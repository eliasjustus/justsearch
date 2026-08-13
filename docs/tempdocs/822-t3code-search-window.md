# 822 — Search window rebuilt on the T3 Code donor system

```
status: ACTIVE
created: 2026-08-12
owner-decisions (owner conversation, 2026-08-12, this session):
  - T3 Code (pingdotgg/t3code) is the SOLE design donor. T3 Chat is retired as a donor
    ("focus strictly on T3 code and forget T3 chat") — its capture artifacts remain in the
    session scratchpad as dated reference only; nothing in this arc consumes them.
  - New window FROM SCRATCH: zero presentation code reused from UnifiedChatView or
    search-v2. Shared authorities (search API clients, stores) will be reused when real
    data is wired — per the 818 guardrail "from-scratch components, shared authorities".
  - NOT on main, NOT in any installer: this branch stays local. No push, no PR, no
    publish, no merge without a new explicit owner instruction.
  - Autonomous proceed granted ("proceed autonomously from here on") — slices execute
    without blocking on checkpoints; screenshots surfaced at every slice boundary so the
    owner can redirect at any time.
related: 818 (Search v2 — strangler/route-registration recipe reused; its window is NOT
  touched), 820 (feel-budget predicates, other worktree — complementary, chat-surface
  latency; its W2 T3 Code interaction-pattern notes feed a LATER slice, not this one)
```

## 0. Problem

Agent-generated UI design failed across multiple rounds (evidence: this session's
frontend-history audit — every attempt was palette-scoped, no brief ever contained a
positive visual reference, honesty-law culture defended chrome accretion). Owner decision:
copy the design of an admired product instead of originating one. Research basis (session
2026-08-12): donor CODE is the measured-best reference input (beats screenshots; DesignBench
edit 8.4-8.6/10 code-only vs 7.4-7.7 image-only); region-by-region beats whole-screen
(up to +48% at high complexity); critic loops plateau at ~3 cycles; agent eyes cannot
self-judge geometry (BlindTest ~58.6%) — verification must be measurement, not vibes.

## 1. Donor authority

- **Spec:** `docs/tempdocs/822-donor/t3code-system.md` (mined 2026-08-12; every claim
  cites file:line into the clone).
- **Source:** github.com/pingdotgg/t3code @ `b73232bdd31e83914a8a943960c7dc4b6390b39b`
  (2026-08-12). Session-local clone in scratchpad; re-clone at that commit if absent.
- **License:** MIT (T3 Tools Inc.). Derived CSS shipped in a build must carry a
  THIRD-PARTY-NOTICES entry. Added in slice 1 so it can never be forgotten at cutover.
- Build briefs consult ONLY this spec + clone. No other products, no invented flourishes,
  no T3 Chat vocabulary.

## 2. Adopted donor laws (all cited in 822-donor/t3code-system.md)

1. **Token graph flows primitive → semantic → component; dark mode = semantic-token
   redefinition, never component re-authoring.** JustSearch twist: tokens live on the
   window HOST element (not `:root`) so the donor palette cannot leak into the shipped app;
   custom properties inherit down through every nested shadow root (donor §8.1 verifies
   the mechanism in production).
2. **Geometry tokens are semantic and test-enforced** ("cannot quietly drift apart") —
   every component's use of `--control-radius` / inset tokens is asserted by unit test.
3. **Two-knob radius:** derived additive ladder off `--radius` for surfaces; independent
   `--control-radius` for controls.
4. **Surface encodes interaction; content encodes status.** One surface model for rows;
   states express in content. (Donor tried elevation-per-state and recorded the failure.)
5. **3-color status budget:** color only for act-now / in-motion / broken; resting state
   unlabeled.
6. **Dark is an elevation inversion:** light casts shadows down; dark catches light
   (low-alpha white fills + 1px inset top highlight, drop shadows removed). Dark gets more
   blur, less saturation.
7. **Duty-cycled motion:** looping indicators hold ~40% at extremes, step in coarse
   increments, animate transform/opacity only. No continuous animation at rest.
8. **The app never scrolls; only inner regions do.**
9. **View-transition choreography:** only named elements move; opacity holds at the ends,
   crossing only mid-transition; sub-elements exit faster than containers settle.
10. **Type: system font stack, four effective sizes, weights 400/500/600 (no bold),
    desktop ramp one step down from touch.** No shipped fonts.
11. **Improve-don't-copy (donor §8.4):** explicit z-scale tokens; explicit 4px spacing
    ladder; tokenized 1px-border padding compensation.
12. **Never copy (donor §9):** T3 branding/wordmarks/stage-art/ultrathink/brand hue;
    Electron WCO env() values (keep the token indirection, feed from Tauri later);
    code-agent domain furniture.

## 3. Scope & mechanics

- New directory: `modules/ui-web/src/shell-v0/views/search-v3/`. Elements `jf-sv3-*`
  (prefix convention per `customElementPrefix.test.ts`).
- Mounted DEEPLINK/DEVELOPER only, hidden route `core.search-v3-surface`, **no rail
  entry** — 818 slice-1 registration recipe (lazySurfaceRegistry + CorePlugin +
  registry-surface label catalog + component-vocabulary regen).
- Fixture-first: no backend consumption until the shell clears the owner's bar.
- Zero new npm dependencies. No edits to UnifiedChatView, search-v2, global tokens.css,
  or any shipped surface.
- Implementation workers: ONE at a time (shared worktree), opus, Edit/Write tools only
  (UTF-8 rule). Orchestrator writes briefs + decomposition; worker never invents layout.
- Verification per slice: typecheck + unit tests + ui-web gate recipe; /ui-check ui-shot
  with measured assertions vs donor numbers; refute-first critic ≤3 cycles; orchestrator
  critical-analysis pass. Agent eyes judge nothing — numbers and gates do.
- **Import-bridge clause (F9 lesson, binding on every future brief):** any slice that
  mounts a shipped component inside the window MUST map that component's consumed
  custom properties onto sv3 host tokens in the same slice (the F4/F9 bridge pattern)
  AND add computed-contrast assertions for its text/surface pairs. The fit audit
  measured the drift: F4 bridged 2/2 imports, F7+F8 bridged 1/4 — the unbridged
  remainder produced white-on-white inside the dark window. Predictable evasion:
  "the component mostly looks fine" — legibility is measured, never eyeballed.

## 4. Slice plan

1. **Token sheet + shell skeleton** (fixture data): host-scoped donor tokens (semantic
   light+dark, geometry, radius ladder, z-scale, spacing ladder), shared adopted
   stylesheet (keyframes + scrollbar mixin), window grid (topbar 52px / sidebar 256px
   shell / main surface / composer band shell), THIRD-PARTY-NOTICES entry. This slice is
   the calibration experiment for the whole copy pipeline (Lit target is unbenchmarked).
2. **Sidebar rows** — donor session-row spec (two-level inset 8/10px, size ladder,
   hover/active at 6%/9% foreground-alpha, group labels) as the future result-row base.
3. **Composer + input morph** — view-transition choreography (§5.5/5.9), glass shell
   (simplified — no welded-tray clip-path), focus states.
4. **Command palette + empty states** — 420×576 popup, 8/16px inset pair, selection
   precedence; donor empty-state pattern.
5. **Adaptation seams — STOP for owner conversation** before designing any
   JustSearch-specific surface (result rows with real densities, document pane,
   citations). Donor principles constrain; owner decides.
6. Later (separate): real search wiring (shared authorities), 820-predicate alignment,
   promotion/sunset decision — none of it in this tempdoc's scope.

## 4b. Continuation plan (authored 2026-08-12, post-shell; owner has not yet ratified)

**Gate 0 — owner verdict on the shell**: **GO** (owner, 2026-08-12: "the shell is fine
for now. proceed as you see fit") — sequencing delegated; Phase A first per the
recommendation below.

**COURSE CORRECTION (owner, 2026-08-13).** The owner's intent for the copy was
T3 Code's PRODUCT FUNCTIONALITY ("simply copy t3code for the functionality of its
product and not the search capability of justsearch"), not a search-first window in
T3 Code's clothes. Phase A's search wiring was the orchestrator's interpretation —
kept as a thin, tested, currently-secondary seam (it becomes the material for the
separately-deferred search-integration conversation; do NOT delete it). The window's
functional axis from here: composer → conversational/agent session with the local AI
(T3 Code's product shape), sessions = conversations. The 818 escalating-input thesis
remains the eventual synthesis point; not built until the deferred conversation.
Phase B's result-row/economy conversation is postponed accordingly; the F-series
below replaces the old Phase-B/C ordering.

**STANDING DIRECTIVE (owner, 2026-08-13, post-F3):** search integration is deferred
INDEFINITELY — it starts only on an explicit owner instruction ("until i explicitly
tell you its time"). Until then the mission is copying T3 Code's AGENT functionality
and UI/UX. Consequence: the §9/§4b exclusion of "chat markdown" as domain furniture is
SUPERSEDED for the agent axis — response rendering IS the donor product's UX; markdown
and citation rendering join the copyable backlog. Do not re-propose search work.

**BACKLOG TRIAGE (owner, 2026-08-13, post-F3):**
- PROCEED (autonomous): F4 response rendering (markdown via the SHARED MarkdownBlock
  authority, styled per donor) + citations panel (shared jf-citations-panel +
  citationResolve, v2-proven); F5 sidebar mechanics (drag-resize with the donor's
  max = viewport − pane-min rule, icon-collapse, session rename).
- ADAPTATION RATIFIED: the donor's per-session provider picker maps to an
  effort/mode control (one local model — no provider concept); build when the
  composer control row is next touched.
- DEFERRED (documented, not important now): real palette commands + {key,command,when}
  keybinding registry (also the double-palette fix), snooze shelf + wake rules,
  banner stack, dark-fill separation, issuance register.
- REJECTED: parallel concurrent runs — "will most likely never happen, considering
  its all local" (owner). Do not scaffold for it.
- BACKEND WORK NOT NEEDED NOW: controller server-reattach (true reload survival),
  status-vs-serving mismatch fix — observations stand, no slices.

**SCOPE GRANT (owner, 2026-08-13, post-rendering-gap report):** the four-cause
remediation ("why the answers look so different") is granted design-then-implement,
INCLUDING work outside search-v3/: the ask-tier prompt shape string (RAGQAStyle),
context-section numbering (ContextBudgeter), the citation-chain correctness defects
(index conflation + sources[0] mistarget in citationResolve, missing cite-ungrounded
rule, lexical-vs-cross-encoder score-scale mismatch), and the MarkdownBlock geometry
exposure. CONTAINMENT RULE for shipped-shared edits: the shipped window's visual
behavior must not change except where a named defect is being fixed — geometry
exposure uses tokens-with-identical-defaults so shipped surfaces render byte-alike
until a consumer overrides. Implementation queues serially behind the in-flight
design/implementation agents (owner: "resume the agents with implementation once the
previous agent is done").
- F1. The conversational core: composer submit routes to the shared ask tier
  (grounded, cited, streamed — the seams search-v2 proved: `buildRequestBody` +
  `consumeShapeStream` shared authorities; mine v2's askClient PATTERN, do not import
  another dev window's module); Sv3Main gains a transcript (message anatomy mined
  from the t3code clone's own session view); sessions hold turns; streaming state
  with the donor's primary-action-slot rule (the slot IS Stop while running — 820 W2
  pattern 2); honest AI-offline state via the availability authority.
- F2. Agent-run hosting (delegate tier): shared AgentSessionController, run feed,
  approvals/steer/halt — adopting 820 W2's mined patterns (two-axis run state,
  optimistic-handoff predicate, settle/snooze shelves).
- F3. Session lifecycle depth: shelves replace recency buckets (the sidebar
  comparison finding 4 resolves toward the donor), broken-state mapping, unread/woke.
- F4+. The deferred search-integration conversation (owner), then the 818 synthesis. Go = the donor-copy strategy cleared
the bar; no-go = record why, delete the window in one PR (818-style falsification),
re-open the strategy question. Everything below assumes GO.

**Phase A — real data in the donor shell** (functional spine; minimal design; each item
the slices-1-4 loop: brief → opus worker → measure → commit):
- A1. Live search via shared authorities: the shipped search client/stores feed the
  existing placeholder rows; execution-surfaces registration the moment a SearchTrace
  referencer appears; 36px session-row correction rides along; dev-stack lease for live
  verification (coordinate via owner; leaseDurationSec for the campaign).
- A2. Sessions become real: committed searches persist to the sidebar (recency grouping
  stays until the B3 decision); deeplink boot-race workaround documented for dev use.
- Rationale for A-before-B (recommendation, not law): 40 real results in the shell is
  the highest-value input to the row-design conversation — real densities, real paths,
  real latency. The alternative order (design first on fixtures) stays available.

**Phase B — the adaptation design conversation** (owner-in-loop; the phase-stop item;
output = a ratified adaptation spec agents then execute against):
- B1. Result-row design from the donor 78px CARD variant (file-icon · path · pin ·
  status + snippet body): which fields earn resting visibility; behavior at 50 results;
  column/measure discipline (donor rule: main pane min 640 defines limits).
- B2. Chrome-economy allocation — the direct fix for the founding 40%-chrome complaint:
  a translation table for every shipped-window band/fact → its v3 form (resting pill /
  slot-swap survivor / tooltip / palette / status strip / dropped), with the L14
  boundary mapped to the donor's never-yields exception (their PR badge = our LOCKED
  tier). Owner ratifies the table; it becomes the register a later gate can enforce.
- B3. Remaining owner calls batched: grouping semantics (recency vs state shelves),
  dark-mode fill separation, chip-box referent (24 vs 28), item line-height token.

**Phase C — adaptation slices** (loop resumes under the ratified spec): result rows;
document pane + citations (shared citationResolve/consumeShapeStream authorities);
status→action slot swap with honesty exceptions; sidebar mechanics (drag-resize with
the donor's max = viewport − pane-min rule; icon-collapse); 820 feel-predicate
alignment (build against P1/P2/P3 budgets from the start — v3 is the natural first
adopter of the 820 harness).

**Phase D — reconciliation & cutover (SEPARATE tempdoc; owner-gated; not this arc):**
three-window resolution — recommendation: v3 presentation + v2's records/laws substrate
merge into ONE successor window, then a single sweep retires UnifiedChatView AND
search-v2 per retire-with-a-sweep + 818 §5 mechanics (parity checklist, measured UX
audit, auditor ≠ implementer). Also cutover-scope debts, all recorded earlier: shell
chrome ownership (rail/topbar/status), the mod+k when-clause, the deeplink boot-race
root fix, THIRD-PARTY-NOTICES generator input, theme-file integration.

Cross-session continuity: this tempdoc is the contract; any new session resumes from
§4b + the log. Serve recipe + measurement scripts live in the session scratchpad and
are re-creatable from the log's descriptions.

## 5. Log

- 2026-08-12 — Charter authored. Worktree `822-t3code-window` off local main @ 0063a8f4;
  FE deps prepared (`prepare-worktree.cjs --no-dist`). Donor pinned, spec imported.
  T3 Chat capture retired (kept in scratchpad: decision-sheet.md, tokens.json, 22 shots —
  dated reference only). Slice 1 delegated.
- 2026-08-12 — **Slice 1 implemented + verified** (opus worker + orchestrator review +
  live measurement). `views/search-v3/`: host-scoped token sheet (T0/T1 dark-default +
  light set behind `:host([theme="light"])`, T2 geometry, radius ladder, z-scale,
  4px spacing ladder, `--control-pad-3`; teal `--primary` replacing donor brand hue),
  shared adopted sheet (4 duty-cycled keyframes, scrollbar mixin via inherited
  `scrollbar-color` — `ambient-purity` bans `::-webkit-scrollbar` outside primitives),
  five shell components (`jf-sv3-window/topbar/sidebar/main/composer`), fixtures,
  33 tests incl. token-enforcement + box-math mechanism pins. Registration mirrors 818
  (CorePlugin DEVELOPER/DEEPLINK, lazySurfaceRegistry, label catalog,
  vocabulary regen, sandbox-coverage exempt row). MIT notice colocated in `search-v3/`
  (root THIRD_PARTY_NOTICES is a machine projection with no derived-source input —
  promotion-time TODO recorded in the notice). Verified: typecheck clean; 4480 unit
  tests green; 29/31 ui-web gates OK (2 reds pre-existing, untouched files);
  vite build green. **Live-measured** (Playwright, worktree serve, 1568×900):
  mount at hidden deeplink, five regions render, zero page errors; one remediation
  round — missing `box-sizing: border-box` made geometry tokens mean "at least"
  (sidebar 273→**256**, topbar 53→**52** after fix; donor numbers land exactly).
  Render is window-in-stage: the shipped shell's rail/topbar/status-bar frame the
  surface — the donor look is judged on the window interior; whole-shell chrome is a
  cutover-scope question, not this arc's. Observation filed: deeplink hash not honored
  on backend-less boot (URLSource boot read loses the default-surface race; popstate
  re-dispatch works — likely affects search-v2 too).
- 2026-08-12 — **Slice 2 implemented + verified** (fresh opus worker + live measurement,
  zero remediation rounds). `jf-sv3-session-row` per donor §6.1/§6.2: 32px rows,
  8+10 two-level inset (fill starts at 8, measured), --control-radius, glyph ladder
  (rest .4 grayscale → full on hover/active), fill precedence active>selected>hover with
  :not() guards (mutation-probed tests), 3-color status budget (act-now --success /
  in-motion --warning+duty-cycled ping / broken --destructive; resting = timestamp, no
  dot — donor's null-gray dot dropped per charter law 5), content ladder (unread w500
  full fg / broken 95% / normal 90% / receded 75%+w400), single-line ellipsis,
  content-visibility virtualization, aria-current on the one active row, 3×32px group
  labels, static order. 4494 tests green; kernel gates pass; live-measured all spec
  values exact (10 rows, 1 ping, 1 aria-current, hover fill appears). Worker corrected
  the charter's slice-2 shorthand: 6%/9% fills are the command palette's pair (§3.3),
  not the sidebar's — sidebar uses --sidebar-row-* tokens (§6.1). OWNER CALL PENDING:
  donor dark mode makes hover/active/selected fills identical (all white@4%),
  distinguishing states by content only — copied verbatim; diverging is one line.
  Known cosmetic: fixture glyphs render as empty ghost squares (no real icons in
  fixtures); resolves when real data lands.
- 2026-08-12 — **Slice 3 implemented + verified** (opus worker + 1 remediation round,
  both defects live-caught by orchestrator probes, fixes independently re-probed).
  Composer anatomy per donor (768px glass box, --radius-3xl 22px, dark glass
  mix@80%+blur16/sat1.08 with inset top highlight + no drop shadow — elevation
  inversion; light set carries the donor's single composer shadow; @supports no-blur
  fallback), field (min 70 / max 200, field-sizing: content, overlaid placeholder —
  ::placeholder is ambient-gated), round 32px send (disabled-on-empty, hover
  scale 1.05), two ghost scope-control chips (--control-pad-3), focus/invalid via
  :has() + --ring. Hero↔docked morph on the donor §5.5 choreography (group 180ms,
  sub-exit 130ms, holds 0-35/65-100), document-level VT sheet ref-counted onto
  adoptedStyleSheets with lifecycle containment, doubly-disabled reduced motion.
  REMEDIATION 1: morph flag leaked — rAF await inside the VT update callback
  deadlocks (Chromium suspends rendering; callback hit the ~4s timeout, so the morph
  also never animated); fixed with updateComplete awaits + unconditional finally +
  sync-throw fallback (a second hole the new test caught). REMEDIATION 2: glass
  reported backdrop:none — donor's node split (radius on one node, material on a
  sibling pseudo) served only the excluded attachment tray; collapsed to ONE .glass
  node, structural test forbids re-split. 4520 tests green; 10/10 mutation probes
  caught; kernel gates pass; live: flag null at +3s, blur(16px) saturate(1.08) on the
  22px node, zero page errors. Worker's clone-verified brief corrections: hero = same
  box centered + headline (not wider); send is bespoke rounded-full (not §6.3
  primary); label collapse instant by donor construction (VT crossfade covers it).
- 2026-08-12 — **Slice 4 implemented + verified** (opus worker, self-live-measured;
  orchestrator screenshot review; 1 defect the worker live-caught itself: ambient
  :focus-visible sheet boxing the palette field — fixed with the donor's ring-0
  equivalent). `jf-sv3-palette`: 576×420 donor spec exact (8/16 inset pair, item
  fills 0.06/0.09 single-fill precedence, mask-based scroll fade, kbd footer,
  corner-follows-footer), window-scoped overlay (--z-overlay, backdrop dims ONLY the
  window region; deliberately no showModal — top layer would cover shipped chrome;
  background not inert, recorded), Ctrl+K via host capture-phase listener (no global
  listener, spied); `jf-sv3-empty` (tile-fan anatomy) for sidebar-empty +
  zero-results. 4555 tests; 16/16 mutation probes; kernel gates pass.
  Accepted deviations: tooltip §6.5 skipped (aria-labels instead); 10cqh replaces
  10vh (window-scoped container); pointer moves highlight (donor hover would be a
  third fill); item 33px vs donor 32 (no line-height token tier — introduce with a
  type slice if wanted); donor --font-heading undefined in clone, not ported.
  OPEN (cutover-scope, observation filed): Ctrl+K inside the window opens BOTH
  palettes — the shipped mod+k dispatcher is a window-capture listener from Shell
  boot (KeybindingRegistry.ts:178) and cannot be pre-empted from a surface; remedy
  is a when-clause on the shipped binding or a surface-scoped binding tier, both
  outside this arc's file scope.
- 2026-08-12 — **Polish pass** (orchestrator five-state visual tour findings, donor-
  answerable subset): docked composer now COMPACTS per the donor's compact form
  (band 142→75px, field floor 70px→1lh with growth + 200px ceiling preserved,
  insets tightened to the donor's px-3 py-2 split; the donor's "single truncating
  line" honestly ported as a one-line floor since our field stays typable); scope
  chips got real 16px glyphs via the shared icon() helper (database/clock — the same
  Lucide set the donor imports), names move to aria-label ONLY when compacted.
  4560 tests; 9/9 new mutation probes. Open items recorded for the adaptation phase:
  (a) chip box is slice-3's 24px vs the donor ComposerControl's h-7 28px — two donor
  referents exist, settle when chips become real scope chips; (b) Sv3Empty tile glyph
  is a string prop rendering a hollow square — convert to icon() with a template-typed
  prop when empty states get final content.
- 2026-08-12 — **SHELL COMPLETE (slices 1-4). Phase stop per §4.5**: owner inspection
  + verdict on the donor copy, then the adaptation-seams conversation (result rows
  with real densities, document pane, citations, honesty facts) BEFORE any
  JustSearch-specific surface is designed. Serve: modules/ui-web `npx vite --port
  5175`; deeplink #justsearch://surface/core.search-v3-surface (backendless boot
  ~30s; hash needs a popstate re-dispatch after boot — see the deeplink observation).
  [Restored 2026-08-13 — an A1 log edit accidentally consumed this entry.]
- 2026-08-13 — **Slice A2 implemented + live-verified. PHASE A COMPLETE.**
  `sv3-sessions.ts` (pure, window-local, IN-MEMORY by design — the Phase-D
  records-substrate boundary stated in its header; no localStorage, no shared-store
  writes): first submit creates, later submits update-in-place, prepend-once-never-
  reorder, focus/new-session claim semantics, coarse render-time relative timestamps,
  Today/Earlier calendar buckets (empty groups hidden; no Pinned bucket until pin
  exists — dead scaffolding refused). One `runSearch` funnel for composer send AND
  row re-run. Sidebar now presentation-only; donor-patterned "New search" header
  control; fixtures SWEPT (SIDEBAR_GROUPS + FixtureSet machinery deleted; a test
  forbids surviving references). Store subtlety pinned: re-query behind displayed
  results sets `isRefining`, not `isSearching` (searchState.ts:611) — in-flight
  mapping covers both. 4601 tests / 7 of 7 mutation probes / kernel gates pass.
  Live: two sessions, claim moves, re-run renders (593 matches), in-motion ping only
  on the running session. Session status is two-valued (no `broken` mapping for a
  failed pass — Phase C item). Next: PHASE B (owner conversation) — evidence photos
  live-3-results.png + sv3-a2-sessions.png.
- 2026-08-13 — **Slice F1 implemented + FULL-TIER live-verified (active GPU model).**
  The conversational core per the course correction: `sv3-ask.ts` = sv3's single
  ask-dispatch site on the SHARED authorities (`buildRequestBody('core.rag-ask')` +
  `consumeShapeStream` + typed handlers — v2's askClient PATTERN mined, module not
  imported; cross-window-import forbidden by test). Sessions hold turns
  (append semantics; A2's re-run-update semantics superseded on the conversational
  axis — row click claims only; `broken` now maps failed/refused). Transcript in
  Sv3Main with donor anatomy mined from the t3code clone's own session view
  (48rem column, 16px turn rhythm, user bubble max-80% rounded-2xl, response
  plain-on-panel, meta hidden while streaming); bottom-follow with 24px re-arm
  threshold. Slot rule STRUCTURAL: Stop renders INSTEAD of Send while streaming
  (donor ComposerPrimaryActions early-return; mutation-probed both-rendered fails).
  AI-offline: `projectAvailability` authority, refusal keeps draft, reachable
  reason. Search seam preserved behind palette command `cmd-search-text` (no session
  writes). 4622 tests / 9 probes bite / kernel gates pass. LIVE (stack restarted
  after the 820-class `cuda12 variant not installed` on this fresh worktree —
  remedy reapplied: junction modules/ui/native-bin/llama-server/variants/cuda12 →
  main checkout copy [TEMPORARY SCAFFOLD — remove when arc closes], llm.modelPath
  POST, ai_activate 22s): streamed answer with Stop slot at t+2s; settled 1,960-char
  grounded answer correctly describing the three-process architecture with [n]
  markers + Sources list + "5 sources"; session row titled by opening question;
  zero page errors. Residuals: markdown rendering (asterisks visible — deliberate,
  charter §9), citation resolving/panel (count only), issuance-register decision
  (sv3 is now the SECOND window-local ask site — 818's residual is live; owner/
  orchestrator call), streaming rebuilds session list per delta (fine at scale).
- 2026-08-13 — **Slice F2 implemented + live-verified (partial live tier; gaps
  honest).** Agent-run hosting on shared authorities only: agentSessionStore
  (peek-first — a never-delegating window starts no polling), EVERY directive via
  dispatchRunControl (+directiveAvailable), the ONE jf-tool-call-card, availability
  via projectAvailability('agent'). Registers: steering-surfaces adopter +
  run-renderers mount site. Derived-phase model `sv3-run.ts`: session axis ×
  turn axis → one of idle|dispatching|running|holding|ended (dispatching added for
  the window's optimistic echo; holding outranks live); explicit activeTurnId.
  Slot = strict-priority state machine with the reason in the aria-label.
  APPROVALS DEVIATION (correct): no inline Approve/Deny — authorizationBroker.ts
  retired inline approvals into the one jf-authorization-host ceremony; the window
  renders a held call as a typed act-now block and the ceremony asks. Ctrl+Enter
  delegates / Enter asks (hint via slot aria-label only; a11y gate caught + fixed
  title-on-disabled). 4659 tests / 10 probes bite / steering-arbitration +
  run-renderers + controls-a11y + kernel gates green. LIVE (stack + model): real
  delegate → run feed with tool card, slot "Stop … Enter steers", session in-motion;
  then the agent requested core_ingest_files and the run HELD: typed prompt
  "waiting for your approval (medium risk)", slot "The run is waiting for your
  decision", session ACT-NOW — the donor state vocabulary end-to-end. Halt receipt
  verified at unit tier only (probe: halt-as-completion fails) — live halt was
  frustrated by a NEW NAMED FINDING: **window-local in-memory sessions orphan a
  live run on reload** — a fresh window instance showed 0 sessions while the run
  kept holding server-side (F2's cross-surface residual made concrete). F3 /
  records-substrate must recover window state from the controller (presence), not
  memory. Run ended by stack stop (dev throwaway). Residuals → F3: shelf model,
  presence/recovery, cross-surface slot honesty; issuance-register decision still
  open (owner/orchestrator).
- 2026-08-13 — **Slice S1 implemented (backend, remediation series). The numbering
  contract.** One canonical `sectionHeader(n, label)` formatter (invariant in its doc
  comment); all emitters now render `[n] label` — including a FOURTH the design
  missed (McpToolSurface concise mode re-renders the same sections; unnumbered it
  would disagree with detailed mode about ordinals — fixed under the design's own
  sweep reasoning, goldens correctly NOT rebaselined since they document a 0.3.1
  byte-capture). Parser keys on the header's own number with running-ordinal
  fallback. Invariant test on the real Lucene runtime with per-document ordinals
  chosen so a chunkIndex-derived header cannot pass. No contract/persistence/wire
  touches (design claim verified at source). Full Gradle suites green: indexing 78 /
  app-inference 225 / app-services 1965 / worker-services 965 / ui 698 / app-api 150.
  FE alignment holds by construction (label = refIdx+1); the in-range-unclaimed and
  out-of-range mark states stay for the FE slices. LIVE VERIFICATION DEFERRED to
  ride the S2/S3 round (one backend restart for all three). Two out-of-scope
  findings filed (budget-stopped citation; truncation-after-emission).
- 2026-08-13 — **Slice F11 implemented + measured. The one-line answer tail.**
  Per the f11 design + orchestrator resolutions: 24.0px single row (was 3 rows/103px),
  verdict·duration resting with elaboration in title+accessible name, bare "Sources"
  disclosure (sentence case, aria carries the count; quiet-count variant behind a
  tested one-line switch — owner may flip), icon-only copy as the sole yielding
  element (live-region as SIBLING — a label flip would rename the control),
  stale-attribution guard adopted (tail shows a turn's model IFF ≠ composer's),
  model label relocated to the composer as a static span SURVIVING docked compaction
  (98.5/192px cap; absent when empty). CitationsPanel gains additive default-false
  externalDisclosure under the containment rule — six regression tests over all
  three header paths prove the shipped window byte-identical; live: plain mount
  still renders its own header. Deviations (all sound): panel mounted only while
  expanded (preserves the 30px total the design demanded), E8 assertion placement.
  4934 tests (+39) / gates green / live: gap 6px, rhythm 16px unchanged, no-wrap at
  the 640 floor, per-turn expansion, zero console errors. RESIDUALS: composer model
  label likely persists after model unload (static evidence: lastKnownModelId) —
  the design's availability gate deferred to the next composer touch, recorded;
  panel-internal uppercase dialect remains (choice-6 residual, screenshot filed);
  gen-token-names red is pre-existing on an untouched tree (inbox).
- 2026-08-13 — **Slice F10 implemented + payload-proven. The composer effort control.**
  Capability audit table (donor→backend→verdict): effort/traits BUILT on the shared
  seams enableThinking (ConversationEngine→OnlineModeOps→chat_template_kwargs),
  maxTokens, topK — rungs Quick {thinking:false, 512} / Standard {} (sends NOTHING —
  restating defaults would fork them) / Thorough {thinking:true, 3072, topK 12};
  runtime-mode (autonomy) DEFERRED-seam-verified (needs the confirmAsync ceremony +
  an owner scope call — its own slice when wanted); provider picker EXCLUDED (§9 +
  ratified remap); attachment EXCLUDED (search-deferred); ask-path posture parameter
  DESIGN-ONLY (absent in buildRequestBody). BRIEF CORRECTION: the shipped window has
  NO effort chip — the orchestrator's "Low" memory was ToolCallCard's risk word;
  no effort seam exists anywhere in UnifiedChatView. Chip referent RESOLVED: composer
  controls take ComposerControl h-7/28px (measured 28.00 live); slice-3's two inert
  scope chips SWEPT (dead chrome for the deferred search axis). Donor menu anatomy
  (MenuPopup/RadioItem, glass recipe, Default badge at the 2xs rung; one measured
  deviation: badge fill dropped for AA at 10px → 4.56:1). Escape ladder gains
  menu>pane as most-local. Window-local per-session effort state (no shared
  per-conversation preference seam exists — verified). 4895 tests / 8 probes /
  payload proof at the fetch boundary per rung / 7 contrast pairs ≥AA / zero
  console errors. NOTE: one stray probe ask reached the stack (fetch-restore race,
  halted; conversation "probe standard rung" carries an honest broken dot);
  incidental: thinking:false produced no reasoning block — uncontrolled but
  consistent with the parameter reaching the engine.
- 2026-08-13 — **Fit audit + Slice F9 (fit pass).** Independent audit
  (822-donor/f-series-fit-audit.md): the founding disease did NOT return — resting
  interactive chrome 16/16/16 across hero/settled/run-holding (donor baseline 27),
  transcript chrome share 18.8% vs the founding 40%, zero duplicate facts, all 13
  honesty facts correctly tiered. BUT a second design system crept in via imports
  (F4 bridged 2/2, F7+F8 only 1/4): 4 typefaces, two ramps, off-ladder radii, and two
  measured contrast DEFECTS (white-on-white tool card; slate-on-dark reasoning). F9
  fixed the fixable in-window: three F4-pattern bridges (tool-call-card /
  reasoning-block / hover-card → sv3 tokens; audit's "unbridgeable statusAccent"
  corrected — statusTone resolves var(--accent-<tone>) so host tokens reach it;
  22 computed-contrast pairs now asserted from the real token graph, all ≥AA, live
  measured 4.74:1-17.32:1); --accent collision renamed to --accent-surface (a shipped
  color name is never redefined as a fill); Escape ladder = rename > pane > palette >
  flip (rename wins by host declining, live-reproduced); sv3 palette closes on focus
  departure (the double-palette collision now strands nothing — shipped palette's own
  behavior stays deferred); two inert slice-1 topbar placeholders deleted (resting
  chrome 16→14). 4883 tests (+13, probed-red-first). Import-bridge clause added to §3
  as a binding brief requirement. OWNER-PENDING: the theme seam (audit #2; window
  hard-dark vs app theme — recommendation: stay dark-only until cutover) and the
  audit's 11 needs-decision items. NOTE: the live app's data-theme was DARK during F9
  (audit measured light) — bridges are theme-independent.
- 2026-08-13 — **Slice F7 implemented + model-tier verified. The honesty pack: 13/13.**
  E5+E4 (lock-elsewhere → transcript replaced by the locked view; tri-state derive;
  composer deliberately NOT gated on the stale poll — the 423 stays authority),
  C1 (answer frame from the evidenceProjection authority; duration measured, model
  stamped — the done payload carries neither; live: ".answer-frame" =
  "Partly grounded — some statements are not backed by your documents · 4.3 s ·
  Qwen Qwen3.5-9B" — the basis clause varies with the real grounding verdict, which
  the orchestrator's first probe misread as a missing frame by pattern-matching the
  fixture wording; diagnosed via deep-shadow walk, no defect), C4 (resolver-output
  parity pinned), C3 (hover card via the shared block's event, delegated at the
  scroller), C8 (rag.rewrite → standalone-question note), C9 (reasoning_chunk IS in
  the shape; jf-reasoning-block wired), E9 (friendlyStreamError after abort/423
  branches), E10 (three-state zero-corpus landing — unreported ≠ zero, sharper than
  the shipped conflation; remedy navigates to Library), E8 (no allow-list needed —
  v3 never uses the word; a test keeps it so), A9 (per-turn copy on the donor action
  bar; honesty facts hold at opacity 1), A10 (export via the store seam, palette
  command — no resting chrome), A11 (generated titles, rename wins — probed),
  B8 (raise-budget through the one seam; remedy renders before concessions).
  Register: sv3-honesty.ts added to groundingSemantics.consumerSites (gate-demanded,
  caller-not-classifier note). 4839 tests / 14 probes / gates green. Residuals:
  E3 Detailed-mode gating stays Tier-B; record-restored turns carry no frame
  (F6 recorded gap); C8/C9 live-exercise depends on model emitting rewrite/reasoning
  (fixture-covered; shape-verified).
- 2026-08-13 — **Slice F6 implemented + STACK-TIER VERIFIED. Sessions live on the
  product's record.** Adopted (each ≥2-consumer shared): conversationListStore
  (id mint/list/title/active/apiBase), unifiedThreadClient/Projection (extractions
  consumed by BOTH shipped windows — the brief's "outside views/" test replaced by
  the two-consumer test, else fork), lastViewedConversation (per-tab, 609P3),
  DraftPersistence + notifyDraftKeptOnce, activeRunPointer+reattachActiveRunOnLoad,
  setAiActivity. sv3-sessions = pure projection/cache now (identity/title/existence
  in the store; pin/unread stay window-local prefs — boundary note rewritten). New
  sv3-record.ts: record events → the live feed's own item shapes, ONE renderer for
  live and recorded runs; refuses to touch streaming turns, F4 evidence, or halt
  wording; empty record is a no-op (727 F-8). New-session aborts the ask stream and
  detaches (never halts) the run view. 4787 tests / 9 probes / gates green.
  REGISTER RECONCILIATION: F2 was right, the inventory's D6 "not registered" was
  stale — sv3 appears in 5 registers. LIVE (stack, model): sidebar pre-listed
  conversations from OTHER windows/sessions (incl. the owner's own browsing thread
  and the F1-era ask — cross-window continuity observed); ask → session; FULL PAGE
  RELOAD → all sessions survive; claim → thread renders from the record without
  re-asking; zero page errors. FINDINGS: (a) production dev-data carries the pre-F6
  window-local id "sv3-session-1" as a real conversation — the A1 identity collision
  observed in the wild, closed by this slice; (b) PRODUCT BEHAVIOR: the v3 sidebar
  now lists ALL app-wide conversations (both windows share the one store) — coherent
  with sessions=conversations and with cutover, surfaced to the owner. Residuals:
  record-side evidence not projected (cold-loaded turns show no citations panel —
  honest never-told; F8-adjacent), detached-run re-claim renders record not live
  feed (presence latched per run id).
- 2026-08-13 — **Slice F7 implemented + live-verified. THE HONESTY PACK.** The 14 inventory
  rows the brief named, all on shared authorities. New pure module `sv3-honesty.ts` (the
  window's three honesty derivations, testable without a DOM): **E5+E4** — `historyLocked`
  is derived TRI-STATE from every `conversationProtection` snapshot (734's mechanism, not
  write-once from a 423), and a locked store makes the transcript UNREADABLE — `Sv3Main`
  renders a locked view (the donor-economy `jf-sv3-empty` + `reasonFor('conversations.locked')`
  wording + that cause's own navigate remedy) INSTEAD of the turns; a 423 adopts the lock
  immediately (poll is ~10s behind) and its refusal line clears when the lock lifts.
  **C1** — the answer frame line, `answerFrame`/`answerFrameLabel` from the shared authority
  plus a receipt tail; duration is MEASURED (`now − askedAt`, stamped in `settleTurn`) and
  the model is stamped AT THE TERMINAL (the shipped window re-reads it at render, which
  re-labels old answers). Verified deviation: `core.rag-ask`'s `done` payload carries
  `promptTokens`/`contextBreakdown` and NO duration or model — the brief's "the data arrives
  in the done payload" does not hold; the shipped window derives both the same way we do.
  738's Detailed-mode gating of the model name NOT adopted (that is E3/Tier-B).
  **C4** — already satisfied by F4; pinned with a test comparing stored marks against
  `claimsToCitations`' own output. **C3** — `cite-ref-hover`/`leave` delegated at the
  scroller into the shared `jf-citation-hover-card`. **C8** — `rag.rewrite` → the turn's
  `standaloneQuestion` → "Interpreted as: …". **C9** — NOT n/a: `core.rag-ask` declares
  `reasoning_chunk`; wired to the shared `ReasoningController`, blocks recorded on the turn
  at every terminal (a halt keeps what was thought). **E9** — `friendlyStreamError`, reached
  only after the abort and 423 branches. **E10** — a THREE-state corpus (`documents` /
  `empty` / `unknown`): a reported 0 offers "Add folders in Library…" as a real navigation,
  and an unreported count says NOTHING — a deliberate improvement on the shipped landing,
  which shows the remedy for both. **E8** — search-v3 uses the token nowhere; no allow-list
  entry needed (gate green) and a test keeps it that way. **A9** — per-turn Copy answer in
  the donor's hover/focus-reveal action bar; honesty facts never yield. **A10** —
  `exportConversationMarkdown` + `copyToClipboard` as a palette command (streaming turns
  excluded). **A11** — `generateConversationTitle` once per conversation, refused for a
  renamed one (new `renamed` flag) with a write-back if the reader renames mid-flight.
  **B8** — the raise-by-a-step option, FIRST in the prompt (the remedy before the
  concessions), dispatched as `raise-budget` through `dispatchRunControl`.
  REGISTER: `sv3-honesty.ts` added to `run-renderers.v1.json` `groundingSemantics.consumerSites`
  (with a `consumerNotes` rationale) — the gate demanded it for the `answerFrame` import; it
  is a caller, not a second classifier. 4839 tests (+52) / **14 of 14 mutation probes bite** /
  typecheck clean / vite build green / ui-web + kernel gates green (the same 4 pre-existing
  reds F3 recorded, all in untouched files). **LIVE** (worktree vite on 5179 against the
  running stack, read-only; fixture values pinned onto the regions): zero-corpus landing shows
  the underlined remedy centred 8px under the headline; a real recorded conversation renders,
  then the pinned lock REPLACES it — 0 turns, no transcript node, the answer text absent from
  the region entirely, heading = the shared wording, "Unlock in Security" present; a fixture
  turn renders the whole pack in one turn — "Interpreted as: …", "Thought for 2s",
  the `[1]` mark, **"Based on your documents — per-sentence grounding not verified · 45.7 s ·
  Qwen Qwen3.5-9B"** (810 §T-B's own sentence), the citations panel, and Copy answer at
  opacity 0 at rest → 1 on focus while the frame line holds at 1; the shared hover card shows
  "strong match · 91%" on the mark and hides on leave. Residuals: a live ask was NOT sent
  (it would write a conversation into the shared stack the orchestrator holds), so C1/C8/C9
  are stack-verified fixture-driven rather than model-driven; the composer is deliberately
  NOT gated on `historyLocked` (the poll can be stale in both directions — the 423 stays the
  authority); a record-restored turn carries no frame line (the record has no receipt —
  honest never-told, F6's recorded gap).
- 2026-08-13 — **Slice F8 implemented + live-verified. THE CITATION PANE.** New region
  `jf-sv3-pane` (`Sv3Pane.ts`) — chosen over a bare `<aside>` so the window keeps one element
  per region and the pane owns its own shadow root for the two presentations; it mounts the
  product's ONE reading surface (`jf-document-pane`) by its OWN side-effect import, never via
  search-v2. **The double-open guard**: `citation-select` is now handled panel/block-element-local
  in `Sv3Main` (both producers — the citations panel and the inline `[n]` mark) and
  `stopPropagation()`d there, so Shell's unguarded host listener (`Shell.ts:533-554`) never writes
  the shared `inspectorState`; the window's own `sv3-citation-open` carries it to the host instead.
  Probed both ways: the guarded click leaves `getInspectorState().selected` null with zero escapes,
  and the SAME event from an unguarded node in the same window IS observed escaping to
  `document.body` (the probe has teeth). **Boundary math**: `sv3-sidebar-sizing.ts` renamed to
  `sv3-boundaries.ts` — the moment a second boundary appeared, one file holding the main column's
  640 beat two files that would drift; both ceilings are cut from one `mainSafeRemainder(host,
  other)`, pane default 540 / floor 360 / share 70% (donor), sidebar's ceiling now subtracts the
  pane's OCCUPANCY (0 when overlaid or closed) and the pane's subtracts the sidebar's RENDERED
  width (48 collapsed). Charter law 11 cited in-module: the donor has NO main-column clamp for its
  right panel — we do. **Narrow**: below the donor's 980 the pane is a window-scoped overlay
  (`--z-overlay`, backdrop dimming only the window box, donor sheet size `min(42%,28rem)`≥20rem,
  200ms entry, reduced-motion instant) — driven by a reflected attribute from the window's own
  measurement, NOT a `@container` query: the container would need `container-type` on the host,
  whose layout containment would re-anchor F7's fixed-position hover card (the trap
  `unifiedChatStyles.ts:207-212` already recorded). **Escape order**: pane first, capture-phase on
  the host, probed with the palette open (pane closes, palette stays). Grip mirrors F5 (16px hit /
  2px line, keyboard, double-click forgets, persisted on pointerup under
  `justsearch.searchV3.pane.width.v1`) with two donor-faithful differences for THIS handle:
  rAF-coalesced drag and pointercancel REVERTS; `Home` (not Escape) is its reset, because Escape
  belongs to the pane. Scope guard: `docPath` is `attribute: false` and the window has exactly ONE
  writer of it (structurally asserted) — arbitrary-path opening stays the deferred boundary.
  4870 tests (+41 net, 415 files) / typecheck clean / vite build green / ui-web + kernel gates
  green bar the same pre-existing reds (RecentsMenu ghosts, ActionLedgerView accents,
  ActionLedger/RecentsMenu fallbacks, three kernel gates missing preflight inputs; `dead-code`
  gains no new finding). **LIVE** (the running stack's own FE at 5173, read-only — no ask sent, a
  fixture turn pinned onto the region and the citation followed by clicking the SHARED panel's
  real source row): the pane opened on the real cited document at lines 20-34 with the shared
  reader's own decay, `inspectorState` untouched, zero escapes. Measured at a 1568px window box:
  sidebar 256 + main 772 + pane 540 = 1568 at the donor defaults; both boundaries pushed to their
  ceilings → 208 + **640** + 720; sidebar collapsed → 48 + **640** + 880 — the main column's floor
  holds exactly in both, measured from real rects. Escape closed the pane with the palette left
  open; `Home` forgot the stored width. **The live pass earned its keep**: it caught a cascade
  defect no DOM-less test could see — the host's `jf-sv3-pane { width: … }` track is outer-tree and
  beat the pane's own `:host([overlay]) { inset: 0 }`, pinning the overlaid sheet 540px wide at the
  LEFT edge; fixed by guarding the track on `:host(:not([pane-overlay]))` and pinned by a test.
  Post-fix the overlay spans the window box exactly (793) with the sheet right-anchored at the
  donor's 333 = min(42%,28rem). Screenshots: `sv3-f8-pane.png`, `sv3-f8-narrow.png` (scratchpad).
  Residual: the pane grip swallows nothing, but while a document is open Escape is the pane's —
  a session rename in progress would lose its cancel key (noted, not fixed: the brief's order).
- 2026-08-13 — **Slice F5 implemented + live-verified (backendless — no stack tier
  needed for chrome mechanics).** Sidebar mechanics per donor: drag-resize
  (min 208 / default 256 / max = box − 640; 16px grip hit with 2px line — worker
  corrected the brief's 4px; pointer-capture + rAF, persisted at pointer-up;
  keyboard arrows mined from v2's grips since the donor rail is tabIndex=-1;
  double-click FORGETS the stored width per L13), icon-collapse (48px rail,
  200ms, glyph+status stay — act-now dot survives collapse, probed; width
  untouched by collapse so expand restores the chosen width structurally),
  rename (donor's real mechanics found: double-click guarded inline input,
  Enter/Escape/blur-latch, one shared commit rule ported as resolveSv3Rename;
  rename beats first-question titling, probed). Persistence:
  justsearch.searchV3.sidebar.{width,collapsed}.v1 localStorage — chrome
  PREFERENCE, explicitly not session data (Phase-D boundary comment).
  DELIBERATE DEVIATION, live-proven: clamp derives from the WINDOW HOST box,
  not the viewport (host 1516 vs viewport 1568 — the donor's rule would have
  let main drop to 588; ours pins main at exactly 640). 4748 tests / 17 probes /
  gates green / zero page errors; jitter check: rects byte-identical on grip
  hover. Residuals: tooltip primitive still deferred (title+aria-label);
  chevron glyphs (no panel-left in shared set); toggle in sidebar header (topbar
  move = window-grid change, out of scope); narrow-window re-clamp binds at next
  gesture (donor parity).
- 2026-08-13 — **Slice F4 implemented + full-tier verified.** Response rendering +
  citations on SHARED authorities: `jf-markdown-block` mounted per turn (single
  mount, streaming-first — the block's own mend+rAF path; a test forbids
  stream-then-swap), donor `.chat-markdown` styling re-expressed as token
  re-mappings on the exposed hooks; `jf-citations-panel` + `claimsToCitations`
  populate from the turn's new EVIDENCE RECORD (sources/matches/marks/mode —
  count now derived, cannot disagree with the panel; never-told ≠ zero, probed);
  agent-feed text renders through the same block. PANE LANDING FREE: Shell's one
  `citation-select` listener already routes the composed event to the shared
  inspector state — sv3 adds no handler. Registers: execution-surfaces
  (sv3-ask-client, sv3-turn-evidence carriers) + run-renderers (answerRenderer/
  weaveSites). 4698 tests / 6 probes / gates green / deps untouched (bare-import
  ban test added). LIVE (model active): mid-stream progressive render with zero
  raw markup; settled answer 17 strong / 4 lists / 17 code spans, zero asterisks,
  panel "▸ 5 sources", zero page errors. RECORDED GAP (its own future slice —
  touches 3 shipped surfaces): MarkdownBlock hard-codes block geometry + mono
  face and declares nothing for headings/tables, so donor heading scale +
  table truncate/expand cannot be applied from sv3; needs the shared component
  to expose parts/geometry tokens. Deviations: `.answer` pre-wrap removed
  (block children own rhythm); in-window document pane remains residual.
- 2026-08-13 — **F3 STACK-TIER VERIFIED (orchestrator, real run + active model).**
  The F2 named-finding scenario re-run for real: delegate → run holds (act-now,
  Active shelf, slot "waiting for your decision") → navigate away (window unmounts,
  in-memory sessions destroyed) → navigate back → session ADOPTED from the shared
  controller (run's own task as title, act-now preserved, slot honest in an instance
  that never dispatched it — cross-surface residual closed) → run settles → Recent
  shelf, resting, Send restored. Zero page errors. Remaining true-reload survival
  is the shared controller's server-reattach question (Phase D / backend), not sv3's.
- 2026-08-13 — **Slice F3 implemented + backendless-live-verified.** Session-lifecycle depth:
  (A) SHELVES replace A2's Today/Earlier recency buckets — `projectSv3Sessions` now yields
  **Active ▸ Pinned ▸ Recent** ("Recent" kept over the donor's "Settled": most rows are
  conversations at rest, not stopped runs), empty shelves hidden, static order INSIDE a shelf
  (a pin moves a row between shelves, never within one). Blockers-override (820 W2) is
  structural: act-now/in-motion outrank the pin, so a run blocked on the reader cannot sit on
  the shelf they parked it on. Snooze OUT of scope (needs menu + wake timer) → residual.
  (B) PIN via the donor §6.1 SLOT SWAP: status at rest → pin action on row hover OR keyboard
  focus, hidden state out of flow, `min-inline-size` floor, and THE NEVER-YIELDS EXCEPTION —
  an act-now or broken status keeps its place (the donor's PR-badge counterpoint = L14's honesty
  boundary) with the pin appearing beside it in a gutter reserved AT REST. Pin is a sibling
  button (no button-in-button), `aria-pressed`, pin state in the pure module.
  (C) PRESENCE closes F2's named finding: on connect and on every controller snapshot, a
  live/holding run no session represents is ADOPTED as a session (title = the run's own task,
  Active shelf, unclaimed — news, not navigation); the composer slot is honest about a run this
  window never dispatched; claiming it renders the F2 feed. Adoption is once per run id.
  (D) UNREAD/WOKE bit: `completedAt` vs `lastVisitedAt` (both in the pure module) drive the
  existing w500/full-foreground rung; completing while claimed never sets it, a visit clears it,
  and a HALT records no completion at all (the reader stopped it themselves).
  4691 tests (+32) / 12 of 12 mutation probes bite / typecheck clean / vite build green /
  controls-a11y + a11y-closure + steering-arbitration + presentation-purity + layout-purity +
  ambient-purity + style-literal + atom-fork all green (4 pre-existing reds in untouched files:
  theme-token-closure + accent-as-text + strip-token-fallbacks on RecentsMenu/ActionLedgerView,
  stale token-names.generated; kernel dead-code is red dir-wide against a stale knip baseline —
  observation filed). **LIVE (backendless serve + Chromium, sessions handed in as data):** three
  shelves render with correct membership; the swap fires by mouse AND by Tab, with the slot box
  measured at **32px in every state** (no jitter); the broken row keeps its dot visible with the
  pin 4px beside it; mouse pin-click pins without claiming; Enter on a focused pin unpins and the
  empty shelf disappears; zero page errors. **Two defects the live tier caught that the unit tier
  could not:** (1) `:host(:has(:focus-visible))` is a Chrome SYNTAX ERROR and killed the whole
  selector list — the swap did nothing in the browser while the CSS-text tests were green
  (`static-green ≠ live-working`); rewritten as separate rules + a test banning the nesting.
  (2) critical-analysis pass: the shared controller APPENDS across runs, so presence starting at
  entry 0 would have put a finished run's cards in the adopted run's feed and receipt —
  `sv3RunPresenceStart` now starts at the live run's own task entry.
  Residuals → later: snooze shelf + wake rules; presence recovers only what the SHARED CONTROLLER
  still holds, so a full page reload (which drops the controller singleton) still needs the
  controller's own server reattach — the window adopts whatever it reports; in-motion still yields
  on hover (only act-now/broken are the never-yields set).
- 2026-08-12 — **Sidebar comparison findings** (owner-requested donor-vs-copy analysis;
  feed the adaptation phase): (1) ROW-HEIGHT REFERENT — our rows are 32px from §3.2's
  menu-button ladder, but the donor's SESSION rows are §6.1's slim 36px / card 78px;
  when rows become real search sessions, adopt 36px slim. (2) THE CARD VARIANT IS THE
  RESULT-ROW SEED — the donor's 78px card (header: favicon·project·pin·status + body
  line, contain-intrinsic-size 96px) maps directly onto a search result (file-icon ·
  path · pin · status + snippet); start the result-row design there, not from scratch.
  (3) STATUS→ACTION SLOT SWAP still unbuilt (no row actions yet) — when built, adopt
  the donor's exception rule: their PR badge stays visible+clickable during hover
  ("only the time label yields") = exactly L14's honesty-fact boundary; LOCKED etc.
  take the PR-badge role. (4) GROUPING SEMANTICS OPEN — our Pinned/Today/Earlier
  time buckets are fixture placeholders; the donor's real grouping is state shelves
  (Active/Pinned/Settled/Snoozed, activity-blockers-override per 820 W2). Recency vs
  state is a product decision for the owner. (5) Donor separates semantic
  system-health tokens from raw-palette DOMAIN taxonomy colors (PR emerald/violet/red)
  — adopt when file-type/domain hues appear. (6) Not yet ported: resize handle
  (min 208 / max viewport−640), 48px icon-collapse, multi-select, woke/unread-
  completion state machine.
- 2026-08-13 — **Slice A1 implemented + live-verified against the real backend.**
  Seam: the shared `searchState` store (`setQuery/submitSearch/subscribeSearch` — the
  same authority both shipped windows consume; issuance mirrors SearchV2View's
  explicit-submit pair; exactly one request, mutation-probed). Count via the shipped
  `matchCountLabel`; unreachable copy via `readinessNotice` vocabulary. NO SearchTrace
  referencer — execution-surfaces registration correctly not needed (gate green).
  Four honest states (loading skeleton/ready/zero/unreachable-distinct-from-zero);
  Enter-to-send added (Shift+Enter newline); 36px row correction landed. 4569/4570
  (one unrelated wall-clock flake, observation filed); 5 mutation probes bite.
  Worker also repaired ui-web node_modules corrupted by a Gradle/npm file-lock
  collision (my parallel backend build — lesson: skipWebBuild when FE tooling is
  live). LIVE: stack from this worktree's dist (runId aeb93837, docs/ corpus,
  862 accepted), "worker lifecycle readiness" → "Top 50 of 325 matches", real rows,
  skeleton observed at 0.7s, zero page errors. Observations: /api/status reports
  indexedDocuments 0 / DEGRADED while search serves 325 hits (status-vs-serving
  mismatch, 819-adjacent); OR-semantics makes true zero-results rare. THE PHASE-B
  EVIDENCE PHOTO (live-3-results.png): real rows expose the row-design needs
  (absolute-path redundancy, title↔path gulf, no snippets), and the shipped shell's
  chrome re-materializes around the window with a live backend (indexing toast +
  walkthrough toast + degraded pill + queue facts + rail badge, toasts overlapping
  the window region) — the founding 40%-chrome complaint, photographed beside the
  donor economy. Phase A2 (real sessions) next; Phase B conversation is now
  evidence-armed. owner inspection
  + verdict on the donor copy, then the adaptation-seams conversation (result rows
  with real densities, document pane, citations, honesty facts) BEFORE any
  JustSearch-specific surface is designed. Serve: modules/ui-web `npx vite --port
  5175`; deeplink #justsearch://surface/core.search-v3-surface (backendless boot
  ~30s; hash needs a popstate re-dispatch after boot — see the deeplink observation).
