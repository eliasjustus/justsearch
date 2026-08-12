// SPDX-License-Identifier: Apache-2.0
/**
 * sv3-ask — the ONE ask-issuance site of the Search v3 window (tempdoc 822 Phase F1).
 *
 * Derived from T3 Code (T3 Tools Inc., MIT) — see THIRD-PARTY-NOTICES.md in this directory.
 *
 * Every answer this window produces is dispatched from {@link sv3Ask} and nowhere else, and
 * `sv3-ask.forbid.test.ts` fails the build if a second `consumeShapeStream` caller appears in
 * `search-v3/`. That is the point: the shipped window's L9 defect is not that its 423 handling is
 * wrong, it is that ONE of its send paths dispatches without any — an asymmetry only a second
 * issuance site can have. With one site the refusal is structural: there is no second path to forget.
 *
 * Shared authorities consumed, not re-authored (the charter's "from-scratch components, shared
 * authorities"; the PATTERN is search-v2's `askClient.ts:26-37`, mined — never imported, because a
 * dev window importing another dev window's module is the coupling this arc exists to avoid):
 *  - `buildRequestBody('core.rag-ask', …)` — the per-shape POST body.
 *  - `consumeShapeStream` + `dispatchShapeEventToHandlers` — the ONE SSE consumer, so the
 *    connection-budget accounting in `streams.ts` stays correct.
 *  - the GENERATED `CoreRagAskHandlers` interface — a new shape event is a compile-time fact.
 *
 * FOUR TERMINALS, EXACTLY ONE OF THEM, ALWAYS. `sv3Ask` never throws: completion, refusal (the
 * session lock's 423), a halt the reader asked for, and failure are four distinct sink calls, so the
 * caller's turn can always reach a terminal state and can always say WHICH one it reached. A halt is
 * not a failure and must not be worded as one — the reader stopped the answer on purpose.
 */

import {
  consumeShapeStream,
  dispatchShapeEventToHandlers,
} from '../../../api/streams.js';
import type { CoreRagAskHandlers } from '../../../api/generated/shape-handlers/core-rag-ask.js';
import { buildRequestBody } from '../unifiedChatRequest.js';

/** The one shape this window's ask route dispatches. */
export const SV3_ASK_SHAPE_ID = 'core.rag-ask';

export interface Sv3AskRequest {
  readonly apiBase: string;
  readonly question: string;
  /** The window's session id — stamped on every dispatch so the backend records the turn. */
  readonly conversationId: string;
  readonly signal?: AbortSignal;
}

export interface Sv3AskSink {
  /** Streaming text, delta by delta. The accumulated answer lives in the caller's turn. */
  onDelta(text: string): void;
  /**
   * The backend reported the retrieval set it grounded the answer in. Phase F1 keeps only the COUNT:
   * rendering the citations themselves is the document-pane work (Phase C), and a count is the one
   * claim this window can make without a resolver behind it.
   */
  onCitations(count: number): void;
  onDone(): void;
  /** The session lock refused this send (HTTP 423) — the ONLY 423 consumer in this window. */
  onRefused(): void;
  /** The reader pressed Stop. Whatever streamed so far is kept; the turn is halted, not failed. */
  onHalted(): void;
  onFailed(message: string): void;
}

function messageOf(err: unknown): string {
  if (err instanceof Error && err.message) return err.message;
  return 'The answer could not be produced.';
}

/**
 * Dispatch one grounded ask and drive `sink` from the stream.
 *
 * `docIds` is empty by construction: this window has no committed document set to scope an answer to
 * (search-v2's frozen-snapshot law has no counterpart here yet), and an empty array is the backend's
 * documented open-retrieval fallback (`views/unifiedChatRequest.ts:80-84`) rather than a scope this
 * FE invented.
 */
export async function sv3Ask(req: Sv3AskRequest, sink: Sv3AskSink): Promise<void> {
  const body = buildRequestBody(SV3_ASK_SHAPE_ID, req.question, req.conversationId, '', []);
  // The turn is recorded against THIS window's session, exactly as the shipped window stamps it.
  body.conversationId = req.conversationId;

  const handlers: CoreRagAskHandlers = {
    onChunk(payload: unknown) {
      const p = payload as { text?: unknown } | string | null;
      const delta = typeof p === 'string' ? p : typeof p?.text === 'string' ? p.text : '';
      if (delta === '') return;
      sink.onDelta(delta);
    },
    onRagCitations(payload: unknown) {
      const p = payload as { citations?: unknown } | null;
      if (p && Array.isArray(p.citations)) sink.onCitations(p.citations.length);
    },
  };

  try {
    await consumeShapeStream(
      `${req.apiBase}/api/chat/dispatch`,
      body,
      (event, payload) =>
        dispatchShapeEventToHandlers(handlers as Record<string, unknown>, event, payload),
      req.signal,
    );
  } catch (err) {
    // The signal is consulted BEFORE the error is classified: an abort surfaces as whatever the
    // platform's fetch rejects with (`DOMException: AbortError` in a browser, a plain error in some
    // test environments), so keying on the error's shape would report the reader's own Stop as a
    // backend failure on some runtimes and not others.
    if (req.signal?.aborted === true) {
      sink.onHalted();
      return;
    }
    // The ONE refusal path. `consumeShapeStream` stamps the HTTP status on the thrown error, so a
    // lock taken between this window's render and its submit is a typed outcome here, not a generic
    // failure the caller has to sniff for.
    if ((err as Error & { status?: number }).status === 423) {
      sink.onRefused();
      return;
    }
    sink.onFailed(messageOf(err));
    return;
  }

  sink.onDone();
}
