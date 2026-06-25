// SPDX-License-Identifier: Apache-2.0
/**
 * Slice 449 phase 5 — hand-written TypeScript types for the Surface Manifest
 * primitive and its nested value types.
 *
 * Mirrors `modules/app-agent-api/src/main/java/io/justsearch/agent/api/registry/`
 * (Surface.java, SurfaceRef.java, Audience.java, Placement.java,
 * SurfaceConsumes.java).
 *
 * Per slice 449 §0 D1: Surface is a **Manifest** (composition over primitives),
 * not a fifth primitive. Surface's catalog is parallel-but-distinct from the
 * per-primitive catalogs (Resource / Operation / Prompt / DiagnosticChannel).
 *
 * Wire-shape conventions match the Resource / DiagnosticChannel types:
 *  - Java enums serialize as bare strings.
 *  - SurfaceRef serializes as a bare string (Java record's @JsonValue).
 *  - Set<X> serializes as `X[]`.
 */

import type { OperationRef, Presentation, Provenance } from './registry.js';
import type { DiagnosticChannelRef } from './diagnostic.js';
import type { Altitude } from '../generated/registry-enums.generated.js';

export type { Altitude };

// ============================================================
// Discriminator vocabularies (mirror Java enums)
// ============================================================

/**
 * Access-control audience axis. Mirrors `Audience.java`.
 *
 * Trust ordering for the audience-composition rule (slice 449 §0 D2):
 * `USER < OPERATOR < DEVELOPER`. AGENT is excluded from the comparison —
 * agent surfaces are consumed by headless tool APIs, not human chrome.
 */
export type Audience = 'USER' | 'AGENT' | 'OPERATOR' | 'DEVELOPER';

/** Closed list useful for exhaustive switches and test fixtures. */
export const AUDIENCES: readonly Audience[] = [
  'USER',
  'AGENT',
  'OPERATOR',
  'DEVELOPER',
] as const;

/**
 * Chrome-zone placement axis. Mirrors `Placement.java`.
 *
 * V1 enum maps to today's React `<GlassShell>` 5-zone layout plus a few
 * audience-specific values (DEEPLINK, HEADLESS_AGENT_TOOL). Lumino-class
 * placement values land when the 3a.6+ runtime cutover ships.
 */
export type Placement =
  | 'COMMAND'
  | 'RAIL'
  | 'STAGE'
  | 'HUD'
  | 'STATUS'
  | 'DRAWER'
  | 'MODAL'
  | 'DEEPLINK'
  | 'HEADLESS_AGENT_TOOL';

/** Closed list useful for exhaustive switches. */
export const PLACEMENTS: readonly Placement[] = [
  'COMMAND',
  'RAIL',
  'STAGE',
  'HUD',
  'STATUS',
  'DRAWER',
  'MODAL',
  'DEEPLINK',
  'HEADLESS_AGENT_TOOL',
] as const;

// ============================================================
// Common value types
// ============================================================

/**
 * Surface id. Serialized as a bare string. Mirrors `SurfaceRef.java`.
 * Pattern: `core.<name>` or `vendor.<plugin-id>.<name>`.
 */
export type SurfaceRef = string;

/**
 * Typed cross-reference graph from a Surface to the primitives it consumes.
 * Mirrors `SurfaceConsumes.java`.
 *
 * Slice 491 §9.D Phase E (C0): adds `conversationShapes` to the consumption
 * graph. Surfaces hosting a chat shape (e.g., `core.agent-surface` consumes
 * `core.agent`; `core.ask-surface` consumes `core.ask`) declare it here. The
 * FE `<jf-chat-shape-mount>` reads this field to resolve which shape's view
 * factory to instantiate. Optional for back-compat with pre-Phase-E entries.
 */
export interface SurfaceConsumes {
  resources: OperationRef[];
  operations: OperationRef[];
  prompts: OperationRef[];
  diagnosticChannels: DiagnosticChannelRef[];
  /** Slice 491 §9.D Phase E — ConversationShape refs hosted by this surface. */
  conversationShapes?: string[];
}

// ============================================================
// Surface manifest
// ============================================================

/**
 * Wire shape of a Surface catalog entry. Mirrors
 * `modules/app-agent-api/.../Surface.java`.
 */
export interface Surface {
  id: SurfaceRef;
  presentation: Presentation;
  audience: Audience;
  placement: Placement;
  consumes: SurfaceConsumes;
  mountTag: string;
  provenance: Provenance;
  /**
   * Tempdoc 571 — the surface's **altitude**: the governing axis that determines its
   * home (rail band) and its core-vs-plugin eligibility, as a projection of the primary
   * authority it carries. Optional at the FE boundary: the wire always provides it (Jackson
   * serializes the Java record's field, which defaults to `PRODUCT`), and consumers treat an
   * absent value as `PRODUCT` — mirroring the backend record's `null → PRODUCT` default. The
   * authoritative declaration and the foreclosures (`TRUST ⟹ CORE`; `channel ⟹ DIAGNOSTIC`)
   * live in the Java `CoreSurfaceCatalog` + the `surface-altitude` gate.
   */
  altitude?: Altitude;
  /**
   * Tempdoc 571 §11 / 578 — the declared host/member composition relationship. A host surface
   * names the member surfaces it presents inside itself (e.g. System hosts Health/Logs/Activity).
   * Membership is the single home-authority: a member is excluded from the rail and its deep-link
   * resolves to the host. Order is the declared tab order. Absent/empty ⇒ this surface hosts
   * nothing. Authored in the Java `CoreSurfaceCatalog`; the wire is the authority (CorePlugin omits
   * it). The `surface-composition` gate enforces one-home integrity.
   */
  members?: SurfaceRef[];
  /**
   * Tempdoc 521 §22 Phase D — declarative split pairing. When this
   * surface is the primary pane of a split-stage layout and the user
   * has not chosen a secondary surface explicitly, `Shell` reads
   * `splitPairing.secondary` as the curated default. Plugins can
   * declare their preferred pair without touching host code.
   * Absent ⇒ no curated pair; fallback to "first non-primary rail
   * surface."
   */
  splitPairing?: {
    secondary: string;
  };
  /**
   * 478 §4.A — SurfaceFactory minted by the FE catalog at
   * boot/merge time. The factory captures the validated mountTag
   * in a closure; consumers (Stage.render()) call factory.mount()
   * to construct the surface element WITHOUT going through
   * template-string interpolation.
   *
   * Optional (forward-compat): wire-shape Surfaces from the
   * server initially have factory=undefined; the catalog stamps
   * it on receipt. Plugin contributions get factory stamped at
   * `mergePluginSurfaceContributions` time. Stage falls back to
   * the legacy `mountTag` + customElements.get path when factory
   * is absent (transitional; V1.5.2 marks factory required).
   */
  factory?: SurfaceFactory;
}

/**
 * 478 §4.A — opaque dispatch token. The catalog is the only mint-
 * site; consumers cannot construct a SurfaceFactory because they
 * cannot reach the module-private WeakSet of valid factories.
 *
 * Two-layer brand model (post-reviewer-pass critical-analysis):
 *
 * 1. **Type-level brand** — the `__dispatchBrand: symbol` field
 *    prevents accidental constructions like `{mount: () => el}`
 *    from satisfying the SurfaceFactory type. Consumers can READ
 *    the symbol off a real factory but the type system rejects
 *    objects without the field.
 *
 * 2. **Runtime brand verification** — the catalog's
 *    `mountSurface()` helper checks WeakSet membership. The
 *    WeakSet is module-private inside SurfaceCatalogClient; a
 *    consumer with only a factory reference cannot insert into
 *    it. So stealing `__dispatchBrand` from a real factory and
 *    forging `{__dispatchBrand: stolen, mount: () => evilEl}`
 *    passes the type check (Symbol matches) but FAILS the
 *    runtime verification (forged factory not in WeakSet).
 *
 * The factory's mount() returns an HTMLElement constructed from
 * the registered custom element. customElements.get() validates
 * the tag is a properly-registered name (HTML spec PCEN regex);
 * this validation is encapsulated inside the factory closure
 * rather than exposed to consumers.
 */
export interface SurfaceFactory {
  /** Type-level brand — see SurfaceFactory doc comment. */
  readonly __dispatchBrand: symbol;
  /**
   * Mount the surface as a fresh HTMLElement. Throws if the
   * registered class can't be constructed (degenerate edge case;
   * customElements.get returned a class but `new klass()` threw).
   */
  mount(opts?: { apiBase?: string; host_?: unknown }): HTMLElement;
}

/**
 * Catalog envelope returned by `GET /api/registry/surfaces`.
 * Wire shape mirrors `RegistryController.handleSurfaces`.
 */
export interface SurfaceCatalog {
  $schema?: string;
  schemaVersion: string;
  catalogVersion: number;
  namespace: string;
  primitive: 'Surface';
  entries: Surface[];
}
