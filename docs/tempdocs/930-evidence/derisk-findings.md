<!-- Sidecar of docs/tempdocs/930-replace-bounded-areas-with-maintained-oss.md — moved here per the tempdoc size-cap split (930 §19.3 F4, 2026-09-05). Headings below are unchanged from the main file; this directory is exempt from check-tempdoc-size.mjs. -->

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

