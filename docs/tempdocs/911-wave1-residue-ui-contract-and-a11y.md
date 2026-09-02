---
title: "Wave-1 UI residue: a parse-boundary contract for the substrate failed-jobs wire, and the a11y gate's two capture errors"
type: tempdocs
status: "IMPLEMENTED (2026-09-02) — both items closed; verification recorded in §D"
created: 2026-09-02
updated: 2026-09-02
lane: R6 (wave-1 residue closure)
model: fable (implementation)
parent: 885-decision-review-lane-c-runtime-lifecycle-and-isolation
related:
  - 885-decision-review-lane-c-runtime-lifecycle-and-isolation  # UL.9 (wire contract), UL.10 (a11y gate), item 21b (RETRY_EXHAUSTED)
  - 564-wire-contract-single-authority        # the record -> JSON Schema -> {TS, Zod} pipeline this rides
  - 884-surface-manifest-wire                 # tightened /api/registry/surfaces to the generated Zod — the cause of item 2
  - 855-settings-as-a-window                  # made Settings MODAL and Security a settings member
  - 599-folder-indexing-journey-no-status     # §16/B1 — the FailedJobsDrawer this contract protects
---

# 911 — Wave-1 UI residue (lane R6)

Two items routed by 885's residue pass:

- **UL.9** — the substrate failed-jobs wire had no parse-boundary contract. The endpoint the
  `FailedJobsDrawer` reads hand-built a `Map<String,Object>`, so nothing described it, and the FE
  read `state` — the RETRY_EXHAUSTED discriminator PR #603 depends on — off untyped JSON.
- **UL.10** — `jseval ui-a11y-gate` exited 2 (capture error) on the `security` / `security-light`
  steps, on `main`'s own frontend, pre-existing and unrelated to #603.

---

## §A — Scope and ownership

Lane R6 owns `modules/ui-web/**`, the jseval ui-check harness, `SSOT/schemas/**`,
`IndexingController.java` + `IndexingRoutes.java` (item 1 only), and the generated schema-types.

**One declared deviation from the brief's ownership fence.** The brief said not to edit
`governance/**`; it also said to make `--gate contract-projection` pass. Those cannot both hold: the
gate's *register-coherence* check (`scripts/governance/gates/contract-projection/enforcer.mjs:110-120`)
fails the build when a codegen `TARGETS` entry has no matching record in
`governance/contract-surfaces.v1.json`, and its *undeclared-consumer* check
(`enforcer.mjs:143-162`) fails when an FE file imports a generated module without being a declared
consumer. So a new generated projection is unrepresentable without exactly one record in that file.
One record was added (`FailedIndexingJobsResponse`, +6 lines); nothing else in `governance/**` was
touched, and `expected-state.v1.json` (lane R5's) was not touched at all. Flagged rather than
resolved silently.

---

## §B — Pre-implementation pass: every claim in the brief, verified verbatim

| Brief's claim | Verdict | Evidence |
|---|---|---|
| `SSOT/schemas/indexing-job-view.v1.json` describes `pathHash`/`state`/`retryAfterMs` | **TRUE** | `SSOT/schemas/indexing-job-view.v1.json:5-27` — 8 properties, no `required` array (all-optional, plain `"string"` types) |
| No generated TS projection for it | **TRUE** | `modules/ui-web/src/api/generated/schema-types/` held `failed-jobs-response.ts` only; `scripts/codegen/gen-wire-schema-types.mjs` `TARGETS` had no indexing-job entry |
| `failed-jobs-response.ts` is the legacy `path`-carrying shape | **TRUE** | `SSOT/schemas/failed-jobs-response.v1.json:14-30` — items carry `path`, not `pathHash`; that is the *other* endpoint (`GET /api/indexing/failed-jobs`, `IndexingController.java:603`) |
| The by-prefix envelope is a hand-built `Map<String,Object>` omitting `scanId` | **TRUE** | pre-change `IndexingController.handleListFailedJobsByPathPrefix` built a `LinkedHashMap` with 7 keys (`pathHash`, `state`, `attempts`, `lastUpdatedMs`, `errorMessage`, `retryAfterMs`, `collection`) — `scanId` absent |
| `FailedJobsDrawer.refresh` reads `state` off untyped JSON with zero contract coverage | **TRUE** | pre-change `FailedJobsDrawer.ts` did `String(j['state'] ?? '')` over `Array.isArray(body?.jobs) ? body.jobs : []` |
| `state` is what the RETRY_EXHAUSTED display depends on | **TRUE** | `FailedJobsDrawer.ts` `renderRow`: `r.state.toUpperCase() === JOB_STATE_RETRY_EXHAUSTED` |

### §B.a — One correction to the brief's framing

The brief scoped item 1 to the by-prefix endpoint. But **`handleListFailedJobsSubstrate`
(`GET /api/indexing-jobs/failed`) built a byte-identical map with the same `scanId` omission** —
two copies of one projection, which is how the field came to be dropped from both. Giving the
by-prefix endpoint a record while leaving its twin hand-built would have created the exact
fork the projection-vs-fork rule exists to prevent. Both handlers now share one record
(`FailedIndexingJobsResponse`) and one mapping helper (`IndexingController.toJobView`).

### §B.b — `scanId` is out of lane scope, **not** unavailable (corrected after review S2-3)

The first version of this section said `scanId` "cannot be sourced". That was wrong, and the
correction matters: **the data exists and is simply not selected.**

| Layer | State | Evidence |
|---|---|---|
| SQLite | `scan_id` IS written on every enqueue | `SqliteJobQueue.java:392`, `:473` (INSERT column lists) |
| The failed-jobs SELECTs | neither selects it | `SqliteJobQueue.java:1664-1669` (`listFailedJobs`) and `:1728-1733` (`listFailedJobsByPathPrefix` — the one the drawer actually reads); both are `SELECT path, error_message, attempts, last_updated, collection, state` |
| proto | no field for it | `indexing.proto` `message FailedJob {…}` — 6 fields, no `scan_id` |
| Head DTO | no component for it | `RemoteKnowledgeClient.java:1219`, `:1241` build `FailedJobInfo`; `IndexingService.java:298-304` declares 6 components |

All four are worker / app-services surfaces, outside lane R6. So the projection emits `""` — but
`IndexingJobView`'s contract defines `""` as "single-file ingest, watcher, or a pre-`scan_id` row",
a **real state**, so emitting it unqualified would make this surface assert something it never
checked. Two things keep that honest rather than silent:

- `IndexingController.SCAN_ID_NOT_PLUMBED` — the value is a named constant, not an inline `""`, so
  the gap is greppable and cannot be misread as a checked "no scan"; the `toJobView` javadoc states
  the four-layer cause.
- §F.2 states the fix as the four-layer plumb, not as a vague follow-up.

**Why not mark the component nullable instead** (review S2-3 preferred it if clean): it is not
clean here. `scanId` is a component of `IndexingJobView`, which `RemoteIndexingJobsBridge.toView`
also produces for the `core.indexing-jobs` Resource — where the value IS plumbed and is never null.
Making it nullable would weaken a record two surfaces share, and widen the FE type to
`string | null` for every consumer, in order to describe one projection's gap. Dropping the key is
what made the payload un-typeable; emitting it labelled is the honest minimum in lane.

### §B.c — Precision opt-in, and the generator fork it exposed (corrected after review S2-2)

`IndexingJobView` and `FailedIndexingJobsResponse` both implement `PreciseWire`
(`modules/app-agent-api/.../PreciseWire.java`), so `WireSchemaConfig` emits `required` + non-null
instead of the permissive all-optional default. This is *true* of the record: the compact
constructor `requireNonNull`s `pathHash`/`state`/`collection`, normalizes `errorMessage`/`scanId` to
`""`, and the rest are primitives — and of both producers (`RemoteIndexingJobsBridge.toView` reads
proto getters, which never return null; `IndexingController.toJobView` defaults every slot).

**The first version treated a fork as a safety property.** It observed that
`IndexingJobViewSchemaTest.java:40-49` built its **own** plain victools config — no
`withRequiredCheck` / `withNullableCheck` — and concluded, approvingly, that
`indexing-job-view.v1.json` would stay byte-identical. What that actually meant is that
`PreciseWire` was a **no-op in that test**, leaving two generated schemas describing one record and
disagreeing: `indexing-job-view.v1.json` said all-optional/all-nullable, while the same record
inlined at `failed-indexing-jobs-response.v1.json` said all-required/non-null. And the permissive
one is the one the product advertises for **this very endpoint** —
`FailedIndexingJobsResourceCatalog.java:88-89` points `core.failed-indexing-jobs` at
`indexing-job-view.v1.json`, `SchemaController.java:63` serves it, `ResourceView.ts:279` fetches it.

Fixed: `IndexingJobViewSchemaTest` now calls `WireSchemaConfig.generator()` — the single generator
authority — and the baseline was recaptured. The two are now identical (verified by comparing
`indexing-job-view.v1.json` minus `$schema` against the inlined `jobs.items` node: equal). No
consumer broke: every reference is to the file *name*, not its contents
(`IndexingJobsResourceCatalogTest.java:44` asserts only the URL suffix).

### §B.d — Item 2: the brief's hypothesis was wrong; the cause is one layer down

The brief suspected a stale harness step (retired route / missing modal-open). **It is not.** The
step definition is correct per tempdoc 855:

- `ui_check.py:_view_setup` finds no rail button for `core.security-surface` (it is off-rail
  DEEPLINK since 855), falls through to `location.hash = 'justsearch://surface/<id>'`, then waits on
  `jf-settings-window dialog[open]` and `jf-settings-window jf-settings-surface jf-security-surface`.
  That is exactly the member→host redirect 855 designed.

The real chain, each link read from source:

1. `catalogResolver.ts` `memberHostAliases()` builds the `core.security-surface → core.settings-surface`
   redirect by scanning `listSurfaces()` for `host.members`. `resolveSurface` applies it **before**
   exact-match, precisely so a member deep-link does not mount standalone.
2. `members` arrives **only from the wire catalog**. `CorePlugin.ts:147-159` declares
   `core.security-surface` FE-side but the FE `core.settings-surface` declaration carries no
   `members`; the merge preserves the wire entry's.
3. `SurfaceCatalogClient.tryFetchAndPopulate` (`SurfaceCatalogClient.ts:352-364`) validates
   `/api/registry/surfaces` with `parseWireContract(surfaceCatalogSchema, …)` — added by **tempdoc
   884** — and `catch`es, returning `false`.
4. The generated `surfaceWireSchema`
   (`modules/ui-web/src/api/generated/schema-types/surface.ts:98-...`) is a `z.strictObject`
   requiring `altitude`, `riskTier`, `stateSchema`, `members`, `consumes.conversationShapes`,
   `provenance.identity`, `presentation.category`, `presentation.iconHint`.
5. `ui_fixtures.py::_surface_entry` supplied **none of those**, and omitted `members` entirely when
   empty. Its own docstring asserted the client "does NOT Zod-validate this envelope" — true when
   855 wrote it, invalidated by 884, and never re-run against the security steps.

So: in `--fixtures` mode the surface-catalog fetch fails on every retry, `members` never lands, the
redirect never resolves, `core.security-surface` exact-matches itself and mounts standalone, and the
settings dialog never opens. **Fixtures-only** — production `/api/registry/surfaces` is serialized
from the same Java record the schema is generated from, so it is conformant by construction. Not a
product defect; the security surface is reachable for users.

The values the fixture now carries are not invented: `CoreSurfaceCatalog.java:579-628` constructs
these four surfaces with the short `Surface` constructor, and `Surface.java:99-107` defaults
`riskTier = LOW`, `altitude = PRODUCT`, `members = List.of()`.

---

## §C — What changed

### Item 1 (UL.9)

| File | Change |
|---|---|
| `modules/app-api/.../indexing/FailedIndexingJobsResponse.java` | **new** — `record(List<IndexingJobView> jobs, int count) implements PreciseWire` |
| `modules/app-api/.../indexing/IndexingJobView.java` | `implements PreciseWire` (+ javadoc for why it is true) |
| `modules/ui/.../IndexingController.java` | both substrate handlers emit the record; one shared `toJobView` helper replaces two hand-built maps |
| `SSOT/schemas/failed-indexing-jobs-response.v1.json` | **new**, generated (all 8 view fields `required`, non-nullable) |
| `WireRecordSchemaGenTest.java` | capture-or-verify entry for the new record |
| `gen-wire-schema-types.mjs` + `governance/contract-surfaces.v1.json` | new TARGET + register record |
| `modules/ui-web/src/api/generated/schema-types/failed-indexing-jobs-response.ts` | **new**, generated (type + Zod) |
| `modules/ui-web/src/shell-v0/components/FailedJobsDrawer.ts` | `parseWireContract(failedIndexingJobsResponseSchema, …)`; `FailedRow` derived from the generated type instead of re-declared |
| `modules/ui/build.gradle.kts` + `modules/ui/gradle.lockfile` | `libs.json.schema.validator` on the test suite |

`docs/reference/api-contract-map.md` was **not** changed: `grep -n "indexing-jobs" ` returns no row
for `/api/indexing-jobs/failed` or `.../by-prefix` — the doc never documented either endpoint — so
no row's shape or schema changed and no update was due.

`RouteResponseSchemas.java` **was** updated (review nit): the map's own stated criterion is "routes
that return a documented wire record (those in `governance/contract-surfaces.v1.json`)", which both
routes now meet. Both entries point at the one shared schema, and
`SchemaController.SCHEMA_NAMES` gained the file so the OpenAPI `$ref` resolves —
`RouteResponseSchemasCoverageTest` enforces that closure.

### §C.a — Fixes from the independent review (S2-1 / S2-2 / S2-3)

| Finding | Fix | Files |
|---|---|---|
| **S2-1** — the PR opened a regression: `parseWireContract` returns the RAW body in prod, and the PR removed the downstream defence, so `r.state.toUpperCase()` threw inside Lit's `performUpdate` (outside `refresh()`'s try/catch) → frozen drawer, strictly worse than pre-PR | `toRow` normalizes the three dereferenced fields at the ONE parse site (covers `retryAll` / `onRowActionSuccess` too, which key on `pathHash`), mirroring `api/domains/indexing.ts`'s defensive `(parsed.jobs ?? [])`; two prod-posture tests added | `FailedJobsDrawer.ts:56-70`, `:130`; `FailedJobsDrawer.test.ts` (new `PROD posture` describe) |
| **S2-2** — two generated schemas disagreeing about one record (see §B.c) | `IndexingJobViewSchemaTest` → `WireSchemaConfig.generator()`; baseline recaptured | `IndexingJobViewSchemaTest.java:38-48`, `SSOT/schemas/indexing-job-view.v1.json` |
| **S2-3** — `scanId` certified required and always empty (see §B.b) | named `SCAN_ID_NOT_PLUMBED` + four-layer cause in the javadoc; §B.b reworded from "cannot be sourced" to "out of lane scope"; §F.2 states the plumb | `IndexingController.java:916-940` |
| **Ride-along** — the fixture-vs-schema guard for the exact defect that bit | new pytest validating every `_surface_entry` body against `SSOT/schemas/surface.v1.json` (`jsonschema`, already in jseval's `dev` extra) | `scripts/jseval/tests/test_ui_fixtures_wire_schema.py` |
| **CI `dead-code`** — `schema-types/index.ts` 51 → 53 unused exports | `declared-growth` changeset; **not** the #611 whole-file masking trap — the barrel re-exports every target and the new module's own two exports are both consumed, so only the barrel row moves (same shape as 884's `surface.ts` changeset) | `gates/dead-code/.changesets/911-failed-jobs-wire-projection.md` |

### Item 2 (UL.10)

`scripts/jseval/jseval/ui_fixtures.py::_surface_entry` — schema-COMPLETE against `surfaceWireSchema`;
`members` always present; docstring corrected (it asserted the opposite of current behavior).

---

## §D — Verification

### Tests added, each falsified once

| Test | Falsification | Observed failure |
|---|---|---|
| `IndexingControllerFailedJobsWireContractTest.byPrefixConformsToSchema` (`modules/ui`) | reverted the by-prefix handler to the pre-911 hand-built map that dropped `scanId` | `does not conform to SSOT/schemas/failed-indexing-jobs-response.v1.json: [/jobs/0: required property 'scanId' not found, /jobs/1: …, /jobs/2: …]` — the exact stated reason; restored, green |
| `FailedJobsDrawer.test.ts` "refuses a by-prefix body missing the required `state` field instead of rendering it" | replaced `parseWireContract(...)` with the old untyped cast | that test alone failed (1 failed / 7 passed) — no collateral, so it is the parse boundary it measures, not the render path |
| `FailedJobsDrawer.test.ts` "a RETRY_EXHAUSTED row reads as gave up…" (fixtures upgraded to schema-shaped) | `exhausted = r.state.toUpperCase() === 'FAILED'` | that test alone failed (1 failed / 7 passed) — the assertion still distinguishes the two terminal states after the fixture change |
| `FailedJobsDrawer.test.ts` PROD posture: "renders a state-less row instead of throwing, and records the drift" + "survives a jobs array of nulls" (review S2-1) | removed the `.map(toRow)` normalization | both failed with `TypeError: Cannot read properties of undefined (reading 'toUpperCase')` — i.e. the falsification **reproduced the exact regression the review described**, which is also the proof it was real |
| `scripts/jseval/tests/test_ui_fixtures_wire_schema.py` (ride-along) | deleted `"altitude": "PRODUCT"` from `_surface_entry` | `surface fixture entry 'core.settings-surface' does not satisfy SSOT/schemas/surface.v1.json: $: 'altitude' is a required property` — the precise field, named |

`IndexingControllerFailedJobsWireContractTest.substrateConformsToSchema` covers the sibling endpoint
(same record, same schema) so the de-duplication in §B.a cannot silently regress on one side only.

### Post-implementation critical pass — which wrong implementation would still pass?

- **`byPrefixConformsToSchema`.** Schema conformance alone would pass an implementation that emitted
  the right *keys* with wrong *values* (e.g. every `state` hard-coded to `"FAILED"`), because the
  schema says `"type":"string"`. So the test also asserts the three things the schema cannot:
  `RETRY_EXHAUSTED` survives the projection, `scanId` is present, and a blank worker state defaults
  to `FAILED` (not `""`). It also asserts no raw path appears on the wire (ADR-0028), which schema
  conformance is blind to.
- **The drift test.** It would pass for the wrong reason if the drawer failed for *any* reason —
  e.g. a thrown fetch. Guarded: it asserts the surfaced text contains `WireContract` (the marker
  `parseWireContract` puts in its message) and, separately, that it is **not** the empty-state
  string — an empty list would otherwise read as "this folder has no failed files", which is the
  plausible-but-wrong screen the whole item is about.
- **The gave-up test.** Both rows now come from one `job()` builder differing only in `state`, so
  the assertion cannot pass because of an incidental fixture difference.
- **Retired assertion, replaced not deleted.** The old test "a row with NO state (older backend)
  renders as FAILED" asserted a body the contract now forbids. Its *property* (an unrecognized state
  must not fall into the exhausted arm) is kept as "a non-exhausted state renders WITHOUT the gave-up
  line", driven with `state: 'PENDING'`; the "missing field" half became the drift test above. No
  coverage was dropped to make a test pass.
- **Wrong-gate check.** `JOB_STATE_RETRY_EXHAUSTED` is the constant projected from
  `IndexingJobView.STATE_RETRY_EXHAUSTED`; the Java test asserts the endpoint emits that same
  constant, so the FE arm and the backend vocabulary are pinned to one spelling from both ends.
- **Item 2 — did it pass for the right reason?** The `security` step's readiness selector is
  `jf-settings-window jf-settings-surface jf-security-surface`, i.e. the Security **category content
  mounted inside the settings modal** — not merely "the modal opened". Its going green is the
  redirect working, which is the thing that was broken.

### Commands run

```
./gradlew.bat :modules:app-api:cleanTest :modules:app-api:test --tests "*WireRecordSchemaGenTest*" \
    --tests "*IndexingJobViewSchemaTest*" --no-build-cache   -> BUILD SUCCESSFUL
./gradlew.bat :modules:ui:test --tests "*IndexingControllerFailedJobsWireContractTest*" -> BUILD SUCCESSFUL
```

`jseval ui-a11y-gate`, before (on this worktree's unmodified frontend) and after:

```
before: exit_code 2  summary "capture error"   18 ok / 2 ERROR
        security       ERROR  Locator.wait_for: Timeout 10000ms exceeded.
                              waiting for locator("jf-settings-window dialog[open]")
        security-light ERROR  (identical)
after:  exit_code 0  summary "clean — no NEW a11y violations vs baseline"   20 ok / 0 ERROR
        {"surface": "security", "step": "security", "status": "ok", "new": [], "known": 0}
        {"surface": "security-light", "step": "security-light", "status": "ok", "new": [], "known": 0}
```

`jseval ui-proportion-gate` (same fixture path, regression check): exit 0.

The remaining floor — full ui-web unit suite, the ui-web gate set, `--gate wire`,
`--gate contract-projection`, `./gradlew.bat build -x test`, `:modules:ui:test` — is recorded in the
PR body.

> **Method note.** The first `ui-a11y-gate` run was piped to `tail` and reported `EXIT=0` while the
> command had actually exited 2 — the `piped-exit-masked` lesson, live. Every exit code above comes
> from a bare invocation with the output redirected to a file.

---

## §E — Report-back to 885

- **UL.9 — closed.** Both substrate failed-jobs endpoints are one typed record with a generated
  schema and a generated FE parse boundary. The `scanId` omission is fixed; the field is present and
  empty, because the Head has no source for it (see §B.b) — 885 should note that plumbing `scanId`
  through `IndexingService.FailedJobInfo` is a worker-services change nobody has needed yet.
- **UL.10 — closed.** Not a stale step and not a product defect: tempdoc 884 tightened the surfaces
  parse boundary and the jseval fixture was never updated, so the harness's own test double had been
  silently invalid since #597. The class of defect (a generated `z.strictObject` outgrowing a
  hand-written fixture, failing closed inside a `catch`) can recur for any other fixture route.

## §F — Open items

1. **The fixture-vs-schema guard is now real but single-route.**
   `scripts/jseval/tests/test_ui_fixtures_wire_schema.py` validates `/api/registry/surfaces` against
   `SSOT/schemas/surface.v1.json`, which closes the exact defect this lane hit. It does **not**
   generalize: every other fixture body a `parseWireContract` consumer reads is still one schema
   tightening away from the same silent, fail-closed-inside-a-`catch` failure. The generic register
   (each fixture route → its schema, asserted for all) stays routed, not done here. Its honest limit
   is also stated in the test: JSON Schema checks required-ness and types, not unknown keys, while
   the generated Zod is `z.strictObject` — so an extra key still passes locally and fails in the
   browser.
2. **`scanId` is not plumbed to failed-job rows — four layers, all outside lane R6** (§B.b). The
   fix, in order: (a) add `scan_id` to **both** failed-jobs SELECTs — `listFailedJobs`
   (`SqliteJobQueue.java:1664-1669`) and `listFailedJobsByPathPrefix` (`:1728-1733`), the latter
   being the one the drawer reads, so fixing only the former would leave the surface that motivated
   this item unchanged; (b) add `string scan_id` to `message FailedJob` in
   `indexing.proto`; (c) pass it through `RemoteKnowledgeClient.java:1219` / `:1241`; (d) add the
   component to `IndexingService.FailedJobInfo` (`IndexingService.java:298-304`) and read it in
   `IndexingController.toJobView`, replacing `SCAN_ID_NOT_PLUMBED`. Until then the by-prefix and
   substrate surfaces cannot distinguish "no scan" from "not asked". Owner: whoever next touches the
   worker's failed-jobs read path.

## Live product validation (2026-09-02)

Two independent reviewers ran a live product-validation campaign against this lane's items:

- **V2** — wire contract PASS: both endpoints schema-valid, and the failed-jobs drawer renders
  degrade-free.
- **V3** — the Security tab opens live, and the `security` ui-shot step in live mode does NOT
  time out.
- Follow-ups routed to PR #616 (not fixed here): the drawer's 22 px column, the chip flicker, and
  the collection field.

Related PRs: #616, #617.
