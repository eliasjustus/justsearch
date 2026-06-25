/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.api.registry;

import io.justsearch.substrate.ContributionComposer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The one tier-agnostic contribution mechanism (tempdoc 560 §4.2; 507 KCS): a registry of typed
 * contributions with {@code install}/{@code uninstall} verbs that <em>any</em> contributor may call,
 * and over which the "plugin substrate is merely the transactional composer" — atomic install with
 * rollback, ownership-keyed revocation.
 *
 * <p><strong>One composer, all four axes (§4.4).</strong> An {@link Installation} carries the four
 * axis projections of the one declaration model — EXECUTABLE {@link Operation}s, OBSERVABLE
 * {@link Resource}s, LANGUAGE_MEDIATED {@link Prompt}s, and PRESENTATION {@link Surface}s — plus the
 * EXECUTABLE-axis {@link OperationHandler}s. The composer does <em>not</em> re-derive "register a
 * thing, attenuate by trust, allow revocation" per axis: every axis is a {@link Declaration}, so a
 * single ref-keyed store, a single validate-before-commit pass, and a single ownership index cover
 * all four uniformly. Adding a fifth axis projection is a new {@code List<X>} on {@link Installation}
 * routed through the same generic {@code validateAxis} + commit loop — not a new mechanism.
 *
 * <p>This collapses the per-mechanism re-derivation of "register a thing, attenuate by trust, allow
 * revocation" into one primitive (§1 diagnosis). The MCP-host is its first composer: it installs one
 * {@link Installation} per external server (the server's whole contribution set atomically), and the
 * plugin can be uninstalled wholesale when the server disconnects.
 *
 * <p>Transactional discipline: {@code install} validates the ENTIRE set (plugin not already present,
 * no ref collisions across any axis, no {@code core.*} mint by a non-core plugin) <em>before</em>
 * committing any state, so a rejected installation leaves the registry unchanged — the rollback is
 * structural (validate-before-commit), not a compensating undo.
 */
public final class ContributionRegistry {

  /**
   * One atomic unit of contribution: a plugin manifest plus the six axis projections it installs
   * (EXECUTABLE operations + handlers, OBSERVABLE resources, LANGUAGE_MEDIATED prompts, PRESENTATION
   * surfaces, OBSERVABLE-stream diagnosticChannels, and conversationShapes). Empty axes are the common
   * case (the MCP-host contributes only operations). The two trailing axes (tempdoc 560 §10.4 —
   * declaration completeness) route through the same generic install loop as the original four: each is
   * a {@link Declaration}, so adding them is "a new {@code List<X>} routed through {@link #axes()}", not
   * a new mechanism.
   */
  public record Installation(
      Plugin plugin,
      List<Operation> operations,
      List<Resource> resources,
      List<Prompt> prompts,
      List<Surface> surfaces,
      List<DiagnosticChannel> diagnosticChannels,
      List<ConversationShape> conversationShapes,
      Map<OperationRef, OperationHandler> handlers) {
    public Installation {
      Objects.requireNonNull(plugin, "plugin");
      operations = operations == null ? List.of() : List.copyOf(operations);
      resources = resources == null ? List.of() : List.copyOf(resources);
      prompts = prompts == null ? List.of() : List.copyOf(prompts);
      surfaces = surfaces == null ? List.of() : List.copyOf(surfaces);
      diagnosticChannels = diagnosticChannels == null ? List.of() : List.copyOf(diagnosticChannels);
      conversationShapes = conversationShapes == null ? List.of() : List.copyOf(conversationShapes);
      handlers = handlers == null ? Map.of() : Map.copyOf(handlers);
    }

    /** The EXECUTABLE-only shape (the MCP-host's contribution): operations + handlers, no other axis. */
    public Installation(
        Plugin plugin, List<Operation> operations, Map<OperationRef, OperationHandler> handlers) {
      this(plugin, operations, List.of(), List.of(), List.of(), List.of(), List.of(), handlers);
    }

    /** A non-executable single-axis shape (e.g. a plugin contributing only DiagnosticChannels). */
    public static Installation ofDiagnosticChannels(
        Plugin plugin, List<DiagnosticChannel> diagnosticChannels) {
      return new Installation(
          plugin, List.of(), List.of(), List.of(), List.of(), diagnosticChannels, List.of(), Map.of());
    }

    /**
     * Every axis projection in declaration form, in
     * EXECUTABLE→OBSERVABLE→LANGUAGE_MEDIATED→PRESENTATION→DIAGNOSTIC_STREAM→CONVERSATION_SHAPE order.
     */
    List<List<? extends Declaration>> axes() {
      return List.of(
          operations, resources, prompts, surfaces, diagnosticChannels, conversationShapes);
    }
  }

  /** The ONE shared composer (tempdoc 560 §4.2/§4.3) — keyed by ref, reused by the Worker too. */
  private final ContributionComposer<RegistryRef<?>, Declaration> composer =
      new ContributionComposer<>(RegistryRef::value);

  /** Plugin manifests by id — the composer tracks ownership; this holds the full {@link Plugin} for lookup. */
  private final Map<PluginRef, Plugin> plugins = new LinkedHashMap<>();

  /** EXECUTABLE-axis handlers (the only axis that carries an executable body). */
  private final Map<OperationRef, OperationHandler> handlers = new LinkedHashMap<>();

  /**
   * Atomically install a plugin's whole contribution set across all four axes — delegated to the one
   * shared {@link ContributionComposer}, which applies Lifecycle/Boundary/Trust uniformly. The Head's
   * {@link TrustTier} is projected onto the composer's admission booleans via
   * {@link ContributionSubstrates} (the same composer the Worker's extractor registry uses).
   *
   * @throws IllegalStateException if the plugin is already installed, the runtime cannot provide the
   *     plugin's required isolation, any contribution ref collides with an already-installed one, or a
   *     non-core plugin attempts to mint a {@code core.*} ref (the registry is left unchanged)
   */
  public synchronized void install(Installation installation) {
    Objects.requireNonNull(installation, "installation");
    Plugin plugin = installation.plugin();
    PluginRef pluginId = plugin.id();
    TrustTier tier = plugin.trustTier();
    Map<RegistryRef<?>, Declaration> entries = new LinkedHashMap<>();
    for (List<? extends Declaration> axis : installation.axes()) {
      for (Declaration decl : axis) {
        entries.put(decl.id(), decl);
      }
    }
    composer.install(
        new ContributionComposer.Installation<>(
            pluginId,
            "plugin " + pluginId.value(),
            ContributionSubstrates.isCore(tier),
            ContributionSubstrates.boundaryAdmissible(tier),
            ContributionSubstrates.boundaryDetail(tier),
            entries));
    // The composer accepted (it throws on rejection, leaving itself unchanged) — commit the Head-side
    // tables only now, so a rejected install leaves the registry entirely unchanged.
    plugins.put(pluginId, plugin);
    handlers.putAll(installation.handlers());
  }

  /** Revoke a plugin and every contribution it owns across all axes. Returns false if not installed. */
  public synchronized boolean uninstall(PluginRef pluginId) {
    Objects.requireNonNull(pluginId, "pluginId");
    ContributionComposer.UninstallResult<RegistryRef<?>> result = composer.uninstall(pluginId);
    if (!result.wasInstalled()) {
      return false;
    }
    plugins.remove(pluginId);
    for (RegistryRef<?> ref : result.removedKeys()) {
      if (ref instanceof OperationRef opRef) {
        handlers.remove(opRef);
      }
    }
    return true;
  }

  public synchronized List<Plugin> plugins() {
    return List.copyOf(plugins.values());
  }

  /** The composed EXECUTABLE axis (Operations), in installation order. */
  public synchronized List<Operation> operations() {
    return ofType(Operation.class);
  }

  /** The composed OBSERVABLE axis (Resources), in installation order. */
  public synchronized List<Resource> resources() {
    return ofType(Resource.class);
  }

  /** The composed LANGUAGE_MEDIATED axis (Prompts), in installation order. */
  public synchronized List<Prompt> prompts() {
    return ofType(Prompt.class);
  }

  /** The composed PRESENTATION axis (Surfaces), in installation order. */
  public synchronized List<Surface> surfaces() {
    return ofType(Surface.class);
  }

  /** The composed OBSERVABLE-stream axis (DiagnosticChannels), in installation order. */
  public synchronized List<DiagnosticChannel> diagnosticChannels() {
    return ofType(DiagnosticChannel.class);
  }

  /** The composed ConversationShape axis, in installation order. */
  public synchronized List<ConversationShape> conversationShapes() {
    return ofType(ConversationShape.class);
  }

  private <T extends Declaration> List<T> ofType(Class<T> type) {
    return composer.values().stream().filter(type::isInstance).map(type::cast).toList();
  }

  public synchronized Map<OperationRef, OperationHandler> handlers() {
    return Map.copyOf(handlers);
  }

  /** Which plugin contributed a ref (the ownership index uninstall keys on), any axis. */
  public synchronized Optional<PluginRef> ownerOf(RegistryRef<?> ref) {
    return composer.ownerOf(ref).map(PluginRef.class::cast);
  }

  public synchronized Optional<Plugin> plugin(PluginRef id) {
    return Optional.ofNullable(plugins.get(id));
  }

  public synchronized boolean isInstalled(PluginRef id) {
    return plugins.containsKey(id);
  }
}
