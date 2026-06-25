/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.ingest;

import java.util.Objects;

/**
 * Durable typed result emitted when an untrusted local file crosses the Worker ingestion boundary.
 */
public record IngestionOutcome(
    IngestionOutcomeClass outcomeClass,
    String reasonCode,
    IngestionRetryPolicy retryPolicy,
    String diagnosticSummary,
    long observedAtMs) {
  private static final int MAX_DIAGNOSTIC_SUMMARY_CHARS = 512;

  public IngestionOutcome(
      IngestionOutcomeClass outcomeClass,
      String reasonCode,
      IngestionRetryPolicy retryPolicy,
      String diagnosticSummary,
      long observedAtMs) {
    this.outcomeClass = Objects.requireNonNull(outcomeClass, "outcomeClass");
    this.reasonCode =
        reasonCode == null || reasonCode.isBlank() ? outcomeClass.name() : reasonCode.trim();
    this.retryPolicy = retryPolicy == null ? IngestionRetryPolicy.NONE : retryPolicy;
    this.diagnosticSummary = sanitizeDiagnosticSummary(diagnosticSummary);
    this.observedAtMs = observedAtMs > 0 ? observedAtMs : System.currentTimeMillis();
  }

  public static IngestionOutcome of(
      IngestionOutcomeClass outcomeClass, String reasonCode, IngestionRetryPolicy retryPolicy) {
    return new IngestionOutcome(outcomeClass, reasonCode, retryPolicy, null, 0L);
  }

  public static IngestionOutcome of(
      IngestionOutcomeClass outcomeClass,
      String reasonCode,
      IngestionRetryPolicy retryPolicy,
      String diagnosticSummary) {
    return new IngestionOutcome(outcomeClass, reasonCode, retryPolicy, diagnosticSummary, 0L);
  }

  private static String sanitizeDiagnosticSummary(String diagnosticSummary) {
    if (diagnosticSummary == null || diagnosticSummary.isBlank()) {
      return null;
    }
    String singleLine = diagnosticSummary.replace('\r', ' ').replace('\n', ' ').trim();
    if (singleLine.length() <= MAX_DIAGNOSTIC_SUMMARY_CHARS) {
      return singleLine;
    }
    return singleLine.substring(0, MAX_DIAGNOSTIC_SUMMARY_CHARS);
  }
}
