// SPDX-License-Identifier: Apache-2.0
/**
 * Slice 448 phase 5 — Subscription strategy for DiagnosticChannel × SSE_STREAM.
 *
 * Stands parallel to (not registered inside) `subscriptionStrategy.ts` because
 * DiagnosticChannel is not a Resource Category; the existing
 * `(Category, SubscriptionMode) → strategy` registry is Resource-specific.
 *
 * Wire shape (from slice 448 phase 3):
 *  - LIFECYCLE.connected: no state change beyond `catalogVersion = env.seq`.
 *  - LIFECYCLE.heartbeat / closing: same.
 *  - LIFECYCLE.snapshot: firehose has no current state — empty extras (per
 *    `DiagnosticChannelStreamController.handle`'s `Map::of` snapshot supplier).
 *    Treated as a no-op for state.
 *  - LIFECYCLE.reset: reset events array to empty.
 *  - UPDATE: payload is a `DiagnosticEventEnvelope` with
 *    `kind === 'log-event'` and a nested `event: DiagnosticEvent`. The
 *    reducer appends `event` to a bounded ring buffer.
 *
 * Per slice 448 phase 3 D6 (server-emits-everything-except-DELIVERY_INTERNAL):
 * the server-side appender drops events marked DELIVERY_INTERNAL; subscribers
 * receive every other event. The strategy carries an optional client-side
 * SubCategory filter for consumers (e.g., a log surface defaulting to
 * `[CORE_DIAGNOSTIC]` and exposing checkboxes for LIBRARY_TRACE / BOOT_TRACE).
 * The default filter is empty (= "accept all"); consumers narrow it via
 * the configuration argument.
 */

import type {
  DiagnosticEvent,
  DiagnosticEventEnvelope,
  SubCategory,
} from '../../api/types/diagnostic.js';
import { KIND_LOG_EVENT } from '../../api/types/diagnostic.js';
import type { EnvelopeReducer } from '../streaming/EnvelopeStream.js';

/** Default ring-buffer cap for log emissions. */
export const DEFAULT_DIAGNOSTIC_CAP = 500;

/** State shape emitted to consumers. */
export interface DiagnosticChannelData {
  events: DiagnosticEvent[];
  cap: number;
  /**
   * Client-side sub-category filter. Empty set means "accept all"; non-empty
   * narrows to events whose subCategory is included.
   */
  subCategoryFilter: ReadonlySet<SubCategory>;
}

/** Carried alongside the data so consumers can read the seq cursor. */
export interface DiagnosticChannelStrategyState {
  catalogVersion: number;
  data: DiagnosticChannelData;
}

export interface DiagnosticChannelStrategy {
  initialState: DiagnosticChannelStrategyState;
  reducer: EnvelopeReducer<DiagnosticChannelStrategyState>;
}

export interface DiagnosticChannelStrategyOptions {
  /** Ring-buffer cap; defaults to {@link DEFAULT_DIAGNOSTIC_CAP}. */
  cap?: number;
  /**
   * Initial sub-category filter. Empty / omitted = accept all (server's
   * DELIVERY_INTERNAL drop is the only effective filter).
   */
  subCategoryFilter?: Iterable<SubCategory>;
}

/**
 * Returns a strategy bundle ready for `EnvelopeStream<DiagnosticChannelStrategyState>`
 * consumption.
 */
export function diagnosticChannelStrategy(
  options: DiagnosticChannelStrategyOptions = {},
): DiagnosticChannelStrategy {
  const cap = options.cap ?? DEFAULT_DIAGNOSTIC_CAP;
  const initialFilter: ReadonlySet<SubCategory> = new Set(
    options.subCategoryFilter ?? [],
  );
  return {
    initialState: {
      catalogVersion: 0,
      data: { events: [], cap, subCategoryFilter: initialFilter },
    },
    reducer: (s, env) => {
      const next: DiagnosticChannelStrategyState = {
        catalogVersion: env.seq,
        data: s.data,
      };
      if (env.frameKind === 'LIFECYCLE') {
        const p = env.payload as { kind?: string };
        if (p.kind === 'reset') {
          next.data = { ...s.data, events: [] };
        }
        // 'connected' / 'heartbeat' / 'closing' / 'snapshot' (firehose) are
        // state-noop beyond seq advancement.
      } else if (env.frameKind === 'UPDATE') {
        const p = env.payload as DiagnosticEventEnvelope | undefined;
        if (p && p.kind === KIND_LOG_EVENT && p.event) {
          if (
            s.data.subCategoryFilter.size > 0 &&
            !s.data.subCategoryFilter.has(p.event.subCategory)
          ) {
            return next; // filtered out client-side
          }
          const events = [...s.data.events, p.event];
          if (events.length > cap) events.splice(0, events.length - cap);
          next.data = { ...s.data, events };
        }
      }
      return next;
    },
  };
}
