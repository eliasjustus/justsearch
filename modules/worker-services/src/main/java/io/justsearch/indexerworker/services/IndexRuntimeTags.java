/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import io.justsearch.adapters.lucene.runtime.CommitReason;
import io.justsearch.adapters.lucene.runtime.SwapReason;
import io.justsearch.adapters.lucene.runtime.ValidationReason;
import io.justsearch.telemetry.catalog.TagSchema;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Typed tag schemas for {@code index.runtime.*} metrics. Each schema wraps one of the bounded
 * sealed-style enums in {@code modules/adapters-lucene} and projects to OTel
 * {@link Attributes} for the catalog to forward to the SDK.
 *
 * <p>Tempdoc 417 Phase 1.
 */
public final class IndexRuntimeTags {

  private IndexRuntimeTags() {}

  static final String REASON_KEY = "reason";

  /** Single-key set used for all three reason-tagged metrics in this catalog. */
  static final Set<String> REASON_KEYS;

  static {
    Set<String> ks = new LinkedHashSet<>();
    ks.add(REASON_KEY);
    REASON_KEYS = ks;
  }

  /** Tag schema for commit metrics. */
  public record CommitTags(CommitReason reason) implements TagSchema {
    public CommitTags {
      Objects.requireNonNull(reason, "reason");
    }

    public static CommitTags of(CommitReason reason) {
      return new CommitTags(reason);
    }

    @Override
    public Set<String> allowedKeys() {
      return REASON_KEYS;
    }

    @Override
    public Attributes toAttributes() {
      return Attributes.of(AttributeKey.stringKey(REASON_KEY), reason.wireValue());
    }
  }

  /** Tag schema for swap-lifecycle metrics. */
  public record SwapTags(SwapReason reason) implements TagSchema {
    public SwapTags {
      Objects.requireNonNull(reason, "reason");
    }

    public static SwapTags of(SwapReason reason) {
      return new SwapTags(reason);
    }

    @Override
    public Set<String> allowedKeys() {
      return REASON_KEYS;
    }

    @Override
    public Attributes toAttributes() {
      return Attributes.of(AttributeKey.stringKey(REASON_KEY), reason.wireValue());
    }
  }

  /** Tag schema for validation-failure metrics. */
  public record ValidationTags(ValidationReason reason) implements TagSchema {
    public ValidationTags {
      Objects.requireNonNull(reason, "reason");
    }

    public static ValidationTags of(ValidationReason reason) {
      return new ValidationTags(reason);
    }

    @Override
    public Set<String> allowedKeys() {
      return REASON_KEYS;
    }

    @Override
    public Attributes toAttributes() {
      return Attributes.of(AttributeKey.stringKey(REASON_KEY), reason.wireValue());
    }
  }
}
