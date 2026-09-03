/* SPDX-License-Identifier: Apache-2.0 */
import { AsyncLocalStorage } from 'node:async_hooks';

export type RuntimeFetch = typeof globalThis.fetch;

export interface RuntimeTransportOptions {
  baseUrl: string;
  fetch?: RuntimeFetch;
}

export interface RuntimeHttpResponse<T> {
  data: T;
  status: number;
  headers: Headers;
}

interface ResolvedRuntimeTransport {
  baseUrl: URL;
  fetch: RuntimeFetch;
}

const transportContext = new AsyncLocalStorage<ResolvedRuntimeTransport>();

export function resolveRuntimeTransport(options: RuntimeTransportOptions): ResolvedRuntimeTransport {
  const baseUrl = new URL(options.baseUrl);
  const hostname = baseUrl.hostname.replace(/^\[(.*)]$/, '$1').toLowerCase();
  if (baseUrl.protocol !== 'http:' || !['127.0.0.1', 'localhost', '::1'].includes(hostname)) {
    throw new TypeError('JustSearch runtime baseUrl must be an http:// loopback URL');
  }
  if (baseUrl.username || baseUrl.password || baseUrl.search || baseUrl.hash) {
    throw new TypeError('JustSearch runtime baseUrl must not contain credentials, query, or fragment');
  }
  if (baseUrl.pathname !== '/') {
    throw new TypeError('JustSearch runtime baseUrl must not contain a path');
  }
  return { baseUrl, fetch: options.fetch ?? globalThis.fetch };
}

export function withRuntimeTransport<T>(
  transport: ResolvedRuntimeTransport,
  operation: () => Promise<T>,
): Promise<T> {
  return transportContext.run(transport, operation);
}

export async function runtimeFetch<T>(url: string, init: RequestInit): Promise<T> {
  const transport = transportContext.getStore();
  if (!transport) {
    throw new Error('Generated runtime operation called without createRuntimeClient()');
  }
  const response = await transport.fetch(new URL(url, transport.baseUrl), init);
  const body = await response.text();
  const data = body.length === 0 ? undefined : JSON.parse(body);
  return { data, status: response.status, headers: response.headers } as T;
}
