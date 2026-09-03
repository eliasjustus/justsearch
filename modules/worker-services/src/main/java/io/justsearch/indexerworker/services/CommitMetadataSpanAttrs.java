/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import io.opentelemetry.api.trace.Span;
import java.util.List;
import java.util.Map;

/**
 * Attaches {@code commit.*} identity attributes to spans (tempdoc 400 LR2-d.2).
 *
 * <p>Replaces the retired {@code pipeline_hash}/{@code budget_profile} slot (tempdoc 400 LR2-d.1)
 * with the commit-metadata hashes that survived ADR 0014 and govern runtime index identity. Tempdoc 915 folded {@code analyzer_fp} and
 * {@code index_schema_fp} into the single {@code index_fingerprint}, so the list is 7 keys.
 * Sourced from the Worker's {@code ingestLifecycle.latestCommitUserDataBestEffort()}.
 *
 * <p>The keys are allowlisted in {@code NdjsonSpanExporter.ALLOWED_ATTRS} as {@code commit.<name>};
 * the source map keys are the unprefixed SSOT commit-metadata key names.
 */
public final class CommitMetadataSpanAttrs {
  private static final List<String> KEYS = List.of(
      "schema_fp",
      "field_catalog_hash",
      "synonyms_hash",
      "grammar_hash",
      "similarity_fp",
      "boosts_fp",
      "index_fingerprint");

  private CommitMetadataSpanAttrs() {}

  /** No-op when either argument is null/empty; best-effort. */
  public static void applyTo(Span span, Map<String, String> commitMetadata) {
    if (span == null || commitMetadata == null || commitMetadata.isEmpty()) {
      return;
    }
    for (String key : KEYS) {
      String value = commitMetadata.get(key);
      if (value != null && !value.isBlank()) {
        span.setAttribute("commit." + key, value);
      }
    }
  }
}
