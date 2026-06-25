---
title: "Contract Source-of-Truth — Correct Design (the format is the defect; JSON-Schema is the answer; the gate is the floor)"
status: done
created: 2026-05-31
updated: 2026-06-03
supersedes-self: "Earlier draft titled 'Single-Authority Contract Projection — Correct Design'. Rewritten 2026-05-31 after an implementation pass disproved its load-bearing assumption (proto can be the single source) and exposed its missing mechanism discipline (generate, don't gate). History in git."
related: [563, 481, 530, 549, 551, 553, 557, 558, 559]
adrs: ["the retired 421 FE-rewrite draft 50-decisions/08-wire-contract-source-of-truth", "docs/decisions/0039-contract-substrate", "docs/decisions/0040-wire-contract-format", "the retired 421 FE-rewrite draft 10-kernel/05-contract-substrate"]
audience: architect
---

# 564. Contract Source-of-Truth — Correct Design

> **What this is.** A *theorization* of the correct long-term structure for the FE↔backend
> contract boundary, **disregarding feasibility, migration sequencing, and effort** (those
> belong to a later implementation tempdoc). It deliberately **prefers completing existing,
> proven substrates over inventing new ones**.
>
> **Why it was rewritten.** The first draft (same number) stated the right *principle*
> ("one source, every representation a projection, the second copy unrepresentable") but
> was wrong at the load-bearing point: it assumed the existing wire-contract format (proto3)
> *could* be that single source. An implementation pass (2026-05-31, §8) **proved it cannot**,
> and the pass's own workaround revealed the deeper discipline the draft omitted: it leaned on
> drift-*catching gates* where the principle demands drift-*preventing structure*. This rewrite
> corrects both. Every thesis traces to ADR-08/09, the §9 evidence ledger, or the §8 post-mortem.

---

## 1. The problem, re-diagnosed

Tempdoc 563 §9 measured the friction: a per-feature "fixed tax" (catalog entry + count-test +
i18n label in another module + bootstrap wiring), a 2,400-line route god-file, and an FE that
keeps **four parallel, hand-synced wire-type sources** that drift silently (`.loose()` Zod fails
open; `HealthEvent` defined 4×; ~70 hardcoded endpoint literals; hand-mirrored enums — one
already shipped a bug). The first draft diagnosed this as "the structure permits a second
authority." True, but it stopped one layer too shallow. The implementation pass exposed the
**root**:

> **There is no single source that is simultaneously (a) expressive enough to faithfully model
> the actual wire data, (b) language-neutral, and (c) able to generate every target (Java, TS,
> Zod, OpenAPI, the FE consumer). The codebase owns two *partial* sources, and because neither
> is whole, both persist and the hand-mirrors persist with them.**

- The **proto/wire** source is neutral and code-generates Java + TS — but is **not expressive
  enough**. proto3 cannot model the project's own wire shapes: `KnowledgeSearchResponse.facets`
  (a map-of-map) becomes a `FacetCounts` *wrapper message* whose JSON gains a nesting level the
  real wire does not have; `HealthEventBody.attributes` (arbitrary JSON) becomes
  `google.protobuf.Value`, a tagged-union wrapper that is not the plain value the FE reads
  (§9b proves both). So the records **cannot** be generated from the proto without changing the
  wire — and they are not. The proto became *an island the FE barely consumes* (~3 files).
- The **Java record** source is expressive (it *is* what Jackson emits, so it is faithful by
  construction) and a faithful JSON-Schema can be generated from it (victools) — but it is
  **not neutral**: it is a Java artifact. Generating the *proto* from it hits the same
  wrapper problem in reverse, and a future non-Java consumer would have to port from Java.

The decisive observation: **ADR-08 already chose the correct destination but the format choice
that realizes it was deferred to a spike, and the spike picked the inexpressive option.** ADR-08
commits verbatim — *"Java records, TypeScript interfaces, Zod runtime schemas, and OpenAPI
documentation are all mechanically generated from this artifact. Hand-written per-language
mirrors of the wire contract are forbidden"* — then explicitly **defers the format**
(*"OpenAPI 3.1 / TypeSpec / Smithy / protobuf with JSON mapping / Java-internal annotation DSL /
other … deferred to spike work … and may be recorded as a follow-up ADR"*) with a stated
acceptance bar: it must be evaluated *"against actual JustSearch wire types (HealthEvent
discriminated unions, CapabilitiesView versioned slots, KnowledgeSearchResponse deep nesting,
`@JsonUnwrapped` cases)."* The implementing slice (3a-1-8, ADR-09a) picked **protobuf with JSON
mapping** — the one candidate that **fails that very bar**. ADR-08 even pre-recorded the
symptom: snake/camel `.loose()` drift is *"already broken in production,"* and a
`json-schema-to-typescript` spike produced *"28 fields, every one optional."*

**So the format pick is the structural defect.** Everything downstream — two un-unified
authorities, the FE's four sources, the per-feature tax — is a consequence of choosing a source
format that cannot host the data, which makes "generate everything from one source" impossible
and leaves hand-authoring as the only option.

The first draft's principle is right. Its §6 line *"explicitly **not** part of the correct
design: a new IDL (the format is validated)"* is **wrong** — the format was never validated
against the hard cases, and it fails them. **The right format is, however, already in the repo:**
the de-risk (§11) proves the victools record→JSON-Schema output represents those exact hard cases
faithfully (no wrappers) — so the fix is *not* a new IDL but **completing the record→JSON-Schema→FE
generation that already half-exists**, with proto demoted from "the source" to a derived wire-view.

---

## 2. What already exists — the foundation to complete

The correct design is reachable by *finishing and correcting* existing work, not inventing.

- **The wire-contract substrate (ADR-08/09/09a, `10-kernel/05-contract-substrate.md`,
  slices `3a-1-8`).** ADR-08 fixes the destination ("one artifact, all generated, hand-mirrors
  forbidden") and — crucially — **provisions a follow-up ADR to re-record the format**. ADR-09
  generalizes to a seven-axis contract where *"producer-vs-consumer drift is impossible because
  both project from the same source,"* with **symmetric CEL runtime invariants on both Java and
  TS** (validation-as-projection, already designed). State: **destination committed, format
  mis-picked, generation ~30% executed** (Phase 3 "records from the contract" + Phase 4 "retire
  the hand FE sources" never done — the source-of-truth is *inverted*: the proto hand-mirrors
  the records today).
- **The 481 `RegistryEntry` meta-substrate.** Every declarable thing is a `RegistryEntry` whose
  `shape` **is** the ADR-09 contract instance, carrying a `consumers: NonEmpty<ConsumerHook>`
  completeness invariant. 481 already names the disease — *"seven instances of the same root
  defect: parallel decompositions of things the backend declares for consumers to read"* — and
  the cure (one substrate). State: theory + partial; the projection-unification enforcement is
  deferred.
- **The 530 discipline-gate kernel.** A four-layer kernel (`enforcer + truth-table + rule-
  descriptions + classifications`, one `run.mjs`, unified SARIF); **15 gates ride it.** This is
  the generalized coverage-gate layer — the *floor* of the projection-tier ladder, already real.
- **Two proven instances** that the design is the generalization of: **SearchTrace** (549/551/553
  — one canonical record, every surface a governed projection, enforced on a shared
  `execution-surfaces.v1.json` register) and **presentation authority** (557/558/559 — one
  authority per concept, every consumer a projection through `present()`, on `check-*.mjs` gates).
  Evidence (citations) and SearchTrace already **share one register and one gate** — the
  meta-substrate is already emerging.
- **`api/lifecycleState.ts`** — the in-repo proof of the correct FE shape: it derives the FE
  `LifecycleState` constants *from* the generated enum. Single-authority by construction. The
  template; not yet the norm.
- **Generation infrastructure present:** proto→{Java, TS} (`api-contract-projection-java`,
  `protoc-gen-es`); **record→JSON-Schema** (victools, `:*:updateSchemas`). **Absent:**
  schema→{TS, Zod, Java} — the generators the correct design needs.

**Honest state of the foundation:** the gate layer is generalized (530, shipped); the projection
layer is recognized as one thing but re-implemented per domain (481 deferred); and the contract's
**source format is the wrong one** (proto3, fails the hard cases). So the correct design is not
"invent a projection abstraction" — it is **"pick a source format that can actually host the
data, generate every representation from it, and unify the whole thing onto 481 + 530."**

---

## 3. The unifying principle (refined)

Unchanged in spirit, sharpened in mechanism:

> **Every cross-boundary fact is declared once in a canonical source that is expressive enough
> to model it faithfully. Every consumer representation — Java type, TS type, runtime
> validation, JSON schema, OpenAPI, FE consumer, label slot, route — is a *projection* of that
> source: generated or type-collapsed, never hand-authored. The hand-authored second copy is the
> defect class, and the correct design makes it *unrepresentable* — not merely caught at build.**

Two sub-principles make it precise — and the second is where the implementation went wrong:

- **The projection-tier ladder** (548 vocabulary; strongest first): **Collapse** (one type;
  no second copy exists) > **Unrepresentable-by-type** (a second copy cannot compile) >
  **Generate** (humans never author the copy) > **Gate** (drift is caught at build). Push each
  concern as **high** as it can go.

- **Gate is the *floor*, not the *mechanism*.** §5's promise ("closed structurally, not
  patrolled by discipline") holds **only** when the load-bearing concerns sit at Collapse/
  Generate. A conformance test that confirms two authorities agree, a capture-or-verify that
  confirms a generated file matches its source, a lint that refuses a known-bad shape — these
  are valuable as a *backstop* for what genuinely cannot be collapsed, but **they patrol drift;
  they do not prevent it.** If the design's hard parts are realized as gates, the second copy is
  still representable — just watched. §8 documents an implementation pass that did exactly this
  and thereby *did not achieve the principle*. The discipline: **for every concern, ask "can
  this be Collapsed or Generated?" and only fall to Gate when the answer is provably no.**

- **Mandate over capability.** "Drift is structurally impossible" is true only under mandate.
  The codebase's own `structural-defects-no-repeat` rule says one documented silent drift proves
  the bug-class; 563 §9.2 documents several. The threshold for capability→mandate is met.

---

## 4. The correct structure

Five facets, each a completion/correction of §2's substrates. The through-line: **move the
contract boundary up the ladder to Generate/Collapse, beginning with the source format.**

### 4a. One source, in a format that can actually host the data

The load-bearing move, restated correctly **and now de-risked (§11)**. The two authorities
(proto/wire and hand-written REST-records) collapse into **one canonical contract** expressed in a
format **expressive enough to model the real wire shapes** — and the de-risk identifies that
format concretely: **JSON Schema**. §11 proves it against the live fixtures — victools *already*
emits a JSON Schema from the records that faithfully represents the exact cases proto3 fails
(map-of-map facets → bare `object`, no `FacetCounts`; arbitrary-JSON attributes → bare `object`,
no `google.protobuf.Value`; `HealthEvent` body → `anyOf` union). **JSON Schema does not inherit
proto3's expressiveness ceiling.** So the correct move is **not "adopt a new IDL"** — TypeSpec /
Smithy were the rewrite's search space, but they are unnecessary for faithfulness. It is
**complete the existing record→JSON-Schema→{TS, Zod, Java, OpenAPI} generation** that victools has
half-built, and demote proto from "the source" to a derived wire-view (or retire it). This is even
more *"extend, don't invent"* than the rewrite framed. The residual work is **emission precision**
(victools is currently lossy on value-types/required-ness — the all-optional symptom ADR-08
flagged; §11) plus the **schema→{TS, Zod} generators** — execution, not a structural re-pick.

From that one source, the Java wire records and the FE types become **projections**, not
independent artifacts — flipping today's inverted source-of-truth (proto hand-mirrors records →
records generated from the contract). `KnowledgeSearchResponse` *is* a view of the contract; the
REST path and the proto path stop being two worlds. Until this is one source in a sufficient
format, every other improvement still synchronizes copies — which is precisely what the §8
implementation, lacking this, was forced to do.

*Authority direction — the one genuinely open decision, sharpened by the de-risk.* Both
directions are now viable, because JSON Schema is proven faithful (§11):
- **record-as-IDL** — the Java record is authored, victools emits the schema, TS/Zod are generated
  from it. **Feasible now and half-built** (it is the a5 direction, matured); pragmatic because the
  record is the runtime authority — but it privileges Java and leaves the record hand-authored.
- **schema-as-source** — the JSON Schema is authored, and the record + TS + Zod are generated from
  it. ADR-08's neutral destination and the **strongest** form (even the record is generated, so a
  hand-authored wire record is *unrepresentable* and a non-Java port is just another generator) —
  but a larger lift (author schemas; add a schema→Java generator).

The rewrite *preferred* neutral on principle; the de-risk shows record-as-IDL is the
proven-feasible, lower-risk path. So the honest framing is: **both work; the choice is
pragmatic-now (record-as-IDL) vs neutral-purist (schema-as-source); it is a user decision (§7.3),
not a foregone preference.** Either way the format is JSON Schema and proto stops being a source.

### 4b. Every representation is a projection — *including the primary hand-authored one*

From the one source, all representations are generated or type-collapsed: Java types, TS types,
**runtime validation** (the symmetric CEL ADR-09 already proves works both sides — validation
becomes a *projection of the source's invariants*, retiring the fail-open `.loose()` Zod), JSON
schema, OpenAPI. The FE consumes the generated types **directly** — `lifecycleState.ts`
universalized — so the four parallel FE sources collapse to one.

The correction the §8 pass missed: the four sources are not equal. `wire-types.ts` and `*_pb`
are the *generated* pair (the "island" the FE barely consumes); **`api/domains/*` (13 modules)
and the 889-line, 384-usage `.loose()` Zod in `api/schemas.ts` are the hand-authored authority
that carries the actual user-facing data (search, settings).** The correct design's target is
**that** layer: `api/domains` types and the Zod schemas are *generated from the contract* and
retired as hand artifacts. Collapsing only the generated pair (as §8 did) leaves the primary
drift source untouched — and risks **adding a third representation** rather than removing one
(the §7 trap). Endpoint identifiers and enum vocabularies are projected too (no hand-typed URL
literals or hand-mirrored enums — the class that already shipped a bug).

### 4c. The declaration generates the mechanical legs **and subsumes their gates**

Extend the catalog/Manifest substrate so **one capability/surface declaration generates every
mechanical leg**: catalog membership (already), the i18n-key *slot* (the requirement, not the
prose), route registration, handler/view *binding* (the wiring, not the body), and the
FE-consumer scaffold. The natural completeness hook is 481's `consumers: NonEmpty<ConsumerHook>`
(a declaration is complete iff all its projected legs are satisfied).

**The non-negotiable discipline:** generation must **subsume** the independent per-leg ERROR
gates (`I18nKeyValidator`, the count-test, `check-shape-view-coverage`), **not coexist** with
them. Coexistence is the *half-applied* failure — the tax doubles. §8 records the exact mistake:
faced with the per-op i18n-key declaration, the implementation *added a convention-checking gate*
(`I18nKeyConventionValidator`) instead of **generating the slot from the id and deleting the
hand-declaration + its validator**. The correct move is to push the leg to Generate (the slot is
derived) and remove the gate, not to add a higher gate. After 4c, exactly **two** things remain
hand-authored, and they are irreducible: the label **text** (content) and the handler/view
**body** (behavior). Everything mechanical is projected.

### 4d. Mandate = unrepresentability, not a drift ratchet

The enforcement ADR-08 specified becomes real and **mandated as structure**: a hand-authored
contract-shaped type, a second copy of a wire fact, a hand-typed endpoint/enum mirror — each is
**refused by build because there is no way to author one** (the generated artifacts are the only
wire types; they are read-only and regenerated, not editable). This is stronger than the §8
pass's gates, which *catch* a drifted copy after it is written: the correct mandate makes the
copy **unrepresentable** (Unrepresentable-by-type / build-refusal), so it cannot be written in
the first place. A ratchet against a frozen baseline is a legitimate *transition* device while
4a/4b migrate, but the **end state is unrepresentability, not a perpetual ratchet** — the ratchet
is scaffolding, not the structure.

### 4e. One meta-substrate, on the existing kernel

Recognize wire-contract, registry, SearchTrace, presentation, **and** the FE-contract boundary
as **instances of one substrate**: *canonical source → governed projections → coverage gate*.
The gate half exists and is shared (530); the projection half is 481's deferred unification. The
correct design **completes 481** (the `RegistryEntry` whose `shape` is the ADR-09 contract; the
FE-contract registered as another instance, exactly as 557 §3 proposes) and **rides the 530
kernel** — the projection generators and the residual floor-gates dispatched by the one
`run.mjs`, emitting unified SARIF. The §8 pass built **bespoke standalone tests** instead
(`WireTypesTsGenerationTest`, the X-cut conformance tests, `WireShapeMandateTest`) — the *"five
bespoke solutions"* §6 forbids. The result must be one principle with N instances on one kernel,
not N bespoke guards.

---

## 5. Why this prevents the issue long-term

The friction recurs today because the structure *permits a second authority* — and, more subtly,
because the only thing standing against drift is a patrol (gates), which a new feature can
out-run. Under this design, **a hand-authored second copy is unrepresentable**: there is nothing
to drift between, because every consumer view is, by construction, a generated/collapsed
projection of one source — and that source is finally a format that can hold the data, so the
"can't represent it, so hand-author it" escape hatch is closed. New instances inherit the
property for free: a new surface, field, or plugin contract cannot introduce a second authority,
because the substrate admits only declarations-and-projections. The defect class is closed
**structurally**, not patrolled by discipline — the same reason SearchTrace's four-representation
problem cannot recur for search execution. The explicit contrast with §8: a wall of conformance
gates makes drift *loud*; it does not make it *impossible*. Only Collapse/Generate does.

---

## 6. Extend-vs-build map

The correct design builds little genuinely new; with the de-risk (§11) the **format is settled**
(JSON Schema), so its one real *decision* shrinks to the **authority direction** (§7.3).

| Facet | Action | Existing substrate |
|---|---|---|
| 4a one source | **complete** the victools record→JSON-Schema path (half-built): make emission precise + add the schema→{TS,Zod} generators; demote proto to a derived wire-view | wire-contract substrate; **victools record→schema (already emits faithful schemas — §11)** |
| 4b all-projections | **finish** Phase 4: generate FE types/validation, incl. `api/domains` + Zod; retire hand copies | wire-contract substrate; `lifecycleState.ts` pattern; victools record→schema |
| 4c generative legs | **extend** the catalog/Manifest substrate; generate the legs and **delete** the per-leg gates | registry substrate + 481 `consumers` invariant |
| 4d mandate | **ship** unrepresentability (read-only generated artifacts; build-refusal of hand-authored wire types) | a structure on the kernel — no new kernel |
| 4e one meta-substrate | **complete** 481; register the FE-contract; ride 530 | 481 + discipline-gate kernel (530) |

Explicitly **not** part of the correct design: a new gate kernel (530 exists), a new projection
abstraction from scratch (481 is the abstraction), or — the correction to the first draft — proto3
as the source format (it cannot host the data; **JSON Schema, which victools already emits from the
records, is the faithful source** — §11). Nor a *new* IDL (TypeSpec/Smithy): unnecessary now that
JSON Schema is proven faithful.

---

## 7. Honest tensions and open design decisions

1. **Two irreducible legs remain.** Label *text* and handler/view *bodies* are content and
   behavior; no design generates them. "Only these remain hand-authored" is the goal.
2. **The format is settled; the residual format risk is emission *precision*.** The de-risk (§11)
   answers the format question — JSON Schema, which victools already emits faithfully from the
   records (no proto3 wrappers). What is *not yet* proven is that the emission can be made *precise*
   (typed map-values + required-ness, not today's untyped/all-optional output) so the generated
   TS/Zod are precise, not merely faithful. This is a tuning problem (victools config / a richer
   profile), not a re-pick; the one pre-commitment spike worth running is the precise-schema
   round-trip against the live fixtures (§11).
3. **Neutral source vs record-as-IDL — the one genuinely open decision (now the *only* one).** Both
   are proven viable (JSON Schema is faithful, §11). Record-as-IDL is feasible *now* and half-built
   (the a5 direction matured) but privileges Java; schema-as-source is ADR-08's neutral destination
   and the strongest form (the record itself becomes generated → unrepresentable) but a larger lift.
   Weighed in §4a; the user decides pragmatic-now vs neutral-purist.
4. **The collapse is a major rewrite, and partial collapse is *worse* than none.** Per the user's
   framing this tempdoc disregards the cost — but the value is realized only if the collapse is
   carried through. §8 shows the failure mode concretely: adding a generated layer **beside** the
   hand-authored one yields a *third* representation that also drifts. Collapse must retire the
   copies it replaces.
5. **Gate-subsumption is the discipline, not an optimization.** §4c pays off only if generation
   *replaces* the per-leg gates. The §8 anti-instance (a gate added, the leg still hand-authored)
   is the precise trap; the correct design treats "added a gate" as a signal that the concern was
   *not* pushed up the ladder.

---

## 8. Implementation post-mortem (2026-05-31) — why this rewrite exists

An implementation pass executed the first draft. It is the strongest evidence for the rewrite.

**What it achieved (genuine Generate-tier wins):** the agent-event surface became a true
producer→FE generated projection (typed payloads, codegen, drift killed at source); and
`wire-types.ts` was restored as a **live record→TS projection** (`WireTypesTsGenerationTest`,
the `lifecycleState.ts` pattern universalized for the barrel wire types) under the user-ratified
**a5** decision. For the surfaces that consume the barrel (status, health), the FE types are now
projections. These stand.

**Where it fell short of the principle (the facet verdict):**

- **4a — not achieved.** The two authorities were *not* collapsed. a5 made the **record** the FE
  authority and kept the **proto** as a parallel mirror, reconciled by **record↔proto conformance
  gates** — two authorities, gated, at the *bottom* of the ladder. The draft's preferred
  mechanism (records from the proto) was proven *infeasible* (§9b), but the response was to keep
  both sources rather than fix the source *format*. The load-bearing collapse did not happen.
- **4b — partial, and on the wrong layer.** The *generated* pair (`wire-types.ts` + `*_pb`) was
  collapsed to one; the **primary hand-authored authority** (`api/domains/*` + the 384-usage
  `.loose()` Zod) — the layer 563 §1 names as carrying the user-facing data — was **untouched**.
  Validation never became a projection. Four sources became ~three.
- **4c — anti-achieved.** Facing the per-op i18n leg, the pass **added a coexisting gate**
  (`I18nKeyConventionValidator`) instead of generating the slot and deleting the validator —
  exactly the §7.5 "tax doubles" trap. (The count-derivation was a small genuine subsumption.)
- **4d — narrow.** Only the *polymorphic* (`@JsonTypeInfo`) axis was made build-refused; a plain
  hand-authored wire record or hand-mirrored enum remained representable. The broad mandate was
  left as a named decision.
- **4e — not achieved.** Standalone tests were built, not kernel-registered instances — the
  "bespoke solutions" §6 forbids; 481's unification was not advanced beyond confirming a
  pre-existing step.

**The meta-finding, and the reason for the rewrite:** the pass's hard-part work was almost
entirely **Gate-tier** (conformance tests, capture-or-verify, mandate lint). It built a wall to
*catch* drift where the principle demands structure that makes drift *impossible*. It patrolled.
The first draft did not forbid this strongly enough, and it pointed the load-bearing collapse at
a source format that cannot host the data. This rewrite fixes both: **the format is the defect to
correct; the gate is the floor, not the mechanism.**

---

## 9. Evidence ledger

**§9a — de-risk pass (Track A, 2026-05-31), carried forward.** Read-only investigations into the
four implementation uncertainties:

- **V1 — record↔proto (4a).** The "~86 sites" framing overstated the call-site count (~22 across
  6 files), but understated the real blocker, which V1 located correctly: **proto3 map-value
  wrapper types + `null`→`hasX()`**. Confirmed decisive by §9b.
- **V3 — generative legs (4c).** Substrate operations share one generic route (no per-op route
  hand-edit); the count-test and i18n-requirement are subsumable. The genuine remaining work is
  the 481 `consumers: NonEmpty` enforcement (a Pass-3 design slice).
- **V4 — mandate/481 (4d/4e).** Enforcement is ~70% scaffolded; ADR-09 provisions per-axis
  capability→mandate graduation, so the mandate is that graduation, not a contradiction.
- **V2 — generate FE types (4b).** The spike-worthy unknown; confirmed by §8 to be the primary,
  untouched layer (`api/domains` + Zod).

**§9b — the decisive proof (the rewrite's keystone).** proto3 **structurally cannot** faithfully
type the surfaces with map-of-map or arbitrary-JSON shapes. `knowledge.proto` encodes
`facets` (`Map<String,Map<String,Long>>`) as `map<string, FacetCounts>` where
`FacetCounts { map<string,int64> values }` — its proto3-JSON gains a `values` nesting level the
record's bare nested map does not have. `health.proto`'s `HealthEventBody.attributes`
(`Map<String,Object>`) becomes `map<string, google.protobuf.Value>` — a tagged-union wrapper, not
the plain JSON value the FE reads. A generated record (or FE type) would therefore **change the
wire**; a facade would expose the wrappers. This is exactly the bar ADR-08 set ("KnowledgeSearch
Response deep nesting," "HealthEvent discriminated unions") and the proto format **fails it**.
*(Corollary: a health.proto↔producer field-name drift — `condition_status`/`threshold_phase` vs
the wire's `status`/`phase` — was found and logged; it is a symptom of the same un-generated,
hand-mirrored proto.)*

**§9c — implementation outcomes (§8 summarized as evidence).** agent-events + barrel `wire-types`
reached Generate; 4a/4b/4c/4d/4e fell to Gate-tier or partial; the primary `api/domains`+Zod
authority and the source *format* were never addressed. These outcomes are the empirical basis
for §1's re-diagnosis and §3's "gate is the floor" sub-principle.

---

## 10. Relationship to tempdoc 563

563 measured the friction (§9) and ranked remediations by leverage. This tempdoc supplies the
*correct structure* those remediations are local views of — and, after the implementation pass,
corrects the structure's two errors: the **source format** (§1, §4a) and the **mechanism
discipline** (§3, §4c–d). 563 says *what hurts and what to do next*; 564 says *what the end
structure is, why the first attempt fell short, and why the corrected structure cannot recur*.

---

## 11. Implementation-confidence / de-risk report (2026-05-31)

Read-only + cheap-signal de-risk against the §7/§9 uncertainties, ahead of any implementation.
**Net: confidence LOW (~30%) → MEDIUM (~55–60%)**; the dominant surprise is substantially retired.

**Keystone (4a — is there a faithful format?) — was LOW, now MEDIUM-HIGH.** The cases proto3
*fails* (§9b) are **faithfully representable in JSON Schema, which victools already generates from
the records today**: `Map<String,Long>` → bare `{"type":"object"}` (no `FacetCounts` wrapper);
arbitrary-JSON `attributes` → bare `{"type":"object"}` (no `google.protobuf.Value`); the
`HealthEvent` body → `anyOf` (a faithful discriminated union, not proto3's flattened message). So
**JSON Schema does not inherit proto3's expressiveness ceiling** — the design's load-bearing "one
faithful source → all targets generated" is *feasible* via record→schema→{TS, Zod, Java(=the
record), OpenAPI}. This reduces the biggest surprise risk ("no format clears the bar") to near-zero.

- **Design implication (refines §4a/§7.3):** the proven-faithful direction is **record-as-IDL**
  (the record authored; a faithful JSON Schema emitted by victools; TS/Zod generated from it) —
  not the original draft's "contract(proto)→records" (which §9b proved impossible). §7.3's
  record-as-IDL is therefore the *feasible* direction, not merely the fallback; a neutral
  expressive IDL (TypeSpec/Smithy) remains the purist alternative but is unnecessary for faithfulness.
- **Remaining sub-risk (tractable, not a ceiling):** victools' *current* emission is **lossy on
  value types + required-ness** (`Map<String,Long>` → untyped `object`), so naïvely-generated
  TS/Zod would be imprecise (`Record<string, unknown>`, all-optional — the same all-optional
  symptom ADR-08 flagged). This is a precision-tuning problem (victools config / a richer emission
  profile + the schema→{TS,Zod} generators, which exist as npm tooling), **not** proto3's
  structural impossibility. The one spike still worth running before commitment: tune victools (or
  a profile) to emit *precise* schemas for the 3 hard types and round-trip-verify the generated
  Java-serialization / TS / Zod against the live fixtures (`__fixtures__/{search,status,settings}
  -response-live.json`). Expected to pass; if it does not, the gap is emission precision, addressable.

**4b (generate `api/domains` + Zod) — was LOW/MEDIUM, now MEDIUM.** The `api/domains` layer is
**mostly raw wire types** (generatable); only `search.ts` and `inference.ts` carry an FE-ergonomic
**mapper** (`mapKnowledgeSearchResponse(raw): SearchResponse`) — a renaming/deriving view
transform. So 4b = *generate the raw wire types across the 13 modules + the 384-usage Zod; keep
the ~2 mappers as the irreducible FE "body"* (analogous to a handler/view body — a legitimate
projection, not a hand-mirror). This bounds 4b and corrects a draft nuance (the FE-ergonomic
transform is not a duplicate to eliminate). Remaining: the **Zod-tightening risk** (tightening
fail-open `.loose()` may surface latent wire mismatches — not yet measured; D3), and the precision
dependency on the keystone spike.

**4c / 4d / 4e — unchanged (MEDIUM / MEDIUM-LOW / LOW-MEDIUM), pending deeper reads.** 4c's i18n-slot
generation + gate-deletion is bounded; 4d's "wire-shaped type" definition becomes mechanical *once
4a lands* ("anything outside the generated-output set"); 4e depends on the 481 Pass-3 design slice
(the four §E.2.1 decisions) + the 530 kernel registration. None is a *surprise* risk; each is
known scope or a named design blocker.

**Bottom line:** the corrected design's load-bearing move is **feasible** — a faithful single
source exists (record→JSON-Schema, already half-built), so the "format" question that this rewrite
identified as the root error has an answer that does not repeat proto3's failure. The residual work
is precision-tuning + bounded generation + the named Pass-3 design slice — execution risk, not
existential risk. The remaining pre-commitment spike (precise-schema round-trip vs the live
fixtures) is small and high-signal.

---

# Consolidated satellites (folded 2026-06-09, post-400 hygiene pass)

> The implementation-progress checkpoint folded in.

## Implementation progress checkpoint (was 564-impl-progress)

*(folded from `564-impl-progress.md`)*

### 564 implementation progress (worktree `worktree-564-contract-projection`)

## Alternative-path exploration for the 4a / health-body residue (the "work around it" requirement)

The residue (KnowledgeSearchResponse on the Java side; the health body family + facets on the FE side)
cannot move to `*_pb` because proto3's representation diverges from the raw wire JSON (map-of-map →
`FacetCounts`/`EntityVariantList` wrappers; `Map<String,Object>` → `google.protobuf.Value`). Before
calling this a user-decision blocker, every alternative implementation path was examined:

| Path | Feasible? | Why it still needs a decision |
|---|---|---|
| **(b) proto→`*_pb`** (the planned direction) | **No — impassable** | proto-es `Value`/wrapper types ≠ raw JSON; *proven* with two concrete instances (facets, attributes). |
| **(a1) record→victools-schema→TS** | **Yes, technically** — `SSOT/schemas/health-event.v1.json` already exists and faithfully captures `status`/`phase`/plain-`attributes`; only an npm `json-schema-to-typescript` dep + a codegen step are missing | Re-introduces a **record→TS** generator that ADR-09a **deliberately retired** (2026-05-06) in favor of one direction (proto→`*_pb`). Running both **fragments** the generation architecture — arguably *anti*-564 (re-creates the multi-source problem). Adopting it is an ADR-09a amendment = an architecture decision. |
| **(a2) FE↔record conformance gate** | Yes | Gate-tier floor only — makes the frozen residue drift-*safe*, but does **not** make it a live projection (doesn't "implement the facet"). |
| **(a3) fix the proto to be faithful** | No | proto3 *cannot* express map-of-map / arbitrary-JSON without the wrappers — the limitation is the blocker. |
| **(a4) change the wire JSON** to the proto's wrapper shape | Yes | Breaking FE migration; lets the tool dictate an uglier contract. Rejected on contract-quality grounds; a user decision. |
| **(a5) consolidate the *whole* FE on record→schema→TS** (retire `*_pb` FE generation) | Yes | The cleanest single-direction story (records are the runtime authority, faithful for all surfaces), but a **wholesale ADR-09a reversal** + retires the shipped `*_pb` FE work — a major architecture decision. |

## a5 IMPLEMENTED (user-ratified 2026-05-31) — the residue is resolved

The user ratified **a5: consolidate the FE wire layer on record→schema→TS**. Implemented:

- **Restored `WireTypesTsGenerationTest`** (typescript-generator 4.0.0, capture-or-verify) + re-added the
  retired dep (+ lockfile). `wire-types.ts` is once again a **LIVE record→TS projection** of the Java
  records — the single FE wire-type authority. Fixed two drift issues the restoration surfaced:
  `OperationId`→`OperationRef` rename, and `MetricRef.resourceId`'s `ResourceRef` (`@JsonValue` bare
  string) needed mapping to `string` to avoid dragging the registry graph (`registry.Severity/RenderHint`)
  into the closure → name collision. Regeneration brought the records' current shape back (incl.
  `SearchTrace`, which the frozen snapshot had dropped) — proof the snapshot HAD drifted.
- **Consolidated the barrel** onto the live `wire-types.ts`: the `*_pb`-sourced FeWire/StatusFeWire
  migrations (status/timeseries/severity) are superseded; all FE wire types now derive from the records
  (one direction; faithful for the facets / `google.protobuf.Value` surfaces proto3 can't type). Only
  `SearchTrace` stays on `knowledge_pb` (553's separate fromJson migration). Removed the unused FeWire
  infra; **retired the FE snapshot-ratchet** (it inverted under a5 — `WireTypesTsGenerationTest` is the
  FE-side gate now).
- **Result:** the 4a/health-body **residue is RESOLVED** — `KnowledgeSearchResponse` + the health body
  family are live record→TS projections (the records are the single authority; the `*_pb` protos remain
  the gated buf contract + runtime validators, conformance-checked against the records by the X-cut gates).
- **Verified:** backend `build -x test` ✅, `WireTypesTsGenerationTest` verify-mode ✅, FE typecheck ✅,
  2340 FE unit tests ✅.
- **FINAL live verification on the a5 FE (PASSED, post-implementation):** fresh stack (apiPort 49505,
  model active) against the worktree FE carrying every a5 change. Status bar (a5 `StatusResponse` from
  the live wire-types) renders `CONN`+584 docs+`Online`; search `"overlay"` → 45 results; one-window
  agent ran full propose→approve→execute (`/api/chat/agent/*` all 200) with the `LOW · COMPLETED` tool
  card + trace telemetry + correct synthesized answer; **console zero errors/exceptions**; evidence captured.

## (Historical) Conclusion before ratification

**Conclusion:** the residue sits at a genuine FE-wire-type-generation **architecture fork** —
consolidate-on-record (a5) vs consolidate-on-proto-with-wire-changes (a4) vs the fragmented split (a1).
Each is a major decision with real trade-offs; unilaterally picking one (especially the a1/a5 ADR
reversal) is exactly the "multiple valid architectural placements → present options" case. This is the
*single* decision that unblocks 4a's Java collapse **and** the health-body FE residue. It is **named, with
every alternative path examined and its feasibility assessed** — not treated as a silent stopping point.
(The achievable, non-decision work was done: the health.proto↔producer drift is logged; the gate-tier
floor is shipped across knowledge/status/operation_history; the 4d ratchet pins the residue at 6.)

## Facet disposition (current — accurate as of the implementation pass)

| Facet | Status |
|---|---|
| **Phase 0** (agent-event typed contracts) | **DONE** (machinery + gates green); 6 consumer handlers typed; rest = named follow-up (partial test fixtures) + D3 producer-path |
| **proto-parity gate** | **DONE** — operation_history (`fc1db53af`) + **status (recursive walker, this pass)** + knowledge (pre-existing flat). All three record↔proto field-name gates green |
| **4a** (records as single authority) | **RESOLVED via a5** (user-ratified). proto→record-generation proven structurally infeasible (proto3 map-wrappers; 564 §9b), so a5 inverts: the **record is the single FE-type authority**, `wire-types.ts` is its LIVE record→TS projection, the `*_pb` proto is a gated parallel contract (conformance-checked vs the record by the X-cut gates). `KnowledgeSearchResponse` is now a live record→TS projection |
| **4b** (collapse FE wire-type sources) | **RESOLVED via a5.** Drove the `*_pb` path 58→6 first (agent events, TimeseriesSnapshot, Severity, StatusResponse — verified), proving the final 6 (health body) were proto3-unrepresentable (`google.protobuf.Value`). Then user-ratified **a5**: `wire-types.ts` restored as the LIVE record→TS authority (`WireTypesTsGenerationTest`), barrel consolidated onto it, the `*_pb` FeWire migrations + snapshot-ratchet retired. ALL FE wire types (incl. the health body) are now live record→TS projections. Backend build + typecheck + 2340 unit tests + the gen-gate green |
| **4c** (subsume per-leg gates) | **achievable parts LANDED** (this pass): (1) `I18nKeyConventionValidator` — ERROR gate asserting every Operation's i18n keys follow the id-derived convention `ops.<id-suffix>.{label,description,confirm}`, subsuming the per-op key declaration into a structural rule (negative-control-proven it fires on drift); (2) `CoreOperationCatalogTest` count is now DERIVED from the canonical id-set, not a magic number. Substrate ops already share one route (A2 — no per-op route leg). The **deep enforcement** (`consumers: NonEmpty<ConsumerHook>` + runtime witness) stays the named **481 Pass-3 slice** (the goal's "481 Step 3"), blocked on its four §E.2.1 design decisions |
| **4d** (mandate ratchet) | **DONE both sides.** Java: `WireShapeMandateTest` (app-launcher) lifts the polymorphic-wire rule to whole-codebase scope, 14-pkg permit-list = frozen baseline, negative-control-proven (drop `health..` → 2 `HealthEventBody` violations). FE: `check-wire-shape-purity.mjs` + `wire-snapshot-baseline.v1.json` freeze the 58 barrel→wire-types.ts re-exports, refuse-new + shrink-via-rebalance, both NC paths proven. The broader 'all hand-authored wire records' mandate stays the NAMED user-ratification question (no clean signal until 4a/4b shrink the set) |
| **4e** (481 PrimitiveCatalog Step 1) | **DONE — pre-existing** on main: `PrimitiveCatalog<T extends RegistryEntry, R extends RegistryRef<T>>` (namespace/definitions/findById/idOf/resolve); all 4 catalogs extend it (slice 481 §7 step 1) |

**Named separately-scoped slice (work around, per the goal):** **481 Pass-3** — the
`NonEmpty<ConsumerHook>` enforcement + runtime-witness (the deep half of 4c/4e). Blocked on the four
§E.2.1 design decisions (agent-consumer witness shape, per-actor deadline policy, SliceCatalog
referential-integrity, runtime-traffic-counter). A3 confirmed it is a separate design slice, not in
the mechanical-implementation scope here.

**Named user-decision blocker:** the **mandate ratification** for 4d (graduate the "no hand-authored
wire types" axis to mandate, landed as a ratchet) — ADR-09 provisioned this; awaiting the user's call.

**Genuinely-remaining implementable structural work:** the broader 4b FE collapse (live consumers
off the `wire-types.ts` snapshot onto `*_pb` with `fromJson` boundaries — a large multi-surface
migration the now-shipped 4d ratchet enables incrementally without new drift). 4a's "unrepresentable"
upgrade + 481 Pass-3 + the broader-mandate ratification are NAMED blockers (user-architecture /
design-slice / ratification), not effort-deferred.

## Live-browser verification (FINAL batch — 2026-05-31, PASSED)

Verified the worktree's own FE (Vite on :5174 serving this branch's Phase 0 typed handlers + codegen)
against the live dev-stack backend (apiPort 58549, model `Meta-Llama-3.1-8B-Instruct` activated via
`ai_activate`). Dev stack taken over with user approval (`takeover: warn`); MCP launches from main, so
a second Vite served the worktree FE pointed at the live backend via `?api_port=`.

- **FE loads + connects:** status bar `CONN` + `Online — Meta-Llama-3.1-8B-Instruct`; all `/api/*`
  calls 200/202 (status, settings, inference, registry/surfaces, SSE streams, action-ledger) — zero 4xx/5xx.
- **Search (KnowledgeSearchResponse render):** `"index"` → 552 results · 8ms; result cards, highlighted
  matches, "Why this result?" explainers, export toolbar all render; `/api/knowledge/search` → 200.
- **One-window agent (Phase 0 typed handlers — the user-visible surface this branch changed):** full
  propose→**approve**→execute→synthesize round (`/api/chat/agent/{virtual-operations,tools,approve}` all
  200). Header trace telemetry `Iter 3 · Tools 2 · Tokens 4156 · +316` rendered with real typed values
  (not `undefined`/`NaN`) — direct proof the typed progress/budget/trace payload field-access is correct.
  Agent produced a correct synthesized answer over the search results.
- **Console: zero errors/exceptions** through load → search → navigation → agent run (broad-pattern read
  returned only benign Vite/Lit-dev/i18n-fallback DEBUG/WARN lines; `onlyErrors` read returned none).
- Evidence screenshot captured.

This satisfies the goal's final required condition (live real-browser verification of the user-visible
work) and the `static-green ≠ live-working` rule for the Phase 0 typed-handler surface.

## 4b full FE collapse — progress + remaining-slice plan (2026-05-31)

Snapshot of `wire-types.ts` barrel re-exports collapsed **58 → 7** (commits on the worktree branch),
every step typecheck + 2340-unit-test green, ratchet rebalanced down each time:

- **Infra:** `FeWire<X_pb>` (wireProjection.ts) — the FE-facing plain shape of a generated `*_pb`
  type (`bigint→number`, `$`-brand stripped at type level), paired with the existing `bigintToNumber`
  runtime via `toFeWire()`. The reusable foundation for every surface.
- **Migrated onto `*_pb`:** agent events (Phase 0); `TimeseriesSnapshot` → `metrics_pb` (one fixture
  fix); `Severity` → `health_pb` derived union (`Exclude<keyof typeof Severity,'UNSPECIFIED'>`, the
  `lifecycleState.ts` enum-collapse pattern). `SearchTrace` family was already on `knowledge_pb`.
- **Pruned:** ~50 dead barrel re-exports (the ~40 unconsumed nested `*View`/`*Group` status types —
  the FE reads status via `stores/systemTypes.ts`, not the barrel) + 13 unconsumed FE-name aliases
  (`RuntimeIdentity`/`InferenceRuntime`/…/`HealthEventBodyBase`). All typecheck-guarded (type-only).

**`StatusResponse` — DONE** (the entangled status surface): `StatusFeWire<status_pb.StatusResponse>` —
`FeWire` + the `LifecycleState` enum (`components.*.state`) projected to its wire-string union
(`LifecycleWireName`). The `/api/status` JSON is structurally compatible, so `statusPoll` needs no
fromJson; consumers (StatusDeck/HealthSurface/aiStateStore) unchanged. Typecheck + 2340 unit tests green;
ratchet 7→6.

**Remaining 6 — the health body family** (`HealthEvent`, `AssertedCondition`, `LifecycleEvent`,
`ThresholdState`, `Source`, `MetricRef`) — **BLOCKED by the same 4a-class faithfulness limitation, plus a
health.proto drift bug:**

1. **4a-class blocker (`google.protobuf.Value`).** `health_pb.HealthEventBody.attributes` is
   `map<string, google.protobuf.Value>` → proto-es `{[k]: Value}`, a complex wrapper that does **not**
   match the plain-JSON attribute values the FE reads (`typeof attrs['message'] === 'string'`). This is
   the *identical* representation-divergence as 4a's `FacetCounts`/`EntityVariantList` wrappers (564 §9b):
   the proto-es type can't faithfully type the raw JSON, so the body can't migrate to `health_pb` without
   the same wire-JSON-change-or-authority-inversion user decision that gates 4a.
2. **health.proto↔producer drift (a real bug, logged to observations.md).** `condition_status`/
   `threshold_phase` (no `json_name`) emit `conditionStatus`/`thresholdPhase`, but the Java producer
   (`AssertedCondition.status` / `ThresholdState.phase`) emits `status`/`phase` on the wire — so
   `health.proto` doesn't even describe the actual HealthEvent JSON. A recursive health record↔proto
   conformance gate (extending the X-cut) would catch this; fixing it is a wire-governed contract change.

So the health body joins `KnowledgeSearchResponse` in the **4a-blocked set**: its migration is gated on the
same user-architecture decision (proto-es representation faithfulness for `Value`/wrapper types), not on
effort. The **4d ratchet pins the set at 6** — no NEW snapshot type can appear. `wire-types.ts` retires
fully once the 4a representation question is resolved (the health body + `KnowledgeSearchResponse` share
that blocker).

**Net 4b: 58→6 (90%) migrated + verified; the final 6 are 4a-blocked (now with a second concrete
instance, `google.protobuf.Value`), not effort-deferred.**

## FINAL live-browser verification — re-run AFTER the 4b collapse (2026-05-31, PASSED)

Per the goal's sequencing (live verification is the last step, after all implementation), re-ran the full
batch on a fresh stack (apiPort 52164, `Meta-Llama-3.1-8B-Instruct` activated) against the worktree FE
(Vite :5174) carrying every 4b change:

- **Status bar (`StatusFeWire` migration, the biggest 4b change):** `/api/status` → 200; `CONN` + 584 docs
  + memory + `Online` render correctly — `statusPoll`→`StatusDeck` parses + renders the migrated
  `StatusResponse` (status_pb projection) live.
- **Search:** `"overlay"` → 551 results · 13ms, rendered; `/api/knowledge/search` → 200.
- **One-window agent:** full propose→approve→execute (`/api/chat/agent/{virtual-operations×3,tools,approve}`
  all 200); a **tool card rendered with the `LOW · COMPLETED` risk/gate badge** — directly validating the
  Phase 0 `ProposedCall` typed payload (`risk`/`gateBehavior`); trace telemetry `Iter 3 · Tools 2 · Tokens
  4006`; correct synthesized answer; action-ledger 4→5.
- **Console: zero errors/exceptions** through load → search → agent run. Evidence screenshot captured.

This is the post-implementation live verification the goal's step (3) requires — the affected user-visible
surfaces (one-window agent + search, plus the 4b-migrated status surface) all render correctly with the
4b changes live.


Implementing tempdoc 564 per the approved plan (Phases 0–5 + proto-parity X-cut).
**Never merge to main** (user merges later). Defer ALL live/browser verification to one
final batch (Phase 19). Always take the structural option.

## Done
- Worktree set up at local `main` HEAD (753dc671, includes 561 one-window code).
- **Phase 0 foundation** (`c3d14cbd2`): typed event-schema model —
  `EventFieldType` / `EventField` / `EventDescriptor`.
- **eventSchema migration + agent-run correction** (`c0c4bdd4b`): `ConversationShape.eventSchema`
  → `List<EventDescriptor>` across all 8 shapes + 2 tests; `AgentRunShape` typed descriptors;
  corrected drift (added `tool_batch_proposed`/`tool_call_virtual`, dropped phantom `navigate.url_*`);
  FE fixture + regen + parity tests aligned. Green: compileJava (agent-api/services/ui) + conversation
  tests + regen gate + FE typecheck.
- **Producer-conformance gate** (`c239df17b`): `AgentEventSchemaConformanceTest` binds the declared
  names to `ToolIteratingShapeRunner.eventName` over the sealed `AgentEvent` — drift (D1/D2/D4) now
  fails the build. The structural anti-drift core. Green.
- **Generated shapes fixture** (`fa368c887`): `ConversationShapeFixtureGenTest` capture-or-verifies
  `scripts/codegen/shapes.fixture.json` from the catalog (no Jackson). The fixture is now a
  projection, not hand-maintained.
- **Typed FE codegen** (`91a9f9892`): `gen-shape-handlers.mjs` loads the fixture + emits typed
  payload interfaces per event (+ `shared.ts` leaf types `TracePayload`/`ProposedCall`); deleted the
  fragile-regex parity test (superseded). Green: regen gate + FE typecheck + conversation tests.

**Phase 0 — machinery DONE; consumer migration partially done + a named follow-up.**
Committed typed handlers (`91d95cfb7`+`90d5f9ff7`): `onSessionStarted`, `onChunk`,
`onToolBatchProposed`, `onToolCallApproved`, `onToolExecStarted`, `onToolCallRejected` (the
single-/simple-field events) + the dispatch **boundary-cast** pattern (one `unknown→typed` cast per
event at the deserialization boundary). The typed interfaces + `shared.ts` are generated and
validate.

**Remaining consumer typing = named follow-up (NOT a blind in-context change):**
- *Multi-field handlers* (`onDone`/`onProgress`/`onBudgetUpdate`/`onError`): strict typing is correct
  but the **unit tests pass partial payload literals** (e.g. `onDone({finalResponse,iterationsUsed,
  toolCallsExecuted})` missing `totalTokensUsed`; `onError({})`; `onProgress({message})`) and 2
  synthetic callers pass `onError({error})`. Fully typing them requires completing ~8 test fixtures +
  the 2 synthetic calls with the real wire fields — a careful edit (must not alter test intent), best
  done deliberately. (`onHandoffProposed`/`onHandoffExecuted` are clean and can be re-migrated.)
- *D3-blocked handlers* (`onToolExecCompleted` nested `result`; `handleToolCallEntry`/`onToolCallVirtual`
  read non-field `sessionId`): stay `unknown` pending the producer-path investigation (which emitter
  sends the nested/extra fields) — named, not faked.

This is consumer-side cleanup; the **structural 4b machinery for agent events is complete**. Pivot
to the higher-structural-value remaining work: the proto-parity X-cut, then Phase 1's 4a (load-bearing),
4b/Phase 2, 4c/3, 4d/4, 4e/5 → FINAL live verification.

### D3 — CORRECTED (de-risk hypothesis refuted by a unit test)
The de-risk pass flagged `AgentSessionController.onToolExecCompleted`'s nested `data.result.*`
branch as a dead footgun (only the flat fallback fires for the substrate producer). **A unit test
(`AgentSessionController.test.ts` "… from result wrapper") asserts the nested wrapper**, with a
sibling "falls back to flat fields" test — i.e. the FE intentionally handles BOTH forms, so a
producer path emits the nested `{result}`. Removing the branch broke 4 tests; reverted per
`fix-root-causes-not-symptoms`. **D3 is NOT a quick deletion** — it needs producer-path
investigation (which emitter sends nested vs flat, e.g. the `/api/chat/agent/tool-result` virtual
path) before any unification. Out of Phase 0's quick scope; not a footgun. (D6/D7 field-presence
are handled by the typed descriptors + FE typecheck.)

## Remaining Phase 0 — typed-field FE codegen (precise sub-plan; atomic unit)
The name-level anti-drift gate is committed; this adds typed payloads so the FE stops hand-casting
(facet 4b precision).

> **STRUCTURAL REFINEMENT (decided 2026-05-31).** Do NOT hand-edit `BUNDLED_SHAPES` into nested
> descriptor objects + patch the parity regex — that's the quick/fragile path (field `name:`s
> collide with event `name:`s in the regex; the hand fixture keeps drifting). The structural answer:
> make the fixture **generated**. Add a Java capture-or-verify test (mirror `SubstrateSchemaGenTest`)
> that writes `SSOT/schemas/conversation-shapes.v1.json` (or `scripts/codegen/shapes.fixture.json`)
> from `CoreConversationShapeCatalog.catalog().definitions()` (the full `List<EventDescriptor>` via
> Jackson). `gen-shape-handlers.mjs` static mode reads THAT JSON instead of the hand `BUNDLED_SHAPES`;
> the `--live` path already returns the same shape from `/api/registry/shapes`. Then `BUNDLED_SHAPES`
> + `ConversationShapeFixtureParityTest` are RETIRED (the capture test + regen gate replace them —
> the fixture can no longer drift because it's projected from the catalog). This is the 564 principle
> applied to the fixture itself (single source → generated projection → gate). Then steps 2–4, 6–7
> below stand; step 5 (parity regex) is replaced by the capture test.

**Fixture is DONE** (`fa368c887`): `ConversationShapeFixtureGenTest` captures
`scripts/codegen/shapes.fixture.json` (typed descriptors) from the catalog, capture-or-verify,
no Jackson. Remaining = the **consumption switch** (one green unit):

**A. `gen-shape-handlers.mjs`** — replace the hand `const BUNDLED_SHAPES = [ … ]` (the big array,
~lines 57–125) with `const BUNDLED_SHAPES = JSON.parse(readFileSync(join(REPO_ROOT,'scripts',
'codegen','shapes.fixture.json'),'utf8'));` (keep the name + `export { BUNDLED_SHAPES }`). The
fixture entries are `{id, eventSchema:[{name, fields:[{name,type,optional,enumValues,elementType,
objectType}]}]}`.
**Consumer analysis (verified):** `check-shape-view-coverage.mjs` uses only `s.id` → unaffected;
`check-shape-handler-regen.mjs` line 67 uses `.eventSchema` only on the `--live` path (deferred) →
the static `--check` path is unaffected; `ConversationShapeFixtureParityTest` regex-parses
`BUNDLED_SHAPES` → **DELETE it** (superseded by the GenTest + regen gate; not weakening — replaced
by a stronger gate).
**B. `renderHandlers`** — `shape.eventSchema` is now descriptor objects. For each: if `fields` is
empty → `on<Event>?(payload: unknown): void` (unchanged); else emit
`export interface <Shape><Event>Payload { name<?>: <tsType>; … }` and `on<Event>?(payload:
<…>Payload): void`. `tsTypeForField`: STRING→`string`, NUMBER→`number`, BOOLEAN→`boolean`,
ENUM→`'a'|'b'` (fallback `string` if empty), OBJECT→`objectType`, ARRAY→`<objectType|scalar>[]`.
PascalEvent = `eventNameToHandlerMethod(name).slice(2)`. Emit `import type { TracePayload,
ProposedCall } from './shared.js';` when referenced.
**C.** Write `modules/ui-web/src/api/generated/shape-handlers/shared.ts` (generated header):
`TracePayload { runId?/stepId?/spanId?/parentSpanId?/agentId?/toolCallId?: string; iteration:
number }` and `ProposedCall { callId: string; toolName: string; risk?: string; gateBehavior?:
string }`. (Update `writeIndexFile` to also export from shared if desired.)
**D.** Regenerate (`node gen-shape-handlers.mjs`); verify `check-shape-handler-regen.mjs` (static),
`:app-services:test` (GenTest green, parity deleted), `ui-web npm run typecheck` (AgentSessionController's
`unknown` params still satisfy typed interface via contravariance — no consumer migration needed yet).
Commit.
**E.** (separate increment) migrate `AgentSessionController` hand-casts to the generated typed params.

Original step list (superseded by A–E above):

1. **BUNDLED_SHAPES format** (`gen-shape-handlers.mjs`): change each entry's `eventSchema` from
   `['name', …]` to descriptor objects `[{ name, fields: [{name,type,optional,enumValues?,
   elementType?,objectType?}] }, …]`. core.agent-run carries the AgentRunShape field types
   (copy from `AgentRunShape.EVENT_SCHEMA`); the other 7 shapes use `fields: []`. This now matches
   what `/api/registry/shapes` serializes (Java `List<EventDescriptor>` via Jackson), so `--live`
   and static agree.
2. **`tsTypeForField(field)` helper**: STRING→`string`, NUMBER→`number`, BOOLEAN→`boolean`,
   ENUM→`'v1' | 'v2'`, OBJECT→`field.objectType` (referenced), ARRAY→`<elem-or-objectType>[]`.
3. **renderHandlers**: for each descriptor, emit `export interface <TypeName><PascalEvent>Payload {
   field: type; optional?: type; trace?: TracePayload }` (only if it has fields; else keep
   `payload: unknown`), and the method `on<Event>?(payload: <…>Payload): void`. Append the shared
   `trace?: TracePayload` from the producer's TRACE_FIELD (agent events).
4. **Shared leaf types** — write `modules/ui-web/src/api/generated/shape-handlers/shared.ts`
   (generated header) with `TracePayload` (`runId?/stepId?/spanId?/parentSpanId?/agentId?/
   toolCallId?: string; iteration: number` — from `TraceContext`/`toTraceMap`) and `ProposedCall`
   (read `ProposedBatchProjection.project` for the exact fields: ~`callId,toolName,arguments,risk,
   gateBehavior?`). Reference them from generated files. These leaf nested shapes are the one
   hand-written piece (the descriptor model is non-recursive); acceptable per the projection-tier
   ladder (still single-source for the scalar/enum/array fields).
5. **Parity test** (`ConversationShapeFixtureParityTest.parseBundledShapes`): update the regex to
   parse the new object format and extract `name`s (still compares the name lists). Or compare full
   descriptors if cheap.
6. **Migrate `AgentSessionController`**: replace the ~60 `payload as …` hand-casts with the typed
   generated payload param (`onToolCallPending(p: …ToolCallPendingPayload)` etc.). Keep the D3
   nested-`result` dual handling (it is contract-relevant — see above).
7. **Verify green**: `node gen-shape-handlers.mjs` → `check-shape-handler-regen.mjs` →
   `:app-services:test --tests *ConversationShapeFixtureParityTest*` → `ui-web npm run typecheck`
   + `npx vitest run AgentSessionController`. Commit.

Then: Phase X-cut, Phases 1–5, FINAL live browser verification batch (per plan + tasks).

## Next: finish Phase 0 — migrate `ConversationShape.eventSchema` `List<String>` → `List<EventDescriptor>`

Compiling unit (do all together, then `:app-services:compileJava` + `:app-agent-api:test`):

1. `EventDescriptor.java`: add `static List<EventDescriptor> namesOnly(List<String> names)` →
   maps each name to `nameOnly(name)`.
2. `ConversationShape.java`: field `List<String> eventSchema` → `List<EventDescriptor>`; update
   compact-ctor copy; add import.
3. `AgentRunShape.java`: replace the `List<String> EVENT_SCHEMA` with a typed
   `List<EventDescriptor>` carrying the **corrected** set (the wire truth from
   `ToolIteratingShapeRunner.buildPayload`/`eventName`): drop the phantom
   `navigate.url_extracted/_dispatched/_rejected` (D4); add `tool_call_virtual` (D1) +
   `tool_batch_proposed` (D2); keep `intent.resolution`. Typed fields:
   - session_started{sessionId:S}; chunk{text:S}; reasoning_chunk{text:S}
   - tool_call_proposed{callId:S,toolName:S,arguments:S,risk:ENUM[low,medium,high]}
   - tool_batch_proposed{calls:ARRAY<OBJECT ProposedCall>}
   - tool_call_pending{callId:S,toolName:S,arguments:S,risk:ENUM,gateBehavior?:ENUM[auto,inline_confirm,typed_confirm,deny]}
   - tool_call_approved{callId:S}; tool_exec_started{callId:S,toolName:S}
   - tool_exec_completed{callId:S,success:BOOL,output:S,executionId:S}
   - tool_call_rejected{callId:S,reason:S}; tool_call_virtual{callId:S,wireName:S,arguments:S}
   - done{finalResponse:S,iterationsUsed:N,toolCallsExecuted:N,totalTokensUsed:N}
   - error{error:S,errorCode:S,errorClass?:S,retryAction?:S,retryAttempt?:N,i18nKey?:S}
   - progress{phase:S,message:S,iteration:N,maxIterations:N}
   - budget_update{phase:S,tokensConsumed:N,tokensRemaining:N}
   - handoff_proposed{fromAgentId:S,toAgentId:S,reason:S}; handoff_executed{fromAgentId:S,toAgentId:S}
   - intent.resolution{target:S, ...} (from URLExtractor — confirm fields against URLExtractor.java)
   - (every event also carries the optional shared `EventDescriptor.TRACE_FIELD`)
4. The 7 other shapes — wrap last ctor arg `EVENT_SCHEMA)` → `EventDescriptor.namesOnly(EVENT_SCHEMA))`
   + add import. Sites: SummarizeShape:66, RAGAskShape:62, NavigateChatShape:87,
   HierarchicalSummarizeShape:56, FreeChatShape:63, ExtractShape:60, BatchSummarizeShape:60.
   (Their typed fields are filled in Phase 2; nameOnly is the typed-model placeholder.)
5. Tests: `ConversationShapeFixtureParityTest`, `CoreConversationShapeCatalogCompletenessTest` —
   adapt `eventSchema()` reads to `.stream().map(EventDescriptor::name)`.
6. `RegistryController.handleShapes` — serialize each descriptor as `{name, fields:[{name,type,
   optional,enumValues?,elementType?,objectType?}]}` (currently emits the bare name list).

## Then (rest of Phase 0)
7. **Conformance test** (app-services): reflect `AgentEvent.getPermittedSubclasses()`; for each,
   build a representative instance, run real `ToolIteratingShapeRunner.translate(...)`, assert the
   payload keys+value-types match the agent-run descriptor for that `eventName` (+ exhaustiveness
   both ways). This is the anti-drift gate (kills D1–D4) — the structural core.
8. **D3 fix**: `AgentSessionController.onToolExecCompleted` reads a nested `data.result.*` primary
   branch that the producer never emits (flat success/output/executionId). Remove the dead branch.
9. **FE codegen**: `gen-shape-handlers.mjs` emit typed `<Shape><Event>Payload` interfaces from the
   descriptors (map STRING→string, NUMBER→number, BOOLEAN→boolean, ENUM→union, OBJECT→named iface,
   ARRAY→T[]); add shared `TracePayload`. Update `BUNDLED_SHAPES` fixture to carry descriptors;
   update `/api/registry/shapes` live path. Regenerate `generated/shape-handlers/*`.
10. **AgentSessionController**: replace the ~60 `payload as ...` hand-casts with typed field access
    off the generated payload interfaces.
11. Verify: `:app-services:test` (+ conformance), `cd modules/ui-web && npm run typecheck`,
    `node scripts/ci/check-shape-handler-regen.mjs`. Commit Phase 0.

## Remaining phases: see plan `~/.claude/plans/whimsical-soaring-rain.md` + tempdoc 564 §4 + tasks.
Live browser verification (one-window agent + search) is the FINAL batch, after all phases.

---

# De-risked plan execution (plan `~/.claude/plans/whimsical-soaring-rain.md`) — phased tracker

Authoritative from 2026-05-31: record-as-IDL, record → JSON-Schema (victools) → {TS, Zod} → FE.
Each user-visible phase (1, 2) closes only on a real-browser gate.

## Phase 0 — Precise-schema foundation — ✅ DONE (2026-05-31)

The record→JSON-Schema output is now **faithful + precise**, the keystone made real.

- **`WireSchemaConfig`** (`app-api` test scope) is the single shared victools config. Three precision
  layers beyond `RESPECT_JSONPROPERTY_ORDER`/`REQUIRED`:
  1. **value-class string overrides** — `NamespacedId`/`I18nKey` (`@JsonValue` → bare string) emit a
     `string` schema, not victools' default empty object.
  2. **typed map-values** (`typedMapDefinition`) — `Map<String,V>` →
     `{type:object, additionalProperties:<schema of V>}` recursively, so
     `Map<String,Map<String,Long>>` (facets) → `object → object → integer` — **the exact nested-map
     shape proto3 cannot model without a wrapper.** This is the §9b structural proof, now generated.
  3. **`FLATTENED_ENUMS_FROM_JSONVALUE`** — enums serialize via their `@JsonValue` (StageId →
     `query-understanding`, StageStatus → `executed`); the schema's enum list now matches the wire.
  4. **`withNullableCheck` (`isNullableOnWire`)** — a reference field is `["T","null"]` iff its
     declaring record does not carry `@JsonInclude(NON_NULL)` (Jackson emits explicit `null` there;
     a `NON_NULL` record omits the field instead). Faithful to `nextCursor`/`filterNormalization`.
- **`KnowledgeSearchResponseSchemaTest`** (new) — generates `SSOT/schemas/knowledge-search-response.v1.json`
  (capture-or-verify + `-PupdateSchemas`), **plus the record↔schema↔wire faithfulness check**: the real
  captured fixture (`api/__fixtures__/search-response-live.json`) validates against the generated schema
  via networknt. This is the conformance assertion the plan's Phase 0 mandates — and it caught three
  real faithfulness gaps (kebab enums, lowercase status, explicit-null fields) before they shipped.
- **Regenerated baselines** with the precise config: `operation/resource/prompt.v1.json` (substrate),
  `status-response/knowledge-status/debug-state.schema.json` (status). Dual-copy `modules/ui/.../SSOT/schemas`
  auto-mirrors at build.
- **Verified**: `:modules:app-api:test` green; **full `./gradlew.bat test` green** (192 tasks, no
  downstream drift). Line-ending churn (lockfiles/synonyms/fixtures) restored — diff is scoped.

## Phase 1 — SEARCH pilot (json-schema → {TS,Zod} codegen) — ✅ STATIC DONE (browser gate → final batch)

The keystone: the FE wire layer for search is now a single generated projection of the Java
record, with the runtime tier (Zod) the typescript-generator path can't produce.

- **`scripts/codegen/gen-wire-schema-types.mjs`** — general JSON-Schema → {TS type, Zod schema}
  emitter (`$ref`/`$defs`, nullable type-arrays, `additionalProperties` maps, `enum`, arrays).
  Driven by a `TARGETS` registry (Phase 1: the one search schema; Phase 2 adds the rest). Emits
  `generated/schema-types/knowledge-search-response.ts` — the TS interface AND
  `knowledgeSearchResponseSchema` Zod, both from `knowledge-search-response.v1.json`. Facets typed
  `Record<string, Record<string, number>>` (the proto3-impossible shape); enums the kebab/lowercase
  wire unions; nullables `| null`.
- **`search.ts` re-pointed**: the hand `KnowledgeSearchResponse` is **deleted** (re-exported from the
  generated module for callers); `request<unknown>` → `parseWireContract(knowledgeSearchResponseSchema,
  raw, …)` validates the RAW wire at the parse boundary; the fail-open `.loose()` post-map check is
  retired. `mapKnowledgeSearchResponse` (the FE body) kept; one `normalizeEntityFacetVariants` helper
  bridges generated-optional → ergonomic-required.
- **`parseWireContract`** (schemas.ts) — the faithful, **non-fail-open** boundary: a mismatch logs
  `[WireContract] … contract drift` loudly (the browser gate asserts its absence live).
- **FE faithfulness gate**: `knowledge-search-response.test.ts` — the generated Zod validates the real
  captured `search-response-live.json` (the FE mirror of the Java `liveFixtureConforms`).
- **`scripts/ci/check-wire-schema-types-regen.mjs`** + `npm run check:wire-schema-types-regen` /
  `gen:wire-schema-types` — drift gate.
- **Verified (static)**: `npm run typecheck` clean; full FE unit suite green (234 files / 2342 tests).
- **Browser gate (deferred to the ONE final live batch)**: dev stack + model, real faceted search,
  results + facets + SearchTrace render, generated Zod validates the live response (no `[WireContract]`
  console error), console clean.

## Phase 2 — expand 4b across surfaces — ◑ IN PROGRESS (emitter generalized; STATUS migrated)

- **Emitter generalized** for every wire schema, not just search: `anyOf` (nullable enums + unions),
  `const` (discriminated-union literals), `$defs` name **sanitization** (`Component-nullable` →
  `ComponentNullable`), and **topological `$defs` ordering** (Zod consts reference each other → must
  precede). Search output is byte-identical (proven by the regen gate) — pure additive generality.
- **STATUS migrated** (the second hard case — `@JsonUnwrapped` flattening, nullable-ref defs, the
  `LifecycleState` anyOf nullable enum): generated `status-response.ts` (type + `statusResponseSchema`
  Zod); FE faithfulness test validates the real `status-response-live.json`; `statusPoll.ts` now
  validates the raw `/api/status` at the parse boundary via `parseWireContract` (was a raw cast with
  **no** validation). Type-authority convergence (demoting the parallel `wire-types.ts` `StatusResponse`)
  is the incremental cross-consumer step the plan defers ("demote wire-types.ts as consumers leave them").
- **Verified (static)**: regen gate green; `npm run typecheck` clean; full FE suite green (235 / 2343).
- **Remaining surfaces** (health, inference, packs, browse, indexing, …): same pattern — add a `TARGETS`
  entry + parse-boundary `parseWireContract`; faithfulness gate where a captured fixture exists, else via
  the final live batch. Browser gate per surface → the ONE final live batch.

## Phase 3 — generate the i18n leg + DELETE the convention gate — ✅ DONE (2026-05-31)

The §7.5 anti-pattern fix made real: the duplication is removed structurally, not patrolled.

- **`NamespacedId.suffix()`** — the id-part after the namespace (`core.restart-worker` →
  `restart-worker`; per the namespace pattern the id-part has no dot, so substring-after-last-dot).
- **`Presentation.forId(id[, icon, category])`** + **`ConfirmStrategy.typedForId(id)`** — derive the
  i18n keys from the id by the `ops.<id-suffix>.{label,description,confirm}` convention. The keys are
  **generated by construction** — the declaration fans out to the i18n leg; nothing to drift from the id.
- **All 34 operation construction sites** (29 Core + 5 AgentTools presentations, 2 Typed confirms)
  converted from hand-typed `new I18nKey("ops.…")` to the factories. Byte-identical keys (the convention
  validator that previously gated this proves the suffix-match for every op), so zero behavior change —
  proven by the unchanged `CoreOperationCatalogTest` / emitter tests.
- **`I18nKeyConventionValidator` DELETED** (the gate I had added this session — the coexisting-gate
  anti-pattern) and removed from `ValidatorRunnerTest`. **`I18nKeyValidator` folded** to its remaining
  role: the *coverage* gate (the id-derived keys must have authored text in the catalog) — doc updated.
- **Count literals**: `CoreOperationCatalogTest` already asserts `expectedIds.size()` (registry-derived,
  not a magic number) — the item was already satisfied.
- **Verified**: `:app-agent-api:test` + `:app-services:test` + `:app-agent:test` + `:app-api:test` green;
  unused `I18nKey` imports removed; spotless + compile clean.

## Phase 3 (old placeholder) — superseded by the section above
## Phase 4 — mandate = unrepresentability — ✅ DONE (2026-05-31)

The 564 thesis made enforceable: a migrated wire type's second copy is build-refused, not patrolled.

- **`scripts/ci/check-wire-type-single-authority.mjs`** + `npm run check:wire-type-single-authority` —
  the mandate gate. For each generated root type (the codegen `TARGETS` registry, so it **auto-extends**
  as surfaces migrate — the "transition ratchet" is simply "not yet in TARGETS"), it scans the FE source
  *outside* `api/generated/**` for a hand `interface <Name>` / `type <Name> =` **declaration** and fails
  the build on any. Re-exports (`export type { Name } from '…/generated/…'`) and inline type-imports are
  allowed — they point at the single authority.
- **Proven both ways** (audit-driven): passes for the current tree (`KnowledgeSearchResponse`,
  `StatusResponse` each have exactly one generated authority); a planted `interface StatusResponse {…}`
  in a non-generated file fails the build with exit 1.
- **Read-only generated artifacts**: each generated file carries the `Do NOT edit by hand` header, and
  `check-wire-schema-types-regen.mjs` is the enforcement — a hand-edit drifts from codegen output and
  fails the gate (the regen gate *is* the read-only mandate).
- `TARGETS` is now exported from the codegen so the gate and the generator share one registry.

## Phase 5 — register the `contract-projection` gate on the 530 kernel — ✅ DONE (2026-05-31)

The 564 invariants are now a unified discipline gate, not a scatter of bespoke scripts.

- **`scripts/governance/gates/contract-projection/`** — a kernel gate (enforcer + truth-table +
  rule-descriptions) registered in `governance/registry.v1.json`. Like execution-surface it is a
  **meta-coordinator**: it delegates to the two proven checks (regen drift + single-authority mandate)
  and adds a generated-coverage check, projecting their results into the kernel's verdict/finding shape.
  Three rules: `schema-types-drift`, `duplicate-wire-type`, `coverage-gap`.
- **Verified end-to-end**: `node scripts/governance/run.mjs --gate contract-projection` passes clean;
  a planted hand `interface KnowledgeSearchResponse` makes the kernel gate fail (exit 1);
  `--explain contract-projection/duplicate-wire-type` resolves; the full 21-gate suite still loads
  (`prose-tier-register` — which cross-validates the registry — passes; the 4 unrelated failures
  ui-bundle/clone/stage-completeness/independent-review are pre-existing ratchet debt, no finding
  references a 564 file).
- **On "replacing the bespoke standalone tests"**: `WireTypesTsGenerationTest` (the a5 record→TS
  projection) and the proto X-cut conformance tests enforce the *parallel* wire-types.ts / `*_pb`
  paths, demoted **incrementally** (P2 in progress) — NOT redundant with this gate yet, so retiring
  them now would drop real coverage. They coexist until P2 convergence empties the parallel paths;
  this gate is the kernel home for the JSON-Schema pipeline.
- **481 Pass-3** (`NonEmpty<ConsumerHook>`, runtime-witness) stays the explicitly-named deferred slice.
- **Closure note**: this is substrate-shipping work; per `independent-reviewer-required` it needs an
  independent (second-agent) review + a `gates/independent-review/slices.json` record for formal
  closure — outside the single-agent autonomous mandate. The one final live browser batch (search +
  status surfaces) is the remaining user-visible verification.

---

# Final live verification batch (2026-05-31) — ✅ LIVE FAITHFULNESS PROVEN

The one deferred live batch. Result: the generated Zod schemas faithfully validate **real live
backend responses**, including the two hardest cases.

- **Live `/api/status`** → validates against the generated `statusResponseSchema`. ✓
- **Live `/api/knowledge/search`** (faceted) → validates against the generated
  `knowledgeSearchResponseSchema`: `totalHits=61`, `facets={file_kind, language}` — the
  **map-of-map** shape proto3 cannot model, every count typed as `number` — plus a **12-stage
  SearchTrace** and 20 results. ✓
- This is the record↔schema↔**WIRE** proof against a running stack (not a captured fixture): the
  generated Zod validates the actual live JSON, so `parseWireContract` succeeds with **no fail-open /
  no `[WireContract]` drift**. The captured-fixture conformance tests (Java `liveFixtureConforms` + the
  FE `knowledge-search-response.test.ts` / `status-response.test.ts`) corroborate against committed real
  captures.
- **Method note / honest limit**: the live responses were fetched read-only (host-side, raw bytes) and
  validated against the generated Zod via a throwaway probe (since removed). The shared dev stack flapped
  ports and was **taken over by another agent's worktree** mid-batch, so the *in-browser FE render* of
  search (results/facets/trace painting in the UI shell) could not be driven against this worktree's own
  build without dev-stack coordination (which the autonomous mandate excludes). That render is unchanged
  behaviour — my work re-points the raw type source + adds the parse-boundary validation; the mapper and
  rendering are identical and covered by the green FE unit suite. The substantive claim the browser gate
  would check (generated Zod validates the live response → no fail-open) is proven directly above.

## Verification tiers achieved
1. **Static** — compile + spotless + PMD; full Java unit suite (192 tasks); full FE unit suite
   (235 files / 2343 tests) + typecheck; governance 21-gate suite loads, `contract-projection` passes,
   the mandate gate proven to fail on a planted hand copy.
2. **Captured-fixture faithfulness** — generated schema/Zod validate the committed real wire fixtures.
3. **Live-wire faithfulness** — generated Zod validates the live running backend's `/api/status` +
   faceted `/api/knowledge/search` (facets map-of-map + 12-stage SearchTrace).

---

# Corrective work (post-critical-analysis, 2026-05-31) — closing the load-bearing gaps

The critical-analysis pass found the load-bearing facets incomplete or at the wrong ladder tier.
Corrective phases (plan `~/.claude/plans/whimsical-soaring-rain.md`), all committed + statically green:

- **P-A (status third-representation collapsed)** — `StatusResponse` now has ONE authority (generated
  schema-types); the barrel re-exports it; `statusPoll` drops the cross-projection cast; the hand
  `SystemStatusSchema` + ~18 sub-schemas + dead `InferenceStatusSchema` (364 lines of fail-open Zod)
  deleted. Fixes the §7.4 "partial collapse is worse than none" trap.
- **P-B (4b breadth)** — the real drift surface migrated for **inference / packs / browse**:
  `WireRecordSchemaGenTest` emits SSOT schemas for the records; the FE re-points to generated
  `parseWireContract` (non-fail-open) and deletes the hand `.loose()` Zod; 7 wire types now under
  single authority. BrainSurface's second hand `EffectivePolicy` removed.
- **P-D (4d → lint tier)** — the mandate moved from CI-only to **author-time**: an ESLint
  `no-restricted-syntax` rule (driven by the codegen TARGETS, auto-extending) forbids hand-declaring a
  migrated wire type outside `api/generated/**`. The sharpest critical-analysis gap (4d was Gate-tier).
- **P-E (4e → registered instance)** — `governance/contract-surfaces.v1.json` declares each record +
  schema + generated + consumers; the kernel gate enforces register↔TARGETS coherence + consumer
  integrity + undeclared-consumer detection (mirrors execution-surfaces). The FE-contract is now a
  declared 530 surface, not an ad-hoc gate.
- **P-C (proto-free FE, partial)** — `lifecycleState.ts` demoted off the proto `LifecycleState` enum
  to the generated `StatusResponse` enum (via a `satisfies` check).

## Deliberately deferred (named, with reasons — not silently skipped)
- **indexing** + **GpuCapabilities** (4b): indexing has a genuine `pathHash→path` semantic rename
  (needs a real raw→ergonomic mapper, kept on its hand schema); GpuCapabilities is a cross-module
  (gpu-bridge) record. Both tracked 4b follow-ups.
- **SearchTrace proto consumers** (`searchState.ts` + `searchTraceExplain.ts`): the 553 Phase D
  protobuf-es `fromJson` branded-Message mechanism feeding the search-trace explain surface — needs a
  standalone SearchTrace schema + parse-mechanism rewire + branded→plain consumer changes; a focused,
  browser-gated follow-up.
- **schema-as-source** (the strongest 4a form: generate the Java record too): user-deferred; needs a
  schema→Java generator. **481 Pass-3** (the four §E.2.1 decisions): deferred-by-design.
- **Bespoke tests retained** (`WireTypesTsGenerationTest`, the X-cut `*ConformanceTest`,
  `WireShapeMandateTest`): the investigation found they enforce **orthogonal** invariants (record→TS
  capture, record↔proto parity, no-new-polymorphic-hierarchy) the contract-projection gate does not
  cover — retiring them would drop real coverage. (The tempdoc's "retire them" rested on a wrong
  premise; keeping them is the correct call.)

## Corrective-batch live verification (2026-05-31) — ✅ generated Zod validates live wire (+ a bug caught)

Host-side fetch (read-only; the shared dev stack was owned by another worktree, so its build was
queried — the migrated records are unchanged by either branch) + validate against the generated Zods:
**all six migrated surfaces validate live** — `/api/status`, `/api/policy/effective`,
`/api/ai/packs/status`, `/api/ai/runtime/status`, `/api/knowledge/folders`, `/api/knowledge/folder-files`.

The batch did its job: it caught a real faithfulness bug the static fixtures missed — `EffectivePolicy
.PolicySource.path` is a `java.nio.file.Path`, which Jackson serializes as a bare string
(`"file:///…"`) but victools generated as an object. Fixed by teaching `WireSchemaConfig` that
`Path`/`URI`/`File` are string value-types; regenerated; the live policy then validated. (The
in-browser *render* of these surfaces is unchanged behaviour — re-point + validation only, mapper +
rendering identical, covered by the green FE suite — so the substantive live claim is proven directly.)

---

# Tractable remainder (2026-05-31) — indexing, GpuCapabilities (dead), SearchTrace (proto-free)

The three remaining tractable items, committed + statically green:

- **Ph1 — dead-code deletion.** `getGpuCapabilities` + `GpuCapabilities*` (zero call sites; the UI's GPU
  info comes from `/api/inference/status`) and the legacy `listRoots` + `IndexedRoot` (superseded by
  LibrarySurface's substrate path) were dead — deleted, not migrated.
- **Ph2 — indexed-roots LibrarySurface.** The active roots surface did a raw fetch with NO validation; it
  now validates `{items: IndexedRootView[]}` at the boundary via `parseWireContract` against the generated
  `IndexedRootView` (drop-in; fields match). The register's undeclared-consumer gate also caught + fixed an
  unregistered `lifecycleState.ts` StatusResponse import.
- **Ph3 — SearchTrace proto→Zod (FE proto-free).** The last FE protobuf consumer. `searchState.ts` now
  validates the raw trace via the generated `SearchTrace`/`HitStage` Zod instead of `fromJson` (`knowledge_pb`);
  `grep knowledge_pb modules/ui-web/src` is empty. FieldRoles dropped the protobuf meta-keys; the barrel
  re-exports from the generated modules; tests switched to Zod parse. (`@bufbuild/protobuf` stays — other
  proto surfaces still use it.)

## Live verification (corrective batch)
On my own dev stack (port 60952, 584-doc index): host-fetch + validate against the generated Zods — the
**live `/api/indexing-roots/substrate` (IndexedRootView), the live 12-stage `searchTrace` (SearchTrace), and
the per-hit trace (HitStage) all validate**. The browser console was **clean** (no `[WireContract]`, no JS
errors). The in-browser *render* of search/roots hit the same Vite-proxy/SSE hang seen in prior batches
(environmental — the backend serves the wire fine via host-fetch; the FE dev-proxy stalls), reproduced across
stacks; the render is unchanged behaviour covered by the green FE unit suite (`searchTraceExplain.test` asserts
the rendered output strings). So the substantive live claim — the generated Zods validate the live wire — is
proven directly for both surfaces.

## Net state
The FE is **proto-free for SearchTrace** (`knowledge_pb` no longer imported); every live FE wire surface with a
backend record is a **validated generated projection**; dead GPU/legacy-roots code is gone. The remaining
out-of-scope items (full proto retirement, schema-as-source, 481 Pass-3, retained bespoke tests) are the
documented decisions. The tractable remainder is closed.

---

# Tractable-set implementation + closure (2026-06-03)

Picked up per the approved plan (`~/.claude/plans/drifting-nibbling-pumpkin.md`): the tractable
drift-elimination set (record-as-IDL), defer D/E/G, F after A–C. All phases static-green; the
consolidated live-browser batch is deferred per the user (shared dev stack owned by another session).

## Phases landed (all gates + FE typecheck + full FE suite + touched backend module tests green)
- **Phase 1 — Health.** `HealthEvent` is now the single generated authority (`schema-types/health-event.ts`);
  `domains/health.ts` + the barrel derive the FE-ergonomic body aliases from it via `Extract`/`NonNullable`
  (the hand interfaces + `generated/index.ts` override + `UnknownEventBody` removed). `HealthSurface`'s SSE
  ingest validates each event via `parseWireContract(healthEventSchema, …)`. Root-cause fix: taught
  `HealthEventSchemaTest` the `I18nKey→string` value-class override so `MetricRef.label` is a faithful
  string (was a bare object). Registered as a contract surface.
- **Phase 2 — Search/trace.** Removed the dead empty `SearchTraceSchema = z.object({}).loose()` + its
  vestigial use (the ergonomic `SearchResponse` dropped `searchTrace` in 549 E4; trace is validated in
  `searchState` via the generated SearchTrace). The other post-map search schemas are legit ergonomic
  mapper bodies (design §11) — intentionally kept, not deleted.
- **Phase 3 — Agent sessions/history (LIST surfaces).** New app-api records (`AgentSessionsResponse`/
  `AgentSessionSummary`/`AgentTerminationReason`/`AgentHistoryResponse`/`AgentBatchSummary`);
  `AgentController` projects the agent layer's untyped `Map`s → records via `MAPPER.convertValue`
  (app-api can't depend back on app-agent-api → controller-boundary projection). FE re-pointed to
  `parseWireContract(generated, …)`; the fail-open list `.loose()` schemas deleted; `agent.test.ts`
  re-pointed to the generated schemas. `AgentWireProjectionTest` proves the record serializes identically
  to the prior Map wire (round-trip fidelity — the audit-without-test guard). **Session SNAPSHOT** (full
  free-form meta) deferred — observations.md.
- **Phase 4 — Retire `wire-types.ts`.** Migrated the last barrel consumer (`TimeseriesSnapshot`) to
  schema-types (from the clean hand-curated `timeseries-snapshot.v1.json`); deleted `wire-types.ts` +
  `WireTypesTsGenerationTest` + the `typescript-generator` dependency (lockfiles regenerated); pruned the
  barrel; updated `common-workflows.md`.
- **Phase 5 — Indexing failed-jobs.** New `FailedJobsResponse`/`FailedJob` records;
  `IndexingController.handleListFailedJobs` emits the record (built directly from the typed
  `FailedJobInfo`); FE `listFailedJobs` validates via `parseWireContract`. Substrate failed-jobs/roots
  variants + suggested-roots + excludes deferred — observations.md.

## Verification tiers achieved (static)
- Full `./gradlew.bat build -x test` (254 tasks; ArchUnit/PMD/Spotless green).
- `:app-api:test` + `:app-observability:test` + `:ui:test` green; FE `typecheck` + 2342 unit tests green.
- 564 gates: `check-wire-schema-types-regen`, `check-wire-type-single-authority` (16 single-authority
  wire types), and the `contract-projection` 530-kernel gate all pass.

## Deferred (named, not silently skipped)
- **Consolidated live-browser batch** — the per-phase real-UI render gates (health / search+trace /
  agent Sessions+History / failed-jobs) + live-fixture capture + FE faithfulness tests. Deferred because
  the shared dev stack was owned by another agent session. This is the remaining `static-green ≠
  live-working` tier for the user-visible surfaces.
- **F (broad-mandate ratification)** — residual hand wire validators: ~24 `.loose()` schemas (mostly
  legit ergonomic mapper bodies + settings + the agent-snapshot chain) and 1 remaining fail-open
  `validateWithFallback` (the agent snapshot). Recommend NOT graduating the universal "no hand-authored
  wire type" mandate yet — the residual set needs per-surface triage first. User ratification call.
- **D (endpoint registry), E (full proto retirement / schema-as-source), G (481 Pass-3)** — out of
  scope this pass per the plan.
- **Independent-review gate (rule 30) record** — requires a second-agent (reviewer ≠ committer) review +
  live verification; the `gates/independent-review/slices.json` record (liveVerified=true) is added after
  the live batch. Not merged to main (user merges).
- Per-phase follow-ups logged to `docs/observations.md` (config unification, timeseries record-gen,
  agent snapshot, remaining indexing surfaces).

## Independent static review (2026-06-03) — APPROVE-WITH-NITS (reviewer ≠ committer)

An independent reviewer (did not author the code) statically reviewed the full working-tree diff
against the substrate-discipline rubric. Verdict: **APPROVE-WITH-NITS, no blockers.** Confirmed:
wire round-trip fidelity (records serialize identically to the prior Map wire), convertValue safety
(Jackson 3 `FAIL_ON_UNKNOWN_PROPERTIES`=false; boxed fields → no unbox NPE), the MetricRef.label
faithfulness fix, single-authority (no hand second copy), and wire-types.ts retirement (no live
importers; record↔schema gating retained except the noted timeseries gap).

Nits + disposition:
1. TimeseriesSnapshot record↔schema gate gap (hand schema, no record-gen check) — already logged
   (observations.md); defer to the shared-config unification.
2. `agent.test.ts` C44 FE-tier rejection relaxed — comment reframed to state the FE-side rejection is
   genuinely relaxed (the rename defect is instead structurally impossible at the source).
3. `conditionKey` could yield `"undefined|…"` if `id` absent — fixed (added `event.id &&` guard).
4. Apparent ~45-file line-ending churn — NON-ISSUE: the CRLF warnings polluted the file list; the real
   `git diff` is clean/scoped (25 files), git's `.gitattributes eol=lf` normalizes the rest; only
   `app-observability/gradle.lockfile` changed (the typescript-generator removal).

**Gate-30 (`independent-review`) closure note:** the formal `gates/independent-review/slices.json`
record (reviewer ≠ committer, verdict=approve, **liveVerified=true**, coversThrough=tip) is added after
the deferred consolidated live-browser batch — the static review above is its static half.

## Live verification (2026-06-03) — generated Zod validates the running backend; Health renders; console clean

Ran against a live dev stack (apiPort 56461, 794-doc index; AI offline — see note). Three things proven:

1. **Live-wire faithfulness (the substantive 564 claim) — ALL 6 migrated surfaces.** A throwaway probe
   host-fetched the running backend and validated each response against the GENERATED Zod (since
   removed): `/api/status` (statusResponseSchema), `/api/knowledge/search` (knowledgeSearchResponse
   — totalHits=54, **SearchTrace present**), **`/api/health/events/stream` — 4 live HealthEvents
   validated against the generated `healthEventSchema`** (the discriminated `anyOf` body + the
   `MetricRef.label` fix, proven against real events), `/api/chat/sessions` (agentSessionsResponse),
   `/api/chat/agent/history` (agentHistoryResponse), `/api/indexing/failed-jobs` (failedJobsResponse).
   All 6 pass → `parseWireContract` succeeds with no fail-open / no drift.
2. **Real-UI render (browser) — Health surface + status bar.** The Health surface rendered fully from
   the migrated HealthEvent/StatusResponse contracts (Files 794, GPU detected 3.37/11.99 GB, Queue DB
   Healthy, AI Engine, Connection, reindex banner); the status bar shows CONN + 794 docs.
3. **Console clean.** Across health + status loads: 30 console messages, ALL benign (Vite/Lit-dev/SES/
   i18n-fallback DEBUG). **Zero `[WireContract]` drift, zero TypeError/JS exceptions.**

**Honest limits (environmental, not code):**
- *In-browser render of search results + the agent Sessions/History tabs* could not be driven: the FE
  dev-server (Vite) proxy stalls on the search POST + SSE streams ("Reconnecting…"; the agent surface
  froze the renderer) — the **identical Vite-proxy/SSE hang reproduced in every prior 564 live batch**.
  The backend serves the wire correctly (proven by host-fetch above); the render logic is unchanged and
  covered by the green FE unit suite (SearchSurface*, AgentSessionController, HealthLitView render tests).
  The agent LIST envelopes were validated live via host-fetch; the populated-item render is the residual
  proxy-blocked tier.
- *A fresh agent run* (to populate sessions/history) needs the LLM; `ai_activate` failed on the dev
  stack with "Failed to apply runtime overrides" (an inference-runtime/worktree environmental error,
  unrelated to 564 — the worker was mid-enrichment with 793 embeddings queued). The agent
  propose→approve→execute round was live-verified in prior 564 batches; this change only re-points the
  sessions/history LIST surfaces (host-fetch-validated + `AgentWireProjectionTest` round-trip + green
  AgentSessionController unit tests).

**Gate-30 (`independent-review`) record:** the independent review (reviewer ≠ committer, APPROVE-WITH-NITS,
nits addressed) + this live verification satisfy the gate's substance. The formal
`gates/independent-review/slices.json` record (which needs a `coversThrough` commit) is added at
commit time, since this worktree is intentionally left uncommitted for the user to merge.

---

# Merge with main (2026-06-03) — back-merge resolved + verified; ready to forward-merge

Back-merged main (134 commits: tempdoc 559 UI-as-projection, 561 agent ledger + workflow subsystem,
563 frontend analysis + gate retirements) into the 564 branch (merge commit `93a53ebd9`), resolved in
isolation, verified, ready to forward-merge to main (clean fast-forward — main `98e280cb` is an ancestor).

## Conflict reconciliations (10 files)
- **Agent-event keystone** (`ConversationShape.java`): `eventSchema` stays typed `List<EventDescriptor>`
  (564 Phase 0) AND gains main's `implements Provenanced`. Propagated the type to main's new
  `WorkflowRunShape` + `WorkflowShapeRunnerTest` (`EventDescriptor.namesOnly`). `AgentEventSchemaConformanceTest` green.
- **Shape-handler codegen** (`gen-shape-handlers.mjs`): kept the 564 generated-fixture approach over
  main's hand array; regenerated `shapes.fixture.json` (9 shapes incl. `core.workflow-run`) + handlers.
  Critical-analysis confirms the UNION (core-agent-run typed payloads + core-workflow-run both present).
- **Operation catalogs** (`CoreOperationCatalog[Test]`): kept 564 Phase 3 (i18n-from-id + derived
  count) + main's WS4 collapse (navigate-to-surface → AgentToolsOperationCatalog; core count 28).
- **`ToolIteratingShapeRunner`**: kept both main's `toolCompletedPayload` (560) + 564 package-private `eventName`.
- **`registry.v1.json`**: added `contract-projection`; dropped `independent-review` + `ux-audit-closure`
  (retired by 563 — so the deferred gate-30 slices.json record is now MOOT). `AgentToolsOperationCatalog`
  re-added the `I18nKey` import for main's new `remember()` op.
- **`execution-surfaces.v1.json`**: removed the deleted `wire-types.ts` surface; registered the generated
  `schema-types/search-trace.ts` (FE proto-free for SearchTrace) + main's previously-undeclared `citationTypes.ts`.
- Docs (`observations.md`, 564 design doc): unioned / took the rewritten 564 doc.

## Verification (post-merge)
- `./gradlew.bat build -x test` SUCCESSFUL (all modules + ArchUnit/PMD/Spotless/class-size).
- `:app-api`, `:app-observability`, `:app-agent-api`, `:ui`, `:app-agent` tests green; `:app-services`
  1463 tests — only the **pre-existing main `core.remember`** validator failure (observations.md).
- FE typecheck + **2385 unit tests** green (241 files).
- Gates: `contract-projection`, `execution-surface`, `prose-tier-register`, regen (schema-types +
  shape-handlers + 16 single-authority types), tempdoc-numbers — all green. Pre-existing main ratchet
  debt (`ui-bundle` / `consumer-drift` / `stage-completeness`) unchanged (main's files, not 564).
- **Live re-verify** (merged backend): generated Zod validates all 6 migrated surfaces on the running
  merged stack — status, agent sessions/history, failed-jobs, search (54 hits + trace), 3 live HealthEvents.

## Forward-merge handoff (user, from the main checkout `F:\JustSearch`)
    git merge worktree-564-contract-projection   # clean fast-forward to 93a53ebd9
    ./gradlew.bat build -x test                   # post-merge gate
    git branch -d worktree-564-contract-projection
