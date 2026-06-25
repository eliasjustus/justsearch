// SPDX-License-Identifier: Apache-2.0
/**
 * Plugin recovery-overlay client (slice 447 §X.3.3 + §X.11.5 follow-up Phase 6).
 *
 * Companion to `ResourceCatalogClient.mergePluginResourceContributions` (slice
 * 447-impl-C) and the V1.5 `mergePluginSurfaceContributions`. Plugins can declare
 * recovery overlays — overrides for the recommended Operation associated with a
 * given (conditionId, subject) pair — and the Health surface consults this overlay
 * before falling back to the backend-declared core recovery.
 *
 * Trust-tier governance per §X.3.3:
 *   - UNTRUSTED contributions targeting `core.*` Conditions are rejected (silently
 *     skipped + logged) so a malicious or misbehaving untrusted plugin can't
 *     hijack a CORE condition's recovery.
 *   - TRUSTED contributions targeting `core.*` Conditions are accepted.
 *   - Any contribution targeting a plugin-namespace Condition is accepted (the
 *     plugin is the truth-owner of its own Conditions).
 *
 * Audience-floor composition (slice 449 phase 12): a TRUSTED override of CORE
 * applies the trust-tier audience floor at consumption time; this module records
 * the override but does not gate it — gating happens at the consumer (HealthLitView,
 * agent retrospection) per the audience-floor mechanism's existing pattern.
 */

import type { Provenance } from '../types/registry.js';
// §4.1/§4.3 anti-drift (tempdoc 560): generated alias of the Java `TrustTier` authority.
import type { TrustTier as GenTrustTier } from '../generated/registry-enums.generated.js';

export type TrustTier = GenTrustTier;

export interface RecoveryOverlayEntry {
  /** Identity of the contributing plugin. Used for filter on remove + trust-tier check. */
  pluginId: string;
  /** Target HealthEvent id (e.g., `"schema.reindex-required"`). */
  conditionId: string;
  /** Subject within the condition (e.g., `"worker.schema"`). */
  subject: string;
  /** OperationRef the override resolves to. */
  operationRef: string;
  /**
   * 548 §4.3 — the uniform multi-axis Provenance minted once at the
   * PluginRegistry install site. The governance check reads `provenance.tier`;
   * the catalog no longer carries a separate `trustTier` field (the §1
   * fragmented-provenance defect). One authority, projected.
   */
  provenance: Provenance;
}

interface StoredOverlay extends RecoveryOverlayEntry {
  /** Whether the entry is "active" (not rejected by trust-tier governance). */
  active: boolean;
}

let overlays: Map<string, StoredOverlay> = new Map();
let listeners: Set<() => void> = new Set();

function key(conditionId: string, subject: string): string {
  return `${conditionId}|${subject}`;
}

/** Returns true when the contribution is rejected by trust-tier governance. */
function isRejected(entry: RecoveryOverlayEntry): boolean {
  // Discrimination rule: a plugin contribution is "in its own namespace" when the
  // targeted conditionId starts with `vendor.<pluginId>.` (e.g., a plugin id of
  // `vendor.acme` may freely override conditions whose id starts with `vendor.acme.`).
  // Any other target is treated as CORE / cross-plugin and gated by trust tier.
  const pluginNamespace = `vendor.${entry.pluginId.replace(/^vendor\./, '')}.`;
  const inOwnNamespace = entry.conditionId.startsWith(pluginNamespace);
  if (inOwnNamespace) return false;
  if (entry.provenance.tier === 'UNTRUSTED_PLUGIN') {
    // eslint-disable-next-line no-console
    console.warn(
      `[RecoveryOverlayClient] UNTRUSTED plugin '${entry.pluginId}' attempted to override CORE condition '${entry.conditionId}'; rejected per §X.3.3 trust-tier governance`,
    );
    return true;
  }
  return false;
}

/**
 * Merges a batch of plugin recovery overlay contributions. Each entry is keyed by
 * (conditionId, subject); collisions follow last-writer-wins semantics within the
 * accepted set. Trust-tier rejections are silently skipped (logged at warn level).
 *
 * Listeners fire after the merge completes (whether or not any entry was accepted).
 */
export function mergePluginRecoveryOverlays(
  entries: ReadonlyArray<RecoveryOverlayEntry>,
): void {
  const next = new Map(overlays);
  for (const entry of entries) {
    const stored: StoredOverlay = { ...entry, active: !isRejected(entry) };
    next.set(key(entry.conditionId, entry.subject), stored);
  }
  overlays = next;
  notify();
}

/** Removes all overlay entries contributed by a specific plugin (uninstall path). */
export function removePluginRecoveryOverlays(pluginId: string): void {
  const next = new Map(overlays);
  let changed = false;
  for (const [k, entry] of overlays) {
    if (entry.pluginId === pluginId) {
      next.delete(k);
      changed = true;
    }
  }
  if (!changed) return;
  overlays = next;
  notify();
}

/**
 * Returns the active overlay's OperationRef for the given condition, if any.
 * Returns `undefined` when no overlay applies (or the overlay was rejected by
 * trust-tier governance — consumers should fall back to the core
 * `core.condition-recovery-index` lookup).
 */
export function getOverlayRecovery(conditionId: string, subject: string): string | undefined {
  const stored = overlays.get(key(conditionId, subject));
  return stored && stored.active ? stored.operationRef : undefined;
}

/** Subscribe to overlay-set changes. Returns an unsubscribe function. */
export function onOverlayChange(listener: () => void): () => void {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}

function notify(): void {
  for (const l of listeners) {
    try {
      l();
    } catch {
      // swallow
    }
  }
}

/** Test-only: reset module state. */
export function __resetForTest(): void {
  overlays = new Map();
  listeners = new Set();
}
