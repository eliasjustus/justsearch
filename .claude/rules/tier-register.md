<!-- budget: always-loaded; ceiling in scripts/ci/always-loaded-budget.v1.json (ratchets down) — tempdoc 620. -->

# Prose-rule Enforcement Tier Register

**Seeded** by tempdoc 530 §The meta-loop (2026-05-20). The meta-loop *gate*
that enforces this register's completeness — `prose-tier-register` — is now
**live** (`scripts/governance/gates/prose-tier-register/enforcer.mjs`, registered
in `governance/registry.v1.json`); see "What the meta-loop gate enforces" at the
foot of this file for what it checks.

## Format

Each row names a load-bearing rule from `CLAUDE.md` or `.claude/rules/*.md`
and records which tier enforces it. Tiers:

| Tier | Enforced at | Adherence (Anthropic guidance) |
|---|---|---|
| `gate` | CI pre-merge via a discipline-gate kernel rule (tempdoc 530) | ~100% |
| `hook` | PreToolUse / PostToolUse **blocking** hooks (`bash-guard.mjs`, `repeat-guard`, etc.) | ~100% |
| `hook-hint` | PreToolUse / PostToolUse **non-blocking** hint hook — *delivers* a rule at its moment of relevance (raises salience), does not block (`compact-restore`, `tempdoc-age-hint`) | ~85% |
| `archunit` | ArchUnit test in the relevant module's `src/test/` | ~100% |
| `lint` | PMD / Spotless / ESLint / TS / static-analysis tool | ~100% |
| `prose-only` | Documentation alone; agent discretion | ~70% |

Every rule's row also names *what concretely catches violations*. For
`prose-only` rows, the reason names why no automation is feasible (or
why prose-tier is intentionally the right choice).

## CLAUDE.md — Hard Invariants

| # | Slug | Rule | Tier | Resolves to | Catches violations via |
|---|---|---|---|---|---|
| 1 | `head-never-touches-lucene` | Head never touches Lucene | `archunit` | `archunit:AppServicesWorkerGuardrailsTest` | ArchUnit module-dependency boundaries; Lucene types are not on Head's classpath |
| 2 | `loopback-only-network` | Loopback-only network — Local API binds to 127.0.0.1 only | `archunit` | `archunit:UiApiGuardrailsTest` | `LocalApiServer` binding policy + UI API guardrails test |
| 3 | `no-legacy-endpoints` | No legacy endpoints — Don't resurrect removed APIs (`/api/search`, `/api/settings`) | `lint` | (Gradle task; no marker grammar yet) | `docsApiDriftCheck` Gradle task (root `:verify`) enforces |
| 4 | `verify-dont-guess` | Verify, don't guess — use `/api/debug/state` and `/api/health` for lifecycle | `prose-only` | — | Agent discipline; no mechanical check is feasible for "did you guess." Hooks redirect bare `grep` to Grep tool but can't tell whether output was interpreted vs. fabricated |
| 33 | `frontend-stack-is-lit` | Frontend is Lit, not React — canonical docs describe the Lit/`shell-v0` stack; React is retired (ADR-0032) | `lint` | (CI lint; no marker grammar) | `scripts/docs/check-frontend-stack-claims.mjs` fail-closed docs-lint (wired in `.github/workflows/docs-lint.yml`) flags present-tense `React`/`.tsx`/`Zustand` claims in `docs/{explanation,reference,how-to}` **plus the public surface (`docs/business/**` + root `README.md`)** (tempdoc 579; scope extended to the public blast radius per tempdoc 650 §M1) |
| 34 | `language-agnostic-analysis` | No per-language search levers — analysis is locale-invariant; no per-language analyzer/field/stopwords/spelling-dictionary/curated-synonym artifact (ADR-0043 / tempdoc 581) | `lint` | (CI lint; no marker grammar) | `scripts/ci/check-language-agnostic-analysis.mjs` (581/ADR-0043) fails the build on a non-`*` locale analyzer, a provider outside `{icu, keyword}`, a `content_<lang>` field, a non-empty per-language synonym file under `SSOT/catalogs/`, or a `content_<lang>` query literal; the analyzer-provider `enum` in `analyzers-catalog.schema.json` is the rung-1 schema half. Same category as rule #3 (custom CI check, not a kernel gate) |

## CLAUDE.md — Agent Discipline

| # | Slug | Rule | Tier | Resolves to | Catches violations via |
|---|---|---|---|---|---|
| 5 | `explore-before-implementing` | Explore Before Implementing — check for existing infrastructure | `prose-only` | — | Agent discipline; investigator-judgment, no mechanical check |
| 6 | `fix-root-causes-not-symptoms` | Fix Root Causes, Not Symptoms (the 6 named anti-patterns) | `prose-only` | — | Code review for the semantic forms (weakened assertion, broadened catch, deleted code). The `@Disabled` + `// noinspection` subset is now ratcheted by `scripts/ci/check-suppression-ratchet.mjs` (620 Part V) — a NEW one beyond baseline fails the lint; the rest stay prose |
| 7 | `verify-your-work` | Verify Your Work — run `./gradlew.bat build -x test` + module tests | `prose-only` | — | Agent discipline; CI catches but locally is honor-system |
| 8 | `use-every-verification-tier` | Use every verification tier available, including the LLM | `prose-only` | — | Agent discipline; `ai_activate` exists but is opt-in |
| 9 | `audit-driven-fixes-need-test` | Audit-driven fixes need a runnable test | `prose-only` | — | Code-review catch; structural test coverage couldn't enforce |
| 10 | `critical-analysis-pass` | Critical-analysis pass required for non-trivial changes | `prose-only` | — | Code-review catch; per-commit pass is judgment-based |
| 11 | `interrogate-results` | Interrogate Results — investigate causation, not correlation | `prose-only` | — | Agent discipline; experiment-design is judgment |
| 12 | `structural-defects-no-repeat` | Structural Defects Don't Need Repeat Incidents — no "wait-for-more-evidence" framing | `prose-only` | — | Discussion-level; argument-quality, not measurable |
| 13 | `tempdoc-is-your-contract` | Tempdoc Is Your Contract — implement every item | `prose-only` | — | Tempdoc-closure review; "skipped item" detection would need NLP |
| 14 | `stay-focused-on-assigned-work` | Stay Focused on Your Assigned Work | `prose-only` | — | Reviewer catches scope creep at PR time |
| 15 | `log-pre-existing-issues` | Log Pre-Existing Issues, Don't Fix Them — append to `docs/observations.md` | `prose-only` | — | Agent discipline |
| 16 | `before-appending-to-rules` | Before Appending to CLAUDE.md or `.claude/rules/` — run the gate | `gate` | `gate:prose-tier-register` | `prose-tier-register` discipline gate enforces this register's completeness (tempdoc 530 §Meta-loop) |
| 17 | `ask-when-uncertain` | Ask When Uncertain | `prose-only` | — | Agent judgment |
| 18 | `delegating-to-subagents` | Delegating to Subagents — brief inline, no destructive git delegation | `prose-only` | — | Cannot mechanically inspect subagent prompts; agent discipline. (`bash-guard` gives **no** subagent coverage — parent hooks don't fire in subagents, so the inline brief is the only control there) |
| 32 | `tempdocs-are-dated-history` | Tempdocs Are Dated History, Not Current Truth — check highest-numbered tempdoc + frontmatter, verify against `main` before trusting | `hook-hint` | `hook:tempdoc-age-hint.mjs` | `tempdoc-age-hint.mjs` (PostToolUse Read, 620 Part V) age-stamps every `docs/tempdocs/**` Read with date/status + newer-count — a non-blocking delivery assist (~85%); staleness is surfaced but acting on it stays judgment |

## .claude/rules/branch-safety.md

| # | Slug | Rule | Tier | Resolves to | Catches violations via |
|---|---|---|---|---|---|
| 19 | `never-checkout-in-main` | Never `git checkout` in the main worktree | `hook` | `hook:bash-guard.mjs` | `bash-guard.mjs` PreToolUse hook blocks |
| 20 | `never-share-worktree` | Never share a worktree between sessions | `prose-only` | — | No mechanical detection of session ownership |
| 21 | `one-branch-per-worktree` | One branch per worktree | `prose-only` | — | Git refuses to check out the same branch in two worktrees (structural) |
| 22 | `after-compaction-verify` | After compaction, verify worktree + branch | `hook-hint` | `hook:compact-restore.mjs` | `compact-restore.mjs` writes the worktree dir + branch into the post-compaction state block (620 Part V/VI) — a non-blocking delivery assist (~85%): raises salience, the agent still acts on a mismatch |
| 23 | `never-destructive-git-in-main` | Never run destructive git in main worktree | `hook` | `hook:bash-guard.mjs` | `bash-guard.mjs` blocks `git reset --hard`, `git clean -f`, `git restore .` |
| 24 | `never-delete-untracked-in-main` | Never delete files in main worktree that you didn't create | `prose-only` | — | No mechanical ownership signal |
| 25 | `never-force-push` | Never `git push --force` to main/master | `hook` | `hook:bash-guard.mjs` | `bash-guard.mjs` blocks `--force` everywhere |
| 26 | `pre-merge-gradle-build` | Pre-merge `./gradlew build -x test` required | `prose-only` | — | CI catches at PR time, but local-first discipline is honor system |
| 35 | `verify-worktree-base` | Always verify a new worktree's base contains the expected work before coding | `prose-only` | — | Agent discipline; `worktree.baseRef:"head"` is the by-construction half (config, not a gate), and manual `git worktree add` ignores it — no mechanical check that the base matched task intent (tempdoc 618 §1) |
| 36 | `docs-ride-along` | Publishing docs-only changes: tempdoc/observations edits ride-along with their code PR or batch; canonical-doc updates may stand alone (ADR-0045 axis-2 / tempdoc 653) | `hook-hint` | `hook:docs-granularity-hint.mjs` | `docs-granularity-hint.mjs` (PreToolUse Bash `git push`) fires when a branch's whole diff vs `origin/main` is `docs/tempdocs/**` / `docs/observations*` only, delivering the ride-along/batch convention — non-blocking (~85%); canonical-doc-only and docs+code branches intentionally don't trigger |

## .claude/rules/agent-lessons.md

| # | Slug | Rule | Tier | Resolves to | Catches violations via |
|---|---|---|---|---|---|
| 27 | `subagents-no-inheritance` | Subagents do NOT inherit CLAUDE.md / `.claude/rules/` — brief inline | `prose-only` | — | Platform constraint (Anthropic limitation); brief-inline is judgment. But the *baseline* brief (Hard Invariants live-projected from CLAUDE.md + platform + risk profile) IS auto-injected by the `subagent-guide` SubagentStart hook (620 Part V); only the task-specific brief is honor-system |
| 28 | `parent-hooks-dont-fire-in-subagents` | Parent session hooks don't fire in subagents — limit delegation scope | `prose-only` | — | Platform constraint |

## .claude/rules/slice-execution.md

| # | Slug | Rule | Tier | Resolves to | Catches violations via |
|---|---|---|---|---|---|
| 29 | `bidirectional-pass` | Bidirectional pass (pre-impl spec-tighten + post-impl critical-analysis) | `prose-only` | — | Methodology discipline; judgment |
| 30 | `independent-reviewer-required` | Slice closure: an independent (reviewer ≠ committer) review + live verification | `prose-only` | — | Was `gate`-enforced (tempdoc 550 thesis V); the `independent-review` gate was **retired** (tempdoc 563), so honor-system now — code review catches at PR time |
| 31 | `ux-audit-closure` | Presentation-authority closure: an independent, measured, live-verified whole-screen UX audit (auditor ≠ committer) | `prose-only` | — | Was briefly `gate`-enforced (tempdoc 559 §6-7); the `ux-audit-closure` gate was **retired** (tempdoc 563), so honor-system now — independent measured audit is expected practice, not build-enforced |

## .claude/rules/context-efficiency.md, hooks-reference.md

These files contain procedures and operational documentation rather than
load-bearing rules. They are not subject to the meta-loop register; their
correctness is validated by the procedures they document (e.g., the
gradle-task names they reference either exist or don't).

(`common-workflows.md` was relocated out of the always-loaded layer to
`docs/reference/contributing/common-workflows.md` — tempdoc 620 Phase 2 — and its
path-triggerable recipes now deliver just-in-time via `governance/consult-register.v1.json`.)

## What the meta-loop gate enforces

The `prose-tier-register` gate (`scripts/governance/gates/prose-tier-register/`)
now enforces all of the following — verbatim alignment with the tempdoc 530
§Meta-loop spec after the Pass-5 closure:

1. **Register internal consistency** — every row parses; every tier value is
   one of the tiers in the Format legend above (the `ALLOWED_TIERS` set in
   `truth-table.mjs`); numeric # is unique.
2. **Rule-file ↔ register anchor cross-validation** — every
   `<!-- rule:<slug> -->` anchor in scoped rule files corresponds to a
   `Slug` value here. New anchors without a register row fail with
   `prose-tier-register/new-untagged-rule`. Orphan register rows fail with
   `prose-tier-register/orphan-register-row`.
3. **Sentence-level scan** — every `must / never / always / do not / you must`
   sentence in scoped rule files falls inside an anchored section. New
   unanchored sentences fail with `prose-tier-register/untagged-sentence`.
4. **`gate:<id>` reference resolves** — every `gate:` marker in the
   `Resolves to` column resolves to a real gate id in
   `governance/registry.v1.json`.
5. **`hook:<filename>` reference resolves** — every `hook:` marker resolves
   to a file under `scripts/agent-analytics/hooks/`.
6. **`archunit:<class>` reference resolves** — every `archunit:` marker
   resolves to a `**/src/test/**/*<class>.java` file (suffix match).
7. **Missing `Resolves to` marker** — every row tagged `gate` / `hook` /
   `archunit` carries at least one `Resolves to` marker. Net-new rows
   without a marker fail with `prose-tier-register/missing-resolves-to`.
8. **Tier-change discipline** — a row's tier cannot change vs. the baseline
   ref without a declared `gates/prose-tier-register/.changesets/<id>.md`
   classified as `tier-change` / `new-rule-registered` / `rule-retired` /
   `emergency-override`. Changesets that declare these classifications
   require a `tempdoc:` or `adr:` frontmatter field.

Scope of files scanned: `CLAUDE.md` plus every `.md` under `.claude/rules/`,
minus the explicit exclude set (`tier-register.md`, `context-efficiency.md`,
`hooks-reference.md`, and the auto-generated `compaction-state.md` — these are
operational/ephemeral docs, not load-bearing rules).

### Workflow

**Adding a rule.** Write the rule inline in the appropriate rule file with a
trailing `<!-- rule:<slug> -->` anchor. Add a row to this register with the
matching slug, the correct tier, and (for `gate`/`hook`/`archunit` tiers) a
`Resolves to` marker. Author a `gates/prose-tier-register/.changesets/<id>.md`
classified as `new-rule-registered` with a `tempdoc:` or `adr:` reference.

**Changing a rule's tier** (e.g., new automation now exists). Update the row
+ marker. Author a `tier-change` changeset with justification.

**Retiring a rule.** Remove the anchor + remove the register row. Author a
`rule-retired` changeset with justification.

## See also

- `docs/tempdocs/530-class-size-ratchet-automation.md` — the design tempdoc
- `governance/registry.v1.json` — the gate registry the future meta-loop gate will cross-validate against
- `scripts/agent-analytics/hooks/` — the hook handlers
- `.claude/rules/hooks-reference.md` — what each hook does
