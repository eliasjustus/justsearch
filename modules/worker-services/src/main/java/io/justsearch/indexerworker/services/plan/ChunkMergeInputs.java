/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services.plan;

import io.justsearch.indexerworker.services.input.SpladeEncoding;
import io.justsearch.indexerworker.services.input.VectorEncoding;
import java.util.List;

/**
 * Captured inputs for the chunk-merge operator (tempdoc 517).
 *
 * <p>Threaded from the {@link ChunkMergeDirective.EligibleApply} directive into
 * the executor's chunk-merge step. The query text used here is the
 * <i>post-correction</i> text — encoded by the {@code QueryAfterCorrection}
 * input type into the {@code ChunkMergeOp} signature so the ordering
 * Correction → ChunkMerge is enforced by types, not by comment.
 *
 * <p>Vector + splade fields are nullable because BM25-only chunk merge does
 * not need them; the planner sets only the fields the executed leg set
 * actually produced.
 */
public record ChunkMergeInputs(
    int limit, List<Float> chunkQueryVector, java.util.Map<String, Float> chunkSpladeWeights) {
  public ChunkMergeInputs {
    // No null-checks on the optional payloads — null is the in-band "absent" signal.
  }
}
