// SPDX-License-Identifier: Apache-2.0
/**
 * compose — universal dispatch helper per tempdoc 526 §4.5 + §12.4.
 *
 * Replaces the slice 510 / slice 514 `askAi` helper and dissolves the
 * askAi-vs-direct-route fork (§6) by carrying a typed
 * {@link ComposeOperation} + an optional {@link SelectionPayload}, then
 * resolving the target shape through a single (operation, kind) → ShapeId
 * table — instead of letting {@code UnifiedChatView}'s {@code
 * affordanceToShape} pick.
 *
 * The helper dispatches a {@code navigate-with-context} event with the
 * resolved {@code forceShapeId}; UnifiedChatView prefers that shape over its
 * own affordance derivation. The pending selection is parked in a one-shot
 * register so UnifiedChatView's next dispatch forwards {@code body.selection}.
 *
 * compose() gates on AI capability and returns {@code false} when AI is
 * unavailable (mirrors the legacy askAi contract).
 */

import { getAiState } from '../state/aiStateStore.js';
import { type Affordance } from '../state/unifiedChatState.js';
import type { SelectionPayload } from '../../api/types/selection.js';

/**
 * Operations the FE compose() helper recognizes. Mirrors the Java
 * {@code ComposeIntent.operation} field; new operations require an entry in
 * {@link resolveShape}.
 */
/**
 * Operations the FE compose() helper recognizes. Tempdoc 526 §16 retraction:
 * narrowed from {summarize, ask, explain, compare} to {summarize, ask} because
 * the explain/compare shapes don't exist — those rows routed to {@code core.rag-ask}
 * under misleading labels. They land with their first concrete shape.
 */
export type ComposeOperation = 'core.summarize' | 'core.ask';

export interface ComposeArgs {
  readonly operation: ComposeOperation;
  /** Source/provenance tag (e.g., 'BUTTON', 'PALETTE'). */
  readonly source: string;
  /** Free-text user input (question, instruction). */
  readonly userPrompt?: string;
  /** Typed selection — when present, forwarded as body.selection on next dispatch. */
  readonly selection?: SelectionPayload;
  /** Doc context for migration-compatibility with the askAi shape (e.g., context-menu Summarize). */
  readonly docIds?: ReadonlyArray<string>;
  /** Display name for templated query (e.g., "Summarize X.pdf"). */
  readonly docName?: string;
  /**
   * Optional affordance hint. Defaults from operation + docIds. The resolved
   * shape (see {@link resolveShape}) is always preferred; affordance is
   * carried only so UnifiedChatView's UI bits that still read it (chip,
   * input placeholder) keep behaving as before.
   */
  readonly affordance?: Affordance;
  /** Optional explicit shape override; bypasses {@link resolveShape}. */
  readonly targetShape?: string;
}

/**
 * Pending-selection register — UnifiedChatView's next /api/chat/dispatch
 * picks this up and forwards it as body.selection. One-shot.
 */
let pendingSelection: SelectionPayload | null = null;

/**
 * Forced-shape register — compose() resolves a shapeId from the (operation,
 * kind) table and parks it here. UnifiedChatView's next dispatch prefers it
 * over the legacy {@code affordanceToShape} derivation, dissolving the
 * askAi-vs-direct-route fork named in tempdoc 526 §6. One-shot.
 */
let pendingForceShapeId: string | null = null;

export function setPendingSelection(s: SelectionPayload | null): void {
  pendingSelection = s;
}

export function takePendingSelection(): SelectionPayload | null {
  const s = pendingSelection;
  pendingSelection = null;
  return s;
}

export function setPendingForceShape(shapeId: string | null): void {
  pendingForceShapeId = shapeId;
}

export function takePendingForceShape(): string | null {
  const s = pendingForceShapeId;
  pendingForceShapeId = null;
  return s;
}

/**
 * Auto-run register — 548 §4.5. Set by the IntentRouter when it lowers an
 * `answer` verb to a chat-surface activation. UnifiedChatView drains it on
 * connect and, once the AI is chat-capable, fires `send()` once with the
 * prefilled prompt — giving the `answer` verb the same "navigate-and-run"
 * symmetry the `query` verb already has against the search surface
 * (SearchSurface's restoreSearch executes the fetch). One-shot.
 *
 * compose()/askAi paths deliberately leave this unset: those prefill the
 * composer and wait for the user to press Send. Only the agent-emitted
 * `answer` verb declares "run it now."
 */
let pendingAutoRun = false;

export function setPendingAutoRun(v: boolean): void {
  pendingAutoRun = v;
}

export function takePendingAutoRun(): boolean {
  const v = pendingAutoRun;
  pendingAutoRun = false;
  return v;
}

/**
 * Data-driven (operation, selection.kind, affordance?) → ShapeId table per
 * tempdoc 526 §4.5 property 1. Source of truth for shape routing on
 * compose() paths.
 *
 * Adding a new row requires no callsite change. The optional {@code
 * affordance} hint disambiguates `core.ask + none` between RAG-ask and
 * FreeChat (which one the user wants without a typed selection).
 */
export function resolveShape(
  operation: ComposeOperation,
  selectionKind: SelectionPayload['kind'] | 'none',
  // Tempdoc 561 P-B3: accepts the full Affordance. 'agent' is the action-plane MODE (not an
  // answer-plane shape), so it falls through to 'core.free-chat' here and is never used to resolve
  // a dispatch shape — the agent path hosts <jf-agent-view> instead of dispatching a shape.
  affordance?: Affordance,
): string {
  switch (operation) {
    case 'core.summarize':
      return 'core.summarize';
    case 'core.ask':
      switch (selectionKind) {
        case 'none':
          // Tempdoc 526 §14.5 T5 — affordance hint disambiguates the
          // ask-without-selection case: 'documents' → RAG, 'extract' →
          // Extract shape, 'none' → FreeChat continuation.
          if (affordance === 'documents') return 'core.rag-ask';
          if (affordance === 'extract') return 'core.extract';
          return 'core.free-chat';
        case 'text-range':
        case 'item':
        case 'citation':
        case 'result-set':
        case 'health-condition':
        default:
          return 'core.rag-ask';
      }
  }
}

/** Mirror the legacy askAi return contract: false when AI is offline. */
export function compose(args: ComposeArgs): boolean {
  const ai = getAiState();
  if (!ai.capabilities.chat) {
    return false;
  }
  const docIds = args.docIds ? Array.from(args.docIds) : [];
  const affordance: Affordance =
    args.affordance ?? (docIds.length > 0 ? 'documents' : 'none');
  const query = resolveQuery(args);
  if (args.selection) {
    setPendingSelection(args.selection);
  }
  const kind: SelectionPayload['kind'] | 'none' = args.selection?.kind ?? 'none';
  const forceShapeId =
    args.targetShape ?? resolveShape(args.operation, kind, args.affordance);
  // Park forceShapeId in the one-shot register; UnifiedChatView.send() drains
  // it on the next /api/chat/dispatch, dissolving the askAi-vs-direct-route
  // fork (§6) by routing through the resolver instead of affordanceToShape.
  setPendingForceShape(forceShapeId);
  const detail = {
    target: 'core.unified-chat-surface',
    state: { query, affordance, docIds, forceShapeId },
  };
  const event = new CustomEvent('navigate-with-context', {
    bubbles: true,
    composed: true,
    detail,
  });
  const shell = document.querySelector('jf-shell');
  (shell ?? document).dispatchEvent(event);
  return true;
}

function resolveQuery(args: ComposeArgs): string {
  if (args.userPrompt && args.userPrompt.trim().length > 0) return args.userPrompt;
  if (args.operation === 'core.summarize') {
    if (args.docName) return `Summarize ${args.docName}`;
    if (args.selection?.kind === 'text-range') return 'Summarize the selection';
    if (args.selection?.kind === 'result-set') return 'Summarize these documents';
    if (args.selection?.kind === 'citation') return 'Summarize this citation';
    return 'Summarize this document';
  }
  return '';
}
