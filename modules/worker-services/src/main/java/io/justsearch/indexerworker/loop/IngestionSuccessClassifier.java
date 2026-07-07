/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop;

/**
 * Pure, total mapping from a ledger transition's {@code artifactStatus} string (an
 * {@code ExtractionStatus.name()} value) to the success bucket
 * {@link IngestionOutcomeJournal#drainPending()} should route it to (tempdoc 671, Long-term
 * design part 2). Extracted from the inline {@code isPartialSuccessTransition} boolean check this
 * replaces, which — like {@code OcrSkipReason.TEXTUAL} before it — answered only "is this
 * partial," collapsing every other outcome (including a genuinely empty one) into "full."
 *
 * <p>The LAW: total over any {@code artifactStatus} string, mirroring
 * {@code io.justsearch.indexerworker.loop.AdmissionPolicy}'s shape in the same package family —
 * {@code "SUCCESS_PARTIAL"} maps to {@link Bucket#PARTIAL}, {@code "SUCCESS_EMPTY"} maps to
 * {@link Bucket#EMPTY}, every other value (including {@code null}, legacy rows, and
 * {@code "SUCCESS_FULL"} itself) maps to {@link Bucket#FULL}. Kept string-based, not
 * {@code ExtractionStatus}-typed, to match the existing ledger-persistence convention — a value
 * read back after a restart/replay must not throw on an unrecognized string.
 */
final class IngestionSuccessClassifier {

  enum Bucket {
    FULL,
    PARTIAL,
    EMPTY
  }

  private IngestionSuccessClassifier() {}

  static Bucket classify(String artifactStatus) {
    if ("SUCCESS_PARTIAL".equals(artifactStatus)) {
      return Bucket.PARTIAL;
    }
    if ("SUCCESS_EMPTY".equals(artifactStatus)) {
      return Bucket.EMPTY;
    }
    return Bucket.FULL;
  }
}
