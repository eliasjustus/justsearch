/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.justsearch.agent.tools.ReadDocumentTool;
import io.justsearch.app.api.OnlineAiService;
import io.justsearch.core.util.ContextBudget;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * Tempdoc 883 decision 3 — the two former CLASS-INIT constants now track the live window.
 *
 * <p><b>Why this test exists in this exact shape.</b> {@code AgentLlmCaller.DEFAULT_MAX_TOKENS} and
 * {@code AgentContextCompressor.MAX_TOOL_RESULT_CHARS} were {@code static final} fields initialized
 * once per JVM. A test that asserted their value could not distinguish "resolved correctly" from
 * "frozen at whatever the first caller saw" — so this one changes the window WITHIN ONE JVM and
 * requires the derived numbers to move. On the pre-883 code that is impossible by construction: the
 * fields are final and the window is not an input to them at all.
 */
class AgentContextBudgetsTest {

  @Test
  @DisplayName("the tool-result cap changes when the window changes at runtime, in one JVM")
  void toolResultCapTracksTheWindowWithinOneJvm() {
    AtomicReference<Integer> window = new AtomicReference<>(4096);
    AgentContextCompressor compressor =
        new AgentContextCompressor(
            false, 200, 1, () -> AgentContextBudgets.forCall(fakeAi(window)));

    int atSmallWindow = compressor.toolResultCapChars();

    window.set(32768);
    int atLargeWindow = compressor.toolResultCapChars();

    assertTrue(
        atLargeWindow > atSmallWindow,
        "the cap must follow the window it is a fraction of: "
            + atSmallWindow
            + " -> "
            + atLargeWindow);
    assertEquals(2304, atSmallWindow, "min(2304/4, 2048) tokens * 4 chars");
    assertEquals(8192, atLargeWindow, "min(28108/4, 2048) tokens * 4 chars — at its ceiling");
    assertNotEquals(
        4000,
        atLargeWindow,
        "4000 was the class-init default; a value that never moves is the defect, not the fix");
  }

  @Test
  @DisplayName("the same truncation, at two windows, cuts at two different points")
  void truncationPointFollowsTheWindow() {
    AtomicReference<Integer> window = new AtomicReference<>(4096);
    AgentContextCompressor compressor =
        new AgentContextCompressor(
            false, 200, 1, () -> AgentContextBudgets.forCall(fakeAi(window)));

    String output = "x".repeat(20_000);
    int cutAtSmall = compressor.truncate(output).indexOf("\n[... truncated,");

    window.set(32768);
    int cutAtLarge = compressor.truncate(output).indexOf("\n[... truncated,");

    assertTrue(cutAtLarge > cutAtSmall, cutAtSmall + " -> " + cutAtLarge);
    assertEquals(2304, cutAtSmall);
    assertEquals(8192, cutAtLarge);
  }

  @Test
  @DisplayName("the completion reserve is derived per call, and only ever shrinks below 4096")
  void completionReserveIsPerCall() {
    AtomicReference<Integer> window = new AtomicReference<>(2048);
    assertEquals(512, AgentContextBudgets.forCall(fakeAi(window)).completionReserve());

    window.set(4096);
    assertEquals(1024, AgentContextBudgets.forCall(fakeAi(window)).completionReserve());

    window.set(32768);
    assertEquals(
        1024,
        AgentContextBudgets.forCall(fakeAi(window)).completionReserve(),
        "a larger window does not make the ANSWER longer, and AgentBudgetPolicy's spend bound is"
            + " stated against this ceiling");
  }

  @Test
  @DisplayName("a window too small to answer at all still gets the 256-token floor")
  void reserveNeverFallsBelowTheAnswerFloor() {
    AtomicReference<Integer> window = new AtomicReference<>(512);
    ContextBudget budget = AgentContextBudgets.forCall(fakeAi(window));
    assertEquals(
        AgentContextBudgets.MIN_COMPLETION_TOKENS,
        budget.completionReserve(),
        "512/4 = 128 tokens cannot produce an answer; the floor wins");
    assertEquals(
        512, budget.windowTokens(), "and the budget is recomputed against the floor, not patched");
  }

  @Test
  @DisplayName("the read page grows with the window and never exceeds the Layer-2 cut")
  void readPageGrowsWithTheWindowAndStaysUnderTheCut() {
    int atSmall = ReadDocumentTool.readPageChars(budgetAt(4096));
    int atLarge = ReadDocumentTool.readPageChars(budgetAt(32768));

    assertTrue(atLarge > atSmall, "the page must follow the window: " + atSmall + " -> " + atLarge);
    assertNotEquals(3000, atLarge, "3000 was the literal sized 'well inside n_ctx 4096'");

    for (int window : new int[] {2048, 4096, 8192, 16384, 32768}) {
      ContextBudget budget = budgetAt(window);
      int page = ReadDocumentTool.readPageChars(budget);
      int layerTwo = ToolResultCarrier.layerTwoCapChars(budget);
      // page == 0 is the DESIGNED refusal (execute() fails out loud below MIN_PAGE_CHARS), not a
      // page that gets clipped — the invariant is about pages that are actually served.
      assertTrue(page > 0, "every shipped rung must be able to serve a page; window " + window);
      assertTrue(
          page + ReadDocumentTool.PAGE_HEADROOM_CHARS <= layerTwo,
          "a full page plus its header must survive the Layer-2 cut at window "
              + window
              + ": page="
              + page
              + " headroom="
              + ReadDocumentTool.PAGE_HEADROOM_CHARS
              + " cap="
              + layerTwo);
    }
  }

  @Test
  @DisplayName("S1: an operator completion cap the window cannot afford is REDUCED, and said so")
  void operatorCapReductionIsReported() {
    // The knob is a ceiling on a window FRACTION, not a verbatim value, so a 2048-token window
    // cannot honour a configured 999. Reducing a number someone typed must not be silent: the
    // javadoc used to promise "honoured verbatim, never silently reduced", which was false.
    // The (cap, window) pair is deliberately unusual: the report is deduplicated per pair for the
    // life of the JVM, so a pair another test also used could be swallowed here.
    List<ILoggingEvent> logs =
        captureFrom(
            AgentContextBudgets.class,
            () -> {
              ContextBudget budget = AgentContextBudgets.forCall(2048, 2048, 999);
              assertEquals(512, budget.completionReserve(), "the reduction is real");
            });

    ILoggingEvent reported =
        logs.stream()
            .filter(e -> e.getFormattedMessage().contains("max_completion_tokens"))
            .findFirst()
            .orElseThrow(
                () ->
                    new AssertionError(
                        "no reduction was reported; logs were "
                            + logs.stream().map(ILoggingEvent::getFormattedMessage).toList()));
    assertEquals("INFO", reported.getLevel().toString());
    assertTrue(reported.getFormattedMessage().contains("999"), reported.getFormattedMessage());
    assertTrue(reported.getFormattedMessage().contains("512"), reported.getFormattedMessage());

    // ...and it is said ONCE per pair: this runs on every LLM call, and a line repeated per
    // iteration trains the reader to skip it.
    List<ILoggingEvent> again =
        captureFrom(AgentContextBudgets.class, () -> AgentContextBudgets.forCall(2048, 2048, 999));
    assertTrue(again.isEmpty(), "the repeat must be deduplicated: " + again.size() + " records");
  }

  @Test
  @DisplayName("S1: a cap the window CAN afford is applied quietly")
  void unreducedOperatorCapIsNotReported() {
    List<ILoggingEvent> logs =
        captureFrom(
            AgentContextBudgets.class,
            () -> {
              ContextBudget budget = AgentContextBudgets.forCall(32768, 32768, 1024);
              assertEquals(1024, budget.completionReserve(), "honoured whole");
            });
    assertTrue(
        logs.isEmpty(),
        "a budget that honoured the cap must say nothing; logs were "
            + logs.stream().map(ILoggingEvent::getFormattedMessage).toList());
  }

  @Test
  @DisplayName("S5: the Layer-2 bound dominates the page fraction at every rung")
  void pageFractionNeverBinds() {
    // Stated because the doc table lists readDocumentPageTokens() as a derivation: today it never
    // wins, because min(ib/2, 4096) >= min(ib/4, 2048) always. If a future change raises the
    // tool-result ceiling above the page ceiling, this goes red and the table stops being a lie.
    for (int window : new int[] {2048, 4096, 8192, 16384, 32768}) {
      ContextBudget b = budgetAt(window);
      assertTrue(
          b.readDocumentPageChars() >= b.toolResultCapChars(),
          "page fraction " + b.readDocumentPageChars() + " < cap " + b.toolResultCapChars());
      assertEquals(
          ToolResultCarrier.layerTwoCapChars(b) - ReadDocumentTool.PAGE_HEADROOM_CHARS,
          ReadDocumentTool.readPageChars(b),
          "the Layer-2 bound is what resolves the page at window " + window);
    }
  }

  private static List<ILoggingEvent> captureFrom(Class<?> type, Runnable action) {
    Logger logger = (Logger) LoggerFactory.getLogger(type);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    try {
      action.run();
      return List.copyOf(appender.list);
    } finally {
      logger.detachAppender(appender);
      appender.stop();
    }
  }

  /** The budget the agent loop itself derives at {@code window} with the shipped config. */
  private static ContextBudget budgetAt(int window) {
    AtomicReference<Integer> ref = new AtomicReference<>(window);
    return AgentContextBudgets.forCall(fakeAi(ref));
  }

  /** Minimal {@link OnlineAiService} that reports whatever window the reference currently holds. */
  private static OnlineAiService fakeAi(AtomicReference<Integer> window) {
    return new OnlineAiService() {
      @Override
      public boolean isAvailable() {
        return true;
      }

      @Override
      public boolean isStartingUp() {
        return false;
      }

      @Override
      public java.util.concurrent.CompletableFuture<String> askQuestion(
          String question, String context) {
        return java.util.concurrent.CompletableFuture.completedFuture("");
      }

      @Override
      public java.util.concurrent.CompletableFuture<String> summarize(String text) {
        return java.util.concurrent.CompletableFuture.completedFuture("");
      }

      @Override
      public Integer llmContextTokens() {
        return window.get();
      }

      @Override
      public Integer configuredContextTokens() {
        return window.get();
      }
    };
  }
}
