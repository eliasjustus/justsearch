/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.api.registry;

import java.util.List;
import java.util.Objects;

/**
 * Outcome of catalog-level intent resolution (tempdoc 499 §4.1).
 *
 * <p>Three variants model the complete resolution lifecycle:
 * <ul>
 *   <li>{@link Resolved} — exact catalog match.
 *   <li>{@link Redirected} — alias/rename hit; dispatch proceeds with the canonical entry.
 *   <li>{@link Unresolved} — no match; carries diagnosis and ranked alternatives.
 * </ul>
 */
public sealed interface ResolutionResult<T> {

  record Resolved<T>(T entry) implements ResolutionResult<T> {
    public Resolved {
      Objects.requireNonNull(entry, "entry");
    }
  }

  record Redirected<T>(T entry, String originalId, RedirectReason reason)
      implements ResolutionResult<T> {
    public Redirected {
      Objects.requireNonNull(entry, "entry");
      Objects.requireNonNull(originalId, "originalId");
      Objects.requireNonNull(reason, "reason");
    }
  }

  record Unresolved<T>(String attemptedId, UnresolvedDiagnosis diagnosis,
                        List<Suggestion<T>> alternatives) implements ResolutionResult<T> {
    public Unresolved {
      Objects.requireNonNull(attemptedId, "attemptedId");
      Objects.requireNonNull(diagnosis, "diagnosis");
      alternatives = alternatives == null ? List.of() : List.copyOf(alternatives);
    }
  }

  enum RedirectReason { ALIAS, RENAMED }

  record UnresolvedDiagnosis(FailureMode mode, String detail) {
    public UnresolvedDiagnosis {
      Objects.requireNonNull(mode, "mode");
      Objects.requireNonNull(detail, "detail");
    }
  }

  enum FailureMode {
    TYPO,
    STALE_CATALOG,
    RENAMED_REMOVED,
    UNKNOWN
  }

  record Suggestion<T>(T entry, String refId, double confidence, String rationale) {
    public Suggestion {
      Objects.requireNonNull(entry, "entry");
      Objects.requireNonNull(refId, "refId");
      Objects.requireNonNull(rationale, "rationale");
    }
  }
}
