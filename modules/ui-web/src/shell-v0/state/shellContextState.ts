// SPDX-License-Identifier: Apache-2.0
/**
 * shellContextState — Tempdoc 508 §11.1 / §13.1 — the unified
 * "where am I, what am I focused on, what's selected" snapshot that
 * feeds the WhenExpression evaluator.
 *
 * Ephemeral (not persisted in UserStateDocument). One snapshot at a
 * time; listener-based fan-out matches the existing state-store
 * idiom in the codebase.
 *
 * Flat-key shape — VS Code's when-clause grammar (slice 508 §13.1
 * verdict) accepts only flat identifiers, not dotted paths. We
 * compose the underlying domain models into flat keys at the
 * boundary so an expression like `selectionKind == search-hit &&
 * selectionCount > 0` evaluates straight against this snapshot.
 */
/** Where the user's keyboard focus lives. */
export type FocusKind = 'input' | 'result' | 'tab' | 'palette' | 'none';

/**
 * Selection kind discriminator (§13.2 trim — demand-driven kinds only).
 *
 * Tempdoc 526 §12.4 added `text-range` when the InspectorPane DOM selection
 * handler started publishing typed selections.
 */
export type SelectionKind =
  | 'search-hit'
  | 'browse-node'
  | 'plugin-item'
  | 'text-range'
  | 'citation'
  | 'result-set'
  | 'health-condition'
  | 'none';

/** Capabilities the current selection supports (§13.2 capability vocabulary). */
export type SelectionCapability =
  | 'open'
  | 'pin'
  | 'export'
  | 'ask-ai-about'
  | 'reveal-in-explorer'
  | 'copy-link';

export interface ShellContext {
  /** Active surface id, or null when no surface is mounted. */
  readonly activeSurface: string | null;
  /** Active profile id. Defaults to "default" until Profiles V2 lands. */
  readonly activeProfile: string;
  /** Where the user's focus is. */
  readonly focusKind: FocusKind;
  /** Selection summary (flat-key shape). */
  readonly selectionKind: SelectionKind;
  readonly selectionCount: number;
  /** Capabilities of the current selection — comma-joined for `in`-membership. */
  readonly selectionCapabilities: string;
  /**
   * Tempdoc 526 §12.4 — flat-key fields for the typed `text-range` variant.
   * `selectionEntityKind` mirrors the `text-range` host kind (`doc` / `snippet` / `message` /
   * `log-entry`). `selectionAddressCoords` is the {@link DocumentAddress} coordinate system
   * (`canonical` / `display`). `selectionTextLength` is the length of the picked text. All
   * three are null/0 for non-text-range selections.
   */
  readonly selectionEntityKind: string | null;
  readonly selectionAddressCoords: string | null;
  readonly selectionTextLength: number;
  /** Inspector pane open state. */
  readonly inspectorOpen: boolean;
  /** Currently-visible inspector tab id, if any. */
  readonly inspectorTab: string | null;
  /** Platform capabilities — comma-joined for `in`-membership. */
  readonly platformCapabilities: string;
  /** Palette open state (for keybindings that should only fire when palette is closed). */
  readonly paletteOpen: boolean;
  // ── Tempdoc 543 §3.B Scope — named-but-deferred slots ──
  //
  // Each slot is a contract: when the domain ships its state module
  // (corpus selection, library, model preference, agent-role, plugin
  // enable-set), it populates here through serializeScope/restoreScope.
  // Until then the slots stay null — per §12.3 #4, WhenExpression
  // predicates referencing absent slots evaluate false silently.
  //
  // Naming the slots in advance prevents the next 5 features from
  // inventing 5 parallel "current X" stores.
  /** Active corpus selection. Aspirational until corpus state ships. */
  readonly activeCorpusId: string | null;
  /** Active library selection. Aspirational until library state ships. */
  readonly activeLibraryId: string | null;
  /** Preferred inference model. Aspirational. */
  readonly preferredModelId: string | null;
  /** Active agent role. Aspirational. */
  readonly activeAgentRole: string | null;
  /** Comma-joined enabled plugin ids (for `in`-membership). Aspirational. */
  readonly enabledPluginIds: string;
  /** Viewer audience tier (USER | OPERATOR | DEVELOPER | AGENT). */
  readonly audience: string;
}

const DEFAULT_CONTEXT: ShellContext = {
  activeSurface: null,
  activeProfile: 'default',
  focusKind: 'none',
  selectionKind: 'none',
  selectionCount: 0,
  selectionCapabilities: '',
  selectionEntityKind: null,
  selectionAddressCoords: null,
  selectionTextLength: 0,
  inspectorOpen: false,
  inspectorTab: null,
  platformCapabilities: '',
  paletteOpen: false,
  // Tempdoc 543 §3.B — deferred slots default to null/'' (predicates
  // referencing them evaluate false silently per §12.3 #4).
  activeCorpusId: null,
  activeLibraryId: null,
  preferredModelId: null,
  activeAgentRole: null,
  enabledPluginIds: '',
  audience: '',
};

let current: ShellContext = DEFAULT_CONTEXT;
const listeners = new Set<(ctx: ShellContext) => void>();

export function getShellContext(): ShellContext {
  return current;
}

export function subscribeShellContext(listener: (ctx: ShellContext) => void): () => void {
  listeners.add(listener);
  try {
    listener(current);
  } catch {
    /* swallow */
  }
  return () => {
    listeners.delete(listener);
  };
}

/**
 * Patch the context. Producer can return a partial — unspecified
 * fields preserve their current value. Listeners fire only when the
 * resulting object differs by referential equality from the previous.
 */
export function updateShellContext(patch: Partial<ShellContext>): void {
  const next: ShellContext = { ...current, ...patch };
  // Cheap structural equality — if every field matches, skip the
  // notify pass. Mostly for the high-frequency cases (focusKind
  // changing on tab/click) where consumers don't want spurious
  // re-renders.
  if (
    next.activeSurface === current.activeSurface &&
    next.activeProfile === current.activeProfile &&
    next.focusKind === current.focusKind &&
    next.selectionKind === current.selectionKind &&
    next.selectionCount === current.selectionCount &&
    next.selectionCapabilities === current.selectionCapabilities &&
    next.selectionEntityKind === current.selectionEntityKind &&
    next.selectionAddressCoords === current.selectionAddressCoords &&
    next.selectionTextLength === current.selectionTextLength &&
    next.inspectorOpen === current.inspectorOpen &&
    next.inspectorTab === current.inspectorTab &&
    next.platformCapabilities === current.platformCapabilities &&
    next.paletteOpen === current.paletteOpen &&
    // Tempdoc 543 §3.B — deferred slots + audience must participate
    // in the change-detection equality check; otherwise Scope's
    // restoreScope() can silently swallow updates.
    next.activeCorpusId === current.activeCorpusId &&
    next.activeLibraryId === current.activeLibraryId &&
    next.preferredModelId === current.preferredModelId &&
    next.activeAgentRole === current.activeAgentRole &&
    next.enabledPluginIds === current.enabledPluginIds &&
    next.audience === current.audience
  ) {
    return;
  }
  current = next;
  for (const l of listeners) {
    try {
      l(current);
    } catch {
      /* swallow listener errors */
    }
  }
}

export function __resetShellContextForTest(): void {
  current = DEFAULT_CONTEXT;
  listeners.clear();
}
