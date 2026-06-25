/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.api.registry;

import java.util.List;
import java.util.Objects;

/**
 * Policy-driven action to take after intent resolution (tempdoc 499 §3.2).
 *
 * <p>Produced by {@link ResolutionRecoveryPolicy#decide} from a {@link ResolutionResult}.
 * The consumer pattern-matches on the variant and executes the corresponding behavior.
 */
public sealed interface RecoveryAction<T> {

  record Proceed<T>(T entry) implements RecoveryAction<T> {
    public Proceed {
      Objects.requireNonNull(entry, "entry");
    }
  }

  record AutoCorrect<T>(T entry, String originalId, String correctedId)
      implements RecoveryAction<T> {
    public AutoCorrect {
      Objects.requireNonNull(entry, "entry");
      Objects.requireNonNull(originalId, "originalId");
      Objects.requireNonNull(correctedId, "correctedId");
    }
  }

  record SuggestToUser<T>(String attemptedId, List<ResolutionResult.Suggestion<T>> alternatives)
      implements RecoveryAction<T> {
    public SuggestToUser {
      Objects.requireNonNull(attemptedId, "attemptedId");
      alternatives = alternatives == null ? List.of() : List.copyOf(alternatives);
    }
  }

  record InjectHint<T>(String attemptedId, String hintMessage,
                        List<ResolutionResult.Suggestion<T>> alternatives)
      implements RecoveryAction<T> {
    public InjectHint {
      Objects.requireNonNull(attemptedId, "attemptedId");
      Objects.requireNonNull(hintMessage, "hintMessage");
      alternatives = alternatives == null ? List.of() : List.copyOf(alternatives);
    }
  }

  record Abort<T>(String attemptedId, String reason) implements RecoveryAction<T> {
    public Abort {
      Objects.requireNonNull(attemptedId, "attemptedId");
      Objects.requireNonNull(reason, "reason");
    }
  }
}
