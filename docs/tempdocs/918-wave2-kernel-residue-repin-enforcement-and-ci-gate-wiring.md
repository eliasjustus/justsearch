---
title: "Wave-2 kernel residue — declared growth must re-pin in the same diff; wire the hermetic kernel gates into CI"
status: "IMPLEMENTED (2026-09-03) — both items landed with tests, a live bite test and a 20-gate census"
created: 2026-09-03
updated: 2026-09-03
supersedes: []
owner: governance-kernel lane
---

## §A — Scope

Two inherited open items from wave 1 of the decision-review program. Both are node-only; no Gradle,
no dev stack.

1. **A declared-growth changeset must re-pin in the same diff** (tempdoc 910 §E item 9, and the same
   defect one lane earlier in 883's Report-back). PR-scoped changeset discovery means a changeset
   that licenses a row's growth *without* advancing that row's pin goes invisible at squash-merge,
   and the next push to `main` fails `silent-growth` on a row nobody touched. Observed three times.
   Fix it structurally in the kernel, at a shared layer, with a new rule id, fixtures, a live bite
   test, and the doc paragraph promoted from advice to enforcement.
2. **20 registered kernel gates run in no workflow** (tempdoc 913 open item 1). Census each on the
   base, classify per gate, wire the ones that can be wired, and say precisely why each of the rest
   cannot be — with a pin where the reason is "red on main".

Out of scope by brief: root `CLAUDE.md` (byte-ratcheted; 910 items 7/8 deliberately left — though
item 7 turns out to be **already closed**, see §B.6).

## §B — Pre-implementation pass: every claim relied on, verified against source

Each row quotes the claim as written, then records the verification.

**B.1 — 910 §E.9, the defect statement.** Verbatim (`910-wave1-residue-governance-kernel.md:688-698`):

> "**PR-scoped changeset discovery lets a declared growth go red on `main` one merge later
> (observed 2026-09-02).** `#614` declared `schema-types/index.ts 51 → 53` in a changeset but did
> not advance the pin in the same commit. Discovery is PR-scoped
> (`scripts/governance/lib/changeset-loader.mjs`, "PR-scope discovery"), so once #614 squash-merged
> the changeset was no longer in any diff: the next push to `main` (#613, `b6d0861e`) and #615's
> merge-group run both failed `dead-code/silent-growth` on a row nobody had changed."

**VERIFIED.** `changeset-loader.mjs:56-70` is the PR-scope branch: when `baselineRef` is set and
`fixtureMode` is false, candidates come from `diffAddedModifiedFiles(baselineRef, changesetsDir,
repoRoot)` plus locally-added files — nothing else. `git-utils.mjs:265-280` implements that as
`git diff --diff-filter=AM --name-only <base>...HEAD -- <dir>`. A committed changeset present at the
base is therefore invisible, exactly as claimed. The repair changeset named in 910 exists on the base
at `gates/dead-code/.changesets/915-schema-types-index-repin-after-614.md`.

**B.2 — 883's Report-back, the same defect one gate over.** Verbatim
(`883-decision-review-lane-a-config-and-context-budget.md:1856-1861`):

> "**One process finding worth carrying past this lane.** The `config-surface` red (B1) is structural,
> not a one-off: any gate whose changeset discovery is diff-scoped against the baseline ref will go red
> on every subsequent PR the moment a declaring PR merges, until someone advances the baseline. It has
> now happened twice with the same gate (854 in #517, 885 in #595), each time discovered by a later
> lane rather than by the lane that caused it. The cheap fix is a rule — a PR that declares growth also
> advances the pin in the same commit — rather than a third rediscovery."

**VERIFIED, and the count is now three incidents across two gates** (883's two on `config-surface`
plus 910's on `dead-code`). The repair changesets for both `config-surface` incidents are on the
base: `gates/config-surface/.changesets/854-w1-advance-baseline-to-112-243.md` and
`885-advance-baseline-to-246.md`, each an after-the-fact pin advance.

**B.3 — "the remedy `discipline-gate-kernel.md` already documents".** 910 §E.9 cites the kernel doc
as already advising "advance the baseline in the same commit as the change".

**MOVED / PARTLY WRONG.** The doc did not advise that. The one hit for the phrase
(`docs/reference/contributing/discipline-gate-kernel.md:248`, pre-change) is the *opposite* framing —
it quotes editing the baseline in the same commit as the **attack** the baseline-shift rule closes:

> "This closes the silent escape-hatch class the tempdoc named: 'edit the
> baseline in the same commit as the change so the gate sees nothing wrong.'"

So the "already documented remedy" was a sentence about a different rule, read the other way round.
There was no paragraph to promote from advice to enforcement — §C.4 writes one. This matters:
had it been advice, the fix would be a wording change; it is not, so the fix has to be the rule.

**B.4 — 913 open item 1, the list of 20.** Verbatim
(`913-residue-2-live-validation-backend-fixes.md:614-622`):

> "The 20 still unwired: `consumer-drift`, `consumer-presence`, `contract-projection`,
> `contribution-surface`, `hook-integrity`, `host-owns-truth`, `interaction-surface`,
> `observed-happening`, `prose-tier-register`, `runtime-state`, `runtime-witness`,
> `ssot-catalog-sync`, `stage-completeness`, `surface-altitude`, `tempdoc-wiring`,
> `test-efficacy`, `test-to-code`, `todo-fixme`, `ts-any`, `wire` — each able to sit red on
> `main` indefinitely with nothing to notice. Wiring them needs a per-gate decision (`wire` is
> environment-sensitive, `ts-any` is pinned red)"

**VERIFIED.** All 20 ids resolve in `governance/registry.v1.json` (35 gates), and none of the 20
appears in `.github/workflows/*.yml` — directly or via `scripts/ci/run-ui-web-gates.mjs`, whose
`ui-web-gates` recipe names six *other* gates. 913's own method note about the indirection is
correct and was re-checked rather than trusted.

**B.5 — "no CI job runs `contracts/**` checks".** 913 does not claim this; the brief asks.
**ANSWERED: no such job exists.** `grep -rn "wire-contract\|--gate wire\|contracts/" .github/workflows/*.yml`
returns nothing across all eight workflows. The `wire` gate — protobuf breaking-change detection —
has no coverage anywhere, and neither does the `contracts/` tree.

**B.6 — 910 §E item 7 ("no pre-merge-table row maps `expected-state.v1.json` to its suite").**
**WRONG AS OF THE BASE — already closed.** The row exists on `origin/main` at `CLAUDE.md`
(Pre-merge table, last row): `` | `expected-state.v1.json` pins | `node scripts/agent-analytics/run-all-tests.mjs` | ``.
It landed in #615 (`a83de156`, "expected-state pre-merge row"). 910 was written before that merge.
Recorded because the brief scoped `CLAUDE.md` out on the strength of items 7/8 being open, and one
of the two is not.

**B.7 — the shape of the covered-growth branch, per gate.** Read verbatim before writing anything.
Eleven gates downgrade a live exceedance to a `level:'note'` when a covering changeset is present:
`dead-code:172-177`, `ts-any:100-105`, `module-deps:88-93`, `todo-fixme` (via
`truth-table.mjs:30-34`, `status:'pass'`), `config-surface` (via `truth-table.mjs:34-38`,
`status:'pass'`), `test-to-code:104-108`, `dead-code-jvm:115-117`, `npm-audit:126-141`,
`test-efficacy:133-146` and `:164-177`, and `style-literal-ratchet` + `atom-fork-ratchet` through the
shared `lib/ratchet-gate.mjs:68-76` factory. `adr-coverage:388-395` loads changesets and **discards
the result** — no covered branch exists there, so the rule does not apply to it (§C.3).

**B.8 — a claim I formed and then falsified myself.** My first design was a runner-level pass in
`run.mjs`: upgrade every `level:'note'` finding whose ruleId is `<gate>/<growth-classification>` to
an error. That would have been the strongest possible "shared layer". **It is wrong**, and the
counter-example is in the same file: `dead-code/enforcer.mjs:212-218` emits the SAME
`dead-code/declared-growth` id at `level:'note'` from the *baseline-shift* block, for a pin that WAS
raised under a changeset — i.e. precisely the case the rule must permit. A blanket runner upgrade
would fail the correct behaviour. Recorded because the brief asked for the shared layer and the
honest answer is "shared rule module, called at each gate's exceedance branch", not "runner pass";
the inheritance guarantee is bought back with a test instead (§C.2).

## §C — Implementation

### C.1 — The rule module

`scripts/governance/lib/declared-growth-repin.mjs` (new, 159 lines) is the single authority for the
rule: its ids, its SARIF descriptions, its message wording and its fail decision.

- `GROWTH_LICENSING_CLASSIFICATIONS` (`:44-52`) — the seven words across the kernel that license a
  metric moving the wrong way: `declared-growth`, `declared-regression`, `merge-import`,
  `emergency-override`, `test-wired-infra`, `lockfile-import`, `strength-regression`. Deliberately
  excludes the fourteen words that license a *baseline edit* rather than an exceedance
  (`unit-renormalization`, `unused-export-shrink`, `monotonic-shrink`, `dep-shrink`,
  `severity-decrease`, `seam-retraction`, `tier-change`, `rule-retired`, `new-rule-registered`,
  `slot-retraction`, `grace-extension`, `intentional-divergence`, `mirror-retirement`,
  `guard-downgrade`) — asserted as a list, not left to the reader (`declared-growth-repin.test.mjs`
  check 6).
- `repinRuleId` / `repinRuleDescription` (`:62-81`) — `<gate>/declared-growth-without-repin`, and
  `<gate>/declared-regression-without-repin` for the floor-shaped gates.
- `repinFinding` (`:122-159`) — the error. Names the pin file, the row, the measured value, how the
  pin moved (or did not) in this diff, the incident history, and the exact line to write.

**Why the rule collapses to one comparison.** "The pin was advanced to at least the measured value in
the same diff" is *equivalent* to "this row is not an exceedance", because every ratchet compares the
measured value against the **live** pin. So the rule needs no per-gate notion of "same diff": a
growth-licensing changeset simply stops suppressing an exceedance. Once the pin moves, the row leaves
the exceedance branch entirely and passes — on this PR and on every push after the squash.

`pinMovementClause` (`:91-99`) takes a `direction` so a floor gate (`test-to-code`, `test-efficacy`)
is told its pin has to come *down*, not up. Getting that backwards would print "advance the pin" at
an author who must lower it.

### C.2 — The eleven gates, and the test that keeps the twelfth honest

| Gate | Call site | Shape |
|---|---|---|
| `dead-code` | `enforcer.mjs:187-194` | `<path> <count>`; prior pin read at `:171-175` and reused by the baseline-shift block |
| `ts-any` | `enforcer.mjs:113-119` | `<path> <count>`; prior pin added at `:87-91` |
| `todo-fixme` | `enforcer.mjs:127-140` | truth-table `pass` + `count > pinned` is the exceedance |
| `module-deps` | `enforcer.mjs:100-107` | `<module> <count>` |
| `config-surface` | `enforcer.mjs:149-160` | truth-table `pass` + `current > pinned` |
| `test-to-code` | `enforcer.mjs:116-127` | **floor**; `declared-regression-without-repin` |
| `dead-code-jvm` | `enforcer.mjs:120-128` | set membership — the "pin" is the row's presence |
| `npm-audit` | `enforcer.mjs:141-155` | per `target/severity` counts in the baseline JSON |
| `test-efficacy` | `enforcer.mjs:136-148`, `:177-189` | **floor** (strength) and ceiling (no-coverage) |
| `style-literal-ratchet`, `atom-fork-ratchet` | `lib/ratchet-gate.mjs:69-84` | inherited from the shared factory |

`scripts/governance/lib/repin-coverage.test.mjs` (new) is the inheritance guarantee that §B.8 cost:
for every registered gate with a `changesetsDir` whose sources mention a growth-licensing word, it
asserts the gate either references the rule module or appears in `EXEMPT` with a stated reason. A new
ratchet gate fails this on the day it lands, not two lanes later. It also asserts no `EXEMPT` id has
fallen out of the registry (a stale exemption is false authority) and that the reach is ≥10 gates.

**But a static check is a hypothesis** (`audit-without-test`, and specifically `wrong-gate`): an
enforcer can import the module and call it on a branch that never runs. So
`scripts/governance/lib/repin-fires-per-gate.test.mjs` (new) is the runtime half — it drives seven
enforcers with a covering changeset AND a live exceedance and asserts each returns `verdict:'fail'`
carrying **its own** `…-without-repin` id at `level:'error'`. The remaining four call sites
(`ts-any`, `todo-fixme`, `dead-code`, and `atom-fork-ratchet`, which shares
`style-literal-ratchet`'s factory verbatim) are covered by `declared-growth-repin.test.mjs` and by
the `style-literal-ratchet` case. **11 of 11 branches exercised.** Writing this file was worth it on
its own terms: three of the seven first "passed" as `verdict:'fail'` for the *wrong* rule (two
schema mismatches and one report shape), which is why the assertion is on the rule id and not on the
verdict. §E F7 shows the coverage test passing while this one fails, on exactly the wrong-gate shape.

### C.3 — The eight exemptions, each with its reason

`repin-coverage.test.mjs:29-56`. `prose-tier-register`, `runtime-state` (register-row vocabularies —
the declared artifact *is* the register edit); `consumer-drift`, `ssot-catalog-sync`
(slot/mirror retraction, no numeric pin); `register-guard-resolution` (guard-string downgrade);
`tempdoc-wiring` (`emergency-override` suppresses a wiring finding; there is no baseline file);
`adr-coverage` (loads changesets for vocabulary validation only — §B.7); `wire` (protobuf
evolution-rule vocabulary in its own parser; a breaking change is licensed outright, there is no
count to pin).

### C.4 — Docs

- `docs/reference/contributing/discipline-gate-kernel.md` — new subsection "A growth-licensing
  changeset must re-pin in the same diff (enforced)" under the changeset escape-hatch protocol
  (`:96-124`, heading at `:96`), plus a closing paragraph on the baseline-shift section (`:279-283`) stating the
  three-way closure: growth without a changeset fails; a pin raise without a changeset fails; a
  changeset without a pin raise fails; the only passing shape is both, in one commit. Per §B.3 this
  is a *new* paragraph, not a promoted one.
- `.claude/skills/governance/SKILL.md` — the changeset-authoring doc the governance skill is. The
  rule, its rule id, the seven classifications it binds, the shrink words it does not, and the
  predictable evasion named inline ("the changeset covers it, the pin can move later").
- `scripts/governance/lib/explain.mjs:55-70` — `--explain <gate>/…-without-repin` now prints the
  re-pin remedy. Checked BEFORE the existing substring ladder on purpose:
  `declared-regression-without-repin` contains "regression", so the old code would have answered it
  with the changeset template it is telling the author is insufficient.

### C.5 — CI wiring (item 2)

`.github/workflows/ci.yml`, in the `public-claims` job, after the existing "Register-family gates"
step (`:272-276`) whose pattern #617 established:

- **`Hermetic kernel gates (17, no produced inputs)`** (`:295-311`) — the 17 green-and-hermetic gates
  from the census, one `run.mjs` invocation, ~8.4s. Placed above the ui-web install because none of
  them needs it (§D).
- **`Wire contract gate (protobuf breaking changes)`** (`:313-318`) — `npm ci --prefix
  scripts/wire-contract` then `--gate wire`, **plus an assertion that the SARIF does not contain
  `buf-cli-missing`**. That third line is the point: without buf the gate emits `buf-cli-missing`
  and still returns verdict `pass` (`wire-gate-buf-cli-missing` pin documents this fail-open), so a
  step that ran the gate without installing buf would be a permanent green inspecting nothing —
  `unreachable-seed-green`, bought and paid for.
- `scripts/wire-contract/package-lock.json` added to the `Setup Node` `cache-dependency-path`
  (`:70`) so the buf install is cached like the other two.

Registered-gate CI coverage goes **15 → 33 of 35**; the two that remain are `ts-any` and
`test-efficacy`, both with a stated blocker (§D).

### C.6 — One pin field added

`scripts/agent-analytics/expected-state.v1.json`, entry `ts-any-gate-counts-english-prose`: added
`fixOwner`. The pin already existed with `added`, `reviewBy: 2026-09-30` and an `exitProbe`, so the
brief's "pin it if a pin does not already exist" did not fire — but its owner line was "whoever next
touches the ts-any gate", which is nobody. `fixOwner` now names the governance-kernel lane and states
the consequence: `ts-any` is the one gate excluded from the hermetic CI step *because* of this red,
so the counting fix has a consumer instead of a backstop date. (`fixOwner` is in `ALLOWED_PIN_KEYS`,
`known-state-hint.test.mjs:99`, registered by 910 §F.10.)

## §D — The 20-gate census

Measured on the untouched base `39d38f73`, each gate run bare:
`node scripts/governance/run.mjs --gate <id> --mode gate`. **Inputs column is measured, not
inferred**: the worktree had no `node_modules` at any level (repo root, `modules/ui-web`,
`scripts/wire-contract` all absent) and no Gradle outputs, so "none" means the gate genuinely ran
against checked-in files alone.

| # | Gate | Base result | Runtime | Inputs needed | Decision | Where wired / why not |
|---|---|---|---|---|---|---|
| 1 | `consumer-drift` | pass (12 notes) | 1400 ms | none | (a) wire | ci.yml "Hermetic kernel gates" |
| 2 | `consumer-presence` | pass (1 note) | 90 ms | none | (a) wire | same step |
| 3 | `contract-projection` | pass | 635 ms | none | (a) wire | same step |
| 4 | `contribution-surface` | pass | 218 ms | none | (a) wire | same step |
| 5 | `hook-integrity` | pass | 2090 ms | none (reads tracked `.claude/**`; passes with `settings.local.json` absent) | (a) wire | same step |
| 6 | `host-owns-truth` | pass (1 note) | 98 ms | none | (a) wire | same step |
| 7 | `interaction-surface` | pass | 410 ms | none | (a) wire | same step |
| 8 | `observed-happening` | pass | 410 ms | none | (a) wire | same step |
| 9 | `prose-tier-register` | pass | 440 ms | none | (a) wire | same step |
| 10 | `runtime-state` | pass | 680 ms | none | (a) wire | same step |
| 11 | `runtime-witness` | pass (1 note) | 95 ms | none | (a) wire | same step |
| 12 | `ssot-catalog-sync` | pass | 275 ms | none | (a) wire | same step |
| 13 | `stage-completeness` | pass | 235 ms | none | (a) wire | same step |
| 14 | `surface-altitude` | pass | 272 ms | none | (a) wire | same step |
| 15 | `tempdoc-wiring` | pass | 210 ms | none | (a) wire | same step |
| 16 | `test-to-code` | pass | 400 ms | none | (a) wire | same step |
| 17 | `todo-fixme` | pass | 458 ms | none | (a) wire | same step |
| 18 | `wire` | pass **vacuously** (`contract-governance/buf-cli-missing`, verdict still pass) | 251 ms bare | buf CLI — `npm ci --prefix scripts/wire-contract`, 2 packages, committed lockfile, ~1 s | (a) wire **with its install** | ci.yml "Wire contract gate", + a SARIF assertion that `buf-cli-missing` is absent |
| 19 | `ts-any` | **fail** — 5 × `ts-any/silent-growth` | 381 ms | none | (b) do not wire red | pin `ts-any-gate-counts-english-prose` (exists; `fixOwner` added). Blocked by: `countAny` (`gates/ts-any/enforcer.mjs:29-31`) counting the English word "any" in comments. Owner: governance-kernel lane |
| 20 | `test-efficacy` | **skipped** (`kernel/input-skipped`, exit 0) | 88 ms | `tmp/pit-strength-report.v1.json` — an **on-demand** input produced by `node scripts/ci/report-pit-strength.mjs --run`, i.e. a PIT mutation run under Gradle | (c) needs an environment this job lacks | Not wired: the runner SKIPS an absent on-demand input, so a step here could only ever report a vacuous green. Unblocked by a lane that produces the PIT report; belongs beside `dead-code-jvm` in the unit-test lane, not in `public-claims` |

**Wired later, blocked by:**

| Gate | Blocked by | Owning tempdoc |
|---|---|---|
| `ts-any` | `countAny` scores prose; fixing it needs comment-stripping (`scripts/ci/check-readiness-reason-codes.mjs:116-148` has the pattern) then a rebalance of 18 rows | 884 §F row 1 → this tempdoc's §G, pin `ts-any-gate-counts-english-prose` |
| `test-efficacy` | needs a Gradle `:pitest` run to produce its on-demand input | 913 open item 1 → §G here |

Verified after wiring: the 17-gate command exits 0 (0 fail, 15 notes); the same command **with
`ts-any` added exits 1** (§E, F6); `--gate test-efficacy` exits 0 with verdict `skipped` (F6b).

## §E — Falsification record

Every new assertion broken once, watched fail, restored. Driver: `tmp/falsify.mjs` (scratch, not
committed); it restores each file and re-runs the full kernel suite at the end.

| # | What was broken | Test that noticed | Failure text (verbatim) |
|---|---|---|---|
| F1 | the repin branch in `ts-any` stops flipping the verdict (finding still emitted, gate passes) | `declared-growth-repin.test.mjs` | `declared-growth-repin.test: 2 FAILED, 14 passed` · `✗ ts-any: growth declared + pin unchanged → FAIL with the new rule id: Expected values to be strictly equal:` |
| F2 | the rule id renamed (`declared-growth-without-repin` → `declared-growth-ok`) | same | `declared-growth-repin.test: 7 FAILED, 9 passed` · `✗ rule id is <prefix>/declared-growth-without-repin and is described` |
| F3 | `priorPin` no longer threaded into the finding | same | `✗ ts-any: growth declared + pin advanced but below measured → FAIL: The input did not match the regular expression /moved 1 → 2 in this diff but still short/` |
| F4 | `dead-code` reverted to the pre-918 `level:'note'` behaviour — literally the code path that let #614 through | same | `declared-growth-repin.test: 1 FAILED, 15 passed` · `✗ dead-code: reproduces the #614 shape — changeset, no re-pin → FAIL: Expected values to be strictly equal:` |
| F5 | a wired gate silently drops the rule (`module-deps` import replaced by a local stub) | `repin-coverage.test.mjs` | `✗ every growth-licensing gate wires the repin rule (or is exempt with a reason): these gates license growth but never call the repin rule — import declared-growth-repin.mjs at the branch where a measured value exceeds its live pin, or add an EXEMPT entry saying why the gate has no such branch (tempdoc 918).` |
| F6 | `ts-any` added to the hermetic CI gate set | the command itself | `governance: 2 gates evaluated, 1 fail` · `ts-any: fail`, exit 1 — this is why row 19 is not in the step |
| F6b | `test-efficacy` treated as wireable | the command itself | `governance: 1 gate evaluated, 0 fail` · `test-efficacy: skipped`, exit 0 — a green that measured nothing |
| F7 | the **`wrong-gate` shape**: `config-surface` keeps the import and the call, but the branch is made unreachable (`&& false`) | only `repin-fires-per-gate.test.mjs` | `repin-coverage.test: all 3 checks passed` (**exit 0 — the static test does not notice**) vs `repin-fires-per-gate.test: 1 FAILED, 6 passed` · `✗ config-surface: expected config-surface/declared-growth-without-repin, got []` (exit 1) |

Restore verified: `governance run-all-tests: all 29 test files passed` after each sweep. F7 is the
one that justifies the second test file existing at all — it is the failure mode the postmortem
register calls `wrong-gate`, reproduced deliberately, with the static check green over it.

### E.1 — The live bite test (the #614 shape, on the real repo)

Not a fixture: `ts-any` on the working tree, whose five rows are a genuine live exceedance.

**A — base, no changeset** (`--gate ts-any --mode gate`, exit 1):

```
  ts-any: fail
    [ts-any] ts-any/silent-growth: modules/ui-web/src/shell-v0/components/chat/citationResolve.test.ts: 0 → 1 any-casts without declared changeset
    … (5 rows)
```

**B — a `declared-growth` changeset added, pin NOT advanced** — the #614 shape (exit 1, verbatim
first finding):

```
modules/ui-web/src/shell-v0/components/chat/citationResolve.test.ts: 'declared-growth' licenses this
change but the baseline pin was not moved with it. Measured 1 any-casts; the pin in
gates/ts-any/baseline.txt is 0. Changeset discovery is PR-scoped
(scripts/governance/lib/changeset-loader.mjs), so once this PR squash-merges the changeset leaves the
diff and the next push to main fails ts-any/silent-growth on a row nobody touched (tempdoc 910 §E.9;
observed #517→854, #595→885, #614→#613/#615). Remedy, in THIS commit: write
`modules/ui-web/src/shell-v0/components/chat/citationResolve.test.ts 1 <today>` into
gates/ts-any/baseline.txt and keep the changeset …
```

**C — changeset kept, the five pins advanced to their measured values** (exit 0):

```
governance: 1 gate evaluated, 0 fail, 0 findings
  ts-any: pass
```

Both the changeset and the five baseline rows were then reverted; `git status -- gates/` is clean.
The B→C transition is the whole rule: the same changeset that fails without the pin passes with it.

### E.2 — One defect the bite test found in my own message

Step B's first draft ended `(ts-any/silent-baseline-shift is what fires without it)`. **`ts-any` has
no `silent-baseline-shift` rule** — only some ratchets gate their baseline file. A confident,
gate-specific, wrong sentence in an error message. Rewritten to the conditional form plus a pointer
to `--explain`. Found only because the bite test printed the untruncated message on a real gate; the
fixtures would not have caught it.

## §F — Verification

Commands verbatim, from `F:/justsearch-public/.claude/worktrees/918-kernel-residue`.

| Command | Result |
|---|---|
| `node scripts/governance/run.mjs --mode gate` | exit 1 — `35 gates evaluated, 4 fail, 82 findings`. Error-level breakdown: `{ 'kernel/input-missing': 3, 'ts-any/silent-growth': 5 }`. All four are pinned pre-existing state: `npm-audit`, `dead-code`, `dead-code-jvm` are `kernel/input-missing` (`governance-kernel-inputs-unbuilt`; I produced the two node-only inputs, so `config-surface` and `module-deps` now **pass**, down from the pin's 5), and `ts-any` is `ts-any-gate-counts-english-prose`. **Zero findings carry a `…-without-repin` id** — the new rule is inert on this base, as §G.1 argues it must be. |
| `node scripts/governance/run.mjs --self-test --mode gate` | exit 0 — 58 fixture verdicts, 0 mismatches |
| `node scripts/governance/run-all-tests.mjs` | `governance run-all-tests: all 29 test files passed` (26 on the base + the 3 added here) |
| `node scripts/agent-analytics/run-all-tests.mjs` | `agent-analytics: 64/64 test files passed` (run because `expected-state.v1.json` was edited — the pre-merge row 910 §F.10 bought). A later re-run under heavier parallel-worktree load reported `failed: 861-w5-agent-spawn-sweep.test.mjs`; re-run alone it is `16 passed / 0 skipped`, exit 0 — which is exactly what the `agent-analytics-suite-wallclock-flaky-under-load` pin describes, and the pin's own instruction ("re-run it with `node <file>` before believing it") is what was followed. Not caused by this diff, which touches no wall-clock-budgeted code. |
| `node scripts/ci/check-workflow-triggers.mjs` | `OK (workflow triggers match workflow-signal-policy.v1.json)` |
| `node scripts/docs/llmstxt-generate.mjs --check` | `OK (115 docs indexed)` |
| `node scripts/docs/skills-sync.mjs --check` | `OK (5 skills, 9 sources)` |
| `node scripts/docs/verify-canonical-doc-links.mjs` | `OK (files=156)` |
| `node scripts/ci/check-always-loaded-budget.mjs` | `pass — every always-loaded file within its ratchet ceiling` |
| the 17-gate CI step, exactly as written in `ci.yml` | exit 0 — `17 gates evaluated, 0 fail, 15 findings` |
| `npm ci --prefix scripts/wire-contract` then `--gate wire --mode gate` | exit 0 — `1 gate evaluated, 0 fail, 0 findings`; `grep -c buf-cli-missing tmp/wire-real.sarif` → `0`. The gate really inspected the protos |

No Gradle was run (brief constraint); no gate in this diff needs it.

**Does the rule turn `main` red?** No, and the argument is mechanical rather than hopeful. The rule
fires only when a growth-licensing changeset is in PR scope AND a row exceeds its live pin. On a
`push` to `main`, `GITHUB_BASE_REF` is empty and `merge-base(HEAD, origin/main) === HEAD`, so
`resolveGitBase` (`git-utils.mjs:196-215`) falls through to `HEAD~1` — only the last commit's
changesets are in scope. The most recent changeset added to `main` is
`gates/dead-code/.changesets/915-…` in `a83de156` (#615), two commits back, and that PR *did* advance
its pin. On a PR, scope is the merge base; this branch adds no changeset at all. Confirmed
empirically by the full-kernel row above: zero `…-without-repin` findings. **No ride-along re-pin is
needed on the base.**

## §G — Open items

1. **The rule is inert until the first PR that declares growth.** That is correct — there is nothing
   on `main` to catch — but it means the *live* evidence for the rule is the bite test (§E.1) and the
   fixtures, not a production firing. The first PR to author a growth changeset is the real
   confirmation; if it reports the finding and the author re-pins, the loop 883 named is closed.
2. **`ts-any` is still red and still unwired.** `countAny` (`gates/ts-any/enforcer.mjs:29-31`) runs
   its regex over raw text, scoring the English word "any" in comments. The fix is comment-stripping
   (pattern at `scripts/ci/check-readiness-reason-codes.mjs:116-148`) plus a rebalance — but note the
   rebalance must now itself respect the re-pin rule, and 884 §F row 1 warns the 18 existing rows may
   be overstated by the same bug, so the rebalance is a re-measurement, not a `--rebalance` run.
   This is the single remaining blocker on the last-but-one gate. Owner: recorded as `fixOwner` on
   the pin.
3. **`test-efficacy` needs a PIT-producing lane.** It should join `dead-code-jvm` in the unit-test
   lane (which already builds a Gradle-produced gate input) rather than `public-claims`. Not done
   here: the brief is node-only, and adding a `:pitest` invocation to a test lane is a walltime
   decision that belongs to whoever owns that lane's budget.
4. **`scripts/governance/lib/covers.mjs` has no production consumer.** `persistentlyCovers` /
   `coversBoundFor` / `parseCovers` — the tempdoc 576 §4 bounded-exception protocol — are imported
   only by `covers.test.mjs`. Its one consumer (the `ui-bundle` gate) was removed for go-public
   (tempdoc 634) and the lift-out survived it. This is `retire-with-a-sweep` residue: 74 lines of
   registered-looking substrate that no gate can reach, and it is adjacent to this work because a
   reader designing a new ratchet will find it and assume it is live. Routed here rather than fixed:
   deleting it or wiring it is the 576/634 owner's call. Owner: the governance-kernel lane.
5. **910 §E item 8 remains open** (`CLAUDE.md`'s pre-merge table does not list
   `governance/store-corruption-policies.v1.json` as a `check-store-recoverability` trigger). Item 7
   is closed (§B.6). Still deliberately untouched: byte-ratcheted file, brief scopes it out.
6. **`adr-coverage` loads changesets and discards them** (`enforcer.mjs:388-395`). Not a defect this
   lane creates — the call validates the classification vocabulary and nothing else, which may be
   intentional — but a reader will read it as coverage. Left alone; noted so the next kernel lane can
   decide between deleting the call and using its result.

## Report-back

**PRs.** One: `gov(918): declared growth must re-pin in the same diff; wire the hermetic kernel gates
into CI`, against `main` from `worktree-918-kernel-residue` (base `39d38f73`).

**Items done.** Both, in full. Item 1: the re-pin rule as a shared module reaching eleven gates, a
new rule id per gate, an `--explain` remedy, fixtures covering all four cases, a coverage test that
fails a future gate that forgets it, and the two docs. Item 2: the 20-gate census measured on the
base, 18 of the 20 wired (17 hermetic + `wire` with its install and an anti-vacuous-green assertion),
the two exclusions each with a named blocker and an owner.

**Deviated.** Three, each stated where it happens. (a) The "shared layer" is a rule *module* called
per gate, not a runner pass — §B.8 records the counter-example that killed the runner design, and
§C.2's coverage test buys back the inheritance guarantee the brief wanted. (b) The kernel doc
paragraph was *written*, not promoted: §B.3 found the "already documented" remedy the brief cites
does not exist in that form. (c) I added one field (`fixOwner`) to an existing pin rather than
creating a pin, because the pin already existed with everything else the brief asked for.

**Skipped.** Nothing.

**Evidence.** §F, ten commands with verbatim results. §E, eight falsifications (including the `wrong-gate` shape
reproduced deliberately) plus a three-step live bite test on the real repo.

**Residue routed.** §G items 2-6 — `ts-any`'s counting bug (to its pin, now with an owner),
`test-efficacy`'s Gradle input (to the unit-test lane), dead `covers.mjs` (to the governance-kernel
lane), 910 item 8 (still open, still out of scope), `adr-coverage`'s discarded load.

**What the next lane must know.**
- **The changeset protocol changed shape.** A growth changeset without a pin advance is now a build
  failure, not a warning. If you write one, write the baseline line in the same commit; the finding
  tells you the exact text.
- **CI now runs 33 of 35 registered gates.** A gate you add is expected to be wired the day it lands;
  the census in §D is the template for arguing it cannot be.
- **`wire` finally runs, and it fails open.** If a step ever "passes" the wire gate without the buf
  install, it inspected nothing. The `ci.yml` step asserts that explicitly — keep the assertion.
- **A fixture cannot catch a wrong sentence in an error message.** §E.2: the bite test on real state
  found a confident, gate-specific, false clause the fixtures printed but never read.
