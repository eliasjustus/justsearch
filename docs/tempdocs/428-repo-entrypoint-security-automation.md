---
title: "Repo entrypoint and security automation repair"
type: tempdocs
status: active
created: 2026-05-08
---

# 428 - Repo Entry Point and Security Automation Repair

## Status

**ACTIVE.** Implemented in the working tree on 2026-05-08; awaiting
review/commit. Targeted checks are green except for the pre-existing
canonical-doc link drift noted under Results.

This tempdoc covers the first, low-risk remediation batch:

- restore the root README as a real JustSearch entry point;
- add a small README smoke check so the regression cannot quietly
  recur;
- add Cargo/Tauri coverage to Dependabot;
- add baseline CodeQL scanning for Java and JavaScript/TypeScript.
- normalize GitHub workflows so repository workflows are
  `workflow_dispatch` only.
- canonicalize the manual-only workflow decision so future agents do
  not reintroduce tag, push, PR, or schedule triggers.

It intentionally does **not** cover larger behavioral changes such as
`OnlineModeOps` stream cancellation or decomposition of the remaining
large orchestration classes. Those are real findings, but they have
larger blast radius and should be handled in separate tempdocs or an
existing relevant tempdoc.

## Origin

External review claim, locally validated on 2026-05-08:

> JustSearch is architecturally strong, but under-protected by current
> automation defaults. The highest-leverage fixes are the root README,
> Cargo Dependabot, CodeQL, and workflow trigger normalization.

Local validation confirmed those items as current repo state.

## Evidence

### Root README regression

`README.md` currently starts with:

```text
# LocalIntentTranslatorConfig Dead Code Analysis
```

This is an internal analysis artifact, not the product/developer entry
point. The root README is the first contract a future contributor or
public visitor sees, so this is a visible documentation correctness bug.

### Workflow trigger drift

Most `.github/workflows/*.yml` files are `workflow_dispatch` only. This
is intentional per ADR-0026 and `CLAUDE.md`: agents invoke workflows
explicitly when remote verification is needed.

`build-installer.yml` currently retains a tag `push` trigger. Per user
direction on 2026-05-08, **all workflows should be `workflow_dispatch`
only**, so the installer tag trigger is drift and belongs in this batch.

Canonicalization note: ADR-0026 currently contains a 2026-04-26
amendment that explicitly preserves `build-installer.yml` tag pushes
because it runs on GitHub-hosted `windows-latest`. That carve-out is now
superseded by the 2026-05-08 user decision. Implementation must amend
ADR-0026, not only edit the workflow YAML.

### Missing CodeQL workflow

Search found no workflow using:

- `github/codeql-action/init`
- `github/codeql-action/autobuild`
- `github/codeql-action/analyze`

`ci.yml` does upload custom contract-governance SARIF to GitHub Code
Scanning, but that is not CodeQL semantic analysis.

### Missing Cargo Dependabot coverage

`.github/dependabot.yml` currently covers:

- root npm;
- `modules/ui-web` npm;
- `modules/shell` npm;
- Gradle;
- GitHub Actions.

It does not cover Cargo. Rust dependencies exist in
`modules/shell/src-tauri/Cargo.toml` for the Tauri shell, so the
automation gap is real.

## Implementation Checklist

- [x] Preserve the current README dead-code analysis content outside
      the root entry point if it still has value. Candidate destination:
      `docs/tempdocs/428-artifacts/local-intent-translator-config-dead-code-analysis.md`
      or a more specific existing tempdoc if one is found during
      implementation.
- [x] Replace the root `README.md` with a concise JustSearch overview:
      product identity, local-first positioning, start links, build
      pointers, architecture link, contributing/security/license links.
- [x] Add `scripts/ci/check-root-readme.mjs` that verifies:
      `README.md` starts with `# JustSearch`;
      required entrypoint phrases/links are present;
      the dead-code-analysis title is not the root title.
- [x] Wire the README smoke check into local/CI documentation gates.
      Candidate first wiring: add the script to `docs-lint.yml`.
      Consider adding it to `ci.yml` only if it fits the existing gate
      scope cleanly.
- [x] Add a Cargo ecosystem entry to `.github/dependabot.yml`:
      directory `/modules/shell/src-tauri`, monthly schedule, grouped
      updates.
- [x] Remove tag `push` triggering from `build-installer.yml`; keep it
      manual via `workflow_dispatch`.
- [x] Add `.github/workflows/codeql.yml` with Java and
      JavaScript/TypeScript analysis.
- [x] Keep CodeQL workflow separate from the self-hosted Windows CI
      lane. Because this repo's workflow policy is manual-only, CodeQL
      should also use `workflow_dispatch` only unless the user later
      changes the policy.
- [x] Amend `docs/decisions/0026-manual-ci-triggering.md` with a
      2026-05-08 amendment: all repository workflows are manual-only,
      including GitHub-hosted installer/security workflows; the earlier
      `build-installer.yml` tag-push carve-out is retired.
- [x] Update agent-facing guidance (`CLAUDE.md` and/or
      `docs/reference/contributing/agent-guide.md`) so it no longer
      describes `build-installer.yml` as retaining a tag push trigger.
- [x] Add or wire a deterministic trigger-policy guard that fails if
      any `.github/workflows/*.yml` contains top-level `push:`,
      `pull_request:`, or `schedule:`. Candidate:
      `scripts/ci/check-workflow-triggers.mjs`, invoked from `ci.yml`
      and local gate if the gate has a clean governance-check section.
- [x] Run formatting/lint checks relevant to edited files.
- [x] Update this tempdoc with results and mark complete only after the
      checks are green or failures are explicitly explained.

## Results

Implemented files/surfaces:

- Root README restored; old dead-code analysis preserved under
  `docs/tempdocs/428-artifacts/`.
- `scripts/ci/check-root-readme.mjs` added and wired into `ci.yml`,
  `docs-lint.yml`, and `scripts/ci/local-agent-gate-win.ps1`.
- `scripts/ci/check-workflow-triggers.mjs` added and wired into the
  same gates.
- `build-installer.yml` tag-push trigger removed.
- `codeql.yml` added as manual-only CodeQL scanning.
- Cargo Dependabot added for `modules/shell/src-tauri`.
- ADR-0026 amended; `CLAUDE.md`, `agent-guide.md`,
  `workflow-signal-policy.v1.json`, and open-source readiness notes
  updated to match.
- Follow-up review fixes applied: `check-workflow-triggers.mjs` now
  enforces an exact `workflow_dispatch`-only event set, readiness docs
  no longer show stale push/PR/schedule workflow examples, and workflow
  signal test fixtures no longer model obsolete trigger events.

Verification run on 2026-05-08:

```text
node scripts/ci/check-root-readme.mjs                         PASS
node scripts/ci/check-workflow-triggers.mjs                   PASS
node --check scripts/ci/check-root-readme.mjs                 PASS
node --check scripts/ci/check-workflow-triggers.mjs           PASS
node scripts/ci/test-workflow-signal-health.mjs               PASS
node scripts/ci/test-verify-codeowners.mjs                    PASS
node scripts/docs/llmstxt-generate.mjs --check                PASS
npx markdownlint README.md docs/tempdocs/428-repo-entrypoint-security-automation.md docs/decisions/0026-manual-ci-triggering.md docs/reference/contributing/agent-guide.md  PASS
git diff --check                                              PASS (line-ending warnings only)
synthetic workflow-trigger guard cases                        PASS
```

Known verification caveat:

- `node scripts/docs/verify-canonical-doc-links.mjs` still fails on
  pre-existing canonical-doc references to tempdocs/missing paths
  outside this tempdoc's scope. Logged to `docs/observations.md` on
  2026-05-08.

## Suggested Verification

Minimum targeted verification for this batch:

```powershell
node scripts/ci/check-root-readme.mjs
node scripts/ci/check-workflow-triggers.mjs
node scripts/docs/verify-canonical-doc-links.mjs
node scripts/docs/llmstxt-generate.mjs --check
npx markdownlint README.md docs/tempdocs/428-repo-entrypoint-security-automation.md
Select-String -Path .github/workflows/*.yml -Pattern '^\s*push\s*:','^\s*pull_request\s*:','^\s*schedule\s*:'
```

Optional broader verification if time permits:

```powershell
.\scripts\gate.ps1
```

Do not run remote workflows automatically unless the user asks; current
project policy is explicit manual dispatch.

## Canonicalization Plan

The durable decision should live in **ADR-0026** because it already owns
manual-only CI triggering and contains the stale installer carve-out.
The implementation should add an append-only amendment rather than
rewriting history:

1. Record the 2026-05-08 decision: every repository workflow must use
   `workflow_dispatch` only.
2. Explicitly retire the prior exception for `build-installer.yml`
   tag pushes.
3. Clarify that the rule is trigger-policy based, not runner-class
   based: GitHub-hosted workflows such as CodeQL are also manual-only.
4. Add a mechanical guard so the ADR is executable policy, not just
   prose.

After the ADR changes, update agent-facing docs that summarize workflow
policy. At minimum, `CLAUDE.md` currently says `build-installer.yml`
retains its tag trigger; that line must change with the workflow.

Estimated implementation effort for this whole tempdoc: about 2 hours
for the straightforward path, plus whatever time verification reveals.

## Follow-Up Decisions

These were validated or partially validated during the same review pass
but are outside this tempdoc's implementation scope:

- **Lightweight automatic PR/push lane.** Explicitly not desired for the
  current policy. Revisit only if the user changes the manual-only
  workflow direction.
- **Coverage/SpotBugs hardening.** Current build logic confirms coverage
  enforcement and SpotBugs failure are opt-in/relaxed. Hardening should
  be a deliberate quality-policy change, not incidental to README repair.
- **`OnlineModeOps` stream cancellation.** Current source still uses
  fire-and-forget streaming futures with no returned cancel handle. The
  old "unbounded executor" doc claim is stale, but the cancellation
  design gap is real.
- **Frontend settings contract duplication.** The exact stale path from
  the review (`stores/systemTypes.ts`) no longer exists, but settings
  types are duplicated between `api/domains/settings.ts` and Lit surface
  files.
- **Large orchestration classes.** `KnowledgeServer`,
  `GrpcIngestService`, `RuntimeActivationService`, and
  `AiInstallService` remain above 1,000 LOC and should be decomposed
  incrementally under separate scoped work.

## Promotion Notes

If this tempdoc ships:

- README behavior is self-evident in `README.md`.
- The README smoke check should be documented in the relevant
  contributing or docs-maintenance reference if it becomes a permanent
  gate.
- If CodeQL is added, update any canonical open-source/security
  readiness docs that currently say CodeQL is absent.

---

# Consolidated artifact (folded 2026-06-09, post-400 hygiene pass)

## Local intent-translator config — dead-code analysis (was 428-artifacts/)

### LocalIntentTranslatorConfig Dead Code Analysis

## Overview

Complete analysis of all `LocalIntentTranslatorConfig` method usage across the JustSearch codebase. This analysis identifies dead code (unused methods/fields) that can be safely removed to reduce configuration complexity.

## Documents in This Directory

### 1. `SUMMARY.txt` ⭐ START HERE
Executive summary with:
- Scan results (19 files, 49 methods analyzed)
- 7 methods with zero callers (100% safe to delete)
- 8 methods that are incomplete features
- Recommended deletion phases
- Quick reference table

**Read this first for a high-level overview.**

### 2. `FINDINGS-LocalIntentTranslatorConfig.md`
Comprehensive detailed analysis including:
- Complete usage breakdown for all 49 methods
- Builder site analysis (EmbeddingService, LocalAiTranslatorService, LocalIntentTranslatorFactory)
- File-by-file reference (13 production files, 11 test files)
- Recommendation summary with categories

**Read this for detailed method-by-method breakdown.**

### 3. `LocalIntentTranslatorConfig-methods.csv`
Spreadsheet-friendly reference with columns:
- Method name
- Caller count
- Status (ACTIVE, LOW_USAGE, UNUSED)
- Recommendation (KEEP, REVIEW, DELETE_GETTER, DELETE_BOTH)
- Notes explaining usage context

**Use this for quick lookup or import into tools.**

### 4. `DEAD-METHODS-USAGE-LOCATIONS.md`
Exact file locations for all dead/questionable methods:
- Lines where methods are defined
- Lines where they're called/set
- Detailed status for each

**Reference this when implementing deletions.**

## Key Findings

### Methods Safe to Delete (0 callers)
1. `translateTemplate()` ❌ — Set but never read
2. `summaryTemplate()` ❌ — Set but never read
3. `summaryReduceTemplate()` ❌ — Set but never read
4. `enableNativeLogging()` ❌ — Never set or read
5. `enablePrefixCaching()` ❌ — Never set or read
6. `embeddingQueueCapacity()` ❌ — Never set or read
7. `vramActivationOverheadBytes()` ❌ — Never set or read

**Total: 7 methods, ~70 lines of code, zero risk**

### Methods to Investigate (1-3 callers, possibly incomplete features)
- `contextPolicy()` — Test-only usage
- `pinnedContextTokens()` — Defined but never used
- Template and remote execution features — Set in builders, never consumed

## What to Delete

### From `LocalIntentTranslatorConfig.java`
- Private fields (7 fields)
- Getter methods (7 methods)
- Validation logic (if any) related to these fields

### From `LocalIntentTranslatorConfig.Builder`
- Setter methods (7 methods)

### From `LocalIntentTranslatorFactory.java`
- Builder setter calls:
  - `.translateTemplate(llm.templateTranslate())`
  - `.summaryTemplate(llm.templateSummary())`
  - `.summaryReduceTemplate(llm.templateSummaryReduce())`

### From `LocalIntentTranslatorConfig.forProduction()` (if applicable)
- Any documentation referring to dead fields

## Verification Method

All findings verified by:
```bash
grep -r "\.methodName()" --include="*.java" modules/ | grep -v "private"
```

Results:
✓ All 7 dead methods confirmed with 0 callers
✓ No false positives
✓ Verification passed on 2026-03-29

## Configuration Methods Used by Production Code

**High usage (6+ callers):**
modelPath (86), backend (25), gpuLayers (33), deadlineMs (32), temperature (17), gpuDeviceId (18), maxSessions (13), topP (12), contextLength (12), summaryPipelineId (11), and many others.

**These should NOT be deleted.**

## Next Steps

1. **Phase 1**: Delete the 7 zero-caller methods (safe, immediate)
   - Update `LocalIntentTranslatorConfig.java` (remove fields and getters)
   - Update `LocalIntentTranslatorConfig.Builder` (remove setters)
   - Update `LocalIntentTranslatorFactory.java` (remove setter calls)
   - Run `./gradlew.bat build -x test` to verify

2. **Phase 2**: Investigate test-only and incomplete features
   - Check if `contextPolicy` feature is actually implemented
   - Check if `pinnedContextTokens` feature is active
   - Delete or implement consuming code

3. **Phase 3**: Optional cleanup of feature stubs
   - Remote execution (allowRemoteExecution, remoteEndpoint, remoteAuthToken)
   - VRAM features (enableVramAutoScale, vramLimitBytes)
   - These represent unfinished feature work; either complete or delete

## Tools Used

- `grep -r` with `--include="*.java"` for codebase scanning
- Manual verification of builder sites
- File-by-file cross-referencing

## Codebase Statistics

- **Total files analyzed:** 19 (8 production, 11 test)
- **Total methods in LocalIntentTranslatorConfig:** 49 getters
- **Methods with 0 callers:** 7 (14%)
- **Methods with 1-3 callers:** 8 (16%)
- **Methods with 4+ callers:** 34 (70%)

## Author

Analysis Date: 2026-03-29
Worktree: `367-dead-code-cleanup`
Branch: `worktree-367-dead-code-cleanup`
