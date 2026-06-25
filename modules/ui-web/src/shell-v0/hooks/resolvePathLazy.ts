// SPDX-License-Identifier: Apache-2.0
/**
 * Slice 3a.1.9 §A.7 — Path-hash resolver helper.
 *
 * For Resources with `Privacy.HASHED_REQUIRES_RESOLVER`, the wire
 * carries SHA-256 path hashes. When a user gesture demands the path
 * string (open file, copy path, view in explorer), the FE invokes
 * the Resource's `privacy.resolver` Operation with the row's primary
 * key.
 *
 * This helper:
 *  - Reads the Resource's privacy axis from the catalog.
 *  - Invokes the resolver Operation via the existing OperationClient.
 *  - Memoizes results per session (paths don't outlive the browser
 *    session; in-memory cache only — no localStorage).
 *  - Returns null when the Resource doesn't declare a resolver, the
 *    Operation isn't reachable, or the resolver returns not-found.
 *
 * Per ADR-0028 + LibraryResolveHashOnlyCallerPin: this helper is a
 * substrate-routed equivalent of the existing
 * `POST /api/library/resolve-hash` endpoint. It doesn't bypass the
 * pin — the OperationClient calls /api/operations/{id}/invoke,
 * which dispatches to ResolvePathHashHandler, which calls
 * IndexingService.resolvePathHash (an approved caller).
 */

import { OperationClient, type OperationInvocationSuccess } from '../operations/OperationClient.js';
import { getResource } from '../../api/registry/ResourceCatalogClient.js';

/** In-memory cache. Cleared on app reload. */
const cache: Map<string, string | null> = new Map();
const inflight: Map<string, Promise<string | null>> = new Map();

interface ResolvePathOptions {
  /** Optional API base override (defaults to window.location.origin). */
  apiBase?: string;
  /** Optional fetch override (for tests). */
  fetchImpl?: typeof fetch;
}

/**
 * Resolves a row's primary-key value (path hash) to the path string the
 * worker has on record. Returns null when:
 *  - the Resource isn't in the FE catalog,
 *  - the Resource's `privacy.resolver` is null,
 *  - the resolver Operation reports `found: false`,
 *  - the Operation invocation throws.
 *
 * Memoized per `(resourceId, primaryKey)` for the session.
 *
 * @param resourceId Resource catalog id (e.g., `core.indexing-jobs`)
 * @param primaryKey value of the row's primary-key field (e.g., the
 *   sha256 hex `pathHash`)
 */
export async function resolvePathLazy(
  resourceId: string,
  primaryKey: string,
  options: ResolvePathOptions = {},
): Promise<string | null> {
  const cacheKey = `${resourceId}::${primaryKey}`;
  const cached = cache.get(cacheKey);
  if (cached !== undefined) return cached;
  const pending = inflight.get(cacheKey);
  if (pending) return pending;

  const resource = getResource(resourceId);
  if (!resource) {
    cache.set(cacheKey, null);
    return null;
  }
  if (resource.privacy.pathPolicy !== 'HASHED_REQUIRES_RESOLVER') {
    // The caller asked for resolution on a non-hashed resource;
    // probably bug, but return null defensively.
    cache.set(cacheKey, null);
    return null;
  }
  const resolverId = resource.privacy.resolver;
  if (!resolverId) {
    cache.set(cacheKey, null);
    return null;
  }

  const apiBase =
    options.apiBase ??
    (typeof globalThis !== 'undefined' &&
    (globalThis as { location?: { origin?: string } }).location?.origin
      ? (globalThis as { location: { origin: string } }).location.origin
      : '');

  const client = new OperationClient({
    apiBase,
    fetchImpl: options.fetchImpl,
  });

  // Resolver argument key matches the primary-key field name (slice
  // 445 convention: `pathHash`). Future Resources with a different
  // primary-key name pass that name through here.
  const argName = resource.primaryKey;
  if (!argName) {
    cache.set(cacheKey, null);
    return null;
  }

  const promise: Promise<string | null> = client
    .invoke(resolverId, { args: { [argName]: primaryKey } })
    .then((success: OperationInvocationSuccess) => {
      const data = success.structuredData;
      if (!data || data['found'] !== true) return null;
      const path = data['path'];
      return typeof path === 'string' ? path : null;
    })
    .catch(() => null)
    .finally(() => {
      inflight.delete(cacheKey);
    })
    .then((result: string | null) => {
      cache.set(cacheKey, result);
      return result;
    });

  inflight.set(cacheKey, promise);
  return promise;
}

/** Test-only: clear all cached resolutions. */
export function __resetForTest(): void {
  cache.clear();
  inflight.clear();
}

/** Test-only: pre-populate a resolution. */
export function __seedForTest(
  resourceId: string,
  primaryKey: string,
  path: string | null,
): void {
  cache.set(`${resourceId}::${primaryKey}`, path);
}
