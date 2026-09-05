<!-- Sidecar of docs/tempdocs/930-replace-bounded-areas-with-maintained-oss.md — moved here per the tempdoc size-cap split (930 §19.3 F4, 2026-09-05). Headings below are unchanged from the main file; this directory is exempt from check-tempdoc-size.mjs. -->

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

