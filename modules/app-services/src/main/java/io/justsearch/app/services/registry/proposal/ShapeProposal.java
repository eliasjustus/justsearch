/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.registry.proposal;

import io.justsearch.agent.api.registry.RegistryEntry;
import io.justsearch.agent.api.registry.Severity;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/**
 * Build-time meta-spec for a registered registry primitive.
 *
 * <p>Per tempdoc 429 §A.6 + §C.D (revision-3 trim): 6 fields capturing the load-bearing
 * structural checks the build can usefully validate. The full ten-question prose
 * meta-spec lives in 421-data-plane.md §"Registry primitive design checklist"; this
 * record captures only the build-validatable subset.
 *
 * <p>Each registered primitive (Operation, Resource, Prompt) ships a ShapeProposal
 * instance via {@code ShapeProposalProvider}. The {@link ShapeProposalValidator} harness
 * verifies them at test time.
 */
public record ShapeProposal(
    Class<? extends RegistryEntry> entryType,
    Map<StatusReason, FailureRendering> failureModes,
    EscapeHatchPolicy escapeHatch,
    DataFreshness dataFreshness,
    PluginIntegration pluginIntegration,
    String owningModule) {

  public ShapeProposal {
    Objects.requireNonNull(entryType, "entryType");
    failureModes = failureModes == null ? Map.of() : Map.copyOf(failureModes);
    Objects.requireNonNull(escapeHatch, "escapeHatch");
    Objects.requireNonNull(dataFreshness, "dataFreshness");
    Objects.requireNonNull(pluginIntegration, "pluginIntegration");
    Objects.requireNonNull(owningModule, "owningModule");
    if (owningModule.isBlank()) {
      throw new IllegalArgumentException("owningModule must be non-blank");
    }
  }

  public enum StatusReason {
    PRIMITIVE_NOT_SERVED,
    ENTRIES_MALFORMED,
    SOURCE_DISCONNECTED,
    BACKEND_UNREACHABLE,
    FRONTEND_OUT_OF_DATE
  }

  public record FailureRendering(String placeholderMessageKey, Severity severity) {
    public FailureRendering {
      Objects.requireNonNull(placeholderMessageKey, "placeholderMessageKey");
      Objects.requireNonNull(severity, "severity");
    }
  }

  public enum EscapeHatchPolicy {
    NONE,
    RENDER_HINTS_ONLY,
    CUSTOM_RENDERER_VIA_EXECUTOR
  }

  /** Data-freshness model for entries within this primitive. */
  public sealed interface DataFreshness {
    record OneShot() implements DataFreshness {
      public static final OneShot INSTANCE = new OneShot();
    }

    record SseStream() implements DataFreshness {
      public static final SseStream INSTANCE = new SseStream();
    }

    record Polling(Duration interval) implements DataFreshness {
      public Polling {
        Objects.requireNonNull(interval, "interval");
      }
    }
  }

  public record PluginIntegration(
      boolean entriesContributable, boolean renderersContributable, boolean consumable) {}
}
