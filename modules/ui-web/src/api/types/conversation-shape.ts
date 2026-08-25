// SPDX-License-Identifier: Apache-2.0
/**
 * Slice 491 §9.D Phase E (C0) — FE mirror types for `ConversationShape`.
 *
 * Mirrors `modules/app-agent-api/src/main/java/io/justsearch/agent/api/registry/`:
 * - `ConversationShape.java`
 * - `ConversationShapeRef.java`
 *
 * Wire shape: served by `GET /api/registry/shapes` envelope via
 * `RegistryController.handleShapes` — same envelope as other registry catalogs
 * (`schemaVersion`, `catalogVersion`, `namespace: "registry-shape"`,
 * `primitive: "ConversationShape"`, `entries: ConversationShape[]`).
 *
 * Discovery flow: `ConversationShapeCatalogClient.bootRegistry()` fetches the
 * envelope at boot and caches it in localStorage with ETag. The FE
 * `<jf-chat-shape-mount>` reads the shape ID, looks up the registered
 * `ViewFactory<S>` in `viewFactoryRegistry`, and mounts the typed view.
 */

// `Audience` resolves to the generated `registry-enums.generated.ts` union via the
// surface.ts re-export (560 §4.1/§4.3 anti-drift).
import type { Audience } from './surface.js';
import type { Presentation, Provenance } from './registry.js';
import type {
  ExecutionMode as GenExecutionMode,
  IterationMode as GenIterationMode,
  PersistenceMode as GenPersistenceMode,
} from '../generated/registry-enums.generated.js';

/**
 * Stable, namespaced identifier for a `ConversationShape` manifest entry.
 * Wire shape: bare string. Mirrors `ConversationShapeRef.java`.
 *
 * Pattern: `core.<name>` or `vendor.<plugin-id>.<name>`.
 */
export type ConversationShapeRef = string;

/**
 * Iteration axis. Generated alias of the Java `IterationMode` authority.
 *
 * - `ONE_SHOT` — one LLM call per request.
 * - `WITHIN_TURN_ITERATION` — engine drives a per-iteration loop until the
 *   `IterationController` signals stop.
 */
export type IterationMode = GenIterationMode;

/**
 * Persistence axis. Generated alias of the Java `PersistenceMode` authority.
 *
 * - `EPHEMERAL` — no session-storage; one-shot or one-turn conversation.
 * - `PERSISTENT` — conversation has a session ID and can be resumed
 *   (per-shape `ConversationStore` implementation).
 */
export type PersistenceMode = GenPersistenceMode;

/**
 * Execution-mode axis. Generated alias of the Java `ExecutionMode` authority.
 *
 * - `SUBSTRATE_DRIVEN` — engine controls the per-iteration loop and SPI
 *   invocation (default for fresh shapes).
 * - `SHAPE_DRIVEN` — engine delegates to a per-shape runner that owns its
 *   iteration (used to encapsulate the agent loop).
 */
export type ExecutionMode = GenExecutionMode;

/**
 * Wire shape of a ConversationShape catalog entry. Mirrors
 * `modules/app-agent-api/.../ConversationShape.java`.
 *
 * G4 Pass-8 follow-up: the `projections` field (`ShapeProjections` record) was
 * retracted from both Java + TS as speculative substrate (C-018: only the F4
 * test ever read it). Discoverability is provided by the reverse reference
 * from `Surface.consumes.conversationShapes`.
 */
export interface ConversationShape {
  id: ConversationShapeRef;
  presentation: Presentation;
  audience: Audience;
  provenance: Provenance;
  executionMode: ExecutionMode;
  iterationMode: IterationMode;
  persistenceMode: PersistenceMode;
  promptContributorIds: string[];
  contextInjectorIds: string[];
  streamConsumerIds: string[];
  iterationControllerId?: string | null;
  eventSchema: string[];
  /**
   * Tempdoc 863 §4.A.1 — whether this shape's answer-plane turns are recorded to the canonical
   * conversation record, INDEPENDENT of `persistenceMode`. Promoted from a derived Java accessor to
   * a declared record component, which is what put it on this envelope: a record COMPONENT
   * serialises where the bare `recordsToThread()` accessor did not. The wire probe that measured
   * that addition is `RegistryControllerTest.shapesEnvelopeKeySetAfterRecordsToThreadPromotion`.
   */
  recordsToThread: boolean;
}

/**
 * Catalog envelope returned by `GET /api/registry/shapes`. Wire shape mirrors
 * `RegistryController.handleShapes`.
 */
export interface ConversationShapeCatalog {
  $schema?: string;
  schemaVersion: string;
  catalogVersion: number;
  namespace: string;
  primitive: 'ConversationShape';
  entries: ConversationShape[];
}
