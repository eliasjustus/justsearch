// SPDX-License-Identifier: Apache-2.0
/**
 * sv3-record — the canonical thread RECORD, projected into this window's turns (tempdoc 822 Phase
 * F6; inventory D1, tempdoc 561 P-A/P-A2).
 *
 * Derived from a third-party design system (MIT) — see THIRD-PARTY-NOTICES.md in this directory.
 *
 * The window is NOT the authority on what happened in a conversation. `GET /api/thread/{id}` is, and
 * the product already owns both halves of reading it: `views/unifiedThreadClient.ts`
 * (`fetchUnifiedThread` — schema, forward-tolerant per-event parsing, the EMPTY-on-failure contract)
 * and `views/unifiedThreadProjection.ts` (`projectUnifiedThread` — authoritative `occurredAt` order,
 * tool-lifecycle merge by `callId`, run segmentation). Both are consumed by the shipped window as
 * well as by this one, which is what makes them shared authorities rather than one window's
 * extraction; this module imports the pair and adds ONLY the last mile — grouping the ordered items
 * into the turns this window renders.
 *
 * It authors no fetch, no schema and no ordering of its own, and it is pure: same items in, same
 * turns out. The record's interleaving is preserved exactly (561 P-A: chat turns and agent activity
 * come from ONE record and must not be re-sorted into two lists), and it is expressed in the SAME
 * `Sv3RunFeedItem` shapes the live controller feed produces — so a settled run and a running one go
 * through one renderer in `Sv3Main`, not two.
 */
import { projectUnifiedThread, type ThreadEvent, type UnifiedTurnItem } from '../unifiedThreadProjection.js';
import type { ToolCall } from '../../controllers/AgentSessionController.js';
import {
  reasoningBlocksFromRecord,
  type ReasoningBlock,
} from '../../controllers/ReasoningController.js';
import type { Sv3RunFeedItem } from './sv3-run.js';
import type { Sv3Turn } from './sv3-sessions.js';

/**
 * The label a non-prose record item carries. Deliberately the same closed vocabulary
 * `sv3-run.ts`'s `RUN_NOTE_LABEL` gives the LIVE feed, so the same happening is not named two
 * different things depending on whether the reader watched it or came back to it.
 */
const RECORD_NOTE_LABEL: Partial<Record<string, string>> = {
  error: 'Error',
  progress: 'Progress',
  handoff: 'Handoff',
};

/**
 * A record tool row → the shared `ToolCall` the ONE tool-call card renders. The attribute mapping is
 * mined from the shipped window's own record path (`views/UnifiedChatView.ts:5322-5340`): the
 * projection has already merged a call's lifecycle events, so `attributes` carry identity
 * (toolName / arguments / risk, from `proposed`) alongside outcome (output / structuredData from
 * `completed`, reason from `rejected`). Risk persists lowercase and the card expects the live
 * uppercase `ToolRisk`.
 */
export function recordToolCall(item: UnifiedTurnItem): ToolCall {
  const a = item.attributes;
  return {
    callId: typeof a.callId === 'string' ? a.callId : item.id,
    toolName: typeof a.toolName === 'string' ? a.toolName : 'tool',
    arguments: typeof a.arguments === 'string' ? a.arguments : '',
    risk: (typeof a.risk === 'string' ? a.risk.toUpperCase() : 'LOW') as ToolCall['risk'],
    status: (typeof a.status === 'string' ? a.status : 'completed') as ToolCall['status'],
    output: typeof a.output === 'string' ? a.output : undefined,
    success: typeof a.success === 'boolean' ? a.success : undefined,
    rejectReason: typeof a.reason === 'string' ? a.reason : undefined,
    structuredData:
      a.structuredData !== null && typeof a.structuredData === 'object'
        ? (a.structuredData as Record<string, unknown>)
        : undefined,
    gateBehavior:
      typeof a.gateBehavior === 'string' ? (a.gateBehavior as ToolCall['gateBehavior']) : undefined,
  };
}

/** The turn under construction — the same fields as {@link Sv3Turn}, mutable while it accumulates. */
interface Building {
  id: string;
  question: string;
  askedAt: number;
  answers: string[];
  activity: Sv3RunFeedItem[];
  tools: number;
  errored: boolean;
  /** Tempdoc 848 §2.7 — the turn's persisted thinking, accumulated across all its assistant items. */
  reasoning: ReasoningBlock[];
}

const open = (id: string, question: string, askedAt: number): Building => ({
  id,
  question,
  askedAt,
  answers: [],
  activity: [],
  tools: 0,
  errored: false,
  reasoning: [],
});

/**
 * The record's ordered items → this window's turns.
 *
 * A `user` item OPENS a turn and everything after it belongs to that turn, which is the record's own
 * `user → … → (next user | end)` bracketing (the same segmentation `terminalAssistantIds` uses). Items
 * that arrive BEFORE any user item — a run started from somewhere that recorded no prompt — open a
 * turn with an empty question rather than being dropped: the window renders no ask bubble for it, but
 * a tool call the agent really made is not something to lose because its prompt is missing.
 *
 * The turn's KIND is derived, not declared: a turn that recorded tool calls or notes was an agent
 * run, and a turn that recorded only prose was an ask. Nothing in the record says which tier this
 * window dispatched it from, so deriving it from what happened is the only honest answer available.
 *
 * `evidence` is `null` throughout: the record does carry a persisted assistant message's sources, but
 * this window's evidence value is the SHARED resolver's output over the live stream's claims (Phase
 * F4), which the record has no counterpart for. {@link ../sv3-sessions.applySv3Record} therefore
 * keeps whatever evidence the turn already had rather than letting a refresh blank the panel. A turn
 * restored on a cold load has none — an honest "never told", not a claimed zero.
 */
export function projectSv3RecordTurns(events: readonly ThreadEvent[]): readonly Sv3Turn[] {
  // The shared projector runs HERE, in this window's one registered render site
  // (`governance/run-renderers.v1.json` runProjection) — never at the view. The run is ONE ordered
  // projection (565 §12.3.A), and a view that assembled it itself would be the second structure.
  const items = projectUnifiedThread(events);
  const built: Building[] = [];
  let current: Building | null = null;
  const ensure = (item: UnifiedTurnItem): Building => {
    if (current !== null) return current;
    const created = open(item.id, '', item.ts);
    current = created;
    built.push(created);
    return created;
  };

  for (const item of items) {
    if (item.kind === 'user') {
      current = open(item.id, item.content, item.ts);
      built.push(current);
      continue;
    }
    const turn = ensure(item);
    if (item.kind === 'assistant') {
      turn.answers.push(item.content);
      turn.activity.push({ kind: 'text', id: item.id, text: item.content });
      // Tempdoc 848 §2.7 — a turn can record several assistant items (an iterating shape, a
      // multi-step run), so blocks accumulate across them in record order rather than the last one
      // winning.
      turn.reasoning.push(...reasoningBlocksFromRecord(item.attributes.reasoning));
      continue;
    }
    if (item.kind === 'tool-activity') {
      const call = recordToolCall(item);
      turn.tools++;
      turn.activity.push({ kind: 'tool', id: call.callId, call });
      continue;
    }
    if (item.kind === 'error') turn.errored = true;
    turn.activity.push({
      kind: 'note',
      id: item.id,
      label: RECORD_NOTE_LABEL[item.kind] ?? 'Step',
      text: item.content,
    });
  }

  return built.map((turn) => {
    // A turn is "an agent run" exactly when the record shows it doing something other than talking.
    const agent = turn.activity.some((entry) => entry.kind !== 'text');
    return {
      id: turn.id,
      kind: agent ? 'agent' : 'ask',
      question: turn.question,
      answer: turn.answers.join('\n\n'),
      status: turn.errored ? 'failed' : 'complete',
      evidence: null,
      detail: '',
      toolCalls: turn.tools,
      // An ASK turn's whole response is its answer text, which the transcript already renders through
      // the shared markdown block — handing it a one-item activity list would mean two ways to draw
      // the same paragraph. Only a turn with real activity carries the interleaved sequence.
      activity: agent ? turn.activity : [],
      askedAt: turn.askedAt,
      // Tempdoc 848 §2.7 — the record now carries the turn's THINKING (persisted on the assistant
      // message by `ConversationEngine`, lifted onto the thread event by `InteractionThreadController`
      // / folded from the run journal by `AgentInteractionMapper`), so a cold-loaded turn shows the
      // blocks the run really produced. The other three (tempdoc 822 Phase F7) are still absent from
      // the record: no rewrite note and no receipt. Those stay seeded EMPTY rather than guessed, and
      // {@link ../sv3-sessions.applySv3Record} keeps whatever the live turn observed — so a
      // cold-loaded turn honestly shows no frame line instead of one built from invented numbers.
      standaloneQuestion: '',
      reasoning: turn.reasoning,
      durationMs: null,
      modelLabel: null,
    } satisfies Sv3Turn;
  });
}
