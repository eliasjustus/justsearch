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
 * Slice 4 is the presentation pass's first half — motion and vertical space:
 *  - **the commit choreography** — the causal order of §1 rendered as motion (turn → record → deck →
 *    rail/name → answer), on a transient host class, removed by the last animation's end or a
 *    timeout, and entirely absent under `prefers-reduced-motion`.
 *  - **the deck's movable boundary (L7/L13 complete)** — a grip whose FLOOR is computed at drag time
 *    from the deck's own occupants (`deckSizing.ts`), so while a run is live the run controls are
 *    part of the floor and a held decision cannot be dragged off screen any more than it can be
 *    scrolled off; the transcript's own minimum honest form is the ceiling; double-click returns the
 *    boundary to automatic.
 *  - **the unhappy states** — a zero-result search states the honest empty and re-derives its
 *    escalation label for n = 0 ("Ask anyway…", never "Ask about these 0"), and an AI-offline
 *    verdict dims the ASK/DELEGATE destinations WITH the shared reason while SEARCH — the floor —
 *    is untouched.
 *  - **the small-window pass** — the surface consumes tempdoc 814's block-axis breakpoint authority
 *    (`compositionLayout.SHORT_VIEWPORT_*`) rather than minting a second one: the transcript owns
 *    the centre column's slack, the chrome yields below the breakpoint, and each region owns exactly
 *    one scroller.
 *
 *    That last claim is this window's OWN, and it is deliberately not 814 §D3's: D3 says one scroller
 *    per SURFACE (`.conversation` becomes the single scrolling region of the chat surface), and this
 *    window has five side-by-side regions that each scroll. The two are compatible in spirit — D3
 *    attacks NESTED scrollers, each of which marks a place a layout ran out of room and solved it
 *    locally, and none of these nest — but citing D3 as the warrant said something D3 does not say.
 *    The claim is measured on its own terms by the scroller rows of the two ui-shot steps below.
 *
 *    Registered in `governance/ui-proportion-baseline.v1.json` (`search-v2-window`,
 *    `search-v2-small`) since tempdoc 818 §6g C2. It was previously NOT registered, with the cost
 *    deferred to the §5 cutover; the §6c critical pass spent that deferral by showing it was
 *    circular — the cutover is gated on a comparison campaign that is itself gated on this window
 *    being spatially correct, and four of the thirteen findings were geometry no unit test can see.
 *    `governance/sandbox-coverage.v1.json` still carries the surface as `tier: exempt`, and that is
 *    correct rather than an oversight: that register is release-candidate validation of user
 *    journeys, whose trigger genuinely IS the cutover.
 *
 * Slice 5 is the presentation pass's second half — horizontal space, elaboration and input:
 *  - **the rails' movable boundaries (L13 complete)** — both rails carry a grip, with the clamps in
 *    `railSizing.ts` derived from existing authorities (the product's collapsed rail strip, the
 *    proportion register's readable-document and reading-column floors). Widths REMEMBER, because a
 *    width is a preference; the deck's height still resets, because a height is a per-session shape.
 *  - **the extension convention (L14)** — the resting surface shows the identifying minimum and
 *    elaboration extends on hover AND on `:focus-within`, through ONE mechanism (`.ext-row` / `.ext`)
 *    so the rule cannot fork per region. The extended text stays in the accessibility tree (the same
 *    clip pattern `ambientStyles`' `.visually-hidden` uses). The hard boundary holds: counts,
 *    verdicts, LOCKED and grounding stats rest visible — only elaboration extends.
 *  - **the query trail (L12)** — history lives in the input band, never in the rail: pinned searches
 *    from the shared `pinnedSearchState` projection plus this window's own recents (`queryTrail.ts`).
 *    A picked row FILLS the draft and runs the live search; it never commits, because committing is
 *    the user's act.
 *  - **citation-follow landing** — a source click in the shared citations panel (`citation-select`,
 *    the panel's own event — nothing is forked into this window) opens `<jf-document-pane>` at the
 *    cited line range. The land-strong-then-settle is the PANE's own decay, not a second emphasis.
 *  - **the keyboard pass** — ⌥↑/⌥↓ walks the session index and scrolls the record into view, never
 *    while an input has focus; Escape unwinds one layer at a time (history → document → the flip).
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
// Tempdoc 814 §D6 — the ONE block-axis breakpoint authority. This window reads it rather than
// minting a second "what counts as a short window" number (see the §4 note on {@link SearchV2View}).
import { shortViewportMedia, subscribeShortViewport } from '../../primitives/compositionLayout.js';
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
import { projectAvailability, type Availability } from '../../state/availability.js';
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
import { formatRelative } from '../../utils/relativeTime.js';
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
  frozenTimingLabel,
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
import {
  DECK_KEY_STEP_PX,
  clampDeckHeight,
  collectIncompressibleHeights,
  deckFloorFrom,
  listYields,
  transcriptMinPx,
} from './deckSizing.js';
import { reconcileBoundaries, type BoundaryState } from './boundaryReconciler.js';
import { BoundaryReconcilerController } from './BoundaryReconcilerController.js';
import {
  RAIL_KEY_STEP_PX,
  clampRailWidth,
  documentRailCeiling,
  forgetRailWidth,
  railDefaultPx,
  railFloorPx,
  railYields,
  readStoredRailWidth,
  sessionRailCeiling,
  storeRailWidth,
  type RailId,
} from './railSizing.js';
import { filterTrail, mergeRecents, readTrail, recordSubmittedQuery } from './queryTrail.js';
import { subscribePinnedSearches, type SearchPin } from '../../state/pinnedSearchState.js';
import type { DocumentLineRange } from '../../components/documentPane/DocumentPane.js';
import type { CitationSelectDetail } from '../../components/chat/citationTypes.js';

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

/**
 * The transient class that runs the commit choreography (the prototype's `#win.committing`). It is a
 * HOST class, not view state: the animation is presentation, so it must not enter the records array
 * or any projection — nothing on screen means anything different while it is applied.
 */
export const COMMITTING_CLASS = 'committing';

/**
 * The choreography's own length: the last animation starts at 550 ms and runs 300 ms, so 950 ms is
 * the settle plus a rounding margin. Used as the fallback teardown for the case where no
 * `animationend` arrives at all — a delegate commits with no answer region, and a window animating
 * nothing (an element removed mid-flight) must still shed the class.
 */
export const COMMIT_CHOREOGRAPHY_MS = 950;

/** The animation whose end IS the choreography's end (the answer, last in causal order). */
const CHOREOGRAPHY_LAST_ANIMATION = 'sv2-cm-answer';

/**
 * L6 — the ask affordance's label, derived from the set it would freeze. At n = 0 it re-derives
 * rather than degrading: "Ask about these 0" would name a scope that does not exist, and an empty
 * result list is not an empty corpus — the model still retrieves at answer time, which is exactly
 * what the n = 0 wording offers.
 */
export function askAffordanceLabel(resultCount: number): string {
  return resultCount > 0
    ? `Ask about these ${resultCount}`
    : 'Ask anyway — your files are searched again while answering';
}

/**
 * The reachable reason a capability-gated destination cannot be served right now, or `null` when it
 * can. `degraded` deliberately does NOT read as a reason: a quality caveat is not a block, and
 * dimming an affordance that works would be the same over-claim in the other direction.
 */
function unavailableReason(a: Availability | null): string | null {
  return a !== null && a.kind === 'unavailable' ? a.reason : null;
}

/** Is this element a text entry — i.e. is the user typing into it right now? */
function isTextEntry(el: HTMLElement | null): boolean {
  const tag = el?.tagName;
  return tag === 'INPUT' || tag === 'TEXTAREA';
}

/** Does this window's user want motion suppressed? Unknown (no `matchMedia`) means "no" — the CSS
 * media block is the guarantee either way; this only spares the DOM a class it would ignore. */
function prefersReducedMotion(): boolean {
  if (typeof window === 'undefined' || typeof window.matchMedia !== 'function') return false;
  return window.matchMedia('(prefers-reduced-motion: reduce)').matches;
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
  /**
   * L7 — the deck's compressible bodies, evicted to their minimum honest form. `listCollapsed` is
   * also the user's own toggle; `feedCollapsed` has no toggle because a run's feed is not something
   * the user asked for room for. Both are OUTPUTS of {@link reconcileBoundaries} whenever the window
   * decides, and the toggle is an input to the next reconcile rather than a competing authority.
   */
  private listCollapsed = false;
  private feedCollapsed = false;
  /** True while the user has explicitly collapsed the list, so a reconcile cannot silently reopen it. */
  private listCollapsedByUser = false;
  /**
   * L7/L13 — the deck's user-chosen height. `null` is AUTOMATIC (the deck sizes to its content), and
   * double-clicking the grip returns to it. Deliberately NOT persisted: a height is a per-session
   * shape, unlike a rail width (a preference), per L13's remember/reset asymmetry.
   */
  private deckHeightPx: number | null = null;
  /**
   * L13 — the rails' CHOSEN widths, `null` for automatic: the user's preference, not what is on
   * screen. Unlike the deck's height these are REMEMBERED (`railSizing`'s storage edge): a width is
   * a preference about this window, not a shape of one session's contents.
   *
   * What renders is {@link applied}, which is these clamped against the window as it is right now.
   * Keeping the two apart is the whole of §6c finding 7's fix: a preference narrowed to fit a small
   * window must come back when the window grows, so the clamp may never be written back here.
   */
  private sessionRailPx: number | null = null;
  private documentRailPx: number | null = null;
  /** The reconciled, render-ready boundaries. Recomputed at mount, on resize, and at gesture end. */
  private applied: BoundaryState = {
    sessionRailPx: null,
    documentRailPx: null,
    deckHeightPx: null,
    railCollapsed: false,
    eviction: { listYields: false, feedYields: false },
  };
  /** L13 — the sessions rail below its legible width takes its collapsed strip form. */
  private railCollapsed = false;
  /** The omnibox query trail (L12): open only while the user is choosing from it. */
  private historyOpen = false;
  /** Which trail row the keyboard walk is on; -1 means the input itself still holds focus. */
  private historyCursor = -1;
  /** The shared pinned-search projection — read, never copied. */
  private pins: readonly SearchPin[] = [];
  /** This window's own recents (the queries that ran but were never committed). */
  private trail: readonly string[] = [];
  /** The cited passage the reading pane is landing on — the pane owns the settle. */
  private highlightRange: DocumentLineRange | null = null;
  /** ⌥↑/⌥↓ — which index node the walk is on; -1 means the walk has not started. */
  private indexCursor = -1;
  /** Tempdoc 814 §D6 — is the window below the shared block-axis breakpoint? */
  private shortViewport = false;
  /** The last observed AI state, kept so the escalation affordances can project their availability. */
  private aiSnapshot: AiState | null = null;
  private choreographyTimer: ReturnType<typeof setTimeout> | null = null;
  private unsubscribePins: (() => void) | null = null;
  private unsubscribeSearch: (() => void) | null = null;
  private unsubscribeShortViewport: (() => void) | null = null;
  private unsubscribeAgent: (() => void) | null = null;
  private unsubscribeSessions: (() => void) | null = null;
  private unsubscribeChips: (() => void) | null = null;
  private unsubscribeFacets: (() => void) | null = null;
  private unsubscribeAi: (() => void) | null = null;

  constructor() {
    super();
    this.apiBase = '';
    // L13 — the RESIZE caller of the reconciliation seam. Constructed for its registration: a
    // ReactiveController adds itself to its host, and this one exposes no value to read back (unlike
    // its `adaptiveDensity`/`adaptiveBar` siblings, which the host queries), so holding a reference
    // would only be a field nothing uses.
    new BoundaryReconcilerController(this);
  }

  /**
   * The MOUNT caller: a remembered width meets this window for the first time here. It is explicit
   * rather than left to the controller's first frame because the controller no-ops where
   * `ResizeObserver` is undefined, and restoring a preference must not depend on an optional API.
   */
  protected override firstUpdated(): void {
    this.reconcileBoundaries();
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
    // 814 §D6 — a vertical resize across the breakpoint re-renders the height-gated chrome, and the
    // grip's transcript floor re-derives on the next drag. Fires once immediately.
    this.unsubscribeShortViewport = subscribeShortViewport((short) => {
      this.shortViewport = short;
      this.requestUpdate();
    });
    this.unsubscribePins = subscribePinnedSearches((pins) => {
      this.pins = pins;
      this.requestUpdate();
    });
    // L13 — the rails open at the width this user last chose, restored VERBATIM. It is reconciled
    // against this window in `firstUpdated`, once there is a box to measure: the reader deliberately
    // does not judge a remembered width, because a clamp is a fact about the window on screen and
    // discarding the preference for briefly not fitting would lose it for good (§6c finding 7).
    this.sessionRailPx = readStoredRailWidth('sessions');
    this.documentRailPx = readStoredRailWidth('document');
    this.trail = readTrail();
    this.addEventListener('animationend', this.onChoreographyEnd);
    // The ⌥↑/⌥↓ index walk is a WINDOW-level key, so it listens on the host rather than on any one
    // region — and refuses while a text entry has focus, which is the whole of the prototype's
    // "never while typing".
    this.addEventListener('keydown', this.onWindowKeydown);
    // Subscribing does NOT create the shared controller (the store's `peek` reader is what this
    // window uses until it actually delegates), so merely mounting Search v2 starts no polling.
    this.unsubscribeAgent = subscribeAgentSession(() => this.onAgentUpdate());
    void loadConversations();
  }

  override disconnectedCallback(): void {
    super.disconnectedCallback();
    this.askAbort?.abort();
    this.askAbort = null;
    this.removeEventListener('animationend', this.onChoreographyEnd);
    this.removeEventListener('keydown', this.onWindowKeydown);
    this.endCommitChoreography();
    this.unsubscribePins?.();
    this.unsubscribePins = null;
    this.unsubscribeShortViewport?.();
    this.unsubscribeShortViewport = null;
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
    this.aiSnapshot = s;
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
      // L14 — the wall clock at the run's terminal, captured here (the records module owns no clock)
      // so the receipt's extended form can say when, instead of guessing.
      endedAt: new Date().toISOString(),
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
    this.closeHistory();
    this.beginCommitChoreography();
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

  /**
   * The commit choreography (818 §1) — causal order made visible: the turn rises in, the frozen
   * record lands with an evidence-tinted settle, the deck collapses, the rail and the name follow,
   * and the answer arrives last. The periphery never moves before the record, because the record is
   * what happened; the rest is consequence.
   *
   * The whole sequence is CSS on a transient host class, so it can carry no meaning: nothing on
   * screen says anything different while it runs, and a window that never renders it (reduced
   * motion) is not showing less. Both send paths start it — an ask commit and a delegate are the
   * same act of committing a turn, and a periphery that reordered itself on one but not the other
   * would be teaching two different causal stories.
   */
  private beginCommitChoreography(): void {
    // Reduced motion is honoured twice: the CSS media block is the guarantee (it also covers a
    // preference changed while the class is applied), and this early return keeps the class off the
    // host entirely, so no consumer can read "committing" as a state that only some users enter.
    if (prefersReducedMotion()) return;
    this.classList.add(COMMITTING_CLASS);
    if (this.choreographyTimer !== null) clearTimeout(this.choreographyTimer);
    this.choreographyTimer = setTimeout(() => this.endCommitChoreography(), COMMIT_CHOREOGRAPHY_MS);
  }

  /** Idempotent teardown — reached by whichever of `animationend` / the timer arrives first. */
  private endCommitChoreography(): void {
    if (this.choreographyTimer !== null) {
      clearTimeout(this.choreographyTimer);
      this.choreographyTimer = null;
    }
    this.classList.remove(COMMITTING_CLASS);
  }

  private onChoreographyEnd = (e: Event): void => {
    if ((e as AnimationEvent).animationName === CHOREOGRAPHY_LAST_ANIMATION) {
      this.endCommitChoreography();
    }
  };

  /** The deck element — the boundary's subject, read from the shadow root at interaction time. */
  private deckElement(): HTMLElement | null {
    return (this.shadowRoot?.querySelector('.deck') as HTMLElement | null) ?? null;
  }

  /**
   * L7/L13 — the floor, computed AT DRAG TIME from what the deck is holding right now. It is not a
   * constant because the deck's occupancy is not: the moment a run is live the run controls join the
   * sum, so the boundary the user can drag to stops above them.
   */
  private deckFloorPx(deck: HTMLElement): number {
    return deckFloorFrom(
      collectIncompressibleHeights(deck, (el) => el.getBoundingClientRect().height),
    );
  }

  /**
   * The grip's drag. Pointer capture keeps the gesture on the grip even when the pointer outruns it,
   * and the deck's inline height is written DIRECTLY during the move (not through a re-render): the
   * results card should not re-render on every pointer frame. The chosen height is adopted into view
   * state at the end of the gesture, so a later render keeps it.
   */
  private onGripPointerDown(e: PointerEvent): void {
    const deck = this.deckElement();
    const centre = deck?.parentElement;
    if (!deck || !centre) return;
    e.preventDefault();
    const startY = e.clientY;
    const startHeightPx = deck.getBoundingClientRect().height;
    const availablePx = centre.getBoundingClientRect().height;
    const floorPx = this.deckFloorPx(deck);
    const grip = e.currentTarget as HTMLElement;
    grip.setPointerCapture?.(e.pointerId);
    let height = startHeightPx;
    const move = (ev: PointerEvent): void => {
      height = clampDeckHeight({
        startHeightPx,
        deltaPx: startY - ev.clientY,
        floorPx,
        availablePx,
        transcriptMinPx: transcriptMinPx(this.shortViewport),
      });
      deck.style.flex = `0 0 ${height}px`;
      // L7 — at the floor the list has no room to be a list, so it takes its minimum honest form.
      const yields = listYields(height, floorPx);
      if (yields !== this.listCollapsed) {
        this.listCollapsed = yields;
        this.requestUpdate();
      }
    };
    const up = (): void => {
      grip.removeEventListener('pointermove', move);
      grip.removeEventListener('pointerup', up);
      grip.removeEventListener('pointercancel', up);
      // GESTURE END — the third caller of the one reconciliation seam.
      this.deckHeightPx = height;
      this.reconcileBoundaries();
      this.requestUpdate();
    };
    grip.addEventListener('pointermove', move);
    grip.addEventListener('pointerup', up);
    grip.addEventListener('pointercancel', up);
  }

  /** The keyboard half of the SAME boundary — same clamp, same floor, one nudge at a time. */
  private onGripKeydown(e: KeyboardEvent): void {
    const deck = this.deckElement();
    const centre = deck?.parentElement;
    if (!deck || !centre) return;
    if (e.key === 'Home' || e.key === 'Escape') {
      e.preventDefault();
      this.resetDeckSize();
      return;
    }
    if (e.key !== 'ArrowUp' && e.key !== 'ArrowDown') return;
    e.preventDefault();
    const floorPx = this.deckFloorPx(deck);
    this.deckHeightPx = clampDeckHeight({
      startHeightPx: deck.getBoundingClientRect().height,
      deltaPx: e.key === 'ArrowUp' ? DECK_KEY_STEP_PX : -DECK_KEY_STEP_PX,
      floorPx,
      availablePx: centre.getBoundingClientRect().height,
      transcriptMinPx: transcriptMinPx(this.shortViewport),
    });
    this.reconcileBoundaries();
    this.requestUpdate();
  }

  /** L13 — double-click returns the boundary to automatic: the height was a choice, not a setting. */
  private resetDeckSize(): void {
    const deck = this.deckElement();
    if (deck) deck.style.removeProperty('flex');
    this.deckHeightPx = null;
    this.listCollapsedByUser = false;
    this.reconcileBoundaries();
    this.requestUpdate();
  }

  /** The element a horizontal boundary sizes. Read at interaction time, like the deck's. */
  private railElement(rail: RailId): HTMLElement | null {
    const selector = rail === 'sessions' ? '.rail' : '.reading';
    return (this.shadowRoot?.querySelector(selector) as HTMLElement | null) ?? null;
  }

  private railWidthPx(rail: RailId): number | null {
    return rail === 'sessions' ? this.sessionRailPx : this.documentRailPx;
  }

  /** The horizontal track — the box whose size decides every boundary (the controller observes it). */
  boundaryBoxElement(): HTMLElement | null {
    return (this.shadowRoot?.querySelector('.win') as HTMLElement | null) ?? null;
  }

  /**
   * The width the three regions actually share: the track minus its chrome. The grips sit in the
   * row and the track carries a gap between every pair of children, so a ceiling computed against
   * the raw track width is generous by exactly that chrome — which is enough to let the centre
   * column land under its own floor while every clamp agrees it is fine (§6c finding 13b).
   */
  private trackAvailableWidthPx(): number {
    const win = this.boundaryBoxElement();
    if (!win) return 0;
    const grips = [...(this.shadowRoot?.querySelectorAll('button.vgrip') ?? [])];
    const gripsPx = grips.reduce((sum, g) => sum + g.getBoundingClientRect().width, 0);
    const gapPx = Number.parseFloat(getComputedStyle(win).columnGap || '0') || 0;
    // One gap between each adjacent pair of flex children.
    const childCount = win.children.length;
    const gapsPx = childCount > 1 ? gapPx * (childCount - 1) : 0;
    return win.getBoundingClientRect().width - gripsPx - gapsPx;
  }

  /**
   * L13 — the ONE reconciliation call. Its three callers are the whole lifecycle of a boundary:
   * MOUNT (a remembered width meets this window for the first time), RESIZE (the window moved under
   * boundaries that were legal a moment ago) and GESTURE END (a new choice). §6c findings 3 and 7
   * were both "the clamp existed but only a gesture ever ran it"; routing all three through one
   * entry point is what makes that state unrepresentable rather than merely fixed.
   */
  reconcileBoundaries(): void {
    const centre = this.shadowRoot?.querySelector('.centre') as HTMLElement | null;
    const deck = this.deckElement();
    if (!this.boundaryBoxElement() || !centre) return;
    const next = reconcileBoundaries({
      availableWidthPx: this.trackAvailableWidthPx(),
      availableHeightPx: centre.getBoundingClientRect().height,
      sessionRailChosenPx: this.sessionRailPx,
      documentRailChosenPx: this.documentRailPx,
      documentOpen: this.readingDocPath !== null,
      deckChosenPx: this.deckHeightPx,
      deckFloorPx: deck ? this.deckFloorPx(deck) : 0,
      shortViewport: this.shortViewport,
      hasList: true,
      hasFeed: this.runOwned,
    });
    this.applyBoundaryState(next);
  }

  /** Adopt a reconciled state, re-rendering only when something actually moved. */
  private applyBoundaryState(next: BoundaryState): void {
    const prev = this.applied;
    // The user's own collapse survives reconciliation: eviction may TAKE the list's rows away, but
    // it may not hand them back to someone who put them away themselves.
    const listCollapsed = this.listCollapsedByUser || next.eviction.listYields;
    const changed =
      prev.sessionRailPx !== next.sessionRailPx ||
      prev.documentRailPx !== next.documentRailPx ||
      prev.deckHeightPx !== next.deckHeightPx ||
      prev.railCollapsed !== next.railCollapsed ||
      prev.eviction.listYields !== next.eviction.listYields ||
      prev.eviction.feedYields !== next.eviction.feedYields ||
      this.listCollapsed !== listCollapsed ||
      this.feedCollapsed !== next.eviction.feedYields;
    this.applied = next;
    this.railCollapsed = next.railCollapsed;
    this.listCollapsed = listCollapsed;
    this.feedCollapsed = next.eviction.feedYields;
    if (changed) this.requestUpdate();
  }

  /**
   * L13 — a rail's clamps for a GESTURE, computed from what the window is currently holding. The
   * ceiling is the OTHER side's minimum honest form: whatever leaves the centre column its reading
   * floor beside the region on the far side, which is why opening the document pane tightens what
   * the sessions rail may take without either boundary knowing about the other.
   */
  private railBounds(rail: RailId): { floorPx: number; ceilingPx: number } | null {
    if (!this.boundaryBoxElement()) return null;
    const availablePx = this.trackAvailableWidthPx();
    const sessionsPx = this.railElement('sessions')?.getBoundingClientRect().width ?? 0;
    const documentPx = this.railElement('document')?.getBoundingClientRect().width ?? 0;
    return {
      floorPx: railFloorPx(rail),
      ceilingPx:
        rail === 'sessions'
          ? sessionRailCeiling(availablePx, documentPx)
          : documentRailCeiling(availablePx, sessionsPx),
    };
  }

  /** The width a gesture starts from: the chosen width, else what is on screen, else automatic. */
  private railStartWidthPx(rail: RailId): number {
    const chosen = this.railWidthPx(rail);
    if (chosen !== null) return chosen;
    const measured = this.railElement(rail)?.getBoundingClientRect().width ?? 0;
    return measured > 0 ? measured : railDefaultPx(rail);
  }

  /**
   * The horizontal grip's drag — the same gesture as the deck's, one axis over. The rail's inline
   * width is written DIRECTLY during the move (the rail's rows and the document pane must not
   * re-render on every pointer frame); the chosen width is adopted, and remembered, at the end.
   */
  private onRailPointerDown(e: PointerEvent, rail: RailId): void {
    const el = this.railElement(rail);
    const bounds = this.railBounds(rail);
    if (!el || !bounds) return;
    e.preventDefault();
    const startX = e.clientX;
    const startWidthPx = this.railStartWidthPx(rail);
    // The sessions rail grows rightward; the document region grows leftward. One clamp, two signs.
    const grow = rail === 'sessions' ? 1 : -1;
    const grip = e.currentTarget as HTMLElement;
    grip.setPointerCapture?.(e.pointerId);
    let width = startWidthPx;
    const move = (ev: PointerEvent): void => {
      width = clampRailWidth({ ...bounds, startWidthPx, deltaPx: (ev.clientX - startX) * grow });
      el.style.flex = `0 0 ${width}px`;
      if (rail === 'sessions') this.syncRailCollapsed(width);
      // The gesture is live, so it is the one place the applied width is the gesture's own — keeping
      // `applied` in step means a render mid-drag does not fight the inline style being written here.
      if (rail === 'sessions') this.applied = { ...this.applied, sessionRailPx: width };
      else this.applied = { ...this.applied, documentRailPx: width };
    };
    const up = (): void => {
      grip.removeEventListener('pointermove', move);
      grip.removeEventListener('pointerup', up);
      grip.removeEventListener('pointercancel', up);
      this.adoptRailWidth(rail, width);
    };
    grip.addEventListener('pointermove', move);
    grip.addEventListener('pointerup', up);
    grip.addEventListener('pointercancel', up);
  }

  /** The keyboard half of the SAME boundary — same clamp, same floor, one nudge at a time. */
  private onRailKeydown(e: KeyboardEvent, rail: RailId): void {
    if (e.key === 'Home' || e.key === 'Escape') {
      e.preventDefault();
      this.resetRailSize(rail);
      return;
    }
    if (e.key !== 'ArrowLeft' && e.key !== 'ArrowRight') return;
    const bounds = this.railBounds(rail);
    if (!bounds) return;
    e.preventDefault();
    const grow = rail === 'sessions' ? 1 : -1;
    const width = clampRailWidth({
      ...bounds,
      startWidthPx: this.railStartWidthPx(rail),
      deltaPx: (e.key === 'ArrowRight' ? RAIL_KEY_STEP_PX : -RAIL_KEY_STEP_PX) * grow,
    });
    this.adoptRailWidth(rail, width);
  }

  /**
   * L13 — a chosen width is adopted into view state AND remembered; the two are one act. What is
   * stored is the user's CHOICE; what renders is that choice reconciled against the window, which
   * is why this ends at the seam rather than writing an applied width straight to the DOM.
   */
  private adoptRailWidth(rail: RailId, px: number): void {
    if (rail === 'sessions') this.sessionRailPx = px;
    else this.documentRailPx = px;
    storeRailWidth(rail, px);
    // GESTURE END — the third caller of the one reconciliation seam.
    this.reconcileBoundaries();
    this.requestUpdate();
  }

  private syncRailCollapsed(widthPx: number): void {
    const yields = railYields(widthPx);
    if (yields !== this.railCollapsed) {
      this.railCollapsed = yields;
      this.requestUpdate();
    }
  }

  /**
   * L13 — back to automatic, and the memory goes with it. A width the user withdrew must not come
   * back at the next mount: "automatic" is a choice too, and a remembered width would overrule it.
   */
  private resetRailSize(rail: RailId): void {
    this.railElement(rail)?.style.removeProperty('flex');
    if (rail === 'sessions') this.sessionRailPx = null;
    else this.documentRailPx = null;
    forgetRailWidth(rail);
    this.reconcileBoundaries();
    this.requestUpdate();
  }

  /**
   * The window's own keys. ⌥↑/⌥↓ walks the session index and brings the matching record into view —
   * and refuses outright while a text entry has focus, because a modifier chord that steals the
   * caret's line-movement is worse than no shortcut (the prototype's "never while typing").
   */
  private onWindowKeydown = (e: KeyboardEvent): void => {
    if (!e.altKey || (e.key !== 'ArrowUp' && e.key !== 'ArrowDown')) return;
    if (this.typingSomewhere(e)) return;
    const nodes = projectIndex(this.records, Date.now()).nodes;
    if (nodes.length === 0) return;
    e.preventDefault();
    const step = e.key === 'ArrowDown' ? 1 : -1;
    const from = this.indexCursor < 0 ? (step === 1 ? -1 : nodes.length) : this.indexCursor;
    const next = Math.min(nodes.length - 1, Math.max(0, from + step));
    this.selectIndexNode(next);
  };

  /** Is a text entry holding focus? Checked on the composed path AND on the shadow root's own
   * active element — a key event dispatched at the host retargets, and the caret is what matters. */
  private typingSomewhere(e: KeyboardEvent): boolean {
    const path = typeof e.composedPath === 'function' ? e.composedPath() : [];
    const first = (path[0] ?? e.target) as HTMLElement | null;
    return isTextEntry(first) || isTextEntry(this.shadowRoot?.activeElement as HTMLElement | null);
  }

  /**
   * The index walk's landing: the node becomes the selected one and the record it stands for is
   * brought into view. The transcript is the authority for what is shown, so the walk scrolls it —
   * it never re-renders a record inside the rail (L12: the rail does not yield item-by-item).
   */
  private selectIndexNode(index: number): void {
    const nodes = projectIndex(this.records, Date.now()).nodes;
    const node = nodes[index];
    if (!node) return;
    this.indexCursor = index;
    this.requestUpdate();
    const recordId = node.recordIds[0] ?? node.id;
    const target = this.shadowRoot?.querySelector(`[data-record-id="${recordId}"]`);
    if (target && typeof target.scrollIntoView === 'function') {
      target.scrollIntoView({
        block: 'center',
        behavior: prefersReducedMotion() ? 'auto' : 'smooth',
      });
    }
  }

  /**
   * The citation landing. The shared panel's own `citation-select` is the source — this window adds
   * no citation affordance of its own — and the pane's `highlightRange` is the cited line span, so
   * the land-strong-then-settle is the PANE's own decay (`DocumentPane`'s `HIGHLIGHT_DECAY_MS`,
   * which already lands quiet under reduced motion). A second emphasis here would be a fork of an
   * authority that already exists.
   */
  private onCitationSelect(detail: CitationSelectDetail): void {
    if (!detail?.parentDocId) return;
    this.readingDocPath = detail.parentDocId;
    this.highlightRange =
      Number.isFinite(detail.startLine) && Number.isFinite(detail.endLine)
        ? { startLine: detail.startLine, endLine: detail.endLine }
        : null;
    this.requestUpdate();
  }

  /** The trail's rows, filtered by whatever is in the draft (an empty draft filters nothing). */
  private trailRows(): { pinned: readonly string[]; recent: readonly string[] } {
    const committed = this.records
      .filter((r): r is FrozenSearchRecord => r.kind === 'frozen-search')
      .map((r) => r.query)
      .reverse();
    return {
      pinned: filterTrail(
        this.pins.map((p) => p.query),
        this.draft,
      ),
      recent: filterTrail(mergeRecents(committed, this.trail), this.draft),
    };
  }

  private trailFlat(): readonly string[] {
    const rows = this.trailRows();
    return [...rows.pinned, ...rows.recent];
  }

  /** The trail opens on the focus of an EMPTY draft — a draft in progress is not a history search. */
  private onDraftFocus(): void {
    if (this.draft.trim()) return;
    this.historyOpen = true;
    this.historyCursor = -1;
    this.requestUpdate();
  }

  private closeHistory(): void {
    if (!this.historyOpen) return;
    this.historyOpen = false;
    this.historyCursor = -1;
    this.requestUpdate();
  }

  /**
   * Picking a trail row FILLS the draft and runs the live search. It never commits: a committed
   * record is the user's act of saying "this is the set", and a history row is a shortcut to a
   * query, not to a commitment (L4/L8).
   */
  private runTrailQuery(query: string): void {
    this.draft = query;
    this.historyOpen = false;
    this.historyCursor = -1;
    setQuery(query);
    submitSearch();
    this.trail = recordSubmittedQuery(query);
    this.requestUpdate();
    const input = this.shadowRoot?.querySelector('[data-testid="draft"]') as HTMLInputElement | null;
    input?.focus();
  }

  /** Move the walk's cursor and take the focus with it — the selection IS where the focus is. */
  private moveTrailCursor(index: number): void {
    this.historyCursor = index;
    this.requestUpdate();
    void this.updateComplete.then(() => {
      const row = this.shadowRoot?.querySelector(
        `[data-testid="trail-row"][data-index="${index}"]`,
      ) as HTMLElement | null;
      row?.focus();
    });
  }

  /** The keyboard walk into the trail: ↓ enters the list, ↑/↓ move, Enter picks, Escape returns. */
  private onTrailKeydown(e: KeyboardEvent): void {
    const rows = this.trailFlat();
    if (e.key === 'Escape') {
      e.preventDefault();
      this.closeHistory();
      (this.shadowRoot?.querySelector('[data-testid="draft"]') as HTMLInputElement | null)?.focus();
      return;
    }
    if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
      e.preventDefault();
      const step = e.key === 'ArrowDown' ? 1 : -1;
      this.moveTrailCursor(Math.min(rows.length - 1, Math.max(0, this.historyCursor + step)));
      return;
    }
    if (e.key === 'Enter') {
      e.preventDefault();
      const picked = rows[this.historyCursor];
      if (picked) this.runTrailQuery(picked);
    }
  }

  static styles = [
    surfaceLayoutStyles,
    css`
      :host {
        color: var(--text-primary);
      }
      /* The shared surface layout scrolls the .body region; here the regions inside it (rail,
         transcript, list, feed, reading pane) own their own scrolling, so leaving .body scrollable
         too would wrap every one of them in a second, outer scroller.

         .body is the SCROLL-POLICY region and .win is the horizontal TRACK, and they must stay
         different elements. Carrying both classes on one node put this rule's flex-direction column
         on the track — where nothing contested it, because .win never declares a direction — and the
         window silently rendered its three regions stacked instead of side by side for two slices.
         Guarded by the axis + node-identity witnesses in the presentation suite. */
      .body {
        overflow: hidden;
        display: flex;
        flex-direction: column;
      }
      .win {
        display: flex;
        flex: 1 1 auto;
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
        min-height: 0;
        overflow-y: auto;
      }
      .centre {
        flex: 1;
        min-width: 0;
        min-height: 0;
        display: flex;
        flex-direction: column;
        gap: var(--density-inner-pad-y);
        overflow: hidden;
      }
      /* 814 §D1 — the priority region: the transcript takes the centre column's slack and owns the
         only scroller between the two, so accreting chrome costs the DECK, never the reading. */
      .transcript {
        flex: 1 1 auto;
        min-height: 0;
        overflow-y: auto;
      }
      .deck {
        flex: 0 0 auto;
        min-height: 0;
      }
      /* Before the first commit there is no transcript to protect, so the deck is the column. */
      .deck.fills {
        flex: 1 1 auto;
      }
      /* Once the user has SIZED the deck, the list body spends whatever the fixed height leaves,
         instead of holding the automatic cap and leaving the extra room blank. */
      .deck.sized > .list {
        max-height: none;
        flex: 1 1 auto;
      }
      /* L7/L13 — the ONE movable boundary, drawn as movable rather than as a rule. A native button,
         so the keyboard half of the boundary (↑/↓ resize, Home for automatic) comes for free. */
      button.grip {
        align-self: stretch;
        height: 0.6rem;
        padding: 0;
        border: 0;
        background: transparent;
        cursor: row-resize;
        display: flex;
        align-items: center;
        justify-content: center;
        touch-action: none;
      }
      button.grip::after {
        content: '';
        width: 2.5rem;
        height: 3px;
        border-radius: 1.5px;
        background: var(--border-subtle);
      }
      button.grip:hover,
      button.grip:focus-visible {
        background: transparent;
      }
      button.grip:hover::after,
      button.grip:focus-visible::after {
        width: 5.75rem;
        background: var(--border-strong);
      }
      .reading {
        flex: 0 0 24rem;
        min-width: 0;
        border-left: 1px solid var(--border-subtle);
        padding-left: var(--density-inner-pad-x);
        overflow-y: auto;
      }
      /* L13 — the horizontal twin of the deck's grip, and deliberately the same construction: a
         native button, so the boundary is keyboard-operable (←/→ resize, Home returns to automatic)
         without a hand-rolled role/tabindex triad. */
      button.vgrip {
        flex: 0 0 0.6rem;
        align-self: stretch;
        padding: 0;
        border: 0;
        background: transparent;
        cursor: col-resize;
        display: flex;
        align-items: center;
        justify-content: center;
        touch-action: none;
      }
      button.vgrip::after {
        content: '';
        width: 3px;
        height: 2.5rem;
        border-radius: 1.5px;
        background: var(--border-subtle);
      }
      button.vgrip:hover,
      button.vgrip:focus-visible {
        background: transparent;
      }
      button.vgrip:hover::after,
      button.vgrip:focus-visible::after {
        height: 5.75rem;
        background: var(--border-strong);
      }
      /* L13 — the rail's minimum honest form: at its floor it is a strip with the one affordance
         that undoes the choice, plus the count it would otherwise be showing. */
      .strip {
        display: flex;
        flex-direction: column;
        align-items: center;
        gap: 0.35rem;
      }
      /* ── L14: the ONE extension mechanism ──────────────────────────────────────────────────
         An .ext rests visually collapsed but STAYS in the accessibility tree — the same clip
         pattern ambientStyles' .visually-hidden uses (JfElement adopts it into every root), so a
         screen reader reads the elaboration at rest while the visual surface keeps its identifying
         minimum. Its .ext-row reveals it on hover AND on :focus-within: focus parity is not
         optional, because a hover-only elaboration is unreachable from a keyboard. One convention,
         every region — the rule cannot fork per surface. No transition: the reveal carries no
         meaning, so there is nothing to animate and nothing for reduced motion to suppress. */
      .ext {
        position: absolute;
        width: 1px;
        height: 1px;
        padding: 0;
        margin: -1px;
        overflow: hidden;
        clip: rect(0 0 0 0);
        clip-path: inset(50%);
        white-space: nowrap;
        border: 0;
      }
      .ext-row:hover .ext,
      .ext-row:focus-within .ext {
        position: static;
        width: auto;
        height: auto;
        margin: 0;
        overflow: visible;
        clip: auto;
        clip-path: none;
        white-space: normal;
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
      /* Divergence 1 of the copied sidebar convention: rows WRAP, never truncate. */
      .rowlist li,
      button.node {
        white-space: normal;
      }
      /* An index node is a real affordance — it jumps the transcript to the record it stands for —
         so it is a native button and reaches the keyboard for free. */
      button.node {
        display: flex;
        gap: 0.4rem;
        align-items: baseline;
        width: 100%;
        background: transparent;
        border-color: transparent;
        padding: 0.2rem 0.3rem;
      }
      button.node[aria-current='true'] {
        border-color: var(--border-strong);
        background: var(--surface-3);
      }
      /* The trail — omnibox history, in the input band where history belongs (L12). */
      .qhist {
        display: flex;
        flex-direction: column;
        border: 1px solid var(--border-subtle);
        border-radius: 0.4rem;
        background: var(--surface-2);
        overflow: hidden;
      }
      .qhist button {
        background: transparent;
        border: 0;
        border-radius: 0;
        display: flex;
        gap: 0.5rem;
        align-items: baseline;
      }
      .qhist h2 {
        padding: 0.3rem var(--density-inner-pad-x) 0.1rem;
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
      /* The AI-dependent rungs when the model cannot answer. Distinct from .off (the L10 empty-draft
         preview): that says "nothing to send", this says "this destination is not reachable right
         now" — and the reason rides the same element's title, never a suppressed one. SEARCH carries
         neither, because the floor never degrades. */
      .rung-pill.unavailable {
        color: var(--text-muted);
        background: transparent;
        border-style: dashed;
      }
      button[data-unavailable='true'] {
        color: var(--text-muted);
        border-style: dashed;
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
      /* The honest empty (818 §6b): a direction-giving line, never a fabricated row. The count
         itself stays the card's — this line explains, it does not count. */
      .none-left {
        margin: 0;
        font-size: var(--font-size-sm);
        color: var(--text-secondary);
        padding: var(--density-inner-pad-y) 0;
      }

      /* ── the commit choreography (818 §1) ──────────────────────────────────────────────────
         Causal order made visible: the turn rises in, the record lands with an evidence-tinted
         settle, the deck follows, then the rail and the name, and the answer last. Timing ported
         from the prototype (818-prototype/index3.html:26-42), selectors adapted to this window's
         regions. The periphery must never move before the record. */
      @keyframes sv2-cm-rise {
        from {
          opacity: 0;
          transform: translateY(10px);
        }
        to {
          opacity: 1;
          transform: none;
        }
      }
      @keyframes sv2-cm-land {
        0% {
          opacity: 0;
          transform: translateY(-6px);
          box-shadow: 0 0 0 3px var(--accent-tint-45);
        }
        55% {
          opacity: 1;
          box-shadow: 0 0 0 3px var(--accent-tint-45);
        }
        100% {
          opacity: 1;
          transform: none;
          box-shadow: 0 0 0 0 transparent;
        }
      }
      @keyframes sv2-cm-fade {
        from {
          opacity: 0;
        }
        to {
          opacity: 1;
        }
      }
      /* The LAST animation of the sequence carries its own name, so the teardown can end the
         choreography on the real end of it rather than on whichever animation finishes first. */
      @keyframes sv2-cm-answer {
        from {
          opacity: 0;
        }
        to {
          opacity: 1;
        }
      }
      :host(.committing) .turn {
        animation: sv2-cm-rise 0.2s ease both;
      }
      :host(.committing) .frozen {
        animation: sv2-cm-land 0.5s ease 0.12s both;
      }
      :host(.committing) .deck {
        animation: sv2-cm-fade 0.25s ease 0.3s both;
      }
      :host(.committing) .rail,
      :host(.committing) .name {
        animation: sv2-cm-fade 0.3s ease 0.4s both;
      }
      :host(.committing) .answer,
      :host(.committing) .pending {
        animation: sv2-cm-answer 0.3s ease 0.55s both;
      }
      /* Reduced motion removes the whole sequence, not a softened version of it: the choreography
         carries no information, so a window that renders the committed state instantly is showing
         exactly the same facts. */
      @media (prefers-reduced-motion: reduce) {
        :host(.committing) .turn,
        :host(.committing) .frozen,
        :host(.committing) .deck,
        :host(.committing) .rail,
        :host(.committing) .name,
        :host(.committing) .answer,
        :host(.committing) .pending {
          animation: none;
        }
      }

      /* Tempdoc 814 §D6 — below the shared block-axis breakpoint the CHROME yields and the
         transcript does not: the two deck bodies take shorter caps, and the not-yet-built material
         rail placeholder (an announcement, not a fact of this session) stands down entirely. */
      ${shortViewportMedia} {
        .list {
          max-height: 12rem;
        }
        .feed {
          max-height: 10rem;
        }
        .placeholders {
          display: none;
        }
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
    // ↓ from the input steps into the trail — the list is a continuation of the field, not a
    // separate destination the user has to Tab to.
    if (e.key === 'ArrowDown' && this.historyOpen && this.trailFlat().length > 0) {
      e.preventDefault();
      this.moveTrailCursor(0);
      return;
    }
    // The Escape ORDER, one layer per press, outermost first: the query trail is on top of the
    // window, the document pane is beside it, and the ⇥ flip is the innermost thing a draft holds.
    // Collapsing them into one press would throw away two states the user did not ask to leave.
    if (e.key === 'Escape') {
      e.preventDefault();
      if (this.historyOpen) {
        this.closeHistory();
        return;
      }
      if (this.readingDocPath) {
        this.closeReadingPane();
        return;
      }
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
      if (this.draft.trim()) {
        this.closeHistory();
        submitSearch();
        // The trail's own source: a query the user SUBMITTED. Nothing typed-and-abandoned enters it.
        this.trail = recordSubmittedQuery(this.draft);
      }
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
    this.closeHistory();
    this.beginCommitChoreography();
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
    this.highlightRange = null;
    this.historyOpen = false;
    this.historyCursor = -1;
    this.indexCursor = -1;
    this.lockRefused = false;
    this.contextPromptTokens = null;
    // A live run is backend-owned and keeps going; this window simply stops hosting it, so its
    // receipt cannot land in a session that no longer holds the turn that started it. Halting is a
    // decision with its own control — never a side effect of leaving a session.
    this.runOwned = false;
    this.haltRequested = false;
    this.steerDraft = '';
    this.listCollapsed = false;
    // L13 — the deck RESETS (a height is a per-session shape, not a preference): a new session opens
    // with the automatic deck, which is the honest default for a session with nothing in it yet.
    this.deckHeightPx = null;
    this.deckElement()?.style.removeProperty('flex');
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
    // Opening a whole result is not a landing on a passage: no range, so the pane shows no emphasis
    // it cannot justify.
    this.highlightRange = null;
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
    this.highlightRange = null;
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
      <div class="body">
        <div class="win">
          <nav
            class="rail"
            data-testid="rail"
            aria-label="Sessions"
            style=${this.applied.sessionRailPx !== null
              ? `flex: 0 0 ${this.applied.sessionRailPx}px`
              : nothing}
          >
            ${this.railCollapsed
              ? this.railStrip()
              : this.records.length === 0
                ? this.sidebar()
                : this.sessionIndex()}
          </nav>
          ${this.railGrip('sessions')}
          <div class="centre">
            ${this.transcript()} ${this.deck()} ${this.placeholders()}
          </div>
          ${this.readingDocPath ? this.railGrip('document') : nothing} ${this.readingPane()}
        </div>
      </div>
    `;
  }

  /**
   * L13 — a rail's movable boundary. Same construction as the deck's grip (a native button, so the
   * keyboard half comes for free), same three gestures: drag, arrow-key nudge, and a double-click
   * that returns the boundary to automatic AND forgets the remembered width.
   */
  private railGrip(rail: RailId): TemplateResult {
    const label =
      rail === 'sessions'
        ? 'Resize the sessions list — arrow keys resize, Home returns to automatic'
        : 'Resize the document panel — arrow keys resize, Home returns to automatic';
    return html`<button
      type="button"
      class="vgrip"
      data-testid=${rail === 'sessions' ? 'rail-grip' : 'document-grip'}
      aria-label=${label}
      @pointerdown=${(e: PointerEvent) => this.onRailPointerDown(e, rail)}
      @keydown=${(e: KeyboardEvent) => this.onRailKeydown(e, rail)}
      @dblclick=${() => this.resetRailSize(rail)}
    ></button>`;
  }

  /**
   * The sessions rail at its floor: its minimum honest form. It keeps the ONE affordance that undoes
   * the choice and the count it would otherwise be showing — narrowing a region may cost its rows,
   * it may not cost the fact that they are there.
   */
  private railStrip(): TemplateResult {
    const count =
      this.records.length === 0
        ? this.sessions.length
        : projectIndex(this.records, Date.now()).headerCount;
    const noun = this.records.length === 0 ? 'earlier sessions' : 'entries';
    return html`
      <div class="strip" data-testid="rail-strip">
        <button
          type="button"
          class="quiet"
          data-testid="rail-expand"
          aria-label="Widen the sessions list"
          @click=${() => this.resetRailSize('sessions')}
        >›</button>
        ${/* The unit rides in the shared `.visually-hidden` utility (`ambientStyles`) rather than an
              `aria-label` on a span, which has no role to carry one: the number is what the strip has
              room for, the word is what a screen reader needs. */ ''}
        <span class="count" data-testid="rail-strip-count"
          >${count}<span class="visually-hidden"> ${noun}</span></span
        >
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
                  ${/* L14 — the row RESTS at its identifying minimum (the title, which is what the
                        user recognises it by) and its meta extends. The row is focusable so the
                        elaboration is reachable from the keyboard as well as the pointer; it is
                        deliberately not a button yet, because opening a prior session is not a thing
                        this window can do until it can load one. */ ''}
                  ${b.rows.map(
                    (s) => html`<li
                      class="ext-row"
                      tabindex="0"
                      data-testid="session-row"
                      data-session-id=${s.id}
                    >
                      ${s.label}
                      <span class="count ext" data-testid="session-row-meta"
                        >${messageCountLabel(s.messageCount)} · ${b.label}</span
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
    const index = projectIndex(this.records, Date.now());
    return html`
      <div class="stack" data-testid="rail-index">
        <button type="button" data-testid="rail-back" @click=${this.clearRecords}>
          ‹ All sessions
        </button>
        <h2>This session</h2>
        <p class="count" data-testid="index-count">${index.headerCount} entries</p>
        <ul class="rowlist">
          ${/* L14 — a node rests as what it IS (its label and the size of the cluster it stands
                for, both honesty facts) and elaborates into the detail of the record that opened
                it. The node is a button because it does something: it jumps the transcript. */ ''}
          ${index.nodes.map(
            (n, i) => html`<li class="ext-row" data-node-id=${n.id}>
              <button
                type="button"
                class="node"
                data-testid="index-node"
                data-node-id=${n.id}
                aria-current=${this.indexCursor === i ? 'true' : nothing}
                @click=${() => this.selectIndexNode(i)}
              >
                <span>${n.label}</span> <span class="count">${n.size}</span>
                ${n.detail
                  ? html`<span class="count ext" data-testid="index-node-detail">${n.detail}</span>`
                  : nothing}
              </button>
            </li>`,
          )}
        </ul>
        <p class="count" data-testid="index-foot">⌥↑ ⌥↓ — never while you are typing</p>
      </div>
    `;
  }

  /** The reading stage — mounted only while a document is open; `pane-close` is its only exit. */
  private readingPane(): TemplateResult | typeof nothing {
    if (!this.readingDocPath) return nothing;
    return html`
      <aside
        class="reading"
        data-testid="reading-pane"
        aria-label="Document"
        style=${this.applied.documentRailPx !== null
          ? `flex: 0 0 ${this.applied.documentRailPx}px`
          : nothing}
      >
        <jf-document-pane
          .docPath=${this.readingDocPath}
          .highlightRange=${this.highlightRange}
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
      <section class="stack transcript" data-testid="transcript" data-scrollable="true">
        ${items.map((item) => this.transcriptItem(item))}
      </section>
    `;
  }

  private transcriptItem(item: TranscriptItem): TemplateResult {
    if (item.kind === 'frozen-search') return this.frozenBlock(item);
    if (item.kind === 'user-turn') {
      return html`<p class="turn" data-testid="turn" data-record-id=${item.id}>${item.text}</p>`;
    }
    if (item.kind === 'answer') return this.answerBlock(item);
    if (item.kind === 'agent-run') return this.runReceipt(item);
    if (item.kind === 'refused-answer') {
      return html`<p
        class="pending"
        data-testid="refused-answer"
        data-record-id=${item.id}
        data-reason=${item.reason}
      >
        ${item.label}
      </p>`;
    }
    // The slot is still open: while the stream runs, the accumulating text lives in VIEW state, so
    // no partial answer is ever written into the records array (L4).
    const streamingText = this.streaming?.id === item.id ? this.streaming.text : '';
    return streamingText
      ? html`<p class="answer-text" data-testid="streaming-answer" data-record-id=${item.id}>
          ${streamingText}
        </p>`
      : html`<p class="pending" data-testid="pending-answer" data-record-id=${item.id}>
          ${item.label}
        </p>`;
  }

  /**
   * L8 — the delegated run's receipt in the transcript. One line, entirely derived: the label comes
   * from `runSummaryLabel` over the record's own counts, so the transcript's account of a run cannot
   * drift from the numbers the record carries.
   */
  private runReceipt(item: TranscriptRunItem): TemplateResult {
    return html`<p
      class="turn ext-row"
      data-testid="agent-run"
      data-record-id=${item.id}
      data-outcome=${item.outcome}
      tabindex="0"
    >
      ${item.label}
      ${/* L14 — the outcome and the counts REST (they are what the receipt is); when the run ended
            extends beside them. */ ''}
      ${item.endedAt
        ? html`<span class="count ext" data-testid="agent-run-timing"
            >${formatRelative(new Date(item.endedAt).getTime())}</span
          >`
        : nothing}
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
    const timing = frozenTimingLabel(item.mode, item.tookMs);
    return html`
      ${/* No `tabindex` here, unlike the receipt and the sidebar row: the card inside this block
            already holds focusable controls, and `:focus-within` crosses the shadow boundary — so
            tabbing into the card reveals the elaboration without this wrapper adding a tab stop of
            its own. Focus parity comes from the focus that is already there. */ ''}
      <div class="frozen ext-row" data-testid="frozen-block" data-record-id=${item.id}>
        ${/* L14 — the card's header already states the query, the counts, the retrieval mode and
              when it ran, and every one of those rests visible. What extends is only what the
              header does NOT carry: how the pass ran and how long it took. */ ''}
        ${timing
          ? html`<span class="count ext" data-testid="frozen-timing">${timing}</span>`
          : nothing}
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
        ${/* The citation-follow landing: the panel already emits `citation-select` with the cited
              doc and its line span, so following a source needs no affordance of this window's own —
              it needs the pane mounted at the range the panel names. */ ''}
        ${item.citations.length > 0 || sources.length > 0
          ? html`<jf-citations-panel
              data-testid="citations"
              .citations=${[...item.citations]}
              .sources=${sources}
              .retrievalMode=${item.retrievalMode ?? ''}
              @citation-select=${(e: CustomEvent<CitationSelectDetail>) =>
                this.onCitationSelect(e.detail)}
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
    const askLabel = askAffordanceLabel(results.length);
    const ask = this.escalationAvailability('documents');
    const agent = this.escalationAvailability('agent');
    const askReason = unavailableReason(ask);
    const agentReason = unavailableReason(agent);
    return html`
      <section
        class="stack deck ${projectTranscript(this.records).length === 0 ? 'fills' : ''} ${this
          .deckHeightPx !== null
          ? 'sized'
          : ''}"
        data-testid="deck"
        style=${this.applied.deckHeightPx !== null
          ? `flex: 0 0 ${this.applied.deckHeightPx}px`
          : nothing}
      >
        ${this.deckGrip()} ${this.scopeChips()}
        <div class="band" data-testid="input-band">
          <input
            type="text"
            data-testid="draft"
            aria-label="Search or ask about your files"
            placeholder="Search your files…"
            autocomplete="off"
            aria-describedby=${this.historyOpen ? 'sv2-query-trail' : nothing}
            .value=${this.draft}
            @focus=${this.onDraftFocus}
            @input=${this.onInput}
            @keydown=${this.onKeydown}
          />
          ${/* The rung pills carry the degraded truth too: a destination the model cannot serve reads
                as unavailable WITH its reason, rather than promising a rung that would fail on
                arrival. SEARCH is never marked — it is the floor, and the floor does not degrade. */ ''}
          <span
            class="rung-pill ${dimmed ? 'off' : ''} ${this.flipped && !dimmed
              ? 'flip'
              : ''} ${unavailableReason(this.rungAvailability(primary)) ? 'unavailable' : ''}"
            data-testid="pill"
            data-dimmed=${String(dimmed)}
            data-unavailable=${String(unavailableReason(this.rungAvailability(primary)) !== null)}
            title=${unavailableReason(this.rungAvailability(primary)) ??
            (dimmed
              ? 'Nothing to send yet — type to see where this goes'
              : RUNGS[primary].label)}
            >${this.flipped && !dimmed ? '⇥ ' : ''}${RUNGS[primary].pill} ⏎</span
          >
          <span
            class="rung-pill alt ${dimmed ? 'off' : ''} ${unavailableReason(
              this.rungAvailability(alt),
            )
              ? 'unavailable'
              : ''}"
            data-testid="pill-alt"
            data-unavailable=${String(unavailableReason(this.rungAvailability(alt)) !== null)}
            title=${unavailableReason(this.rungAvailability(alt)) ?? RUNGS[alt].label}
            >${RUNGS[alt].pill} ${alt === 'steer' ? '⌘⏎' : '⇥'}</span
          >
          ${/* The escalation affordances stay OPERABLE while the model is down (a soft unavailability,
                per `availability.ts`: the reason is reachable, the click is not silently swallowed) —
                the lock is the only hard gate, because only the lock is this session's own refusal.
                The reason itself is a VISIBLE line below, referenced by `aria-describedby`, never a
                `title`: a tooltip on a control that may also be lock-disabled is unreachable in the
                state it describes (596 face 1.1), and an honesty fact must not hide behind hover. */ ''}
          <button
            type="button"
            data-testid="commit"
            ?disabled=${this.sessionLocked}
            aria-disabled=${String(askReason !== null)}
            data-unavailable=${String(askReason !== null)}
            aria-describedby=${askReason !== null ? 'sv2-ai-unavailable' : nothing}
            @click=${this.commit}
          >${askLabel}</button>
          ${/* L9 — the same optimistic hint on BOTH send buttons: a locked session promises no send
                on either rung. The keyboard paths still reach the ONE refusal handler, so the draft
                and the refusal's exits are never lost. */ ''}
          <button
            type="button"
            data-testid="delegate"
            ?disabled=${this.sessionLocked}
            aria-disabled=${String(agentReason !== null)}
            data-unavailable=${String(agentReason !== null)}
            aria-describedby=${agentReason !== null ? 'sv2-ai-unavailable' : nothing}
            @click=${this.delegate}
          >Delegate ⌘⏎</button>
        </div>
        ${this.queryTrail()}
        ${askReason ?? agentReason
          ? html`<p class="count" id="sv2-ai-unavailable" data-testid="ai-unavailable">
              ${askReason ?? agentReason} — searching your files is unaffected.
            </p>`
          : nothing}
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
        ${this.zeroNote()} ${this.runRegion()}
      </section>
    `;
  }

  /**
   * L12 — the query trail, in the input band. The rail never shows queries: it shows SESSIONS
   * (mode A) or this session's index (mode B), and a history that leaked into it would be exactly
   * the item-by-item yielding L12 forbids.
   *
   * Two sections with two different sources, each named on screen so neither can be mistaken for the
   * other: PINNED comes from the shared `pinnedSearchState` projection (the same pins the shipped
   * search surface writes — this window neither forks nor mints them), RECENT from this session's
   * own committed searches first and then the local trail of queries that ran without being
   * committed (`queryTrail.ts` states that order and why).
   */
  private queryTrail(): TemplateResult | typeof nothing {
    if (!this.historyOpen) return nothing;
    const { pinned, recent } = this.trailRows();
    if (pinned.length === 0 && recent.length === 0) return nothing;
    let index = -1;
    const row = (query: string, pinnedRow: boolean): TemplateResult => {
      index += 1;
      const i = index;
      return html`<button
        type="button"
        data-testid="trail-row"
        data-index=${i}
        data-pinned=${String(pinnedRow)}
        aria-current=${this.historyCursor === i ? 'true' : nothing}
        @click=${() => this.runTrailQuery(query)}
      >
        <span>${query}</span>
      </button>`;
    };
    return html`
      ${/* A labelled GROUP of real buttons, not a hand-rolled combobox: the rows are natively
            focusable and the walk marks its position with `aria-current`, so nothing here claims an
            ARIA pattern the window only half implements. Stated residual: the trail closes on
            Escape, on a pick, and on a send — not on a click elsewhere in the window. */ ''}
      <div
        class="qhist"
        id="sv2-query-trail"
        role="group"
        aria-label="Earlier searches"
        data-testid="query-trail"
        @keydown=${this.onTrailKeydown}
      >
        ${pinned.length > 0
          ? html`<h2 data-testid="trail-pinned-label">Pinned searches</h2>
              ${pinned.map((q) => row(q, true))}`
          : nothing}
        ${recent.length > 0
          ? html`<h2 data-testid="trail-recent-label">Recent</h2>
              ${recent.map((q) => row(q, false))}`
          : nothing}
      </div>
    `;
  }

  /**
   * L7/L13 — the deck's movable boundary. It is a button, not a styled `div`: the same boundary has
   * to be movable from the keyboard, and a native button gets focus + activation semantics by
   * construction instead of a hand-rolled role/tabindex triad.
   */
  private deckGrip(): TemplateResult {
    return html`<button
      type="button"
      class="grip"
      data-testid="deck-grip"
      aria-label="Resize the search area — arrow keys resize, Home returns to automatic"
      @pointerdown=${this.onGripPointerDown}
      @keydown=${this.onGripKeydown}
      @dblclick=${this.resetDeckSize}
    ></button>`;
  }

  /**
   * The honest empty (818 §6b). Zero is a count like any other and the card already states it
   * ("No matches for …") — this line adds the DIRECTION the count cannot: what typically causes an
   * empty set here, and that an empty list is not an empty corpus. No fabricated rows, no
   * near-match list this window cannot actually produce yet, and no second count.
   */
  private zeroNote(): TemplateResult | typeof nothing {
    const live = this.live;
    // Collapsed, the deck is at its minimum honest form — the derived count line, which already
    // states the zero. The direction belongs to the expanded form; it is elaboration, not the fact.
    if (!live || this.listCollapsed) return nothing;
    const query = live.query.trim();
    if (!query || live.isSearching || live.error || live.results.length > 0) return nothing;
    return html`<p class="none-left" data-testid="zero-note">
      Nothing in your files matches all of “${query}”. Names are the usual culprit — try fewer words,
      or ask anyway — no matches here does not mean there is nothing to answer from.
    </p>`;
  }

  /**
   * The availability of an escalation affordance, projected ONCE from the observed-state authority
   * (`projectAvailability`) rather than re-derived here from `capabilities.chat` / phase / liveness.
   * The window's pills therefore give the same reason, in the same words, that every other
   * capability-gated control in the product gives — including its remedy.
   */
  private escalationAvailability(affordance: 'documents' | 'agent'): Availability {
    return projectAvailability(affordance, this.aiSnapshot);
  }

  /** Which capability a destination rung depends on — `null` for SEARCH, the floor, which has none. */
  private rungAvailability(rung: Rung): Availability | null {
    if (rung === 'ask' || rung === 'chat') return this.escalationAvailability('documents');
    if (rung === 'agent' || rung === 'steer' || rung === 'workflow') {
      return this.escalationAvailability('agent');
    }
    return null;
  }

  /**
   * L7 — the user's own collapse of the list body. Distinct from EVICTION, which the window decides
   * when the column cannot hold everything: the two can both be true, and re-expanding here only
   * withdraws the user's half. If the room genuinely is not there, the next reconcile keeps the list
   * in its minimum honest form and the affordance says so, rather than promising rows that would be
   * two pixels tall.
   */
  private toggleList(): void {
    this.listCollapsedByUser = !this.listCollapsed;
    this.reconcileBoundaries();
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
    return `${matchCountLabel(live.matchCount, shown, false, live.totalHits, live.facetsTruncated)} · results hidden`;
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
    // L7 (amended) — EVICTED: the column cannot hold the feed's body without starving something that
    // may not yield, so the feed takes its own minimum honest form. It states what the run has done,
    // derived from the same observed activity the receipt will carry (L6), rather than rendering a
    // scroller too short to read. The CONTROLS beside it are untouched: a decision never yields.
    if (this.feedCollapsed) {
      const calls = this.observedToolCalls(ctrl);
      return html`<p class="count" data-testid="run-feed-collapsed">
        Run in progress · ${calls} tool ${calls === 1 ? 'call' : 'calls'} · steps hidden for room
      </p>`;
    }
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
                aria-label="Steer the running agent"
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
        <p>${r.wording} — your question was not asked. Your text is still in the search box.</p>
        <div class="refusal-exits">
          ${nav
            ? html`<button
                type="button"
                data-testid="lock-exit-unlock"
                @click=${() => requestSurfaceNavigation(nav.target)}
              >${nav.label}</button>`
            : nothing}
          <button type="button" data-testid="lock-exit-new" @click=${this.newSessionWithDraft}>
            New session with this text
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
    // L14 — the OCCUPANCY rests (it is the verdict the meter exists to give); the numbers it was
    // computed from extend. There is no separate breakdown feed on this window, so the breakdown IS
    // those numbers — the elaboration is never a second, differently-derived figure.
    return html`<p
      class="count ext-row"
      data-testid="context-meter"
      data-band=${horizon.color}
      tabindex="0"
    >
      Context ${horizon.pct}% full
      <span class="ext" data-testid="context-meter-breakdown"
        >${(this.contextPromptTokens ?? 0).toLocaleString()} of
        ${horizon.window.toLocaleString()} tokens</span
      >
    </p>`;
  }

  /** The remaining unbuilt occupant, present as a labelled box so the window's shape stays honest. */
  private placeholders(): TemplateResult {
    return html`
      <div class="placeholders">
        <div class="placeholder" data-testid="placeholder-material-rail">
          Documents you keep — not built yet
        </div>
      </div>
    `;
  }
}

if (!customElements.get('jf-search-v2')) {
  customElements.define('jf-search-v2', SearchV2View);
}
