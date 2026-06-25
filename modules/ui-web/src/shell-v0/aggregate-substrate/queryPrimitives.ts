// SPDX-License-Identifier: Apache-2.0
/**
 * Tempdoc 511 — Audience-gate query primitives.
 *
 * Reduced in 511-followup (2026-05-18) from 7 primitives to 2: only
 * the two audience-gate functions have production consumers (the
 * (Operation, button) and (Resource, list-item) canonical
 * strategies). Removed primitives — operationCallableBy,
 * operationAffectsResource, operationSupersedes, operationGroupKey,
 * resourceGroupKey — were forward-declared infrastructure with zero
 * production callsites; per C-018 discipline, they're shipped when
 * a consumer claims them, not before. Add back per-function as
 * palette / lineage / grouping consumers materialize.
 *
 * Audience axis ordering (DEVELOPER ⊆ OPERATOR ⊃ USER; AGENT is
 * pass-through) mirrors `PluginRegistry.audienceFloorForTier` —
 * stay consistent with that semantics.
 */

import type {
  Audience,
  Operation,
  Resource,
} from '../../api/types/registry.js';

/**
 * Audience filter for surface visibility. An Operation with
 * `audience: OPERATOR` is hidden from a USER viewer; DEVELOPER
 * viewers see everything.
 */
export function operationVisibleTo(
  op: Operation,
  viewerAudience: Audience,
): boolean {
  if (op.audience === 'USER') return true;
  if (op.audience === 'AGENT') return viewerAudience === 'AGENT';
  if (op.audience === 'OPERATOR') {
    return viewerAudience === 'OPERATOR' || viewerAudience === 'DEVELOPER';
  }
  if (op.audience === 'DEVELOPER') return viewerAudience === 'DEVELOPER';
  return false;
}

export function resourceVisibleTo(
  res: Resource,
  viewerAudience: Audience,
): boolean {
  if (res.audience === 'USER') return true;
  if (res.audience === 'AGENT') return viewerAudience === 'AGENT';
  if (res.audience === 'OPERATOR') {
    return viewerAudience === 'OPERATOR' || viewerAudience === 'DEVELOPER';
  }
  if (res.audience === 'DEVELOPER') return viewerAudience === 'DEVELOPER';
  return false;
}
