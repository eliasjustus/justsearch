/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.registry.proposal;

import io.justsearch.agent.api.registry.Resource;
import io.justsearch.agent.api.registry.Severity;
import java.util.Map;

/** {@link ShapeProposal} for the {@link Resource} primitive (per tempdoc 429 §A.6). */
public final class ResourceShapeProposalProvider {

  private ResourceShapeProposalProvider() {}

  public static final ShapeProposal PROPOSAL =
      new ShapeProposal(
          Resource.class,
          Map.of(
              ShapeProposal.StatusReason.PRIMITIVE_NOT_SERVED,
              new ShapeProposal.FailureRendering(
                  "errors.resource.primitive-not-served", Severity.WARNING),
              ShapeProposal.StatusReason.ENTRIES_MALFORMED,
              new ShapeProposal.FailureRendering(
                  "errors.resource.entries-malformed", Severity.ERROR),
              ShapeProposal.StatusReason.SOURCE_DISCONNECTED,
              new ShapeProposal.FailureRendering(
                  "errors.resource.source-disconnected", Severity.WARNING),
              ShapeProposal.StatusReason.BACKEND_UNREACHABLE,
              new ShapeProposal.FailureRendering(
                  "errors.resource.backend-unreachable", Severity.WARNING),
              ShapeProposal.StatusReason.FRONTEND_OUT_OF_DATE,
              new ShapeProposal.FailureRendering(
                  "errors.resource.frontend-out-of-date", Severity.INFO)),
          ShapeProposal.EscapeHatchPolicy.RENDER_HINTS_ONLY,
          ShapeProposal.DataFreshness.SseStream.INSTANCE,
          new ShapeProposal.PluginIntegration(false, true, true),
          "app-services");
}
