package io.justsearch.app.services.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkerSpawnerOrtNativePathHintTest {

  @TempDir Path tempDir;

  @Test
  void resolveHint_returnsNullForBlankInput() {
    assertNull(WorkerSpawner.resolveOnnxRuntimeNativePathHint(null));
    assertNull(WorkerSpawner.resolveOnnxRuntimeNativePathHint(""));
    assertNull(WorkerSpawner.resolveOnnxRuntimeNativePathHint("   "));
  }

  @Test
  void resolveHint_returnsNullWhenDirectoryDoesNotLookLikeOrtNativeDir() throws Exception {
    Path candidate = Files.createDirectories(tempDir.resolve("empty"));
    assertFalse(WorkerSpawner.looksLikeOnnxRuntimeNativeDir(candidate));
    assertNull(WorkerSpawner.resolveOnnxRuntimeNativePathHint(candidate.toString()));
  }

  @Test
  void resolveHint_returnsNormalizedPathWhenCudaDllsPresent() throws Exception {
    Path candidate = Files.createDirectories(tempDir.resolve("cuda12"));
    Files.createFile(candidate.resolve("onnxruntime.dll"));
    Files.createFile(candidate.resolve("cudart64_12.dll"));
    assertTrue(WorkerSpawner.looksLikeOnnxRuntimeNativeDir(candidate));
    assertEquals(
        candidate.toAbsolutePath().normalize().toString(),
        WorkerSpawner.resolveOnnxRuntimeNativePathHint(candidate.toString()));
  }
}
