/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.registry.validator;

import io.justsearch.agent.api.registry.Severity;
import java.util.Objects;

/**
 * Single validator finding emitted by a {@link RegistryShapeValidator}.
 *
 * <p>Per tempdoc 429 §A.7: validators emit structured findings tagged with severity;
 * the runner test asserts no ERROR findings against the seed catalog. WARN findings
 * print to test output but don't fail the build.
 */
public record ValidationFinding(
    String operationId, String validatorName, Severity severity, String message) {

  public ValidationFinding {
    Objects.requireNonNull(operationId, "operationId");
    Objects.requireNonNull(validatorName, "validatorName");
    Objects.requireNonNull(severity, "severity");
    Objects.requireNonNull(message, "message");
  }
}
