/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.util;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.VarHandle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Windows Job Object wrapper for crash-safe process tree containment.
 *
 * <p>When the parent process (Head) exits — cleanly or via crash — the OS closes the job handle
 * and kills all member processes (Worker) via {@code JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE}.
 *
 * <p>On non-Windows platforms, {@link #createOrNull()} returns {@code null}.
 *
 * <p>Uses the Java Foreign Function &amp; Memory (FFM) API to call Win32 kernel32.dll functions.
 * No external dependencies required.
 */
public final class WindowsJobObject implements AutoCloseable {
  private static final Logger log = LoggerFactory.getLogger(WindowsJobObject.class);

  // Win32 constants
  private static final int JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE = 0x2000;
  private static final int JOB_OBJECT_EXTENDED_LIMIT_INFORMATION = 9;
  private static final int PROCESS_SET_QUOTA = 0x0100;
  private static final int PROCESS_TERMINATE = 0x0001;

  // Value layouts
  private static final ValueLayout.OfInt I32 = ValueLayout.JAVA_INT;
  private static final ValueLayout.OfLong I64 = ValueLayout.JAVA_LONG;

  // JOBOBJECT_BASIC_LIMIT_INFORMATION layout (x64: 48 bytes)
  // Fields: PerProcessUserTimeLimit(8), PerJobUserTimeLimit(8), LimitFlags(4),
  // padding(4), MinimumWorkingSetSize(8), MaximumWorkingSetSize(8),
  // ActiveProcessLimit(4), padding(4), Affinity(8), PriorityClass(4), SchedulingClass(4)
  private static final StructLayout BASIC_LIMIT_INFO_LAYOUT =
      MemoryLayout.structLayout(
          I64.withName("PerProcessUserTimeLimit"),
          I64.withName("PerJobUserTimeLimit"),
          I32.withName("LimitFlags"),
          MemoryLayout.paddingLayout(4), // alignment pad
          ValueLayout.ADDRESS.withName("MinimumWorkingSetSize"),
          ValueLayout.ADDRESS.withName("MaximumWorkingSetSize"),
          I32.withName("ActiveProcessLimit"),
          MemoryLayout.paddingLayout(4), // alignment pad
          ValueLayout.ADDRESS.withName("Affinity"),
          I32.withName("PriorityClass"),
          I32.withName("SchedulingClass"));

  // IO_COUNTERS layout (48 bytes)
  private static final StructLayout IO_COUNTERS_LAYOUT =
      MemoryLayout.structLayout(
          I64.withName("ReadOperationCount"),
          I64.withName("WriteOperationCount"),
          I64.withName("OtherOperationCount"),
          I64.withName("ReadTransferCount"),
          I64.withName("WriteTransferCount"),
          I64.withName("OtherTransferCount"));

  // JOBOBJECT_EXTENDED_LIMIT_INFORMATION layout (x64: 144 bytes)
  private static final StructLayout EXTENDED_LIMIT_INFO_LAYOUT =
      MemoryLayout.structLayout(
          BASIC_LIMIT_INFO_LAYOUT.withName("BasicLimitInformation"),
          IO_COUNTERS_LAYOUT.withName("IoInfo"),
          ValueLayout.ADDRESS.withName("ProcessMemoryLimit"),
          ValueLayout.ADDRESS.withName("JobMemoryLimit"),
          ValueLayout.ADDRESS.withName("PeakProcessMemoryUsed"),
          ValueLayout.ADDRESS.withName("PeakJobMemoryUsed"));

  private static final VarHandle LIMIT_FLAGS_HANDLE =
      EXTENDED_LIMIT_INFO_LAYOUT.varHandle(
          MemoryLayout.PathElement.groupElement("BasicLimitInformation"),
          MemoryLayout.PathElement.groupElement("LimitFlags"));

  private final Arena arena;
  private final MemorySegment jobHandle;
  private final MethodHandle openProcess;
  private final MethodHandle assignProcessToJobObject;
  private final MethodHandle closeHandle;
  private boolean closed;

  private WindowsJobObject(
      Arena arena,
      MemorySegment jobHandle,
      MethodHandle openProcess,
      MethodHandle assignProcessToJobObject,
      MethodHandle closeHandle) {
    this.arena = arena;
    this.jobHandle = jobHandle;
    this.openProcess = openProcess;
    this.assignProcessToJobObject = assignProcessToJobObject;
    this.closeHandle = closeHandle;
  }

  /**
   * Creates a Windows Job Object with {@code JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE}.
   *
   * @return a new {@code WindowsJobObject}, or {@code null} on non-Windows platforms or if
   *     creation fails (best-effort — failure is logged but does not block startup)
   */
  public static WindowsJobObject createOrNull() {
    if (!isWindows()) {
      return null;
    }
    try {
      return createInternal();
    } catch (Throwable t) {
      log.warn("Failed to create Windows Job Object (process containment disabled): {}", t.getMessage());
      return null;
    }
  }

  private static WindowsJobObject createInternal() throws Throwable {
    Arena arena = Arena.ofShared();
    try {
      Linker linker = Linker.nativeLinker();
      SymbolLookup kernel32 = SymbolLookup.libraryLookup("kernel32", arena);

      // Bind kernel32 functions
      MethodHandle createJobObjectW =
          downcall(
              linker,
              kernel32,
              "CreateJobObjectW",
              FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));

      MethodHandle setInformationJobObject =
          downcall(
              linker,
              kernel32,
              "SetInformationJobObject",
              FunctionDescriptor.of(I32, ValueLayout.ADDRESS, I32, ValueLayout.ADDRESS, I32));

      MethodHandle openProcessFn =
          downcall(
              linker,
              kernel32,
              "OpenProcess",
              FunctionDescriptor.of(ValueLayout.ADDRESS, I32, I32, I32));

      MethodHandle assignProcessToJobObjectFn =
          downcall(
              linker,
              kernel32,
              "AssignProcessToJobObject",
              FunctionDescriptor.of(I32, ValueLayout.ADDRESS, ValueLayout.ADDRESS));

      MethodHandle closeHandleFn =
          downcall(
              linker,
              kernel32,
              "CloseHandle",
              FunctionDescriptor.of(I32, ValueLayout.ADDRESS));

      // Create unnamed Job Object
      MemorySegment hJob =
          (MemorySegment) createJobObjectW.invokeExact(MemorySegment.NULL, MemorySegment.NULL);
      if (hJob.address() == 0) {
        arena.close();
        throw new IllegalStateException("CreateJobObjectW returned NULL");
      }

      // Set KILL_ON_JOB_CLOSE limit
      MemorySegment info = arena.allocate(EXTENDED_LIMIT_INFO_LAYOUT);
      info.fill((byte) 0);
      LIMIT_FLAGS_HANDLE.set(info, 0L, JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE);

      int ok =
          (int) setInformationJobObject.invokeExact(
              hJob,
              JOB_OBJECT_EXTENDED_LIMIT_INFORMATION,
              info,
              (int) EXTENDED_LIMIT_INFO_LAYOUT.byteSize());

      if (ok == 0) {
        int ignored = (int) closeHandleFn.invokeExact(hJob);
        arena.close();
        throw new IllegalStateException("SetInformationJobObject failed");
      }

      log.info("Created Windows Job Object for process containment");

      return new WindowsJobObject(arena, hJob, openProcessFn, assignProcessToJobObjectFn, closeHandleFn);
    } catch (Throwable t) {
      arena.close();
      throw t;
    }
  }

  /**
   * Assigns a child process to this Job Object. Should be called immediately after spawn, before
   * the child creates grandchildren.
   *
   * <p>Best-effort: if the process has already exited or assignment fails, the error is logged but
   * does not propagate.
   *
   * @param pid the process ID to assign
   */
  public void assign(long pid) {
    if (closed || pid <= 0) {
      return;
    }
    try {
      MemorySegment hProcess =
          (MemorySegment) openProcess.invokeExact(PROCESS_SET_QUOTA | PROCESS_TERMINATE, 0, (int) pid);
      if (hProcess.address() == 0) {
        log.debug("OpenProcess failed for PID {} (may have already exited)", pid);
        return;
      }
      try {
        int ok = (int) assignProcessToJobObject.invokeExact(jobHandle, hProcess);
        if (ok != 0) {
          log.info("Assigned PID {} to Job Object", pid);
        } else {
          log.warn("AssignProcessToJobObject failed for PID {}", pid);
        }
      } finally {
        int ignored = (int) closeHandle.invokeExact(hProcess);
      }
    } catch (Throwable t) {
      log.warn("Failed to assign PID {} to Job Object: {}", pid, t.getMessage());
    }
  }

  /**
   * Closes the Job Object handle. On Windows, this triggers the OS to terminate all member
   * processes (due to {@code JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE}).
   *
   * <p>Should be called <em>after</em> attempting graceful shutdown — the Job Object is a safety
   * net for when Head itself crashes, not a replacement for graceful shutdown.
   */
  @Override
  public void close() {
    if (closed) {
      return;
    }
    closed = true;
    try {
      int ignored = (int) closeHandle.invokeExact(jobHandle);
      log.info("Closed Windows Job Object");
    } catch (Throwable t) {
      log.debug("Failed to close Job Object handle: {}", t.getMessage());
    }
    arena.close();
  }

  private static MethodHandle downcall(
      Linker linker, SymbolLookup lookup, String symbol, FunctionDescriptor fd) {
    MemorySegment fn =
        lookup
            .find(symbol)
            .orElseThrow(() -> new IllegalStateException("kernel32 symbol missing: " + symbol));
    return linker.downcallHandle(fn, fd);
  }

  private static boolean isWindows() {
    return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
  }
}
