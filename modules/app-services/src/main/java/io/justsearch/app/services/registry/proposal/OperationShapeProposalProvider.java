/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.registry.proposal;

import io.justsearch.agent.api.registry.Operation;
import io.justsearch.agent.api.registry.Severity;
import java.util.Map;

/**
 * {@link ShapeProposal} for the {@link Operation} primitive (per tempdoc 429 §"Acceptance
 * criteria" + §A.6).
 *
 * <p>Validated at test time by
 * {@link io.justsearch.app.services.registry.proposal.ShapeProposalRunnerTest} via
 * {@link ShapeProposalValidator#assertNoErrors(ShapeProposal)}.
 */
public final class OperationShapeProposalProvider {

  private OperationShapeProposalProvider() {}

  public static final ShapeProposal PROPOSAL =
      new ShapeProposal(
          Operation.class,
          Map.of(
              ShapeProposal.StatusReason.PRIMITIVE_NOT_SERVED,
              new ShapeProposal.FailureRendering(
                  "errors.operation.primitive-not-served", Severity.WARNING),
              ShapeProposal.StatusReason.ENTRIES_MALFORMED,
              new ShapeProposal.FailureRendering(
                  "errors.operation.entries-malformed", Severity.ERROR),
              ShapeProposal.StatusReason.SOURCE_DISCONNECTED,
              new ShapeProposal.FailureRendering(
                  "errors.operation.source-disconnected", Severity.WARNING),
              ShapeProposal.StatusReason.BACKEND_UNREACHABLE,
              new ShapeProposal.FailureRendering(
                  "errors.operation.backend-unreachable", Severity.WARNING),
              ShapeProposal.StatusReason.FRONTEND_OUT_OF_DATE,
              new ShapeProposal.FailureRendering(
                  "errors.operation.frontend-out-of-date", Severity.INFO)),
          ShapeProposal.EscapeHatchPolicy.CUSTOM_RENDERER_VIA_EXECUTOR,
          ShapeProposal.DataFreshness.OneShot.INSTANCE,
          new ShapeProposal.PluginIntegration(true, true, true),
          "app-services");
}
