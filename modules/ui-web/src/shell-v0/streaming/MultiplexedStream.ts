// SPDX-License-Identifier: Apache-2.0
/**
 * MultiplexedStream — tempdoc 662's cross-channel SSE fan-out.
 *
 * Opens ONE underlying `EnvelopeStream` connection — reusing its reconnect / heartbeat-
 * absence-watchdog liveness / `originContact` bump UNCHANGED — to a multiplexed endpoint
 * (e.g. `/api/shell-events/stream`), and demuxes every received frame by its `streamId` to
 * the matching registered consumer's reducer. N logical streams (intent, the two advisory
 * classes, action-ledger, indexing-jobs) share ONE physical connection instead of N
 * EventSources, which is the actual fix for the browser's ~6-per-host connection-pool
 * exhaustion that starved the cheap `/api/status`/`/api/inference/status` polls under load
 * (tempdoc 649).
 *
 * Per-stream resume is REUSED, not reinvented: each frame's `resumeToken` is already the
 * server's per-channel `streamId:seq` token (`ResumeTokenCodec`, backend
 * `MultiplexedSseWriter`). On (re)connect, the bundle of every currently-registered stream's
 * last-known resumeToken is comma-joined into the `?since=` query param via
 * `EnvelopeStream`'s `resumeTokenProvider` hook — no new codec on either side.
 *
 * Subscription shape mirrors `EnvelopeStreamPool.subscribePooled` (ref-counted: the first
 * subscriber for a `streamId` creates the demux entry from `createConfig`; the last
 * unsubscribe removes it), so migrating an existing `new EnvelopeStream({...}).subscribe(l)`
 * call site to `multiplex.subscribe(streamId, () => ({...}), l)` is a small, local edit.
 */

import { EnvelopeStream } from './EnvelopeStream.js';
import type {
  EnvelopeReducer,
  EnvelopeStreamConfig,
  EnvelopeStreamListener,
  EnvelopeStreamSnapshot,
} from './EnvelopeStream.js';
import type { SseEnvelope } from './envelope-types.js';

// Detect dev mode safely (Vite sets this via import.meta.env) — mirrors api/schemas.ts's
// IS_DEV guard so this module doesn't depend on ImportMetaEnv being typed for every consumer.
const IS_DEV = (() => {
  try {

    return (import.meta as any).env?.DEV === true;
  } catch {
    return false;
  }
})();

interface StreamEntry<T> {
  payload: T;
  seq: number;
  resumeToken: string | null;
  reducer: EnvelopeReducer<T>;
  listeners: Set<EnvelopeStreamListener<T>>;
  refs: number;
}

/** Constructor configuration — the subset of `EnvelopeStreamConfig` that applies to the
 * shared physical transport (no `initialState`/`reducer`/`initialResumeToken`/
 * `resumeTokenProvider`: those are per-demuxed-stream or computed internally). */
export type MultiplexedStreamConfig = Pick<
  EnvelopeStreamConfig<null>,
  'url' | 'eventSourceFactory' | 'watchdogStaleMs' | 'reconnectBaseMs' | 'reconnectCapMs'
>;

export class MultiplexedStream {
  private readonly inner: EnvelopeStream<null>;
  private readonly entries = new Map<string, StreamEntry<unknown>>();
  /** Cached physical-connection state, updated ONLY by the inner subscription below — see
   * the class-level note on why routeFrame must read this cache, not `inner.getSnapshot()`
   * directly (the inner stream flips `isConnected` AFTER invoking the reducer). */
  private lastConnected = false;

  constructor(config: MultiplexedStreamConfig) {
    this.inner = new EnvelopeStream<null>({
      url: config.url,
      initialState: null,
      reducer: (_state, envelope) => {
        this.routeFrame(envelope);
        return null;
      },
      eventSourceFactory: config.eventSourceFactory,
      watchdogStaleMs: config.watchdogStaleMs,
      reconnectBaseMs: config.reconnectBaseMs,
      reconnectCapMs: config.reconnectCapMs,
      resumeTokenProvider: () => this.bundleResumeToken(),
    });
    // The SOLE place `lastConnected` is updated. Fires on every inner notify — including the
    // ones already triggered by routeFrame's reducer call (same isConnected value at that
    // point, so `changed` is false and no extra broadcast happens) AND on connection-only
    // events the reducer never sees (open/error/watchdog timeout) — see class doc.
    this.inner.subscribe((snapshot) => {
      const changed = snapshot.isConnected !== this.lastConnected;
      this.lastConnected = snapshot.isConnected;
      if (changed) {
        this.broadcastConnectionChange();
      }
    });
  }

  /** Opens the shared physical connection. Idempotent (delegates to `EnvelopeStream.start`). */
  start(): void {
    this.inner.start();
  }

  /** Closes the shared physical connection. Idempotent. */
  stop(): void {
    this.inner.stop();
  }

  /**
   * Subscribes to one logical stream's demuxed frames. The FIRST subscriber for a given
   * `streamId` creates its demux entry from `createConfig()`; subsequent subscribers attach
   * to the existing entry (its `initialState`/`reducer` are fixed by the first caller — a
   * second caller passing a different config is assumed compatible, mirroring
   * `EnvelopeStreamPool.subscribePooled`'s contract). The LAST unsubscribe for a `streamId`
   * removes its entry, so frames for an unregistered stream are dropped (see `routeFrame`).
   */
  subscribe<T>(
    streamId: string,
    createConfig: () => { initialState: T; reducer: EnvelopeReducer<T> },
    listener: EnvelopeStreamListener<T>,
  ): () => void {
    let entry = this.entries.get(streamId) as StreamEntry<T> | undefined;
    if (!entry) {
      const cfg = createConfig();
      entry = {
        payload: cfg.initialState,
        seq: 0,
        resumeToken: null,
        reducer: cfg.reducer,
        listeners: new Set(),
        refs: 0,
      };
      this.entries.set(streamId, entry as StreamEntry<unknown>);
    }
    entry.listeners.add(listener);
    entry.refs += 1;
    let released = false;
    return () => {
      if (released) return;
      released = true;
      const current = this.entries.get(streamId) as StreamEntry<T> | undefined;
      if (!current) return;
      current.listeners.delete(listener);
      current.refs -= 1;
      if (current.refs <= 0) {
        this.entries.delete(streamId);
      }
    };
  }

  /** Read a logical stream's current snapshot synchronously, or `null` if unregistered. */
  getSnapshot<T>(streamId: string): EnvelopeStreamSnapshot<T> | null {
    const entry = this.entries.get(streamId) as StreamEntry<T> | undefined;
    if (!entry) return null;
    return {
      payload: entry.payload,
      seq: entry.seq,
      resumeToken: entry.resumeToken,
      isConnected: this.lastConnected,
    };
  }

  private routeFrame(envelope: SseEnvelope): void {
    const entry = this.entries.get(envelope.streamId);
    if (!entry) {
      // Unregistered streamId. The server's dedicated heartbeat pseudo-channel (and any
      // heartbeat frame in general) is EXPECTED to have no registered consumer — silent drop.
      // Anything else (an UPDATE, or a non-heartbeat lifecycle frame) for an unknown streamId
      // signals an FE/BE streamId-constant mismatch, worth surfacing in dev.
      const payload = envelope.payload as { kind?: string } | null;
      const isHeartbeat = envelope.frameKind === 'LIFECYCLE' && payload?.kind === 'heartbeat';
      if (!isHeartbeat && IS_DEV) {
        // eslint-disable-next-line no-console -- dev-only diagnostic, see class doc.
        console.warn(`[MultiplexedStream] frame for unregistered streamId: ${envelope.streamId}`);
      }
      return;
    }
    let nextPayload: unknown;
    try {
      nextPayload = entry.reducer(entry.payload, envelope);
    } catch {
      // Reducer error — keep prior payload, advance seq + resumeToken anyway (mirrors
      // EnvelopeStream.handleFrame's contract) so the next reconnect doesn't replay from stale.
      nextPayload = entry.payload;
    }
    entry.payload = nextPayload;
    entry.seq = envelope.seq;
    entry.resumeToken = envelope.resumeToken ?? entry.resumeToken;
    const snapshot: EnvelopeStreamSnapshot<unknown> = {
      payload: entry.payload,
      seq: entry.seq,
      resumeToken: entry.resumeToken,
      isConnected: this.lastConnected,
    };
    for (const listener of entry.listeners) {
      try {
        listener(snapshot);
      } catch {
        // Swallow listener errors so one bad subscriber doesn't break others (mirrors
        // EnvelopeStream.notify's contract).
      }
    }
  }

  private broadcastConnectionChange(): void {
    for (const entry of this.entries.values()) {
      const snapshot: EnvelopeStreamSnapshot<unknown> = {
        payload: entry.payload,
        seq: entry.seq,
        resumeToken: entry.resumeToken,
        isConnected: this.lastConnected,
      };
      for (const listener of entry.listeners) {
        try {
          listener(snapshot);
        } catch {
          // Swallow listener errors — same contract as routeFrame above.
        }
      }
    }
  }

  /** Comma-joins every registered stream's last-known resumeToken for the `?since=` bundle
   * (Investigation §D / Design §D1 — reuses the per-channel `ResumeTokenCodec` tokens
   * verbatim; no new codec). A stream with no token yet (never received a frame) is omitted —
   * the backend's `MultiplexedSseWriter` treats a missing bundle entry for a channel
   * identically to a fresh subscriber: it sends that channel its own initial snapshot. */
  private bundleResumeToken(): string | null {
    const tokens: string[] = [];
    for (const entry of this.entries.values()) {
      if (entry.resumeToken) tokens.push(entry.resumeToken);
    }
    return tokens.length > 0 ? tokens.join(',') : null;
  }
}
