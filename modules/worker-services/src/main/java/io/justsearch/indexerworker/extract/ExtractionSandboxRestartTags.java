/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.extract;

import io.justsearch.telemetry.catalog.TagSchema;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import java.util.Objects;
import java.util.Set;

/**
 * Tag schema for {@code extraction.sandbox_restart_total} (tempdoc 885 item 14). The
 * {@code reason} dimension is what makes the counter actionable: a steady stream of
 * {@code request_budget} recycles is the leak guard working, while {@code timeout} or {@code oom}
 * is a corpus problem.
 */
public record ExtractionSandboxRestartTags(String reason) implements TagSchema {

  static final String KEY = "reason";
  static final Set<String> KEYS = Set.of(KEY);

  public ExtractionSandboxRestartTags {
    Objects.requireNonNull(reason, "reason");
  }

  public static ExtractionSandboxRestartTags of(String reason) {
    return new ExtractionSandboxRestartTags(reason);
  }

  @Override
  public Set<String> allowedKeys() {
    return KEYS;
  }

  @Override
  public Attributes toAttributes() {
    return Attributes.of(AttributeKey.stringKey(KEY), reason);
  }
}
