---
title: "Frontend Rewrite — Indirect Backend Impact (where the rework landed)"
status: done
created: 2026-05-30
updated: 2026-05-31
related: [375, 421, 519, 501, 502, 550, 491, 549, 487, 518, 548, 543, 524, 516]
audience: maintainer
---

# 563. Frontend Rewrite — Indirect Backend Impact

## The question this answers

A month of frontend work (React → Lit web components, the
`421 FE-rewrite draft` plan) is complete; the app works and FE
features are being actively added. The ask: **during that month of FE work, what
were the most common points of rewrite / the most worked-on *indirect* areas of the
backend?** This is a historical question about where the FE effort drove backend
churn — not a feature audit.

> **Refined question (the operative one): backend friction for *ongoing* FE
> development.** §1–§8 answer the historical question; **§9 is the friction map** —
> where the backend slows down adding the next FE feature, quantified. If you're
> reading for "what makes FE work harder than it should be," go to §9.

## Method (and a correction)

> **Method note.** An earlier cut of this doc answered a *different* question — the
> *current* landed/deferred state of each substrate — by mining the tempdoc corpus
> flat. That was both a wrong method (tempdocs are an append-only design stream;
> early "blockers" are routinely closed later — verify against `main`, weight
> recency) and a wrong question (current-state ≠ "what got worked on"). This version
> answers the historical question with the right ground truth: **git history.** What
> got *worked on* is what *churned*. The current-state map is kept as supporting
> context (§6), not the headline.

- **Window:** 2026-05-05 (first Lit `shell-v0` commit) → 2026-05-31. ~1,900 commits.
- **Indirect backend** = all `modules/**` Java + `contracts/` + `SSOT/` +
  `governance/`, **excluding** `modules/ui-web` and `modules/shell` (those *are* the
  FE/desktop, not indirect).
- **Metrics:** commit-recurrence per module (how often an area was returned to =
  "points of rework"); file-level touch counts (the sharpest "point of rewrite"
  signal); an early/late split (recency — how focus shifted); and commit-prefix
  attribution (was the churn FE-driven?).

---

## 1. Headline answer — the ranking

**Backend modules by commit-recurrence in the window** (commits touching each):

| Rank | Module | Commits | What it is / why the FE work kept returning to it |
|---|---|---|---|
| 1 | **app-services** | **372** | The Head's registry/dispatch/composition hub: OperationCatalog, OperationExecutor, intent router, conversation engine, the composition root. Every new surface, operation, intent, or capability landed here. |
| 2 | **ui** | **293** | The Head's REST surface: Javalin routes, controllers, SSE streams. Every FE surface needs an endpoint registered. |
| 3 | **app-api** | **117** | Wire records (`@RecordBuilder`) + i18n message catalogs. Every new/changed wire shape + label. |
| 4 | **app-observability** | **102** | Backend-owned truth surfaces: HealthEvent, metrics/TIMESERIES, advisory, SSE envelope, surface/diagnostic catalogs. |
| 5 | **app-agent-api** | **74** | Registry primitives: Resource/Operation/Prompt, ConversationShape, Intent, RequiredCapability, ShellAddress. |
| 6 | **worker-services** | **50** | Worker-side wire/search shaping (SearchResponseBuilder, SearchTrace projection) + job-queue stream. |
| 7 | **app-agent** | **45** | Agent loop: intent routing, recovery policies, step runner. |
| 8 | **app-inference** | **26** | Inference lifecycle (TransitionRunner, InferenceLifecycleManager). |
| — | SSOT (41), ipc-common (19), indexer-worker (16), contracts (16), app-launcher (18) | | Schemas, proto, cross-process plumbing — lighter but recurrent. |

**The two dominant points of rewrite are not modules — they are two files**, each
touched far more than anything else in the entire backend:

| File | Touches | Why it was *the* recurring point of rewrite |
|---|---|---|
| **`LocalApiServer.java`** | **133** | The single Javalin route-registration hub. Every FE surface = a route + (usually) an SSE stream registered here. It is the structural chokepoint where "the FE needs to reach X" becomes backend code. |
| **`AppFacadeBootstrap.java`** → `HeadAssembly` | **113** (+18 HeadAssembly) | The Head composition root. Every new service, handler, catalog, or substrate had to be *wired* here. It grew so hot it was itself rewritten — decomposed into `HeadAssembly` + ~30 phase classes (slice 524); the decomposition is part of the churn. |

So the blunt answer to "most worked-on indirect area": **the Head process's two
structural chokepoints — its API surface (`LocalApiServer`) and its composition root
(`AppFacadeBootstrap`/`HeadAssembly`).** Every FE surface forced a route
registration *and* a wiring, so those two files absorbed FE pressure that
originated everywhere.

---

## 2. The points of rewrite — file level

Top recurring backend files (touch counts, window), grouped by what they represent:

- **Head API surface & composition** — `LocalApiServer.java` (133),
  `AppFacadeBootstrap.java` (113), `HeadlessApp.java` (33), `HeadAssembly.java` (18),
  `OperationSubstrateInit.java` (14), `AiRoutes.java` (13), `OperationsController.java`
  (13), `StatusLifecycleHandler.java` (13), `RuntimeManifestPublisher.java` (12),
  `KnowledgeSearchController.java` (12).
- **Registry catalogs & dispatch** — `OperationExecutorImpl.java` (27),
  `CoreSurfaceCatalog.java` (26), `CoreOperationCatalog.java` (25),
  `registry-operation.en.properties` (17), `resource.v1.json` schema (15),
  `registry-surface.en.properties` (14), `governance/registry.v1.json` (14).
- **Head↔Worker & search wire** — `KnowledgeHttpApiAdapter.java` (33),
  `SearchResponseBuilder.java` (17, worker-side), `IndexingLoop.java` (16).
- **Agent & inference lifecycle** — `AgentLoopService.java` (22),
  `TransitionRunner.java` (13), `InferenceLifecycleManager.java` (13).
- **Tests that moved in lockstep** — `ValidatorRunnerTest` (20),
  `CoreOperationCatalogTest` (17), `OperationExecutorImplTest` (17),
  `RegistryControllerTest` (18), `CoreSurfaceCatalogTest` (17) — each catalog/dispatch
  rewrite dragged its test with it.

The shape is consistent: a **catalog entry** (operation/surface/resource) + a **route**
in `LocalApiServer` + a **wiring** in the composition root + an **i18n label** +
a **schema/validator** — repeated per surface. That five-touch footprint is the
"point of rewrite" unit, and it recurred dozens of times.

---

## 3. The arc across the month (recency dimension)

Splitting the window shows the focus *moved*, which matters for "what's current":

| Module | Early (May 5–17) | Late (May 18–31) | Reading |
|---|---|---|---|
| app-services | 170 | 202 | sustained hub activity throughout |
| ui | 142 | 151 | sustained route/surface activity |
| app-observability | **75** | **27** | front-loaded then **stabilized** — substrate built early |
| app-api | 61 | 56 | steady wire-record work |
| app-agent-api | 49 | 25 | primitive vocabulary mostly settled early |
| worker-services | **5** | **45** | **rose late** — cross-process / SearchTrace / wire-hardening |
| app-inference | **5** | **21** | **rose late** — inference lifecycle (518) is a late theme |
| app-agent | 12 | 33 | agent lifecycle (550) intensified late |

**Driving tempdocs, early vs late:**
- **Early (substrate-building):** 421 (kernel), 491 (conversation), 502 (capability
  gating), 447 (primitive substrate), 487 (intent), 486 (consumer features), 499
  (recovery), 493 (citations), 495/497 (agent/chat surfaces).
- **Late (lifecycle / composition / wire-hardening):** 543 (agent prior-art +
  reviews), 550 (action lifecycle), 519 (head composition graph), 548 (merge-to-main
  correct design), 549 (search trace unification), 501 (runtime manifest), 557/559
  (presentation authority), 518 (inference lifecycle), 511 (aggregate surfacing).

The arc: **build the FE-facing substrate first (resources, operations, intent,
conversation, observability — concentrated in app-services/ui/observability), then
harden lifecycle, composition, search-trace and wire/presentation authority (pulling
in worker-services and app-inference).** Observability's early-peak-then-drop is the
clearest "built and stabilized" signal; worker-services' and inference's late rise is
where the most *recent* rework sits — and therefore the least settled.

---

## 4. The recurring *kinds* of rewrite (grounded in the churn)

Five patterns explain the file-level hotspots above:

1. **Per-surface five-touch footprint** (catalog + route + wiring + label + schema) —
   the dominant churn driver; explains `LocalApiServer` / composition-root / catalog
   co-movement.
2. **Spec-vs-implementation gaps** — kernel docs declared fields the Java lacked;
   closing them was prerequisite to FE features (drives app-api + app-agent-api).
3. **Representation drift / multiple authorities for one truth** — 4 search reps →
   `SearchTrace` (549); proto↔record drift; scattered action ledgers → `ActionEvent`
   (550). Drives the late worker-services + app-observability + app-api churn.
4. **FE renders truth → truth must be backend-declared** — health derivation, error
   catalogs, labels, audience gating, recovery actions moved backend-side.
5. **Composition-root pressure** — every wiring lands in one file until it's too hot,
   then it's decomposed (524: `AppFacadeBootstrap` → `HeadAssembly`). The hotspot
   *causes* its own refactor.

## 5. Attribution — was this FE-driven?

Overwhelmingly yes. Commit-prefix histogram in the window is dominated by the
FE-rewrite lineage: `421` (91 commits), `519` (57), `550` (44), `502` (39), `501`
(23), `491` (34), `549` (34), `543` (56), `548` (28), `518` (15), `487` (13), `559`
(13), `507` (13), `486` (13). Generic backend-internal prefixes are a small
minority. The backend churn of this month was almost entirely a *consequence* of the
FE rewrite — which is exactly the "indirect impact" the question asked about.

---

## 6. Current state of the hot areas (supporting context)

Code-verified on `main` (2026-05-31), condensed — the hot areas are now mostly
**landed and production-wired**, so the churn was convergent, not thrash:

- **Landed & wired:** the per-surface substrate (Resource Category, Surface Manifest,
  DiagnosticChannel, TIMESERIES metrics, advisory projectors), Operation substrate +
  real handlers + capability gating, intent substrate + trust lattice
  (`/api/intent/stream`), conversation substrate + branching + selection + recovery,
  MCP `/mcp`, SSE envelope, `SearchTrace` unification, runtime manifest, the
  `HeadAssembly` composition substrate (`AppFacadeBootstrap` no longer exists), the
  wire-contract substrate + governance gate, citations backend, 6 presentation/a11y
  CI gates. The job-queue Worker→Head **stream** (`SubscribeIndexingJobs`) shipped.
- **Open residue (small):** (a) the wire **record↔proto parity gate** covers only
  knowledge/SearchTrace — `operation_history`/`status` are ungated and
  `OperationHistoryEntry.provenance` is a live drift (logged to `observations.md`);
  (b) ingestion ledger is unary-poll, no stream; (c) slice-489 URL emitters are
  orphaned (test-only callers).
- **Late/least-settled** (per §3, the late-rising areas warrant the most scrutiny
  before the prototype): worker-services search-trace projection and `app-inference`
  lifecycle.
- **Design-only futures (not blockers):** multi-corpus (535), multi-model routing
  (536), workflow/LANGUAGE_WORKFLOW (534/437), WorkerAssembly cross-process trace
  (546).

(Full per-artifact `file:line` verdicts available from the verification sweep; see
Appendix B.)

## 7. Connection to tempdoc 375 (original prototype blockers)

375's blockers were a *different class* (packaging + CPU inference) than the FE month
(truth/contract/observability) and the git data confirms the FE month barely touched
them: `adapters-lucene` 7 commits, `ai-backend` 6, `ort-common` 1, `gpu-bridge` 1.
Status: **G27** (worker-lib spawn) fixed; **G29** (CPU FP16) mitigated via the
FP32-on-CPU variant path but **unconfirmed on a live no-GPU machine** — the one 375
item that still warrants a real check before the prototype.

---

## 8. Deep-dive on the top 3 (structure, debt, prototype notes)

Code- + git-verified on `main`, 2026-05-31. The headline: **the churn in all three
was convergent (re-composition + substrate-hardening), not feature thrash** — but
each carries a specific structural debt a prototype will hit.

### 8.1 app-services (#1, 372 commits) — the hub

- **Churn nature:** overwhelmingly *structural re-composition* (tempdoc 519, 73
  commits) + *trust/security substrate* (550, 36; 502, 21; 487, 13). Convergent, not
  thrash. Sub-areas hottest: `bootstrap/phases/` (120), `worker/` (107), `conversation/`
  (~156 combined), `registry/operations/handlers/` (97), `intent/` (62).
- **Composition root — correction to earlier wording:** `AppFacadeBootstrap` was
  **renamed → `HeadAssembly`** (519 §31 Phase 5, `063a08271`), not deleted; the
  113-touch count is mostly pre-rename history of the same file. `HeadAssembly.java`
  (916 LOC) is a **clean 5-phase sequencer** (Infra→Capability→Service→Substrate→
  Orchestration), typed `Output` records, `tracedPhase()` + OTel + `BootTrace`. The
  28 `bootstrap/phases/` files are ≤272 LOC each. This is mature; build on it.
- **Dispatch core is overloaded:** `OperationExecutorImpl.java` (678 LOC) bundles **7
  concerns** — trust lattice, consent capsules/durable grants, provenance integrity,
  capability gating, input-schema validation, operation-history emission, advisory
  emission — behind **9 telescoping constructors** (every collaborator nullable "in
  legacy/test wiring"). Dispatch ordering (provenance→lattice→capability→schema→
  handler) *is* the security boundary; reordering silently changes semantics. This is
  the module's clearest structural debt and at its scaling limit.
- **Registry:** 34 operations (29 `CoreOperationCatalog` — 1150 LOC, **on
  class-size exceptions** — + 5 `AgentToolsOperationCatalog`), 35 handlers, 11
  test-time shape validators.
- **SPOFs / fragility:** `HeadAssembly` (one eager boot path — any phase throw fails
  Head boot); `OperationExecutorImpl` (one dispatch chokepoint — add no side-channel
  dispatch); `IntentGateEvaluator` (deliberately a *single shared instance* between
  enforcement + preview — don't duplicate); worker late-bind `Supplier` seam (read the
  client eagerly → NPE at boot). Recently *relocated* services (`ai/install` 1044,
  `ai/runtime` 1096, `KnowledgeSearchEngine` 917) are large, exception-listed, and only
  weeks old in this module — least settled.

### 8.2 ui (#2, 293 commits) — the route hub

- **`LocalApiServer.java` = 2,415 LOC**, the single largest backend class, **on
  class-size exceptions** (pinned 2026-05-27 with an explicit "re-measure to
  rebalance" = *deferred debt*). 148 in-window commits, net **+1,180 lines**. 97
  instance fields, **52 controller-typed**.
- **Half-finished extraction is the confirmed churn driver:** 10 delegated
  `api/routes/*Routes.java` classes coexist with **55 inline route registrations** in
  one `setupRoutes()` method. The Routes pattern exists but every May surface was
  bolted on *inline*. `api/` churned 447× vs `api/routes/` 29× (~15:1). A maintainer
  cannot predict where a given endpoint is registered.
- **SSE: writer clean, registration not.** `SseEnvelopeWriter.attach()` is uniform
  (14 controllers, ~5 LOC each) — but all 14 `app.sse(...)` are inline in
  `setupRoutes()`, and a **second legacy `SseWriter`** still backs
  `AgentController`/`ChatController`. Two idioms coexist.
- **Biggest risk = merge-conflict magnet.** Every new surface must edit the same
  ~260-line `setupRoutes()` *and* the same 52-field controller block. With 3–4
  parallel agents, guaranteed conflicts on one 2,415-LOC file. **This is the single
  most important prototype-readiness finding.**
- **Other traps:** two-phase lifecycle (`lateBindKnowledgeServer()` re-registers
  Worker-dependent routes after async boot; `KnowledgeSearchController` may be null at
  `setupRoutes()` time); `if (xController != null)` guard sprawl (~25 inline routes);
  loopback safety is *comment-enforced per endpoint*, not code-enforced (admin reload
  routes rely on the 127.0.0.1 bind). 3 class-size exceptions total (LocalApiServer
  2415, HeadlessApp 1200, StatusLifecycleHandler 1104); 0 inline TODO markers.

### 8.3 app-api (#3, 117 commits) — the contract surface

- **Framing correction:** the central registry records (`Operation`/`Resource`/
  `Prompt`) and the `@JsonValue` value types (`OperationRef`/`I18nKey`) live in
  **app-agent-api**, *not* app-api. app-api owns the **i18n catalogs**, the
  **schema-gen tests**, and the **knowledge/status/runtime wire records**. This
  records-here / labels+schema-gen-there split *is* the churn root.
- **i18n churn driver confirmed + enforced:** every new operation/surface must append
  `label`/`description`(/`confirm`) keys to `registry-operation.en.properties` (33
  ops) / `registry-surface.en.properties` (14 surfaces) — **enforced at ERROR by
  `I18nKeyValidator`** (in app-services). The "label leg" of the five-touch
  footprint; a forced two-module edit per operation.
- **Headline record rework = 549 unified `SearchTrace`** (collapsed 15 flat fields +
  `pipelineExecution` + `introspection` + per-hit `debugScores`/`HitProvenance` into
  one record). `knowledge/` + `selection/` are the hottest record packages;
  `StatusResponse` itself had **0 in-window commits** — its *test* churned (12).
- **Schema drift is loud; proto drift is silent.** Two byte-equality `updateSchemas`
  baselines guard record↔schema. But record↔proto conformance exists **only for
  knowledge/SearchTrace** (`KnowledgeWireContractConformanceTest`); `StatusResponse` +
  `OperationHistoryEntry` have **no proto-parity guard** — adding a field without
  mirroring the proto is silently undetected. (Reconfirms §3 / observations.md item.)
- **Fragility:** `StatusResponse` (29 fields, **positional constructor, not
  @RecordBuilder**) is the highest-friction record to extend; the FE Zod schemas use
  `.loose()` so a forgotten Zod field passes silently; **10 modules depend on
  app-api**, so a record field change ripples record→schema→proto(knowledge only)→
  `wire-types.ts`→Zod→TS→FE tests.

### 8.4 Cross-cutting prototype-readiness takeaways

1. **`LocalApiServer` is the #1 thing to fix before parallel prototype work** —
   finish the `*Routes` extraction (move the 55 inline routes + 14 inline SSE
   registrations out) and add a controller registry/factory, or every surface
   serializes on one 2,415-LOC file.
2. **A new operation/surface is inherently a multi-file, multi-module edit:** catalog
   entry (app-services) + handler + capability decl + route+SSE (`LocalApiServer`) +
   i18n keys (app-api, ERROR-enforced) + schema regen (two `updateSchemas` tasks).
   Budget the whole footprint; it's the recurring unit.
3. **Don't add side-channel dispatch** — everything must flow through
   `OperationExecutorImpl` or it skips the trust gate, capability check, and audit
   ledger. But mind its constructor sprawl (prefer a context object over a 10th
   constructor).
4. **The one real correctness gap to close:** extend record↔proto parity beyond
   knowledge to `status` + `operation_history` (the `OperationHistoryEntry.provenance`
   drift is live).

## 9. Friction map for ongoing FE development (the operative answer)

Quantified from real commits + FE-side code reading (2026-05-31). **Good news
first:** the FE is **fully migrated to Lit** — zero `.tsx`, zero React imports, no
React dependency (`modules/ui-web/package.json`). Coexistence is *not* a friction
anymore (only stale "stays React" comments remain). The friction is elsewhere.

### 9.1 The cost of adding a feature, by class (measured)

| Feature class | Example commit | Code files | Modules | Backend hops | Always-required legs |
|---|---|---|---|---|---|
| **FE-only** (browser-only) | G35 `f062da513`, G36 `07e9492f9` | ~7–8 | **1** (ui-web) | **0** | FE consumer + FE test |
| **Surface reusing an existing Resource** | F15 `751d9a519` | 7 | 2 | 1 | catalog + i18n + FE + test |
| **Net-new interactive surface** | unified-chat `4d8767873` | 11 | 3 | 2 | catalog + route + wiring + FE + test |
| **Backend operation (handler)** | rebuild-index `08cee3b9f`, navigate `b5f6bdeb1` | 8 | 2 | 1 | i18n + catalog + handler + bootstrap wiring + 2 registry tests |
| **Backend Resource + emitter + stream** | advisory `4dea3c856` | 13 | 3 | 2–3 | schema (**+ dual classpath copy**) + wire record + catalog + executor wiring + route + stream controller + test |

**The fixed backend tax.** The moment a feature must be *registered* (a catalog
entry) or *served* (a route/Resource), the footprint roughly doubles from the
FE-only floor (~7 files / 1 module / 0 hops) to **8–13 files across 2–3 modules**,
and a fixed quartet always appears:

- **catalog entry** (`CoreOperationCatalog` / `CoreSurfaceCatalog`) +
- **its count-assertion test** (`*CatalogTest` numeric bump — a brittle leg: every
  addition edits a hard-coded count) +
- **i18n label** in `app-api/messages/*.properties` (**ERROR-enforced** by
  `I18nKeyValidator` — a forced second-module edit) +
- **bootstrap wiring** in the composition root (`HeadAssembly`/handler registration).

For an operation that quartet was **100% consistent** across samples (identical
8-file shape). This is the concrete, repeatable backend friction the team pays for
every non-trivial FE-facing capability.

### 9.2 The FE↔backend contract boundary — the biggest friction (HIGH)

The FE built the *right* "declare-once-on-backend, project-on-FE" primitive — the
**registry catalog clients** (`ResourceCatalogClient`, `SurfaceCatalogClient`,
`OperationCatalogClient`, …, all ETag-cached fetches of `/api/registry/*`). Adding a
backend-declared Resource/Surface shows up on the FE *without FE code*. That's the
bright spot. **But it only covers catalog *membership*; every contract *shape* is
still hand-synced**, and that's where the friction concentrates:

1. **Four parallel wire-type sources, no single authority (HIGH).** `wire-types.ts`
   (typescript-generator output, **frozen — no longer regenerated**), `*_pb.ts`
   (protobuf-es, the "new" path), hand-written Zod (`schemas.ts`), and hand-written
   domain types (`api/domains/*`). `HealthEvent` is defined **4×**; `Category` **2×**.
   The generated barrel labels itself **"TRANSITIONAL"** — the migration stalled. An
   FE dev adding a feature must pick which of four sources to import, and the answer
   differs per existing consumer.
2. **Zod `.loose()` + dev-only fail-open = silent drift (HIGH).** 66 `.loose()`, 0
   `.strict()`; `validateWithFallback` only warns `if (IS_DEV)` then returns data
   as-is. A renamed/removed backend field **passes FE validation silently in prod**
   and the UI degrades quietly. `SearchTraceSchema = z.object({}).loose()` enforces
   nothing.
3. **Hardcoded contract values break at runtime, not compile time (HIGH).** ~70
   `/api/...` endpoint string literals with no central registry; Java enums
   hand-mirrored in TS (a real shipped bug: `AuditPolicy` once had `'FULL'` that
   silently disagreed with the wire); Lucene field names hardcoded in the search
   mapper (a `fields.v1.json` rename silently yields `undefined`).
4. **Hand-written per-domain mappers (MED).** Every domain hand-renames snake→camel
   field-by-field; adding one backend field = edit mapper + domain type + Zod schema
   + (maybe) a generated alias.
5. **bigint→number boundary tax (MED).** protobuf-es emits `int64`→`bigint`; any
   `_pb` consumer must route through `wireProjection.ts` or hit type errors.
6. **No record↔proto parity gate beyond knowledge/SearchTrace (MED, also §3).**
   `status` + `operation_history` can drift silently; `OperationHistoryEntry.provenance`
   already has.

### 9.3 Where the leverage is (remediation directions, by impact)

Ranked by how much each would reduce *ongoing* FE-development friction:

1. **Collapse the FE wire-type layer to one generated source** (finish the
   protobuf-es migration; delete `wire-types.ts` + the hand Zod + hand domain types,
   or invert so they're generated). Kills friction #9.2.1, #9.2.4, and most of #9.2.2.
   Highest leverage — it's the stalled-migration tax paid on every feature.
2. **Make the per-surface "fixed quartet" declarative.** A single registration
   (catalog entry that *generates* its i18n key requirement, count, and wiring)
   instead of four hand-edited files + a brittle count-test. Removes the recurring
   backend tax in §9.1.
3. **Finish the `LocalApiServer` route extraction** (§8.2) — the route leg of the
   tax lands in one 2,415-LOC conflict-magnet; per-domain `*Routes` classes make
   parallel FE-feature work non-conflicting.
4. **A central FE endpoint + enum registry** generated from the backend — kills the
   hardcoded-literal runtime-break class (#9.2.3).
5. **Extend record↔proto parity + tighten Zod** (close #9.2.6 and make #9.2.2 loud).

The throughline: the backend already has the *right idea* (declare-once catalogs);
the friction is that it's **half-applied** — membership is declarative, but shape,
labels, routes, and FE types are still hand-synced across many files and modules.
Finishing what's started (FE type generation, declarative surface registration,
route extraction) would cut the per-feature footprint substantially.

## Appendix A. Raw git data (window 2026-05-05 → 2026-05-31)

- Module commit-recurrence: app-services 372, ui 293, app-api 117, app-observability
  102, app-agent-api 74, worker-services 50, app-agent 45, SSOT 41, app-inference 26,
  ipc-common 19, app-launcher 18, indexer-worker 16, contracts 16.
- Top files: LocalApiServer 133, AppFacadeBootstrap 113, HeadlessApp 33,
  KnowledgeHttpApiAdapter 33, OperationExecutorImpl 27, CoreSurfaceCatalog 26,
  CoreOperationCatalog 25, AgentLoopService 22, HeadAssembly 18,
  SearchResponseBuilder 17.
- Early/late split: see §3.

## Appendix B. Design-history & method note (LAGGING)

The original §2–§6 of this doc was a corpus-mined "current-state map" with a
ranking-by-tempdoc-mention that conflated proposed/deferred/landed. It has been
superseded by this git-grounded historical analysis; the verified current-state
content is condensed into §6. The transferable lesson: tempdocs are a lagging
indicator and answer a different question than git history — for "what was worked
on," measure churn; for "what is true now," read `main`; never read the corpus flat
as if uniformly current. (This is the basis for a proposed CLAUDE.md addition on
reading the tempdoc corpus, pending user decision.)
