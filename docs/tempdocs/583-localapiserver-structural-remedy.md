---
title: "583 — LocalApiServer structural remedy: the repo's single largest class-size bump offender (2596 LOC, 37 class-size changesets — >half of all of them). Charter to determine and execute the RIGHT structural fix — decomposition, rewrite, declarative re-expression, or other — NOT a decomposition mandate. The goal is to end the pin treadmill at its dominant source; the SHAPE of the fix is the investigation's output, not its premise."
type: tempdocs
status: COMPLETE — charter executed + live-smoked + durability follow-up (§B.10 2596→997; §B.11 997→954 LOC, pin deleted); §D extensions shipped + browser-validated (spine P1–P3, full tail P4–P7, §D.8 fidelity follow-ups); §C independent review done (ship-with-nits, its one defect fixed). No residual.
created: 2026-06-15
updated: 2026-06-15
origin: tempdoc 582 §B.5 R2 (governance-critique — the class-size treadmill is concentrated here)
---

# 583 — LocalApiServer: end the pin treadmill at its dominant source

> **Purpose, stated carefully.** This is NOT "decompose LocalApiServer." It is: *this file is the
> single largest recurring class-size pin-bump offender in the repo; determine the correct
> structural remedy and execute it.* The remedy may be decomposition (per-domain route classes), a
> **rewrite** (declarative route table / generated registration), a re-expression of the
> registration pattern, or a combination. **Which remedy is right is the first deliverable**, not an
> assumption. Do not pre-commit to "split it into N files" before the investigation justifies that
> over the alternatives.

## 1. Why this file (the evidence)

- **2596 LOC**, pinned in `gradle/class-size-exceptions.txt` (highest pin in the repo).
- **37 of the 64 class-size changesets** name this file (per 582 §B.1 forensic) — more than half of
  *all* class-size churn across the codebase. It is a merge magnet: 501, 526, 541, 542 each
  re-pinned it.
- **82 Javalin route registrations** spanning at least six unrelated concerns in one class:
  i18n message bundles (`/messages/*.properties`), knowledge (`/api/knowledge/*`), indexing
  (`/api/indexing/*`), chat/agent (`/api/chat/agent`), governance (`/api/governance/state`),
  MCP (`/mcp`, `/api/mcp/token`), and an OpenAI-compat shim (`/v1/chat/completions`, `/v1/models`).
- Every new endpoint grows it, so every feature slice that adds a route pays the pin-bump tax here.

## 2. What to find out before choosing a remedy (§B-style spec-tightening)

1. **What actually lives in the 2596 lines** — route wiring vs handler bodies vs i18n/static-asset
   plumbing vs lifecycle/bind logic. A line-budget breakdown by concern. (If most lines are handler
   bodies, decomposition differs from the case where most lines are repetitive registration.)
2. **The existing pattern the repo already endorses.** `common-workflows.md` (Add a REST endpoint)
   says routes are *"registered through the relevant `…/api/routes/*Routes.java` class, then wired
   from `LocalApiServer`."* So a per-domain `*Routes` seam **already exists** — is LocalApiServer
   bloated because routes were added inline instead of via that seam? If so the remedy is
   *migration to an existing pattern*, not a new invention. Verify against source.
3. **The loopback/​binding invariant.** Hard invariant #2 (`loopback-only-network`, 127.0.0.1)
   lives here and is guarded by `UiApiGuardrailsTest` (archunit). Any remedy must keep the bind
   policy in one provable place — confirm the guardrail test still pins it after the change.
4. **Parallel-agent collision risk.** This file is a known merge magnet; a large rewrite collides
   with concurrent worktrees (the `stay-focused`/keep-diffs-scoped rule). Assess timing and whether
   the remedy can land as a sequence of small, merge-safe moves rather than one big-bang rewrite.

## 3. Candidate remedies (the investigation picks; none is pre-ordained)

- **A — Migrate inline routes to per-domain `*Routes` classes** (decomposition along the existing
  seam). Lowest-novelty; aligns with the documented pattern. LocalApiServer becomes a thin
  composer that instantiates and wires route classes.
- **B — Declarative route table / rewrite.** Replace 82 imperative `.get/.post(...)` calls with a
  data-driven registration (a table of {method, path, handler, policy}) iterated at bind time.
  Shrinks the class structurally and makes the route surface auditable. A rewrite, not a split.
- **C — Hybrid.** Declarative table for the registration spine + handler bodies moved to their
  domain controllers (several already exist: `AgentController`, `KnowledgeSearchController`).
- **D — Something the breakdown in §2.1 reveals** (e.g., the i18n `/messages/*.properties` serving
  is a self-contained block that lifts out cleanly regardless of the route decision).

**Selection criteria:** end the recurring bump (file ≤ ceiling or pin row deleted), preserve the
loopback invariant + all route behavior, minimize merge-collision blast radius, and prefer reusing
an existing repo pattern over inventing one (`explore-before-implementing`).

## 4. Constraints & invariants (do not violate)

- **Loopback-only (Inv #2)** — the 127.0.0.1 bind stays single-authority; `UiApiGuardrailsTest`
  must still pass and still pin it.
- **No legacy endpoints (Inv #3)** — do not resurrect `/api/search` / `/api/settings`; the
  `docsApiDriftCheck` task guards this.
- **Head never touches Lucene (Inv #1)** — unaffected, but any moved handler keeps delegating to
  the worker client.
- **Behavior-preserving** — this is a structural remedy; no endpoint contract changes. Every one of
  the 82 routes resolves identically after the change.

## 5. Verification plan

- `wc -l` the result ≤ 1000 (delete the pin row) OR a justified new pin with a recorded rationale if
  full remedy is staged.
- `./gradlew.bat :modules:ui:test` green (incl. `UiApiGuardrailsTest`).
- Live-stack smoke: every route family still responds (`/api/status`, `/api/knowledge/*`,
  `/api/chat/agent`, `/mcp`, `/v1/chat/completions`) — per `verify-your-work` use the dev stack, not
  just unit tests.
- `./gradlew.bat build -x test` from main before merge (pre-merge gate).

## 6. Success criteria

The class-size pin for `LocalApiServer.java` is deleted (file ≤ ceiling) or materially reduced via a
remedy that the §2 breakdown justified over the alternatives, with no behavior change and the
loopback guardrail intact — ending the dominant source of class-size bump churn identified in 582.

**Met (§B.10).** Pin row deleted; file at 997 LOC (≤ the 1000 ceiling). Remedy = per-domain
collaborator decomposition (the §2.1 breakdown showed the constructor, not routes, was the bulk —
so the §B.7 shape, not Candidates A/B). No behaviour change (construction-site-only moves;
`build -x test` + `:modules:ui:test` green). Loopback guardrail intact (`UiApiGuardrailsTest` /
`LocalApiCorsPolicyTest` green; `resolveAllowedOrigin` single-authority in `ApiSecurityFilters`).
Remaining before merge: independent review (§C) + the live-stack Worker-reconnect smoke (§B.10).

## §B — Findings (populated during execution)

> Investigation pass 1 (2026-06-15, agent). Source-verbatim against `main` HEAD
> `552467576`. The headline below **corrects the charter's framing**: the route surface is
> *not* the dominant cost — the constructor's controller/engine assembly is. The charter
> invited questioning its assumptions (§3 "none is pre-ordained"); this is that.

### §B.1 — Line-budget breakdown by concern (the §2.1 deliverable)

`wc -l` = **2596**. Measured region budget (`python` over the method boundaries; see
the per-region script in the investigation log):

| LOC | % | Region |
|----:|---:|---|
| 1001 | **38.6%** | **Constructor `LocalApiServer(Builder b)`** (lines 255–1255) — the bulk |
| · 281 | 10.8% | ↳ `if (HeadAssembly != null)` substrate-Resource controller cohort (registry/resource/metric/advisory/history/authorization) + the 30-line `else` null-init mirror |
| · 284 | 10.9% | ↳ doc/preview/debug/status/ai/policy/diagnostics controller cohort |
| · 277 | 10.7% | ↳ conversation-engine + SPI-registry assembly (promptContributors, streamConsumers, contextInjectors, iterationControllers, `ConversationEngine`, agent/chat controllers, MCP handler) |
| · 51 | 2.0% | ↳ `MessageCatalogController` block (9 identical-shape i18n catalogs — execution found 9, not the charter's "11") |
| · 48 | 1.8% | ↳ server bind/start + ephemeral-port fallback |
| 299 | 11.5% | security plumbing — `setupCors` / `setupSessionTokenEnforcement` / `setupCapabilityGates` / `maybeRecord*Deny` / `maybeCaptureSlowRequestDump` / `resolveAllowedOrigin` |
| 297 | 11.4% | **`setupRoutes`** — the route registration spine |
| 254 | 9.8% | lifecycle — `getPort` / `lateBindKnowledgeServer` / `stop` / token accessors |
| 196 | 7.6% | field/constant declarations (≈50 controller fields, heavily comment-annotated) |
| 178 | 6.9% | `buildAndStartApp` |
| 141 | 5.4% | `Builder` inner class |
| 108 | 4.2% | misc handlers (`handleMcpToken`/`handleDebugDashboard`/`handleResetIndex`/`handleAdmin*Reload`) |

**The decisive finding:** *most lines are controller-construction/assembly wiring in the
constructor, not route registration and not handler bodies.* Per §2.1's own conditional —
"if most lines are handler bodies, decomposition differs from the case where most lines are
repetitive registration" — the truth is a **third** case the charter didn't enumerate: most
lines are **dependency-injection / composition-root wiring**.

### §B.2 — Consequence: the charter's framing over-weights routes; A & B are insufficient alone

The charter's evidence sentence ("82 Javalin route registrations … in one class") frames the
problem as the route surface. Measured reality:

- **`setupRoutes` is 11.4%** of the file, and it is *already substantially delegated*: 11
  `*Routes.register(...)` calls fan out to per-domain classes (`AiRoutes` 25 routes,
  `AgentRoutes` 24, `IndexingRoutes` 19, `DebugRoutes` 15, `StatusRoutes` 13, `InferenceRoutes`
  10, `KnowledgeRoutes` 9, `RuntimeApiRoutes` 7, `InfraRoutes` 2, `BootRoutes` 1, `ScansRoutes`
  1). Of ~196 total head routes, ~126 already live in `*Routes` classes; ~70 remain inline.
- Therefore **Candidate A** (migrate the remaining inline routes to `*Routes`) reclaims **at
  most ~100–150 LOC** and **Candidate B** (declarative route table) addresses the *same*
  ~11% region while *fighting* the already-endorsed `*Routes` seam. **Neither touches the 38.6%
  constructor.** Either one alone leaves the file ≥ ~2300 LOC — still 2.3× the ceiling, pin
  intact, treadmill running.

**Verdict on A/B as stated:** necessary-ish cleanup, not the remedy. The remedy must attack
the constructor.

### §B.3 — What actually drives each pin bump (forensic, corrected)

Reading the 37 LocalApiServer changesets, every feature-slice bump adds the *same triplet*,
and the route line is the *smallest* of the three:

1. a **controller field** decl (+ a tempdoc-citation comment) — fields region;
2. **bootstrap-path construction** in the `if (HeadAssembly != null)` block **+ a null-init
   line in the `else` mirror** — constructor (≈2× the route cost);
3. one **route line** in `setupRoutes`.

Representative: `550-action-ledger-endpoint` (+13 LOC: "an `ActionLedgerController` field, its
bootstrap-path construction …, the null-init on the test-only Builder path, and the `GET
/api/action-ledger` route"); `561-item4-memory-presence-wiring` (+35); `560-ws5-ws7b` (+35 at
"the single production ConversationEngine / RegistryController construction site"). Multiple
changesets explicitly defer the real fix: *"the decomposition obligation for LocalApiServer
continues to fall to tempdoc 519's head-API extraction"* — an obligation that was **named but
never executed** (519 turned out to be the AppFacadeBootstrap→HeadAssembly + ratchet-rule work;
it contains no LocalApiServer route/controller extraction).

**Root cause, stated precisely:** LocalApiServer is simultaneously (a) the **single
composition root** that `new`s ~50 controllers and (b) the **single route registry** for every
domain. Both responsibilities are *shared edit points*, which is exactly why the file is a
merge magnet and a pin treadmill. A remedy that merely *moves* construction into a collaborator
**still called from the constructor** does not end the treadmill — the constructor would still
grow one `new Module(...) + module.register(...)` per feature. **The treadmill ends only if the
per-feature edit lands in a per-domain unit, not in this file.**

### §B.4 — Existing-pattern verification (`explore-before-implementing`)

- **The `*Routes` seam is register-only.** Each `*Routes.register(app, …controllers…)` takes
  *already-constructed* controllers and binds routes (verified in `AiRoutes`, `KnowledgeRoutes`,
  etc.). It does **not** construct controllers. So extending route delegation cannot, by itself,
  relieve the constructor. The seam is real and reusable, but it must be *widened* from
  "register(app, ctrls)" to "construct-from-context **and** register" to attack the bulk.
- **No controller-grouping/assembly type exists** in `modules/ui/src/main/java/` (grep for
  `Controllers`/`ControllerAssembly`/`ApiModule`/`ApiContext`/`HeadControllers` → none). So the
  composition root genuinely is this one constructor.
- **The endorsed decomposition tool is the package-private collaborator extraction** — the
  pattern behind the "eleven prior decompositions" (`SearchOrchestrator` → `services/{input,
  plan,execute,respond}`; `InferenceLifecycleManager` per ADR-0017; `IndexingLoop` collaborator
  cuts; class-size-standard.md). This is decomposition, and it targets the constructor bulk
  directly. **Reuse it; do not invent a DI-by-table framework** (that is Candidate B over-reach).

### §B.5 — Constraints verified against source

- **Loopback (Inv #2):** the 127.0.0.1 bind + `LOOPBACK_HOSTS`/`TAURI_WEBVIEW_HOST` policy
  lives in `setupCors`/`resolveAllowedOrigin`/`buildAndStartApp`. Guarded by
  `UiApiGuardrailsTest`, `LocalApiCorsPolicyTest`, `LocalApiUiTokenPolicyTest`. Any remedy keeps
  this in one place — the cleanest extraction puts the whole security-plumbing region (§B.1,
  299 LOC) behind one `ApiSecurityFilters` collaborator that still binds on the one `app`, so
  the bind policy stays single-authority.
- **No legacy endpoints (Inv #3):** `LegacyEndpointGuardTest` enumerates the real route set via
  Javalin's `InternalRouter` and asserts `/api/search`/`/api/settings` absent. **This is the
  behaviour-preservation oracle for any route move** — snapshot `allHttpHandlers()` before/after
  and assert set-equality. (Currently it only registers the `*Routes` classes; widening it to a
  full before/after route-set diff is part of the verification plan.)
- **Test-null path is load-bearing.** ~8 unit tests construct `LocalApiServer.builder()` with
  **no** `HeadAssembly`; the 30-line `else` block nulls every substrate-Resource controller and
  `setupRoutes` guards each with `if (ctrl != null)`. Any cohort extraction **must preserve this
  null-tolerance** (a module that no-ops when its bootstrap input is absent).
- **Late-bind wrinkle.** `lateBindKnowledgeServer` (Worker reconnect) constructs
  `KnowledgeSearchController` + `ScanProgressController` + `RetrieveContextController` *a second
  time* and calls `ScansRoutes.register`/`KnowledgeRoutes.register` lazily. The knowledge cohort
  thus has **two** construction sites; its module must expose a late-bind entry, not just a boot
  one. (Noted so the extraction doesn't break Worker-reconnect.)

### §B.6 — Collision-risk snapshot (the §2.4 deliverable)

At investigation time all three live worktrees (`576-token-migration`, `580-engine-levers`,
`lambdamart-bootstrap-fix`) hold LocalApiServer **byte-identical to main** — **no concurrent
divergence right now**, so the collision window is currently open. But the file's history says
the window closes often. **Mitigation = stage the remedy as small, independently-mergeable
moves**, each: (i) extracts one cohort to a new file (new files don't merge-conflict), (ii)
leaves a *small, localized* deletion in LocalApiServer, (iii) is independently green +
pin-rebalanced. Big-bang rewrite is rejected on this basis.

### §B.7 — Recommended remedy: **Hybrid (Candidate C), as per-domain construct-and-register modules over a shared `HeadApiContext`, staged**

Not A, not B, not a single big rewrite. The shape:

1. Introduce one new abstraction — a **`HeadApiContext`** record (the Builder's shared inputs +
   the few derived singletons: `apiCatalog`, `eventBuffer`, `gpuCapabilitiesService`,
   `enterprisePolicyService`, `pendingAuthorizationStore`, and the assembled `ConversationEngine`
   / shared stores). Built once, early, in LocalApiServer.
2. Widen the `*Routes` seam into **domain modules** that take `HeadApiContext`, **construct their
   own controllers**, and **register their own routes** — `module.wire(ctx, app)`. LocalApiServer's
   `setupRoutes` becomes a flat list of `module.wire(ctx, app)` calls; the constructor stops
   `new`-ing those controllers and stops declaring their fields.
3. Use the **declarative table only where it genuinely fits** — the 11 `MessageCatalogController`
   instances are identical-shape `{namespace, resourcePath}` rows (Candidate D); collapse them
   into a `MessageCatalogRoutes` driven by a table. This is the one place B's "data-driven" idea
   is correct, because the rows really are homogeneous.
4. Candidate A folds in for free: once a domain module owns construction, it owns its routes too,
   so remaining inline routes migrate as a side-effect of the cohort move.

**Why this ends the treadmill (not just shrinks once):** after the move, a new endpoint is
added to its **domain module** (a small file, far from ceiling) — LocalApiServer is touched
**zero or one** line. The shared edit point dissolves, so both the merge-magnet property and the
pin churn go away at the source. This is the property §B.3 proved is required and that A/B/D
alone do not deliver.

### §B.8 — Staged plan (each stage independently green + merge-safe)

Ordered cheapest/safest-first; LOC are estimates (construction + fields + routes reclaimed):

| Stage | Move | LOC after | Status |
|---|---|---:|---|
| 1 | `MessageCatalogRoutes` (declarative table; 9 catalogs) | 2517 | ✅ done (`843019212`) |
| 2 | `ConversationApiAssembly` (the SPI+engine+agent/chat/MCP block) | 2255 | ✅ done (`531c23e24`) |
| 3 | `ResourceApiModule` (substrate-Resource cohort + routes + SSE shutdown) | 1610 | ✅ done (`eea7f7f3f`) |
| 4 | `ApiSecurityFilters` (cors/token/capability/slow-dump/origin) | 1306 | ✅ done (`dcafcd167`) |
| 5 | `CoreApiAssembly` (preview/debug/status/ai cohort + status wiring) | 1034 | ✅ done (`c21f35727`) |
| 6 | comment-region cleanup + pin row delete | **997** | ✅ done (`ef47cc70a`) |

Outcome matched the plan's shape (the stage names map 1:1; the route-migration of Candidate A
folded into Stage 3's `ResourceApiModule.register`, exactly as predicted). The `HeadApiContext`
abstraction of §B.7.1 was realized pragmatically as **per-cohort assembly inputs** (the Builder +
a handful of suppliers) rather than one shared record — each collaborator takes only what it needs,
which kept the seams narrow and the diffs reviewable. No single shared context object was required.

### §B.9 — Sequencing decision (resolved)

The user chose **drive all six stages now** (target ≤1000, row removed) while the merge window was
open (all three live worktrees held LocalApiServer byte-identical to main at start — §B.6). Done in
one worktree (`worktree-583-localapiserver-remedy`) as six independently-green, merge-safe commits.

### §B.10 — Execution record & verification

**Result: `LocalApiServer.java` 2596 → 997 LOC (−1599, 62%); class-size pin row deleted.** The
2596-LOC god-constructor + route spine became a thin composer over five package-private
collaborators (the repo's proven decomposition pattern, §B.4):

- `routes/MessageCatalogRoutes` — table-driven i18n surface (Candidate D).
- `ConversationApiAssembly` — ConversationEngine + SPI registries + agent/chat/MCP.
- `ResourceApiModule` — substrate-Resource cohort: construct + `register(app)` + `shutdown()`.
- `ApiSecurityFilters` — CORS/token/capability-gate/slow-dump; `resolveAllowedOrigin` single-authority.
- `CoreApiAssembly` — preview/debug/status/ai controllers + status/observability wiring.

**Behaviour preservation.** Every move was construction-site-only; `lateBindKnowledgeServer` (Worker
reconnect) and `stop()` still mutate the same LocalApiServer fields, so those paths are structurally
unchanged. The 4 `resolveAllowedOrigin` test callsites were repointed to `ApiSecurityFilters`;
`SESSION_TOKEN_HEADER` stayed public on LocalApiServer (Tauri/test contract).

**Verification (§5 plan):**
- ✅ `./gradlew.bat build -x test` green from the worktree (all modules compile; `class-size` gate
  passes — row deletion accepted; ui + app-services integration tests run).
- ✅ `:modules:ui:test` green after each stage (incl. `UiApiGuardrailsTest`, `LegacyEndpointGuardTest`,
  `LocalApiCorsPolicyTest`, `LocalApiUiTokenPolicyTest`, `LifecycleContractTest`,
  `StatusLifecycleHandlerTest`, `McpProtocolHandlerTest`, the controller suites).
- ✅ New `MessageCatalogRoutesTest` pins the exact 9-route i18n set via Javalin `InternalRouter`.
- ✅ **Live-stack smoke (done 2026-06-15).** Ran the worktree build live by launching
  `scripts/dev/dev-runner.cjs` *from the worktree* (its `repoRoot` derives from `__dirname`, so it
  built + ran MY code) with `JUSTSEARCH_MODELS_DIR`/`SERVER_EXE`/`SPLADE_MODEL_PATH` pointed at
  main's assets. Backend reached `httpReady` + **`workerReady`** — i.e. the full constructor (all
  five collaborators assembled) **and the `lateBindKnowledgeServer` Worker-connect path** executed
  cleanly. Probed each extracted surface: ResourceApiModule (`/api/registry/operations`,
  `/api/runtime-context`, `/api/action-ledger` → 200; `/api/operations/{id}/preview` → registered),
  MessageCatalogRoutes (`/api/messages/errors|registry-workflow/en` → 200), ConversationApiAssembly
  MCP (`POST /mcp` initialize → 200; `/api/mcp/token` → 200), OpenAiCompatController (`/v1/models` →
  503 registered, Brain offline), CoreApiAssembly (`/api/status`, `/api/health`,
  `/api/ai/runtime/status`, `/api/inference/status` → 200), KnowledgeSearchController via lateBind
  (`/api/knowledge/search` → 200, 4 hits), and the **ApiSecurityFilters capability gate**
  (`POST /api/chat/agent` with worker-up/inference-pending → structured **503**
  `{"unavailable":"inference",…}`). Legacy `POST /api/search` → 404 (absent). **One tier
  unreached:** the live-LLM agent turn — `ai_activate` failed `Variant not installed: cuda12`
  (the fresh worktree data dir has no AI pack installed; an environment artifact, not a code
  defect). The agent/engine plumbing is unit-tested green (`AgentSseContractTest`,
  `AgentControllerShapeDispatchTest`, `McpProtocolHandlerTest`) and the engine assembled without
  error at boot; the capability gate correctly guards `/api/chat/agent` when the Brain is down.

**Out-of-scope finding logged:** the `exception-count` portfolio meta-ratchet is red on the branch
base (live count 55 > ceiling 48), independent of 583 (the class-size row removal is
exception-count-neutral). Recorded in `docs/observations.md`; it is 582's domain (baseline
ratchet-down as 583/584/585 retire exceptions).

## §C — Independent review (DONE — ship-with-nits, defect fixed)

Per `independent-reviewer-required` (slice-execution.md), an independent second agent (reviewer ≠
implementer) reviewed the §D API-self-description work (the largest net-new surface; the core
decomposition §B was already independently live-smoked at merge). **Verdict: ship-with-nits.**

The review confirmed the structural claims hold: behaviour preserved for existing routes; loopback
bind + capability gating untouched; the capability single-authority is genuine (no second table); the
MCP-surface gate was correctly changed to `resourceApiModule != null` (not `!apiModules.isEmpty()`,
which would have gone always-true once `MetaApiModule` became always-present); the field ceiling holds
at 28; `owningModule` self-capture is sound (sequential registration, no cross-attribution); the lazy
`() -> this.apiModules` supplier has no init hazard; test assertions are right-reason.

**One real defect found + fixed:** `RouteResponseSchemas` referenced 3 schema names
(`knowledge-search-response`, `ai-runtime-status-response`, `effective-policy`) that
`SchemaController`'s allowlist did not serve, so the OpenAPI per-route `$ref /api/schemas/<name>`
would 404. Fixed at root — added the three (classpath-synced wire records) to
`SchemaController.SCHEMA_NAMES`; added `RouteResponseSchemasCoverageTest` pinning
`RouteResponseSchemas ⊆ SchemaController.servedNames()` so the two authorities cannot drift again
(audit-driven fix → runnable regression test). Two cosmetic nits also fixed (stale `OpenApiController`
javadoc; a dead `/api/document/` prefix row). The review's harmless observations (e.g. `RouteCohorts`
still lists the removed `/api/settings` as a display prefix — never matched, no resurrection) were left
as-is. The OpenAPI-`$ref`-resolves gap is now closed by construction.

## §B.11 — Follow-up: durable margin (post-merge critical-analysis fix)

A critical-analysis pass on the merged result found the remedy was a *one-time* shrink for the two
biggest cohorts, not a durable treadmill-end: Stages 2 & 5 returned a `Result` that LocalApiServer
**spread back into 25 individual fields**, and the 997-LOC landing had only a 3-LOC margin bought by
Stage-6 comment-trimming (cosmetic). Adding one chat/AI/status controller would re-breach 1000 and
re-pin within 1–2 slices — re-starting the very treadmill §B.7/§6 set out to end.

**Fix (landed):** LocalApiServer now holds each assembly's `Result` as a single field (`core`,
`convApi`) and reads controllers via the record accessors (`core.statusLifecycleHandler()`,
`convApi.agentController()`, …) at the ~30 use-sites in `setupRoutes`/`lateBindKnowledgeServer`/
`stop`. Removed 24 field decls + 24 assignment lines (the 2 `@SuppressWarnings("unused")` catalog
fields included — the Result retains them). Carve-out: `knowledgeSearchController` stays a mutable
field (it is re-created in `lateBindKnowledgeServer`), seeded once from `core.knowledgeSearchController()`.
Also deleted the dead `ResourceApiModule.hasRegistry()`. **LocalApiServer 997 → 954 LOC** (46 under
the ceiling — *structural* margin), so a new controller in either cohort now touches the assembly
only (Result + `assemble`), matching §B.7's "zero or one line." Behaviour-identical (accessor
substitution only). Verified: `:modules:ui:compileJava`/`:test` green, `class-size` gate pass,
live-stack smoke green (workerReady → `lateBind` via the new accessors; `/api/status`, search (4
hits), `/api/chat/agent` 503 gate, `/v1/models`, `POST /mcp`, `/api/registry/operations`, resolve-
address all correct).

## §D — Post-implementation: theoretical extensions & research (2026-06-15, agent, autonomous)

> Research pass commissioned after 583 shipped: "now that the design is implemented, what could we
> theoretically do with it?" Pure ideation — **no code changes**, doc-only. Two rounds: (1) read-only
> codebase inventory of the three systems any extension would touch (API self-description, the plugin
> substrate, the governance/fitness-function kernel); (2) external prior-art validation. No specific
> goal; all ideas are options. App is pre-production with no users — so "new UX" ideas are judged on
> enabling-potential, not immediate user value.

### §D.0 — The unifying insight

583 converted the head's API surface from **imperative wiring** (one god-constructor + one
`setupRoutes` hand-binding ~196 routes) into **structured, per-domain registration** (collaborators
that each own a cohort's construction + routes + lifecycle). The property that unlocks: **the API
surface is now machine-inspectable by construction.** Every worthwhile extension below either
*exploits* that structure (make it visible/usable) or *protects* it (keep it from eroding). In the
literature's terms, 583 is a textbook **Composition Root** refactor (Seemann) — isolate all
object-graph composition into one small place; the canonical guard against re-bloat is
*convention / auto-registration*, which is exactly the `ApiModule`-list end-state (§D.2a).

### §D.1 — Polish & simplify (small, low-risk)

- **Restore the Stage-6 comment trims (polish).** §B.11 gave LocalApiServer a *structural* 46-LOC
  margin, so the Stage-6 historical-citation trims are no longer load-bearing; restoring the useful
  tempdoc citations improves readability at no ceiling cost. Trivial.
- **Do NOT introduce a shared `HeadApiContext` blob (rejected).** §B.7.1 floated one; I used explicit
  per-cohort params. Seemann's caution + AHA both argue against a god-context parameter object — the
  explicit `assemble(...)` params keep each collaborator's true dependencies visible. Leave as-is.

### §D.2 — Extend the pattern

- **D.2a — One `ApiModule` seam + `List<ApiModule>` (simplify + the durable treadmill-kill).** Today
  there are *three* collaborator shapes: `*Routes` (static register-only), `ResourceApiModule`
  (instance construct+register+shutdown), and the `*Assembly` factories (→ `Result`). Unify the
  **route-owning** ones behind one `interface ApiModule { void register(Javalin app); default void
  shutdown() {} }` that `LocalApiServer` iterates; `setupRoutes` becomes a loop, and a new endpoint
  family = a new `ApiModule` in the list, LocalApiServer otherwise untouched (Seemann's
  convention-registration end-state; the *full* realization of §B.7's "touched zero or one line").
  **Honest scope (AHA):** the `*Assembly` factories are NOT modules — they *produce* the shared
  `ConversationEngine` consumed by several controllers — so keep them as factories; only route-owners
  implement `ApiModule`. This is the vertical-slice tradeoff in practice (self-contained slices vs.
  shared cross-cutting): `ResourceApiModule` self-owns cleanly; the engine + `ApiSecurityFilters`
  stay factored. Value: medium. Effort: medium.
- **D.2b — Apply the decomposition to the worker-side god-classes (the next 582 frontier).** The
  biggest *remaining* class-size pins are worker-side: `KnowledgeServer` (2027), `GrpcIngestService`
  (1911), `SqliteJobQueue` (1753), `RemoteKnowledgeClient` (1594), `RagContextOps` (1548),
  `ResolvedConfigBuilder` (1477). Same medicine (collaborator extraction + the single-`Result`-field
  durability lesson + a fitness function, §D.4). Value: high (the actual remaining treadmill mass).
  Effort: high; less novel (apply, don't invent) — a 586/587-class charter set, not a 583 follow-up.
- **D.2c — Plugin-contributed API modules (a 7th contribution axis).** The plugin substrate is a
  transactional 6-axis `ContributionComposer` with trust tiers, but **plugins cannot contribute HTTP
  routes** (static binding). 583's `ResourceApiModule` (construct+register+shutdown, null-tolerant) is
  *already the exact shape* a plugin-supplied API module would take, and the composer is structurally
  ready for a 7th `ApiModule`/`HttpRoute` axis. Caveat: crosses ADR-0035's truth/presentation boundary
  — plugin routes need trust-tier gating + the loopback invariant + a careful capability surface.
  Value: medium (V1.5+, no users yet). Effort: high (security-sensitive). Clean but speculative.

### §D.3 — New capabilities the decomposition *enables* (highest-payoff axis)

- **D.3a — A self-describing route manifest (keystone).** Inventory finding: rich *semantic*
  registries exist (`/api/registry/{operations,resources,surfaces,shapes,…}`) and response-type codegen
  (record→JSON-Schema→TS) — but **no machine-readable map of the HTTP surface itself**: `{method, path,
  owning-module, required-capability, request+response schema}`. 583 makes this cheap: enumerate routes
  via Javalin's `InternalRouter` (already used by `LegacyEndpointGuardTest`), tag each with its owning
  `ApiModule` (§D.2a) + its capability from `ApiSecurityFilters`' gate classification, join to the
  existing wire-schema for response shapes. Expose as a build artifact and/or read-only `/api/meta/routes`.
  The single biggest *new* thing 583 unlocks. Value: high. Effort: medium. Enables:
  - **D.3b — An in-app API-explorer Surface.** Reuse the existing *Surface* concept (`CoreSurfaceCatalog`,
    `/api/registry/surfaces`, `jf-*-surface` mounts): a developer/operator Surface rendering the live
    route map grouped by domain module, each endpoint's required capability + schema. (Pre-production:
    a *developer* surface — high dogfooding value.)
  - **D.3c — OpenAPI export (compose, don't adopt).** Javalin has an official compile-time OpenAPI
    annotation processor, but adopting it means annotating every handler = a **second source of truth**,
    which the codebase's single-authority ethos (+ its own wire codegen) rejects. The aligned move:
    *generate* OpenAPI FROM the D.3a manifest so external tooling (Swagger UI, client gens) works with
    no parallel annotation surface. Effort: low once D.3a exists.
  - **D.3d — Typed FE API *client* generation.** Wire codegen emits response *types* only today; the
    *call surface* is hand-written in the FE. Generating a typed client from the D.3a manifest closes
    the contract loop end-to-end (kills a class of FE/BE drift). Value: medium-high.
- **D.3e — A live "system capability map" UX.** `ApiSecurityFilters` already classifies routes by the
  capability they need (worker / inference); joined with live capability state, that's a "what works
  right now" view of which API families are available and why others aren't. Composes with the existing
  health/readiness surfaces. Value: medium.

### §D.4 — Protect the structure: a composition-root fitness function

- **D.4 — A "thin composer" fitness function for LocalApiServer.** 583 §B.11's durability rests on a
  *pattern* (single-`Result` field) + the generic class-size LOC gate. The literature's durable
  mechanism for "don't let structure erode" is an **architectural fitness function** — a structural
  check in the same pipeline as tests (*Building Evolutionary Architectures*). The codebase already has
  the precedent: `CompositionRootGuardrailsTest` (ArchUnit) pins cardinality ceilings on the *bootstrap*
  composition root (phases ≤ 8, phase-output record ≤ 26 components, lateBindings ≤ 5 fields). The
  analogue for the *API* root: an ArchUnit rule that `LocalApiServer` stays a thin composer (instance-
  field-count ceiling; possibly "must not both `new …Controller(...)` and bind routes"). **Honest
  caveat:** the class-size LOC gate already provides ~80% of this, and a "no construct + register in one
  class" rule has a real false-positive (LocalApiServer legitimately re-creates `KnowledgeSearchController`
  in `lateBindKnowledgeServer`). So the high-confidence version is the **field-count ceiling** (cheap,
  precise, complements LOC); the behavioral "purity" rule is appealing but needs careful scoping. Value:
  high (capstones 583's anti-regrowth goal *by construction* — the thing §B.11 only gestured at). Effort: low.

### §D.5 — If we pursue any (prioritized; no-rush / pre-production / all-viable context)

1. **D.4 — thin-composer field-count fitness function** — cheapest durability win; makes 583's
   anti-regrowth goal structural, not margin-dependent. Low effort, high durability value.
2. **D.3a + D.3b — route manifest → API-explorer Surface** — highest-payoff *new* capability, uniquely
   enabled by 583, composing with the registry / Surface / wire-codegen infra already present.
3. **D.2a — the `ApiModule` seam** — clean simplification that completes the convention-registration end-state.
4. (Bigger, separate charters) **D.2b worker god-classes** → **D.3c/d OpenAPI + typed client** →
   **D.2c plugin routes** (V1.5+, security-gated).

### §D.6 — How this was researched (for reproducibility)

- **Round 1 — codebase inventory** (3 parallel read-only Explore agents): API self-description
  (`RegistryController`, `*Routes`, `gen-wire-schema-types.mjs`, `contract-surfaces.v1.json`,
  `CoreSurfaceCatalog`, `LegacyEndpointGuardTest`'s `InternalRouter` route enumeration); the plugin
  substrate (`ContributionComposer`/`ContributionRegistry`, the 6 axes, `ExamplePlugin`, ADR-0035); the
  governance kernel (`scripts/governance/run.mjs`, ratchet/scan/meta gates, `makeScanGate`, ArchUnit
  `CompositionRootGuardrailsTest`/`UiApiGuardrailsTest`, the prose-tier register).
- **Round 2 — external prior art:** Composition Root / Pure DI (Seemann — convention/auto-registration
  guards against root re-bloat); architectural fitness functions (*Building Evolutionary Architectures*
  2e — structural checks in-pipeline catch drift early); Javalin OpenAPI plugin (compile-time,
  annotation-based → would be a 2nd source of truth); vertical-slice / modular-monolith tradeoffs
  (self-containment vs cross-cutting duplication — mirrors `ResourceApiModule` vs the shared engine).

**Sources:** ploeh blog (Composition Root, Mark Seemann); *Building Evolutionary Architectures* 2e
(O'Reilly, ch. 2 & 4); javalin.io OpenAPI plugin docs + `javalin/javalin-openapi`; Milan Jovanović /
Kevin Sookocheff on vertical slices in modular monoliths.

---

## §D.7 — Implementation Record (spine P1–P3 shipped + browser-validated)

The §D.5-prioritized **spine** is implemented and merged to `main` (worktree
`worktree-583d-extensions`, branched from `4aaeafee0`, merged green). Each phase landed in its own
commit; all static gates + tests green; **P3 is validated in a real browser** per the closure rule.

### P1 — thin-composer fitness function (§D.4) — *protects the structure*
- `modules/ui/src/test/java/io/justsearch/ui/api/LocalApiServerThinComposerTest.java`: reflection test
  pinning `LocalApiServer` instance-field count ≤ **28** (current actual = 28; no buffer — a growth must
  be a deliberate, justified bump). Pattern copied from `CompositionRootGuardrailsTest`.
- **Decision recorded:** field-count ceiling only (high-confidence). NOT the behavioral "no construct +
  no register in one class" rule — it has a real false-positive (`lateBindKnowledgeServer` legitimately
  re-creates `KnowledgeSearchController`), as §D.4 found. No tier-register row added (the
  `CompositionRootGuardrailsTest` field-count precedent is itself not registered — consistency).

### P2 — self-describing route manifest + capability single-authority (§D.3a) — *the keystone*
- `RouteCapabilityPolicy.java`: the ONE authority for `{path → required capability}`. `enum Capability
  { WORKER, INFERENCE }`; `record Rule(pathPattern, getExempt, required)`; `RULES` covers knowledge/*
  (getExempt, WORKER), indexing/* (getExempt, WORKER), chat/agent (WORKER+INFERENCE). `requiredFor(method,path)`.
- `ApiSecurityFilters.java`: `setupCapabilityGates` refactored to iterate `RouteCapabilityPolicy.RULES`
  (behavior-identical — the enforce side now reads the same table the manifest describes from; no drift).
- `RouteManifestController.java` + `RouteCohorts.java`: enumerates the live Javalin `InternalRouter`
  (the `LegacyEndpointGuardTest` pattern), tags each route with cohort (declarative prefix table) +
  required capabilities (from `RouteCapabilityPolicy`); serves `GET /api/meta/routes`. Constructed
  inline in `setupRoutes` (no field — preserves the P1 ceiling).
- `RouteManifestControllerTest.java`: verifies cohort + capability tagging + get-exemption + precedence.
- **Live smoke (GREEN):** `GET /api/meta/routes` → 200, **201 routes across 22 cohorts, 19
  capability-gated**; chat/agent → [WORKER, INFERENCE], indexing/* mutations → [WORKER], indexing GET → [].
- **Refinement vs plan:** no wire-schema codegen entry — the manifest is an app-internal `/api/meta`
  diagnostic endpoint, not an FE wire contract surface; the controller serves a plain JSON envelope.

### P3 — API-explorer Surface + live capability map (§D.3b + §D.3e) — *user-visible; browser-validated*
- FE: `modules/ui-web/src/shell-v0/views/ApiExplorerView.ts` — `JfElement`-based Lit view, composes
  `surfaceScrollLayoutStyles`, token-only colors, no own `<h1>`/`main` landmark; projects
  `GET /api/meta/routes` grouped by cohort, cross-referenced with `GET /api/status` readiness (§D.3e);
  degrades to confident loading/error/empty states. Mirrors the sibling `GovernanceView`.
- Registration: `CorePlugin.ts` (`core.api-explorer-surface`, DEVELOPER/DEEPLINK),
  `lazySurfaceRegistry.ts` (lazy chunk), `CoreSurfaceCatalog.java` (Java authority, empty-consumes ⟹
  PRODUCT altitude, `jf-api-explorer-view`), `registry-surface.en.properties` (i18n).
  `CoreSurfaceCatalogTest` count 17 → 18.
- **Static verification (all GREEN):** FE typecheck + 2996 unit tests; the 9 presentation gates
  (layout-purity, ambient-purity, a11y-closure, color-tokens, theme-token-closure, controls-a11y,
  presentation-purity, surface-composition, declared-surfaces) + surface-altitude; surface-conformance
  Java tests; `build -x test`.
- **REAL-BROWSER validation (GREEN — the success criterion):** live Lit shell at
  `#justsearch://surface/core.api-explorer-surface` against the running dev stack. Confirmed: the
  surface mounts and renders the intro + summary (201 routes / 22 cohorts / 19 capability-gated); the
  **§D.3e live-capability strip** (`worker: available` + `inference: available`, green, matching the
  status bar "Online — Qwen Qwen3.5-9B"); cohort-grouped tables (agent, conversation, debug, indexing,
  inference, knowledge, mcp, observability, …); **per-route capability badges** — `POST /api/chat/agent`
  → `worker`+`inference`; indexing mutations → `worker`; and the **GET-exemption** visible (`GET
  /api/indexing/roots` → `—` while `POST /api/indexing/roots` → `worker`), proving the single
  `RouteCapabilityPolicy` authority end-to-end. Scrolling/layout (`surfaceScrollLayout`) and token
  theming correct; no console errors attributable to the surface.
- **Note (dev-stack mechanics):** the Head route registration (`/api/meta/routes`) required an explicit
  `:modules:ui:installDist` + restart — the documented stale-jar pitfall (the MCP start reported the
  install UP-TO-DATE and served the prior jar). First-navigation freezes were Vite dev-server churn from
  the stack restart; navigating within a warm SPA (eager chat surface → hash-nav to the surface) is the
  reliable path and rendered cleanly.

### Tail (P4–P7) — status
The user directed building the full tail (consumerless now, accepted as future-proofing — the
investigation showed no current consumer exists for the OpenAPI doc / typed client / a second route
cohort, so these are speculative by the repo's own AHA/YAGNI rules; built at the user's explicit call).

- **P6 (§D.2a — ApiModule seam): DONE & merged.** `interface ApiModule { register; default shutdown }`;
  `ResourceApiModule implements ApiModule`; LocalApiServer holds cohorts in a `List<ApiModule>` (one
  today) iterated for register + shutdown. Net-zero field change → the §D.4 ceiling holds at 28 (no
  rebalance). Honest scope: the static handler-passing `*Routes` stay as-is; `RuntimeApiRoutes` is a
  sub-component of `ResourceApiModule`, not a top-level cohort.
- **P4 (§D.3c — OpenAPI export): DONE & merged + live-smoked.** `GET /api/meta/openapi.json`
  (`OpenApiController`) composes the §D.3a manifest into a valid OpenAPI 3.1 doc (cohorts→tags,
  capability gates→`x-required-capabilities`, `{param}` declared). Runtime endpoint (the manifest is a
  runtime enumeration — no static route source at build time, ADR-0026 CI has no live backend).
  Unit-tested (`OpenApiControllerTest`). **Live smoke GREEN:** `GET /api/meta/openapi.json` → 200,
  `openapi:"3.1.0"`, 185 path items, `POST /api/chat/agent` tagged `agent` with
  `x-required-capabilities:[WORKER,INFERENCE]`.
- **P7 (§D.1 — polish): DONE & merged.** Added the missing class-level javadoc to `LocalApiServer`
  documenting the post-583 thin-composer structure. File 978 LOC (< 1000 ceiling).
- **P5 (§D.3d — typed FE client): DONE & merged + live-captured.** `scripts/codegen/gen-api-client.mjs`
  projects the route manifest into a typed `apiRoutes.ts` (route key → `{method, path, cohort,
  requiredCapabilities}`, `apiPath(key)` helper, compile-checked keys). Two modes: `--from-live=<baseUrl>`
  captures the committed snapshot `SSOT/schemas/route-manifest.v1.json` from a running backend AND emits
  the client; default/`--check` regenerates offline from the snapshot. Pure `renderClient()` is
  fixture-unit-tested (`gen-api-client.test.mjs`, 4 checks). **Generation GREEN:** captured 202 routes →
  committed snapshot + generated `apiRoutes.ts` (`--check` idempotent); migrated both `ApiExplorerView`
  fetches (`/api/meta/routes`, `/api/status`) to typed `apiPath(...)` keys (the consumer proof) — FE
  typecheck + 2996 unit tests green. Honest caveat in the script header: the committed snapshot is a
  point-in-time capture and CI's offline `--check` only guards client↔snapshot, not snapshot↔live
  (no live backend in CI) — re-run `--from-live` after a route-surface change.

**All §D.5-prioritized work + the full tail are shipped, merged, and live-verified.**

## §D.8 — Design-fidelity follow-ups (the three §D analysis gaps, closed)

A conceptual re-read of §D against the implementation flagged three fidelity gaps; the user directed
closing all three. Shipped + merged + live-verified:

- **§D.3a owning-module dimension (was: "cohort, not owning-module").** `RouteEntry` gains
  `owningModule`, derived **single-source** from each `ApiModule.ownedRoutePaths()` — `ResourceApiModule`
  snapshots its own routes during `register()` (no parallel prefix table). `cohort` stays as the domain
  grouping (the legitimate single authority for "which domain" — there is no other source, so it is not
  a drifting second copy). **Honest partiality:** the static handler-passing `*Routes` are deliberately
  not instance-modules (AHA — §D.2a), so their routes carry no `owningModule`. Live: 62 routes attributed
  (`ResourceApiModule`/`MetaApiModule`).
- **§D.3a schema dimension (was: "no per-route schema").** `RouteEntry` gains `responseSchema` from
  `RouteResponseSchemas` — a small declarative `{route → generated wire schema}` map for the documented
  wire routes (7 today). Partial by design: a per-route schema authority for all ~200 routes is a
  separate charter (handler annotations would be a second source of truth, rejected per §D.3c); the map
  is the one maintenance point. `OpenApiController` emits per-op `$ref` + `components.schemas` +
  `x-owning-module`. Live: `GET/POST /api/knowledge/search → knowledge-search-response.v1.json`.
- **§D.2a fuller realization (was: "single-implementer / setupRoutes not a loop").** `MetaApiModule`
  now owns `/api/meta/{routes,openapi.json}` via the `apiModules` loop — dogfoods the seam (the meta
  family was inline, contradicting §D.2a's own principle). `apiModules` is now a real list
  ({MetaApiModule[, ResourceApiModule]}). The static `*Routes` remain by deliberate AHA choice (forcing
  them into instances is the over-engineering §D.2a warned against), so this is the *measured* loop step,
  not a total conversion.
- **P5 categorization fix.** The captured route snapshot moved out of `SSOT/schemas/` (which
  `syncSsotSchemas` mirrors into the ui classpath as a *served schema*) to the codegen output dir — a
  route-data snapshot is not a wire-record JSON Schema.
- The API-explorer surface surfaces both new dimensions (Owner + Response columns), browser-verified.

Verification: `RouteManifestControllerTest`/`OpenApiControllerTest` (new owningModule/schema/`$ref`
coverage), thin-composer field ceiling unchanged (28), `UiApiGuardrails`/`LegacyEndpointGuard`,
`build -x test`, FE typecheck + 2996 unit tests + presentation gates, live manifest/openapi smoke, and
the live-browser explorer render (all five columns).

**Residual: none.** The §C independent review is done (ship-with-nits; the one defect it found — the
dangling OpenAPI `$ref`s — is fixed at root + closure-tested; see §C). The charter (§1–§6) and every
§D item the user directed (the §D.5 spine, the full tail, the three fidelity follow-ups) are shipped,
merged to `main`, and live-verified.
