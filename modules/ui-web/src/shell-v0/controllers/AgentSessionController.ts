// SPDX-License-Identifier: Apache-2.0
/**
 * Slice 495 — AgentSessionController.
 *
 * Plain TypeScript class that encapsulates the agent session lifecycle:
 * state machine (14 SSE mutation paths), HTTP dispatch, availability polling.
 * Implements CoreAgentRunHandlers and uses consumeShapeStream internally,
 * aligning with the typed-stream pattern established by AskView/NavigateView/
 * SummarizeView in slice 491 Phase E.
 *
 * Framework-agnostic: no Lit dependency. The consuming view (AgentView) passes
 * an onUpdate callback that triggers re-render.
 */

import { consumeShapeStream } from '../../api/streams.js';
import { LivenessWatchdog } from '../streaming/LivenessWatchdog.js';
import { STREAM_WATCHDOG_STALE_MS } from '../../api/generated/stream-liveness-constants.js';
import { friendlyStreamError } from '../utils/streamError.js';
import { emitEphemeralToast } from '../components/advisory/ephemeralToast.js';
import type {
  CoreAgentRunHandlers,
  CoreAgentRunDonePayload,
  CoreAgentRunSessionStartedPayload,
  CoreAgentRunChunkPayload,
  CoreAgentRunToolBatchProposedPayload,
  CoreAgentRunToolCallApprovedPayload,
  CoreAgentRunToolExecStartedPayload,
  CoreAgentRunToolCallRejectedPayload,
  CoreAgentRunStateSnapshotPayload,
} from '../../api/generated/shape-handlers/core-agent-run.js';
import type { PluginHostApi } from '../plugin-api/plugin-types.js';
import { streamViaHost } from '../plugin-api/pumpHostAiStream.js';
import { ReasoningController } from './ReasoningController.js';
// Tempdoc 577 §2.14 Root I (#1d) — the ONE cross-tab pointer to the live agent run, so a SECOND tab
// discovers and reattaches to a run started in another tab (per-tab sessionStorage cannot cross tabs).
import { setActiveRun, clearActiveRun, readActiveRun } from './activeRunPointer.js';
// Tempdoc 561 P-B2 — Timeline is a DISTINCT projection of the one action ledger (the workspace
// activity stream), via the same ActionLedgerClient authority ActionLedgerView uses — not a copy
// of the Sessions roster. Single-authority: no re-derivation of the activity projection.
import {
  ActionLedgerClient,
  projectBackend,
  type BackendLedgerEntry,
  type UnifiedActionEntry,
} from '../operations/ActionLedgerClient.js';
// Tempdoc 561 P-D (autonomy-policy collapse): the FE no longer DECIDES auto-approval — it sends the
// dial level to the backend (on the run request + the /autonomy endpoint when the dial moves) and the
// backend issuance policy decides. We only read the current level + subscribe to changes.
import { getAutonomyLevel, subscribeAutonomy } from '../substrates/autonomy/index.js';
// Tempdoc 550 C3 — route a tool call's human-approval moment through the unified ceremony
// host (the same surface used for gated effect/emission dispatches).
import { requestAuthorization, cancelAuthorizationsForRun } from '../operations/authorizationBroker.js';
// 543-fwd idea #0 — agent→journal bridge: a successful server-side tool-call is
// the real agent action; mirror it into the Effect Journal so the audit log /
// "what the AI did" digest / undo-AI reflect REAL agent activity (not just the
// demo + producer-less vop_ path). recordEffect journals WITHOUT dispatching —
// the op already ran server-side, so we must not re-invoke it via applyEffect.
import { recordEffect, markUndoableOperation } from '../substrates/effects/index.js';
import { CORE_PROVENANCE } from '../primitives/provenance.js';
import type { Effect } from '../substrates/effect.js';

export type ToolRisk = 'LOW' | 'MEDIUM' | 'HIGH';
export type ToolCallStatus = 'proposed' | 'pending' | 'approved' | 'executing' | 'completed' | 'rejected';

/**
 * Tempdoc 561 P-D1: the backend trust-lattice's authoritative verdict for this dispatch, carried
 * on the wire (lowercased `GateBehavior`). When present, the FE renders THIS decision instead of
 * re-deriving its own from `risk` (the second-authority 550 Thesis V flags).
 */
export type BackendGateBehavior = 'auto' | 'inline_confirm' | 'typed_confirm' | 'deny';

export interface ToolCall {
  callId: string;
  toolName: string;
  arguments: string;
  risk: ToolRisk;
  status: ToolCallStatus;
  output?: string;
  success?: boolean;
  rejectReason?: string;
  executionId?: string;
  /**
   * Tempdoc 560 Phase 1 — structured result payload (e.g. an MCP tool's `mcpContent` array of
   * text/image/resource blocks). Carried so the card can render non-text content, not only `output`.
   */
  structuredData?: Record<string, unknown>;
  /** Tempdoc 561 P-D1: the backend gate verdict, when the loop supplied it. */
  gateBehavior?: BackendGateBehavior;
}

// Entry IDs are per-controller instance (see nextEntryId method).

export interface ConversationEntry {
  id: string;
  type:
    | 'user'
    | 'assistant-text'
    | 'tool-call-group'
    | 'error'
    | 'progress'
    | 'handoff'
    | 'run-node'
    // Tempdoc 565 §30 — a human STEERING directive acknowledged mid-run (the DIRECTION authority).
    | 'steer-directive';
  content: string;
  callIds?: string[];
  errorCode?: string;
  fromAgentId?: string;
  toAgentId?: string;
  /** Tempdoc 585 §D Phase 2 (D2): the handoff reason, kept discrete for <jf-handoff-card>. */
  reason?: string;
  /** Tempdoc 561 #5: progress severity ('info'|'warn'|'error'); absent ⇒ routine (info). */
  severity?: string;
  /** Tempdoc 577 Ext II: the typed progress phase token ('init'|'llm_call'); the user-tier label
   * projects from this closed vocabulary, the prose message is the unknown-phase fallback only. */
  phase?: string;
  // Tempdoc 565 §26.B — a `run-node` entry marks a workflow node boundary (pushed on
  // node_started/node_completed); the live projector emits it as a boundary `progress` item the
  // assignRunSegments pass consumes to bracket the node's steps into a run segment.
  nodeBoundary?: 'start' | 'end';
  nodeId?: string;
  nodeKind?: string;
  nodeLabel?: string;
  timestamp: number;
}

// Tempdoc 565 §3.A / §13.8 — AgentSource + AgentSentenceCite are now the single-authority shared
// shape-leaf types (the generated `done` event references them by name); imported for local use and
// re-exported so existing importers (SourcesPane, UnifiedChatView) keep their
// `from '../controllers/AgentSessionController'` path.
import type {
  AgentSource,
  AgentSentenceCite,
} from '../../api/generated/shape-handlers/shared.js';
export type { AgentSource, AgentSentenceCite };

/**
 * Tempdoc 577 §2.14 Root III — one agent tool as the authority-space projection reads it (the rich
 * shape `/api/chat/agent/tools` already emits via `AgentController.operationToToolMap`). `risk` is
 * the lowercased {@link RiskTier}; `tier` is the provenance trust band (CORE / TRUSTED_PLUGIN /
 * UNTRUSTED_PLUGIN); `kind` distinguishes core op / MCP tool / projected workflow.
 */
export interface AgentToolInfo {
  readonly name: string;
  readonly description?: string;
  readonly risk?: 'low' | 'medium' | 'high';
  readonly supportsUndo?: boolean;
  readonly tier?: string;
  readonly provenance?: string;
  readonly kind?: string;
}

export interface SessionListItem {
  sessionId: string;
  startedAtEpochMs?: number;
  status?: string;
  initialMessage?: string;
  iterationsUsed?: number;
  /** Tempdoc 561 #4: the backend-derived first-user-message preview — the human session label. */
  preview?: string;
  /** Tempdoc 577 Move 1 — the record's declared resume capability (the wire has always carried it;
   * the FE dropped it, which is how Resume rendered on evicted sessions and 500'd). */
  resumable?: boolean | null;
}

/**
 * Tempdoc 561 #4: a human label for an agent session — the first-user-message preview the backend
 * derives ({@code AgentRunStore.derivePreview}), never the raw session UUID. Falls back to a neutral
 * label when a run has no preview yet (e.g. before the first user turn persists).
 */
export function sessionLabel(s: SessionListItem): string {
  const preview = (s.preview ?? s.initialMessage)?.trim();
  return preview && preview.length > 0 ? preview : 'Untitled session';
}

/**
 * Tempdoc 565 §26.D — one background run as the retrospective INBOX sees it (the `/api/presence`
 * projection of `AgentRunStore` filtered to background runs). The activity half folded out of the
 * standalone Memory surface into the one window's retrospective drawer.
 */
export interface PresenceRun {
  readonly sessionId: string;
  readonly state: string;
  readonly actor: string;
  readonly toolCalls: number;
  readonly turns: number;
  readonly iterations: number;
}

/**
 * Tempdoc 577 §2.12 Move 2 — the run is PARKED at the budget boundary as a held decision. Present
 * while the gate is held; cleared by the next budget/progress/terminal event (the loop only moves
 * again once the gate resolved).
 */
export interface BudgetGateState {
  tokensNeeded: number;
  tokensRemaining: number;
  totalTokensConsumed: number;
}

/**
 * Tempdoc 577 §2.14 Root II (#14) — the run parked at the context-pressure boundary (the cognitive
 * sibling of {@link BudgetGateState}). The drawer renders the decision: continue anyway / compact
 * older turns / stop.
 */
export interface ContextGateState {
  promptTokens: number;
  contextWindow: number;
}

export interface BudgetUpdate {
  phase: string;
  tokensConsumed: number;
  tokensRemaining: number;
  /** Tempdoc 577 Ext III: run-cumulative consumption (absent on legacy/replayed records). */
  totalTokensConsumed?: number;
  /** Tempdoc 577 §2.14 Root II (#14): current context occupancy (latest prompt size). */
  promptTokens?: number;
  /** Tempdoc 577 §2.14 Root II (#14): the model's context window (n_ctx); 0/absent ⇒ no horizon. */
  contextWindow?: number;
}

export type AgentTab = 'chat' | 'sessions' | 'timeline' | 'history';

const AVAILABILITY_POLL_MS = 10_000;
const DEFAULT_MAX_ITERATIONS = 10;
const SESSIONS_LIMIT = 50;
const HISTORY_LIMIT = 100;

export class AgentSessionController implements CoreAgentRunHandlers {
  // --- Observable state ---
  conversation: ConversationEntry[] = [];
  toolCalls: Record<string, ToolCall> = {};
  streamingText = '';
  isStreaming = false;
  // Tempdoc 565 §33 — WHICH kind of run is live (null = idle). Only an `agent` run is steerable (§30
  // interject) — a `workflow` run goes through WorkflowShapeRunner (no drain) and a `background` run is
  // detached, so the steer input gates on `runKind === 'agent'` (an affordance shown only when it works).
  runKind: 'agent' | 'workflow' | 'background' | null = null;
  sessionId: string | null = null;
  // Tempdoc 561 P-A/P-B (Slice 2): the chat conversationId this run belongs to, supplied by the host
  // UnifiedChatView so the run's thread events land under the SAME interaction record as the chat
  // turns (one unified thread). Null = the run is its own thread (backend falls back to the run id).
  conversationId: string | null = null;
  iterationsUsed = 0;
  toolCallsExecuted = 0;
  // Tempdoc 565 §3.A — the grounding behind the latest answer: the clickable local-passage sources
  // and (when the matcher ran) the per-sentence inline citations. Read by the sources pane.
  answerSources: AgentSource[] = [];
  answerCitations: AgentSentenceCite[] = [];
  // Tempdoc 550 N1: the tool calls the agent proposed for the CURRENT turn, announced as one
  // batch (tool_batch_proposed) before any per-call gate — lets a surface preview the whole
  // turn's plan ("the agent wants to do X, Y, Z") ahead of the first approval. Per-call approval
  // still flows through the ceremony unchanged; this is a heads-up view.
  currentToolBatch: ReadonlyArray<{ callId: string; toolName: string }> = [];
  totalTokensUsed: number | null = null;
  budgetUpdates: BudgetUpdate[] = [];
  /** Tempdoc 577 Move 2 — non-null while the run is parked at the budget gate. */
  budgetGate: BudgetGateState | null = null;
  /** Tempdoc 577 §2.14 Root II — non-null while the run is parked at the context-pressure gate. */
  contextGate: ContextGateState | null = null;
  activeAgentId: string | null = null;
  /** Slice 491 E17 probe 4: navigation receipts from URLExtractor events. */
  navigationReceipts: Array<{
    outcome: 'extracted' | 'forwarded' | 'dispatched' | 'rejected' | 'unresolved';
    target: string;
    addressKind: string;
    reasonCode?: string;
    message?: string;
    suggestions?: Array<{ id: string; label: string }>;
  }> = [];
  sessions: SessionListItem[] = [];
  history: UnifiedActionEntry[] = [];
  // Tempdoc 565 §26.D — the cross-conversation background-run inbox (the retrospective drawer's
  // Inbox tab projects this; the run-in-background launcher folded here from the Memory surface).
  presence: PresenceRun[] = [];
  // Tempdoc 561 P-B2 — the workspace activity timeline: a chronological projection of the one
  // action ledger (all kinds, all originators), DISTINCT from the Sessions run-roster.
  timeline: UnifiedActionEntry[] = [];
  available: boolean | null = null;
  // Tempdoc 577 §2.13 #17 / §2.14 Root III — the agent's AUTHORITY SPACE. `/api/chat/agent/tools`
  // has always emitted the rich per-tool shape (risk / supportsUndo / tier / provenance / kind /
  // description); the FE dropped all but `name`. Reading it makes the agent's verb-space projectable
  // (the Goal-1 Move C analogue), so users calibrate trust by INSPECTION, not only by tripping a gate.
  tools: AgentToolInfo[] = [];
  reasoning!: ReasoningController;

  // Tempdoc 585 §D Phase 1 (C1) — run-replay state. In replay mode the controller is fed a FINISHED
  // run's PERSISTED events (GET /api/chat/sessions/{id}/events) instead of a live SSE stream, so the
  // existing per-event handlers + projection render it like a live run. The four live-only
  // side-effects (auto-approve/ceremony, the Effect Journal, virtual-tool dispatch, the cross-tab
  // active-run pointer) are SUPPRESSED while replayMode is true — replaying a dead run must never
  // re-drive real backend actions or claim another tab's live pointer.
  replayMode = false;
  replayCursor = 0; // number of persisted events applied so far
  private replayEvents: Array<{ eventType: string; payload: unknown }> = [];
  get replayTotal(): number {
    return this.replayEvents.length;
  }

  /**
   * Tempdoc 577 Ext III (live-validation fix) — a run is LIVE while its stream is in flight. The
   * per-run remedies (raise-budget / halt) only make sense against a live run: the backend evicts
   * the session at completion, so offering them on a DONE run silently 404s (observed live).
   */
  get runInFlight(): boolean {
    return this.abortController !== null;
  }

  // --- Private ---
  private entryIdCounter = 0;
  private abortController: AbortController | null = null;
  private availabilityPoll: number | null = null;
  private pendingNotify = false;
  private errorHandledDuringStream = false;
  // Tempdoc 577 §2.14 Root I (#13/#1d) — one-shot guard: a mid-run stream drop reattaches to the
  // backend run exactly once, so a persistently-failing attach endpoint cannot loop. Reset per send.
  private reattachedThisRun = false;
  // True once this stream saw session_started — so a drop is a genuine mid-run drop (a live run to
  // reattach), not an initial POST failure over a stale sessionId. Reset per send.
  private runStartedThisStream = false;
  // Tempdoc 604 — the agent SSE stream has no liveness signal of its own, so a transport-hung stream
  // (e.g. a worker bounce mid-run) would block the FE silently for the backend's 30-min attach latch.
  // The watchdog resets on every received event (incl. the backend `heartbeat`); if it expires, the
  // stream is presumed hung and we abort it to drive the run to a terminal (so it concludes rather
  // than hanging). A parked-but-alive run keeps heartbeating, so its watchdog never trips.
  private readonly liveWatchdog = new LivenessWatchdog(STREAM_WATCHDOG_STALE_MS, () =>
    this.onLiveStreamStale(),
  );
  // Distinguishes a watchdog-fired abort (a silent transport hang → recover) from a genuine
  // user-initiated cancel (just end). Set right before the watchdog aborts; consumed in the catch.
  private liveWatchdogFired = false;
  private pendingAutoApprovals: string[] = [];
  // 543-fwd #6 — causation: the previous agent tool-call's journal entry id in
  // the CURRENT turn. The bridge chains each tool-call to it so the journal
  // records a per-turn causation chain (real-agent causation was empty before —
  // it was only set on accepted proposal sequences). Reset on each new session.
  private lastAgentCausationId: number | null = null;
  private readonly apiBase: string;
  private readonly onUpdate: () => void;
  /**
   * Tempdoc 508-followup §ε3 — optional host API. When set, read-only
   * session lookups (transcript / metadata) route through host.ai
   * instead of raw fetch. Streaming + approve/reject paths stay on
   * direct fetch because /api/chat/agent isn't exposed on host.ai
   * with the audience header + virtual-op roundtrip the agent loop
   * requires.
   */
  private host_: PluginHostApi | undefined;

  constructor(apiBase: string, onUpdate: () => void) {
    this.apiBase = apiBase;
    this.onUpdate = onUpdate;
    this.reasoning = new ReasoningController(() => this.notify());
    // Tempdoc 561 P-D: a mid-run dial move updates the live backend session so the issuance policy
    // reflects it on the next gated tool call. No active session → nothing to update (the level rides
    // the next run's request).
    this.autonomyUnsub = subscribeAutonomy(() => {
      if (this.sessionId) void this.pushAutonomy(getAutonomyLevel());
    });
  }

  private autonomyUnsub: (() => void) | null = null;

  private async pushAutonomy(level: string): Promise<void> {
    try {
      await fetch(`${this.apiBase}/api/chat/agent/autonomy`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ sessionId: this.sessionId, level }),
      });
    } catch {
      // Best-effort: a failed dial sync leaves the prior level in force (safe — it can only be staler,
      // never more permissive than the user's last successful sync).
    }
  }

  /**
   * Tempdoc 577 Ext III — the raise-budget remedy. Per-run directive (dispatched ONLY through the
   * runControlIntent seam, like halt): grants the live session additional tokens; the next
   * budget_update on the stream reflects the raised remaining/ceiling.
   */
  async raiseBudget(addTokens: number): Promise<boolean> {
    if (!this.sessionId || !Number.isFinite(addTokens) || addTokens <= 0) return false;
    try {
      const res = await fetch(`${this.apiBase}/api/chat/agent/budget`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ sessionId: this.sessionId, addTokens }),
      });
      return res.ok;
    } catch {
      return false;
    }
  }

  /**
   * Tempdoc 565 §30 — the DIRECTION authority's `interject`: push a free-form mid-run steering
   * directive to the live backend run. The loop drains it at the next step boundary and folds it into
   * the next LLM call; the acknowledgement returns as a `directive_acknowledged` SSE event
   * (→ {@link onDirectiveAcknowledged}). Returns false (no-op) when there is no live session to steer.
   * Mirrors {@link pushAutonomy} — both write a per-run control input the loop reads between steps.
   */
  async steer(text: string): Promise<boolean> {
    const trimmed = text.trim();
    if (!trimmed || !this.sessionId) return false;
    try {
      const res = await fetch(`${this.apiBase}/api/chat/agent/steer`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ sessionId: this.sessionId, text: trimmed }),
      });
      if (!res.ok) {
        // Tempdoc 565 §33 — a failed steer (404: the run finished between render and submit) must NOT be
        // silent. Surface a one-line system note so the user knows their direction didn't land.
        this.steerFailureNote();
        return false;
      }
      return true;
    } catch {
      this.steerFailureNote();
      return false;
    }
  }

  /** Tempdoc 565 §33 — a one-line system note when a steer couldn't be delivered (the run finished). */
  private steerFailureNote(): void {
    this.conversation = [
      ...this.conversation,
      {
        id: this.nextEntryId(),
        type: 'progress',
        content: 'Could not steer — the run already finished.',
        severity: 'warn',
        timestamp: Date.now(),
      },
    ];
    this.notify();
  }

  /** Tempdoc 508-followup §ε3 — wire the host API after construction. */
  setHost(host: PluginHostApi | undefined): void {
    this.host_ = host;
  }

  private nextEntryId(): string {
    // Tempdoc 565 §27.2 — zero-pad so the id sorts LEXICALLY == TEMPORALLY (`e-000010` > `e-000002`).
    // `projectLiveAgentActivity` sorts by `ts || id.localeCompare`, and entries pushed in the same
    // millisecond (a node boundary + a committed assistant turn) tie on `ts` → the id decides; an
    // unpadded `e-10` would sort BEFORE `e-2` and mis-order the live run (the live mirror of the §26.I
    // record-side temporally-sortable node-id fix). The id is consumed ONLY by the sort tiebreaker and
    // `data-item-id` (format-agnostic) — no dedup/reconciliation keys on it (§27.G de-risk).
    return `e-${String(++this.entryIdCounter).padStart(6, '0')}`;
  }

  // --- Microtask-deduped notification (§3.1.1) ---
  private notify(): void {
    if (!this.pendingNotify) {
      this.pendingNotify = true;
      queueMicrotask(() => {
        this.pendingNotify = false;
        this.onUpdate();
      });
    }
  }

  // --- Lifecycle ---

  startPolling(): void {
    void this.checkAvailability();
    this.availabilityPoll = window.setInterval(() => {
      if (this.available !== true) void this.checkAvailability();
    }, AVAILABILITY_POLL_MS);
  }

  stopPolling(): void {
    if (this.availabilityPoll !== null) {
      window.clearInterval(this.availabilityPoll);
      this.availabilityPoll = null;
    }
  }

  destroy(): void {
    this.stopPolling();
    this.abortController?.abort();
    this.reasoning.destroy();
    this.autonomyUnsub?.();
    this.autonomyUnsub = null;
    // Tempdoc 585 §D Phase 1 (D1): stop the background-completion presence poll.
    if (this.presencePollTimer !== null) {
      window.clearTimeout(this.presencePollTimer);
      this.presencePollTimer = null;
    }
  }

  // --- CoreAgentRunHandlers implementation (14 SSE mutation paths) ---

  /**
   * Tempdoc 585 §D Phase 1 (C1) — enter replay mode for a FINISHED run and load its persisted events
   * (GET /api/chat/sessions/{id}/events). After Phase 0 unified the persistence mapping, those events
   * are a faithful match for the live wire payloads, so they feed the SAME per-event handlers. Shows
   * the full run by default; the view's scrubber calls {@link replaySeek}/{@link replayStepForward}.
   */
  async loadReplay(sessionId: string): Promise<void> {
    this.replayMode = true;
    this.sessionId = sessionId;
    this.replayEvents = await this.fetchSessionEvents(sessionId);
    this.replaySeek(this.replayEvents.length);
  }

  /**
   * Tempdoc 585 §D Phase 2 (D3) — load a SHARED/exported run transcript as a replay. The export
   * artifact (the backend `handleSessionTranscript` download, `{meta, events:[…]}`) carries the same
   * persisted event records; this strips each to the `{eventType, payload}` replay shape (identical to
   * {@link fetchSessionEvents}) and drives the SAME scrubber + projection the C1 inspector uses — so a
   * downloaded run becomes a portable, replayable artifact. Returns false when the JSON has no usable
   * events (so the caller can surface a "not a valid transcript" message).
   */
  loadReplayFromExport(exported: unknown): boolean {
    const body = exported as { events?: Array<Record<string, unknown>>; meta?: { sessionId?: string } };
    const events = (body?.events ?? [])
      .map((e) => ({ eventType: String(e.eventType ?? ''), payload: (e.payload as unknown) ?? {} }))
      .filter((e) => e.eventType);
    if (events.length === 0) return false;
    this.replayMode = true;
    this.sessionId = body?.meta?.sessionId ?? 'shared-replay';
    this.replayEvents = events;
    this.replaySeek(events.length);
    return true;
  }

  /** Reset the run state and re-apply persisted events [0, index) — the scrubber's seek primitive. */
  replaySeek(index: number): void {
    const n = Math.max(0, Math.min(index, this.replayEvents.length));
    this.resetRunState();
    for (let i = 0; i < n; i++) {
      const ev = this.replayEvents[i];
      if (ev) this.dispatchEvent(ev.eventType, ev.payload);
    }
    this.commitStreamingText();
    this.replayCursor = n;
    this.notify();
  }

  replayStepForward(): void {
    this.replaySeek(this.replayCursor + 1);
  }

  replayStepBack(): void {
    this.replaySeek(this.replayCursor - 1);
  }

  /** Leave replay mode and clear the replayed run state (back to an idle live controller). */
  exitReplay(): void {
    this.replayMode = false;
    this.replayEvents = [];
    this.replayCursor = 0;
    this.resetRunState();
    this.notify();
  }

  /** Clears the run-observable state (NOT the roster/tools) so a replay seek re-applies from scratch. */
  private resetRunState(): void {
    this.conversation = [];
    this.toolCalls = {};
    this.streamingText = '';
    this.isStreaming = false;
    this.iterationsUsed = 0;
    this.toolCallsExecuted = 0;
    this.answerSources = [];
    this.answerCitations = [];
    this.currentToolBatch = [];
    this.totalTokensUsed = null;
    this.budgetUpdates = [];
    this.budgetGate = null;
    this.contextGate = null;
    this.activeAgentId = null;
    this.navigationReceipts = [];
    this.lastAgentCausationId = null;
    this.reasoning = new ReasoningController(() => this.notify());
  }

  private async fetchSessionEvents(
    sessionId: string,
  ): Promise<Array<{ eventType: string; payload: unknown }>> {
    try {
      const res = await fetch(
        `${this.apiBase}/api/chat/sessions/${encodeURIComponent(sessionId)}/events`,
      );
      if (!res.ok) return [];
      const body = (await res.json()) as { events?: Array<Record<string, unknown>> };
      return (body.events ?? []).map((e) => ({
        eventType: String(e.eventType ?? ''),
        payload: (e.payload as unknown) ?? {},
      }));
    } catch {
      return [];
    }
  }

  onSessionStarted(payload: CoreAgentRunSessionStartedPayload): void {
    this.sessionId = payload.sessionId ?? null;
    // Tempdoc 577 Root I — this stream established a live run, so a later mid-run drop is eligible to
    // reattach (distinct from an initial POST failure where no run exists + sessionId may be stale).
    this.runStartedThisStream = true;
    // Tempdoc 577 Root I (#1d) — publish the live AGENT run to the cross-tab pointer so a second tab
    // can discover + reattach to it. Stamped with the conversation so a tab pinned to a DIFFERENT
    // conversation won't adopt it. Cleared on terminal (done/error/not-live).
    if (!this.replayMode && this.runKind === 'agent' && this.sessionId) {
      setActiveRun(this.sessionId, this.conversationId);
    }
    // F2: a new run starts with no proposed batch (avoid showing a prior run's stale plan).
    this.currentToolBatch = [];
    // 543-fwd #6 — a new turn starts a fresh causation chain.
    this.lastAgentCausationId = null;
    if (this.sessionId && this.pendingAutoApprovals.length > 0) {
      const queued = this.pendingAutoApprovals.splice(0);
      for (const callId of queued) void this.approveCall(callId);
    }
    this.notify();
  }

  onReasoningChunk(payload: unknown): void {
    this.reasoning.handleReasoningChunk(payload);
  }

  onChunk(payload: CoreAgentRunChunkPayload): void {
    this.reasoning.endThinking();
    this.streamingText = this.streamingText + (payload.text ?? '');
    this.notify();
  }

  onToolCallProposed(payload: unknown): void {
    this.handleToolCallEntry(payload, 'proposed');
  }

  onToolCallPending(payload: unknown): void {
    this.handleToolCallEntry(payload, 'pending');
  }

  /**
   * Tempdoc 550 N1: record the turn's proposed tool-call batch (announced before any per-call
   * gate). A surface can render this as the turn's plan; per-call approval is unaffected.
   */
  onToolBatchProposed(payload: CoreAgentRunToolBatchProposedPayload): void {
    this.currentToolBatch = (payload.calls ?? []).map((c) => ({
      callId: c.callId,
      toolName: c.toolName,
    }));
    this.notify();
  }

  private handleToolCallEntry(payload: unknown, status: 'proposed' | 'pending'): void {
    const data = payload as Record<string, unknown>;
    const callId = data.callId as string;
    if (status === 'pending') {
      this.commitStreamingText({ groupCallId: callId });
    }
    this.toolCalls = {
      ...this.toolCalls,
      [callId]: {
        ...(this.toolCalls[callId] ?? {
          callId,
          toolName: data.toolName as string,
          arguments: data.arguments as string,
          risk: data.risk as ToolRisk,
          status,
          // Tempdoc 561 P-D1: capture the backend gate verdict from the wire (may be absent).
          gateBehavior: (data.gateBehavior as BackendGateBehavior | undefined) ?? undefined,
        }),
        status,
      },
    };
    if (!this.sessionId && data.sessionId) {
      this.sessionId = data.sessionId as string;
    }
    // Tempdoc 585 §D Phase 1 (C1): in replay the tool-call render state is set above, but a finished
    // run's pending gate must NOT re-drive an approval POST or pop the authorization ceremony.
    if (this.replayMode) {
      this.notify();
      return;
    }
    // Tempdoc 561 P-D (the autonomy-policy collapse): the BACKEND issuance policy
    // (IntentGateEvaluator.agentGate) is the SOLE auto-approval authority. It already folded in the
    // user's autonomy dial (sent on the request + the /autonomy endpoint), so the FE simply OBEYS its
    // GateBehavior — auto-approve iff AUTO — instead of re-deriving from risk (agentToolAutoApprove,
    // removed). Any non-AUTO verdict (incl. an absent one from legacy/test wiring) routes to the
    // ceremony: the FE never silently auto-approves without an explicit backend AUTO.
    const backendGate = data.gateBehavior as BackendGateBehavior | undefined;
    if (status === 'pending' && backendGate === 'auto') {
      if (this.sessionId) {
        void this.approveCall(callId);
      } else {
        this.pendingAutoApprovals.push(callId);
      }
    } else if (status === 'pending') {
      // Tempdoc 550 C3 (producer migration): a tool call needing a human decision routes through the
      // ONE unified ceremony host (jf-authorization-host via the broker). The ceremony's gate is the
      // BACKEND verdict (INLINE_CONFIRM / TYPED_CONFIRM here), not a risk-derived guess.
      const toolName = data.toolName as string;
      const argsSummary = typeof data.arguments === 'string' ? data.arguments : undefined;
      // Tempdoc 561 P-D: the risk tier for the ceremony's fallback + context (the backend verdict is
      // the primary signal; risk only seeds the legacy/absent-verdict heuristic below).
      const risk = data.risk as ToolRisk;
      // Tempdoc 605 Move 1 — the ceremony is owned by the run that issued the call (the event's
      // sessionId, else the current run). The owner lets a run conclusion drain its own ceremonies
      // (Move 2) and routes approve/reject to THIS run, never a later run's stale sessionId (M2).
      const owningRunId = (data.sessionId as string | undefined) ?? this.sessionId ?? undefined;
      void requestAuthorization({
        pendingId: callId,
        operationId: toolName,
        ...(owningRunId ? { owningRunId } : {}),
        // Tempdoc 561 P-D1: the ceremony's confirmation mechanism is the BACKEND verdict when the
        // loop supplied it (collapsing the FE re-derivation); fall back to the risk heuristic only
        // when absent (legacy/test wiring).
        gateBehavior: backendGate
          ? (backendGate.toUpperCase() as 'AUTO' | 'INLINE_CONFIRM' | 'TYPED_CONFIRM' | 'DENY')
          : risk === 'HIGH'
            ? 'TYPED_CONFIRM'
            : 'INLINE_CONFIRM',
        // Tempdoc 550 P1: give the ceremony the decision context it already has from the event.
        riskTier: risk,
        ...(argsSummary ? { argsSummary } : {}),
      }).then((decision) => {
        // Tempdoc 605 — the ceremony was drained because its run concluded (not a human deny):
        // the run is gone, so do NOT POST a reject (concludeRunCeremonies already surfaced the one
        // legible notice).
        if (decision.superseded) return;
        if (!decision.approved) {
          void this.rejectCall(callId, 'Denied in authorization ceremony', owningRunId);
          return;
        }
        // Route the approval to the run that OWNED the ceremony (Move 1, M2 fix), not whatever
        // this.sessionId happens to be when the decision resolves. If neither is known yet, queue
        // it (replayed on session start) — mirrors the auto-approve pendingAutoApprovals path.
        const runId = owningRunId ?? this.sessionId ?? undefined;
        if (runId) {
          void this.approveCall(callId, runId);
        } else {
          this.pendingAutoApprovals.push(callId);
        }
      });
    }
    this.notify();
  }

  onToolCallApproved(payload: CoreAgentRunToolCallApprovedPayload): void {
    this.updateToolCall(payload.callId, { status: 'approved' });
  }

  onToolExecStarted(payload: CoreAgentRunToolExecStartedPayload): void {
    const callId = payload.callId;
    if (!this.toolCalls[callId]) return;
    const grouped = this.conversation.some(
      (e) => e.type === 'tool-call-group' && e.callIds?.includes(callId),
    );
    if (!grouped) {
      this.commitStreamingText({ groupCallId: callId });
    }
    this.updateToolCall(callId, { status: 'executing' });
  }

  onToolExecCompleted(payload: unknown): void {
    const data = payload as Record<string, unknown>;
    const callId = data.callId as string;
    if (!this.toolCalls[callId]) return;
    const result = data.result as Record<string, unknown> | undefined;
    const success = (result?.success as boolean) ?? (data.success as boolean) ?? false;
    this.updateToolCall(callId, {
      status: 'completed',
      success,
      output: (result?.output as string) ?? (data.output as string) ?? '',
      executionId: (result?.executionId as string) ?? (data.executionId as string) ?? undefined,
      structuredData:
        (result?.structuredData as Record<string, unknown>) ??
        (data.structuredData as Record<string, unknown>) ??
        undefined,
    });
    // 543-fwd idea #0 — bridge the real (successful) agent tool-call into the
    // Effect Journal. Only successes: a failed op changed no state, so it is not
    // an "AI action" to undo or digest. (The chat transcript still shows it.)
    // Tempdoc 585 §D Phase 1 (C1): never journal during replay — the op already ran (and was already
    // journalled) when the run was live; re-journalling a finished run would inject phantom undo
    // affordances + ledger entries.
    if (success && !this.replayMode) {
      const completed = this.toolCalls[callId];
      if (completed) this.journalAgentToolCall(completed);
    }
  }

  /**
   * 543-fwd idea #0 — agent→journal bridge. Mirror a successful server-side
   * tool-call into the Effect Journal as an `originator:'agent'`
   * invoke-operation entry. Uses `recordEffect` (journal-only), NEVER
   * `applyEffect` — the op already executed server-side; re-dispatching would
   * re-run it (a destructive `core_file_operations` would fire twice). When the
   * backend returned an `executionId` (undoSupported ops), wire "undo the AI"
   * through the existing executionId → POST /api/undo/{id} path via
   * `markUndoableOperation` (the audit log's per-entry Undo button reads that
   * side-map). vop_ tools are skipped — they already self-journal via
   * `onToolCallVirtual` → `invokeAndApply('agent')` → `applyEffect`.
   */
  private journalAgentToolCall(call: ToolCall): void {
    if (call.toolName.startsWith('vop_')) return; // self-journaled via the virtual path
    let args: Record<string, unknown> = {};
    try {
      const parsed = call.arguments ? JSON.parse(call.arguments) : {};
      if (parsed && typeof parsed === 'object') args = parsed as Record<string, unknown>;
    } catch {
      /* non-JSON args — journal with empty args; the operationId label is the signal */
    }
    const effect: Effect = { kind: 'invoke-operation', operationId: call.toolName, args };
    // 543-fwd #6 — chain this tool-call to the previous one in the same turn so
    // the journal records a real per-turn causation chain (the "why did this
    // happen" trace). The first call of a turn has no parent (chain root).
    const entry = recordEffect(effect, CORE_PROVENANCE, {
      originator: 'agent',
      ...(this.lastAgentCausationId !== null ? { causation: this.lastAgentCausationId } : {}),
    });
    this.lastAgentCausationId = entry.id;
    if (call.executionId) {
      markUndoableOperation(entry.id, call.toolName, call.executionId);
    }
  }

  onToolCallRejected(payload: CoreAgentRunToolCallRejectedPayload): void {
    const callId = payload.callId;
    if (!this.toolCalls[callId]) return;
    this.updateToolCall(callId, { status: 'rejected', rejectReason: payload.reason });
  }

  /**
   * Tempdoc 508 §11.5 / §13.5 Phase B — handle a `tool_call_virtual`
   * SSE frame. The agent loop is blocked waiting on the matching
   * future; we resolve the wireName via VirtualOperationCatalog,
   * invoke the matching shell/plugin command, and POST the captured
   * result to /api/chat/agent/tool-result.
   *
   * Lazy-imports the dispatcher to keep the module graph small for
   * sessions that never hit this path.
   */
  onToolCallVirtual(payload: unknown): void {
    // Tempdoc 585 §D Phase 1 (C1): NEVER dispatch a virtual (shell/plugin) tool during replay — it
    // would re-execute a real command and POST a result to a dead session. The most dangerous guard.
    if (this.replayMode) return;
    const data = payload as Record<string, unknown>;
    const callId = data.callId as string;
    const wireName = data.wireName as string;
    const args = (data.arguments as string) ?? '';
    if (!callId || !wireName) return;
    // sessionId may come from the event or from the controller's
    // own tracked session id (set on session_started).
    const sessionId = (data.sessionId as string) ?? this.sessionId ?? '';
    if (!sessionId) {
      // No session context — agent will time out after 30s on
      // server side. Nothing we can do client-side.
      return;
    }
    void import('../commands/VirtualToolDispatcher.js').then(
      ({ dispatchVirtualToolCall }) =>
        dispatchVirtualToolCall(
          { sessionId, callId, wireName, arguments: args },
          { apiBase: this.apiBase },
        ),
    );
  }

  onDone(payload: CoreAgentRunDonePayload): void {
    this.reasoning.finalize();
    // F2: the run is complete — the turn's proposed batch is no longer pending.
    this.currentToolBatch = [];
    const streamedSoFar = this.streamingText.trim();
    this.commitStreamingText();
    const finalResp = payload.finalResponse;
    if (finalResp?.trim() && finalResp.trim() !== streamedSoFar) {
      this.conversation = [
        ...this.conversation,
        { id: this.nextEntryId(), type: 'assistant-text', content: finalResp, timestamp: Date.now() },
      ];
    }
    this.iterationsUsed = payload.iterationsUsed ?? 0;
    this.toolCallsExecuted = payload.toolCallsExecuted ?? 0;
    this.totalTokensUsed = payload.totalTokensUsed ?? null;
    // Tempdoc 565 §3.A — capture the answer's grounding (clickable local-passage citations).
    // The generated `done` payload now types `sources`/`citations` (§13.8 schema-drift fix) — no
    // loose-cast: an empty/absent list yields no grounding, a populated one drives the rail + chips.
    this.answerSources = payload.sources ?? [];
    this.answerCitations = payload.citations ?? [];
    this.isStreaming = false;
    this.runKind = null; // §33 — run terminal: idle again
    // Tempdoc 585 §D Phase 1 (C1): replaying a finished run must not retire a REAL live run's pointer.
    if (!this.replayMode) clearActiveRun(); // 577 Root I (#1d) — the run ended; retire the pointer.
    if (!this.replayMode) this.concludeRunCeremonies(this.sessionId); // Tempdoc 605 Move 2 — drain this run's open ceremonies.
    this.notify();
  }

  /**
   * Tempdoc 565 §3.A/persistence — rehydrate the answer's grounding from the persisted record (the
   * reload case, where the live SSE `onDone` never fired this session). The record is the authority;
   * subscribers (the Sources pane + the "Sources · N" affordance) re-render via notify.
   */
  hydrateAnswerEvidence(sources: AgentSource[], citations: AgentSentenceCite[]): void {
    this.answerSources = sources;
    this.answerCitations = citations;
    this.notify();
  }

  /** Tempdoc 577 Move 2 — the run parked at the budget boundary; the drawer renders the decision. */
  onBudgetGate(payload: unknown): void {
    const data = payload as Record<string, unknown>;
    this.budgetGate = {
      tokensNeeded: (data.tokensNeeded as number) ?? 0,
      tokensRemaining: (data.tokensRemaining as number) ?? 0,
      totalTokensConsumed: (data.totalTokensConsumed as number) ?? 0,
    };
    this.notify();
  }

  /**
   * Tempdoc 577 Move 2 — resolve the held budget gate (finalize | stop). Per-run directive:
   * dispatched ONLY through the runControlIntent seam. CONTINUE is not a value here — raising the
   * budget IS the continue decision (the raise endpoint resolves the gate backend-side).
   */
  async resolveBudgetGate(decision: 'finalize' | 'stop'): Promise<boolean> {
    if (!this.sessionId) return false;
    try {
      const res = await fetch(`${this.apiBase}/api/chat/agent/budget-decision`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ sessionId: this.sessionId, decision }),
      });
      if (res.ok) {
        this.budgetGate = null;
        this.notify();
      }
      return res.ok;
    } catch {
      return false;
    }
  }

  /** Tempdoc 577 §2.14 Root II — the run parked at the context-pressure boundary; render the decision. */
  onContextGate(payload: unknown): void {
    const data = payload as Record<string, unknown>;
    this.contextGate = {
      promptTokens: (data.promptTokens as number) ?? 0,
      contextWindow: (data.contextWindow as number) ?? 0,
    };
    this.notify();
  }

  /** Tempdoc 577 §2.14 Root II — the compaction landed; clear the gate (a progress line narrates it). */
  onContextCompacted(_payload: unknown): void {
    this.contextGate = null;
    this.notify();
  }

  /**
   * Tempdoc 577 §2.14 Root II — resolve the held context gate (continue | summarize | stop). Per-run
   * directive: dispatched ONLY through the runControlIntent seam (like budget-decision).
   */
  async resolveContextGate(decision: 'continue' | 'summarize' | 'stop'): Promise<boolean> {
    if (!this.sessionId) return false;
    try {
      const res = await fetch(`${this.apiBase}/api/chat/agent/context-decision`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ sessionId: this.sessionId, decision }),
      });
      if (res.ok) {
        this.contextGate = null;
        this.notify();
      }
      return res.ok;
    } catch {
      return false;
    }
  }

  onBudgetUpdate(payload: unknown): void {
    const data = payload as Record<string, unknown>;
    // Tempdoc 577 Move 2 — any budget movement means the gate resolved (CONTINUE emits the next
    // iteration_start; FINALIZE streams the synthesis; STOP terminates via error).
    this.budgetGate = null;
    // Tempdoc 577 §2.14 Root II — a budget update also means the loop advanced past any context gate.
    this.contextGate = null;
    // Tempdoc 577 Move 1 — bounded buffer: only the latest update is consumed (the drawer reads
    // the tail); keep a short window for debugging, never an unbounded session-lifetime append.
    const BUDGET_UPDATES_CAP = 50;
    this.budgetUpdates = [
      ...this.budgetUpdates.slice(-(BUDGET_UPDATES_CAP - 1)),
      {
        phase: (data.phase as string) ?? '',
        tokensConsumed: (data.tokensConsumed as number) ?? 0,
        tokensRemaining: (data.tokensRemaining as number) ?? 0,
        // Tempdoc 577 Ext III — the run-cumulative figure the ceiling derivation needs (§2.9 V4).
        totalTokensConsumed:
          typeof data.totalTokensConsumed === 'number' ? data.totalTokensConsumed : undefined,
        // Tempdoc 577 §2.14 Root II — the cognitive-headroom figures (occupancy ÷ n_ctx).
        promptTokens: typeof data.promptTokens === 'number' ? data.promptTokens : undefined,
        contextWindow: typeof data.contextWindow === 'number' ? data.contextWindow : undefined,
      },
    ];
    this.notify();
  }

  onError(payload: unknown): void {
    const data = payload as Record<string, unknown>;
    this.errorHandledDuringStream = true;
    this.conversation = [
      ...this.conversation,
      {
        id: this.nextEntryId(),
        type: 'error',
        content: (data.error as string) ?? 'Unknown error',
        errorCode: data.errorCode as string,
        timestamp: Date.now(),
      },
    ];
    this.isStreaming = false;
    this.runKind = null; // §33 — run terminal (error): idle again
    // Tempdoc 585 §D Phase 1 (C1): replaying a finished (errored) run must not retire a live pointer.
    if (!this.replayMode) clearActiveRun(); // 577 Root I (#1d) — the run ended (error); retire pointer.
    if (!this.replayMode) this.concludeRunCeremonies(this.sessionId); // Tempdoc 605 Move 2 — the abnormal-terminal case (the M1 trigger).
    this.notify();
  }

  onProgress(payload: unknown): void {
    const data = payload as Record<string, unknown>;
    this.conversation = [
      ...this.conversation,
      {
        id: this.nextEntryId(),
        type: 'progress',
        content: (data.message as string) ?? (data.phase as string) ?? '',
        // Tempdoc 561 #5: carry the backend severity so the renderer decorates by intent.
        severity: typeof data.severity === 'string' ? data.severity : undefined,
        // Tempdoc 577 Ext II: carry the typed phase token — the label authority projects from it.
        phase: typeof data.phase === 'string' ? data.phase : undefined,
        timestamp: Date.now(),
      },
    ];
    this.notify();
  }

  onHandoffProposed(payload: unknown): void {
    const data = payload as Record<string, unknown>;
    this.conversation = [
      ...this.conversation,
      {
        id: this.nextEntryId(),
        type: 'handoff',
        content: `Handoff: ${data.fromAgentId} → ${data.toAgentId}: ${data.reason}`,
        fromAgentId: data.fromAgentId as string,
        toAgentId: data.toAgentId as string,
        // Tempdoc 585 §D Phase 2 (D2) — keep the reason as a discrete field so <jf-handoff-card>
        // renders it beside the role badges (not only baked into the `content` fallback string).
        reason: data.reason as string,
        timestamp: Date.now(),
      },
    ];
    this.notify();
  }

  onHandoffExecuted(payload: unknown): void {
    const data = payload as Record<string, unknown>;
    this.activeAgentId = data.toAgentId as string;
    this.notify();
  }

  /**
   * Tempdoc 585 §D Phase 2 (C4) — apply the one-shot state primer emitted on (re)attach BEFORE the
   * buffered-event replay. Seeds the run's current active agent so a late attacher — especially a
   * precise B1 `Last-Event-ID` reconnect that replays only the events it missed — reflects where the
   * run stands without reconstructing it from the full history. Suppressed in replay mode (a finished
   * run's snapshot is part of its recorded stream, not a live primer).
   */
  onStateSnapshot(payload: CoreAgentRunStateSnapshotPayload): void {
    if (this.replayMode) return;
    if (payload.activeAgentId) {
      this.activeAgentId = payload.activeAgentId;
    }
    this.notify();
  }

  // --- Navigate URL handlers (slice 491 E17 probe 4) ---

  onNavigateUrlExtracted(payload: unknown): void {
    const d = payload as Record<string, unknown>;
    this.navigationReceipts = [
      ...this.navigationReceipts,
      {
        outcome: 'extracted',
        target: (d.target as string) ?? '',
        addressKind: (d.addressKind as string) ?? '',
      },
    ];
    this.notify();
  }

  onNavigateUrlDispatched(payload: unknown): void {
    const d = payload as Record<string, unknown>;
    const outcome = (d.outcome as string) ?? 'dispatched';
    const idx = this.navigationReceipts.findIndex(
      (r) => r.target === (d.target as string) && r.outcome === 'extracted',
    );
    if (idx >= 0) {
      const updated = [...this.navigationReceipts];
      const prev = updated[idx]!;
      updated[idx] = { ...prev, outcome: outcome as 'forwarded' | 'dispatched' };
      this.navigationReceipts = updated;
    } else {
      this.navigationReceipts = [
        ...this.navigationReceipts,
        {
          outcome: outcome as 'forwarded' | 'dispatched',
          target: (d.target as string) ?? '',
          addressKind: (d.addressKind as string) ?? '',
        },
      ];
    }
    this.notify();
  }

  onNavigateUrlRejected(payload: unknown): void {
    const d = payload as Record<string, unknown>;
    const idx = this.navigationReceipts.findIndex(
      (r) => r.target === (d.target as string) && r.outcome === 'extracted',
    );
    if (idx >= 0) {
      const updated = [...this.navigationReceipts];
      const prev = updated[idx]!;
      updated[idx] = {
        outcome: 'rejected',
        target: prev.target,
        addressKind: prev.addressKind,
        reasonCode: (d.reason as string) ?? undefined,
        message: (d.message as string) ?? undefined,
      };
      this.navigationReceipts = updated;
    } else {
      this.navigationReceipts = [
        ...this.navigationReceipts,
        {
          outcome: 'rejected',
          target: (d.target as string) ?? '',
          addressKind: (d.addressKind as string) ?? '',
          reasonCode: (d.reason as string) ?? undefined,
          message: (d.message as string) ?? undefined,
        },
      ];
    }
    this.notify();
  }

  // --- ToolCall state helper ---

  private updateToolCall(callId: string, updates: Partial<ToolCall>): void {
    if (!this.toolCalls[callId]) return;
    this.toolCalls = {
      ...this.toolCalls,
      [callId]: { ...this.toolCalls[callId], ...updates },
    };
    this.notify();
  }

  // --- Event dispatch (preserves `this` binding) ---

  private dispatchEvent(event: string, payload: unknown): void {
    // Tempdoc 604 — every received event (incl. the unmapped backend `heartbeat`) is proof the
    // stream is live; reset the liveness watchdog. A silent hang stops kicking it and trips recovery.
    // Scoped to the steerable agent run (send/attachToRun) — a workflow run (runKind 'workflow') has
    // no reattach/recover path, so it does not arm this watchdog.
    if (this.isStreaming && this.runKind === 'agent') {
      this.liveWatchdog.kick();
    }
    const map: Record<string, ((p: unknown) => void) | undefined> = {
      session_started: (p) => this.onSessionStarted(p as CoreAgentRunSessionStartedPayload),
      reasoning_chunk: (p) => this.onReasoningChunk(p),
      chunk: (p) => this.onChunk(p as CoreAgentRunChunkPayload),
      tool_batch_proposed: (p) => this.onToolBatchProposed(p as CoreAgentRunToolBatchProposedPayload),
      tool_call_proposed: (p) => this.onToolCallProposed(p),
      tool_call_pending: (p) => this.onToolCallPending(p),
      tool_call_approved: (p) => this.onToolCallApproved(p as CoreAgentRunToolCallApprovedPayload),
      tool_exec_started: (p) => this.onToolExecStarted(p as CoreAgentRunToolExecStartedPayload),
      tool_exec_completed: (p) => this.onToolExecCompleted(p),
      tool_call_rejected: (p) => this.onToolCallRejected(p as CoreAgentRunToolCallRejectedPayload),
      // Tempdoc 508 §11.5 / §13.5 Phase B — virtual tool call from
      // the LLM. Resolve via VirtualOperationCatalog, invoke the
      // matching shell/plugin command, POST the result back so the
      // agent loop unblocks.
      tool_call_virtual: (p) => this.onToolCallVirtual(p),
      done: (p) => this.onDone(p as CoreAgentRunDonePayload),
      budget_update: (p) => this.onBudgetUpdate(p),
      // Tempdoc 577 Move 2 — the run parked at the budget boundary (held decision).
      budget_gate: (p) => this.onBudgetGate(p),
      // Tempdoc 577 §2.14 Root II — the run parked at the context-pressure boundary; the compaction
      // event is a progress narration (folds through onProgress / PROGRESS_PHASE_LABELS).
      context_gate: (p) => this.onContextGate(p),
      context_compacted: (p) => this.onContextCompacted(p),
      // Tempdoc 577 §2.14 Root I — the attach target is no longer a live run; fall back to the record.
      attach_not_live: (p) => this.onAttachNotLive(p),
      error: (p) => this.onError(p),
      progress: (p) => this.onProgress(p),
      handoff_proposed: (p) => this.onHandoffProposed(p),
      handoff_executed: (p) => this.onHandoffExecuted(p),
      // Tempdoc 585 §D Phase 2 (C4) — the state primer a (re)attaching observer receives BEFORE the
      // replay. Seeds run state (active agent) so a late/precise-reconnect attach reflects where the
      // run stands without replaying the whole history.
      state_snapshot: (p) => this.onStateSnapshot(p as CoreAgentRunStateSnapshotPayload),
      'navigate.url_extracted': (p) => this.onNavigateUrlExtracted(p),
      'navigate.url_dispatched': (p) => this.onNavigateUrlDispatched(p),
      'navigate.url_rejected': (p) => this.onNavigateUrlRejected(p),
      'intent.resolution': (p) => this.onIntentResolution(p),
      // Tempdoc 565 §26.B — the workflow run's STRUCTURE: a node boundary becomes a `run-node`
      // conversation entry the live projection brackets into a run segment (the same grouping the
      // record side gets from AgentInteractionMapper). Before §26 these were no-op'd, flattening the
      // node graph; now a workflow run renders as labelled node segments + spine boundaries.
      workflow_started: () => {
        /* the run-level marker carries no per-segment structure; nodes carry it (no-op) */
      },
      node_started: (p) => this.onNodeBoundary(p, 'start'),
      node_completed: (p) => this.onNodeBoundary(p, 'end'),
      // Tempdoc 565 §30 — the DIRECTION authority acknowledged a human mid-run steering directive.
      directive_acknowledged: (p) => this.onDirectiveAcknowledged(p),
    };
    map[event]?.(payload);
  }

  /**
   * Tempdoc 604 — the liveness watchdog expired: no event (not even a heartbeat) arrived within the
   * window, so the agent stream is transport-hung. Abort it so the blocked `await` unwinds; the
   * `liveWatchdogFired` flag tells the stream's catch to treat this as a silent transport drop
   * (recover/conclude) rather than a user cancel. Idempotent / safe after the run already concluded.
   */
  private onLiveStreamStale(): void {
    if (!this.isStreaming) {
      return;
    }
    this.liveWatchdogFired = true;
    this.abortController?.abort();
  }

  /**
   * Tempdoc 565 §26.B — push a workflow node boundary as a `run-node` conversation entry. The live
   * projector ({@link projectLiveAgentActivity}) emits it as a boundary `progress` item that
   * {@link assignRunSegments} consumes to bracket the node's steps into one {@link RunSegmentRef}.
   * Any in-flight streamed text is committed first so it falls inside the correct node.
   */
  onNodeBoundary(payload: unknown, boundary: 'start' | 'end'): void {
    this.commitStreamingText();
    const d = (payload ?? {}) as Record<string, unknown>;
    const nodeId = typeof d.nodeId === 'string' ? d.nodeId : undefined;
    this.conversation = [
      ...this.conversation,
      {
        id: this.nextEntryId(),
        type: 'run-node',
        content: '',
        nodeBoundary: boundary,
        nodeId,
        nodeKind: typeof d.kind === 'string' ? d.kind : undefined,
        nodeLabel: nodeId,
        timestamp: Date.now(),
      },
    ];
    this.notify();
  }

  /**
   * Tempdoc 565 §30 — the agent loop drained a human steering directive at a step boundary; append it
   * as a `steer-directive` entry. The live projection renders it as a human-origin run-spine landmark
   * + a "Your direction: …" body chip, so the user sees their interject land in the run.
   */
  onDirectiveAcknowledged(payload: unknown): void {
    this.commitStreamingText();
    const d = (payload ?? {}) as Record<string, unknown>;
    const text = typeof d.directiveText === 'string' ? d.directiveText : '';
    if (!text) return;
    this.conversation = [
      ...this.conversation,
      { id: this.nextEntryId(), type: 'steer-directive', content: text, timestamp: Date.now() },
    ];
    this.notify();
  }

  onIntentResolution(payload: unknown): void {
    const d = payload as Record<string, unknown>;
    const target = (d.target as string) ?? '';
    const resolution = d.resolution as Record<string, unknown> | undefined;
    const status = (resolution?.status as string) ?? '';
    if (status === 'resolved' || status === 'redirected') return;

    const alternatives = (resolution?.alternatives as Array<Record<string, unknown>>) ?? [];
    const diagnosis = resolution?.diagnosis as Record<string, unknown> | undefined;
    const newEntry = {
      outcome: 'unresolved' as const,
      target,
      addressKind: '' as string,
      message: (diagnosis?.detail as string) ?? 'Unknown target',
      suggestions: alternatives.map(a => ({
        id: (a.id as string) ?? '',
        label: (a.label as string) ?? (a.id as string) ?? '',
      })),
    };

    const idx = this.navigationReceipts.findIndex(
      (r) => r.target === target && r.outcome === 'extracted',
    );
    if (idx >= 0) {
      const updated = [...this.navigationReceipts];
      updated[idx] = { ...updated[idx]!, ...newEntry, addressKind: updated[idx]!.addressKind };
      this.navigationReceipts = updated;
    } else {
      this.navigationReceipts = [...this.navigationReceipts, newEntry];
    }
    this.notify();
  }

  // --- Internal helpers ---

  commitStreamingText(opts?: { groupCallId?: string }): void {
    const text = this.streamingText.trim();
    const updated = [...this.conversation];
    if (text) {
      updated.push({ id: this.nextEntryId(), type: 'assistant-text', content: this.streamingText, timestamp: Date.now() });
      this.streamingText = '';
    }
    if (opts?.groupCallId) {
      const last = updated[updated.length - 1];
      if (last?.type === 'tool-call-group') {
        last.callIds = [...(last.callIds ?? []), opts.groupCallId];
      } else {
        updated.push({
          id: this.nextEntryId(),
          type: 'tool-call-group',
          content: '',
          callIds: [opts.groupCallId],
          timestamp: Date.now(),
        });
      }
    }
    this.conversation = updated;
  }

  // --- HTTP dispatch ---

  async send(message: string): Promise<void> {
    this.conversation = [
      ...this.conversation,
      { id: this.nextEntryId(), type: 'user', content: message, timestamp: Date.now() },
    ];
    this.isStreaming = true;
    this.runKind = 'agent'; // §33 — a `send` run is the steerable agent loop
    this.streamingText = '';
    this.reasoning.reset();
    this.iterationsUsed = 0;
    this.toolCallsExecuted = 0;
    this.totalTokensUsed = null;
    this.budgetUpdates = [];
    this.budgetGate = null;
    this.contextGate = null;
    this.errorHandledDuringStream = false;
    this.reattachedThisRun = false; // Tempdoc 577 Root I — reset the one-shot reattach guard.
    this.runStartedThisStream = false;
    this.liveWatchdogFired = false; // Tempdoc 604 — clear any stale watchdog flag from a prior run.
    this.notify();

    this.abortController = new AbortController();
    this.liveWatchdog.kick(); // Tempdoc 604 — arm liveness detection even if the first frame never lands.
    try {
      // Tempdoc 521 §16.1 deeper (Phase C) — AgentView consumes
      // host.ai.streamShape like every other built-in chat surface.
      // The dispatch endpoint accepts shapeId=core.agent-run with the
      // same body the legacy /api/chat/agent route reads (verified by
      // SSE event-sequence parity audit). Companion virtual-op routes
      // (approve / reject / tool-result / tools / history) remain on
      // /api/chat/agent/* — they are stateful side-channels, not
      // streams, and §11.4 only governs the streaming surface.
      await streamViaHost({
        host_: this.host_,
        shapeId: 'core.agent-run',
        fallbackUrl: `${this.apiBase}/api/chat/agent`,
        body: {
          messages: [{ role: 'user', content: message }],
          tools: [],
          maxIterations: DEFAULT_MAX_ITERATIONS,
          // The single-window chat agent is SINGLE-agent: an empty profile list (and no initialAgentId)
          // puts the backend in single-agent mode (AgentTurnPolicy.shouldForceToolCall → false), so the
          // agent gets the full tool set (emit(catalog, []) = all AGENT ops incl. search) and can search +
          // answer. A lone non-'primary' profile would instead trip the E0a multi-agent policy
          // (AgentStepRunner.buildE0aTools), forcing core_ingest_files on turn 1 with no handoff target.
          // A real manager+workers team is a separate future feature, not a lone manager stub.
          agentProfiles: [],
          // Tempdoc 561 P-A/P-B (Slice 2): stamp the chat conversationId so this run's thread events
          // share the unified interaction record with the chat turns.
          ...(this.conversationId ? { conversationId: this.conversationId } : {}),
          // Tempdoc 561 P-D: send the autonomy dial so the backend issuance policy decides gating.
          autonomyLevel: getAutonomyLevel(),
        },
        onEvent: (event, payload) => this.dispatchEvent(event, payload),
        signal: this.abortController.signal,
      });
    } catch (e) {
      if ((e as Error).name === 'AbortError') {
        if (!this.liveWatchdogFired) {
          this.commitStreamingText();
          return; // a genuine user-initiated cancel — just end.
        }
        // Tempdoc 604 — the watchdog aborted a silently-hung stream (NOT a user cancel). Fall through
        // to the reattach-or-error path below so the run re-establishes or concludes, never hangs.
        this.liveWatchdogFired = false;
      }
      // Tempdoc 577 §2.14 Root I (#13/#1d) — a stream that DROPS mid-run (a transport error, not the
      // user cancelling, and not a backend error already surfaced) does NOT mean the run died: the
      // run is a backend-owned entity (RunEventHub). REATTACH to it once — replay the buffer + stream
      // to terminal — instead of falsely ending it on the FE. onAttachNotLive falls back to the record
      // when the run truly ended. One shot per run (the guard) so a persistently-dead endpoint can't loop.
      if (
        !this.reattachedThisRun &&
        this.runStartedThisStream &&
        !this.errorHandledDuringStream &&
        this.sessionId &&
        this.runKind === 'agent'
      ) {
        this.reattachedThisRun = true;
        this.abortController = null;
        this.isStreaming = false;
        await this.attachToRun(this.sessionId);
        return; // attachToRun owns the stream lifecycle (commit/cleanup) from here
      }
      if (!this.errorHandledDuringStream) {
        this.onError({ error: friendlyStreamError(e) });
      }
    } finally {
      this.liveWatchdog.clear(); // Tempdoc 604 — the stream concluded; disarm the watchdog.
      this.commitStreamingText();
      this.abortController = null;
      this.isStreaming = false;
      this.notify();
    }
  }

  /**
   * Tempdoc 565 §15.C — run a declared workflow through THIS controller (the one run-render
   * authority). The workflow shape's live stream shares the agent tool_call_*, chunk, done vocabulary,
   * so its events populate the SAME conversation/toolCalls model `projectLiveAgentActivity` renders —
   * the workflow run shows as unified turns + jf-tool-call-card + the shared `<jf-authorization-host>`
   * approval ceremony, with no bespoke surface. The workflow-only wrappers (workflow_started/node_*)
   * are gracefully ignored by `dispatchEvent` (no handler = no-op).
   */
  async runWorkflow(workflowId: string): Promise<void> {
    this.conversation = [
      ...this.conversation,
      {
        id: this.nextEntryId(),
        type: 'user',
        content: `Run workflow: ${workflowId}`,
        timestamp: Date.now(),
      },
    ];
    this.isStreaming = true;
    this.runKind = 'workflow'; // §33 — a workflow run is NOT steerable (WorkflowShapeRunner, no drain)
    this.streamingText = '';
    this.reasoning.reset();
    this.errorHandledDuringStream = false;
    this.notify();

    this.abortController = new AbortController();
    try {
      await streamViaHost({
        host_: this.host_,
        shapeId: 'core.workflow-run',
        fallbackUrl: `${this.apiBase}/api/chat/agent`,
        body: {
          workflowId,
          ...(this.conversationId ? { conversationId: this.conversationId } : {}),
        },
        onEvent: (event, payload) => this.dispatchEvent(event, payload),
        signal: this.abortController.signal,
      });
    } catch (e) {
      if ((e as Error).name === 'AbortError') {
        this.commitStreamingText();
        return;
      }
      if (!this.errorHandledDuringStream) {
        this.onError({ error: friendlyStreamError(e) });
      }
    } finally {
      this.commitStreamingText();
      this.abortController = null;
      this.isStreaming = false;
      this.notify();
    }
  }

  async approveCall(callId: string, runId?: string): Promise<void> {
    // Tempdoc 605 Move 1 — route to the run that OWNED the ceremony (passed explicitly), not
    // whatever this.sessionId is now (a later/stacked run would 404 — M2). Defaults to the current
    // run for the existing callers (pendingAutoApprovals replay / auto-approve).
    const sessionId = runId ?? this.sessionId;
    if (!sessionId) return;
    // Tempdoc 565 §15.C enforcement — the ONE approval endpoint dispatches the shared ceremony's
    // verdict to the matching backend gate (agent gate by sessionId+callId, else the workflow gate by
    // callId). The FE no longer branches the URL by run shape ("a run is a run" all the way down).
    try {
      const res = await fetch(`${this.apiBase}/api/chat/approve`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ sessionId, callId }),
      });
      // Surface a failed approval instead of swallowing it — a non-ok POST (e.g. a stale backend that
      // lacks the §15.J unified route → 404) would otherwise leave the tool card stuck PENDING with no
      // feedback. On success the SSE stream reflects the approval.
      if (!res.ok) {
        this.onError({
          error: `Couldn't send your approval (HTTP ${res.status}) — the action was not approved. The run may be stale or the backend out of date.`,
        });
      }
    } catch {
      this.onError({
        error: 'Couldn’t reach the backend to send your approval — the action was not approved.',
      });
    }
  }

  async rejectCall(callId: string, reason?: string, runId?: string): Promise<void> {
    // Tempdoc 605 Move 1 — reject the run that OWNED the ceremony (M2), defaulting to the current run.
    const sessionId = runId ?? this.sessionId;
    if (!sessionId) return;
    try {
      const res = await fetch(`${this.apiBase}/api/chat/reject`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ sessionId, callId, reason }),
      });
      if (!res.ok) {
        this.onError({
          error: `Couldn't send your rejection (HTTP ${res.status}) — the action was not rejected. The run may be stale or the backend out of date.`,
        });
      }
    } catch {
      this.onError({
        error: 'Couldn’t reach the backend to send your rejection — the action was not rejected.',
      });
    }
  }

  /**
   * Tempdoc 605 Move 2 — a run reaching a TERMINAL (done / error / cancel / attach-not-live)
   * fail-closes its own still-open approval ceremonies, so a stuck predecessor ceremony can no
   * longer block the NEXT run's ceremony from surfacing on the one host (the M1 fix; the run-
   * conclusion half of 550 Thesis II "every in-flight record has a live owner"). Keyed by the
   * concluding run's id, so a reattach of the SAME live run is NOT a conclusion and never reaches
   * here. Surfaces ONE legible notice via the existing 559 system-message channel when ≥1 ceremony
   * was auto-denied — never silent. Other runs' ceremonies and plugin consent requests are untouched.
   */
  private concludeRunCeremonies(runId: string | null | undefined): void {
    if (!runId) return;
    const denied = cancelAuthorizationsForRun(runId);
    if (denied > 0) {
      emitEphemeralToast({
        message:
          denied === 1
            ? 'A pending action from the previous run was cancelled because the run ended.'
            : `${denied} pending actions from the previous run were cancelled because the run ended.`,
        severity: 'warning',
      });
    }
  }

  async cancelSession(): Promise<void> {
    this.abortController?.abort();
    if (this.sessionId) {
      try {
        await fetch(
          `${this.apiBase}/api/chat/sessions/${encodeURIComponent(this.sessionId)}`,
          { method: 'DELETE' },
        );
      } catch {
        // ignore
      }
    }
    this.commitStreamingText();
    this.isStreaming = false;
    this.runKind = null; // §33 — run halted: idle again
    this.concludeRunCeremonies(this.sessionId); // Tempdoc 605 Move 2 — halting a run drains its open ceremonies.
    this.notify();
  }

  async resumeSession(sessionId: string): Promise<void> {
    this.streamingText = '';
    this.isStreaming = true;
    this.runKind = 'agent'; // §33 — resuming a prior agent run (an AgentLoopService session) is steerable
    this.errorHandledDuringStream = false;
    this.notify();

    this.abortController = new AbortController();
    try {
      await consumeShapeStream(
        `${this.apiBase}/api/chat/sessions/${encodeURIComponent(sessionId)}/resume`,
        null,
        (event, payload) => this.dispatchEvent(event, payload),
        this.abortController.signal,
      );
    } catch (e) {
      if ((e as Error).name === 'AbortError') {
        this.commitStreamingText();
        return;
      }
      if (!this.errorHandledDuringStream) {
        this.onError({ error: friendlyStreamError(e) });
      }
    } finally {
      this.commitStreamingText();
      this.abortController = null;
      this.isStreaming = false;
      this.notify();
    }
  }

  /**
   * Tempdoc 585 §D Phase 3 (C2) — TIME-TRAVEL FORK: branch a NEW live run from a finished one
   * (POST /api/chat/sessions/{id}/fork), rewinding to its last user turn and replacing that question
   * with {@link editedMessage} (blank ⇒ a re-roll of the original). Leaves replay mode (a fork is a
   * fresh live run, not a replay of the source) and streams the new run through the SAME dispatch.
   */
  async forkRun(sessionId: string, editedMessage: string): Promise<void> {
    this.exitReplay();
    this.streamingText = '';
    this.isStreaming = true;
    this.runKind = 'agent';
    this.errorHandledDuringStream = false;
    this.notify();

    this.abortController = new AbortController();
    try {
      await consumeShapeStream(
        `${this.apiBase}/api/chat/sessions/${encodeURIComponent(sessionId)}/fork`,
        { editedMessage },
        (event, payload) => this.dispatchEvent(event, payload),
        this.abortController.signal,
      );
    } catch (e) {
      if ((e as Error).name === 'AbortError') {
        this.commitStreamingText();
        return;
      }
      if (!this.errorHandledDuringStream) {
        this.onError({ error: friendlyStreamError(e) });
      }
    } finally {
      this.commitStreamingText();
      this.abortController = null;
      this.isStreaming = false;
      this.notify();
    }
  }

  /**
   * Tempdoc 577 §2.14 Root I (#13/#1d) — ATTACH this controller to a LIVE backend run as an observer:
   * the run is a backend-owned entity (its loop survives the original socket close), so on
   * mount/reconnect we re-open a stream to {@code /api/chat/agent/{sessionId}/attach}. The backend
   * REPLAYS the run's buffered history then streams ongoing events through the SAME dispatch the
   * initiating run used — so the window is a projection of (run × this observer), and a dropped
   * connection (or a second tab) reattaches without restarting the run. If the run is no longer live
   * the backend emits `attach_not_live`; the caller then reads the persisted thread record instead.
   */
  async attachToRun(sessionId: string): Promise<void> {
    this.sessionId = sessionId;
    this.streamingText = '';
    this.isStreaming = true;
    this.runKind = 'agent'; // an attached live run is steerable like the run that started it
    this.errorHandledDuringStream = false;
    this.liveWatchdogFired = false; // Tempdoc 604 — clear any stale watchdog flag from a prior run.
    this.notify();

    this.abortController = new AbortController();
    this.liveWatchdog.kick(); // Tempdoc 604 — detect a reattach that connects but never delivers a frame.
    try {
      await consumeShapeStream(
        `${this.apiBase}/api/chat/agent/${encodeURIComponent(sessionId)}/attach`,
        null,
        (event, payload) => this.dispatchEvent(event, payload),
        this.abortController.signal,
      );
    } catch (e) {
      if ((e as Error).name === 'AbortError') {
        if (this.liveWatchdogFired) {
          // Tempdoc 604 — the reattach was transport-hung (no heartbeat within the window). This IS
          // the reattach (one-shot), so conclude the run with a terminal error rather than hanging to
          // the backend's 30-min attach latch. (A parked-but-alive run keeps heartbeating and never
          // reaches here.) The terminal lets the run's pending ceremonies drain (tempdoc 605).
          this.liveWatchdogFired = false;
          if (!this.errorHandledDuringStream) {
            this.onError({
              error: 'Lost connection to the agent run — it may still be running in the background.',
            });
          }
          return;
        }
        this.commitStreamingText();
        return;
      }
      if (!this.errorHandledDuringStream) {
        this.onError({ error: friendlyStreamError(e) });
      }
    } finally {
      this.liveWatchdog.clear(); // Tempdoc 604 — the reattach concluded; disarm the watchdog.
      this.commitStreamingText();
      this.abortController = null;
      this.isStreaming = false;
      this.notify();
    }
  }

  /** Tempdoc 577 §2.14 Root I — the backend signalled the run is no longer live; fall back to the record. */
  onAttachNotLive(_payload: unknown): void {
    this.isStreaming = false;
    this.runKind = null;
    // `attach_not_live` IS the terminal signal for a reattach to an already-ended run: the backend
    // emits it then closes the SSE with no done/error event, so consumeShapeStream raises
    // STREAM_INCOMPLETE. Mark the stream handled so that incomplete-close does not surface as a
    // spurious error entry — falling back to the persisted record is the correct, quiet outcome.
    this.errorHandledDuringStream = true;
    clearActiveRun(); // Tempdoc 577 Root I (#1d) — the pointer was stale (run no longer live); retire it.
    this.concludeRunCeremonies(this.sessionId); // Tempdoc 605 Move 2 — the attached run already ended; drain its ceremonies.
    this.notify();
  }

  /**
   * Tempdoc 577 §2.14 Root I (#1d) — cross-tab reattach on load. A freshly-loaded tab (with no run of
   * its own) consults the shared {@link readActiveRun} pointer and, if another tab has a live agent
   * run, ATTACHES to it — so the run is observed across tabs, not just within the tab that started it.
   * A no-longer-live pointer resolves quietly via {@link onAttachNotLive} (record fallback + clear).
   * One-shot per controller; the caller (the view's first agent mount) guards against re-entry.
   */
  async reattachActiveRunOnLoad(): Promise<void> {
    if (this.isStreaming || this.sessionId) {
      return; // this tab already owns/observes a run — do not steal or double-attach.
    }
    const pointer = readActiveRun();
    if (!pointer || pointer.runKind !== 'agent') {
      return;
    }
    // Conversation guard: a tab pinned to a SPECIFIC conversation must not adopt a run that belongs
    // to a different one (the pointer is global / latest-run-wins). A fresh/default tab (no specific
    // conversation) still resumes the active run — the intended cross-tab behaviour.
    if (this.conversationId && pointer.conversationId && this.conversationId !== pointer.conversationId) {
      return;
    }
    await this.attachToRun(pointer.sessionId);
  }

  async checkAvailability(): Promise<void> {
    try {
      const res = await fetch(`${this.apiBase}/api/chat/agent/tools`);
      if (!res.ok) {
        this.available = false;
        this.tools = [];
        this.notify();
        return;
      }
      const data = (await res.json()) as { available: boolean; tools: AgentToolInfo[] };
      this.available = data.available;
      this.tools = data.tools ?? [];
    } catch {
      this.available = false;
      this.tools = [];
    }
    this.notify();
  }

  async loadSessions(): Promise<void> {
    try {
      // Tempdoc 508-followup §ε3 — prefer host.data.fetch when wired
      // so plugin-installed observability / rate-limiting middleware
      // sees the call. Falls back to direct fetch for back-compat
      // with mount paths that don't inject host_.
      const res = this.host_
        ? await this.host_.data.fetch(`/api/chat/sessions?limit=${SESSIONS_LIMIT}`)
        : await fetch(`${this.apiBase}/api/chat/sessions?limit=${SESSIONS_LIMIT}`);
      if (!res.ok) return;
      const data = (await res.json()) as { sessions?: SessionListItem[] };
      this.sessions = data.sessions ?? [];
    } catch {
      // ignore
    }
    this.notify();
  }

  /**
   * Tempdoc 561 P-B2: load the workspace activity Timeline — a DISTINCT projection of the one
   * action ledger (chronological, all kinds + originators), via the shared ActionLedgerClient
   * authority. This replaces the V1 Timeline that just re-rendered the Sessions roster (the
   * live-audit's "Timeline ≈ Sessions"): Sessions answers "which runs exist", Timeline answers
   * "what happened, in order" — two declared projections of one record, not two copies.
   */
  async loadTimeline(): Promise<void> {
    try {
      const client = new ActionLedgerClient({ apiBase: this.apiBase });
      this.timeline = await client.unifiedActivity();
    } catch {
      // ignore
    }
    this.notify();
  }

  /**
   * Tempdoc 561 P-B1: the History tab is a projection of the ONE action ledger, filtered to this
   * agent session via the cross-domain join key (P-A1: the loop stamps its sessionId as the
   * ledger row's `correlationId`). It no longer reads the separate `/api/chat/agent/history`
   * (FileOperationLog) source — that file-ops-only authority is exactly why a `search` tool call
   * could complete yet leave History reading "No tool call history" (the live-audit defect). Now
   * the live thread and History project the same record, so they cannot disagree, and an empty
   * History while the record is non-empty is unrepresentable.
   */
  async loadHistory(): Promise<void> {
    if (!this.sessionId) {
      this.history = [];
      this.notify();
      return;
    }
    try {
      const res = await fetch(
        `${this.apiBase}/api/action-ledger?originator=agent&correlationId=${encodeURIComponent(
          this.sessionId,
        )}`,
      );
      if (!res.ok) return;
      const data = (await res.json()) as { entries?: BackendLedgerEntry[] };
      const rows = data.entries ?? [];
      // tempdoc 558 §S1 — project through the ONE shared projection (projectBackend), so History and
      // the Activity surface render the same record identically rather than forking a HistoryEntry.
      this.history = rows
        .filter((r) => r.kind === 'operation')
        .slice(-HISTORY_LIMIT)
        .map(projectBackend);
    } catch {
      // ignore
    }
    this.notify();
  }

  /**
   * Tempdoc 565 §26.D — load the cross-conversation background-run inbox (`/api/presence`, the
   * projection of `AgentRunStore` filtered to background runs). The retrospective drawer's Inbox tab
   * renders these; a conversation-linked background run ALSO renders inline in the thread as a
   * `background` run-segment (§26.A), so the two are consistent views of the one run entity.
   */
  async loadPresence(): Promise<void> {
    try {
      const res = await fetch(`${this.apiBase}/api/presence`);
      if (!res.ok) return;
      const data = (await res.json()) as { runs?: PresenceRun[] };
      this.presence = Array.isArray(data.runs) ? data.runs : [];
      this.detectBackgroundCompletions();
    } catch {
      // ignore
    }
    this.notify();
  }

  // Tempdoc 585 §D Phase 1 (D1) — the "what the agent did while you were away" signal: presence is a
  // read-time projection, so a background run's COMPLETION is detected by comparing the prior snapshot
  // to the new one. Only a run we previously observed NON-terminal and now see terminal toasts — so
  // historical DONE runs on first load (a fresh drawer open) never spam, only genuine transitions
  // observed this session do. The toast's "View" action opens the run in the C1 replay inspector.
  private static readonly PRESENCE_TERMINAL = new Set(['DONE', 'ERROR']);
  private prevPresenceStates = new Map<string, string>();

  private detectBackgroundCompletions(): void {
    for (const run of this.presence) {
      const prev = this.prevPresenceStates.get(run.sessionId);
      const nowTerminal = AgentSessionController.PRESENCE_TERMINAL.has(run.state);
      if (prev !== undefined && !AgentSessionController.PRESENCE_TERMINAL.has(prev) && nowTerminal) {
        const ok = run.state === 'DONE';
        const tools = run.toolCalls ?? 0;
        emitEphemeralToast({
          message: `Agent ${ok ? 'finished' : 'failed'} a background task · ${tools} tool${tools === 1 ? '' : 's'}`,
          severity: ok ? 'success' : 'error',
          actionLabel: 'View',
          onAction: () => void this.loadReplay(run.sessionId),
        });
      }
      this.prevPresenceStates.set(run.sessionId, run.state);
    }
  }

  // Tempdoc 585 §D Phase 1 (D1): after launching a background run, poll presence while any background
  // run is non-terminal so detectBackgroundCompletions sees the transition and fires the toast.
  private presencePollTimer: number | null = null;

  private pollPresenceUntilIdle(maxPolls = 30): void {
    if (this.presencePollTimer !== null) return; // already polling
    let polls = 0;
    const tick = async (): Promise<void> => {
      await this.loadPresence();
      polls += 1;
      const anyLive = this.presence.some(
        (r) => !AgentSessionController.PRESENCE_TERMINAL.has(r.state),
      );
      if (anyLive && polls < maxPolls) {
        this.presencePollTimer = window.setTimeout(() => void tick(), 4000);
      } else {
        this.presencePollTimer = null;
      }
    };
    this.presencePollTimer = window.setTimeout(() => void tick(), 4000);
  }

  /**
   * Tempdoc 565 §26.D — launch a background run FROM this conversation: the conversationId is carried so
   * the run joins this conversation's run history (rendering as a `background` segment, §26.A) as well as
   * the cross-conversation inbox. Reloads presence shortly after (the run executes detached).
   */
  async runBackgroundTask(prompt: string): Promise<void> {
    const trimmed = prompt.trim();
    if (!trimmed) return;
    try {
      await fetch(`${this.apiBase}/api/presence/run`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          prompt: trimmed,
          ...(this.conversationId ? { conversationId: this.conversationId } : {}),
        }),
      });
      // Tempdoc 585 §D Phase 1 (D1): poll until the run completes so the completion toast fires.
      this.pollPresenceUntilIdle();
    } catch {
      // ignore
    }
  }

  fork(fromIndex: number): ConversationEntry[] {
    return this.conversation.slice(0, fromIndex + 1).map(e => ({ ...e, id: this.nextEntryId() }));
  }

  loadForkedConversation(entries: ConversationEntry[]): void {
    this.conversation = entries;
    const retainedCallIds = new Set<string>();
    for (const e of entries) {
      if (e.callIds) for (const id of e.callIds) retainedCallIds.add(id);
    }
    const forkedCalls: Record<string, ToolCall> = {};
    for (const id of retainedCallIds) {
      if (this.toolCalls[id]) forkedCalls[id] = { ...this.toolCalls[id] };
    }
    this.toolCalls = forkedCalls;
    this.streamingText = '';
    this.isStreaming = false;
    this.sessionId = null;
    this.iterationsUsed = 0;
    this.toolCallsExecuted = 0;
    this.totalTokensUsed = null;
    this.budgetUpdates = [];
    this.budgetGate = null;
    this.contextGate = null;
    this.activeAgentId = null;
    this.pendingAutoApprovals = [];
    this.notify();
  }
}
