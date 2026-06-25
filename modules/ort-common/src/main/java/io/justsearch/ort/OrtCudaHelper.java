/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ort;

import io.justsearch.configuration.ConfigPrecedence;
import io.justsearch.configuration.EnvRegistry;
import io.justsearch.configuration.resolved.ConfigStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shared CUDA DLL initialization utilities for ONNX Runtime encoders.
 *
 * <p>Used by both {@code OnnxEmbeddingEncoder} and {@code SpladeEncoder} to preload CUDA
 * dependency DLLs and copy them to the ORT extraction directory on Windows.
 *
 * <p>On Windows, ORT extracts native libraries (including {@code onnxruntime_providers_cuda.dll})
 * to a temp directory ({@code %TEMP%/onnxruntime-java<random>/}). When Windows {@code LoadLibrary}
 * loads that DLL, it resolves transitive dependencies (cuDNN, cuFFT, cuBLAS) by searching the
 * DLL's directory — NOT already-loaded modules. Therefore, {@code System.load()} preloading alone
 * is insufficient; the dependency DLLs must physically exist in the ORT temp directory.
 */
public final class OrtCudaHelper {

  private static final Logger log = LoggerFactory.getLogger(OrtCudaHelper.class);

  /**
   * The pinned CUDA toolkit major version (single source — keep in lockstep with the DLL list
   * below). Surfaced into the benchmark-release hardware projection so an eval run records the
   * CUDA major it linked against (tempdoc 623 U7). The driver version is reported separately via
   * NVML; this is the toolkit pin, not the runtime/driver version.
   */
  public static final String CUDA_TOOLKIT_MAJOR = "12";

  /**
   * CUDA dependency DLLs in load order. cuBLAS depends on cuBLASLt, so cuBLASLt must come first.
   * Version-pinned to CUDA 12 / cuDNN 9 / cuFFT 11. Update this list when upgrading CUDA toolkit.
   */
  static final List<String> CUDA_DEPENDENCY_DLL_ORDER =
      List.of(
          "cudart64_12.dll",
          "cublasLt64_12.dll",
          "cublas64_12.dll",
          "cufft64_11.dll",
          // Tempdoc 374 alpha.16: providers_cuda.dll's full CUDA 12.x dependency
          // surface includes cuRand/cuSparse/cuSolver/nvJitLink. The alpha.16
          // cuda-runtime Install AI package now ships these (curated NVIDIA
          // redist), so the copy can include them in the ORT temp dir alongside
          // the rest of the CUDA companions. Missing-from-disk entries are
          // skipped silently (candidateCudaDependencyDlls filters by Files.exists),
          // so older installs without these files behave the same as before.
          "curand64_10.dll",
          "cusparse64_12.dll",
          "cusolver64_11.dll",
          "nvJitLink_120_0.dll",
          "cudnn64_9.dll",
          "cudnn_graph64_9.dll",
          "cudnn_heuristic64_9.dll",
          "cudnn_ops64_9.dll",
          "cudnn_cnn64_9.dll",
          "cudnn_adv64_9.dll",
          "cudnn_engines_runtime_compiled64_9.dll",
          "cudnn_engines_precompiled64_9.dll");

  private static final Set<String> PRELOADED_NATIVE_DLLS = ConcurrentHashMap.newKeySet();
  private static volatile boolean legacyKeyWarningLogged;

  private OrtCudaHelper() {}

  /**
   * Prepares CUDA dependencies for ORT GPU session creation.
   *
   * <p>Performs two steps: (1) preloads CUDA DLLs via {@code System.load()}, and (2) copies them
   * to the ORT temp extraction directory so Windows {@code LoadLibrary} can resolve transitive
   * dependencies.
   *
   * @param nativePath directory containing CUDA dependency DLLs
   */
  public static void prepareCudaDependencies(Path nativePath) {
    preloadCudaDependencies(nativePath);
    copyCudaDllsToOrtTempDir(nativePath);
  }

  /**
   * Checks for missing core CUDA DLLs on the file system (Windows only, advisory).
   *
   * <p><b>Use case:</b> validating an externally-set {@code JUSTSEARCH_NATIVE_PATH} that
   * is supposed to be a self-contained ORT GPU directory — i.e. a directory that
   * holds BOTH the ORT EP DLLs ({@code onnxruntime_providers_cuda.dll},
   * {@code onnxruntime_providers_shared.dll}) AND the CUDA runtime DLLs.
   *
   * <p>Do <b>not</b> use this for validating the bundled
   * {@code …\native-bin\llama-server\variants\cuda12\} directory — that dir
   * ships CUDA runtime DLLs only; the ORT EP DLLs auto-extract from
   * {@code onnxruntime-gpu.jar} to a JVM temp dir at runtime. For that case,
   * use {@link #checkMissingCudaRuntimeDlls(Path)} instead. Tempdoc 374
   * alpha.13 round-5 finding: the alpha.13 fix B used this method as a
   * defensive guard against the cuda12 dir, which always tripped because
   * the ORT EP DLLs are never in cuda12 — silently disabled the entire fix.
   *
   * @param searchPath directory to check for DLLs
   * @return list of missing DLL names (empty if all present or not on Windows)
   */
  public static List<String> checkMissingCudaDlls(Path searchPath) {
    String osName = ConfigPrecedence.envOrProperty(null, "os.name", "");
    if (!osName.toLowerCase(Locale.ROOT).contains("win")) {
      return List.of();
    }
    if (searchPath == null || !Files.isDirectory(searchPath)) {
      return List.of();
    }
    List<String> missing = new ArrayList<>();
    for (String dll :
        List.of(
            "onnxruntime_providers_cuda.dll",
            "onnxruntime_providers_shared.dll",
            "cudart64_12.dll",
            "cublas64_12.dll",
            "cublasLt64_12.dll")) {
      if (!Files.exists(searchPath.resolve(dll))) {
        missing.add(dll);
      }
    }
    return missing;
  }

  /**
   * Checks for missing CUDA <em>runtime</em> DLLs in a search path. Distinct from
   * {@link #checkMissingCudaDlls} — this method validates only the runtime DLLs
   * that ship next to llama-server's cuda12 binary
   * ({@code cudart64_12.dll}, {@code cublas64_12.dll}, {@code cublasLt64_12.dll}).
   *
   * <p><b>Use case:</b> validating the bundled
   * {@code …\native-bin\llama-server\variants\cuda12\} directory before pointing
   * ORT at it via {@code justsearch.onnxruntime.native_path}. ORT's CUDA EP DLL
   * (auto-extracted from the {@code onnxruntime-gpu} JAR to a JVM temp dir)
   * depends on these runtime libraries at LoadLibrary time;
   * {@link #copyCudaDllsToOrtTempDir} copies them next to the EP DLL so the
   * Windows loader can resolve the dependency chain.
   *
   * <p>Tempdoc 374 alpha.14 fix: replaces the broken use of
   * {@link #checkMissingCudaDlls} as a precondition guard.
   *
   * @param searchPath directory to check for DLLs
   * @return list of missing DLL names (empty if all present or not on Windows)
   */
  public static List<String> checkMissingCudaRuntimeDlls(Path searchPath) {
    String osName = ConfigPrecedence.envOrProperty(null, "os.name", "");
    if (!osName.toLowerCase(Locale.ROOT).contains("win")) {
      return List.of();
    }
    if (searchPath == null || !Files.isDirectory(searchPath)) {
      return List.of();
    }
    List<String> missing = new ArrayList<>();
    for (String dll : List.of("cudart64_12.dll", "cublas64_12.dll", "cublasLt64_12.dll")) {
      if (!Files.exists(searchPath.resolve(dll))) {
        missing.add(dll);
      }
    }
    return missing;
  }

  /**
   * Resolves the ORT native path from environment/system properties, falling back to the given
   * default.
   *
   * @param defaultPath fallback path (typically the model directory)
   * @return resolved native path
   */
  public static Path resolveOrtNativePath(Path defaultPath) {
    // 1. First-class resolved config (centralized, propagated via worker snapshot)
    ConfigStore store = ConfigStore.globalOrNull();
    if (store != null) {
      Path configured = store.get().paths().ortNativePath();
      if (configured != null && Files.isDirectory(configured)) {
        log.info("ORT native path resolved via ConfigStore: {}", configured);
        return configured;
      }
      log.debug("ORT native path: ConfigStore returned {} (isDir={}), falling through",
          configured, configured != null && Files.isDirectory(configured));
    } else {
      log.debug("ORT native path: ConfigStore is null, falling through");
    }
    // 2. Canonical key via EnvRegistry, then deprecated key fallback for backwards compatibility.
    String ortNativePathStr = EnvRegistry.ORT_NATIVE_PATH.getString(null);
    if (ortNativePathStr == null || ortNativePathStr.isBlank()) {
      ortNativePathStr =
          ConfigPrecedence.envOrProperty(
              "JUSTSEARCH_NATIVE_PATH", "onnxruntime.native.path", null);
    }
    if (ortNativePathStr == null || ortNativePathStr.isBlank()) {
      log.info("ORT native path: no env/sysprop configured, using default: {}", defaultPath);
      return defaultPath;
    }
    try {
      Path configuredPath = Path.of(ortNativePathStr).toAbsolutePath().normalize();
      if (Files.isDirectory(configuredPath)) {
        if (!legacyKeyWarningLogged) {
          legacyKeyWarningLogged = true;
          log.warn(
              "ORT native path resolved via deprecated key (JUSTSEARCH_NATIVE_PATH / "
                  + "onnxruntime.native.path). Migrate to JUSTSEARCH_ONNXRUNTIME_NATIVE_PATH "
                  + "/ justsearch.onnxruntime.native_path. Resolved path: {}",
              configuredPath);
        }
        log.info("ORT native path resolved via env/sysprop: {}", configuredPath);
        return configuredPath;
      }
      log.info("ORT native path: env/sysprop value '{}' is not a directory, using default: {}",
          configuredPath, defaultPath);
    } catch (Exception e) {
      log.debug(
          "Ignoring invalid ORT native path '{}': {}", ortNativePathStr, e.getMessage());
    }
    return defaultPath;
  }

  /**
   * Returns the list of candidate CUDA dependency DLL paths found in the given directory.
   *
   * @param searchPath directory to scan
   * @return list of absolute, normalized paths to existing DLLs (in load order)
   */
  public static List<Path> candidateCudaDependencyDlls(Path searchPath) {
    if (searchPath == null || !Files.isDirectory(searchPath)) {
      return List.of();
    }
    List<Path> dlls = new ArrayList<>();
    for (String dllName : CUDA_DEPENDENCY_DLL_ORDER) {
      Path dllPath = searchPath.resolve(dllName);
      if (Files.exists(dllPath)) {
        dlls.add(dllPath.toAbsolutePath().normalize());
      }
    }
    return dlls;
  }

  // --- Internal ---

  private static void preloadCudaDependencies(Path searchPath) {
    for (Path dllPath : candidateCudaDependencyDlls(searchPath)) {
      String key = dllPath.toString();
      if (!PRELOADED_NATIVE_DLLS.add(key)) {
        continue;
      }
      try {
        System.load(key);
        log.debug("Preloaded CUDA dependency {}", dllPath);
      } catch (UnsatisfiedLinkError | SecurityException e) {
        PRELOADED_NATIVE_DLLS.remove(key);
        log.debug("Failed to preload CUDA dependency {}: {}", dllPath, e.getMessage());
      }
    }
  }

  /**
   * Copies CUDA dependency DLLs into the ORT temp extraction directory so that Windows
   * {@code LoadLibrary} can find them when loading {@code onnxruntime_providers_cuda.dll}.
   *
   * <p><b>TOCTOU note:</b> This method identifies the ORT extraction directory by scanning
   * {@code %TEMP%} for the most recently modified {@code onnxruntime-java*} directory containing
   * {@code onnxruntime.dll}. This heuristic assumes a single ORT-using JVM per machine. If multiple
   * JVMs run concurrently, or stale directories have newer timestamps, the wrong directory may be
   * selected. In practice, only one JustSearch Worker runs per machine, so the risk is low.
   *
   * <p>The check-then-copy on individual DLLs ({@code Files.exists} → {@code Files.copy}) also has
   * a narrow TOCTOU window. This is benign: concurrent copies of the same DLL would produce
   * identical content, and {@code Files.copy} throws {@code FileAlreadyExistsException} which is
   * caught and logged.
   */
  private static void copyCudaDllsToOrtTempDir(Path searchPath) {
    if (searchPath == null || !Files.isDirectory(searchPath)) {
      return;
    }
    String osName = ConfigPrecedence.envOrProperty(null, "os.name", "");
    if (!osName.toLowerCase(Locale.ROOT).contains("win")) {
      return;
    }

    Path tmpDir = Path.of(ConfigPrecedence.envOrProperty(null, "java.io.tmpdir", "."));
    Path bestDir = null;
    long bestMtime = 0;
    try (var dirStream = Files.newDirectoryStream(tmpDir, "onnxruntime-java*")) {
      for (Path candidate : dirStream) {
        if (!Files.isDirectory(candidate)) {
          continue;
        }
        Path ortDll = candidate.resolve("onnxruntime.dll");
        if (!Files.exists(ortDll)) {
          continue;
        }
        long mtime = Files.getLastModifiedTime(ortDll).toMillis();
        if (mtime > bestMtime) {
          bestMtime = mtime;
          bestDir = candidate;
        }
      }
    } catch (IOException e) {
      log.debug("Failed to scan for ORT extraction directory: {}", e.getMessage());
      return;
    }

    if (bestDir == null) {
      log.debug("No ORT extraction directory found in {}", tmpDir);
      return;
    }

    log.debug("Found ORT extraction directory: {}", bestDir);
    int copied = 0;
    for (Path dllPath : candidateCudaDependencyDlls(searchPath)) {
      Path target = bestDir.resolve(dllPath.getFileName().toString());
      if (!Files.exists(target)) {
        try {
          Files.copy(dllPath, target);
          copied++;
          log.debug("Copied CUDA DLL to ORT temp dir: {}", target.getFileName());
        } catch (IOException e) {
          log.debug(
              "Failed to copy CUDA DLL {} to ORT temp dir: {}",
              dllPath.getFileName(),
              e.getMessage());
        }
      }
    }
    if (copied > 0) {
      log.info(
          "Copied {} CUDA dependency DLLs to ORT extraction directory {}", copied, bestDir);
    }
  }
}
