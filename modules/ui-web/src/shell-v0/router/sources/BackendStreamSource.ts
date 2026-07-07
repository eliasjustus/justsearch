// SPDX-License-Identifier: Apache-2.0
/**
 * BackendStreamSource — Intent substrate tier 1 (slice 492).
 *
 * Owns the backend-broadcast ingress channel: the always-on
 * `/api/intent/stream` SSE endpoint slice 487 §4.3 introduced. When
 * the backend's `BackendIntentRouter` receives a Navigation envelope
 * (today: from slice 491's LLM-emission `MarkdownUrlExtractor`,
 * future: agent-loop tool calls, MCP, scheduled triggers), it
 * publishes the envelope onto this channel; this source receives
 * the envelope, unwraps the `Intent`, and dispatches into the FE
 * router via the standard source path.
 *
 * Headline scenario this source enables (the regression-bait
 * referenced in the slice 492 tempdoc): an LLM emits
 * `[link](justsearch://surface/core.search-surface?query=foo)` in
 * chat. Slice 487's `MarkdownUrlExtractor` produces a Navigation
 * Intent with `state: {query: 'foo'}`; the BackendIntentRouter
 * publishes the envelope; this source receives it; the FE router
 * delegates to the NavigationHandler; the handler distributes state
 * to the search store; the search surface activates with
 * `searchState.query === 'foo'`. Pre-slice-492, the state was
 * silently dropped at the dispatch boundary; slice 492 fixes it by
 * construction; this source completes the end-to-end live path.
 *
 * Implementation note: `bootIntentStreamBridge` (slice 487) takes
 * an `IntentRouter` shape and calls `router.dispatch(intent)`
 * directly. This source adapts it to the `IntentSource` interface
 * by passing a router-shaped adapter that forwards into the source's
 * SourceDispatch callback. Functionally a thin shim; the value is
 * substrate-shape consistency — every ingress is an `IntentSource`.
 */

import type { Intent } from '../types.js';
import type { IntentRouter } from '../intentRouter.js';
import {
  bootIntentStreamBridge,
  stopIntentStreamBridge,
} from '../../../api/intent/bootIntentStreamBridge.js';
import type { MultiplexedStream } from '../../streaming/MultiplexedStream.js';
import type { IntentSource, SourceDispatch } from './IntentSource.js';

/** Stable Manifest-tier id this source corresponds to. */
export const BACKEND_STREAM_SOURCE_REF = 'core.backend-stream';

export interface BackendStreamSourceConfig {
  /**
   * Tempdoc 662: the shared `MultiplexedStream` (one of the 5 always-on streams collapsed
   * onto `/api/shell-events/stream`) this source subscribes its `system:intent-envelopes`
   * streamId on. The caller owns its construction/start lifecycle (one instance per shell).
   */
  multiplex: MultiplexedStream;
}

export function createBackendStreamSource(
  config: BackendStreamSourceConfig,
): IntentSource {
  return {
    ref: BACKEND_STREAM_SOURCE_REF,
    start(dispatch: SourceDispatch): () => void {
      // Adapt SourceDispatch → IntentRouter shape that
      // bootIntentStreamBridge expects. The bridge only calls
      // `dispatch(intent)` (single arg); the source's dispatch hook
      // accepts an optional DispatchOptions second arg which we omit
      // (backend-broadcast intents always push history — they are
      // remote arrivals, not popstate-replays).
      const routerAdapter: IntentRouter = {
        dispatch(intent: Intent): Promise<unknown> {
          dispatch(intent);
          return Promise.resolve();
        },
        // subscribe() is never called by bootIntentStreamBridge but
        // is part of the IntentRouter shape; supply a no-op.
        subscribe(): () => void {
          return () => undefined;
        },
      };
      bootIntentStreamBridge(config.multiplex, routerAdapter);
      // The bridge tracks its own singleton state; the canonical
      // teardown is the module-level stopIntentStreamBridge function.
      return () => {
        stopIntentStreamBridge();
      };
    },
  };
}
