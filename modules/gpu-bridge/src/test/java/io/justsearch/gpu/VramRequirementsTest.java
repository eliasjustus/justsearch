package io.justsearch.gpu;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.configuration.model.HardwareProfile;
import org.junit.jupiter.api.Test;

/**
 * Boundary tests for {@link VramRequirements}. Tempdoc 374 alpha.27.
 *
 * <p>Pin each helper at the threshold edges (null, negative, just-below, at-threshold,
 * just-above) so the threshold logic can't silently drift when the NVML migration is
 * applied to remaining call sites in alpha.27 Phase B.
 */
final class VramRequirementsTest {

  @Test
  void meetsGgufRequirementsHandlesNullAndNegative() {
    assertFalse(VramRequirements.meetsGgufRequirements(null));
    assertFalse(VramRequirements.meetsGgufRequirements(-1L));
    assertFalse(VramRequirements.meetsGgufRequirements(0L));
  }

  @Test
  void meetsGgufRequirementsBoundary() {
    long min = HardwareProfile.MINIMUM_VRAM_FOR_GGUF;
    assertFalse(VramRequirements.meetsGgufRequirements(min - 1));
    assertTrue(VramRequirements.meetsGgufRequirements(min));
    assertTrue(VramRequirements.meetsGgufRequirements(min + 1));
  }

  @Test
  void hasComfortableVramBoundary() {
    long comfy = VramRequirements.COMFORTABLE_VRAM_BYTES;
    assertFalse(VramRequirements.hasComfortableVram(null));
    assertFalse(VramRequirements.hasComfortableVram(-1L));
    assertFalse(VramRequirements.hasComfortableVram(comfy - 1));
    assertTrue(VramRequirements.hasComfortableVram(comfy));
    assertTrue(VramRequirements.hasComfortableVram(comfy + 1));
  }

  @Test
  void describeFormatsKnownValues() {
    assertEquals("Unknown", VramRequirements.describe(null));
    assertEquals("Unknown", VramRequirements.describe(-1L));
    // 12 GB exactly = 12 * 1024^3 = 12884901888
    assertEquals("12.0 GB", VramRequirements.describe(12_884_901_888L));
    // The round-13 sandbox NVML reading exactly:
    assertEquals("12.0 GB", VramRequirements.describe(12_878_610_432L));
    // 8 GB
    assertEquals("8.0 GB", VramRequirements.describe(8_589_934_592L));
  }

  @Test
  void recommendedLlamaServerFlagsByTier() {
    // Below GGUF minimum → null
    assertNull(VramRequirements.recommendedLlamaServerFlags(null));
    assertNull(VramRequirements.recommendedLlamaServerFlags(-1L));
    assertNull(VramRequirements.recommendedLlamaServerFlags(HardwareProfile.MINIMUM_VRAM_FOR_GGUF - 1));

    // 8 GB tier (between GGUF minimum and comfortable) → KV quantization flags
    long eightGB = 8_589_934_592L;
    assertArrayEquals(
        new String[] {"-c", "4096", "-ngl", "99", "-fa", "on", "-ctk", "q4_0", "-ctv", "q4_0"},
        VramRequirements.recommendedLlamaServerFlags(eightGB));

    // Just below comfortable → still 8 GB tier
    assertArrayEquals(
        new String[] {"-c", "4096", "-ngl", "99", "-fa", "on", "-ctk", "q4_0", "-ctv", "q4_0"},
        VramRequirements.recommendedLlamaServerFlags(VramRequirements.COMFORTABLE_VRAM_BYTES - 1));

    // 12 GB tier (comfortable) → no quantization
    long twelveGB = 12_878_610_432L; // round-13 NVML reading
    assertArrayEquals(
        new String[] {"-c", "4096", "-ngl", "99", "-fa", "on"},
        VramRequirements.recommendedLlamaServerFlags(twelveGB));
  }

  /**
   * Tempdoc 374 alpha.27 post-ship regression guard: pin the explicit "-fa" value.
   * llama-server b8571 rejects bare "-fa" with "expected value for argument" — the
   * syntax is "-fa [on|off|auto]". This test fails fast if a future PR drops the
   * value (mirroring the bug that nearly shipped in the original alpha.27 cut).
   */
  @Test
  void recommendedLlamaServerFlagsPinsExplicitFlashAttnValue() {
    long twelveGB = 12_878_610_432L;
    String[] flags = VramRequirements.recommendedLlamaServerFlags(twelveGB);
    int faIdx = -1;
    for (int i = 0; i < flags.length; i++) {
      if ("-fa".equals(flags[i])) {
        faIdx = i;
        break;
      }
    }
    assertTrue(faIdx >= 0, "-fa flag must be present");
    assertTrue(faIdx + 1 < flags.length, "-fa must be followed by a value (b8571 requires it)");
    assertEquals(
        "on",
        flags[faIdx + 1],
        "-fa must be followed by 'on' (force flash-attention). Bare -fa is rejected by b8571.");
  }
}
