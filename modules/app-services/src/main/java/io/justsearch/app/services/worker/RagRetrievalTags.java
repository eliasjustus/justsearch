/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

import io.justsearch.telemetry.catalog.TagSchema;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Tag schema for {@code rag.retrieval_total}. Carries the pre-refactor {@code component} tag
 * (constant {@code "rag_retrieval"}) alongside the mode for wire-format byte-stability.
 *
 * <p>Tempdoc 417 F2 restored the {@code component} tag dropped during initial Phase 2e — the
 * plan defaulted to byte-stable wire format and only the unbounded {@code reason} tag was
 * authorized for removal.
 */
public record RagRetrievalTags(String component, RagRetrievalMode mode) implements TagSchema {

  static final String KEY_COMPONENT = "component";
  static final String KEY_MODE = "mode";
  static final Set<String> KEYS;

  static {
    Set<String> ks = new LinkedHashSet<>();
    ks.add(KEY_COMPONENT);
    ks.add(KEY_MODE);
    KEYS = ks;
  }

  public RagRetrievalTags {
    Objects.requireNonNull(component, "component");
    Objects.requireNonNull(mode, "mode");
  }

  /** Convenience: construct with the canonical {@code component="rag_retrieval"}. */
  public static RagRetrievalTags of(RagRetrievalMode mode) {
    return new RagRetrievalTags("rag_retrieval", mode);
  }

  @Override
  public Set<String> allowedKeys() {
    return KEYS;
  }

  @Override
  public Attributes toAttributes() {
    return Attributes.builder()
        .put(AttributeKey.stringKey(KEY_COMPONENT), component)
        .put(AttributeKey.stringKey(KEY_MODE), mode.wireValue())
        .build();
  }
}
