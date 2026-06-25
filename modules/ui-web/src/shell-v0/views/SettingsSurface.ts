// SPDX-License-Identifier: Apache-2.0
/**
 * SettingsSurface — Lit-side Settings rail surface (slice 454 phase 9).
 *
 * Self-mounting Surface with full functional parity to React SettingsView:
 * Interface (mode), Appearance (theme + high-contrast), Keyboard (default
 * action), Desktop autostart (Tauri-only), Reset to defaults via
 * `core.reset-settings`, Delete all data (Tauri-only, dangerous).
 *
 * Persists settings via POST /api/settings/v2 (matches Library + Brain
 * patterns). Reset routes through OperationClient.
 *
 * Side-effect registers `<jf-settings-surface>` for the chrome dispatcher.
 *
 * Note: this is a NEW self-mounting surface, distinct from the existing
 * `<jf-settings-view>` (slice 3a-2-b) which was parent-data-driven. The
 * old element remains for the React coexistence path; the new
 * `<jf-settings-surface>` is the phase-9 promotion target.
 */

import { html, css, nothing, type TemplateResult, type PropertyValues } from 'lit';
import { JfElement } from '../primitives/JfElement.js';
// Tempdoc 511 §511-followup-B: reset-settings now routes through
// `<jf-operation>` → ActionButton → OperationClient internally; the
// surface no longer needs a direct OperationClient handle. Tauri-only
// affordances (autostart, delete-all-data) now route through
// host_.platform.capabilities so the legacy isTauriRuntime import was
// dropped as part of the surface migration.
import { icon } from '../components/Icon.js';
// §2.A: rail-customization labels resolve through the one surface-label
// authority — never the raw `core.*-surface` id.
import { present } from '../display/present.js';
import { applyAppearance, getSurfaceMode, setSurfaceMode } from '../state/themeState.js';
import { setUiMode } from '../state/uiModeState.js';
// 569 Move 1/3 — the body-tier apply path: a real region rendered from a declaration.
import {
  subscribePresentation,
  activeBodyFor,
  activeInteractionFor,
  type ActivePresentation,
} from '../state/presentationRuntime.js';
import {
  SETTINGS_INTERFACE_REGION,
  APPEARANCE_FLOW,
  CONFIRM_CEREMONY,
} from '../themes/builtinPresentations.js';
// 569 §14 — run the appearance behaviour as a declared statechart (Move 8 operative).
import { createMachine, type InteractionMachine } from '../substrates/interaction/index.js';
import { auditAndQuarantine } from '../state/runtimeConformance.js';
// 569 §19 Phase 6 — the presentation AUTHORING UI (paste-JSON / generate / fork) moved out of
// Settings into the dedicated `jf-presentation-editor-surface`; Settings keeps only the declared
// Interface-region render (the Move-3 keystone projection), not the authoring affordances.
import { applyAdaptationProfile, getAdaptationProfile } from '../state/adaptationProfile.js';
import '../components/DeclaredSurface.js';
import type { SurfaceChangeEventDetail } from '../components/DeclaredSurface.js';
// Tempdoc 543 §20.7 B1 — schema-driven form via x-ui-renderer dispatch.
import '../components/Form.js';
import '../components/AutonomyDial.js';
import '../components/StatusBadge.js';
import '../components/Button.js';
import '../components/ErrorAlert.js';
import type { FormChangeEventDetail } from '../components/Form.js';
import type { PluginHostApi } from '../plugin-api/plugin-types.js';
import { listLayouts } from '../layout/LayoutManifest.js';
import {
  listAvailableThemes,
  type ThemeCatalogEntry,
} from '../themes/themesCatalog.js';
import {
  listSurfaces,
  getSurface,
  removePluginSurfaceContributions,
} from '../../api/registry/SurfaceCatalogClient.js';
// Tempdoc 571 §11 / 578 — Settings ⊇ Appearance: Settings hosts the theming surfaces as tabs.
import '../components/SurfaceTabs.js';
import type { SurfaceTabItem } from '../components/SurfaceTabs.js';
import { takeMemberTabIntent, subscribeMemberTab } from '../router/memberTabIntent.js';
import type { Surface } from '../../api/types/surface.js';
import type { RendererUserConfig } from '../renderers/userConfig.js';
import {
  getSessionPluginRegistry,
  pluginDeclaration,
  type InstalledPlugin,
} from '../plugin-api/index.js';
import {
  getViewerAudience,
  setViewerAudience,
  subscribeViewerAudience,
} from '../state/viewerAudienceState.js';
// Allowlisted in eslint.config.js — see 511-followup-B. The audience
// toggle UI needs the `Audience` union directly for radio-button
// rendering; mounting an aggregate component would be a worse fit.
import type { Audience } from '../../api/types/registry.js';
// Tempdoc 511 §511-followup-B: core.reset-settings mounted via the
// canonical (Operation, button) cell. The wire policy (RiskTier.MEDIUM,
// ConfirmStrategy.Inline, Audience.OPERATOR) drives ceremony — the
// confirm modal + manual `client.invoke` here are replaced.
import '../aggregate-substrate/components/JfOperation.js';
import type { OpErrorEventDetail } from '../components/OpButton.js';

interface UISettings {
  mode?: 'simple' | 'advanced';
  theme?: 'system' | 'dark' | 'light';
  highContrast?: boolean;
  defaultAction?: 'open' | 'reveal' | 'preview';
  vimMode?: boolean;
  pauseIndexingDuringAi?: boolean;
}

interface AllSettings {
  ui?: UISettings;
  settingsMode?: string;
}

export class SettingsSurface extends JfElement {
  static properties = {
    apiBase: { attribute: 'api-base', type: String },
    host_: { attribute: false },
    ui: { state: true },
    readOnly: { state: true },
    saving: { state: true },
    autostart: { state: true },
    autostartLoaded: { state: true },
    error: { state: true },
    deleting: { state: true },
    // 569 §15 (Move 8) — the delete ceremony's declared statechart state + the typed-confirm input.
    deleteState: { state: true },
    confirmText: { state: true },
    // Slice 477 H1 — V1.5 user-authorship state
    activeThemeId: { state: true },
    userConfig: { state: true },
    railSurfaces: { state: true },
    activeTab: { state: true },
    plugins: { state: true },
    // Tempdoc 560 §28 — URL-loaded plugins that came back UNTRUSTED, keyed by id → source url,
    // so the operator can approve-and-trust them (fetch + hash + reload as TRUSTED on approval).
    untrustedLoads: { state: true },
    // Tempdoc 560 §28 (4d) — the operator's durable allow-always grants (operation + family).
    durableGrants: { state: true },
    // Tempdoc 560 §28 Phase 3 — the run-tier witness: live composed contributions (read-only).
    witnessEntries: { state: true },
    // Tempdoc 511-followup Track A
    viewerAudience: { state: true },
    // Tempdoc 543 §20.7 B6 — WorkspaceProfile registry snapshot for
    // the developer affordance.
    workspaceProfiles: { state: true },
    // §25.ζ#4 — selected parent profile id for the new-profile snapshot
    // affordance. Empty string means "flat profile, no inheritance".
    snapshotInheritsFrom: { state: true },
    // §28.W3 — recorded consent grants for the plugin-permissions panel.
    consents: { state: true },
    // 569 Move 1/3 — active presentation (drives which regions render from a declaration).
    presentation: { state: true },
    // Tempdoc 567 §8 (deferred → built) — custom-theme management drafts (host-owned, editor-independent):
    // the import paste-box visibility + JSON draft, and the inline-rename target id + label draft.
    themeImporting: { state: true },
    themeImportDraft: { state: true },
    renamingThemeId: { state: true },
    renameDraft: { state: true },
    // Tempdoc 567 §9.4 — the glass/solid surface-mode toggle (FE-only pref, mirrors high-contrast).
    surfaceMode: { state: true },
  };

  declare apiBase: string;
  declare host_: PluginHostApi;
  declare ui: UISettings;
  declare readOnly: boolean;
  declare saving: boolean;
  declare autostart: boolean | null;
  declare autostartLoaded: boolean;
  declare error: string | null;
  declare deleting: boolean;
  // 569 §15 (Move 8) — the BRANCHING delete-confirm ceremony, run as a declared statechart.
  declare deleteState: string;
  declare confirmText: string;
  // Slice 477 H1 — V1.5 user-authorship state
  declare activeThemeId: string | null;
  // Tempdoc 567 §8 (deferred → built) — custom-theme import/rename drafts.
  declare themeImporting: boolean;
  declare themeImportDraft: string;
  declare renamingThemeId: string | null;
  declare renameDraft: string;
  declare surfaceMode: 'glass' | 'solid';
  declare userConfig: RendererUserConfig;
  declare railSurfaces: Surface[];
  /** Active composition tab id: 'preferences' (own body) or a member surface id. */
  declare activeTab: string;
  declare plugins: InstalledPlugin[];
  // Tempdoc 560 §28 — pending operator-approval candidates (URL-loaded + UNTRUSTED), id → source url.
  declare untrustedLoads: Map<string, string>;
  // Tempdoc 560 §28 (4d) — durable allow-always grants the trust gate honors (operation + family).
  declare durableGrants: ReadonlyArray<{ kind: string; target: string; sourceTier: string }>;
  // Tempdoc 560 §28 Phase 3 — run-tier witness rows (kind/id/owner/buildWitnessed) from the live registry.
  declare witnessEntries: ReadonlyArray<{
    kind: string;
    id: string;
    owner: string | null;
    buildWitnessed: boolean;
  }>;
  // Tempdoc 511-followup Track A
  declare viewerAudience: Audience;
  // Tempdoc 543 §20.7 B6 — developer affordance: cached list of
  // WorkspaceProfile registry entries for the dev switcher.
  declare workspaceProfiles: ReadonlyArray<{ id: string; label: string }>;
  declare snapshotInheritsFrom: string;
  declare consents: ReadonlyArray<{
    contributorId: string;
    capability: string;
    decision: 'allow-once' | 'allow-always' | 'deny';
    decidedAt: string;
  }>;
  // 569 Move 1/3 — the active body/layout presentation tiers.
  declare presentation: ActivePresentation;
  private consentUnsub: (() => void) | null = null;
  private presentationUnsub: (() => void) | null = null;
  // 569 Move 6 — guard so the apply-time runtime audit runs once per applied presentation.
  private lastAuditedPresentationId: string | null = null;

  // Slice 477 H1 — subscription cleanup handles
  private themeUnsub: (() => void) | null = null;
  // 569 §14 — save-settings Effect listener (the appearance statechart persists through it).
  private saveSettingsListener: ((e: Event) => void) | null = null;
  // 569 §14 — the appearance behaviour, run as a declared statechart through the gated dispatcher.
  private appearanceMachine: InteractionMachine | null = null;
  // 569 §15 — the BRANCHING delete-confirm ceremony (CONFIRM_CEREMONY), run as a declared statechart.
  private deleteMachine: InteractionMachine | null = null;
  private deleteUnsub: (() => void) | null = null;
  private userConfigUnsub: (() => void) | null = null;
  private catalogUnsub: (() => void) | null = null;
  private memberTabUnsub: (() => void) | null = null;
  // Tempdoc 511-followup Track A
  private viewerAudienceUnsub: (() => void) | null = null;
  // Tempdoc 543 §20.7 B6 — WorkspaceProfile registry subscription.
  private workspaceProfilesUnsub: (() => void) | null = null;

  constructor() {
    super();
    this.apiBase = '';
    this.host_ = undefined as unknown as PluginHostApi;
    this.ui = {};
    this.readOnly = false;
    this.saving = false;
    this.autostart = null;
    this.autostartLoaded = false;
    this.error = null;
    this.deleting = false;
    this.deleteState = 'idle';
    this.confirmText = '';
    // Slice 477 H1
    this.activeThemeId = null;
    // Tempdoc 567 §8 (deferred → built)
    this.themeImporting = false;
    this.themeImportDraft = '';
    this.renamingThemeId = null;
    this.renameDraft = '';
    this.surfaceMode = getSurfaceMode();
    this.userConfig = {} as RendererUserConfig;
    this.railSurfaces = SettingsSurface.railSurfacesForCustomization();
    this.activeTab = 'preferences';
    this.plugins = getSessionPluginRegistry().list();
    this.untrustedLoads = new Map();
    this.durableGrants = [];
    this.witnessEntries = [];
    // Tempdoc 511-followup Track A
    this.viewerAudience = getViewerAudience();
    // Tempdoc 543 §20.7 B6 — initialize empty; populated lazily on
    // connectedCallback (dynamic import keeps the substrate lazily-
    // loaded for non-DEVELOPER audiences).
    this.workspaceProfiles = [];
    this.snapshotInheritsFrom = '';
    this.consents = [];
    this.presentation = { id: null, bodies: {}, layout: null, interaction: {} };
  }

  static styles = [
    css`
    /* Tempdoc 571 §11 / 578 — Settings is a host surface: display:contents pass-through (layout-purity)
       delegating layout to <jf-surface-tabs>. Its own "Preferences" body scrolls inside
       .settings-scroll; the Appearance members (Skins, Editor) carry their own SurfaceLayout. */
    :host {
      display: contents;
    }
    .settings-scroll {
      height: 100%;
      overflow-y: auto;
      color: var(--text-primary);
      font-family: system-ui, -apple-system, sans-serif;
    }
    .header {
      position: sticky;
      top: 0;
      z-index: 1;
      background: var(--surface-1);
      padding: 1rem 1.5rem;
      border-bottom: 1px solid var(--border-subtle);
      display: flex;
      align-items: flex-start;
      justify-content: space-between;
      gap: 1rem;
    }
    .header h2 {
      margin: 0;
      font-size: var(--font-size-lg);
      font-weight: 600;
    }
    .header .subtitle {
      margin: 0.125rem 0 0 0;
      font-size: var(--font-size-xs);
      color: var(--text-secondary);
    }
    .body {
      padding: 1rem 1.5rem;
      display: flex;
      flex-direction: column;
      gap: 1rem;
    }
    /* 574 B2 — plugin-trust + session-only status pills are the jf-status-badge atom now;
       the per-surface .badge base/.ok/.danger fork is deleted. */
    button {
      display: inline-flex;
      align-items: center;
      gap: 0.4rem;
      background: var(--surface-primary);
      color: var(--text-primary);
      border: 1px solid var(--border-subtle);
      border-radius: 0.375rem;
      padding: 0.4rem 0.75rem;
      cursor: pointer;
      font-size: var(--font-size-sm);
    }
    button:hover:not(:disabled) {
      background: var(--surface-hover);
    }
    button:disabled {
      opacity: 0.5;
      cursor: not-allowed;
    }
    /* 574 B1 — generic action buttons are the jf-button atom now; the .primary/.danger fork
       is deleted. The base button{} + .option-btn/.card/.rail-arrow rules below stay for the
       bespoke selectable-option + card-picker affordances (a distinct pattern, not the action
       button base). */
    .section {
      padding: 1rem;
      background: var(--surface-secondary);
      border: 1px solid var(--border-subtle);
      border-radius: 0.5rem;
    }
    .section h3 {
      margin: 0 0 0.5rem 0;
      font-size: var(--font-size-xs);
      font-weight: 600;
      letter-spacing: 0.05em;
      text-transform: uppercase;
      color: var(--text-secondary);
      display: flex;
      align-items: center;
      gap: 0.4rem;
    }
    .row {
      display: flex;
      gap: 0.5rem;
      flex-wrap: wrap;
    }
    .toggle-row {
      display: flex;
      justify-content: space-between;
      align-items: center;
      padding: 0.5rem 0;
    }
    .toggle-row + .toggle-row {
      border-top: 1px solid var(--border-subtle);
    }
    .toggle-label {
      font-size: var(--font-size-sm);
    }
    .toggle-desc {
      font-size: var(--font-size-xs);
      color: var(--text-secondary);
      margin-top: 0.125rem;
    }
    .option-btn {
      flex: 1;
      display: flex;
      flex-direction: column;
      align-items: center;
      padding: 0.75rem;
      border: 1px solid var(--border-subtle);
      border-radius: 0.375rem;
      background: transparent;
      color: var(--text-primary);
      cursor: pointer;
    }
    .option-btn:hover:not(:disabled) {
      background: var(--surface-hover);
    }
    .option-btn.selected {
      border-color: var(--accent-tint);
      background: var(--accent-tint-08);
      color: var(--text-tint);
    }
    .option-label {
      font-size: var(--font-size-sm);
      font-weight: 500;
      margin-top: 0.25rem;
    }
    .option-desc {
      font-size: var(--font-size-xs);
      color: var(--text-secondary);
      margin-top: 0.125rem;
    }
    .custom-theme {
      flex: 1;
      position: relative;
      display: flex;
    }
    .custom-theme .option-btn {
      flex: 1;
    }
    /* 574 B1 — the delete control is a jf-button(ghost,icon); this class now only
       overlay-positions it over the palette swatch (the skin is the atom's). */
    .custom-theme-del {
      position: absolute;
      top: 0.25rem;
      right: 0.25rem;
    }
    /* Tempdoc 567 §8 (deferred → built) — rename control sits just left of delete. */
    .custom-theme-rename {
      position: absolute;
      top: 0.25rem;
      right: 1.75rem;
    }
    /* Inline-rename row replaces the swatch while a custom theme is being renamed. */
    .custom-theme-renaming {
      align-items: center;
      gap: 0.25rem;
      padding: 0.5rem;
      border: 1px solid var(--border-subtle);
      border-radius: 0.375rem;
    }
    .theme-rename-input {
      flex: 1;
      min-width: 0;
      padding: 0.375rem 0.5rem;
      background: var(--surface-tertiary);
      color: var(--text-primary);
      border: 1px solid var(--border-subtle);
      border-radius: 0.25rem;
      font-size: var(--font-size-sm);
    }
    /* Import-from-JSON affordance (host-owned, editor-independent). */
    .theme-import {
      margin-top: 0.5rem;
      display: flex;
      flex-direction: column;
      gap: 0.375rem;
    }
    .theme-import-json {
      width: 100%;
      box-sizing: border-box;
      padding: 0.5rem;
      background: var(--surface-tertiary);
      color: var(--text-primary);
      border: 1px solid var(--border-subtle);
      border-radius: 0.375rem;
      font-family: var(--font-mono);
      font-size: var(--font-size-xs);
      resize: vertical;
    }
    .theme-import-actions {
      display: flex;
      gap: 0.5rem;
    }
    .theme-import-toggle {
      margin-top: 0.5rem;
    }
    select {
      padding: 0.375rem 0.5rem;
      background: var(--surface-tertiary);
      border: 1px solid var(--border-subtle);
      border-radius: 0.25rem;
      color: var(--text-primary);
      font-size: var(--font-size-sm);
    }
    .switch {
      width: 2.5rem;
      height: 1.25rem;
      border-radius: 9999px;
      background: var(--surface-tertiary);
      border: 1px solid var(--border-subtle);
      position: relative;
      cursor: pointer;
      transition: background var(--duration-fast) var(--ease-standard);
    }
    .switch::after {
      content: '';
      position: absolute;
      top: 1px;
      left: 1px;
      width: 1rem;
      height: 1rem;
      border-radius: 50%;
      background: var(--text-secondary);
      transition: left var(--duration-fast) var(--ease-standard), background var(--duration-fast) var(--ease-standard);
    }
    .switch.on {
      background: var(--accent-tint);
      border-color: var(--accent-tint);
    }
    .switch.on::after {
      left: 1.25rem;
      background: white;
    }
    p.help {
      margin: 0.5rem 0 0 0;
      font-size: var(--font-size-xs);
      color: var(--text-secondary);
      line-height: 1.5;
    }
    /* Slice 477 H1 — V1.5 user-authorship sections */
    .rail-list,
    .plugin-list {
      list-style: none;
      padding: 0;
      margin: 0;
      display: flex;
      flex-direction: column;
      gap: 0.25rem;
    }
    .rail-row {
      display: grid;
      grid-template-columns: auto 1fr auto auto;
      align-items: center;
      gap: 0.5rem;
      padding: 0.375rem 0.5rem;
      border: 1px solid transparent;
      border-radius: 0.25rem;
    }
    .rail-row:hover {
      border-color: var(--border-subtle);
      background: var(--surface-hover);
    }
    .rail-label {
      font-size: var(--font-size-sm);
      font-family: ui-monospace, monospace;
    }
    /* 574 B1 — the reorder arrows are jf-button(ghost,icon); their skin is the atom's. The
       .rail-arrow class is retained on the element only as a query hook (no skin rules). */
    .plugin-row {
      display: flex;
      gap: 0.75rem;
      padding: 0.5rem 0.75rem;
      border: 1px solid var(--border-subtle);
      border-radius: 0.375rem;
      align-items: flex-start;
      justify-content: space-between;
    }
    .plugin-meta {
      flex: 1;
      min-width: 0;
    }
    .plugin-id {
      display: flex;
      align-items: center;
      gap: 0.5rem;
      font-family: ui-monospace, monospace;
      font-size: var(--font-size-sm);
    }
    .plugin-version {
      color: var(--text-secondary);
      font-size: var(--font-size-xs);
    }
    .plugin-display {
      font-size: var(--font-size-sm);
      margin-top: 0.125rem;
    }
    .plugin-extras {
      font-size: var(--font-size-xs);
      color: var(--text-secondary);
      margin-top: 0.25rem;
    }
  `,
  ];

  override connectedCallback(): void {
    super.connectedCallback();
    // Tempdoc 571 §11 / 578 — if reached via a member deep-link (Skins/Editor → redirected here),
    // open that member's Appearance tab. Drain a pending intent (mounting now) AND subscribe (member
    // deep-link while THIS host is already active still switches the tab).
    const requested = takeMemberTabIntent('core.settings-surface');
    if (requested) this.activeTab = requested;
    this.memberTabUnsub = subscribeMemberTab((hostId, memberId) => {
      if (hostId !== 'core.settings-surface') return false;
      this.activeTab = memberId;
      return true;
    });
    // 559 Authority VI (slack/fill) — the reading-column fill policy IS available
    // (SurfaceLayout's `:host([data-fill='reading'])` + `--surface-content-max-width`),
    // but Settings does NOT adopt it: full-bleed is the current default. A measured
    // live A/B (559 Appendix A, "the reading-column tradeoff") found centering only
    // a partial win — it halves the 1.8k→1k px label↔control travel but leaves a
    // still-large gap and ~43% empty side margin — so adoption is deferred pending a
    // narrower-measure / row-regroup decision. To re-enable: setAttribute('data-fill','reading').
    // Tempdoc 511 §511-followup-D: aggregate-substrate bootstrap
    // moved to module-load in `shell-v0/index.ts`. By the time this
    // callback fires, all canonical strategies are already
    // registered.
    void this.loadSettings();
    if (this.host_.platform.capabilities.has('native-notifications')) {
      void this.loadAutostart();
    } else {
      this.autostartLoaded = true;
    }
    this.themeUnsub = this.host_.theme.subscribeActiveTheme((id) => {
      this.activeThemeId = id;
    });
    this.userConfigUnsub = this.host_.layout.subscribeUserConfig((cfg) => {
      this.userConfig = cfg as unknown as RendererUserConfig;
    });
    // Tempdoc 511-followup Track A — viewer-audience store
    this.viewerAudienceUnsub = subscribeViewerAudience((a) => {
      this.viewerAudience = a;
    });
    // 569 Move 1/3 — track the active presentation so the Interface region re-renders
    // through the engine when a declaration is applied (and reverts when cleared).
    this.presentationUnsub = subscribePresentation((p) => {
      this.presentation = p;
    });
    this.catalogUnsub = this.host_.layout.onSurfaceCatalogChange(() => {
      this.railSurfaces = SettingsSurface.railSurfacesForCustomization();
      this.plugins = getSessionPluginRegistry().list();
    });
    // Tempdoc 543 §20.7 B6 — populate WorkspaceProfile list lazily.
    void import('../substrates/profiles/index.js').then(
      ({ listProfiles: listWorkspaceProfiles, subscribeProfiles }) => {
        this.workspaceProfiles = [...listWorkspaceProfiles()];
        // Refresh on registry changes.
        const unsub = subscribeProfiles(() => {
          this.workspaceProfiles = [...listWorkspaceProfiles()];
        });
        // Stash for disconnect — store on the instance for teardown.
        this.workspaceProfilesUnsub = unsub;
      },
    );
    // §28.W3 — populate consent grants list lazily + subscribe to changes.
    void import('../substrates/consent/index.js').then(
      ({ listAllConsents, subscribeConsent }) => {
        this.consents = [...listAllConsents()];
        this.consentUnsub = subscribeConsent(() => {
          this.consents = [...listAllConsents()];
        });
      },
    );
    // 569 §14 — save-settings Effect listener: the appearance statechart (Move 8)
    // persists through this rather than calling fetch imperatively. Carries the
    // POST body verbatim ({ ui: {...} }) so the surface owns the endpoint/shape.
    this.saveSettingsListener = (e: Event) => {
      const settings = (e as CustomEvent<{ settings?: Record<string, unknown> }>).detail?.settings;
      if (!settings || this.readOnly) return;
      // This surface owns the persist lifecycle the statechart's save-settings edge triggers:
      // optimistic apply already happened (set-appearance effect); persistence is best-effort.
      this.saving = true;
      void this.doFetch('/api/settings/v2', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(settings),
      })
        .catch((err: unknown) => {
          this.error = err instanceof Error ? err.message : String(err);
        })
        .finally(() => {
          this.saving = false;
        });
    };
    document.addEventListener('jf-save-settings', this.saveSettingsListener);

    // 569 §14 — build the appearance behaviour machine from the ACTIVE presentation's
    // interaction tier (a user may re-author it), falling back to the built-in flow. The
    // default gated dispatcher routes every edge-effect through the 550 trust seam +
    // journals it — so the surface's behaviour IS the declared statechart, not imperative
    // code. (SETTINGS_DECLARED is boot-applied before this surface mounts, so the active
    // tier already carries APPEARANCE_FLOW.)
    this.appearanceMachine = createMachine(
      activeInteractionFor(APPEARANCE_FLOW.id) ?? APPEARANCE_FLOW,
    );

    // 569 §15 (Move 8) — the BRANCHING delete-confirm ceremony: a multi-state, GUARDED statechart
    // (idle → confirming → done; the CONFIRM edge guarded by `typed == true`) drives a real
    // destructive flow. The surface renders the confirm state from `deleteState` and runs the
    // bespoke Tauri delete (the §7 effect body) on entering `done`; the chart's declared effect is
    // the closed `toast`, dispatched + journaled through the same gated 550 seam.
    this.deleteMachine = createMachine(
      activeInteractionFor(CONFIRM_CEREMONY.id) ?? CONFIRM_CEREMONY,
    );
    this.deleteUnsub = this.deleteMachine.subscribe((state) => {
      this.deleteState = state;
      if (state === 'done') void this.runDelete();
    });

    // Tempdoc 560 §28 (4d) — load the operator's durable allow-always grants for the panel.
    void this.loadDurableGrants();
    void this.loadWitness();
  }

  /** Tempdoc 609 — settle transient state on hide: in-flight ops, errors, and the DESTRUCTIVE
   *  delete-confirm CEREMONY (the working rule's "transient confirmations" — a half-confirmed delete must
   *  not survive a tab switch). The theme-import paste draft + inline rename draft are user DRAFT work
   *  (recoverable), so they are deliberately KEPT — resetting them would re-introduce the draft-loss 609
   *  fixes. Settings data + activeTab are also untouched. */
  protected override settleTransients(): void {
    this.saving = false;
    this.deleting = false;
    this.error = null;
    this.deleteState = 'idle';
    this.confirmText = '';
  }

  override disconnectedCallback(): void {
    super.disconnectedCallback();
    this.themeUnsub?.();
    this.userConfigUnsub?.();
    this.catalogUnsub?.();
    this.memberTabUnsub?.();
    this.memberTabUnsub = null;
    this.viewerAudienceUnsub?.();
    this.workspaceProfilesUnsub?.();
    this.consentUnsub?.();
    if (this.saveSettingsListener) {
      document.removeEventListener('jf-save-settings', this.saveSettingsListener);
      this.saveSettingsListener = null;
    }
    this.appearanceMachine = null;
    this.deleteUnsub?.();
    this.deleteUnsub = null;
    this.deleteMachine = null;
    this.presentationUnsub?.();
    this.presentationUnsub = null;
    this.lastAuditedPresentationId = null;
    this.themeUnsub = null;
    this.userConfigUnsub = null;
    this.catalogUnsub = null;
    this.viewerAudienceUnsub = null;
    this.workspaceProfilesUnsub = null;
    this.consentUnsub = null;
  }

  override updated(changed: PropertyValues): void {
    super.updated(changed);
    // 569 Move 6 — apply-time RUNTIME conformance: once per applied presentation, audit the
    // declared region's RENDERED DOM (computed-contrast oracle). A failure quarantines the region
    // to the built-in render (degrade-never-fail). No-op when no declaration drives the region.
    const body = activeBodyFor(SETTINGS_INTERFACE_REGION);
    const pid = this.presentation.id;
    if (body && pid !== this.lastAuditedPresentationId) {
      const el = this.shadowRoot?.querySelector('jf-declared-surface');
      if (el) {
        this.lastAuditedPresentationId = pid;
        auditAndQuarantine(SETTINGS_INTERFACE_REGION, el);
      }
    }
  }

  private doFetch(path: string, init?: RequestInit): Promise<Response> {
    return this.host_.data.fetch(path, {
      method: init?.method,
      headers: init?.headers as Record<string, string> | undefined,
      body: init?.body as string | undefined,
    });
  }

  private async loadSettings(): Promise<void> {
    try {
      const res = await this.doFetch('/api/settings/v2');
      if (!res.ok) return;
      const data = (await res.json()) as AllSettings;
      this.ui = data.ui ?? {};
      setUiMode(this.ui.mode); // Q8: publish to the app-wide UI-mode authority
      this.readOnly = data.settingsMode === 'in_memory';
      // §2.C: replay the persisted appearance on load (one writer) so the theme
      // survives reload and the high-contrast class is applied.
      this.applyAppearance();
    } catch (err) {
      this.error = err instanceof Error ? err.message : String(err);
    }
  }

  /**
   * §2.C / C5 — delegate to the single appearance writer in themeState (the
   * appearance authority), so the boot path and the settings-change path use
   * the exact same DOM-mutation logic and cannot drift.
   */
  private applyAppearance(): void {
    // C1: delegate to the one appearance writer (themeState). paletteId omitted —
    // the palette is changed separately via selectTheme → host → applyAppearance.
    void applyAppearance({
      theme: this.ui.theme,
      highContrast: this.ui.highContrast === true,
    });
  }

  private async loadAutostart(): Promise<void> {
    try {
      const mod = await import('@tauri-apps/plugin-autostart');
      this.autostart = await mod.isEnabled();
    } catch {
      // Plugin not available — leave null
    } finally {
      this.autostartLoaded = true;
    }
  }

  /**
   * 569 §14 — an appearance change now RUNS THROUGH the declared APPEARANCE_FLOW statechart
   * (Move 8 made operative): the local reactive view updates optimistically (button highlight),
   * then the change is sent as named EVENTS into the machine, which dispatches the closed Effect
   * v3 kinds through the gated + journaled 550 seam — `set-appearance` / `set-ui-mode` (the
   * optimistic apply, via the Shell listeners) and `save-settings` (the persist, via this
   * surface's listener, which owns `saving`/`error`). The imperative applyAppearance / setUiMode /
   * fetch this method used to call are now those effects' host handlers, so the surface's
   * BEHAVIOUR is the statechart, not hand-wired code. Both render paths (the hand-authored
   * buttons and the declared option-buttons) funnel here, so both are statechart-driven.
   */
  private patch(updates: Partial<UISettings>): void {
    if (this.readOnly) return;
    this.ui = { ...this.ui, ...updates };
    const m = this.appearanceMachine;
    if (!m) return;
    if (updates.theme !== undefined) {
      m.send(`THEME_${String(updates.theme).toUpperCase()}`);
    }
    if (updates.highContrast !== undefined) {
      m.send(updates.highContrast ? 'HC_ON' : 'HC_OFF');
    }
    if (updates.mode !== undefined) {
      m.send(updates.mode === 'advanced' ? 'MODE_ADVANCED' : 'MODE_SIMPLE');
    }
  }

  private async toggleAutostart(): Promise<void> {
    if (!this.host_.platform.capabilities.has('native-notifications') || this.autostart === null) return;
    const next = !this.autostart;
    this.autostart = next;
    try {
      const mod = await import('@tauri-apps/plugin-autostart');
      if (next) await mod.enable();
      else await mod.disable();
    } catch (err) {
      this.autostart = !next;
      this.error = err instanceof Error ? err.message : String(err);
    }
  }

  /**
   * Tempdoc 511 §511-followup-B: `core.reset-settings` is mounted via
   * `<jf-operation>` (see `renderFooter`). Surface-side, we only react
   * to op-success/op-error to refresh + surface errors. The confirm
   * ceremony lives in the inner ActionButton driven by the wire's
   * `ConfirmStrategy.Inline`.
   */
  private handleResetSuccess(): void {
    this.error = null;
    void this.loadSettings();
  }

  private handleResetError(e: CustomEvent<OpErrorEventDetail>): void {
    const msg = e.detail?.message;
    this.error = typeof msg === 'string' ? msg : 'Reset failed.';
  }

  /**
   * 569 §15 (Move 8 / §7) — the bespoke destructive BODY, run by the surface when the declared
   * CONFIRM_CEREMONY statechart reaches `done`. The Tauri-shell delete is the team-owned residue
   * (outside the closed Effect vocabulary); the CEREMONY (states + the `typed == true` guard) is the
   * user-authored statechart that gates it. The imperative `showConfirmDialog` is replaced by the
   * declared `confirming` state the surface renders inline (the typed-confirm panel).
   */
  private async runDelete(): Promise<void> {
    if (!this.host_.platform.capabilities.has('native-notifications') || this.deleting) return;
    this.deleting = true;
    try {
      const mod = await import('@tauri-apps/api/core');
      const token = await mod.invoke<string>('prepare_delete_data');
      await mod.invoke('confirm_delete_data', { token });
    } catch (err) {
      this.error = err instanceof Error ? err.message : String(err);
      this.deleting = false;
    }
  }

  /**
   * 569 Move 1/3 (the keystone) — render the Interface + Appearance region from the ACTIVE
   * Presentation Declaration's body, through the projection engine (`<jf-declared-surface>`),
   * when one is applied; otherwise the built-in Lit render. An absent or quarantined body
   * silently degrades to the built-in (degrade-never-fail, Move 6). The author supplies only
   * schema + uischema (composition over the renderer vocabulary); the engine co-projects the
   * accessible, operable, 558-contrast-safe controls the author never touches. Edits round-trip
   * through the SAME `patch()` the built-in render uses.
   */
  private renderInterfaceRegion(): TemplateResult {
    const body = activeBodyFor(SETTINGS_INTERFACE_REGION);
    if (!body) {
      return html`${this.renderInterface()}${this.renderAppearance()}`;
    }
    return html`
      <div class="section">
        <jf-declared-surface
          .declaration=${body}
          .data=${this.ui as Record<string, unknown>}
          .enabled=${!this.readOnly}
          @surface-change=${(e: CustomEvent<SurfaceChangeEventDetail>) =>
            void this.patch(e.detail.data as Partial<UISettings>)}
        ></jf-declared-surface>
      </div>
    `;
  }

  private renderInterface(): TemplateResult {
    const mode: 'simple' | 'advanced' = this.ui.mode === 'advanced' ? 'advanced' : 'simple';
    return html`
      <div class="section">
        <h3>${icon({ name: 'layers', size: 12 })} Interface</h3>
        <div class="row">
          <button
            class="option-btn ${mode === 'simple' ? 'selected' : ''}"
            ?disabled=${this.readOnly}
            @click=${() => void this.patch({ mode: 'simple' })}
          >
            ${icon({ name: 'list', size: 18 })}
            <div class="option-label">Simple</div>
            <div class="option-desc">Standard view</div>
          </button>
          <button
            class="option-btn ${mode === 'advanced' ? 'selected' : ''}"
            ?disabled=${this.readOnly}
            @click=${() => void this.patch({ mode: 'advanced' })}
          >
            ${icon({ name: 'maximize-2', size: 18 })}
            <div class="option-label">Advanced</div>
            <div class="option-desc">Full controls + diagnostics</div>
          </button>
        </div>
        <p class="help">
          Advanced mode unlocks AI runtime configuration, GPU controls, Lucene search syntax,
          and library management tools.
        </p>
      </div>
    `;
  }

  private renderAppearance(): TemplateResult {
    const theme = this.ui.theme ?? 'system';
    return html`
      <div class="section">
        <h3>${icon({ name: 'palette', size: 12 })} Appearance</h3>
        <div class="row" style="margin-bottom: 0.75rem">
          <button
            class="option-btn ${theme === 'system' ? 'selected' : ''}"
            ?disabled=${this.readOnly}
            @click=${() => void this.patch({ theme: 'system' })}
          >
            ${icon({ name: 'monitor', size: 18 })}
            <div class="option-label">System</div>
            <div class="option-desc">Follow OS</div>
          </button>
          <button
            class="option-btn ${theme === 'dark' ? 'selected' : ''}"
            ?disabled=${this.readOnly}
            @click=${() => void this.patch({ theme: 'dark' })}
          >
            ${icon({ name: 'moon', size: 18 })}
            <div class="option-label">Dark</div>
            <div class="option-desc">Default theme</div>
          </button>
          <button
            class="option-btn ${theme === 'light' ? 'selected' : ''}"
            ?disabled=${this.readOnly}
            @click=${() => void this.patch({ theme: 'light' })}
          >
            ${icon({ name: 'sun', size: 18 })}
            <div class="option-label">Light</div>
            <div class="option-desc">Bright theme</div>
          </button>
        </div>
        <div class="toggle-row">
          <div>
            <div class="toggle-label">High contrast</div>
            <div class="toggle-desc">Better visibility</div>
          </div>
          <div
            class="switch ${this.ui.highContrast ? 'on' : ''}"
            role="switch"
            tabindex="0"
            @click=${() => void this.patch({ highContrast: !this.ui.highContrast })}
            @keydown=${(e: KeyboardEvent) => {
              if (e.key === ' ' || e.key === 'Enter') {
                e.preventDefault();
                void this.patch({ highContrast: !this.ui.highContrast });
              }
            }}
          ></div>
        </div>
        <div class="toggle-row">
          <div>
            <div class="toggle-label">Solid surfaces</div>
            <div class="toggle-desc">Opaque panels, no glass blur</div>
          </div>
          <div
            class="switch ${this.surfaceMode === 'solid' ? 'on' : ''}"
            role="switch"
            tabindex="0"
            @click=${() => this.toggleSurfaceMode()}
            @keydown=${(e: KeyboardEvent) => {
              if (e.key === ' ' || e.key === 'Enter') {
                e.preventDefault();
                this.toggleSurfaceMode();
              }
            }}
          ></div>
        </div>
      </div>
    `;
  }

  /**
   * Tempdoc 567 §9.4 — flip the glass/solid surface mode. `setSurfaceMode` (the theme authority)
   * applies it live through the one appearance writer AND persists it (FE-only, user-state document).
   */
  private toggleSurfaceMode(): void {
    this.surfaceMode = this.surfaceMode === 'solid' ? 'glass' : 'solid';
    setSurfaceMode(this.surfaceMode);
  }

  /**
   * 569 §19 Seam 4 — the adaptation / accessibility axes (density · contrast · motion). One authority
   * (`applyAdaptationProfile`) persists them per-profile and projects them to global DOM state; the
   * cascade re-projects every surface, so a single switch is total and contrast stays AA by construction.
   */
  private renderAccessibility(): TemplateResult {
    const p = getAdaptationProfile();
    const density = p.density ?? 'comfortable';
    const contrast = p.contrast ?? 'normal';
    const motion = p.motion ?? 'full';
    const opt = (active: boolean, label: string, desc: string, pick: () => void): TemplateResult => html`
      <button class="option-btn ${active ? 'selected' : ''}" ?disabled=${this.readOnly} @click=${pick}>
        <div class="option-label">${label}</div>
        <div class="option-desc">${desc}</div>
      </button>
    `;
    return html`
      <div class="section" data-testid="settings-accessibility">
        <h3>${icon({ name: 'layers', size: 12 })} Accessibility</h3>
        <div class="toggle-label" style="margin-bottom: 0.35rem">Density</div>
        <div class="row" style="margin-bottom: 0.75rem">
          ${opt(density === 'compact', 'Compact', 'More on screen', () =>
            applyAdaptationProfile({ density: 'compact' }),
          )}
          ${opt(density === 'comfortable', 'Comfortable', 'Default', () =>
            applyAdaptationProfile({ density: 'comfortable' }),
          )}
          ${opt(density === 'spacious', 'Spacious', 'Roomy', () =>
            applyAdaptationProfile({ density: 'spacious' }),
          )}
        </div>
        <div class="toggle-label" style="margin-bottom: 0.35rem">Contrast</div>
        <div class="row" style="margin-bottom: 0.75rem">
          ${opt(contrast === 'normal', 'Normal', 'Default', () =>
            applyAdaptationProfile({ contrast: 'normal' }),
          )}
          ${opt(contrast === 'high', 'High', 'Guaranteed AA', () =>
            applyAdaptationProfile({ contrast: 'high' }),
          )}
        </div>
        <div class="toggle-label" style="margin-bottom: 0.35rem">Motion</div>
        <div class="row">
          ${opt(motion === 'full', 'Full', 'Animations on', () =>
            applyAdaptationProfile({ motion: 'full' }),
          )}
          ${opt(motion === 'reduced', 'Calm', 'Reduced motion', () =>
            applyAdaptationProfile({ motion: 'reduced' }),
          )}
        </div>
      </div>
    `;
  }

  /**
   * Tempdoc 511-followup Track A + 511-followup-2 Track BB —
   * view-tier selector.
   *
   * IMPORTANT: this is a view PREFERENCE, not access control. The
   * local single-user deployment doesn't authenticate or authorize
   * operation invocations; the wire ships every operation's
   * metadata to every client, and `OperationClient.invoke()` will
   * call any operation regardless of the tier set here. Switching
   * tiers only affects what the UI RENDERS. Real authorization
   * (server-side catalog filtering by session identity) is a
   * separate concern that isn't necessary in a single-user local
   * model. The tempdoc's named follow-up "Option A2" describes
   * what such an auth layer would entail.
   *
   * USER (default): hide operator and developer ops; show only
   * user-facing surfaces and operations.
   * OPERATOR: also show operator-only ops (restart-worker,
   * bulk-reindex, etc.) — useful for system administration tasks.
   * DEVELOPER: show everything, including developer-only debug
   * surfaces and operations.
   * AGENT is reserved for the agent runtime, not user-selectable.
   */
  private renderViewerAudience(): TemplateResult {
    const audience: Audience = this.viewerAudience;
    const choose = (a: Audience) => () => setViewerAudience(a);
    return html`
      <div class="section">
        <h3>${icon({ name: 'shield', size: 12 })} View tier</h3>
        <div class="toggle-row" style="margin-bottom: 0.5rem">
          <div>
            <div class="toggle-label">View tier preference</div>
            <div class="toggle-desc">
              Controls which operations and surfaces the UI renders
              for you. This is a view preference — it does not
              restrict backend access. Leave on "User" for the
              default experience; switch up for admin or debug
              workflows.
            </div>
          </div>
        </div>
        <div class="row">
          <button
            class="option-btn ${audience === 'USER' ? 'selected' : ''}"
            ?disabled=${this.readOnly}
            @click=${choose('USER')}
          >
            ${icon({ name: 'monitor', size: 18 })}
            <div class="option-label">User</div>
            <div class="option-desc">Default tier</div>
          </button>
          <button
            class="option-btn ${audience === 'OPERATOR' ? 'selected' : ''}"
            ?disabled=${this.readOnly}
            @click=${choose('OPERATOR')}
          >
            ${icon({ name: 'shield', size: 18 })}
            <div class="option-label">Operator</div>
            <div class="option-desc">Shows admin ops</div>
          </button>
          <button
            class="option-btn ${audience === 'DEVELOPER' ? 'selected' : ''}"
            ?disabled=${this.readOnly}
            @click=${choose('DEVELOPER')}
          >
            ${icon({ name: 'database', size: 18 })}
            <div class="option-label">Developer</div>
            <div class="option-desc">Show everything</div>
          </button>
        </div>
      </div>
    `;
  }

  private renderKeyboard(): TemplateResult {
    const action = this.ui.defaultAction ?? 'open';
    return html`
      <div class="section">
        <h3>${icon({ name: 'keyboard', size: 12 })} Keyboard</h3>
        <div class="toggle-row">
          <div>
            <div class="toggle-label">Enter action</div>
            <div class="toggle-desc">Default action when pressing Enter on a result</div>
          </div>
          <!-- Tempdoc 543 §20.7 B1 — schema-driven form via the
               x-ui-renderer dispatcher. The 'enter-action-select' hint
               routes to EnterActionPickerRenderer (registered at boot
               via shell-v0/renderers/registry.ts). -->
          <jf-form
            .schema=${{
              type: 'object',
              properties: {
                defaultAction: {
                  type: 'string',
                  enum: ['open', 'reveal', 'preview'],
                  'x-ui-renderer': 'enter-action-select',
                },
              },
            }}
            .uischema=${{
              type: 'Control',
              scope: '#/properties/defaultAction',
            }}
            .data=${{ defaultAction: action }}
            ?enabled=${!this.readOnly}
            @form-change=${(e: CustomEvent<FormChangeEventDetail>) => {
              const next = (e.detail.data as { defaultAction?: string })
                .defaultAction;
              if (
                next === 'open' ||
                next === 'reveal' ||
                next === 'preview'
              ) {
                void this.patch({
                  defaultAction: next as UISettings['defaultAction'],
                });
              }
            }}
          ></jf-form>
        </div>
      </div>
    `;
  }

  private renderDesktop(): TemplateResult | typeof nothing {
    if (!this.host_.platform.capabilities.has('native-notifications') || !this.autostartLoaded || this.autostart === null) {
      return nothing;
    }
    return html`
      <div class="section">
        <h3>${icon({ name: 'power', size: 12 })} Desktop</h3>
        <div class="toggle-row">
          <div>
            <div class="toggle-label">Launch on startup</div>
            <div class="toggle-desc">Start minimized in the system tray</div>
          </div>
          <div
            class="switch ${this.autostart ? 'on' : ''}"
            role="switch"
            tabindex="0"
            @click=${() => void this.toggleAutostart()}
          ></div>
        </div>
      </div>
    `;
  }

  /**
   * Slice 477 H1 — V1.5 Themes section.
   *
   * Lists built-in themes (`themesCatalog.BUILT_IN_THEMES`) with a
   * "Default" option that clears the active theme. Selection writes
   * to `themeState`, which fetches `/themes/<id>.css` and injects
   * the result. Persists across reloads via `themeState`'s
   * localStorage layer.
   */
  private renderThemes(): TemplateResult {
    const themes = listAvailableThemes();
    const active = this.activeThemeId;
    const renderThemeButton = (entry: ThemeCatalogEntry): TemplateResult => {
      // Tempdoc 567 — a custom (user-authored) theme carries its token tree; built-ins carry cssPath.
      const isCustom = entry.tokens !== undefined;
      const btn = html`
        <button
          class="option-btn ${active === entry.id ? 'selected' : ''}"
          @click=${() => void this.selectTheme(entry.id)}
          title=${entry.description}
        >
          ${icon({ name: 'palette', size: 18 })}
          <div class="option-label">${entry.displayName}</div>
          <div class="option-desc">${entry.description}</div>
        </button>
      `;
      if (!isCustom) return btn;
      // Tempdoc 567 §8 #3 — custom themes are MANAGED in the host (Settings → Appearance), independent
      // of the editor plugin's lifecycle: rename + delete controls overlay the palette button. While
      // this theme is being renamed (§8 deferred → built), the button is replaced by an inline input.
      if (this.renamingThemeId === entry.id) {
        return html`
          <div class="custom-theme custom-theme-renaming">
            <input
              class="theme-rename-input"
              .value=${this.renameDraft}
              aria-label=${`Rename custom theme ${entry.displayName}`}
              @input=${(e: Event) => (this.renameDraft = (e.target as HTMLInputElement).value)}
              @keydown=${(e: KeyboardEvent) => {
                if (e.key === 'Enter') this.commitRenameTheme(entry.id);
                else if (e.key === 'Escape') this.cancelRenameTheme();
              }}
            />
            <jf-button
              variant="ghost"
              size="icon"
              label="Save new name"
              title="Save"
              .onActivate=${() => this.commitRenameTheme(entry.id)}
            >
              ${icon({ name: 'check-circle-2', size: 14 })}
            </jf-button>
            <jf-button
              variant="ghost"
              size="icon"
              label="Cancel rename"
              title="Cancel"
              .onActivate=${() => this.cancelRenameTheme()}
            >
              ${icon({ name: 'x', size: 14 })}
            </jf-button>
          </div>
        `;
      }
      return html`
        <div class="custom-theme">
          ${btn}
          <jf-button
            class="custom-theme-rename"
            variant="ghost"
            size="icon"
            label=${`Rename custom theme ${entry.displayName}`}
            title=${`Rename "${entry.displayName}"`}
            .onActivate=${() => this.beginRenameTheme(entry)}
          >
            ${icon({ name: 'pencil', size: 13 })}
          </jf-button>
          <jf-button
            class="custom-theme-del"
            variant="ghost"
            size="icon"
            label=${`Delete custom theme ${entry.displayName}`}
            title=${`Delete "${entry.displayName}"`}
            .onActivate=${() => void this.deleteCustomTheme(entry)}
          >
            ${icon({ name: 'trash-2', size: 14 })}
          </jf-button>
        </div>
      `;
    };
    return html`
      <div class="section">
        <h3>${icon({ name: 'palette', size: 12 })} Theme</h3>
        <p class="help" style="margin: 0 0 0.5rem 0">
          Pick a full theme palette. Composes with the dark/light variant in
          Appearance above.
        </p>
        <div class="row">
          <button
            class="option-btn ${active === null ? 'selected' : ''}"
            @click=${() => void this.selectTheme(null)}
            title="Default tokens — no theme override"
          >
            ${icon({ name: 'circle', size: 18 })}
            <div class="option-label">Default</div>
            <div class="option-desc">No override</div>
          </button>
          ${themes.map(renderThemeButton)}
        </div>
        ${this.renderThemeImport()}
      </div>
    `;
  }

  /**
   * Tempdoc 567 §8 (deferred → built) — import a custom theme from a pasted JSON declaration. Host-owned
   * (Settings → Appearance), the symmetric counterpart of the editor's export-to-clipboard: a theme
   * shared as JSON can be brought back in without the editor plugin. The host capability validates and
   * holds the tree to the seeds+roles authorable surface, so import is not a backdoor for derived tokens.
   */
  private renderThemeImport(): TemplateResult {
    if (!this.themeImporting) {
      return html`
        <jf-button
          class="theme-import-toggle"
          variant="ghost"
          size="sm"
          label="Import a theme from JSON"
          .onActivate=${() => {
            this.error = null;
            this.themeImporting = true;
          }}
        >
          ${icon({ name: 'upload', size: 14 })} Import theme…
        </jf-button>
      `;
    }
    return html`
      <div class="theme-import">
        <label class="help" for="theme-import-json">Paste a theme's exported JSON</label>
        <textarea
          id="theme-import-json"
          class="theme-import-json"
          rows="5"
          spellcheck="false"
          .value=${this.themeImportDraft}
          @input=${(e: Event) => (this.themeImportDraft = (e.target as HTMLTextAreaElement).value)}
        ></textarea>
        <div class="theme-import-actions">
          <jf-button
            variant="primary"
            size="sm"
            label="Import the pasted theme"
            ?disabled=${this.themeImportDraft.trim() === ''}
            .onActivate=${() => this.importThemeFromDraft()}
          >
            Import
          </jf-button>
          <jf-button
            variant="ghost"
            size="sm"
            label="Cancel import"
            .onActivate=${() => {
              this.themeImporting = false;
              this.themeImportDraft = '';
            }}
          >
            Cancel
          </jf-button>
        </div>
      </div>
    `;
  }

  /**
   * Slice 477 H1 — V1.5 Rail customization section.
   *
   * Per-surface visibility checkbox + up/down arrow buttons for
   * reordering. Drag-to-reorder is V1.5.1 polish (richer UX, library
   * dep, accessibility surface); up/down buttons are accessible by
   * default and require no library.
   *
   * The order shown reflects the current `userConfig.surfaceOrder`
   * applied to the catalog (catalog order is the fallback for
   * surfaces not in `surfaceOrder`). Visibility checkbox reflects
   * `userConfig.surfaceVisibility` (absent = visible).
   */
  private renderRail(): TemplateResult {
    const orderedSurfaces = this.applyOrderToSurfaces(this.railSurfaces);
    const visibility = this.userConfig.surfaceVisibility ?? {};
    const hasOverrides =
      this.userConfig.surfaceVisibility !== undefined ||
      this.userConfig.surfaceOrder !== undefined;
    return html`
      <div class="section">
        <h3>${icon({ name: 'menu', size: 12 })} Rail</h3>
        <p class="help" style="margin: 0 0 0.5rem 0">
          Reorder or hide surfaces in the activity rail.
          ${hasOverrides
            ? html`
                <jf-button
                  size="sm"
                  label="Reset"
                  style="margin-left: 0.5rem"
                  title="Reset rail to catalog defaults"
                  .onActivate=${() => this.host_.layout.clearAllLayoutOverrides()}
                >
                  Reset
                </jf-button>
              `
            : nothing}
        </p>
        <ul class="rail-list">
          ${orderedSurfaces.map((s, idx) => {
            const visible = visibility[s.id] !== false;
            const isFirst = idx === 0;
            const isLast = idx === orderedSurfaces.length - 1;
            return html`
              <li class="rail-row">
                <input
                  type="checkbox"
                  ?checked=${visible}
                  @change=${(e: Event) =>
                    this.host_.layout.setSurfaceVisibility(s.id, (e.target as HTMLInputElement).checked)}
                  aria-label=${`Show ${present({ kind: 'surface', id: s.id }).label} in rail`}
                />
                <span class="rail-label" style=${visible ? '' : 'opacity: 0.5'}>
                  ${present({ kind: 'surface', id: s.id }).label}
                </span>
                <jf-button
                  class="rail-arrow"
                  variant="ghost"
                  size="icon"
                  label="Move up"
                  title="Move up"
                  ?disabled=${isFirst}
                  .onActivate=${() => this.moveRailSurface(idx, -1)}
                >
                  ${icon({ name: 'chevron-up', size: 14 })}
                </jf-button>
                <jf-button
                  class="rail-arrow"
                  variant="ghost"
                  size="icon"
                  label="Move down"
                  title="Move down"
                  ?disabled=${isLast}
                  .onActivate=${() => this.moveRailSurface(idx, 1)}
                >
                  ${icon({ name: 'chevron-down', size: 14 })}
                </jf-button>
              </li>
            `;
          })}
        </ul>
      </div>
    `;
  }

  /**
   * Slice 477 H1 — V1.5 Plugins section.
   *
   * Lists plugins from the session-singleton {@link getSessionPluginRegistry}.
   * For each plugin: id + version + provenance (currently uniform
   * TRUSTED_PLUGIN per V1.5 alpha; 478 §4.D refines via Sigstore).
   * Revoke calls `registry.uninstall(id)` + `removePluginSurfaceContributions(id)`.
   *
   * Ships a "Load plugin from URL" input (see renderPlugins) alongside
   * console / dev-examples auto-load. Plugins loaded this way resolve to
   * UNTRUSTED tier. NOTE: per docs/tempdocs/547 F1/F2 the UNTRUSTED
   * sandbox does not fully contain plugins in the default (lockdown-off)
   * configuration — treat loading a plugin as running code with
   * effectively-full app access. Marketplace UI is gated by 470 §7's
   * ≥5-community-plugins demand trigger (deferred per 477 §4.4).
   */
  private renderLayout(): TemplateResult {
    const layouts = listLayouts();
    const activeId = this.userConfig?.activeLayoutId ?? 'core.default';
    return html`
      <div class="section">
        <h3>${icon({ name: 'layers', size: 12 })} Layout</h3>
        <p class="help" style="margin: 0 0 0.5rem 0">Choose how the workspace is arranged.</p>
        <div class="card-row">
          ${layouts.map(
            (layout) => html`
              <button
                class="card ${activeId === layout.id ? 'active' : ''}"
                @click=${() => this.selectLayout(layout.id)}
              >
                <span class="card-label">${layout.displayName}</span>
                <span class="card-desc">${layout.description ?? ''}</span>
              </button>
            `,
          )}
        </div>
      </div>
    `;
  }

  private selectLayout(layoutId: string): void {
    this.host_.layout.setActiveLayoutId(layoutId === 'core.default' ? undefined : layoutId);
  }

  private renderPlugins(): TemplateResult {
    return html`
      <div class="section">
        <h3>${icon({ name: 'package', size: 12 })} Plugins</h3>
        ${this.plugins.length > 0
          ? html`
              <ul class="plugin-list">
                ${this.plugins.map((p) => this.renderPluginRow(p))}
              </ul>
            `
          : html`<p class="help" style="margin: 0">No plugins installed.</p>`}
        <div class="plugin-loader" style="margin-top: 0.75rem; display: flex; gap: 0.5rem; align-items: center;">
          <input
            type="text"
            class="filter-input"
            placeholder="Plugin URL (e.g., http://localhost:3000/plugin.js)"
            style="flex: 1; padding: 0.4rem 0.625rem; background: var(--surface-secondary); border: 1px solid var(--border-subtle); border-radius: 0.375rem; color: var(--text-primary); font-size: var(--font-size-sm);"
            @keydown=${(e: KeyboardEvent) => {
              if (e.key === 'Enter') void this.loadPluginFromInput();
            }}
          />
          <jf-button .onActivate=${() => void this.loadPluginFromInput()}>
            ${icon({ name: 'folder-plus', size: 12 })} Load
          </jf-button>
        </div>
      </div>
    `;
  }

  private async loadPluginFromInput(): Promise<void> {
    const input = this.shadowRoot?.querySelector('.plugin-loader input') as HTMLInputElement | null;
    const url = input?.value?.trim();
    if (!url) return;
    try {
      this.error = null;
      const manifest = await this.installFromUrl(url);
      this.plugins = getSessionPluginRegistry().list();
      this.recordTrustState(manifest.id, url);
      if (input) input.value = '';
    } catch (err) {
      const message = err instanceof Error ? err.message : String(err);
      const stack = err instanceof Error ? err.stack : undefined;
      this.error = message;
      // Tempdoc 508 §6.2 — dispatch plugin load error to overlay
      const { dispatchPluginError } = await import('../components/PluginErrorOverlay.js');
      dispatchPluginError({ pluginUrl: url, message, stack });
    }
  }

  /**
   * Tempdoc 560 §28 — install a plugin through the real trust path: FirstPartyTrustChannel wrapping
   * RemoteTrustChannel → POST /api/plugins/verify → the persisted operator-allowlist verdict. Shared
   * by the initial load and the post-approval reload so both run the identical channel; an already-
   * fetched `source` is reused (avoids a second fetch and keeps the hashed bytes identical to what was
   * approved). The dev-only first-party marker is the sandboxed stand-in until Sigstore (560 §23); in
   * a production build it is a pure pass-through, so a third-party URL gets the pure backend verdict.
   */
  private async installFromUrl(url: string, source?: string) {
    const { loadPluginFromUrl } = await import('../plugin-api/PluginLoader.js');
    const { RemoteTrustChannel, FirstPartyTrustChannel } = await import(
      '../plugin-api/TrustChannel.js'
    );
    const { resolveApiEndpoint } = await import('../../api/http.js');
    const endpoint = await resolveApiEndpoint();
    const trustChannel = new FirstPartyTrustChannel(new RemoteTrustChannel(endpoint.baseUrl ?? ''));
    // §4.2 — host deps expose the tier-attenuated @kernel/* access path to the plugin.
    const hostDeps = { apiBase: endpoint.baseUrl ?? '', registerSurfacePort: () => {} };
    return loadPluginFromUrl(getSessionPluginRegistry(), url, {
      trustChannel,
      hostDeps,
      ...(source !== undefined ? { sourceFetcher: () => Promise.resolve(source) } : {}),
    });
  }

  /** Tempdoc 560 §28 — record whether a freshly-installed plugin is UNTRUSTED (gates "Approve & trust"). */
  private recordTrustState(id: string, url: string): void {
    const installed = this.plugins.find((p) => p.manifest.id === id);
    const next = new Map(this.untrustedLoads);
    if (installed && installed.trustTier === 'UNTRUSTED_PLUGIN') {
      next.set(id, url);
    } else {
      next.delete(id);
    }
    this.untrustedLoads = next;
  }

  /**
   * Tempdoc 560 §28 — operator approves a URL-loaded UNTRUSTED plugin: fetch its source via the one
   * loader fetch authority, add its artifact SHA-256 to the persisted backend allowlist, then reload
   * it through the same channel so the now-allowlisted verdict returns TRUSTED (its own-element
   * surface becomes admissible under the §4.4 presentation constraint). This is the explicit,
   * auditable trust ceremony — not a client-side tier override.
   */
  private async approveAndTrust(id: string): Promise<void> {
    const url = this.untrustedLoads.get(id);
    if (!url) return;
    try {
      this.error = null;
      const { fetchPluginSource } = await import('../plugin-api/PluginLoader.js');
      const { artifactSha256OfSource } = await import('../plugin-api/TrustChannel.js');
      const source = await fetchPluginSource(url);
      const sha = await artifactSha256OfSource(source);
      const res = await this.doFetch('/api/plugins/allowlist', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ artifactSha256: sha }),
      });
      if (!res.ok) {
        this.error = `Approval failed: HTTP ${res.status}`;
        return;
      }
      // Reload through the same channel: uninstall the UNTRUSTED instance, re-install (now verified).
      const registry = getSessionPluginRegistry();
      if (registry.has(id)) registry.uninstall(id);
      const manifest = await this.installFromUrl(url, source);
      this.plugins = registry.list();
      this.recordTrustState(manifest.id, url);
    } catch (err) {
      this.error = err instanceof Error ? err.message : String(err);
    }
  }

  private renderPluginRow(p: InstalledPlugin): TemplateResult {
    const errored = p.registerError !== null;
    // §4.1: read the plugin via the unified declaration projection (the FE side of
    // "PluginManifest projected onto the backend Plugin"), not the raw manifest. Pass the stored
    // REGISTRATION tier (CORE for compiled-in, the loader's verdict for URL-loaded) so the badge
    // reflects the real tier; only fall back to signature-presence when no tier was recorded.
    const decl = pluginDeclaration(p.manifest, p.trustTier);
    const trusted = decl.provenance.trustTier !== 'UNTRUSTED_PLUGIN';
    return html`
      <li class="plugin-row">
        <div class="plugin-meta">
          <div class="plugin-id">
            ${p.manifest.id}
            <span class="plugin-version">v${p.manifest.version}</span>
            ${errored
              ? html`<jf-status-badge tone="error" title=${p.registerError?.message ?? ''}
                  >error</jf-status-badge
                >`
              : trusted
                ? html`<jf-status-badge tone="success">trusted</jf-status-badge>`
                : html`<jf-status-badge tone="warning" title="Unsigned / third-party"
                    >untrusted</jf-status-badge
                  >`}
          </div>
          <div class="plugin-display">${decl.presentation.label}</div>
          ${p.manifest.capabilities.surfaces?.length
            ? html`
                <div class="plugin-extras">
                  ${p.manifest.capabilities.surfaces.length} surface(s)
                  ${p.installedTranslationKeys.length > 0
                    ? html` · ${p.installedTranslationKeys.length} i18n key(s)`
                    : nothing}
                </div>
              `
            : nothing}
          ${!trusted && this.untrustedLoads.has(p.manifest.id)
            ? html`<div
                class="plugin-extras"
                style="color: var(--text-warning);"
              >
                Untrusted — its own UI is hidden. Approve to trust this source and load it fully.
              </div>`
            : nothing}
        </div>
        ${!trusted && this.untrustedLoads.has(p.manifest.id)
          ? html`<jf-button
              label="Approve & trust"
              title="Add this plugin's source hash to the operator trust allowlist and reload it as TRUSTED"
              .onActivate=${() => void this.approveAndTrust(p.manifest.id)}
            >
              ${icon({ name: 'shield', size: 14 })} Approve &amp; trust
            </jf-button>`
          : nothing}
        <jf-button
          variant="danger"
          label="Revoke"
          title="Uninstall plugin"
          .onActivate=${() => void this.revokePlugin(p.manifest.id)}
        >
          ${icon({ name: 'trash-2', size: 14 })} Revoke
        </jf-button>
        ${p.manifest.settingsSchema
          ? html`
              <div class="plugin-settings">
                <jf-form
                  .schema=${p.manifest.settingsSchema}
                  .data=${this.host_.settings.getSetting('__all__') ?? {}}
                  @form-change=${(e: CustomEvent<{ data: Record<string, unknown> }>) => {
                    const data = e.detail?.data;
                    if (data) {
                      for (const [k, v] of Object.entries(data)) {
                        this.host_.settings.setSetting(k, v);
                      }
                    }
                  }}
                ></jf-form>
              </div>`
          : nothing}
      </li>
    `;
  }

  private async selectTheme(id: string | null): Promise<void> {
    try {
      this.error = null;
      // selectTheme is a TRUSTED+/CORE write (optional on PluginThemeState since 560 §24); this
      // Settings surface runs at CORE tier so it is present, but guard for the optional type.
      if (id === null) {
        void this.host_.theme.selectTheme?.(null);
      } else {
        await this.host_.theme.selectTheme?.(id);
      }
    } catch (err) {
      this.error = err instanceof Error ? err.message : String(err);
    }
  }

  /**
   * Tempdoc 567 §8 #3 — delete a user custom theme from the host (Settings → Appearance), independent
   * of the editor plugin. Deleting the ACTIVE theme reverts to default via the capability's
   * clearActiveTheme (the subscribeActiveTheme listener re-renders); for a non-active theme the catalog
   * changed but no reactive prop did, so request an explicit re-render.
   */
  private async deleteCustomTheme(entry: ThemeCatalogEntry): Promise<void> {
    const confirmed = await this.host_.ui.showConfirmDialog(
      `Delete the custom theme "${entry.displayName}"? This cannot be undone.`,
    );
    if (!confirmed) return;
    this.host_.theme.deleteTheme?.(entry.id);
    this.requestUpdate();
  }

  /**
   * Tempdoc 567 §8 (deferred → built) — begin an inline rename of a custom theme. Opens the row's
   * text input pre-filled with the current label; commit calls the host `renameTheme` capability
   * (displayName-only, id stable). Host-owned management, independent of the editor plugin.
   */
  private beginRenameTheme(entry: ThemeCatalogEntry): void {
    this.error = null;
    this.renamingThemeId = entry.id;
    this.renameDraft = entry.displayName;
  }

  private cancelRenameTheme(): void {
    this.renamingThemeId = null;
    this.renameDraft = '';
  }

  private commitRenameTheme(id: string): void {
    try {
      this.error = null;
      this.host_.theme.renameTheme?.(id, this.renameDraft);
      this.renamingThemeId = null;
      this.renameDraft = '';
      this.requestUpdate();
    } catch (err) {
      this.error = err instanceof Error ? err.message : String(err);
    }
  }

  /**
   * Tempdoc 567 §8 (deferred → built) — import a custom theme from a pasted JSON declaration (the
   * counterpart to the editor's export-to-clipboard). The host `importTheme` capability validates the
   * tree and holds it to the same seeds+roles authorable surface as a save, so an imported theme can
   * never carry a derived token. On success the new theme joins the catalog's custom layer.
   */
  private importThemeFromDraft(): void {
    try {
      this.error = null;
      this.host_.theme.importTheme?.(this.themeImportDraft);
      this.themeImporting = false;
      this.themeImportDraft = '';
      this.requestUpdate();
    } catch (err) {
      this.error = err instanceof Error ? err.message : String(err);
    }
  }

  /**
   * Apply current `userConfig.surfaceOrder` to the catalog list,
   * so the user sees the rendered order. Surfaces not in
   * `surfaceOrder` keep catalog order after the ordered set.
   */
  private applyOrderToSurfaces(surfaces: Surface[]): Surface[] {
    const order = this.userConfig.surfaceOrder;
    if (!order || order.length === 0) return surfaces;
    const present = new Map(surfaces.map((s) => [s.id, s]));
    const ordered: Surface[] = [];
    const seen = new Set<string>();
    for (const id of order) {
      const s = present.get(id);
      if (s && !seen.has(id)) {
        ordered.push(s);
        seen.add(id);
      }
    }
    for (const s of surfaces) {
      if (!seen.has(s.id)) ordered.push(s);
    }
    return ordered;
  }

  private moveRailSurface(idx: number, delta: -1 | 1): void {
    const ordered = this.applyOrderToSurfaces(this.railSurfaces);
    const target = idx + delta;
    if (target < 0 || target >= ordered.length) return;
    const next = [...ordered];
    const [moved] = next.splice(idx, 1);
    if (!moved) return;
    next.splice(target, 0, moved);
    this.host_.layout.setSurfaceOrder(next.map((s) => s.id));
  }

  private async revokePlugin(id: string): Promise<void> {
    const ok = await this.host_.ui.showConfirmDialog(
      `This uninstalls plugin "${id}" and removes its surface contributions, ` +
      'overrides, and i18n keys. The plugin must be re-loaded to come back.', {
      confirmLabel: 'Revoke',
    });
    if (!ok) return;
    try {
      this.error = null;
      const registry = getSessionPluginRegistry();
      registry.uninstall(id);
      removePluginSurfaceContributions(id);
      // Refresh local snapshot — `onSurfaceCatalogChange` listener
      // will also fire from `removePluginSurfaceContributions`, but
      // updating now makes the UI feel instantaneous.
      this.plugins = registry.list();
    } catch (err) {
      this.error = err instanceof Error ? err.message : String(err);
    }
  }

  private renderData(): TemplateResult {
    const tauri = this.host_.platform.capabilities.has('native-notifications');
    return html`
      <div class="section">
        <h3>${icon({ name: 'database', size: 12 })} Data</h3>
        <p class="help" style="margin-top: 0">
          If you want to uninstall JustSearch or start fresh, you can delete all local data from
          inside the app.
        </p>
        <div style="margin-top: 0.75rem">
          ${this.deleteState === 'confirming'
            ? this.renderDeleteConfirm()
            : html`<jf-button
                variant="danger"
                label="Delete all local data"
                ?disabled=${!tauri || this.deleting}
                title=${tauri
                  ? 'Closes the app and wipes local data on next start'
                  : 'Available only in the desktop app (Tauri).'}
                .onActivate=${() => this.deleteMachine?.send('REQUEST')}
              >
                ${icon({ name: 'trash-2', size: 14 })}
                ${this.deleting ? 'Closing…' : 'Delete all local data'}
              </jf-button>`}
        </div>
      </div>
    `;
  }

  /**
   * 569 §15 (Move 8) — the declared `confirming` state of the delete ceremony, rendered inline. The
   * typed input feeds the statechart's `typed == true` GUARD: `CONFIRM` only advances to `done`
   * (which runs the bespoke delete) when "DELETE" is typed — otherwise the guard blocks the
   * transition. This is the first BRANCHING, GUARDED statechart driving a real destructive surface
   * flow (the appearance flow was single-state). Replaces the imperative `showConfirmDialog`.
   */
  private renderDeleteConfirm(): TemplateResult {
    const typed = this.confirmText.trim().toUpperCase() === 'DELETE';
    return html`
      <div role="group" aria-label="Confirm delete all local data">
        <p class="help" style="margin-top: 0">
          This closes JustSearch and deletes your local index, settings, and logs on next launch
          (your AI models in AI Home are preserved). Type <strong>DELETE</strong> to confirm.
        </p>
        <div
          style="margin-top:0.5rem; padding:0.6rem; background:var(--accent-warning-16); border-radius:0.25rem"
        >
          <!-- Tempdoc 629 (#protect-on-delete): the AUTHORED stores, if encrypted, are NOT rebuildable,
               so the uniform wipe is permanent. Point to the encrypted export (in Security & Privacy)
               first so the user can restore them later. -->
          If you've encrypted your chat history, memories, or agent history, they
          <strong>can't be rebuilt</strong> — export an encrypted backup first so you can restore them
          later.
          <div style="margin-top:0.5rem">
            <jf-button
              variant="secondary"
              .onActivate=${() => this.host_.navigation.navigate('core.security-surface')}
              >Open Security &amp; Privacy to export</jf-button
            >
          </div>
        </div>
        <input
          type="text"
          aria-label="Type DELETE to confirm"
          placeholder="DELETE"
          style="padding:0.4rem 0.6rem; border-radius:0.375rem; border:1px solid var(--border-subtle); background:var(--surface-1); color:var(--text-primary); font:inherit; min-inline-size:12rem"
          .value=${this.confirmText}
          @input=${(e: Event) => (this.confirmText = (e.target as HTMLInputElement).value)}
        />
        <div style="display:flex; gap:0.5rem; margin-top:0.5rem">
          <jf-button
            variant="danger"
            label="Delete & Close"
            ?disabled=${!typed}
            .onActivate=${() => this.deleteMachine?.send('CONFIRM', { typed })}
          >
            ${icon({ name: 'trash-2', size: 14 })} Delete &amp; Close
          </jf-button>
          <jf-button
            variant="ghost"
            label="Cancel"
            .onActivate=${() => {
              this.confirmText = '';
              this.deleteMachine?.send('CANCEL');
            }}
          >
            Cancel
          </jf-button>
        </div>
      </div>
    `;
  }

  /**
   * Tempdoc 571 §11 / 578 (post-review fix B) — the RAIL surfaces for the rail-customization UI, with
   * host MEMBERS excluded (a member's home is its host, so it never appears on the rail; mirrors
   * Shell.refreshSurfaces). Source-agnostic: excludes members regardless of their declared placement.
   */
  private static railSurfacesForCustomization(): Surface[] {
    const all = listSurfaces();
    const memberIds = new Set(all.flatMap((s) => s.members ?? []));
    return all.filter((s) => s.placement === 'RAIL' && !memberIds.has(s.id));
  }

  override render(): TemplateResult {
    // Tempdoc 571 §11 / 578 — Settings ⊇ Appearance: tab 0 is the Preferences own body (slotted so this
    // surface's shadow CSS + the declaration engine mount stay intact); the members (Skins, Editor)
    // form the Appearance tab group. Member labels come from the one display authority `present`
    // (i18n label "Skins", humanized id as fallback) — not a per-host humanize fork (578 §14).
    const members = getSurface('core.settings-surface')?.members ?? [];
    const items: SurfaceTabItem[] = [
      { id: 'preferences', label: 'Preferences', altitude: 'PRODUCT', slot: 'tab-preferences' },
      ...members.map((mid) => ({
        id: mid,
        label: present({ kind: 'surface', id: mid }).label,
        altitude: getSurface(mid)?.altitude,
        surfaceId: mid,
      })),
    ];
    return html`
      <jf-surface-tabs
        tablist-label="Settings views"
        api-base=${this.apiBase}
        .host_=${this.host_}
        active-id=${this.activeTab}
        .items=${items}
        @tab-change=${(e: CustomEvent<{ id: string }>) => (this.activeTab = e.detail.id)}
      >
        <div slot="tab-preferences" class="settings-scroll">${this.renderSettingsBody()}</div>
      </jf-surface-tabs>
    `;
  }

  private renderSettingsBody(): TemplateResult {
    return html`
      <div class="header">
        <div>
          <h2>Settings</h2>
          <p class="subtitle">Customize your experience</p>
        </div>
        <div class="row">
          ${this.readOnly
            ? html`<jf-status-badge tone="warning">Session-only</jf-status-badge>`
            : nothing}
          ${this.saving
            ? html`<span style="font-size: var(--font-size-xs); color: var(--text-tint)">Saving…</span>`
            : nothing}
          ${!this.readOnly
            ? html`<span
                @op-success=${() => this.handleResetSuccess()}
                @op-error=${(e: CustomEvent<OpErrorEventDetail>) =>
                  this.handleResetError(e)}
              >
                <jf-operation
                  operation-id="core.reset-settings"
                  context="button"
                  api-base=${this.apiBase}
                ></jf-operation>
              </span>`
            : nothing}
        </div>
      </div>
      <div class="body">
        ${this.error
          ? html`<jf-error-alert
              tone="error"
              .onDismiss=${() => (this.error = null)}
            >
              <span slot="icon">${icon({ name: 'alert-circle', size: 14 })}</span>
              ${this.error}
            </jf-error-alert>`
          : nothing}
        ${this.renderInterfaceRegion()}
        ${this.renderAccessibility()}
        ${this.renderThemes()}
        ${this.renderRail()}
        ${this.renderLayout()}
        ${this.renderPlugins()}
        ${this.renderViewerAudience()}
        ${this.renderKeyboard()}
        ${this.renderDesktop()}
        <div class="section">
          <h3>${icon({ name: 'shield', size: 12 })} Security &amp; Privacy</h3>
          <p class="help" style="margin-top: 0">
            Chat encryption, encrypted backups, auto-lock, and what's protected at rest now have their
            own home.
          </p>
          <div style="margin-top: 0.5rem">
            <jf-button
              variant="secondary"
              .onActivate=${() => this.host_.navigation.navigate('core.security-surface')}
              >Open Security &amp; Privacy</jf-button
            >
          </div>
        </div>
        ${this.renderData()}
        ${this.renderPluginPermissions()}
        ${this.renderDurableGrants()}
        ${this.renderWitness()}
        ${this.renderAutonomyDial()}
        ${this.renderWorkspaceProfilesDeveloper()}
      </div>
    `;
  }

  /**
   * Tempdoc 543 §20.7 B6 — Workspace Profiles substrate developer
   * affordance. Gated to DEVELOPER audience; shows: save current
   * snapshot as a named profile, list known profiles, activate one.
   * Minimal viable consumer for Slice 10's substrate; full UX is a
   * future product slice.
   */
  /**
   * §32 U1 — Agent autonomy dial. Shown to all audiences (a primary
   * agent-safety control). The destructive-op gate is backend-enforced
   * regardless of the setting.
   */
  private renderAutonomyDial(): TemplateResult {
    return html`
      <div class="section">
        <h3>${icon({ name: 'layers', size: 12 })} Agent autonomy</h3>
        <p class="toggle-desc">
          How much the assistant acts on its own. Destructive actions are
          always confirmed regardless of this setting (backend-enforced).
        </p>
        <jf-autonomy-dial></jf-autonomy-dial>
      </div>
    `;
  }

  /**
   * §28.W3 / §14.3 δ5 — Plugin permissions management UI.
   *
   * Renders the list of recorded consent grants (from
   * substrates/consent/) with revoke + change-decision affordances.
   * Closes the §14.3 β4 "manage plugin permissions" requirement — the
   * per-request prompt already exists via the AuthorizationHost ceremony
   * surface (tempdoc 550 G9, formerly ConsentHost); this is the
   * central management screen for already-granted consents.
   *
   * Visible to OPERATOR + DEVELOPER audiences; USER audience hides
   * the panel (consent management is a power-user affordance).
   */
  // ── Durable grants (tempdoc 560 §28 / 4d) ───────────────────────────────────────────────────────

  private async loadDurableGrants(): Promise<void> {
    try {
      const res = await this.doFetch('/api/authorizations/grants');
      if (!res.ok) return;
      const data = (await res.json()) as {
        grants?: Array<{ kind: string; target: string; sourceTier: string }>;
      };
      this.durableGrants = data.grants ?? [];
    } catch {
      // Best-effort; the panel simply shows no grants if the backend is unreachable.
    }
  }

  private async revokeDurableGrant(
    kind: string,
    target: string,
    sourceTier: string,
  ): Promise<void> {
    try {
      const res = await this.doFetch('/api/authorizations/grants', {
        method: 'DELETE',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ kind, target, sourceTier }),
      });
      if (res.ok) await this.loadDurableGrants();
    } catch (err) {
      this.error = err instanceof Error ? err.message : String(err);
    }
  }

  private async grantFamilyFromInput(): Promise<void> {
    const input = this.shadowRoot?.querySelector(
      '.grant-family-input',
    ) as HTMLInputElement | null;
    const family = input?.value?.trim();
    if (!family) return;
    try {
      const res = await this.doFetch('/api/authorizations/grants', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        // The agent loop runs as UNTRUSTED, so a family grant for that tier is what auto-approves it.
        body: JSON.stringify({ kind: 'FAMILY', target: family, sourceTier: 'UNTRUSTED' }),
      });
      if (res.ok) {
        if (input) input.value = '';
        await this.loadDurableGrants();
      }
    } catch (err) {
      this.error = err instanceof Error ? err.message : String(err);
    }
  }

  private renderDurableGrants(): TemplateResult {
    return html`
      <div class="setting-group">
        <h3>Durable grants</h3>
        <p class="help" style="margin: 0 0 0.5rem">
          "Allow always" approvals the trust gate honors without re-prompting — per operation, or for a
          whole capability family (e.g. <code>file-operations</code>). They persist across restarts.
        </p>
        ${this.durableGrants.length === 0
          ? html`<p class="help" style="margin: 0">No durable grants.</p>`
          : html`<ul class="plugin-list">
              ${this.durableGrants.map(
                (g) => html`
                  <li class="plugin-row">
                    <div class="plugin-meta">
                      <div class="plugin-id">
                        ${g.target}
                        <span class="plugin-version"
                          >${g.kind === 'FAMILY' ? 'family' : 'operation'} · ${g.sourceTier}</span
                        >
                      </div>
                    </div>
                    <jf-button
                      variant="danger"
                      label="Revoke"
                      .onActivate=${() =>
                        void this.revokeDurableGrant(g.kind, g.target, g.sourceTier)}
                    >
                      ${icon({ name: 'trash-2', size: 14 })} Revoke
                    </jf-button>
                  </li>
                `,
              )}
            </ul>`}
        <div
          class="plugin-loader"
          style="margin-top: 0.5rem; display: flex; gap: 0.5rem; align-items: center;"
        >
          <input
            type="text"
            class="filter-input grant-family-input"
            placeholder="Capability family (e.g. file-operations)"
            style="flex: 1; padding: 0.4rem 0.625rem; background: var(--surface-secondary); border: 1px solid var(--border-subtle); border-radius: 0.375rem; color: var(--text-primary); font-size: var(--font-size-sm);"
            @keydown=${(e: KeyboardEvent) => {
              if (e.key === 'Enter') void this.grantFamilyFromInput();
            }}
          />
          <jf-button .onActivate=${() => void this.grantFamilyFromInput()}>
            ${icon({ name: 'shield', size: 12 })} Grant family
          </jf-button>
        </div>
      </div>
    `;
  }

  // ── Run-tier witness (tempdoc 560 §28 Phase 3) ──────────────────────────────────────────────────

  private async loadWitness(): Promise<void> {
    try {
      const res = await this.doFetch('/api/registry/witness');
      if (!res.ok) return;
      const data = (await res.json()) as {
        entries?: Array<{
          kind: string;
          id: string;
          owner: string | null;
          buildWitnessed: boolean;
        }>;
      };
      this.witnessEntries = data.entries ?? [];
    } catch {
      // Best-effort observability; the panel shows nothing if the backend is unreachable.
    }
  }

  private renderWitness(): TemplateResult {
    const runtimeOnly = this.witnessEntries.filter((e) => !e.buildWitnessed).length;
    return html`
      <div class="setting-group">
        <h3>Delivered contributions</h3>
        <p class="help" style="margin: 0 0 0.5rem">
          The live composed registry: every <em>operation</em> from all sources (core, agent-tools,
          workflows, MCP, plugins) plus all <em>plugin-contributed</em> surfaces, resources, prompts,
          channels and shapes. <strong>${runtimeOnly}</strong> ${runtimeOnly === 1 ? 'is' : 'are'}
          runtime-only (live, but absent from the build-time witness snapshot). Core surfaces/resources
          are served at <code>/api/registry/*</code>, not duplicated here. Read-only observability.
        </p>
        ${this.witnessEntries.length === 0
          ? html`<p class="help" style="margin: 0">No live contributions reported.</p>`
          : html`<ul class="plugin-list">
              ${this.witnessEntries.map(
                (e) => html`
                  <li class="plugin-row">
                    <div class="plugin-meta">
                      <div class="plugin-id">
                        ${e.id}
                        <span class="plugin-version"
                          >${e.kind}${e.owner ? ` · ${e.owner}` : ''}</span
                        >
                        ${e.buildWitnessed
                          ? nothing
                          : html`<jf-status-badge
                              tone="warning"
                              title="Live but absent from the build-time witness snapshot"
                              >runtime-only</jf-status-badge
                            >`}
                      </div>
                    </div>
                  </li>
                `,
              )}
            </ul>`}
      </div>
    `;
  }

  private renderPluginPermissions(): TemplateResult | typeof nothing {
    if (this.viewerAudience === 'USER') return nothing;
    return html`
      <div class="section">
        <h3>${icon({ name: 'shield', size: 12 })} Plugin permissions</h3>
        <p class="toggle-desc">
          Tempdoc 543 §14.3 β4 / §28.W3 — manage capabilities you've granted
          to plugins. Per-request prompts surface in the lower-left when a
          plugin requests permission; this panel shows the persisted decisions.
        </p>
        ${this.consents.length === 0
          ? html`<p class="toggle-desc">No consent decisions recorded yet.</p>`
          : html`
              <table style="width:100%;border-collapse:collapse;font-size: var(--font-size-sm);">
                <thead>
                  <tr style="text-align:left;color:var(--text-secondary);">
                    <th style="padding:0.25rem 0.5rem;">Plugin</th>
                    <th style="padding:0.25rem 0.5rem;">Capability</th>
                    <th style="padding:0.25rem 0.5rem;">Decision</th>
                    <th style="padding:0.25rem 0.5rem;">Granted</th>
                    <th style="padding:0.25rem 0.5rem;"></th>
                  </tr>
                </thead>
                <tbody>
                  ${this.consents.map(
                    (c) => html`
                      <tr data-consent-row="${c.contributorId}:${c.capability}">
                        <td style="padding:0.25rem 0.5rem;font-family:var(--font-mono);">${c.contributorId}</td>
                        <td style="padding:0.25rem 0.5rem;font-family:var(--font-mono);">${c.capability}</td>
                        <td style="padding:0.25rem 0.5rem;">
                          <select
                            data-testid="consent-decision-select"
                            .value=${c.decision}
                            @change=${(e: Event) =>
                              void this.handleConsentDecisionChange(
                                c.contributorId,
                                c.capability,
                                (e.target as HTMLSelectElement).value,
                              )}
                          >
                            <option value="allow-always" ?selected=${c.decision === 'allow-always'}>allow-always</option>
                            <option value="deny" ?selected=${c.decision === 'deny'}>deny</option>
                          </select>
                        </td>
                        <td style="padding:0.25rem 0.5rem;color:var(--text-tertiary);">
                          ${new Date(c.decidedAt).toLocaleString()}
                        </td>
                        <td style="padding:0.25rem 0.5rem;">
                          <button
                            class="option-btn"
                            @click=${() =>
                              void this.handleRevokeConsent(c.contributorId, c.capability)}
                          >
                            Revoke
                          </button>
                        </td>
                      </tr>
                    `,
                  )}
                </tbody>
              </table>
            `}
      </div>
    `;
  }

  private async handleRevokeConsent(
    contributorId: string,
    capability: string,
  ): Promise<void> {
    const { revokeConsent, listAllConsents } = await import(
      '../substrates/consent/index.js'
    );
    revokeConsent(contributorId, capability);
    this.consents = [...listAllConsents()];
    this.requestUpdate();
  }

  private async handleConsentDecisionChange(
    contributorId: string,
    capability: string,
    decision: string,
  ): Promise<void> {
    if (decision !== 'allow-always' && decision !== 'deny') return;
    const { recordConsent, listAllConsents } = await import(
      '../substrates/consent/index.js'
    );
    recordConsent(contributorId, capability, decision);
    this.consents = [...listAllConsents()];
    this.requestUpdate();
  }

  private renderWorkspaceProfilesDeveloper(): TemplateResult | typeof nothing {
    if (this.viewerAudience !== 'DEVELOPER') return nothing;
    return html`
      <div class="section">
        <h3>${icon({ name: 'layers', size: 12 })} Workspace Profiles (developer)</h3>
        <p class="toggle-desc">
          Tempdoc 543 §13.6 substrate — snapshot the current Scope into
          a named WorkspaceProfile; switch back later to restore. §25.ζ#4
          picker lets you choose a parent profile so the new profile
          inherits manifest set + Scope under set-arithmetic semantics.
        </p>
        <div class="toggle-row">
          <select
            data-testid="workspace-profile-inherits-from"
            @change=${(e: Event) => {
              this.snapshotInheritsFrom = (e.target as HTMLSelectElement).value;
              this.requestUpdate();
            }}
          >
            <option value="">— No parent (flat profile) —</option>
            ${this.workspaceProfiles.map(
              (p) => html`<option value=${p.id} ?selected=${this.snapshotInheritsFrom === p.id}>${p.label}</option>`,
            )}
          </select>
          <button
            class="option-btn"
            @click=${() => void this.handleSnapshotWorkspaceProfile()}
          >
            Snapshot current as Profile
          </button>
          <select
            data-testid="workspace-profile-switcher"
            @change=${(e: Event) => {
              const id = (e.target as HTMLSelectElement).value;
              if (id) void this.handleActivateWorkspaceProfile(id);
            }}
          >
            <option value="">— Activate a profile —</option>
            ${this.workspaceProfiles.map(
              (p) => html`<option value=${p.id}>${p.label}</option>`,
            )}
          </select>
        </div>
      </div>
    `;
  }

  private async handleSnapshotWorkspaceProfile(): Promise<void> {
    const { createProfileFromCurrent, listProfiles: listWorkspaceProfiles } =
      await import('../substrates/profiles/index.js');
    const id = `dev.profile.${Date.now()}`;
    const label = `Snapshot @ ${new Date().toLocaleTimeString()}`;
    // §25.ζ#4 — propagate the picker selection. createProfileFromCurrent's
    // 3rd-arg `overrides` accepts `inheritsFrom`.
    const overrides: { description: string; inheritsFrom?: string } = {
      description: 'Created via SettingsSurface developer affordance',
    };
    if (this.snapshotInheritsFrom) {
      overrides.inheritsFrom = this.snapshotInheritsFrom;
    }
    createProfileFromCurrent(id, label, overrides);
    this.workspaceProfiles = [...listWorkspaceProfiles()];
    this.requestUpdate();
  }

  private async handleActivateWorkspaceProfile(id: string): Promise<void> {
    const { activateProfile } = await import('../substrates/profiles/index.js');
    try {
      await activateProfile(id);
    } catch (err) {
      // eslint-disable-next-line no-console
      console.warn(`[SettingsSurface] activateProfile('${id}') failed`, err);
    }
  }
}

if (typeof customElements !== 'undefined' && !customElements.get('jf-settings-surface')) {
  customElements.define('jf-settings-surface', SettingsSurface);
}
