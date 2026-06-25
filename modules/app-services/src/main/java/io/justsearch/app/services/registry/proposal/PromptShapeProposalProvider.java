/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.registry.proposal;

import io.justsearch.agent.api.registry.Prompt;
import io.justsearch.agent.api.registry.Severity;
import java.util.Map;

/** {@link ShapeProposal} for the {@link Prompt} primitive (per tempdoc 429 §A.6). */
public final class PromptShapeProposalProvider {

  private PromptShapeProposalProvider() {}

  public static final ShapeProposal PROPOSAL =
      new ShapeProposal(
          Prompt.class,
          Map.of(
              ShapeProposal.StatusReason.PRIMITIVE_NOT_SERVED,
              new ShapeProposal.FailureRendering(
                  "errors.prompt.primitive-not-served", Severity.WARNING),
              ShapeProposal.StatusReason.ENTRIES_MALFORMED,
              new ShapeProposal.FailureRendering(
                  "errors.prompt.entries-malformed", Severity.ERROR),
              ShapeProposal.StatusReason.SOURCE_DISCONNECTED,
              new ShapeProposal.FailureRendering(
                  "errors.prompt.source-disconnected", Severity.INFO),
              ShapeProposal.StatusReason.BACKEND_UNREACHABLE,
              new ShapeProposal.FailureRendering(
                  "errors.prompt.backend-unreachable", Severity.WARNING),
              ShapeProposal.StatusReason.FRONTEND_OUT_OF_DATE,
              new ShapeProposal.FailureRendering(
                  "errors.prompt.frontend-out-of-date", Severity.INFO)),
          ShapeProposal.EscapeHatchPolicy.RENDER_HINTS_ONLY,
          ShapeProposal.DataFreshness.OneShot.INSTANCE,
          new ShapeProposal.PluginIntegration(true, false, true),
          "app-services");
}
