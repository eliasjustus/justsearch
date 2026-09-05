---
title: "Replace bounded areas with maintained open source: whole-project analysis (product + agentic system + tooling) of where a polished, regularly-updated upstream can absorb bespoke code, ranked by maintainer effort saved"
type: tempdocs
status: "TWO PASSES COMPLETE (2026-09-05). Pass 1 (replace X with equivalent Y, §4-§12): engines already upstream; commodity retirements ≈6.5k lines + 166 MB data. Pass 2 (stop doing X + is equal function necessary, §13-§15): six evidence-of-delivered-value workers + orchestrator judgment; the counterfactual already ran (most gates/hooks/tests inert for ~68 of 72 days, one recorded drift); retirements ≈65-75k lines of code+tests, ~280k lines/quarter of data churn, and the tempdoc growth rate; precondition is ledger retention ≥60 d so 'unmeasured' rows become measurable. Size-based churn added (§3.1). Public-claim defects found (README:146 'regression-gated in CI' is false; release-number drift). §16: orchestrator re-verified every relayed claim (2 worker errors corrected, verdicts intact). §17 (founder challenge 'unused ≠ useless'): four deletions REVERSED on merit (lease model, index_identity, baseline_shift, corpus_fidelity), analytics and jseval rows narrowed, cost model corrected (effort tracks churn, not lines; actively-costing set ≈20-25k lines, the rest inert). Nothing implemented. Next: §15 rows 0-2, then the re-scoped governance and hook stops."
created: 2026-09-05
updated: 2026-09-05
lane: maintainer-effort / dependency strategy
model: fable (orchestration + judgment) / opus + sonnet (research workers)
author: agent (Fable orchestration), founder-directed 2026-09-05 ("analysing whether any bounded aspects/areas of this project [could be] replaced/integrated by more polished open source projects that are regularly updated, to decrease the amount of work needs to be done manually by this project's maintainers … the project as a whole, including the agentic system and everything else")
related:
  - 887-improvement-landscape-register   # breadth-first improvement register; this tempdoc is a cross-cutting axis 887 did not chart (build vs buy)
  - 886-agent-token-efficiency-review    # the founder's running lane on agent cost; overlaps §4 (analytics)
  - 920-codex-cli-dual-harness-migration # the dual-harness layer assessed in §8
  - 872-retire-observations-store        # precedent: deleting a bespoke authority nobody consumed
  - 742 (retire-with-a-sweep)            # every adoption here is also a retirement and needs the sweep
  - 743 (delegation economics)           # cost model for the research passes
  - 709 (dataset cache)                  # jseval data-hygiene baseline, §7
  - 622 (native Claude Code OTel capture) # analytics already half-way to upstream, §4
---

# 930 — Replace bounded areas with maintained open source

## Briefing for the agent picking this up

This is an **analysis** tempdoc: its deliverable is a ranked go/no-go table (§11), not code.
Read §1 (framing), §2 (method), §3 (baseline numbers) and then only the area sections you are
acting on. Each area section is a research worker's findings **judged** by the orchestrator;
worker claims that could not be re-verified are listed per section under *Unverified*. Treat
every "REPLACEABLE" verdict as a hypothesis until the adoption PR's sweep (742) proves nothing
downstream consumed the bespoke artefact. Nothing here is implemented.

## 1. Framing

The founder's question is whole-project: not "which library is better" but "where does a
maintained upstream let one maintainer write less". Two facts set the frame:

- **One maintainer.** 889 commits in the repository's whole public history (72 days from
  2026-06-25); 878 by one author, 11 by dependabot. Every bespoke line is that person's future
  time.
- **Cores are already delegated.** Lucene (index), llama.cpp server (generation), ONNX Runtime
  (encoders), Tika/PDFBox/POI (extraction), Tauri (shell), OpenTelemetry (telemetry),
  ir-measures/ranx/ir-datasets/Inspect AI (eval metrics). What remains bespoke is glue,
  infrastructure, and the agentic governance layer. That is where this analysis looks.

The trap the analysis must avoid: an upstream only pays when the seam shares a *reason to
change* with it (AHA). Otherwise the maintainer trades owning code for owning an adapter plus
tracking upstream breaking changes, and a desktop app pays for every new dependency in install
size and supply-chain exposure.

### Hard constraints that disqualify candidates outright

| Constraint | Source | Kills |
|---|---|---|
| Local-first, loopback-only, offline-capable | CLAUDE.md invariant 2, ADR-0046 | anything that runs a network service outside loopback or phones home |
| GPU-only inference, fails closed | branch-safety §worktree, 919 | libraries whose value is CPU fallback |
| Locale-invariant analysis | CLAUDE.md invariant 6 | search libs that ship per-language analyzers/stopwords |
| Head never touches Lucene | CLAUDE.md invariant 1 | any embedded-search lib that would run in the Head |
| Frontend is Lit web components | CLAUDE.md invariant 5 | React/Vue component libraries |
| Windows is the primary dev + ship platform | environment | tools without Windows support |
| Retire-with-a-sweep | CLAUDE.md `retire-with-a-sweep` | every adoption PR must also delete the retiree's fingerprints in the same PR |

## 2. Method

1. **Measure before choosing.** Per-directory commit frequency over 12 months (`git log
   --since=2025-09-05 --format= --name-only`) is the real maintenance cost; line counts are
   secondary. §3.
2. **Fan out by area, read-only.** Six research workers, each with a self-contained brief
   requiring `file:line` or git-command evidence per repo claim, and license / last release /
   6-month cadence / Windows+offline fit per upstream candidate. Workers classify each component
   REPLACEABLE / PARTIAL / KEEP (plus ENV-FIX where the code exists only to work around this
   machine).
3. **Judge, don't relay.** The orchestrator re-verifies load-bearing claims and records what
   stayed unverified. Findings are hypotheses (`audit-without-test`); the adoption PR is the
   test.
4. **Rank by (maintainer lines retired ÷ risk)**, where risk = adapter size + upstream churn +
   what the sweep might break.

## 3. Baseline: size and churn

**Correction (jseval worker, verified by orchestrator):** this repository's root commit is dated
**2026-06-25**, so "12 months" of history is really **72 days**, and under ADR-0045 every commit
is a squashed PR. The first draft of this table counted file-touch pairs (`commit × file`),
which overstates churn by 5–30× for wide-diff directories. The corrected column is whole-history
commit count per directory (`git log --oneline -- <dir> | wc -l`). Code lines are `wc -l` over
source files, excluding node_modules/build/data.

| Area | Code lines | PR-squashes (72 d) | Note |
|---|---|---|---|
| `docs/tempdocs` | 667 docs | **407** | working history; touched by ~46% of all PRs |
| Product: TS (`modules/ui-web/src`) | ~268k | 150 | |
| `scripts/jseval` (Python) | ~116k tracked (58.8k `jseval/`, 46.9k `tests/`) | 145 | see §7 for the tracked-bytes finding |
| `.claude/skills` (+ `.agents/skills` projection) | 28 + 31 files | 126 | dual-harness, §8 |
| `governance/*.v1.json` registers (53) | — | 106 | |
| Product: Java (`modules/app-services` alone; ~551k Java total) | — | 101 | |
| `scripts/ci` (87 `check-*` + tests) | ~35k | 93 | |
| `scripts/agent-analytics` incl. 30 hooks | ~37k (hooks ~8.7k) | 63 | |
| `scripts/dev` | ~18k | 36 | |
| `scripts/codegen` | ~3k | 19 | |
| `scripts/docs` | ~4k | 18 | |
| `scripts/sandbox` | ~14k | 17 | |
| `scripts/governance` (35 gate impls) + `gates/` | ~22k | 16 | gate *implementations* are quiet; the *registers* they read are not |

Reading: the two highest-churn surfaces are tempdocs and the register/skill layer, ahead of any
product module. jseval is churned as often as the whole frontend. Gate implementations are
stable; it is the registers and the docs that move. §10 argues from that shape.

### 3.1 Churn by diff size (founder correction 2026-09-05: "PR number isn't enough, size matters")

Lines added + deleted since the import commit (`git log --numstat <root>..HEAD`, root excluded
so the initial import does not dominate). Total: **1,126,533 lines changed in 72 days**
(982k added, 144k deleted).

| Area | Lines changed | Share | Of which |
|---|---|---|---|
| `scripts/jseval` | 361,293 | **32%** | code + tests ≈ 82k; **≈ 280k is checked-in data**: run dirs (`624-run-scale` 60.6k, `782-run-hero` 29.9k, `624-run-…-confirmatory` 16.5k, …) and corpora (`707-corpora` 48.4k, `781-corpora` 13.9k, `666-corpora` 12.6k) |
| `modules/ui-web` | 178,644 | 16% | 52k of it deletions, the only large-deletion product area |
| `docs/tempdocs` | 175,823 | **16%** | 174k added, 1.4k deleted: append-only by construction |
| Product Java (app-services 52.6k, ui 32.5k, worker-services 30.9k, app-agent 19.1k, adapters-lucene 11.8k, indexer-worker 8.5k, configuration 6.0k, app-inference 5.6k, worker-core 5.3k, app-api 5.2k, app-observability 5.2k) | ≈ 183k | 16% | |
| Tooling code (agent-analytics 36.8k, ci 24.8k, sandbox 21.2k, dev 14.9k, governance 8.8k, docs/codegen/models ≈ 5k) | ≈ 111k | 10% | |
| `.claude/skills` + `.agents/skills` | 24,298 | 2% | 13.2k of the skills churn is deletions (620's eviction) |
| `docs/ui-explorations`, `docs/observations.*`, `docs/reference` | ≈ 38k | 3% | observations: 10.9k written, retired by 872 |
| `governance/` registers | 5,390 | 0.5% | |

Reading by size: **a quarter of every line that moved in this repository was jseval data**, and
another sixth was tempdoc prose. Product code and tooling code together are about a quarter.
Any "reduce maintainer work" lever that does not touch data hygiene and working-history volume
is working on the smaller half. This strengthens §7.3 and §10 and does not change any §11 row.

Product-side dependency surface for reference: root `package.json` 11 deps, `ui-web` 41,
`gradle/libs.versions.toml` 178 lines.

## 4. Agent analytics (`scripts/agent-analytics/`, excluding hooks)

Worker: opus, read-only, 25 tool uses. Orchestrator verified the dead-script claim, the 745
status line, the telemetry directory size, and the stale retention comment on 2026-09-05.

### 4.1 Prior art governs

**This survey was already run.** Tempdoc 745 (2026-07-16) probed 7 slices against 8 tools live
and returned *adopt 0 of 7*; the founder recorded no standing OSS-first policy (745 V-1). §1's
"analytics-to-OTel is the strongest candidate" framing was wrong on the engine question and is
withdrawn. What 745 could not know: **its F-10 finding, that per-subagent role attribution is
structurally absent from the ecosystem, is now falsified by this machine's own capture.**
Verified from `tmp/agent-telemetry/otlp/`:

- `api_request` log events carry `model`, all four token axes, `cost_usd` (harness-computed),
  `duration_ms`, `query_source` (e.g. `agent:builtin:general-purpose`) and `agent.name`.
- `subagent_completed` events carry `agent_type`, `total_tokens`, `total_tool_uses`, `model`,
  `model_swapped`.
- 1,298 Claude Code spans (`claude_code.interaction` → `llm_request`/`tool`) with real
  `parent_span_id` and `agent_id`, `gen_ai.*` semconv.

That moves two rows, and only two.

### 4.2 Inventory and verdicts (27 scripts, 12,737 lines)

| Script(s) | Lines | Verdict | Reason |
|---|---|---|---|
| `test-pipeline.mjs` | 2,233 | **DELETE** | `docs/explanation/21-agent-analytics-pipeline.md:243`: "Not wired to anything … known stale failures". `run-all-tests.mjs:37` globs `*.test.mjs` only; 0 refs in `.github/` or `package.json` (orchestrator grep). |
| `cost-session.mjs` + pricing table `lib/transcript-cost.mjs` | 435 + 496 | PARTIAL → native `api_request.cost_usd` | Retires the hand-maintained pricing table and the "wrong price is silent" asymmetry (README lines 78-90). **Blocked on retention first**: `otlp-sink.py:240` keeps logs 2 days × 20 MB rotation; transcripts persist, logs do not. |
| `spawn-economics.mjs` | 399 | PARTIAL | role split + per-axis tokens now native; `firstUserMessageChars` stays bespoke |
| `otlp-viewer/index.html` | 93 | REPLACEABLE (otel-desktop-viewer, Windows zip) | 93 lines that work; do only on request |
| `baseline-economics`, `merge-links`, `record-merge`, `recover-merge-links`, `outcome-session` | ~2,230 | KEEP | 745 F-10 still holds for the cost→git-merge join; nothing upstream does it |
| `evaluate-session`, `mine-friction`, `aggregate-friction`, `friction-timeline`, `signature-census`, `overhead-taxonomy` | ~2,300 | KEEP | behavioural taxonomy slice is still empty in OSS (745 F-3b); nearest match `sniffly` stale since 2025-08 |
| `cache-efficiency`, `context-residency`, `context-attribution` | ~1,500 | KEEP | no native compaction event or context-window metric; `query_source:"compact"` is the only signal |
| `world-state`, `expected-state-probe`, `note-observation`, `run-all-tests`, `dev-tool-usage` | ~1,250 | KEEP | repo-specific; live invokers in skills, CI, `remove-worktree.cjs`, `preview-squash-message.mjs` |
| `analyze-session`, `analyze-trends`, `generate-index` | ~1,830 | KEEP | read hooks `events.ndjson`, which no backend ingests; a dashboard with no live consumer is the 745 V-2 trap |
| `otlp-sink.py` | 448 | KEEP | the receiver is the local-only boundary |

### 4.3 Backend candidates: re-affirm adopt-0

| Project | License | Cadence (6 mo) | Windows native | Verdict |
|---|---|---|---|---|
| Claude Code native OTel | first-party | live (2.1.259) | yes | **use as source** (4.1) |
| ccusage 20.0.x | MIT | 26 | unverified | magnitude oracle only (745 amendment) |
| claude-code-log 1.6 | MIT | 3 | node | ≈ `transcript-spine` (102 lines); no gain |
| claude-code-otel | MIT | 0, last commit 2025-06 | Docker only | abandoned |
| otel-desktop-viewer 0.5 | Apache-2.0 (unverified) | ≥1 | yes | replaces the 93-line viewer only |
| Grafana + Loki 3.7 (+Tempo) | AGPL-3.0 | many | yes | could host trends; cannot read hooks events |
| SigNoz / Langfuse / OpenObserve / Phoenix | MIT+EE / MIT+EE / AGPL / Elastic 2.0 | active | Docker or license-blocked | no (745 F-9 holds) |
| Jaeger v2 | Apache-2.0 (unverified) | 1 | yes | traces only; nothing to gain |

### 4.4 Recommendations from this area

1. **Delete `test-pipeline.mjs`** (−2,233, +0, zero risk).
2. **Fix log retention, then move cost/role attribution to native `api_request`** (~+80 in
   `lib/telemetry-io.mjs`, est. −650 across the pricing table and cost paths). Retention first,
   or a durable source is swapped for an ephemeral one.
3. **No backend adoption.** Two privacy facts bind: every OTLP record carries the user's
   account identity fields, and any backend widens that surface.

### 4.5 Routed out-of-scope findings (owner: tempdoc 745 / the sink)

- `tmp/agent-telemetry/otlp` is **17 GB** (orchestrator `du`), 16.7 GB of it traces, and
  ~98% of sampled spans are Codex rather than Claude Code. `otlp-sink.py:237-239` still says
  traces are "~4 GB/month, small enough to keep in full" with `RETENTION["traces"]=None`. The
  comment is stale by roughly 4× and the Codex volume was never in its model. Needs a trace
  retention policy, not this tempdoc.
- README `:269` cites a metrics footprint that is now ~228 MB; refresh when 745's sweep runs.

### 4.6 Unverified

Whether `api_request` events survive long enough for month-scale aggregation under any
retention setting (requires changing `RETENTION` and observing); whether Claude Code spans need
`CLAUDE_CODE_ENHANCED_TELEMETRY_BETA=1` (research says yes; the var is absent from user settings
yet spans are present); whether `subagent_completed.total_tokens` reconciles with
transcript-derived spawn totals (no cross-check run).

## 5. Governance gates and CI checks

Worker: opus, read-only, 28 tool uses. Orchestrator re-verified the four load-bearing claims
(orphaned legacy script refs, the 15-of-35 CI census, the empty todo baseline, the `ts-any`
regex) against `main` on 2026-09-05.

### 5.1 Gate classification (35 registered, `governance/registry.v1.json`)

Kernel: `run.mjs` + `lib/` 4,269 lines (1,253 tests); gate dirs 17,362 lines (3,872 tests);
`gates/` baselines + 61 changesets 2,368 lines.

| Class | Count | Gates |
|---|---|---|
| **COMMODITY** (upstream does the job) | 6 | `ts-any`, `todo-fixme`, `dead-code-jvm`, `npm-audit`, `style-literal-ratchet`, `test-to-code` |
| **PARTIAL** (wraps an upstream, adds baseline/changeset semantics) | 8 | `dead-code` (knip), `wire` (buf), `module-deps`, `test-efficacy` (PIT), `adr-coverage`, `contract-projection`, `ssot-catalog-sync`, `tempdoc-wiring`, `atom-fork-ratchet` |
| **SEMANTIC** (JustSearch invariant, no upstream) | 21 | every register gate: execution/operation/interaction/contribution-surface, host-owns-truth, consumer-drift/presence, hook-integrity, runtime-state/witness, observed-happening, stage-completeness, surface-altitude, prose-tier-register, register-guard-resolution, config-surface, modal/transient-arbitration, modality-contract, ambient-purity |

Of the 21.6k-line governance + ci corpus, roughly 8% is commodity; 92% encodes project invariants.

### 5.2 The ratchet mechanism itself: no upstream

The kernel's shape is "cross-language shrink-only baseline + structured per-item justification
(changeset) + the pin must advance in the same diff" (`lib/declared-growth-repin.mjs`, 159
lines). No tool surveyed combines all three:

| Tool | Baseline | Justification | Cadence (last 6 mo) | Note |
|---|---|---|---|---|
| ESLint bulk suppressions (`eslint-suppressions.json`, v9.24+) | per file+rule count, auto-prunes | none | 17 releases | auto-prune silently permits the drift `declared-growth-repin` catches |
| betterer | snapshot ratchet | none | **0, dormant since 2024-12** | do not adopt |
| knip 6.34 | **none** (ignore globs only) | none | 66 | why the `dead-code` wrapper exists |
| dependency-cruiser 18.2 | `--ignore-known` baseline | none | 12 | regenerates wholesale |
| ArchUnit `FreezingArchRule` 1.4.2 | ViolationStore, self-shrinking | none | unverified | **unused in the repo today** (0 grep hits in `modules/`) |
| PIT 1.30 / Stryker 10 | global threshold | none / partial | 15 / 2 | no per-seam floor |
| better-npm-audit `.nsprc` | per-ID `{active, notes, expiry}` | **yes** | 0, stale since 2024-09 | |
| cargo-deny 0.20 | `[[advisories.ignore]] {id, reason}` | **yes** | 12 | Rust only |
| semgrep `--baseline-commit` | git-native "new since ref" | none | 22 | SARIF native |

Verdict: the kernel stays. Swaps are per-gate, for gates whose baseline is empty or whose
semantics are a plain lint rule.

### 5.3 CI checks: 87 `check-*.mjs`, 13,751 lines (+29 tests, 4,969)

| Bucket | Count | Lines | Upstream |
|---|---|---|---|
| (i) regen-freshness | 9 | 659 | **collapsible**: 7 are 19–33-line `spawnSync(gen, ['--check'])` wrappers → one `regen-all --check` step |
| (ii) register/doc sync | 18 | 2,340 | none (project registers) |
| (iii) UI a11y/contrast/layout static oracles | 27 | 3,948 | none (axe-core is already the runtime oracle; these are token-level) |
| (iv) installer/packaging | 8 | 2,150 | none |
| (v) generic lint | 8 | 2,103 | only `check-slf4j-bare-message-logging` is a clean PMD-XPath swap; `check-workflow-triggers` checks *policy* (`workflow-signal-policy.v1.json`), not syntax, so actionlint does not replace it |
| residual domain invariants | 17 | 2,551 | none |

### 5.4 Concrete deletions (~1,750 lines out, ~65 in)

| # | Action | Delete | Add | Risk | Verified |
|---|---|---|---|---|---|
| 1 | Delete `scripts/ci/check-npm-audit-ratchet.mjs` + `test-check-npm-audit-ratchet.mjs`. The kernel gate replaced it (`gates/npm-audit/enforcer.mjs:4`); surviving refs are only `module-filter.yml:59,61`, the changeset README, and one kernel-doc mention | ~450 | 0 | none | orchestrator grep 2026-09-05 |
| 2 | `ts-any` → `@typescript-eslint/no-explicit-any: error` (currently `off`, `eslint.config.js:98`) + `eslint-suppressions.json`. Also fixes a live defect: `ANY_PATTERN` (`gates/ts-any/enforcer.mjs:32`, `:\s*any\b`) matches English "any" in prose; 5 false positives are pinned in `expected-state.v1.json` | ~180 | ~4 + generated | low | regex verified |
| 3 | `todo-fixme` → ESLint `no-warning-comments` + PMD rule. `gates/todo-fixme/baseline.txt` is 0 bytes, so a plain error rule is equivalent | ~273 | ~6 | low | verified 0 bytes |
| 4 | Delete `test-to-code` (LOC-ratio floor; no upstream, no documented incident, 2 commits/yr) | ~190 | 0 | low: grep `gates/test-to-code/baseline.txt` consumers beyond `scripts/`, `governance/`, `.github/`, `docs/reference/` first | worker grep only |
| 5 | `dead-code-jvm` → ArchUnit `FreezingArchRule` around `WholeProgramDeadCodeTest` | ~230 | ~15 | medium: loses the changeset layer over dead classes | |
| 6 | Collapse the 7 pure regen wrappers into one `--check` runner | ~250 | ~30 | low | |
| 7 | `scripts/docs/anchor-audit.mjs` → lychee (see §6) | ~196 | ~10 | low | |

**Do not swap**: `dead-code` (knip has no baseline; the `unit-renormalization` exclusion at
`enforcer.mjs:58-59` prevents a counting change from buying blanket suppression), `wire`,
`config-surface`, `adr-coverage`, `test-efficacy` (per-seam retraction is tied to
`logic-seams.v1.json`), and every register gate.

### 5.5 Adjacent finding, routed here because it dominates the savings above

`.github/workflows/ci.yml:305-307` says of 35 registered gates 15 run in CI and 20 are unwired.
**That comment is stale** (second-pass correction, 2026-09-05): PR #619 (`38c43254`, 2026-09-03,
"wire the hermetic kernel gates into CI") landed after it was written; `ci.yml` now names 27
distinct `--gate` ids plus 6 via the `ui-web-gates` recipe, so **33 of 35 are reachable today**.
The two still unwired are `ts-any` (red on `main` behind an expected-state pin, see §5.4 row 2)
and `test-efficacy` (PIT skip makes it vacuously green). The stronger point survives in §13.1: for
roughly 68 of the 72 public days most gates ran nowhere, so their delivered value is days old.

### 5.6 Unverified

- ArchUnit 6-month cadence; SARIF support for dependency-cruiser, lychee, cargo-deny, PIT,
  Stryker. No gate was executed; all behavioural claims come from source and `git log`.
- "Lines added" for the ESLint/ArchUnit adapters are estimates, not prototypes.

## 6. Codegen and docs tooling

Worker: sonnet, read-only, 25 tool uses.

### 6.1 Two corrections to §1's framing

- `contracts/wire/` is **protobuf via buf** (`contracts/registry.v1.json:5-8`), not JSON
  Schema, and already upstream-tooled. The JSON-Schema pipeline is a separate surface:
  victools emits draft 2020-12 from Java records into `SSOT/schemas/*.v1.json`
  (`agent-history-response.v1.json:2`), consumed only by `gen-wire-schema-types.mjs`.
- There is **no OpenAPI document** anywhere; `gen-api-client.mjs` reads a committed runtime
  route manifest from `GET /api/meta/routes`. openapi-generator is therefore not a swap but a
  new schema authority (a fork under the projection-vs-fork test). The §1 candidate is withdrawn.

### 6.2 Classification

| Script | Lines | 12-mo commits | Verdict | Upstream / reason |
|---|---|---|---|---|
| `gen-wire-schema-types.mjs` | 558 | **7** (highest churn here) | PARTIAL, best ROI | quicktype (Apache-2.0) takes standard JSON Schema and emits TS + Zod; parity on `$ref`→`$defs`, nullable tuples, typed `additionalProperties` needs a spike. json-schema-to-zod entered maintenance ~2026-03: caution on the class |
| `add-spdx-headers.mjs` | 76 | 1 | PARTIAL | FSFE `reuse` (GPL-3.0 tool, fine for a tool) covers stamp + `--check`; buys the full REUSE model |
| `dump-cargo-licenses.mjs` | 34 | 2 | PARTIAL, negligible | cargo-about / cargo-license |
| `gen-notices.mjs` | 397 | 3 | KEEP | already delegates extraction to jk1 gradle-license-report + license-checker; the aggregator (models + Tesseract + JVM + npm → NOTICE) has no analog |
| `gen-text-tokens.mjs` | 183 | 1 | KEEP | WCAG-contrast OKLCH derivation is an algorithm; style-dictionary would host it as a custom transform, same code |
| `gen-api-client`, `gen-field-constants`, `gen-liveness-constants`, `gen-stream-liveness-constants`, `gen-shape-handlers` | ~980 | 1–2 each | KEEP | named-register → {Java, TS} projections |
| `gen-agent-hooks-wiring`, `gen-codex-hooks` | 303 | 3 + 1 | KEEP while dual-harness exists | see §8 |
| `migrate-font-size-tokens.mjs` | 88 | 1 | one-shot, already run | delete as residue |
| `anchor-audit.mjs` + `verify-canonical-doc-links.mjs` | 404 | 1 + 1 | PARTIAL | lychee (MIT, Windows binary, `--offline`) for mechanical links/anchors; keep a ~60-line policy script for "no canonical → tempdoc links" and the alias autofix |
| `docs-validate.mjs` | 169 | 3 | KEEP | frontmatter/normative-section schema; markdownlint custom rules would be the same code |
| `llmstxt-generate.mjs` | 264 | 2 | KEEP | generic llms.txt generators crawl sites, not local frontmatter |
| `skills-sync`, `codex-skills-projection`, `agent-instructions-sync` | 404 | 4 + 1 + 1 | KEEP / shrink per §8 | |
| `tempdoc-staleness-*` | 393 | 3 | KEEP | |

Net near-term: ~76 + ~340 lines deletable (spdx, link audit) for ~60 lines of config; the
558-line schema generator is the real prize and needs the spike first.

### 6.3 Unverified

quicktype's Zod path on draft 2020-12 shapes; reuse's comment-style detection for `.java`/`.ts`;
lychee duplicate-heading-id detection.

## 7. jseval

Worker: opus, read-only, 42 tool uses. It also produced the §3 correction. Orchestrator
re-verified the root-commit date, per-directory commit counts, and the per-run-directory tracked
bytes on 2026-09-05.

### 7.1 Clusters (tests excluded: 46,941 lines, ~1:1 with the package)

| Cluster | Lines | Verdict | Seam / reason |
|---|---|---|---|
| agent-utility eval (`agent_*`, `utility_*`) | 14,186 | KEEP | already on Inspect AI; the rest is the versioned public-claim policy (`utility_claim_policy.py:1-10`), a governance artefact |
| run orchestration + artifacts (`manifest`, `history`, `compare_runs`, `bisection`, `cohort_baselines`, `comparability`, `baseline_shift`, `env_fingerprint`, …) | 12,163 | PARTIAL | seam: `history.py` (SQLite metric store) + `artifacts.py` (run-dir layout) + `env_fingerprint.py` → `mlflow-skinny` `file:` store. ≈1.5k lines for 21 deps and a documented Windows `file:///` URI footgun (mlflow#16094). Marginal. |
| corpus lifecycle (`corpus_*`, `entity_*`, `dataset_cache`) | 8,966 | KEEP | contamination certification (`corpus_certify.py`: corpus passes only if the model fails it closed-book), leak measurement, cross-interpreter determinism proof. BEIR/MTEB are contaminated by construction; `corpora.py` already loads BEIR via ir-datasets for the cases where that is fine. |
| UI harness (`ui_*`) | 5,851 | PARTIAL | `ui_diff.py` + `ui_proportion_gate.py` image compare → Playwright `toHaveScreenshot`; a11y already delegates to axe |
| scoring + judging | 4,870 | PARTIAL | per-query significance in `compare_runs.py` → `ranx` (already an optional dep); CE-coverage validity guard stays |
| gates + diff (`ratchet_kernel.py`) | 3,643 | KEEP | already the anti-fork consolidation of the Node kernel's *convention* |
| projections, backend driving, domain evals, bench, CLI | ~9,150 | KEEP | thin, product-shaped, low churn |

**What no tracker models** (and why MLflow/DVC/W&B cannot absorb the orchestration cluster):
cohort identity as a hash of *system* state with a ±2σ non-determinism envelope (`manifest.py`);
comparability as a *gate* derived from readiness + ANN proof + error rate (`comparability.py`);
axis-wise bisection over cached runs (`bisection.py`); changeset-justified baseline shifts.

### 7.2 Tracker candidates

| Candidate | License | Latest | 12-mo releases | Windows offline | Verdict |
|---|---|---|---|---|---|
| MLflow 3.16 (`mlflow-skinny`) | Apache-2.0 | 2026-09-04 | 33 | yes, `file:///c:/…` only | only defensible adoption, as a `history.py` backend |
| DVC 3.67 | Apache-2.0 | 2026-03-31 | 8 (was ~50/yr before the 2025-11 lakeFS acquisition) | yes | 42 core deps; wrong remedy for §7.3 |
| W&B 0.29 | MIT client | 2026-08-26 | 25 | client yes; server needs Docker + licence | no |
| Aim 3.29 | Apache-2.0 | 2025-05 | **0 stable** | **Linux/macOS only** | disqualified |
| Sacred 0.8.7 | MIT | 2024-11 | 0 | yes | dormant |

### 7.3 Data hygiene: the real finding in this area

`scripts/jseval` is **736 tracked files / ~215 MB, about 59% of the repository's ~364 MB of
tracked bytes**, while its Python is 5 MB (worker; orchestrator confirmed per-directory bytes
below). No LFS filter applies to any jseval path (`.gitattributes:25-29` covers model files only).

| Directory | Tracked bytes | Class |
|---|---|---|
| `782-run-2026-07-28-hero/` | 52.9 MB | run residue |
| `635-corpora/` | 52.2 MB | fixture, but 43.9 MB is one regenerable file |
| `624-run-2026-07-18-confirmatory/` | 33.9 MB | run residue |
| `624-run-2026-07-21-relaunch/` | 25.8 MB | run residue |
| `agent-utility-records/` + `public-agent-utility/` | 12.5 + 9.2 MB | deliberate sha256-chained publication (`.gitattributes:33-34`) |
| `707-corpora/`, `624-corpora/`, `781-corpora/` | 11.0 + 9.5 + 3.3 MB | fixtures (tests bind to the paths) |
| six other `624-run-*` | 3.5 MB total | run residue; `624-run-2026-07-17` and `-phase2` have zero references anywhere |

- **~116 MB of `*-run-*` campaign residue** referenced 0–5 times each, all in prose.
- **34 duplicated blob groups**: `707-corpora/` and `781-corpora/` carry byte-identical
  `fabricated-docs.jsonl` under 8 paths each; three of four 12.5 MB
  `utility-comparison-cross-corpus.v1.json` copies are the same blob.
- **The largest single file is regenerable**: `635-corpora/battlefield-en-scale-v1/docs.jsonl`
  (43.9 MB) is fully determined by `meta.json`'s `generation_provenance`, and
  `corpus_generate.regenerate_and_diff` (`corpus_generate.py:1326-1345`) proves byte-determinism.
  Committing it contradicts the recipe-not-content policy `corpus_fetch.py:4-11` states for
  fetched corpora.

The remedy is not DVC or LFS. The existing `dataset_cache.py` shared cache (709) plus the
recipe-not-content policy already *is* the right pattern; it was never applied to campaign
outputs or to the two largest generated corpora.

### 7.4 Recommendations from this area

1. **Delete `*-run-*` campaign residue from the working tree** (~116 MB) after a
   `docs/tempdocs` prose scan for citations (the worker's scan timed out). Keep the READMEs and
   ledgers; keep the sha256-chained publication dirs. History rewrite is a separate decision.
2. **Materialize the two `battlefield-*` corpora through `corpus_build.py` into gitignored
   `datasets/`** (~50 MB, zero new dependency).
3. **De-duplicate `781-corpora/` against `707-corpora/`** (16 identical blobs).
4. **Do not adopt a tracker.** If `history.py` ever needs a real store, `mlflow-skinny` is the
   only candidate that passes the constraints.

### 7.5 Unverified

Whether run-residue directories are cited from `docs/tempdocs` prose; `.git` is 5.3 GB and its
jseval share was not attributed; MLflow #16094 status on 3.16; W&B server licence terms;
resolved transitive install sizes.

## 8. Dev tooling, hook plumbing, dual-harness

Worker: sonnet, read-only, 27 tool uses. Orchestrator judgment inline.

### 8.1 `scripts/dev/` inventory (12-month commits in parentheses)

| File | Lines | Verdict | Why |
|---|---|---|---|
| `dev-runner.cjs` (12) + `lib/*` | 2,617 + ~3,500 | KEEP | Generic supervisors (process-compose, mprocs, overmind, tilt) cover "start N, restart on crash" only. The load-bearing part is the multi-agent ownership/lease contract (`dev-runner.cjs:156,223,2006-2163`, `lib/ownership-verdict.cjs:3`) and the two-tier readiness (HTTP-ready ≠ Worker-ready, `dev-runner.cjs:1157-1162`), which no supervisor models. |
| `justsearch-dev-mcp/*` | ~2,900 | KEEP | Already on `@modelcontextprotocol/sdk ^1.29` with `registerTool` + zod v4 schemas (`server.mjs:76-77`, `schemas.mjs:1,57`). Only bespoke layer is the self-freshness hash (`server.mjs:12-16`, 637 §H.1). |
| `prepare-worktree.cjs` (9), `remove-worktree.cjs` (8) | 114 / 471 | PARTIAL | Native `EnterWorktree` (`.claude/settings.json` `worktree.baseRef`) covers creation only. Dev-readiness seeding and Windows junction/long-path-safe teardown (`remove-worktree.cjs:4-11`) have no native or OSS equivalent. |
| `worktree-collision-preflight.cjs`, `serve-worktree-fe.cjs` | 253 / 382 | KEEP | Multi-agent-specific. |
| `run-gh.mjs` (2) | 245 | ENV-FIX | Exists only for the Scoop-shim quirk (`run-gh.mjs:4-8`, agent-lessons). The fix is PATH/session config, not more wrapper. |
| `run-py.mjs` | 56 | KEEP | Real upstream Python console-codepage bug (`run-py.mjs:4-12`). |
| `run-watcher.mjs` | 253 | RETIRE-ON-DATE | Self-labelled "ADAPTER-CLASS, 90-DAY ARTIFACT" stand-in for claude-code#75438 (`run-watcher.mjs:4-10`). Check the upstream issue; delete when fixed. |
| `HotSwapPush.java` + DevReloadManager | ~90 + ? | PARTIAL (spike) | HotswapAgent + DCEVM (JetBrains Runtime) does structural redefinition, which the bespoke path explicitly cannot. Cost is pinning the dev JDK to JBR plus a `-javaagent` flag: a toolchain decision, not a drop-in. |

### 8.2 Hook plumbing and dual-harness

- **`hook-base.mjs` (231 lines) and `dispatch.mjs` (256): KEEP.** OSS hook SDKs found (`cchooks`, `cchook`, `claude-hook-utils`) are single-harness, Claude-Code-stdin-only; they would replace only the JSON-parsing slice of `hook-base`, not the multi-hook serialization or the Codex adapter.
- **Dual-harness parity surface ≈ 9,300 lines**: `.agents/` 7,279, `codex-*` scripts + tests 1,404, `.codex/` 163, `AGENTS.md` 163, sync scripts 255. Tempdoc 920 marked the migration done 2026-09-03; this is its standing cost.
- **Skills projection: PARTIAL.** `.claude/skills` vs `.agents/skills` differ in every file: it is a deterministic projection (`scripts/docs/codex-skills-projection.mjs:2-9`), not a copy. The Agent Skills spec (agentskills.io, open-sourced 2025-12) makes SKILL.md bodies portable, but Codex's explicit-only invocation still lives in a separate `agents/openai.yaml`. The projection can shrink to "author once, generate only `openai.yaml`", not disappear.

### 8.3 Recommendations from this area

1. Shrink the skills projection to `openai.yaml`-only generation (deletes the SKILL.md-body regeneration path and its parity check).
2. Retire `run-gh.mjs` via environment fix; retire `run-watcher.mjs` when claude-code#75438 closes (check date).
3. Spike HotswapAgent/DCEVM as a `HotSwapPush.java` replacement; decide on toolchain cost.
4. No action on dev-runner, hook plumbing, or the MCP server.

### 8.4 Unverified

- OSS feature claims (HotswapAgent JDK coverage, process-compose health semantics) are search-snippet level; read primary docs before an adoption PR.
- The 9,300-line dual-harness figure is `wc -l` over generated + adapter files; it counts projected output as maintenance surface, which overstates hand-written cost.
- The worker could not find tempdoc 926 on `main`; it exists only on the `codex/926-hook-architecture-derisk` worktree branch, so 926's conclusions were not consulted here.

## 9. Product seams

Worker: sonnet, read-only, 36 tool uses. The product side is mostly "plugin plus glue working
correctly": the engine is upstream, the bespoke code is policy or process-ownership that the
constraints in §1 require.

| Seam | Bespoke component | Lines | Candidate | Constraint | Verdict |
|---|---|---|---|---|---|
| Extraction | `PolicyDrivenTikaExtractor`, `StructuredContentExtractor` (worker-services `extract/`) | 592 + 275 | Docling, unstructured | Docling/unstructured are Python with their own model runtime: a second inference surface, FAIL on single GPU-only boundary | KEEP (Tika/PDFBox/POI already the engines; wrapper is budget/MIME/provenance policy) |
| OCR | `PdfOcrEngine` + `TikaOcrRuntime` | 613 | OCRmyPDF, PaddleOCR | Built so the app owns every child process (`PdfOcrEngine.java:34`: "Tika never spawns tesseract") after an orphan-leak bug | KEEP by necessity |
| Chunking | `ChunkSplitter` (indexing `chunking/`) | 865 | LangChain4j `DocumentSplitters` | Only DEFAULT token-window mode is commodity; CSV/JSON/STRUCTURED modes are domain-specific | PARTIAL (small win, 4 of 6 modes stay) |
| VDU | `VduProcessor`, `VduBatchProcessor`, `VduAbstentionGate` | ~2,000 | none | own design | KEEP |
| Fusion | `HybridFusionUtils` | 1,043 | OpenSearch neural-sparse | FAIL: server-based engine, violates Head-never-touches-Lucene | KEEP (RRF is tiny; the bulk is adaptive weighting) |
| Vector field | kNN | — | Lucene `KnnFloatVectorField` | already used (`FieldMapper.java:437`) | not a gap |
| Reranker | `CrossEncoderReranker` | 474 | DJL ONNX engine | DJL wraps tensor plumbing only; fail-closed session lifecycle (919) is the point | PARTIAL, low value |
| Inference | `LlamaServerOps` 1,522, `InferenceLifecycleManager` 1,365, `NativeSessionHandle` 811, `gpu-bridge` 1,202 | ~8.8k + 5k + 1.2k | java-llama.cpp (in-process JNI), DJL | In-process bindings reintroduce the single-crash-domain that the separate `llama-server.exe` avoids | KEEP |
| Wire contract | `contracts/wire` | — | buf / protobuf | already used (`buf.yaml`, `buf.gen.yaml`) | not a gap |
| HTTP JSON contract | victools JSON Schema from Java records → `gen-wire-schema-types.mjs` (558) emitting TS type + Zod | 558 | openapi-generator, json-schema-to-zod | OpenAPI would be a second schema authority (fork, not projection); json-schema-to-zod reportedly discontinued ~2026-03 (unverified) | KEEP (see §6 for the codegen view) |
| **Configuration** | `ResolvedConfigBuilder` (ordinal precedence sysprop 500 > env 400 > YAML 200 > default 100) | 1,845 | smallrye-config, Typesafe Config | PASS; both do ordinal source merging natively | **PARTIAL, strongest product-side candidate**: absorb the merge mechanics, keep `EnvRegistry` typed accessors (1,503) and the install-plan domain model |
| Frontend chrome | `ConfirmDialog` 353, `ContextMenu`, toast host, chips | ~1,500 generic | Web Awesome (Shoelace successor, MIT, Lit-native) | PASS on Lit; every swap re-measures `ui-a11y-baseline.v1.json` | PARTIAL: pilot dialog/dropdown/toast only; data views over tanstack/virtua stay |
| Updater | `updater.rs` on top of `tauri-plugin-updater =2.10.1` | 2,288 | official updater plugin | already used (`Cargo.toml:33`, `updater.rs:13`); bespoke part is Ed25519 + store-compat gating + backend restart coordination | KEEP |
| Observability | `NdjsonSpanExporter` 363, `NdjsonMetricExporter` 471 on OTel SDK + RRD4J | ~830 | OTel Collector | FAIL: collector is an external process; offline exporters are the glue | KEEP |

### 9.1 Recommendations from this area

1. **Spike smallrye-config or Typesafe Config for `ResolvedConfigBuilder`.** Highest product-side ROI; the enum accessor surface stays as a thin wrapper.
2. **Pilot Web Awesome** for dialog, dropdown, and toast chrome, with the a11y baseline re-measured in the same PR.
3. `ChunkSplitter` DEFAULT mode to LangChain4j is real but small; do it only if LangChain4j enters the graph for another reason.

### 9.2 Unverified

- Lion (ing-bank) maintenance status; java-llama.cpp license/cadence; json-schema-to-zod discontinuation.
- Line counts are the worker's `wc -l`; not re-run by the orchestrator.
- The "degraded scan" design doc for VDU was not located by the worker (624 is the agent-utility eval); the KEEP verdict rests on the brief's framing, not on reading that design.

## 10. What no upstream fixes

The founder's question was "less manual work for the maintainer". Six research passes over the
whole project return the same shape: **the engines are already upstream; the bespoke code is
policy, and policy has no vendor.** Summed across §4–§9, the confident commodity swaps and dead
deletions total roughly **6,500 lines out for ~250 in**, plus ~166 MB of tracked data. That is
real, but it is under 3% of the tooling corpus and touches none of the churn leaders in §3.

Where the maintainer's time actually goes, per §3, is:

1. **Tempdocs**: 407 of 889 PRs touch `docs/tempdocs`. Working history is being written at a
   rate no upstream tool changes; only a decision about how much history a solo project needs
   changes it.
2. **Registers and skills**: 53 `governance/*.v1.json` registers (106 PRs) and the skills tree
   (126 PRs) move far more than the 35 gate implementations that read them (16 PRs). The cost is
   not the gate code, it is the number of *authorities* that must stay mutually consistent: 53
   registers, 35 gates, 87 checks, 30 hooks, 2 harness projections. Bucket (ii) in §5.3, 18
   checks and 2,340 lines, exists purely to keep authorities in sync with each other.
3. **jseval**: 145 PRs in 72 days on a toolkit whose commodity parts are already delegated.
   The churn is in agent-utility eval and corpus lifecycle, the two clusters that *are* the
   product's evidence, not infrastructure.

The lever for (2) is **consolidation, not substitution**: fewer authorities, each with one
consumer-driven reason to exist, and the sync checks between them retired with the authorities
(the 872 precedent: 565 observations, none read, deleted). Tempdoc 887's register, 924's
doc-impact governance, and 913's gate-wiring open items are the places that decision belongs;
this tempdoc only supplies the measurement. And §5.5 stands as the sharpest single fact: 20 of
35 gates do not run in CI. Wiring what exists beats replacing what works.

## 11. Ranked go/no-go table

Rank = confident lines (or bytes) retired ÷ risk. "Spike" means a bounded prototype before any
deletion. Every GO is still one PR with a 742 sweep.

| # | Item | Area | Retire | Add | Risk | Verdict |
|---|---|---|---|---|---|---|
| 1 | Delete `scripts/agent-analytics/test-pipeline.mjs` (documented dead, 0 invokers) | §4 | 2,233 | 0 | none | **GO** |
| 2 | Delete `scripts/ci/check-npm-audit-ratchet.mjs` + test (kernel gate replaced it; refs only in `module-filter.yml`) | §5 | ~450 | 0 | none | **GO** |
| 3 | Delete jseval `*-run-*` residue after a tempdoc-prose citation scan; keep READMEs/ledgers and the sha256 publication dirs | §7 | ~116 MB | 0 | low | **GO** (working tree only) |
| 4 | Materialize `battlefield-*` corpora via `corpus_build.py` into gitignored `datasets/`; de-dupe `781-corpora/` vs `707-corpora/` | §7 | ~50 MB + 16 blobs | 0 | low | **GO** |
| 5 | `ts-any` gate → `@typescript-eslint/no-explicit-any` + `eslint-suppressions.json` (also fixes the prose-"any" false positive) | §5 | ~180 | ~4 | low | **GO** |
| 6 | `todo-fixme` gate → ESLint `no-warning-comments` + PMD rule (baseline is 0 bytes) | §5 | ~273 | ~6 | low | **GO** |
| 7 | Delete `test-to-code` gate (no upstream, no incident) after a consumer grep beyond `scripts/`, `governance/`, `.github/`, `docs/reference/` | §5 | ~190 | 0 | low | **GO** |
| 8 | Collapse the 7 pure regen-freshness wrappers into one `--check` runner | §5 | ~250 | ~30 | low | **GO** |
| 9 | Fix OTLP log retention, then move cost/role attribution to native `api_request.cost_usd` / `query_source`; retire the pricing table | §4 | ~650 | ~80 | medium (sequencing) | **GO, retention first** |
| 10 | `anchor-audit.mjs` + `verify-canonical-doc-links.mjs` → lychee + a ~60-line policy script | §6 | ~340 | ~60 | low | **GO** |
| 11 | Shrink the Codex skills projection to `agents/openai.yaml`-only generation | §8 | part of 1,404 | — | low | **GO** |
| 12 | Retire `run-gh.mjs` via environment fix; retire `run-watcher.mjs` when claude-code#75438 closes | §8 | ~500 | 0 | low | **GO, dated** |
| 13 | `dead-code-jvm` → ArchUnit `FreezingArchRule` | §5 | ~230 | ~15 | medium (loses changeset layer) | spike |
| 14 | `gen-wire-schema-types.mjs` → quicktype (JSON Schema 2020-12 → TS + Zod) | §6 | 558 | config | medium (parity unverified) | spike |
| 15 | `ResolvedConfigBuilder` precedence merge → smallrye-config or Typesafe Config | §9 | up to 1,845 | wrapper | medium | spike |
| 16 | Web Awesome for dialog/dropdown/toast chrome, a11y baseline re-measured in-PR | §9 | ~1,500 | dep | medium | spike |
| 17 | `add-spdx-headers.mjs` → `reuse` | §6 | 76 | ~25 | low | spike (comment-style coverage) |
| 18 | `HotSwapPush.java` → HotswapAgent/DCEVM (JetBrains Runtime) | §8 | ~90 + limitation | toolchain pin | medium | spike |
| 19 | `history.py` → `mlflow-skinny` file store | §7 | ~1.5k | 21 deps | medium | NO unless a store is needed |
| 20 | Any observability backend (Grafana, SigNoz, Langfuse, …) | §4 | 93 | service | privacy + 745 | **NO** (re-affirm 745) |
| 21 | Generic process supervisor for dev-runner; OSS hook SDK for `hook-base`/`dispatch` | §8 | — | — | — | **NO** |
| 22 | The discipline-gate kernel, `dead-code` (knip wrapper), `wire` (buf wrapper), `test-efficacy`, every register gate | §5 | — | — | — | **NO** (no upstream has baseline + justification + same-diff repin) |
| 23 | jseval corpus lifecycle, agent-utility eval, ratchet kernel | §7 | — | — | — | **NO** (they are the product's evidence) |
| 24 | Extraction wrappers, OCR process ownership, VDU, fusion, reranker lifecycle, llama-server supervision, updater glue, NDJSON exporters | §9 | — | — | — | **NO** (bespoke by constraint) |
| 25 | openapi-generator for the HTTP contract | §6/§9 | — | — | fork | **NO** (no OpenAPI exists; would be a second authority) |

## 12. Open items / next steps

1. **Bundle rows 1, 2, 5, 6, 7, 8 into one "commodity-gate retirement" PR** with a 742
   sweep (kernel doc, tier-register rows, `module-filter.yml`, `expected-state` pins). Run the
   full kernel, not the subset.
2. **Bundle rows 3 and 4 into one jseval-hygiene PR**, preceded by the tempdoc-prose citation
   scan the worker could not finish.
3. **Row 9 is two PRs**: retention policy first (also covers the 17 GB trace directory routed in
   §4.5), then the cost-source migration with a reconciliation test against transcript totals.
4. Rows 13–18 each get a spike tempdoc or a section here before any deletion.
5. **Routed, not owned here**: 20 unwired gates (913); trace retention and the stale
   `otlp-sink.py` comment (745's sweep); `docs/observations.d` present untracked in the main
   checkout despite 872 (owner: whoever's WIP it is; not touched).
6. The §10 consolidation question (fewer authorities) is a founder decision. This tempdoc
   supplies the numbers; 887/924 are where the decision should be recorded.

### Unverified across the whole analysis

Per-section lists stand. Cross-cutting: no OSS candidate was installed or run; all cadence and
licence facts are from web search on 2026-09-05 and need a primary-source read at adoption
time; all "lines added" are estimates.

---

# Second pass (2026-09-05): "stop doing X", and is equal function necessary?

Founder direction after the first pass: *"run the second pass with the 'stop doing X' frame and
also run a pass yourself on the question whether equal function is not necessary for any given
area/aspect."* The first pass asked "replace X with an equivalent Y" and so could only return
swaps at feature parity. This pass asks two different questions per subsystem:

1. **Stop:** delete X outright, accept the nearest commodity as-is with *less* function. What is
   lost, measured by value X actually delivered (incidents caught, decisions fed, usage counts),
   not by X's own rationale comments?
2. **Necessity (orchestrator's own judgment, §14):** is equal function needed at all, given who
   consumes it, whether it has ever been exercised, and how reversible the loss is?

Six read-only workers gathered the evidence for (1); §13 records it with the orchestrator's
re-verification notes. §14 is the orchestrator's judgment. §15 re-ranks.

## 13. "Stop doing X": evidence of delivered value per subsystem

### 13.1 Build-time governance (kernel, 35 gates, 53 registers, 111 check scripts)

Worker: opus, 33 tool uses. Orchestrator verified the CI wiring count (27 `--gate` ids + 6 via
recipe = 33/35, correcting the stale `ci.yml` comment used in §5.5) and the postmortems file.
Cost to keep: **≈ 51,900 lines (5.5% of the repo's ~950k source lines)**: kernel 4,269, gate
impls 17,362, check scripts 18,074, registers 7,913, baselines + changesets 2,368, shipped
dashboard 869. PRs: 16 on the kernel, 93 on checks, 106 on registers.

**Value delivered, measured:**

| Evidence line | Finding |
|---|---|
| Changesets (the "human justified growth" artefact) | 47 substantive across 76 files. **16 of 35 gates have zero.** `prose-tier-register` 18 and `config-surface` 15 dominate. |
| Ceremony vs real | Worker read 9 in full: **≈ 29 of 47 (62%) are the gates' own arithmetic**: 13 bookkeeping registrations of CLAUDE.md prose rules, 5 literally titled `advance-baseline-to-NNN` for growth the branch did not cause (`885-advance-baseline-to-246.md`: "This PR adds no configuration key"), 9 merge-imports of other branches' counts, 1 fixing the gate's own false positive (`910-whole-file-normalization.md`: 23 of 186 rows one import from a false red), 1 *lowering* a floor to stop a treadmill. **Real human-justified surface growth ≈ 8.** |
| Documented catches in `agent-postmortems.md` (25 cases) | **One**: §13, `execution-surface` caught an extracted file that had become an unregistered `SearchTrace` referencer. |
| Catches in commit bodies | **One product defect**: #539 (`6e0ee868`), `check-controls-a11y` caught a `title` on a `[disabled]` button (reason unfocusable). A check script, not a kernel gate. Every other "gate red" match is a gate red on governance's own PR or a pre-existing red being pinned. |
| Built-after with no later catch | `discipline-gate-kernel.md:23` (class-size escape hatch), `:158` (`dead-code` "silently inert for its entire life"), `:255` (`ssot-catalog-sync` mechanizes a pitfall, no incident), `:262` (`consumer-drift` generalizes a one-shot audit). |
| Did they run? | Until #604/#617/#619 (2026-09-02/03), 12–15 of 35 ran anywhere; `operation-surface` "ran NOWHERE… main red from that merge onward" (`ci.yml:290-296`); `wire` ran in no workflow and **fails open** without buf, needing a bespoke `node -e` grep (`ci.yml:326`) to detect its own vacuous pass. |
| CI failures since wiring | 200 runs since 09-02: 146 pass / 36 fail / 18 cancelled. Of 8 sampled failures, 6 failed at a governance step; **5 of those 6 were governance's own PRs**. One real feature PR blocked (run `33754307323`, the 920 Codex harness, by registry-snapshot gates). |
| Register consumption | **31 of 53 registers have zero reference from `modules/`.** The 22 that do are cited in javadoc prose; only 8 Java *test* files parse one. No production code path reads any register. |
| Output consumers | SARIF is written to `tmp/` and read by two inline greps checking for vacuous-pass markers; not uploaded to code scanning. `--explain` has no scripted caller. The `/api/governance/state` view is labelled "DEEPLINK dev/operator tool, off-rail" (`CorePlugin.ts:74`). **No consumer outside the maintainer's agents and CI.** |
| Sync-only checks | **24 of 111** check scripts exist solely to keep two authorities consistent: generated↔generator 8, register↔code 6, register↔doc or doc↔doc 10. |

**Lost under stop, with cheaper carriers where one exists:**

| Invariant (incident-backed) | Cheaper carrier |
|---|---|
| root SSOT catalog ≡ classpath copy (`ssot-catalog-sync`; fields dropped in packaged builds) | a ~20-line JVM unit test doing file equality. Clearest genuine loss and cheapest to re-carry. |
| only registered classes reference `SearchTrace` / project `IndexingJobView` (`execution-`/`operation-surface`; 553 drift, #614 red) | ArchUnit `FreezingArchRule` with an allowlist; exactly this shape |
| every hook file is manifested (`hook-integrity`; #559 never-wired reaper) | a node unit test: every file in `hooks/` appears in settings |
| substrate symbols keep ≥N consumers (`consumer-drift`; 527 audit) | knip + ArchUnit; **no changeset was ever filed against this gate** |
| protobuf breaking (`wire`) | `buf breaking` directly in CI. The wrapper is a net negative (fails open). |
| dead-code, npm-audit, ts-any, module-deps, mutation floors | knip, dependabot, `@typescript-eslint` + bulk suppressions, ArchUnit, PIT threshold. Only gate-internal defects are on record for these. |

Not carried by the commodity list: `adr-coverage` (ADR premise probes) and `prose-tier-register`
(tags each CLAUDE.md rule with its tier). Both are documentation hygiene, not code invariants.
**20 of 35 gates** have no documented incident, 0–1 changesets, and no recorded catch.

The ratchet-with-justification protocol itself: origin incident real (`kernel.md:23`), the
same-diff re-pin rule has no commodity equivalent, **and** its own failure mode is documented
three times (#517→854, #595→885, #614→#613/#615) and it is what generates the 62% ceremony.

**Worker's judgment:** three concrete outcome changes attributable to the layer in 72 days
against ~52k lines; demonstrated value on the order of 5–10% of the layer. Caveats: 42 of 53
registers predate the public import, so earlier catches are invisible; a gate that quietly kept
every PR compliant leaves no artefact by design; the CI sample is 8 failures inside the wiring
window.

### 13.2 Hook layer and session analytics

Worker: opus, 54 tool uses. Orchestrator verified the two mechanism claims behind the
false-positive finding: `bash-guard.mjs:32` matches force-push with
`/\bgit\s+push\b[^"']*(?:--force\b|-f\b)/`, whose `[^"']*` spans across `&&` into a later
`gh workflow run -f`; and `isMainWorktree()` (`:115-117`) tests the hook's `process.cwd()`, not
a `cd` inside the compound. Both user and project settings have **no `permissions.deny`** and
the user runs `defaultMode: bypassPermissions`.

**Structural finding:** the hook layer emits no telemetry about itself. `events.ndjson` records
tool events, not "I blocked" or "I hinted" (`dispatch.mjs:67`). Every count below was
reconstructed by grepping 30 days of transcripts (2026-08-06 → 09-05, 1,174 files) for each
hook's literal output string. 42 hooks, zero instrumentation of their own effect.

| Hook | Blocks / fires (30 d) | Evidence of help |
|---|---|---|
| `bash-guard` sleep rule | **117** | **negative**: of 20 recoverable commands the top one is the verbatim condition-poll form the rule permits; `agent-lessons.md` already concedes "the guard is correct, it just costs a turn" |
| `bash-guard` git rules (checkout 19, force-push 4, reset 3, switch 1) | 27 | **0 true positives; 11 of 11 recoverable were false**: `reset --hard`/`checkout --detach` inside worktrees after a `cd` in the compound; `git checkout --ours <file>` during conflict resolution; substring hits on `git config` and an `echo`; three "force push" blocks on `git push -u … && gh workflow run -f sign=true` |
| `repeat-guard` / `build-counter` | 60 / 13 | plausible: loops that are self-evidently wasteful |
| `subagent-model-guard` | 55 | plausible: the only enforcement point for the model policy |
| `intervene` | 4 | unknown |
| `maintain-doc-hint`, `taskcreate-guard` | **0** | never fired |
| `known-state-hint` | **988 fires** in 60 sessions | negative: injected ~400 tokens of three unrelated pins onto a `grep` of `ci.yml` during this very investigation |
| `docs-regen-hint`, `tempdoc-age-hint`, `consult-doc-hint`, `ui-shot-hint` | 162 / 154 / 20 / 13 | unknown |
| `docs-granularity-hint` | 12 of 120 sessions | **the only measured hint study (739 §2), and it was negative**: misfired on non-pushes, diffed the wrong repo, lost to a competing instruction |
| ~24 remaining advisory hooks | not separable from CLAUDE.md text | unknown |

Zero destructive commands in the main checkout were intercepted in 30 days. The layer's largest
directly measurable effect is **~130 wasted turns per month from false-positive blocks.**
743 R4 §4 itself concedes the "~100% mechanical adherence" figure is "a category difference, not
an adherence rate".

**Native `permissions.deny` coverage:** force-push maps cleanly to `Bash(git push --force*)` /
`Bash(git push -f*)` and would not have false-positived on `-f sign=true`. Destructive-git rules
cannot be scoped to "main worktree only" natively, so a deny rule is *stricter* than today and
would block the legitimate worktree use that was 100% of observed traffic. Counters and all 34
hints have no native equivalent. (Unverified: whether deny is enforced under
`bypassPermissions`.)

**Analytics → decision:**

| Instrument | Fed a recorded decision? |
|---|---|
| friction mining (`mine-friction`, `aggregate-`, `friction-timeline`) | **yes**: 727 (7 fixes merged, PR #180), 739 §2, 745 slice 7 |
| cost→merge join (`baseline-economics`, `transcript-cost`, `record-merge`) | **yes**: 908 §4.4 delegate-by-default falsifier (rework 15.4%→40.8%, z=4.63); 745 F-6 |
| `expected-state-probe` | yes, as a CI gate (`ci.yml:181`) |
| `cost-session`, `analyze-session`, `analyze-trends`, `context-attribution`, `generate-index`, `evaluate-session`, `outcome-session` + 3 retired | **0 invokers each** (745 F-1, 2026-07-16); stores unmoved since: `scores.ndjson` 26 rows from 2026-07-12, `outcomes.ndjson` never produced |
| `signature-census`, `overhead-taxonomy`, `spawn-economics`, `dev-tool-usage`, `context-residency`, `world-state` | no decision found |

**Expected-state pins:** 19 live (27 ever added, 8 removed); all 19 share `reviewBy 2026-09-30`
(uniform, not per-defect); 3 of 19 name a fix owner; no record any exit probe was ever executed
(the gate checks presence, not execution). **Four pins currently carry real reds on `main`**
(`docs-validate` 6,751 findings; `ts-any` enforcer bug; `runtime-manifest-closure`; `wire` gate
inspecting no protos), 11 of 19 added in the last four days. The file says "A PIN IS A DATED
EXCEPTION, NOT A STEADY STATE"; as implemented, it makes red-on-main cheaper to live with than
to fix.

**Cost to keep:** 36,166 lines (41% tests), 73 of 889 commits (8.2%), **27 tempdocs =
17,305 lines** (3.7% of all tempdoc prose), plus always-loaded prose in `hooks-reference.md`,
CLAUDE.md, and `agent-lessons.md`.

**Worker's judgment:** demonstrated value is `subagent-model-guard`, `repeat-guard`,
`build-counter`, friction mining, and the cost→merge join. The headline safety claim
(`bash-guard` git rules) has zero true positives and eleven false; the hint tier has one study
and it was negative; ten of sixteen analytics CLIs have had no invoker since July.

### 13.3 jseval evidence pipeline

Worker: opus, 38 tool uses. Orchestrator corrections: the worker reported run directories as
untracked; **they are tracked** (`git ls-files` returns 34, 16, 76, 25 files for the four
largest), so §7.3 stands. The worker's finding that `README.md:146` claims retrieval floors are
"regression-gated in CI" while no workflow, `scripts/ci/`, or `governance/` file invokes
`relevance-gate`, `leak-gate`, `perf-gate`, or `ratchet_kernel` **is verified** (orchestrator
grep, 0 hits). That is a public enforcement claim with no enforcement behind it.

**Public claims and what produced them:**

| Claim | Defensible from BEIR + ranx alone? |
|---|---|
| README nDCG@10 table (scifact, enron-qa, legal-clerc, miracl-de/fr) | **Mostly yes.** Three are `ir_datasets` corpora; the cohort/comparability fields are provenance, not the number. |
| "in the range of published retrievers" | Yes; cited literature, nothing measured. |
| "floors regression-gated in CI" | **Unsupported** either way (above). |
| `leak_rate` ceilings, `leg_union_recall` | No commodity equivalent, but note `leak_rate` here is recall-cascade leak (636), not contamination; readers will conflate. |
| Agent-utility publication `agent-utility-hero-2026-07-28`: a defended **null** ("did not establish an improvement across every required stratum") | Inspect AI gives paired A/B and CIs; it does not give the fail-closed verdict, the certification chain, or the closed-book licence. |

What contamination certification buys: **nothing for the README retrieval table** (public,
contaminated-by-construction corpora with no cleanliness claim). It buys exactly one thing, the
licence to attribute the agent-utility result to retrieval rather than memorisation
(`corpus_certify.py:1-19`: 0% closed-book on fabricated vs 38% on public). And the sharpest
fact: **~13,100 lines and 40 commits of agent-utility machinery have produced one publication,
and its content is a null.**

**Decisions where the bespoke machinery was load-bearing: 7 of 12** (803 §Decision, 776 §D.1,
781 §F.5, 916 §J.4, 673 §F1/F2, 748 §G.3, 916 §G.5). All seven are one of two mechanisms:
*validity refusal* (comparability, `ce_coverage`, certification digests) or *instrument-power
reasoning*. The other five (712, 713, 762, 789, 916 §G.4) were plain A/B diffs or replication
that ranx or Inspect AI handles.

**False positives actually prevented: seven, confirmed with citations** (635:1006 comparability
refused an arm with 20% error rate; 916:775-780 `ce_coverage` caught 13 silent reranker deadline
skips and the reported sign *reversed* on re-measure; 718:259-268 `chunk_completeness` caught a
build whose vector nDCG had halved while the count oracle said healthy; 624:1089 closed-book
filter found 36% contaminated queries running with the opposite sign; 719:1648 claim policy
refused an "accepted" publication; 776:81-84 four corpora gold-enumerable by id shape;
782 incident ledger, cohort key caught a cross-stratum record from three harness identities,
~$130 sunk). Two of these (916, 718) are defects **in the system under test** that BEIR + ranx
cannot detect in principle: they compare numbers and have no view into whether the pipeline ran
as claimed.

**Gates with zero demonstrated catch and zero artefacts:** `bisection` (451 lines, 1 commit,
193 lines of how-to), `cohort_baselines`/`drift_calibration`/`baseline_shift` (509 lines, 4
commits, ~450 lines of how-to), `compare_runs`, `index_identity`, `corpus_fidelity`
(self-labelled detection-only). `find` across every run directory returns no bisect, envelope,
or compare-runs output. The ±2σ envelope the README's reproduction note invokes has no
calibration artefact behind it.

**Consumers:** CI runs one pytest file and three publication guards. **143 test files, 46,941
lines, run in CI nowhere** (`ci.yml:92` says so). No consumer outside the maintainer's agents.
Ancillary surface: 73 of 667 tempdocs (11%) are eval-titled; 820 lines of canonical how-to
document the zero-artefact cluster.

**Scoping correction:** the named bespoke gates total ~5,500 lines, **9.4% of the package**. The
other ~53k is runner, backend lifecycle, corpus build, ingest, which `ir_datasets` does not
replace: it delivers corpora, it does not drive a JustSearch backend.

**Worker's judgment:** `ce_coverage` (370 lines) is the highest value-per-line artefact in the
repository; `comparability` (37 lines) is trivially cheap and fired twice; certification and
claim policy each have a documented catch. Bisection, cohort envelopes, drift calibration, and
compare-runs are stated value only: written once, documented at length, never revisited, never
produced output. The 46,941-line test suite is the largest single cost and 99.3% of it is
unexecuted by any automation.

**Routed (public-doc drift, not owned here):** `methodology.md` generated block and
`scorecard.md` carry superseded release numbers (715 vs 832 rebaseline); `README.md:153` cites
"the canonical 2026-07-16 release" two paragraphs after `:128` says 2026-08-14. The anti-drift
design prevents hand-transcription, not regeneration lag.

### 13.5 Dev-stack tooling (dev-runner lease model, dev MCP server, hot-reload, worktree scripts, spawn reaper)

Worker: sonnet, 26 tool uses. Cost to keep ≈ 14.5k lines, 38 PRs, 10 tempdocs (254, 283, 305,
416, 542, 545, 606, 644, 670, 844).

| Component | Usage found | Prevented incidents | Built-after incidents |
|---|---|---|---|
| Ownership/lease model (6,257 lines) | 201 recorded runs; 20 interference events (2026-07-02 → 08-19), **~13 of 20 are a session reclaiming its own stale run**, i.e. restart hygiene any supervisor does | 0 documented averted-corruption cases; every event logs `portsClosed:true` with no downstream failure | 606's own audit: enforcement gates only `start`; `ingest`, `api_call`, `reload`, `ai_activate`, MCP `stop` are unenforced regardless of holder |
| Dev MCP server (4,242 lines, 31 commits) | 7 real tool calls in one 3.6 h window (2026-07-18); **0 in the 16 h window of 2026-09-04/05** | n/a | 844: the surface reporting state it had not verified (self-correction) |
| Hot-reload (`DevReloadManager` 283 Java lines + JDWP wiring) | **0 `reload` invocations in any sampled window** | — | `HotSwapPush` is a comment-level design name; no such class exists on `main` |
| `prepare-`/`remove-worktree.cjs` (585 lines) | 29 worktrees live, 87 historical `worktree-*` branches (lower bound) | no ledger of a blocked corruption | 618 §2 junction incident |
| Spawn reaper | live registry of 2; no cumulative reap history | not measurable | 861 |

**Data caveat that also applies to §13.2:** the OTLP log ledger rotates to two windows (~3.6 h in
July, ~16 h in September) across a 10-week history. Usage counts from it are spot checks, not
rates. `git reflog` does not record `worktree add`, so worktree creation count is a floor.

**Lost under stop:** cross-agent collision sensing (never shown to have fired against a real
cross-agent collision), two-tier readiness (HTTP-ready ≠ Worker-ready; a single health check
collapses it), junction-safe teardown on Windows (tied to a real incident class), ONNX-preserving
hot reload (unused in the sample), spawn reaping (new, unmeasured), typed MCP ergonomics.

**Worker's judgment (evidence-only):** value is asserted in design tempdocs and not established
by the operational logs; where logs retain history they show routine self-cleanup more often
than cross-agent rescue.

### 13.6 Product-side policies

Worker: opus, 62 tool uses. Corrections to §9 line counts: VDU is 2,627 lines (not ~2,000);
the UI static oracles are 22 scripts / 3,529 lines (not 27 / 3,948).

**Bounding facts:** the repository has **zero user-filed issues** (`gh api … issues` → `[]`).
Two releases: v0.1.0 (2026-06-25, 4 downloads) and v0.2.0 (2026-08-13, 16 downloads;
`latest.json` 1 download). Every incident cited anywhere in this tempdoc was found by an agent,
by static audit or on an agent-driven dev stack. No product-side policy has ever been exercised
by an end user.

| Subsystem | Lines only-because-of-policy | Incidents field / stress | What is actually lost under relaxation | Value |
|---|---|---|---|---|
| GPU-only fail-closed inference | **≈ 0 today.** 919 is `DESIGNED … not started`; existing supervision predates it and serves the general crash case. | 386, 402 static; 819, 843 live on an agent stack (843 not reproducible on re-run); 862 was tooling, retired. **Field 0/5. Device-lost never observed** (919 §1.4). | **CPU fallback is already shipped and load-bearing**: `NativeSessionHandle.java:291` `getCpuSession()`, init-failure fallback `:658-671`, telemetry event `cpu_fallback.triggered`, ADR-0004's 2026-06-17 amendment deliberately runs query-embed on CPU in Online Mode; 919 §5.1 lists three shipped CPU-fallback behaviours it does not change. The only written *why* for "no CPU fallback" is a dev-machine argument (656: the 9B CPU fallback DOSed concurrent worktrees); the product-side ban is an owner directive dated 2026-09-03 inside an unstarted tempdoc, not an ADR. The defensible part is **process separation** (`llama-server.exe` as its own crash domain), which is a different claim. | stated, partly self-contradicting |
| OCR process ownership (913 lines) | the registry + `destroyForcibly` teardown is a minority | orphan leak was **static** (`OcrConfidenceExtractor.java:64-66`), class deleted in the same PR (706), so recurrence is structurally impossible | The cheap part is the orphan fix. The expensive part is throughput: 706 measured Tika's serial OCR at 35–350 s stalls per scanned doc; the owned loop measured **115,055 → 16,774 ms (6.9×)** durably, ~5× corpus-level, plus per-document budgets returning partial page-ordered text. | **demonstrated and quantified**; strongest row |
| VDU (2,627 lines) | all | none; not incident-driven | Gated behind an optional 918 MB vision model (`necessity: adds-feature`), `hasVisionCapability()` guard, and Online Mode contention with chat. **No scan-fraction measurement exists anywhere** (607, 686, 705, 897 checked). What is lost is a public claim (`README.md:122` "Vision OCR", `strategy.md:267` "REAL & shipping") and a routing threshold. | stated only |
| Config precedence (1,845 + 1,503) | ordinal-merge mechanics only | n/a | 250 env/sysprop pairs, 111 YAML keys, 56 config keys vs a user-facing `UiSettings` of ~72 members. No telemetry can say which knobs users set (privacy forbids it, strategy §U8). **Brief premise corrected:** the install-plan model has 10 main-source consumers and is the runtime spine of the in-app model download flow every user goes through. The library swap is sound; dropping the domain model is a product change. | mechanics commodity; model not |
| UI static a11y/contrast oracles (22 / 3,529 + baselines) | the oracles | 18 commits | **Every traced contrast catch came from measured axe or by hand**, once *because* the static gate was blind (853: `check-contrast-matrix` parsed only two of four palettes, F-07 4.40:1 body text "had to be found by hand"). `ui-a11y-baseline.v1.json` is not an oracle but the shared known-violation ledger both tiers need. The one thing runtime axe cannot replace is the four-palette sweep. | mostly demonstrated the other way |
| Updater glue (2,288) | Ed25519 compat gating, two-boot reconciliation, sandbox autorun | 2 commits total, added 2026-07-31 | **Has never run for a user**: v0.1.0 predates `updater.rs`; no release after v0.2.0, so no in-app update was ever offered. The invariant (models ~9 GB must survive update) is real; the gate concedes it "cannot prove a built installer is clean". | entirely stated |
| NDJSON OTel exporters (834) | allowlist, size roll, free-space guard | n/a | Consumers are agent-side (`telemetry-io.mjs`, 30 tempdocs). The load-bearing piece is the **`ALLOWED_ATTRS` redaction allowlist** (`NdjsonSpanExporter.java:27-45`) on telemetry written to the user's disk; an SDK stdout exporter has none. | demonstrated, agent-side |

**Routed:** `governance/ui-a11y-baseline.v1.json:19` carries a self-declared unexplained a11y
debt row (`search` surface, `aria-valid-attr-value`) predating public history.

### 13.4 Dual-harness, skills tree, tempdoc process

Worker: sonnet, 65 tool uses. Shared caveat: the hooks event ledger retains two rotations
(~2 days, 2026-09-03 → 09-05); every telemetry number here is a 2-day window against a 72-day
history. Git committer identity is one person for every commit, so authorship metadata cannot
say which harness produced a change.

**A. Dual-harness.** Codex was introduced **two days before this analysis** (`4de6f20d`, #623,
2026-09-03). In those two days: 131 `mcp__codex_app__*` calls, 36 local `codex/*` branches vs 76
`worktree-*`, 10 vs 11 on origin. Hand-authored Codex-specific surface is **899 lines**
(`.codex/` 163, `AGENTS.md` 163, adapter 251, generators 212, parity check 110); the 7,279-line
`.agents/skills/` mirror is generated. Hook logic is shared, not duplicated: one manifest,
40 scripts, 3 excluded from Codex with reasons. Lost under "drop Codex": ~8.2k lines (89%
generated), the parity gate, the bridge, and whatever sits only on 36 open `codex/*` branches
(not inspected).

**B. Skills tree.** 28 `SKILL.md`, 7,082 lines. **One explicit `Skill` tool invocation in
39,590 logged events** (2 days); the ledger does not record which skill. Weak proxy (tempdoc
mentions / commits / lines): `search-quality` 65 / 82 / 3,709; `jseval` 160 / 12 / 551;
`governance` 101 / 4 / 115; `inference-runtime` 21 / 23 / 1,238. Never mentioned anywhere:
`collision-check`, `present-status`, `session-retro` (one commit each, their creation). The two
CLAUDE.md-named registers: `search-quality` updated 82 times against ~151 closed tempdocs in its
domain (≈54%); `inference-runtime` 23 against ~126 (≈18%). The "update the register before you
close" rule is followed about half the time in one domain and a fifth in the other.
`consult-register.v1.json` (244 lines) is a separate path-triggered delivery channel that
measures nothing about skill use.

**C. Tempdoc process.** 667 files, **468,187 lines**, mean 702, median 444; 153 over 1,000
lines, 40 over 2,000, 6 over 4,000 (max 5,542). 407 of 889 PRs touch them. 285 (43%) carry a
closed-ish status. Strict citation proxy (`tempdoc NNN` convention): 79% cited by another doc,
**21% never cited**. Reading cost: no persisted token attribution exists (`context-attribution`
runs on demand and nothing stored it); in the 2-day window, 3,008 Bash commands mention
"tempdoc" and 10 of 26 `Read` calls target a tempdoc. `tempdoc-age-hint` fires on every tempdoc
read because agents "read tempdocs at length" (620 Part V), and carries no measured number.
Nearest commodity: 49 ADRs in `docs/decisions/` (13.6× fewer) plus PR bodies. **No evidence
either way that a human has read a tempdoc**; the operator identity is shared with agents.

## 14. Is equal function necessary? (orchestrator's pass)

Three tests per area, applied to §13's evidence rather than to the code's rationale: **who
consumes** the function (end users, public claims, the maintainer's agents, nobody); **has it
been exercised** (demonstrated vs stated); **how reversible** is the loss (the code stays in git;
is the failure it guards silent or loud?). "Not necessary" means a *lesser* function suffices,
not that the concern was wrong.

### 14.0 Three facts that bound every verdict

1. **The counterfactual has already run.** For ~68 of 72 public days most gates ran in no
   workflow, the hook layer's git rules caught nothing real, and jseval's test suite ran nowhere.
   Recorded damage over that period: one unregistered projection (#614), one silent inert gate
   (`dead-code`), one class-size treadmill. `main` did not collapse. A layer whose absence for
   two months is indistinguishable from its presence is not delivering equal function today.
2. **There are no end users yet.** Zero user issues, 20 downloads of the current release, no
   in-app update ever offered. Every "user-protecting" policy is protecting a future user; its
   cost is paid now. That does not make the policies wrong, it makes them *deferrable* until a
   release cadence exists that would exercise them.
3. **Usage cannot currently be measured.** Both ledgers (hooks events, OTLP logs) retain about
   two days. Every "is X used" question in §13 was answered from a spot-check. **Precondition for
   every stop decision below: set ledger retention to ≥ 60 days first** (§4.5's 17 GB trace
   directory is the wrong thing being retained; the events and logs are the right thing). Then
   most verdicts below become checkable in a month.

### 14.1 Verdicts

| Area | Consumer | Exercised? | Equal function necessary? | What suffices |
|---|---|---|---|---|
| **Governance kernel + ratchet protocol** (4.3k + 2.4k) | maintainer's agents | 3 outcome changes / 72 d; 62% of changesets are its own arithmetic | **No.** | Commodity ratchets (ESLint suppressions, ArchUnit Freezing, PIT threshold, knip, dependabot, `buf breaking` bare). Lose the written *why* per pin; `git blame` + PR body carry it well enough for one maintainer. |
| **21 register-semantic gates + 53 registers** (≈14k + 7.9k) | agents; 31 registers unread by any code | 1 catch in postmortems (`execution-surface`), 20 gates with no incident | **No, with five exceptions.** | The five incident-backed invariants (§13.1 table) as ArchUnit rules and ~3 unit tests, ≈ 200–300 lines total. Delete the rest and the 24 sync-only checks with them. A register whose only reader is a check that keeps it in sync with another register is a fork of nothing. |
| **UI static oracles** (22 / 3.5k) | agents | catches came from measured axe or by hand; one gate was structurally blind | **No.** | Measured axe via ui-shot + the shared a11y baseline ledger + the one four-palette contrast sweep. |
| **Hook guards** (`subagent-model-guard`, `repeat-guard`, `build-counter`, `intervene`) | agents | 132 blocks, plausibly useful, no false-positive finding | **Yes, cheaply.** | Keep as-is. ≈ 1k lines. |
| **`bash-guard` git + sleep rules** | agents | **0 true / 11 false positives; ~117 sleep blocks on the permitted poll form** | **No.** | Native `permissions.deny` for force-push. Delete the main-worktree git rules (`bypassPermissions` agents that know the rule from `branch-safety.md` did not attempt a real violation in 30 days) or, if kept, fix cwd and compound parsing first and measure again. Delete the sleep rule outright: its measured effect is ~117 wasted turns/month. |
| **Hint hooks** (~30) + `known-state-hint` + expected-state pins | agents | one study, negative (739); `known-state-hint` 988 fires; 4 pins carrying real reds | **No.** | Delete all hints not backed by a positive measurement. Replace pins with the rule the file already states: red on `main` is fixed, not pinned. `compact-restore` and `otlp-sink-ensure` are plumbing, keep. |
| **Session analytics** | maintainer | friction mining and cost→merge join fed 727 and 908; 10 CLIs at 0 invokers since July | **Two instruments yes, the rest no.** | Keep `mine-friction`/`aggregate`/`timeline`, `baseline-economics` + `record-merge`, `expected-state-probe` (until pins go). Delete the ten zero-invoker CLIs and `test-pipeline.mjs` (§11 row 1). Hook tests (15k lines) shrink with the hooks. |
| **jseval validity gates** (`ce_coverage`, `comparability`, `chunk_completeness`, `corpus_certify`, `corpus_leak`, `utility_claim_policy`; ≈ 3.5k) | public README claims + 7 recorded decisions | **7 confirmed catches, two in the system under test that no commodity can see** | **Yes.** | Keep. This is the highest value-per-line code in the repository. |
| **jseval orchestration extras** (`bisection`, `cohort_baselines`, `drift_calibration`, `baseline_shift`, `compare_runs`, `index_identity`, `corpus_fidelity`; ≈ 1.7k + 650 how-to lines) | nobody | **zero artefacts ever produced** | **No.** | Delete, with their how-to pages. ranx for plain comparison. |
| **jseval agent-utility cluster** (13.1k) | one public publication (a null) | claim policy has one catch (719); the machinery has produced one result in 72 d | **Not now.** | Freeze: no new development until a second publication candidate exists. Keep the claim policy and certification (above); the rest waits. |
| **jseval tests** (46.9k lines, 143 files) | nobody: 1 file runs in CI | 99.3% unexecuted | **The function is necessary; the current state delivers none of it.** | Wire the CPU-only pytest suite into CI. If a file cannot run there, delete it. Assurance that never runs is the same as no assurance at a higher cost. |
| **jseval checked-in data** (≈ 280k lines of churn, 166 MB) | nobody: run dirs cited 0–5 times, in prose | — | **No.** | §11 rows 3–4. The single largest reduction available in the repository by any measure. |
| **Tempdoc process** (468k lines; 46% of PRs; 16% of all churn) | agents (no human-read evidence) | 21% never cited; 153 over 1,000 lines | **No.** The design-history *function* is necessary; the *volume* is not. | (a) size cap on new tempdocs, enforced by the existing `intervene`-style hook at write time, not read time; (b) closed tempdocs move out of the agent-searchable path into `docs/tempdocs/closed/` with a one-paragraph digest left behind; (c) decisions that are current truth go to ADRs (49 today). The tempdoc corpus is the largest context-token sink agents have and the only "maintenance" item that grows by construction. |
| **Skills tree** (7k; two registers 4.9k) | agents | 1 `Skill` call in 2 days; register discipline followed 54% / 18% | **Two registers yes; the rest unmeasured.** | Keep `search-quality` and `inference-runtime`. Measure the other 26 for 30 days after retention is fixed; fold or delete those with zero invocations. |
| **Dual-harness** (899 hand-authored + 7.3k generated) | maintainer, actively (131 bridge calls in 2 days) | too new to judge | **Yes for now.** | Reassess at 30 days by which harness produced merged PRs. The cost is mostly generated output plus one parity check. |
| **Dev-runner lease/ownership** (≈ 6.3k) | agents | 0 cross-agent rescues; enforcement covers only `start` (606) | **No.** | A lock file with holder + timestamp, checked on `start`. Keep two-tier readiness (small). Keep `remove-worktree.cjs` (real Windows incident class). |
| **Hot-reload** (283 Java + JDWP wiring + 2 tempdocs) | agents | **0 uses observed** | **No.** | Delete. Warm restart costs ~40 s; nobody paid the reload path to avoid it. |
| **Dev MCP server** (4.2k) | agents | 7 calls in one window, 0 in another | **Unmeasured.** | Measure 30 days; delete tools with zero calls. |
| **GPU fail-closed (919)** | future users | not built; CPU fallback shipped and relied on by ADR-0004 | **No.** | Do not implement 919 as specified. Keep process separation for `llama-server.exe`. Record the decision in 919's status, which currently says "do not re-litigate §4 without writing why": this is the why. |
| **OCR process ownership** | future users | 6.9× throughput, re-measured | **Yes.** | Keep. |
| **VDU** (2.6k) | a README claim | optional 918 MB model, no scan-fraction measurement | **Unknown, and it should not be.** | Measure the scan fraction of a representative corpus before the next release. Below a threshold the founder sets, retire the code and the claim together. Do not delete blind: it is a public claim. |
| **Config precedence mechanics** | — | — | **No.** | §11 row 15 spike stands; keep `EnvRegistry` and the install-plan model (10 runtime consumers). |
| **Updater glue** (2.3k) | future users' ~9 GB of models | never run for a user | **Yes, but the necessary function is a test, not more code.** | Keep; add a CI job that performs a real v0.2.0 → next update on a runner with a seeded model directory. Until that exists the code is untested field code guarding the most expensive user asset. |
| **NDJSON exporters** | agent-side analytics; privacy allowlist on user disk | — | **Allowlist yes, rest could be SDK.** | Keep. Small. |

### 14.2 What the second frame changes

The first frame (§11) retired ~6.5k lines. This frame retires **roughly 65–75k lines of code and
tests, ~280k lines of data churn, and the growth rate of the largest prose corpus**, and it does
so by lowering function where the function was never exercised, not by finding a better
library. The per-area evidence in §13 is what makes that defensible; §10's "policy has no
vendor" was true and beside the point, because most of the policy has no consumer either.

Two costs of this frame are real and stated plainly:

- **Silent-compliance loss.** A gate that kept every PR compliant leaves no artefact. The
  counterfactual in 14.0(1) is the answer: two months without most gates produced one recorded
  drift. That is the measured size of the silent benefit.
- **Rebuild cost if wrong.** Everything deleted stays in git history and in this tempdoc's
  citations. What does not come back is the *habit*; if a deleted gate's incident class recurs,
  the recurrence is the evidence that justifies rebuilding that one gate, per
  `structural-defects-no-repeat`.

## 15. Re-ranked table under the second frame

Ordered by (lines or bytes retired) ÷ risk, with the retention precondition first because it
gates the "unmeasured" rows.

| # | Item | Retire | Risk | Verdict |
|---|---|---|---|---|
| 0 | **Set hooks-event and OTLP-log retention to ≥ 60 days; drop trace retention to 14 days** (§4.5, §14.0) | −17 GB disk | none | **GO first** |
| 1 | jseval data hygiene (§11 rows 3–4) | 166 MB tracked, ≈ 280k lines/quarter of churn | low | **GO** |
| 2 | Fix public claims: `README.md:146` "regression-gated in CI" (false), `README.md:153` vs `:128` release date, `methodology.md`/`scorecard.md` superseded numbers (§13.3) | honesty | none | **GO, before any release** |
| 3 | Governance stop, **re-scoped by §17.2**: delete the 24 sync-only checks, the unread registers (keep `store-recoverability`, §16), the changeset ceremony, the dashboard; adopt commodity ratchets (§11 rows 2, 5–8 fold in); carry the five incident-backed invariants as ArchUnit/unit tests. Wired, green, incident-free gates are inert and may stay. | ≈ 15–20k lines | medium: one PR with a full 742 sweep | **GO, staged**: commodity adoption PR first, deletion PR second |
| 4 | Hook stop: delete `bash-guard` git/sleep rules (force-push → native deny), all unmeasured hints, `known-state-hint` + pins; keep the four guards + plumbing | ≈ 15–20k incl. tests | low: the deleted rules had no true positives | **GO** |
| 5 | Analytics stop, **narrowed by §17**: delete `test-pipeline.mjs`, `analyze-session`/`-trends`/`generate-index`, `evaluate-session`/`outcome-session`; keep friction + cost→merge and the 886/743/844 instruments until those lanes close | ≈ 5.3k | none | **GO** |
| 6 | jseval extras, **narrowed by §17**: delete the cohort-envelope / drift-calibration machinery and its how-tos, replace with an absolute encoder-latency line; keep `index_identity`, `baseline_shift`, `corpus_fidelity`; `compare_runs` → ranx or keep; `bisection` inert | ≈ 0.7k + 2 how-tos | none | **GO** |
| 7 | Wire jseval's CPU pytest suite into CI; delete what cannot run | 0 lines, +assurance | low | **GO** |
| 8 | Hot-reload path | ≈ 300 + wiring | — | **DEFER** (§17: real 35 s → 2 s saving if used; measure after row 0) |
| 9 | Tempdoc volume policy: size cap at write time, closed-archive path with digests, decisions → ADRs | growth rate of 468k lines | low | **GO** (policy; founder sets the cap) |
| 10 | UI static oracles → measured axe + baseline ledger + four-palette sweep | ≈ 3k | low | **GO** |
| 11 | ~~Dev-runner lease → lock file~~ | — | — | **WITHDRAWN** (§17: 11 cross-session reclaims in two months; a lock file reintroduces 606's defect D1) |
| 12 | Do not implement 919's fail-closed GPU policy; keep process separation | avoids 4 PRs | none | **GO** (decision record in 919) |
| 13 | Updater: add a real update CI test | +1 job | — | **GO** |
| 14 | Skills (26 non-register), dev MCP tools, Codex parity: measure 30 days after row 0, then delete zero-use items | ≈ 2–8k | — | **DEFER 30 d** |
| 15 | jseval agent-utility cluster | 13k frozen, not deleted | — | **FREEZE** |
| 16 | VDU | 2.6k + a public claim | — | **MEASURE**, then decide |
| 17 | Keep unchanged: jseval validity gates, OCR ownership, four hook guards, friction + cost join, two skill registers, NDJSON allowlist, install-plan model, `remove-worktree.cjs` | — | — | **KEEP, evidence-backed** |

First-frame rows not superseded (§11: 9 retention-first cost migration, 10 lychee, 11 skills
projection shrink, 13–18 spikes) stand as written.

## 16. Orchestrator verification of the relayed §13 claims (2026-09-05)

Founder asked whether the §13 findings were validated or merely relayed. Pass 1 and 2 workers
were read-only researchers; the orchestrator had verified ~15 load-bearing claims inline (noted
per section). This section records a second sweep over the rest, one command per claim, breadth
over depth. Legend: ✓ confirmed; ≈ confirmed with a different number, same conclusion;
✗ worker wrong, corrected.

| Claim (section) | Result | Note |
|---|---|---|
| 47 substantive changesets; classification split 13/13/9/3/3/2/2/1/1 (13.1) | ✓ | exact |
| 5 changesets titled `advance-baseline-to-NNN` (13.1) | ✓ | |
| 16 of 35 gates have zero changesets (13.1) | **✗ → 26 of 35** | worker undercounted; the stop case is stronger |
| 31 of 53 registers unreferenced from `modules/` (13.1) | ≈ 19–31 by method | basename grep finds 34 referenced, but 17 of the hits are comments |
| "No production code path reads any register" (13.1) | **✗ by one** | `modules/ui/.../UpgradeReconciliationProbe.java:200` loads `/governance/store-recoverability.v1.json` from the classpath. Every other main-source reference is javadoc or a built asset comment. That register must be kept or its consumer re-pointed in the governance stop. |
| One documented gate catch in postmortems (13.1) | ✓ | `agent-postmortems.md:96` |
| `bash-guard` git rules: 27 blocks, 0 true positives, 11 false (13.2) | ✓ | orchestrator joined every `is_error` block to its `tool_use` command: **11 results, all false**: 5 `cd`-into-worktree compounds, 2 single-file `--ours`/`--theirs`, 2 scratchpad/manifest echoes, 2 `gh workflow run … -f` chained after a plain push. 21 "main worktree" + 4 "force push" `is_error` results in 30 days. |
| sleep rule ≈117 blocks (13.2) | ✓ | 116 `is_error` results |
| `known-state-hint` 988 fires (13.2) | ≈ | 1,048 raw string hits, includes echoes |
| 19 pins, uniform `reviewBy 2026-09-30`, 3 with owner (13.2) | ✓ | exact |
| 1 `Skill` invocation in the 2-day ledger (13.4) | ✓ | 2 lines (pre + post) |
| Codex first commit 2026-09-03; branch split (13.4) | ✓ | 46 `codex/*` incl. remote vs 79 `worktree-*` local |
| 667 tempdocs / 468k lines / 153 > 1,000 / 40 > 2,000 / 6 > 4,000 (13.4) | ✓ | exact |
| 21% of tempdocs never cited (13.4) | ≈ 9–21% by regex | orchestrator's stricter pattern (also matching `NNN-slug` filenames) gives 62 of 666 |
| bisection / cohort / drift / compare-runs: zero artefacts in any run dir (13.3) | ✓ | 0 files for each name outside source and tests; `bisection.py` has 1 commit |
| `ci.yml:92` says the 132-file test suite runs nowhere; 46,941 test lines (13.3) | ✓ | |
| Catches at 916:775-780 (`degraded-ce`, 13 silent deadline drops) and 635:1006 (`COMPARABLE=False`, arm-C error rate 0.20) (13.3) | ✓ | both quoted verbatim |
| 201 dev-runner runs; 20 interference events; ~13 self-reclaims (13.5) | ≈ | 201 ✓, 20 ✓, **9 of 20** self-reclaim by actor = victim session id; still roughly half |
| Hot-reload 0 uses (13.5) | ≈ 0–1 | the OTLP log embeds the tool schema in request bodies, so every tool name appears ~55 times per window; only 2 `tool_result`/`tool_use` lines mention `reload`. Unused in the sample; the sample is two days. |
| CPU fallback shipped: `getCpuSession()` (13.6) | ✓ | called at `NativeSessionHandle.java:213,218,248`; `cpu_fallback.triggered` in `08-observability.md:581`; ADR-0004 update dated 2026-06-17 at `:96` |
| OCR 115,055 → 16,774 ms, 6.9× (13.6) | ✓ | 706 status line and `:345`; the per-file table says 6.8× |
| VDU gated on `hasVisionCapability()` (13.6) | ✓ | `VduBatchProcessor.java:162`, `VduProcessor.java:166` |
| `updater.rs` added 2026-07-31, 2 commits (13.6) | ✓ | `6a6e4835` |
| Two releases; 0 user issues; v0.2.0 setup 16 downloads, `latest.json` 1 (13.6) | ✓ | via `gh` |

**Net effect on §14/§15:** no verdict changes. Two corrections strengthen the governance stop
(26 not 16 gates without a changeset) and one narrows it (keep or re-point
`store-recoverability.v1.json`, which has a real runtime consumer). Every number derived from
the 2-day ledgers remains a spot check until §15 row 0 lands.

## 17. "Unused is not useless": re-judging the deletion candidates on merit (2026-09-05)

Founder challenge: *"maybe the reason they weren't used yet isn't actually cause they are
worthless."* Correct, and §14 leaned on invocation counts. This section re-judges each
deletion candidate by reading what problem it solves, whether that problem has occurred or
plausibly will, and what it actually costs to keep. Orchestrator's own reads, cited.

### 17.0 A correction to the cost model

§14.2's "65–75k lines retired" counts lines. Maintainer effort tracks **churn**, not existence:
a module with one commit and no consumers costs nothing to keep except agent reading load.
The things that actively cost are (a) interruptions with no true positive (sleep rule, hint
noise, pins), (b) sync checks that force edits in several places per change, (c) large
always-read prose, and (d) data churn policy. Inert code is a different category: deleting it
saves clone size and agent context, not maintainer hours. §17.2 re-sorts on that basis.

### 17.1 Verdict changes

| Candidate | What it does | Why it was unused | Re-judgment |
|---|---|---|---|
| **Dev-runner lease / presence model** (§14 "→ lock file") | Decides whether the one shared dev stack is held by a *live* session or an abandoned one, and frees it without a human. | It was not unused. `interference-events.ndjson` dispositions: **9 cross-session `stale_reclaim`, 1 `forced_reclaim`, 1 `warned_takeover`** in two months. Each is a case where an agent freed a stack another session had abandoned. 606 §"Evidence from the field" records the pre-model failure exactly: one abandoned holder blocked three agents in an hour, each needing a human to type "takeover and proceed". | **REVERSED: keep.** A lock file with a timestamp cannot tell "alive" from "abandoned"; that is defect D1 that 606 fixed. The acting layer (reaper, queue) is what produced the 11 cross-session reclaims. Trim only if a specific piece is shown idle. |
| **`index_identity.py`** (1,156 lines; listed in §14 extras) | The identity key for the eval-index cache (751): lets `jseval run` adopt a previously built index instead of rebuilding. | It is not unused: `commands/run.py:673-676` calls `compute_live_identity` on every run. | **REVERSED: keep.** Misfiled by the worker's artefact search; it produces cache adoptions, not named output files. |
| **`baseline_shift.py`** (184) | The changeset-justification convention for jseval's own floors (perf-gate, leak-gate, release recompose). | Not unused: `scripts/jseval/.changesets/` holds three real changesets (leak-gate 666, perf 715 ×2). | **REVERSED: keep.** It is the protocol that made the 832 re-baseline explicit. |
| **`corpus_fidelity.py`** (158) | Certifies a corpus is non-trivial and genuinely multi-hop. | Built after a real failure (635: a clean corpus scored nDCG 0.97, too easy to measure anything). Detection-only by design. | **REVERSED: keep.** 158 lines against a documented corpus-design failure that will recur whenever a corpus is generated. |
| **`compare_runs.py`** (359) | Paired t-tests per query between two runs (`scipy.stats.ttest_rel`, `:53`). | Decisions in 712/713 read raw deltas; nobody asked for significance. | **Function is necessary, code is replaceable.** Over-reading a 0.005 delta is the classic eval mistake. Keep the function via `ranx.compare` (already an optional dep) or keep this file; either is fine. Not a deletion. |
| **`cohort_baselines` + `drift_calibration` + `projections/encoder_drift`** (~500 + how-tos) | Detect encoder span-duration drift (a silently slower ONNX path) against a per-cohort baseline of ≥3 runs. | **No `cohort_baselines/` directory exists anywhere**, so the projection has never had a reference. The design is brittle: the cohort hash changes with any input, so baselines go stale constantly (hence a whole how-to on envelope staleness). | **Concern valid, mechanism impractical.** The risk it targets is real and silent, and §13.6 shows CPU fallback is exactly such a path. Replace with a fixed absolute p50 encoder-latency line in every run summary plus the product's existing `cpu_fallback.triggered` event. Delete the calibration machinery and its two how-to pages. |
| **`bisection.py`** (451 + 193 how-to) | When two runs differ by more than the envelope, swap one manifest axis at a time against a run cache to attribute the delta. | Practice here is controlled A/B (916 changes one thing per arm), so the situation it serves rarely arises; it also depends on the envelope above. 1 commit. | **Downgrade to inert, not delete-for-savings.** A legitimate tool with no occasion yet; keeping it costs nothing. Delete only if the envelope machinery goes, since it depends on it. |
| **Analytics instruments for open lanes**: `context-residency`, `spawn-economics`, `overhead-taxonomy`, `context-attribution` (886), `dev-tool-usage` (844), `signature-census` (743) | Productionised measurement scripts so a past audit can be re-run identically after a change. | "Zero invokers" is their normal state between measurements. 886 is the founder's running lane; the CLAUDE.md delegation falsifier is due 2026-09-14 and needs spawn and rework measurement. Most have `.test.mjs` files. | **REVERSED: keep** at least until 886/743/844 close. Deleting an instrument nine days before its judgment date would destroy the evidence path. |
| **`evaluate-session` + `outcome-session`** (1,266) | LLM-as-judge session outcome, folded into a fact join. | `outcomes.ndjson` was never produced; `judge-outcomes` has 2 rows; the composite score it fed was retired (858). | **Delete stands.** The consumer was retired; these are the residue. |
| **`analyze-session` / `analyze-trends` / `generate-index`** (1,833) | The older hooks-events → session-report → trend pipeline. | 745 F-1 found 0 invokers; `mine-friction` reads transcripts directly and does not depend on them. `analyze-session` has no `.test.mjs`; `test-pipeline.mjs` was its test and is stale. | **Delete stands**, with `test-pipeline.mjs`. If the trend view is wanted later, the hooks events are still there. |
| **`cost-session.mjs`** | Transcript-based cost with an embedded pricing table. | Superseded by native `api_request.cost_usd` (§4). | Delete after §11 row 9 lands, not before. |
| **Hot-reload** (283 + wiring) | Push method-body changes into the running Worker in ~2 s instead of a 35 s build + restart (305 measured). | Default is off (`dev-runner.cjs:154`); method-body-only; agents build with Gradle anyway. 0–1 uses in a 2-day sample. | **Downgrade to defer.** The saving is real if used; the cost of keeping is 2 commits. Measure after retention lands. |
| **Run directories in git** | Raw campaign outputs; the evidence behind tempdoc conclusions (624, 782). | They are write-once; they do not churn after landing. | **Reframe.** The maintainer-effort lever is the *policy* (do not commit run outputs; they go to a gitignored archive or a release asset). Removing the existing ones from the working tree saves clone size and agent search noise, and history keeps them. Keep the reframed row but drop the effort claim. |

### 17.2 Re-sorted: what actively costs vs what is merely inert

**Actively costing (do these):** sleep rule and false-positive git rules (~130 wasted
turns/month); `known-state-hint` and the pin mechanism (red on `main` normalised); the 24
sync-only checks and the registers with no code consumer (every change fans out); tempdoc
volume policy; data-commit policy; the public-claim defects; wiring the 47k-line test suite
or deleting it.

**Inert (keep unless a sweep is happening anyway):** bisection, hot-reload, the analytics
instruments for open lanes, the 20 incident-free gates *that are already wired and green*.
Their deletion is a hygiene choice, not an effort saving. The governance stop in §15 row 3
should be re-scoped to the sync checks, the kernel's changeset ceremony, and the unread
registers; a wired gate that never fires costs one CI step.

**Reversed outright:** lease model, `index_identity`, `baseline_shift`, `corpus_fidelity`.

Revised §14.2 figure: the actively-costing set is on the order of **20–25k lines** plus the
policies; the rest of the earlier 65–75k is inert and its removal is optional.

## 18. Consolidated plan (current state; supersedes §11, §15, §17 where they differ)

Read this section alone if you only want the plan. Everything above is the evidence trail.

### 18.1 Do now: actively costing, or simply wrong

| # | Change | Why it survived |
|---|---|---|
| 1 | Ledger retention: hooks events + OTLP logs ≥ 60 days; traces 14 days | every usage question so far was answered from a 2-day window; 17 GB of traces is the wrong thing retained |
| 2 | Fix public claims: `README.md:146` CI-gating (false), `README.md:153` vs `:128` release date, `methodology.md` / `scorecard.md` superseded numbers | public enforcement claim with nothing behind it |
| 3 | Data policy: campaign outputs and generated corpora are never committed; existing `*-run-*` dirs leave the working tree (history keeps them); `battlefield-*` corpora regenerate via `corpus_build.py`; de-dupe `781-corpora` vs `707-corpora` | a quarter of all lines ever changed; write-once data is not maintenance but it is clone size and agent search noise |
| 4 | Hooks: delete the sleep rule and the main-worktree git rules (force-push → native `permissions.deny`); delete all hints without a positive measurement incl. `known-state-hint`; retire the expected-state pin mechanism and fix the four reds it hides. Keep `subagent-model-guard`, `repeat-guard`, `build-counter`, `intervene`, `compact-restore`, `otlp-sink-ensure` | 0 true / 11 false git blocks, 116 sleep blocks on the permitted form, one hint study and it was negative; pins normalise red on `main` |
| 5 | Governance, staged: (a) adopt commodity ratchets (ESLint bulk suppressions for `ts-any`/`todo-fixme`, knip, ArchUnit `FreezingArchRule`, PIT threshold, bare `buf breaking`, dependabot); carry the five incident-backed invariants as ArchUnit/unit tests; (b) delete the 24 sync-only checks, registers with no code consumer (keep `store-recoverability`), the changeset ceremony, the dashboard, the legacy npm-audit script, `test-to-code`; collapse the 7 regen wrappers. Wired, green, incident-free gates may stay as inert | 62% of changesets are the gates' own arithmetic; 26 of 35 gates never had one; 3 outcome changes in 72 days; sync checks fan every edit out |
| 6 | Analytics: delete `test-pipeline.mjs`, `analyze-session`/`-trends`/`generate-index`, `evaluate-session`/`outcome-session` | documented dead, 0 invokers since July, consumer retired in 858 |
| 7 | jseval: delete cohort-envelope + drift-calibration machinery and its two how-tos; add a fixed encoder-latency line to run summaries; wire the CPU pytest suite into CI and delete what cannot run there | no baseline has ever existed; 47k test lines run nowhere |
| 8 | Tempdoc volume policy, **founder-agreed design 2026-09-05**: a CI check (`check-tempdoc-size.mjs`, same shape as `check-always-loaded-budget`) that fails when a tempdoc **touched in the diff** exceeds the cap, measured per tempdoc number (`NNN-*.md` plus any `NNN-*/` sidecars together, one named evidence directory exempt). No baseline, no changeset: a flat rule on changed files, so the 153 existing oversize tempdocs do not go red until edited. The failure message names three remedies, never "summarise": settled truth → canonical doc or ADR; bulk evidence/tables/logs → the exempt evidence sidecar; closed sections → a dated digest. The `intervene` hook delivers the same message at write time. Cap: founder sets it; data says median 444, mean 702, so 600–800 | 16% of all churn, 468k lines agents read, no human-read evidence; the predictable evasion (splitting one design into five tempdocs) is countered by counting per number |
| 9 | UI static oracles → measured axe + shared a11y baseline + the four-palette contrast sweep | every traced catch came from axe or by hand |
| 10 | Record in tempdoc 919 that fail-closed GPU inference is not built as specified; keep `llama-server.exe` process separation | CPU fallback is shipped and relied on by ADR-0004 |
| 11 | Updater: add a CI job that performs a real v0.2.0 → next update with a seeded model directory | never executed for a user; guards ~9 GB |

### 18.2 Optional spikes (first frame, unchanged)

quicktype for `gen-wire-schema-types` · smallrye-config / Typesafe Config for `ResolvedConfigBuilder` (keep `EnvRegistry` + install-plan model) · Web Awesome for dialog/dropdown/toast chrome with a11y re-measured · `reuse` for SPDX headers · lychee for link audit · HotswapAgent for hot reload · native `api_request.cost_usd` replacing the pricing table (after row 1).

### 18.3 Defer 30 days after row 1, then decide on data

Non-register skills (26) · dev MCP tools with zero calls · Codex parity surface · hot-reload · `cost-session` migration.

### 18.4 Freeze / measure

Agent-utility cluster frozen until a second publication candidate exists · VDU: **skipped by founder decision 2026-09-05**; not in scope for this tempdoc's implementation. The measure-first note in §14.1 stands as advice for whoever next touches it.

### 18.5 Keep, on evidence

jseval validity gates (`ce_coverage`, `comparability`, `chunk_completeness`, `corpus_certify`, `corpus_leak`, `utility_claim_policy`) · `index_identity`, `baseline_shift`, `corpus_fidelity`, `compare_runs` (or ranx) · dev-runner lease/presence model · OCR process ownership · the four hook guards · friction mining, cost→merge join, and the 886/743/844 instruments · `search-quality` and `inference-runtime` registers · NDJSON exporter allowlist · install-plan domain model · `remove-worktree.cjs` · `store-recoverability.v1.json`.

### 18.6 Routed elsewhere

Trace retention comment in `otlp-sink.py` (745) · `docs/observations.d` residue (872) · unexplained a11y debt row (`ui-a11y-baseline.v1.json:19`) · the 20 gates comment in `ci.yml:305-307` is stale (913).

## 19. Derisk pass (2026-09-05, `/derisk`; founder: proceed autonomously, so no plan-mode gate)

### 19.1 Uncertainties, ranked by how much they could change the plan

| # | Item | Uncertainty | Why it matters |
|---|---|---|---|
| U1 | 18.1 row 5 | "Registers with no code consumer" was measured against `modules/` only. Codegen, ui-shot, hooks wiring, and docs generators read registers from `scripts/`. | Deleting a register a generator reads breaks a regen check, and the deletion list could shrink a lot. |
| U2 | 18.1 row 5 | "Delete 24 sync-only checks" is under-specified. A sync check removed without removing one side of the duplication just silences drift. Which duplications can actually collapse to one authority? | Could turn "delete checks" into "delete authorities", a different and larger change, or into "keep the check". |
| U3 | 18.1 row 5 | If wired incident-free gates stay on the kernel, the kernel and its changeset protocol stay for them. What exactly is deletable then? | Bounds the governance PR. |
| U4 | 18.1 row 7 | The jseval pytest suite has never run in CI. Pass rate, runtime, and stack-bound tests are unknown. Deleting the envelope machinery touches 20 test files. | Row 7 could be a week of test repair, not a wiring step. |
| U5 | 18.1 row 4 | Native `permissions.deny` semantics under `bypassPermissions` are unverified. Removing the pins requires fixing four real reds (`docs-validate`, `ts-any`, `runtime-manifest-closure`, `wire`); their fix cost is unknown. | Force-push protection could end up with no carrier; pins could be un-retirable. |
| U6 | 18.1 row 11 | A real updater test needs a Windows runner that can run the NSIS installer silently, a seeded model directory, and a built installer from CI. Feasibility unknown. | May not be doable in hosted CI; could be self-hosted only. |
| U7 | 18.1 row 2 | How README numbers and the methodology/scorecard blocks regenerate; whether `relevance_gate` is run anywhere locally so the sentence can be corrected rather than deleted. | Wrong fix reintroduces drift. |
| U8 | 18.1 row 1 | How hooks `events.ndjson` rotates (who, on what trigger); disk cost of 60 days of logs. | Retention change could be a one-liner or a redesign. |
| U9 | 18.1 row 6/8 | Consumers of the analytics scripts slated for deletion (skills, docs, README). The size check would fail on this tempdoc itself (>1,100 lines). | Sweep completeness; the first casualty of row 8 is 930. |
| U10 | 18.1 row 9 | Which of the 22 UI static scripts the ui-shot harness or governance baselines depend on. | Deleting one the harness imports breaks ui-shot. |

### 19.2 Plan to reduce them

1. Consumer maps (read-only workers): (a) every `governance/*.v1.json` → all readers repo-wide by path and by basename, classified runtime/codegen/check/doc/test; (b) every `scripts/ci/check-*.mjs` in the sync buckets → what two things it compares and whether one side can be deleted; (c) analytics scripts, hook files, and the 22 UI scripts → all referencing files. Resolves U1, U2, U9, U10.
2. Kernel boundary read: which gates convert to commodity, which stay; what the kernel needs to keep for the stayers. Resolves U3.
3. Run the jseval pytest suite locally with the CPU-only subset, record pass/fail/runtime; list tests touching the envelope modules. Resolves U4.
4. Runtime probe of `permissions.deny` under bypass with a harmless rule; read the four pinned reds and estimate each fix. Resolves U5.
5. Read `build-installer.yml`, runner labels, and NSIS silent-install flags; decide hosted vs self-hosted. Resolves U6.
6. Trace README/methodology/scorecard regen scripts and `relevance_gate` invocations. Resolves U7.
7. Read the events rotation code and measure current daily log volume. Resolves U8.

Findings land in §19.3; confidence rating and model recommendation in §19.4.

### 19.3 Findings

**F1 (U5, resolved by runtime probe).** `permissions.deny` IS enforced under
`defaultMode: bypassPermissions`, takes effect without a restart, is evaluated **per segment of
a compound command**, and matches **prefix only**. Probe: a deny rule `Bash(echo DENYPROBE930*)`
in `.claude/settings.local.json` blocked `echo DENYPROBE930 …`, blocked
`echo first && echo DENYPROBE930 …`, and allowed `echo not-a-prefix DENYPROBE930`. Rule removed
after the probe. Consequence: deny rules on the two force-push spellings carry that protection
with none of the `gh workflow run -f` false positives, because the `-f` is in a different
segment. Row 4 is safe on that point. (Live confirmation of the false-positive class while
writing this section: `bash-guard` blocked a documentation heredoc because the *text* contained
the force-push spelling.)

**F2 (U8, changes row 1's design).** Hooks events rotate at 10 MB to a single `.prev`
(`lib/event-writer.mjs:8-28`); one day of activity fills ~10 MB, so 60 days ≈ 600 MB, fine.
OTLP **logs do not scale**: they rotate at 20 MB (`otlp-sink.py:232`) about every 25 minutes of
active work (three 21 MB files between 01:46 and 04:46 on 2026-09-05), roughly 1 GB per active
day, because `api_request` rows embed request bodies. Sixty days of raw logs is 30–60 GB.
**Row 1 must instead add a compact derived stream**: the sink extracts `api_request`,
`subagent_completed`, and `tool_result` metadata (no bodies; ~500 rows/day ≈ 1 MB/day) into a
`ledger.ndjson` retained ≥ 60 days; raw logs keep their 2-day window. ~80 lines in
`otlp-sink.py` plus one reader in `telemetry-io.mjs`. Traces to 14 days is a one-line
`RETENTION` change.

**F3 (U3, kernel boundary).** Gates with a baseline file: `config-surface` (239 B, 15
changesets), `dead-code` (10 KB, 6), `dead-code-jvm` (2.8 KB, 0), `module-deps` (empty, 0),
`test-to-code` (1.4 KB, 3), `todo-fixme` (438 B, 2), `ts-any` (1.1 KB, 2). After converting
`ts-any`, `todo-fixme`, `dead-code-jvm`, `npm-audit`, `style-literal-ratchet` (unwired today) to
commodity and deleting `test-to-code`, the kernel still hosts `config-surface`, `dead-code`,
`prose-tier-register` (17 changesets, all bookkeeping), `ssot-catalog-sync`, and ~20 register
gates without baselines, 25 of them wired. **The kernel stays.** The deletable protocol surface
is `prose-tier-register` (it tags CLAUDE.md rules with a tier; its 17 changesets are the
ceremony) plus the tier-register doc it guards. Keep the same-diff repin rule for
`config-surface` and `dead-code`. Sweep note: seven hint hooks and
`always-loaded-budget.v1.json` reference `tier-register.md`.

**F4 (U9, self-inflicted).** This tempdoc is 1,121 lines and will fail the row-8 size check the
first time it is edited after the check lands. The row-8 PR must restructure 930 first: §4–§9
and §13 evidence move to `docs/tempdocs/930-evidence/` (the exempt sidecar), leaving §1–§3,
§18, §19 in the main file. That is also the worked example the check's message points to.

**F5 (U4, partial, from the expected-state pin dated 2026-09-02).** The jseval suite was run
on this machine three days ago: 2,661 passed, 3 collection errors from optional deps
(`inspect_ai`, `hypothesis`, `jsonschema`) not installed locally. Full run in progress for
runtime; 28 test files reference the envelope modules and need editing or deleting in row 7.

**F6 (U6, resolved: HOSTED-FEASIBLE, 1–2 days).** `build-installer.yml:55,594` already runs on
`windows-latest` (18 min per build), and its `installer_verify` job already performs a real
silent NSIS install + uninstall via `scripts/ci/verify-installer-nsis-win.ps1` (`/S /D=`,
per-user, no admin). The SAC blocker is local-only. Three real gaps: (1) the update path is NSIS
`/UPDATE /ARGS` (`updater.rs:1354-1356`), not `/S`, so the existing proof does not cover it;
(2) updater metadata needs an Ed25519 signature (`updater.rs:906-919`); the workflow already has
a `sandboxTestMode` dispatch input and `updater.rs:1214,1236` override hooks built for an
N→N+1 lane, but **no script drives them yet**; (3) the Windows-Sandbox harness needs a GUI
desktop and is out. Cheapest real test: on `windows-latest`, seed a sentinel in
`%APPDATA%/<bundle>/models/`, install the N+1 build with `/UPDATE /ARGS` against a CI-generated
Ed25519 key via the sandbox override, assert the sentinel survives. Models live outside the
install dir (`lib.rs:573,791`; `installer-hooks.nsh:89` only does a non-recursive `RMDir`).

**F7 (U5, the four pinned reds, all 10–30 lines, none weakens a check).**
`docs-validate` heading-case: delete the rule (`docs-validate.mjs:66-90`); 6,751 violations, zero
enforcement point, many headings intentionally lowercase. `ts-any`: strip comments/strings
before counting (pattern exists at `check-readiness-reason-codes.mjs:116-148`), rebalance; or
simply superseded by the ESLint conversion in row 5. `runtime-manifest-closure`: two consumers
read `runtime/api-port.txt` (`packaging/mcpb/server/index.js:33`,
`scripts/sandbox/mcp-typed-confirm.mjs:109`); publish the port as a `manifest.json` field and
repoint both. `wire`: install `buf` in CI via `bufbuild/buf-setup-action`; **fail-open confirmed
live** (gate returns `pass` with a finding while `buf` is absent), which is a second defect the
bare `buf breaking` step in row 5 removes.

**F8 (U7, resolved).** README numbers are hand-authored and *checked* against
`scripts/jseval/release.v1.json` by `check-readme-benchmark-numbers.mjs` (`ci.yml:394`);
`gen-public-benchmark.mjs` targets only `methodology.md`; `gen-scorecard.mjs` targets
`scorecard.md`. The 832 commit (`71212bee`) touched README and the register but neither
generated doc, and the two drift checks that would have caught it
(`check-release-baseline-sync.mjs`, `gen-scorecard.mjs --check`) are **not in any workflow**.
Both fail today. Regen sequence: `gen-public-benchmark.mjs`, `gen-scorecard.mjs`, hand-fix
`README.md:153` (2026-07-16 → 2026-08-14), reword `README.md:146` and `methodology.md:83` to:
*"Per-corpus nDCG@10 floors are checked against a pinned baseline at release-composition time
(`python -m jseval relevance-gate`); a local gate, not a CI job. The README table itself is
checked against the release object in CI."* Then wire the two drift checks into `ci.yml`
(routed as a row-2 sub-item).

**F9 (U1 + U2, resolved; shrinks row 5 substantially).** Full consumer map over the **55**
registers (not 53) with path and basename search; none is orphaned. Three groups:

- **Group C, 15 registers with a runtime, codegen, or harness consumer: keep.**
  `store-recoverability` (Java classpath read, `updater.rs:32` `include_str!`, gradle copy),
  `logic-seams` (PIT scoping in `MutationConventionsPlugin.kt:113`), `agent-hooks`,
  `observed-happening` (→ generated Java + TS constants), `registry` (the kernel),
  `consult-register` (hook layer + ui-web gate set), `config-lifecycle`, `sandbox-coverage`,
  `sandbox-defect-classes`, `status-facts`, `ui-proportion-baseline`, `ui-a11y-baseline`,
  `design-reference` (all ui-shot), `llama-server-arg-rejection`, `supervision-contract`
  (test-enforced).
- **Group B, 13 registers whose consumer is a registered kernel gate**: deleting the register
  means deleting that gate. All non-gate references are javadoc or tests. `modals` and
  `transients` also appear in generated `governance-state.json`; check the emitter first.
- **Group A, 27 registers whose only non-doc consumer is one dedicated `check-*.mjs`**: the
  "register ↔ code" invariants (e.g. `language-agnostic-analysis`, `readiness-reason-codes`,
  `search-degradation-reason-codes`, `live-witness`, `run-renderers`). Deletable as
  register + check pairs, **but each pair is a declared invariant, not a sync artefact**;
  `language-agnostic-analysis` is CLAUDE.md hard invariant 6. Two checks are shared with
  Group C registers and cannot go. Several checks carry clauses beyond the register
  (`check-platform-lifecycle.mjs` 538 lines). 14 are in the `ui-web-gates` recipe.

**The "24 sync-only checks" claim from §13.1 does not survive the read.** Of the 24 checks
examined: **7 are pure `--check` regen wrappers** (`agent-hooks-wiring`, `api-client`,
`field-constants`, `liveness-constants`, `wire-schema-types`, `notices`, `shape-handler`
static mode) foldable into one `regen-all --check` step (~150 lines, 7 CI/npm entry points);
1 is a collapse candidate (`check-dev-mcp-doc-sync`: make the tool tables a generated include);
2 are partial folds (`release-baseline-sync`, `codex-agent-parity`); **the other 14 are
single-authority lints or genuine two-source invariants** (`mcpb-consistency` hash contract,
`jseval-lock`, `install-api-contract`, `ui-step-coverage`, `liveness-constants-single-authority`,
`premerge-table` dangling-reference linter, …) and stay.

Routing finding: six of these checks are wired in no workflow (`agent-hooks-wiring-regen`,
`field-constants-regen`, `release-baseline-sync`, `intent-tier-coverage`, `premerge-table`,
`ui-baseline-schemas`); they can only fail when a human runs them.

**Revised row 5 scope**: (a) commodity conversion of `ts-any`, `todo-fixme`, `dead-code-jvm`,
`npm-audit`, `style-literal-ratchet`; delete `test-to-code` and the legacy npm-audit script;
(b) fold the 7 regen wrappers; (c) delete `prose-tier-register` + `tier-register.md` with its
seven hook references; (d) remove the governance dashboard (`GovernanceView.ts`,
`GovernanceStateController.java`, `lib/dashboard.mjs`, the `governance-state.json` emitter);
(e) **a founder decision list**, not a deletion: the 27 Group-A pairs and 13 Group-B gates,
each with its incident record from §13.1, for per-invariant keep/drop. The kernel stays.

**F10 (U9 + U10, resolved; sweep maps for rows 4, 6, 9).**

*Row 4, hooks.* Removal is mechanical: delete the hook's catalog entry **and** its binding entry
in `governance/agent-hooks.v1.json`, delete the file and its `.test.mjs`, run
`gen-agent-hooks-wiring.mjs` (regenerates `.claude/settings.json`,
`settings.local.json.example`, and local settings); `.codex/hooks.json` is per-event and needs
no edit. The `hook-integrity` gate enforces both directions (orphan file or dangling manifest
entry fails) **and** `tier-sync`: every `hook:` row in
`docs/reference/contributing/tier-register.md` must resolve, so those rows are edited in the same
PR (moot if `tier-register.md` goes with `prose-tier-register`, F3). One cross-import:
`codex-hook-adapter.test.mjs:16-17` imports from `maintain-doc-hint` and `context-ceiling-hint`;
the adapter itself does not. Prose sweep: `hooks-reference.md` (near-total rewrite),
`agent-lessons.md:16-50`, `branch-safety.md:86-194`, `21-agent-analytics-pipeline.md` hook table,
`agent-guide.md`, `common-workflows.md:13`, `development-philosophy.md:12`,
`consult-register.v1.json:3` `$comment`, `expected-state.v1.json:4` `$comment`, five skills in
both trees. **Decision taken for the plan:** keep the four guards, `compact-*`,
`otlp-sink-ensure`, `mcp-session-inject`, `export-session-env`, `subagent-guide`, the two
861 spawn hooks (they belong to the reaper, kept in §17), and four path-scoped regen pointers
(`ssot-hint`, `lockfile-hint`, `mcpb-repack-hint`, `docs-regen-hint`); delete the other 19
hints plus the git/sleep/`cat` rules in `bash-guard`. Adjustable by the founder per hook.

*Pins.* 19 pins; `known-state-hint` surfaces them pre-command, `expected-state-probe --gate`
(`ci.yml:217`) fails on missing exit or past `reviewBy`. Retiring the mechanism = delete both
scripts, the JSON, the ci step, and fix the four reds (F7); the 15 flake pins become nothing
(a flaky test is fixed or quarantined in its own runner, not remembered).

*Row 6, analytics.* The six scripts have **no importer** outside their own tests; no lib is
orphaned (`telemetry-io` has 19 consumers, `transcript-store` 11). Sweep: `21-agent-analytics-
pipeline.md` rows 44/134/194-243, `README.md:8,29`, `context-efficiency.md:7` and the identical
sentence in `hooks/subagent-guide.mjs:87` (names `analyze-session.mjs` as a "known large
file"), stale comments in `mine-friction.mjs:5-9`, `telemetry-io.mjs:17`,
`transcript-store.mjs:9`, `signature-census.mjs:5`, `context-attribution.mjs:35`.

*Row 9, UI static scripts.* The `ui-web-gates` recipe (`consult-register.v1.json:37`) names 22
scripts; `run-ui-web-gates.mjs` parses that prose, one authority; `ci.yml:203-209` runs it and
pins the count. **None of the 22 depends on ui-shot or the two governance baselines, and
nothing in `scripts/jseval` references them**: cleanly deletable. `check-contrast-matrix.mjs`
(keep) reads `tokens.css` directly and owns its four-palette cascade resolution
(`PALETTES` at `:172`, cascade regexes `:147-149`) with no shared import. **Scope
correction:** 8 of the 22 are Group-A register checks from F9 (`run-renderers`,
`inflight-liveness`, `composition-surfaces`, `declared-surfaces`, `live-channels`,
`adaptive-closure`, `layout-purity`, `surface-composition`) and `message-single-model` is a
structural invariant; row 9 covers only the **a11y/contrast/token oracles**:
`a11y-closure`, `controls-a11y`, `color-tokens`, `theme-token-closure`, `accent-as-text`,
`presentation-purity`, `observed-state-collapse`, `offline-single-sense`,
`printable-keybinding-policy`, `gen-token-names --check`, `gen-component-vocabulary --check`,
`strip-token-fallbacks --check`. The register-backed ones join the F9 decision list.

**F11 (U4, resolved by running the suite).** `python -m pytest -q` under `scripts/jseval`,
2026-09-05: **3,036 passed, 1 failed, 10 skipped, 17 min 12 s.** The failure is
`tests/test_run.py::test_execute_run_always_emits_a_cadence_block` (a `summary["cadence"]`
shape assertion; a real, small defect). Ten `test_execute_run_*` tests take ~34 s each
(~6 min of the total) because an unmocked HTTP call waits out a 30 s timeout
(`commands/run.py:476` `httpx.post(url, timeout=30)`); mocking it brings the suite to ~11 min.
The optional-deps collection errors from the 09-02 pin did not recur (deps now installed).
Row 7 is therefore: fix one test, mock one call, add a CI job (`windows-latest` or
`ubuntu-latest`, CPU only, ~11 min in parallel with the JVM job), then remove the envelope
modules and edit the 28 test files that reference them.

### 19.4 What was learned, confidence, and model recommendation

**Learned.** (1) Two of the eleven rows were mis-scoped and are now correctly bounded: the
governance row is a commodity conversion plus 7 regen folds plus a 40-item founder decision
list, not a mass deletion (F3, F9); the retention row needs a compact derived ledger, not longer
raw retention (F2). (2) Native deny rules work under bypass, per segment, prefix-only (F1), so
row 4 has its carrier. (3) The updater test is hosted-feasible; the missing piece is a driver
script, not infrastructure (F6). (4) The jseval suite is green but for one test and one timeout
(F11). (5) Every sweep is enumerable (F10) and two of them are gate-enforced (`hook-integrity`,
`registry`), which converts sweep misses into loud failures. (6) This tempdoc must be split
before row 8 lands (F4).

**Confidence per row (0–10):** row 1: 8 · row 2: 9 · row 3: 7 (corpus regeneration time and
determinism unexercised in this pass) · row 4: 7 (wide prose sweep; gate-enforced) · row 5: 6
(five commodity conversions each touch build config; dashboard removal touches product code;
the 40-item list is a founder decision) · row 6: 9 · row 7: 7 · row 8: 8 · row 9: 8 ·
row 10: 10 · row 11: 5 (sandbox-override plumbing and CI key generation are untested).
**Overall: 7/10** for rows 1–10 as a set; row 11 is a separate 5.

**Difficulty.** Mostly mechanical multi-file sweeps with self-verifying acceptance
(gates, regen `--check`, pytest, `hook-integrity`). Judgment is concentrated in row 5's
conversions (ESLint suppressions file, ArchUnit `FreezingArchRule` test, PIT threshold, bare
`buf`), row 1's sink change, row 3's corpus regeneration, and row 11's new CI lane.

**Model / effort recommendation.** Orchestration and the founder-decision list: this session.
Implementation chunks: **sonnet, medium** for rows 2, 6, 8, 9, 10 (enumerated edits with a
runnable check each). **opus, high** for rows 1, 3, 4, 5, 7 (multi-file sweeps where a missed
reference or a wrong regen is silent until CI, and where test edits need judgment). Row 11:
**opus, high**, as its own later lane once rows 1–10 have merged.

**Not started:** no feature work was done in this pass; the only repo change is this tempdoc.
The probe deny rule was added to and removed from `.claude/settings.local.json`
(gitignored, session-local). `tmp/930-pytest.log` holds the suite output.

## 20. Implementation plan (2026-09-05, `/plan`; founder: proceed autonomously, no PR until told)

### 20.1 Decisions taken so work can proceed (founder may override any)

| Decision | Default chosen | Why |
|---|---|---|
| Tempdoc size cap | **800 lines** per tempdoc number | mean 702, median 444; catches the tail, spares normal work |
| The 40 register-backed invariants (F9 Groups A+B) | **keep all for now**; produce the decision list as `docs/tempdocs/930-evidence/invariant-decision-list.md` | they are wired, green, and inert (§17.2); deletion is reversible later, a wrong deletion is silent |
| Hook keep list | per F10: four guards, plumbing, two 861 spawn hooks, four regen pointers; delete 19 hints + `bash-guard` | measured evidence in §13.2 |
| Row 11 (updater test) | **deferred to its own lane** after rows 1–10 merge | separate 5/10 confidence, 1–2 days, new CI plumbing |
| Existing `*-run-*` dirs | leave the working tree via `git rm`; no history rewrite | F: write-once data; policy is the lever |

### 20.2 Chunks, in dependency order

Each chunk is one branch in its own worktree (`isolation: "worktree"`), one squash-PR later.
Every chunk ends with: the named verification green, a `retire-with-a-sweep` grep for every
deleted name across code, config, gates, baselines, docs (excluding `docs/tempdocs/`), the
UTF-8/NUL diff check, and a status line in 930. Nothing is pushed or PR'd.

| Chunk | Rows | Touches | Verification | Model |
|---|---|---|---|---|
| **A** | 2, 10 | `README.md:146,153`, `methodology.md:83` + regen, `scorecard.md` regen, `ci.yml` (+2 drift checks), `919` status | `check-readme-benchmark-numbers`, `check-release-baseline-sync`, `gen-scorecard --check`, `check-root-readme`, `check-workflow-triggers` | sonnet |
| **G** | 8 | new `scripts/ci/check-tempdoc-size.mjs` + test, `ci.yml` step, `intervene.mjs` write-time message, CLAUDE.md pre-merge row, **930 split** into `930-evidence/` | its own test; the check green on the split 930; `check-premerge-table`; `run-all-tests` | sonnet |
| **B** | 1 | `otlp-sink.py` (ledger stream, traces retention 14 d), `telemetry-io.mjs` reader + test, `21-agent-analytics-pipeline.md`, `README.md` (analytics) | a synthetic OTLP POST produces a ledger row; `run-all-tests`; Python syntax check | opus |
| **C** | 3 | `git rm` of `*-run-*` dirs (after a tempdoc citation scan), `battlefield-*` `docs.jsonl` → regenerated via `corpus_build.py` with `.gitignore` entries, `781` vs `707` de-dupe, a `scripts/jseval/README` policy note, `dataset_cache` docs | `pytest tests/test_corpus_*`; regeneration byte-diff proof; `check-jseval-lock` | opus |
| **D** | 7 | fix `test_run.py` cadence test; mock the 30 s `httpx.post`; new `ci.yml` job running pytest; delete `cohort_baselines.py`, `drift_calibration.py`, `projections/encoder_drift.py`, `commands/calibrate.py` calibrate-drift, 2 how-tos (`calibrate-drift-baseline`, `envelope-staleness-policy`, `triage-psi-drift`), 28 test references; add encoder p50 latency line to run summary | full pytest green locally; `docs/llms.txt` regen; `verify-canonical-doc-links` | opus |
| **H** | 9 | delete 12 a11y/token oracle scripts + tests + 2 empty baselines; edit `consult-register.v1.json:37` recipe; `run-ui-web-gates.test.mjs` count pin; `ci.yml:203-209`; skills mentioning them | `run-ui-web-gates.mjs` green; its test; `check-ui-baseline-schemas`; ui-web typecheck | sonnet |
| **E** (after A–H land locally) | 4, 6 | manifest + `gen-agent-hooks-wiring`; delete 19 hints + `bash-guard` + tests; deny rules in `settings.json` + `.example`; retire pins (`known-state-hint`, `expected-state-probe`, JSON, `ci.yml:217`) with the four F7 fixes; delete 6 analytics scripts; prose sweep (F10 list); `tier-register.md` hook rows | `--gate hook-integrity`, `gen-agent-hooks-wiring --check`, `gen-codex-hooks --check`, `run-all-tests`, `check-codex-agent-parity`, `check-always-loaded-budget` | opus |
| **F** (after E) | 5 | ESLint `no-explicit-any` + suppressions + `no-warning-comments`; ArchUnit `FreezingArchRule` for `dead-code-jvm`; PMD todo rule; bare `buf breaking` step; dependabot/`npm audit` step; delete gates `ts-any`, `todo-fixme`, `dead-code-jvm`, `npm-audit`, `style-literal-ratchet`, `test-to-code`, `prose-tier-register` + `tier-register.md` + baselines/changesets; legacy `check-npm-audit-ratchet` + test; fold 7 regen wrappers into `regen-all --check`; remove dashboard (`GovernanceView.ts`, `GovernanceStateController.java`, `lib/dashboard.mjs`, route, `governance-state.json` emitter); `registry.v1.json`; kernel doc; CLAUDE.md pre-merge rows; the 40-item decision list | full kernel `run.mjs` green; `gradlew build -x test` + affected module tests; ui-web typecheck + unit + gates; `check-premerge-table`; `run-all-tests` | opus |

Parallel wave 1: A, G, B, C, D, H (disjoint files except `ci.yml`, resolved at PR time).
Wave 2: E. Wave 3: F. Orchestrator: briefs, evidence judgment, merge-order, 930 status.

### 20.3 Acceptance for the whole tempdoc

All chunks green on their named verification; `./gradlew.bat build -x test` and the full
kernel green on the last branch; every deleted name has zero hits outside `docs/tempdocs/`;
930 main file under the cap; §18.1 rows 1–10 each carry a branch name and a verification
transcript pointer in the status line.
