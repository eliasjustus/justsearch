/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.conversation;

import io.justsearch.agent.api.conversation.ContextInjector;
import io.justsearch.agent.api.conversation.IterationController;
import io.justsearch.agent.api.conversation.PromptContributor;
import io.justsearch.agent.api.conversation.StreamConsumer;
import io.justsearch.agent.api.registry.ConversationShape;
import io.justsearch.agent.api.registry.TrustTier;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Slice 491 §9.D Phase E (G4) — tier-compatibility validator for the PLUGIN-tier path.
 *
 * <p>Per §9.D E4 + §10 Q4 resolution: plugin-contributed shapes (Provenance.tier =
 * TRUSTED_PLUGIN or UNTRUSTED_PLUGIN) compose SPI implementations by id. Each SPI
 * declares an {@code allowedShapeTiers()} set (default = all tiers); the catalog enforces
 * that the shape's {@code provenance.tier()} is in every referenced SPI's allowed set
 * at registration time. A CORE-only-restricted SPI cannot be composed by a PLUGIN-tier
 * shape.
 *
 * <p>This validator runs at catalog-construction time (CORE shapes today; plugin shapes
 * when they register via {@code mergePluginShapeContributions} on the FE — the BE side
 * uses this validator as the structural enforcement point). Each non-compatible
 * reference produces a {@link Violation}; the caller decides whether to throw or
 * collect.
 */
public final class ShapeTierCompatibilityValidator {

  /** A single tier-incompatibility finding. */
  public record Violation(
      String shapeId, TrustTier shapeTier, String spiId, String spiKind, java.util.Set<TrustTier> allowedTiers) {

    @Override
    public String toString() {
      return String.format(
          "Shape '%s' (tier=%s) references %s '%s' (allowedShapeTiers=%s) — tier not allowed",
          shapeId, shapeTier, spiKind, spiId, allowedTiers);
    }
  }

  private final PromptContributorRegistry promptContributors;
  private final ContextInjectorRegistry contextInjectors;
  private final StreamConsumerRegistry streamConsumers;
  private final IterationControllerRegistry iterationControllers;

  public ShapeTierCompatibilityValidator(
      PromptContributorRegistry promptContributors,
      ContextInjectorRegistry contextInjectors,
      StreamConsumerRegistry streamConsumers,
      IterationControllerRegistry iterationControllers) {
    this.promptContributors = Objects.requireNonNull(promptContributors, "promptContributors");
    this.contextInjectors = Objects.requireNonNull(contextInjectors, "contextInjectors");
    this.streamConsumers = Objects.requireNonNull(streamConsumers, "streamConsumers");
    this.iterationControllers =
        Objects.requireNonNull(iterationControllers, "iterationControllers");
  }

  /**
   * Validate every SPI reference on the given shape against the corresponding SPI's
   * {@code allowedShapeTiers()}. Returns the list of violations (empty if all references
   * are tier-compatible OR if the referenced id isn't registered — that's the
   * static-callsite gap, a separate failure mode caught by the F4 audit).
   */
  public List<Violation> validate(ConversationShape shape) {
    List<Violation> out = new ArrayList<>();
    TrustTier shapeTier = shape.provenance().tier();
    for (String id : shape.promptContributorIds()) {
      Optional<PromptContributor> spi = promptContributors.findById(id);
      if (spi.isPresent() && !spi.get().allowedShapeTiers().contains(shapeTier)) {
        out.add(
            new Violation(
                shape.id().value(),
                shapeTier,
                id,
                "PromptContributor",
                spi.get().allowedShapeTiers()));
      }
    }
    for (String id : shape.contextInjectorIds()) {
      Optional<ContextInjector> spi = contextInjectors.findById(id);
      if (spi.isPresent() && !spi.get().allowedShapeTiers().contains(shapeTier)) {
        out.add(
            new Violation(
                shape.id().value(),
                shapeTier,
                id,
                "ContextInjector",
                spi.get().allowedShapeTiers()));
      }
    }
    for (String id : shape.streamConsumerIds()) {
      Optional<StreamConsumer> spi = streamConsumers.findById(id);
      if (spi.isPresent() && !spi.get().allowedShapeTiers().contains(shapeTier)) {
        out.add(
            new Violation(
                shape.id().value(),
                shapeTier,
                id,
                "StreamConsumer",
                spi.get().allowedShapeTiers()));
      }
    }
    String controllerId = shape.iterationControllerId();
    if (controllerId != null) {
      Optional<IterationController> spi = iterationControllers.findById(controllerId);
      if (spi.isPresent() && !spi.get().allowedShapeTiers().contains(shapeTier)) {
        out.add(
            new Violation(
                shape.id().value(),
                shapeTier,
                controllerId,
                "IterationController",
                spi.get().allowedShapeTiers()));
      }
    }
    return out;
  }

  /**
   * Validate every shape in the registry; throw {@link IllegalStateException} with a
   * concatenated diagnostic if any violations are found. Intended for catalog-construction
   * paths (call from {@code CoreConversationShapeCatalog.catalog()} or from the plugin
   * registration merge path).
   */
  public void enforceOrThrow(Iterable<ConversationShape> shapes) {
    List<Violation> all = new ArrayList<>();
    for (ConversationShape shape : shapes) {
      all.addAll(validate(shape));
    }
    if (!all.isEmpty()) {
      StringBuilder msg = new StringBuilder("ShapeTierCompatibilityValidator: ");
      msg.append(all.size()).append(" violation(s):");
      for (Violation v : all) msg.append("\n  - ").append(v);
      throw new IllegalStateException(msg.toString());
    }
  }
}
