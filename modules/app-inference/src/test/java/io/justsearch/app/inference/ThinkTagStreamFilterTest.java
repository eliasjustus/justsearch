package io.justsearch.app.inference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The streaming think-tag filter (tempdoc 835 §5.3). The hard part is the frame straddle: a
 * per-chunk {@code replaceAll} passes {@code "<thi"} + {@code "nk>"} straight through, so every
 * split point of the same input must produce the same result.
 */
@DisplayName("ThinkTagStreamFilter")
class ThinkTagStreamFilterTest {

  /** Feeds the chunks through a fresh filter; returns [visibleContent, reasoning]. */
  private static String[] run(List<String> chunks) {
    StringBuilder reasoning = new StringBuilder();
    StringBuilder visible = new StringBuilder();
    ThinkTagStreamFilter filter = new ThinkTagStreamFilter(reasoning::append);
    for (String chunk : chunks) {
      visible.append(filter.accept(chunk));
    }
    visible.append(filter.flush());
    return new String[] {visible.toString(), reasoning.toString()};
  }

  /** Every split of the input into two chunks, so no boundary is special-cased. */
  private static List<List<String>> everyTwoWaySplit(String input) {
    List<List<String>> splits = new ArrayList<>();
    for (int i = 0; i <= input.length(); i++) {
      splits.add(List.of(input.substring(0, i), input.substring(i)));
    }
    return splits;
  }

  @Test
  @DisplayName("content without tags passes through byte-identically, at every split point")
  void noTagPassthroughIsByteIdentical() {
    String input = "The answer is 42. 5 < 6 and a<b, but not <thought> or <thin king>.";
    for (List<String> chunks : everyTwoWaySplit(input)) {
      String[] out = run(chunks);
      assertEquals(input, out[0], "content changed for split " + chunks);
      assertEquals("", out[1], "nothing should be routed to reasoning for " + chunks);
    }
  }

  @Test
  @DisplayName("a <think> block is rerouted to reasoning, not deleted")
  void thinkBlockIsRerouted() {
    String[] out = run(List.of("<think>weighing options</think>Clean answer"));
    assertEquals("Clean answer", out[0]);
    assertEquals("weighing options", out[1]);
  }

  @Test
  @DisplayName("a tag split across frames is still caught — every split point")
  void tagSplitAcrossFramesIsCaught() {
    String input = "Prefix <think>hidden reasoning</think>visible tail";
    for (List<String> chunks : everyTwoWaySplit(input)) {
      String[] out = run(chunks);
      assertEquals("Prefix visible tail", out[0], "split " + chunks);
      assertEquals("hidden reasoning", out[1], "split " + chunks);
    }
  }

  @Test
  @DisplayName("the classic straddle: '<thi' + 'nk>' arrives as two frames")
  void openTagStraddlesTwoFrames() {
    String[] out = run(List.of("answer <thi", "nk>secret", " more</thi", "nk> end"));
    assertEquals("answer  end", out[0]);
    assertEquals("secret more", out[1]);
  }

  @Test
  @DisplayName("per-token frames (one character each) behave like one frame")
  void characterByCharacterFrames() {
    String input = "a<think>b</think>c";
    List<String> chunks = new ArrayList<>();
    for (char c : input.toCharArray()) {
      chunks.add(String.valueOf(c));
    }
    String[] out = run(chunks);
    assertEquals("ac", out[0]);
    assertEquals("b", out[1]);
  }

  @Test
  @DisplayName("mixed content: several blocks interleaved with answer text")
  void mixedContentWithSeveralBlocks() {
    String[] out = run(List.of("<think>first</think>Part 1 <think>second</think>Part 2"));
    assertEquals("Part 1 Part 2", out[0]);
    assertEquals("firstsecond", out[1]);
  }

  @Test
  @DisplayName("a lone </think> (the zero-budget leak) is dropped, content survives")
  void loneClosingTagIsDropped() {
    String[] out = run(List.of("stray reasoning</think>the answer"));
    assertEquals("stray reasoningthe answer", out[0]);
    assertEquals("", out[1]);
  }

  @Test
  @DisplayName("a lone <think> with no close routes the remainder to reasoning at flush")
  void unclosedThinkBlockIsFlushedToReasoning() {
    String[] out = run(List.of("answer <think>truncated thought"));
    assertEquals("answer ", out[0]);
    assertEquals("truncated thought", out[1]);
  }

  @Test
  @DisplayName("a trailing partial tag that never completes is emitted as content at flush")
  void danglingPartialTagIsNotSwallowed() {
    String[] out = run(List.of("compare a <", "thi"));
    assertEquals("compare a <thi", out[0]);
    assertEquals("", out[1]);
  }

  @Test
  @DisplayName("content is released as it streams — a filter does not buffer the whole answer")
  void contentIsReleasedIncrementally() {
    ThinkTagStreamFilter filter = new ThinkTagStreamFilter(null);
    assertEquals("hello ", filter.accept("hello "));
    assertTrue(filter.accept("<think>").isEmpty());
    assertTrue(filter.accept("thinking").isEmpty());
    assertEquals("world", filter.accept("</think>world"));
  }

  @Test
  @DisplayName("a null reasoning sink discards captured thinking without failing")
  void nullReasoningSinkDiscards() {
    ThinkTagStreamFilter filter = new ThinkTagStreamFilter(null);
    StringBuilder visible = new StringBuilder();
    visible.append(filter.accept("<think>dropped</think>kept"));
    visible.append(filter.flush());
    assertEquals("kept", visible.toString());
  }
}
