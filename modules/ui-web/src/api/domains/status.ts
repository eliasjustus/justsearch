// SPDX-License-Identifier: Apache-2.0
/**
 * Status domain API - System health and UI handshake
 */

import { request } from '../http';

// ============================================
// Types
// ============================================

interface UiReadyHandshakeV1 {
  schema: 'UI_READY_HANDSHAKE_V1';
  runId?: string | null;
  runtime: 'browser' | 'tauri' | 'javafx' | 'unknown';
  apiSource: string;
  uiConnectedAtMs?: number | null;
  meta?: Record<string, unknown>;
}

// ============================================
// API Functions
// ============================================

/**
 * UI-ready handshake (best-effort; used by desktop parity smoke harness).
 */
export async function postUiReady(baseUrl: string, payload: UiReadyHandshakeV1): Promise<void> {
  await request<Record<string, unknown>>(baseUrl, '/api/ui/ready', { method: 'POST', body: payload, retries: 1 });
}

/**
 * Switches inference mode between 'online' and 'indexing'.
 */
export async function switchInferenceMode(
  baseUrl: string,
  mode: 'online' | 'indexing',
  signal?: AbortSignal
): Promise<void> {
  await request(baseUrl, '/api/inference/mode', {
    method: 'POST',
    body: { mode },
    signal,
  });
}


