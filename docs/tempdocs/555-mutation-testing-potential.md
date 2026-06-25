---
title: "555 — Test efficacy as a first-class authority: law-backed seams (property spec + measured bite)"
type: tempdocs
status: done
created: 2026-05-28
updated: 2026-06-03
category: testing-strategy / quality / governance / architecture / theorization
related:
  - tempdoc 554 (property-based-testing-potential) — SIBLING; in this design it is not a separate tool but the *law-encoding half* of one mechanism. (Not edited by this doc; cross-linked only.)
  - tempdoc 553 (canonical-search-execution-record) — the register + governed-projection + anti-fragmentation-gate pattern this design reuses verbatim; `governance/execution-surfaces.v1.json` is the structural template.
  - tempdoc 530 / 531 (discipline-gate kernel) — the ratchet substrate the efficacy gate extends; the retired `independent-review` / `ux-audit-closure` gates are the load-bearing cautionary precedent.
  - tempdoc 547 (discipline-mechanization-audit) — the judgment-vs-artifact + anti-redundancy test any new gate must pass.
  - tempdoc 556 (integrity-audit) — Z.2 "name the drift class → register it → gate it"; this doc names the class "tests that execute but don't constrain".
  - docs/explanation/09-testing-strategy.md ; docs/reference/contributing/testing-quality.md — the strategy + prose-tier statement this would mechanize.
  - CLAUDE.md rules: critical-analysis-pass, audit-driven-fixes-need-test, fix-root-causes-not-symptoms — the prose disciplines this is the automation tier for.
---

# 555 — Test efficacy as a first-class authority

> **What this document is.** A *design* theorization: what the correct long-term structure should be so
> that the code that matters provably has tests that bite — and so the problem cannot silently recur.
> Feasibility/implementation specifics are deliberately deferred (the prerequisite that would normally
> block such a design — "does the tooling even run here?" — is already settled firsthand in §9.1).
> Major refactors are in scope. Markers: **[V]** = verified firsthand (source / web / experiment,
> 2026-06-03 unless noted); **[I]** = design judgment.

## 1. The problem is structural, not a missing tool

The narrow question "should we add mutation testing?" is the wrong frame. The takeover investigation
(experiment §9.1, sweep §9.2) surfaced a *structural* defect:

> **Logic-density and test-strength are uncorrelated in this codebase — and nothing in the design makes
> them correlate.** The densest, highest-blast-radius logic is often the *least* constrained.

Concretely **[V]**: `TokenAwareBudgeter` and `core/util/TokenEstimation` (token-budget arithmetic +
binary-search truncation) have **no direct unit tests**; `ContextBudgeter` has a **39-line** test for
~126 LOC; the hot `worker-services/loop` guard classes (`FileFreshnessSnapshot`,
`WorkerIngestionAuthority`, `BackfillScheduler`, `LoopPacingPolicy`) have none. Meanwhile the existing
guards measure only *proxies of effort, never constraint*:

- **`test-to-code` ratchet (530 §2.10)** measures test **volume** (LOC ratio). A module can hit ratio
  and still have tautological assertions.
- **JaCoCo coverage (`CoverageConventionsPlugin`)** measures **execution** (a line ran) — and is
  *dormant*: opt-in behind `-Pcoverage.enforce=true`, skipped locally. Execution ≠ constraint.

The §9.1 experiment shows the gap on one screen: **100% line coverage, 75% mutation score** — two
faults on fully-covered lines that no assertion caught. So bugs ship silently *in exactly the code
where a silent bug is most expensive*. The missing thing is not a tool — it is a **tier of the design
that ties "this is load-bearing logic" to "this logic has a test that provably kills faults".**

## 2. Thesis: efficacy is a missing authority; 554 and 555 are one mechanism

The repo already states this discipline in prose — `critical-analysis-pass` ("does the assertion
distinguish *passes-for-the-right-reason* from *passes-for-a-wrong-reason*?"),
`audit-driven-fixes-need-test`, and `testing-quality.md`'s self-review prompt ("if I introduce a subtle
bug, will any test catch it?"). It lands at ~70% prose adherence (`tier-register.md`). The correct
design is the **automation tier** for that class.

The key insight that reframes the two sibling tempdocs:

> **Property-based testing (554) and mutation testing (555) are not two tools to evaluate separately.
> They are the two halves of one mechanism.** A piece of load-bearing logic carries: **(i)** a *declared
> law* (the invariant it must obey); **(ii)** a *property test that encodes that law as an executable
> spec* — the 554 half; **(iii)** a *measured proof the spec actually bites* — the 555 half (mutation
> adequacy).

Neither half is sufficient alone: a property without mutation-adequacy can itself "pass for the wrong
reason" (a vacuous generator, a weak assertion); mutation-adequacy without a property just grades
whichever hand-picked examples happen to exist. **Together** they are the design: *the law is stated as
a property, and the property's bite is measured.* This is what makes efficacy a first-class,
mechanically-checkable property of the code rather than a hope.

## 3. The correct structure — a "Law-Backed Seam" authority

This mirrors tempdoc 553's proven shape (canonical source + governed projection + anti-fragmentation
gate). 553 governs *representations of a fact*; this governs *behavioral laws of logic*.

**Pillar A — the canonical register.** A single allowlist — `governance/logic-seams.v1.json`, sibling
of `execution-surfaces.v1.json` — of designated **behavioral-law seams**: pure, branch/arithmetic-dense,
high-blast-radius units whose failure mode is a *silent wrong value*. Each entry declares:
- **location** (the pure unit),
- **law** — the invariant in words + as the property it must obey (e.g. *fusion rank is monotone in
  each leg's score*; *config precedence is a total order*; *a projection is lossless-downward*; *chunk
  offsets never overlap or gap*; *a budget is never exceeded*; *truncation yields a prefix*),
- **efficacy guard** — the property/example test(s) that must kill faults in it, and the measured
  adequacy floor.

The register is also the **discovery oracle** (553 §7): the place an agent consults to learn "is this
already a governed seam?" before authoring — see §5.

**Pillar B — the law layer (the 554 half).** The declared law is encoded as a property test: the
property *is* the law statement, generators throw adversarial inputs, counterexamples shrink. Example
tests remain valid where the behavior is a table with no underlying law, but a *registered* seam's
guard must express the law, not a handful of points.

**Pillar C — the efficacy gate (the 555 half).** A **mutation-adequacy ratchet** over the registered
seams only. It measures **test strength** — killed / *covered* mutants, **not** raw mutation score
(the §9.1 finding: a `NO_COVERAGE` mutant is just line-coverage being honest; only a `SURVIVED`-on-
covered mutant is the unique signal). It ratchets monotonically: grandfather the current strength,
allow only improvement, require a classified changeset to regress (the 530 ratchet shape). It emits a
**mechanical signal** (a number), which is the property that makes it safe to gate — see §6.

## 4. The structural refactor this implies: functional core / imperative shell

This is the part that makes it a *design* answer rather than a "write more tests" chore, and it is the
honest reason §9.2's test-poverty exists:

> **A law is only enforceable if it lives in a pure, native-free, IO-free unit that can be law-tested in
> isolation.** §9.2 found the test-poor logic is test-poor *because* its law is entangled with IO /
> native / orchestration — you cannot cheaply assert "budget never exceeded" when the budget math is
> braided through a writer that also touches Lucene and an encoder.

So the correct design pushes a **functional-core / imperative-shell** discipline for law-bearing code:
extract the law into a pure seam (offset math out of `ChunkDocumentWriter`; budget arithmetic out of
the RAG writers; the worker-loop guards are already nearly pure and just need registering). The pure
seam is what Pillar A registers, Pillar B specifies, and Pillar C measures. The impure shell stays
*out* of the register by design (§6, Wall 2). This refactor is the durable fix: it converts "the
important logic happens to be hard to test" into "the important logic is, by construction, a testable
seam carrying its own law."

> **Calibration (§10 de-risk P3).** The extraction is real for *some* seams but overstated as a blanket
> claim: of the test-poor seams probed, `LoopPacingPolicy`, `TokenAwareBudgeter`, and `TokenEstimation`
> are **already pure — just untested** (so they need only tests, which *strengthens* §1: the logic isn't
> even hard to test), while `ChunkDocumentWriter` (IO), `FileFreshnessSnapshot` (FS+clock),
> `WorkerIngestionAuthority` (FS), and `BackfillScheduler` (clock) are genuinely entangled and *do* need
> the functional-core split (e.g. inject a clock so the backoff math is law-testable). So §4 is the fix
> for the entangled subset, not a universal prerequisite.

## 5. Long-term prevention — the authoring oracle

A register that is only read at CI time prevents nothing at authoring time; the seam was already born
test-poor. The prevention mechanism (553 §7, applied here): the register is the **authoring-time
discovery surface**. When new branch-dense pure logic is written, the oracle's question is *"is this a
law-backed seam? then declare its law and its property."*

> **A seam cannot be born without its law.** The LLM agent is both the *cause* of the defect (it writes
> dense, plausibly-tested-looking logic) and the *oracle* that can catch it at write time (whole-repo
> context + the register to check against).

This closes the recurrence loop the same way 553 proposes for representation drift: the gate catches
the declared set at ~100%; the oracle is what keeps the declared set from lagging reality.

## 6. Honest limits and the defense-in-depth posture

Transferring 553's two walls and the 530 retired-gate lesson verbatim — these are *design constraints*,
not caveats to bolt on later:

- **Wall 1 (undecidability).** The gate guarantees the *declared* seams bite (~100% within scope). It
  **cannot** prove a law is the *right* law (a wrong invariant is a wrong spec — 554/555 both note
  this), nor discover an *undeclared* seam, nor auto-eliminate equivalent mutants. Those are judgment;
  they stay prose-tier and human-audited.
- **Wall 2 (not all behavior is law-projectable).** Controllers, IO wrappers, native/ORT/GPU boundaries,
  gRPC forwarding, and DTO/UI boilerplate have *no law* and a non-silent (or no) failure mode. They are
  **out of the register by design** — this promotes §9.2's exclusion set from accident to declared
  boundary. Forcing them in is the 553-AHA over-unification trap ("a 'shared' thing that accretes flags
  until it's confusion in a costume").
- **The load-bearing constraint (530 §Remediation).** The gate **must** be a *mechanical ratchet
  signal* (a mutation-strength number), **never a human "I reviewed the tests are good" record.** The
  retired `independent-review` and `ux-audit-closure` gates failed precisely because they gated on an
  attestation whose *content* could not be machine-validated: they rubber-stamped, and they false-failed
  on no-op refactors (a pure type-extraction with zero behavioral delta tripped `ux-audit-closure`
  because file-membership ≠ behavioral-delta). An efficacy gate keyed on a measured number has neither
  failure mode — it only moves when the *kill behavior* moves.

The resulting posture is **defense-in-depth, not total prevention** (553 §5, "asymptotic, never
total"): a sharp narrow efficacy ratchet on the declared seams + the broad authoring oracle (catches
new seams) + cheap JaCoCo branch coverage as the broad, noisy tripwire for everything *outside* the
register + periodic human audit at the edges.

## 7. Integration, not a parallel system

This extends what exists; it does not sit beside it (547 anti-redundancy):

- **On the 530 kernel.** The efficacy gate is a new gate-class on the discipline-gate kernel, with a
  `ratchet-file` baseline (per-seam test-strength floors), the standard changeset escape-hatch, and
  self-test fixtures — the same substrate as `class-size` / `test-to-code`.
- **In the tier-register.** A new `tier-register.md` row: the **quality sibling** of the existing
  `test-to-code` **quantity** ratchet. (`test-to-code` answers "are there enough tests?"; this answers
  "do they bite?") The relevant `testing-quality.md` self-review lines move prose-only → gate for the
  registered seams — exactly the meta-loop tier-upgrade path.
- **Subsumes coverage where it matters.** Mutation strength strictly dominates branch coverage on a
  registered seam (a survived mutant on a covered branch means coverage was lying). So for registered
  seams the efficacy gate *replaces* the coverage check; JaCoCo stays as the cheap broad tripwire
  elsewhere (the 553 "token-clone tripwire" analogue).
- **Reuses 553's register vocabulary.** `logic-seams.v1.json` is "`execution-surfaces` for behavioral
  laws": `canonicalRecord` → the seam's law; `surfaces[].guard` → `test:<PropertyTest>` /
  `gate:efficacy`. An agent reads it the same way.

## 8. What this design deliberately is *not*

- **Not "adopt PIT and Stryker."** Those are *candidate mechanisms* for Pillar C; the design is the
  register + the functional-core seam + the adequacy ratchet + the authoring oracle. The tool is
  swappable; the structure is the point.
- **Not a blanket quality program.** It governs the *declared seams* only. The whole-codebase ROI
  skepticism stands (the takeover review): the value is concentrated, so the *register stays small* and
  earns each entry. A repo-wide mutation run is explicitly an anti-goal (§6 Wall 2).
- **Not a phased plan.** Feasibility is settled (§9.1) but sequencing, thresholds, and the first seams
  to extract are an implementation slice's job, not this doc's.

---

# 9. Empirical grounding (firsthand, 2026-06-03)

The design above rests on two firsthand results from the takeover investigation. They are retained in
full because they are the evidence the structure is built on — and because the §9.2 sweep is the **seed
of the Pillar-A register**.

## 9.1 Feasibility: the *toolchain* runs; in-build *wiring* has real tasks (see §10)

The prerequisite "does mutation tooling even run on Gradle 9.4 / Java 25 / JUnit 5.14?" is answered
**yes** by experiment. **But "feasibility settled" is scoped to the toolchain, not the build:** the §10
de-risking pass found that wiring PIT into the *real* locked build is blocked transiently by
**dependency verification** (`gradle/verification-metadata.xml` rejects unverified PIT artifacts) and by
init-script plugin/extension/task-wiring awkwardness — so the clean integration is a normal build-file
**convention plugin** + verification-metadata entries, not a bolt-on. The numbers below are from an
isolated sandbox; the real-class measurement is in §10 (P1/U2).

- Stack **[V]**: Gradle **9.4.0**, Java toolchain **25** (Temurin 25.0.2), JUnit **5.14.3**. Java 25 =
  class-file v69; ASM rejects v69 before **9.8**; PIT bundles **ASM 9.9 since 1.22.1** (current 1.25.3),
  so current PIT reads/instruments v69. `gradle-pitest-plugin 1.19.0` supports Gradle 9. **[V web]**
- **Experiment** (throwaway, git-ignored `tmp/pit-sandbox/`, reusing the cached 9.4.0 dist; class
  `OrdinalResolver` modeling `ResolvedConfigBuilder.resolve()` + a `clampWeight` + an `outranks`):

  | Run | Test suite | Line cov | Mutation score | SURVIVED | NO_COVERAGE | Test strength |
  |---|---|---|---|---|---|---|
  | 1 | partial (happy path) | 85% | 50% (4/8) | 0 | 4 | 100% |
  | 2 | full coverage, *weak* assertions | **100%** | **75%** (6/8) | **2** | 0 | 75% |

  `BUILD SUCCESSFUL` on Java 25 both runs (PIT minion "exited ok"). Run 2 is the thesis on one screen:
  **100% line coverage but 2 faults survived on covered lines** — a `ConditionalsBoundary` `>`→`>=` on
  `outranks` and a boundary/math mutant on `clampWeight`. This is *why* §3 Pillar C measures **test
  strength** (killed / covered), not raw mutation score: run 1's four "survivors" were `NO_COVERAGE`
  (line coverage already reports that); only run 2's covered survivors are the unique efficacy signal.
- **De-risks for the design** **[V]**: PIT's auto-filters (`+fequals`, `+fstati`, `+ftrywr`, …) already
  trim a chunk of equivalent mutants; and the highest-value targets (`adapters-lucene`,
  `configuration`) use **~zero Mockito**, so the classic mock-interference pain point is absent.

  Repro: `./gradlew.bat pitest` from `tmp/pit-sandbox/`.

## 9.2 The seam register, seeded — where laws actually live (and don't)

A 5-way parallel sweep of all 38 modules (verdicts `[V]` re-verified firsthand for the lead rows,
`[A]` = audit hypothesis pending a real run). This *is* the initial population of the Pillar-A
`logic-seams.v1.json` register.

**Tier 1 — law-bearing seams that already have a suite to measure (extract/register first):**

| Seam (`module`) | Law | Has property/example test |
|---|---|---|
| **`HybridFusionUtils`** (`adapters-lucene`) — *the lead* | RRF/CC/CC3 fusion: rank-weighting, min-max normalization, weight clamping monotonicity | `HybridFusionUtilsTest` (1379 LOC); pure (858 LOC, 238 branch/math tokens, **0** native/IO) **[V]**. Also tempdoc 554's #1 PBT target → the two halves coincide here. |
| `SearchAfterCursorHelper`, `PagingCursorManager`, `QueryFilterBuilder.addLongRangeFilter` | cursor codec round-trips; TTL/checksum; range bounds (`min>max` swap) | cursor/filter tests exist `[A]` |
| `ResolvedConfigBuilder`, `ConfigParsingUtils`, `VariantSelector`, `InstallPlanner` (`configuration`); `VramFlagsUtil`, `VramRequirements` (`gpu-bridge` pure helpers) | precedence total-order; parse+bounds; EP selection; VRAM-tier thresholds | dense (~99–126 conditionals) + mock-free suites **[V/A]** |
| `QueryClassifier`, `TemporalQueryExtractor`, `AnswerTypeClassifier`, `KnowledgeSearchEngine.is*Eligible` (`app-services`) | query-intent rules; date bounds + epoch math; eligibility-gate boolean algebra | classifier tests exist `[A/V]` |
| `ConditionStore` (`app-observability`), `TelemetryHealthSnapshot` (`telemetry`) | k8s condition state machine; success-rate aggregation | tests exist `[A]` |

**The sharp sub-finding — law-rich but test-poor seams (the §1 defect made concrete).** These have the
densest laws and the thinnest/absent suites, so they are the prime targets for the §4 functional-core
extraction + the §3-Pillar-B property layer **before** mutation can measure anything: `TokenAwareBudgeter`,
`core/util/TokenEstimation` (no direct tests **[V]**), `ContextBudgeter` (39-line test **[V]**),
`ChunkDocumentWriter` offset math, and the four `worker-services/loop` guards. *PBT-first, then
mutation* — this is precisely the 554↔555 unification, and it tells a slice the order.

**Out of the register by design (Wall 2):** native ORT/GPU probes (`ort-common`, `gpu-bridge` detectors),
Lucene/Tika IO, model-inference paths, gRPC forwarding wrappers, and boilerplate (`app-api` ≈ 71%
`@RecordBuilder` DTOs, `app-agent-api` ≈ 100%, `ui` ≈ 98% controllers) **[A]**.

**Synthesis.** Pure, deterministic, law-bearing logic is *sparse but sharply concentrated* in five
seams: fusion/scoring, cursor/paging codecs, config/hardware decisioning, query understanding, and
worker-loop guards + health/telemetry derivation. That concentration is exactly why the design wants a
*small, earned register* rather than a blanket program — and why the lead is `HybridFusionUtils`.

---

# 10. De-risking pass — 2026-06-03 (confidence calibration before any implementation)

> **Purpose.** Before this design is implementable, a critical self-assessment named six load-bearing
> uncertainties that were mostly *inference*. This pass ran bounded, throwaway probes (no register, no
> gate, no permanent build wiring) to convert each into evidence. Net effect: confidence raised on the
> thesis + cost + gate-fit; two design claims corrected (§4 calibration above; §9.1 narrowed). Markers
> `[V]` = verified firsthand 2026-06-03.

## 10.1 What each probe found

- **U1 — PIT in the *real* build (was: low confidence).** Attempting to wire PIT transiently via a
  Gradle init script surfaced concrete in-build friction **[V]**: (1) **dependency verification**
  (`gradle/verification-metadata.xml`) rejects the unverified `gradle-pitest-plugin` + PIT artifacts —
  the build enforces checksums, so adding PIT means adding verification-metadata entries (a real,
  trust-bearing task); (2) the plugin must be applied by implementation class, and its
  extension/task wiring depends on the `java` plugin + the plugin's own `afterEvaluate`, so an
  init-script hack is the wrong shape. **Conclusion:** PIT cannot be bolted on transiently; the clean
  integration is a normal **build-file convention plugin** (`conventions.mutation` in `build-logic/`) +
  verification-metadata entries + a lockfile pass. This is the in-build task list §9.1 now points to.
  (Dependency *locking* is `DEFAULT` mode and was **not** a blocker — the new `pitest` config resolves
  unlocked.)
- **U2 — does the lead seam's test already bite? + cost (was: unknown). DECISIVE.** Ran PIT against the
  **real `HybridFusionUtils`** (858 LOC) + its **real 1379-LOC test** (sandbox-copied with 3 minimal
  stub deps; the real test passes `null` for `ResolvedConfig`, so the measurement is faithful) **[V]**:
  **225 mutants, 157 killed, test strength 75%, line coverage 94%, ~53 mutants SURVIVED on covered
  lines**, in **27s** total (1247 test executions). Per-mutator, `ConditionalsBoundary` (the `>`/`>=`
  silent-mis-ranking class) was **10 survived / 2 killed**. **Conclusions:** (a) the lead target does
  **not** already bite — the thesis holds on the repo's *best-tested* seam, so the lead example
  **stands** (no re-anchor needed); (b) this is the honest "found ~53 gaps 94% line coverage hid" proof,
  now on the real crown jewel; (c) **cost is a non-issue** for a targeted seam (~27s), so Pillar C is
  cheap when scoped.
- **U3 — are the stated laws real? (was: inference). CORRECTED.** Reading the fusion math **[V]**: the
  RRF path scores `weight/(k+rank)` — it is **rank-monotone, not score-monotone** (raw score only
  matters via rank + the bm25 boost term); the CC path's `normalizeScore = (s-min)/range` **is**
  score-monotone. So the blanket "monotone in each leg's score" law (used loosely in §3/§9.2)
  **conflates two algorithms** — the real laws are *algorithm-specific*: RRF→rank-monotonicity +
  weight-bound [0,1] (clamped) + total-order tiebreak; CC→score-monotonicity. Pillar B's example law
  must be stated per-algorithm, not blanket. (This is itself a small case of "the law you'd write down
  is subtly wrong" — exactly what the property+mutation pairing is meant to surface.)
- **U4 — is the §4 refactor load-bearing? (was: partly disproved). CALIBRATED** — see the §4
  calibration box: ~3 of 7 test-poor seams are already pure (need tests only), ~4 are genuinely
  entangled (need the split). §4 reframed accordingly.
- **U5 — PBT framework on Java 25 (was: mostly resolved). CONFIRMED + caveat.** jqwik runs on Java ≥21
  (covers 25) but its current 1.x line needs **JUnit Platform ≥1.14.4** (repo has 1.14.3 → a one-patch
  bump) and is in **maintenance mode**, with the future line on JUnit Platform 6. Pillar B is feasible
  with that bump; the maintenance-mode status is a durability note for a long-term design (the JUnit
  Platform 6 line is the path).
- **U6 — can the kernel host the efficacy gate? (was: mostly resolved). CONFIRMED.** The `npm-audit`
  gate already **reads a pre-produced report** (`tmp/npm-audit-report.json`) and ratchets against a
  baseline, failing `report-missing` if absent — the exact shape for an efficacy gate that reads PIT's
  `mutations.xml`-derived strength report. `class-size` baselines are **per-entity rows**
  (`<path> <max> <date>`), confirming per-seam strength floors fit. Pillar C is a faithful kernel
  citizen; no new substrate needed.

## 10.2 Corrected confidence

| Claim | Before | After | Basis |
|---|---|---|---|
| Architectural skeleton (register+projection+ratchet on the kernel) | high | **high** | U6 confirmed it's a faithful kernel citizen |
| Mutation tooling runs on the toolchain | high | **high** | §9.1 experiment |
| Mutation tooling drops cleanly into the *real build* | low | **medium** | U1: known task list (verification-metadata + convention plugin), not a bolt-on |
| Thesis "tests don't bite on the code that matters" holds on the lead seam | unknown | **high** | U2: 75% strength, ~53 survivors on the best-tested seam |
| Pillar-C runtime is affordable when targeted | unknown | **high** | U2: 27s for the 858-LOC crown jewel |
| The stated laws are correct as written | inference | **corrected** | U3: RRF rank- vs CC score-monotone; state per-algorithm |
| §4 functional-core refactor is universally required | asserted | **calibrated** | U4: real for the entangled subset only |
| Pillar-B PBT framework is available | unchecked | **medium-high** | U5: jqwik works on JDK 25 w/ a platform bump; maintenance-mode caveat |

**Net:** the design's *skeleton and thesis are now high-confidence and evidence-backed*; the residual
risk is concentrated in **build integration** (U1 — a known, bounded task list, not an unknown) and the
**per-algorithm precision of the laws** (U3 — a Pillar-B authoring discipline). No probe falsified the
design; two sharpened it. Repro: real-seam run = `tmp/pit-sandbox/` (now targets `HybridFusionUtils`);
the blocked in-build attempt = `tmp/pit-real-init.gradle`.

## 10.3 As-built (2026-06-03 implementation + fix pass)

The design was implemented on `worktree-555-test-efficacy` (merged to main 2026-06-03, merge commit
`3a5a24353`): `conventions.mutation`
(build-logic) scopes PIT from `governance/logic-seams.v1.json`; **5 seams** registered (hybrid-fusion
76%, chunk-offset-math 88%, file-freshness/admission-policy/splade-backoff 100%); all 4 entangled seams
functional-core-extracted (pure cores + delegating shells, behavior-preserving — full
`adapters-lucene` + `worker-services` suites green); the `test-efficacy` gate + `seam-hint` oracle.

A self-critique then found the first cut was **inert/partly-incorrect** and was fixed:

- **Enforcement is now real.** A dispatch-gated `mutation_tests` CI lane (`runMutation`) runs
  `report-pit-strength --run` (PIT per seam module) → the gate; `check-logic-seams.mjs` validates the
  register on every CI run + locally. (Previously the gate was never invoked and PIT never ran.)
- **Fail-closed.** A registered seam missing from an existing report now FAILS (`seam-not-measured`),
  not silently passes. The register validator catches a typo'd FQCN that would otherwise no-op PIT.
- **Coverage-erosion guard.** The ratchet now tracks a per-seam `maxNoCoverage` ceiling, so adding
  untested branches (invisible to killed/covered) fails. Metric counts `TIMED_OUT` as killed (PIT's
  definition); the baseline is `--rebalance`-derived, not hand-set.
- **Oracle de-noised.** `seam-hint` fires only on a `Write` of a dense, IO-free new class.

**Honest limitation (Pillar B).** The `*PropertyTest`s are fixed-seed JUnit loops (reproducible, but
**no shrinking, fixed input sample**) — weaker than true PBT. Deliberate: jqwik 1.10.0 shipped an
AI-agent-targeted prompt-injection payload, so it is not added to this verification-gated build (§7).
Mutation adequacy is what certifies the tests bite. **Out of scope:** pre-existing class-size main-debt
(`AgentLoopService`/`LocalApiServer`) blocks the *full* `build`; logged in `observations.md`.

## 10.4 Merged + remaining work (2026-06-03)

Merged to `main` as `--no-ff` commit `3a5a24353` (single 555-doc conflict resolved to this superset).
Post-merge `build -x test` is green **except** the pre-existing class-size debt (the two untouched
files above — no *new* breakage from the merge); the new infra is intact on main (`check-logic-seams`
exit 0; `test-efficacy --self-test` positive-pass / negative-fail). The merge is on local `main`; not
yet pushed.

**Remaining work — a goal-vs-implementation audit found the authority is a proven *instance*, not yet
the full authority §1–§7 envisioned. Tracked here so it is not lost:**

1. **Coverage gap vs §1's named disease.** The RAG budgeters (`TokenAwareBudgeter`,
   `core/util/TokenEstimation`, `ContextBudgeter`) — §1's flagship "dense, untested" examples — are
   **not yet** registered/extracted. The 5 landed seams skew toward cleanly-extractable logic; the
   highest-value test-poor code §1 actually flagged remains uncovered. *Next slice: extract + register
   the budgeter laws (budget-never-exceeded, truncation-is-a-prefix — §3's own examples).*
2. **Defense-in-depth net is half-built (§6).** Only the sharp narrow efficacy ratchet exists; the
   **broad JaCoCo branch-coverage tripwire** for everything *outside* the register is still dormant
   (opt-in). The posture needs the broad layer activated, or undeclared dense logic still ships with no
   floor at all.
3. **Pillar B is a surrogate.** Native fixed-seed JUnit loops, not true PBT (no shrinking, fixed
   sample). If the jqwik supply-chain risk clears (or another generator lands), upgrade the law layer.
4. **Integration/consistency follow-ups (indirect — the merged infra is otherwise undocumented in the
   canonical surfaces).** Add the `test-efficacy` row to `.claude/rules/tier-register.md` (the §7
   quality-sibling of `test-to-code`); list the gate in `docs/reference/contributing/discipline-gate-kernel.md`;
   mention the new enforcement tier in `docs/explanation/09-testing-strategy.md` +
   `docs/reference/contributing/testing-quality.md`; add `check-logic-seams.mjs` to the CLAUDE.md
   per-subject pre-merge list and `seam-hint` to `.claude/rules/hooks-reference.md`; then run the
   `llmstxt-generate` + `skills-sync` regen. (These canonical-doc edits carry their own gates/regen, so
   they are batched as a follow-up rather than smuggled into the merge.)
5. **Pre-existing class-size main-debt** (`AgentLoopService`/`LocalApiServer`, unrelated to 555) still
   blocks a green full `build`; its own fix (bump pins or decompose) is a separate main-debt task.

---

# 11. Original theorization (2026-05-28) — dated history

> Preserved per `tempdocs-are-dated-history`. This is the pre-takeover "should we add mutation testing?"
> framing. It is **superseded** by §1–§8: its central recommendation (lead with `configuration` because
> of the `ConfigWiringTest` escape) was found to be doubly wrong — the escape lives in `adapters-lucene`,
> not `configuration` (geographic error), and it is an *exception-type* assertion weakness that **no
> standard PIT mutator can distinguish** (the throw-site `ComponentsFactory.validateVectorDimension`
> yields the same kill-set under `Exception.class` and `IllegalStateException.class`). The lead target is
> `HybridFusionUtils` (§9.2), and the example to motivate the technique is a boundary/math survivor
> (§9.1 run 2), not that escape. The original §1–§7 text:

**§1 What mutation testing is.** A tool injects small faults into production code (`>`→`>=`, delete a
statement, swap `+`/`-`, negate a boolean, default a return) producing "mutants," then runs the suite
per mutant. Killed = a test noticed; survived = no test constrained that logic. Output = a mutation
score, harder to game than line coverage.

**§2 Why a sharp fit.** It is the mechanized form of `critical-analysis-pass` /
`audit-driven-fixes-need-test` / `fix-root-causes-not-symptoms` — the automation tier for rules at ~70%
prose adherence. *(Original cited the `ConfigWiringTest` escape as proof — see §10 header for why that
is now retracted.)*

**§3 Current state [V].** No PIT/Stryker present; the suite is large and multi-tiered (unknown real
bite); the 530 kernel already runs ratchet baselines a mutation-score ratchet would slot into.

**§4 Candidate targets (original ranking).** 1 `configuration`; 2 `adapters-lucene` fusion/query; 3
worker state/lifecycle (516/518); 4 `app-services` decision tree (517/549/553); 5 wire validators.
*(§9.2 reorders: fusion math leads; configuration is a density-justified second.)*

**§5 Open questions.** runtime cost (manual-only CI → dedicated/incremental lane); equivalent mutants;
advisory vs gate; flaky/non-deterministic tests must be excluded; scope creep (one module, not blanket).
*(§9.1 answers the prerequisite §5 omitted — does it run at all.)*

**§6 Honest limits.** A high score proves bite, not correctness; pairs with PBT (554) — mutation on a
PBT-covered module is the sharpest combined signal *(now the core thesis, §2)*; cost/benefit favorable
only on mature, branch-dense, deterministic modules.

**§7 Original next step.** "Pick one module (recommended `configuration`), run PIT advisory/incremental,
report score + survivors, show one survivor on a genuinely under-tested branch." *(Superseded: lead with
`HybridFusionUtils`; headline test-strength; the design is §1–§8, not a single advisory run.)*
