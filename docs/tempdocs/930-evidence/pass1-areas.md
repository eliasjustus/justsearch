<!-- Sidecar of docs/tempdocs/930-replace-bounded-areas-with-maintained-oss.md — moved here per the tempdoc size-cap split (930 §19.3 F4, 2026-09-05). Headings below are unchanged from the main file; this directory is exempt from check-tempdoc-size.mjs. -->

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

