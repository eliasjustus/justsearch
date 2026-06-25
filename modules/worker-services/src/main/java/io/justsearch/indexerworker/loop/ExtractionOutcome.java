/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop;

/**
 * Tagged return value from a single extraction attempt.
 *
 * <p>Tempdoc 516 Slice 3 substrate (Appendix A.1). Replaces the implicit "ExtractedJob | null"
 * contract today's {@code IndexingLoop.extractJob} carries (where {@code null} means "outcome
 * already recorded via side effect"). The sealed shape lets the upcoming Slice 4a
 * {@code JobBatchExtractor} return outcomes the {@code runLoop} residue dispatches without
 * field mutation across the seam.
 *
 * <p>Cases:
 * <ul>
 *   <li>{@link Extracted} — pre-checks + Tika extraction succeeded; the {@link ExtractedJob}
 *       carries the validated artifact for the writer to index.
 *   <li>{@link Skipped} — admission rejected the job (e.g., non-regular source, temp/system
 *       file). The ledger has already been marked done; nothing else to do.
 *   <li>{@link Failed} — extraction itself failed (parser, sandbox, budget). The
 *       {@code terminal} flag mirrors {@code IngestionRetryPolicy.NONE} semantics.
 *   <li>{@link StaleDeleted} — source disappeared or changed between admission and validation;
 *       any prior index entry was deleted, the ledger marked done with a stale reason code.
 * </ul>
 *
 * <p>P5 note (Appendix A.1 + the body's discipline boundary): this is a sealed sum type with
 * a closed set of named cases, not a strategy interface with implementations. New extraction
 * shapes require an explicit case + every consumer's pattern match, not a plug-in.
 *
 * <p><b>C-018 status (substrate-awaiting-consumer):</b> shipped in tempdoc 516 Slice 3 in
 * anticipation of Slice 4a.2 / 4a.3 (Extract + Write extraction), which were deferred from
 * the autonomous overnight run due to a Tier-3 verification blocker (dev-runner held by
 * another agent session). Today this type has zero callers; the consumer slice is named in
 * tempdoc 516 Appendix B's "What the next implementing agent should do" section. If that
 * slice does not land within a reasonable horizon, this type and {@link WriteOutcome} should
 * be deleted rather than left as orphan substrate — that's the standing C-018 discipline.
 */
public sealed interface ExtractionOutcome
    permits ExtractionOutcome.Extracted,
        ExtractionOutcome.Skipped,
        ExtractionOutcome.Failed,
        ExtractionOutcome.StaleDeleted {

  record Extracted(ExtractedJob job) implements ExtractionOutcome {}

  record Skipped(String reasonCode) implements ExtractionOutcome {}

  record Failed(String reasonCode, boolean terminal) implements ExtractionOutcome {}

  record StaleDeleted(String reasonCode) implements ExtractionOutcome {}
}
