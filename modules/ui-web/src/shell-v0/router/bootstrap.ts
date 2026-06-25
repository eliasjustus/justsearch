// SPDX-License-Identifier: Apache-2.0
/**
 * Router bootstrap — slice 489 §6 / §7.
 *
 * One-time setup the chrome calls during boot:
 *   1. Register the concrete FE stores as `StoreAdapter`s (the abstract
 *      storeId → store mapping the schemas reference).
 *   2. Fetch `GET /api/registry/surfaces` and register each surface's
 *      `stateSchema` (if present) in the local schema cache.
 *
 * After bootstrap completes:
 *   - URLSource (slice 492) can parse `?...` query params from the hash
 *     and dispatch Navigation Intents that the NavigationHandler realizes
 *     against the registered surfaces' schemas.
 *   - URLProjector can subscribe to the relevant stores on activation.
 *
 * The two-phase split (register stores first, then fetch schemas) means
 * schemas can reference stores added later (forward-compat) and the local
 * fallback in `surfaceSchemas.resolveSurfaceStateSchema` (drop bindings to
 * unknown stores) handles gaps gracefully.
 */

import {
  serializeSearch,
  restoreSearch,
  subscribeSearch,
} from '../state/searchState.js';
import {
  serializeSearchFilters,
  restoreSearchFilters,
  subscribeFilters,
} from '../state/searchFiltersState.js';
import {
  serializeAskChat,
  restoreAskChat,
  subscribeAskChat,
} from '../state/askChatState.js';
import {
  serializeAgentChat,
  restoreAgentChat,
  subscribeAgentChat,
} from '../state/agentChatState.js';
import {
  serializeUnifiedChat,
  restoreUnifiedChat,
  subscribeUnifiedChat,
} from '../state/unifiedChatState.js';
import * as selectionStateMod from '../state/selectionState.js';
import { registerStore, type StoreAdapter } from './storeRegistry.js';
import {
  registerSurfaceStateSchema,
  type RawSurfaceStateSchema,
} from './surfaceSchemas.js';
import type { StateSnapshot } from './types.js';

interface SurfaceCatalogEnvelope {
  entries?: Array<{
    id?: string;
    stateSchema?: {
      schema?: string;
      bindings?: Array<{
        schemaPath?: string;
        storeId?: string;
        storeKey?: string;
      }>;
    };
  }>;
}

let storesRegistered = false;
let schemasFetched = false;

/** Register the v1 set of concrete stores. Idempotent. */
export function registerCoreStores(): void {
  if (storesRegistered) return;
  storesRegistered = true;
  registerStore(buildSearchAdapter());
  registerStore(buildSearchFiltersAdapter());
  registerStore(buildAskChatAdapter());
  registerStore(buildAgentChatAdapter());
  registerStore(buildUnifiedChatAdapter());
}

/**
 * Fetch the surface catalog and register every entry that declares a
 * `stateSchema`. Surfaces without a schema are skipped (per §17.2 optional).
 */
export async function fetchAndRegisterSurfaceSchemas(apiBase: string): Promise<void> {
  if (schemasFetched) return;
  const url = (apiBase || '') + '/api/registry/surfaces';
  let envelope: SurfaceCatalogEnvelope;
  try {
    const res = await fetch(url);
    if (!res.ok) {
      // eslint-disable-next-line no-console
      console.warn(
        `[router/bootstrap] GET ${url} returned ${res.status}; surface schemas unavailable`,
      );
      return;
    }
    envelope = (await res.json()) as SurfaceCatalogEnvelope;
  } catch (err) {
    // eslint-disable-next-line no-console
    console.warn(`[router/bootstrap] GET ${url} failed:`, err);
    return;
  }
  schemasFetched = true;
  const entries = envelope.entries ?? [];
  for (const entry of entries) {
    if (!entry.id || !entry.stateSchema) continue;
    const raw = normalizeRawSchema(entry.stateSchema);
    if (raw) registerSurfaceStateSchema(entry.id, raw);
  }
}

/** Test-only: reset both stages so vitest can re-run bootstrap cleanly. */
export function __resetBootstrapForTest(): void {
  storesRegistered = false;
  schemasFetched = false;
}

// ----- adapters -----

function buildSearchAdapter(): StoreAdapter {
  return {
    storeId: 'search',
    serialize(): StateSnapshot {
      const slice = serializeSearch();
      const out: StateSnapshot = {};
      if (slice.query !== undefined) out.query = slice.query;
      return out;
    },
    restore(snapshot: StateSnapshot): void {
      restoreSearch({ query: snapshot.query as string | string[] | undefined });
    },
    subscribe(listener: (s: StateSnapshot) => void): () => void {
      return subscribeSearch((s) => {
        listener(s.query ? { query: s.query } : {});
      });
    },
  };
}

function buildSearchFiltersAdapter(): StoreAdapter {
  return {
    storeId: 'search.filters',
    serialize(): StateSnapshot {
      const slice = serializeSearchFilters();
      const out: StateSnapshot = {};
      if (slice.modifiedFromMs !== undefined) out.modifiedFromMs = slice.modifiedFromMs;
      if (slice.modifiedToMs !== undefined) out.modifiedToMs = slice.modifiedToMs;
      return out;
    },
    restore(snapshot: StateSnapshot): void {
      restoreSearchFilters({
        modifiedFromMs: snapshot.modifiedFromMs as string | string[] | undefined,
        modifiedToMs: snapshot.modifiedToMs as string | string[] | undefined,
      });
    },
    subscribe(listener: (s: StateSnapshot) => void): () => void {
      return subscribeFilters((s) => {
        const out: StateSnapshot = {};
        if (typeof s.modifiedFromMs === 'number') {
          out.modifiedFromMs = String(s.modifiedFromMs);
        }
        if (typeof s.modifiedToMs === 'number') {
          out.modifiedToMs = String(s.modifiedToMs);
        }
        listener(out);
      });
    },
  };
}

function buildAgentChatAdapter(): StoreAdapter {
  return {
    storeId: 'agent',
    serialize(): StateSnapshot {
      return serializeAgentChat();
    },
    restore(snapshot: StateSnapshot): void {
      restoreAgentChat({
        initialMessage: snapshot.initialMessage as string | string[] | undefined,
        sessionId: snapshot.sessionId as string | string[] | undefined,
      });
    },
    subscribe(listener: (s: StateSnapshot) => void): () => void {
      return subscribeAgentChat((s) => {
        const out: StateSnapshot = {};
        if (s.initialMessage) out.initialMessage = s.initialMessage;
        if (s.sessionId) out.sessionId = s.sessionId;
        listener(out);
      });
    },
  };
}

function buildAskChatAdapter(): StoreAdapter {
  return {
    storeId: 'ask',
    serialize(): StateSnapshot {
      const s = serializeAskChat();
      const out: StateSnapshot = {};
      if (s.query) out.query = s.query;
      if (s.docIds && s.docIds.length > 0) out.docIds = s.docIds;
      return out;
    },
    restore(snapshot: StateSnapshot): void {
      restoreAskChat({
        query: snapshot.query as string | string[] | undefined,
        docIds: snapshot.docIds as string | string[] | undefined,
      });
    },
    subscribe(listener: (s: StateSnapshot) => void): () => void {
      return subscribeAskChat((s) => {
        const out: StateSnapshot = {};
        if (s.query) out.query = s.query;
        if (s.docIds.length > 0) out.docIds = s.docIds;
        listener(out);
      });
    },
  };
}

function buildUnifiedChatAdapter(): StoreAdapter {
  return {
    storeId: 'unified-chat',
    serialize(): StateSnapshot {
      // Tempdoc 526 §14.5 T7 — selectionState.result-set is the source of
      // truth for docIds. We pull from selectionState here so existing
      // surface state-schema bindings (`{storeId: 'unified-chat',
      // storeKey: 'docIds'}`) keep persisting the right data to the URL
      // without requiring a server-side surface catalog change.
      const s = serializeUnifiedChat();
      const out: StateSnapshot = {};
      if (s.query) out.query = s.query;
      if (s.affordance) out.affordance = s.affordance;
      const cur = selectionStateMod.getSelection().items[0];
      if (cur && cur.kind === 'result-set' && cur.items.length > 0) {
        out.docIds = cur.items.map((r) => r.id);
      }
      return out;
    },
    restore(snapshot: StateSnapshot): void {
      restoreUnifiedChat({
        query: snapshot.query as string | string[] | undefined,
        affordance: snapshot.affordance as string | string[] | undefined,
      });
      // Tempdoc 526 §14.5 T7 — bridge ?docIds= → selectionState.result-set.
      // The legacy URL key persists for one release; the destination is the
      // substrate, not the legacy store.
      const raw = snapshot.docIds;
      const ids =
        typeof raw === 'string' ? [raw] : Array.isArray(raw) ? (raw as string[]) : [];
      if (ids.length > 0) {
        selectionStateMod.setSingleSelection(
          {
            kind: 'result-set',
            items: ids.map((id) => ({ id, kind: 'doc' as const })),
            capabilities: selectionStateMod.DEFAULT_CAPABILITIES_BY_KIND['result-set'],
          },
          'core.url-restore',
        );
      }
    },
    subscribe(listener: (s: StateSnapshot) => void): () => void {
      // Tempdoc 526 §16 F9 — single subscription path. The legacy listener
      // reads the selection state synchronously on each emit. Selection-only
      // changes (e.g., F9 menu picks) don't re-emit a URL snapshot: that's
      // correct because URL state for selection rides through the same
      // legacy `docIds` URL key.
      return subscribeUnifiedChat((s) => {
        const out: StateSnapshot = {};
        if (s.query) out.query = s.query;
        if (s.affordance !== 'none') out.affordance = s.affordance;
        const cur = selectionStateMod.getSelection().items[0];
        if (cur && cur.kind === 'result-set' && cur.items.length > 0) {
          out.docIds = cur.items.map((r) => r.id);
        }
        listener(out);
      });
    },
  };
}

function normalizeRawSchema(
  s: {
    schema?: string;
    bindings?: Array<{ schemaPath?: string; storeId?: string; storeKey?: string }>;
  },
): RawSurfaceStateSchema | null {
  if (!s.schema) return null;
  const bindings: RawSurfaceStateSchema['bindings'] = [];
  for (const b of s.bindings ?? []) {
    if (typeof b.schemaPath === 'string' && typeof b.storeId === 'string') {
      bindings.push({
        schemaPath: b.schemaPath,
        storeId: b.storeId,
        storeKey: b.storeKey ?? '',
      });
    }
  }
  return { schema: s.schema, bindings };
}
