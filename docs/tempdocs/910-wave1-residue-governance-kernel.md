---
title: "Wave-1 residue, governance kernel: the dead-code gate's whole-file masking trap, a closed corruptionPolicy vocabulary with a count ratchet, and two pinned pre-existing reds"
type: tempdocs
status: "IMPLEMENTED + REVIEW-ROUND-1 APPLIED (2026-09-02) — all three items landed; independent review returned NEEDS-FIXES (no S1) and every finding is answered in §F, including one measurement of mine that was false and one review claim that did not hold"
created: 2026-09-02
updated: 2026-09-02
lane: wave-1 residue R5 (governance kernel)
model: opus (implementation)
parent: 884-decision-review-lane-b-governance-loop
coordination: "→ lane R4 (worktree resid2-stores) owns `governance/store-recoverability.v1.json` ROWS and may coin a new corruptionPolicy value; item 2 is built so R4's merge cannot break this lane — the row pin is a FLOOR (growth allowed) and a coined value fails with the remedy naming the file to extend, never silently. → lane R6 (worktree resid2-ui) owns the ui-a11y-gate settings-dialog capture fix that retires pin `ui-a11y-gate-settings-dialog-capture-timeout`. → lane R7's watcher fix MERGED as #612/`33ffc3bb` while this PR was in review, so its pin `worker-methvin-watcher-create-event-size-race` was deleted here before ever reaching `main`. Each pin names its fix owner inline and is deleted by whichever PR notices the red is gone."
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

### Item 3 — two pins, of which ONE ships

Added to `scripts/agent-analytics/expected-state.v1.json`, which ends this PR at **18 pins**:

- `ui-a11y-gate-settings-dialog-capture-timeout` — matches `ui-a11y-gate`. Claim leads with the
  discriminator ("exit 2 means two steps could not be CAPTURED, not that accessibility regressed").
  Fix owner: lane R6, **not yet merged**, so the pin ships.
- ~~`worker-methvin-watcher-create-event-size-race`~~ — **written, then deleted before merge: its
  fix landed first.** Lane R7's PR #612 merged to `main` as `33ffc3bb` ("watcher records a
  mid-write file's size as unknown, not zero"), which is exactly the pinned defect. Verified on
  `origin/main` rather than taken from the merge title: the assertion the pin quoted (`The watcher
  must record the file's real size, not the unknown-size sentinel ==> expected: <4096> but was:
  <0>`) is **gone** from `deliversCreateEventToJobQueue` — the create-event case no longer asserts a
  real size at all, so the race it named cannot recur.

**This is the pin lifecycle working, not a wasted pin.** `expected-state.v1.json`'s own contract
says a pin is a dated exception and "a pin whose red is gone is a lie; delete it in the fixing PR".
The fixing PR was someone else's, so this one deletes it instead — which is the same rule, applied
by whoever notices first. The pin carried a `fixOwner` naming lane R7 precisely so that this
deletion needed no investigation.

The surviving pin carries `reviewBy: 2026-09-30`, a `fixOwner`, and an `exitProbeOmitted` field
saying why no automated probe exists — see §C.3.

The full-kernel run also turned up a THIRD red on `main` that nothing pinned — `operation-surface`
on `modules/ui-web/src/shell-v0/state/indexingJobStates.ts`. It was briefly pinned, and the pin was
**withdrawn on review**: the red was one register row away, and a pin is the second-best remedy
whenever the fix is in reach. See §F.3.

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
| `node scripts/governance/run.mjs --gate dead-code --mode gate` | **pass** — 11 `declared-growth` notes, `'unit-renormalization' covers` |
| `node scripts/governance/gates/dead-code/enforcer.test.mjs` | **all 15 checks passed** (new file) |
| `node scripts/governance/run-all-tests.mjs` | **all 23 test files passed** |
| `node scripts/governance/run.mjs --gate operation-surface --mode gate` | **pass, 0 findings** (was the third red; fixed, not pinned) |
| `node scripts/governance/run.mjs --gate register-guard-resolution --mode gate` | **pass, 0 findings** (second reader of the register I edited) |
| `node scripts/ci/check-store-recoverability.mjs` | **OK — 6 catalog stores and 36 durable state authorities (floor 36), across 27 corruption policies** |
| `node scripts/ci/check-store-recoverability.test.mjs` | **OK — 64 assertions passed** (was 51) |
| `node scripts/agent-analytics/expected-state-probe.mjs --gate` | **18 pins; 0 shape/review problems; 0 exit-probes fired** (19 before R7's fix landed) |
| `node scripts/ci/check-tempdoc-numbers.mjs` | recorded in §D.3 |
| `node scripts/governance/run.mjs --mode gate` (full kernel) | **35 gates, 2 fail, 94 findings** — both pinned pre-existing; see §D.3 |

### D.1 — Item 1 falsifications (each break run, then reverted)

| # | Break | Observed |
|---|---|---|
| F1 | normalization removed (whole-file → 1) | 2 FAILED — `whole-file finding must normalize to the module export count: 1 !== 4`, and `a genuinely new dead export … still fails: 'pass' !== 'fail'` |
| F2 | floor of 1 dropped | 2 FAILED — the no-export module and the star barrel both drop out of the ratchet entirely (`Input: ''`) |
| F3 | bare `export *` followed transitively | 1 FAILED — `bare star re-exports introduce no binding knip attributes to this module: 2 !== 0` |
| F5 | fail-closed replaced by silent fallback | 2 FAILED — both fail-closed tests, `'pass' !== 'fail'` |
| F6 | member counting reverted (the pre-review counter) | 3 FAILED — `ScratchEnum + 3 members + scratchOne + scratchTwo: 3 !== 6`, the namespace case, and `T + I + E + E.X: 3 !== 4` |
| F-A | baseline-shift block removed | 2 FAILED — `raising a pinned number without a changeset fails: 'pass' !== 'fail'`, and the covering case finds no message |
| F-B | `unit-renormalization` added to `GROWTH_COVERING` (the two sets collapsed) | 1 FAILED — `unit-renormalization covers the PIN but NOT the live count: 'pass' !== 'fail'` |
| F-C | `unit-renormalization` dropped from `BASELINE_SHIFT_COVERING` | 1 FAILED — `a unit-renormalization changeset covers a raised pin: 'fail' !== 'pass'` |

Plus two end-to-end probes against the real tree rather than fixtures. **They are separate probes,
and the first version of this tempdoc wrongly reported them as one** — see §C.6.

- **E1, the trap.** Append `import { fetchFolders } from './domains/browse';` to
  `modules/ui-web/src/api/http.ts`, regenerate the report. `origin/main`'s enforcer + baseline
  report `silent-growth | src/api/domains/browse.ts: 1 → 2`; the fixed gate reports
  `rebalance-available | src/api/domains/browse.ts: 2 < pinned 4` **and nothing else** — the import
  alone produces no growth finding anywhere, `http.ts` stays at 4.
- **E2, real growth still caught.** Append `export const scratchDeadExport = 1;` to
  `src/api/domains/status.ts` (a whole-file-unused module pinned at 2), regenerate, run the fixed
  gate:

  ```
  error dead-code/silent-growth | src/api/domains/status.ts: 2 → 3 unused exports without declared changeset
  ```

Both probe files were restored byte-for-byte afterwards (`git status --porcelain modules/ui-web`
clean).

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
governance: 35 gates evaluated, 2 fail, 94 findings
```

`dead-code: pass`, `operation-surface: pass`. The first version of this PR reported **3 fail**; the
third was fixed rather than pinned on review (§F.3). Both remaining fails are pre-existing and
already pinned:

| gate | finding | disposition |
|---|---|---|
| `ts-any` | 5 × `ts-any/silent-growth`, all the English word "any" in prose | `ts-any-gate-counts-english-prose` (existing pin) |
| `dead-code-jvm` | `kernel/input-missing: tmp/dead-code-jvm-report.json` — its producer is a Gradle run, out of this lane's node-only scope | `governance-kernel-inputs-unbuilt` (existing pin) |
| `operation-surface` | `operation-surface/undeclared-surface` on `modules/ui-web/src/shell-v0/state/indexingJobStates.ts` | **FIXED, not pinned** — one register row (§F.3) |

`wire` reports `contract-governance/buf-cli-missing` but its verdict stays pass (pinned as
`wire-gate-buf-cli-missing`, which warns about exactly that fail-open); no `.proto` is touched here.

**The unpinned one, fixed rather than pinned.** `operation-surface` was red on `main` with no pin.
It is pre-existing, not branch-induced — `git log -1 --oneline origin/main --
modules/ui-web/src/shell-v0/state/indexingJobStates.ts` returns `bff70561` (#603). The first version
of this PR pinned it; review corrected that to fixing it, because the red was one register row away
(§F.3). Final pin count: **19**.

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

## §F — Independent review round (2026-09-02, PR #611 → NEEDS-FIXES, no S1)

### F.1 (S2-1) — a false measurement I had recorded in three durable places

**The finding.** All three write-ups claimed the browse.ts import probe "still catches the probe's
own new export as `silent-growth | src/api/http.ts: 4 → 5`". The reviewer ran the probe as written
and got no such finding. **They are right, and re-running it confirms it:** with only
`import { fetchFolders } from './domains/browse';` appended, `http.ts` stays at 4 with no finding —
only the `browse.ts: 2 < pinned 4` half reproduces.

**Cause, which is the part worth recording.** My original probe appended TWO lines — the import
*and* `export const __probeA = fetchFolders;`. The `4 → 5` finding was real, but it was caused by
the second line, which I then omitted from every write-up. So the probe as documented could not
produce the result as documented. This is `interrogate-results` failing in its most seductive form:
the run produced exactly the two findings my thesis predicted, one for each half, so nothing
prompted me to ask which line caused which.

**Corrected in all three places** — `gates/dead-code/.changesets/910-whole-file-normalization.md`,
§D.1 above, and the PR body — and split into two explicitly separate probes, E1 (the trap) and E2
(real growth still caught), with E2 being the `status.ts` case the reviewer supplied.

### F.2 (S2-2) — the upper-bound invariant was false for enum members

**Measured, not conceded on argument.** A scratch module
`export enum ScratchEnum {A,B,C}` + two consts:

| state | knip | counter |
|---|---|---|
| whole-file unused | `files: [self]` = 1 | 3 (old) |
| `ScratchEnum.A` consumed | `enumMembers: [B,C]` + `exports: [scratchOne, scratchTwo]` = **4** | 3 (old) → **silent-growth** |

**And `enumMembers` is in the DEFAULT report** — the default array keys measured on this repo's own
config are `dependencies, devDependencies, enumMembers, exports, files, types, unlisted`. So this
was a live break, not a latent one. (My first probe missed it because I used the enum as a *type
alias*, which reports it under `types` instead; it takes a *value* reference to surface
`enumMembers`.)

**Fixed** in `countReportableMembers` (`export-count.mjs`): exported enum members and exported
namespace members now count.

**Where I did NOT follow the reviewer, with the measurement for why.** They also asked for
`classMembers`. Counting class members moved four real baseline rows —
`SelectionActionsMenu.ts 1 → 19` (ONE exported Lit class, 18 methods), `retainedScroll.ts 1 → 7`,
`ResolutionStats.ts 1 → 7`, `NativePopoverSpike.ts 3 → 5` — and `classMembers` is **absent from the
default report**. That is a 19× standing allowance bought for a category knip does not emit here:
the same over-permissiveness this very module rejects for transitive star barrels, which would have
made the PR contradict itself. So `classMembers` is documented as an unbounded category alongside
`duplicates`, with an explicit trigger — if `knip.config.ts` ever sets `include: ['classMembers']`,
the counter must count them and the affected rows re-pinned in that PR.

**Falsification.** Reverting to the old counter: `3 !== 6` on the enum case, plus 2 more.

### F.3 (S2-3) — pin withdrawn, register row added

The reviewer was right that a pin is the second-best remedy when the fix is one row away, and that
this one is in my lane (I own `governance/`). Added
`fe-indexing-job-state-vocabulary` to `governance/operation-surfaces.v1.json` and **deleted** the
pin `operation-surface-indexingjobstates-unregistered`.

Two things checked rather than assumed before writing the row:

- **A row covers exactly one path.** `enforcer.mjs:57` builds `declared` as
  `new Set(surfaces.map((s) => norm(s.path)))`, and `norm` is `String(p).replace(/\\/g,'/')` — an
  array path would stringify to `"a.ts,b.ts"` and match nothing. So folding the file into the
  sibling `indexingJobsBridge.ts` row would NOT have worked; a standalone row is the only shape
  that does.
- **A second gate reads this file.** `register-guard-resolution` lists it in `config.registers[]`
  and forbids bare `self` / `none-yet` / absent guards, so the row's `guard: "gate:operation-surface"`
  had to be a resolving form. Both gates were run: `operation-surface: pass`,
  `register-guard-resolution: pass`.

Worth recording about the detection itself: `indexingJobStates.ts` never references `IndexingJobView`
in *code* — `scanTs` is a whole-file text regex, and the match is the phrase `IndexingJobView.STATE_*`
in the file's header comment. The row is still correct (the file genuinely is the shell-side spelling
of that vocabulary), but the gate found it by prose, which is worth knowing before trusting its
population count as an import graph.

### F.4 (S2-4) — one vocabulary meaning contradicted its code; the rest re-audited

`DELETE_AND_RECOMPUTE`'s meaning claimed the file is "deleted outright and recomputed" with "no user
content". Verified at source, both halves are wrong: `MigrationProgressStore.readBestEffort`
swallows the parse failure at debug and returns `null` (`:55-58`); the write path replaces via
`ATOMIC_MOVE` (`:91-99`); `grep -n "delete"` over the file returns **zero hits**; and `last_path` —
one user document path in the clear — is written at `:89`.

Reworded to what the code does, and the name/behaviour mismatch is left *visible* in the meaning
rather than smoothed over, because renaming the value is a register-row edit owned by the row's
owner, not by this vocabulary file.

The re-audit of the other 25 meanings is recorded in §F.8.

### F.5 (structural gap) — `dead-code` had no `silent-baseline-shift`

Confirmed: `dead-code/enforcer.mjs` never read a prior baseline, so **raising a pinned number by
hand passed as `rebalance-available`** — the ratchet held the measurement to the pin but not the pin
to its own history. Mirrored `todo-fixme` (the closest sibling: same `<path> <count> <date>` shape,
same per-file dynamic key set, same changeset loader), using `readFileAtRef(ref, filePath, cwd)` in
real mode and `_baseline/` in fixture mode.

**The design point the reviewer's "otherwise `unit-renormalization` is documentation-only" names.**
The two covering sets are deliberately different:

| set | covers | members |
|---|---|---|
| `GROWTH_COVERING` | live count exceeds its pin | `declared-growth`, `merge-import`, `emergency-override` |
| `BASELINE_SHIFT_COVERING` | the pin itself was raised | those three **+ `unit-renormalization`** |

A counting change may move the pin; it may not licence the measurement to exceed it. Collapsing
these into one set is falsification F-B below, and it fails.

On the real repo the rule now *sees* this PR's own work: 11 × `dead-code/declared-growth |
… baseline raised 1 → N; 'unit-renormalization' covers`, gate green.

### F.6 (nits)

| nit | disposition |
|---|---|
| `expected-state.v1.json` evidence said 6 files, diff is 11 | moot — that evidence line lived in the pin deleted in §F.3 |
| PR body cited a nonexistent `--gate` flag on `expected-state-probe` | **Checked and NOT upheld — the flag exists.** `scripts/agent-analytics/expected-state-probe.mjs:69` reads `if (args.includes('--gate') && (shape.length \|\| probes.exitFired.length)) process.exit(1);`, and the file's own usage header documents it at `:14` (`--gate  # non-zero on a stale pin`). The register's `comment` field also names `--gate` as the CI form. The PR body's citation was correct and is unchanged. Recorded rather than silently "fixed", because quietly editing a correct line to match a review note would leave the next reader with a worse command than the one that works. |
| `enforcer.mjs` legacy-shape branch untested, would fail closed on keys `"0"`, `"1"` | **deleted**, not tested. It was speculative back-compat for a knip version this repo does not use (`package.json:71` pins `^6.20.0`), and normalization had made it actively harmful: `Object.entries` over a legacy `issues.files` ARRAY yields indices as paths. The top-level `files[]` tolerance branch went with it — same "untested guess at a shape" class. An unrecognised shape is now `dead-code/report-malformed`, with a test. |
| vocabulary file threw a raw `JSON.parse` stack | `loadPolicies()` now fails with a remedy for both the unreadable and the malformed case, with a test that captures `console.error` and asserts the remedy text |

**One consequence of the strict-shape change, found by the suite rather than by me.**
`scripts/governance/run.input-contract.test.mjs` uses the dead-code enforcer as a vehicle for two
producer-plumbing tests, with `{}` as a throwaway payload. Under the new contract `{}` is
`report-malformed`, so both went red. I changed the FIXTURE, not the rule: a report with no
`issues` array silently reading as "zero dead code" is precisely the fail-open this gate must not
have — if a producer breaks and emits `{}`, the gate should scream, not come back green. The
producers now emit `{"issues":[]}`, a valid empty report, so those tests assert what their names
say and the assertion is now stronger than it was.

### F.7 — Cross-lane coordination (lane R4, PR #613)

R4 coined `REGENERATE_FROM_RUN_EVENTS_OR_PRESERVE`. Adding it exposed a real conflict with my own
"a declared value no row uses is a failure" check: pre-declaring R4's value would have failed my
gate on my own branch.

Resolved with an `awaitingRow` map — value → the PR whose row will use it — that is **self-retiring**:
a value in `awaitingRow` that a row now uses is itself a failure telling you to delete the marker.
So the forward declaration cannot outlive its reason, which is the same discipline
`retire-with-a-sweep` asks of everything else here.

**Confirmed for R4:** `ratchet.durableStoreRows: 36` is a FLOOR — `checkCountRatchet` fails only on
`rows < durableStoreRows`. R4's 42 rows pass unchanged, and the test
`gaining a durableStores row is allowed — the pin is a floor, not an equality` exists precisely to
keep a later agent from "tightening" it into an equality (falsification F4 in §D.2).

### F.9 — The three routed gate defects, fixed here; and a census

Round 1 routed these instead of fixing them. That was the pile, and they are fixed in this PR.

**Census — every gate declaring a `silent-baseline-shift` rule, and whether it could reach it**
(`grep -rl silent-baseline-shift scripts/governance/gates/`, then the `readFileAtRef` call sites):

| gate | rule declared | could it fire? | disposition |
|---|---|---|---|
| `config-surface` | yes | yes — 2 positional calls (`enforcer.mjs:233,:262`) | routed through the shared reader |
| `npm-audit` | yes | yes — `enforcer.mjs:249` | routed through the shared reader |
| `todo-fixme` | yes | yes — `enforcer.mjs:135` | routed through the shared reader |
| `dead-code` | **no rule at all** (round 1) | n/a | rule added in round 1; now on the shared reader |
| **`module-deps`** | yes (`enforcer.mjs:28`) | **NO** — `readFileAtRef` imported at `:16`, **never called** | **fixed (a)**: `verdictForBaselineShift` added to its truth-table, block added to the enforcer, 5 tests |
| **`test-efficacy`** | yes | **NO in real runs** — `enforcer.mjs:253` passed an OPTIONS OBJECT to a POSITIONAL signature (`git-utils.mjs:236`), so `git show [object Object]:undefined` threw and the catch returned null | **fixed (b)**: positional call + 4 tests on the REAL git branch |

Two adjacent findings from the same sweep: `ts-any/enforcer.mjs:15` imported `readFileAtRef` and
never called it — removed as a ride-along (it declares no shift rule, so it was a dead import, not
an unreachable rule). `consumer-drift`, `prose-tier-register`, `ssot-catalog-sync`,
`register-guard-resolution` and `wire` call `readFileAtRef` correctly for non-baseline-shift
purposes and were left alone.

**(c) — I have to correct myself: `detectBaselineTamper` is NOT unused.** Round 1 routed it as "a
helper nobody calls beside six copies". That was wrong, and I should have grepped before reporting
it: `register-guard-resolution/enforcer.mjs:148` calls it, and `guard-resolver.mjs:80-83` documents
building its events. So the instruction's premise — delete it or adopt it — does not apply as
stated, and neither branch was right:

- **Deleting it** would break a live consumer.
- **Routing the six through it** is not the mechanical change it looks like. The helper expects the
  gate to supply BOTH the covered and uncovered variants and picks between them; each of the six
  gates instead has a `verdictForBaselineShift` in its own `truth-table.mjs` that already branches
  on `classification` and returns one verdict. Adopting it would mean either restructuring six
  registered truth-table artifacts, or a passthrough where both ruleIds are the one the truth-table
  already chose — decorative centralization that makes the invariant *look* shared while the real
  decision stays in six places. That is the failure mode this tempdoc is about.

**What the duplication actually was, and what I did instead.** The copied code was the prior-baseline
*read* — a multi-line fixtureMode/`_baseline` vs `readFileAtRef` branch with a git call inside it.
That is the shape that rots when copied, and it is exactly where defect (b) lived. It is now
`scripts/governance/lib/prior-baseline.mjs` (`readPriorBaselineText`), used by all six gates, with
its own test on the real git branch. One call site of `readFileAtRef` to get wrong instead of six.
`detectBaselineTamper` keeps its one legitimate consumer; the two helpers own different halves of
the invariant, and the file header of each now says which.

### F.8 — Re-audit of the remaining 25 meanings, and what "audited" means here

The reviewer asked for a re-audit for the same drift class, and for an honest record of which
meanings I actually re-read against code. Two tiers, kept separate on purpose:

**Tier 1 — verified against CODE (full read of the handling path).**

| value | verdict | evidence |
|---|---|---|
| `DELETE_AND_RECOMPUTE` | **DRIFT, fixed** | `MigrationProgressStore.java` — `readBestEffort` swallows at debug and returns null (`:55-58`); write replaces via `ATOMIC_MOVE` (`:91-99`); `grep -n "delete"` over the file = **0 hits**; `last_path` written at `:89` |

**Tier 2 — verified against the owning row's `encryptionNote`, i.e. the CONTENT claim only.** This is
the check that caught the first defect, so it is the one worth running across the board; it does not
verify the verb. Seven values whose meanings made an absolute content or disposability claim:

| value | row | verdict |
|---|---|---|
| `ROTATE_OR_PRUNE_DIAGNOSTIC_ARTIFACT` | telemetry-and-crash-artifacts | **DRIFT, fixed** — my meaning said "diagnostics are disposable by construction"; the row records that spans carry `doc.path` and `http.target`, so indexed document paths and query strings land in `telemetry/*.ndjson`, and `crashes/*.json` keeps raw exception messages and stack traces |
| `SKIP_DERIVED_ROW` | gpl-training-triples | **DRIFT (mild), fixed** — regenerable is right, but the rows are user-derived: each carries a synthetic query generated from one of the user's documents plus its id. Meaning now says so. |
| `RECREATE_DERIVED_METRIC` | telemetry-rrd | OK — "no free text, no paths, no user content; high-cardinality streams that could carry a document path go elsewhere" |
| `REGENERATE_OR_DROP_DERIVED_HISTORY` | inference-runtime-projections | OK — closed vocabulary of lifecycle phases/reason codes, "no prompt text, no completion text" |
| `CLASSIFY_UNCLEAN_AND_REGENERATE` | runtime-manifest-history | OK — process bookkeeping, "no user content" |
| `DISCARD_UNREADABLE_DERIVED_STATUS_AND_RESCAN` | runtime-activation-status, pack-import-status | OK — both notes say "no user content" |
| `CONSERVATIVE_REBUILD_OR_CLEAR` | worker-protocol-markers | OK — signal files carrying one reason code, "no message text, no path" |

**Tier 1 (round 2) — all remaining values, verified against the handling path.** No delegation; the
subagent attempt in round 1 never returned, so this was done inline. 18 values, not 17 — I
miscounted in round 1.

| value | row(s) | verdict | evidence |
|---|---|---|---|
| `FAIL_LOUD` | ai-install-contract, encryption-keystore, **user-policy** | **DRIFT, fixed** | The first two throw (`InstallContractIO.java:41,:53`; `EncryptionKeystore.java:36-46`). **`user-policy` does not**: `EnterprisePolicyServiceImpl.java:287-290` catches, warns, and returns `PolicyLoad(present, loaded=false, message)` — the field is `loaded`, not `valid` (`:265`); `:283-285` same for an unsupported schemaVersion. Downstream splits: an unloadable **machine** policy fails CLOSED (`:73-86`), while the effective policy otherwise starts permissive (`:66-69`) and only narrows (`:100-106`). Meaning now says all of this. |
| `FAIL_LOUD_ON_UNREADABLE_OR_FUTURE_LINE` | feedback-records | **DRIFT (scope), fixed** | The corruption verb is right — `NdjsonAppendStore.java:100-118` throws per line. But blank lines are skipped (`:100-102`) and a **sealed-but-locked** store returns an EMPTY list (`:95-97`), so "you always see every record" was wider than the code. Both caveats now stated. |
| `FAIL_LOUD_AND_PRESERVE` | 8 rows (durable-grants, plugin-allowlist, installed-packs, memories, conversations, agent-runs, run-events, watched-roots) | OK | All 8 throw `CorruptDurableStoreException` (2-7 sites each) and **none delete on the corruption path**. The single `Files.deleteIfExists` in `FileConversationStore.java:731-734` is a user-initiated session-directory delete (a `Files.walk` teardown), not recovery. |
| `ACTIVE_AND_FUTURE_LOGS_FAIL_LOUD` | file-operation-journal | OK | `FileOperationLog.java:148,:223` throw `CorruptDurableStoreException`; `:237` throws `UnsupportedStoreVersionException`; `:145-146,:189-190,:201-202,:220-221` rethrow rather than swallow. |
| `SKIP_UNREADABLE_LINE_AND_WARN` | action-ledger-audit-journal | **DRIFT (scope), fixed** | The parse-failure path warns (`ActionEventJournal.java:268-271`), but a line that PARSES and is structurally invalid — missing `id`/`kind`, unparseable `occurredAt` — is dropped **silently** by `ActionLedgerProjection.fromWireRow` returning `Optional.empty` (`:264-266`, `:272-274`). And the skip is per-LINE only: `Files.readAllLines` sits outside the try at `:254`, so an IO failure aborts that generation and the remaining ones. Meaning now carries both qualifications. |
| `PRESERVE_AND_RECOVER_DEFAULTS` | ui-settings | OK | `UiSettingsStore.java:109` calls `quarantineCorruptFile` (`:194`), which moves to `settings.json.corrupt-<UTC>` at `:198` (non-atomic fallback `:200`) and rethrows the original with the IO failure suppressed into it at `:202-204` — so it never defaults *without* preserving. |
| `FAIL_CLOSED_DISABLED_WRITE_LOCKED` | feedback-capture-preference | OK | `FeedbackCaptureSettings.java:114-116` catches corrupt/future state, returns `false` (capture off) and sets `writeLocked`; `:59-60` makes a later write throw. Fail-closed *to disabled*, which is what the meaning says. |
| `FAIL_CLOSED_AND_RETRY_PREPARATION` | head-shutdown-receipt | OK | `HeadShutdownCoordinator.java:88-89` — "Missing receipt is a fail-closed signal to the shell"; the receipt is nonce-bound (`:16-20`) so a stale one cannot satisfy a new preparation. |
| `FAIL_CLOSED` | shell-release-sequence | OK | `updater.rs:435,:438` return `Err` on a descriptor/hash mismatch or a non-NSIS artifact; `:315,:449` refuse to proceed. Nothing is written. |
| `FAIL_CLOSED_REPAIR_REQUIRED` | shell-upgrade-intent | OK | `updater.rs:242,:260,:298` set state `repair_required`; `:512,:542,:549` call `mark_repair_required` and return the error, so it stays stopped until an explicit repair. |
| `RECOVER_PREVIOUS_OR_REBUILD` | index-generations | OK (**evidence corrected**) | The corruption path is `loadStateBestEffort():642-659`: `state.json` unreadable → fall back to `state.json.prev`, `log.warn` at `:650`, and `writeState(prev)` at `:652` overwrites the corrupt file. My round-2 citation was wrong on two counts — `rollbackToPreviousGeneration():366` is an **operator control**, not the corruption path, and the `:323`/`:362` "does not delete any generations" comments are **method-scoped**, not class-wide: the class *does* delete generation directories via `pruneMarkedForDeletionBestEffort():754` → `FileOps.deleteRecursivelyBestEffort():782` and `gcBestEffort():799,:845`, guarded by `protectedGenerationIds():876` and reachable only from the operator gRPC `IndexGc` (`MigrationControlOps.java:270-286`). The meaning never claimed "never deletes", so it stands as written. |
| `REFUSE_FUTURE_SCHEMA_AND_PRESERVE_OVERRIDES` | entity-clusters | OK | `EntityClusterStore.java:70-75` throws when `PRAGMA user_version` exceeds `CURRENT_SCHEMA_VERSION`; `:79-89` runs schema creation (incl. `CREATE_OVERRIDES_TABLE`) in a transaction that rolls back on failure — no destructive migration. |
| `VERIFY_PROCESS_IDENTITY_OR_REPLACE_STALE_LOCK` | process-locks | OK | `AppInstanceLock.java:144-166` — `ProcessHandle.of(pid)`, dead-process **and** start-time-mismatch checks before `log.warn("Recovering stale app lock…")` and deleting; `:71-72` documents the retry-once. |
| `FAIL_OR_REBUILD_DERIVED_QUEUE` | jobs-db | OK | `SqliteJobQueue.java:259` runs an integrity check on existing databases that "throws SQLException on corruption"; the queue is derived, so the rebuild path is a re-enqueue rather than a repair. |
| `SKIP_INVALID_PLUGIN_AND_PRESERVE_BYTES` | user-plugin-payloads | OK | `lib.rs:1194-1224` — the scan skips an oversized/invalid payload with a flag and reserves `Err` for real I/O failure; nothing removes the file. |
| `NEVER_DELETE_OR_OVERWRITE_UNKNOWN_ASSET` | byo-ai-assets | OK | The only delete on the install path is `AiInstallService.java:2655` `Files.deleteIfExists(partial)` — a partial download, never a complete or unrecognised asset. |
| `VERIFY_HASH_OR_PRESERVE_USER_ASSET` | managed-ai-assets | OK | Same delete-site audit as above plus the `sha256` verification threaded through `AiInstallService.java:1767`. |
| `REGENERATE_BEFORE_CONSUMPTION` | worker-config-snapshot | OK | `WorkerSpawner.java:61-66,:119` — the snapshot is written by HeadlessApp at ordinal 450 and read by the Worker afterwards; it is rewritten every boot, so a stale copy is never consumed. |

**Score for the whole vocabulary: 6 of 26 meanings were wrong** — `DELETE_AND_RECOMPUTE`,
`ROTATE_OR_PRUNE_DIAGNOSTIC_ARTIFACT`, `SKIP_DERIVED_ROW` (round 1), `FAIL_LOUD` and
`FAIL_LOUD_ON_UNREADABLE_OR_FUTURE_LINE` (round 2), and `SKIP_UNREADABLE_LINE_AND_WARN` (round 3,
found by the reviewer). Every one was a claim written from a `corruptionNote` without opening the
code — which is the honest argument for §E item 3. One further round-3 correction was to my own
EVIDENCE rather than a meaning (`RECOVER_PREVIOUS_OR_REBUILD`, above): I cited an operator control
and two method-scoped comments as if they were the corruption path and a class-wide guarantee.

**The pattern in the two round-2 drifts is worth naming**: both were wrong by being *too strong*.
`FAIL_LOUD` averaged three rows that do not behave alike, and
`FAIL_LOUD_ON_UNREADABLE_OR_FUTURE_LINE` promised a guarantee wider than the code. A one-line
meaning invites exactly this: the shortest true sentence is often narrower than the shortest
sentence that sounds authoritative.

### F.10 — CI caught a key the local floor could not: a verification-floor gap of my own making

`known-state-hint.test.mjs` failed on PR #611 with
`entry ui-a11y-gate-settings-dialog-capture-timeout has unrecognised key exitProbeOmitted`.

**The test was right and my pins were the problem.** `ALLOWED_PIN_KEYS` exists because nothing reads
an unrecognised key, so a typo is invisible -- `exitProbe` misspelled silently downgrades a pin's
exit discipline to `reviewBy` alone. I invented TWO keys in round 1 (`exitProbeOmitted`, `fixOwner`),
argued for them at length in this tempdoc, and never registered either. Both are now in the set with
a one-line meaning, and each was falsified separately: dropping `fixOwner` reproduces
`has unrecognised key fixOwner`, dropping `exitProbeOmitted` reproduces its own.

**Why the local floor missed it, which is the part worth keeping.** My §D floor ran
`scripts/governance/run-all-tests.mjs` -- and `scripts/agent-analytics/**` is a SEPARATE suite with
its own runner and its own CI step (`ci.yml:121-122`, `node scripts/agent-analytics/run-all-tests.mjs`,
64 files). I edited `scripts/agent-analytics/expected-state.v1.json` in round 1 and ran only the
gate that file has a *probe* for (`expected-state-probe.mjs --gate`, which checks shape and
`reviewBy` but not the key vocabulary), never the suite that owns its schema. The governance suite
being green read as "the tests pass" -- `subset-isnt-the-suite`, with the subset chosen by which
runner I happened to know about.

The generalizable rule: **the suite to run is the one that OWNS the file you edited, not the one
nearest the work you think you are doing.** `expected-state.v1.json` lives under
`scripts/agent-analytics/`, so it is that suite's contract regardless of the fact that my change was
about governance gates.

## §E — Open items

1. **`src/api/domains/indexing.ts` is still entirely dead** (885:3609-3618). This lane fixed the
   *measurement*, not the module: 13 exported symbols with zero importers. The honest fix is
   deleting it or reaching into it — owned by whoever owns the indexing HTTP client, not by the
   kernel. Its pin is now 13 rather than 1, so the size of the hole is at least legible.
2. **`--rebalance` still cannot delete a stale row.** It iterates the *measured* counts, so a file
   with zero findings is never visited and its row survives forever. 884's changeset deleted 5 such
   rows by hand and named the limitation; this lane did not close it, and none of the 11 rows it
   touched are of that shape.
3. **The vocabulary's meanings are prose, not enforced semantics — and §F.8 measured how much that
   costs.** The gate checks that a value is *declared* with a *non-empty* meaning; it cannot check
   that a meaning matches what the code does. **All 26 have now been read against their handling
   path, and 6 were wrong** (§F.8): `DELETE_AND_RECOMPUTE`, `ROTATE_OR_PRUNE_DIAGNOSTIC_ARTIFACT`,
   `SKIP_DERIVED_ROW`, `FAIL_LOUD`, `FAIL_LOUD_ON_UNREADABLE_OR_FUTURE_LINE`,
   `SKIP_UNREADABLE_LINE_AND_WARN` — a 23% error rate on prose that reads as authoritative, plus one
   row where my *evidence* was wrong while the meaning happened to be right. Nothing keeps them true
   from here: the next edit to any of those handling paths can silently re-drift the meaning.
   Closing it needs a per-row assertion tying the policy to the handling path (the row's named
   recovery test is the natural anchor) — a bigger change than this lane, and the one that would
   make this register self-defending rather than self-describing.
4. **`pendingDurableClassification` is at cap (8/8).** Any new pending entry needs a paired bump in
   two files now. That is the intent, but it means the next lane discovering a durable write site
   with no row will hit this gate before it hits anything else.
5. **The three new pins are dated exceptions, not steady state.** They are deleted by lanes R6 and
   R7 and by the operation-surfaces register row respectively; the `reviewBy: 2026-09-30` backstop
   turns CI red for every PR if none lands.
6. **The three gate defects routed in round 1 are FIXED in this PR, not routed** — see §F.9 for the
   census and for the correction of my own wrong claim about `detectBaselineTamper`.
7. **No pre-merge-table row maps `scripts/agent-analytics/expected-state.v1.json` to its suite.**
   The table routes an edited *subject* to the check that owns it, and this file has no row -- so a
   pin edit gives no signal to run `node scripts/agent-analytics/run-all-tests.mjs`, which is what
   §F.10 cost. Same shape and same owner as item 8 below; both are one table row, deliberately not
   taken here because this lane's brief scopes CLAUDE.md out and it is under the
   always-loaded-byte ratchet.
8. **CLAUDE.md's pre-merge table still names only `StoreCatalog.java` · store construction sites as
   the trigger for `check-store-recoverability`.** Editing `governance/store-corruption-policies.v1.json`
   is now a third trigger and is not listed. Left unedited deliberately: this lane's brief scoped
   CLAUDE.md out, and the file is under the always-loaded-byte ratchet, so the row belongs to
   whoever next opens that table (or to a `consult-register` recipe, which is the cheaper home).
9. **PR-scoped changeset discovery lets a declared growth go red on `main` one merge later
   (observed 2026-09-02).** `#614` declared `schema-types/index.ts 51 → 53` in a changeset but did
   not advance the pin in the same commit. Discovery is PR-scoped
   (`scripts/governance/lib/changeset-loader.mjs`, "PR-scope discovery"), so once #614 squash-merged
   the changeset was no longer in any diff: the next push to `main` (#613, `b6d0861e`) and #615's
   merge-group run both failed `dead-code/silent-growth` on a row nobody had changed. Repaired in #615
   by advancing the pin with a covering changeset (`915-schema-types-index-repin-after-614.md`), which
   is the remedy `discipline-gate-kernel.md` already documents ("advance the baseline in the same
   commit as the change"). Structural fix worth its own item: make a `declared-growth` changeset
   *require* the matching pin advance in the same diff (fail the PR that declares growth without
   re-pinning), so the trap cannot fire after merge. Owner: next kernel-facing lane.
