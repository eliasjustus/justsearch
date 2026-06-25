/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ipc.mmf;

/**
 * Canonical Memory-Mapped File (MMF) layout for the Worker signal bus (schema v1).
 *
 * <p>This layout is a binary protocol shared across processes; it must have a single owner to
 * prevent silent drift. All call sites should import these constants rather than duplicating
 * offsets and sizes.
 *
 * <p>Layout (64 bytes, little-endian):
 * <ul>
 *   <li>[0-7]   activity_epoch_ms (long) — Main writes; Worker reads</li>
 *   <li>[8-15]  heartbeat_epoch_ms (long) — Main writes; Worker reads</li>
 *   <li>[16]    shutdown_signal (byte, 1=stop) — Main writes; Worker reads</li>
 *   <li>[17]    energy_reduced (byte, 1=OS wants reduced bg work) — Main writes; Worker reads (630)</li>
 *   <li>[18-19] reserved</li>
 *   <li>[20-23] worker_grpc_port (int) — Worker writes; Main reads</li>
 *   <li>[24]    main_gpu_active (byte, 1=GPU in use) — Main writes; Worker reads</li>
 *   <li>[25-28] header (magic, version, flags) — see {@link MmfWorkerSignalHeaderV1}</li>
 *   <li>[29]    reload_signal (byte, 1=reload) — External writes; Worker reads (dev-only)</li>
 *   <li>[30-63] reserved</li>
 * </ul>
 *
 * <p>Stability: stable (IPC contract)
 */
public final class MmfWorkerSignalLayoutV1 {

  private MmfWorkerSignalLayoutV1() {}

  public static final int MMF_SIZE_BYTES = 64;

  public static final int OFFSET_ACTIVITY_EPOCH_MS = 0;
  public static final int OFFSET_HEARTBEAT_EPOCH_MS = 8;
  public static final int OFFSET_SHUTDOWN_SIGNAL = 16;

  /** OS energy-intent: 1 = OS wants reduced background work (energy saver). Main writes, Worker reads (tempdoc 630). */
  public static final int OFFSET_ENERGY_REDUCED = 17;

  public static final int OFFSET_RESERVED0_START = 18;
  public static final int RESERVED0_LENGTH_BYTES = 2;

  public static final int OFFSET_WORKER_GRPC_PORT = 20;
  public static final int OFFSET_MAIN_GPU_ACTIVE = 24;

  /** Dev hot-reload signal (byte, 1=reload requested). Written by Gradle task, read by Worker. */
  public static final int OFFSET_RELOAD_SIGNAL = 29;

  public static final int OFFSET_RESERVED1_START = 25;
  public static final int RESERVED1_LENGTH_BYTES = MMF_SIZE_BYTES - OFFSET_RESERVED1_START;
}
