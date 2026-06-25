/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent;

import io.justsearch.agent.AgentTags.AgentBudgetEdgeTags;
import io.justsearch.agent.AgentTags.AgentErrorTags;
import io.justsearch.agent.AgentTags.AgentRetryExhaustedTags;
import io.justsearch.agent.AgentTags.AgentRetryTags;
import io.justsearch.agent.AgentTags.SessionEndedTags;
import io.justsearch.agent.AgentTags.ToolCallTags;
import io.justsearch.agent.AgentTags.ToolFailureTags;
import io.justsearch.telemetry.catalog.Buckets;
import io.justsearch.telemetry.catalog.CounterMetric;
import io.justsearch.telemetry.catalog.EmptyTags;
import io.justsearch.telemetry.catalog.GaugeMetric;
import io.justsearch.telemetry.catalog.HistogramMetric;
import io.justsearch.telemetry.catalog.MetricCatalog;
import io.justsearch.telemetry.catalog.MetricDefinition;
import io.justsearch.telemetry.catalog.MetricRegistry;
import io.justsearch.telemetry.catalog.NoopMetricRegistry;
import io.justsearch.telemetry.catalog.RrdArchive;
import io.justsearch.telemetry.catalog.StatusEndpoint;
import io.justsearch.telemetry.catalog.Unit;
import java.util.List;
import java.util.Objects;
import java.util.function.IntSupplier;

/**
 * Catalog for {@code agent.*} metrics. Tempdoc 417 Phase 2d covered the original 5
 * counters; tempdoc 415 adds 9 {@code agent.session.*} lifecycle metrics including the
 * {@link #SESSION_ACTIVE_COUNT} gauge surfaced via {@code /api/status}.
 */
public final class AgentMetricCatalog implements MetricCatalog {

  public static final String NAMESPACE = "agent";

  public static final String ERROR_TOTAL = "agent.error.total";
  public static final String RETRY_TOTAL = "agent.retry.total";
  public static final String LOOP_BLOCKED_TOTAL = "agent.loop.blocked.total";
  public static final String BUDGET_EDGE_FINALIZE_TOTAL = "agent.budget_edge_finalize.total";
  public static final String RETRY_EXHAUSTED_TOTAL = "agent.retry.exhausted.total";

  // Tempdoc 415: agent.session.* lifecycle metrics.
  public static final String SESSION_START_TOTAL = "agent.session.start_total";
  public static final String SESSION_DURATION_MS = "agent.session.duration_ms";
  public static final String SESSION_TERMINATE_TOTAL = "agent.session.terminate_total";
  public static final String SESSION_CONTEXT_SIZE_BYTES_AT_END =
      "agent.session.context_size_bytes_at_end";
  public static final String SESSION_ITERATIONS_AT_END = "agent.session.iterations_at_end";
  public static final String SESSION_TOOL_CALLS_AT_END = "agent.session.tool_calls_at_end";
  public static final String SESSION_TOOL_CALL_TOTAL = "agent.session.tool_call_total";
  public static final String SESSION_TOOL_FAILURE_TOTAL = "agent.session.tool_failure_total";
  public static final String SESSION_ACTIVE_COUNT = "agent.session.active_count";

  // Tempdoc 585 §D Phase 1 (A1): per-event-type emission counter (one chokepoint, all 21 events).
  public static final String EVENT_EMIT_TOTAL = "agent.event.emit_total";

  public static final List<MetricDefinition> DEFINITIONS =
      List.of(
          MetricDefinition.counter(ERROR_TOTAL)
              .unit(Unit.COUNT)
              .tagKeys(AgentTags.ERROR_KEYS)
              .build(),
          MetricDefinition.counter(RETRY_TOTAL)
              .unit(Unit.COUNT)
              .tagKeys(AgentTags.RETRY_KEYS)
              .cardinalityLimit(16)
              .build(),
          MetricDefinition.counter(LOOP_BLOCKED_TOTAL).unit(Unit.COUNT).build(),
          MetricDefinition.counter(BUDGET_EDGE_FINALIZE_TOTAL)
              .unit(Unit.COUNT)
              .tagKeys(AgentTags.SUCCESS_KEYS)
              .build(),
          MetricDefinition.counter(RETRY_EXHAUSTED_TOTAL)
              .unit(Unit.COUNT)
              .tagKeys(AgentTags.ERROR_CODE_ONLY_KEYS)
              .build(),
          // Tempdoc 415: session-lifecycle metrics.
          MetricDefinition.counter(SESSION_START_TOTAL).unit(Unit.COUNT).build(),
          MetricDefinition.histogram(SESSION_DURATION_MS)
              .unit(Unit.MILLISECONDS)
              .buckets(Buckets.TIME_HISTOGRAM)
              .build(),
          MetricDefinition.counter(SESSION_TERMINATE_TOTAL)
              .unit(Unit.COUNT)
              .tagKeys(AgentTags.SESSION_ENDED_KEYS)
              .build(),
          MetricDefinition.histogram(SESSION_CONTEXT_SIZE_BYTES_AT_END)
              .unit(Unit.BYTES)
              .buckets(Buckets.BYTE_SIZE_HISTOGRAM)
              .build(),
          MetricDefinition.histogram(SESSION_ITERATIONS_AT_END)
              .unit(Unit.COUNT)
              .buckets(Buckets.SMALL_COUNT_HISTOGRAM)
              .build(),
          MetricDefinition.histogram(SESSION_TOOL_CALLS_AT_END)
              .unit(Unit.COUNT)
              .buckets(Buckets.SMALL_COUNT_HISTOGRAM)
              .build(),
          MetricDefinition.counter(SESSION_TOOL_CALL_TOTAL)
              .unit(Unit.COUNT)
              .tagKeys(AgentTags.TOOL_CALL_KEYS)
              .build(),
          MetricDefinition.counter(SESSION_TOOL_FAILURE_TOTAL)
              .unit(Unit.COUNT)
              .tagKeys(AgentTags.TOOL_FAILURE_KEYS)
              .build(),
          MetricDefinition.gauge(SESSION_ACTIVE_COUNT)
              .unit(Unit.COUNT)
              .archivedTo(RrdArchive.STANDARD)
              .surfacedAt(StatusEndpoint.AGENT_SESSION_VIEW, "activeCount")
              .build(),
          // Tempdoc 585 §D Phase 1 (A1): one counter, tagged by event-type name (21 today).
          MetricDefinition.counter(EVENT_EMIT_TOTAL)
              .unit(Unit.COUNT)
              .tagKeys(AgentTags.EVENT_TYPE_KEYS)
              .cardinalityLimit(32)
              .build());

  static {
    String prefix = NAMESPACE + ".";
    for (MetricDefinition def : DEFINITIONS) {
      if (!def.name().startsWith(prefix)) {
        throw new ExceptionInInitializerError(
            "AgentMetricCatalog metric '"
                + def.name()
                + "' does not match namespace '"
                + NAMESPACE
                + "'");
      }
    }
  }

  public final CounterMetric<AgentErrorTags> errorTotal;
  public final CounterMetric<AgentRetryTags> retryTotal;
  public final CounterMetric<EmptyTags> loopBlockedTotal;
  public final CounterMetric<AgentBudgetEdgeTags> budgetEdgeFinalizeTotal;
  public final CounterMetric<AgentRetryExhaustedTags> retryExhaustedTotal;

  // Tempdoc 415 instruments.
  public final CounterMetric<EmptyTags> sessionStartTotal;
  public final HistogramMetric<EmptyTags> sessionDurationMs;
  public final CounterMetric<SessionEndedTags> sessionTerminateTotal;
  public final HistogramMetric<EmptyTags> sessionContextSizeBytesAtEnd;
  public final HistogramMetric<EmptyTags> sessionIterationsAtEnd;
  public final HistogramMetric<EmptyTags> sessionToolCallsAtEnd;
  public final CounterMetric<ToolCallTags> sessionToolCallTotal;
  public final CounterMetric<ToolFailureTags> sessionToolFailureTotal;
  public final GaugeMetric<EmptyTags> sessionActiveCount;
  public final CounterMetric<AgentTags.AgentEventTypeTags> eventEmitTotal;

  /** Legacy / test-only constructor: defaults the active-count supplier to {@code () -> 0}. */
  public AgentMetricCatalog(MetricRegistry registry) {
    this(registry, () -> 0);
  }

  /**
   * Production constructor: takes a live {@code activeSessionSupplier} (typically
   * {@code () -> agentLoopService.activeSessionCount()}). The supplier must be safe to invoke
   * during async gauge sampling — return 0 if the underlying state isn't yet initialized.
   */
  public AgentMetricCatalog(MetricRegistry registry, IntSupplier activeSessionSupplier) {
    Objects.requireNonNull(registry, "registry");
    Objects.requireNonNull(activeSessionSupplier, "activeSessionSupplier");
    this.errorTotal = registry.buildCounter(ERROR_TOTAL);
    this.retryTotal = registry.buildCounter(RETRY_TOTAL);
    this.loopBlockedTotal = registry.buildCounter(LOOP_BLOCKED_TOTAL);
    this.budgetEdgeFinalizeTotal = registry.buildCounter(BUDGET_EDGE_FINALIZE_TOTAL);
    this.retryExhaustedTotal = registry.buildCounter(RETRY_EXHAUSTED_TOTAL);
    this.sessionStartTotal = registry.buildCounter(SESSION_START_TOTAL);
    this.sessionDurationMs = registry.buildHistogram(SESSION_DURATION_MS);
    this.sessionTerminateTotal = registry.buildCounter(SESSION_TERMINATE_TOTAL);
    this.sessionContextSizeBytesAtEnd = registry.buildHistogram(SESSION_CONTEXT_SIZE_BYTES_AT_END);
    this.sessionIterationsAtEnd = registry.buildHistogram(SESSION_ITERATIONS_AT_END);
    this.sessionToolCallsAtEnd = registry.buildHistogram(SESSION_TOOL_CALLS_AT_END);
    this.sessionToolCallTotal = registry.buildCounter(SESSION_TOOL_CALL_TOTAL);
    this.sessionToolFailureTotal = registry.buildCounter(SESSION_TOOL_FAILURE_TOTAL);
    this.sessionActiveCount =
        registry.buildGauge(
            SESSION_ACTIVE_COUNT,
            EmptyTags.INSTANCE,
            () -> (double) activeSessionSupplier.getAsInt());
    this.eventEmitTotal = registry.buildCounter(EVENT_EMIT_TOTAL);
  }

  /** Cached no-op singleton. F6 fix. */
  private static final AgentMetricCatalog NOOP =
      new AgentMetricCatalog(new NoopMetricRegistry(DEFINITIONS));

  /** No-op catalog for tests / bootstrap paths without {@code LocalTelemetry}. */
  public static AgentMetricCatalog noop() {
    return NOOP;
  }

  @Override
  public String namespace() {
    return NAMESPACE;
  }

  @Override
  public List<MetricDefinition> definitions() {
    return DEFINITIONS;
  }
}
