---
title: "550 — The operation & action lifecycle: one canonical lifecycle record, governed projections, liveness as an invariant, and the judgement that gates non-user actors"
type: tempdocs
status: active
created: 2026-05-25
restructured: 2026-05-28
category: design-theorization / operation-lifecycle / intent-substrate / agent-governance / cross-cutting-structure
related:
  - tempdoc 553 (canonical search-execution record — THE PARENT PATTERN: one canonical record → governed projections → an execution-surface anti-drift gate; this doc is its sibling, one tier down, for the operation/action lifecycle)
  - tempdoc 542 (operation-scoped lease taxonomy — the op-lease heartbeat/expiry/criticality this design reuses as the liveness authority for in-flight records, Thesis II)
  - tempdoc 490 / 494 / 496 (proactive emission / AdvisoryProjector / inbox filter chips — EmissionPolicy + groupKey + advisory log; reframed as a projection of the one lifecycle record, not a parallel store)
  - tempdoc 543 / 543-fwd (Effect Journal, declared inverses, PendingEffect, capability consent, macros — the FE half of the lifecycle record + grant-as-Effect)
  - tempdoc 549 (unified SearchTrace — one canonical record → many read-views; the same idea at the search tier)
  - tempdoc 530 / 531 (discipline-gate kernel + substrate-consumer-drift gate + grace-period DSL — the forcing-function the projection-coverage gate reuses)
  - tempdoc 508 / 510 (AiStateStore — multi-source aggregation into one derived status; precedent for deriving in-flight state from many contributors)
  - tempdoc 487 (intent substrate — the (SourceTier × RiskTier) trust lattice; the judgement facet, Thesis IV)
  - tempdoc 521 / 532 (plugin + virtual-operation sources — must flow through the same lifecycle record)
  - tempdoc 548 (ui-web theoretical improvements — home for the sibling meta-class instances named in §3: theme tokens, i18n labels, event transport, surface-scoped state)
  - catalog: G119 "Chat-receipt UI for emitted URLs" + G138 "Intent Preview / Plan Summary" (read-views/consumers of the lifecycle record)
---

# 550 — The operation & action lifecycle

> **Scope note.** This is a *design theorization*: the correct long-term structure and the forcing
> functions that keep it correct, deliberately disregarding feasibility, phasing, and file-by-file
> steps. Major rewrites are allowed. A first partial realization (the agent-action spine) already
> shipped (see Appendix); this document widens and re-frames that work around the structure it should
> converge on. Markers: **[V]** = verified against source/live; **[I]** = inferred / judgment.

## 0. Thesis — this is 553's defect class, one tier down

There must be **exactly one canonical record of what an actor or the system did, is doing, or may
do**, and every surface that needs those facts — the receipt, the activity timeline, the advisory
inbox, the indexing badges, the trust-audit, undo, the plan-preview — must be a **governed
projection** of that record, never an independent model of it. When a surface models the lifecycle
independently, the views drift silently, because nothing ties them together.

This is the **representation-drift defect class** that tempdoc **553** formalized for *search
execution* (DRY, Hunt & Thomas: "every piece of knowledge must have a single, unambiguous,
authoritative representation"). 553 did it for "what the search pipeline did"; **550 does it for the
operation & action lifecycle** — the same three pillars (a single canonical record; pure projections,
lossy only downward; an anti-fragmentation gate on the 530 kernel), plus two things the lifecycle
domain needs that a search trace does not: an explicit **state machine with a liveness invariant**
(§Thesis II), and, for non-user actors, a **judgement** about whether the action may happen at all
(§Theses IV–V).

A non-user actor (LLM, agent loop, plugin, MCP, schedule) has three faces — **Preview** (what would
happen), **Authorize** (may it happen), **Outcome** (what happened). A *system* operation (indexing,
enrichment, migration) has only the Outcome face, but it is the *same* Outcome: one lifecycle record,
not a separate "task" world. The first build delivered the agent faces as *a spine plus a menu of
features*, scoped to agent/user actions only. That scoping was the deeper error: it left system
operations in a parallel universe, which is what a live UX pass made impossible to miss (§Appendix —
Live evidence).

## 1. The defect this eliminates — three records of one lifecycle

A 2026-05-28 browser pass and a source probe found **three parallel, non-converging representations
of the operation/task lifecycle**, none of which is the canonical record: **[V]**

1. **The action-ledger** (543/550) — `ActionEventStore` + `ActionLedgerProjection`, an in-memory
   ring of **user + agent *actions only***. It **excludes system/indexing operations** by
   construction. The Activity surface reads this.
2. **The indexing-jobs / Task substrate** — `indexingJobsBridge.ts` projects the Worker's
   **durable SQLite** job queue (`SqliteJobQueue` / `IndexingJobChangeFeed`) into `TaskList.ts`
   badges, by *stateless* reconciliation against the live job set, with **no liveness or
   terminal-state check**. The left-rail badges read this.
3. **The advisory log** (490/494) — `OperationCompletionEvent` → `AdvisoryChangeRegistry` →
   `AdvisoryLog`, an in-memory ring of *completion* advisories. The advisory inbox/toasts read this.

Two symptoms, each a symptom of the missing canonical record, not a one-off bug:

- **F-1 — orphan RUNNING that cannot die.** ~40 "RUNNING · Indexing · default(<jobid>)" badges fill
  the rail while `/api/status` reports `pendingJobs: 0 / indexState: IDLE` — nothing is indexing.
  They return **byte-identical after a full cold restart** (new process, new port): the records are
  disk-persisted in the Worker queue and replayed, and **nothing reconciles a stuck record against
  liveness** — a record marked PROCESSING with no live owner re-streams forever, rendered 1:1 with
  no cap/grouping. → **Theses II + III.**
- **F-2 — the canonical ledger disagrees with the rail.** The Activity surface reads "No operations
  recorded yet" *while the rail floods*; an agent run that incremented the advisory counter 2→3
  never appeared in Activity either. The rail and the Activity ledger **read different records**. →
  **Thesis I.**

The agent-tier failures the first build already documented are the same class on the action side:
plumbed-but-invisible read-views (the live `/api/action-ledger/stream`, `gateFirings`, the batch
preview, `ActionLedgerView` — data layer + tests, no mounted renderer → **Thesis III**); evaluation
drift across ~5 sites (the F1 preview-vs-dispatch disagreement → **Thesis IV**); two logs joined
ad-hoc by `unifiedActivity()` (→ **Thesis I**); four consent mechanisms with no shared abstraction
(→ **Thesis V**); self-validated, branch-only, invisible (→ **Thesis VI**).

## 2. What already exists — extend, do not greenfield

The pieces are present; they are simply not unified under one record + one liveness authority + one
governance. **[V]**

| Existing | Today | Role in the structure |
|---|---|---|
| **553** canonical-record + projections + execution-surface gate | Shipped for search execution. | **The parent pattern** — this doc is its sibling for the lifecycle. |
| **542** op-lease (`OperationLease`: heartbeat, `expiresAt`, criticality) | Multi-agent coordination only. | A **precedent** for lease-based liveness, and the authority for the operations it covers (migration/reindex/gc) — but **not** for the per-document indexing jobs that flood (§B-verified). The per-job authority is the worker drain state + job state-machine, not this lease. |
| **543/550** `ActionLedgerProjection` + `ActionLedgerChangeRegistry` | Projection + fan-in relay over federated *action* stores. | **The projection layer** — built in the right shape; widen its source to all operations. |
| Worker `JobQueue` / `IndexingJobChangeFeed` (durable SQLite) | A separate system-op record + stream, keyed by `pathHash` only. | **A federated contributor** to the one record — but it carries **no correlation id** today (§B); cross-process correlation is net-new work, à la 553's "two contributors, one schema". |
| **490/494/496** `EmissionPolicy` (`renderHint` + `dedupeWindow`) / `AdvisoryProjector` / inbox chips | A parallel advisory store + per-class dedupe. | **A projection** of the one record; bounded projection (cap/group) is **net-new** — `groupKey`/`importanceFloor` (G160/G161) are design-only forward fields, **not** shipped primitives (§B). |
| **530/531** discipline-gate kernel + consumer-drift gate + grace DSL | Governance kernel; the `search-explain-panel-mount` slot is the precedent. | **Home for the projection-coverage / operation-surface gate** (Thesis III). |
| **508/510** `AiStateStore` | Multi-source aggregation into one derived status tier + label. | **Precedent** for deriving in-flight state from many contributors (Thesis II). |
| **487** `(SourceTier × RiskTier) → GateBehavior` lattice + `CoreTrustEvaluator` | Correct, called from many sites. | **The judgement** (Thesis IV). |

## 3. The theses

### I. One lifecycle record (Outcome)
**Correct structure.** A single append-only **LifecycleEvent** record — a discriminated union
carrying `{ id, occurredAt, state, originator(user|agent|system), source/transport,
kind(invoke|gate|navigate|effect|grant|undo|index|enrich|migrate…), subject(op/surface/capability/
job), outcome, correlationId, … }` — that **subsumes user actions, non-user-actor actions, AND
system/background operations**. It is written by **federated contributors** (Head action-ledger;
**Worker job-queue**; FE Effect Journal) correlated by **explicit id** under one schema, never
re-joined per read. The receipt, Activity timeline, advisory inbox, **indexing badges**, 538
trust-audit, and undo are all **projections/filters** over this one record. This dissolves F-2: the
rail and the Activity surface become two reads of one record.

**Extends, doesn't invent.** 553's one-record-→-many-projections and its cross-process "two
contributors, one schema" composition; 549's SearchTrace; 543's Effect Journal as append-only log;
`ActionLedgerProjection`/`ActionLedgerChangeRegistry` (keep as the projection layer). Eliminates: the
`unifiedActivity()` ad-hoc join, the separate indexing-jobs stream as a *rendering* source, and the
advisory log as a parallel store.

**Probe correction (don't understate the work).** The contributors genuinely diverge today: three
outcome semantics (`OperationOutcome` vs `AuthorizationDisposition` vs the FE `pendingOutcome`), a
kind-taxonomy collision (Effect `navigate`/`toast` vs backend `operation`/`navigation`/`gate`, with
no slot for system `index`/`enrich`/`migrate`), **no explicit event IDs** (in-memory ring buffers
keyed by position; the Worker queue keyed by pathHash), and correlation only via `executionId`
(populated only for undo-supported ops). The unified record is therefore a discriminated union with
kind-specific detail + an explicit id + a real outcome/state union — not one flat row. This is the
bulk of the cost.

### II. Lifecycle is an explicit state machine with a liveness / terminal-reconciliation invariant
**(The new primitive this pass exposes.)**

**Correct structure.** Every record has an explicit state:
`pending | running | terminal{ done | failed | cancelled | abandoned }`. The load-bearing rule is an
**invariant, not a transition handler**: *every in-flight (running) record must have a live owner.*
The in-flight view must be **derived from an authoritative running-signal that distinguishes
*queued* from *actually-running* from *terminal***, not asserted by mere membership in a job stream.
The authority is the operation's own lifecycle state-machine **plus the worker's actual drain/liveness**
(which rows the indexing loop is *currently processing*) — **not** 542's op-lease, which a 2026-05-28
probe (§B) confirmed does **not** cover the per-document indexing jobs that flood. A record that is
queued-but-undrained, completed, or owner-less must render as its true state (pending / done /
abandoned), **never as a live "RUNNING" badge**. This is the F-1 cure. **The verified root cause
(§B) is sharper than the original framing:** the flooding badges are `state: PENDING` rows streamed
*unfiltered* and projected as "running" — not orphaned `PROCESSING`, and the 542 lease is irrelevant
to them.

The point is structural: today the "is it running?" answer is *asserted* by whichever store last
wrote it; correctly it is *derived* from "does a live owner still hold this?". This mirrors 508's
`AiStateStore`, which derives a status tier from many contributors rather than trusting any one.

**Extends, doesn't invent.** 508's `AiStateStore` (derive one status from many contributors) is the
precedent for deriving state rather than trusting a single writer. 542's lease is a liveness signal
for the operations it covers (migration/reindex/gc) but is **not** the authority for per-document
indexing jobs (§B); the per-job authority is the worker drain state + the job state-machine. Note a
startup reaper already exists (`SqliteJobQueue.recoverStuckJobs()` resets `PROCESSING`→`PENDING`) — it
is necessary but insufficient: it runs only at startup and does nothing for queued-but-undrained
`PENDING` rows.

**Honest edge.** A genuinely hung worker that *still appears to be draining* is a different problem
(worker cancellation / halt-in-flight), explicitly out of scope (§5).

### III. Every lifecycle surface is a governed, bounded projection
**Correct structure.** Two coupled requirements:

- **(a) Governed — an operation-surface register.** Every read-view is a **registered entry**
  declaring the projection it consumes and the renderer that mounts it; **a registered read-view
  without a mounted, rendering consumer is a build failure**, and **no surface may model lifecycle
  state independently** of the canonical record. This is 553's pillar-C execution-surface gate applied
  to the lifecycle, realized on the **530/531 consumer-drift gate** (data-driven slots keyed on FE
  mount tags + an `expectedMin` floor + the grace-period/changeset escape-hatch). The
  `search-explain-panel-mount` slot — a component that shipped *registered-but-unmounted* and was
  brought under coverage by a slot keyed on its mount tag — is the direct precedent for
  `jf-action-ledger`, the live-stream subscription, and the indexing/advisory projections.
- **(b) Bounded — aggregation is a property of the projection, not the data.** High-cardinality
  projections (the rail) must **cap + group + summarize** ("38 indexing jobs queued" with a
  drill-down), so a true backlog can never render as 40 individual immortal pills. This is a
  **net-new** contract on the projection layer: `EmissionPolicy.groupKey`/`importanceFloor` (G160/G161)
  are **design-only forward fields, not shipped** (§B-verified), so this introduces the bounded-projection
  property rather than generalizing an existing primitive.

Together (a) cures F-1's rendering and locks F-2's convergence; without it the other theses can be
silently shipped invisible (or unbounded) again. "Data layer + unit test" is no longer "done";
"mounted + rendering + bounded + (gate-tracked)" is.

**Probe correction (the home already exists; reuse, don't generalize).** The chat-specific
`check-shape-view-coverage.mjs` is *not* the mechanism to extend — the general `consumer-drift` gate
(531) already is (data-driven `slots.json`, scoped-grep of arbitrary symbols including FE mount tags,
`expectedMin`, grace + changeset DSL). Bring the unmounted Outcome read-views and the lifecycle
projections under coverage by **adding slots**, no script change.

### IV. One intent evaluation (Preview + the gate — the judgement facet for non-user actors)
**Correct structure.** A single authoritative `evaluateIntent(op, args, provenance) → IntentVerdict`
computed at the enforcement chokepoint, returning all facets it *can* at that site: derived source
tier, gate behaviour, availability, reversibility, hard-stop state. Enforcement, the preview
endpoint, the agent-tool emitter, and the plan-preview **read the same computation** (and the same
evaluator instance) — none recompute. The verdict is what gets recorded as the gate LifecycleEvent
(Thesis I) and what 538 audits. The F1-class drift (preview predicting a permissive gate while
dispatch DENIED under the engaged hard stop) becomes structurally impossible.

**Extends, doesn't invent.** 487's `(SourceTier × RiskTier)` lattice + `CoreTrustEvaluator` +
`deriveSourceTier`; `AvailabilityEvaluator`; 538's audit.

**Probe correction (the verdict is not uniform across sites).** Enforcement
(`OperationExecutorImpl.enforceTrustLattice`) has `op + argsJson + provenance + confirmationToken`;
the **preview endpoint has no args and no token**, so it can only produce a *structural prediction*
(derived tier + lattice gate + hard-stop) and cannot evaluate the args-bound consent-capsule binding —
correctly deferred to dispatch. The realistic unification is one shared `deriveSourceTier` + lattice +
hard-stop computation (today duplicated across 2–3 sites); **args-bound facets stay
enforcement-only**, and preview is explicitly the structural-prediction *read* of that one
computation. This is the judgement facet that applies only when the originator is non-user; system
operations skip it.

### V. One capability/grant model (Authorize)
**Correct structure.** A single **Grant** primitive: a caveat-bearing token (scope / budget / TTL /
delegation / revocation), attenuable (a holder can only narrow it) and revocable. The current
mechanisms are points on this model:
- consent capsule = a **max-attenuated, single-use, short-TTL grant** bound to (op, args);
- durable capability consent = a **persisted grant** (a wider caveat set);
- the autonomy dial = the **issuance policy** that decides which grants auto-issue per source × risk;
- the Global Hard Stop = a **global revocation** over all non-user grants.

Grants are recorded as LifecycleEvents (Thesis I) → one audit, one revocation path, one ceremony.

**Extends, doesn't invent.** macaroon-style attenuation (the capsule is already a single HMAC — the
long-term shape is a chained MAC for delegation + a revocation list); 543's grant-as-Effect; 487's
source tier as the issuance input.

**Probe correction (only two of the four are grants).** The **consent capsule** is the only real
grant today; **durable capability consent does not exist yet** (design-only). The **autonomy dial** is
a *stateless policy function* (the lattice), not an issued revocable token — it is the *issuance
policy*. The **Global Hard Stop** is a *circuit-breaker toggle*, not a grant — model it as a global
*revocation/deny predicate* operating on grants. So the Grant primitive unifies capsule + (future)
durable consent; the dial and hard stop are the policy and revocation that act *on* grants.

### VI. Verification/process is structural, not honor-system
**Correct structure.** A read-view/substrate-shipping change is "done" only with (a) a mounted
renderer (the Thesis III gate), (b) an **independent reviewer** (≠ implementer) on the
substrate-shipping commit, and (c) **live verification** (real backend + the rendered surface), not
just compile+unit. These are encoded into the **530 kernel** as the `independent-review` discipline
gate, not aspirations. The entire first build was self-validated, branch-only, with the visible tier
never run — exactly what this prevents.

## 4. The same shape elsewhere (one source + a coverage contract + governed projections)

The live pass surfaced four more defects that are *not* lifecycle issues but are the **same
meta-class**: a missing single source + a missing completeness/coverage contract + ungoverned
consumers. They are named here so the through-line is recorded; their full design belongs in
**tempdoc 548**, not here.

- **Theme tokens (F-3 — the built-in "Light" theme renders dark-on-dark, invisible text).** Correct
  structure: a surface **declares the tokens it consumes**; *every* theme — built-in **and**
  plugin — must satisfy the full declared token set or **fail a gate**; no partial theme, no
  invisible fallback ships. (Today a coverage test exists but covers only the shipped Dark catalog;
  Light and manifest themes are unchecked.)
- **i18n labels (F-4 — the command palette renders raw `ops.*.label` / `surface.health.label` keys).**
  Correct structure: a label is part of the operation's/surface's **canonical definition**; a gate
  fails when a referenced key has no catalog entry; **no raw-key fallback ships**. (Ties to 509,
  whose `<jf-op-button>` fix the palette bypasses.)
- **Event transport (F-5/F-6 — Health shows "Disconnected / 0 B", Logs "Waiting for log events…",
  while chat SSE works).** Correct structure: **one event substrate** with a uniform connect-snapshot
  + delta + heartbeat contract; surfaces are *consumers*, not bespoke per-surface EventSources.
  (448's DiagnosticChannel is the partial start; three independent SSE channels exist today.)
- **Surface-scoped state (F-7 — the doc-preview pane leaks across surfaces; F-8 — the active
  conversation is not resumed on re-entry).** Correct structure: pane/inspector visibility **and**
  active-conversation identity are **addressable surface-scoped state** (489's URL-addressable shell /
  StateSnapshot), not global shell singletons → no cross-surface leak, and resume becomes automatic.

The principle, stated once: **one canonical source per concept; a completeness/coverage contract that
fails the build when a consumer's needs aren't met; every surface a governed projection.** 553 proved
it for search execution, 550 for the lifecycle; these four are the next applications.

## 5. What prevention can and cannot achieve (the honest core)

Borrowing 553's realism: the operation-surface gate (Thesis III) makes the fragmentation class
**structurally hard to re-enter within its declared scope (~100%)** — it does **not** prevent
duplication in general. The discovery problem (knowing two things are one concept) and unprojectable
behaviour remain irreducible; for that residue there is only detection-plus-review, reduction not
prevention. Liveness reconciliation (Thesis II) is only as strong as the owner-lease being
authoritative. And the **AHA guardrail** applies: do not over-unify records that do not share a reason
to change — the discriminator is a shared *reason to change*, not surface similarity. The lifecycle
record is one concept (a thing an actor/the system did) precisely because all its projections must
agree about it; that is the test, not "they look alike."

## 6. Out of scope / open questions
- Exact caveat grammar for the Grant (scope predicates, budget units, delegation depth, revocation as
  list vs epoch vs grant-state event).
- Whether the lifecycle record is one process-spanning event type or one *schema* with federated
  projections reconciled by correlation id (the Head/Worker/FE split).
- Whether availability stays a distinct facet of the verdict or folds into the gate (keep distinct:
  it is emit-time discovery, not dispatch-time consent).
- **Halting in-flight work** (vs reconciling a dead owner) — a separate worker-cancellation mechanism,
  not part of liveness reconciliation or the grant model.
- The disk-persistence story for the unified record (the Worker queue is durable; the Head rings are
  not) and how replay reconstructs reconciled state on cold start.

---

## Appendix — Live UX evidence (2026-05-28 browser pass)

The widening in this rewrite is grounded in a live browser pass against a running dev stack
(Qwen3.5-9B online, 113-doc corpus), not inference. **[V]**

- **F-1 (Thesis II).** The rail showed ~40 "RUNNING · Indexing · default(<jobid>)" badges while
  `/api/status` reported `pendingJobs: 0`, `indexState: IDLE`, `recentJobQueueDepth: [0]`. The badges
  survived page reload, hot-reload, **and a full cold restart (new run id, new API port)
  byte-identical** — proving disk-persisted orphan RUNNING records replayed with no liveness
  reconciliation, rendered 1:1 with no cap/group.
- **F-2 (Thesis I).** The Activity surface read "No operations recorded yet" while the rail flooded;
  a separate agent run incremented the assistant advisory counter 2→3 yet **never appeared in
  Activity** — two surfaces reading two records.
- **Meta-class (§4).** Selecting the built-in "Light" theme produced invisible dark-on-dark text
  (Dark + the Nord plugin theme rendered fine); the command palette listed raw `ops.*.label` keys;
  the Health surface showed "Disconnected / 0 B" and Logs "Waiting for log events…" while chat SSE
  streamed correctly; the doc-preview pane persisted across Search → Chat → Activity; re-entering the
  chat surface started a blank conversation. Each is the same "missing single source + coverage
  contract + governed projection" shape.

The core RAG/agent loop itself verified **well** (accurate, grounded, well-cited streaming answers;
working cancel; the agent tool-call producing a correct file attribution and recording an Outcome
event) — the defects are lifecycle-coherence and presentation-coverage, not the substrate's
correctness.

## Appendix — first partial realization (what's shipped)

The backend spine + a first set of read-view/grant/eval features are implemented and
backend-tier-verified on `worktree-550-impl`. They are the *first increment* of the (now widened)
theses, scoped to agent/user actions — the system-operation half (Thesis I widening, Thesis II
liveness) is the principal unrealized work this rewrite names.

**Spine (Authorize/Outcome/Preview backend):** consent capsule + approve-by-pending-id + one ceremony
host; the action-ledger projection + live SSE (`/api/action-ledger`, `…/stream`); trust-gate firings
recorded + trust-audit read-view; the Preview availability channel (`/api/operations/{id}/preview`).

**High-confidence extensions:** gate prediction in preview · capability-derived availability · agent
tool-call batch event · richer approval prompt · lattice-level Global Hard Stop · the F1–F3
critical-analysis fixes (the preview now reflects the hard stop — the drift Thesis IV removes
structurally).

**Known gaps (reframed by the theses):** the Outcome read-views are plumbed but not mounted (→ III);
two *action* logs joined ad-hoc and **system operations entirely outside the record** (→ I); no
lifecycle state machine / liveness reconciliation, so orphan RUNNING persists (→ II); five eval sites
(→ IV); four consent mechanisms (→ V); self-validated/branch-only (→ VI). Plus canonical-doc updates,
the merge to `main`, and the independent reviews below.

## Appendix — independent review (thesis VI, 2026-05-27)

Per thesis VI (reviewer ≠ implementer), an independent reviewer agent statically reviewed the four
shipped substrate commits (the coverage-slot, the shared evaluation, the one-log id, the grant) against
the theses + the substrate-discipline rules. **Verdict: APPROVE-WITH-NITS.** All verified correct
against source: the shared evaluation preserves the hard-stop DENY and shares ONE evaluator instance
(F1 drift structurally impossible); the grant keeps the capsule token format/verification unchanged and
is fail-closed; the deterministic id is stable across snapshot + stream and `toWireRow` emits it; the
coverage slots are genuine forcing functions (fail when unmounted). Two NITs logged to observations
(id collision for two same-subject events at one `Instant`; a dropped error banner) — neither a blocker.

## Appendix — independent review of the critical-analysis fixes (thesis VI, 2026-05-27, round 2)

An independent reviewer (not the implementer) reviewed the F1–F6 critical-analysis fix commits.
**Verdict: APPROVE-WITH-NITS, no blockers** — all six verified correct against source, fail-closed
discipline intact, no weakened tests, no hollow code. Addressed in follow-up: the revocation predicate
was aligned to the gate exactly (`UNTRUSTED` only, so a user-mediated MEDIUM approval survives an
emergency stop); the navigation fan-in was made fail-soft uniformly with the operation/gate paths;
ordering was a non-issue (the snapshot re-sorts by `occurredAt`).

## Appendix — thesis VI realized as a gate + final review (2026-05-27, round 3)

Thesis VI is no longer honor-system prose. The `independent-review` discipline gate
(`scripts/governance/gates/independent-review/`, registered in `governance/registry.v1.json`) encodes
implementer ≠ validator and static-green ≠ live-working into the 530 kernel: each declared substrate
slice in `gates/independent-review/slices.json` must carry a review record where reviewer ≠ committer,
verdict is `approve`, `liveVerified` is true, and `coversThrough` is still the substrate tip (checked
via `git diff coversThrough...HEAD` over the slice's `substratePaths`). This raised tier-register row
#30 (`independent-reviewer-required`) from `prose-only` → `gate`. The gate self-tests and ships a
17-check truth-table unit test.

**Final independent reviews (reviewer ≠ committer).** A full-tip review verified all theses against
source across the six substrate-discipline rules: **APPROVE-WITH-NITS, no blockers**; the two surfaced
nits were addressed (the deterministic-id `Instant` collision is fixed by folding in a kind's projected
discriminators, re-reviewed by a second independent agent **APPROVE** and re-live-verified; the
`jf-action-ledger` coverage is a true forcing function via the general read-view discovery). Live
verification at the tip via `IsolatedBackendFixture`: `ActionLedgerE2ETest` + `OperationPreviewE2ETest`
+ `ConsentCapsuleRecoveryE2ETest`, 0 failures.

**Deliberately out of scope (per §6).** Per-kind-store *elimination*, durable-grant disk persistence,
macaroon delegation, and folding availability into the verdict remain §6 open questions. The
widened-scope work — Thesis I's system-operation contributor, and Thesis II's lifecycle state machine
+ liveness reconciliation — is the principal design surfaced by this rewrite and is not yet realized.

## Appendix — §B De-risking probe corrections (2026-05-28)

Before implementation, a confidence pass traced the four load-bearing *reuse* claims to source + a
live experiment. It **corrected the original Thesis II root-cause attribution** (twice) and tightened
Theses I and III. Every claim below is **[V]** (file path or live observation).

**B.1 — The F-1 flood is `PENDING`-undrained rows streamed unfiltered, rendered as "running."**
Not orphaned `PROCESSING`, not the 542 lease, not even DONE-as-running (an interim hypothesis).
Verified on a running stack: `GET /api/indexing-jobs/stream` returned dozens of rows all
`"state":"PENDING"` (`attempts:0`, identical enqueue timestamp; collections `justsearch-help` +
`default`), while `/api/status` reported `pendingJobs:0, processingJobs:0, indexState:IDLE`. Chain:
- `IndexingJobsChangeStream.readAllRows()` (`modules/indexer-worker/.../queue/IndexingJobsChangeStream.java:191-194`)
  is `SELECT … FROM jobs` with **no WHERE** → streams every state (PENDING, PROCESSING, **DONE**, FAILED).
- `projectJobsToTasks` (`modules/ui-web/src/shell-v0/substrates/tasks/indexingJobsBridge.ts:88-101`)
  treats **only `FAILED` as terminal**; every other state → a **"running"** Task badge. So PENDING
  (and DONE) rows render as perpetual RUNNING pills.
- `SqliteJobQueue.markDone` sets `state='DONE'` (the row **persists**, not deleted), so DONE rows are
  eligible to flood too; `recoverStuckJobs()` resets only `PROCESSING`, never PENDING.

**B.2 — A second, independent drift: the count disagrees with the list.** `/api/status`
`pendingJobs:0` contradicts the stream's many PENDING rows — two job-queue representations (likely
per-generation/per-collection scopes) disagree on "how many pending." This is Thesis I's point made
concrete and is *itself* evidence the canonical record is missing.

**B.3 — Corrected Thesis II authority.** The higher-level invariant holds ("in-flight must be
*derived* from an authoritative running-signal, not *asserted* by stream membership"), but the
mechanism is **not** 542's op-lease: per-document indexing jobs are **not lease-covered** (542 leases
only migration/reindex/gc — `IndexingController.handleMigrationStart`, `BulkReindexHandler`,
`RebuildIndexHandler`, `IndexGcHandler`). The correct authority is the **job state-machine semantics
(queued vs running vs terminal) + the worker's actual drain state**, plus a **bounded projection**
(B.5). Thesis II body + the §2 table rows are corrected accordingly.

**B.4 — Corrected Thesis I scope/phasing.** `ActionEvent` is *already* a clean sealed union
(`OPERATION/NAVIGATION/GATE/GRANT/EFFECT`, explicit deterministic id, `originator user|agent|system` —
`modules/app-observability/.../ledger/ActionEvent.java`), so the action-side unify is low-friction.
But the Worker→Head path (`SubscribeIndexingJobs` gRPC → `RemoteIndexingJobsBridge` →
`IndexingJobsChangeRegistry` → `/api/indexing-jobs/stream`) carries **only `pathHash`, no correlation
id**. So folding system/indexing operations into the one record is **net-new cross-process correlation
work** and a *later phase* — and may be better modeled as a registered **projection/contributor** than
as a member of `ActionEvent`. Three outcome taxonomies (op `SUCCESS/FAILURE/UNDONE` vs gate
`disposition` vs grant `action`, plus job `state` and `OperationOutcome`) confirm the union is
discriminated, not flat.

**B.5 — Corrected Thesis III.** III(a) is **confirmed solid and already realized for the
action-ledger**: the `consumer-drift` gate is data-driven (`gates/consumer-drift/slots.json` +
`scripts/governance/gates/consumer-drift/`), keys slots on arbitrary symbols incl. FE mount tags
(`search-explain-panel-mount` → `jf-search-trace` is the precedent), and the
`action-ledger-view-mounted` slot (`jf-action-ledger`, expectedMin 1) **already exists and passes**
— `<jf-action-ledger>` is mounted in `ActivitySurface.ts` (the earlier "zero mount sites" probe was
wrong: it checked only the two `Shell.ts` files and missed `ActivitySurface`). So Thesis III's
gate-enforced read-view coverage is shipped, not pending. III(b) is corrected: **`EmissionPolicy.groupKey` does not exist** — the shipped record
(`modules/app-agent-api/.../registry/EmissionPolicy.java`) has only `renderHint` + `dedupeWindow`;
`groupKey`/`importanceFloor` are doc-forward design-only. Bounded projection is therefore net-new, and
no rail/advisory renderer caps or groups today (`TaskList.ts` / `AdvisoryInboxDrawer.ts` render 1:1;
only *acknowledged-advisory storage* is capped at 500).

**Net effect.** The design's *direction* (canonical record → governed, bounded projections → anti-drift
gate; in-flight derived not asserted) survived intact and is now grounded in verified facts; the
specific *reuse mechanisms* were corrected (542-lease → job-state+drain authority; "generalize groupKey"
→ net-new bounded projection; "correlate by id today" → net-new correlation). No remaining load-bearing
lifecycle claim rests on an unverified assumption.

## Appendix — Theses I + III realized (2026-05-28, federated-projection pass)

The system-operation half this rewrite named as "the principal unrealized work" is now implemented
on `worktree-550-impl`, with two design refinements taken during implementation (both on substrate
discipline, both recorded here):

- **Thesis I — federated projection, not a literal one-stream (§6 open question resolved).** The one
  lifecycle is the worker job state machine. The rail keeps the live `/api/indexing-jobs/stream`
  Resource (a *declared* governed projection); a new `ActionEvent.Index` variant + a Head-side
  translator (`IndexingJobsBridgeWiring`) fold **terminal** indexing outcomes (DONE/FAILED on a live
  `Update` only — never snapshot/reconnect) into the ONE action-event log, so Activity shows
  indexing happened (F-2 dissolved: indexing now appears in Activity, previously "No operations
  recorded yet"). The literal "rail re-sources from `/api/action-ledger/stream`" variant was
  **rejected** after finding `ActionEventStore` is a bounded 500-event ring: per-transition emission
  would flood it and evict agent actions from Activity. This resolves §6's open question toward
  "federated projections reconciled by correlation id (`pathHash`)".
- **Thesis III(b) — bounded as a projection-layer property, FE-only.** `boundedProjection.ts`
  (`countByKey` / `capWithOverflow` / `collapseBursts`) is consumed by the rail (`TaskList`) **and**
  the Activity timeline (`ActionLedgerView` collapses an indexing burst to one "Indexed N ·
  `<collection>`" summary). The advisory inbox is deliberately **not** a consumer (a filtered
  scrollable drawer — a different bounding regime; forcing it would be over-unification per §5 AHA).
  The `EmissionPolicy.groupKey`/`importanceFloor` Java fields were **not** added — with no backend
  consumer they would be C-018 dead substrate; the bounded property's correct locus is the FE
  projection layer where the render bound applies.
- **Thesis III(a) — semantic teeth beyond the type allowlist.** The `operation-surface` gate gains a
  `consumesProjection` lineage check (every surface must name a resolvable semantic source), and a
  live-stack `IndexingLedgerCoherenceTest` asserts the ledger is a faithful terminal projection of
  the actual jobs (exactly one terminal event per ingested file — the over/under-emission guard the
  worker-local conformance test cannot see).

Verification: backend unit + FE unit (full suites green); operation-surface gate + self-test green;
the live-stack coherence test + the browser convergence pass are the Thesis VI live tier
(`independent-review` slice `550-thesis-i-iii-federated-projection`).
