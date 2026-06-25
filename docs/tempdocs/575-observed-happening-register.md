---
title: "575 — The Observed-Happening Register: the projection spine at the observability-authority tier. Every 'what happened / what's happening' stream (logs, health conditions, the action lifecycle, indexing jobs, metrics, advisories, boot, …) declares its ONE canonical source, its KIND (the facet-coordinate that derives which primitive owns it), and its governed projections — so a mechanically-discriminable misclassification, the re-fragmentation of a DECLARED concept, and a stateful stream missing its liveness owner become build failures. Bounded (per 550 §5, de-risk §9) by the irreducible discovery problem: recognizing that two streams are secretly one concept stays detection+review, not prevention. The data-tier sibling of 571 (surfaces) and the family-level generalization of 550/553 (per-concept canonical records)."
type: tempdocs
status: >-
  IMPLEMENTED + MERGED to main (2026-06-11). The design theory below was carried into shipped governance:
  §13 (L1 reverse-coverage + L2 shape conformance), §14 (the FE liveness derivation), §15 (Pillar-3b — the
  liveness authority + the window-coherence gate + the projection gate), and the two §16 Tier-1 indirect
  follow-ups (the dev-override prod-hardening + this canonical-doc section) are all on `main`. The
  `observed-happening` discipline gate + `governance/observed-happening.v1.json` register are live; the
  Tier-2/3/4 items in §16 remain deferred-by-design. **§17 (2026-06-11) adds the FORWARD design theory** — the
  correct long-term *generative + projective* completion of the spine (generate the constants/types/map *from* the
  register à la the 564 wire-schema spine; the **System Self-View** as a projection authority composing the 571/559/
  565/557 authorities; **liveness as a declared model**, governed not forced into one code path — the AHA-correct fix
  to §16 Tier-2). **§17 is now IMPLEMENTED + MERGED to main (2026-06-11):** Face A
  (the liveness window generated from the register into the worker + FE constants), Face C (the declared
  `liveness.model` per stateful concept + the backend install/pack reaper backstop + install/pack registered as
  Resources), and Face B (the `core.system-self-view` surface + its `live-concept-unprojected` reverse-coverage
  gate) all landed and verified green; see §17.10 (as-built) and §17.11 (a conceptual-review follow-up that fixed a
  committed Face A generator regression and shipped Face B's missing gate). The original design framing is retained
  below for the design record. — CORRECT-DESIGN THEORY (2026-06-10). The correct long-term structure for *how the observability /
  "what-happened" stream family is modeled, classified, and kept single-sourced*. The motivating question ("why are Logs and Recent-events two things? are logs/events/activity/
  metrics handled correctly?") turned out to be a *proof-by-example* of a missing tier: the codebase
  proves the projection-spine meta-principle ("one canonical source per concept → governed projections →
  a coverage gate") **per-domain, one concept at a time** (553 for search execution, 550 for the action
  lifecycle, ADR-0027 for metrics), but there is no **family-level** register or gate — so for a *new*
  observability concept nothing catches the two recurring failures: choosing the **wrong primitive**
  (Resource shape vs DiagnosticChannel vs Metric vs Advisory) or fragmenting one concept across **several
  sources**. The single claim: lift the already-proven meta-principle into ONE enforced register at the
  observability-authority tier — classify, do NOT flatten (the heterogeneous primitives stay distinct;
  the register sits *above* them). This **completes 550's own "principal unrealized work" (the liveness
  invariant) family-wide** and is the **data-tier sibling of 571** (surfaces). Genre: design-theory per
  557/559/567 — feasibility, phasing, and sequencing deliberately disregarded; major rewrites/refactors
  in scope; end-states stated at the bar the category sets. Current-behavior claims verified against
  `main`/`gfmg` on 2026-06-10 (citations inline); re-verify before relying.
created: 2026-06-10
updated: 2026-06-11
category: observability / telemetry / diagnostics / event-streams / single-authority / projection / registry / design-theory
related:
  - tempdoc 553 (THE PARENT PATTERN — the canonical search-execution record: one canonical record → governed projections → an execution-surface anti-drift gate. 575 generalizes the same spine from "one concept" to "the whole observability family")
  - tempdoc 550 (the action-lifecycle instance of the spine — one canonical `LifecycleEvent` + federated contributors + governed projections; its **Thesis II liveness invariant** ("every in-flight record has a live owner") is designed-not-shipped and is the F-1 fix 575 lifts family-wide; 550 §4 already names the meta-principle as generalizable)
  - tempdoc 571 (the SURFACE-tier sibling — the projection spine applied to *where surfaces live*; 575 is the same spine one tier down, at the *authority* the surfaces project)
  - tempdoc 559 (the projector set — noted only to RECORD that a shared per-genre feed projector is deliberately NOT proposed here: 571 §9.B declined it as over-DRY, §3 + §9 U3)
  - ADR-0036 / CONFLICT-LEDGER C-012 (the Resource-vs-DiagnosticChannel decision: operator-traces are "not Resource truth" — the **five-axis discriminator** (origin, schema, audience, privacy class, self-observability) 575 formalizes into the facet→primitive derivation)
  - ADR-0027 (the metric-catalog-as-telemetry-contract — the mature per-domain proof for the metrics genre; `MetricSurfaceContractTest` is the precedent gate)
  - tempdoc 530 / 531 (the discipline-gate kernel + the per-domain register/gate pattern — operation-surface / execution-surface / surface-altitude / consumer-drift — that 575's family gate composes)
verified-against-main:
  - modules/app-agent-api/src/main/java/io/justsearch/agent/api/registry/Resource.java (the ~9-facet taxonomy: Category × SubscriptionMode × Role × Audience × Privacy + Optional<HistoryPolicy> + Optional<EmissionPolicy> + keying)
  - modules/app-agent-api/src/main/java/io/justsearch/agent/api/registry/Category.java (STATE / EVENT_STREAM / HISTORY / TABULAR / TIMESERIES — LOG_TAIL retired per C-012 path-b, 2026-05-07)
  - modules/app-agent-api/src/main/java/io/justsearch/agent/api/registry/DiagnosticChannel.java (the sibling primitive: dataClasses × ProducerKind × DeliveryMode × LoggerNamespaceSelector × ConsumerPermission — the three Resource-orthogonal axes)
  - docs/decisions/0036-fe-resource-category.md (the LOG_TAIL retirement + the "not Resource truth" reasoning)
  - docs/tempdocs/550-agent-action-lifecycle.md (§4 "the same shape elsewhere" — the meta-principle stated once; Thesis II liveness as principal unrealized work)
  - docs/explanation/08-observability.md (the metric-catalog discipline + the health "named-question" pattern — deriveHealthEvents.ts as the FE-owned condition taxonomy)
---

# 575 — The Observed-Happening Register

## §1 Thesis — the projection spine, one tier up

The codebase runs one projection spine at every altitude:

> **one authority → a typed declaration → a governed projection → the breaking/fragmenting form made
> *unrepresentable* → a register/gate whose coverage itself projects from the authority's catalog.**

571 applies this spine at the **surface tier** (where a window lives is derived/declared, not improvised).
This doc applies the *same* spine one tier **down** — to the **observability-authority tier**: the
*streams* that surfaces project. An "observed happening" is anything the system records about *what it
did or what is true of it* — a log line, a health condition, an action-lifecycle event, an indexing-job
row, a metric sample, an advisory, a boot phase. There are ~12 such streams today.

The codebase **already proves the spine for individual observability concepts**: 553 made "what the
search pipeline did" one canonical record with governed read-views and an anti-drift gate; 550 did it
for the action/operation lifecycle; ADR-0027 did it for metrics (one typed `MetricCatalog`, a
`MetricSurfaceContractTest` that fails the build on drift). 550 §4 even states the principle once and
names it generalizable: *"one canonical source per concept; a completeness/coverage contract that fails
the build when a consumer's needs aren't met; every surface a governed projection."*

**The gap is that the spine is applied per-concept, ad-hoc, one tempdoc at a time — never at the family
level.** So while each *shipped* concept is well-governed, the **meta-properties of the family are
ungoverned**: nothing enumerates the streams, nothing catches a *new* concept that picks the wrong
primitive, and nothing forces a new concept to have exactly one canonical source. The keystone:

> **An observed happening declares its ONE canonical source, its KIND (the facets that derive which
> primitive owns it), and its governed projections. Decide none of the three by ad-hoc judgment — so a
> *declared* concept cannot be re-fragmented, a mechanically-discriminable misclassification cannot ship,
> and a stateful stream cannot omit its liveness owner.**

> **Honesty bound (de-risk §9, inherited from 550 §5):** the register makes these failures **structurally
> hard to re-enter within a *declared* concept's scope (~100%)** — it does **not** make them
> "unrepresentable" in general. The **discovery problem** — recognizing that two independently-declared
> streams are secretly *one* concept — is **irreducible**; for that residue the register gives
> detection-plus-review, *reduction not prevention*. 575 claims exactly what 550 claims, no more.

## §2 What already exists — this is generalization, not invention

The whole design is assembled from proven parts; the only genuinely new pieces are §4.1's family
register and §4.2's facet→primitive derivation. The reuse map:

- **The meta-principle** — stated once at `550-agent-action-lifecycle.md` §4 ("the same shape
  elsewhere"); parent 553. 550 already lists four *future* applications (theme tokens, i18n, event
  transport, surface state) — i.e. the codebase already knows the pattern generalizes; it just applies
  it case-by-case.
- **The classification vocabulary already exists, richly.** `Resource` carries ~9 facets
  (`Category` ∈ {STATE, EVENT_STREAM, HISTORY, TABULAR, TIMESERIES} × `SubscriptionMode` × `Role` ×
  `Audience` × `Privacy` + optional `HistoryPolicy` + optional `EmissionPolicy` + keying). `DiagnosticChannel`
  is the **sibling primitive** for operator-traces, carrying three Resource-orthogonal axes
  (`ProducerKind` origin, `LoggerNamespaceSelector` structural routing, the `DELIVERY_INTERNAL`
  self-observability/recursion guard). **ADR-0036 / C-012** records the discriminator verbatim
  ("operator traces are not Resource truth … different consumer model, schema discipline, privacy class,
  and self-observation risk") — that is the five-axis test §4.2 formalizes.
- **The gate machinery + precedent** — the 530/531 discipline-gate kernel and the per-domain
  register/gates (`operation-surface`, `execution-surface`, `surface-altitude`, `consumer-drift`). 575's
  family gate composes the same kernel; `MetricSurfaceContractTest` (ADR-0027) is a precedent for a
  contract gate over a stream genre.
- **The liveness design already exists *as a design*** — 550 Thesis II ("every in-flight record has a
  live owner") is the fix for the orphan-record class. It is **designed-not-shipped** ("the principal
  unrealized work this rewrite names"): the invariant is formulated generally (it mirrors 508's
  `AiStateStore`, which derives a status tier from many contributors), but **no general owner-liveness
  mechanism exists** — it has not shipped for even one concept. So lifting it family-wide (§4.3) is
  genuinely-new work, not "wire up an existing thing."
- **(NOT reused — render genre.)** 559's projector set could host a shared feed projector, but 571 §9.B
  *deliberately declined* to build one (AHA — Activity and Logs are distinct authorities with distinct
  reasons to change). 575 does **not** reopen that (see §3); the render genre is out of this design.

## §3 The defect class — one missing tier, two faces

Because the family-level discipline is absent, the same under-governance shows two faces:

1. **Misclassification (wrong primitive).** "Should this stream be a `Resource` shape, a
   `DiagnosticChannel`, a `Metric`, or an `Advisory`?" is decided by author judgment against ADR-0036 +
   an *implicit* facet model. No gate catches a stateful, keyed collection declared as a firehose, or a
   recursion-risk log modeled as a Resource. The correct classification exists in the primitives'
   vocabularies but is **not enforced as a coordinate system**. (§4.2 de-risk: *partly* mechanizable
   today — the discriminator the headline Channel-vs-Resource case needs is not yet a declared facet.)
2. **Representation-drift (>1 source per concept).** 550 documents two live bugs from exactly this:
   **F-2** — Activity rendered "no operations recorded" while the rail flooded with advisories, because
   action-ledger / indexing-jobs / advisories were three stores read separately; **F-1** — ~40 "indexing"
   badges survived a cold restart byte-identical, because in-flight records had **no liveness
   reconciliation**. Their status differs and matters: **F-2 is already *dissolved*** (550 Theses I+III
   shipped — federated contributors projected from one record), so it is the **proven precedent that the
   discipline works**; **F-1 is the *open* case** (Thesis II liveness, unshipped). The *class* can recur
   for any new concept, because the one-source-per-concept rule is enforced only where a bespoke gate
   already exists — and even then bounded by the irreducible discovery problem (§1 honesty bound).

> **What is NOT a third face (de-risk correction).** An earlier draft listed render-genre duplication
> (Activity & Logs both being virtualized filtered feeds) as a third face and proposed a shared projector.
> 571 §9.B already adjudicated this: it *deliberately declined* the shared filtered-event-stream projector
> as over-DRY (AHA — "distinct authorities with distinct reasons to change"), extracting only the
> genuinely-shared `newestFirst` + the SSE primitive. That decision is correct and 575 defers to it; the
> render genre is **not** a defect this design fixes.

These are not two problems; they are one missing tier (no family-level spine) seen two ways.

## §4 The correct structure — the observed-happening register

One register at the observability-authority tier, governing **meta-properties only** (it classifies and
single-sources; it never flattens the heterogeneous primitives into one schema). Three pillars (the
render-genre is deliberately *not* a fourth — §3).

### §4.1 Pillar 1 — the register (the missing enumeration)

Every "what happened / what's happening" concept is a declared entry naming three things:

- its **canonical source** — the ONE store that is the truth for this concept;
- its **kind** — the facet-coordinate (statefulness, keying, retention, origin, recursion-risk, genre,
  audience/role) from which the owning primitive is *derived* (§4.2);
- its **governed projections** — the read-views/surfaces that render it, each declaring the projection it
  consumes (the existing `consumer-drift` / `operation-surface` lineage check).

This is the "single written taxonomy" the research found missing — but as an **enforced register**, not
prose. Coverage projects from the register the same way 553/550/surface-altitude project coverage from
their catalogs. It **classifies; it does not merge**: `Resource`, `DiagnosticChannel`, and the metric
catalog stay distinct primitives — the register sits *above* them and records *which* one each concept
uses and *why*.

### §4.2 Pillar 2 — misclassification made a build failure (kind is derived, not asserted)

The "which primitive owns this stream" decision stops being judgment. Each entry declares its facets;
a **derivation** maps facets → the correct primitive, generalizing the existing `Category ×
SubscriptionMode` matrix and **ADR-0036's five axes** into a full family coordinate system. (The
mechanism has direct precedent: the `surface-altitude` gate already derives a verdict from parsed catalog
facets — Role / Category / channel-presence — and fails on contradiction. §4.2 is that gate's shape,
one tier over.)

> recursion-risk / in-process-logger origin / namespace-routing ⟹ **DiagnosticChannel**;
> keyed collection with per-item deltas ⟹ **Resource TABULAR**;
> append-only typed events with a recent window ⟹ **Resource EVENT_STREAM**;
> current-value-no-history ⟹ **Resource STATE**;
> sliding-window numeric samples ⟹ **TIMESERIES / a MetricCatalog**;
> dedup-windowed user-facing notification ⟹ **Resource KIND_ADVISORY**.

Declaring a stream whose primitive contradicts its facets (a stateful keyed thing as a firehose) is a
**build failure** — *without* collapsing the two primitives (the heterogeneity ADR-0036 documents is real
and preserved; only the *choice between them* is governed).

> **De-risk prerequisite (§9 U2) — one missing facet.** The shape mismatches above (TABULAR without a
> primaryKey, EVENT_STREAM without a HistoryPolicy, …) fire on facets `Resource` *already* declares. But
> the **headline case — a recursion-risk log modeled as a Resource that should have been a
> DiagnosticChannel — cannot fire today**, because `Resource` declares **no `origin` / self-observability
> facet** (only `DiagnosticChannel` carries `ProducerKind`). So Pillar 2 has a named precondition: add the
> discriminator to `Resource` (an `origin: Optional<ProducerKind>`, or a reserved `Role`/`kind` proxy) so
> the gate can read "this is operator-trace data, not product truth." This is **one new declared facet**,
> not a redesign — but it is genuinely new, so §4.2 is "reuse the surface-altitude derivation *machinery*;
> add the one discriminator the Channel-vs-Resource axis needs," not "derive from existing facets."

### §4.3 Pillar 3 — drift + orphans foreclosed *within a declared concept* (550, generalized family-wide)

Two foreclosures lift 550's lifecycle-specific discipline to every concept. **Both are bounded by the §1
honesty bound: they foreclose re-entry within a concept whose canonical source is *declared*; they cannot
discover a *new* fragmentation on their own (550 §5).**

- **One canonical source per *declared* concept.** Once a concept's canonical source is declared, a second
  store for it is a build failure (the discipline that **dissolved F-2** — generalized from "actions" to
  every concept). What the gate cannot do — and 550 §5 is explicit — is *recognize* that two
  independently-declared streams are secretly one concept; that discovery stays detection-plus-review.
  The register's contribution is to make the declaration *cheap and central* (one place lists every
  concept's source), so the human equivalence-judgment has somewhere to live and re-fragmentation of a
  known concept is structurally hard.
- **Stateful ⟹ liveness declared.** Any entry whose facets mark it stateful/in-flight MUST declare its
  **state machine + live owner** ("every in-flight record has a live owner" — 550 Thesis II). A stateful
  stream that omits its liveness owner is a build failure (the **F-1** fix). 550 designed this invariant
  *generally* but has **not shipped it for even one concept** — so this is genuinely-new work, not wiring
  up an existing mechanism. It is the completion 550 names as its principal unrealized work, generalized
  from "the lifecycle" to "any observed happening with state."

> **§4.4 (retracted by de-risk §9 U3).** An earlier draft added a fourth pillar — one shared per-genre
> projection engine (scrolling-feed / tabular / timeseries) — to resolve the Activity↔Logs duplication.
> It is **retracted**: 571 §9.B already adjudicated this and *deliberately declined* the shared
> filtered-event-stream projector as over-DRY (AHA), extracting only the genuinely-shared `newestFirst` +
> SSE primitive. Building it here would re-introduce the over-unification 571 rejected for sound reasons
> (the two feeds are distinct authorities with distinct reasons to change). 575 defers to that decision;
> the render genre is out of scope (§3).

## §5 The key design choice — family register vs per-domain (the honest fork)

550's current approach is **per-domain**: each concept earns its own register/gate (operation-surface,
execution-surface, …), and 550 §4 frames its four future applications as *separate* applications of the
pattern. 575 proposes instead **one family register**. The argument, stated plainly so the trade is
visible:

- **For the family register:** the per-domain approach governs only *shipped* concepts; it leaves the
  **meta-failure uncaught** — a *new* concept can fragment across stores, pick the wrong primitive, or
  skip liveness, and nothing watches until it is a bug (F-1/F-2 were found in production, not at build).
  One register catches misclassification + drift + missing-liveness for **all** streams, including future
  ones; and it makes the family *legible* (you can finally ask "what are all the observability streams,
  and what kind is each?").
- **The risk:** forcing a uniform abstraction over genuinely heterogeneous primitives (a metric histogram
  is not a log line is not an action event). **Mitigation — and the load-bearing design constraint:** the
  register governs **meta-properties only** (source-uniqueness, facet-classification, liveness). It never
  defines a common payload schema, never merges `Resource`/`Channel`/`Metric`, never touches the
  per-primitive models. It is a *classification + provenance + single-source* contract, not a unifying
  data type. With that constraint, the uniformity it imposes is exactly the part that *should* be uniform
  (every concept has one source; every stream is correctly typed), and nothing more.

**Recommendation:** the family register is the better long-term option — it is the only one of the two
that makes the defect class *not recur*, which is the stated goal. The per-domain approach is the
local optimum that produced F-1/F-2.

## §6 Where this sits — the spine at every tier

This doc completes a picture the codebase has been assembling spine-by-spine:

| Tier | Concept | The spine instance |
|---|---|---|
| Execution | "what the search pipeline did" | 553 — canonical SearchTrace + execution-surface gate |
| Lifecycle | "what an actor did / may do" | 550 — canonical LifecycleEvent + operation-surface gate (+ Thesis II liveness, unshipped) |
| **Observability authority** | **"what happened / what's true of the system" (the family)** | **575 — the observed-happening register (this doc)** |
| Surface | "where a stream is shown" | 571 — surface altitude/composition + surface-altitude gate |
| Telemetry | "what numbers changed" | ADR-0027 — MetricCatalog + MetricSurfaceContractTest |

575 is the **authority tier directly beneath 571's surface tier**: 571 governs *where* Logs/Activity/Health
live; 575 governs *what they are as data* and *that each is single-sourced and correctly typed*. They are
two applications of one spine, sharing the 530/531 gate kernel.

## §7 Scope, honesty, and the highest-value increment

- **Honest cost (corrected by de-risk §9).** Most of this **already exists** (generalization, not
  invention): the meta-principle (550/553), the facet vocabulary (Resource + DiagnosticChannel + ADR-0036),
  the gate kernel (530/531). The **genuinely new** pieces are: the **family-level register** (§4.1); the
  **facet→primitive derivation gate** (§4.2) — *plus its one prerequisite*, an `origin` discriminator on
  `Resource` so the Channel-vs-Resource axis can fire; and the **general liveness mechanism** (§4.3),
  which 550 designed but never shipped for even one concept. The render-genre pillar was **retracted**
  (571 §9.B AHA). And the whole thing is **power-bounded**: it forecloses re-entry within a *declared*
  concept, not the irreducible discovery problem (550 §5).
- **The highest-value single increment** — so this theory does not read as all-or-nothing — is finishing
  **550 Thesis II (the liveness invariant)**: it closes the live F-1 orphan-record bug and is the
  precondition for §4.3's family-wide foreclosure. The register (§4.1/§4.2) is the larger structural move
  and the one that reduces (within declared scope) recurrence for *future* concepts.

## §8 Out of scope (design-theory genre)

- The register's concrete schema, the exact facet vocabulary, migration of the ~12 existing streams,
  phasing, and feasibility — all slice concerns. This doc states the *structure*.
- **Do NOT re-open the Resource-vs-DiagnosticChannel split.** The research confirmed it is correct and
  load-bearing (ADR-0036 / C-012). The register sits *above* the primitives and governs the *choice*
  between them; it does not merge them. Any reading of §4 as "unify logs into Resources" is a
  misreading — the opposite is the point (classify, don't flatten).

## §9 Confidence ledger — de-risk pass (2026-06-10)

A measured de-risk read the load-bearing claims against shipped source *before* trusting them — the §8
discipline of the sibling docs, aimed at the subagent-sourced claims and one self-inconsistency. Where
this ledger corrects §1–§7, **the ledger wins**. Net: the **structure is feasible (HIGH)**; several
**power claims were overstated and are now bounded**; one pillar was **retracted**. The corrections were
folded inline above; this is the record.

- **U1 — the crux: "drift becomes unrepresentable" was an OVERCLAIM; corrected to 550 §5's own honesty.**
  A declared source-identity exists and is gate-enforced (`consumesProjection` / "semantic source",
  operation-surface Check 4). But 550 §5 is explicit: the gate is "structurally hard to re-enter *within
  its declared scope* (~100%); it does **not** prevent duplication in general … the discovery problem
  (knowing two things are one concept) … remains irreducible — detection-plus-review, reduction not
  prevention." §1/§4.3/§7 now claim exactly that, no more.
- **U2 — facet→primitive is VIABLE with a NAMED PREREQUISITE.** Strong precedent (`surface-altitude`
  derives a verdict from parsed catalog facets, tested by `CoreSurfaceAltitudeDerivationTest`). But
  `Resource` declares **no `origin` / recursion-risk**, so the headline Channel-vs-Resource case cannot
  fire today — it needs **one new declared facet** on `Resource` (named in §4.2). Shape mismatches
  (TABULAR-without-primaryKey, …) fire on existing facets.
- **U3 — §4.4 contradicted 571 §9.B (AHA); RETRACTED.** 571 deliberately declined the shared feed
  projector ("distinct authorities with distinct reasons to change"). The render-genre pillar is removed
  and §3's third face withdrawn; 575 defers to 571's adjudication.
- **U4 — Thesis II liveness: CONFIRMED unshipped; general design, no mechanism.** The invariant is
  formulated generally (mirrors 508 `AiStateStore`) but has not shipped for even one concept — so §4.3's
  family-wide lift is genuinely-new, and "finish 550 Thesis II" is honestly the highest-value increment.
- **U5 — F-1/F-2 CONFIRMED + status corrected.** Both verbatim-attributed to "the missing canonical
  record." **F-2 is *dissolved*** (Theses I+III shipped) — now cited as the *proven precedent*; **F-1 is
  the *open* case** (liveness). §3 corrected.
- **U6 — enumeration RESOLVED (reinforces feasibility).** `/api/registry/{resources,diagnostic-channels}`
  + catalog-root parsing already enumerate streams with declared facets; the register **auto-projects**
  coverage from the catalogs (the 530/553 property), not a hand-maintained allowlist.

**Confidence after the pass:** feasibility **HIGH** (precedent + enumeration + kernel all exist); the
design's *reach* is honestly bounded — it forecloses re-fragmentation/misclassification **within a
declared concept** and reduces (via detection+review) the irreducible discovery problem, needs **one new
`Resource` facet** for the Channel-vs-Resource gate, and carries **one genuinely-new mechanism** (general
liveness, which 550 itself never shipped). No implementation-mechanics surprise remains; the honest
residual is that the register *reduces and centralizes* the defect class — it does not make it vanish.

## §10 As-built — Phases 1 + 2 shipped (2026-06-10)

Implemented exactly to the de-risked design (Phases 1 + 2 of the approved plan; Phase 3 — complete Thesis
II liveness — deferred to 550's backlog, its user-visible F-1 symptom already fixed by 550 I+III).

- **Pillar 1 — the register (§4.1).** `governance/observed-happening.v1.json` declares the observability
  stream family as **concepts**, each naming one `canonicalSource` + its `contributors`. The genuinely
  multi-contributor concept (the action lifecycle — `core.action-ledger` canonical + `core.operation-history`
  + `core.indexing-jobs`, 550 Thesis I) is now gate-locked; the rest are 1:1.
- **The gate (`observed-happening`).** New discipline gate (`scripts/governance/gates/observed-happening/`
  + registered in `governance/registry.v1.json`), mirroring `surface-altitude`. It parses the real stream
  ids + origins from the catalog **source** (`*ResourceCatalog.java` / `*DiagnosticChannelCatalog.java` —
  coverage projects from the catalogs, the 530/553 property; reads `ResourceRef` literals so helper-built
  catalogs like `AdvisoryResourceCatalog` resolve) and forecloses four rules: **contributor-unresolved**,
  **concept-canonical-source**, **contributor-shared** (the F-2 re-fragmentation class), and **Pillar 2's
  operator-trace-must-be-channel**. Bounded per §9 — within a *declared* concept.
- **Pillar 2 — the `Resource.origin` facet (§4.2).** `Resource` gained `Optional<ProducerKind> origin`
  (mirroring `withRole` via a `withOrigin(...)` builder; defaulted empty; back-compat constructors
  preserved). It is **`@JsonIgnore`'d** — a source-only governance facet with no runtime consumer, kept off
  the `/api/registry/resources` wire (so the `UIResourceView` conformance is untouched). The gate's
  `operator-trace-must-be-channel` rule fails any Resource that declares an origin (operator-trace data
  belongs on a `DiagnosticChannel`, ADR-0036). The negative fixture exercises it; the real catalog (no
  Resource declares an origin) passes.
- **Verification.** Gate real-repo pass + `--self-test` (positive passes; negative fails all four rules);
  `./gradlew.bat build -x test` green (the gate runs in the discipline suite); `:modules:app-agent-api:test`
  + `:modules:app-services:test` green. No user-visible surface changed (build-time governance + an
  off-wire record facet), so the live-browser pass is a no-regression smoke.

## §11 As-built — the remaining design completed (2026-06-10)

A critical re-read found three design elements thin/missing; all are now built (the build-time pieces +
the runtime liveness mechanism). Phase 3 (the cross-process drain-state) stays scoped out as before.

- **A — KIND↔primitive consistency (§4.2).** The gate gained `kind-mismatch`: a concept's `kind` must
  match its canonicalSource's primitive class (channel ⟺ diagnostic-channel; Resource ⟺ a Resource-shape
  kind) — the register's previously-inert `kind` is now validated. The full "derive kind from facets"
  engine is **deliberately not built** (it would duplicate `ResourceAreaValidator`'s shape checks or
  encode the render-genre vocabulary 571 §9.B / 575 §3 declined).
- **B — governed projections (§4.1, Pillar 1's third element).** Each concept may declare `projections`
  (the read-view surface mount tags); `projection-unresolved` fails a projection not resolving to a real
  `jf-*` mount tag in the surface catalog. Optional-but-validated (the discovery-problem bound). Reuses
  the operation-surface lineage idea.
- **C-i — the liveness DECLARATION (§4.3b).** Each concept declares `stateful` + (when stateful) a
  `livenessOwner`; `stateful-requires-liveness` fails a stateful concept missing it. The
  action-lifecycle concept names the job state machine + reaper + (now) the heartbeat reconciliation.
- **C-ii — the liveness MECHANISM (550 Thesis II, the one user-visible piece).** The worker indexing
  loop now **heartbeats** its in-flight rows (`JobQueue.heartbeatProcessing` → `SqliteJobQueue`, called at
  batch phase boundaries + time-gated in the write loop), so `last_updated` is a true LIVENESS signal —
  "in-flight derives from a live owner, not stream membership." The age-bounded reaper's window tightens
  15 min → 5 min (safe: a live loop never stops beating mid-batch; re-index is idempotent). Bounded
  realization, not a cross-process gRPC mechanism: the F-1 visible flood was already mitigated (550 I+III),
  so this closes the structural gap (orphans reclaim in ~5–7 min, not ~15–20) without touching the worker
  hot path beyond cheap phase-boundary beats.
- **Verification.** All seven gate rules fire on the negative fixture; gate real-repo + self-test pass;
  `JobQueueTest` proves a heartbeated row is spared and an un-beaten (orphaned) row is reclaimed;
  `build -x test` green (class-size growth of `SqliteJobQueue` declared via a `declared-growth` changeset);
  worker-services + indexer-worker tests green. C-ii is user-visible → **happy-path** live-validated
  (indexing drains to IDLE; no phantom RUNNING badges; `/api/status` agrees). The **crash-recovery** path is
  unit-proven (`JobQueueTest`) + production-topology-reasoned but **not dev-live-provable** — the dev stack
  disables the head's `WorkerSpawner` (the worker is dev-runner-managed), so a hard-killed worker can't be
  restarted to exercise boot-recovery. A 2026-06-11 live pass corrected this overstated claim — see §12.

## §12 Live-validation / de-risk pass (2026-06-11)

A post-merge confidence pass ran the build-time findings against source and the runtime claim against a live
dev stack (the gfmg binary, ~2400 docs). Where it corrects §10/§11, **this section wins** (the §9 discipline).
Net: **no defect found**; three claims are now *bounded* instead of overstated.

- **L1 — register coverage is FORWARD-only (the completeness bound made explicit).** The gate validates every
  *declared* concept (contributors resolve, source-is-a-contributor, no shared contributor, kind / projection
  / liveness). It does **not** enforce *reverse* coverage — that every Resource / DiagnosticChannel id in the
  catalogs is declared as a concept. The catalog universe is **16 streams**; the register declares **12**. The
  4 undeclared split: `core.runtime-context` + `core.inference-runtime` are clean in-family STATE concepts;
  `core.indexed-roots` (library config) + `core.server-capabilities` (an `/infra` capability handshake) sit on
  the "observed-happening vs config/capability" boundary. So a reverse-coverage rule is **not a pure mechanical
  close** — it needs a declared *out-of-family exclusion list*, which **is** the irreducible discovery-judgment
  (§1 honesty bound). Forward-only is the honest reach today; closing it is a judgment-bearing follow-up, not a
  one-line rule.
- **L2 — Pillar 2 teeth, located precisely.** The headline **channel-vs-Resource** axis *is* build-enforced
  against the **real** catalogs (the gate parses catalog source: `operator-trace-must-be-channel` +
  `kind-mismatch`). The **finer shape rules** (Category×SubscriptionMode, HistoryPolicy-required-when,
  TABULAR-primaryKey) live in `ResourceAreaValidator`, which has **no `src/main` caller and no conformance test
  over the real catalogs** — they are *fixture-tested only*. §11.A's "would duplicate `ResourceAreaValidator`"
  is accurate that the rules *exist*, but they don't currently bite production. (Pre-existing infra gap; logged
  to `docs/observations.md`, not a 575 deliverable.)
- **L3 — C-ii live status corrected (the load-bearing one).** **Confirmed live:** jobs drive
  PENDING→PROCESSING→DONE and drain to **zero stuck rows** (orphan-free happy path); during an active window
  all PROCESSING rows carry an identical ~200 ms-fresh `last_updated` (the bulk `UPDATE … WHERE
  state='PROCESSING'` signature — the heartbeat is present in the running binary). **NOT live-confirmable in
  dev:** (a) the distinct 30 s heartbeat *refresh* — source files extract too fast for any row to stay
  PROCESSING ≥30 s, so its slow-batch value stays unit-proven; (b) **crash-recovery** — a hard kill *did* leave
  **32 orphaned PROCESSING rows**, but the dev head does not supervise the worker (`/api/worker/restart` →
  `WorkerSpawner not running`), so the crashed worker never restarts and the **in-worker reaper cannot run**.
  This is a **dev-harness topology artifact** (production runs `WorkerSpawner`), not a defect. Notably, F-1's
  **phantom-RUNNING-badge symptom does not manifest in a crash**: a dead worker makes `/api/status` report
  `DEGRADED` with no job data (it flows *through* the worker), so the FE shows degraded — **not** phantom
  badges. F-1 needs a *live* worker reporting *stale* rows — exactly the within-process loop-death case
  `JobQueueTest` covers. **Honest standing:** happy-path live; crash-recovery unit-proven + topology-reasoned;
  slow-batch heartbeat unit-proven.

## §13 De-risk pass #2 — residual assumptions closed (2026-06-11)

A second read-only pass verified the three assumptions §12 rested on but hadn't proven. Two are now closed and
one is deepened. **No defect; no code changed.**

- **L3 — UPGRADED to code-confirmed (was "topology-reasoned").** `WorkerSpawner.checkWorkerHealth()`
  (`app-services/.../worker/WorkerSpawner.java`) is a `scheduleWithFixedDelay` monitor that detects worker
  death (`!p.isAlive()`) and **auto-restarts with exponential backoff** (1s→2s→4s, cap 30s) up to
  `MAX_RESTART_ATTEMPTS = 3`; the respawned worker runs the unconditional boot `recoverStuckJobs()`
  (`KnowledgeServer.java:396`) → orphans reclaimed. So **production crash-recovery is real for the transient
  case**. Newly-explicit bound: a *crash-loop* (>3 deaths inside the stability window) hits
  `"restart limit exceeded, giving up"` → `running=false` → orphans stay stuck + reaper dead. That is exactly
  the dev-wedge I induced with 4 rapid kills, and it is a **deliberate anti-crash-loop bound, not a defect**.
- **L2 — CLOSED.** A test-side sweep confirms the only references to `ResourceAreaValidator` /
  `DiagnosticChannelAreaValidator` are their own fixture tests + `KindRendererCrossRefValidatorTest` +
  `HealthResourceCatalogRetrofitTest` (which exists for what the validator *can't* check). **No test runs them
  over the real aggregate catalog.** Confirmed narrow real gap; the honest fix is a one-line conformance test
  mirroring how `SurfaceAreaValidator` is build-enforced. (Pre-existing infra; logged to observations.)
- **L1 — DEEPENED (the universe is structurally bigger than 16).** The §12 "16 streams" counted only the
  Resource/DiagnosticChannel catalogs. The tempdoc's family is broader — §1 names "a boot phase" — and the
  codebase has "what happened / what's true" streams that are **not Resources at all**: **boot trace /
  rebuild history** (`RebuildHistory.java` + `BootTrace`/`BootRoutes`), **scan progress**
  (`ScanProgressRegistry.java`), and the **search-execution trace** (`SearchTrace.java`, 553 — which has its
  *own* spine). So the register today governs only the *Resource/Channel-backed subset*. The completeness
  **scoping fork** is therefore the real open design question: a reverse-coverage rule over just the
  Resource/Channel catalogs is mechanical but **still misses boot/scan/search-trace**; covering the whole
  family needs **cross-spine references** (defer search-trace to 553) **plus** the out-of-family exclusion
  judgment — a larger, judgment-bearing follow-up, not a mechanical rule. This is the one residual whose
  closure is a design decision, not a verification.

**Confidence after pass #2 (0–10):** shipped work 9 · L3 9 · L2 9 · L1 (characterised; close-path is a
judgment) 7 · overall "no surprise invalidates shipped work" 9.

## §14 As-built — the remaining implementation shipped (2026-06-11)

The §13 follow-ups were then built (the Resource/Channel-decidable parts; the non-Resource extension stays
deferred to the streams' own spines by design).

- **L1 — reverse-coverage SHIPPED (Resource/Channel universe).** The `observed-happening` enforcer now keys
  off `implements ResourceCatalog` / `DiagnosticChannelCatalog` (not the `*ResourceCatalog.java` filename —
  which had silently missed `ConditionRecoveryIndexCatalog`). A new `stream-uncovered` rule fails the build
  if any catalog stream is neither a contributor of some concept nor in the register's new `outOfFamily`
  array. The 5 previously-uncovered streams are declared: `runtime-context` / `inference-runtime` /
  `condition-recovery-index` / `indexed-roots` in-family; `server-capabilities` out-of-family (the
  `/infra/capabilities` handshake, not a happening). **§5's meta-failure — a NEW stream escaping the
  register — is now a build failure** for the Resource/Channel-backed family. Bite-proven (a bogus undeclared
  stream fails the gate).
- **L2 — shape teeth SHIPPED.** `CoreRegistryShapeConformanceTest` runs `ResourceAreaValidator` +
  `DiagnosticChannelAreaValidator` over the real core catalogs (the `SubstrateGraphAssembler` set), so the
  §4.2 finer shape rules now bite production (bite-proven: an `EVENT_STREAM`-without-HistoryPolicy fails the
  test). Closes the §13 L2 gap.
- **Liveness derivation SHIPPED — the Pillar-3b / §13 L3 completion at the projection tier (FE-only).**
  Investigation found the C-ii heartbeat **already propagates to the FE**: `SqliteJobQueue`'s SQLite
  update/commit-hook (`IndexingJobsChangeStream`) emits a full-row `Delta.Update` on *every* committed change
  — including `heartbeatProcessing()`'s `UPDATE jobs SET last_updated=…` — and `lastUpdatedMs` survives every
  hop (`JobRow` → proto `last_updated_ms` → `RemoteIndexingJobsBridge.toView` → `IndexingJobView` → SSE JSON →
  the FE raw frame). So **§11's "no cross-process gRPC mechanism" bound is HONORED, not reversed** — the
  completion is FE-only. `indexingJobsBridge.statusFor` now returns `running` for a PROCESSING job **only when
  its heartbeat is fresh** (`Date.now() − lastUpdatedMs < 90 s`, 3× the worker's 30 s interval); a stale row
  (a wedged loop in a *live* worker) demotes to `queued` — **"in-flight derives from a live owner, not stream
  membership."** A ~15 s re-eval tick re-derives staleness from the last known rows so a job that *stops*
  getting frames demotes without a new frame. **This supersedes §13 L3's "time-proxy only" at the DISPLAY
  tier**: the badge now demotes within ~tick+window, not the worker reaper's 5 min (the reaper stays the
  worker-side queue backstop).
- **Verification.** `./gradlew.bat build -x test` green; the `observed-happening` self-test (8 rules) +
  real-repo pass; `CoreRegistryShapeConformanceTest` green + bite-proven; `cd modules/ui-web && npm run
  typecheck` clean + `test:unit:run` 2684 green incl. the new liveness tests (fresh→running, stale→queued,
  tick-demotion). The liveness piece is user-visible → **live-browser validated** (2026-06-11, the worktree
  FE served on :5199 against a live backend): with a clean queue + a moderate ingest, the Tasks tray rendered
  **"16 RUNNING · 382 QUEUED"** with teal RUNNING badges for the actively-processing jobs (fresh heartbeat →
  RUNNING) while the PENDING backlog stayed QUEUED; then with the dev override `__JF_STALE_MS__=1`, **128 jobs
  PROCESSING on the backend** (`/api/status processingJobsCount`) rendered as **QUEUED with no RUNNING pill** —
  the same processing state demotes when the heartbeat is treated as stale. The same-state-opposite-badge
  contrast is the headline derivation shown live. A genuine wedged-loop is not externally inducible (same WS3
  limit), so the dev-override is the honest live exercise of the stale path; the tick re-derivation is
  deterministically unit-tested.

## §15 As-built — Pillar 3b lifted to the "unrepresentable + gate" bar (2026-06-11)

§14 shipped the liveness *mechanism* + one adoption, but a critical re-read found it short of §1's thesis
("the breaking form made *unrepresentable* → a gate"): the derivation was inline in one bridge, the window
was scattered magic numbers, and there was **no gate** making a phantom-running projection a build failure —
the "adoption ≠ unrepresentable" gap (574 §22.C / the same class L1/L2 closed for the other pillars). Pillar 3b
was enforced strictly weaker than 1/2/3a. Now closed (scoped to the indexing-job liveness; the cross-domain
"every running badge derives from a live owner" — agent/brain/install, each with its own liveness — is 559/565,
deferred):

- **One FE liveness authority.** The freshness derivation is now `inFlightLiveness.ts`
  (`isInFlightLive` + `IN_FLIGHT_STALE_MS` + the dev override); `indexingJobsBridge.statusFor` consumes it —
  no behavior change (pinned by the existing bridge tests + a focused authority test). Kills re-implementation drift.
- **One liveness window + a coherence gate (~100% teeth).** The window is declared **once** — the
  action-lifecycle concept's `liveness` block (`heartbeatMs:30000 / displayStaleMs:90000 / reaperStaleMs:300000`,
  with `sources` pointing at each real constant). The `observed-happening/liveness-window-coherent` rule parses
  the actual worker (`IndexingLoop.HEARTBEAT_INTERVAL_MS`, `KnowledgeServer.STALE_PROCESSING_MS`) + FE
  (`IN_FLIGHT_STALE_MS`) constants and **fails the build** if any drifts from the declared value or breaks the
  ordering invariant (`heartbeat < displayStale ≤ reaper`; `displayStale ≥ 3×heartbeat`). Bite-proven (heartbeat
  30k→31k fails). This is the genuine single-source the scattered constants lacked.
- **In-flight projection register + gate (early-warning, the FE ceiling).** `governance/inflight-liveness-projections.v1.json`
  + `scripts/ci/check-inflight-liveness.mjs` (mirroring `check-run-renderers`): registered in-flight render sites
  must import the authority; an unregistered `'PROCESSING'→'running'` mapping is flagged. Bite-proven (a phantom
  site is caught). Honest scope (565 §12.10): import-visible early-warning — a computed/different-literal re-model
  slips, the same ceiling as the sibling FE anti-drift gates. Wired into `ci.yml` + the CLAUDE.md pre-merge list.

**Honest bars.** The window-coherence is ~100% (it parses real constants); the projection gate is the FE
import-register early-warning bar — the realistic ceiling for FE rendering (the FE tier does not support the
100% backend-gate bar). So Pillar 3b now reaches the same bar as the sibling FE anti-drift gates and adds the
~100% window teeth — the breaking form (phantom-running) is foreclosed at the bar this tier admits. The
cross-domain generalization and the data-tier reaper-as-conservative-backstop stay deliberately out of scope.

## §16 Indirect-changes ledger — the ripples of implementing 575 (2026-06-11)

§13–§15 are implemented + merged to `main`. A post-implementation pass identified the *indirect* changes the
work implies, tiered by ownership + warrant (verified facts inline). **Only Tier 1 is in-scope warranted
follow-up; the rest are deferred / others' tier — recorded so they don't become silent drift.**

- **Tier 1 — direct consequences of THIS work (warranted):**
  - **Canonical-doc drift.** Verified: **no** `docs/explanation/` or `docs/reference/` doc mentions the
    observed-happening register/gate. 575 shipped a new family-level governance tier (the register, the §6
    spine-per-tier picture, the liveness governance) that the canonical docs don't reflect — the
    "canonical docs must not drift" rule. `docs/explanation/08-observability.md` is the home; needs the
    register section + the §15 liveness window/gate, then the `llms.txt` + skills-sync regen.
  - **Dev-override prod-hardening.** Verified: `inFlightLiveness.ts` reads `globalThis.__JF_STALE_MS__` in
    *all* builds (not dev-guarded) — a stray-global escape hatch in production. Guard the read behind
    `import.meta.env.DEV` (the override is only ever used in dev/live-verification).
- **Tier 2 — generalizations 575 DEMONSTRATES but does not own (559/565 presentation tier):** the
  "RUNNING derives from a live owner" derivation + gate generalize to the other `running` badges (agent
  runs, brain install/pack/runtime — each with its own liveness, none gated); and the §15
  "declare-the-window-once + coherence-gate" discipline generalizes to other scattered staleness windows
  (capability / translator-handshake / inference-poll). Flagged, not claimed.
- **Tier 3 — coordination / future:** when `558-presentation-pairs` lands (verified: its
  `advisory-classes`/`action-event-surfaces` registers are NOT yet on `main`) it will dual-enumerate
  advisories against this register's advisory concepts → the L1 cross-spine reference deferred in §13.
  The non-Resource family (boot/scan/search-trace) stays §6-deferred to its own spines.
- **Tier 4 — hygiene (small, optional):** a focused unit test for the enforcer's law-bearing
  `parseLongExpr` / coherence arithmetic (bite-proven, not unit-pinned); and completing the register's
  `projections` for the advisory/metric concepts that lack them (a fuller §4.1, not a correctness gap).

The honest verdict: the FEATURE is complete; the two Tier-1 items (doc-drift + dev-guard) are now **DONE +
MERGED to main (2026-06-11)** — the dev-override read is gated on `import.meta.env.DEV` (prod tree-shakes it;
the Vite dev server used for live verification retains it), and `docs/explanation/08-observability.md` carries
the observed-happening-register section. The Tier-2/3/4 items remain deferred-by-design or others'-tier — not
575 scope creep.

**Post-merge hygiene note (2026-06-11):** a sweep after the Tier-1 merge confirmed the `observed-happening`
gate is enforced the same way its sibling register-gates are (surface-altitude / execution-surface) — via the
CLAUDE.md manual pre-merge bullets under ADR-0026 manual-only CI, not an individual ci.yml step; this is the
established pattern, not a wiring gap. One unrelated, pre-existing item stays the bundle owner's call: the
`ui-bundle` hard-cap is RED on `main` (built `index.js` > 1,020,000 B), which `docs/observations.md` attributes
to cumulative 565/575 *feature* FE growth — the Tier-1 dev-guard (a conditional wrap) does not materially move
it. Logged, not actioned here (architectural resolution: lazy-split, deliberate cap-raise, or emergency-override).

## §17 The correct long-term design — completing the spine to its *generative + projective* form (design theory, 2026-06-11)

**Genre: design theory (per 557/559/567), not a schedule.** This section states the *correct long-term structure* for
the ideas/remaining work the register opens — not a ranked idea menu (that was the prior draft, superseded here). It is
grounded in one round of external research (telemetry-schema codegen, catalog-driven UI, liveness/failure-detection,
self-observability UX) **and** an investigation of what the codebase already runs, so the design *extends* proven
internal authorities rather than inventing parallel ones. Feasibility/phasing are deliberately disregarded; major
refactors are in scope where they are the better long-term option. Nothing is scheduled; the app is pre-production.

### §17.1 Diagnosis — 575 sits one tier *below* the spine the codebase already runs elsewhere

575's thesis (§1) is the projection spine: *one authority → typed declaration → governed projection → a coverage gate.*
But the **shipped** register realizes only the spine's **detection** tier: it *declares* (the `liveness` window, the
KINDs, the projections) and a gate *detects* drift after the fact — the worker/FE constants are still hand-authored in
three files (`IndexingLoop.HEARTBEAT_INTERVAL_MS`, `KnowledgeServer.STALE_PROCESSING_MS`, `inFlightLiveness.ts
IN_FLIGHT_STALE_MS`) and the gate asserts they *match*. The codebase already runs the spine at its **stronger tier**
elsewhere: tempdoc **564** (wire-contract projection) made a record the one source, *generated* every representation
(`SSOT/schemas/*.v1.json` → `scripts/codegen/gen-wire-schema-types.mjs` → TS type + Zod), and foreclosed the second copy
by *mandate* (`check-wire-type-single-authority`) + *idempotency* (`check-wire-schema-types-regen`). That is **Collapse →
Generate → Gate**, where the breaking form is *unrepresentable*, not merely caught. 575's liveness window is the exact
shape 564 rejected as too weak: "declare + detect" instead of "generate, second copy cannot exist."

### §17.2 The thesis — the register becomes a *generative + projective authority*, and gains the one facet it lacks

The correct long-term form of the observed-happening register is to complete it to the spine's strong tier in **both**
directions, and to give it the **one missing dimension** it needs to be a true observability authority:

> **The register is the single source from which the machine-facing representations are GENERATED (constants, kind/
> source types, the human map, the runtime self-description), from which the human-facing "what is the system doing
> right now?" surface is PROJECTED, and on which each stateful concept declares its LIVENESS MODEL — so that "running"
> is *derived from a declared, cited owner*, never asserted ad hoc.**

Three faces of one structure follow. None is a new framework; each *extends* an authority the codebase already runs.

### §17.3 Face A — the machine projection: *generate*, don't gate-check (extend the 564 spine)

The liveness window, the stream-KIND/source unions, and the human map should be **generated projections of the
register**, exactly as the wire types are generated projections of the schema. Conceptually:

- The register is the source. A generator emits the worker constant, the FE constant, the KIND/source TS unions, and the
  observability-map doc as **checked-in generated artifacts**. The coherence/ordering law (`heartbeat < lease ≤ reaper`,
  `lease ≥ 3×heartbeat`) is validated *once*, at generation, not re-derived by a drift parser.
- The gate degenerates to the **564 pair**: an *idempotency* check (regenerate → no diff) and a *single-authority
  mandate* (a hand-authored copy of a generated constant is unrepresentable). The bespoke "parse the constant out of
  three source files and compare" enforcer (`observed-happening/liveness-window-coherent`) is *replaced* by this
  stronger, cheaper pair — drift becomes impossible by construction.
- **The one genuinely-new infrastructure piece** (named honestly): today generation is *TS-only*, from a Java/JSON
  source; the liveness window must generate **into both Java and TypeScript from one JSON register**. This is a small,
  well-precedented extension of `scripts/codegen/` (the OTel **Weaver** model: one registry → language-idiomatic
  constants across many SDKs), not a new system. The structural payoff is the point: the worker and the UI can no longer
  hold different ideas of "fresh," because neither *holds* the number — both *are* the register.
- **De-risked (2026-06-11):** the generated Java constant's home is **`worker-core`** — both `worker-services`
  (`IndexingLoop`) and `indexer-worker` (`KnowledgeServer`) already depend on it, so **no new module and no
  module-dependency-governance risk** (the worry that a new shared constants module would trip the `module-deps` gate is
  eliminated). The 564 *idempotency* check is reused directly; the only novel enforcement is a tiny "no hand-authored
  copy of a generated **constant**" mandate (the 564 mandate scans for hand-authored *types*, not values) — a ~50-line
  parallel gate. Feasibility for this face is **high**.

### §17.4 Face B — the human projection: the **System Self-View** as a projection authority (extend 571, compose 559/565/557)

The register is the *only* artifact in the codebase that can answer "what is the system doing right now?" — it is the
set of concepts that are `stateful` with a live owner. The correct structure is a single **System Self-View**: not a new
panel bolted beside the others, but a **projection authority** in the §1 sense — *authority* = the register; *declaration*
= each concept's source/kind/altitude/liveness; *projection* = the one surface; *gate* = reverse-coverage (every stateful
concept is rendered by the Self-View or a declared surface — an unprojected live concept fails the build, the 575 §13 L1
pattern turned on presentation). It **composes existing authorities, inventing none**:

> **De-risked scope correction (2026-06-11) — the premise is thinner than the framing above suggests, and that bounds
> the face.** *Today only **one** register concept is `stateful: true`* (`action-lifecycle`, which carries indexing
> jobs); the other genuinely-live things — agent runs, brain install/pack, scan progress — are **explicitly out-of-family**
> (separate spines, §6/§13). So the "register answers what's-happening-now" query resolves to ~one concept *as the
> register stands*. The honest near-term form is therefore **a glanceable pane that composes the existing live surfaces**
> (indexing jobs + whatever else is registered), *not* a single omniscient feed of all live work. Broadening it to the
> full live set is a deliberate **scope decision** — either expand the register's `stateful` set (register agent-runs /
> install / scan as in-family concepts) or have the Self-View compose across spines at the presentation boundary. That
> choice is the gating design question for this face, not an implementation detail.

- **Ordering** from the **571 altitude axis** (`surface-altitude.v1.json` + `CoreSurfaceCatalog`): TRUST (approvals)
  → DIAGNOSTIC (health) → PRODUCT → TOOL, *derived*, not a hand-tuned priority list.
- **Status→tone** from `statusTone.ts` (565), **labels/icons** from `present()` (557), **evidence/provenance** from
  `evidenceProjection` (565), **multi-zone frame** from `composeGridStyles` (559 §13), **overflow/pinning** from the
  `OverflowController` adaptive primitive (559 Authority VI), **idle/stale** from the `ConnectionPhase` authority (557),
  the **history ring** from the `unifiedThreadProjection` time-ordered replay (565 §12.3).
- It adds the one thing no IDE/status bar has: a **confident idle state** ("system idle", with per-concept last-checked
  timestamps) — a positive signal, not a blank panel (the GitHub-Desktop "last fetched" pattern; the SquaredUp "first
  pane of glass" discipline; ≤5 primaries, no wall-of-green-LEDs).

This is the **completion of the projection spine one tier down from surfaces (571) to the observability data (575)** that
§6 already names as the family-level sibling — now made concrete as a surface. Long-term it **supersedes** the present
fragmentation (Health / Activity / Log / Brain surfaces + StatusDeck + the Task tray each rendering a slice with its own
staleness model and its own silence-when-idle); that consolidation is a major-but-correct refactor the spine licenses,
not scope to avoid because it is large. A *living observability map* doc (Mermaid `source → concept → projection`,
generated under Face A and indexed by `llmstxt-generate`) is the same projection rendered for docs/onboarding instead of
runtime — zero-staleness by construction (EventCatalog `<NodeGraph/>` / Backstage precedent).

**Hard constraint from 571 (verified 2026-06-11) — compose, do not fuse.** 571 §9.D *deliberately declined* a shared
Activity/Logs feed projector (reasoned AHA: distinct authorities, distinct reasons to change — Logs has severity filters
+ pause/resume + virtualization; Activity has burst-collapse + a transparent lifecycle), and gate-forbids two authorities
behind one surface (the `altitude-conflict` rule). The Self-View must therefore **compose each surface's already-projected
output independently** (a read-only cross-cutting *status aggregate*) and must **never** build a unified event-stream
projector that forces Activity and Logs into one filter/pause/collapse model — that is the over-unification 571 already
rejected. The Self-View is "one glanceable pane *referencing* N authorities," not "one authority *replacing* N surfaces";
keeping that distinction is what makes it compatible with 571 rather than a reopening of a closed decision. (All eight
composed authorities above were verified to exist as reusable primitives in the same pass.)

### §17.5 Face C — liveness as a *declared model*, governed; **not** a single code path (the corrected design)

The prior draft proposed "one `LivenessPolicy {heartbeat, lease, reaper}` consumed by every in-flight badge." The
investigation shows that is **wrong** — and naming why is the load-bearing part of this design. The three in-flight
things use *fundamentally different ownership models*:

| In-flight concept | Liveness model | Owner | "Is it stuck?" |
|---|---|---|---|
| Indexing jobs (`isInFlightLive`) | **heartbeat-lease** (`last_updated` renewed every 30 s; FE freshness window; worker reaper) | worker process | yes — reaper + FE demotion |
| Agent operation runs (`OperationClient`) | **promise-bounded** (HTTP request in flight = running; response = terminal) | Head process | n/a — a dropped connection *is* the terminal signal |
| Brain install/pack/runtime (`BrainSurface`) | **polled backend-state** (`state==='running'` from the last poll) | backend service | **no backstop today** |

A single code abstraction would force spurious heartbeats onto a promise and a spurious reaper onto a poll — and the
**AHA guardrail (550)** forbids unifying things that do not share a *reason to change*: the indexing retry policy, the
HTTP timeout policy, and the install state machine evolve independently. So the correct unification is at the
**governance tier, not the code tier**:

- The register gains a **`liveness.model`** facet per stateful concept — `heartbeat-lease | promise-bounded |
  polled-state` — naming its owner and its *proof of aliveness*. Liveness becomes **declared**, the way KIND already is.
  **Prerequisite found in de-risking (2026-06-11):** the facet can only attach to *registered* concepts. Agent
  operations (`core.operation-history`) and brain runtime (`core.inference-runtime`) **are** already registered Resources
  → the facet attaches directly. **Brain install + pack import are NOT registered** as Resources today → Face C must
  first register them (new catalogs, in-family `stateful` concepts) before it can declare their liveness model. So the
  governance work has a concrete ordering: *register the missing in-flight concepts, then declare each one's model.*
- The gate (extend **550 Thesis II/III** + the shipped `check-inflight-liveness`) requires every surface that renders a
  "running"/in-progress state to **cite the liveness authority** for that concept. A "running" derivation that cites no
  declared owner is the failure — exactly 575's "derived from a live owner, not asserted by stream membership", lifted
  from one concept to the family. The invariant that unifies is *"every in-flight thing has one auditable owner; every
  surface cites that owner's proof"* — a shared discipline, not a shared protocol.
- This surfaces a **real correctness gap**, not mere hygiene: brain install/pack has **no backstop** — if the backend
  wedges in `state==='running'`, the FE polls "running" forever. **Confirmed in code (2026-06-11):** `BrainSurface`
  polls every 1 s and trusts the last `state` indefinitely, and the backend `AiInstallService` holds a `running` flag
  with **no reaper / timeout / timestamp** (no analogue of the worker's `recoverStuckJobs`). The correct long-term fix is
  a **backend-side terminal timeout / reaper or explicit cancellation** (the owner certifies its own death), *not* an FE
  freshness hack bolted on the poll — and it is **cross-tier**: the backend must add a `lastUpdatedAtMs` + reaper, the FE
  then derives staleness, and the concept must be registered (above). It is **not** FE-fixable alone. Declaring its
  `liveness.model: polled-state` is what makes the missing backstop *visible* as a gap.
- The display refinements belong to the **heartbeat-lease model specifically** (they are correct there, not universal):
  rename the window to **lease** (etcd/Chubby vocabulary; "lease = 3×renewal, reaper = k×lease" as named ratios), and add
  a continuous **lease-fraction** signal (a depleting ring + "last contact Xs ago") so a job that stopped beating stops
  looking *confidently* fine before the cliff — the continuous-suspicion idea from phi-accrual, realized purely in the
  display layer with no state-machine change.

### §17.6 What this resolves, and the smaller structural pieces

- **The §16 Tier-2/3/4 deferrals fold into the three faces.** Tier-2 ("uniform liveness") is *corrected* by Face C
  (declared-model governance, not one policy). Tier-3 (the **558** dual-enumeration) becomes a **register-to-register
  projection**: 575's advisory concepts and 558's `advisory-classes` register cross-reference under the *same* spine
  applied *between two registers* (each remains its own authority; a gate checks they enumerate the same advisories).
  Tier-4 ("complete the `projections`") is *subsumed* by Face B's reverse-coverage gate.
- **Two register-facet additions** make it a fuller authority (Chronosphere/Weaver convention): a one-sentence
  `description` and a `stability` (`experimental | stable`) per concept; the gate can then warn when a projection consumes
  an `experimental` stream as if stable, and validate every `canonicalSource` resolves to a real source file.
- **Runtime self-description** (`/api/debug/state` exposes the parsed register) closes the loop — the running app can
  validate its own observability against its declared model ("verify, don't guess" applied to self-observability; the
  Weaver `live-check` analogue).

### §17.7 Considered and *rejected* (so they are not re-explored)

- **Phi-accrual / adaptive failure detection for the window — overkill.** Phi-accrual earns its keep against *network
  jitter* across a cluster; the worker and UI share **one machine over loopback**, so there is no variance to calibrate
  to. The fixed "lease = 3×heartbeat with margin" is the correct long-term model; the gains are *vocabulary* and
  *display*, not a probabilistic detector.
- **A single `LivenessPolicy` code abstraction over all in-flight types — over-unification (AHA violation).** Rejected
  in §17.5: it would force false owners (heartbeats on promises, reapers on polls) onto models that do not share a reason
  to change. The unification is the *declared-model + cite-the-owner governance*, not one code path.

### §17.8 External precedent index (checkable anchors)

| Design face | Primary external anchor | Internal authority it extends |
|---|---|---|
| Face A — generate from the register | OpenTelemetry **Weaver** (`registry generate`); OTEP-0243 resolved telemetry schema | 564 wire-schema spine (`gen-wire-schema-types.mjs` + the mandate/idempotency gate pair) |
| Face B — System Self-View / map | **EventCatalog** `<NodeGraph/>`; **Backstage** system model; **JetBrains**/**VS Code** task UIs; **SquaredUp** "single pane" | 571 altitude axis; 559/565/557 presentation authorities (`statusTone`/`present`/`composeGridStyles`/`OverflowController`/`ConnectionPhase`/`unifiedThreadProjection`) |
| Face C — declared liveness model | **etcd/Chubby** leases; **phi-accrual** (continuous suspicion); **Carbon** status vocabulary | 550 Thesis II/III liveness invariant; the shipped `check-inflight-liveness` gate |

### §17.9 De-risking pass + readiness (2026-06-11)

A read-only investigation (three Explore sweeps) tested the design's load-bearing assumptions *before* any
implementation, to surface scoping surprises early. Outcome, per face (the corrections are already folded into §17.3–§17.5
above):

- **Face A — confidence ≈ 8.5 (high).** De-risked to a clean extension: home = `worker-core` (no new module, no
  module-dep-governance risk); the only novel enforcement is a ~50-line constant-mandate gate; the rest reuses the 564
  idempotency pattern.
- **Face C — confidence ≈ 7.** Design *validated*: the three liveness models are genuinely distinct (AHA correction
  confirmed), and the brain-install no-backstop gap is **real, in code**. Residual: the fix is cross-tier and requires
  registering install/pack as concepts first — a concrete, ordered piece of work, not a blocker.
- **Face B — confidence ≈ 5.5 (the main residual).** The building blocks all exist, but the *premise* was thinner than
  the original framing (one stateful concept today, not many) and the face must thread the 571 §9.D "compose, do not
  fuse" needle. This is a **scope/boundary design decision**, now made explicit — not a hidden surprise.

**Overall ≈ 7/10** for the remaining work: no hard blockers; the residual is concentrated in Face B's scope choice. The
honest sequencing this implies — *Face A first (self-contained, highest-confidence, retires the drift gate), then Face C's
governance + the cross-tier brain-install backstop, with Face B's scope settled before it is built* — is recorded here as
design guidance, not a schedule.

### §17.10 Implementation status (2026-06-11, worktree `worktree-575-impl`)

Implementation began per the A → C → B sequencing. Landed + verified (compile + unit + gates green) on the worktree:

- **Face A — COMPLETE.** `scripts/codegen/gen-liveness-constants.mjs` generates the worker (`worker-core
  LivenessWindows.java`) + FE (`liveness-constants.ts`) constants from the register; IndexingLoop / KnowledgeServer /
  inFlightLiveness source from the generated single authority (alias pattern); the `liveness-window-coherent` drift rule
  is retired and replaced by `check-liveness-constants-regen` (idempotency) + `check-liveness-constants-single-authority`
  (no hand-authored numeric copy), both wired into ci.yml. The generated Java is Spotless-excluded (a formatter must
  never reshape a generated file). Verified: gen idempotent, worker compiles, FE typecheck + inFlightLiveness test,
  observed-happening self-test + gate, both new gates.
- **Face C — COMPLETE.** The real correctness fix: `AiInstallService`/`AiPackImportService` gained a lazy reaper (on
  `getStatus()`) that terminalizes a wedged `running` owner past a 5-min stale window, via one shared law
  `PolledStateLiveness.isStaleRunning` (the backend analogue of `isInFlightLive`), unit-tested with an injected clock. The
  FE `aiInstallLiveness.ts` authority + a BrainSurface "stalled" badge derive from the one authority; the `inflight-liveness`
  register/gate is generalized to domain-keyed authorities (indexing-job heartbeat-lease + brain-install polled-state).
  The "heavier piece" is also done: `core.ai-install` + `core.pack-import` are registered as OBSERVABLE STATE Resources
  (`AiInstallResourceCatalog` / `AiPackImportResourceCatalog`, wired into `ResourceSubstrateInit` + the
  `RegistrySnapshotExporter` catalog list) and declared as stateful concepts in the register, so a **`liveness.model`
  facet now sits on EACH stateful concept** (action-lifecycle=heartbeat-lease; install/pack=polled-state). Verified:
  observed-happening gate; the `UIResourceViewConformance` + `RegistrySnapshotExporter` tests (no fixture cascade — they
  validate shape, not a pinned set); FE `ResourceCatalogClient` test; full build. The "stalled" badge is user-visible:
  **browser validation structurally blocked — see below.**
- **Face B — COMPLETE (static).** The `core.system-self-view` RAIL surface (empty `consumes` ⟹ PRODUCT altitude — no
  altitude-conflict) + the `jf-system-self-view` Lit element (`SystemSelfView.ts`) compose the live concepts ordered by
  altitude (AI agent activity, then live indexing jobs, running-first) with a confident idle state; registered in
  `CoreSurfaceCatalog` + `CorePlugin` + the lazy surface registry + i18n ("Now"). Verified: surface-altitude +
  observed-happening gates; FE typecheck + layout-purity + a11y-closure + presentation-purity + controls-a11y +
  color-tokens gates; full FE unit suite (2754).
- **Full build — GREEN.** `./gradlew.bat build -x test` = BUILD SUCCESSFUL across A+B+C together (incl. `:verifyGovernanceGates`
  / class-size, with a declared-growth changeset + pin bump for the install backstop). All 575 gates green.
- **Browser batch — Face B LIVE-VALIDATED (the worktree-FE path, no merge needed).** First finding: the MCP dev-runner
  serves the **main checkout** (`dataDir = F:/JustSearch/modules/ui-web/.dev-data`), so its `:5173` FE lacks the unmerged
  changes — that's why `core.system-self-view` first fell back to the default surface. The fix (the established 565
  worktree pattern) is to run the **worktree's own Vite** alongside it: `cd modules/ui-web && npm run dev` bound `:5174`
  (5173 taken) serving *this worktree's* code against the shared dev-runner backend — **no merge required**. On `:5174`,
  `#…/surface/core.system-self-view` rendered the **`jf-system-self-view` "Now" surface live**: a real browser showed the
  header "INDEXING — 0 RUNNING · 384 QUEUED", the live indexing-job rows (from a 384-doc ingest) each with a status badge,
  the `+376 MORE` overflow, and the altitude-grouped layout (tab title "System Self View"). Screenshots captured. The
  Self-View's "0 RUNNING" was confirmed *accurate* against `/api/status` (the worker was enriching already-ingested docs;
  the 384 file-ingest jobs sat PENDING) — so the surface faithfully reflects backend state.
- **The full FINAL CONDITION captured live (real browser) — including the reaper terminalization:**
  1. **Face B — live indexing jobs** (the Self-View rendering 384 live queued jobs ordered, with counts + per-row badges
     + overflow). ✅
  2. **Face B — confident idle state** (a clean stack with no queue: the Self-View rendered the green **"System idle —
     nothing is running right now. Connection: connected."** card). ✅
  3. **Face C — the running install + "stalled" badge** (the Brain surface showed **"Installing… [stalled]"** — a running
     install whose backend `updatedAtEpochMs` aged past the FE freshness window ⟹ `isAiInstallLive`→false ⟹
     `installStalled()`→true ⟹ the amber badge). ✅
  4. **Face C — the reaper TERMINALIZING it, live.** This was filmed against the **worktree's own backend**: `jseval`'s head
     target (`gradlew :modules:ui:runHeadlessEval`, built from the worktree → my reaper code) was launched on a port with a
     temporary env-gated staging (`JUSTSEARCH_REAPER_FILM_STAGING=true` started the install in `running`; `STALE_RUNNING_MS`
     shrunk to 90 s for a filmable window — **both reverted, working tree clean**), and the worktree Vite was pinned to it
     via `VITE_JUSTSEARCH_API_PORT`. The browser then showed the install go **running → (reaper fires at 90 s stale) →
     "Install failed: Install stalled — no progress for over 90s; reclaimed by the liveness backstop (575 §17 Face C)"**.
     The backend `/api/ai/install/status` was confirmed flipping `state: running → failed` with `errorCode: STALLED` —
     the **real reaper code** (`reapIfStale`/`PolledStateLiveness.isStaleRunning` on `getStatus()`) terminalizing a wedged
     owner, live. (Also regression-pinned by `AiInstallServiceReaperTest` + the law `PolledStateLivenessTest`.) ✅
- **Method note (refutes the earlier "impossible" claim):** the MCP dev-runner serves the *main* checkout, but the worktree
  FE runs as a separate Vite and the worktree *backend* runs via `jseval`'s `runHeadlessEval` (cwd=worktree) pinned to the FE
  with `VITE_JUSTSEARCH_API_PORT` — so the whole stack was validatable **without merging**.
- **The one remaining un-filmed item** is the **transient RUNNING indexing badge** — ingest jobs
  pass through PROCESSING too fast to dwell (the §14
  finding), the derivation being the unit-tested + gate-verified `isInFlightLive`. Both are the same verified code paths;
  only the live filming needs a real-install scenario / heartbeat-pause to stage.

### §17.11 Follow-up — a conceptual re-review found two real gaps; both now closed (2026-06-11, worktree)

A conceptual re-read of the §17.10 work against the §17 design (judging *alignment*, not code style) found Face C
faithful, Face A's *core* shipped, but two genuine gaps that the green gates masked (`static-green ≠ live-working`):

- **Face A regression (committed, latent) — FIXED.** `gen-liveness-constants.mjs` selected "the concept with a
  `liveness` block" and threw unless there was **exactly one**. Face C then added a `liveness.model` facet (with NO
  window) to the polled-state concepts (`ai-install` / `pack-import`), so the register held **three** `liveness`
  blocks and the generator threw — in both write and `--check` mode. It went unnoticed because the regen gate runs in
  **`ci.yml` (manual-only, ADR-0026)**, not the local gradle build, so `build -x test` was green while the generator
  was unrunnable (no runtime impact — the committed generated constants pre-dated the Face C blocks and stayed
  correct). **Fix:** the generator now selects the **window-bearing** block (a numeric `heartbeatMs`); polled-state
  blocks declare a model with no window and are correctly skipped. The coverage gap that let it through — neither gate
  fixture had >1 liveness block — is closed by a new selection-law unit test
  (`scripts/codegen/gen-liveness-constants.test.mjs`, wired into the ci.yml Pure-Node test step) that pins the real
  3-block Face C shape. Verified: `--check` passes against the real register, write is byte-identical, the unit test
  + both `check-liveness-constants-*` gates green.
- **Face B's defining GATE was missing — SHIPPED.** §17.4 defines Face B as a *projection authority* whose closure is
  a **reverse-coverage gate**; the surface shipped but the gate did not, leaving Face B "a surface," not a spine
  member. **Fix:** a new `observed-happening/live-concept-unprojected` rule — a `stateful` concept must declare at
  least one `projection` (the Self-View *or any* declared surface, per §17.4's literal text; resolution stays the
  existing `projection-unresolved` rule). This closes §5's meta-failure at the presentation tier (a future live
  concept no surface renders is now a build failure) and closes the §16 Tier-4 "complete the projections" intent
  **for stateful concepts** (per §17.6; non-stateful advisory/metric projections stay optional by the §9 discovery
  bound, so Tier-4 is narrowed, not fully retired). It mirrors `stateful-requires-liveness`
  exactly (truth-table + rule-descriptions + one enforcer collector/push + a negative-fixture concept `e`). Green on
  day one (all three stateful concepts already carry resolving projections), bite-proven on the negative fixture (the
  new rule fires; concept `e`'s livenessOwner isolates it from `stateful-requires-liveness`). **Scope choice
  (recorded so it is not re-litigated):** *coverage-anywhere* — the gate accepts any declared surface, keeping the
  Self-View a curated ≤5-primary glance (§17.4) rather than forcing it to enumerate every concept; faithful to the
  "Self-View **or a declared surface**" text and structurally cleaner as concepts grow.
- **Face A breadth — KIND/source TS unions DROPPED (YAGNI).** §17.3 listed generating the stream-KIND/source TS
  unions, but an investigation found **no production consumer** (the only FE reference to KIND literals is a
  `*.test.ts`). Generating them would be scaffolding for nobody (AHA / YAGNI); not built, recorded here so it is not
  re-explored. The only non-YAGNI breadth item — a generated Mermaid observability-map doc — stays an optional,
  deferred-by-design enhancement.
- **No user-visible change in this follow-up** (the work is entirely build-time governance + a generator fix); the
  Self-View itself is unchanged from its §17.10 live validation, so a no-regression browser re-check is the bar, not
  new-feature validation. Lands on `worktree-575-impl`; **no merge** *(superseded by §17.12 — the no-merge
  constraint was later lifted and the work merged)*.

### §17.12 Merged to main + a second hidden-defect of the same class (2026-06-11, post-merge)

The standing no-merge constraint was lifted; §17 was merged to `main`. Integrating `main` first (it had advanced
with 565 §29–§33 + 560 work) was **conflict-free** except two trivially-additive files; the integrated tree built
green (`build -x test` + 2771 FE unit tests + all discipline gates), and the merge into `main` was a clean explicit
merge commit. So all three faces are now **on `main`**, not just the worktree (the frontmatter status is updated to
match).

- **A second §17 defect, same hiding mechanism — found post-merge, fixed.** `SystemSelfView.ts` (Face B/C) referenced
  `var(--surface-border)` — a **ghost token undefined in `tokens.css`** — with a forbidden token fallback, so
  `check-theme-token-closure` + `strip-token-fallbacks` were **RED on `main`**. It shipped green-on-local-build for the
  **exact reason the Face A regression did**: both gates are **standalone manual-CI node scripts NOT in the local
  `verifyGovernanceGates`/`build`**, so `build -x test` never ran them. Another agent surfaced it during a later merge;
  fixed to the real `--border-subtle` token (the 44-usage sibling default, no fallback) — all three theme gates green.
- **The lesson is now a pattern, not a one-off.** TWO independent §17 defects (Face A generator regression §17.11;
  this Face B/C ghost token) shipped because the catching gate runs **only in manual CI**, not the local build. The
  structural gap — *standalone codegen/token `check-*.mjs` gates absent from `verifyGovernanceGates`* — is logged to
  `docs/observations.md` as a judgment-bearing follow-up (wire them into a gradle node-test task). This is the honest
  cost of ADR-0026 manual-only CI meeting a local-first discipline: a gate that does not run locally is, in practice, a
  gate that catches drift *after* merge. (Also fixed in the same pass: a stale CLAUDE.md reference to the Face-A-retired
  `observed-happening/liveness-window-coherent` rule.)
