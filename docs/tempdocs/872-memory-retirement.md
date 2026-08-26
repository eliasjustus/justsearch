---
title: "872 — Stop needing memories: retire the observations store, route findings at discovery"
type: tempdocs
status: implemented + reviewed (PR #571 green at 34921ac6, CI run 32926396477; awaiting merge); open items in §6; post-merge step in §4
created: 2026-08-26
updated: 2026-08-26
author: agent session (Fable 5), taking over the 2026-08-26 analysis thread (Opus 5)
charter: "agents should not need to write memories — every memory names a gap the system should close instead"
supersedes: "680 (executes its recorded fallback and goes one step further); 821 §7 D5 (the store is no longer the defect home); 665/618 Seam C/862 (shard mechanics)"
---

# 872 — Stop needing memories: retire the observations store, route findings at discovery

## §1 The question, and what the data actually says

Owner prompt: *"analyse what the memories currently are, that agents have written. the goal is not
to need memories anymore. how do we need to update system documentation."*

The memory surfaces at the start of this work:

| Surface | Size | Read by |
|---|---|---|
| `docs/observations.md` conditions store | 565 conditions / 924 occurrence lines | nobody (517 kinds never confirmed; 1 probe) |
| `docs/observations.d/*.md` shards | 7 live (2 sessions, 33 notes written *that day*) | the fold only |
| `scripts/agent-analytics/expected-state.v1.json` | 2 pins, unchanged since 2026-07-06 | `known-state-hint` — at the moment of relevance |
| `.claude/rules/agent-lessons.md` | 9,247 B | every session |
| `docs/reference/contributing/agent-postmortems.md` | 27 handles | on demand |

Two claims from the preceding analysis thread were re-checked and fell:

1. **"One bug rediscovered by 25 agents" — false.** `seen` counts notes sharing a *file anchor*, not
   recurrence. `obs:unifiedchatview` (seen 25) held 23 distinct findings; `remove-worktree` 16/16;
   `searchv3view` 10/10. The recurrence ranking 680 was built on barely exists. The only genuine
   repeats were **"main is broken in a known way"**: `envelopestream-test` flaky ×14,
   `gen-token-names --check` RED ×12, `gen-component-vocabulary --check` stale ×12,
   `pluginloader-test` ×7.
2. **Those reds existed because the checks are not enforced.** Both generators were green again
   on 2026-08-26, but sat red for weeks because they live only in the advisory edit-time
   `ui-web-gates` recipe (`governance/consult-register.v1.json`) — no hosted CI step ran them.
   The memory was a stand-in for a missing gate.

And one finding the thread did not make: **the `log, don't fix` rule manufactures memories.** The
day's live shards held the same stale-comment note twice in one session
(`AgentToolsOperationCatalog.java:187-192`), the same `BrowseTool` note twice, `jf-control` twice.
An agent that finds a wrong one-line comment writes a note instead of fixing it — because
`rule:log-pre-existing-issues` told it to.

## §2 What a memory is, and which classes are closable

A memory gets written when the system (a) told the agent something false, (b) failed to tell it
something true, (c) tolerates a broken state, or (d) has no slot for a finding. (a)–(c) are gaps in
the system and closable; (d) is a backlog, not a memory problem.

| Class | Share at retirement | Closable? | By |
|---|---|---|---|
| Doc/comment lies | ~20 + 5 that day | yes, fully | fix at discovery; the existing drift gates |
| Main red / flaky test | ~57 `environment` + 4 that day | yes | enforce the gate in CI; a pin is a *dated exception with an exit* while the fix lands |
| Platform gotchas / lessons | 24 + `agent-lessons.md` | mostly | must/never → hook; the rest is legitimate durable docs |
| Out-of-scope product bugs | ~350 + ~20 that day | **no — not a memory problem** | owning tempdoc / domain register, or fix-at-discovery |
| Work scheduling (`STANDING TAKEOVER …`) | 1 | yes | a tempdoc, never a note |

680's own falsification clause (`680:176-191`): *"if, after implementation, two consecutive months of
read-model output go unconsumed, switch to B"* (the honest scratchpad). Implemented 2026-07-06;
7.3 weeks; 821 §C8 says outright "the store's triage pass has never run". The clause fired. 872
executes it and goes one step further than B: **no pile at all** — a lossy pile still teaches
agents that noting is a valid substitute for fixing.

## §3 The two owner decisions, and how they were taken

Proceeding was authorised as "do that and proceed autonomously" on the assessment in §1–§2.

1. **Where do bugs go?** No external tracker is introduced (a public GitHub Issues dump is an
   outward-facing publication the owner did not explicitly approve). Bugs an agent will not fix
   now go to **the owning tempdoc's open-items section** or the domain register; small doc/comment
   drift is fixed in place. Reopen this if a tracker is wanted — everything else in 872 stands
   either way.
2. **Accept 680's fallback and delete the store rather than make it TTL?** Yes — deleted. The
   content is in git history (last full store: commit `7b85a5a6`,
   `git show 7b85a5a6:docs/observations.md`). Deleting an unread note asserts nothing about the
   bug; 821 §6.1 showed a third of *verified* "fixed" verdicts wrong, so the originals are no
   cleaner, and per-item verification of 523 uncorroborated claims nobody consumes is not work
   worth doing.

## §4 What shipped (this PR)

**Rule + delivery**
- `CLAUDE.md` `rule:log-pre-existing-issues` rewritten → *Route Out-of-Scope Findings, Don't Log
  Them* (four destinations; predictable evasion named). Header pointer and `## Pointers` entry to
  the store removed. Always-loaded budget rebalanced **down** (4 files shrank; total ceiling
  54,816 → 53,848 B).
- `note-observation.mjs` is now a **router**: prints the destination table to stderr and exits 2,
  writes nothing. `resolveSessionId` stays exported (shared by `record-merge.mjs`,
  `preview-squash-message.mjs`). A subagent still carrying an old brief is redirected, not fed a
  dead file. Test rewritten (`note-observation.test.mjs`: CLI creates no files).
- `subagent-guide.mjs` observations protocol → report findings in the result with `file:line`; fix
  one-line drift in place.
- `report-flake-trend.mjs` WARN remedy → a dated expected-state pin, not a note (test updated).

**Retire with a sweep**
- Deleted: `fold-observations.mjs` (+test), `observations-triage.mjs` (+test),
  `lib/observations-store.mjs` (+test), `hooks/observation-shard-hint.mjs` (+test),
  `docs/observations.d/` (README, `.gitkeep`, the 7 live shards — see §6 for their unrouted
  content). `observation-shard-hint` removed from `governance/agent-hooks.v1.json`; wiring
  regenerated; `hook-integrity` gate passes.
- `docs/observations.md` → a retirement notice (frontmatter `status: retired`).
- `scripts/ci/check-no-observations-shards.mjs` (in `ci.yml`): fails legibly if
  `docs/observations.d/` reappears. Needed because sessions already running on the pre-872
  brief kept writing shards — the publish catch-up merge found 7 new shard files (35 notes)
  on `main` after the #568 fold; they were routed (see §6) and the directory deleted again.
- `.claude/rules/branch-safety.md` (post-merge fold step, inbox bullet, docs-ride-along
  `observations*` clauses), `hooks-reference.md`, `context-efficiency.md`.
- Canonical docs/skills (14 files, sonnet sweep, orchestrator-reviewed): `development-philosophy.md`
  (two-tier table → route-at-discovery), `writing-docs-for-ai.md`, `tier-register.md` row 15,
  `search-quality` / `inference-runtime` skills + registers (historical wording),
  `docs-maintenance` / `takeover` skills, `secondary-views-behavior.md`,
  `21-agent-analytics-pipeline.md`, `triage-psi-drift.md`, `smoke-test-health-event-substrate.md`,
  `03-knowledge-server.md`, `observability-scoped-fix-playbook.md`,
  `gates/npm-audit/.changesets/README.md`, `gates/consumer-drift/slots.json` `$comment`.
  `llms.txt` and skills regenerated.

**Make the recurring reds impossible instead of remembered**
- `scripts/ci/run-ui-web-gates.mjs` — runs the ui-web gate set by **parsing the
  `ui-web-gates` recipe** (one list, no fork); wired into `ci.yml`. Found on the way: the recipe's
  `run.mjs --gate a,b,c` syntax is not accepted by `run.mjs` (single id) — the runner expands it.
  **Review fix (refuter obj. 1):** a prose parser can silently shrink — dropping the
  `(node scripts/ci/<name>.mjs)` marker parsed 6 commands and printed `6/6 passed`. Now:
  `EXPECTED_MIN = 40` floor (exit 1 below it), the self-test parenthetical is parsed as a
  command, and `run-ui-web-gates.test.mjs` (also in CI) string-diffs every name in the recipe
  against the parse. 40/40 green.
- `scripts/dev/prepare-worktree.cjs` regenerates the hooks block when it drifts from the
  manifest (`.worktreeinclude` copies the parent's real `settings.local.json` in, so a new
  worktree otherwise inherits a wiring for a deleted hook and its Stop hook exits 1 visibly).
  Existing trees: run `node scripts/codegen/gen-agent-hooks-wiring.mjs` once after merge.
- `scripts/agent-analytics/expected-state-probe.mjs` (`--gate` in `ci.yml`): a pin must carry an
  exit (`exitProbe` and/or `reviewBy`); past `reviewBy` or a fired exit fails. The pin contract
  comment now states *a pin is a dated exception, not a steady state*.
- `known-state-hint` renders **every** matched pin (the silent `slice(0, 4)` cap is gone) and
  shows `reviewBy`. Match patterns tightened: `gradlew … -x test` no longer triggers the
  gradle-test pin (it fired on this session's own compile), the two vitest pins fire on a full
  suite or their own file only.
- Pins added (reviewBy 2026-09-30): `ui-web-envelopestream-heartbeat-flaky`,
  `ui-web-pluginloader-module-mode-timeout`. The two 2026-07-06 pins got a `reviewBy`.

**Doc lies fixed in place (the class the rule now covers)**
- `22-agent-system-architecture.md`: `core_file_operations` "delete" → MOVE/RENAME/MKDIR/COPY, no
  delete (`FileOperation.OpType`).
- `AgentToolsOperationCatalog.java` browse comment: `BrowseTool` *does* read `list_files`.
- `docs-validate.mjs` no longer **crashes** on one bad front matter; it reports
  `frontmatter-parse` and continues. Two crashes (530, 565) had hidden every other result for
  weeks; 530's `updated:` value quoted.

## §5 Verification

- `node scripts/agent-analytics/run-all-tests.mjs` — 49/49.
- `node scripts/governance/run.mjs --gate hook-integrity --mode gate` — pass.
- `node scripts/ci/check-always-loaded-budget.mjs` — pass after `--rebalance` (down only).
- `node scripts/ci/check-workflow-triggers.mjs` — OK.
- `node scripts/ci/run-ui-web-gates.mjs` — 34/34 (after the kernel-gate expansion).
- `node scripts/agent-analytics/expected-state-probe.mjs --gate` — 4 pins, 0 problems.
- `known-state-hint` match probe: `build -x test` → no pin; `gradlew test` → vdu pin;
  `npm run test:unit:run` → both vitest pins; `vitest run PluginLoader` → pluginloader only.
- `./gradlew.bat spotlessApply` + `build -x test -PskipWebBuild=true` — see PR.
- Live: the hint fired on this session's own commands (680's open assumption #1, resolved twice).
- **Review pass (refute-first, Opus, 2026-08-26):** 15 claims — 10 confirmed, 4 partial, 1 refuted
  (the runner parsed 39 not 34 and dropped the keybinding self-test). 11 objections; all fixed in
  the same PR except the ui-web vitest CI lane (§6). Anchored outside the tempdoc: CI run
  32921552074 job log (`ui-web gates: 39/39 passed`; probe `5 pin(s); 0 problems`),
  `note-observation.mjs` live CLI (exit 2, `git status` clean), the Stop hook in a stale tree
  (`MODULE_NOT_FOUND`, exit 1, visible non-blocking), all 40 shard notes present in
  `7b85a5a6:docs/observations.md`.

## §6 Open items (routed here, per the rule)

- **Fix the two pinned flakes** (owning: ui-web). `EnvelopeStream.test.ts` heartbeat-watchdog
  reconnect under full-suite load (deterministic timer control); `PluginLoader.test.ts` module-mode
  5000 ms timeout on a cold run. Delete the pin in the fixing PR (the probe gate will demand it
  after 2026-09-30 anyway).
- **The ui-web vitest suite (`npm run test:unit:run`) runs in no CI lane** — the recipe's last
  line names it, but hosted CI has no step (typecheck is pinned RED on TS5101; the suite carries
  two pinned flakes). Adding the lane now would make the shared merge queue intermittently red,
  so it is **not** added in 872 — owner call, tied to fixing the two flakes above. Until then this
  is a state main can hold silently, i.e. the class 872 exists to remove.
- **30 tempdocs with unparseable front matter**, now reported by `docs-validate` instead of
  crashing it: 565, 567, 570, 571, 585, 586, 587, 589, 591, 592, 594, 600, 603, 655, 665, 669,
  680, 681, 686, 687, 740, 749, 754, 763, 764, 765, 811, 852, 857, 863. Dated history; fix
  opportunistically (quote the offending value) or exclude tempdocs from front-matter parsing.
  `docs-validate` itself is not a CI gate and is red on main for other pre-existing reasons
  (Title Case, tags/aliases) — its exit code is not a signal today.
- **The 6 deleted shards' content is routed** (review fix, refuter obj. 6; shard *content* is
  folded into `observations.md` at `7b85a5a6` — the shard files themselves are not there).
  Appended verbatim under `## Open items routed from the retired observations store` in
  859 (12 notes: sv3 a11y/console/cancel/stop-affordance/budget), 860 (1: the
  `STANDING TAKEOVER … P1+P2 / P4+P5` dispatch), 868 (10: `NO_TOOLS`, availability-gated tools,
  `OperationPolicy.retry()`, `BrowseTool` `max_files`, `path_prefix`, GPU runtime in worktrees,
  n_ctx 4096, banner copy, api-contract-map claim, javadoc warning). 5 handled by 872 itself
  (two doc fixes, three pins). **Unowned — no active tempdoc** (named here so they are not a
  hidden pile; whoever reopens the area takes them): `remove-worktree` blocked by a CWD-inside
  holder with no registry record (861 closed); 861 W3's F5 test writing into the production
  `agent-spawns/` register (861 closed); GitHub merge queue silently dropping an enqueued PR
  (#549, #559 — no 829 open item); `WorkerMethvinWatcherTest` flaky once; `OnnxEmbeddingEncoder`
  long-doc forensic test flaky on one machine. **`WatchedRootScanCollectionTest` recurred** in
  this PR's post-merge full run (2372 tests, 1 failed; passes on `--rerun`) → pinned
  `app-services-watched-root-scan-collection-flaky`; the fix (in-process gRPC scan racing the
  watcher under full-suite load) is owed to app-services.
- **`check-tempdoc-numbers` cross-worktree blind spot** (retired `obs:check-tempdoc-numbers`, seen
  9) and **dev-runner bound to the main repo path** (seen 8) were the two other genuine repeats —
  both real, both defects, both belong to their scripts' owners (`scripts/ci`, `scripts/dev`).
- **Domain-register hygiene**: `search-quality-register.md` D-004 and the register skills now say
  "routed into the retired store"; the entries that were live there are in `7b85a5a6` for
  whoever next touches those registers.
- **`scripts/dev/run-gh.mjs checks-wait` reports PASS on CLA alone** when the `CI` workflow run
  has not yet registered on the PR rollup (observed twice on #571: "all checks green" with only
  `cla-assistant` present; the CI run was `pending` in `gh run list`). Its pre-poll should wait
  for the `CI` workflow specifically, not for "any check". Owner: `scripts/dev`.
- **Publish catch-up (2026-08-26): 7 post-fold shards, 35 notes, routed** — 8 genuinely new
  notes appended to 859 (1), 869 (4), 871 (2), 847 (1); the rest were duplicates of notes already
  routed above. New **pin candidates** (not pinned — add only when they bite a session again):
  ui-web vitest exits non-zero on an unhandled `ECONNREFUSED` from the dev-proxy probe
  (`vite.config.js:73`) even with all tests passing; `./gradlew test` under *concurrent-agent*
  load times out random worker-core/worker-services tests (30 s `TimeoutException`, zero
  assertion failures — the one-Gradle-at-a-time convention in `branch-safety.md`, not a defect).
  New **unowned**: the Settings "Solid surfaces" toggle in `renderAppearance()` is unreachable;
  the recipe's `--gate a,b,c` wording (fixed in place in `consult-register.v1.json`).
- **Owner call, still open**: an external defect tracker. Not needed for 872 to hold.

## §7 What this does NOT claim

It does not claim agents will stop finding things. It claims that the four things a note used to
carry now each have a place that is *acted on*, that the recurring class ("main is red") is a CI
failure instead of a memory, and that the one delivery mechanism that works (a pin surfaced at the
moment of relevance) is now contractually dated so it cannot rot into a second pile.
