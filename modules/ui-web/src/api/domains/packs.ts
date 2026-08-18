// SPDX-License-Identifier: Apache-2.0
/**
 * Packs domain API - AI installation, offline packs, policy, and diagnostics
 */

import { request } from '../http';
import { parseWireContract } from '../schemas';
import { summarizeWireDrift } from '../wireDriftTelemetry';
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

// The manifest wire shape is the v2 `ModelRegistry` record
// (`modules/configuration/src/main/java/io/justsearch/configuration/model/ModelRegistry.java`),
// serialized as-is by `GET /api/ai/install/manifest`. The pre-v2 flat `assets[]` types that used to
// live here modelled a registry format that no longer exists on the wire.

/** One downloadable model file — a precision/EP variant of a package's model. */
export interface AiInstallModelVariant {
  filename: string;
  /** `ModelPrecision` constant (e.g. `FP32`, `FP16`, `INT8`, `Q4_K_M`). */
  precision?: string;
  /** `ExecutionProvider` constant (e.g. `CPU`, `CUDA`) this variant targets. */
  targetEP?: string;
  sha256: string;
  sizeBytes: number;
  downloadUrl: string;
}

/** A non-model file a package needs (tokenizer, config, archive) — always downloaded. */
export interface AiInstallSupportingFile {
  filename: string;
  sha256: string;
  sizeBytes: number;
  downloadUrl: string;
  extract?: boolean;
}

/** One logical model package: its variants, supporting files, and licensing metadata. */
export interface AiInstallModelPackage {
  id: string;
  label?: string;
  description?: string;
  targetDir?: string;
  variants?: AiInstallModelVariant[];
  supportingFiles?: AiInstallSupportingFile[];
  minVramBytes?: number;
  /** Upstream model/licence page. Nullable in the registry. */
  termsUrl?: string | null;
  installRoot?: string | null;
  /** SPDX identifier for this package's artifacts (e.g. `Apache-2.0`). Nullable. */
  license?: string | null;
  /** `CapabilityTier` — serialized as the enum constant (e.g. `RETRIEVAL_CORE`), nullable. */
  tier?: string | null;
  requiresCuda?: boolean;
}

export interface AiInstallManifest {
  schemaVersion: number;
  purpose?: string;
  packages?: AiInstallModelPackage[];
}

// Tempdoc 840 Phase 4: the hand-written `AiInstallStatus` + `AiInstallAssetStatus` that lived here
// are gone. They modelled the pre-v2 flat `assets[]` progress shape, which the package-based v2
// install stopped emitting long ago — a stale second authority for a wire type that also had a
// third hand copy in `shell-v0/utils/aiInstallPoll.ts`. The one authority is now the Java DTO
// (`modules/app-api/.../AiInstallStatus.java`) projected through
// `SSOT/schemas/ai-install-status.v1.json` into the generated type + Zod schema below.
export type { AiInstallStatus } from '../generated/schema-types/ai-install-status';
import type { AiInstallStatus } from '../generated/schema-types/ai-install-status';

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
 * Exports diagnostics bundle. Rides the FE wire-drift ring summary along in the body so
 * production contract-drift observations land in the exported bundle
 * (frontend/fe-telemetry.json) instead of dying in the console.
 */
export async function exportDiagnostics(
  baseUrl: string,
  signal?: AbortSignal
): Promise<{ success: boolean; path: string }> {
  return request<{ success: boolean; path: string }>(baseUrl, '/api/diagnostics/export', {
    method: 'POST',
    body: { feTelemetry: { wireDrift: summarizeWireDrift() } },
    signal,
  });
}


