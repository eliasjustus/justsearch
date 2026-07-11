# 714 — Fragile-field closure: extend the rmwPolicy fail-fast to every field type + dead-field census

- **status:** takeover complete, verdict GO — theorize/design/plan done, awaiting founder
  approval to implement (chartered 2026-07-11 from the 711 close-out)
- **created:** 2026-07-11
- **updated:** 2026-07-11 (takeover + theorize + design + plan, worktree `714-fields`)

## Charter question

Close the remaining third of the RMW bug-class that 711 opened: today's startup fail-fast
(`FieldMapper.validateRmwPolicies`) covers vector/splade *types* only — a fragile field of any
other type (text, keyword-without-docValues, future types) can still ship with no declared RMW
disposition and be silently destroyed. What does full closure look like, and which existing
fields are dead and should simply be deleted?

## Evidence that motivates the charter (verified, citable)

- `content_all` (`SSOT/catalogs/fields.v1.json` — type text, stored:false, docValues:false) is
  destroyed by every RMW and declares no policy. It currently has **no production writer**
  (711 audit: only `modules/benchmarks/` writes it — `EngineIndexBench.java:151`,
  `IndexingOverheadProfiler.java:89`), so today it is a latent trap plus a probable dead
  field, not a live bug. Observation logged 2026-07-11 (folded into the conditions store).
- 711's implementation deviation #4 explicitly scoped it out ("outside the design's
  vector/splade fragile scope — flagged, not fixed").
- The class-closure principle (711 §Reach: "declared RMW disposition ... enforced at the
  single write choke point, not by caller discipline") is only enforced for two field types;
  the principle's earning condition ("new fragile fields fail fast at startup until they
  declare") is not yet true for the general case.

## Candidate shape (for the design pass to confirm or replace)

1. Census every non-stored, non-docValues field in the catalog; classify: has production
   writer? derived-at-write? dead?
2. Dead fields (content_all candidate) → delete from catalog + schema + any writer, with the
   ssot-catalog-sync/regenSsotManifest procedure (711 Step 1 is the worked example).
3. Extend `validateRmwPolicies` to ALL field types, with a disposition vocabulary that covers
   the legitimate non-preserve cases (e.g. `recomputed-at-write` for genuinely derived fields)
   so the fail-fast has no permanent exemption list.

## Constraints / relations

- Small, bounded, no retrieval-semantics change expected; regression tests per 711's pattern
  (`RmwFieldPreservationTest`, startup-validation rejection tests).
- Touching `SSOT/catalogs/**` + `adapters-lucene/**` fires ssot-catalog-sync +
  check-language-agnostic-analysis (both procedures documented in 711 §Derisk E2).
- Related: tempdoc 711 (parent), F-032, tempdoc 553 (representation-drift register idiom).

## Takeover investigation (2026-07-11)

### Census — every field with `stored:false AND docValues:false`

Full sweep of `SSOT/catalogs/fields.v1.json` (68 fields total; both catalog copies confirmed
byte-identical via `JSON.stringify` deep-equal): **exactly 4 fields match the fragile
predicate**, closing the search space the charter opens.

| field | type | rmwPolicy today | production writer | reader | disposition |
|---|---|---|---|---|---|
| `vector` | vector | `preserve-reread` | yes (embedding backfill / combined pass) | yes (vector search mode) | covered by 711 — no action |
| `chunk_vector` | vector | `preserve-reread` | yes (chunk embedding backfill) | yes (chunk-vector search mode) | covered by 711 — no action |
| `splade` | splade | `reset-status:splade_status` | yes (SPLADE backfill / interleaved pass) | yes (SPLADE search mode) | covered by 711 — no action |
| `content_all` | text | **none — unguarded** | **NONE.** Only `modules/benchmarks/EngineIndexBench.java:151` and `modules/benchmarks/IndexingOverheadProfiler.java:89` write it (benchmark code, never on a production write path). | **NONE, functionally.** `IntentJsonTemplate.java:38` emits `"field":"content_all"` into the AI-fallback JSON, but the only consumer, `RemoteKnowledgeClient.extractQueryText` (`RemoteKnowledgeClient.java:923-932`), reads only `clause.type()`/`clause.value()` — `clause.field()` is never read anywhere in the query path (confirmed via repo-wide `\.field\(\)` grep). The real multi-field text query (`TextQueryOps.combineMultiField`, `TextQueryOps.java:127,172,646,654`) is built from `content`/`title`/`author`/entity fields — `content_all` never appears. | **DEAD — delete.** |

`content_all` is the charter's only open item: the census confirms it is the sole
non-vector/non-splade fragile field in the catalog today, and it is provably dead on both the
write and the read side, not merely under-protected.

**Corroborating history (found, not created, by this takeover):** this exact mismatch was
already logged as `obs:intentjsontemplate` in `docs/observations.md:957-959`, dated
**2026-06-15** ("found during tempdoc 581 de-risking") — three and a half weeks before 711's
own observation (`obs:fields-v1`, `docs/observations.md:1991-1993`, 2026-07-11) rediscovered the
same field from the write-path side. Two independent audits, a month apart, converged on the
same dead field from opposite directions (query-side vs. write-path-side). This is stronger
evidence than either one alone, and rules out "flagged once and might have been a fluke."

### Verified: the charter's core technical premise, at file:line

`FieldMapper.validateRmwPolicies` (`modules/adapters-lucene/.../runtime/FieldMapper.java:186`):

```java
boolean dataBearing = "vector".equals(def.type) || "splade".equals(def.type);
boolean fragile = dataBearing && !def.stored && !def.docValues;
```

This is exactly the type-scoped gate the charter describes — a field of any other type (text,
keyword, long, double, boolean) that is non-stored/non-docValues is invisible to the fail-fast
today. The **preservation engine itself is already type-agnostic**: `WritePathOps.applyRmwPolicies`
(`WritePathOps.java:325-348`) iterates `FieldMapper.rmwPolicyFields()` — which is not
type-filtered, just "has a declared `rmwPolicy`" — and dispatches purely on the policy *string*.
Only the validation gate carries the type restriction. This means generalizing the fail-fast is a
**one-line, currently-zero-behavioral-difference change**: `content_all` — the only field that
would newly qualify — is being deleted in the same PR, so no field is affected by the gate
widening on the day it lands. It exists purely to make the *next* fragile field impossible to ship
silently, which is the charter's stated goal ("new fragile fields fail fast at startup until they
declare" — 711 §Reach item 1's earning condition).

### Two risks the charter did not surface (found during this takeover, must shape the design)

1. **`preserve-reread` is not actually type-generic — it is vector-read-specific.**
   `WritePathOps.applyRmwPolicies` (`:333-337`) unconditionally calls `readFloatVector` for any
   field declaring `preserve-reread`. Widening `validateRmwPolicies`'s fragile-detection to all
   types without *also* constraining which types may legally declare `preserve-reread` (only
   `vector`) would let a catalog author declare `preserve-reread` on a `text`/`keyword` field —
   the engine would silently no-op (read a non-existent float vector, get null, preserve
   nothing) instead of failing fast. The design must add a policy-value/type cross-check
   (`preserve-reread` ⇒ `type == "vector"`) alongside the type-restriction removal, not just
   remove the restriction.
2. **The `analyzers.v1.json` catalog entry named `content_all` is not the field's analyzer — it
   is the sole generic ICU analyzer definition, reused by every text field via alias
   resolution.** `SsotAnalyzerRegistry` (`modules/adapters-lucene/.../analyzers/SsotAnalyzerRegistry.java:82-87`)
   builds an alias map where any analyzer entry whose `provider` contains `"icu"` becomes the
   resolution target for the literal key `"icu"` (`aliases.putIfAbsent("icu", def.id())`), and
   every field's `analyzer` property in `fields.v1.json` is the literal string `"icu"` (confirmed:
   the only distinct `analyzer` value across all 68 fields is `"icu"`) — never `"content_all"`.
   The `content_all` **field** and the `content_all` **analyzer catalog entry** are two unrelated
   things that happen to share a name. Deleting the analyzer entry alongside the field would
   silently degrade every text field's analysis: `defaultAnalyzerId`'s fallback logic
   (`SsotAnalyzerRegistry.java:85-91`) would fall through to `defs.keySet().iterator().next()`,
   i.e. whatever's left — currently `"keyword"` (`KeywordAnalyzer`, no tokenization) — for the
   *entire* index. **Field deletion and analyzer-catalog cleanup must be kept strictly separate.
   Recommendation: do not touch `analyzers.v1.json` in this tempdoc at all** — the shared name is
   an unfortunate historical coincidence, not a dependency, and `SsotValidatorFingerprintTest`
   (which pins that entry's fingerprint) stays untouched as a result, further reducing blast
   radius.

### Disposition-vocabulary judgment (AHA / no speculative code)

The charter's candidate shape asks whether the fail-fast needs a `recomputed-at-write` (or
similar) disposition for "legitimate non-preserve cases." **No field in the catalog today needs
one** — `content_all`, the only candidate, is being deleted rather than reclassified. Per the
AHA/over-DRY caution (CLAUDE.md `explore-before-implementing`) and the same judgment 711 itself
applied ("*Store vectors twice*... rejected" — don't build machinery a real case hasn't asked
for yet), the recommendation is: **do not add a third policy value now.** Widening the
type-restriction is what removes the "permanent exemption list" (the actual 711 §Reach earning
condition); a speculative third disposition would ship an engine branch with no field to exercise
it and no test that isn't synthetic. Record the extension point in the schema `$comment`
(`rmwPolicy` pattern) as a note for whoever needs it, not as shipped code.

### Verdict: GO — do it now

- **Should this be done at all?** Yes. It closes a documented gap in a bug-class 711 already
  proved live three separate times (vector destruction, SPLADE status lie, chunk-vector 100%
  loss) and explicitly deferred ("outside the design's vector/splade fragile scope — flagged,
  not fixed", 711 deviation #4). The gap is not hypothetical: `content_all` is a second,
  independently-discovered instance of exactly the failure mode 711 was built to prevent, just
  outside its type filter.
- **Should it be done now?** Yes — no reason to wait. The evidence this takeover needed
  (a complete census + a writer/reader audit of the one open field) is cheap, already gathered,
  and definitive: `content_all` has zero production writers and zero functional readers, verified
  by tracing the actual query-construction code path (`TextQueryOps.combineMultiField`), not just
  grepping for the string. There is no unresolved unknown left to wait on.
- **Cheapest evidence that would validate/invalidate the charter, and does it already exist?**
  The census itself *is* that evidence (which fields are fragile-and-undeclared; whether the one
  such field has a writer or reader) — gathered above, at file:line, via static analysis only,
  matching the charter's own scoping note ("No GPU or dev stack needed... keep it static-analysis
  + unit-test-level evidence"). Nothing further to gather before implementing.
- **What does it displace or duplicate?** Nothing new is displaced; it closes 711's own
  explicitly-flagged deferral (deviation #4) and mechanically extends 711's shipped machinery
  (`validateRmwPolicies`, the `rmwPolicy` catalog attribute, the `RmwFieldPreservationTest`
  pattern) rather than forking a parallel one.
- **Scope correction vs. the charter's candidate shape:** the two risks above (policy/type
  cross-check; analyzer-catalog name collision) are real and must be designed around, but they do
  not change the verdict — they narrow *how* to implement safely, not *whether* to.

## Theorize (2026-07-11)

Alternatives considered for "how does the fail-fast close":

1. **Chosen: widen `validateRmwPolicies`'s `dataBearing` predicate from `{vector, splade}` to
   "any type"** (i.e. `fragile = !def.stored && !def.docValues`, dropping the type check
   entirely). Matches 711's own idiom exactly, zero new abstractions, zero behavior change on
   landing day (post-deletion, no field is newly fragile). This is the only option that actually
   satisfies the charter's stated goal — "a fragile field of *any other type*... can still ship
   with no declared RMW disposition" — the goal is explicitly type-generic.
2. **Rejected: leave the type restriction, add `text` explicitly** (`{vector, splade, text}`).
   Rejected because it just moves the same bug one type over — the next fragile `keyword` or
   `long` field would still ship silently. This is the "discipline, not structure" failure mode
   711 itself named and rejected for the original bug.
3. **Rejected: a catalog-level `additionalProperties`-style lint script outside `FieldMapper`**
   (e.g. a CI check scanning `fields.v1.json` for the fragile predicate, separate from the
   startup fail-fast). Rejected: 711's precedent is deliberately a **runtime** fail-fast
   (`FieldMapper.validatePrimaryKeySupport` precedent) co-located with the parse/validate step
   that already owns this catalog — a second, parallel CI-only check would be a fork of the same
   concept (tempdoc 553 projection-vs-fork discipline), not a projection, and would need its own
   maintenance instead of riding the existing choke point.
4. **Considered and adopted as a must-pair, not an alternative: the `preserve-reread`
   type-constraint** (risk 1 above) — without it, option 1 above is not actually safe to ship.
   This isn't a competing design, it's a precondition for option 1's correctness.
5. **Rejected: deferring `content_all` deletion to a later tempdoc, landing only the gate
   widening.** Rejected because a `text` field that's non-stored/non-docValues, if left with no
   declared policy, would make the *newly widened* gate fail the build immediately —
   `content_all` must be deleted (or given a policy) in the *same* change as the gate widening,
   not after. They are not independently sequenceable; this is a single PR by construction, matching
   711's own PR-1 shape (catalog + validation + engine + tests together).

No internet research was warranted — this is a pure codebase-closure task with no external
prior-art question (the design precedent is 711 itself, already shipped in this repo).

**Broader principle this points toward:** 711's §Reach item 1 already named the general
principle ("a field that cannot survive the write path's RMW must declare, in the catalog, what
happens when a write omits it"). This tempdoc is not a new principle — it is the observation that
a principle scoped by *implementation convenience* (which two types happened to be fixed first)
rather than by the *actual hazard* (any non-recoverable field, any type) leaves a gap shaped
exactly like the thing it was built to prevent. The recurring shape worth naming for future
catalog work: **when a fail-fast is introduced to close a bug class, scope its trigger condition
to the hazard's true boundary (here: "non-stored AND non-docValues", a structural fact about
every field), not to the specific instances that motivated it (here: "type is vector or splade",
an accident of which fields were fixed first).** A type-enumerated allowlist inside a fail-fast is
itself a smaller version of the "caller discipline" anti-pattern 711 rejected — it just moved the
discipline from call sites to catalog-authors-choosing-a-type.

## Design (2026-07-11)

**Scope: one PR, four coordinated changes, all inside 711's existing machinery — no new
abstractions.**

1. **`FieldMapper.validateRmwPolicies` (`FieldMapper.java:184-223`):**
   - Change `dataBearing` to unconditionally `true` (i.e. drop the `"vector".equals || "splade".equals`
     check) so `fragile = !def.stored && !def.docValues` for every type.
   - Add a policy/type cross-check: if `policy.equals(RMW_PRESERVE_REREAD)`, additionally require
     `"vector".equals(def.type)`, else throw `IllegalStateException` naming the mismatch (closes
     risk 1 from the takeover). `reset-status:<target>` keeps its existing target-exists /
     target-docValues checks unchanged — those are already type-agnostic (a `reset-status` target
     just needs to be a docValues-backed status field, regardless of the fragile field's own type).
   - Update the method's javadoc (currently says "type `vector` or `splade`") to describe the
     type-generic contract.
2. **Delete `content_all` end-to-end** (same PR, same commit or immediately adjacent per Theorize
   item 5):
   - `SSOT/catalogs/fields.v1.json` and its `modules/adapters-lucene/.../SSOT/catalogs/fields.v1.json`
     mirror: remove the `content_all` field entry (both copies, ssot-catalog-sync gate re-verifies
     deep-equal).
   - **Do not touch `SSOT/catalogs/analyzers.v1.json`** (either copy) — risk 2 above; the
     `content_all` analyzer entry is the load-bearing default ICU analyzer for all text fields and
     shares nothing but a name with the field being deleted.
   - `modules/indexing/SchemaFields.java:25` — remove the `CONTENT_ALL` constant (only two
     production-tree consumers remain after this change, both being deleted below).
   - `modules/benchmarks/EngineIndexBench.java:151` and `IndexingOverheadProfiler.java:89` —
     remove the `fields.put(SchemaFields.CONTENT_ALL, ...)` writes (dead-field writers; benchmarks
     should stop exercising a field the catalog no longer defines, or the benchmark harness's own
     `toDocument()` call would throw on an unknown field).
   - `modules/ai-backend/backend/IntentJsonTemplate.java:38` — the AI-fallback JSON currently
     hardcodes `"field": "content_all"`, referencing a field that (per the census) was never
     actually read downstream and is now deleted from the catalog outright. Recommend changing the
     literal to `"content"` (the field that *is* live, stored, and actually searched) — this is a
     one-line fix that also resolves the pre-existing `obs:intentjsontemplate` observation
     (2026-06-15) as a byproduct, not scope creep: leaving a dead catalog-field-name in a
     JSON-shaped payload the moment that catalog is the very thing being cleaned up would be
     inconsistent with the same PR's own intent. (If the founder prefers to leave this untouched as
     strictly out of charter, it is a one-line revert — flagged here as a recommendation, not
     assumed.)
   - `modules/app-services/src/test/java/io/justsearch/app/services/DefaultAppFacadeTest.java:24`
     — a test literal (`new SearchRequest.Clause("text", "content_all", ...)`) unrelated to the
     catalog (it doesn't validate against `fields.v1.json`); update to `"content"` for consistency
     with the `IntentJsonTemplate` change above, but not load-bearing either way.
   - No schema change needed for the deletion itself — `field-catalog.schema.json`'s `rmwPolicy`
     pattern is unaffected by removing a field that never declared one.
3. **Regression tests (`RmwFieldPreservationTest`, matching 711's own pattern):**
   - New fail-fast test: a non-stored/non-docValues field of a *non-vector/splade* type (e.g. a
     synthetic `text` or `keyword` fixture field) with no `rmwPolicy` declared → startup
     `validateRmwPolicies()` throws (proves the gate is now type-generic — the actual regression
     `content_all` would have caught before this fix).
   - New fail-fast test: a non-vector field declaring `preserve-reread` → throws (proves risk-1's
     cross-check).
   - No behavioral test needed for `content_all`'s removal itself beyond "the catalog parses and
     `validateRmwPolicies()` passes on the shipped catalog" (implicitly covered by every existing
     test that constructs a real `FieldMapper` from the shipped catalog).
4. **Verification tier:** compile + `:modules:adapters-lucene:test` (with the new
   `RmwFieldPreservationTest` cases), `:modules:indexing:test`, `:modules:benchmarks` compile
   (benchmarks module has no unit tests today, so `build -x test` compile-only is the applicable
   gate there), `:modules:ai-backend:test`, `:modules:app-services:test`,
   `:modules:ssot-tools:test` (ssot-catalog-sync + fingerprint), full
   `./gradlew.bat build -x test`. Matches the charter's own note: "no retrieval-semantics change
   expected" — no live-stack / dev-stack run is required (`content_all` is not read by any live
   query path, and no vector/splade field's behavior changes).
5. **Procedure for the catalog touch:** per `docs/reference/contributing/common-workflows.md` /
   the `ssot-catalog` skill and 711's own worked Step-1 example — edit both `fields.v1.json`
   copies, run `./gradlew.bat :modules:ssot-tools:regenSsotManifest`, then
   `:modules:ssot-tools:test` to confirm the deep-equal + fingerprint gates hold.
6. **Explicitly out of scope (per the takeover's disposition-vocabulary judgment):** no new
   `rmwPolicy` vocabulary entry (e.g. `recomputed-at-write`); no `analyzers.v1.json` change; no
   rename of the `content_all` analyzer catalog entry (tempting for name-hygiene, but a distinct,
   higher-risk, out-of-charter task — flagged as a possible future observation, not touched here).

### Reach judgment

**Principle:** *"a fail-fast's trigger condition should be scoped to the hazard's structural
boundary, not to the specific instances that motivated writing it."* This is not a new principle
— it is 711 §Reach item 1's own principle (*"a field that cannot survive RMW must declare its
disposition, enforced at the choke point, not by caller discipline"*) with its scope corrected to
match what it already claimed to cover. This tempdoc doesn't introduce a second principle
alongside 711's; it is 711's principle actually reaching its stated boundary.

**Where else it would apply:** any other startup/parse-time fail-fast in this catalog family that
is currently keyed off a type enum rather than a structural predicate is worth a look with the
same lens — e.g. if a future validator keys off `type == "vector"` for something that is really
about "has a `vector` sub-object" or "is roles-tagged `vector`/`chunk_vector`", the same
narrowing-by-accident risk applies. No other instance was found in `FieldMapper.java` during this
takeover (`validatePrimaryKeySupport` is keyed off the `id`/`unique`/`roles` role tag, not a type
enum, so it doesn't have this shape); this is a candidate lens for future catalog-validation work,
not a finding of an existing second violation.

**Earning its keep:** the next fragile field (any type) added to the catalog fails the build at
declaration time instead of shipping a silent data-loss bug — i.e., a repeat of exactly what
happened to `content_all` (added without a declared disposition, went unnoticed for at least a
month per the two independent observations) becomes structurally impossible rather than
depending on someone noticing.

**Retire when:** identical to 711's own retirement condition for the parent principle — the write
path becomes append-only/single-writer per doc, or an index-engine change makes non-stored-field
RMW lossless natively for all types. There is no separate retirement condition for the
type-generalization itself; it is not a separate mechanism, just the parent principle's scope
finally matching its own claim.

## Plan (2026-07-11) — for founder approval, NOT executed

No plan-mode tool is available in this environment; this section is the implementation plan,
written for review before any code is touched. Nothing below has been implemented.

**Step 1 — Catalog + schema (small, mechanical):**
- Remove the `content_all` entry from `SSOT/catalogs/fields.v1.json` and the
  `modules/adapters-lucene/.../SSOT/catalogs/fields.v1.json` mirror.
- Run `./gradlew.bat :modules:ssot-tools:regenSsotManifest`.
- Verify `:modules:ssot-tools:test` green (ssot-catalog-sync deep-equal, fingerprint tests
  untouched since `analyzers.v1.json` is not touched).

**Step 2 — Parse/validate generalization (`FieldMapper.java`):**
- Drop the `"vector"/"splade"` type restriction in `validateRmwPolicies` so any non-stored,
  non-docValues field must declare a policy.
- Add the `preserve-reread` ⇒ `type == "vector"` cross-check.
- Update the method javadoc.
- Compile + run existing `RmwFieldPreservationTest` (should stay green — the shipped catalog,
  post-Step-1, has no unguarded fragile field left).

**Step 3 — Delete dead-field writers/reader-literal:**
- `modules/indexing/SchemaFields.java`: remove `CONTENT_ALL`.
- `modules/benchmarks/EngineIndexBench.java:151`, `IndexingOverheadProfiler.java:89`: remove the
  `content_all` field write (confirm the benchmark still compiles/runs sensibly without it — it's
  writing `title + " " + body` into a field that no longer exists in the catalog, so this is a
  required change, not optional, once Step 1 lands — `toDocument()` would otherwise throw on an
  unknown field for these two writers).
- `modules/ai-backend/backend/IntentJsonTemplate.java:38`: change `"content_all"` literal to
  `"content"` (recommended; flag for founder sign-off per Design item 2).
- `modules/app-services/src/test/java/io/justsearch/app/services/DefaultAppFacadeTest.java:24`:
  same literal update for consistency.

**Step 4 — New regression tests in `RmwFieldPreservationTest`:**
- Fail-fast rejects an undeclared non-vector/non-splade fragile field (synthetic fixture).
- Fail-fast rejects `preserve-reread` declared on a non-vector field (synthetic fixture).

**Step 5 — Full verification:**
- `./gradlew.bat spotlessApply`.
- `./gradlew.bat build -x test` (compile all, incl. `benchmarks`).
- `./gradlew.bat :modules:adapters-lucene:test :modules:indexing:test :modules:ai-backend:test
  :modules:app-services:test :modules:ssot-tools:test`.
- `./gradlew.bat test` (full suite) before marking ready, per the standard pre-merge gate.
- No dev-stack / live-stack run required (static + unit-test tier suffices per the charter's own
  scoping and the Design section's verification-tier note).

**Estimated blast radius:** ~6 files touched for the deletion (2 catalog copies + SchemaFields +
2 benchmark writers + IntentJsonTemplate [+ 1 test literal]), ~1 file for the gate generalization
(`FieldMapper.java`), ~1 test file for new coverage (`RmwFieldPreservationTest`). No touches to
`analyzers.v1.json`, `field-catalog.schema.json`, `WritePathOps.java`'s engine dispatch (already
generic), or any live query/search code path.

**Awaiting founder go-ahead before implementation begins** (per project convention: takeover /
design / plan is investigation, not authorization to write code).
