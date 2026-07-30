// SPDX-License-Identifier: Apache-2.0
/**
 * EnvelopeStreamPool — Tempdoc 508-followup §γ3.
 *
 * Multiplexes EnvelopeStream subscriptions by stream URL so that N
 * subscribers to the same channel share one EventSource. The first
 * subscriber for a URL creates + starts the stream; subsequent
 * subscribers attach to the existing instance. The last unsubscribe
 * stops + removes the stream.
 *
 * Without pooling, every plugin (or every consumer surface) that
 * calls `host.data.subscribeResource('hub.recent')` opens a separate
 * EventSource. Browsers cap connections per origin (typically 6),
 * so a half-dozen subscribers exhausts the budget. The pool fixes
 * the fan-out without changing the consumer API.
 *
 * Stream parameters (initialState, reducer) are determined by the
 * first subscriber. The plan's working assumption: subscribers to
 * the same channel use a uniform shape (the typical case — they
 * read the same envelope payloads); divergent reducer shapes need
 * their own channel id.
 */

import { EnvelopeStream } from './EnvelopeStream.js';
import type {
  EnvelopeStreamConfig,
  EnvelopeStreamListener,
} from './EnvelopeStream.js';

interface PoolEntry<T> {
  readonly stream: EnvelopeStream<T>;
  refs: number;
}

const pool = new Map<string, PoolEntry<unknown>>();

/**
 * Subscribe to a pooled EnvelopeStream. If the URL is already
 * pooled the listener attaches to the existing stream; otherwise a
 * new stream is created from {@link createConfig}, started, and
 * cached. Returns an unsubscribe that decrements the refcount and
 * disposes the stream when the count reaches zero.
 */
export function subscribePooled<T>(
  url: string,
  listener: EnvelopeStreamListener<T>,
  createConfig: () => EnvelopeStreamConfig<T>,
): () => void {
  let entry = pool.get(url) as PoolEntry<T> | undefined;
  if (!entry) {
    const stream = new EnvelopeStream<T>(createConfig());
    entry = { stream, refs: 0 };
    pool.set(url, entry as PoolEntry<unknown>);
    stream.start();
  }
  entry.refs += 1;
  const unsubListener = entry.stream.subscribe(listener);
  let released = false;
  return () => {
    // Idempotent: a consumer that calls the returned unsub twice
    // (defensive pattern in Lit disconnectedCallback chains) must not
    // double-decrement the refcount.
    if (released) return;
    released = true;
    unsubListener();
    const current = pool.get(url) as PoolEntry<T> | undefined;
    if (!current) return;
    current.refs -= 1;
    if (current.refs <= 0) {
      current.stream.stop();
      pool.delete(url);
    }
  };
}

/**
 * Re-establish a pooled stream in place, at a consumer's request — the fallback-transport twin of
 * `MultiplexedStream.nudgeReconnect()` (tempdoc 798 B4), so a consumer with a per-channel stall
 * detector has ONE actuator call site regardless of which transport it happens to be running on.
 *
 * The gap this closes is narrower here than on the multiplexed transport: a pooled stream's
 * physical connection IS the channel, so `EnvelopeStream`'s own heartbeat-absence watchdog observes
 * the same silence a consumer-level stall detector does and normally reconnects first. It exists so
 * the actuator is not silently ABSENT on this path — a consumer switched back to the pooled
 * transport must not lose its recovery action without anyone noticing.
 *
 * `stop()` + `start()` (not a raw reopen) so the stream re-reads its freshest `resumeToken` for the
 * `?since=` replay, exactly as its internal reconnect does.
 *
 * @returns `true` when a reconnect was performed; `false` when the URL is not pooled.
 */
export function nudgePooledStream(url: string): boolean {
  const entry = pool.get(url);
  if (!entry) {
    return false;
  }
  entry.stream.stop();
  entry.stream.start();
  return true;
}

/** Test-only: drop all pooled streams without firing their stops. */
export function __resetEnvelopeStreamPoolForTest(): void {
  for (const entry of pool.values()) {
    try {
      entry.stream.stop();
    } catch {
      // ignore — stop is best-effort during tests
    }
  }
  pool.clear();
}

/** Test inspector: number of active pooled URLs. */
export function __poolSizeForTest(): number {
  return pool.size;
}
