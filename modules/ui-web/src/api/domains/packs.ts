// SPDX-License-Identifier: Apache-2.0
/**
 * Packs domain API - AI installation, offline packs, policy, and diagnostics
 */

import { request } from '../http';
import { parseWireContract } from '../schemas';
// Tempdoc 564 Phase B (4b): EffectivePolicy + AiPackImportStatus are generated wire-contract
// projections (record → JSON Schema → {TS, Zod}); the hand types + fail-open `.loose()` Zod are retired.
import {
  effectivePolicySchema,
  type EffectivePolicy,
} from '../generated/schema-types/effective-policy';
import {
  aiPackImportStatusSchema,
  type AiPackImportStatus,
} from '../generated/schema-types/ai-pack-import-status';

// Re-export the generated wire types so existing consumers keep a stable import path.
export type { EffectivePolicy } from '../generated/schema-types/effective-policy';
export type { AiPackImportStatus } from '../generated/schema-types/ai-pack-import-status';

// ============================================
// v1 Simple Mode: AI Install Types
// ============================================

export interface AiInstallManifestAsset {
  id: string;
  filename: string;
  sha256: string;
  sizeBytes: number;
  urls: string[];
  termsUrl?: string;
  notes?: string;
  label?: string;
  description?: string;
}

export interface AiInstallManifest {
  schemaVersion: number;
  registryVersion: string;
  purpose?: string;
  assets: AiInstallManifestAsset[];
}

export interface AiInstallAssetStatus {
  id: string;
  filename: string;
  url: string;
  sha256: string;
  sizeBytes: number;
  state: string;
  bytesDownloaded: number;
  bytesTotal: number;
  error?: string;
}

export interface AiInstallStatus {
  state: string;
  phase: string;
  message?: string;
  startedAtEpochMs?: number;
  updatedAtEpochMs?: number;
  cancelRequested?: boolean;
  lastError?: string;
  errorCode?: string;
  /**
   * True only when state === "completed" AND no packages were skipped/failed.
   * False after partial-success completion (e.g., chat skipped because no
   * CUDA). Tempdoc 374 finding #8.
   */
  installedFully?: boolean;
  assets: AiInstallAssetStatus[];
}

// ============================================
// v2: Offline AI Packs + enterprise policy
// ============================================

// EffectivePolicy + EffectivePolicySource are now generated (see imports above).

interface CreateUserPolicyResult {
  success: boolean;
  path: string;
}

interface UpdateUserPolicyAllowlistResult {
  success: boolean;
  path: string;
  changed: boolean;
  allowlistedCount: number;
}

export interface AiPackPreflightResult {
  packId: string;
  packVersion: string;
  manifestSha256: string;
}

// AiPackImportStatus is now generated (see imports above).

// observations.md: Installed-packs FE types/functions/schemas were dead post
// React-migration — `getInstalledPacks` had zero callers. The BE
// /api/ai/packs/installed endpoint remains (still used by an integration
// test); add a fresh FE consumer here when the Brain surface needs it.

// ============================================
// v1 Simple Mode API Functions
// ============================================

/**
 * Gets the AI install manifest.
 */
export async function getAiInstallManifest(baseUrl: string, signal?: AbortSignal): Promise<AiInstallManifest> {
  return request<AiInstallManifest>(baseUrl, '/api/ai/install/manifest', { method: 'GET', signal });
}

/**
 * Gets the AI install status.
 */
export async function getAiInstallStatus(baseUrl: string, signal?: AbortSignal): Promise<AiInstallStatus> {
  return request<AiInstallStatus>(baseUrl, '/api/ai/install/status', { method: 'GET', signal });
}

/**
 * Starts the AI installation process.
 */
export async function startAiInstall(
  baseUrl: string,
  acceptTerms: boolean,
  signal?: AbortSignal
): Promise<AiInstallStatus> {
  return request<AiInstallStatus>(baseUrl, '/api/ai/install/start', {
    method: 'POST',
    body: { acceptTerms },
    signal,
  });
}

/**
 * Cancels the AI installation process.
 */
export async function cancelAiInstall(baseUrl: string, signal?: AbortSignal): Promise<AiInstallStatus> {
  return request<AiInstallStatus>(baseUrl, '/api/ai/install/cancel', { method: 'POST', body: {}, signal });
}

/**
 * Repairs the AI installation.
 */
export async function repairAiInstall(
  baseUrl: string,
  acceptTerms: boolean,
  signal?: AbortSignal
): Promise<AiInstallStatus> {
  return request<AiInstallStatus>(baseUrl, '/api/ai/install/repair', {
    method: 'POST',
    body: { acceptTerms },
    signal,
  });
}

// ============================================
// v2 Policy API Functions
// ============================================

/**
 * Gets the effective policy for the current user with dev-mode validation.
 */
export async function getEffectivePolicy(baseUrl: string, signal?: AbortSignal): Promise<EffectivePolicy> {
  const data = await request<unknown>(baseUrl, '/api/policy/effective', { method: 'GET', signal });
  return parseWireContract(effectivePolicySchema, data, 'GET /api/policy/effective');
}

/**
 * Creates a user policy to allow pack import.
 */
export async function createUserPolicyForPackImport(
  baseUrl: string,
  manifestSha256: string,
  signal?: AbortSignal
): Promise<CreateUserPolicyResult> {
  return request<CreateUserPolicyResult>(baseUrl, '/api/policy/user/create', {
    method: 'POST',
    body: { manifestSha256 },
    signal,
  });
}

/**
 * Adds a pack manifest digest to the existing user policy allowlist (schemaVersion=1 only).
 */
export async function addPackDigestToUserPolicyAllowlist(
  baseUrl: string,
  manifestSha256: string,
  signal?: AbortSignal
): Promise<UpdateUserPolicyAllowlistResult> {
  return request<UpdateUserPolicyAllowlistResult>(baseUrl, '/api/policy/user/allowlist/pack-manifest/add', {
    method: 'POST',
    body: { manifestSha256 },
    signal,
  });
}

// ============================================
// v2 AI Packs API Functions
// ============================================

/**
 * Gets the current AI pack import status with dev-mode validation.
 */
export async function getAiPackStatus(baseUrl: string, signal?: AbortSignal): Promise<AiPackImportStatus> {
  const data = await request<unknown>(baseUrl, '/api/ai/packs/status', { method: 'GET', signal });
  return parseWireContract(aiPackImportStatusSchema, data, 'GET /api/ai/packs/status');
}

/**
 * Preflights an AI pack import (validates the pack before import).
 */
export async function preflightAiPack(
  baseUrl: string,
  path: string,
  signal?: AbortSignal
): Promise<AiPackPreflightResult> {
  return request<AiPackPreflightResult>(baseUrl, '/api/ai/packs/preflight', {
    method: 'POST',
    body: { path },
    signal,
  });
}

/**
 * Imports an AI pack.
 */
export async function importAiPack(
  baseUrl: string,
  path: string,
  allowDowngrade = false,
  signal?: AbortSignal
): Promise<AiPackImportStatus> {
  return request<AiPackImportStatus>(baseUrl, '/api/ai/packs/import', {
    method: 'POST',
    body: { path, allowDowngrade },
    signal,
  });
}

// ============================================
// Diagnostics API Functions
// ============================================

/**
 * Exports diagnostics bundle.
 */
export async function exportDiagnostics(
  baseUrl: string,
  signal?: AbortSignal
): Promise<{ success: boolean; path: string }> {
  return request<{ success: boolean; path: string }>(baseUrl, '/api/diagnostics/export', {
    method: 'POST',
    body: {},
    signal,
  });
}


