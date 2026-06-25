/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ort;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Driver-API CUDA capability probe. Loads {@code nvcuda.dll} (Windows) / {@code libcuda.so.1}
 * (Linux) — the system-resident CUDA driver — and asks it whether at least one CUDA device is
 * visible. This answers the actual question "can this machine run any CUDA workload" rather than
 * the proxy questions ({@code nvidia-smi.exe} on PATH, ORT EP DLLs at a dev-tree path, etc.).
 *
 * <p>Companion to {@link io.justsearch.gpu.NvmlService} (NVML — diagnostics, VRAM size). NVML
 * success does not imply CUDA functional (PyTorch docs explicitly warn). The driver API is the
 * canonical "is CUDA available" check used by ggml-cuda, PyTorch, ORT internally.
 *
 * <p>Tempdoc 374 sandbox round 2 finding #4: replaces {@link GpuAutoDetection}'s filesystem
 * sentinel-DLL check (broken for every production install — dev-tree path doesn't exist).
 */
public final class GpuDriverApiProbe {

  private static final Logger LOG = LoggerFactory.getLogger(GpuDriverApiProbe.class);

  private static final ValueLayout.OfInt I32 = ValueLayout.JAVA_INT;

  /**
   * Probe result. {@code available} is true iff {@code cuInit} succeeded AND
   * {@code cuDeviceGetCount} returned ≥ 1.
   */
  public record Result(
      boolean available, int deviceCount, int driverVersion, String reason) {}

  private GpuDriverApiProbe() {}

  /**
   * Probes for CUDA driver API availability. Returns a structured result; never throws.
   *
   * <p>On Windows, looks for {@code nvcuda.dll} in the standard DLL search path (System32 when
   * a CUDA-capable NVIDIA driver is installed). On Linux, looks for {@code libcuda.so.1}.
   */
  public static Result probe() {
    String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
    String lib = os.contains("windows") ? "nvcuda" : "cuda";

    SymbolLookup lookup;
    try (Arena arena = Arena.ofConfined()) {
      try {
        lookup = SymbolLookup.libraryLookup(lib, arena);
      } catch (IllegalArgumentException e) {
        LOG.debug("CUDA driver library '{}' not found: {}", lib, e.getMessage());
        return new Result(false, 0, 0, "driver library not found: " + lib);
      } catch (Throwable t) {
        LOG.debug("CUDA driver library '{}' load failed: {}", lib, t.getMessage());
        return new Result(false, 0, 0, "driver library load failed: " + safeMsg(t));
      }

      Linker linker = Linker.nativeLinker();
      MethodHandle cuInit;
      MethodHandle cuDeviceGetCount;
      MethodHandle cuDriverGetVersion;
      try {
        // CUresult cuInit(unsigned int Flags);
        cuInit = linker.downcallHandle(
            lookup.find("cuInit").orElseThrow(),
            FunctionDescriptor.of(I32, I32));
        // CUresult cuDeviceGetCount(int *count);
        cuDeviceGetCount = linker.downcallHandle(
            lookup.find("cuDeviceGetCount").orElseThrow(),
            FunctionDescriptor.of(I32, ValueLayout.ADDRESS));
        // CUresult cuDriverGetVersion(int *driverVersion); (best-effort, optional)
        cuDriverGetVersion = lookup.find("cuDriverGetVersion")
            .map(addr -> linker.downcallHandle(addr,
                FunctionDescriptor.of(I32, ValueLayout.ADDRESS)))
            .orElse(null);
      } catch (Throwable t) {
        LOG.debug("Failed to bind CUDA driver-API entrypoints: {}", safeMsg(t));
        return new Result(false, 0, 0, "entrypoint binding failed: " + safeMsg(t));
      }

      int initRc;
      try {
        initRc = (int) cuInit.invokeExact(0);
      } catch (Throwable t) {
        return new Result(false, 0, 0, "cuInit invocation failed: " + safeMsg(t));
      }
      if (initRc != 0) {
        return new Result(false, 0, 0, "cuInit returned " + initRc);
      }

      int deviceCount;
      try {
        MemorySegment countBuf = arena.allocate(I32);
        int rc = (int) cuDeviceGetCount.invokeExact(countBuf);
        if (rc != 0) {
          return new Result(false, 0, 0, "cuDeviceGetCount returned " + rc);
        }
        deviceCount = countBuf.get(I32, 0);
      } catch (Throwable t) {
        return new Result(false, 0, 0, "cuDeviceGetCount invocation failed: " + safeMsg(t));
      }

      int driverVersion = 0;
      if (cuDriverGetVersion != null) {
        try {
          MemorySegment verBuf = arena.allocate(I32);
          int rc = (int) cuDriverGetVersion.invokeExact(verBuf);
          if (rc == 0) {
            driverVersion = verBuf.get(I32, 0);
          }
        } catch (Throwable ignored) {
          // best-effort; absence is non-fatal
        }
      }

      if (deviceCount > 0) {
        return new Result(true, deviceCount, driverVersion, "ok");
      }
      return new Result(false, 0, driverVersion, "cuDeviceGetCount returned 0 devices");
    }
  }

  private static String safeMsg(Throwable t) {
    String m = t.getMessage();
    return m != null ? m : t.getClass().getSimpleName();
  }
}
