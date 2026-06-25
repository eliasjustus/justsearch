/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.inference;

import io.justsearch.configuration.EnvRegistry;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.configuration.resolved.ResolvedPathResolver;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Locale;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Configuration for the InferenceLifecycleManager.
 *
 * <p>Defines paths to model files, server executable, and runtime parameters.
 *
 * @param serverExecutable path to llama-server executable
 * @param modelPath path to main VLM model (e.g., qwen3-vl-8b-instruct-q4_k_m.gguf)
 * @param mmprojPath path to vision projector model (e.g., mmproj-model-f16.gguf)
 * @param serverPort port for llama-server HTTP API
 * @param contextSize context window size (default: 4096)
 * @param gpuLayers number of layers to offload to GPU (default: 0 = CPU mode)
 * @param vduMode when true, server launches with vision-safe flags ({@code -np 1},
 *     {@code --cache-ram 0}) that prevent multi-slot vision errors and prompt cache corruption
 */
public record InferenceConfig(
    Path serverExecutable,
    Path modelPath,
    Path mmprojPath,
    int serverPort,
    int contextSize,
    int gpuLayers,
    boolean vduMode
) {
  private static final Logger log = LoggerFactory.getLogger(InferenceConfig.class);

  public InferenceConfig {
    Objects.requireNonNull(serverExecutable, "serverExecutable is required");
    Objects.requireNonNull(modelPath, "modelPath is required");
    // mmprojPath can be null for text-only models
    if (serverPort <= 0 || serverPort > 65535) {
      throw new IllegalArgumentException("serverPort must be between 1 and 65535");
    }
    if (contextSize <= 0) {
      throw new IllegalArgumentException("contextSize must be positive");
    }
    if (gpuLayers < 0) {
      throw new IllegalArgumentException("gpuLayers must be non-negative");
    }
  }

  /**
   * Creates configuration from environment variables and default paths.
   *
   * <p>Environment variables:
   * <ul>
   *   <li>JUSTSEARCH_SERVER_EXE - path to llama-server.exe</li>
   *   <li>JUSTSEARCH_MODELS_DIR - directory containing model files</li>
   *   <li>JUSTSEARCH_SERVER_PORT - HTTP port (default: 8080)</li>
   *   <li>JUSTSEARCH_CONTEXT_SIZE - context window size (default: 4096)</li>
   *   <li>JUSTSEARCH_GPU_LAYERS - GPU layers to offload (default: 0)</li>
   * </ul>
   *
   * @param baseDir the base directory for resolving relative paths
   * @return configuration based on environment
   */
  public static InferenceConfig fromEnvironment(Path baseDir) {
    log.debug("Creating InferenceConfig from environment");

    ResolvedConfig rc = ConfigStore.global().get();
    Path resolvedBaseDir =
        ResolvedPathResolver.resolveBaseDir(
            rc,
            baseDir != null
                ? baseDir.toAbsolutePath().normalize().toString()
                : System.getProperty("user.dir"));
    log.debug("  Base directory: {}", resolvedBaseDir);

    Path configuredModelsDir = ResolvedPathResolver.resolveModelsDir(rc, resolvedBaseDir);
    log.debug(
        "  Models directory: {} (exists: {})",
        configuredModelsDir,
        Files.isDirectory(configuredModelsDir));

    // Tempdoc 374 sandbox round 4 issue A: serverPort and apiPort both
    // historically defaulted to 8080 — when the head HTTP API binds 8080
    // first, every install's smoke-test llama-server collides on the same
    // port. Default away from apiPort. Users who need 8080 can still set
    // JUSTSEARCH_SERVER_PORT explicitly.
    int port = rc.ports().serverPort();
    if (port <= 0) {
      int apiPort = rc.ports().apiPort();
      port = apiPort > 0 && apiPort != 8081 ? 8081 : 8082;
    }
    int ctxSize = rc.ai().contextSize();
    if (ctxSize <= 0) ctxSize = 4096;
    // Locked decision: CPU fallback must work by default.
    int layers = rc.ai().gpuLayers();
    log.debug("  Server port: {}, context size: {}, GPU layers: {}", port, ctxSize, layers);

    // Tempdoc 374 alpha.13 fix A1: derive CUDA availability from the resolved
    // config instead of shelling out to nvidia-smi. `rc.ai().gpuLayers()` (read
    // at line above) already integrates ordinal-150 auto-detection (driver-API
    // probe via nvcuda.dll), env vars (400), and sysprops (500). When layers
    // > 0, GPU is requested AND available. The previous VramDetector probe
    // shelled out to nvidia-smi.exe — which ships with the full CUDA toolkit,
    // not the driver — and sticky-failed on every host without it on PATH,
    // silently downgrading binary selection to the default (CPU) variant.
    boolean cudaAvailable = layers > 0;
    log.debug("  CUDA available: {} (derived from gpu_layers={})", cudaAvailable, layers);

    // Find llama-server executable (prefer CUDA variant when GPU is available)
    Path serverExe = findServerExecutable(resolvedBaseDir, cudaAvailable);

    // Primary model selection:
    // - Prefer explicit full path override (legacy UI setting): justsearch.llm.model_path / JUSTSEARCH_LLM_MODEL_PATH
    // - Otherwise use modelsDir + VLM_MODEL filename.
    Path llmModelPath = rc.ai().llmModelPath();
    String llmModelPathOverride =
        llmModelPath != null ? llmModelPath.toString() : null;
    boolean usingLlmModelOverride = llmModelPathOverride != null && !llmModelPathOverride.isBlank();

    // Tempdoc 580 Track D / F-009: the extraction VLM is selected as an atomic
    // (model, mmproj) PROFILE, not two independent filenames — so a half-swap
    // (new model, forgotten projector → silent text-only degradation) is
    // unrepresentable. Default profile = QWEN_VL = today's canonical pair.
    // The per-file VLM_MODEL/MMPROJ_MODEL env overrides still win for advanced
    // testing; no auto-discovery of other GGUFs.
    VlmExtractionProfile profile = VlmExtractionProfile.resolve(EnvRegistry.VLM_PROFILE.get());
    log.debug("  VLM extraction profile: {} (set: {})", profile.id(), EnvRegistry.VLM_PROFILE.isSet());
    String vlmModel = nonBlankOr(rc.ai().vlmModel(), profile.vlmModel());
    String mmprojModel = nonBlankOr(rc.ai().mmprojModel(), profile.mmprojModel());

    log.debug("  Model files (from env or default):");
    log.debug("    VLM: {} (set: {})", vlmModel, EnvRegistry.VLM_MODEL.isSet());
    log.debug("    MMProj: {} (set: {})", mmprojModel, EnvRegistry.MMPROJ_MODEL.isSet());

    Path modelPath;
    Path associatedModelsDir;
    if (usingLlmModelOverride) {
      Path raw = Path.of(llmModelPathOverride.trim());
      modelPath = raw.isAbsolute() ? raw : resolvedBaseDir.resolve(raw);
      associatedModelsDir =
          modelPath.getParent() != null ? modelPath.getParent() : configuredModelsDir;
      log.debug(
          "  Using LLM model override: {} (associatedModelsDir={})",
          modelPath,
          associatedModelsDir);
    } else {
      modelPath = configuredModelsDir.resolve(vlmModel);
      associatedModelsDir = configuredModelsDir;
    }

    Path mmprojPath;
    if (usingLlmModelOverride && !EnvRegistry.MMPROJ_MODEL.isSet()) {
      // When the user explicitly picks a model file path, we should NOT assume a specific projector.
      // Passing a mismatched mmproj can cause llama-server startup to fail.
      log.info(
          "LLM model override is set; mmproj not explicitly configured. Starting in text-only mode (mmproj disabled). "
              + "Set {} / {} to enable vision.",
          EnvRegistry.MMPROJ_MODEL.sysProp(),
          EnvRegistry.MMPROJ_MODEL.envVar());
      mmprojPath = null;
    } else {
      mmprojPath = resolveOptionalModelPath(mmprojModel, associatedModelsDir);
      if (mmprojPath != null && !Files.exists(mmprojPath)) {
        log.warn("MMProj model not found at {}. Vision features will be disabled.", mmprojPath);
        mmprojPath = null;
      }
    }

    log.debug("  Resolved paths:");
    log.debug("    Server executable: {} (exists: {})", serverExe, Files.exists(serverExe));
    log.debug("    Model path: {} (exists: {})", modelPath, Files.exists(modelPath));
    log.debug(
        "    MMProj path: {} (exists: {})",
        mmprojPath,
        mmprojPath != null && Files.exists(mmprojPath));

    return new InferenceConfig(
        serverExe,
        modelPath,
        mmprojPath,
        port,
        ctxSize,
        layers,
        false // vduMode — normal startup, not VDU batch
    );
  }

  private static String nonBlankOr(String value, String fallback) {
    return value != null && !value.isBlank() ? value : fallback;
  }

  private static Path resolveOptionalModelPath(String raw, Path modelsDir) {
    if (raw == null) return null;
    String trimmed = raw.trim();
    if (trimmed.isBlank()) return null;
    if ("none".equalsIgnoreCase(trimmed) || "null".equalsIgnoreCase(trimmed)) return null;

    Path p = Path.of(trimmed);
    if (p.isAbsolute()) {
      return p;
    }
    // Support relative paths that already exist (relative to CWD) for dev.
    if (Files.exists(p)) {
      return p;
    }
    return modelsDir.resolve(p);
  }

  /**
   * VLM extraction profiles (tempdoc 580 Track D / F-009). Each profile bundles the document
   * extraction model with its matching multimodal projector as ONE atomic unit, so a swap
   * cannot leave the projector behind (which would silently degrade the runtime to text-only —
   * the failure mode the per-file {@code VLM_MODEL}/{@code MMPROJ_MODEL} override allows).
   *
   * <p>Selected by the {@code justsearch.vlm.profile} / {@code JUSTSEARCH_VLM_PROFILE} key; the
   * default is {@link #QWEN_VL}, which is byte-for-byte today's canonical pair (so the default
   * runtime behavior is unchanged). {@link #PADDLE_OCR_VL} is the F-009 pilot candidate
   * (PaddleOCR-VL-1.6 — ~1B, Apache-2.0, llama.cpp-runnable). The pilot is gated on the
   * retrieval-aware extraction eval (jseval {@code extraction-gate}), NOT the OCR leaderboard
   * number — per §14.4 / InduOCRBench: OCR char-accuracy is a poor proxy for downstream nDCG.
   */
  enum VlmExtractionProfile {
    /** Default — the canonical Qwen3.5-9B + mmproj pair. Today's behavior. */
    QWEN_VL("qwen-vl", "Qwen_Qwen3.5-9B-Q4_K_M.gguf", "mmproj-F16.gguf"),
    /** F-009 pilot — PaddleOCR-VL-1.6 (eval-gated, not yet default). */
    PADDLE_OCR_VL("paddle-ocr-vl", "PaddleOCR-VL-1.6-Q4_K_M.gguf", "PaddleOCR-VL-1.6-mmproj-F16.gguf");

    private final String id;
    private final String vlmModel;
    private final String mmprojModel;

    VlmExtractionProfile(String id, String vlmModel, String mmprojModel) {
      this.id = id;
      this.vlmModel = vlmModel;
      this.mmprojModel = mmprojModel;
    }

    String id() {
      return id;
    }

    String vlmModel() {
      return vlmModel;
    }

    String mmprojModel() {
      return mmprojModel;
    }

    /** Resolve a profile from its id (case-insensitive); unknown/blank → the {@link #QWEN_VL} default. */
    static VlmExtractionProfile resolve(java.util.Optional<String> rawId) {
      if (rawId == null || rawId.isEmpty()) {
        return QWEN_VL;
      }
      String wanted = rawId.get().trim();
      if (wanted.isBlank()) {
        return QWEN_VL;
      }
      for (VlmExtractionProfile p : values()) {
        if (p.id.equalsIgnoreCase(wanted) || p.name().equalsIgnoreCase(wanted)) {
          return p;
        }
      }
      log.warn(
          "Unknown VLM extraction profile '{}'; falling back to the '{}' default. Known profiles: {}.",
          wanted,
          QWEN_VL.id,
          java.util.Arrays.stream(values()).map(VlmExtractionProfile::id).toList());
      return QWEN_VL;
    }
  }

  /**
   * Creates a builder for InferenceConfig.
   */
  public static Builder builder() {
    return new Builder();
  }

  private static Path findServerExecutable(Path baseDir, boolean preferCudaVariant) {
    log.debug("Finding llama-server executable (preferCuda={})...", preferCudaVariant);

    ConfigStore cs = ConfigStore.globalOrNull();
    ResolvedConfig rc = cs != null ? cs.get() : null;
    String envPath = cs != null && cs.get().ai().serverExe() != null
        ? cs.get().ai().serverExe().toString() : null;
    if (envPath != null && !envPath.isBlank()) {
      Path p = Path.of(envPath);
      log.debug("  JUSTSEARCH_SERVER_EXE set to: {}", envPath);
      if (Files.exists(p)) {
        log.debug("  Found via environment variable: {}", p);
        return p;
      } else {
        log.warn("  JUSTSEARCH_SERVER_EXE path does not exist: {}", p);
      }
    } else {
      log.debug("  JUSTSEARCH_SERVER_EXE not set");
    }

    Path normalizedBaseDir =
        baseDir != null
            ? baseDir.toAbsolutePath().normalize()
            : ResolvedPathResolver.resolveBaseDir(rc, System.getProperty("user.dir"));
    Path found = findExistingServerExecutable(normalizedBaseDir, preferCudaVariant);
    if (found != null) {
      return found;
    }

    Path explicitRepoRoot = ResolvedPathResolver.resolveExplicitRepoRoot(rc);
    if (explicitRepoRoot != null && !explicitRepoRoot.equals(normalizedBaseDir)) {
      Path repoRootFound = findExistingServerExecutable(explicitRepoRoot, preferCudaVariant);
      if (repoRootFound != null) {
        return repoRootFound;
      }
      // 369: Dev layout — Tauri shell bundles the binary under its resources.
      // Reuses findExistingServerExecutable so CUDA variant selection applies.
      Path devBase = explicitRepoRoot.resolve(
          "modules/shell/src-tauri/resources/headless");
      Path devFound = findExistingServerExecutable(devBase, preferCudaVariant);
      if (devFound != null) {
        log.info("  Found via dev layout (Tauri resources): {}", devFound);
        return devFound;
      }
      return canonicalServerExecutable(explicitRepoRoot);
    }

    return canonicalServerExecutable(normalizedBaseDir);
  }

  private static Path findExistingServerExecutable(Path baseDir, boolean preferCudaVariant) {
    if (baseDir == null) {
      return null;
    }
    Path nativeBin = baseDir.resolve("native-bin").resolve("llama-server");
    log.debug("  Searching in: {} (exists: {})", nativeBin, Files.isDirectory(nativeBin));

    // 1. Check canonical baseline path FIRST (deterministic, preferred in release builds)
    Path directPath = nativeBin.resolve("llama-server.exe");
    if (Files.exists(directPath)) {
      log.debug("  Found baseline at canonical path: {}", directPath);
      // If GPU requested, still check for CUDA variant which is better
      if (preferCudaVariant) {
        Path cudaVariant = findCudaVariant(nativeBin);
        if (cudaVariant != null) {
          log.info("  Preferring CUDA variant over baseline: {}", cudaVariant);
          return cudaVariant;
        }
      }
      return directPath;
    }

    // 2. Check CUDA variant in variants/ directory (dev mode — canonical path often absent)
    if (preferCudaVariant) {
      Path cudaVariant = findCudaVariant(nativeBin);
      if (cudaVariant != null) {
        return cudaVariant;
      }
    }

    // 3. Scan subdirectories (SORTED for determinism, skip variants/)
    if (Files.isDirectory(nativeBin)) {
      try (var dirs = Files.list(nativeBin)) {
        var found = dirs
            .filter(Files::isDirectory)
            .filter(d -> !"variants".equalsIgnoreCase(d.getFileName().toString()))
            .sorted(Comparator.comparing(p -> p.getFileName().toString().toLowerCase(Locale.ROOT)))
            .peek(d -> log.debug("    Checking subdirectory: {}", d))
            .map(d -> d.resolve("llama-server.exe"))
            .filter(Files::exists)
            .findFirst();
        if (found.isPresent()) {
          log.debug("  Found in subdirectory (legacy layout): {}", found.get());
          return found.get();
        }
      } catch (IOException e) {
        log.debug("  Error scanning subdirectories: {}", e.getMessage());
      }
    }

    // 4. Last resort: any variant (CUDA binary works for CPU too, just larger)
    Path anyVariant = findCudaVariant(nativeBin);
    if (anyVariant != null) {
      log.info("  No baseline found; falling back to variant: {}", anyVariant);
      return anyVariant;
    }

    return null;
  }

  /**
   * Finds the CUDA variant executable under {@code variants/cuda12/}.
   *
   * @return path to the variant executable, or null if not found
   */
  private static Path findCudaVariant(Path nativeBin) {
    Path variantsDir = nativeBin.resolve("variants");
    if (!Files.isDirectory(variantsDir)) {
      return null;
    }
    // Prefer cuda12 explicitly (deterministic, no scanning ambiguity)
    Path cuda12 = variantsDir.resolve("cuda12").resolve("llama-server.exe");
    if (Files.exists(cuda12)) {
      log.debug("  Found CUDA variant: {}", cuda12);
      return cuda12;
    }
    // Future: scan variants/ for other CUDA versions if cuda12 not present
    return null;
  }

  private static Path canonicalServerExecutable(Path baseDir) {
    Path directPath = baseDir.resolve("native-bin").resolve("llama-server").resolve("llama-server.exe");
    log.debug("  Using fallback path: {} (exists: {})", directPath, Files.exists(directPath));
    return directPath;
  }

  /**
   * Returns a copy of this config with {@code vduMode} set to the given value. All other fields are
   * preserved. Used by the lifecycle manager to toggle between normal and VDU server configurations.
   */
  public InferenceConfig withVduMode(boolean vdu) {
    if (vdu == this.vduMode) return this;
    return new InferenceConfig(
        serverExecutable, modelPath, mmprojPath, serverPort, contextSize, gpuLayers, vdu);
  }

  /**
   * Validates that all required files exist.
   *
   * @throws IllegalStateException if required files are missing
   */
  public void validate() {
    if (!Files.exists(serverExecutable)) {
      throw new IllegalStateException("llama-server executable not found: " + serverExecutable);
    }
    if (!Files.exists(modelPath)) {
      throw new IllegalStateException("Model file not found: " + modelPath);
    }
    if (mmprojPath != null && !Files.exists(mmprojPath)) {
      throw new IllegalStateException("Vision projector not found: " + mmprojPath);
    }
  }

  /**
   * Builder for InferenceConfig.
   */
  public static final class Builder {
    private Path serverExecutable;
    private Path modelPath;
    private Path mmprojPath;
    private int serverPort = 8080;
    private int contextSize = 4096;
    private int gpuLayers = 0;
    private boolean vduMode = false;

    private Builder() {}

    public Builder serverExecutable(Path serverExecutable) {
      this.serverExecutable = serverExecutable;
      return this;
    }

    public Builder modelPath(Path modelPath) {
      this.modelPath = modelPath;
      return this;
    }

    public Builder mmprojPath(Path mmprojPath) {
      this.mmprojPath = mmprojPath;
      return this;
    }

    public Builder serverPort(int serverPort) {
      this.serverPort = serverPort;
      return this;
    }

    public Builder contextSize(int contextSize) {
      this.contextSize = contextSize;
      return this;
    }

    public Builder gpuLayers(int gpuLayers) {
      this.gpuLayers = gpuLayers;
      return this;
    }

    public Builder vduMode(boolean vduMode) {
      this.vduMode = vduMode;
      return this;
    }

    public InferenceConfig build() {
      return new InferenceConfig(
          serverExecutable,
          modelPath,
          mmprojPath,
          serverPort,
          contextSize,
          gpuLayers,
          vduMode
      );
    }
  }
}
