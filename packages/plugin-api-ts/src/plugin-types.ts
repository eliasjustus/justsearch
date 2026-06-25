/**
 * Shell V0 Plugin API — V1 contract types.
 *
 * Per slice 3a.1 §"Slice 3a.1" Phase 7: Stage 3a ships a plugin
 * skeleton (no loader, no signing). Plugins are statically compiled
 * into the bundle for V1; the loader + signing surfaces ship in V1.5+
 * (see archive/source-tempdocs/421-extensibility.md).
 *
 * The contract here is the **shape** plugins must conform to. The
 * registry keeps a map from plugin id → manifest; the host
 * iterates the registry to dispatch plugin-defined surfaces.
 */

/**
 * The plugin contract version this V1.1 host accepts. Plugins
 * declare their target version in `manifest.contractVersion`; the
 * registry validates compatibility at install time.
 *
 * Comparison rule (slice 3a.1.6): exact major + ≥minor. Host 1.1
 * accepts plugin 1.0 (with deprecation warning) or 1.1; rejects
 * 0.x / 2.x. Plugins authored against 1.0 work as register-only
 * (no `unregister`); the registry's `uninstall` path treats absent
 * `unregister` as a no-op teardown.
 */
export const PLUGIN_CONTRACT_VERSION = '1.1';

/**
 * V1.1 plugin manifest. Slice 3a.1.6 added the V1.1 fields
 * (`contractVersion`, `tagNamespace`, optional `unregister`) to lock
 * plugin lifecycle before plugin authors couple to V1's
 * register-only surface. See foundation-correctness audit Concern 3.
 *
 *  - id: stable identifier; used by the registry as the key.
 *  - version: semver; the host can decline to load if range mismatches.
 *  - displayName: human-facing label.
 *  - capabilities: feature-detect-driven advertisements; see below.
 *  - register(host): one-shot setup hook; the plugin registers its
 *    custom elements and surface-port handlers via the host.
 *  - contractVersion (V1.1): plugin contract version this plugin
 *    targets. Validated at install time against
 *    `PLUGIN_CONTRACT_VERSION`.
 *  - tagNamespace (V1.1): custom-element tag prefix this plugin
 *    uses. Must equal `id` exactly. Validated at install time.
 *  - unregister (V1.1, optional): teardown hook called by
 *    `uninstall(id)`. Plugins authored at V1 (no `unregister`)
 *    work unchanged; `uninstall` treats absent hook as a no-op.
 */
export interface PluginManifest {
  id: string;
  version: string;
  displayName: string;
  capabilities: PluginCapabilities;
  register: PluginRegisterFn;

  // V1.1 additions (slice 3a.1.6):
  contractVersion: string;
  tagNamespace: string;
  unregister?: PluginUnregisterFn;

  /**
   * Per-Category contract version map (slice 3a-1-8 Phase 6, optional).
   *
   * V1 plugins declare {@link contractVersion} (single string for the plugin
   * Category). Plugins that consume wire-Category types additionally declare
   * `contractVersions: { wire: "0.1" }`; the host's
   * `PluginRegistry.installFromHandshake` validates each declared Category
   * against the handshake's `serverCapabilities.contractVersions` map.
   *
   * Forward-compat: V1.1 plugins declaring only the legacy
   * {@link contractVersion} string continue to work (legacy single-Category
   * declaration treated as plugin-Category only). V2+ plugins declaring
   * `contractVersions` get multi-Category validation.
   */
  contractVersions?: Record<string, string>;

  /**
   * Slice 471 / 449 phase 12 — i18n translations for the plugin's
   * declared `labelKey` / `descriptionKey` strings (and any custom keys
   * the plugin's surfaces / operations consume). Keyed by locale code
   * (e.g., `'en'`, `'de'`); each value is a `{ key: translation }`
   * map merged into the existing resource catalog at install time
   * via `registerCatalogEntries`.
   *
   * Existing catalog keys are NOT overwritten — server-fetched
   * translations win over plugin-supplied ones (plugins can ship
   * defaults; the host catalog is authoritative for shared keys).
   *
   * V1.5 alpha shape: simple flat record. V1.5.1 polish may add
   * pluralization / interpolation support per Lingui conventions.
   */
  translations?: Record<string, Record<string, string>>;
}

/**
 * Capabilities a plugin advertises to the host. Each axis is optional;
 * absence means the plugin does not contribute that axis. The host
 * iterates the registry, skipping plugins that don't advertise the
 * axis it's currently dispatching.
 *
 * V1 axes:
 *  - customElementTags: list of tags the plugin registers via
 *    `customElements.define` during `register()`. The host uses this
 *    list to surface plugin-defined elements in the dispatcher.
 *  - surfacePorts: list of surface-port ids the plugin contributes
 *    (e.g., 'health.condition', 'workspace.editor'). Surface ports
 *    are the V0 extension axis — a host slot that asks "which plugin
 *    renders this view?".
 */
export interface PluginCapabilities {
  customElementTags?: string[];
  surfacePorts?: string[];
  /**
   * Slice 449 phase 12 — Surface Manifest entries the plugin
   * contributes. Each entry is a `core.<id>`-shaped catalog row that
   * the host's SurfaceCatalog merges with `CoreSurfaceCatalog` at
   * boot. Audience floor enforcement (per slice 449 §0 D2):
   *  - TrustTier CORE / TRUSTED_PLUGIN -> Audience.USER floor
   *  - TrustTier UNTRUSTED_PLUGIN -> Audience.OPERATOR floor
   * The host applies the floor at registration time; declared
   * entries with a lower-than-floor audience are silently
   * promoted (not rejected — registration shouldn't fail on a
   * misconfigured audience).
   */
  surfaces?: PluginSurfaceContribution[];
}

/**
 * Plugin-contributed Surface Manifest entry. Mirrors the host-side
 * `Surface` record shape but uses string ids (the host turns these
 * into `SurfaceId` value-types and validates with `SurfaceAreaValidator`
 * before merging into the live catalog).
 */
export interface PluginSurfaceContribution {
  /** Catalog id, e.g., `acme-tools.dashboard-surface`. */
  id: string;
  /** Lit custom-element tag name the plugin registers. */
  mountTag: string;
  /** I18n keys for label + description. Plugin owns the catalog. */
  labelKey: string;
  descriptionKey: string;
  /** Declared audience; the host promotes to floor when needed. */
  audience: 'USER' | 'AGENT' | 'OPERATOR' | 'DEVELOPER';
  /** Where in the chrome the surface mounts. */
  placement:
    | 'COMMAND'
    | 'RAIL'
    | 'STAGE'
    | 'HUD'
    | 'STATUS'
    | 'DRAWER'
    | 'MODAL'
    | 'DEEPLINK'
    | 'HEADLESS_AGENT_TOOL';
  /** Operations / Resources / Prompts / DiagnosticChannels consumed. */
  consumes?: {
    resources?: string[];
    operations?: string[];
    prompts?: string[];
    diagnosticChannels?: string[];
  };
}

/**
 * The plugin's setup hook. Called once at host startup. Plugins
 * register custom elements and surface-port handlers via the
 * `host` object. The host catches errors thrown from `register` and
 * skips the plugin (the failure is logged; other plugins continue).
 */
export type PluginRegisterFn = (host: PluginHostApi) => void;

/**
 * The plugin's optional teardown hook (V1.1+). Called by
 * `PluginRegistry.uninstall(id)` when the plugin is removed at
 * runtime (user disable, hot-reload). Plugins clean up any
 * registry-level state they registered in `register`: surface-
 * port handlers, custom-element tag dispatch entries, open
 * connections.
 *
 * Renderers' own `disconnectedCallback` covers DOM teardown
 * automatically; this hook covers registry-level cleanup that
 * outlives the DOM (e.g., closing an EnvelopeStream the plugin
 * opened in `register`).
 *
 * `unregister` is sync; if a plugin needs async cleanup, fire
 * the work and return — the registry doesn't await.
 */
export type PluginUnregisterFn = (host: PluginHostApi) => void;

/**
 * The slice of host functionality plugins are allowed to call during
 * `register`. Deliberately small — V1 only needs surface-port
 * handler registration (custom-element registration is via the
 * platform's `customElements.define` and doesn't need a host hook).
 */
export interface PluginHostApi {
  /**
   * Register a handler for a surface-port id. The host invokes the
   * handler when it needs to render that surface.
   */
  registerSurfacePort: (
    id: string,
    handler: SurfacePortHandler,
  ) => void;
  /**
   * V1.1 (slice 3a.1.6): the tagNamespace the host has assigned
   * this plugin. Equals `manifest.id`. Plugins use this to verify
   * their own namespace at runtime (debug aid; the install-time
   * validation already enforced equality).
   */
  installedTagNamespace: string;
}

/** Handler invoked by the host to render a surface port. */
export type SurfacePortHandler = (
  context: SurfacePortContext,
) => HTMLElement | null;

/** Context passed to a surface-port handler. */
export interface SurfacePortContext {
  /** The surface port id (e.g., 'health.condition'). */
  portId: string;
  /** Optional payload the host passes (e.g., the HealthEvent for a
   *  health.condition port). Plugin-defined; host doesn't validate. */
  payload?: unknown;
}

