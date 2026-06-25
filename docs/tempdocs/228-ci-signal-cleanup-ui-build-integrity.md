---
title: "228: CI Signal Cleanup and UI Build Integrity"
type: tempdoc
status: done
created: 2026-02-20
updated: 2026-02-25
---
# 228: CI Signal Cleanup and UI Build Integrity

## Purpose

Document and track the hardening pass for five CI/UI quality issues:
1. Vite chunk-size and circular-chunk warnings.
2. UI circular dependency graph cycles.
3. npm vulnerability/deprecation noise handling under warn-only policy.
4. Lockfile CRLF warning noise in lockfile freshness checks.
5. Test-reporter summary-size trimming risk.

## Issue Summary

Observed issues before this pass:
1. Rollup warnings: `circular dependency between chunks` caused by barrel re-exports and cross-chunk store imports.
2. Rollup warning: large post-minification chunk (`>500kB`) in UI build.
3. CI install steps emitted noisy npm vulnerability/deprecation output.
4. Lockfile freshness checks emitted CRLF/LF warning spam on Windows runners.
5. Test report check runs risked oversized summary output trimming.

## Implemented Fixes

### 1) Deterministic vendor chunking

File:
- `modules/ui-web/vite.config.js`

Change:
1. Replaced object-style `manualChunks` with callback-style deterministic path mapping.
2. Co-located `react`, `react-dom`, and `scheduler` into `vendor-react`.
3. Kept dedicated vendor chunks for `framer-motion`, `zustand`, and `zod`.

Result:
1. Removed chunk-size warning.
2. Reduced main app chunk size and stabilized chunk topology.

### 2) Circular dependency cleanup (targeted breaks)

Files:
- `modules/ui-web/src/hooks/useBrowseTreeKeyboard.ts`
- `modules/ui-web/src/components/views/BrowseView.tsx`
- `modules/ui-web/src/hooks/useSearch.ts`
- `modules/ui-web/src/stores/useSearchStore.tsx`
- `modules/ui-web/src/stores/useSystemStore.ts`
- `modules/ui-web/src/hooks/useInferenceMode.ts`
- `modules/ui-web/src/hooks/useSources.ts`
- `modules/ui-web/src/hooks/useSettings.ts`
- `modules/ui-web/src/hooks/useStatus.ts`
- `modules/ui-web/src/components/views/brain/BrainCompatibilitySection.tsx`
- `modules/ui-web/src/components/views/brain/BrainRuntimeSection.tsx`
- `modules/ui-web/src/hooks/searchFiltersShared.ts` (new)

Changes:
1. Replaced barrel imports with direct module imports in cycle-prone paths.
2. Removed selector re-exports from `useSystemStore`.
3. Switched selector consumers to `systemSelectors`.
4. Broke `useSearchStore` → `useSearch` type/value coupling by extracting shared filter contract to `searchFiltersShared.ts`.
5. Removed self-referential brain barrel imports (`from '.'`) in section components.

Result:
1. `madge` reports zero circular dependencies for `modules/ui-web/src`.
2. Rollup circular-chunk warnings no longer appear in local build.

### 3) Warn-only npm audit governance

Files:
- `scripts/ci/report-npm-audit.mjs` (new)
- `.github/workflows/ci.yml`

Changes:
1. Added `report-npm-audit.mjs` to run `npm audit --json` for:
   - repo root
   - `modules/ui-web`
   - `SSOT/tools`
2. Emits combined JSON artifact (`tmp/npm-audit-report.json`).
3. Emits concise markdown summary to `$GITHUB_STEP_SUMMARY` when available.
4. Explicitly exits success (warn-only policy).

Workflow integration:
1. Added `NPM audit summary (warn-only)` step in full and fast build paths.

### 4) CI install noise reduction

File:
- `.github/workflows/ci.yml`

Changes:
1. Updated Node dependency installs to:
   - `npm ci --no-audit --no-fund`
   - `npm ci --prefix SSOT/tools --no-audit --no-fund`
2. Applied to corpus quickcheck and full/fast build job install steps.

### 5) Lockfile CRLF warning suppression with unchanged stale detection

Files:
- `.github/workflows/ci.yml`
- `scripts/ci/local-agent-gate-win.ps1`

Changes:
1. Retained `resolveAndLockAll --write-locks --quiet`.
2. Switched lockfile diff checks to step-local git options:
   - `git -c core.autocrlf=false -c core.safecrlf=false ...`
3. Included both `"**/gradle.lockfile"` and `"settings-gradle.lockfile"` in checks.
4. Applied parity change to local gate freshness path.

### 6) Test-reporter summary trimming risk reduction

File:
- `.github/workflows/ci.yml`

Changes:
1. Set `only-summary: true` on both `dorny/test-reporter@v2` steps.
2. Kept `use-actions-summary: false`.
3. Kept `fail-on-error` and `fail-on-empty` unchanged.

## Dependency Updates Applied

Files:
- `package.json`
- `package-lock.json`
- `SSOT/tools/package.json`
- `SSOT/tools/package-lock.json`
- `modules/ui-web/package.json`
- `modules/ui-web/package-lock.json`

Updates:
1. `ajv` bumped to `^8.18.0` in root and `SSOT/tools`.
2. `modules/ui-web` lint stack patch/minor updates (no major migration):
   - `eslint` `^9.39.3`
   - `@eslint/js` `^9.39.3`
   - `@typescript-eslint/eslint-plugin` `^8.56.0`
   - `@typescript-eslint/parser` `^8.56.0`

## Verification Evidence

Commands run:
1. `npm run build --prefix modules/ui-web` -> passed.
2. `npx madge --circular --extensions ts,tsx modules/ui-web/src` -> passed, no cycles found.
3. `node scripts/ci/report-npm-audit.mjs --out tmp/npm-audit-report.json` -> passed, report emitted.
4. Local YAML parse of `.github/workflows/ci.yml` -> passed.
5. LF-safe lockfile diff command with `settings-gradle.lockfile` -> passed.

Notes:
1. `npm run lint --prefix modules/ui-web` and `npm run typecheck --prefix modules/ui-web` currently fail on pre-existing unrelated errors/warnings outside this scoped change.
2. Full remote CI run was watched to completion, but against the currently pushed SHA at dispatch time. Final verification for these exact code changes requires push of this change set and another full CI run.

## Current State

All six implementation items and all long-term guardrails are complete. CI ran successfully
(3 runs, `success`) on 2026-02-20 against the change set. Tempdoc closed 2026-02-25.

**Known open issue (out of scope):** `npm run lint` and `npm run typecheck` in `modules/ui-web`
fail on pre-existing errors unrelated to this change set. Tracked separately — not a 228 blocker.

**Documentation gap (noted):** §Guardrail Architecture lists `app_main_bytes` as a tracked
metric but §Baseline and Threshold Policy does not include its baseline value. Verify against
`scripts/ci/ui-bundle-budget.v1.json` if updating thresholds.

## Long-Term Guardrails (2026-02-20)

Added first-class CI guardrails to keep UI integrity and dependency drift signal stable after the initial cleanup pass.

### Guardrail Architecture

Files:
1. `scripts/ci/check-ui-cycles.mjs`
2. `scripts/ci/check-ui-bundle-budget.mjs`
3. `scripts/ci/ui-bundle-budget.v1.json`
4. `scripts/ci/check-npm-audit-ratchet.mjs`
5. `scripts/ci/npm-audit-ratchet-baseline.v1.json`
6. `.github/workflows/ci.yml`
7. `scripts/ci/module-filter.yml`

Behavior:
1. **UI cycle gate** runs madge in JSON circular mode and fails only in `gate` mode when cycles/tool errors are present.
2. **UI bundle budget gate** enforces a ratchet + hard-cap policy over:
   - `total_js_bytes`
   - `max_js_chunk_bytes`
   - `app_main_bytes`
   - `vendor_react_bytes`
   - `vendor_motion_bytes`
3. **NPM audit ratchet gate** compares current `report-npm-audit` output against a committed baseline and gates only selected severities (`high`,`critical`).

### Trigger Conditions

Path-filter contracts added:
1. `ui_web`: runs UI cycle + UI bundle budget checks when UI web files or UI guardrail files change.
2. `npm_audit_scope`: runs audit ratchet gate only when npm manifests/lockfiles or audit guardrail files change.

Workflow behavior:
1. Fast and full jobs keep warn-only `report-npm-audit` summary always.
2. Ratchet gate runs conditionally on `npm_audit_scope` (or `all`).
3. UI gates run conditionally on `ui_web` (or `all`).

### Baseline and Threshold Policy

`ui-bundle-budget.v1.json` initial thresholds (baseline +15% hard caps):
1. `total_js_bytes`: baseline `990959`, hard cap `1140000`
2. `max_js_chunk_bytes`: baseline `371254`, hard cap `427000`
3. `vendor_react_bytes`: baseline `192912`, hard cap `222000`
4. `vendor_motion_bytes`: baseline `121524`, hard cap `140000`
5. Ratchet rule per metric: fail on increase beyond `max(3%, 10000 bytes)`.

`npm-audit-ratchet-baseline.v1.json` policy:
1. Scope: `high`,`critical`
2. Trigger: dependency-change scoped
3. Outcome: gate only on selected severity increases

### Baseline Update Runbook

1. Refresh current audit report:
   - `node scripts/ci/report-npm-audit.mjs --out tmp/npm-audit-report.json`
2. Rewrite ratchet baseline from current report (intentional dependency update only):
   - `node scripts/ci/check-npm-audit-ratchet.mjs --report tmp/npm-audit-report.json --baseline scripts/ci/npm-audit-ratchet-baseline.v1.json --write-baseline --mode warn`
3. Verify ratchet behavior:
   - `node scripts/ci/check-npm-audit-ratchet.mjs --report tmp/npm-audit-report.json --baseline scripts/ci/npm-audit-ratchet-baseline.v1.json --severities high,critical --mode gate --out tmp/npm-audit-ratchet-report.json`

Bundle baseline update procedure (intentional packaging/layout change only):
1. `npm run build --prefix modules/ui-web`
2. Measure generated asset sizes via:
   - `node scripts/ci/check-ui-bundle-budget.mjs --dist modules/ui-web/dist/assets --policy scripts/ci/ui-bundle-budget.v1.json --mode warn --out tmp/ui-bundle-budget-report.json`
3. Update `scripts/ci/ui-bundle-budget.v1.json` baseline/hard caps in same change set with rationale.

### Failure Diagnostics and Remediation

1. UI cycle gate failure:
   - Read `tmp/ui-cycles-report.json`.
   - Break import cycle(s) by replacing barrel/self-referential imports with direct module imports.
2. UI bundle budget gate failure:
   - Read `tmp/ui-bundle-budget-report.json`.
   - If ratchet breach: investigate new heavy dependency or chunk topology regression.
   - If hard-cap breach: reduce payload (lazy-load/split/replace dependency) before raising cap.
3. Audit ratchet gate failure:
   - Read `tmp/npm-audit-ratchet-report.json`.
   - Remediate dependency drift or explicitly refresh baseline only when increase is accepted by policy.

---

## Post-Closure Analysis (2026-02-25)

### Guardrail value assessment

**UI cycle gate — high long-term value.** Fires only on UI file changes, produces actionable
JSON, and protects the one-time cleanup that preceded it. Low maintenance cost.

**Bundle budget ratchet — high long-term value, with a structural risk.** The 3%/10KB
ratchet threshold is correct policy, but the baseline was set on 2026-02-20 against a
pre-feature-complete app. JustSearch is pre-launch with active UI development — every
meaningful UI feature addition will trip the ratchet and require a manual baseline update
via the §Baseline Update Runbook. If baseline updates are repeatedly deferred, the gate
loses credibility. The update discipline must be treated as a first-class obligation, not
a housekeeping task.

**npm audit ratchet — low long-term value.** npm audit is notoriously noisy for
devDependencies. High/critical vulns in dev tooling are often unfixable without breaking
dependency upgrades, and the ecosystem moves faster than most teams can track. The
`--no-audit --no-fund` install suppression (§4) is the more durable fix — it acknowledges
the signal quality is too low for per-install noise. The ratchet is likely to become
ignored or special-cased over time; watch for gate bypass patterns and consider
downgrading it to warn-only if false-positive noise accumulates.
