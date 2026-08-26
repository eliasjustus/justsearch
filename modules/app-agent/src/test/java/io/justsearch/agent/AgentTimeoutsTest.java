/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.agent.tools.AgentToolErrors;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 877 §2.8 — pins {@link AgentTimeouts}'s defaults (a silent change is a red test), the two
 * system-property overrides it preserves, and the ordering invariant the corrected
 * {@code AgentStepRunner#handleVirtualToolCall} javadoc now states: the virtual-tool wait is
 * deliberately much shorter than the approval gate.
 */
class AgentTimeoutsTest {

  private static final String BUDGET_PROP = "justsearch.agent.budgetGateTimeoutSec";
  private static final String CONTEXT_PROP = "justsearch.agent.contextGateTimeoutSec";

  @AfterEach
  void restoreModuleTestDefaults() {
    // modules/app-agent/build.gradle.kts pins both to "0" for the whole test JVM (577 §2.12 Move 2 /
    // §2.14 Root II) so an interactive-gate test never blocks the suite; restore that convention
    // rather than leaving whatever value the last test in this class set.
    System.setProperty(BUDGET_PROP, "0");
    System.setProperty(CONTEXT_PROP, "0");
  }

  @Test
  void llmCallMs_defaultIsFiveMinutes() {
    assertEquals(TimeUnit.MINUTES.toMillis(5), AgentTimeouts.llmCallMs());
  }

  @Test
  void sessionAttachMs_defaultIsThirtyMinutes() {
    assertEquals(TimeUnit.MINUTES.toMillis(30), AgentTimeouts.sessionAttachMs());
  }

  @Test
  void approvalGateMs_defaultIsThreeHundredSeconds() {
    assertEquals(TimeUnit.SECONDS.toMillis(300), AgentTimeouts.approvalGateMs());
  }

  @Test
  void virtualToolMs_defaultIsThirtySeconds() {
    assertEquals(TimeUnit.SECONDS.toMillis(30), AgentTimeouts.virtualToolMs());
  }

  @Test
  void citationMatchMs_defaultIsFourThousand() {
    assertEquals(4000L, AgentTimeouts.citationMatchMs());
  }

  @Test
  void toolFetchMs_defaultIsFifteenSeconds() {
    assertEquals(TimeUnit.SECONDS.toMillis(15), AgentTimeouts.toolFetchMs());
  }

  @Test
  void fileOpConflictToleranceMs_defaultIsTwoSeconds() {
    assertEquals(TimeUnit.SECONDS.toMillis(2), AgentTimeouts.fileOpConflictToleranceMs());
  }

  @Test
  void contextGateMs_defaultIsOneHundredTwentySeconds() {
    String prev = System.getProperty(CONTEXT_PROP);
    System.clearProperty(CONTEXT_PROP);
    try {
      assertEquals(TimeUnit.SECONDS.toMillis(120), AgentTimeouts.contextGateMs());
    } finally {
      restoreOrClear(CONTEXT_PROP, prev);
    }
  }

  @Test
  void budgetGateMs_defaultIsOneHundredTwentySeconds() {
    String prev = System.getProperty(BUDGET_PROP);
    System.clearProperty(BUDGET_PROP);
    try {
      assertEquals(TimeUnit.SECONDS.toMillis(120), AgentTimeouts.budgetGateMs());
    } finally {
      restoreOrClear(BUDGET_PROP, prev);
    }
  }

  @Test
  void contextGateMs_honoursSystemPropertyOverride() {
    String prev = System.getProperty(CONTEXT_PROP);
    try {
      System.setProperty(CONTEXT_PROP, "7");
      assertEquals(TimeUnit.SECONDS.toMillis(7), AgentTimeouts.contextGateMs());
    } finally {
      restoreOrClear(CONTEXT_PROP, prev);
    }
  }

  @Test
  void budgetGateMs_honoursSystemPropertyOverride() {
    String prev = System.getProperty(BUDGET_PROP);
    try {
      System.setProperty(BUDGET_PROP, "9");
      assertEquals(TimeUnit.SECONDS.toMillis(9), AgentTimeouts.budgetGateMs());
    } finally {
      restoreOrClear(BUDGET_PROP, prev);
    }
  }

  /**
   * The documented "fall through immediately" behaviour tests rely on (577 §2.12 Move 2 / §2.14 Root
   * II): a property explicitly set to 0 must yield 0ms, not the 120s default.
   */
  @Test
  void contextGateMs_propertySetToZeroYieldsZero() {
    String prev = System.getProperty(CONTEXT_PROP);
    try {
      System.setProperty(CONTEXT_PROP, "0");
      assertEquals(0L, AgentTimeouts.contextGateMs());
    } finally {
      restoreOrClear(CONTEXT_PROP, prev);
    }
  }

  @Test
  void budgetGateMs_propertySetToZeroYieldsZero() {
    String prev = System.getProperty(BUDGET_PROP);
    try {
      System.setProperty(BUDGET_PROP, "0");
      assertEquals(0L, AgentTimeouts.budgetGateMs());
    } finally {
      restoreOrClear(BUDGET_PROP, prev);
    }
  }

  /**
   * Guards against the accessor freezing the property at class-init: two different values read in
   * sequence within the same test (no class reload between them) must both take effect, or this
   * whole test class would be order-dependent on whichever value happened to be read first.
   */
  @Test
  void contextGateMs_readsPropertyFreshOnEveryCall_notFrozenAtClassInit() {
    String prev = System.getProperty(CONTEXT_PROP);
    try {
      System.setProperty(CONTEXT_PROP, "3");
      assertEquals(TimeUnit.SECONDS.toMillis(3), AgentTimeouts.contextGateMs());
      System.setProperty(CONTEXT_PROP, "11");
      assertEquals(TimeUnit.SECONDS.toMillis(11), AgentTimeouts.contextGateMs());
    } finally {
      restoreOrClear(CONTEXT_PROP, prev);
    }
  }

  @Test
  void budgetGateMs_readsPropertyFreshOnEveryCall_notFrozenAtClassInit() {
    String prev = System.getProperty(BUDGET_PROP);
    try {
      System.setProperty(BUDGET_PROP, "4");
      assertEquals(TimeUnit.SECONDS.toMillis(4), AgentTimeouts.budgetGateMs());
      System.setProperty(BUDGET_PROP, "13");
      assertEquals(TimeUnit.SECONDS.toMillis(13), AgentTimeouts.budgetGateMs());
    } finally {
      restoreOrClear(BUDGET_PROP, prev);
    }
  }

  /**
   * The invariant the fixed {@code AgentStepRunner#handleVirtualToolCall} javadoc now states: the
   * virtual-tool wait is deliberately much SHORTER than the approval gate, not "aligned" with it (the
   * stale claim this tempdoc corrects — the two used to differ 10x with a comment claiming parity).
   */
  @Test
  void virtualToolMs_isShorterThanApprovalGateMs() {
    assertTrue(
        AgentTimeouts.virtualToolMs() < AgentTimeouts.approvalGateMs(),
        "virtual-tool wait must stay shorter than the approval gate: an FE that never answers a"
            + " virtual tool must not hold the agent loop for the full approval window");
  }

  @Test
  @DisplayName("877 §2.8: a fetch that never returns becomes a RETRYABLE timeout, not a hung loop")
  void call_abandonsAnUnresponsiveFetch() {
    // Before this, SearchTool/BrowseTool/IngestTool called the Worker straight from the agent loop
    // thread with no budget at all: an unresponsive Worker held the run forever. The explicit
    // budget here is what keeps the assertion instant — the production one is 15 seconds.
    Callable<String> neverReturns =
        () -> {
          new CountDownLatch(1).await();
          return "unreachable";
        };

    TimeoutException thrown =
        assertThrows(
            TimeoutException.class,
            () -> AgentTimeouts.call("core_search_index", 60L, neverReturns));

    assertTrue(
        thrown.getMessage().contains("core_search_index"),
        "the timeout must name the tool that stalled: " + thrown.getMessage());

    OperationResult classified =
        AgentToolErrors.classify("core_search_index", "Search error", thrown);
    assertFalse(classified.success());
    assertEquals(
        Boolean.TRUE,
        classified.retryable().orElseThrow(),
        "an abandoned Worker fetch is transient — the model may try again");
  }

  @Test
  @DisplayName("877 §2.8: a fetch that returns in time is passed through untouched")
  void call_returnsTheValueWhenInsideTheBudget() throws Exception {
    assertEquals("ok", AgentTimeouts.call("core_browse_folders", 5_000L, () -> "ok"));
  }

  @Test
  @DisplayName("877 §2.8: the callable's own failure propagates, it is not masked as a timeout")
  void call_propagatesTheCallablesOwnFailure() {
    IllegalStateException thrown =
        assertThrows(
            IllegalStateException.class,
            () ->
                AgentTimeouts.call(
                    "core_ingest_files",
                    5_000L,
                    () -> {
                      throw new IllegalStateException("worker said no");
                    }));
    assertEquals("worker said no", thrown.getMessage());
  }

  private static void restoreOrClear(String key, String prev) {
    if (prev == null) {
      System.clearProperty(key);
    } else {
      System.setProperty(key, prev);
    }
  }
}
