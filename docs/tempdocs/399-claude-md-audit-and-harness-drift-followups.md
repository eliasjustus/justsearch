---
title: "399: CLAUDE.md System-Prompt Alignment and Harness Drift Followups"
type: tempdoc
status: done
created: 2026-04-21
closed: 2026-04-22
---

> NOTE: Noncanonical working tempdoc. Verify behavioral claims against canonical docs, code, and tests before promotion.

# 399: CLAUDE.md System-Prompt Alignment and Harness Drift Followups

## Purpose

Capture the residue from an audit of project-level agent instructions (`CLAUDE.md`,
`.claude/rules/*`) against the Claude Code (Opus 4.7) built-in system prompt, plus gaps
identified during an external-research pass on AI coding-agent failure modes (April 2026).

Items fall into three classes:

1. **Completed** — trims already applied in the originating session.
2. **Small direct edits** — few-line additions, no implementation risk.
3. **New capability** — harness and model drift detection.

## Scope guardrails

- Does not re-open tempdocs 262-265, 272, 276, 285, 218 — their work stands.
- Does not duplicate tempdoc 353 (repo-friction log).
- Does not expand scope beyond the items listed below. Additional items require a separate
  tempdoc.

## Completed this session

`CLAUDE.md` trimmed from 246 to 239 lines:

- *Explore Before Implementing* — removed generic intro; kept the 3-step check list.
- *Fix Root Causes* — removed duplicative opening sentence (system prompt covers root-cause
  discipline in *Executing actions with care*); kept the concrete anti-pattern list
  (weakening assertions, `@Disabled`, `@SuppressWarnings`, broadened catches).
- *Stay Focused* — consolidated generic scope-control bullets (system prompt covers "don't
  add features beyond task"); kept tempdoc-specific guidance and the parallel-worktree
  merge-conflict note.
- *Ask When Uncertain* — removed generic opening; kept the three project-specific triggers
  (tempdoc ambiguity, architectural choice, cross-module impact).

`.claude/rules/context-efficiency.md` trimmed from 25 to 19 lines:

- Removed: "Use Grep `files_with_matches` first" (Grep tool default), "Use Explore subagent
  for >3 queries" (verbatim duplicate of system prompt's session-specific guidance), "Scope
  subagent prompts narrowly" (Agent tool description covers this), "Plan changes before
  making them" (generic).
- Kept: `model: haiku` recommendation, concrete large-file list, Java `spotlessApply`
  post-edit workflow, worktree awareness, compaction preservation rules,
  `CLAUDE_AUTOCOMPACT_PCT_OVERRIDE=65` tip.

`.claude/rules/branch-safety.md` left unchanged — worktree-sharing, multi-agent
destructive-git rules, and the shared-dev-stack lease model are unique project value that
the system prompt's generic *Executing actions with care* paragraph does not cover.

### Research basis

Sources consulted, all within the last month relative to session date:

- Anthropic, *Best Practices for Claude Code* — explicit anti-pattern *over-specified
  CLAUDE.md*; `✅ Include / ❌ Exclude` table.
- HumanLayer, *Writing a good CLAUDE.md* — system prompt consumes ~50 of the ~150-200
  instructions LLMs reliably follow.
- dbreunig.com, *How Claude Code Builds a System Prompt* (Apr 4, 2026) — confirms
  system-prompt assembly shape.
- Piebald-AI/claude-code-system-prompts repo — version-tracked system prompt source.

## Small direct edits (proposed)

### E1. Session-scoping rule in CLAUDE.md

Add one line under *Tempdoc Is Your Contract*:

> Start a fresh session per tempdoc. Do not cross tempdocs in one session.

Rationale: recent external data (Apr 2026) correlates context rot and hallucination volume
with long multi-task sessions. Tempdoc 276 classifies session types automatically; this
rule makes the operational scoping explicit before a session starts.

Acceptance: CLAUDE.md contains the rule and total line count remains below the 300-line
threshold HumanLayer and Anthropic recommend.

### E2. Worktree disk-bloat note in `branch-safety.md`

Add under *Setup* or a new *Resource budget* subsection:

> Each worktree's `modules/*/build` directory is independent from the main checkout. With
> 3-4 concurrent agents, accumulated build artifacts can consume 30-40 GB. Worktrees share
> `.git/objects` but not build output — run `./gradlew.bat clean` per module on
> long-running worktrees, or remove the worktree entirely after merge.

Rationale: external research (appxlab.io, Mar 31 2026) reports ~5 GB per worktree on a
2 GB codebase. Not currently flagged in `branch-safety.md` despite being directly relevant
to the repo's parallel-agent model.

Acceptance: rule added; verified against actual `modules/*/build` sizes in at least one
worktree.

### E3. Extend `/doc-audit` to include agent-facing docs

Current `/doc-audit` scope is canonical docs. Extend (or add a sibling skill) to cover
`CLAUDE.md`, `.claude/rules/*`, and `.claude/skills/*/SKILL.md` for:

- Duplication against the Claude Code built-in system prompt — harness updates can shift
  what the system prompt already covers, so an audit that was correct last quarter may
  have new duplicates.
- Internal redundancy across rules files (e.g. the same idea in both CLAUDE.md and
  `context-efficiency.md`).
- Rules not referenced in any session telemetry for >90 days (candidate for removal or
  conversion to a hook).

Implementation path: inspect `.claude/skills/doc-audit/SKILL.md` first. If its procedure
is generic enough, extend scope. Otherwise add `.claude/skills/agent-docs-audit/SKILL.md`.

Acceptance: at least one rule or CLAUDE.md line is identified as a duplication or stale
candidate by the extended skill.

### E5. In-moment observation-logging rule in CLAUDE.md

Add a new subsection under *Agent Discipline* (placement: immediately after *Stay
Focused on Your Assigned Work*) that instructs agents to append pre-existing,
out-of-scope issues to `docs/observations.md` in the moment they are noticed, and
then return to the assigned task.

Context: `docs/observations.md` already exists (109 lines as of 2026-04-22) with a
self-contained rules section covering format, one-line-per-entry, skip-duplicates,
don't-investigate, and promotion-to-`docs/reference/issues/`. It is referenced from
CLAUDE.md's Quick Links but no rule instructs agents to write to it. The infrastructure
is complete — only the behavioral trigger is missing.

Rationale vs the originally considered SessionEnd self-report:

- Higher signal fidelity — the issue is logged while file, line, and context are still
  in working memory.
- No sycophancy — transactional (notice → append → continue), not retrospective.
- Directly resolves the tension in *Stay Focused on Your Assigned Work*, which today
  forces agents to choose between scope creep and knowledge loss.
- No hook infrastructure required — behavioral rule only.
- Cheaper than a session-end retrospective turn (one append per incident).
- Compatible with the existing promotion path to `docs/reference/issues/`.

### Design decisions (settled 2026-04-22)

1. **Section targeting — Inbox at EOF.** Agents append to a new `## Inbox` section
   added as the last section of `observations.md`. Curation moves entries to the
   proper module section in batches. Chosen over per-section Edit (friction kills
   behavioral-rule compliance; Read + section-picker cost per entry discourages
   use) and over a separate inbox file (preserves `observations.md` as the
   canonical home).
2. **Rule title — "Log Pre-Existing Issues, Don't Fix Them".** Mirrors
   *Fix Root Causes, Not Symptoms* (X-not-Y pattern), front-loads the permissive
   action, keeps "pre-existing" as the disambiguator from current-task bugs.
3. **No explicit dedup mechanism.** At expected volume (~5–10 obs/day), keyword
   grep catches ≤ 30% of duplicates with a nonzero false-positive rate. False
   skips would cost more knowledge loss than accumulated dupes; pruning absorbs
   residual duplication at curation time.
4. **Bash-only, no PowerShell fallback.** Shell default is Bash; PowerShell
   translation is rare enough that documenting it wastes instruction-budget
   lines in CLAUDE.md.

### Rule text (placement: CLAUDE.md *Agent Discipline*, after *Stay Focused*)

````markdown
### Log Pre-Existing Issues, Don't Fix Them

When you notice an issue outside your current task's scope — pre-existing bug,
dead code, stale comment, broken-but-unrelated test, config drift — append one
line to the `## Inbox` section of `docs/observations.md` and keep working. This
resolves the tension in *Stay Focused*: noticed-but-out-of-scope findings have
a home instead of becoming scope creep or knowledge loss.

- **Append via Bash** (no Read required):
  ```bash
  echo "- [ ] <description> — \`<file:line>\` ($(date +%Y-%m-%d))" >> docs/observations.md
  ```
  Use a single-quoted heredoc if the description contains `$` or backticks.
- **Do not investigate.** Record and return to your task.
- Issues caused by your current change don't belong here — fix those.
````

Trigger criteria (narrow enough to be unambiguous):

- Pre-existing bug, dead code, stale comment, broken-but-unrelated test, config drift.
- Anything the agent would fix under a "code quality audit" task but which is out of
  scope for the active tempdoc.

Non-triggers (explicit):

- Issues caused by the current change — fix those.
- Issues already being addressed in the active tempdoc.
- Structural opinions (file too long, naming preferences) unless they directly caused
  a bug — the file's own rules already exclude these.

Known limits / accepted trade-offs:

- Parallel-worktree merge conflicts on `docs/observations.md` are expected. They
  are line-level (end-of-file appends) and trivial to resolve at merge time.
- Inbox accumulation requires periodic curation. Expected volume (~5–10 obs/day)
  is manageable in a weekly 5-minute cleanup; if volume grows, promote curation
  to the `/doc-audit` skill's scope.
- Duplicate entries may appear because agents do not read the file before
  appending. Absorbed by the file's own pruning rule at curation time.

### Implementation steps

1. Add `## Inbox` section to the bottom of `docs/observations.md` with a
   one-line explainer directing agent appends there until curation moves them.
2. Insert the rule text above into CLAUDE.md under *Agent Discipline*,
   immediately after *Stay Focused on Your Assigned Work*.
3. Validate by appending one test entry via the command shown in the rule;
   confirm it lands in `## Inbox`; remove the test entry.

Acceptance: `## Inbox` section exists in `docs/observations.md`; CLAUDE.md
contains the new rule under *Agent Discipline* after *Stay Focused*; at least
one appended observation validates end-to-end; total CLAUDE.md line count
remains under 300.

### E4. Trim candidates for future consideration (not required)

Listed for visibility; needs user judgment on whether the underlying concerns are still
live before trimming.

- *Interrogate Results* (~9 lines) — unique content, compressible to 3-4 lines without
  losing meaning.
- *Structural Defects Don't Need Repeat Incidents* (~11 lines) — highly specific, reads
  like a scar from past conflict. Remove or shorten if the concern has stopped recurring
  in practice.

## New capability: harness and model drift detection

### Problem

External research (Apr 18 2026 article citing AMD data across 14 Claude Code releases)
reports silent harness updates causing:

- ~2× CI failure rate
- ~3× drop in "read file before editing" behavior
- ~2× increase in full-file rewrites vs targeted edits
- Production outages from agent-generated code that passed review days earlier

This directly threatens behaviors CLAUDE.md and the rules files rely on: minimal diffs,
edit-over-write preference, read-before-edit, `TaskCreate` use for multi-step work.

The existing analytics pipeline (`scripts/agent-analytics/`) records tool calls, failures,
costs, and outcomes — but does not record which Claude Code version or model ID produced
each session. Regressions across harness boundaries are therefore invisible even though
the infrastructure to detect them already exists.

### Relationship to existing tempdocs

- **Tempdoc 285** evolves signal shape (oscillation detection, threshold calibration) but
  does not address environment drift. Drift detection is a different layer.
- **Tempdoc 264** records workflow-run metrics but not harness identity.
- **Tempdoc 272** maps repo `session_id` to OTel `session.id` but does not address version
  tracking; OTel GenAI semantic conventions already define `gen_ai.request.model` and
  related fields, so this work aligns with standards rather than inventing a schema.
- **Tempdoc 353** is repo-friction, not harness-friction. Out of scope here.

### Proposed approach

1. **Capture (required)** — at session start, record:
   - `claude_code_version` (from `claude --version` or an env var)
   - `model_id` (from the session's system context if exposed, otherwise nominal)
   - optionally, a hash of the built-in system prompt if accessible

   Write these into the session-init event in `events.ndjson`, using OTel GenAI
   semantic-convention field names (`gen_ai.request.model`, plus a custom
   `claude_code.version`).

   Plumbed via either the `/start` skill (user-space) or
   `scripts/agent-analytics/hooks/dispatch.mjs` (harness-space). Harness-space is
   preferred because it does not require the user to invoke `/start`.

2. **Aggregate (required)** — extend `analyze-trends.mjs` or add a sibling script to
   bucket core PHI signals (tool_failure_rate, rapid_reedit_count, edit-vs-write ratio,
   unbounded_read_pct) by `claude_code.version`.

3. **Alert (required)** — flag when a signal shifts >20% across adjacent version buckets
   with N ≥ 10 sessions on each side. Surface via `scripts/agent-analytics/` output, not
   as a CI gate yet.

4. **Probe (optional)** — a small reproducible prompt run against each new Claude Code
   version; diff between versions is the drift signal. This is the "AMD pattern" from the
   source article. Skip for v1.

### Dependencies and sequencing

- Can proceed independently of tempdoc 353.
- Does not block and is not blocked by tempdoc 285.
- No canonical-doc or ADR impact until the capability stabilizes; if drift-detection
  becomes a default gate, promote to `docs/explanation/08-observability.md` and possibly a
  new ADR at that point.

### Non-goals

- Not adding vendor-specific observability platforms (Braintrust, Datadog, Arize).
  Tempdoc 265 already evaluated and scoped external backends; Opik + Inspect AI are the
  adopted opt-ins.
- Not building a new telemetry store. Use the existing NDJSON pipeline.
- Not auto-halting sessions on version-drift detection. v1 is report-only.

## Exit criteria

- E1, E2, E3, E5 edits applied and validated.
- Drift-detection capture (step 1) implemented and emitting events for at least one
  session.
- Drift-detection aggregation (step 2) produces at least one bucketed report across
  ≥ 2 Claude Code versions.
- CLAUDE.md still under 300 lines with no duplicated system-prompt content reintroduced.
- No canonical docs modified unless explicit promotion is agreed.

## Out of scope (explicit)

- E4 trim candidates (*Interrogate Results*, *Structural Defects*) — need separate user
  judgment.
- Tempdoc 353 items (jseval smoke, Gradle daemon stop, port ownership verification) —
  owned there.
- Reworking the session-analytics signal set. Tempdoc 285 owns that.
- Promoting any content to canonical docs. Deferred until the drift-detection capability
  stabilizes.
