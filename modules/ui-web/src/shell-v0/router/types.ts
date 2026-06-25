// SPDX-License-Identifier: Apache-2.0
/**
 * Router types — slice 489 §4 ShellAddress.
 *
 * TS counterpart of `modules/app-agent-api/.../registry/ShellAddress.java`.
 * The two implementations share the URL grammar; the wire-contract pipeline
 * integration (`contracts/wire/`) is a follow-up if/when the grammar grows.
 *
 * Per slice 489 §13 anti-pattern #3: the Java and TS implementations of the
 * grammar must stay in sync. Treat the Java record as the canonical model
 * and mirror it here byte-for-byte.
 */

import type { TransportTag } from './transports.js';

/** Catalog identifier for a Surface (matches `SurfaceRef.value`). */
export type SurfaceRefValue = string;

/** Catalog identifier for an Operation (matches `OperationRef.value`). */
export type OperationRefValue = string;

/** Carrier for surface state — flat key/value bag. Matches `StateSnapshot.values`. */
export type StateSnapshot = Record<string, string | number | boolean | string[]>;

/**
 * Sealed-union address — discriminated by `kind`. Mirrors the Java
 * `ShellAddress` sealed interface with `Navigation`, `Invocation`, and `Query`
 * variants.
 */
export type ShellAddress =
  | ShellAddressNavigation
  | ShellAddressInvocation
  | ShellAddressQuery
  | ShellAddressAnswer;

export interface ShellAddressNavigation {
  readonly kind: 'navigate';
  readonly target: SurfaceRefValue;
  readonly state: StateSnapshot;
}

export interface ShellAddressInvocation {
  readonly kind: 'invoke';
  readonly target: OperationRefValue;
  /** Args payload as a plain object — serialized to JSON when dispatched. */
  readonly args: Record<string, unknown>;
  readonly confirmationToken?: string;
}

/**
 * Search verb (548 S4-A) — "run a search for `query`". Mirrors the Java
 * `ShellAddress.Query`. The query text is free-form (not a catalog id), so the URL
 * grammar carries it in the `q` param (`justsearch://query?q=…`), not the path; `state`
 * carries optional refinements. The IntentRouter lowers it to a search-surface activation.
 */
export interface ShellAddressQuery {
  readonly kind: 'query';
  readonly query: string;
  readonly state: StateSnapshot;
}

/**
 * Answer verb (548 §4.5) — "give a cited one-turn answer to `prompt`". Mirrors the Java
 * `ShellAddress.Answer`. Distinct resolution model from the others: it resolves an AI *shape*
 * (default `core.rag-ask`) rather than a surface/operation catalog id, and the router lowers it
 * to an activation of the shape-hosting chat surface with the prompt. Grammar:
 * `justsearch://answer?q=<prompt>[&shape=<shapeId>]`.
 */
export interface ShellAddressAnswer {
  readonly kind: 'answer';
  readonly prompt: string;
  /** AI shape to resolve (default `core.rag-ask`). */
  readonly shape: string;
  readonly state: StateSnapshot;
}

/**
 * One normalized intent every entry point produces (slice 489 §9).
 *
 * Sources: rail click, drop handler, palette button, URL/popstate hydration,
 * Tauri deep-link callback, bookmark, MCP-constructed URL, future
 * voice/scheduled/gesture transports. The router is the single consumer.
 */
export interface Intent {
  readonly address: ShellAddress;
  readonly transport: TransportTag;
}
