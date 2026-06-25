---
title: "543-fwd — future directions (research + ideation)"
---

# 543-fwd — future directions (research + ideation)

**Research-only doc** (2026-05-26). No code shipped here. The goal: given the
§32 substrate is now built, merged, and live-proven (see
`543-fwd-live-evidence.md`), think about what we could *theoretically* do with
it — polish, simplify, extend, or new UX. App is pre-production, no users; all
improvements are viable, nothing is urgent.

Method: (R0) ground in the actual substrate; (R1) four parallel web-research
streams mapping each primitive to its established problem-domain; (R2)
source-verify every "easy win" claim against our code + dedupe against
surfaces that already exist (the arc's own lesson: *verify before claiming*).

---

## The reframe — what we actually have

In standard terms, §32 is not just "agent oversight." It is a **local-first,
provenance-rich, undoable action journal with an agent-proposal layer** — the
single-player cousin of the engines behind Linear's sync, Figma's multiplayer
undo, LangGraph's human-in-the-loop interrupts, and programming-by-demonstration
tools. Concretely:

- Every UI action is an **Effect** written to an append-only **journal** with
  provenance, originator (`user/agent/system`), a **derived inverse** (undo),
  optional **causation** link, and optional `pendingOutcome`.
- Agents **propose** Effects (with a confidence score); the user accepts/rejects.
- An **autonomy dial** gates the agent's per-tool auto-approval by risk.
- **Tasks** track async work; **macros** record/replay Effect sequences;
  **consent** persists capability grants; **elicit** does mid-action prompts.

**Central insight:** the highest-leverage moves are not new primitives — they are
*completing primitives that are already ~90% built*. The journal stores inverses
but has no redo; it records causation but barely surfaces it; it records every
agent approval but never learns from them. The cheapest, biggest wins close
those gaps.

---

## What already exists (dedupe baseline — don't re-propose these)

Source-verified in `shell-v0/components/` + `substrates/`:

- **History list**: `<jf-effect-audit-log>` — filterable by originator, scrollable.
- **Undo**: cursor-based `undoLastEffect`; originator-scoped `undoLast/AllByOriginator`
  ("Undo all AI actions"); the three undo paths skip vetoed/marker entries.
- **Causation chips**: single-hop `↳ #parent` rendered on chained entries.
- **Save-as-macro**: multi-select entries → `defineMacro` (elicit name prompt).
- **Parameterized macros**: `{{token}}` substitution via elicit; `previewMacro` dry-run.
- **Confidence chips**: rendered + sorted (most-uncertain-first) on the pending queue.
- **"What the AI did" digest** + a **unified agent-activity drawer**.
- **Autonomy dial** driving real per-tool auto-approval (HIGH never auto).
- **Backend undo** for `invoke-operation` via the executionId → `POST /api/undo/{id}` bridge.

---

## External research (condensed; full sources at the end)

**Local-first / event-sourcing / time-travel.** Redux DevTools = scrubbable
action list + jump-to-any-point. Linear batches all ops in one tick into a single
undo unit; undo is a *new reversing transaction*, not a state rewind. Automerge
encodes redo as "undo-of-undo" (a pointer into the op log — no separate redo
store). Vim/Ink&Switch keep a *branching* history so an "overwritten future" is
recoverable. Per-user undo stacks (Figma/Liveblocks) revert only *your* actions
and no-op on conflicts.

**Agentic human-in-the-loop.** LangGraph `interrupt()` + AG-UI model a pause that
serializes full state and lets the human *edit args*, not just approve.
Plan-mode (Copilot/Cursor) emits an editable plan artifact *before* executing.
LangChain's **Agent Inbox** types each interrupt as *review / question / flag*
with allowed-response flags. Vercel AI SDK makes `needsApproval` an async function
of the *inputs*. Anthropic's data: autonomy is *earned* — experienced users
auto-approve more but also intervene more (monitor, don't pre-approve). Checkpoint
time-travel lets you rewind the agent to a step and re-run.

**Optimistic UI / undo-redo design.** Two-stack command pattern; **new action
truncates redo** (the default). **Coalesce** many fine-grained events into one
undo unit (time-window or explicit pause/resume). **Selective undo** = append the
inverse of one past action without rewinding the rest. Detect conflicts at
*enqueue* time and prune the stack, signaling it subtly, rather than failing at
undo time.

**Teach-mode / provenance.** Programming-by-demonstration = record concrete
actions, then *generalize* constants into parameters (ALLOY 2025 showed
demonstration beats prompting for preserving intent). Keyboard Maestro/Zapier =
inline variable tokens + conditional **Paths** (branching). Dry-run = an
*actionable diff*, not a summary. ProvenanceWidgets = history *in situ* on the
control. Trrack/undo-tree = the history *graph itself* is the navigation control
(click a node = go there), with bookmarks + annotations.

---

## Idea catalog (deduped, feasibility source-verified)

Tagged `[polish | simplify | extend | new-UX]`. "Afford" = how much the substrate
already gives us.

### Tier 1 — near-free completions (data already on the wire)

1. **Redo** `[extend]` — second cursor walking forward via inverse-of-inverse;
   a new appended Effect truncates redo (tombstone entries above the cursor to
   stay append-only). *Afford ~90%: inverses already stored per entry; no redo
   exists today (verified). Highest ROI.*
2. **Deterministic "because" line on proposals** `[polish]` — render the
   trust-lattice gate reason as plain text ("HIGH-risk → always confirmed";
   "Assist + LOW → auto-approved"). Mechanical, not LLM-generated. *Afford ~95%:
   the gate already computes this; just expose it as a field. Fights automation bias.*
3. **"Why uncertain?" expand on the confidence chip** `[polish]` — the chip
   exists; clicking a low chip reveals the factors (risk tier, ambiguity). *Afford ~90%.*
4. **Undo toast with a label** `[polish]` — "Undid: Open knowledge pane" /
   "Undid 3 AI actions", derived from the top entry's effect + originator. *Afford ~95%.*

### Tier 2 — surface what the journal already records

5. **Time-travel: click an audit-log entry → restore that point** `[new-UX]` —
   the list exists; add jump-to-cursor (apply inverses back to entry N). *Afford
   ~85%: data + inverses present; new interaction on an existing surface.*
6. **Causation "why did this happen?" trace** `[new-UX]` — single-hop chips
   exist; add a bottom-up chain to the root (later: a clickable DAG). *Afford
   ~85% for the linear trace; the graph is more work.*
7. **Grouped/atomic undo via causation** `[extend]` — collapse an agent's
   multi-effect action (shared causation root) into one undo step; optional
   explicit `beginGroup/endGroup`. *Afford ~80%: causation captured; undo doesn't
   yet collapse by it.*
8. **Confirm-before-mass-undo preview** `[simplify]` — "Undo all AI actions"
   exists; add a dry-run list + count modal before committing. *Afford ~90%.*

### Tier 3 — teach-mode / automation (build on save-as-macro)

9. **"Save what the agent just did as a macro"** `[polish]` — save-as-macro
   needs manual multi-select; pre-select the agent's just-accepted entries
   (demonstration-as-authoring). *Afford ~90%: a pre-filtered shortcut into the
   existing flow.*
10. **Parameterize-on-save** `[extend]` — on save, propose which constants
    (paths/queries) become `{{params}}`, wiring to the existing elicit prompt.
    *Afford ~75%: token substitution + elicit exist; the generalization heuristic is new.*
11. **Conditional / branching macro steps** `[extend]` — a condition/filter
    Effect kind (Zapier-Paths-lite); macros are linear today. *Afford ~55%: new
    effect kind + a safe expression evaluator.*
12. **Macro dry-run *diff* view** `[polish]` — `previewMacro` returns the effect
    list; render before/after diff rows (each effect declares `describeChange()`).
    *Afford ~75%.*

### Tier 4 — reframe the agent UX (higher ceiling)

13. **Agent plan-preview card** `[extend]` — show the whole proposed step
    sequence as one editable/reorderable plan *before* execution (LangGraph/Copilot
    plan-mode). *Afford ~70%: PendingEffect sequences + the macro model it; needs a
    "confirm plan → run" protocol.*
14. **Agent Inbox + typed interrupt kinds** `[new-UX]` — type each pending as
    *review / question / flag*; a dedicated async review surface. *Afford ~70%: the
    queue + activity drawer are the seed; add a `kind` enum + per-kind rendering.*
15. **Edit-args-before-approve** `[extend]` — modify a proposal's parameters at
    approval time, not just accept/reject. *Afford ~65%: needs a param-schema form +
    a modified-accept path.*
16. **Adaptive autonomy + time-boxed grants** `[extend]` — track approval/rejection
    streaks per op-class (from the journal) → suggest dial changes; "auto-approve
    reads for this task / 10 min" via a consent grant with expiry. *Afford ~65%:
    journal + consent exist; needs a streak query + expiry logic.*

### Tier 5 — ambitious / local-first horizon

17. **Branching undo-tree ("recover the discarded future")** `[new-UX]` — a new
    Effect after undo *branches* the future instead of destroying it; vim-undotree
    UX. *Afford ~50%: needs a `branchId` per entry + a branch registry; high value in
    agent-heavy sessions.*
18. **Selective undo** `[new-UX]` — undo one past action by appending its inverse
    without rewinding the rest. *Afford ~60% for the mechanism; conflict detection
    is the hard, novel part.*
19. **Journal export / session archive / replay** `[extend]` — serialize entries
    as a replay script the macro engine runs (portability without sync infra; a
    seam toward future cross-device sync). *Afford ~85%.*

---

## Recommended sequence (no obligation — a suggested path)

If picking up incrementally, the order that maximizes value-per-effort and
compounds:

1. **Redo** (#1) — completes the single most conspicuous half-built primitive;
   unlocks the time-travel surfaces below.
2. **The trust/calibration polish pair** (#2 + #3 + #4) — a day of FE work, each
   nearly free, and together they make the agent's actions *legible* (why it
   acted, how sure it was, what just got undone).
3. **Surface the journal** (#5 time-travel + #6 causation trace) — turns the
   already-rich journal into the app's "memory + explainability" UI.
4. **Close the teach-mode loop** (#9 agent→macro + #10 parameterize-on-save) —
   the agent becomes a *demonstration source*; the user turns "what the AI just
   did" into a reusable, parameterized workflow. This is the most distinctive
   product direction the substrate uniquely affords.

Higher-ceiling bets (#13 plan-preview, #14 agent inbox, #16 adaptive autonomy,
#17 undo-tree) are worth a dedicated tempdoc each when the moment comes; they
reframe the agent UX rather than complete a primitive.

---

## Confidence & key uncertainties (de-risk BEFORE implementing)

Honest split: the journal/inverse/cursor **primitives are solid and proven for
USER effects** (navigations etc. genuinely populate the journal). But the
**agent-facing value of most ideas hinges on one gap that the arc's own lesson
predicts** — so confidence is mixed, and the surprises are concentrated, not diffuse.

**U1 — LINCHPIN (statically confirmed; live-verify pending): real agent activity
does not reach the FE Effect Journal / PendingEffect.** Grep is unambiguous: the
agent surface (`AgentSessionController` / `AgentView`) makes **zero** journal or
pending writes. The only production producers of `originator:'agent'` journal
entries are (a) the producer-less `vop_` path (`VirtualToolDispatcher.ts:61`) and
(b) a **demo** component (`AgentEmitterDemo.ts`). The real agent runs core ops
server-side and approves them in the in-chat UI — none of which touches the FE
journal. So the agent-facing ideas (#5 time-travel of AI actions, #6 AI-causation,
#9 agent→macro) — *and the already-shipped "what the AI did" digest + "undo all AI
actions"* — currently read an **empty/synthetic stream** for the real agent. They
were only ever "live-proven" via console-fed/demo state (the Vite dual-instance
caveat), never against real agent traffic. **Confidence in agent-facing ideas as
directly implementable: LOW.** The true prerequisite is a new **idea #0 — an
agent→journal bridge** that reflects the real agent surface's approved
tool-calls/results into the journal as `originator:'agent'` entries. With that
bridge, every agent-facing idea lights up at once; without it, they decorate
nothing (exactly the dial's old fate).

**U2 — redo is not "inverse-of-inverse" trivial (MEDIUM).** Re-applying an undone
effect through `applyEffect` would **re-journal** it (applyEffect auto-records),
and `_undoCursor` resets to `_entries.length` on every append and on
cross-session restore. Redo needs: a replay-without-re-journal path (or a
cursor-move-without-dispatch), an explicit truncate-on-new-append rule, and a
persistence decision. Also unverified: where undo is even triggered (keyboard /
command binding). The mechanism exists; the journaling/persistence interaction is
a real design step, not a one-liner.

**U3 — is the gate reason / confidence actually on the FE wire? (LOW until checked.)**
#2 (deterministic "because") assumes the trust-lattice REASON reaches the FE; #3
("why uncertain") assumes confidence carries factors AND is populated on the real
path. Both unverified — and #3 is doubly gated by U1 (PendingEffect confidence has
no real agent producer).

**U4/U5/U6 — journal richness + replay safety (MEDIUM).** Multi-step "jump to
entry N" must correctly handle null-inverse / marker / async backend-undo
(`POST /api/undo/{id}`) entries. Causation density in real flows is unknown —
sparse causation = a shallow "why" trace. And replaying an agent's
`invoke-operation` macro would **re-run backend ops** (re-invoking destructive
file-ops) — a safety concern for agent→macro that needs a guard.

**Solid (HIGH confidence):** the user-effect redo *mechanism*, undo labels,
mass-undo confirm-preview, macro dry-run diff — UI/logic over data that already
exists for user + macro flows.

### How confidence gets increased (summary; full plan separate)
Per uncertainty: **U1** → trace the agent SSE tool-call path for any journal
write + a live run of a real agent op inspecting the journal/digest for real
`agent` entries; then scope the agent→journal bridge. **U2** → read
`applyEffect` journaling + `_undoCursor` lifecycle + find the undo trigger; sketch
the redo design + name the pitfalls. **U3** → trace `/api/chat/agent/tools` + the
tool-call SSE payload fields; capture a real payload live. **U4–U6** → inspect a
real post-session journal (causation density, entry kinds) + trace the
macro-replay path for backend re-invocation. Each yields a verified verdict +
any re-scoping, *before* a line of feature code.

### De-risking results (confidence pass, 2026-05-26 — static traces, conclusive)

The four uncertainties were resolved by reading the code (these are "does a code
path exist" questions, definitively answered statically). The live dev-server
experiments were **not run** — U1's verdict is the *absence* of a code path
(conclusive by reading), and the shared dev stack is held by another agent;
running the LLM would only re-confirm what the missing code already proves.

**U1 — CONFIRMED (conclusive). The real agent surface produces zero journal
entries.** `AgentSessionController`'s SSE handlers mutate only its own
`conversation`/`toolCalls` state and `notify()` the view; `dispatchEvent` (`:484`)
is an internal SSE-event *router*, not a DOM/journal path — no `recordEffect` /
`applyEffect` / `jf-invoke-operation` anywhere. The only `originator:'agent'`
producers are the producer-less `vop_` path (`VirtualToolDispatcher.ts:61`) and a
**demo** (`AgentEmitterDemo.ts`). → **Idea #0 (agent→journal bridge) is the
prerequisite** for every agent-facing idea AND for the already-shipped digest /
undo-AI to mean anything. **Bridge shape (feasible, contained):** when a tool-call
reaches `completed` in the controller, `recordEffect({originator:'agent'})` an
`invoke-operation` mirror of `{toolName, arguments}`; if the ToolCall's
`executionId` is present, also `markUndoableOperation(entryId, toolName,
executionId)` — which wires "undo the AI" through the *existing* backend-undo
path (`POST /api/undo/{id}`). Care: avoid double-attribution, and only the
`executionId`-bearing (undoSupported) calls get a real inverse.

**U2 — redo re-scoped DOWN (was ~90% → MEDIUM, with a concrete design).**
`applyEffect:371` re-journals on *every* dispatch, so redo must NOT re-dispatch
through it — it needs a journal-suppressed replay of the original `effect` (or a
cursor-move that re-applies side-effects without `recordEffect`). And the global
`undoLastEffect` has **no production trigger** (only the by-originator buttons in
`EffectAuditLog` exist) — so redo needs a *trigger story* too (and global undo
itself lacks one). Design: a `redoCursor`, re-apply the original effect via a
journal-suppressed path, truncate-on-new-append, session-only persistence.

**U3 — CONFIRMED. The `ToolCall` wire shape carries `risk` but no `reason` and no
`confidence`** (`AgentSessionController.ts:28-38`). → **#2 (because-line) is
feasible FE-only** by *deriving* the text from `risk` + dial level (the same logic
as `agentToolAutoApprove`); no backend change. **#3 (why-uncertain confidence) is
blocked on the real path** — there is no confidence datum on a real tool-call; the
confidence chip lives on the producer-less PendingEffect path. #3 would need the
backend/LLM to emit confidence first.

**U4/U5/U6 — CONFIRMED.** Causation is set **only on accepted sequences**
(`proposeEffectSequence`/`acceptSequence`); single effects get none, and the real
agent never uses `proposeEffect` → real-agent causation is doubly empty, so #6
("why did this happen") is shallow until causation is enriched. `runMacro:212`
replays via `applyEffect`, so a macro containing an `invoke-operation`
**re-runs the backend op** → agent→macro (#9) needs a destructive-replay guard.
Jump-to-entry (#5) must special-case async backend-undo entries (not pure FE
inverses).

### Re-scoped confidence + revised first step

| Idea | Was | Now | Note |
|---|---|---|---|
| **#0 agent→journal bridge (NEW)** | — | **HIGH feasibility / keystone** | Unlocks the whole agent-facing half + makes the *shipped* digest/undo-AI real. Recommended FIRST. |
| #1 redo | ~90% | **MEDIUM** | Journal-suppressed replay + a trigger story; not a one-liner. |
| #2 because-line | ~95% | **HIGH (confirmed)** | FE-derive from risk+level; no backend change. |
| #3 why-uncertain | ~90% | **BLOCKED** | No confidence on the real path; needs backend emission. |
| #5 time-travel, #9 agent→macro | — | **gated on #0** | Then feasible (#9 + a destructive-replay guard). |
| #6 AI-causation | ~85% | **gated on #0 + shallow** | Causation only on sequences; needs enrichment. |
| user-facing (undo-label, mass-undo-confirm, macro-diff) | HIGH | **HIGH (unchanged)** | Real producers (user/macro flows) exist. |

**Revised recommendation:** the first implementation slice should be **#0, the
agent→journal bridge** — it is contained, and it is the single change that turns
the already-shipped agent surfaces *and* half the catalog from synthetic to real.
Without it, the agent-facing ideas repeat the dial's substrate-ahead-of-consumer
fate. The user-facing polish (#2 because-line, undo-label, mass-undo-confirm,
macro-diff) and #1 redo can proceed in parallel; #3 is parked behind a backend
confidence signal.

## Implementation log

### Idea #0 — agent→journal bridge — CODE COMPLETE + UNIT-VERIFIED; live success-path BLOCKED (2026-05-26)

**Implemented** (`AgentSessionController.onToolExecCompleted` → `journalAgentToolCall`,
commit `7bd584bce`): a successful server-side tool-call is mirrored into the
Effect Journal as an `originator:'agent'` invoke-operation entry via
`recordEffect` (never `applyEffect` — the op already ran; re-dispatch would
re-run it). `executionId`-bearing calls get `markUndoableOperation` (undo-the-AI
via the existing `POST /api/undo/{id}` path). vop_ tools skipped (self-journal);
failures not journaled.

**Unit-verified:** 4 new controller tests (success→entry+undoable-mapping;
failure→none; vop_→skip; no-executionId→entry-without-mapping), each driving the
real handler; full controller suite 53 green; effects 43 green; typecheck clean.

**Live-stack: PARTIAL — the success→journal branch is NOT observed, blocked by a
backend issue, NOT this FE code.** Pushed hard (4 dev-stack configs, real Qwen
LLM, two tools). Confirmed live: the agent surface processes real tool-call SSE
frames end-to-end; the autonomy dial gates them (MEDIUM·PENDING under Assist;
Auto auto-approves — my §32 unify); and **`onToolExecCompleted` fires on real
runs** (it set the failed status the UI showed). What could not be observed: a
*successful* tool-call producing the journal entry — because **no agent tool-call
completes successfully in this dev stack**: every search/ingest tool-call fails
with *"No handler registered for binding core.search-index"* (thrown by
`OperationExecutorImpl.java`, the server-side operation-executor handler
registry). My bridge is success-gated, so on these failures it correctly journals
nothing — i.e. the bridge behaved correctly; there is simply no success to
mirror. This is the **same blocker the §32 unify live-proof hit** (recorded in
`543-fwd-live-evidence.md`: "the agent's search handler errored on execution …
an unrelated tool-registration issue").

**Specific blocker (per the /goal — named, not deferred):** the dev stack's
server-side agent-tool operation handlers are unregistered (`core.search-index`
binding has no handler in `OperationExecutorImpl`), so no agent tool-call
returns success. This is a **backend / dev-environment condition**, genuinely
unrelated to this FE journal-bridge tempdoc (logged to `observations.md`). Until
a dev stack executes an agent tool-call successfully, idea #0's
success→journal link is verifiable only at the unit tier + the live
hook-fires tier. **Idea #0 is therefore NOT marked `status: implemented`.**

### UPDATE — backend blocker ROOT-CAUSED + FIXED (commit `0febc18fb`)

Investigated the "No handler registered for binding core.search-index" blocker to
root cause. **It is an ordering bug in `HeadAssembly.connectKnowledgeServer`, not a
missing handler.** The agent's server-side tools are registered by
`AgentToolHandlers.registerLateBound`, triggered via a one-shot `Memoized<Boolean>`
(`agentToolsRegistration`). That memo was resolved at the *top* of
`connectKnowledgeServer` — **before** the WorkerCapability bridge (a few lines
later) transitions `this.capabilities.worker()` from the standalone `PENDING` state
to the Worker's real `READY`. So `registerLateBound` saw `!workerCapability.available()`
(a standalone PENDING capability's `available()` is false — pinned by
`WorkerCapabilityBridgeTest:68`), logged "skipped", returned `false`, and `Memoized`
cached that `false` for the process lifetime (no overwrite path). The tools were
never registered; `OperationExecutorImpl` threw on every agent tool-call. Classic
`standalone-capability-stays-stuck` ordering bug. (Also why the §32 unify proof hit it.)

**Fix (`0febc18fb`):** move the registration trigger to *after* the capability
bridge; resolve the memo only when the worker is `available()` (caching the success,
never a premature false); forward later capability transitions so a worker reaching
READY shortly after connect still registers. `registerLateBound` is idempotent.
**Statically verified:** app-services tests green (HeadAssembly / WorkerCapabilityBridge
/ OperationExecutor / OperationSubstrateInit); full `./gradlew build -x test` green.

**Live-verify of the fix (and thereby idea #0 end-to-end) — pending a coordination
decision, NOT a code gap.** The MCP dev-runner builds + runs from the **main checkout**,
not this worktree, and local `main` has diverged with ~20 commits of the **549
(unified-search-trace)** worktree's work — so exercising my backend fix in a dev stack
requires merging this branch into `main` first: a real merge (a
`gates/consumer-drift/slots.json` union conflict with 549's explain-panel slot, + likely
an `observations.md` union). Merging as-yet-live-unverified work into 549's on a shared
`main` and building/pushing it is a cross-agent, hard-to-reverse action — flagged for the
user, not done autonomously. Once merged (union-resolve the slots + observations), a
dev stack built from `main` registers the agent tools; the live smoke is then: agent
search → tool succeeds (no "No handler" error) → a real `originator:'agent'` journal
entry appears in the digest / audit log (idea #0 end-to-end).

Also encountered (environmental, logged): repeated backend deaths under corpus
re-indexing saturation (`clean:soft` on the default dataDir → ~30 concurrent
indexing jobs → SSE drops); avoided via `clean:none` + a complete index. And a
#1-bridge staleness edge: the indexing-jobs Task tray showed ~30 phantom RUNNING
jobs (backend reported 0 active) — stale entries from backend deaths that never
received "completed"/departed frames.

**Remaining tempdoc work (NOT blocked by the above — verifiable against the
user-effect journal):** #2 because-line (FE-derive from risk+level), undo-label,
mass-undo-confirm preview, macro dry-run diff, and #1 redo. These do not depend
on agent tool execution and are the next implementation targets.

### UPDATE 2 — boot-NPE hotfix + idea #0 LIVE-VERIFIED end-to-end (commit `299b2ba69`)

The first fix (`0febc18fb`) had a latent ordering bug that only surfaced once it
made `registerLateBound` actually run: it resolved the Memoized **before**
`this.services` was reassembled with the fresh worker services, passing a **null
`indexingService`** into `registerLateBound` → `NullPointerException`
(`AgentToolHandlers:111`, `indexingService::getWatchedPaths`) → HeadlessApp
crashed in `connectKnowledgeServer` → `/api/status` never stayed up. Merging that
to `main` **broke head boot for all agents** (a real regression). **Hotfix
`299b2ba69`:** move the registration trigger to *after*
`this.services = assembleServiceGraph(..., newIndexing, ...)` so
`this.services.worker().indexing()` is the live client. Builds; the dev stack now
boots (`ready_worker:true`).

**LIVE-VERIFIED end-to-end (real Qwen, dev stack `:61771`, FE `:5173` = merged
main):** dial set to Auto, prompted *"Search the knowledge base for configuration…"*.
The agent ran real tool-calls that **executed successfully** (answer cited "103
total files in the slices folder" with real filenames — handler registered now; no
"No handler registered for binding core.search-index"), and **idea #0's bridge
journaled them**: the mounted "what the AI did" digest showed **"Since you last
looked, the assistant: 2 invoke-operations"** + "Undo all AI actions" (screenshot
`ss_00012v1pq`). Before idea #0 this digest read empty for the real agent — this is
the `REAL agent tool-call → real originator:'agent' journal entry → mounted UI`
proof the goal required.

**Idea #0 is now `status: implemented` (live-verified).** Net of the whole
investigation: the original "No handler" blocker was an ordering bug in
`connectKnowledgeServer` (registration before the capability bridge); the naive fix
exposed a second ordering bug (registration before the services rebuild → boot
NPE); the hotfix resolves both — agent tools register AND the head boots, and the
agent→journal bridge is real and live-proven.

### UPDATE 3 — remaining FE ideas implemented (worktree `worktree-543-fwd-ideas`, 2026-05-26)

The five non-blocked follow-on ideas, each code-complete + unit-tested + committed.
All FE-only; full FE suite green (2078). Built on idea #0's now-real agent→journal
stream.

| Idea | Commit | What shipped | Status |
|---|---|---|---|
| #4 undo-label | `82348d1de` | `describeEffect()` (exhaustive); `peekLastUndoableByOriginator`; "Undid: <effect>" / "Undid N AI actions" toasts on the audit-log + digest undo affordances | code+unit ✓; live: affordance fires on real agent entry ("Nothing to undo", correct for non-reversible invoke-op); labelled branch unit-tier |
| #2 because-line | `d45d33224` | `becauseLine(risk, level)` (mirrors `agentToolAutoApprove`); rendered on pending `ToolCallCard`s, level-reactive; FE-derived (U3: no wire reason) | **`status: implemented`** — LIVE PASS (real agent MEDIUM pending card → "Watch mode — every action needs your confirmation.") |
| #8 mass-undo-confirm | `3d449efc5` | `previewUndoAllByOriginator`; two-step confirm listing the exact reversal set before commit, on both digest + audit log | code+unit ✓; live: affordance fires on real agent entry ("Nothing to undo", no panel — correct); confirm-panel branch unit-tier (needs reversible agent entries) |
| #12 macro dry-run diff | `0b6d63757` | `previewMacroReplay` (entries + backendOps); `runMacro({allowBackendReplay})` guard (no silent backend re-POST); `<jf-macro-dry-run>` panel; palette routes backend-bearing macros through it | code+unit ✓; live demo blocked by elicit name-form authoring (separate substrate); panel/guard/routing unit-proven |
| #1 redo | `f061c219a`, `5da6265c5` | `dispatchEffectToChrome` (journal-suppressed); `redoLastEffect` cursor (symmetric to undo); global Undo/Redo (mod+z / mod+shift+z) + palette Actions; truncate-on-append; session-only persistence; navigate replay-suppression | code+unit ✓; live: undo trigger PASS ("Undone" + reversal), redo trigger fires; **defect found+fixed live** (navigate re-journal); navigate-cursor best-effort (own history model) — documented |

**#3 why-uncertain: PARKED — not built.** U3 refuted the premise (no confidence
datum on the real tool-call wire); it needs backend/LLM emission first.

**Live-verification (2026-05-26, dev stack taken over with user approval):** full
log in `543-fwd-live-evidence.md` UPDATE 3. Worktree FE on :5174 pinned to the
backend :61243, real Qwen3.5. **#2 because-line is LIVE PASS** (real agent
pending card → my derived text) and is marked `status: implemented`. Idea #0's
bridge was re-confirmed live (digest "1 invoke-operation"). The deterministic FE
ideas (#4/#8/#12/#1) are unit-complete; live, their affordances were confirmed
firing on real agent data, but their RICH branches (labelled undo, mass-undo
confirm panel, macro dry-run panel) need either FE-*reversible* `agent` entries
(the real agent emits only non-reversible `invoke-operation`) or macro authoring
(elicit-form friction) — those branches stand at the unit tier with named live
blockers, NOT a generic deferral. #1 redo additionally surfaced + fixed a real
navigate re-journal defect live.

### UPDATE 4 — end-to-end remaining work (worktree `worktree-543-fwd-remaining`, 2026-05-26)

Built on the research-grounded undo model (FE inverse / saga compensating /
checkpoint-rewind). Six priorities, each code-complete + unit-tested + committed
(full FE suite 2128 green). Live log: `543-fwd-live-evidence.md` UPDATE 4.

| Pri | Commit | What shipped | Status |
|---|---|---|---|
| P1 undo-the-AI (compensating) | `11c47ee8c` | `undoEffectFor` routes the originator-scoped undos through the FE inverse OR the backend compensating `undo-operation` (executionId); "Undo all AI actions" + preview now include agent mutations (file-ops), reads stay non-compensable | code+unit ✓; live: no compensable entry in journal (needs a mutating op — unsafe to drive); wire proven §32 S5 |
| P2 #9 agent→macro | `a6724a56e` | one-tap "Save as macro" on the digest; shared `defineMacroFromEffects` | code+unit ✓; live: button + modal collection PASS; macro COMPLETION blocked by elicit-form renderer |
| P3 #6 causation | `fa7914693` | bridge chains a turn's tool-calls via causation; audit-log "why?" bottom-up trace | code+unit ✓; live: 1 agent entry this run (backend search fail) → no chain to show |
| P4 #10 parameterize-on-save | `2174c5cfd` | constant extraction + tokenize + per-constant param prompt | code+unit ✓; live blocked by elicit-form renderer |
| P5 #12 dry-run DIFF | `14807338d` | `describeChange` before→after rows in the macro dry-run panel | code+unit ✓; live needs a defined macro (elicit-form renderer) |
| P6 #5 time-travel | `5fac0263c` | `undoToEntry`/`previewUndoToEntry` + per-entry "↶ restore" + confirm | code+unit ✓; live: restore buttons render; reversal over navigate best-effort |

**Pre-existing blockers found + logged (not this work):** (1) elicit forms
render no input controls (`jf-form` empty shadow) — blocks live completion of
the macro-author ideas (#9/#10/#12); (2) backend agent searches returned
`success:false` this run; (3) driving a real `core_file_operations` is unsafe.
A partial ElicitHost uischema-generation fix was reverted (out of scope +
insufficient against the deeper renderer gap). NONE marked `status: implemented`
— code+unit complete with affordances rendering live, specific blockers named.

### UPDATE 5 — de-risked + elicit blocker FIXED + LIVE-VERIFIED (worktree `worktree-543-fwd-remaining`, 2026-05-26)

A confidence pass (3 Explore agents) root-caused the UPDATE-4 elicit blocker as a
*small* fix on a *working* registry (not a deep gap — TextControl/VerticalLayout
work in SettingsLitView; a schema-only request just passed `undefined` uischema).
Three steps then shipped (code+unit, FE suite 2140 green); the keystone is
live-verified. Full log: `543-fwd-live-evidence.md` UPDATE 5.

| Item | Commit | Status |
|---|---|---|
| **Elicit-form fix** (ElicitHost generates a default uischema; VerticalLayout userConfig) | `8aecc2b6e` | **`status: implemented`** — LIVE PASS: the macro-save prompt now renders inputs and the teach-mode loop completes end-to-end (was fully blocked in UPDATE 4). Unblocks #9/#10/#12 live. |
| **#19 journal export / replay** | `4fdcc9517` | code+unit ✓; export button renders live; round-trip + import-replays-as-macro unit-proven |
| **#7 grouped/atomic undo by turn** | `040c5ad3e` | code+unit ✓ (group-root, whole-turn reverse incl. compensable mutation); affordance renders; live needs an agent turn for causation data |

This retroactively unblocks the UPDATE-4 macro-author ideas (#9/#10/#12): the
elicit fix is the keystone they all needed. Still deferred (named): true causal
"why" (#6) + confidence chip (#3) need backend/LLM signals; true state-rewind +
undo-tree (#5-full/#17) need a checkpointer. `undo-the-AI`'s compensable surface
is confirmed `core.file-operations` only.

## Sources

Local-first / time-travel: Ink&Switch local-first essay; Automerge undo/redo
(arxiv 2404.11308, SPLASH 2023); Liveblocks multiplayer undo; reverse-engineered
Linear sync engine; Replicache local-mutations; Redux DevTools; Vim undo-tree /
time-machine.nvim; Martin Fowler / Azure event-sourcing.
Agentic HITL: LangGraph human-in-the-loop; AG-UI protocol; GitHub Copilot
plan-mode; LangChain Agent Inbox / ambient agents; Vercel AI SDK tool approval;
Anthropic "measuring agent autonomy" + Claude Code auto-mode; "Levels of Autonomy
for AI Agents" (arxiv 2506.12469); agent time-travel checkpoints.
Undo/redo design: esveo command-pattern; Figma multiplayer; TanStack Query
optimistic updates; ACM selective-undo; "You Don't Know Undo/Redo" (dev.to);
CodeMirror history.
Teach-mode / provenance: ALLOY (arxiv 2510.10049); CACM programming-by-example;
Keyboard Maestro; Zapier Paths; Playwright codegen; ProvenanceWidgets (arxiv
2407.17431); Trrack; mbbill/undotree; "dry run button" UX.
