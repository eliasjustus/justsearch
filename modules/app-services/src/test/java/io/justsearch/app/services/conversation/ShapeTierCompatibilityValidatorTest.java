package io.justsearch.app.services.conversation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.agent.api.conversation.ContextInjector;
import io.justsearch.agent.api.conversation.ConversationContext;
import io.justsearch.agent.api.conversation.ExecutionMode;
import io.justsearch.agent.api.conversation.InjectorResult;
import io.justsearch.agent.api.conversation.IterationController;
import io.justsearch.agent.api.conversation.IterationDecision;
import io.justsearch.agent.api.conversation.IterationMode;
import io.justsearch.agent.api.conversation.PersistenceMode;
import io.justsearch.agent.api.conversation.PromptContributor;
import io.justsearch.agent.api.conversation.PromptFragment;
import io.justsearch.agent.api.conversation.StreamConsumer;
import io.justsearch.agent.api.conversation.StreamConsumerResult;
import io.justsearch.agent.api.registry.Audience;
import io.justsearch.agent.api.registry.ConversationShape;
import io.justsearch.agent.api.registry.EventDescriptor;
import io.justsearch.agent.api.registry.ConversationShapeRef;
import io.justsearch.agent.api.registry.I18nKey;
import io.justsearch.agent.api.registry.Presentation;
import io.justsearch.agent.api.registry.Provenance;
import io.justsearch.agent.api.registry.TrustTier;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Slice 491 §9.D Phase E (G4) — tier-compatibility validator tests.
 *
 * <p>Covers the §9.D E4 acceptance criteria:
 * <ol>
 *   <li>A CORE-tier shape composing a CORE-restricted SPI passes (CORE is in the allowed
 *       set).
 *   <li>A TRUSTED_PLUGIN-tier shape composing a CORE-restricted SPI fails with a Violation
 *       naming the shape, the SPI, and the allowed-tiers set.
 *   <li>A TRUSTED_PLUGIN-tier shape composing an unrestricted SPI passes (default
 *       allowedShapeTiers = all tiers).
 *   <li>{@code enforceOrThrow} aggregates all violations into one IllegalStateException;
 *     no-violation case is a no-op.
 *   <li>Validator silently accepts references to unregistered SPI ids (the static-callsite
 *     gap is a separate failure mode caught by other audits — F4's everyShape audit).
 * </ol>
 */
final class ShapeTierCompatibilityValidatorTest {

  private static ConversationShape shape(String id, TrustTier tier, List<String> promptIds) {
    return new ConversationShape(
        new ConversationShapeRef(id),
        new Presentation(
            new I18nKey("test.label"),
            new I18nKey("test.desc"),
            Optional.empty(),
            Optional.empty()),
        Audience.USER,
        new Provenance(tier, "test-plugin", "v1"),
        ExecutionMode.SUBSTRATE_DRIVEN,
        IterationMode.ONE_SHOT,
        PersistenceMode.EPHEMERAL,
        promptIds,
        List.of(),
        List.of(),
        null,
        EventDescriptor.namesOnly(List.of("done")));
  }

  /** Test SPI: PromptContributor restricted to CORE shapes. */
  private static final class CoreOnlyPromptContributor implements PromptContributor {
    @Override
    public String id() {
      return "test.core-only-prompt";
    }

    @Override
    public Optional<PromptFragment> contribute(ConversationContext ctx) {
      return Optional.empty();
    }

    @Override
    public Set<TrustTier> allowedShapeTiers() {
      return EnumSet.of(TrustTier.CORE);
    }
  }

  /** Test SPI: PromptContributor allowed for all tiers (default behavior). */
  private static final class UnrestrictedPromptContributor implements PromptContributor {
    @Override
    public String id() {
      return "test.unrestricted-prompt";
    }

    @Override
    public Optional<PromptFragment> contribute(ConversationContext ctx) {
      return Optional.empty();
    }
  }

  private static final PromptContributor CORE_ONLY = new CoreOnlyPromptContributor();
  private static final PromptContributor UNRESTRICTED = new UnrestrictedPromptContributor();

  private static ShapeTierCompatibilityValidator newValidator() {
    return new ShapeTierCompatibilityValidator(
        PromptContributorRegistry.of(List.of(CORE_ONLY, UNRESTRICTED)),
        ContextInjectorRegistry.of(List.of()),
        StreamConsumerRegistry.of(List.of()),
        IterationControllerRegistry.of(List.of()));
  }

  @Test
  @DisplayName("CORE shape composing a CORE-only SPI passes (CORE ⊆ allowed)")
  void coreShapeWithCoreOnlySpiPasses() {
    var validator = newValidator();
    var s = shape("core.x", TrustTier.CORE, List.of("test.core-only-prompt"));
    assertEquals(List.of(), validator.validate(s));
  }

  @Test
  @DisplayName(
      "PLUGIN shape composing a CORE-only SPI fails with a tier-mismatch Violation")
  void pluginShapeWithCoreOnlySpiFails() {
    var validator = newValidator();
    var s =
        shape(
            "vendor.acme.x", TrustTier.TRUSTED_PLUGIN, List.of("test.core-only-prompt"));
    var violations = validator.validate(s);
    assertEquals(1, violations.size());
    var v = violations.get(0);
    assertEquals("vendor.acme.x", v.shapeId());
    assertEquals(TrustTier.TRUSTED_PLUGIN, v.shapeTier());
    assertEquals("test.core-only-prompt", v.spiId());
    assertEquals("PromptContributor", v.spiKind());
    assertEquals(EnumSet.of(TrustTier.CORE), v.allowedTiers());
  }

  @Test
  @DisplayName("PLUGIN shape composing an unrestricted SPI passes (default = all tiers)")
  void pluginShapeWithUnrestrictedSpiPasses() {
    var validator = newValidator();
    var s =
        shape(
            "vendor.acme.y", TrustTier.UNTRUSTED_PLUGIN, List.of("test.unrestricted-prompt"));
    assertEquals(List.of(), validator.validate(s));
  }

  @Test
  @DisplayName(
      "enforceOrThrow aggregates violations across multiple shapes into one exception")
  void enforceOrThrowAggregatesViolations() {
    var validator = newValidator();
    var s1 = shape("vendor.acme.a", TrustTier.TRUSTED_PLUGIN, List.of("test.core-only-prompt"));
    var s2 = shape("vendor.acme.b", TrustTier.UNTRUSTED_PLUGIN, List.of("test.core-only-prompt"));
    var ex =
        assertThrows(
            IllegalStateException.class, () -> validator.enforceOrThrow(List.of(s1, s2)));
    assertTrue(ex.getMessage().contains("2 violation"));
    assertTrue(ex.getMessage().contains("vendor.acme.a"));
    assertTrue(ex.getMessage().contains("vendor.acme.b"));
  }

  @Test
  @DisplayName("enforceOrThrow is a no-op when no violations are present")
  void enforceOrThrowNoOpOnGreen() {
    var validator = newValidator();
    var s = shape("core.x", TrustTier.CORE, List.of("test.core-only-prompt"));
    // Should not throw.
    validator.enforceOrThrow(List.of(s));
  }

  @Test
  @DisplayName(
      "References to unregistered SPI ids are silently accepted (separate static-callsite"
          + " gap caught by F4 audit)")
  void unregisteredSpiIdsAreIgnoredByTierValidator() {
    var validator = newValidator();
    var s = shape("vendor.acme.z", TrustTier.UNTRUSTED_PLUGIN, List.of("never-registered"));
    assertEquals(List.of(), validator.validate(s));
  }

  @Test
  @DisplayName(
      "Validator covers all four SPI kinds (PromptContributor, ContextInjector,"
          + " StreamConsumer, IterationController)")
  void validatorCoversAllFourSpiKinds() {
    var coreOnlyInjector =
        new ContextInjector() {
          @Override
          public String id() {
            return "test.core-only-injector";
          }

          @Override
          public InjectorResult inject(ConversationContext ctx) {
            return InjectorResult.empty();
          }

          @Override
          public Set<TrustTier> allowedShapeTiers() {
            return EnumSet.of(TrustTier.CORE);
          }
        };
    var coreOnlyConsumer =
        new StreamConsumer() {
          @Override
          public String id() {
            return "test.core-only-consumer";
          }

          @Override
          public StreamConsumerResult onChunk(String chunkText, ConversationContext ctx) {
            return StreamConsumerResult.empty();
          }

          @Override
          public StreamConsumerResult onDone(String fullText, ConversationContext ctx) {
            return StreamConsumerResult.empty();
          }

          @Override
          public Set<TrustTier> allowedShapeTiers() {
            return EnumSet.of(TrustTier.CORE);
          }
        };
    var coreOnlyController =
        new IterationController() {
          @Override
          public String id() {
            return "test.core-only-controller";
          }

          @Override
          public IterationDecision next(ConversationContext ctx) {
            return IterationDecision.STOP_SUCCESS;
          }

          @Override
          public Set<TrustTier> allowedShapeTiers() {
            return EnumSet.of(TrustTier.CORE);
          }
        };
    var validator =
        new ShapeTierCompatibilityValidator(
            PromptContributorRegistry.of(List.of(CORE_ONLY)),
            ContextInjectorRegistry.of(List.of(coreOnlyInjector)),
            StreamConsumerRegistry.of(List.of(coreOnlyConsumer)),
            IterationControllerRegistry.of(List.of(coreOnlyController)));

    var pluginShape =
        new ConversationShape(
            new ConversationShapeRef("vendor.acme.kitchen-sink"),
            new Presentation(
                new I18nKey("test.label"),
                new I18nKey("test.desc"),
                Optional.empty(),
                Optional.empty()),
            Audience.USER,
            new Provenance(TrustTier.UNTRUSTED_PLUGIN, "plugin", "v1"),
            ExecutionMode.SUBSTRATE_DRIVEN,
            IterationMode.WITHIN_TURN_ITERATION,
            PersistenceMode.EPHEMERAL,
            List.of("test.core-only-prompt"),
            List.of("test.core-only-injector"),
            List.of("test.core-only-consumer"),
            "test.core-only-controller",
            EventDescriptor.namesOnly(List.of("done")));
    var violations = validator.validate(pluginShape);
    assertEquals(4, violations.size(), () -> "Expected 4 violations (one per SPI kind), got " + violations);
    Set<String> kinds =
        violations.stream()
            .map(ShapeTierCompatibilityValidator.Violation::spiKind)
            .collect(java.util.stream.Collectors.toSet());
    assertEquals(
        Set.of("PromptContributor", "ContextInjector", "StreamConsumer", "IterationController"),
        kinds);
  }
}
