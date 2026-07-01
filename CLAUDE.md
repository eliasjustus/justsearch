<!-- budget: always-loaded; ceiling in scripts/ci/always-loaded-budget.v1.json (ratchets down) — tempdoc 620. -->

# JustSearch — Claude Code Instructions

This file provides guidance to Claude Code when working with code in this repository.

Canonical entry points: `docs/llms.txt` (docs index), `docs/tempdocs/` (active work), `docs/observations.md` (inbox).

## Hard Invariants (Do Not Violate)

1. **Head never touches Lucene** - Delegate all index IO to Worker via gRPC <!-- rule:head-never-touches-lucene -->
2. **Loopback-only network** - Local API binds to 127.0.0.1 only <!-- rule:loopback-only-network -->
3. **No legacy endpoints** - Don't resurrect removed APIs (`/api/search`, `/api/settings`) <!-- rule:no-legacy-endpoints -->
4. **Verify, don't guess** - Use `/api/debug/state` and `/api/health` for lifecycle, `/infra/capabilities` for `host.*` sub-API contract versions; not log grepping <!-- rule:verify-dont-guess -->
5. **Frontend is Lit, not React** - Canonical docs describe the Lit/`shell-v0` web-components stack; the React stack is retired (ADR-0032) <!-- rule:frontend-stack-is-lit -->
6. **No per-language search levers** - Search analysis is locale-invariant (ICU + NFC + lowercase); the engine is multilingual by construction via the multilingual model stack, with no per-language analyzer/field/stopwords/spelling-dictionary/curated-synonym artifact to author or maintain (ADR-0043, tempdoc 581) <!-- rule:language-agnostic-analysis -->

## Agent Discipline

### Explore Before Implementing <!-- rule:explore-before-implementing -->

Before new code, check for existing infrastructure:

1. **The module you're working in** for helpers and patterns.
2. **Related modules** — how other controllers handle errors; how neighboring services are structured.
3. **`docs/llms.txt`** and canonical docs for documented patterns.

Failure mode to avoid: creating a new utility function when an identical one exists two packages over. This is the single most common agent mistake in this codebase.

**Before authoring a new *representation* of existing data** (a record/type/projection/span/schema that describes something already modelled — e.g. "what the search pipeline did"), check the relevant register and decide **projection vs fork**: a projection derives from the one canonical source; a fork is a second authority that will drift. For search-execution, the register is `governance/execution-surfaces.v1.json` (the `execution-surface` gate fails the build on an unregistered referencer of the canonical `SearchTrace`). This is the discovery step that prevents the representation-drift class (tempdoc 553); prose-tier (~70%) — the gates are the guarantee. Honest limit: a register only covers *declared* concepts, so judgment still applies for genuinely new ones (and per AHA, only unify what shares a reason to change — don't over-DRY scaffolding).

### Fix Root Causes, Not Symptoms <!-- rule:fix-root-causes-not-symptoms -->

**Never do any of the following to resolve a build or test failure:**
- Comment out or delete the failing code
- Weaken an assertion (e.g., `assertEquals` → `assertNotNull`, or adding `try/catch` around a test)
- Delete or `@Disabled` a failing test
- Add `@SuppressWarnings` or `// noinspection` to silence a warning
- Broaden a catch clause (e.g., `catch (IOException e)` → `catch (Exception e)`)
- Remove validation or error handling that's "in the way"

**If a test fails after your changes**, the test is probably right and your code is wrong. Investigate the test's intent before assuming it's outdated. If you genuinely believe the test is wrong, explain why and ask the user before modifying it.

### Verify Your Work <!-- rule:verify-your-work -->

After implementing a change, confirm it actually works before moving on. Run compilation and relevant module tests — do not rely on "it should work."

- **At minimum**: `./gradlew.bat build -x test` (compilation) + `./gradlew.bat :modules:<module>:test` for affected modules.
- **After multi-module changes**: `./gradlew.bat test` for the full unit test suite.
- **After frontend changes** (`modules/ui-web`): `cd modules/ui-web && npm run typecheck && npm run test:unit:run`
- **For visual verification**: `jseval ui-shot <step>` (load `/ui-check` skill for full reference).

Do not declare a task complete if the build is broken or tests are failing.

**Use every verification tier available to you, including the LLM.** <!-- rule:use-every-verification-tier --> `ai_activate` loads the inference runtime in ~11s. When verifying AI-facing features (chat surfaces, RAG, conversation shapes), do not stop at `AI_OFFLINE` and declare "verified up to the LLM boundary." Load the model, send a real query, and confirm the full response renders correctly. Compile + unit tests verify code correctness; live-stack API tests verify plumbing; but only an end-to-end test with a running model verifies feature correctness. The general principle: before declaring a verification tier unavailable, check whether you have a tool that provides it. Handle: `ai-offline-isnt-a-wall` (see `docs/reference/contributing/agent-postmortems.md`).

**Audit-driven fixes need a runnable test, not just a passing audit.** <!-- rule:audit-driven-fixes-need-test --> When a subagent audit concludes "X is the only blocker for Y" (or any similar narrow lifecycle claim — "field F is/isn't rebuilt", "state machine accepts/rejects T", "method M is the sole consumer"), the fix is not complete until a regression test exercising Y is green. Static audits are hypotheses; the test is truth. Handle: `audit-without-test` (see `docs/reference/contributing/agent-postmortems.md` §1).

**Critical-analysis pass is required for non-trivial changes.** <!-- rule:critical-analysis-pass --> After implementing a change that modifies control flow, adds/removes behavioral code, or was implemented based on a subagent audit, perform a critical-analysis pass before declaring complete. Re-examine each change for: (a) wrong-gate / wrong-flag mistakes — does the gate actually fire in the target scenario? `grep` the set-site, don't just trust the symbol exists; (b) audit conclusions that weren't independently verified — did you re-read the code the subagent's claim depends on? (c) test precision — does the assertion distinguish "passes for the right reason" from "passes for a wrong reason"? Handles: `wrong-gate` (§2), `audit-without-test` (§1) in `docs/reference/contributing/agent-postmortems.md`.

### Interrogate Results <!-- rule:interrogate-results -->

When an experiment, benchmark, test, or diagnostic produces a result, investigate what caused it before acting on it or reporting it as a finding. The result is data — the cause is what matters.

- **Improvements**: A benchmark shows 2x speedup. Was it your change, or a warm cache, different baseline, or uncontrolled variable? Establish the causal link.
- **Regressions**: A metric dropped 15%. Is the measurement sound? Did conditions change between runs? Don't report a regression without understanding what caused it.
- **Expected results**: A test fails in the way you predicted, or a metric matches your hypothesis. This is the most dangerous case — confirmation feels validating, so there's no instinct to dig deeper. Verify the result happened *for the reason you think*, not a different one.

Failure mode to avoid: treating correlation as causation. The experiment produced the number you expected, so you move on — without establishing that your change was the reason, or that the result means what you think it means.

### Structural Defects Don't Need Repeat Incidents <!-- rule:structural-defects-no-repeat -->

YAGNI applies to speculative abstractions, not to known structural defects. One documented silent bug proves the bug-class, not just the bug — you do not need a second instance to endorse the fix. If a tempdoc argues for a structural improvement with a proof-by-example, critique the argument's substance (wrong diagnosis, wrong mechanism, wrong scope) — not urgency.

**Do not re-introduce "wait-for-more-evidence" triggers under different names.** These are all the same failure mode:
- "low historical rate of X"
- "wait for Y to land first, then the design will be informed"
- "start with a cheaper intermediate step (test, guardrail, Alt-N)"
- "bridging measure until there's more motion"

If the user tells you to disregard a tempdoc's own trigger list, do not invent new ones. A correctness argument is not a cost-benefit argument — don't convert one into the other unless asked.

### Tempdocs Are Dated History, Not Current Truth <!-- rule:tempdocs-are-dated-history -->

`docs/tempdocs/` is append-only design history, not canonical truth — a tempdoc reflects its writing date, and newer tempdocs and shipped code supersede older ones. Newer tempdocs have higher numbers; always check the highest-numbered tempdoc first to gauge how stale an older one really is. Before trusting a tempdoc's claim as current, check its frontmatter (`status`/`created`/`updated`) and verify against `main` + canonical docs. (`verify-don't-guess`, applied to docs.)

### Tempdoc Is Your Contract <!-- rule:tempdoc-is-your-contract -->

When you are assigned a tempdoc, every item marked for implementation is work the user has already deemed necessary. You do not get to unilaterally decide that remaining items are "not worth it", "too difficult", "low priority", or "diminishing returns". The user made that judgment when the tempdoc was written.

- **Implement every item** in the tempdoc unless the user explicitly tells you to skip it.
- **If you believe an item is infeasible**, explain why and ask the user — do not silently skip it or mark the tempdoc as complete.
- **Do not summarize remaining work and suggest closing.** If there are unchecked items, there is remaining work.
- **A tempdoc is complete when all its items are implemented**, not when you feel the most impactful ones are done.

### Stay Focused on Your Assigned Work <!-- rule:stay-focused-on-assigned-work -->

When asked "what should we do next?", consult the active tempdoc for remaining items first. Propose those before suggesting new work.

- **Do not propose switching to a different tempdoc** unless the current one is fully complete.
- **If nothing is left on the current tempdoc**, say so explicitly and let the user decide.
- **Parallel agents share `main`** — untouched-code reformatting causes merge conflicts with other worktrees, so keep diffs scoped to your task.

### Log Pre-Existing Issues, Don't Fix Them <!-- rule:log-pre-existing-issues -->

When you notice an issue outside your current task's scope — pre-existing bug, dead code, stale comment, broken-but-unrelated test, config drift — log one line to the inbox and keep working. This resolves the tension in *Stay Focused*: noticed-but-out-of-scope findings have a home instead of becoming scope creep or knowledge loss.

- **Log via the per-session inbox helper** (618 Seam C — writes to *your* shard under `docs/observations.d/`, never the shared file, so a neighbour's commit can't wipe your note):

  ```bash
  node scripts/agent-analytics/note-observation.mjs "<description> — \`<file:line>\`"
  ```

  It resolves your session id and stamps the date; the shard commits with your work. `fold-observations.mjs --apply` later reconciles shards into the `## Inbox` of `docs/observations.md`.
- **Do not investigate.** Record and return to your task.
- Issues caused by your current change don't belong here — fix those.

### Before Appending to CLAUDE.md or `.claude/rules/` <!-- rule:before-appending-to-rules -->

This file is loaded every session. Anthropic's published guidance: *"For each line, ask: 'Would removing this cause Claude to make mistakes?' If not, cut it. Bloated CLAUDE.md files cause Claude to ignore your actual instructions!"*

Before appending a new rule or lesson, run the gate:

1. **Broad applicability** — would a fresh agent on a *different* task need this? If it's specific to one slice / one module / one failure mode, it's not a CLAUDE.md rule. Candidates by destination:
   - Cross-cutting platform constraint → `.claude/rules/agent-lessons.md`
   - Named reference case → `docs/reference/contributing/agent-postmortems.md` (one paragraph + one citation)
   - Domain workflow → existing skill body or new `/skill-name`
   - Out-of-scope finding → `docs/observations.md` Inbox (one line, don't fix here)
2. **Already-said test** — grep CLAUDE.md and `.claude/rules/` for the keyword. If the concept is already covered, edit the existing line; don't add a duplicate.
3. **Enforcement question** — if the rule is "must" / "never" / "always" load-bearing, the right home may be a hook or an ArchUnit test, not more prose. Anthropic: *"hooks enforce rules at 100%"* vs ~70% adherence for prose rules.

If the rule passes all three gates, add it to the smallest scope that holds it.

When you do add a `must`/`never` rule, name its predictable evasion inline — as `structural-defects-no-repeat` (the "wait-for-more-evidence" aliases) and `fix-root-causes-not-symptoms` (its 6 anti-patterns) already do. Agents skip rules by rationalizing in the moment, so pre-empting the specific excuse raises adherence more than restating the rule.

### Ask When Uncertain <!-- rule:ask-when-uncertain -->

- **Unclear tempdoc requirements**: If an item can be interpreted multiple ways, ask which.
- **Architectural choices**: Multiple valid placements (which module owns a responsibility) — present options, let the user decide.
- **Cross-module impact**: Flag before proceeding if your change could break callers in other modules.

### Delegating to Subagents (Agent Tool) <!-- rule:delegating-to-subagents -->

Subagents do **NOT** inherit this file, `.claude/rules/*.md`, or any of the parent's PreToolUse/PostToolUse hooks. Verified via primary-source subagent prompts (Piebald-AI prompt-leak repo) and live introspection probe. When delegating non-trivial work, briefing in the Agent prompt is mandatory, not optional. Make the brief self-contained — the plan, the tests/acceptance criteria that define "done", and the relevant constraints — since the subagent inherits none of this file. Brief subagents to **cite primary-source `file:line` evidence by default** for any load-bearing claim they return, so re-verification is a glance rather than a re-derivation — subagent findings are a starting point, not a result, and confidently-wrong claims that mis-scope work are a recurring cost (tempdoc 618 §6; the `audit-without-test` reference case).

**Implications when you dispatch via the `Agent` tool:**

- **Destructive git commands are not blocked.** A subagent can run `git reset --hard`, `git push --force` from worktrees, `git clean -f`, and `git checkout` in the main worktree. Do not delegate destructive git operations.
- **No repeat-guard, no build-counter, no Read auto-limit.** Subagents can loop on identical calls, thrash on builds, or read large files unbounded. Choose subagent tasks accordingly: research and exploration are well-suited; long iterative refactors are not.
- **No PostToolUse hints.** A subagent editing `SSOT/catalogs/fields.v1.json` will not be reminded about the dual-copy sync. If a subagent edits anything in `SSOT/catalogs/`, `docs/{explanation,reference,how-to,decisions}/`, `build.gradle.kts`, or `modules/ui-web/src/`, follow up after its return with the relevant regen step yourself.
- **`isolation: "worktree"`** branches from `origin/main`, not the parent's HEAD ([claude-code#50850](https://github.com/anthropics/claude-code/issues/50850)). A subagent verifying against an isolated worktree may pass while the parent's view fails. Prefer non-isolated subagents unless the work genuinely needs an independent worktree.

Good subagent tasks: open-ended research, parallel codebase exploration, second-opinion code review, batch read-only audits.

Risky subagent tasks: anything that writes shared state, runs migrations, edits `.gitignore`, modifies CI config, or could leave the worktree in an inconsistent state.

## Architecture

| Process | Module | Entry Point |
|---------|--------|-------------|
| **Head** (UI Host) | `modules/ui` | `HeadlessApp.java` |
| **Body** (Worker) | `modules/indexer-worker` | `IndexerWorker.java` |
| **Brain** (Inference) | `modules/app-inference` | Manages `llama-server.exe` |

Full architecture: `docs/explanation/01-system-overview.md`. Key API endpoints: `docs/reference/api-contract-map.md`.

## Key Modules

- `modules/ui` - UI Host backend (Head process, Javalin REST API)
- `modules/ui-web` - Frontend (TypeScript, Lit web components, Vite)
- `modules/indexer-worker` - Knowledge Server (Body process, Lucene owner)
- `modules/adapters-lucene` - Lucene search integration
- `modules/ai-backend` - Backend abstractions and local translator support
- `modules/gpu-bridge` - GPU/VRAM detection and hardware capability helpers
- `modules/prompt-support` - Prompt templates and prompt/reasoning support utilities
- `modules/app-inference` - Online llama-server lifecycle
- `modules/ort-common` - ORT session infrastructure (OrtSessionAssembler, SessionHandle, NativeSessionHandle, OrtCudaHelper, OrtCudaStatus, OnnxSessionCache, ModelManifest)
- `modules/shell` - Tauri desktop shell

## Quick Commands

| Goal | Command |
|------|---------|
| Compile | `./gradlew.bat build -x test` |
| Format (run first) | `./gradlew.bat spotlessApply` |
| Unit tests | `./gradlew.bat test` |
| Single module | `./gradlew.bat :modules:<module>:test` |
| Frontend typecheck + tests | `cd modules/ui-web && npm run typecheck && npm run test:unit:run` |
| Pipeline profiling (full lifecycle) | `cd scripts/jseval && python -m jseval run --start-backend --clean --pipeline --json` |
| Hot-reload after edit | `reload` (requires `hotReload: true` on dev-stack start) |
| Pre-merge gate | `./gradlew.bat build -x test` from main before merge |

Build fails on PMD/Spotless violations — run `spotlessApply` first.

Public hosted `CI` runs on PRs, pushes to `main`, and manual dispatch ([ADR-0044](docs/decisions/0044-public-hosted-ci-fact-lanes.md)); self-hosted/specialty workflows remain manual. Local-first verification stays primary. For CI triage load `/ci-triage`; for profiling/live stack load `/jseval` and `/dev-stack`.

Pre-merge script checks — run the check whose **subject** you edited. Commands: `node scripts/ci/<name>.mjs` or `node scripts/governance/run.mjs --gate <id> --mode gate`.

| Edited subject | Check(s) |
|---|---|
| `.github/workflows/*.yml` · root README | `check-workflow-triggers` · `check-root-readme` |
| root `CLAUDE.md` Pre-merge table | `check-premerge-table` |
| repo history publication settings (ADR-0045) | `check-repo-history-policy` |
| PR title/body as public squash message | `preview-squash-message` |
| `contracts/**` | `--gate wire` |
| new `<dataDir>/runtime/` file | `check-runtime-manifest-closure` |
| `SSOT/catalogs/**` · analyzers schema · `adapters-lucene/**` | `check-language-agnostic-analysis` |
| new tempdoc/changeset (cross-worktree) | `check-tempdoc-numbers` |
| indexing-job lifecycle surfaces | `--gate operation-surface` |
| `CoreSurfaceCatalog.java` / surface `altitude` | `--gate surface-altitude` |
| `governance/logic-seams.v1.json` or a registered seam | `check-logic-seams --mode gate` |
| `RegistrySnapshotExporter` / `LiveWitness` | `check-live-witness` |
| guard-string register (`execution-surfaces`/`operation-surfaces`) | `--gate register-guard-resolution` |
| `LifecycleReasonCode.java` / `readinessNotice.ts` | `check-readiness-reason-codes` |
| `SearchReasonCode.java` / `searchTraceExplain.ts` | `check-search-degradation-reason-codes` |
| `StoreCatalog.java` · store construction sites | `check-store-recoverability` |
| `UnifiedChatView.ts` / `CoreConversationShapeCatalog.java` | `check-intent-tier-coverage` |
| **`modules/ui-web/src/**`** (ui-web gate set) | `check-presentation-purity` · `check-observed-state-collapse` · `check-theme-token-closure` · `gen-token-names --check` · `strip-token-fallbacks --check modules/ui-web/src` · `check-color-tokens` · `check-a11y-closure` · `check-controls-a11y` · `check-adaptive-closure` · `check-layout-purity` · `check-surface-composition` · `check-message-single-model` · `check-run-renderers` · `check-inflight-liveness` · `check-composition-surfaces` · `check-declared-surfaces` · `check-live-channels` · `check-contrast-matrix` · `check-accent-as-text`; `--gate ambient-purity,style-literal-ratchet,atom-fork-ratchet,modality-contract,transient-arbitration,modal-arbitration` |
| `modules/ui-web/src/shell-v0/**` (also) | `check-steering-arbitration` · `check-search-issuance` · `check-verdict-derivation` · `check-message-classes` · `check-capability-availability` · `check-realized-capability` |
| `shell-v0/views/**` (also) | `check-surface-task-state-retention` |
| ui-shot harness · new RAIL surface | `check-ui-step-coverage` |

## Common Pitfalls

| Pitfall | Solution |
|---------|----------|
| Stale lockfiles after dep changes | `lockfile-hint` flags `build.gradle.kts` edits — regenerate: `./gradlew.bat --no-configuration-cache resolveAndLockAll --write-locks` (`/lockfile`) |
| Windows env vars unreliable | Pass config via `-D` system properties |
| Windows memory pressure | Use `-PskipWebBuild=true` for backend-only runs |
| Flaky IPC tests | Use state polling (`awaitPort`), not `Thread.sleep()` |
| Large files in `models/` | `*.onnx`, `*.gguf`, `*.int8-backup`, `*.fp32-backup`, and `*onnx_data` are Git LFS-tracked. **Do not gitignore model files.** Runtime caches (`*.optimized`, `*.opt-meta`, `*.sha256`) are already excluded by `models/.gitignore`. |
| Stale index after field changes | Adding fields to `fields.v1.json` or extraction logic in `IndexingDocumentOps` does NOT update existing documents. Existing indices must be rebuilt: `jseval run --reset` (eval mode) or `jseval run --start-backend --clean`. Test corpora indexed before your change will silently lack the new fields. |
| Classpath catalog drift | The `ssot-catalog-sync` gate (CI, ~100%) + `ssot-hint` enforce the dual-copy sync: `SSOT/catalogs/fields.v1.json` ↔ `adapters-lucene/src/main/resources/SSOT/catalogs/fields.v1.json` (the classpath copy production loads). Load `/ssot-catalog`. |
| Schema/fixture drift after record changes | After adding/renaming fields on API records (`app-api`), run `./gradlew.bat :modules:app-api:updateSchemas` (regenerates schemas + cross-language fixtures); `test-edit-hint` re-surfaces the affected test. Full recipe: `/api-record` or `docs/reference/contributing/common-workflows.md`. |
| Dev stack runs stale jar after Java edits | The MCP `justsearch_dev_start` doesn't always re-install the worker dist when upstream Gradle tasks report UP-TO-DATE. After editing Java in `modules/app-services/`, `modules/app-agent-api/`, or other modules on the head process classpath, run `./gradlew.bat :modules:ui:installDist` explicitly before `dev_start` / restart so the jar in `modules/ui/build/install/ui/lib/` is fresh. Symptom: wire payload reflects the previous build despite a successful `gradlew build`. Discovered tempdoc 511-followup Track E live-verify. |

## Skills (load via `/skill-name`)

Available skills are surfaced in your session via system-reminders (names + descriptions); load the matching skill **before** domain work (sources: `.claude/skills/<name>/SKILL.md`). The one rule that injected list does **not** carry: the two **registers** — `/search-quality` and `/inference-runtime` — must be loaded before the work *and updated before you close your tempdoc*. (tempdoc 620 Move 1: the per-skill descriptions were evicted as a fork of the harness-injected list.)

## Parallel Agents

Up to 3-4 agent sessions run concurrently, each in its own **git worktree** under
`.claude/worktrees/<name>` (branch `worktree-<name>`); the main checkout `F:\JustSearch` stays on `main`.

Setup: `EnterWorktree { name: "..." }` in-session, or `claude --worktree <name>` for a new session. Subagent isolation: `isolation: "worktree"` on the Agent tool.

Dev stack: shared (one at a time). Coordinate via user. Merge target: `main`.

Full rules — destructive-command list, worktree lifecycle, merge workflow: `.claude/rules/branch-safety.md`.

## Pointers

- **Full agent guide**: `docs/reference/contributing/agent-guide.md`
- **Docs index**: `docs/llms.txt`
- **Active work**: `docs/tempdocs/`
- **Out-of-scope findings**: `docs/observations.md` (Inbox section)
- **Canonical docs** (must not drift): `docs/explanation/`, `docs/reference/`, `docs/how-to/`, `docs/decisions/`
- **Reference cases by handle**: `docs/reference/contributing/agent-postmortems.md`
- **Contribution recipes**: `docs/reference/contributing/common-workflows.md` (relocated from always-loaded; path-triggerable recipes also push via `governance/consult-register.v1.json`)
