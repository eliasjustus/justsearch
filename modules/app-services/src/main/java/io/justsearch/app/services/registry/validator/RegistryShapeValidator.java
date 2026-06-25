/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.registry.validator;

import io.justsearch.agent.api.registry.Severity;
import java.util.stream.Stream;

/**
 * Build-time validator over a registered Operation catalog.
 *
 * <p>Per tempdoc 429 §A.7 (revision 5 trim): six concrete validators run via a
 * parameterized JUnit harness with explicit {@code Stream.of(...)} registration
 * (NOT ServiceLoader — these are test-local per §B.B). Each validator is small
 * (~30-80 LOC) and covers one or two related rules.
 *
 * <p>Inspired by but not extending {@code MetricSurfaceContractTest} per §B.I —
 * different scope (10 → 6 validators), different shape (severity-tagged findings).
 */
public interface RegistryShapeValidator {

  /** Stable validator name (used in test output + finding records). */
  String name();

  /** Default severity for findings emitted by this validator. */
  Severity severity();

  /** Run the validator against the provided context. */
  Stream<ValidationFinding> validate(ValidationContext context);
}
