// SPDX-License-Identifier: Apache-2.0
/**
 * Tempdoc 521 §16.1 phase A.2 — adapter from a `host.ai.streamShape` /
 * `AISession.send` iterable to the (event, payload)-callback contract
 * used by the view-layer shape handlers (`dispatchShapeEventToHandlers`).
 *
 * The two streaming surfaces yield the same logical event sequence,
 * just in different shapes:
 *
 *   - {@link './HostApiImpl.ts'} `streamShape` → `AsyncIterable<AIChunk>`
 *     where `AIChunk = { name, payload }`
 *   - {@link '../../api/streams.ts'} `consumeShapeStream` → callback
 *     `(event: string, payload: unknown) => void`
 *
 * `pumpHostAiStream` lets a view replace `await consumeShapeStream(url,
 * body, dispatch, signal)` with `await
 * pumpHostAiStream(host.ai.streamShape(shapeId, body, signal), dispatch,
 * signal)` — one-line swap, identical event semantics.
 *
 * Abort: when `signal` aborts, the iterator's `return()` runs and
 * unwinds the stream. The underlying fetch was also given the same
 * signal upstream, so the connection tears down cleanly.
 */

import type { AIChunk, PluginHostApi } from './plugin-types.js';
import { consumeShapeStream } from '../../api/streams.js';

export async function pumpHostAiStream(
  iter: AsyncIterable<AIChunk>,
  onEvent: (event: string, payload: unknown) => void,
  signal?: AbortSignal,
): Promise<void> {
  const iterator = iter[Symbol.asyncIterator]();
  // §13 A6 parity: if the iterator already yielded a discriminated
  // `error` chunk, surface it through the same `error` event the
  // view's CoreXxxHandlers vocabulary expects.
  // 565 robustness: track whether a terminal (`done`/`error`) chunk arrived, so a
  // host stream that ends silently (empty body / dropped connection, no terminal
  // event) surfaces STREAM_INCOMPLETE instead of stalling the view with isStreaming
  // stuck true — the exact asymmetry consumeShapeStream (the fetch path) already guards.
  let receivedTerminal = false;
  try {
    while (true) {
      if (signal?.aborted) {
        // Unwind the iterator if we can — many AsyncGenerators implement
        // `return()` which runs `finally` blocks and aborts the consumer.
        await iterator.return?.(undefined);
        const err = new Error('aborted') as Error & { name: string };
        err.name = 'AbortError';
        throw err;
      }
      const result = await iterator.next();
      if (result.done) break;
      const chunk = result.value;
      if (chunk.name === 'done' || chunk.name === 'error') receivedTerminal = true;
      onEvent(chunk.name, chunk.payload);
    }
  } catch (err) {
    if (err instanceof Error && err.name === 'AbortError') {
      // Propagate AbortError so the view's existing `if (err.name ===
      // 'AbortError')` branch in the catch handler treats it the same
      // as a fetch-level abort. Symmetric with `consumeShapeStream`.
      throw err;
    }
    throw err;
  }

  // Symmetric with consumeShapeStream (api/streams.ts:618): a stream that ended
  // without a terminal event is a silent failure — make it loud so the view's
  // onError path fires instead of leaving the run stuck.
  if (!receivedTerminal) {
    const err = new Error('Stream ended without terminal event') as Error & {
      code?: string;
    };
    err.code = 'STREAM_INCOMPLETE';
    throw err;
  }
}

/**
 * Tempdoc 521 §16.1 — single entry point used by built-in chat
 * surfaces to stream a conversation shape. The §11.4 design says
 * there is exactly one production consumer surface for streaming AI:
 * `host_.ai.streamShape` against `/api/chat/dispatch`.
 *
 * §22 Phase E — there is no production fallback. If `host_` is
 * undefined, this function throws synchronously rather than silently
 * routing around the host. Every production mount path that streams
 * an AI shape forwards `host_` today (Stage's `renderOneSurface`,
 * `<jf-peek>`'s mountSurface call, `<jf-chat-shape-mount>`'s
 * view-factory forwarding — see the §20 inventory). A missing
 * `host_` in production is a real regression, not a graceful-degrade
 * scenario.
 *
 * Tests that exercise consumeShapeStream directly (legacy URL paths)
 * use {@link streamViaHostInTest} below — the fallback lives there
 * and is unreachable from production code by construction.
 */
export async function streamViaHost(opts: {
  readonly host_: PluginHostApi | undefined;
  readonly shapeId: string;
  readonly fallbackUrl: string;
  readonly body: Record<string, unknown>;
  readonly onEvent: (event: string, payload: unknown) => void;
  readonly signal?: AbortSignal;
}): Promise<void> {
  const { host_, shapeId, fallbackUrl, body, onEvent, signal } = opts;
  if (host_ !== undefined) {
    const iter = host_.ai.streamShape(shapeId, body, signal);
    await pumpHostAiStream(iter, onEvent, signal);
    return;
  }
  // §22 Phase E — production throws when host_ is missing. The test
  // setup file `src/__test-setup__/streamViaHostSuppress.ts` sets
  // `__STREAM_VIA_HOST_ALLOW_FALLBACK__ = true` so test contexts that
  // mock consumeShapeStream directly still work. Production code
  // never sets the flag, so a missing-host_ mount is a thrown Error.
  const win = globalThis as unknown as {
    __STREAM_VIA_HOST_ALLOW_FALLBACK__?: boolean;
  };
  if (!win.__STREAM_VIA_HOST_ALLOW_FALLBACK__) {
    throw new Error(
      `[streamViaHost] host_ must be wired for shapeId='${shapeId}' ` +
        `(tempdoc 521 §11.4 / §22 Phase E — built-in chat surfaces ` +
        `consume host.ai). This is a mount-path regression; check the ` +
        `surface's mountSurface() callsite forwards host_.`,
    );
  }
  await consumeShapeStream(fallbackUrl, body, onEvent, signal);
}
