package io.justsearch.indexing.chunking;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ChunkSplitter token estimation")
class ChunkSplitterTokenEstimateTest {

  @Test
  @DisplayName("Dense ASCII text does not severely under-estimate tokens")
  void denseAsciiDoesNotUnderEstimate() {
    String dense = "a".repeat(300); // no whitespace
    int tokens = ChunkSplitter.estimateTokens(dense);
    // For dense text, heuristic uses len/3 => 100 tokens (ceil) rather than ~1 word.
    assertTrue(tokens >= 90, "Expected dense text to estimate ~len/3 tokens, got " + tokens);
  }

  @Test
  @DisplayName("Whitespace-rich text uses word-based estimate as floor")
  void whitespaceRichUsesWordEstimate() {
    String text = "The quick brown fox jumps over the lazy dog.";
    int tokens = ChunkSplitter.estimateTokens(text);
    assertTrue(tokens >= 10, "Expected some non-trivial token estimate, got " + tokens);
  }

  @Test
  @DisplayName("Dense non-ASCII text can estimate 1 token per char")
  void denseNonAsciiCanBeOneTokenPerChar() {
    // 100 CJK chars, no whitespace
    String cjk = "汉".repeat(100);
    int tokens = ChunkSplitter.estimateTokens(cjk);
    assertEquals(100, tokens, "Expected 1 token per char for dense non-ascii, got " + tokens);
  }
}
