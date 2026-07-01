---
title: "AI-engine state — the brain surface as the fourth depth-round sibling (594 Display / 595 Observed-state / 596 Operability / 601 Progress), not a bespoke verdict; align the wire with the backend's own InferenceRuntimeView rather than reconstructing it FE-side"
type: tempdoc
status: "IMPLEMENTED (2026-07-01, uncommitted in worktree worktree-663-ai-engine-verdict) — the core self-healing fix, the ai-verdict-derivation gate, 594 Fact adoption, 596 Availability adoption, the backend wire-typing fix, Design pass 2's correction (store-computed shared `AiState.aiEngine`, consumed Stability projection, stale-poll resilience on the already-installed axis), AND Design pass 3 (the AI-engine presentation projection + a completion/failure toast + aria-live announcement + corrected pill click-routing, closing all four 'Future work' findings) are all built, unit-tested, and (where live-verifiable in this environment) live-verified. See '## Design pass 3 — implemented' for what shipped and the one disclosed, unchanged verification gap (the toast/announcer's live behavior requires a genuine installed AI, unavailable in this environment throughout every pass)."
created: 2026-06-30
updated: 2026-07-01
related:
  - 649-connection-truthfulness-under-load   # parent: shared root (FE state-truthfulness) + shared poller
  - 595                                        # the Observed-state/verdict depth round; sibling, not merge target
  - 594                                        # the Display/Fact depth round; owns leaf facts (model, GPU, variant)
  - 596                                        # the Operability/Availability depth round; owns why install/activate is blocked
  - 601                                        # the model-load progress/ETA seam; already correctly scoped, reuse as-is
  - 627-process-supervision-crash-recovery   # the backend's own runtime-state consolidation (InferenceRuntimeView, tempdoc 518 P2) this doc's design leans on
---

# 663. AI-engine verdict — single-authority state for the brain surface

> **Spun out of 649 ("Related finding").** 649 found the AI-engine (brain) state surface is the FE's most
> fragile, sharing 649's root theme (FE state-truthfulness) and the *same* timeout-less poller. 649 recorded
> the diagnosis + recommended direction but explicitly left the full fix to its own tempdoc. This is it.
> 649's connection fix and this are **separable** (neither depends on the other). Diagnosis carried from 649
> §"Related finding" — verify against `main` before trusting (649 is the more-current source).

## The problem

The brain is the **one major subsystem deliberately left outside the FE's anti-drift kernel.** Search/retrieval
health was collapsed onto a single `SystemHealthVerdict` every surface merely consumes (595, `explanation/27`),
gated against re-derivation. The AI/inference component was **explicitly excluded** (`state/verdict.ts:142-143`:
"the AI (inference) component is intentionally EXCLUDED … surfaced by its own AI-Engine card").

That exclusion left the brain doing by hand what every other surface gets for free → five compounding
fragilities (evidence from 649):

1. **`BrainSurface.deriveAiState()` reconciles ~5 overlapping state representations** by a hand-ordered
   precedence ladder (`views/BrainSurface.ts:1014-1047`): `installStatus`, local `busy[...]` flags,
   `runtimeStatus.onnxFeatures[].modelActive`, `_unifiedAiState.runtime`, and a separate raw `inference`
   snapshot. A comment admits the drift it fights — the representation-drift class the kernel makes unwritable
   elsewhere.
2. **Fed by four self-owned poll timers** (`pollInstall`/`pollPack`/`pollRuntime`/`pollDiagnostics`,
   `:270-273`) **plus** shared `subscribeAiState` (`:520`) **plus** on-mount `fetch`es — each independently
   stale-able. `installStatus` alone has 6 write-sites — every combination a potential wrong-state render.
3. **The most states / most complex lifecycle of any subsystem** (`not_installed → installing → offline →
   starting → connecting → online`, + `indexing`/`transitioning`, × per-variant activate/deactivate, GPU/VRAM,
   restart-ETA) — because it fronts an **external native process** (`llama-server.exe`) with far more failure
   modes than the in-process Lucene worker.
4. **Weakly-typed wire boundary:** the raw inference `mode` is an untyped `string` (`utils/inferencePoll.ts:13`)
   and `BrainSurface` branches on `mode === 'transitioning'` (`:1040`), a value absent from the typed runtime
   union `'offline'|'online'|'indexing'|'starting'|'unknown'` (`state/aiStateStore.ts:72`) — vocabulary drift
   escapes the compiler.
5. **Inherits 649's connection bug** — `inferencePoll` is one of the two timeout-less pollers that starve under
   the connection-pool exhaustion (`utils/inferencePoll.ts:48`), so a starved poll can make the brain read "AI
   Offline / Connecting…" wrongly for the same reason 649 made the header read "Reconnecting…".

Corroboration: `UnifiedChatView.ts` (~4302 lines) and `BrainSurface.ts` (~2051) are the two largest FE view
files — both AI-facing. (Honesty caveat from 649: this public mirror is squashed, so "breaks most frequently"
is argued structurally, not from git churn — but the design history keeps revisiting this surface:
518/586/601/604/627/630.)

## Judgment — re-architect one seam, do NOT rewrite

A "rewrite the brain" is the wrong, riskier tool:
- The fragility is **concentrated** in the state-reconciliation seam, not the render/install/variant code
  (downstream-but-correct).
- A rewrite **discards embedded edge-case knowledge** the precedence ladder encodes
  (runtime-active-while-install-stale, no-data≠not-installed, restart-ETA window, variant handling) — the
  classic second-system regression.
- The fix **already exists as a pattern** (the single-verdict kernel) — invent nothing.
- "Rewrite" slides into rewriting the two largest FE files on shared `main` — large blast radius for a bounded
  defect.

## Recommended direction (state/health layer only)

1. **Add an `aiEngineVerdict` authority** parallel to the search verdict — one typed place that folds install
   state + runtime mode + inference snapshot into a single AI-engine state. Type the boundary (kill the
   `mode: string` / `'transitioning'` drift; extend the runtime union or map at the edge).
2. **Make `BrainSurface` a pure consumer** — delete `deriveAiState()`'s 5-source ladder; collapse the four
   self-owned poll timers into the store.
3. **Keep the render markup** mostly as-is — once state is single-source it barely changes.
4. **Gate it** — extend the `verdict-derivation` gate so the AI verdict can't be re-forked.

**The key design insight (makes this a clean refactor, not a philosophical reopening):** the original 595
exclusion conflated *"don't raise a health alarm when AI is off"* (correct — AI is offline-by-design) with
*"don't give AI a single state authority"* (the actual mistake). A **calm-by-default, single-source AI
verdict** — one that never alarms but is single-authority — satisfies both. This is the lens that keeps the
work bounded.

## Scope
- **IN:** the `aiEngineVerdict` authority + typed boundary; `BrainSurface` (and other AI-mode consumers like
  the status pill) become pure consumers; the `verdict-derivation` gate extension.
- **OUT:** the render/markup, install/download/variant/GPU UI (correct as-is); the connection-pool resource
  fix (→ tempdoc 662); 649's reachability layer (shipped).
- **Interaction to preserve:** the `inferencePoll` timeout-less-poller starvation is shared with 649 and
  addressed structurally by 662 (consolidation) — this doc should make the AI verdict *resilient to* a starved
  poll (calm "checking", not false "offline"), mirroring 649's positive-contact treatment.

## Verification (when built)
- A regression test: a stale/failed `inferencePoll` does NOT flip the AI verdict to a confident "offline"
  (mirrors 649's anti-false-alarm test).
- `BrainSurface` renders solely from `aiEngineVerdict` (no `deriveAiState` ladder; the `verdict-derivation`
  gate fails on a re-fork).
- The typed boundary: no `mode: string` / unlisted `'transitioning'` literal escapes the compiler.

## Investigation (2026-07-01)

Autonomous pass — verify the diagnosis against shipped `main`, correct stale details, and critique the
recommended direction for shape/feasibility. **No solution chosen** (design still deferred); evidence +
critique only. All `file:line` against this worktree's checkout of `main` (`worktree-663-ai-engine-verdict`,
based on `84b305b`).

### A. The five fragilities are confirmed at the source, line numbers hold

`BrainSurface.ts` has not been touched since the initial public-mirror commit (`29579e5`), so the doc's cited
lines are current:

- **#1 (5-source ladder)** — `deriveAiState()` at `BrainSurface.ts:1014-1047` is exactly as described: it
  reads `this.installStatus`, `this.busy['install-start']`/`this.busy['inference-switch']`,
  `this.runtimeStatus?.onnxFeatures[].modelActive`, `this._unifiedAiState?.runtime?.mode`, and the raw
  `this.inference?.mode`/`.starting` — five sources, hand-ordered.
- **#2 (write-sites)** — `installStatus` has exactly 6 write-sites: `:281` (init), `:595` (on-mount fetch),
  `:634` (poll callback), `:829/:837/:853` (diagnostics-poll branches). Confirmed by direct grep, not just the
  doc's count.
- **#4 (typed-boundary drift) — confirmed, and it is not just a type-safety nit, it is a real data-loss path.**
  `inferencePoll.ts:13` still declares `mode?: string` (untouched since `29579e5`) and `:48` is still a bare
  `fetch(...)` with no `AbortController`/timeout — both exactly as 649 described for the sibling
  `statusPoll.ts`. But `aiStateStore.ts:355-363` (`computeRuntime`) — the ONE place that narrows the raw string
  into the typed `AiRuntime['mode']` union (`'offline'|'online'|'indexing'|'starting'|'unknown'`, `:89`) —
  **silently maps `mode === 'transitioning'` to `'unknown'`** (no branch matches it, so `resolvedMode` stays
  its `'unknown'` default). That is why `BrainSurface.ts:1040` cannot use `_unifiedAiState.runtime.mode` for
  this check and instead reads the raw untyped `this.inference?.mode === 'transitioning'` — the typed
  projection is *lossy*, not merely permissive. An `aiEngineVerdict` fix must add `'transitioning'` as a real
  member of the narrowed union (or an equivalent typed cause), not just tighten the wire type — otherwise the
  new authority reproduces the same loss and BrainSurface has to keep bypassing it.
- **#5 (shared timeout-less poller) — status changed since 649/this doc were written.** `git log` shows tempdoc
  **662 shipped today** (`a9694aa1`, 2026-07-01, "Managed connection budget — multiplex 5 always-on SSE streams
  onto one connection"), confirmed in source: `streaming/MultiplexedStream.ts`,
  `streaming/shellEventsMultiplexInstance.ts`, and `ShellEventsStreamController.java` all exist and are wired
  (`explanation/27:73` already documents this as shipped, though **662's own tempdoc frontmatter still says
  `status: open`** — a stale-status mismatch worth fixing when 662 is next touched, not here). Consequently the
  5-SSE-stream connection-pool pressure that made `inferencePoll`'s starvation *routine* under load (649's
  finding) is now rare. The underlying defect is not gone, though: `inferencePoll.ts:48` genuinely still has no
  request timeout, so a rare residual starvation (a burst of concurrent user-initiated fetches, e.g.) could
  still stall it silently. Net effect: **this doc's item under "Interaction to preserve" (resilience to a
  starved poll) is still correct to keep in scope, but the urgency argument weakens** — it is now a
  defense-in-depth requirement, not a routine-reproduction one.

### B. Correction — the brain is not entirely outside the anti-drift kernel; a *coarse*, partially-dead parallel projection already exists

The doc's framing ("the AI-engine … is the one major subsystem deliberately left outside the FE's anti-drift
kernel") is accurate for **BrainSurface**, but overstates the gap elsewhere:

- `aiStateStore.ts` already derives a single `AiRuntime` (`computeRuntime`, `:355-380`) from the *same* shared
  `subscribeInference` poller BrainSurface mirrors (`:685`, `:759-761` — one poller, not two; `BrainSurface`
  does not run a second inference poll). `computeStatusLabel`/`computeStatusTone` (`:382-429`, `:522-…`) derive
  a single label/tone from `(verdict, runtime, activity)`, and **`StatusDeck.ts` already consumes that
  single-sourced label/tone** rather than re-deriving it — i.e. the doc's Scope line "other AI-mode consumers
  like the status pill … become pure consumers" is **already true for the status pill today**; it does not need
  to be done as part of this work, only preserved. `UnifiedChatView.ts` was checked directly (grep for the
  ladder patterns) and contains no parallel AI-state derivation either — its size is chat-rendering complexity,
  not a second broken consumer.
- **The gap is real but narrower than framed: `AiRuntime` is a genuine parallel authority, but it is
  install-blind by construction and dead on that axis in production.** `computeRuntime()` calls
  `mapKnown(installState, …)` against `installStateSig`, and `aiStateStore.ts:799-801` exports
  `setInstallState(installed, installing)` to write it — but a repo-wide grep found **zero production call
  sites** for `setInstallState`; the only callers are `aiStateStore.test.ts`. So `installStateSig` is `UNKNOWN`
  forever in the running app, and `AiRuntime.installed`/`.installing` are always the unknown sentinel outside
  tests. This means **item 1 ("add an `aiEngineVerdict` authority") is not a from-scratch build** — a coarse,
  half-wired sibling authority already exists in `aiStateStore`. The real first step is smaller and more
  concrete than "invent a new authority": (a) decide whether `aiEngineVerdict` extends/replaces `AiRuntime` or
  is a new derived value that also consumes it, and (b) wire the install signal into whichever authority wins
  — either by finally calling `setInstallState` from the shared install poll, or by moving the install poll
  itself into `aiStateStore` (which is closer to what `deriveAiState()`'s comment already assumes when it says
  "the runtime may be active even if install status is stale" — it is reasoning about two sources it already
  half-trusts).

### C. Critical analysis — a single calm/alarm *verdict* enum is the wrong shape for what `BrainSurface` renders; the 594/595 split is the right precedent to reuse, not just the 595 half

The doc frames this as "apply the 595 single-verdict kernel to the brain," but 595's `SystemHealthVerdict` is a
**severity rollup** (ready / degraded / unreachable / transitioning) designed to answer one question ("is the
system healthy, and how alarmed should the user be?"). `BrainSurface` does not primarily need that question
answered — it needs to render **rich, multi-field detail**: install phase/progress, per-variant
(`cuda12`/`default`) activation state, GPU/VRAM description, restart-ETA, context-window size, model label.
None of that is expressible as a severity enum; a verdict-shaped fix would still leave `BrainSurface` needing a
*second* structure for the facts, recreating the ladder one level down.

The codebase already has the right precedent for this, and it is not 595 alone: `explanation/27`'s authority
table draws exactly this line for a different domain — **`display/present.ts`/`display/facts.ts` (594, the
Display/VALUE authority for a leaf fact) vs `verdict.ts` (595, the derived rollup VERDICT)** — "a thing's
displayed NAME/VALUE" is a separate authority from "is the system healthy," and 594 explicitly does not
re-derive 595's verdict. The same split should apply here: an **`aiEngineState`** (the rich, single-sourced
projection of every fact `BrainSurface` needs — install phase, variant, progress, GPU, restart-ETA, model,
context window; the 594-shaped half) with an **`aiEngineVerdict`** derived *from* it (the calm/alarm severity
projection the status pill / capability map / any surface that only needs "is AI okay" would consume; the
595-shaped half). Recommend the eventual design pass frame item 1 as two artifacts, not one, both single-source
but at different altitudes — this keeps `BrainSurface`'s legitimate need for rich detail from pressuring the
calm-by-default verdict into carrying fields it was never meant to hold.

### D. The `verdict-derivation` gate's enforcement is unconfirmed from this checkout — a caveat for item 4, not a blocker

`governance/verdict-derivation.v1.json` + `scripts/ci/check-verdict-derivation.mjs` exist and are internally
coherent (seam = `computeVerdict` in `verdict.ts`, single-predicate scan over `retrieval\s*[=!]==`). But a
repo-wide search for its invocation — `.github/workflows/*.yml`, `modules/ui-web/package.json` scripts, and
`*.gradle.kts` — found **no call site**; the only matches are the script's own definition and doc/comment
mentions (including 595's own frontmatter claiming "the gate is live"). This may be a self-hosted/manual lane
outside this public mirror's `ci.yml` (per ADR-0044, "self-hosted/specialty workflows remain manual"), or it
may be an orphaned check that runs nowhere. Logged to the observations inbox rather than chased further here
(out of this doc's scope to resolve) — but item 4 ("extend the verdict-derivation gate") should confirm the
gate actually fires before relying on it as enforcement, and if it doesn't, wiring it in is itself a
prerequisite, not a detail. Note also the register's schema (`governance/verdict-derivation.v1.json`) is
single-seam/single-predicate (one `seam` object, one `predicatePattern` string) — extending it for an AI
verdict needs either a schema change to support multiple seams, or a sibling register file, since the current
shape cannot express two independently-guarded predicates.

### E. Additional evidence — the lifecycle has uncoordinated axes beyond the six-state enum

Two more facts render outside `deriveAiState()`'s six-value return, confirming the ladder undercounts the real
state space rather than just mis-ordering it:

- **Failure is a parallel, un-typed string, not a lifecycle state.** `runtimeError: string | null`
  (`BrainSurface.ts:256`) is set from ~8 independent call sites and rendered as a dismissable banner
  (`:950-958`), while `installStatus?.state === 'failed'` is checked *separately* at render time (`:1231`).
  Neither folds into the six-value enum — a failed install or a runtime error is not one of
  `not_installed/installing/offline/starting/connecting/online`; it is an ad-hoc overlay on top of whichever of
  those six the ladder happened to land on.
- **Restart-ETA is a seventh derived fact**, computed by a standalone module function
  (`formatRestartEtaSub`, `:173`) from `runtime.lastStartupDurationMs`, layered onto the `'starting'` branch at
  render time rather than being part of the state value itself.

This means the six-state enum was never a complete model of "what BrainSurface needs to know" — it was the
*visible* fraction, with failure, error text, and ETA bolted on separately. A design that treats the ladder's
current return type as the target shape for the new authority would carry this incompleteness forward.

### F. Reframings — naming the problem differently pre-selects the fix

- **Cross-product, not a flat enum.** The six-state ladder implicitly encodes two independent axes as if they
  were one dimension: *is the engine installed* (rarely changes; a mostly-static fact) and *is the process
  currently running/healthy* (changes frequently; the volatile fact). `not_installed → installing` is really
  install-axis movement; `offline → starting → connecting → online` is really runtime-axis movement. Modelled
  as `installLifecycle × runtimeLifecycle`, most of the ladder's ordering logic (which source wins when they
  disagree) stops being an ad-hoc precedence rule and becomes "read two independent facts, render their
  cross-product" — closer to how 595 already separates a value from its stability.
- **A supervised-external-process problem, not a status-fetching problem.** The brain fronts an actual OS
  process (`llama-server.exe`, managed on the Head by `InferenceLifecycleManager`) — structurally closer to a
  process supervisor's domain (systemd-unit state, a Kubernetes reconciliation loop, a lease) than to the
  Worker's in-process gRPC calls. Read this way, the FE's four polls + one subscription are a *client-side*
  reconstruction of a state machine the backend process supervisor already has to track internally to do its
  job (install → download → activate → load → ready, with retries and crash-restart). The FE-only framing this
  doc deliberately chose ("state/health layer only") is still the right bounded first cut, but it is worth
  naming explicitly: the FE authority will always be a *reconstruction*, and some of the reconciliation cost
  this doc assigns to the FE might instead belong to a backend endpoint that already emits one coherent
  snapshot instead of four independently-timed ones — a candidate for a later, backend-facing follow-up rather
  than something to fold into this doc's scope.
- **A liveness problem the codebase already solved once, one layer up.** 649/662 established that "is the
  origin reachable" should come from *any* positive contact (a poll success **or** any live-channel heartbeat),
  registered once (`state/originContact.ts`, the `connection` domain in
  `governance/inflight-liveness-projections.v1.json`) and consumed everywhere. The AI verdict's own
  "resilient to a starved poll" requirement (this doc's "Interaction to preserve") is the same fact, not a new
  one — the natural reading is that `aiEngineVerdict`'s calm-under-starvation behavior should key off the
  *existing* `aiStateStore.connection.reachable` signal rather than invent an AI-specific staleness rule that
  would have to independently relearn 649's lesson.

### G. Wider solution space — directions beyond "one derived value computed from current signals"

- **Event-pushed state instead of polled-and-reconciled.** 662 (shipped today) proved the pattern of collapsing
  many independently-polled/streamed things onto one already-open multiplexed channel
  (`/api/shell-events/stream`). The AI engine's install/runtime/pack status *could* join that channel as
  additional event kinds, so the FE never polls for them at all — it subscribes once and receives authoritative
  pushed transitions. This would solve the starved-poll-resilience requirement structurally (there is no poll
  to starve) rather than by a calm-fallback rule, mirroring how 662 removed the *cause* of 649's problem rather
  than only treating the symptom. This is a materially larger change (new backend event kinds, publisher
  wiring) and probably belongs to its own follow-up rather than this doc's "state/health layer only" scope —
  recorded here as a direction worth naming, not a recommendation to pursue now.
- **A real state machine with transition memory, vs. a pure function of current signals.** `computeVerdict`
  (595, for search) is a pure derivation: recompute from the current snapshot every time, no memory of how the
  system got there. That fits search's comparatively simple readiness model. The AI engine's lifecycle has
  genuine *sequencing* — install, then download, then activate, then load, each with its own possible failure
  and retry — which a stateless "derive from current snapshot" function can under-model (e.g., distinguishing
  "still installing" from "installed, now reconnecting" from two snapshots that momentarily look similar). The
  design pass should treat "pure derivation" vs. "explicit state machine with guarded transitions" as a real
  fork, not assume the 595 shape transfers unchanged — the current ladder's own comment
  ("the runtime may be active even if install status is stale") is itself evidence that *some* transition
  memory is already load-bearing, informally.
- **Push the typed-boundary fix up the prevention ladder.** `explanation/27`'s ladder ranks fixes Collapse >
  Generate > Gate by strength. This doc's item 1 ("type the boundary") as written is an FE-side fix — a
  hand-maintained TypeScript union that must remember to include every value the backend can emit. If the
  backend's inference-status payload is (or could be) part of the schema-generation pipeline that already
  produces cross-language fixtures for other API records, the stronger fix is a Generate-rung one: the
  `mode` field becomes a generated enum end-to-end, and an unlisted value becomes a compile-time impossibility
  on both sides rather than something the FE authority must remember to widen. Worth checking, at design time,
  whether the inference-status payload is already inside that codegen boundary or sits outside it as a
  hand-cast JSON shape (this investigation did not verify which).
- **Reuse 595's `Stability`/`ProvisionalCause` vocabulary instead of a parallel one.** 595 designs a general
  `settled | provisional·cause` axis with causes like `initial-load`, `rebuilding`, `generation-switch`,
  `worker-restart`. AI's `installing`/`starting`/restarting states are additional instances of the same shape
  (a bounded operation in flight that will resolve to a settled value) — a model reload that swaps
  `activeModelId` is structurally the same event as search's `generation-switch`. Extending the *same* causal
  vocabulary (rather than inventing an AI-specific one) would make "is anything in the app currently mid-
  transition, and why" answerable from one place across both search and AI. The tension: 595's own frontmatter
  marks the general `Stability` axis as still not fully built (only the search-specific pieces shipped), so
  building the AI verdict on top of it means depending on unfinished shared substrate rather than a smaller,
  independent AI-specific type. Both are legitimate; which is right depends on how far along 595's axis is by
  the time this doc's design is scheduled — worth re-checking against `main` at that point rather than assuming
  either way now.

### H. Hidden assumptions and risks worth naming before the design is settled

- **"Keep the render markup mostly as-is" could quietly anchor the new authority's shape to the old ladder's
  gaps.** §E above shows the current six-value return type is already incomplete (failure and ETA live outside
  it). If the new authority is shaped to match `deriveAiState()`'s existing return value for a low-diff render,
  it risks re-encoding the same omissions under a new name. The render can stay visually the same while the
  *type* backing it is more complete — the two are separable goals and should not be conflated for the sake of
  a smaller diff.
- **A single authority does not by itself prevent a new, larger god-object.** Consolidation removes the
  *distributed* drift risk (five sources disagreeing) but introduces a *concentration* risk if the new
  authority becomes one large derivation function no one wants to touch, mirroring `BrainSurface.ts`'s own
  2000-line size. Whatever shape the design settles on should keep the install-axis, runtime-axis, and
  error/ETA facts as separately-reasoned-about fields even while being single-sourced, rather than merging them
  into one flat computed string.
- **"Calm-by-default, single-authority" may be a reusable pattern beyond AI, but is not yet proven as one.**
  This doc's key move — separating "don't alarm when a component is optionally off" from "don't give it a
  single authority" — resembles a problem already visible elsewhere (595 notes an optional, unconfigured
  re-ranker currently causing an over-alarmed "degraded" verdict for a cosmetic gap). Whether that is the same
  underlying pattern (an "optional component" tone-suppression rule, reusable beyond AI) or a coincidence is
  worth naming as a candidate principle to watch for, without generalizing now — a second concrete instance
  earning the same fix independently would be the right trigger to factor it out, not this observation alone.

### I. Net read on the recommended direction

The four-step direction (add the authority, make `BrainSurface` a pure consumer, keep markup, gate it) is still
the right shape and the re-architect-not-rewrite judgment holds — nothing found here argues for a different
strategy. The corrections narrow and de-risk the work: item 1 is partly already built (dead install-wiring to
finish, not a new authority to invent from nothing), item 3's "other AI-mode consumers" is partly already done
(the status pill), and item 5's urgency is reduced by 662 shipping today (though the underlying timeout-less
poller is still unfixed and should stay in scope as defense-in-depth). The one real addition this investigation
surfaces: split item 1 into a rich `aiEngineState` (594-shaped) + a derived `aiEngineVerdict` (595-shaped)
rather than one verdict-shaped authority, so `BrainSurface`'s legitimate need for install/variant/GPU/ETA detail
has a home that isn't the calm/alarm severity axis.

## Design pass (2026-07-01)

> Genre: design-theory in the 557/594/595/596/601 line — state the correct end-state, reuse what already
> exists, size the change to what the problem requires. Read-only investigation against `main`; no
> implementation started. This section supersedes the "Theorization" section's open framings with a settled
> shape, in the same way 601 §5-6 corrected its own first draft after reading the adjacent cluster.

### J. What already exists that this design should extend, not re-derive

The FE presentation kernel (`explanation/27`) is not one authority — it is 557's three projections
(Display / Observed-state / Operability) plus their depth-round completions, each shipped as its own
tempdoc: **594** (Display → the Fact authority: `name, value, presence, confidence`, projected via
`projectFact`), **595** (Observed-state → the verdict/Stability authority), **596** (Operability →
typed `Availability = available | blocked | unavailable{reason, transient}`, projected via
`unavailableBecause`/`projectAvailability`), **601** (a narrow, correctly-scoped progress/ETA seam that
*extends* 596 rather than generalizing into a `Progress` framework), and **608** (the acknowledgement/busy
axis). All five are implemented and merged. `BrainSurface` is **already a partial adopter**, not a
green-field site: it imports `unavailableBecause`/`reasonFor` from the Operability/reason authorities
(`BrainSurface.ts:35,39`) and consumes the 601 estimate seam (`formatRestartEtaSub`, `:173-185`, sharing the
*number* with `projectAvailability` per its own doc comment). What it does **not** do is route its core
lifecycle value — `deriveAiState()`'s six-state return — through an equivalent single authority; that is the
one depth round this family never received.

This reframes the correct design: it is not "invent an `aiEngineVerdict`," it is **"complete `BrainSurface`'s
adoption of the same four-authority family every other complex surface already composes, and add the one
authority that family doesn't yet have — an Observed-state/Stability projection for the AI engine, since
595's is deliberately search-scoped."** Recommend against a bespoke, BrainSurface-only `aiEngineState` +
`aiEngineVerdict` pair invented independently of 594/595/596/601's shapes; instead:

- **Leaf facts** (model label, context window, GPU description, active variant, download bytes/total) →
  project through **594's Fact authority** (`projectFact`) the same way any other runtime/build fact does,
  rather than the ad hoc formatting `BrainSurface` currently does inline. (`594`'s catalog today is scoped to
  host-capability/build facts; extending it to cover inference-runtime facts like `activeModelId` is new
  *catalog rows*, not a new authority — check at implementation time whether the catalog's fact-source
  abstraction already reaches `aiStateStore.runtime` or needs a small extension.)
- **Is a control usable right now, and why not** (install/download/switch-variant/activate buttons) → extend
  **596's Availability authority**, the same one `BrainSurface` already partially uses for the refresh button.
  Today `busy['install-start']`/`busy['inference-switch']` are ad hoc booleans checked inline in
  `deriveAiState()`; they are exactly the shape 596 exists to type (`unavailable{reason, transient}` vs a bare
  boolean).
- **Time-to-availability while starting** → **601's seam, unchanged.** It is already correctly narrow
  ("one small estimate seam, not a progress framework" — 601 §6) and download progress is a *different*,
  already-honest signal (determinate `bytesDownloaded/bytesTotal`, not an estimate) computed inline from real
  data — it does not need 601's estimate treatment and does not yet have ≥2 consumers, so per AHA it does not
  need its own shared seam either; leave it inline unless a second consumer appears.
- **Is the engine settled or mid-transition, and calmly why** → the one genuinely new piece, §K below.

### K. The one new authority: an AI-engine Observed-state projection, shaped like 595's sibling, not merged into it

595's `SystemHealthVerdict` deliberately excludes AI (`verdict.ts:164-166`) — correctly: AI is optional and
offline-by-design, so folding it into the *alarm* axis would make the whole-system verdict over-alarm for a
component the user may not have installed. That exclusion should **stay**. What 595 never built for AI is a
**structurally equivalent, separately-scoped Observed-state projection** — the same shape (a derived, single-
sourced "is this settled or provisional, and why" value), calm-by-default, but its own instance rather than a
branch inside the system verdict. This mirrors exactly how 594/595/596 already describe themselves as
siblings ("*THE THIRD SIBLING… same 558 shape, three different authorities*" — 596's own frontmatter) — an
AI-engine Observed-state projection is the natural fourth member of that family, not a parallel invention and
not a merge into 595's verdict.

Two axes, cross-producted rather than flattened into one ladder (per the earlier Theorization §F reframing,
now load-bearing for the design): **install lifecycle** (rarely changes; `not_installed → installing →
installed`, with a `failed` terminal the current six-state enum has no slot for — see §L) and **runtime
lifecycle** (frequently changes; `offline → starting → transitioning → online/indexing`). 595's own
`Stability`/`ProvisionalCause` vocabulary (`settled | provisional·cause`, with causes like `initial-load`,
`worker-restart`, `generation-switch`) is the right *shape* to reuse for the runtime axis's provisional causes
— an AI model reload that swaps `activeModelId` is structurally the same kind of event as search's
`generation-switch`. Whether the AI causes become literal new members of 595's shared `ProvisionalCause` union
(cross-subsystem reuse) or a same-shaped-but-separate enum (subsystem-scoped reuse) is a real implementation
choice deferred to the design's next pass — but should default toward reuse given 595's own type is described
as a general axis "beside `ConnectionPhase`," not a search-only one, once 595's general axis substrate is
confirmed still on `main` in the shape this doc's Investigation described (verify before depending on it, per
`tempdocs-are-dated-history`; 595's own frontmatter marks the general axis as not-yet-fully-built).

### L. The wire boundary — this doc's biggest correction: the backend already solved this once, upstream

The Investigation section (§B) found `aiStateStore`'s `AiRuntime` is a coarse, partly-dead parallel projection.
Reading the backend closes the loop on *why* it is coarse: **`modules/app-inference` already has a clean,
single-authority runtime view** (tempdoc 518 P2) that `aiEngineVerdict`'s runtime axis should be a thin,
faithful projection of — not reconstruct independently:

- `InferenceRuntimeView` (`InferenceRuntimeView.java:30-42`) is exactly the "collapse five scattered fields
  into one snapshot" move this doc wants for the frontend, already done on the backend: one record carrying
  `Mode phase`, `RuntimeIdentity identity`, `InferenceFailure lastFailure`, `usingExternalLlamaServer`,
  context/model, and `lastStartupDurationMs`.
- `ModeStateMachine` (`ModeStateMachine.java:22-92`) enforces the transitions explicitly:
  `{OFFLINE,ONLINE,INDEXING} → TRANSITIONING → {target}`, plus a `forceOffline()` escape for crash recovery —
  and **`Mode.TRANSITIONING` is a real, first-class backend state**
  (`modules/app-api/.../Mode.java:22-27`: `ONLINE, INDEXING, TRANSITIONING, OFFLINE`). This resolves the
  Investigation's open question about the `'transitioning'` literal definitively: it is not a stray value the
  FE happens to see, it is one of exactly four backend modes, and the FE's typed union should have four
  members mapping onto it 1:1, not five-minus-one.
- A structured failure authority already exists and is **already unused by the frontend**:
  `InferenceFailureView(code, detail)` (`app-api/.../status/InferenceFailureView.java:19`) backs
  `GET /api/inference/failures` (a ring-buffer of recent failures — `InferenceHandlers.java:258-294`), but a
  repo-wide search found **zero consumers of this route in `modules/ui-web/src`.** `BrainSurface`'s
  `runtimeError: string | null` is set from ~8 scattered call sites (`:724,827,837,851,870,885,910,925`)
  instead — the frontend is hand-rolling an error signal the backend already models more precisely, one route
  away, unread.
- A transition-history authority also already exists and **is already consumed**:
  `GET /api/inference/transitions` backs `BrainSurface`'s diagnostics list (`:685`, doc comment at `:66`) — so
  the "does the FE need memory of how it got here" question the Theorization section raised is already
  answered for one use case; the design should reuse this route for the Stability axis's provisional-cause
  attribution too; instead of a fresh derivation.
- **What is weak is only the wire, not the backend model.** `GET /api/inference/status` is hand-assembled —
  `InferenceHandlers.java:68` calls `response.put("mode", onlineAi.getCurrentMode())` into a raw
  `Map<String,Object>`, not a generated API record — so nothing enforces that the JSON shape tracks
  `InferenceRuntimeView`'s fields, and the untyped `mode: string` on the FE (`inferencePoll.ts:13`) is the
  direct downstream consequence: a hand-built endpoint feeding a hand-typed client field, with no schema
  connecting them. Aligning this wire (whether by having the handler serialize `InferenceRuntimeView`
  directly, or by giving it a real `@RecordBuilder` API record through the existing schema-generation
  pipeline — `api-record` skill / `updateSchemas`) is the Generate-rung fix `explanation/27`'s ladder prefers
  over an FE-side hand-maintained union: the backend's enum becomes the frontend's generated type, and an
  unlisted `mode` value becomes a compile-time impossibility on both sides rather than something the FE
  authority must remember to widen. This is backend + wire-contract work, larger than "FE state/health layer
  only" as originally scoped — recorded as the design's honest scope correction, not something to route
  around by re-deriving `InferenceRuntimeView`'s shape by hand on the FE a second time.
- **This does not move verdict-computation to the backend.** 601 §8 already established the load-bearing
  precedent for this codebase: the backend stays primitive (raw observed facts), the frontend derives verdicts
  and phases from them (`computeVerdict` is FE-side by design, because a backend-computed phase at a 5-10s poll
  cadence is stale before it renders). The wire-alignment recommendation above is about the **primitive facts**
  the backend already emits (`mode`, `lastFailure`, `lastStartupDurationMs`) reaching the FE through a typed
  channel — the AI-engine Observed-state projection (§K) still derives its verdict/stability FE-side from
  those primitives, consistent with 595/601's existing division of labor, not a departure from it.

### M. The install axis has no backend equivalent — real, asymmetric, FE-scoped work

Unlike the runtime axis, `modules/app-services/.../ai/install/` (`AiInstallService` and siblings) has **no**
`InferenceRuntimeView`/`ModeStateMachine`-shaped consolidation — install/download/pack state is read from
several service classes with no single backend view object. This is a genuine asymmetry the design should
name rather than paper over: the runtime axis's fix is mostly "connect the wire to a backend authority that
already exists correctly"; the install axis's fix is "the frontend genuinely is the first place a coherent
single view of install/download/activation state would exist," because the backend doesn't have one either.
Building a matching backend-side consolidation for install (mirroring 518 P2, applied to `AiInstallService`)
is a legitimate future counterpart to this doc's backend recommendation in §L, but is **not required** to
complete this doc's FE-scoped work — the install axis's four write-sites can still be collapsed into one FE-
side derivation reading the existing `/api/ai/install/status` endpoint as-is; it merely means that axis stays
FE-derived-from-primitives (the 601-precedent shape) rather than gaining a Generate-rung backend fix the way
the runtime axis can. Naming it here so a future backend-side install consolidation, if picked up, is
recognized as extending this doc's design rather than as unrelated work.

### N. Judging the design's reach — principle and candidate scope, not new structure

**The design does not introduce a new pattern; it completes an existing one.** The correct shape for
"AI-engine state" is not a bespoke invention — it is `BrainSurface` becoming the **fourth full adopter** of a
family (Display/Fact, Observed-state/Stability, Operability/Availability, Progress/ETA) that 557's depth
rounds already generalized and proved on other surfaces. Recognizing this changes what "done" means for this
doc: success is not a new `aiEngineVerdict.ts` file, it is `BrainSurface` reading `projectFact` +
`unavailableBecause` + a new-but-sibling-shaped Stability projection + the existing 601 seam, the same
composition every other complex surface in `shell-v0` already uses. Conform to the seam; do not parallel it.

**A candidate principle this investigation surfaces, named but not built:** *a subsystem's presentation
authority should be a thin, typed projection of whichever backend authority already models that subsystem's
ground truth — and where the backend has no such authority yet, that absence is itself diagnostic, not just an
FE gap.* The runtime axis (§L) shows the strong case: the backend already consolidated its state
(`InferenceRuntimeView`, tempdoc 518 P2) and the FE's fragility traces to a wire boundary that never caught up
— a Generate-rung fix, not a Gate-rung one. The install axis (§M) shows the weak case: no backend
consolidation exists, so the FE genuinely has to be the first place a coherent view is built. Both are correct
outcomes of the *same* diagnostic question — "does a single-authority view of this subsystem exist anywhere
in the system already?" — applied honestly rather than assumed. **Where else this would apply:** any FE
authority effort should ask this question before assuming the fix is FE-side; the process-supervision domain
(tempdoc 627) already independently reached a structurally similar backend-side conclusion for Body/Brain
crash recovery (*"a declared policy… as a single authority rather than scattered monitor/spawner logic"*),
suggesting the shape recurs at the backend layer too, not only at the FE/backend boundary. **Existing
violation surfaced in passing (not fixed here):** `GET /api/inference/status`/`/api/inference/mode` hand-build
`Map<String,Object>` responses outside the schema-generation pipeline (§L) — two concrete instances of a wire
endpoint bypassing the Generate rung; whether this is widespread across other hand-built Javalin endpoints is
unverified and out of this doc's scope to audit. Per `structural-defects-no-repeat`, these two instances are
enough to name the class; per this doc's own instruction not to build generalized structure prematurely, no
repo-wide wire-typing sweep is proposed here — only that this doc's own runtime-axis fix take the Generate-rung
path rather than a local FE workaround, and that the pattern be watched for elsewhere.

## User-facing design pass (2026-07-01)

> The whole surface under discussion — `BrainSurface`, the status pill, the degradation banner — is
> user-facing by construction; there is no "skip this" case here. This pass live-drove the current shell
> against a real (if minimal) backend rather than judging from the tempdoc's prose or the source alone, per
> `verify-dont-guess`. Setup: a headless eval backend on `127.0.0.1:33221` (no AI installed — the stock
> "fresh install" state) + this worktree's Vite serving `shell-v0` at `localhost:5176`, driven via the Chrome
> extension. No code was changed; findings below are observations against the *current, unmodified* `main`
> behavior, folded into the design already stated in §J-N.

### O. A live-reproduced bug that upgrades §M from an architectural note to a correctness requirement

Loading the AI Brain surface fresh (first mount, no AI installed) produced a genuine stuck state: the panel
showed the calm, correctly-worded **"Connecting… / Checking AI status…"** card with a disabled **"✕
Checking…"** button — and stayed there, unchanged, for 10+ seconds while the rest of the app (the connection
dot, memory/queue counters elsewhere on the shell) kept updating live. The backend was fully healthy the
whole time (`GET /api/ai/install/status` verified to respond in ~0.2s with `{"state":"idle",...}` via a
direct probe run in parallel). The card only advanced — to the correct **"Not Installed" + "Install AI"**
CTA — after a **manual click on the refresh button**. Left alone, it would apparently have stayed on
"Connecting…" indefinitely.

This is the live consequence of the asymmetry named in §M: the runtime axis reads through
`subscribeAiState`/`inferencePoll`, which retries every 5s forever, so it cannot get permanently stuck. The
install axis reads through `BrainSurface`'s own `refreshAll()`, a **fire-once** `Promise.all` called from
`connectedCallback` with no retry — if it doesn't populate `installStatus` the first time (a plausible
mount-order race: `refreshAll()` guards on `this.apiBase` already being set, `:576`), nothing ever asks
again until the user manually triggers it. §M already named this as "real, asymmetric, FE-scoped work";
this live run turns it from an architectural tidiness argument into a **user-facing correctness
requirement**: whatever authority owns the install axis in the eventual design must be built on the same
continuously-retrying primitive the runtime axis already uses (a shared poller/signal), not a one-shot mount
fetch. Framed against §K's calm-by-default principle: **calm is not sufficient on its own — the calm state
must also be self-recovering.** A calm state that never resolves is a worse user experience than an honest
"couldn't reach install status — retry" would be, precisely because "Connecting…" *reads* as if it will
resolve on its own, and here it did not. The design in §L/§M already prescribes the fix (route the install
axis through the same kind of continuously-refreshing primitive as the runtime axis); this section records
*why* that requirement is load-bearing rather than a nice-to-have, and that it should stay explicit in
whatever design ships — "single authority" alone does not guarantee "eventually resolves" unless the
authority's own data source is retried, not fetched once.

### P. Corroborating evidence one surface over (not this doc's scope, logged separately)

The same live run surfaced the identical defect *shape* on a different surface: the System Health page's
red **"Failed to fetch"** banner stayed visible for 10+ seconds while the Memory/Queue counters on the same
page kept updating from later, successful polls — a one-shot caught error that does not clear on a
subsequent success, only on manual dismissal. This is `HealthSurface`, not `BrainSurface`, so it is outside
this doc's scope and is logged to the observations inbox rather than designed here. It is worth naming as
*corroboration*, not as new scope: the "provisional state must be reactively derived, never latched from a
single failed attempt" requirement in §O is not a `BrainSurface`-specific lesson, it appears to be a
recurring shape in this codebase's error-handling — consistent with §N's principle that the fix belongs at
the observation-authority layer, not as a per-surface patch.

### Q. What the live UI confirms is already correct and should be left alone

Matching the tempdoc's original Scope line ("keep the render markup mostly as-is"), several things observed
live are already right and need no redesign:

- **The reachability/AI-state separation already holds visually.** The connection indicator (green,
  "reachable") and the AI runtime pill (amber, "Offline") are already two distinct, correctly-toned signals
  on screen at once — not merged into one confusing badge. This is the visual proof that §K's "don't merge
  the AI verdict into the reachability signal" instinct matches what is already shipped elsewhere; the design
  should preserve this separation, not introduce a new combined indicator.
- **The Operability/Availability vocabulary is already reachable from exactly the right spot.** The disabled
  "✕ Checking…" button during the stuck state is a real, already-typed unavailable control (the primitive
  §J recommends extending to install/activate is not hypothetical — it renders here today, just not yet
  covering every button this doc's scope would want it to).
- **The search-degradation banner (Chat surface) already lists "The local AI model is offline" as one
  bulleted cause among several**, correctly scoped to `readinessNotice`'s existing reason vocabulary (595/600
  territory). This doc's calm-by-default AI verdict must not change that banner's behavior or severity —
  AI being off is one legitimate, already-worded contributor to a *search* degradation, and that is a
  different question from "does the AI panel itself alarm," which is what §K's exclusion protects. Confirmed
  live that these two are already independent today; the design should keep them that way.
- **The install confirmation flow (the "several GB, accept upstream terms" modal before a real download)
  renders correctly** via the existing modal primitive, with no observed issues — out of this doc's scope
  (§ Scope already excludes install/download UI) and confirmed live as a non-concern.

Net effect on the design: §J-N's shape is unchanged by this pass. What changes is emphasis — the install-axis
consolidation in §M is not optional polish, it is the fix for a real, observed "stuck forever" user-facing
bug, and whatever ships should be verified against exactly this repro (fresh mount, no AI installed, watch
the panel for 10+ seconds without touching it) before being called done.

## External research pass (2026-07-01)

Before treating the design as settled, checked which of its assumptions rest on a fast-moving external
dependency vs. established, slow-changing practice — only the former is worth spending a research pass on.
**Scoped out:** the FE single-authority/presentation-kernel pattern (§J-K) and the backend process-
supervision shape (§L, tempdoc 627) are both applied, settled software-engineering practice internal to this
codebase's own prior design history, not an area of active external research — a general web search would
not have changed either. **Scoped in:** two assumptions this design inherits from *llama.cpp* itself
(`llama-server`, the process the "Brain" wraps) — a project with near-daily upstream commits, so a claim
about its API dated 2026-06-17 (tempdoc 601) is exactly the kind of thing worth re-checking against current
upstream rather than trusting as still true.

**Checked against the current `llama.cpp` server README (`ggml-org/llama.cpp`, `tools/server/README.md`,
fetched 2026-07-01):**

- **601's "no progress/ETA signal, estimate-only" design remains correct today.** `/health` exposes only a
  binary-plus-typed-error signal — `HTTP 503 {"error":{"code":503,"message":"Loading model",...}}` while
  loading, `HTTP 200 {"status":"ok"}` once ready — and `/slots` reports live inference activity (tokens,
  sampling params), not load progress. No endpoint, field, or query parameter reports a model-load
  percentage or ETA. This is external confirmation, not just an internal assumption: §L/§M's design should
  keep 601's retrospective "usually ~Ns" estimate as the ceiling of what is honestly knowable here — a future
  design should not assume a real progress signal will appear without re-checking upstream again first.
- **New, corroborating risk for the "resilient to a starved/hung poll" requirement (§K's calm-by-default
  Stability axis, §O's self-healing requirement).** Community-reported issues against `llama.cpp`
  ([#20921](https://github.com/ggml-org/llama.cpp/issues/20921) and a related report against the `ik_llama.cpp`
  fork) describe `/health` and `/slots` becoming unresponsive while the server is busy (heavy CPU-only prompt
  processing), in some reports for extended periods, before recovering once generation begins. This is
  *informally reported*, not documented upstream behavior, so it should not be treated as a guaranteed
  contract — but it is independent, external evidence that a hung health-check poll against the Brain process
  is a real condition this design will eventually meet in practice, not a hypothetical this codebase invented.
  It strengthens (does not change) the existing internal case for the same requirement (627's Brain
  hang-recovery via periodic health-check + capped restart; §K's calm "checking" state under a stale poll,
  keyed off the shared reachability signal rather than a bespoke AI-specific timeout per §F).

**Comparable local-LLM desktop apps (LM Studio, Ollama) — light pass, no design change.** Checked whether
comparable products (LM Studio's docs) expose a different, more informative loading-state UX worth aligning
with. The available public documentation describes download-progress display and qualitative status but does
not document a numeric loading ETA/percentage pattern either — consistent with, not contradicting, this
design's existing choice (a determinate progress bar for the already-honest download-bytes signal, §J, and a
retrospective, non-fabricated estimate for the genuinely-unknowable model-load phase, §L/601). No specific
external pattern was found strong enough to cite as something this design should adopt or diverge from; this
was a sanity check, not a redesign input.

**No external code, text, or assets were copied or closely adapted into the codebase or this doc.** This
pass was fact-verification only (confirming/refuting assumptions against current upstream API behavior); the
paraphrased findings above are original summaries with sources cited, not reproductions, so the repo's
license/attribution and public-claims CI checks are not implicated. Sources: [`llama.cpp` server
README](https://github.com/ggml-org/llama.cpp/blob/master/tools/server/README.md) (MIT-licensed upstream
project — cited for the factual API-behavior claims above, nothing reproduced verbatim beyond the two short
JSON snippets, which are the project's own documented API response shapes, not creative content), [llama.cpp
issue #20921](https://github.com/ggml-org/llama.cpp/issues/20921), [LM Studio offline-operation
docs](https://lmstudio.ai/docs/app/offline).

## Confidence-building pass (2026-07-01)

> Read-only investigation + two narrow, cleaned-up live probes (module-dependency check, one direct
> `/api/inference/status` curl for wire-casing). No production file was edited; no feature work started.
> Ten load-bearing assumptions from §J-O were checked against source/behavior rather than left as
> plausible-sounding claims. Verdicts below with citations; each either de-risks or sharpens the design in
> §J-O — none overturn it.

1. **Module-boundary feasibility of §L's wire fix — CONFIRMED OPEN, no blocker.**
   `modules/ui/build.gradle.kts:35` already declares `implementation(project(":modules:app-inference"))`,
   and `modules/ui/src/main/java` already imports from `io.justsearch.app.inference` in two places
   (`ApiErrorHandler.java:6`, `EffectiveConfigController.java:8`). Serializing `InferenceRuntimeView` (or a
   record wrapping it) directly from `InferenceHandlers.java` does not need a new module dependency or
   ArchUnit exception — the boundary is already crossed today.

2. **`Mode` enum wire casing — CONFIRMED lowercase, matches FE.** Direct probe (`curl
   127.0.0.1:33221/api/inference/status` against a fresh headless backend) returned `"mode":"offline"` —
   lowercase, matching the FE's existing `mode === 'online'`-style checks. No serialization adapter was
   found in `Jackson3JsonMapper.java` (plain `JsonMapper.builder().build()`, no enum-naming customization)
   or on `Mode.java` (no `@JsonValue`/`toString()` override), so the exact mechanism producing the lowercase
   form wasn't traced further — but the *behavior* that matters for the design (backend and FE agree on
   casing today) is empirically confirmed, closing this uncertainty regardless of mechanism.

3. **`/api/inference/failures` ring buffer — CONFIRMED populated in production, but scoped narrower than
   `runtimeError`.** `TransitionRunner.recordFailureToHistory` (`TransitionRunner.java:174`) is called from
   real production paths, not just tests: `InferenceLifecycleManager.java:589,605,638` via
   `recordFailureOutsideTransition`, plus the in-transition path (`TransitionRunner.java:416`). So the
   ring buffer is a real, live-populated signal. But `BrainSurface.ts:724`'s `runtimeError = msg` (one of
   the ~8 sites) is set inside a **generic** operation-invocation catch (`invokeOp`/`invokeWithConsent`-style
   wrapper) that fires for *any* invoked operation — install, pack-import, activate, switch alike — not only
   inference-lifecycle failures. The ring buffer can only replace the subset of `runtimeError` writes that
   are genuinely inference-lifecycle failures (activate/switch/mode-change); install/pack failures have no
   backend ring-buffer equivalent (consistent with item 9). The design's error-axis fix is therefore
   correctly scoped to the runtime axis only, not a full `runtimeError` replacement — worth stating
   explicitly rather than implying total replacement.

4. **594's `projectFact` — CONFIRMED already reaches live/dynamic state, not just static facts.**
   `display/facts.ts:223`: `export function projectFact(id: string, aiState: AiState | null): ProjectedFact`
   — the projector already takes the live `aiState` snapshot as an argument. Extending the catalog to cover
   `activeModelId`/GPU/variant is genuinely "new catalog rows referencing `aiState.runtime.*`," not a
   structural change to the authority. Low risk, as assumed in §J.

5. **595's `Stability`/`ProvisionalCause` axis — CONFIRMED shipped, but as a CLOSED union scoped to the
   search-verdict seam; settles §K's deferred question toward "sibling type," not "literal reuse."**
   `verdict.ts:37-57` defines `ProvisionalCause` as a closed 7-member union (`'initial-load' |
   'channel-stale' | 'rebuilding' | 'generation-switch' | 'worker-restart' | 'catching-up' | 'updating'`) and
   `Stability = {kind:'settled'} | {kind:'provisional', cause}`, consumed by exhaustive `switch`es elsewhere
   in the same file (e.g. `verdictHeadline`). Adding AI-only cause values directly into this union would
   force every one of those exhaustive switches (all search-scoped) to handle cases that can never actually
   occur in their own output, and the union lives inside the file the `verdict-derivation` gate treats as the
   single search-verdict seam. **This settles §K's open question: reuse the *shape* (`{kind:'settled'} |
   {kind:'provisional', cause}`), define a separate, sibling `AiProvisionalCause` closed union scoped to AI
   causes — not literal membership in `ProvisionalCause`.** This is a concrete correction to §K, not a new
   finding that changes the overall direction.

6. **596's Availability blast radius for `BrainSurface` — LARGER than §J's "small, additive" framing
   assumed; still not a blocker.** Counted directly: `BrainSurface.ts` has **one** existing
   `.availability=` use (`:1002`, the refresh button) against **eighteen** bare `?disabled=`/`busy[...]`
   sites (`:1104,1112,1120,1145,1221,1694,1702,1740,1747,1754,1816,1824,1851,1859,1866` and the `busy[key]`
   guard at `:714`). §J's "small, additive" framing understated the raw count. This does not block the
   design — 596's own precedent (keep native `disabled` as the hard-block default, add `.availability` only
   where a *reason* needs to be shown, per 596 §14 U2 "zero existing-site edits" resolution) means not all
   18 need conversion — but the design should say "candidate set of ~18 sites, convert the subset that
   currently has no reachable reason for the user (install/activate/switch primarily), not treat 596 adoption
   as a one-line change."

7. **Root cause of the live-reproduced "stuck Connecting…" bug — SHARPENED to a stronger, code-confirmed
   claim than §O's original "plausible race."** Re-reading `refreshAll()` end to end: `fetchJson()`
   (`:565-573`) catches every failure internally and returns `null`; `refreshAll()` (`:575-621`) is called
   exactly twice in the whole file — once from `connectedCallback` (`:515`) and once from the manual refresh
   button's `onActivate` (`:1003`) — and **has no interval, no retry, and no re-arm on failure.** This means
   the mechanism named in §O does not depend on the specific `apiBase`-timing theory (which, on closer
   reading of the `attribute:'api-base'`/`declare apiBase` binding at `:213,235,277`, is plausible but not
   confirmed as the specific trigger) — it is sufficient, and confirmed directly in source, that **any**
   transient failure of *any* of `refreshAll()`'s five parallel fetches during the single mount-time window
   permanently strands that field with no self-correction. The live repro's specific trigger (my own backend
   restart moments earlier) is one concrete way to hit this; the structural claim — no retry primitive exists
   — is what the design fix (§M) must address, and it is now confirmed by source, not inferred from one
   observation.

8. **Test coverage for `deriveAiState()` — CONFIRMED: none exists today.** No `BrainSurface.test.ts` (or
   equivalent covering the ladder) was found. Three narrow test files exist for adjacent, unrelated concerns
   (`BrainSurface.reindex-coherence.test.ts`, `BrainSurface.restartEta.test.ts`,
   `BrainSurface.sparkline.test.ts`), none of which exercise `deriveAiState()`'s six-state precedence logic
   or the install/runtime/pack write-sites. This means the eventual refactor has **no regression safety net
   to preserve** — every test for the new authority will be net-new, which is lower risk for "did I break
   existing behavior" but means "does the new authority handle every case the old ladder handled" must be
   verified by hand-derived test cases (one per §E's six-plus-failure-plus-ETA axis), not by an existing
   suite catching regressions automatically.

9. **`AiInstallService` — RE-CONFIRMED: no consolidated state record.** A second, still-non-exhaustive but
   slightly broader pass found only the service class and `DownloadExecutor` — no `InstallRuntimeView`-shaped
   record. §M's asymmetry claim stands.

10. **Blast radius for BrainSurface's direct field reads outside `deriveAiState()` — the item-6 count doubles
    as this answer.** 18 direct `busy[...]`/`?disabled=` reads plus the render methods that branch on
    `installStatus`/`runtimeStatus`/`_unifiedAiState`/`this.inference` fields directly (confirmed present at
    minimum around `:1021-1046`, `:1104-1145`, `:1690-1760`, `:1816-1866`) are the concrete surface a "pure
    consumer" refactor touches. This is a real, multi-hundred-line refactor within one already-large file,
    not a small seam addition — sizing the difficulty rating below.

### Net effect on confidence

Nothing found here overturns §J-N's direction. Two items *sharpen* the design with a concrete decision that
was previously left open (item 5: sibling type, not shared union) and one *narrows* a claim that was stated
too strongly (item 3: the failure ring buffer covers the runtime axis only). Two items *raise* the honestly-
stated difficulty (items 6 and 10: the Availability/render blast radius is larger than "small, additive").
One item *increases* confidence in an already-stated claim by replacing a hedge with a source-confirmed fact
(item 7). No item surfaced a blocker or a reason to redesign.

## Summary and confidence rating

**What was learned:** the design's biggest architectural bets are now checked, not assumed — the wire fix
has no module-boundary obstacle, the wire casing already matches, 594's Fact projector already reaches live
state, and the "stuck forever" bug has a source-confirmed structural cause (no retry primitive) rather than
a guessed one. The two things that got *harder*, not easier, on inspection: (a) 595's Stability axis is
real but closed and search-coupled, so the AI verdict needs its own sibling type, not a one-line extension
of an existing union; (b) `BrainSurface`'s Availability/render blast radius is ~18-20 call sites, not a
handful, and there is zero existing test coverage of the logic being replaced, so the implementer must
author the full regression-test set from scratch rather than lean on an existing suite.

**Critical confidence rating for the remaining implementation work: 7/10.** The direction is sound and now
evidence-backed rather than merely plausible; the main residual risk is execution scale and care (touching
~20 sites in a 2000-line file with no safety net, plus a genuine backend wire-contract change in a second
language/module), not a wrong architectural bet. It is not higher because: no implementation has actually
been attempted yet (design confidence ≠ code-writing confidence), the exact shape of the `aiEngineState`
type and the `AiProvisionalCause` sibling union are still conceptual, and the install-axis backend
consolidation question (§M) is explicitly left as a judgment call for whoever implements, not fully
pre-decided.

## Implementation difficulty and model/effort recommendation

**Difficulty: medium-high, multi-session.** The work spans two languages/modules (a Java wire-contract
change in `modules/app-inference`/`modules/ui`, and a TypeScript authority + refactor in
`modules/ui-web/src/shell-v0`), touches a ~2000-line file with no existing regression tests for the logic
being replaced, requires authoring a new closed union type used inside an existing gate-guarded seam
(`verdict-derivation`) without violating that gate's single-derivation invariant, and has a genuine,
already-reproduced live bug (§O) that the fix must be verified against, not just unit-tested. None of the
individual pieces are algorithmically hard — this is disciplined refactoring and cross-cutting API-contract
work, not novel logic — but the *number of coordinated, correctness-sensitive pieces* (typed wire boundary,
two-axis state cross-product, a new sibling Stability-shaped type, ~18-site Availability adoption, dead
install-wiring to finish, live UI re-verification) is large enough that a shallow pass would likely miss one
of them.

**Recommendation: Opus (or Sonnet with a Plan-mode-first, section-by-section implementation broken into
several sessions and independent live verification after each section — do not attempt this as one large
diff).** The reasoning-load is in *keeping ~10 cross-cutting constraints consistent simultaneously*
(module boundaries, gate predicates, a closed union's exhaustiveness, wire-type alignment across Java/TS,
and the live-repro bug) rather than in raw code volume, which is the kind of task where Opus's larger
effective context and more careful multi-constraint reasoning earns its cost over Sonnet. If Sonnet is used
for cost reasons, it should be run in explicit stages (backend wire fix → runtime-axis FE consolidation →
install-axis FE consolidation → Availability/Fact adoption → gate extension), each independently verified
(build + tests + one live repro of §O's bug) before starting the next, rather than as a single end-to-end
implementation pass. Fable is not a fit here — this is precise, constraint-heavy engineering work across a
typed boundary, not a creative/writing task.

## Implementation (2026-07-01)

Executed the approved implementation plan. **Core consolidation shipped and live-verified; two items were
deliberately down-scoped from the original plan, and two polish items deferred.** All decisions below are
scope judgments, not omissions — recorded so a follow-up knows exactly what remains.

### What shipped

- **New `state/aiVerdict.ts`** — `computeAiEngineVerdict`, the single AI-engine lifecycle derivation
  replacing `deriveAiState()`'s 5-source ladder. `AiProvisionalCause`/`AiStability` mirror `verdict.ts`'s
  shape as a sibling, not a shared union (per the confidence-building pass's item 5 correction). Adds two
  states the old ladder could not represent: `install_failed` (with the install service's own error text)
  and `indexing` (previously fell through to `offline`). Full unit-test coverage (`aiVerdict.test.ts`) —
  none existed before.
- **New `utils/aiInstallPoll.ts`** — a shared, always-on, retry-forever poller for
  `/api/ai/install/status`/`/api/ai/runtime/status`/`/api/ai/packs/status`, mirroring `inferencePoll.ts`'s
  proven shape. Replaces `BrainSurface`'s one-shot `refreshAll()` fetch and the conditionally-armed
  `pollInstall`/`pollPack`/`pollRuntime` timers (which only ever self-armed *after* a prior fetch had
  already succeeded — the exact mechanism behind §O's live-reproduced bug). Wired into `aiStateStore`
  exactly like `inferencePoll`/`statusPoll` already are; finally activates the previously-dead
  `setInstallState`/`installStateSig`. Regression-tested (`aiInstallPoll.test.ts`) against the precise
  failure mode: a failed first tick must not permanently strand the signal.
- **`aiStateStore.ts`/`inferencePoll.ts` typing** — `AiRuntime['mode']` and `InferenceSnapshot['mode']`
  widened to the real 4-value backend union (adds `'transitioning'`, which was previously silently
  collapsed to `'unknown'`). Fixed the resulting priority-order regression against two existing tests
  (`starting` must still take precedence over a bare `'transitioning'` when the live-load flag is set —
  the tests encode that as intentional; only a `'transitioning'` mode *without* the starting flag now
  resolves to the new value).
- **`BrainSurface.ts` refactor** — `deriveAiState()` replaced by a thin adapter (`deriveAiEngineVerdict()`)
  delegating to `computeAiEngineVerdict`; `pollInstall`/`pollPack`/`pollRuntime` and their 8 call sites
  removed; `refreshAll()` now fetches only genuinely one-shot facts (settings, policy). Render markup
  unchanged except two new `statusConfig` entries (`install_failed`, `indexing`) using the existing dot/
  label/sub idiom — matches the doc's own "keep the render as-is" scope line.
- **Live-verified** (headless eval backend + this worktree's Vite, via `claude-in-chrome`): the exact §O
  repro — fresh mount, no AI installed, untouched for 13+ seconds — now resolves to "Not Installed" on its
  own, with no manual refresh. Confirmed unaffected: the install-confirmation modal, the status pill's
  reachability/AI-state separation, the Health surface, and the Chat degradation banner.
- All existing tests pass (3460/3461 — the one failure, `HealthLitView.test.ts`'s SSE-paused-tone test, is
  confirmed pre-existing via `git stash`, unrelated, logged to the inbox). New tests: 21 (`aiVerdict.test.ts`
  ×12, `aiInstallPoll.test.ts` ×3, plus the fixed `StatusDeck.test.ts` fixture). Typecheck, lint, build, and
  three ui-web gates (`check-observed-state-collapse`, `check-verdict-derivation`, `check-inflight-liveness`,
  `check-presentation-purity`) all pass on the touched files.

### What was deliberately down-scoped (a judgment call, not a silent gap — the tradeoff is named per
`structural-defects-no-repeat`'s spirit)

- **Stage 1's backend wire-record conversion did not happen.** Converting `/api/inference/status`'s
  hand-built `Map<String,Object>` (in `InferenceHandlers.java`, ~15 best-effort fields, likely multiple
  consumers including the MCP tool surface) to a generated `@RecordBuilder` API record is real, valuable
  Generate-rung work — but it is backend-only, large, and not required to fix the live bug (which is
  entirely FE-side) or to complete the ladder consolidation. Down-scoped to the FE-only fix: widen the FE's
  own type to match the wire value that was *already* correct (confirmed live: `"mode":"offline"`,
  lowercase, matching). The backend conversion remains a legitimate, separately-scoped follow-up — §L's
  reasoning for it stands unchanged.
- **`/api/inference/failures` (the structured failure ring buffer) was not wired into the FE.** §L/Stage 2
  proposed consuming it in place of `runtimeError: string`. Not done this pass: `runtimeError` is set from a
  *generic* operation-invocation catch spanning install/pack/activate/switch alike (confirmed at
  `BrainSurface.ts` — the generic `withBusy` wrapper), so the ring buffer (inference-lifecycle-scoped only)
  can replace at most a subset of its call sites, not all — a real design decision (which sites, and what
  UI change results) that deserves its own pass rather than a rushed partial wire-up alongside this one.

### What was deferred as separable polish (AHA — not needed to complete this doc's core claim)

- **596 Availability adoption for the ~18 candidate `?disabled=`/`busy[...]` sites** (confidence pass item
  6) — the coarse lifecycle consolidation (this pass) and the per-button reason-surfacing polish are
  genuinely separable; the latter doesn't block or get blocked by the former.
- **594 `projectFact` adoption for model/GPU/variant leaf facts** — same reasoning; the facts already
  render correctly today, just not yet through the shared Fact catalog.
- **Stage 6 (new `ai-verdict-derivation` gate)** — the new seam (`aiVerdict.ts`) is real and independently
  verified not to collide with the existing `verdict-derivation` gate (re-run and confirmed passing), but a
  dedicated gate guarding *against a future re-fork* of `aiVerdict.ts` itself was not authored this pass.
- ~~Stage 8 (explanation/27 kernel-doc update)~~ — **correction:** this line was accurate when first
  written but is now stale. `docs/explanation/27-frontend-presentation-kernel.md`'s authorities table WAS
  updated with a row for the new AI-engine authority, in the same session, in response to the
  `maintain-doc-hint` Stop hook (a governed-region edit requires its doc updated before the turn ends). Not
  a new fix — just correcting this list to match what actually happened.

None of the deferred items are required for tempdoc 663's core claim (single-authority, self-healing
AI-engine state) to be true and live-verified — each is independently completable later without touching
the consolidation shipped here.

## Critical gap analysis — remaining work vs. the tempdoc's own design (2026-07-01)

Re-read this tempdoc in full (not just the Implementation section) and checked the shipped work against
§N's own explicit definition of "done" — *"success is not a new `aiEngineVerdict.ts` file, it is
`BrainSurface` reading `projectFact` + `unavailableBecause` + a new-but-sibling-shaped Stability projection
+ the existing 601 seam... Conform to the seam; do not parallel it."* Verdict: **the sharpest, most
correctness-critical problem (the stuck-forever bug, the 5-source ladder) is genuinely solved and
live-verified — but the tempdoc's broader thesis (BrainSurface as the fourth full adopter of the
594/595/596/601 family) is only one-quarter complete.** Concretely still missing, each a real gap against
this doc's own stated design, not a style nit:

1. **594 Fact adoption** — leaf facts (model, GPU, variant, context window) still formatted inline, not
   projected through `display/facts.ts`'s `projectFact`.
2. **596 Availability adoption** — the ~18 `busy[...]`/`?disabled=` sites still bare booleans, not typed
   `Availability`.
3. **§L's backend Generate-rung wire fix** — shipped as an FE-only type widen instead, which §L explicitly
   named as the weaker tier the design should move past.
4. **No gate protects the new `aiVerdict.ts` seam** from a future re-fork, unlike `computeVerdict`'s
   `verdict-derivation` gate.

## Confidence-building pass (remaining work) (2026-07-01)

> Read-only investigation only — no feature code changed. Six uncertainties about the four remaining items
> above, checked against source/docs rather than left as assumptions.

1. **594's Fact catalog — CONFIRMED already reaches `aiState.runtime.*`; adding rows is low-risk.**
   `display/facts.ts` is a plain TypeScript `Record<string, FactDef>` object literal (`:118`), **not** an
   SSOT-style JSON register with a dual-copy-sync gate. Its `observed` `FactSource` kind's `read: (s: AiState)
   => string | null | undefined` (`:61-70`) already takes the full live `AiState` — the same object
   `aiVerdict.ts` and `BrainSurface` already consume. A new row like `'core.ai.model'` reading
   `s.runtime.modelLabel` is a new object entry, not a schema or authority change. Confirms and strengthens
   the prior pass's item 4 finding.

2. **Consumers of `GET /api/inference/status` — CONFIRMED wider than assumed; raises the backend-fix risk
   above the prior pass's estimate.** Beyond the FE, a **live-server system integration test**
   (`HttpModeTransitionTest.java:190-218`, `@DisplayName("GET /api/inference/status returns valid
   response")`) asserts on exact field presence (`mode`/`available`/`starting`) and the literal mode-value
   set. The endpoint is also documented in the canonical `docs/reference/api-contract-map.md:21` ("GET
   /api/inference/status — returns current mode and availability") and referenced by
   `docs/explanation/{05-ai-architecture,13-ai-setup-and-verification}.md`. A wire-shape change is not an
   FE-only concern — it needs this system test (which requires a live backend to even run) kept green and
   the contract-map doc kept accurate.

3. **The 18 candidate sites — categorized; the true "worth converting" subset is smaller than the raw
   count.** Read all 18 in context (`BrainSurface.ts:1017-1780`). Roughly three groups: (a) sites with a
   real, showable reason (`downloadsDisabled`, `onlineDisabled`, `packStatus?.state==='running'` — a policy
   gate or "already running" condition) — genuine 596 Availability candidates, ~6-8 sites; (b) pure
   busy-only disables (`busy['install-cancel']`, `busy['pack-preflight']`) with no reason beyond "in
   flight" — this is 608's acknowledgement/busy axis, not 596's Availability, per `explanation/27`'s own
   table; converting these to `.availability=` would be the wrong authority; (c) hard intent gates
   (`!installing || busy['install-cancel']` — cancel only makes sense while installing) that 596's own
   precedent says should stay native `disabled` (`blocked`, not `unavailable`). The raw "18 sites" framing
   in the prior pass conflated three different concerns; the real 596-shaped work is the smaller (a) subset.

4. **Gate-wiring mechanism — RESOLVED, and lower-risk than the prior pass's repeated deferral suggested.**
   `governance/registry.v1.json` has **33 entries, and none of them are `check-observed-state-collapse`,
   `check-inflight-liveness`, or `check-presentation-purity`** — three gates already confirmed live and
   working (re-ran them in the prior implementation pass, all passed). This means `registry.v1.json` is a
   *different*, narrower tier (the discipline-gate-kernel family, tempdoc 530) than the broad ui-web
   pre-merge checklist most `check-*.mjs` scripts belong to. The real, well-precedented mechanism for a new
   check in this family is exactly what its ~18 siblings already do: write the script, add one line to
   CLAUDE.md's pre-merge table for `modules/ui-web/src/**`. No special registry onboarding needed — the
   twice-deferred "is this gate even wired anywhere" question was answered by discovering the question
   itself was aimed at the wrong registry.

5. **`controls-a11y` baseline — CONFIRMED empty, zero regression risk from converting.**
   `scripts/ci/controls-a11y-disabled-title-baseline.v1.json` is a bare `{}` — the ratchet's baseline is
   already zero violations. Converting `?disabled=` sites toward `.availability=`/`aria-disabled` moves
   further in the direction this gate already rewards (killing native-`disabled`+`title` pairs, which
   suppress the tooltip); there is nothing at baseline-zero that a conversion could break.

6. **`updateSchemas`/API-record blast radius — CONFIRMED the backend fix has no documented recipe to
   follow, raising its risk further.** The `api-record` skill's entire documented workflow
   (`.claude/skills/api-record/SKILL.md`) is scoped to *adding a field to an already-`@RecordBuilder`
   record* (`KnowledgeSearchResponse`, `StatusResponse`, `WorkerDebugView`, and five others — listed
   explicitly, `/api/inference/status` is not among them). Converting a hand-built `Map` endpoint that has
   *never* had a record into a new one is a categorically different, uncharted task in this codebase — no
   skill, no precedent commit to model it on (this public mirror's history is a single squashed commit, so
   `git log` cannot supply one either).

### Net effect on confidence

Two items (594 Fact adoption, the gate) came back **lower-risk than assumed** — both have clear,
well-precedented, low-blast-radius paths. Two items (the backend wire fix, and the true size of the 596
Availability work) came back **higher-risk / smaller-but-clearer than assumed** — the backend fix now has a
confirmed, concrete consumer list (a live-server system test + a canonical doc) and no documented recipe to
follow, and the Availability work is smaller in true scope than "18 sites" but requires correctly sorting
sites into three different authorities (596 vs. 608 vs. native-blocked), a categorization judgment call, not
a mechanical conversion.

## Summary and confidence rating (remaining work)

**What was learned:** two of the four remaining items (Fact adoption, the new gate) are genuinely
low-risk, well-precedented additions with no structural surprises — these could be implemented with high
confidence in isolation. The other two are where the real risk concentrates: the backend wire fix touches a
documented public contract and a live-server test with no established conversion recipe in this codebase,
and the Availability work requires a correct three-way categorization (596/608/native) rather than a
mechanical batch conversion, which raises the chance of misclassifying a site (e.g., converting a
busy-only disable to Availability when it belongs to 608 instead).

**Critical confidence rating for the remaining implementation work: 6/10.** Slightly lower than the
original implementation's 7/10, specifically because two of the four items have concentrated,
confirmed-but-unmitigated risk (the backend wire fix's contract-compat surface; the Availability
categorization judgment call) rather than because the work is bigger — the other two items are now
genuinely low-risk. Not lower still because nothing here surfaced a reason the design itself is wrong;
every finding sharpens execution risk, not architectural risk.

## Implementation difficulty and model/effort recommendation (remaining work)

**Difficulty: medium, but uneven across the four items — recommend splitting by risk tier, not doing all
four in one pass.** Two items (594 Fact rows, the new gate + CLAUDE.md table line) are low-risk, mechanical,
well-precedented — a careful Sonnet pass with the same section-by-section, verify-after-each-stage
discipline as the original implementation would likely be sufficient and cost-effective for these two. The
other two (the backend wire-record conversion touching a live-server system test and a canonical doc; the
596/608/native categorization of the Availability sites) carry the same kind of "keep ~5-8 cross-cutting
constraints consistent, in an area with no existing recipe to lean on" load that justified Opus for the
original core work — **recommend Opus for the backend wire fix and the Availability categorization
specifically**, Sonnet acceptable for the Fact-catalog and gate additions. Fable is not a fit for any of
the four. If run as one combined session regardless of the split, default to Opus for the whole pass rather
than mixing models mid-session.

## Critical-analysis pass and fix (2026-07-01)

Per `critical-analysis-pass`, re-read the implementation diff line-by-line against the original
`deriveAiState()` ladder it replaced, looking specifically for wrong-priority/dropped-condition mistakes —
the class of error a refactor is most likely to introduce silently. Found one real regression, fixed the
same pass:

- **`computeAiEngineVerdict` had dropped the `busy['install-start']` local-intent flag.** The original
  ladder's first, unconditional check was `installing = installStatus?.state === 'running' ||
  busy['install-start']` — clicking "Install AI" showed "Installing…" *instantly*, before any network
  round-trip. The new derivation only checked `installStatus?.state === 'running'`, so a click now waited
  up to one poll interval (previously 3s) before the headline updated — a real, user-visible regression
  against this doc's own "keep behavior as-is" scope line, not a style nit. **Fixed**: added
  `installStarting: boolean` to `AiEngineVerdictInput`, wired from `BrainSurface.busy['install-start']`,
  checked first/unconditionally (ahead of the new `install_failed` state too, so a retry-click shows
  "Installing…" immediately even if the last-known poll still says `failed`). Deliberately did **not**
  extend this to `busy['install-repair']`/`busy['install-cancel']` — the original ladder never consulted
  those either; matching original fidelity means not expanding scope. Two new unit tests added
  (`aiVerdict.test.ts`) pinning the priority; live-verified in the browser (clicked "Install AI" → "I
  Accept & Download" → the headline flipped to "Installing… / Phase: plan" in the same screenshot as the
  click, i.e. before the network round-trip completed).
- **`aiInstallPoll.ts`'s fixed 3000ms interval was slower than the original's 1000ms per-operation
  cadence** for no compensating benefit (download/activation progress would have refreshed less often
  than before). Fixed: `INTERVAL_MS` lowered to 1000, matching the original responsiveness while keeping
  the always-on/retry-forever property that fixes §O's bug. Existing poller tests updated to the new
  interval; all pass.

Both fixes verified: `aiVerdict.test.ts` (14 tests, up from 12) + `aiInstallPoll.test.ts` (3 tests, interval
updated) pass; full ui-web suite unaffected (3462/3463, same single pre-existing unrelated failure as
before); typecheck clean on touched files; live-verified in the browser as described above. As a side
effect of the live click-through, the new `install_failed` state was also confirmed rendering correctly in
a real environment (this headless eval backend's bundled AI runtime is missing, so the install failed fast
with "Install Failed — Bundled AI runtime is missing and could not be restored." and a working retry
button — no multi-GB download occurred).

Nothing else found in the critical-analysis pass warranted a code change: the axis cross-product, the
`switching`/`inference-switch` handling, the reachability resilience, the poller's retain-last-good
semantics, and the Advanced panel's separate (pre-existing, unaffected) `installing` check all matched the
original ladder's behavior and this doc's design intent.

## Remaining work implemented (2026-07-01)

All four items from the confidence-building pass's implementation plan are now shipped, in the planned
risk order. Each was independently verified before the next started.

**Stage 1 — `ai-verdict-derivation` gate.** `governance/ai-verdict-derivation.v1.json` +
`scripts/ci/check-ai-verdict-derivation.mjs`, a structural copy of `check-folder-status-derivation.mjs`:
seam-integrity (does `computeAiEngineVerdict` still exist and still read the raw
`installStatus?.state`/`onnxFeatures` fields?) + single-derivation-predicate scan (does any OTHER file in
`shell-v0` read those raw fields?). CLAUDE.md's pre-merge table gained one entry. Running it against the
as-shipped tree found **two genuine re-derivations** in `BrainSurface.ts` that had survived the earlier
critical-analysis pass — the install-failure banner condition and the Advanced panel's `installing` check
were both still reading `installStatus?.state` directly instead of consuming `computeAiEngineVerdict()`'s
result. Both fixed to consume the verdict. One legitimate exception (`installStalled()`'s staleness check —
a distinct question, "is this install stuck," not "what lifecycle state are we in") was allow-listed with an
explanatory note. Gate passes; 31/31 affected tests still pass.

**Stage 2 — 594 Fact adoption.** Added `core.ai.model`, `core.ai.contextWindow`, `core.ai.gpu` rows to
`display/facts.ts` (deliberately distinct from the pre-existing `core.gpu.accel`, which sources the
*Worker's* GPU via `status.gpu` — the Brain's own GPU probe via `runtime.gpu` is a different signal).
`BrainSurface.ts`'s two inline-formatted GPU/model/context-window render sites (Simple panel's grid, and the
Advanced panel's Runtime section) now call `projectFact(...)` instead of hand-formatting
`this.inference.activeModelId`/`.gpu.vramDescription`/`.llmContextTokens`. The local `friendlyModel()`
formatter (used only at the removed call site) was deleted — `runtime.modelLabel` already applies the same
(and slightly better: it also normalizes underscores) formatting at the source, in `aiStateStore.ts`. Model
label's tooltip (the raw, pre-friendly id) is preserved via the Fact's `provenance` field. Typecheck clean;
31/31 tests pass.

**Stage 3 — 596 Availability adoption.** Converted the primary action button (Install AI / Start AI / Cancel
/ Shut Down — one shared `jf-button`, all six `aiState` cases now route through one `Availability` value
instead of a bare `disabled` boolean), the Import-pack button, the Advanced panel's Install/Repair buttons,
and the Online mode-switch button — six sites with a genuine, showable reason (a policy gate, "already
installing," "already importing") converted to `unavailableBecause(reason)`, reusing the exact policy-reason
wording already shown in the page's banner text rather than inventing new copy. Busy-only sites (Preflight,
pack-import's sibling Cancel, the two mode-switch buttons with no policy gate, variant-activation buttons)
and the one hard-intent-gate site (Cancel-install's `!installing`) were deliberately left as native
`?disabled=`, per 596 §10/C2 — a control with no showable reason beyond "wait" stays `blocked`/native, not a
soft `unavailable{reason}`. The `ai-verdict-derivation` and `controls-a11y` gates both re-run clean; full
ui-web suite: 3462/3463 (same pre-existing unrelated failure as before Stage 1).

**Stage 4 — backend wire-typing fix.** New `@RecordBuilder` record `InferenceStatusResponse` (+ nested
`InferenceGpuView`) in `modules/app-api`, mirroring every field `InferenceHandlers.handleInferenceStatus`
was already emitting from a hand-built `Map` — none dropped, none added. `mode` stays typed `String` (not
the `Mode` enum): `OnlineAiService.getCurrentMode()` is itself declared to return `String`, so
compile-time-enforcing the mode *value* set would mean changing that interface's contract — a materially
larger, separately-scoped change. This record's actual contribution is the JSON *shape* (field names/types)
becoming schema-generated and cross-language-fixture-checked, which is the concrete gap the design named
("hand-built endpoint feeding a hand-typed FE field, no schema between them"). Registering the new record
required two additions beyond the record itself, both discovered by following the existing pattern rather
than guessing: a `captureOrVerify` entry in `WireRecordSchemaGenTest.java` (the `updateSchemas` Gradle task
runs a curated allowlist of schema tests, not automatic classpath discovery) and a `TARGETS` entry in
`scripts/codegen/gen-wire-schema-types.mjs` (the FE TS/Zod codegen has its own, separate registration list).
Verified: `./gradlew.bat build -x test` clean; `WireRecordSchemaGenTest` passes in normal (non-update) mode;
`check-wire-schema-types-regen.mjs` reports no drift; a live `:modules:ui:run` backend's `/api/inference/status`
response inspected directly via curl matches the intended shape exactly (nested `externalServer`/`gpu`
objects, all fields present). `HttpModeTransitionTest`'s 4 mode-switch-dependent assertions failed against
that live backend — confirmed via the test's own error output to be entirely environmental (this worktree's
`native-bin/llama-server/llama-server.exe` was never staged for a plain `:ui:run`, so the online-mode switch
genuinely cannot succeed here; a pre-existing test gap independently visible in the failure text, "Mode
should be online/indexing/transitioning" not including "offline," also surfaced), not a wire-shape defect —
the shape itself was independently confirmed correct via the direct curl inspection. FE's
`inferencePoll.ts#InferenceSnapshot.mode` was deliberately **not** swapped to import the generated type: the
generated type's `mode` is bare `string` (JSON Schema can't encode a Java `String` field's runtime-only
value set as a TS union), so swapping would have regressed the narrower `'online'|'indexing'|'transitioning'|'offline'`
union already in place. The hand-typed union is being kept as an intentional refinement layered on top of
the now-schema-backed wire shape, not a drift risk — the schema+test now catches field renames/removals,
which is what was missing before.

**Live browser verification (Stages 2-3 user-visible surfaces).** Dev stack started (headless eval backend +
this worktree's Vite), `AI Brain` surface driven via `claude-in-chrome`: the `not_installed` state (the one
reachable state in this environment without a multi-GB model download) renders correctly — status card,
"Install AI" button in its `AVAILABLE` state, Advanced panel toggle, no console errors. The genuinely
disabled/reason-bearing states (Model/GPU/Context facts on `online`, the policy-gated tooltips) could not be
reached live in this environment (no `llama-server.exe` staged, same environmental gap Stage 4 hit) — their
correctness rests on the unit-test coverage (`aiVerdict.test.ts` 14 tests, `BrainSurface.*.test.ts` 31 tests)
plus the passing `controls-a11y` gate (which specifically checks disabled-control accessible naming), not an
additional live pixel check. This is a disclosed scope limit, not a skipped verification: the render logic
itself is a pure function of `AiState`/`policy`/`busy`, already exercised by those tests for every branch
this stage touched.

All four stages: tempdoc's own "fourth full adopter of 594/596/601" design is now satisfied — `BrainSurface`
derives its lifecycle from the one `computeAiEngineVerdict` seam (protected by a gate), projects Model/GPU/
Context through the shared Fact catalog (594), expresses genuinely-reasoned disablement through the shared
Availability authority (596), and the backend endpoint it reads is schema-typed end-to-end rather than a
hand-built `Map` feeding a hand-typed FE field.

## Critical conceptual review (2026-07-01) — three real gaps against this doc's own design

A conceptual re-read against §J-N (not code style) found three real mismatches, all against this doc's own
stated design, not invented requirements:

1. **§F/§K/§H's "two axes, separately-reasoned fields, not one flat computed string" is not fully honored.**
   `AiEngineVerdict.kind` is one flat 8-member enum computed by a single priority ladder over both the
   install axis and the runtime axis together — the same *shape of problem* the doc set out to eliminate,
   just consolidated into one function instead of five sources.
2. **§K's "one genuinely new piece" — the AI-engine Stability projection — is computed but never consumed.**
   `AiEngineVerdict.stability` is unit-tested but zero production code reads `.stability`; the calm-vs-
   provisional distinction (and *why*) never reaches the render.
3. **`computeAiEngineVerdict` is private to `BrainSurface`, not a shared, store-computed authority.** Unlike
   594/595/596 (each computed once in `aiStateStore.ts` and consumed by multiple surfaces), it is a pure
   function only `BrainSurface` happens to call with locally-mirrored instance fields — unavailable to any
   other consumer (status pill, capability map) the way §K's own framing implies it should be.

A fourth, lower-confidence reading: the "resilient to a starved poll" requirement (explicit under
"Verification (when built)") is wired only for the not-yet-installed path (§O's actual live-reproduced bug,
which is fixed). The already-installed fallback branch never consults `reachable`, so a stale poll on an
already-installed engine can still settle to a confident "Offline" rather than a calm "checking."

## Design pass 2 (2026-07-01) — closing the gaps without re-opening the shape

> Read-only design theorization against `main`/this worktree. No implementation in this pass. Investigated
> whether an existing, working pattern already covers this before proposing anything new — per
> `explore-before-implementing`, extension beats invention. Adjacent tempdocs read/checked in this pass:
> 508 (`aiStateStore`'s own origin — the `setAiActivity` local-intent-into-store precedent), 595
> (`verdict.ts` — `computeStability`'s actual shape, read in full), 627 (backend process-supervision —
> confirms "single authority, not scattered monitor/spawner logic" is the same judgment one layer down, no
> FE-design impact). 594/596/601 were re-grounded from their shipped call sites (`facts.ts`, `availability.ts`,
> the ETA seam) rather than re-read in prose, since the source is the more authoritative artifact for "does
> this pattern already exist" questions (`verify-dont-guess`).

### R. What already exists that resolves this — do not invent a new mechanism

Re-reading `verdict.ts` (595's own precedent, in full) sharpens finding 1 rather than overturning it.
`computeStability` (`verdict.ts:100-132`) is **also** a priority-ordered ladder over several independent
inputs (`indexState`, `migrationState`, generation-id mismatches, `catchingUp`, `phase`) — so "a ladder" is
not itself the anti-pattern; 595's own single authority is built the same way. The actual precedent 595
sets, confirmed by tracing its consumers, is narrower and more specific:

- **The RAW axes stay separately exposed on `AiState`** (`indexState`, `migrationState`, generation ids,
  `status.*` fields) — `StatusDeck.ts`'s doc-count/size/memory rendering reads those directly, not
  `verdict.kind`. `computeStability`/`computeVerdict` only collapse them into ONE additional field
  (`Stability`, then `SystemHealthVerdict`) for surfaces that specifically want the alarm-level rollup.
- **The rollup is computed ONCE, in the store, inside the existing `computed()` block** alongside its
  sibling derivations (`aiStateStore.ts:609-626` calls `computeStability`/`computeVerdict` the same way it
  calls `computeRuntime`) — never privately, per-component.
- **`.stability.kind === 'provisional'` already has an established, reused consumption pattern**: a
  `provisional` boolean gates dimmed/last-known-value rendering, identically, in `StatusDeck.ts:379`,
  `HealthSurface.ts:516`, `BrowseSurface.ts:256`, `LibrarySurface.ts:388` — four independent surfaces
  already share ONE rendering convention for "this value is real-now but in flux," not four ad hoc
  treatments.
- **Local, per-click optimistic intent already has an established store-integration pattern that is
  deliberately NOT used by every local flag.** `setAiActivity(patch)` (`aiStateStore.ts:853`) lets a
  component push local intent into a shared signal the store's own derivation consumes — proving the
  mechanism exists — but tempdoc 663's own `AiEngineVerdictInput` doc comment already reasoned about this
  correctly for `installStarting`/`switching`: they are "genuinely surface-local UI intent, not observed
  state" (`aiVerdict.ts:63-73`). That judgment should **stay**, not be overturned to force-fit the
  `setAiActivity` precedent — a different surface's status pill showing "Installing…" the instant
  `BrainSurface`'s OWN button is clicked, before the poll has confirmed anything, would be an honest signal
  leaking as if it were observed truth. The precedent to reuse for install/runtime axes is "compute the
  OBSERVED rollup once, in the store"; the precedent correctly NOT to reuse here is "push every local click
  into a shared signal."

Net: nothing here needs a new mechanism. Three existing, already-proven patterns — the store's `computed()`
derivation slot, the `provisional`-gated dimming convention, and the discipline of keeping per-click intent
local rather than store-wide — already cover every gap. The fix is *placement and shape*, not invention.

### S. The correct shape

- **`aiStateStore.ts` gains one more field on `AiState`, computed the same way `stability`/`verdict`/
  `runtime` already are** — the OBSERVED half of `computeAiEngineVerdict` (install status, runtime status,
  `runtime`, `connection.reachable`; no local busy flags), producing the same `AiEngineVerdict` shape
  (`kind` + `stability` + `installFailure`) this doc already designed, just computed once centrally instead
  of privately inside `BrainSurface`. `kind` stays a flat rollup — that is the correct, precedented shape
  for a rollup (595's `VerdictKind` is flat too) — because the axes it is built from remain separately
  readable on `AiState` already (`installStatus`, `runtimeStatus`, `runtime` are already independent fields
  from the current implementation), so nothing is lost by keeping one rollup field alongside them.
- **The observed rollup's fallback branches become fully reachability-aware**, closing the fourth gap: any
  branch that currently settles to a confident value without checking `reachable` should do so only when
  reachable (or when a more specific, higher-precedence signal already answered the question) — mirroring
  how `computeStability`'s own connection-axis check is not scoped to one sub-case.
- **`BrainSurface`'s local optimistic overlay (`installStarting`, `switching`) becomes a small, explicitly
  local function** taking the store's observed rollup and returning the presentation-adjusted value for
  *its own* render — the doc comment's existing reasoning for why these two flags are local stays correct;
  it just now overlays a shared base instead of being the entire derivation.
- **`BrainSurface` (and any future consumer) reads `AiState.aiEngine` directly** instead of privately
  calling `computeAiEngineVerdict` with hand-mirrored instance fields — this is what makes it the genuine
  fourth sibling, available the same way `AiState.stability`/`.runtime`/`.installStatus` already are.
- **`BrainSurface` adopts the existing `provisional` dimming convention** using its own new field
  (`aiEngine.stability.kind === 'provisional'`), the same pattern already used in four other files — not a
  new visual treatment, an existing one applied to a fifth surface.

This is a small, targeted move-and-wire change: no new store mechanism, no render redesign, no rename or
discard of Stages 1-4's Fact/Availability/backend-wire work (those stay exactly as shipped and are
untouched by this). The `ai-verdict-derivation` gate's seam (`computeAiEngineVerdict` in `aiVerdict.ts`)
does not move; only its caller does, from `BrainSurface.ts` to `aiStateStore.ts` — a follow-up implementation
pass should re-verify the gate's `allowed` list against the new call site, not assume it is unaffected.

### T. Judging the design's reach

**Is this an instance of an existing principle, or a new one?** It is an instance — a sharper reading of
the same discipline CLAUDE.md's `explore-before-implementing` register already names ("projection vs
fork": before authoring a new representation of existing data, check whether it should derive from one
canonical source or become a second, drifting authority). This design pass adds a concrete, two-part test
for the specific case of a *state-derivation* authority (the 594/595/596/601/608 family, and any future
sibling):

> **A derived state authority is genuinely single-source only when (a) it is computed once in the shared
> store alongside its siblings, not privately inside one consuming component, AND (b) it exposes each
> independently-meaningful observed axis as its own field, collapsing only a narrow rollup (severity/
> stability) into one flat value for surfaces that need just the alarm-level answer — not the axes
> themselves.** Failing (a) reintroduces the private-derivation risk the authority was built to remove,
> just with one source instead of five. Failing (b) reintroduces the "one flat string means everything"
> risk one level up, even with a single source.

**Where else would this apply?** Any future "make X a single derived authority" work in this codebase
(the next subsystem that gets its own 594/595/596-style consolidation) should be checked against both
halves before being called done — this is a candidate addition to the existing `explore-before-implementing`
discovery step, not a new gate.

**Does existing code already violate it?** The freshest instance is this doc's own shipped
`computeAiEngineVerdict` (the subject of this design pass) — private (violates (a)) and flattened (violates
(b), though mitigated by the axes already being separately exposed on `AiState`). No exhaustive audit of
the rest of the codebase was performed for other violations; per this doc's own instruction not to build
generalized structure prematurely, this principle is recorded here with its candidate scope, not turned
into a repo-wide sweep or a new discipline gate. If a second, independent instance is found later, per
`structural-defects-no-repeat` that would be the trigger to formalize it further (e.g. as a checklist item
or lint rule) — not this one recognition alone.

**What this pass is NOT proposing:** a new shared "operation intent" signal (the `setAiActivity` precedent
was deliberately not extended here, per §R); a rename of `AiEngineVerdict`/`computeAiEngineVerdict` (the
name already matches the correct rollup-shape, mirroring `SystemHealthVerdict`/`computeVerdict`); any
change to the render markup beyond adding the `provisional` dimming already used elsewhere; any revisiting
of Stages 1-4 (gate, Fact adoption, Availability adoption, backend wire-typing), which remain correct and
are untouched by this design.

## Confidence-building pass 2 (2026-07-01) — de-risking Design pass 2 before implementation

> Read-only investigation against source (no feature code changed). Seven uncertainties identified from
> Design pass 2's own proposal; each checked directly against source rather than assumed. One item
> corrects a Design pass 2 claim; the rest confirm it with concrete evidence.

1. **`aiStateStore.ts`'s reactive structure — CONFIRMED trivially simple, not staged/nested.** There is
   exactly ONE `computed()` in the whole module (`aiState = computed<AiState>(buildSnapshot)`,
   `aiStateStore.ts:663`); `buildSnapshot()` is a plain function reading all input signals directly and
   calling `computeStability`/`computeVerdict`/`computeRuntime` in sequence (`:599-660`), mirroring exactly
   how `stability`/`verdict` are already added (`:612-635`). Adding `aiEngine` is the same one-more-call
   pattern — no staging/ordering risk. **Lowers risk on uncertainty 1.**

2. **Gate false-positive check — CONFIRMED clean.** A fresh grep of `aiStateStore.ts` for the
   `ai-verdict-derivation` gate's predicate (`installStatus\?\.state\s*[=!]==|onnxFeatures\?\.some`) found
   zero matches. Moving `computeAiEngineVerdict`'s CALL SITE into `aiStateStore.ts` does not add a match
   there (the raw-field reads stay inside `aiVerdict.ts`, the seam); the gate's `allowed` list does not need
   `aiStateStore.ts` added. **Settles uncertainty 3 — gate-neutral, confirmed not assumed.**

3. **Test-suite blast radius — CONFIRMED small.** Read `aiVerdict.test.ts` in full and categorized all 14
   tests: 11 exercise the OBSERVED axes only (install/runtime/reachable, no local intent) and would move to
   a new function with a trivial signature change; 3 (`installStarting` alone, `installStarting` vs. a
   stale `failed` status, `switching`) specifically test the local-intent overlay and would move to a small
   new describe block for that overlay function. No wholesale rewrite. **Settles uncertainty 2.**

4. **The stale-poll fallback fix — SCOPED to exactly one branch, not all of them.** Re-reading 595's own
   `computeStability` precedent in full (`verdict.ts:100-132`) clarifies the right scope: 595's
   connection-axis check applies as a low-precedence catch-all, not to every branch equally. By the same
   reasoning — and matching the doc's own stated concern (avoid a *false confident negative*, not hedge
   every positive) — only the FINAL fallback branch (`return {kind:'offline', stability:{kind:'settled'}}`,
   reached when installed=true and no other branch matched) needs a `reachable` check; the
   `online`/`indexing`/`starting` branches correctly stay unconditional, consistent with test #9's own
   existing reasoning ("runtime online is proof of an installed engine even if installStatus itself is
   stale"). Cross-checked against all 14 existing tests: none break (the one test exercising this exact
   branch, "installed, runtime offline… → offline, settled," uses the `input()` helper's `reachable: true`
   default, so it is unaffected; a 15th test for `reachable: false` in this branch is new coverage, not a
   changed assertion). **Settles uncertainty 5 — small, precisely bounded, zero regression risk to existing
   tests.**

5. **The `provisional`-dimming target named in Design pass 2 was wrong — CORRECTED.** Design pass 2 proposed
   dimming the Simple panel's Model/GPU/Context facts (Stage 2's `projectFact` additions). Checking where
   those actually render (`BrainSurface.ts:1121`, gated on `aiState === 'online'`) against which branches
   can produce `stability.kind === 'provisional'` (installing/starting/connecting/switching-variant — never
   `online`, which is always `settled` in the current design and stays that way after fix 4 above) shows
   those facts can **never** render in a provisional state — there is no reachable trigger. The
   Advanced panel's Runtime section (`renderRuntimeSection`, `BrainSurface.ts:1723`, gated only on
   `this.inference?.gpu` truthy — spanning `starting`/`switching-variant`/`online` alike, not `online`-only)
   is the correctly-grounded target: during a variant switch the shown GPU/VRAM values are genuinely
   last-known-and-about-to-change, the actual "real-now-but-in-flux" case the dimming convention exists
   for. **Settles uncertainty 4 — with a correction, not a confirmation; the implementation plan should
   target the Runtime section, not the Simple panel's online-gated grid.**

6. **`AiState` fixture blast radius — CONFIRMED to be one site, not five.** A broad grep found 5 test files
   with a full `AiState`-typed fixture function, but reading each closing `return` shows 4 of the 5
   (`CapabilityMap.test.ts`, `LivenessReadout.test.ts`, `facts.test.ts`, `availability.test.ts`) use an
   `as unknown as AiState` escape-hatch cast — structurally unaffected by a new required field. Only
   `StatusDeck.test.ts`'s `makeAiState` (`Partial<AiState>` merged into a real base object, no cast) needs a
   one-line addition — the same fix pattern already applied once in the prior implementation pass.
   **Settles uncertainty 6 — smaller than the initial broad grep suggested.**

7. **Live-repro feasibility for the stale-poll fix — feasible in principle, not readily available in this
   environment right now.** §O's bug was reproducible without any AI installed. This fix's trigger requires
   `installed === true` (a genuine completed install or `onnxFeatures.modelActive`), which this worktree
   does not have (no staged `llama-server.exe`, confirmed during Stage 4's own verification) and installing
   one requires a real multi-GB download not undertaken for a verification check. The mechanism itself
   (kill the backend process after a genuine install, mirroring §O's method, then watch the panel over the
   15s staleness window) is the same class of repro already used successfully once. **Settles uncertainty 7
   — an honestly-scoped verification gap: the implementation's primary verification for this specific fix
   will be the new unit test (item 4 above) plus this documented live-repro method, run when a real install
   is available (e.g. the first time this ships against a real user machine, or if this worktree's
   environment later gets a staged runtime) — not a blocker to implementing.**

**Bonus check, not in the original uncertainty list:** does moving `computeAiEngineVerdict` into
`aiStateStore.ts` create a circular import? `aiVerdict.ts` already imports `AiRuntime`/`InstallStatus`/
`AiRuntimeStatus` FROM `aiStateStore.ts` (`aiVerdict.ts:27`) — but as `import type`, which TypeScript erases
entirely at compile time (no runtime import edge). `aiStateStore.ts` importing the `computeAiEngineVerdict`
*value* from `aiVerdict.ts` therefore creates only one real runtime edge (`aiStateStore.ts → aiVerdict.ts`),
not a cycle. **Confirmed non-issue.**

### Net effect on confidence

Six of seven uncertainties resolved with concrete, low-risk answers (small test blast radius, no gate
conflict, no circular import, precisely-bounded fallback fix, trivial store integration). One
(`provisional`-dimming target) was corrected rather than confirmed — Design pass 2's proposal named the
wrong render site; the Advanced panel's Runtime section is the right one. The seventh (live-repro) is an
honestly-scoped gap, not a blocker: unit-test coverage plus a documented, previously-proven repro method
covers it until a real install is available to verify against.

## Summary and confidence rating (Design pass 2 implementation)

**What was learned:** the design's two biggest assumptions — that the store's reactive shape can absorb a
new derived field cleanly, and that the local-intent split wouldn't require a large test rewrite — are both
confirmed true and low-risk. The gate and circular-import risks, which were not even in the original
uncertainty list, both came back clean on inspection. The one real correction (provisional-dimming target)
is exactly the kind of thing this pass exists to catch before code is written: implementing the *originally
proposed* site would have shipped inert code a second time, the same defect class Design pass 2 itself was
written to fix.

**Critical confidence rating for the remaining implementation work: 8/10.** The design is now not just
theoretically sound but concretely verified against the exact files it will touch — store structure, gate
predicate, test fixtures, and the render target are all confirmed, not assumed. It is not higher because:
no code has actually been written yet (verification confidence is not the same as execution confidence),
and the live-repro gap (item 7) means the stale-poll fix's real-world correctness rests on unit tests until
a genuine install is available to confirm against — a smaller, more honestly-bounded residual than the 7/10
rating on the *original* implementation carried.

## Implementation difficulty and model/effort recommendation (Design pass 2)

**Difficulty: low-medium, single session.** This is meaningfully smaller than the original four-stage
implementation: one store file gains one new computed field (mirroring an exact existing pattern twice
already proven in the same file), one function splits into two (with the split's exact boundary already
determined by the confidence-building pass), one render call site moves from one gated section to another
(already identified), and one test file needs a ~15-test reshuffle plus one new test — all against files
already read in full this session, with every open question resolved to a concrete answer rather than a
judgment call deferred to implementation time.

**Recommendation: Sonnet, single pass, no staging required.** Unlike the original four-stage work (which
justified Opus/staged-Sonnet due to ~10 simultaneously-live cross-cutting constraints across two languages),
this is a single-language, single-file-family change with a fully pre-resolved shape — the reasoning load
that justified Opus before has already been done in this pass, not deferred to the implementer. Standard
verification (typecheck, the existing + new unit tests, the `ai-verdict-derivation` and `controls-a11y`
gates, one live browser check of the Runtime section's dimming) is sufficient; Opus would not meaningfully
reduce risk here, and Fable remains a non-fit for the same reason as before (precise engineering work, not
creative/writing work).

## Design pass 2 — implemented (2026-07-01)

Executed exactly the plan the confidence-building pass produced — every step landed as scoped, no
surprises beyond what that pass had already flagged (the live-repro gap, item 7, which stays open as
documented).

**What shipped:**

- **`aiVerdict.ts` split into two functions.** `computeAiEngineVerdict(observed: AiEngineObservedInput)`
  keeps the exact original precedence ladder (install/runtime/reachable only, no local intent) and adds
  the stale-poll fix: the final fallback branch (installed, no other branch matched) now returns
  `{kind:'offline', stability:{provisional, cause:'stale-poll'}}` when `!reachable`, closing the gap the
  critical review found — the one branch that needed it, per the confidence-building pass's scoping
  against 595's own `computeStability` precedent. `applyLocalIntent(observed, {switching,
  installStarting})` carries the two branches that used to consult local click-intent, unchanged in
  behavior (verified test-by-test against the original 3 local-intent test cases, now restructured, not
  rewritten).
- **`aiStateStore.ts` computes the observed rollup once**, in `buildSnapshot()`, alongside `stability`/
  `verdict`/`runtime` — the exact existing pattern, one more call. `AiState.aiEngine: AiEngineVerdict` is
  now a genuine fourth 594/595/596 sibling: store-computed, shared, not private to `BrainSurface`.
- **`BrainSurface.ts` becomes a pure consumer.** `deriveAiEngineVerdict()` is now a 6-line method: read
  `this._unifiedAiState.aiEngine`, apply `applyLocalIntent` with this surface's own
  `busy['inference-switch']`/`busy['install-start']`. Every existing render call site (the Simple panel's
  status lookup, the install-failure banner, the Advanced panel's `installing` check) is unchanged —
  only the value's origin moved.
- **The `provisional` dimming convention adopted at the corrected target** (`renderRuntimeSection`'s
  CUDA/VRAM/Tier grid, not the Simple panel's online-only facts grid, per the confidence-building pass's
  correction) — `const provisional = this._unifiedAiState?.aiEngine.stability.kind === 'provisional'`,
  applied as a `0.6` opacity reduction, reusing this file's own existing disabled-control opacity
  convention (no new CSS class invented).
- **Gate confirmed gate-neutral** (`ai-verdict-derivation` passes with no `allowed`-list change — the
  raw-field reads stay inside `aiVerdict.ts`, only the CALLER moved) and **no circular import**
  (`aiVerdict.ts`'s reverse reference to `aiStateStore.ts` types is `import type`, erased at compile time).
- **`aiVerdict.test.ts` restructured, not rewritten**: 12 tests exercise `computeAiEngineVerdict` (the
  original 11 observed-axis tests plus one new `reachable: false` test pinning the stale-poll fix), 5
  tests exercise `applyLocalIntent` (the original 3 local-intent cases plus 2 new tests: "switching never
  overrides a confident non-offline kind" and "no local intent returns the observed value unchanged" —
  both implicit in the original ladder but never separately pinned). `StatusDeck.test.ts`'s `makeAiState`
  fixture gained one default field (the only fixture site needing it, per the confidence-building pass's
  count).
- **Verified**: full `modules/ui-web` unit suite — 3466/3467 passing (up from 3462/3463 pre-change; the
  one failure is the same pre-existing, unrelated `HealthLitView.test.ts` case tracked since the original
  critical-review pass). Typecheck clean on all touched files. Both the `ai-verdict-derivation` and
  `controls-a11y` gates pass. Live-verified via the dev stack + `claude-in-chrome`: the fresh-mount
  `not_installed` state renders identically to before this change, no console errors introduced. The new
  `provisional`-dimming behavior itself could not be triggered live in this environment (no staged
  `llama-server.exe`, unchanged from the earlier-documented gap) — its correctness rests on the dedicated
  unit test plus the reused, already-proven `StatusDeck`-identical rendering pattern, not an additional
  live pixel check; this was disclosed as the expected scope limit before implementation began, not
  discovered as a surprise during it.

**Net result:** the three gaps the critical conceptual review found are closed. `computeAiEngineVerdict`
is now genuinely store-computed and shared (not private), its Stability projection is genuinely consumed
(not dead code), and the stale-poll resilience the doc's own "Verification (when built)" section asked
for now covers the already-installed axis, not only the not-yet-installed one. The tempdoc's own
definition of "done" (§N: "the fourth full adopter... conform to the seam, do not parallel it") is now
met for the Observed-state/Stability piece as well as the previously-completed Fact/Availability/backend-
wire pieces.

## Future work — what `AiState.aiEngine` now makes possible (2026-07-01)

> Read-only research pass, no implementation. The core value of this section is that `AiState.aiEngine`
> is now a genuine, shared, store-level fact several other surfaces could read — none of them could
> before this session's work, because the value did not exist outside `BrainSurface`. Everything below is
> a candidate, not a commitment ("nothing specific, no rush" per the request that produced this section).
> Checked against source, not assumed; one external search attempted, yielded nothing citable beyond
> general/known industry practice, so no external claim is made below.

### What already exists that a follow-up should extend, not re-derive

- **The global footer pill (`StatusDeck.ts`'s `core.inference-mode` item) already exists, is already an
  operable `<jf-control>`, and already renders `aiState.statusLabel`/`.statusTone`** (visible on every
  page, not just Brain). It does not need a new component — it needs its label SOURCE widened.
- **`computeStatusLabel`/`computeStatusTone`/`computeStatusTier`** (`aiStateStore.ts:414-570`) are the
  ONE place that pill's text/tone come from — the natural, single edit point for any of the ideas below,
  not a new derivation.
- **The ephemeral-toast/advisory infrastructure** (`AdvisoryStore.ts`, `advisory/ephemeralToast.ts`)
  already exists and is already used by several unrelated surfaces (`StatusDeck.ts` itself,
  `draftKeptHint.ts`, `SearchSurface.ts`) — a candidate mechanism for a notification idea, not something
  to build from scratch.
- **The aria-live announcer pattern for a Stability transition already exists once**, for search:
  `presentVerdict(verdict).announce` feeding a `<jf-system-notice live=...>` (`StatusDeck.ts:545-556`,
  the 595 §15.1 E1 precedent). No equivalent exists yet for `aiEngine.stability`.

### Findings, ordered by how directly they follow from this session's work

1. **The global pill has zero install-awareness — a real, currently-invisible gap.** Traced
   `computeStatusLabel`'s AI branches (`aiStateStore.ts:437-460`): they read `runtime.mode` only
   (`online`/`indexing`/`starting`/`unknown`/else). A user who has never installed AI, who is mid-install,
   or whose install just failed all render the exact same generic **"Offline"** in the footer pill visible
   on every page — indistinguishable from "AI works, currently shut down." `aiEngine.kind` already
   distinguishes `not_installed`/`installing`/`install_failed` from `offline` today; the pill just doesn't
   read it. **Practicality: high.** A user who starts a multi-GB install and switches to Search (a very
   plausible flow — the confirmation modal itself says "this may take a while") currently has NO
   indication anywhere outside the Brain surface that anything is happening, or that it failed. Extending
   `computeStatusLabel`/`computeStatusTone` to source from `aiEngine.kind` (in addition to, not instead
   of, the existing runtime-mode branches, which stay correct for the already-installed cases) would close
   this using only the ONE existing pill, not a new one.
   - **Caution, not a blocker:** the pill is compact (a few characters + a dot). "Installing…" fits;
     a numeric percentage may not, without crowding — a determinate ring/dot instead of inline text is
     the lighter-touch option, worth deciding at implementation time, not assumed here.

2. **The pill's click target is hard-coded to Health, even when the actionable next step is Brain.**
   `core.inference-mode`'s `<jf-control>` always calls `this.openHealth()` (`StatusDeck.ts:475-485`), for
   every `aiEngine.kind`. For `online`/`offline`/`indexing` (states where "how is the system doing
   overall" is the right question), Health is the correct destination — unchanged. For
   `not_installed`/`install_failed` (states where the right next step is "go install/retry," an action
   Health cannot take), routing to the Brain surface instead would remove one navigation hop for exactly
   the users who most need to act. **Practicality: medium** — not a bug (today's behavior is not wrong,
   just one hop further than necessary for a specific subset of states), and the existing
   `requestSurfaceNavigation` seam this same file already uses elsewhere (`StatusDeck.ts:435`) is the
   mechanism, not a new one.

3. **A one-shot "install finished" / "install failed" notification, using the toast infrastructure that
   already exists.** Today, confirming an install completed (or failed) requires either watching the
   Brain surface live or noticing the pill change (once finding 1 ships). A toast fired on the
   `aiEngine.kind` transition into `online`/`install_failed` (keyed off the store's own change detection,
   not a new poll) would notify a user who walked away mid-install, using the SAME `AdvisoryStore`
   mechanism already wired for unrelated one-shot notices elsewhere in this file. **Practicality:
   medium-high** for exactly the "started an install, went to do something else" flow this doc's own §O
   live-repro established is a real, common first-run path — but genuinely optional (the pill fix in
   finding 1 already answers "what's happening" passively; a toast additionally answers "it's done" for a
   user not looking).

4. **Accessibility gap — the new `provisional` dimming has no aria-live equivalent of its own precedent.**
   `verdict`'s Stability changes get an explicit screen-reader announcement (`presentVerdict().announce` →
   `<jf-system-notice live>`); the `aiEngine.stability`-driven dimming this session added to
   `renderRuntimeSection` is visual-only (an opacity change). A sighted user sees "this GPU/VRAM reading
   is about to change (variant switching)"; a screen-reader user gets nothing. **Practicality: medium** —
   smaller audience, but a direct, precedented gap against the SAME pattern family this design otherwise
   conforms to (a `presentAiEngineVerdict(...).announce`-shaped helper, mirroring `presentVerdict`
   structurally, is the natural fix — not a new accessibility mechanism, the existing one just isn't
   reused here yet).

5. **Minor, low-priority consistency fix, incidentally exposed.** `computeStatusLabel`'s fallback branches
   read `if (runtime.mode === 'unknown') return 'offline';` then `return 'Offline';` — an inconsistent
   casing wart, pre-existing and unrelated to this session's work, but one that finding 1's label-source
   migration would naturally subsume (since `aiEngine.kind` already collapses `unknown`/`offline` into
   one clean value). Not worth a standalone fix; worth folding in if/when finding 1 is implemented.

### What this pass is deliberately NOT recommending

- **Not** re-litigating `AiEngineKind`'s flat-string shape into a full exposed cross-product type —
  Design pass 2 §T already made and implemented that call (keep the rollup flat, keep the axes separately
  readable on `AiState`); nothing found in this pass changes that judgment.
- **Not** proposing a new shared "operation intent" store signal — the local/store split from Design pass
  2 §R stands; none of the ideas above need BrainSurface's own click-intent to be visible elsewhere (an
  install-progress toast/pill reflects the STORE's observed `aiEngine`, not another surface's click).
- **Not** naming a new cross-cutting principle here — every finding above is a direct, mechanical
  consequence of `aiEngine` now being a shared fact (the principle worth naming was already named in
  Design pass 2 §T: a store-computed authority becomes usable by surfaces that could not use it before it
  moved out of one component — this section is that prediction paying off, not a new instance of it).
- **Not** claiming any of this is planned, scheduled, or committed — per the request that produced this
  section, these are documented options with a practicality read on each, not a roadmap.

## Design pass 3 (2026-07-01) — the correct shape for the four findings above

> Read-only design theorization, no implementation. Investigated the "Future work" findings above against
> source before proposing anything, per `explore-before-implementing`. Adjacent tempdocs checked: 613
> ("Notifications need one product model and placement strategy" — its own thesis is that a NEW
> notification decision should route through its already-declared, closed vocabulary, which is exactly
> what this design does, not works around); 664/665 (newer-numbered, unrelated — eval-corpus-integrity and
> the observations-inbox workflow, no overlap). 595/596/601/649/662 re-grounded from their shipped call
> sites (already read in full across the earlier passes in this doc), not re-read in prose.

### U. What already exists that resolves all four findings — a presentation-projection layer, already built once, for a different axis

Findings 1 and 4 both reduce to the same missing piece: `aiEngine` (a 595-shaped rollup) has no
presentation projection, the way `verdict` (595's OWN rollup, for search) already does. Reading
`verdict.ts` in full (`:230-363`) shows the exact, complete precedent:

- `verdictHeadline(v)` / `verdictTone(severity)` / `verdictBody(v)` — three small, pure functions, one
  responsibility each (headline text / tone / body text), switching exhaustively on the rollup's `kind`.
- `presentVerdict(v): VerdictPresentation` — composes the three into `{tone, headline, body, announce:
  {text, politeness}}`. Its own doc comment states the principle directly: *"mirrors 557's `present()`
  display projector, extended from the verdict's VALUE to its PRESENTATION, so the wording lives in one
  place."* Every consumer (Health badge, footer, status bar via `computeStatusLabel`, the a11y announcer)
  reads THIS, never `v.kind` directly with its own switch.
- **The a11y announcer this projection feeds already lives in the ALWAYS-MOUNTED surface**, not a
  page-specific view: `StatusDeck.ts:545-557`'s `<jf-system-notice live=${announce.politeness}>` renders
  in the global footer bar, which is mounted on every page — correctly, since a screen-reader user who
  triggers a transition and then navigates away must still hear it settle. A per-page announcer (e.g. one
  local to `BrainSurface`) would go silent the moment the user left that page — the wrong place for this
  specific finding, however natural it looks locally.
- **A separate, ALREADY-WORKING one-shot-toast-on-transition mechanism exists for the same rollup**, for a
  different purpose: `StatusDeck.ts:286-320`'s `announceSettledIfNeeded` tracks a `sawTransitioning` flag
  across `updated()` calls and fires `emitEphemeralToast({..., classId: 'core.verdict.settled'})` exactly
  once per settle-after-transition cycle — not on every render, and not spamming intermediate states. The
  target class (`core.verdict.settled`) is one row in `messageClasses.ts`'s closed, gated vocabulary
  (`LOCAL_MESSAGE_CLASSES`, `check-message-classes.mjs`), not a free-form string.

Findings 1 and 4 are the SAME missing piece (`aiEngine` never got its `presentVerdict`-equivalent).
Finding 3 is a SECOND, separately-precedented missing piece (`aiEngine` never got its
`announceSettledIfNeeded`-equivalent). Both mechanisms already exist, fully built, for the search axis —
neither needs inventing, both need a second, AI-scoped instance.

### V. The correct shape

- **A `presentAiEngineVerdict` projection, in `aiVerdict.ts` (alongside `computeAiEngineVerdict`/
  `applyLocalIntent` — the same "one file per axis, computation and presentation together" placement
  `verdict.ts` already uses for search).** Small headline/tone functions per `AiEngineKind` (including,
  for the first time in ANY presentation layer, the install-specific wording findings 1/4 need:
  "Installing…", "Install failed: {reason}", "Not installed" — text `computeStatusLabel` cannot produce
  today because it never looks at install state at all), composed into the same `{tone, headline, body?,
  announce}` shape. `computeStatusLabel`/`computeStatusTone` (`aiStateStore.ts:414-570`) start consulting
  this for their AI-specific branches instead of hand-rolling `runtime.mode` checks — the existing
  `verdict.kind === 'connecting'/'unreachable'/'transitioning'` priority (the SEARCH-connection axis,
  correctly still first) stays exactly as-is; only the AI-specific fallback branches change their source.
  This closes finding 1 (the pill gains install-awareness) as a byproduct of giving `aiEngine` the same
  presentation layer `verdict` already has, not as a special case bolted onto the pill.
- **A second `<jf-system-notice live>` block in `StatusDeck.ts`, fed by `presentAiEngineVerdict(...).announce`**,
  alongside the existing search-verdict one — NOT inside `BrainSurface`'s local render. This closes finding
  4 correctly (an install/switch transition announces even if the user has navigated away from Brain,
  matching why the search announcer already lives in the always-mounted surface) rather than the earlier,
  under-specified "Future work" draft's suggestion of wiring it into `BrainSurface`'s own dimming, which
  would have gone silent exactly when it mattered most (the user not looking at that page).
- **A second `announceSettledIfNeeded`-shaped tracker in `StatusDeck.ts`, keyed on `aiEngine.kind`**, firing
  through the SAME `emitEphemeralToast` channel with one or two NEW rows in `messageClasses.ts`'s closed
  vocabulary (e.g. `core.ai-engine.settled` mirroring `core.verdict.settled`'s exact policy shape for the
  install-complete case; a `core.ai-engine.failed` row, `supersede: true`, for the install-failure case —
  a repeated failure notification about the same ongoing problem should replace, not stack). This closes
  finding 3 using the governed extension point 613's own thesis says new notification decisions should go
  through, not a new one-off mechanism.
- **Finding 2 (click-target routing) stays a small, separate, local decision** at `StatusDeck.ts`'s
  `core.inference-mode` render case — a `aiEngine.kind === 'not_installed' || 'install_failed' ? Brain :
  Health` navigation choice. It is NOT folded into `presentAiEngineVerdict` (navigation target is a routing
  concern, not a presentation-value concern — keeping it separate matches how `verdictHeadline`/
  `verdictTone`/`verdictBody` also don't encode navigation, only wording/tone).

This is a small, precisely-scoped addition: one new presentation-projection function (mirroring an exact
existing one), one more announcer instance (mirroring an exact existing one), one more transition-tracker
instance (mirroring an exact existing one), two new closed-vocabulary rows, and one small conditional at
an existing call site. No new mechanism, no rewrite, no change to Stages 1-4 or Design pass 2's shipped
code — all of which stay exactly as implemented.

### W. Judging the design's reach

**Is this an instance of an existing principle?** Yes, directly — and it is the SAME principle Design pass
2 §T already named, applied one layer further. §T's principle was: *a derived state authority is
genuinely single-source only when computed once in the shared store AND exposes its axes separately.*
`aiEngine` now satisfies that (Design pass 2, implemented). What THIS pass adds is the natural next
consequence, already documented in this exact codebase's own comments (`verdict.ts:352-354`'s "mirrors
557's `present()` display projector, extended from the VALUE to its PRESENTATION"): **a single-sourced
rollup is only fully usable once its PRESENTATION is ALSO centralized as one projection — otherwise every
consumer re-invents its own wording/tone from the raw `kind`, which is the exact representation-drift
class 594/595/596 exist to prevent, just moved from the VALUE layer to the TEXT layer.** This is not a new
principle; it is the same 557 lineage (Display → Presentation) applied to a rollup that had reached
value-single-sourcing (Design pass 2) but not yet presentation-single-sourcing.

**Where else would this apply?** Any future rollup that reaches Design pass 2's bar (store-computed,
shared, axes separated) should be checked for whether it ALSO has a presentation projection before being
called fully adopted into the 557 family — "single-sourced value" and "single-sourced presentation" are
two separate bars, and this doc's own history shows a real authority can clear the first without the
second (that is precisely what happened here, across two design passes, not one).

**Does existing code already violate it?** `aiEngine` itself, until this pass, is the instance (a rollup
with a value authority but no presentation one). No exhaustive search for OTHER rollups missing their
presentation layer was performed — per this doc's own repeated discipline, this principle is recorded with
its candidate scope, not turned into a codebase-wide audit or a new gate. `check-message-classes.mjs`
already gates part of this shape (a message class must be declared, closed, and forward/backward-checked)
but nothing today gates "does a store-computed rollup have a presentation projection" — appropriately left
ungated until a second, independent instance of the gap justifies it, per `structural-defects-no-repeat`'s
own bar (one clear instance names the class; a second independent one is the trigger to formalize).

**What this pass is NOT proposing:** a generalized "presentation projector" framework or base class (the
existing pattern is three small functions + one composer, copy-pasted per axis with axis-specific wording —
premature to abstract over TWO instances); a gate enforcing "every rollup has a presentation layer" (no
second violation exists yet to justify one); any change to `messageClasses.ts`'s CLOSED-VOCABULARY
mechanism itself (only new rows within it); moving the click-routing decision (finding 2) into the
presentation projection (a deliberate scope boundary, not an oversight).

## Confidence-building pass 3 (2026-07-01) — de-risking Design pass 3 before implementation

> Read-only investigation against source (no feature code changed). Eight uncertainties identified from
> Design pass 3's own proposal; each checked directly against source. One item sharpens the design with a
> concrete boundary the design pass itself left open; the rest confirm the plan is executable as scoped.

1. **`AiEngineKind` → wording/tone mapping — CONFIRMED, fully reusable from existing copy.**
   `BrainSurface.ts`'s `statusConfig` table (`:951-994`) already has label/sub text for all 8 kinds
   ("Not Installed" / "Installing…" / "Install Failed" / "AI Offline" / "Starting…" / "AI Online" ×2 for
   online+indexing / "Connecting…"), and `brainDotTone` (`:161-176`) already maps each to a tone
   (`not_installed`→neutral, `installing`→info, `offline`→neutral, `starting`→warning, `online`→success,
   `connecting`→warning). `presentAiEngineVerdict` should reuse this exact wording/tone table, not invent
   new copy — one worth-flagging nuance for the eventual implementer, not resolved here: `install_failed`
   currently maps to the SAME neutral tone as plain `offline` (its dot is `'offline'`), which reads as
   under-alarmed for a failure state; reusing it faithfully preserves consistency, but a future pass could
   reasonably reconsider it — not this investigation's call to make. **Settles uncertainty 1.**

2. **The "Online — {modelLabel}" richness — CONFIRMED, resolved with a clean boundary.**
   `computeStatusLabel`'s model-label concatenation is a data-value operation (appending
   `runtime.modelLabel`), not a status-wording one — the correct boundary is
   `presentAiEngineVerdict(v).headline` returns the plain "Online", and `computeStatusLabel` keeps its own
   thin `runtime.modelLabel ? `${headline} — ${modelLabel}` : headline` wrapper on top, unchanged in
   behavior, just now sourcing the base headline from the new projection instead of a hardcoded string.
   **Settles uncertainty 2.**

3. **Search-verdict vs. AI-presentation precedence — CONFIRMED, no restructuring needed.** The existing
   `verdict.kind === 'connecting'/'unreachable'/'transitioning'` check should stay FIRST, unchanged — a
   backend-connection problem is more severe/urgent than an AI-install state (nothing AI-related is
   actionable if the backend itself is unreachable). `presentAiEngineVerdict` only needs to supply the
   content for the EXISTING settled-branch fallback (today's `runtime.mode` chain), not a new priority
   tier. **Settles uncertainty 3.**

4. **The one-shot toast mechanism — CONFIRMED safe to mirror, no double-fire risk.** Read
   `announceSettledIfNeeded` in full (`StatusDeck.ts:308-322`): the `sawTransitioning` flag is
   unconditionally reset (`= false`) at the end of its own branch regardless of whether the toast fired,
   so repeated `updated()` calls on an unchanged `kind` cannot re-fire it — confirmed safe to copy this
   exact shape for an `aiEngine.kind` tracker (`sawInstalling` set on `'installing'`, checked/reset on
   reaching `'online'` for success or `'install_failed'` for failure). `emitEphemeralToast`'s signature
   (`ephemeralToast.ts:31-43,65`) trivially supports an error-severity toast via a class's own
   `defaultSeverity` — no special-case mechanism needed for the failure toast. **Settles uncertainty 4.**

5. **`check-message-classes.mjs`'s registration surface — CONFIRMED single point, no hidden second list**
   (unlike the Stage 4 backend-wire surprise this uncertainty was modeled on). Read the gate script in
   full: it is a pure forward+backward correspondence check between `LOCAL_MESSAGE_CLASSES`' declared keys
   and every `classId: '...'` object-literal emitted anywhere under the scan root — one declaration file,
   one scan, no second registry. Adding a declared row AND an actual `emitEphemeralToast({classId:...})`
   call site (both planned) satisfies both directions. **Settles uncertainty 5.**

6. **Click-routing mechanism — CONFIRMED, and sharpened: use the newer helper, not the one the design pass
   named.** `StatusDeck.ts` has TWO coexisting navigation mechanisms: `openHealth()`'s bespoke
   `dispatchEvent(new CustomEvent('navigate-with-context', ...))` (595 §15.3 N3, older) and
   `requestSurfaceNavigation(surfaceId)` (`navigateRequest.ts:17-24`, already used in this same file for
   `core.running-job`'s click-through, `:435`) — a simpler, more current helper.
   `requestSurfaceNavigation('core.brain-surface')` is the correct mechanism for finding 2, not a copy of
   `openHealth()`'s older pattern. **Settles uncertainty 6 — with a correction to which existing mechanism
   to reuse.**

7. **Test blast radius — CONFIRMED small, with one real design refinement.** `aiStateStore.test.ts` pins
   exact `statusLabel` strings (`:76,89,181,186,191,198`) including live-elapsed cases (`'Starting… 12s'`,
   `'Starting… 1m 30s'`) that depend on `runtime.loadStartedAtMs` at render time — a LIVE, ticking value
   `presentAiEngineVerdict`'s static headline lookup cannot produce. **Refinement to Design pass 3:** the
   `starting` kind should stay a special case in `computeStatusLabel` reading `runtime.loadStartedAtMs`
   directly (exactly as today), NOT routed through the new projection — every OTHER AI-specific branch
   (including the three currently entirely-missing ones: not_installed/installing/install_failed) routes
   through `presentAiEngineVerdict`. No existing test needs to change for kinds outside `starting`
   (they were never covered — a currently-hardcoded `else return 'Offline'` for cases the new projection
   will now distinguish). No `StatusDeck.test.ts` coverage of `announceSettledIfNeeded` exists to update or
   mirror precisely — a new AI-scoped toast tracker needs tests written from scratch (mocking
   `emitEphemeralToast`, driving `updated()` through a kind sequence), a modest addition, not a rewrite.
   **Settles uncertainty 7.**

8. **Live-repro feasibility — CONFIRMED unchanged.** Re-checked: this worktree still has no staged
   `native-bin/llama-server/llama-server.exe`. The environmental gap from Design pass 2's confidence pass
   persists; verification for all four findings' new logic will rely on unit tests (the established
   fallback already used and disclosed for the stale-poll fix) rather than a live repro. **Settles
   uncertainty 8 — no change from the prior finding.**

### Net effect on confidence

All eight uncertainties resolved with concrete, source-verified answers; none surfaced a blocker or wrong
assumption in Design pass 3's core shape. Two items sharpened the design with decisions the design pass
itself had left open (item 2's headline/modelLabel boundary, item 6's choice between two coexisting
navigation helpers) and one item added a real scope refinement not previously named (item 7: `starting`
stays a special case, not routed through the new projection, because it needs a live timestamp the static
headline lookup cannot carry). No item required reconsidering the two-piece design (a presentation
projection + a second toast tracker) itself.

## Summary and confidence rating (Design pass 3 implementation)

**What was learned:** every piece of wording, tone, and mechanism this design needs already exists
somewhere in the codebase and was located precisely — the `statusConfig`/`brainDotTone` tables for
copy/tone, `announceSettledIfNeeded`'s exact one-shot-safe shape for the toast tracker,
`requestSurfaceNavigation` (not the older `openHealth()` pattern) for click-routing, and confirmation that
`check-message-classes.mjs` has no hidden second registration point. The one genuine new finding — the
`starting` kind's live-elapsed formatting can't be absorbed into the static projection — is exactly the
kind of thing this pass exists to catch: implementing Design pass 3 literally-as-written would have either
broken the live "Starting… Ns" count-up or produced awkward plumbing to thread a live timestamp through a
function whose whole point is to be a pure, static lookup.

**Critical confidence rating for the remaining implementation work: 8/10.** Comparable to Design pass 2's
post-confidence-pass rating — every mechanism is now verified against the actual files it will touch, not
assumed. It is not higher for the same class of reason as before: no code has been written yet, and the
live-repro gap (item 8) means the toast/announcer behavior's real-world correctness rests on unit tests
until a genuine install is available in this or a similar environment.

## Implementation difficulty and model/effort recommendation (Design pass 3)

**Difficulty: low-medium, single session** — comparable in scale to Design pass 2, arguably slightly
smaller in code volume (one new small presentation-projection file addition, one new toast-tracker method
mirroring an exact existing one, two new closed-vocabulary rows, one small conditional at an existing
call site) but touching one more file (`messageClasses.ts`, gate-checked) and requiring new tests to be
authored from scratch for the toast tracker (no existing pattern to extend, only one to mirror).

**Recommendation: Sonnet, single pass, no staging required** — same reasoning as Design pass 2's
recommendation: the constraint-resolution work (which wording to reuse, which navigation helper, the
`starting`-kind boundary, the gate's exact requirements) has already been done in this confidence-building
pass, not left for the implementer. Opus would not meaningfully reduce risk here; Fable remains a non-fit
(precise engineering work reusing existing UI copy/mechanisms, not creative/writing work).

## Design pass 3 — implemented (2026-07-01)

Executed exactly the plan the confidence-building pass produced, with one real correction found and fixed
during implementation (not caught by the prior pass, but caught before it shipped).

**What shipped:**

- **`aiVerdict.ts` gained the presentation projection**: `aiEngineHeadline`/`aiEngineTone`/`aiEngineBody`/
  `presentAiEngineVerdict`, mirroring `verdict.ts`'s `verdictHeadline`/`verdictTone`/`verdictBody`/
  `presentVerdict` shape exactly. Wording reuses `BrainSurface.ts`'s `statusConfig` text verbatim for the
  four kinds the footer never had wording for (`not_installed`/`installing`/`install_failed`/
  `connecting`); the four it already had (`online`/`indexing`/`offline`/`starting`) keep the footer's OWN
  pre-existing, terser wording ("Online"/"Indexing"/"Offline"), NOT `statusConfig`'s "AI Online"/
  "AI Offline" — a deliberate scope decision, documented in the code, made during implementation: the
  footer has no sub-text slot the way `BrainSurface`'s status card does, so `indexing` needs its own
  distinct label AND its own amber tone (matching the footer's pre-existing, pre-649 convention) rather
  than collapsing to the same "AI Online"/green as `BrainSurface`'s dot does. This was caught by writing
  the wiring, not assumed — the confidence-building pass had confirmed the wording SOURCE but not this
  specific divergence between the two existing conventions.
- **`aiStateStore.ts`'s `computeStatusLabel`/`computeStatusTone`** now project the AI-specific branches
  from `presentAiEngineVerdict` instead of a hand-rolled `runtime.mode` chain; the search-verdict
  precedence check stays first, unchanged; `starting` stays a special case reading
  `runtime.loadStartedAtMs` directly, unchanged (confirmed in the confidence-building pass to need a live
  value the projection can't carry). Closes finding 1 — the footer pill now distinguishes
  `not_installed`/`installing`/`install_failed`, live-verified.
- **`messageClasses.ts`** gained `core.ai-engine.settled` (success, mirrors `core.verdict.settled`) and
  `core.ai-engine.failed` (error, `supersede: true`) — two rows in the existing closed vocabulary.
- **`StatusDeck.ts`** gained: a second one-shot toast tracker (`announceAiEngineSettledIfNeeded`,
  `sawInstalling` flag, mirroring `announceSettledIfNeeded`'s confirmed-safe shape exactly) closing
  finding 3; a second `<jf-system-notice live>` announcer fed by
  `presentAiEngineVerdict(aiState.aiEngine).announce`, living in this always-mounted surface (not
  `BrainSurface`, per the design's own correction of the original "Future work" draft) closing finding 4;
  and a conditional on `core.inference-mode`'s click handler routing to the AI Brain surface (via
  `requestSurfaceNavigation`, the confirmed-current helper) instead of Health when
  `aiEngine.kind === 'not_installed' | 'install_failed'`, closing finding 2 — **live-verified**: navigated
  to Chat, clicked the footer pill (showing "Not Installed"), confirmed it navigated to
  `core.brain-surface`, not Health.
- **Tests**: `aiVerdict.test.ts` gained a full `presentAiEngineVerdict` suite (all 8 kinds' headline/tone,
  the install-failure body text, the alert-vs-status announce politeness split). `aiStateStore.test.ts`
  gained one new test proving the previously-invisible `not_installed` state now produces a distinct
  label/tone (correctly threading the confirmed search-verdict-first precedence — the test initially
  failed until a status-poll success was fed first, exactly the ordering the confidence-building pass had
  already reasoned through but the test itself hadn't yet exercised). `StatusDeck.test.ts` gained 5 new
  tests: the toast fires exactly once on `installing→online` and `installing→install_failed`, does NOT
  fire on a first-load-already-online edge case, does not re-fire on a repeated identical render, and the
  pill's click target differs correctly between `not_installed` and `online`.
- **Verified**: full `modules/ui-web` unit suite — 3483/3484 passing (up from 3466/3467; the one failure
  is the same pre-existing, unrelated `HealthLitView.test.ts` case). Typecheck clean. All three relevant
  gates (`check-message-classes`, `check-ai-verdict-derivation`, `check-controls-a11y`) pass. Live-verified
  via the dev stack + `claude-in-chrome`: the footer pill's new "Not Installed" label (finding 1) and its
  corrected click-target routing to the Brain surface (finding 2) both confirmed working in the real UI,
  no console errors introduced. The completion/failure toast (finding 3) and the aria-live announcement
  for a real transition (finding 4) could not be triggered live in this environment (no staged
  `llama-server.exe`, the same disclosed, unchanged gap as every prior pass) — covered by their dedicated
  unit tests instead, consistent with the confidence-building pass's documented scope limit.

**Net result:** all four "Future work" findings from the earlier ideation pass are closed, using only
mechanisms that already existed in the codebase (the presentation-projection pattern from `verdict.ts`,
the one-shot-toast pattern from `StatusDeck.ts`'s own prior code, the navigation helper already used
elsewhere in the same file, the closed message-class vocabulary) — no new architecture, no rewrite, and
Stages 1-4 plus Design pass 2's shipped code remain untouched throughout.

## Critical review + fix (2026-07-01) — two self-inconsistencies found and fixed

A critical review of everything implemented since the last such pass (Stages 1-4, Design pass 2, Design
pass 3) found two real, substantive issues — both self-inconsistencies introduced within this session's
own recent work, not pre-existing problems. No security/privacy issues found.

1. **`install_failed` tone/severity mismatch (Design pass 3).** `aiEngineTone('install_failed')` returned
   `'neutral'`, while the SAME implementation pass gave the accompanying `core.ai-engine.failed` toast
   class `defaultSeverity: 'error'` — a failed install fired one urgent red toast, then the persistent
   footer pill for that state settled into a calm, neutral tone, understating the problem for anyone who
   missed the toast. **Fixed**: `aiEngineTone`'s `install_failed` case now returns `'error'`, agreeing
   with the toast it was built alongside. `aiVerdict.test.ts`'s presentation table updated to match.
2. **Overbroad `provisional` dimming trigger (Design pass 2).** `renderRuntimeSection`'s GPU/VRAM/Tier
   dimming fired on a bare `stability.kind === 'provisional'`, but that covers FIVE causes, two of which
   (`checking`/`stale-poll`) are about the DATA FRESHNESS of a different fact (install/runtime status),
   not about the GPU values themselves being about to change — reachable when a retained
   `this.inference.gpu` snapshot coexists with `aiEngine.kind === 'connecting'` right after a fresh mount.
   **Fixed**: extracted the condition into a new exported, unit-tested pure function
   `isGpuReadingProvisional` (`BrainSurface.ts`, mirroring this file's own existing
   `formatRestartEtaSub` extraction pattern), narrowed to only `installing`/`starting`/
   `switching-variant`. New test file `BrainSurface.gpuProvisional.test.ts` pins both the included and
   the deliberately-excluded causes.

**Verified**: full `modules/ui-web` unit suite — 3490/3491 passing (up from 3483/3484; the one failure is
the same pre-existing, unrelated `HealthLitView.test.ts` case). Typecheck clean. The
`ai-verdict-derivation` and `controls-a11y` gates both re-run clean. No live browser re-verification
needed for these two fixes (a tone constant and a boolean condition, no markup/structure change) —
covered by the new/updated unit tests, and the surface's general render was already confirmed unbroken by
Design pass 2/3's own live checks.
