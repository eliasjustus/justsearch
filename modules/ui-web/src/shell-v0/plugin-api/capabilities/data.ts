// SPDX-License-Identifier: Apache-2.0
/**
 * host.data capability module (548 §4.2 Increment A) — extracted from
 * HostApiImpl. Owns the OperationClient, the UNTRUSTED read-only fetch
 * allowlist, and the trusted/untrusted fetch composition (UNTRUSTED: GET-only,
 * allowlisted paths), plus invokeOperation + pooled resource/health
 * subscriptions. Trust attenuation is composition (521 §2.3): the fetch variant
 * is selected by tier, no runtime if(tier) in any method body.
 */

import { OperationClient } from '../../operations/OperationClient.js';
import { subscribePooled } from '../../streaming/EnvelopeStreamPool.js';
import type {
  PluginHostApi,
  PluginTrustTier,
  OperationResult,
  HostFetchInit,
  Unsubscribe,
} from '../plugin-types.js';

type PluginData = PluginHostApi['data'];

// Allowlisted read-only API paths for UNTRUSTED plugins.
const UNTRUSTED_READ_ALLOWLIST = [
  '/api/health',
  '/api/status',
  '/api/knowledge/search',
  '/api/registry/',
];

function isAllowlistedForUntrusted(path: string): boolean {
  return UNTRUSTED_READ_ALLOWLIST.some((prefix) => path.startsWith(prefix));
}

async function performFetch(apiBase: string, path: string, init?: HostFetchInit): Promise<Response> {
  const url = `${apiBase}${path}`;
  const fetchInit: RequestInit = {};
  if (init?.method) fetchInit.method = init.method;
  if (init?.headers) fetchInit.headers = init.headers;
  if (init?.body) {
    fetchInit.body = typeof init.body === 'string' ? init.body : JSON.stringify(init.body);
    fetchInit.headers = {
      'Content-Type': 'application/json',
      ...fetchInit.headers as Record<string, string>,
    };
  }
  if (init?.signal) fetchInit.signal = init.signal;
  return globalThis.fetch(url, fetchInit);
}

export function createDataApi(tier: PluginTrustTier, apiBase: string): PluginData {
  const client = new OperationClient({ apiBase });
  const isUntrusted = tier === 'UNTRUSTED_PLUGIN';

  const fetchFn = isUntrusted
    ? async (path: string, init?: HostFetchInit): Promise<Response> => {
        if (init?.method && init.method !== 'GET') {
          throw new Error('UNTRUSTED plugins may only perform GET requests');
        }
        if (!isAllowlistedForUntrusted(path)) {
          throw new Error(`UNTRUSTED plugins may not access ${path}`);
        }
        return performFetch(apiBase, path, init);
      }
    : (path: string, init?: HostFetchInit) => performFetch(apiBase, path, init);

  return {
    fetch: fetchFn,
    invokeOperation: async (
      id: string,
      params?: Record<string, unknown>,
      opts?: { consented?: boolean },
    ): Promise<OperationResult> => {
      // Tempdoc 550 C3 (merged from main): route through invokeWithConsent. For a
      // consented HIGH-risk op it recovers the backend 428 by approving the
      // backend-issued pending by id and re-invoking with the minted capsule;
      // AUTO-gated ops just invoke once.
      const result = await client.invokeWithConsent(
        id,
        { args: params },
        { consented: opts?.consented === true },
      );
      return {
        success: result.success,
        message: result.message,
        executionId: result.executionId,
        structuredData: result.structuredData,
      };
    },
    subscribeResource: (id: string, handler: (data: unknown) => void): Unsubscribe => {
      // Tempdoc 508-followup §γ3 — pool by URL so N subscribers to the same
      // resource id share one EventSource.
      const url = `${apiBase.replace(/\/$/, '')}/api/registry/resources/${encodeURIComponent(id)}/stream`;
      return subscribePooled<unknown>(
        url,
        (snap) => handler(snap.payload),
        () => ({ url, initialState: null, reducer: (_state, frame) => frame.payload }),
      );
    },
    subscribeHealth: (handler: (status: unknown) => void): Unsubscribe => {
      const url = `${apiBase.replace(/\/$/, '')}/api/health/events/stream`;
      return subscribePooled<unknown>(
        url,
        (snap) => handler(snap.payload),
        () => ({ url, initialState: null, reducer: (_state, frame) => frame.payload }),
      );
    },
  };
}
