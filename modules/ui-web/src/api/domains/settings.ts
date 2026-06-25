// SPDX-License-Identifier: Apache-2.0
/**
 * Settings domain API - User preferences and configuration
 */

import { request } from '../http';

// ============================================
// Types
// ============================================

export interface UISettings {
  theme?: 'system' | 'dark' | 'light' | undefined;
  highContrast?: boolean | undefined;
  density?: 'compact' | 'comfort' | 'rich' | undefined;
  defaultAction?: 'open' | 'reveal' | 'preview' | undefined;
  inspectorWidth?: number | undefined;
  // Power-user AI behavior (client-side only; server may ignore)
  pauseIndexingDuringAi?: boolean | undefined;
  // Progressive disclosure mode (default: simple)
  mode?: 'simple' | 'advanced' | undefined;
  // One-time trust-loop teaching moment (citations)
  hasSeenTrustLoopNudge?: boolean | undefined;
  // Glob patterns to exclude from indexing/search (applied via explicit action)
  excludePatterns?: string[] | undefined;
  // Vim-style keyboard navigation
  vimMode?: boolean | undefined;
}

export interface LLMSettings {
  // Desktop/Tauri only: optional BYO llama-server executable path override
  serverExecutable?: string | undefined;
  contextWindow?: number | undefined;
  maxTokens?: number | undefined;
  gpuLayers?: number | undefined;
  modelPath?: string | undefined;
  llamaLibPath?: string | undefined;
}

export interface AppSettings {
  ui?: UISettings | undefined;
  llm?: LLMSettings | undefined;
  indexPaths?: string[] | undefined;
  settingsMode?: 'read_write' | 'in_memory' | undefined;
}

// ============================================
// API Functions
// ============================================

/**
 * Updates settings (canonical v2 endpoint).
 */
export async function updateSettingsV2(
  baseUrl: string,
  settings: Partial<AppSettings>,
  signal?: AbortSignal
): Promise<AppSettings> {
  return request<AppSettings>(baseUrl, '/api/settings/v2', {
    method: 'POST',
    body: settings,
    signal,
  });
}
