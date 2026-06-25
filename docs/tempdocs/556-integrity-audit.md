---
title: "556 — Breadth integrity audit: doc↔code drift, concept-forking, the dead-code graveyard, and cross-worktree coordination"
type: tempdocs
status: done
created: 2026-05-28
category: integrity-audit / docs / architecture / governance / process
related:
  - tempdoc 553 (canonical search-execution record) — named the "representation drift" defect class C2 generalizes
  - tempdoc 367 (legacy code audit, ~17,800 lines removed) — the largest single graveyard event
  - tempdoc 530/531 (discipline-gate kernel + consumer-drift) — the governance home for drift prevention
  - tempdoc 379 (canonical doc refresh) + /doc-audit skill — prior doc-drift sweeps
  - tempdoc 519/502 (AppFacade/EngineState dissolution) — sources of stale doc references
  - tempdoc 320 (LuceneIndexRuntime decomposition) / 314 (RuntimeConfig deletion) — graveyard + doc-drift sources
  - .claude/rules/tier-register.md — the prose-vs-enforced tiering this audit's findings feed
method: |
  Markers: [V] = verified against repo source at the cited path on 2026-05-28; [I] = inferred/judgment.
  Scope: the main worktree (F:\JustSearch) is the audited tree. Sibling worktrees
  (507-kernel-boundary, 548-followups, 550-impl) are treated as read-only context for C4 only.
  third_party/llama.cpp is excluded from all sub-audits (vendored upstream).
---

# 556 — Breadth integrity audit

> **What this document is.** A diagnostic produced from an overview vantage that has ingested the full
> commit history, the module topology, and the canonical docs. It looks for integrity defects that are
> only visible across the breadth: documentation that describes code that no longer exists (C1), single
> concepts that have silently forked into multiple representations (C2), subsystems built then abandoned
> (C3), and the coordination cost of parallel-agent worktrees (C4). Each finding is a `F-<sub><n>` row
> with evidence and a severity. **No fixes are applied in this doc** — fix-worthy findings are flagged
> for a future scoped slice or for `docs/observations.md`.

## 0. Severity scale

| Severity | Meaning |
|---|---|
| **S1 — misleading** | A reader/agent acting on this would be actively wrong (doc describes deleted code as current; two forked records silently disagree). |
| **S2 — stale-but-harmless** | Out of date but unlikely to cause a wrong action (historical reference, dead allowlist entry). |
| **S3 — smell** | Not wrong today, but a trend that erodes integrity if unchecked (growing allowlist, rising class-size pin, recurring collision). |

---

## C1 — Doc ↔ code drift

**Thesis (verified):** the canonical "must-not-drift" docs track *features* well but **lag the largest
structural refactors**. Every S1 below traces to one of the four biggest 2026 architecture moves —
`LuceneIndexRuntime` deletion (320), `RuntimeConfig` deletion (314), `AppFacade` dissolution (502/519),
and the `/api/agent/* → /api/chat/*` namespace migration (491). The docs describe the *pre-refactor*
world as current.

**Deletions verified at audit time** (all `find modules -name … → empty`): `LuceneIndexRuntime.java`,
`RuntimeConfig.java`, `AppFacade.java`, `DefaultAppFacade.java`, `SearchPipelineExecutor*.java`,
`SearchStageType*.java` — **all gone. [V]** Current agent routes are `/api/chat/agent/*` and
`/api/chat/sessions/*` (`modules/ui/src/main/java/io/justsearch/ui/api/AgentRoutes.java`). **[V]**
**Not drift (correctly excluded):** the FFM references in `02/08/16` — FFM is still live for MMF
signalling (`MainSignalBus`/`MmfWorkerSignalBus`) and NVML; only the *llama.cpp* binding was removed. **[V]**

| ID | Sev | Where (canonical) | Drift | Current reality |
|---|---|---|---|---|
| **F-C1.1** | **S1** | `explanation/03-knowledge-server.md:25`, `04-storage-engine.md:117,142,166`, `18-adapters-lucene-deep-dive.md:29,254,274,571,605`, `23-search-pipeline-overview.md:240` + `reference/issues/search-accessibility.md:45` (`LuceneIndexRuntime.java:2037`) | Describes `LuceneIndexRuntime` as the **live Lucene runtime facade** ("writes IndexDocument to LuceneIndexRuntime", "enforces the physical write", "@ThreadSafe", file paths). `18-…` is structured *entirely* around the facade. | **Deleted in tempdoc 320** (facade eliminated). Successors: `ReadPathOps`/`WritePathOps`/`TextQueryOps`/`RuntimeSession`/`IndexingCoordinator`/`LuceneLifecycleManager`. **[V]** |
| **F-C1.2** | **S1** | `reference/api-contract-map.md:212–224,751` | Lists the **entire** agent API under `/api/agent/run/stream`, `/api/agent/approve`, `/api/agent/tools`, `/api/agent/session/{id}/events`, `/api/agent/undo`, `/api/agent/history`, … as current. | Migrated to `/api/chat/agent/*` and `/api/chat/sessions/*` in **491** (shape also changed: `session`→`sessions`; `run/stream`→`/api/chat/agent`). The canonical *API contract reference* is wrong about the prefix for the whole agent surface. **[V]** |
| **F-C1.3** | **S1** | `explanation/19-module-architecture.md:41,164,183` | `DefaultAppFacade` named the "orchestration hub"; `AppFacade → DefaultAppFacade` mapping; "`DefaultAppFacade.java`: Delegation + DTO mapping". | **Both deleted in 502/519** — `HeadAssembly` + typed service records (`ServiceGraph`/`SubstrateGraph`/`CapabilityGraph`) replace the facade. **[V]** |
| **F-C1.4** | **S1** | `explanation/04-storage-engine.md:16`, `11-index-schema-migration.md:63` | "The effective index root is `RuntimeConfig.indexBasePath()`" — instructs the reader to call a deleted class. | **`RuntimeConfig.java` deleted in 314**; `ConfigStore`/`ResolvedConfig` are the source. **[V]** |
| **F-C1.5** | **S2** | `explanation/17-ai-bridge-deep-dive.md` (whole file) + `18-…:610` cross-link + `docs/llms.txt` index entry | Half-migrated: the body now *self-warns* ("Older documentation referred to an in-process … `ai-bridge` … obsolete", lines 34–45) — good — but the doc still exists under the **old module name**, `18` links to it as current "AI/embedding integration", and llms.txt advertises it as "AI Bridge Deep Dive". Module is `ai-backend` now. | `ai-bridge` renamed `ai-backend` (367/406); `gpu-bridge`/`prompt-support` split out. The deprecation note softens this to S2. **[V]** |
| **F-C1.6** | **S2** | `explanation/09-testing-strategy.md:15` | Example test classes `SearchStageTypeTest`, `SearchPipelineExecutorStateTest`. | Pipeline `StageType`/`SearchPipelineExecutor` deleted (221/313); those test classes no longer exist. **[V]** |
| **F-C1.7** | **S2/S3** | `reference/issues/backend-tech-debt.md:30,36,61,76`, `reference/contributing/class-size-standard.md:123,132`, `configuration/environment-variables.md:15` | Current-tense debt/standards tables cite `LuceneIndexRuntime.java` (LOC, "Already decomposed"), `RuntimeConfig` (1,766→954, 8 factories), "RuntimeConfig YAML keys". | Both classes are now *deleted*, not merely decomposed. These read as historical records embedded in current-state tables — defensible as history, but they name files that no longer exist. **[V]** |

**Magnitude.** The drift is **clustered, not scattered**: it sits almost entirely in the four
storage/architecture/API docs (`04`, `18`, `19`, `23`, `api-contract-map`) plus two issue/standard
records. Feature-level docs (search quality, observability, agent system) read as current. So the
canonical-truth guarantee held for incremental work and broke specifically on the *whole-class-deletion*
refactors — which is exactly where a doc-drift gate would pay for itself (none currently enforces
class-existence of cited symbols; `docsApiDriftCheck` only guards removed *endpoints*, and even it
missed F-C1.2 because the route still exists under a new prefix).

**Recommended disposition:** F-C1.1/.2/.3/.4 → fix-worthy (a focused canonical-refresh slice, à la
tempdoc 379); F-C1.2 additionally suggests extending `docsApiDriftCheck` to assert the *prefix*, not
just absence. F-C1.5/.6/.7 → lower priority; fold into the same refresh.

## C2 — Concept-forking / representation drift

**Thesis (verified).** The repo is *unusually self-aware* of representation drift — it **named the
defect class** (553) and has a strong, verifiable record of detecting and collapsing single-source forks.
But the audit finds the live risk has shifted to a **second, unguarded class**: not "one source modelled
twice" (DRY violation) but **one *word* reused for many concepts** (vocabulary overload). The
drift-prevention machinery targets the first class; nothing guards the second — which is where the
repo's most recent documented bug actually came from.

### C2.1 — Two distinct fork classes

- **(i) Single-source forks (DRY violations)** — the same knowledge modelled in N independent places.
  This is what 553/549/531 fight, and the repo **handles it well** (see C2.4).
- **(ii) Vocabulary overload (polysemy)** — N genuinely-different concepts sharing one domain noun.
  *Not* a DRY violation (nothing to unify), but a clarity hazard: a reader/agent searching the noun
  gets unrelated hits and can wire the wrong one. **No guard exists for this class.**

### C2.2 — `Capability`: ~5 concepts, one word (S3, with a prior real incident)

Verified distinct meanings carrying the `Capability` token: **[V]**

| Meaning | Types | Domain |
|---|---|---|
| **Readiness/health** ("is subsystem X up?") | `app-api/lifecycle/Capability` (+`CapabilityHealth`), `app-services/lifecycle/WorkerCapability`, `InferenceCapability`, bootstrap `CapabilityGraph`/`CapabilityPhase`/`CapabilityHealthBridge` | lifecycle gating |
| **Operation-gating** ("op needs readiness X") | `app-agent-api/RequiredCapability` | built *on* readiness — coherent with row 1 |
| **Model capability profile** ("what can the LLM do?") | `ai-backend/CapabilityProfile` (`contextLength`, `maxThroughput`, `securityTier`, `List<String> capabilities`) | inference model features |
| **Wire feature-advertisement** ("does the server support X?") | proto `I18nCapability`, `StreamingEnvelopeCapability`, `serverCapabilities` handshake | contract/handshake |
| **Plugin host permission** ("what may this plugin call?") | `PluginHostApi` / `host.*` sub-capabilities / trust attenuation | plugin sandbox |

Verified two are unrelated concepts: `Capability` = *"health can be queried … gates Javalin routes …
returns 503"* vs `CapabilityProfile` = *model context/throughput/security record*. **[V]** These should
**not** be unified — they are different ideas — but the shared noun is a documented hazard: the
`standalone-capability-stays-stuck` agent-lesson postmortem is **a real bug caused by two `Capability`
instances for the same readiness concept not being bridged**. Overloading the word makes exactly that
mistake easy. **Disposition:** disambiguate names (e.g. `ReadinessCapability` / `ModelCapabilityProfile`
/ `WireCapabilityAdvertisement` / `PluginPermission`), not unify.

### C2.3 — `Provenance`: 3 concepts, one word (S3, lower blast radius)

`HitProvenanceProjector` (adapters-lucene, *which retrieval leg produced a hit*), `InvocationProvenance`
(app-agent-api, *what triggered an operation*), `Provenance` (app-agent-api, *multi-axis UI-contribution
stamp*). **[V]** Three bounded contexts, one noun. Within the UI domain, provenance reconstruction *was*
already unified to one minted authority (543 §4.3) — so the remaining issue is purely cross-domain
naming, lower severity than `Capability`.

### C2.4 — `Operation / Command / Action / Effect`: a verb cluster mid-collapse (S2)

The FE carries four coexisting verb-concepts **[V]**: `Operation*` (`OperationClient`,
`OperationCatalogClient`, `JfOperation`, `VirtualOperationCatalog`), `Command*` (`CommandPalette`,
`CommandRegistry`, `CommandPaletteProjection`), `Action*` (`ActionButton`, `ActionLedgerView`,
`ContextActionRegistry`, `SelectionActionRegistry`, `wireActionButton`), `Effect*` (`effect.ts`
substrate, `EffectLine`, `EffectAuditLog`, `PendingEffectQueue`). This **is** a type-(i) situation, and
the repo is *actively collapsing it*: 543 §21.B "Action absorbs Command + Operation projection", 550 G6
"Effect→Operation collapses to ONE logical record via executionId", 548 "one authority per concept" — as
recent as 2026-05-26/27. So it is a fork **in active, deliberate collapse**, not neglected — but it is
genuinely still mid-flight (accreting and converging at once). S2: trending the right way, not yet one
authority.

### C2.5 — The resolved-fork track record (positive control)

Evidence the type-(i) discipline works — forks the repo *detected and collapsed*: **[V]** (commit/tempdoc)
`SearchTrace` canonical record + execution-surface gate (549/553); hand-written `LifecycleState` →
proto-enum sole authority + ArchUnit ban (548 §4.1); typed-IDs → `NamespacedId`/`RegistryRef<T>` (447);
`RuntimeConfig`+factories → single `ConfigStore`/`ResolvedConfig` (314/347). The machinery is real.

### Findings

| ID | Sev | Finding |
|---|---|---|
| **F-C2.1** | **S3 + prior incident** | `Capability` overloaded across ~5 distinct concepts; the `standalone-capability-stays-stuck` postmortem is a real bug from the readiness-Capability sub-overload. Fix = disambiguate names, not unify. |
| **F-C2.2** | **S3** | `Provenance` overloaded across 3 concepts (search-leg / invocation / UI-contribution); within-UI already unified, cross-domain naming remains. |
| **F-C2.3** | **S2** | `Operation/Command/Action/Effect` verb cluster (4 FE representations) is a single-source fork in *active collapse* (543/548/550) but not yet one authority. |
| **F-C2.4** | **S3 (gap)** | **The structural finding:** drift-prevention guards single-source forks (553 execution-surface gate, 531 consumer-drift) but **nothing guards vocabulary overload** — the very class that produced the `Capability` bug. A reserved-domain-noun registry / lint (mirroring 553's register) would close it. Natural follow-up tempdoc. |

**Verdict:** type-(i) drift is the repo's strength, not its weakness — it is named, gated, and has a
collapse track record. The real, *unguarded* exposure is type-(ii) **vocabulary overload**, concentrated
on `Capability`, and it has already cost one documented bug. C2's headline recommendation is a small one:
a concept-noun guard, not more substrate.

## C3 — Dead-code graveyard

**Thesis (verified):** the *posture* is healthy — pruning is active and mechanized — but the graveyard
exposes a **build-ahead / speculative-module pattern**: ~25% of the modules ever created were deleted,
several with sub-month lifespans, and an entire abstraction family was a multi-month dead end. The repo
prunes well; it also over-builds.

### C3.1 — Module graveyard (12 deleted modules, [V] from `git --diff-filter=D modules/*/build.gradle.kts`)

| Module | Lifespan (add → del) | Days | What it was → fate |
|---|---|---|---|
| `app-pipeline` | 2026-01-14 → 02-03 | **20** | speculative pipeline module; removed unused before maturing |
| `app-plugins` | 2026-01-14 → 02-03 | **20** | speculative plugin module; the plugin substrate was rebuilt from scratch in May (477/508) |
| `ui-automation-runner` | 2025-11-11 → 12-28 | ~47 | early automation harness; superseded |
| `pipeline-schema` | 2026-02-19 → 03-16 | ~25 | created from the pipeline-engine/executor split (214), deleted a month later (313, ADR-0014) |
| `app-secrets` | 2026-01-14 → 03-28 | ~73 | the `SecretsVault` module — built, never wired to a consumer, deleted (367) |
| `events` | 2025-11-01 → 02-03 | ~94 | unused event module |
| `infra-index` | 2025-11-01 → 02-03 | ~94 | unused infra module |
| `app-ai` | 2026-01-14 → 03-29 | ~74 | collapsed into `app-inference` (367, "1-file module") |
| `pipeline-executor` | 2025-11-22 → 02-19 | ~89 | custom pipeline execution engine (214/221) |
| `pipeline-engine` | 2025-11-01 → 02-19 | ~110 | custom pipeline engine; whole family removed by ADR-0014 |
| `ai-engine-native` | 2025-11-26 → 03-10 | ~104 | the FFM/llama.cpp native build bridge; removed in the ONNX/llama-server pivot |
| `ai-worker` | 2025-11-01 → 03-29 | ~148 | the "fourth process"; never reached production, deleted (367) |

**The pipeline dead end.** `pipeline-engine` → `pipeline-executor` → `pipeline-schema` was a custom
DAG/pipeline-definition abstraction maintained Nov→Mar, then dismantled entirely (ADR-0014
"pipeline-definition-removal"). The *current* search path is a composable dispatch in
`SearchOrchestrator` (256) — i.e., the abstraction was replaced by direct code. **[V]**

### C3.2 — Class / subsystem graveyard (the big deletions, not module-level)

- **`LuceneIndexRuntime`** (the storage god-facade) — deleted, decomposed into ops (320). **[V]**
- **`RuntimeConfig` + 8 factory classes** — deleted, replaced by `ConfigStore`/`ResolvedConfig` (314). **[V]**
- **`AppFacade`/`DefaultAppFacade`** — dissolved into `HeadAssembly` + typed graphs (502/519). **[V]**
- **`EngineState` enum** — deleted, `WorkerCapability` is the sole health source (502). **[V]** (commit message)
- **`ShardCoordinator`** distributed/sharding scaffolding — deleted dead (326). The "Remote Shard SPI"
  (ADR-0011) was scaffolded then never used. **[V]** (commit message)
- **The entire React frontend** — atomically decommissioned (449/phase-11, 2026-05-07, ~29K lines), Lit
  chrome is production default. **[V]** A full UI stack built (Jan–Apr) and replaced (May).
- **The FFM/llama.cpp in-process engine** (`NativeLlamaBinding`, `GenerationActor`, etc.) — removed in
  the ONNX-embedding + external-llama-server pivot (367). **[V]**

### C3.3 — Line-count noise caveat (verified, to avoid a false "rot" reading)

The biggest single-commit *line* deletions are **not abandoned code** — they are model-file/corpus/doc
churn: `fix(364) model distribution` (~2.4M), `feat(343) multilingual model swaps` (~300K), reranker
upgrade (~282K), `remove tmp multihop corpus` (~136K), `remove 240 completed tempdocs` (~100K). Raw
deletion volume overstates code rot; the real code graveyard is the module/class lists above. **[V]**

### C3.4 — Current dead-code posture (healthy)

- **Quadruple-guarded:** the `dead-code` discipline gate (`scripts/governance/gates/dead-code/`),
  ArchUnit `UnreferencedCodeTest` (bytecode-level unreferenced private/package methods), PMD
  `UnusedFormalParameter`, and Knip for the FE. **[V]**
- `UnreferencedCodeTest.KNOWN_UNREFERENCED` allowlist = **44 entries** **[V]**, each with a documented
  reason (test-only/reflection/inheritance) and an explicit audit procedure in the Javadoc. Curated, not
  a dumping ground — but 44 and growing is the kind of number worth a periodic audit (S3-watch).

### Findings

| ID | Sev | Finding |
|---|---|---|
| **F-C3.1** | **S3** | **Speculative over-building.** 12 modules created-then-deleted (~25% churn); `app-pipeline`/`app-plugins` lived 20 days; `events`/`infra-index`/`ai-worker`/`pipeline-*` lived months unused. This is the YAGNI-violation class CLAUDE.md explicitly warns against ("YAGNI applies to speculative abstractions"). The *pruning* is the healthy half; the *build-ahead* is the cost half. |
| **F-C3.2** | **S2** | The pipeline-definition family (engine/executor/schema) was a ~4-month, 3-module dead end ultimately removed by ADR-0014 — a documented, deliberate retraction, so S2 not S1. |
| **F-C3.3** | **S3-watch** | `KNOWN_UNREFERENCED` allowlist at 44 entries; curated + audit-documented, but trending up. Worth a scheduled audit pass (the Javadoc's own "grep for callers; if none, delete" procedure). |

**Verdict:** the dead-code *machinery* is among the healthiest parts of the repo. The graveyard's signal
is not rot — it is **scope discipline lagging build velocity**: the team repeatedly builds substrates/modules
ahead of a consumer, then (correctly, via the gates) deletes them. This directly sets up C2 — speculative
substrates are where forked representations breed before they are pruned.

## C4 — Cross-worktree coordination smells

**Thesis (verified).** Parallel-agent worktree development is real, heavy, and **growing** (3 live
worktrees now; 120 merges in May alone). The repo has built strong *detection/resolution* machinery for
the resulting collisions — but the underlying friction (blind number allocation, shared mutable
baselines, long-lived worktrees) is **structural and unprevented**, so the coordination tax scales with
parallelism.

### Quantified coordination volume [V]

| Signal | Value |
|---|---|
| Total commits / merges | 4,796 / **234** (~4.9%) |
| Merge-commits by month | Feb 22 · Mar 62 · Apr 30 · **May 120** (~4/day) |
| `Merge main into worktree` catch-ups | **36** (pure integration overhead, no feature value) |
| Renumber / avoid-collision commits | **26** |
| Class-size pin commits | **40** |
| `CONFLICT-LEDGER` doc mentions | **51** |
| Live worktrees now | `main` + `507-kernel-boundary`, `548-followups`, `550-impl` |

### Findings

| ID | Sev | Finding & evidence |
|---|---|---|
| **F-C4.1** | **S3** | **Number-namespace collisions are detected, not prevented.** 26 renumber commits ("renumbered 543→546", "rename 521→523 (avoid main collision)", "renumber 412→418") + 3 *live* collisions at #550/#552/#553. `check-tempdoc-numbers.mjs` is a **post-hoc detector** — by its own message, "parallel worktrees can't see each other's in-flight numbers." A reserve-a-number registry or per-worktree number band would *prevent* rather than detect. **[V]** |
| **F-C4.2** | **S3** | **Shared mutable baselines are merge-contention points.** The class-size ratchet (40 pin commits) is a shared file parallel worktrees serialize on at merge: `KnowledgeHttpApiAdapter`'s pin was re-realigned `2118→2207→2328` (then `2093→2096`) across successive worktree merges (549/550/553). **Sub-smell:** the pin only ever moves *up* — the class is repeatedly *re-permitted to grow*, never decomposed. The ratchet is acting as a pressure-release valve, and a God-Object is reforming under it (ties to F-C3.1's build-ahead pattern). **[V]** |
| **F-C4.3** | **S2** | **Integration catch-up churn.** 36 `Merge main into worktree` commits; the May 120-merge spike. Long-lived worktrees (519 head-composition, 543-fwd, 548) re-merge `main` repeatedly — each catch-up is overhead that grows with worktree lifetime. Shorter-lived slices would cut it. **[V]** |
| **F-C4.4** | **positive** | **Detection/resolution machinery exists and is used:** `CONFLICT-LEDGER` (51 mentions — a formalized "implementer ≠ resolver" protocol), `check-tempdoc-numbers.mjs`, `bash-guard` branch-safety hooks. The 51-mention ledger size itself *quantifies* how often parallel design-work collides. **[V]** |

**Verdict:** like C3's dead-code story, the *machinery* is healthy (collisions are caught, conflicts are
ledgered, destructive git is hook-blocked). The unaddressed half is **prevention**: blind number
allocation, shared-baseline contention, and long worktree lifetimes are structural taxes that the
detection tools merely *surface* after the fact. The class-size-pin-only-goes-up sub-smell (F-C4.2) is
the one with teeth — it is a governance gate being used to *authorize* God-Object growth rather than
prevent it.

## Z. Synthesis & severity-ranked findings register

### Z.1 — All findings, ranked

| ID | Sev | One-line | Disposition |
|---|---|---|---|
| **F-C1.1** | **S1** | Storage docs (03/04/18/23) describe deleted `LuceneIndexRuntime` as the live runtime | canonical-refresh slice |
| **F-C1.2** | **S1** | `api-contract-map` lists the whole agent API under `/api/agent/*`; actual is `/api/chat/agent/*`+`/api/chat/sessions/*` | refresh + extend `docsApiDriftCheck` to assert prefix |
| **F-C1.3** | **S1** | `19-module-architecture` names deleted `DefaultAppFacade` as the orchestration hub | canonical-refresh slice |
| **F-C1.4** | **S1** | `04`/`11` instruct calling deleted `RuntimeConfig.indexBasePath()` | canonical-refresh slice |
| **F-C2.1** | **S3 + incident** | `Capability` overloaded across ~5 concepts; caused the `standalone-capability-stays-stuck` bug | disambiguate names + concept-noun guard |
| **F-C4.2** | **S3** | Class-size pin only ratchets *up* (`KnowledgeHttpApiAdapter` 2118→2328); shared-baseline merge contention | decompose the class; don't re-permit |
| **F-C3.1** | **S3** | Speculative over-building: 12 modules created-then-deleted (`app-pipeline`/`app-plugins` in 20 days) | accept-with-awareness (pruning works); YAGNI discipline |
| **F-C4.1** | **S3** | Tempdoc-number collisions detected post-hoc, not prevented (26 renumbers + 3 live) | reserve-a-number registry / per-worktree band |
| **F-C2.4** | **S3 (gap)** | No guard against vocabulary overload — the class that produced the `Capability` bug | new tempdoc: reserved-domain-noun lint |
| **F-C2.2** | **S3** | `Provenance` overloaded across 3 concepts | disambiguate (lower priority) |
| **F-C3.3** | **S3-watch** | `UnreferencedCodeTest` allowlist at 44, trending up | scheduled audit pass |
| **F-C1.5/.6/.7** | **S2** | `17-ai-bridge` self-warns but persists under old name; stale test-class examples; debt tables cite deleted files | fold into canonical refresh |
| **F-C2.3** | **S2** | `Operation/Command/Action/Effect` fork in *active* collapse (543/548/550) | continue convergence (in flight) |
| **F-C3.2** | **S2** | Pipeline-definition family was a 4-month, 3-module dead end (ADR-0014) | resolved/historical |
| **F-C4.3** | **S2** | 36 `Merge main into worktree` + May 120-merge spike; long worktree lifetimes | shorter-lived slices |
| **F-C2.5 / F-C4.4 / C3.4** | **positive** | Resolved-fork track record; CONFLICT-LEDGER + number-checker + branch hooks; quadruple-guarded dead code | — (controls confirming the machinery works) |

**Tally:** 4 × S1 (all C1 doc-drift) · 6 × S2 · 8 × S3 · 3 positive controls.

### Z.2 — The unifying pattern

A single signature explains every finding, and it maps **exactly onto the repo's own
`tier-register.md` philosophy**:

> **JustSearch enforces, at ~100%, every drift class it has *named and conceptualized* — and the
> *adjacent, un-named* classes drift at prose-tier or are entirely unguarded.**

- **Named & gated → solid:** single-source forks (553 execution-surface gate), dead code (4 guards),
  endpoint *removal* (`docsApiDriftCheck`), destructive git (`bash-guard`), consumer drift (531),
  number *collision* (post-hoc checker). The positive controls (Z.1 bottom row) all live here.
- **Un-named → drifts:** the S1/S3 findings are all *one conceptual step* outside a named guard:
  - doc *class-existence* (vs the named endpoint-removal guard) → **F-C1.1/.3/.4** (S1).
  - route *prefix* (vs endpoint *absence*) → **F-C1.2** (S1) — even `docsApiDriftCheck` missed it.
  - vocabulary *overload* (vs single-source *forking*) → **F-C2.1/.4** (S3 + a real bug).
  - number *prevention* (vs collision *detection*) → **F-C4.1**.
  - class *decomposition* (vs class-size *permission*) → **F-C4.2**.

So the audit's real product is **a map of the not-yet-named drift classes** — exactly the input the repo
turns into gates (the 530/553/555 pattern: name the class → register it → gate it).

### Z.3 — Overall integrity posture

**Healthy, with a precise and characteristic blind-spot signature.** The codebase is *not* rotting:
deletions are deliberate and guarded, conflicts are ledgered, the biggest forks have been collapsed. The
genuine, actionable exposure is narrow and concentrated:

1. **One S1 cluster** — the canonical storage/architecture/API docs lag the four biggest refactors
   (320/314/502/491). This is the only finding where a reader/agent would be *actively misled today*.
   **Highest-value fix.**
2. **One bug-bearing S3** — `Capability` vocabulary overload, with a documented prior incident.
3. **A structural prevention gap** across C2/C4 — the repo *detects* (collisions, conflicts) and
   *deletes* (dead code) well, but *prevents* (number allocation, vocabulary reuse, class growth) weakly.

### Z.4 — Recommended follow-ups (ranked, NOT executed here)

1. **Canonical-doc refresh slice** — fix F-C1.1/.2/.3/.4; extend `docsApiDriftCheck` to assert route
   *prefix* and to fail on cited-symbol non-existence (closes the S1 cluster + prevents recurrence).
2. **Reserved-domain-noun guard** (new tempdoc, mirrors 553/555) — register reserved concept nouns
   (`Capability`, `Provenance`, …); lint a new type that reuses one for an unrelated concept. Closes
   F-C2.4; the `Capability` disambiguation (F-C2.1) is its first consumer.
3. **`KnowledgeHttpApiAdapter` decomposition** (F-C4.2) — stop ratcheting the pin upward; it is a God-Object
   reforming under the gate.
4. **Number-allocation prevention** (F-C4.1) — a reserve-a-number step so worktrees never collide.
5. **Scheduled `UnreferencedCodeTest` allowlist audit** (F-C3.3) — run the Javadoc's own "grep callers,
   delete if none" procedure.

### Z.5 — Honest limits of this audit

- I read commit *subjects*, the module topology, the canonical docs, and *targeted* source — not every
  line. S1 findings were verified by confirming class/route existence directly (`find`/`grep`); S2/S3 are
  evidence-cited but carry judgment. **[I]** markers denote judgment throughout.
- C2 is the most judgment-laden: distinguishing "legitimate polysemy" from "harmful overload" is a call;
  I anchored it on a real prior incident (`Capability`) to keep it grounded.
- This is a point-in-time snapshot (2026-05-28); three live worktrees may shift the picture on merge.
- The audit deliberately did **not** fix anything (per the doc's charter). Every fix-worthy item is a
  flag, not an action.

---

## CA. Confidence audit (de-risk pass, 2026-05-28)

> Added after the audit by a confidence-raising pass: 3 Explore agents (codebase verification) + **one live
> dev-stack experiment**. It **corrects three over-claims** and **strengthens** the verified findings.
> No production code changed; no §Z.4 follow-up opened. Per-finding confidence-after in §CA.4.

### CA.1 — Corrections (claims the evidence disproved)

- **F-C2.1 — RETRACTED causal claim** (was "S3 + prior incident" → now **"S3, naming-clarity, NO incident"**).
  I claimed the `Capability` overload *caused* the `standalone-capability-stays-stuck` bug. Source-verified
  verbatim root cause: *`AppFacadeBootstrap` held a standalone `WorkerCapability` at default PENDING; the KS
  bootstrap held its OWN `WorkerCapability` instance and transitioned only that one; the gate consulted the
  stuck copy → 503.* Fixed via `HeadAssembly.addListener` bridging (regression `WorkerCapabilityBridgeTest`).
  This is **instance-duplication + a missing late-bind bridge within ONE concept** — "the shared name was
  incidental." The overload (5 genuinely-distinct concepts, no naming ADR — confirmed) remains a real
  *clarity* smell, but did **not** cause a bug. Original framing = confirmation bias. **[V]**
- **F-C2.4 — DOWNGRADED** (concept-noun *gate* → naming-convention *note*). With no incident, a new gate is
  disproportionate and would itself be a YAGNI substrate (the C3 pattern). Proportionate response: a short
  reserved-noun **convention note** in contributing docs, not a governance gate.
- **F-C4.1 — RETRACTED recommendation.** A reserve-a-number registry is **infeasible** under the
  worktree-isolation model (worktrees branch from `origin/main`, cannot see siblings' in-flight numbers; a
  shared authority breaks isolation). The post-hoc `check-tempdoc-numbers.mjs` "is the right design"; the
  ~26-commit renumber tax is **inherent to the parallel-worktree model**, not a fixable defect. Reframe from
  S3-actionable to **accepted cost**. **[V]**

### CA.2 — Strengthened findings (higher confidence)

- **F-C1.2 — LIVE-VERIFIED (S1 holds; code-read → live).** Against a running stack (apiPort 61377):
  `GET /api/agent/tools`, `GET /api/agent/session/last`, `POST /api/agent/run/stream` → **404** (absent);
  `GET /api/chat/agent/tools` → **200** with top-level keys **`{tools, available}`** (not the bare array the
  doc shows); `GET /api/chat/sessions` → **200**; `POST /api/chat/agent` → **503** (capability gate; worker
  not at full readiness). Drift is **more than a prefix swap**: `/stream` dropped on resume endpoints; new
  undocumented `X-JustSearch-Audience` request header; new optional `shapeId` body field; `session`→`sessions`;
  tool `description` is now an **i18n key** (`ops.*.description`), not prose. SSE event names unchanged. **[V]**
- **F-C1.1/.3/.4 — successor-truth confirmed (raises *fix* confidence to HIGH).** The storage-doc fix must
  *name the new owner*, not just delete the stale name: write/commit → `WritePathOps`+`CommitOps`; read →
  `ReadPathOps`; lifecycle → `RunningRuntime`+`RuntimeSession` (all `adapters-lucene/.../runtime/`). **[V]**
- **F-C1.8 — NEW finding (S2; explains why the F-C1.x cluster went undetected).**
  `modules/ssot-tools/src/main/java/io/justsearch/ssot/tools/DocsApiDriftCheck.java` scans only
  `docs/explanation/` + `how-to/use-ui.md` with a hardcoded banned-endpoint regex list — **`docs/reference/`
  (home of `api-contract-map.md`) is out of scan scope**, and it asserts endpoint *absence*, not route
  *prefix* or cited-*class* existence. The canonical-refresh slice should extend its scope to
  `docs/reference/` and add a cited-symbol-existence pass. **[V]**
- **F-C4.2 — decompose verdict supported (still a real refactor).** `KnowledgeHttpApiAdapter` = 2,146 LOC,
  a thin-facade-with-fat-body mixing 4 concerns (search plumbing / ranking-eligibility gating / proto
  marshalling / telemetry); Pattern-A decomposition has in-repo precedent (`SearchOrchestrator` 1919→154).
  Confidence raised — but confirm with a focused design pass before executing. **[V]**

### CA.3 — Worktree overlap & HOLD (resolved blind spot)

The audit was point-in-time on `main`; reading the 3 live worktrees' in-flight tempdocs shows adjacent work:

| Worktree | In-flight focus | Overlaps |
|---|---|---|
| `507-kernel-boundary` | `507-capability-mediated-surface` — **`Capability`** / PluginHostApi / kernel boundaries | **C2** (directly) |
| `548-followups` | `551-agent-current-truth-substrate` (single-authoritative-record / representation drift); `553-code-duplication-audit` | **C1/C2** + **C3** |
| `550-impl` | no in-flight tempdocs detected | low |

**HOLD (discipline):** do **not** open the C2 (`Capability` naming note) or C3 (duplication/dead-code)
follow-ups until reconciled with 507 and 548 via the user — they may already be in flight, and a colliding
tempdoc would repeat the F-C4.1 tax. **C1 (canonical-doc refresh, incl. the F-C1.8 guard extension)** and
**F-C4.2 (decompose)** are low-overlap and remain the clean next candidates.

### CA.4 — Per-finding confidence after the pass

| Finding | Before | After | Note |
|---|---|---|---|
| F-C1.1/.3/.4 (deleted classes in docs) | HIGH fact / MED fix | **HIGH / HIGH** | successor-truth map obtained |
| F-C1.2 (agent API drift) | MED (code-read) | **HIGH (live-verified)** | + extra shape diffs |
| F-C1.8 (DocsApiDriftCheck scope gap) | — | **HIGH (new)** | source-confirmed |
| F-C2.1 (Capability overload) | over-claimed (S3+incident) | **corrected → S3, no incident** | causal link retracted |
| F-C2.4 (concept-noun guard) | proposed gate | **downgraded → naming note** | |
| F-C2.3 (verb cluster) | S2 | S2 (unchanged) | in active collapse |
| F-C3.1 (speculative modules) | HIGH | HIGH | unchanged |
| F-C4.1 (number prevention) | S3-actionable | **retracted → accepted cost** | infeasible under isolation |
| F-C4.2 (KnowledgeHttpApiAdapter) | MED feasibility | **MED-HIGH (decompose)** | confirm w/ design pass |

**Net:** the **findings survived**; the **3 most actionable recommendations were corrected** (2 retracted/
downgraded, 1 reframed) and F-C1.2 was live-proven. The clean, low-overlap next steps are the **C1
canonical-doc refresh** (now with a concrete successor map + the F-C1.8 guard fix) and the **F-C4.2
decomposition** — both pending your go-ahead and worktree reconciliation for C2/C3.

---

## I. Implementation outcome (2026-05-28, branch `worktree-556-impl`)

The two clean, low-overlap candidates from §CA.3/§Z.4 were implemented end-to-end. **C2/C3 were not
touched** — they remain on the §CA.3 HOLD pending reconciliation with the live worktrees 507 (Capability)
and 548 (duplication/drift). Nothing here unifies a concept, adds a governance gate, or removes dead code.

### I.1 — Shipped

- **C1 canonical-doc refresh (Z.4 #1).** F-C1.1/.2/.3/.4 (the S1 cluster) plus F-C1.5/.6/.7 fixed: storage
  docs now name the successor ops (`ReadPathOps`/`WritePathOps`+`CommitOps`/`RunningRuntime`+`RuntimeSession`)
  instead of deleted `LuceneIndexRuntime`; `api-contract-map` corrected to `/api/chat/agent/*` + the shape
  diffs from §CA.2; `19-module-architecture` names `HeadAssembly` (not `DefaultAppFacade`); `RuntimeConfig`
  call-sites repointed to `ConfigStore`/`ResolvedConfig`.
- **F-C1.8 guard extension.** `DocsApiDriftCheck` now scans `docs/{explanation,reference,how-to,decisions}`
  (closing the `reference/` blind spot), bans the migrated `/api/agent/*` prefix, rejects deleted-class
  `.java` citations via a `BANNED_CLASSES` list, and honours an inline `drift-allow:<token>` opt-out. A
  `DocsApiDriftCheckTest` (@TempDir) covers each rule. Wired to the `verify` Gradle task.
- **F-C4.2 decomposition (Z.4 #3).** `KnowledgeHttpApiAdapter` 2,146 → **223** LOC thin facade; the
  search/status engine + retrieval orchestration extracted to `KnowledgeSearchEngine` (was provisionally
  `SearchExecutor` — see I.3) with collaborators `WorkerStatusCache`, `SearchRequestMapper`,
  `SearchResultMapper`, `SearchTraceMapper`, `SearchPipelinePresets`, `SearchPerSourceExecutor`. All under
  the 1000-LOC ceiling; the class-size pin (the F-C4.2 sub-smell — "only ratchets up") was *removed*, not
  raised. Behaviour-preserving: facade pass-throughs are byte-identical to the pre-split original; engine
  logic was relocated verbatim.

### I.2 — Verification

`./gradlew.bat build -x test` green (full compile + all discipline gates, incl. the extended drift guard);
full `:modules:app-services:test`; `docsApiDriftCheck` OK (121 files) + `DocsApiDriftCheckTest`;
`prose-tier-register` gate pass. **Live:** WS2 browser-validated the real UI (result rows + explain-trace
panel); post-rename a live HTTP `POST /api/knowledge/search` returned **200 / 53 hits** through the renamed
engine. The self-spawning `IngestionDiagnosticsContractTest` could not run in-worktree (its worker opened a
2136-doc legacy index + ~20.7s GPU model init, exceeding the fixture's READY gate — environmental,
worker-side, logged to `docs/observations.md`).

### I.3 — A live datapoint that partially re-strengthens C2 (vocabulary overload)

The critical-analysis pass caught that the decomposition had introduced a *second* `SearchExecutor`
(`app.services.worker.SearchExecutor`) colliding with the pre-existing
`indexerworker.services.execute.SearchExecutor` — and renamed it to `KnowledgeSearchEngine`. This is a
**fresh, live instance of C2's vocabulary-overload thesis**, and a notable one: §CA.1 had softened F-C2.1
to "clarity smell, no incident," but here the overload *did* breed a concrete problem (a confusing
duplicate concept) — in the very work implementing this audit. It did not reach production (caught in
review), so it does not reinstate the retracted *causal* claim; but it is direct evidence that reusing a
domain noun for a second concept is an active hazard, not merely cosmetic. Reinforces, doesn't overturn,
§CA's correction.

### I.4 — Disposition of the remainder

| Item | Disposition |
|---|---|
| C2 (`Capability`/`Provenance` disambiguation; F-C2.1/.2) | **HELD** (§CA.3) — collides with worktree 507. Not done. |
| F-C2.4 (concept-noun guard) | **Downgraded** to a convention-note (§CA.1); deferred, not a gate. |
| C3 (dead-code / over-building; F-C3.1/.2/.3) | **HELD** (§CA.3) — overlaps worktree 548; F-C3.3 allowlist audit is a scheduled pass, not now. |
| F-C4.1 (number-allocation prevention) | **Retracted** (§CA.1) — accepted cost of worktree isolation. |
| F-C2.3 (verb-cluster collapse) | In active collapse elsewhere (543/548/550); no action here. |

**Closure:** this tempdoc's actionable, low-overlap scope is complete. The held items are intentionally
left for their owning worktrees; reopening them is a separate, user-gated decision.

