---
title: "601 — A model-load TIME-estimate behind forward-looking availability. REFRAMED twice in the design pass: (1) the premise 'the backend has no progress/ETA signal' is partly FALSE — `lastStartupDurationMs` already rides `/api/inference/status` and is already rendered as 'Usually takes ~Ns' (BrainSurface, tempdoc 518); (2) once the adjacent cluster is read, 601's UNIQUE unowned problem is NARROW — the AI model-load time-estimate for a transiently-unavailable affordance. Indexing/folder progress is 599's; embedding coverage already ships; the degradation cause is 600's. So 601 is NOT a general progress framework — it is one small estimate seam shared by BrainSurface + 596's projectAvailability, reusing the existing wire field. The live-elapsed primitive and any cross-operation Progress type are deferred as precision the problem does not yet require."
status: "IMPLEMENTED & MERGED to main (2026-06-17). The §13/U1 backend gap was fixed at root cause and PROVEN LIVE (`lastStartupDurationMs=5676` after a real activation, where it was absent before); the FE estimate seam + truthfulness arms were verified by in-browser execution of the served bundle + the rendered tooltip (both arms). See §15 (as-built + live verification). Design history: design pass §§5–11; §12 user-facing design; §13 confidence pass (which CORRECTED the assumption that the field was already surfaced); §14 cross-tempdoc interference. Scope was NARROWED in the second design round — see §6."
created: 2026-06-17
updated: 2026-06-17
relates-to: [596, 599, 600]
---

# 601 — A model-load time-estimate behind forward-looking availability

> **Reading order.** The sections "Why this tempdoc was opened" → "Open questions" are the ORIGINAL
> problem statement, unchanged. **§5 onward is the design pass.** Two findings reframe the doc and
> sharply narrow it: §5 — the premise "the backend has no progress/ETA signal" is **partly false** (the
> model-load estimate already rides the wire AND is already rendered); §6 — reading the adjacent cluster
> (598/599/600) shows 601's *unique, unowned* problem is **only** the AI model-load time-estimate; the
> indexing/embedding/rebuild progress I first folded in already has homes. **If you read one section,
> read §6 (the scope cut) then §7 (the design).**

> **Scope of the original problem statement (below):** the main idea only — the problem and why it is
> worth a tempdoc. The design and truthfulness contract are in the design pass (§5+).

## Why this tempdoc was opened

This spun directly out of **tempdoc 596** (the affordance-availability authority). 596 wanted a
*forward-looking* reason for a transiently-unavailable control — the difference between a dead
"Unavailable" and a reassuring **"available in ~10s"** / **"ready once the model finishes loading"**.

When 596 went to build it, the honest finding (596 §16.4 / decision D1, confirmed in §18) was: **the
frontend has no time or progress data to back a number.** The observed-state snapshots
(`inferencePoll` / `statusPoll`, surfaced through `aiStateStore`) carry phases and counts
(`runtime.mode`, `index.pendingJobs`, `documentCount`) but **no elapsed/remaining/percent field**. So
596 deliberately scoped its forward-looking wording to the *condition* it can know
("available once indexing finishes"), and explicitly ruled a numeric ETA **out** — because inventing a
"~10s" with no data behind it would be a fabricated, frequently-wrong promise, which is exactly the
"unexplained / untruthful affordance" class 596 exists to kill.

So the gap is real and named, but it lives **below** the frontend: closing it is not a UI change, it is
a **backend** capability — emit a trustworthy progress/ETA signal for the long-running, self-clearing
operations (model load/activation, indexing/embedding, rebuilds) that the UI already shows as "busy".
That is a distinct piece of work from 596, with its own owner (the worker / inference lifecycle), its own
correctness bar (an ETA that is *wrong* is worse than none), and its own consumers beyond 596 (the Health
"now" strip, indexing surfaces, the degradation banner) — hence its own tempdoc rather than a 596 follow-up.

## The main idea

The long-running operations the system runs (loading the local AI model, indexing a folder, computing
embeddings, rebuilding the index) each have a knowable sense of *how far along* they are and *roughly how
much is left*. Today that is **not observable** — the frontend can only say "this is happening", not "this
is N% done" or "≈T seconds remain".

The idea is to give those operations a **first-class, trustworthy progress signal** — surfaced through the
same observed-state path the UI already consumes — so a forward-looking, *time-aware* statement of
availability becomes possible and honest:

- A still-loading affordance can read **"available in ~10s"**, not just "still starting".
- A "busy" indexing job can show real progress, not an indeterminate spinner.
- 596's queue-and-auto-run can tell the user *how long* their queued action will wait.

> **Design-pass correction (see §6):** of these three bullets, only the **first** turns out to be 601's
> own. The indexing-progress bullet is **599's** (per-folder journey status); the queue-wait bullet is a
> *consumer* of the first, not a separate signal. The problem statement was written before the cluster
> was mapped; §6 is the corrected scope.

## What this is NOT (guardrails for the later design pass)

- **Not** a frontend change — 596 already consumes whatever the backend exposes; the missing half is the
  data. This tempdoc is about producing the signal at its source. *(Partly corrected in §5: the data is
  not actually missing — it is on the wire and unread.)*
- **Not** a license to fabricate. The whole reason 596 deferred this is that a wrong ETA is worse than
  none. The bar for the eventual design is a signal honest enough to show as a number — including
  honestly saying "unknown" when it is (mirroring the system's existing tri-state / `Maybe` discipline).
- **Not** scoped here. Which operations, how progress is measured, how an ETA is estimated, the wire shape,
  and the truthfulness contract are all **out of this doc** and belong to the design pass.

## Open questions to resolve in the design pass (recorded, not answered)

1. Which long-running operations are in scope first (AI model load is the 596-motivating one)?
2. Is the signal *progress* (a fraction/known-total), *ETA* (a time estimate), or both — and is ETA
   derived on the backend or left to the FE to estimate from a progress stream?
3. How is "unknown / not estimable yet" represented so the UI never shows a confident-but-wrong number?
4. Who else consumes it (Health "now" strip, indexing/operation surfaces, the 596 queue), and does it ride
   the existing observed-state snapshot path or a new channel?

---

# DESIGN PASS (2026-06-17)

> Genre: design-theory in the 557/594/595/596/599/600 line — state the correct end-state and its
> truthfulness contract; size is an outcome of the scope judgment, not a target. Read-only investigation
> against `main`: three parallel codebase sweeps (observed-state path, inference lifecycle,
> indexing/rebuild) + targeted `git grep` verification + a read of the adjacent cluster (598/599/600).
> **This pass corrects an over-reach in its own first draft** (a unified cross-operation `Progress` type)
> — see §6 and §11.

## 5. First reframing — the premise is partly false

The problem statement (written from 596's vantage) says the backend "has no time or progress data to back a
number." **Investigation against `main` shows the model-load estimate already exists and is already
rendered:**

- `lastStartupDurationMs` (the last successful inference startup duration in this process lifetime; `-1`
  when none) is on `/api/inference/status`, lands in the FE inference snapshot (`inferencePoll.ts:28`),
  and is **already formatted into the exact "available in ~Ns" idiom 596 said was impossible**:
  `formatRestartEtaSub()` (`BrainSurface.ts:159–173`, tempdoc 518 App. F W3.1) renders *"AI is
  initializing. Usually takes ~Ns."*, and honestly falls back to *"AI is initializing."* (no number)
  when the value is `< 0`. Backend source: `InferenceLifecycleManager` captures `elapsed = now −
  startupStart` and exposes it via `OnlineAiRuntimeIntrospection.lastStartupDurationMs()`.

So **596 §18.2's claim** — *"no progress/ETA field exists in `inferencePoll`/`statusPoll`; would need a new
backend signal"* — **is stale.** The field exists in `inferencePoll`; the ETA idiom is already shipped. What
596 actually lacked was not a backend signal but a way for `projectAvailability` (the affordance reason
projector) to *reach* the estimate. 601's value is therefore not "produce a missing signal."

## 6. Second reframing — the scope cut (the central result of this pass)

My first draft of this design pass generalized 601 into "one typed `Progress` projection over **all**
long-running operations (model load + embedding + rebuild + folder walk)." **Reading the adjacent cluster
shows that is over-reach** — most of those operations already have an owner, and a unified `Progress` type
would *fork* a seam 599 is already building. Corrected boundary:

| Operation | Honest signal | Owner — NOT 601 |
|---|---|---|
| **Folder / per-folder indexing journey** (progress + terminal "done") | count-down "N remaining" → "✓ searchable" | **599** — `folderStatus(root)` seam, folder-granularity sibling of `computeVerdict`; *explicitly count-down, no percentage, no ETA* (599 §9.1/§9.2/§10) |
| **Embedding / SPLADE coverage** | determinate `%` (`coveragePercent`) | **already ships** — on `/api/status`, consumed by `BrainSurface.ts:1497` + `aiStateStore.ts:342` |
| **Index rebuild transition** | provisional / last-known via `Stability` | **595** (transition display) + **599** (folder `ready` on drain) |
| **Why a capability is degraded** | reason code + remedy | **600** (degradation-cause legibility) |
| **AI model load → time to availability** | `estimate` ("usually ~Ns") | **601 — this is the only unowned one** |

601's *unique, unowned* problem is therefore narrow and exactly the 596-motivating one: **a transiently
unavailable affordance (the model is loading) cannot state a TIME** — "available in ~Ns" — even though the
estimate already exists on the wire and is already formatted elsewhere. Everything else the original problem
statement listed is owned. **601 is one small estimate seam, not a progress framework.**

This is the scope-matching discipline applied honestly: the unified `Progress` type was structure for cases
601 does not own (a textbook *speculative completeness* / over-DRY error — it shared a *consumer surface*
across operations that share no *reason to change*; per AHA, do not unify those). The corrected design adds
only the structure the model-load case requires.

## 7. The design — one shared model-load-estimate seam (extends 596, reuses the wire)

The defect, stated once at the corrected scope:

> The model-load time-estimate is derived in exactly one place today — `formatRestartEtaSub` on
> `BrainSurface`. 596's `projectAvailability` needs the same estimate to say "available in ~Ns" on the
> affordance bar, and has no way to reach it; so it falls back to condition-only wording (596 §18.1
> Phase 1c). If 596 hand-rolls its own derivation from `lastStartupDurationMs`, that is a **second
> derivation site of the same fact** — the precise representation-drift the 594/595/596/599 lineage exists
> to kill (BrainSurface could later format "~11s" while the affordance bar formats "~12s" off the same
> field, or one updates its honesty rule and the other does not).

The structural fix is the same one every sibling used: **resolve the fact once, upstream; make each surface
a pure consumer.** Concretely, and no larger than the problem:

- **One derivation — `modelLoadEstimate(inferenceSnapshot) → LoadEstimate`**, a small projection that lives
  beside 596's availability authority (the natural home, since the originating consumer is
  `projectAvailability`). It is the *sole* place the model-load estimate is decided. Conceptual shape (a
  two-arm value, not a record spec — this is all the model-load case needs):
  - `{ kind: 'estimate'; typicalMs }` — there is a last-known duration to state ("usually ~Ns").
  - `{ kind: 'unknown' }` — no basis (`lastStartupDurationMs < 0`, first load this process lifetime). The
    UI says "AI is initializing", **no number** — the existing `Maybe`/tri-state idiom, and exactly what
    `formatRestartEtaSub` already does for `< 0`. This is the structural answer to open-question 3:
    "unknown" is a first-class arm, never a fabricated sentinel.
- **Two consumers, no third channel (open-question 4).** (a) `BrainSurface` — `formatRestartEtaSub` becomes
  a *renderer of the `estimate` arm* rather than its own mini-derivation (dissolving the incipient fork).
  (b) 596's `projectAvailability` — reads the seam to render "available in ~Ns" on a transient-unavailable
  affordance, and 596's queue-and-auto-run (596 §18.1 Phase 3a) tells the user how long the queued action
  will wait. Everything rides the existing observed-state path (`inferencePoll` → `aiStateStore`), the same
  path `verdict` and `projectAvailability` already ride.

That is the whole design: **lift one existing derivation into a shared seam, add one consumer.** No new wire
field is required for this floor (§9), no new poller, no new record tier — it is composition of authorities
that already exist, the same shape 599 §9 reached for its own scope. **⚠ Qualified by §13/U1 (live probe):
the wire *field* exists but is not *populated* through the activation paths users actually hit — so a
contained backend duration-surfacing fix is a prerequisite. The FE seam below is unchanged; the data source
is not.**

## 8. Backend vs frontend split (open-question 2)

**The estimate is FE-derived from the existing primitive; the backend stays primitive.** This matches the
established pattern (595's `computeVerdict` derives FE-side from raw observed-state) and avoids a real trap:
at a 5–10s poll cadence a **backend-computed "remaining: 3s" is already wrong by the time the FE renders
it.** The backend already emits the only primitive the floor needs — `lastStartupDurationMs`. The seam reads
it and picks the arm. No ETA arithmetic crosses the wire.

## 9. Truthfulness contract (the teeth — this is the weakest signal in the system, so the contract matters most)

The irony worth naming: the 596-*motivating* operation is the system's **weakest** for an honest time
estimate. llama.cpp's `/health` is binary (no fraction); the inference sweep found cold-vs-warm cache
differs 2–3× on the same hardware. So `lastStartupDurationMs` is a *weak predictor*, and the contract must
prevent it from masquerading as precision:

- **Estimate-only, never a determinate %, never a decrementing countdown.** Render the retrospective
  "usually ~Ns" (what `formatRestartEtaSub` already ships) — *never* "ready in 6s…5s…", which would sit at
  "0s remaining" while the model is still loading, the exact fabrication 596 feared.
- **No basis ⇒ `unknown`, never a guess.** `lastStartupDurationMs < 0` ⇒ the `unknown` arm ⇒ no number.
  This is already the live behavior; the contract makes it load-bearing rather than incidental.
- **Phrase as approximate and historical, not a promise.** "Usually takes ~Ns" is honest (it is a
  statement about the past); "available in Ns" is a promise the signal cannot keep. The affordance-bar
  wording in 596 should inherit the "usually ~Ns" framing, not a hard countdown.

## 10. Scope — new vs reused vs deferred

- **Reused (do not build):** `lastStartupDurationMs` (wire *field* + FE snapshot — **but see §13/U1: the
  field is not populated through the GPU-runtime-activate / reload paths, so its *surfacing* is NOT free**),
  `formatRestartEtaSub` (becomes the `estimate`-arm renderer), the `inferencePoll → aiStateStore` transport,
  596's `projectAvailability` consumer seam.
- **Genuinely new — minimal:** one shared `modelLoadEstimate` derivation (the two-arm value above) + wiring
  596's affordance bar to it. The "type" is barely more than the number-vs-`<0` distinction
  `formatRestartEtaSub` already encodes — promoted to a named seam with a second consumer.
- **Deferred as precision the present problem does not require** (the 599-style scope line — do not add
  structure for cases not yet present):
  - **Live elapsed during the current load** ("loading… 12s"). Would need one new backend primitive — an
    *op-start* epoch (`inference.loadStartedAtEpochMs`; note `loadedAtEpochMs` is *completion*, not start).
    The forward-looking statement 596 asked for ("available in ~Ns") is satisfied by the *retrospective*
    typical alone; live elapsed is an enhancement, deferrable exactly as 599 deferred its percentage
    denominator. Add it only if a concrete consumer needs the count-up.
  - **Any cross-operation unified `Progress` type.** Each operation that has progress already has its own
    owner and its own honest shape (§6 table); a single type over them is the over-unification §6/§11
    rejects. If a future surface genuinely needs to render *several* operations' progress uniformly, that
    is the moment to introduce a shared shape — driven by that consumer, not pre-built here.

## 11. Critical analysis (including the self-correction)

- **Is even this seam warranted, or is "already rendered on BrainSurface" enough?** Warranted, narrowly:
  596 is a real, documented second consumer (596 D1/§18.1 Phase 1c) that cannot reach the estimate today,
  and the moment it derives its own, the fork is live. Per `structural-defects-no-repeat`, one consumer
  blocked by the absence of a single authority proves the class — the same logic 599 §9.1 used for the
  folder verdict. But the seam must stay *small* (one derivation, two consumers); inflating it back into a
  general framework is the error this pass corrected.
- **The first-draft error, named for the record.** Draft 1 proposed a four-arm `Progress` type
  (`determinate | estimate | indeterminate | unknown`) over model-load + embedding + rebuild + folder walk.
  That bundled cases owned by 599 (folder/indexing), already shipped (embedding `coveragePercent`), and
  595/600 (rebuild transition / degradation cause). It unified a *consumer surface* across operations with
  no shared *reason to change* — the AHA anti-pattern. The cluster read (598/599/600) is what surfaced it;
  the corrected scope is one operation, one seam.
- **AHA cut, restated.** The model-load `estimate` and the embedding `determinate %` do *not* belong to one
  type. They have different sources (inference startup duration vs worker enrichment counts), different
  owners (601 vs already-shipped), different consumers, and different truthfulness regimes (weak predictor
  vs real denominator). Keep them separate; that separation *is* the design decision.
- **Does the `estimate` arm risk re-introducing the wrong-ETA harm 596 feared?** Only if rendered as a
  countdown or a promise. §9 forbids both; the seam *generalizes the existing honest idiom*
  (`formatRestartEtaSub`'s "usually ~Ns" + `< 0` fallback), it does not invent a riskier one.
- **Verification when built** (per `audit-driven-fixes-need-test`): the contract is unit-testable without a
  live stack — `lastStartupDurationMs < 0` ⇒ `unknown` (no number); `≥ 0` ⇒ `estimate` with the rounded
  seconds; assert both `BrainSurface` and `projectAvailability` read the seam (one derivation, no second
  literal). The live tier (`use-every-verification-tier`): `ai_activate` against the running stack to
  confirm a transient-unavailable affordance reads "usually ~Ns" while loading and flips to `available` on
  ready — the one path that needs the LLM boundary crossed.
- **Coordination.** 596 is IMPLEMENTED & MERGED; this extends its availability authority, so the seam's
  home is the `availability`/`projectAvailability` module, and `formatRestartEtaSub` migrates into it. No
  conflict with 599 (different operation, different surface) — the two are siblings of `computeVerdict` at
  different granularities, by design.

## 12. User-facing / frontend design (browser-verified against the live demo, 2026-06-17)

The §7 seam is entirely about *what the user reads*, so this was inspected in the running UI (Chrome on
the `?demo=true` shell) rather than judged from code. Two surfaces render the model-load state today, and
the inspection both confirmed the fork §7 predicted **and** surfaced a detection gap the code-only read had
missed.

### 12.1 The two homes, as they actually render

- **The affordance-bar tooltip (chat surface).** The mode tabs `Documents / Structured / Agent` dim when
  AI is unavailable; hovering a dimmed tab pops 596's reachable reason bubble. Live, it read
  **"The local AI model is offline"** — a small one-line tooltip (596's `aria-disabled` reachable channel,
  confirmed focusable). This is the literal home of the 596-motivating "available in ~Ns".
- **The BrainSurface status subtitle (`core.brain-surface`).** "AI Brain" shows a single `{dot, label, sub}`
  status line per AI state (`BrainSurface.ts:1033` `statusConfig`). The `starting` arm's `sub` is
  **`formatRestartEtaSub(lastStartupDurationMs)`** → *"AI is initializing. Usually takes ~Ns."* (in demo it
  sat on the `connecting` arm, *"Checking AI status…"*). This is the estimate already shipping — on this one
  surface only.

### 12.2 What the inspection changed in the design

1. **The fork is user-visible, and it is a *wording* fork as well as an estimate fork.** The same fact is
   worded two ways across the two surfaces — BrainSurface *"AI is initializing. Usually takes ~Ns"* vs the
   tooltip/`CAUSE_ROWS` *"The local AI model is still starting"* (`readinessNotice.ts:95`, severity `info`,
   **no number**). So §7's "one estimate" must travel *through the one reason vocabulary*: the estimate is a
   **suffix appended to the existing `inference.starting` reason wording**, not a second formatter. Both
   surfaces then speak one wording + one estimate. (This is the §7 seam landing in the *reason vocabulary*
   `reasonFor`/`CAUSE_ROWS`, the same single-authority `availability.ts` already consumes — not a new type.)

2. **A detection gap that only shows up live: the estimate must key on the model-load state, not the FE
   connection phase.** `projectAvailability` marks a reason `transient` only when `s.phase === 'connecting'`
   (the FE↔backend connect window, `availability.ts:87`); once *connected* but with `capabilities.chat ===
   false`, it falls to the **settled** `inference.offline` (`:92`). But during an actual model load the
   store reports `runtime.mode === 'starting'` (`aiStateStore.ts:271`, derived from `inference.starting`)
   while chat is still false and the phase is `connected` — so today the tooltip would say the *settled*
   "offline" during a live load, and the estimate would never appear. **The estimate's correct trigger is
   `runtime.mode === 'starting'`** (model actively loading), a transient distinct from both
   `inference.offline` (off, not loading) and the connect-phase transient. This refinement is necessary for
   the §7 design to be *reachable* at all — without it the `estimate` arm has no live path to the user.

3. **Tooltip size constrains the wording.** The reachable bubble is ~one line (verified). The estimate must
   be a short suffix — *"The local AI model is still starting — usually ready in ~11s"* fits; a multi-clause
   ETA does not. The `unknown` arm stays the current bare *"…still starting"* (no number).

### 12.3 The user-facing wording contract (grounds §9 in real copy)

- **estimate arm** (`runtime.mode==='starting'` ∧ `lastStartupDurationMs ≥ 0`): *"The local AI model is
  still starting — usually ready in ~Ns."* The "usually" framing is historical/approximate, not a promise
  — it reuses the honest idiom `formatRestartEtaSub` already ships, and BrainSurface adopts the same suffix
  so the two surfaces stop diverging.
- **unknown arm** (`lastStartupDurationMs < 0`, first load this lifetime): *"The local AI model is still
  starting."* — no number, exactly today's `inference.starting` wording and `formatRestartEtaSub`'s `< 0`
  fallback. The `Maybe` discipline, made user-visible.
- **Never a decrementing countdown** ("ready in 6s…5s…") on either surface — §9's central prohibition,
  reaffirmed at the copy level.

### 12.4 Consumers, restated at the pixel level (open-question 4, grounded)

- **Affordance tooltip** — gains the estimate suffix on the `starting` reason (the originating 596 gap).
- **BrainSurface `starting.sub`** — keeps rendering the estimate, but as a *consumer of the shared seam /
  reason vocabulary* rather than its own `formatRestartEtaSub` call, dissolving the wording fork (12.2.1).
- **596 queue-and-auto-run activation toast** (596 §18.1 Phase 3a) — a click on a starting-unavailable tab
  can now say *"Queued — AI usually ready in ~Ns"* instead of a bare condition. Same seam, third surface.

### 12.5 Deliberately NOT in the user-facing scope (UX scope discipline)

- **A prominent inline "available in ~Ns" badge on the tab itself** (beyond the hover/focus tooltip). The
  reachable-tooltip suffix is the honest floor and the 596 channel; a persistent on-tab countdown is more
  pixels, more motion, and re-tempts the countdown anti-pattern. Defer unless a concrete need appears.
- **Model *download* progress.** Inspection found BrainSurface's `installing` arm already renders a real
  determinate **download %** from `bytesDownloaded/bytesTotal` (`BrainSurface.ts:1070`). That is a *real
  denominator* and already works — a different operation (download, not load) with its own honest signal.
  601 does not touch it; noting it only to draw the boundary (it is the determinate cousin of the load
  `estimate`, and correctly already determinate).
- **The status-bar "Backend disconnected" / "CONN" pill.** A connection-state indicator, not a model-load
  estimate — adjacent, owned by the observed-state/connection display, out of 601.

## 13. Confidence-building findings (2026-06-17 — live dev-stack probe + code trace)

A pre-implementation pass ran the design's load-bearing assumptions against a **running** stack and the
gates (read-only; `ai_activate`, `/api/inference/reload`, `/api/ai/runtime/*`, status polls — no code
changes). One assumption was **corrected, and it is material**: it re-opens part of §10's "FE-only floor".

| # | Assumption | Verdict | Effect |
|---|---|---|---|
| U1 | `lastStartupDurationMs` is a reliable basis already on the wire | **CORRECTED (major).** It is absent on a fresh process (✓ the `unknown` arm), but it **stayed absent after a successful GPU activation** (`ai_activate`, measured 10888ms) **and after a `/api/inference/reload`**. The field is written only by the **Mode state machine cold-start** (`switchToOnlineMode` → `withStartupDuration`, `/api/inference/mode`); the **GPU-runtime activation path** (`/api/ai/runtime/activate`, what `ai_activate`/the UI use) and the reload remedy **bypass it**. The two lifecycles are decoupled — a `/api/ai/runtime/deactivate` left `mode:"online"`. | The `estimate` arm's data source is, in practice, **usually absent through the paths users actually trigger** — so `unknown` would dominate and the feature would rarely render a number. **This is a real backend dependency the design missed.** It also means the existing 518 "Usually takes ~Ns" on BrainSurface is likely **dormant in the common GPU-activate path** too. |
| U2 | The `runtime.mode==='starting'` window is observable during load | **Backend half CONFIRMED** (`starting=true` for the whole `Mode.TRANSITIONING` window — `OnlineAiServiceImpl.isStartingUp`/`InferenceHandlers:69`; FE derives `runtime.mode='starting'` from it — `aiStateStore.ts:271`). Live FE-window capture deferred — moot until U1's data gap is closed (no number to show regardless). | The trigger refinement (§12.2.2) stands; it is gated behind U1, not independently risky. |
| U3 | A dynamic "~Ns" suffix is architecturally permitted | **CONFIRMED.** Share the estimate **number**, applied in `availability.ts:unavailableFor` (or as an optional numeric field on the `unavailable` `Availability`). `verdict-derivation` forbids only `retrieval ===` re-derivation; `presentation-purity` governs op-label keys — neither covers reason wording. `availability.ts` reading `s.inference?.lastStartupDurationMs` is gate-safe. | The FE seam is clear; no gate baseline work. |
| U4 | BrainSurface can unify without a copy regression | **CONFIRMED → decided.** BrainSurface keeps its `label:'Starting…'` + `sub` structure and shares only the **estimate number**, not the `CAUSE_ROWS` sentence (which would read redundantly beside "Starting…"). | No regression; only the number is the shared seam, not the sentence. |
| U6 | `s.inference?.lastStartupDurationMs` is reachable in `projectAvailability` | **CONFIRMED** (`inferencePoll.ts` `InferenceSnapshot` → `aiStateStore` `AiState.inference`). | — |

### 13.1 The revised shape of the work (consequence of U1)

601 is **no longer FE-only**. The honest floor now has a backend prerequisite, plus a decision:

- **Backend prerequisite (new):** a startup duration must be **recorded and surfaced on the paths users
  actually hit** — the GPU-runtime activation (`/api/ai/runtime/activate`) and the reload remedy
  (`/api/inference/reload`) — not only the `switchToOnlineMode` Mode-transition path. The duration *is*
  measured on the GPU path (`ai_activate` returned `durationMs:10888`); the gap is that it never reaches
  `runner.view().lastStartupDurationMs()`, the field the status endpoint reads
  (`InferenceLifecycleManager:239`). The fix is to thread the already-measured duration into the surfaced
  view across both activation lifecycles (or unify the two lifecycles' duration recording) — a contained
  inference-lifecycle change, but a genuine cross-process one, not the "reuse the existing field" the design
  assumed in §7/§10.
- **Decision for the user:** because the existing 518 BrainSurface ETA is also dormant on the common path,
  fixing U1 *also revives that shipped feature*. 601 should either (a) own the backend duration-surfacing
  fix as its prerequisite (recommended — it is the data 601's whole thesis rests on), or (b) be explicitly
  blocked on a separate inference-lifecycle fix. Either way, **§10's "no new backend field for the floor"
  is downgraded to "no new *field*, but a new *population/surfacing path* is required."**
- **Unchanged:** the FE seam (U3/U4/U6) is well-understood and gate-clear; the truthfulness contract (§9)
  and consumer set (§12) hold. The risk moved from the FE to the backend data source.

### 13.2 Critical confidence rating for the remaining (implementation) work: **5.5 / 10**

Down from the pre-probe ~7 the FE-only framing implied. The FE half is high-confidence (the seam, the
gates, the wording, the reachability are all verified). The rating is pulled down by U1: the estimate's
data source is unreliable through the real activation paths, so the floor now includes a backend
inference-lifecycle change across two decoupled activation lifecycles — verified as a real gap, but its
exact fix (thread the measured duration into the surfaced view vs unify the lifecycles) is not yet pinned,
and it touches a process boundary with its own transition/rollback semantics. The surprise is now **known
and bounded**, which is the point of this pass; closing U1's fix shape (one more targeted code trace of the
GPU-runtime-activate view-install path + a `/api/inference/mode` cold-start probe to confirm that path
*does* populate the field) would raise the rating to ~7.5.

## 14. Cross-tempdoc interference analysis (2026-06-17)

Checked the active 593–603 cluster (all modified <5h) + the two active worktrees against 601's footprint:
FE `availability.ts` / `readinessNotice.ts` (CAUSE_ROWS `inference.starting`) / `BrainSurface.ts` (starting
arm) / `aiStateStore.ts` (`runtime.mode`) / `Control.ts` (the tooltip), and the §13 backend dependency
(`InferenceLifecycleManager` / `InferenceHandlers` / the GPU-runtime-activate view-install path).

**No active worktree interferes — both are orthogonal at the file AND seam level:**
- **598 `worktree-598-capability-retrieval`** (auto-dense retrieval reachability): real committed files are
  search/retrieval only (`KnowledgeSearchEngine`, `SearchPipelinePresets`, `GrpcSearchService`,
  `SearchSurface.ts`, `indexing.proto`). The `availability.ts`/`Control.ts`/`BrainSurface.ts` deltas its
  `diff --stat main` shows are **base-skew**: 598 branched at `9ef0f1bf3` (the commit *before* the 596 merge
  `b90300adc`), so 596's merged FE additions read as "deletions" — 598 has **zero** real commits to any 601
  file. **One merge-hygiene hazard (598's, not 601's):** 598 must merge current `main` before merge-back, or
  it would transiently revert the 596 foundation 601 extends. Semantic coupling is also low — 598 changes
  *retrieval* capability; 601's estimate keys on *inference load* (`runtime.mode==='starting'` /
  `lastStartupDurationMs`), a different axis.
- **599 `worktree-599-impl`** (folder indexing journey): real committed files are folder/indexing only
  (`folderStatus.ts` new, `LibrarySurface.ts`, `IndexedRootView`, the per-folder gRPC count, a new gate). It
  did not even need to touch `aiStateStore.ts`. **Zero** 601-file overlap — the clean sibling split 601 §6/
  §11 predicted (both are `computeVerdict`-pattern projections at different granularities).

**Design-level overlaps (no worktree — coordination, not conflict):**
- **600** (degradation cause): its Design A edits the SAME reason-vocabulary authority 601 does
  (`readinessNotice.ts` `CAUSE_ROWS` + `verdict.ts`). **Additive, not contradictory** — 600 adds *cause*
  rows (`BLOCKED_LEGACY`/reindex) and rewrites the verdict→cause synthesis; 601 adds an *estimate suffix* to
  the `inference.starting` row via `reasonFor`/`unavailableFor`. Different rows, and 600 does not change the
  `reasonFor`/`unavailableFor` contract 601's seam reads. The only real touchpoint is co-editing
  `readinessNotice.ts` → a merge-order coordination, not a design clash. (600's own PART VI interference
  pass predates 601's design and did not consider it; this is the reciprocal note.)
- **595** (observed-state / verdict): the parts 601 depends on (`verdict`, `CAUSE_ROWS`, `runtime.mode`,
  `projectAvailability`) are **merged and stable on `main`**; 595's remaining Stability-axis/tone work is
  additive and 596 confirmed the seam does not restructure. No worktree. Low interference.
- **602** (residual-findings catalog) explicitly defers to 601 for 601's territory ("the sweep after
  597/598/599/600/601"); catalog-only. **603** (RAG trust) is a different domain (grounding/citations), no
  601-file overlap.

**601's new §13 backend dependency is uncontended:** no active worktree touches the inference lifecycle
(`InferenceLifecycleManager`/`InferenceHandlers`/GPU-runtime-activate) — 598 is search, 599 is indexing.

**Net:** no current work threatens 601's remaining work. Two soft coordination items: (1) sequence the
`readinessNotice.ts` `CAUSE_ROWS` edits with 600 (additive, trivial); (2) ensure 598 merges `main` before
merge-back so the 596 foundation is not transiently reverted (598's hygiene; affects 601 only if 601 starts
from a 598-poisoned `main`).

## 15. As-built + live verification (worktree `601-impl`, 2026-06-17)

Implemented exactly the §6-narrowed scope: the model-load estimate seam (no cross-operation `Progress`
type), with the §13/U1 backend gap fixed at root cause.

### 15.1 Changes
- **Backend (root cause, §13/U1):** `InferenceLifecycleManager.applyConfig(...)` restart body now times the
  `startLlamaServer()`+`waitForServerHealth()` window and records `.withStartupDuration(elapsed)` on the
  success view — mirroring `switchToOnlineMode()`. This surfaces `lastStartupDurationMs` after BOTH the
  GPU-runtime-activate (`/api/ai/runtime/activate`) and reload (`/api/inference/reload`) paths, which reach
  Online via `applyConfig`, not `switchToOnlineMode`. (+9 LOC; class-size pin bumped 1253→1262 with a
  `declared-growth` changeset `gates/class-size/.changesets/601-inference-startup-duration.md`.)
- **FE — one shared seam:** new `state/startupEstimate.ts` `formatStartupEstimate(ms) → "~Ns" | null` is the
  sole `<0→unknown` + seconds-format decision. `availability.ts` `projectAvailability` gained a branch
  keyed on `runtime.mode==='starting'` (the load state, not the connect phase — §12.2.2) that returns the
  transient `inference.starting` reason with the estimate suffixed via `unavailableFor(code, transient,
  estimate)`. `BrainSurface.formatRestartEtaSub` now delegates to the shared helper (number unified, sentence
  kept — §12.2.1/U4).
- **Tests:** `startupEstimate.test.ts` (formatter arms), `availability.test.ts` (+3: estimate arm / unknown
  arm / precedence over offline), and a backend regression `InferenceLifecycleManagerExternalServerTest
  .applyConfigRestartRecordsStartupDuration_tempdoc601` driving `applyConfig(RESTART_ALWAYS)` from a fresh
  OFFLINE manager (reaches Online without `switchToOnlineMode`, so `getLastStartupDurationMs() >= 0`
  isolates the fix).

### 15.2 Static verification (all green)
`npm run typecheck` clean; `npm run test:unit:run` **327 files / 3141 tests pass** (incl. the new cases);
`./gradlew.bat :modules:app-inference:test :modules:ui:test` pass (incl. the new regression);
`./gradlew.bat build -x test` BUILD SUCCESSFUL; gates: `check-verdict-derivation` OK,
`check-presentation-purity` OK, `class-size` pass (changeset).

### 15.3 Live verification (worktree backend on a real model)
The dev stack was run from the worktree (`dev-runner.cjs`, which builds the worktree code) — the MCP
dev-runner is pinned to `main` and could not run the fix. Backend on `:63123`, worktree FE on `:5191`
(a fresh Vite; the session's `:5173`/`:5180` Vites were stale and served pre-601 code — a port-conflict
trap worth noting for future worktree live-checks).

- **Backend fix, before/after (real model load):** BEFORE (this fix absent, captured earlier on `main`):
  `inference_status` after `ai_activate` AND after reload → `lastStartupDurationMs` **ABSENT**. AFTER (worktree
  backend): after activation → `mode=online`, **`lastStartupDurationMs=5676`** present. The exact field/path
  the §13/U1 probe found broken is now populated.
- **FE arms, executed in-browser against the served worktree bundle** (`import()` of the live modules on
  `:5191`, fed the real `5676`):
  - Chat affordance tooltip (`projectAvailability`, estimate arm): `{ kind:'unavailable', transient:true,
    reason:"The local AI model is still starting — usually ready in ~6s" }`.
  - Unknown arm (`lastStartupDurationMs=-1`): `reason:"The local AI model is still starting"` — **no number**.
  - Settled offline (not starting): `reason:"The local AI model is offline"`, `transient:false` (distinct).
  - BrainSurface sub (`formatRestartEtaSub` delegating): `5676 → "AI is initializing. Usually takes ~6s."`;
    `-1/undefined → "AI is initializing."`.
  - `formatStartupEstimate`: `5676→"~6s"`, `11000→"~11s"`, `65000→"~1m 5s"`, `-1/undefined→null`.
  - Live FE confirmed running against the worktree backend (status bar "Online — Qwen Qwen3.5-9B", `:5191`).
- **RENDERED tooltip captured from the live DOM** (worktree FE `:5191`, the model-loading state driven via
  the store's `__feedForTest` hook — `runtime.mode='starting'`, `phase='connected'`, the real `5676` — which
  exercises the SAME component render path a live load produces, without the warm-load race):
  - Chat Documents-tab tooltip, **estimate arm** — `role="tooltip"` DOM text:
    **"The local AI model is still starting — usually ready in ~6s"** (screenshot: dimmed tab + tooltip
    bubble; status bar "Starting…").
  - Chat Documents-tab tooltip, **unknown arm** (`lastStartupDurationMs=-1`) — DOM text:
    **"The local AI model is still starting"** (no number).
  > BrainSurface's `starting.sub` rendered-DOM was not captured: the demo surface-host stayed on a
  > "Loading AI Brain…" placeholder and the browser tab became unstable (navigations reverting / error
  > pages) — a browser-tooling issue, not a code issue. It is covered by (a) the served-bundle execution
  > above (`formatRestartEtaSub(5676) → "AI is initializing. Usually takes ~6s."`) and (b) the fact that it
  > consumes the identical `formatStartupEstimate` helper proven rendering in the chat tooltip, plus the
  > unit tests. The raced *live-load* screenshot was infeasible (warm load ~5.6s < FE poll; backend
  > re-entering INDEXING) — the `__feedForTest`-driven render above is the faithful, deterministic capture.

## 16. Forward-research survey — what the shipped substrate enables (2026-06-17)

> Genre: a *survey of what the §15 substrate (the one `formatStartupEstimate` seam + the reliably-surfaced
> `lastStartupDurationMs` + the honest estimate/unknown arms) makes cheap*, mirroring 596 §16 / 594 §18.
> Not a commitment — ideas ranked by value × cheapness, each anchored to a codebase hook AND external UX
> evidence, with an explicit AHA / over-engineering check. The app is pre-production (no users), so all are
> viable; none are urgent. Two findings reframe the menu (both grounded in the codebase, not invented):
> (i) a **live count-up already exists and is proven** in chat (`ReasoningController` "Thinking… 12s" =
> FE-captured `Date.now()` + a 1s `setInterval`) — so 601's *deferred* live-elapsed needs **no backend
> primitive**; (ii) **richer estimate data already rides the wire untapped** (`/api/inference/transitions`
> carries 10–20 per-transition `durationMs`; the FE reads only the single `lastStartupDurationMs`).

### 16.0 External evidence base (cited)
- **Response-time thresholds (NN/g, Nielsen):** 0.1s = instant, 1s = noticeable but flow intact, **10s =
  attention limit / abandon**. Operations >10s want a percent-done indicator **and a signposted interrupt**;
  >1s benefits from a wait animation. Model load is ~5–11s warm and 30–60s cold — squarely in the
  "needs a time-aware indicator + interrupt" zone, esp. cold.
- **Psychology of waiting (Maister):** uncertain waits feel longer than **known finite** ones; **unexplained**
  waits feel longer than explained; **occupied** time feels shorter; "people want to get started"; anxiety
  lengthens. → 601's "usually ready in ~Ns" converts an uncertain wait into a known finite one; a live
  count-up makes it occupied + explained.
- **Honest-uncertainty / anti-jumpy-ETA:** inaccurate/jumpy progress (stall-then-jump-to-100%) **erodes
  trust**; the honest rules are (1) use uncertainty words ("usually", "~", "about"), (2) explain the process,
  (3) when the total isn't knowable, **count what's done**, (4) when all else fails, be honest. 601 already
  obeys all four; this is *why* a faked model-load percent is the wrong move.
- **Labor illusion (Buell & Norton, HBS, *Management Science* 2011):** visibly signalling that the system is
  working **increases perceived value** and makes the wait feel **shorter** — surfacing live load progress is
  a net-positive trust signal, not merely damage control.

### 16.1 Ranked ideas (anchored + AHA-checked)

**A. Live-elapsed count-up — NEW, cheap, highest value (top pick).**
*"Loading the local model… 12s · usually ~6s"* — a live count-up beside the historical estimate, on the
affordance tooltip / BrainSurface sub / queued toast. **Hook:** reuse the proven `ReasoningController`
pattern (capture `Date.now()` when `runtime.mode` flips to `'starting'`; 1s `setInterval`; clear on settle) —
**no backend primitive** (601 §10 deferred this assuming a new `loadStartedAtEpochMs`; the codebase shows the
FE-captured idiom already ships for chat "Thinking… Ns"). **Evidence:** Maister (occupied + explained),
NN/g (>1s animation), labor illusion (visible effort → value). **AHA:** extends the one seam; the count-up
reuses the seconds-humanize core (see D). **Honesty:** count-up is always measured-truthful; pair with
"usually ~Ns" (uncertainty word); **never a decrementing "ready in 5…4…" countdown** (601 §9 — and the
jumpy-ETA trust research independently condemns it).

**B. "Taking longer than usual" — NEW, precedented, trust/honesty (depends on A).**
Once live elapsed exceeds the historical typical (e.g. ≥1.5–2×), switch wording to a calm *"Still loading —
taking longer than usual"* (info tone, not alarm). **Hook:** `verdict.ts` ALREADY does exactly this for
rebuild/migration ("(taking longer than expected)" via `migrationSwitchingAgeMs` vs `…MaxDurationMs`) — mirror
for model load using elapsed-vs-typical. **Evidence:** Maister (defuse anxiety of an unexplained stall),
honest-uncertainty rule #2 (explain when overdue), NN/g (>10s → also offer a signposted interrupt, i.e. the
existing reload/cancel affordance). **AHA:** reuses the established escalation idiom; small.

**C. History-backed estimate — POLISH, honest, uses existing data.**
Replace the single-sample `lastStartupDurationMs` with a **rolling median of recent successful durations** from
`/api/inference/transitions` (already 10–20 entries on the wire), with confidence-aware wording ("last time
~Ns" for n=1 → "usually ~Ns" for n≥3). **Hook:** the FE only reads `lastStartupDurationMs` today; the
transitions history is untapped. **Evidence:** anti-jumpy-ETA (a median is stable across loads vs a lone
sample), honest-uncertainty (uncertainty words scale with confidence). **AHA:** a small derivation inside the
`startupEstimate` seam; **no new backend**. The median naturally down-weights the cold-start outlier (the
warm/cold 2–3× spread the §13 probe found) — a cheaper honesty win than a separate cold/warm model.

**D. Shared "humanize seconds" core — SIMPLIFY, modest.**
The forward/elapsed `seconds → "~Ns" / "Nm Ns"` logic is now adjacent across `formatStartupEstimate`, the
inline "Thinking… Ns" (`aiStateStore.computeStatusLabel`), and `ReasoningController.elapsedSeconds`. Extract
one `humanizeSeconds()` core they share (and that A's count-up reuses). **AHA caveat (important):** do NOT
over-DRY — `relativeTime.formatRelative` (past timestamps) and `formatBytes` are different domains with
different reasons to change; leave them. Only the *forward/elapsed duration → short string* logic unifies.

**E. Generalize the estimate to other binary-readiness ops — EXTEND, narrowly scoped.**
The "estimate from last-known duration + live elapsed" seam fits **worker restart** (binary readiness, like
model load, with a recordable last-duration). **AHA boundary (the load-bearing cut):** do NOT extend to ops
that already have a *real denominator* — **embedding/SPLADE coverage** and **model download** show a true
percent (better than an estimate), and **folder/index progress is 599's count-down** (601 §6 cut). The seam
is for *binary-readiness ops without a denominator*; determinate ops keep their %. So the honest extension is
just worker-restart (if/when it surfaces a "restarting…" state), not a generic progress framework.

**F. Determinate model-load % via llama.cpp log parsing — NEW, REJECTED (over-engineering).**
llama-server logs layer-load progress ("loaded 80/140 layers") to stderr; parsing it could yield a real
model-load percent (which NN/g prefers for >10s). **Rejected:** high cost + fragility (scraping upstream
stderr, version-coupled) — the 596/601 investigation already flagged llama.cpp's load signal as
parse-fragile. The honest estimate (A+B+C) delivers most of the value at a fraction of the cost and risk.
Recorded to mark the boundary, not to pursue.

### 16.2 Recommended order (an outcome of the judgment, not a target)
**A** (live count-up, the proven `ReasoningController` reuse) → **D** (extract the shared humanizer alongside
A) → **C** (median-backed estimate from the existing transitions history) → **B** ("taking longer than usual",
needs A's elapsed). **E** only if worker-restart grows a visible state; **F** stays rejected. Each is small,
extends an existing seam, and is independently shippable — the substrate did the structural work; these are
leaf refinements on it.

### Sources
- NN/g — *Response Time Limits* (Nielsen): https://www.nngroup.com/articles/response-times-3-important-limits/ ; *Progress Indicators Make a Slow System Less Insufferable*: https://www.nngroup.com/articles/progress-indicators/
- D. Maister — *The Psychology of Waiting Lines*: https://www.columbia.edu/~ww2040/4615S13/Psychology_of_Waiting_Lines.pdf
- Cloud Four — *Truth, Lies and Progress Bars*: https://cloudfour.com/thinks/truth-lies-and-progress-bars/
- Buell & Norton — *The Labor Illusion: How Operational Transparency Increases Perceived Value* (HBS / Management Science 2011): https://www.hbs.edu/ris/Publication%20Files/Norton_Michael_The%20labor%20illusion%20How%20operational_f4269b70-3732-4fc4-8113-72d0c47533e0.pdf

## 17. The correct long-term design for the §16 remaining work (2026-06-17)

> Genre: design-theory (general, not implementation-level), scope-matched to the *present* problem the
> shipped estimate has — not to hypothetical futures. It EXTENDS existing seams; it replaces nothing.

### 17.1 The present problem (what the shipped estimate actually lacks)
601 ships a **static, single-sample, phase-blind** estimate: the same "usually ~Ns" string regardless of
whether the load just began or is overrunning, drawn from the *one* last duration (so it swings 2–3× cold↔warm
— the §13 spread). The §16 evidence base names three real consequences: the wait is **unoccupied** (no live
"it's progressing"), **unexplained when overdue** (no "taking longer than usual"), and the number is
**jumpy** (single sample → erodes trust). These are properties of the *shipped code*, not speculation.

### 17.2 The correct shape — ONE live value, resolved once (the §16 A+B+C unified)
§16's three ideas are not three bolt-ons; they are three facets of a single thing — **time-to-ready for a
binary-readiness operation**: how long it's *been* (elapsed), how long it *usually* takes (typical), and
whether it's *overrunning* (over-typical). Shipping them independently would fork the elapsed-derivation and
the over-typical threshold across the three render sites (tooltip · BrainSurface · queued toast) — the exact
representation-drift this lineage exists to kill, and the same reason 601 §7 resolved the static number once.
So the long-term shape promotes the seam's output from a bare `ms → string` to a small typed value resolved
once in the observed-state layer:

```
ModelLoadProgress =
  | { kind: 'idle' }
  | { kind: 'loading';
      elapsedMs: number;          // live (A) — from a captured load-start + the store clock
      typicalMs: number | null;   // (C) — median of recent successful loads; null ⇒ unknown arm
      sampleCount: number;        // (C) — drives confidence wording ("last time" vs "usually")
      overTypical: boolean }      // (B) — elapsed materially exceeds typical
```

ONE renderer (extending today's `formatStartupEstimate`) turns this into the wording every site reads:
- `loading`, typical known, not over → **"still starting — 12s · usually ~6s"** (occupied + known-finite).
- `loading`, typical known, over → **"still starting — 18s · taking longer than usual"** (explained overrun).
- `loading`, typical unknown → **"still starting — 12s"** (elapsed only; the honest unknown arm, no fabricated typical).
- confidence: "usually ~Ns" only when `sampleCount ≥ k`; else "last time ~Ns" (honest-uncertainty rule).

This is the minimal structure the present problem requires: one small union, one renderer. Not a framework.

### 17.3 Where each piece lives — extend, don't replace
- **Live elapsed (A):** the store captures `loadStartedAtMs = now` when `runtime.mode` flips to `'starting'`
  (FE-captured, exactly as `ReasoningController` does for "Thinking… Ns" — **no backend primitive**, so 601
  §10's deferred `loadStartedAtEpochMs` stays deferred), and ticks at **1s while loading** (the store already
  owns `clockTickSig` + a 5s `stalenessTimer`; the only change is a faster cadence *during the starting
  window*, restored after). Because `aiState` recomputes on the tick and the consumers re-derive from it, the
  count-up reaches all three sites for free — the single-authority home, not three per-component timers.
- **Typical from history (C):** the median + sample-count of recent successful loads. The data already exists
  in the backend transitions ring buffer (`/api/inference/transitions`, ≤20 `durationMs`); the FE reads only
  the single `lastStartupDurationMs` today. Minimal-wire form: the backend surfaces `recentStartupMedianMs` +
  `recentStartupSampleCount` on the inference-status snapshot — two primitives, mirroring exactly how 601
  surfaced `lastStartupDurationMs` (the FE still owns all wording/confidence/phase derivation per the
  backend-emits-primitives / FE-derives split). The median naturally down-weights the cold-start outlier — a
  cheaper honesty win than modelling cold/warm separately.
- **Over-typical (B):** derived in the same seam (`elapsedMs > typicalMs × K`), wording mirrors `verdict.ts`'s
  existing rebuild escalation idiom (`…AgeMs` vs `…MaxDurationMs`). **Mirror, do not merge:** it stays in the
  596 availability-reason wording at a **calm/info** tone — it must NOT escalate the 595 system-health verdict
  (model load is a transient affordance state, not a degradation; this is the 595 `transitioning`-is-calm rule
  and the 595/596 altitude boundary, §9.6).
- **The renderer + humanizer (D):** the `seconds → "~Ns"/"Nm Ns"` core is shared by this renderer, the live
  count-up, and the inline "Thinking… Ns" — extract the one `humanizeSeconds()` they share. (AHA: leave
  `relativeTime` (past) and `formatBytes` alone — different reasons to change.)
- **The three consumers stay pure readers** of the resolved value — unchanged in role, exactly as 601 left them.

### 17.4 Scope boundaries (what this design deliberately excludes)
- **No cross-operation `Progress` framework** (601 §6 rejected; reaffirmed). The value/renderer are
  operation-agnostic *by being plain durations* — but only the **model-load derivation** is built. Worker
  restart (§16/E) would add a *second derivation* feeding the same value **if/when** it grows a visible
  "restarting…" state — not built now (no present case). Determinate ops (download %, embedding %, folder
  count-down = 599) keep their own real signals; the estimate is for binary-readiness ops without a
  denominator only.
- **FE-captured start, not a backend epoch.** The late-join undercount (UI opened mid-load) is immaterial
  pre-production and the typical reference covers it; a backend `loadStartedAtEpochMs` is a precision upgrade
  to add only if accurate late-join elapsed ever matters.
- **No log-parsed determinate %** (§16/F) — fragile/version-coupled; the honest live estimate wins on
  cost/value.
- **Honesty contract carried forward (§9):** elapsed is always measured-truthful; "usually" only with enough
  samples; over-typical is a calm explanation, never an alarm; **never a decrementing countdown.**

### 17.5 Why this is the right size
The substrate (the resolved-once seam, the surfaced duration, the three pure consumers) already did the
structural work in 601. §17 completes the *one* value those consumers read — from a static number to a live,
honest, phase-aware estimate — by extending the store clock, the transitions data, and the escalation idiom
that all already exist. The new structure is a single small typed value plus one renderer; everything else is
reuse. That is the scope the present problem requires — no more (no framework, no second operation, no backend
epoch), no less (not three drifting per-site patches).

### 17.6 Critical refinement (bidirectional pass over §17.2–§17.3, 2026-06-17)
A second pass against `main` corrected three points in §17 and **shrank** the design. (Adjacent-cluster
re-check: no tempdoc >603 exists; 602/603 carry nothing on the estimate/elapsed/availability axis — the design
is unchanged by the cluster.)

- **Correction 1 — the live elapsed is PRESENTATION, not observed-state (the load-bearing fix).** §17.3
  proposed capturing `loadStartedAtMs` and ticking inside `aiStateStore`. But `aiState` is a single
  `computed` fanned out to **every** `subscribeAiState` consumer on any input change (verified), so a 1s
  in-store tick would re-render all observed-state surfaces every second during a load — and, more
  fundamentally, **a count-up is a derived presentation value, not observed state**, so putting it in the
  observed-state authority violates the 557 Display/Observed-state separation this whole lineage rests on.
  Correct home: a small **presentation-layer timer** (the `ReasoningController` pattern) that *observes* the
  store's existing `runtime.mode==='starting'` start-signal and exposes `loadElapsedMs` as a **focused
  signal** only the estimate surfaces subscribe to. The store stays pure observed-state (it already provides
  the start-signal + the typical); the timer stays presentation. "Resolve once" then applies to the **one
  wording function** (`renderLoadEstimate({elapsedMs, typicalMs}) → string`, extending `formatStartupEstimate`)
  that both persistent surfaces call — that is what prevents the elapsed/threshold fork, *not* a store field.
- **Correction 2 — history median (C) is DEFERRED, and is FE-fetched not backend-surfaced.** Strict scope:
  the live elapsed (A) and the calm over-typical (B) address the validated gaps (unoccupied / unexplained
  waits) and need **no new fetch and no backend change** — B's "is elapsed ≫ typical" works fine on the
  single-sample typical (6s last → 20s now is unambiguous). The median (C) only steadies the *displayed*
  "usually ~Ns" against cold↔warm swing — a milder, currently-userless concern. So C is **deferred** until
  jumpiness is observed to matter; when added, it is the FE fetching the **existing** `/api/inference/transitions`
  route (which the FE does not call today) **once on load-complete** and computing the median — FE-owned
  arithmetic per the backend-emits-primitives split, **no backend change** (§17.3's "backend surfaces the
  median" is withdrawn). This makes the core completion A+B only.
- **Correction 3 — consumer nuance.** The queued-toast (`Control.ts`) is a momentary fire-once toast; it takes
  the **typical** ("usually ~Ns"), not a ticking count-up (toasts don't re-render with a live number). Only
  the two **persistent** surfaces (chat affordance tooltip, BrainSurface sub) consume `loadElapsedMs`. The one
  wording function still serves all three; each picks the facets it shows.

**Net after the refinement:** the core is **A (live elapsed, a presentation timer reusing the `ReasoningController`
pattern) + B (calm over-typical, mirroring `verdict.ts`)** + the one shared wording function + the
`humanizeSeconds` extraction (D) — **zero backend change, zero new fetch, no store coupling.** C (median) and
worker-restart (E) and a backend start-epoch are all explicitly deferred (no present case). The corrected
design is strictly smaller than §17.2–§17.3 and better respects the 557 presentation/observed-state cut.

## 18. User-facing / frontend design — placement by visibility (browser-verified, 2026-06-17)

§17/§17.6 designed the live estimate from the code; inspecting the running UI (demo shell on a Vite serving
the merged 601 code) corrected **where** the live facet belongs — a question code alone hid.

### 18.1 What the live UI shows (screenshots)
- **Always-visible status pill** (bottom-left): during a load it reads a bare **"Starting…"** — *no number*.
  Decisively, the **same** `computeStatusLabel` already renders a live count-up for chat —
  **"Thinking… 12s"** (`aiStateStore.ts:303–307`, elapsed from a captured `startedAtMs`, shown after >2s).
  So a live count-up on this pill is a *proven, in-place pattern*, and the model-load case is the one that
  is conspicuously number-less.
- **Chat affordance tooltip** (the §15 estimate's current home): it is **hover/focus-only** (596's reachable
  tooltip — the dimmed tab shows nothing until hovered). A *ticking* number here is near-useless: the user
  only sees it if they happen to be hovering a disabled tab during the ~6–30s load.
- The pill is **compact** — "Starting… 12s" fits; a long "taking longer than usual" clause would overflow it.

### 18.2 The correction to §17.6's consumer mapping (the load-bearing UX finding)
§17.6 named the *hover tooltip* + BrainSurface as the live-elapsed consumers and **omitted the status pill**.
Inspection inverts this: **a live count-up only earns its keep on an *always-visible* surface.** So map the
facets to surfaces *by visibility*, not uniformly:

| Surface | Visibility | Facet it should show |
|---|---|---|
| **Status-bar pill** (`computeStatusLabel`) | **always** | **the live count-up** — "Starting… 12s" (mirror the existing "Thinking… Ns"). The *primary* home — most-seen, proven, compact-fits the number. |
| Chat affordance tooltip (`projectAvailability`) | on hover/focus | the **typical reason** — "still starting — usually ready in ~6s" (the reachable *why + when*); **not** the ticking number. Unchanged from §15. |
| BrainSurface "Starting…" sub | when on the Brain screen | the **full** estimate — "AI is initializing. 12s · usually ~6s" (room for both elapsed + typical). |
| Queued-action toast (`Control.ts`) | momentary | the **typical** only (toasts don't tick). Unchanged. |

This makes the *highest-value, cheapest* win the one §17 under-weighted: **add the live elapsed to the status
pill**, exactly mirroring "Thinking… Ns" in the same function — always visible, no hover required.

### 18.3 Over-typical (B) wording, placed by room/tone
- The **compact pill** carries the **number only** when overrunning ("Starting… 18s") — it must **not**
  escalate to the amber/alarm tone (the pill's `transitioning` tier is calm by 595 design; §17.6's
  calm-not-verdict rule, reaffirmed at the pixel level). The pill stays calm and just keeps counting.
- The fuller **"taking longer than usual"** explanation rides the surfaces with room — the **tooltip** and
  **BrainSurface** — where the user who wants the *why* will look. (Honest-uncertainty rule #2: explain when
  overdue, but where there is space, not crammed into a status chip.)

### 18.4 Net effect on the design
§18 does not add scope — it *relocates* the already-scoped live-elapsed facet (A) from the hover tooltip to
the always-visible status pill, where the proven "Thinking… Ns" precedent already lives. The tooltip/toast
keep the typical (unchanged from §15); BrainSurface shows both. The user-facing core is therefore: **the
status pill ticks "Starting… Ns" (mirroring chat "Thinking… Ns"), staying calm if it overruns; the tooltip
keeps "usually ~Ns"; BrainSurface shows both.** Same small structure as §17.6, now placed where users will
actually see it.

## 19. Confidence-building pass before implementation (2026-06-17)

Two read-only code traces against `main` retired the remaining work's load-bearing assumptions. The pass
**corrected §18 and §17.6** and, by deferring the one un-honest piece, **shrank the core to A + D**.

- **U1 — the pill does NOT tick at 1s (CORRECTED §18, and simplified §17.6).** `StatusDeck` (the status
  pill) has **no timer** — it refreshes only via `subscribeAiState` (`StatusDeck.ts:254`), i.e. when
  `aiState` recomputes. No status-bar/chrome component runs a 1s tick; only `UnifiedChatView` does, and that
  drives the *chat reasoning block*, not the pill (a `UnifiedChatView.requestUpdate` does not change a store
  signal, so it does not cascade to the pill). The pill's "Thinking… Ns" therefore advances in **~5s steps**
  (the `stalenessTimer`@5s bumps `clockTick`, which `buildSnapshot` reads → `aiState` recomputes →
  StatusDeck refreshes), *not* 1s. **Consequence:** "Starting… Ns" rides the **existing 5s store clock with
  no new timer** — computed inside `computeStatusLabel` from a store-captured `loadStartedAtMs` + `Date.now()`,
  exactly as the shipped "Thinking… Ns" computes from `act.startedAtMs`. This **corrects §17.6's "the elapsed
  must not live in the store"**: the shipped precedent computes the live elapsed in the store's
  `computeStatusLabel` from a *static captured timestamp* (not a stored ticking number), which is clean and
  causes no store-wide churn. A 1s `StatusDeck` render-tick (gated on the live-elapsed label) is an **optional
  smoothness upgrade**, not required — 5s-step is honest and adequate for a load indicator.
- **U2 — gate-clean, and even cleaner than feared (CONFIRMED).** Model-load 'starting' is rendered by the
  **settled runtime-label branch** (`computeStatusLabel`: `if (runtime.mode === 'starting') return
  'Starting…'`), **not** the `verdictHeadline` branch — so adding an elapsed compositor there touches neither
  595's verdict wording nor `readiness.retrieval`. The `verdict-derivation` gate forbids only re-deriving the
  verdict from `readiness.retrieval` outside `verdict.ts`; an elapsed compositor reading `runtime.mode` + a
  timestamp is untouched. §18's worry about `verdictHeadline` coupling was unfounded.
- **U3 — DEFER "taking longer than usual" (B) with C (CORRECTED §17.6's "B works on a single sample").**
  Warm loads run ~6–11s; cold loads run 30–120s (`waitForServerHealth` timeout 120s; tempdoc 369: 9B cold
  >30s). With that warm↔cold bimodality (~3–10×), "taking longer than usual" derived from a single-sample (or
  even a naive median) typical **frequently false-fires** on a cold load following a warm one (30s is normal
  for cold, yet flags at ~2×6s). Making B honest needs cold/warm-aware history or a conservative absolute
  floor — more structure than the present problem warrants. **So B is deferred together with C.** The honest
  core needs no typical: **A (the measured count-up) is always truthful on its own**; the tooltip keeps the
  shipped static "usually ~Ns" (an acceptable soft hint).
- **U4 — start-signal is clean (CONFIRMED).** Sequence: `offline` → (self-test on a *separate probe port*,
  manager stays `offline`, ~5–30s) → **one** `transitioning`/`starting` window (set once in `runner.run`,
  held through `startLlamaServer`+`waitForServerHealth`) → `online`. No mid-load flap; failure rolls back to
  `offline` cleanly; a retry is a fresh window; crash-recovery never shows 'starting'. Latch on first
  'starting', reset on leaving. Caveat (acceptable): the 5s poll means the captured start and the
  completion are each detected up to ~5s late, and a fast warm load (~6s) may show only 0–1 'starting'
  polls — fine, fast loads don't need a counter (NN/g: <10s is low-stakes).
- **U5 — humanizer consolidation is output-preserving (CONFIRMED).** A shared `humanizeSeconds(ms, {floor})`
  serves all three, preserving each call site's differences: the floor (`Math.max(1,…)` for estimate/
  reasoning vs `0` for the thinking gate), the `>2s` suppression (caller-side), and the `~` prefix
  (caller-side, estimate only).

### 19.1 Net effect on the remaining work
The honest, scope-matched **core shrinks to A + D**: a measured **"Starting… Ns"** on the status pill
(computed in `computeStatusLabel` from a store-captured `loadStartedAtMs`, refreshing on the existing 5s
clock — optional 1s tick for smoothness) + the `humanizeSeconds` extraction. The tooltip/toast/BrainSurface
keep the **shipped static "usually ~Ns"**. **B (taking-longer) joins C (median), E (worker-restart), and the
backend epoch in the deferred set** — B specifically because it cannot be made honest from the data the
present problem has. No backend change, no new fetch, no new timer required for the core.

### 19.2 Critical confidence rating for the remaining work: **8.5 / 10**
High. The core (A + D) is small, precedent-matching (`computeStatusLabel` already computes a live elapsed
from a captured timestamp), gate-clean (U2), and fed by a clean start-signal (U4); the risky piece (B) was
identified as un-honest from current data and deferred (U3), which removes the main correctness hazard rather
than carrying it into implementation. The residual is minor: a trivial design pick (5s-step vs an optional 1s
`StatusDeck` tick), the ~5s poll lag (acceptable for an approximate estimate), and reconciling the doc trail
(this §19 supersedes §17.6's store-separation claim and §18's "1s pill tick"). No live-stack verification was
needed — the code traces are dispositive and the browser was unstable this session; a ~10-min live
confirmation that the pill shows "Starting… Ns" during a real activation would lift this to ~9.5.

## 20. As-built — the live "Starting… Ns" count-up (implemented, 2026-06-17)

Implemented the §19-narrowed core (A + D); B/C/E and the backend epoch stay deferred per §17.6/§19.

### 20.1 Changes
- **D — shared formatter** (`startupEstimate.ts`): new `humanizeSeconds(seconds, {floor})` = the
  `"Ns" / "Nm Ns"` core (no prefix); `formatStartupEstimate` refactored to `'~' + humanizeSeconds(…,
  {floor:1})` — output-preserving (existing tests unchanged). `thinking`/`ReasoningController` untouched.
- **A — live elapsed** (`aiStateStore.ts`): a new module signal `loadStartedAtSig` is stamped on the
  `starting` edge by a shared `ingestInferenceSnapshot` (called by both the inference poll and
  `__feedForTest`, so the capture runs in tests); `computeStatusLabel` gains a `loadStartedAtMs` param and
  the `'starting'` branch now returns `Starting… ${humanizeSeconds(elapsed)}` (elapsed = now − start,
  measured at render, `>2s` gate like "thinking") instead of a bare "Starting…". `buildSnapshot` passes
  `loadStartedAtSig.get()`. **No new timer, no backend, no new fetch** — it rides the existing 5s store
  clock (updates in ~5s steps); the elapsed is a static-timestamp + render-time `Date.now()` (no store-wide
  ticking), preserving the 557 observed-state/presentation cut.
- The tooltip/toast/BrainSurface keep the shipped static "usually ~Ns" (unchanged).

### 20.2 Verification
Static (all green): `npm run typecheck` clean; `npm run test:unit:run` **329 files / 3161 tests** (incl.
new `humanizeSeconds` cases + a store test pinning `"Starting…"` (<2s) → `"Starting… 12s"` →
`"Starting… 1m 30s"` (minute-aware) → cleared-on-leave); `check-presentation-purity` /
`check-verdict-derivation` / `check-observed-state-collapse` OK; `./gradlew.bat build -x test` SUCCESSFUL.
Live (worktree FE in demo, `__feedForTest` 'starting'): the **always-visible status pill** rendered
**`"Starting… 46s"`** in the live DOM and a screenshot caught it advancing to **`"Starting… 57s"`** — a
genuine live measured count-up on the pill (the `:5173`/`:5180` stale Vites do not serve this code; a fresh
`:5193` Vite on the worktree code does).
Pre-existing note: 601's branch realigned three tempdoc-599 class-size pins (`RemoteKnowledgeClient`/
`SqliteJobQueue`/`GrpcIngestService`) that 599's merge had grown past their pins without a changeset
(`main` was red on `class-size`); logged to observations.md.

### 20.3 Addendum — BrainSurface now shows BOTH (the §18 gap closed, 2026-06-17)
A conceptual re-analysis found the one real deviation from §18's surface mapping: the live count-up shipped
only on the status pill, leaving **BrainSurface static** ("AI is initializing. Usually takes ~Ns") where §18's
table planned it to show **both**. Closed here:
- `loadStartedAtMs` is now exposed on `AiRuntime` (set in `computeRuntime` from `loadStartedAtSig`), so the
  pill and BrainSurface read ONE source; `computeStatusLabel` reads `runtime.loadStartedAtMs` (its 4th param
  dropped). A shared `elapsedSecondsSince` single-sources the elapsed math across both surfaces.
- `BrainSurface.formatRestartEtaSub(lastStartupDurationMs, loadStartedAtMs?)` now renders, past the `>2s`
  gate, **"AI is initializing — 12s (usually ~6s)"** (elapsed + typical), or **"AI is initializing — 12s"**
  when there's no prior duration (unknown arm, no fabricated typical); below the gate / with no stamp it is
  byte-identical to the prior static copy (existing call sites + tests unchanged).
- Verified: typecheck; full FE suite **329 files / 3166 tests** (incl. new BrainSurface §20 + `elapsedSecondsSince`
  cases); FE gates + `build -x test` green. **Live (worktree FE demo):** BrainSurface rendered
  **"AI is initializing — 23s (usually ~6s)"** in the DOM + a screenshot (card "Starting…" / sub, pill
  "Starting… 23s" consistent).
- **Still deferred (reaffirmed):** #2 confidence-scaled wording ("usually" stands — §9 endorses it; the
  "last time" refinement rides the deferred median C) and B "taking longer than usual" (§19/U3: not honest
  from a single sample). With this, the tempdoc's design (§7/§9/§12/§18) is fully realized for the model-load
  estimate; C/E/backend-epoch remain the only explicitly-deferred extensions.
