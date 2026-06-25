---
title: "551 — The wire-contract gap: SearchTrace lives in the frozen barrel, not the gated source-of-truth (and is the protobuf-es migration alive?)"
type: tempdocs
status: done
created: 2026-05-27
updated: 2026-06-10
category: wire-contract / governance / frontend-framework-kernel
related:
  - tempdoc 553 (canonical-search-execution-record) — the umbrella design; this gap is the wire-projection instance
  - tempdoc 549 (unified-search-trace) — the campaign that surfaced this gap; COMPLETE/merged
  - ADR-08 (docs/decisions/0038-wire-contract-source-of-truth.md)
  - ADR-09a (docs/decisions/0040-wire-contract-format.md)
  - slice 3a-1-8 (docs/decisions/0039-contract-substrate.md) + 3a-1-8b/3a-1-8f
  - slice 441 (docs/decisions/0038-wire-contract-source-of-truth.md (historical: the wire-format serialization-audit slice; retired to git))
  - observations.md item (2026-05-27): "contracts/wire/knowledge.proto has no SearchTrace ... wire-gen-path drift"
---

# 551 — The wire-contract gap: SearchTrace is in the wrong layer

> **CLOSED 2026-06-10 — see [553 §0](553-canonical-search-execution-record.md#0-umbrella--searchtrace-instance-tracker-551--552--553) for the family tracker.**
> **Part 1 DONE & merged** — `SearchTrace` is in the gated `contracts/wire/knowledge.proto`
> (field 27 + `message SearchTrace`), backed by `KnowledgeWireContractConformanceTest`.
> **Part 2 (the FE migration) is superseded**, not executed: its goal was achieved by tempdoc 564's
> JSON-Schema projection (the `wire-types.ts` barrel was deleted; the FE now consumes the generated
> `search-trace.ts`), not the `knowledge_pb` route §B.4 planned — see [552](552-searchtrace-fe-barrel-migration.md).
> No remaining work owned here; any further execution-record unification is tracked in 553. Everything
> below is the preserved dated reasoning trail.

## Status — updated 2026-05-27 (takeover agent)

**The central question this doc was opened to answer is RESOLVED.** Everything
below the "## The verified architecture" heading is the *original investigation
by the 549 agent* — preserved as the reasoning trail. Where it has been
superseded, the superseded passage carries an inline **`⟹ SUPERSEDED`** marker
pointing here / to §B. Current truth:

1. **The gap is real** and exactly as the original body describes — verified
   claim-by-claim in **§B.1–B.3**. The trace has zero breaking-change /
   protovalidate protection on the FE↔Head contract, and **no record↔proto
   conformance test exists** to catch the omission class.
2. **The "is the `_pb` migration alive or dormant?" question was the wrong
   model** (§B.5). Primary sources (3a-1-8 closure header lines 14-16; kernel
   §"Capability vs Mandate") show consumer migration is an **opt-in capability
   by design, not a campaign** — so flat adoption is the *expected* steady
   state, not abandonment, and the `_pb` destination is alive (ADR-08/09a
   un-rescinded; ArchUnit + ESLint barrel-only guards still enforced). This
   **eliminates** the decision tree's "reopen ADR-09a" branch.
3. **The fix decomposes** (§B.4): **Part 1** — add the trace to
   `contracts/wire/knowledge.proto` + protovalidate + a record↔proto
   conformance test; *no FE changes*; additive, non-breaking minor VERSION
   bump. This is **migration-independent** and endorsed by capability-vs-mandate.
   **Part 2** — migrate the 4-5 FE consumers + delete the trace from frozen
   `wire-types.ts`; this is **opt-in**, not mandated, and needs its own
   go-ahead.
4. **Correction to the original "clean camelCase pilot" claim** (§B.7): the
   trace dodges the casing sweep but hits the **`bigint` friction** that
   actually drove Item-4 deferral (`int64 ms` → `bigint` in protoc-gen-es).
   Model **`ms` as `int32`** on the wire contract (faithful to the width-less
   JSON; emits `number`) to make the pilot genuinely clean.

**User decision (2026-05-27): do NOT implement yet.** This takeover stopped at
investigation + design correction; no proto/code edits were made. Standing
recommendation when authorized: ship Part 1 (`ms` as int32); treat Part 2 as
opt-in. Full detail and primary-source citations: **§B**.

**UPDATE (2026-05-27): Part 1 SHIPPED — commit `386c4f0ef`.** The user subsequently
authorized Part 1. Implemented exactly as §B recommended:
- `contracts/wire/knowledge.proto`: added `SearchTrace`/`TraceQpp`/`TraceDegradation`/
  `TraceStage`/`HitStage` + `KnowledgeSearchResponse.search_trace` (27) + `Hit.trace` (9),
  mirroring the app-api records via `json_name`.
- protovalidate: `TraceStage.status` ∈ {executed,skipped,disabled,failed}; `id` left a free
  string (ADR-09a forward-compat). `ms` as **`int32`** per §B.7 — verified `knowledge_pb.d.ts`
  emits `ms?: number`, not `bigint`.
- VERSION 1.0.0→1.0.1 + `additive-optional` changeset (`551-add-search-trace.md`); wire gate
  passes (`declared-additive`).
- NEW `KnowledgeWireContractConformanceTest` (app-api): asserts every knowledge record field
  has a matching proto `json_name` — the load-bearing guard against recurrence.
- **No FE consumer changes.** Verified: full `build -x test`, conformance test, FE typecheck +
  2206 unit tests, wire + class-size gates.

**Part 2 (OPEN, opt-in) — now owned by tempdoc 552** (`docs/tempdocs/552-searchtrace-fe-barrel-migration.md`):
migrate the 4-5 FE search consumers from the frozen `wire-types.ts` barrel onto generated
`knowledge_pb`, then delete the trace from `wire-types.ts`. Per capability-vs-mandate (§B.5) this is
NOT compelled — it needs its own workload justification (552 §"Justification gate"). Until then, the
live FE still reads the barrel, so record↔barrel drift is unguarded (only record↔proto is, via Part
1's conformance test). See §B.4 "Residual risk."

---

> **Read this first — epistemic status.** This doc was written by the agent that
> implemented and merged tempdoc 549. Some statements below are *verified against
> source* (marked **[V]**); others are *inferred from documented intent* and were
> NOT confirmed (marked **[I]**). The single most important question in this doc —
> "is the protobuf-es wire migration alive or dormant?" — is **[I] / unresolved**
> and is a roadmap question the code alone cannot answer. Do not treat the
> recommendation as settled until that question is answered. Subagents do not
> inherit `CLAUDE.md`/`.claude/rules` — everything you need is inlined here, but
> verify the **[I]** claims yourself before acting.

## TL;DR / the ask

Tempdoc 549 collapsed five search-explainability representations into one canonical
`SearchTrace` and claimed "exactly one source of truth **on the wire**." That is true
for the **internal Head↔Worker gRPC wire** (`ipc-common/indexing.proto`). It is **not**
true for the **FE↔Head wire contract**: the `SearchTrace`/`TraceStage`/`HitStage` types
were added only to the **frozen, no-longer-regenerated `wire-types.ts` barrel**, and are
**absent from `contracts/wire/knowledge.proto`** — the buf-gated, protovalidate-carrying,
VERSION-tracked artifact that ADR-08/09a designate as the wire-contract source of truth.

Consequences (all **[V]**):
- The `wire` discipline gate (bumped to **VERSION 1.0.0** by 549) guards `knowledge.proto`,
  which has no trace → **the gate gives the trace zero breaking-change protection.**
- protovalidate/CEL (ADR-09a's "highest-value axis") cannot enforce anything on the trace
  (closed `status` vocabulary, `StageId` discriminator) — there's no proto to carry the
  constraints.
- The protobuf-es generated type `knowledge_pb.d.ts` (compiled from `knowledge.proto`) has
  `KnowledgeSearchResponse` but **no `SearchTrace`**, and is imported by **nobody**. If the
  documented per-consumer barrel→`_pb` migration ever reaches the knowledge surface,
  regenerating drops the trace — a latent landmine.

**The decision is NOT a free "(a) add to proto vs (b) ratify hand-maintenance" choice.**
There is no living "split" to ratify — the typescript-generator that produced `wire-types.ts`
was retired (2026-05-06). The real fork is whether the `contracts/wire`→protobuf-es migration
is **alive** (then: promote the trace into `knowledge.proto`, ideal clean first/second consumer)
or **de-facto dormant** (then: either deliberately revive it, or reopen ADR-09a to ratify the
barrel as the real contract — a canonical-decision change, not a quiet default).

## The verified architecture (three artifacts + the Java side)

The FE↔Head wire is **JSON over loopback REST** (Javalin). There is no protobuf on this wire
at runtime — protobuf is used purely as the **contract IDL / codegen source**. Three artifacts:

1. **`contracts/wire/knowledge.proto`** — **[V]** the declared source-of-truth IDL for the
   FE↔Head JSON surface. `package justsearch.wire.v1`. Uses `[json_name=...]` to pin the JSON
   field names; carries `(buf.validate...)` CEL invariants. Its header comment (line ~73) says
   it was "Populated 2026-05-06 per proto-correctness pass against
   `modules/app-api/.../knowledge/KnowledgeSearchResponse.java`" — i.e. it is hand-mirrored
   against the app-api record. **549 correctly *reserved* the retired fields here
   (`debug_scores`, `provenance`, `pipeline_execution`, flat fields 7–21) but never added the
   replacement `SearchTrace`/`TraceStage`/`HitStage` messages, nor a `search_trace` field on
   `KnowledgeSearchResponse`, nor a `trace` field on `Hit`.** So it describes a response with
   holes where the old fields were and no trace.
   - Emitters (`contracts/wire/buf.gen.yaml`): `protoc-gen-es` → `modules/ui-web/src/api/generated/*_pb.{js,d.ts}` (TS).
   - Java emission: `modules/api-contract-projection-java/` (the `com.google.protobuf` Gradle
     plugin reads `srcDir contracts/wire` → generated Java under `io.justsearch.contract.wire`).
   - `contracts/wire/VERSION` = **1.0.0** (549 bumped 0.2.0→1.0.0 for the `remove` changeset).
   - Gate: `node scripts/governance/run.mjs --gate wire --mode gate` (runs `buf breaking`;
     registered in `governance/registry.v1.json`). Changesets: `contracts/wire/.changesets/`.

2. **`modules/ui-web/src/api/generated/knowledge_pb.{js,d.ts}`** — **[V]** generated from #1 via
   `:wireGenerate`. Has `KnowledgeSearchResponse`, **0 occurrences of `SearchTrace`**, and is
   imported by **0** FE files. Dead today; the intended future target.

3. **`modules/ui-web/src/api/generated/wire-types.ts`** — **[V]** the OLD typescript-generator
   barrel. Its header still says "Generated by WireTypesTsGenerationTest from typescript-generator"
   but that test/dependency were **RETIRED 2026-05-06** (slice 3a-1-8 Phase 4 closure;
   `modules/app-observability/build.gradle.kts` lines ~67-70, ~100-103). The file is **frozen —
   no longer regenerated by anything.** **549 added `SearchTrace`/`TraceStage`/`TraceQpp`/
   `TraceDegradation`/`HitStage` here, by hand**, plus `searchTrace?` on `KnowledgeSearchResponse`
   and `trace?: HitStage[]` on `Hit`.
   - The barrel `modules/ui-web/src/api/generated/index.ts` re-exports `SearchTrace` et al.
     **from `./wire-types`** (lines ~79-90). Its header (lines 1-21) says: "Wire-types barrel —
     TRANSITIONAL… New code: import directly from the relevant `./<topic>_pb.d.ts`… Existing code:
     continues using this barrel's typescript-generator-derived exports until incremental
     migration." So the barrel itself documents `wire-types.ts` as legacy-to-be-migrated.

**The runtime source of truth for the actual JSON** is the **app-api Java record**
(`modules/app-api/.../knowledge/KnowledgeSearchResponse.java` + `SearchTrace.java`), serialized
by `modules/ui/.../api/KnowledgeSearchController.java`. `knowledge.proto` is a *descriptive
contract* of that JSON; the Java record and the TS types are both **projections that must
conform** to it. The whole governance value of `contracts/wire` is that buf + protovalidate
enforce that conformance — which is exactly the protection the trace currently lacks.

### FE consumers of the trace today (all import from the barrel `api/generated/index.js`)
- `modules/ui-web/src/shell-v0/aggregate-substrate/strategies/searchTraceExplain.ts` (+ `.test.ts`)
- `modules/ui-web/src/shell-v0/aggregate-substrate/components/JfSearchTrace.ts`
- `modules/ui-web/src/shell-v0/state/searchState.ts`
- `modules/ui-web/src/shell-v0/views/SearchSurface.ts`

## The critical open question: is the protobuf-es migration alive? **[I] / UNRESOLVED**

> **⟹ SUPERSEDED (§B.5).** Resolved by the takeover agent: this binary is the
> wrong model. Consumer migration is an *opt-in capability by design*, not a
> campaign — so the flat adoption below is the expected steady state, not
> evidence of dormancy. The `_pb` destination is alive. The text below is
> retained as the reasoning that led to the (corrected) framing.

ADR-09a + slice 3a-1-8 Phase 4 *intend* `contracts/wire`→`_pb` as the end-state and call
`wire-types.ts` transitional. **But documented intent ≠ live direction.** Adoption evidence
measured 2026-05-27:

- **`_pb` direct importers in `modules/ui-web/src`: 3 files** — `api/lifecycleState.ts`,
  `api/lifecycleState.test.ts`, `api/wireProjection.test.ts` (i.e. ~1 real product consumer +
  the projection plumbing).
- **Barrel / `wire-types` importers: 19 files.**
- `git log -- contracts/wire/` and `git log -- modules/ui-web/src/api/generated/`: the only
  recent motion is **549 itself** (plus incidental 550/447). **No dedicated migration effort is
  advancing the `_pb` path.** `contracts/wire` gets touched only when a feature happens to reach
  it.

So by **usage**, the barrel is still the de-facto production contract and the protobuf-es
replacement has produced ~1 consumer in the ~3 weeks since Phase 4 "closed." This is consistent
with *either* "migration real but barely started" *or* "migration stalled / quietly deprioritized."
**The code cannot distinguish these.** This is a roadmap fact owned by the 421 framework-kernel
track. **Answer this before implementing anything.**

## Decision tree

1. **Confirm the migration's status** (with the 421 owner / user):
   - **Alive / on-roadmap** → go to §"Recommended slice" (promote trace to `contracts/wire`,
     migrate the search FE onto `knowledge_pb`). The trace is an unusually clean first/second
     consumer (see §"camelCase angle").
   - **Stalled / abandoned** → two sub-options, both bigger than a quick fix:
     - **Revive deliberately** — same work as §"Recommended slice", but framed as restarting a
       dormant migration (own decision, more scope: you become the pattern other consumers follow).
     - **Reopen ADR-09a** — ratify the barrel as the real contract and move governance there
       (protovalidate on the barrel? a Java-record-derived gate?). This **changes a canonical
       decision** and per CLAUDE.md needs a new/superseding ADR, not a default. Likely the wrong
       call (forfeits buf breaking-detection + protovalidate that already exist), but document why
       if chosen.

2. Whatever the choice, **the gate-protection gap is real and must be closed**: today a breaking
   change to the trace's wire shape is caught by *nothing* on the FE↔Head contract. Either the
   proto guards it (preferred) or a new check does.

## Recommended slice (if migration is alive — the directionally-correct end-state)

Promote the trace into the gated source-of-truth and migrate the search FE onto the generated
type. Concretely:

1. **Define the trace in `contracts/wire/knowledge.proto`:** `SearchTrace`, `TraceStage`,
   `TraceQpp`, `TraceDegradation`, `HitStage` messages; `SearchTrace search_trace = 25?` (pick a
   free field number — 25 is `query_understanding`; use the next free numbers and respect the
   `reserved` ranges) on `KnowledgeSearchResponse`; `repeated HitStage trace` on `Hit`.
   `[json_name]` must match the app-api record's JSON exactly (mirror `SearchTrace.java` /
   `KnowledgeSearchResponse.java`). **Stay faithful to the runtime record — run the proto-
   correctness check by hand and ideally automate it (see item 6).**
2. **protovalidate constraints:** `TraceStage.status` should be validated against the closed set
   {executed, skipped, disabled, failed}; **`StageId`/`stage.id` MUST stay a free string, NOT an
   `in:`-constrained enum** — ADR-09a §"Forward-compat unknown variants" explicitly forbids
   `(buf.validate.field).string.in:` on discriminators (rejects unknown future variants). Mirror
   the closed StageId vocabulary the `stage-completeness` gate already owns (12 wireIds:
   query-understanding, expansion, correction, sparse/dense/splade-retrieval, fusion, chunk-merge,
   branch-fusion, lambdamart, cross-encoder, freshness).
3. **Regenerate + version:** `./gradlew :wireGenerate` (rewrites `knowledge_pb`); this is an
   *additive* change (new fields) → minor VERSION bump + an `add`/`feature` changeset under
   `contracts/wire/.changesets/`. Verify `node scripts/governance/run.mjs --gate wire --mode gate`.
4. **Migrate the FE search consumers** (the 4-5 files in §"FE consumers") from
   `../api/generated/index.js` (barrel→wire-types) to the generated `knowledge_pb` types.
5. **Remove the trace types from the frozen `wire-types.ts`** + the barrel re-export — they do
   not belong in a retired snapshot. Confirm no other importer breaks (`grep` first).
6. **Close the conformance hole:** verify whether ANY automated test catches "app-api record has a
   field `knowledge.proto` lacks." The trace omission going undetected strongly suggests **none
   exists [I]** — the proto was hand-mirrored. If so, add a `KnowledgeSearchResponse.java` ↔
   `knowledge.proto` field-shape conformance test so the next field addition can't silently skip
   the contract. (Candidate home: `modules/api-contract-projection-java` or a new app-api test.
   Existing wire tests — `WireContractValidatorTest`, `ContractGovernanceArchUnitTest`,
   `ContractEventValidatorTest` — validate *invariants*, not *record↔proto field parity*; confirm.)
7. **Java side:** check whether `modules/api-contract-projection-java`'s generated Java types are
   consumed anywhere, or are as dead as `knowledge_pb` on the FE (parallel adoption question).

### The camelCase angle (why the trace is a clean first consumer) **[V]**

> **⟹ SUPERSEDED / half-true (§B.7).** The casing dodge is real, but the trace
> hits the *bigint* friction (`int64 ms` → `bigint`) that actually drove Item-4
> deferral. The pilot is clean only if `ms` is modeled as `int32` on the wire
> contract. See §B.7.
ADR-09a §"Mixed snake/camel naming (SE-4)" lines ~153-181: the main cost of the barrel→`_pb`
migration is a snake_case→camelCase access-site sweep (protobuf-es emits camelCase TS props). But
the trace types in `wire-types.ts` are **already camelCase** (`effectiveMode`, `decisionKind`,
`spladeExecuted`, …) — so migrating the trace specifically incurs **none** of that sweep. That
makes the trace an unusually low-friction pilot for the migration, *if* the team wants to restart it.

## What I verified vs inferred (for the next agent)

**[V] Verified against source this session:**
- `wire-types.ts` is frozen / no longer regenerated; typescript-generator retired 2026-05-06.
- `contracts/wire/knowledge.proto` has the retired fields reserved but no trace messages/fields.
- `knowledge_pb.d.ts` has `KnowledgeSearchResponse`, 0 `SearchTrace`, 0 importers.
- FE reads `SearchTrace` from the barrel, which re-exports from `wire-types.ts`.
- `buf.gen.yaml` emits `protoc-gen-es` → `modules/ui-web/src/api/generated/`.
- Adoption counts: 3 `_pb` importers vs 19 barrel importers; `contracts/wire` git motion is 549-only.
- ADR-09a forbids `in:` on discriminator fields; names protovalidate the "highest-value axis."

**[I] Inferred, NOT confirmed — verify before acting:**
- That the `contracts/wire`→`_pb` migration is the *live* direction (intent is documented; live
  adoption looks dormant). **THE decision hinges on this.**
- That no automated app-api↔proto conformance test exists (the omission going undetected implies
  it, but confirm by reading the wire/projection test suite).
- That `knowledge_pb`'s zero-importer state means "not-yet-reached" rather than "abandoned."

---

## §B — Takeover investigation (2026-05-27, agent picking up 551)

I re-verified every **[V]** claim and resolved the three **[I]** claims against source.

### B.1 — Every [V] claim reconfirmed
- `contracts/wire/knowledge.proto`: retired fields **reserved** (KnowledgeSearchResponse `reserved 7–21, 24` + names; Hit `reserved 4,5` = `debug_scores`/`provenance`), **zero** `SearchTrace`/`TraceStage`/`HitStage` messages, no `search_trace`/`trace` field. `VERSION` = `1.0.0`. Only changeset is `549-retire-legacy-search-reps.md` (the `remove`).
- `knowledge_pb.d.ts`: `SearchTrace` occurrences = **0**. `wire-types.ts`: = **3**.
- Adoption (re-counted this session): `_pb` direct importers = **3** (`api/lifecycleState.ts`, `lifecycleState.test.ts`, `wireProjection.test.ts` — i.e. ~1 real consumer + plumbing); barrel/`wire-types` importers = **16**. `contracts/wire/` and `generated/` git motion is **549-only** (plus incidental 447/550), confirming no dedicated `_pb` migration effort is advancing.

### B.2 — Resolved a near-miss in the 549 commit trail (strengthens the thesis)
Phase A's commit (`bc01e49c0`) message reads "additive unified SearchTrace types **(proto** + app-api + wire-types)". Read alone this looks like it contradicts the gap claim. It does **not**: the `--stat` shows the proto it touched was **`modules/ipc-common/src/main/proto/indexing.proto` (+36)** — the **internal Head↔Worker gRPC** wire — **not** `contracts/wire/knowledge.proto` (the FE↔Head contract, untouched in Phase A). This is *exactly* the layer confusion 551 names. The trace is gated on the internal gRPC wire and absent from the FE↔Head contract. **[V] confirmed.**

### B.3 — [I] "no record↔proto conformance test" → CONFIRMED absent
Read all three `api-contract-projection-java` wire tests:
- `WireContractValidatorTest` — protovalidate round-trips (regex/CEL/forward-compat/alias). Invariants, not field parity.
- `ContractEventValidatorTest` — protovalidate shape checks on the *event* contract (CapabilityRegistered, ReactionOutcome…). Not the knowledge surface, not parity.
- `ContractGovernanceArchUnitTest` — ArchUnit: generated types stay in the wire package / inherit `Message` / carry no Jackson annotations. Codegen-hygiene, not parity.

None compares `KnowledgeSearchResponse.java`'s field set against `knowledge.proto`'s. **Inference #6 is correct — the proto is hand-mirrored with no automated guard against an app-api field the proto lacks.** That is precisely how the trace omission shipped silently.

### B.4 — Critical refinement: the proto edit is NOT actually gated on the migration question (for 3 of 4 branches)
The tempdoc's §"Scope guard" says *"do not start the proto edit until the migration-status question is answered."* I think this over-couples two separable concerns and should be relaxed. **Decompose the recommended slice into two parts:**

- **Part 1 — close the governance gap (migration-INDEPENDENT).** Add `SearchTrace`/`TraceStage`/`TraceQpp`/`TraceDegradation`/`HitStage` to `knowledge.proto`; `search_trace`/`trace` fields; protovalidate `status` vocabulary (StageId stays a free string per ADR-09a); regen `knowledge_pb`; minor VERSION bump `1.0.0`→`1.1.0` + `add` changeset; add the record↔proto conformance test. **No FE file changes.** Adding fields is non-breaking under buf ([breaking rules](https://buf.build/docs/breaking/rules/)), so this is a clean additive minor bump. This makes the proto catch up to a shape **already live** in the runtime JSON (app-api record) and in `wire-types.ts` — it creates **no new public commitment**, it just brings the gated contract into conformance with reality. It closes both the buf-gate gap and the protovalidate gap *today*.
- **Part 2 — migrate FE consumers (migration-DEPENDENT).** Repoint the 4–5 search FE files from the barrel to `knowledge_pb` and delete the trace types from frozen `wire-types.ts`. This is the only part whose value/timing depends on whether the `_pb` migration is live.

**Why Part 1 is robust across the decision tree:** of the four branches (alive → promote; stalled→revive; stalled→reopen ADR-09a; do-nothing), Part 1 is the correct move in **three**. Only the "reopen ADR-09a to ratify the barrel and abandon the proto-as-SoT" branch would skip it — and 551 itself calls that "likely the wrong call." So the proto edit's risk is not contingent on the roadmap answer; only the *consumer migration* (Part 2) and the *barrel deletion* genuinely are. Recommendation: decouple, ship Part 1 regardless, gate Part 2 on the answer below.

**Residual risk of Part 1-alone:** a temporary dual definition (generated `knowledge_pb` *and* hand-frozen `wire-types.ts` both carry the trace). But that duplication already exists for the entire barrel and is the documented transitional cost; Part 1 strictly *improves* on today (zero protection → buf+protovalidate guarding the canonical shape, plus a parity test catching app-api drift). The remaining barrel-vs-proto drift window is closed by Part 2.

### B.5 — The "alive vs dormant" question is the WRONG model — DETERMINED from design, not adoption-count
The user asked me to determine the migration status from evidence rather than guess. I did, and the answer dissolves the tempdoc's binary. **The barrel→`_pb` consumer migration is neither "alive (active campaign)" nor "abandoned." It is, by explicit and current design, an opt-in *capability*, not a *mandate.*** Primary sources:

- **Slice 3a-1-8 closure header** (`slices/3a-1-8-wire-contract-architecture.md` lines 14-16, the slice that *shipped* the protobuf-es path): *"Item 4 (wire-types.ts deletion) **deferred** per substrate's capability-vs-mandate (**bigint friction surfaced**; **per-consumer migration is incremental opt-in work**). Per-record adoption + Zod retirement remain admitted-as-capability (**capability-vs-mandate by design**)."*
- **Kernel `10-kernel/05-contract-substrate.md` §"Capability vs Mandate"** (lines 401-424): *"The substrate's commitments are *capabilities*… not *mandates*. Each slice that exercises a capability decides whether to make that exercise universal… or opt-in… **When in doubt, default to opt-in; mandates are additional architectural commitments that require workload evidence.**"*
- The destination itself is **not rescinded**: ADR-08/09a stand (no superseding ADR), the `protoc-gen-es` emitter is live, and the **ArchUnit + ESLint barrel-only guards are still active** (kernel V1-exercise matrix line 452: "Wire-shaped types restricted to contract spec (build-time) — Live"). The door to `_pb` is held open by build-time enforcement.

**Therefore the determination is:** the `_pb` end-state is **alive as the canonical destination**, but **conversion is demand-driven opt-in by design** — so the flat ~1-consumer adoption count is the *expected steady state of an opt-in capability with no mandate*, **not** evidence of abandonment. There is no "campaign" that could be alive or dormant; 551's binary mismeasured an opt-in mechanism against a campaign yardstick. **Forcing a consumer migration now would itself be creating a mandate** — precisely the move the kernel says requires independent workload evidence, not a default.

**Consequences for the decision tree:**
- **"Reopen ADR-09a" branch → eliminated.** Proto-as-SoT is reaffirmed by live build-time guards, not just historical intent. There is no basis to ratify the barrel.
- **Part 1 (add trace to `knowledge.proto` + protovalidate + conformance test, NO consumer migration) → directly endorsed by the discipline.** It exercises the *mandated* capability (contract = SoT; ADR-08 forbids hand-mirrors) without imposing a *consumer mandate*. It is the correctly-sized move and is **not** gated on any roadmap answer.
- **Part 2 (migrate the 4-5 FE consumers + delete trace from `wire-types.ts`) → opt-in, not mandated.** Permitted, but per capability-vs-mandate it needs its own justification (workload evidence). "SearchTrace is a clean pilot" is the *only* offered justification — and §B.7 shows that justification is weaker than 551 claimed.

### B.7 — The "clean camelCase pilot" claim is half-true: the trace walks straight into the bigint friction
551's §"camelCase angle" argues the trace is an unusually low-friction migration pilot because its `wire-types.ts` types are *already camelCase*, dodging the snake→camel access-site sweep. **Verified — but it dodges only the casing axis and walks into the *other* documented friction axis (`bigint`), which is the one the closure header names as the actual reason Item 4 was deferred.**

- Internal `ipc-common/indexing.proto` models the stage cost as **`optional int64 ms = 4`** (`TraceStage`, line 161); the runtime JSON and `wire-types.ts` use `ms?: number`.
- `protoc-gen-es` emits **`int64 → bigint`** — verified live in the *existing* `knowledge_pb.d.ts` (`pendingJobs: bigint`, `indexedDocuments: bigint`, …). So a faithful int64 mirror of `ms` into `contracts/wire/knowledge.proto` would regenerate `TraceStage.ms: bigint`, mismatching the runtime `number` and reintroducing exactly the friction that stalled Item 4.
- **Design fix (and a genuine improvement to 551's recommended slice):** the FE↔Head `knowledge.proto` is a *separate* IDL from the internal gRPC `indexing.proto` and need not share scalar widths. Model **`int32 ms`** on the wire contract — a per-stage latency in ms fits int32 comfortably (~24.8 days of headroom), it is faithful to the JSON (which has no width), and `protoc-gen-es` emits `int32 → number`, **no bigint**. With int32 the "clean pilot" claim becomes genuinely true; with a copied int64 it is false. 551 item 1 says "mirror `SearchTrace.java` JSON exactly" — since JSON carries no width, int32 *is* the faithful mirror. This must be stated explicitly or the pilot reintroduces the friction it claims to avoid.

### B.6 — User decision (2026-05-27)
Asked the user: (1) migration status, (2) start Part 1 now. Answers: **(1) "I don't know — determine it somehow"** → resolved in §B.5 above (opt-in capability, not a campaign; destination alive, conversion demand-driven). **(2) "Do not implement anything yet."** So this takeover stops at investigation + design correction; no proto/code edits made. The standing recommendation for when implementation is authorized: **ship Part 1 (governance-gap closure, migration-independent, modeling `ms` as int32 per §B.7); treat Part 2 (consumer migration) as opt-in requiring its own go-ahead — the capability-vs-mandate discipline does not compel it, and SearchTrace's pilot value is real only with the int32 modeling.**

## Pointers / commands
- Regenerate FE types from proto: `./gradlew :wireGenerate`
- Wire gate: `node scripts/governance/run.mjs --gate wire --mode gate` (+ `--explain <ruleId>`)
- buf toolchain install: `scripts/wire-contract/install-buf.sh`
- FE verify: `cd modules/ui-web && npm run typecheck && npm run test:unit:run`
- Source-of-truth ADRs: `08-wire-contract-source-of-truth.md`, `09a-wire-contract-format.md`
  (both under `docs/decisions/`)
- Prior art: slice `441-wire-format-serialization-audit.md`, `3a-1-8*-*.md` (same `slices/` dir)
- The trace's app-api SoT: `modules/app-api/.../knowledge/SearchTrace.java`,
  `.../knowledge/KnowledgeSearchResponse.java`; controller `modules/ui/.../api/KnowledgeSearchController.java`
- The `stage-completeness` gate (owns the closed 12-wireId StageId vocabulary):
  `scripts/governance/gates/stage-completeness/`

## Scope guard

> **⟹ PARTIALLY SUPERSEDED (§B.4–B.5).** "Do not start the proto edit until the
> migration-status question is answered" over-couples the concerns. The
> migration question is resolved (§B.5) AND the proto edit (Part 1) is
> migration-*independent* — correct in 3 of 4 branches. Only the FE-consumer
> migration (Part 2) was ever roadmap-gated. The "fresh budget" advice below
> still stands.

This is wire/governance precision work. Tempdoc 549's own closure note recorded that this area
caused three mis-diagnoses at high context depth ("proto-derived → Java-record → frozen-barrel")
and is best done with fresh budget. Verify the **[I]** claims first; do not start the proto edit
until the migration-status question (§"critical open question") is answered.
