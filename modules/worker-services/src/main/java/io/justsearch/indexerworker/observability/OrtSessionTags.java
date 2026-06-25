/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.observability;

import io.justsearch.ort.telemetry.AssemblerFailureKind;
import io.justsearch.ort.telemetry.CpuRecreateCause;
import io.justsearch.ort.telemetry.FailureCause;
import io.justsearch.ort.telemetry.Outcome;
import io.justsearch.telemetry.catalog.TagSchema;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Typed tag schemas for {@code ort.session.*} metrics. Each schema wraps the bounded
 * sealed-style enums in {@code modules/ort-common}'s telemetry package and projects to OTel
 * {@link Attributes} for the catalog to forward to the SDK.
 *
 * <p>Tempdoc 414 v2 (B1 fix): cause-tag records now type-discriminated by their cause enum
 * ({@link GpuInitFailureTags} carries {@link FailureCause}; {@link RecoveryTags} carries
 * {@link CpuRecreateCause}; {@link AssemblerFailureTags} carries {@link AssemblerFailureKind}).
 * Restores the ADR-0027 compile-time tag-type guarantee that the v1 collapsed-{@code String} shape
 * weakened. Pattern reference: {@link io.justsearch.indexerworker.services.IndexRuntimeTags}
 * (Phase 1 of tempdoc 417).
 */
public final class OrtSessionTags {

  private OrtSessionTags() {}

  static final String CONSUMER_KEY = "consumer";
  static final String OUTCOME_KEY = "outcome";
  static final String CAUSE_KEY = "cause";
  static final String KIND_KEY = "kind";

  static final Set<String> CONSUMER_KEYS = orderedSet(CONSUMER_KEY);
  static final Set<String> CONSUMER_OUTCOME_KEYS = orderedSet(CONSUMER_KEY, OUTCOME_KEY);
  static final Set<String> CONSUMER_CAUSE_KEYS = orderedSet(CONSUMER_KEY, CAUSE_KEY);
  static final Set<String> CONSUMER_KIND_KEYS = orderedSet(CONSUMER_KEY, KIND_KEY);

  /** Tag schema for metrics tagged only by encoder name. */
  public record ConsumerTags(String consumer) implements TagSchema {
    public ConsumerTags {
      Objects.requireNonNull(consumer, "consumer");
    }

    @Override
    public Set<String> allowedKeys() {
      return CONSUMER_KEYS;
    }

    @Override
    public Attributes toAttributes() {
      return Attributes.of(AttributeKey.stringKey(CONSUMER_KEY), consumer);
    }
  }

  /** Tag schema for metrics carrying a binary success/failure outcome. */
  public record ConsumerOutcomeTags(String consumer, Outcome outcome) implements TagSchema {
    public ConsumerOutcomeTags {
      Objects.requireNonNull(consumer, "consumer");
      Objects.requireNonNull(outcome, "outcome");
    }

    @Override
    public Set<String> allowedKeys() {
      return CONSUMER_OUTCOME_KEYS;
    }

    @Override
    public Attributes toAttributes() {
      return Attributes.of(
          AttributeKey.stringKey(CONSUMER_KEY), consumer,
          AttributeKey.stringKey(OUTCOME_KEY), outcome.wireValue());
    }
  }

  /**
   * Tag schema for {@code gpu_init_failure_total} — typed by {@link FailureCause}. Wrong cause
   * type fails to compile (ADR-0027 typed-tag-schema contract).
   */
  public record GpuInitFailureTags(String consumer, FailureCause cause) implements TagSchema {
    public GpuInitFailureTags {
      Objects.requireNonNull(consumer, "consumer");
      Objects.requireNonNull(cause, "cause");
    }

    @Override
    public Set<String> allowedKeys() {
      return CONSUMER_CAUSE_KEYS;
    }

    @Override
    public Attributes toAttributes() {
      return Attributes.of(
          AttributeKey.stringKey(CONSUMER_KEY), consumer,
          AttributeKey.stringKey(CAUSE_KEY), cause.wireValue());
    }
  }

  /**
   * Tag schema for {@code recovery_total} — typed by {@link CpuRecreateCause}. Distinct from
   * {@link GpuInitFailureTags} because the cause enums are semantically different — passing a
   * {@link FailureCause} where {@link CpuRecreateCause} is required fails at javac.
   */
  public record RecoveryTags(String consumer, CpuRecreateCause cause) implements TagSchema {
    public RecoveryTags {
      Objects.requireNonNull(consumer, "consumer");
      Objects.requireNonNull(cause, "cause");
    }

    @Override
    public Set<String> allowedKeys() {
      return CONSUMER_CAUSE_KEYS;
    }

    @Override
    public Attributes toAttributes() {
      return Attributes.of(
          AttributeKey.stringKey(CONSUMER_KEY), consumer,
          AttributeKey.stringKey(CAUSE_KEY), cause.wireValue());
    }
  }

  /** Tag schema for {@code assembler_failure_total} — typed by {@link AssemblerFailureKind}. */
  public record AssemblerFailureTags(String consumer, AssemblerFailureKind kind)
      implements TagSchema {
    public AssemblerFailureTags {
      Objects.requireNonNull(consumer, "consumer");
      Objects.requireNonNull(kind, "kind");
    }

    @Override
    public Set<String> allowedKeys() {
      return CONSUMER_KIND_KEYS;
    }

    @Override
    public Attributes toAttributes() {
      return Attributes.of(
          AttributeKey.stringKey(CONSUMER_KEY), consumer,
          AttributeKey.stringKey(KIND_KEY), kind.wireValue());
    }
  }

  private static Set<String> orderedSet(String... keys) {
    LinkedHashSet<String> set = new LinkedHashSet<>();
    for (String k : keys) set.add(k);
    return java.util.Collections.unmodifiableSet(set);
  }
}
