/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import io.justsearch.telemetry.catalog.TagSchema;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import java.util.Set;

/**
 * Tag schema for {@code worker.job_queue.outcome.total} (tempdoc 885 item 21e).
 *
 * <p>The single tag is the {@code IngestionOutcomeClass} name — a closed vocabulary of 14 values
 * plus {@code UNKNOWN} for an untyped failure, which is why the metric declares a tight cardinality
 * limit. Nothing path-derived or exception-derived is admitted as a tag: that is the cardinality bug
 * {@code VduOutcomeTags} already records having removed once.
 */
public record QueueOutcomeTags(String outcomeClass) implements TagSchema {

  static final String KEY_OUTCOME_CLASS = "outcome_class";

  /** Tag value for an untyped failure — the legacy {@code markFailed(Path, String)} path. */
  public static final String UNKNOWN = "UNKNOWN";
  private static final Set<String> KEYS = Set.of(KEY_OUTCOME_CLASS);
  private static final AttributeKey<String> ATTR = AttributeKey.stringKey(KEY_OUTCOME_CLASS);

  public QueueOutcomeTags {
    outcomeClass = outcomeClass == null || outcomeClass.isBlank() ? UNKNOWN : outcomeClass;
  }

  @Override
  public Set<String> allowedKeys() {
    return KEYS;
  }

  @Override
  public Attributes toAttributes() {
    return Attributes.builder().put(ATTR, outcomeClass).build();
  }
}
