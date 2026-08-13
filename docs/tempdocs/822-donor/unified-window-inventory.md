# 822 — What the shipped window knows that search-v3 does not

**Type:** requirements record, NOT a design reference. Every row is a *capability* or a
*product decision*, never a form. Where the shipped window solves a problem with a band, a
banner, or a drawer, the row states the problem it solves; the v3 form is an open question
this document deliberately does not answer.

**Subject:** `modules/ui-web/src/shell-v0/views/UnifiedChatView.ts` (6,083 lines) + its satellite
record (810 §T-B, 814, 817, 818 §5b/§6) + its wiring in `Shell.ts` / `CorePlugin.ts` /
`governance/`.

**Comparand:** `modules/ui-web/src/shell-v0/views/search-v3/` as of F5 (SearchV3View.ts,
Sv3Composer/Main/Sidebar/SessionRow/Topbar/Empty/Palette, sv3-{ask,run,sessions,sidebar-sizing}).

**Directive compliance (§4b STANDING DIRECTIVE, owner 2026-08-13):** search integration is
deferred INDEFINITELY. §J below lists the search-adjacent capabilities in one line each, with
no analysis and no recommendation beyond `D`. They are recorded so a future sweep knows they
exist; they are not proposed.

**Provenance convention:** the shipped window carries its own provenance in comments (tempdoc
numbers, round numbers, finding ids). Where a row's provenance is quoted from such a comment it
is unmarked. Where I reasoned it out, it is marked **(inferred)**.

**Tiers**
- **A** — adopt via an existing shared authority. The authority already exists and is already
  imported by other surfaces; the donor-economy *form* is still to be decided, but no new
  substrate and no owner taste call is required to have the capability.
- **B** — adopt, but requires a donor-economy re-forming decision (owner taste). The capability
  is wanted; its shipped form is exactly what the 40%-chrome complaint was about, so it cannot
  be copied across, only re-formed.
- **C** — deliberately leave behind. The row names why: superseded by v3's model, or it is a
  defect 818 §6 says v3 kills by construction, or governance already records it as unreachable.
- **D** — deferred by the standing directive (search-adjacent). Recorded, not analysed.

---

## A. Conversation lifecycle and the record

| Capability | Where (file:line) | Why it exists (provenance) | v3 status | Tier |
|---|---|---|---|---|
| A1. Conversations survive the process — ids minted, sessions listed, threads reloaded from the backend store | `UnifiedChatView.ts:257-282` (imports), `:808` `createConversationId`, `:917` `loadConversations`, `:1962` `loadConversation` | The product's conversation store (`conversationListStore`) is the app-wide authority; the window is one consumer | **lacks** — v3 sessions are a pure in-memory list (`sv3-sessions.ts:142` `SV3_SESSIONS_EMPTY`; only sidebar *sizing* touches storage, `sv3-sidebar-sizing.ts:89-132`) | A |
| A2. Returning to the app offers the last conversation back, with a lock-safe preview | `:2073` `renderResumePrompt`, `:2086-2092` | Tempdoc 577 §3.13/A2; the lock-safe preview rule is tempdoc 562 (never derive the preview from a client-side plaintext cache) | **lacks** | B (form) / A (the lock-safe rule) |
| A3. A page reload restores the thread *this tab* was reading, not the globally-most-recent one | `:291-297` import, `:925-929` | Tempdoc 609 Phase 3 — the §K.2 blank-then-reload flicker | **lacks** | A |
| A4. Backend message ids are reconciled into the FE thread (so per-turn actions have stable handles) | `:5481` `syncMessageIds`, `:744-747` monotonic `syncToken` | Slice 515 FIX-4 (stale-response discard) | **lacks** (v3 turn ids are FE-minted, `sv3-sessions.ts:202` `submitInSession`) | A (rides A1) |
| A5. A user turn can be edited in place and re-sent | `:1376` `startEdit` … `:1398` `commitEdit`, `:1388` `onEditKeydown` (Ctrl/Cmd+Enter commits, Escape cancels) | Tempdoc 610 Phase A; 818 §5b lists it unchecked for v2 too | **lacks** | B |
| A6. A turn can be retried from that point | `:1408` `retryFrom`, offered both inline (`:1560-1567`) and in the overflow (`:1305`) | Tempdoc 610 §13.1 | **lacks** | B |
| A7. A turn can be branched into a new thread (fork, with the parent named) | `:1421` `branchAndResend`, `:5525` `branchHere`, `:451-453` + `:624` `parentFirstMessagePreview` | Slice 515 FIX-8 (banner names which parent) | **lacks** | B |
| A8. Sibling branches at a turn are navigable inline (version pager) | `:1458` `pagerForTurn`, `:1494` `renderVersionPager`, `siblingSessionsAt` (`:271`) | Tempdoc 610 Phase B — a pure sibling-set projection, deliberately no new endpoint | **lacks** | B |
| A9. An answer can be copied | `:1273` `copyText`, `:1552-1559` | shared `copyToClipboard` util (`:68`) | **lacks** | A |
| A10. A conversation can be exported as markdown | `:2037` `exportMarkdown` → `exportConversationMarkdown` + clipboard | 818 §5b lists export unchecked for v2 — it is a *shipped-window-only* capability | **lacks** | A |
| A11. A conversation gets an auto-generated title | `:2045` `generateTitle` (needs ≥2 turns) | conversationListStore authority | **partial** — v3 derives a title from the first message and supports manual rename (`sv3-sessions.ts:428` `renameSession`), but never asks the model | A |
| A12. Per-turn actions live in one shared overflow primitive (not a hand-rolled popover) | `:1294` `openTurnMenu` → `openContextMenu` (`:241`), rides TransientController single-open arbitration | Tempdoc 610 — explicitly "not a hand-rolled popover" | **lacks** | B (which actions is the taste call; the primitive is free) |
| A13. New-conversation is always reachable and resets everything the previous one owned | `:2114` `newConversation` (aborts the in-flight stream, `:2117`) | Slice 516 FIX-T1 (abort so `onDone` can't append to the fresh thread); the *reachability* half is 818 §6's "New chat state-gated/unreachable, seen 6×" | **partial** — v3's New session is always visible (`Sv3Sidebar.ts:274`) and `startNewSession` is one line (`sv3-sessions.ts:443`); the in-flight abort on new-session is not wired (`SearchV3View.ts:1006` `onSessionNew`) | A (abort) / **C** for the 54-field reset protocol — 818 §6: "triplicated reset protocol … projections have no state to reset" |
| A14. A composer draft survives a reload | `:499-506` `DraftPersistence('unified-chat.composer')` | Tempdoc 609 §R (T2.1) | **lacks** | A |
| A15. Navigating away with a non-empty draft says so, once | `:1123` `notifyDraftKeptOnce` | Tempdoc 609 §R (T1.4) — the reassurance for instance-retention | **lacks** | A |
| A16. In-flight/partial state settles on hide; recoverable task state does not | `:1077` `settleTransients` (+ `check-surface-task-state-retention.mjs`, a gate that exists *because* this window wiped a draft on mount) | Tempdoc 609 instance-retention | **partial** — v3 has far less transient state to settle | A (the discipline) / C (the 20-field ritual, per 818 §6) |

## B. Context economy (what the model actually sees)

| Capability | Where (file:line) | Why it exists | v3 status | Tier |
|---|---|---|---|---|
| B1. The reader can see how much of the context window the last turn occupied | `:1642` `renderContextMeter`, `:417` `contextPromptTokens` (from the chat `done` payload) | Tempdoc 610 §E.4 | **lacks** | B |
| B2. …and where it went (system / conversation / retrieved split) | `:419` + `:528` `contextBreakdown`, rendered in the meter | Tempdoc 610 §I.2 | **lacks** | B |
| B3. The effective context can be rewound to a chosen turn while the transcript stays whole | `:1581` `resetContextTo`, `:1595` `restoreContext`, `:1627` `floorIndex`, `:1840` `renderFloorDivider` | Tempdoc 610 Phase C | **lacks** | B |
| B4. Everything above a turn can be compacted (summarize-then-floor), and the summary is editable | `:1608` `compactTo`, `:1904` `commitFloorSummaryEdit`, `:425` `compacting` | Tempdoc 610 Phase D + §E.2 | **lacks** | B |
| B5. A single message can be excluded from the next prompt without leaving the transcript | `:1363` `toggleMessageExcluded`, `:1696` `renderExcludedSummary`, `:1717` `includeAll` | Tempdoc 610 §E.3 | **lacks** | B |
| B6. The reader can inspect what the last turn actually saw | `:1729` `openContextInspector`, `:1778` `buildInspectorView` (phases + segments) | Tempdoc 610 §K | **lacks** | B |
| B7. A retrieved source can be hidden from the context, consistently across every surface showing it | `:283-290` `excludedSources` store, `:4665` `toggleSourceExcluded` | Tempdoc 610 §J.3 — one source of truth across chips + rail | **lacks** | B |
| B8. A run that hits its budget asks the reader rather than silently truncating | `:3748` `onRaiseBudget` (+`RAISE_BUDGET_STEP_TOKENS`), `:3765` `onBudgetDecision('finalize'\|'stop')` | 565 run-control seam | **partial** — v3 has the budget prompt and its decision (`sv3-run.ts:218` `Sv3RunPromptBudget`, `:251` `projectSv3RunPrompts`); it lacks the *raise by a step* option | A |
| B9. A run that hits the context horizon asks continue / summarize / stop | `:3772` `onContextDecision`, `projectContextHorizon` (`:110`) | 565 | **has** (`sv3-run.ts:225` `Sv3RunPromptContext`, rendered `Sv3Main.ts:608`) | — |

## C. Evidence, grounding, and answer honesty

| Capability | Where (file:line) | Why it exists | v3 status | Tier |
|---|---|---|---|---|
| C1. The answer carries an honest frame line naming its basis, duration, and model | `:4974` `frameFor`, `:5003` `formatReceiptTail`, `:5027` `renderAnswerFrameLine`, `answerFrame`/`answerFrameLabel` (`:206-207`) | **810 §T-B explicitly carries this forward as owner-valued credit**: *"Based on your documents — per-sentence grounding not verified · 45.7 s · Qwen_Qwen3.5-9B"* is "an honest disclaimer worth preserving" | **partial** — v3 has a run receipt label (`sv3-run.ts:442` `sv3RunReceiptLabel`) but no answer-frame honesty line | **A** |
| C2. Grounding coverage is stated, and degraded grounding is said out loud | `:4513` `renderGroundingBadge`, `groundingCoverage`/`groundingDegraded`/`sourcesAreChunkPrecise` (`:204-209`) | 559 Authority IV / 565 §15.B | **lacks** | B |
| C3. Inline citation marks resolve to a hover preview | `:1248` `onCiteRefHover`, `:1269` `onCiteRefLeave`, `CitationHoverCard` (`:223`) | 565 | **lacks** | A |
| C4. Claims + retrieval sources resolve to citation marks through the ONE shared resolver | `:3530` `resolveAnswerCitations`, `:3545` `resolveClaimCitations`, `citationResolve.ts` (`:251-255`) | 565 §15.B / 559 Authority IV; registered in `governance/execution-surfaces.v1.json:213` | **partial** — v3 renders markdown through the shared `MarkdownBlock` (`Sv3Main.ts:36,202`) and carries a `Citation` type (`sv3-sessions.ts:31`), but does not go through the shared claim→citation resolver | A |
| C5. Sources are echoed under the answer, collapsible, in sync with the rail and the inline marks | `:4608` `renderSourceChips`, `:4672` `onChipSelect`, `:636` `sourceChipsToggles`, `selectedSource` store (`:196-202`) | 565 §13.8 P3 + §12.3.E | **lacks** | B |
| C6. A bounded evidence index is always visible at width (not a scroller) | `:3103` `renderEvidenceRail`, `:319` `EVIDENCE_RAIL_MAX_VISIBLE = 3` + "Open all · N" | **Tempdoc 814 §D3** — a settled parameter of the chrome-allocation design: the rail is a bounded INDEX, not a nested scroll region | **lacks** | B |
| C7. Following a citation lands the reader on the cited lines of the document | `:3131` `renderDocumentPane`, `:488-490` `readingDocPath`/`readingHighlightRange`, `:968` the shared `inspectorState` signal | Search Thread S6 (the Reading Stage); `Shell.onCitationSelect` funnels through the one store | **lacks** | B (the citation-follow half; the search-result-open half is **D**) |
| C8. The model's decontextualized standalone question is shown back to the reader | `:560` `rewriteNote` | Tempdoc 603 C2 (transparency) | **lacks** | A |
| C9. Reasoning/thinking output is rendered as its own controlled block | `:645` `ReasoningController`, `ReasoningBlock` (`:222`) | 565 | **lacks** | A |

## D. Agent-run hosting (the parity mission's own axis)

| Capability | Where (file:line) | Why it exists | v3 status | Tier |
|---|---|---|---|---|
| D1. One canonical thread RECORD is fetched from the backend and projected — the window is not the authority | `:3433` `refreshUnifiedThread`, `projectUnifiedThread` (`:88-97`), `fetchUnifiedThread` (`:104`) | Tempdoc 561 P-A/P-A2 — chat turns and agent activity interleave from ONE record | **lacks** — v3's turns are FE-held (`sv3-sessions.ts:68` `Sv3Turn`); the run feed projects from the live controller only (`sv3-run.ts:168` `projectSv3RunFeed`) | **A** |
| D2. A failed record refresh says so instead of silently leaving stale live state on screen | `:3453` `renderThreadRefreshFailedNotice`, `:672-678` | Tempdoc 727 F-8 — the EMPTY-on-failure fallback was deliberate but *completely silent* | **lacks** | A |
| D3. A run in progress is re-attached after a reload / in another tab | `:3496` `ensureAgentCtrl`, `:688` `reattachChecked` (one-shot guard) | Tempdoc 577 Root I (#1d) | **partial** — v3 has presence recovery (`SearchV3View.ts:825` `syncRunPresence`, `sv3-run.ts:409` `sv3RunNeedsPresence`, `:342` `hasServerAcknowledgedLocalDispatch`) but no cross-tab reattach on cold load | A |
| D4. A live run can be steered mid-flight through the ONE control-intent seam | `:2178` `renderSteerInput` → `dispatchRunControl({kind:'interject'})` (`:234`) | Tempdoc 565 §30 (and §33 records the *wrong-gate* bug: gated on the view's `isStreaming`, which is false during an agent run) | **has** (`SearchV3View.ts:757` `steerLiveRun`) | — |
| D5. A run can be halted | `:3758` `onHaltRun` → the same seam | 565 §30 | **has** (`SearchV3View.ts:770` `haltRun`, `:778` `deliverHalt`) | — |
| D6. Every run-control affordance goes through ONE seam (registered) | `:234` `runControlIntent`; `governance/steering-surfaces.v1.json:9` names this file | 565 §30 | **partial** — v3 dispatches through the shared controller but is **not registered** in `steering-surfaces.v1.json` | A + booby-trap |
| D7. Where-am-I / how-do-I-move in a long run is owned by ONE reading-position model | `:718` `NavigationController`, `:3201` `renderRunSpine`, `:304` `computeSpinePositions`, `:313` `SPINE_CLUSTER_MIN_GAP_PX = 14` | 565 §21 + **814 §D4** (aggregation threshold) | **lacks** | B — **817 §2 says the shipped form is a "six-glyph private symbol language: no legend, colour-only authorship distinction, ~4px hit targets, competing native scrollbar."** Adopt the capability, not the form |
| D8. Run telemetry / lifecycles are available but collapsed by default | `:3557` `renderActivityRail`, `:446` + `:620` `activityRailExpanded` | Round-14 finding 12(a) — "developer instrumentation with a disclosure triangle should start closed", made a *declared* default rather than whatever `<details>` was left in | **lacks** | B |
| D9. Each run's trace collapses independently in a multi-turn session | `:4853` `renderRunTrace`, `:630` `runTraceToggles` (per-segment, keyed by first item id) | 565 §12.3.C + multi-turn fix (A) | **lacks** | B |
| D10. Tool activity renders as structured cards, not log lines | `:5316` `renderToolActivity`, `ToolCallCard` (`:242`), `:302` `stepPresentation` (the ONE run-step presentation projection) | 565 §17 | **partial** — v3 has typed feed tool items (`sv3-run.ts:115` `Sv3RunFeedTool`) rendered in `Sv3Main.ts` | A |
| D11. A tool's evidence is openable back into the same data the card rendered from | `:5357` `handleToolEvidenceOpen`, `:179` `findAgentSearchHit` | Search Thread S7 decision 4 | **lacks** | B |
| D12. A multi-agent handoff is a structured card, not prose | `:244` `HandoffCard` | Tempdoc 585 §D Phase 2 (D2) | **lacks** | B — note: the owner REJECTED *parallel concurrent runs* (§4b), which is a different thing from a sequential handoff; flag for the owner rather than assume |
| D13. The reader can see the agent's authority space before it acts ("what can it do, what will ask first") | `:2222` region + `:440` `showAbilities`, `AgentAuthorityPanel` (`:246`) | Tempdoc 577 §2.13 #17 | **lacks** | B |
| D14. Chrome grades on the agency posture; the reader sets the posture | `:172` `agencyPosture`/`postureChrome`/`deriveAffordance`, `:173` `getAutonomyLevel`, `:176` `AutonomyDial`, `:933` re-render on dial change (chrome only — "touches no record and no in-flight run") | Tempdoc 561 C-2 (graded continuum) | **lacks** | B — §4b already ratifies the adaptation: the donor's per-session provider picker maps to an effort/mode control |
| D15. A finished run leaves a reviewable retrospective | `:182` `openRetrospectiveAt`/`toggleRetrospective` | Tempdoc 561 surface tier; **814 §"Finding 7's structural half"** covers its naming/authority | **lacks** | B |
| D16. Sources drawer is reachable at narrow widths (the rail's fallback) | `:183` `toggleSources`, mounted Shell-side (`Shell.ts:535-541`) | 565 §12.3.E fix F — one `SourcesPane` per surface, never two | **lacks** | B |
| D17. A streaming answer has a live overlay distinct from the settled record | `:4934` `renderLiveOverlay` | 561 P-B | **partial** | **C** — superseded: v3's primary-action-slot rule (`sv3-run.ts:493` `sv3PrimaryAction`; the slot IS Stop while running) is the donor's model for the same problem |

## E. Degraded state, locked stores, and offline honesty

| Capability | Where (file:line) | Why it exists | v3 status | Tier |
|---|---|---|---|---|
| E1. Reduced capability is communicated from the readiness authority, with a one-click remedy | `:2356` `renderDegradationBanner`, `:2462` `renderDegradationRemedy`, `:2480` `openRemedyTarget`, `readinessNotice`/`warrantsSearchDegradationBanner` (`:70-76`) | 593/600 truthfulness workstream; 596 §11.4 for the remedy nav | **lacks** — v3 has no degradation surface at all | **B** (the *form* is exactly 810 §T-B's ~100px banner; the *capability* is not optional) |
| E2. …deduped by reason CODE, not by string-matching the wording | `:360` `dedupDegradationCauses` + `isReindexCause` | Search Thread Round-2 R1a: "dedup by comparing notice code, not string matching" | **lacks** | A (rides E1) |
| E3. …with disclosure gated on the app-wide Simple/Detailed authority, not a per-surface bookmark | `:81` `subscribeUiMode`/`isAdvancedMode`, `:822`, `:2366-2375` | **Tempdoc 738** — replaced tempdoc 687's per-cause-set "seen-hash" bookmark | **lacks** — v3 does not read `uiModeState` at all | B |
| E4. A locked conversation store makes the transcript unreadable rather than stale-readable | `:4376` `renderHistoryLocked`, `:436` + `:600` `historyLocked` | Tempdoc 629 (LAYER) | **lacks** — v3 handles the refusal but has no locked *view* | A |
| E5. A lock taken elsewhere (idle/auto-lock, another tab) is picked up live | `:1038-1048` `applyAiState` derives `historyLocked` from the polled `conversationProtection.state`; documented ~10s bound | **Tempdoc 734** §"Locked thread stays readable after lock" — before this it was write-once from the initial 423, so a lock elsewhere left the transcript readable *forever* | **lacks** | **A** |
| E6. A send the locked store refused says so, in the reader's words, and clears when the lock lifts | `:6019-6023` + `:6041` `noteRefusedWhileLocked`, cleared at `:1047` | Tempdoc 734 round-14 F4 | **has** — and better by construction: v3 has exactly ONE 423 consumer (`sv3-ask.ts:75,231`), settled as a distinct sink (`SearchV3View.ts:676` → `reasonFor('conversations.locked')`) | **C** (v3's model supersedes; 818 §6: locked-path text loss `:5673` is killed by L9's session-level gate) |
| E7. Why a send is blocked is always stated, and the control stays focusable | `:2791` `sendBlockedReason`; `unavailableBecause` (`:78`) | the availability authority's contract: the reason stays reachable, a natively-disabled control is not even focusable | **has** (`Sv3Composer.ts:478,599-600`, `SearchV3View.ts:613-625` via `projectAvailability`) | — |
| E8. "Offline" carries exactly ONE sense (the engine), enforced | `check-offline-single-sense.mjs:50-57` allow-lists this file's four phrases | Tempdoc 813 §6, regression home for human-validation finding 11 | **partial** — v3 has no allow-list entry; new copy must not re-split the word | A + booby-trap |
| E9. A stream error becomes friendly wording, and an abort is not an error | `:6023-6024` `friendlyStreamError`, `AbortError` excluded | — | **partial** — v3 has a distinct `onFailed` sink (`sv3-ask.ts`) but no shared friendly-error mapping | A |
| E10. A zero-document corpus offers the fix rather than claiming to search 0 files | `:3021-3039` `renderLanding`, "Add folders in Library to start searching" | **Tempdoc 811 C-4** — "a reported `0` is real" | **lacks** — `Sv3Empty.ts` is the donor's decorative empty state, with no corpus/remedy fact | A |
| E11. Honesty facts never hide behind hover; elaboration may | `818 §6b L14`; the window's shared results card already follows it | 818 §6b (owner-directed law) | **partial** — v3 states the rule for its own rows (`Sv3SessionRow.ts:317`: "WHAT DOES NOT GO: the status dot") | A |

## F. Tiers, dispatch, and the intent ladder

| Capability | Where (file:line) | Why it exists | v3 status | Tier |
|---|---|---|---|---|
| F1. One input escalates across intent tiers (retrieve → ask → delegate → structured) | `:2741` `renderEscalationRungs`, `:378` `rungLabel`, `:2919` `escalateAsk`, `:2902` `runRoute` | 570 Move H / 577 §3.7; **this is the 818 "escalating-input thesis"** §4b names as the eventual synthesis point | **partial** — v3 has ask vs delegate on Enter / Ctrl+Enter (`sv3-run.ts:488` `SV3_SEND_HINT`); no structured tier, no rung surface | B |
| F2. The tier is sticky when chosen explicitly and derived otherwise; capability appearing never moves the user | `:548` `explicitAffordance` + the computed `affordance` accessor pair, `:779-783`, `:1034-1036` | **Search Thread S5a decision B14** — the AI-online auto-upgrade was deleted on purpose | **lacks** (v3 has no tier concept beyond the two send verbs) | B |
| F3. A per-turn route is guessed, shown, and overridable | `:86` `inferRoute`, `:2505` `currentRoute`, `:2969` `renderRouteRow`, `RouteChip` (`:87`), `:467` `routeOverride` (a transient — a return re-runs the guess) | Search Thread D2/D3 stage S2 | **lacks** | B |
| F4. A schema can be attached, and the attachment is a deliberate act distinct from the draft | `:5612` `renderSchemaInput`, `:554` `schemaAttached` (S5a decision 6) | S5a decision 6 | **lacks** | B — note the catalog obligation in §K |
| F5. Every dispatch resolves through one shape-resolution site | `:2278` `dispatchShape`, `:39` `resolveDispatchShape`, `buildRequestBody` (`:23`) | 561 surface tier | **partial** — v3 has one ask issuance site (`sv3-ask.ts:51` `SV3_ASK_SHAPE_ID`) and one delegate path | A |
| F6. A deeplinked shape presets the window's mode (every entry point lands here, in the right mode) | `:839-851` `presetByShape` | 561 surface tier | **lacks** | A |
| F7. Free-chat (`core.free-chat`, the `'none'` affordance) | `:846`, `:1897`-era `restoreRecentConversation` | — | n/a | **C** — `governance/sandbox-coverage.v1.json:56`: "NOT REACHABLE BY ANY USER as of round 7". Do not port a dead path; the *fix* it names (an unscoped Ask resolving to `'none'`) is a live-window decision, not a v3 obligation |
| F8. Workflow trigger + catalog picker | `:3898` `loadWorkflows`, `:3917` `renderWorkflowTrigger`, `:729` `workflowPending`, `:856-859` | 565 §15.C retired the standalone workflow surface into this window | n/a | **C** — `governance/sandbox-coverage.v1.json:59`: "NO entry point exists in any shipped build … a retire-with-a-sweep residue: the replacement was never wired to a live trigger". 818 §5b lists it unchecked. Leaving it behind IS the sweep |
| F9. Affordance preview (what the current toggles will dispatch) | `:2222` `renderAffordancePreview` | — | **lacks** | **C** — the affordance/schema desync on card fork is 818 §6's `:3735` defect, killed by v3's pure-route model (inferred) |

## G. Shell / app integration (things that stop working if the window is swapped)

| Capability | Where (file:line) | Why it exists | v3 status | Tier |
|---|---|---|---|---|
| G1. It IS the canonical interaction surface — the rail landing and the default surface | `CorePlugin.ts:130-139` (RAIL), `Shell.ts:303,320,1955,2068-2070,2908`, `governance/interaction-surfaces.v1.json:4-5` | 561 surface tier: at most ONE visible surface may consume the core interaction shapes | **lacks** — v3 is `DEEPLINK` + DEVELOPER, off-rail (`CorePlugin.ts:108-113`) | A at cutover + booby-trap |
| G2. Command-palette navigation actions route here | `substrates/actions/index.ts:793,797` (`shell.go-to-search`, `shell.go-to-chat`), `CommandPalette.ts:433-438` | the retired standalone search surface folded in here | **lacks** — v3's palette (`Sv3Palette.ts`) is window-local; §4b defers real palette commands + the keybinding registry | A |
| G3. Free text in the palette becomes a query in this window | `CommandPalette.ts:361-369` | — | **lacks** | A |
| G4. `Ctrl+L` / `/` from anywhere focuses the composer | `Shell.ts:798` dispatches `jf-focus-composer`; `:909` + `:3072` `onFocusComposer` | Search Thread D2/D3 stage S2 | **lacks** — v3 handles only host-scoped keys (`SearchV3View.ts:365,482`) | A |
| G5. Intent-router `query`/`answer` verbs lower onto this surface | `Shell.ts:1508-1517`, `:1202`, `:1255` | 548 §4.5 | **lacks** | A |
| G6. An `answer` intent auto-sends once, when prompt and capability are both present | `:1060` `maybeAutoRun`, `:869` `takePendingAutoRun` | 548 §4.5 — idempotent by design (flag cleared before dispatch) | **lacks** | A |
| G7. A cross-surface selection scopes the next send | `:875-891` `refreshDocsFromSelection`, `:5680` `effectiveDocIds`, `selectionState` (`:59-65`) | Tempdoc 526 §14.5 T3 (+ 515 FIX-1) | **lacks** | B |
| G8. The compose handoff (`askAi` from elsewhere) lands with its payload | `:39` `takePendingSelection`/`takePendingForceShape`/`compose`, `:40` `selectionItemToWirePayload` | — | **lacks** | B |
| G9. The window reports its own measured content-box width to the ONE breakpoint authority, so CSS and TS cannot disagree | `:1185` `observeSurfaceWidth`, `reportLayoutWidth` (`:67`), `:697-701` | **798 round 8** — the mount gate and the grid must decide on the same box the `@container` rules resolve against | **lacks** | A |
| G10. Chrome that may spend height on a tall window yields on a short one | `:831` `subscribeShortViewport`, `:707` `shortZone` (defaults to NOT short so an unknown viewport keeps full form) | **Tempdoc 814 §D6** — the block-axis sibling of `wideZone` | **lacks** | B |
| G11. Global AI activity indicator is settled when a stream is torn down | `:1080` `setAiActivity({state:'idle',…})` | 609 Phase 4 | **lacks** | A |

## H. Input, keyboard, accessibility

| Capability | Where (file:line) | Why it exists | v3 status | Tier |
|---|---|---|---|---|
| H1. Enter sends; the alternate modifier escalates | `:2872` `handleComposerKeydown`, `:2892` `handleComposerSubmitAlt` | Search Thread stage S2 | **has** (`sv3-run.ts:488`) | — |
| H2. Long runs are navigable by keyboard, from the window (not from an unfocusable div) | `:4734-4736` `onConversationKeydown` (J/K), `:737-739` window-level listener | **565 §33** — the div-scoped `@keydown` never fired for a real user; 818 §6b slice 5 upgrades this to ⌥↑/⌥↓ with "never while typing" and an Escape order | **lacks** | B |
| H3. Toggle state is carried in the accessible LABEL where the primitive has no `aria-pressed` passthrough | `:378` `rungLabel` ("(current mode)"), `:2948` `renderPinToggle` | **Round-13 review P3** — `?data-pressed` is a CSS hook, invisible to assistive tech | **partial → superseded** — v3 uses native `aria-pressed` on real buttons (`Sv3SessionRow.ts:579`, `Sv3Sidebar.ts:284`) | **C** (superseded by v3 not using `jf-control` for toggles) — but keep the *rule* if a `jf-control` toggle ever appears |
| H4. Escape closes a transient panel; a per-panel Escape order exists | `:1389`, `:4249`; the ordering law is 818 §6b slice 5 | — | **partial** (`SearchV3View.ts:482` Escape/Home; `Sv3SessionRow.ts:478` rename cancel) | A |
| H5. Reduced-motion is honoured in the commit choreography | 818 §6b slice 4 ("~700 ms, reduced-motion instant") | 818 §6b | **has** (sv3 token sheet / morph) | — |

## I. Chrome economy as capability (814's design, stated as requirements)

These are **not** forms. Each is a property the shipped window was retrofitted with after 810
§T-B measured ~60% chrome at 790px; v3 inherits the obligation, not the implementation.

| Capability | Where | Why it exists | v3 status | Tier |
|---|---|---|---|---|
| I1. Something owns the SUM of chrome height — the answer region holds a stated minimum share at a pinned viewport | `814 §D1`; enforced by `governance/ui-proportion-baseline.v1.json:94,118,178,202` (`minShareOfSelector: jf-unified-chat-view`, floors 0.55 / 0.45) | **810 §T-B root cause**: "each band was independently justified by its own workstream … none of them owned the sum" | **lacks** — no v3 rows in the baseline | **B** + booby-trap |
| I2. One scroll region per surface, page-level | `814 §D3` | 809 finding 13: "each nested scrollbar marks a place the layout ran out of room and solved it locally" | **partial** (donor grid is single-scroll by construction) | B |
| I3. Scrollbar geometry means scrolling, and nothing else | `814 §D4` | the scrollbar idiom was reused for two non-scroll meanings (the 565 run spine; the telemetry meters) | **has by construction** (inferred) | C (killed by construction) |
| I4. No status fact renders in two persistent surfaces simultaneously | `814 §D5` "one authority, one pointer" | 810 §T-B's fingerprint: three facts rendered six times; *Reduced capability* twice ~660px apart | **has by construction** — v3 has one banner slot (`Sv3Composer.ts:148`: "This window's one banner is the availability reason") | C (killed by construction) — but re-check the moment E1 lands |
| I5. In-flow chrome is summary height; detail is on demand | `814 §D2` + `818 §6b L14` | 814 | **partial** | A |
| I6. Every movable boundary is clamped by the minimum honest form on both sides; rails remember, decks reset | `818 §6b L13` | owner-directed law | **has** for the sidebar (`sv3-sidebar-sizing.ts:26,89-132`; double-click resets, `SearchV3View.ts:439,501`) | — |
| I7. Counts are derived from the set on screen, never independently | `818 §6 L6`; the recurrence is 597 → 690 I1 → 817 S5 | **817 §2** names the pattern: *"a number true of a narrow scope rendered as though it describes the whole"* | **has by construction** | C (killed by construction) — the standing obligation is not to reintroduce it |

## J. Deferred by the standing directive (search-adjacent) — recorded, not analysed

One line each, no recommendation beyond `D`.

- J1. Live search-as-you-type retrieve tier — `:3961` `renderRetrieveTier`. **D**
- J2. Committing a live search to a frozen record — `:4132` `commitLiveSearch`, `:329` `CommittedSearch`, `:4206` `renderCommittedSearches`. **D**
- J3. Recent-query trail dropdown — `:4107` `rememberQueryInTrail`, `:4244` `renderQueryTrail`. **D**
- J4. Persisted pinned searches + landing pin strip — `:2948` `renderPinToggle`, `:3043-3057`, `:3063` `runPinnedSearch`. **D**
- J5. Scope chips (file + set) constraining both search and retrieval — `:2847` `renderScopeChipRow`, `:5680` `effectiveDocIds`. **D**
- J6. Facet chips and facet toggling — `:4351` `handleRetrieveFacetToggle`, `:166-170`. **D**
- J7. Result-card open / multi-select / "ask about this set" / fork-to-new-query — `:4053`, `:4285`, `:4337`, `:4093`. **D**
- J8. Search-event posting into the thread record — `:4175` `postSearchEvent`. **D**
- J9. Restored (record-side) search item rendering — `:5234` `renderRestoredSearchItem`. **D**
- J10. Document pane as the *search-result* reading surface (the citation-follow half is C7) — `:3121-3147`. **D**
- J11. The `retrieve` BASE tier's shape-less contract (`submitSearch`, no ConversationShape) — `governance/intent-tier-coverage.v1.json:12-13`. **D**

---

## K. The five highest-value items for the agent-parity mission

Ranked for **T3 Code product functionality parity**, not for general completeness.

1. **A1 — conversations survive the process** (`conversationListStore`, Tier A).
   v3's sessions are an in-memory list. Every other item in §A is downstream of this: no
   resume (A2/A3), no stable turn ids (A4), so no edit (A5), retry (A6), branch (A7), or
   pager (A8). The donor product's shape is "sessions = conversations"; a conversation that
   dies with the tab is not that shape. This is the single largest gap and it needs no owner
   taste call — the authority is shared and already imported by other surfaces.

2. **D1 — the run/thread RECORD as the authority** (`fetchUnifiedThread` + `projectUnifiedThread`,
   Tier A). v3 projects its feed from the live controller only, so a run's history exists only
   while the controller does. The shipped window's hardest-won lesson (561 P-A) is that the
   window must be a projection of one backend record; adopting it now is far cheaper than
   retrofitting it after F-series turns accumulate. D2 (the not-silent refresh failure, 727 F-8)
   rides along for near-zero cost.

3. **E5 + E4 — locked-store discipline beyond the send refusal** (Tier A). v3 already has the
   *best* 423 handling in the repo (one consumer, one sink). What it lacks is the half tempdoc
   734 had to add later: a lock taken elsewhere (idle/auto-lock, another tab) must clear the
   rendered transcript, and a locked store must have a *view*. Shipping F-series turns without
   this recreates exactly the defect 734 fixed — a transcript readable forever after the store
   locked. It is ~15 lines riding the `aiState` subscription v3 already holds.

4. **E1 (+E2/E3) — communicating reduced capability with a remedy** (Tier B). v3 currently has
   no way to say "the thing you are about to ask cannot work, and here is the fix." This is the
   one Tier-B item in the top five, and deliberately so: it *must* be re-formed (810 §T-B
   measured the shipped form at ~100px of a 790px window), but the alternative to re-forming it
   is not having it, and an agent window that silently fails when the model is degraded is
   worse than a window with a banner. Pair it with E3's Simple/Detailed gating so the re-formed
   version has a disclosure story from day one.

5. **C1 — the honest answer frame** (Tier A). *"Based on your documents — per-sentence grounding
   not verified · 45.7 s · Qwen_Qwen3.5-9B."* 810 §T-B singles this out as owner-valued credit
   worth preserving, and it is the cheapest high-trust element in the whole window: one line,
   entirely derivable from data v3's ask tier already receives. v3 has the run-side analogue
   (`sv3RunReceiptLabel`) and not the answer-side one.

**Runners-up, in case one of the above is blocked:** B8's raise-budget step (v3 has the prompt,
not the option), D3's cross-tab reattach, G4 (`Ctrl+L` from anywhere), A14 (draft survives
reload), A10 (export).

## L. Booby-traps for an eventual sweep

`UnifiedChatView` / `unified-chat` / `jf-unified-chat-view` has **252 references across
`modules/ui-web/src`** and **20 distinct files outside it**. A promotion PR that does not
handle these leaves 742-class false authority. Enumerated:

**Registers that name the file as a referencer (an unregistered successor fails, or a stale
entry lingers):**
1. `governance/execution-surfaces.v1.json:407-420` — two evidence entries (`evidence-fe-unified-chat-view`, `evidence-fe-unified-chat-request`); the `execution-surface` gate fails on an *unregistered* `SearchTrace` referencer, so v3 needs its own row the moment it touches one.
2. `governance/run-renderers.v1.json:11,22,41,49,62,85,100,108` — six renderer families registered to this path; a v3 re-render of any of them without registration is an atom fork.
3. `governance/steering-surfaces.v1.json:9` — v3 dispatches run control but is **not listed** (D6).
4. `governance/live-channels.v1.json:171` (this window's stream row) and `:180` (search-v2's, which explicitly says "the sunset criterion retires one of these rows with its window" — v3 needs its own row, and there are now **three** windows for two rows).
5. `governance/composition-surfaces.v1.json:10`.
6. `governance/interaction-surfaces.v1.json:4-5` — `canonicalSurface` + `canonicalMountTag` are pinned to this window; the `interaction-surface` gate enforces at-most-one visible consumer of the core shapes, so promoting v3 to RAIL **while UnifiedChatView is still RAIL will fail the gate** (this is a feature).
7. `governance/intent-tier-coverage.v1.json:6,10` — `window` points at this file and the gate regex-parses its `presetByShape` literal byte-for-byte against the Java `CORE_USER_INTERACTION_SHAPES` (`CoreConversationShapeCatalog.java:39-46`: rag-ask, free-chat, extract, agent-run, workflow-run). A successor without an equivalent table breaks `check-intent-tier-coverage`. Note F7/F8 above: two of those five shapes are governance-declared unreachable.
8. `governance/ui-step-coverage.v1.json:26-28` and `scripts/jseval/jseval/ui_step_index.json:20` — the ui-shot step registry maps steps to this source path; `check-ui-step-coverage` will flag the successor.
9. `governance/ui-proportion-baseline.v1.json:94,118,140,178,202` — four `minShareOfSelector: "jf-unified-chat-view"` floors plus the `chat-spine` presence pair; these are shrink-only ratchets keyed on a selector that will not exist post-sweep.
10. `governance/sandbox-coverage.v1.json:37,56,57,59` — surface + shape coverage rows citing `UnifiedChatView.ts` line numbers and `data-testid`s (`escalation-structured`). Two rows are `exempt` with an explicit "EXPLICITLY FORBIDDEN routes to a green flag" clause — do not satisfy them by renaming.

**Scripts / gates that read the file directly:**
11. `scripts/ci/check-intent-tier-coverage.mjs:7`.
12. `scripts/ci/check-offline-single-sense.mjs:50-57` — a per-file allow-list of four "offline" phrases; v3 copy needs its own entry or the token lint bites (E8).
13. `scripts/ci/check-surface-task-state-retention.mjs:7` — exists *because* this window's `connectedCallback` wiped the draft; it will apply to the successor's retention story.
14. `scripts/ci/check-thread-event-kinds.mjs:71` — error text names `renderUnifiedItem` as the place a new event kind must be handled.
15. `scripts/governance/gates/interaction-surface/{enforcer,truth-table}.mjs` + `enforcer.test.mjs` + `_fixtures/interaction-surface/{positive,negative}/**` — the tag constant `ONE_WINDOW_MOUNT_TAG = 'jf-unified-chat-view'` is hard-coded in fixtures and test expectations (`enforcer.mjs:210`).

**Non-governance residue:**
16. `scripts/agent-analytics/hooks/subagent-guide.mjs:74` — the always-injected subagent brief names this file (with a stale ~5,400-line figure; actual 6,083).
17. `modules/ui-web/src/shell-v0/plugin-api/coreInteractionShapes.ts` — `ONE_WINDOW_MOUNT_TAG`, the FE mirror the gate keeps in sync with Java.
18. `Shell.ts` — 12 sites (`:303,320,535-541,798,1202,1255,1341-1349,1508-1517,1955,2068-2070,2305,2361,2908`), including the narrow-width `SourcesPane` drawer mounted in `OverlayHost` *on behalf of* this window (a sibling tree it cannot mount itself) and the `activateSurface` default-landing call.
19. `substrates/actions/index.ts:793,797` + `CommandPalette.ts:361-369,433-438` — palette actions and free-text routing point at `core.unified-chat-surface`.
20. `renderers/component-vocabulary.generated.ts` — regenerated artifact; a sweep must regenerate, not hand-edit.

**Structural traps (not greppable):**
- `views/unifiedChatStyles.ts` (621 Phase 1), `views/unifiedChatRequest.ts` (621 Phase 2 — `ThreadMessage`, `buildRequestBody`, `SHAPE_LABELS`, `CONVERSATION_ZONES`), `views/unifiedThreadProjection.ts`, `views/unifiedThreadClient.ts`, `views/budgetProjection.ts`, `views/runStepPresentation.ts`, `views/runSpinePresentation.ts` are extractions *of* this window. Some are genuinely shared (`buildRequestBody` is what F1 mined); others die with it. A sweep must classify each rather than delete the directory.
- **Three windows, two of everything.** 818 §5 committed to a two-window sunset; there are now three (UnifiedChatView, search-v2, search-v3). §4b Phase D already names this as a separate owner-gated tempdoc. Every register row above currently has a *search-v2* sibling that will need the same decision.
