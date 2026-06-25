/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ipc.mmf;

/**
 * Header constants for MMF v1 signal bus.
 *
 * <p>Uses reserved bytes [25-31] from {@link MmfWorkerSignalLayoutV1}:
 * <ul>
 *   <li>[25-26] MAGIC_BYTES (short) — 0x534A ("JS" in little-endian)</li>
 *   <li>[27] FORMAT_VERSION (byte) — 1</li>
 *   <li>[28] COMPAT_FLAGS (byte) — 0x00 (new) or 0x01 (legacy marker)</li>
 *   <li>[29-31] reserved for future use</li>
 * </ul>
 *
 * <p>Backward compatibility: Files with all-zero header bytes are treated as legacy v1,
 * allowing pre-header signal files to continue working.
 *
 * <p>Stability: stable (IPC contract)
 */
// PERMANENT COMPAT - DO NOT REMOVE (IPC signal bus layout shared with Worker)
public final class MmfWorkerSignalHeaderV1 {

    private MmfWorkerSignalHeaderV1() {}

    /**
     * Magic bytes: "JS" in little-endian (0x4A='J', 0x53='S').
     * Stored as short at OFFSET_MAGIC.
     */
    public static final short MAGIC_BYTES = 0x534A;

    /** Format version for this header layout. */
    public static final byte FORMAT_VERSION = 1;

    /** Offset within MMF for 2-byte magic value. */
    public static final int OFFSET_MAGIC = 25;

    /** Offset within MMF for 1-byte version. */
    public static final int OFFSET_VERSION = 27;

    /** Offset within MMF for 1-byte compatibility flags. */
    public static final int OFFSET_FLAGS = 28;

    /** Compatibility flag: set when this file was upgraded from legacy (no header). */
    public static final byte FLAG_LEGACY = 0x01;

    /**
     * Check if header indicates legacy mode (zero magic = pre-header file).
     *
     * @param magic the magic bytes read from OFFSET_MAGIC
     * @param version the version byte read from OFFSET_VERSION
     * @return true if this appears to be a legacy (pre-header) file
     */
    public static boolean isLegacyHeader(short magic, byte version) {
        return magic == 0 && version == 0;
    }

    /**
     * Check if header is valid v1.
     *
     * @param magic the magic bytes read from OFFSET_MAGIC
     * @param version the version byte read from OFFSET_VERSION
     * @return true if this is a valid v1 header
     */
    public static boolean isValidV1(short magic, byte version) {
        return magic == MAGIC_BYTES && version == FORMAT_VERSION;
    }
}
