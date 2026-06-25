package io.justsearch.gpu;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.TestResolvedConfigHelper;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Unit tests for {@link VramFlagsUtil}. */
class VramFlagsUtilTest {

  // ===== mergeRecommendedFlags tests =====

  @Test
  void mergeRecommendedFlags_nullInput_returnsEmptyList() {
    List<String> command = new ArrayList<>();
    List<String> added = VramFlagsUtil.mergeRecommendedFlags(command, null);

    assertTrue(added.isEmpty());
    assertTrue(command.isEmpty());
  }

  @Test
  void mergeRecommendedFlags_emptyInput_returnsEmptyList() {
    List<String> command = new ArrayList<>();
    List<String> added = VramFlagsUtil.mergeRecommendedFlags(command, new String[0]);

    assertTrue(added.isEmpty());
    assertTrue(command.isEmpty());
  }

  @Test
  void mergeRecommendedFlags_normalFlagsWithValues_addsAll() {
    List<String> command = new ArrayList<>(List.of("llama-server", "-m", "model.gguf"));
    String[] recommended = {"-ctk", "q4_0", "-ctv", "q4_0"};

    List<String> added = VramFlagsUtil.mergeRecommendedFlags(command, recommended);

    assertEquals(List.of("-ctk", "q4_0", "-ctv", "q4_0"), added);
    assertEquals(
        List.of("llama-server", "-m", "model.gguf", "-ctk", "q4_0", "-ctv", "q4_0"), command);
  }

  @Test
  void mergeRecommendedFlags_skipsConfigOwnedFlags() {
    List<String> command = new ArrayList<>(List.of("llama-server"));
    // -c and -ngl are config-owned and should be skipped
    String[] recommended = {"-c", "4096", "-ngl", "99", "-ctk", "q4_0"};

    List<String> added = VramFlagsUtil.mergeRecommendedFlags(command, recommended);

    // Only -ctk q4_0 should be added; -c 4096 and -ngl 99 skipped
    assertEquals(List.of("-ctk", "q4_0"), added);
    assertEquals(List.of("llama-server", "-ctk", "q4_0"), command);
  }

  @Test
  void mergeRecommendedFlags_skipsDuplicateFlags() {
    List<String> command = new ArrayList<>(List.of("llama-server", "-ctk", "q8_0"));
    // -ctk already exists in command with different value
    String[] recommended = {"-ctk", "q4_0", "-ctv", "q4_0"};

    List<String> added = VramFlagsUtil.mergeRecommendedFlags(command, recommended);

    // -ctk skipped (duplicate), only -ctv added
    assertEquals(List.of("-ctv", "q4_0"), added);
    assertEquals(List.of("llama-server", "-ctk", "q8_0", "-ctv", "q4_0"), command);
  }

  @Test
  void mergeRecommendedFlags_skipsOrphanedValues() {
    List<String> command = new ArrayList<>(List.of("llama-server"));
    // "orphan" doesn't start with -, so it's skipped as an orphaned value
    String[] recommended = {"orphan", "-fa"};

    List<String> added = VramFlagsUtil.mergeRecommendedFlags(command, recommended);

    // Only -fa added; "orphan" skipped
    assertEquals(List.of("-fa"), added);
    assertEquals(List.of("llama-server", "-fa"), command);
  }

  @Test
  void mergeRecommendedFlags_handlesFlagsWithoutValues() {
    List<String> command = new ArrayList<>(List.of("llama-server"));
    // Boolean flags without values
    String[] recommended = {"-fa", "--mlock", "-v"};

    List<String> added = VramFlagsUtil.mergeRecommendedFlags(command, recommended);

    assertEquals(List.of("-fa", "--mlock", "-v"), added);
    assertEquals(List.of("llama-server", "-fa", "--mlock", "-v"), command);
  }

  @Test
  void mergeRecommendedFlags_handlesNullAndBlankTokens() {
    List<String> command = new ArrayList<>(List.of("llama-server"));
    String[] recommended = {null, "", "  ", "-fa", null};

    List<String> added = VramFlagsUtil.mergeRecommendedFlags(command, recommended);

    assertEquals(List.of("-fa"), added);
    assertEquals(List.of("llama-server", "-fa"), command);
  }

  @Test
  void mergeRecommendedFlags_realWorldScenario_8gbTier() {
    // Exact output from VramDetector.getRecommendedLlamaServerFlags() for 8GB tier (line 204)
    List<String> command =
        new ArrayList<>(List.of("llama-server", "-m", "model.gguf", "-c", "4096", "-ngl", "99"));
    String[] recommended = {"-c", "4096", "-ngl", "99", "-ctk", "q4_0", "-ctv", "q4_0"};

    List<String> added = VramFlagsUtil.mergeRecommendedFlags(command, recommended);

    // -c and -ngl skipped (config-owned), only KV quantization flags added
    assertEquals(List.of("-ctk", "q4_0", "-ctv", "q4_0"), added);
    assertEquals(
        List.of(
            "llama-server", "-m", "model.gguf", "-c", "4096", "-ngl", "99", "-ctk", "q4_0", "-ctv",
            "q4_0"),
        command);
  }

  // ===== detectVramTier tests =====

  @Test
  void detectVramTier_null_returnsUnknown() {
    assertEquals("unknown", VramFlagsUtil.detectVramTier(null));
  }

  @Test
  void detectVramTier_negative_returnsUnknown() {
    assertEquals("unknown", VramFlagsUtil.detectVramTier(-1L));
  }

  @ParameterizedTest
  @CsvSource({
    // Exact thresholds
    "11500000000, 12gb_plus", // exactly 11.5 GB threshold
    "7500000000, 8gb", // exactly 7.5 GB threshold
    "3500000000, 4gb", // exactly 3.5 GB threshold

    // Just above thresholds
    "11500000001, 12gb_plus",
    "7500000001, 8gb",
    "3500000001, 4gb",

    // Just below thresholds
    "11499999999, 8gb",
    "7499999999, 4gb",
    "3499999999, under_4gb",

    // Real GPU values
    "12878610432, 12gb_plus", // RTX 4070 (reports ~11.99 GiB)
    "8589934592, 8gb", // 8 GB exactly
    "12884901888, 12gb_plus", // 12 GB exactly
    "4294967296, 4gb", // 4 GB exactly

    // Edge cases
    "0, under_4gb",
    "1, under_4gb"
  })
  void detectVramTier_boundaries(long vramBytes, String expectedTier) {
    assertEquals(expectedTier, VramFlagsUtil.detectVramTier(vramBytes));
  }

  @Test
  void detectVramTier_respectsSystemPropertyOverride() {
    // With default threshold (11.5GB), 6GB → "4gb"
    assertEquals("4gb", VramFlagsUtil.detectVramTier(6_000_000_000L));

    // Override 12GB threshold to 5GB → 6GB now classifies as "12gb_plus"
    System.setProperty("justsearch.vram.threshold.12gb", "5000000000");
    ConfigStore prev = ConfigStore.globalOrNull();
    TestResolvedConfigHelper.storeFromEnvironment();
    try {
      assertEquals("12gb_plus", VramFlagsUtil.detectVramTier(6_000_000_000L));
    } finally {
      System.clearProperty("justsearch.vram.threshold.12gb");
      TestResolvedConfigHelper.restoreGlobal(prev);
    }
  }

  @Test
  void detectVramTier_invalidSystemPropertyOverride_fallsBackToDefaultThreshold() {
    assertEquals("8gb", VramFlagsUtil.detectVramTier(7_600_000_000L));

    System.setProperty("justsearch.vram.threshold.8gb", "not-a-number");
    ConfigStore prev = ConfigStore.globalOrNull();
    TestResolvedConfigHelper.storeFromEnvironment();
    try {
      assertEquals("8gb", VramFlagsUtil.detectVramTier(7_600_000_000L));
    } finally {
      System.clearProperty("justsearch.vram.threshold.8gb");
      TestResolvedConfigHelper.restoreGlobal(prev);
    }
  }
}
