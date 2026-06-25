package io.justsearch.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
import java.util.ArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AgentTelemetryTest {

  private TestMetricRegistry registry;
  private AgentTelemetry agentTelemetry;

  @BeforeEach
  void setUp() {
    // F8: single registry containing both catalogs' DEFINITIONS, mirroring production wiring
    // (LocalTelemetry's registry holds all catalogs together).
    var defs = new ArrayList<>(AgentMetricCatalog.DEFINITIONS);
    defs.addAll(GenAiMetricCatalog.DEFINITIONS);
    registry = new TestMetricRegistry(defs);
    agentTelemetry =
        new AgentTelemetry(new AgentMetricCatalog(registry), new GenAiMetricCatalog(registry));
  }

  @AfterEach
  void tearDown() {
    if (registry != null) registry.close();
  }

  @Test
  void recordsAllCounterFamilies() {
    agentTelemetry.recordError(AgentErrorCode.LLM_TRANSIENT, AgentErrorClass.TRANSIENT);
    agentTelemetry.recordRetry(AgentErrorCode.LLM_TRANSIENT, 1);
    agentTelemetry.recordRetryExhausted(AgentErrorCode.LLM_TRANSIENT);

    assertEquals(
        1L,
        registry.counterValue(
            AgentMetricCatalog.ERROR_TOTAL,
            new AgentErrorTags(AgentErrorCode.LLM_TRANSIENT, AgentErrorClass.TRANSIENT)));
    assertEquals(
        1L,
        registry.counterValue(
            AgentMetricCatalog.RETRY_TOTAL,
            new AgentRetryTags(AgentErrorCode.LLM_TRANSIENT, "1")));
    assertEquals(
        1L,
        registry.counterValue(
            AgentMetricCatalog.RETRY_EXHAUSTED_TOTAL,
            new AgentRetryExhaustedTags(AgentErrorCode.LLM_TRANSIENT)));
  }

  @Test
  void noopTelemetryDoesNothing() {
    var noop = AgentTelemetry.noop();
    noop.recordError(AgentErrorCode.INTERNAL_ERROR, AgentErrorClass.PERMANENT);
    noop.recordRetry(AgentErrorCode.LLM_TRANSIENT, 1);
    noop.recordRetryExhausted(AgentErrorCode.LLM_TRANSIENT);
    // Tempdoc 415 F3a: also exercise session-lifecycle methods on noop.
    noop.recordSessionStart();
    noop.recordSessionEnd(
        TerminalDisposition.COMPLETED, null, null, 1234L, 8192, 3, 2);
    noop.recordToolCall("search_index");
    noop.recordToolFailure("ingest_files");
    // No assertion needed: just verifies emission paths don't throw.
  }

  // ---------------------------------------------------------------------------
  // Tempdoc 415 F3a — session-lifecycle method coverage
  // ---------------------------------------------------------------------------

  @Test
  void recordsAllSessionLifecycleFamilies() {
    agentTelemetry.recordSessionStart();
    agentTelemetry.recordSessionEnd(
        TerminalDisposition.COMPLETED, null, null, 1234L, 8192, 3, 2);
    agentTelemetry.recordToolCall("search_index");
    agentTelemetry.recordToolFailure("ingest_files");

    assertEquals(
        1L,
        registry.counterValue(AgentMetricCatalog.SESSION_START_TOTAL, EmptyTags.INSTANCE));
    assertEquals(
        1L,
        registry.counterValue(
            AgentMetricCatalog.SESSION_TERMINATE_TOTAL,
            new SessionEndedTags(TerminalDisposition.COMPLETED, null, null)));
    assertEquals(
        1L,
        registry.histogramCount(AgentMetricCatalog.SESSION_DURATION_MS, EmptyTags.INSTANCE));
    assertEquals(
        1L,
        registry.histogramCount(
            AgentMetricCatalog.SESSION_CONTEXT_SIZE_BYTES_AT_END, EmptyTags.INSTANCE));
    assertEquals(
        1L,
        registry.histogramCount(AgentMetricCatalog.SESSION_ITERATIONS_AT_END, EmptyTags.INSTANCE));
    assertEquals(
        1L,
        registry.histogramCount(AgentMetricCatalog.SESSION_TOOL_CALLS_AT_END, EmptyTags.INSTANCE));
    assertEquals(
        1L,
        registry.counterValue(
            AgentMetricCatalog.SESSION_TOOL_CALL_TOTAL, new ToolCallTags("search_index")));
    assertEquals(
        1L,
        registry.counterValue(
            AgentMetricCatalog.SESSION_TOOL_FAILURE_TOTAL, new ToolFailureTags("ingest_files")));
  }

  @Test
  void recordSessionEnd_skipsWhenDispositionNull() {
    // Defensive: null disposition should not throw or emit.
    agentTelemetry.recordSessionEnd(null, null, null, 100L, 0, 0, 0);
    assertEquals(
        0L,
        registry.histogramCount(AgentMetricCatalog.SESSION_DURATION_MS, EmptyTags.INSTANCE));
  }

  @Test
  void recordSessionEnd_emitsConditionalTagsForErrored() {
    // ERRORED disposition carries error_code, no cancel_trigger.
    agentTelemetry.recordSessionEnd(
        TerminalDisposition.ERRORED, AgentErrorCode.BUDGET_EXHAUSTED, null, 500L, 4096, 2, 1);

    assertEquals(
        1L,
        registry.counterValue(
            AgentMetricCatalog.SESSION_TERMINATE_TOTAL,
            new SessionEndedTags(
                TerminalDisposition.ERRORED, AgentErrorCode.BUDGET_EXHAUSTED, null)));
    // A different errorCode lands in a different bin — should be empty.
    assertEquals(
        0L,
        registry.counterValue(
            AgentMetricCatalog.SESSION_TERMINATE_TOTAL,
            new SessionEndedTags(
                TerminalDisposition.ERRORED, AgentErrorCode.LLM_TRANSIENT, null)));
  }

  @Test
  void recordSessionEnd_emitsConditionalTagsForCancelled() {
    // CANCELLED disposition carries cancel_trigger, no error_code.
    agentTelemetry.recordSessionEnd(
        TerminalDisposition.CANCELLED, null, CancelTrigger.USER, 250L, 2048, 1, 0);

    assertEquals(
        1L,
        registry.counterValue(
            AgentMetricCatalog.SESSION_TERMINATE_TOTAL,
            new SessionEndedTags(TerminalDisposition.CANCELLED, null, CancelTrigger.USER)));
    // BUDGET trigger landed in a different bin — should be empty.
    assertEquals(
        0L,
        registry.counterValue(
            AgentMetricCatalog.SESSION_TERMINATE_TOTAL,
            new SessionEndedTags(TerminalDisposition.CANCELLED, null, CancelTrigger.BUDGET)));
  }

  @Test
  void recordToolCall_skipsBlankAndNull() {
    agentTelemetry.recordToolCall(null);
    agentTelemetry.recordToolCall("");
    agentTelemetry.recordToolCall("   ");
    // No emit for any of them.
    assertEquals(
        0L,
        registry.counterValue(
            AgentMetricCatalog.SESSION_TOOL_CALL_TOTAL, new ToolCallTags("anything")));
  }
}
