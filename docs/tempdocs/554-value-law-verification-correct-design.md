---
title: "554 — Value-Law Verification: Correct Design (the law-tier ladder; lift laws into types; PBT is the floor, not the mechanism)"
type: tempdocs
status: done
created: 2026-05-28
updated: 2026-06-03
category: testing-strategy / verification-architecture / theorization / correct-design
audience: architect
supersedes-self: "Earlier draft titled '554 — Property-based testing: where invariants would earn their keep'. That draft asked the adopt-or-not question (should we add jqwik, and where). A takeover deep-dive (2026-06-03, now the §A evidence ledger) showed that is the wrong altitude: the real subject is *executable value-laws over pure functions*, an unregistered authority that drifts the same way representations do, and PBT is only the floor-tier mechanism for it. This rewrite states the correct structure; the prior firsthand findings are preserved as the evidence ledger. History in git."
related:
  - tempdoc 555 (mutation-testing) — SIBLING; reframed here as the *oracle-certifier* of the test floor (§7)
  - tempdoc 564 (contract-projection correct-design) — the model this generalizes; source of the projection-tier ladder (Collapse > Unrepresentable-by-type > Generate > Gate)
  - tempdoc 553 (canonical execution record) / 549 (unified SearchTrace) — the representation-drift instance whose register+gate this mirrors for laws
  - tempdoc 547 (discipline-mechanization audit) — the binding constraint: no *parallel* enforcement; prefer structural decomposition over more gates
  - tempdoc 530 (discipline-gate kernel) / 481 (RegistryEntry meta-substrate, per 564 §2) — the kernel + projection abstraction this rides
  - docs/explanation/09-testing-strategy.md — the test pyramid this re-tiers; docs/reference/contracts/search-pipeline-invariants.md — a prose law-set that is a register candidate
---

# 554 — Value-Law Verification: Correct Design

> **What this is.** A *theorization* of the correct long-term structure for verifying the class of
> facts the codebase currently leaves implicit: **executable laws that pure functions must obey** —
> round-trip codecs, offset/budget conservation, idempotent normalizers, precedence total-orders,
> framing-invariance, numeric bounds. It **disregards feasibility, migration, and effort** (those
> belong to a later implementation tempdoc) and **prefers completing proven existing substrates over
> inventing**. Property-based testing — the question the first draft asked — turns out to be one
> *floor-tier mechanism* of this structure, not the structure itself. Markers: **[V]** verified
> firsthand against the repo (2026-06-03); **[I]** inferred / judgment.

---

## 1. The problem, re-diagnosed

The first draft asked "where would property-based testing earn its keep?" That is the adopt-or-not
question, and it is one altitude too low. Re-diagnosed:

> **The codebase has a whole class of facts — *laws a pure function must obey* — that are declared
> nowhere, restated (if at all) in prose, and verified (if at all) by hand-picked examples. The law
> and its check are two un-unified authorities, so they drift or never meet. This is the *same defect
> class* the repo already fights for representations (553 SearchTrace, 564 wire-contract): a fact with
> no single home and only a patrol against drift.**

A *value-law* is a property like `decode(encode(x)) == x`, "chunk offsets tile the input with no gap
or overlap," `normalize(normalize(x)) == normalize(x)`, "higher config ordinal always wins,"
`length() ≤ budget`. These are not opinions; they are constraints the code is *already assumed to
satisfy* by every caller. Today they live only inside the function body and, sometimes, a sentence in
`search-pipeline-invariants.md`. Nothing binds the law to the code, and nothing prevents an edit from
silently breaking it. **[V]** (no PBT framework; the laws are spot-checked by example tests — §A).

The §A "oracle problem" is the same defect seen from the test side. A property test with a *weak
oracle* — one that can only check the law by re-deriving the function's own math — is a second
authority that **agrees with nothing**: it looks like verification but constrains the code no more
than the code constrains itself. A green weak-oracle property is `static-green ≠ live-working` ported
into the test tier. So "add property tests" is not the fix; done naively it *adds* instances of the
very defect (a check that drifts from, or tautologically mirrors, the law).

## 2. What already exists — the foundation to complete

The correct design is reachable by *finishing and re-pointing* existing substrates, not inventing. **[V]**

- **The projection-tier ladder** (564 §3, 548 vocabulary): **Collapse > Unrepresentable-by-type >
  Generate > Gate** — push every concern as *high* as it can go. 564's load-bearing line: *"Gate is the
  floor, not the mechanism."* Conformance tests and capture-or-verify *patrol* drift; only
  Collapse/Generate *prevent* it. The ladder was built for *representations*; this tempdoc's move is to
  **apply it to laws**.
- **The meta-substrate** (481 `RegistryEntry` + 530 discipline-gate kernel, per 564 §2/§4e):
  *canonical source → governed projections → coverage gate*, one `scripts/governance/run.mjs`, ~23
  gates, unified SARIF. **[V]**
- **The surface registers** that bind a concept to its source and its enforcing gate:
  `governance/execution-surfaces.v1.json` (a `canonicalRecord` + `surfaces[]`, each with
  `guard: "gate:X + test:Y"`, scanned so an *unregistered referencer* of the canonical type fails the
  build) and `governance/contract-surfaces.v1.json` (`records[]` → generated `outFile` + `consumers`).
  **[V]** These are registers for **representations** and (in `.claude/rules/tier-register.md`) for
  **prose rules**.
- **The conformance / capture-or-verify floor**: `*ConformanceTest` assert a *totality* property
  (every record field classified REPRESENTED-xor-DROPPED; `SearchTraceProjectionConformanceTest`),
  `captureOrVerify` fails on baseline drift (`HealthEventSchemaTest`). **[V]** These are exactly the
  *Gate-tier floor* — they catch, they do not prevent.
- **547's redirection**, and the hard constraint on this design: the prose-rule mechanization frontier
  is *mined out* (what remains `prose-only` is judgment), the 530 kernel **forbids parallel
  enforcement** (a new gate may not duplicate an existing check), and *"the highest-value remaining
  structural work is decomposition, not more enforcement."* **[V]**

**Honest state of the foundation.** There is a register for *representations* and one for *prose
rules*. **There is no register for executable value-laws** — they are the one authority class the
meta-substrate has not yet absorbed. That gap is the subject of this tempdoc, and filling it is a *new
instance of the existing substrate*, not a new substrate — and it lands precisely on 547's recommended
frontier (decomposition), not on the mined-out enforcement frontier.

## 3. The unifying principle: the law-tier ladder

Generalize 564's ladder from copies-of-a-fact to constraints-on-a-function:

> **Every law a pure function must obey is realized at the highest tier it can reach. From strongest:
> the law is a *type* (a violating program does not compile) > the law's check is *generated* from its
> declaration > the law is *property*-checked over a generated input domain > the law is *example*-
> checked. The law that is a type has no oracle and no test; only floor-tier laws need an oracle, and
> that oracle must be named. The hand-authored, drifting example-check is the defect, and the correct
> design pushes laws off that floor — into types — rather than piling more checks onto it.**

| Law tier | What it means | Oracle? | Defect exposure |
|---|---|---|---|
| **Collapse / Unrepresentable-by-type** | the law is the type's invariant; a violation cannot be written | none — true by construction | nil |
| **Generate** | the check is derived from the law's declaration; humans never author the oracle | derived, not authored | nil (the generator is the single authority) |
| **Property (PBT)** | the law is checked over generated inputs; **the floor** | **must be named & classified** (free / model / weak) | real — weak oracle = green-but-empty |
| **Example** | hand-picked inputs; weakest | implicit, un-named | high — drifts, "passes for the wrong reason" |

The decisive correct-design claim: **the §1 oracle problem only exists at the property/example floor.
A law encoded as a type is true by construction — no oracle, no flakiness, no maintenance, no
"passes for the wrong reason."** So the primary move is not "write property tests" but **drain laws off
the floor into types**, and reach for PBT only for the residual that genuinely cannot be lifted. This
is the same logic by which 564 prefers Collapse over Gate — applied to laws instead of copies.

## 4. The correct structure

Four facets, each a completion/re-pointing of a §2 substrate.

### 4a. Laws as types — the load-bearing move

For each law class in the §A.3 inventory, ask first: *can this law be the invariant of a type?* The
strongest realizations make the §A counterexamples and oracle problems **structurally impossible**: **[I]**

- **Round-trip → one bidirectional codec.** Today `SearchAfterCursorHelper` and `ResumeTokenCodec`
  expose `encode` and `decode` as two *independent* static methods (§A.3 #2/#3) — two authorities that
  *can* disagree; the round-trip is a hoped-for property a test must patrol. Collapsed: a single
  `Codec<A>` / invertible parser-printer where one declaration *is* both directions, so
  `decode∘encode = id` holds **because there is no second function to disagree** (the established
  "invertible syntax" / `Iso`/`Prism` technique). The law stops being testable because it stops being
  violable.
- **Budget → a bounded type.** `length() ≤ maxChars` (§A.3 #6) becomes a `Budget`/`Bounded` whose only
  constructor admits within-bound content; an over-budget value is *unrepresentable* (the
  "make-illegal-states-unrepresentable" technique). The conservation law moves from "asserted after the
  fact" to "cannot be constructed otherwise."
- **Offsets → a tiling type.** "Chunks tile `[0,len)` with no gap/overlap" (§A.3 #1) becomes a `Tiling`
  whose smart constructor refuses gaps and overlaps; the splitter *returns a `Tiling`*, so the law is
  the return type's invariant, not a property a test re-derives.
- **Precedence → an ordered structure with a total comparator by construction.** The config ordinal
  chain (§A.3 #4) is already a `TreeMap(reverseOrder())` over ordinals **[V]** — most of the way there;
  the total-order law is closer to "make the comparator's totality a type-level fact" than to a property.
- **Idempotence / framing-invariance** are the hardest to type fully and are the honest *residual*
  (§4c) — though even here, normalizing-into-a-canonical-newtype (a `Normalized<EntityName>` that can
  only be built by `normalize`) makes `normalize∘normalize = normalize` true by construction.

The §A.4 catalog is re-read here not as "what to PBT" but as **"what to collapse, what to generate, and
the small residual to PBT."** Most of it should leave the test tier entirely.

### 4b. The value-law register — laws get a home, on the existing kernel

For the residual that stays at Generate/Property/Example, give value-laws the same treatment
representations got: a register, a sibling to `execution-surfaces.v1.json`. **[I]** Each entry declares:

- the **function/surface** the law constrains (drift-scanned like execution-surfaces, so a law-bearing
  function with no registered law is detectable);
- the **law class** (round-trip / conservation / idempotence / order / framing / bounds / model);
- the **ladder tier** it currently sits at (collapsed / unrepresentable / generated / property /
  example);
- for floor-tier laws, the **oracle class** (free = input-is-spec/round-trip/idempotence; model =
  metamorphic or small hand-model; weak = re-derives the function — the §A.2 trap).

This is the register the codebase is missing — the third authority class (after representations and
prose rules) folded onto the same `RegistryEntry`/kernel substrate, not a bespoke framework.

### 4c. The meta-gate — altitude and honesty, not re-execution

A meta-gate on the 530 kernel, modeled on `prose-tier-register` (which already governs the *tier* of
each prose rule). **[I]** It does **not** re-run the laws — their bound types/tests do — so it is not
parallel enforcement (§2, 547). It governs two things the codebase has no structural handle on today:

1. **Altitude.** Every registered law declares its tier; an **`@Law`-anchored** declaration with no
   registered binding fails (the *anchored*-law completeness check); and **a law moves *up* the ladder
   only via a `tier-raise` changeset** — the ratchet pushes toward Collapse/Generate and treats "add a
   property/example check" as the *least* acceptable resolution, never the default. (Mirrors 564 §4c's
   "added a gate is a signal the concern wasn't pushed up the ladder.") *Honest limit (de-risked §B.5):
   auto-discovery of an **unanchored** law-bearing function is **not** mechanizable — there is no
   syntactic signal for "this function carries a law" — so new-law discovery stays human-judgment, as
   the 530 kernel itself leaves 527-style audits to humans. The gate validates declared laws; it does
   not find undeclared ones.*
2. **Honesty of the floor.** A property-tier law declared with a **`weak` oracle requires an explicit
   changeset justification** — the structural answer to green-but-empty. You may ship a weak-oracle
   property, but not *silently*; the register makes the oracle a reviewed, named artifact, so "this
   property passes for the wrong reason" becomes a declared, auditable state rather than an invisible one.

### 4d. One meta-substrate, not a fifth bespoke guard

Value-laws register as **another instance** of the 481+530 meta-substrate — *canonical law → its
highest-tier realization → completeness/altitude gate* — dispatched by the one `run.mjs`, emitting the
same SARIF, reusing `*ConformanceTest`/`captureOrVerify` as the *floor only*. **[I]** Not a new kernel,
not a "PBT framework," not the "five bespoke solutions" 564 §6 forbids. The §A.4 catalog becomes the
register's seed inventory; the §A.1 counterexamples become the evidence that the floor is untrustworthy
and the law belongs higher.

## 5. Why this prevents the issue long-term

The defect recurs today because **a law-bearing function can ship with its law unstated and unchecked,
and the only thing standing against a later silent break is a patrol that may not exist.** Under this
design: **[I]**

- A new codec / budgeter / parser / normalizer **cannot silently ship an unverified law** *once its law
  is anchored* — the register + meta-gate force a tier declaration and changeset-gate tier-changes /
  weak-oracles. *Honest limit (de-risked §B.5): a brand-new **unanchored** law-bearing function is not
  auto-detectable; that discovery stays human-judgment (the kernel leaves 527-style audits to humans).
  The mechanical guarantee covers **declared** laws — which is still a strict gain over today's "declared
  nowhere," but it is not the "unregistered-referencer" auto-scan that representations enjoy.*
- The **ladder pressure drains laws toward type-level truth**, where the defect *cannot recur* — the
  same reason SearchTrace's four-representation drift cannot recur for search execution: there is
  nothing left to drift, because the law is the type. A round-trip that is one `Codec` has no second
  function to disagree; a budget that is a bounded type has no over-budget state to assert against.
- The contrast with the first draft's "adopt jqwik" answer is exact: a wall of property tests makes a
  broken law *loud* (if the oracle bites) or *invisible* (if it doesn't); only Collapse/Generate make
  the break *impossible*. The first draft's own §A.5 honest limit — "oracle design, not generator
  design, is the gating difficulty" — is dissolved, not managed, by lifting the law off the floor.

## 6. Extend-vs-build map

| Facet | Action | Existing substrate |
|---|---|---|
| 4a laws-as-types | **decompose** codecs→`Codec`, budgets→bounded types, offsets→tiling types, normalized→newtypes | none needed — this is 547's recommended *decomposition* frontier, in the type system |
| 4b value-law register | **mirror** `execution-surfaces.v1.json` for laws | the surface-register pattern + 481 `RegistryEntry` |
| 4c meta-gate | **clone** the `prose-tier-register` tier-governance shape; govern altitude + oracle-honesty | 530 kernel; `prose-tier-register` gate |
| 4d one substrate | **register** value-laws as another 481/530 instance; reuse conformance/capture-or-verify as floor only | discipline-gate kernel (530) + conformance pattern |

Explicitly **not** part of the correct design: a new gate kernel (530 exists), a bespoke PBT framework
or test silo (the floor reuses existing conformance/capture-or-verify), or a register that *re-runs*
laws (that would be the parallel enforcement 547/530 forbid). The only genuinely new artifacts are the
`value-laws` register, its meta-gate, and the **law-bearing types** — and the types are decomposition,
the thing 547 says is the high-value frontier.

## 7. Unification with 555 (mutation testing): the floor's oracle-certifier

The ladder gives 554 and its sibling 555 a single home. **[I]** Collapse/Generate-tier laws are true by
construction — they need no mutant check. The **property/example floor is exactly where mutation testing
earns its keep**: it measures whether a floor-tier law's oracle *actually bites* (kills mutants) — i.e.
it is the mechanical test of the §4b `oracle: weak` declaration. So:

- **554** supplies the floor's *strong inputs* (generators that reach the unimagined cases).
- **555** *certifies the floor's oracle* (mutants that prove the check constrains the code).
- The **register** records which laws are stuck on the floor at all — the worklist for both.

The two are not competing initiatives ("PBT vs mutation testing," the first draft's §A.6 framing); they
are the two instruments of one tier, applied only to the residual that cannot be lifted. A parallel
reframe of 555 around the ladder is warranted but belongs to 555's owner; not branched here.

## 8. Honest tensions and open decisions

1. **How far to push laws-as-types is the central open decision.** Some laws (round-trip, budget,
   offsets) collapse cleanly; framing-invariance and some metamorphic fusion relations may have no
   honest type and stay at the model-oracle floor permanently. The design's value is realized by
   pushing *as high as each law honestly goes* — not by forcing every law to a type. The register's job
   is to record where each law *actually* sits and resist silent slippage downward. **[I]**
2. **Where the law-class taxonomy ends.** round-trip / conservation / idempotence / order / framing /
   bounds / model covers the §A inventory, but the taxonomy is open; a new law class is a register
   schema change, not a code change. **[I]**
3. **This is a major rewrite, and partial application is worth less than none** — the 564 §7.4 caution
   transfers: a `Codec` type added *beside* the old `encode`/`decode` statics yields a *third* authority
   that also drifts. Collapse must retire what it replaces. **[I]**
4. **The first draft's priority verdict is reframed, not discarded.** "PBT is low-priority behind
   mutation testing" (old §16) was a *test-floor* judgment; the correct-design answer supersedes it:
   **lift laws into types first**; PBT + mutation are the instruments for the residual floor. The
   tooling caveat survives as a one-liner — jqwik's maintenance-mode status (§A) matters only for that
   residual floor, and `fast-check`/`hypothesis` (the FE/Python floors) carry no such caveat. **[I]**
5. **Feasibility is deliberately out of scope.** Per the user's framing this theorization ignores cost,
   sequencing, and the real difficulty of retrofitting types onto Java/TS hot paths; an implementation
   tempdoc would weigh those and would almost certainly start by *registering* the existing laws
   (cheap) long before *collapsing* them (expensive).

---

# Appendix A — Evidence ledger (firsthand takeover deep-dive, 2026-06-03)

> The investigation that drove the rewrite. Preserved verbatim-in-substance; recontextualized: where it
> originally argued "where to apply PBT," it now stands as **evidence that the test floor is
> untrustworthy and laws belong higher (§3–§4)**. All **[V]** verified against `main` (fusion file
> identical between worktree base and `main`); line numbers as of 2026-06-03.

## A.1 Two counterexamples — the test-floor oracle is untrustworthy

The score-fusion math (`modules/adapters-lucene/.../HybridFusionUtils.java`) is *three* algebras —
RRF (rank-based, `:132`), CC (min-max score-based, `:295`), CC3 (per-doc renormalized, `:616`). **[V]**
Two firsthand-derived, numerically-reproduced counterexamples show why a *direct* property on fusion
"fails for the wrong reason" — the law-spec error the §1 oracle problem names:

- **CC cross-document non-monotonicity.** Min-max normalization is sample-dependent (`:822`
  `(score-min)/range`, over the per-call set `:273`), so raising one doc's raw score rescales every
  other doc in that leg. Concrete (`alpha=0.5`, sparse `{A:0,B:10,X:11}`, dense `{D:5,E:10,F:8.5}`):
  raising `A:0→9` (A stays bottom, own rank unchanged) demotes **B below F** (`B:0.4545→0.2500`, F
  unchanged at `0.3500`). A naive "raising a leg score never lowers a doc's rank" property reds — but
  the red is a *spec error*, not a bug (Bruch et al., ACM TOIS 2023: fusion must be monotone **and
  scale-invariant in component scores**; min-max buys scale-invariance via exactly this coupling).
- **RRF permutation-sensitivity.** Rank = iteration order (`:127`), so two equal-score docs flip with
  input order (`[P,Q]→P>Q`, `[Q,P]→Q>P`); CC's deterministic `docId` tie-break (`:305`) is
  permutation-invariant. One "permutation-invariance" property is true for CC, false for RRF. **[V]**

**Lesson for the design:** these laws cannot be honestly checked at the *direct property* floor — only
via metamorphic relations (model oracle) or by lifting the representation. They are the archetypal
`oracle: weak/model` register entries (§4b), and the register's job is to force that declaration rather
than let a green property hide it. (Also logged firsthand: an asymmetric null-`docId` rank handling,
`:130` no-increment vs `:150` increment — a latent inconsistency a generator feeding nulls would
surface; recorded in `observations.md`.)

## A.2 The oracle problem — restated as the §1 defect

For most law targets the hard question is *not* "what law?" but **"what oracle?"** Fusion has no
independent "correct order" short of re-deriving the math (tautology) or a metamorphic relation;
chunking/config/codecs have a *free* oracle (the input is the spec). A generator with a weak oracle is
green-but-empty — `static-green ≠ live-working` in the test tier. This is precisely why §3 ranks
*laws-as-types* above *laws-as-tests*: the type has no oracle to be weak.

## A.3 What the candidate code actually is (firsthand)

The wire round-trip the first draft co-led on is the *weakest* test target: there is **no encode/decode
pair** in the FE (flow is unidirectional Java→JSON→TS), and 564's now-*shipped* codegen
(`scripts/codegen/gen-wire-schema-types.mjs` → `search-trace.ts` etc. on `main`, with a
`wire-type-single-authority` gate) makes cross-emitter equivalence a *generation guarantee* — exactly
§3's Generate tier, already realized. **[V]** SearchTrace projection is *already* an exhaustive
gate-backed conformance test (`SearchTraceProjectionConformanceTest`) — the floor, already in place.

## A.4 The law inventory (seed for the §4b register)

The codebase sweep (six scouts, honest-oracle filter, flagships firsthand-verified) found ~10 genuine
value-laws — the seed inventory the register would hold, re-tagged here by **target ladder tier**:

| Law / function | Class | Honest target tier (§3) | file |
|---|---|---|---|
| `ChunkSplitter.splitWithMetadata` offsets | conservation | **Unrepresentable-by-type** (a `Tiling` return) | `modules/indexing/.../ChunkSplitter.java:746` **[V]** |
| `SearchAfterCursorHelper.encode/decode` | round-trip | **Collapse** (one `Codec`) | `…/SearchAfterCursorHelper.java:87/51` **[V]** |
| `ResumeTokenCodec.encode/decode` | round-trip | **Collapse** (one `Codec`) | `…/stream/ResumeTokenCodec.java:30/44` **[V]** |
| `ContextBudgeter`/`TokenAwareBudgeter.appendSection` | conservation/bounds | **Unrepresentable** (bounded type) | `modules/indexing/.../rag/*.java` |
| `ResolvedConfigBuilder.resolve` | order (total) | **Generate/type** (already `TreeMap(reverseOrder())`) | `…/ResolvedConfigBuilder.java:667` **[V]** |
| `parseSseBuffer` framing | framing (metamorphic) | **Property** (floor; free oracle: chunk-boundary invariance) | `modules/ui-web/src/api/sse.ts:65` **[V]** |
| `EntityNormalizer.normalize` | idempotence | **Unrepresentable** (`Normalized<…>` newtype) or Property | `…/disambiguation/EntityNormalizer.java:59` |
| `parseUrl`/`canonicalize` | round-trip | Collapse/Property (already has a corpus) | `…/shell-v0/router/parser.ts:91` |
| `jseval` `percentile`/`compute_stats` | bounds/permutation-invariance | **Property** (floor; `hypothesis`) | `scripts/jseval/jseval/suite_stats.py:47/19` |
| `ModeStateMachine` / `GrpcCircuitBreaker` | model | **Property/model** (model-based) | `…/ModeStateMachine.java`; `…/grpc/GrpcCircuitBreaker.java` |

**Explicitly rejected** (and why — the discriminating half): `HybridFusionUtils.fuse*` (oracle is
re-derive-the-math; metamorphic-only floor at best — §A.1); `normalizeScore`/`minScore`/
`linearInterpolation` (2–6-line closed-form helpers; the formula *is* the oracle — example tests
suffice, PBT is ceremony; a scout rated these "High" — that was over-generation); `SearchTraceProjector`
(already conformance-gated); `VariantSelector.select` (a decision *table*, not a law); native-adjacent
NER/tokenizer code (determinism boundary). The rejections matter: they are laws that should stay
*example*-tier or have *no* law, and the register would record exactly that.

## A.5 Tooling note (residual floor only)

jqwik (Java) is in **pure maintenance mode** — core (generators, shrinking, stateful) is feature-
complete, so it is acceptable for the residual floor, recorded knowingly, with QuickTheories as
fallback. **[V]** `fast-check` (TS) and `hypothesis` (Python) are actively developed. This matters
*only* for laws that cannot be lifted off the floor (§3); for everything else there is no framework to
choose because there is no test.

---

# Appendix B — De-risk ledger (confidence-building pass, 2026-06-03)

> A firsthand pass that stress-tested the rewrite's load-bearing claims before any implementation slice
> — **not** implementation. Each finding carries a verdict: **UPHELD** (with evidence), **CORRECTED**
> (claim refined in place), or **CALIBRATED-DOWN** (claim was too strong). The most important outcomes:
> the catalog committed *my own* §A.1 sin (naive law statements), and the laws-as-types Collapse tier is
> materially weaker in Java than the rewrite implied. Net: the *direction* of the thesis stands; several
> *claims* are tightened.

## B.1 True-law verification — the catalog had naive statements (CORRECTED)

Firsthand reads of each flagship body show the laws are real but several were stated too cleanly — the
same error §A.1 catches in fusion, now applied to my own catalog:

- **`ChunkSplitter.splitWithMetadata`** — stated "offsets tile, **no gap/overlap**." **WRONG by design:**
  `:792` `advance = max(endPos-position-overlapChars, minChars)` makes adjacent-chunk **overlap
  intentional** (sliding-window RAG). The code's *actual* law is its own documented invariant (`:783-788`,
  a historical "Bug fix"): `content.substring(startChar,endChar) == chunk.content()` (per-chunk
  reconstruction), plus **no-gap**, bounded, monotonic. Free oracle survives; the statement was naive.
- **`SearchAfterCursorHelper`** — round-trip is **partial** (per sort, only the active numeric slot is
  populated; others are `_`→null) and **cross-type** (`encode` takes a Lucene `ScoreDoc`/`FieldDoc`,
  `decode` returns a flat `DecodedCursor` record — there is *no single `x`* for `decode(encode(x))==x`).
  The score round-trip is **exact** (`Float.parseFloat(Float.toString(x))` is the Java identity incl.
  NaN/Inf; `_` is unambiguous vs base64/numerals; docId base64 is exact). Law HOLDS — but it is *not a
  clean `Iso`*, which matters for B.4.
- **`ResumeTokenCodec`** — round-trip HOLDS for `seq≥0`, and robustly: `StreamId` is a *validated*
  newtype (`^(registry|surface|system):[a-z][a-z0-9-]*$`), so the encoded `<kind>:<id>:<seq>` has exactly
  two colons and the last-colon split is unambiguous. Latent asymmetry: `encode` does **not** reject
  `seq<0` but `decode` does → `encode(sid,-1)` does not round-trip (minor; logged as a real inconsistency).
- **`ContextBudgeter.appendSection`** — conservation is **exact** (`length ≤ maxChars` always; truncation
  fills to *exactly* `maxChars`). But truncation is `substring(0,n)` (UTF-16 code units) → can split a
  **surrogate pair** (lone surrogate). Budget law holds; "truncation is a clean prefix" has an
  adversarial-Unicode edge — a genuine floor finding.
- **`ResolvedConfigBuilder.resolve`** — total-order + idempotent (result-wise; the `resolvedKeys`
  mutation is observational) + blank-skip all HOLD. But **insertion-order-independence is conditional**:
  the `TreeMap` is keyed by ordinal, so two sources at the *same* ordinal collide → last-`put`-wins →
  order-dependent. Holds only for distinct ordinals (qualification added).
- **`parseSseBuffer`** — framing-invariance HOLDS; see B.2. The cleanest floor candidate.

**Verdict:** the catalog laws are real but **§A.4's one-line statements are corrected above**; the
chunking flagship's law is per-chunk-reconstruction (overlap-allowed), not "no overlap."

## B.2 Empirical floor proof-of-concept (UPHELD)

A throwaway experiment exercised the **real** `parseSseBuffer` outer framing algorithm (its exact
`doubleLineBreakRegex` + remainder-carry, ported verbatim) under framing-invariance: parse one stream
whole vs. fed at arbitrary chunk boundaries. **Result: 12192/12192 chunkings matched whole-parse, 0
failures** — every single split point + 2000 random multi-splits across 6 streams incl. mixed/`\r\r`/
mixed-style double-breaks. The metamorphic floor property holds and a generator covers what the ~7
hand-split example cases cannot. The cursor/budget/config laws were settled **analytically** (language
guarantees / regex validation / exact arithmetic), so a heavier gradle throwaway was not run.

## B.3 jqwik toolchain integration — RESIDUAL GAP (not run)

Confirmed **zero** existing `jqwik`/`fast-check`/`@Property` usage (novelty UPHELD). The toolchain smoke
(jqwik in this Gradle/JUnit5-platform/Spotless/PMD/ErrorProne build) was **not run**: the worktree's
`ui-web` has no `node_modules`, and a Java jqwik trial needs a repo-wide lockfile regen — disproportionate
for a throwaway. **This is the one residual empirical unknown**, and the cheapest place to settle it is
*step 1 of any implementation slice* (when you are committing the dependency anyway), not a throwaway now.

## B.4 Laws-as-types feasibility in Java/TS — CALIBRATED DOWN (the crux)

The rewrite's §3/§4a implied most laws Collapse into types. Firsthand calibration splits this:

- **Bounds / idempotence / normalization → Collapse is feasible *with precedent*.** The repo **already**
  uses make-illegal-states-unrepresentable: `StreamId` (regex-validated record) and `ResumeTokenCodec.Decoded`
  (compact-constructor `seq≥0`) are smart-constructor newtypes. So a `Bounded` budget, a `Normalized<…>`
  entity name, a `Tiling` offset type are idiomatic here.
- **Round-trip → Collapse is NOT cheap; it is greenfield.** There is **zero** `Codec`/`Iso`/`Prism`/optic
  infrastructure in the codebase, and the cursor's encode/decode are *asymmetric cross-types* that resist
  a clean `Iso` entirely. So round-trip laws realistically live at **Generate** (generate the round-trip
  property from a declared codec pair) or the **Property floor** — *not* Collapse — in this Java codebase.

**Verdict:** the honest end-state is **"some Collapse (bounds/newtypes, precedented), more Generate, a
real Property floor"** — *not* "most candidates collapse to types." §3's ladder and §4a's direction stand;
§4a's specific "round-trip → one `Codec`" example is **downgraded to Generate/Property for Java** (the TS
side is somewhat better but still has no codec infra). This is the rewrite's most important correction.

## B.5 Mechanism admissibility + the discovery limit — CONFIRMED with a key correction

- **Admissible, with an exact surviving precedent.** `prose-tier-register` is a meta-gate that reads
  "the register itself + `registry.v1.json`", governs tier-changes via declared changesets, and does
  **not** re-run the rules — precisely the shape proposed for value-laws. So §4c is buildable and is *not*
  parallel enforcement.
- **But the 530 kernel has a documented record of *retiring* coverage-gates that give "no mechanical
  signal an automated check could supply" and "false-failed on no-op refactors"** (`independent-review`,
  `ux-audit-closure`; `discipline-gate-kernel.md` §"What this kernel does NOT do" — it "is not a
  substitute for human-judgment audits … the human calibrates edges"). This **bounds the meta-gate**: the
  survivable, mechanical parts are *anchored*-law completeness + tier-change/weak-oracle changesets
  (prose-tier-register-shaped). The rewrite's claim that the gate "**detects a law-bearing function with
  no registered law**" (old §4c/§5) is **overstated** — there is no syntactic signal for "carries a law,"
  so that is exactly the no-mechanical-signal check the kernel retires. **Corrected in place** (§4c.1, §5):
  the gate validates **declared/`@Law`-anchored** laws; *discovery* of undeclared laws stays human-judgment
  (a register covers only declared concepts — CLAUDE.md's own honest limit). Still a strict gain over
  "declared nowhere," but weaker than the representation registers' auto-scan.

## B.6 Dev-server blast-radius — not run (out of tier)

Value-laws are on pure functions; the dev server is the wrong tier. A WS6 confirmation (a broken cursor
→ duplicate/skipped live-paging results) would be assertion-grade obvious and is not worth a dev-stack
spin for a theorization. Deferred.

## B.7 Net confidence

| Claim | Pre | Post | Why |
|---|---|---|---|
| Mechanism (register + meta-gate on 530) | medium | **high (scope-corrected)** | exact `prose-tier-register` precedent; but governs *anchored* laws + altitude + oracle-honesty, **not** auto-discovery |
| Laws-as-types is the primary move | high | **calibrated** | Collapse only for bounds/idempotence/normalization (precedent); round-trips → Generate/Property (no codec infra) |
| Catalog laws (§A.4) | medium | **corrected** | chunk overlap-by-design; cursor partial/cross-type; budget surrogate edge; config order-independence conditional |
| Floor works empirically | unproven | **upheld** | framing-invariance 12192/12192 |
| jqwik integrates with this build | unproven | **residual** | settle as implementation step-1, not throwaway |

**Bottom line:** no load-bearing claim was *falsified*, but the catalog statements and the laws-as-types
Collapse optimism were too strong and are now tightened; the mechanism is confirmed buildable with a
sharper, smaller scope; the floor is empirically real; and exactly one cheap empirical check (jqwik
toolchain) is deferred to implementation step-1. The design is materially de-risked.

---

# Appendix C — Build log (2026-06-03) — DATED HISTORY, SUPERSEDED BY §C.1

> **Read this first.** The phases below record what was *built* under the `/goal` run. That substrate
> was subsequently **removed** in the retrenchment recorded in **§C.1** — a critical-analysis pass found
> it was the over-engineering this tempdoc's own §8/§B ledgers warned against. The phase log is retained
> as honest history of the build; for the final shipped state read §C.1. The canonical reference doc
> mentioned below (`value-law-verification.md`) was deleted in the retrenchment; the surviving guidance
> lives in `docs/reference/contributing/testing-quality.md`.

The correct-design structure was implemented in full (six phases; all unit + governance gates green).

**Phase A — root-cause bug-class fixes.** `core/util/Strings.codePointSafePrefix` (surrogate-safe
budget truncation) applied at the three RAG truncation sites (`ContextBudgeter`, `TokenAwareBudgeter`,
`TokenEstimation`) — closing the §B.1 surrogate-split defect *class*, not one site. `HybridFusionUtils`:
a null `docId` no longer consumes a rank slot in the result2 leg (symmetric with result1).

**Phase B — laws-as-types.** `core/law/Codec<A>` makes the round-trip law one type; `ResumeTokenCodec`
implements `Codec<Decoded>` (round-trip total over the domain — `seq<0` unrepresentable) and gained
encode `seq>=0` symmetry. `indexing/ChunkTiling` makes the chunk-offset **conservation** law the
invariant of a validating type; `ChunkSplitter.tiling()` returns it. **The jqwik conservation property
surfaced a real bug** — `ChunkSplitter` computed content with `trim()` (ASCII) but offsets with
`strip()` (Unicode whitespace), so offsets drifted from content for control / non-ASCII-whitespace
input; **fixed at root** (both use `strip()`). This is the §B.4 calibration confirmed: round-trips and
conservation reach Collapse/Unrepresentable in Java via smart-constructor types, exactly the precedent
(`StreamId`, `Decoded`) the de-risk found.

**Phase B (toolchain) — jqwik wired.** jqwik 1.10.1 on the version catalog (junit 5.14.3→5.14.4 /
platform →1.14.4 to meet jqwik's minimum; lockfiles + sha256 verification-metadata regenerated). New
opt-in `@Tag("pbt")` lane (`-PincludePbt=true`, mirroring the stress lane). The §B.3 residual gap is
**closed** — jqwik runs cleanly on the Java-25 / JUnit-5.14 build.

**Phase C — the governance substrate.** `@Law("<id>")` (source-retention, `core/law`) anchors a law to
its registry row; applied to the four Java laws. `governance/value-laws.v1.json` is the register — the
**third authority class** after representations (`execution-surfaces`) and prose rules (`tier-register`).
The `value-law` discipline gate (enforcer + truth-table + classifications + rule-descriptions + fixtures
+ registry entry, on the 530 kernel) cross-validates `@Law`↔register, checks tier/oracle validity, and
gates tier changes + weak-oracle acceptances via changesets. Per §B.5 it governs **anchored** laws only
(new-law *discovery* stays human-judgment — the kernel's documented limit). Self-test green
(positive pass / negative fail); the negative fixture exercises the novel **oracle-honesty** rule
(a `property`-tier `weak` oracle without a changeset fails). Full governance self-test: no regression.

**Phase D — the property floor in three ecosystems.** jqwik (Java: `Strings`, `ResumeTokenCodec`,
`ChunkTiling`); fast-check (TS: SSE framing-invariance over arbitrary chunk-boundary splits of the real
`parseSseBuffer`); hypothesis (Python: `jseval.suite_stats` percentile/compute_stats invariants). All
green.

**Phase E — docs.** This log; the canonical `value-law-verification.md`; the kernel doc's gate table.

**What this validates about the theory.** The de-risk's central calibration (§B.4) held under
construction: laws-as-types is real and idiomatic for bounds/conservation/round-trip via smart
constructors, while the *mechanism* (register + gate) is genuinely prose-tier-register-shaped and
non-parallel. And the floor paid for itself on day one — a generated counterexample found a shipping
bug an example test had missed, which is the only honest proof the technique adds something here.

# Appendix C.1 — Retrenchment (2026-06-03) — THE FINAL STATE

A critical-analysis pass over the §C build found the substrate was largely the over-engineering this
tempdoc's own §8 ("honest tensions") and §B ledgers warned against, with concrete symptoms:

- **The meta-gate could not verify its own central claims.** `value-law/enforcer.mjs` checked `@Law`
  presence and that `tier`/`oracle` were valid *enum values*, but never that a row's `test` existed or
  exercised the law, nor that the claimed *tier* matched reality. The register could name a missing or
  unrelated test, or inflate a tier, and stay green — the substrate's whole promise, unenforced.
- **Tier-inflation.** `chunk-offset-conservation` was registered `unrepresentable`, but `ChunkTiling` /
  `ChunkSplitter.tiling()` were referenced **only by the test** — production returned raw `List<Chunk>`,
  so the "throws at construction" guarantee never fired. It was a property test wearing a type's clothes.
  `cursor-search-after-roundtrip` and `config-precedence-order` were `generated`-tier with no test and no
  anchor — decorative rows.
- **The property floor was dormant.** The three jqwik properties were `@Tag("pbt")` and the convention
  plugin excluded `pbt` by default, so the regression-catching floor did not run in `./gradlew test`.
- **`@Law` coverage was asymmetric** — the truncation-bounds law lived at three sites; only one was
  anchored/registered.

**Decision (user): retrench to the residue** — honor the original negative verdict. The register, the
`value-law` gate (enforcer + truth-table + classifications + rule-descriptions + fixtures + registry
entry + kernel-doc row), the law-tier ladder, the `@Law`/`Codec` type layer, and the standalone
`value-law-verification.md` were all **removed** (~1,200 lines). What stays, made honest:

- **Bug-class fix kept:** `Strings.codePointSafePrefix` (surrogate-safe) at all three RAG truncation
  sites — `@Law` stripped, the fix unchanged.
- **The two real `ChunkSplitter` fixes kept:** `trim()`→`strip()` (offset/whitespace consistency) and the
  boundary clamp (StringIndexOutOfBounds), both surfaced by the conservation property.
- **`ResumeTokenCodec` `seq>=0` encode guard kept** (genuine encode/decode symmetry); the `Codec` field
  removed.
- **`HybridFusionUtils` null-`docId` rank symmetry kept** (a correct latent-bug fix in a near-dead branch).
- **Property tests kept and ungated:** `@Tag("pbt")` and the opt-in lane removed, so the jqwik /
  fast-check / hypothesis properties now run in the **default** suite — where they actually guard `main`.
  jqwik/fast-check/hypothesis are retained as the right tool (the deliberate divergence from the original
  framework-free draft); `ChunkTiling` moved to test sources as an honest validation helper.
- **Guidance kept, machinery dropped:** a short "Value-law verification" section in
  `docs/reference/contributing/testing-quality.md` (prefer unrepresentable-by-type → free-oracle property
  test → call out weak oracles) replaces the register/gate prose.

**The honest lesson.** The genuinely valuable output of this whole exercise was four small fixes and a
handful of property tests — found by *doing* the property work, not by the register/gate that wrapped it.
The §1–§16 theorization remains a useful map of the law-class; the substrate to govern it was not worth
building, exactly as the first-pass analysis concluded before the `/goal` overrode it. Two latent
asymmetries remain logged in `docs/observations.md` as deferred (null-`docId` rank in the result1 leg;
no further `ResumeTokenCodec` work).
