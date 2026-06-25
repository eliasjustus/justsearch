---
title: "547 — Discipline-mechanization audit: which honor-system rules can become gates/hooks"
type: tempdoc
status: done
---

> NOTE: Noncanonical investigation. The ranked opportunities are recommendations, not commitments.

# 547 — Discipline-mechanization audit

**Date:** 2026-05-25
**Method:** static audit of the ~18 `prose-only` rows in `.claude/rules/tier-register.md`
against the *current* enforcement surface (the tempdoc-530 discipline-gate
kernel + the `scripts/agent-analytics/hooks/` system), asking per rule: *can
this now flip from honor-system (~70% adherence) to mechanized (~100%)?*
**Context:** continues the 530 §Meta-loop program. This session shipped two
mechanizations along the way — the `consumer-drift` gate (531) and the
`ssot-catalog-sync` gate — and this audit determines where the next ones are.

## Headline finding

**The prose-rule register is mostly *correctly* prose-only.** Of the ~18
`prose-only` rows, ~12 are judgment/platform rules that are unenforceable *by
construction* — you cannot detect "did you explore before implementing", "did
you do a critical-analysis pass", or "did you brief the subagent inline" from a
diff or a hook. They are prose-only because the thing they govern is judgment,
not a measurable artifact. Mining this register for more gates yields little.

**The high-value mechanizations live elsewhere — in the documented
*silent-failure* surface** (CLAUDE.md "Common Pitfalls", the pre-merge script
checks). Those are concrete, file-observable invariants. `ssot-catalog-sync`
(this session) came from exactly there ("Classpath catalog drift"), and it's
non-inert and high-value on day one. **Recommendation: redirect the meta-loop's
mechanization effort from the prose-rule register to the silent-failure surface.**

## Per-rule verdict (prose-only rows)

| # | Slug | Mechanizable now? | Note |
|---|---|---|---|
| 5,6,11,12,13,14,17 | explore-before / fix-root-causes / interrogate-results / structural-defects / tempdoc-contract / stay-focused / ask-when-uncertain | **No** | Pure judgment; no measurable artifact. Correctly prose-only. |
| 9,10,29,30 | audit-needs-test / critical-analysis-pass / bidirectional-pass / independent-reviewer | **No** | Workflow judgment; "did you do the pass / dispatch a reviewer" isn't diff-detectable. (This session *practiced* #30 by dispatching an independent reviewer — but practicing ≠ enforceable.) |
| 15 | log-pre-existing-issues | **No** | Needs to read intent ("you noticed X but didn't log it"). |
| 27,28 | subagents-no-inheritance / parent-hooks-dont-fire | **No** | Platform constraints (Anthropic), not repo-enforceable. |
| 20,21 | never-share-worktree / one-branch-per-worktree | **No / already structural** | No session-ownership signal; git already structurally forbids #21. |
| 4,8 | verify-dont-guess / use-every-verification-tier | **No** | Can't tell "guessed vs verified" or "stopped at AI_OFFLINE" mechanically. |
| **7** | **verify-your-work** | **Partial (advisory)** | A `git commit` PreToolUse hook could cross-reference `intervene`'s edit-counts (edited modules) against `events.ndjson` (gradlew test invocations this session) and warn "edited module X, no test run." Feasible, but heuristic → **false-positive-prone** (doc-only edits; tests run in a prior session). Hint-tier at best. |
| **22** | **after-compaction-verify** | **Partial (low-effort)** | `compact-restore` already injects orientation; it could also inject the current branch + `pwd` into the restored `compaction-state.md` so the agent sees it without running commands. Doesn't *enforce*, but removes the need. Cheap. |
| **24** | **never-delete-untracked-in-main** | **Partial (false-positive risk)** | `bash-guard` already gates Bash in main; it could block `rm -rf` / `mv` of non-`.gitignore`d paths there. Can't do *ownership* (the rule's actual subject), and risks blocking legitimate `rm` of one's own files. Marginal. |
| 26 | pre-merge-gradle-build | **No (practically)** | Can't run a build in a hook; build-freshness isn't cheaply detectable. CI already catches at PR time. |

## Ranked opportunities (if the team still wants register-derived mechanization)

1. **#22 compact-restore branch/pwd injection** — lowest effort, no false
   positives, directly serves a real post-compaction discipline. Best value/risk.
2. **#7 verify-your-work commit hint** — highest-value *rule* but advisory +
   noise risk; only worth it if calibrated against telemetry to fire rarely
   (ties into 520 P2 "dead-hint-hook telemetry" — measure before adding a hint).
3. **#24 rm-in-main guard** — marginal; the ownership semantics it really wants
   aren't mechanizable, and the partial version risks false positives.

None is compelling enough to rush. The silent-failure candidates were
investigated **on their merits (not on weight)** and both turned out to be
*already covered* — the honest result, recorded here:

- **Schema/fixture drift — ALREADY MECHANIZED, do not build.** `captureOrVerify`
  (e.g. `HealthEventSchemaTest.java:118`) asserts `baseline == current` and
  **fails on drift**; the ~8 `*SchemaTest` classes (app-api, app-observability,
  app-agent-api, …) run in the standard `./gradlew test` suite. A `schema-drift`
  gate would duplicate this — a parallel-enforcement violation the 530 kernel
  explicitly forbids. *(Earlier draft of this doc ranked it high and parked it
  as "heavier (needs the Java generator)" — that was a cost-excuse; the real
  reason is redundancy.)*
- **`wire-emitter-elision` — already tripwired for high-risk types; no clean
  general gate.** `KnowledgeSearchResponseContractTest` reflects the record's
  `getRecordComponents()` and fails if they don't match the controller-mapped
  field set (the established per-type pattern). A *general* gate would need a
  declarative signal for "which records are hand-emitted by a manual map" —
  which doesn't exist, so it can't be a file-compare like `ssot-catalog-sync`.
  Real feasibility limit, not weight. Use a per-type contract test (the
  existing pattern) when a new hand-emitted type appears.
- **Folding standalone pre-merge scripts into the kernel** (`check-workflow-triggers`,
  `check-root-readme`, `check-runtime-manifest-closure`) — *consolidation* only
  (uniform SARIF/changeset), lower marginal value.

## Meta-conclusion: the mechanization frontier here is largely exhausted

Between the discipline-gate kernel, the `*SchemaTest`/`*ContractTest` suites,
the hook system, and the two gates this session added, this codebase has
**already mechanized most of its silent-failure surface**. What remains
`prose-only` is prose-only because it's judgment; what looked like
unmechanized silent-failures (schema drift, wire elision) is already covered.
That is itself the finding: new gate-building has low marginal return here, and
the highest-value remaining structural work is *decomposition* (e.g. the
`AgentLoopService` plan in tempdoc 240), not more enforcement.

## Conclusion

The 530 meta-loop's value is real, but its *target* should shift. The prose-rule
register has been mostly mined out — what remains prose-only is prose-only for
good reason. Future mechanization budget is best spent on documented
silent-failure invariants (the `ssot-catalog-sync` pattern), not on trying to
gate judgment. This audit is the evidence for that redirection; it closes the
"audit the discipline surface" item without recommending a low-value build.
