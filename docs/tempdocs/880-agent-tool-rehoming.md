---
status: IMPLEMENTED
created: 2026-09-01
updated: 2026-09-01
follows: [868, 875, 876, 877, 878, 879, 832, 584, 519, 541]
owner-session: f6617483
---

# 880 — Agent-tool re-homing and loop decomposition

Pure-structure workstream. Every behaviour question that came out of the 868 investigation
was answered by 875–879, which are all on `main` (PRs #581, #584, #583, #576, #582). What is
left is where the code LIVES and how big its methods are: declaration two modules from
implementation, five identity adapters that a finished migration left behind, a composition
path that assembles the same bundle twice, and two methods (`AgentStepRunner.executeIteration`,
`AgentSession`) that have outgrown any single reader.

**Constraint that binds the whole tempdoc: zero behaviour change.** Every test that passes
before must pass after, unchanged, except where a package or wiring reference moves. If a move
forces a behaviour question, the move stops and the question is recorded as an open item.

## Findings handed over

*Verbatim from the orchestrator's review synthesis (BRIEF-880-C2). Line references are as of
the pre-merge review and are RE-VERIFIED in §A below — five large PRs landed on these exact
files between the review and this tempdoc.*

1. **Declaration is two modules from implementation for no boundary reason.** `AgentToolsOperationCatalog` (app-services) imports only `io.justsearch.agent.api.registry.*` + `java.util` — zero app-services types; it compiles unchanged inside `app-agent` (which has `api(project(":modules:app-agent-api"))`). No ADR backs the split; `LayeringEnforcementTest` pins only core/config/telemetry/ipc leaves and "only app-launcher depends on ui".
2. **Five identity handlers are finished-migration residue** (`SearchOperationHandler` etc., `return tool.execute(argumentsJson)`, 131 LOC; `undo` is a `default` on `OperationHandler`, which lives in app-agent-api). Tools can `implements OperationHandler` with zero new module edges. Their javadoc (429 §E.9) says the bridge's reason is gone.
3. **Composition path: six holders, two phase boundaries, one bundle un/re-bundled twice**, then discarded on a normal boot because `registerLateBound` calls `assemble` a second time. `SubstratePhase.runWithOutcome` has 22 parameters; `HeadAssembly` (1463 LOC, 55 fields, 11 reasons to change) destructures the bundle (~:417) and re-copies to finals (~:515). `CompositionRootGuardrailsTest.MAX_OUTPUT_FIELDS = 26` fired on the 7th tool and was relieved by re-shaping, not re-homing.
4. **`AgentStepRunner.executeIteration` is one 860-line method** (~15 concerns by its own section comments: cancel, steering drain, loop-block escalation, DECIDING, tool list/E0a, zero-observer park, token accounting, context gate + compaction, budget gate, empty-response detection, handoff, virtual dispatch, safety gate, loop guard, grounding stamp + truncation).
5. **`AgentSession` (1327 LOC, 82 methods) has four separately-implemented gate mechanisms** (two verbatim singleton gates ~:772-802 / ~:839-874, two map-keyed) — a `HeldGate<T>` primitive would replace ~110 LOC with ~40.
6. **Dead channels**: `vop_*` virtual tools — `withVirtualOperationStore` has zero callers in main or test (`AgentLoopWiring` uses the 1-arg ctor; the merge at `AgentOperationEmitter:~167-184` is unreachable in every configuration); E0a/handoff multi-agent path has no producer of non-primary profiles (`AgentSessionController.ts` hardcodes `agentProfiles: []`); `buildE0aTools` discards selection. Decide per channel: delete (retire-with-a-sweep: FE `VirtualOperationCatalog.ts`, `AgentToolsController` virtual endpoints, `VirtualOperationStore`, tests) or keep with a named producer. Default to delete unless a tempdoc records a live plan.
7. `app-agent-api` is documented as "agent-facing contracts" (`docs/explanation/19-module-architecture.md:~34`) but ~120 of ~160 types are the operation/plugin/surface substrate — the NAME is wrong, not the position; don't move the module, fix the doc (and consider a package rename only if cheap).

### Design guidance (from the topology review)
- Don't create a new module; `app-agent` is the right node.
- Move 1: tools implement `OperationHandler`; delete the five adapters; `AgentToolHandlers` registers tools directly via the 877 table. Boundary tests: none change. Pre-check `governance/execution-surfaces.v1.json` and `logic-seams.v1.json` for registered referencers.
- Move 2: `AgentToolsOperationCatalog` → `io.justsearch.agent.tools`; `record AgentToolBundle(Operation declaration, OperationHandler handler)` per tool with `static bundle(...)`; `assemble` returns `List<AgentToolBundle>`; collapse the composition path (SubstratePhase params, HeadAssembly finals, one registration loop). `MAX_OUTPUT_FIELDS` stays 26.
- Move 3: split `registry-operation.en.properties` agent entries into app-agent's resources if `I18nKeyValidator` permits (check `:13,49`); the bundle is loaded by classpath path.
- Then: `executeIteration` split into ~6 collaborators along the existing section comments (budget gate, context gate, handoff, virtual dispatch, safety/loop guard, result append) — the 584 "cut on the breadth axis" method; `HeldGate<T>` in AgentSession.
- Honest cost 4–8 days; `AgentLoopServiceTest` (4789 lines, 115 tests) is the blast radius — keep it green, don't rewrite it.
- Update `docs/explanation/22-agent-system-architecture.md` §Safe Extension Path (6 steps → 2) and 19-module-architecture.

### Ride-alongs routed from workstream 877
- `FileOperationExecutor.java:~29` takes roots as `Supplier<List<Path>>` from `getWatchedPaths` while every other tool uses `getWatchedRoots()`/`RootsView` — a fifth roots shape; fold it into `RootsView` during the re-home.
- Add one line to `.claude/rules/agent-lessons.md` (platform constraints section): a class-scanning test (`WholeProgramDeadCodeTest`, `AgentGroundingSeamAuditTest`, ArchUnit importers) that reds with `TimeoutException` during parallel-agent work must be re-run isolated before being believed — CPU starvation from concurrent worktree builds, observed 2026-08-31.

### What NOT to do
No behaviour changes (pure re-homing/decomposition; behaviour changes belong to 875–879 which are already on main). No MCP surface changes. No new module.

## §T. Theorization (2026-09-01, before design)

Written from the post-merge tree, with two independent source audits in flight. Nothing here is
decided; §A records the re-verified facts and §B the design.

### T.1 The shape underneath all seven findings

Every finding is the same species: **a migration that succeeded left its scaffolding standing,
and the scaffolding now reads as architecture.** None of it was wrong when written.

- The five identity handlers bridged a boundary that has since moved. `OperationHandler` lives in
  `app-agent-api`; `app-agent` already declares `api(project(":modules:app-agent-api"))`; the tools'
  `execute(String)` already returns `OperationResult`. So the tools *structurally satisfy the
  interface already* — the adapters declare a conformance the compiler would accept directly.
  The bridge's far bank was removed and nobody noticed, because nothing in the system notices that.
- The catalog/tool split was a boundary too. `app-services` already declares
  `api(project(":modules:app-agent"))`, and post-877 the catalog even imports
  `io.justsearch.agent.tools.FileOperationsTool` for `MAX_BATCH_SIZE` — the split it was meant to
  protect is already crossed, in the direction that makes co-location free.
- The six holders and the 22-parameter phase method are pressure gauges on one pipe: the composition
  root carries *tool instances* positionally instead of carrying *the assembled registration* as one
  value.

Naming the species is worth more than fixing the instances, because it is mechanically detectable:
**an adapter whose every method body is a direct delegation, and whose interface is visible from the
delegate's own module, is residue by construction.** That is a decidable property of the bytecode.
Whether it earns a gate is genuinely contested — `structural-defects-no-repeat` says one documented
instance proves the class, while the gate-cost line (743) says do not build machinery for a
one-off. This tempdoc should *record* the invariant and leave the gate question to an owner call
rather than quietly resolving it in either direction. (If it is ever built, the honest scope is
narrow: delegation-only adapters, not "small classes".)

### T.2 The empirical argument for co-location, which beats the aesthetic one

"Declaration should live near implementation" is taste. The falsifiable version: **things that
change together should live together (AHA — only unify what shares a reason to change).** The last
five PRs are the evidence. 875 (consent boundary), 876 (offering truth), 877 (centralisation), 879
(policy enforcement) each edited the catalog entry *and* the tool in lockstep — a risk tier next to
the code that honours it, a declared schema key next to the parser that reads it. The
`AgentToolsOperationCatalog` javadoc is now thick with comments explaining what the *tool* does,
which is the smell in prose form: the declaration cannot be understood without the implementation
open beside it.

That argument also bounds the move. It does **not** license moving anything else out of
app-services; it licenses moving exactly the declarations whose co-change history is documented.

### T.3 The counter-argument to Move 2, and why it probably still loses

Deliberately steelmanned: declaration-in-a-leaf is a real pattern — many consumers read a catalog
without wanting the implementation. If the MCP surface, the UI emitter, and the preview endpoint all
read `AgentToolsOperationCatalog`, keeping it low means they need only the leaf.

It loses here for a specific, checkable reason rather than a general one: `app-services` **already**
depends on `app-agent` as `api`, so no consumer gains an edge it did not have. The pattern protects
against a dependency that is already present. If the audit shows a consumer in a module that does
*not* depend on app-agent, this reverses — that is the fact to check, not the principle to argue.

### T.4 Extract the predicate, not the phase

This is the theorization point most likely to decide whether the loop work succeeds or hurts.

The 584 precedent (`AgentPromptComposer`) worked because prompt composition is a *pure function of
its inputs* — extraction could not change control flow. `executeIteration` is not that: its sections
mutate shared state and short-circuit out of the method. "Cut along the section comments" is
therefore not obviously safe; the seams resist precisely where locals are produced in one section and
consumed three sections later.

Three candidate strategies, in increasing risk:

- **(a) Extract the decision, leave the effect.** Each gate becomes a pure
  `evaluate(inputs) -> Verdict` returning a record; `executeIteration` keeps
  `if (verdict.stop()) return ...` textually where it is. Control flow is unchanged *by
  construction*, and the resulting units are trivially unit-testable in isolation for the first time.
  LOC falls; the method's shape becomes legible as a sequence of named questions.
- **(b) Extract whole phases with a mutable context object.** Bigger LOC win, but it moves control
  flow into the collaborators and the context object becomes a new god-type — trading a long method
  for a wide record, which is the failure mode `substrate-without-consumer-flavors` warns about.
- **(c) Add section markers and stop.** Cheapest; near-zero value.

(a) is the only one that is behaviour-preserving *by inspection* rather than by test coverage, which
matters a lot when the safety net is a 4,700-line test file nobody should be rewriting. The honest
cost is that (a) does not make the method short — it makes it *shallow*. That may be the right trade:
a 400-line method that reads as fifteen named questions is a different artifact from an 860-line
method that reads as fifteen tangled ones, even though both are "long".

### T.5 `HeldGate<T>` is the highest silent-risk item in the tempdoc

A gate unification is only safe if the gates are genuinely identical in **timeout, cancellation, and
interrupt** behaviour. If they differ and are unified anyway, the regression is a gate that now times
out where it used to wait forever (or the reverse) — invisible to unit tests, visible only under a
live stall. This is the one item where "looks like duplication" and "is duplication" can diverge
badly.

The conservative shape: unify **only** the pair that is verbatim-identical, leave the map-keyed ones
as named non-members with one line saying why. A primitive covering two of four call sites is still
a real reduction and carries none of the silent risk. If the audit shows all four are truly
identical, the full unification becomes available — but the burden of proof sits on the audit, not
on the tidiness of the result.

### T.6 What "zero behaviour change" means operationally — and building the oracle first

The constraint needs a falsifiable definition, or it degrades into "I looked and it seemed fine":

1. `AgentOperationEmitter`'s wire projection of the seven agent operations is **byte-identical**
   before and after.
2. The set of `OperationRef → handler` bindings resolvable at boot is the **same set of refs**
   (handler class names change; the refs must not).
3. The full unit suite passes with **no test edited except package/import lines**.

(1) is not currently guarded. Tempdoc 868 §D routed exactly this: `AgentOperationEmitterRegressionTest`
deep-equals a baseline built from four **hand-written stub** operations, not the real catalog — so the
shipped model-facing tool surface has no baseline guard at all
(`docs/reference/api-contract-map.md:248`, `AgentOperationEmitterRegressionTest.java:56`).

That turns into the single best idea in this theorization: **build the oracle before making any
move.** Add a baseline test that snapshots the *real* `AgentToolsOperationCatalog` projected through
`AgentOperationEmitter`, and a companion assertion over the resolved ref→handler set. Then every
subsequent move is verified by a byte-comparison instead of by inspection, and the same test closes
a routed 868 open item. Adding a test is not a behaviour change, and it is the enabling condition for
everything else — so it is step zero, not a nicety.

Its known limit, stated up front: a baseline pins the *projection*, not the tool's runtime behaviour.
It would not catch a change in what `SearchTool.execute` does. It is exactly the right net for a
re-homing and exactly the wrong net for a rewrite — which is another reason this tempdoc must stay a
re-homing.

### T.7 Where a "pure structure" move quietly stops being pure

Two places to watch, both worth naming now so they are not decided by accident later:

- **The double `assemble`.** Collapsing it is presented as tidying, but `assemble` is documented as
  *not side-effect free* — it constructs a `FileOperationLog` whose constructor runs a diagnostic
  retention prune. Changing how many times that runs is a real, on-disk behaviour change, however
  small. If the audit confirms two calls on a normal boot, the honest move is to restructure the
  holders and **leave the call count alone**, recording the collapse as a separate question. This is
  the tempdoc's own rule applied to its own most tempting item.
- **Move 3 (splitting `registry-operation.en.properties`).** `Presentation.forId` derives I18n keys
  from the `OperationRef` value, not from the declaring class's package, so the keys are stable under
  Move 2 regardless. Move 3 is therefore *pure tidying of a resource file* whose only risk is a
  resolution difference in how the bundle is loaded — worst cost/benefit ratio in the set. It should
  proceed only if the validator makes it trivially safe, and otherwise be recorded as deliberately
  not done, with the reason.

### T.8 Dead channels: a scope question, not just a delete

Deleting genuinely unreachable code is not a behaviour change — that is what unreachable means. The
whole difficulty is *proving* unreachability, and the bar is higher than "no callers": reflective
lookup, ServiceLoader, config strings, and HTTP paths all route around a `git grep` for a symbol. An
endpoint in particular is a surface even when nothing calls it; deleting it wants a check against
`contracts/**` and `docs/reference/api-contract-map.md`, not just the Java call graph.

There is also a shape question worth settling deliberately: dead-channel removal is a *third* thing,
next to re-homing and decomposition. Mixing a deletion into a pure structural diff makes both harder
to review and neither independently revertible. The resolution that costs nothing is **separate
commits inside one PR** — the sweep stays atomic with its own message, and a reviewer can read the
re-home without the deletion in the way.

If the audit finds a tempdoc recording a live forward plan for `vop_*` or for multi-agent profiles,
the default flips to keep-with-a-named-producer, and the finding becomes "declared-but-inert", which
is 879's subject and not this tempdoc's.

### T.9 Sequencing, ordered by reversibility rather than by size

Ordered so the tempdoc has landed value even if the later items prove too large for one PR:

0. The oracle (T.6) — enables everything, risks nothing.
1. Move 1 (tools `implements OperationHandler`, delete the five adapters) — fully mechanical,
   compiler-verified, smallest diff.
2. Move 2 (catalog re-home + per-tool bundle record + one registration loop) — mechanical plus the
   one composition-path question from T.7.
3. Ride-along: the `FileOperationExecutor` roots shape — but only if `getWatchedPaths` and
   `getWatchedRoots` return the *same* set. If they differ, folding them is a behaviour change and
   the item must become an open question instead. This is a fact to check, not a judgement to make.
4. Dead channels, as their own commits.
5. `executeIteration` predicate extraction (T.4a).
6. `HeldGate` for the verbatim-identical pair only (T.5).

Items 5–6 are the ones most likely to need their own PR. Splitting there is legitimate — it is a
boundary between two kinds of work, not a "follow-up PR will clean it up" deferral of a sweep.

### T.10.pre — note on §A

§A below re-verifies every handed-over claim against the post-merge tree. Several turned out
STALE, and two of them falsify theorization written above (T.7's double-`assemble` worry and part
of T.9). The theorization is left as written rather than retro-edited — it is dated thinking, and
§A is the correction of record.

### T.10 The recurring system shape this points at

If there is a principle here beyond the instances, it is about **the direction refactors leave
residue in**. A migration that *adds* a layer is visible: reviewers see new files. A migration that
*removes the reason for* a layer is invisible: the files it obsoleted still compile, still have
tests, still pass. The system has strong machinery for the first case (gates, registers, catalogs)
and none for the second.

The registers already model "declared but unimplemented" (879's subject). The mirror — "implemented
but no longer needed" — has no representation at all. That asymmetry, not the five adapters, may be
the durable finding; naming it is in scope for this tempdoc, building for it is not.

## §A. Re-verification against the post-merge tree (2026-09-01)

Two independent read-only audits, plus my own spot-checks. Base `f3b29de5`, which contains all five
prerequisite merges (e10e690d / 558efb6a / ef892291 / c7045b8a / 650fbe26). Every handed-over claim
is marked CONFIRMED, STALE (true once, now wrong), or REJECTED (never true — an audit error).

### A.1 Findings that stand

| # | Claim | Verdict | Evidence |
|---|---|---|---|
| 1 | Catalog has no app-services dependency; compiles unchanged in `app-agent` | **CONFIRMED** | `AgentToolsOperationCatalog.java:4-27` imports only `io.justsearch.agent.api.registry.*` (23 types) + `io.justsearch.agent.tools.FileOperationsTool` + `java.util.*`. `modules/app-services/build.gradle.kts:17` already declares `api(project(":modules:app-agent"))`; `modules/app-agent/build.gradle.kts:8` declares `api(project(":modules:app-agent-api"))`. |
| 1b | No boundary test blocks the move | **CONFIRMED** | `LayeringEnforcementTest` — all 11 `@ArchTest` rules read; **none names `io.justsearch.agent..`**. Rule 5 pins `io.justsearch.app.api..`, a different package root. `governance/execution-surfaces.v1.json` has no entry for the catalog or the handlers (its only agent-tools row is `agent-narration` at `:108-115`, pinning `SearchTool.java` as an exempt consumer). `governance/logic-seams.v1.json`: zero hits. |
| 2 | Five identity handlers are residue; tools can implement `OperationHandler` directly | **CONFIRMED, and stronger than stated** | All five tools ALREADY declare `public OperationResult execute(String argumentsJson)` (`SearchTool.java:161`, `BrowseTool.java:72`, `IngestTool.java:80`, `ReadDocumentTool.java:96`, `FileOperationsTool.java:92`) and `FileOperationsTool.java:148` declares `public OperationResult undo(String executionId)`. The tools structurally satisfy the interface today; the adapters declare a conformance the compiler would accept directly. 131 LOC across the five. |
| 4 | `executeIteration` is one enormous method | **CONFIRMED, worse than stated** | `AgentStepRunner.java:132-1065` = **934 lines** (the brief said 860 — 875/876/878/879 each added to it). 14 `return`s, 0 `throw`s, 44 distinct `AgentSession` mutation sites. |
| 5 | `AgentSession` has four separately-implemented gates | **CONFIRMED (count), STALE (lines)** | 1387 LOC. Budget gate `:774-815`, context gate `:829-885`, approval gate `:689-718`, virtual-tool gate `:952-975`. The brief's `~772-802` / `~839-874` have drifted. |
| 6a | `withVirtualOperationStore` has zero callers | **CONFIRMED** | `AgentOperationEmitter.java:140` (the declaration) and `:55` (its own javadoc) are the only two hits in the entire tree. |
| 6b | The emitter's virtual merge is unreachable in every production configuration | **CONFIRMED** | `AgentLoopWiring.java:73-75` uses the 1-arg ctor → `this(messageResolver, null)`; `withAvailabilityProbe` (`:149-152`) propagates the null. `RegistrySnapshotExporter.java:335` and `AgentOperationEmitter.java:285` likewise. The 2-arg and 3-arg ctors that accept a store have no caller outside the class, so `emit`'s merge branch (`:195-211`) never runs. |
| 6c | `buildE0aTools` discards the run's selection | **CONFIRMED** | `AgentStepRunner.java:1128-1139` hard-codes the selector to the single literal `core.ingest-files` and passes no `request.selectedToolNames()`, unlike its sibling `buildIterationTools:1103-1115` which honours it at `:1109-1111`. |
| 6d | No producer of non-empty `agentProfiles` | **CONFIRMED** | `AgentSessionController.ts:1898` hard-codes `agentProfiles: []`, pinned by `AgentSessionController.test.ts:1207,1216`. Non-empty lists are constructed only in two Java tests (`AgentSseContractTest.java:642,698-724`; `ConversationEngineTest.java:503-521`). With `[]`, `AgentTurnPolicy.java:56,74` short-circuit, so `DECIDING` is unreachable and the whole handoff/E0a path is dead in production. |

### A.2 Findings that were STALE — already fixed by 868/875–879, or never as described

- **STALE — the double `assemble` does not happen on a normal boot.** The brief said
  `registerLateBound` calls `assemble` a second time and the first bundle is discarded. On production
  boot `assemble` runs **exactly once** and nothing is discarded. `HeadlessApp.java:351-354` (and
  `LauncherEnvironment.java:90-96`) pass `knowledgeServer = null`; `HeadAssembly.java:321-327` then
  nulls the client; `AgentToolFactory.java:63-65` returns an all-null `Output` **without calling
  `assemble`**; `registerEager` skips all five; the single `assemble` happens later at
  `AgentToolHandlers.java:205-216`. The shape is pinned by `AgentToolFactoryScanWiringTest.java:108-110`.
  The double call is reachable only from test paths that supply a non-null `KnowledgeServerBootstrap`
  (`HeadAssemblyTest.java:145,196`).
  **Consequence: §T.7's "collapsing it would be a behaviour change" worry is moot — there is nothing
  to collapse.** The `allLateBoundRefsPresent` guard (`AgentToolHandlers.java:194-201`) does exactly
  what its comment claims and the `FileOperationLog` retention-prune side effect
  (`AgentToolFactory.java:113`) runs once. DONE-BY-876 (§B.5).
- **STALE — `HeadAssembly` does not re-copy tools to final fields.** There is exactly ONE
  agent-tool-adjacent field in the chain: `agentSearchAdapter` (`HeadAssembly.java:88`, assigned
  `:417`). The five tools and `FileOperationLog` are constructor-scoped **locals** (`:417-425`,
  `:515-520`, `:653`); the `*Final` copies exist only for lambda capture into the `tracedPhase`
  supplier at `:544-548`. The file is 1512 LOC, not 1463. Nothing to fix.
- **STALE — the `MAX_OUTPUT_FIELDS` pressure is already relieved.**
  `CompositionRootGuardrailsTest.java:64` still pins 26, but `ServicePhase.Output` now carries the
  agent tools as ONE component (`ServicePhase.java:129`, javadoc `:120-128`) and has **21**
  components; `SubstratePhase.Output` has 16. Five components of headroom. DONE-BY-868 (§B.2). Its
  javadoc at `:59-63` still claims "26 components" and is now wrong — fixed as a ride-along.
- **STALE — the `19-module-architecture.md` wording.** The doc does not say "agent-facing
  contracts"; `:34` says **"Agent-facing request/response contracts"**, which is more wrong, not
  less. Measured: `modules/app-agent-api/src/main/java/io/justsearch/agent/api/` holds **171** Java
  files — `registry/` 124 (72.5%), `conversation/` 17, root 14, `encryption/` 8, `memory/` 3,
  `interaction/` 3, `lifecycle/` 2. **There is no `plugin/` and no `surface/` package** (the brief
  assumed both). The concentration is entirely in `registry/`.
- **STALE — `VirtualOperationCatalog.ts` is not orphaned.** It is booted at
  `modules/ui-web/src/main.jsx:349-378` and publishes on every boot. The true statement is narrower
  and more interesting: it always publishes an **empty** list, because the only way an entry enters it
  is `decorateCommandForAgent` (`VirtualOperationCatalog.ts:100`), which has callers only in `.test.ts`
  files. The repo already says so at `shell-v0/substrates/autonomy/index.ts:47-49`.

### A.3 A finding that was REJECTED

The composition-path audit reported that `AgentToolEmitter` is a **phantom class name** appearing in
four canonical docs, and proposed a rename sweep as a ride-along. **That is wrong.**
`modules/app-agent-api/src/main/java/io/justsearch/agent/api/registry/AgentToolEmitter.java` exists
(4,765 bytes) and is a live SPI with ten referencers — `AgentRunQueries`, `AgentLoopService`,
`AgentStepRunner`, `AgentRunQueryService`, `AgentOperationEmitter` (its implementation),
`AgentToolAuthorityBoundaryTest`, `AgentLoopServiceTest`, `AgentOperationEmitterTest`,
`AgentBatteryTest`. Its `offer(...)` is 876 §B.1's authority method. Every doc naming it is correct
and **no rename was performed**.

Recorded because acting on it would have broken four canonical docs in the name of fixing them — a
worked instance of `audit-without-test`: a subagent's grep-shaped conclusion taken as a result rather
than a hypothesis. The check that caught it cost one `ls`.

### A.4 Two constraints the handed-over plan did not know about

1. **`AgentGroundingSeamAuditTest` hard-codes the method name.**
   `modules/app-agent/src/test/java/io/justsearch/agent/AgentGroundingSeamAuditTest.java:175-176`
   declares `DISPATCH_SEAM_METHODS = List.of("executeIteration", "handleVirtualToolCall")` and asserts
   (at `:193, :203, :230, :270, :305`) that grounding is minted and stamped **only** inside those two
   named methods. Extracting the grounding stamp (`AgentStepRunner.java:1024-1027`) into a
   collaborator fails this gate — which is the gate working, since it asserts a real invariant about
   where grounding may originate. Any decomposition must avoid that section or make a deliberate,
   argued change to the seam list. It is not a mechanical rename.
2. **`AgentStepRunner.java:816-827` mutates the live message list in place.** It holds a reference to
   `session.messages()` and structurally `remove`/`set`s it inside a loop. A collaborator split must
   preserve list *identity*, not pass a copy.

### A.5 The ride-along that must NOT be done

The 877-routed item asked to fold `FileOperationExecutor`'s `Supplier<List<Path>>` roots into
`AgentToolPaths.RootsView`, calling it "a fifth roots shape". The path SETS do agree —
`IndexingService.java:96-103`, and `RootLifecycleOps.java:124-126` vs `:128-156` iterate the same
`watchedRoots` map with no filtering — so a fold would not change *which* roots are seen. Three facts
make it a behaviour change anyway:

1. **Cost.** `getWatchedRoots` does per-root work `getWatchedPaths` does not: `isRootAvailable(root)`
   (`RootLifecycleOps.java:140,166-183`) plus walk-error / walk-completed / drift / `lastVerifiedAt`
   lookups. `FileOperationExecutor` calls its supplier once per `validate(...)`
   (`FileOperationExecutor.java:50`) — a hot path. The fold makes every file-operation validation
   strictly more expensive.
2. **Type and normalization.** `getWatchedPaths` returns `Path`; `RootsView` is over
   `List<BrowseTool.RootInfo>` (path **String** + display name), and the factory's supplier applies
   `.toAbsolutePath().normalize()` at `AgentToolFactory.java:120` while `FileOperationExecutor`
   canonicalizes itself. A fold must preserve that or containment comparison shifts.
3. **The two arguments are two deliberate jobs, and the code says so.**
   `AgentToolFactory.java:127-129` states that `FileOperationsTool` gets the roots view *"NOT as a
   second sandbox (that stays `indexingService::getWatchedPaths`, one argument earlier) but so a
   root-relative path the model echoed back from a browse result resolves"*. `FileOperationsTool`
   takes both on purpose (`:81-90`).

So it is not a fifth shape — it is a sandbox boundary and a name resolver that happen to derive from
the same map. **Not done. Recorded as an open item** rather than decided, per this tempdoc's own rule:
a move that forces a behaviour question stops and becomes a question.

### A.6 Move 3 (splitting the i18n bundle) must NOT be done — it would silently shadow

The design guidance proposed splitting the agent entries out of `registry-operation.en.properties`
into app-agent's resources "if `I18nKeyValidator` permits". The validator is the wrong thing to ask.

- The bundle is single and lives in **`app-api`**, not app-services:
  `modules/app-api/src/main/resources/messages/registry-operation.en.properties`. The seven agent
  operations' `ops.<id>.{label,description,confirm}` keys are at `:130-179`.
- `I18nKeyValidator.java` (76 lines) does **not** constrain loading at all — it is a pure
  set-membership check over `context.validI18nKeys()` (`:42, :52, :63`).
- Loading happens in three independent single-resource `getResourceAsStream` lookups:
  `BootstrapHelpers.java:113-115,126-142` (production), `ValidatorRunnerTest.java:361-375` (gate),
  `MessageCatalogController.java:143-145` (the HTTP route). `getResources()` (plural) is used
  **nowhere**.

`getResourceAsStream` returns the **first match in classpath order**. A second
`messages/registry-operation.en.properties` in another module's resources would therefore not merge
and not conflict — whichever jar sorts first wins and **every key in the loser becomes invisible**.
Since `Presentation.forId` derives I18n keys from the `OperationRef` value and not from the declaring
class's package, the keys are stable under the re-home regardless. The split buys nothing and risks a
silent, locale-wide blanking. **Not done, permanently — this is a "don't", not a "later".**

## §B. Design

Scope: the moves that are provably behaviour-preserving, plus the oracle that proves it, plus the doc
sweep. The two channel retractions and the loop decomposition are handled explicitly in §B.5 and §B.6
rather than silently dropped.

### B.0 The oracle, first

`modules/app-services/src/test/java/io/justsearch/app/services/registry/emitter/AgentToolCatalogBaselineTest.java`
plus `modules/app-services/src/test/resources/agent-tools-wire-baseline.json`.

Two halves, because the wire projection is lossy in exactly the direction that matters:

- **Wire half** — `new AgentOperationEmitter().emit(new AgentToolsOperationCatalog(), List.of())`,
  serialized with insertion order preserved and compared byte-for-byte against the checked-in
  baseline (169 lines, 7 tools). Identity message resolver, no virtual store, no availability probe,
  so the projection is deterministic and independent of live condition state. On mismatch the actual
  output is written to `build/reports/agent-tool-baseline/` and the failure message names the file —
  a deliberate change is a copy, not a hand-edit.
- **Policy half** — per-operation assertions on everything the OpenAI projection throws away:
  `OperationPolicy` (risk tier, confirm strategy, audit policy, retry policy, undo support, advisory
  class, capability family), `executors()`, `audience()`, `lineage()`, `binding()`, `provenance()`,
  and whether an availability expression is present. A wire baseline alone would not notice a dropped
  `withCapabilityFamily("file-operations")` or a `RiskTier.HIGH` relaxed to `MEDIUM` — and those are
  precisely the consent-boundary facts 875 just landed.

This closes the open item tempdoc 868 §D routed (`docs/reference/api-contract-map.md:248` /
`AgentOperationEmitterRegressionTest.java:56`): the shipped model-facing tool surface had no baseline
guard at all, because the existing regression test deep-equals a baseline built from four
hand-written stub operations — it pins the emitter's *algorithm*, not the catalog.

Stated limit: it pins the DECLARATION, not the tool's runtime behaviour. It cannot catch a change in
what `SearchTool.execute` does. That is the right net for a re-homing and the wrong net for a rewrite
— another reason this tempdoc stays a re-homing.

**The oracle was observed RED before it was made green** (`unreachable-seed-green`). A golden-file
test is trivially green the moment you generate its baseline from the code it checks, which is
exactly the shape that can pass for the wrong reason — a resource that never loads, an assertion that
never runs. So the baseline resource was seeded with `[ ]` first and the test run: it failed at
`AgentToolCatalogBaselineTest.java:86` with the real 7-tool projection dumped to
`build/reports/agent-tool-baseline/`. Only then was the dump installed as the baseline. The test is
therefore known to bite, not merely known to pass.

### B.1 Move 1 — the tools become their own handlers

The five tools `implements OperationHandler`; the five identity adapters and their four unit tests are
deleted; `AgentToolHandlers` registers the tool instances directly.

The four adapter tests are **deleted rather than migrated** because the unit under test ceases to
exist: each mocks the tool, stubs `execute`, and asserts the adapter returned the stubbed value. Once
the tool *is* the handler, that assertion is tautological. This is a sweep, not a weakening — no
assertion about tool behaviour is lost, because those tests never made one. (`ReadDocumentHandler`
never had a test at all, so the coverage delta is four tautologies.)

No module edge changes: `OperationHandler` lives in `app-agent-api`, which `app-agent` already
depends on. No boundary test changes (A.1b).

### B.2 Move 2 — the catalog comes home

`AgentToolsOperationCatalog` moves to `io.justsearch.agent.tools` in `app-agent`, beside the tools it
declares. The `import io.justsearch.agent.tools.FileOperationsTool` it needs for
`FileOperationsTool.MAX_BATCH_SIZE` becomes a same-package reference — the split it was meant to
protect was already crossed, in the direction that makes co-location free.

The argument is the empirical one (§T.2), not the aesthetic one: 875, 876, 877 and 879 each edited the
catalog entry and the tool in lockstep — a risk tier next to the code that honours it, a declared
schema key next to the parser that reads it. The catalog's javadoc is now thick with prose explaining
what the *tool* does, which is the same smell in comment form.

Test files stay in their current modules and packages; only their import line changes. Moving them
would enlarge the diff for no gain, and app-services depends on app-agent so they still compile.

`governance/consult-register.v1.json` (`:145` `pathIncludes`, `:154-155` prose) is updated in the same
commit — a path trigger that no longer covers its subject is exactly the residue-becomes-false-
authority failure `retire-with-a-sweep` exists to prevent.

**The sweep caught a live gate, and this is the part worth remembering.**
`scripts/ci/check-policy-axis-liveness.mjs:37` hard-coded
`CATALOG_DIR = 'modules/app-services/.../registry/operations'` as the set of Operation DECLARATION
sites. After the move that directory no longer contained `AgentToolsOperationCatalog`, and the gate
went red with:

> `axis 'capabilityFamily' is Optional and no catalog declares a value for it — every construction
> site passes Optional.empty().`

Which is false. `capabilityFamily` is declared twice, on `core.ingest-files` and
`core.file-operations`; the gate had simply lost sight of the only file that declares it. This is
879's own gate — the one built to refuse "declared ahead of its consumer" — reporting an absence
that was really an invisibility. A path literal that outlives its subject does not go quiet; it
starts asserting.

Fixed at the root rather than by adding a second literal: `CATALOG_DIRS` is now a list, filtered to
`*OperationCatalog.java` so a directory may hold non-catalog code without contributing, and the
reader-scan exclusion is derived from **the same computed set** instead of an independently
maintained path substring. One authority for "which files are declaration sites".

One deliberate non-change while doing it: the original exclusion `file.includes('/registry/operations/')`
also matched that tree's `handlers/` subdirectory, so the app-services handlers were excluded from
the reader scan too. Narrowing it to catalogs-only would have ADMITTED them as readers — a genuine
weakening of the gate, arriving as a side effect of a re-homing. The original clause is therefore
kept verbatim alongside the new set clause, and whether handlers should count as readers is routed
(§C.2) rather than decided here.

### B.3 Move 2b — one fewer holder

`SubstratePhase.runWithOutcome`'s five agent-tool parameters (`:97-101`) collapse into the single
existing `AgentToolFactory.Output` record: 22 params → 18. This is the only part of the "six holders,
bundle un/re-bundled twice" finding that survived §A.2 — the bundle is already carried as one
component through `ServicePhase` (868 §B.2), `HeadAssembly` already holds no tool fields, and the
double-assemble does not exist. What remains is one signature that re-spreads a record which was
already bundled.

Carried with an explicit abandon rule — if it needed a guardrail pin changed or spread past its named
call sites it would be dropped, because B.1 and B.2 are the task and this is a nice-to-have. It did
not need either: `CompositionRootGuardrailsTest`'s three pins (`MAX_OUTPUT_FIELDS` 26, `MAX_PHASES` 8,
`MAX_LATE_BINDINGS` 5) are untouched, and the change stayed inside `SubstratePhase`,
`AgentToolHandlers`, `HeadAssembly` and three test call sites. `HeadAssembly` also loses five
constructor locals, the five `*Final` lambda-capture copies and four imports, which is the finding-3
"un/re-bundled" residue actually disappearing rather than being re-shaped.

### B.4 Docs

`22-agent-system-architecture.md` (the module/location rows and §Safe Extension Path),
`api-contract-map.md` (the `AgentToolsOperationCatalog` path literal, and the byte-stability claim now
that a real baseline exists), `common-workflows.md` (the add-an-agent-tool recipe), and
`19-module-architecture.md` (the `app-agent-api` and `app-agent` cells).

The Safe Extension Path drops from 6 steps to 4, and that reduction is the whole tempdoc in one
artifact: "add the operation definition" and "implement the operation handler" were two steps in two
modules; they are now one step at one location, and the approval/safety metadata that was step 4 is a
field of the `OperationPolicy` in that same entry.

### B.5 The two dead channels — deliberately NOT deleted here, with the evidence to decide

Both are confirmed dead in production (§A.1, rows 6a–6d). Neither is deleted in this tempdoc, and the
reason is not squeamishness about diff size.

**`vop_*` virtual operations.** The channel is not merely unreferenced code. `POST`/`GET
/api/chat/agent/virtual-operations` and `POST /api/chat/agent/tool-result` are **live HTTP surfaces**
(`AgentRoutes.java:59-66`) and the FE **calls them on every boot** (`main.jsx:361`,
`VirtualToolDispatcher.ts:68`). Deleting them is an API change plus an FE change across ~25 files —
the opposite of this tempdoc's stated constraint. More decisively, the decision already has an owner
and a home: `docs/tempdocs/532-virtual-operation-catalog-ship-or-retract.md` is `status: open` and is
*exactly* this fork ("ship with a real consumer, or retract", `:36`). Its own sequencing (`:81`) was
"533 first, this resolved second"; 533 shipped agent-verb-free (`533:59, :110`), which does select
retract — but recording that is 532's call, not a side effect of a re-homing PR. **Routed to 532 with
the full evidence chain**, per `log-pre-existing-issues`.

Two things found on the way that 532 should have:

- The FE→store publish path IS live and its result DOES surface on `GET /api/chat/agent/tools`
  (`AgentToolsController.java:65-68`), while the model never sees virtual tools because the emitter's
  store is null. So the trust panel and the model's real offering can disagree — the exact defect
  class 876 §B.1 exists to close. They agree today only because the published list is always empty.
- `AgentStepRunner.java:842` routes `vop_*` **before** the 875 authorization check at `:899`, so a
  hallucinated `vop_` name bypasses `isAuthorizedThisIteration` and parks the loop for
  `AgentTimeouts.virtualToolMs()` waiting on an FE that was never asked to dispatch it.

**E0a / multi-agent handoff.** Dead with `agentProfiles == []` (§A.1 row 6d), but the retraction is a
product decision about whether multi-agent profiles return. The originating tempdoc
(`211-multi-agent-handoff-m0.md`) is `status: done` on the retired React stack, with three
recommendations deferred to unnamed successors; there is no live plan and no live owner. Recorded in
§C for an owner call rather than routed.

This is a recorded disagreement with the handed-over "default to delete", not a silent skip. The
default was written before it was known that both channels have live HTTP/FE surface and that one has
an open owner-decision tempdoc. Deleting either would make this a product retraction wearing a
refactor's title.

### B.6 The loop decomposition — bounded, and honest about the bound

`executeIteration` is 934 lines with 14 exits, 44 session mutations, and locals alive across five
sections (`tools`, declared `:197`, is read as late as `:899`). "Cut along the section comments" is
not available: the sections short-circuit and mutate shared state, and `AgentGroundingSeamAuditTest`
pins the method NAME as the only legal home for the grounding stamp (§A.4).

The strategy is §T.4(a) — **extract the decision, leave the effect**: a gate becomes a unit returning
a verdict record, and the caller keeps `if (verdict.stop()) return terminated(...)` textually where it
is, so control flow is unchanged by construction rather than by test coverage.

`HeldGate<T>` is scoped down hard, and §A says why. The two singleton gates are **not** verbatim
duplicates:

- the context gate carries a third field `contextGateFired` (`AgentSession.java:845`, set `:856`) that
  is deliberately **non-volatile** while its two siblings on the same gate are `volatile`;
- the timeout fallbacks differ — context → `CONTINUE` **plus** a `PHASE_CONTEXT_GATE_UNANSWERED`
  narration (`AgentStepRunner.java:344-346, 371-378`); budget → `FINALIZE`, silent (`:499-500`);
- the timeout values differ (`AgentTimeouts.contextGateMs()` vs `budgetGateMs()`);
- the background-run guard wraps gate *creation* for budget (`:483`) but sits inside the *trigger
  predicate* for context (`:304`).

The map-keyed pair differs too: approval carries a `PendingGate` record with `detail` +
`sinceEpochMs` feeding `parkSnapshot()` (`AgentSession.java:47-50, :745-753`), while the virtual-tool
gate is a bare future map (`:59-60`) invisible to `parkSnapshot()`, and `cancel()` clears one map but
not the other (`:283` vs `:288`).

So a uniform primitive over all four would silently change a memory model, two timeout behaviours and
a park-visibility surface — the "looks like duplication, is not duplication" trap §T.5 flagged, now
with line numbers. What IS safe is the `resolve` / `held` / `clear` triple of the two singletons,
which is byte-equivalent modulo the type parameter. Unifying `create` needs an explicit hook for
`contextGateFired`; unifying the map-keyed pair needs a decision about whether the virtual-tool gate
should appear in `parkSnapshot()` — a behaviour change, and therefore out of scope by this tempdoc's
own rule.

## Plan

Ordered by reversibility, so the tempdoc has landed value even if a later item proves too large.
Each numbered item is its own commit, so the deletion-shaped work is reviewable and revertible apart
from the re-homing.

| # | Item | Files | Verification |
|---|---|---|---|
| 0 | **Oracle**: `AgentToolCatalogBaselineTest` + `agent-tools-wire-baseline.json` | 2 new | `:modules:app-services:test --tests "*AgentToolCatalogBaselineTest*"` green with the generated baseline installed |
| 1 | **Move 1**: five tools `implements OperationHandler`; delete 5 adapters + 4 adapter tests; rewire `AgentToolHandlers`; sweep the four tools' javadoc | 5 tools, 1 wiring file, 9 deletions | `spotlessApply` → `build -x test` → `:modules:app-agent:test :modules:app-services:test`; the oracle must stay green untouched |
| 2 | **Move 2**: `git mv` the catalog to `io.justsearch.agent.tools`; update 4 main + ~13 test imports; fix cross-module `{@link}`s; update `consult-register.v1.json` `pathIncludes` + recipe prose | ~20 files | full `test`; `check-live-witness` (RegistrySnapshotExporter edited); `--gate register-guard-resolution`; `check-tempdoc-numbers` |
| 3 | **Move 2b** (optional, abandon rule): collapse `SubstratePhase`'s five tool params into `AgentToolFactory.Output`, 22 → 18 | `SubstratePhase`, `AgentToolHandlers`, `HeadAssembly`, 3 test call sites | full `test`; `CompositionRootGuardrailsTest` pins unchanged |
| 4 | **Docs**: `22-agent-system-architecture.md` (rows + §Safe Extension Path 6→4), `api-contract-map.md` (path literal + byte-stability claim), `common-workflows.md` (recipe 6→4), `19-module-architecture.md` (two cells) | 4 docs | grep for stale paths; no `AgentToolEmitter` renamed |
| 5 | **Ride-alongs**: `.claude/rules/agent-lessons.md` line + declared budget bump; `CompositionRootGuardrailsTest` javadoc "26 components" → 21 | 3 files | `check-always-loaded-budget` |
| — | **Not done**: i18n split (§A.6), roots fold (§A.5), `vop_*` retraction (§B.5), E0a retraction (§B.5) | — | recorded, with the evidence to decide |
| 6 | **`HeldGate<T>`** (§B.6, the safe subset only): the two singleton gates delegate arm/resolve/held/clear to one private primitive; every public method keeps its exact signature, so no caller changes | `AgentSession` only | `:modules:app-agent:test` (`AgentSessionBudgetTest` covers both gates); full `test` |
| — | **Not done**: the `executeIteration` split — see §C.2b for the section map and the reason | — | groundwork delivered instead |

Teardown of what this orphans: the five adapter classes and their four tests are deleted, not
deprecated; the `consult-register` path trigger and recipe prose are corrected in the same commit as
the move; every doc sentence naming the old module or the six-step extension path is rewritten rather
than annotated. Nothing is left behind with a "superseded" comment.

Verification list (all must pass before the PR is called ready):
`gradle-locked.sh spotlessApply` · `gradle-locked.sh build -x test` · `gradle-locked.sh test` (full
suite) · `node scripts/ci/check-tempdoc-numbers.mjs` · `node scripts/ci/check-always-loaded-budget.mjs`
· `node scripts/ci/check-live-witness.mjs` · `node scripts/governance/run.mjs --gate
register-guard-resolution --mode gate` · `node scripts/ci/preview-squash-message.mjs`.

No `modules/ui-web/src` change is made, so the ui-web gate recipe does not apply. No live-stack run is
required by the design — but see §C for what a live run would add.

## §D. What shipped

| Item | Status |
|---|---|
| B.0 Oracle (`AgentToolCatalogBaselineTest` + baseline resource) | shipped; observed red before green |
| B.1 Move 1 — five tools `implements OperationHandler`, five adapters + four adapter tests deleted | shipped |
| B.2 Move 2 — catalog re-homed to `io.justsearch.agent.tools`; ~20 importers updated; consult-register `pathIncludes` + recipe corrected | shipped |
| B.2 sweep — `check-policy-axis-liveness.mjs` catalog-directory authority | shipped (found red, fixed at root) |
| B.4 Docs — 4 canonical docs; Safe Extension Path 6 steps → 4 | shipped |
| B.6 `HeldGate<T>` — the two singleton gates' arm/resolve/held/clear, public API unchanged | shipped |
| Ride-along — `agent-lessons.md` class-scanner-timeout lesson + declared budget bump | shipped |
| B.3 Move 2b — `SubstratePhase` 22 → 18 params; `HeadAssembly` loses five locals, five lambda-capture finals and four imports | shipped (not abandoned) |
| A.5 roots fold · A.6 i18n split · B.5 both channel retractions · B.6 `executeIteration` split | deliberately not done — reasons and evidence in each section |

### D.1 Behaviour-preservation argument, stated so it can be checked

The claim is not "the tests pass". It is that each change is behaviour-preserving *by construction*,
with the tests as corroboration:

1. **Move 1.** The five tools already declared `public OperationResult execute(String)` with
   matching signatures; `implements OperationHandler` adds no method and overrides no default that
   the adapters were not already inheriting. The one place handler identity was observable is
   `OperationHandler.java:58`'s `"Undo not supported by " + getClass().getSimpleName()`, and that is
   unreachable for the four non-undo tools because `OperationExecutorImpl.java:531` checks
   `op.policy().undoSupported()` before delegating — only `core.file-operations` declares it, and
   `FileOperationsTool` overrides `undo`. The adapters' `Objects.requireNonNull(tool)` is not lost
   either: `HandlerRegistry.register` does `Objects.requireNonNull(handler, "handler")` one frame
   later, and `AgentToolFactory.assemble` constructs all four unconditionally anyway.
2. **Move 2.** A package move changes no value in the catalog. `Presentation.forId` derives I18n keys
   from the `OperationRef`, not the declaring class, so keys are stable; the wire baseline and the
   policy assertions pin the rest. No module edge is added (app-services already depended on
   app-agent), and no ArchUnit rule names `io.justsearch.agent..`.
3. **`HeldGate<T>`.** Every public method keeps its exact signature and body semantics: `arm()`
   stamps `sinceEpochMs` as `create*Gate` did, `resolve()` nulls the field *before* completing the
   future in the same order as the originals, `clear()` nulls the field and deliberately leaves
   `sinceEpochMs` stale exactly as `clearBudgetGate` did, and both inner fields stay `volatile`.
   `contextGateFired = true` remains in `createContextGate`, outside the primitive. No caller
   changed, so `AgentStepRunner` is untouched and `AgentGroundingSeamAuditTest` is unaffected.

### D.2 Critical-analysis pass

Run against the diff before review, per `rule:critical-analysis-pass`:

- **Wrong-gate / wrong-flag.** Checked the null-guard chain in `AgentToolHandlers` (above), the
  `undoSupported` gate at `OperationExecutorImpl.java:531`, and the field-order in
  `HeldGate.resolve`. Also re-ran `check-policy-axis-liveness` — which is how the broken
  `CATALOG_DIR` was found rather than discovered by CI.
- **Unverified audit claims.** One audit conclusion was rejected outright after a one-command check
  (§A.3); two more were marked STALE and the plan changed accordingly (§A.2). No load-bearing claim
  in §B rests on an audit assertion I did not read the source for.
- **Test precision.** The oracle's wire half is a golden file and therefore trivially green at
  creation — so it was seeded with `[ ]`, observed failing with the real projection, and only then
  installed (§B.0). Its policy half is hand-written from reading the catalog, so it is an
  independent statement rather than a recording of current output. The four deleted adapter tests
  were tautologies (mock the tool, stub `execute`, assert the adapter returned the stub); deleting
  them removes no assertion about tool behaviour.
- **Widening a gate as a side effect.** Caught one: rewriting the policy-axis reader exclusion from a
  path substring to a computed catalog set would have admitted the app-services handlers as readers.
  The original clause was kept verbatim alongside the new one and the question routed (§C.2).
- **A golden file that would have passed only on the machine that made it.** Jackson's pretty printer
  emits the platform line separator, so the generated baseline was CRLF — and git's warning at
  `git add` (`CRLF will be replaced by LF the next time Git touches it`) is the whole bug: the
  committed file is LF, so every fresh checkout, every other developer and CI would have compared LF
  against CRLF and failed. Caught before the commit landed. Fixed at the root rather than with a
  `.gitattributes` exemption: the test normalizes line endings on both sides and the baseline is
  stored LF, so the artifact is platform-neutral by construction. A wire baseline that depends on the
  OS that generated it is not a baseline.
- **Null-tolerance narrowed by a signature collapse.** Move 2b's `registerEager(HandlerRegistry,
  AgentToolFactory.Output)` dereferences the record without a null check, where the old five-argument
  form would have accepted five nulls and skipped. Checked rather than assumed: `AgentToolFactory.build`
  returns `new Output(null, …)` on the degraded path and `assemble(...)` otherwise — never null
  (`AgentToolFactory.java:55-70`) — and `ServicePhase.java:250` assigns that result straight into
  `ServicePhase.Output.agentTools`, so `serviceOut.agentTools()` cannot be null at
  `SubstratePhase.java:163`. The per-tool null guards inside the method are unchanged, which is what
  the degraded boot actually relies on.
- **The environmental red was tested, not asserted.** The full-suite run finished with 9 failures
  across three `worker-services` classes and one in `worker-core`, every one a
  `TimeoutException … after 30 seconds`. Rather than wave at "known flake": the three
  worker-services classes were re-run isolated (BUILD SUCCESSFUL in 10s, zero failures), and the
  structural argument was checked — `modules/worker-services/build.gradle.kts:12-26` and
  `modules/worker-core/build.gradle.kts:9-14` declare no dependency on `app-agent` or `app-services`,
  so no edit in this diff can reach those tests. The worker-core one reproduces isolated and matches
  `expected-state.v1.json`'s existing `worker-core-onnx-longdoc-forensic-timeout` pin verbatim; the
  three worker-services classes had no pin, so one was added
  (`worker-services-search-suite-30s-timeout-under-load`, dated, with an `exitProbe`).

## §C. Open items

Recorded here because they were found by this tempdoc's investigation and are not this tempdoc's to
decide. Each is stated with enough evidence that the next agent does not have to re-derive it.

### C.1 Owner decisions this tempdoc deliberately did not make

- [ ] **`vop_*` virtual-operation channel: ship or retract.** Confirmed dead end-to-end for the model
      (§A.1 rows 6a–6b) but with live HTTP surface and a live FE publisher (§B.5). **Routed to
      `docs/tempdocs/532-virtual-operation-catalog-ship-or-retract.md`**, which is `status: open` and
      is exactly this fork; its stated precondition (533 shipping agent-verb-free) has fired.
      Retraction spans ~25 files across FE and BE — it is a product decision, not a refactor.
- [ ] **E0a / multi-agent handoff: keep or retract.** Dead while `agentProfiles` is hard-coded `[]`
      (§A.1 row 6d). The originating tempdoc 211 is `status: done` on the retired React stack and
      names no successor for its deferred recommendations, so there is no live owner. Needs an owner
      call on whether multi-agent profiles return before ~1,100 lines of `AgentStepRunner` handoff
      path can be swept.
- [ ] **`FileOperationExecutor` roots shape.** Not folded into `RootsView`; see §A.5 for why (cost on
      a hot path, `Path` vs `String` normalization, and two deliberately separate jobs documented at
      `AgentToolFactory.java:127-129`). If it is ever folded, the fold must preserve the sandbox
      boundary as a distinct concept from name resolution.

### C.2 Defects found while investigating, not caused by this work

- [ ] `AgentStepRunner.java:842` routes `vop_*` **before** the 875 authorization check at `:899`, so a
      hallucinated `vop_` tool name bypasses `isAuthorizedThisIteration` and parks the run for
      `AgentTimeouts.virtualToolMs()` awaiting an FE dispatch that was never requested. Belongs to
      532 if the channel is kept, and disappears if it is retracted.
- [ ] The trust panel (`GET /api/chat/agent/tools`, `AgentToolsController.java:65-68`) merges the
      virtual store while the model's offering never does (emitter store is null). Two views of "what
      the model is offered" that can disagree — the defect class 876 §B.1 exists to close. They agree
      today only because the published list is always empty.
- [ ] `modules/app-agent/src/test/java/io/justsearch/agent/AgentTimeoutsTest.java:224` cites literal
      source line numbers (`AgentStepRunner:236/335/487/1138`) that have already drifted (current:
      236, 343, 498, 1315). A comment that cites line numbers in a 900-line method is a maintenance
      trap; it should cite symbols.
- [ ] `docs/tempdocs/533-first-plugin-scaffold.md:110` states `VirtualOperationCatalog` "is not
      booted"; it is (`modules/ui-web/src/main.jsx:357`). Dated tempdoc history, left as written per
      `tempdocs-are-dated-history`, but noted so 532 does not inherit the error.
- [ ] `scripts/ci/check-policy-axis-liveness.mjs` excludes the whole `/registry/operations/` subtree
      from its READER scan, which the substring also applies to that tree's `handlers/` directory.
      Handlers are implementations, not declaration sites, so they arguably should be eligible as
      readers. Left exactly as it was here (§B.2) because narrowing it is a gate-widening decision
      on its own merits, not a consequence of moving a file. Owner of the axis-liveness rule (879)
      should decide.

### C.2b Groundwork for the `executeIteration` decomposition (the map, so the next pass starts here)

The expensive part of decomposing a 934-line method is not the editing — it is knowing where the
seams are and which locals cross them. That analysis is done and recorded here so a follow-up does
not repeat it. Line numbers are as of `f3b29de5` + this tempdoc's changes (which do not touch
`AgentStepRunner`).

`AgentStepRunner.executeIteration` — `:132-1065`, 934 lines, 14 `return`s, 0 `throw`s.

| § | Section | Lines | Can exit? |
|---|---|---|---|
| S1 | Cancel check | 140–152 | yes → `terminated(false)` `:151` |
| S2 | Steering drain | 154–170 | no |
| S3 | Iteration counter | 172 | no |
| S4 | Loop-block escalation | 174–189 | yes `:188` |
| S5 | Compression record | 191 | no |
| S6 | **Tool-list build** (produces `agentState` `:195`, `tools` `:197`) | 193–211 | no |
| S7 | Checkpoint READY_FOR_LLM | 213 | no |
| S8 | Zero-observer park | 215–243 | no |
| S9 | **Token projection + budget snapshot** (produces `projectedTokens` `:250`, `tokens` `:252`, `budgetSnapshot` `:253`, `budgetExhausted` `:254`) | 245–276 | no |
| S10 | Budget-raise narration | 278–283 | no |
| S11 | **Context-pressure gate + compaction** (reassigns `tokens` `:450`, `budgetExhausted` `:452`) | 285–456 | yes `:364` |
| S12 | **Budget gate** | 458–581 | yes `:520, :556, :579` |
| S13 | LLM call (produces `result` `:589`) | 584–612 | yes `:611` |
| S14 | Handoff escalation (reassigns `result` `:641`) | 614–647 | no |
| S15 | Text-only terminal | 649–676 | yes `:666, :675` |
| S16 | Handoff pre-scan + assistant append (produces `toolCalls` `:680`) | 678–695 | no |
| S17 | Batch announce | 697–712 | no |
| S18 | **Per-call dispatch loop** | 713–1062 | — |
| S18a | ↳ cancel re-check | 716–728 | yes `:727` |
| S18b | ↳ handoff execution (in-place mutation of `session.messages()` `:816-827`) | 730–833 | yes `:748, :769` |
| S18c | ↳ `vop_*` virtual dispatch | 835–845 | no |
| S18d | ↳ resolution + recovery policy | 847–871 | yes `:870` |
| S18e | ↳ offering authorization (875 Move 3) — reads `tools` from S6 | 873–920 | no |
| S18f | ↳ safety gate | 922–937 | no |
| S18g | ↳ loop guard | 941–965 | no |
| S18h | ↳ execute + lineage + **grounding stamp** `:1024-1027` + append | 967–1061 | no |
| S19 | Continue | 1064 | `cont()` |

**The seams that resist extraction**, i.e. locals produced in one section and consumed much later:

- `tools` (`:197`, S6) is read at `:250` (S9), `:448` (S11), `:595`/`:598` (S13) and `:899` (S18e).
  The 875 authorization check needs the very list the tool-list builder produced 700 lines earlier —
  this is the hardest single seam.
- `tokens` / `budgetExhausted` / `budgetSnapshot` (S9) are *reassigned* inside S11 (`:450`, `:452`)
  and then read throughout S12. S9–S12 are one nested `if (projectedTokens.isPresent())` block
  spanning `:251-582`.
- `result` (`:589`, S13) is reassigned in S14 (`:641`) and read by S15 and S16.

**Two hard constraints** (§A.4): `AgentGroundingSeamAuditTest:175-176` pins `"executeIteration"` as
one of only two legal homes for the grounding stamp, so S18h cannot move without an argued change to
that seam list; and `:816-827` structurally mutates the live `session.messages()` list, so any split
must preserve list identity rather than pass a copy.

**Recommended first cut** (§T.4a, extract the decision and leave the effect): S12 (budget gate, 123
lines) then S11 (context gate + compaction, 171 lines) — both far from the grounding seam, both
naturally shaped as "evaluate → verdict record → caller acts". `AgentLoopServiceTest` (5239 LOC, 121
tests) constructs `AgentStepRunner` **zero** times and calls `executeIteration` **zero** times — every
test drives it through `AgentLoopService` — so no test binds to the method's internal shape. That is
the fact that makes the decomposition tractable at all.

**Not attempted in this tempdoc.** The re-homing is done and green; a 934-line method with 14 exits
and 44 session mutations, under a hard zero-behaviour-change constraint, is a separate piece of work
whose honest cost is days, not the tail of a refactor PR. Starting it here and stopping half-way
would leave the method in a worse state than either end. The map above is the deliverable that makes
the next attempt cheap.

### C.2c A note on how these findings were produced

Three of the seven handed-over findings were STALE and one was flatly REJECTED (§A.2, §A.3). That is
not a complaint about the review that produced them — it is the expected decay rate for `file:line`
claims across five merged PRs touching the same files, and it is why the brief's instruction to
re-verify everything against the post-merge tree was the single most valuable line in it. The cost of
re-verification was two parallel read-only audits; the cost of skipping it would have been a rename
sweep that broke four canonical docs to "fix" a class that exists, plus a composition-path
refactor aimed at a double-`assemble` that does not happen.

The corollary is the one worth carrying forward: an audit's conclusion is a hypothesis with a
location attached. The `AgentToolEmitter` rejection took one `ls` to settle.

### C.3 What a live-stack run would add

Nothing in this tempdoc's design requires one — the change is structural and the oracle pins the
declaration. The one thing a live run would confirm that static verification cannot: that the
delegate is still offered the same seven tools by name at runtime, via the `Agent tools offered` log
that 868 §C added. That is a cheap confirmation, not a gap in the argument, because the registration
refs are unchanged by construction (only the handler class identities change) and the wire projection
is byte-pinned.
