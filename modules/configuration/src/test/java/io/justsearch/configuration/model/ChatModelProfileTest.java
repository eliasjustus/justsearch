/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.configuration.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 842 — guard tests for the hoisted chat model profile.
 *
 * <p>The profile bundles the (model, mmproj) pair so a half-swap is unrepresentable, and resolution
 * never throws: a bad {@code justsearch.chat.profile} value must fall back to the standard pair
 * rather than brick the chat engine. The load-bearing guard is
 * {@link #standardIsTodaysCanonicalPair()} — the default profile must reproduce the shipped
 * filenames byte-for-byte, so introducing the profile changes nothing for an unconfigured runtime.
 */
class ChatModelProfileTest {

  @Test
  void unsetProfileResolvesToStandardDefault() {
    assertEquals(ChatModelProfile.STANDARD, ChatModelProfile.resolve(null));
    assertEquals(ChatModelProfile.STANDARD, ChatModelProfile.resolve(""));
    assertEquals(ChatModelProfile.STANDARD, ChatModelProfile.resolve("   "));
    assertEquals(ChatModelProfile.STANDARD, ChatModelProfile.DEFAULT);
  }

  @Test
  void standardIsTodaysCanonicalPair() {
    assertEquals("Qwen_Qwen3.5-9B-Q4_K_M.gguf", ChatModelProfile.STANDARD.modelFile());
    assertEquals("mmproj-F16.gguf", ChatModelProfile.STANDARD.mmprojFile());
  }

  @Test
  void compactPairLivesUnderTheChatCompactTargetDir() {
    // The registry's chat-compact package installs into "compact/", so the profile's relative
    // filenames must carry that prefix or resolution lands on a nonexistent path.
    assertEquals("compact/Qwen3.5-4B-Q4_K_M.gguf", ChatModelProfile.COMPACT.modelFile());
    assertEquals("compact/mmproj-F16.gguf", ChatModelProfile.COMPACT.mmprojFile());
  }

  @Test
  void resolvesEveryProfileById() {
    for (ChatModelProfile p : ChatModelProfile.values()) {
      assertEquals(p, ChatModelProfile.resolve(p.id()));
    }
  }

  @Test
  void resolutionIsCaseInsensitiveAndAcceptsEnumName() {
    assertEquals(ChatModelProfile.COMPACT, ChatModelProfile.resolve("COMPACT"));
    assertEquals(ChatModelProfile.COMPACT, ChatModelProfile.resolve("  Compact "));
    assertEquals(ChatModelProfile.STANDARD, ChatModelProfile.resolve("StAnDaRd"));
    assertEquals(ChatModelProfile.PADDLE_OCR_VL, ChatModelProfile.resolve("PADDLE-OCR-VL"));
    assertEquals(ChatModelProfile.PADDLE_OCR_VL, ChatModelProfile.resolve("PADDLE_OCR_VL"));
  }

  @Test
  void legacyQwenVlAliasResolvesToStandard() {
    // The pre-842 justsearch.vlm.profile key named this exact pair "qwen-vl". An existing
    // configuration must keep SELECTING the standard pair, not warn-fall-back onto it.
    assertEquals(ChatModelProfile.STANDARD, ChatModelProfile.resolve("qwen-vl"));
    assertEquals(ChatModelProfile.STANDARD, ChatModelProfile.resolve("QWEN_VL"));
  }

  @Test
  void unknownProfileFallsBackToStandard() {
    assertEquals(ChatModelProfile.STANDARD, ChatModelProfile.resolve("not-a-profile"));
    assertEquals(ChatModelProfile.STANDARD, ChatModelProfile.resolve("compackt"));
  }

  @Test
  void everyProfileBundlesADistinctNonBlankPair() {
    Set<String> ids = new HashSet<>();
    Set<String> modelFiles = new HashSet<>();
    for (ChatModelProfile p : ChatModelProfile.values()) {
      assertNotNull(p.id());
      assertNotNull(p.modelFile());
      assertNotNull(p.mmprojFile());
      assertFalse(p.id().isBlank(), p + " has a blank id");
      assertFalse(p.modelFile().isBlank(), p + " has a blank model file");
      assertFalse(p.mmprojFile().isBlank(), p + " has a blank mmproj file");
      // A profile must not collapse to one file — the projector is a separate artifact.
      assertNotEquals(p.modelFile(), p.mmprojFile(), p + " model and mmproj must be distinct files");
      assertTrue(ids.add(p.id()), "duplicate profile id: " + p.id());
      // Two profiles selecting the same model file would make the switch a no-op.
      assertTrue(modelFiles.add(p.modelFile()), "duplicate model file: " + p.modelFile());
    }
  }
}
