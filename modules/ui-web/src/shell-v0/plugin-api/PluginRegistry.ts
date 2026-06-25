// SPDX-License-Identifier: Apache-2.0
/**
 * PluginRegistry — V1.1 host-side registry of installed plugins.
 *
 * V1 (slice 3a.1 Phase 7): scaffold with `install` only.
 * V1.1 (slice 3a.1.6): adds `uninstall(id)` + install-time
 * validation (`contractVersion`, `tagNamespace`).
 *
 * The host calls `install()` once per plugin during startup; each
 * `install()` invokes the plugin's `register()` hook with the host
 * API surface. After install, the host can query the registry by
 * capability axis to dispatch plugin-contributed surfaces. A user
 * disable / hot-reload calls `uninstall(id)` which invokes the
 * plugin's optional `unregister()` hook + removes the plugin from
 * surface-port dispatch.
 *
 * V1.1 has no loader, no sandboxing, and no signing. Plugins are
 * statically compiled into the bundle by application code. V1.5+
 * adds the loader (lazy-loaded ES modules) + the sandbox surface;
 * see archive/source-tempdocs/421-extensibility.md.
 */

import {
  PLUGIN_CONTRACT_VERSION,
  type PluginCapabilities,
  type PluginContribution,
  type PluginHostApi,
  type PluginTrustTier,
  type PluginManifest,
  type PluginSurfaceContribution,
  type SurfacePortContext,
  type SurfacePortHandler,
} from './plugin-types.js';
import { createHostApi, type HostApiDeps } from './HostApiImpl.js';
import { isPresentationAdmissible, isConstrainedPresentationTag } from './PresentationVocabulary.js';
import {
  registerPluginCatalog,
  unregisterPluginCatalog,
} from '../../i18n/resourceCatalog.js';
// Slice 447-followup-live-wiring §X.12.8 Item 1.2 — V1.5.1 polish
// completes the install/uninstall path for the three plugin-overlay
// merge/remove function pairs. These were defined but never called
// from any production callsite (the gap §X.12 surfaced for Recovery
// and that the deeper investigation generalized to all three).
import {
  mergePluginSurfaceContributions,
  removePluginSurfaceContributions,
} from '../../api/registry/SurfaceCatalogClient.js';
import {
  mergePluginResourceContributions,
  removePluginResourceContributions,
} from '../../api/registry/ResourceCatalogClient.js';
import {
  mergePluginRecoveryOverlays,
  removePluginRecoveryOverlays,
} from '../../api/registry/RecoveryOverlayClient.js';
import {
  registerStatusBarItem as registerStatusBarItemFromContribution,
  unregisterStatusBarItem,
} from '../commands/StatusBarRegistry.js';
import {
  registerInspectorTab as registerInspectorTabFromContribution,
  unregisterInspectorTab,
} from '../commands/InspectorTabRegistry.js';
import {
  registerContextAction as registerContextActionFromContribution,
  unregisterContextAction,
} from '../commands/ContextActionRegistry.js';
import {
  registerEmptyState as registerEmptyStateFromContribution,
  unregisterEmptyState,
} from '../commands/EmptyStateRegistry.js';
import {
  registerWalkthrough,
  unregisterWalkthrough,
} from '../commands/WalkthroughRegistry.js';
import { getSurfaceAliases, setSurfaceAliases } from '../router/catalogResolver.js';
// Tempdoc 560 §28.G — the ConversationShape runner: a plugin's declared shape becomes mountable via
// `<jf-chat-shape-mount>` by registering its (shapeRef → viewTag) factory at install time, and the
// factory is withdrawn on uninstall (ownership-checked).
import { registerViewFactory, unregisterViewFactory } from '../router/viewFactoryRegistry.js';
// Tempdoc 511 — aggregate-strategy plugin axis.
import {
  registerAggregateStrategy,
  removePluginAggregateStrategies,
  type AggregateStrategy,
} from '../aggregate-substrate/aggregateRegistry.js';
import {
  isWireAggregateKind,
  type WireAggregateKind,
} from '../aggregate-substrate/aggregateKinds.js';
import {
  isSurfaceContextKind,
  type SurfaceContextKind,
} from '../aggregate-substrate/surfaceContextKinds.js';
// Tempdoc 543 §20.7 C1 — multi-axis Provenance producer.
import { makePluginProvenance, stampInstalledAt } from '../primitives/provenance.js';
import type { Provenance } from '../primitives/provenance.js';

// PluginTrustTier is now exported from plugin-types.ts (tempdoc 507).
// Re-export for back-compat with consumers that import from here.
export type { PluginTrustTier } from './plugin-types.js';

/**
 * Slice 449 §0 D2 / phase 12 — audience floor by trust tier:
 *  - CORE / TRUSTED_PLUGIN -> USER (no demotion)
 *  - UNTRUSTED_PLUGIN -> OPERATOR (admin-only)
 * AGENT is not orderable; pass-through.
 * The helper returns the effective audience after applying the floor.
 */
export function audienceFloorForTier(
  declared: PluginSurfaceContribution['audience'],
  tier: PluginTrustTier,
): PluginSurfaceContribution['audience'] {
  if (declared === 'AGENT') return 'AGENT';
  if (tier === 'UNTRUSTED_PLUGIN') {
    // OPERATOR floor: USER -> OPERATOR; OPERATOR/DEVELOPER pass-through.
    return declared === 'USER' ? 'OPERATOR' : declared;
  }
  // CORE / TRUSTED_PLUGIN: USER floor — no demotion needed.
  return declared;
}

/**
 * Slice 449 phase 12 — entry returned to the host's SurfaceCatalog
 * merge. `effectiveAudience` reflects the floor-applied value.
 */
export interface PluginSurfaceEntry {
  pluginId: string;
  trustTier: PluginTrustTier;
  contribution: PluginSurfaceContribution;
  effectiveAudience: PluginSurfaceContribution['audience'];
}

export interface InstalledPlugin {
  manifest: PluginManifest;
  /** Set if the plugin's register() threw; the plugin is skipped for dispatch. */
  registerError: Error | null;
  /**
   * 478 §4.F refactor: i18n removal is now O(1) namespace delete
   * via `unregisterPluginCatalog(pluginId)`. The previous V1.5 alpha
   * `installedTranslationKeys: string[]` tracking array is no
   * longer needed — the catalog itself is owner-scoped.
   *
   * Preserved as an empty array for back-compat with consumers
   * (Settings UI's plugin row reads `installedTranslationKeys.length`
   * for diagnostic display); future cleanup converts the consumer
   * to `manifest.translations?.en` directly.
   */
  installedTranslationKeys: string[];
  /** Tempdoc 508 §4 — preserved contribution for uninstall cleanup. */
  contributionApplied?: PluginContribution;
  /**
   * Tempdoc 560 §4.4 — the trust tier the loader's TrustChannel actually assigned this plugin.
   * Compiled-in plugins install without one (defaulting to TRUSTED_PLUGIN); a URL-loaded UNTRUSTED
   * plugin carries UNTRUSTED_PLUGIN, so its non-vocabulary surfaces are dropped by the PRESENTATION
   * constraint (`isPresentationAdmissible`).
   */
  trustTier?: PluginTrustTier;
}

export class PluginRegistry {
  private readonly plugins = new Map<string, InstalledPlugin>();
  private readonly surfacePortHandlers = new Map<
    string,
    Array<{ pluginId: string; handler: SurfacePortHandler }>
  >();
  private hostApiDeps: HostApiDeps | null = null;
  private hostContractVersions: Readonly<Record<string, string>> | null = null;

  /**
   * Tempdoc 507 §3.1 — inject framework dependencies so the registry
   * can build rich PluginHostApi instances. Call once at boot before
   * installing plugins. If not called, the registry falls back to the
   * legacy 2-method PluginHostApi for back-compat.
   */
  setHostApiDeps(deps: HostApiDeps): void {
    this.hostApiDeps = deps;
  }

  /**
   * Tempdoc 508 §11.8 / §13.8 — supply the host's advertised
   * `contractVersions` map from the capabilities handshake. When
   * present, `install()` validates manifest.contractVersions against
   * it (per-Category major-match + ≥-minor). Plugins declaring
   * `host.*` sub-API requirements need this to be set; otherwise
   * any declared contractVersions entries cause install to throw.
   */
  setHostContractVersions(versions: Readonly<Record<string, string>> | null): void {
    this.hostContractVersions = versions;
  }

  /**
   * Install a plugin. Throws if:
   * - a plugin with the same id is already installed
   * - manifest.tagNamespace !== manifest.id
   * - manifest.contractVersion is incompatible with
   *   PLUGIN_CONTRACT_VERSION (rule: exact major + ≥minor)
   *
   * After validation, the plugin's `register()` is called
   * immediately; if it throws, the plugin is recorded with the
   * error and dispatch skips it (other plugins are unaffected).
   */
  install(manifest: PluginManifest, trustTier?: PluginTrustTier): void {
    if (this.plugins.has(manifest.id)) {
      throw new Error(
        `PluginRegistry: a plugin with id '${manifest.id}' is already installed.`,
      );
    }
    if (manifest.tagNamespace !== manifest.id) {
      throw new Error(
        `PluginRegistry: plugin '${manifest.id}' has tagNamespace='${manifest.tagNamespace}'; ` +
          `must equal id (== '${manifest.id}'). The renderer contract spec ` +
          `(slice 3a.1.5 §2) requires tagNamespace and id to be identical.`,
      );
    }
    assertCompatibleContractVersion(manifest);
    // Tempdoc 508 §11.8 / §13.8 — per-Category / per-sub-API
    // validation. Skipped if the host hasn't supplied a
    // contractVersions map yet (e.g., in unit tests that don't go
    // through the handshake). Plugins that don't declare
    // contractVersions are likewise unaffected.
    if (this.hostContractVersions) {
      assertCompatibleAgainstHostCategories(manifest, this.hostContractVersions);
    } else if (manifest.contractVersions) {
      // Plugin declares per-sub-API requirements but the host hasn't
      // injected its capabilities yet. This is a programming error
      // (the host should call setHostContractVersions before
      // installing plugins that declare them); fail loudly rather
      // than silently accept the plugin against an unknown contract.
      throw new Error(
        `PluginRegistry: plugin '${manifest.id}' declares contractVersions ` +
          `but host has not supplied serverCapabilities.contractVersions via ` +
          `setHostContractVersions(). Call setHostContractVersions(...) at boot.`,
      );
    }
    // Slice 471 — register the plugin's i18n translations BEFORE
    // calling its `register()` hook so the plugin's own setup code
    // can `localizeResourceKey()` against its own keys without
    // race conditions.
    //
    // 478 §4.F (slice 477 follow-on): translations land in a
    // namespaced bucket keyed by manifest.id. The catalog enforces
    // owner-scope at the storage level — cross-plugin overwrites
    // are structurally impossible. Removal is one O(1) delete in
    // uninstall().
    const installedTranslationKeys: string[] = [];
    if (manifest.translations) {
      // V1.5 alpha: en-only locale resolution. Future locales
      // are additive.
      const enEntries = manifest.translations.en;
      if (enEntries && typeof enEntries === 'object') {
        const added = registerPluginCatalog(manifest.id, enEntries);
        if (added > 0) {
          // Preserve the per-key list for the InstalledPlugin
          // diagnostic surface (Settings UI's plugin row reads
          // .length for the i18n-key count display). Removal no
          // longer depends on this array.
          installedTranslationKeys.push(...Object.keys(enEntries));
        }
      }
    }
    const hostApi = this.makeHostApi(manifest.id);
    let registerError: Error | null = null;
    let contributionApplied: PluginContribution | undefined;
    try {
      // 478 §4.I: register may return a PluginContribution record.
      // If so, the registry applies it atomically AFTER register
      // returns. If register throws, none of the contribution
      // applies (atomicity guaranteed). If register returns void
      // (V1.5 alpha legacy), the imperative side effects already
      // happened during register's body — V1.5 alpha behavior
      // preserved.
      const contribution = manifest.register(hostApi);
      if (contribution !== undefined && contribution !== null) {
        contributionApplied = contribution;
        applyContribution(
          manifest.id,
          manifest,
          contribution,
          hostApi,
          trustTier ?? 'TRUSTED_PLUGIN',
        );
        // Translations from the contribution win over manifest.translations
        // if both are present (per the documented behavior).
        if (contribution.translations) {
          const enFromContribution = contribution.translations['en'];
          if (enFromContribution && typeof enFromContribution === 'object') {
            // Re-register the contribution's translations; the
            // owner-scoped catalog (478 §4.F) replaces any prior
            // entries for this plugin.
            const added = registerPluginCatalog(
              manifest.id,
              enFromContribution,
            );
            if (added > 0) {
              // Replace the manifest-level keys with contribution-level keys.
              installedTranslationKeys.length = 0;
              installedTranslationKeys.push(...Object.keys(enFromContribution));
            }
          }
        }
      }
    } catch (err) {
      registerError = err instanceof Error ? err : new Error(String(err));
    }
    this.plugins.set(manifest.id, {
      manifest,
      registerError,
      installedTranslationKeys,
      contributionApplied,
      trustTier,
    });
    // Tempdoc 521 §16.4 deeper — fire `onPluginInstalled` only on
    // successful registration. A plugin whose `register()` threw is
    // still in the map (with `registerError`), but downstream consumers
    // (walkthroughs gated by `extensionInstalled:<id>`) should not
    // advance for failed installs.
    if (!registerError) {
      // §25.ζ#5 — call manifest.activate() after a successful register.
      // This is fire-and-forget for sync activate; async activate
      // returns a Promise the caller could await if needed (V1
      // installs don't await — first-party installs are at module
      // load and don't block boot).
      if (manifest.activate) {
        try {
          const r = manifest.activate();
          // Best-effort: if activate returns a Promise, catch rejection
          // so it doesn't surface as an unhandled rejection.
          if (r && typeof (r as Promise<unknown>).catch === 'function') {
            (r as Promise<unknown>).catch(() => {
              /* swallow — activate failure is logged by future telemetry */
            });
          }
        } catch {
          /* swallow */
        }
      }
      for (const listener of this.installListeners) {
        try { listener(manifest.id); } catch { /* swallow */ }
      }
    }
  }

  // Tempdoc 521 §16.4 deeper — install-event broadcast channel,
  // mirrors CommandRegistry.onCommandInvoked.
  private readonly installListeners = new Set<(pluginId: string) => void>();

  onInstalled(listener: (pluginId: string) => void): () => void {
    this.installListeners.add(listener);
    return () => this.installListeners.delete(listener);
  }

  /**
   * Uninstall a plugin. Calls the plugin's `unregister()` hook if
   * present (V1 plugins without `unregister` are a no-op teardown),
   * removes all surface-port handlers the plugin registered, and
   * removes the plugin from the registry.
   *
   * Returns true if the plugin was removed, false if no plugin
   * with that id was registered. Errors thrown from `unregister`
   * are caught + logged silently; uninstall always cleans up the
   * registry-level state regardless of unregister-side throws.
   */
  uninstall(id: string): boolean {
    const entry = this.plugins.get(id);
    if (!entry) {
      return false;
    }
    // §25.ζ#5 — deactivate runs BEFORE unregister so the plugin can
    // tear down any state that depends on its registered contributions
    // still being live.
    if (entry.manifest.deactivate) {
      try {
        const r = entry.manifest.deactivate();
        if (r && typeof (r as Promise<unknown>).catch === 'function') {
          (r as Promise<unknown>).catch(() => {
            /* swallow */
          });
        }
      } catch {
        /* swallow */
      }
    }
    if (entry.manifest.unregister) {
      try {
        entry.manifest.unregister(this.makeHostApi(id));
      } catch {
        // Swallow unregister-side errors so registry teardown
        // still completes. A future logger surfaces these.
      }
    }
    // Strip every surface-port handler this plugin registered.
    for (const [portId, list] of this.surfacePortHandlers.entries()) {
      const remaining = list.filter((h) => h.pluginId !== id);
      if (remaining.length === 0) {
        this.surfacePortHandlers.delete(portId);
      } else {
        this.surfacePortHandlers.set(portId, remaining);
      }
    }
    // 478 §4.F (slice 477 follow-on): O(1) namespace delete.
    // Replaces the V1.5 alpha key-list iteration.
    unregisterPluginCatalog(id);
    // Tempdoc 548 §4.3a (S2) — remove every per-plugin merge axis via the SAME
    // PER_PLUGIN_AXES table applyContribution applied them through (idempotent;
    // each removePluginX no-ops on an empty filter). Co-located apply+remove → the
    // merge-axis enumerations cannot drift (surface/resource/recovery/aggregate).
    for (const axis of PER_PLUGIN_AXES) {
      axis.remove(id);
    }
    // Tempdoc 508 §4 — remove plugin UI slot contributions using the
    // entry's preserved contribution record.
    if (entry.contributionApplied) {
      const c = entry.contributionApplied;
      // Tempdoc 548 R2 / §6 — remove every per-item axis via the SAME
      // CONTRIBUTION_AXES table applyContribution registered them through, so
      // the install/uninstall enumerations are derived from one source and
      // cannot drift (F15/F16 unrepresentable).
      for (const axis of PER_ITEM_AXES) {
        for (const item of axis.items(c)) axis.unregister(axis.key(id, item));
      }
      // Tempdoc 560 §28.G — withdraw the ConversationShape view factories this plugin registered
      // (ownership-checked, so a re-used shape id held by another owner is left untouched). Symmetric
      // with the install-time registerViewFactory step; without it an uninstalled plugin's factory
      // would linger in the module-global registry.
      for (const { contribution: shape } of c.conversationShapes ?? []) {
        if (shape.viewTag) unregisterViewFactory(shape.id, id);
      }
      // Tempdoc 499 F6 — strip the plugin's resolution aliases (F15).
      // Best-effort: only delete a key whose current target still
      // matches what this plugin set, so we don't clobber an alias a
      // later contributor overrode for the same key.
      const aliasContribs = c.resolutionAliases ?? [];
      if (aliasContribs.length > 0) {
        const current = { ...getSurfaceAliases() };
        let changed = false;
        for (const a of aliasContribs) {
          if (current[a.from]?.target === a.to) {
            delete current[a.from];
            changed = true;
          }
        }
        if (changed) setSurfaceAliases(current);
      }
    }
    this.plugins.delete(id);
    return true;
  }

  /** True if a plugin with the given id is installed and registered cleanly. */
  has(id: string): boolean {
    const entry = this.plugins.get(id);
    return entry !== undefined && entry.registerError === null;
  }

  /** Return the installed entry (manifest + register error). */
  get(id: string): InstalledPlugin | undefined {
    return this.plugins.get(id);
  }

  /** Return all installed plugins, including those whose register failed. */
  list(): InstalledPlugin[] {
    return Array.from(this.plugins.values());
  }

  /**
   * Dispatch a surface port: invoke the handler that any plugin
   * registered for the given port id. Returns the first
   * successfully-rendered element. V1 dispatch is "first-wins"; V2+
   * may add ranking or composition.
   */
  dispatchSurfacePort(
    portId: string,
    payload?: unknown,
  ): HTMLElement | null {
    const handlers = this.surfacePortHandlers.get(portId) ?? [];
    const ctx: SurfacePortContext = { portId, payload };
    for (const { handler } of handlers) {
      try {
        const el = handler(ctx);
        if (el) {
          return el;
        }
      } catch {
        // Plugin handler threw — skip and try the next handler.
      }
    }
    return null;
  }

  /**
   * Filter installed plugins by capability axis. The shell uses this
   * to enumerate which plugins contribute to a given axis (e.g.,
   * "all plugins that register a custom element").
   */
  byCapability(
    predicate: (capabilities: PluginCapabilities) => boolean,
  ): InstalledPlugin[] {
    return this.list().filter(
      (p) => p.registerError === null && predicate(p.manifest.capabilities),
    );
  }

  /**
   * Slice 449 phase 12 — enumerate Surface contributions across all
   * installed plugins, with audience-floor applied per trust tier.
   *
   * Plugins that errored during register() are skipped. Surfaces with
   * a declared audience below the trust-tier floor are silently
   * promoted (per §0 D2: registration succeeds; the catalog's effective
   * audience is the floor-elevated value).
   */
  surfaceContributions(): PluginSurfaceEntry[] {
    const out: PluginSurfaceEntry[] = [];
    for (const p of this.list()) {
      if (p.registerError !== null) continue;
      const surfaces = p.manifest.capabilities.surfaces ?? [];
      // Tempdoc 560 §4.4: use the trust tier the loader's TrustChannel actually assigned (stored at
      // install). Compiled-in plugins install without one and default to TRUSTED_PLUGIN (they ship
      // with the host); a URL-loaded UNTRUSTED plugin carries UNTRUSTED_PLUGIN, so its non-jf-vocabulary
      // surfaces are dropped below by the PRESENTATION constraint.
      const tier: PluginTrustTier = p.trustTier ?? 'TRUSTED_PLUGIN';
      for (const s of surfaces) {
        // §4.4 PRESENTATION constraint: an UNTRUSTED plugin surface may only mount the constrained
        // host vocabulary (jf-*), never its own element — otherwise it is a second presentation
        // authority. Inadmissible untrusted surfaces are dropped (registration of the plugin still
        // succeeds; the offending surface is simply not catalogued). CORE/TRUSTED are unaffected.
        if (!isPresentationAdmissible(s, tier)) {
          continue;
        }
        out.push({
          pluginId: p.manifest.id,
          trustTier: tier,
          contribution: s,
          effectiveAudience: audienceFloorForTier(s.audience, tier),
        });
      }
    }
    return out;
  }

  private makeHostApi(pluginId: string, tier?: PluginTrustTier): PluginHostApi {
    const registerPort = (pid: string, id: string, handler: SurfacePortHandler) => {
      const list = this.surfacePortHandlers.get(id) ?? [];
      list.push({ pluginId: pid, handler });
      this.surfacePortHandlers.set(id, list);
    };

    if (this.hostApiDeps) {
      return createHostApi(pluginId, tier ?? 'TRUSTED_PLUGIN', {
        ...this.hostApiDeps,
        registerSurfacePort: registerPort,
      });
    }

    // Legacy fallback: nested sub-interfaces for back-compat when deps not injected
    return {
      installedTagNamespace: pluginId,
      pluginId,
      trustTier: tier ?? 'TRUSTED_PLUGIN',
      registration: {
        registerSurfacePort: (id, handler) => registerPort(pluginId, id, handler),
        registerCommand: () => {},
        registerKeybinding: () => {},
      },
      data: {
        fetch: () => Promise.reject(new Error('Host API deps not initialized')),
        invokeOperation: () => Promise.reject(new Error('Host API deps not initialized')),
        subscribeResource: () => () => {},
        subscribeHealth: () => () => {},
      },
      navigation: {
        navigate: () => {},
        navigateBack: () => {},
        navigateForward: () => {},
      },
      ui: {
        showNotification: () => {},
        showConfirmDialog: () => Promise.resolve(false),
        copyToClipboard: () => Promise.resolve(),
        showInspector: () => {},
        // Tempdoc 508-followup §ε1 — fallback stub when deps not injected.
        scrollSurfaceTo: () => {},
      },
      discovery: {
        listOperations: () => [],
        listResources: () => [],
        listSurfaces: () => [],
        getSystemStatus: () => Promise.resolve(null),
      },
      settings: {
        getSetting: () => undefined,
        setSetting: () => {},
        subscribeSetting: () => () => {},
      },
      platform: {
        capabilities: new Set(),
        pickFile: () => Promise.resolve(null),
        pickFolder: () => Promise.resolve(null),
        revealInExplorer: () => Promise.resolve(),
        openExternal: () => Promise.resolve(),
      },
      search: {
        subscribeSearch: () => () => {},
        getSearchState: () => ({ query: '', results: [], totalHits: 0, matchCount: 0, facetsTruncated: false, isSearching: false, processingTimeMs: null, error: null }),
        setQuery: () => {},
        submitQuery: () => {},
        setSearchApiBase: () => {},
        subscribePinnedSearches: () => () => {},
        getPinnedSearches: () => [],
        pinSearch: () => null,
        unpinSearch: () => false,
        isPinned: () => false,
        recordSearchRun: () => {},
        subscribeFilters: () => () => {},
        getFilters: () => ({} as import('./plugin-types.js').SearchFilterSnapshot),
        setFilterRange: () => {},
        hasActiveFilter: () => false,
        hitToSelectedItem: () => ({ id: '', title: '' }),
      },
      inspector: {
        subscribeInspector: () => () => {},
        getInspectorState: () => ({ isOpen: false, selected: null, activeTab: 'preview', ai: { loading: false, text: '', error: null } }),
      },
      selection: {
        // Tempdoc 508-followup §γ2 — no-op stub for back-compat when
        // HostApiDeps are not injected. Real wiring lives in HostApiImpl.
        current: () => null,
        subscribe: () => () => {},
        // Tempdoc 526 §5 — `actions` and `compose` are part of the read-only
        // contract; stub returns empty list / no-op so the type contract
        // holds even in this fallback path.
        actions: () => [],
        compose: () => false,
        setSelection: () => {},
        clearSelection: () => {},
      },
      theme: {
        subscribeActiveTheme: () => () => {},
        getActiveThemeId: () => null,
        selectTheme: () => Promise.resolve(),
        // Tempdoc 560 §23 / 567 — fallback host stub: no tokens, no themes, no-op preview.
        getTokens: () => [],
        listThemes: () => [],
        exportTheme: () => null,
        previewTokens: () => {},
      },
      layout: {
        subscribeUserConfig: () => () => {},
        getUserConfig: () => ({ version: 1 }),
        setSurfaceVisibility: () => {},
        setSurfaceOrder: () => {},
        clearAllLayoutOverrides: () => {},
        setActiveLayoutId: () => {},
        onSurfaceCatalogChange: () => () => {},
      },
      utilities: {
        formatRelativeTime: () => '',
      },
      ai: {
        invokeShape: () => Promise.reject(new Error('Host API deps not initialized')),
        // eslint-disable-next-line require-yield
        streamShape: async function* () {},
        openSession: () => ({
          id: 'noop',
          // eslint-disable-next-line require-yield
          send: async function* () {},
          close: () => {},
        }),
        // Tempdoc 508-followup §ε1 — fallback stubs reject so callers
        // surface "deps not initialized" rather than silently returning
        // empty data.
        getSessionTranscript: () => Promise.reject(new Error('Host API deps not initialized')),
        getSessionMetadata: () => Promise.reject(new Error('Host API deps not initialized')),
      },
    } satisfies PluginHostApi;
  }
}

/**
 * Validate `manifest.contractVersion` against `PLUGIN_CONTRACT_VERSION`
 * per the V1.1 rule: exact major + ≥ minor. Same major + lower minor
 * is accepted (deprecation warn-on-use). Different major or higher
 * minor is rejected with a migration message.
 *
 * Implementation: parses "MAJOR.MINOR" only. Patch-level differences
 * are ignored. A plugin declaring a non-numeric `contractVersion`
 * throws.
 */
function assertCompatibleContractVersion(manifest: PluginManifest): void {
  if (!manifest.contractVersion) {
    throw new Error(
      `PluginRegistry: plugin '${manifest.id}' has no contractVersion. ` +
        `V1.1 manifests must declare contractVersion (target host ` +
        `'${PLUGIN_CONTRACT_VERSION}').`,
    );
  }
  const pluginMm = parseMajorMinor(manifest.contractVersion);
  // Host version is a const; parse is statically guaranteed to succeed.
  const hostMm = parseMajorMinor(PLUGIN_CONTRACT_VERSION)!;
  if (!pluginMm) {
    throw new Error(
      `PluginRegistry: plugin '${manifest.id}' has malformed ` +
        `contractVersion='${manifest.contractVersion}'; expected 'MAJOR.MINOR'.`,
    );
  }
  if (pluginMm.major !== hostMm.major) {
    throw new Error(
      `PluginRegistry: plugin '${manifest.id}' targets contractVersion ` +
        `'${manifest.contractVersion}' but host runs ` +
        `'${PLUGIN_CONTRACT_VERSION}'. Major version mismatch is a ` +
        `breaking change; the plugin must be updated to target the ` +
        `host's major version.`,
    );
  }
  if (pluginMm.minor > hostMm.minor) {
    throw new Error(
      `PluginRegistry: plugin '${manifest.id}' targets contractVersion ` +
        `'${manifest.contractVersion}' but host runs ` +
        `'${PLUGIN_CONTRACT_VERSION}'. Plugin requires a newer host than ` +
        `is running. Upgrade the host or use an older plugin build.`,
    );
  }
  // Lower minor is allowed (the plugin doesn't depend on the host's
  // newer features). No warn here — a future logger may surface
  // deprecation; V1.1 silently accepts.
}

/**
 * Slice 3a-1-8 Phase 6: validate a plugin manifest's per-Category
 * `contractVersions` map against the host's reported
 * `serverCapabilities.contractVersions` (from the handshake).
 *
 * V1 plugins that don't declare `contractVersions` are not validated against
 * the handshake (back-compat — only the legacy single-Category
 * {@link assertCompatibleContractVersion} runs). V2+ plugins that declare
 * `contractVersions` get per-Category MAJOR.MINOR validation following the
 * same rule as the legacy plugin-Category check.
 *
 * @throws Error on incompatibility — major mismatch, plugin needs newer host
 *   than reported, or unknown Category not in the host's map.
 */
export function assertCompatibleAgainstHostCategories(
  manifest: PluginManifest,
  hostContractVersions: Readonly<Record<string, string>> | undefined,
): void {
  const declared = manifest.contractVersions;
  if (!declared) {
    // Legacy single-Category declaration; nothing to validate beyond the
    // legacy plugin-Category check that already ran.
    return;
  }
  if (!hostContractVersions) {
    throw new Error(
      `PluginRegistry: plugin '${manifest.id}' declares contractVersions ` +
        `but host did not report serverCapabilities.contractVersions. ` +
        `Plugin requires a newer host that supports per-Category contract ` +
        `version negotiation (slice 3a-1-8 Phase 6).`,
    );
  }
  for (const [category, declaredVersion] of Object.entries(declared)) {
    const hostVersion = hostContractVersions[category];
    if (!hostVersion) {
      throw new Error(
        `PluginRegistry: plugin '${manifest.id}' declares contractVersions['${category}']` +
          ` = '${declaredVersion}' but host does not advertise that Category. ` +
          `Either the plugin requires a Category the host doesn't ship, or the ` +
          `host's contract substrate doesn't include this Category yet.`,
      );
    }
    const declaredMm = parseMajorMinor(declaredVersion);
    const hostMm = parseMajorMinor(hostVersion);
    if (!declaredMm) {
      throw new Error(
        `PluginRegistry: plugin '${manifest.id}' has malformed ` +
          `contractVersions['${category}']='${declaredVersion}'; expected 'MAJOR.MINOR'.`,
      );
    }
    if (!hostMm) {
      throw new Error(
        `PluginRegistry: host advertised malformed contractVersions['${category}']` +
          `='${hostVersion}'; expected 'MAJOR.MINOR'.`,
      );
    }
    if (declaredMm.major !== hostMm.major) {
      throw new Error(
        `PluginRegistry: plugin '${manifest.id}' targets contractVersions['${category}']` +
          `='${declaredVersion}' but host runs '${hostVersion}'. Major version ` +
          `mismatch is a breaking change.`,
      );
    }
    if (declaredMm.minor > hostMm.minor) {
      throw new Error(
        `PluginRegistry: plugin '${manifest.id}' targets contractVersions['${category}']` +
          `='${declaredVersion}' but host runs '${hostVersion}'. Plugin requires a ` +
          `newer host's '${category}' Category than is reported.`,
      );
    }
  }
}

function parseMajorMinor(s: string): { major: number; minor: number } | null {
  const m = /^(\d+)\.(\d+)/.exec(s);
  if (!m) return null;
  return { major: Number(m[1]), minor: Number(m[2]) };
}

/**
 * 478 §4.I — apply a plugin contribution record. Called by
 * `install()` after `register()` returns a non-void value. Each
 * sub-contribution has its own validation; failures throw and
 * are caught by `install()`'s outer try/catch (the plugin lands
 * with `registerError` set; partial state may have been applied
 * for sub-contributions earlier in the iteration order).
 *
 * V1.5.1 alpha applies in dependency-safe order:
 *   1. Custom elements (must register before surface contributions
 *      reference them via mountTag)
 *   2. Surface-port handlers (declarative; idempotent)
 *
 * V1.5.2 will introduce a two-phase apply (validate-all-then-commit-all)
 * so partial-failure is impossible. V1.5.1 alpha's per-step apply
 * is sufficient because the failures it catches are programming
 * errors (malformed klass, invalid tag suffix), not runtime conditions.
 */
/**
 * Tempdoc 548 R2 / §6 — the per-item contribution-axis table.
 *
 * The F15/F16 defect class was install/uninstall asymmetry: a per-item axis
 * (statusBar, inspectorTab, …) registered under `${pluginId}.${item.id}` on
 * install but, on uninstall, a SEPARATE enumeration had to remember to
 * unregister the same keys. The two enumerations drifted (F15 shipped with
 * savedViews-style omissions). This table makes the apply + remove of each
 * per-item axis **co-located in one entry**, and both `applyContribution`
 * (install) and `uninstall` iterate this single list — so adding an axis is one
 * entry carrying BOTH sides; you cannot register an axis without also defining
 * its removal. The asymmetry is structurally unrepresentable. The §6
 * contribution-symmetry guard remains the regression net under this refactor.
 *
 * Out of this table by design (different, non-drift-prone patterns, documented):
 *   - customElements — HTML one-way registration; no removal is possible.
 *   - surfacePorts — torn down via the registry-instance handler strip in
 *     uninstall (keyed by pluginId, not per item).
 *   - translations (i18n) — O(1) namespace delete via unregisterPluginCatalog.
 *   - surface/resource/recovery/aggregate contributions — removed by a single
 *     removePluginX(pluginId) call (no per-item key to drift).
 *   - resolutionAliases — a conditional map-merge/delete (special semantics).
 */
type ContribItem<K extends keyof PluginContribution> =
  NonNullable<PluginContribution[K]> extends readonly (infer T)[] ? T : never;

interface PerItemAxis<Item> {
  /** The PluginContribution field this axis reads (for diagnostics). */
  readonly field: keyof PluginContribution;
  /** This axis's items from a contribution record (absent → empty). */
  readonly items: (c: PluginContribution) => readonly Item[];
  /** Namespaced registry key for one item. Shared by apply + remove. */
  readonly key: (pluginId: string, item: Item) => string;
  /** Register one item under `key`. */
  readonly register: (
    key: string,
    item: Item,
    provenance: Provenance,
    pluginId: string,
  ) => void;
  /** Remove the item previously registered under `key`. */
  readonly unregister: (key: string) => void;
}

function defineAxis<Item>(axis: PerItemAxis<Item>): PerItemAxis<Item> {
  return axis;
}

const PER_ITEM_AXES: readonly PerItemAxis<unknown>[] = [
  defineAxis<ContribItem<'statusBarItems'>>({
    field: 'statusBarItems',
    items: (c) => c.statusBarItems ?? [],
    key: (pluginId, item) => `${pluginId}.${item.id}`,
    register: (key, item, provenance) =>
      registerStatusBarItemFromContribution({
        id: key,
        position: item.position,
        priority: item.priority,
        render: item.render,
        source: 'plugin',
        provenance,
      }),
    unregister: (key) => unregisterStatusBarItem(key),
  }),
  defineAxis<ContribItem<'inspectorTabs'>>({
    field: 'inspectorTabs',
    items: (c) => c.inspectorTabs ?? [],
    key: (pluginId, item) => `${pluginId}.${item.id}`,
    register: (key, item, provenance) =>
      registerInspectorTabFromContribution({
        id: key,
        label: item.label,
        icon: item.icon,
        priority: item.priority,
        render: item.render,
        source: 'plugin',
        provenance,
      }),
    unregister: (key) => unregisterInspectorTab(key),
  }),
  defineAxis<ContribItem<'contextActions'>>({
    field: 'contextActions',
    items: (c) => c.contextActions ?? [],
    key: (pluginId, item) => `${pluginId}.${item.id}`,
    register: (key, item, provenance) =>
      registerContextActionFromContribution({
        id: key,
        context: item.context,
        label: item.label,
        icon: item.icon,
        priority: item.priority,
        ...(item.when !== undefined ? { when: item.when } : {}),
        handler: item.handler,
        ...(item.enabled !== undefined ? { enabled: item.enabled } : {}),
        source: 'plugin',
        provenance,
      }),
    unregister: (key) => unregisterContextAction(key),
  }),
  defineAxis<ContribItem<'emptyStateContributions'>>({
    field: 'emptyStateContributions',
    items: (c) => c.emptyStateContributions ?? [],
    key: (pluginId, item) => `${pluginId}.${item.id}`,
    register: (key, item, provenance) =>
      registerEmptyStateFromContribution({
        id: key,
        context: item.context,
        priority: item.priority,
        ...(item.when !== undefined ? { when: item.when } : {}),
        render: item.render,
        source: 'plugin',
        provenance,
      }),
    unregister: (key) => unregisterEmptyState(key),
  }),
  defineAxis<ContribItem<'walkthroughs'>>({
    field: 'walkthroughs',
    items: (c) => c.walkthroughs ?? [],
    key: (pluginId, item) => `${pluginId}.${item.id}`,
    register: (key, item, provenance, pluginId) =>
      registerWalkthrough({
        id: key,
        title: item.title,
        ...(item.description !== undefined ? { description: item.description } : {}),
        priority: item.priority,
        source: pluginId === 'core' ? 'core' : 'plugin',
        ...(item.when !== undefined ? { when: item.when } : {}),
        steps: item.steps,
        provenance,
      }),
    unregister: (key) => unregisterWalkthrough(key),
  }),
] as readonly PerItemAxis<unknown>[];

/**
 * Tempdoc 548 §4.3a (S2) — per-PLUGIN contribution-axis table.
 *
 * Generalizes the R2 co-location to the merge axes (surface/resource/recovery
 * contributions + aggregate strategies). These remove via a single
 * `removePluginX(pluginId)` call (no per-item key), but their apply lived in
 * `applyContribution` while their remove lived in `uninstall` — a second
 * enumeration that could drift (add a merge axis, forget its uninstall remove).
 * Now each axis's apply + remove are ONE entry and both sites iterate this table,
 * so adding a merge axis carries both sides (F15/F16 unrepresentable for these
 * too). `resolutionAliases` stays separate (special conditional-delete remove);
 * customElements / surfacePorts / translations are out-of-table (no
 * removeByPlugin shape), documented at `applyContribution`.
 */
interface PerPluginAxis {
  readonly field: string;
  readonly apply: (
    pluginId: string,
    contribution: PluginContribution,
    trustTier: PluginTrustTier,
    // 548 §4.3 — the uniform Provenance minted once at the install site below.
    // Merge axes that store provenance (surface/resource) thread it through
    // verbatim instead of reconstructing a lossy partial at the catalog.
    provenance: Provenance,
  ) => void;
  readonly remove: (pluginId: string) => void;
}

const PER_PLUGIN_AXES: readonly PerPluginAxis[] = [
  {
    field: 'surfaceContributions',
    apply: (pluginId, contribution, trustTier, provenance) => {
      const list = contribution.surfaceContributions;
      if (!list || list.length === 0) return;
      mergePluginSurfaceContributions(
        list.map((s) => ({
          pluginId,
          contribution: {
            ...s.contribution,
            consumes: s.contribution.consumes
              ? {
                  operations: s.contribution.consumes.operations
                    ? Array.from(s.contribution.consumes.operations)
                    : undefined,
                  resources: s.contribution.consumes.resources
                    ? Array.from(s.contribution.consumes.resources)
                    : undefined,
                  prompts: s.contribution.consumes.prompts
                    ? Array.from(s.contribution.consumes.prompts)
                    : undefined,
                  diagnosticChannels: s.contribution.consumes.diagnosticChannels
                    ? Array.from(s.contribution.consumes.diagnosticChannels)
                    : undefined,
                  // Tempdoc 560 §28.G — preserve the consumed ConversationShape(s) through the merge.
                  // Without this the chrome can't derive the `shape-id` for a `jf-chat-shape-mount`
                  // surface, so a plugin shape surface renders the no-shape-id placeholder (the runner
                  // factory is registered, but the host never asks for it).
                  conversationShapes: s.contribution.consumes.conversationShapes
                    ? Array.from(s.contribution.consumes.conversationShapes)
                    : undefined,
                }
              : undefined,
          },
          effectiveAudience: audienceFloorForTier(s.contribution.audience, trustTier),
          provenance,
        })),
      );
    },
    remove: (id) => removePluginSurfaceContributions(id),
  },
  {
    field: 'resourceContributions',
    apply: (pluginId, contribution, _trustTier, provenance) => {
      const list = contribution.resourceContributions;
      if (!list || list.length === 0) return;
      // 548 §4.3 — the per-contribution `r.version` is no longer rebuilt into a
      // partial provenance here; the uniform Provenance (carrying manifest.version
      // + identity + capability) is stored verbatim. (If a future need arises for
      // per-resource version override distinct from the manifest, it belongs on
      // the Provenance, not a second field.)
      mergePluginResourceContributions(
        list.map((r) => ({
          pluginId,
          contribution: r.contribution as Omit<
            import('../../api/types/registry.js').Resource,
            'provenance'
          >,
          provenance,
        })),
      );
    },
    remove: (id) => removePluginResourceContributions(id),
  },
  {
    field: 'recoveryOverlays',
    apply: (pluginId, contribution, _trustTier, provenance) => {
      const list = contribution.recoveryOverlays;
      if (!list || list.length === 0) return;
      // 548 §4.3 — carry the uniform minted Provenance; the overlay's governance
      // check reads provenance.tier (no separate trustTier authority).
      mergePluginRecoveryOverlays(
        list.map((o) => ({
          pluginId,
          conditionId: o.conditionId,
          subject: o.subject,
          operationRef: o.operationRef,
          provenance,
        })),
      );
    },
    remove: (id) => removePluginRecoveryOverlays(id),
  },
  {
    field: 'aggregateStrategies',
    apply: (pluginId, contribution, _trustTier, provenance) => {
      const list = contribution.aggregateStrategies;
      if (!list || list.length === 0) return;
      for (const s of list) {
        if (!isWireAggregateKind(s.aggregate)) continue;
        if (!isSurfaceContextKind(s.context)) continue;
        registerAggregateStrategy({
          aggregate: s.aggregate as WireAggregateKind,
          context: s.context as SurfaceContextKind,
          rank: s.rank,
          strategy: s.strategy as AggregateStrategy<
            WireAggregateKind,
            SurfaceContextKind
          >,
          // `source` stays the dedup/removal identity (511-followup-D); 548 §4.3
          // adds the uniform Provenance for attribution alongside it.
          source: { plugin: pluginId },
          provenance,
        });
      }
    },
    remove: (id) => removePluginAggregateStrategies(id),
  },
];

function applyContribution(
  pluginId: string,
  manifest: PluginManifest,
  contribution: PluginContribution,
  hostApi: PluginHostApi,
  // Tempdoc 560 §28.G — the REAL trust tier the loader's TrustChannel assigned (stored at install).
  // Gates the ConversationShape runner below (an UNTRUSTED plugin may not register its own element as
  // a shape view — §4.4) AND now stamps the contribution Provenance + feeds the PER_PLUGIN_AXES merge
  // (was a hardcoded 'TRUSTED_PLUGIN' — the provenance-tier mis-stamp logged in observations.md).
  installedTier: PluginTrustTier,
): void {
  // Tempdoc 543 §20.7 C1 — multi-axis Provenance producer.
  // PluginRegistry stamps every contribution's optional `provenance`
  // field with a typed value:
  //   - tier: the REAL installed tier (560 §28.G fix) — TRUSTED_PLUGIN for a
  //     compiled-in / operator-allowlisted plugin, UNTRUSTED_PLUGIN for a
  //     URL-loaded plugin that has not passed the trust handshake. (A plugin is
  //     never CORE — CORE is first-party `makeCoreProvenance`; a CORE installedTier
  //     collapses to the TRUSTED_PLUGIN stamp.)
  //   - identity.verified: true ONLY when the handshake has happened, i.e. the
  //     tier is not UNTRUSTED_PLUGIN. An UNTRUSTED contribution must not stamp
  //     verified:true (it would render as VERIFIED via displayTier).
  //   - capability: drawn from manifest.capabilities (a PluginCapabilities
  //     object). Converted to a flat string list of advertised capability
  //     keys.
  //   - installedAt: §25.α7 — stamped at install site below via
  //     stampInstalledAt, NOT inside makePluginProvenance.
  // The ProvenanceChip's VERIFIED branch (Slice 1) is now reachable in
  // production for any plugin contribution rendered through StatusDeck
  // / future InspectorPane / etc.
  const capabilityKeys = Object.keys(
    (manifest.capabilities ?? {}) as Record<string, unknown>,
  ).filter(
    (k) =>
      (manifest.capabilities as Record<string, unknown> | undefined)?.[k] !==
      undefined,
  );
  // 560 §28.G — map the install tier onto the two-valued provenance tier. A URL-loaded UNTRUSTED
  // plugin's resource/aggregate contributions store this Provenance VERBATIM (no re-derivation at
  // enumeration, unlike surfaces), so a hardcoded TRUSTED + verified:true would mis-attribute an
  // untrusted contribution as VERIFIED in chrome.
  const provenanceTier: 'TRUSTED_PLUGIN' | 'UNTRUSTED_PLUGIN' =
    installedTier === 'UNTRUSTED_PLUGIN' ? 'UNTRUSTED_PLUGIN' : 'TRUSTED_PLUGIN';
  const pluginProvenance: Provenance = stampInstalledAt(
    makePluginProvenance(pluginId, manifest.version, provenanceTier, {
      identity: { verified: provenanceTier === 'TRUSTED_PLUGIN' },
      ...(capabilityKeys.length > 0 ? { capability: capabilityKeys } : {}),
    }),
  );
  // 1. Custom elements — host validates tag suffix + namespace,
  // then calls customElements.define on the plugin's behalf.
  if (contribution.customElements && contribution.customElements.length > 0) {
    for (const decl of contribution.customElements) {
      const tag = `${pluginId}-${decl.tagSuffix}`;
      // Validate tag suffix is HTML-spec-compliant (lowercase ASCII,
      // hyphen-separated). customElements.define enforces this too,
      // but throwing earlier with a clearer message is better.
      if (!/^[a-z][a-z0-9.\-_]*$/.test(decl.tagSuffix)) {
        throw new Error(
          `Plugin '${pluginId}' contribution.customElements[].tagSuffix '${decl.tagSuffix}' ` +
            `is not a valid suffix (must match /^[a-z][a-z0-9.\\-_]*$/).`,
        );
      }
      // The host customElements.get() check prevents re-define
      // (HTML spec one-way registration).
      if (!customElements.get(tag)) {
        customElements.define(tag, decl.klass);
      }
    }
  }
  // 1.5 (tempdoc 560 §28.G) — the ConversationShape runner. A plugin's declared shape with a
  // `viewTag` becomes mountable through `<jf-chat-shape-mount shape-id=…>` by registering its
  // (shapeRef → element tag) view factory. Trust-gated three ways:
  //   - namespace: the shape id MUST be `vendor.*` — `registerViewFactory` REPLACES on collision, so
  //     without this a plugin could hijack a `core.*` view (e.g. the unified chat) with its own element.
  //   - presentation (§4.4): an UNTRUSTED plugin may only register a shape whose `viewTag` is the
  //     constrained host vocabulary (`jf-*`); its own element would be a second presentation authority.
  //   - declaration-only shapes (no `viewTag`) register nothing — they remain catalog entries.
  if (contribution.conversationShapes && contribution.conversationShapes.length > 0) {
    for (const { contribution: shape } of contribution.conversationShapes) {
      if (!shape.viewTag) continue;
      if (!shape.id.startsWith('vendor.')) {
        throw new Error(
          `Plugin '${pluginId}' conversationShape '${shape.id}' must be namespaced 'vendor.*' — a ` +
            `plugin may not register a view factory for a 'core.*' shape (it would override the host view).`,
        );
      }
      // §4.4: an UNTRUSTED plugin's own-element shape is DROPPED (not registered), mirroring how
      // `surfaceContributions()` silently skips inadmissible untrusted surfaces — registration of the
      // plugin still succeeds; the offending shape simply never gets a runner. (Hijack of a `core.*`
      // shape above is a contract violation and stays a hard throw.)
      if (installedTier === 'UNTRUSTED_PLUGIN' && !isConstrainedPresentationTag(shape.viewTag)) {
        continue;
      }
      // Pass the owning pluginId so the registry refuses a cross-plugin replace (shape-view hijack) and
      // can withdraw this plugin's factories on uninstall.
      registerViewFactory(shape.id, shape.viewTag, pluginId);
    }
  }
  // 2. Surface-port handlers — registered via the existing host API.
  if (contribution.surfacePorts && contribution.surfacePorts.length > 0) {
    for (const port of contribution.surfacePorts) {
      hostApi.registration.registerSurfacePort(port.portId, port.handler);
    }
  }
  // 3. Slice 447-followup-live-wiring §X.12.8 Item 1.2 — V1.5.1 polish.
  // The three plugin-overlay merge functions are now wired here so
  // declaratively-contributed surfaces, resources, and recovery
  // overlays land in their respective catalogs at install time.
  // Tempdoc 560 §28.G — thread the REAL installed tier (was hardcoded TRUSTED_PLUGIN). The surfaces
  // axis re-derives audience-floor + admissibility from the stored per-plugin tier at enumeration
  // (`surfaceContributions()`), so this apply-time value is a label there — but passing the real tier
  // keeps the merged store honest for any future reader and matches the Provenance stamped above.
  const trustTier: PluginTrustTier = installedTier;
  // Tempdoc 548 §4.3a (S2) — apply every per-plugin merge axis from the single
  // PER_PLUGIN_AXES table; uninstall iterates the SAME table to remove them, so
  // their apply/remove enumerations cannot drift (F15/F16).
  for (const axis of PER_PLUGIN_AXES) {
    axis.apply(pluginId, contribution, trustTier, pluginProvenance);
  }
  // Tempdoc 548 R2 / §6 — apply every per-item axis from the single
  // CONTRIBUTION_AXES table. `uninstall` iterates the SAME table to remove
  // them, so the install/uninstall enumerations cannot drift (F15/F16).
  for (const axis of PER_ITEM_AXES) {
    for (const item of axis.items(contribution)) {
      axis.register(axis.key(pluginId, item), item, pluginProvenance, pluginId);
    }
  }
  // Tempdoc 499 F6 — resolution alias contributions.
  if (
    contribution.resolutionAliases &&
    contribution.resolutionAliases.length > 0
  ) {
    const aliases: Record<string, { target: string; reason: 'alias' }> = {};
    for (const a of contribution.resolutionAliases) {
      aliases[a.from] = { target: a.to, reason: 'alias' };
    }
    setSurfaceAliases({ ...getSurfaceAliases(), ...aliases });
  }
  // Tempdoc 511 / 548 §4.3a — aggregate-strategy contributions apply through the
  // PER_PLUGIN_AXES table above (co-located with their removePluginAggregateStrategies
  // removal). Each entry narrows the loose plugin-boundary discriminators to the
  // strict registry types; invalid entries are skipped.
}
