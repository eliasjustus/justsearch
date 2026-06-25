---
title: "553 — Preventing search-execution representation drift: the canonical record, governed projections, and the limits of prevention"
type: tempdocs
status: done
created: 2026-05-27
updated: 2026-06-10
category: substrate-design / search-quality / observability / wire-contract
related:
  - tempdoc 517 (search-execution-design — SearchDecision/SearchOutcome value model; ADR-0014) — THE ROOT
  - tempdoc 549 (unified-search-trace — SearchTrace) — the explain projection
  - tempdoc 551 / 552 (wire-contract gap + FE barrel migration) — the wire projection instance
  - tempdoc 530 / 531 (discipline-gate kernel + consumer-drift) — home for the gate (pillar c)
  - tempdoc 511 / 525 (aggregate-surfacing substrate / search-introspection) — the rendering layer
  - tempdoc "553-code-duplication-audit" (548-followups worktree) — SIBLING / number collision; the empirical view of this same defect class
  - ADR-0014 (pipeline-definition-removal) ; ADR-09/09a (wire contract substrate) ; ADR-0027/0029 (telemetry contracts)
  - docs/reference/contracts/search-execution-spans.md ; docs/explanation/08-observability.md
---

# 553 — Preventing search-execution representation drift

> **What this document is.** A theorization of the *correct long-term structure* for "what the search
> pipeline did," **and an honest account of what preventing its drift can and cannot achieve.** It
> deliberately omits feasibility, phasing, and file-by-file steps for the structure (§1–§4, §8 are
> design); §5 is the realism counterweight — a fundamental-limits argument, not a design. Major
> refactors are allowed. Markers: **[V]** = verified against source; **[I]** = inferred / judgment.

## 0. Umbrella — SearchTrace instance tracker (551 / 552 / 553)

> **This doc is the coordination home for the whole SearchTrace family.** 551 and 552 are the two
> wire/FE *instances* of this doc's principle; rather than track their remaining work in three places,
> all of it lives here. 551 and 552 carry closure headers pointing back to this section and are not
> independently active. (Status reconciliation 2026-06-10, verified against source — see the citation
> column.) **[V]**

| Instance | Scope | State (2026-06-10) | Verified by |
|---|---|---|---|
| **551** — wire-contract gap | Promote `SearchTrace` into the gated `contracts/wire/knowledge.proto` + a record↔proto conformance test (Part 1); FE consumer migration (Part 2). | **Part 1 DONE & merged. Part 2 superseded → see 552 row.** | `knowledge.proto:104` (`SearchTrace search_trace = 27`) + `:197` (`message SearchTrace`); changeset `contracts/wire/.changesets/551-add-search-trace.md`; `KnowledgeWireContractConformanceTest.java`. |
| **552** — FE barrel migration (551 Part 2) | Move FE consumers off the frozen `wire-types.ts` barrel onto a single generated authority; delete the trace from the barrel. | **Goal ACHIEVED by 564, not as written (superseded).** The `knowledge_pb` route this doc planned was *not* the path taken. | `wire-types.ts` deleted; FE imports `SearchTrace` from `api/generated/schema-types/search-trace.ts` (generated from `SSOT/schemas/search-trace.v1.json` by 564's `gen-wire-schema-types.mjs`). See `searchTraceExplain.ts:36`, `api/schemas.ts:141-143`. |
| **553** — the principle + the gate | One canonical execution record; every surface a governed projection; the `execution-surface` gate as the enforcement floor. | **Gate SHIPPED & live.** Doc remains the standing design + limits theorization (§1–§8). | `governance/execution-surfaces.v1.json`; `scripts/governance/gates/execution-surface/`; listed in CLAUDE.md pre-merge checks. |

### 0.1 Remaining work (reconciled by the §14 takeover audit, 2026-06-10)

The two *instances* (551/552) are closed, and — verified this pass — **553's structural agenda is also
largely shipped and live-enforced**, not design-stage. The "three still-parallel records" framing of
§2 is itself now resolved: the OTel `search/*` tree was converted to an OpenInference **projection**
(§13 Phase A), the 517 value model is the **root** (not a parallel copy), and the `SearchTrace` is the
governed cross-process projection. So the live surface is **not** a big unification build; it is the
small, specific residue the §14 audit found:
- **G-A** — the head-slice→trace superset conformance gap (the one real correctness hole; worker has it, head doesn't).
- **G-B/C/D/E** — gate-teeth proxy, register maintenance-tax honesty, the multi-record (559) identity reconciliation, and stale internal logs.

**See §14 for the full verification, the prior-art fact-check of §4–§5, and the priority-ordered
remaining-work list.** Track all of it here, not in 551/552.

## 1. Thesis

There must be **one authority over which execution/evidence records exist and who may project them**,
and for **each** such record **exactly one canonical representation** from which every surface that
needs those facts is a **governed projection**, never an independent model of it. For search-execution
that record is `SearchTrace` — human "explain," observability spans, eval, learning-to-rank features,
AI narration, and the external wire contract are all its projections. A **sibling** record is admitted
only when it shares *no* reason to change with the first (the 559 `ContextCitation` RAG-evidence record
is the worked example: registered *beside* `SearchTrace`, not merged into a mega-record — see §6 AHA
and §14.5 G-D). When a surface models execution/evidence independently, the views drift silently,
because nothing ties them together.

> **Wording note (§14 reconciliation, 2026-06-10).** The original thesis said "*exactly one* canonical
> record." 559 Authority IV generalized the register to a **multi-record authority** (SearchTrace +
> ContextCitation siblings) — so the invariant is one level up: *one authority over the record set*,
> with single-source-per-record beneath it. This is a vindication of §6, not a retreat from DRY.

## 2. The defect class — this is the DRY principle, not a new idea

This is **DRY** verbatim (Hunt & Thomas, 1999): *"every piece of knowledge must have a single,
unambiguous, authoritative representation."* The literature names the failure mode it prevents —
**"representation drift"** — and calls divergence between copies *a fault*. The issue is reported to
the maximum; this doc applies a foundational, 25-year-old principle to a domain where the repo's own
discipline lapsed. The recurrence proves it: 549 found the *explainability* representations fragmented
across five forms; 551 found the *wire contract* had drifted; and a span scan found the OTel
`search/*` tree is a *third* parallel record of the same execution. Three incidents, one defect class.

## 3. What already exists (extend — do not greenfield)

The pieces are here; they are simply not unified under one source + one governance. **[V]**

| Existing | Today | Role in the structure |
|---|---|---|
| **517** `(SearchDecision, SearchOutcome)` + ADR-0014 | The worker value model. `SearchOutcome` already carries timings the trace drops (`chunkBm25Ns`/`chunkKnnNs`/…). | **The root / source** — already the richest representation. |
| **549** `SearchTrace` | Projected from `(decision, outcome)` + head stages. | **The human-explain projection** — one view, not special. |
| Telemetry spans (ADR-0027/0029, `search-execution-spans.md`) | A separately-authored OTel span tree of the same execution. | **The observability projection** — stops being parallel. |
| **530/531** discipline-gate kernel | Governance kernel + consumer-drift gate (a fitness-function system). | **Home for the gate** (pillar c). |
| Contract substrate / ADR-09(a) | "One proto → many emitters (Java/TS/Zod/JSON-Schema)." | **The precedent** — this is that philosophy applied to the execution-record domain. |

## 4. The correct structure — three pillars

- **(a) The canonical ExecutionRecord (the single source).** A complete, typed record of one query's
  execution, rooted in / extending the 517 value model. *Complete* (a superset of every projection —
  the lossy asymmetry where `SearchOutcome` holds more than `SearchTrace` is the intended direction).
  *Internal, not the wire* (the wire is a downstream gated projection, not the master). *Composed
  cross-process from typed contributions* (worker slice + head slice — generalizing 549's "two
  contributors, one schema" into a first-class composition contract, replacing today's ad-hoc merge).
- **(b) The projection layer — views, not authors.** Each surface is a pure function of the record,
  lossy only downward: explain-trace; **OTel/OpenInference spans** (retriever + reranker conventions →
  free Jaeger/Tempo/Phoenix interop; the mapping is ~1:1); eval read-model; LTR feature log (closes
  the loop on the LambdaMART stage it describes); AI narration; the gated wire contract (551/552 are
  this instance).
- **(c) The anti-fragmentation gate — an execution-surface register on the 530 kernel.** Registers
  every surface allowed to describe execution; **fails the build** when a surface models execution
  independently or a registered projection's field set drifts from the record. Generalizes 551's
  record↔proto conformance test + 549's `stage-completeness` into one rule. *Within its declared
  scope* this makes the fragmentation class structurally hard to re-enter — but it does **not** prevent
  duplication in general (§5).

## 5. What prevention can and cannot achieve (the honest core)

**Total prevention is impossible — for fundamental reasons, not tooling gaps.** The defect needs three
things true at once: knowledge is **(1)** needed in multiple places, **(2)** *authored* more than once,
and **(3)** able to change independently. (This tripartite framing is this doc's own synthesis, not a
named theorem; note **(2)** is harmful precisely *because* **(3)** — they interact. **[V]** §14.3.)
Prevention means killing one structurally:

- **Kill (3) — copies that provably can't drift.** Equivalence-check the copies on every change. Works
  for data shapes (conformance tests). For arbitrary logic this is *program equivalence*, **undecidable
  in general — a corollary of Rice's theorem** (equivalence is a non-trivial *semantic* property; it is
  not something undecidability "reduces to" — the §14.3 correction). Restricted/approximate analyses
  (abstract interpretation, SMT, model checking on finite-state) stay decidable and useful; what's ruled
  out is a *complete, general* checker. Dead end for the general case. **[V]**
- **Kill (1) — don't need it in many places.** One home, everyone calls it. But cross-process / cross-
  language **boundaries force** the same knowledge onto both sides (you can't share an object between
  Head and Worker), and you can't make future code call the one home unless it *knows it exists*. So
  (1) reduces to generation or to the discovery problem.
- **Kill (2) — authored once, everything else *generated*.** The only true preventer: if humans never
  write the second copy because it's derived, drift is impossible by construction. So the whole problem
  collapses to: *what stops us generating everything from one model?* — and there are two walls.

**Wall 1 — the discovery problem (the bedrock).** To generate a concept from one source you must first
*know it is one concept*. The only complete routes are both walls: **detect it** (semantic /
program-equivalence detection — undecidable in general per Rice; type-4 *semantic* clone detection has
**no robust general solution**, and LLMs specifically are weak at it — GPT-4 recall ≈0.23 vs ≈0.94 for
purpose-built learning-based detectors on BigCloneBench Type-4 **[V]**, arXiv 2407.02402), or **model
it** (a complete canonical model of every concept — which is
*itself* a single source of truth that can drift from the code; the problem recurses one level up).

**Wall 2 — not all knowledge is projectable.** Data shapes generate cleanly into types/validators/wire
(mature). Arbitrary *behavior* does not reduce to a declarative source you can generate every call-site
from. Some logic does (validators-from-constraints, parsers-from-grammars, state-machines-from-specs);
most business logic does not. So even with perfect discovery, much shared knowledge can't be lifted
into a generatable model.

**Therefore prevention is asymptotic, never total.** The theoretical endpoint is **model-driven
development at the limit** — every shared concept modeled once, every artifact (including both sides of
every boundary) generated from it, hand-authoring of a copy made structurally harder than referencing
the model. JustSearch already does this in pockets (the wire contract generates from one proto; the
value model is decisions-as-values; the gate kernel is fitness functions). But the **irreducible
residue** — novel logic, and the *first* expression of a not-yet-modeled concept — can neither be
generated (it isn't in the model yet) nor reliably detected (semantic equivalence). For that residue
there is only detection-plus-review: reduction, not prevention. The gate (pillar c) guarantees the
*declared* class (~100% within scope); it cannot find an undrawn line.

## 6. Non-goals — the AHA guardrail (do not over-unify)

"Prevent *all* duplication" is the wrong target. The counter-literature is emphatic — Metz:
*"duplication is far cheaper than the wrong abstraction"*; Dodds: **AHA, "Avoid Hasty Abstractions."**
Forcing a single source on things that don't share a reason to change yields a worse artifact (a
"shared" function that accretes flags until it's confusion in a costume). The discriminator is one
question: **"do these share one reason to change?"** The execution-record surfaces pass by construction
(they all describe the same execution). Explicit non-goals: the gate registers *execution-fact
surfaces*, not all similar-looking code; regular-shaped scaffolding (catalog/handler stamps, JMH
harnesses) stays duplicated; the goal is preventing **drift of coupled knowledge**, a strict subset.

## 7. The realistic approach — defense-in-depth (reduction, not elimination)

No single mechanism is complete; layered, they shrink the residue:
- **Structural gate on the declared core (pillar c)** — ~100% within scope; the recurrence-prone class
  (549/551 were exactly this).
- **Token-clone tripwire** (jscpd/CPD in CI — the sibling 553's tool) — broad mechanical copy-paste,
  with noise. A tripwire, not a guarantee.
- **Periodic semantic audit** (the sibling 553's Part B; human/LLM) — the unsolved class, reactively.
- **Authoring-time discovery — the realistic frontier.** Surface "this concept already exists,
  reference it" *at the moment of writing*. An LLM coding agent with whole-repo context is both the
  *cause* (agents happily regenerate existing logic) and the potential *oracle* (check new code against
  the model before accepting it). Still approximate, but it attacks discovery at the only point that
  matters — before the second copy is born.
- **Code review + DRY/AHA judgment** — the irreducible human layer.

**The honest cost ledger (§14.5 G-C).** Defense-in-depth is not free: the structural gate trades the
*silent-fork* risk for a *loud-orphan-churn* tax. Because the register is a hand-curated allowlist, a
rename/move/delete of a registered surface fails the build until the register is reconciled — and in
practice this has been caught **reactively** (a red build / post-merge), not at the edit: the 556
`SearchExecutor→KnowledgeSearchEngine` rename moved head-span emission off the declared surface, and
561's view deletion left `AskView.ts` registered as a dangling orphan that failed `execution-surface`
for days (observations #296/#334/#341; the latter cleared by this campaign's P0). This is the right
trade — a loud, mechanical, locally-fixable failure beats a silent divergence — but it is a real
maintenance cost the register owner pays, and it belongs in the ledger, not elided. (Parity with §5's
honesty about the gate's *scope* limit: §5 names what the gate can't *see*; this names what it *costs*.)

## 8. First concrete change (the crystallized starting point, if/when authorized)

The full source-unification is large; its **load-bearing, already-feasible core is pillar (c) — the
gate** — because it is the part that *prevents recurrence of the declared class* and JustSearch already
owns the machinery (the 530 kernel; working precedents in 551's conformance test and 549's
`stage-completeness`). Intended first change: **add an "execution-surface register" fitness-function
gate** that (1) lists every surface allowed to describe "what the pipeline did," and (2) fails the
build if a new surface models it independently or a registered projection drifts from the record. A
thin, structural generalization of two checks that already pass — not the big refactor. Source-
unification (pillars a/b) then proceeds incrementally *behind* the gate. **This stops a fourth fork
from shipping silently; it does not, and cannot, prevent the undeclared/semantic residue (§5).**

## 9. Relationship map
- **Root:** 517 (`SearchDecision`/`SearchOutcome`, ADR-0014).
- **Explain projection:** 549. **Wire projection:** 551 (Part 1 shipped) / 552 (Part 2, opt-in).
- **Observability projection:** ADR-0027/0029, `search-execution-spans.md`, `08-observability.md`.
- **Gate home / precedent:** 530/531 ; ADR-09(a).  **Rendering layer:** 511/525.
- **Sibling — the empirical view of this same defect class:** `553-code-duplication-audit` (in the
  `548-followups` worktree; **number collision** — one must renumber on merge). Its #1 finding (the
  `AgentEvent → wire-payload` mapping authored in 3 places, already drifted) is this exact pattern in a
  non-search domain → the principle generalizes. Both docs converge on §7's conclusion: *drift should
  fail the build, not wait for an audit* — its "extract + pin" remediation and this doc's "source +
  projections + gate" are the reactive and structural halves of one capability.

## 10. Open design questions (design-level; feasibility/migration out of scope)

### 10.0 RESOLVED — Record locality + whole-serialization (the goal's load-bearing decision, 2026-05-27)

**Decision: the canonical record is DISTRIBUTED per-process — each process's typed value-model slice
is the local source for its own projections; the composed `SearchTrace` is the canonical
cross-process projection that downstream surfaces read. There is no single materialized whole record.**

This is **forced, not chosen**: a single head-assembled record would require shipping the worker's
complete slice over gRPC, but `SearchOutcome` holds **process-local Lucene runtime objects** —
`LuceneRuntimeTypes.SearchResult result` and `org.apache.lucene.search.Query queryForSpans` (verified
in `modules/worker-services/.../SearchOutcome.java`). Those cannot/should not cross the wire, so a
"whole" serialized record is structurally infeasible. Any wire-crossing form is necessarily lossy —
i.e. a *projection* — which is exactly 553's "the wire is a downstream projection, not the master."

**Therefore pillar (a) is realized as a contract, not a new object:**
- **Worker canonical slice** = `(SearchDecision, SearchOutcome, SearchInputs)` (tempdoc 517) — the
  local source for all worker projections (worker trace stages via `SearchTraceProjector`, worker
  `search/*` OTel spans).
- **Head canonical slice** = the head's execution inputs/results — the local source for head
  projections (head trace stages via `buildHeadStages`, head spans, the 4a span projection).
- **Cross-process canonical projection** = the composed app-api `SearchTrace` (worker stages +
  head stages), which every *downstream* surface (eval, LTR, narration, wire, FE explain) reads.

**Pillar (b) then means:** no surface authors execution facts independently — each derives from its
process's slice or from the composed trace. The structural work is to (1) verify/close any surface
that computes facts independently rather than projecting (esp. worker `search/*` span status), (2)
add record↔projection conformance tests, (3) extend the execution-surface gate to enforce
*structural* derivation (the surface reads the slice/trace; shares the one vocabulary) — noting the
honest §5 limit that semantic purity ("is a pure function") is not mechanically decidable, so the
gate enforces derivation + vocabulary-closure, not provable purity.

### Remaining (genuinely design-level)
- **Whole-serialization** — RESOLVED above: projection-only by necessity (Lucene-locality).
- **Vocabulary ownership** — who owns the closed stage/decision vocabulary as it grows.
- **Register granularity** — per-surface vs. per-field; drift detected via descriptor diff (551,
  generalized).
- **Migration sequencing** — explicitly out of scope; this is the target structure + the honest limits.

## 11. Implementation log + remaining work (branch worktree-execution-surface-gate)

**SHIPPED (verified, committed on the branch):**
- **Phase 1 — keystone:** `governance/execution-surfaces.v1.json` register + the `execution-surface`
  discipline gate (enforcer/truth-table/rule-descriptions + registry entry + fixtures). Guards the
  declared seam: an unregistered production referencer of the canonical `SearchTrace` fails the build;
  orphan paths + dangling guards fail. Meta-coordinator (delegates conformance to stage-completeness /
  wire / the 551 test). Self-test green; full governance suite green (only pre-existing `ts-any`).
- **Phase 2a — number-collision check** (`scripts/ci/check-tempdoc-numbers.mjs`): cross-worktree
  divergent-claim detection. Found two REAL collisions (552, 553) with the `548-followups` worktree.
- **Phase 3 — workflow:** discovery checkpoint in CLAUDE.md `explore-before-implementing` + wired the
  number-check into the pre-merge list.
- **Phase 4a — OTel span projection (bounded step):** `SearchTraceSpanProjection.attributesOf(trace)`
  (pure helper) projects the canonical trace onto the root `search` span; applied in
  `KnowledgeHttpApiAdapter.search()`. The "unverifiable span output" blocker was dissolved by making the
  projection a pure function unit-tested directly (`SearchTraceSpanProjectionTest`) — no live backend.
  Registered as the `otel-span-projection` surface. Full OpenInference per-stage retriever/reranker
  span *structure* on the worker `search/*` tree remains the larger follow-up (`otel-spans-worker`).

**REMAINING — specific blockers (per the /goal discipline: name the blocker, not a generic deferral):**
> **⟹ BOTH ITEMS SINCE SHIPPED — this list is dated history (reconciled 2026-06-10, §14 G-E).**
> **Phase 2b** (clone tripwire) shipped as **§13 Phase E** — the kernel-native `clone` gate (the
> "clean path" below was chosen: a ratcheting baseline mirroring `ts-any`/`class-size`, no new dep).
> **Phase 4b** (FE migration) shipped as **§13 Phase D**, then its `knowledge_pb` mechanism was
> **superseded by 564** (proto→Zod; the FE is proto-free for SearchTrace — see §13 Phase D's marker
> and §14.1). Neither is open work; read the two bullets below as the blocker-analysis at the time.

- **Phase 2b (clone tripwire gate):** BLOCKER — `jscpd`/CPD is not installed and there is no CPD gradle
  task; shipping it requires adding a heavy devDependency for a *reduction-grade*, ratcheting tripwire
  whose high-value subset is already covered by the keystone. Decision: defer (prefer the structural
  keystone over a noisy reduction-gate + new dep). Clean path (better than a new npm dep): add a **CPD
  gradle task** (PMD is already a Gradle dependency) + a ratcheting-baseline `clone` gate (mirror
  `ts-any`/`class-size`).
- **Phase 4b (552 FE barrel→knowledge_pb migration):** SCOPED + READY, but a focused effort — NOT
  unsupervised-batch work. Evidence-based shape: `knowledge_pb`'s
  `SearchTrace` is a *branded* `Message<"justsearch.wire.v1.SearchTrace">` type (knowledge_pb.d.ts:573),
  so plain JSON from the REST response is NOT assignable: a type-only re-point (path B) fails typecheck.
  Path A (`fromJson(SearchTraceSchema, raw.searchTrace)` at the mapper boundary, producing real
  Message instances) is the only viable route. Measured blast radius: **~12 TS files** reference the
  trace types (`domains/search.ts` + its `SearchResponse` type, `schemas.ts`, `types/selection.ts`,
  `aggregateKinds.ts`, `bootstrap.ts`, `JfSearchTrace.ts`, `searchTraceExplain.ts`, `plugin-types.ts`,
  `searchState.ts`, `SearchSurface.ts`) — all must move off the barrel before the trace types can be
  deleted from `wire-types.ts` + `index.ts` — plus FE test fallout, plus a **runtime risk** (does
  `fromJson` accept the live backend JSON?) that only live/browser validation closes. USER-VISIBLE →
  browser validation is the success bar (dev stack + `ai_activate` + worktree vite on :5175). *Opt-in*
  per capability-vs-mandate (552). **Deliberately NOT done in this unsupervised batch** — a 12-file
  user-visible cascade whose only real verification is the live UI is a focused effort with the dev
  stack up, not tail-of-session work. Scoped + ready; owned by 552.

**Coordination flag:** the number-check surfaced that `548-followups` independently used **552 and 553**
for unrelated docs. Both collide on merge; one side must renumber (a coordination call — I did not touch
the other worktree). The committed `check-tempdoc-numbers.mjs` will catch this at pre-merge.

## 12. Structural-core campaign (`worktree-553-core`, /goal — pillars a + b)

Implemented the structural core under a `/goal` run. The key intellectual output is the
**record-locality decision (§10.0)**: a single head-assembled whole record is structurally
infeasible (SearchOutcome holds process-local Lucene objects), so the canonical record is a
**distributed per-process contract** — each process's value-model slice is the local source for its
projections; the composed `SearchTrace` is the canonical cross-process projection. Pillar (a) is
therefore a contract that already exists (517 value model + the projectors), not a new object.

**Shipped on `worktree-553-core` (build + full unit suite + FE typecheck/2206 tests + full governance
suite all green; `ui-bundle` is the documented pre-existing exception):**
- **§10.0 record-locality decision** (the load-bearing design).
- **Gate projection-purity (pillar b, structural):** the `execution-surface` gate now requires every
  `kind: projection`/`producer` surface to name a real derivation guard (a conformance `test:`/`gate:`,
  not `self`) — so a projection can't silently start authoring facts independently. `.ts` test guards
  resolve. Self-tested (negative fixture). `projection-pending` is the exempt not-yet-converged kind.
- **4a-worker:** `WorkerSpanProjection` projects the worker trace slice onto the active worker span
  (applied in `SearchResponseBuilder`); pure helper, unit-tested. The worker telemetry now projects
  the record. Registered as `otel-worker-span-projection`.

**Projection-status matrix (every surface derives from the record / its slice):** explain trace
(549) · wire contract (551, conformance test) · head OTel span (4a-head, on main, unit-tested) ·
worker OTel span (4a-worker, this campaign, unit-tested) · eval/jseval (549, tests) · LTR (549) ·
narration (549). All are registered + guarded; the gate enforces it.

**Verification status (the /goal's live-tier — DONE, all three modalities confirmed):**
- Code tier: build + full unit + FE typecheck/2206 tests + full governance suite — **green** (above).
- **Live-stack run** (the `start` tool builds+runs the worktree's own code; preflight READY; AI
  activated; query `"search execution trace"`):
  - **Query-level `SearchTrace` projects end-to-end** (curled `/api/knowledge/search`):
    `effectiveMode=TEXT`, `decisionKind=sparse_shortcut`, **all 12 stages composed from BOTH processes**
    (worker correction/sparse/dense/splade/fusion/chunk-merge/branch + head query-understanding/
    expansion/lambdamart/cross-encoder/freshness) + per-hit `trace` (HitStage) on every result.
  - **(a) Browser UI render — CONFIRMED** (drove the real UI via the page's shadow DOM): the explain
    panel rendered live with headline **"BM25 search · TEXT"** (derived from decisionKind+effectiveMode),
    the QPP line (`maxIdf=4.88 · avgIctf=7.96 · queryScope=0.05`), and **all 12 stage chips** with
    status/reason/ms (`Sparse (BM25): executed`, `Chunk merge: executed · 18ms`, …) — rendered from the
    record. (The initial a11y-tree read returned empty for the Lit shadow DOM; driving via the page's
    own shadow-DOM traversal worked.)
  - **(b) OTel spans carry record-derived data — CONFIRMED** at the tracing-on tier the live stack can't
    show (dev stack runs `tracing_level=none`): the SDK recording-tracer test
    (`SearchExecutorOtelTopologyTest#workerSpanCarriesRecordDerivedAttributes`) runs a full real search
    and asserts the request span carries `justsearch.search.worker.effective_mode=TEXT` +
    `stage.sparse-retrieval.status=executed`. Head-side is `SearchTraceSpanProjectionTest`.
  - **(c) eval/LTR read the record — CONFIRMED live**: ran jseval `extract_query_evidence(response)`
    (reads SOLELY `response.searchTrace`) on the live response → `effective_mode=TEXT`,
    `chunk_merge_reason=SKIPPED_QUERY_SYNTAX`, … ; `extract_hit_evidence(hit)` → `sparse_rank=15`,
    `sparse_score=1.74236` from `hit.trace`. LTR/GPL reads the same trace (qpp/detail) server-side; the
    trace is the live source (GPL byte-equiv guard verifies its read).
  - **All from the one source** — the `SearchTrace`, projected from the `(SearchDecision, SearchOutcome)`
    value model.

## 13. Correctness-gap closure campaign (`worktree-553-core`, follow-up — pillars a/b/c hardening)

A critical ideal-vs-actual re-read (judging against the §1/§4 goal, not the feasibility excuses) found
the gate was real but the thing it guards was only partly built. This campaign closes those gaps.

**Phase A — the worker `search/*` span tree is now an OpenInference projection (SHIPPED).** The
founding "third parallel record" (§2) is closed: `OpenInferenceSpanProjection` (worker `execute` pkg,
pure helper) projects each leg/fusion `SearchResult` onto `openinference.span.kind` +
`retrieval.documents.*` / `reranker.output_documents.*` (id + score + bounded content), applied at all
six `SearchExecutor` span sites. Existing scalar attrs kept for the documented Layer-4 consumers +
topology. Verified by `OpenInferenceSpanProjectionTest` (pure) + a live recording-tracer assertion in
`SearchExecutorOtelTopologyTest` (the correct tier — the dev stack runs tracing-off). The
span-privacy contract was **amended by explicit owner decision** to allow per-document
id/score/content on spans (already on the wire `Hit.trace`); query text + filter values still
excluded. Register: `otel-spans-worker-structure` → `projection`, guard
`test:OpenInferenceSpanProjectionTest`.

**Phase B — the gate now catches the fork class that motivated it (SHIPPED).** New Check 5
(`undeclared-vocabulary-fork`): any Java-main file emitting a `search/*` span-name literal must be a
registered surface — widening detection beyond Check 1's canonical-TYPE import to the span tree that
re-models execution *without* importing `SearchTrace`. Honest §5 limit: string-literal heuristic
(reduces, not eliminates). The check immediately surfaced a real unregistered emitter
(`CrossEncoderReranker`'s `search/rerank`), now registered. Self-tested (positive + negative fixtures;
the negative fires `undeclared-vocabulary-fork` for the right reason).

**Phase C — projection purity is enforced as conformance, and the superset is proven (SHIPPED).**
Two closures: (1) `SearchTraceProjectionConformanceTest` reflects over every `SearchOutcome` record
component and asserts each is classified REPRESENTED (reflected in the trace) or DELIBERATELY_DROPPED
(in the slice, deliberately absent from the query-level trace) — a new field that's neither fails,
forcing project-vs-drop instead of silently widening the slice↔trace gap. This is the G1 "the slice
is the superset, lossy-downward is enforced not asserted" closure. (2) Gate Check 6
(`non-conformance-guard`): a projection/producer surface guarded only by test(s) must name a
*conformance* test (Conformance/Projection/searchTrace) or a `gate:` — not an arbitrary unit test
(G4). Honest §5 limit: the naming convention is a proxy for the undecidable "is a pure projection",
forcing an auditable claim, not a proof. Self-tested (the negative fixture fires
`non-conformance-guard` for the right reason).

**Phase D — the FE trace types migrate to `knowledge_pb` (552 Part 2, SHIPPED + browser-validated).**
> **⟹ SUPERSEDED by tempdoc 564 (2026-06-10 reconciliation).** This phase's specific *mechanism* — FE
> `SearchTrace` as a branded `knowledge_pb` `Message<…>` consumed via `fromJson` at the parse boundary —
> was **reverted by 564 Phase 3** ("SearchTrace proto→Zod — the FE is now proto-free", commit
> `291cda6e6`). The *goal* survived (FE off the frozen `wire-types.ts` fork, onto one generated
> authority); 564 delivered it via a record→JSON-Schema→Zod projection instead of protobuf-es. Current
> truth: the FE imports `SearchTrace` from `api/generated/schema-types/search-trace.ts` and validates
> the raw trace against that generated Zod in `searchState` — see the register's `fe-generated-schema-trace`
> surface. Read the rest of this phase as dated history of the route taken before 564 landed.

The frozen `wire-types.ts` snapshot of `SearchTrace`/`TraceStage`/`TraceQpp`/`TraceDegradation`/
`HitStage` is retired; the barrel now re-exports them from the protobuf-es generated `knowledge_pb`
(one proto → these types), eliminating the wire-types fork. These are branded `Message<…>` types, so a
pure swap fails typecheck; the raw REST JSON is converted to real instances via
`fromJson(SearchTraceSchema/HitStageSchema, …, {ignoreUnknownFields:true})` at the single `searchState`
parse boundary. Code tier: typecheck clean + all 2206 FE unit tests green; execution-surface gate
green. **Browser-validated (the success bar):** ran the worktree's own vite (:5175, serving the
migrated FE — the dev-stack vite serves main, so a separate server was required and the distinction was
caught by interrogating a false-positive render) against the live backend; a real search produced an
explain panel rendering the headline "BM25 search · TEXT", the QPP line, and all 12 stages with
status/reason/ms — and the element's `trace` is a genuine branded `knowledge_pb` Message
(`$typeName="justsearch.wire.v1.SearchTrace"`, nested `TraceStage` branded), confirming the fromJson
conversion is the live feed (not a structural type-lie). The real backend trace converts cleanly
(12 stages + per-hit 3-stage `HitStage[]`).

**Phase E — clone tripwire gate (SHIPPED, reduction-grade).** A `clone` discipline gate on the 530
kernel: a per-file ratchet on duplicated code blocks (sliding window of 8 significant normalized
source lines; a window is a clone if its text recurs at another non-overlapping location). Mirrors
`ts-any` exactly (TSV baseline, changeset-classified growth, rebalance-on-shrink). Baseline seeded
from current state (180 files / 2305 cloned windows — the ratchet floor); growth above it fails
without a `declared-growth` changeset. Honest §7 scope: a coarse line-based TRIPWIRE (CPD is
token-based + finer), noisy by design, and it deliberately does NOT guard the declared
execution-surface class — the keystone does. Implementation note: a kernel-native node detector was
chosen over the planned PMD/CPD Gradle task — `pmd-cli` isn't cached and a `cpd` configuration would
add lockfile churn across modules for a coarse signal; the node detector fits the kernel's
source-scanning ratchet model (like `ts-any`/`class-size`) with zero new deps. Self-tested (negative
fixture fires `clone/silent-growth` for the right reason).

**Head-side OpenInference symmetry (SHIPPED).** The head reranker spans
(`search/cross_encoder`, `search/lambdamart` in `KnowledgeHttpApiAdapter`; `search/rerank` in
`CrossEncoderReranker`) are now OpenInference RERANKER projections carrying the reranked output docs
(id + score + content), mirroring the worker. The key intellectual output is **de-duplication**: the
OpenInference key vocabulary + bounded document encoding moved into ONE shared projector,
`telemetry.OpenInferenceSpans` (generic `Doc(id, score, content)`), which the worker adapter, the head
spans, and the reranker all delegate to — so there is no per-module fork of the OpenInference
vocabulary (the exact 553 anti-fork thesis, applied to the projector itself). `reranker` gained a
`telemetry` dep (lockfiles regenerated). Register: `otel-spans-head-reranker` → `projection`, guard
`test:OpenInferenceSpansProjectionTest`. Build + full governance green (only pre-existing `ui-bundle`).

## 14. Takeover analysis — independent verification + critique (2026-06-10)

> **Who/why.** New agent, takeover. Per `independent-reviewer-required` (slice-execution.md: the
> reviewer ≠ the implementer), this is a second-pass audit of §11–§13's shipped claims plus a
> prior-art fact-check of the §4–§5 theory. Method: (1) ground-truth the merged tree against the logs
> (the logs are *hypotheses* until verified — `audit-without-test`); (2) read the gate enforcer to see
> what it **actually** checks vs. what the prose claims; (3) two web-research passes (OpenInference/OTel
> conventions; the §5 computability claims) with adversarial verification. Markers: **[V]** verified
> against source/citation this pass; **[I]** inferred.

### 14.1 Ground truth — the structural agenda is shipped and largely real **[V]**

`worktree-553-core` merged (`e73cbff26`); **every** artifact §11–§13 names exists in the tree and the
load-bearing one genuinely bites:

| Claim (§11–§13) | Verified state |
|---|---|
| Pillar (c) gate + register | **Real.** `execution-surface` enforcer runs 6 checks (below); register `execution-surfaces.v1.json` lists 40+ surfaces. |
| Worker slice→trace superset conformance (Phase C) | **Real teeth.** `SearchTraceProjectionConformanceTest` reflects over `SearchOutcome.getRecordComponents()` and fails on any field that is neither REPRESENTED nor DELIBERATELY_DROPPED. This is the one place the "lossy-downward superset" is *mechanized*, not asserted. |
| Worker `search/*` as OpenInference projection (Phase A) | **Real.** `OpenInferenceSpanProjection` + shared `telemetry.OpenInferenceSpans`; `SearchExecutorOtelTopologyTest` asserts via a recording tracer (correct tier — dev stack runs tracing-off). |
| Span-fork Check 5 + conformance-guard Check 6 | **Real,** with the limits in §14.2. |
| Clone tripwire (Phase E) | **Real.** Kernel-native `clone` gate, ratcheting baseline. |
| **§13 Phase D — FE migrated to `knowledge_pb`** | **STALE / reverted.** 564 (`291cda6e6` "SearchTrace proto→Zod — the FE is now proto-free") replaced the branded-`Message`/`fromJson` route with a JSON-Schema→Zod generated type. The register's `fe-generated-schema-trace` note is now authoritative; §13 Phase D describes a mechanism that no longer exists. (A residual stale comment at `searchState.ts:91` still cites "fromJson (553 Phase D)" — logged to observations.) |

**Conclusion: 553 is not "open work waiting to start."** Pillars (a)/(b)/(c) are implemented, merged,
and live-verified. The takeover value is therefore *not* more building — it is correcting the doc's
stale internal logs, sharpening the theory against the literature, and naming the **real residual
gaps** the implementer's self-validation didn't surface.

### 14.2 What the gate *actually* enforces — an allowlist + naming-proxy, not a conformance verifier **[V]**

Reading `enforcer.mjs`, the six checks are:

1. **Undeclared surface** — a Java-main file with `import <canonical type>` or a TS file matching `\bSearchTrace\b|\bRetrievalCitation\b` must be in the allowlist. *Text/import scan.*
2. **Orphan** — registered path must still exist.
3. **Dangling guard** — `gate:<id>`/`test:<Name>` must resolve (a gate id; or a **test file whose name matches** — not that the test asserts anything).
4. **Unguarded projection** — `kind: projection|producer` must name a non-`self` guard.
5. **Span-fork** — a Java-main file emitting a `"search/[a-z_]+"` literal must be registered. *String-literal heuristic.*
6. **Conformance-grade guard** — a projection's named test must match `/conformance|projection|searchtrace/i`. *Filename-regex proxy.*

The honest read: **the gate is a membership + naming-convention meta-coordinator.** It guarantees
"every surface that *references the canonical type or the span vocabulary* is on a human-curated
allowlist, and each names a guard whose *name* looks conformance-shaped." It does **not** verify that
the named guard proves projection ⊆ record — Check 6 is satisfied by a test *called* `…ProjectionTest`
that asserts nothing. §5 acknowledges this in principle ("derivation + vocabulary-closure, not provable
purity"); §14.5/G-B states the practical consequence the prose softens.

### 14.3 Prior-art fact-check of the §5 theory — mostly sound, three corrections **[V]**

Two adversarially-verified web passes (citations in the relationship map below). The §5 argument is
*directionally correct and unusually honest*, but three claims need tightening:

- **Rice's theorem framing is backwards.** §5 says semantic-equivalence detection "reduces to program
  equivalence / Rice's theorem." Program equivalence is a **corollary of** Rice (a non-trivial
  extensional property), not something you reduce *to*. And the practical caveat §5 omits: on
  *restricted* domains (finite-state, bounded, typed-pure) equivalence is decidable/tractable (model
  checking, SMT) — Rice only forecloses a **complete, general** algorithm. This strengthens, not
  weakens, §7: approximate detection is exactly what's on the table. *(Rice 1953; cf. Wikipedia
  "Rice's theorem" Limitations.)*
- **"Type-4 clone detection is an unsolved problem" is overstated.** The accurate split: *no robust
  general/cross-domain solution*, but purpose-built non-LLM graph methods (GNN/graph-matching, SEED)
  reach ~0.94 recall on BigCloneBench type-4. The doc's narrower claim — **LLMs specifically are
  weak** — is now strongly cited (GPT-4 recall ≈ 0.23 on BCB type-4 vs ≈0.94 for specialized tools;
  arXiv 2407.02402, 2511.01176). So §7's "LLM authoring-time discovery is approximate" is *well*
  founded; "unsolved research problem" should become "no robust general solution; LLMs weak."
- **The three-conditions decomposition (needed-in-many / authored-twice / can-change-independently)
  is the doc's own synthesis, not a named theorem** — and conditions (2)/(3) interact ((2) is harmful
  *because* (3)). Sound as a framing; present it as such, not as established result. (DRY/SSOT — Hunt &
  Thomas 1999; Meyer Single-Choice — corroborate the spirit.)
- **Validated as-is:** the MDD-at-the-limit ceiling (round-trip problem; expressiveness "escape to
  code"; the metamodel-is-itself-a-SSOT recursion) and the data-shapes-projectable / behavior-not
  split (better grounded in computability: any general behavior-spec language is Turing-complete, so
  behavior has no compact declarative projection). These are correct and well-supported.

Net: §5's **conclusion** — prevention is asymptotic, the gate guarantees only the declared class, the
residue is detection-plus-review — survives the fact-check intact. The corrections are about rigor of
the supporting claims, not the thesis.

### 14.4 Prior-art fact-check of §4(b) — the OpenInference projection **[V]**

- **The convention names §13 uses are exactly right** (`openinference.span.kind` ∈ {RETRIEVER,
  RERANKER,…}; `retrieval.documents.N.document.{id,score,content}`; `reranker.{input,output}_documents.*`).
- **Choosing OpenInference over OTel-GenAI is well-justified, not arbitrary:** OTel-GenAI (still
  "Development" status as of 2026) defines **no reranker** convention and only a partial retrieval one
  with no per-document schema. OpenInference is the only convention that actually covers this pipeline.
- **The "free Jaeger/Tempo/Phoenix interop" claim conflates two things and should be split:**
  Jaeger/Tempo give *transport* interop only (they display whatever attributes exist — true of *any*
  OTel span, no retrieval-aware UI). **Phoenix** gives *semantic* interop (it specifically consumes
  OpenInference RETRIEVER/RERANKER spans for RAG-eval views) — that half is genuinely valuable.
- **Stability risk, with an accidental hedge:** OpenInference semconv is sub-1.0 (0.1.x), no formal
  SemVer guarantee — a moving-ish target to build a *governed* projection against. But §13's
  de-duplication into one shared `telemetry.OpenInferenceSpans` means a convention rename is a
  **one-file** edit, which is precisely the right mitigation. Worth stating explicitly as a strength:
  the anti-fork move doubles as version-risk insulation. The deferred risk is OTel-GenAI later
  standardizing *different* retrieval keys, forcing dual-emission.

### 14.5 The real residual gaps (what the self-validation missed)

These are concrete, in-scope, and the honest answer to "what remains":

- **G-A — asymmetric conformance teeth (DOWNGRADED — see §14.7).** Only the **worker** slice has a
  reflective superset-conformance test; the **head composer** (`KnowledgeHttpApiAdapter`, head execution
  inputs → trace stages) is guarded *only* by `gate:stage-completeness` (stage **vocabulary**, not field
  **superset**). I first called this "the sharpest gap, a small mechanical fix" — **the de-risking pass
  refuted that.** There is **no head-slice record** to reflect over: `SearchTraceMapper.buildHeadStages(…)`
  takes **8 scattered loose parameters** assembled in `KnowledgeSearchEngine.search()`, not a typed
  record like the worker's `SearchOutcome`. So a worker-style reflective test has nothing to reflect over;
  porting it would require first **minting a `HeadExecutionState` record and refactoring the search hot
  path**. And the *risk* is lower than I claimed: the head's drift surface is the `HeadStage` enum
  (already exhaustively tested by `KnowledgeHttpApiAdapterHeadStagesTest`) **plus a compile-time parameter
  list** — a new head fact surfaces as a new param / enum value the compiler and the existing test catch,
  not as silent record drift. **§6 AHA argues against minting a record solely for test symmetry.**
  **Resolution: documented asymmetry, no head record minted** (owner decision; see §14.7). The honest
  residue is named, not papered over — which is the §5 discipline applied to this gap itself.
- **G-B — Check 6 is a name proxy.** "Conformance-grade guard" is satisfied by *filename regex*. Of the
  projection surfaces, exactly one (worker) names a test that actually reflects; the span projections
  name pure-helper pins that verify the projector's *output shape* but not that the span is the *sole*
  execution-fact author there, nor lossy-downward. The gate's "conformance" guarantee is therefore only
  as strong as the weakest named test. Honest mitigation: keep the proxy (it forces an auditable claim)
  but track which named guards are *reflective* vs *example* — the gate could require ≥1 reflective
  guard per record, not per surface.
- **G-C — register maintenance tax / reactive drift (observed, not theoretical).** observations.md
  items #296/#334/#341 show the register repeatedly went stale on renames/deletions
  (`SearchExecutor→KnowledgeSearchEngine` moved head-span emission off the declared surface; `AskView.ts`
  orphan after 561 deleted it) — each **caught reactively** (red build / post-merge), not at the edit.
  The gate trades silent-fork risk for loud-orphan-churn; that cost is real and should be named in §7's
  defense-in-depth ledger, not elided.
- **G-D — the register is now a *multi-record* authority; §1's wording lags.** 559 Authority IV
  generalized the register to hold **two** sibling records — `SearchTrace` (ranking "why") and
  `ContextCitation` (RAG evidence) — that share no field and never co-occur. This is a *vindication* of
  §6's AHA guardrail (they were explicitly **not** merged into one mega-record), but §1's "exactly one
  canonical record" now reads as imprecise: the invariant is "**one authority over which
  execution/evidence records exist and who projects them**," admitting N AHA-justified sibling records.
  §1 should be reworded to match what shipped.
- **G-E — stale internal logs.** §13 Phase D (knowledge_pb) is reverted by 564 (§14.1); §11's "deferred"
  Phase 2b/4b were since shipped (clone gate; FE migration-then-revert). The doc's own log is the kind
  of dated-history-presented-as-current that `tempdocs-are-dated-history` warns about — the §0 umbrella
  + this section are the reconciliation.

### 14.6 Verdict + proposed remaining work (no new tempdocs — all of it lands here)

**Verdict:** 553's thesis is sound, its structural implementation is shipped and (mostly) genuinely
enforced, and its §5 honesty about limits is — after the §14.3 corrections — *more* defensible than
the doc claimed, not less. It is one of the better-executed substrate slices. The residue is small and
specific, not a re-architecture.

Remaining, in priority order (each a blocker-named item per the /goal discipline, owned by **this**
tempdoc). **All items implemented under the /goal run of 2026-06-10 — outcome in §14.8:**
1. **G-A: head-slice conformance** — **DONE (downgraded).** De-risking refuted the "mechanical fix"
   framing (no head record; AHA against minting one). Resolution: documented asymmetry — see §14.5 G-A /
   §14.7 / §14.8. No code.
2. **G-E + §1/§13D: doc reconciliation** — **DONE.** §1 reworded (multi-record authority); §13 Phase D
   carries a `⟹ SUPERSEDED by 564` marker; `searchState.ts:91` comment fixed + observations item cleared.
3. **G-C: maintenance-tax ledger** — **DONE.** Added to §7 (the loud-orphan-churn cost; honesty parity with §5).
4. **G-B: reflective-guard-per-record teeth** — **DONE (shipped, not just "optional").** New gate
   **Check 7** (`missing-reflective-guard`) requires every canonical/sibling record to have ≥1 surface
   tagged `guardKind:"reflective"` (+ `recordId`). Register tags on `worker-projector` (SearchTrace) and
   `evidence-fe-projection` (ContextCitation); verdict fn + enforcer wiring + rule-descriptions + ±
   self-test fixtures. Self-test green; gate green. See §14.8.
5. **§5 wording fixes** — **DONE.** Rice-framing (corollary, not "reduces to"; restricted analyses stay
   decidable) and type-4 (no robust general solution; GPT-4 0.23 vs 0.94, arXiv 2407.02402) applied to §5.

Plus **P0 (prerequisite): DONE** — removed the stale `evidence-fe-ask-view` orphan (561's deleted
`AskView.ts`), turning `execution-surface` from pre-existing FAIL → green so Check 7 is verifiable.
None required branching to another tempdoc.

### 14.7 De-risking pass — confidence-building before implementation (2026-06-10)

> Per a user-directed "increase confidence / reduce surprises" step, I de-risked §14.6 with read-only
> investigation, a gate-baseline run, and primary-source citation checks (no implementation). It paid
> off: **the de-risking overturned the §14.5 "G-A is the sharpest gap" framing.** Outcomes:

**(1) G-A is REFRAMED — the §14.5 "small, mechanical, high-value" call was wrong. [V]** Two Explore
passes established there is **no head-slice record to reflect over**. `SearchTraceMapper.buildHeadStages(…)`
takes **8 scattered loose parameters** (`PipelineConfig`, `lambdaMartNs`/`crossEncoderMs` longs,
`lambdaMartApplied`/`…SkipReason`, `expansionApplied`/`…`, `crossEncoderApplied`/`…`, `QueryType`)
assembled from locals in `KnowledgeSearchEngine.search()` — not a typed record like the worker's
`SearchOutcome`. So a worker-style reflective conformance test has nothing to reflect over; porting it
would require **first minting a `HeadExecutionState` record and refactoring the search hot path**.
And the drift *risk* is lower than §14.5 claimed: the head's drift surface is the `HeadStage` enum
(already exhaustively tested by `KnowledgeHttpApiAdapterHeadStagesTest#everyHeadStageEmitsExactlyOneNode…`)
plus a **compile-time** parameter list — new head facts surface as new params/enum values the compiler
and the existing enum-exhaustiveness test catch, not as silent record drift. **§6 AHA argues against
minting a record solely for test symmetry.** → **Recommendation: downgrade G-A to an honestly-recorded
asymmetry; do NOT refactor the hot path.** (Confirmed separately: `stage-completeness` is vocabulary/enum
coverage only — `enforcer.mjs` Checks A/B — so the *premise* that the head composer lacks a field-superset
guard is correct; it is the *fix shape*, not the premise, that was wrong.)

**(2) G-B de-risked favorably. [V]** Both registered records already carry ≥1 **reflective** guard:
`SearchTrace` → `SearchTraceProjectionConformanceTest` (Java `getRecordComponents()` reflection) **and**
`assertFieldRoles<SearchTrace>` (TS compile-time `Record<keyof T,…>` totality in `searchTraceExplain.ts`);
`ContextCitation` → `evidenceProjection.test.ts` (runtime `Object.keys(FULL)` totality, "total projection"
header). So a "≥1 reflective guard per record" rule **codifies an invariant that already holds → it will
NOT turn the gate red.** Mechanism decision (sized against `truth-table.mjs`/`rule-descriptions.mjs`): add
an explicit **`guardKind: "reflective"` tag** to the relevant register surfaces + a new per-record Check 7
(every `canonicalRecord`/`siblingRecord` has ≥1 reflective-tagged guard) — explicit/auditable, beats the
brittle content-heuristic alternative. Bounded change (one verdict fn + enforcer wiring + 2 rule-desc keys
+ self-test fixture), low blast radius. Confidence ~55% → ~85%.

**(3) Gate baseline (attribution safety). [V]** Before any change: `execution-surface` **FAIL** — single
finding `orphan-surface: modules/ui-web/src/shell-v0/views/AskView.ts` (the view 561 deleted; observations
#341) — **pre-existing, not mine, and itself a live instance of G-C** (reactive register drift). `stage-completeness`
**PASS**. `clone` **FAIL** — 25 findings (pre-existing ratchet floor; observations #334). Implication: the
execution-surface red must be cleared (drop the stale `evidence-fe-ask-view` entry — a 561 cleanup) before a
G-B change can be verified green; track that dependency.

**(4) §5 citations locked [V].** (i) Rice's theorem: program/functional equivalence is a **corollary** (a
non-trivial *semantic* property), confirming §14.3's "reduces to program equivalence" is backwards; and the
restricted/abstract-interpretation/model-checking escape is explicit — *Wikipedia "Rice's theorem"*, primary
read. (ii) Type-4 clone recall: GPT-3.5 **0.07**, GPT-4 **0.23** on BigCloneBench Type-4 vs learning-based
**0.94** — *arXiv 2407.02402 (full text), Table 2*. Both §14.3 corrections stand as written.

**Revised confidence + the one open decision.**

| §14.6 item | Confidence after de-risking | Note |
|---|---|---|
| G-A | **Reframed → downgrade** | No head record; mechanical port impossible; AHA + lower-risk argue downgrade, not refactor |
| G-B | **~85%** | Invariant already holds; mechanism = `guardKind` tag + Check 7; bounded |
| G-C | ~90% | Now has a live exhibit: the `AskView.ts` orphan red |
| G-D / §1 | ~90% | Pure doc |
| G-E | ~95% | 564 revert + stale `searchState.ts:91` comment both confirmed |
| §5 wording | **~95%** | Both citations primary-source-locked |

**Open decision for the user (the only fork left):** G-A — (a) **downgrade in the doc** [recommended:
no head record + AHA + lower drift-risk], or (b) **mint a `HeadExecutionState` record and refactor
`KnowledgeSearchEngine.search()`/`buildHeadStages`** for true symmetry (real hot-path change). One
not-yet-run optional step remains: an **independent live-trace reproduction** of §12 (needs shared
dev-stack coordination) — offered, not required.

### 14.8 Implementation outcome (/goal run, 2026-06-10)

Implemented the entire §14.6 residue under a `/goal` run (worktree `worktree-tempdoc-hygiene`; not
merged — merge is the owner's call). The structural choice was taken everywhere it arose: the gate
gained real per-record teeth rather than a doc-only acknowledgement; G-A was the one place the
*structural* option (a `HeadExecutionState` record) was correctly **declined** because §6 AHA and the
de-risking (no head record; drift surface already covered) make minting one the *wrong* structure.

**Shipped:**
- **P0 — orphan cleanup.** Removed the stale `evidence-fe-ask-view` surface (561's deleted `AskView.ts`;
  `UnifiedChatView` already covers + is registered). `execution-surface`: pre-existing FAIL → **green**.
- **G-B — reflective-guard-per-record (Check 7), the only code change.** `governance/execution-surfaces.v1.json`:
  `worker-projector` tagged `recordId:"SearchTrace", guardKind:"reflective"` (its reflective guard is
  `SearchTraceProjectionConformanceTest`); `evidence-fe-projection` tagged `recordId:"ContextCitation",
  guardKind:"reflective"` (`evidenceProjection.test`). Enforcer Check 7 + `verdictForMissingReflectiveGuard`
  + two rule-description keys + positive/negative self-test fixtures (negative declares a record with no
  reflective surface → `missing-reflective-guard` fires). The invariant **already held**, so the gate
  stayed green — Check 7 is a *ratchet against regression* (a future record added without a totality
  guard, or removal of the only one, now fails the build). Honest §5 limit preserved: `guardKind:"reflective"`
  is a declared, auditable claim, not a mechanical proof of totality.
- **G-A — DOWNGRADED (doc).** §14.5 G-A rewritten to the verified reality; no `HeadExecutionState` record.
- **G-C/G-D/G-E/§5 — doc reconciliations.** §7 cost ledger; §1 multi-record authority; §13 Phase D
  `⟹ SUPERSEDED by 564`; `searchState.ts:91` comment fixed + observations item cleared; §5 Rice + type-4
  corrections (primary-source-locked, §14.7(4)).

**Verification (static tiers — all green except the documented pre-existing exception):**
- `execution-surface --self-test` → positive pass, negative fail (on `missing-reflective-guard` among the five). ✔
- `execution-surface --mode gate` → **pass**. ✔
- `clone --mode gate` → 25 findings, **unchanged** from baseline (pre-existing ratchet, observations #334;
  the new verdict fn added **no** new clone window). Not regressed. ✔ (documented exception, not mine)
- `modules/ui-web` typecheck + unit tests → [recorded in the final batch below].

**Live verification (the terminal /goal condition — one batch at the end). DONE — 2026-06-10.**

Coordination constraint honored: the shared dev stack was **owned by another agent session**
(`callerIsOwner:false`, fresh lease; per branch-safety I did **not** take over, start, stop, or
`ai_activate` it — activating the LLM on another agent's stack would interfere). The stack ran in
TEXT/BM25 mode (`aiActive:null`). The verification was done **read-only** against the running backend
(apiPort 64778), and the FE was served from **this worktree's own vite** (`:5175 → VITE_JUSTSEARCH_API_PORT=64778`)
— exactly the §13 Phase D pattern (serve the worktree's FE, reuse the running backend), so no
backend-ownership conflict.

- **API tier — DEFINITIVE.** `curl /api/knowledge/search "search execution trace"` → the **full
  query-level `SearchTrace`** projects end-to-end: `effectiveMode=TEXT`, `decisionKind=sparse_shortcut`,
  `qpp={maxIdf 2.67, avgIctf 6.51, queryScope 0.34}`, and **all 12 stages composed from BOTH processes**
  (worker: correction/sparse/dense/splade/fusion/chunk-merge/branch-fusion; head: query-understanding/
  expansion/lambdamart/cross-encoder/freshness — each with status + reason, e.g. `dense-retrieval:skipped:
  sparse-shortcut`, `lambdamart:skipped:MODEL_NOT_LOADED`), plus per-hit `trace` (HitStage). This is the
  canonical record → app-api `SearchTrace` → wire → REST chain, live.
- **FE tier — CONFIRMED in the real browser** (this worktree's vite on :5175, advanced mode). The search
  surface rendered 8 results; expanding a result's **"Why this result?"** rendered the per-hit `HitStage`
  trace **from the record**: `sparse-retrieval #8 0.04 · chunk-merge 0.03 · branch-fusion 0.00 ·
  cross-encoder -0.38` (+ an "Explain in words" affordance). The executed stages match the API trace
  exactly — the FE is projecting the canonical record, live. (Screenshots captured: search-results view +
  expanded per-hit trace.)
- **Scope note (honest):** the *query-level* aggregate explain panel (`searchTraceExplain.ts` /
  `jf-search-trace` headline+QPP+12-chips) is surfaced via the **aggregate inspector**, a surface the
  in-flight **565/570 search-window rework** is actively reorganizing (a registered, separately-owned
  concern — not a regression from this campaign; my changes touch no FE render path). The per-hit
  projection above + the API-tier full trace together confirm the thesis; the chip-by-chip query-level
  panel render was last pinned in §13 Phase D. **Confirmatory as predicted:** with G-A downgraded, no
  item in this campaign changed user-visible behavior — the live check reproduces §12 independently and
  shows 553's projection holding end-to-end on the worktree's own code.

**Result: all three DONE conditions met.** Gate self-test + gate green; clone not regressed; FE
typecheck + 2605 unit tests green; every §14.6 item landed-or-downgraded; live browser verification
confirms the SearchTrace projects to the real UI. (Not merged — merge is the owner's call.)

### 14.9 Honest closure accounting (status → done, 2026-06-10)

Status flipped `active → done`. The honest size of what the takeover *implemented* should be on the
record, not inflated: **this tempdoc was ~95% shipped before the takeover** (pillars a/b/c — the
canonical record, the projections, the `execution-surface` gate + Checks 1–6, the worker conformance
test, the OpenInference spans, the clone gate — all merged via `worktree-553-core`). Of the takeover
residue, the only genuine *engineering* was **G-B** (gate Check 7, ~6 files) — and even that *codified
an invariant that already held*, so it is a regression-ratchet, not a defect fix — plus **P0** (a
one-line orphan deletion, originally 561's cleanup). **G-A evaporated** under de-risking (no head
record; AHA → no fix). Everything else (G-C/G-D/G-E/§5, §11 reconciliation) was **documentation
honesty**. The takeover's real value was therefore *verification, a primary-source critique that
corrected the doc's own claims (Rice framing, type-4 figures, the G-A reframe), and tightening the
record* — not new construction.

**What remains is NOT implementation debt:** §10's "genuinely design-level" questions (vocabulary
ownership, register per-surface-vs-per-field granularity, migration sequencing) are *standing design
discussion*, explicitly out-of-scope per §10; the full source-unification (pillars a/b at the limit)
was always framed (§8) to proceed incrementally behind the gate, and §10.0 RESOLVED it as a
distributed contract that already exists. So `done` means **the actionable contract is complete**; the
doc persists as standing theorization, not open work.
