// SPDX-License-Identifier: Apache-2.0
/**
 * Suggest domain API - Autocomplete suggestions
 */

import { request } from '../http';

interface SuggestResponse {
  suggestions: string[];
}

/**
 * Fetch autocomplete suggestions from the backend.
 * GET /api/knowledge/suggest?query={q}&limit={n}
 */
export async function suggest(
  baseUrl: string,
  query: string,
  limit = 8,
  signal?: AbortSignal,
): Promise<SuggestResponse> {
  const params = new URLSearchParams({ query, limit: String(limit) });
  return request<SuggestResponse>(baseUrl, `/api/knowledge/suggest?${params}`, { signal });
}
