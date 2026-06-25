package io.justsearch.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests for {@link VramDetector}.
 *
 * <p>The nvidia-smi subprocess plumbing is not directly unit-testable, so the error-prone part —
 * parsing {@code nvidia-smi}'s MB output into bytes for the first GPU — is exercised via the pure
 * {@link VramDetector#parseNvidiaSmiFirstGpuBytes} seam. A second group asserts the public
 * wrappers' cross-method invariants, which hold regardless of whether the host has a GPU.
 */
final class VramDetectorTest {

  private static final long MB = 1024L * 1024L;

  // ===== parseNvidiaSmiFirstGpuBytes — deterministic, no subprocess =====

  @Test
  void parsesSingleGpuMbToBytes() {
    assertEquals(8192L * MB, VramDetector.parseNvidiaSmiFirstGpuBytes("8192"));
    assertEquals(12288L * MB, VramDetector.parseNvidiaSmiFirstGpuBytes("12288\n"));
  }

  @Test
  void multiGpu_takesFirstLine() {
    // nvidia-smi emits one line per GPU; we report the first.
    assertEquals(24564L * MB, VramDetector.parseNvidiaSmiFirstGpuBytes("24564\n8192\n8192"));
  }

  @Test
  void trimsSurroundingWhitespace() {
    assertEquals(16384L * MB, VramDetector.parseNvidiaSmiFirstGpuBytes("  16384  "));
  }

  @Test
  void zeroIsValid() {
    assertEquals(0L, VramDetector.parseNvidiaSmiFirstGpuBytes("0"));
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(
      strings = {
        "   ", // blank
        "\n\n", // only newlines → empty first line
        "[N/A]", // nvidia-smi unsupported-query marker
        "Insufficient Permissions",
        "12.5", // MB is integer; a decimal is not valid
        "-4096", // negative is not a real reading
        "8192MB" // unit suffix (we query with nounits, but be defensive)
      })
  void returnsMinusOneForUnparseable(String output) {
    assertEquals(-1L, VramDetector.parseNvidiaSmiFirstGpuBytes(output));
  }

  // ===== public-wrapper cross-method invariants — environment-tolerant =====
  //
  // These pin invariants that must hold in BOTH host states; they adapt to the runtime GPU
  // value rather than assuming one. On a GPU-less host (CI / cuda12 sandbox) only the
  // "unavailable" branch executes, so here they mainly guard that the sentinel paths stay
  // self-consistent; the GPU-present branches are exercised only when run on a GPU host. The
  // decision/parse logic itself is covered deterministically by the pure-seam tests above and
  // by VramRequirementsTest — these wrappers are the thin process-spawning shell around it.

  @Test
  void isCudaAvailable_agreesWithTotalVramSign() {
    VramDetector detector = new VramDetector();
    long total = detector.getTotalVramBytes();
    assertEquals(total > 0, detector.isCudaAvailable());
  }

  @Test
  void description_andTierGates_areConsistentWithTotal() {
    VramDetector detector = new VramDetector();
    long total = detector.getTotalVramBytes();

    if (total < 0) {
      // No nvidia-smi (e.g. CI / cuda12 sandbox): every derived answer is the unknown sentinel.
      assertEquals("Unknown (nvidia-smi not available)", detector.getVramDescription());
      assertFalse(detector.meetsVduRequirements());
      assertFalse(detector.hasComfortableVram());
    } else {
      // A GPU host: the description is a formatted GB string and the gates agree with the value.
      assertTrue(detector.getVramDescription().endsWith(" GB"));
      // hasComfortableVram() implies meetsVduRequirements() — comfortable is the stricter bound.
      if (detector.hasComfortableVram()) {
        assertTrue(detector.meetsVduRequirements());
      }
    }
  }

  @Test
  void cachedTotal_isStableAcrossCalls() {
    VramDetector detector = new VramDetector();
    long first = detector.getTotalVramBytes();
    long second = detector.getTotalVramBytes(); // served from cache (or sticky -1)
    assertEquals(first, second);
  }
}
