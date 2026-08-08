// SPDX-License-Identifier: Apache-2.0
/**
 * SearchV2View — the Search v2 window (tempdoc 818 slices 1-2).
 *
 * A from-scratch sibling of the shipped search window, built to test the twelve laws of the 818
 * prototype against a real backend. Nothing here is copied from `UnifiedChatView.ts`: the point of
 * building beside rather than editing is that a copy would import the model-level defects (three
 * parallel conversation representations, authored counts, state-gated affordances).
 *
 * The window is one records array plus projections:
 *  - the transcript, the session index (rail mode B) and the session name are all PROJECTIONS of
 *    `records` (`records.ts`) — no region authors its own copy (L11), and the name/index appear at
 *    the first commit because the projection says so, not because a flag was flipped (L8).
 *  - the live search is NOT a record. It is fed by the shared `searchState` store (the ONE search
 *    issuance seam — this surface never posts a search itself) and becomes a record only by commit.
 *  - every count on screen is derived: the live/frozen cards read their own snapshot through the
 *    ONE results card, the index header reads Σ node sizes, the grounding line reads the backend's
 *    `sentencesMatched/sentencesTotal` (L6). Nothing on screen is authored by this file.
 *  - the destination pill is `route()` over the draft, with the ⇥ flip as a one-shot lens that
 *    dies on Escape or on commit — never a stored destination (L1/L2/L10).
 *
 * Slice 2 adds the three things a skeleton cannot fake:
 *  - **results parity** — both the live deck occupant and every frozen record render through
 *    `<jf-results-card>`, the product's single results projection. There is no search-v2 row markup.
 *  - **the real ASK tier** — `askClient.ts` dispatches `core.rag-ask` through the shared shape
 *    stream; the answer lands in the slot the commit opened, with the citations panel and a derived
 *    grounding line.
 *  - **lock semantics (L9)** — the aiState `conversationProtection` state is the optimistic hint,
 *    and a 423 from the dispatch runs ONE {@link SearchV2View.refuseLocked} handler: the draft comes
 *    back verbatim, the refusal names its exits, and the answer slot terminates honestly. Unlike the
 *    shipped window there is no second send path to forget — `askClient` is the only issuance site.
 *
 * Slice 3 adds the deck's other occupant and the rail's convention:
 *  - **the delegated run (L2/L8/L9)** — the DELEGATE rung is real. The run is hosted through the ONE
 *    shared `AgentSessionController` (never a second controller) and driven only through the ONE
 *    `dispatchRunControl` seam. Its live feed is attention, so it is NOT records; at the run's
 *    terminal exactly one `agent-run` receipt lands in the transcript.
 *  - **the deck stack (L7)** — while a run is live the deck holds the live search list, the run feed
 *    and the run controls. Only the two BODIES scroll; the controls row — step line, steer, halt,
 *    budget meter, and any held budget/context gate — sits outside every scroll container, so a
 *    decision can never be scrolled or collapsed off screen.
 *  - **the sidebar's time buckets** — rail mode A groups prior sessions by last activity through the
 *    pure `sessionBuckets` module (no clock inside the grouping).
 *
 * Mounted as a hidden DEEPLINK surface, dev audience, no rail entry:
 * `#justsearch://surface/core.search-v2-surface`.
 *
 * Registered in `governance/execution-surfaces.v1.json` (`sv2-window`) as a PROJECTION: it reads
 * `SearchTrace.effectiveMode` to capture the frozen record's retrieval mode, and projects the
 * `RetrievalCitation` evidence set into the shared citations panel + the derived grounding line.
 * Guard: `SearchV2View.answerProjection.test.ts`.
 *
 * Side-effect registers <jf-search-v2> for the chrome dispatcher.
 */

import { html, css, nothing, type TemplateResult } from 'lit';
import { JfElement } from '../../primitives/JfElement.js';
import { surfaceLayoutStyles } from '../../primitives/surfaceLayout.js';
import {
  addScopeChip,
  clearScopeChips,
  removeScopeChip,
  setQuery,
  submitSearch,
  subscribeScopeChips,
  subscribeSearch,
  type SearchScopeChip,
  type SearchState,
} from '../../state/searchState.js';
import {
  subscribeFacetSelections,
  toggleFacetValue,
} from '../../state/searchFiltersState.js';
import {
  createConversationId,
  loadConversations,
  subscribeConversationList,
} from '../../state/conversationListStore.js';
import { subscribeAiState, type AiState } from '../../state/aiStateStore.js';
import { reasonFor } from '../../state/readinessNotice.js';
import { requestSurfaceNavigation } from '../../controllers/navigateRequest.js';
import { projectBudget, projectContextHorizon } from '../budgetProjection.js';
import {
  getAgentSessionController,
  peekAgentSessionController,
  subscribeAgentSession,
} from '../../state/agentSessionStore.js';
import { dispatchRunControl, directiveAvailable } from '../../controllers/runControlIntent.js';
import type {
  AgentSessionController,
  ConversationEntry,
} from '../../controllers/AgentSessionController.js';
import type { SearchTrace } from '../../../api/generated/index.js';
import type { CardSnapshot, SearchProvenance } from '../../components/searchResults/ResultsCard.js';
import type { RetrievalCitation } from '../../components/chat/citationTypes.js';
import { matchCountLabel } from '../../components/searchResults/matchCountLabel.js';
import '../../components/searchResults/ResultsCard.js';
import '../../components/documentPane/DocumentPane.js';
import '../../components/chat/CitationsPanel.js';
import '../../components/chat/ToolCallCard.js';
import { askDocuments } from './askClient.js';
import { RUNGS, applyFlip, route, type RouteContext, type Rung } from './route.js';
import {
  NO_RECORDS,
  appendAgentRun,
  appendUserTurn,
  commitSearch,
  finalizeAnswer,
  pendingAnswerIdFor,
  projectIndex,
  projectSessionName,
  projectTranscript,
  refuseAnswer,
  type FrozenSearchRecord,
  type RunOutcome,
  type SearchCapture,
  type SessionRecord,
  type TranscriptAnswerItem,
  type TranscriptFrozenItem,
  type TranscriptItem,
  type TranscriptRunItem,
} from './records.js';
import {
  bucketSessions,
  messageCountLabel,
  type BucketableSession,
} from './sessionBuckets.js';

/** A rail mode-A row: one prior session, named by what the user can recognise. */
type SessionRow = BucketableSession;

/** The label a non-tool run entry carries, so a progress line never renders as bare prose. */
const RUN_ENTRY_LABEL: Readonly<Record<string, string>> = Object.freeze({
  error: 'Error',
  progress: 'Progress',
  handoff: 'Handoff',
  'run-node': 'Step',
  'steer-directive': 'Steered',
});

/** The answer stream's live accumulation — VIEW state, deliberately outside the records array. */
interface StreamingAnswer {
  readonly id: string;
  text: string;
}

export class SearchV2View extends JfElement {
  static properties = {
    apiBase: { type: String, attribute: 'api-base' },
  } as const;

  declare apiBase: string;

  /** THE records array. Every region below is a projection of exactly this. */
  private records: readonly SessionRecord[] = NO_RECORDS;
  private draft = '';
  /** The one-shot ⇥ lens (L1). Never persisted: cleared by Escape and by every commit. */
  private flipped = false;
  /** The live deck occupant — the shared store's snapshot, never a local re-derivation. */
  private live: SearchState | null = null;
  private sessions: readonly SessionRow[] = [];
  /** The shared scope-chip authority's chips (L3) — pinned here, honoured by the search seam. */
  private chips: readonly SearchScopeChip[] = [];
  /** The shared facet-selection authority's state — the card renders chips from it, never a copy. */
  private facetSelections: Record<string, string[]> = {};
  /** The window's session id, minted at the FIRST commit (a session with no record has no id). */
  private sessionId: string | null = null;
  private streaming: StreamingAnswer | null = null;
  private readingDocPath: string | null = null;
  /** L9 — the optimistic hint, read from the same `/api/status` field the shipped window reads. */
  private sessionLocked = false;
  /** L9 — set only by {@link refuseLocked}: a send the lock actually refused. */
  private lockRefused = false;
  private contextPromptTokens: number | null = null;
  private contextWindow: number | null = null;
  private askAbort: AbortController | null = null;
  /** The ONE shared agent controller, resolved on the first delegate. Never owned/destroyed here. */
  private agentCtrl: AgentSessionController | null = null;
  /** True while THIS window's delegated run has not yet reached its terminal (the receipt is owed). */
  private runOwned = false;
  /** Edge detector for the run's terminal — set once the controller is observed live. */
  private runLive = false;
  /** Where in the shared controller's conversation this window's run begins (its feed's origin). */
  private runEntryStart = 0;
  /** L7 — set by the Halt control, so the receipt says the run was halted rather than "finished". */
  private haltRequested = false;
  private steerDraft = '';
  /** L7 — the ONLY compressible deck occupant: the live search LIST body. */
  private listCollapsed = false;
  private unsubscribeSearch: (() => void) | null = null;
  private unsubscribeAgent: (() => void) | null = null;
  private unsubscribeSessions: (() => void) | null = null;
  private unsubscribeChips: (() => void) | null = null;
  private unsubscribeFacets: (() => void) | null = null;
  private unsubscribeAi: (() => void) | null = null;

  constructor() {
    super();
    this.apiBase = '';
  }

  override connectedCallback(): void {
    super.connectedCallback();
    this.unsubscribeSearch = subscribeSearch((s) => {
      this.live = s;
      this.requestUpdate();
    });
    this.unsubscribeSessions = subscribeConversationList((s) => {
      this.sessions = s.conversations.map((c) => ({
        id: c.id,
        label: (c.title ?? c.firstUserMessage ?? '').trim() || 'Untitled session',
        lastActiveAt: c.lastActiveAt,
        messageCount: c.messageCount,
      }));
      this.requestUpdate();
    });
    this.unsubscribeChips = subscribeScopeChips((chips) => {
      this.chips = chips;
      this.requestUpdate();
    });
    this.unsubscribeFacets = subscribeFacetSelections((sel) => {
      this.facetSelections = sel;
      this.requestUpdate();
    });
    this.unsubscribeAi = subscribeAiState((s) => this.applyAiState(s));
    // Subscribing does NOT create the shared controller (the store's `peek` reader is what this
    // window uses until it actually delegates), so merely mounting Search v2 starts no polling.
    this.unsubscribeAgent = subscribeAgentSession(() => this.onAgentUpdate());
    void loadConversations();
  }

  override disconnectedCallback(): void {
    super.disconnectedCallback();
    this.askAbort?.abort();
    this.askAbort = null;
    this.unsubscribeSearch?.();
    this.unsubscribeSearch = null;
    this.unsubscribeSessions?.();
    this.unsubscribeSessions = null;
    this.unsubscribeChips?.();
    this.unsubscribeChips = null;
    this.unsubscribeFacets?.();
    this.unsubscribeFacets = null;
    this.unsubscribeAi?.();
    this.unsubscribeAi = null;
    // The controller is SHARED: unsubscribe from it, never halt or destroy it. A run is backend-owned
    // and outlives this window; halting is a decision the user makes, not a navigation side effect.
    this.unsubscribeAgent?.();
    this.unsubscribeAgent = null;
  }

  /**
   * L9 — the lock gates the SESSION, so its state comes from the session's own authority
   * (`/api/status` → `conversationProtection.state`), polled by the one aiState store. A lock taken
   * anywhere else (idle auto-lock, another window) reaches this surface the same way it reaches the
   * shipped one; an unlock clears the refusal, because the refusal describes a lock that is gone.
   */
  private applyAiState(s: AiState): void {
    const protection = s.status?.conversationProtection?.state;
    if (protection === 'locked') {
      this.sessionLocked = true;
    } else if (protection === 'unlocked') {
      this.sessionLocked = false;
      this.lockRefused = false;
    }
    this.contextWindow = s.runtime.contextWindow;
    this.requestUpdate();
  }

  /**
   * The shared run controller as a READ: `peek` never constructs one, so the routing question "is a
   * run in flight?" (L2) can be asked on every render without this window creating a controller —
   * and starting its polling — as a side effect of being looked at.
   */
  private agentController(): AgentSessionController | null {
    return this.agentCtrl ?? peekAgentSessionController();
  }

  /**
   * The ONE shared controller (`agentSessionStore`), resolved at the moment this window actually
   * delegates. The run's thread events are stamped with THIS window's session id, so a delegated run
   * lands under the same interaction record as the session's committed turns.
   */
  private ensureAgentCtrl(): AgentSessionController {
    if (!this.sessionId) this.sessionId = createConversationId();
    const ctrl = getAgentSessionController(this.apiBase);
    ctrl.conversationId = this.sessionId;
    this.agentCtrl = ctrl;
    return ctrl;
  }

  /**
   * The shared controller moved. Two things happen here: the window re-renders (the live feed and the
   * run controls are projections of the controller, never copies), and the run's TERMINAL edge is
   * detected so exactly one receipt lands.
   *
   * Liveness is `runInFlight || isStreaming` rather than `runInFlight` alone because the controller's
   * first notification of a run arrives from inside `send()` BEFORE its abort controller exists
   * (`AgentSessionController.ts:1368-1370`) — reading `runInFlight` alone would see that first frame
   * as "not live" and conclude a run that had only just begun. Both flags clear together in the
   * stream's `finally` (`:1436-1441`), so the terminal edge stays exact.
   */
  private onAgentUpdate(): void {
    const ctrl = this.agentController();
    if (ctrl) {
      const live = ctrl.runInFlight || ctrl.isStreaming;
      if (live) {
        this.runLive = true;
      } else if (this.runLive) {
        this.runLive = false;
        this.concludeRun(ctrl);
      }
    }
    this.requestUpdate();
  }

  /**
   * L8 — the run's terminal: its live feed ends and exactly ONE `agent-run` receipt is appended. The
   * counts are captured from the run's OWN observed entries (L6), not from a separate counter, so the
   * receipt can never disagree with the feed it summarises; `totalTokensUsed` is the backend's figure
   * and stays null when the run ended before one was reported.
   *
   * Only a run THIS window started leaves a receipt here. The controller is a product-level
   * singleton, so a run may have been started by the sibling window — that run is not this session's
   * commitment and must not be recorded as one.
   */
  private concludeRun(ctrl: AgentSessionController): void {
    if (!this.runOwned) return;
    this.runOwned = false;
    const entries = ctrl.conversation.slice(this.runEntryStart);
    const outcome: RunOutcome = this.haltRequested
      ? 'halted'
      : entries.some((e) => e.type === 'error')
        ? 'error'
        : 'completed';
    this.haltRequested = false;
    this.records = appendAgentRun(this.records, {
      outcome,
      toolCallCount: this.observedToolCalls(ctrl),
      tokensUsed: ctrl.totalTokensUsed,
    });
  }

  /**
   * L6 — the ONE tool-call count of this window: the distinct calls in the run's own entries, which
   * is exactly the set of cards the feed rendered. The live status line and the run's receipt both
   * read it, so the number cannot change meaning when the run ends — and neither can contradict the
   * cards on screen the way a separately-maintained counter would.
   */
  private observedToolCalls(ctrl: AgentSessionController): number {
    const callIds = new Set<string>();
    for (const e of ctrl.conversation.slice(this.runEntryStart)) {
      if (e.type === 'tool-call-group') for (const id of e.callIds ?? []) callIds.add(id);
    }
    return callIds.size;
  }

  /**
   * L2/L8/L9 — the DELEGATE path. Three things in causal order, mirroring the ask commit: the lock is
   * consulted through the ONE refusal path, the commitment lands as a record, and only then is the
   * run dispatched (through the ONE `dispatchRunControl` seam, per the 565 §30 steering register).
   *
   * Why the lock is consulted BEFORE dispatch rather than from a 423 the way the ask path is: the
   * agent stream has no 423 terminal of its own — `AgentSessionController.send` handles no HTTP
   * status (`AgentSessionController.ts:1372-1442`), so a refusal that only existed downstream would
   * never arrive. Residual gap, stated rather than hidden: a lock taken between the last
   * `/api/status` poll and this dispatch is not refused here; the run starts and the backend's own
   * protection decides. Closing it needs a typed lock terminal on the agent stream — controller work,
   * not window work.
   */
  private delegate(): void {
    const text = this.draft.trim();
    if (!text) return; // L10 — an empty draft submits nowhere, on this rung too.
    if (this.refuseIfLocked()) return;
    const ctrl = this.ensureAgentCtrl();
    this.records = appendUserTurn(this.records, text);
    this.runOwned = true;
    this.runEntryStart = ctrl.conversation.length;
    this.haltRequested = false;
    this.draft = '';
    this.flipped = false;
    this.lockRefused = false;
    this.requestUpdate();
    void dispatchRunControl(ctrl, { kind: 'initiate', prompt: text });
  }

  /** L2 — the alt slot mid-run: the draft becomes a steering directive, not a new commitment. */
  private steerFromDraft(): void {
    const ctrl = this.agentController();
    const text = this.draft.trim();
    if (!ctrl || !text) return;
    this.draft = '';
    this.flipped = false;
    this.requestUpdate();
    void dispatchRunControl(ctrl, { kind: 'interject', text });
  }

  /** The run-controls steer input — the same directive from the deck rather than from the composer. */
  private steerFromControls(): void {
    const ctrl = this.agentController();
    const text = this.steerDraft.trim();
    if (!ctrl || !text) return;
    this.steerDraft = '';
    this.requestUpdate();
    void dispatchRunControl(ctrl, { kind: 'interject', text });
  }

  /** L7 — halt is a decision, so it is recorded as one: the receipt says the run was halted. */
  private halt(): void {
    const ctrl = this.agentController();
    if (!ctrl) return;
    this.haltRequested = true;
    void dispatchRunControl(ctrl, { kind: 'halt' });
  }

  private resolveBudget(decision: 'finalize' | 'stop'): void {
    const ctrl = this.agentController();
    if (!ctrl) return;
    if (decision === 'stop') this.haltRequested = true;
    void dispatchRunControl(ctrl, { kind: 'budget-decision', decision });
  }

  private resolveContext(decision: 'continue' | 'summarize' | 'stop'): void {
    const ctrl = this.agentController();
    if (!ctrl) return;
    if (decision === 'stop') this.haltRequested = true;
    void dispatchRunControl(ctrl, { kind: 'context-decision', decision });
  }

  static styles = [
    surfaceLayoutStyles,
    css`
      :host {
        color: var(--text-primary);
      }
      .win {
        display: flex;
        gap: var(--density-inner-pad-x);
        min-height: 0;
      }
      .rail {
        flex: 0 0 14rem;
        display: flex;
        flex-direction: column;
        gap: var(--density-inner-pad-y);
        border-right: 1px solid var(--border-subtle);
        padding-right: var(--density-inner-pad-x);
        overflow-y: auto;
      }
      .centre {
        flex: 1;
        min-width: 0;
        display: flex;
        flex-direction: column;
        gap: var(--density-inner-pad-y);
        overflow-y: auto;
      }
      .reading {
        flex: 0 0 24rem;
        min-width: 0;
        border-left: 1px solid var(--border-subtle);
        padding-left: var(--density-inner-pad-x);
        overflow-y: auto;
      }
      .name {
        font-size: var(--font-size-md);
        font-weight: 600;
        color: var(--text-primary);
      }
      h2 {
        font-size: var(--font-size-xs);
        font-weight: 600;
        letter-spacing: 0.06em;
        text-transform: uppercase;
        color: var(--text-muted);
        margin: 0;
      }
      .rowlist {
        list-style: none;
        margin: 0;
        padding: 0;
        display: flex;
        flex-direction: column;
        gap: 0.25rem;
      }
      .rowlist li {
        font-size: var(--font-size-sm);
        color: var(--text-secondary);
        overflow-wrap: anywhere;
      }
      .count {
        font-size: var(--font-size-xs);
        color: var(--text-muted);
        font-variant-numeric: tabular-nums;
      }
      button {
        font: inherit;
        font-size: var(--font-size-sm);
        color: var(--text-primary);
        background: var(--surface-2);
        border: 1px solid var(--border-subtle);
        border-radius: 0.4rem;
        padding: 0.3rem 0.6rem;
        cursor: pointer;
        text-align: left;
      }
      button:hover {
        background: var(--surface-hover);
      }
      button[disabled] {
        color: var(--text-muted);
        cursor: not-allowed;
      }
      .band {
        display: flex;
        flex-wrap: wrap;
        align-items: center;
        gap: 0.5rem;
        border: 1px solid var(--border-subtle);
        border-radius: 0.5rem;
        padding: var(--density-inner-pad-y) var(--density-inner-pad-x);
        background: var(--surface-2);
      }
      .band input {
        flex: 1;
        min-width: 12rem;
        font: inherit;
        font-size: var(--font-size-sm);
        color: var(--text-primary);
        background: var(--surface-1);
        border: 1px solid var(--border-subtle);
        border-radius: 0.4rem;
        padding: 0.4rem 0.6rem;
      }
      .rung-pill {
        font-size: var(--font-size-xs);
        font-family: var(--jf-font-mono);
        letter-spacing: 0.06em;
        border: 1px solid var(--border-strong);
        border-radius: 0.4rem;
        padding: 0.2rem 0.5rem;
        color: var(--text-primary);
        background: var(--surface-3);
        white-space: nowrap;
      }
      .rung-pill.alt {
        background: transparent;
        color: var(--text-secondary);
      }
      .rung-pill.off {
        color: var(--text-muted);
        background: transparent;
        border-style: dashed;
      }
      .rung-pill.flip {
        border-color: var(--accent-tint);
        text-decoration: underline dashed 1px;
        text-underline-offset: 3px;
      }
      .pins {
        display: flex;
        flex-wrap: wrap;
        gap: 0.35rem;
        align-items: center;
      }
      .pin {
        font-size: var(--font-size-xs);
        padding: 0.15rem 0.45rem;
        border-radius: 0.75rem;
        border: 1px solid var(--border-strong);
        background: var(--surface-3);
      }
      .frozen {
        border: 1px solid var(--border-subtle);
        border-left: 3px solid var(--accent-tint-45);
        border-radius: 0.4rem;
        padding: var(--density-inner-pad-y) var(--density-inner-pad-x);
        background: var(--surface-2);
      }
      .turn {
        color: var(--text-primary);
        font-size: var(--font-size-sm);
      }
      .pending {
        color: var(--text-muted);
        font-size: var(--font-size-sm);
      }
      .answer {
        display: flex;
        flex-direction: column;
        gap: 0.35rem;
        font-size: var(--font-size-sm);
        color: var(--text-primary);
      }
      .answer-text {
        white-space: pre-wrap;
        margin: 0;
      }
      .refusal {
        display: flex;
        flex-direction: column;
        gap: 0.4rem;
        border: 1px solid var(--border-strong);
        border-radius: 0.4rem;
        padding: var(--density-inner-pad-y) var(--density-inner-pad-x);
        background: var(--surface-2);
        font-size: var(--font-size-sm);
      }
      .refusal p {
        margin: 0;
      }
      .refusal-exits {
        display: flex;
        flex-wrap: wrap;
        gap: 0.4rem;
      }
      .placeholders {
        display: flex;
        flex-wrap: wrap;
        gap: 0.5rem;
      }
      .placeholder {
        flex: 1 1 10rem;
        border: 1px dashed var(--border-subtle);
        border-radius: 0.4rem;
        padding: var(--density-inner-pad-y) var(--density-inner-pad-x);
        color: var(--text-muted);
        font-size: var(--font-size-xs);
      }
      .stack {
        display: flex;
        flex-direction: column;
        gap: var(--density-inner-pad-y);
      }
      /* L7 — the two deck BODIES scroll; they are the compressible half of the deck. */
      .list,
      .feed {
        max-height: 22rem;
        overflow-y: auto;
        min-height: 0;
      }
      .feed {
        display: flex;
        flex-direction: column;
        gap: 0.35rem;
        max-height: 18rem;
        border-left: 3px solid var(--accent-tint-45);
        padding-left: var(--density-inner-pad-x);
      }
      .listhead {
        display: flex;
      }
      button.quiet {
        background: transparent;
        border-color: transparent;
        color: var(--text-muted);
        padding: 0.15rem 0;
      }
      button.quiet:hover {
        background: transparent;
        color: var(--text-primary);
      }
      .run {
        display: flex;
        flex-direction: column;
        gap: var(--density-inner-pad-y);
        min-height: 0;
      }
      /* L7 — the incompressible occupant: a SIBLING of the scroll containers, never inside one. */
      .run-controls {
        display: flex;
        flex-wrap: wrap;
        align-items: center;
        gap: 0.5rem;
        border: 1px solid var(--border-strong);
        border-radius: 0.4rem;
        padding: var(--density-inner-pad-y) var(--density-inner-pad-x);
        background: var(--surface-2);
      }
      .run-controls .steer {
        flex: 1;
        min-width: 10rem;
        font: inherit;
        font-size: var(--font-size-sm);
        color: var(--text-primary);
        background: var(--surface-1);
        border: 1px solid var(--border-subtle);
        border-radius: 0.4rem;
        padding: 0.3rem 0.5rem;
      }
      .gate {
        flex: 1 1 100%;
        display: flex;
        flex-wrap: wrap;
        align-items: center;
        gap: 0.4rem;
        border-top: 1px solid var(--border-subtle);
        padding-top: 0.4rem;
      }
      .gate p {
        margin: 0;
        flex: 1 1 100%;
      }
      .line {
        margin: 0;
        font-size: var(--font-size-sm);
        color: var(--text-secondary);
      }
    `,
  ];

  /**
   * The visible facts routing may read. `runInFlight` is the SHARED controller's own liveness (L2):
   * the run is a product-level singleton, so a run started in the sibling window is still the run
   * this composer's STEER would act on — offering the slot off any other fact would offer a steer
   * that cannot be honoured. An ask stream is deliberately NOT a run: it has nothing to steer.
   */
  private routeContext(): RouteContext {
    return {
      scopePinned: this.chips.length > 0,
      schemaAttached: false,
      runInFlight: this.agentController()?.runInFlight ?? false,
    };
  }

  /** The pill's two slots — `route()` plus the one-shot flip lens. Never stored (L1). */
  private slots(): { primary: Rung; alt: Rung; dimmed: boolean } {
    const ctx = this.routeContext();
    const r = route(this.draft, ctx);
    if (r.empty) {
      // L10 — an empty draft submits nowhere; the pill PREVIEWS the default, dimmed.
      const preview = route('x', ctx);
      if (preview.empty) return { primary: 'search', alt: 'ask', dimmed: true };
      return { primary: preview.primary, alt: preview.alt, dimmed: true };
    }
    const lensed = applyFlip(r, this.flipped);
    return { primary: lensed.primary, alt: lensed.alt, dimmed: false };
  }

  private onInput(e: Event): void {
    this.draft = (e.target as HTMLInputElement).value;
    setQuery(this.draft);
    this.requestUpdate();
  }

  private onKeydown(e: KeyboardEvent): void {
    if (e.key === 'Tab') {
      // The flip only exists while a draft does; an empty input keeps native focus movement.
      if (route(this.draft, this.routeContext()).empty) return;
      e.preventDefault();
      this.flipped = !this.flipped;
      this.requestUpdate();
      return;
    }
    if (e.key === 'Escape') {
      this.flipped = false;
      this.requestUpdate();
      return;
    }
    // L2 — ⌘/Ctrl↩ is the AGENT key: it delegates a new task, and while a run is in flight the same
    // key steers that run instead. Checked before the ⇧↩ branch so a modifier combination can never
    // fall through to the ask commit.
    if (e.key === 'Enter' && (e.metaKey || e.ctrlKey)) {
      e.preventDefault();
      if (this.routeContext().runInFlight) this.steerFromDraft();
      else this.delegate();
      return;
    }
    if (e.key === 'Enter' && e.shiftKey) {
      e.preventDefault();
      this.commit();
      return;
    }
    if (e.key === 'Enter') {
      e.preventDefault();
      if (this.draft.trim()) submitSearch();
    }
  }

  /**
   * The commit (L4/L5/L8): the live set freezes into the transcript as the retrieval scope, the
   * turn lands, the answer slot opens — and the name + index appear because the projections now
   * have a first committed record, not because this method touched them. The ask is dispatched
   * last, so the periphery never moves before the record.
   */
  private commit(): void {
    const live = this.live;
    const rawDraft = this.draft;
    const turnText = rawDraft.trim() || (live?.query ?? '').trim();
    if (!turnText) return; // L10 — nothing to commit.
    if (this.refuseIfLocked()) return;
    const capture: SearchCapture = {
      query: live?.query ?? '',
      hits: live?.results ?? [],
      total: live?.matchCount ?? 0,
      mode: live?.passStage ?? 'unknown',
      tookMs: live?.processingTimeMs ?? null,
      // Captured from the trace at commit time, so the frozen card states how THAT pass retrieved,
      // not how the live search retrieves now.
      retrievalMode:
        (live?.searchTrace as SearchTrace | null | undefined)?.effectiveMode ?? 'UNKNOWN',
      executedAt: new Date().toISOString(),
    };
    const pendingId = pendingAnswerIdFor(this.records);
    const before = this.records;
    this.records = commitSearch(this.records, capture, turnText);
    const frozen = this.records[before.length] as FrozenSearchRecord;
    this.draft = '';
    this.flipped = false;
    this.lockRefused = false;
    this.requestUpdate();
    void this.dispatchAsk(turnText, rawDraft, frozen, pendingId);
  }

  /**
   * L5 — "ask about these N" means these N: the retrieval scope of the answer is exactly the
   * committed snapshot. `docIds` carries the hit PATHS, which is what the backend's `filters.docIds`
   * term-filter matches (the same key the shipped window's scope chips use).
   */
  private askDocIds(frozen: FrozenSearchRecord): string[] {
    return frozen.hits.map((h) => h.path);
  }

  /**
   * The ONE ask dispatch of this window. Every terminal — answer, refusal, failure — fills the slot
   * the commit opened, so a turn can never leave a permanently pending answer.
   */
  private async dispatchAsk(
    question: string,
    rawDraft: string,
    frozen: FrozenSearchRecord,
    pendingId: string,
  ): Promise<void> {
    if (!this.sessionId) this.sessionId = createConversationId();
    this.askAbort = new AbortController();
    this.streaming = { id: pendingId, text: '' };
    this.requestUpdate();
    await askDocuments(
      {
        apiBase: this.apiBase,
        question,
        conversationId: this.sessionId,
        docIds: this.askDocIds(frozen),
        signal: this.askAbort.signal,
      },
      {
        onDelta: (delta) => {
          if (this.streaming?.id !== pendingId) return;
          this.streaming = { id: pendingId, text: this.streaming.text + delta };
          this.requestUpdate();
        },
        onDone: (outcome) => {
          this.streaming = null;
          this.askAbort = null;
          this.contextPromptTokens = outcome.promptTokens;
          this.records = finalizeAnswer(this.records, pendingId, outcome);
          this.requestUpdate();
        },
        onLocked: () => this.refuseLocked(rawDraft || question, pendingId),
        onError: (message) => {
          this.streaming = null;
          this.askAbort = null;
          this.records = refuseAnswer(this.records, pendingId, 'error', message);
          this.requestUpdate();
        },
      },
    );
  }

  /**
   * L9 — the ONE pre-dispatch lock gate, consulted by EVERY send path (ask commit and delegate
   * alike). The lock gates the session, not a button: a refusal must read the same and cost the same
   * whichever rung the draft was headed for. Returns true when the send was refused.
   */
  private refuseIfLocked(): boolean {
    if (!this.sessionLocked) return false;
    this.refuseLocked(this.draft, null);
    return true;
  }

  /**
   * L9 — the ONE refusal handler. The lock gates the session, so every send path lands here — the
   * pre-dispatch gate above and the ask stream's 423 both — and three things follow, all required
   * for the surface to stay honest: the draft comes back to the composer VERBATIM (it is the user's,
   * and nothing else holds it), an answer slot that a commit already opened terminates as refused
   * rather than pending forever, and the locked state is adopted so the composer stops promising a
   * send it cannot make.
   *
   * `pendingId` is null for a refusal that happened BEFORE anything was committed (the pre-dispatch
   * gate): there is no slot to terminate, and inventing a refused record for a turn that never
   * entered the transcript would put an event in the history that did not happen.
   */
  private refuseLocked(draftText: string, pendingId: string | null): void {
    this.streaming = null;
    this.askAbort = null;
    this.sessionLocked = true;
    this.lockRefused = true;
    if (pendingId !== null) {
      this.records = refuseAnswer(
        this.records,
        pendingId,
        'locked',
        reasonFor('conversations.locked').wording,
      );
    }
    if (!this.draft.trim()) this.draft = draftText;
    this.requestUpdate();
  }

  /** Back to the sessions sidebar — an explicit user intent, never a lifecycle side effect. */
  private clearRecords(): void {
    this.askAbort?.abort();
    this.askAbort = null;
    this.streaming = null;
    this.records = NO_RECORDS;
    this.draft = '';
    this.flipped = false;
    this.sessionId = null;
    this.readingDocPath = null;
    this.lockRefused = false;
    this.contextPromptTokens = null;
    // A live run is backend-owned and keeps going; this window simply stops hosting it, so its
    // receipt cannot land in a session that no longer holds the turn that started it. Halting is a
    // decision with its own control — never a side effect of leaving a session.
    this.runOwned = false;
    this.haltRequested = false;
    this.steerDraft = '';
    this.listCollapsed = false;
    clearScopeChips();
    this.requestUpdate();
  }

  /** L9 exit — the refused draft survives the reset: a new session opens WITH the text still in it. */
  private newSessionWithDraft(): void {
    const kept = this.draft;
    this.clearRecords();
    this.draft = kept;
    this.requestUpdate();
  }

  /**
   * L3 — opening a result is one act with two visible consequences: the document opens in the
   * reading pane, and a `file` scope chip pins onto the shared scope authority so the next search
   * is narrowed to it. Both consequences are visible; neither changes where the draft would go.
   */
  private openResult(id: string, hits: ReadonlyArray<{ id: string; title: string; path: string }>): void {
    const hit = hits.find((h) => h.id === id);
    if (!hit) return;
    this.readingDocPath = hit.path;
    addScopeChip({ kind: 'file', label: hit.title, docIds: [hit.path] });
    this.requestUpdate();
  }

  /**
   * L3 — a facet chip is another narrowing: it goes through the shared facet authority and re-issues
   * the search through the ONE seam. Wired because the card renders those chips from the wire
   * `facets` whether or not a host listens — unwired, they would be silently inert affordances.
   */
  private toggleFacet(field: string, value: string): void {
    toggleFacetValue(field, value);
    if (this.live?.query.trim()) submitSearch();
  }

  private closeReadingPane(): void {
    this.readingDocPath = null;
    this.requestUpdate();
  }

  private unpinChip(index: number): void {
    removeScopeChip(index);
    if (this.live?.query.trim()) submitSearch();
  }

  override render(): TemplateResult {
    return html`
      <div class="header">
        <div class="name" data-testid="session-name">${projectSessionName(this.records)}</div>
      </div>
      <div class="body win">
        <nav class="rail" data-testid="rail" aria-label="Session rail">
          ${this.records.length === 0 ? this.sidebar() : this.sessionIndex()}
        </nav>
        <div class="centre">
          ${this.transcript()} ${this.deck()} ${this.placeholders()}
        </div>
        ${this.readingPane()}
      </div>
    `;
  }

  /**
   * Rail mode A (L12): the conventional session sidebar — identical in every pre-session state, with
   * the convention's time buckets. The grouping is the pure {@link bucketSessions} (the clock is a
   * parameter, so the boundaries are testable), and each row's meta is the message count DERIVED from
   * the list entry it describes (L6) — this window counts nothing itself.
   */
  private sidebar(): TemplateResult {
    const buckets = bucketSessions(this.sessions, Date.now());
    return html`
      <div class="stack" data-testid="rail-sidebar">
        <button type="button" data-testid="new-session" @click=${this.clearRecords}>
          New session
        </button>
        <h2>Search sessions</h2>
        ${buckets.length === 0
          ? html`<p class="count" data-testid="session-empty">No earlier sessions</p>`
          : buckets.map(
              (b) => html`<div class="stack" data-testid="session-bucket" data-bucket=${b.id}>
                <h2 data-testid="session-bucket-label">${b.label}</h2>
                <ul class="rowlist" data-testid="session-list">
                  ${b.rows.map(
                    (s) => html`<li data-testid="session-row" data-session-id=${s.id}>
                      ${s.label}
                      <span class="count" data-testid="session-row-meta"
                        >${messageCountLabel(s.messageCount)}</span
                      >
                    </li>`,
                  )}
                </ul>
              </div>`,
            )}
      </div>
    `;
  }

  /** Rail mode B (L12 / L8 corollary): the session index, projected from the records array. */
  private sessionIndex(): TemplateResult {
    const index = projectIndex(this.records);
    return html`
      <div class="stack" data-testid="rail-index">
        <button type="button" data-testid="rail-back" @click=${this.clearRecords}>
          ‹ All sessions
        </button>
        <h2>This session</h2>
        <p class="count" data-testid="index-count">${index.headerCount} entries</p>
        <ul class="rowlist">
          ${index.nodes.map(
            (n) => html`<li data-testid="index-node" data-node-id=${n.id}>
              ${n.label} <span class="count">${n.size}</span>
            </li>`,
          )}
        </ul>
      </div>
    `;
  }

  /** The reading stage — mounted only while a document is open; `pane-close` is its only exit. */
  private readingPane(): TemplateResult | typeof nothing {
    if (!this.readingDocPath) return nothing;
    return html`
      <aside class="reading" data-testid="reading-pane" aria-label="Document">
        <jf-document-pane
          .docPath=${this.readingDocPath}
          api-base=${this.apiBase}
          @pane-close=${this.closeReadingPane}
        ></jf-document-pane>
      </aside>
    `;
  }

  private transcript(): TemplateResult | typeof nothing {
    const items = projectTranscript(this.records);
    if (items.length === 0) return nothing;
    return html`
      <section class="stack" data-testid="transcript">
        ${items.map((item) => this.transcriptItem(item))}
      </section>
    `;
  }

  private transcriptItem(item: TranscriptItem): TemplateResult {
    if (item.kind === 'frozen-search') return this.frozenBlock(item);
    if (item.kind === 'user-turn') {
      return html`<p class="turn" data-testid="turn">${item.text}</p>`;
    }
    if (item.kind === 'answer') return this.answerBlock(item);
    if (item.kind === 'agent-run') return this.runReceipt(item);
    if (item.kind === 'refused-answer') {
      return html`<p class="pending" data-testid="refused-answer" data-reason=${item.reason}>
        ${item.label}
      </p>`;
    }
    // The slot is still open: while the stream runs, the accumulating text lives in VIEW state, so
    // no partial answer is ever written into the records array (L4).
    const streamingText = this.streaming?.id === item.id ? this.streaming.text : '';
    return streamingText
      ? html`<p class="answer-text" data-testid="streaming-answer">${streamingText}</p>`
      : html`<p class="pending" data-testid="pending-answer">${item.label}</p>`;
  }

  /**
   * L8 — the delegated run's receipt in the transcript. One line, entirely derived: the label comes
   * from `runSummaryLabel` over the record's own counts, so the transcript's account of a run cannot
   * drift from the numbers the record carries.
   */
  private runReceipt(item: TranscriptRunItem): TemplateResult {
    return html`<p
      class="turn"
      data-testid="agent-run"
      data-record-id=${item.id}
      data-outcome=${item.outcome}
    >
      ${item.label}
    </p>`;
  }

  /**
   * A frozen record renders through `<jf-results-card variant="snapshot">` — the product's ONE
   * results projection, header included. The card's provenance line derives its counts from this
   * record's own captured numbers, so the frozen header cannot disagree with the frozen rows (L6),
   * and there is no search-v2 count label to drift from `matchCountLabel`.
   */
  private frozenBlock(item: TranscriptFrozenItem): TemplateResult {
    const snapshot: CardSnapshot = {
      query: item.query,
      results: item.hits,
      matchCount: item.matchedTotal,
      totalHits: item.capturedCount,
      facetsTruncated: false,
      isSearching: false,
      processingTimeMs: item.tookMs,
      error: null,
      passStage: item.mode,
    };
    const provenance: SearchProvenance = {
      actor: 'user',
      query: item.query,
      mode: item.retrievalMode,
      matchCount: item.matchedTotal,
      resultCount: item.capturedCount,
      executedAt: item.executedAt,
    };
    return html`
      <div class="frozen" data-testid="frozen-block" data-record-id=${item.id}>
        <jf-results-card
          variant="snapshot"
          .snapshot=${snapshot}
          .provenance=${provenance}
          .askAvailability=${null}
          @card-open=${(e: CustomEvent<{ id: string }>) => this.openResult(e.detail.id, item.hits)}
        ></jf-results-card>
      </div>
    `;
  }

  /**
   * The landed answer: the text, the DERIVED grounding line (L6 — the counts come from the backend's
   * citation-matching pass; a turn it did not measure renders no line at all rather than "0 of 0"),
   * and the shared citations panel over the same evidence the answer stood on.
   */
  private answerBlock(item: TranscriptAnswerItem): TemplateResult {
    const sources: RetrievalCitation[] = [...item.sources];
    return html`
      <div class="answer" data-testid="answer" data-record-id=${item.id}>
        <p class="answer-text" data-testid="answer-text">${item.text}</p>
        ${item.groundedSentencesLabel
          ? html`<p class="count" data-testid="grounding-line">${item.groundedSentencesLabel}</p>`
          : nothing}
        ${item.citations.length > 0 || sources.length > 0
          ? html`<jf-citations-panel
              data-testid="citations"
              .citations=${[...item.citations]}
              .sources=${sources}
              .retrievalMode=${item.retrievalMode ?? ''}
            ></jf-citations-panel>`
          : nothing}
      </div>
    `;
  }

  /**
   * The deck (L7): the occupants that are not yet records — the input band, the live search list,
   * and, while a run is live, that run's feed and controls. Two of those bodies scroll; the
   * decisions never do (see {@link runControls}).
   */
  private deck(): TemplateResult {
    const live = this.live;
    const results = live?.results ?? [];
    const { primary, alt, dimmed } = this.slots();
    const askLabel =
      results.length > 0
        ? `Ask about these ${results.length}`
        : 'Ask anyway — the model retrieves at answer time';
    return html`
      <section class="stack" data-testid="deck">
        ${this.scopeChips()}
        <div class="band" data-testid="input-band">
          <input
            type="text"
            data-testid="draft"
            aria-label="Search or ask about your documents"
            .value=${this.draft}
            @input=${this.onInput}
            @keydown=${this.onKeydown}
          />
          <span
            class="rung-pill ${dimmed ? 'off' : ''} ${this.flipped && !dimmed ? 'flip' : ''}"
            data-testid="pill"
            data-dimmed=${String(dimmed)}
            title=${dimmed
              ? 'previews the default — an empty draft submits nothing (L10)'
              : RUNGS[primary].label}
            >${this.flipped && !dimmed ? '⇥ ' : ''}${RUNGS[primary].pill} ⏎</span
          >
          <span class="rung-pill alt ${dimmed ? 'off' : ''}" data-testid="pill-alt" title=${RUNGS[alt].label}
            >${RUNGS[alt].pill} ${alt === 'steer' ? '⌘⏎' : '⇥'}</span
          >
          <button
            type="button"
            data-testid="commit"
            ?disabled=${this.sessionLocked}
            @click=${this.commit}
          >${askLabel}</button>
          ${/* L9 — the same optimistic hint on BOTH send buttons: a locked session promises no send
                on either rung. The keyboard paths still reach the ONE refusal handler, so the draft
                and the refusal's exits are never lost. */ ''}
          <button
            type="button"
            data-testid="delegate"
            ?disabled=${this.sessionLocked}
            @click=${this.delegate}
          >Delegate ⌘⏎</button>
        </div>
        ${this.lockRefusal()} ${this.contextMeter()}
        ${/* No count line of this window's own: the card's meta line IS the headline count, derived
              through the shared `matchCountLabel`. A second count here would be exactly the fork
              L6 exists to prevent. */ ''}
        <div class="listhead">
          <button
            type="button"
            class="quiet"
            data-testid="list-collapse"
            aria-expanded=${String(!this.listCollapsed)}
            @click=${this.toggleList}
          >${this.listCollapsed ? '▸ Show results' : '▾ Collapse results'}</button>
        </div>
        ${this.listCollapsed
          ? html`<p class="count" data-testid="live-count">${this.liveCountLabel()}</p>`
          : html`<div class="list" data-testid="live-results" data-scrollable="true">
              <jf-results-card
                variant="live"
                .snapshot=${live}
                .facetSelections=${this.facetSelections}
                .askAvailability=${null}
                @card-facet-toggle=${(e: CustomEvent<{ field: string; value: string }>) =>
                  this.toggleFacet(e.detail.field, e.detail.value)}
                @card-open=${(e: CustomEvent<{ id: string }>) =>
                  this.openResult(e.detail.id, results)}
              ></jf-results-card>
            </div>`}
        ${this.runRegion()}
      </section>
    `;
  }

  /** L7 — the list BODY is the one compressible occupant; nothing else in the deck collapses. */
  private toggleList(): void {
    this.listCollapsed = !this.listCollapsed;
    this.requestUpdate();
  }

  /**
   * L7 — the list's minimum honest form. Collapsing may hide the rows; it may not hide what the deck
   * is holding, so the collapsed form still names the set through the SAME shared `matchCountLabel`
   * the card's own header uses (L6: one count authority, two presentations of it).
   */
  private liveCountLabel(): string {
    const live = this.live;
    const shown = live?.results.length ?? 0;
    if (!live || (!live.query.trim() && shown === 0)) return 'Nothing searched yet';
    return `${matchCountLabel(live.matchCount, shown, false, live.totalHits, live.facetsTruncated)} · hidden`;
  }

  /**
   * L2/L7 — the run occupant. It renders only for a run THIS window delegated: the controller is a
   * product-level singleton, and showing a sibling window's run here as if it were this session's
   * would be the same lie the receipt rules out.
   */
  private runRegion(): TemplateResult | typeof nothing {
    const ctrl = this.agentController();
    if (!ctrl || !this.runOwned) return nothing;
    return html`
      <div class="run" data-testid="run-region">
        ${this.runFeed(ctrl)} ${this.runControls(ctrl)}
      </div>
    `;
  }

  /**
   * L8 — the live feed is ATTENTION, not record: it is projected straight from the shared
   * controller's conversation (this window keeps no copy of it) and it ends with the run. The user's
   * own turn is skipped because the transcript already holds it as the commitment that started the
   * run; re-rendering it here would be the second conversation model 818 exists to avoid.
   */
  private runFeed(ctrl: AgentSessionController): TemplateResult {
    const entries = ctrl.conversation.slice(this.runEntryStart);
    return html`
      <div class="feed" data-testid="run-feed" data-scrollable="true">
        ${entries.map((e) => this.runEntry(ctrl, e))}
        ${ctrl.streamingText
          ? html`<p class="answer-text" data-testid="run-streaming">${ctrl.streamingText}</p>`
          : nothing}
      </div>
    `;
  }

  private runEntry(
    ctrl: AgentSessionController,
    entry: ConversationEntry,
  ): TemplateResult | typeof nothing {
    if (entry.type === 'user') return nothing;
    if (entry.type === 'assistant-text') {
      return html`<p class="answer-text" data-testid="run-text">${entry.content}</p>`;
    }
    if (entry.type === 'tool-call-group') {
      // The product's ONE tool-call primitive — approvals render for free through the Shell's
      // `<jf-authorization-host>` ceremony, so this window mounts no approval surface of its own.
      const calls = (entry.callIds ?? []).map((id) => ctrl.toolCalls[id]).filter(Boolean);
      return html`${calls.map(
        (call) => html`<jf-tool-call-card
          data-testid="run-tool-card"
          .toolCall=${call}
          .stepPresentation=${null}
        ></jf-tool-call-card>`,
      )}`;
    }
    return html`<p class="line" data-testid="run-line" data-entry=${entry.type}>
      <span class="count">${RUN_ENTRY_LABEL[entry.type] ?? 'Step'}</span> ${entry.content}
    </p>`;
  }

  /**
   * L7 — the incompressible occupant. Everything here is a DECISION or the fact a decision needs:
   * where the run is, how to redirect it, how to stop it, what it has spent, and any gate it is
   * parked at. This container is a SIBLING of the two scrollable bodies above, never a descendant,
   * so no amount of scrolling or collapsing can put a held decision off screen.
   *
   * Every directive goes through the ONE `dispatchRunControl` seam, and each control's visibility
   * uses the seam's own `directiveAvailable` predicate — the affordance and the dispatch consult the
   * same lifecycle fact, so a control can never be offered for a run that cannot honour it.
   */
  private runControls(ctrl: AgentSessionController): TemplateResult {
    const steerable =
      ctrl.runKind === 'agent' && directiveAvailable(ctrl, { kind: 'interject', text: '' });
    const haltable = directiveAvailable(ctrl, { kind: 'halt' });
    const budget = projectBudget(ctrl.budgetUpdates[ctrl.budgetUpdates.length - 1] ?? null);
    return html`
      <div class="run-controls" data-testid="run-controls" role="group" aria-label="Run controls">
        <p class="count" data-testid="run-status">${this.runStatusLine(ctrl)}</p>
        ${steerable
          ? html`<input
                type="text"
                class="steer"
                data-testid="steer-input"
                aria-label="Redirect the run"
                placeholder="Redirect the run…"
                .value=${this.steerDraft}
                @input=${(e: Event) => {
                  this.steerDraft = (e.target as HTMLInputElement).value;
                }}
                @keydown=${(e: KeyboardEvent) => {
                  if (e.key === 'Enter') {
                    e.preventDefault();
                    this.steerFromControls();
                  }
                }}
              />
              <button type="button" data-testid="run-steer" @click=${this.steerFromControls}>
                Steer
              </button>`
          : nothing}
        ${haltable
          ? html`<button type="button" data-testid="run-halt" @click=${this.halt}>Halt</button>`
          : nothing}
        ${budget
          ? html`<p class="count" data-testid="run-budget" data-band=${budget.color}>
              ${budget.overBudget
                ? `Over the ${budget.ceiling.toLocaleString()}-token budget by ${budget.overBy.toLocaleString()}`
                : `Budget ${budget.pct}% of ${budget.ceiling.toLocaleString()} tokens`}
            </p>`
          : nothing}
        ${this.budgetGate(ctrl)} ${this.contextGate(ctrl)}
      </div>
    `;
  }

  /** L6 — the step line, derived from the run's own activity and its held-gate state. */
  private runStatusLine(ctrl: AgentSessionController): string {
    const calls = this.observedToolCalls(ctrl);
    const phase =
      ctrl.budgetGate || ctrl.contextGate
        ? 'holding for your decision'
        : ctrl.runInFlight || ctrl.isStreaming
          ? 'running'
          : 'finishing';
    return `Step ${ctrl.iterationsUsed} · ${calls} tool ${calls === 1 ? 'call' : 'calls'} · ${phase}`;
  }

  /** L7 — a parked run's economic decision. Rendered as the decision it is, never as a notice. */
  private budgetGate(ctrl: AgentSessionController): TemplateResult | typeof nothing {
    const gate = ctrl.budgetGate;
    if (!gate) return nothing;
    return html`
      <div class="gate" role="group" aria-label="Budget decision" data-testid="budget-gate">
        <p class="count">
          The run needs ${gate.tokensNeeded.toLocaleString()} more tokens;
          ${gate.tokensRemaining.toLocaleString()} remain.
        </p>
        <button
          type="button"
          data-testid="budget-gate-finalize"
          @click=${() => this.resolveBudget('finalize')}
        >Finish with what it has</button>
        <button
          type="button"
          data-testid="budget-gate-stop"
          @click=${() => this.resolveBudget('stop')}
        >Stop the run</button>
      </div>
    `;
  }

  /** L7 — the parked run's cognitive sibling decision (the context window, not the budget). */
  private contextGate(ctrl: AgentSessionController): TemplateResult | typeof nothing {
    const gate = ctrl.contextGate;
    if (!gate) return nothing;
    return html`
      <div class="gate" role="group" aria-label="Context decision" data-testid="context-gate">
        <p class="count">
          The prompt is ${gate.promptTokens.toLocaleString()} of
          ${gate.contextWindow.toLocaleString()} tokens.
        </p>
        <button
          type="button"
          data-testid="context-gate-continue"
          @click=${() => this.resolveContext('continue')}
        >Continue anyway</button>
        <button
          type="button"
          data-testid="context-gate-summarize"
          @click=${() => this.resolveContext('summarize')}
        >Compact older turns</button>
        <button
          type="button"
          data-testid="context-gate-stop"
          @click=${() => this.resolveContext('stop')}
        >Stop the run</button>
      </div>
    `;
  }

  /** L3 — the pinned narrowings, visible and individually removable. */
  private scopeChips(): TemplateResult | typeof nothing {
    if (this.chips.length === 0) return nothing;
    return html`
      <div class="pins" data-testid="scope-chips">
        ${this.chips.map(
          (c, i) => html`<button
            type="button"
            class="pin"
            data-testid="scope-chip"
            @click=${() => this.unpinChip(i)}
          >${c.label} ✕</button>`,
        )}
      </div>
    `;
  }

  /**
   * L9 — the refusal, rendered only once a send was ACTUALLY refused (a merely-locked session gets
   * the disabled commit hint, not a claim that something was lost). It names both of its exits.
   */
  private lockRefusal(): TemplateResult | typeof nothing {
    if (!this.lockRefused) return nothing;
    const r = reasonFor('conversations.locked');
    const nav = r.remedy?.kind === 'navigate' ? r.remedy : null;
    return html`
      <div class="refusal" role="alert" data-testid="lock-refusal">
        <p>${r.wording} — your question was not sent. Your text is back in the composer.</p>
        <div class="refusal-exits">
          ${nav
            ? html`<button
                type="button"
                data-testid="lock-exit-unlock"
                @click=${() => requestSurfaceNavigation(nav.target)}
              >${nav.label}</button>`
            : nothing}
          <button type="button" data-testid="lock-exit-new" @click=${this.newSessionWithDraft}>
            New session with this draft
          </button>
        </div>
      </div>
    `;
  }

  /**
   * The context meter — how full the model's window is, projected by the SHARED
   * {@link projectContextHorizon}. It renders nothing until a real turn reported both numbers, so
   * the deck never shows a fabricated 0%.
   */
  private contextMeter(): TemplateResult | typeof nothing {
    const horizon = projectContextHorizon({
      tokensConsumed: 0,
      tokensRemaining: 0,
      promptTokens: this.contextPromptTokens ?? 0,
      contextWindow: this.contextWindow ?? 0,
    });
    if (!horizon) return nothing;
    return html`<p class="count" data-testid="context-meter" data-band=${horizon.color}>
      Context ${horizon.pct}% of ${horizon.window.toLocaleString()} tokens
    </p>`;
  }

  /** The remaining unbuilt occupant, present as a labelled box so the window's shape stays honest. */
  private placeholders(): TemplateResult {
    return html`
      <div class="placeholders">
        <div class="placeholder" data-testid="placeholder-material-rail">
          Material rail — not yet built
        </div>
      </div>
    `;
  }
}

if (!customElements.get('jf-search-v2')) {
  customElements.define('jf-search-v2', SearchV2View);
}
