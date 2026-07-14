---
title: "727 — Session-transcript friction mining: batch analysis + fixes for confirmed developer-agent process timewastes"
type: tempdocs
status: "open — all 7 designed fixes IMPLEMENTED, derisked, and independently review-verified in worktree .claude/worktrees/727-friction-fixes (F-6a cwd-hint, F-7b taskcreate-guard, F-7a agent-lessons.md prose + intervene.mjs basename-tracking + edit-reread-hint, F-7c intervene.mjs explicit-limit capping, F-8a branch-safety.md prose, F-2 remove-worktree.cjs taskkill line, F-3 worktree-base-hint.mjs extension + registration). All 4 new/extended hooks registered in governance/agent-hooks.v1.json, settings.local.json regenerated with zero drift, hook-integrity + prose-tier-register gates both pass, full hook test suite green (14 files + 1 new integration test). A refute-first subagent review found 3 real lower-severity issues (all fixed: intervene.mjs's F-7c cap no longer reads whole files, worktree-base-hint.mjs now honors the hook kill switch, compaction's wipe of F-7a's cross-root index is now documented) and 1 high-severity platform constraint (not a code bug, documented): worktree-local hook edits don't take effect until merged to main, confirmed via a live runtime probe. A conceptual tempdoc-fit re-review then found and closed 2 more items: F-7c's secondary context-efficiency.md fix was actually made (which surfaced and fixed an always-loaded-budget ratchet failure in 2 files, plus logged a pre-existing, out-of-scope budget violation in 2 others), and the Theorization section's rollout-risk question was explicitly resolved (kill switch verified sufficient; no new soak/opt-in machinery built). See 'Implementation review' and 'Fit-review closure' sections below for full detail. Nothing committed, no PR opened — gated on explicit founder go-ahead."
created: 2026-07-14
author: agent session e8c883b6 (Sonnet 5)
category: agent-process / tooling / observability
related:
  - 695 (agent-session-friction-retro — single-session precedent this generalizes to batch scale)
  - 655 (MCP confirmation-gate fix — F-1 cross-references its merge history)
  - 624 / 701 (agent-utility benchmark + battlefield-en-v1 corpus diagnosis — F-1/F-5's excluded benchmark-subject sessions concern this work, not this tempdoc's own scope)
  - docs/reference/contributing/agent-postmortems.md (named reference cases this may extend)
  - .claude/rules/tier-register.md (rule 35 verify-worktree-base — F-3 is a direct hit)
  - .claude/rules/branch-safety.md (F-2 concerns scripts/dev/remove-worktree.cjs)
---

# 727 — Session-transcript friction mining

## Charter

Build a repeatable capability to mine local Claude Code session transcripts for **process
friction** — time, turns, or tokens wasted for reasons other than the inherent difficulty of
the task — analyze the results at batch scale, and drive fixes for whatever confirmed,
recurring issues surface. This is deliberately a different instrument than reading tempdocs:
tempdocs document interesting technical results and have survivorship bias against mundane
process cost; raw transcripts show the tax directly. Scoped specifically to **developer-agent
sessions** — organic coding/engineering work on this repo — not benchmark-harness subjects,
personal use, or business/research sessions (scope corrected mid-session; see below).

**Triggered by**: a conversation reviewing tempdocs 681-726 to assess how much recent work
targets product/marketability vs. engine correctness. That review concluded transcripts, not
tempdocs, are the better signal for "what's actually costing agents time" — this tempdoc is
the follow-through on that conclusion.

## Methodology

- **Tooling**: `scripts/agent-analytics/mine-friction.mjs` — a sibling to the existing
  `evaluate-session.mjs` (which judges task *completion*). Reuses the same
  condense-then-judge-via-headless-CLI shape (`claude -p --tools "" --max-turns 1`, structured
  JSON rubric) but with a friction-tuned condenser and a different system prompt.
  `evaluate-session.mjs` deliberately hides process signals from its judge to avoid anchoring
  the completion verdict on them ("do not let the number of errors or retries influence
  task_completion"); this miner does the opposite on purpose — it keeps *more*
  error/hook-block/warning content, because that content is exactly the signal being measured.
- **Source**: raw Claude Code session transcripts under
  `~/.claude/projects/<repo-path-slug>/*.jsonl`. This is **not** the same stream as
  `tmp/agent-telemetry/events.ndjson` (this project's own structured tool-call telemetry,
  narrower and used by `score-session.mjs`/`outcome-session.mjs`) — it's Claude Code's own
  full conversation log, the raw ground truth those structured signals are themselves
  derived from for the *current* project's sessions.
- **Coverage**: 110 local transcripts (spanning roughly 2026-06-29 to 2026-07-14; the
  then-active session was excluded). 21 were too small/trivial to judge meaningfully (<200
  chars after condensing). Of the remaining 89 judged sessions, **31 were excluded** as
  non-developer-agent work (see Scope correction below), leaving **58 developer-agent
  sessions** as this tempdoc's analysis base.
- **Cost**: ~$0.03/session (sonnet, single-turn, condensed to ≤45K input chars), ~$3 total
  for the full 89-session judge run.
- **Output**: `scripts/agent-analytics/aggregate-friction.mjs` and `friction-timeline.mjs` —
  per-session raw judge output at `tmp/agent-telemetry/friction-results/<sessionId>.json`,
  aggregated into `tmp/agent-telemetry/friction-aggregate.json` /
  `friction-timeline.json` (gitignored under `/tmp/` — regenerate via the scripts, they are a
  point-in-time measurement, not source). Both scripts filter by default against
  `scripts/agent-analytics/friction-excluded-sessions.json` (committed — a hand-classified,
  documented exclusion list); pass `--include-excluded` to disable the filter.

## Scope correction: developer-agent sessions only

The first pass through this tempdoc aggregated all 89 judged sessions together. When asked
to dig into the timeline in more depth, one finding (F-5, then) traced back to a single
14-session automated benchmark-harness burst — which raised the obvious follow-up: were there
*other* non-developer sessions silently mixed into "89 substantive sessions" too? Checking
every session's judge-produced summary against its first user message found there were:

- **14 benchmark-subject sessions** — the `battlefield-en-v1` eval-harness burst (2026-07-08,
  12-minute window) already found while investigating F-5.
- **2 personal/unrelated sessions** — a League of Legends match-history lookup + Discord scam
  check, and a YouTube-transcript capability test / general Q&A session. Not JustSearch work.
- **9 empty sessions** — slash-commands only (`/model`, `/clear`, `/start`, `/resume`,
  `/passes`) with no actual task content; these should have been filtered by the <200-char
  "too small" threshold and weren't (padding from stacked command entries pushed them over).
- **6 non-coding project sessions** — business/research work on the project (Claude Science
  grant research, competitor/market research, GEO/AI-search-visibility research, grant/outreach
  drafting, release-policy research, a Claude Code best-practices discussion) that never
  touched code. Confirmed with the user: "developer agent" means coding/engineering sessions
  specifically, so these are out of scope even though they're legitimate project work.

All 31 are recorded with individual reasons in `scripts/agent-analytics/friction-excluded-sessions.json`
(committed, so a future re-run applies the same classification consistently). **58 sessions
remain** as the developer-agent analysis base. Every number below is post-correction; F-1
(the benchmark-burst finding) is preserved as an appendix since it's still a valid, verified
finding — just about excluded sessions, not this tempdoc's core scope.

## Aggregate results (58 developer-agent sessions)

**86% of developer-agent sessions showed at least one detected friction incident**
(50 of 58). Categories, ranked by severity-weighted frequency (low=1 / medium=2 / high=3,
summed per category):

| Rank | Category | Count | Weight |
|---|---|---|---|
| 1 | `environment-quirk` | 52 | 78 |
| 2 | `tool-error-loop` | 67 | 75 |
| 3 | `wrong-assumption-corrected` | 38 | 68 |
| 4 | `discovery-tax` | 27 | 45 |
| 5 | `user-correction` | 20 | 34 |
| 6 | `redundant-exploration` | 25 | 32 |
| 7 | `retry-loop` | 12 | 22 |
| 8 | `hook-block-friction` | 9 | 12 |
| 9 | `repeated-build-failure` | 4 | 8 |
| 10 | `permission-denial` | 4 | 6 |
| 11 | `other` | 3 | 3 |
| 12 | `stale-artifact` | 3 | 3 |
| 13 | `self-introduced-bug` | 1 | 2 |
| 14 | `build-failure` | 1 | 2 |

Full per-category evidence: `tmp/agent-telemetry/friction-aggregate.json` (regenerate with
`node scripts/agent-analytics/aggregate-friction.mjs`).

## Timeline analysis (58 developer-agent sessions)

Sessions ordered chronologically (session start = first timestamped transcript line) and
split into three volume-controlled thirds (n=19/19/20, since daily session volume varies
1-15x and isn't comparable day-to-day):

| Third | Date range | n | % sessions w/ friction | weight/session |
|---|---|---|---|---|
| Early | 2026-06-29 → 07-01 | 19 | 74% | 5.21 |
| Mid | 2026-07-01 → 07-07 | 19 | **95%** | **7.47** |
| Late | 2026-07-07 → 07-14 | 20 | **90%** | **7.45** |

**This reverses the conclusion from the uncorrected (89-session) run.** The first pass
reported "spiked mid-period, mostly recovered late" — that recovery was almost entirely an
artifact of non-developer sessions (mainly the benchmark burst, which landed at the end of
the original mid/late boundary and diluted the count). With the scope corrected to organic
developer work, **mid and late are statistically indistinguishable from each other (95%/7.47
vs. 90%/7.45) and both are clearly, persistently above early (74%/5.21).** Friction rose
around 2026-07-01 and has **not** come back down since — it plateaued, at best.

Category-level detail (early → mid → late, corrected):

| Category | Early | Mid | Late | Shape |
|---|---|---|---|---|
| `environment-quirk` | 19 | 24 | **35** | still climbing — see F-6 |
| `tool-error-loop` | 21 | 31 | 23 | spiked, mostly recovered |
| `wrong-assumption-corrected` | 16 | 26 | 26 | rose, plateaued (not recovering) |
| `discovery-tax` | 13 | 13 | **19** | flat, then rising late — see F-6 |
| `user-correction` | 12 | 11 | 11 | flat / mild improvement |
| `redundant-exploration` | 9 | 12 | 11 | flat — confirms F-5 (not a real trend) |
| `retry-loop` | 0 | 11 | 11 | rose from ~zero, sustained — F-4 |
| `hook-block-friction` | 2 | 7 | 3 | spiked, recovered |
| `permission-denial` | 0 | 2 | 4 | small numbers, minor |
| `repeated-build-failure` | 3 | 3 | 2 | flat |

### F-4 — `retry-loop`'s rise is real, heterogeneous, and confirmed persistent under the scope correction

Per-incident inspection (not just the category count) shows `retry-loop` incidents span
unrelated causes — CI-wait cycles (push→dispatch→~10-15min feedback, repeated per fix
attempt), PR-merge retries (branch-not-up-to-date, auto-merge disabled, base-branch-policy
blocks), background-job kills/restarts, browser-automation chains, a subagent stalling
mid-experiment, three-successive-diff-methodology attempts. This tracks with the project's
own shift in this window toward more CI/PR/eval-harness-heavy work (the 624 campaign,
tempdoc-655/658/710 MCP and enrichment work, batched observation-fold PRs) — those workflows
have externally-imposed wait/retry loops (CI feedback latency, GitHub merge preconditions)
that earlier, more self-contained sessions didn't hit as often. **Not a single fixable
defect** — a genuine trend, but one to watch/re-measure rather than patch. Confirmed under
both the benchmark-burst correction and the full developer-scope correction (0→11→11
developer-only vs. 2→22→17 raw) — not an artifact either time.

### F-5 (confirmed retracted) — the "search-convergence" finding was the benchmark burst, full stop

The original writeup treated long chains of near-duplicate `justsearch_search`/
`justsearch_browse`/`ToolSearch` calls as an organic late-period trend and cross-validated it
against tempdoc 624's "~+0.00 accuracy" finding. That cross-validation is **retracted**:
every one of those sessions (`ec6fc252`, `9afc6bb5`, `093c1695`, `cfc71061`, `08f9f832`,
`24b11d2e`, `158a7721`, `ae3fff2b`) is inside the excluded 14-session `battlefield-en-v1`
benchmark burst, not developer work. `redundant-exploration` is flat across all three
corrected developer-only thirds (9→12→11) — there is no organic rising trend in this
category. The underlying observation (this specific synthetic, deliberately-confusable
corpus produces multi-query search struggles, consistent with tempdoc 701's existing
verdict) is still true and still worth a note to the 624/719 harness owner (§ Appendix below)
— it just isn't a tempdoc-727 developer-friction finding.

### F-6 — `environment-quirk` and `discovery-tax`'s rise, root-caused via full per-incident review + 4 parallel subagent verifications

Unlike `tool-error-loop` and `hook-block-friction` (which spiked mid-period and mostly came
back down) or `wrong-assumption-corrected` (which rose and plateaued), these two categories
are **higher in the late third than the mid third** — the opposite of recovery:

- `environment-quirk`: 19 → 24 → 35 (monotonically increasing across all three thirds).
- `discovery-tax`: 13 → 13 → 19 (flat, then a late rise).

A full per-incident review of every `environment-quirk`/`discovery-tax` incident across all
three thirds (not just the late one) — cross-checked against early/mid incidents for contrast
— found this is **not one cause**, and importantly, **the biggest single sub-cause isn't
actually the thing that's rising**:

**F-6a — cwd/path-confusion is a large, CONSTANT tax present since the very first session (2026-06-29), not an accelerating one.** Reviewing early+mid incidents alongside late ones shows this exact failure shape recurring throughout the whole dataset: `cd` inside a Bash call silently persists as the shell's cwd for later calls; a subsequent Read/Grep/git command using a path that looks repo-root-relative then fails. Severe instances exist from day one (`d90c9841` 07-01: "bash shell working directory repeatedly reset to the worktree path between commands, forcing the agent to re-`cd` in nearly every subsequent invocation"; `6bdc6aeb` 07-04: same pattern, "throughout the session"). A dispatched subagent verified this against source: no hook in `scripts/agent-analytics/hooks/bash-guard.mjs` (259 lines, checked in full) or elsewhere tracks `cd` or verifies cwd — the only cwd-adjacent hook, `worktree-base-hint.mjs`, checks `git rev-parse HEAD` divergence after `EnterWorktree`, not shell cwd after a Bash `cd`. Live-verified platform behavior: cwd **does** silently persist across Bash calls in the *main* session (unlike subagent threads, which reset cwd every call by design) — this is exactly why it bites main-session work and not delegated subagent work. Read/Grep already self-report the correct cwd on failure ("File does not exist. Note: your current working directory is X") — the infrastructure isn't blind, but it's reactive (after the wasted call), not proactive. **Concrete, low-risk fix identified**: a PostToolUse hook on Bash matching `cd\s` (standalone or chained) that echoes the resulting absolute cwd into context — cheap, fires only on actual directory changes, closes the gap between "silently changed" and "self-reports on the next failure." Since this is a constant background cost rather than a late-period trend, it explains a meaningful floor under `environment-quirk` across the *whole* dataset, not specifically its late-period climb.

**F-6b — a 2-session, same-day (2026-07-07) browser-automation pair drove a disproportionate share of the late-third numbers**, and surfaced one real product bug in the process, verified via a dispatched subagent: the **"New chat" button is state-gated and doesn't render on a fresh/empty chat** (`modules/ui-web/src/shell-v0/views/UnifiedChatView.ts:2114-2117` — rendered only when `thread.length > 0 && !agentMode`; no other entry point exists per the keyboard-shortcuts doc). Both sessions independently burned many tool calls hunting for a "new chat" affordance that doesn't exist in the state they landed in — this is a genuine, previously-unlogged UI defect, not agent error. Logged to the observations inbox (out of this tempdoc's process-friction charter). The other half of this cluster — viewport-resize/window-focus flakiness (innerWidth/outerWidth mismatches) — is **not confirmed as a JustSearch-side bug**; more likely `claude-in-chrome`/multi-tab contention (consistent with the already-documented "too many tabs hangs the extension" lesson in `agent-lessons.md`), also logged to the inbox as unconfirmed.

**F-6c — tempdoc-number collisions (2 incidents: `b919c72e`, `83c6e490`) are an already-accepted, deliberate tradeoff — investigated and closed, no fix warranted.** A dispatched subagent confirmed `check-tempdoc-numbers.mjs` is intentionally manual/honor-system (no hook, no CI wiring), and the repo's own design rationale — stated explicitly in tempdoc 618 and tempdoc 553 — is that collisions are rare, disjoint-file renames never actually git-conflict, and the check catches every case pre-merge; a reservation/atomic-claim mechanism would be over-engineering for how cheaply this resolves today. No action item.

**F-6d — CRLF fixture noise (`settings-v2-live.json`) is a known, already-documented issue with an existing, unimplemented fix recommendation** — not a new finding. A dispatched subagent found it named in `docs/observations.md:311-313`, and root-caused + a fix proposed (an explicit `eol=lf` `.gitattributes` pin) in tempdoc 618 and tempdoc 696 — just never implemented. The friction here is "agent didn't check existing docs before investigating," not a tooling gap; the actual fix is a pre-existing backlog item, not something this tempdoc needs to design.

**F-6e — a Windows/git-bash "system cannot find the path specified" error recurring across many test names inside a CI coverage-check loop (`83c6e490`) is genuinely novel and undocumented.** A dispatched subagent could not identify the producing script via static search (checked `check-*coverage*.mjs`/`check-*tier*.mjs` for `spawnSync`/`execFileSync` per-test-name patterns — no match) and concluded it needs a live repro. Logged to the observations inbox; still open.

**Net read on F-6**: the late-period rise in `environment-quirk`/`discovery-tax` is mostly explained by a handful of session-specific clusters (F-6b's browser-automation pair, F-6c's tempdoc collisions, F-6e's CI quirk) rather than one systemic regression — but the single highest-value fix to come out of this whole tempdoc is F-6a's cwd-echo hook, precisely because it isn't a late-period spike at all: it's a constant, unaddressed tax that's been paid since the very first session in this dataset.

## Appendix: the excluded benchmark-harness burst (F-1) — valid finding, out of this tempdoc's scope

### F-1 — MCP `TYPED_CONFIRM` stale-message friction (6 of the 14 excluded benchmark-subject sessions, all dated 2026-07-08) — confirmed contained to a stale build, not a live source bug

This finding concerns 6 of the 14 sessions excluded from the analysis above (the
`battlefield-en-v1` automated benchmark harness burst — see Scope correction) — it is not a
developer-agent finding, but it was verified against source this session and is worth keeping
on record, likely for whoever owns the 624/719 benchmark harness.

6 sessions (`03c2a600-c190-45b2-8d26-11737bafe2aa`, `08f9f832-9ee3-4749-8bba-197cf015e905`,
`093c1695-b125-4980-8b33-8a97e6bd737f`, `9afc6bb5-2dd2-4e75-88a5-46ada039d2b0`,
`c9e0c02e-12b5-49e4-95e9-eff62a73cc80`, `d91e0923-2314-4205-afd1-a8e85ab59243`) hit the
identical failure: `justsearch_ingest` blocked by `TYPED_CONFIRM`, agent retries with
`"_confirmationToken": "confirm"`, gets the **exact same error again**.

Checked against source: `OperationExecutorImpl.java:606-627` shows the confirmation path now
requires a cryptographically-bound, single-use consent capsule
(`capsuleService.verifyAndConsume`) — the comment at line 610 states explicitly *"the V1
nominal token (any non-blank string) is gone... The URL/deeplink (MEDIUM), agent-loop, and MCP
paths carry no token and are correctly gated until they too route through an approval that
mints a capsule."* `McpToolSurface.java:760-784`'s `handleConfirmationRequired` already
reflects this — its doc comment says it replaced *"the previous fabricated
`_confirmationToken` hint, which nothing in the codebase ever read — the tool could never
actually succeed via that path"* (tempdoc 655). Git history: the 655 fix merged 2026-07-02
(`5481a89`) and 2026-07-07 (`5779c48`, `ffbea13`) — **before** all 6 sessions (2026-07-08).

All 6 are sub-sessions of the *same* 14-session, 12-minute automated benchmark burst — this
is **one incident** (one batch run hitting one stale server), not 6 independent occurrences.
**Zero recurrences appear in the following six days of data** (07-09 → 07-14, outside the
burst). Confidence: **confirmed contained — one incident, one batch run, zero recurrence in
6 days of subsequent data.** The root-cause gap (exactly which stale instance the harness
talked to — dev-stack jar vs. installed app) remains open, and is lower priority given the
non-recurrence.

**Open, not yet fixed**: if a stale-server/stale-jar gap can silently reproduce an
already-fixed, misleading security-relevant error message for at least 6 days after the
source fix landed, that's a build-freshness signal gap worth a durable fix — e.g. checking
whether the MCP connect-time instructions (tempdoc 655) carry a build/commit-hash stamp an
agent could check before trusting an error message's advertised remedy. Also worth a note to
whoever owns the 624/719 benchmark harness: 2 of its 14 sessions (`ae3fff2b`, `cfc71061`)
never found the target document despite 15-25+ search attempts — an eval-harness cost
question, independent of whether `battlefield-en-v1`'s underlying corpus defect (already
diagnosed, tempdoc 701) is real.

## Deep-dive: findings verified against source (developer-agent sessions)

Per this repo's `audit-without-test` and `critical-analysis-pass` discipline, F-2 and F-3
were checked against actual code and git history — not left as raw LLM-judge output, which is
a hypothesis, not a verified finding. Both are drawn from genuine developer-agent sessions
(not the excluded set).

### F-2 — Worktree teardown EPERM survives `remove-worktree.cjs`'s own fallback chain

`scripts/dev/remove-worktree.cjs` already has real defense in depth (junction-unlink →
5-attempt retry → `\\?\` long-path `.NET` delete → `reportHolders()` naming the PID holding
the path — tempdoc 618 §2/§9). The friction incident (`environment-quirk`, "repeatedly failed
with EPERM/long-path delete errors from the custom remove-worktree tooling, requiring
multiple retries, a re-enter/re-attempt cycle") shows this exact tool still failing end to
end. Reading `deleteTree` (lines 122-154): when the long-path delete also fails
(`ps.status !== 0`), the script calls `reportHolders()` and returns `false` — it correctly
**diagnoses** a held handle but does **not act on it**; the operator/agent has to manually
identify and kill the holding process from the printed PID list. **Confirmed gap, not yet
fixed**: a real last-mile hole in an otherwise well-engineered script, not agent error. Given
F-6, this may be one of several `environment-quirk` sub-causes, not the whole story.

### F-3 — `verify-worktree-base` (tier-register rule 35, prose-only) — confirmed still-live non-adherence

The `discovery-tax` incident ("worktree branched from HEAD without first checking for
uncommitted local changes on main, then discovered mid-read that main had an uncommitted
'Direction note' resolving the tempdoc's core question... requiring an extra cd-and-diff round
trip to recover it") is exactly the failure mode `.claude/rules/branch-safety.md` rule 7
(tier-register rule 35, `verify-worktree-base`) exists to prevent — and it happened anyway, in
a session dated well after that rule existed. This is direct empirical evidence that a
`prose-only`-tier rule (~70% adherence per the tier-register's own calibration) measurably
under-performs in practice, not just in theory. **Candidate for tier promotion** (prose →
hook), per `.claude/rules/before-appending-to-rules`'s own philosophy: a load-bearing must
belongs in a hook/gate when it's shown to recur, not in more prose. Given F-6, this may
likewise be one of several `discovery-tax` sub-causes behind the late-period rise, not the
whole story.

## F-7 — `tool-error-loop` (the single largest category, weight 75) root-caused via 3 parallel subagent verifications

`tool-error-loop` incidents were reviewed across all 58 sessions (not just one period) and
clustered by actual mechanism, then verified:

**F-7a — Edit-before-fresh-Read (11+ sessions, the single largest sub-cluster in the entire
dataset) — a previously-diagnosed gap that stalled mid-promotion, not a fresh discovery.** The
agent calls `Edit` on a file it hasn't freshly `Read` in the current context — most often
because the file exists in both a worktree copy and the main-checkout copy, and a read of one
doesn't count for the other. One session hit "File has not been read yet" 4+ times on the same
file in a single session. A dispatched subagent found this exact pattern was **already
diagnosed once before**, in `docs/tempdocs/618-agent-developer-velocity-friction.md` §11e
("the `Edit` tool demands a re-read of the *same* logical file across the worktree↔`main` path
boundary... hit live while filing this very section") — flagged for promotion to
`agent-lessons.md`, but that promotion was **never completed**; it's absent from
`agent-lessons.md` today. This tempdoc independently rediscovered the same category without
originally connecting it back. **Concrete fix**: a `PostToolUseFailure` hint hook matching
Edit + response text `"has not been read"`/`"modified since read"`, mirroring the existing
`pipe-mask-hint`/`tempdoc-age-hint` pattern.

**F-7b — TaskCreate schema-shape confusion (6+ sessions) — undocumented anywhere, no hook
coverage, but fixable.** Agents repeatedly call `TaskCreate` with a batch-style `tasks` array
payload instead of the correct one-call-per-task `subject`/`description` shape; in at least 2
sessions the agent gave up on task tracking entirely after the failure. A dispatched subagent
confirmed: no skill/rule/canonical doc in this repo documents TaskCreate's correct usage (the
correct shape is only implicit in this repo's own analytics code, e.g.
`scripts/agent-analytics/hooks/repeat-guard.mjs:49-50`, never surfaced to an agent); no
existing hook intercepts this. **Concrete fix**: a `PreToolUse` hint hook matching
`TaskCreate` that statically detects the malformed batch shape before dispatch and corrects it
same-turn — the same "residence→delivery" pattern already used for `pipe-mask-hint.mjs`/
`docs-granularity-hint.mjs`. Not an Anthropic-harness-only problem; this repo can close it.

**F-7c — large-file Read-limit exceeded (5-6 sessions) — a stale, incomplete guidance list, cheap to fix.**
Agents repeatedly hit the Read tool's silent 25,000-token ceiling on large tempdocs and
`docs/observations.md`, sometimes twice in the same session without adapting strategy. A
dispatched subagent confirmed `.claude/rules/context-efficiency.md:7`'s "known large files"
list only names 6 specific code files and covers neither `docs/observations.md` (995
lines/177KB — confirmed) nor tempdocs generically (several exceed 4,000+ lines — confirmed:
`249-open-source-investigation.md` 5,542 lines, `624-agentic-retrieval-eval-rebuild.md` 4,432,
etc.). **Concrete fix**: broaden that one line to cover `docs/observations.md` and a
size-based rule for tempdocs generally — a one-line prose edit, no automation needed.

**F-7d — long tail, heterogeneous, no single fix.** PowerShell/Bash quoting fragility on
Windows, `gh`/`git` CLI misuse (invalid flags, ambiguous refs), browser/computer-tool clicks
not registering, `python3` unavailable. Same conclusion as F-4: real, but not one fixable
defect — accept as baseline agentic-coding cost.

## F-8 — `wrong-assumption-corrected` (third-largest, weight 68) root-caused via 1 parallel subagent verification + direct review

**F-8a — squash-merge obscures merge status (4 sessions) — a real, cheap, high-value
documentation gap, confirmed.** Agents repeatedly conclude work is "unmerged" by inspecting
branch labels/commit ancestry/`git log`, then discover it was actually already merged under a
differently-titled squashed commit (per ADR-0045's squash-only policy, which erases the
original branch's commit history from `main`). One instance fed a wrong conclusion into a
user-facing decision before being caught. A dispatched subagent confirmed: neither
`branch-safety.md` nor ADR-0045 nor `agent-postmortems.md` documents the correct verification
method (content-diff against `main`'s tree, e.g. `git diff <branch> main -- <paths>`, empty
output = already landed) — every existing doc either explains *why* squash-merge exists or
describes normal merge workflow, none says "don't trust commit ancestry for this." **Concrete
fix**: a short addition to `branch-safety.md` stating the correct check.

**F-8b — `audit-without-test` recurring despite already being a named rule (3 sessions:
`18896f80`, `91b15f6e`, `93aca2c4`).** A subagent's finding was accepted without independent
verification and later proven wrong in all three — one was a real bug ("pre-existing" test
failures that were actually a fresh regression), one was a stale audit conclusion re-verified
weeks later, two were hallucinated/unverifiable web-research claims needing a second
verification pass. This is a direct, repeated instance of the `audit-without-test` postmortem
handle **already named** in `docs/reference/contributing/agent-postmortems.md` — meaning the
existing prose-tier rule is not preventing the exact failure mode it exists to name, same
pattern as F-3. Not a new fix to design here — but further evidence that this specific
postmortem handle deserves harder enforcement than a prose reminder.

**F-8c — a correctness-risk finding, not just friction (`9e554d4f`).** After adding new
tests, the agent ran `pytest` from the main checkout instead of the worktree, causing the new
tests to silently run against stale/unmodified files and falsely appear to pass (the same
count, "66 passed," reported twice) before the agent noticed the new tests weren't collected.
This is a live instance of this repo's own named `static-green ≠ live-working` /
`unreachable-seed-green` risk class — worth flagging prominently since a false-positive green
is worse than a visible failure. No systemic fix designed here (out of scope for this
tempdoc), but worth a callout: verifying *which* directory a test run actually executed in
before trusting its result.

**F-8d — long tail, mostly normal engineering self-correction, not friction to fix.** Reversed
design conclusions after research, corrected technical misunderstandings, scope
redirects — this is the "verify, don't guess" discipline working as intended (an assumption
made, then checked, then corrected), not a defect. One exception worth naming: `d90c9841`'s
first proposed fix was documentation-only when a functional fix was needed — a direct instance
of this repo's own `fix-root-causes-not-symptoms` rule being violated in the moment it was
supposed to prevent.

## Smaller categories (`hook-block-friction`, `user-correction`, `stale-artifact`, `self-introduced-bug`, `build-failure`, `other`) — reviewed directly, no subagent needed

- **`hook-block-friction`** (9 incidents) — overwhelmingly guardrails (repeat-guard,
  bash-guard) working as intended, small cost each time. One distinct exception: `bd75a752`'s
  Fable model safety guardrails flagged security-remediation content and silently fell back to
  Opus mid-session, requiring the agent to retroactively split a document and rewrite
  cross-references — a model-level behavior, not a repo hook, and a real but out-of-repo-scope
  cost.
- **`user-correction`** (18 incidents) — heterogeneous: plan rejections (working as intended),
  scope-redirection (normal collaborative refinement), and one rule-violation instance
  (`d90c9841`, same as F-8d) worth naming specifically. No single fix.
- **`stale-artifact`** (3 incidents) — small; one instance is the same CRLF issue as F-6d
  (already tracked, not new).
- **`self-introduced-bug`** (1 incident) — trivial, self-corrected same session, not worth
  pursuing further.
- **`build-failure`** (1 incident, `720a6bf8`) — **resolved, not a new issue**: the failing
  `pmdIntegrationTest` task is an already-known, already-documented Gradle config-cache
  classpath-serialization bug (`observations.md` #178, `modules/app-launcher/build.gradle.kts:195-200`),
  with an existing CI workaround (`ci.yml: -x pmdIntegrationTest`). The friction was the agent
  running a full local build without the exclusion CI already uses — not a new defect.
- **`other`** (3 incidents) — heterogeneous noise (duplicate user message, an idle subagent
  needing a resume nudge, a disclosed evidence gap). Not actionable as a group.

## Remaining backlog

All 14 friction categories have now had at least a direct review; 8 subagent-verified findings
(F-6a-e, F-7a-c, F-8a) identify concrete, fixable gaps. Per this repo's "Tempdoc Is Your
Contract" rule, each stays an explicit open item, not silently closed:

- **F-6a (cwd/path-confusion hook)** — top priority, and the single most valuable fix this
  whole tempdoc found: a constant, dataset-wide tax (not late-period-specific), a verified
  gap (no existing hook covers it), and a concrete low-risk design already sketched. Ready
  for implementation pending founder go-ahead (this tempdoc's charter is analysis-only).
- **F-6b (browser-automation product bug)** — the "New chat" button fix is logged to the
  observations inbox; it's a `modules/ui-web` product bug, not this tempdoc's fix to make.
  The viewport-flakiness half is logged as unconfirmed (`claude-in-chrome`-side vs. product).
- **F-6c (tempdoc-number collisions)** — closed, no action; already a deliberate,
  documented tradeoff (tempdoc 618/553).
- **F-6d (CRLF fixture noise)** — not a new item; points at an existing, already-designed,
  never-implemented fix (an `.gitattributes` `eol=lf` pin) sitting in tempdoc 618/696's
  backlog. Worth implementing, but as part of that existing thread, not a new one here.
- **F-6e (Windows CI path-specified error)** — logged to the observations inbox; genuinely
  novel, needs a live repro session to identify the producing script before it can be fixed.
- **F-7a (Edit-before-fresh-Read hint hook)** — second-highest-value fix found (largest
  single sub-cluster in the dataset); a stalled promotion from tempdoc 618, ready to design
  now that it's reconnected.
- **F-7b (TaskCreate schema hint hook)** — ready to design; undocumented gap with no existing
  coverage, concrete fix identified.
- **F-7c (broaden `context-efficiency.md`'s large-file list)** — trivial one-line prose fix,
  ready to make immediately.
- **F-7d, F-8d** (heterogeneous long tails) — no single fix; accepted baseline cost.
- **F-8a (squash-merge verification note)** — ready to design; a short, precise addition to
  `branch-safety.md` closes a confirmed documentation gap.
- **F-8b (`audit-without-test` still recurring)** — no new fix; further evidence this named
  postmortem handle needs harder enforcement than prose, same conclusion as F-3.
- **F-8c (silent stale-test false-positive)** — flagged as a correctness risk, not scoped for
  a fix in this tempdoc.
- Smaller categories (`hook-block-friction`, `user-correction`, `stale-artifact`,
  `self-introduced-bug`, `build-failure`, `other`) — reviewed directly (see section above); no
  further action needed beyond what's already noted there.

## Next steps

All 14 categories now have at least a direct pass; ready-to-build fixes are ranked by value
below. This tempdoc's charter stays analysis-only — every item needs founder go-ahead before
implementation.

1. **F-6a — cwd-echo hook** (PostToolUse on Bash matching `cd\s`, echoing the resulting
   absolute cwd into context). Highest value: a dataset-wide constant tax since the first
   session, zero existing coverage.
2. **F-7a — Edit-before-fresh-Read hint hook** (`PostToolUseFailure` matching Edit + "has not
   been read"/"modified since read"). Second-highest value: largest single sub-cluster in the
   dataset, and a stalled tempdoc-618 promotion this tempdoc can finally close out.
3. **F-7b — TaskCreate schema hint hook** (`PreToolUse` matching TaskCreate, detecting the
   malformed batch-array shape before dispatch).
4. **F-7c — extend `intervene.mjs`'s existing large-file guard** (see Design section below —
   revised from the original "broaden a prose list" idea once the actual failure mechanism
   was pinned down).
5. **F-8a — add a squash-merge verification note to `branch-safety.md`** ("diff file content
   against `main`, not commit ancestry, before concluding work is unmerged").
6. **F-3 update: already mostly implemented, not still a design item** — see Design section
   below; `scripts/agent-analytics/hooks/worktree-base-hint.mjs` already exists (untracked)
   and mechanizes half of this. Extend it, don't redesign it.
7. Decide + implement a remediation for F-2 (holder-kill, or at minimum a clearer
   forced-manual-recovery prompt) — see Design section for the specific extension.
8. Pass F-1's residual open item (build-freshness signal) and the search-convergence note
   (§ Appendix) to whoever owns the 624/719 benchmark harness — not this tempdoc's fix to
   make. Do NOT re-open 624's retrieval-quality question on this tempdoc's evidence; that's
   701's territory and 701's verdict already stands.
9. Whoever owns tempdoc 618/696's backlog should pick up F-6d's `.gitattributes` pin — flagged
   here, not re-designed here.
10. Re-run `mine-friction.mjs` + `friction-timeline.mjs` periodically (e.g. weekly, or folded
    into the `session-retro` skill) so the timeline stays live rather than a one-time snapshot,
    applying `friction-excluded-sessions.json`'s classification to new sessions as they
    accumulate — open question, not decided here.

## Theorization: open questions before finalizing the ranked fixes above

The ranked list above says *what* to build; this section is deliberately upstream of that —
questions worth sitting with before any one design gets locked in, not a verdict on any of
them.

### Is "echo/hint after the fact" the right shape, or a symptom-level patch?

F-6a, F-7a, and (implicitly) F-8a all share a shape: something silently changed or was
silently wrong (cwd, which copy of a file was read, whether a branch's work already landed),
and the fix on the table is to surface that fact more loudly, closer to the moment it
mattered. That's a real improvement over today (nothing surfaces it at all, or it only
surfaces reactively after the mistake), but it's worth asking whether it's the *root* fix or
a well-aimed patch on a deeper one:

- For F-6a specifically: the deeper question is whether shell working-directory state should
  *persist* across tool calls in the main session at all, given that subagent threads already
  don't have this problem (their cwd resets every call). Making persistence louder (echoing
  the new cwd) is cheaper and preserves genuinely useful multi-step-in-one-directory
  workflows; eliminating persistence (resetting cwd like subagents do) would remove the
  failure mode entirely but could break sessions that rely on staying in a directory across
  several commands on purpose. Both are legitimate; which one is "the fix" depends on how much
  legitimate use of persistent cwd actually exists, which isn't measured here.
- For F-7a: the tool's own error message ("File has not been read yet") is already
  self-explanatory in the generic case. A hint hook that just repeats that adds little. The
  part actually worth surfacing is the *specific, repo-flavored* reason it keeps happening
  here — reading a file under one worktree path doesn't count as having read the "same" file
  under a different path — which the platform has no way to know and only this repo's hook
  layer could add. Worth checking, before building anything, whether that more specific hint
  is what actually gets designed, rather than a generic restatement of the existing error.
- A cheaper, faster-to-test alternative for F-7a in particular: the underlying finding (a
  stalled tempdoc-618 promotion) could be closed out first as a plain documentation addition
  to `agent-lessons.md` — no engineering, immediate — and only escalated to a hook if the
  documentation alone doesn't move the numbers on a future re-run of this same mining
  process. That mirrors this repo's own stated philosophy of moving from prose to
  hook/gate only once prose is shown to under-perform, rather than skipping straight to
  automation.

### Do the hint hooks need to be hints at all, or could some be hard blocks?

F-7b's malformed-batch-payload case is a clean, unambiguous shape mismatch — there's no
legitimate reason to call the tool that way. A blocking `PreToolUse` guard (closer to how
existing guards intercept other unambiguous mistakes) would prevent the wasted call outright,
rather than a same-turn hint that still lets the failure happen first. Worth weighing
blocking-vs-hinting per fix individually rather than defaulting all of them to the same
mechanism, the same way this repo already distinguishes hard guards from soft hints
elsewhere.

### Is a static file list ever the right shape for "which files are large"?

F-7c's fix (broadening a short enumerated list of "known large files") is cheap and correct
for today, but an enumerated list can't keep up with a file population that grows over time
(new tempdocs are added continuously and many are already large). A size- or line-count-based
rule of thumb, or a lightweight check before a large Read, generalizes better than any list
ever will — worth keeping in mind as a "if this list needs updating again in a few months,
that's the signal to switch shape" tripwire, rather than re-litigating the list itself each
time.

### A possible shared shape across several findings: silent environment-state drift

A few of this tempdoc's findings, looked at side by side, resemble the same underlying shape
more than they resemble each other's surface symptom: a piece of state the agent implicitly
trusts across turns or tool calls — a shell's current directory (F-6a), which copy of a file
was actually read (F-7a), a browser window's actual size (F-6b), whether a branch's work
already landed on `main` (F-8a), whether a subagent's reported finding was actually verified
(F-8b) — silently drifts out of sync with reality, and the tooling around it either doesn't
say so at all, or only says so after something has already gone wrong. Named individually,
each is a small fix. Named as one shape, the underlying question becomes: for any piece of
mutable state an agent is expected to trust across a turn boundary, is there a designed answer
to "how would the agent find out if this drifted," or is that answer currently "it finds out
when something breaks"? This isn't a conclusion to build toward yet — it's a lens that might
make it easier to recognize the next instance of this same shape before it costs another dozen
sessions of friction to notice.

### A second possible shared shape: diagnosed-but-never-promoted

Separately, at least three of this tempdoc's findings (F-7a's stalled tempdoc-618 promotion,
F-6d's designed-but-unimplemented `.gitattributes` fix, and this tempdoc's own initial
rediscovery of a category tempdoc-618 had already named) point at the same meta-pattern: this
project is good at *diagnosing* friction in a tempdoc, but the step from "diagnosed and a fix
was proposed" to "actually promoted into a durably-enforced rule, hook, or committed fix"
sometimes doesn't happen, and nothing currently tracks that gap as its own backlog. Worth
considering, later, whether a lightweight register of "flagged for promotion, not yet done"
items — distinct from the existing tier-register, which tracks *how* a rule is enforced once
it exists, not *whether* a proposed promotion ever actually landed — would catch this class of
leak before the next mining pass has to rediscover it independently.

### A more structural alternative worth naming, not deciding: environment mismatch as the root

Several separate findings in this tempdoc — CRLF line-ending noise, PowerShell quoting
fragility, an undiagnosed Windows path-handling CI error — all trace back to the same broad
condition: a Windows development machine running tooling that assumes more POSIX-like shell
behavior. Patching each surface symptom individually (a `.gitattributes` pin here, a
corrected PowerShell invocation there) is the path this tempdoc has largely taken, and it
works. A more structural alternative — developing inside a Linux environment (e.g. WSL) to
remove a whole category of these mismatches at the root rather than one symptom at a time —
is a much bigger, more disruptive lever that this tempdoc doesn't have the evidence to
recommend, but it's worth naming explicitly as a different level to intervene at, rather than
assuming the only available shape is "one more Windows-specific patch."

### A possible alternative to five separate hint hooks: one proactive orientation surface

Most of the fixes above are separate, reactive mechanisms, each watching for one specific
failure signature. An different framing worth holding onto: several of these — knowing the
current directory, knowing which worktree is active, knowing whether required tools
(a specific JDK, `python3`) are actually available — are all facts an agent could be told
proactively at a natural checkpoint (session start, entering a worktree, after a directory
change) rather than discovering piecemeal through separate failures. A single, unified
"where am I and what's available" surface might subsume several of the smaller, more
scattered fixes into one mechanism instead of five independent ones — worth weighing against
the simplicity of just shipping the five targeted fixes, which are individually well-scoped
and lower-risk to reason about.

### Rollout risk, not yet discussed anywhere above

None of the ranked fixes above have discussed how they'd actually ship. A new hook that fires
on every future session, for every developer using this workflow, is a small but real and
ongoing surface — a false-positive match, a regex that's slightly too broad, or hint text that
turns out to be unhelpful costs a little bit of everyone's context on every session going
forward, for as long as it exists. Worth considering a soak period or a narrower/opt-in trial
before any of these becomes unconditional, the same caution any change to shared,
always-on infrastructure deserves.

**Resolved (post-implementation, not left open)**: decided against building bespoke soak/opt-in
machinery (a new per-hook flag, staged rollout, etc.) for these 4 specific hooks — that would be
structure this risk level doesn't need, not caution. Reasoning: all 4 already carry (a) unit
tests plus one true end-to-end integration test, (b) `hook-integrity` gate bite tests that prove
each one fires as intended via a real subprocess call, (c) universal fail-open behavior (silent
no-op on any parse error, never breaks a session), and (d) the existing repo-wide
`JUSTSEARCH_DISABLE_HOOKS=1` kill switch, live-verified this session to instantly suppress all
four — including `taskcreate-guard`'s hard block (confirmed: exit 0, not 2, with the switch
set). That kill switch already *is* the "narrower trial" lever this theorization was asking
for: if any of the four turns out noisy, disabling is one env var, not a design or code change.
Combined with the Reach section's own named observable signal (a future `mine-friction.mjs`
re-run finding these sub-patterns recur, or a promoted hook generating more false positives than
catches), the safety net this question wanted already exists — it didn't need new parts.

## Design

Settling each fix's actual mechanism required checking what already exists first — two of the
seven turned out to need a different design than the ranked list above first assumed, once
the existing code was actually read rather than assumed absent.

### F-6a — cwd visibility (new hook; no existing coverage) — **mechanism corrected during derisk**

Confirmed no existing hook tracks `cd` or cwd (`bash-guard.mjs`'s three layers are git-safety,
sleep-hygiene, and bare-read-redirect; none of them touch this). The original design proposed
a `PostToolUse` hook on Bash pattern-matching directory-changing commands (`cd`, `pushd`,
`Set-Location`) by regex. That's no longer the right mechanism: Claude Code has a **dedicated
`CwdChanged` hook event**, purpose-built for exactly this ("when the working directory
changes, for example when Claude executes a `cd` command") — confirmed against official
Claude Code hook documentation, not inferred. `cwd` itself is also confirmed to be a reliable,
per-call field on every hook payload (reflecting the shell's actual current directory at the
moment each hook fires, updating correctly as `cd` drifts it), so there's no need to estimate
or reconstruct the resulting path — the harness already hands it over directly. Revised
design: wire a new hook to `CwdChanged`, which fires exactly when needed rather than requiring
command-text pattern matching, and surface the new `cwd` via `additionalContext`, silent
otherwise — same non-blocking shape `pipe-mask-hint.mjs`/`worktree-base-hint.mjs` already use.
Simpler and more robust than the original regex-based design, not just a new instance of the
same idea. Scope stays deliberately narrow to shell-cwd drift — it doesn't try to also cover
`EnterWorktree`'s directory switch (F-3's territory) or subagent cwd handling (subagent
threads reset cwd every call by design and don't have this problem). Same underlying "state
drift" shape as F-3, two different trigger events, kept as two small mechanisms.

### F-7a — Edit-before-fresh-Read (closes a stalled prose promotion + a scoped hint hook)

Two parts, not one:

1. **Finish the promotion that already stalled.** This exact pattern was already diagnosed in
   an earlier tempdoc and flagged for promotion into the durable lessons file, but the
   promotion never happened. The cheapest, most durable piece of this fix is simply completing
   it — name the actual mechanism (a worktree-copy and a main-checkout copy of the "same" file
   don't share read state), not just "Edit sometimes needs a fresh Read."
2. **A hint hook scoped to add information the platform's own error text doesn't already
   give.** The platform's own error on this failure is already self-explanatory in the generic
   case, so a hook that just repeats it adds little. It only earns its keep if it adds the
   specific, repo-flavored explanation: on an Edit failure containing "has not been read" /
   "modified since read," check whether a same-named file was read earlier under a *different*
   root (a different worktree, or main) and only then add that pointed note; stay silent
   otherwise rather than restate the generic error.

Mechanism: `PostToolUseFailure`, matcher `Edit` — this event category already exists (today
only a generic catch-all logger), so this extends an underused wiring point rather than
inventing a new hook lifecycle stage.

**Confirmed during derisk**: the cross-root check can't simply query `intervene.mjs`'s
existing per-session read-tracking cache as first assumed — that cache is keyed by full
normalized path with only read counts (`{ [path]: { total, unbounded } }`), so it has no
record of *which other paths* a same-named file was read under. This needs a small new
tracking structure (a basename → \[full paths read this session\] map, following the same
per-session-cache-file convention `intervene.mjs` already uses) rather than reusing existing
data as-is. Small, well-precedented addition, not a blocker — just more than "reuse what's
already there."

### F-7b — TaskCreate schema (new hook, blocking) — **value recalibrated during derisk**

Unlike F-6a/F-7a, this is a hard block, not a hint: the malformed batch-array shape has no
legitimate use, so intercepting it before dispatch costs nothing, the same way existing
guards already block other unambiguous mistakes outright rather than commenting on them
afterward. Mechanism: `PreToolUse`, matcher `TaskCreate`, checking `tool_input` for the
malformed shape and rejecting with the correct one-call-per-task form. A new hook file, but
the same blocking-guard pattern already established elsewhere. Confirmed exact shape from 4
real historical failures: `{"tasks": "<stringified JSON array>"}`. **Recalibration**: the
platform's own validation error on this exact shape is already excellent — it names the
missing/unexpected parameters explicitly and states outright "TaskCreate creates ONE task per
call and has no `tasks` or `todos` parameter." So this fix's value is narrower than first
scoped: it saves one wasted round-trip, not "fixes a confusing failure" — the failure was
never confusing, some sessions just gave up after hitting it anyway. Still worth building
(zero legitimate downside to blocking it), just lower priority than its original ranking.

### F-7c (revised) — extend `intervene.mjs`, not the prose list

This is the fix that changed most on inspection. The actual failure mechanism, verified this
session: both observed large-file Read failures were main-thread (not subagent) reads where
the agent supplied its own explicit `offset`/`limit` — one was `{"offset":1,"limit":1200}` on
a roughly 43,400-token, 4,432-line, 388KB tempdoc (confirmed exact size) — that the existing
auto-limiter correctly declined to override, because it only ever steps in when the agent
supplies *no* limit at all, by design. The gap is narrower than "the known-large-files list is
incomplete": it's that nothing estimates whether an agent-supplied limit is *still* too large
for a given file. Design: extend the existing size-check to also estimate the requested
slice's likely token cost from the file's size/line-count ratio and the requested range, and
only intervene — capping the limit downward, using the same non-blocking shape already used
for the no-limit case — when that estimate would still exceed the ceiling. **Residual
uncertainty, confirmed during derisk, not fully resolved**: the exact bytes-to-token
accounting the Read tool's own ceiling uses couldn't be pinned down from static analysis alone
(a naive chars-per-token estimate against this file's real numbers didn't cleanly reproduce
the observed 43,383-token figure for a 1,200-line slice) — implementation needs a short,
direct calibration step (a few live Read calls at different limits against a known-large file)
to get the threshold right, not just the estimation formula sketched here. Broadening the
prose list remains worth doing too, as a smaller, secondary, immediate fix, but it would not
have prevented either incident actually observed; the mechanism gap would have.

### F-8a — squash-merge verification note (prose-only; correctly sized as-is)

The correct check is a single command (diff a branch's file content against `main`, not its
commit ancestry) — no multi-step procedure exists to justify a dedicated script, and building
one would be structure this problem doesn't need. A short, precise addition to the branch
workflow guidance, stating plainly that ancestry-based checks are exactly what squash-merging
invalidates, closes the gap at the size it actually is.

### F-2 — extend the existing holder-report, don't add a parallel recovery path

The teardown script already identifies which process holds a path open; it stops at
description rather than action. An unconditional auto-kill carries real risk (it could be a
legitimate process — an editor, another session) that this analysis has no basis to wave away.
The right-sized middle ground: extend the existing report to also emit a ready-to-run,
single-line kill command per holder, alongside its existing description — turning recovery
from "figure out the right command" into "copy this one line if you're sure it's safe,"
without executing anything unprompted. A small extension of an existing function, not a new
mechanism.

### F-3 — already substantially built; extend it, don't design a parallel hook

A hook mechanizing most of `verify-worktree-base` already exists in this codebase (currently
uncommitted) — a `PostToolUse` hook on `EnterWorktree` comparing the new worktree's commit
against the main checkout's, surfacing a mismatch when found. Designing a second, parallel
hook for the same trigger would have been the exact "did you check for existing
infrastructure first" mistake this repo's own rules name as its most common failure mode.

What the existing hook does not yet cover: the specific incident behind this finding wasn't a
commit mismatch at all — the new worktree and main shared the same commit, but main had
*uncommitted* changes at the moment of branching that a worktree created from a commit can
never see. The existing check is silent in exactly that case. The right design is a small,
additive extension to the same hook — alongside its existing commit-equality check, also
check for uncommitted changes in the main checkout at the same moment, using the same
silent-unless-relevant shape it already uses. One hook, two related checks, not two hooks.

**Confirmed during derisk**: "already built" needs one qualification — the existing hook file
is not yet registered in `governance/agent-hooks.v1.json` (the single authority this repo's
hook wiring is generated from; hand-editing `.claude/settings.json` directly is explicitly
disallowed by that manifest's own header comment) and so isn't live yet. Completing F-3 means
registering it (plus a `bite` test, the pattern every other registered hook in that manifest
already follows) in addition to the uncommitted-changes extension — not just adding one check
to already-active code.

## General implementation mechanics (confirmed during derisk, applies to F-6a/F-7a/F-7b/F-3)

Adding or extending a hook here is a fixed, multi-step pipeline, not a direct settings edit:
write/extend the `.mjs` file under `scripts/agent-analytics/hooks/`, add or update its entry in
`governance/agent-hooks.v1.json` (role: `advisory` for a hint, `blocking` for a hard guard),
regenerate `.claude/settings.local.json` from that manifest via
`scripts/codegen/gen-agent-hooks-wiring.mjs`, and satisfy the `hook-integrity` gate's `bite`
test (a scripted before/after check proving the hook actually fires as intended — every
currently-registered hook with meaningful logic has one). This is a known, well-trodden
process — not a source of implementation risk on its own — but it is a real step count beyond
"write the .mjs file," worth budgeting for.

## Reach

The mechanism behind all five designs above isn't a new idea for this project — it's a
confirming instance of two principles this repo already names for itself, not a third one:

1. **Recurring, evidence-confirmed friction gets promoted from prose to a hook (hint or
   block) once prose has been shown to under-perform** — already this repo's explicit,
   named philosophy for how its own rules escalate. Nothing here asks that principle to
   stretch further than it already does; this tempdoc simply supplies several more evidenced
   candidates for a promotion path that already exists.
2. **Check for existing infrastructure before designing something new** — already this
   repo's own most-named agent-discipline rule. Two of the seven designs above are the
   sharpest evidence for why it matters here specifically: in both cases, the first-draft fix
   (design a new hook; broaden a doc list) turned out to be *wrong*, not merely redundant,
   once the already-existing, already-partially-correct mechanism was actually read. Friction
   investigation is exactly the kind of work most tempted to skip this step, since the
   friction itself feels new — the fix for it often isn't.

Where else this applies: anywhere a future pass over this same kind of data proposes a new
hook or new guidance for a recurring problem. The practice worth naming — not a new structure
to build — is: before writing a new hook for an observed gap, check whether an existing
hook's trigger already covers the *event*, even if its current logic doesn't yet cover the
*specific case*. Extending an existing mechanism's scope is usually cheaper and safer than a
parallel one, and that's only visible if the existing code gets read first.

Does existing code currently violate this beyond what's covered here? Two coverage gaps were
found and addressed in this pass — a partial check that missed one real failure mode, and an
auto-limiter that correctly deferred to an agent's own choice but had no fallback when that
choice was still wrong. Both were gaps in otherwise-sound mechanisms, not evidence the
mechanisms themselves were mistaken. Whether other existing mechanisms in this repo have
similar partial-coverage gaps elsewhere is an open question this tempdoc's scope doesn't
extend to — it isn't asserted here, and would need its own look rather than an assumption
drawn from two data points.

What would show this is earning its keep, and when to stop: the observable signal is a future
re-run of the same mining process finding these specific sub-patterns at a materially lower
rate than this pass measured. If a promoted hook instead produces more false-positive noise
than genuine catches — showing up in a later pass as its own new friction entry, the same way
this tempdoc caught an already-named rule still recurring despite existing — that is the
signal to demote or remove it, not to add a second hook on top of the first. A principle that
only ever grows more enforcement and never sheds an underperforming instance of itself is the
thing worth watching for here, not something this tempdoc claims exemption from.

## Implementation review — findings and corrections

All 7 designed fixes were implemented in worktree `.claude/worktrees/727-friction-fixes` and
independently re-verified by a refute-first subagent review (default stance: every claim wrong
until proven). Full test suite (14 hook test files, one new integration test) green; the
`hook-integrity` and `prose-tier-register` governance gates both pass. Three real, lower-severity
issues were found and fixed; one high-severity finding is a platform constraint, not a code bug,
recorded here rather than fixed in code. One earlier claim in this record was corrected.

**Correction**: an earlier draft of this tempdoc's implementation notes assumed
`remove-worktree.cjs`'s `reportHolders()` (F-2) couldn't be tested through its real code path
because it "isn't exported." That was wrong — it **is** exported (`module.exports`), and was
in fact live-tested through the real function with real background processes, not just a
hand-copied equivalent command.

**Finding A (high severity, not a code bug — a platform constraint worth knowing)**: hook-code
edits made inside a worktree do not take effect for the *current* session until merged to
`main`. Confirmed via a live runtime probe: an Agent-tool subagent operating in this worktree
still executed **main's** checkout copy of a modified hook file, not the worktree's own edited
copy — consistent with Claude Code's generated hook commands resolving via
`${CLAUDE_PROJECT_DIR}`, which points at the main checkout regardless of which worktree is
active in a session. This also explains why a real `cd` didn't visibly trigger `cwd-hint`'s
output, and a real bare `cat` didn't visibly trigger the pre-existing `bash-guard`'s redirect,
during this same implementation session — neither is evidence of a broken hook. **Practical
consequence**: unit tests plus the `hook-integrity` gate's bite tests (which invoke hook files
directly via real worktree-relative paths, not through `${CLAUDE_PROJECT_DIR}`) are the correct
verification tier for hook changes made in a worktree — not a live interactive trigger from
within that same worktree session. A genuinely fresh session started after these changes merge
to `main` would be the way to confirm end-to-end live behavior.

**Finding B (medium, fixed)** — `intervene.mjs`'s `shouldCapExplicitLimit` (F-7c) originally
read and line-split the *entire* file on every bounded Read over 8KB, even when the requested
slice was tiny — defeating the purpose of offset/limit for a genuinely huge file read in small
deliberate slices. Fixed: a bounded, geometrically-growing read (`readLineRangeBounded`) that
stops once enough lines are collected, rather than reading past the requested range. Verified
identical output against the real `624` incident file before and after the change, plus a new
test with a ~7MB file confirming correctness holds when the needed lines sit at the very start
of a file far larger than the requested slice.

**Finding C (low, fixed)** — `worktree-base-hint.mjs` (F-3) didn't honor the
`JUSTSEARCH_DISABLE_HOOKS=1` kill switch, unlike every other hook touched in this pass, despite
now shelling out to `git` twice per `EnterWorktree`. Fixed: added the same `hooksDisabled()`
check the other new hooks already use. Live-verified the kill switch actually suppresses
output.

**Finding D (low, documented, not code-fixed)** — `compact-save.mjs`'s existing read-counts
reset (deliberate, for the pre-existing hot-file-read cap) also silently wipes F-7a's
`_byBasename` cross-root index on every compaction, narrowing `edit-reread-hint.mjs`'s
real-world coverage to reads since the last compaction. Not re-engineered (a partial reset
would add structure this narrow a gap doesn't need) — documented with a comment at both the
reset site and the index's definition instead.

**Test-coverage gap (fixed)**: the originally-shipped `intervene.test.mjs`/
`edit-reread-hint.test.mjs` tests for F-7a's cross-root mechanism only exercised the reader
(`getOtherPathsWithSameBasename`) against a hand-written synthetic cache fixture — neither
proved the real writer (`trackRead`, only reachable via `intervene.mjs`'s `main()`) produces a
cache the reader can correctly consume. Added
`scripts/agent-analytics/hooks/edit-reread-integration.test.mjs`: spawns the real
`intervene.mjs` and `edit-reread-hint.mjs` subprocesses (matching how Claude Code itself
invokes them) to prove the full round trip, including a symmetry check and a true-negative
case (an unrelated, never-read path stays silent).

## Fit-review closure — the two items a tempdoc-fit check found missing

A conceptual re-read of this tempdoc against the implementation found two things the tempdoc
itself had asked for that hadn't actually been done. Both are now closed:

**F-7c's secondary fix (`context-efficiency.md`'s known-large-files list) — done.** Broadened
to include `docs/observations.md` and a general "any large tempdoc" note, per the Design
section's own instruction. While making this edit, the `always-loaded-budget` ratchet gate
(`node scripts/ci/check-always-loaded-budget.mjs`) failed: `context-efficiency.md` and
`agent-lessons.md` were each sitting *exactly* at their byte ceiling before this addition (zero
margin), so the new content needed an equal trim elsewhere, not just a bump (the ceiling never
ratchets up, by design). Both files are now back within budget — trimmed a duplicate
cross-reference to a CLAUDE.md rule and tightened a few verbose existing bullets, no content
lost. **Separately found, and NOT fixed here (logged as an observation instead)**:
`branch-safety.md` and `tier-register.md` were already over their own ceilings *before* this
session touched them at all — pre-existing debt from earlier tempdocs, unrelated to this work,
just made marginally larger by this tempdoc's own (gate-required) rule registrations. Left for
whoever owns that reconciliation, per "log pre-existing issues, don't fix them" — trimming only
this tempdoc's 2 rows out of step with the other ~40 in the same table would be cosmetic, not a
real fix.

**Rollout-risk theorization — resolved, not left open.** See the "Resolved" note added directly
under "Rollout risk" in the Theorization section above: decided against new soak/opt-in
infrastructure, since the existing kill switch (`JUSTSEARCH_DISABLE_HOOKS=1`, now live-verified
against all 4 hooks including `taskcreate-guard`'s hard block) already provides the same
protection a bespoke mechanism would add, without new structure.

## Verification evidence (session-closeout pass)

Every claim above that says a test/gate/command passed is backed by one of these, re-run fresh
at closeout time (not just recalled from earlier in the session) rather than left as an
unqualified assertion:

- **"Full test suite (14 hook test files, one new integration test) green"** — re-run at
  closeout: `for f in scripts/agent-analytics/hooks/*.test.mjs; do node "$f"; done` inside
  `.claude/worktrees/727-friction-fixes` → all 15 files (the count grew to 15 including
  `edit-reread-integration.test.mjs`) printed `all N checks passed` / `N passed`, 0 failures.
  Per-file counts: bash-guard 51, build-counter 11, compact-restore 8, cwd-hint 9,
  dataset-cache-hint 28, docs-granularity-hint 12, edit-reread-hint 9,
  edit-reread-integration 6, intervene 19, known-state-hint 12, observation-shard-hint 6,
  pipe-mask-hint 47, repeat-guard 4, taskcreate-guard 10, worktree-base-hint 7.
- **"`hook-integrity` and `prose-tier-register` gates both pass"** — re-run at closeout:
  `node scripts/governance/run.mjs --gate hook-integrity --mode gate` → exit 0,
  `governance: 1 gate evaluated, 0 fail, 0 findings ... hook-integrity: pass`.
  `node scripts/governance/run.mjs --gate prose-tier-register --mode gate` → exit 0,
  `governance: 1 gate evaluated, 0 fail, 0 findings ... prose-tier-register: pass`.
- **`taskcreate-guard`'s kill-switch behavior** — re-verified at closeout with the real
  malformed payload piped through the real hook file: with
  `JUSTSEARCH_DISABLE_HOOKS=1` set, exit code 0 and silent stdout; unset, exit code 2 with the
  block message ("TaskCreate was called with a `tasks` key... blocked before dispatch").
- **`context-efficiency.md`/`agent-lessons.md` back within budget** — re-run at closeout:
  `node scripts/ci/check-always-loaded-budget.mjs` shows both `ok` (context-efficiency.md
  1934/1955 B, agent-lessons.md 9647/9680 B).
- **Correction to the budget-overage claim above**: re-running that same command at closeout
  found **4** files over ceiling, not 2 — `branch-safety.md` (12099/10581 B) and
  `tier-register.md` (17304/15725 B), as already stated, **plus two not previously mentioned
  in this tempdoc**: `CLAUDE.md` (24260/22656 B) and `.claude/rules/hooks-reference.md`
  (2839/2740 B). Confirmed via `git diff origin/main -- CLAUDE.md .claude/rules/hooks-reference.md`
  (empty output, both files) that neither was touched by this worktree's commit — this is
  separate pre-existing drift from other sessions that landed on `main` while this work was in
  progress, not caused by tempdoc 727. Logged to the observations shard
  (`docs/observations.d/e8c883b6-6084-42ea-8a08-6148373891b2.md`), not fixed here, same
  reasoning as the other two files.

### Unverified assumptions / not reproducible at closeout

These claims elsewhere in this tempdoc rest on observations made earlier in the implementation
session and are **not** re-confirmed by a fresh command at closeout time — recorded here
explicitly rather than left standing as unqualified fact:

- **Finding A's live runtime probe** ("an Agent-tool subagent operating in this worktree still
  executed main's checkout copy of a modified hook file") — a one-time observation from a
  specific subagent dispatch earlier in the session; not independently re-run at closeout. The
  underlying mechanism (`${CLAUDE_PROJECT_DIR}` resolving to the main checkout) is a platform
  behavior, not a repo-code claim, so there's no repo-side command that would re-prove it here.
- **Finding B's "verified identical output against the real 624 incident file before and
  after the change"** — the specific before/after comparison was done earlier in the
  implementation session; the regression test added for it (a ~7MB synthetic file case inside
  `intervene.test.mjs`) is the durable, re-run-at-closeout proof instead (see above), which is
  why the claim is trustworthy going forward even though the original one-off comparison itself
  wasn't repeated here.
- **F-2's correction that `reportHolders()` was "live-tested through the real function with
  real background processes"** — a one-time manual test against real `node.exe` processes
  earlier in the session; not repeated at closeout (would require spawning and orphaning a real
  background process again, which isn't warranted just to re-confirm a already-narrow, already
  test-covered claim).
- **F-6/F-7/F-8's underlying friction-mining numbers** (the aggregate table, the timeline
  thirds, the per-category counts) — these come from `tmp/agent-telemetry/friction-aggregate.json`
  / `friction-timeline.json`, which are gitignored, point-in-time outputs of
  `aggregate-friction.mjs`/`friction-timeline.mjs` and are **not** re-generated at closeout (the
  underlying judge run cost real API spend and its 58-session classification depends on
  `friction-excluded-sessions.json`, which hasn't changed). Regenerate via
  `node scripts/agent-analytics/aggregate-friction.mjs` /
  `node scripts/agent-analytics/friction-timeline.mjs` if these numbers need re-confirming.
