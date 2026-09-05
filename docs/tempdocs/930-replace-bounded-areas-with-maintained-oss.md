---
title: "Replace bounded areas with maintained open source: whole-project analysis (product + agentic system + tooling) of where a polished, regularly-updated upstream can absorb bespoke code, ranked by maintainer effort saved"
type: tempdocs
status: "PUBLISHED (2026-09-05) — §18.1 rows 1-10 landed on main as nine squash PRs #649-#652, #654-#656, #661 and the closeout PR (§22 table, each with a green exact-SHA main run). Deviations per chunk in §21 and per PR in §22.1; tracked follow-ups in §22.2 (npm-audit → dependency-review-action, PMD wiring, jseval-suite as required check, measured axe on PRs, row 11 updater lane). VDU skipped by founder decision."
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


**§4–§9 (agent analytics, gates/CI, codegen/docs, jseval, dev tooling, product seams)** moved to `docs/tempdocs/930-evidence/pass1-areas.md` (size-cap split, 930 §19.3 F4).

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


**§13 ("stop doing X": evidence of delivered value per subsystem)** moved to `docs/tempdocs/930-evidence/pass2-stop-doing.md` (size-cap split, 930 §19.3 F4).

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


**§16 (orchestrator verification of the relayed §13 claims)** moved to `docs/tempdocs/930-evidence/verification.md` (size-cap split, 930 §19.3 F4).

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


**§19.3 (findings F1–F11, in full)** moved to `docs/tempdocs/930-evidence/derisk-findings.md` (size-cap split, 930 §19.3 F4).

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

## 21. Chunk status (2026-09-05; integration branch `worktree-930-oss-stop`)

Each chunk was built by a pinned worker in its own worktree, verified there, then merged into
`worktree-930-oss-stop`. Nothing pushed, no PR opened. Merge conflicts (E1×E2 on the analytics
doc, README, CLAUDE.md pin bullet, subagent-guide) were resolved by the orchestrator keeping
both sides' deletions.

| Chunk | Row | Branch / commit | Result | Verification |
|---|---|---|---|---|
| A | 2, 10 | `worktree-agent-afffd8db7c469ec5e` @ `a9bd47dc` | README:146/153 corrected; methodology + scorecard regenerated to 832; `check-release-baseline-sync` + `gen-scorecard --check` wired into CI. **919 note NOT applied**: 919 is an untracked in-flight tempdoc owned elsewhere; the decision text is held for its owner. | 7 checks green |
| G | 8 | `worktree-agent-a392ce4e214927feb` @ `c4a42a51` | `check-tempdoc-size.mjs` (cap 800/number, `NNN-evidence/` exempt) + 31-assertion test + CI step; write-time hint rides on `intervene`; 930 split to 541 lines + 4 evidence files; fixed a collision false-positive in `tempdoc-scan.mjs` the sidecar convention exposed | 12 checks green, 14-gate kernel sweep 0 fail |
| B | 1 | `worktree-agent-ae8ca089c4d6422b7` @ `1b0058fa` | `ledger.ndjson` body-free projection stream (allow-listed attrs, `session.id` only identity), `RETENTION` ledger 90 / traces 14; reader + 41 tests; e2e POST verified | 6 checks green. **Owner heads-up:** the live sink still runs old code; its next restart prunes the 17 GB trace backlog to 14 archives in one step |
| C | 3 | `worktree-agent-a938ece104cd29894` @ `a9bb2171` | 6 of 9 run dirs removed (−29 MB, −103k lines); 3 kept as cited publication evidence (`624-run-2026-07-03`, `624-run-2026-07-18-confirmatory`, `782-run-…-hero`); **corpora NOT regenerable** (generator's name minter replaced in 767, recipes predate `entity_bank`), kept; **781/707 de-dupe refused** (781 is the v2 cohort pinned by the sha256-chained publication); data policy + `.gitignore` rules landed | 206 corpus tests + full suite (1 known failure, D's) green |
| D | 7 | `worktree-agent-ac9697e187acd5527` @ `057c5bc9` | cadence test fixed (test drifted after #612); slow tests mocked (3 unmocked socket helpers, not the post): `test_run.py` 402 s → 7.5 s; suite 2,940 pass / 10 skip in 6 m 41 s; new `jseval-suite` CI job on ubuntu; envelope/drift machinery removed (−5.3k) incl. 4 how-tos; `encoder_latency` p50/p95 summary block added; **a real defect found and fixed at root**: dropping envelope keys from `_VOLATILE_FIELDS` would have invalidated every 707 certificate (`manifest.py:63-78` `_RETIRED_VOLATILE_FIELDS`) | 10 checks green; Linux CI run unverified |
| H | 9 | `worktree-agent-adb37ddbd567d9f28` @ `13666922` | 10 static oracle scripts + 2 empty baselines deleted (−1,785); two token generators kept (frontend build consumes their output); recipe 22→10, runner pin 40→27; sweep over 42 files | 27/27 gates, FE 6,274 tests, build green |
| E1 | 4 | `worktree-agent-a8604472032635671` @ `956d6126` + `7025ec76` | `bash-guard` + 21 hints retired (−5,973); 20 hooks kept; force-push → native `permissions.deny` (`.claude/settings.json:5-10`) for Claude and a per-segment, quote-stripped, token-exact refusal in `codex-hook-adapter.mjs:36-72` for Codex (11 tests); changeset `930-hook-stop.md` (`tier-change` ×8); new `harness` tier | hook-integrity, wiring, parity, 51/51 tests, budget 62,122/63,084 B |
| E2 | 6, 4 | `worktree-agent-a7979eeb14d55d853` @ `f015580d` | 6 analytics scripts deleted (−6,713); pin mechanism retired; `docs-validate` heading-case rule deleted (6,751 findings → 0; 2,143 other findings remain, reported); runtime-manifest red fixed for the right reason (`api-port.txt` had no producer since 501 Phase 18; both consumers read `head.apiPort`); 15 flaky-test pins listed as tracked items | Gradle build + `:modules:ui:test` green, 14 checks green |
| F | 5 | `worktree-agent-a6623df8ad9700035` @ `5f010f63` (226 files, +1,171/−6,923) | registry 35 → 29 gates. `ts-any` → `no-explicit-any: error` + `eslint-suppressions.json` (33 suppressions; ESLint had run in **no** workflow, now wired at `ci.yml:355`); `todo-fixme` → `no-warning-comments` + PMD `CommentContent` (proven to bite); `dead-code-jvm` → `FreezingArchRule` store (byte-equal to the 18-entry baseline); `test-to-code`, `prose-tier-register` (+ `tier-register.md`, `hook-integrity` tier-sync phase; 18 changesets moved to `930-evidence/retired-changesets/`) deleted; 7 regen wrappers → `regen-all.mjs` (found and fixed a permanent SPDX drift in `gen-liveness-constants`); dashboard removed (11 files); `wire` fails closed. **`npm-audit` kept**: `npm audit` exits 1 today (11 high) and ADR-0044's 2026-09-04 amendment rejects its transport on evidence; replacement shape is `dependency-review-action` (founder decision). `style-literal-ratchet` was wired with a 12-row baseline (brief premise wrong): kernel wrapper removed, script enforcement kept. `check-shape-handler-regen` kept its `--live` half as `check-shape-handler-live.mjs`. | full kernel 29/29 (test-efficacy on-demand), full `gradlew test`, ESLint 0, vitest 6,269, 27/27 ui gates, 30/30 + 49/49 script suites, 16 checks |
| — | — | `worktree-agent-acb23558524fc37dc` @ `a2fc0c5b` | `930-evidence/invariant-decision-list.md`: 40 rows; 18 KEEP, 12 KEEP-AS-TEST, 6 DEFER (mutual pairs + shared enforcers), 4 DROP-CANDIDATE (`chip-facts`, `intent-tier-coverage`, `live-witness`, `search-degradation-reason-codes`; none wired, none incident-backed) | |

### 21.2 Final verification on the integrated branch (`0f43a1fd`, 513 files, +5,657 / −129,344 vs base `8da7a24d`)

`gradlew build -x test` SUCCESSFUL · `gradlew test` SUCCESSFUL **but cache-replayed in 25 s** (the
integrated state equals chunk F's tested state plus one markdown file; F ran the real suite with
forced `cleanTest --no-build-cache` on the four touched modules) · ui-web typecheck + 467 files /
6,269 tests · full kernel with `--produce-inputs` and buf installed: 29 gates, 0 fail,
`test-efficacy` skipped (on-demand PIT) · `regen-all --check --except notices` 7/7 (notices needs
the license lane's inputs, as CI already splits it) · `run-ui-web-gates` 27/27 · governance
30/30, agent-analytics 49/49 · premerge-table, workflow-triggers, always-loaded-budget,
codex-parity, tempdoc-size, tempdoc-numbers all pass.

### 21.3 Founder decisions surfaced by implementation

1. `npm-audit`: keep the kernel gate (current), or adopt `actions/dependency-review-action` per
   ADR-0044's replacement clause and retire the gate then.
2. Trace backlog: archive any of the 17 GB before the OTLP sink restarts (chunk B).
3. PMD runs in no CI job; the new Java TODO rule is dormant behind ~78 pre-existing
   `pmdMain` violations. Wire `pmdMain` or accept dormancy.
4. `scripts/**/*.mjs` and `*.ps1` lost TODO-marker coverage with the gate (5 markers were
   baselined); ESLint has no root config.
5. The four DROP-CANDIDATE invariants in the decision list.
6. PR granularity: the work sits as nine chunk branches plus this integration branch; open one
   PR per chunk in dependency order (A, G, B, C, D, H, E1+E2, F) or one PR from the integration
   branch.

Integrated-branch checks after merging A–E (before F): workflow-triggers, premerge-table,
always-loaded-budget, codex-parity, tempdoc-numbers, readme-benchmark, release-baseline-sync,
runtime-manifest-closure, mcpb-consistency, jseval-lock, tempdoc-size, run-ui-web-gates,
hook-integrity, wiring/codex `--check`, prose-tier-register, skills-sync, llms.txt: all green;
`run-all-tests` 49/49; `gradlew build -x test` BUILD SUCCESSFUL.

### 21.1 Routed findings from implementation (not owned here)

- `check-runtime-manifest-closure.mjs:182` `matchesGlob` escapes braces before expanding
  them, so any path containing "js" matches; fixing it narrows real coverage. Needs an
  explicit `SCAN_GLOBS` widening first (E2).
- `docs-validate.mjs` still reports 2,143 findings: 813 `[tags]`, 813 `[aliases]`, 483
  `[heading]`, 33 `[frontmatter-parse]` (all tempdocs), 1 `[encoding]` (743 has a U+FFFD).
- `.claude/settings.json` hooks block was stale before this work and nothing regenerates it
  in the public checkout (`--emit-public-template` targets a private sidecar); E1 hand-edited it.
- `docs/explanation/21-agent-analytics-pipeline.md` component table omits 11 kept hooks.
- `782-run-…-hero/combined-v4/utility-comparison-cross-corpus.v1.json` duplicates a 13 MB
  blob in `agent-utility-records/`; the record store is canonical (C).
- `scripts/jseval/jseval/perf_gate.py:36` unused `import os` (D).
- Claude-side deny is prefix-only and does not cover the `+refspec` force-push spelling; the
  Codex adapter does. Recorded in `hooks-reference.md` (E1).
- 919's owner: apply the decision text held by the orchestrator (§18.1 row 10).

## 22. Publication (2026-09-05; founder: "agree with your recommendations. proceed accordingly. you have authorisation")

Founder decisions on §21.3, in order: nine PRs in dependency order, one per chunk (E1+E2
combined), each cherry-picked onto `origin/main` by a pinned prep worker (local `main` was 297
commits ahead of `origin/main` with another lane's unpublished work, so the integration branch
itself was never pushed); traces pruned, not archived; `npm-audit` kept with a tracked
follow-up; PMD dormancy accepted and tracked; drop `chip-facts`, `intent-tier-coverage`,
`search-degradation-reason-codes`; `live-witness` becomes its backend test; row 11 stays a
separate lane. Enqueue, `merge-wait` and the exact-SHA main run stayed with the orchestrator.

| # | PR | Chunk | Landed on `main` | Main CI |
|---|---|---|---|---|
| 1 | #649 `ci(930): tempdoc size cap per number; sidecars exempt` | G | `c5694b0b` | green |
| 2 | #650 `docs(930): benchmark claims match release; drift gated` | A | `d6786af2` | green |
| 3 | #651 `analytics(930): body-free OTLP ledger stream; traces pruned` | B | `9e0405fb` | green |
| 4 | #652 `jseval(930): campaign outputs leave git; data policy` | C | `e58ed4dd` | green |
| 5 | #655 `ui-gates(930): retire static a11y oracles for measured axe` | H | `b25a6a2b` | green |
| 6 | #656 `hooks(930): retire bash-guard and hint hooks; native deny` | E1+E2 | `a42d039a` | green |
| 7 | #654 `jseval(930): retire drift envelopes; run pytest in CI` | D | `18e2833f` | green |
| 8 | #661 `governance(930): commodity ratchets replace six gates` | F + decision list | `534aaf50` | green |
| 9 | #663 `governance(930): drop three unwired invariants; closeout` | PR 10 | this PR | see PR checks |

### 22.1 What the re-based PRs changed against §21

- **B**: the test fixtures carried this session's real `session.id` and account/org identifiers;
  replaced with synthetic values before publication (attribute names unchanged).
- **C**: the chunk subject claimed a 781/707 fixture de-duplication that the diff did not contain;
  message corrected. Machine finding, not repo: the editable `jseval` install points at a deleted
  worktree (`lane-C5`), so `python -m jseval` fails from any checkout until
  `pip install -e "scripts/jseval[dev,agent]"` is re-run.
- **D**: the new hosted `jseval-suite` job found four missing `pytest.importorskip("inspect_ai")`
  guards (collection aborted the whole suite) and two Windows-only path tests without a
  `skipif`; both fixed. The job is **not a required check** (absent from
  `public-ci-local-repro.v1.json` and `ci-walltime-policy.v1.json`); promoting it is an owner
  action. The `TracingLocalExportTest` brief named the wrong module; the correct run
  (`modules/telemetry`) is 7/7.
- **H**: 17 present-tense comments claiming a retired gate still enforces a property were
  relabelled. Honest limit: the measured axe half of the replacement (`jseval ui-a11y-gate`) is
  local-only by ADR-0026; on PRs only the four-palette contrast sweep runs. `npm run lint` in
  `modules/ui-web` is red on `main` (24 pre-existing errors); nothing claims it green.
- **E**: the retired set is 19 advisory + 3 blocking hooks (`bash-guard`, `taskcreate-guard`,
  `maintain-doc-hint`), not "21 advisory"; corrected in `hooks-reference.md`, the manifest and
  `baseline-economics.mjs`. Four residues E1/E2 missed (takeover skill, three `ci.yml` comments)
  fixed. `world-state.test.mjs` fails under load (18.4 s against a 15 s subprocess timeout and a
  10 s budget) — the pin that hid it is gone, so PR 9 fixes it.
- **F**: the chunk's `ci.yml` had swapped the `npm-audit` kernel gate for plain
  `npm audit --audit-level=high`, contradicting the founder decision (and F's own
  `discipline-gate-kernel.md`); CI failed on 17 pre-existing high advisories and the gate step was
  restored. The commodity swap is viable only together with the dependency upgrades that clear
  those advisories. One missed fixture-path residue in `test-tempdoc-scan.mjs` fixed. Newly
  visible: `npx eslint . --max-warnings=0` reports 37 pre-existing unused-disable warnings
  (CI's `eslint .` is green). PMD with `-PskipPmd=false`: 20 violations in `modules/ui`, none
  `CommentContent`.
- **PR 9 (closeout)**: `chip-facts`, `intent-tier-coverage` and `search-degradation-reason-codes`
  dropped (registers, scripts, CLAUDE.md rows; doc claims of "the gate" corrected). For the
  reason-code pairing, `searchTraceExplain.test.ts` already pins both wording tables against
  declared code lists in both directions, but no typed FE source carries the Java enum values
  (the wire schema types the fields as `string`), so the lists stay hand-kept mirrors, stated as
  such. `live-witness`: register and offline check deleted; ADR-0042 probe now `kind: "test"`;
  `LiveWitnessTest` gained a behavioural assertion that the witness equals the build-tier
  consumer merge over every delivered op (a fork reading only one merge input fails).
  `world-state.mjs` fixed at the root: the per-worktree git loop was serial (31.5 s across 61
  worktrees, 60 % in `status`); now `Promise.all` per row + bounded concurrency 8, measured
  3.9–4.9 s, budget unchanged.
- **Harness lesson (E, D)**: `origin/main` re-fetches in the background, so `git diff origin/main`
  drifts mid-task and `git reset --soft origin/main` can silently pick a parent that already
  contains a later PR; verify `git log -1 --format=%P`. `run-gh checks-wait --required-only`
  reports green with zero checks while a PR is CONFLICTING.

### 22.2 Tracked follow-ups (owner actions; not scheduled here)

1. `npm-audit`: adopt `actions/dependency-review-action` per ADR-0044's replacement clause, then
   retire the kernel gate.
2. PMD `CommentContent` is dormant behind ~78 pre-existing `pmdMain` violations; clear them and
   wire `pmdMain`, or accept dormancy explicitly.
3. `scripts/**/*.mjs` and `*.ps1` lost TODO-marker coverage; ESLint has no root config.
4. Promote `jseval-suite` to a required check (branch protection + the two inventories).
5. `ui-a11y-gate` has no hosted lane (ADR-0026); decide whether measured axe should run on PRs.
6. Row 11 (updater preserves models) needs its own lane: a Windows runner that installs silently.
7. ~~`docs-validate.mjs` still exits 1 on pre-existing `[heading]`/`[tags]`/`[aliases]` findings.~~
   **DONE 2026-09-05** (PR `docs(930): docs-validate exits 0 and runs on PRs`): `tags`/`aliases`
   retired (1,626 warnings, no consumer reads either key and no doc carries one); the H1 counter
   now skips fenced code blocks (the 92 "Multiple H1" hits were `#` comments in shell blocks);
   the H1 rules are scoped to durable docs (`docs/tempdocs/**` H1s are read by nothing — 406
   findings, zero consumers); 96 canonical/runbook docs repaired at the cause; 33 tempdoc
   frontmatter blocks re-quoted so they parse; 743's U+FFFD restored to `ä`. Exit 0, and the
   script now runs in the `Public claims` job, `public-ci-local-repro.v1.json`, `npm run
   lint:docs` and the CLAUDE.md pre-merge table.
8. `modules/ui-web`: 37 unused `eslint-disable` directives (`--max-warnings=0` fails); `npm run
   lint` has 24 pre-existing errors on `main`.
9. Tempdoc 919's owner: apply the row-10 decision text held by the orchestrator.
