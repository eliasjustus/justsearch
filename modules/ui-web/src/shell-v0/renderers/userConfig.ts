// SPDX-License-Identifier: Apache-2.0
/**
 * Slice 3a.1.7 — User-supplied dispatch and rendering overrides.
 *
 * Threaded through dispatch + child-renderer construction so per-pane
 * settings, plugin substitution, density override, and theme-variant
 * override can flow without consumer-side surgery later.
 *
 * V1 shape — schema-versioned per
 * `docs/reference/ui/frontend-kernel/kernel/01-runtime-contracts.md`
 * §"Versioning". Future versions may add fields; existing consumers
 * ignore unknown fields. Removing or renaming a field is a breaking
 * change.
 *
 * Foundation-correctness lock #2 (per
 * `60-migration-history/09-foundation-correctness-audit.md` Concern 2):
 * adding this channel later would require refactoring every renderer
 * + Form + Table + view consumer that has coupled to V0 dispatch.
 * Locking the API shape now means downstream consumers thread it
 * unconditionally from day one.
 */

/** Density override; affects component-token resolution via the semantic-token tier. */
export type DensityVariant = 'compact' | 'comfortable' | 'spacious';

/**
 * Per-pane user-supplied config. Forms, Tables, and plugin views
 * read their own slice of this map by pane id.
 */
export interface PaneUserConfig {
  /** Column ordering for tables. ids must match column ids. */
  columnOrder?: string[];
  /** Hidden field ids for forms / tables. */
  hiddenFields?: string[];
  /** Default filters. Free-form per pane. */
  defaultFilters?: Record<string, unknown>;
  /** Free-form pane-specific settings. */
  custom?: Record<string, unknown>;
}

/**
 * Top-level user config threaded through dispatch + render. Optional
 * everywhere; omitted = use V0 defaults. The `version` field gates
 * future schema evolution.
 */
export interface RendererUserConfig {
  /** Schema version of this config object. Currently always 1. */
  version: 1;

  /**
   * Plugin substitution map. Keys are surface-port ids OR
   * `"renderer:<schema-pointer>"` for fine-grained per-schema-pointer
   * renderer overrides. Values are plugin tag names.
   *
   * The dispatcher consults this map first; if the user has explicitly
   * chosen a renderer tag for the dispatch point, return it directly
   * (skip rank-based dispatch). Falls through to rank-based dispatch
   * on no override.
   */
  rendererOverride?: Record<string, string>;

  /**
   * Per-pane configuration. Keyed by pane id (the Lumino Shell's
   * pane registry assigns these). Forms and Tables read this for
   * column ordering, hidden fields, filter defaults.
   */
  paneConfig?: Record<string, PaneUserConfig>;

  /**
   * Density override. Effects component-token resolution via the
   * semantic-token tier (slice 3a.1.1).
   */
  density?: DensityVariant;

  /**
   * 569 §19 Seam 4 — the user-selected accessibility/adaptation axes the projection layer reads.
   * `contrast` toggles the global `.high-contrast` class; `motion: 'reduced'` toggles `.motion-reduced`
   * (forces reduced-motion + quiets liveness). Density is the existing `density` field above (it threads
   * to renderers via the DensityController). `state/adaptationProfile.ts applyAdaptationProfile` is the
   * ONE authority that writes all three + projects them to global DOM state; the cascade does the rest,
   * so an adaptation axis is O(1) and total across every present + future surface.
   */
  accessibilityProfile?: {
    contrast?: 'normal' | 'high';
    motion?: 'full' | 'reduced';
  };

  /**
   * Theme variant per surface. Optional override of the default
   * theme for specific panes (e.g., "this pane uses the dark
   * variant even if the host is in light mode"). Keyed by pane id.
   */
  themeVariant?: Record<string, string>;

  /**
   * Slice 471 (V1.5) — per-Surface override map. Both keys AND values
   * are SurfaceIds. The key is the canonical surface id the user
   * wants to replace (e.g., `core.library-surface`); the value is the
   * SurfaceRef of the alternative surface — most commonly a plugin-
   * contributed surface in the same SurfaceCatalog
   * (e.g., `acme.alt-library-surface`). NOT a PluginId — the override
   * resolves through `getSurface(value)` not the registry.
   *
   * Dispatch flow: when the chrome's Stage resolves a SurfaceRef for
   * mounting, it consults this map first. If the user has chosen
   * an alternative surface, the dispatcher resolves THAT surface's
   * mountTag instead. Falls through to the original mountTag when
   * the override target isn't in the live catalog (graceful
   * degradation per `archive/source-tempdocs/421-extensibility.md`
   * §"Failure modes").
   *
   * Symmetric to {@link rendererOverride} — that channel overrides
   * per-dispatch-point renderer choices; this channel overrides
   * whole-surface choices. Provenance UI (Popover top-layer per
   * `slices/470-v1-5-user-ui-authorship-substrate.md` §B.A.4) makes
   * any active override visible at all times.
   */
  surfaceOverride?: Record<string, string>;

  /**
   * Slice 472 (V1.5) — per-Surface visibility map. Keys are
   * SurfaceRef; `false` hides the surface from the rail (without
   * uninstalling its contributor); absent entries default to true.
   *
   * Composes with {@link surfaceOrder}: the chrome's rail filter
   * applies visibility first, then orders the remaining surfaces.
   */
  surfaceVisibility?: Record<string, boolean>;

  /**
   * Slice 472 (V1.5) — explicit rail order. Array of SurfaceRef in
   * the order the user wants them displayed in the rail. Surfaces
   * not listed appear after the ordered list in catalog order;
   * surfaces in the list but not in the catalog (uninstalled) are
   * silently skipped (graceful degradation).
   */
  surfaceOrder?: string[];

  /**
   * Slice 472 (V1.5) — active layout id. V1.5 ships a single
   * default layout (kernel-defined zones + catalog order); this
   * field reserves the slot for plugin-contributed Layouts when
   * the LayoutCatalog backend ships (V1.5.1 / V1.6). Unused in V1.5
   * minimum-viable — surfaceVisibility + surfaceOrder cover the
   * actual user-authorship channel for now.
   */
  activeLayoutId?: string;

  /**
   * Tempdoc 521 §16.7 deeper — secondary active surface for the
   * split-stage layout (`core.split`). When the active layout's
   * `stage.exclusive === false`, Shell renders the rail-selected
   * surface in the left pane and the surface named here in the right
   * pane. Unused (silently ignored) by exclusive layouts. Defaults to
   * the second registered surface in the rail when undefined.
   */
  secondaryActiveSurface?: string;
}
