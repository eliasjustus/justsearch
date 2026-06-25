/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

import io.justsearch.telemetry.catalog.TagSchema;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/** Tag schemas for {@code ipc.*} metrics. */
public final class IpcTags {

  private IpcTags() {}

  static final String KEY_OUTCOME = "outcome";
  static final String KEY_FROM = "from";
  static final String KEY_TO = "to";

  static final Set<String> OUTCOME_KEYS = Set.of(KEY_OUTCOME);
  static final Set<String> STATE_CHANGE_KEYS;

  static {
    Set<String> sk = new LinkedHashSet<>();
    sk.add(KEY_FROM);
    sk.add(KEY_TO);
    STATE_CHANGE_KEYS = sk;
  }

  /** Tag schema for {@code ipc.worker.restart}. */
  public record WorkerRestartTags(WorkerRestartOutcome outcome) implements TagSchema {

    public WorkerRestartTags {
      Objects.requireNonNull(outcome, "outcome");
    }

    @Override
    public Set<String> allowedKeys() {
      return OUTCOME_KEYS;
    }

    @Override
    public Attributes toAttributes() {
      return Attributes.of(AttributeKey.stringKey(KEY_OUTCOME), outcome.wireValue());
    }
  }

  /** Tag schema for {@code ipc.circuit_breaker.state_change}. */
  public record CircuitBreakerStateChangeTags(CircuitBreakerState from, CircuitBreakerState to)
      implements TagSchema {

    public CircuitBreakerStateChangeTags {
      Objects.requireNonNull(from, "from");
      Objects.requireNonNull(to, "to");
    }

    @Override
    public Set<String> allowedKeys() {
      return STATE_CHANGE_KEYS;
    }

    @Override
    public Attributes toAttributes() {
      return Attributes.builder()
          .put(AttributeKey.stringKey(KEY_FROM), from.wireValue())
          .put(AttributeKey.stringKey(KEY_TO), to.wireValue())
          .build();
    }
  }
}
