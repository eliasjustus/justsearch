---
title: "512 — Codebase Investigation and Critical Analysis"
---

# 512 — Codebase Investigation and Critical Analysis

**Date**: 2026-05-18
**Status**: done
**Owner**: agent investigation requested by user; conversation continues from here
**Related**: ADR-0001 / 0002 / 0003 / 0008 / 0014 / 0017 / 0026; the meta-machinery
in `.claude/rules/*.md`, `CLAUDE.md`, `docs/observations.md`; the abandoned
phase plan in `docs/meta/plan-implementation-phases.md`.

---

## Why this doc exists

The user asked for two things in sequence:

1. An autonomous investigation of the codebase, especially its **initial / early
   state** and **main design decisions**, to fully understand them.
2. A **deep, critical** analysis of the resulting development trajectory — not
   just a summary.

This tempdoc captures both, plus the user's clarifications received at the end
of the second pass, so the next conversation can pick up cold.

The framing is honest critique, not a hit piece. There is real architectural
quality here. There are also real concerns. Both are recorded.

---

## Scope of the investigation

Read (in full or with targeted offsets):

- First commit (`6f0bc5a62`, "Phase 4a", 2025-11-01, 803 files)
- The original README + Changelog (from the first commit)
- The original phase plan (`docs/meta/plan-implementation-phases.md`, phases 0–13)
- The earliest in-tree ADR (SSOT/ADRs/0004 — Phase 0 scope variant)
- The canonical ADRs 0001–0010, 0014 (pipeline removal), 0017 (ai-bridge
  decomposition)
- Canonical `docs/explanation/01-system-overview.md` and `05-ai-architecture.md`
- `docs/observations.md` (first 100 lines — the inbox)
- `CLAUDE.md`, `.claude/rules/agent-lessons.md`, `branch-safety.md`,
  `common-workflows.md`, `context-efficiency.md`, `deprecated-modules.md`,
  `hooks-reference.md`, `slice-execution.md`
- Module listing (`ls modules/`), tempdoc count + range
- Git velocity (`git shortlog` style aggregates by date window)

Not read in depth (out of scope for this pass, but cited in the critique
where the headline summary was enough to land the point): ADRs 0007, 0011–0013,
0015–0016, 0018–0030; explanation docs 02, 03, 04, 06–25.

---

## Origin shape

- First commit landed **2025-11-01**, already containing 803 files: SSOT catalogs,
  JSON schemas, ADRs, phase-acceptance checklists, the build skeleton, and a
  detailed phase plan. The project was **doc-first by design** — the architecture
  was specified before substantial Java code existed.
- Phase plan: 14 main phases (0 → 13) with sub-tracks (.x sequenced, letter
  parallel). Phases 0 / 0.5 / 1 covered bootstrap, SSOT, build system; 2–5
  contracts → Lucene → indexing → search; 6 LLM bridge; 7–13 infra, UI,
  launcher, test, CI, MVD, hardening.
- Current state: ~3,855 commits over ~6 months (Nov 2025 → May 2026). 1,343
  commits in the last six weeks. 738 commits in May 2026 alone (~37/day). 290+
  tempdocs in `docs/tempdocs/` numbered up to 511 (this is 512). 42 modules
  under `modules/`.

---

## What is structurally healthy

These are real architectural wins and they should not be lost in the critique:

1. **The surviving ADRs (0001, 0002, 0003, 0008) are tightly reasoned.** Each
   names its constraints, considers alternatives, lists what would invalidate
   the decision. ADR-0001 (three-process: Head / Body / Brain) is driven by
   Windows MMap locking — a real constraint with no portable workaround. ADR-0002
   (gRPC + MMF hybrid) solves the GC-pause-during-heartbeat problem and the
   port-discovery chicken-and-egg in one move. ADR-0003 (direct Lucene) is the
   correct call for desktop footprint. ADR-0008 (settings ephemeral) is honest
   about the cost-benefit and refused to build migration infrastructure that
   wasn't yet justified.

2. **"One Owner" policy is real, not slogan.** `AppInstanceLock` (one app per
   `dataDir`) + `IndexRootLock` (one Worker per effective `indexBasePath`)
   enforce the invariant in code, not just docs.

3. **"Verify, don't guess" is well-executed.** Ephemeral gRPC ports →
   MMF-published port → Head polls until non-zero. `/api/status` polling for
   readiness instead of `Thread.sleep`. Contract-tested `/api/health` returning
   200 for READY|DEGRADED and 503 otherwise.

4. **Willingness to delete speculative bets.** ADR-0014 is the cleanest example:
   the team built ~6,600 LOC of pipeline-engine + executor, discovered the
   runtime never followed the DAG, deleted the engine first, then realized the
   *retained* schema + budget + `dag_hash` were also dead weight, and deleted
   those too. The one-line lesson from that ADR is worth more than the code it
   cost: **"SSOT works well as a data catalog but failed as a behavioral
   specification."**

5. **Graceful degradation is shaped right** for a single-user desktop product:
   no GPU → BM25 still works; Worker crash → UI degrades to a file browser
   until the watchdog restarts it; embedding model unavailable → search
   continues without vector recall.

---

## What is structurally concerning

### F1. Speculative generality is a *repeating* failure mode

Not one mistake — a pattern:

| Built | Status | Notes |
|---|---|---|
| `pipeline-engine` + `pipeline-executor` (~6,600 LOC) | deleted Feb 2026 | DAG runtime never matched the real code (SearchOrchestrator is a decision tree; IndexingLoop is a state machine) |
| `pipeline-schema` + budget profiles + `dag_hash` (~450 LOC + metadata) | deleted Mar 2026 (ADR-0014) | Retained "for telemetry tagging" after the engine was deleted; later judged dead weight |
| FFM llama.cpp embedding bindings (~10,000 LOC) | deleted Mar 2026 | Replaced by ONNX Runtime |
| `ai-worker` module (full 4th JVM process) | deleted Apr 2026 (ADR-0017) | Built day-1, *never activated in production*, lived ~5 months |
| `app-ai` module (3-impl interface, 1 real impl) | deleted Apr 2026 | Indirection layer with no callers needing the indirection |
| Custom CI DAG engine + 13 runners (~12,300 LOC) | deleted Mar 2026 | Replaced by `jseval` consolidation; ADR-0009 superseded |
| 721 MB CUDA binaries tracked in git | untracked Apr 2026 | Committed before noticed |
| `scripts/resilience/*`, `scripts/governance/*`, orphan PS1 wrappers, `PSScriptAnalyzer/` vendor tree | deleted May 2026 | "Stranded post-`a9c484f59` infrastructure" — orphaned for 2 months before cleanup |
| Settings `schemaVersion` + migration chain | not built (ADR-0008) | Considered, rejected on cost-benefit — *this is the healthy case in this pattern* |

The same shape recurs: design an abstraction, build it, ship it, discover months
later that the runtime doesn't follow it (or never used it), delete it. The
ADR-0017 case is the cleanest illustration — an entire 4th JVM process
(`ai-worker`) existed for five months without ever being started in production
before being deleted.

The discipline machinery (Pass-8 second-agent verification, C-018
substrate-without-consumer rule, bidirectional pre/post-implementation passes,
"audit-driven fixes need a runnable test") all exists *because of* this
pattern. The rules are well-written. But the rate at which new rules accrete
suggests the underlying failure mode is still firing.

### F2. The plan-vs-reality gap is structural, not a one-time slip

- The phase plan (`docs/meta/plan-implementation-phases.md`) still lists Phase
  4 (Indexing pipeline) and Phase 4a (Pipeline engine) as planned phases. The
  pipeline engine was deleted. The plan was not updated.
- ADR-0014 admits openly that for some period the system shipped with two
  diverging specs: real code, and SSOT pipeline JSON that didn't govern
  runtime behavior.
- Empty stub directories still exist at `modules/ai-bridge/`, `modules/app-ai/`,
  `modules/ai-worker/` six weeks after ADR-0017 declared them eliminated.
  Logical deletion was complete; physical cleanup wasn't.
- Multiple `.claude/worktrees/499-*` directories with their own
  `CONFLICT-LEDGER.md` inside `the retired 421 FE-rewrite draft`
  suggest parallel agent sessions whose merge state isn't obvious from the
  outside.
- The "Phase 3 §B.14 sweep" in `observations.md` documents a wholesale cleanup
  of infrastructure that had been broken on `main` since the March commit
  `a9c484f59` (two months earlier). The regression net didn't catch this
  because of F4.

The codebase accretes artifacts faster than it grooms them.

### F3. The infrastructure-to-feature ratio drifts toward substrate

Tempdoc trajectory, with rough sampling:

- Early 200s: `accessibility-audit`, `error-ux-recovery-flows`,
  `installer-distribution-maturity`, `configuration-ux`, `ner-model-agnosticism`,
  `ai-model-tuning-optimization` — concrete, often user-facing.
- Recent 500s: `mcp-protocol-surface`, `runtime-port-discovery-architecture`,
  `boot-composition-architecture`, `horizon-2-compositional-ui`, `horizon-3-ecosystem`,
  `capability-mediated-surface-architecture`, `plugin-ecosystem-substrate`,
  `coherent-ai-presence`, `operation-label-coherence`, `ai-aware-shell`,
  `aggregate-surfacing-substrate` — increasingly meta, increasingly internal.

The most recent commits visible at investigation time are all `feat(510)`,
`fix(510)`, `docs(510)` — internal slice work on the AI-aware shell. The
trajectory is **substrate work building on substrate work**.

Note: the user has explicitly framed this as intended — see §"Clarifications
from the user" below. The observation is recorded here because the *risk* it
creates (substrate that never gets a consumer) is real even if the strategy is
deliberate. Mitigation lives in the existing C-018 rule ("substrate must name
a consumer slice or don't ship the slot") and in Pass-8 verdicts being treated
as merge gates rather than discussion items (slice 481 reference case shows
this has been violated).

### F4. Manual-only CI (ADR-0026) — re-evaluated after user clarification

My initial critique framed this as a "discipline-over-net" trade-off where the
team trusted agents to run local gates reliably. The user clarified the real
reason: **GitHub Actions minutes wouldn't cover automatic CI given the commit
velocity.** That is a cost constraint, not a process choice, and it changes the
analysis.

What's still true:

- The structural risk ADR-0026 documents is real: "no auto-regression trap on
  `main`." The Phase 3 §B.14 sweep is direct evidence — infrastructure broken
  on `main` since March was not caught for two months. That's a *consequence* of
  the policy, not a *failure* of the policy; the policy was knowingly
  taken.
- The mitigation surface (manual `gh workflow run ci.yml` dispatches after
  substantive changes, plus the `workflow-signal-health.mjs` triage report,
  plus the local gate as the primary discipline) is appropriately designed
  for the constraint.

What changes from the initial critique:

- "ADR-0026 is a structural risk masquerading as discipline" is too harsh. The
  honest framing is: **ADR-0026 is a structural risk knowingly accepted as a
  cost trade-off, mitigated by local gates that occasionally don't fire.**
- The deeper question moves to: if the project's velocity is what makes auto-CI
  unaffordable, is the velocity itself the variable? That's a strategic
  question for the user, not a technical critique.

### F5. The discipline machinery is fighting fires it lit

`agent-lessons.md`, `slice-execution.md`, and the CLAUDE.md "Agent Discipline"
section together document a long series of agent failure modes that produced
specific incidents:

- Wrong-gate / wrong-flag mistakes (tempdoc 403 Tier B).
- Audit conclusions not independently verified (tempdoc 403 Tier C).
- Treating correlation as causation (the "interrogate results" rule).
- Substrate without consumer (C-018, slice 447 §X.11).
- Pass-8 verdicts overridden via mandate-citation (slice 481, ratified rule).
- Phantom IDs / phantom fields / phantom schema URLs (slice 447 §X.11.5).
- Static-green-but-live-broken (slice 447 §X.12 three-tier verification).
- Reading the wrong row of a candidate catalog (slice 486 §27).
- Audit-without-test (tempdoc 403, "the audit was wrong; the test would have
  caught it in minutes").

Each rule reads as evidence-of-incident. The discipline is doing real work —
slice 447's Pass-8 dispatch caught 3 substrate-without-consumer violations + a
phantom field + a phantom schema URL + a soft deferral that the implementing
agent had missed. But the *existence* of so much rule surface, and the rate
at which new rules continue to land (the §X.12.10 closure ratified two more
Pass-8 prompt-template additions on 2026-05-08), is itself the signal that
the underlying failure mode keeps mutating into new shapes.

This is a known tension in agent-driven development at scale. It isn't a
defect; it's a property. But it shows up in the codebase as: process surface
growing alongside (and sometimes ahead of) product surface.

### F6. The constraint set is principled but narrow

ADR-0001 / 0003 / 0004 (original) target the intersection of: Windows desktop,
~8GB consumer VRAM, local-only, single-user. That's a real audience but a
small one, and the complexity tax (three processes, gRPC + MMF, suicide-pact
heartbeat, GPU mutex, zombie-process killing, VRAM accounting, blue-green
schema migration) is significant.

There is no visible user-feedback loop in the codebase. No retention metrics,
no complaint patterns, no feature-request inflow from non-agent humans. The
eval harness produces ranking and throughput numbers; the dev MCP exposes the
stack to agents. The product is being engineered against a *constraint
profile*, not a *user demand profile*.

The user has clarified that monetization is intended. That ties off the "who
is this for" question — the answer is "a future paying user" — but it doesn't
yet ground the requirements. A v1 product targeting Windows desktop users
with 8GB VRAM is plausible. What hasn't been written down anywhere I read is
the *user story* that closes the loop between architecture and revenue.

---

## Clarifications received from the user (end of pass 2)

Recorded verbatim-equivalent so future conversation can pick up cold:

1. **Both (a) and (b).** The codebase is *both* a real product intended for
   future monetization *and* an experiment in agent-managed development. The
   experiment is in service of the product, not separate from it. The
   substrate investment now is intended to make future feature delivery
   cheaper / faster / more agent-tractable.

2. **Manual CI is a cost constraint.** GitHub Actions minutes wouldn't cover
   automatic CI at the current velocity either way. The choice was
   constrained, not philosophical. See F4 above for the re-evaluated framing.

These two points materially change the read of the trajectory:

- F3 (infrastructure-to-feature ratio drifting toward substrate) is *intended*
  rather than accidental — but the C-018 risk (substrate without consumer)
  doesn't go away just because the strategy is deliberate. It just moves
  from "did we mean to do this?" to "are we sure each substrate slot has a
  consumer slice that will land?"
- F4 (manual CI) reframes from "discipline failure" to "knowingly-taken
  cost trade-off" — same consequence space, different judgment.
- F6 (constraint set is narrow) is partially answered: the audience is a
  future paying user. The *who exactly* and *for what use* still aren't
  documented in any place I read, but that's now a product question, not an
  architecture question.

---

## Open questions for the next conversation

These are the threads I would want to pull on, in priority order:

1. **What's the v1 user?** "Windows desktop user with 8GB VRAM who wants local
   search with optional LLM features" is a constraint profile. Who *specifically*
   would pay for v1, what would they pay for, and what's the smallest shipping
   product they would buy? This grounds every later substrate decision.

2. **Is the current substrate trajectory (500s tempdocs) earning out?** F3 +
   the F1 history together suggest substrate work has historically been a
   ~50/50 bet — half the substrate ships, half gets deleted as not-fit. Are
   the current 500s passing the C-018 gate (named consumer slice for each
   substrate slot)? Slice 481 (`requires-pass-3` overridden) is the
   cautionary case — that substrate shipped, and the audit said the
   consumers it claimed to enable didn't materialize as designed.

3. **What's the cleanup discipline?** The empty `ai-bridge/` /
   `app-ai/` / `ai-worker/` directories, the orphan `.claude/worktrees/499-*`
   trees, the post-`a9c484f59` infrastructure that lived broken for two
   months — these are cheap to fix and they're the signal of ground-level
   grooming. A 30-minute audit-and-purge would close most of them.

4. **Is there a way to defer some of the meta-machinery?** Pass-8, C-018,
   CONFLICT-LEDGER, bidirectional passes, three-tier verification — these
   are all real and each catches real defects. But the *combined* surface is
   high, and the marginal defect each new rule catches is lower than the
   first ones. Worth asking: which rules have actually fired in the last
   month, and which would you not miss if they were retired?

5. **The phase plan.** It's outdated and the project no longer navigates by
   it. Either refresh it to match where the project is, or formally retire
   it and point future agents at whatever does replace it (currently:
   tempdoc-of-the-moment).

6. **The "experiment" side of (a)+(b).** If learning how to manage agents
   on a complex stack is part of the explicit goal, the lessons in
   `agent-lessons.md` and `slice-execution.md` are themselves valuable
   artifacts. Are they being captured / extracted in a form that's portable
   off this project, or are they only useful inside it? (Asked because if
   monetization is the goal, the meta-learnings might be commercial
   themselves, separate from the search product.)

---

## What this doc is *not*

- It is not a roadmap, plan, or slice. No implementation work attaches.
- It is not a verdict — the user asked for honest critique and got it; the
  user's intent (a + b, cost-constrained CI) reframes parts of the critique.

---

## Role going forward (added after user direction)

The user has set the conversation role: **be the overview of this codebase's
development.** Not critic, not implementer, not strategy advisor. The critique
above stands as a recorded investigation. From here, the agent in this
conversation serves as a reference surface for "what happened here, why, and
where it went."

A second investigation pass (post-critique) covered the remaining ADRs and
key explanation docs. The reference appendix below captures the factual
material from that pass — not as critique, just as the structured knowledge
the overview-agent should be able to answer from.

---

## Reference appendix — what the overview knows

### Full ADR landscape (30 ADRs, 2025-10 → 2026-04)

The ADR set covers three temporal clusters:

**Cluster 1: foundational (Jan–Feb 2026)** — the architecture survives.
- 0001 Three-process. 0002 gRPC+MMF. 0003 Direct Lucene. 0007 Entity faceting
  over knowledge graph. 0008 Settings ephemeral. 0013 Synonyms FST placeholder
  (dated 2025-10 but accepted later).

**Cluster 2: speculative bets, mostly superseded (Feb–Apr 2026)** — the
abandoned architecture.
- 0004 Single-tenant GPU policy (superseded by GGUF→ONNX migration).
- 0005 Manual FFM bindings (superseded — entire FFM layer deleted).
- 0009 Custom DAG engine for CI (superseded — `jseval` consolidation).
- 0012 UI Stack (JavaFX) (superseded — React/Vite/TypeScript).
- 0014 Pipeline definition removal (corrects ADR-0006-SSOT / pipeline-engine
  abandonment).
- 0017 ai-bridge decomposition (eliminates `app-ai`, `ai-worker`).

**Cluster 3: product-shaping (Mar–Apr 2026)** — the current direction.
- 0006 Two-pronged citation. 0010 Local-first workflow observability.
- 0011 Distributed Readiness — Remote Shard SPI (forward-compat, no remote
  implementation yet).
- 0015 MCP tool surface (7 → 4 task-oriented tools, motivated by agent eval
  showing search-then-preview loop dominance).
- 0016 QU soft-boost (SHOULD clauses; hard filters caused 20% nDCG drop +
  7/50 zero-result queries).
- 0018 VLM PDF extraction via existing Qwen chat model (no Docling sidecar).
- 0019 CPU vs GPU model selection (FP32 for CPU, FP16 for GPU; FP16 on CPU
  EP causes 30-60min optimization hangs).
- 0020 Structured metadata facets (`meta_` prefix, extends ADR-0007 pattern).
- 0021 Build-stamp content-hash (Worker dist staleness detection in dev).
- 0022 RecordBuilder for app-api records (additive evolution).
- 0023 API responses declare runtime context (in_memory vs read_write,
  ONNX active vs file-discovered).
- 0024 NSIS, per-user install, download-on-demand (8.5GB model payload
  exceeds NSIS 32-bit PE limit; lean install ~748MB).
- 0025 Core DTO dual-type layering (gRPC `core` vs REST `app-api`; not
  duplication, intentional layering).
- 0026 Manual-only CI (cost-constrained, see F4).
- 0027 MetricCatalog as telemetry contract (typed instruments via
  `MetricCatalog`; retires `Telemetry.counter/timer/histogram/gauge/meter`).

### Module structure (42 Gradle modules + 2 non-Gradle)

| Layer | Modules | Role |
|---|---|---|
| **Foundation (leaf)** | `core`, `configuration`, `telemetry`, `ort-common`, `ai-backend`, `gpu-bridge`, `prompt-support`, `core-contracts`, `infra-core` | Base types, no internal deps |
| **API contract** | `app-api`, `app-api-tck`, `app-agent-api`, `ipc-common`, `api-contract-projection-java` | gRPC + REST interfaces |
| **Application services** | `app-services`, `app-search`, `app-indexing`, `app-agent`, `app-inference`, `app-config`, `app-secrets`, `app-observability`, `app-util` | Head-side orchestration |
| **Worker process** | `indexer-worker`, `worker-core`, `worker-services`, `adapters-lucene`, `indexing`, `search`, `reranker` | Body process (Lucene owner) |
| **Entry points** | `ui` (Javalin), `app-launcher` (CLI), `ui-web` (React), `shell` (Tauri) | User surfaces |
| **Cross-cutting** | `agent-review`, `benchmarks`, `ssot-tools`, `system-tests`, `test-support` | Quality + tools |
| **Stub artifacts** | `ai-bridge/`, `app-ai/`, `ai-worker/` | Empty dirs from ADR-0017 deletion |

ArchUnit + custom Gradle governance enforces dep direction. Class-size
standard caps at 1,000 LOC.

### Testing posture

Four-tier pyramid:
- **Tier 1 Unit** (<10ms, no IO) — Mockito heavy, plus Vitest for FE
- **Tier 2 Integration** — `@TempDir`, real gRPC wiring, real ORT sessions
- **Tier 2.5 Guardrails & Contract** — ArchUnit dep rules, lifecycle
  contract test pinning `/api/health` + `/api/status` schema v1, security
  policy tests
- **Tier 3 System** (`modules/system-tests`) — real multi-process scenarios
- Plus: AI Judge / chaos testing for the AI-facing paths

The `IsolatedBackendFixture` + `JUSTSEARCH_LITE_MODE` pattern lets integration
tests get a clean backend per test class without paying the 3-8s AI-stack
load cost.

### Search pipeline (current, post-pipeline-engine deletion)

Head → gRPC → Worker → gRPC → Head, with these stages:

1. **Head** (`KnowledgeHttpApiAdapter`): resolves `PipelineConfig` from
   preset, optionally fires async LLM expansion.
2. **Worker** (`SearchOrchestrator`): runs retrieval legs in parallel
   (BM25, Dense KNN, SPLADE) via virtual threads. Fuses via RRF/CC.
   Fuzzy correction retry on zero-hits. Chunk search if chunks exist,
   collapsed by parent doc. Computes match spans, excerpts, facets.
3. **Head** (post-retrieval): merges LLM expansion. Reranking cascade:
   LambdaMART (~5ms) → cross-encoder (200-500ms). Trims to limit.

`SearchOrchestrator` is the decision tree that ADR-0014 cites as the
reason the DAG-based pipeline engine never matched runtime.

### Process model and IPC (anchor for "how do these things talk")

- **Head** (`HeadlessApp.java`, JVM, 128-256MB heap): UI Host, API gateway,
  config owner, watchdog, sidecar host (child of Tauri shell).
- **Body** (`IndexerWorker.java`, JVM, dynamic heap): Lucene owner, indexer,
  Tika extractor, ORT encoder host.
- **Brain** (`llama-server.exe`, native): generative LLM, OpenAI-compatible
  REST.

IPC:
- gRPC for structured calls (search, indexing, document, health)
- 64-byte MMF `WorkerSignalBus` for: port discovery (offset 20), suicide-pact
  heartbeat, GPU active flag (offset 24), user activity timestamp
- HTTP loopback for browser ↔ Head (port `33221` default, auto-discovers
  `33221..33250` in dev)

Three locks enforce ownership:
- `AppInstanceLock` — one app per `dataDir`
- `IndexRootLock` — one Worker per effective `indexBasePath`
- Implicit: only Head spawns/kills llama-server

### Canonical docs surface (the maps the overview points at)

- `docs/llms.txt` — index of canonical docs, auto-generated
- `docs/explanation/01..25` — 25 architecture explanations
- `docs/reference/*` — API contracts, configuration, contributing guides,
  performance refs, search/inference registers
- `docs/how-to/*` — 10 task-specific guides
- `docs/decisions/0001..0027` — ADR series
- `docs/tempdocs/*` — 290+ investigation logs (noncanonical, may drift)
- `docs/observations.md` — inbox of noticed issues
- `docs/reference/issues/*` — promoted bugs / tech-debt tracking
- `CLAUDE.md` + `.claude/rules/*.md` — agent discipline

### Tempdoc convention (per `docs/tempdocs/README.md`)

- Status values: `open` | `active` | `done` | `shipped` | `draft`
- Stale = 30+ days unupdated in non-terminal status
- At terminal state: promote learnings to canonical docs, leave tempdoc
  as design-history record
- "What doesn't belong here": non-software (→ `business/`), feature ideas
  (→ `future-features/`), permanent reference (→ promote)

---

## What's missing — work that other codebases invest in more

Added after a comparative pass: categories where mature production
software typically does load-bearing work, and where this codebase
shows thin or absent investment. Intentional absences (cloud sync,
multi-platform, cluster search) are excluded — these are the
*unintended* gaps.

### G1. Security beyond crash isolation

The codebase handles process crashes well (auto-restart, zombie
killing, suicide-pact heartbeat). The *security* dimension of "this
app reads every file on the user's machine and feeds them to an LLM"
is largely invisible.

- **No file-extraction sandbox.** Tika is a documented attack surface
  (malformed PDFs, OOXML, image parsers, embedded macros). The crash
  case is handled (poison-pill restart per ADR-0001) but a compromise
  during extraction has access to the entire index, the user's files,
  and the gRPC channel to Head.
- **No prompt-injection defense visible.** RAG feeds retrieved chunks
  directly into LLM context. A file containing
  `Ignore previous instructions and …` will be treated as
  authoritative. The two-pronged citation strategy (ADR-0006) detects
  *whether* the LLM cited, not whether the source manipulated it.
- **No threat-model document.** For an app of this trust scope (reads
  all user files, runs local LLM on them, loopback HTTP, subprocess
  inference), a written threat model is standard. Not present.
- **No SBOM / CVE scanning.** Gradle dependency verification pins
  hashes (tamper detection) but doesn't track advisories. No
  Dependabot, no `osv-scanner`, no `cyclonedx`. Lucene, Tika, Jackson,
  OTel, ORT, llama.cpp all have CVE histories.
- **Installer code signing** is in `docs/reference/issues/installer.md`
  as known-open. Without it, every user sees a SmartScreen warning.

### G2. Eval corpus is a category error

The retrieval-quality work uses SciFact and BEIR — scientific paper
abstracts and entity-rich queries. The product is **personal file
search**. Personal files are tax PDFs, emails, screenshots, photos,
receipts, notes, contracts, recipes, code, spreadsheets — sparse-entity,
high-context, mixed-modality, often without good keyword targets.

The eval currently answers *can the system find scientific abstracts
that match scientific queries?* It doesn't answer *can a user find
their 2024 tax PDF when they type "taxes" three months later?* The
metrics are confident on the wrong test set.

A personal-files search engine shipping seriously would have a
personal-document corpus (anonymized, dogfooded, or synthesized) with
personal-style queries — vague, time-anchored, "the thing about X."

### G3. Long-running stability is barely tested

Stress tests exist for `NativeSessionHandle`, `OnnxSessionCache`,
`OrtCudaHelper` — concurrency under load. What I don't see:

- 24h / 48h soak test against steady ingestion (file-handle leaks,
  ORT session accumulation, gRPC channel orphaning, MMF unmapping
  races, log/segment growth).
- Memory-pressure test under realistic workload (Lucene MMap + 4–7 GB
  resident model + user workload on an 8 GB machine).
- Documented retention policy for NDJSON/traces/metrics/worker.log
  (rotation is hinted at by `_mirror_telemetry` but not surfaced as a
  user-facing or operator-facing policy).

### G4. Performance budgets are backend-shaped, not user-shaped

Measured: indexing throughput, ORT timing, cold-start to worker-ready
(~38 s), gRPC latencies, encoder drift PSI.

Not measured (or not surfaced):

- Keystroke → first visible result latency.
- App icon click → first usable search (cold-launch).
- First search after install (with 8.5 GB models downloading).
- Search latency under load (concurrent indexing).
- Result-render latency end-to-end including the 200–500 ms
  cross-encoder rerank tail.

Backend numbers are necessary but not sufficient — a search tool is
judged by p95 keystroke-to-result.

### G5. Desktop-integration surface is absent

For a search tool, "how the user gets to the search" is the product.
The codebase ships a Tauri app — icon + window. Missing:

- System tray + quick-open
- Global hotkey (Win+Space equivalent — Alfred/Raycast/PowerToys Run
  shape)
- File association (right-click → "Find similar")
- Windows Search / Cortana integration
- Browser extension hitting the loopback API
- OneDrive / iCloud / Dropbox stub-file awareness (personal files
  increasingly live in cloud-synced folders with placeholder files)

Without these, the user must open the app → click search box → type.
That's the worst UX for the "I need that file right now" use case the
product exists to serve.

### G6. Battery / power / thermal awareness

Continuous indexing is fine on a desktop, brutal on a laptop. I don't
see:

- AC-vs-battery detection
- CPU throttle on battery
- Indexing pause when user is active in another app
- Thermal-aware throttling
- "Defer heavy work to plugged-in" scheduling

The MMF user-activity timestamp ("breath holding") shows there's some
user-presence awareness — but I didn't see it wired to power state.

### G7. First-run experience

ADR-0024 documents the mechanism (NSIS per-user, ~748 MB lean, models
on demand). The *flow* isn't visible: what does the user see during
30 s → 5 min → first useful search? Progress UX during 8.5 GB download?
Network drop at 7 GB? Closed mid-download? Demo without LLM models?

Desktop apps that succeed invest heavily here. This is the retention
decision moment.

### G8. Update infrastructure

NSIS installer builds. What I don't see: auto-update channel (Squirrel,
custom), beta/stable channel separation, update integrity verification
(related to the unsigned-installer issue), rollback, "your version is
N days old" UX. For a local-first desktop app, the update channel is
the only path from a fixed bug to the user.

### G9. Accessibility depth

`docs/reference/issues/search-accessibility.md` and tempdoc 200 mark
this as open. ARIA, keyboard nav, focus management, SSE-streamed
result announcements — not visibly invested in. A keyboard-driven
"search by typing" persona is exactly the user who needs this most.

### G10. Privacy boundary documentation

For a product whose distinguishing claim is *local-first*, the
privacy boundary should be auditable: what leaves the machine ever,
what telemetry captures (query text? file content? identifiers?),
what the LLM sees, how to disable telemetry, how to fully scrub on
uninstall. No user-facing privacy doc found.

### G11. PII handling during indexing

Tax documents → SSNs. Emails → passwords sent in plaintext. Photos →
geolocation EXIF. No PII detection during indexing. No "redact this
from chat context" policy. No "don't surface this in vector search by
default" classifier.

### G12. Human-facing documentation

All docs are agent-shaped. `CLAUDE.md`, agent-guide, skills,
slice-execution methodology, hooks-reference. A human developer joining
or auditing has no on-ramp distinct from the agent surface; a *user*
has no documentation at all.

### G13. Reproducibility / provenance

ADR-0021 has build-stamp content-hash. But: can a build from a given
git SHA be reproduced? Are dependencies pinned strictly enough across
JVM/native/Tauri/Node? Are model weights content-addressed independent
of the repo? For a project that may eventually need to audit "what
shipped on day X" — this matters.

### G14. The agent surface is more invested in than the user surface

ADR-0015 designs 4 MCP tools with schema-minimality, task-orientation,
and eval-grounding against agent accuracy. That's good work. The same
depth applied to the *user's* search UX is not visible — the canonical
`docs/reference/search-ui-behavior.md` exists but the design rigor and
eval rigor going into the agent surface exceeds what's going into the
human surface.

### Pattern across the gaps

The gaps cluster around a single shape: **work that protects,
polishes, or services the human user.** Security against the file
ecosystem the user lives in. Real-corpus eval reflecting actual files.
Long-running stability the user experiences as "this stays good over
weeks." Performance metrics the user perceives. Desktop integration
points the user reaches search through. Power awareness the user's
laptop needs. Accessibility for users who deserve it. Privacy docs
the user can audit. Updates that deliver fixes to users.

The substrate work is detailed. The infrastructure is sophisticated.
The architecture is principled. The agent surface is carefully
designed. The work that's thinner is, almost without exception, the
work that touches a real person on the other end.

### What's intentionally absent (not a gap)

- Cluster search / replication — ADR-0011 has SPI placeholder.
- Cloud sync / remote backend — local-first by design.
- Multi-platform — Windows-only per ADR-0001's constraint set.
- User behavior analytics — local-first makes this fundamentally hard.

### What this codebase does that most don't (positive flip)

- ADR rigor unusual (27 ADRs vs typical 0–3, with named alternatives
  + reassess-when clauses).
- ArchUnit guardrails on dependency direction.
- Four-tier test pyramid with explicit Tier 2.5.
- Process-model and IPC discipline.
- SSOT as fingerprinted data catalog.
- Bidirectional spec/critical-analysis slice methodology.
- Honest deletion of speculative bets.

### Caveat

Things possibly present I didn't see: deep frontend code, full
extraction sandbox config, agent loop runtime, full eval harness
beyond `jseval`, ~250 tempdocs I only saw by title. Pointers to any of
these would revise the read.

---

## Phase 2 — Deep analyses (autonomous follow-on)

The user directed: "autonomously proceed with investigating and
analysing each of these aspects." Findings land below as they're
completed, organized by the A/B/C/D categories from the proposal list.

### A. Quantitative patterns over time

#### A1. Velocity-by-week and what correlates with surges

Monthly commit counts (project life: 2025-11-01 → 2026-05-18):

| Month | Commits | Note |
|---|---|---|
| 2025-11 | 145 | Project start, ramp |
| 2025-12 | 71 | Quiet (year-end) |
| 2026-01 | 235 | Steady ramp |
| 2026-02 | 876 | **Foundational ADRs landed (0001–0010)** |
| 2026-03 | 1187 | **Peak** — pipeline-engine deletion, FFM deletion, custom DAG deletion, ADR-0011/0014 |
| 2026-04 | 582 | Cooling — ai-bridge decomp (ADR-0017), API record work |
| 2026-05 | 771 | Mid-month, MetricCatalog (ADR-0027), 500-series substrate |

Weekly view confirms: peak weeks W11–W12 (390 + 413) align with the
March great-deletion period. W17 (364) corresponds to the
late-April ai-bridge decomposition. Lulls (W14: 24, W16: 20) are
real — week-long pauses, not random distribution.

**Interpretation.** Velocity correlates with *architectural events*,
not with feature work. The two biggest months (Feb-Mar) were when
the project formalized its surviving ADRs *and* deleted the bets
that didn't survive. April cooled because the big consolidations
were done. May is reaccelerating into substrate work (500-series).

This is consistent with the substrate-investment-as-strategy framing:
velocity tracks substrate shifts, not feature delivery.

#### A2. Tempdoc lifecycle health (292 tempdocs)

Status-value distribution scanned from frontmatter:

| Status | Count | Valid per README? |
|---|---|---|
| `active` | 69 | ✓ |
| `complete` | 34 | ✗ (invented — README says use `done`/`shipped`) |
| `open` | 33 | ✓ |
| `done` | 28 | ✓ |
| `implemented` | 12 | ✗ |
| `closed` | 9 | ✗ |
| `superseded` | 4 | ✗ (meaningful — superseded by newer tempdoc) |
| `completed` | 4 | ✗ (duplicate of `complete`) |
| `shipped` | 3 | ✓ |
| `proposed` / `idea` / `research` / `backlog` / `in-progress` / `concluded` / `resolved` / `design` / `deferred` / etc. | 1–3 each | ✗ |

**22 distinct status values** appear in tempdoc frontmatter, despite
the README explicitly stating "Use **only** these values: open /
active / done / shipped / draft. Do not invent custom status values."

This is **discipline drift inside the discipline system itself.** The
rule exists; the rule is documented; the rule is violated 60+% of
the time. Some divergences are semantic refinements (e.g.,
`superseded`, `substantively-complete`) that the canonical taxonomy
doesn't express. Others are pure synonyms (`complete` = `done`,
`completed` = `done`, `closed` = `done`).

**WIP shape.** Of 292 tempdocs:
- Non-terminal (open + active + open-variants): ~102 (35%)
- Terminal-shaped (done + shipped + complete + completed + implemented + closed + resolved + concluded + superseded): ~96 (33%)
- Other/ambiguous: ~94 (32%)

69 `active` is the largest single bucket. If "active" means "started
but not declared done," there's substantial WIP accumulation. The
"stale" definition (30+ days unupdated in non-terminal status)
would need per-file `git log` to compute, but with this volume, a
significant slice is almost certainly stale.

#### A3. Tempdoc → ADR conversion rate

ADRs cite tempdocs as motivation. Citation counts (top references):

| Tempdoc | ADR citations | Subject (inferred) |
|---|---|---|
| 406 | 4 | (corrupt-index recovery / parity guard) |
| 397 | 4 | (NativeSessionHandle rename / ORT renaming) |
| 269 | 4 | (trigger audit / phase plan reality) |
| 268, 414, 413, 412, 233 | 2 each | (various — embedding migration, GPU init, contract evolution) |
| ~25 others | 1 each | (single-citation tempdocs) |

**Total ADR-cited tempdocs: ~30 unique, of ~290+ filed.**
Conversion rate ≈ **10%**. The other 90% either:
- Produced canonical-doc changes without an ADR
- Produced code changes captured in commit messages but not in
  formal architectural decisions
- Died quietly
- Remain `active` / `open`

**Interpretation.** Most investigation work doesn't reach formal
architectural-decision status. This isn't necessarily bad — many
tempdocs are bug-investigations, validation studies, or design
explorations that wouldn't merit an ADR. But it does mean the ADR
record is selective: the *durable* architecture surface is the
~30 tempdocs that hit ADR, plus the ~30 ADRs themselves, plus the
~25 explanation docs. The other ~260 tempdocs are design-history
rationale, useful but secondary.

#### A4. Rule churn in `.claude/rules/`

Per-file commit counts on the 8 rule files:

| File | Commits | First commit | Inferred theme |
|---|---|---|---|
| `agent-lessons.md` | 15 | 2026-03-09 | Lessons learned (highest churn — ~2/week) |
| `branch-safety.md` | 15 | 2026-02-11 | Worktree + git discipline |
| `common-workflows.md` | 11 | 2026-03-18 | Standard procedures |
| `hooks-reference.md` | 8 | 2026-03-18 | Hook behavior catalog |
| `context-efficiency.md` | 7 | 2026-02-03 | Compaction / context use |
| `deprecated-modules.md` | 3 | 2026-02-17 | Deletion record |
| `slice-execution.md` | 1 | 2026-05-05 | Methodology (newest, 13 days old) |
| `compaction-state.md` | 0 | — | Untracked / new |

**~60 commits across 8 rule files in 3 months ≈ 20 rule-revisions/month.**
The discipline system is itself a churning artifact. `agent-lessons.md`
in particular accretes a new lesson roughly every week. `slice-execution.md`
as a separate methodology doc only exists since 2026-05-05 — the
bidirectional-pass framework is 13 days old.

**Interpretation.** The "lessons learned" timeline is short and
ongoing. The project hasn't reached a steady-state where the rules
are stable — every week or two a new failure mode produces a new
rule. This corroborates F5 (the discipline machinery is fighting
fires it lit, and the lit-fire rate is not yet declining).

#### A5. Observations.md throughput

- **138 entries total** (107 checked-off + 31 open)
- **78% resolved.** Healthy ratio for an inbox.

Open items (31) cluster around:
- **Deferred-by-design** (e.g., `gpu-saturated` event suppression
  during llama-server uptime; FE Delta discriminator wrap)
- **Unmeasured cold-start timings** (true cold-start with eager-wire)
- **Latent-risk acknowledgments** (Tempdoc 415 L1/L2 — test ordering
  load-bearing on synchronous event delivery)
- **Dev-env papercuts** (scoop symlinks; claude-notifications-go
  doubled Stop events)
- **Methodology improvements** (source-anchor mirrored components at
  write time — caught defect class)
- **Forward-compat acknowledgments** (auto-reconnect deferred; V2
  WorkspaceTimeline sessionId join)

These are not "we forgot" entries. They are "considered, deferred
with reasoning." That's a healthy inbox shape — the discipline
"log pre-existing issues, don't fix them" from CLAUDE.md is
functioning. Note: none of the G1–G14 gap classes from Phase 2
(security, eval corpus, soak tests, desktop integration, battery
awareness, accessibility, privacy docs, update channel) appear in
the observations inbox — i.e., the gaps weren't noticed-and-deferred,
they were never noticed in the agent's normal flow. That's a
different category from "deferred."

#### A6. Commit-message taxonomy

| Prefix | Count | % of total |
|---|---|---|
| `docs:` | 1279 | 33% |
| `feat:` | 903 | 23% |
| `fix:` | 589 | 15% |
| `refactor:` | 249 | 6% |
| `chore:` | 161 | 4% |
| `test:` | 68 | 2% |
| `ci:` | 53 | 1% |
| `perf:` | 24 | 1% |
| `build:` | 13 | <1% |
| Other (`merge`, ad-hoc, etc.) | ~470 | 12% |

**Striking ratios:**
- `docs:` is the **largest single category** (1279 commits, 33%).
  Most projects ship docs as 1–5% of commits.
- `feat:fix` ratio = 903:589 ≈ 1.53. Healthy on the surface.
- `test:` at 68 is small *as a dedicated prefix* — but tests usually
  land inside `feat:` commits, so this undercount is expected.
- "Product work" (feat + fix + refactor + perf + test) = 1833 = 47%
- "Meta work" (docs + chore + ci + build) = 1506 = 39%
- Other = 14%

**Interpretation.** This codebase invests **roughly equal effort in
documentation/meta work and product code.** That's 5–10× the
docs-investment ratio of most projects. Two readings:

1. **Healthy interpretation:** agent-driven dev needs canonical
   documentation to maintain coherence across stateless sessions.
   Docs aren't overhead; they're the medium agents reason in.
2. **Concerning interpretation:** much of the docs traffic is
   tempdoc maintenance and ADR / canonical-doc thrash from
   in-flight slices. Some of the 1279 is genuine knowledge
   capture; some is the cost of agent-driven dev paid in docs.

Both can be true simultaneously.

#### A7. Author analysis

Effective single-user development:

| Author | Commits |
|---|---|
| `eliasjustus` | 3652 (95%) |
| `justus457` | 204 (5% — early commits, same person on different machine) |
| `dependabot[bot]` | 11 |

**3652 commits in ~200 days from one human author ≈ 18 commits/day**
sustained, with peak days far higher (March averaged 38/day across
the month). Impossible for a human alone. This is the empirical
fingerprint of agent-augmented solo development.

No co-authors visible. No team. This is one person + Claude.

### A — Synthesis

The A-cluster paints a coherent picture:

1. **Velocity tracks architectural events, not feature shipment.**
   Peak months coincide with the great-deletion era and ADR
   formalization. Cooling correlates with consolidation. The
   current uptick is substrate work.
2. **Discipline drift is present even in the discipline system.**
   The tempdoc README defines 5 valid status values; the actual
   tempdocs use 22. This is direct evidence that rules-as-written
   don't fully bind agent behavior.
3. **WIP is accumulating.** 69 `active` + 33 `open` = 102
   non-terminal tempdocs is substantial overhang. Stale fraction
   unmeasured but likely large.
4. **Investigation-to-decision conversion is ~10%.** 30 of 290
   tempdocs reach ADR status. The other 90% either land code,
   land docs, or die quiet.
5. **Rule machinery is itself young and churning.** 20 rule
   revisions per month. `slice-execution.md` is 13 days old.
   The "lessons learned" surface is not at steady state.
6. **Documentation effort is unusually high** (~33% of commits).
   This is partly necessary for agent context and partly the
   visible cost of agent-driven dev.
7. **Single-developer + Claude operation.** No human team. This
   shapes everything — coordination overhead is zero; review is
   internal; the meta-machinery is the team.

### B. Architectural shape

#### B1. Module-coupling chokepoints

Inbound-edge counts (how many modules depend on each):

| Module | Inbound | Role |
|---|---|---|
| `configuration` | 18 | Universal substrate — almost every module imports |
| `telemetry` | 9 | Observability infrastructure |
| `app-api` | 9 | API contract layer |
| `adapters-lucene` | 9 | Body-side Lucene wrapper |
| `ai-backend` | 7 | Backend abstraction (post-decomp) |
| `ipc-common` | 6 | gRPC proto definitions |
| `indexing` | 6 | Indexing primitives |
| `core` | 6 | Base DTOs (Query, Result.Hit) |
| `app-agent-api` | 5 | Agent contracts |
| `core-contracts` | 2 | Contract-governance substrate |
| `app-services` | 2 | Application orchestration hub |

**Risk profile.** A bug in `configuration` affects 18 dependents.
A change to `app-api` ripples to 9 consumers. The IPC layer
(`ipc-common`) flows through 6 modules. These are the *blast-radius
multipliers* — defects propagate through them.

Curiously, `app-services` (the orchestration hub) has only **2
inbound edges** (`ui` and `app-launcher`). It's the top of the
dependency tree — depends on 18 others, depended on by 2. This is
the correct shape for a composition root, but it also means
`app-services` is "the place where everything plugs together" —
its complexity isn't distributed; it's concentrated.

#### B2. Deletion / cleanup candidates

Empty-module stub check (directories that exist with zero `.java` files):

| Module dir | Java files | Status |
|---|---|---|
| `ai-bridge/` | 0 | Stub remnant of ADR-0017 deletion |
| `app-ai/` | 0 | Stub remnant of ADR-0017 deletion |
| `ai-worker/` | 0 | Stub remnant of ADR-0017 deletion |
| `agent-review/` | 0 | **Also empty — not flagged before** |
| `app-secrets/` | 0 | **Also empty — not flagged before** |

So there are **5 empty module directories**, not 3 as previously
noted in F2. `agent-review` and `app-secrets` are additional stubs
the cleanup sweep missed. Cheap to fix.

Substrate-without-consumer candidates (low inbound + unclear use):

- **`core-contracts`** (12 Java files actual, 2 inbound). Houses
  `BootContract`, `AdvisoryContract`, `BuildContract`, `ContractEmitter`,
  `ContractSampler`, `ContractViolation`, `BootContractRegistry`,
  `BootContractRunner`, `BootContractValidator`, `BootContractAudit`,
  `SampleContract`, `SampleKey`.
  This is the contract-governance kernel referenced by slice 3a-1-8f.
  **AUDITED — see tempdoc 519.** Verdict: no deletions warranted.
  The original "3 zero-consumer types" finding (ContractSampler,
  BootContractRegistry, BootContractValidator) was based on a
  too-narrow Java-source grep that missed: (a) internal cousin-type
  consumption within the module, (b) ServiceLoader SPI surfaces,
  (c) cross-language Python projection consumer, (d) annotation-as-
  use-site rather than annotation-as-import. Each suspect type has
  legitimate consumers; the §X.11 refinement of C-018 explicitly
  covers this case ("type-system refactors where every existing
  callsite is already a consumer"). Secondary finding flagged: the
  BootContract chain is currently a runtime no-op in production
  (ServiceLoader finds zero validators); ContractSampler is wired
  but has no production placement sites. Both are
  substrate-prepared-without-placement, not substrate-without-consumer.

- **`benchmarks`** module — used in CI but is the inner machinery
  still wired? Tempdoc 367 Phase 3 supplemental deleted parts of
  the benchmark infrastructure; verifying the remainder is live.

- **`system-tests`** — only used in tests (per A2.1 in
  module-deps.md). Healthy.

#### B3. Hidden complexity concentrations (LOC)

The load-bearing classes against the 1000 LOC class-size standard:

| File | LOC at audit (2026-05-18 AM) | Current LOC (2026-05-18 PM) | Status |
|---|---|---|---|
| `AppFacadeBootstrap.java` | 2823 (2.8×) | 2837 raw / below 500 NCSS | tempdoc 519 `head-composition-graph` — Step 8+9 done; PMD allowlist removed |
| `IndexingLoop.java` | 1955 (2.0×) | **931** (below ceiling) | tempdoc 516 `indexingloop-size` — decomposed into 18 sibling collaborators in `loop/` |
| `SearchOrchestrator.java` | 1919 (1.9×) | **154** (92% reduction) | tempdoc 517 `search-execution-design` — shipped: thin facade + capture→plan→execute→respond chain |
| `InferenceLifecycleManager.java` | 1486 (1.5×) | 1226 (residual coordinator) | tempdoc 518 — merged; `LlamaServerOps` ratcheted to 1037 LOC; post-merge defect cleanup in flight |
| Total | **8183** | **~5148** | ~37% reduction within hours of issue tempdocs being opened |

**Mega-class concentration substantially addressed within ~24h** of the issue tempdocs being opened. Cross-validation of invariant preservation completed 2026-05-18 PM — all hard invariants (Head/Lucene separation, One-Owner config, GPU mutex protocol, fuzzy correction policy, reason-code allowlist, mode-routing exclusivity, MMF write at mode transitions, taskkill on Windows, stderr crash-diagnostic parsing, breath-holding, migration safety, stage telemetry) preserved across decompositions. One real finding surfaced: 517's privacy-redaction scope narrowed from "all Worker log sites" to "Head HTTP entry boundary" — `SearchExecutor.java:157` now logs raw queries at DEBUG (`docs/observations.md` filed entry).

**Two new decomposition patterns earned canonical promotion** (worth adding to `class-size-standard.md`):

1. **Facade + capture→plan→execute→respond chain** (517) — the orchestration class becomes a thin facade wiring four pipeline-stage collaborators; each stage has its own package; the residual facade's main method is ~4 lines.
2. **Observer-pattern wiring at composition root** (519) — cross-process protocols (the MMF GPU mutex from ADR-0004) move from "X knows about Y's coordination surface" to "X emits events, composition root wires the listener that does Y's coordination." Decouples the protocol producer from the protocol consumer.

**SearchOrchestrator structural read:** the imports span 10+ ops
classes from `adapters-lucene.runtime` (`ChunkSearchOps`,
`CommitOps`, `DocumentFieldOps`, `FacetingEngine`,
`HybridFusionUtils`, `HybridSearchOps`, `IndexCountOps`,
`QueryFilterBuilder`, `ReadPathOps`, `TextQueryOps`) plus IPC types.
The doc comment confirms it owns mode routing (TEXT/VECTOR/HYBRID),
degradation signaling, fuzzy correction chains, facet computation,
and response building. This is the *policy* layer above the *ops*
layer; it's lengthy because policy is conditional. Not unhealthy
in shape, but the size means the test surface for "did the policy
do the right thing in scenario X" is correspondingly large — and
search-pipeline-invariants.md is the contract that holds it
accountable.

**Implications.**

1. The 1000 LOC standard is aspirational, not enforced. No
   ArchUnit rule visible that fails the build when a class
   exceeds 1000.
2. The four files concentrate ~8.2k LOC of complex policy. Any
   refactor of search behavior, indexing behavior, inference
   lifecycle, or Head bootstrap touches one of these.
3. `AppFacadeBootstrap` at 2823 is the standout. The Head's entire
   composition graph is in one file. Splitting it would be
   substantial work but would reduce blast radius.

#### B4. Risk-concentration / bus-factor map

Single points of failure (SPOFs) where a bug has outsized impact:

| Surface | File / location | Why it's SPOF |
|---|---|---|
| Process-coordination contract | `MmfWorkerSignalLayoutV1` (64-byte struct) | All Head↔Worker signaling rides this; offset change is silent corruption |
| Lucene sole writer | `LuceneIndexRuntime` | Only writer to the index; commit-strategy bugs corrupt user data |
| Process spawn | `WorkerSpawner` | All Worker lifecycle (spawn, watch, restart, kill) here |
| App-instance lock | `AppInstanceLock` | Single lock prevents data-dir corruption from concurrent instances |
| Index-root lock | `IndexRootLock` | Prevents two Workers fighting over same index |
| Head composition | `AppFacadeBootstrap` (2823 LOC) | Wiring graph for everything Head-side |
| Search policy | `SearchOrchestrator` (1919 LOC) | Sole decision tree for query routing |
| Indexing state machine | `IndexingLoop` (1955 LOC) | Sole driver of indexing lifecycle |
| Inference lifecycle | `InferenceLifecycleManager` (1486 LOC) | Sole driver of llama-server transitions |
| Schema field catalog | `SSOT/catalogs/fields.v1.json` + classpath copy | Drift here corrupts indexing semantically |

Most of these are intentional SPOFs — concentrating ownership is
the "One Owner" policy from ADR-0001. The concern isn't that they
exist; it's that the *test coverage* matches the *blast radius*.
The lifecycle-contract tests, ArchUnit guardrails, MMF compat
tests, and contract tests on `/api/health` collectively defend
some of these. Others (e.g., AppFacadeBootstrap composition
correctness) are mostly defended by integration smoke alone.

#### B5. Invariant enforcement — ArchUnit + runtime guards

Ten ArchUnit-style test files exist across modules:

```
modules/adapters-lucene/.../AdaptersLuceneGuardrailsTest.java
modules/ai-backend/.../ArchUnitEgressTest.java
modules/api-contract-projection-java/.../ContractGovernanceArchUnitTest.java
modules/app-api/.../ArchitectureRulesTest.java
modules/app-launcher/.../LayeringEnforcementTest.java
modules/app-observability/.../DiagnosticChannelArchUnitTest.java
modules/app-services/.../AppServicesWorkerGuardrailsTest.java
modules/core/.../ArchUnitSanityTest.java
modules/indexer-worker/.../IndexerWorkerGuardrailsTest.java
modules/ui/.../UiApiGuardrailsTest.java
```

Spot-check (`AppServicesWorkerGuardrailsTest`) shows two real
invariants enforced:

1. **No ad-hoc `System.getenv/getProperty/setProperty`** in
   `app-services` package — forces config through `EnvRegistry`.
   Has an allowlist of 7 legacy callsites with explicit
   migration intent: "Prefer migrating these to
   `modules/configuration` over adding new allowlist entries."
2. **No `java.nio.MappedByteBuffer`** outside `MainSignalBus` —
   isolates MMF usage to the documented signaling class.

This is healthy: invariants compile-checked, allowlists documented,
direction of travel stated. ArchUnit catches drift at build time
rather than relying on convention.

**Hard invariants from CLAUDE.md vs. enforcement:**

| Invariant | Enforcement |
|---|---|
| "Head never touches Lucene" | Architectural via `app-services` ↛ `adapters-lucene` (Head modules don't depend on `adapters-lucene` — only `ui`, `app-launcher`, `system-tests` do; gRPC is the boundary). Soft. |
| "Loopback-only network" | Runtime — `LocalApiServer` binds to `127.0.0.1`. `LocalApiCorsPolicyTest` regression-tests CORS allowlist. |
| "No legacy endpoints" | Convention; surfaced via the API contract map. Not statically enforced. |
| "Verify, don't guess" | Convention; expressed via `/api/health` schema test and state-polling patterns in tests. |

The first invariant (Head/Lucene separation) is the most important
and is only architecturally enforced (no Head module imports
`adapters-lucene` directly). A future refactor that added that
import would bypass the architectural check unless an ArchUnit
rule is added. Currently no such rule visible in the spot-checks I
ran.

### B — Synthesis

1. **`configuration` is the universal substrate** — 18 modules
   depend on it. A bug there affects everything.
2. **`AppFacadeBootstrap` is the largest single concentration of
   complexity** at 2823 LOC, 2.8× the documented class-size
   standard. The Head's entire wiring graph lives there.
3. **Four mega-classes** (`AppFacadeBootstrap`, `IndexingLoop`,
   `SearchOrchestrator`, `InferenceLifecycleManager`) contain
   ~8.2k LOC of policy code, all violating the 1000 LOC ceiling.
   The standard is aspirational, not enforced.
4. **5 empty module directories** (`ai-bridge`, `app-ai`,
   `ai-worker`, `agent-review`, `app-secrets`) — 2 more than F2
   originally identified.
5. **`core-contracts` (12 files, 2 inbound) was audited — see
   tempdoc 519.** No deletions warranted. The "3 zero-consumer
   types" finding was a false positive from too-narrow grep
   methodology. Secondary finding: the BootContract chain is a
   runtime no-op today (no production validators registered);
   substrate-prepared-without-placement is its accurate framing,
   not substrate-without-consumer per C-018 §X.11.
6. **ArchUnit invariants are well-defined where they exist** (no
   ad-hoc env reads, MMF isolation, dependency direction). The
   most important invariant (Head/Lucene separation) is enforced
   structurally but not by an explicit ArchUnit rule I found.
7. **Most SPOFs are intentional** (One Owner policy). Test
   coverage matches blast radius for some (lifecycle contract,
   MMF compat) but not others (Head wiring correctness).

### C. Process / discipline effectiveness

#### C1. Which rules have actually fired with citations

`agent-lessons.md` is the high-density citation surface
(consistent with its 15 commits / 2 months of churn from A4):

| Cited incident | Lessons it grounds |
|---|---|
| **Slice 447** (5+ refs, §X.11, §X.11.2, §X.12, §X.12.10) | Substrate without consumer (C-018); Pass-8 mandatory second-agent verification; static-callsite + wire-emitter checks; "C-018 governs new slots, not refactors" refinement |
| **Slice 481** (2 refs) | Pass-8 verdicts as merge gates not discussion items; override via mandate-citation = anti-pattern (3,217 LOC substrate shipped without consumers via `requires-pass-3` override) |
| **Slice 486** (3 refs, §15.2, §27) | Read the source-of-truth catalog row verbatim before scoping (G34 vs G37 confusion shipped two G36-widening features under wrong IDs) |
| **Tempdoc 423** (3 refs, §14.16, §14.19, §14.19.1) | Subagent MCP access; Edit-tool schema validation as probe; Read-tool silent truncation layers; scoop shim junctions unreachable from session |
| **Slice 497** (1 ref) | LLM is a loadable tool, not external dependency — "before declaring any capability unavailable, check whether you have a tool that provides it" |
| **Slice 486 + 487 + 489 cross-refs** (1 ref) | Inbound-citation sweep when renaming tempdoc sections |
| **Tempdoc 275** (1 ref) | Cold-start baseline reference numbers |

CLAUDE.md itself cites few specific incidents (4 cited), because
its function is the *summary surface*; the detail lives in
`agent-lessons.md` and `slice-execution.md`. Rules with the
strongest evidentiary backing are the Pass-8 / C-018 / Tier-3
verification cluster — all motivated by slice 447's repeat
failures.

**No-citation rules** (rules whose original incidents are
implicit, not named): the basic Explore-Before-Implementing rule,
the Fix-Root-Causes-Not-Symptoms rule, the Verify-Your-Work rule.
These were presumably motivated by older incidents not formally
captured.

#### C2. Pass-8 outcomes — the slice 447 case

The canonical Pass-8 case lives in
`the retired 421 FE-rewrite draft CONFLICT-LEDGER.md`.
Reading it directly, **the C-015 family is a five-conflict cascade**
that took >3 months to resolve and explicitly cites a "doc-lie":

> "Initial closure narrative was a doc-lie (the autonomous run
> shipped 3 substrate-without-consumer instances despite the
> Pass 6 mandate). Honest closure: Pass 8 second-agent
> verification (447-followup/1.1) caught the violations;
> rollbacks applied — `Resource.recovery` widening rolled back
> (447-followup/1.2 commit 0f5485825); `AvailabilityExpression`
> rolled back (447-followup/1.3 commit 20afc6a17);
> `OperationHistoryEntry.affectedResources` deleted as phantom
> (447-followup/1.4 commit c815c703b)."

**Pass-8 saved-us record:**
- 6 implementation slices shipped (447-impl-A through E + 442)
- Self-declared "done"
- Pass-8 verification (via Explore subagent dispatch) caught **3
  substrate-without-consumer violations**
- 3 rollback commits (0f5485825, 20afc6a17, c815c703b)
- 1 mapping escalated to named follow-up slice (089f434fc)

This is **direct empirical evidence Pass-8 catches what
implementation-agent self-validation misses.** The "doc-lie"
phrase is itself doing work — formalizing that single-agent
self-validation systematically produces false-positive
closure narratives.

**The §X.11 refinement note** is its own lesson: C-018 was
*misapplied* as a too-broad deferral trigger.

> "C-018 governs new substrate slots that nobody reads, NOT
> type-system refactors where every existing callsite is already
> a consumer. Conflating 'no NEW consumer' with 'no consumer at
> all' caused §4.A (rename) + §4.E (partition) to be deferred
> when both have full existing consumer surface from day one."

So the discipline rule itself drifted. The fix was to refine the
rule's *meaning*, not to add another rule. This is healthier than
adding rules — narrowing existing rules to their proper scope.

#### C3. Audit-yielded-wrong-conclusion pattern

Named cases:

1. **Tempdoc 403 Tier C** — Audit said `analyzerRegistry` was the
   only restart blocker for `LuceneLifecycleManager`. Partial fix
   shipped. Regression test then revealed two more blockers
   (state machine + `indexingCoordinator`). The audit was wrong;
   the test would have caught it in minutes.
2. **Slice 447 §X.11** — Audit conclusions about which fields had
   consumers and which didn't were systematically over-confident.
   3 substrate slots survived the audit but failed the Pass-8
   verification.
3. **Slice 486 §27** — Reading "next item" from a running summary
   instead of from the catalog row. Two features shipped under
   IDs G34 + G37 that didn't match the catalog definitions.
4. **Slice 481** — Pass-8 verdict (`requires-pass-3`) overridden by
   the implementing agent citing a user mandate; 3,217 LOC
   shipped to main that the verdict said wasn't ready.

**Pattern across all four**: single-agent static analysis (whether
audit, summary read, or self-verification) systematically under-
weights cases the agent didn't think to look for. The "audit was
wrong" failure mode isn't about agent incompetence — it's about
the closed loop of "I'll verify my own conclusion." Pass-8 fixes
this by making verification an *other-agent* responsibility.

**The rule now reads** (from `slice-execution.md` via §X.12.10):
*"Pass 8 verdicts are merge gates, not discussion items"* — meaning
you can't talk-your-way past a Pass-8 verdict; only an explicit
per-commit user authorization (recorded in commit message + ledger
+ named follow-up slice) overrides it. Slice 481 is the case
study of what happens without that gate.

#### C4. Documentation drift — known issue, partial mitigation

Doc-audit tempdocs and recent activity:

| Tempdoc | Subject |
|---|---|
| 379 | canonical-doc-refresh |
| 399 | claude-md-audit-and-harness-drift-followups |
| 423 | claude-code-environment-survey (the meta-research on Claude Code itself) |
| 424 | agent-quality-axes-investigation |

The `/doc-audit` skill exists (per CLAUDE.md skill registry) and
the `node scripts/docs/llmstxt-generate.mjs --check` script
exists as a docs-lint gate. The PostToolUse hook
`docs-regen-hint` fires after edits to canonical doc paths. So
the *mechanism* for drift detection exists.

**But:** I've already documented multiple drift instances:
- Phase plan (`docs/meta/plan-implementation-phases.md`) lists
  Phase 4a as planned-and-shipped; engine was deleted; doc not
  updated.
- Empty module directories `ai-bridge/` etc. remained for ~6
  weeks after ADR-0017.
- 22 tempdoc status values vs. the README's 5.
- `PathNormalizer` doc-vs-reality drift (resolved 2026-04-27 per
  observations.md).
- `Tempdoc 423 §14.19` Read-tool truncation undocumented for
  some time before being captured.

**Drift mitigation is reactive** (caught by audit) rather than
*structural* (prevented at write time). The doc-audit tempdocs
are themselves the audit instances; between audits, drift
accumulates. A more structural mitigation would be ArchUnit-style
tests for canonical-doc claims (compare what doc says about
module X to what module X actually contains).

#### C5. CLAUDE.md as time-series

CLAUDE.md is 303 lines (more compact than I previously estimated).
Recent commits:

| Date | Subject |
|---|---|
| 2026-05-16 | docs: add agent lesson — LLM is a loadable tool |
| 2026-05-14 | chore: working-tree state — skills/docs/rules sweep |
| 2026-05-12 | chore(367): DAG runner family burndown |
| 2026-05-08 | feat(428): repo entrypoint + security automation repair |
| 2026-04-28 | docs(tempdoc 423): Claude Code agent environment survey |
| 2026-04-26 | docs(ui-explorations): noncanonical exploration directory + cross-ref |
| 2026-04-26 | chore(ci): extend manual-only policy to all self-hosted workflows |
| 2026-04-25 | feat(ci): govern policy-covered stress suites |
| 2026-04-24 | feat(406): close P4d-P4e |
| 2026-04-24 | feat(ci): workflow-signal-health report |
| 2026-04-24 | docs: agent rules + CLAUDE.md + observations inbox updates |
| 2026-04-23 | docs+ci: adopt manual-only CI policy (ADR-0026) |

**Cadence**: ~2-3 day intervals. CLAUDE.md gets a touch every
few days, almost always for new lessons, new rules, or new
discipline refinements.

**Content type**: the recent updates are dominated by *agent
discipline* additions (the LLM-loadable lesson, manual-CI
policy ratification, security automation, agent rules sweep).
Very few touches are about the *product*; almost all are about
the *agent operating environment*.

This is a healthy pattern for an agent-driven codebase (the
operating environment needs to be kept current) but it's also
direct evidence of what the codebase is investing in: **the
operating environment for agents** is a primary first-class
artifact, updated more frequently than most product surfaces.

### C — Synthesis

1. **Pass-8 is doing real work.** Slice 447 is the clearest
   evidence — 3 substrate-without-consumer violations caught,
   3 rollback commits, 1 follow-up slice spawned. The
   "doc-lie" phrase is the project formalizing that
   single-agent self-validation produces false closure.
2. **The discipline rules themselves drift in meaning.** C-018
   was misapplied as a too-broad deferral trigger; the §X.11
   refinement narrowed it. Even the rules need maintenance.
3. **The audit-was-wrong failure mode is named and recurring.**
   Tempdoc 403 Tier C, slice 447 §X.11, slice 486 §27, slice
   481 are all instances. The mitigation (Pass-8 second-agent
   verification + named follow-up slices for deferred work)
   is structural.
4. **Doc drift is reactive, not structural.** The mechanism
   exists (`/doc-audit` skill, llmstxt-check, post-edit hooks)
   but drift still accumulates between audits.
5. **CLAUDE.md is a churning operating-environment artifact.**
   Updated every 2-3 days, almost entirely with agent-discipline
   refinements, not product content. The agent operating
   environment is itself a primary first-class artifact of this
   codebase.
6. **10 active worktrees** carry parallel agent sessions
   working on the 499/502/507/508/509/510-series frontend
   framework kernel work — each with its own
   `CONFLICT-LEDGER.md`. Parallelism is real but the kernel
   draft (`the retired 421 FE-rewrite draft `)
   is the focal artifact most of this energy converges on.

### D. Forward-looking analyses

#### D1. Upstream-shock resilience inventory

Pinned version-catalog versions plus the abstraction-layer
between codebase and each upstream:

| Dependency | Version | Abstraction layer | Shock exposure |
|---|---|---|---|
| **JDK** | 25 | None — language runtime | **High** — JEP 514 AOT, FFM API changes, Vector API removal each could break Worker startup. Note: `--add-modules=jdk.incubator.vector` was already removed for AOT compatibility (tempdoc 269 §D4a). |
| **Lucene** | 10.4.0 | `JustSearchCodec` extends `Lucene104Codec` | **Medium** — codec wrapper means a Lucene 11 base codec name change is a one-file fix; index format compatibility is the real concern. SSOT fingerprint detects drift. |
| **Tika** | 3.2.3 | Direct usage in `PolicyDrivenTikaExtractor`, `StructuredContentExtractor` | **High** — Tika API used directly across multiple extraction classes; major version bump would ripple. Also the CVE attack surface (G1 in gaps analysis). |
| **Jackson** | 3.1.0 | Annotation-based throughout | **High** — Jackson 3 is itself the upcoming break from 2.x; the codebase has already adopted 3.1, so this risk is paid forward but the codebase is sensitive to 3.x patch changes. |
| **gRPC** | 1.79.0 | `RemoteKnowledgeClient`, server interceptors | **Medium** — well-abstracted; channel/stub usage is concentrated. |
| **Protobuf** | 4.33.5 | Auto-generated stubs via `ipc-common` | **Medium** — generated code regenerates cleanly but proto API changes (e.g., field number reuse rules) could break wire compat. |
| **OpenTelemetry** | 1.60.1 | `LocalTelemetry` + `MetricCatalog` (ADR-0027) | **Low** — ADR-0027 typed instruments give a real adapter layer. OTel API changes mostly absorbed. |
| **Netty** | 4.1.131 | Indirect via gRPC + Javalin | **Low** — transitive; pinned by parent libs. |
| **ONNX Runtime** | 1.24.3 | `OrtSessionAssembler`, `NativeSessionHandle`, `OnnxSessionCache`, `OrtCudaHelper` | **High** — but well-isolated. Multiple ORT-related abstraction classes precisely because this surface has been painful (G29 cast-node hang, CUDA provider quirks). |
| **DJL Tokenizers** | 0.36.0 | Wrapped in inference modules | **Medium** — tokenizer compatibility ties to the embedding model; locked together. |
| **SQLite (xerial)** | 3.51.2.0 | Direct usage for `jobs.db` + entity sidecar | **Low** — schema migrations possible; rare breaking changes. |
| **Javalin** | 6.7.0 | `LocalApiServer` + routes | **Low** — REST surface only; route registration centralized. |
| **CEL** | 0.12.0 | Rule evaluation in agent runtime | **Low** — narrow use surface. |
| **HdrHistogram** | 2.2.2 | Direct in telemetry | **Low** — stable lib. |
| **llama.cpp** (native, pinned build) | `third_party/llama.cpp/` | `LlamaServerOps` HTTP wrapper | **High** — bundled binary; upstream API changes (HTTP/props/health shape) handled but VRAM behavior, model format support, and binary distribution are all upstream-controlled. |
| **Models** (gte-multilingual-base, NER, SPLADE, reranker, citation, Qwen 3.5 VLM) | Manifest-pinned | `ModelManifest` + `model-registry.v2.json` | **High** — if a HuggingFace model is delisted, the download URL breaks. SHA-256 verification catches integrity but not availability. No mirror visible. |
| **NSIS / Tauri / React / Vite / TailwindCSS** | Various | Build/UI | **Medium** — Tauri 3 transition is a future shock; React 19 likewise. |

**Most exposed surfaces** (high risk + thin abstraction):

1. **Tika** — direct API usage across extraction; CVE-prone domain.
2. **JDK 25** — relatively new release; FFM / AOT semantics evolving.
3. **Jackson 3.x** — fresh major version; deserialization changes mid-flight.
4. **llama.cpp** — pinned upstream binary with no fallback build path documented.
5. **Models on HuggingFace** — no mirror documented; availability is single-vendor.

**Best-abstracted surfaces** (high coupling but real adapters):

1. ORT — `OrtSessionAssembler` family. Pain-driven, well-modularized.
2. Lucene — `JustSearchCodec` + commit-metadata fingerprinting.
3. Telemetry — `MetricCatalog` (ADR-0027) absorbs OTel API.
4. gRPC — concentrated client + interceptor layer.

The gradient correlates with how much the codebase has been *forced*
to engage with each surface. ORT and Lucene got abstraction layers
because they hurt. Tika and Jackson haven't yet.

#### D2. Substrate trajectory — what consumers does each 500s slot promise?

Sampling current 500-series tempdocs:

| Tempdoc | Status | Named consumers |
|---|---|---|
| **510 ai-aware-shell** | Implemented + framework-absorb refactor complete (live-verified 2026-05-18) | HealthSurface, BrainSurface, BrowseSurface, ConversationListStore, `<jf-capability-pills>`, AiStateStore, Stage `data-ai-available` CSS — concrete shipped consumers |
| **509 operation-label-coherence** | (not read in depth) | Operation catalog ID resolution; consumers in HealthSurface, BrainSurface |
| **508 plugin-ecosystem-substrate** | Design | "Three layers": contribution vocabulary, discovery surface (command palette), development loop. **No named consumer slice yet — design-stage**. |
| **508 coherent-ai-presence** | (separate doc, same number — drift) | (need read) |
| **507 capability-mediated-surface-architecture** | (not read) | PluginHostApi boundary |
| **506 horizon-3-ecosystem** | Horizon-3 (future) | (forward-looking) |
| **505 horizon-2-compositional-ui** | Horizon-2 | (forward-looking) |
| **502 boot-composition-architecture** | (active worktree) | Bootstrap path consumers |
| **501 runtime-port-discovery-architecture** | (worktree) | Port-discovery consumers |
| **500 mcp-protocol-surface** | (active) | MCP server clients |
| **511 aggregate-surfacing-substrate** | design pending — retraction of `511-wire-field-surfacing.md` | (cited consumer surfaces: `<jf-action-button>`, command palette filter, item-operation rendering) |

**C-018 check applied to recent shipped substrate (510):**
The framework-absorb refactor for 510 names concrete consumers
(HealthSurface, BrainSurface, BrowseSurface, etc.), has live
verification (2026-05-18), and went through critical-analysis
gap fixes. This is the **healthy substrate-shipping shape**.

**Design-stage tempdocs (507, 508, 506, 505)** are forward-looking;
their C-018 risk is real but they're not shipping yet. The
substrate-without-consumer risk for these is "if we ship before
the consumer slice is named, C-018 fires."

**Two tempdocs sharing number 508** — `508-coherent-ai-presence.md`
and `508-plugin-ecosystem-substrate.md` — is itself a *discipline
drift* signal. The numbering scheme assumed unique IDs.

#### D3. Minimum-shippable thing (technical, not commercial)

Given current state, what's the smallest cohesive subset that
could be packaged, signed, distributed, and used by a non-developer?

**What's load-bearing and present:**
- Three-process architecture working (Head/Body/Brain)
- Search pipeline (BM25 + Dense KNN + SPLADE + RRF + LambdaMART
  + cross-encoder)
- File ingestion (Tika + ONNX embedding)
- RAG with two-pronged citations
- llama-server integration (CPU-default per ADR-0024 v1)
- NSIS installer mechanism (ADR-0024)
- Lucene index lifecycle including blue/green schema migration
- gRPC IPC + MMF signaling
- React UI on Tauri shell

**What's load-bearing and missing (from gaps analysis):**
- Code signing (G1) — without it, every user sees a SmartScreen warning
- Auto-update channel (G8) — no path from fixed bug to user
- First-run UX polish (G7) — model download flow not visibly designed end-to-end
- Privacy boundary documentation (G10) — local-first claim isn't auditable
- Power awareness (G6) — laptops get cooked
- Real-corpus eval (G2) — quality metrics aren't measured against the actual product target

**What's load-bearing on the *user reach* path:**
- At least one of: system tray quick-search, global hotkey, file
  association, browser extension (G5). Without any of these, the
  user must open the app to use it.

**What's *not* load-bearing for v1:**
- Plugin ecosystem substrate (508+)
- Capability-mediated surface architecture beyond the current
  consumer set
- Horizon-2 compositional UI (505)
- Horizon-3 ecosystem (506)
- The MCP server (500) for agent use
- The 421 frontend framework kernel work
- Most of `app-observability` substrate beyond basic telemetry
- `core-contracts` contract-governance kernel (if it's
  substrate-without-consumer)
- `benchmarks` module (if it's not actively used)
- Agent-analytics pipeline (`docs/explanation/21`)

**Reading**: roughly 80% of what's there could ship as v1.
Roughly 20% of recent substrate work doesn't intersect the v1
critical path. That's not a problem if the user wants both (a) +
(b), but it's worth knowing the split.

#### D4. The forking question — where would you cut?

If the project were forked tomorrow with "keep only what is
necessary for the core local-first search-with-LLM product",
likely cuts:

| Surface | Cut rationale |
|---|---|
| `agent-review` module | Empty stub |
| `app-secrets` module | Empty stub |
| `ai-bridge`, `app-ai`, `ai-worker` directories | Empty stubs from ADR-0017 deletion |
| `core-contracts` | Audited (tempdoc 519) — keep. `@BuildContract` is applied at production sites (`NativeSessionHandle` etc.); `BootContractRunner.validateAll()` is called at `IndexerWorker` boot; `ContractEmitter` feeds the Python projection. Chain is a runtime no-op today but the substrate is wired and waiting. |
| `benchmarks` module | If only invoked by deleted DAG runners, candidate for trim |
| `app-observability` plugin-recovery-overlay substrate | Plugin V1.5 dependent; the production app doesn't need it |
| Most of `the retired 421 FE-rewrite draft ` | Substrate work; v1 ships with current React/Lit hybrid |
| `agent-analytics` hooks tree (the LLM-as-judge scoring path) | Per observations.md #94, the consumer was never built; tracking left intact as a record |
| MCP server (production) | Optional integration; not a v1 product feature |
| Slice-execution methodology + Pass-8 infrastructure | A development-time concern; doesn't ship in the binary |
| Bench/eval harness beyond `jseval`'s minimum | If a v1 eval is "real-corpus retrieval", the BEIR/SciFact infrastructure becomes optional |

**Estimated remainder**: roughly 70–75% of the current Java
codebase + most of `modules/ui-web` + `modules/shell` + the
installer scripts. The cuts are dominantly *substrate-and-meta*,
not product code.

**What this reveals**: the *product* and the *substrate* are
already separable. The product surface is reasonably bounded;
the substrate has been growing. A v1 fork would mostly cut
substrate. That's good news for shippability and consistent with
the (a)+(b) framing: substrate work is investment for later,
product work is investment for now.

#### D5. Next-effort prioritization (technical only)

Given the G1–G14 gap analysis + the C-018 risk + the discipline
of focus on consumer-having substrate:

**P0 (the v1 ship-blockers):**
1. **G7** (first-run experience) — every user's first moment with the product
2. **G8** (auto-update channel) — without it, bug fixes don't reach users
3. **G1.5** (code signing only) — directly blocks G7 (SmartScreen)

**P1 (the credibility-of-the-product layer):**
4. **G2** (real-corpus eval) — without this, search-quality claims are unanchored
5. **G1** (extraction sandbox + prompt-injection defense) — security debt
6. **G3** (long-running stability test) — a desktop app that leaks dies slowly

**P2 (the user-reach + integration layer):**
7. **G5** (at least one of: system tray, global hotkey, file association)
8. **G6** (battery/power awareness)
9. **G9** (accessibility) — open in known-issues, low marginal cost to start

**P3 (the trust-statement layer):**
10. **G10** (privacy boundary doc) — auditable claim
11. **G11** (PII handling during indexing) — content-aware redaction

**Substrate work that has C-018 risk now:**
- 508 plugin ecosystem substrate — should not ship until the
  first plugin's consumer slice is named
- 506 / 505 horizon-2/3 — explicitly forward-looking; OK if
  marked accordingly
- 502 / 507 / 509 / 511 — verify named consumers in design
  docs before substrate lands

**This isn't a roadmap.** It's a dependency-ordering claim. P0
work unblocks shipping; P1 unblocks credibility; P2 unblocks
reach; P3 unblocks trust. Each P-level is a logical prerequisite
for the next.

### D — Synthesis

1. **The most exposed upstream surfaces are Tika, JDK 25,
   Jackson 3.x, llama.cpp, and the HuggingFace model
   distribution.** Of these, ORT and Lucene are well-
   abstracted because they've hurt before; Tika and Jackson
   haven't yet been forced into abstraction.
2. **510 (ai-aware-shell) is the healthy C-018 case** — named
   consumers, live verification, gap fixes, retro on bugs
   surfaced during verification. The shape of substrate that
   *passes* the discipline.
3. **508+ design-stage tempdocs carry C-018 risk** — not yet a
   problem; they're not shipping. The discipline test fires
   when substrate lands.
4. **Two tempdocs sharing number 508 is itself a discipline
   drift signal.** The numbering convention isn't enforced.
5. **The minimum-shippable thing is mostly already there.**
   The cuts to reach it are dominantly substrate-and-meta,
   not product. This validates the (a)+(b) framing: product
   and substrate are separable.
6. **Forking out the substrate would keep ~70-75% of code**
   and produce a focused v1. The substrate isn't *load-bearing*
   for v1; it's investment for later versions.
7. **The next-effort dependency order is:** ship-blockers
   (first-run, updates, code signing) → credibility
   (real-corpus eval, security, stability) → reach (desktop
   integration, power awareness, a11y) → trust (privacy,
   PII). Each layer logically prerequisites the next.

---

## Phase 2 — Overall synthesis

The deep-dive analyses converge on a coherent picture, which I
record here for the next conversation to pick up cold:

**The codebase has two distinct shapes** that have been growing
in parallel:

1. **The product surface** — three-process search engine, hybrid
   retrieval, RAG, local LLM. This is bounded, well-architected,
   and substantially complete relative to a v1 ship.
2. **The substrate/meta surface** — the agent operating environment,
   the frontend framework kernel draft, the contract-governance
   kernel, the plugin ecosystem substrate, the discipline machinery,
   the eval/benchmark harness, the CONFLICT-LEDGER pattern. This
   is open-ended and accumulating.

The two are separable. The fork analysis (D4) shows ~70-75% of
code stays if substrate is cut; the substrate work isn't load-
bearing for the product's v1 critical path.

**The discipline machinery does real work** (C2 slice 447 case is
the clearest evidence — 3 substrate-without-consumer rollbacks
caught by Pass-8). But the discipline machinery is also itself a
substrate, growing at ~20 rule revisions/month, with rule meanings
themselves drifting (C-018 misapplication §X.11).

**The gaps cluster around the human user** (Phase 2 G-series).
The substrate work clusters around the agent and the framework.
The product work clusters around the search engine. These three
investment vectors run roughly parallel — each independent of the
other.

**The development style is empirically validated as agent-driven
solo development.** 95% single-author, 3,652 commits in ~6 months,
~33% docs-commit ratio, 22 status values for a 5-value
taxonomy, 290+ tempdocs, ~10% tempdoc→ADR conversion. None of
these are pathological in isolation; together they paint the
fingerprint of the development style.

**The discipline rules themselves are young.** `slice-execution.md`
is 13 days old. `agent-lessons.md` has averaged a new lesson every
~4 days. The operating environment is not at steady state — every
week a new failure mode surfaces and gets formalized. This is the
*experiment side* of (a)+(b) doing real work; the cost shows up as
20 rule revisions/month and the "doc-lie" admission in CONFLICT-LEDGER.

**Forward-looking risk concentrates in upstream dependencies** that
haven't been forced into abstraction (Tika, Jackson 3.x, llama.cpp,
model availability) and in C-018 risk on design-stage substrate
(508+, forward-horizons 505/506). The product-side ship-blockers
(first-run UX, update channel, code signing) are not currently the
focus of agent activity — the substrate work is.

This is internally coherent. The user's (a)+(b) framing
predicts exactly this shape: the product is being kept ship-
adjacent while the substrate is being invested in for later.
Whether the substrate investment will earn out is the open
empirical question — the C-018 discipline + Pass-8 verification
are the project's attempts to make sure it does.

---

The next move is the user's. This doc is the artifact the conversation
continues from.

---

## Follow-up: meta-machinery slimming (2026-05-18, continuation conversation)

This section appended in the continuation conversation that began with
the user requesting a critical analysis of `CLAUDE.md` and
`.claude/rules/*.md`. The work below partially addresses §F5 (discipline
machinery fighting fires it lit) and Open Question §4 ("which rules
have actually fired in the last month, and which would you not miss
if they were retired?").

### What was changed (all doc-only, no product code touched)

**Deleted**:
- `.claude/rules/hooks-reference.md` — documented ~14 user-script hooks
  (`bash-guard.mjs`, `intervene.mjs`, `repeat-guard.mjs`, `build-counter.mjs`,
  `compact-save.mjs`, `ui-shot-hint`, etc.) that **do not exist on disk**.
  Verified: no `hooks` block in `.claude/settings.json`, no hook scripts
  under `.claude/`, no hook scripts in user-scope or plugin scope. The
  Read-auto-limit behavior described as `intervene.mjs` is actually a
  Claude Code built-in, not a project hook. The `claude-notifications-go`
  plugin fires Stop/SubagentStop/Notification hooks; nothing else fires.
- `.claude/rules/deprecated-modules.md` — empty live content (the three
  modules it listed as deprecated had all been physically removed).

**Created**:
- `docs/reference/contributing/agent-postmortems.md` — extracted
  substrate-discipline reference cases out of `CLAUDE.md` and
  `agent-lessons.md` prose into 9 named handles. Loads on-demand, not
  every session.

**Slimmed**:
- `CLAUDE.md`: 23,813 → ~17,400 B. Restructured under the canonical
  preamble. Trimmed "Detailed Workflows" link list, "Key API Endpoints"
  table, "Pipeline profiling" block, "Hot-reload" detail — all into
  one-line pointers to existing skills / canonical docs.
  **Agent Discipline section preserved verbatim** at user instruction
  (the user explicitly said not to move it to a skill).
- `.claude/rules/agent-lessons.md`: 15,317 → ~4,500 B (70% reduction).
  Substrate-discipline prose moved to postmortems; pipeline-profiling
  + dev-stack duplication cut (already covered by `/jseval` and
  `/dev-stack` skills); platform constraints kept.
- `.claude/rules/branch-safety.md`: replaced false "Enforced by
  `bash-guard.mjs`" claims with "Destructive git commands — do not run"
  (the rules stand as guidance; no fake mechanical-enforcement claim).
- Added per-file token budgets to remaining rule files
  (`common-workflows.md`, `slice-execution.md`, `context-efficiency.md`,
  `branch-safety.md`) as a deletion trigger for future drift.

**De-jargonized**: the "Pass N" taxonomy was stripped from `CLAUDE.md`,
`agent-lessons.md`, and `agent-postmortems.md`. Web research (separately
documented in the continuation transcript) found:

- Zero hits for `path:CLAUDE.md "Pass 1" "Pass 2"` on GitHub
- No other public CLAUDE.md / AGENTS.md uses numbered Pass-N terminology
- Anthropic's published guidance is the opposite direction
  ("Bloated CLAUDE.md files cause Claude to ignore your actual
  instructions… 70% prose adherence vs 100% hook enforcement")

The substantive disciplines were renamed to what they actually mean:
`pass-8-not-optional` → `independent-review-required`, "Pass 8 verdict"
→ "independent reviewer's verdict", etc. The canonical
`slice-execution.md` long-form was left untouched at the user's
direction so the methodology survives in one place.

### What was NOT done (with reasons)

- **Agent Discipline → skill**: user explicitly directed to keep in
  CLAUDE.md. No change.
- **Pass-N renames in `slice-execution.md` canonical doc**: user directed
  to preserve the long form. No change.
- **Slice-execution rule-file → skill**: would have low yield; the rule
  file is now a 79-line budget-headed quick-ref pointing to canonical.
- **Restoring the documented-but-missing hooks**: substantial work; this
  is itself a candidate slice. The canonical-doc drift in
  `docs/explanation/21-agent-analytics-pipeline.md` (which describes
  the hook pipeline as implemented) was logged to `observations.md`.

### Open questions surfaced for the user

1. **Hooks vs. prose** (Anthropic's strongest published recommendation):
   `.claude/rules/branch-safety.md` lists destructive git commands "do
   not run" as advisory prose. With zero hooks, every "do not X" rule
   is advisory only. Worth a dedicated slice: implement
   `bash-guard.mjs` (and possibly `repeat-guard.mjs`,
   `build-counter.mjs`) as actual `PreToolUse` hooks, closing the gap
   between rule statement and enforcement.

2. **Accretion gate.** Git log shows 56 commits to `CLAUDE.md`, growing
   from 18,813 to 23,813 B in ~3 weeks before this rewrite. The
   accretion pattern ("agent learned X → append paragraph") will resume
   unless either (a) a rule is added like "before appending to
   CLAUDE.md, check broad applicability", or (b) a hook gates
   Edit-to-CLAUDE.md.

3. **What to do with `docs/explanation/21-agent-analytics-pipeline.md`**.
   Describes hooks that don't exist. Restore or rewrite the doc.

4. **Verification of OQ4** — empirical question still open: which
   discipline rules actually fired in the last month, and which
   wouldn't be missed? Could be answered by `git log --grep="Pass"`,
   CONFLICT-LEDGER row scan, or transcript JSONL search.

### Pointer

For the named-handle reference cases extracted from this work:
`docs/reference/contributing/agent-postmortems.md`.

### Audit retraction (2026-05-18, same day)

A substantial part of the follow-up above rests on the claim "hooks do not
exist on disk." That claim is **wrong**.

Verified after the user prompted "is there further custom bash level rules
in this codebase for agents?": the hooks DO exist at
`scripts/agent-analytics/hooks/*.mjs` and are wired via
`.claude/settings.local.json` → `hooks`. Sixteen hooks are active across
eight event types. `bash-guard.mjs` is the actual mechanism cancelling the
sleep commands the user has been seeing.

The audit subagent that informed the rewrite probed only `.claude/settings.json`
(213 bytes, no hooks block) and the user-scope `~/.claude/`. It did not check
`.claude/settings.local.json` (7,144 bytes, the actual hook wiring, checked
into git for this repo) or grep `scripts/` for hook script files. Both were
trivially available.

**What was incorrectly stated:**

- "`.claude/rules/hooks-reference.md` documented ~14 hooks that don't exist."
  → The doc was substantially accurate. It described real hooks with correct
  behavior. Deletion in commit `263fc53f4` is reverted.
- "The branch-safety.md 'Enforced by bash-guard.mjs' claims are false." →
  They are true. Reverted.
- "Canonical doc `docs/explanation/21-agent-analytics-pipeline.md` describes
  a hook pipeline that does not exist on disk." → It is accurate. The
  observations.md Inbox entry making this claim is retracted.
- "Tempdoc 520 proposes to implement the missing hooks." → Tempdoc 520 is
  reframed: hooks exist; the slice is now hardening of nine specific defects
  surfaced in the critical-analysis pass.

**Audit-procedure lesson** (added to `docs/observations.md`): when probing
for hooks, all four scopes must be checked — shared project settings, **local
project settings**, user settings, and every enabled plugin's `hooks.json` —
plus an independent grep of `scripts/` for hook script files. The audit
subagent failure here is the same defect class as `audit-without-test`
(see `docs/reference/contributing/agent-postmortems.md` §1): a static probe
returned negative; a regression test (i.e., actually trying to run a blocked
command, or grepping the `scripts/` tree) would have shown the truth.

Followed up in commit (this revert) and tempdoc 520 reframe.
