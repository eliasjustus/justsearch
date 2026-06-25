package io.justsearch.ort;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for {@link OnnxSessionCache} — specifically the GPU cache path derivation and sidecar
 * round-trip logic. Actual session creation is not exercised here (that requires CUDA and is
 * covered by the end-to-end pipeline run).
 */
@DisplayName("OnnxSessionCache — GPU cache helpers")
final class OnnxSessionCacheTest {

  @TempDir Path tempDir;

  @Nested
  @DisplayName("gpuOptimizedPath")
  class GpuOptimizedPath {

    @Test
    @DisplayName("appends .cuda.optimized suffix next to the source model")
    void appendsCudaOptimizedSuffix() {
      Path model = tempDir.resolve("model_fp16.onnx");
      Path cache = OnnxSessionCache.gpuOptimizedPath(model);
      assertEquals(tempDir.resolve("model_fp16.onnx.cuda.optimized"), cache);
    }

    @Test
    @DisplayName("preserves the parent directory of the source model")
    void preservesParentDirectory() {
      Path subdir = tempDir.resolve("sub");
      Path model = subdir.resolve("model.onnx");
      Path cache = OnnxSessionCache.gpuOptimizedPath(model);
      assertEquals(subdir, cache.getParent());
    }

    @Test
    @DisplayName("is distinct from the CPU .optimized path")
    void distinctFromCpuOptimized() {
      Path model = tempDir.resolve("model_fp16.onnx");
      Path gpu = OnnxSessionCache.gpuOptimizedPath(model);
      Path cpu = model.resolveSibling(model.getFileName() + ".optimized");
      assertNotEquals(cpu, gpu, "GPU and CPU caches must not share a path (EP-specific graphs)");
    }
  }

  @Nested
  @DisplayName("GPU sidecar validation (via reflection-free round-trip)")
  class GpuSidecarRoundTrip {

    @Test
    @DisplayName("valid sidecar + optimized file → cache considered valid")
    void validSidecarValidatesTrue() throws Exception {
      Path model = writeModel("model.onnx", "source-bytes");
      Path optimized = writeOptimizedSibling(model, "optimized-bytes");
      writeGpuSidecarFor(model, "cuda");

      assertTrue(invokeIsGpuOptimizedCacheValid(model, optimized));
    }

    @Test
    @DisplayName("missing optimized file → cache invalid")
    void missingOptimizedInvalidatesCache() throws Exception {
      Path model = writeModel("model.onnx", "source-bytes");
      Path optimized = OnnxSessionCache.gpuOptimizedPath(model);
      writeGpuSidecarFor(model, "cuda");

      assertFalse(invokeIsGpuOptimizedCacheValid(model, optimized));
    }

    @Test
    @DisplayName("missing sidecar → cache invalid")
    void missingSidecarInvalidatesCache() throws Exception {
      Path model = writeModel("model.onnx", "source-bytes");
      Path optimized = writeOptimizedSibling(model, "optimized-bytes");

      assertFalse(invokeIsGpuOptimizedCacheValid(model, optimized));
    }

    @Test
    @DisplayName("mtime drift invalidates the cache")
    void mtimeDriftInvalidatesCache() throws Exception {
      Path model = writeModel("model.onnx", "source-bytes");
      Path optimized = writeOptimizedSibling(model, "optimized-bytes");
      writeGpuSidecarFor(model, "cuda");

      Files.setLastModifiedTime(
          model, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 60_000));

      assertFalse(invokeIsGpuOptimizedCacheValid(model, optimized));
    }

    @Test
    @DisplayName("ep tag mismatch invalidates the cache (guards against EP drift)")
    void epTagMismatchInvalidatesCache() throws Exception {
      Path model = writeModel("model.onnx", "source-bytes");
      Path optimized = writeOptimizedSibling(model, "optimized-bytes");
      writeGpuSidecarFor(model, "rocm");

      assertFalse(invokeIsGpuOptimizedCacheValid(model, optimized));
    }

    @Test
    @DisplayName("missing ep tag invalidates the cache")
    void missingEpTagInvalidatesCache() throws Exception {
      Path model = writeModel("model.onnx", "source-bytes");
      Path optimized = writeOptimizedSibling(model, "optimized-bytes");
      // sidecar without ep: field
      Path sidecar = model.resolveSibling(model.getFileName() + ".cuda.opt-meta");
      long mtime = Files.getLastModifiedTime(model).toMillis();
      long size = Files.size(model);
      Files.writeString(sidecar, "mtime:" + mtime + " size:" + size + " ort:test\n");

      assertFalse(invokeIsGpuOptimizedCacheValid(model, optimized));
    }

    @Test
    @DisplayName("ORT version drift invalidates the cache")
    void ortVersionDriftInvalidatesCache() throws Exception {
      Path model = writeModel("model.onnx", "source-bytes");
      Path optimized = writeOptimizedSibling(model, "optimized-bytes");
      // Hand-crafted sidecar with a bogus ORT version
      Path sidecar = model.resolveSibling(model.getFileName() + ".cuda.opt-meta");
      long mtime = Files.getLastModifiedTime(model).toMillis();
      long size = Files.size(model);
      Files.writeString(
          sidecar, "mtime:" + mtime + " size:" + size + " ort:0.0.0-bogus ep:cuda\n");

      assertFalse(invokeIsGpuOptimizedCacheValid(model, optimized));
    }
  }

  // --- helpers ---------------------------------------------------------

  private Path writeModel(String name, String body) throws Exception {
    Path p = tempDir.resolve(name);
    Files.writeString(p, body);
    return p;
  }

  private Path writeOptimizedSibling(Path model, String body) throws Exception {
    Path p = OnnxSessionCache.gpuOptimizedPath(model);
    Files.writeString(p, body);
    return p;
  }

  /**
   * Writes a GPU sidecar for the given model using the production on-disk format. Uses the actual
   * ORT version from the class-loaded OnnxSessionCache so that version checks pass in the positive
   * test case.
   */
  private void writeGpuSidecarFor(Path model, String epTag) throws Exception {
    Path sidecar = model.resolveSibling(model.getFileName() + ".cuda.opt-meta");
    long mtime = Files.getLastModifiedTime(model).toMillis();
    long size = Files.size(model);
    String ortVersion = readOrtVersionConstant();
    Files.writeString(
        sidecar,
        "mtime:" + mtime + " size:" + size + " ort:" + ortVersion + " ep:" + epTag + "\n");
  }

  private static String readOrtVersionConstant() throws Exception {
    var f = OnnxSessionCache.class.getDeclaredField("ORT_VERSION");
    f.setAccessible(true);
    return (String) f.get(null);
  }

  private static boolean invokeIsGpuOptimizedCacheValid(Path model, Path optimized)
      throws Exception {
    var m =
        Stream.of(OnnxSessionCache.class.getDeclaredMethods())
            .filter(mm -> mm.getName().equals("isGpuOptimizedCacheValid"))
            .findFirst()
            .orElseThrow();
    m.setAccessible(true);
    return (boolean) m.invoke(null, model, optimized);
  }
}
