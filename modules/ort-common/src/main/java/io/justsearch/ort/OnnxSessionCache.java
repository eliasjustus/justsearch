/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ort;

import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.OrtSession.SessionOptions;
import ai.onnxruntime.OrtSession.SessionOptions.OptLevel;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Creates ONNX Runtime sessions with per-machine graph optimization caching.
 *
 * <p>On first run, the model is optimized at {@code EXTENDED_OPT} level and the optimized graph is
 * saved alongside the original model (e.g., {@code model.onnx.optimized}). On subsequent runs, the
 * pre-optimized graph is loaded with {@code NO_OPT} (skipping the 1-5s optimization step).
 *
 * <p>The optimized cache is invalidated when the source model changes (detected via mtime+size
 * sidecar) or when the ORT version changes.
 */
public final class OnnxSessionCache {
  private static final Logger log = LoggerFactory.getLogger(OnnxSessionCache.class);

  private static final String ORT_VERSION = getOrtVersion();
  private static final String OPTIMIZED_SUFFIX = ".optimized";
  private static final String SIDECAR_SUFFIX = ".opt-meta";
  private static final String GPU_OPTIMIZED_SUFFIX = ".cuda.optimized";
  private static final String GPU_SIDECAR_SUFFIX = ".cuda.opt-meta";
  private static final String GPU_EP_TAG = "cuda";

  private OnnxSessionCache() {}

  /**
   * Returns the conventional path for a CUDA-EP pre-optimized graph cache next to a source model.
   * Distinct from the CPU {@code .optimized} suffix — the optimized graph is EP-specific.
   */
  public static Path gpuOptimizedPath(Path modelPath) {
    return modelPath.resolveSibling(modelPath.getFileName() + GPU_OPTIMIZED_SUFFIX);
  }

  /**
   * Creates a CUDA-EP ORT session with per-machine graph-optimization caching.
   *
   * <p>On first run with a given (model, ORT version) pair, optimises the graph at {@code
   * EXTENDED_OPT} under the CUDA EP and writes {@code model.onnx.cuda.optimized} as a byproduct
   * (via {@link SessionOptions#setOptimizedModelFilePath}). Subsequent runs load the pre-optimised
   * graph at {@code NO_OPT}, skipping the ~6 s optimisation pass that previously ran on every cold
   * backend start (tempdoc 391 § Issue B follow-up).
   *
   * <p>The caller must provide {@code opts} pre-configured with the CUDA provider + any production
   * session options. The CUDA-EP cache is distinct from the CPU {@code .optimized} cache because
   * ORT graph optimisation is EP-aware — a CPU-optimised graph is not directly reusable by CUDA
   * and vice versa.
   *
   * @param env the ORT environment
   * @param modelPath path to the source {@code .onnx} model
   * @param opts session options pre-configured with CUDA EP (caller owns lifecycle)
   * @return an ORT session (either from cached optimized graph or freshly optimised + cached)
   * @throws OrtException if session creation fails
   */
  public static OrtSession createCachedGpuSession(
      OrtEnvironment env, Path modelPath, SessionOptions opts) throws OrtException {
    Path optimizedPath = gpuOptimizedPath(modelPath);

    if (isGpuOptimizedCacheValid(modelPath, optimizedPath)) {
      log.info(
          "Loading pre-optimized CUDA-EP ONNX model: {} (skipping graph optimization)",
          optimizedPath.getFileName());
      long startMs = System.currentTimeMillis();
      opts.setOptimizationLevel(OptLevel.NO_OPT);
      OrtSession session = env.createSession(optimizedPath.toString(), opts);
      log.info(
          "Pre-optimized CUDA-EP model loaded in {}ms: {}",
          System.currentTimeMillis() - startMs,
          optimizedPath.getFileName());
      return session;
    }

    log.info(
        "Optimizing CUDA-EP ONNX model (first run or cache stale): {}", modelPath.getFileName());
    long startMs = System.currentTimeMillis();
    opts.setOptimizationLevel(OptLevel.EXTENDED_OPT);
    opts.setOptimizedModelFilePath(optimizedPath.toString());
    OrtSession session;
    try {
      session = env.createSession(modelPath.toString(), opts);
    } catch (OrtException e) {
      // Clean up partial cache on failure — otherwise a truncated .cuda.optimized
      // could fool the next startup's cache-valid check (sidecar not yet written,
      // so the check returns false, but better to be explicit).
      try {
        Files.deleteIfExists(optimizedPath);
      } catch (IOException io) {
        log.debug("Failed to clean up partial CUDA-EP cache: {}", io.getMessage());
      }
      throw e;
    }
    long elapsedMs = System.currentTimeMillis() - startMs;
    log.info(
        "CUDA-EP ONNX optimization complete in {}ms: {}", elapsedMs, modelPath.getFileName());

    writeGpuSidecar(modelPath);
    return session;
  }

  /**
   * Creates an ONNX session, using a cached optimized graph if available and valid.
   *
   * @param env the ORT environment
   * @param modelPath path to the source .onnx model file
   * @return an ORT session (either from cached optimized graph or freshly optimized)
   * @throws OrtException if session creation fails
   */
  public static OrtSession createCachedSession(OrtEnvironment env, Path modelPath)
      throws OrtException {
    return createCachedSession(env, modelPath, null);
  }

  /**
   * Creates an ONNX session with additional session options, using a cached optimized graph if
   * available. Uses {@code EXTENDED_OPT} by default.
   *
   * @param env the ORT environment
   * @param modelPath path to the source .onnx model file
   * @param existingOpts session options to apply (e.g., CUDA provider); null for defaults. Caller
   *     owns the lifecycle of these options.
   * @return an ORT session
   * @throws OrtException if session creation fails
   */
  public static OrtSession createCachedSession(
      OrtEnvironment env, Path modelPath, SessionOptions existingOpts) throws OrtException {
    return createCachedSession(env, modelPath, existingOpts, OptLevel.EXTENDED_OPT);
  }

  /**
   * Creates an ONNX session with explicit optimization level control.
   *
   * <p>Use {@code BASIC_OPT} for FP16 models that must run on CPU (avoids the 30+ min
   * EXTENDED_OPT catastrophe where ORT inserts thousands of Cast FP16→FP32 nodes). Use {@code
   * EXTENDED_OPT} (the default) for FP32/INT8 models and all GPU sessions.
   *
   * @param env the ORT environment
   * @param modelPath path to the source .onnx model file
   * @param existingOpts session options to apply (e.g., CUDA provider); null for defaults
   * @param minOptLevel the optimization level to use when creating a fresh optimized graph
   * @return an ORT session
   * @throws OrtException if session creation fails
   */
  public static OrtSession createCachedSession(
      OrtEnvironment env, Path modelPath, SessionOptions existingOpts, OptLevel minOptLevel)
      throws OrtException {
    Path optimizedPath = modelPath.resolveSibling(modelPath.getFileName() + OPTIMIZED_SUFFIX);

    if (isOptimizedCacheValid(modelPath, optimizedPath)) {
      log.info(
          "Loading pre-optimized ONNX model: {} (skipping graph optimization)",
          optimizedPath.getFileName());
      long startMs = System.currentTimeMillis();
      OrtSession session = loadWithDisabledOptimization(env, optimizedPath, existingOpts);
      log.info(
          "Pre-optimized model loaded in {}ms: {}",
          System.currentTimeMillis() - startMs,
          optimizedPath.getFileName());
      return session;
    }

    // First run or cache invalidated: optimize and cache.
    log.info(
        "Optimizing ONNX model (first run or cache stale): {} [optLevel={}]",
        modelPath.getFileName(),
        minOptLevel);
    long startMs = System.currentTimeMillis();
    OrtSession session =
        optimizeAndCache(env, modelPath, optimizedPath.toString(), existingOpts, minOptLevel);
    long elapsedMs = System.currentTimeMillis() - startMs;
    log.info("ONNX optimization complete in {}ms: {}", elapsedMs, modelPath.getFileName());

    writeSidecar(modelPath);
    return session;
  }

  private static OrtSession loadWithDisabledOptimization(
      OrtEnvironment env, Path optimizedPath, SessionOptions existingOpts) throws OrtException {
    if (existingOpts != null) {
      existingOpts.setOptimizationLevel(OptLevel.NO_OPT);
      return env.createSession(optimizedPath.toString(), existingOpts);
    }
    try (SessionOptions opts = new SessionOptions()) {
      opts.setOptimizationLevel(OptLevel.NO_OPT);
      return env.createSession(optimizedPath.toString(), opts);
    }
  }

  private static OrtSession optimizeAndCache(
      OrtEnvironment env,
      Path modelPath,
      String optimizedPathStr,
      SessionOptions existingOpts,
      OptLevel optLevel)
      throws OrtException {
    if (existingOpts != null) {
      existingOpts.setOptimizationLevel(optLevel);
      existingOpts.setOptimizedModelFilePath(optimizedPathStr);
      return env.createSession(modelPath.toString(), existingOpts);
    }
    try (SessionOptions opts = new SessionOptions()) {
      opts.setOptimizationLevel(optLevel);
      opts.setOptimizedModelFilePath(optimizedPathStr);
      return env.createSession(modelPath.toString(), opts);
    }
  }

  private static boolean isOptimizedCacheValid(Path modelPath, Path optimizedPath) {
    if (!Files.isRegularFile(optimizedPath)) {
      return false;
    }
    Path sidecar = modelPath.resolveSibling(modelPath.getFileName() + SIDECAR_SUFFIX);
    if (!Files.isRegularFile(sidecar)) {
      return false;
    }
    try {
      String content = Files.readString(sidecar).strip();
      long mtime = -1;
      long size = -1;
      String ortVer = null;
      for (String part : content.split("\\s+")) {
        if (part.startsWith("mtime:")) {
          mtime = Long.parseLong(part.substring("mtime:".length()));
        } else if (part.startsWith("size:")) {
          size = Long.parseLong(part.substring("size:".length()));
        } else if (part.startsWith("ort:")) {
          ortVer = part.substring("ort:".length());
        }
      }

      long actualMtime = Files.getLastModifiedTime(modelPath).toMillis();
      long actualSize = Files.size(modelPath);

      if (mtime != actualMtime || size != actualSize) {
        log.debug(
            "ONNX optimized cache stale: mtime {}→{}, size {}→{}",
            mtime,
            actualMtime,
            size,
            actualSize);
        return false;
      }
      if (ortVer != null && !ortVer.equals(ORT_VERSION)) {
        log.debug("ONNX optimized cache stale: ORT version {}→{}", ortVer, ORT_VERSION);
        return false;
      }
      return true;
    } catch (IOException | NumberFormatException e) {
      log.debug("Failed to validate ONNX optimized cache: {}", e.getMessage());
      return false;
    }
  }

  private static void writeSidecar(Path modelPath) {
    Path sidecar = modelPath.resolveSibling(modelPath.getFileName() + SIDECAR_SUFFIX);
    try {
      long mtime = Files.getLastModifiedTime(modelPath).toMillis();
      long size = Files.size(modelPath);
      String content = "mtime:" + mtime + " size:" + size + " ort:" + ORT_VERSION + "\n";
      Files.writeString(sidecar, content);
    } catch (IOException e) {
      log.debug("Failed to write ONNX optimization sidecar (non-fatal): {}", e.getMessage());
    }
  }

  private static boolean isGpuOptimizedCacheValid(Path modelPath, Path optimizedPath) {
    if (!Files.isRegularFile(optimizedPath)) {
      return false;
    }
    Path sidecar = modelPath.resolveSibling(modelPath.getFileName() + GPU_SIDECAR_SUFFIX);
    if (!Files.isRegularFile(sidecar)) {
      return false;
    }
    try {
      String content = Files.readString(sidecar).strip();
      long mtime = -1;
      long size = -1;
      String ortVer = null;
      String ep = null;
      for (String part : content.split("\\s+")) {
        if (part.startsWith("mtime:")) {
          mtime = Long.parseLong(part.substring("mtime:".length()));
        } else if (part.startsWith("size:")) {
          size = Long.parseLong(part.substring("size:".length()));
        } else if (part.startsWith("ort:")) {
          ortVer = part.substring("ort:".length());
        } else if (part.startsWith("ep:")) {
          ep = part.substring("ep:".length());
        }
      }

      long actualMtime = Files.getLastModifiedTime(modelPath).toMillis();
      long actualSize = Files.size(modelPath);

      if (mtime != actualMtime || size != actualSize) {
        log.debug(
            "CUDA-EP optimized cache stale: mtime {}→{}, size {}→{}",
            mtime,
            actualMtime,
            size,
            actualSize);
        return false;
      }
      if (ortVer != null && !ortVer.equals(ORT_VERSION)) {
        log.debug("CUDA-EP optimized cache stale: ORT version {}→{}", ortVer, ORT_VERSION);
        return false;
      }
      // An EP tag is required for GPU sidecars. Absent or mismatched → invalidate.
      // Guards against accidentally reusing a CPU-optimized graph file, should it
      // ever end up at the GPU cache path (belt-and-suspenders since the suffixes
      // differ, but cheap).
      if (!GPU_EP_TAG.equals(ep)) {
        log.debug("CUDA-EP optimized cache stale: ep tag {} (expected {})", ep, GPU_EP_TAG);
        return false;
      }
      return true;
    } catch (IOException | NumberFormatException e) {
      log.debug("Failed to validate CUDA-EP optimized cache: {}", e.getMessage());
      return false;
    }
  }

  private static void writeGpuSidecar(Path modelPath) {
    Path sidecar = modelPath.resolveSibling(modelPath.getFileName() + GPU_SIDECAR_SUFFIX);
    try {
      long mtime = Files.getLastModifiedTime(modelPath).toMillis();
      long size = Files.size(modelPath);
      String content =
          "mtime:" + mtime + " size:" + size + " ort:" + ORT_VERSION + " ep:" + GPU_EP_TAG + "\n";
      Files.writeString(sidecar, content);
    } catch (IOException e) {
      log.debug("Failed to write CUDA-EP optimization sidecar (non-fatal): {}", e.getMessage());
    }
  }

  private static String getOrtVersion() {
    try {
      return OrtEnvironment.getEnvironment().getVersion();
    } catch (Exception e) {
      return "unknown";
    }
  }

  /**
   * The ONNX Runtime library version (cached at class load). Exposed so the benchmark-release
   * hardware projection can record "what ORT produced these eval numbers" (tempdoc 623 U7). This
   * is a library-version query, identical on Head and Worker (same dependency), so it is safe to
   * read from either process.
   */
  public static String ortVersion() {
    return ORT_VERSION;
  }
}
