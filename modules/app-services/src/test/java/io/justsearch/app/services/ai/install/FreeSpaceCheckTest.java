/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.ai.install;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 840 U2 — the per-stage disk precondition.
 *
 * <p>The property under test is asymmetric on purpose, and that asymmetry is the whole design: only a
 * measurement that positively shows too little space may refuse an install. Everything else — an
 * unmeasurable filesystem, a stage with nothing to fetch — must let the install proceed, because
 * refusing an install that would have succeeded is a worse failure than the late IO error this check
 * exists to pre-empt.
 */
final class FreeSpaceCheckTest {

  private static final long GB = 1024L * 1024L * 1024L;

  @Test
  @DisplayName("blocks only when the measurement positively shows too little room")
  void blocksWhenMeasurablyTooSmall() {
    String reason = FreeSpaceCheck.blockedReason("Retrieval core", 4 * GB, 2 * GB);

    assertNotNull(reason, "4 GB to fetch with 2 GB free cannot succeed");
    assertTrue(reason.contains("Retrieval core"), "names the stage that did not fit: " + reason);
    assertTrue(reason.contains("2.0 GB"), "states what is free: " + reason);
    assertTrue(reason.contains("4.0 GB"), "states what the download needs: " + reason);
  }

  @Test
  @DisplayName("allows when free space covers the bytes plus the working-room margin")
  void allowsWhenRoomy() {
    assertNull(FreeSpaceCheck.blockedReason("Chat", 4 * GB, 4 * GB + FreeSpaceCheck.MARGIN_BYTES));
  }

  /**
   * The margin is required, not advisory: the cuda-runtime zip is extracted in place and kept, so a
   * stage needs room for the archive plus its contents. Exactly-the-bytes must not be enough.
   */
  @Test
  @DisplayName("the working-room margin is required, not advisory")
  void marginIsRequired() {
    assertNotNull(
        FreeSpaceCheck.blockedReason("Retrieval core", 4 * GB, 4 * GB),
        "free space equal to the download leaves no room to extract or stage");
    assertNull(
        FreeSpaceCheck.blockedReason("Retrieval core", 4 * GB, 4 * GB + FreeSpaceCheck.MARGIN_BYTES),
        "one byte of margin short is the boundary; at the margin it must pass");
  }

  /**
   * Fail-open. Some network and virtual mounts report 0 usable bytes rather than refusing the call,
   * which is indistinguishable from a full disk if you only look at the number. An unmeasurable disk
   * is not a full one, and treating it as one would refuse installs that would have worked.
   */
  @Test
  @DisplayName("an unmeasurable filesystem never blocks")
  void unmeasurableNeverBlocks() {
    assertNull(FreeSpaceCheck.blockedReason("Chat", 6 * GB, 0L), "0 usable = the FS would not say");
    assertNull(FreeSpaceCheck.blockedReason("Chat", 6 * GB, -1L), "negative = same, defensively");
  }

  @Test
  @DisplayName("a stage with nothing to fetch is never blocked, however full the disk")
  void nothingToFetchIsNeverBlocked() {
    assertNull(FreeSpaceCheck.blockedReason("Enrichment", 0L, 1L));
    assertNull(FreeSpaceCheck.blockedReason("Enrichment", -1L, 1L));
  }

  @Test
  @DisplayName("sizes read the way the rest of the product writes them")
  void humanBytes() {
    assertEquals("1.5 GB", FreeSpaceCheck.humanBytes(1536L * 1024L * 1024L));
    assertEquals("512 MB", FreeSpaceCheck.humanBytes(512L * 1024L * 1024L));
    assertEquals("872 B", FreeSpaceCheck.humanBytes(872L));
  }
}
