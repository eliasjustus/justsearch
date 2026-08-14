/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 836 §1.3 / §3.5 / §5 — the preparation both citation producers consume: which text a
 * source is verified against, how it is windowed, and which window ordinals survive the budget.
 */
@DisplayName("PassageWindows — literal text, windowing, admission")
class PassageWindowsTest {

  /** Records every lookup the preparation performs (tempdoc 836 §5.2). */
  private static final class LookupSpy implements IntFunction<String> {
    private final List<Integer> calls = new ArrayList<>();
    private final List<String> texts;

    LookupSpy(List<String> texts) {
      this.texts = texts;
    }

    @Override
    public String apply(int sourceIndex) {
      calls.add(sourceIndex);
      return sourceIndex < texts.size() ? texts.get(sourceIndex) : null;
    }
  }

  private static String prose(String seed, int chars) {
    StringBuilder sb = new StringBuilder(chars + 16);
    while (sb.length() < chars) {
      sb.append(seed).append(' ');
    }
    return sb.substring(0, chars);
  }

  @Nested
  @DisplayName("Text resolution")
  class TextResolution {

    @Test
    @DisplayName("supplied text wins over the chunk the citation points at")
    void suppliedTextWins() {
      LookupSpy spy = new LookupSpy(List.of("the chunk the index would return"));

      var prepared =
          PassageWindows.prepare(
              List.of("doc-1"),
              List.of(0),
              List.of("the literal passage the user selected"),
              spy,
              1,
              0,
              "answer");

      assertEquals(List.of("the literal passage the user selected"), prepared.windowTexts());
      assertTrue(prepared.suppliedAt(0), "source 0 supplied its own text");
    }

    @Test
    @DisplayName("no lookup happens for a source that supplies text")
    void suppliedSourceCostsZeroLookups() {
      LookupSpy spy = new LookupSpy(List.of("chunk a", "chunk b"));

      PassageWindows.prepare(
          List.of("doc-1", "doc-2"),
          List.of(0, 3),
          List.of("passage a", "passage b"),
          spy,
          1,
          0,
          "answer");

      assertTrue(spy.calls.isEmpty(), "a fully supplied request must not read the index at all");
    }

    @Test
    @DisplayName("fallback is per source: a blank entry is looked up, a filled one is not")
    void fallbackIsPerSource() {
      LookupSpy spy = new LookupSpy(List.of("chunk for A", "chunk for B"));

      var prepared =
          PassageWindows.prepare(
              List.of("doc-a", "doc-b"),
              List.of(0, 1),
              List.of("passage for A", ""),
              spy,
              1,
              0,
              "answer");

      assertEquals(List.of(1), spy.calls, "only the blank source is looked up");
      assertEquals(List.of("passage for A", "chunk for B"), prepared.windowTexts());
      assertTrue(prepared.suppliedAt(0));
      assertFalse(prepared.suppliedAt(1), "source 1's text came from the index");
    }

    @Test
    @DisplayName("an empty passage_texts list falls back for every source")
    void emptyListLooksUpEverything() {
      LookupSpy spy = new LookupSpy(List.of("chunk a", "chunk b"));

      var prepared =
          PassageWindows.prepare(
              List.of("doc-a", "doc-b"), List.of(0, 1), List.of(), spy, 1, 0, "answer");

      assertEquals(List.of(0, 1), spy.calls);
      assertEquals(List.of("chunk a", "chunk b"), prepared.windowTexts());
      assertFalse(prepared.suppliedAt(0));
      assertFalse(prepared.suppliedAt(1));
    }

    @Test
    @DisplayName("a source whose lookup fails contributes no window and re-points at nobody")
    void failedLookupContributesNothing() {
      IntFunction<String> nullLookup = i -> null;

      var prepared =
          PassageWindows.prepare(
              List.of("doc-a", "doc-b"),
              List.of(0, 1),
              List.of("", "passage for B"),
              nullLookup,
              1,
              0,
              "answer");

      assertEquals(List.of("passage for B"), prepared.windowTexts());
      assertEquals(1, prepared.sourceOf(0), "B's window still reports B, not the missing source");
    }
  }

  @Nested
  @DisplayName("Windowing")
  class Windowing {

    @Test
    @DisplayName("a long passage is split into windows of at most WINDOW_CHARS")
    void splitsAtWindowSize() {
      String text = prose("lucene analyzer pipeline", 5000);

      List<String> windows = PassageWindows.split(text);

      assertTrue(windows.size() >= 4, "5000 chars needs at least 4 windows of 1500");
      for (String w : windows) {
        assertTrue(
            w.length() <= PassageWindows.WINDOW_CHARS,
            "window of " + w.length() + " chars exceeds the measured budget");
      }
    }

    @Test
    @DisplayName("windows tile the passage: the tail is present, so a tail-only claim can match")
    void windowsCoverTheTail() {
      String tail = "the final clause names the cursor encoding";
      String text = prose("body sentence about pagination", 4000) + " " + tail;

      List<String> windows = PassageWindows.split(text);

      assertEquals(
          text.replaceAll("\\s+", ""),
          String.join("", windows).replaceAll("\\s+", ""),
          "the concatenated windows must reproduce the whole passage");
      assertTrue(
          windows.stream().anyMatch(w -> w.contains("cursor encoding")),
          "the tail of a long passage must appear in some window");
    }

    @Test
    @DisplayName("a passage shorter than a window is one window, unchanged")
    void shortPassageIsOneWindow() {
      assertEquals(List.of("short passage"), PassageWindows.split("short passage"));
    }
  }

  @Nested
  @DisplayName("Admission control")
  class Admission {

    @Test
    @DisplayName("the cap is the deadline's affordable pairs divided by the sentence count")
    void capTracksBudgetAndSentences() {
      // 2000ms / 25ms per pair = 80 pairs.
      assertEquals(80, PassageWindows.admissionCap(1, 2000));
      assertEquals(16, PassageWindows.admissionCap(5, 2000));
      assertEquals(5, PassageWindows.admissionCap(15, 2000));
      assertEquals(2, PassageWindows.admissionCap(40, 2000));
    }

    @Test
    @DisplayName("a zero deadline admits everything; a tiny one still admits one window")
    void capEdges() {
      assertEquals(Integer.MAX_VALUE, PassageWindows.admissionCap(10, 0));
      assertEquals(1, PassageWindows.admissionCap(500, 100));
    }

    @Test
    @DisplayName("over-budget requests drop windows and say so, rather than scoring them all")
    void admissionDropsWindows() {
      String big = prose("chunked passage text about lucene", 60_000);

      var prepared =
          PassageWindows.prepare(
              List.of("doc-1"), List.of(0), List.of(big), i -> null, 15, 2000, "answer sentence");

      assertTrue(prepared.windowsConsidered() > 5, "60 KB is far more than 5 windows");
      assertEquals(5, prepared.windowTexts().size(), "15 sentences at a 2000ms budget buys 5");
      assertTrue(prepared.admissionTruncated());
    }

    @Test
    @DisplayName("every source keeps a window while slots remain, so none becomes uncitable")
    void admissionKeepsEverySourceRepresented() {
      List<String> docIds = List.of("doc-a", "doc-b", "doc-c");
      List<Integer> indices = List.of(0, 0, 0);
      List<String> texts =
          List.of(
              prose("alpha material", 9000), prose("beta material", 9000), prose("gamma", 9000));

      // 5 sentences at 2000ms => 16 window slots, against ~21 windows.
      var prepared = PassageWindows.prepare(docIds, indices, texts, i -> null, 5, 2000, "answer");

      assertTrue(prepared.admissionTruncated());
      boolean[] seen = new boolean[3];
      for (int w = 0; w < prepared.windowTexts().size(); w++) {
        seen[prepared.sourceOf(w)] = true;
      }
      assertTrue(seen[0] && seen[1] && seen[2], "each source must keep at least one window");
    }

    @Test
    @DisplayName("a blank answer still admits a bounded window set rather than failing")
    void blankAnswerStillAdmits() {
      var prepared =
          PassageWindows.prepare(
              List.of("doc-1"),
              List.of(0),
              List.of(prose("passage text", 30_000)),
              i -> null,
              40,
              2000,
              "");

      assertTrue(prepared.admissionTruncated());
      assertEquals(2, prepared.windowTexts().size(), "the budget still binds with no query terms");
      assertEquals(0, prepared.sourceOf(0));
    }

    @Test
    @DisplayName("admission prefers windows that lexically overlap the answer")
    void admissionPrefersRelevantWindows() {
      String needle = prose("saffron threads harvested by hand", 1400);
      String filler = prose("unrelated boilerplate about shipping crates", 20_000);

      var prepared =
          PassageWindows.prepare(
              List.of("doc-1"),
              List.of(0),
              List.of(filler + " " + needle),
              i -> null,
              40,
              2000,
              "Saffron threads are harvested by hand.");

      assertEquals(2, prepared.windowTexts().size(), "40 sentences at 2000ms buys 2 windows");
      assertTrue(
          prepared.windowTexts().stream().anyMatch(w -> w.contains("saffron")),
          "the window that shares vocabulary with the answer must survive the cut");
    }
  }

  @Nested
  @DisplayName("The numbering contract")
  class Numbering {

    @Test
    @DisplayName("windows of one source all map back to that source's request position")
    void backMapPointsAtSources() {
      var prepared =
          PassageWindows.prepare(
              List.of("doc-a", "doc-b"),
              List.of(0, 7),
              List.of(prose("alpha", 4000), prose("beta", 4000)),
              i -> null,
              1,
              0,
              "answer");

      assertTrue(prepared.windowTexts().size() > 2, "both sources yield several windows");
      for (int w = 0; w < prepared.windowTexts().size(); w++) {
        int source = prepared.sourceOf(w);
        assertTrue(source == 0 || source == 1, "source " + source + " is not a request position");
        assertEquals(
            prepared.windowTexts().get(w).contains("alpha") ? 0 : 1,
            source,
            "window " + w + " maps to the source its text came from");
      }
    }

    @Test
    @DisplayName("a window ordinal outside the back-map throws rather than naming a plausible source")
    void outOfRangeWindowThrows() {
      var prepared =
          PassageWindows.prepare(
              List.of("doc-a"), List.of(0), List.of("one short passage"), i -> null, 1, 0, "a");

      assertThrows(IndexOutOfBoundsException.class, () -> prepared.sourceOf(1));
      assertThrows(IndexOutOfBoundsException.class, () -> prepared.sourceOf(-1));
    }
  }
}
