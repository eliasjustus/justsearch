// SPDX-License-Identifier: Apache-2.0
/**
 * The one authorized-request seam for shell-v0 (tempdoc 804 B3).
 *
 * The packaged shell boots the backend with `prod=true`, where
 * `ApiSecurityFilters` requires the session-token header on POST/PUT/DELETE
 * (GET/OPTIONS are exempt, and there are no path exemptions). shell-v0 issued
 * bare `fetch` from every mutating call site, so every write 401'd in the
 * shipped app while dev/test tiers (no `prod=true`) stayed green.
 *
 * This helper is deliberately NOT a second token authority: the resolver
 * (`resolveSessionTokenFromTauri`), the cache reader (`getSessionToken`) and the
 * header constant (`SESSION_TOKEN_HEADER`) all stay in `api/http`. It only
 * decides WHEN to attach — awaiting resolution before headers are built, the
 * same ordering `api/streams.ts` uses for its stream POSTs.
 */

import {
  SESSION_TOKEN_HEADER,
  getSessionToken,
  resolveSessionTokenFromTauri,
} from '../../api/http.js';

/** Methods the backend exempts from token enforcement (ApiSecurityFilters). */
const TOKEN_FREE_METHODS = new Set(['GET', 'HEAD']);

/**
 * The one sanctioned reference to the raw global — this seam's exit. Resolved per
 * call (never bound at module load) so runtime fetch mocks still take effect.
 */
function globalFetch(input: RequestInfo | URL, init?: RequestInit): Promise<Response> {
  // eslint-disable-next-line no-restricted-syntax
  return globalThis.fetch(input, init);
}

function headerAlreadySet(headers: HeadersInit | undefined): boolean {
  if (!headers) return false;
  const key = SESSION_TOKEN_HEADER.toLowerCase();
  if (headers instanceof Headers) return headers.has(SESSION_TOKEN_HEADER);
  if (Array.isArray(headers)) return headers.some(([k]) => k.toLowerCase() === key);
  return Object.keys(headers).some((k) => k.toLowerCase() === key);
}

function withToken(headers: HeadersInit | undefined, token: string): HeadersInit {
  if (headers instanceof Headers) {
    const merged = new Headers(headers);
    merged.set(SESSION_TOKEN_HEADER, token);
    return merged;
  }
  if (Array.isArray(headers)) {
    return [...headers, [SESSION_TOKEN_HEADER, token]];
  }
  // Plain-record shape is preserved (call sites and their tests read it back as one).
  return { ...(headers ?? {}), [SESSION_TOKEN_HEADER]: token } as Record<string, string>;
}

function resolveMethod(input: RequestInfo | URL, init?: RequestInit): string {
  if (init?.method) return init.method.toUpperCase();
  if (typeof Request !== 'undefined' && input instanceof Request) {
    return input.method.toUpperCase();
  }
  return 'GET';
}

/**
 * `fetch` with the desktop session token attached to mutating requests.
 *
 * Signature-compatible with the global `fetch`, so it can be dropped in as a
 * `fetchImpl` default. GET/HEAD pass straight through. An explicit
 * `X-JustSearch-Session` header set by the caller always wins.
 */
export async function authorizedFetch(
  input: RequestInfo | URL,
  init?: RequestInit,
): Promise<Response> {
  const method = resolveMethod(input, init);
  if (TOKEN_FREE_METHODS.has(method)) {
    return globalFetch(input, init);
  }

  // Ordering matters: resolve BEFORE building headers, so a request issued
  // during cold start blocks on the token instead of racing past it.
  await resolveSessionTokenFromTauri();
  const token = getSessionToken();

  // Headers carried by a Request input are only visible when `init` supplies
  // none — that mirrors fetch's own precedence (init.headers replaces them).
  const callerHeaders =
    init?.headers ??
    (typeof Request !== 'undefined' && input instanceof Request ? input.headers : undefined);

  if (!token || headerAlreadySet(callerHeaders)) {
    return globalFetch(input, init);
  }

  return globalFetch(input, { ...init, headers: withToken(callerHeaders, token) });
}
