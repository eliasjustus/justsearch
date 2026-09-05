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

import { z } from 'zod';
import type { Presentation, Provenance } from './registry.js';
import type { Altitude } from '../generated/registry-enums.generated.js';
import type { SurfaceWire } from '../generated/schema-types/surface.js';
import { surfaceWireSchema } from '../generated/schema-types/surface.js';
export { surfaceWireSchema };
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
 * so a new zone appears in this type automatically. A new zone's landmark-role mapping
 * (shell-v0/display/landmarks.ts) is a review-tier obligation (tempdoc 930 chunk H retired the
 * static `check-a11y-closure` gate that used to assert this by parsing the list below).
 */
export type Placement = SurfaceWire['placement'];

/**
 * Closed list useful for exhaustive switches.
 *
 * This is a runtime VALUE, which is why it is hand-maintained rather than projected.
 *
 * The `readonly Placement[]` annotation checks only that every entry IS a `Placement` — a subset
 * type-checks fine, so it cannot catch a dropped or newly-added zone. `PLACEMENT_CLOSURE` below is
 * what makes that a compile error.
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

/**
 * Compile-time closure over the generated `Placement` union: every member must appear as a key.
 * Adding a zone in Java (`Placement.java`) regenerates the union, and this declaration then fails to
 * type-check until the new zone is listed here AND in `PLACEMENTS` above — which is what forces the
 * a11y gate to see it and demand a landmark-role mapping.
 *
 * Type-level only; `void`-consumed so it is not an unused binding. It is deliberately NOT exported:
 * the registry barrel may not declare a second hand-authored wire shape (`contribution-surface`
 * gate), and this is an assertion, not a shape.
 */
const PLACEMENT_CLOSURE: Record<Placement, true> = {
  COMMAND: true,
  RAIL: true,
  STAGE: true,
  HUD: true,
  STATUS: true,
  DRAWER: true,
  MODAL: true,
  DEEPLINK: true,
  HEADLESS_AGENT_TOOL: true,
};
void PLACEMENT_CLOSURE;

// ============================================================
// Common value types
// ============================================================

/**
 * Surface id. Serialized as a bare string (`SurfaceRef.java`'s `@JsonValue`).
 * Pattern: `core.<name>` or `vendor.<plugin-id>.<name>` — the generated schema carries the regex.
 */
export type SurfaceRef = SurfaceWire['id'];

/**
 * Typed cross-reference graph from a Surface to the primitives it consumes — a direct projection of
 * the wire's `consumes` object.
 *
 * `SurfaceConsumes.java` implements `PreciseWire` and its compact constructor defaults every `Set`
 * to `Set.of()`, so the generated schema marks all five graphs `required` and non-null. Nothing is
 * restated here: the field set, its cardinality and its nullability all come from Java
 * mechanically, so a renamed or removed component changes this type rather than silently diverging
 * from it.
 *
 * Slice 491 §9.D Phase E (C0): `conversationShapes` joins the graph. Surfaces hosting a chat shape
 * (e.g. `core.agent-surface` consumes `core.agent`) declare it here, and `<jf-chat-shape-mount>`
 * reads it to resolve which shape's view factory to instantiate. It is the ONE key relaxed to
 * optional at the FE boundary — client-side Surface literals (plugin contributions, test fixtures)
 * predate Phase E and omit it, even though the wire always carries it. Every other key comes
 * through `Omit` untouched, so the required set is derived by exclusion rather than restated.
 */
export type SurfaceConsumes = Omit<SurfaceWire['consumes'], 'conversationShapes'> &
  Partial<Pick<SurfaceWire['consumes'], 'conversationShapes'>>;

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

/**
 * Runtime validator for the Surface catalog envelope (tempdoc 884; the 560 §4c Phase B parse
 * boundary, extended to the Surface Manifest). Each entry is validated by the GENERATED
 * `surfaceWireSchema` — the single runtime authority for the Surface wire shape.
 */
export const surfaceCatalogSchema = z.object({
  $schema: z.string().optional(),
  schemaVersion: z.string(),
  catalogVersion: z.number(),
  namespace: z.string(),
  primitive: z.literal('Surface'),
  entries: z.array(surfaceWireSchema),
});
