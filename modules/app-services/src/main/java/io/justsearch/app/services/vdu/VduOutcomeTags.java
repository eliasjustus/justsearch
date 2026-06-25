/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.vdu;

import io.justsearch.telemetry.catalog.TagSchema;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Tag schema for {@code vdu.outcome_total}. Carries the pre-refactor {@code component} tag
 * (constant {@code "vdu_batch"}) alongside outcome for wire-format byte-stability.
 *
 * <p>Drops the pre-refactor unbounded {@code reason} exception-message tag (a cardinality bug)
 * — exception details continue to be logged via slf4j at the same callsite.
 *
 * <p>Tempdoc 417 F2 restored the {@code component} tag dropped during initial Phase 2e.
 */
public record VduOutcomeTags(String component, VduOutcome outcome) implements TagSchema {

  static final String KEY_COMPONENT = "component";
  static final String KEY_OUTCOME = "outcome";
  static final Set<String> KEYS;

  static {
    Set<String> ks = new LinkedHashSet<>();
    ks.add(KEY_COMPONENT);
    ks.add(KEY_OUTCOME);
    KEYS = ks;
  }

  public VduOutcomeTags {
    Objects.requireNonNull(component, "component");
    Objects.requireNonNull(outcome, "outcome");
  }

  /** Convenience: construct with the canonical {@code component="vdu_batch"}. */
  public static VduOutcomeTags of(VduOutcome outcome) {
    return new VduOutcomeTags("vdu_batch", outcome);
  }

  @Override
  public Set<String> allowedKeys() {
    return KEYS;
  }

  @Override
  public Attributes toAttributes() {
    return Attributes.builder()
        .put(AttributeKey.stringKey(KEY_COMPONENT), component)
        .put(AttributeKey.stringKey(KEY_OUTCOME), outcome.wireValue())
        .build();
  }
}
