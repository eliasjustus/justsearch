package io.justsearch.app.inference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.justsearch.app.inference.InferenceConfig.VlmExtractionProfile;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 580 Track D / F-009 — guard tests for the atomic VLM extraction profile.
 *
 * <p>The profile bundles the (model, mmproj) pair so a half-swap is unrepresentable. The most
 * important guard is {@link #qwenVlProfileIsTodaysCanonicalPair()}: the default profile MUST be
 * byte-for-byte today's canonical filenames, so introducing the profile changes nothing for an
 * unconfigured runtime.
 */
class VlmExtractionProfileTest {

  @Test
  void unsetProfileResolvesToQwenVlDefault() {
    assertEquals(VlmExtractionProfile.QWEN_VL, VlmExtractionProfile.resolve(Optional.empty()));
    assertEquals(VlmExtractionProfile.QWEN_VL, VlmExtractionProfile.resolve(null));
    assertEquals(VlmExtractionProfile.QWEN_VL, VlmExtractionProfile.resolve(Optional.of("   ")));
  }

  @Test
  void qwenVlProfileIsTodaysCanonicalPair() {
    // Regression guard: the default profile reproduces the pre-580 canonical constants exactly,
    // so the default runtime behavior is unchanged by introducing the profile mechanism.
    assertEquals("Qwen_Qwen3.5-9B-Q4_K_M.gguf", VlmExtractionProfile.QWEN_VL.vlmModel());
    assertEquals("mmproj-F16.gguf", VlmExtractionProfile.QWEN_VL.mmprojModel());
  }

  @Test
  void resolvesPaddleOcrVlById() {
    assertEquals(
        VlmExtractionProfile.PADDLE_OCR_VL, VlmExtractionProfile.resolve(Optional.of("paddle-ocr-vl")));
  }

  @Test
  void resolutionIsCaseInsensitiveAndAcceptsEnumName() {
    assertEquals(
        VlmExtractionProfile.PADDLE_OCR_VL,
        VlmExtractionProfile.resolve(Optional.of("PADDLE-OCR-VL")));
    assertEquals(
        VlmExtractionProfile.PADDLE_OCR_VL,
        VlmExtractionProfile.resolve(Optional.of("PADDLE_OCR_VL")));
    assertEquals(VlmExtractionProfile.QWEN_VL, VlmExtractionProfile.resolve(Optional.of("Qwen-VL")));
  }

  @Test
  void unknownProfileFallsBackToDefault() {
    assertEquals(
        VlmExtractionProfile.QWEN_VL, VlmExtractionProfile.resolve(Optional.of("not-a-profile")));
  }

  @Test
  void paddleProfileBundlesADistinctNonBlankPair() {
    VlmExtractionProfile paddle = VlmExtractionProfile.PADDLE_OCR_VL;
    assertNotNull(paddle.vlmModel());
    assertNotNull(paddle.mmprojModel());
    assertFalse(paddle.vlmModel().isBlank());
    assertFalse(paddle.mmprojModel().isBlank());
    // A profile must not collapse to one file — the projector is a separate artifact.
    assertNotEquals(paddle.vlmModel(), paddle.mmprojModel());
    // And it is genuinely a different model than the default (a real swap).
    assertNotEquals(VlmExtractionProfile.QWEN_VL.vlmModel(), paddle.vlmModel());
  }
}
