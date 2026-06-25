package io.justsearch.agent.api.registry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Verifies the {@link Plugin} Manifest tier (tempdoc 560 §4.1) — shared axes + bundled contributions. */
class PluginTest {

  @Test
  void pluginRefEnforcesNamespacedFormat() {
    assertEquals("vendor.mcphost.reference", new PluginRef("vendor.mcphost.reference").value());
    assertThrows(IllegalArgumentException.class, () -> new PluginRef("Bad Id"));
  }

  @Test
  void pluginRefIsARegistryRefButNotARegistryEntry() {
    RegistryRef<Plugin> ref = new PluginRef("vendor.acme.pack");
    assertEquals("vendor.acme.pack", ref.value());
    // Plugin is a Manifest tier — not part of the sealed RegistryEntry primitive hierarchy.
    assertFalse(RegistryEntry.class.isAssignableFrom(Plugin.class));
  }

  @Test
  void contributionsBundlePrimitiveRefsAcrossAxes() {
    PluginContributions c =
        new PluginContributions(
            Set.of(new OperationRef("vendor.acme.run")),
            Set.of(new ResourceRef("vendor.acme.state")),
            Set.of(),
            Set.of(new SurfaceRef("vendor.acme.panel")),
            Set.of(new DiagnosticChannelRef("vendor.acme.log")),
            Set.of(new ConversationShapeRef("vendor.acme.flow")));
    assertEquals(5, c.size());
    assertFalse(c.isEmpty());
    assertTrue(PluginContributions.empty().isEmpty());
    assertEquals(2, PluginContributions.ofOperations(Set.of(new OperationRef("vendor.a.x"), new OperationRef("vendor.a.y"))).size());
  }

  @Test
  void trustTierFlowsFromProvenance() {
    Plugin p =
        new Plugin(
            new PluginRef("vendor.acme.pack"),
            Presentation.of(new I18nKey("k.label"), new I18nKey("k.desc")),
            new Provenance(TrustTier.TRUSTED_PLUGIN, "vendor.acme", "1.0"),
            Audience.AGENT,
            PluginContributions.ofOperations(Set.of(new OperationRef("vendor.acme.run"))),
            List.of(new ConsumerHook.Realized("agent-loop", Audience.AGENT)));
    assertEquals(TrustTier.TRUSTED_PLUGIN, p.trustTier());
    assertEquals(Audience.AGENT, p.audience());
    assertEquals(1, p.contributions().operations().size());
    assertFalse(p.consumers().isEmpty());
  }

  @Test
  void compactConstructorDefaultsNulls() {
    // provenance/audience/contributions default from null; consumers must be supplied (keystone).
    Plugin p =
        new Plugin(
            new PluginRef("vendor.acme.pack"),
            Presentation.of(new I18nKey("k.label"), new I18nKey("k.desc")),
            null,
            null,
            null,
            List.of(new ConsumerHook.Realized("agent-loop", Audience.AGENT)));
    assertEquals(TrustTier.CORE, p.trustTier());
    assertEquals(Audience.USER, p.audience());
    assertTrue(p.contributions().isEmpty());
    assertFalse(p.consumers().isEmpty());
  }

  @Test
  void zeroConsumerPluginIsUnrepresentable() {
    // §5 keystone (rung 2): a Plugin with no consumer cannot be constructed — null/empty both throw.
    PluginRef ref = new PluginRef("vendor.acme.pack");
    Presentation pres = Presentation.of(new I18nKey("k.label"), new I18nKey("k.desc"));
    IllegalArgumentException ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> new Plugin(ref, pres, null, null, null, List.of()));
    assertTrue(ex.getMessage().contains("NonEmpty"));
    assertThrows(
        IllegalArgumentException.class, () -> new Plugin(ref, pres, null, null, null, null));
  }
}
