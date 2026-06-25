/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.registry.proposal;

import io.justsearch.agent.api.registry.Severity;
import io.justsearch.app.services.registry.validator.ValidationFinding;
import java.util.EnumSet;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Build-time harness for {@link ShapeProposal} consistency checks.
 *
 * <p>Per tempdoc 429 §A.6 (revision-3 trim): validates each registered ShapeProposal
 * against the actual emission sites — every primitive ships with a populated proposal,
 * and the harness errors on missing failure modes, blank module names, etc.
 */
public final class ShapeProposalValidator {

  private ShapeProposalValidator() {}

  public static List<ValidationFinding> validate(ShapeProposal proposal) {
    return validateStream(proposal).toList();
  }

  public static Stream<ValidationFinding> validateStream(ShapeProposal proposal) {
    String name = proposal.entryType().getSimpleName();
    Stream.Builder<ValidationFinding> builder = Stream.builder();

    EnumSet<ShapeProposal.StatusReason> covered = EnumSet.noneOf(ShapeProposal.StatusReason.class);
    proposal.failureModes().keySet().forEach(covered::add);
    EnumSet<ShapeProposal.StatusReason> missing = EnumSet.allOf(ShapeProposal.StatusReason.class);
    missing.removeAll(covered);
    if (!missing.isEmpty()) {
      builder.add(
          new ValidationFinding(
              name,
              "ShapeProposalValidator",
              Severity.ERROR,
              "ShapeProposal.failureModes is missing entries for: "
                  + missing.stream().map(Enum::name).collect(Collectors.joining(", "))));
    }

    if (proposal.dataFreshness() instanceof ShapeProposal.DataFreshness.Polling p) {
      if (p.interval().toMillis() < 100L) {
        builder.add(
            new ValidationFinding(
                name,
                "ShapeProposalValidator",
                Severity.ERROR,
                "Polling interval " + p.interval().toMillis() + "ms is sub-100ms; rarely meaningful"));
      }
    }

    if (!proposal.owningModule().matches("^app-[a-z0-9-]+$")) {
      builder.add(
          new ValidationFinding(
              name,
              "ShapeProposalValidator",
              Severity.ERROR,
              "owningModule '" + proposal.owningModule()
                  + "' must match ^app-[a-z0-9-]+$"));
    }

    return builder.build();
  }

  /** Convenience: throws AssertionError if any ERROR-severity findings surface. */
  public static void assertNoErrors(ShapeProposal proposal) {
    List<ValidationFinding> errors =
        validate(proposal).stream()
            .filter(f -> f.severity() == Severity.ERROR)
            .toList();
    if (!errors.isEmpty()) {
      String summary =
          errors.stream()
              .map(f -> "  - " + f.message())
              .collect(Collectors.joining("\n"));
      throw new AssertionError(
          "ShapeProposal for "
              + proposal.entryType().getSimpleName()
              + " has ERROR findings:\n"
              + summary);
    }
  }
}
