/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.embed;

import io.justsearch.indexerworker.embed.EmbeddingTelemetryEvents.InvokeFailureReason;
import io.justsearch.indexerworker.embed.EmbeddingTelemetryEvents.Operation;
import io.justsearch.indexerworker.embed.EmbeddingTelemetryEvents.UnloadReason;
import io.justsearch.telemetry.catalog.TagSchema;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Tag schemas for {@code embedding.runtime.*} metrics. Reuses the typed enums from
 * {@link EmbeddingTelemetryEvents} so the events-interface contract and the metric tag values
 * share their canonical taxonomy.
 */
public final class EmbeddingTags {

  private EmbeddingTags() {}

  static final String KEY_OPERATION = "operation";
  static final String KEY_REASON = "reason";

  static final Set<String> INVOKE_FAILURE_KEYS;
  static final Set<String> UNLOAD_KEYS = Set.of(KEY_REASON);

  static {
    Set<String> ifk = new LinkedHashSet<>();
    ifk.add(KEY_OPERATION);
    ifk.add(KEY_REASON);
    INVOKE_FAILURE_KEYS = ifk;
  }

  /** Tag schema for {@code embedding.runtime.invoke_failure_total}. */
  public record InvokeFailureTags(Operation operation, InvokeFailureReason reason)
      implements TagSchema {

    public InvokeFailureTags {
      Objects.requireNonNull(operation, "operation");
      Objects.requireNonNull(reason, "reason");
    }

    @Override
    public Set<String> allowedKeys() {
      return INVOKE_FAILURE_KEYS;
    }

    @Override
    public Attributes toAttributes() {
      return Attributes.builder()
          .put(AttributeKey.stringKey(KEY_OPERATION), operation.name())
          .put(AttributeKey.stringKey(KEY_REASON), reason.name())
          .build();
    }
  }

  /** Tag schema for {@code embedding.runtime.unload_total}. */
  public record UnloadTags(UnloadReason reason) implements TagSchema {

    public UnloadTags {
      Objects.requireNonNull(reason, "reason");
    }

    @Override
    public Set<String> allowedKeys() {
      return UNLOAD_KEYS;
    }

    @Override
    public Attributes toAttributes() {
      return Attributes.of(AttributeKey.stringKey(KEY_REASON), reason.name());
    }
  }
}
