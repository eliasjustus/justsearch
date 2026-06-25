// SPDX-License-Identifier: Apache-2.0
/**
 * ContributionManifest substrate — Tempdoc 543 §13.2.3.
 *
 * A contributor's contributions are described by a single typed *data*
 * shape — not a sequence of imperative `host.registerCommand(...)`
 * calls. The kernel reads the manifest once and instantiates all
 * contributions atomically, tying every entry to the manifest's
 * Provenance.
 *
 * Main's `PluginContribution` (`plugin-api/plugin-types.ts`) is the
 * partial seed; this substrate generalizes the shape so first-party
 * feature modules can ship the same declarative form, and provides a
 * thin installer (`installContributionManifest`) that doesn't require
 * the sandbox/contract-version machinery PluginRegistry uses for
 * untrusted plugins.
 *
 * Per §13.2.3, fields beyond PluginContribution:
 *   - Provenance baked into the manifest (versus stamped at
 *     registration time per-entry).
 *   - `activate` / `deactivate` lifecycle hooks distinct from one-time
 *     `register` / `unregister`.
 *   - `dependencies` declaration.
 *   - `profileBinding` (global vs profile-scoped — consumed by Slice 10
 *     Workspace Profiles).
 *   - `factsProjectors`, `renderers` entries that wire into §13.2.1
 *     EvaluationContext / §13.3.1 Form.
 *
 * KCS bridge per §19: future `useContributionManifest()` capability
 * module OR an exported `installContributionManifest(manifest)`
 * module-load API.
 *
 * Per §13.7 q.8: this substrate lands as an OPT-IN alongside existing
 * imperative registration. The eventual ground-truth migration
 * (deprecate imperative) is a follow-up; this slice ships the
 * declarative shape + atomic install + lifecycle hooks + one
 * canonical first-party consumer.
 */

import type { Provenance } from '../../primitives/provenance.js';
import { stampInstalledAt } from '../../primitives/provenance.js';
// §25.γ5 — wire the effects-substrate inverse lookup at module load.
import { setEffectInverseLookup } from '../effects/index.js';
// Profile-scoped manifest factories live in a leaf registry (cycle break,
// tempdoc 530 UI-cycle gate) so this module no longer imports `profiles/`.
import { registerProfileScopedManifestFactory } from '../manifestFactoryRegistry.js';
import type { Action } from '../actions/index.js';
import type { AddressableKind } from '../addressable.js';
import type { Projector } from '../evaluationContext/index.js';
import type { StatusBarItem } from '../../commands/StatusBarRegistry.js';
import type { InspectorTabContribution } from '../../commands/InspectorTabRegistry.js';
import type { ContextActionContribution } from '../../commands/ContextActionRegistry.js';
import type { EmptyStateContribution } from '../../commands/EmptyStateRegistry.js';
import type { KeybindingEntry } from '../../commands/KeybindingRegistry.js';
import type { TemplateRegistration } from '../../commands/TemplateCatalog.js';
import type { WalkthroughContribution } from '../../commands/WalkthroughRegistry.js';
import { registerAction, unregisterAction } from '../actions/index.js';
import {
  registerProjector,
  unregisterProjector,
} from '../evaluationContext/index.js';
import {
  registerXUiRenderer,
  unregisterXUiRenderer,
} from '../../renderers/controls/XUiRendererControl.js';
import {
  registerStatusBarItem,
  unregisterStatusBarItem,
} from '../../commands/StatusBarRegistry.js';
import {
  registerInspectorTab,
  unregisterInspectorTab,
} from '../../commands/InspectorTabRegistry.js';
import {
  registerContextAction,
  unregisterContextAction,
} from '../../commands/ContextActionRegistry.js';
import {
  registerEmptyState,
  unregisterEmptyState,
} from '../../commands/EmptyStateRegistry.js';
import {
  registerKeybinding,
  unregisterKeybinding,
} from '../../commands/KeybindingRegistry.js';
import {
  registerTemplate,
  unregisterTemplate,
} from '../../commands/TemplateCatalog.js';
import {
  registerWalkthrough,
  unregisterWalkthrough,
} from '../../commands/WalkthroughRegistry.js';

/**
 * The declarative contribution shape. Every field is optional; a
 * manifest may carry one entry or many. The installer iterates
 * declared entries and routes each through the appropriate
 * per-registry register function, stamping the manifest's Provenance.
 *
 * Per §13.2.3: this is the union of contribution kinds main supports
 * today. Each kind's entry type is the per-registry contribution
 * interface MINUS the per-entry `provenance` field (the manifest's
 * provenance is shared).
 *
 * NOT included today (no main registry):
 *   - settingsPanels — main mounts settings inline; no SettingsPanelRegistry
 *   - layouts — main declares layouts inline; no LayoutRegistry
 */
export interface ContributionEntries {
  /** Action contributions (§3.C addressable behaviors). */
  readonly actions?: ReadonlyArray<Omit<Action, 'provenance'>>;
  /** EvaluationContext projectors (§13.2.1). */
  readonly factsProjectors?: ReadonlyArray<{
    readonly kind: NonNullable<AddressableKind>;
    readonly project: Projector;
  }>;
  /** Form / x-ui-renderer renderers (§13.3.1). */
  readonly renderers?: ReadonlyArray<{
    readonly hint: string;
    readonly mountTag: string;
  }>;
  /** Status-bar items. */
  readonly statusBarItems?: ReadonlyArray<
    Omit<StatusBarItem, 'provenance' | 'source'>
  >;
  /** Inspector tabs. */
  readonly inspectorTabs?: ReadonlyArray<
    Omit<InspectorTabContribution, 'provenance' | 'source'>
  >;
  /** Context-menu actions. */
  readonly contextActions?: ReadonlyArray<
    Omit<ContextActionContribution, 'provenance' | 'source'>
  >;
  /** Empty-state renderers (e.g., palette-no-results). */
  readonly emptyStates?: ReadonlyArray<
    Omit<EmptyStateContribution, 'provenance' | 'source'>
  >;
  /** Keybindings. */
  readonly keybindings?: ReadonlyArray<
    Omit<KeybindingEntry, 'provenance' | 'source'>
  >;
  /** Command-palette templates. */
  readonly templates?: ReadonlyArray<
    Omit<TemplateRegistration, 'provenance' | 'source' | 'trustTier'>
  >;
  /** Walkthroughs. */
  readonly walkthroughs?: ReadonlyArray<
    Omit<WalkthroughContribution, 'provenance' | 'source'>
  >;
  /**
   * §25.γ5 — per-operation declarative inverses (§13.7 q.9 / §15.3 γ5).
   * Map from operation id → the inverse Effect to dispatch when undoing.
   * Lets plugins ship undo behavior without waiting for the Java-side
   * `Operation.policy.inverse?: Effect` wire extension. The effects
   * substrate consults this on `invoke-operation` deriveInverse —
   * preferring the manifest-declared inverse over the placeholder
   * toast acknowledgement.
   */
  readonly effectInverses?: Readonly<Record<string, import('../effect.js').Effect>>;
}

/**
 * The ContributionManifest itself. Generalizes 521's PluginContribution
 * with the additions named in §13.2.3.
 */
export interface ContributionManifest {
  /** Stable contributor id (e.g., 'core.demo', plugin id). */
  readonly id: string;
  /** Semver-shaped or opaque version string. */
  readonly version: string;
  /** Uniform attribution (§3.A). */
  readonly provenance: Provenance;
  /**
   * Per §13.2.3 — capability declaration. Future capability tokens
   * land here; today the field is informational + manifest-
   * introspection.
   */
  readonly capabilities?: readonly string[];
  /**
   * Per §13.2.3 — other manifest ids this manifest depends on.
   * Install order is dependency-first; missing dependencies fail
   * install.
   */
  readonly dependencies?: readonly string[];
  /**
   * Per §13.2.3 — 'global' (always active when installed) or
   * 'profile-scoped' (active only when the active Workspace Profile
   * lists this manifest's id; consumed by Slice 10). Defaults to
   * 'global'.
   */
  readonly profileBinding?: 'global' | 'profile-scoped';
  /** The contribution entries themselves. */
  readonly contributes: ContributionEntries;
  /**
   * Per §13.2.3 — lifecycle hooks distinct from install/uninstall.
   * `activate` runs after install when the manifest becomes enabled
   * (e.g. entering a profile that requires it). `deactivate` runs
   * before uninstall or before leaving such a profile. First-party
   * always-on manifests typically omit these.
   */
  readonly lifecycle?: {
    readonly activate?: () => void | Promise<void>;
    readonly deactivate?: () => void | Promise<void>;
  };
}

/** Tracks installed manifests so uninstall can iterate the right entries. */
interface InstalledManifest {
  readonly manifest: ContributionManifest;
  readonly registeredActions: string[];
  readonly registeredProjectors: NonNullable<AddressableKind>[];
  readonly registeredRenderers: string[];
  readonly registeredStatusBarItems: string[];
  readonly registeredInspectorTabs: string[];
  readonly registeredContextActions: string[];
  readonly registeredEmptyStates: string[];
  readonly registeredKeybindings: Array<{ key: string; commandId: string }>;
  readonly registeredTemplates: string[];
  readonly registeredWalkthroughs: string[];
  /** §25.γ5 — operationIds added to the inverse registry; uninstall removes them. */
  readonly registeredEffectInverses: string[];
}

// §25.γ5 — global operationId → inverse Effect lookup. The effects
// substrate consults this for `invoke-operation` deriveInverse.
// Manifest install populates; uninstall clears.
const _effectInverses = new Map<string, import('../effect.js').Effect>();

export function lookupEffectInverse(
  operationId: string,
): import('../effect.js').Effect | undefined {
  return _effectInverses.get(operationId);
}

/** Test-only: clear declarative inverses. */
export function __resetEffectInversesForTest(): void {
  _effectInverses.clear();
}

// §25.γ5 — install the lookup at module load so the effects substrate
// can consult it on invoke-operation deriveInverse.
setEffectInverseLookup((operationId) => _effectInverses.get(operationId));

const _installed = new Map<string, InstalledManifest>();
const _listeners = new Set<() => void>();

function notify(): void {
  for (const l of _listeners) {
    try {
      l();
    } catch {
      /* swallow */
    }
  }
}

/**
 * Install a ContributionManifest into the kernel registries. Atomic:
 * if any entry fails (e.g. duplicate id), partial registrations are
 * rolled back before the error propagates.
 *
 * Per §13.2.3: this is the first-party path. Plugin manifests
 * continue to flow through `PluginRegistry.install()` for now;
 * unifying the two is a follow-up.
 */
export async function installContributionManifest(
  manifest: ContributionManifest,
): Promise<void> {
  if (_installed.has(manifest.id)) {
    throw new Error(
      `ContributionManifest already installed: ${manifest.id}`,
    );
  }
  for (const depId of manifest.dependencies ?? []) {
    if (!_installed.has(depId)) {
      throw new Error(
        `ContributionManifest ${manifest.id} depends on missing ${depId}`,
      );
    }
  }
  // §25.α7 — stamp installedAt at install-site. Replaces the manifest's
  // Provenance with a stamped copy so every downstream registration
  // (registerAction, registerStatusBarItem, etc.) receives a Provenance
  // whose installedAt reflects this install moment, not a stale value
  // from `makePluginProvenance` helper invocation.
  manifest = { ...manifest, provenance: stampInstalledAt(manifest.provenance) };
  // Tempdoc 543 §3.A — derive legacy `source` from manifest tier.
  // First-party manifests register entries as 'core' so consumers
  // gating on source === 'core' (StatusDeck plugin-row filter,
  // InspectorPane core/plugin split, TemplateCatalog binding-source
  // attenuation) classify them correctly. Plugins register as
  // 'plugin'. Reviewer follow-up addressed in-slice.
  const legacySource: 'core' | 'plugin' =
    manifest.provenance.tier === 'CORE' ? 'core' : 'plugin';
  const tracked: InstalledManifest = {
    manifest,
    registeredActions: [],
    registeredProjectors: [],
    registeredRenderers: [],
    registeredStatusBarItems: [],
    registeredInspectorTabs: [],
    registeredContextActions: [],
    registeredEmptyStates: [],
    registeredKeybindings: [],
    registeredTemplates: [],
    registeredWalkthroughs: [],
    registeredEffectInverses: [],
  };
  // §25.γ3 — auto-register profile-scoped manifests as Profile
  // factories without callers needing the separate
  // registerProfileScopedManifestFactory API. The factory returns the
  // same manifest object since profile activation only runs the
  // factory after uninstall removes the prior install record.
  if (manifest.profileBinding === 'profile-scoped') {
    // Synchronous call into the leaf factory registry (cycle break,
    // tempdoc 530 UI-cycle gate). Previously a deferred dynamic
    // import('../profiles') — the manifest→profiles edge of the cycle.
    registerProfileScopedManifestFactory(manifest.id, () => manifest);
  }
  // §25.γ2 — table-driven registration coordinator. Adding an 11th
  // contribution kind now requires one COORDINATORS row instead of
  // duplicating the per-kind loop + per-kind rollback branch.
  try {
    for (const coord of COORDINATORS) {
      coord.install(manifest, legacySource, tracked);
    }
  } catch (err) {
    rollbackTracked(tracked);
    throw err;
  }
  _installed.set(manifest.id, tracked);
  if (manifest.lifecycle?.activate) {
    try {
      await manifest.lifecycle.activate();
    } catch (err) {
      _installed.delete(manifest.id);
      rollbackTracked(tracked);
      throw err;
    }
  }
  notify();
}

// §25.γ2 — table-driven contribution-kind coordinator.
//
// Each row encodes ONE contribution kind's install + rollback logic.
// Adding an 11th kind is one row, not a per-kind loop + per-kind
// rollback branch. The CONTRIBUTION_KINDS array drives both install
// (top of installContributionManifest) and rollback (rollbackTracked
// below).
//
// Each row is keyed by the InstalledManifest field it appends to;
// the install closure receives (manifest, legacySource, tracked) and
// is responsible for both calling the underlying register* AND
// recording an entry in tracked. The rollback closure receives the
// recorded ids and calls the matching unregister* with swallow.
interface ContributionKindCoordinator {
  readonly kind: string;
  readonly install: (
    manifest: ContributionManifest,
    legacySource: 'core' | 'plugin',
    tracked: InstalledManifest,
  ) => void;
  readonly rollback: (tracked: InstalledManifest) => void;
}

function swallow<T>(fn: () => T): void {
  try {
    fn();
  } catch {
    /* swallow */
  }
}

const COORDINATORS: ReadonlyArray<ContributionKindCoordinator> = [
  {
    kind: 'actions',
    install: (m, _, t) => {
      for (const action of m.contributes.actions ?? []) {
        registerAction({ ...action, provenance: m.provenance });
        t.registeredActions.push(action.id);
      }
    },
    rollback: (t) => {
      for (const id of t.registeredActions) swallow(() => unregisterAction(id));
    },
  },
  {
    kind: 'factsProjectors',
    install: (m, _, t) => {
      for (const proj of m.contributes.factsProjectors ?? []) {
        registerProjector(proj.kind, proj.project);
        t.registeredProjectors.push(proj.kind);
      }
    },
    rollback: (t) => {
      for (const k of t.registeredProjectors) swallow(() => unregisterProjector(k));
    },
  },
  {
    kind: 'renderers',
    install: (m, _, t) => {
      for (const r of m.contributes.renderers ?? []) {
        registerXUiRenderer(r.hint, r.mountTag);
        t.registeredRenderers.push(r.hint);
      }
    },
    rollback: (t) => {
      for (const h of t.registeredRenderers) swallow(() => unregisterXUiRenderer(h));
    },
  },
  {
    kind: 'statusBarItems',
    install: (m, source, t) => {
      for (const item of m.contributes.statusBarItems ?? []) {
        registerStatusBarItem({
          ...item,
          source,
          provenance: m.provenance,
        });
        t.registeredStatusBarItems.push(item.id);
      }
    },
    rollback: (t) => {
      for (const id of t.registeredStatusBarItems)
        swallow(() => unregisterStatusBarItem(id));
    },
  },
  {
    kind: 'inspectorTabs',
    install: (m, source, t) => {
      for (const tab of m.contributes.inspectorTabs ?? []) {
        registerInspectorTab({
          ...tab,
          source,
          provenance: m.provenance,
        });
        t.registeredInspectorTabs.push(tab.id);
      }
    },
    rollback: (t) => {
      for (const id of t.registeredInspectorTabs)
        swallow(() => unregisterInspectorTab(id));
    },
  },
  {
    kind: 'contextActions',
    install: (m, source, t) => {
      for (const ca of m.contributes.contextActions ?? []) {
        registerContextAction({
          ...ca,
          source,
          provenance: m.provenance,
        });
        t.registeredContextActions.push(ca.id);
      }
    },
    rollback: (t) => {
      for (const id of t.registeredContextActions)
        swallow(() => unregisterContextAction(id));
    },
  },
  {
    kind: 'emptyStates',
    install: (m, source, t) => {
      for (const es of m.contributes.emptyStates ?? []) {
        registerEmptyState({
          ...es,
          source,
          provenance: m.provenance,
        });
        t.registeredEmptyStates.push(es.id);
      }
    },
    rollback: (t) => {
      for (const id of t.registeredEmptyStates)
        swallow(() => unregisterEmptyState(id));
    },
  },
  {
    kind: 'keybindings',
    install: (m, _, t) => {
      for (const kb of m.contributes.keybindings ?? []) {
        // KeybindingEntry.source enum is 'default' | 'user' | 'plugin'
        // — no 'core'. Map CORE manifests to 'default'; plugins to
        // 'plugin'.
        registerKeybinding({
          ...kb,
          source: m.provenance.tier === 'CORE' ? 'default' : 'plugin',
          provenance: m.provenance,
        });
        t.registeredKeybindings.push({ key: kb.key, commandId: kb.commandId });
      }
    },
    rollback: (t) => {
      for (const kb of t.registeredKeybindings)
        swallow(() => unregisterKeybinding(kb.key, kb.commandId));
    },
  },
  {
    kind: 'templates',
    install: (m, source, t) => {
      for (const tmpl of m.contributes.templates ?? []) {
        registerTemplate({
          ...tmpl,
          source,
          provenance: m.provenance,
        });
        t.registeredTemplates.push(tmpl.id);
      }
    },
    rollback: (t) => {
      for (const id of t.registeredTemplates)
        swallow(() => unregisterTemplate(id));
    },
  },
  {
    kind: 'walkthroughs',
    install: (m, source, t) => {
      for (const wt of m.contributes.walkthroughs ?? []) {
        registerWalkthrough({
          ...wt,
          source,
          provenance: m.provenance,
        });
        t.registeredWalkthroughs.push(wt.id);
      }
    },
    rollback: (t) => {
      for (const id of t.registeredWalkthroughs)
        swallow(() => unregisterWalkthrough(id));
    },
  },
  {
    // §25.γ5 — manifest-declared per-operation inverses. effects/
    // substrate consults _effectInverses on invoke-operation
    // deriveInverse.
    kind: 'effectInverses',
    install: (m, _, t) => {
      for (const [opId, inverse] of Object.entries(m.contributes.effectInverses ?? {})) {
        _effectInverses.set(opId, inverse);
        t.registeredEffectInverses.push(opId);
      }
    },
    rollback: (t) => {
      for (const opId of t.registeredEffectInverses)
        swallow(() => _effectInverses.delete(opId));
    },
  },
];

function rollbackTracked(tracked: InstalledManifest): void {
  // Iterate in reverse install order so dependent unregisters happen
  // before their dependencies (e.g., keybindings before their command
  // references go).
  for (let i = COORDINATORS.length - 1; i >= 0; i--) {
    COORDINATORS[i]!.rollback(tracked);
  }
}

/**
 * Uninstall a previously-installed manifest. Calls deactivate hook
 * first; deactivate failures are swallowed (do not block uninstall).
 */
export async function uninstallContributionManifest(
  id: string,
): Promise<boolean> {
  const tracked = _installed.get(id);
  if (!tracked) return false;
  if (tracked.manifest.lifecycle?.deactivate) {
    try {
      await tracked.manifest.lifecycle.deactivate();
    } catch {
      /* swallow */
    }
  }
  rollbackTracked(tracked);
  _installed.delete(id);
  notify();
  return true;
}

/** List installed manifests. */
export function listInstalledManifests(): readonly ContributionManifest[] {
  return Array.from(_installed.values()).map((t) => t.manifest);
}

/** Snapshot for diagnostics. */
export function getInstalledManifest(
  id: string,
): ContributionManifest | undefined {
  return _installed.get(id)?.manifest;
}

/** Subscribe to install/uninstall events. */
export function subscribeManifests(listener: () => void): () => void {
  _listeners.add(listener);
  return () => {
    _listeners.delete(listener);
  };
}

/** Test-only reset. */
export async function __resetManifestRegistryForTest(): Promise<void> {
  const ids = Array.from(_installed.keys());
  for (const id of ids) {
    await uninstallContributionManifest(id);
  }
  _installed.clear();
  _listeners.clear();
}
