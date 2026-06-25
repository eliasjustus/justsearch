---
title: "543-fwd — live browser verification evidence"
---

# 543-fwd — live browser verification evidence

Per-slice live proof captured in real Chrome against the running dev stack.
Setup: worktree FE (`worktree-543-forward`) served on http://localhost:5174
(Vite, `VITE_API_PORT` pinned to the dev-runner backend on :55393). The
backend is the main dev stack (valid where a slice makes no backend change).

---

## ★ Status register (current state — synthesis of UPDATE 6–17, as of 2026-05-27)

**Read this for "where things stand"; the dated UPDATEs below are the evidence trail.**
The 543-fwd polish + fix work is **implemented, statically verified** (typecheck clean; full
FE suite 2228; touched Java module tests green) **and its user-facing paths live-verified**,
on branch `worktree-543-fwd-impl` (**NOT merged** — merge is the user's call). Map: original
item descriptions = UPDATE 8 (register) + UPDATE 9 (de-risk); implementation = UPDATE 12;
defect fixes = UPDATE 13; goal-achievement analysis = UPDATE 14; remaining-uncertainty
de-risk = UPDATE 15; FE-tail browser-validation = UPDATE 16; forward synthesis = UPDATE 17.

**Merge state (read carefully).** The *original §32 arc* (S2–S11 + the dial-unify) AND the early
polish ideas (**UPDATEs 3–11**) are **on `main`** (the arc merged 2026-05-26, see "Post-merge
coda"; main's tempdoc carries UPDATEs 3–11). The **still-unmerged** body on
`worktree-543-fwd-impl` is **UPDATEs 12–18 + residue #3/#5** — `jf-effect-line`, native dialogs,
turn-grouping, P1/P2 fixes, the empty-state fix, SSE-pool dedup, and dialog focus-restoration.
`main` has since been **merged INTO this branch** (UPDATE 18), so the branch is up-to-date and a
fast-forward merge to `main` is the only remaining step (PENDING below, user's call). So "merged"
in the coda = the arc + UPDATEs 3–11, NOT this 12–18 body.

**The through-line for everything still open: the FE is ahead of its producers** (UPDATE 17) —
built surfaces (confidence chip, the `vop_` path, a real "why") are dormant for lack of an upstream
agent/backend signal, not for lack of FE effort.

### DONE — implemented + verified (live unless noted)
- **#1 / #4 / #5 (legibility)** — shared `<jf-effect-line>`: friendly `describeEffect` labels +
  relative time + collapsible structured detail replace the raw-JSON rows; dry-run diff shows
  only when a real `before` exists. (UPDATE 12/13)
- **#2 multiline** — the import-archive JSON field renders a `<textarea>` via `options.multi`.
- **#3 disabled AI-undo buttons** — gated on the reversible-agent-entry count, with "(N)".
- **#6 turn grouping** — collapsible causation-rooted turn headers; **live** (a real
  "agent 2-step turn" rendered); every member row preserved (no lossy run-collapse — Fix C). (UPDATE 15)
- **#7 grouped-undo reversal** — **live end-to-end**: undo-turn → populated confirm → the
  reversal deleted the created dir. (UPDATE 15)
- **P1 undo-the-AI** — full round trip **works live**: agent MKDIR → undo → dir deleted on
  disk. Unblocked by the op-id↔wire-name fix (Fix F) + arg-validation (P2/Fix A). (UPDATE 13)
- **P2 / Fix A — untrusted agent input** — the file-op tool validates args (no NPE); clean
  errors let the model **self-correct live** (`action`→`op`). (UPDATE 12/13)
- **P3 native `<dialog>` + a11y** — focus mgmt, Escape (synced via keydown, Fix B), backdrop,
  aria-modal; aria-live toasts; non-cancellable prompts stay modal (Fix B); #12 responsive
  drawer width. (UPDATE 12/13)
- **Keyboard** (Ctrl+Z / Ctrl+Shift+Z), **becauseLine / autonomy matrix**, **perf** (401 rows
  ≈ 31 ms — no virtualization needed), **persistence** — verified. (UPDATE 9/12)
- **FE-tail (all browser-validated live)** — true zero-entry empty state (**fixed**: distinct
  "No actions recorded yet" copy vs the filter copy), toast lifecycle (severity/stack/dismiss/
  auto-dismiss/aria-live), elicit-queue FIFO (+ textarea #2), rapid undo/redo (symmetric +
  boundary-guarded, zero console errors), S4 digest + Mark-as-seen, light theme. Narrow drawer =
  32rem cap verified live (narrow branch correct by construction). (UPDATE 16)

### REMAINING — scoped / non-functional
- `[SCOPED]` **SSE resilience** — error/abort/malformed/reducer-throw + **recovery** handling now
  fully unit-covered (`EnvelopeStream.test.ts`); live mid-turn fault injection needs a rig. (UPDATE 16)
- `[NF]` Interaction feel (latency / animation) — needs human judgment.

### BLOCKED — forward-looking (needs an upstream signal, not effort)
- **Confidence (R-E2)** — no agent-emitted confidence signal; the proposer
  (`substrates/actions/index.ts:579-581`) never passes `confidence`; §32.9 forbids FE fabrication.
  Blocked on a producer at the proposer seam. (UPDATE 16)
- **Autonomy dial `vop_` path** — `VirtualOperationCatalog` complete+tested but its publish seam
  (`decorateCommandForAgent` → `setVirtualOperationPublisher` → `publishNow`) is never called in
  production; no command opts into AGENT audience. Blocked on the first such command. (UPDATE 16)

### OPEN QUESTIONS — product / design, upstream-dependent (UPDATE 17)
- **Transparency *shape*** — the causation-per-turn grouping MODEL is correct and not a FE redesign
  (UPDATE 15); whether it *pays off* depends on richer multi-step agent behavior + a reason signal,
  i.e. upstream. Open question, not a defect.
- **Reversibility *narrowness*** — "Undo the AI" works only for the one undoable op
  (`core.file-operations`); the Undo affordance therefore appears only for the scariest action,
  never routine ones. Worth a conscious product stance.

### RESIDUE — named, low-stakes, non-blocking (UPDATE 17 §5)
- Export omits `causation` → imported archives are causation-flat. (UPDATE 7 §A)
- Cross-surface state is per-origin — a dev dual-port artifact; production's single origin shares it. (UPDATE 7 §B)
- Narrow-viewport visual verified by-construction, never *seen* on a small screen. (UPDATE 16)
- navigate-via-cursor undo/redo is best-effort (router re-journals / canonicalizes URLs). (UPDATE 3)
- `assist` proposes *all* FE-mediated backend ops, not just risky ones; risk-aware refinement deferred (residue #4 — declined this round). (deep-reflection §B)
- Full a11y sweep: focus-restoration on close **now done** (residue #5, UPDATE 18); **tab order** not exhaustively driven. (UPDATE 9)
- Interaction feel + live SSE mid-turn fault injection — not closable by code (judgment / needs a rig). (UPDATE 8/16)
- *Resolved this round*: indexing-jobs duplicate SSE → pooled (residue #3, UPDATE 18); the "departed → succeeded" note was stale (the bridge **removes** departed job-tasks — confirmed against code + test). Causation-in-export (residue #1) declined this round.

### PENDING
- **Merge to `main`** (user's call) — UPDATEs 12–18 + residue #3/#5; a fast-forward (branch is
  0-behind after merging main in, UPDATE 18). The governance drift previously flagged here was
  **fixed on main** (ts-any baseline seeded, class-size pins realigned) and is now part of this
  branch; the pre-merge gate is expected green modulo any bundle-ratchet rebalance from this
  work's own FE additions (handled in UPDATE 18 verification). (UPDATE 18)

---

## S2 — Agent trust bridge (originator→transport) — 2026-05-25 — PASS

Op used: `core.index-gc` (`RiskTier.MEDIUM`, the silent-execution window per §32.9).

### A. Backend gate differentiates by transport (explicit-header probe)
`POST /api/operations/core.index-gc/invoke`:

| `X-JustSearch-Transport` | HTTP | Result |
|---|---|---|
| `AGENT_LOOP` | **428** | `CONFIRMATION_REQUIRED` — *"gate=TYPED_CONFIRM, sourceTier=UNTRUSTED"* |
| `BUTTON` | **200** | `success` — *"Index GC accepted"* (AUTO) |

(S2-review follow-up: the agent tag was corrected `LLM_EMISSION`→`AGENT_LOOP`
— the catalog's reserved tag for agent tool-calls; both resolve to
`SourceTier.UNTRUSTED`, so the gate verdict is identical. The mapping was
extracted to the unit-tested `originatorToTransport()` helper and re-verified
live above.)

### B. The real FE Shell listener maps originator→transport
`document.dispatchEvent(new CustomEvent('jf-invoke-operation', {detail:{operationId:'core.index-gc', originator:X}}))`
drove the actual §32 S2 Shell listener (not a hand-built fetch):

| `detail.originator` | Observed HTTP (network monitor) |
|---|---|
| `'agent'` | **428 CONFIRMATION_REQUIRED** |
| `'user'` | **200 success** |

### Conclusion
The agent's MEDIUM/write operation is now **gated (TYPED_CONFIRM)** where,
before the bridge, the invalid `'EFFECT'` transport silently degraded to
`BUTTON → AUTO` and the op auto-fired. End-to-end: agent dispatch → Shell
listener → `originatorToTransport('agent')='AGENT_LOOP'` → `OperationClient`
→ `X-JustSearch-Transport: AGENT_LOOP` → trust lattice
`UNTRUSTED × MEDIUM → TYPED_CONFIRM`.

Screenshot artifact: `ss_8344tbadp` (worktree shell live on :5174, status CONN).

---

## S3 — Causation graph (R-E3 / R-P1) + confidence (R-E2) — 2026-05-25 — PASS

Substrate (unit-tested, green): `proposeEffect` stores `confidence`;
`acceptSequence` chains accepted entries via `causation`
(`entry[i].causation === entry[i-1].id`); `getCausationChain` walks to root.
Confirmed live that proposals register through the real module
(`pendingCount=2`, `acceptSequence` → `accepted=3`).

### R-E2 — PendingEffect confidence (real mounted component, live)
Fed `<jf-pending-effect-queue>` two agent proposals (confidence 0.9 then
0.2). Rendered: 2 cards, 2 confidence chips, the 0.2 chip warning-styled
(`data-low`, orange) and **sorted first** ("20%" above "90%") — proves chip
rendering + most-uncertain-first sort. Screenshot: `ss_4940mnfx6`.

### R-E3 — causation chip (real mounted component, live)
Fed `<jf-effect-audit-log>` (open) three accepted agent entries chained
10←11←12. Rendered 3 rows; causation chips `↳ #11` and `↳ #10` on the
chained entries; the root (id 10) shows none. Screenshot: `ss_2325r5990`.

(Note: Vite dev serves the app and a console `import()` as *separate* module
instances, so substrate state set via a console import does not reach the
mounted components. Component RENDERING was therefore proven by feeding the
real mounted components realistic state; the substrate LOGIC is unit-tested
and was confirmed live through the real module.)

---

## S4 — "What the AI did" digest (U3) — 2026-05-25 — PASS

Substrate (unit-tested): `summarizeAgentActivity(sinceId)` counts agent
actions grouped by Effect kind, excludes user-originated + rejected entries,
and respects the "last seen" cursor.

Component `<jf-ai-activity-digest>` (mounted in Shell's render tree): render
tests cover empty-collapse, summary-by-kind + undo-all button, and
mark-as-seen → collapse.

Live (real mounted component, top-right banner): *"Since you last looked, the
assistant: 3 navigates, 2 invoke-operations"* + a "1 PENDING" badge + "Undo
all AI actions" + "Mark as seen". Screenshot: `ss_2608s9heq`.

---

## S5 — Undo-the-AI (U2) — 2026-05-25 — PASS

Substrate (unit-tested): `markUndoableOperation`/`getUndoableOperation`
side-map (keeps `JournalEntry` append-only); new `undo-operation` Effect kind;
`applyEffect` threads `journalEntryId` into the invoke-operation detail and
dispatches `jf-undo-operation`. `OperationClient.undo` (pre-existing) POSTs
`/api/undo/{id}`.

FE wiring: the Shell jf-invoke-operation listener captures the returned
`executionId` (undoSupported ops) → `markUndoableOperation`; a new
jf-undo-operation listener → `OperationClient.undo`. `EffectAuditLog` renders
an "Undo" button on undoable entries → dispatches `undo-operation`. All
unit-tested (dispatch, journalEntryId threading, button render + click,
side-map).

Live (real Shell listener → real backend): dispatching
`jf-undo-operation {operationId:'core.index-gc', executionId:'fake-exec-123'}`
→ **POST /api/undo/core.index-gc → 200**. The FE→backend undo wire fires
end-to-end. (executionId synthetic — proves the wire, not a specific reversal;
production supplies a real captured id from the invoke result.)

---

## S6 — Autonomy Dial (U1) — 2026-05-25 — PASS

Designed per §32.9 as a FE surface OVER the backend trust lattice (NOT an
accept-count learner). Substrate `autonomy/`: `watch | assist | auto`,
persisted; `agentInvocationDisposition()` → watch=propose, assist/auto=dispatch.

Production consumer (the gate): `invokeAndApply`, for an agent invocation in
'watch' mode, PROPOSES the effect to the PendingEffect queue instead of
dispatching; assist/auto dispatch (the backend lattice still gates
write/destructive — the dial never lets a destructive op auto-fire). Unit-tested
integrated flow (autonomy-gate.test.ts, real substrates in one process):
watch+agent → queued (no journal); assist+agent → journaled (no queue);
watch+user → dispatched (the dial only gates agent invocations).

UI `<jf-autonomy-dial>` mounted in SettingsSurface ("Agent autonomy" section).
Live (real app substrate instance): 3 segments, `assist` active by default;
clicking **Watch** → active moves to Watch, hint "Agent actions wait in the
queue for your approval", and the "Destructive actions are always confirmed
regardless of this setting (backend-enforced)" note is shown. Screenshot:
`ss_7553kwbdc`.

---

## S7 — Async / long-running Effect (R-E1) — 2026-05-25 — PASS

A standalone Task substrate (the right shape — async tasks don't fit the
synchronous closed Effect union, the §32.9 gap), mirroring MCP Tasks:
start/updateProgress/complete/cancel/list/subscribe; cancellable tasks invoke
an injected cancel fn. Unit-tested (tasks.test.ts: lifecycle, progress-only-
while-running, cancel-invokes-fn, listRunning/clearFinished).

Production consumer: the Shell wraps agent-originated operation invocations as
tasks (running → succeeded on resolve / failed on reject). `<jf-task-list>`
(lower-left, collapses when empty) renders status + progress + cancel; mounted
in Shell. Component render-tested (TaskList.test.ts).

Live (real Shell + real backend):
- Integrated: dispatching agent `jf-invoke-operation core.index-gc` created a
  task "Agent operation: core.index-gc" that resolved to **failed** — correct,
  because the agent op hit the S2 trust gate (428), proving the whole
  agent-op → task → gate chain end-to-end.
- Visual: the list renders "RUNNING Rebuild index [Cancel]" with a 60%
  progress bar + "SUCCEEDED Summarize 12 documents". Screenshot: `ss_2974dd6jy`.

Follow-up: wiring the backend indexing-jobs stream (SubscribeIndexingJobs +
CancelIndexingJobHandler) into the Task substrate is the natural real-time feed
(additive — the substrate + UI are ready).

---

## S8 — Sequence dry-run (R-E5) + parameterized macros (U4) — 2026-05-25 — PASS

Substrate-level — no new chrome (reuses the existing `<jf-elicit-host>` modal +
the command palette). Proven by an in-process integration test through the REAL
elicit substrate (not mocked).

R-E5: `effects/previewSequence(effects[])` previews a plan (would-be journal
entries) without dispatching; `macros/previewMacro(id, vars)` consumes it
(substitutes vars, no dispatch). Tested: previewSequence no-dispatch;
previewMacro substitutes `{{name}}` and appends nothing.

U4: `defineMacro` accepts optional `params` (an elicit prompt schema);
`runMacro` is now async — for a parameterized macro it prompts via elicit,
substitutes `{{key}}` tokens in effect string fields with the elicited values,
then dispatches. Integration test (real elicit): `runMacro('greet')` → elicit
request pending → `resolveElicit({name:'World'})` → toast "Hello World"
dispatched; a cancelled prompt → 0 dispatched.

Live (boot-regression check): the shell boots cleanly with the S8 changes —
`<jf-shell>`, `<jf-elicit-host>` (the parameterized macro's visible surface,
already proven), and `<jf-task-list>` all present; no errors. No new
screenshot — S8 adds no new visible surface. Verify: tsc clean; vitest
1984/1984.

---

## S9 — Unified agent activity surface (U5) — 2026-05-25 — PASS

`<jf-agent-activity-panel>` — a toggled right-drawer aggregating the existing
substrates into one "agent presence" panel: Pending proposals (accept/reject),
Running tasks (label + progress), Recent agent actions (last 8 from
`listJournalByOriginator('agent')`). Toggled via
`core.action.shell.show-agent-activity` (mirrors the audit-log toggle); mounted
in Shell. Reads the same substrates the floating surfaces use — an aggregating
consumer, not a new store. Render-tested (AgentActivityPanel.test.ts, 4):
closed→nothing; open→three sections with counts; accept→consumes pending;
close→collapses.

Live (real mounted component): the drawer renders "PENDING (1) invoke-operation
reclaim space [Reject][Accept]", "RUNNING TASKS (1) Rebuild index 45%", "RECENT
AGENT ACTIONS (2) navigate / toast accepted". Screenshot: `ss_0780u2vv3`.

§28 lesson, live: this check caught a runtime bug typecheck could not — the
panel was imported as a NAMED import used only in a type cast, so Vite ELIDED
it and `customElements.define` never ran (`defined:false`). Fixed with a
side-effect import + a type-only import.

---

## S10 — R-S1 (unify pending/elicit/consent?) + R-P3 — 2026-05-25 — VERIFY → SKIP UNIFICATION + R-P3 GUARD

The `/goal` prompt scoped S10 as *verify-first*: "unify the three **only if
they are genuinely the same shape; otherwise record why-not and skip**." This
slice is an architectural-judgment + regression-guard slice — no new
user-visible surface, so no new screenshot (consistent with the prompt's
"otherwise skip" path).

### R-S1 — the three substrates are NOT the same shape → DECLINE unification

Read verbatim against source (`pending-effects/index.ts`, `elicit/index.ts`,
`consent/index.ts`). They share only one thing: a *dispatch-to-host*
convention (a `document` CustomEvent + a chrome host that listens). That shared
surface is **already factored** — the S2 `dispatchDomEvent` helper + the
per-host listener convention. Above that single shared seam, they diverge on
every load-bearing axis:

| Axis | PendingEffect | elicit | consent |
|---|---|---|---|
| Resolution model | **event-driven** (subscribe; chrome calls accept/reject out-of-band) | **Promise** (awaited mid-handler) | **Promise** prompt **+ synchronous lookup** (`isAllowed`) |
| Decision shape | binary (accept / reject) | arbitrary structured form value | 3-way enum (`allow-once`/`allow-always`/`deny`) |
| Payload | a pre-determined `Effect` | a form value | a capability grant |
| Persistence | none | none | **yes** (localStorage; survives sessions) |
| Journal-integrated | **yes** (writes a `JournalEntry`) | no | no |
| Dominant consumer | the review queue UI | the one awaiting handler | the gate's `isAllowed` (**mostly no prompt at all**) |
| Lifetime | one decision then gone | one decision then gone | persistent grant, re-read indefinitely |

Three distinctions make a unification genuinely *leaky*, not just verbose:

1. **consent is dominated by a persistent keyed lookup, not a request.**
   `isAllowed` / `checkCapability` run with no prompt; `requestCapability`
   fires only on 'undecided'. An "Interaction = request→decide→resolve"
   abstraction does not model consent's primary job (a persistent
   policy store queried synchronously). Folding it in forces a persistent
   grant store onto two substrates that never use one.
2. **PendingEffect is event-driven + journal-integrated; the other two are
   Promise-return + journal-free.** Accept *dispatches an Effect and writes
   the journal*; elicit/consent *return a value to a caller*. A single
   abstraction must pick one resolution model — Promise (breaks the
   review-queue's many-coexisting-out-of-band-proposals model) or event
   (breaks elicit/consent's await-mid-handler ergonomics).
3. **The decision types are binary / arbitrary / 3-way-enum.** A unified
   `resolve` would be typed `unknown`, erasing the per-consumer type safety.

Decision: **decline unification**, consistent with §31.2 / W11 (the registry
over-consolidation refusal). The genuine shared kernel (dispatch-to-host) is
already at the right level; the substrates above it are correctly distinct.

### R-P3 — the PendingEffect-accept "double entry" is intentional, not a bug

Investigated the §32.3 R-P3 concern ("`acceptPending` writes two journal
entries; audit shows apparent duplicates"). On reading the code: the two
entries are **distinct facts** — (a) the `applyFn` dispatch entry records
*what happened* (the effect, under `applyEffect`'s default `'user'`
originator), and (b) the marker records the *pending-lifecycle outcome*
(`'accepted'`, under the pending's originator). The split is **load-bearing**:
§32 U3 `summarizeAgentActivity` counts `'agent'` entries and depends on the
dispatch entry NOT being `'agent'`, so an accepted agent pending counts **once**
(the marker), not twice. "Deduping" by re-attributing the dispatch to the
pending's originator would silently double-count it in the digest. Resolution:
added a regression-guard comment in `acceptPending` documenting this so a
future agent doesn't "fix" it and break the digest. No behavioral change.

### Verify
Comment + doc + one regression test; no behavioral code touched. No new chrome
surface → no live screenshot applicable (per the prompt's "otherwise skip"
path). Full FE unit suite green (typecheck clean; 1988→1989 with the new test).

### Independent review — APPROVE-WITH-FOLLOWUPS (`543-fwd-slice-10-review.md`)
The reviewer adversarially attacked the skip and confirmed both decisions sound
on the merits (no BLOCK): all three R-S1 pillars hold against source, and a
sketched *partial* (elicit+consent) unification is net-negative. One actionable
caught: the R-P3 invariant was guarded only by a comment — no test exercised the
real two-entry path (`pending-effects.test.ts` mocked `applyFn`), the repo's own
`audit-without-test` pattern. **Resolved in-slice**: added
`R-P3: real-applyEffect accept yields [user dispatch, agent marker] → digest
counts once` (`pending-effects.test.ts`), which drives the real `applyEffect`
and asserts both the `['user','agent']` originator sequence and
`summarizeAgentActivity(0).total === 1`. A future "dedup fix" now fails loudly.

---

## S11 — Final gates + honest end-to-end score — 2026-05-25

### Gates (all green)
- **Java compilation** (pre-merge gate): `./gradlew.bat build -x test` →
  **BUILD SUCCESSFUL** (spotless + assemble + web build included). No Java was
  changed this run — S2 found the backend trust lattice / undo / job model
  already complete, so the work was the FE↔backend seam, not new backend code.
- **FE typecheck**: `tsc --noEmit` clean.
- **FE unit suite**: vitest **1989 passed / 1989** (195 files), up from the
  pre-run baseline by the tests added across S2–S10.
- **Working tree**: clean on `worktree-543-forward`; 19 commits ahead of
  `origin/main`. NOT merged to main (per the chosen handback mode).

### Per-slice review + live-proof status
| Slice | Item(s) | Review verdict | Live proof |
|---|---|---|---|
| S1 | R-P4/P2/R-S2/P5 polish | APPROVE-W-FOLLOWUPS (resolved) | n/a (foundation) |
| S2 | agent trust bridge | (closure) | ✅ `ss_8344tbadp` + 428/200 wire |
| S3 | R-E3 causation + R-E2 confidence | (review) | ✅ `ss_4940mnfx6`, `ss_2325r5990` |
| S4 | U3 digest | (follow-up tests) | ✅ `ss_2608s9heq` |
| S5 | U2 undo-the-AI | (follow-up test) | ✅ undo POST→200 (synthetic execId) |
| S6 | U1 autonomy dial | (follow-up test) | ✅ `ss_7553kwbdc` |
| S7 | R-E1 async task | APPROVE-W-FOLLOWUPS (resolved) | ✅ `ss_2974dd6jy` + task→gate |
| S8 | R-E5 dry-run + U4 macros | APPROVE-W-FOLLOWUPS (resolved) | ✅ boot-regression (no new surface) |
| S9 | U5 unified panel | APPROVE | ✅ `ss_0780u2vv3` |
| S10 | R-S1 + R-P3 | APPROVE-W-FOLLOWUPS (resolved) | n/a (verify→skip, no new surface) |

Every slice independently reviewed (implementer ≠ reviewer); **no open BLOCK**;
every follow-up resolved in-slice.

### Catalog coverage (honest)
Every §32 catalog item is **addressed** — implemented or explicitly resolved:
- **Implemented + proven**: U1–U5 (5/5); R-E1, R-E2, R-E3, R-E5 (4/4; R-E4
  AG-UI was excluded earlier in §32.9 as a category mismatch); R-P1, R-P2,
  R-P4, R-P5; R-S2.
- **Verified-intentional (no change correct)**: R-P3 (the accept double-entry
  is load-bearing for the U3 digest; now pinned by a regression test).
- **Declined with reviewer-approved rationale**: R-S1 (the three substrates are
  genuinely not the same shape; unification would be a leaky abstraction —
  consistent with §31.2/W11).

### Honest confidence by verification tier
- **Compile + unit tests** — HIGH. 1989 green; many added this run, each
  asserting the specific behavior (not `assertNotNull`-style).
- **Independent static review** — HIGH. 10 review docs, adversarial on the S10
  skip, no BLOCK.
- **Live-stack** — MIXED, stated honestly:
  - **HTTP/wire proofs are genuinely end-to-end**: real Shell listener → real
    backend (S2 428-vs-200 by transport; S5 undo POST→200; S7 agent-op → task
    → trust gate). These exercise the actual production path.
  - **Component-RENDER proofs are "real component fed real state directly"**,
    not full user click-through. This is a *harness* limitation: Vite dev
    serves the app and a console `import()` as separate module instances, so
    substrate state set via console does not reach mounted components. Logic is
    covered by unit tests + confirmed live through the real module; rendering is
    covered by feeding the mounted component realistic state. Not claimed as
    click-through E2E.

### Remaining work — honest reclassification (post-handback source audit)

The first cut of this section labelled all three "deferred / additive."
A follow-up source audit (prompted by the user asking *why* each was deferred)
found two of the three labels were wrong — in opposite directions. Corrected,
with citations:

**1. Backend indexing-jobs stream → Task tray — BUILT (read-only ambient bridge).**
   An extension I proposed in the S7 note (never a §32 catalog item). The user
   chose the *ambient read-only* scope: live indexing jobs surface in the Task
   tray for glanceable awareness; cancel/retry stay on the `core.indexing-jobs`
   Resource view (which owns CANCEL_OP/RETRY_OP) — the tray does not duplicate
   that control. See the "#1 live proof" section below.

**2. `assist` vs `auto` autonomy modes — RESOLVED (commit `0d646121d`).**
   The two were FE-identical (both dispatched). Now genuinely distinct via an
   effect-kind-keyed disposition (`agentInvocationDisposition(effectKind, level)`):
   `watch` proposes every agent effect; `assist` proposes agent backend
   operations (`invoke-operation`) for one-click approval while dispatching
   pure-FE effects (navigate/toast); `auto` dispatches all (backend lattice is
   the sole gate). The originally-proposed 428/confirmation-token flow proved
   unnecessary — an accepted proposal re-dispatches via `applyEffect`'s default
   `'user'` originator → BUTTON transport, so the backend auto-runs LOW/MEDIUM
   and STILL typed-confirms HIGH/destructive (BUTTON×HIGH = TYPED_CONFIRM). The
   FE never classifies risk (backend stays the authority, §32.9). See the
   "#2 live proof" section below; observations.md entry closed.

**3. Production undo executionId — WIRED end-to-end; only a LIVE ROUND-TRIP
   PROOF is missing (NOT a code gap).**
   Earlier I implied production undo might fire against a wrong/empty id. Source
   says otherwise — the path is complete:
   - `OperationExecutorImpl.java:253-254` returns `result.executionId()` for
     `outcome == SUCCESS && op.policy().undoSupported()`; `FileOperationsHandler`
     sets it "for undo correlation"; `AgentToolsOperationCatalog` ops are
     `undoSupported=true`.
   - `OperationClient.invoke` surfaces `executionId` (`OperationClient.ts:193`).
   - **My Shell listener captures the REAL id**: `Shell.ts:860-865` —
     `if (result.executionId && detail.journalEntryId !== undefined)
     markUndoableOperation(journalEntryId, operationId, result.executionId)`.
   - `EffectAuditLog` Undo → `jf-undo-operation {operationId, executionId}` →
     `OperationClient.undo` → `POST /api/operations/{id}/undo` (which **requires
     a non-blank executionId**, `OperationsController.java:219-224`).

   So the production code threads a real captured id. S5's synthetic id was a
   shortcut in the *proof* (I dispatched the undo half directly).

   **Live-proof investigation (2026-05-25): the round-trip cannot be run as a
   safe smoke test.** The ONLY `undoSupported` operation in the catalog is
   `core.file-operations` — `AgentToolsOperationCatalog.java:38-39`:
   `DESTRUCTIVE → HIGH, undoSupported=true`. No non-destructive op is undoable
   (read-only/LOW ops have nothing to undo). So obtaining a *real* `executionId`
   requires performing a genuine, destructive filesystem mutation behind a
   TYPED_CONFIRM gate. I declined to run a destructive op in the shared dev
   stack purely for a proof. This is itself a finding: **the "Undo" affordance
   only ever appears for destructive file operations today** (a UX implication
   worth noting). The production wire is source-verified complete; a live
   round-trip belongs in a deliberate scratch-env destructive-op test, not a
   casual smoke.

Net: #1 is a true extension (see below); **#2 is DONE** (implemented + live-
proven); **#3 is done-in-code, source-verified, with its live round-trip
gated behind a destructive op that shouldn't run casually.**

---

## §32 #2 — assist/auto distinction — 2026-05-25 — PASS (live)

Implemented (commit `0d646121d`) + verified across all tiers:
- **Unit**: `autonomy.test.ts` disposition table (effect-kind × level).
- **Integration (real substrates)**: `autonomy-gate.test.ts` — assist+backend-op
  → proposed; auto+backend-op → dispatched; assist+toast → dispatched;
  watch+backend-op → proposed. Drives the real `invokeAndApply` → real
  `proposeEffect`/`recordEffect`.
- **Live (real compiled module in the browser runtime, :5174)**: the new
  disposition function returns the exact designed table —

  | level / effect | disposition |
  |---|---|
  | watch / invoke-operation | propose |
  | watch / toast | propose |
  | **assist / invoke-operation** | **propose** |
  | assist / toast | dispatch |
  | assist / navigate | dispatch |
  | auto / invoke-operation | dispatch |
  | auto / toast | dispatch |

  And the real `<jf-autonomy-dial>` renders the now-distinct, honest copy
  (segment titles): Watch = "Every agent action waits in the queue for your
  approval."; **Assist = "Agent operates the UI freely; backend actions wait
  for your one-click approval."**; Auto = "Agent acts freely; the backend still
  confirms destructive actions."

Harness note (same as S3/S4/S6): the Vite dev server serves a console
`import()` as a *distinct module instance* from the app's mounted components —
and `invokeAndApply`'s internal `proposeEffect`/autonomy bindings resolve to yet
another instance — so an end-to-end "click dial → agent op → queued" browser
proof is not reliable. The disposition logic (a pure function) and the dial
render are single-instance and proven live above; the gate *wiring* is proven
by the real-substrate integration test. No screenshot — the proof is the
extracted disposition table + segment copy (a screenshot would show whichever
level the app instance happens to hold).

## §32 #1 — indexing-jobs → Task tray (read-only) — 2026-05-25 — PASS (live, end-to-end)

A new bridge (`substrates/tasks/indexingJobsBridge.ts`) subscribes to the
backend `core.indexing-jobs` SSE stream and projects live jobs into the Task
substrate; the Shell boots it in `connectedCallback` / tears it down in
`disconnectedCallback`. READ-ONLY (the user's chosen scope): job-tasks carry no
cancel fn (`cancellable:false`); cancel/retry stay on the Resource view. Reuses
the canonical `tabularStrategy` reducer (now exported) for frame parsing; the
bridge owns only the items→Task projection.

- **Unit (7)**: `indexingJobsBridge.test.ts` — projection lifecycle (present
  non-FAILED→running; FAILED→failed; departed→succeeded; cleared-FAILED stays
  failed via the terminal guard; running→FAILED no duplicate) + a full
  SSE-frame → reducer → projection chain via a fake EventSource + the headless
  no-op guard.
- **Live wire (real backend SSE, browser, :5174)**: opened
  `/api/indexing-jobs/stream`; the real endpoint delivers a `connected`
  lifecycle frame then a snapshot frame `{frameKind:'LIFECYCLE',
  payload:{kind:'snapshot', items:[…], snapshotSeq}}` — *exactly* the shape the
  `tabularStrategy` reducer consumes (verified `payload.kind === 'snapshot'`).
- **Live end-to-end (the app's OWN bridge → app's Task substrate → app's
  rendered tray)**: 5 real PENDING jobs (collection `justsearch-help`) were
  in-flight; the app's mounted `<jf-task-list>` (inside Shell's shadow root)
  rendered exactly 5 **RUNNING** rows: "Indexing · justsearch-help (4e2335 /
  2f5554 / e4c3e5 / 4982dd / 0e5ae0)" — pathHash prefixes matching the snapshot.
  Screenshot: `ss_7253zgatn`. This is NOT subject to the Vite dual-instance
  limitation — the bridge, substrate, and component are all the app's own
  instance; the chain is genuinely end-to-end.

Independent review: APPROVE, no follow-ups (`543-fwd-followup-1-review.md`) —
projection correctness, read-only invariant, lifecycle/no-leak, reducer reuse,
and lifecycle test coverage all verified against source.

Net (all three remaining items closed): **#1 BUILT** (ambient read-only,
live-proven end-to-end); **#2 DONE** (assist/auto distinction, live-proven +
reviewed); **#3 source-verified complete in code**, its live round-trip gated
behind the only undoable op (destructive `core.file-operations`), not run
casually.

## Deep-reflection findings (post-completion source audit)

Applying this session's own meta-lesson (post-hoc claims are hypotheses until
source-verified — my first "deferred/additive" classifications were both wrong),
I re-examined the two follow-ups that shipped behavioral code for claims I had
*asserted* but not traced. Findings:

**A. #2 dial scope — VERIFIED, and more precise than "gates backend ops".**
   I had not traced whether *real* AI-emitted operations reach the autonomy
   gate or bypass it (a classic wrong-gate risk). Traced to source: the real
   agent path is `AgentLoopService` → `tool_call_virtual` SSE →
   `VirtualToolDispatcher.dispatchVirtualToolCall` (`:61`) →
   `invokeCommandWithResult({originator:'agent'})` →
   `invokeAndApply(actionId, {}, null, undefined, 'agent')`
   (`CommandRegistry.ts:158`). So the gate **does** fire for real AI ops.
   CORRECTION (later re-investigation, §2A refutation): an earlier draft of this
   note claimed the gate covers "only FE-mediated invocations, not the agent's
   backend operations." That UNDERSTATED it. `projectOperationsToActions`
   (`actions/index.ts:742`) projects EVERY backend operation to an FE Action
   returning `{kind:'invoke-operation'}`, and agent tools resolve to those — so
   the gate DOES cover the agent's real backend ops (search / ingest / file-ops),
   not just navigation. The only ops that bypass it are any executed purely
   server-side by the agent loop; the backend trust lattice (AGENT_LOOP →
   UNTRUSTED) remains the universal backstop in all cases. The autonomy module
   header now states this corrected scope.

**B. #2 honest limitation — assist proposes ALL FE-mediated backend ops, not
   just risky ones.** The gate keys on `effect.kind === 'invoke-operation'`,
   not op risk, so in `assist` a harmless read-only FE-mediated op is proposed
   too. This was a deliberate §32.9-aligned choice (the FE does not classify
   risk). A lighter-touch refinement exists and is NOT done: read the op's risk
   from `UIOperationEmitter` metadata (already on the wire) to auto-run LOW
   reads and propose only MEDIUM+. Recorded as a future option, not a defect —
   the current behavior is safe and the more-conservative direction.

**C. #1 honest semantic — a departed PENDING job maps to `succeeded`.** The
   stream gives no completed-vs-cancelled-vs-cleared signal when a row leaves
   the live set, so the bridge optimistically completes it as `succeeded`
   (the reviewer agreed this is a defensible default for an *observational*
   tray). A refinement could map a terminal SUCCESS `state` precisely if the
   worker vocabulary has one (only `PENDING`/`FAILED` were confirmed here).

**D. #1 minor — duplicate SSE connections.** The bridge and the
   `core.indexing-jobs` Resource view (when open) each open their own
   EventSource to the same endpoint. `EnvelopeStreamPool` could dedupe by URL.
   Wasteful, not incorrect; not done (the bridge is Shell-lifetime, the view is
   on-demand — coupling them was not worth it for V1).

**Meta-lesson (reinforced — twice):** "what's left / what this does"
classifications made from memory are hypotheses. Both my original deferral
labels were wrong; then my *correction* over-shot (the "FE-mediated only, not
backend ops" scope note was itself wrong — a later source trace via
`projectOperationsToActions` showed the gate covers real backend ops). Even a
correction is a hypothesis until traced. The verify-before-claiming discipline
is the through-line of the whole follow-up arc — and it had to be applied to my
own corrections, not just my original claims.

## Live verification of the critical-analysis fixes — 2026-05-25

Three live checks were attempted against the running dev stack (:5174 + backend
:55393, GPU LLM active). Outcomes, stated honestly:

**#1 (bridge reset/remount fix) — steady-state CONFIRMED live; edge unit-only.**
The FIXED bridge correctly mirrors the live set: with 5 real PENDING
`justsearch-help` jobs in flight, the app's `<jf-task-list>` shows exactly 5
running rows (cross-checked against a direct SSE snapshot = 5 items). The
reset/remount EDGE behavior is unit-proven (reset-cycle, remount-reconcile,
in-place revival), but a full-stack forced reset wasn't run — this backend sends
`connected`+`snapshot` on reconnect (not a bare `reset` lifecycle), so the 1A
reset path has low real-world reachability and isn't readily forceable without
invasive Shell manipulation. Steady-state + unit coverage is the honest tier.

**#17 (real-agent gate proof) — BLOCKED by the dev build (not by code).** Both
paths to a real agent failed: (a) MCP `justsearch_dev_agent_chat` → HTTP 404,
it POSTs to the removed path `/api/agent/run/stream` (real route is
`/api/chat/agent`, AgentRoutes.java:24); (b) the in-browser agent surface
(`core.agent-surface`) errors `[streamViaHost] host_ must be wired for
shapeId='core.agent-run'` — a mount-path regression (tempdoc 521 §11.4/§22 E)
where the surface's `mountSurface()` doesn't forward `host_`. Both logged to
observations.md. The autonomy gate therefore remains verified at the source +
integration tiers only; the live-LLM tier is unreachable in this build.

**#18 (#2B typed-confirm) — a real GAP surfaced; #2B is PARTIAL.** A zero-risk
check of the production precondition (does the FE op catalog classify the HIGH
ops?) revealed:
- `/api/registry/operations` (29 ops) DOES classify the UI HIGH ops
  (`bulk-reindex`, `restart-worker`, `rebuild-index`, `apply-excludes`) — so
  #2B's typed-confirm fires correctly for those.
- BUT the **agent-tools ops** (`core.file-operations`, `core.search-index`,
  `core.ingest-files`, `core.browse-folders`) are **NOT in the FE registry** —
  they live behind a separate `/api/chat/agent/tools` endpoint. So
  `getOperation('core.file-operations')` returns undefined →
  `needsTypedConfirm` is false → for an agent-proposed HIGH agent-tool, #2B's
  typed-confirm does NOT fire, and accept would re-dispatch → BUTTON×HIGH →
  428 → the dead-end PERSISTS for exactly the destructive-agent case #2B
  targeted.

  This raised the question: do agent-tools-only ops actually reach the FE gate
  as `invoke-operation` effects? **RESOLVED (source-certain) — they do NOT.**

### Routing RESOLVED — agent core ops are server-side (AgentLoopService:1142,1261)

A follow-up source trace settled the question this section left open (and
re-corrects the §2A "refutation", which over-generalized). `AgentLoopService`
routes each tool-call by name:

```java
if (call.toolName().startsWith("vop_")) { handleVirtualToolCall(...); continue; }  // → FE
...
OperationResult toolResult = executeOperationWithPolicy(op, call);                  // → server-side
```

So `vop_`-prefixed (FE-virtual) tool-calls go to the FE (`VirtualToolDispatcher`
→ `invokeAndApply(...,'agent')` → the FE gate + #2B); **all core agent ops
(`core_file_operations`, `core_search_index`, …) execute SERVER-SIDE** via
`executeOperationWithPolicy` (OperationDispatcher → BackendIntentRouter → trust
lattice) and NEVER reach the FE. They have backend handlers
(`FileOperationsHandler`, etc.). The live agent-tools list confirms the current
tools are all `core_*` (no `vop_`).

Consequences:
- **The "#2B agent-tools-risk gap" is NOT a live dead-end.** Agent
  `file-operations` runs server-side, never queued on the FE; the backend
  lattice gates it (AGENT_LOOP → UNTRUSTED × HIGH → TYPED_CONFIRM/DENY). #2B's
  `getOperation` lookup is correct for what DOES reach `invokeAndApply`
  (user-invoked Actions = registry ops; agent `vop_` ops).
- **`projectOperationsToActions` projects registry ops to USER/palette Actions**
  — NOT to the agent's tool execution. The §2A conclusion conflated the two.
- **Residual (forward, currently vacuous):** if a `vop_` agent op were HIGH,
  #2B's registry lookup wouldn't classify it (`VirtualOperation` carries no risk
  field). The correct forward fix — thread risk into the proposed effect (so
  `needsTypedConfirm` reads `effect.risk`) — has no current producer to test
  against (no `vop_` ops exist), so it is deferred, not built.
- **Honest scope of the whole dial (#2/U1):** it gates the FE-virtual (`vop_`)
  agent path, which has NO producers today. So the dial gates no current real
  agent traffic — it is forward-looking infra. `watch/assist/auto` are distinct
  in code + tests but fire only once a `vop_` agent tool (or an explicit
  `proposeEffect`'d agent invocation) exists. This is a pre-existing U1 scope
  reality, now source-confirmed — not introduced by these fixes.

Net of the live pass: #1 fix holds (steady-state live + unit edge); the #2B
"gap" resolves to **not a live dead-end** (agent core ops server-side); #2B is
correct for its path; the only open items are doc accuracy (done here) and the
two dev/build bugs (stale MCP endpoint, agent-surface mount regression).

### Routing now LIVE-CONFIRMED (after fixing the agent-surface regression)

The agent-surface `host_` mount regression was then ROOT-CAUSED and FIXED (the
"Slice 495" wrapper `AgentSurface` didn't forward `host_` to `<jf-agent-view>`;
view-factory.ts:90-92 sets it on the surface, the wrapper dropped it). With
agent chat restored, the routing trace above was confirmed LIVE: a real prompt →
the agent emitted a `core_ingest_files` (MEDIUM) tool-call shown in the AGENT
SURFACE's OWN in-chat Approve/Reject UI (`MEDIUM · PENDING`), and the FE
observers showed `feInvokeDispatches: []` + `feQueueCards: 0` — the core op did
NOT touch the FE autonomy dial / PendingEffectQueue / #2B. Source + live now
agree: the agent's core ops are server-side, gated by the agent surface's own
approval flow; the FE dial gates only the (producer-less) `vop_` path.
Screenshots: `ss_758019o40` (tool-call in the agent's approval UI),
`ss_5311dgumc` (session cancelled, no side effect). Fix:
`AgentSurface.ts` (declare + forward `host_`); AgentView tests green.

## Arc synthesis — the central architectural finding (deep reflection)

Stepping back over the whole §32 arc (forward-research catalog → implementation
→ three follow-ups → critical-analysis fixes → live verification): the work
succeeded on every tier it could be measured by — compile, unit, integration
(real substrates), independent static review, and the reachable live tiers. Yet
disciplined end-to-end verification surfaced what none of those tiers could:
**the autonomy dial (U1) — the centerpiece of the §32 oversight UX — sits on a
runtime path the real agent does not use.** The agent's actual tool-calls are
CORE operations executed server-side (`AgentLoopService.java:1142,1261` → trust
lattice), gated by the agent surface's OWN in-chat Approve/Reject UI. The FE dial
gates only the FE-virtual (`vop_`) path, which has no producers. So the dial —
correct, tested, and live-rendered — controls no real agent behavior. It is
substrate ahead of its consumer (now pinned as a user-decision question in
`the retired 421 FE-rewrite draft slices/486-consumer-feature-discovery.md`
R2.3 #6).

The meta-lesson at the architecture level: **a feature can be "complete and
green" — compiling, unit/integration-tested, statically reviewed — and still be
wired to nothing real.** The lower tiers verified the dial's logic in isolation
(with a synthetic `originator='agent'`); they could not reveal that no real
producer drives that originator. Only tracing the decisive routing
(`AgentLoopService:1142`) AND observing it live (a real agent op gated by the
agent surface's own UI; `feInvokeDispatches=[]`) settled it — which is why the
scope flipped three times: each flip was a confident conclusion from partial
tracing. The arc's through-line — verify-before-claiming, `static-green ≠
live-working` — held at every scale, including my own corrections.

The scrutiny also paid for itself in concrete repairs: looking closely enough to
answer "does the gate fire for real agents?" surfaced and fixed two genuine bugs
(the #1 bridge reset/remount state-machine; the agent-surface `host_` mount
regression that had broken ALL agentic chat) plus a dev-tooling bug (the stale
MCP endpoint) — value that came from the scrutiny, not from the feature itself.

Net: §32's substrate is sound and forward-ready; its central consumer question
is now an explicit, pinned user-decision (486 R2.3 #6).

## §32 unify — the dial now drives the agent's real auto-approval (RESOLVED)

The 486 R2.3 #6 decision was taken (user-chosen): **unify the dial with the
agent surface's own approval flow** rather than the dead `vop_` path. The dial
level now drives per-tool auto-approval via a new `agentToolAutoApprove(risk,
level)` policy (`autonomy/index.ts`): `watch`→approve nothing; `assist`→LOW;
`auto`→LOW+MEDIUM; **HIGH/destructive NEVER auto-approved at any level** (safety
floor, the first guard). `AgentSessionController` calls it at tool-call arrival
(reading the live level), replacing the old binary `autoApproveLowRisk` flag;
`AgentView`'s "Auto-approve low-risk" checkbox is replaced by a compact
`<jf-autonomy-dial>` in the header (shared state with the Settings dial). This
gives the previously-dormant dial a **real, live consumer** — resolving the
substrate-ahead-of-consumer finding.

- **Verified:** typecheck clean; vitest 2012 green incl. the policy table
  (`autonomy.test.ts`) and the controller auto-approve matrix
  (`AgentSessionController.test.ts`: assist+LOW→approve, assist+MED/HIGH→manual,
  auto+MED→approve, auto+HIGH→manual, watch+LOW→manual — driven by the REAL
  autonomy substrate, not a mock); gradle `build -x test` exit 0.
- **Live (UI):** the compact dial renders in the agent header (Watch/Assist/Auto,
  active segment reflects the substrate; the old checkbox is gone). Screenshot
  `ss_47364u8en`.
- **Live (behavioral) — DONE (end-to-end, real LLM):** after restarting the dev
  stack (new backend `:52802`) + re-activating inference, a fresh worktree Vite
  (`:5176` → `:52802`, serving the committed code) ran the same prompt at two
  dial levels:
  - `watch` → the agent's tool-call stayed `MEDIUM · PENDING`; the fetch hook
    recorded `approveCalls = []` (NO auto-approval — manual). Screenshot
    `ss_9108f6oc8`.
  - `auto` → the `core.search-index` (LOW) tool-call AUTO-approved and ran to
    `LOW · COMPLETED`; the hook recorded a real
    `POST /api/chat/agent/approve`. Screenshot `ss_0796tricf`.
  Same prompt, only the dial changed → the dial demonstrably drives the agent's
  real per-tool auto-approval. (Dev-env note: the dev-runner serves the MAIN
  checkout FE, so a separate worktree Vite was needed to exercise the committed
  code; the agent's search handler errored on execution in the fresh stack — an
  unrelated tool-registration issue — but the AUTO-APPROVAL itself fired, which
  is what this proves.)
- **Decision recorded — default-behavior shift (intentional):** the old checkbox
  defaulted OFF (auto-approve nothing); the dial defaults to `assist`
  (auto-approve LOW). So read-only agent tools (search/browse) auto-approve by
  default now — the "balanced" intent of `assist`; the backend lattice still
  gates writes/destructive, and HIGH is always manual. The orphaned
  `jf-agent-auto-approve` localStorage key is intentionally abandoned (no
  migration — the dial default ≈ the old "auto-approve low ON" behavior).
- Independent review: APPROVE-WITH-FOLLOWUPS (`543-fwd-followup-4-review.md`) —
  safety floor verified; the one actionable follow-up (document the default
  shift) is this entry.

### Handback
Branch `worktree-543-forward` is reviewed and merge-ready (compiles, all unit
tests green) but **left unmerged** for the user to review, per the chosen mode.
The "#2B agent-tools-risk gap" was investigated and **resolved as NOT a live
dead-end** (agent core ops execute server-side — source- AND live-confirmed).
The **dial-consumer question (486 R2.3 #6) is RESOLVED** — the dial now drives
the agent's real auto-approval (the §32-unify section above), reviewed APPROVE.
Three real bugs found+**FIXED** via the verification: the agent-surface `host_`
mount regression, the #1 bridge reset/remount state-machine, and the stale
dev-MCP `agent_chat` endpoint. The unify is now also **live-proven end-to-end** (watch→manual, auto→auto-approved
the same prompt's LOW tool — `ss_9108f6oc8` / `ss_0796tricf`). The only
genuinely-open item is a forward, currently-vacuous edge (risk-classifying a
HIGH `vop_` agent op), deferred for lack of any producer.

## Post-merge coda — MERGED + indirect-changes follow-through (2026-05-26)

Supersedes the "left unmerged" note above. The branch was reviewed and **merged
to `main` (fast-forward, pushed)**, then a short post-merge indirect-changes pass
landed (each fix negative-control-verified; full ui-web suite green, gradle
`build -x test` clean):

- **531 consumer-drift gate — first production slots** (`chore(531)`). Registered
  the §32 autonomy-dial substrate as `gates/consumer-drift/slots.json` slots
  (`agentToolAutoApprove` floor 1; `jf-autonomy-dial` floor 2). This *mechanizes*
  the substrate-ahead-of-consumer lesson the whole arc surfaced (486 R2.3 #6): the
  gate now fails loudly if the dial ever drifts back to dormancy. Gate teeth
  verified (fails at an inflated floor reporting the real count). Deliberately did
  NOT register `agentInvocationDisposition` — its only consumer drives the
  producer-less `vop_` path, a hollow the 531 `$comment` warns against seeding.
- **Undo correctness — rejected-skip across all three undo paths** (`fix(543)` ×2).
  Critical analysis found the observation's bug was broader than logged: not only
  `undoLast/AllByOriginator` but the **global `undoLastEffect`** would apply the
  inverse of a vetoed (`pendingOutcome:'rejected'`) proposal that was never
  dispatched. Then a *distinct* bug surfaced: the global undo **double-undid an
  accepted** pending (the markerless dispatch + the `accepted` marker both carry
  inverses) — fixed by skipping *all* pending-lifecycle markers in the global
  path (the byOriginator paths stay `rejected`-only, since the `accepted` agent
  marker is their sole originator-attributed undoable). Asymmetry documented;
  three regression tests, each negative-control-verified.
- **Test hygiene** (`test(543)`). Eliminated 3 unhandled rejections in
  `AgentSurface.test.ts` (a `fetch` stub guarded by a condition happy-dom never
  satisfies → live ECONNREFUSED; an incomplete host mock). Suite now reports 0 errors.

### Future directions (research)
A research-only ideation pass over the now-shipped substrate — polish / simplify
/ extend / new-UX ideas grounded in external patterns (local-first time-travel,
agentic human-in-the-loop, undo/redo design, teach-mode/provenance) and
source-verified against our code — lives in **`543-fwd-future-directions.md`**.
Headline: the biggest wins *complete primitives already ~90% built* — redo (the
inverse field exists; no redo does), deterministic "because"/confidence legibility,
surfacing the journal as time-travel + causation-trace UI, and closing the
teach-mode loop ("turn what the agent just did into a parameterized macro").

---

## UPDATE 3 — follow-on ideas live-verified (worktree-543-fwd-ideas, 2026-05-26)

Worktree FE served on http://localhost:5174 (Vite, `VITE_JUSTSEARCH_API_PORT`
pinned to the taken-over dev-stack backend :61243), real Qwen3.5 LLM active.

### #2 because-line — LIVE PASS (real agent, mounted UI)
Dial set to **Watch**; prompted *"Search the knowledge base for budget"*. The
real agent's tool-call rendered a **MEDIUM · PENDING** `ToolCallCard`; the
mounted card showed my derived because-line **"Watch mode — every action needs
your confirmation."** (read from the live shadow DOM,
`[data-testid="tool-call-because"]`). This is FE-derived from `risk`+dial level
(U3: no reason on the wire) and is the strongest real-agent→mounted-UI proof of
this slice.

### Idea #0 bridge — RE-CONFIRMED LIVE
Dial **Auto**, same query: the agent's search tool-call **executed
successfully** (answer cited real files at score 0.87 — no "No handler
registered"), and the mounted "what the AI did" digest showed **"Since you last
looked, the assistant: 1 invoke-operation"** + "Undo all AI actions". The
substrate the follow-on ideas read is live and real.

### #4 undo-label / #8 mass-undo-confirm — LIVE (affordance fires; rich path is unit-tier)
Clicking "Undo all AI actions" against the real agent entry fired **"Nothing to
undo"** (no confirm panel) — the CORRECT path: the agent's only journal entry is
an `invoke-operation`, which is non-reversible at the Effect-inverse layer
(backend-undo is the separate executionId bridge). The labelled toast / confirm
preview branches require FE-*reversible* `agent` entries, which the real agent
never emits (it has no reversible tools — there are no `vop_` ops). Those
branches are unit-verified (describe.test, EffectAuditLog.test, AiActivityDigest
.test). The live affordances were confirmed firing on real agent data.

### #1 redo — mechanism unit-proven; triggers LIVE; a defect found + fixed live
Global **Ctrl+Z** (undo) live-PASS: reverted the active surface + fired an
"Undone" toast. Global **Ctrl+Shift+Z** (redo) trigger fires live. Live testing
SURFACED A DEFECT: a `navigate` inverse dispatched during undo re-journals
through NavigationJournal (router → recordEffect, async), resetting the cursor →
"Nothing to redo". Root-caused + fixed (`5da6265c5`: replay-suppression flag,
`#`-normalized) + regression-tested. FURTHER live finding: navigate is an
imperfect fit for the Effect-cursor regardless (surfaces append `?q=` → secondary
navigations; the router canonicalizes URLs) — navigation owns its own history
(NavigationJournal ring + browser back/forward). The redo MECHANISM is sound and
unit-proven for all reversible kinds (round-trip, truncate-on-append, marker/
irreversible skip, suppression); navigate-via-cursor is best-effort (logged for
follow-up in observations.md).

### #12 macro dry-run diff — unit-proven; live demo blocked by elicit-form authoring
The panel + backend-replay guard + palette routing are unit-verified
(macros.test, MacroDryRun.test). The live demo requires authoring a macro that
contains a backend op via the audit-log "Save selected as macro" flow, whose
**elicit name-form did not expose a reachable text control** in the mounted DOM
(the jsonforms label input was absent from `jf-elicit-host`'s shadow and the a11y
tree) — a separate elicit-substrate rendering issue, not the macro-diff code.
Macro-diff therefore stands at the code+unit tier with the panel/guard/routing
proven by tests.

### Verdict
Real-agent→mounted-UI proven for the agent-facing core (#0 re-confirm, #2 PASS).
The deterministic FE ideas are unit-complete with their substrate (#0) live and
their affordances live-confirmed; rich branches that need reversible agent
entries / macro authoring are unit-tier with specific, named live blockers.

---

## UPDATE 4 — remaining ideas P1-P6 (worktree-543-fwd-remaining, 2026-05-26)

Six follow-on priorities, each code-complete + unit-tested + committed (full FE
suite 2128 green). Live pass: worktree FE on :5174 pinned to the dev-stack
backend :55464, real Qwen3.5, against the MOUNTED FE.

### Live-verified (real agent / mounted UI)
- **Idea #0 bridge — RE-CONFIRMED.** A real agent turn executed tool-calls; the
  mounted journal shows a real `originator:'agent'` entry (#76
  `core_browse_folders`).
- **P1/P2 affordances render on real agent data.** The "what the AI did" digest
  shows the new **"Save as macro"** button (P2) beside "Undo all AI actions";
  clicking it opens the author modal correctly scoped to the agent's actions
  (*"Save what the assistant just did (2 actions)"*) — the agent-entry
  COLLECTION works live.
- **P6 affordance renders.** Every audit-log entry shows the new **"↻ restore"**
  button (time-travel).

### Blocked from full live COMPLETION — named pre-existing blockers (not this work)
1. **Elicit forms render no input controls.** `<jf-elicit-host>` mounts
   `<jf-form>` but it renders an empty shadow (no `<input>`) for a schema-only
   request — even with a generated VerticalLayout uischema. A deeper
   renderer-registry / elicit-integration gap (`ElicitHost.ts:174` +
   `renderers/layouts/layoutDispatch.ts`). This blocks live COMPLETION of the
   macro author → **P2 #9 / P4 #10 / P5 #12** (which need a defined macro). The
   collection/tokenize/diff logic is unit-proven; only the substrate
   `resolveElicit()` path works (the §32 S8 "live" proof used it, never the
   rendered input). Logged to `observations.md`; my partial uischema fix reverted
   (out of scope + insufficient).
2. **Backend agent searches returned `success:false` this dev-stack run** — only
   1 of 4 tool-calls journaled (`core_browse_folders`), so no multi-entry
   **causation chain (P3 #6)** formed to display. Causation enrichment + chain
   are unit-proven (`AgentSessionController.test`: chains within a turn, resets
   across sessions).
3. **P1 compensating undo** needs a *mutating* agent op (`core_file_operations`,
   undoSupported → executionId) to populate a compensable entry. Driving a real
   destructive file-op on the live system is unsafe; the compensating wire
   (`undo-operation` → `POST /api/undo`) is live-proven (§32 S5) and the bridge
   chain (executionId → markUndoableOperation → bulk undo) is code + unit
   verified. No compensable entry existed in the live journal (reads carry no
   executionId), so the bulk-undo inclusion couldn't be shown live.
4. **P6 restore** over `navigate`-dominated history inherits the navigate
   re-journal flakiness (documented UPDATE-3); the confirm bar renders but
   navigate reversal fidelity is best-effort.

### Verdict
All six are code+unit complete (2128 green) with their affordances confirmed
rendering live on real agent data. Full live completion of the macro-author
ideas (#9/#10/#12) is gated by a pre-existing elicit-form renderer gap; the
undo/causation flows by backend/file-op realities. NONE marked
`status: implemented` — they sit at code+unit + affordance-render-live, with the
specific blockers named above (not generic deferrals).

---

## UPDATE 5 — de-risked remaining work, elicit fix LIVE-VERIFIED (worktree-543-fwd-remaining, 2026-05-26)

A de-risking pass (3 Explore agents) root-caused the elicit-form blocker and
confirmed the path; then three steps shipped (code+unit, FE suite 2140 green) and
the keystone was live-verified in the mounted worktree FE (:5174 → backend :50876).

### Elicit-form fix — LIVE PASS (the thing that was blocked)
Root cause: a schema-only elicit request passed `undefined` uischema → `jf-form`
matched no renderer → ZERO inputs. The registry was always fine (TextControl +
VerticalLayout work in SettingsLitView). Fix: `ElicitHost` generates a default
VerticalLayout uischema; also fixed `VerticalLayout` dropping `userConfig`.
- **Live, mounted FE:** selected a reversible journal entry → "Save selected as
  macro" → the elicit modal now renders **2 text inputs** (was 0): the `label`
  field AND a parameter-name field (P4 parameterize-on-save). Filled the label,
  submitted → modal closed + `selectedIds` cleared, i.e. `defineMacroFromEffects`
  returned a macro = **the teach-mode loop completes end-to-end live**. This was
  fully blocked in UPDATE 4. Decisively unit-tested too (ElicitHost.test.ts: a
  schema-only request renders a real `<input>`).

### Steps shipped
- **Step 1 — elicit fix** (`8aecc2b6e`): live PASS above; unblocks #9/#10/#12 + future #13/#15/#16.
- **#19 journal export / replay** (`4fdcc9517`): `exportJournalArchive` / `importJournalArchive` round-trip; audit-log "Export selected" (clipboard) + "Import archive" (replay as macro). Unit-tested round-trip + component flows; export button renders live.
- **#7 grouped/atomic undo by turn** (`040c5ad3e`): `getGroupRoot` / `previewUndoGroup` / `undoGroup`; per-entry "↶ undo turn" + confirm. Unit-tested (group-root, whole-turn reverse incl. compensable mutation, confirm→commit). Live needs an agent turn for causation data (deterministic + unit-proven; affordance renders).

### Net
The keystone uncertainty collapsed (small fix, working registry) and is live-proven;
the teach-mode loop now functions live. #19/#7 are code+unit complete with affordances
rendering. Deferred (named): true causal "why" + confidence chip (need backend/LLM
signals), true state-rewind/undo-tree (need a checkpointer). undo-the-AI's real
compensable surface is `core.file-operations` only (confirmed via the op catalog).

## UPDATE 6 — live browser-validation sweep (worktree-543-fwd-validate, 2026-05-26)

Drove the shipped 543-fwd features through the **mounted** worktree FE (Vite :5174 →
backend :60435, model active) in Chrome. Key harness discovery up front, then the
per-feature live results.

### Harness root-cause: stale modules from hash routing (fixed)
Every prior "live redo failure" was **stale code**: the SPA uses hash routing, so a
`navigate`/hash change NEVER re-fetches JS modules. The page kept running the module
graph from the original full page load, predating each FE edit. Confirmed via a
`globalThis.__dbg` probe in `recordEffect` that stayed empty after edits until a
**`location.reload()`**. Lesson: after any FE edit, a real `location.reload()` is
required — tab/hash navigation is not enough. (Also: dynamic `import('/src/...index.ts')`
from the console resolves to a *different* module instance than the mounted app — good
for calling pure functions, useless for reading live singleton state like the journal;
drive/read via the mounted DOM instead.)

### #1 redo — LIVE PASS (the long-standing blocker)
Invoked the real kernel action handlers (`invokeAndApply('core.action.shell.undo'|'redo')`
— exactly what `Ctrl+Z`/`Ctrl+Shift+Z` run) against the **real 111-entry journal**.
Cursor trace: undo `cur 105→104` + dispatched inverse; the handler's `{kind:'noop'}`
return journaled at `cur=104/112` **without resetting the cursor** (the fix); redo
`104→105→106`, re-applying each effect, noop preserved each time. The noop-cursor fix
holds end-to-end live. Regression test green (`effects.test.ts`, 58/58). The earlier
"redo broken live" verdicts were the stale-module artifact, not a logic bug.

### #5 time-travel restore — LIVE PASS (pure mounted DOM)
`restore-to-110` → `restore-confirm` bar listed 3 `restore-row` entries (human-labelled
"Navigate to …") → `restore-confirm-yes` → 3 inverses dispatched (journal 117→121),
confirm bar closed.

### #19 export / import — LIVE PASS
Export: selected 2 entries → `export-archive` → valid `{version,exportedAt,entries[2]}`
JSON to clipboard (487 chars). Import: `import-archive` → **elicit form renders 2
controls** from a schema-only request (the elicit fix) → filled `json`+`label` →
submit → macro defined, projected into the **command palette** as a runnable command
("CleanImport", "ImportedArchiveTest"). `importJournalArchive` round-trips 2 effects
(navigate, toast).

### #12 macro dry-run diff — LIVE PASS (rows + before→after diff)
`jf-open-macro-dry-run({macroId})` opened the panel with 2 `macro-dry-run-row` rows
rendering `describeChange` before→after ("… view → …", "Show message: Reverted 3
actions"). No backend-op warning (correct: this macro is navigate+toast). The
backend-op `macro-dry-run-warning` path needs an `invoke-operation` macro → covered in
the Group B agent sweep.

### Deferred to Group B (agent-driven, authentic data)
#4 undo-label, #8 mass-undo-confirm, idea#0 digest, #2 because-line, #6 causation
trace, #7 grouped undo, #9 agent→macro, #10 parameterize, P1 undo-the-AI — these need
real `originator:'agent'` journal entries from live agent turns to validate honestly.

### Harness limitations named (not feature defects)
- Keybindings don't fire through Chrome automation (synthetic + real `Ctrl+Z/K` reach
  window-capture but the dispatcher→`invokeCommand` path is async via a slow first
  dynamic import; validated the identical handler directly instead). The keybinding→
  command wiring is unit/integration covered; the substrate behavior is live-proven.
- Console dynamic imports are a separate module instance — measure via mounted DOM.

### Group B — agent-driven (live model, real turns through the mounted composer)
Drove real agent turns via the mounted composer (placeholder "Ask the agent to do
something…") with the autonomy dial. The model's tool choices varied (it picked
`core_ingest_files` / `core_browse_folders` over search), but that produced authentic
`originator:'agent'` journal entries — the point of the sweep.

- **#2 because-line — LIVE PASS.** Watch mode + a turn → pending `jf-tool-call-card`
  with `tool-call-because` rendering `becauseLine(tc.risk, getAutonomyLevel())` =
  "Watch mode — every action needs your confirmation." Deterministic gate explanation
  on a live agent-proposed pending call. (Denied the proposed `core_ingest_files`.)
- **idea#0 digest — LIVE PASS.** Auto-mode turns → `jf-ai-activity-digest` rendered
  "Since you last looked, the assistant: 4 invoke-operations, 1 toast, 4 open-modals,
  2 navigates" + `ai-digest-undo-all` / `ai-digest-save-macro` / `ai-digest-mark-seen`.
- **#6 causation trace — LIVE PASS.** The agent ran chained ops (entry 130
  `invoke-operation` with `causation:129`). Audit-log chip "↳ #129 why?" → expand →
  `trace-step-130` rows: `root Run core_browse_folders` → `#130 Run core_browse_folders`
  (bottom-up causal chain).
- **#8 mass-undo-confirm — LIVE PASS.** `undo-all-agent` → `undo-all-confirm` listing
  6 `undo-all-row` entries with readable labels ("Navigate to #agent-emitted-demo" ×2,
  "Open dialog \"demo.agent-confirmation\"" ×4).
- **#4 undo-label — LIVE PASS.** `undo-last-agent` → toast **"Undid: Navigate to
  #agent-emitted-demo"** (names the effect via `describeEffect`).
- **#9 agent→macro + #10 parameterize — LIVE PASS.** Digest `ai-digest-save-macro` →
  elicit form with `label` + `param_0..param_3`, the param titles derived from the
  agent's effect constants ("Parameter name for \"budget\"", etc.). Filled label +
  named `param_0` → submit → macro **"AgentMacroTest"** projected into the command
  palette. The teach-mode → parameterized-macro loop works end-to-end live.
- **#7 grouped undo — PARTIAL (precisely named).** Causation grouping is live-proven
  (the #6 trace; `undo-turn-130` renders only for causation-grouped entries, not for
  standalone 104). The handler fires correctly: `undo-turn-130` on the read-only browse
  turn toasted "Nothing reversible in this turn" (correct — `core_browse_folders` has
  null inverse). The reversal-with-rows path (populated `undo-turn-confirm` + reverse)
  stays unit-proven: the live model produced either reversible-but-ungrouped (demo
  navigates/open-modals) or grouped-but-irreversible (browse) entries, never a
  reversible *grouped* turn in one session. Both halves proven separately, live.

### Net (Group B)
Every digest/because/causation/mass-undo/undo-label/save-as-macro/parameterize surface
validated live against real agent turns + the live model. #7's two mechanisms each
proven live; their combination unit-proven (model-behavior gap, not a code gap).

### P1 undo-the-AI (compensating undo) — LIVE PASS (plumbing) + named smoke
The compensating-undo wire is the FE-side P1 mechanism: a compensable agent entry's
"Undo" affordance dispatches a document-level `jf-undo-operation` ({operationId,
executionId}); Shell's listener routes it to `OperationClient.undo` → `POST
/api/undo/{operationId}`. Dispatched that exact event live → observed a real **`POST
http://localhost:5174/api/undo/core.file-operations` → 200** (Vite-proxied to backend
:60435, through the mounted Shell handler). The FE→backend compensating-undo path and
the backend's acceptance are live-proven.

Named smoke (not run autonomously): the full agent-driven round trip — agent executes
`core_file_operations` MKDIR → HIGH-risk approval → entry carries the real
`executionId` → "Undo all AI actions" includes it → undo deletes the directory on
disk. This needs a real filesystem mutation; the only indexed root is in the **main**
checkout (`F:\JustSearch\docs\…`), so per branch-safety rule 6 (don't create files in
the main worktree) it's left as a user-driven smoke. The compensable-inclusion logic
(`previewUndoAllByOriginator` + `undoEffectFor` routing) is unit-proven; the live POST
above proves the dispatch end of the same wire.

## UPDATE 6 — verdict
Live-validated in the mounted browser against the live backend + model: #1 redo, #4
undo-label, #5 restore, #6 causation trace, #8 mass-undo-confirm, #9 agent→macro, #10
parameterize, #12 macro dry-run (rows+diff), #19 export+import, idea#0 digest, #2
because-line, the elicit-form fix, and the P1 compensating-undo POST. Precisely-named
residue: #7 grouped-undo reversal-with-rows (grouping + handler live, reversal unit-
proven — live model never produced a reversible *grouped* turn) and the P1 full MKDIR
round trip (user smoke; FE wire + backend accept live-proven). The recurring "redo
broken live" verdict was a harness artifact (hash routing never reloaded modules); the
noop-cursor fix is correct and now live-proven, with a regression test.

## UPDATE 7 — frontend robustness analysis (dimensions the feature sweep never touched)

UPDATE 6 answered one narrow question: "does each shipped 543-fwd feature's *happy
path* work when driven through the DOM?" This pass analyses the orthogonal dimensions
that question ignored — failure paths, persistence, lifecycle, untrusted input, the
gating matrix, visual rendering, and the genuinely-still-untested residue. Method: code
analysis for the statically-decidable claims + targeted live confirmation (dev stack
FE on :5174; backend was down for part of this, which is fine — these are FE/localStorage
concerns). Each item is tagged CLEAN (checked, no defect), CONFIRMED (live-verified), or
OPEN (still untested — named precisely).

### A. Checked and SOUND

- **Effect-label exhaustiveness — CLEAN.** `describeEffect`/`describeChange`
  (`substrates/effects/describe.ts`) are exhaustive `switch`es over the closed `Effect`
  union with a `const _exhaustive: never` default — adding an Effect kind without a label
  **fails the TS build**. Refutes the "unknown kind renders `undefined`/`[object Object]`"
  worry: a missing label is a compile error, not a runtime surprise.
- **Persistence — CONFIRMED live.** Three independent stores, all survive reload:
  - Journal `justsearch.effect-journal.v1`: **500-entry LRU cap** (`writePersisted` slices
    the last `MAX_PERSISTED_ENTRIES`), quota/serialization errors swallowed, restored at boot
    (`restoreJournalFromStorage()` wired at `Shell.ts:829`), `_undoCursor` reset to end on
    restore (redo is session-only **by design**). Live: localStorage held 133 entries; after
    `location.reload()` the mounted audit log rehydrated to 135 (133 + 2 boot navigates).
  - Macros `justsearch.macros.v1`: persisted; live: 6 macros in storage, "CleanImport"
    rehydrated into the command palette after reload.
  - Autonomy `justsearch.autonomy.level.v1`: validated against the level set, default
    `assist`; live value `"auto"` survived reload.
- **Listener lifecycle — CLEAN.** All five consumers symmetrically add/remove in
  `disconnectedCallback`: EffectAuditLog (journal sub + window keydown), AiActivityDigest
  (journal + pending subs), ElicitHost (`jf-elicit-request`), MacroDryRun
  (`jf-open-macro-dry-run` + keydown), Shell (`keydown` + `jf-undo-operation`). No leak
  across surface switches.
- **Untrusted-archive handling — CLEAN (one design note).** `importJournalArchive`
  validates `version === 1`, `Array.isArray(entries)`, and filters to entries whose
  `effect.kind` is a string; malformed input → `[]`. Labels render via Lit text-binding
  (`${describeEffect(...)}`), not `unsafeHTML`, so a hostile macro label / effect message
  is escaped (no XSS). Design note: `exportJournalArchive` serializes only
  `{effect, originator, invokedAt}` — **not `causation`** — so an imported archive is
  causation-flat (the replay-as-macro use case doesn't need it, but cross-session causal
  traces don't round-trip through export/import).
- **Autonomy gating matrix — CLEAN (live-consistent).** `agentToolAutoApprove(risk, level)`
  fully specifies the live agent tool-approval path: HIGH → never auto at any level (safety
  floor), watch → never, assist → LOW only, auto → LOW+MEDIUM. Consistent with the two
  cells observed live (Watch gated `core_ingest_files`; Auto auto-ran LOW `core_browse_folders`).
- **Failure path (malformed import) — CONFIRMED live.** Submitting `not valid json {{{`
  through the import elicit → graceful toast **"No valid entries in that archive"** (no
  crash, no partial macro).
- **Visual spot-check — CONFIRMED.** Screenshot of the open Effect Journal: clean drawer
  layout, originator chips (user/agent), filter selects, accurate "N/N" count, correct
  disabled states ("Save selected (0)" / "Export selected (0)" greyed until a selection),
  the agent row's "↳ #129 why?" causation chip + "undo turn"/"restore" buttons, and the
  import-success / malformed-import toasts all rendering as designed.

### B. Findings worth recording (nuance, not defect)

- **`becauseLine` has 5 deterministic variants** (HIGH-any, watch, auto, assist-LOW,
  assist-MEDIUM); only the `watch` string was seen live. The other four are
  straight-line deterministic code, unverified live.
- **Cross-surface state is origin-scoped — dev artifact.** localStorage is per
  (scheme,host,**port**). Dev serves unified-chat (:5173) and agent (:5174) as *separate
  origins*, so journal/macros/autonomy do **not** share across the two dev surfaces. In
  production both surfaces are hash-routes within one origin (single served port), so
  state is shared there. Consequence: the UPDATE 6 sweep on :5174 was origin-isolated; the
  unified-chat surface was never exercised.
- **The dial's `vop_` consumer gates no real traffic today.** Per the autonomy module
  header (source-verified), `agentInvocationDisposition` (the FE-virtual `vop_` path) has
  no published `vop_` tools, so it's forward-looking infrastructure; the *live* gate is
  `agentToolAutoApprove` via `AgentSessionController` (the in-chat Approve/Reject flow).

### C. Still OPEN (named, not silently skipped)

- **Real keyboard wiring end-to-end.** Ctrl+Shift+Z → KeybindingRegistry dispatcher →
  `invokeCommand` → handler was never fired live (Chrome automation reaches window-capture
  but the dispatch didn't land; I proved the *handler*, not the key→handler path). Also
  Escape-closes-dialog, `mod+k` palette, `when`-expression gating, user-override conflicts.
- **`becauseLine` non-watch variants + autonomy matrix cells** beyond Watch and Auto-LOW
  (Assist-LOW/MEDIUM, Auto-MEDIUM, the HIGH floor) — deterministic in code, unverified live.
- **SSE resilience** — agent turns stream via SSE; disconnect mid-turn / reconnection /
  partial tool-call handling untested.
- **Scale / perf** — the audit log renders one DOM row per journal entry with no
  virtualization; at the 500-entry persistence cap that's 500 rows (+ per-entry restore/
  select/causation controls). Render/scroll/memory at the cap is untested.
- **Accessibility** — focus traps in the modal/dry-run dialogs (they set
  `role="dialog" aria-modal`), focus restoration on close, `aria-live` for toasts, tab
  order — not exercised.
- **Concurrency** — the elicit **queue** (`newPending[0]`) tangled when I opened several
  imports in a row; queue ordering / rapid-undo spam / two back-to-back agent turns are
  untested.
- **#7 grouped-undo reversal-with-rows** (carried from UPDATE 6) — grouping + handler
  live-proven; the populated `undo-turn-confirm` + reverse path stays unit-proven because
  the live model never produced a reversible *grouped* turn.
- **Full visual / responsive sweep** — only one surface, one viewport, spot-checked.

### Net
No new defects found in the analysed dimensions — the structurally-risky ones
(effect-label exhaustiveness, persistence cap + restore, listener cleanup, untrusted-input
escaping) are sound, and two failure/persistence behaviours were live-confirmed. The
residue in §C is genuinely untested surface area, dominated by things the Chrome-automation
harness can't drive (real keybindings, SSE faults) or that need a fault-injection / a11y /
scale rig rather than a happy-path sweep.

## UPDATE 8 — visual / UX quality critique (how well it looks, not just whether it works)

UPDATE 6–7 measured function and robustness. This pass is the qualitative read the
user correctly flagged was missing: screenshots of each surface across states, judged
with a design eye. Captured (mounted FE, :5174): populated audit log, restore-confirm
bar, macro dry-run panel, import elicit form, near-empty state. Backend was down, so the
because-line (needs a live pending tool-call) and authentic digest content weren't
re-shot — named as untested below.

### What looks GOOD
- **Consistent dark-theme drawer/modal system** — even spacing, clear headers, legible
  hierarchy. Modals (dry-run, elicit) center cleanly over a dimming scrim.
- **Confirm bars are the strongest surface.** "Restore to this point — reverse these 5
  actions done after it?" with a clean human-readable action list (via `describeEffect`)
  and a danger-red **Restore** + neutral **Cancel** pair. Good copy, good affordance,
  correct risk coloring.
- **Originator chips** (blue `user` / orange `agent`) make provenance scannable at a glance.
- **Primary-action distinction** — green Submit / Run macro vs neutral Cancel reads well.
- **Honest live affordance states** — accurate counts ("138/138", "1/1"); disabled
  "Save selected (0)" / "Export selected (0)" correctly greyed until a selection exists.
- **Causation chip** "↳ #129 why?" is a compact, inviting affordance.
- **Digest self-hides** when there's no agent activity (good empty-state instinct).

### Quality ISSUES found (function-correct, but rough)
1. **Raw JSON leaks into the journal list rows.** Every entry renders its payload
   verbatim in monospace (`{"to":"justsearch://…"}`, `{"message":"…","severity":"warning"}`).
   That's developer-facing in a user surface — and *inconsistent*: the confirm bars and
   dry-run use the polished `describeEffect` labeler, but the main list does not. The
   labeler already exists; the list just isn't using it. **Highest-value polish fix.**
2. **Single-line input for "Archive JSON".** The import elicit asks the user to paste a
   multi-hundred-char JSON archive into a one-line text field — can't see or verify what
   was pasted. The schema (`{type:'string'}`) renders a `TextControl`; a long-text field
   wants a multiline/textarea control (a renderer-selection gap, not a logic bug).
3. **"Undo all AI actions" stays enabled with zero AI actions.** In the near-empty state
   (one user navigate, no agent entries) the AI-undo buttons are still clickable — a
   no-op / empty-confirm dead-end. Buttons don't reflect whether agent-originator entries
   exist.
4. **Dry-run before→after diff is redundant for the common kinds.** For navigate/toast it
   restates the label ("Navigate to X" / "view → X", "Show message: …" / "message: …").
   The before→after framing only earns its keep for open/close-pane/modal and
   set-form-value (which have a real prior state). For everything else it's visual noise.
5. **Absolute timestamps** ("12:23:24 AM") in an activity log — relative time ("2m ago")
   scans far better for "what did the agent just do."
6. **No turn grouping / no de-duplication.** Agent and user actions are one flat,
   repetitive list; a causal turn (129→130) isn't visually grouped, and runs of
   near-identical navigate/toast rows aren't collapsed. At density this is hard to skim.

### Still UNJUDGED (harness or backend limits — named, not claimed)
- **Responsive / narrow viewport** — `resize_window` reported success but the capture
  didn't reflect a narrowed viewport, so drawer/modal behavior on small screens is
  unverified.
- **Interaction feel** — latency, animation, focus movement, scroll perf: static
  screenshots can't assess these.
- **because-line visual** — needs a live pending agent tool-call (backend was down);
  only its text was seen earlier, never judged as a rendered surface.
- **Toast appearance/lifecycle** — transient + auto-dismissing; not captured.
- **Light theme, true zero-entry empty state** (boot always journals one navigate),
  and a full copy review.

### Net
The interaction *scaffolding* is solid and the confirm/preview surfaces are genuinely
well-designed. The recurring rough edge is the **journal list itself**: it exposes raw
JSON, lacks relative time, and doesn't group or de-duplicate — so the one surface a user
looks at most is the least polished, even though the polished labeler already exists two
components over. The single-line JSON-paste input is the other concrete affordance miss.
None of these are correctness bugs; they're the gap between "works" and "feels finished."

## UPDATE 9 — de-risk pass results (2026-05-27)

Ran the approved de-risk plan (live harness: backend :64369 READY + model, FE :5174 from
the worktree). Goal was confidence + surprise-reduction, not fixes. Headline: **the biggest
surprise risk (#7 keyboard) was a false alarm — it works** — and a **new, real backend defect
surfaced** that blocks the P1 smoke. Per-item verdicts below.

### COLLAPSED — resolved / verified
- **#7 keyboard wiring → VERIFIED LIVE (false alarm).** On confirmed-fresh code, real
  `Ctrl+Shift+Z` re-applied a navigate (hash → `#agent-emitted-demo`) and `Ctrl+Z` reverted
  it (hash → `core.agent-surface`), both with `event.defaultPrevented === true` (the real
  `KeybindingRegistry` dispatcher matched + acted). My earlier "keys don't fire" was the
  stale-module trap **plus** undo+redo cancelling out within one batched keypress sequence.
  **This is also the strongest possible proof of #1 redo — the real keypress trigger, not
  just the handler.** Remove from the open register.
- **#10 becauseLine + autonomy matrix → VERIFIED.** Direct calls over all 3×3 risk×level:
  `agentToolAutoApprove` is exactly right (watch=none, assist=LOW-only, auto=LOW+MEDIUM,
  HIGH=manual at every level) and all 6 `becauseLine` strings match their decision.
- **#12 responsive → COLLAPSED via CSS (no working resize needed).** Dry-run
  (`max-width: calc(100vw-2rem)`), elicit (`max-width: 36rem`), digest (`max-width: 30rem`)
  all clamp and degrade gracefully; there are **no `@media` queries anywhere**. The one real
  weak point is the **EffectAuditLog drawer: fixed `width: 32rem` with no max-width** — it
  overflows below ~512px. Now a specific, bounded fix, not a vague unknown.
- **#2 multiline input → feasibility RAISED to medium-high.** The renderer registry is
  JSON-Forms-style testers-with-ranks and already has an `x-ui`/`x-control` keyword-dispatch
  (`XUiRendererControl`) + custom-control precedent (CorpusPicker, EnterActionPicker). A
  `TextareaControl` + a tester matching a `format`/`x-ui` hint registers cleanly; **blast
  radius is low** (only the import schema gains the hint; other elicit forms unaffected).
- **Unified-chat surface → COLLAPSED.** Audit-log + digest are shell-chrome (surface-agnostic);
  they mount on the unified-chat surface too. The cross-surface "isolation" is purely the dev
  dual-*port* localStorage artifact; production is one origin.

### NEW DEFECT (out of 543-fwd scope — logged to observations.md)
- **`core_file_operations` NPEs on the agent's `{op,path}` mkdir arg shape.** Live agent turn
  (Auto mode) → MKDIR card `completed` with *"Execution error: Cannot invoke
  `JsonNode.asText()` because `JsonNode.get(String)` is null"* — a backend null-deref on an
  unexpected/missing field, where a clean validation error is expected. The sandbox dir was
  **never created** (verified empty on disk).

### STILL OPEN — now with a named cause
- **#9 P1 full round trip → BLOCKED by the backend NPE above**, not by safety/feasibility.
  Setup is proven feasible (added a disposable `%TEMP%` watched root via `POST
  /api/indexing/roots`, removed it after), and the FE compensating-undo wire is live-proven
  (UPDATE 6). The full agent-MKDIR→undo smoke can't complete until the `core_file_operations`
  arg-parsing defect is fixed.
- **#8 grouped-undo reversal → residual risk LOW, still not combined-live.** A reversible
  *grouped* turn in practice comes from chained file-ops — blocked by the same NPE. Pure-FE
  reversible grouped turns aren't producible (demo emits standalone; the model won't chain
  reversible FE ops). The reverse machinery is proven via adjacent live paths (restore + undo-all
  both reverse multiple entries live) and unit tests.
- **a11y → concrete gaps named.** Modals declare `role="dialog" aria-modal="true"` + Escape-close
  + `aria-label`s, but there is **no focus management** (no `.focus()`/`tabindex`/`autofocus` →
  focus isn't moved into or trapped in the dialog) and **no `aria-live`** for toasts.
- **perf → confirmed no virtualization.** `repeat([...filtered].reverse())` renders every entry;
  500 complex rows at the cap. Virtualization is a real lift (variable-height rows + inline
  confirm bars/traces), warranted only if measured janky.
- **Polish #1/#3/#5 stay high-confidence mechanical; #4 a design call; #6 a real list redesign.**
  **SSE resilience, interaction feel, light theme, toast lifecycle** stay named-untested.

### Net confidence shift
The scariest unknown (keyboard) resolved *in our favour*; the responsive + multiline unknowns
shrank to specific, low-blast-radius fixes; and the one genuinely-blocking discovery is an
out-of-scope backend bug (now logged) that gates P1/#8's full live proof. Fix-readiness for the
polish set is high; the only items needing a decision before work are #6 (redesign) and whether
perf virtualization is warranted.

## UPDATE 10 — design theory for the remaining work (2026-05-27)

Not a fix list — a theory of the *correct* shapes, grounded in the codebase's own patterns
and current best practice. The key move: most register items are not isolated patches; they
collapse into **three force-multiplier design principles** plus a few leaf fixes. Land the
principles first — each fixes several items at once and makes the consistency structural
(not per-callsite discipline that re-rots).

### Principle 1 — One labeling authority, one row primitive
`describeEffect`/`describeChange` are the single source of human-readable effect text, and
they're *already* used by the confirm bars, dry-run, and the undo toast (`EffectAuditLog`
lines 173/731/749/778/806). Only the **journal row bypasses them** — it renders
`${e.effect.kind}` + `formatPayload` (raw `JSON.stringify`). The fix isn't "call describeEffect
in the row"; it's to extract a shared presentational unit — **`<jf-effect-line>`** (primary
label via `describeEffect` · relative time · a collapsible structured-detail `<details>`) —
and render it from *every* surface. Consistency becomes a property of the component graph, not
a thing each callsite must remember. This single primitive resolves **#1** (raw JSON → label +
collapsible detail that still preserves `invoke-operation` args as key/value, not a JSON blob),
**#5** (`<time datetime title={absolute}>{formatRelative()}</time>` — the util already exists —
relative for scanning, absolute on hover), and **#4** (the diff line renders only when
`describeChange.before !== undefined`; for navigate/toast it shows just the label, killing the
redundant "after" restatement).

### Principle 2 — Untrusted-agent-input discipline at every tool boundary
The autonomy doc already cites §32.9 *"treat the LLM as an untrusted client."* The trust
lattice honours it; the tool **arg parsers do not**. `FileOperationsTool.parseOperations`
(line 308) does `Path.of(opNode.get("destination").asText())` **unguarded** — when the local
model emitted `{op,path}` (hallucinating `path` for the schema's canonical `destination`),
`get("destination")` was null → NPE (line 303 has the same latent gap for `op`). The correct
shape: a shared **agent-arg validation layer** — required-field checks that throw a *structured,
agent-visible* error ("operation 0: missing required field 'destination'") so the model
self-corrects on the next turn, **never a raw exception**. Optionally lenient: accept `path` as
an alias for `destination` (weak models routinely say "path" for mkdir). This fixes the NPE
*class*, and unblocks **#9** (P1 full smoke) and the file-ops route to **#8** (a real reversible
chained turn). The schema/parser already agree (`required:["op","destination"]`), so this is
pure robustness, not a contract change.

### Principle 3 — Native platform primitives over hand-rolled
`EffectAuditLog`, `MacroDryRun`, and `ElicitHost` each hand-roll a scrim + `role="dialog"
aria-modal="true"` + a manual Escape `keydown` listener, but with **no focus management** —
so `aria-modal` is asserted without enforcement. The correct shape is the native **`<dialog>`
+ `showModal()`**: it provides focus management, Escape-to-close, `::backdrop`, inert background,
and implicit `aria-modal` for free; modern guidance (W3C APA) says you no longer hand-roll a
focus trap with it — just put `autofocus` on the primary field/button and label via
`aria-labelledby`. This deletes three copies of scrim+keydown code and fixes the a11y gap
structurally. Pair it with one `aria-live="polite"` (`role="status"`) region in the toast host
so toasts are announced to screen readers (today they're silent).

### Leaf designs (grounded in existing conventions)
- **#2 multiline input — the JSON Forms-canonical way, not a new control.** `TextControl`
  already extends `JsonFormsRendererBase` but always renders `<input type="text">` and ignores
  `uischema.options`. JSON Forms expresses multiline as `options:{multi:true}` → `<textarea>`.
  Correct design: branch `TextControl` on `options.multi`; have `defaultUiSchemaFor` map a schema
  hint (`x-ui:{multi:true}` — the registry already has an `x-ui` tester mechanism, or
  `contentMediaType`) into `options.multi`; the import elicit's `json` property declares it. The
  `<textarea>` branch only activates when the option is present → **zero blast radius** on other
  forms.
- **#6 turn grouping / de-dup — a derived view-model, not a journal change.** The journal stays
  the flat append-only truth. Add a pure selector that folds entries into causation-rooted
  **turns** (via the existing `getGroupRoot`/`getCausationChain` DAG) and collapses runs of
  identical effects into "×N". `EffectAuditLog` renders turn headers (originator chip · root
  label · step count · collapse toggle) over `<jf-effect-line>` children. Unit-testable in
  isolation; the biggest item, but cleanly separable.
- **#3 disable AI-undo buttons — fit the existing pattern.** Mirror save/export's
  `?disabled=${selectedIds.size===0}`: compute `previewUndoAllByOriginator('agent').length` →
  disable + show the count ("Undo all AI actions (N)") when zero.
- **#12 responsive — one rule.** Audit-log drawer `width: min(32rem, 100vw)` (full-width below a
  small breakpoint). The other modals already clamp via `max-width`.
- **perf — gated on measurement.** Only if 500 rows actually janks: adopt `@lit-labs/virtualizer`
  (`<lit-virtualizer>`, dynamic variable-height measurement — fits the Lit + `@lit-labs/signals`
  stack already in use; caveat: late-prerelease 2.1.x). Cheaper alternative if not: render-cap
  with "load older" paging. Don't add the dep speculatively.

### Sequencing (why principles first)
`<jf-effect-line>` → then #1/#4/#5 are near-free; the native-`<dialog>` migration → then a11y is
done across all modals; the agent-arg validator → then #9/#8 unblock. The leaves (#2, #3, #12,
#6, perf) layer on top. Doing leaves first would re-spread the same labeling/modal logic the
principles consolidate.

### References
- Native `<dialog>` a11y (focus mgmt, no manual trap, `autofocus`): jaredcunha.com, css-tricks.com,
  thewcag.com (WCAG 2.2 modal guide).
- `@lit-labs/virtualizer` variable-height virtualization: npmjs.com/package/@lit-labs/virtualizer,
  lit/lit virtualizer README.
- JSON Forms multiline via `options.multi`: JSON Forms renderer convention (matches the
  `@jsonforms/core` types `TextControl` already imports).

## UPDATE 11 — prior-art audit (what already exists for the UPDATE 10 designs)

*Explore-before-implementing pass over each UPDATE 10 design element. Verdict per item:
**REUSE/EXTEND** (in-repo precedent to copy or an idiom to extend) vs **NET-NEW** (no prior art;
follows an external convention). Roughly half have direct precedent — much lower effort/risk
than UPDATE 10 assumed.*

### REUSE / EXTEND — precedent exists in-repo
- **perf / virtualization → COPY `Table.ts`.** `@lit-labs/virtualizer ^2.1.1` is already a
  dependency, and `components/Table.ts` already virtualizes a Lit list with the exact idiom my
  design proposed: `VIRTUALIZER_THRESHOLD = 100` → render rows directly via `.map()` below the
  threshold, `<lit-virtualizer>` above it (with an empirical sizing comment). The audit-log perf
  fix is "apply Table's pattern," not "introduce virtualization."
- **#5 relative time → ALIGN with the established convention.** `utils/relativeTime.ts`
  (`formatRelative`) is used in ~8 places (AgentView, ConversationHistory, ResourceView,
  BookmarksPopover, DispatchSource, healthEventActivityRow, …). `EffectAuditLog.formatTime`'s
  `toLocaleTimeString()` is the **outlier**; the fix aligns it with the house style.
- **#6 grouping → EXTEND primitives already wired in.** `EffectAuditLog` *already imports + uses*
  `getGroupRoot` / `getCausationChain` (for the undo-turn confirm + the #6 trace). The list-view
  grouping reuses the same DAG. Grouping-by-key precedents: `CommandPalette.groupByCategory`,
  `CitationsPanel.groupByDoc`.
- **a11y toasts → MIRROR the sibling.** The 543-fwd toasts route through `SimpleToast`
  (`showToast` / `<jf-simple-toast>`), which has **no `aria-live`** — but `AdvisoryToastHost`
  (`aria-live="polite"`), `AgentView` (`role="log" aria-live`), and `WalkthroughCard`
  (`role="status"`) already do it right. The fix is mirroring the sibling host's region, not
  inventing a pattern.
- **Principle 2 / the NPE → EXTEND an idiom already in the same file.** `FileOperationsTool`
  *already* guards `explanation` (`args.has(...) ? … : default`) and `conflict_strategy`
  (`has` + null-check + clean `"Invalid conflict_strategy: …"` throw). The per-op loop
  (lines 303/308) simply **skips** that idiom for `op`/`destination`. The fix extends the file's
  own defensive-validation style to the unguarded fields.
- **#3 disable AI-undo → EXTEND the existing button pattern.** Save/Export already do
  `?disabled=${selectedIds.size===0}` + show "(N)". The AI-undo buttons just don't; apply the
  same pattern keyed on `previewUndoAllByOriginator('agent').length`.

### NET-NEW — no prior art (follows an external convention; no duplication risk)
- **Principle 1 / `<jf-effect-line>`.** `describeEffect`/`describeChange` are called in **4 places**
  (`AiActivityDigest`, `EffectAuditLog`, `MacroDryRun`, `macros/index`), each emitting bespoke
  markup; there is **no shared effect-row/line component**. The primitive is net-new but it
  *consolidates* those 4 callsites — the consolidation target is real and bounded.
- **Principle 3 / native `<dialog>` + `showModal()`.** **Zero** production usage of the native
  dialog element (confirmed: no `showModal` outside tests); all three modals hand-roll scrim +
  `role=dialog` + manual Escape. Net-new pattern (a migration), no conflicting precedent.
- **#2 / `options.multi` textarea.** No renderer reads `uischema.options` or renders a
  `<textarea>` today. Net-new, but it's the documented JSON Forms convention (and `TextControl`
  already imports `@jsonforms/core` types), so it aligns the code with the library it's built on.

### Net
The two biggest-feeling items shrank: **perf** is a copy of `Table.ts`'s threshold idiom, and the
**backend NPE** fix is extending validation the same file already does elsewhere. Grouping reuses
DAG primitives already imported. Only `<jf-effect-line>`, the native-`<dialog>` migration, and the
`options.multi` textarea are genuinely net-new — and each is a consolidation or a
convention-alignment, not a novel invention. This strengthens the UPDATE 10 sequencing: the
force-multiplier principles are even cheaper than estimated because the substrate/idioms exist.

## UPDATE 12 — implementation + live-verification batch (worktree-543-fwd-impl, 2026-05-27)

Implemented the UPDATE 10 design (the `/goal`), then ran the single deferred live batch
(worktree FE on :5174 + dev stack + model). Per-commit static gates were green throughout
(typecheck + targeted/full FE unit suite; per-module Java build). Commits on
`worktree-543-fwd-impl` (not merged).

### What shipped (commits)
- **P1 `<jf-effect-line>`** (`747c6ac3e`): shared label+relative-time+collapsible-detail
  primitive; EffectAuditLog rows, MacroDryRun, AiActivityDigest routed through it. Fixes
  #1 (raw-JSON→label), #5 (relative time), #4 (diff only when a real `before` exists).
- **P2 FileOperationsTool validation** (`79e478de3`): per-op field validation + `path`→
  `destination` alias; clean agent-visible errors instead of NPE. Unblocks #9/#8.
- **P3 native `<dialog>`** (`9b97ab9d0`) + **Escape-sync fix** (`e865c479b`): all three
  modals use `<dialog>`+showModal (focus mgmt, ::backdrop, aria-modal); SimpleToast is a
  polite live region; #12 drawer width clamps.
- **#2 multiline** (`fa15cbc0a`): TextControl honors `options.multi`→`<textarea>` via an
  `x-ui` schema hint. **#3 disabled AI-undo** (`24dc1223b`). **#6 turn grouping**
  (`f461edcd9`): pure `groupJournalForDisplay` view-model + collapsible turn headers.

### Live-verified (worktree FE, real browser)
- **#1/#5 (screenshot):** journal rows show the friendly `describeEffect` label
  ("Navigate to …", "Run core_file_operations"), a relative `<time>` ("just now", "2m
  ago"), and a "▸ details" disclosure — no raw JSON dump.
- **#3:** empty journal → "Undo last/all AI actions" disabled; after a compensable agent
  entry → enabled, "Undo all AI actions (1)".
- **native `<dialog>`:** showModal opens the audit-log drawer; **Escape closes AND syncs
  `open`** (after the fix), and it reopens — the desync regression is gone.
- **#2 (screenshot):** the import-archive elicit renders a multiline `<textarea>` for the
  JSON field (+ single-line label input).
- **P2 (headline) — LIVE PASS:** an Auto-mode agent turn ran `core_file_operations`
  `{"op":"mkdir","path":"…/tmp-undo-test"}` → **"All 1 operations completed
  successfully"** and the directory was **created on disk** — the exact `{op,path}` shape
  that NPE'd pre-fix.
- **P1 FE wire:** the per-entry "Undo" affordance fired `POST /api/undo/core_file_operations`.
- **perf (measure-then-decide):** 401 seeded entries → audit-log render **31 ms** (401
  rows). Not janky; journal is 500-capped → **virtualization not warranted** (design's
  "only if measured janky" gate). No dependency added.

### Precisely-named residue
- **P1 full round trip (undo deletes the dir): NOT achieved live.** The undo POST returned
  **422** and the directory was not deleted. Root cause is in the pre-existing compensating-
  undo path (likely an operationId-form mismatch — the affordance posts `core_file_operations`
  (tool name) vs the canonical `core.file-operations` op-id — and/or the backend batch-log
  lookup), which **my changes do not touch**. The forward fix (P2) + the FE dispatch wire are
  proven; the backend undo handler is the remaining gap. Logged.
- **#6 turn grouping header: not shown live** — this session's agent turns produced
  single-entry (standalone) journal entries, never a multi-step causation turn, so no
  grouped header rendered. The grouping view-model + collapsible header + run-collapse are
  unit-proven (`journalView.test.ts` + EffectAuditLog turn test); the live header awaits a
  genuine multi-step reversible agent turn.
- **Backend-dist caveat:** the shared dev-runner launches the backend from **main**, not the
  worktree. To live-prove P2 I hot-swapped the worktree's fixed `app-agent` jar into main's
  build output (build artifact, not source); it is self-healing on main's next `installDist`.

### Build gate
`./gradlew build -x test` from the worktree fails ONLY at `:verifyGovernanceGates` on
**pre-existing** drift in files I did not touch (class-size: SearchExecutor/Knowledge-
HttpApiAdapter; ui-bundle hard-cap + stale metric matchers; ts-any in api/schemas.ts et al.
— all from other agents' merges, logged in observations.md). All modules + the FE bundle
**compile**; the failure is governance-baseline drift, not my code.

## UPDATE 13 — critical-analysis fixes + live re-check (worktree-543-fwd-impl, 2026-05-27)

A deep critical-analysis pass over the UPDATE-12 implementation found four real defects
(beyond the named residue); all four are fixed, unit-tested, and the backend-dependent
ones re-verified live. Commits on `worktree-543-fwd-impl` (not merged).

### Defects found + fixed
- **Fix A — over-broad catch** (`42f6c88a3`): `FileOperationsTool.execute`'s
  `catch (IllegalArgumentException)` wrapped the whole try, so an IAE from
  validate/execute/Path.of was mislabeled a clean validation error AND skipped
  `LOG.error`. Now a dedicated `OperationArgException` is caught specifically; other IAEs
  fall through to the logged generic handler. Test: a deeper IAE (InvalidPathException)
  surfaces as "Execution error".
- **Fix B — non-cancellable elicit was Escape-dismissable** (`25df810a3`): keydown
  `preventDefault` can't block the native dialog close; only `@cancel` can. Added
  `@cancel`+preventDefault for non-cancellable; `@keydown` resolves only cancellable.
  Tests: cancel-event blocked for required prompts; Escape dequeues cancellable.
- **Fix C — #6 run-collapse dropped per-entry actions** (`e00290c60`): collapsing
  consecutive identical effects hid the collapsed-away entries' selection/restore/
  undo-turn while the header still claimed "N steps". Now every member renders (turn
  grouping kept; run-collapse removed — scale handled by render perf, not row-hiding).
- **Fix F — P1 undo 422** (`730443089`): the undo posted the agent tool wire-name
  ("core_file_operations") but `OperationsController.resolveOperation` matched op-id only.
  Switched to `OperationCatalog.findByWireName` (transliterates + falls back to op-id).
  Test: `handleUndo` resolves a wire-name (not 422).

### Live re-check (worktree backend via app-agent + ui jar swap into main's dist)
- **P1 FULL ROUND-TRIP — now PASSES (the UPDATE-12 headline gap):** Auto-mode agent →
  MKDIR created `tmp-undo-test` on disk; the per-entry Undo → `POST /api/undo/
  core_file_operations` → **200** (was 422); the directory was **deleted on disk**.
  End-to-end "undo the AI's file operation" works.
- **Fix A self-correction (live):** the model first emitted `{"action":"mkdir"}` → clean
  error "operation 0: missing required field 'op'" → it **self-corrected** to
  `{"op":"mkdir","path":...}` → success. The clean message (vs the old NPE) is what let
  the agent recover within the turn — exactly Fix A's intent.
- **Fix B / Fix C:** unit-proven (a non-cancellable elicit and a multi-step *successful*
  agent turn are hard to trigger naturally in a single live session); the render paths are
  covered by `ElicitHost.test.ts` and the EffectAuditLog turn test.

### Cleanup
Sandbox watched-root + dir removed; main's `app-agent`/`ui` dist jars restored from main
source (the swap is build-output only). Full FE suite (2225) + the touched Java module
tests green; `gradlew build` still red ONLY on the pre-existing governance drift (not mine).

## UPDATE 14 — theoretical goal-achievement analysis (against the 543-fwd vision)

Stepping back from implementation specifics: did the implemented + fixed work achieve what
543-fwd is actually FOR? 543-fwd ("§32 forward") has one thesis — **make the effect journal
a trustworthy control layer over an untrusted AI** (§32.9, "treat the LLM as an untrusted
client"). Four pillars: **safety** (gate risky agent actions — S2/S6), **transparency**
(see/understand what the AI did — S3/S4/S9), **reversibility** (undo the AI, preview,
macros — S5/S8), **async** (S7). This phase was finish-and-fix.

### Per-pillar verdict
- **Safety — ACHIEVED + deepened.** P2/Fix A carried the untrusted-client principle from the
  transport layer (S2) down to the tool-argument layer: the file-op tool validates agent
  input instead of NPEing, and — live — its clean errors let the model *self-correct*
  mid-turn (`action`→`op`). Fix B keeps required prompts modal. The ethos is honored where
  it was previously breached.
- **Reversibility — ACHIEVED, and the standout.** S5's promise ("undo what the AI did")
  shipped as a stub (synthetic executionId) and was in fact broken live (undo 422'd on an
  op-id vs wire-name mismatch). Fix F + P2 made the FULL round trip real: agent creates a
  directory → Undo → it is deleted on disk. The journal's central promise now holds.
- **Transparency — MOSTLY achieved; the weakest pillar.** Legibility is real (jf-effect-line:
  plain-language labels + relative time + structured detail replace the raw-JSON dump on the
  surface users read most). But the *"understand the AI's multi-step turn"* part (#6
  causation grouping) is built correctly yet rarely triggers — today's agent turns are
  mostly single-step, so the grouped header seldom appears. Mechanism present; payoff thin.
- **Finish / a11y — ACHIEVED.** Native `<dialog>` (focus mgmt, Escape, aria-modal),
  aria-live toasts, responsive drawer width — the "works vs feels finished" gap is largely
  closed. Fix C kept per-entry control (selection/restore/undo) by rendering every member.

### Honest shortfalls vs. the ideas (theoretical)
- **Turn-transparency is under-exercised**, not under-built — depends on multi-step agent
  behavior the model rarely produces; could need grouping by session/time, not only causation.
- **Reversibility is file-ops-only** — the sole compensable backend op; read-only agent ops
  (search/browse) have no inverse, so "undo the AI" is a narrow slice by design.
- **Confidence (R-E2)** and the **autonomy dial's `vop_` path** remain forward-looking: they
  need backend signals / virtual-op tools that don't exist yet (not this phase's scope).
- **Dry-run diff (#4)** now shows before→after only when meaningful — a deliberate divergence
  from "always diff" (legibility over completeness).

### Net
The journal moved decisively toward the §32 vision: legible, safe at the untrusted boundary,
accessible, and — newly — genuinely reversible (undo-the-AI works for the first time). The
soft spot is the "understand the AI's plan" transparency: the surfaces exist but current
agent behavior rarely lights them up.

## UPDATE 15 — de-risk pass over the remaining uncertainties (2026-05-27)

Investigation + experiments to raise confidence / cut surprises before any remaining work.
Verdicts:

### COLLAPSED
- **#7 grouped-undo reversal — now LIVE (was unit-only).** A two-step Auto-mode turn produced
  a real causation chain (`core_browse_folders` 5 → `core_file_operations` 6); the audit log
  rendered a collapsible **"agent 2-step turn"** header (`turn-toggle-5`); `undo-turn` on the
  chained file-op opened a populated `undo-turn-confirm` (row "Run core_file_operations" — the
  read-only browse correctly excluded); confirm → `POST /api/undo` 200 → the created directory
  `alpha` was **deleted on disk**. The reversal-with-rows path that was unit-only is now proven
  end-to-end.
- **Transparency soft-spot — RESOLVED (fine as designed).** `AgentLoopService` iterates
  (`for iteration < maxIterations`) and `AgentSessionController.onSessionStarted` resets
  `lastAgentCausationId` per run — so a "turn" = one agent run, and grouping triggers exactly
  when a turn has ≥2 tool calls (proven live above). The "rarely lights up" was task-dependence,
  not a defect; the grouping model (causation == per-turn) is correct. No redesign needed.
- **Harness friction — clean path found.** The dev-runner roots at `process.cwd()` /
  `JUSTSEARCH_REPO_ROOT` (`scripts/dev/.../paths.mjs`) and is worktree-aware
  (`resolveMainRepoRoot`). So a worktree-rooted dev-runner invocation launches the WORKTREE
  backend directly — no jar-swap needed. (This session still used the proven jar-swap; the
  clean path is now documented for next time.)

### SCOPED (no longer a black box)
- **SSE resilience.** `api/streams.ts` already has `onError` (with `code`/`errorClass`/
  `retryable`), `AbortController`/abort handling, and a stream `catch`; the agent shape-handler
  exposes `onError`. The failure paths are **unit-testable** (drive the handler's error/close
  path). Live mid-turn fault injection still needs a rig, but the logic is verifiable — not a
  blind spot. Confidence: low → medium-high.

### CONFIRMED BLOCKED (forward-looking — needs upstream, not effort)
- **Confidence (R-E2).** PendingEffect `confidence` is optional with **no upstream source**
  (the tool-call wire carries `risk`, not confidence); §32.9 forbids the FE fabricating it.
  Blocked on an agent-emitted confidence signal + a product decision — not FE-implementable now.
- **Autonomy dial `vop_` path.** Infrastructure exists (`VirtualOperationCatalog`,
  `registerVirtualToolGate`) but **no `vop_` tools are published**, so the dial's `vop_`
  consumer gates no real traffic. Blocked on publishing virtual tools.

### High-confidence tail (FE-only, deferred verification)
Elicit-queue ordering, rapid undo/redo, unified-chat surface, true empty state, toast
lifecycle, light/narrow visual pass — small, FE-only, unit-adjacent; the earlier elicit-queue
"tangle" was a multi-trigger artifact, not a defect. Low surprise risk.

### Net
The two surprise-prone items resolved decisively: **#7 now works live** and the
**transparency grouping is correct + does trigger** for multi-step turns. SSE is scoped + 
unit-coverable; confidence/`vop_` are cleanly blocked on upstream signals (not effort). The
remaining tail is low-risk FE confirmation.

---

## UPDATE 16 — FE-tail browser-validated; one fix; SSE recovery test; blocked seams named — 2026-05-27

Per the user's bar ("a user-visible feature is successful only when validated in the real UI via
the browser"), the FE-tail was driven against the real mounted Shell on the worktree FE (:5174,
pinned to the dev backend). Worktree-FE-only (no backend jar swap — every 543-fwd surface mounts
at the Shell chrome root, single origin). Screenshots are real-Chrome captures against the
running stack.

### Fix shipped — true zero-entry empty state (one structural defect found + fixed)
`EffectAuditLog` rendered **"No entries match the current filter"** for *any* empty list — even a
genuinely empty journal with no filter active (the false-filter copy is confusing on a fresh
install). Fixed by branching on `this.entries.length === 0`
(`EffectAuditLog.ts` render, the `filtered.length === 0` block) → a true-empty journal now reads
**"No actions recorded yet — anything you or the AI does will appear here, newest first."**; the
filter-hid-everything case keeps the filter copy. Two regression tests added
(`EffectAuditLog.test.ts`) drive the real component+substrate for both branches.
- **Live proof**: forced the real mounted `jf-effect-audit-log` to 0 entries → new copy rendered,
  `0 / 0`, all mass-actions disabled, only **Import archive** active (`ss_9820xq9rn`). The
  filter-empty branch stays distinct (unit-proven).

### Browser-validated as already-correct (no change needed)
- **Toast lifecycle** — fired 3 toasts (success/warning/error) via the real `jf-show-toast`
  document event → all three render bottom-right with correct severity left-borders, stacked, each
  with a × dismiss; auto-dismiss observed; `role=status`/`aria-live=polite` code-verified
  (`ss_0114p81t5`). Manual × dismiss confirmed live.
- **Rapid undo/redo** — `Ctrl+Z`×5 then `Ctrl+Shift+Z`×5 produced exactly *4×Undone → "Nothing to
  undo" → 4×Redone → "Nothing to redo"* — symmetric, cursor-boundary-guarded, no desync, **zero
  console errors** (`ss_0673x9ybb`).
- **Elicit-queue ordering** — queued two real `elicit()` calls through the live substrate (same
  module instance confirmed: `listPendingElicits()===2`). Only **FIFO #1** showed (one modal at a
  time), its `x-ui:{multi}` field rendered a **textarea** (#2 re-proven live); resolving #1
  surfaced **#2** with a single-line input (`ss_8573jk35e`, `ss_7909s0xtb`).
- **S4 "What the AI did" digest** — appeared live ("Since you last looked, the assistant: 3
  invoke-operations" + Undo all / Save as macro / Mark as seen); **Mark as seen** dismissed it
  (`ss_51851reno`).
- **Light theme** — forced `[data-theme="light"]`: the Effect Journal re-themes correctly (dark
  text on light surfaces, readable chips/borders/buttons, no contrast issues) (`ss_8083idabz`).
- **Responsive drawer width** — `min(32rem, calc(100vw - 2rem))` resolves to **514px = 32rem** (the
  cap) live at the wide viewport. The automation viewport couldn't be shrunk below ~1346px, so the
  narrow branch (`calc(100vw - 2rem)`, which by construction can't overflow) is verified
  by-construction rather than by screenshot. *(Honest gap: no narrow screenshot.)*
- **Unified-chat / cross-surface** — the 543-fwd surfaces are chrome-root-mounted in `Shell.ts`
  (~2008–2033), so they render on the agent/chat surface (where all the above was captured).

### SSE resilience — one missing case added, rest already covered
`EnvelopeStream.test.ts` already covered malformed-frame ignore, wrong-shape ignore,
reducer-throw-preserves-payload, open/error/stop connection flips, idempotency, and subscriber
isolation (per the "already-said test" gate, no duplicates added). The one genuine gap —
**recovery**: `isConnected` flips back to `true` on the next frame after an `error`
(EventSource auto-reconnect, `handleFrame` L187–189) — is now tested. Suite: **2228 green**
(+1 here, +2 empty-state), typecheck clean. Live mid-turn fault-injection still needs a rig (out
of scope).

### Blocked items — named upstream seams (defer; no backend build, per user)
- **Confidence (R-E2)** — `PendingEffect.confidence` is defined at
  `pending-effects/index.ts:53` and set *only* at `:97` from `opts.confidence`. The sole
  production proposer (`substrates/actions/index.ts:579-581`) passes `rationale` but **never**
  `confidence`; no backend (app-agent/app-services) emits a per-effect confidence over the wire.
  **Blocked on**: an agent-emitted confidence signal at the proposer seam. §32.9 forbids the FE
  fabricating one → cannot be browser-validated until that producer exists.
- **Autonomy-dial `vop_`** — `VirtualOperationCatalog.ts` is complete + tested but its publish
  seam (`decorateCommandForAgent(id,{audience:['USER','AGENT']})` → `setVirtualOperationPublisher`
  → `publishNow`) is **never called in production**; zero commands opt into AGENT audience.
  **Blocked on**: the first plugin/command decorated for AGENT audience. Nothing to validate until
  one is published.

### Register effect
FE-tail items move **REMAINING → DONE** (empty-state, toast, elicit-queue, rapid undo/redo, light
theme; narrow = cap-verified). SSE recovery test landed. The two blocked items now carry exact
upstream seams instead of fuzzy "low confidence". No merge (user's call).

---

## UPDATE 17 — what remains (forward synthesis) — 2026-05-27

A step back over the whole arc to state honestly what is left and — more usefully — what *kind* of
remaining each item is, since they are not the same and shouldn't be lumped.

### This session's goal-achievement read (theoretical)
This round was **finishing + proving, not advancing**. The one behavioral change was the empty-state
copy (a small honesty fix); the rest was browser-proving the FE-tail and documenting blockers. Its
truest contribution was to the doc's own epistemic spine — `static-green ≠ live-working`: it
converted "should work" into "demonstrated live." The four core pillars (safety / transparency /
reversibility / async) were already built and proven in earlier sessions; this session moved none of
them forward in capability. Against the grand §32 thesis it is a finishing increment, not a
vision-mover (consistent with UPDATE 14's per-pillar verdict).

### Headline finding — the frontend is ahead of its producers
The single most important characterization of what remains: **several built FE surfaces are waiting
on a backend/agent signal that does not exist yet.** The confidence chip (no agent-emitted
confidence), the autonomy dial's `vop_` disposition path (no published `vop_` tools), and a real
"why" (the because-line is FE-*derived* from risk+dial level, not an agent rationale) are all
complete on the FE and dormant for lack of an upstream producer. This is the arc's own
**"substrate ahead of consumer"** lesson turned forward; the **531 consumer-drift gate** exists to
fail loudly if these surfaces ever drift back to dormancy. So the valuable remaining work is mostly
**not more frontend** — it is the agent/backend catching up to the FE that already receives it.

### Remaining work, sorted by kind
1. **Merge decision (user's) — the only thing blocking "done."** The current polish body (UPDATEs
   3–17, this `worktree-543-fwd-impl`) is unmerged. Gated only by **pre-existing governance drift in
   other agents' files** (class-size / ui-bundle / ts-any) that reddens `gradlew build`; my code
   compiles and tests are green. (See the merge-state reconciliation in the top register.)
2. **Blocked on upstream producers (not FE effort)** — confidence (R-E2), the `vop_` path, and an
   agent-emitted rationale. Each has a named seam (top register / UPDATE 16) and is correctly
   deferred; §32.9 forbids the FE fabricating any of them.
3. **One open design question — transparency *shape*.** The causation-per-turn grouping **model is
   correct and not a FE redesign** (UPDATE 15 settled this: a "turn" = one agent run; grouping fires
   for ≥2 tool calls). What's open is whether it *pays off*, which depends on richer multi-step agent
   behavior + a reason signal — i.e. **upstream**, not FE. (Not a reversal of UPDATE 15.)
4. **One product decision — reversibility *narrowness*.** "Undo the AI" works end-to-end, but for
   exactly the one undoable op (`core.file-operations` — destructive, HIGH, always-confirmed).
   Read-only agent ops have no inverse, so the **Undo affordance only ever appears for the scariest
   action, never routine ones** (the S11 UX implication, elevated). Defensible, but worth a conscious
   product stance rather than silent acceptance.
5. **Honest small residue (named, low-stakes, non-blocking)** — narrow-viewport visual verified
   by-construction, never *seen* (UPDATE 16); `exportJournalArchive` omits `causation` → imported
   archives are causation-flat (UPDATE 7 §A); the indexing-jobs bridge maps a departed PENDING job
   optimistically to `succeeded` and opens a duplicate SSE alongside the Resource view (deep-
   reflection §C/§D); navigate-via-cursor undo/redo is best-effort (router re-journals / canonicalizes
   — UPDATE 3); `assist` proposes *all* FE-mediated backend ops, not just risky ones, with a
   risk-aware refinement deferred (deep-reflection §B); the full a11y sweep (focus-restoration on
   close, tab order) was not exhaustively driven (UPDATE 9 §a11y).
6. **Can't be closed by code** — interaction feel (latency / animation; human judgment, UPDATE 8);
   live SSE mid-turn fault injection (needs a fault rig — the resilience logic is now fully
   unit-covered, UPDATE 16).

### Net
The frontend is essentially finished and proven. The genuinely valuable remaining work is
**upstream** — the agent/backend emitting the confidence, reasons, and richer multi-step turns that
the already-built transparency and confidence surfaces are waiting to display — plus the user's
merge call. Everything else is either a conscious product decision (reversibility narrowness),
small named residue, or unmeasurable-by-code.

---

## UPDATE 18 — merge-prep (main merged in) + two residue fixes — 2026-05-27

Per the user's decision to take the unmerged body to `main` plus two residue fixes (#3, #5;
#1/#4 deferred with reasons). Commits on `worktree-543-fwd-impl`.

### Investigation of `main` (corrected the merge-state picture)
- `main` already carries **UPDATEs 3–11** (its tempdoc has them); only **my 16 commits =
  UPDATEs 12–17** were unmerged. The earlier register line saying "3–17 unmerged" overstated —
  fixed in the top register.
- `main` moved **+28 commits** (549/551/552/553 search-trace + gate work) and had **already
  fixed the governance drift** I'd flagged (`fix(gates): seed the empty ts-any baseline`,
  `fix(549): realign class-size pins`). So the "pre-merge build red on others' drift" caveat is
  obsolete once main is merged in.
- `git merge main` was **clean except one trivial conflict** (`docs/observations.md`, both
  appended Inbox lines). Resolved by taking main's version — my only unique line (a
  governance-failures note) was stale (main fixed those gates). Branch is now **0-behind** main →
  the merge to `main` will be a fast-forward.

### Residue #3 — indexing-jobs SSE pooled (DONE)
The always-on Task-tray bridge and an open `core.indexing-jobs` Resource view both subscribed to
`/api/indexing-jobs/stream`. Verified against **authoritative backend tests** (not the synthetic
catalog fixture): `IndexingJobsResourceCatalogTest` → endpoint `/api/indexing-jobs/stream`;
`WireShapeContractTest` → primaryKey `pathHash` — so both consumers use the **same URL + same
tabular reducer shape**, making pooling safe (no corruption). Routed both through
`EnvelopeStreamPool.subscribePooled()`; they now share one EventSource in the production
single-origin case (refcounted, last-release stops). ResourceView's now-unused direct stream
field + `EnvelopeStream` import removed. New test asserts one shared stream + both consumers
receiving frames + last-release close. *(The triage's "10-line safe win" was over-optimistic —
it touched the general ResourceView + needed URL/key verification; surfaced and resolved honestly.)*

### Residue #5 — dialog focus-restoration (DONE) + a real bug caught
Native `<dialog>.showModal()` only auto-restores focus when opened via an invoker click; the three
modals open by property, so focus was lost on close. Save `document.activeElement` before
`showModal`, restore on close. **The test caught a real correctness bug in `ElicitHost`**: it
captured focus in `updated()` (after render), but the elicit form's field **autofocuses during
render**, so it captured the input, not the invoker — restoring to a removed input. Fixed by
capturing in `refresh()` *before* render (empty→pending transition). EffectAuditLog/MacroDryRun
capture in `updated()` (their content doesn't steal focus). Focus-spy tests per modal (assert the
captured element's `focus()` fires on close — deterministic, avoiding flaky `activeElement` reads).

### Corrections folded in
- **Residue #2 was a no-op (and the doc was stale).** The bridge **removes** departed job-tasks
  (vanishes), it does **not** mark them `succeeded` — confirmed against the current code + test
  (`indexingJobsBridge.test.ts`: "a job that leaves the live set → REMOVED (not marked
  succeeded)"). UPDATE 17 §5 / deep-reflection §C's "optimistically → succeeded" described a
  pre-reset-fix state and is stale; no code change needed.

### Deferred (with reasons, user-confirmed)
- **#1 causation-in-export** — involved: causation is an entry-ID reference, so a correct
  round-trip needs an ID-remapping table + archive version bump. Current replay works without it.
- **#4 `assist` risk-awareness** — touches the autonomy trust/safety model + threads op-risk to
  the gate; a behavioral contract change needing design review, not a residue fix.

### Verification + merge
- FE: typecheck clean; the touched suites (EnvelopeStream, EffectAuditLog, MacroDryRun, ElicitHost,
  indexingJobsBridge, ResourceView) green incl. the new dedup + 3 focus tests; full suite re-run
  before merge.
- Pre-merge gate `./gradlew build -x test` (+ any sanctioned ui-bundle rebalance from this work's
  own FE growth), then a fast-forward merge to `main`. No push unless asked.
