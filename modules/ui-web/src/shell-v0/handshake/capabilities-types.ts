// SPDX-License-Identifier: Apache-2.0
/**
 * Shell V0 capabilities handshake — hand-written TS view of the
 * `/infra/capabilities` payload (slice 443).
 *
 * NOTE: This mirrors `CapabilitiesService.CapabilitiesView` in
 * `modules/app-observability`. Until generated TS types land
 * (workload validation pass 2 §"NEW Gap 9"), keep this file in sync
 * with the Java records by hand. The pass-2 doc is the authoritative
 * tracking item.
 */

/** Per-primitive entry within `serverCapabilities.primitives`. */
export interface PrimitiveDescriptor {
  current: string;
  endpoint: string;
  messageCatalogUrl: string;
  dynamicRegistration: boolean;
}

/**
 * Slice 449 phase 3: per-Manifest entry within
 * `serverCapabilities.manifests`. Same wire shape as
 * {@link PrimitiveDescriptor} but distinct type so future Manifest-
 * specific fields can be added without affecting primitives.
 *
 * Manifests differ from Primitives in the framework's ontology
 * (composition layer over primitives) but the descriptor wire shape
 * is intentionally identical — discovery semantics are the same.
 */
export interface ManifestDescriptor {
  current: string;
  endpoint: string;
  messageCatalogUrl: string;
  dynamicRegistration: boolean;
}

/** I18n capability slot — declares supported message-catalog locales. */
export interface I18nCapability {
  version: number;
  availableLocales: string[];
}

/**
 * Streaming-envelope capability slot — per slice 436 §B.8: FE
 * consumers feature-detect that the server emits the universal SSE
 * envelope (single-frame-event, frameKind discriminator, resume-token
 * semantics).
 *
 * Absence implies the bespoke per-endpoint shape (event-named SSE
 * frames). Version 1 = the slice 436 envelope shape.
 */
export interface StreamingEnvelopeCapability {
  version: number;
}

/** LSP-style capability handshake declaration (slice 443 typed-slot envelope). */
export interface ServerCapabilities {
  primitives: Record<string, PrimitiveDescriptor>;
  catalogVersion: number;
  protocolVersion: string;
  i18n: I18nCapability;
  streamingEnvelope: StreamingEnvelopeCapability;
  /**
   * Per-Category contract version map (slice 3a-1-8 Phase 6).
   *
   * V1: `{ wire: "0.1.0" }`. Future Categories (3a-1-8b plugin Category,
   * 3a-1-8d catalog Categories) add entries without breaking the wire
   * shape. Optional in the type so older host versions still parse if
   * the field is absent (forward-compat per the substrate's LSP
   * soft-fail discipline).
   */
  contractVersions?: Record<string, string>;
  /**
   * Slice 449 phase 3: Manifest tier slot. Manifests are the second
   * tier alongside primitives — they compose primitives into shippable
   * units. V1 ships one entry: `Surface` (slice 449). Plugin reification
   * into the same map is deferred to V1.5 plugin maturity per slice 449
   * §6. Optional in the type per the same forward-compat discipline as
   * `contractVersions`.
   */
  manifests?: Record<string, ManifestDescriptor>;
}

/** Top-level `/infra/capabilities` payload. */
export interface CapabilitiesView {
  schema_versions: Record<string, unknown>;
  prompt_templates: Array<{
    task_id: string;
    template_ver: string;
    hash: string;
  }>;
  plugins: Array<unknown>;
  source: {
    phase: string;
    schema_ver: string;
    generated_at: string;
  };
  serverCapabilities: ServerCapabilities;
}

/**
 * Phase 5 hook return shape. The shell asks four questions:
 *  - has the capabilities view loaded yet (`isLoaded`)?
 *  - did the load fail (`error`)?
 *  - what does the server advertise (`view`)?
 *  - does the server speak the universal envelope (`hasEnvelope`)?
 *
 * The convenience flags collapse the typical feature-detect logic
 * the consumer would write themselves.
 */
export interface CapabilitiesSnapshot {
  isLoaded: boolean;
  error: string | null;
  view: CapabilitiesView | null;
  /**
   * True iff `view.serverCapabilities.streamingEnvelope.version >= 1`.
   * The shell uses this to decide whether to consume SSE Resources via
   * the universal envelope or fall back to bespoke per-endpoint
   * shapes (currently only the universal envelope is shipping).
   */
  hasEnvelope: boolean;
}
