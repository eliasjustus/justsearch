---
title: "CI enforcement closure, tier 0: wire every already-built verification mechanism that needs no new infrastructure into public CI"
type: tempdocs
status: CHARTERED (2026-09-02) — not started
created: 2026-09-02
updated: 2026-09-02
lane: 887 L1
model: opus (takeover)
parent: 887-improvement-landscape-register
related:
  - 872-memory-retirement            # wired the ui-web static gate set via run-ui-web-gates.mjs
  - 884-decision-review-lane-b-governance-loop   # wired kernel gate self-tests into CI (B1)
  - 745-oss-first-observability      # wired agent-analytics node self-tests (D6)
  - 802-release-artifact-provenance  # wired test_release.py; flagged the other 131 pytest files
  - 698-codeql-first-run-security-triage
  - 651-public-ci-feedback-loop-efficiency / 668-public-ci-walltime-attribution  # CI cost discipline
---

# 888 — CI enforcement closure, tier 0

## Briefing for the agent picking this up

You are starting fresh. Read this file, then `docs/tempdocs/887-improvement-landscape-register.md`
§X1 and Appendix A7/A8 (the evidence). Work in your own worktree (`EnterWorktree`). One PR per
numbered item below — they are independent and small; do not bundle. Every item's acceptance
criterion is "the mechanism runs in a public CI job on a PR and the job is green or its red is
pinned with a dated exit in `scripts/agent-analytics/expected-state.v1.json`". Never make a red
mechanism green by disabling it (`fix-root-causes-not-symptoms`). Public CI is hosted
`ubuntu-latest` + one Windows lane (ADR-0044); everything here must run there without a GPU,
a dev stack, or a self-hosted runner — anything that needs those belongs to lane L2 and is out
of scope. Keep CI walltime visible: note each new job's duration in §Status (668 owns the trend).

## Thesis

Ten verification mechanisms are fully implemented on `main` and enforce nothing because no
workflow invokes them (887 §X1). This lane closes the subset that needs no new infrastructure.
The class already burned the repo once: `scripts/ci/run-ui-web-gates.mjs:8-10` records
`gen-token-names --check` sitting RED on main for weeks while twelve sessions rediscovered it.

## Scope (each item = one PR)

1. **Frontend typecheck + unit tests.** `modules/ui-web` `npm run typecheck && npm run test:unit:run`
   run in no workflow (grep `.github/workflows/` for `vitest|test:unit|typecheck` → 0). The
   `ui-web-gates` recipe in `governance/consult-register.v1.json` lists them on a line the runner's
   parser skips (`run-ui-web-gates.mjs:34-42`). Add a dedicated CI step (do not overload the gate
   runner). Known flaky cases are pinned in `expected-state.v1.json` (EnvelopeStream heartbeat,
   PluginLoader module-mode, resourceRegistry defaults) with fixes tracked in 872 §6 — **fix them
   deterministically** (fake timers / per-test timeout / warm import) rather than retrying or
   skipping; a retry config that hides them is the predictable evasion.
2. **PMD + SpotBugs actually run.** `JvmBaseConventionsPlugin.kt:187-215`: `pmdMain` attaches
   to `check`; `skipPmd` defaults true unless `CI` is set; no workflow runs `check`/`build`
   (workflows invoke only `checkLicense`, `assemble`, `:modules:*:test`, `integrationTest`,
   `installDist`). `SpotBugsConventionsPlugin.kt:27-63`: warn-only, `failOnError` set nowhere.
   Add a CI job running `pmdMain` + `spotbugsMain` across modules. Triage the SpotBugs baseline:
   fix what is real, register the rest as an only-shrinks ratchet (pattern:
   `scripts/governance/gates/todo-fixme/`), then flip `spotbugs.failOnError=true`. FindSecBugs
   rides along. Then correct `CLAUDE.md` Quick Commands ("Build fails on PMD + Spotless") to
   whatever is true after this PR (887 §Z-1).
3. **Codegen idempotency + SSOT sync gates.** Only `check-notices-regen.mjs` (`ci.yml:413`) and
   a regen *test* (`ci.yml:338`) run. Wire `check-api-client-regen`, `check-field-constants-regen`,
   `check-liveness-constants-regen`, `check-wire-schema-types-regen`,
   `check-agent-hooks-wiring-regen`, `check-shape-handler-regen`, and the kernel
   `ssot-catalog-sync` gate (`node scripts/governance/run.mjs --gate ssot-catalog-sync --mode gate`).
   Also wire every other kernel gate whose inputs exist without a Gradle/knip/audit run — enumerate
   with `run.mjs --list` and state in §Status which gates remain excluded and why.
4. **Lint for non-Java code.** `modules/ui-web` `npm run lint` (eslint configured, never invoked);
   `scripts/jseval`: add `ruff` (lint + format check) and `mypy` in lenient mode to
   `pyproject.toml`, run both; `modules/shell/src-tauri`: `cargo clippy --locked -- -D warnings`,
   `cargo fmt --check`, `cargo audit` (or `cargo deny`). Baseline-ratchet any pre-existing volume
   rather than suppressing. The 4 `unsafe` blocks in `updater.rs` get a `// SAFETY:` comment each.
5. **Hook bites.** `governance/agent-hooks.v1.json`: 39 hooks, 5 real subprocess bites, 8
   `kind: "unit"` satisfied by `existsSync(testPath)` (`hook-integrity/enforcer.mjs:229-231`), 26
   with no bite. Add a crafted-stdin bite for every blocking or redirecting hook (bash-guard
   variants, repeat-guard, build-counter, intervene, maintain-doc-hint, subagent-model-guard,
   taskcreate-guard, edit-reread, worktree-base) and make `unit` kind execute the test file
   rather than stat it.
6. **Stale-claim one-liners** found during 887: `governance/ui-a11y-baseline.v1.json` description
   names a non-existent "TS e2e accessibility-audit gate" (real consumers: `ui_measure.py`,
   `ui_a11y_gate.py`, `regen_a11y_baseline.py`); `scripts/ci/check-ui-step-coverage.mjs` header and
   tempdoc 615 claim it is "wired as a ci.yml step" — wire it, then the claim is true (887 §Z-3, Z-4).
7. **Stretch (only if 1-6 land):** live axe gate `scripts/jseval/jseval/ui_a11y_gate.py` in CI
   via Playwright chromium on ubuntu against the built catalog. If the fixture harness needs a
   backend, stop and record why in §Status — do not stub.

## Acceptance criteria

- Each item: a PR whose CI run shows the new step executing (link the run in §Status), green or
  pinned. `node scripts/ci/check-workflow-triggers.mjs` passes (workflow edits).
- Item 2: `./gradlew.bat pmdMain spotbugsMain` locally with `-PskipPmd=false -PskipSpotBugs=false`
  matches CI; `CLAUDE.md` sentence corrected; `node scripts/ci/check-premerge-table.mjs` green.
- Item 3: `node scripts/governance/run.mjs --list` output pasted in §Status with a wired/excluded
  column and a reason per exclusion.
- Item 5: `node scripts/governance/run.mjs --gate hook-integrity --mode gate` green with bite
  count ≥ 30 of 39.
- Full local pre-merge: `./gradlew.bat build -x test`, `cd modules/ui-web && npm run typecheck &&
  npm run test:unit:run`, `node scripts/governance/run.mjs --self-test --mode gate`.

## Constraints

- No self-hosted runner, GPU, dev stack, or model download in any new job (L2 territory).
- Do not fold this into the founder's lane B (884) branch; B1 already wired kernel self-tests —
  build on `main`, not on 884's branch.
- Keep each job parallel to existing lanes; report walltime deltas.
- Non-goals: perf-gate, PIT/`test-efficacy`, soak, full jseval pytest, upgrade matrix (all L2);
  NullAway/JSpecify (lane 900); visual-regression baselines (policy decision, not this lane).

## Status

(unstarted)
