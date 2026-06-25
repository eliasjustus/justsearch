/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.bootstrap.phases;

import io.justsearch.agent.api.conversation.ExecutionMode;
import io.justsearch.agent.api.conversation.IterationMode;
import io.justsearch.agent.api.conversation.PersistenceMode;
import io.justsearch.agent.api.registry.Audience;
import io.justsearch.agent.api.registry.Category;
import io.justsearch.agent.api.registry.ConsumerHook;
import io.justsearch.agent.api.registry.ConsumerPermission;
import io.justsearch.agent.api.registry.ContributionRegistry;
import io.justsearch.agent.api.registry.ConversationShape;
import io.justsearch.agent.api.registry.ConversationShapeRef;
import io.justsearch.agent.api.registry.DataClass;
import io.justsearch.agent.api.registry.DeliveryMode;
import io.justsearch.agent.api.registry.DiagnosticChannel;
import io.justsearch.agent.api.registry.DiagnosticChannelRef;
import io.justsearch.agent.api.registry.I18nKey;
import io.justsearch.agent.api.registry.LoggerNamespaceSelector;
import io.justsearch.agent.api.registry.Placement;
import io.justsearch.agent.api.registry.Plugin;
import io.justsearch.agent.api.registry.PluginContributions;
import io.justsearch.agent.api.registry.PluginRef;
import io.justsearch.agent.api.registry.Presentation;
import io.justsearch.agent.api.registry.Privacy;
import io.justsearch.agent.api.registry.ProducerKind;
import io.justsearch.agent.api.registry.Prompt;
import io.justsearch.agent.api.registry.PromptRef;
import io.justsearch.agent.api.registry.Provenance;
import io.justsearch.agent.api.registry.Resource;
import io.justsearch.agent.api.registry.ResourceRef;
import io.justsearch.agent.api.registry.RiskTier;
import io.justsearch.agent.api.registry.SubscriptionMode;
import io.justsearch.agent.api.registry.Surface;
import io.justsearch.agent.api.registry.SurfaceConsumes;
import io.justsearch.agent.api.registry.SurfaceRef;
import io.justsearch.agent.api.registry.TrustTier;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Tempdoc 560 §10.4 (declaration-completeness demo): an example vendor plugin that contributes a
 * {@link DiagnosticChannel}, a {@link Surface}, and a {@link ConversationShape} through the new
 * {@link PluginContributions} axes, exercising the plugin→endpoint→UI path end-to-end. The Surface is
 * the showcase: it declares {@link Placement#RAIL} so a real plugin-contributed rail item appears in
 * the shell rail (reusing the {@code jf-log-surface} mountTag so clicking it renders the Logs view —
 * no bespoke plugin custom element needed). It is the closest live reference for a plugin author.
 *
 * <p><strong>Dev-gated.</strong> Off unless {@code -Djustsearch.demo.plugin=true}, so production ships
 * no {@code vendor.example.*} contributions. One multi-axis {@link ContributionRegistry.Installation}
 * routes all three kinds through the same validate-before-commit + trust-boundary loop (a
 * {@code TRUSTED_PLUGIN}-tier {@code vendor.example.*} plugin — never {@code core.*}).
 */
public final class ExamplePlugin {

  private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ExamplePlugin.class);

  /** System property that opts the example plugin in (off by default). */
  public static final String FLAG = "justsearch.demo.plugin";

  /** Env-var equivalent of {@link #FLAG} (dev convenience; propagates reliably to the forked head). */
  public static final String ENV_FLAG = "JUSTSEARCH_DEMO_PLUGIN";

  /** The example plugin manifest id (a vendor-namespaced ref — the host owns {@code core.*}). */
  public static final PluginRef PLUGIN_ID = new PluginRef("vendor.example.demo");

  public static final DiagnosticChannelRef CHANNEL_ID =
      new DiagnosticChannelRef("vendor.example.demo-log");
  public static final SurfaceRef SURFACE_ID = new SurfaceRef("vendor.example.demo-surface");
  public static final ConversationShapeRef SHAPE_ID =
      new ConversationShapeRef("vendor.example.demo-shape");
  // Tempdoc 560 §28 Phase 2 — the two remaining contribution KINDS (Resource + Prompt), so the demo
  // plugin exercises ALL six axes and the new Resources/Prompts serving bridges have a live producer.
  public static final ResourceRef RESOURCE_ID = new ResourceRef("vendor.example.demo-resource");
  public static final PromptRef PROMPT_ID = new PromptRef("vendor.example.demo-prompt");

  private ExamplePlugin() {}

  /** Whether the example plugin is enabled this boot (system property OR env var). */
  public static boolean enabled() {
    return Boolean.getBoolean(FLAG) || "true".equalsIgnoreCase(System.getenv(ENV_FLAG));
  }

  /**
   * Install the example vendor contributions (channel + surface + shape) into the shared registry when
   * enabled; no-op otherwise. One atomic multi-axis installation — a rejection (the refs are all
   * {@code vendor.example.*}, never colliding) propagates like any boot failure.
   */
  public static void installIfEnabled(ContributionRegistry contributions) {
    if (!enabled()) {
      log.info("ExamplePlugin: disabled (set -D{} or {}=true to enable the demo plugin)", FLAG, ENV_FLAG);
      return;
    }
    log.info(
        "ExamplePlugin: installing demo vendor.example.demo (channel + RAIL surface + shape) [tempdoc 560 §10.4]");
    contributions.install(
        new ContributionRegistry.Installation(
            plugin(),
            List.of(),
            List.of(resource()),
            List.of(prompt()),
            List.of(surface()),
            List.of(channel()),
            List.of(shape()),
            Map.of()));
  }

  private static Plugin plugin() {
    return new Plugin(
        PLUGIN_ID,
        Presentation.of(
            new I18nKey("plugin.vendor-example-demo.label"),
            new I18nKey("plugin.vendor-example-demo.description")),
        provenance(),
        Audience.USER,
        new PluginContributions(
            Set.of(),
            Set.of(RESOURCE_ID),
            Set.of(PROMPT_ID),
            Set.of(SURFACE_ID),
            Set.of(CHANNEL_ID),
            Set.of(SHAPE_ID)),
        List.of(new ConsumerHook.Realized("registry", Audience.OPERATOR)));
  }

  /** A minimal non-advisory STATE Resource (the 14-arg back-compat ctor: audience=USER, no emissionPolicy). */
  private static Resource resource() {
    return new Resource(
        RESOURCE_ID,
        Presentation.of(
            new I18nKey("registry-resource.vendor-example-demo-resource.label"),
            new I18nKey("registry-resource.vendor-example-demo-resource.description")),
        "vendor.example.demo-resource.schema.json",
        Category.STATE,
        SubscriptionMode.ONE_SHOT,
        "/api/registry/resources",
        "document",
        Optional.empty(),
        Optional.empty(),
        provenance(),
        Privacy.noPaths(),
        Set.of(),
        Set.of(),
        "id");
  }

  /** A minimal Prompt (templateRef + no required variables). */
  private static Prompt prompt() {
    return new Prompt(
        PROMPT_ID,
        Presentation.of(
            new I18nKey("registry-prompt.vendor-example-demo-prompt.label"),
            new I18nKey("registry-prompt.vendor-example-demo-prompt.description")),
        "vendor.example.demo-prompt.template",
        List.of(),
        provenance(),
        Audience.USER,
        // §5 NonEmpty<ConsumerHook> keystone — a Prompt must name ≥1 consumer.
        List.of(new ConsumerHook.Realized("registry", Audience.OPERATOR)));
  }

  private static DiagnosticChannel channel() {
    return new DiagnosticChannel(
        CHANNEL_ID,
        Presentation.of(
            new I18nKey("registry-diagnostic.vendor-example-demo-log.label"),
            new I18nKey("registry-diagnostic.vendor-example-demo-log.description")),
        Set.of(DataClass.CONFIG_VALUES),
        ProducerKind.IN_PROCESS_LOGBACK,
        DeliveryMode.SSE_STREAM,
        LoggerNamespaceSelector.of(Map.of()),
        "/api/diagnostic-channels/vendor-example-demo-log/stream",
        ConsumerPermission.OPERATOR_OVERRIDE,
        provenance());
  }

  /**
   * A USER RAIL surface. Reuses the core {@code jf-log-surface} mountTag (a registered custom element)
   * so the rail item renders the Logs view on click; consumes nothing (so it is not a second core
   * interaction surface).
   */
  private static Surface surface() {
    return new Surface(
        SURFACE_ID,
        Presentation.of(
            new I18nKey("registry-surface.vendor-example-demo-surface.label"),
            new I18nKey("registry-surface.vendor-example-demo-surface.description")),
        Audience.USER,
        Placement.RAIL,
        SurfaceConsumes.empty(),
        "jf-log-surface",
        provenance(),
        Optional.empty(),
        RiskTier.LOW);
  }

  /** A minimal SHAPE_DRIVEN / ONE_SHOT shape (no iterationControllerId required). */
  private static ConversationShape shape() {
    return new ConversationShape(
        SHAPE_ID,
        Presentation.of(
            new I18nKey("registry-shape.vendor-example-demo-shape.label"),
            new I18nKey("registry-shape.vendor-example-demo-shape.description")),
        Audience.USER,
        provenance(),
        ExecutionMode.SHAPE_DRIVEN,
        IterationMode.ONE_SHOT,
        PersistenceMode.EPHEMERAL,
        List.of(),
        List.of(),
        List.of(),
        null,
        List.of());
  }

  private static Provenance provenance() {
    return new Provenance(TrustTier.TRUSTED_PLUGIN, "vendor.example", "1.0");
  }
}
