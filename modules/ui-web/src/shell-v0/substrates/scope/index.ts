// SPDX-License-Identifier: Apache-2.0
/**
 * Scope substrate — Tempdoc 543 §3.B.
 *
 * Scope is the canonical "where I am, who I am, what's active"
 * substrate. It extends ShellContext (which today holds the UI-state
 * subset for WhenExpression evaluation) with named-but-deferred slots
 * for domain dimensions (corpus / library / model / agent role /
 * enabled-plugin set) plus the viewer audience.
 *
 * Per §12.3 #4: deferred slots stay null/'' until per-domain state
 * modules ship. WhenExpression predicates that reference absent slots
 * evaluate false silently (verified by `whenExpression.ts:17-21`).
 *
 * Per §3.B, Scope is the single source of truth for:
 *   - WhenExpression evaluator input (via the flat-key projection in
 *     shellContextState.ts).
 *   - Workspace Profiles serialization (Slice 10). The persistent
 *     subset of Scope (audience, corpus, model, enabled plugins,
 *     eventually theme + layout) snapshots into a profile; restoring
 *     applies the subset to ShellContext + the per-feature state
 *     stores.
 *
 * This module exposes:
 *   - `ScopeSnapshot` — the serializable bundle that Workspace
 *     Profiles persist.
 *   - `serializeScope()` — capture current persistent state into a
 *     snapshot.
 *   - `restoreScope(snapshot)` — apply a snapshot to live state.
 *   - `getScope()` — typed Scope view of the current ShellContext.
 *
 * The non-persistent fields of ShellContext (selection, paletteOpen,
 * focusKind, inspectorOpen, etc.) are NOT in the snapshot — those
 * are ephemeral UI state, not profile data.
 *
 * KCS bridge per §19: under future three-layer KCS this becomes
 * `useScope()` + `useScopeSnapshot()` capability modules.
 */

import {
  getShellContext,
  updateShellContext,
  type ShellContext,
} from '../../state/shellContextState.js';

/**
 * The persistent subset of Scope. Workspace Profiles serialize this
 * structure into a profile blob; restoring re-applies it to the live
 * ShellContext + the per-feature state stores.
 *
 * Per §3.B: this is the "named slots" set. As per-domain state
 * modules ship (corpus, library, model, agent-role, enabled-plugins),
 * their snapshot/restore handlers register against this shape; the
 * snapshot grows in lockstep with the domain modules.
 *
 * Fields are all optional — a fresh "default" profile has none set,
 * and per-domain modules populate over time.
 */
export interface ScopeSnapshot {
  // Snapshot shape uses optional-only fields (no explicit `null`) to
  // keep the three-state space (undefined / null / value) collapsed to
  // two on the wire. `undefined` in the snapshot = "this slot was
  // unset at capture time"; restore clears the live state to its
  // empty form (null for IDs, '' for flat-key strings).
  /** Per §3.B Scope identity-axis. */
  readonly activeProfileId?: string;
  /** Active corpus selection. Aspirational until corpus state ships. */
  readonly activeCorpusId?: string;
  /** Active library selection. Aspirational. */
  readonly activeLibraryId?: string;
  /** Preferred inference model. Aspirational. */
  readonly preferredModelId?: string;
  /** Active agent role. Aspirational. */
  readonly activeAgentRole?: string;
  /** Comma-joined enabled plugin ids. Aspirational until enable-set state ships. */
  readonly enabledPluginIds?: string;
  /** Viewer audience tier. Sourced from UserStateDocument.viewerAudience. */
  readonly audience?: string;
}

/**
 * Capture the current Scope into a serializable snapshot.
 *
 * Per §3.B: this returns the PERSISTENT subset of Scope only.
 * Ephemeral fields (selection, paletteOpen, focusKind, inspectorOpen)
 * are intentionally omitted — they belong to the current session, not
 * the profile.
 */
export function serializeScope(): ScopeSnapshot {
  const ctx = getShellContext();
  // Build the snapshot directly — TS struct-typing handles assignment
  // into the optional-readonly shape during fresh object construction.
  // Fields are conditionally included to keep snapshots minimal.
  const snapshot: ScopeSnapshot = {
    ...(ctx.activeProfile ? { activeProfileId: ctx.activeProfile } : {}),
    ...(ctx.activeCorpusId !== null ? { activeCorpusId: ctx.activeCorpusId } : {}),
    ...(ctx.activeLibraryId !== null ? { activeLibraryId: ctx.activeLibraryId } : {}),
    ...(ctx.preferredModelId !== null ? { preferredModelId: ctx.preferredModelId } : {}),
    ...(ctx.activeAgentRole !== null ? { activeAgentRole: ctx.activeAgentRole } : {}),
    ...(ctx.enabledPluginIds ? { enabledPluginIds: ctx.enabledPluginIds } : {}),
    ...(ctx.audience ? { audience: ctx.audience } : {}),
  };
  return snapshot;
}

/**
 * Apply a ScopeSnapshot to the live ShellContext.
 *
 * Default semantics (`mode: 'replace'`): fields absent from the
 * snapshot are CLEARED to null/''. This is the Workspace-Profiles
 * "activate this profile" identity contract — the new profile's
 * persistent state fully replaces the previous one, not unions with
 * it. Switching from profile-A (corpus=X) to profile-B (no corpus)
 * leaves corpus null, not X.
 *
 * `activeProfileId` is the exception: a missing activeProfileId in
 * the snapshot is treated as "no change" rather than "clear" — the
 * caller (profile-switch handler) sets the profile id through its
 * own pathway, so restoreScope must not stomp it back to the default.
 *
 * Pass `mode: 'patch'` for additive semantics if a caller genuinely
 * wants merge behavior (e.g., partial domain-state update without
 * profile-switch context).
 */
// §25.γ1 — table-driven scope-field mapping. Each row encodes the
// snapshot field, the ShellContext field, and a per-mode default
// (replace mode applies the default when the snapshot field is
// absent; patch mode skips). Adding a new scope field is one row.
// activeProfileId has no replace-default (it's preserved when absent
// from a snapshot — used by tests that restore partial state).
interface ScopeFieldCoordinator {
  readonly snapshotKey: keyof ScopeSnapshot;
  readonly contextKey: keyof ShellContext;
  /** Default value applied in `replace` mode when snapshot field is
   *  undefined. Omit (or undefined) to mean "skip even in replace mode". */
  readonly defaultOnReplace?: ShellContext[keyof ShellContext];
}

const SCOPE_FIELDS: ReadonlyArray<ScopeFieldCoordinator> = [
  { snapshotKey: 'activeProfileId', contextKey: 'activeProfile' /* no default — preserve */ },
  { snapshotKey: 'activeCorpusId', contextKey: 'activeCorpusId', defaultOnReplace: null },
  { snapshotKey: 'activeLibraryId', contextKey: 'activeLibraryId', defaultOnReplace: null },
  { snapshotKey: 'preferredModelId', contextKey: 'preferredModelId', defaultOnReplace: null },
  { snapshotKey: 'activeAgentRole', contextKey: 'activeAgentRole', defaultOnReplace: null },
  { snapshotKey: 'enabledPluginIds', contextKey: 'enabledPluginIds', defaultOnReplace: '' },
  { snapshotKey: 'audience', contextKey: 'audience', defaultOnReplace: '' },
];

export function restoreScope(
  snapshot: ScopeSnapshot,
  mode: 'replace' | 'patch' = 'replace',
): void {
  // ShellContext's fields are readonly; accumulate the patch into a
  // plain mutable record then cast at the call boundary. updateShellContext
  // freezes the merged result internally, so the readonly contract holds
  // for consumers.
  type Mutable = {
    -readonly [K in keyof ShellContext]?: ShellContext[K];
  };
  const patch: Mutable = {};
  for (const f of SCOPE_FIELDS) {
    const snapVal = snapshot[f.snapshotKey];
    if (snapVal !== undefined) {
      // Cast: per-row narrowing means f.contextKey and snapVal align.
      (patch as Record<string, unknown>)[f.contextKey] = snapVal;
    } else if (mode === 'replace' && f.defaultOnReplace !== undefined) {
      (patch as Record<string, unknown>)[f.contextKey] = f.defaultOnReplace;
    }
    // patch mode + undefined snapVal: skip (no change).
  }
  updateShellContext(patch as Partial<ShellContext>);
}

/**
 * Convenience: get a typed Scope view of the current ShellContext.
 *
 * Today Scope is an extension of ShellContext (same module-state).
 * This accessor exists so future consumers can import `getScope()`
 * from `shell-v0/substrates/scope` without knowing about
 * shellContextState's location. If Scope ever moves to its own
 * module-state, only this accessor changes.
 */
export function getScope(): ShellContext {
  return getShellContext();
}
