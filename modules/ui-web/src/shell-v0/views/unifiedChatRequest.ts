// SPDX-License-Identifier: Apache-2.0
/**
 * Tempdoc 621 Phase 2 — the chat window's request/message prologue, extracted from UnifiedChatView.ts so
 * the host file reads as orchestration, not declarations. Pure (no element/runtime dependencies):
 *  - {@link ThreadMessage} — the FE-maintained live thread message (Phase 4 will also project it into the
 *    unified turn model).
 *  - {@link buildRequestBody} — the per-shape POST body the composer dispatches.
 *  - {@link CONVERSATION_ZONES} — the declared multi-zone composition the host feeds `composeGridStyles`.
 */
import { type ZoneDecl } from '../primitives/compositionLayout.js';
import type { SelectionPayload } from '../../api/types/selection.js';
import type { CitationMatch, RetrievalCitation } from '../components/chat/CitationsPanel.js';
import type { Claim } from '../components/chat/citationTypes.js';
import type { RagMetaPayload } from '../../api/streams.js';
import type { CoreInteractionShapeId } from '../plugin-api/coreInteractionShapes.js';

// The interaction "modes" of the one window — the FE mirror of the Java authority
// (CoreConversationShapeCatalog.CORE_USER_INTERACTION_SHAPES), kept in sync by the
// interaction-surface discipline gate.
export type ShapeId = CoreInteractionShapeId;

// Tempdoc 577 Ext III — the raise-budget remedy's grant size (tokens). One step per click keeps the
// remedy deliberate; the figure is visible on the button so the action is self-describing.
export const RAISE_BUDGET_STEP_TOKENS = 4096;

// Tempdoc 610 Phase A — reserved branch-point marking an empty-prefix branch (inherit
// nothing). Used to edit/retry the FIRST turn, where there is no preceding message.
// MUST match ConversationStore.EMPTY_PREFIX_SENTINEL (Java) — same cross-language
// coupling as THROWAWAY_SESSION_PREFIX.
export const EMPTY_PREFIX_SENTINEL = '__empty_prefix__';

export interface ThreadMessage {
  role: 'user' | 'assistant';
  content: string;
  shapeId: ShapeId;
  citations?: CitationMatch[];
  sources?: RetrievalCitation[];
  claims?: Claim[];
  ragMeta?: RagMetaPayload;
  isExtract?: boolean;
  // Tempdoc 603 C2 — the decontextualized standalone question this follow-up's retrieval ran on
  // (from the `rag.rewrite` SSE event). Persisted per-turn so the "Interpreted as: …" transparency
  // line survives turn completion + restored history, not just the live stream.
  standaloneQuestion?: string;
  // Slice 513 — stable id from backend (real UUID for post-513 writes,
  // synthetic idx-N for legacy). Required for "Branch here" affordance.
  id?: string;
  // Slice 513 — true for messages inherited from a parent conversation
  // when this session is itself a branch. Render with a subtle indicator
  // and suppress the "Branch here" button (you can only branch from your
  // own session's messages, not a re-resolved prefix).
  inheritedFromParent?: boolean;
}

export const SHAPE_LABELS: Record<ShapeId, string> = {
  'core.free-chat': 'Chat',
  'core.rag-ask': 'Document Q&A',
  'core.extract': 'Extraction',
  'core.agent-run': 'Tools',
  // Tempdoc 565 §15.C — the workflow run is a mode of the one window.
  'core.workflow-run': 'Workflow',
};

export function buildRequestBody(
  shapeId: ShapeId,
  text: string,
  sessionId: string,
  schemaDraft: string,
  docIds: string[],
  selection?: SelectionPayload | null,
): Record<string, unknown> {
  const body: Record<string, unknown> = { shapeId };
  switch (shapeId) {
    case 'core.rag-ask':
      body.question = text;
      // Slice 515 FIX-1: forward docIds from unifiedChatState so scoped
      // retrieval (askAi from BrowseSurface/SearchSurface) actually filters
      // to the referenced docs instead of silently degrading to open-retrieval.
      // Empty array preserves the open-retrieval fallback in RAGAskShape.
      body.docIds = docIds;
      break;
    case 'core.extract':
      body.prompt = text;
      body.schema = schemaDraft;
      break;
    case 'core.agent-run':
    case 'core.free-chat':
    default:
      body.prompt = text;
      body.sessionId = sessionId;
      break;
  }
  // Tempdoc 526 §12.4 — when compose() handed us a typed selection, forward
  // it as body.selection so the backend SelectionContextInjector receives it
  // verbatim. Applies to every shape today; shapes that don't read body.selection
  // simply ignore the field. SummarizeShape opts in via selectionPolicy=OPTIONAL.
  if (selection) {
    body.selection = selection;
  }
  return body;
}

/**
 * Tempdoc 565 §13 + §13.8/§13.9 — the agent window's DECLARED multi-zone composition. The grid frame is
 * GENERATED from this by {@link composeGridStyles} (no hand-authored `grid-template-columns`).
 *
 * The reading column (`.conversation`, capped at the ~88ch reading measure) is FLANKED by the spine (a
 * narrow left gutter) and the evidence rail (right). §13.9 corrects two space defects a live audit found:
 * (a) the spine + rail tracks are now CONTENT-SIZED (`auto` / `fit-content`) so an UNMOUNTED zone truly
 * COLLAPSES to 0 — a `minmax(0, max)` track does NOT collapse (it claims free space up to its max; the
 * empty rail's old `minmax(15rem,20rem)` reserved ~320px in Documents mode and shoved the column ~280px
 * left). (b) the outer margins are CAPPED (`minmax(0, 8rem)`) rather than unbounded `1fr`, so the group
 * sits fuller-width with bounded gutter instead of stranded in centre-margin on wide screens. The reading
 * measure is zone CONTENTS (on the answer block), not the frame — outside the generator (the 559 §8 cut).
 */
export const CONVERSATION_ZONES: readonly ZoneDecl[] = [
  { track: 'minmax(0, 8rem)', wideOnly: true },
  { selector: '.run-spine', track: 'auto', col: 2, wideOnly: true },
  { selector: '.conversation', track: 'minmax(0, 50rem)', col: 3 },
  { selector: '.evidence-rail', track: 'fit-content(20rem)', col: 4, wideOnly: true },
  { track: 'minmax(0, 8rem)', wideOnly: true },
];
