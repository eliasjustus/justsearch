/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services.plan;

import io.justsearch.indexerworker.services.SearchReasonCode;
import java.util.Map;
import java.util.Objects;

/**
 * Planner's chunk-merge directive (tempdoc 517).
 *
 * <p>The planner pre-commits 10 of the 11 chunk-merge reason codes. The 11th —
 * {@link SearchReasonCode#SKIPPED_EMPTY_BASE_RESULTS} — is necessarily a
 * runtime decision: {@code EligibleApply} means "no pre-commit reason to skip;
 * the executor checks {@code hasBaseResults} at the chunk-merge call site."
 */
public sealed interface ChunkMergeDirective permits ChunkMergeDirective.Skip, ChunkMergeDirective.EligibleApply {

  /** Pre-committed skip; carries the reason. */
  record Skip(SearchReasonCode reason) implements ChunkMergeDirective {
    public Skip {
      Objects.requireNonNull(reason, "reason");
    }
  }

  /**
   * The planner sees no pre-commit reason to skip; the executor evaluates
   * {@code hasBaseResults} at runtime and either records
   * {@link SearchReasonCode#APPLIED} (on application) or
   * {@link SearchReasonCode#SKIPPED_EMPTY_BASE_RESULTS} (on empty base
   * retrieval).
   */
  record EligibleApply(ChunkMergeInputs inputs) implements ChunkMergeDirective {
    public EligibleApply {
      Objects.requireNonNull(inputs, "inputs");
    }
  }

  /** Default summary key for {@link SearchDecision#summary()}. */
  default String kind() {
    return this instanceof Skip ? "skip" : "eligible_apply";
  }
}
