// SPDX-License-Identifier: Apache-2.0
/**
 * Slice 497 — Unified conversation surface.
 *
 * Consolidates Ask + Chat + Extract into one view with affordance-driven
 * per-message shape routing. The user types a message; affordance toggles
 * (Documents, Schema) determine which ConversationShape the backend dispatches.
 * Default (no affordance) → FreeChat. Each message dispatches independently
 * via POST /api/chat/dispatch with {shapeId, ...body}.
 *
 * Conditional rendering: citations panel appears when rag.citation_matches
 * SSE events arrive; JSON output renders when Extract shape is active.
 * The conversation thread is FE-maintained — each message is tagged with the
 * shape that produced its response.
 */

import { html, unsafeCSS, nothing, type TemplateResult } from 'lit';
import { JfElement } from '../primitives/JfElement.js';
// Tempdoc 621 Phase 1 — the chat window's body styles, extracted to keep this file readable.
import { unifiedChatBodyStyles } from './unifiedChatStyles.js';
import {
  buildRequestBody,
  SHAPE_LABELS,
  RAISE_BUDGET_STEP_TOKENS,
  EMPTY_PREFIX_SENTINEL,
  CONVERSATION_ZONES,
  type ShapeId,
  type ThreadMessage,
} from './unifiedChatRequest.js';
import { composeGridStyles } from '../primitives/compositionLayout.js';
import { friendlyStreamError } from '../utils/streamError.js';
import { composerStyles } from '../components/Composer.js';
import '../components/Composer.js';
import { takePendingSelection, takePendingForceShape, resolveShape, takePendingAutoRun } from '../utils/compose.js';
import type { SelectionPayload } from '../../api/types/selection.js';
import {
  getSelection as getCurrentSelection,
  subscribeSelection,
} from '../state/selectionState.js';
import { setAiActivity, subscribeAiState, type AiState } from '../state/aiStateStore.js';
import { subscribeWide } from '../state/responsiveState.js';
import { copyToClipboard } from '../utils/clipboardCopy.js';
import { orElse } from '../state/known.js';
import { readinessNotice, reasonFor } from '../state/readinessNotice.js';
import { verdictTone } from '../state/verdict.js';
import { projectAvailability, unavailableBecause, type Availability } from '../state/availability.js';
import '../components/SystemNotice.js';
import '../components/OpButton.js';
import '../components/Control.js';
import {
  projectUnifiedThread,
  projectLiveAgentActivity,
  terminalAssistantIds,
  PROMINENCE_SCALE,
  TERMINAL_NODE_WEIGHT,
  type ThreadEvent,
  type UnifiedTurnItem,
  type RunSegmentRef,
} from './unifiedThreadProjection.js';
import {
  fetchWorkflowCatalog,
  type WorkflowCatalogEntry,
} from '../../api/registry/WorkflowCatalogClient.js';
import { presentLabel } from '../display/present.js';
import { fetchUnifiedThread, type ThreadLifecycle } from './unifiedThreadClient.js';
import {
  projectBudget,
  projectContextHorizon,
  type BudgetInput,
} from './budgetProjection.js';

import {
  consumeShapeStream,
  dispatchShapeEventToHandlers,
  type RagMetaPayload,
} from '../../api/streams.js';
import type {
  CitationMatch,
  RetrievalCitation,
} from '../components/chat/CitationsPanel.js';
// Tempdoc 565 §15.B — `Claim` (the RAG accumulation model) relocated to the leaf `citationTypes`
// when `StreamingTextBlock` was retired into the one `MarkdownBlock`/`Citation` renderer.
import type { Claim } from '../components/chat/citationTypes.js';
import {
  getUnifiedChatState,
  subscribeUnifiedChat,
  resetUnifiedChatState,
  type Affordance,
} from '../state/unifiedChatState.js';
// Tempdoc 577 Goal 3 (§3.2/§3.3) — the retrieve BASE tier reuses the FE search store directly
// (pure search via /api/knowledge/search through the one buildSearchIntent seam); it is NOT an LLM
// conversation shape. The window's input drives setQuery for live hits; escalation (Ask/Delegate)
// promotes to the existing rag-ask / agent-run shapes.
import {
  subscribeSearch,
  setQuery as setSearchQuery,
  submitSearch,
  setSearchApiBase,
  recordOpenDisposition,
  type SearchState,
  type SearchHit,
} from '../state/searchState.js';
import { projectResultView, type ResultViewInput } from './searchResultViewModel.js';
import { icon } from '../components/Icon.js';
import {
  renderWhyDisclosure,
  whyThisResultStyles,
} from '../components/searchResults/whyThisResult.js';
import {
  renderFacetChips,
  facetChipStyles,
} from '../components/searchResults/facetChips.js';
import { matchCountLabel } from '../components/searchResults/matchCountLabel.js';
// Tempdoc 602 R3 — the same path/snippet presentation the dedicated Search surface uses.
import {
  formatDisplayPath,
  highlightTerms,
  highlightStyles,
} from '../components/searchResults/resultRowPresentation.js';
import {
  getFacetSelections,
  subscribeFacetSelections,
  toggleFacetValue,
} from '../state/searchFiltersState.js';
// Tempdoc 561 C-2 (graded continuum): chrome grades on the agency posture (affordance × dial).
import { agencyPosture, postureChrome } from '../state/agencyPosture.js';
import { getAutonomyLevel, subscribeAutonomy } from '../substrates/autonomy/index.js';
// Tempdoc 577 §2.14 Root I (#19) — temporal anchoring: relative time on turn boundaries.
import { formatRelative } from '../utils/relativeTime.js';
import '../components/AutonomyDial.js';
// Tempdoc 561 #6: the ONE search-evidence projection (shared with the live tool card).
import { SEARCH_EVIDENCE_CSS } from '../components/chat/searchEvidence.js';
// Tempdoc 561 (surface tier): the ONE shared agent controller + the retrospective drawer.
import { getAgentSessionController, subscribeAgentSession } from '../state/agentSessionStore.js';
import { toggleRetrospective } from '../state/retrospectiveDrawer.js';
import { toggleSources } from '../state/sourcesDrawer.js';
// Tempdoc 610 §K — the context-inspector drawer (what the last turn saw).
import '../components/ContextInspectorPane.js';
import type {
  InspectorView,
  InspectorPhase,
  InspectorSegment,
} from '../components/ContextInspectorPane.js';
import {
  toggleContextInspector,
  isContextInspectorOpen,
  setContextInspectorView,
} from '../state/contextInspectorDrawer.js';
// Tempdoc 565 §12.3.E — the source-chip row reuses the cross-surface selection store + the filename helper.
import {
  getSelectedSource,
  setSelectedSource,
  subscribeSelectedSource,
  sourceKey,
} from '../state/selectedSource.js';
import {
  filenameOf,
  groundingCoverage,
  answerFrame,
  answerFrameLabel,
  groundingDegraded,
  sourcesAreChunkPrecise,
  type AnswerFrame,
} from '../components/chat/evidenceProjection.js';
import type { CitationSelectDetail } from '../components/chat/citationTypes.js';
// Tempdoc 561 surface tier: the one window is the view for EVERY interaction shape.
import { registerViewFactory, getViewFactory } from '../router/viewFactoryRegistry.js';
import {
  CORE_INTERACTION_SHAPES,
  ONE_WINDOW_MOUNT_TAG,
  type CoreInteractionShapeId,
} from '../plugin-api/coreInteractionShapes.js';

import '../components/chat/CitationsPanel.js';
import '../components/chat/ReasoningBlock.js';
import '../components/chat/CitationHoverCard.js';
import '../components/chat/ConversationHistory.js';
// Tempdoc 561 P-B (body-unification): the agent run renders INLINE in this one conversation body —
// there is no separate <jf-agent-view>. We host the AgentSessionController here and reuse its children.
import {
  AgentSessionController,
  type AgentSource,
  type AgentSentenceCite,
  type ToolCall,
} from '../controllers/AgentSessionController.js';
// Tempdoc 565 §30 — the ONE control-intent seam every run-control affordance dispatches through.
import { dispatchRunControl } from '../controllers/runControlIntent.js';
import { requestSurfaceNavigation } from '../controllers/navigateRequest.js';
import { notifyDraftKeptOnce } from '../controllers/draftKeptHint.js';
import { DraftPersistence } from '../controllers/draftPersistence.js';
import '../components/Button.js';
// Tempdoc 610 — the per-turn ⋯ overflow menu reuses the ONE context-menu primitive
// (rides TransientController for single-open arbitration), not a hand-rolled popover.
import { openContextMenu, type ContextMenuAction } from '../components/ContextMenu.js';
import '../components/chat/ToolCallCard.js';
// Tempdoc 585 §D Phase 2 (D2) — the structured multi-agent handoff card.
import '../components/chat/HandoffCard.js';
// Tempdoc 577 §2.13 #17 — the agent authority-space panel ("what can it do, what will ask first").
import '../components/chat/AgentAuthorityPanel.js';
import '../components/chat/MarkdownBlock.js';
// Tempdoc 565 §12.3.E — the persistent evidence rail reuses the SourcesPane in docked mode (no fork).
import '../components/SourcesPane.js';
import type { Citation } from '../components/chat/MarkdownBlock.js';
import {
  claimsToCitations,
  // Tempdoc 577 Phase 1 — the shared agent-answer citation resolver (one mapping authority).
  resolveAnswerCitations as resolveAgentAnswerCitations,
} from '../components/chat/citationResolve.js';
import type { CitationHoverCard, CitationHoverData } from '../components/chat/CitationHoverCard.js';
import {
  setConversationApiBase,
  exportConversationMarkdown,
  resumeConversation,
  generateConversationTitle,
  createConversationId,
  getRecentSessions,
  recordRecentSession,
  branchConversation,
  fetchMessageIds,
  // Tempdoc 610 Phase B — the loaded conversation list + the pure sibling-set
  // projection drive the inline version pager (no new endpoint).
  subscribeConversationList,
  loadConversations,
  siblingSessionsAt,
  type Conversation,
  // Tempdoc 610 Phase C — effective-context floor (rewind) endpoints.
  setContextFloor,
  clearContextFloor,
  // Tempdoc 610 Phase D — compaction (summarize-then-floor).
  compactContext,
  // Tempdoc 610 §E.2 — edit the compaction summary in place.
  editContextFloorSummary,
  // Tempdoc 610 §E.3 — per-message exclude from the effective context.
  setMessageExcluded,
} from '../state/conversationListStore.js';
// Tempdoc 610 §J.3 — the shared hidden-source store (one source of truth across the chips + rail).
import {
  getExcludedSources,
  setExcludedSources,
  toggleExcludedSource,
  subscribeExcludedSources,
  sourceExcludeKey,
} from '../state/excludedSources.js';
// Tempdoc 609 Phase 3 — per-tab pointer so returning to chat auto-restores the thread this tab was
// viewing (instead of the global most-recent card).
import {
  setLastViewedConversation,
  clearLastViewedConversation,
  readLastViewedConversation,
} from '../controllers/lastViewedConversation.js';
import { ReasoningController } from '../controllers/ReasoningController.js';
// Tempdoc 565 §17 — the ONE run-step presentation projection + the ONE run-node primitive. The spine
// node and the trace node compose the descriptor (tone + glyph + label) instead of hand-authoring a
// status dot (no `statusAccent` here any more — that authority is consumed only inside the projector).
import { stepPresentation } from './runStepPresentation.js';
// Tempdoc 621 Phase 5 — the run-spine's pure presentation helpers.
import { computeSpinePositions, spineNodeLabel } from './runSpinePresentation.js';
import { computeSpacedPositions } from '../primitives/adaptiveSpacing.js';
import { NavigationController } from '../primitives/navigation.js';
import '../components/chat/RunNode.js';

export class UnifiedChatView extends JfElement {
  static properties = {
    apiBase: { attribute: 'api-base', type: String },
    // Tempdoc 561 surface tier: when mounted via <jf-chat-shape-mount> for a deeplink/resume, the
    // shape-id presets the affordance (the one window is now the view for every interaction shape).
    shapeId: { attribute: 'shape-id', type: String },
    // Tempdoc 561 P-B3: the view-factory forwards host_ to any inner view that declares it (see
    // view-factory.ts §507/508). The agent loop's streamViaHost path requires it for
    // shapeId='core.agent-run', so the one-window agent affordance forwards it to <jf-agent-view>.
    host_: { attribute: false },
    inputDraft: { state: true },
    // Tempdoc 565 §30 — the live-run steer input draft (the DIRECTION authority's interject).
    steerDraft: { state: true },
    // Tempdoc 585 §D Phase 3 (C2) — the time-travel fork edit input (open + draft).
    forkEditing: { state: true },
    forkDraft: { state: true },
    // Tempdoc 610 Phase A — transcript edit-in-place: the user turn being edited
    // (by message id) and its working draft. Distinct from forkEditing/forkDraft,
    // which are the agent-run time-travel fork (585).
    editingMessageId: { state: true },
    editingDraft: { state: true },
    // Tempdoc 610 Phase B — the loaded conversation list + the current
    // conversation's fork pointers feed the inline version pager.
    conversations: { state: true },
    branchParentId: { state: true },
    branchPointId: { state: true },
    // Tempdoc 610 Phase C — the effective-context floor message id (null = full
    // context). Drives the floor divider + out-of-context band.
    contextFloorId: { state: true },
    // Tempdoc 610 Phase D — the compaction summary attached to the floor (null
    // for a plain rewind), an expand toggle, and the in-flight compacting flag.
    contextFloorSummary: { state: true },
    showFloorSummary: { state: true },
    // Tempdoc 610 §E.4 — last turn's prompt-token occupancy (from the chat done payload),
    // fed into the context-budget meter; null until a turn completes.
    contextPromptTokens: { state: true },
    // Tempdoc 610 §I.2 — the per-phase token split (system/conversation/retrieved) for the meter breakdown.
    contextBreakdown: { state: true },
    // Tempdoc 610 §E.2 — in-place editing of the compaction summary.
    editingFloorSummary: { state: true },
    floorSummaryDraft: { state: true },
    // Tempdoc 610 §E.3 — message ids excluded from the effective context (per-message hide).
    excludedMessageIds: { state: true },
    compacting: { state: true },
    schemaDraft: { state: true },
    isStreaming: { state: true },
    streamingText: { state: true },
    errorMessage: { state: true },
    affordance: { state: true },
    thread: { state: true },
    aiState: { state: true },
    showResumePrompt: { state: true },
    // Tempdoc 629 (LAYER) — the resumed conversation's store is encrypted + locked (history 423'd).
    historyLocked: { state: true },
    // Tempdoc 577 §2.13 #17 — the agent authority-space panel toggle.
    showAbilities: { state: true },
    // Slice 515 FIX-1: docIds carried from askAi navigation, forwarded to
    // RAG dispatch so scoped retrieval actually works. Captured in
    // connectedCallback before unifiedChatState is reset.
    pinnedDocIds: { state: true },
    // Slice 515 FIX-8 — parent's first-message preview surfaced from the
    // backend so the branch banner names which parent this branch came from.
    parentFirstMessagePreview: { state: true },
    // RAG state (active during streaming)
    citations: { state: true },
    sources: { state: true },
    ragMeta: { state: true },
    rewriteNote: { state: true },
    claims: { state: true },
    // Tempdoc 577 Goal 3 — the retrieve base tier's live search snapshot (ephemeral hit-list).
    searchSnapshot: { state: true },
    // Tempdoc 577 Goal 3 §3.9a — facet selections drive the retrieve tier's chips.
    facetSelections: { state: true },
  };

  declare apiBase: string;
  declare shapeId: string | undefined;
  /** Tempdoc 561 P-B3: forwarded to <jf-agent-view> when the agent affordance is active. */
  declare host_: import('../plugin-api/plugin-types.js').PluginHostApi | undefined;
  declare inputDraft: string;
  // Tempdoc 609 §R (T2.1) — reload-durable composer draft (flush on hide, rehydrate on a fresh mount).
  readonly draftPersist = new DraftPersistence(
    this,
    'unified-chat.composer',
    () => this.inputDraft,
    (v) => {
      this.inputDraft = v;
    },
  );
  /** Tempdoc 565 §30 — the live-run steer input draft (the DIRECTION authority's interject). */
  declare steerDraft: string;
  declare forkEditing: boolean;
  declare forkDraft: string;
  /** Tempdoc 610 Phase A — the user turn currently being edited (message id), or null. */
  declare editingMessageId: string | null;
  /** Tempdoc 610 Phase A — working text for the in-place edit. */
  declare editingDraft: string;
  /** Tempdoc 610 Phase B — loaded conversation list (for the version pager). */
  declare conversations: Conversation[];
  /** Tempdoc 610 Phase B — the current conversation's parent (null on roots). */
  declare branchParentId: string | null;
  /** Tempdoc 610 Phase B — the current conversation's branch point (null on roots). */
  declare branchPointId: string | null;
  /** Tempdoc 610 Phase C — the effective-context floor message id (null = full context). */
  declare contextFloorId: string | null;
  /** Tempdoc 610 Phase D — the compaction summary attached to the floor (null = rewind). */
  declare contextFloorSummary: string | null;
  /** Tempdoc 610 §E.4 — last turn's prompt tokens (context occupancy) for the budget meter. */
  declare contextPromptTokens: number | null;
  /** Tempdoc 610 §I.2 — last turn's per-phase token split for the meter attribution breakdown. */
  declare contextBreakdown: { system: number; conversation: number; retrieved: number } | null;
  /** Tempdoc 610 §E.2 — whether the compaction summary is being edited in place. */
  declare editingFloorSummary: boolean;
  /** Tempdoc 610 §E.2 — the working draft while editing the compaction summary. */
  declare floorSummaryDraft: string;
  /** Tempdoc 610 §E.3 — message ids excluded from the effective context (still shown, not sent). */
  declare excludedMessageIds: Set<string>;
  /** Tempdoc 610 Phase D — whether the floor divider's summary is expanded. */
  declare showFloorSummary: boolean;
  /** Tempdoc 610 Phase D — a compaction request is in flight (LLM summarizing). */
  declare compacting: boolean;
  declare schemaDraft: string;
  declare isStreaming: boolean;
  declare streamingText: string;
  declare errorMessage: string;
  declare affordance: Affordance;
  declare thread: ThreadMessage[];
  declare citations: CitationMatch[];
  declare sources: RetrievalCitation[];
  declare ragMeta: RagMetaPayload | null;
  /** Tempdoc 603 C2 — the current answer's decontextualized standalone question (transparency), or null. */
  declare rewriteNote: { original: string; standalone: string } | null;
  declare claims: Claim[];
  declare aiState: AiState | null;
  /** Tempdoc 577 Goal 3 — the retrieve base tier's live search snapshot (ephemeral hit-list). */
  declare searchSnapshot: SearchState | null;
  declare facetSelections: Record<string, string[]>;
  declare showResumePrompt: boolean;
  /** Tempdoc 629 (LAYER) — the resumed conversation is encrypted + locked (history returned 423). */
  declare historyLocked: boolean;
  /** Tempdoc 577 §2.13 #17 — the agent authority-space ("what can it do") panel is open. */
  declare showAbilities: boolean;
  declare pinnedDocIds: string[];
  declare parentFirstMessagePreview: string | null;
  // Tempdoc 565 §12.3.C + multi-turn fix (A) — the run-trace collapse is PER-SEGMENT: a multi-turn
  // session has one trace segment per run, each independently collapsible. This holds the user's
  // EXPLICIT toggle per segment (keyed by the segment's first-item id); a segment with no entry uses
  // its structural default (open iff it is the trailing/in-flight run — see renderRunTrace). Not a
  // reactive property — the only mutation is the summary click, which calls requestUpdate() itself.
  private runTraceToggles = new Map<string, boolean>();
  // Tempdoc 565 §13.8 P3 — the under-answer source-chip row is a COLLAPSIBLE echo of the docked rail
  // (which owns the full source detail). This holds the user's EXPLICIT per-answer expand choice
  // (keyed by the answer item id, or `__live__` for the streaming block); no entry → the structural
  // default (collapsed when the wide rail is showing the same sources, expanded at narrow). Not a
  // reactive property — the only mutation is the summary click, which calls requestUpdate() itself.
  private sourceChipsToggles = new Map<string, boolean>();
  // Tempdoc 577 §2.14 Root I (#19) — the id of the first live item that follows restored record
  // history (the run/session boundary seam), or null when the timeline is all-record or all-live.
  // Derived during render by {@link mergedTimeline} (the one merge authority); read by the renderer.
  private resumeSeamId: string | null = null;
  // Tempdoc 562: pointer only — no cached message content. The resume preview is derived from the
  // lock-safe backend conversation list at render time, never from a client-side plaintext cache.
  private recentSession: { sessionId: string; timestamp: number } | null = null;
  private abortController: AbortController | null = null;
  readonly reasoning = new ReasoningController(() => this.requestUpdate());
  private sessionId: string;
  private storeUnsubscribe: (() => void) | null = null;
  /** Tempdoc 610 Phase B — conversation-list subscription (drives the version pager). */
  private convListUnsub: (() => void) | null = null;
  private selectionUnsubscribe: (() => void) | null = null;
  private aiStateUnsubscribe: (() => void) | null = null;
  // Tempdoc 577 Goal 3 — the retrieve base tier's search-store subscription.
  private searchUnsub: (() => void) | null = null;
  private facetUnsub: (() => void) | null = null;
  // Tempdoc 561 C-2: re-render the graded chrome when the autonomy dial changes (chrome only).
  private autonomyUnsubscribe: (() => void) | null = null;
  /** Tempdoc 610 §J.3 — re-render when the shared hidden-source set changes (e.g. toggled from the rail). */
  private excludedSourcesUnsub: (() => void) | null = null;
  private userToggledAffordance = false;
  // Tempdoc 561 P-A/P-B (Slice 2): the canonical thread record (GET /api/thread/{id}). When present,
  // the conversation renders the unified interleaved thread (chat turns + agent activity) projected
  // from this ONE record; empty -> fall back to the live this.thread render (offline / pre-fetch).
  private unifiedEvents: ThreadEvent[] = [];
  // Tempdoc 561 P-A/P-A2: the agent runs' typed loop objects (state + Turn/Iteration counts + budget)
  // projected from the record; surfaced in the Activity rail.
  private unifiedLifecycles: ThreadLifecycle[] = [];
  // Tempdoc 561 P-A/P-B (Slice 3): the agent run's budget, surfaced from <jf-agent-view> for the
  // secondary Activity rail (the demoted chrome; the conversation stays primary). Null until a run
  // reports budget.
  private agentBudget: BudgetInput | null = null;
  // Tempdoc 561 P-B (body-unification): the agent run is hosted HERE and renders inline in the one
  // thread. Lazily created on first crossing into agent mode (no idle cost otherwise); kept for the
  // view's life so a chat<->agent round-trip is lossless.
  private agentCtrl: AgentSessionController | null = null;
  // Tempdoc 577 Root I (#1d) — one-shot guard for the cross-tab reattach-on-load (see ensureAgentCtrl).
  private reattachChecked = false;
  // Tempdoc 561 surface tier: the agent controller is shared (agentSessionStore); the window only
  // subscribes — it must NOT destroy it on disconnect (the retrospective drawer also reads it).
  private agentSessionUnsub: (() => void) | null = null;
  // Tempdoc 565 §12.3.E — re-render the source chips when the cross-surface selection changes (an inline
  // [n] mark or a rail card was focused), so chip ↔ inline ↔ rail stay in sync.
  private selectedSourceUnsub: (() => void) | null = null;
  // Tempdoc 565 §12.3.E fix F — track the wide breakpoint so the docked evidence rail mounts ONLY when
  // wide (the narrow fallback is the toggle drawer); one SourcesPane instance per viewport, not two.
  // 574 F1 — the wide breakpoint comes from the one responsiveState authority (a single shared
  // matchMedia, not a per-instance MediaQueryList — the viewport is globally singular).
  private wideViewport = true;
  private unsubWide: (() => void) | null = null;
  // Tempdoc 565 §21 — the chat-first Navigation authority. The run-spine's "where am I / how do I move"
  // (POSITION dots · WINDOW box · FOCUS ring · the jump/pin CONTROL) is owned by ONE reading-position
  // model in the NavigationController (`primitives/navigation.ts`), not hand-wired here. renderRunSpine is
  // a pure projection of `this.nav.{fractions,trackPx,viewport,activeId}`; the controller self-manages its
  // observers + scroll listener + lifecycle (hostUpdated/hostDisconnected), mirroring the Adaptivity
  // controllers (OverflowController/DensityController). It is active only in agent mode at the wide
  // breakpoint — 574 F1: the breakpoint is the shared `wideViewport` (responsiveState), not a per-instance mql.
  private readonly nav = new NavigationController(this, {
    scrollEl: () => (this.shadowRoot?.querySelector('.conversation') as HTMLElement | null) ?? null,
    spineEl: () => (this.shadowRoot?.querySelector('.run-spine') as HTMLElement | null) ?? null,
    active: () => this.affordance === 'agent' && this.wideViewport,
  });
  // 548 §4.5: set when an `answer` verb activated this surface; drives a
  // one-shot auto-send once the prompt is present and the AI is chat-capable.
  private autoRunPending = false;
  private renderTickTimer: number | null = null;
  // Tempdoc 565 §15.C (fix) — true while the workflow shape is mounted but the run hasn't been
  // triggered yet; renders an explicit RUN affordance instead of auto-running on mount.
  private workflowPending = false;
  // Tempdoc 565 §26.C — the workflow PICKER: the launcher projects the `/api/registry/workflows`
  // catalog (replacing the hardcoded `WORKFLOW_ID` const, §25.2). `workflows` is the fetched catalog;
  // `selectedWorkflowId` is the picker's current choice (defaults to the first entry).
  private workflows: WorkflowCatalogEntry[] = [];
  private selectedWorkflowId: string | null = null;
  private boundCiteRefHover = this.onCiteRefHover.bind(this);
  private boundCiteRefLeave = this.onCiteRefLeave.bind(this);
  // Tempdoc 565 §33 — J/K step-nav is a window-level shortcut (the conversation div is not focusable, so
  // a div-scoped @keydown never fired for a real user). Added on connect, removed on disconnect.
  private boundWindowKeydown = this.onConversationKeydown.bind(this);
  private hoverCard: CitationHoverCard | null = null;
  // Slice 515 FIX-4 — monotonic token to discard stale syncMessageIds
  // responses. Each invocation bumps the token; only the latest one's
  // response is applied to the thread.
  private syncToken = 0;

  constructor() {
    super();
    this.apiBase = '';
    this.inputDraft = '';
    this.steerDraft = '';
    this.forkEditing = false;
    this.forkDraft = '';
    this.editingMessageId = null;
    this.editingDraft = '';
    this.conversations = [];
    this.branchParentId = null;
    this.branchPointId = null;
    this.contextFloorId = null;
    this.contextFloorSummary = null;
    this.contextPromptTokens = null;
    this.contextBreakdown = null;
    this.editingFloorSummary = false;
    this.floorSummaryDraft = '';
    this.excludedMessageIds = new Set();
    setExcludedSources([]);
    this.showFloorSummary = false;
    this.compacting = false;
    this.schemaDraft ='{\n  "type": "object",\n  "properties": {}\n}';
    this.isStreaming = false;
    this.streamingText = '';
    this.errorMessage = '';
    this.historyLocked = false;
    // Tempdoc 577 Goal 3 (§3.11) — the window lands in the `retrieve` base tier (the always-available
    // search entry tier), not free-chat. When a chat model is online the aiState subscription
    // auto-upgrades to `documents` (§ connectedCallback), so the online landing is unchanged; the only
    // behavioural delta is the AI-offline cold start, which now opens working search instead of a dead
    // "AI Offline" composer. `retrieve` is ephemeral (never persisted), so a restored session with a
    // real affordance still overrides this below.
    this.affordance = 'retrieve';
    this.thread = [];
    this.citations = [];
    this.sources = [];
    this.ragMeta = null;
    this.rewriteNote = null;
    this.claims = [];
    this.aiState = null;
    this.searchSnapshot = null;
    this.facetSelections = {};
    this.showResumePrompt = false;
    this.pinnedDocIds = [];
    this.parentFirstMessagePreview = null;
    this.sessionId = createConversationId();
    const recent = getRecentSessions();
    if (recent.length > 0 && recent[0]) {
      this.recentSession = recent[0];
      this.showResumePrompt = true;
    }
  }

  override connectedCallback(): void {
    super.connectedCallback();
    // Tempdoc 565 §12.3.E — re-render when the cross-surface source selection changes (chip highlight).
    this.selectedSourceUnsub = subscribeSelectedSource(() => this.requestUpdate());
    // Tempdoc 565 fix F / 574 F1 — re-render the rail mount when the wide breakpoint is crossed,
    // via the shared responsiveState authority (fires once immediately with the current value).
    this.unsubWide = subscribeWide((wide) => {
      this.wideViewport = wide;
      this.requestUpdate();
    });
    // Tempdoc 561 surface tier: when this one window is mounted for a specific shape (a deeplink /
    // resume via <jf-chat-shape-mount>), preset the affordance from the shape-id — so every entry
    // point lands HERE in the right mode, not in a separate per-shape view.
    const presetByShape: Record<string, Affordance> = {
      'core.rag-ask': 'documents',
      'core.extract': 'extract',
      'core.agent-run': 'agent',
      // Tempdoc 565 §15.C — the workflow run is a MODE of the one window, rendered through the SAME
      // agent path (one run-render authority): tool cards + the shared approval ceremony + the answer.
      'core.workflow-run': 'agent',
      'core.free-chat': 'none',
    };
    if (this.shapeId && presetByShape[this.shapeId] !== undefined) {
      this.affordance = presetByShape[this.shapeId]!;
      this.userToggledAffordance = true;
    }
    // Tempdoc 565 §15.C (fix): mounting the workflow shape arms a one-shot RUN affordance instead of
    // auto-running — the user explicitly triggers the run (no surprising re-run on every mount). The
    // run still streams through the unified agent controller into the one window's run authority.
    // Tempdoc 565 §26.C: the affordance is now a PICKER over the fetched workflow catalog.
    if (this.shapeId === 'core.workflow-run') {
      this.workflowPending = true;
      void this.loadWorkflows();
    }
    const initial = getUnifiedChatState();
    if (initial.query) this.inputDraft = initial.query;
    if (initial.affordance !== 'none') {
      this.affordance = initial.affordance;
      this.userToggledAffordance = true;
    }
    // 548 §4.5: drain the one-shot auto-run flag parked by the IntentRouter's
    // `answer` lowering. maybeAutoRun() fires once the prompt + AI capability
    // are both present (here, in the store subscription, or in the aiState
    // subscription — whichever settles last).
    this.autoRunPending = takePendingAutoRun();
    this.maybeAutoRun();
    // Tempdoc 526 §14.5 T3 — pinnedDocIds is sourced from selectionState's
    // result-set kind. The legacy unifiedChatState.docIds path remains as a
    // URL-restore bridge (bootstrap.ts publishes the restored set into
    // selectionState; we still read selectionState here).
    const refreshDocsFromSelection = (): void => {
      const cur = getCurrentSelection().items[0];
      if (cur && cur.kind === 'result-set') {
        this.pinnedDocIds = cur.items.map((r) => r.id);
        if (this.pinnedDocIds.length > 0 && !this.userToggledAffordance) {
          this.affordance = 'documents';
        }
      }
    };
    refreshDocsFromSelection();
    this.selectionUnsubscribe = subscribeSelection(() => {
      if (this.isStreaming) return;
      refreshDocsFromSelection();
    });
    this.storeUnsubscribe = subscribeUnifiedChat((s) => {
      if (this.isStreaming) return;
      if (s.query) this.inputDraft = s.query;
      if (s.affordance !== 'none') {
        this.affordance = s.affordance;
        this.userToggledAffordance = true;
      }
      this.maybeAutoRun();
    });
    // Tempdoc 609 (M1) — do NOT reset the chat store on mount. `query` (composer draft prefill)
    // and `affordance` (mode) are recoverable task state held in the singleton `unifiedChatState`
    // store; resetting here is what discarded a draft on a brief tab switch. Clearing now happens
    // only through the explicit `newConversation()` (New chat) action.
    this.addEventListener('cite-ref-hover', this.boundCiteRefHover as EventListener);
    this.addEventListener('cite-ref-leave', this.boundCiteRefLeave as EventListener);
    // §33 — window-level J/K step-nav (guarded to the agent run + non-input focus inside the handler).
    window.addEventListener('keydown', this.boundWindowKeydown);

    setConversationApiBase(this.apiBase || '');
    // Tempdoc 610 Phase B — track the loaded conversation list so the inline
    // version pager can resolve a turn's sibling set; fetch it once on connect.
    this.convListUnsub = subscribeConversationList((s) => {
      this.conversations = s.conversations;
    });
    void loadConversations();
    // Tempdoc 609 — auto-restore the conversation this tab was viewing, but ONLY when the thread is
    // empty. Under instance-retention, a same-session tab switch reuses this element with `this.thread`
    // already populated, so connect must NOT re-fetch (that would blank-then-reload — the §K.2 flicker).
    // The auto-load therefore fires only on a genuinely cold/empty instance: a fresh page reload (new
    // instance, empty thread) restores the thread via the per-tab `lastViewedConversation` pointer
    // (sessionStorage survives reload); same-session navigation keeps the retained thread silently.
    // Cold start with no pointer keeps the constructor's most-recent resume-card behavior.
    const lastViewed = readLastViewedConversation();
    if (lastViewed && this.thread.length === 0) {
      this.showResumePrompt = false;
      void this.loadConversation(lastViewed, 'core.free-chat');
    }
    this.aiStateUnsubscribe = subscribeAiState((s) => {
      this.aiState = s;
      if (!this.userToggledAffordance && s.capabilities.rag) {
        this.affordance = 'documents';
      }
      this.maybeAutoRun();
    });
    // Tempdoc 561 C-2: the dial change only re-grades chrome (placeholder / send label / rail
    // posture); it touches no record and no in-flight run.
    this.autonomyUnsubscribe = subscribeAutonomy(() => this.requestUpdate());
    this.excludedSourcesUnsub = subscribeExcludedSources(() => this.requestUpdate());
    // Tempdoc 577 Goal 3 — the retrieve base tier reads the one search store. Same apiBase as chat.
    setSearchApiBase(this.apiBase || '');
    this.searchUnsub = subscribeSearch((s) => {
      this.searchSnapshot = s;
    });
    // §3.9a — facet selections drive the retrieve tier's chips; seed + subscribe.
    this.facetSelections = getFacetSelections();
    this.facetUnsub = subscribeFacetSelections((sel) => {
      this.facetSelections = sel;
    });
  }

  /**
   * 548 §4.5 — one-shot auto-send for the `answer` verb. Fires `send()` exactly
   * once when an `answer` intent activated this surface AND the prompt is
   * prefilled AND the AI is chat-capable. Idempotent: clears the flag before
   * dispatch so the multiple subscription paths that call it can't double-fire.
   * If the AI is offline the flag simply never fires and the prompt stays
   * prefilled for the user to send manually once the model is up.
   */
  private maybeAutoRun(): void {
    if (!this.autoRunPending) return;
    if (this.isStreaming) return;
    if (!this.inputDraft.trim()) return;
    if (!this.aiState?.capabilities?.chat) return;
    this.autoRunPending = false;
    void this.send();
  }

  /**
   * Tempdoc 609 (instance-retention) — settle transient state on hide. The Stage retains this element
   * across navigation, so `@state` survives; this resets the in-flight / partial-answer / error /
   * transient-panel fields so a return never shows a stale "thinking" spinner, a half-streamed answer,
   * or a stale error. Recoverable state (thread, inputDraft, affordance, sessionId, showResumePrompt,
   * facetSelections) is deliberately NOT touched. Auto-invoked via JfElement.disconnectedCallback (so it
   * runs through `super.disconnectedCallback()` below, BEFORE the abort/teardown).
   */
  protected override settleTransients(): void {
    // A torn-down stream is no longer live — settle the global activity indicator (Phase 4).
    if (this.isStreaming) {
      setAiActivity({ state: 'idle', shapeId: null, startedAtMs: null, canCancel: false, cancel: null });
    }
    this.isStreaming = false;
    this.streamingText = '';
    this.errorMessage = '';
    this.historyLocked = false;
    this.citations = [];
    this.sources = [];
    this.claims = [];
    this.ragMeta = null;
    this.rewriteNote = null;
    this.reasoning.reset();
    // Close transient panels/editors so they don't reopen on return.
    this.showAbilities = false;
    this.forkEditing = false;
    this.forkDraft = '';
    this.steerDraft = '';
  }

  override disconnectedCallback(): void {
    super.disconnectedCallback();
    // Tempdoc 609 §R (T1.4) — instance-retention keeps the composer draft across navigation; surface that
    // reassurance once (per session) when leaving with a non-empty draft. settleTransients keeps inputDraft
    // (recoverable), so it's intact here.
    notifyDraftKeptOnce('core.unified-chat-surface', this.inputDraft.trim().length > 0);
    this.abortController?.abort();
    this.abortController = null;
    this.reasoning.destroy();
    // Tempdoc 561 surface tier: the agent controller is shared — unsubscribe, do NOT destroy it.
    this.agentSessionUnsub?.();
    this.agentSessionUnsub = null;
    this.selectedSourceUnsub?.();
    this.selectedSourceUnsub = null;
    this.unsubWide?.();
    this.unsubWide = null;
    this.searchUnsub?.();
    this.searchUnsub = null;
    this.facetUnsub?.();
    this.facetUnsub = null;
    this.excludedSourcesUnsub?.();
    this.excludedSourcesUnsub = null;
    // §21 — the run-spine's observers + scroll listeners are torn down by NavigationController.hostDisconnected.
    this.agentCtrl = null;
    this.stopRenderTick();
    this.storeUnsubscribe?.();
    this.storeUnsubscribe = null;
    this.convListUnsub?.();
    this.convListUnsub = null;
    this.selectionUnsubscribe?.();
    this.selectionUnsubscribe = null;
    this.aiStateUnsubscribe?.();
    this.aiStateUnsubscribe = null;
    this.autonomyUnsubscribe?.();
    this.autonomyUnsubscribe = null;
    this.removeEventListener('cite-ref-hover', this.boundCiteRefHover as EventListener);
    this.removeEventListener('cite-ref-leave', this.boundCiteRefLeave as EventListener);
    window.removeEventListener('keydown', this.boundWindowKeydown);
    this.hoverCard?.remove();
  }

  private startRenderTick(): void {
    this.stopRenderTick();
    this.renderTickTimer = window.setInterval(() => this.requestUpdate(), 1000);
  }

  private stopRenderTick(): void {
    if (this.renderTickTimer !== null) {
      window.clearInterval(this.renderTickTimer);
      this.renderTickTimer = null;
    }
  }

  private get thinkingElapsedSec(): number {
    const start = this.aiState?.activity?.startedAtMs;
    if (!start) return 0;
    return Math.round((Date.now() - start) / 1000);
  }

  private get docCount(): number {
    return this.aiState?.index ? orElse(this.aiState.index.documentCount, 0) : 0;
  }

  // Tempdoc 565 §15.B — the inline marks now carry their resolved source directly (every mode renders
  // through the one `MarkdownBlock` weave), so the hover handler reads `detail.source` only; the
  // sentence-index → claim → source lookup (and the `cite-ref-click` re-dispatch, and the
  // `resolveMessageData` helper they shared) retired with `StreamingTextBlock`. Marks now dispatch
  // `citation-select` themselves, which bubbles to `Shell.onCitationSelect`.
  private onCiteRefHover(e: Event): void {
    const detail = (e as CustomEvent).detail as
      | {
          rect: DOMRect;
          source?: { excerpt: string; parentDocId: string; score: number; headingText: string };
        }
      | undefined;
    if (!detail || !detail.source) return;
    const data: CitationHoverData = {
      excerpt: detail.source.excerpt,
      parentDocId: detail.source.parentDocId,
      score: detail.source.score,
      headingText: detail.source.headingText,
    };
    if (!this.hoverCard) {
      this.hoverCard = document.createElement('jf-citation-hover-card') as CitationHoverCard;
      this.shadowRoot!.appendChild(this.hoverCard);
    }
    this.hoverCard.show(data, detail.rect);
  }

  private onCiteRefLeave(): void {
    this.hoverCard?.hide();
  }

  private copyText(text: string): void {
    void copyToClipboard(text);
  }

  // ---- Tempdoc 610 Phase A — transcript edit / retry controls ----

  /** A user turn is controllable (edit/retry) when it is settled, own, has a
   * stable id, and is not part of an agent run (513 §A.5 keeps agent-run
   * branching out of scope). */
  private canTurnControl(m: ThreadMessage): boolean {
    return (
      !m.inheritedFromParent &&
      typeof m.id === 'string' &&
      !this.isStreaming &&
      m.shapeId !== 'core.agent-run' &&
      this.affordance !== 'agent'
    );
  }

  /** Open the per-turn ⋯ overflow menu via the ONE ContextMenu primitive.
   * (Edit is rendered inline on user turns, not here — §13.1.) */
  private async openTurnMenu(e: Event, idx: number): Promise<void> {
    e.stopPropagation();
    const m = this.thread[idx];
    if (!m) return;
    const rect = (e.currentTarget as HTMLElement).getBoundingClientRect();
    const allowRetryEdit =
      !this.isStreaming && this.affordance !== 'agent' && m.shapeId !== 'core.agent-run';
    // Tempdoc 610 §13.1 — Edit is the user turn's defining action and renders
    // INLINE on the turn (not here); the ⋯ overflow holds only the rest.
    const actions: ContextMenuAction[] = [];
    if (allowRetryEdit) {
      actions.push({ id: 'retry', label: 'Retry from here', category: 'ai', enabled: true });
    }
    if (typeof m.id === 'string' && !m.inheritedFromParent) {
      actions.push({
        id: 'branch',
        label: 'Branch to new thread',
        icon: 'git-branch',
        category: 'ai',
        enabled: true,
      });
    }
    // Tempdoc 610 Phase C — reset effective context to this turn (the next
    // prompt starts here; the transcript still shows everything above it).
    if (allowRetryEdit && typeof m.id === 'string') {
      actions.push({
        id: 'reset-context',
        label: 'Reset context to here',
        icon: 'history',
        category: 'ai',
        enabled: this.contextFloorId !== m.id || this.contextFloorSummary !== null,
      });
      // Tempdoc 610 Phase D — compact (summarize) everything above this turn.
      // Only offered when there is something above to summarize.
      if (idx > 0) {
        actions.push({
          id: 'compact',
          label: 'Compact up to here',
          icon: 'history',
          category: 'ai',
          enabled: !this.compacting,
        });
      }
    }
    // Tempdoc 610 §E.3 — per-message exclude: a per-message generalization of the floor. Hide this
    // turn from the next prompt while it stays in the transcript (dimmed). Toggles include/exclude.
    if (typeof m.id === 'string' && !this.isStreaming) {
      const isExcluded = this.excludedMessageIds.has(m.id);
      actions.push({
        id: 'toggle-exclude',
        label: isExcluded ? 'Include in context' : 'Exclude from context',
        category: 'ai',
        enabled: true,
      });
    }
    if (actions.length === 0) return;
    const chosen = await openContextMenu({ actions, anchor: { x: rect.left, y: rect.bottom + 4 } });
    if (chosen === 'retry') await this.retryFrom(idx);
    else if (chosen === 'branch') await this.branchHere(m.id as string);
    else if (chosen === 'reset-context') await this.resetContextTo(idx);
    else if (chosen === 'compact') await this.compactTo(idx);
    else if (chosen === 'toggle-exclude') await this.toggleMessageExcluded(idx);
  }

  /**
   * Tempdoc 610 §E.3 — toggle whether this turn is excluded from the effective context. The
   * transcript still shows it (dimmed); the next prompt drops it. The per-message sibling of the
   * floor — applied in loadEffectiveContext before the floor trim.
   */
  private async toggleMessageExcluded(idx: number): Promise<void> {
    const m = this.thread[idx];
    if (!m || typeof m.id !== 'string' || !this.sessionId) return;
    const id = m.id;
    const nextExcluded = !this.excludedMessageIds.has(id);
    const ok = await setMessageExcluded(this.sessionId, id, nextExcluded);
    if (!ok) return;
    const next = new Set(this.excludedMessageIds);
    if (nextExcluded) next.add(id);
    else next.delete(id);
    this.excludedMessageIds = next;
  }

  private startEdit(idx: number): void {
    const m = this.thread[idx];
    if (!m || m.role !== 'user' || typeof m.id !== 'string') return;
    this.editingMessageId = m.id;
    this.editingDraft = m.content;
  }

  private cancelEdit(): void {
    this.editingMessageId = null;
    this.editingDraft = '';
  }

  private onEditKeydown(e: KeyboardEvent, idx: number): void {
    if (e.key === 'Escape') {
      e.preventDefault();
      this.cancelEdit();
    } else if (e.key === 'Enter' && (e.metaKey || e.ctrlKey)) {
      e.preventDefault();
      void this.commitEdit(idx);
    }
  }

  private async commitEdit(idx: number): Promise<void> {
    const text = this.editingDraft.trim();
    this.cancelEdit();
    if (!text) return;
    await this.branchAndResend(idx, text);
  }

  /** Retry = regenerate the answer to a turn. Resolves to the prompting user
   * turn, then re-dispatches its text unchanged (Tempdoc 610 §4 — retry is
   * branch-from-before + re-dispatch, no engine change). */
  private async retryFrom(idx: number): Promise<void> {
    const m = this.thread[idx];
    if (!m) return;
    const userIdx = m.role === 'user' ? idx : idx - 1;
    const userTurn = this.thread[userIdx];
    if (!userTurn || userTurn.role !== 'user') return;
    await this.branchAndResend(userIdx, userTurn.content);
  }

  /** Shared edit/retry flow: branch from BEFORE the user turn (so the
   * re-dispatched turn is the first divergent message), switch to the branch,
   * then re-dispatch via the normal send path. First turn → empty-prefix
   * sentinel (no preceding message to branch from). */
  private async branchAndResend(userIdx: number, text: string): Promise<void> {
    if (this.isStreaming) return;
    let branchPoint: string;
    if (userIdx === 0) {
      branchPoint = EMPTY_PREFIX_SENTINEL;
    } else {
      const prev = this.thread[userIdx - 1];
      if (!prev || typeof prev.id !== 'string') {
        this.errorMessage = 'Cannot edit yet — the previous message is still saving.';
        return;
      }
      branchPoint = prev.id;
    }
    const shapeId: ShapeId = this.thread[userIdx]?.shapeId ?? 'core.free-chat';
    const preview = this.thread.find((mm) => mm.role === 'user')?.content ?? text;
    const newSessionId = await branchConversation(this.sessionId, branchPoint, preview);
    if (!newSessionId) {
      this.errorMessage = 'Failed to create branch';
      return;
    }
    // loadConversation aborts any in-flight stream (516 FIX-T1) and loads the
    // inherited prefix [0..userIdx-1].
    await this.loadConversation(newSessionId, shapeId);
    // Re-dispatch the (edited or original) turn through the normal send path.
    this.inputDraft = text;
    await this.send();
  }

  // ---- Tempdoc 610 Phase B — inline sibling version pager ----

  /** Resolve the version set for a turn, or null when it is not a divergence
   * point with >1 version. Two cases: (A) the current conversation is itself a
   * branch and this is its first own (post-prefix) turn → the fork is
   * (parent, branchPoint); (B) the current conversation is the BASE for loaded
   * branches that fork at the message just before this turn (or at the first
   * own message, for empty-prefix forks) → it is version 1 of that fork. Pure
   * read over the loaded list (no network). */
  private pagerForTurn(m: ThreadMessage): { sessions: string[]; index: number } | null {
    if (typeof m.id !== 'string' || this.conversations.length === 0) return null;
    // Case A — current conversation is a branch; pager on its first own turn.
    if (this.branchParentId && this.branchPointId && !m.inheritedFromParent) {
      const firstOwn = this.thread.find(
        (x) => !x.inheritedFromParent && typeof x.id === 'string',
      );
      if (firstOwn?.id === m.id) {
        const sessions = siblingSessionsAt(
          this.conversations,
          this.branchParentId,
          this.branchPointId,
        );
        if (sessions.length > 1) {
          return { sessions, index: Math.max(0, sessions.indexOf(this.sessionId)) };
        }
      }
    }
    // Case B — current conversation is the base; this turn is version 1 of any
    // fork whose branch point is the message before it (or the empty sentinel
    // at the first own turn).
    const idx = this.thread.findIndex((x) => x.id === m.id);
    const candidateKeys: string[] = [];
    if (idx > 0) {
      const prevId = this.thread[idx - 1]?.id;
      if (typeof prevId === 'string') candidateKeys.push(prevId);
    }
    const firstOwnIdx = this.thread.findIndex((x) => !x.inheritedFromParent);
    if (idx === firstOwnIdx) candidateKeys.push(EMPTY_PREFIX_SENTINEL);
    for (const key of candidateKeys) {
      const sessions = siblingSessionsAt(this.conversations, this.sessionId, key);
      if (sessions.length > 1) return { sessions, index: 0 };
    }
    return null;
  }

  private renderVersionPager(info: { sessions: string[]; index: number }): TemplateResult {
    const { sessions, index } = info;
    const shapeId: ShapeId = this.thread[0]?.shapeId ?? 'core.free-chat';
    const go = (next: number): void => {
      if (next < 0 || next >= sessions.length || next === index) return;
      void this.loadConversation(sessions[next]!, shapeId);
    };
    return html`<span class="version-pager" role="group" aria-label="Message versions">
      <button
        class="ver-nav"
        aria-label="Previous version"
        ?disabled=${index <= 0}
        @click=${() => go(index - 1)}
      >
        ${icon({ name: 'chevron-left', size: 14 })}
      </button>
      <span class="ver-count">${index + 1} / ${sessions.length}</span>
      <button
        class="ver-nav"
        aria-label="Next version"
        ?disabled=${index >= sessions.length - 1}
        @click=${() => go(index + 1)}
      >
        ${icon({ name: 'chevron-right', size: 14 })}
      </button>
    </span>`;
  }

  /**
   * Tempdoc 610 §D.2 — the ONE per-turn action bar, rendered BELOW each settled
   * turn (the ChatGPT/Claude affordance grammar) instead of in the header /
   * bubble-corner. Primary verbs are visible icon buttons; the rest live behind
   * the `⋯` overflow (openContextMenu). Native icon buttons (keyboard-operable,
   * token-styled) so the `⋯` keeps the click event needed to anchor the menu.
   * Reused by renderMessage (live + delegated user turns) and the
   * renderUnifiedItem assistant record branches, so it shows on reloaded turns.
   */
  private renderTurnActionBar(m: ThreadMessage, idx: number): TemplateResult | typeof nothing {
    const pager = this.pagerForTurn(m);
    if (!this.canTurnControl(m)) {
      // No controls on inherited/streaming/agent turns, but a sibling pager may
      // still apply (it is gated independently on a stable id).
      return pager
        ? html`<div class="turn-actions ${m.role}-actions">${this.renderVersionPager(pager)}</div>`
        : nothing;
    }
    const isUser = m.role === 'user';
    return html`<div class="turn-actions ${m.role}-actions">
      ${pager ? this.renderVersionPager(pager) : nothing}
      ${isUser
        ? html`<button
            class="turn-act-btn"
            title="Edit"
            aria-label="Edit message"
            @click=${() => this.startEdit(idx)}
          >
            ${icon({ name: 'pencil', size: 15 })}
          </button>`
        : html`<button
              class="turn-act-btn"
              title="Copy"
              aria-label="Copy answer"
              @click=${() => this.copyText(m.content)}
            >
              ${icon({ name: 'clipboard-copy', size: 15 })}
            </button>
            <button
              class="turn-act-btn"
              title="Retry"
              aria-label="Retry"
              @click=${() => void this.retryFrom(idx)}
            >
              ${icon({ name: 'refresh-cw', size: 15 })}
            </button>`}
      <button
        class="turn-act-btn"
        title="More actions"
        aria-label="More message actions"
        @click=${(e: Event) => void this.openTurnMenu(e, idx)}
      >
        ${icon({ name: 'more-horizontal', size: 15 })}
      </button>
    </div>`;
  }

  // ---- Tempdoc 610 Phase C — effective-context floor (rewind) ----

  private async resetContextTo(idx: number): Promise<void> {
    const m = this.thread[idx];
    if (!m || typeof m.id !== 'string') return;
    const ok = await setContextFloor(this.sessionId, m.id);
    if (ok) {
      this.contextFloorId = m.id;
      // A plain rewind carries no summary (the backend clears it too).
      this.contextFloorSummary = null;
      this.showFloorSummary = false;
    } else {
      this.errorMessage = 'Failed to reset context';
    }
  }

  private async restoreContext(): Promise<void> {
    const ok = await clearContextFloor(this.sessionId);
    if (ok) {
      this.contextFloorId = null;
      this.contextFloorSummary = null;
      this.showFloorSummary = false;
    } else {
      this.errorMessage = 'Failed to restore context';
    }
  }

  /** Tempdoc 610 Phase D — compact: the backend summarizes everything above
   * this turn (one-shot LLM) and attaches the summary to a floor here. */
  private async compactTo(idx: number): Promise<void> {
    const m = this.thread[idx];
    if (!m || typeof m.id !== 'string' || this.compacting) return;
    this.compacting = true;
    try {
      const summary = await compactContext(this.sessionId, m.id);
      if (summary) {
        this.contextFloorId = m.id;
        this.contextFloorSummary = summary;
        this.showFloorSummary = false;
      } else {
        this.errorMessage = 'Compaction unavailable (the model may be offline)';
      }
    } finally {
      this.compacting = false;
    }
  }

  /** The thread index of the context floor, or -1 when no floor is set. */
  private floorIndex(): number {
    if (!this.contextFloorId) return -1;
    return this.thread.findIndex((m) => m.id === this.contextFloorId);
  }

  /** The full-width divider rendered just above the floor message: everything
   * above it is shown but out-of-context (not sent to the next turn). Distinct
   * from the ↪ inherited-prefix banner. */
  /**
   * Tempdoc 610 §E.4 — the chat-surface context-budget meter: how full the model's context window
   * is (last turn's prompt occupancy ÷ n_ctx). Reuses the §577 `projectContextHorizon` projection +
   * the shared budget-bar visual + the one fullness→colour authority. The agent surface has its own
   * headroom meter in the activity rail; this is its non-agent sibling. Omitted in agent mode, or
   * when there is no occupancy/denominator yet (so it never shows a misleading 0%).
   */
  private renderContextMeter(): TemplateResult | typeof nothing {
    if (this.affordance === 'agent') return nothing;
    const horizon = projectContextHorizon({
      tokensConsumed: 0,
      tokensRemaining: 0,
      promptTokens: this.contextPromptTokens ?? 0,
      contextWindow: this.aiState?.runtime?.contextWindow ?? 0,
    });
    if (!horizon) return nothing;
    // Tempdoc 610 §I.2 — the per-phase attribution. The bar TOTAL stays the real occupancy; the
    // split (system/conversation/documents) is an over-estimate, shown only as proportions/≈. Revealed
    // on hover (the compact bar is the default) + carried in the title for SR/keyboard users.
    const b = this.contextBreakdown;
    const split = b
      ? ` — split (est.): system ~${b.system}, conversation ~${b.conversation}, documents ~${b.retrieved}`
      : '';
    return html`
      <div class="context-meter" title="How full the assistant's context window is${split}">
        <button
          type="button"
          class="context-meter-label context-meter-trigger"
          aria-label="Context ${horizon.pct}% used — show what the assistant sees"
          @click=${() => this.openContextInspector()}
        >
          Context ${horizon.pct}% · ${horizon.occupancy} / ${horizon.window} tokens
        </button>
        <div
          class="budget-bar"
          role="meter"
          aria-label="Context window used"
          aria-valuenow=${horizon.pct}
          aria-valuemin="0"
          aria-valuemax="100"
        >
          <div
            class="budget-bar-fill context-fill-${horizon.color}"
            style=${`width:${horizon.pct}%`}
          ></div>
        </div>
        ${b
          ? html`<span class="context-meter-breakdown"
              >system ~${b.system} · conversation ~${b.conversation} · documents ~${b.retrieved}
              <span class="cmb-est">(estimated)</span></span
            >`
          : nothing}
      </div>
    `;
  }

  /**
   * Tempdoc 610 §I.2 — an aggregate readout of how many turns the user has individually hidden from
   * the effective context, with a one-click "Include all" to undo them in bulk. Complements the
   * per-turn exclude (the dashed-rail dimming); shown only when at least one turn is hidden.
   */
  private renderExcludedSummary(): TemplateResult | typeof nothing {
    if (this.affordance === 'agent') return nothing;
    const n = this.excludedMessageIds.size;
    if (n === 0) return nothing;
    return html`
      <div class="excluded-summary">
        <span class="excluded-summary-label"
          >${n} turn${n === 1 ? '' : 's'} hidden from context</span
        >
        <button
          class="excluded-summary-action"
          aria-label="Include all hidden turns back into context"
          @click=${() => void this.includeAll()}
        >
          Include all
        </button>
      </div>
    `;
  }

  /** Tempdoc 610 §I.2 — bulk-undo: re-include every individually-hidden turn into the context. */
  private async includeAll(): Promise<void> {
    if (!this.sessionId || this.excludedMessageIds.size === 0) return;
    const ids = [...this.excludedMessageIds];
    const next = new Set(this.excludedMessageIds);
    for (const id of ids) {
      const ok = await setMessageExcluded(this.sessionId, id, false);
      if (ok) next.delete(id);
    }
    this.excludedMessageIds = next;
  }

  /** Tempdoc 610 §K — open the (shell-mounted) inspector, seeding its view from the current prompt. */
  private openContextInspector(): void {
    setContextInspectorView(this.buildInspectorView());
    toggleContextInspector();
  }

  /** Tempdoc 610 §K — keep the shell-mounted inspector's view fresh while it is open (e.g. a new turn). */
  protected override updated(_changed: Map<string, unknown>): void {
    if (isContextInspectorOpen()) {
      setContextInspectorView(this.buildInspectorView());
    }
  }

  /**
   * Tempdoc 610 §J/§K — the whole-prompt projection the inspector renders, computed POST-HOC from the
   * last completed turn (no re-retrieval): the system phase (token count), the in-context conversation
   * turns (+ the standing summary), and the last assistant turn's retrieved sources. Each conversation
   * turn / document gets a §L.1 position marker over the COMBINED prompt order (start/end attend well;
   * the middle is "weak"). Per-segment tokens are left null — the phase total (from the estimated
   * contextBreakdown) carries the magnitude; the real promptTokens is the authoritative grand total.
   */
  private buildInspectorView(): InspectorView {
    const b = this.contextBreakdown;
    const floorIdx = this.floorIndex();
    const start = floorIdx >= 0 ? floorIdx : 0;
    const convTurns = this.thread
      .slice(start)
      .filter((m) => typeof m.id !== 'string' || !this.excludedMessageIds.has(m.id));
    const lastAssistant = [...this.thread].reverse().find((m) => m.role === 'assistant');
    const sources = lastAssistant?.sources ?? [];

    // The whole-prompt order for the position signal: summary → conversation turns → documents.
    const ordered: Array<{ kind: 'turn' | 'source'; label: string; text: string }> = [];
    if (this.contextFloorSummary) {
      ordered.push({
        kind: 'turn',
        label: 'Summary of earlier turns',
        text: this.contextFloorSummary,
      });
    }
    for (const m of convTurns) {
      ordered.push({
        kind: 'turn',
        label: m.role === 'user' ? 'You' : 'Assistant',
        text: m.content,
      });
    }
    for (const s of sources) {
      ordered.push({ kind: 'source', label: s.headingText || s.parentDocId, text: s.excerpt });
    }
    const total = ordered.length;
    const posOf = (i: number): 'strong' | 'weak' => {
      if (total <= 4) return 'strong';
      const head = Math.ceil(total * 0.25);
      const tail = Math.floor(total * 0.75);
      return i < head || i >= tail ? 'strong' : 'weak';
    };

    const convSegs: InspectorSegment[] = [];
    const docSegs: InspectorSegment[] = [];
    ordered.forEach((o, i) => {
      const seg: InspectorSegment = {
        label: o.label,
        text: o.text,
        tokens: null,
        position: posOf(i),
      };
      if (o.kind === 'source') docSegs.push(seg);
      else convSegs.push(seg);
    });

    const phases: InspectorPhase[] = [
      { name: 'Conversation', tokens: b?.conversation ?? null, segments: convSegs },
      { name: 'Documents', tokens: b?.retrieved ?? null, segments: docSegs },
    ];
    return {
      systemTokens: b?.system ?? null,
      phases,
      totalTokens: this.contextPromptTokens,
      windowTokens: this.aiState?.runtime?.contextWindow ?? null,
    };
  }

  private renderFloorDivider(): TemplateResult {
    const compacted = this.contextFloorSummary !== null;
    const label = compacted
      ? '❏ Context compacted — earlier messages summarized for the assistant'
      : '↺ Context reset — the assistant no longer sees messages above this line';
    return html`<div class="context-floor-divider" role="separator">
        <span class="cfd-label">${label}</span>
        ${compacted
          ? html`<button
              class="cfd-restore"
              @click=${() => {
                this.showFloorSummary = !this.showFloorSummary;
              }}
            >
              ${this.showFloorSummary ? 'Hide summary' : 'Show summary'}
            </button>`
          : nothing}
        ${compacted && this.showFloorSummary && !this.editingFloorSummary
          ? html`<button
              class="cfd-restore"
              @click=${() => {
                this.floorSummaryDraft = this.contextFloorSummary ?? '';
                this.editingFloorSummary = true;
              }}
            >
              Edit
            </button>`
          : nothing}
        <button class="cfd-restore" @click=${() => void this.restoreContext()}>Restore</button>
      </div>
      ${compacted && this.showFloorSummary
        ? this.editingFloorSummary
          ? html`<div class="cfd-summary cfd-summary-editing">
              <textarea
                class="cfd-summary-input"
                aria-label="Edit context summary"
                .value=${this.floorSummaryDraft}
                @input=${(e: Event) => {
                  this.floorSummaryDraft = (e.target as HTMLTextAreaElement).value;
                }}
              ></textarea>
              <div class="cfd-summary-actions">
                <button class="cfd-restore" @click=${() => void this.commitFloorSummaryEdit()}>
                  Save
                </button>
                <button
                  class="cfd-restore"
                  @click=${() => {
                    this.editingFloorSummary = false;
                  }}
                >
                  Cancel
                </button>
              </div>
            </div>`
          : html`<div class="cfd-summary">${this.contextFloorSummary}</div>`
        : nothing}`;
  }

  /**
   * Tempdoc 610 §E.2 — persist an in-place edit of the compaction summary. The floor is unchanged;
   * only the stored summary text is replaced (the backend reuses compactContext), so the corrected
   * summary stands in for the dropped turns on the next prompt — the §E.1 "no write barriers" answer.
   */
  private async commitFloorSummaryEdit(): Promise<void> {
    if (!this.sessionId) return;
    const text = this.floorSummaryDraft;
    const ok = await editContextFloorSummary(this.sessionId, text);
    if (ok) {
      this.contextFloorSummary = text;
      this.editingFloorSummary = false;
    }
  }

  /** Tempdoc 610 — floor divider + out-of-context class for a record-path turn
   * (renderUnifiedItem), keyed by the record item's id → thread index. Mirrors
   * the renderMessage floor handling so the divider/band render on persisted
   * assistant turns too (not only the live-overlay path). */
  /**
   * Tempdoc 610 §F.3 — the SINGLE source for a turn's effective-context "frame parts": the floor
   * divider (if this is the floor turn) and the dim-class (out-of-context band when above the floor,
   * the dashed-rail when individually excluded). Both render paths derive from here — the live path
   * (renderMessage) and the record path (recordFloorParts) — so a per-turn affordance can no longer
   * be added to one path and forgotten on the other (the root cause of the two §C.1/§D.5 bugs).
   */
  private floorFrameParts(
    id: string | undefined,
    idx: number,
  ): { divider: TemplateResult | typeof nothing; cls: string } {
    const fi = this.floorIndex();
    const outOfContext = fi >= 0 && idx >= 0 && idx < fi ? ' out-of-context' : '';
    const excluded =
      typeof id === 'string' && this.excludedMessageIds.has(id) ? ' excluded' : '';
    return {
      divider: fi >= 0 && idx === fi ? this.renderFloorDivider() : nothing,
      cls: outOfContext + excluded,
    };
  }

  private recordFloorParts(itemId: string | undefined): {
    divider: TemplateResult | typeof nothing;
    cls: string;
  } {
    const idx = typeof itemId === 'string' ? this.thread.findIndex((m) => m.id === itemId) : -1;
    return this.floorFrameParts(itemId, idx);
  }

  /** Tempdoc 610 §D.2 — the per-turn action bar for a record-path turn
   * (renderUnifiedItem), keyed by the record item's id → live-thread message,
   * so reloaded assistant turns get copy/retry/⋯ like the live-overlay path. */
  private recordActionBar(itemId: string | undefined): TemplateResult | typeof nothing {
    const idx = typeof itemId === 'string' ? this.thread.findIndex((m) => m.id === itemId) : -1;
    if (idx < 0) return nothing;
    return this.renderTurnActionBar(this.thread[idx]!, idx);
  }

  private async onConversationSelect(e: CustomEvent): Promise<void> {
    const { sessionId, shapeId } = e.detail as { sessionId: string; shapeId: string };
    this.showResumePrompt = false;
    await this.loadConversation(sessionId, shapeId);
  }

  private async loadConversation(sessionId: string, shapeId: string): Promise<void> {
    // Slice 516 FIX-T1 — cancel any in-flight stream so its onDone doesn't
    // write into the new conversation's thread. AbortError is caught in
    // consumeShapeStream; neither onDone nor onError fires, so no further
    // mutation reaches `this.thread`.
    this.abortController?.abort();
    this.sessionId = sessionId;
    // Tempdoc 609 Phase 3 — this tab is now viewing `sessionId`; remember it so a navigation
    // round-trip auto-restores THIS thread (not the global most-recent one).
    setLastViewedConversation(sessionId);
    this.errorMessage = '';
    this.thread = [];
    // Tempdoc 561 P-A/P-B (Slice 2): load the unified thread for the (re)loaded conversation.
    this.unifiedEvents = [];
    // Tempdoc 577 Move 1 — accountability dies with its run: the previous conversation's budget
    // and lifecycle must not survive into this one (the stale-budget-after-reload defect).
    this.agentBudget = null;
    this.unifiedLifecycles = [];
    void this.refreshUnifiedThread();
    const resumed = await resumeConversation(sessionId, shapeId);
    // Tempdoc 629 (LAYER): the conversation store is encrypted + locked. Render a locked notice with an
    // Unlock affordance instead of an empty transcript (§L4: locked must never look deleted).
    this.historyLocked = resumed.locked === true;
    if (this.historyLocked) {
      this.thread = [];
      this.showResumePrompt = false;
      void loadConversations();
      return;
    }
    const resolvedShape: ShapeId =
      resumed.shapeId === 'core.rag-ask' ||
      resumed.shapeId === 'core.extract' ||
      resumed.shapeId === 'core.free-chat' ||
      resumed.shapeId === 'core.agent-run'
        ? resumed.shapeId
        : 'core.free-chat';
    // Slice 513 — if this is a branch, find the index of the branch point in
    // the resolved message list. All messages up to and including that index
    // were inherited from the parent.
    let inheritedThrough = -1;
    if (resumed.parentSessionId && resumed.branchPointMessageId) {
      for (let i = 0; i < resumed.messages.length; i++) {
        if (resumed.messages[i]?.id === resumed.branchPointMessageId) {
          inheritedThrough = i;
          break;
        }
      }
    }
    this.thread = resumed.messages.map((m, idx) => ({
      role: m.role,
      content: m.content,
      shapeId: resolvedShape,
      id: m.id,
      inheritedFromParent: idx <= inheritedThrough,
    }));
    // Slice 515 FIX-8 — capture parent preview for the branch banner.
    this.parentFirstMessagePreview = resumed.parentFirstUserMessage ?? null;
    // Tempdoc 610 Phase B — record this conversation's fork pointers so the
    // version pager can place itself on the divergent turn, and refresh the
    // conversation list so any freshly-created sibling is discoverable.
    this.branchParentId = resumed.parentSessionId ?? null;
    this.branchPointId = resumed.branchPointMessageId ?? null;
    // Tempdoc 610 Phase C — restore the effective-context floor so the divider
    // + out-of-context band render on reload.
    this.contextFloorId = resumed.contextFloor ?? null;
    // Tempdoc 610 Phase D — restore the compaction summary (if any).
    this.contextFloorSummary = resumed.contextFloorSummary ?? null;
    // Tempdoc 610 §E.3 — restore the per-message excluded set so the toggle state + dimming persist.
    this.excludedMessageIds = new Set(resumed.excludedMessageIds ?? []);
    setExcludedSources(resumed.excludedSourceIds ?? []);
    this.showFloorSummary = false;
    void loadConversations();
  }

  private exportMarkdown(): void {
    const md = exportConversationMarkdown(
      this.thread.map((m) => ({ role: m.role, content: m.content })),
      null,
    );
    void copyToClipboard(md);
  }

  private generateTitle(): void {
    if (this.thread.length < 2) return;
    const userMsg = this.thread.find((m) => m.role === 'user')?.content ?? '';
    const aiMsg = this.thread.find((m) => m.role === 'assistant')?.content ?? '';
    void generateConversationTitle(this.sessionId, userMsg, aiMsg);
  }

  // Tempdoc 577 Move 1 — renamed from `resumeSession`: this restores a CONVERSATION thread (a
  // different mechanism from the controller's agent-session resume, which now flows only through
  // the dispatchRunControl seam; the rename keeps the `.resumeSession(` channel pattern unambiguous).
  private restoreRecentConversation(sessionId: string): void {
    this.showResumePrompt = false;
    // Tempdoc 577 Goal 3 (§3.13 / A2) — leave the retrieve base tier when restoring a past chat, else
    // the loaded thread renders BEHIND the still-showing hit-list. The restored conversation is a
    // free-chat thread; viewing it needs no model (only sending a new turn does).
    if (this.affordance === 'retrieve') {
      this.affordance = 'none';
      this.userToggledAffordance = true;
    }
    void this.loadConversation(sessionId, 'core.free-chat');
  }

  /**
   * Tempdoc 577 Goal 3 (§3.13 / A2) — the "Continue your last conversation?" landing card. Rendered in
   * BOTH the retrieve base tier and the LLM tiers so past chats stay reachable when the model is offline
   * (restoring LOADS a conversation to read; only sending a new turn needs the model). In the retrieve
   * tier it shows only on the bare landing (no query) so it never sits above a live hit-list. Derived
   * state (Ext III 3e): the card cannot render once any thread / event / run source has content.
   */
  private renderResumePrompt(): TemplateResult | typeof nothing {
    const queryActive = (this.searchSnapshot?.query ?? '').trim().length > 0;
    if (
      !this.showResumePrompt ||
      !this.recentSession ||
      this.thread.length !== 0 ||
      this.unifiedEvents.length !== 0 ||
      (this.agentCtrl?.conversation.length ?? 0) !== 0 ||
      (this.agentCtrl?.streamingText.length ?? 0) !== 0 ||
      (this.affordance === 'retrieve' && queryActive)
    ) {
      return nothing;
    }
    // Tempdoc 562: the preview snippet is derived from the lock-safe backend conversation list
    // (`firstUserMessage`, which `listSessions` returns as "" while the chat store is encrypted + locked) —
    // never from a client-side plaintext cache. A present-but-blank entry means the store is locked, so we
    // show the shared `conversations.locked` lock affordance (no content), never the message text.
    const conv = this.conversations.find((c) => c.id === this.recentSession!.sessionId);
    const preview = (conv?.firstUserMessage ?? '').trim();
    const locked = conv != null && preview === '';
    return html`<div class="resume-prompt">
      <span>Continue your last conversation?</span>
      ${preview !== ''
        ? html`<em>"${preview}"</em>`
        : locked
          ? html`<em class="resume-locked">${icon({ name: 'shield', size: 13 })} ${reasonFor('conversations.locked').wording}</em>`
          : nothing}
      <div class="resume-actions">
        <button class="resume-btn" @click=${() => this.restoreRecentConversation(this.recentSession!.sessionId)}>Continue</button>
        <button class="dismiss-btn" @click=${() => this.dismissResumePrompt()}>Start fresh</button>
      </div>
    </div>`;
  }

  private dismissResumePrompt(): void {
    this.showResumePrompt = false;
    // Tempdoc 609 Phase 3 — "Start fresh" is an explicit cold start; forget the last-viewed pointer
    // so a later return does not auto-restore the conversation the user just dismissed.
    clearLastViewedConversation();
  }

  private newConversation(): void {
    // Slice 516 FIX-T1 — cancel any in-flight stream so its onDone doesn't
    // append to the new (empty) thread.
    this.abortController?.abort();
    // Tempdoc 609 (M1) — clearing the recoverable chat store is now intent-driven: the
    // explicit "New chat" action resets the composer draft + mode, replacing the old
    // reset-on-mount that discarded a draft on every navigation.
    this.inputDraft = '';
    resetUnifiedChatState();
    // Tempdoc 609 Phase 3 — New chat means "don't auto-restore the old thread"; forget the pointer.
    clearLastViewedConversation();
    this.thread = [];
    this.sessionId = createConversationId();
    this.streamingText = '';
    this.errorMessage = '';
    this.citations = [];
    this.sources = [];
    this.ragMeta = null;
    this.rewriteNote = null;
    this.claims = [];
    this.showResumePrompt = false;
    // Slice 515 FIX-1: forget the previous askAi-pinned docIds so a new
    // conversation starts with open-retrieval unless the user re-selects.
    this.pinnedDocIds = [];
    this.parentFirstMessagePreview = null;
    // Tempdoc 610 Phase B — a fresh conversation is a root: no fork pointers.
    this.branchParentId = null;
    this.branchPointId = null;
    // Tempdoc 610 Phase C/D — a fresh conversation has full context, no summary.
    this.contextFloorId = null;
    this.contextFloorSummary = null;
    this.contextPromptTokens = null;
    this.contextBreakdown = null;
    this.editingFloorSummary = false;
    this.floorSummaryDraft = '';
    this.excludedMessageIds = new Set();
    setExcludedSources([]);
    this.showFloorSummary = false;
    this.editingMessageId = null;
    this.editingDraft = '';
    // Tempdoc 577 Move 1 — accountability dies with its run (see loadConversation).
    this.agentBudget = null;
    this.unifiedLifecycles = [];
  }

  /**
   * Tempdoc 565 §30 — the live-run STEER input (the DIRECTION authority's `interject`). Renders only
   * while an agent run is in flight; submitting dispatches an `interject` directive through the ONE
   * control-intent seam, which the backend drains at the run's next step boundary and folds into the
   * next LLM call. (Peers: `halt` = the seam's session stop `dispatchRunControl({kind:'halt'})` →
   * `cancelSession`; the composer's inline cancel does a lighter `abortController.abort()` stream
   * teardown. `set-posture` = the autonomy dial → the global 561 P-D store, a peer channel.)
   */
  private renderSteerInput(): TemplateResult | typeof nothing {
    // Tempdoc 565 §33 — show ONLY for a steerable, in-flight agent run. Gate on the CONTROLLER's
    // streaming state (the view's own `this.isStreaming` tracks the non-agent answer plane and is FALSE
    // during an agent run — the original §30 gate used it by mistake, so the steer input never showed),
    // AND on `runKind === 'agent'`: a workflow run streams in the agent affordance too but goes through
    // WorkflowShapeRunner (no interject drain) + isn't an AgentLoopService session, so steering it 404s.
    const ctrl = this.agentCtrl;
    if (this.affordance !== 'agent' || !ctrl?.isStreaming || ctrl.runKind !== 'agent') {
      return nothing;
    }
    const submit = () => {
      const text = this.steerDraft.trim();
      const ctrl = this.agentCtrl;
      if (!text || !ctrl) return;
      void dispatchRunControl(ctrl, { kind: 'interject', text });
      this.steerDraft = '';
    };
    return html`<div class="run-steer" data-steer-input>
      <input
        class="run-steer__input"
        type="text"
        .value=${this.steerDraft}
        placeholder="Redirect the agent…"
        aria-label="Steer the running agent"
        @input=${(e: Event) => (this.steerDraft = (e.target as HTMLInputElement).value)}
        @keydown=${(e: KeyboardEvent) => {
          if (e.key === 'Enter') {
            e.preventDefault();
            submit();
          }
        }}
      />
      <jf-button
        size="sm"
        label="Steer the agent"
        .availability=${!this.steerDraft.trim()
          ? unavailableBecause('Type a direction to send')
          : undefined}
        .onActivate=${submit}
        >Steer</jf-button
      >
    </div>`;
  }

  private renderAffordancePreview(): TemplateResult | typeof nothing {
    if (this.affordance === 'documents') {
      return html`<div class="affordance-preview">Searching ${this.docCount} documents</div>`;
    }
    if (this.affordance === 'extract') {
      try {
        const schema = JSON.parse(this.schemaDraft);
        const propCount = Object.keys(schema?.properties ?? {}).length;
        return html`<div class="affordance-preview">Extracting with schema (${propCount} ${propCount === 1 ? 'property' : 'properties'})</div>`;
      } catch {
        return html`<div class="affordance-preview">Extracting with schema</div>`;
      }
    }
    return nothing;
  }

  static styles = [composerStyles, unsafeCSS(SEARCH_EVIDENCE_CSS), whyThisResultStyles, facetChipStyles, highlightStyles, unifiedChatBodyStyles,
    // §13 Pillar B — the GENERATED grid frame for the conversation-zone (replaces the hand-authored
    // grid-template-columns + per-zone placements removed above; faithful per de-risk Probe S2).
    composeGridStyles(CONVERSATION_ZONES, {
      container: '.conversation-zone',
      breakpoint: '64rem',
      gap: '1.5rem',
    }),
  ];

  override render(): TemplateResult {
    // Tempdoc 526 §16 F13 — single dispatch resolver (no wrapper helper).
    // Tempdoc 561 P-B3: the answer plane resolves a per-message shape; the agent affordance is the
    // action plane — a MODE, not a per-message shape — so it is excluded from shape resolution.
    const answerAffordance = this.affordance === 'agent' ? 'none' : this.affordance;
    const currentShape = resolveShape('core.ask', 'none', answerAffordance) as ShapeId;
    const agentMode = this.affordance === 'agent';
    // Tempdoc 561 P-B3 (Tier-1 correctness fix): both planes are rendered on every pass and the
    // inactive one is hidden (visibility toggle), NOT swapped via a ?: branch. A branch destroyed
    // <jf-agent-view> on every crossing → its disconnectedCallback ran AgentSessionController.destroy()
    // (abort streams, stop polling) → re-entry constructed a fresh controller → the in-progress run
    // (conversation, tool cards, pending approvals, streaming) was wiped. Keeping the element mounted
    // makes a chat↔agent round-trip lossless. We mount the agent plane lazily on first entry (so users
    // who never open the agent pay no idle-controller cost), then keep it mounted for the view's life.
    // Tempdoc 561 P-B (body-unification): ONE conversation body. No separate <jf-agent-view> plane and
    // no visibility swap — the agent run renders INLINE in the unified thread (renderLiveAgentActivity).
    // Lazily create the hosted controller on first crossing; keep it for the view's life (lossless
    // chat<->agent round-trip).
    if (agentMode) this.ensureAgentCtrl();
    return html`
      <div class="header">
        <div>
          <strong>Chat</strong> — ask anything
          <jf-conversation-history
            @conversation-select=${(e: CustomEvent) => this.onConversationSelect(e)}
          ></jf-conversation-history>
          ${this.thread.length > 0 && !agentMode
            ? html`<button class="new-chat-btn" @click=${() => this.newConversation()}>New chat</button>
                   <button class="new-chat-btn" @click=${() => this.exportMarkdown()}>Export</button>`
            : nothing}
        </div>
        <span class="shape-indicator">${agentMode ? 'Agent' : SHAPE_LABELS[currentShape]}</span>
      </div>
      ${this.renderDegradationBanner()}
      ${this.renderAffordanceBar(agentMode)}
      <div class="answer-plane">${this.renderAnswerPlane()}</div>
    `;
  }

  /**
   * Tempdoc 596 §11.4 — the chat window's degradation banner. The search surface already explains
   * an AI/readiness degradation with a reachable, REMEDIED notice (SearchSurface.renderDegradationBanner);
   * the chat surface — where the capability affordances get disabled — did not, so the only "why" was
   * a suppressed `title`. This mirrors that one idiom (the same `readinessNotice` projection +
   * `<jf-system-notice>` + remedy button) so the window-level reason ("AI offline · [Reload AI]") is
   * reachable and actionable beside the affordance bar. Reads the SAME ONE verdict (595 §4.2) the
   * search banner consumes, so the two windows cannot disagree.
   */
  private renderDegradationBanner(): TemplateResult | typeof nothing {
    const verdict = this.aiState?.verdict;
    if (!verdict) return nothing;
    const notice = readinessNotice(verdict);
    if (!notice) return nothing;
    return html`<jf-system-notice
      tone=${verdictTone(verdict.severity)}
      live="status"
      class="degradation-banner"
      data-testid="chat-degradation"
    >
      <span class="notice-row"
        >${icon({ name: 'alert-triangle', size: 13 })}
        <span><strong>${notice.headline}</strong> ${notice.body}</span></span
      >
      ${notice.causes.length > 0
        ? html`<ul class="notice-causes" data-testid="chat-degradation-causes">
            ${notice.causes.map((c) => html`<li>${c}</li>`)}
          </ul>`
        : nothing}
      <span class="notice-remedy">
        ${notice.remedy.kind === 'operation'
          ? html`<jf-op-button
              operation-id=${notice.remedy.operationId}
              api-base=${this.apiBase}
              data-testid="chat-degradation-remedy-op"
            ></jf-op-button>`
          : html`<jf-button
              variant="secondary"
              data-testid="chat-degradation-remedy-nav"
              .onActivate=${() => this.openRemedyTarget((notice.remedy as { target: string }).target)}
              >${notice.remedy.label}</jf-button
            >`}
      </span>
    </jf-system-notice>`;
  }

  /** Tempdoc 596 §11.4 — navigate to a notice remedy target (mirrors SearchSurface.openRemedyTarget). */
  private openRemedyTarget(target: string): void {
    this.dispatchEvent(
      new CustomEvent('navigate-with-context', {
        detail: { target, state: {} },
        bubbles: true,
        composed: true,
      }),
    );
  }

  /**
   * Tempdoc 561 P-B3: the affordance bar — the §2.1 crossing control, shared by both planes so the
   * user can cross between the answer plane (Documents/Structured) and the action plane (Agent)
   * from within the one window. 'Agent' replaces the old disabled 'Tools' stub (the un-built
   * crossing the audit flagged).
   */
  /**
   * Tempdoc 596 §9/§14 — the availability of a capability tab, projected once from the observed-state
   * authority. `isStreaming` is a HARD intent gate (can't switch mode mid-stream) → `blocked` (inert);
   * everything else is a SOFT capability gap → `unavailable{reason}` (reachable reason, non-silent
   * block). The reason that used to live on a suppressed `title` is now rendered by jf-control.
   */
  private tabAvailability(affordance: 'documents' | 'extract' | 'agent'): Availability {
    if (this.isStreaming) return { kind: 'blocked' };
    return projectAvailability(affordance, this.aiState);
  }

  private renderAffordanceBar(agentMode: boolean): TemplateResult {
    return html`
      <div class="affordance-bar">
        ${/* Tempdoc 577 Goal 3 (§3.2) — the retrieve base tier: instant search hits, no LLM. The
              lowest intent rung; "Documents" (Ask) and "Agent" (Delegate) are the escalations. */ ''}
        <button
          class="affordance-btn ${this.affordance === 'retrieve' ? 'active' : ''}"
          @click=${() => this.toggleAffordance('retrieve')}
          title="Search your files — instant results, no AI"
        >
          Search
        </button>
        ${/* Tempdoc 596 — the three capability tabs are jf-control with TYPED availability: an
              unavailable tab is aria-disabled + focusable, its reason reachable, and a blocked
              activation surfaces the reason (no suppressed title, no swallowed click). */ ''}
        <jf-control
          class="affordance-btn ${this.affordance === 'documents' ? 'active' : ''}"
          .availability=${this.tabAvailability('documents')}
          .onActivate=${() => this.toggleAffordance('documents')}
          >Documents</jf-control
        >
        <jf-control
          class="affordance-btn ${this.affordance === 'extract' ? 'active' : ''}"
          .availability=${this.tabAvailability('extract')}
          .onActivate=${() => this.toggleAffordance('extract')}
          >Structured</jf-control
        >
        <jf-control
          class="affordance-btn ${agentMode ? 'active' : ''}"
          .availability=${this.tabAvailability('agent')}
          .onActivate=${() => this.toggleAffordance('agent')}
          >Agent</jf-control
        >
        ${/* Tempdoc 561 C-2: the supervision dial appears only at the agency crossing (agent mode),
              making the answer→action phase transition visible — the chrome grades, not a new mode. */ ''}
        ${agentMode
          ? html`<jf-autonomy-dial class="affordance-dial" compact></jf-autonomy-dial>`
          : nothing}
        ${/* Tempdoc 561 surface tier: open the retrospective drawer (Sessions/Timeline/History)
              folded into the one window — replacing the retired standalone agent surface's tabs. */ ''}
        ${/* Tempdoc 577 Ext III — the retrospective is NOT "Activity" (that read as a fourth posture
              beside the dial, and collided with the live-run rail + the Activity surface): it is the
              record looking BACK — "History". Spaced off the dial so it reads as a panel toggle. */ ''}
        <button
          class="affordance-btn retrospective-toggle ${agentMode ? '' : 'affordance-trailing'}"
          @click=${() => toggleRetrospective()}
          title="History — past sessions, timeline, tool calls, inbox"
        >
          History
        </button>
        ${/* Tempdoc 577 §2.13 #17 — the authority-space toggle: "what can this agent do, and what
            will ask first" — calibrate trust by inspection, before delegating (agent mode only). */ ''}
        ${agentMode
          ? html`<button
              class="affordance-btn"
              aria-pressed=${this.showAbilities ? 'true' : 'false'}
              @click=${() => {
                this.showAbilities = !this.showAbilities;
              }}
              title="What this agent can do, and what will ask first"
            >
              Abilities
            </button>`
          : nothing}
        ${/* Tempdoc 565 §3.A: open the answer's grounding sources (clickable local passages). */ ''}
        ${agentMode && (this.agentCtrl?.answerSources.length ?? 0) > 0
          ? html`<button
              class="affordance-btn sources-affordance"
              @click=${() => toggleSources()}
              title="The latest answer's grounding sources"
            >
              Sources · ${this.agentCtrl?.answerSources.length ?? 0}
            </button>`
          : nothing}
      </div>
    `;
  }

  private renderAnswerPlane(): TemplateResult {
    // §21 AFFORDANCE — when the run-spine is mounted it IS the scroll control (the draggable minimap
    // thumb), so the reading column hides its native scrollbar (`scrollbar-gutter: stable` reserves the
    // gutter so hiding it causes no reflow). Documents/narrow (no spine) keeps the thin native bar.
    const spineShown =
      this.affordance === 'agent' && this.wideViewport;
    return html`
      <div class="conversation-zone">
        ${this.renderRunSpine()}
        <div
          id="run-conversation"
          class="conversation ${spineShown ? 'spine-scrolled jf-scrollbar-none' : ''}"
        >
          ${/* Tempdoc 577 Goal 3 (§3.2) — the retrieve base tier renders the ephemeral hit-list IN
                the window; it owns no thread history. Escalation (Ask/Delegate) promotes to a turn. */ ''}
          ${/* Tempdoc 577 Goal 3 (§3.13 / A2) — the resume card renders BEFORE the tier split so past
                chats stay reachable in the retrieve base tier too (offline-friendly); renderResumePrompt
                guards it to the bare landing in retrieve, and "Continue" leaves retrieve to show the thread. */ ''}
          ${this.renderResumePrompt()}
          ${this.affordance === 'retrieve' ? this.renderRetrieveTier() : nothing}
          ${this.affordance === 'retrieve'
            ? nothing
            : html`
          ${this.thread.some((m) => m.inheritedFromParent)
            ? html`<div class="branch-indicator">
                ↪ Branched from ${this.parentFirstMessagePreview
                  ? html`"<em>${this.parentFirstMessagePreview.slice(0, 80)}</em>"`
                  : 'an earlier conversation'}
              </div>`
            : nothing}
          ${/* Tempdoc 577 §2.13 #17 — the agent's authority-space, on demand: inspect what it can do
               and what would ask first, BEFORE delegating (the §2.11 #8 ceremony reachable by inspection). */ ''}
          ${this.showAbilities
            ? html`<div class="abilities-panel">
                <jf-agent-authority-panel
                  .tools=${this.agentCtrl?.tools ?? []}
                  level=${getAutonomyLevel()}
                ></jf-agent-authority-panel>
              </div>`
            : nothing}
          ${/* Tempdoc 585 §D Phase 1 (C1) — run-replay scrubber: shown only when the shared controller
               is in replayMode (a finished run loaded via RetrospectivePanel → loadReplay). */ ''}
          ${this.agentCtrl?.replayMode ? this.renderReplayBar() : nothing}
          ${this.historyLocked
            ? this.renderHistoryLocked()
            : html`
                ${this.renderUnifiedConversation()}
                ${this.renderLiveOverlay()}
                ${this.renderStreamingBlock()}
                ${this.errorMessage
                  ? html`<div class="error">${this.errorMessage}</div>`
                  : nothing}`}`}
        </div>
        ${this.renderEvidenceRail()}
      </div>
      ${this.renderActivityRail()}
      ${this.renderContextMeter()}
      ${this.renderExcludedSummary()}
      <div class="composer">
        ${this.renderSteerInput()}
        ${this.renderAffordancePreview()}
        ${this.affordance === 'extract' ? this.renderSchemaInput() : nothing}
        <jf-composer
          cancellable
          .value=${this.inputDraft}
          placeholder=${this.getPlaceholder()}
          ?streaming=${this.isStreaming}
          ?submit-disabled=${!this.inputDraft.trim() ||
          this.aiState?.verdict?.kind === 'unreachable' ||
          (this.affordance !== 'retrieve' && !this.aiState?.capabilities?.chat)}
          submit-label=${this.getSubmitLabel()}
          submit-title=${
            this.aiState?.verdict?.kind === 'unreachable'
              ? 'Backend disconnected'
              : this.affordance !== 'retrieve' && !this.aiState?.capabilities?.chat
                ? 'AI offline'
                : ''
          }
          cancel-label=${this.streamingText ? 'Stop' : 'Cancel'}
          @composer-input=${(e: CustomEvent<{ value: string }>) => {
            this.inputDraft = e.detail.value;
            // Tempdoc 577 Goal 3 — in the retrieve tier the one input drives LIVE search (the staged
            // quick pass), no LLM dispatch; the hit-list is ephemeral (never a thread turn).
            if (this.affordance === 'retrieve') setSearchQuery(e.detail.value);
          }}
          @composer-submit=${() =>
            this.affordance === 'retrieve' ? submitSearch() : void this.send()}
          @composer-cancel=${() => this.abortController?.abort()}
        ></jf-composer>
      </div>
    `;
  }

  /**
   * Tempdoc 565 §12.3.D/E — the persistent evidence rail: the THIRD zone of the three-zone layout (a
   * DOCKED <jf-sources-pane> — the SAME component as the toggle drawer, NOT a fork — mounted inline in
   * the conversation's right column, OUTSIDE the single-drawer arbiter so it never closes when the
   * retrospective/advisory drawers open). Shown in agent mode whenever the answer carries grounding, so
   * the evidence is ambient (always visible), not modal. CSS hides it below the wide breakpoint, where
   * the "Sources · N" affordance + the toggle drawer remain the fallback. Cross-highlights the inline
   * [n] marks via the shared selectedSource store.
   */
  private renderEvidenceRail(): TemplateResult {
    const hasSources =
      this.affordance === 'agent' && (this.agentCtrl?.answerSources.length ?? 0) > 0;
    // Fix F — mount the docked rail ONLY at the wide breakpoint (where it is visible); narrow viewports
    // fall back to the "Sources · N" chip + the toggle drawer. So exactly one SourcesPane subscribes per
    // viewport, not a dormant duplicate. Default to mounting when matchMedia is unavailable (tests/SSR).
    const wide = this.wideViewport;
    if (!hasSources || !wide) return html`${nothing}`;
    return html`<jf-sources-pane
      docked
      class="evidence-rail"
      api-base=${this.apiBase}
      .host_=${this.host_ ?? undefined}
    ></jf-sources-pane>`;
  }

  /**
   * Tempdoc 565 §12.3.D/F — the left run-spine: "one ordered run made visual." The LATEST run's steps
   * render as a persistent vertical status spine (a minimap) in the conversation's left margin — one
   * node per step (status-tinted via the §3.B `statusAccent`), the answer the terminal node — so the
   * run's status is scannable at a glance even when the inline trace is collapsed. Positioned in the
   * margin (no grid disruption); wide viewports only; `aria-hidden` because the real, operable content
   * is the conversation — this is a decorative projection of the SAME merged timeline.
   */
  private renderRunSpine(): TemplateResult {
    if (this.affordance !== 'agent') return html`${nothing}`;
    const wide = this.wideViewport;
    if (!wide) return html`${nothing}`;
    // Tempdoc 565 §13/§19.4 — the WHOLE merged timeline as a POSITION-PROPORTIONAL minimap: primary
    // turns (user/assistant) are landmark nodes placed at their conversation scroll fraction, the
    // secondary/ambient steps are smaller texture interpolated between them (`computeSpinePositions`).
    // A faithful projection of the ONE ordered timeline; `data-item-id` anchors it to the reading
    // position (scroll-spy + click-jump, §13.1). §19.3 — the node size+opacity are the DECLARED
    // PROMINENCE_SCALE (set inline), not hand-CSS classes; the status colour stays inline (gate-clean).
    const items = this.mergedTimeline().filter(
      (it) =>
        it.kind === 'user' ||
        it.kind === 'assistant' ||
        it.kind === 'tool-activity' ||
        it.kind === 'progress' ||
        it.kind === 'error',
    );
    if (items.length === 0) return html`${nothing}`;
    const activeId = this.nav.activeId;
    // Tempdoc 565 §17 — the ONE run-step presentation descriptor per item; §19.3 — its declared
    // prominence weight.
    const pres = items.map((it) => stepPresentation(it));
    // §13/§19.3 — the terminal "Answer" peak is the LAST assistant message of each TURN. The agent loop
    // emits several assistant messages per turn (intermediate tool-call preambles + the final answer);
    // only the final one is the destination, so only it gets TERMINAL_NODE_WEIGHT. Earlier intermediate
    // assistants recede to `secondary` texture (and are relabelled below — they are not answers).
    const terminalIds = terminalAssistantIds(items);
    // Tempdoc 565 §26.B — the FIRST item of each workflow node is a segment-boundary landmark on the
    // spine (the node-graph structure §15.C flattened, made navigable). A run with no nodes has none.
    const segmentStartIds = new Set<string>();
    let prevNodeId: string | undefined;
    for (const it of items) {
      const nid = it.segment?.nodeId;
      if (nid && nid !== prevNodeId) segmentStartIds.add(it.id);
      prevNodeId = nid;
    }
    const weights = items.map((it, idx) => {
      if (it.kind === 'assistant') {
        return terminalIds.has(it.id) ? TERMINAL_NODE_WEIGHT : PROMINENCE_SCALE.secondary;
      }
      return PROMINENCE_SCALE[pres[idx]!.prominence];
    });
    // §19.4 — each node sits at its conversation scroll fraction: the minimap contract is that a dot
    // points to WHERE its content actually is. Turns anchor at their measured midpoint; intra-turn steps
    // interpolate between anchors (`computeSpinePositions`). There is deliberately NO even-spacing blend —
    // a faithful position map. A long answer legitimately leaves a marker-free stretch (the viewport box
    // marks the reading position there), and the de-overlap pass below is the ONLY adjustment (minimal,
    // collision-only — it preserves position except where two nodes would otherwise overlap).
    const fractions = computeSpinePositions(items, this.nav.fractions);
    // §19.4 placement facet (565 §19 / 559 Adaptivity) — de-overlap the ideal fractions so dense runs
    // don't pile nodes onto the same point (the measured-audit defect). Convert to px against the
    // measured track height, then space with the min-separation primitive (order-preserving, minimal
    // displacement). Before the track is measured (first paint / jsdom) trackPx is 0 → fall back to the
    // %-based ideal placement (graceful, like the sibling adaptive primitives).
    const PX_PER_REM = 16;
    const trackPx = this.nav.trackPx;
    const tops: string[] =
      trackPx > 0
        ? computeSpacedPositions(
            fractions.map((f) => f * trackPx),
            weights.map((w) => w.sizeRem * PX_PER_REM),
            trackPx,
            2,
          ).map((px) => `${px.toFixed(2)}px`)
        : fractions.map((f) => `${(f * 100).toFixed(2)}%`);
    // §13 Pillar A binding — the spine is an operable nav (keyboard-operable buttons with accessible
    // names → controls-a11y-clean); click/Enter jumps the reading column to that timeline item, and the
    // scroll-spy marks the in-view node `.active`.
    // §13/§19 — the viewport indicator: a decorative box marking the slice of the conversation on
    // screen, so the long answer body is navigable and the full track reads as a map (the spatial
    // "reading position" binding §13 specified). Drawn behind the operable nodes; hidden when nothing
    // scrolls. The active-item ring stays the per-item cue; this is the where-in-the-scroll cue.
    const vp = this.nav.viewport;
    return html`<nav class="run-spine" aria-label="Run timeline — jump to a turn">
      ${vp
        ? html`<div
            class="run-spine-viewport"
            role="scrollbar"
            tabindex="0"
            aria-controls="run-conversation"
            aria-orientation="vertical"
            aria-label="Scroll the conversation"
            aria-valuemin="0"
            aria-valuemax="100"
            aria-valuenow=${Math.round(vp.topFrac * 100)}
            style=${`top:${(vp.topFrac * 100).toFixed(2)}%;height:${((vp.botFrac - vp.topFrac) * 100).toFixed(2)}%`}
            @pointerdown=${this.onSpineThumbPointerDown}
            @pointermove=${this.onSpineThumbPointerMove}
            @pointerup=${this.onSpineThumbPointerUp}
            @pointercancel=${this.onSpineThumbPointerUp}
            @keydown=${this.onSpineThumbKeyDown}
          >
          </div>`
        : nothing}
      ${items.map((it, idx) => {
        // The button owns placement/size/active/jump; <jf-run-node> owns the glyph+tone visual
        // (density `minimal` → a clean tone-dot at this scale, §19.2).
        const p = pres[idx]!;
        const w = weights[idx]!;
        // Tempdoc 565 §26.B — a segment-boundary node names its workflow node ("Node: think — jump").
        const isBoundary = segmentStartIds.has(it.id);
        // Intermediate assistant messages are NOT the answer — only the terminal one per turn reads
        // "Answer" (the prominence demotion is in `weights` above; this is the matching label).
        const label = isBoundary
          ? `Node: ${it.segment?.label ?? it.segment?.nodeId ?? 'step'} — jump`
          : it.kind === 'assistant' && !terminalIds.has(it.id)
            ? 'Working step'
            : p.label || spineNodeLabel(it);
        const style = `top:${tops[idx]};--node-size:${w.sizeRem}rem;opacity:${w.opacity}`;
        // Tempdoc 565 §30 — a human STEERING directive (the DIRECTION authority's interject) is a
        // human-origin POINT landmark on the spine, marked so it reads distinctly from agent steps.
        const isSteer = it.attributes?.steer === true;
        // Tempdoc 565 §29 Tier-2 — a run-health tick: an error step gets a distinct marker so a glance
        // at the spine shows which nodes failed (the error tone is already projected; this names it).
        const isError = it.kind === 'error';
        const spineLabel = isSteer
          ? `Your direction: ${it.content} — jump`
          : isError
            ? `Error: ${it.content.slice(0, 50)} — jump`
            : label;
        return html`<button
          type="button"
          class="run-spine-node ${it.id === activeId ? 'active' : ''} ${isBoundary
            ? 'node-boundary'
            : ''} ${isSteer ? 'steer-landmark' : ''} ${isError ? 'has-error' : ''}"
          style=${style}
          data-item-id=${it.id}
          ?data-steer=${isSteer}
          title=${spineLabel}
          aria-label=${spineLabel}
          @click=${() => this.nav.jumpTo(it.id)}
        >
          <jf-run-node density="minimal" .presentation=${p}></jf-run-node>
        </button>`;
      })}
    </nav>`;
  }

  /**
   * Tempdoc 565 §19.4 — project each timeline item to its 0..1 vertical position in the minimap. Items
   * with a conversation anchor (user/assistant turns, measured into `this.nav.fractions`) sit at their
   * scroll fraction; the intra-turn steps (tool/progress/error — which live inside a turn's collapsible
   * trace and have no independent scroll position) are interpolated evenly between the surrounding
   * anchors, so they read as texture between the landmarks. With no measurement yet (first paint / jsdom)
   * every item is unanchored → an even spread over 0..1, a graceful default.
   */
  // §21 AFFORDANCE — the minimap-as-scrollbar pointer/keyboard handlers. Bound arrow FIELDS so the
  // template references them without an inline `=>` (a `>` in the attribute would truncate the
  // controls-a11y tag scan); the scroll math lives in the NavigationController (Spike A's exact mapping).
  private readonly onSpineThumbPointerDown = (e: PointerEvent): void => {
    const el = e.currentTarget as HTMLElement;
    el.setPointerCapture?.(e.pointerId);
    this.nav.beginDrag(e.clientY);
    e.preventDefault(); // don't let the grab start a text selection / native scroll …
    el.focus?.({ preventScroll: true }); // … but still focus the thumb so arrow-keys work after a grab
  };
  private readonly onSpineThumbPointerMove = (e: PointerEvent): void => {
    const el = e.currentTarget as HTMLElement;
    if (!el.hasPointerCapture?.(e.pointerId)) return; // act only while a drag is captured
    this.nav.dragTo(e.clientY);
  };
  private readonly onSpineThumbPointerUp = (e: PointerEvent): void => {
    (e.currentTarget as HTMLElement).releasePointerCapture?.(e.pointerId);
  };
  private readonly onSpineThumbKeyDown = (e: KeyboardEvent): void => {
    const map: Record<string, 'line-up' | 'line-down' | 'page-up' | 'page-down' | 'home' | 'end'> = {
      ArrowUp: 'line-up',
      ArrowDown: 'line-down',
      PageUp: 'page-up',
      PageDown: 'page-down',
      Home: 'home',
      End: 'end',
    };
    const kind = map[e.key];
    if (!kind) return;
    e.preventDefault(); // the thumb owns these keys (don't also scroll the page)
    this.nav.nudge(kind);
  };

  /**
   * Tempdoc 561 P-A/P-B (Slice 2): refresh the unified thread from the canonical record. Best-effort
   * — failures leave the live this.thread render in place (the projector never becomes an authority).
   */
  private async refreshUnifiedThread(): Promise<void> {
    if (!this.sessionId) return;
    const res = await fetchUnifiedThread(this.apiBase, this.sessionId);
    this.unifiedEvents = res.events;
    this.unifiedLifecycles = res.lifecycles;
    this.hydrateAnswerEvidenceFromRecord(res.events);
    this.requestUpdate();
  }

  /**
   * Tempdoc 565 §3.A/persistence — on (re)load the live controller carries no evidence (loadConversation
   * rebuilds role/content only). Rehydrate the answer's sources/citations from the latest persisted agent
   * assistant message so the Sources pane + the "Sources · N" affordance render from the record. The
   * record is the single authority; this never invents data.
   */
  private hydrateAnswerEvidenceFromRecord(events: readonly ThreadEvent[]): void {
    for (let i = events.length - 1; i >= 0; i--) {
      const e = events[i];
      const sources = e?.attributes?.sources;
      if (e?.kind === 'ASSISTANT_MESSAGE' && Array.isArray(sources) && sources.length > 0) {
        this.agentCtrl?.hydrateAnswerEvidence(
          sources as AgentSource[],
          Array.isArray(e.attributes.citations) ? (e.attributes.citations as AgentSentenceCite[]) : [],
        );
        return;
      }
    }
  }

  /**
   * Tempdoc 561 P-B (body-unification) + surface tier: the inline agent run uses the ONE shared
   * controller (agentSessionStore) — the same instance the retrospective drawer reads, so the
   * window and the panels project the same records. The window subscribes for re-render; it does
   * NOT own/destroy the shared controller.
   */
  private ensureAgentCtrl(): AgentSessionController {
    const ctrl = getAgentSessionController(this.apiBase, this.host_);
    ctrl.conversationId = this.sessionId ?? null;
    if (!this.agentSessionUnsub) {
      this.agentSessionUnsub = subscribeAgentSession(() => this.requestUpdate());
    }
    this.agentCtrl = ctrl;
    // Tempdoc 577 §2.14 Root I (#1d) — one-shot cross-tab reattach: the FIRST time this tab mounts the
    // agent surface, discover a live run started in ANOTHER tab (via the shared activeRunPointer) and
    // attach to it. Guarded both here (one-shot) and inside reattachActiveRunOnLoad (skips if this tab
    // already owns/observes a run), and scheduled off the render via microtask.
    if (!this.reattachChecked) {
      this.reattachChecked = true;
      queueMicrotask(() => void ctrl.reattachActiveRunOnLoad());
    }
    return ctrl;
  }

  /**
   * Tempdoc 565 §3.C follow-up — resolve the agent answer's per-sentence citations (`AgentSentenceCite`)
   * against its grounding sources (`AgentSource`) into the markdown block's `MarkdownCitation[]`. The
   * `[n]` label is the source's 1-based position so it cross-references the Sources pane; the deep-link
   * detail reuses the same `citation-select` contract the Sources pane and RAG path use.
   */
  private agentAnswerCitations(): Citation[] {
    return this.resolveAnswerCitations(
      this.agentCtrl?.answerSources ?? [],
      this.agentCtrl?.answerCitations ?? [],
    );
  }

  /** Resolve the AGENT path's per-sentence cites against its sources into the one `Citation` shape.
   *  Tempdoc 577 Phase 1 — body extracted to the shared {@link resolveAgentAnswerCitations} so the
   *  Inspector's Answer tab resolves through the same authority (no fork of the mapping). */
  private resolveAnswerCitations(
    sources: readonly AgentSource[],
    cites: readonly AgentSentenceCite[],
  ): Citation[] {
    return resolveAgentAnswerCitations(sources, cites);
  }

  /**
   * Tempdoc 565 §15.B — resolve the RAG path's grounded `Claim`s against its `RetrievalCitation`
   * sources into the SAME `Citation` shape the agent path uses, so both render through the one
   * `MarkdownBlock` weave. Mirrors the (now-retired) `cite-ref-click` source-index lookup, so RAG marks
   * gain the deep-link + cross-surface selection they previously lacked. Ungrounded sentences
   * (`sourceRefs` empty) get no mark — they render as neutral prose (the medium-appropriate take on the
   * flat-text dimming). The matcher already filtered to grounded sentences via the §15.A cutoff.
   */
  private resolveClaimCitations(
    claims: readonly Claim[],
    sources: readonly RetrievalCitation[],
  ): Citation[] {
    return claimsToCitations(claims, sources);
  }

  /**
   * Tempdoc 561 P-A/P-B (Slice 3): the secondary "Activity" rail — the agent chrome (budget readout)
   * demoted to a collapsible rail so the conversation stays primary. Hidden until a run reports
   * budget. Timeline / History / Sessions remain reachable in the agent affordance's <jf-agent-view>.
   */
  private renderActivityRail(): TemplateResult {
    // Tempdoc 561 P-B (body-unification): budget comes from the inline-hosted controller now.
    // Tempdoc 577 Move 1 — the controller is a shared singleton; its budget belongs to the run it
    // last served. Project it only when that run is THIS conversation's (accountability dies with
    // its run — the stale-budget defect's second leak path).
    const ctrlBudgetIsOurs =
      this.agentCtrl != null && this.agentCtrl.conversationId === this.sessionId;
    const latestBudget =
      ctrlBudgetIsOurs && this.agentCtrl!.budgetUpdates.length > 0
        ? this.agentCtrl!.budgetUpdates[this.agentCtrl!.budgetUpdates.length - 1]!
        : this.agentBudget;
    const budget = projectBudget(latestBudget);
    // Tempdoc 577 §2.14 Root II (#14) — the COGNITIVE-headroom sibling of the economic budget: how
    // full the model's context window is (occupancy ÷ n_ctx), so "ran out of memory" reads distinct
    // from "ran out of money". Only the `llm_response` phase carries occupancy; `iteration_start`
    // events carry promptTokens 0, so reading the ABSOLUTE-latest event would null the horizon
    // between iterations and make the meter flicker. Project from the last budget update that
    // actually carries occupancy, so the meter persists the last known fullness across iterations.
    const latestOccupancy =
      ctrlBudgetIsOurs && this.agentCtrl!.budgetUpdates.length > 0
        ? [...this.agentCtrl!.budgetUpdates]
            .reverse()
            .find((b) => (b.promptTokens ?? 0) > 0 && (b.contextWindow ?? 0) > 0)
        : undefined;
    const horizon = projectContextHorizon(latestOccupancy ?? latestBudget);
    // Tempdoc 561 P-A/P-A2: the latest agent run's typed loop object (state + Turn/Iteration counts).
    const lc =
      this.unifiedLifecycles.length > 0
        ? this.unifiedLifecycles[this.unifiedLifecycles.length - 1]
        : null;
    // Tempdoc 561 C-2: in agent mode the rail (action-plane chrome) is always present, naming the
    // approval posture in its summary — even before a run reports budget. Outside agent mode it stays
    // hidden until a run produces budget/lifecycle (the prior behaviour).
    const agentMode = this.affordance === 'agent';
    if (!agentMode && !budget && !lc) return html`${nothing}`;
    const approvalPosture = postureChrome(
      agencyPosture(this.affordance, getAutonomyLevel()),
    ).approvalPosture;
    // Tempdoc 577 Ext III — the live run's accountability record, not "Activity" (that name belongs
    // to the retrospective; two records, two names). The summary separates live STATUS from posture
    // POLICY grammatically ("Policy: …"); the budget states its unit (tokens) and ceiling; an
    // over-budget state escalates WITH its remedies (halt / raise) through the one control seam.
    return html`
      <details class="activity-rail">
        <summary>
          This run${agentMode && approvalPosture
            ? html` · <span class="posture-policy">Policy: ${approvalPosture}</span>`
            : nothing}${this.agentCtrl?.budgetGate
            ? html` · <span class="over-budget">Paused — awaiting budget</span>`
            : budget?.overBudget
              ? html` · <span class="over-budget">Over budget +${budget.overBy} tokens</span>`
              : nothing}
        </summary>
        ${/* Tempdoc 577 Move 2 — the HELD budget gate: the run is genuinely parked and waiting, so
              this row IS the decision point (continue +N / finalize / stop), all through the one
              control seam. This is the state in which the remedies are real, not decorative. */ ''}
        ${this.agentCtrl?.budgetGate
          ? html`<div class="activity-budget budget-gate-row">
              <span class="over-budget"
                >Paused: needs ~${this.agentCtrl.budgetGate.tokensNeeded} tokens, ${Math.max(
                  0,
                  this.agentCtrl.budgetGate.tokensRemaining,
                )} left</span
              >
              <span class="budget-actions">
                <button class="budget-action" @click=${() => this.onRaiseBudget()}>
                  Add ${RAISE_BUDGET_STEP_TOKENS} tokens
                </button>
                <button class="budget-action" @click=${() => this.onBudgetDecision('finalize')}>
                  Finish with what it has
                </button>
                <button class="budget-action" @click=${() => this.onBudgetDecision('stop')}>
                  Stop
                </button>
              </span>
            </div>`
          : nothing}
        ${/* Tempdoc 577 §2.14 Root II — the HELD context-pressure gate: the prompt is approaching
              the model's memory (n_ctx). The decision offers COMPACTION (the option the budget gate
              lacks): continue anyway / compact older turns / stop, through the one control seam. */ ''}
        ${this.agentCtrl?.contextGate
          ? html`<div class="activity-budget context-gate-row" data-testid="context-gate">
              <span class="over-budget"
                >Context filling up: ${this.agentCtrl.contextGate.promptTokens} of
                ${this.agentCtrl.contextGate.contextWindow} tokens</span
              >
              <span class="budget-actions">
                <button class="budget-action" @click=${() => this.onContextDecision('summarize')}>
                  Compact older turns
                </button>
                <button class="budget-action" @click=${() => this.onContextDecision('continue')}>
                  Continue anyway
                </button>
                <button class="budget-action" @click=${() => this.onContextDecision('stop')}>
                  Stop
                </button>
              </span>
            </div>`
          : nothing}
        ${lc
          ? html`<div class="activity-lifecycle">
              ${lc.turns} turn${lc.turns === 1 ? '' : 's'} · ${lc.iterations}
              iteration${lc.iterations === 1 ? '' : 's'} · ${lc.toolCalls}
              tool${lc.toolCalls === 1 ? '' : 's'} ·
              ${(lc.actors?.length ?? 1) > 1
                ? html`${lc.actors.length} agents ·`
                : nothing}
              <span class="lifecycle-state">${lc.state}</span>
            </div>`
          : nothing}
        ${budget
          ? html`<div class="activity-budget">
              ${budget.overBudget
                ? html`<span class="over-budget"
                      >Over budget by ${budget.overBy} tokens (granted ${budget.ceiling})</span
                    >
                    ${/* Tempdoc 577 Ext III — control chrome attaches to the LIVE run: the backend
                          evicts a finished session, so the remedies render only while the stream is
                          in flight (a finished over-budget run is a fact, not an actionable state —
                          live-validation finding, the 404 case). */ ''}
                    ${this.agentCtrl?.runInFlight
                      ? html`<span class="budget-actions">
                          <button
                            class="budget-action"
                            @click=${() => this.onRaiseBudget()}
                          >
                            Add ${RAISE_BUDGET_STEP_TOKENS} tokens
                          </button>
                          <button class="budget-action" @click=${() => this.onHaltRun()}>
                            Stop run
                          </button>
                        </span>`
                      : nothing}`
                : html`<span
                    >Tokens: ${budget.consumed} of ${budget.ceiling} used ·
                    ${budget.remaining} left</span
                  >`}
              <div class="budget-bar">
                <div class="budget-bar-fill" style=${`width:${budget.pct}%`}></div>
              </div>
            </div>`
          : nothing}
        ${/* Tempdoc 577 §2.14 Root II (#14) — the context-headroom meter: the COGNITIVE sibling of
              the economic budget above. Distinguishes running out of money (budget) from running out
              of memory (context window). Omitted when the model's n_ctx isn't on the wire. */ ''}
        ${horizon
          ? html`<div class="activity-budget activity-context">
              <span
                >Context: ${horizon.occupancy} of ${horizon.window} tokens (${horizon.pct}%)</span
              >
              <div class="budget-bar" role="meter" aria-label="Context window used"
                aria-valuenow=${horizon.pct} aria-valuemin="0" aria-valuemax="100">
                <div
                  class="budget-bar-fill context-fill-${horizon.color}"
                  style=${`width:${horizon.pct}%`}
                ></div>
              </div>
            </div>`
          : nothing}
      </details>
    `;
  }

  /** Tempdoc 577 Ext III — the raise-budget remedy, dispatched through the one control seam. */
  private onRaiseBudget(): void {
    const ctrl = this.agentCtrl;
    if (!ctrl) return;
    void dispatchRunControl(ctrl, {
      kind: 'raise-budget',
      addTokens: RAISE_BUDGET_STEP_TOKENS,
    });
  }

  /** Tempdoc 577 Ext III — the halt remedy on the over-budget row (the existing seam directive). */
  private onHaltRun(): void {
    const ctrl = this.agentCtrl;
    if (!ctrl) return;
    void dispatchRunControl(ctrl, { kind: 'halt' });
  }

  /** Tempdoc 577 Move 2 — resolve the held budget gate (finalize | stop), through the one seam. */
  private onBudgetDecision(decision: 'finalize' | 'stop'): void {
    const ctrl = this.agentCtrl;
    if (!ctrl) return;
    void dispatchRunControl(ctrl, { kind: 'budget-decision', decision });
  }

  /** Tempdoc 577 §2.14 Root II — resolve the held context gate (continue | summarize | stop). */
  private onContextDecision(decision: 'continue' | 'summarize' | 'stop'): void {
    const ctrl = this.agentCtrl;
    if (!ctrl) return;
    void dispatchRunControl(ctrl, { kind: 'context-decision', decision });
  }

  /**
   * Tempdoc 565 §12 Phase 2 — the SINGLE ordered run projection + render. The record
   * ({@link projectUnifiedThread}) and the live agent run ({@link projectLiveAgentActivity}) project
   * into the SAME {@link UnifiedTurnItem} contract, merge into one timestamp-ordered timeline, and
   * render through the ONE {@link renderUnifiedItem} — retiring the old fork (a record renderer +
   * a parallel `renderLiveAgentActivity` with its own `renderAgentEntry`). The live items are deduped
   * against the record (tool by callId, message by `kind+content`); the record WINS because it is
   * terminal-only (§12.10), so the dedup is permanent and the two halves can never double-render.
   * The in-flight streaming answer is the timeline's tail (not a discrete record event) and is pinned
   * last. In non-agent (chat/RAG) mode there is no live agent run, so this renders the record alone.
   *
   * <p>Tempdoc 565 §12.3.D — extracted as the ONE shared timeline source so the centre conversation AND
   * the left run-spine project from the same merge (they can never diverge).
   */
  /**
   * Tempdoc 621 Phase 4 — the live/record reconciliation, moved OUT of render and INTO the merge
   * authority. Previously `renderUnifiedItem` reached into `this.thread` AT RENDER TIME to "prefer the
   * fresher live message" — the 610 §F.3 cross-source render-time reconciliation, the proven divergence
   * mechanism. Now {@link mergedTimeline} attaches the matched live {@link ThreadMessage} to the record
   * item ONCE (`attributes.live`); the renderer reads it and never re-derives. The match rules are
   * byte-identical to the former render-time logic: a USER turn matches by stable id; an ASSISTANT turn
   * matches by content AND only when the live message carries fresher evidence (sources/claims) — on
   * reload the rebuilt live thread has none, so the record renders (the reload-durability case).
   */
  private attachLiveMatch(it: UnifiedTurnItem): UnifiedTurnItem {
    if (it.kind === 'user') {
      const live = this.thread.find((m) => m.role === 'user' && m.id === it.id);
      return live ? { ...it, attributes: { ...it.attributes, live } } : it;
    }
    if (it.kind === 'assistant') {
      const live = this.thread.find((m) => m.role === 'assistant' && m.content === it.content);
      const hasEvidence =
        !!live && (((live.sources?.length ?? 0) > 0) || ((live.claims?.length ?? 0) > 0));
      return live && hasEvidence ? { ...it, attributes: { ...it.attributes, live } } : it;
    }
    return it;
  }

  private mergedTimeline(): UnifiedTurnItem[] {
    // The reconciliation is computed here (the one merge authority), not at render time (621 Phase 4).
    const recordItems = projectUnifiedThread(this.unifiedEvents).map((it) => this.attachLiveMatch(it));
    const ctrl = this.agentCtrl;
    const liveItems =
      this.affordance === 'agent' && ctrl
        ? projectLiveAgentActivity(ctrl.conversation, ctrl.toolCalls, {
            sources: ctrl.answerSources,
            citations: ctrl.answerCitations,
          })
        : [];

    // Dedup the live overlay against the record (record wins — it is terminal-only, §12.10). Messages
    // dedup by (kind, content) because live/record ids are different spaces — but OCCURRENCE-AWARE (fix
    // D): a live message is deduped only against an UNUSED record occurrence, so two identical
    // consecutive turns ("ok" / "ok") no longer collapse to one.
    const recordedCallIds = new Set<string>();
    const recordedContentCount = new Map<string, number>();
    for (const it of recordItems) {
      if (it.kind === 'tool-activity') {
        const cid = typeof it.attributes.callId === 'string' ? it.attributes.callId : it.id;
        recordedCallIds.add(cid);
      } else if (it.kind === 'user' || it.kind === 'assistant') {
        const k = `${it.kind} ${it.content}`;
        recordedContentCount.set(k, (recordedContentCount.get(k) ?? 0) + 1);
      }
    }
    const usedContent = new Map<string, number>();
    const liveOnly = liveItems.filter((it) => {
      if (it.kind === 'tool-activity') {
        const cid = typeof it.attributes.callId === 'string' ? it.attributes.callId : it.id;
        return !recordedCallIds.has(cid);
      }
      if (it.kind === 'user' || it.kind === 'assistant') {
        const k = `${it.kind} ${it.content}`;
        const used = usedContent.get(k) ?? 0;
        if (used < (recordedContentCount.get(k) ?? 0)) {
          usedContent.set(k, used + 1); // consume one record occurrence → this live turn is already recorded
          return false;
        }
        return true; // no unused record occurrence remains → keep (e.g. a 2nd identical turn)
      }
      return true; // progress / handoff / error are ephemeral — never in the record
    });

    const ordered = [...recordItems, ...liveOnly].sort(
      (a, b) => a.ts - b.ts || a.id.localeCompare(b.id),
    );

    // Tempdoc 577 §2.14 Root I (#19) — the run/session boundary seam: when a thread is RESTORED
    // (the record carries prior turns) and a new run continues live, the seam marks where the
    // persisted history ends and this session's live run begins, so a resumed thread does not read
    // as one continuous exchange (it also surfaces §2.11 #3's evidence-loss boundary). Computed here
    // (the one merge authority knows record-vs-live origin); the renderer reads it, never re-derives.
    // No seam when the timeline is all-record (nothing live yet) or all-live (a fresh run).
    // A new run ALWAYS begins with a USER turn, so the seam is the first live USER item that
    // follows record content. Anchoring on user-kind (not just any first live item) avoids the
    // mid-turn false positive: when a live answer fails to dedup against the reconciled record
    // answer, the user turn is a record item and the answer is the first live item — keying on the
    // answer would draw "resumed · new run" BETWEEN a question and its own answer. A non-user first
    // live item is in-turn reconciliation drift, never a run boundary. (Residual: a new user turn
    // already reconciled into the record shows no seam — a missing seam beats a false one; a fuller
    // structural fix via an explicit per-item run id is deferred.)
    const liveIds = new Set(liveOnly.map((it) => it.id));
    let seamId: string | null = null;
    let sawRecord = false;
    for (const it of ordered) {
      if (liveIds.has(it.id)) {
        if (sawRecord && it.kind === 'user') seamId = it.id;
        break;
      }
      sawRecord = true;
    }
    this.resumeSeamId = seamId;
    return ordered;
  }

  /** Tempdoc 565 §12 — render the merged timeline (centre conversation) + the in-flight streaming tail. */
  /**
   * Tempdoc 565 §26.C — fetch the workflow catalog the picker projects (replacing the hardcoded
   * `WORKFLOW_ID`). Defaults the selection to the first entry so the Run button is immediately usable.
   */
  private async loadWorkflows(): Promise<void> {
    const entries = await fetchWorkflowCatalog(this.apiBase);
    this.workflows = entries;
    if (this.selectedWorkflowId === null && entries.length > 0) {
      this.selectedWorkflowId = entries[0]!.id;
    }
    this.requestUpdate();
  }

  /** Tempdoc 565 §26.C — the human label for a workflow, via the ONE display projector (present()). */
  private workflowLabel(w: WorkflowCatalogEntry): string {
    return presentLabel({ kind: 'workflow', id: w.id, labelKey: w.presentation.labelKey });
  }

  /**
   * Tempdoc 565 §26.C — the workflow PICKER + RUN affordance (replaces §15.C's single-id trigger). Lists
   * the fetched catalog; selecting one and clicking Run streams it through the unified controller into the
   * one window's run authority (a >1-node workflow then renders as labelled node segments, §26.A/§26.B).
   */
  private renderWorkflowTrigger(): TemplateResult {
    const selected = this.selectedWorkflowId;
    const chosen = this.workflows.find((w) => w.id === selected) ?? this.workflows[0];
    return html`<div class="workflow-trigger">
      <label class="workflow-picker-label" for="workflow-picker">Workflow</label>
      <select
        id="workflow-picker"
        class="workflow-picker"
        aria-label="Choose a workflow to run"
        @change=${(e: Event) => {
          this.selectedWorkflowId = (e.target as HTMLSelectElement).value;
          this.requestUpdate();
        }}
      >
        ${this.workflows.length === 0
          ? html`<option disabled selected>No workflows available</option>`
          : this.workflows.map(
              (w) => html`<option value=${w.id} ?selected=${w.id === (chosen?.id ?? '')}>
                ${this.workflowLabel(w)} · ${w.nodes.length} node${w.nodes.length === 1 ? '' : 's'}
              </option>`,
            )}
      </select>
      <button
        class="new-chat-btn"
        ?disabled=${!chosen}
        @click=${() => {
          const id = this.selectedWorkflowId ?? chosen?.id;
          if (!id) return;
          this.workflowPending = false;
          void this.ensureAgentCtrl().runWorkflow(id);
        }}
      >
        Run workflow
      </button>
    </div>`;
  }

  /**
   * Tempdoc 577 Goal 3 (§3.2) — the retrieve base tier. The ONE window's lowest intent tier:
   * pure search (the ephemeral hit-list) reading the FE `searchState` store directly — NOT an LLM
   * conversation shape (§3.3). The hit-list owns no thread history; escalation to Documents (Ask,
   * grounded) or Agent (Delegate, run) via the affordance bar is what promotes intent to a turn.
   * Rendered in the conversation-zone in place of the chat thread while `affordance === 'retrieve'`.
   */
  private renderRetrieveTier(): TemplateResult {
    const s = this.searchSnapshot;
    const q = (s?.query ?? '').trim();
    if (!q) {
      return html`<div class="retrieve-tier retrieve-empty" data-testid="retrieve-empty-prompt">
        <p>
          Search your files — type above for instant results. Then escalate:
          <strong>Documents</strong> to ask a grounded question, or <strong>Agent</strong> to
          delegate a task.
        </p>
      </div>`;
    }
    if (s?.error) {
      return html`<div class="retrieve-tier">
        <div class="error" data-testid="retrieve-error">${s.error}</div>
      </div>`;
    }
    const results = s?.results ?? [];
    // Tempdoc 597 R-1 — project the SAME funnel count label as the dedicated Search surface (the one
    // shared `matchCountLabel` helper, off the same `searchState`), so the two surfaces can never
    // report different counts for one query. Was the bounded window-as-count (`totalHits`).
    const countLabel = matchCountLabel(
      s?.matchCount ?? 0,
      results.length,
      s?.searchTrace?.effectiveMode === 'VECTOR',
      s?.totalHits ?? 0,
      s?.facetsTruncated ?? false,
    );
    return html`<div class="retrieve-tier" data-testid="retrieve-tier">
      <div class="retrieve-meta">
        ${results.length === 0 && s?.isSearching
          ? html`<span>Searching…</span>`
          : results.length === 0
            ? html`<span>No matches for "${q}"</span>`
            : html`<span
                >${countLabel}${s?.isRefining ? ' · refining…' : nothing}</span
              >`}
      </div>
      ${/* §3.9a — the same shared facet chips the standalone surface renders; toggling re-runs
            the search through the one searchState seam (buildSearchIntent reads the selections). */ ''}
      ${renderFacetChips(s?.facets, this.facetSelections, {
        onToggle: (field, value) => this.handleRetrieveFacetToggle(field, value),
      })}
      <div class="retrieve-results" role="list" aria-label="Search results">
        ${results.map((hit) => this.renderRetrieveRow(hit, q))}
      </div>
    </div>`;
  }

  /** §3.9a — toggle a facet then re-run through the one searchState seam (picks up selections). */
  private handleRetrieveFacetToggle(field: string, value: string): void {
    toggleFacetValue(field, value);
    if ((this.searchSnapshot?.query ?? '').trim().length > 0) submitSearch();
  }

  /**
   * One ephemeral hit row — typed via the shared `projectResultView` (Goal 1 Move B), with the
   * shared per-hit "Why this result?" disclosure (§3.9a). The row is a container `<div>` (not a
   * `<button>`) so the disclosure's own buttons are valid siblings, not nested interactives; the
   * open action is a keyboard-operable button over the title/path/snippet.
   */
  private renderRetrieveRow(hit: SearchHit, query: string): TemplateResult {
    const view = projectResultView(hit as unknown as ResultViewInput);
    return html`<div class="retrieve-row" role="listitem" data-testid="retrieve-result-row" data-kind=${view.kind}>
      <button type="button" class="retrieve-row-open" @click=${() => this.openRetrieveHit(hit)}>
        <span class="retrieve-row-title">
          <span class="kind-icon" aria-hidden="true">${icon({ name: view.icon, size: 13 })}</span>
          <span class="title-text">${view.title}</span>
          ${view.kind === 'code' && view.approxLine != null
            ? html`<span class="line-anchor">:L${view.approxLine}</span>`
            : nothing}
        </span>
        ${/* Tempdoc 602 R3 — same formatted path (middle-ellipsis) the Search surface shows;
              raw path stays in the title attribute for hover. */ ''}
        <span class="retrieve-row-path" title=${view.path}>${formatDisplayPath(view.path)}</span>
        ${view.snippet
          ? html`<span class="retrieve-row-snippet">${highlightTerms(view.snippet, query)}</span>`
          : nothing}
      </button>
      ${renderWhyDisclosure(hit)}
    </div>`;
  }

  /** Open a hit through the shared host inspector seam (same path SearchSurface uses). */
  private openRetrieveHit(hit: SearchHit): void {
    const host = this.host_;
    if (!host?.search || !host?.ui) return;
    // Tempdoc 580 §17 P3 — the live search surface's OPENED disposition. This is the
    // retrieve-tier twin of SearchSurface.handleClick: opening a hit here is the same
    // positive outcome signal, so it feeds the one canonical disposition stream (carrying
    // the live search's interactionId from searchState) for the §17.4 snapshot join.
    recordOpenDisposition(hit.id);
    host.ui.showInspector(
      host.search.hitToSelectedItem(hit as unknown as import('../plugin-api/plugin-types.js').SearchHitSnapshot),
    );
  }

  /**
   * Tempdoc 629 (LAYER) — the conversation store is encrypted + locked (history returned 423). Render a
   * clear notice + an Unlock affordance routing to the Settings "Chat encryption" control (the validated
   * unlock path), NOT an empty transcript (§L4: locked must never look deleted). Search/index stay usable
   * (they are not encrypted), so only the transcript pane is gated.
   */
  private renderHistoryLocked(): TemplateResult {
    // Tempdoc 629 (#3): the wording + remedy come from the ONE CAUSE_ROWS authority (reasonFor), not
    // hardcoded here — so the locked-chat gate speaks the same vocabulary as every other readiness cause.
    const r = reasonFor('conversations.locked');
    const nav = r.remedy?.kind === 'navigate' ? r.remedy : null;
    return html`
      <div class="history-locked" role="status">
        <p>${icon({ name: 'shield', size: 16 })} <strong>${r.wording}</strong>.</p>
        <p class="help">Unlock it to read your chat history — your search index is unaffected.</p>
        ${nav
          ? html`<jf-button .onActivate=${() => requestSurfaceNavigation(nav.target)}>
              ${icon({ name: 'shield', size: 14 })} ${nav.label}
            </jf-button>`
          : nothing}
      </div>
    `;
  }

  /**
   * Tempdoc 585 §D Phase 1 (C1) — the run-replay scrubber. Shown only while the shared controller is
   * in replayMode; play/step/seek call the controller's replay primitives, which re-apply the
   * persisted events through the SAME per-event handlers + projection the live thread uses (the four
   * live-only side-effects are suppressed in replayMode). "Exit replay" returns to an idle controller.
   */
  private renderReplayBar(): TemplateResult {
    const ctrl = this.agentCtrl;
    if (!ctrl) return html`${nothing}`;
    const total = ctrl.replayTotal;
    const cursor = ctrl.replayCursor;
    return html`
      <div class="replay-bar" role="group" aria-label="Run replay controls">
        <span class="replay-label">Replaying past run · ${cursor}/${total}</span>
        <button
          class="replay-btn"
          aria-label="Step back one event"
          ?disabled=${cursor <= 0}
          @click=${() => ctrl.replayStepBack()}
        >
          ◀
        </button>
        <input
          class="replay-slider"
          type="range"
          min="0"
          max=${total}
          .value=${String(cursor)}
          aria-label="Replay position"
          @input=${(e: Event) => ctrl.replaySeek(Number((e.target as HTMLInputElement).value))}
        />
        <button
          class="replay-btn"
          aria-label="Step forward one event"
          ?disabled=${cursor >= total}
          @click=${() => ctrl.replayStepForward()}
        >
          ▶
        </button>
        <button
          class="replay-fork"
          title="Branch a new run from this one — edit the question and re-run"
          @click=${() => {
            this.forkEditing = !this.forkEditing;
          }}
        >
          Fork &amp; edit
        </button>
        <button class="replay-exit" @click=${() => ctrl.exitReplay()}>Exit replay</button>
      </div>
      ${this.forkEditing ? this.renderForkEditor() : nothing}
    `;
  }

  /**
   * Tempdoc 585 §D Phase 3 (C2) — the inline fork editor: edit the run's last question and branch a
   * NEW run from it. Submitting drives {@link AgentSessionController.forkRun} (which leaves replay and
   * streams the fresh run); a blank box re-rolls the original question.
   */
  private renderForkEditor(): TemplateResult {
    const ctrl = this.agentCtrl;
    const run = (): void => {
      const id = ctrl?.sessionId;
      if (!ctrl || !id) return;
      const text = this.forkDraft;
      this.forkEditing = false;
      this.forkDraft = '';
      void ctrl.forkRun(id, text);
    };
    return html`<div class="fork-editor" role="group" aria-label="Fork the run from here">
      <textarea
        class="fork-input"
        aria-label="Edited question for the forked run"
        placeholder="Edit the question and re-run (leave blank to re-roll the original)…"
        .value=${this.forkDraft}
        @input=${(e: Event) => (this.forkDraft = (e.target as HTMLTextAreaElement).value)}
      ></textarea>
      <button class="fork-run" @click=${run}>Run fork</button>
    </div>`;
  }

  private renderUnifiedConversation(): TemplateResult {
    const merged = this.mergedTimeline();
    const ctrl = this.agentCtrl;
    return html`
      ${this.workflowPending ? this.renderWorkflowTrigger() : nothing}
      ${this.renderTimeline(merged)}
      ${ctrl?.streamingText
        ? html`<div class="message assistant">
            <jf-markdown-block
              .text=${ctrl.streamingText}
              .citations=${this.agentAnswerCitations()}
            ></jf-markdown-block>
            ${this.renderGroundingBadge(
              ctrl.streamingText,
              ctrl.answerSources ?? [],
              ctrl.answerCitations,
            )}
            ${this.renderSourceChips(ctrl.answerSources ?? [], '__live__')}
          </div>`
        : nothing}
    `;
  }

  /**
   * Tempdoc 565 §14 ④/⑤ — the grounding-honesty badge: ④ a "Grounded" readiness state (shown when the
   * answer carries sources) + ⑤ the "N of M sentences" coverage — BOTH a read of the one §15.A grounding
   * verdict ({@link groundingCoverage}). Surfaced beside the answer so its grounding is explicit, not
   * buried (the §14 honesty rule). Hidden for an answer with no sources (the RAG/plain path owns its own).
   */
  private renderGroundingBadge(
    answerText: string,
    sources: readonly AgentSource[],
    rawCitations: unknown,
  ): TemplateResult | typeof nothing {
    if (sources.length === 0) return nothing;
    const citations = Array.isArray(rawCitations) ? (rawCitations as AgentSentenceCite[]) : [];
    const cov = groundingCoverage(citations, answerText);
    // Tempdoc 603 D-4 — the SOURCED (provenance) state: the answer drew on these documents but they are
    // DOCUMENT-LEVEL (no chunk identity → the per-sentence matcher could not run), so there is no
    // "N of M sentences" verdict to give. Show provenance honestly — NEVER "Grounded · 0 of N" (the
    // over-confidence) — derived from the same authority predicate the frame uses, so badge + frame agree.
    if (cov.cited === 0 && !sourcesAreChunkPrecise(sources)) {
      const n = sources.length;
      return html`<details class="grounding-badge grounding-badge-sourced">
        <summary class="grounding-badge-summary" role="status">
          <span>Based on ${n} document${n === 1 ? '' : 's'}</span>
        </summary>
        <div class="grounding-why">
          <div>
            ${n === 1 ? 'This document was' : `These ${n} documents were`} retrieved and informed the
            answer, but per-sentence grounding was not verified — keyword-only retrieval returned whole
            documents, so each statement could not be tied to a specific passage.
          </div>
        </div>
      </details>`;
    }
    const uncited = Math.max(0, cov.total - cov.cited);
    // Tempdoc 577 §2.12 Move 4 — the answer plane's "Why uncited?" tier (§2.11 #7): a native
    // <details> disclosure (keyboard/AT-accessible by construction, no hover-only title) explaining
    // the breakdown and WHY the uncited sentences carry no mark. The search window got "Why this
    // result?"; this is its mirror for the answer.
    return html`<details class="grounding-badge">
      <summary class="grounding-badge-summary" role="status">
        <span class="grounding-dot" aria-hidden="true"></span>
        <span>Grounded · ${cov.cited} of ${cov.total} sentences</span>
      </summary>
      <div class="grounding-why">
        <div>${cov.grounded} strong + ${cov.weak} supporting of ${cov.total} sentences cite a source.</div>
        ${uncited > 0
          ? html`<div>
              ${uncited} sentence${uncited === 1 ? ' is' : 's are'} not backed by a retrieved
              passage above the match threshold — treat ${uncited === 1 ? 'it' : 'them'} as the
              model's own wording.
            </div>`
          : nothing}
      </div>
    </details>`;
  }

  /**
   * Tempdoc 565 §12.3.E + §13.8 P3 — the source-chip row under a grounded answer: one compact chip per
   * grounding source ([n] · filename), an ambient-grounding surface alongside the inline [n] marks and
   * the evidence rail. §13.8 makes it a COLLAPSIBLE "Sources · N" disclosure (mirroring
   * {@link CitationsPanel}'s "N sources" toggle) because the docked rail already owns the full source
   * detail — so the chips are an on-demand echo, not a redundant always-on third copy. Collapsed by
   * default when the wide rail is showing the same sources; expanded by default at narrow (no rail);
   * an explicit click pins the per-answer choice in {@link sourceChipsToggles}. All surfaces still
   * cross-highlight through the ONE {@link selectedSource} store, and a chip click reuses the existing
   * `citation-select` deep-link (mirrors {@link SourcesPane}'s card).
   */
  private renderSourceChips(sources: readonly AgentSource[], key: string): TemplateResult {
    if (!sources || sources.length === 0) return html`${nothing}`;
    // Structural default: collapsed when the wide rail shows the detail; expanded at narrow (no rail).
    const railShown = this.wideViewport;
    const open = this.sourceChipsToggles.get(key) ?? !railShown;
    const selected = getSelectedSource();
    const bodyId = `source-chips-${key}`;
    return html`<div class="source-disclosure">
      <button
        class="source-disclosure-summary"
        aria-expanded=${open ? 'true' : 'false'}
        aria-controls=${bodyId}
        aria-label=${`${open ? 'Hide' : 'Show'} answer sources (${sources.length})`}
        @click=${() => {
          this.sourceChipsToggles.set(key, !open);
          this.requestUpdate();
        }}
      >
        <span class="disclosure-chevron ${open ? 'open' : ''}" aria-hidden="true">▸</span>
        <span>Sources · ${sources.length}</span>
      </button>
      ${open
        ? html`<div class="source-chips" id=${bodyId} role="group" aria-label="Answer sources">
            ${sources.map((s, i) => {
              const name = s.title || filenameOf(s.path);
              const isSel = selected === sourceKey(s.parentDocId, s.startLine);
              const isHidden = getExcludedSources().has(sourceExcludeKey(s.parentDocId, s.chunkIndex));
              return html`<span class="source-chip-wrap ${isHidden ? 'hidden-source' : ''}">
                <button
                  class="source-chip ${isSel ? 'selected' : ''}"
                  aria-current=${isSel ? 'true' : 'false'}
                  aria-label=${`Source ${i + 1}: ${name} — open at line ${s.startLine}`}
                  title="Open ${s.path} at line ${s.startLine}"
                  @click=${() => this.onChipSelect(s)}
                ><span class="source-chip-n">${i + 1}</span
                  ><span class="source-chip-name">${name}</span></button>
                <button
                  class="source-exclude"
                  aria-label=${isHidden
                    ? `Restore ${name} to the assistant's retrieval`
                    : `Hide ${name} from the assistant's retrieval`}
                  title=${isHidden ? 'Restore to retrieval' : 'Hide from retrieval'}
                  @click=${() => void this.toggleSourceExcluded(s)}
                >${isHidden ? '↺' : '×'}</button>
              </span>`;
            })}
          </div>`
        : nothing}
    </div>`;
  }

  /** Tempdoc 610 §J.3 — toggle a retrieved source's hidden state via the shared store (persist + notify). */
  private async toggleSourceExcluded(s: AgentSource): Promise<void> {
    if (!this.sessionId) return;
    const key = sourceExcludeKey(s.parentDocId, s.chunkIndex);
    await toggleExcludedSource(this.sessionId, key, !getExcludedSources().has(key));
  }

  /** Tempdoc 565 §12.3.E — focus a source across surfaces + deep-link to its local passage (chip click). */
  private onChipSelect(s: AgentSource): void {
    setSelectedSource(sourceKey(s.parentDocId, s.startLine));
    this.dispatchEvent(
      new CustomEvent<CitationSelectDetail>('citation-select', {
        detail: {
          parentDocId: s.parentDocId,
          startLine: s.startLine,
          endLine: s.endLine,
          startChar: 0,
          endChar: 0,
          excerpt: s.excerpt,
        },
        bubbles: true,
        composed: true,
      }),
    );
  }

  /**
   * Tempdoc 565 §12.3.C/F — compose the ordered timeline answer-first: a `primary` item (the user
   * turn, the answer) renders at full prominence as a direct conversation child (so `.message.user`
   * keeps its right-aligned `align-self`); each run of consecutive `secondary`/`ambient` items (the
   * tool + progress steps) collapses into ONE status-coloured trace spine that recedes to a one-line
   * summary once the answer lands. The run is audit-on-demand — dense + expandable — not a wall above
   * the answer. Multiple turns yield multiple trace segments (each a spine between its primary items);
   * each collapses independently (fix A). A segment flushed BECAUSE a primary item followed it is
   * NON-trailing (its run answered → default collapsed); the segment flushed after the loop is the
   * TRAILING (in-flight) run with no answer yet → default open. So the collapse default is derived from
   * timeline structure, not a global streaming flag.
   */
  private renderTimeline(items: readonly UnifiedTurnItem[]): TemplateResult {
    // Tempdoc 565 §26.B — partition the flat stream into consecutive RUN SEGMENTS (workflow nodes).
    // Ungrouped items (an agent run with no nodes — the degenerate one-segment case) render inline
    // EXACTLY as before; a workflow node's items render wrapped in a labelled `<section>` so the node
    // structure §15.C flattened is visible. The intra-node primary/trace composition is unchanged.
    const groups: Array<{ segment?: RunSegmentRef; items: UnifiedTurnItem[] }> = [];
    for (const it of items) {
      const key = it.segment?.nodeId;
      const last = groups[groups.length - 1];
      if (last && last.segment?.nodeId === key) last.items.push(it);
      else groups.push({ segment: it.segment, items: [it] });
    }
    return html`${groups.map((g) =>
      g.segment && g.segment.nodeId
        ? this.renderRunSegment(g.segment, g.items)
        : this.renderTimelineItems(g.items),
    )}`;
  }

  /**
   * Tempdoc 565 §26.B — one workflow node rendered as a labelled segment: a header naming the node
   * (its label + kind) over the node's items, composed through the unchanged intra-node renderer. The
   * node structure that was flattened (§25.1) is now a visible grouping, without nesting the item model.
   */
  /**
   * Tempdoc 565 §29 Tier-2 / §33 — J/K steps focus between run landmarks via the Navigation authority
   * (it owns the ordered positions + the jump path). A WINDOW-level shortcut (§33: the conversation div
   * is not focusable, so a div-scoped handler never fired for a real user). Guarded so it acts only on
   * an agent run with the spine shown, and NEVER while the user is typing (the active element is an
   * input/textarea/contenteditable — so `j`/`k` in the composer or steer input types normally). Letters
   * only — no arrow keys, so scroll/caret movement is untouched.
   */
  private onConversationKeydown(e: KeyboardEvent): void {
    if (this.affordance !== 'agent' || !this.wideViewport) return;
    const dir = e.key === 'j' ? 1 : e.key === 'k' ? -1 : 0;
    if (dir === 0) return;
    // §33 — never hijack typing: descend through nested shadow roots (jf-unified-chat-view →
    // jf-composer → textarea) to the truly-focused element, and bail if it's an editable control.
    let active: Element | null = document.activeElement;
    while (active?.shadowRoot?.activeElement) active = active.shadowRoot.activeElement;
    if (
      active &&
      (active.tagName === 'INPUT' ||
        active.tagName === 'TEXTAREA' ||
        (active as HTMLElement).isContentEditable)
    ) {
      return;
    }
    const landmarks = this.nav.landmarks ?? [];
    if (landmarks.length === 0) return;
    e.preventDefault();
    const cur = landmarks.findIndex((l) => l.id === this.nav.activeId);
    const next =
      cur < 0
        ? dir > 0
          ? 0
          : landmarks.length - 1
        : Math.min(landmarks.length - 1, Math.max(0, cur + dir));
    const target = landmarks[next];
    if (target) this.nav.jumpTo(target.id);
  }

  private renderRunSegment(
    segment: RunSegmentRef,
    items: readonly UnifiedTurnItem[],
  ): TemplateResult {
    const label = segment.label ?? segment.nodeId ?? 'Step';
    // §26.D — a background run is one segment with the `background` chip; a workflow node shows its kind.
    const kindChip = segment.originKind === 'background' ? 'background' : segment.nodeKind;
    // Tempdoc 565 §29 Tier-2 — per-segment elapsed time from the items' authoritative timestamps
    // (`ts` already on every UnifiedTurnItem): the wall-clock the node took, shown in the header.
    const elapsedSec =
      items.length > 1
        ? Math.max(0, (items[items.length - 1]!.ts - items[0]!.ts) / 1000)
        : 0;
    const elapsedLabel = elapsedSec >= 0.05 ? `${elapsedSec.toFixed(1)}s` : '';
    return html`<section
      class="run-segment origin-${segment.originKind}"
      data-node-id=${segment.nodeId ?? ''}
    >
      <header class="run-segment-header">
        <span class="run-segment-name">${label}</span>
        ${kindChip ? html`<span class="run-segment-kind">${kindChip}</span>` : nothing}
        ${elapsedLabel
          ? html`<span class="run-segment-elapsed" title="Time this step took">${elapsedLabel}</span>`
          : nothing}
      </header>
      ${this.renderTimelineItems(items)}
    </section>`;
  }

  /** Tempdoc 565 §12.3 — the intra-segment composition (primary items lead; secondary/ambient steps
   * collapse into a run-trace). Extracted from `renderTimeline` so §26.B can wrap it per node segment. */
  private renderTimelineItems(items: readonly UnifiedTurnItem[]): TemplateResult {
    const out: TemplateResult[] = [];
    let trace: UnifiedTurnItem[] = [];
    const flush = (isTrailing: boolean): void => {
      if (trace.length > 0) {
        out.push(this.renderRunTrace(trace, isTrailing));
        trace = [];
      }
    };
    for (const it of items) {
      // Tempdoc 577 §2.14 Root I (#19) — the run/session boundary seam, rendered before the first
      // live item that follows restored history so a resumed thread reads as two exchanges, not one.
      if (this.resumeSeamId !== null && it.id === this.resumeSeamId) {
        flush(false);
        out.push(
          html`<div class="run-seam" role="separator" aria-label="New run in this session">
            <span class="run-seam-label">resumed · new run</span>
          </div>`,
        );
      }
      if (it.prominence === 'primary') {
        flush(false); // a primary item follows this segment → its run answered → collapsed
        out.push(this.renderUnifiedItem(it));
      } else {
        trace.push(it);
      }
    }
    flush(true); // the last segment has no primary after it → the in-flight run → open
    return html`${out}`;
  }

  /**
   * Tempdoc 565 §12.3.F — the run trace as a status-coloured vertical spine: one node per step, tinted
   * by the §3.B status tone, collapsible (§12.3.C). PER-SEGMENT collapse (fix A): default-open iff this
   * is the trailing (in-flight) run — a completed run whose answer follows is collapsed; the user's
   * explicit toggle (keyed by the segment's first-item id in `runTraceToggles`) pins the choice for THAT
   * segment only. Uses native `<details>` disclosure (the §12.10 correction — NOT the horizontal
   * `OverflowController`), fully controlled so a re-render never fights the user's toggle.
   */
  private renderRunTrace(
    trace: readonly UnifiedTurnItem[],
    isTrailing: boolean,
  ): TemplateResult {
    const segId = trace[0]!.id; // stable: record InteractionEvent id / live ConversationEntry id
    const tools = trace.filter((t) => t.kind === 'tool-activity');
    const denied = tools.filter((t) => t.attributes.status === 'rejected').length;
    // Tempdoc 577 Ext I — outcome-aware summary: a run containing a failed call may not summarize
    // as unqualified success ("2 steps · 1.3s" hiding a failure was the §2.9 V2 trust defect).
    const failed = tools.filter(
      (t) => t.attributes.status === 'completed' && t.attributes.success === false,
    ).length;
    const errors = trace.filter((t) => t.kind === 'error').length;
    const open = this.runTraceToggles.has(segId)
      ? this.runTraceToggles.get(segId)!
      : isTrailing;
    // Fix E — the step count names TOOL actions; a tool-less/error-less run falls back to its event
    // count ("3 events") rather than a misleading "0 steps".
    // Tempdoc 565 §29 Tier-2 — the collapsed completed-run summary also carries wall-clock elapsed
    // (the "completed-run summary card" intent, via the existing per-segment collapse — extend, not fork).
    const elapsedSec =
      trace.length > 1
        ? Math.max(0, (trace[trace.length - 1]!.ts - trace[0]!.ts) / 1000)
        : 0;
    const parts = [
      tools.length > 0 ? `${tools.length} step${tools.length === 1 ? '' : 's'}` : null,
      failed > 0 ? `${failed} failed` : null,
      denied > 0 ? `${denied} denied` : null,
      errors > 0 ? `${errors} error${errors === 1 ? '' : 's'}` : null,
      elapsedSec >= 0.05 ? `${elapsedSec.toFixed(1)}s` : null,
    ].filter(Boolean);
    const summary =
      parts.length > 0
        ? parts.join(' · ')
        : `${trace.length} event${trace.length === 1 ? '' : 's'}`;
    return html`
      <details class="run-trace" ?open=${open}>
        <summary
          class="run-trace-summary"
          @click=${(e: Event) => {
            e.preventDefault();
            this.runTraceToggles.set(segId, !open);
            this.requestUpdate();
          }}
        >
          <span class="run-trace-caret">${open ? '▾' : '▸'}</span>
          <span class="run-trace-label">${summary}</span>
        </summary>
        <div class="trace-spine">
          ${trace.map((it) => this.renderTraceNode(it))}
        </div>
      </details>
    `;
  }

  /**
   * Tempdoc 565 §12.3.F — one spine node (status-tinted via the §3.B `statusAccent` authority) + the
   * item body at its declared prominence (`secondary` dense, `ambient` faint — the §12.3.C facet).
   */
  private renderTraceNode(it: UnifiedTurnItem): TemplateResult {
    // Tempdoc 565 §17 — the trace node is the ONE run-node primitive composing the ONE step descriptor
    // (glyph + tone), no hand-authored status lookup (§17.G). The body carries the human label.
    const p = stepPresentation(it);
    // §19.I-R3 — anchor the trace step for the minimap + spine-jump: when the trace is EXPANDED the row
    // has a real scroll position (NavigationController.measure reads it, skipping it when collapsed → the step
    // interpolates between turn anchors as before). Also makes a spine tool/progress node click-jump to
    // its row (previously unmatched). data-item-id mirrors the spine node's id (the same mergedTimeline item).
    return html`<div class="trace-row prominence-${it.prominence}" data-item-id=${it.id}>
      <span class="trace-node"><jf-run-node density="compact" .presentation=${p}></jf-run-node></span>
      <div class="trace-body">${this.renderUnifiedItem(it)}</div>
    </div>`;
  }

  /**
   * Tempdoc 561 P-B — the live SSE overlay. The canonical {@code /api/thread} projection
   * ({@link renderUnifiedThread}) is the SINGLE read-model; this renders only the in-flight turns the
   * record has not reconciled yet (the current exchange, before {@link refreshUnifiedThread} folds it
   * into {@code unifiedEvents} on terminal). Once a turn is in the record it renders from the
   * projection and is deduped out here by (kind, content), so the two models can never double-render
   * or drift — killing the prior "render record OR live-thread" fork.
   */
  private renderLiveOverlay(): TemplateResult {
    // Tempdoc 561 P-B: dedup the in-flight overlay against the record. Prefer the STABLE id (exact,
    // robust to duplicate content); fall back to a (kind, content) key for the brief pre-id window
    // before syncMessageIds runs. Either way an event in the record never double-renders.
    const recordedIds = new Set(this.unifiedEvents.map((e) => e.id));
    const recordedContent = new Set(
      this.unifiedEvents.map((e) => `${e.kind}\u0000${e.content}`),
    );
    const kindOf = (role: string): string =>
      role === 'user' ? 'USER_MESSAGE' : 'ASSISTANT_MESSAGE';
    const pending = this.thread.filter((m) => {
      if (m.id && recordedIds.has(m.id)) return false;
      return !recordedContent.has(`${kindOf(m.role)}\u0000${m.content}`);
    });
    return html`${pending.map((m) => this.renderMessage(m, this.thread.indexOf(m)))}`;
  }

  /**
   * Tempdoc 577 §2.12 Move 3 — the current mode's conversation shape id (the inverse of the
   * shape→affordance preset). The answer-frame authority keys on this to decide a shape's declared
   * grounding class. A record-side answer belongs to the conversation's current shape.
   */
  private currentShapeId(): CoreInteractionShapeId {
    switch (this.affordance) {
      case 'documents':
        return 'core.rag-ask';
      case 'extract':
        return 'core.extract';
      case 'agent':
        return 'core.agent-run';
      default:
        return 'core.free-chat';
    }
  }

  /**
   * Tempdoc 577 §2.12 Move 3 — the epistemic frame for an answer: the shape's declared grounding
   * class refined by the actual outcome (source count + per-sentence coverage). One authority
   * ({@link answerFrame}); the render sites read it, never re-derive.
   */
  private frameFor(
    shapeId: CoreInteractionShapeId,
    sourceCount: number,
    coverageCites: ReadonlyArray<{ readonly similarity: number }>,
    answerText: string,
    // Tempdoc 603 D-4 — whether the attached sources are chunk-precise (matcher-eligible) or
    // document-level (provenance). Defaults TRUE (the RAG/chunk-native tier); the agent path passes the
    // real predicate so an all-document-level source list frames as `sourced`, not "Grounded · 0 of N".
    chunkPrecise = true,
  ): AnswerFrame {
    return answerFrame(shapeId, sourceCount, groundingCoverage(coverageCites, answerText), chunkPrecise);
  }

  /**
   * Tempdoc 577 Move 3 — the answer's epistemic header line (or nothing for grounded/transform).
   * §2.16 — `degraded` refines the `ungrounded` wording: a shape that SEARCHED but found nothing to
   * cite reads distinct from a mode that never searches (computed via groundingDegraded at the call
   * site, where shapeId × sourceCount are known).
   */
  private renderAnswerFrameLine(
    frame: AnswerFrame,
    degraded = false,
  ): TemplateResult | typeof nothing {
    const label = answerFrameLabel(frame, degraded);
    if (label === null) return nothing;
    return html`<div class="answer-frame answer-frame-${frame}" role="note">${label}</div>`;
  }

  private renderUnifiedItem(it: UnifiedTurnItem): TemplateResult {
    switch (it.kind) {
      case 'user': {
        // Tempdoc 610 — the transcript controls (edit-in-place, the per-turn ⋯ menu, the version pager,
        // the context-floor divider) live in renderMessage. When this record turn has a matching live
        // message, render it so the user turn gets those affordances on the canonical record path too
        // (mirroring the assistant case). The match is now computed ONCE by the merge authority
        // (621 Phase 4 — `attachLiveMatch`), so the renderer reads it instead of reaching into the thread.
        const live = it.attributes.live as ThreadMessage | undefined;
        if (live) return this.renderMessage(live, this.thread.indexOf(live));
        // Tempdoc 577 §2.14 Root I (#19) — temporal anchoring on the turn boundary: an ambient
        // relative-time label on each user turn (the turn's start), absolute time on hover. Gentle
        // (ambient altitude, not per-line noise) — only turn boundaries carry it.
        return html`<div class="message user" data-item-id=${it.id}>
          <span class="message-body">${it.content}</span>
          ${it.ts > 0
            ? html`<time
                class="turn-time"
                datetime=${new Date(it.ts).toISOString()}
                title=${new Date(it.ts).toLocaleString()}
                >${formatRelative(it.ts)}</time
              >`
            : nothing}
        </div>`;
      }
      case 'assistant': {
        // Tempdoc 561 P-A (evidence non-divergence): prefer the live message ONLY when it carries fresher
        // evidence (the in-session case). On reload the live thread is rebuilt WITHOUT evidence
        // (loadConversation maps role/content only), so we render evidence FROM the record — the record is
        // the single authority; the two paths can no longer diverge on reload. The "prefer fresher" match
        // is now computed ONCE by the merge authority (621 Phase 4 — `attachLiveMatch`); the renderer reads
        // `attributes.live` and never reaches into the thread at render time (closes the 610 §F.3 fork).
        const live = it.attributes.live as ThreadMessage | undefined;
        if (live) return this.renderMessage(live, this.thread.indexOf(live));
        // Tempdoc 565 §3.A — the AGENT answer record carries `sources` (AgentSource) + `citations`
        // (AgentSentenceCite); render it as markdown with inline [n] marks FROM the record, so a
        // reloaded thread matches the live render (the reload-durability case). Distinguished from the
        // RAG record (RetrievalCitation under `citations` + `claimMatches`) by the `sources` key.
        if (Array.isArray(it.attributes.sources) && it.attributes.sources.length > 0) {
          const cites = Array.isArray(it.attributes.citations)
            ? (it.attributes.citations as AgentSentenceCite[])
            : [];
          const agentSources = it.attributes.sources as AgentSource[];
          const marks = this.resolveAnswerCitations(agentSources, cites);
          // Tempdoc 577 Move 3 / 603 D-4 — even a sourced agent answer carries a frame: full coverage →
          // grounded (no banner); partial → partially-grounded; document-level (no chunk identity) →
          // `sourced` (provenance, no per-sentence verification). One authority decides.
          const frame = this.frameFor(
            this.currentShapeId(),
            agentSources.length,
            cites,
            it.content,
            sourcesAreChunkPrecise(agentSources),
          );
          const partsA = this.recordFloorParts(it.id);
          return html`${partsA.divider}<div class="message assistant${partsA.cls}" data-item-id=${it.id}>
            ${this.renderAnswerFrameLine(
              frame,
              groundingDegraded(this.currentShapeId(), it.attributes.sources.length),
            )}
            <jf-markdown-block .text=${it.content} .citations=${marks} frame=${frame}></jf-markdown-block>
            ${this.renderGroundingBadge(
              it.content,
              it.attributes.sources as AgentSource[],
              it.attributes.citations,
            )}
            ${this.renderSourceChips(it.attributes.sources as AgentSource[], it.id)}
            ${this.recordActionBar(it.id)}
          </div>`;
        }
        // Tempdoc 621 Phase 4-full — the RAG/chat record turn renders through the ONE chat/RAG body
        // (`renderMessage`), so a RELOADED turn renders IDENTICALLY to its live render (shape tag +
        // per-turn frame + inline marks + citations panel + action bar) — the full 610 §F.3 "live==record"
        // closure, eliminating the second (inline) render path. The live thread entry (present on reload —
        // `loadConversation` rebuilds role/content/id/shapeId) is ENRICHED with the record's persisted
        // evidence (the record stays the single authority; we never invent data); a thread-less record turn
        // (the transient load window) falls back to a minimal reconstruction. `renderMessage` derives the
        // floor parts from `floorFrameParts(id, idx)` — identical to the former `recordFloorParts(id)` — and
        // omits the thread-coupled action bar when `idx < 0` (matching the former `recordActionBar`).
        const idx = this.thread.findIndex((m) => m.id === it.id);
        const shapeId = this.currentShapeId();
        const ragSources = Array.isArray(it.attributes.citations)
          ? (it.attributes.citations as RetrievalCitation[])
          : [];
        // Tempdoc 621 Phase 4-full — FORWARD-COMPAT read: the decontextualized "Interpreted as…" question is
        // delivered LIVE via the `rag.rewrite` SSE event but is NOT persisted on the assistant record
        // (`ConversationEngine.persistedAssistant` stores only citations/calibration/claimMatches), so this is
        // ABSENT today and the note does not render on reload — a backend follow-up (persist it on the record),
        // the sibling of the per-message `shapeId` gap. Wired now so it lights up the day the record carries it.
        const standalone =
          typeof it.attributes['rag.standaloneQuestion'] === 'string'
            ? (it.attributes['rag.standaloneQuestion'] as string)
            : typeof it.attributes.standaloneQuestion === 'string'
              ? (it.attributes.standaloneQuestion as string)
              : undefined;
        const base: ThreadMessage =
          idx >= 0
            ? this.thread[idx]!
            : { role: 'assistant', content: it.content, shapeId, id: it.id };
        const enriched: ThreadMessage = {
          ...base,
          // Tempdoc 621 Phase 4-full — the turn's shape on the record path is the window's CURRENT shape
          // (`currentShapeId()`), NOT the reloaded thread's `shapeId`: the auto-restore seeds the thread
          // with a placeholder `core.free-chat` (per-message shape is not persisted — the documented
          // backend gap), so inheriting it mislabels a reloaded Document-Q&A turn as "Chat". This mirrors
          // the former record branch (which framed via `currentShapeId()`); now it also drives the shape tag.
          shapeId,
          // Tempdoc 621 review fix — a reloaded EXTRACT turn must keep its verbatim (`transform`) render, not
          // re-render as markdown. Extract carries no per-turn flag on the record, so derive it from the mode.
          isExtract: shapeId === 'core.extract',
          sources: ragSources,
          claims: this.claimsFromRecord(it.attributes.claimMatches),
          citations: this.matchesFromRecord(it.attributes.claimMatches),
          ...(standalone ? { standaloneQuestion: standalone } : {}),
        };
        return this.renderMessage(enriched, idx);
      }
      case 'tool-activity':
        return this.renderToolActivity(it);
      case 'error': {
        // Tempdoc 565 §12 Phase 2 — carry the error code (live + record render identically now).
        const code = typeof it.attributes.errorCode === 'string' ? it.attributes.errorCode : '';
        return html`<div class="error">${code ? html`[${code}] ` : nothing}${it.content}</div>`;
      }
      case 'progress':
        // Tempdoc 565 §30 — a human STEERING directive (the DIRECTION authority's interject) renders as
        // a distinct human-origin chip, not an ambient agent step, so the user sees their direction land.
        if (it.attributes?.steer === true) {
          return html`<div class="steer-directive" data-steer>
            <span class="steer-directive__label">Your direction</span>
            <span class="steer-directive__text">${it.content}</span>
          </div>`;
        }
        // Tempdoc 565 §17 — the step's tone/glyph is carried by the <jf-run-node> beside this in the
        // trace row; the label is the backend's already-human AgentProgress message rendered as-is
        // (NOT CSS-uppercased — the §16.4/§17.G fix; "Calling LLM", not "CALLING LLM").
        return html`<div class="trace-label">${stepPresentation(it).label}</div>`;
      case 'handoff': {
        // Tempdoc 585 §D Phase 2 (D2) — the structured handoff card (from → to + reason), replacing
        // the prior flat trace-label text. Falls back to the content string if the ids are absent
        // (e.g. a legacy persisted handoff that predates the discrete-id projection).
        const from = it.attributes?.fromAgentId as string | undefined;
        const to = it.attributes?.toAgentId as string | undefined;
        if (!from && !to) {
          return html`<div class="trace-label">${it.content}</div>`;
        }
        return html`<jf-handoff-card
          .from=${from ?? ''}
          .to=${to ?? ''}
          .reason=${(it.attributes?.reason as string | undefined) ?? ''}
        ></jf-handoff-card>`;
      }
    }
  }

  /**
   * Tempdoc 561 P-A (evidence non-divergence): reconstruct the FE Claim[] from the record's persisted
   * per-claim grounding (attributes.claimMatches.matches), so a RELOADED conversation renders the same
   * inline per-claim marks the live render shows — the live + record evidence paths cannot diverge.
   */
  private claimsFromRecord(claimMatches: unknown): Claim[] {
    const matches =
      claimMatches && typeof claimMatches === 'object' &&
      Array.isArray((claimMatches as { matches?: unknown }).matches)
        ? (claimMatches as { matches: Array<Record<string, unknown>> }).matches
        : [];
    const bySentence = new Map<number, Claim>();
    for (const m of matches) {
      const idx = typeof m.sentenceIndex === 'number' ? m.sentenceIndex : 0;
      const text = typeof m.sentenceText === 'string' ? m.sentenceText : '';
      const sim = typeof m.similarity === 'number' ? m.similarity : 0;
      const chunk = typeof m.chunkIndex === 'number' ? m.chunkIndex : -1;
      const existing = bySentence.get(idx);
      if (existing) {
        existing.score = Math.max(existing.score, sim);
        if (chunk >= 0 && !existing.sourceRefs.includes(chunk)) existing.sourceRefs.push(chunk);
      } else {
        bySentence.set(idx, {
          sentenceIndex: idx,
          sentenceText: text,
          score: sim,
          sourceRefs: chunk >= 0 ? [chunk] : [],
        });
      }
    }
    return [...bySentence.values()];
  }

  /**
   * Tempdoc 603 PART X.B — the SOURCES-panel grounding sibling of {@link claimsFromRecord}: reconstruct the
   * `CitationMatch[]` from the record's persisted `claimMatches.matches` (already that shape) so a RELOADED
   * conversation's source panel groups by grounding too (the live path passes `m.citations`; the record path
   * passed `[]`, leaving reload grounding-blind). The match `chunkIndex` is the source's POSITION in the
   * persisted `citations` list — persisted in order by `RAGDoneEnricher`, the same order `sourceGrounding` joins on.
   */
  private matchesFromRecord(claimMatches: unknown): CitationMatch[] {
    const matches =
      claimMatches && typeof claimMatches === 'object' &&
      Array.isArray((claimMatches as { matches?: unknown }).matches)
        ? (claimMatches as { matches: Array<Record<string, unknown>> }).matches
        : [];
    return matches.map((m) => ({
      sentenceIndex: typeof m.sentenceIndex === 'number' ? m.sentenceIndex : 0,
      sentenceText: typeof m.sentenceText === 'string' ? m.sentenceText : '',
      chunkIndex: typeof m.chunkIndex === 'number' ? m.chunkIndex : -1,
      similarity: typeof m.similarity === 'number' ? m.similarity : 0,
      parentDocId: typeof m.parentDocId === 'string' ? m.parentDocId : '',
    }));
  }

  /** Tempdoc 561 P-A/P-B (Slice 2): an agent tool call rendered inline in the unified thread. */
  private renderToolActivity(it: UnifiedTurnItem): TemplateResult {
    const a = it.attributes;
    // Tempdoc 565 §12.3.B — render the record's tool activity through the SAME <jf-tool-call-card>
    // the live half uses (ONE tool renderer; retires the static `🔧 tool · status` fork). The
    // projection merges the call's lifecycle events, so `attributes` carry identity (toolName /
    // arguments / risk from `proposed`) + outcome (output / structuredData from `completed`, reason
    // from `rejected`). Risk persists lowercase; the card expects the live uppercase ToolRisk.
    const toolCall: ToolCall = {
      callId: typeof a.callId === 'string' ? a.callId : it.id,
      toolName: typeof a.toolName === 'string' ? a.toolName : 'tool',
      arguments: typeof a.arguments === 'string' ? a.arguments : '',
      risk: (typeof a.risk === 'string' ? a.risk.toUpperCase() : 'LOW') as ToolCall['risk'],
      status: (typeof a.status === 'string' ? a.status : 'completed') as ToolCall['status'],
      output: typeof a.output === 'string' ? a.output : undefined,
      success: typeof a.success === 'boolean' ? a.success : undefined,
      rejectReason: typeof a.reason === 'string' ? a.reason : undefined,
      structuredData:
        a.structuredData && typeof a.structuredData === 'object'
          ? (a.structuredData as Record<string, unknown>)
          : undefined,
      gateBehavior:
        typeof a.gateBehavior === 'string'
          ? (a.gateBehavior as ToolCall['gateBehavior'])
          : undefined,
    };
    return html`<div class="message assistant tool-activity">
      <jf-tool-call-card
        .toolCall=${toolCall}
        .stepPresentation=${stepPresentation(it)}
      ></jf-tool-call-card>
    </div>`;
  }

  private renderMessage(m: ThreadMessage, idx: number): TemplateResult {
    // Tempdoc 610 Phase C — floor divider + out-of-context band. Messages above
    // the floor render dimmed (distinct from the ↪ inherited treatment); the
    // divider renders just above the floor message.
    // Tempdoc 610 §F.3 — the floor divider + out-of-context/excluded dim-class come from the one
    // shared authority (floorFrameParts); the live path adds only its own `inherited` treatment.
    const fp = this.floorFrameParts(m.id, idx);
    const floorDivider = fp.divider;
    const inheritedClass = (m.inheritedFromParent ? ' inherited' : '') + fp.cls;
    if (m.role === 'user') {
      // Tempdoc 610 Phase A — edit-in-place: when this user turn is being edited,
      // swap its text for a native textarea (Save → branch-from-before + re-dispatch).
      if (this.editingMessageId && m.id === this.editingMessageId) {
        return html`${floorDivider}<div class="message user editing">
          <textarea
            class="msg-edit"
            .value=${this.editingDraft}
            aria-label="Edit message"
            @input=${(e: Event) => {
              this.editingDraft = (e.target as HTMLTextAreaElement).value;
            }}
            @keydown=${(e: KeyboardEvent) => this.onEditKeydown(e, idx)}
          ></textarea>
          <div class="msg-edit-actions">
            <button class="msg-edit-save" @click=${() => void this.commitEdit(idx)}>Save</button>
            <button class="msg-edit-cancel" @click=${() => this.cancelEdit()}>Cancel</button>
          </div>
        </div>`;
      }
      // Tempdoc 610 Phase A — user-turn hover toolbar (mirrors the assistant one).
      // Gated identically to the assistant Branch affordance: own (non-inherited)
      // turn, stable backend id present, not mid-stream, and not an agent run
      // (agent-run branching is out of scope per 513 §A.5).
      // Tempdoc 610 §D.2 — the per-turn controls render in the action bar BELOW
      // the bubble (not in the bubble corner), matching ChatGPT/Claude.
      return html`${floorDivider}<div class="message user${inheritedClass}" data-item-id=${m.id ?? nothing}>
          <span class="message-body">${m.content}</span>
        </div>
        ${idx >= 0 ? this.renderTurnActionBar(m, idx) : nothing}`;
    }
    const shapeLabel = SHAPE_LABELS[m.shapeId] ?? m.shapeId;
    // Tempdoc 610 §D.2 — per-turn controls (incl. branch) now live in the action
    // bar below the message (renderTurnActionBar), gated by canTurnControl.
    // Tempdoc 577 §2.12 Move 3 — the epistemic frame for the live ThreadMessage path (carries
    // shapeId). The claims' similarity feeds the coverage; the source count is the grounding signal.
    const claimCites = (m.claims ?? []).map((c) => ({ similarity: c.score }));
    const sourceCount = (m.sources?.length ?? 0) + (m.claims?.length ?? 0);
    // Tempdoc 603 D-4 — document-level sources (agent, no chunk identity) frame as `sourced`, not
    // "Grounded · 0 of N". RAG sources (RetrievalCitation, chunk-native) carry no sentinel → chunk-precise.
    const frame: AnswerFrame = m.isExtract
      ? 'transform'
      : this.frameFor(
          m.shapeId,
          sourceCount,
          claimCites,
          m.content,
          sourcesAreChunkPrecise(m.sources ?? []),
        );
    return html`
      ${floorDivider}
      <div class="message assistant${inheritedClass}" data-item-id=${m.id ?? nothing} data-msg-idx=${idx}>
        <div class="message-shape-tag">${shapeLabel}</div>
        ${m.standaloneQuestion
          ? html`<div class="rewrite-note" role="note">
              Interpreted as: <em>${m.standaloneQuestion}</em>
            </div>`
          : nothing}
        ${this.renderAnswerFrameLine(
          frame,
          m.isExtract ? false : groundingDegraded(m.shapeId, sourceCount),
        )}
        ${m.isExtract
          ? // Tempdoc 565 §15.B — extract is verbatim text: the ONE renderer in `plain` format.
            html`<jf-markdown-block format="plain" .text=${m.content} frame=${frame}></jf-markdown-block>`
          : (m.claims?.length ?? 0) > 0
            ? // Tempdoc 565 §15.B — RAG grounding now renders through the ONE renderer + weave: the
              // claims map into the shared `Citation` shape, so RAG gains source-stable, deep-linked,
              // cross-surface-selectable marks (the markdown-aware decoration the §3.C note awaited).
              html`<jf-markdown-block
                format="plain"
                .text=${m.content}
                .citations=${this.resolveClaimCitations(m.claims ?? [], m.sources ?? [])}
                frame=${frame}
              ></jf-markdown-block>`
            : // Tempdoc 565 §15.B — the canonical answer block for every other mode (agent/chat).
              html`<jf-markdown-block .text=${m.content} frame=${frame}></jf-markdown-block>`}
        ${(m.sources?.length ?? 0) > 0 || (m.citations?.length ?? 0) > 0
          ? html`<jf-citations-panel
              .sources=${m.sources ?? []}
              .citations=${m.citations ?? []}
              .retrievalMode=${m.ragMeta?.retrieval_mode ?? ''}
            ></jf-citations-panel>`
          : nothing}
        ${idx >= 0 ? this.renderTurnActionBar(m, idx) : nothing}
      </div>
    `;
  }

  /**
   * Slice 513 — splice backend-assigned message ids into the local thread so
   * freshly-streamed messages are branchable without a full reload. Order is
   * stable (engine appends sequentially); we positionally line up the ids.
   */
  private async syncMessageIds(): Promise<void> {
    // Slice 515 FIX-4 + 516 FIX-T2 — stale-response guard. Rapid sends can
    // produce overlapping syncMessageIds chains; only the latest one's
    // result should reach the thread. Capture token + sessionId before
    // fetch; reject if either changed (another send, or conversation
    // switched mid-fetch) by the time we return.
    const myToken = ++this.syncToken;
    const mySession = this.sessionId;
    const backend = await fetchMessageIds(mySession);
    if (!backend) return;
    if (myToken !== this.syncToken) return;
    if (mySession !== this.sessionId) return;
    // Count how many thread entries need ids; early-exit once all matched
    // to avoid O(N²) walks on long sessions.
    let unmatched = 0;
    for (const m of this.thread) if (!m.id && !m.inheritedFromParent) unmatched++;
    if (unmatched === 0) return;
    let cursor = 0;
    const next = this.thread.map((m) => {
      if (m.id || m.inheritedFromParent) return m;
      if (unmatched === 0) return m;
      while (cursor < backend.length) {
        const candidate = backend[cursor++];
        if (!candidate) continue;
        if (candidate.role === m.role && candidate.content === m.content && candidate.id) {
          unmatched--;
          return { ...m, id: candidate.id };
        }
      }
      return m;
    });
    // Re-check token + session after the (synchronous) walk to be safe under
    // future refactors that might insert awaits.
    if (myToken !== this.syncToken) return;
    if (mySession !== this.sessionId) return;
    this.thread = next;
  }

  /**
   * Slice 513 — branch the current conversation from the given assistant
   * message. Creates a new session on the backend whose history will lazily
   * resolve the parent prefix up to this message, then swaps the chat view
   * to the new session so the user can continue the divergent thread.
   */
  private async branchHere(fromMsgId: string): Promise<void> {
    const preview = this.thread.find((m) => m.role === 'user')?.content ?? '';
    const newSessionId = await branchConversation(this.sessionId, fromMsgId, preview);
    if (!newSessionId) {
      this.errorMessage = 'Failed to create branch';
      return;
    }
    await this.loadConversation(newSessionId, this.thread[0]?.shapeId ?? 'core.free-chat');
  }

  private renderStreamingBlock(): TemplateResult | typeof nothing {
    if (!this.streamingText && !this.isStreaming && !this.reasoning.isThinking) return nothing;
    // Tempdoc 526 §16 F13 — single dispatch resolver (no wrapper helper).
    const currentShape = resolveShape('core.ask', 'none', this.affordance) as ShapeId;
    const isExtract = currentShape === 'core.extract';
    return html`
      <div class="message assistant">
        <div class="message-shape-tag">
          ${SHAPE_LABELS[currentShape]} ${this.isStreaming
            ? this.streamingText ? '(streaming)' : ''
            : ''}
        </div>
        ${this.isStreaming && !this.streamingText && !this.reasoning.isThinking && this.thinkingElapsedSec >= 2
          ? html`<div class="thinking-timer">Thinking… ${this.thinkingElapsedSec}s</div>`
          : nothing}
        ${this.ragMeta ? this.renderPreamble() : nothing}
        ${this.rewriteNote
          ? html`<div class="rewrite-note" role="note">
              Interpreted as: <em>${this.rewriteNote.standalone}</em>
            </div>`
          : nothing}
        ${this.reasoning.isThinking
          ? html`<jf-reasoning-block .controller=${this.reasoning}></jf-reasoning-block>`
          : nothing}
        ${this.reasoning.reasoningBlocks.length > 0 && !this.reasoning.isThinking
          ? this.reasoning.reasoningBlocks.map(
              (block) => html`<jf-reasoning-block
                .text=${block.text} .durationMs=${block.durationMs}
              ></jf-reasoning-block>`,
            )
          : nothing}
        ${isExtract
          ? html`<jf-markdown-block
              format="plain"
              .text=${this.streamingText}
              ?is-streaming=${this.isStreaming}
            ></jf-markdown-block>`
          : html`<jf-markdown-block
              format="plain"
              .text=${this.streamingText}
              .citations=${this.resolveClaimCitations(this.claims, this.sources)}
              ?is-streaming=${this.isStreaming}
            ></jf-markdown-block>`}
        ${this.sources.length > 0 || this.citations.length > 0
          ? html`<jf-citations-panel
              .sources=${this.sources}
              .citations=${this.citations}
              .retrievalMode=${this.ragMeta?.retrieval_mode ?? ''}
            ></jf-citations-panel>`
          : nothing}
      </div>
    `;
  }

  private renderPreamble(): TemplateResult {
    const m = this.ragMeta!;
    const mode = m.retrieval_mode ?? '';
    if (mode === 'FULLTEXT_FALLBACK') {
      return html`<div class="preamble">
        Full-document retrieval (document too small for chunking)
      </div>`;
    }
    const chunks = m.chunks_used ?? 0;
    const found = m.chunks_found ?? 0;
    const coverage = m.retrieval_coverage ?? 0;
    const truncated = m.context_truncated ?? false;
    const parts: string[] = [];
    parts.push(
      `${chunks} ${chunks === 1 ? 'passage' : 'passages'} used` +
        (found > chunks ? ` (${found} found)` : ''),
    );
    parts.push(mode.toLowerCase().replace(/_/g, ' '));
    if (coverage > 0)
      parts.push(`coverage ${Math.round(coverage * 100)}%`);
    if (truncated) parts.push('context truncated');
    return html`<div class="preamble">${parts.join(' · ')}</div>`;
  }

  private renderSchemaInput(): TemplateResult {
    return html`
      <div>
        <div class="schema-label">JSON Schema</div>
        <textarea
          class="mono"
          rows="4"
          .value=${this.schemaDraft}
          ?disabled=${this.isStreaming}
          @input=${(e: Event) =>
            (this.schemaDraft = (e.target as HTMLTextAreaElement).value)}
        ></textarea>
      </div>
    `;
  }

  private toggleAffordance(target: Affordance): void {
    this.affordance = this.affordance === target ? 'none' : target;
    this.userToggledAffordance = true;
    // Tempdoc 561 P-A/P-B (Slice 2): crossing planes (esp. agent -> answer) may have added turns to
    // the record (an agent run); refresh so the unified thread reflects both planes.
    void this.refreshUnifiedThread();
  }

  private getPlaceholder(): string {
    // Tempdoc 561 C-2: in agent mode the placeholder grades with the autonomy posture.
    if (this.affordance === 'agent') {
      return postureChrome(agencyPosture(this.affordance, getAutonomyLevel())).placeholder;
    }
    switch (this.affordance) {
      case 'retrieve':
        return 'Search your files…';
      case 'documents':
        return 'Ask a question about your indexed documents…';
      case 'extract':
        return 'Describe what to extract…';
      default:
        return 'Type a message…';
    }
  }

  /** Tempdoc 561 C-2: the send-button label grades with the agency posture (agent mode only). */
  private getSubmitLabel(): string {
    // Tempdoc 577 Goal 3 — the retrieve base tier is pure search (no LLM); its submit runs the
    // search, so it reads "Search" whether or not a chat model is online (it never says "AI Offline").
    if (this.affordance === 'retrieve') return 'Search';
    if (!this.aiState?.capabilities?.chat) return 'AI Offline';
    if (this.affordance === 'agent') {
      return postureChrome(agencyPosture(this.affordance, getAutonomyLevel())).sendLabel;
    }
    return 'Send';
  }

  private async send(): Promise<void> {
    const text = this.inputDraft.trim();
    if (!text || this.isStreaming) return;

    // Tempdoc 561 P-B (body-unification): in agent mode the conversation IS the agent run — route the
    // message to the inline-hosted controller (it renders in this same thread; approvals route through
    // the global ceremony; the autonomy dial is obeyed via the backend gateBehavior, P-D).
    if (this.affordance === 'agent') {
      const ctrl = this.ensureAgentCtrl();
      if (ctrl.available !== true || ctrl.isStreaming) return;
      this.inputDraft = '';
      ctrl.navigationReceipts = [];
      // Tempdoc 561 P-B (non-divergence): on the run's terminal, refresh the record so the live overlay
      // folds into the /api/thread projection (the dedup above then renders each entry from the record).
      // Tempdoc 565 §30 — `initiate` flows through the ONE control-intent seam (the DIRECTION authority),
      // alongside interject (steer) / set-posture (dial) / halt (stop).
      void dispatchRunControl(ctrl, { kind: 'initiate', prompt: text }).then(
        () => void this.refreshUnifiedThread(),
      );
      return;
    }

    // Tempdoc 526 §4.5 / §6 — single dispatch routing: prefer the compose()-
    // parked shapeId; otherwise consult the same resolver compose() uses,
    // with the current selection's kind + the UI affordance hint. T6 closed
    // the askAi-vs-direct-route fork by removing the legacy affordanceToShape
    // fallback; SEND-button flows now share the (operation, kind, affordance)
    // → shapeId table with compose()-driven flows.
    const forced = takePendingForceShape();
    const currentSelection = getCurrentSelection().items[0];
    // Tempdoc 526 §16 — only kinds with wire variants are forwarded to the
    // resolver; FE-only item kinds (search-hit / browse-node / plugin-item)
    // are summarized as 'item' for shape resolution.
    const k = currentSelection?.kind;
    const currentKind: SelectionPayload['kind'] | 'none' =
      k === 'text-range' || k === 'citation' || k === 'result-set'
        ? k
        : k === 'search-hit' || k === 'browse-node' || k === 'plugin-item'
          ? 'item'
          : 'none';
    const shapeId: ShapeId =
      (forced as ShapeId | null) ??
      (resolveShape('core.ask', currentKind, this.affordance) as ShapeId);
    // Tempdoc 526 §12.4 — drain the pending selection set by compose() on the
    // navigation event. One-shot: subsequent sends without a fresh compose()
    // call carry no body.selection.
    const selection = takePendingSelection();
    const body = buildRequestBody(
      shapeId,
      text,
      this.sessionId,
      this.schemaDraft,
      this.pinnedDocIds,
      selection,
    );

    // Tempdoc 561 P-A/P-B: stamp the surface conversationId on EVERY dispatch so the backend records
    // the answer-plane turn (incl. ephemeral RAG, which carries no sessionId) onto the ONE canonical
    // conversation record. The unified thread, History, and Timeline then project the grounded answer
    // + its evidence FROM that record — they cannot disagree. EPHEMERAL retrieval semantics are
    // unchanged backend-side: conversationId is a write key, never a context-load key.
    body.conversationId = this.sessionId;

    // Cross-turn context forwarding: include recent conversation history
    // so the model has continuity across shape boundaries. For FreeChat
    // (PERSISTENT), only include non-FreeChat turns — FreeChat's own
    // history is loaded from the backend session store, so including
    // FreeChat turns here would duplicate them.
    if (this.thread.length > 0) {
      const recent = this.thread.slice(-10);
      const context: Array<{ role: string; content: string }> = [];
      for (const m of recent) {
        if (shapeId === 'core.free-chat' && m.shapeId === 'core.free-chat') continue;
        context.push({ role: m.role, content: m.content });
      }
      if (context.length > 0) {
        body.context = context;
      }
    }

    this.thread = [...this.thread, { role: 'user', content: text, shapeId }];
    this.showResumePrompt = false;
    if (this.thread.length === 1) {
      // Tempdoc 562: record the session POINTER only — the preview is later derived from the lock-safe
      // backend list, so no plaintext message content is cached client-side.
      recordRecentSession(this.sessionId);
    }
    // Tempdoc 609 Phase 3 — a brand-new conversation (started by sending, not by loadConversation) is
    // now what this tab is viewing; remember it so a navigation round-trip restores it.
    setLastViewedConversation(this.sessionId);
    this.inputDraft = '';
    this.abortController = new AbortController();
    this.isStreaming = true;
    this.startRenderTick();
    setAiActivity({
      state: shapeId === 'core.extract' ? 'extracting' : 'thinking',
      shapeId,
      startedAtMs: Date.now(),
      canCancel: true,
      cancel: () => this.abortController?.abort(),
    });
    this.streamingText = '';
    this.reasoning.reset();
    this.errorMessage = '';
    this.citations = [];
    this.sources = [];
    this.ragMeta = null;
    this.rewriteNote = null;
    this.claims = [];

    const url = (this.apiBase || '') + '/api/chat/dispatch';

    const handlers: Record<string, (payload: unknown) => void> = {
      onReasoningChunk: (payload: unknown) => {
        this.reasoning.handleReasoningChunk(payload);
      },
      onChunk: (payload: unknown) => {
        this.reasoning.endThinking();
        const p = payload as Record<string, unknown> | string | null;
        const t =
          typeof p === 'string'
            ? p
            : typeof p?.text === 'string'
              ? (p.text as string)
              : '';
        if (t) {
          if (!this.streamingText) setAiActivity({ state: 'streaming' });
          this.streamingText = this.streamingText + t;
        }
      },
      onDone: (payload: unknown) => {
        this.reasoning.finalize();
        // Tempdoc 610 §E.4 — capture the prompt-token occupancy the backend now surfaces on the
        // done payload, so the context-budget meter reflects this turn's window fullness.
        const donePayload = payload as
          | {
              promptTokens?: number;
              contextBreakdown?: { system?: number; conversation?: number; retrieved?: number };
            }
          | null;
        if (donePayload && typeof donePayload.promptTokens === 'number') {
          this.contextPromptTokens = donePayload.promptTokens;
        }
        // Tempdoc 610 §I.2 — capture the per-phase token split for the meter attribution breakdown.
        const b = donePayload?.contextBreakdown;
        if (b && typeof b === 'object') {
          this.contextBreakdown = {
            system: typeof b.system === 'number' ? b.system : 0,
            conversation: typeof b.conversation === 'number' ? b.conversation : 0,
            retrieved: typeof b.retrieved === 'number' ? b.retrieved : 0,
          };
        }
        const msg: ThreadMessage = {
          role: 'assistant',
          content: this.streamingText,
          shapeId,
          isExtract: shapeId === 'core.extract',
        };
        if (this.citations.length > 0) msg.citations = [...this.citations];
        if (this.sources.length > 0) msg.sources = [...this.sources];
        if (this.claims.length > 0) msg.claims = [...this.claims];
        if (this.ragMeta) msg.ragMeta = { ...this.ragMeta };
        // Tempdoc 603 C2 — pin the decontextualized question onto the committed turn so the
        // "Interpreted as: …" line persists past the live stream (mirrors citations/ragMeta).
        if (this.rewriteNote) msg.standaloneQuestion = this.rewriteNote.standalone;
        if (this.streamingText.trim()) {
          this.thread = [...this.thread, msg];
        }
        this.streamingText = '';
        this.isStreaming = false;
        this.stopRenderTick();
        setAiActivity({ state: 'idle', shapeId: null, startedAtMs: null, canCancel: false, cancel: null });
        // Tempdoc 561 P-A/P-B (Slice 2): the durable record now has this turn — refresh the unified
        // thread so the conversation projects the one record.
        void this.refreshUnifiedThread();
        if (this.thread.length === 2) this.generateTitle();
        // Slice 513 — splice backend-side ids into the freshly-appended
        // messages so "Branch here" works without requiring the user to
        // resume the conversation first. The thread's role+content order
        // matches the persisted log; we positionally line up the ids.
        void this.syncMessageIds();
      },
      onError: (payload: unknown) => {
        const p = payload as Record<string, unknown> | null;
        this.errorMessage =
          (p?.error as string) ??
          (p?.message as string) ??
          'An error occurred';
        this.isStreaming = false;
        this.stopRenderTick();
        setAiActivity({ state: 'idle', shapeId: null, startedAtMs: null, canCancel: false, cancel: null });
      },
      onRagMeta: (payload: unknown) => {
        const p = payload as RagMetaPayload | null;
        if (p) this.ragMeta = p;
      },
      onRagRewrite: (payload: unknown) => {
        // 603 C2 — a follow-up was decontextualized (rag.rewrite → onRagRewrite via the shape-event
        // dispatcher); show what retrieval actually ran on.
        const d = payload as { original?: string; standalone?: string } | null;
        if (d && typeof d.standalone === 'string' && typeof d.original === 'string') {
          this.rewriteNote = { original: d.original, standalone: d.standalone };
        }
      },
      onRagCitations: (payload: unknown) => {
        const p = payload as { citations?: RetrievalCitation[] } | null;
        if (p && Array.isArray(p.citations)) {
          this.sources = p.citations;
        }
      },
      onRagCitationDelta: (payload: unknown) => {
        const p = payload as {
          sentenceIndex?: number;
          sentenceText?: string;
          citations?: Array<{
            parentDocId: string;
            chunkIndex: number;
            score: number;
          }>;
        } | null;
        if (
          p &&
          Array.isArray(p.citations) &&
          typeof p.sentenceText === 'string'
        ) {
          const bestScore = Math.max(...p.citations.map((c) => c.score), 0);
          const existing = this.claims.find(
            (cl) => cl.sentenceIndex === (p.sentenceIndex ?? 0),
          );
          if (!existing) {
            this.claims = [
              ...this.claims,
              {
                sentenceIndex: p.sentenceIndex ?? 0,
                sentenceText: p.sentenceText,
                score: bestScore,
                sourceRefs: p.citations.map((c) => c.chunkIndex),
              },
            ];
          }
          for (const c of p.citations) {
            const existingCit = this.citations.find(
              (m) =>
                m.parentDocId === c.parentDocId &&
                m.sentenceIndex === (p.sentenceIndex ?? 0),
            );
            if (!existingCit) {
              this.citations = [
                ...this.citations,
                {
                  sentenceIndex: p.sentenceIndex ?? 0,
                  sentenceText: p.sentenceText,
                  chunkIndex: c.chunkIndex,
                  similarity: c.score,
                  parentDocId: c.parentDocId,
                },
              ];
            }
          }
          // If the stream already completed, update the committed thread
          // message so late-arriving delta events aren't lost.
          if (!this.isStreaming) {
            const last = this.thread.at(-1);
            if (last?.role === 'assistant') {
              last.claims = [...this.claims];
              last.citations = [...this.citations];
              this.thread = [...this.thread];
            }
          }
        }
      },
      onRagCitationMatches: (payload: unknown) => {
        const p = payload as { matches?: CitationMatch[] } | null;
        if (p && Array.isArray(p.matches)) {
          this.citations = p.matches;
          // F-5 fix: derive claims from authoritative embedding matches when
          // streaming-lexical didn't fire enough deltas. StreamingCitationMatcher
          // emits rag.citation_delta only when word-overlap matches; that's too
          // strict for typical LLM summary phrasing. rag.citation_matches at
          // done-time gives authoritative cosine-similarity matches — convert
          // them into claims so grounded spans + inline markers (F-17) render.
          // Preserve any existing claims (from streaming deltas) and merge.
          const bySentence = new Map<number, { text: string; score: number; refs: Set<number> }>();
          for (const cl of this.claims) {
            bySentence.set(cl.sentenceIndex, {
              text: cl.sentenceText,
              score: cl.score,
              refs: new Set(cl.sourceRefs),
            });
          }
          for (const m of p.matches) {
            const idx = m.sentenceIndex ?? 0;
            const text = m.sentenceText ?? '';
            const sim = typeof m.similarity === 'number' ? m.similarity : 0;
            const existing = bySentence.get(idx);
            if (existing) {
              existing.score = Math.max(existing.score, sim);
              if (typeof m.chunkIndex === 'number') existing.refs.add(m.chunkIndex);
            } else {
              const refs = new Set<number>();
              if (typeof m.chunkIndex === 'number') refs.add(m.chunkIndex);
              bySentence.set(idx, { text, score: sim, refs });
            }
          }
          this.claims = Array.from(bySentence.entries())
            .map(([sentenceIndex, v]) => ({
              sentenceIndex,
              sentenceText: v.text,
              score: v.score,
              sourceRefs: Array.from(v.refs),
            }))
            .sort((a, b) => a.sentenceIndex - b.sentenceIndex);
          // Propagate to the committed thread message if streaming has ended.
          if (!this.isStreaming) {
            const last = this.thread.at(-1);
            if (last?.role === 'assistant') {
              last.claims = [...this.claims];
              last.citations = [...this.citations];
              this.thread = [...this.thread];
            }
          }
        }
      },
    };

    try {
      await consumeShapeStream(
        url,
        body,
        (event, payload) => {
          dispatchShapeEventToHandlers(handlers, event, payload);
        },
        this.abortController.signal,
      );
    } catch (err) {
      if (!(err instanceof Error && err.name === 'AbortError')) {
        this.errorMessage = friendlyStreamError(err);
      }
    } finally {
      this.isStreaming = false;
      this.abortController = null;
    }
  }
}

if (
  typeof customElements !== 'undefined' &&
  !customElements.get('jf-unified-chat-view')
) {
  customElements.define('jf-unified-chat-view', UnifiedChatView);
}

// Tempdoc 561 surface tier: the one window is the canonical view for EVERY direct-LLM interaction
// shape. Every deeplink / resume path resolves here (with the shape-id presetting the mode),
// instead of mounting a separate per-shape view — and check-shape-view-coverage stays green.
// These literal registrations are the per-shape coverage the shape-view-coverage gate greps for;
// the interaction-surface gate cross-checks that every CORE_INTERACTION_SHAPE registers to
// ONE_WINDOW_MOUNT_TAG here and to no other tag (no second interaction view).
registerViewFactory('core.rag-ask', ONE_WINDOW_MOUNT_TAG);
registerViewFactory('core.free-chat', ONE_WINDOW_MOUNT_TAG);
registerViewFactory('core.extract', ONE_WINDOW_MOUNT_TAG);
registerViewFactory('core.agent-run', ONE_WINDOW_MOUNT_TAG);
// Tempdoc 565 §15.C — the workflow run is a MODE of the one window, not a bespoke surface; the
// retired WorkflowSurface/WorkflowView fork registered 'jf-workflow-surface' here.
registerViewFactory('core.workflow-run', ONE_WINDOW_MOUNT_TAG);
// Load-time exhaustiveness guard: every interaction mode in the FE mirror of the Java authority
// must have registered the one-window view above (and to no other tag). A mode added to
// CORE_INTERACTION_SHAPES without a registration line throws here at import — the runtime
// counterpart of the interaction-surface gate, and a real consumer of the mirror value.
for (const shape of CORE_INTERACTION_SHAPES) {
  if (getViewFactory(shape) === undefined) {
    throw new Error(
      `[one-window] interaction mode '${shape}' is declared in CORE_INTERACTION_SHAPES but has ` +
        `no registered view factory — register it to ${ONE_WINDOW_MOUNT_TAG}.`,
    );
  }
}
