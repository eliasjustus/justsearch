---
title: "595 — Observed-state authority, deeper: the missing 'provisional' dimension. 557's Observed-state projection modelled known-vs-unknown but never settled-vs-in-flux, so the FE renders every provisional state as a settled fact — a contradiction (the health verdict, computed ≥3 ways, disagrees on the 'unknown' boundary so 'Service degraded' and '✓ All systems operational' show on one screen), a catastrophe (a worker rebuild's transient 0-docs / 'No watched folders' reads as data loss), and a freeze (a stalled jobs feed reads as settled counts). The correct long-term structure is a single derived Stability axis (settled | provisional·cause) on aiStateStore — generalizing the one case 557 already got right (ConnectionPhase.stale 'hold last-known') — with a derived system-health verdict as its rollup; transition-/freshness-sensitive renderers become total functions over (value, stability), so 'provisional rendered as settled' is unrepresentable. Surfaced by the 593 walkthrough (§C/§I)."
type: tempdocs
status: "FRONTMATTER RECONCILE (2026-06-17, 593 second-pass regression sweep): this 'not yet implemented' line is now STALE — the 593 re-run confirms the two highest-severity faces SHIPPED. #1.1 split-verdict is FIXED (sweep ✅#6: a single coherent 'System Health · Reduced capability' verdict; the `check-verdict-derivation` gate is live per CLAUDE.md / 595 §4.2) and #1.2 data-loss-illusion is FIXED (sweep ✅#8 / via 599 A1: worker-down now renders 'Files N · Last known' + 'Queue · Rebuilding…' not '0 docs / No watched folders'). REMAINING-OPEN: the full `Stability`-axis substrate as designed, and the connection over-pessimism mechanism (R2: CONNECTION flaps to 'Reconnecting…' at idle while the API is healthy — sweep Still-Present #7), whose live-connection/SSE-reconnect ROOT is now owned by 604. 595 stays open as the substrate/Stability-axis owner; update this line to a precise as-built when the axis lands. ── PRIOR (design-theory, 593-dated): open — design theory, not yet implemented; takeover investigation + long-term design round complete 2026-06-16 (§4 redesigned, §8 investigation). DESIGN: the three defects are one missing epistemic dimension — 'provisional' (real-now-but-in-flux), which 557's known/unknown observed-state never modelled. Correct structure (§4): a single derived **Stability axis** `settled | provisional·cause` on aiStateStore that GENERALIZES the one provisional case 557 already got right (ConnectionPhase.stale), a **SystemHealthVerdict** rollup that reads it (closes 1.1, with a single-derivation gate like check-search-issuance), and transition-/freshness-sensitive renderers made **total over (value, Stability)** so 'provisional rendered as settled' is unrepresentable (closes 1.2/1.3). EXTENDS 557 §2.B, REUSES 575's liveness machinery (fills 575's explicitly-deferred system-transition spine), respects the 594 leaf/595 rollup cut, and is the single upstream source 596 asked to project. Verified against `main`: #1.1 confirmed and upgraded to a THREE-way divergence (header alarms / footer greens / readinessNotice silent on `retrieval==='unknown'`); §6.2 wire-discovery RESOLVED (the `worker.migration` block is already on `/api/status` + in FE generated types with zero consumers ⇒ Move 2 is pure FE projection, no api-record), with one residual live-repro caveat (§8.3.1: is the migration block populated during the 0-docs window, or is that a worker-DOWN/restart window?). TWO corrections: #1.3's 'snapshot-on-open' mechanism is WRONG (TaskList does subscribe; real cause is two divergent job-count authorities — SSE substrate vs `/api/status` poll — with the SSE side able to stall), and Move 1's input set narrowed (`recommendedActions` is HealthSurface-local, not the divergence axis). Recommended sequencing in §8.6. CONFIDENCE PASS (§9) + USER-FACING INSPECTION (§10) complete 2026-06-16 — remaining-work confidence 8/10. §9 resolved the two highest-impact risks from code (the 0-docs window has a worker-DOWN sub-window `indexState='UNAVAILABLE'`/`workerRpcStale` THEN a worker-UP-building sub-window `migration.migrationState='MIGRATING'`; defect 1.2 is a missing-Unknown-state bug, folder endpoint 503s; the verdict single-derivation gate is buildable on the check-search-issuance pattern). §10 browser inspection found the split LIVE and cross-surface (status bar 'Online' vs Health 'Service degraded' simultaneously — `computeStatusLabel` is a 4th verdict site) and surfaced a NEW user-facing design requirement: the live `retrieval` composite is DEGRADED solely because an OPTIONAL re-ranker (LambdaMART) is unconfigured while every other component is READY — so a merely-unified verdict would be CONSISTENTLY over-alarming ('Service degraded' for a cosmetic gap) and the search banner is factually wrong ('semantic degraded/keyword results' when semantic search is fine). The verdict therefore needs a SEVERITY dimension + a per-reason tone/wording table (an FE-owned projection of the reason vocabulary, like readinessNotice.CAUSE_ROWS), and `transitioning` must use a non-alarming 'busy' tone (not the orange 'degraded' alarm) holding last-known values. Refined user-facing design + tone ladder in §10.5."
created: 2026-06-16
author: agent
extends: tempdoc 557 §2.B (Observed-state authority — the tri-state ConnectionPhase + the prevention ladder Collapse > Unrepresentable > Generate > Gate). THIS DOC EXTENDS 557, it does not re-derive it.
category: frontend / ux / design-theory / single-authority / presentation / observed-state
related:
  - tempdoc 557 (presentation = three single-authority projections: Display / Observed-state / Theme; the ladder). The framework this gap lives in; Observed-state is its SECOND projection. OPEN.
  - tempdoc 594 (Display authority depth — the factual content of informational chips). THE SIBLING. 594 §9.5 draws the cut explicitly: 594 projects a LEAF fact's value (single source, never a rollup); 595 derives a VERDICT/PHASE (a rollup over many leaves + the stability axis). "Is the reranker enabled?" = 594 leaf; "is the system healthy / mid-transition?" = 595 rollup. OPEN.
  - tempdoc 596 (operability authority depth — an unavailable affordance must surface its reason). THE OTHER SIBLING (594/595/596 trio). 596 models per-affordance `Availability = available | unavailable·reason·transient?`; its `transient?` flag is the affordance-scoped DOWNSTREAM projection of the system transition 595 authors. 596 §5/§6Q3/§8.7 explicitly defer the system-level phase machine to 595 under "coordinate, don't duplicate" — 595's stability axis is the single upstream source 596 should project, not re-derive. OPEN.
  - tempdoc 575 (observed-happening register — per-concept liveness: heartbeat-lease / promise-bounded / polled-state, `isInFlightLive`, generated liveness windows). IMPLEMENTED+MERGED. 575 §13 L1 / §17.6 EXPLICITLY scoped OUT system-level transitions (worker rebuild / generation-switch / boot) and DECLINED a single aggregate verdict (Face B is "compose, not fuse", 571 §9.D). 595 fills exactly those deferred gaps and REUSES 575's liveness-window machinery for transition/stream freshness.
  - tempdoc 574 (per-instance vs global frontend scope). Relevant to 1.2: the watched-folder list is per-surface LOCAL fetch state (`LibrarySurface.roots`), not global observed-state — which is WHY 557's existing stale-holding doesn't protect it. The transition-sensitive READ projections want lifting toward the global observed-state authority (or at least consulting the stability axis before wiping).
  - tempdoc 558 (presentation authority, deeper — the depth-coverage PRECEDENT). CLOSED.
  - tempdoc 559 (the presentation projection, completed — projects the rendered ELEMENT, not the value). Considered as a home and rejected: 559's thesis is the element-catalog seam; this is a value-verdict single-authority finding, which is 557 §2.B's domain. OPEN.
  - tempdoc 593 (UX walkthrough — §C the 'Service degraded' + 'ALL SYSTEMS OPERATIONAL' contradiction; §I 'a healthy rebuild looks like data loss', named the single worst FE-only finding; §5 the frozen Tasks panel). CLOSED.
  - tempdoc 504 (systematic UX audit taxonomy — D4 'missing-state-narrative' is the #2 symptom; D5/contradiction is #1).
  - CLAUDE.md `structural-defects-no-repeat` (one documented silent bug proves the class; #1 is provable in code + 593 evidences all three — bar cleared).
  - CLAUDE.md `interrogate-results` / `audit-driven-fixes-need-test` (the 593 author's own correction: the 'data wiped' alarm was premature — the state was a TRANSIENT that self-healed; the reportable defect is the alarming/contradictory transient, not data loss).
---

# 595 — Observed-state authority, deeper: the missing 'provisional' dimension

> **What this document is.** A *design theory* for the one dimension the JustSearch frontend's
> **Observed-state** authority (tempdoc 557's second projection) never modelled — and the correct
> long-term structure that closes it. It is **not** a new framework. 557 already stated the framework
> (presentation = three single-authority projections: **Display**, **Observed-state**, **Theme**) and
> shipped its first cut to `main` (the 4-state `ConnectionPhase`, the `Maybe<T>` tri-state fields, the
> last-known-on-stale retention in `aiStateStore`). This doc inherits 557's invariant and ladder
> wholesale and identifies the **missing epistemic dimension** the 593 walkthrough's three
> highest-severity FE findings all share.

> **The meta-finding (why this doc exists).** 557's Observed-state model has two epistemic states for
> what the UI knows: **`Known<T>`** and **`Unknown`**. It made conflating *unknown* with a concrete
> value unrepresentable ("no data" ≠ "0 files"). But it never modelled the third category the live
> system constantly passes through: **provisional** — a value that is *real right now but in flux*,
> a waypoint of a bounded operation that will change it. With no notion of "provisional," every surface
> independently mistakes a provisional state for a settled one, and does so in whatever way its local
> code happens to: as a **contradiction** (1.1 — the health verdict, computed ≥3 ways, disagrees on the
> `'unknown'` boundary), a **catastrophe** (1.2 — a rebuild waypoint of 0 docs / "No watched folders"
> read as data loss), or a **freeze** (1.3 — a stalled jobs feed read as settled counts). This is the
> Observed-state sibling of 594 (which found the Display projection can't reach a chip's *factual
> content*) and 596 (which found operability knows a *reason* it can't deliver) — same 558 depth-round
> shape, the Observed-state facet. The unifying fix is a single derived **Stability axis** that
> generalizes the one provisional case 557 already got right (`ConnectionPhase.stale`).

---

## 1. The defects (three faces of one root)

### 1.1 Split verdict — "Service degraded" AND "✓ All systems operational" on one screen (root PROVABLE in code)

`HealthSurface.ts` computes the system-health verdict **twice, independently**, over the same
`readiness.retrieval` field. The field is a closed tri-state — `aiStateStore.ts:109`:

```ts
retrieval: 'ready' | 'degraded' | 'unknown';
```

**Header verdict** (`HealthSurface.ts:704–727`):

```ts
const retrievalReady = readiness ? readiness.retrieval === 'ready' : (…fallback…);
const allGreen = apiConnected && !reconnecting && retrievalReady && !reindexRequired && !hasOpenConditions;
// …
else if (!retrievalReady) label = 'Service degraded';
```

**Footer verdict** (`renderRecommendedActions`, `HealthSurface.ts:1120–1141`):

```ts
const degraded = readiness != null && (readiness.retrieval === 'degraded' || readiness.reindexRequired);
if (degraded) { …'Attention needed'… }
return html`<h3>✓ All systems operational</h3>`;   // ← the fall-through
```

The boundary value is **`'unknown'`** — exactly what a worker-generation restart yields while
readiness can't determine retrieval. The two predicates handle it with **opposite default polarity**:

| `readiness.retrieval` | Header (`=== 'ready'`?) | Footer (`=== 'degraded'`?) | Result |
|---|---|---|---|
| `'ready'` | ready → green | not degraded → operational | agree ✓ |
| `'degraded'` | not ready → **degraded** | degraded → **attention** | agree ✓ |
| **`'unknown'`** | not ready → **"Service degraded"** | not degraded → **"✓ All systems operational"** | **CONTRADICT** |

Two more divergence axes compound it: the header gates on `connPhase ∈ {'connecting','stale'}`; the
footer ignores `connPhase` entirely (gates only on `status != null`). And `readinessNotice.ts` is a
**third** independent interpreter of the same readiness state. **One concept ("is the system
healthy?"), three predicates, no shared derived verdict** — a textbook 557 §2.B single-authority
violation. This needs no live stack to confirm; it is visible in the source.

### 1.2 Transient rendered as definitive — "a healthy rebuild looks like data loss" (593 §I, highest severity)

During a Force-Rebuild worker restart, `/api/status` momentarily returns 0 docs and an empty folders
list. The FE renders that faithfully as a **settled** state:

- **"No watched folders"** — `LibrarySurface.ts:647`, `BrowseSurface.ts:511`
- **0 docs / 0 size** in the status bar

There is no observed-state that distinguishes *genuinely empty / no folders configured* from
*momentarily unavailable because the worker is rebuilding*. So the FE renders the bottom of a
transition as a fact, and the 593 author concluded — twice — that the index had been destroyed
(§I: *"A user would reasonably conclude the rebuild wiped their index and panic. I did, twice, until
I checked the API."*). Per `interrogate-results`, the honest defect is **the alarming/contradictory
transient**, not data loss — the state self-healed in ~30–60s. But the FE gave the user no way to
know that.

### 1.3 Snapshot, not subscription — the frozen Tasks panel (593 §5)

The expanded Tasks panel showed the *identical* "55 RUNNING · 363 QUEUED" and the same eight job
hashes across two samples while the authoritative status-bar counter kept moving; closing and
reopening corrected it instantly. **The mechanism is NOT "un-subscribed snapshot"** (the takeover §8.4
disproved that — `TaskList` does subscribe and re-renders on every notify): the panel and the status-bar
counter read **two different job-count authorities** — the panel ← the `/api/indexing-jobs/stream` *SSE*
substrate, the counter ← the `/api/status` *poll* — and the symptom matches the **SSE feed stalling
while the poll keeps moving**. The panel is reading a *provisional* (stale-feed) state and rendering it
as settled counts. (Stall hypothesis to confirm with a live repro per `audit-driven-fixes-need-test`;
§8.4.)

---

## 2. Root, stated once

> 557's Observed-state model is binary: a value is `Known<T>` or `Unknown`. The system, though,
> constantly passes through a third epistemic category — **provisional**: a value that is *real right
> now but in flux*, a waypoint of a bounded operation (a rebuild, a generation cutover, a restart, an
> initial load, a stalled live feed) whose completion will change it. **The FE has no single notion of
> "provisional," so every surface independently mistakes a provisional state for a settled one** — and
> does so however its local code happens to: as a **contradiction** (1.1), a **catastrophe** (1.2), or
> a **freeze** (1.3).

This is one missing dimension with three faces, not three bugs. 557 collapsed the *inputs* (one
projected tri-state `StatusResponse`) and got the FIRST provisional case right — `ConnectionPhase.stale`
("the channel aged out → hold last-known, show *Reconnecting…*, don't wipe to 0"). But it never lifted
that one case into a general concept, and it left two *derived* layers un-authored: the **verdict**
(a rollup over the status that each surface re-derives — 1.1) and the **transition/freshness phase**
(the provisional cases other than a stale channel — 1.2, 1.3). The correct long-term structure makes
"provisional" a first-class derived observed-state, of which `ConnectionPhase.stale` is then just one
cause.

---

## 3. Why the existing structure doesn't prevent it

Two structural absences, both downstream of the missing "provisional" concept:

- **No derived verdict.** The health verdict (`allGreen` / "Service degraded" / "Attention needed" /
  "✓ operational") is computed **inline in each render method**, not as a store-derived value. With no
  `aiStateStore`-level verdict to consume, three sites (`HealthSurface` header, `HealthSurface` footer,
  `readinessNotice`) each roll their own — and disagree on the `readiness === 'unknown'` boundary three
  different ways (alarm / green / silent; takeover §8.1). Collapse (557 ladder rung-1) never happened
  for this *derived* concept; 557 collapsed only its *input*.
- **No transition/freshness phase.** `ConnectionPhase.stale` captures one provisional cause — "we lost
  the live channel." But a worker **rebuild** (the worker is UP, deliberately building a new index
  generation) and a stalled **SSE feed** (the channel is fine, this *one stream* went quiet) are
  *distinct, expected, bounded* provisional conditions that produce the same rendering need yet trigger
  no `stale` and so get none of its protection. 575 built per-*concept* liveness (`isInFlightLive`) but
  **explicitly deferred system-level transitions** (rebuild / generation-switch / boot) to "their own
  spines" (575 §13 L1 / §17.6) and **declined a single aggregate verdict**. That deferred spine is what
  this doc designs.

No gate catches the verdict split because, as 559 established, FE verdict logic is inline Lit/TS that
grep-gates can't see into — and the existing observed-state gate (`check-observed-state-collapse.mjs`)
guards the status *type*, not any derived verdict over it (takeover §8.5).

---

## 4. The correct long-term structure

The fix is **not** three local patches. It is one new derived observed-state on `aiStateStore` —
**the Stability axis** — with the health verdict as a rollup that reads it, and a small renderer
discipline that makes "provisional rendered as settled" unrepresentable. Everything below is
conceptual; types are illustrative, not an implementation.

### 4.1 The foundation — a single derived `Stability` axis (rung-1 Collapse, generalizing 557)

Add one derived value to the observed-state authority, beside `ConnectionPhase` and the `Maybe<T>`
fields:

```ts
// aiStateStore — the ONE answer to "is what we're showing settled, or in flux?"
type Stability =
  | { kind: 'settled' }
  | { kind: 'provisional'; cause: ProvisionalCause };

type ProvisionalCause =
  | 'initial-load'        // never succeeded yet (= ConnectionPhase.connecting)
  | 'channel-stale'       // last success aged out (= ConnectionPhase.stale, EXISTING behavior)
  | 'rebuilding'          // worker building a new index generation (worker UP)
  | 'generation-switch'   // cutover in progress (serving gen ≠ ingest gen)
  | 'worker-restart'      // worker process down/replacing (worker component not READY)
  | 'feed-stalled';       // a specific live stream went quiet while the poll is alive
```

This is a **Collapse, not a new authority type**: it *subsumes and generalizes* `ConnectionPhase`'s
existing provisional cases (`connecting` → `initial-load`, `stale` → `channel-stale`) and **adds the
causes 557 never modelled**, each derived from data already on the wire (takeover §8.3): the
`worker.migration` block (`migrationState ∈ {MIGRATING, SWITCHING}`, `serving*GenerationId`,
`buildingIndexedDocuments`) for `rebuilding`/`generation-switch`; the worker-component state /
`ConnectionPhase` for `worker-restart`; and 575's stream-liveness pattern (heartbeat freshness of the
*feed itself*, not just per-job) for `feed-stalled`. The cause is a **closed union** so every consumer
handles it totally. `Stability` is the fundamental value; the verdict and the renderers below are its
consumers.

> **Why one axis and not a per-value "provisional" flag.** Threading a `provisional` bit through every
> field is the heavy alternative. A single system-level phase that renderers *consult* is exactly the
> shape `ConnectionPhase` already proved works — and it is the smallest change that makes provisional a
> concept rather than an accident.

### 4.2 The verdict — a rollup that READS the Stability axis (rung-1 Collapse + single-seam gate; closes 1.1)

One derived `SystemHealthVerdict`, computed once, consumed by the header badge, the footer card, and
`readinessNotice` — **none recomputes**:

```ts
type SystemHealthVerdict =
  | { kind: 'transitioning'; cause: ProvisionalCause }   // Stability provisional for a transition cause
  | { kind: 'connecting' }                               // Stability provisional, initial-load
  | { kind: 'checking' }                                 // settled channel, readiness 'unknown' — the 1.1 boundary
  | { kind: 'operational' }
  | { kind: 'degraded'; reasons: ReadonlyArray<HealthReason> }
  | { kind: 'attention'; reasons: ReadonlyArray<HealthReason> }
  | { kind: 'unreachable' };
```

The verdict is a **total function over `(Stability, readiness, reindexRequired)`** — so the
`readiness === 'unknown'` boundary is resolved in exactly ONE place, as a distinct non-green,
non-alarming `checking` (answering §6.3: neither the footer's false "operational" nor the header's
over-claimed "Service degraded"). Opposite-polarity divergence becomes **structurally impossible**, not
merely currently-aligned.

Because Lit templates are not type-checked (557 §5 de-risk), "consume, don't recompute" cannot be a
pure type guarantee. The realistic strongest tier is **Collapse + a single-derivation gate** modelled
on the codebase's existing single-seam gates (`check-search-issuance` "one `buildSearchIntent`",
`check-steering-arbitration` "one dispatch site"): the readiness→verdict predicate may exist in exactly
ONE function; a second site reading `readiness.retrieval ===` to form a verdict is a build failure.
This is the verdict-shaped analog of the observed-state gate that today only guards the status *type*.

**Input scope (takeover §8.2).** The verdict's inputs are store-global (`Stability`, `readiness`,
`reindexRequired`). `recommendedActions` is *not* — it is `HealthSurface`-local
(`/api/condition-recovery-index`) and is *not the 1.1 divergence axis* (empty in the boundary case).
Two options, recommend (b): **(a)** relocate the recovery-index fetch into the store so the verdict is
one truly app-global authority; **(b)** keep the store verdict on the global inputs and let
`HealthSurface` layer its local `hasOpenConditions` on top of the consumed verdict (smaller; still
closes 1.1 — the local overlay is single-component and monotonic toward "attention," so it cannot
re-split).

### 4.3 The renderers — total over `(value, Stability)` so the catastrophe is unrepresentable (rung-2; closes 1.2)

The structural cure for 1.2 is the generalization of what `ConnectionPhase.stale` already does for a
lost channel: **a transition-/freshness-sensitive value is never rendered as a settled fact while
Stability is provisional.** The renderer becomes a total function over `(value, Stability)` — it cannot
emit the bare value without addressing the provisional branch:

- doc count / index size: **"Rebuilding… (last known: N docs)"**, not `0`;
- watched-folder list: **"Rebuilding index…"**, not "No watched folders" — so an *empty configured
  list* (settled, 0 folders) and a *transiently-unavailable list* (provisional) become **different
  rendered states**, and the data-loss reading becomes unrepresentable;
- the verdict badge renders `transitioning(cause)` with the cause's wording + last-known context.

The seam that makes this rung-2 (not just discipline) is a small typed helper — conceptually
`renderObserved(value, stability, { settled, provisional })` — whose signature *requires* the
provisional branch, so a render site physically cannot show the value without handling flux. A
whole-tree gate backstops the template boundary (the same tier-2-at-seam + tier-4-lint split 557 §5
settled for, because Lit slots aren't type-checked).

> **The 574 wrinkle (1.2's deeper cause).** `LibrarySurface.roots` is **per-surface local fetch state**,
> not global observed-state — which is why 557's existing stale-holding never protected it (takeover
> §8 + tempdoc 574). The long-term-correct move is to treat "how many folders / are there any" as a
> **READ projection of the observed-state authority** (consulting `Stability` before it can wipe),
> distinct from the folder **write** path (add/remove), which stays operational. At minimum the local
> renderer consults the global `Stability`; better, the read count is lifted into the projection.

### 4.4 The job feed — fold the freeze into the same axis (rung-1 Collapse; closes 1.3)

1.3 is the same missing concept at the stream level. Two structural options, in ladder order:

1. **Collapse to one job-count authority** (preferred). The status-bar counter (`/api/status` poll) and
   the Tasks panel (`/api/indexing-jobs/stream` SSE) are two sources for one concept; make them one so
   they cannot disagree. The SSE substrate stays the authority for per-job *detail*; the aggregate
   counts derive from a single source.
2. **Make the stall observable** (if two transports are kept by design). 575 already tracks per-job
   heartbeat liveness; lift that to **stream-level liveness** — a quiet feed becomes
   `Stability = provisional('feed-stalled')`, and the panel renders "live feed stalled — reconnecting"
   instead of silently freezing. This is just the §4.1 axis applied to a stream, and dovetails with
   §4.3's renderer discipline.

Either way the close is **not** "subscribe the panel" — `TaskList` already subscribes (takeover §8.4);
the freeze is a *provisional feed shown as settled counts*, the exact root of this doc.

### 4.5 How the layers compose (and how they respect the sibling cuts)

```
wire: StatusResponse  (readiness/composites · worker.migration · component states · stream frames)
  │
  ▼  projections on aiStateStore (the ONE observed-state authority)
   Maybe<T> fields, ConnectionPhase                  (557 — EXISTING)
   Stability = settled | provisional(cause)          (595 §4.1 — NEW; subsumes ConnectionPhase cases)
   SystemHealthVerdict   (rollup, reads Stability)   (595 §4.2 — NEW)
  │
  ▼  consumers — all projections, none re-derive
   Health header / footer / readinessNotice  → consume SystemHealthVerdict   (closes 1.1)
   doc-count / folder-list / size renderers   → total over (value, Stability) (closes 1.2)
   Tasks panel                                → one job-count authority OR feed-stalled (closes 1.3)
   596 jf-control.transient                   → projects Stability (DOWNSTREAM consumer)
   594 leaf-fact chips                         → UNAFFECTED (leaves, not rollups)
```

- **594 cut (leaf vs rollup):** 594 projects a leaf fact's *value* (one source, never a rollup); 595
  derives a *rollup/phase*. They share `aiStateStore` as substrate but never the same computation — the
  cut 594 §9.5 drew to stop a fourth verdict interpreter from re-growing.
- **596 cut (system vs affordance):** 596's per-control `Availability.transient` is a **downstream
  projection** of `Stability`, not a re-derivation. 595 owns the system transition; 596 consumes it.
  Designing `Stability` as the single upstream source is exactly what 596 §8.7 asked for ("coordinate,
  don't duplicate a phase machine").
- **575 reuse:** the per-job liveness window and the register/coverage machinery are reused for the
  `feed-stalled` cause and (optionally) for declaring the new system-transition concept in
  `observed-happening.v1.json` — extending 575's deferred spine rather than inventing a parallel one.

### 4.6 Why this passes "compose, do not fuse" (the 571 §9.D / AHA guardrail 575 raised)

A single derived verdict is **not** the omniscient live pane 575 declined (Face B). It is the
single derivation of ONE concept — "is the system settled / healthy / in flux" — that is *already*
computed in three diverging places. Per AHA, unify only what shares a reason to change: the three
verdict sites share exactly one reason to change (the definition of "healthy"), and the provisional
causes share exactly one (the don't-mislead-during-flux rule). Collapsing them is single-authority, not
fusion. The axis owns no subsystem; it only answers "settled or in flux, and why."

---

## 5. Scope boundary (what this is NOT — guarding against over-unification)

**IN (same root — the missing "provisional" dimension):** the split health verdict (1.1), the
provisional-rendered-as-settled 0-docs / "No watched folders" (1.2), the frozen (stalled-feed) Tasks
panel (1.3), and any other surface that recomputes a readiness verdict inline or renders a provisional
value/feed as a settled fact.

**OUT (different roots — do NOT bundle):**
- **The 384-d chip / capability strip** — that's the *Display* projection (factual content of a label).
  Owned by **tempdoc 594**. Sibling, not same.
- **Facet count > result count** and the **136 result cap** (593 §8/§10) — search-response
  semantics/pagination. Their own traces.
- **The Force-Rebuild GPU catch-22 / mode-exclusivity** (593 §F/§H) — a real backend/runtime
  operational design issue (Online vs Indexing mode, VRAM contention, BLOCKED_LEGACY not surviving
  restart). This doc covers only how the FE should *render* a transition truthfully; whether the
  rebuild itself should require a mode switch, and why the rebuilt generation didn't survive a
  force-kill restart, are backend questions for a separate tempdoc.

---

## 6. Open questions / decisions (before impl)

1. **Sequencing.** The foundation is §4.1 (the `Stability` axis) — every other piece reads it.
   Recommended order, each independently shippable: **(i)** the `Stability` axis + the §4.2 verdict
   rollup + the single-derivation gate — closes the provable 1.1 with no live dependency, and lays the
   foundation; **(ii)** the §4.3 total-over-`(value, Stability)` renderers — closes the highest-severity
   1.2, but its *input* (which provisional cause fires during the 0-docs window) needs the §8.3.1 live
   repro first; **(iii)** the §4.4 job-feed collapse / `feed-stalled` cause — closes 1.3 once a repro
   confirms which transport stalls. A spot-fix that merely aligns the footer predicate to the header is
   rung-0 symptom-patching — it re-converges today and silently re-diverges on the next edit; not the
   close.
2. **Wire-signal discovery (gating Move 2). — RESOLVED 2026-06-16 (takeover §8.3).** The signal is
   ALREADY on `/api/status`, not just `debug_state`. `WorkerOperationalView.migration`
   (`MigrationGenerationView`) carries `migrationState` (the worker enum
   `IDLE | MIGRATING | SWITCHING | FAILED`), `activeGenerationId` / `buildingGenerationId`,
   `servingSearchGenerationId` / `servingIngestGenerationId`, and `activeIndexedDocuments` /
   `buildingIndexedDocuments` (the `building_doc_count`). It serializes (not `@JsonIgnore`'d) and is
   already in the generated FE types (`status-response.ts`) — with **zero hand-written consumers** in
   `shell-v0`. So Move 2 is a **pure FE-projection task; no api-record wire addition needed.** **Residual
   discovery (the deeper question, still open — §8.3.1):** confirm the migration block is *populated
   during the precise 0-docs window*. If the catastrophe window is "worker UP, building a new
   generation" the block carries `MIGRATING`/`SWITCHING`; if it is "worker DOWN, restarting" (force-rebuild
   appears to force-kill the worker per 593 §F/§H) the worker is unreachable, `/api/status` returns the
   `WorkerOperationalView.fallback(...)` (0 docs, `MigrationGenerationView.empty()`), and the transition
   signal must come from connPhase / worker-component-state instead. This needs a live force-rebuild
   repro to settle and decides whether the `transitioning` verdict reads `migration` OR `(connPhase +
   worker component) + a recent-rebuild-requested latch`.
3. **'unknown' polarity — RESOLVED in design (§4.2).** When `readiness === 'unknown'` with a *settled*
   channel (no transition), the verdict is a distinct non-green, non-alarming **`checking`** — neither
   "operational" (the footer's bug) nor "Service degraded" (the header's over-claim). Decided in one
   place by the total verdict function. Confirm wording at impl.
4. **Home check (done).** 559 was considered and rejected (element-catalog seam, not value-verdict);
   this extends 557 §2.B directly (the Observed-state projection), adding the *provisional* epistemic
   dimension it lacked. Sibling of 594 (leaf facts) and 596 (operability). If you'd rather fold it into
   559, say so.
5. **Register the new concept in 575's spine?** 575's `observed-happening.v1.json` is the natural home
   to *declare* the system-transition concept (with a `liveness.model`), reusing 575's coverage gates
   rather than a parallel register. Open: declare it there now, or keep `Stability` a pure derived
   store value first and register once it stabilizes. Recommend the latter (derive first, declare once
   the cause set is settled).
6. **Doc maintenance.** On impl, 557 §2.B gains a cross-ref noting Observed-state now carries a
   `Stability` axis (settled | provisional·cause) + a derived verdict; 575 §13 L1's "deferred to its own
   spine" entry points here. ADR-0032 / explanation/27 unaffected (the authority *model* widens by one
   derived value; its *reach* extends to the verdict + transition phase).

---

## 7. As-built so far

- **Nothing implemented.** This is a design theory awaiting the §6 decisions. No code touched by this
  doc (the only edit this session was 594's unrelated `384→768` literal).
- **Design round complete (2026-06-16).** §4 was rewritten from three local "Moves" into one structure
  — the `Stability` axis (§4.1) with the verdict rollup (§4.2) and total renderers (§4.3/§4.4) as its
  consumers — after reading the framework (557) and the adjacent design docs (575 observed-happening /
  liveness, 594 leaf-fact sibling, 596 operability sibling, 574 per-instance scope). The design is
  extend-not-reinvent: it generalizes 557's `ConnectionPhase.stale`, reuses 575's liveness machinery,
  and fills the system-transition spine 575 explicitly deferred.
- **#1.1 is independently verifiable now**: read `HealthSurface.ts:704–727` (header) vs `:1120–1141`
  (footer) vs `readinessNotice.ts:108` (search window), against `aiStateStore.ts:109` (the `'unknown'`
  enum value). The contradiction is in the predicates, not in any transient timing.

---

## 8. Takeover investigation (2026-06-16) — source-verbatim verification, two corrections, one resolved discovery

A full read-against-`main` pass over every claim. Net: **#1.1 is confirmed and is stronger than stated
(a three-way divergence)**; **the Move 2 gating discovery (§6.2) is resolved — the signal is on the
status wire, with one residual live-repro caveat**; **#1.3's mechanism and Move 3's premise were both
wrong and are corrected**; **Move 1's input set is narrowed**. No code changed — this remains a design
proposal, now with verified inputs.

> **Label note.** §8 was written against the doc's earlier "Move 1 / 2 / 3" structure; the §4 design
> round (2026-06-16, below-dated edits) superseded those labels. Map: **Move 1 → §4.2** (the verdict
> rollup, now reading the §4.1 `Stability` axis), **Move 2 → §4.3** (total-over-`(value, Stability)`
> renderers), **Move 3 → §4.4** (job-feed collapse / `feed-stalled`). The findings below are unchanged.

### 8.1 #1.1 confirmed, and it is a THREE-way divergence (not two)

Verified verbatim:
- `aiStateStore.ts:109` — `retrieval: 'ready' | 'degraded' | 'unknown'` (closed tri-state). ✔
- Header (`HealthSurface.ts:704–706, 712–727`) — `retrievalReady = readiness.retrieval === 'ready'`;
  `'unknown'` ⇒ not ready ⇒ `allGreen=false` ⇒ `label = 'Service degraded'`. ✔
- Footer (`HealthSurface.ts:1120–1141`) — `degraded = retrieval === 'degraded' || reindexRequired`;
  `'unknown'` ⇒ not degraded ⇒ fall-through `✓ All systems operational`. ✔ (gated by
  `recommendedActions.size === 0` + `dataKnown`, both true in the boundary scenario).

**New finding — the third interpreter disagrees a THIRD way.** `readinessNotice.ts:108` is
`if (r.retrieval !== 'degraded') return null;` — so for `'unknown'` the search window's degradation
notice is **suppressed entirely (silence)**. The boundary value `'unknown'` therefore renders three
different polarities on the SAME app, simultaneously: header **alarms** ("Service degraded"), footer
**greens** ("✓ All systems operational"), search window **goes silent** (no notice). This strengthens
the doc's case: it is not "header vs footer disagree," it is "three independent interpreters, three
verdicts." Move 1's single derived verdict must feed all three consumers, `readinessNotice` included.

### 8.2 Move 1 feasibility — `recommendedActions` is not store-global (input set narrowed)

`recommendedActions` is `HealthSurface`-local (`@state`, `HealthSurface.ts:129/163`), populated by a
REST fetch of `/api/condition-recovery-index` (`:202–226`). It is unavailable to the status bar and
`readinessNotice`. It is also **not the 1.1 divergence axis** (empty in the boundary scenario). The
store verdict should be derived from `(connPhase, readiness, reindexRequired)`; `recommendedActions`
stays a local, monotonic-toward-attention overlay (or is relocated into the store — see Move 1, options
a/b). This removes a hidden coupling the original draft would have hit at impl time.

### 8.3 §6.2 RESOLVED — the transition signal is on `/api/status`, not just `debug_state`

Verified the wire path end-to-end:
- `WorkerOperationalView` (`modules/app-api/.../status/WorkerOperationalView.java:18–35`) carries
  `MigrationGenerationView migration` (line 21). Only `aiReady`/`embeddingReady` are `@JsonIgnore`d;
  `migration` serializes to `/api/status`.
- `MigrationGenerationView` (`.../status/MigrationGenerationView.java:6–25`) carries `migrationState`,
  `activeGenerationId`, `buildingGenerationId`, `servingSearchGenerationId`,
  `servingIngestGenerationId`, `activeIndexedDocuments`, `buildingIndexedDocuments`, …
- The worker enum is `IndexGenerationManager.MigrationState = { IDLE, MIGRATING, SWITCHING, FAILED }`
  (`modules/worker-core/.../index/IndexGenerationManager.java:88–93`); `SWITCHING` is the cutover
  fence (mutations durably buffered).
- FE: `migrationState` / `servingSearchGenerationId` / `buildingIndexedDocuments` appear ONLY in
  generated types (`api/generated/schema-types/status-response.ts`), fixtures, and `*_pb.d.ts` — **no
  hand-written consumer** in `shell-v0`. The FE receives the signal and discards it.

**Conclusion: Move 2 needs no wire addition — it is a pure FE projection.** Concrete predicate
(grounded in the enum): the worker is transitioning when
`migration.migrationState ∈ {'MIGRATING','SWITCHING'}`, corroborated by
`buildingGenerationId !== '' && buildingGenerationId !== activeGenerationId` or
`servingSearchGenerationId !== servingIngestGenerationId`.

#### 8.3.1 Residual discovery — RESOLVED FROM CODE 2026-06-16 (see §9.1). Both windows exist; the design needs both causes (it has them).

This was the open question — is `migration` populated during the 0-docs window? The §9 confidence pass
resolved it **from control-flow + live wire shape, without a destructive repro**: a Force-Rebuild
**force-kills and restarts the worker** (`MigrationOps.setRestartWorker(true)` →
`MigrationControlOps` → `KnowledgeServer.initiateShutdown`), so there are TWO sequential windows:
- **Worker DOWN** (kill→respawn gap): `/api/status` returns `WorkerOperationalView.fallback("UNAVAILABLE")`
  ⇒ `worker.core.indexState === "UNAVAILABLE"`, `indexedDocuments = 0`, `migration = empty()`,
  `workerRpcStale = true` (`StatusLifecycleHandler:278–283`). The transition signal here is
  `indexState === 'UNAVAILABLE'` / `workerRpcStale`, NOT `migration`. **Note the Head's `/api/status`
  stays UP (200 fallback), so `ConnectionPhase` does NOT go `stale`** — the worker-down signal lives
  *inside a successful poll*.
- **Worker UP, building** (after respawn, reads persisted `state.json`): `migration.migrationState =
  "MIGRATING"` (set up-front at `IndexGenerationManager.startMigration:211`), `core.indexedDocuments` =
  building gen climbing from 0, `migration.activeIndexedDocuments` = old serving count.

So the `transitioning` Stability cause is an **OR across two inputs** — exactly the `worker-restart`
*and* `rebuilding`/`generation-switch` causes §4.1 already lists. No design change; the inputs are now
verified (§9.1). The live force-rebuild repro is downgraded from "gating first task" to
"optional confirmation of timing" — the structure no longer depends on it.

### 8.4 #1.3 + Move 3 — mechanism CORRECTED (the doc's hypothesis was wrong)

The doc (and a first exploration subagent) hypothesized "the panel snapshots on open and does not bind
to the live authority." **Source says otherwise:**
- `TaskList.ts:56–62` — `connectedCallback` calls `subscribeTasks(() => { this.tasks = listTasks(); })`;
  `disconnectedCallback` unsubscribes. It is a live subscription, not a one-shot snapshot.
- `substrates/tasks/index.ts:208–210` — `listTasks()` returns `Array.from(_tasks.values())`, a **fresh
  array reference every call**, so Lit's `@state` change-detection re-renders on every notify (no
  same-reference render-skip).

So the panel cannot freeze *while open* from "not subscribed." The real structure is **two divergent
job-count authorities**:
- **Status bar** ← `aiStateStore.index.pendingJobs` ← `/api/status` **poll** (`StatusDeck.ts`; ~5 s).
- **Tasks panel** ← Task substrate ← `/api/indexing-jobs/stream` **SSE** bridge
  (`substrates/tasks/indexingJobsBridge.ts:181–220`, pooled EventSource + a 15 s re-eval tick).

The 593 §5 symptom (status bar moving, panel frozen, fixed by reopen) is exactly what an **SSE stall
with the poll still alive** produces: no new SSE frames ⇒ the 15 s tick re-projects the same
`latestItems` ⇒ each `upsertMirroredTask` hits the no-op suppression (`index.ts:184–191`, identical
id+label+status+progress) ⇒ no `notify()` ⇒ panel frozen, while the independent poll keeps the status
bar moving. ("Fixed by reopen" is only weakly explained by this — a reopened `TaskList` reads the same
(stale) substrate via `listTasks()`; the reopen likely coincided with a fresh frame or a pool
reconnect. The reopen detail is the part that most needs the live repro.)

**Move 3 restated:** the close is NOT "subscribe the panel" (already done). It is to remove the
**two-authority** divergence for job counts — candidates, smallest first:
1. **One authority** — have the status-bar counter and the Tasks panel read the SAME source (e.g. both
   off the SSE substrate, or both off the poll), so they cannot disagree. This is the true 557 Collapse.
2. **Observable stall** — if two transports are kept by design, make an SSE stall a first-class
   observed-state (the bridge already tracks freshness via `isInFlightLive`/`lastUpdatedMs`): the panel
   renders "live feed stalled — reconnecting" instead of silently showing stale counts. This is the
   Observed-state-truthfulness framing and dovetails with Move 2's "transition/uncertainty is a state."

Confirm via a live repro which transport stalls (per `audit-driven-fixes-need-test`) before committing
to (1) vs (2).

### 8.5 "No gate catches this" — confirmed

`scripts/ci/check-observed-state-collapse.mjs` guards the status *type* (forbids a re-forked local
`interface StatusResponse`/`StatusSnapshot`; requires `StatusSnapshot = StatusResponse`). It does
**not** assert the health *verdict* is single-sourced. So the 1.1 class is genuinely un-gated today —
Move 1's value is exactly to create the one derived verdict that a future gate (or the existing one,
extended) could assert against.

### 8.6 Recommended sequencing (answers §6.1)

1. **Move 1 first** (Collapse the verdict; store-derived `SystemHealthVerdict` over
   `(connPhase, readiness, reindexRequired)` consumed by header + footer + `readinessNotice`). Closes
   the provable three-way 1.1 with no live dependency. Ship with a unit test asserting all three
   consumers read one verdict and that `'unknown'` resolves to ONE non-green, non-alarming kind
   (`'checking'`/`'unknown'` — answers §6.3: neither "operational" nor "Service degraded").
2. **Live repro (§8.3.1)** — settle whether the 0-docs window is worker-UP-migrating or
   worker-DOWN-restarting. Gates Move 2's input choice.
3. **Move 2** (the `transitioning` kind) using the input the repro dictates.
4. **Move 3** — repro which transport stalls, then Collapse to one job-count authority (preferred) or
   make the stall observable.

> **Superseded by §9/§10.** §9 resolved §8.6's step 2 from code (no live repro gates Move 2). §10's
> browser inspection added the user-facing design dimension (severity + tone + cause-accuracy). Read
> §10.5 for the updated user-facing design.

---

## 9. Confidence pass (2026-06-16) — pre-implementation de-risk, no feature code written

Each load-bearing assumption checked against source + (read-only) live wire. Net: the two highest-impact
uncertainties resolved **from code**, store/gate feasibility **confirmed idiomatic**, one lower-severity
item (1.3's exact stall) remains a live-repro confirmation.

### 9.1 The 0-docs window — RESOLVED from control-flow + live shape

Force-Rebuild **force-kills + restarts the worker** (`RebuildIndexHandler` →
`MigrationOps.setRestartWorker(true)` → `MigrationControlOps` → `KnowledgeServer.initiateShutdown`).
Verified the down-window discriminator myself: `StatusLifecycleHandler:278–283` →
`WorkerOperationalView.fallback("UNAVAILABLE")` + `workerRpcStale=true`; `CoreIndexView.fallback`
hardcodes `indexedDocuments=0, indexHealthy=false`. Live `/api/status` confirmed the settled shape
(`migrationState="IDLE"`, `serving search===ingest gen`, `indexState="IDLE"`, 605 docs). The §4.1
predicate is grounded:

| Stability | Live discriminator |
|---|---|
| `settled` | `migrationState==='IDLE'` ∧ `serving search===ingest gen` ∧ `buildingGenerationId===''` ∧ `indexState!=='UNAVAILABLE'` |
| `provisional('worker-restart')` | `worker.core.indexState==='UNAVAILABLE'` (∧ `workerRpcStale`) — the kill→respawn gap; **`ConnectionPhase` stays `connected`** (Head answers 200 fallback) |
| `provisional('rebuilding'/'gen-switch')` | `migrationState∈{MIGRATING,SWITCHING}` ∨ `buildingGenerationId!==''` ∨ `serving search≠ingest gen` |

Sharpenings: (a) `worker-restart` reads `indexState`/`workerRpcStale` *inside a successful poll*, NOT
`ConnectionPhase`; (b) "0 docs" is ambiguous on the count alone — the renderer must read the
`indexState`/`migration` provenance; (c) the verdict must order `transitioning` ABOVE
`operational`/`degraded` (mid-build `indexHealthy` may read true while serving the old gen).

### 9.2 Defect 1.2's mechanism — REFINED (strengthens §4.3)

Not a 200-empty wipe. `IndexingController.handleListRootsSubstrate:520–531` returns **503** when
`!workerAvailable()`; `LibrarySurface.refresh` treats `!res.ok` as a throw → keeps already-loaded
roots; roots are Head-local (`watched_roots.json`, survive rebuild). The real catastrophe is a
**missing Unknown state**: `LibrarySurface` seeds `roots=[]` and has no "never-loaded" state, so
navigating to Library *while the worker is down* 503s, leaves `roots=[]`, `loading=false` ⇒ renders the
error banner AND "No watched folders" (`LibrarySurface.ts:643–649`). Exactly the 574-wrinkle §4.3
flagged — fix is "give the surface an Unknown state consulting global `Stability`."

### 9.3 Store + gate feasibility — CONFIRMED idiomatic

`aiStateStore` already has `computeStaleness()` (single staleness source), `computeStatusLabel(phase,
runtime, activity)→string` (the exact pure state→summary precedent for `computeVerdict`), and
`Maybe<T>`/`known`/`UNKNOWN`/`mapKnown` (total-render primitives). The verdict gate is buildable on the
`check-search-issuance` pattern: scannable token `readiness.retrieval` appears in exactly **3 production
sites** (the three collapsed); post-collapse, allowlist one seam. Same honest limit (a verdict from a
different field is import-invisible).

### 9.4 `readinessNotice` as pure consumer — feasible, shapes the union

`readinessNotice` words `causes` from `reasonCodes` + picks a `remedy`, so the verdict union's
`degraded`/`attention`/`transitioning` kinds must carry `reasons` (projectable from `reasonCodes`) —
§4.2's sketch already includes this.

### 9.5 Residual + updated sequencing

RESOLVED (code/live): the 0-docs inputs (§9.1), 1.2's mechanism (§9.2), store/gate feasibility (§9.3),
`readinessNotice` consumability (§9.4), the wire signal (§8.3). STILL live-repro (lowest severity):
1.3's exact stall + the reopen anomaly — §4.4 is robust either way. Untested residual: §4.3 total-render
ergonomics at scale; `workerRpcStale` FE-visibility (`indexState` suffices).

Updated order: (i) `Stability` + verdict + gate (closes 1.1, inputs verified); (ii) total renderers +
`LibrarySurface` Unknown state (closes 1.2, no repro); (iii) job-feed collapse / `feed-stalled` (closes
1.3 after a repro picks §4.4(1) vs (2)).

### 9.6 Confidence rating (remaining work) — **8/10**

High on the structural core; held below 9–10 by unproven §4.3 ergonomics, 1.3's still-hypothetical
stall, and a not-yet-*seen* force-rebuild timeline (resolved from code). See §10.6 for the rating after
the UX inspection.

---

## 10. User-facing inspection (2026-06-16, browser, read-only) — what the user actually sees, and the refined UX design

Drove the live frontend in Chrome (another agent's running stack, read-only — no rebuild/mutation
triggered). The whole tempdoc is user-facing (verdict badge, status-bar label, search banner,
Library/Browse empty-states, Tasks tray), so none of it was skippable. The inspection both **confirmed
the root live** and **surfaced a new user-facing design dimension the tempdoc-alone view missed.**

### 10.1 The verdict split is LIVE and cross-surface (not just code-provable)

On a settled, idle system right now, two of the ≥3 verdict sites disagree **simultaneously and both
visible**:
- the persistent **status bar** reads green **"● Online — Qwen Qwen3.5-9B"** (`computeStatusLabel` keys
  on `runtime.mode==='online'`, ignoring retrieval);
- the **Health header badge** reads orange **"Service degraded"** (the inline `allGreen` keys on
  `readiness.retrieval`).

So the user sees "Online" (status bar) and "Service degraded" (Health) at the same time. This is the
§1.1 root, live — between the status bar and Health, a pairing the original doc under-counted (it framed
1.1 as header-vs-footer-vs-readinessNotice). **`computeStatusLabel` is a FOURTH verdict interpreter**
and MUST also consume the §4.2 verdict (or at least the §4.1 `Stability` axis) — §9.3 already noted the
risk; the live screen proves it.

### 10.2 The exact `'unknown'` polarity flip was NOT live-reproduced — the live state is `'degraded'`

Honest correction: the wire shows `composites.retrieval = DEGRADED` (reasonCode
`lambdamart.not_configured`), NOT `'unknown'`. With `'degraded'`, the HealthSurface header
("Service degraded") and footer ("Attention needed") and the search banner ("Semantic search degraded")
**agree in sentiment** — so the dramatic header-greens-vs-footer-alarms flip (which needs `'unknown'`)
was not on screen. It remains code-provable (§8.1) but is a narrower live window. What IS live is (a)
the cross-surface status-bar-vs-Health split (§10.1), and (b) **three different phrasings of one
condition** — "Service degraded" / "Attention needed" / "Semantic search degraded" — three surfaces,
no shared wording. That three-phrasing drift is the everyday face of the missing single verdict.

### 10.3 NEW finding — the verdict is BINARY but reality is GRADED (a consistent over-claim + an inaccuracy)

This is the design-relevant discovery the tempdoc alone could not have produced. Every component is
`READY` **except** `lambdamartModel: DEGRADED (lambdamart.not_configured)` — an *optional learned
re-ranker*. Yet that single cosmetic gap collapses the whole `retrieval` composite to `DEGRADED`, which
renders as:
- Health: alarming orange **"Service degraded"** (sounds like the search engine is broken);
- Search: **"Semantic search degraded. Showing keyword results; relevance ranking may be reduced."**

Two problems a *merely-unified* verdict would make **consistently** wrong:
1. **Over-claim / wrong severity.** "Service degraded" for an optional re-ranker being unconfigured is
   disproportionate alarm. A single derived verdict that faithfully says "Service degraded" everywhere
   would be consistently over-alarming — unification alone does not fix this; it needs a **severity
   dimension**.
2. **Cause inaccuracy.** `embedding` + `chunkEmbedding` are READY, so semantic search is *fine* and the
   user is NOT on "keyword results." "Semantic search degraded / keyword results" mis-describes a
   re-ranking gap as a retrieval failure. (Wording accuracy is 594's Display-content domain, but the
   verdict it hangs off is 595's — so the two must coordinate: the verdict carries the accurate
   per-reason severity + cause; 594 owns the leaf phrasing.)

### 10.4 The transition tone problem (why §4.3 must not reuse the "degraded" treatment)

Today a worker rebuild surfaces (per §9.1) as `indexState="UNAVAILABLE"` → 0 docs and, on the
HealthSurface, the same orange alarm vocabulary. The **Library surface carries the "Reindex" button**
(observed live) — so the user triggers a rebuild *on the very surface* that will then look broken. A
transition must therefore render in a **distinct, non-alarming "in-progress" tone** (progress/neutral,
e.g. blue), never the orange "degraded" alarm, and **in-place where the action was taken** (the Library
surface, the status bar, the Health header) — holding last-known values ("Rebuilding… last known: 605
docs / 2 folders"), not wiping to 0 / "No watched folders".

### 10.5 The correct user-facing design — a severity-keyed verdict with a tone ladder

The §4.2 verdict union gains a **severity** so presentation tone is a projection of severity, not of
each surface's local guess. Conceptually (general, not implementation):

```
SystemHealthVerdict = { kind, severity, reasons }
  kind ∈ { operational, checking, transitioning(cause), degraded, attention, unreachable }
  severity ∈ { ok, info, busy, warn, error }   // drives tone; derived ONCE from kind + reasons
```

| Verdict | Severity / tone | Wording (single source) | Values |
|---|---|---|---|
| operational | ok · green | "All systems operational" | live |
| checking (`retrieval==='unknown'`, settled) | info · neutral/grey | "Checking…" | last-known |
| transitioning(rebuild/restart/gen-switch) | **busy · blue (NOT orange)** | "Rebuilding…" / "Restarting…" | **hold last-known + progress** |
| degraded — cosmetic (e.g. `lambdamart.not_configured`) | **info/soft, not full alarm** | "Re-ranking unavailable (results still semantic)" | live |
| degraded — impacting (embedding/index) | warn · orange | "Search degraded — keyword-only" | live |
| attention (open recovery conditions) | warn/error · orange/red + action | per condition | live |
| unreachable (Head down) | error · red | "Disconnected" | last-known |

The load-bearing UX moves:
1. **One verdict, every surface consumes it** — status bar, Health header+footer, search banner all
   project the SAME `(kind, severity, reasons)`; `computeStatusLabel` included (§10.1). No surface
   re-derives a verdict or re-phrases a condition.
2. **Tone is a projection of severity**, so a cosmetic degradation (LambdaMART) cannot render with the
   same alarm as a broken index (§10.3) — the per-reason severity is an FE-owned closed table over the
   reason vocabulary (the same shape `readinessNotice.CAUSE_ROWS` already uses for wording/remedy — a
   projection of the reason codes, not a re-derivation of state, so it stays within 557's "project,
   don't re-derive").
3. **Transition ≠ alarm** — `transitioning` is its own non-orange "busy" tone with last-known values +
   progress, rendered in-place on the surface that triggered it (the Library "Reindex" path).
4. **Empty ≠ unknown** — Library/Browse gain an `Unknown`/`unavailable` state (consulting `Stability`)
   so "No watched folders" renders only for a genuinely-empty configured list, never for a
   transient/unavailable backend.
5. **Cause accuracy is the verdict's job to carry, the leaf's job to phrase** — the verdict ships the
   accurate reason+severity; 594 owns the exact sentence. This stops the "semantic degraded / keyword
   results" misstatement from re-appearing.

### 10.6 Confidence after UX inspection — **8/10** (unchanged; risk profile shifted)

The inspection *raised* confidence in the structural core (the split is live and cross-surface; the
store/gate fit is confirmed) but *added* a scope item I'd previously have missed: the verdict needs a
**severity dimension + a per-reason tone/wording table**, or a unified verdict will be consistently
wrong (over-alarming) rather than inconsistently wrong. That is a design addition, not a blocker —
net confidence holds at 8/10. The remaining −2 is unchanged: §4.3 transition-render ergonomics unproven
at scale, 1.3's stall mechanism still a hypothesis, and the rebuild timeline resolved-from-code but not
yet seen live. Lowest-risk first step is still the verdict + `Stability` core (now: with severity).

---

## 11. Implementation effort + cross-tempdoc coordination (2026-06-16)

### 11.1 Effort estimate (calibrated against git history)

Calibrated against comparable implemented tempdocs: 557 (3 presentation authorities) = **48 commits / 2
days**; 559 = 47 / 5 days; 575 = 51 / 5–6 days; 564 = 68 / 3 days. Agent-implemented tempdocs land in
**days and dozens of small verified-increment commits**, not weeks; calendar is inflated by mechanical
migrations, review fixes, and merge-from-main reconciliation.

595 is a **depth-round on ONE of 557's three authorities** (Observed-state), with **no large mechanical
migration** (557's bulk was a 300+ literal theme sweep — 595 has no equivalent). Estimate: **~25–40
commits over ~3–5 calendar days**, in three shippable slices:
- (i) `Stability` axis + `computeVerdict` + severity/tone table + single-derivation gate + migrate 4
  consumers (header / footer / `readinessNotice` / status-bar `computeStatusLabel`) — ~15–20 commits,
  ~1–2 days; closes the live cross-surface 1.1 with no live-stack dependency (the safe first push).
- (ii) total-over-`(value, Stability)` renderers + `LibrarySurface` Unknown state — ~8–12 commits, ~1 day.
- (iii) job-feed collapse / `feed-stalled` — ~5–8 commits, ~0.5–1 day (after a repro picks §4.4(1) vs (2)).

Complexity: **medium** — extends existing infra (`aiStateStore` graph, `computeStatusLabel` as the
verdict precedent, 575 liveness, the `check-search-issuance` gate pattern), touches 4+ user-facing
surfaces, adds a severity model. Not a framework; not trivial.

### 11.2 Cross-tempdoc coordination (neighbors 585–605, active in the last 5h)

No tempdoc-named worktrees exist (only `main`); the sibling cluster is being worked **in the main
worktree** (593 ~1h ago, 594 ~25m, 596/597 ~4m — an active session is editing 596/597 now). Findings:

- **No conceptual interference.** 594/596/597 each explicitly draw a boundary with 595 and defer the
  verdict/phase to it: 594 §9.5 keeps `projectFact` leaf-level "to prevent re-growing a fourth
  health-verdict interpreter"; 596 §9.6/§9.7 "consumes the 595-phase, does not reproduce the verdict …
  coordinate, don't duplicate a phase machine"; 597 declares itself a different lineage (search-response
  contract) and 595 §5 already excludes the count/facet issue. The cuts are clean and mutually
  documented.
- **REAL practical interference = shared-file merge contention — but SMALLER than first feared
  (corrected §12.6).** All three FE depth-rounds edit **`aiStateStore.ts`** (595 adds
  `computeStability`/`computeVerdict`; 594 adds `projectFact`; 596 adds `projectAvailability`) — but
  these are **disjoint regions** (595's are private `compute*` helpers near 257–330 + new `AiState`
  fields; 594/596 add standalone exported projectors near 452+), so worst-case is adjacent-insertion /
  import-ordering, not logic collision. **`HealthSurface.ts` has NO contention**: 594's chip work is in
  `builtinPresentations.ts` / `MetricCardRenderer`, NOT `HealthSurface.ts` (the §11 claim "594 + 595 both
  edit HealthSurface.ts" was wrong) — 595 is its sole editor. Nothing is implemented yet (all 593–597 are
  design-only/untracked). Mitigation: land 595's additions clustered near the existing `compute*` helpers.
- **Ordering dependency (LOW risk, by design).** 596 is the downstream consumer of 595's `Stability`/
  phase; 596 §10/C4 explicitly decoupled (reads raw `phase`/`capabilities` now, consumes 595's
  projections when they land). So either order works; if 596 ships a hand-rolled transient first, 595
  later collapses it onto `Stability` — minor rework, not a blocker. Preferred: 595 slice (i) lands the
  `Stability` axis before 596 wires `Availability.transient`, so 596 consumes one source from the start.
- **Shared reason-vocabulary touch-point.** 595's per-reason severity/tone table (§10.5) and 596's
  `unavailable.reason` both word degradation causes; keep the system-verdict reasons (595) and the
  per-affordance reasons (596) as one shared reason vocabulary (the `readinessNotice.CAUSE_ROWS` /
  `LifecycleReasonCode` set) rather than two — coordinate, don't fork the wording.

Net: nothing in the neighbor cluster threatens 595's *design*; the only practical risk is mild
adjacent-insertion contention in `aiStateStore.ts` (see §12.6 correction — `HealthSurface.ts` is
uncontested).

---

## 12. Second confidence pass (2026-06-16) — residual + §10-introduced uncertainties, read-only

Targets the residuals after §9 and the *new* risks §10 introduced (the severity dimension, the
status-bar 4th interpreter). No feature code; code + sibling-doc + (prior) live-wire reads.

### 12.1 U1 — severity source: FE-owned table NOW; a backend severity authority exists but is incomplete + off-wire

The `/api/status` readiness wire carries **no** severity/impact field — only `state` + `reasonCode(s)`
(`ReadinessComponentView` / `ReadinessCompositeView`); `LifecycleReasonCode` is a flat enum with no
severity attribute (`lambdamart.not_configured` is structurally indistinguishable from
`worker.spawn.failed`). So there is **nothing to project** on the readiness wire today.
**However**, a backend severity authority *does* exist — `Severity{INFO,WARNING,ERROR}` +
`LifecycleSnapshotTap.MAPPING_TABLE` mapping `(dimension, state, reasonCode) → Severity` — but (a) it
rides the **HealthEvent/Condition** wire, not readiness, and (b) it has **NO rows for `LAMBDAMART_MODEL`
/ `CHUNK_EMBEDDING`** (they fall through to unmapped → no condition, no severity), i.e. it's silent for
exactly the codes 595's severity dimension cares about. The reason vocabulary is bounded but **not a
single closed enum** (the `LifecycleReasonCode` enum + ~6 inline `worker.health.*` string literals), so
any FE table needs an "unknown code ⇒ default severity" fallback — which `readinessNotice.CAUSE_ROWS`
already does (`Degraded: ${code}`).
**Decision surfaced (a real fork-vs-project choice, was implicit in §10.5):**
- *Pragmatic now:* FE-owned reason→severity table extending `CAUSE_ROWS` (+ unknown-default). FE-only,
  fast — but creates a **second** severity authority that can drift from the backend `MAPPING_TABLE`.
- *Principled (single-authority):* add the missing `LAMBDAMART_MODEL`/`CHUNK_EMBEDDING` rows to
  `LifecycleSnapshotTap.MAPPING_TABLE` and **surface severity onto the readiness wire**, then the FE
  *projects* it (per "project, don't re-derive"). This is **backend work** (api-record + the readiness
  composite), enlarging the severity slice.
This is the one genuine scope-expander the de-risk surfaced: the severity refinement (§10) is not
cleanly FE-only if done principledly.

### 12.2 U2 — the status bar is a PARALLEL verdict axis, not just a 4th reader (bigger than §10.1 implied)

`computeStatusTier` (`aiStateStore.ts:304–316`) and `computeStatusLabel` (`:257–284`) derive the status
bar's tone/label from `(phase, connection, runtime, activity)` and **never read `readiness`**. That is
the root of the live §10.1 split: `statusTier='online'` whenever `runtime.mode==='online'`, regardless
of `retrieval==='degraded'`. `statusTier` drives **two** consumers (`StatusDeck.ts:282` tone,
`LivenessReadout.ts:24` readout). So unifying the verdict is **a merge of two derivation axes**
(connection/runtime ⊕ readiness/reindex), not "make the status bar consume the Health verdict":
- the verdict's input = the UNION `(phase, connection, runtime, readiness, reindexRequired)`;
- `statusTier` → projected from `verdict.severity`; `statusLabel` → `verdict.kind` with the **activity
  overlay** (`thinking`/`streaming`/`extracting`) layered ON TOP (activity is an orthogonal "active
  work" state that already takes precedence in `computeStatusLabel` and must keep doing so).
Consequence: slice (i) is modestly bigger than "consume one verdict" — it subsumes `computeStatusTier`
into the verdict — but it's all inside `aiStateStore.ts` (one file, sole region), so still tractable.
This is the most useful surprise the de-risk caught.

### 12.3 U3 — total-render helper FITS (resolved cleanly)

The codebase already has the 2-branch total combinator `whenKnown(maybe, onKnown, onUnknown)`
(`StatusDeck.ts:304` for doc-count) alongside the non-total `orElse` (the anti-pattern to avoid for
transition-sensitive values). Crucially: during the worker-down window the doc count is **`known(0)`**
(the poll succeeds with a fallback `indexedDocuments=0`, so `computeIndex.fromStatus(0)` → `known(0)`),
so the existing 2-branch `whenKnown` renders a settled "0" — it **cannot** catch the provisional case.
`renderObserved(value, stability, {settled, provisional, unknown})` is therefore a natural **3-branch
extension** of `whenKnown` (the stability branch is necessary AND sufficient), composing with the
existing `Maybe` primitives rather than fighting them. Ergonomics confirmed.

### 12.4 U4 — gate completeness: confirmed feasible, narrower than feared

The readiness-axis verdict lives in exactly the **3 `.retrieval` sites** (HealthSurface header `:705`,
footer `:1123`, `readinessNotice.ts:100/108`). The connection/runtime axis is **already centralized** in
`aiStateStore` (`computeStatusTier`/`computeStatusLabel`) — not scattered — and post-collapse it folds
into the one verdict seam. So the gate scans for readiness-verdict formation (`\.retrieval\b` / the
composite access) outside the one verdict seam + allowlists the seam. No blind spot from the
connection axis (it was never scattered). Gate is buildable as §9.3 stated.

### 12.5 U5 — SSE-stall: unchanged (low severity, not re-tested)

Not re-investigated live (the shared dev stack is another session's and was flaky/"Reconnecting" during
§10). Remains the §8.4 hypothesis; §4.4's two options are both sound, so this gates only §4.4(1)-vs-(2),
not the design. Carried as a user-coordinated smoke.

### 12.6 U6 — merge contention: LOWER than §11 (correction)

Per the sibling-region read: **nothing implemented yet** (593–597 all design-only/untracked).
`HealthSurface.ts` is **595's alone** (594's chips are in `builtinPresentations.ts`/`MetricCardRenderer`;
596 doesn't touch it) — the §11 "594+595 both edit HealthSurface" claim was wrong (corrected in §11.2).
`aiStateStore.ts` is shared but the three additions are **disjoint regions** (595: private `compute*`
near 257–330 + `AiState` fields; 594/596: standalone exported projectors near 452+) → worst case is
adjacent-insertion, not logic collision.

### 12.7 Confidence after the second pass — **8/10** (held; surprises converted to known scope)

The de-risk did its job: it **converted two latent surprises into known scope** — (U2) the verdict must
merge the connection/runtime axis (subsuming `computeStatusTier`), and (U1) the principled severity
refinement has a backend dependency. It also **cleanly resolved** U3 (helper fits), U4 (gate feasible),
and U6 (contention low). Net confidence is unchanged at **8/10** — the structural core (Stability +
verdict closing the live 1.1 split) is solid and now fully mapped; the −2 is now precisely located:
the **severity dimension's fork-vs-backend-project decision (U1)** is the one open design call that
could enlarge scope, and §4.3 ergonomics/1.3 stall are minor. Slice (i) minus severity (Stability axis
+ verdict over both axes + the 3 `.retrieval` consumers + gate) remains the safe, surprise-free first
push; the severity table is the part to decide (FE-now vs backend-principled) before starting it.

---

## 13. As-built — implementation + gap-closure round (2026-06-17, `worktree-595-impl`)

The design (§4 + §10.5) was implemented in three slices, then a conceptual review against this doc found
three places the first pass under-delivered; a follow-up round closed them. Final state — all green
(typecheck + 3043 unit tests + the `verdict-derivation` gate + adjacent FE gates) and **browser-verified**
via the demo harness driving the real shipped bundle (the streaming-shell dev proxy can't hold a
connection, tempdoc 545):

**Implemented as designed.** `state/verdict.ts` (the `Stability` axis + severity-carrying
`SystemHealthVerdict` + `computeStability`/`computeVerdict`/`verdictHeadline`/`verdictTone`);
`aiStateStore` re-bases `computeStatusTier`/`computeStatusLabel` on the verdict (the §10.1 two-axis
merge); `readinessNotice.CAUSE_ROWS` gains the severity column + `severityForCodes`; `known.ts`
`renderObserved`; `HealthSurface` header+footer consume the verdict; `LibrarySurface`/`BrowseSurface`
Unknown-state gating; `scripts/ci/check-verdict-derivation.mjs` + register + ci.yml + CLAUDE.md.

**Gap 1 — the search-window banner now CONSUMES the verdict (closes §1.1's THIRD interpreter).**
`readinessNotice` was changed from `(ReadinessView)` to `(SystemHealthVerdict)`: it gates on
`verdict.kind === 'degraded'`, words by `verdict.severity`, and no longer reads `readiness.retrieval`.
`SearchSurface` subscribes `st.verdict` and sets the banner tone via `verdictTone`. The gate allowlist
is **tightened to ONLY `verdict.ts`** (the banner is no longer an exception). Browser-verified: the
cosmetic LambdaMART case now renders a calm, accurate "Reduced search capability… results are still
fully semantic" (tone `info`) — the §10.3 misstatement ("Showing keyword results") is gone — while an
impairing embedding gap still shows "Semantic search degraded" (tone `warning`).

**Gap 2 — `feed-stalled` is PANEL-scoped, not a global Stability cause (a §4.4 design correction).**
§4.4 option 2 / §4.5 wrote feed-stall as `Stability = provisional('feed-stalled')`, but that is a flaw:
`Stability` also gates the doc-count/folder renderers (§4.3), which must **not** go provisional for a
mere jobs-feed stall (the `/api/status` doc count is still fresh). So `feed-stalled` was **removed from
the global `ProvisionalCause` union**; the Tasks panel observes feed-staleness directly via
`indexingJobsBridge.subscribeFeedStalled` (closes 1.3 at the correct scope, already shipped + tested).
§4.1/§4.4/§4.5 should be read with this correction.

**Gap 3 — transition-sensitive renderers completed; "last known: N" deferred.** A missed 1.2 site —
`HealthSurface.renderStats` — was rendering a settled "0" for FILES/SIZE/QUEUE during a worker-restart;
it now shows "…" while provisional (MEMORY is Head-process and stays). Browser-verified (FILES/SIZE/QUEUE
"…", header/footer "Restarting…"). §4.3's "(last known: N docs)" enhancement is **deferred** as the
doc's own explicitly-optional "better" (§4.3 sanctions "at minimum … consults Stability"; "…" meets it)
— skipped to avoid a retained-last-settled signal + its signal-graph loop risk; a future improvement.

**Not done (out of scope, unchanged):** merge to `main`; the 594/596 sibling work; the backend-projected
severity (the FE-owned `CAUSE_ROWS` severity is the chosen authority per §12.1).

---

## 14. Future directions (2026-06-17 research round) — what the one authority now enables

Doc-only ideation. Because there is now ONE derived observed-state authority (the `Stability` axis +
`SystemHealthVerdict`), a whole family of polish / extension / feature ideas become *cheap*: they plug
into one place instead of N surfaces. Each below is checked against established UX / a11y / observability
practice (sources at the end). Nothing here is required — ranked by value × low-risk × grounding. The app
has no users and is not in production, so all are viable; no rush.

### Simplify (pay down duplication THIS implementation introduced)
- **S1 — one transition-wording source (do-now quality).** The transition `cause → wording` mapping
  currently lives in THREE switches: `verdictHeadline` (verdict.ts), `computeStatusLabel`
  (aiStateStore.ts), and `verdictBody` (HealthSurface.ts). That is the exact split-authority this doc
  fights, in miniature. Collapse to one `transitionWording(cause)` in `verdict.ts` consumed by the
  status bar + Health header + footer (the status bar still layers the *activity* overlay on top).
  Eat-our-own-dogfood; lowest risk, highest "should-just-do-it" value.
- **S2 — one provisional-render primitive.** `StatusDeck` uses `renderObserved`; `HealthSurface` uses a
  local `wkr` string helper. Fold both onto `renderObserved` (or a shared `provisionalText`).

### Extend (cheap because there's one authority to hook)
- **E1 — announce verdict changes to assistive tech (the biggest gap).** Today only the search banner is
  an `aria-live` region; the Health badge and the status-bar verdict change **silently** for screen
  readers. The verdict is the perfect single source: ONE polite live region announces verdict
  transitions, with `severity → politeness` (`error` ⇒ assertive, else polite), no focus move. Grounded:
  **WCAG SC 4.1.3 Status Messages** + ARIA live-region practice (polite for most; assertive only for
  critical). Highest-value new add and clean precisely because the verdict is single-sourced.
- **E2 — last-known value during a transition (the §4.3 "better", deferred).** Show "Rebuilding…
  (last known: 605 docs)" instead of "…", via a retained last-settled signal on the store. Grounded:
  **stale-while-revalidate** — "serve the last good value with a stale flag; never an error/zero." Medium
  effort (signal-graph retention + loop-safety).
- **E3 — real rebuild progress.** The migration block already carries `buildingIndexedDocuments`
  (climbing) + `activeIndexedDocuments` — **currently consumed nowhere**. The `transitioning` verdict
  could carry `progress`, turning the opaque "…" into an honest determinate bar ("Rebuilding: 1,203
  docs…"). Grounded: **NN/g progress indicators** — for >10 s waits, show real progress, don't fake it.
- **E4 — stuck-transition escalation (the original §4.2 `sinceMs`).** A transition that runs too long
  escalates from calm "Rebuilding…" (busy/info) to "Rebuilding… (taking longer than expected)" / a warn
  tone — closing the 593 §D/§F wedge (a wedged rebuild currently looks calm forever, the inverse of the
  old "looks wiped"). Grounded: the **"taking longer than expected" two-timeout** loading pattern.
- **E5 — decide `aiFeatures`' place (a scope call, not just code).** The verdict rolls up
  retrieval + reindex but ignores the `aiFeatures` composite (already captured in `ReadinessView`), so
  AI-chat degradation leaves the verdict "operational." Either fold `aiFeatures` into the verdict
  (whole-system health) or document the scope (verdict = retrieval/search health; AI features get a
  sibling indicator). Worth an explicit decision.
- **E6 — wire 596 (operability) to consume `Stability` as `Availability.transient`** — the documented
  downstream sibling; the authority is now ready for it.

### New UX features
- **N1 — completion confirmation (cheap, closes the loop).** On verdict `transitioning → operational`
  (rebuild/reconnect finished), a subtle "Rebuild complete" / "Reconnected" toast via the existing
  gate-blessed `emitEphemeralToast` channel. Grounded: SWR "Updated just now"; the user who saw
  "Rebuilding…" is told it finished.
- **N2 — transition history / timeline.** A small log (last rebuild when + duration, recent reconnects)
  for situational awareness — routed to a panel, NOT an interrupt. Ties to 575's observed-happening
  register. Grounded: alert-fatigue research (route context to dashboards; only critical interrupts).
  Higher effort.
- **N3 — the verdict pill as the canonical ambient system-status affordance** (always visible in the
  shell; click → Health). Grounded: **Nielsen heuristic #1, visibility of system status** — the
  foundational heuristic this whole authority serves.

### Suggested order
S1 (free dogfood) → E1 (a11y, high value, clean) → E3 + E4 (honest + escalating progress) → N1
(completion toast) → E2 (last-known) → E5/N2/N3/E6 as scope allows.

### Sources (research round)
Nielsen/NN-g: *Visibility of System Status* (#1), *Progress Indicators Make a Slow System Less
Insufferable*. W3C/WCAG: *SC 4.1.3 Status Messages* + ARIA live-region guidance. *Stale-While-Revalidate*
(web.dev / React-Query practice). Loading-UX pattern writeups (Smart Interface Design Patterns; Pencil &
Paper). Observability alert-severity / alert-fatigue practice (3 core levels; only critical interrupts;
actionable-only).

---

## 15. Design theory for the §14 ideas — scope-matched structure (2026-06-17)

§14 is a flat idea list. This section is the *design judgment*: for each idea, what structure does the
**actual** problem require — no more, no less. The governing finding: **595 single-sourced the verdict's
VALUE, but its PRESENTATION is still scattered.** `verdictTone` + `verdictHeadline` live in `verdict.ts`,
`computeStatusLabel`'s transition arm in `aiStateStore.ts`, and `verdictBody` in `HealthSurface.ts` — the
**same split-authority this doc fights, one layer up** (the transition `cause → wording` mapping is
literally duplicated across three switches). That single observation collapses most of §14 into one
structural move plus a short list of deliberate non-changes.

### 15.1 The ONE structure the problems require — a single verdict-PRESENTATION projection
Add `presentVerdict(verdict) → { tone, headline, body, announce: { text, politeness } }` in `verdict.ts`,
consumed by every surface. This is **not new framework** — it is 557's existing `present()` projector
pattern (`shell-v0/display/present.ts`) applied to the verdict, sized exactly to two *real* problems:
- it dissolves **S1** (the 3× transition-wording duplication) — one wording source, the surfaces consume;
- it **seats E1** (the WCAG 4.1.3 announcement gap) at zero extra structure: the announcement is just the
  `announce` field of the same projection. Routed through **559's existing `<jf-system-notice live>`
  authority** (`live: status|alert|off` → role+aria-live; the ONE live-region owner, 559 Authority III) —
  E1 extends 559's announcer, it does **not** add a new one. `severity → politeness` (error ⇒ assertive,
  else polite).
The status bar keeps layering its **activity** overlay (Thinking/Streaming) and runtime label *on top of*
the projection — the projection is the base, not the whole. This is the natural completion of 595: the
value was collapsed; the presentation is the remaining scatter.

### 15.2 Extend the verdict's DATA shape only for the documented wedge (E4); leave E3 composable
**E4 (stuck-transition escalation) is warranted** — it fixes a *documented* problem (the 593 §D/§F wedge:
a wedged rebuild now looks calm forever, the inverse of the old "looks-wiped"). Structure: the
`transitioning` verdict carries `sinceMs`; `computeVerdict` escalates severity past a threshold;
`presentVerdict` words it ("taking longer than expected"). **E3 (real rebuild progress from the unused
`buildingIndexedDocuments`) is an enhancement with no documented bug → do NOT add a `progress` field as
required structure.** It composes into the same `transitioning` shape + `presentVerdict` if ever wanted;
building it now is speculative completeness.

### 15.3 Deliberate non-changes (scope discipline — structure the problem does NOT require)
- **E5 (fold `aiFeatures` into the verdict): NO.** Slice 456 *intentionally* excludes the AI/inference
  component from the overall-health badge ("AI offline by design … `inference.offline` is NOT a system
  fault"). Folding it in would contradict a deliberate decision and manufacture a false alarm. Correct
  move: a one-line **scope clarification** — the verdict is *retrieval/search* health; AI features are
  surfaced by their own AI-Engine card. No structure.
- **E2 (last-known value): optional, no framework.** The 1.2 catastrophe is closed ("…" not "0") and §4.3
  sanctioned "…" as the minimum; SWR's last-known is a nicety with no documented bug. If ever done, it is
  a localized retained-last-settled signal on the store — not a new structure.
- **N2 (transition history): NOT a 595 concern.** 575 explicitly defers rebuild/boot history to "their
  own spines" (575 §13 L1 / §6: "boot trace / rebuild history … deferred to the streams' own spines by
  design"). History belongs to that deferred rebuild-history spine; 595 must not grow a parallel one.
- **S2 / N1 / N3: localized, no structure.** One shared provisional-render helper (`renderObserved`); one
  edge-emit (`transitioning → operational`) through 559's existing toast channel; one nav wiring on the
  existing status pill.
- **E6 (596 consumes `Stability`):** already the documented downstream contract; no 595 structure needed.

### 15.4 Net
The real problems require exactly **(1) one verdict-presentation projection** (dissolves S1, seats E1 via
559's live-region authority) and **(2) `sinceMs` on the `transitioning` verdict** (E4, the documented
wedge). Everything else is localized, a sibling authority's (559 announcer / 575 history / 596 consumer),
or a deliberate non-change (E5, E2). No framework, no rewrite — the change is small because 595 already
built the hard part (the single verdict value), and the remaining structure is just finishing the same
single-authority cut at the presentation layer.

---

## 16. As-built — the §15 presentation-projection round (2026-06-17, `worktree-595-impl`)

Implemented exactly the two structures §15 judged warranted, plus the E5 scope note; the rest stayed
deliberate non-changes. All green (typecheck + **320 files / 3053 unit tests** + the verdict gate +
adjacent FE gates) and **browser-verified** (demo harness, real shipped bundle).

- **One verdict-presentation projection (S1 + E1).** `verdict.ts` now owns `verdictBody` and a bundling
  `presentVerdict(v) → { tone, headline, body, announce: { text, politeness } }` (mirrors `present()`).
  The Health header/footer consume `presentVerdict`; `HealthSurface`'s local `verdictBody` is deleted;
  and `aiStateStore.computeStatusLabel`'s transition `switch` was replaced by a `verdictHeadline(verdict)`
  call — so the transition wording lives in ONE place (the §15 dup is gone). The status bar still layers
  its activity overlay + runtime label on top.
- **E1 — a11y announcement of verdict changes (WCAG 4.1.3).** `StatusDeck` (always-mounted) renders ONE
  visually-hidden `<jf-system-notice live=…>` announcer driven by `presentVerdict(...).announce`, reusing
  559's live-region authority (no new announcer). Browser-verified: `role=status`/`aria-live=polite`
  mirroring the verdict for non-error states, flipping to `role=alert`/`assertive` on an error verdict;
  visually hidden; text changes only on a real verdict change.
- **E4 — stuck-transition escalation (pure projection of the wire; the 593 §D/§F wedge).** `VerdictInput`
  gains `migrationPaused`/`migrationSwitchingAgeMs`/`migrationSwitchingMaxDurationMs` (already on
  `/api/status`); `computeVerdict` escalates a `rebuilding`/`generation-switch` transition from `busy` to
  `warn` (+`'paused'`/`'overdue'`) using the backend's own signals — no FE timer (the ~5 s poll drives
  it). Browser-verified: badge + footer escalate to "Rebuild paused" / "Rebuilding… (taking longer than
  expected)" at the warning tone, header and footer consistent.
- **E5 — scope clarified (no code beyond a comment).** A note on `computeVerdict` records that the verdict
  is *retrieval/search* health and the AI component is excluded by design (slice 456), surfaced by its own
  AI-Engine card. `aiFeatures` is not folded in.
- **Deliberate non-changes (per §15.3):** S2 (fold `wkr` → `renderObserved`) was **skipped** — the shapes
  differ (`wkr` works on raw status fields + a `known` boolean; `renderObserved` on `Maybe<T>`), so forcing
  it would add churn for no gain; `wkr` stays a fine local. E2 last-known, E3 progress, N1/N2/N3, and the
  `aiFeatures` fold remain out of scope as judged.

**Merged to `main`** (2026-06-17, merge `95f3b4add`): the FE implementation auto-merged cleanly alongside
the parallel 594 (chip facts) and 597 (result-count) work that had also touched `HealthSurface.ts` /
`SearchSurface.ts` — verified on merged main (typecheck clean, **321 files / 3074 unit tests**, the
verdict-derivation + observed-state/presentation/message/a11y gates green, vite build green). The worktree
and `worktree-595-impl` branch were removed post-merge. Tempdoc 595 is **complete**; the only open items
are the deliberate, documented future ideas in §15.3 (E2/E3/N1/N2/N3), none required.

## 17. Confidence pass on the deferred §15.3 ideas (2026-06-17) — read-only de-risk, no feature code

The core is shipped + merged. The remaining work is the OPTIONAL §15.3 menu (E2 last-known, E3 progress
bar, N1 completion toast, N3 clickable pill; N2 history is 575's). This pass resolves the technical
uncertainties so any of them can be picked up later without surprises. **No code, config, or commits — a
source-verified de-risk only.** Each finding cites the verifying source.

### 17.1 E2 (retain last-settled index value) — loop-safe template EXISTS — confidence 8/10
The store is signal/`computed`-based, so the only real risk was a signal-graph loop from feeding retained
state back into the `computed`. **There is no loop risk**: `onStatusUpdate(snap)` (`aiStateStore.ts:497`)
is the *imperative-on-input* poll callback that already stamps `lastStatusSuccessSig.set(Date.now())` on a
successful poll. E2 stamps a new `lastSettledIndexSig` in the **same** callback, gated by a pure
`isSettled(rawStatus)` predicate (migration IDLE + a serving `indexState` + a present `documentCount`); the
render path reads it as a fallback when `aiState.stability` is provisional. The writer is the poll callback,
not a `computed`, so the graph stays acyclic.
- **Why `ConnectionPhase.stale` does NOT already cover this** (confirms §15.3's parenthetical): `stale`
  retention works only when polls *stop* arriving (`onStatusUpdate` skips the `set` on a null poll, so
  `statusSig` keeps its last value). A worker-restart is different — the worker comes back UP and returns a
  *successful* poll carrying a genuine `0` / `indexState:'UNAVAILABLE'`, which **overwrites** `statusSig`.
  That real-but-provisional 0 is exactly what E2's retained-settled signal must shadow.

### 17.2 E3 (rebuild progress bar) — fields exist but the fraction is APPROXIMATE — confidence 6/10
- **Signal:** `worker.migration.{buildingIndexedDocuments, activeIndexedDocuments}` are on the wire (longs,
  `status-response.ts:307–309`), mapped by `WorkerStatusMapper` from `migration.get{Building,Active}DocCount()`
  (`WorkerStatusMapper.java:98–99`).
- **Faithfulness caveat (the real risk):** `activeIndexedDocuments` is the *old serving generation's* count.
  Using it as the denominator (`building/active`) assumes the rebuild reproduces the same corpus. If the
  rebuild re-chunks or adds/removes docs, the ratio can overshoot 100% or finish early. Honest verdict: ship
  it only as an **indicative** bar — clamp to ≤100%, label "Rebuilding…" (not "X% done") — OR confirm the
  rebuild-target semantics against `IndexGenerationManager` first if an exact % is wanted. This caveat is the
  only reason E3 sits below the others.
- **Primitive:** `.progress`/`.progress-bar` (green/amber/red) is **component-local CSS in `HealthSurface`**
  (used 2× — memory card `:831`, VRAM card `:1052`), not a shared atom. Cheapest path: a Health stats-card
  "Rebuild progress" row reusing that local CSS. A status-bar bar would require extracting the CSS into an atom.

### 17.3 N1 (completion toast) — sanctioned channel + spam-free edge — confidence 8/10
- **Channel:** `emitEphemeralToast` (`components/advisory/ephemeralToast.ts`) is THE single client-originated
  message API (559 Authority III) → one `AdvisoryStore` → one `AdvisoryToastHost`. The `message-single-model`
  gate **permits** it (it is the existing sanctioned API, not a new channel).
- **Edge + anti-spam:** the store keeps no previous-verdict ref today. Cleanest home is the always-mounted
  `StatusDeck` (already the verdict consumer + announcer host) holding a tiny `prevVerdictKind`. Emit ONLY on
  the edge `prev === 'transitioning' && next.kind === 'operational'`. That predicate **structurally** excludes
  first-load (`connecting → operational`) and reconnects (`unreachable → operational`), so there is no toast
  spam to debounce.

### 17.4 N3 (clickable status pill) — nav seam ready, but pill must become a control — confidence 8/10
- **Navigation seam (reuse, do not invent):** dispatch `new CustomEvent('navigate-with-context', { detail:
  { target: 'core.health-surface' }, bubbles, composed })`; `Shell` listens (`Shell.ts:638`) → IntentRouter.
  Precedents: `SearchSurface`, `CommandPalette`, `NavigateView`, and the `utils/compose.ts` helper.
- **Required change (the one real cost):** the status items in `StatusDeck` are read-only
  `<span class="group" role="img" aria-label=…>` (`StatusDeck.ts:354`), not operable. To pass
  `check-controls-a11y`, the clickable pill must be **promoted to a `jf-control`** (or role=button + tabindex
  + keydown). Small, but it is a structural change, not a pure add.

### 17.5 N2 (transition history) — CONFIRMED out of 595 — settled
§15.3 (lines 1055–1057) + 575 §13 L1 / §6 park rebuild/boot history in 575's deferred history spine. 595 must
not grow a parallel one. No action.

### 17.6 Rating (remaining work) — **8/10** for the cheap three (N1, E2, N3); **6/10** for E3
All remaining work is optional and well-understood after this pass. N1/E2/N3 each have a named, loop-safe,
gate-compatible mechanism with a single small structural cost (N3's pill→control promotion). E3 is the only
one carrying a genuine open question — whether `building/active` is a faithful enough fraction to show a
number — and should ship as an indicative bar or wait on an `IndexGenerationManager` semantics check. Nothing
here is a blocker; none is required for 595's thesis, which is complete.

## 18. As-built — the §15.3 deferred ideas, implemented (2026-06-17, `worktree-595-followup`)

All four deferred ideas (E2/E3/N1/N3) implemented exactly as the §17 confidence pass scoped them, each
reusing an existing seam (no new store, message channel, gate, or CSS primitive). N2 stayed out (575's
spine). Green: typecheck + **323 files / 3106 unit tests** + the controls-a11y / message-single-model /
verdict-derivation / presentation-purity / observed-state / color-token / contrast / a11y-closure /
layout-purity gates. **Browser-verified** (demo harness + `__feedForTest`, real shipped bundle).

- **E2 — last-settled index retention.** `aiStateStore` gained `lastSettledIndexSig`, stamped in the
  `onStatusUpdate` poll callback when the snapshot is settled (settled-ness reuses the one pure
  `computeStability` oracle; imperative-on-input → acyclic, the §17.1 finding). Exposed as
  `AiState.lastSettledIndex`. `HealthSurface` (the Files/Size cards) and `StatusDeck` (the files/size
  metrics) show the retained value with a dimmed "last known" cue while provisional, instead of collapsing
  to `…`. The worker-restart 0/`UNAVAILABLE` (a *successful* poll) no longer reads as data loss.
  Browser-verified: Files "1,234" + Size "50.0 MB" shown dimmed during a worker-restart provisional.
- **E3 — rebuild progress bar.** `HealthSurface.renderRebuildProgress()` shows an INDICATIVE bar (the
  local `.progress`/`.progress-bar` markup, calm `info` tone) during a `rebuilding`/`generation-switch`
  provisional, from `building/active` doc counts — clamped ≤100%, labelled "Rebuilding…" (never an exact
  %, per the §17.2 faithfulness caveat), `role="progressbar"` with `aria-valuenow`. Browser-verified at
  600/1000 → a 60% bar.
- **N1 — completion toast.** `StatusDeck.updated()` fires ONE `emitEphemeralToast` (the 559 sanctioned
  single channel) on the `transitioning → operational` verdict edge; first-load
  (`connecting→operational`) and reconnects (`unreachable→operational`) are structurally excluded, so no
  spam. Browser-verified: exactly one "Index ready — all systems operational" on the edge, none on
  reconnect.
- **N3 — clickable status pill.** The `core.inference-mode` status item is now a `<jf-control>` (the one
  operability primitive — native button, keyboard-operable, named) that opens Health via the
  `navigate-with-context` seam (`detail.target = 'core.health-surface'`). The `controls-a11y` gate was
  EXTENDED (not weakened) to recognise a metric name projected via a `jf-control` `label` (not only
  `aria-label`); the inline-authoring protection is unchanged. Browser-verified: activating the pill fires
  `navigate-with-context` → `core.health-surface`.

Tempdoc 595 — including every §15.3 deferred idea — is now **fully implemented**.

### 18.1 Post-merge review hardening (2026-06-17, `worktree-595-fixes`)

A critical self-review of the §15.3 merge found two correctness/honesty gaps (and one non-issue),
all fixed:

- **N1 reliability — toast survives an intermediate `checking`.** The original edge predicate
  (`prev === 'transitioning' && next === 'operational'`) missed the real recovery path
  `transitioning → checking → operational` (a settled poll whose readiness is momentarily `unknown`
  resolves to `checking` first). Replaced with a `sawTransitioning` flag: set on `transitioning`, fires
  + clears on `operational`. Spam-guard preserved (first-load/reconnect never set the flag; cleared each
  operational, so a later `degraded → operational` with no transition is silent). Browser-verified: 0
  toasts through `checking`, exactly 1 on reaching `operational`.
- **E2 size honesty — no fake "0 B / Last known".** `lastSettledIndex.indexSizeBytes` is now
  `number | null`; `stampSettledIndex` retains `null` when a settled snapshot lacked the size (was
  `?? 0`). HealthSurface + StatusDeck show the last-known Size only when it was actually observed, else
  `…` — Files retention is unaffected. Browser-verified: a settled poll without a size → Files "1,234"
  last-known, Size "…".
- **Non-issue.** The Health error banner seen during demo-harness validation was `this.host_.data.fetch`
  throwing because the standalone demo mounts `<jf-health-surface>` without a `host_`; a harness artifact,
  not a code defect, and unrelated to `renderRebuildProgress`.

Green: typecheck + **3111 unit tests** (+4 regression tests) + the controls-a11y / message-single-model /
verdict-derivation gates; browser-verified both fixes via the demo harness.
