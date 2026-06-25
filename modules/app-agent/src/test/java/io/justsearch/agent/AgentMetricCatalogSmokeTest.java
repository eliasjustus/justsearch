package io.justsearch.agent;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.justsearch.agent.AgentTags.AgentBudgetEdgeTags;
import io.justsearch.agent.AgentTags.AgentErrorTags;
import io.justsearch.agent.AgentTags.AgentRetryExhaustedTags;
import io.justsearch.agent.AgentTags.AgentRetryTags;
import io.justsearch.agent.AgentTags.SessionEndedTags;
import io.justsearch.agent.AgentTags.ToolCallTags;
import io.justsearch.agent.AgentTags.ToolFailureTags;
import io.justsearch.agent.api.AgentErrorClass;
import io.justsearch.agent.api.AgentErrorCode;
import io.justsearch.telemetry.catalog.EmptyTags;
import io.justsearch.telemetry.catalog.TestMetricRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Tempdoc 417 F4: smoke test for {@link AgentMetricCatalog}. */
final class AgentMetricCatalogSmokeTest {

  private TestMetricRegistry registry;
  private AgentMetricCatalog catalog;

  @BeforeEach
  void setUp() {
    registry = new TestMetricRegistry(AgentMetricCatalog.DEFINITIONS);
    catalog = new AgentMetricCatalog(registry);
  }

  @AfterEach
  void tearDown() {
    if (registry != null) registry.close();
  }

  @Test
  void constructsAndEmitsAllInstruments() {
    assertNotNull(catalog.errorTotal);
    assertNotNull(catalog.retryTotal);
    assertNotNull(catalog.loopBlockedTotal);
    assertNotNull(catalog.budgetEdgeFinalizeTotal);
    assertNotNull(catalog.retryExhaustedTotal);
    // Tempdoc 415 F3b: 9 new session-lifecycle instruments.
    assertNotNull(catalog.sessionStartTotal);
    assertNotNull(catalog.sessionDurationMs);
    assertNotNull(catalog.sessionTerminateTotal);
    assertNotNull(catalog.sessionContextSizeBytesAtEnd);
    assertNotNull(catalog.sessionIterationsAtEnd);
    assertNotNull(catalog.sessionToolCallsAtEnd);
    assertNotNull(catalog.sessionToolCallTotal);
    assertNotNull(catalog.sessionToolFailureTotal);
    assertNotNull(catalog.sessionActiveCount);

    catalog.errorTotal.increment(
        new AgentErrorTags(AgentErrorCode.INTERNAL_ERROR, AgentErrorClass.PERMANENT));
    catalog.retryTotal.increment(new AgentRetryTags(AgentErrorCode.LLM_TRANSIENT, "1"));
    catalog.loopBlockedTotal.increment(EmptyTags.INSTANCE);
    catalog.budgetEdgeFinalizeTotal.increment(new AgentBudgetEdgeTags(true));
    catalog.retryExhaustedTotal.increment(new AgentRetryExhaustedTags(AgentErrorCode.LLM_TRANSIENT));
    // Exercise each new instrument so the smoke also catches NoopMetricRegistry-fallback bugs.
    catalog.sessionStartTotal.increment(EmptyTags.INSTANCE);
    catalog.sessionDurationMs.record(100L, EmptyTags.INSTANCE);
    catalog.sessionTerminateTotal.increment(
        new SessionEndedTags(TerminalDisposition.COMPLETED, null, null));
    catalog.sessionContextSizeBytesAtEnd.record(2048L, EmptyTags.INSTANCE);
    catalog.sessionIterationsAtEnd.record(2L, EmptyTags.INSTANCE);
    catalog.sessionToolCallsAtEnd.record(1L, EmptyTags.INSTANCE);
    catalog.sessionToolCallTotal.increment(new ToolCallTags("search_index"));
    catalog.sessionToolFailureTotal.increment(new ToolFailureTags("ingest_files"));

    assertEquals(1L, registry.counterValue(AgentMetricCatalog.LOOP_BLOCKED_TOTAL, EmptyTags.INSTANCE));
    assertEquals(
        1L, registry.counterValue(AgentMetricCatalog.SESSION_START_TOTAL, EmptyTags.INSTANCE));
    assertEquals(
        1L,
        registry.counterValue(
            AgentMetricCatalog.SESSION_TOOL_CALL_TOTAL, new ToolCallTags("search_index")));
  }

  @Test
  void noopCatalogDoesNotThrow() {
    var noop = AgentMetricCatalog.noop();
    assertDoesNotThrow(() -> noop.loopBlockedTotal.increment(EmptyTags.INSTANCE));
    // Tempdoc 415 F3b: noop catalog must accept session-lifecycle emissions too.
    assertDoesNotThrow(() -> noop.sessionStartTotal.increment(EmptyTags.INSTANCE));
    assertDoesNotThrow(
        () ->
            noop.sessionTerminateTotal.increment(
                new SessionEndedTags(
                    TerminalDisposition.CANCELLED, null, CancelTrigger.USER)));
    assertDoesNotThrow(
        () -> noop.sessionToolCallTotal.increment(new ToolCallTags("any-tool")));
    assertDoesNotThrow(() -> noop.sessionDurationMs.record(0L, EmptyTags.INSTANCE));
  }
}
