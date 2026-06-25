// SPDX-License-Identifier: Apache-2.0
/**
 * IntentSource — Intent substrate tier 1 (slice 492 promotion).
 *
 * Slice 487 declared `IntentSource` as a Manifest tier in
 * `modules/app-agent-api/.../registry/IntentSource.java` — catalog entries
 * for `core.url-bar`, `core.os-tauri-deeplink`, etc. Slice 492 promotes the
 * FE side from manifest-only to **ingress code that adopts the Manifest-
 * tier naming via shared string constants** (the source `ref` matches the
 * catalog's `IntentSourceRef` string). This is structural enough for
 * code-organization + Pass-8 grepability; it does NOT cross-validate
 * against the Java catalog at build time. Promoting the TS interface to
 * carry the full Manifest record (presentation, sourceTier, extractorId,
 * signedIntentTokenSupported, transport) requires exposing the Java
 * catalog to the FE — a separate slice.
 *
 * Pragmatic scope (slice 492 §"Tier 1 — pragmatic promotion"): only
 * sources with multi-step lifecycles are class-promoted. Inline ingresses
 * (rail click, palette button, drop) remain as one-line
 * `intentRouter.dispatch({...})` calls inside Shell event handlers —
 * they already match the substrate's spirit (the Shell event handler is
 * the source; class-promotion would add ceremony without behavioral
 * benefit). Documented in the slice closure record.
 *
 * Java/TS symmetry: the Java side has `IntentSourceCatalog` entries with
 * extractor-id references; this FE interface is the runtime counterpart.
 * The two diverge in concrete shape — FE sources hold UI state and DOM /
 * Tauri / SSE subscriptions; Java sources are different mechanisms. The
 * Manifest tier (catalog entries) is shared; the concrete classes are
 * per-process.
 */

import type { Intent } from '../types.js';
import type { DispatchOptions } from '../intentRouter.js';

/**
 * Dispatch hook handed to each source's `start(...)` so it can fan its
 * extracted Intents into the router. The Shell binds this to
 * `IntentRouter.dispatch` (inline; see Shell.connectedCallback). Sources
 * see only the dispatch capability — not the router instance — to keep
 * source code unaware of the router shape.
 */
export type SourceDispatch = (intent: Intent, options?: DispatchOptions) => void;

export interface IntentSource {
  /** Stable id matching the Manifest-tier `IntentSourceRef` (e.g., `'core.url-bar'`). */
  readonly ref: string;

  /**
   * Boot the source's ingress mechanism. The source fans extracted Intents
   * into the supplied dispatcher. Returns a teardown handle the host calls
   * on shutdown / disconnect.
   *
   * Sync sources return a teardown function directly; async sources (e.g.,
   * Tauri plugin dynamic-imports) return a Promise that resolves to the
   * teardown function. The host normalizes both shapes.
   */
  start(dispatch: SourceDispatch): (() => void) | Promise<() => void>;
}
