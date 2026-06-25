package io.justsearch.app.inference;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CudaRuntimeDetectionTest {

  @Test
  @DisplayName("Null executable returns null")
  void nullExecutable() {
    assertNull(CudaRuntimeDetection.detectCudaRuntimeWarning(null));
  }

  @Test
  @DisplayName("No ggml-cuda.dll returns null (no warning)")
  void noGgmlCudaDll(@TempDir Path dir) {
    Path fakeExe = dir.resolve("llama-server.exe");
    assertNull(CudaRuntimeDetection.detectCudaRuntimeWarning(fakeExe));
  }

  @Test
  @DisplayName("Large statically-linked ggml-cuda.dll returns null")
  void largeStaticDll(@TempDir Path dir) throws IOException {
    Path fakeExe = dir.resolve("llama-server.exe");
    Files.createFile(fakeExe);
    // Create a sparse file > 200MB to simulate statically-linked DLL
    Path ggmlCuda = dir.resolve("ggml-cuda.dll");
    try (RandomAccessFile raf = new RandomAccessFile(ggmlCuda.toFile(), "rw")) {
      raf.setLength(250_000_000L);
    }
    assertNull(CudaRuntimeDetection.detectCudaRuntimeWarning(fakeExe));
  }

  @Test
  @DisplayName("Small dynamically-linked dll with cudart present returns null")
  void smallDllWithCudart(@TempDir Path dir) throws IOException {
    Path fakeExe = dir.resolve("llama-server.exe");
    Files.createFile(fakeExe);
    // Small ggml-cuda.dll (dynamically linked)
    Files.write(dir.resolve("ggml-cuda.dll"), new byte[1024]);
    // CUDA runtime present
    Files.createFile(dir.resolve("cudart64_12.dll"));
    assertNull(CudaRuntimeDetection.detectCudaRuntimeWarning(fakeExe));
  }

  @Test
  @DisplayName("Small dynamically-linked dll without cudart returns warning")
  void smallDllWithoutCudart(@TempDir Path dir) throws IOException {
    Path fakeExe = dir.resolve("llama-server.exe");
    Files.createFile(fakeExe);
    // Small ggml-cuda.dll (dynamically linked) without any cudart
    Files.write(dir.resolve("ggml-cuda.dll"), new byte[1024]);
    String warning = CudaRuntimeDetection.detectCudaRuntimeWarning(fakeExe);
    assertTrue(warning != null && warning.contains("CUDA Toolkit runtime not found"));
  }
}
