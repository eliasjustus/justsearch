/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent;

import io.justsearch.agent.api.AgentErrorClass;
import io.justsearch.agent.api.AgentErrorCode;
import io.justsearch.telemetry.catalog.TagSchema;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Tag schemas for {@code agent.*} metrics. Reuses existing {@link AgentErrorCode} and
 * {@link AgentErrorClass} enums.
 */
public final class AgentTags {

  private AgentTags() {}

  static final String KEY_ERROR_CODE = "error_code";
  static final String KEY_ERROR_CLASS = "error_class";
  static final String KEY_ATTEMPT = "attempt";
  static final String KEY_SUCCESS = "success";

  // Tempdoc 415: tag keys for agent.session.* metrics.
  static final String KEY_TOOL_NAME = "tool_name";
  static final String KEY_DISPOSITION = "disposition";
  static final String KEY_CANCEL_TRIGGER = "cancel_trigger";

  // Tempdoc 585 §D Phase 1 (A1): per-event-type observability.
  static final String KEY_EVENT_TYPE = "event_type";

  static final Set<String> ERROR_KEYS;
  static final Set<String> RETRY_KEYS;
  static final Set<String> ERROR_CODE_ONLY_KEYS = Set.of(KEY_ERROR_CODE);
  static final Set<String> SUCCESS_KEYS = Set.of(KEY_SUCCESS);

  // Tempdoc 415 key sets.
  static final Set<String> SESSION_ENDED_KEYS;
  static final Set<String> TOOL_CALL_KEYS = Set.of(KEY_TOOL_NAME);
  static final Set<String> TOOL_FAILURE_KEYS = Set.of(KEY_TOOL_NAME);

  // Tempdoc 585 §D Phase 1 (A1).
  static final Set<String> EVENT_TYPE_KEYS = Set.of(KEY_EVENT_TYPE);

  static {
    Set<String> ek = new LinkedHashSet<>();
    ek.add(KEY_ERROR_CODE);
    ek.add(KEY_ERROR_CLASS);
    ERROR_KEYS = ek;

    Set<String> rk = new LinkedHashSet<>();
    rk.add(KEY_ERROR_CODE);
    rk.add(KEY_ATTEMPT);
    RETRY_KEYS = rk;

    Set<String> sek = new LinkedHashSet<>();
    sek.add(KEY_DISPOSITION);
    sek.add(KEY_ERROR_CODE);
    sek.add(KEY_CANCEL_TRIGGER);
    SESSION_ENDED_KEYS = sek;
  }

  /** Tag schema for {@code agent.error.total}. */
  public record AgentErrorTags(AgentErrorCode code, AgentErrorClass klass) implements TagSchema {

    public AgentErrorTags {
      Objects.requireNonNull(code, "code");
      Objects.requireNonNull(klass, "klass");
    }

    @Override
    public Set<String> allowedKeys() {
      return ERROR_KEYS;
    }

    @Override
    public Attributes toAttributes() {
      return Attributes.builder()
          .put(AttributeKey.stringKey(KEY_ERROR_CODE), code.name())
          .put(AttributeKey.stringKey(KEY_ERROR_CLASS), klass.name())
          .build();
    }
  }

  /** Tag schema for {@code agent.retry.total}. */
  public record AgentRetryTags(AgentErrorCode code, String attempt) implements TagSchema {

    public AgentRetryTags {
      Objects.requireNonNull(code, "code");
      Objects.requireNonNull(attempt, "attempt");
    }

    @Override
    public Set<String> allowedKeys() {
      return RETRY_KEYS;
    }

    @Override
    public Attributes toAttributes() {
      return Attributes.builder()
          .put(AttributeKey.stringKey(KEY_ERROR_CODE), code.name())
          .put(AttributeKey.stringKey(KEY_ATTEMPT), attempt)
          .build();
    }
  }

  /** Tag schema for {@code agent.retry.exhausted.total}. */
  public record AgentRetryExhaustedTags(AgentErrorCode code) implements TagSchema {

    public AgentRetryExhaustedTags {
      Objects.requireNonNull(code, "code");
    }

    @Override
    public Set<String> allowedKeys() {
      return ERROR_CODE_ONLY_KEYS;
    }

    @Override
    public Attributes toAttributes() {
      return Attributes.of(AttributeKey.stringKey(KEY_ERROR_CODE), code.name());
    }
  }

  /** Tag schema for {@code agent.budget_edge_finalize.total}. */
  public record AgentBudgetEdgeTags(boolean success) implements TagSchema {

    @Override
    public Set<String> allowedKeys() {
      return SUCCESS_KEYS;
    }

    @Override
    public Attributes toAttributes() {
      return Attributes.of(AttributeKey.stringKey(KEY_SUCCESS), Boolean.toString(success));
    }
  }

  /**
   * Tag schema for {@code agent.session.terminate_total} (tempdoc 415). Cross-product of
   * {@link TerminalDisposition} (always present), {@link AgentErrorCode} (present only when
   * disposition is {@code ERRORED}), and {@link CancelTrigger} (present only when disposition is
   * {@code CANCELLED}). The conditional emission keeps the tuple bounded — many disposition/code
   * combinations are impossible by construction.
   */
  public record SessionEndedTags(
      TerminalDisposition disposition, AgentErrorCode errorCode, CancelTrigger cancelTrigger)
      implements TagSchema {

    public SessionEndedTags {
      Objects.requireNonNull(disposition, "disposition");
    }

    @Override
    public Set<String> allowedKeys() {
      return SESSION_ENDED_KEYS;
    }

    /**
     * Tempdoc 415 F7: conditional emission. {@code disposition} is always emitted;
     * {@code error_code} only when non-null (i.e., {@code disposition == ERRORED});
     * {@code cancel_trigger} only when non-null (i.e., {@code disposition == CANCELLED}).
     *
     * <p>{@link #allowedKeys()} returns the full superset of three keys; {@code
     * toAttributes()} returns a subset. The OTel View's {@code setAttributeFilter} (per
     * ADR-0027) only filters out non-allowed keys; missing allowed keys flow through.
     * {@code AgentMetricWireFormatRegressionTest} verifies the NDJSON shape is byte-stable.
     *
     * <p>This is the only {@code TagSchema} in the codebase that emits a strict subset of
     * its declared keys. Future schemas with similar shapes should match this convention
     * and extend the wire-format regression test.
     */
    @Override
    public Attributes toAttributes() {
      var builder = Attributes.builder();
      builder.put(AttributeKey.stringKey(KEY_DISPOSITION), disposition.name());
      if (errorCode != null) {
        builder.put(AttributeKey.stringKey(KEY_ERROR_CODE), errorCode.name());
      }
      if (cancelTrigger != null) {
        builder.put(AttributeKey.stringKey(KEY_CANCEL_TRIGGER), cancelTrigger.name());
      }
      return builder.build();
    }
  }

  /**
   * Tag schema for {@code agent.session.tool_call_total} (tempdoc 415). {@code tool_name} is
   * the wireName of a registered Operation from {@code AgentToolsOperationCatalog} —
   * bounded set (4 today). Handoff calls ({@code handoff_to_<agentId>}) are not in this tag
   * space because the loop branches out before reaching the post-resolve emit point.
   */
  public record ToolCallTags(String toolName) implements TagSchema {

    public ToolCallTags {
      Objects.requireNonNull(toolName, "toolName");
    }

    @Override
    public Set<String> allowedKeys() {
      return TOOL_CALL_KEYS;
    }

    @Override
    public Attributes toAttributes() {
      return Attributes.of(AttributeKey.stringKey(KEY_TOOL_NAME), toolName);
    }
  }

  /**
   * Tag schema for {@code agent.event.emit_total} (tempdoc 585 §D Phase 1 / A1). {@code event_type}
   * is the wire event name from {@code AgentEventPayloads.name} — a bounded set (21 today), capped by
   * the metric's cardinality limit. Emitted once per event at the run's single publish chokepoint, so
   * the counter measures the agent's observable behaviour (tool-call rate, gate-fire rate, etc.) from
   * one place.
   */
  public record AgentEventTypeTags(String eventType) implements TagSchema {

    public AgentEventTypeTags {
      Objects.requireNonNull(eventType, "eventType");
    }

    @Override
    public Set<String> allowedKeys() {
      return EVENT_TYPE_KEYS;
    }

    @Override
    public Attributes toAttributes() {
      return Attributes.of(AttributeKey.stringKey(KEY_EVENT_TYPE), eventType);
    }
  }

  /**
   * Tag schema for {@code agent.session.tool_failure_total} (tempdoc 415). {@code tool_name} is
   * the name of a registered tool whose execution returned {@code !success()} after policy
   * retries. Single-key by design — per-call retry-class signal already comes from
   * {@code agent.retry.total{error_code=TOOL_TRANSIENT_READ_ONLY}}.
   */
  public record ToolFailureTags(String toolName) implements TagSchema {

    public ToolFailureTags {
      Objects.requireNonNull(toolName, "toolName");
    }

    @Override
    public Set<String> allowedKeys() {
      return TOOL_FAILURE_KEYS;
    }

    @Override
    public Attributes toAttributes() {
      return Attributes.of(AttributeKey.stringKey(KEY_TOOL_NAME), toolName);
    }
  }
}
