// SPDX-License-Identifier: Apache-2.0
/**
 * Surface Manifest types — a **composing barrel** over the generated wire projection.
 *
 * Tempdoc 884 (ADR-0038 amendment) retired the hand-written mirror that used to live here.
 * The authority is now `SSOT/schemas/surface.v1.json`, emitted from
 * `modules/app-agent-api/.../registry/Surface.java` by `SubstrateSchemaGenTest` and projected to
 * TypeScript by `scripts/codegen/gen-wire-schema-types.mjs` (registered in
 * `governance/contract-surfaces.v1.json` as `SurfaceWire`). Regenerate with
 * `./gradlew.bat :modules:app-api:updateSchemas` then
 * `node scripts/codegen/gen-wire-schema-types.mjs`; `check-wire-schema-types-regen` fails on drift.
 *
 * This file is not a mirror and must not become one again. Everything below is one of:
 *  1. a re-export or projection of `SurfaceWire` (the field SET comes from Java, mechanically);
 *  2. a **client-side-only** concern the backend never sends — `factory` (478 §4.A dispatch token,
 *     minted by the catalog at boot) and `splitPairing` (521 §22 Phase D, a plugin contribution
 *     merged client-side; it has zero Java source);
 *  3. a closed vocabulary list (`AUDIENCES`, `PLACEMENTS`) that exists as a runtime value, which a
 *     type projection cannot provide.
 *
 * Per slice 449 §0 D1: Surface is a **Manifest** (composition over primitives), not a fifth
 * primitive. Its catalog is parallel-but-distinct from the per-primitive catalogs
 * (Resource / Operation / Prompt / DiagnosticChannel). Same pattern as `registry.ts`'s
 * `ResourceWire` / `OperationWire` barrels.
 */

import type { Presentation, Provenance } from './registry.js';
import type { Altitude } from '../generated/registry-enums.generated.js';
import type { SurfaceWire } from '../generated/schema-types/surface.js';
export { surfaceWireSchema } from '../generated/schema-types/surface.js';
export type { SurfaceWire };

export type { Altitude };

// ============================================================
// Discriminator vocabularies — projected from the wire
// ============================================================

/**
 * Access-control audience axis, projected from `SurfaceWire`.
 *
 * Trust ordering for the audience-composition rule (slice 449 §0 D2):
 * `USER < OPERATOR < DEVELOPER`. AGENT is excluded from the comparison —
 * agent surfaces are consumed by headless tool APIs, not human chrome.
 */
export type Audience = SurfaceWire['audience'];

/** Closed list useful for exhaustive switches and test fixtures. */
export const AUDIENCES: readonly Audience[] = [
  'USER',
  'AGENT',
  'OPERATOR',
  'DEVELOPER',
] as const;

/**
 * Chrome-zone placement axis, projected from `SurfaceWire`.
 *
 * Adding a value is a Java change (`Placement.java`), not an edit here: the union is generated,
 * so a new zone appears in this type automatically and `check-a11y-closure` then demands a
 * landmark-role mapping for it.
 */
export type Placement = SurfaceWire['placement'];

/**
 * Closed list useful for exhaustive switches.
 *
 * This is a runtime VALUE, which is why it is hand-maintained rather than projected — and it is
 * load-bearing beyond TypeScript: `scripts/ci/check-a11y-closure.mjs` parses this declaration's
 * literal to assert every placement has a landmark role. Keep the declaration's exact shape and
 * its quoted constants; the type annotation makes a value that drifts from `Placement` a compile
 * error, so the two cannot silently diverge. (Do not restate the declaration's syntax anywhere
 * above it in this file: that check takes the FIRST match in the file, so a prose copy of it
 * shadows the real one and the gate reports zero placements.)
 */
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
 * Surface id. Serialized as a bare string (`SurfaceRef.java`'s `@JsonValue`).
 * Pattern: `core.<name>` or `vendor.<plugin-id>.<name>` — the generated schema carries the regex.
 */
export type SurfaceRef = SurfaceWire['id'];

/**
 * Typed cross-reference graph from a Surface to the primitives it consumes.
 *
 * The field set is the wire's; the arrays are narrowed to non-null here because every consumer
 * reads them as lists. `SurfaceConsumes.java` defaults each `Set` to `Set.of()`, so the backend
 * never sends null — but `SurfaceConsumes` is not a `PreciseWire` type, so the generated schema
 * cannot say so and marks them nullable.
 *
 * Slice 491 §9.D Phase E (C0): `conversationShapes` joins the graph. Surfaces hosting a chat shape
 * (e.g. `core.agent-surface` consumes `core.agent`) declare it here, and `<jf-chat-shape-mount>`
 * reads it to resolve which shape's view factory to instantiate.
 */
type ConsumesWire = NonNullable<SurfaceWire['consumes']>;

/**
 * The four graphs every Surface has carried since slice 449. `conversationShapes` (491 §9.D
 * Phase E) stays optional at the FE boundary for back-compat with pre-Phase-E fixtures and plugin
 * contributions, even though the backend always sends it.
 *
 * Listing them is checked, not decorative: if a Java component is renamed or removed,
 * `ConsumesWire[K]` stops resolving and this line is a compile error rather than a silent
 * mismatch.
 */
type RequiredConsumes = 'resources' | 'operations' | 'prompts' | 'diagnosticChannels';

export type SurfaceConsumes = { [K in RequiredConsumes]-?: NonNullable<ConsumesWire[K]> } & {
  [K in Exclude<keyof ConsumesWire, RequiredConsumes>]?: NonNullable<ConsumesWire[K]>;
};

// ============================================================
// Surface manifest
// ============================================================

/**
 * A Surface catalog entry as the FE holds it: the generated wire shape, plus the two client-side
 * fields the backend never sends.
 *
 * `altitude` and `members` are relaxed to optional because the FE constructs Surface objects that
 * predate them (`SurfaceCatalogClient` merge paths, plugin contributions, test fixtures); the wire
 * always provides both, and an absent `altitude` is read as `PRODUCT`, mirroring the Java record's
 * `null → PRODUCT` default. `riskTier` and `stateSchema` are backend concerns that ride the same
 * wire; they are optional here for the same reason.
 */
export type Surface = Omit<
  SurfaceWire,
  | 'presentation'
  | 'provenance'
  | 'consumes'
  | 'altitude'
  | 'members'
  | 'riskTier'
  | 'stateSchema'
> & {
  presentation: Presentation;
  provenance: Provenance;
  consumes: SurfaceConsumes;
  /**
   * Tempdoc 571 — the surface's **altitude**: the governing axis that determines its home (rail
   * band) and its core-vs-plugin eligibility, as a projection of the primary authority it carries.
   * The authoritative declaration and the foreclosures (`TRUST ⟹ CORE`;
   * `channel ⟹ DIAGNOSTIC`) live in the Java `CoreSurfaceCatalog` + the `surface-altitude` gate.
   */
  altitude?: Altitude;
  /**
   * Tempdoc 571 §11 / 578 — the declared host/member composition relationship. A host surface names
   * the member surfaces it presents inside itself (e.g. System hosts Health/Logs/Activity).
   * Membership is the single home-authority: a member is excluded from the rail and its deep-link
   * resolves to the host. Order is the declared tab order. Absent/empty ⇒ this surface hosts
   * nothing. The `surface-composition` gate enforces one-home integrity.
   */
  members?: SurfaceRef[];
  /** Backend-only navigation risk tier (tempdoc 550 WA-4); on the wire, unused by the FE. */
  riskTier?: SurfaceWire['riskTier'];
  /** Backend-declared surface state schema; on the wire, unused by the FE. */
  stateSchema?: SurfaceWire['stateSchema'];
  /**
   * Tempdoc 521 §22 Phase D — declarative split pairing. When this surface is the primary pane of
   * a split-stage layout and the user has not chosen a secondary surface explicitly, `Shell` reads
   * `splitPairing.secondary` as the curated default. Plugins can declare their preferred pair
   * without touching host code. Absent ⇒ no curated pair; fallback to "first non-primary rail
   * surface."
   *
   * **Client-side only** — no Java counterpart exists; it is merged in by
   * `mergePluginSurfaceContributions`, never served.
   */
  splitPairing?: {
    secondary: string;
  };
  /**
   * 478 §4.A — SurfaceFactory minted by the FE catalog at boot/merge time. The factory captures the
   * validated mountTag in a closure; consumers (Stage.render()) call factory.mount() to construct
   * the surface element WITHOUT going through template-string interpolation.
   *
   * **Client-side only** — wire-shape Surfaces arrive with `factory` undefined and the catalog
   * stamps it on receipt. Stage falls back to the legacy `mountTag` + customElements.get path when
   * it is absent (transitional; V1.5.2 marks factory required).
   */
  factory?: SurfaceFactory;
};

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
