---
title: "Wave-1 residue, governance kernel: the dead-code gate's whole-file masking trap, a closed corruptionPolicy vocabulary with a count ratchet, and two pinned pre-existing reds"
type: tempdocs
status: "IMPLEMENTED (2026-09-02) — all three items landed, tests falsified, full kernel run recorded in §D"
created: 2026-09-02
updated: 2026-09-02
lane: wave-1 residue R5 (governance kernel)
model: opus (implementation)
parent: 884-decision-review-lane-b-governance-loop
coordination: "→ lane R4 (worktree resid2-stores) owns `governance/store-recoverability.v1.json` ROWS and may coin a new corruptionPolicy value; item 2 is built so R4's merge cannot break this lane — the row pin is a FLOOR (growth allowed) and a coined value fails with the remedy naming the file to extend, never silently. → lane R6 (worktree resid2-ui) owns the ui-a11y-gate settings-dialog capture fix that retires pin `ui-a11y-gate-settings-dialog-capture-timeout`. → lane R7 (worktree resid2-worker) owns the watcher fix that retires pin `worker-methvin-watcher-create-event-size-race`. Both pins name their fix owner inline and are deleted BY the fixing PR."
related:
  - 885-decision-review-lane-c-runtime-lifecycle-and-isolation   # UL.10 routed findings: the whole-file trap (:3609-3622), ui-a11y-gate (:3596-3605), WorkerMethvinWatcherTest (:3624-3641)
  - 884-decision-review-lane-b-governance-loop                    # wired the dead-code gate into CI; the rebalance changeset this one supersedes the unit of
  - 742-substrate-residue-sweep                                   # the dead-code gate's input contract (D1/D2)
  - 530-discipline-gate-kernel                                    # §2.9 the dead-code ratchet
  - 879-store-recoverability-hardening                            # the register's per-row answers; corruptionPolicy was the one left free text
  - 680-agent-environment-expected-state                          # the pin file's contract
---

# 910 — Wave-1 residue: governance kernel

Three items routed by tempdocs 884/885 into the kernel's own surfaces. Base: `bff70561`
(= `origin/main`), worktree `.claude/worktrees/resid2-gov`, branch `worktree-resid2-gov`.

- **Item 1** — the `dead-code` gate counts a whole-file knip finding as `1` and a per-export finding
  as `N`, on the same ratchet slot. Importing one symbol flips `1 → N` and reads as growth.
- **Item 2** — `corruptionPolicy` was the one answer in `store-recoverability.v1.json` still free
  text (26 spellings), and `pendingDurableClassification.cap` called itself a ratchet while living
  in the same file as the entries it capped.
- **Item 3** — two pre-existing local reds from 885 UL.10 were left in prose because the pin file was
  contended during that wave. They are pinned here.

---

## §B — Pre-implementation pass: every claim in the brief, verified against source

Each row is the brief's claim, then what the source actually says. Corrections are in bold.

| # | Claim | Verified |
|---|---|---|
| B1 | The gate's files are `gates/dead-code/{enforcer,classifications,truth-table,rule-descriptions}.mjs` | Confirmed. `classifications.mjs` and `rule-descriptions.mjs` are one-line re-exports of `enforcer.mjs`; `truth-table.mjs` is 9 lines and its own header says "Verdict logic inlined in enforcer.mjs" (`scripts/governance/gates/dead-code/truth-table.mjs:1`). So **all real logic is `enforcer.mjs` (129 lines)** and the other three needed no change. |
| B2 | They consume `tmp/knip-report.json` | Confirmed. `enforcer.mjs:47` — `resolve(sourceRoot, gate.config?.reportPath ?? 'tmp/knip-report.json')`; declared as a `required` input with producer `npm --prefix modules/ui-web run knip:report` in `governance/registry.v1.json:381-388`. |
| B3 | Baseline is `gates/dead-code/baseline.txt` with rows like `src/api/domains/indexing.ts 1` | Confirmed, **and the path is repo-root-relative, not gate-dir-relative**: `governance/registry.v1.json:378` declares `"path": "gates/dead-code/baseline.txt"` and `enforcer.mjs:48` resolves it against `sourceRoot`. The file is at `<repo>/gates/dead-code/baseline.txt`, **187 lines = 1 header + 186 rows**. |
| B4 | A whole-file finding counts as 1 | Confirmed by measurement, not by reading. `enforcer.mjs:78-83` sums the length of every array-valued key on a knip row; a whole-file row carries `files: [{name: <itself>}]` and empty `exports`/`types`, so it sums to exactly 1. Measured row for `src/api/domains/browse.ts` in the real report: `{"file":"src/api/domains/browse.ts", ..., "exports":[], "files":[{"name":"src/api/domains/browse.ts"}], "types":[]}`. |
| B5 | `indexing.ts` has 9 exported functions + 4 exported types = 13 | **Confirmed exactly.** The syntactic counter returns 13: `addRoot, removeRoot, reindex, SuggestedRoot, getSuggestedRoots, PatternMatch, ApplyExcludesResponse, applyExcludes, previewExcludes, startMigration, FailedJob, listFailedJobs, clearFailedJobs`. This independently reproduces 885's count. |
| B6 | 22 files are in the whole-file state in the current report | **Corrected: 23.** `npm --prefix modules/ui-web run knip:report` on `bff70561` yields 186 issue rows, of which **23** carry a non-empty `files[]`. 885:3622 says "22 files"; the extra is `scripts/capture-evidence-bundle.mjs`, which is outside `src/` and easy to miss when eyeballing `src/`-prefixed rows. **Of the 23, only 11 change value** — the other 12 declare 0 or 1 exports and stay at 1. |
| B7 | "Check what knip's JSON already exposes before parsing TS yourself" | **Checked, and it exposes nothing usable.** A whole-file row's `exports` and `types` arrays are EMPTY — knip reports the module as unused *instead of* enumerating its exports. There is no per-file export count anywhere in the report. Parsing TS is therefore necessary, not a shortcut. |
| B8 | Read how #604 recorded baseline changes | `gates/dead-code/.changesets/884-rebalance-after-wiring-into-ci.md`: frontmatter `classification: merge-import` + `tempdoc: 884`, body a measured account of every row that moved. It states in its own text that `merge-import` sets `growthCovered` and suppresses `silent-growth` for the WHOLE run, and argues that is harmless when baseline == measurement. **§C.1 records why this lane did not reuse that classification.** |
| B9 | Two consumers parse `store-recoverability.v1.json`; a top-level key is safe only if both ignore unknown keys | **Both ignore them, verified at source.** Rust: `modules/shell/src-tauri/src/updater.rs:72-86` declares `LocalStoreRegister`/`LocalDurableStore` with `#[derive(Deserialize)]` + `#[serde(rename_all = "camelCase")]` and **no `deny_unknown_fields`** — `grep -rn deny_unknown_fields modules/shell/src-tauri/` returns zero hits repo-wide, and serde's default is to ignore unknown fields. Java: `modules/ui/src/main/java/io/justsearch/ui/api/UpgradeReconciliationProbe.java:197-215` uses `JSON.readTree(in)` + `JsonNode.get(...)` — **tree access, not data binding**, so `FAIL_ON_UNKNOWN_PROPERTIES` is not in play at all. Adding a top-level key or row fields is safe for both. |
| B10 | 26 distinct `corruptionPolicy` values | Confirmed: 26 distinct values across 36 rows. Full list with owning rows in `governance/store-corruption-policies.v1.json`. |
| B11 | Mirror an existing ratchet; don't invent a mechanism | The nearest shapes are `check-suppression-ratchet.mjs` (per-file integer ceiling map) and `check-always-loaded-budget.mjs` (`ceilings` map + `bumps[]` audit trail). **Both are per-key maps over many keys**; this needs two scalars. The mechanism reused is theirs — *a number pinned in a file outside the thing it measures, with the pin-bump as the printed remedy, and a `bumps` array for the audit trail* — at the scale the subject actually has. |
| B12 | `pendingDurableClassification.cap` already exists | Confirmed, `scripts/ci/check-store-recoverability.mjs:341-353` — and **its weakness is exactly why the pin is external**: the cap lives in the same file as the entries it caps, so one commit could add a pending entry and raise the cap forbidding it. Current state: cap 8, entries 8 — already at cap. |
| B13 | `expected-state.v1.json` pin schema; `known-state-hint` consumer | Validator: `scripts/agent-analytics/expected-state-probe.mjs`; `checkPinShape` (`:29-40`) requires `id`, non-empty `match[]`, `claim`, and at least one of `exitProbe` / `reviewBy`, and fails a `reviewBy` in the past. Consumer: `scripts/agent-analytics/hooks/known-state-hint.mjs` `renderHint` (`:53-62`) reads `id`, `claim`, and optionally `reviewBy`; `matchExpectedState` (`:38-50`) reads `match`. Unknown fields are inert in both. |
| B14 | "The exit probe must pass today and fail once the fix lands" | **The brief has this inverted relative to the mechanism, and the mechanism wins.** `expected-state-probe.mjs:52` treats `res.status === 0` as the probe having FIRED, i.e. the pinned red is GONE and the pin must be deleted. So a correct probe FAILS today and PASSES once fixed. §C.3 records why both pins nonetheless carry no probe. |
| B15 | Neither red is already pinned | Confirmed: zero hits for `ui-a11y-gate`, `WorkerMethvinWatcherTest`, `jf-settings-window` in `expected-state.v1.json` at `bff70561`. 885 says both were left unpinned *deliberately* because the file was contended in that wave (885:3596-3599, :3639-3641), not because they were judged not worth pinning. |
| B16 | `governance/store-corruption-policies.v1.json` needs a schema / registry entry | **No.** Only 2 of 52 `governance/*.v1.json` files have companion schemas (`agent-hooks`, `registry`); `governance/registry.v1.json` registers *discipline gates*, not data files. Precedent checked: `governance/search-degradation-reason-codes.v1.json` is referenced only by its own checker, docs and consumers, and does not appear in `registry.v1.json`. |

---

## §A — What was implemented

### Item 1 — whole-file normalization in the `dead-code` gate

**The defect, stated precisely.** Knip reports a module in one of two shapes and the ratchet stores
one number per path:

- no consumer at all → one `files[]` entry → **1**
- some consumer → one entry per unused export/type → **N**

Two different units, one numeric slot. Importing a single symbol from a whole-file-unused module
therefore raises its row from 1 to N with *no new dead code*, and `enforcer.mjs:104-110` calls that
`dead-code/silent-growth`. 23 of 186 baseline rows were one import away from a false red.

**Reproduced before fixing.** Appending
`import { fetchFolders } from './domains/browse'` to `modules/ui-web/src/api/http.ts`, regenerating
the report, and running `origin/main`'s enforcer against `origin/main`'s baseline
(`tmp/counterfactual.mjs`, scratch, not committed) produced:

```
error dead-code/silent-growth | src/api/domains/browse.ts: 1 → 2 unused exports without declared changeset
```

**The fix.** New `scripts/governance/gates/dead-code/export-count.mjs`. A whole-file finding is
normalized to the module's **own declared** export count, floored at 1. Wired at
`enforcer.mjs:71-96` (current shape) and `:130-137` (legacy `issues.files` shape), with
`projectRoot: "modules/ui-web"` added to the gate config (`governance/registry.v1.json:381`) so
knip's project-relative paths resolve.

The normalized number is an upper bound on any per-export count knip can later report for that
module, so `1 → N` becomes `N → (≤ N)` — a shrink — while a genuinely new dead export still pushes
past the pin.

**"Own declared" is measured, not assumed.** Three probes against knip 6.20.0 on 2026-09-02:

| Probe | Result |
|---|---|
| Import one symbol from `src/api/domains/browse.ts` (4 declared exports) | its row becomes per-export **2** (not 3 — `FolderBrowseResponse` is `fetchFolders`'s return type and becomes used too). `2 ≤ 4`. |
| Namespace-import the pure `export *` barrel `src/api/index.ts` | the barrel's row **disappears entirely** |
| Named-import *through* that barrel (`import { fetchFolders } from './index'`) | the barrel's row **disappears entirely**; the still-unused names stay attributed to the ORIGIN module (`src/api/domains/search.ts` unchanged at 10) |

So knip never attributes a star-re-exported name to the barrel. Following `export *` transitively
would have pinned `src/api/index.ts` at **105** and `src/api/domains/index.ts` at **78** instead of
1 — a large permanent allowance bought for nothing. Named re-exports a module writes itself
(`export { A } from './x'`) *are* attributed to it, which is why
`src/api/generated/shape-handlers/index.ts` counts 9 and
`src/shell-v0/aggregate-substrate/index.ts` counts 29 (28 named + one `export * as operationQuery`
at `:51`).

**Fail-closed, not fall-back-to-1.** If the compiler is unavailable or the named file is not on
disk, the gate emits `dead-code/whole-file-uncounted` at error level and fails. Falling back to 1
would silently reinstate the trap.

**Baseline.** 11 of the 23 whole-file rows changed; row count unchanged at 186. Recorded in
`gates/dead-code/.changesets/910-whole-file-normalization.md` under a NEW classification
`unit-renormalization` (see §C.1).

### Item 2 — closed `corruptionPolicy` vocabulary + external count ratchet

New `governance/store-corruption-policies.v1.json`, deliberately NOT inside the register:

- `policies` — all 26 current values, each with a one-line meaning stated as the **observable
  outcome for the user's bytes** (kept / rewritten / deleted / refused), plus an
  `extensionProcedure` that names the near-synonym failure mode explicitly.
- `ratchet` — `durableStoreRows: 36` (a **floor**) and `pendingDurableClassificationCap: 8` (a
  **ceiling**), with the pin-bump as the stated remedy and an empty `bumps[]` for the audit trail.

Two new exported checkers in `scripts/ci/check-store-recoverability.mjs`, called from `main()`:

- `checkCorruptionPolicyVocabulary` — fails on a value not in the vocabulary, with the message
  naming the file to extend and the extension procedure; and fails on a **declared value no row
  uses**, so the vocabulary cannot outlive its rows.
- `checkCountRatchet` — fails if rows fall below the floor, if the register's own `cap` exceeds the
  external pin, or if pending entries exceed the pin.

They are separate exported functions rather than folded into `checkDurableStoreRegister` because
that function is the row-shape checker whose existing unit tests drive it with invented rows
(including `corruptionPolicy: 'SILENT_EMPTY'` at `check-store-recoverability.test.mjs:158`);
folding a repo-wide vocabulary into it would have made those fixtures depend on the real register's
spellings.

**Merge-safety against lane R4** (§C.2 audits this): R4 adds rows — the floor allows growth. R4 may
coin a value — the gate fails with the remedy naming the file, which is the designed outcome.

### Item 3 — two pins

Added to `scripts/agent-analytics/expected-state.v1.json` (now 19 pins):

- `ui-a11y-gate-settings-dialog-capture-timeout` — matches `ui-a11y-gate`. Claim leads with the
  discriminator ("exit 2 means two steps could not be CAPTURED, not that accessibility regressed").
  Fix owner: lane R6.
- `worker-methvin-watcher-create-event-size-race` — matches a full `gradlew test` scoped to
  worker-services, and an explicit `--tests *WorkerMethvinWatcher*` run. Claim states what it is
  NOT (names no timeout → not covered by `worker-services-30s-timeout-under-load`). Fix owner:
  lane R7.

Both carry `reviewBy: 2026-09-30`, a `fixOwner`, and an `exitProbeOmitted` field saying why no
automated probe exists — see §C.3.

A **third** pin, `operation-surface-indexingjobstates-unregistered`, was added at discovery when the
full-kernel run in §D.3 turned up a red on `main` that nothing pinned. It carries a real `exitProbe`.

---

## §C — Post-implementation critical-analysis pass

### C.1 — Why the baseline changeset uses a new classification

The 884 precedent filed its baseline move as `merge-import`. Re-reading `enforcer.mjs:99-101`,
`merge-import` is one of three classifications that set `growthCovered`, which suppresses
`dead-code/silent-growth` for the **entire run**, every file. 884's changeset argues that is
harmless when baseline == measurement — true for that PR, but the mechanism is a blanket either way,
and reusing it would mean a *counting* change buys a suppression it does not need (the gate is green
at 0 findings without any changeset).

`unused-export-shrink` — the one existing non-suppressing value — would have been a false statement
about direction: 11 rows went **up**.

So `unit-renormalization` was added to `DEAD_CODE_CLASSIFICATIONS` and to
`requireJustificationFor` (so it demands a tempdoc + body), and deliberately **not** to the
growth-covering list. The vocabulary now distinguishes "the tree changed" from "the way we count
changed", which is the distinction this whole item is about.

### C.2 — Which wrong implementation would still pass each test

The falsification runs are in §D. This is the analysis the runs then confirmed.

| Test | Wrong impl that would still pass | Verdict |
|---|---|---|
| `whole-file and per-export findings … count the same` | none of the four probed breakages — it reads the count out of the finding message in both report shapes and asserts equality **and** the literal value 4 | strong; this is the load-bearing test |
| `importing one symbol … is not growth` | **passes on the unfixed gate too**, because it feeds a per-export report against a pin of 4 — the normalization never runs. Honest limit, recorded rather than papered over: it is a *pair* with the test above, which is what establishes that a pin of 4 is the number the whole-file state produces. Alone it would be theatre. | weak alone, sound as a pair |
| `a genuinely new dead export … still fails` | an impl that always reports growth. Guarded by the two "not growth" tests failing in that case. | sound |
| `a module with no exports keeps a floor of 1` | an impl that returns 1 unconditionally (i.e. no normalization at all) — F1 shows the count-equality test catches that | sound as a pair |
| `a bare export * barrel is NOT credited with its transitive surface` | an impl that resolves stars but happens to return 1 here — impossible: the fixture's star target declares 4 | sound |
| the two `fails closed` tests | an impl that fails for the *wrong reason*; both assert the reason substring (`is not on disk` / `TypeScript compiler unavailable`) as well as the verdict | sound |
| `countDeclaredExports counts each declaration form once` | a counter that miscounts one form and compensates on another — each form is asserted separately with its own expected integer | sound |
| item 2's four vocabulary tests | a checker that returns a failure for everything. Guarded by the two positive tests (`inside the vocabulary passes`, `the REAL register passes`). | sound |
| item 2's `gaining a row is allowed` | this test exists *because* the obvious wrong impl is an equality pin — F4 confirms it catches exactly that, and that wrong impl would have redded this gate on lane R4's merge | sound, and merge-critical |

### C.3 — Why neither pin carries an `exitProbe`

The brief asked for "a probe that passes today and fails once the fix lands, or a dated review if no
such probe exists (then say so)". §B.14 records that the polarity is the other way round in the
mechanism. Saying so, for each pin:

- **`ui-a11y-gate`** drives a real browser against a **served** frontend. An unattended
  `expected-state-probe.mjs --slow` run has no server, so the probe exits non-zero for lack of a
  listener, not for the pinned defect — and would keep doing so after R6's fix. Exit 0 is what
  retires a pin, so this probe could never fire in either state: an exit that cannot observe the
  pinned state, which is the defect `ui-web-envelopestream-heartbeat-flaky` records in its own
  `exitProbeRetired` note (pointing the other direction: false-GONE). A decoration, not an exit.
- **`WorkerMethvinWatcherTest`** is load-dependent and **passes in isolation** — which is the only
  way a probe could run it. It would report exit 0 = GONE against a fully live red. This is
  verbatim the false-GONE defect that retired the probes on
  `ui-web-envelopestream-heartbeat-flaky` and `ui-web-pluginloader-module-mode-timeout` on
  2026-09-02.

Both therefore exit by `reviewBy` **plus a named fix owner**, and the `exitProbeOmitted` field
records the reasoning at the pin so the next agent does not "helpfully" add a probe that reports
GONE. The field name mirrors the file's existing `exitProbeRetired` / `exitProbeNote` convention;
`checkPinShape` ignores unknown fields, so it is inert to the validator by design.

### C.4 — Wrong-gate check: does each new failure actually fire?

Not trusted from the symbol existing — each was driven to a real failure:

- `dead-code/whole-file-uncounted` — fired twice in the test suite (missing file; absent
  TypeScript), asserted on the reason substring, not just the ruleId.
- `checkCorruptionPolicyVocabulary` unknown-value branch — fired on `SILENT_EMPTY`.
- `checkCountRatchet` floor branch — fired at 1 row against a floor of 2.
- `checkCountRatchet` cap branches — both fired (register cap 80 vs pin 3; 4 entries vs pin 3).
- Both new pins — driven through `known-state-hint.mjs` with crafted stdin; both render.

### C.5 — Residue swept

`retire-with-a-sweep` applied to what item 1 supersedes: the `dead-code` gate's own inline comment
about report shapes was updated at `enforcer.mjs:12-14` to point at `export-count.mjs`; the legacy
`issues{category:{file}}` branch was normalized too rather than left as an asymmetric path that
still counts a whole-file finding as 1. 885's prose claim of "22 files" is superseded by the
measured 23 and recorded in §B.6 rather than silently corrected.

---

## §D — Verification, with results

All from `.claude/worktrees/resid2-gov`.

| Command | Result |
|---|---|
| `npm --prefix modules/ui-web run knip:report` | wrote `tmp/knip-report.json`, 186 issue rows, 23 whole-file |
| `node scripts/governance/run.mjs --gate dead-code --mode gate` | **pass, 0 findings** |
| `node scripts/governance/gates/dead-code/enforcer.test.mjs` | **all 8 checks passed** (new file) |
| `node scripts/ci/check-store-recoverability.mjs` | **OK — 6 catalog stores and 36 durable state authorities (floor 36), across 26 corruption policies** |
| `node scripts/ci/check-store-recoverability.test.mjs` | **OK — 60 assertions passed** (was 51) |
| `node scripts/agent-analytics/expected-state-probe.mjs --gate` | **19 pins; 0 shape/review problems; 0 exit-probes fired** |
| `node scripts/ci/check-tempdoc-numbers.mjs` | recorded in §D.3 |
| `node scripts/governance/run.mjs --mode gate` (full kernel) | recorded in §D.3 |

### D.1 — Item 1 falsifications (each break run, then reverted)

| # | Break | Observed |
|---|---|---|
| F1 | normalization removed (whole-file → 1) | 2 FAILED — `whole-file finding must normalize to the module export count: 1 !== 4`, and `a genuinely new dead export … still fails: 'pass' !== 'fail'` |
| F2 | floor of 1 dropped | 2 FAILED — the no-export module and the star barrel both drop out of the ratchet entirely (`Input: ''`) |
| F3 | bare `export *` followed transitively | 1 FAILED — `bare star re-exports introduce no binding knip attributes to this module: 2 !== 0` |
| F5 | fail-closed replaced by silent fallback | 2 FAILED — both fail-closed tests, `'pass' !== 'fail'` |

Plus the end-to-end counterfactual (F4-equivalent, run against the real tree rather than fixtures):
with one symbol of `browse.ts` imported, `origin/main`'s enforcer + baseline report
`silent-growth | src/api/domains/browse.ts: 1 → 2`, while the fixed gate reports
`rebalance-available | src/api/domains/browse.ts: 2 < pinned 4` — and still catches the probe's own
new export as `silent-growth | src/api/http.ts: 4 → 5`. `modules/ui-web/src/api/http.ts` was
restored byte-for-byte afterwards (`git status --porcelain modules/ui-web` clean).

### D.2 — Item 2 falsifications

| # | Break | Observed |
|---|---|---|
| F1 | unknown-value branch disabled | 1 FAILED — `a corruptionPolicy outside the closed vocabulary fails` |
| F2 | row-floor branch disabled | 1 FAILED — `losing a durableStores row fails against the pinned floor` |
| F3 | both cap branches disabled | 2 FAILED — `pending entries over the pinned ceiling` and `raising the register-side cap without raising the external pin` |
| F4 | floor turned into an equality | 1 FAILED — `gaining a durableStores row is allowed`. **This is the merge-safety guard against lane R4 firing.** |
| F5 | unused-vocabulary branch disabled | 1 FAILED — `a declared policy no row uses fails` |

### D.3 — Full kernel

`node scripts/governance/run.mjs --mode gate` after building the node-side inputs
(`generate-runtime-config-matrix`, `module-deps`, `report-npm-audit`, `knip:report`):

```
governance: 35 gates evaluated, 3 fail, 84 findings
```

`dead-code: pass`. The three fails are all pre-existing and all now pinned:

| gate | finding | pin |
|---|---|---|
| `ts-any` | 5 × `ts-any/silent-growth`, all the English word "any" in prose | `ts-any-gate-counts-english-prose` (existing) |
| `dead-code-jvm` | `kernel/input-missing: tmp/dead-code-jvm-report.json` — its producer is a Gradle run, out of this lane's node-only scope | `governance-kernel-inputs-unbuilt` (existing) |
| `operation-surface` | `operation-surface/undeclared-surface` on `modules/ui-web/src/shell-v0/state/indexingJobStates.ts` | **`operation-surface-indexingjobstates-unregistered` (new, this PR — see below)** |

`wire` reports `contract-governance/buf-cli-missing` but its verdict stays pass (pinned as
`wire-gate-buf-cli-missing`, which warns about exactly that fail-open); no `.proto` is touched here.

**The unpinned one, routed at discovery rather than logged.** `operation-surface` was red on `main`
with no pin. Per `log-pre-existing-issues` a red local verification gets a dated pin **plus** a
named fix owner, so a third pin was added: `operation-surface-indexingjobstates-unregistered`. It is
pre-existing, not branch-induced — `git log -1 --oneline origin/main --
modules/ui-web/src/shell-v0/state/indexingJobStates.ts` returns `bff70561` (#603), and this branch's
`git diff origin/main --name-only` touches zero files under `modules/ui-web` or
`governance/operation-surfaces.v1.json`. Unlike the other two new pins this one **does** carry a real
`exitProbe` (`node scripts/governance/run.mjs --gate operation-surface --mode gate`): it exits 1
today and will exit 0 the moment the register row lands, which is the polarity §B.14 describes. Its
`match` was checked in both directions — it fires on a bare `run.mjs --mode gate` and on
`--gate operation-surface`, and does **not** fire on `--gate wire`.

Post-pin: `node scripts/agent-analytics/expected-state-probe.mjs --gate` → **20 pins; 0
shape/review problems; 2 exit-probes run, 0 fired**.

### D.4 — Baseline row changes (before → after), all 11

| path | before | after |
|---|---|---|
| `src/shell-v0/aggregate-substrate/index.ts` | 1 | 29 |
| `src/api/domains/indexing.ts` | 1 | 13 |
| `src/api/generated/shape-handlers/index.ts` | 1 | 9 |
| `src/api/domains/inference.ts` | 1 | 7 |
| `src/api/domains/settings.ts` | 1 | 5 |
| `src/api/domains/browse.ts` | 1 | 4 |
| `src/api/wireContractVersion.ts` | 1 | 3 |
| `src/spike/NativePopoverSpike.ts` | 1 | 3 |
| `src/api/domains/status.ts` | 1 | 2 |
| `src/shell-v0/commands/coreSelectionActions.ts` | 1 | 2 |
| `src/shell-v0/router/tauriBridge.ts` | 1 | 2 |

The other 12 whole-file rows stay at 1: `scripts/capture-evidence-bundle.mjs`, `src/api/index.ts`,
`src/api/domains/index.ts`, `src/api/domains/suggest.ts`,
`src/shell-v0/controllers/retainedScroll.ts`, `src/shell-v0/components/ResolutionStats.ts`,
`src/shell-v0/components/SelectionActionsMenu.ts`, and the five
`src/api/generated/shape-handlers/core-*.ts` handlers. Row count unchanged: 186.

---

## §E — Open items

1. **`src/api/domains/indexing.ts` is still entirely dead** (885:3609-3618). This lane fixed the
   *measurement*, not the module: 13 exported symbols with zero importers. The honest fix is
   deleting it or reaching into it — owned by whoever owns the indexing HTTP client, not by the
   kernel. Its pin is now 13 rather than 1, so the size of the hole is at least legible.
2. **`--rebalance` still cannot delete a stale row.** It iterates the *measured* counts, so a file
   with zero findings is never visited and its row survives forever. 884's changeset deleted 5 such
   rows by hand and named the limitation; this lane did not close it, and none of the 11 rows it
   touched are of that shape.
3. **The vocabulary's meanings are prose, not enforced semantics.** The gate checks that a value is
   *declared* with a *non-empty* meaning; it cannot check that a row's `corruptionPolicy` matches
   what its code does. `corruptionNote` + the row's named tests remain the only evidence for that.
4. **`pendingDurableClassification` is at cap (8/8).** Any new pending entry needs a paired bump in
   two files now. That is the intent, but it means the next lane discovering a durable write site
   with no row will hit this gate before it hits anything else.
5. **The three new pins are dated exceptions, not steady state.** They are deleted by lanes R6 and
   R7 and by the operation-surfaces register row respectively; the `reviewBy: 2026-09-30` backstop
   turns CI red for every PR if none lands.
6. **CLAUDE.md's pre-merge table still names only `StoreCatalog.java` · store construction sites as
   the trigger for `check-store-recoverability`.** Editing `governance/store-corruption-policies.v1.json`
   is now a third trigger and is not listed. Left unedited deliberately: this lane's brief scoped
   CLAUDE.md out, and the file is under the always-loaded-byte ratchet, so the row belongs to
   whoever next opens that table (or to a `consult-register` recipe, which is the cheaper home).
