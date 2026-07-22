---
title: Observations
type: observations
status: noncanonical
---

# Observations

## Rules

This file holds **conditions** — grouped observations — not a flat inbox (tempdoc 680). Writers
stay cheap and blind; identity, status, and routing live here at the store.

### Writing (any session)

When you notice a behavioral issue outside your task's scope, log ONE flat line and keep working.
**Do not read this file first and do not check for duplicates** — re-observation is signal, not
noise: at the next fold it bumps the matching condition's `seen` count, which is the triage
ranking. Skip only structural commentary (file too long, naming style) unless it caused a problem.

```
node scripts/agent-analytics/note-observation.mjs "<description> — `optional/file:line`"
```

The note lands in your per-session shard under `docs/observations.d/` (618 Seam C — commit the
shard with your work) and is folded here at merge-teardown:

```
node scripts/agent-analytics/fold-observations.mjs --apply
```

### Reading / triage

```
node scripts/agent-analytics/observations-triage.mjs            # read-model: new, top-by-seen, proposed retirements, parked
node scripts/agent-analytics/observations-triage.mjs --probe    # janitor: re-run probes; exit 0 => condition gone => proposes retirement
```

Conditions are processed at the maintainer's periodic triage pass. Kinds route them onward —
**defect** → `docs/reference/issues/` or the owning domain register; **environment** (facts about
main/CI/machines that verification hits) → `scripts/agent-analytics/expected-state.v1.json`;
**lesson** → the delivery pipeline (hooks / `agent-lessons.md` / postmortems), not prose that ages
here; **follow-up** → its owning tempdoc or register. The store is a buffer, not a home: a
condition that two consecutive triage passes cannot route gets `status: parked (<reason>)` with an
explicit revisit trigger, never silent aging.

### Condition grammar

`### obs:<slug> — <title>`, then one backtick field line, then the occurrence lines verbatim:
`kind` (trailing `?` = fold-proposed, confirm at triage) · `anchor` · `seen` · `first`/`last` ·
optional `probe` (a command; **exit 0 means the condition is gone** — prefix `slow:` for probes
only run with `--slow`) · optional `status` (`proposed-retire (<evidence>)` / `parked (<reason>)`).

### Resolving

Delete a condition when its fix lands — the commit (or tempdoc) is the permanent record — or
accept a `proposed-retire` at triage. Deletion is always a human act; automation only proposes.

## Conditions

### obs:shell — 3 unused eslint-disable(no-console) directive warnings — `modules/ui-web/src/shell-v0/chrome/Shell.t
`kind: defect` `anchor: modules/ui-web/src/shell-v0/chrome/Shell.ts` `seen: 3` `first: 2026-05-25` `last: 2026-06-04`
- [ ] 3 unused eslint-disable(no-console) directive warnings — `modules/ui-web/src/shell-v0/chrome/Shell.ts:409,753,1253` (2026-05-25)
- [ ] Shell.ts has 4 redundant `// eslint-disable-next-line no-console` directives above `console.warn` calls (config allows warn) — lines ~426/804/811/1346 (2026-05-30)
- [ ] 565 §12.3.E: two SourcesPane instances exist at wide viewport (the docked rail + the dormant display:none OverlayHost drawer), both subscribed to agentSession+selectedSource — redundant render work, not a bug; consider gating the drawer mount out in agent mode — `Shell.ts`/`SourcesPane.ts` (2026-06-04)

### obs:agent-tool-arg-coercion — Agent tool schema rejects string-typed numbers ("limit":"10") — burns an iteration every session; no coercion at tool boundary
`kind: defect` `anchor: modules/app-services/.../registry/executor/OperationInputSchemaValidator.java` `seen: 2` `first: 2026-05-30` `last: 2026-06-11`
- [ ] Agent search tool rejects string `limit` arg ('string found, integer expected') — model passed {"limit":"1"}, wasted one agent iteration before retrying with integer; tool should coerce or schema should constrain — `modules/app-agent` core_search_index arg handling (2026-05-30)
- [ ] Agent loop burns iteration 1 every session on the same schema rejection: LLM emits `"limit":"10"` (string), OperationInputSchemaValidator rejects ('string found, integer expected'), no coercion at the tool boundary — recurs across sessions (live-verified 2026-06-11, tempdoc 577 §2.9). Consider lenient numeric coercion or prompt-side schema hinting — `modules/app-services/.../registry/executor/OperationInputSchemaValidator.java` (2026-06-11)

### obs:activate — V1.5 dev-mode: Vite middleware adds `?import` query to dynamic-import URLs targeting `public/` stati
`kind: lesson?` `anchor: modules/ui-web/dev-examples/custom-ui-focus/activate.js` `seen: 2` `first: 2026-05-07` `last: 2026-05-07`
- [ ] V1.5 dev-mode: Vite middleware adds `?import` query to dynamic-import URLs targeting `public/` static files, returning 500. Workaround: fetch source as text + emit as `data:` URL. Production Tauri builds don't go through Vite; this is dev-only. — `modules/ui-web/dev-examples/custom-ui-focus/activate.js` (2026-05-07)
- [ ] V1.5 alpha: `btoa()` rejects non-Latin1 characters; UTF-8-encoded JS source needs `TextEncoder` + byte-string conversion before base64. Pattern documented in `dev-examples/custom-ui-focus/activate.js`. — `modules/ui-web/dev-examples/custom-ui-focus/activate.js:fetch-and-package` (2026-05-07)

### obs:baseline — ts-any baseline needs seeding before the gate can be wired to CI in gate-mode. The gate currently fl
`kind: defect?` `anchor: gates/ts-any/baseline.txt` `seen: 2` `first: 2026-05-30` `last: 2026-05-30`
- [ ] ts-any baseline needs seeding before the gate can be wired to CI in gate-mode. The gate currently flags every `any` cast in the codebase as 'new growth' because gates/ts-any/baseline.txt is empty. Operator decision: seed via `node scripts/governance/run.mjs --gate ts-any --rebalance` once an initial count is desired, OR adopt a stricter zero-baseline once the codebase is cleaned. — `gates/ts-any/baseline.txt` (2026-05-21, surfaced by tempdoc 530 Pass-7 Phase B)
- [ ] `clone` gate fails on `main` — silent-growth 0→2 cloned blocks in NavigationHistoryStore.java, AuthorizationOutcomeStore.java, OperationHistoryStore.java, SearchResultMapper.java and 35→36 in ToolIteratingShapeRunner.java, no declared changeset — `gates/clone/baseline.txt` (2026-05-30)

### obs:index — undoAllByOriginator/undoLastEffectByOriginator do not skip pendingOutcome:'rejected' entries; a reje
`kind: defect` `anchor: modules/ui-web/src/shell-v0/substrates/effects/index.ts` `seen: 2` `first: 2026-05-25` `last: 2026-05-26`
- [ ] undoAllByOriginator/undoLastEffectByOriginator do not skip pendingOutcome:'rejected' entries; a rejected (vetoed, never-dispatched) agent effect with a derivable inverse would be 'undone' by 'Undo all AI actions' — `modules/ui-web/src/shell-v0/substrates/effects/index.ts:494,514` (2026-05-25)
- [ ] navigate effects are an imperfect fit for the Effect-cursor undo/redo (543-fwd #1): surfaces append query params (?q=) producing secondary navigations + the router canonicalizes URLs, so cursor-redo of a navigate is unreliable live despite the re-journal suppression. Proper fix: route navigation undo/redo through NavigationJournal's own history, or exclude navigate from the Effect-cursor (it has its own history model). `modules/ui-web/src/shell-v0/substrates/effects/index.ts` (2026-05-26)

### obs:agentsessioncontroller — Agent Sessions list: FE `SessionListItem` reads `startedAtEpochMs`/`status` but backend `toSessionSu
`kind: defect?` `anchor: modules/ui-web/src/shell-v0/controllers/AgentSessionController.ts` `seen: 2` `first: 2026-06-03` `last: 2026-06-09`
- [ ] Agent Sessions list: FE `SessionListItem` reads `startedAtEpochMs`/`status` but backend `toSessionSummary` emits `startedAt`/`state` — the time+status meta renders empty (field-name mismatch) — `modules/ui-web/src/shell-v0/controllers/AgentSessionController.ts:85` / `AgentRunStore.java:424` (2026-06-03)
- [ ] Agent chat ALWAYS forced to core_ingest_files on turn 1 (every run, any prompt). Root cause: FE `AgentSessionController.BUILTIN_PROFILES` is a lone {agentId:'manager', tools:[]} sent as agentProfiles + initialAgentId='manager'; backend `AgentTurnPolicy.shouldForceToolCall` treats any non-null non-'primary' activeId as a sub-agent → E0a fires on turn 1 → `buildE0aTools` restricts tools to core.ingest-files + handoff, but there is no other agent to hand off to and 'manager' has no tools → only core_ingest_files is callable. Mismatch: E0a expects a manager+workers team; the default is a lone manager. Fix: default the single-window chat agent to single-agent (initialAgentId=null / agentProfiles=[]) or name it 'primary'. Pre-existing (not 565). — AgentSessionController.ts:162,854 / AgentTurnPolicy.java:29 / AgentStepRunner.java:181,663 (2026-06-09)

### obs:searchstate — Search surface meta-line displays "2ms" while the actual /api/knowledge/search round trip is ~1.2s w
`kind: defect?` `anchor: modules/ui-web/src/shell-v0/state/searchState.ts` `seen: 2` `first: 2026-06-12` `last: 2026-06-17`
- [ ] Search surface meta-line displays "2ms" while the actual /api/knowledge/search round trip is ~1.2s with AI online — which field feeds processingTimeMs needs tracing — `modules/ui-web/src/shell-v0/state/searchState.ts` (2026-06-12)
- [ ] FE search result mapping has no defensive handling for a raw `chunk:`-prefixed hit id: `path = fields.path ?? r.id` silently renders "chunk:uuid…" as the filename if chunk-merge is skipped (pure-dense result sets). Hard to diagnose if chunk-merge ever regresses. — `modules/ui-web/src/shell-v0/state/searchState.ts:492` (2026-06-17)

### obs:ui-check — ui-shot color_scheme="light" steps render the dark app theme (persisted theme wins over prefers-colo
`kind: defect` `anchor: scripts/jseval/jseval/ui_check.py` `seen: 2` `first: 2026-06-12` `last: 2026-06-19`
- [ ] ui-shot color_scheme="light" steps render the dark app theme (persisted theme wins over prefers-color-scheme) — light-theme shots don't validate light tokens visually — `scripts/jseval/jseval/ui_check.py` (2026-06-12)
- [ ] ui-shot/ui-check chat steps target the retired React inspector (search-input, inspector-pane, context-state pills) or a broken ?shell-demo bypass — none render the live shell-v0 UnifiedChatView, so the main chat surface has no visual-verification coverage — `scripts/jseval/jseval/ui_check.py` (2026-06-19)

### obs:utility-comparison — Pre-existing (unrelated to tempdoc 624 utility_comparison.py work): tests/test_agent_retrieval_eval.
`kind: environment?` `anchor: utility_comparison.py` `seen: 5` `first: 2026-07-02` `last: 2026-07-21`
- [ ] Pre-existing (unrelated to tempdoc 624 utility_comparison.py work): tests/test_agent_retrieval_eval.py::test_build_disallowed_tools_condition_{a,b,c}_* fail with 'Extra items in the left set: Skill' — a disallowed-tools set assertion out of sync with agent_retrieval_eval.py, in already-uncommitted worktree changes predating this session. (2026-07-02)
- [ ] utility_comparison._pair_observations only reads a_by_seed[seed][0]/c_by_seed[seed][0] — if a cell's cell_summaries ever contain >1 summary at the SAME (seed, arm) pair (e.g. a corpus-signature refresh landing at the same seed the _default_corpus_stratify docstring anticipates), all but the first summary's per_query is silently dropped rather than merged; the existing stratify test avoids this by using distinct seeds per signature — `scripts/jseval/jseval/utility_comparison.py:298-300` (2026-07-02)
- [ ] Shared-worktree race: Chain B (tempdoc 736) concurrently edited scripts/jseval/jseval/utility_claim_policy.py while Chain A's git-stash isolation probe was mid-flight, causing a stash-pop conflict (recovered via per-file git checkout from stash@{0}, no data lost, stash left in place as safety net). Separately, Chain B's utility_comparison.py currently adds compose_utility()'s top-level `seed_floor_met` and `denominators` keys UNCONDITIONALLY (no conditional-omission path), which changes semantic_digest for every pre-736 historical composed record — the same digest-perturbation class the pre-existing cell["identity"] comment at utility_comparison.py warns about. Chain A verified in isolation that its own tool_result_digests/four-state/cost-turns changes do NOT perturb the historical fixture's semantic_digest (unchanged at 2f555f661a9165fcb29a3f7d0ec10c70ca5ca28b8e4d47581361c430a464a100); this finding is Chain B's, out of Chain A's file scope (utility_comparison.py) to fix. — `scripts/jseval/jseval/utility_comparison.py` (D13/D15 unconditional dict additions) (2026-07-14)
- [ ] cross-corpus compose DROPS exposure identity that every per-run record carries (cohort.exposure_config.exposure_mode + mcp_initialize_identity end up null/empty in the combined record) → source_identity_complete claim gate fails on composed evidence that is actually complete per-run; carry the (verified-identical) per-run exposure/mcp-init blocks through compose — `scripts/jseval/jseval/utility_comparison.py:1324` area (compose_utility_cross_corpus cohort assembly) (2026-07-18)
- [ ] 757 reviewer MEDIUM: turns/duration delta_mean are NOT direction-gated for truncated with-tool cells (a censored c_turns→0.0 flatters the B arm) — pre-existing, diagnostic-only fields, not claim gates; gate them or label them censored when any usage_truncated cell contributes — `scripts/jseval/jseval/utility_comparison.py:977` + `:1046-1049` (2026-07-21)

### obs:corpus-generate — battlefield-en-v1's materialized corpus-dir contained 858 stale .txt files from an earlier, larger r
`kind: follow-up` `anchor: corpus_generate.py` `seen: 3` `first: 2026-07-02` `last: 2026-07-02`
- [ ] battlefield-en-v1's materialized corpus-dir contained 858 stale .txt files from an earlier, larger regeneration (files not in corpus.jsonl's 390 certified doc ids) — inflated condition-A's file-reading haystack 3.2x vs the certified corpus; removed before the 624 real utility-run since all query evidence_ids were confirmed covered by the 390 legitimate docs. Root cause likely in the golden-corpus materialization step not clearing corpus-dir between corpus_generate.py re-runs at different scale — worth a cleanup-on-materialize fix. — `datasets/golden/battlefield-en-v1/corpus-dir` (2026-07-02)
- [ ] Global `pip install -e` for `jseval` resolves to a separate stale checkout (`F:\JustSearch`, main branch, pre-tempdoc-664 code) rather than the active worktree; any subprocess spawned via `sys.executable -c ...` without an explicit cwd/PYTHONPATH pin can silently import that stale package instead of the worktree's own code — `corpus_generate.regenerate_and_diff` was fixed (this session) by pinning `cwd`, but other subprocess-spawning code in jseval may have the same latent exposure — `scripts/jseval/jseval/corpus_generate.py` (fixed), search for other bare `subprocess.run([sys.executable, ...])` call sites in `scripts/jseval/jseval/`. (2026-07-02)
- [ ] SECOND same-day hit of the stale-editable-install trap (624 twentieth pass): 'python -m jseval' from repo root resolved to the Jun-22 F:\JustSearch editable install, silently running a 3-week-old harness for the first two certified-run attempts (~$25-30 spend invalidated, ~44% phantom exclusions from old-code cells). Editable install re-pointed to F:\justsearch-public\scripts\jseval. A runtime assertion that the imported jseval matches the repo under test is now twice-proven-needed. — `scripts/jseval/jseval/corpus_generate.py:676` (first instance) (2026-07-02)

### obs:installed-plugins — `frontend-design@claude-plugins-official` plugin active at user scope surfaces a `frontend-design` s
`kind: lesson` `anchor: installed_plugins.json` `seen: 1` `first: 2026-04-28` `last: 2026-04-28`
- [ ] `frontend-design@claude-plugins-official` plugin active at user scope surfaces a `frontend-design` skill alongside the 14 project skills. Worth knowing when an agent is told "use the project skills" — the listing is broader than the project's own. — `~/.claude/plugins/installed_plugins.json` (2026-04-28)

### obs:userconfigstate — V1.5 dev-mode: Vite serves `.ts` and `.js`-extension URL imports as separate ES module instances. Re
`kind: defect?` `anchor: modules/ui-web/src/shell-v0/state/userConfigState.ts` `seen: 1`
- [ ] V1.5 dev-mode: Vite serves `.ts` and `.js`-extension URL imports as separate ES module instances. Real consumers in TS source (Shell.ts, ProvenanceBadge.ts, vitest tests) resolve uniformly to one instance. Direct-URL `.js` imports from JS context (browser console eval) produce a SECOND instance with separate singleton state. Symptom: state mutations don't propagate to subscribers. Production Rollup deduplicates this; dev-only quirk. — `modules/ui-web/src/shell-v0/state/userConfigState.ts` (2026-05-07; see 470 §B.C.2)

### obs:hooks — **Audit lesson — when probing for hooks, all four scopes must be checked**: `.claude/settings.json`
`kind: lesson?` `anchor: hooks.json` `seen: 1` `first: 2026-05-18` `last: 2026-05-18`
- [ ] **Audit lesson — when probing for hooks, all four scopes must be checked**: `.claude/settings.json` (shared project), `.claude/settings.local.json` (per-machine project, **checked into git for this repo**), `~/.claude/settings.json` (user-scope), and every enabled plugin's `hooks.json` under `~/.claude/plugins/cache/`. Also grep `scripts/` for hook script files independently of settings. Encoded as discipline so future audit subagents don't repeat the same blind spot — `.claude/settings.local.json` (2026-05-18)

### obs:remove-worktree — Second orphaned worktree dir `.claude/worktrees/597-chat-count` is on disk but unregistered (not in
`kind: defect?` `anchor: remove-worktree.cjs` `seen: 12` `first: 2026-06-21` `last: 2026-07-17`
- [ ] Second orphaned worktree dir `.claude/worktrees/597-chat-count` is on disk but unregistered (not in `git worktree list`) — same failed-removal class as 587; removable via `node scripts/dev/remove-worktree.cjs` with owner approval (618 §15) (2026-06-21)
- [ ] remove-worktree.cjs: two defects seen 2026-07-07 — (a) its record-merge step attributes the merge to whatever session id happens to sit in the invoking checkout's tmp/agent-telemetry/current-session-id (linked a neighbouring session, then 'link skipped' from a fresh worktree; the tearing-down session cannot pass its own id), and (b) the EPERM long-path delete fallback throws 'filename, directory name, or volume label syntax is incorrect' — the \\?\ fallback path construction is broken, so any held-handle worktree fails removal twice. (2026-07-07)
- [ ] remove-worktree.cjs record-merge misattribution RE-OBSERVED 2026-07-07 (681 teardown): linked session 20097c0b (neighbour's id in main checkout's current-session-id) to a local merge commit instead of the tearing-down session 06f94413 -> squash f604144; backfilled manually in session-merges.ndjson — `scripts/dev/remove-worktree.cjs` (2026-07-07)
- [ ] reportHolders (scripts/dev/remove-worktree.cjs, added by 684/#82) still self-matches: its Win32_Process CommandLine -like '*<base>*' filter (excluding only its own powershell $PID) STILL matches the removal script's OWN node process and bash wrapper, because the target worktree path is in THEIR argv (observed live 2026-07-07: 'PID 536: node.exe ... remove-worktree.cjs .claude/worktrees/obs-cleanup'). Cheap fix for a future dev-tooling batch: also exclude the removal process tree (e.g. CommandLine -notlike '*remove-worktree*' and the parent node/bash PIDs). Fundamental cwd-holder limit (Win32_Process has no cwd) remains separate/out-of-scope. — scripts/dev/remove-worktree.cjs:94-113 (2026-07-07)
- [ ] Process gap: no cleanup path for worktree-* branches on closed-but-unmerged PRs — delete_branch_on_merge only fires on actual merge; scripts/dev/remove-worktree.cjs:158-216 only deletes local branch/worktree, never touches origin (2026-07-07)
- [ ] scripts/dev/remove-worktree.cjs intermittently fails to delete a worktree directory with EPERM/'used by another process' even with no obviously-holding process (retry usually succeeds, but not always — hit a case this session requiring git worktree prune + manual rmdir fallback) — scripts/dev/remove-worktree.cjs (2026-07-08)
- [ ] remove-worktree.cjs cannot remove the CALLING session's own start-worktree — the session's MCP server processes (justsearch-dev server.mjs etc.) hold cwd inside it until session exit; remove-worktree correctly deletes other worktrees (624-step0-campaign removed fine post-684-fix) but self-removal needs a post-session step. Suggest: remove-worktree detect-and-say 'owned by live session <id>, rerun after it exits' instead of a bare EPERM-style failure — `scripts/dev/remove-worktree.cjs` (2026-07-10)
- [ ] `remove-worktree.cjs`'s record-merge step links the session to the MAIN CHECKOUT's HEAD, not to the branch's actual merge commit — so it silently attributes an unrelated commit whenever the main checkout isn't sitting on an updated `main`. Reproduced 2026-07-15: tearing down worktree-ui-audit-density-review (merged as add9d620) wrote `session 478caa0c -> 6b16b7c9`, i.e. ANOTHER agent's tempdoc-727 commit, because the main checkout was parked on branch `mcpb-packaging`. Corrupts the tempdoc-622 Layer-B outcome join at its keying step, and fails silently (it prints a confident success line naming the wrong subject). Backfilling with `record-merge.mjs <commit> --session-id <id>` appends the correct row but cannot retract the wrong one. Fix: resolve the merge commit from the branch being removed (e.g. its PR's mergeCommit / `origin/main` content match), not from `git log -1` in the main checkout — `scripts/dev/remove-worktree.cjs` (2026-07-15)
- [ ] remove-worktree.cjs auto record-merge links the session to main's LATEST merge commit, not the session's own — mis-attributed session 25f8ac5d to 6b16b7c9 (another session's 727 merge that landed minutes after PR #179's a8321f6); backfilled correctly + cleaned, but the auto-link races concurrent merges — `scripts/dev/remove-worktree.cjs` (2026-07-14)
- [ ] remove-worktree.cjs auto-record-merge linked the session to the main checkout's HEAD (4e783bdb, unrelated docs(726) commit) instead of the actual PR squash commit; explicit record-merge.mjs with the oid was needed to correct it — `scripts/dev/remove-worktree.cjs` (2026-07-14)
- [ ] remove-worktree.cjs holder-scan (727 F-2 taskkill) matches its OWN invoking process chain when the command line contains the worktree path — killed the invoking bash + its own node mid-run, leaving a half-deleted worktree (.git file gone, dir held). Needs self-PID/ancestor exclusion — `scripts/dev/remove-worktree.cjs` (2026-07-16)
- [ ] record-merge mis-link REPRODUCED a third time (sessions 25f8ac5d, c226227a logged it 2026-07-14): remove-worktree.cjs auto-linked session cfa87fbc to e608f75b (a fold commit made minutes after) instead of its own PR squash cef7a91e — three independent instances now; the fix is to capture the PR's mergeCommit oid at teardown rather than reading main's HEAD — `scripts/dev/remove-worktree.cjs` (2026-07-17)

### obs:healthsurface-flake — HealthSurface 'Recent events' renders NO ConditionStore conditions: `hs.events` is empty for ALL con
`kind: defect?` `anchor: HealthSurface.ts` `seen: 1` `first: 2026-06-22` `last: 2026-06-22`
- [ ] HealthSurface 'Recent events' renders NO ConditionStore conditions: `hs.events` is empty for ALL conditions (ai.not-ready, embedding.blocked, at-rest.unprotected, etc.) despite the /api/health/events/stream snapshot carrying them (a fresh same-origin fetch gets them fine). HealthSurface's persistent SSE subscription (`HealthSurface.ts:571-624`) isn't populating this.events — possibly dev-stack reconnect/stale-port flakiness. Affects all conditions equally; unrelated to 629. (2026-06-22)

### obs:default-index — Dev-stack: orphaned dev-runner/Worker processes accumulate across sessions and hold `index/default.i
`kind: defect?` `anchor: index/default.index.lock` `seen: 1` `first: 2026-06-22` `last: 2026-06-22`
- [ ] Dev-stack: orphaned dev-runner/Worker processes accumulate across sessions and hold `index/default.index.lock` + squat port 5173, crash-looping new Workers (`Index base path is already locked`) and tearing the stack down — symptom looks like a code boot failure but isn't. Recover: kill stray java/node dev PIDs + delete the stale lock; run `dev-runner.cjs start` as a BARE persistent background process (its children are in a KILL_ON_JOB_CLOSE Job Object, so a timeout/pipe wrapper kills the whole stack). Hit during 629 LAYER live-validation. (2026-06-22)

### obs:agent-utility-inspect — jseval utility-run (Inspect eval_set): re-invoking a FULLY-COMPLETED set errors 'log file not associ
`kind: defect?` `anchor: agent_utility_inspect.py` `seen: 4` `first: 2026-06-22` `last: 2026-07-17`
- [ ] jseval utility-run (Inspect eval_set): re-invoking a FULLY-COMPLETED set errors 'log file not associated with a task' — needs --log-dir-allow-dirty; partial-crash resume uses the now-pinned deterministic eval_set_id. tempdoc 624 run-governance validation — `scripts/jseval/jseval/agent_utility_inspect.py:run_utility_eval` (2026-06-22)
- [ ] furniture_markers all-False in the one live L1 capture cell despite extraction verified correct on both content shapes against live 0.3.1 responses (evidence_pack=True in-process) — child-agent-session content path discrepancy, unrecoverable from redacted logs; settle via one debug-instrumented cell — `scripts/jseval/jseval/agent_utility_inspect.py:548-621` (2026-07-14)
- [ ] Inspect eval_set post-hoc retry is structurally unavailable: completed evals with per-sample errors are terminal, and task identity embeds live-captured MCP surface/initialize fields so a fresh backend can never adopt an old log ('log not associated') — retry budget must be designed into the run — `scripts/jseval/jseval/agent_utility_inspect.py:1278` (2026-07-17)
- [ ] Tier-probe harness lesson: Claude CLI background haiku calls void every cell of a non-haiku campaign via the resolved-model cohort guard (39/40 sonnet cells, 'resolved provider model changed within one cell') — pin ANTHROPIC_DEFAULT_HAIKU_MODEL + CLAUDE_CODE_SUBAGENT_MODEL to the campaign tier via --agent-env for any non-haiku run; consider a harness-level default — `scripts/jseval/jseval/agent_utility_inspect.py` (2026-07-17)

### obs:knowledgeapi — Pre-existing: modules/app-api/.../KnowledgeApi.java is a 1-byte empty stub (no package/class) — like
`kind: environment?` `anchor: modules/app-api/src/main/java/io/justsearch/app/api/KnowledgeApi.java` `seen: 1` `first: 2026-06-23` `last: 2026-06-23`
- [ ] Pre-existing: modules/app-api/.../KnowledgeApi.java is a 1-byte empty stub (no package/class) — likely a leftover; harmless but odd — `modules/app-api/src/main/java/io/justsearch/app/api/KnowledgeApi.java` (2026-06-23)

### obs:healtheventstreamcontroller — Health 'Recent events' SSE (/api/health/events/stream) delivered NOTHING across two fresh dev stacks
`kind: environment?` `anchor: modules/ui/src/main/java/io/justsearch/ui/api/HealthEventStreamController.java` `seen: 1` `first: 2026-06-23` `last: 2026-06-23`
- [ ] Health 'Recent events' SSE (/api/health/events/stream) delivered NOTHING across two fresh dev stacks — eventCount 0 for ALL conditions incl. at-rest.unprotected, while FDE=NOT_ENCRYPTED + encryption not_configured (conditions ARE asserted by the taps on /api/status). Broadens the logged aiStateStore frozen-status finding: the whole event-delivery layer (SSE + status poll) is flaky/broken in current dev sessions. App-wide, pre-existing, out of 629. — `modules/ui/src/main/java/io/justsearch/ui/api/HealthEventStreamController.java` (2026-06-23)

### obs:aistatestore — HealthSurface this.status frozen post-mount — observed_at stuck for minutes while /api/status advanc
`kind: follow-up?` `anchor: modules/ui-web/src/shell-v0/state/aiStateStore.ts` `seen: 1` `first: 2026-06-23` `last: 2026-06-23`
- [ ] HealthSurface this.status frozen post-mount — observed_at stuck for minutes while /api/status advances; subscribeAiState callback (HealthSurface.ts:502) sets this.status unconditionally so the shared aiStateStore poll isn't propagating. App-wide (every status field), surfaced during a reconnecting dev stack — investigate whether the statusPoll/aiStateStore stalls after a connection disruption. — `modules/ui-web/src/shell-v0/state/aiStateStore.ts` (2026-06-23)

### obs:corpus-generate-general — 635 suite: generated corpus sources (4x ~450 long docs) committed under scripts/jseval/635-corpora/
`kind: defect?` `anchor: corpus_generate.py` `seen: 4` `first: 2026-06-23` `last: 2026-07-08`
- [ ] 635 suite: generated corpus sources (4x ~450 long docs) committed under scripts/jseval/635-corpora/ — regenerable from corpus_generate.py + meta.json seed/params; a leaner pattern would commit only generator+manifest and regenerate at corpus-build time (2026-06-23)
- [ ] battlefield-de-v1 (and the generator's lang=de path generally): the 'German' corpus's FILLER paragraphs are untranslated English — only the linking sentences are German (measured: both corpora share the same English filler; A-arm analysis 03, corpus analysis 05). Load-bearing for any future cross-lingual battlefield claim: a corpus labeled German that is ~90% English text cannot back a cross-lingual retrieval claim; the generator needs true target-language filler before the cross-lingual member (624 §M.2 successor) is built. — `scripts/jseval/jseval/corpus_generate.py` (2026-07-03)
- [ ] Pre-existing intra-pair token overlap in _SEM_PLACE: ("eastern ridge", "ridge to the east") shares token "ridge", violating the pool's own zero-shared-token synonym invariant — `scripts/jseval/jseval/corpus_generate.py:81` (2026-07-08)
- [ ] Pre-existing intra-pair token overlap across 6 original _SEM_PLACE/_SEM_PLACE_DE entries (violates the pool's own zero-shared-surface-token synonym invariant): EN (eastern ridge/ridge to the east; river bend/curve of the river; Carpathian highlands/Carpathian uplands; hill city/city on the slopes; southern hills/hills to the south) and DE (suedliche Huegel/Huegel im Sueden) — all in the original 26-entry pool, none in the tempdoc-624 append — `scripts/jseval/jseval/corpus_generate.py:81-86,143` (2026-07-08)

### obs:agenthistoryindexer — Restored agent runs are viewable but not searchable: AgentHistoryIndexer is purely live-listener-fed
`kind: defect?` `anchor: modules/app-services/src/main/java/io/justsearch/app/services/agenthistory/AgentHistoryIndexer.java` `seen: 2` `first: 2026-06-23` `last: 2026-07-07`
- [ ] Restored agent runs are viewable but not searchable: AgentHistoryIndexer is purely live-listener-fed (no rebuild/backfill path), and faithful backup-import doesn't fire listeners — so a restored run's transcript never enters the agent-history collection. DERIVED-projection rebuild gap (585's domain); fix = re-index restored runs at import OR add an AgentHistoryIndexer backfill-from-ledger — `modules/app-services/src/main/java/io/justsearch/app/services/agenthistory/AgentHistoryIndexer.java` (2026-06-23)
- [ ] CONFIRMED trust bug (687-R2 Q6): dataDir runtime artifact modules/ui-web/.dev-data/agent-history/<uuid>.md was ingested UNTAGGED into the user corpus (ranked #2 for 'getting started'; doc count 5->6) — the 585-D4b reserved-collection exclusion only guards TAGGED docs, and the generic file watcher ingested the same file untagged, bypassing it. Structural fix: worker refuses to ingest anything under its own dataDir (prefix guard in the scanner/watcher) — `modules/app-services/src/main/java/io/justsearch/app/services/agenthistory/AgentHistoryIndexer.java` (2026-07-07)

### obs:go-public-readiness — go-public-readiness.md:202 publish include-list still lists `third_party/llama.cpp/` (MIT, vendored)
`kind: defect?` `anchor: docs/business/legal/go-public-readiness.md` `seen: 1` `first: 2026-06-23` `last: 2026-06-23`
- [ ] go-public-readiness.md:202 publish include-list still lists `third_party/llama.cpp/` (MIT, vendored) — that tree was removed in tempdoc 632; drop it from the include-list (llama.cpp now ships as the pinned upstream prebuilt binary, nothing to publish from source) — `docs/business/legal/go-public-readiness.md:202` (2026-06-23)

### obs:cli — `jseval run --start-backend` cannot be port/data-dir isolated: `_run_iteration` calls `backend.start
`kind: defect?` `anchor: scripts/jseval/jseval/cli.py` `seen: 1` `first: 2026-06-24` `last: 2026-06-24`
- [ ] `jseval run --start-backend` cannot be port/data-dir isolated: `_run_iteration` calls `backend.start_backend()` without threading `port`/`data_dir`, so `--base-url <port>`/config `api_port` reach only the subprocess while the Python-side --clean+health-check stay on 33221 → silent collision with any concurrent jseval backend (quick_health is blind to these). Fix: thread port/data_dir + fail-fast on a live 33221 — `scripts/jseval/jseval/cli.py:267`, `backend.py:64` (2026-06-24)

### obs:05-ai-architecture — Stale chat-model name in canonical docs: `docs/explanation/05-ai-architecture.md` (e.g. lines 15,167
`kind: defect?` `anchor: docs/explanation/05-ai-architecture.md` `seen: 2` `first: 2026-06-25` `last: 2026-07-08`
- [ ] Stale chat-model name in canonical docs: `docs/explanation/05-ai-architecture.md` (e.g. lines 15,167,226,457) + `06-configuration-ssot.md:82` name the retired `Qwen3VL-8B-Thinking` as the current/default generative LLM. Actual default is `Qwen3.5-9B` (only model on disk; `model-inventory.md:177` + `legal/ai-runtime-and-model-redistribution.md:79` already correct; no `Qwen3VL` anywhere in `modules/*/src/main`). 579-class canonical-vs-code drift, 2nd instance of the stale-technical-claim class (tempdoc 650) — reconcile 05/06 with a careful pass, not a blind find-replace (the reasoning/Thinking discussion may be model-specific). (2026-06-25)
- [ ] 05-ai-architecture.md 'Frontend rendering' section still describes the retired React `useAppAI.ts` hook and the old `meta` citation event; needs a frontend-stack (Lit/shell-v0) refresh beyond the engine-citation event rename — `docs/explanation/05-ai-architecture.md:390` (2026-07-08)

### obs:llm-bench — llm-bench discover_doc_ids uses `*:*` which returns 0 in semantic-search dev stacks (real queries wo
`kind: defect?` `anchor: scripts/jseval/jseval/llm_bench.py` `seen: 1` `first: 2026-06-24` `last: 2026-06-24`
- [ ] llm-bench discover_doc_ids uses `*:*` which returns 0 in semantic-search dev stacks (real queries work) — the bench can't auto-discover docs there, so token/latency benching needs an index that serves `*:*` or an explicit docId — `scripts/jseval/jseval/llm_bench.py` (2026-06-24)

### obs:searchsurface — Pre-existing a11y: the SearchSurface degraded-readiness banner reports an axe serious violation (rea
`kind: environment?` `anchor: modules/ui-web/src/shell-v0/views/SearchSurface.ts` `seen: 1` `first: 2026-06-30` `last: 2026-06-30`
- [ ] Pre-existing a11y: the SearchSurface degraded-readiness banner reports an axe serious violation (readinessNotice render / 'Open Health' control), surfaced only when the banner shows; not introduced by 661 DP3 (no new DOM). Worth a focused a11y check of the degradation banner — `modules/ui-web/src/shell-v0/views/SearchSurface.ts`. Rescued from a near-lost obs shard during the 2026-06-30 main-checkout reconcile. (2026-06-30)

### obs:gitleaks — gitleaks.toml allowlists `third_party/.*` as 'vendored upstream (llama.cpp etc.)' — that tree was re
`kind: defect?` `anchor: docs/business/go-to-market/cutover-package/gitleaks.toml` `seen: 1` `first: 2026-06-24` `last: 2026-06-24`
- [ ] gitleaks.toml allowlists `third_party/.*` as 'vendored upstream (llama.cpp etc.)' — that tree was removed in tempdoc 632, so the allowlist rule is now inert; drop it during the 634 cutover gitleaks pass — `docs/business/go-to-market/cutover-package/gitleaks.toml:11` (2026-06-24)

### obs:16-gpu-booster-pack — Canonical drift: `docs/explanation/16-gpu-booster-pack.md` presents the GPU Booster Pack as the curr
`kind: environment?` `anchor: docs/explanation/16-gpu-booster-pack.md` `seen: 1` `first: 2026-06-24` `last: 2026-06-24`
- [ ] Canonical drift: `docs/explanation/16-gpu-booster-pack.md` presents the GPU Booster Pack as the current GPU-runtime delivery mechanism, but tempdoc 632 recorded the founder correction that the booster pack is LEGACY and the live mechanism is the AI-brain install (AiInstallService downloading the model-registry cuda-runtime package). Doc needs a reframe (pre-existing drift, surfaced by 632's NVIDIA accept-and-document work) — `docs/explanation/16-gpu-booster-pack.md` (2026-06-24)

### obs:search-quality-register — Doc/code drift: search-quality register D-004 still says leg-arbitration 'SHIPPED (default off)' / '
`kind: defect?` `anchor: docs/reference/search-quality-register.md` `seen: 4` `first: 2026-06-24` `last: 2026-07-12`
- [ ] Doc/code drift: search-quality register D-004 still says leg-arbitration 'SHIPPED (default off)' / 'default-on not recommended' (`docs/reference/search-quality-register.md:585-605`), but shipped code has BOTH leg-arbitration + recall-complete default TRUE (`ResolvedConfigBuilder.java:1497,1513`) per tempdoc 636 final decision; F-024 + a recall-complete D-row also need reconciling. (2026-06-24, tempdoc 636 take-over) (2026-06-24)
- [ ] search-quality-register.md has TWO entries numbered F-030 (tempdoc 678 encoder-domain-mismatch, ~line 595, and tempdoc 706 OCR comparability, ~line 579) — pre-existing numbering collision found during 691 Phase-L takeover; register owner should renumber one — `docs/reference/search-quality-register.md` (2026-07-10)
- [ ] Residual hybrid-vs-lexical gap on legal-clerc post-F-032 (hybrid 0.5592/0.5609 vs lexical 0.6891 at b88e76e) is fusion territory — out of 708 scope, needs its own owner — `docs/reference/search-quality-register.md` (2026-07-11)
- [ ] search-quality-register.md has a DUPLICATE finding ID F-030 — used for BOTH the 706 scanned-PDF-OCR-engine finding AND the 678 encoder-domain-mismatch finding. Register hygiene: distinct findings need distinct IDs (renumber one, e.g. the 706 OCR one). Found during 705 re-investigation; register is 678/708's domain — `docs/reference/search-quality-register.md` (2026-07-12)

### obs:resourceapimodule — ResourceApiModule.shutdown() never calls intentStreamController::shutdown — its heartbeat scheduler
`kind: environment?` `anchor: modules/ui/src/main/java/io/justsearch/ui/api/ResourceApiModule.java` `seen: 2` `first: 2026-06-30` `last: 2026-07-14`
- [ ] ResourceApiModule.shutdown() never calls intentStreamController::shutdown — its heartbeat scheduler thread leaks on module shutdown (pre-existing, found while wiring tempdoc 662's ShellEventsStreamController shutdown) — `modules/ui/src/main/java/io/justsearch/ui/api/ResourceApiModule.java:472-494` (2026-06-30)
- [ ] HIGH (found by follow-up audit of the 0.2.0 round, NOT by the round itself): enabling chat encryption permanently breaks GET /api/thread/{id} — the unified chat surface's own endpoint. ResourceApiModule.java:310-313 constructs a SECOND FileConversationStore via the single-arg ctor, which is StoreCipher.disabled() (FileConversationStore.java:49-51) — a permanently-disabled cipher, unrelated to the primary store's live one (ConversationApiAssembly.java:204-209). StoreCipher.open throws KeyLockedException on any JSEv1: line when !key.enabled(), and loadOwnMessages (FileConversationStore.java:121) only catches IOException, so it propagates -> 500 -> unifiedThreadClient.ts:210 swallows !res.ok and renders an EMPTY thread with no banner. ALWAYS broken once any message was written post-encryption-setup — not just while locked (the disabled cipher never consults the live key). Contrast listSessions:216-227 which DOES catch KeyLockedException and degrades gracefully. No test covers the single-arg ctor against a sealed store. Fix surface: ResourceApiModule.java:312-313 (inject the live cipher) + a KeyLocked-aware read path. (2026-07-14)

### obs:resourceview — Tempdoc 662: after migrating startIndexingJobsBridge onto the shared MultiplexedStream, an open core
`kind: defect?` `anchor: modules/ui-web/src/shell-v0/components/ResourceView.ts` `seen: 1` `first: 2026-06-30` `last: 2026-06-30`
- [ ] Tempdoc 662: after migrating startIndexingJobsBridge onto the shared MultiplexedStream, an open core.indexing-jobs Resource view (ResourceView.ts, generic subscribePooled by URL) no longer shares a socket with the always-on bridge — it opens its own lazy socket instead of pooling with the bridge as before. Minor, documented tradeoff (well under the 6-connection budget); a future pass could teach ResourceView's generic SSE_STREAM mechanism to also check the shell-events multiplexer for any of the 5 multiplexed streamIds — `modules/ui-web/src/shell-v0/components/ResourceView.ts` + `modules/ui-web/src/shell-v0/substrates/tasks/indexingJobsBridge.ts:330-385` (2026-06-30)

### obs:branch-safety — branch-safety.md claims '.claude/settings.local.json is tracked' but it is gitignored (seeded from s
`kind: defect?` `anchor: .claude/rules/branch-safety.md` `seen: 2` `first: 2026-07-01` `last: 2026-07-07`
- [ ] branch-safety.md claims '.claude/settings.local.json is tracked' but it is gitignored (seeded from settings.local.json.example) — doc drift — `.claude/rules/branch-safety.md:20` (2026-07-01)
- [ ] Pre-existing markdownlint MD031/MD040 violations (7x, fenced code blocks) in .claude/rules/branch-safety.md — `.claude/rules/branch-safety.md:26-67`, predates the session-695-retro-followup docs work (2026-07-07)

### obs:test-pipeline — test-pipeline.mjs fails at line 361 (JSON.parse of empty intervene output for realLargeFile large-fi
`kind: environment?` `anchor: scripts/agent-analytics/test-pipeline.mjs` `seen: 2` `first: 2026-07-01` `last: 2026-07-06`
- [ ] test-pipeline.mjs fails at line 361 (JSON.parse of empty intervene output for realLargeFile large-file test) on origin/main too — pre-existing/environmental, not from tempdoc 618 — `scripts/agent-analytics/test-pipeline.mjs:361` (2026-07-01)
- [ ] test-pipeline.mjs has multiple stale/pre-existing failures on this machine (1f expects no additionalContext but intervene.mjs emits the auto-limit note for any >8KB file; 3a/3b hardcode D:\code\JustSearch; 10/11 expect retired guidance text incl. BrainView.tsx) — pre-dates 683; capture-evidence-bundle.mjs restoration fixed 1b/1c — `scripts/agent-analytics/test-pipeline.mjs:354` (2026-07-06)

### obs:corpus — jseval corpus-fidelity's default --modes bm25_splade structurally cannot pass semantic=True self-dem
`kind: follow-up?` `anchor: corpus.py` `seen: 1` `first: 2026-07-01` `last: 2026-07-01`
- [ ] jseval corpus-fidelity's default --modes bm25_splade structurally cannot pass semantic=True self-demo corpora (no dense leg, and these corpora are specifically designed so lexical/SPLADE-only retrieval fails at the entry point) -- all 5 635-corpora/* corpora scored 0.017-0.214 nDCG (FAIL, too-hard) under the default mode but 0.53-0.84 (PASS, in-band) under --modes hybrid. Consider whether corpus-fidelity's default mode should be hybrid for semantic=True corpora, or whether the CLI should warn when certifying a semantic corpus under a dense-less mode -- scripts/jseval/jseval/commands/corpus.py cmd_corpus_fidelity default --modes (2026-07-01)

### obs:hybridsearchops — Stale code comments say recall-complete pool is 'default off' but resolved default is true — `Hybrid
`kind: defect?` `anchor: HybridSearchOps.java` `seen: 3` `first: 2026-06-30` `last: 2026-07-08`
- [ ] Stale code comments say recall-complete pool is 'default off' but resolved default is true — `HybridSearchOps.java:477`, `SearchExecutor.java:758`, `EnvRegistry.java:972`; also CE javadoc still names 'MiniLM-L6-v2' (model is gte-multilingual-reranker-base) at `RerankerConfig.java:59`, `KnowledgeSearchEngine.java:158-161`. Found during tempdoc 643 investigation. (2026-06-30)
- [ ] Low-signal fusion fallback constants drift from documented config defaults: HybridSearchOps.java:45-46 hardcodes DEFAULT_VECTOR_ONLY_CAP_LOW_SIGNAL=10 / DEFAULT_VECTOR_RRF_WEIGHT_LOW_SIGNAL=0.3 while claiming to match ResolvedConfig defaults, but ResolvedConfigBuilder.java:1480-1481 defaults are 3 / 0.25 — the no-config fallback path silently uses different fusion parameters than the documented defaults. Found during read-only constants-provenance sweep 2026-07-06. (2026-07-06)
- [ ] Engine robustness (for 636/643): fusion CAN bury a RETRIEVED gold below the returned top-10 when one leg is degraded (CASCADE_LEAK); the recall-complete splice is not wired into the shipped 3-way CC path and SPLADE is unprotected (`HybridSearchOps.java:477-490`, `SearchExecutor.java runThreeWay`). Latent, non-biting on healthy realistic corpora (MIRACL CASCADE_LEAK≈0.03) — measured in tempdoc 701 E3 (2026-07-08)

### obs:vieweraudiencestate — viewerAudience: localStorage edits don't propagate to the in-memory store cache. A direct `localStor
`kind: defect?` `anchor: modules/ui-web/src/shell-v0/state/viewerAudienceState.ts` `seen: 1` `first: 2026-05-18` `last: 2026-05-18`
- [ ] viewerAudience: localStorage edits don't propagate to the in-memory store cache. A direct `localStorage.setItem('justsearch.userState.v1', ...)` doesn't refresh `getViewerAudience()`'s return value (the store keeps its initialization-time cache). Use `setViewerAudience()` (or the SettingsSurface UI radio buttons) to flip tiers in dev probes and tests. Cost me ~10 min during 511-followup-D live-verification before the symptom resolved. — `modules/ui-web/src/shell-v0/state/viewerAudienceState.ts`, `modules/ui-web/src/shell-v0/state/UserStateDocument.ts` (2026-05-18)

### obs:jfhealthevent — 526 §17 review note 1 — JfHealthEvent.handleConditionClick skip-on-button is overly broad; if a card
`kind: defect?` `anchor: modules/ui-web/src/shell-v0/aggregate-substrate/components/JfHealthEvent.ts` `seen: 1`
- [ ] 526 §17 review note 1 — JfHealthEvent.handleConditionClick skip-on-button is overly broad; if a card later grows a non-recovery button, that click will also suppress selection. Use `data-recovery-op` attribute or a more specific selector. — `modules/ui-web/src/shell-v0/aggregate-substrate/components/JfHealthEvent.ts` (2026-05-21) — NOTE (403 Round 5): investigated, NOT changed — the recovery buttons aren't in `JfHealthEvent` or its `healthEventActivityRow` strategy (the host listener catches bubbled clicks), and it's unclear what `closest('button')` matches given the shell uses `jf-button`/`jf-control` custom elements (which `closest('button')` would NOT match). A blind selector swap risks a regression. Correct fix needs the actual click-target inventory first; current broad skip is safe, the defect is latent (no non-recovery button exists yet).

### obs:citationspanel — 526 §17 review note 3 — T1A citation anchor publish has no regression test; if `event.currentTarget`
`kind: defect?` `anchor: modules/ui-web/src/shell-v0/components/chat/CitationsPanel.ts` `seen: 1` `first: 2026-05-21` `last: 2026-05-21`
- [ ] 526 §17 review note 3 — T1A citation anchor publish has no regression test; if `event.currentTarget` rect extraction regresses, only manual browser testing would catch it. — `modules/ui-web/src/shell-v0/components/chat/CitationsPanel.ts:222` (2026-05-21)

### obs:dev-runner — Dev-runner is bound to main repo path (`F:/JustSearch`) and cannot live-verify Java backend changes
`kind: defect?` `anchor: scripts/dev/dev-runner.cjs` `seen: 2` `first: 2026-07-14` `last: 2026-07-14`
- [ ] Dev-runner is bound to main repo path (`F:/JustSearch`) and cannot live-verify Java backend changes made in worktrees. Worsened by main's gradle currently failing with a snakeyaml lockfile issue (`Resolved 'org.snakeyaml:snakeyaml-engine:3.0.1' which is not part of the dependency lock state`). Net effect: tempdoc 530 §4.2 `/api/governance/state` endpoint compiled cleanly in the worktree (class present in worktree's installed jar; route registered in source) but could not be live-HTTP-verified due to this contradiction. Resolution path: fix main's lockfile, or extend dev-runner to honor worktree CWD. — `scripts/dev/dev-runner.cjs` + `F:/JustSearch` main lockfile (2026-05-21, tempdoc 530 Pass-7 Phase D2)
- [ ] justsearch_dev_stop broke after its origin worktree was torn down: the MCP dev server resolves scripts/dev/dev-runner.cjs from the session-inject-time worktree path (removed adoption-legibility) -> MODULE_NOT_FOUND; stack had to be killed by PID tree. dev-runner stop path should resolve from the run record or repoRoot, not the session cwd at inject time. Hit 2026-07-14 during 725 A/B teardown; no stop-report written for run b1784f21. (2026-07-14)

### obs:logger — Governance `ts-any` gate: silent-growth across ~16 files untouched by 549 (logger.ts, platform.ts, t
`kind: environment?` `anchor: logger.ts` `seen: 1` `first: 2026-05-26` `last: 2026-05-26`
- [ ] Governance `ts-any` gate: silent-growth across ~16 files untouched by 549 (logger.ts, platform.ts, tauriRuntime.ts, WalkthroughCard.ts, HoverPreviewHost.ts, dev-fixtures.ts, stateValidator.ts, etc.) — pre-existing baseline drift (ungated under manual-only CI); needs a ts-any baseline rebalance or per-file changesets — `modules/ui-web/src` (2026-05-26)

### obs:actionledgerview — 550 thesis II NIT (independent review 2026-05-27): `ActionLedgerView` went stream-only and dropped t
`kind: defect?` `anchor: modules/ui-web/src/shell-v0/components/ActionLedgerView.ts` `seen: 1`
- [ ] 550 thesis II NIT (independent review 2026-05-27): `ActionLedgerView` went stream-only and dropped the error banner; a permanently-unreachable backend renders "No activity yet." indistinguishable from a genuinely empty ledger (no onError/connection-state signal on `openActionLedgerStream`). — `modules/ui-web/src/shell-v0/components/ActionLedgerView.ts`

### obs:isolatedbackendfixture — LESSON (static-green != live-working, 2026-05-27): merging 138 commits of `main` into a long-lived b
`kind: lesson?` `anchor: modules/system-tests/.../harness/IsolatedBackendFixture.java` `seen: 1` `first: 2026-05-27` `last: 2026-05-27`
- [ ] LESSON (static-green != live-working, 2026-05-27): merging 138 commits of `main` into a long-lived branch (worktree-550-impl) compiled green and passed all unit + FE tests, but ALL 3 live E2E suites failed — `IsolatedBackendFixture`'s readiness probe string-matched `"worker":{"state":"READY"`, which tempdoc 548's lifecycle-enum collapse had silently changed to the proto-prefixed `"LIFECYCLE_STATE_READY"` on the wire. The worker booted fine (worker.log: models loaded + indexing, no errors) — ready-but-undetectable. Only the live tier caught it. Takeaway: a string-matching test fixture against a wire/serialization shape is brittle across a serialization change landed on another branch; after a big merge, re-run the LIVE tier, not just compile+unit. Probe now accepts both forms — `modules/system-tests/.../harness/IsolatedBackendFixture.java:296` (2026-05-27)

### obs:libraryview — ui-ux.md 'Key Files' + UIX-013/UIX-014 reference the retired React stack (`components/views/LibraryV
`kind: defect?` `anchor: components/views/LibraryView.tsx` `seen: 1` `first: 2026-05-30` `last: 2026-05-30`
- [ ] ui-ux.md 'Key Files' + UIX-013/UIX-014 reference the retired React stack (`components/views/LibraryView.tsx`, `stores/`, `hooks/`); likely stale after the Lit shell-v0 rewrite — `docs/reference/issues/ui-ux.md:13` (2026-05-30)

### obs:tokens — `gen-token-names --check` is stale on main: `--surface-content-max-width` (added to styles/tokens.cs
`kind: defect?` `anchor: tokens.css` `seen: 1` `first: 2026-06-04` `last: 2026-06-04`
- [ ] `gen-token-names --check` is stale on main: `--surface-content-max-width` (added to styles/tokens.css by 559 commit 77d32f5f2) is missing from themes/token-names.generated.ts — run `node scripts/ci/gen-token-names.mjs` (2026-06-04)

### obs:selectioncontextinjector — SelectionContextInjector.java uses a raw `"\n\n---\n\n"` separator literal instead of a canonical co
`kind: environment?` `anchor: modules/app-services/src/main/java/io/justsearch/app/services/conversation/spi/SelectionContextInjector.java` `seen: 1` `first: 2026-06-03` `last: 2026-06-03`
- [ ] SelectionContextInjector.java uses a raw `"\n\n---\n\n"` separator literal instead of a canonical constant (SeparatorConstantDrift test was red on main; allowlisted as the sanctioned escape during tempdoc 554 impl). Structural fix: hoist a shared SECTION_SEPARATOR constant to a module app-services can reach. — `modules/app-services/src/main/java/io/justsearch/app/services/conversation/spi/SelectionContextInjector.java:285` (2026-06-03)

### obs:dev-server — **First-plugin onboarding broken: the scaffold `dev-server.js` won't run.** `modules/ui-web/dev-exam
`kind: lesson?` `anchor: modules/ui-web/dev-examples/plugin-scaffold/dev-server.js` `seen: 1` `first: 2026-06-04` `last: 2026-06-04`
- [ ] **First-plugin onboarding broken: the scaffold `dev-server.js` won't run.** `modules/ui-web/dev-examples/plugin-scaffold/dev-server.js` uses CommonJS `require`, but `modules/ui-web/package.json` is `"type": "module"`, so `node dev-server.js` throws `require is not defined in ES module scope`. This is the *documented first step* of 533's "Browser dev mode" first-plugin flow (README: "Run `node dev-server.js`"), so the canonical onboarding path is dead. Fix: rename to `dev-server.cjs` (and update the README) or rewrite with ESM imports. Workaround used in the 560 §20 de-risk: serve `plugin.js` same-origin via the app's Vite (`http://localhost:5174/dev-examples/plugin-scaffold/plugin.js`) — but note Vite *transforms* the module, which mangled the manifest id to `'unknown'` (so the plugin loaded + attenuated correctly but its surface didn't mount); a faithful load needs the raw source. — `modules/ui-web/dev-examples/plugin-scaffold/dev-server.js` (2026-06-04)

### obs:index-general — Packaged Tauri CSP likely blocks the `index.html` Google Fonts `<link href="https://fonts.googleapis
`kind: defect?` `anchor: modules/ui-web/index.html` `seen: 1` `first: 2026-06-05` `last: 2026-06-05`
- [ ] Packaged Tauri CSP likely blocks the `index.html` Google Fonts `<link href="https://fonts.googleapis.com…">` (Plus Jakarta Sans display font): CSP `style-src 'self' 'unsafe-inline'` (no googleapis) + `font-src 'self' data:` (no gstatic) — works in vite dev, silently drops the display font in packaged builds — `modules/ui-web/index.html:26` vs `modules/shell/src-tauri/tauri.conf.json:70` (2026-06-05)

### obs:coreplugin — Surface audience drift: `core.health-surface` + `core.activity-surface` are USER in Java CoreSurface
`kind: defect?` `anchor: modules/ui-web/src/shell-v0/plugin-api/CorePlugin.ts` `seen: 1` `first: 2026-06-09` `last: 2026-06-09`
- [ ] Surface audience drift: `core.health-surface` + `core.activity-surface` are USER in Java CoreSurfaceCatalog but OPERATOR in FE CorePlugin.ts — two-authority drift (tempdoc 571 CI-2) — `modules/ui-web/src/shell-v0/plugin-api/CorePlugin.ts:109,151` (2026-06-09)

### obs:coreplugin-missing — FE-only surfaces: `core.memory-surface` + `core.command-palette` exist in FE CorePlugin.ts but are a
`kind: defect?` `anchor: modules/ui-web/src/shell-v0/plugin-api/CorePlugin.ts` `seen: 1` `first: 2026-06-09` `last: 2026-06-09`
- [ ] FE-only surfaces: `core.memory-surface` + `core.command-palette` exist in FE CorePlugin.ts but are absent from the Java CoreSurfaceCatalog served by /api/registry/surfaces (tempdoc 571 CI-2) — `modules/ui-web/src/shell-v0/plugin-api/CorePlugin.ts:89,137` (2026-06-09)

### obs:runcontrolintent — §30 "stop a run" wiring gap — VERIFY whether stopping an agent run actually halts the BACKEND loop o
`kind: follow-up?` `anchor: modules/ui-web/src/shell-v0/controllers/runControlIntent.ts` `seen: 1` `first: 2026-06-11` `last: 2026-06-11`
- [ ] §30 "stop a run" wiring gap — VERIFY whether stopping an agent run actually halts the BACKEND loop or leaks tokens/compute. Verified facts: (a) the only full cancel, `cancelSession()`, does `this.abortController?.abort()` (the *controller's* stream) **+ `DELETE /api/chat/sessions/{id}`** (the real backend cancel) — `AgentSessionController.ts:1126`; (b) it is reachable ONLY through the `halt` RunDirective, which has ZERO live dispatchers (only a doc-comment mentions `dispatchRunControl({kind:'halt'})`); (c) the actual live stop affordance is the composer's `@composer-cancel → this.abortController?.abort()` — but `this` is the VIEW (`UnifiedChatView.ts:1723`), whose abortController is a DIFFERENT one than the agent controller's, and it sends NO backend DELETE. So clicking stop during an agent run may abort the wrong (idle) stream and leave the backend `AgentLoopService` iterating. Open questions for a live pass: does the SSE-disconnect from `abort()` make the backend stop on its own, or does the loop keep running? Should a real "stop the agent" control be wired to `dispatchRunControl({kind:'halt'})`? (The §30 comments themselves are now ACCURATE — `5914193e5` fixed the earlier halt-vs-abort confusion; this is the substantive residual.) — `modules/ui-web/src/shell-v0/controllers/runControlIntent.ts`, `AgentSessionController.ts:1126`, `UnifiedChatView.ts:1723` (2026-06-11)

### obs:retrospectivepanel — RetrospectivePanel Inbox per-run cards show the raw underscored LifecycleState text (e.g. `READY_FOR
`kind: defect?` `anchor: modules/ui-web/src/shell-v0/components/RetrospectivePanel.ts` `seen: 1` `first: 2026-06-11` `last: 2026-06-11`
- [ ] RetrospectivePanel Inbox per-run cards show the raw underscored LifecycleState text (e.g. `READY_FOR_LLM`) as the card-badge label — the §33 Fix D humanization covers the group HEADER only. The atom-fork half is CLOSED by the 574-merge (the per-run badge now composes `jf-status-badge`, tone-projected); only the raw-enum *text* in the slot remains (consistent with the Sessions tab) — `modules/ui-web/src/shell-v0/components/RetrospectivePanel.ts` (2026-06-11)

### obs:knowledgesearchengine — Search result count is nondeterministic across runs of the same query: LLM query expansion success-v
`kind: defect?` `anchor: modules/app-services/.../KnowledgeSearchEngine.java` `seen: 1` `first: 2026-06-12` `last: 2026-06-12`
- [ ] Search result count is nondeterministic across runs of the same query: LLM query expansion success-vs-timeout changes totalHits (~12 vs 31 observed); backend determinism/timeout policy question for the search-quality domain — `modules/app-services/.../KnowledgeSearchEngine.java` (expansion eligibility ~line 337) (2026-06-12)

### obs:searchplanner — Live worker returns chunk-merge skipped(SKIPPED_QUERY_SYNTAX) even for simple/absent querySyntax, co
`kind: defect?` `anchor: modules/worker-services/.../plan/SearchPlanner.java` `seen: 1` `first: 2026-06-12` `last: 2026-06-12`
- [ ] Live worker returns chunk-merge skipped(SKIPPED_QUERY_SYNTAX) even for simple/absent querySyntax, contradicting SearchPlanner.planChunkMerge on main which only skips for LUCENE — suspect stale worker dist on the shared dev stack; re-verify after fresh installDist — `modules/worker-services/.../plan/SearchPlanner.java:252` (2026-06-12)

### obs:knowledgesearchcontroller-general — Search wire matchSpans entries carry empty `term` strings — `modules/ui/.../KnowledgeSearchControlle
`kind: defect?` `anchor: modules/ui/.../KnowledgeSearchController.java` `seen: 1` `first: 2026-06-12` `last: 2026-06-12`
- [ ] Search wire matchSpans entries carry empty `term` strings — `modules/ui/.../KnowledgeSearchController.java` (response mapping) (2026-06-12)

### obs:settings-v2-live — Build-hygiene: `./gradlew build` (re)normalizes line endings (CRLF→LF) on `SSOT/catalogs/synonyms.{d
`kind: environment?` `anchor: ui-web/src/api/__fixtures__/settings-v2-live.json` `seen: 2` `first: 2026-06-13` `last: 2026-07-06`
- [ ] Build-hygiene: `./gradlew build` (re)normalizes line endings (CRLF→LF) on `SSOT/catalogs/synonyms.{de,en}.v1.txt` and `ui-web/src/api/__fixtures__/settings-v2-live.json`, leaving content-identical churn in git status after every build (pre-existing; noticed during tempdoc 578) (2026-06-13)
- [ ] running `npm run test:unit:run` in modules/ui-web rewrites a committed fixture with changed line endings (content-identical), dirtying the worktree — likely a fixture-refreshing test vs core.autocrlf interplay — `modules/ui-web/src/api/__fixtures__/settings-v2-live.json:1` (2026-07-06)

### obs:browser — MSW browser-mock activation may be unwired: `src/mocks/browser.ts` exports a `setupWorker` but no `w
`kind: follow-up?` `anchor: src/mocks/browser.ts` `seen: 1` `first: 2026-06-13` `last: 2026-06-13`
- [ ] MSW browser-mock activation may be unwired: `src/mocks/browser.ts` exports a `setupWorker` but no `worker.start()` exists in app source and `src/main.jsx` never imports it — verify whether `VITE_MSW=true npm run dev` actually mounts MSW in the browser, or update `docs/how-to/develop-ui.md` mock-mode section (2026-06-13)

### obs:gpljobcoordinator — GPL has no doc-sampling cap — `GplJobCoordinator` iterates the entire corpus (`ListAllDocumentIds`),
`kind: defect?` `anchor: modules/app-services/.../gpl/GplJobCoordinator.java` `seen: 1`
- [ ] GPL has no doc-sampling cap — `GplJobCoordinator` iterates the entire corpus (`ListAllDocumentIds`), so a GPL run on a 5k-doc corpus is ~2+ hrs (~1.6s/doc). A max-docs/sample config would make GPL tractable on large corpora for eval + first-model bootstrap. — `modules/app-services/.../gpl/GplJobCoordinator.java` (tempdoc 580 §12.8, 2026-06-14)

### obs:logger-general — logger.ts uses CSS `var(--text-*)` inside console `%c` style strings, which don't resolve in devtool
`kind: defect?` `anchor: logger.ts` `seen: 1` `first: 2026-06-15` `last: 2026-06-15`
- [ ] logger.ts uses CSS `var(--text-*)` inside console `%c` style strings, which don't resolve in devtools console — colors silently fall back to default — `modules/ui-web/src/utils/logger.ts:63` (2026-06-15)

### obs:ui-shot-cleanup — ui-shot-cleanup.mjs exists on disk but is not wired in .claude/settings.local.json (hooks-reference.
`kind: defect?` `anchor: scripts/agent-analytics/hooks/ui-shot-cleanup.mjs` `seen: 3` `first: 2026-06-16` `last: 2026-07-16`
- [ ] ui-shot-cleanup.mjs exists on disk but is not wired in .claude/settings.local.json (hooks-reference.md documents it as a SessionEnd hook) — `scripts/agent-analytics/hooks/ui-shot-cleanup.mjs` (2026-06-16)
- [ ] ui-shot's worktree auto-serve Vite (port 5174) survives the capture and breaks a later `./gradlew.bat build` in the same session: it holds handles under modules/ui-web, so `:modules:ui:installWebDependencies` fails with npm exit -4048 (libuv UV_EPERM on Windows) — looks like a build defect, is a live file lock. Reproduced 2026-07-15; killing the pid made the same build green. `ui-shot-cleanup` only fires at SessionEnd, so ANY capture-then-build session hits this. Remedy: stop the 5174 vite before building (pid via its `vite.js --port 5174` cmdline), or teach the build/hook to reap it — `scripts/agent-analytics/hooks/ui-shot-cleanup.mjs` (2026-07-15)
- [ ] ui-shot's auto-served Vite (port 5174) left running from an earlier /ui-check session holds modules/ui-web/node_modules/lightningcss-win32-x64-msvc/*.node locked; a later ./gradlew.bat build's :modules:ui:installWebDependencies task fails EPERM/unlink trying to replace it. Kill the stray vite.js process for the worktree before a full build if one was ui-shot'd earlier in the session — `scripts/agent-analytics/hooks/ui-shot-cleanup.mjs` only fires at SessionEnd, not before an in-session gradle build. (2026-07-16)

### obs:presentation-demo — presentation-demo §7 chip strip drifts from the real HEALTH_STATS_BODY strip — demo shows Indexed/Qu
`kind: defect?` `anchor: modules/ui-web/src/shell-v0/demo/presentation-demo.ts` `seen: 1`
- [ ] presentation-demo §7 chip strip drifts from the real HEALTH_STATS_BODY strip — demo shows Indexed/Queue/GPU%/Memory/Uptime/Embeddings/Reranker; real surface declares Embeddings/SPLADE/Reranker/NER/GPU cuda12/Float32. The demo is not a faithful preview of the surface it illustrates. — `modules/ui-web/src/shell-v0/demo/presentation-demo.ts:327` (2026-06-16, tempdoc 594 §11.4)

### obs:0004-single-tenant-gpu-policy — ADR-0004 line 52 stale: claims embedder "defaults to CPU-only (JUSTSEARCH_EMBED_GPU_LAYERS opt-in)"
`kind: defect?` `anchor: docs/decisions/0004-single-tenant-gpu-policy.md` `seen: 1` `first: 2026-06-17` `last: 2026-06-17`
- [ ] ADR-0004 line 52 stale: claims embedder "defaults to CPU-only (JUSTSEARCH_EMBED_GPU_LAYERS opt-in)" — the current ONNX path defaults embed-GPU via the master gpu auto-detect switch (`ResolvedConfigBuilder.resolveEmbedGpuEnabled`); contradicts ADR. — `docs/decisions/0004-single-tenant-gpu-policy.md:52` (2026-06-17)

### obs:indexingoverlay — IndexingOverlay gating field `ai.index.embeddingQueueSize` does not track the embedding *backfill* q
`kind: defect?` `anchor: modules/ui-web/src/shell-v0/components/IndexingOverlay.ts` `seen: 1` `first: 2026-06-17` `last: 2026-06-17`
- [ ] IndexingOverlay gating field `ai.index.embeddingQueueSize` does not track the embedding *backfill* queue (that's `embeddingPendingCount` in /api/status); the overlay never surfaces during normal embedding backfill — verify which queue it is meant to reflect (VDU/online-embed vs backfill) — `modules/ui-web/src/shell-v0/components/IndexingOverlay.ts:333` (2026-06-17)

### obs:server — Dev-stack ownership gates only `start` (spawn); a non-owner agent can POST `/api/knowledge/ingest`,
`kind: defect?` `anchor: scripts/dev/justsearch-dev-mcp/server.mjs` `seen: 3` `first: 2026-06-17` `last: 2026-07-16`
- [ ] Dev-stack ownership gates only `start` (spawn); a non-owner agent can POST `/api/knowledge/ingest`, `/api/indexing/reindex|gc|migration`, or `reload` against a peer's running stack with no owner check — ownership grants no exclusivity over the mutating/lifecycle surface — `scripts/dev/justsearch-dev-mcp/server.mjs` (2026-06-17)
- [ ] Fresh worktree dev-data has no AI chat-model pack imported; POST /api/ai/runtime/activate fails MODEL_PATH_REQUIRED even with llama-server auto-staged. /api/ai/packs/* expects a packaged manifest (end-user Install-AI flow), not a local-file import. Workaround: GET/POST full /api/settings/v2 with llm.modelPath set to a real local GGUF, then retry activate. Worth a documented dev-stack shortcut. — `scripts/dev/justsearch-dev-mcp/server.mjs:2432-2520` (2026-07-01)
- [ ] MCP dev-tools cannot reach /infra/capabilities or /infra/health (absent from fetch_api_json map + api_call allowlist) and have no raw-gRPC or no-JVM GPU probe — the 4 unique hand-tools covering those niches were owner-deleted (742 followup) so the gap is now uncovered; candidates: add both /infra endpoints to the MCP allowlist, optionally a gpu/nvml preflight probe — `scripts/dev/justsearch-dev-mcp/server.mjs:930` (2026-07-16)

### obs:searchresultsrenderer — **(602 R3 spillover)** `SearchResultsRenderer` (the `x-ui-renderer='search-results'` declared-surfac
`kind: defect?` `anchor: modules/ui-web/src/shell-v0/renderers/controls/SearchResultsRenderer.ts` `seen: 1` `first: 2026-06-18` `last: 2026-06-18`
- [ ] **(602 R3 spillover)** `SearchResultsRenderer` (the `x-ui-renderer='search-results'` declared-surface renderer) is a THIRD search-result renderer that reads raw `hit` fields and does NOT use the shared `projectResultView` view-model or the `resultRowPresentation` path/highlight authority — so it can drift from the two governed rows. Own `ResultHit` shape + no query in scope (can't highlight). Folding it onto the shared projection is a separate step (or 570's grand result-as-projection). — `modules/ui-web/src/shell-v0/renderers/controls/SearchResultsRenderer.ts:67` (2026-06-18)

### obs:fixtures — `modules/ui-web/src/mocks/fixtures.mjs` (+ fixtures.d.mts/.test.ts) is orphaned React-era demo data
`kind: follow-up?` `anchor: modules/ui-web/src/mocks/fixtures.mjs` `seen: 1` `first: 2026-06-19` `last: 2026-06-19`
- [ ] `modules/ui-web/src/mocks/fixtures.mjs` (+ fixtures.d.mts/.test.ts) is orphaned React-era demo data — not referenced by shell-v0; candidate for deletion (tempdoc 615 React-residue audit) (2026-06-19)

### obs:resourceregistry-test — resourceRegistry.test.ts "produces the four expected registrations" fails in the FULL vitest suite b
`kind: environment?` `anchor: resourceRegistry.test.ts` `seen: 1` `first: 2026-06-19` `last: 2026-06-19`
- [ ] resourceRegistry.test.ts "produces the four expected registrations" fails in the FULL vitest suite but passes in isolation — global resource-view-renderer registry pollution / order-dependence (pre-existing on main HEAD f3e002117, confirmed via pre-merge worktree run; not 609). Owner: 421/610/613 renderer-registry. Fix: reset the registry in beforeEach or isolate the count test. (2026-06-19)

### obs:shell-drift — Reachability-fossil (same class as the deleted CapabilityPills, found by the 613 follow-up hunt): th
`kind: follow-up?` `anchor: modules/ui-web/src/shell-v0/chrome/Shell.ts` `seen: 1` `first: 2026-06-20` `last: 2026-06-20`
- [ ] Reachability-fossil (same class as the deleted CapabilityPills, found by the 613 follow-up hunt): the `_aiDependentIds` subsystem in `Shell.ts` is dead. It is the subset of RAIL surfaces consuming `conversationShapes`, but the AI window is a DEEPLINK not a rail peer (577 IA), so the set is always empty. After CapabilityPills' deletion its only consumers are two always-false rail visuals — the `ai-dimmed` class (`renderButton` `dimmed`, Shell.ts:2292) and the `activity-dot` (`showDot`, Shell.ts:2293/2310) — so "dim/pulse an AI-dependent rail surface when AI is offline/active" silently never fires. NB the SIBLING live path `surface.consumes.conversationShapes` checked on the ACTIVE mounted surface (Shell.ts:2601/2632, SurfaceCatalogClient.ts:104) IS reachable (the chat window) — only the rail-filtered `_aiDependentIds` is dead. Same keep-vs-delete judgment as CapabilityPills: dead in the current IA, designed for an AI-dependent rail surface that no longer exists. Static caught it only via root-cause tracing; exhaustive detection of this class needs live per-surface/per-state verification. — `modules/ui-web/src/shell-v0/chrome/Shell.ts:377,1862,2041,2291-2293,2310` (2026-06-20)

### obs:settingscontroller — **Latent governance gap (tempdoc 612 R1):** a TRUST/SECURITY-relevant settings change leaves NO audi
`kind: defect?` `anchor: modules/ui/src/main/java/io/justsearch/ui/api/SettingsController.java` `seen: 1` `first: 2026-06-20` `last: 2026-06-20`
- [ ] **Latent governance gap (tempdoc 612 R1):** a TRUST/SECURITY-relevant settings change leaves NO audit-grade row in the action-ledger. `POST /api/settings/v2` (`SettingsController.handleUpdateSettingsV2`) is ledger-silent — it does not append to `OperationHistoryStore`/`ActionLedgerChangeRegistry`. The only ledger trace is the FE `save-settings` effect, whose payload is an opaque catch-all `Record<string,unknown>`. Today this is harmless (the payload carries only UI prefs; the autonomy dial is FE-local localStorage `justsearch.autonomy.level.v1`, trust grants/consent emit `grant` rows, `core.reset-settings` is an audited Operation). But if a security-relevant key is ever added to `UiSettings`, it would persist silently with no audit row — and tempdoc 612's Activity-feed curation (treating `save-settings` as routine) would then hide it. Mitigation: route any security-relevant setting through an explicit Operation (mirroring `core.reset-settings`), not the bulk `save-settings` POST. — `modules/ui/src/main/java/io/justsearch/ui/api/SettingsController.java` (2026-06-20)

### obs:agent-hooks-v1-drift — governance/agent-hooks.v1.json changes have no regen-reminder hook (unlike lockfile-hint for build.g
`kind: defect?` `anchor: governance/agent-hooks.v1.json` `seen: 2` `first: 2026-07-01` `last: 2026-07-12`
- [ ] governance/agent-hooks.v1.json changes have no regen-reminder hook (unlike lockfile-hint for build.gradle.kts or docs-regen-hint for canonical docs) — after a manifest edit merges, every other existing worktree/checkout keeps serving its stale .claude/settings.local.json (gitignored, per-checkout) until someone manually runs `node scripts/codegen/gen-agent-hooks-wiring.mjs`; discovered while wiring observation-shard-hint in tempdoc 665 — `governance/agent-hooks.v1.json` (2026-07-01)
- [ ] Committed maintainer seed `.claude/settings.local.json.example` is stale vs the hook manifest: regen adds pipe-mask-hint, known-state-hint, observation-shard-hint (3 hooks missing) — no CI gate guards it against `governance/agent-hooks.v1.json` drift. Regenerate: `node scripts/codegen/gen-agent-hooks-wiring.mjs --emit-local-example` — `.claude/settings.local.json.example` (2026-07-12)

### obs:release-v1 — mixed/enron-qa has no committed fetch/materialization mechanism (datasets/ is gitignored, no corpus-
`kind: defect?` `anchor: scripts/jseval/release.v1.json` `seen: 1` `first: 2026-07-01` `last: 2026-07-01`
- [ ] mixed/enron-qa has no committed fetch/materialization mechanism (datasets/ is gitignored, no corpus-fetch-enron equivalent exists) -- a sibling gap to the MIRACL/CourtListener issue tempdoc 666 fixed; a fresh worktree cannot reproduce it. Blocked recomposing release.v1.json with a fully cohort-consistent 5-corpus set -- `scripts/jseval/release.v1.json` measured.mixed/enron-qa._cohort_note (2026-07-01)

### obs:staged-recall-accounting — staged_recall_accounting.py module docstring 'Output shape v1' example (lines ~40-56) is stale — mis
`kind: defect?` `anchor: staged_recall_accounting.py` `seen: 1` `first: 2026-06-30` `last: 2026-06-30`
- [ ] staged_recall_accounting.py module docstring 'Output shape v1' example (lines ~40-56) is stale — missing oracle_judge_ndcg_ceiling, judge_headroom_ceiling, fp_mapping which the actual produce() output includes. Noticed while adding judge_rank_histogram during tempdoc 643 Stage 1b. (2026-06-30)

### obs:test-compare — compare_runs.compare_pipeline_timing has no unit test (test_compare.py covers compare() + per_query_
`kind: environment?` `anchor: test_compare.py` `seen: 1` `first: 2026-07-01` `last: 2026-07-01`
- [ ] compare_runs.compare_pipeline_timing has no unit test (test_compare.py covers compare() + per_query_diff only) — pre-existing gap, noticed while adding compare_stage_decomposition (tempdoc 647) (2026-07-01)

### obs:0024-app-packaging-nsis-per-user-download — ADR-0024 stale: claims '~748 MB installer, no models bundled, all ~8.5 GB downloaded post-install' b
`kind: defect?` `anchor: docs/decisions/0024-app-packaging-nsis-per-user-download.md` `seen: 1` `first: 2026-07-01` `last: 2026-07-01`
- [ ] ADR-0024 stale: claims '~748 MB installer, no models bundled, all ~8.5 GB downloaded post-install' but stageOnnxModels (includeOnnxModels defaults true) bundles ~3.5 GB ONNX retrieval models + CPU llama-server — only GGUF chat + cuda-runtime are download-on-demand — `modules/ui/build.gradle.kts:384` vs `docs/decisions/0024-app-packaging-nsis-per-user-download.md:37-52` (2026-07-01)

### obs:model-inventory — model-inventory.md Open Decision #1 ('should ONNX embedding+SPLADE enter model-registry.v2.json?') i
`kind: defect?` `anchor: docs/reference/model-inventory.md` `seen: 1` `first: 2026-07-01` `last: 2026-07-01`
- [ ] model-inventory.md Open Decision #1 ('should ONNX embedding+SPLADE enter model-registry.v2.json?') is stale/settled — they are already packages in the registry (embedding L5, splade L52), and FP32 embedding model.onnx now ships too, contradicting the doc's 'not yet in registry' notes — `docs/reference/model-inventory.md:355` vs `modules/ui/src/main/resources/ai/model-registry.v2.json` (2026-07-01)

### obs:test-report-ci-walltime-attribution — scripts/ci/test-*.mjs unit tests (test-report-ci-walltime-attribution, test-report-unit-test-attribu
`kind: defect?` `anchor: scripts/ci/test-report-ci-walltime-attribution.mjs` `seen: 1` `first: 2026-07-02` `last: 2026-07-02`
- [ ] scripts/ci/test-*.mjs unit tests (test-report-ci-walltime-attribution, test-report-unit-test-attribution) are not run by any CI workflow — no node --test lane; regressions in these scripts are caught only by their live CI invocation, not the unit tests — `scripts/ci/test-report-ci-walltime-attribution.mjs` (2026-07-02)

### obs:multiplexedstream — governance kernel: ts-any gate fails on modules/ui-web/src/shell-v0/streaming/MultiplexedStream.ts (
`kind: environment?` `anchor: MultiplexedStream.ts` `seen: 2` `first: 2026-07-02` `last: 2026-07-07`
- [ ] governance kernel: ts-any gate fails on modules/ui-web/src/shell-v0/streaming/MultiplexedStream.ts (`(import.meta as any).env`) — pre-existing since PR #22 (tempdoc 662), not registered in gates/ts-any/baseline.txt. Not part of any tempdoc-655 work. (2026-07-02)
- [ ] ts-any gate fails pre-existing on search-thread base: MultiplexedStream.ts:60 (import.meta as any) exists at base 2ef7396 with ratchet baseline 0 — 683-era addition without a changeset/rebalance — `modules/ui-web/src/shell-v0/streaming/MultiplexedStream.ts:60` (2026-07-07)

### obs:run-judge-with-backend — scripts/jseval/_run_judge_with_backend.py (untracked, tempdoc-624 judge-scoring-gap scratch) hardcod
`kind: defect?` `anchor: scripts/jseval/_run_judge_with_backend.py` `seen: 1` `first: 2026-07-02` `last: 2026-07-02`
- [ ] scripts/jseval/_run_judge_with_backend.py (untracked, tempdoc-624 judge-scoring-gap scratch) hardcodes JUSTSEARCH_SERVER_EXE to modules/ui/build/llama-server/stage/llama-server.exe (CPU-only), bypassing dev-runner.cjs's tempdoc-656 GPU-preferred shared-cuda12 resolution entirely — this is the actual cause of a 47min CPU-bound judge run this session; dev-runner.cjs/prepare-worktree.cjs itself already do the right thing — `scripts/jseval/_run_judge_with_backend.py:33-48` (2026-07-02)

### obs:dev-runner-drift — justsearch_dev_start defaults to launching the backend from the MAIN checkout's installed dist (cwd=
`kind: follow-up?` `anchor: scripts/dev/dev-runner.cjs` `seen: 1` `first: 2026-07-02` `last: 2026-07-02`
- [ ] justsearch_dev_start defaults to launching the backend from the MAIN checkout's installed dist (cwd=main repo, ui.bat), not the calling worktree's — even when called from inside a worktree session. Verifying a worktree-local Java fix live against the dev stack silently ran unmodified main-branch code for ~40 minutes (evidence looked stale after 3 restarts + hard-cleans) until distFrom was passed explicitly pointing at the worktree path. The tool schema documents this (`distFrom`, tempdoc 606 Piece 4) but nothing nudges an agent to set it — worth a hook-hint or MCP tool default when sessionId resolves to a worktree cwd. `scripts/dev/dev-runner.cjs`, `scripts/dev/justsearch-dev-mcp/server.mjs` (2026-07-02)

### obs:pendingauthorizationbridge — PR#55 (655 MCP policy) post-merge review F1 (medium): duplicate approval ceremony for browser-origin
`kind: defect?` `anchor: pendingAuthorizationBridge.ts` `seen: 1` `first: 2026-07-02` `last: 2026-07-02`
- [ ] PR#55 (655 MCP policy) post-merge review F1 (medium): duplicate approval ceremony for browser-originated gated invocations — pendingAuthorizationBridge.ts subscribes with no transport filter and its handledIds guard never fires on the in-page invoke-first path (Shell.ts:1048), so a gated in-page call queues TWO identical dialogs (second approval 410s harmlessly). One-line fix: filter bridge to transport==='MCP' (field already serialized) + a bridge test pinning non-MCP broadcast -> no presentation; also unhardcode InvocationProvenance.mcp in AuthorizationController.executeApprovedPending:271. Lower: F2 schema-drift guard manual for browse/ingest (fails safe); F4 no serverInfo.version patch bump despite gated-ingest flow change (defensible). (2026-07-02)

### obs:agent-utility-run — agent_utility_run._per_query_from_result (classic run_agent_eval path) does not filter results with
`kind: defect?` `anchor: scripts/jseval/jseval/agent_utility_run.py` `seen: 1` `first: 2026-07-02` `last: 2026-07-02`
- [ ] agent_utility_run._per_query_from_result (classic run_agent_eval path) does not filter results with r.get('error') set, unlike eval_logs_to_summaries's Inspect path which skips metadata.error samples — an errored/timed-out classic cell (tool_calls=[] default, correct=False, cost=0) can be silently included in paired comparisons as a genuine zero-tool-call zero-cost observation instead of being excluded — `scripts/jseval/jseval/agent_utility_run.py:33-51` (2026-07-02)

### obs:unanchored — scoop python/java `current` symlinks are Windows-unfriendly (mid-session regression, likely needs `s
`kind: defect?` `anchor: none` `seen: 1` `first: 2026-04-23` `last: 2026-04-23`
- [ ] scoop python/java `current` symlinks are Windows-unfriendly (mid-session regression, likely needs `scoop reset` with admin or reinstall) — blocks running `jseval` / java-based CLIs without fully-qualified versioned paths — `F:\scoop\apps\{python,temurin25-jdk}\current` (2026-04-23)

### obs:unanchored-drift — Methodology improvement — tempdocs that propose to mirror an existing component should source-anchor
`kind: defect?` `anchor: none` `seen: 1` `first: 2026-05-05` `last: 2026-05-05`
- [ ] Methodology improvement — tempdocs that propose to mirror an existing component should source-anchor it verbatim at write time (§A.0 block with source path). Caught defect class: tempdoc-vs-source numeric drift (height, strokeWidth, behavior contract). Reference: the retired 421 FE-rewrite draft slices/3a-1-4-timeseries-resource-category.md §B.1 — four mismatches caught at pre-impl, second instance of source-vs-shape after 444a. (2026-05-05)

### obs:unanchored-drift-2 — Inbound references to 3a-1-8f assume narrow Axis-6 framing ("mechanical structural-diff"); kernel-de
`kind: defect?` `anchor: none` `seen: 1` `first: 2026-05-07` `last: 2026-05-07`
- [ ] Inbound references to 3a-1-8f assume narrow Axis-6 framing ("mechanical structural-diff"); kernel-design rewrite leaves them stale until Phase 5 ships — `the retired 421 FE-rewrite draft {10-kernel,50-decisions,slices,60-migration-history}/*` (2026-05-07)

### obs:unanchored-general-5 — 635 suite-profile: --records-root agent-record lookup expects tmp/635-<dataset-name>/ but the prose
`kind: defect?` `anchor: none` `seen: 1` `first: 2026-06-23` `last: 2026-06-23`
- [ ] 635 suite-profile: --records-root agent-record lookup expects tmp/635-<dataset-name>/ but the prose flagship records live in tmp/635-prose-v2/ (name mismatch) — agent_acc_delta shows only for members whose record dir matches the dataset name (2026-06-23)

### obs:unanchored-error — Local Rust/cargo builds blocked by Windows Application Control policy (os error 4551) on freshly-bui
`kind: defect?` `anchor: none` `seen: 1` `first: 2026-06-23` `last: 2026-06-23`
- [ ] Local Rust/cargo builds blocked by Windows Application Control policy (os error 4551) on freshly-built build-script binaries (e.g. zerocopy) — affects `modules/shell/src-tauri` cargo build/test in worktrees; same machine-permissions class as the scoop-shim quirk. Tauri/Rust changes can't be compile-verified locally. (2026-06-23)

### obs:unanchored-drift-4 — workerRpcStale env bug — Head→Worker status RPC reports stale on first stack-start of a session even
`kind: defect?` `anchor: none` `seen: 1` `first: 2026-05-18` `last: 2026-05-18`
- [ ] workerRpcStale env bug — Head→Worker status RPC reports stale on first stack-start of a session even with fresh dataDir; resolves on a second `installDist` + restart. Hypothesis: stale jar artifact between agent sessions. Reproduced in tempdoc 516 Wave 4 attempt (commit a7ea6fdab) but did NOT recur across 3 Tier-3 attempts after `installDist` (2026-05-18) — `HeadlessApp` / `GrpcHealthService` / `IndexStatusOps` chain (2026-05-18)

### obs:unanchored-general-10 — 526 §17 verification finding — Lit class-field shadowing pattern: `static properties = { foo: { stat
`kind: follow-up?` `anchor: none` `seen: 1` `first: 2026-05-21` `last: 2026-05-21`
- [ ] 526 §17 verification finding — Lit class-field shadowing pattern: `static properties = { foo: { state: true } }` paired with `private foo: T = initialValue;` silently breaks reactivity (TS class-field initializer runs after Lit installs the accessor, shadowing it). Caught by browser runtime warning, missed by unit tests + static review. Fix pattern: `declare private foo: T;` plus initialization in the constructor. Consider an ESLint rule (`lit/no-classfield-shadowing` or custom) flagging the pattern. — fixed in `d882d3f7b` for SelectionActionsMenu; no other instances found in audit. (2026-05-21)

### obs:unanchored-general-11 — Tempdoc 501 §12.6 trust-envelope is gated on three pre-conditions: sigstore-java dependency lands, o
`kind: follow-up?` `anchor: none` `seen: 1` `first: 2026-05-21` `last: 2026-05-21`
- [ ] Tempdoc 501 §12.6 trust-envelope is gated on three pre-conditions: sigstore-java dependency lands, offline build-time-key signing flow exists, and at least one signature-verifying consumer category materializes. Document the dependency chain so future tempdocs touching plugin trust (slice 477 H2.3) or remote/external consumers can revisit. — `docs/tempdocs/501-runtime-manifest-design.md §12.6` (2026-05-21)

### obs:unanchored-general-13 — Tempdoc 501 §13 F3 (`@SensitiveField` ArchUnit enforcement) + F5 (per-component `LifecycleSnapshotBu
`kind: follow-up?` `anchor: none` `seen: 1` `first: 2026-05-21` `last: 2026-05-21`
- [ ] Tempdoc 501 §13 F3 (`@SensitiveField` ArchUnit enforcement) + F5 (per-component `LifecycleSnapshotBuilder` for the remaining duplicate-state surface) are documented blockers without reserved tempdoc IDs. F3 needs its own greenfield-annotation tempdoc; F5 lives under tempdoc 502 follow-up territory (capability layer needs a projection API). — `docs/tempdocs/501-runtime-manifest-design.md §11 'Phases 33–40 ... documented blockers'` (2026-05-21)

### obs:unanchored-general-15 — `.dev-data-548/` (worktree dev-stack data dir) is not gitignored — `git add -A` stages it; .gitignor
`kind: defect?` `anchor: none` `seen: 1` `first: 2026-05-26` `last: 2026-05-26`
- [ ] `.dev-data-548/` (worktree dev-stack data dir) is not gitignored — `git add -A` stages it; .gitignore covers `.dev-data/` but not numbered-suffix variants — `.gitignore` (2026-05-26)

### obs:unanchored-general-16 — Agent pitfall: piping source files through PowerShell 5.1 `Get-Content`/`Set-Content -Encoding utf8`
`kind: lesson?` `anchor: none` `seen: 1` `first: 2026-05-26` `last: 2026-05-26`
- [ ] Agent pitfall: piping source files through PowerShell 5.1 `Get-Content`/`Set-Content -Encoding utf8` corrupts non-ASCII (UTF-8 read as Windows-1252 → mojibake for §, em-dash, Greek). Use git-bash or Edit/Write tools for file content moves. (caught + fixed in 548 §4.2 host.ai extraction, commit 632490989) (2026-05-26)

### obs:unanchored-drift-5 — Dev-stack agent-tool execution fails: `OperationExecutorImpl` throws "No handler registered for bind
`kind: defect?` `anchor: none` `seen: 1` `first: 2026-05-26` `last: 2026-05-26`
- [ ] Dev-stack agent-tool execution fails: `OperationExecutorImpl` throws "No handler registered for binding core.search-index" for agent search/ingest tool-calls — no agent tool-call completes successfully (recurring; also hit by the §32 unify live-proof). Blocks live verification of any agent-tool-completion FE feature. Backend handler-registry issue, possibly stale dist (started skipBuild) vs a real gap — needs a backend check (2026-05-26)

### obs:unanchored-general-17 — HealthSurface `static styles` mixes hardcoded rgba() literals with tokens (e.g. :247,:325,:990) — ou
`kind: defect?` `anchor: none` `seen: 1` `first: 2026-05-29` `last: 2026-05-29`
- [ ] HealthSurface `static styles` mixes hardcoded rgba() literals with tokens (e.g. :247,:325,:990) — outside the var() strip codemod scope (tempdoc 557 review INFO) (2026-05-29)

### obs:unanchored-general-18 — Backend message catalog `registry-surface.en.properties` lacks entries for `token-editor-surface` an
`kind: defect?` `anchor: none` `seen: 1` `first: 2026-05-29` `last: 2026-05-29`
- [ ] Backend message catalog `registry-surface.en.properties` lacks entries for `token-editor-surface` and `command-palette`; both id-derive ("Token Editor"/"Command Palette"). Add authored label/description for a complete surface-label authority. (2026-05-29)

### obs:unanchored-gate-red-2 — 553 Phase 2b (clone tripwire gate) deferred: jscpd/CPD not installed + no CPD gradle task; a clone g
`kind: follow-up?` `anchor: none` `seen: 1` `first: 2026-05-27` `last: 2026-05-27`
- [ ] 553 Phase 2b (clone tripwire gate) deferred: jscpd/CPD not installed + no CPD gradle task; a clone gate needs a new heavy devDependency for reduction-grade ratchet value (keystone covers the high-value declared class). Clean path: `npm i -D jscpd` + ratcheting-baseline `clone` gate. — `scripts/governance/gates` (2026-05-27)

### obs:unanchored-drift-6 — §B.2 job-queue count/list divergence: /api/status worker.core.pendingJobs=0 while /api/indexing-jobs
`kind: defect?` `anchor: none` `seen: 1` `first: 2026-05-28` `last: 2026-05-28`
- [ ] §B.2 job-queue count/list divergence: /api/status worker.core.pendingJobs=0 while /api/indexing-jobs/stream snapshots 108 PENDING rows (collections justsearch-help+default), despite both deriving from the single SqliteJobQueue (KnowledgeServer:372) — queueDepth() COUNT(PENDING+PROCESSING) and IndexingJobsChangeStream.readAllRows() read the same connection yet disagree. Contradicts the single-queue static model; needs runtime debugging (likely a help-collection enqueue that never drains + a counting-scope bug). Surfaced during tempdoc 550 lifecycle impl; blocks 550 goal 1(b) "rail pending agrees with status count". (2026-05-28)

### obs:unanchored-general-22 — The MCP `justsearch-dev` dev stack launches the backend from the **main checkout** (`dataDir F:/Just
`kind: defect?` `anchor: none` `seen: 1`
- [ ] The MCP `justsearch-dev` dev stack launches the backend from the **main checkout** (`dataDir F:/JustSearch/modules/ui-web/.dev-data`), not the caller's worktree — so worktree-only backend routes are 404 live and cannot be exercised via the MCP stack. To live-verify unmerged backend changes, launch the dev stack from the worktree or merge first. Discovered tempdoc 561 de-risk pass (2026-05-31).

### obs:unanchored-missing — **Live-verify dev gotcha — zombie `HeadlessApp` JVMs.** Manual `gradlew :modules:ui:runHeadless` (us
`kind: lesson?` `anchor: none` `seen: 1` `first: 2026-06-04` `last: 2026-06-04`
- [ ] **Live-verify dev gotcha — zombie `HeadlessApp` JVMs.** Manual `gradlew :modules:ui:runHeadless` (used to browser-verify a worktree backend) leaks orphan head JVMs across restarts; killing only the PORT LISTENER (`Get-NetTCPConnection -LocalPort N | Stop-Process`) does NOT kill the head — a new run then hangs trying to bind the port while a stale head keeps serving OLD responses. Symptom: `curl` reports "ready after 0 polls" and newly-added registry contributions are MISSING even though the code/tests are correct (cost ~hours of false debugging in 560 §10.4 live-verify). Fix: kill by command line, e.g. `Get-CimInstance Win32_Process -Filter "Name='java.exe'" | ? { $_.CommandLine -match '<worktree-name>' } | Stop-Process -Force`, AND/OR run on a brand-new unused port (a clean port + cmdline-kill is what finally produced the correct end-to-end result). The MCP `justsearch-dev` tools have lease-based ownership precisely to avoid this — prefer them for live verify. — `scripts/dev/run-headless-api.ps1`, `modules/ui/build.gradle.kts` (runHeadless) (2026-06-04)

### obs:unanchored-drift-8 — **Live-verify dev gotcha — `JAVA_TOOL_OPTIONS` is unreliable for passing a `-D` to the head.** Setti
`kind: lesson?` `anchor: none` `seen: 1` `first: 2026-06-04` `last: 2026-06-04`
- [ ] **Live-verify dev gotcha — `JAVA_TOOL_OPTIONS` is unreliable for passing a `-D` to the head.** Setting `$env:JAVA_TOOL_OPTIONS="-Dflag=true"` to toggle a dev feature on the forked `runHeadless` head leaked a STALE value (a prior run's flag name) to the forked `HeadlessApp` even with a fresh shell, `JAVA_TOOL_OPTIONS` empty at User/Machine/Process scope, and `gradlew --stop` run first (gradle-daemon / persistent-shell env carry-over; the flag also never appears in the head's visible `-D` args because JAVA_TOOL_OPTIONS args are applied internally). For dev toggles, read a plain ENV VAR in code via `System.getenv(...)` — it propagates reliably to the forked head like `JUSTSEARCH_API_PORT` does. Pattern adopted for the 560 §10.4 demo: `ExamplePlugin.enabled()` honors `JUSTSEARCH_DEMO_PLUGIN=true` in addition to the `-Djustsearch.demo.plugin` sysprop. — `modules/ui/build.gradle.kts` (runHeadless forwards no arbitrary `-D`) (2026-06-04)

### obs:unanchored-general-24 — **Live-verify dev gotcha — logback logs are NOT in the captured gradle stdout.** App SLF4J/logback `
`kind: lesson?` `anchor: none` `seen: 1` `first: 2026-06-04` `last: 2026-06-04`
- [ ] **Live-verify dev gotcha — logback logs are NOT in the captured gradle stdout.** App SLF4J/logback `log.info(...)` lines do not land in `gradlew :modules:ui:runHeadless | Out-File tmp/head.log` — logback writes to its own configured appender, not gradle's console. Don't grep the gradle-stdout capture to confirm an app-level boot signal (e.g. a startup log line); assert via the HTTP API (`/api/registry/*`, `/api/health`) or read the logback target directly. (2026-06-04)

### obs:unanchored-general-26 — 565 independent UX-audit residual (moderate/minor — agent window): #6 streaming answer needs an SR l
`kind: defect?` `anchor: none` `seen: 1` `first: 2026-06-05` `last: 2026-06-05`
- [ ] 565 independent UX-audit residual (moderate/minor — agent window): #6 streaming answer needs an SR live-region, but naive aria-live on streaming text spams the reader — needs a careful "Agent is responding" status pattern, not raw text; #7 AuthorizationHost dialog lacks focus-trap + initial-focus + focus-restoration; #14 SourcesPane empty-state + #15 ToolCallCard "Awaiting approval" need role=status aria-live=polite; #10 CitationsPanel disclosure + #12 SourcesPane close-button could carry explicit aria-labels. (Fixed already: cursor + chevron reduced-motion, spine-jump focus, source-disclosure aria-label.) — `modules/ui-web/src/shell-v0/components/{AuthorizationHost,SourcesPane,chat/ToolCallCard,chat/CitationsPanel}.ts` (2026-06-05)

### obs:unanchored-general-27 — /dev-stack: chat model is runtime-configurable via `POST /api/settings/v2` `{"llm":{"modelPath":"<gg
`kind: follow-up?` `anchor: none` `seen: 1` `first: 2026-06-06` `last: 2026-06-06`
- [ ] /dev-stack: chat model is runtime-configurable via `POST /api/settings/v2` `{"llm":{"modelPath":"<gguf>","gpuLayers":99}}` then `ai_activate` — no installer pack-import or `-D` restart needed (unblocked the 565 §15 live verification; the dev data dir starts with no chat model). Worth a /dev-stack skill note. (2026-06-06)

### obs:unanchored-drift-9 — Verify the search surface's "Semantic search degraded — showing keyword results" banner is firing fo
`kind: defect?` `anchor: none` `seen: 1`
- [ ] Verify the search surface's "Semantic search degraded — showing keyword results" banner is firing for a real reason: live dev stack showed it while /api/debug/state reported embedding_ready:true & ai_ready:true (suspect ann_cache_ready_percent:75 readiness, not embeddings). Confirm it isn't a stale/false signal. — observed on core.search-surface, dev stack :5173 (2026-06-09, tempdoc 570 §8)

### obs:unanchored-general-30 — Index is in `BLOCKED_LEGACY` embedding state: `Embedding compatibility: BLOCKED_LEGACY (index has no
`kind: environment?` `anchor: none` `seen: 1` `first: 2026-06-11` `last: 2026-06-11`
- [ ] Index is in `BLOCKED_LEGACY` embedding state: `Embedding compatibility: BLOCKED_LEGACY (index has no embedding fingerprint…) — Embedding writes and vector/hybrid queries are blocked until a forced reindex` — semantic/vector + hybrid search is degraded until a forced reindex (`jseval run --reset`). Pre-existing index state, not caused by the run; contributes to the worker's unhealthy status. — `i.j.i.embed.EmbeddingCompatibilityController` (2026-06-11)

### obs:unanchored-general-31 — The build's classpath-SSOT auto-sync (393 §3.6) rewrites `synonyms.{de,en}.v1.txt` to LF on every bu
`kind: follow-up?` `anchor: none` `seen: 1` `first: 2026-06-11` `last: 2026-06-11`
- [ ] The build's classpath-SSOT auto-sync (393 §3.6) rewrites `synonyms.{de,en}.v1.txt` to LF on every build, producing recurring working-tree churn (modified-but-not-mine) that has to be restored before each commit. Consider a `.gitattributes` `eol=lf` on the classpath SSOT copies so the sync is a no-op — `modules/adapters-lucene/src/main/resources/SSOT/catalogs/synonyms.*.v1.txt` (2026-06-11)

### obs:unanchored-general-34 — 585 split relocated several `AgentController` symbols referenced by open items above: `writeAgentEve
`kind: defect?` `anchor: none` `seen: 1` `first: 2026-06-15` `last: 2026-06-15`
- [ ] 585 split relocated several `AgentController` symbols referenced by open items above: `writeAgentEvent`/`evictIfGone`/`writeOrEvict`/the `sources`-emitting `done` case → `AgentSseWriter`; session/history reads (`handleSessionDetail` etc.) → `AgentSessionController`; tools/virtual-ops → `AgentToolsController`. So items #354 (resume-path shadow emitter), #315 (snapshot schema), #364 (sources emit) point at the old file/line — the concerns persist, just relocated. — `modules/ui/src/main/java/io/justsearch/ui/api/Agent{SseWriter,SessionController,ToolsController}.java` (2026-06-15)

### obs:unanchored-general-35 — `@types/dompurify@^3.2.0` in ui-web devDeps is a redundant STUB — dompurify (now 3.4.10) ships its o
`kind: defect?` `anchor: none` `seen: 1` `first: 2026-06-16` `last: 2026-06-16`
- [ ] `@types/dompurify@^3.2.0` in ui-web devDeps is a redundant STUB — dompurify (now 3.4.10) ships its own type definitions; `npm install` warns "you do not need this installed". Removable from `modules/ui-web/package.json` (typecheck stays green without it). — `modules/ui-web/package.json:60` (2026-06-16)

### obs:unanchored-general-36 — `deleteByPathPrefix` (SqliteJobQueue) uses `path LIKE ? || '%'` — `_`/`%` in a path act as LIKE wild
`kind: environment?` `anchor: none` `seen: 1` `first: 2026-06-17` `last: 2026-06-17`
- [ ] `deleteByPathPrefix` (SqliteJobQueue) uses `path LIKE ? || '%'` — `_`/`%` in a path act as LIKE wildcards (over-match); 599 Fix 2 switched the sibling `countByPathPrefix` to a range query but left delete (pre-existing, more dangerous since it deletes). Consider the same range fix. (2026-06-17)

### obs:unanchored-general-37 — a11y: critical `aria-valid-attr-value` on the search input (`jf-search-surface .q`) — an ARIA attrib
`kind: defect?` `anchor: none` `seen: 1` `first: 2026-06-20` `last: 2026-06-20`
- [ ] a11y: critical `aria-valid-attr-value` on the search input (`jf-search-surface .q`) — an ARIA attribute has an invalid value; surfaced reproducibly by `jseval ui-shot home --fixtures` (615 §16 deterministic capture) (2026-06-20)

### obs:unanchored-general-38 — DX/§4 (tempdoc 618): running repo-wide regen (`skills-sync`/`llmstxt-generate`) on a multi-agent dir
`kind: follow-up?` `anchor: none` `seen: 1` `first: 2026-06-20` `last: 2026-06-20`
- [ ] DX/§4 (tempdoc 618): running repo-wide regen (`skills-sync`/`llmstxt-generate`) on a multi-agent dirty `main` silently bakes OTHER agents' uncommitted source-doc WIP into generated artifacts (hit live: a docs-edit regen pulled another agent's VDU/OCR doc WIP into `inference-runtime/SKILL.md`). Mitigation: regen + stage generated artifacts in isolation, or regen only when the relevant sources are clean. Candidate: scope skills-sync to changed sources. (2026-06-20)

### obs:unanchored-missing-3 — `modules/ui-web/node_modules` in the main checkout is incomplete (.bin empty, ~82 pkgs; vite present
`kind: lesson?` `anchor: none` `seen: 1` `first: 2026-06-20` `last: 2026-06-20`
- [ ] `modules/ui-web/node_modules` in the main checkout is incomplete (.bin empty, ~82 pkgs; vite present but its deps missing → `ERR_MODULE_NOT_FOUND` on any `vite`/`npx vite` start). Blocks ui-shot's auto-serve (and bash `npx vite`) from starting a fresh Vite; needs `cd modules/ui-web && npm ci`. Surfaced during 615 §27 live-validation (2026-06-20). Also: detached `npx.cmd`/`cmd npx` spawn dies immediately in this session (scoop-shim-unreachable, agent-lessons.md) — auto-serve relies on reusing an externally-started server here. (2026-06-20)

### obs:unanchored-general-39 — └ 615 §41 live-inspection pinned the 2 nameless Settings controls: the **"Load"** button (PLUGINS →
`kind: defect?` `anchor: none` `seen: 1` `first: 2026-06-21` `last: 2026-06-21`
- [ ] └ 615 §41 live-inspection pinned the 2 nameless Settings controls: the **"Load"** button (PLUGINS → load plugin from URL) and the **"Grant family"** button (AUTHORIZATIONS) — both render visible text but the operable <button> has no accessible name (WCAG 2.5.3 label-in-name). The `jf-button` atom is correct (slot text = name); root cause is in the SettingsSurface usage / nested-slot name-drop. Give each an accessible name matching its visible label. (2026-06-21)

### obs:unanchored-general-41 — tempdoc 623 U7 follow-up: capture ORT library version string worker-side (Head cannot init OrtEnviro
`kind: follow-up?` `anchor: none` `seen: 1` `first: 2026-06-21` `last: 2026-06-21`
- [ ] tempdoc 623 U7 follow-up: capture ORT library version string worker-side (Head cannot init OrtEnvironment — confirmed live; gpu.ortVersion always null in /api/inference/status). Surface via a worker→Head channel the eval manifest retains. cudaMajor+driver already captured. (2026-06-21)

### obs:unanchored-error-4 — HealthSurface's error banner ('Failed to fetch') latches and does not self-clear on subsequent succe
`kind: follow-up?` `anchor: none` `seen: 1` `first: 2026-07-01` `last: 2026-07-01`
- [ ] HealthSurface's error banner ('Failed to fetch') latches and does not self-clear on subsequent successful polls — live-reproduced: Memory/Queue kept updating live for 10+s while the red banner stayed. Same defect class as tempdoc 663's BrainSurface finding (one-shot caught error, not reactively derived from the latest poll). Different surface (Health, not Brain) so out of 663's scope, but corroborates the class is systemic — worth a HealthSurface-scoped look. (2026-07-01)

### obs:unanchored-missing-4 — docs/reference/configuration/environment-variables.md 'Search reranker' section is missing a row for
`kind: defect?` `anchor: none` `seen: 1` `first: 2026-06-30` `last: 2026-06-30`
- [ ] docs/reference/configuration/environment-variables.md 'Search reranker' section is missing a row for JUSTSEARCH_RERANK_MAX_AVG_DOC_LENGTH_CHARS / justsearch.rerank.max_avg_doc_length_chars (exists in EnvRegistry + RerankerConfig but undocumented). Noticed while adding the tempdoc 643 judge-blend rows nearby. (2026-06-30)

### obs:unanchored-error-5 — Running ./gradlew.bat :modules:<x>:compileJava/test/spotlessApply in the SAME worktree while a 'jsev
`kind: lesson?` `anchor: none` `seen: 1` `first: 2026-06-30` `last: 2026-06-30`
- [ ] Running ./gradlew.bat :modules:<x>:compileJava/test/spotlessApply in the SAME worktree while a 'jseval run --start-backend' eval is actively running its own runHeadlessEval JVM caused that live JVM to throw java.lang.NoClassDefFoundError (SearchPipelinePresets) on its first search request -- the concurrent recompile mutated app-services' compiled-classes output dir out from under the running classloader, and the OS port (33221) was left orphaned after the crash. Lesson: never run a separate Gradle build against a worktree while a --start-backend eval is live there; wait for it to stop first. Found during tempdoc 643 Stage 1c-e. (2026-06-30)

### obs:unanchored-error-6 — python -m jseval --help (bare, no subcommand) crashes with UnicodeEncodeError ('charmap' codec can't
`kind: environment?` `anchor: none` `seen: 1` `first: 2026-07-01` `last: 2026-07-01`
- [ ] python -m jseval --help (bare, no subcommand) crashes with UnicodeEncodeError ('charmap' codec can't encode character u03c3 / Greek sigma) when stdout isn't a UTF-8-aware console (e.g. redirected to a file/pipe on Windows, cp1252 default codepage) -- some text in the root CLI's help/epilog contains a sigma character. Subcommand help (e.g. 'jseval run --help') is unaffected. Pre-existing, unrelated to any dependency change; found while verifying jseval after installing the 'datasets' package during tempdoc 643 confidence-building work. (2026-07-01)

### obs:unanchored-general-42 — Quantified follow-up to the staged_recall_accounting trec-blindness bug (see earlier entry this sess
`kind: follow-up?` `anchor: none` `seen: 1` `first: 2026-07-01` `last: 2026-07-01`
- [ ] Quantified follow-up to the staged_recall_accounting trec-blindness bug (see earlier entry this session): recomputed F-026's original scifact judge_low_rate measurement using predictedDocIds instead of trec on the exact archived run (scripts/jseval/tmp/eval-results/643_scifact_ce_on/20260630T232234_scifact) — aggregate judge_low_rate barely moves (0.270 trec vs 0.267 true), but 83/300 individual queries land in a different rank bucket. Worse: comparing that run against the paired Floor-ON run (643_scifact_ce_on_floor/20260630T234714_scifact) via trec shows only 12/300 queries shift bucket (exactly the 5 the register attributed to "the floor firing"); via true predictedDocIds order it's 58/300 — trec is blind to any reordering-only stage's actual effect, so a same-config before/after comparison via staged_recall_accounting can silently attribute run-to-run noise to a real code change (or vice versa). Fixed in F-026's own text (docs/reference/search-quality-register.md) for this specific case; the general fix (prefer predictedDocIds in _ranked_by_qid when present) is still not done and would need re-validating every other register finding that depends on staged_recall_accounting's per-query buckets — out of scope for tempdoc 643, flagged for a future dedicated tempdoc. (2026-07-01)

### obs:unanchored-drift-12 — Killing the jseval-launched Head (runHeadlessEval) java process via Stop-Process/taskkill does NOT t
`kind: defect?` `anchor: none` `seen: 1` `first: 2026-07-01` `last: 2026-07-01`
- [ ] Killing the jseval-launched Head (runHeadlessEval) java process via Stop-Process/taskkill does NOT terminate its llama-server.exe child -- it's left orphaned, still bound to its port, still serving with whatever binary/model it loaded. Reproduced 3 times this session. jseval's own graceful 'Stopping backend...' shutdown path presumably handles this correctly (not confirmed); a hard-kill of the Head does not. Anyone hard-killing a jseval --llm backend (e.g. after an external interruption) should also explicitly check for and kill any lingering llama-server.exe process, or a stale/wrong-binary child can silently keep serving under a freshly-restarted Head that thinks it started a new one. (2026-07-01)

### obs:unanchored-general-46 — inspector-open ui-shot step reports 1 serious axe violation (pre-existing, unrelated to tempdoc 669'
`kind: environment?` `anchor: none` `seen: 1` `first: 2026-07-02` `last: 2026-07-02`
- [ ] inspector-open ui-shot step reports 1 serious axe violation (pre-existing, unrelated to tempdoc 669's --record addition) — noticed while validating video recording spans the search-results->inspector-open chain. (2026-07-02)

### obs:unanchored-drift-13 — bash-tool grep/wc/sha256sum via the /f/... posix-mount path returned stale (pre-edit) content for a
`kind: lesson?` `anchor: none` `seen: 1` `first: 2026-07-02` `last: 2026-07-02`
- [ ] bash-tool grep/wc/sha256sum via the /f/... posix-mount path returned stale (pre-edit) content for a file just written by the Edit tool on an F: (ReFS) volume, while Read/Grep tools and a direct python open() on the F:\... path saw the live content immediately -- a real read-coherency quirk between msys/cygwin posix I/O and native Win32 writes on ReFS, not a bug in the edited file itself. Workaround: prefer the Grep/Read tools (or python's open()) over bash grep/wc/sha256sum for freshly-edited files on this platform. (2026-07-02)

### obs:unanchored-drift-14 — Hard Invariant #1 names only Lucene, but the worker-exclusive SQLite job queue is equally ownership-
`kind: follow-up?` `anchor: none` `seen: 1` `first: 2026-07-06` `last: 2026-07-06`
- [ ] Hard Invariant #1 names only Lucene, but the worker-exclusive SQLite job queue is equally ownership-critical and no longer named by any invariant — SqliteJobQueue lives in modules/indexer-worker and no Head main code touches SQLite today, yet nothing (invariant text or ArchUnit rule) forbids a future Head-side SQLite reader. Consider re-affirming the SQLite half of the ownership invariant. (2026-07-06)

### obs:agent-utility-inspect-error — New agent-eval leak class found + cleaned (624 DE cycle): an earlier run with direct write access to
`kind: defect?` `anchor: agent_utility_inspect.py` `seen: 3` `first: 2026-07-03` `last: 2026-07-07`
- [ ] New agent-eval leak class found + cleaned (624 DE cycle): an earlier run with direct write access to the canonical corpus-dir left agent-authored solver artifacts (connections.txt = the corpus's full entity-link map; trace_chain.txt = a chain-tracing bash script) inside datasets/golden/battlefield-de-v1/corpus-dir — carried into the archive and re-ingested into the MCP index (394 docs vs 390+sentinel). Removed + clean re-ingest before the DE certified run. EN v4 verified unaffected via per-cell tool-call scan (0 genuine write commands / 5,862 calls). Residual structural gap: run_utility_eval stages ONE shared corpus copy per run, so within-run cross-cell writes remain possible — per-cell staging or a read-only staged dir would close it. — `scripts/jseval/jseval/agent_utility_inspect.py:stage_corpus_dir` (2026-07-03)
- [ ] jseval agent-utility eval (Inspect eval_set) crashes with UnicodeEncodeError on the rich-display braille spinner (⠿) when stdout is redirected/non-tty on Windows (cp1252) — set INSPECT_DISPLAY=none or PYTHONUTF8=1 for backgrounded runs. Pre-existing (default display), but more relevant post-675 (long non-blocking runs) — `jseval/agent_utility_inspect.py:run_utility_eval` calls eval_set with default display (2026-07-07)
- [ ] Upstream Inspect defect: any float task-arg (or non-default GenerateConfig float) breaks eval_set resume — the JSON recorder reads persisted floats back as Decimal (ijson without use_float), so task_identifier re-hashes to a different id than the persisted log → PrerequisiteError. Worked around in jseval by threading max_budget as str — `scripts/jseval/jseval/agent_utility_inspect.py` claude_agent_solver (2026-07-07)

### obs:auth — 624 §M.9 cross-family calibration: user has ~/.codex/auth.json (Codex CLI) but its OPENAI_API_KEY fi
`kind: defect?` `anchor: auth.json` `seen: 1` `first: 2026-07-03` `last: 2026-07-03`
- [ ] 624 §M.9 cross-family calibration: user has ~/.codex/auth.json (Codex CLI) but its OPENAI_API_KEY field is empty/null (auth is ChatGPT-OAuth id_token/access_token, not an API key) — not a usable non-Anthropic grader credential. No OPENAI_API_KEY/GEMINI_API_KEY/GOOGLE_API_KEY env vars, no .env files, no gcloud ADC found. (2026-07-03)

### obs:inferencehandlers — VDU offline processing (post-672) drains in ~100-doc batches and then stops with vduProcessing=false
`kind: defect?` `anchor: modules/ui/src/main/java/io/justsearch/ui/api/InferenceHandlers.java` `seen: 1` `first: 2026-07-03` `last: 2026-07-03`
- [ ] VDU offline processing (post-672) drains in ~100-doc batches and then stops with vduProcessing=false while visualTextNeededCount>0 — the idle/energy auto-trigger does not chain batches on a loaded queue; each batch needed a manual POST /api/offline/process re-trigger (observed live draining synth-scan-v1's 360 docs at ~9.3 docs/min per batch). Whether per-batch stop-without-continuation is intended energy behavior or a gap belongs to 672's owner. — `modules/ui/src/main/java/io/justsearch/ui/api/InferenceHandlers.java:555` (2026-07-03)

### obs:unanchored-general-49 — 624 §T.2 scan-battlefield premise empirically resolved NEGATIVE at the shipped degradation band: the
`kind: defect?` `anchor: none` `seen: 1` `first: 2026-07-03` `last: 2026-07-03`
- [ ] 624 §T.2 scan-battlefield premise empirically resolved NEGATIVE at the shipped degradation band: the band tuned to defeat Claude Code's multimodal Read ALSO defeats the product's own extraction stack (local Qwen VLM hallucinates; tesseract path yields empty) — live fidelity nDCG@10=0.0000 on a clean fully-extracted scan-only index. The structural-advantage window requires extraction >= agent vision, but the local extractor is weaker than frontier vision; a viable band (readable-by-pipeline, unreadable-by-agent) may not exist and would need adversarial-to-frontier-vision-but-OCR-friendly degradations — a research question, not a parameter tweak. — `datasets/golden/synth-scan-v1` (2026-07-03)

### obs:unanchored-drift-15 — package.json self-presentation bug: version says 1.0.0 (app is 0.1.0-alpha), description is stale pr
`kind: defect?` `anchor: none` `seen: 1` `first: 2026-07-04` `last: 2026-07-04`
- [ ] package.json self-presentation bug: version says 1.0.0 (app is 0.1.0-alpha), description is stale pre-cutover text, author/keywords empty - GitHub/npm surfaces show wrong metadata (outsider first-touch audit 2026-07-01) - `package.json:3` (2026-07-04)

### obs:unanchored-general-50 — README badge line still ships the empty placeholder comment (build status / release / nDCG badge) -
`kind: defect?` `anchor: none` `seen: 1` `first: 2026-07-04` `last: 2026-07-04`
- [ ] README badge line still ships the empty placeholder comment (build status / release / nDCG badge) - visibly unfinished self-presentation on the public front door (outsider first-touch audit 2026-07-01) - `README.md:7` (2026-07-04)

### obs:hook-base — Agent-harness pitfall: PowerShell 5.1 pipes prepend a UTF-8 BOM to native stdin, so piping crafted J
`kind: lesson?` `anchor: hook-base.mjs` `seen: 1` `first: 2026-07-07` `last: 2026-07-07`
- [ ] Agent-harness pitfall: PowerShell 5.1 pipes prepend a UTF-8 BOM to native stdin, so piping crafted JSON into a hook script for a runtime probe silently fails JSON.parse (hook reads null, stays silent) — probe hooks via node spawnSync with the input option (the hook-integrity bite mechanism) instead — `scripts/agent-analytics/lib/hook-base.mjs:readJsonStdin` (2026-07-07)

### obs:624-agentic-retrieval-eval-rebuild — Tempdoc frontmatter status fields can be multi-thousand-token essays (e.g. tempdoc 624's), which mak
`kind: defect?` `anchor: docs/tempdocs/624-agentic-retrieval-eval-rebuild.md` `seen: 1` `first: 2026-07-07` `last: 2026-07-07`
- [ ] Tempdoc frontmatter status fields can be multi-thousand-token essays (e.g. tempdoc 624's), which makes any batch frontmatter survey blow up in tokens and defeats cheap staleness checks — evidence for tempdoc 646's derived current-state trigger; surveys should truncate status to ~200 chars — `docs/tempdocs/624-agentic-retrieval-eval-rebuild.md:18` (2026-07-07)

### obs:unanchored-general-51 — downloadLlamaCudaPrebuilt skips the SHA-256 pin the CPU prebuilt has ('hash check disabled for large
`kind: defect?` `anchor: none` `seen: 1` `first: 2026-07-06` `last: 2026-07-06`
- [ ] downloadLlamaCudaPrebuilt skips the SHA-256 pin the CPU prebuilt has ('hash check disabled for large file') — the cuda12 zip is version-pinned by URL but not content-pinned — `modules/ui/build.gradle.kts:566-570` (2026-07-06)

### obs:unanchored-general-52 — downloadLlamaCudaPrebuilt skips the SHA-256 pin the CPU prebuilt has ('hash check disabled for large
`kind: defect?` `anchor: none` `seen: 1` `first: 2026-07-06` `last: 2026-07-06`
- [ ] downloadLlamaCudaPrebuilt skips the SHA-256 pin the CPU prebuilt has ('hash check disabled for large file') — the cuda12 zip is version-pinned by URL but not content-pinned — `modules/ui/build.gradle.kts:566-570` (2026-07-06)

### obs:cost-session — cost-session analytics tool defect (develocity audit 2026-07-05): per-turn cost attribution falls ba
`kind: defect?` `anchor: scripts/agent-analytics/cost-session.mjs` `seen: 1` `first: 2026-07-06` `last: 2026-07-06`
- [ ] cost-session analytics tool defect (develocity audit 2026-07-05): per-turn cost attribution falls back to a wrong model price for ~all turns (measured -18%/-41% undercount patterns), and its batch mode reads a directory that does not exist — `scripts/agent-analytics/cost-session.mjs`. Fix or retire before trusting any cost read. (2026-07-06)

### obs:unanchored-general-55 — 624 §T.2 scan-battlefield premise empirically resolved NEGATIVE at the shipped degradation band: the
`kind: defect?` `anchor: none` `seen: 1` `first: 2026-07-03` `last: 2026-07-03`
- [ ] 624 §T.2 scan-battlefield premise empirically resolved NEGATIVE at the shipped degradation band: the band tuned to defeat Claude Code's multimodal Read ALSO defeats the product's own extraction stack (local Qwen VLM hallucinates; tesseract path yields empty) — live fidelity nDCG@10=0.0000 on a clean fully-extracted scan-only index. The structural-advantage window requires extraction >= agent vision, but the local extractor is weaker than frontier vision; a viable band (readable-by-pipeline, unreadable-by-agent) may not exist and would need adversarial-to-frontier-vision-but-OCR-friendly degradations — a research question, not a parameter tweak. — `datasets/golden/synth-scan-v1` (2026-07-03)

### obs:unanchored-drift-20 — ui-shot step skeleton-library fails in every harness mode (live --no-demo, --fixtures, demo): rail c
`kind: defect?` `anchor: none` `seen: 1` `first: 2026-07-06` `last: 2026-07-06`
- [ ] ui-shot step skeleton-library fails in every harness mode (live --no-demo, --fixtures, demo): rail click reaches the library surface but data-testid skeleton-library never becomes visible; the e2e_view_delay_ms=4000 skeleton-hold mechanism has no matching selectors in modules/ui-web/src (grep empty) — step vs FE drift predating worktree 683; found during the 683 liveness census (2026-07-06)

### obs:buf — Stale comment: contracts/catalog/severity/buf.yaml:8 still claims the root :wireGenerate task discov
`kind: defect?` `anchor: contracts/catalog/severity/buf.yaml` `seen: 1` `first: 2026-07-07` `last: 2026-07-07`
- [ ] Stale comment: contracts/catalog/severity/buf.yaml:8 still claims the root :wireGenerate task discovers each catalog for TS emission — :wireGenerate is Java-only since the 683 TS-emission teardown — `contracts/catalog/severity/buf.yaml:8` (2026-07-07)

### obs:unanchored-missing-6 — npm version skew trap: a locally-resynced modules/ui-web/package-lock.json (npm 11.6/node 24.12) pas
`kind: defect?` `anchor: none` `seen: 1` `first: 2026-07-07` `last: 2026-07-07`
- [ ] npm version skew trap: a locally-resynced modules/ui-web/package-lock.json (npm 11.6/node 24.12) passed local 'npm ci --dry-run' but failed CI's stricter sync validator (node 24.14 bundled npm) with 'Missing: @emnapi/core@1.11.2' — optional wasm-binding transitives. Fix that works on both: regenerate with 'npx -y npm@latest install --package-lock-only' and verify 'ci --dry-run' under BOTH npms. Cost one red required-checks round on PR #77. (2026-07-07)

### obs:url-probe-system-prompt — url-probe-system-prompt.md was already stale before my edit: core.rebuild-index shows audience=OPERA
`kind: environment?` `anchor: scripts/ci/url-probe-system-prompt.md` `seen: 1` `first: 2026-07-07` `last: 2026-07-07`
- [ ] url-probe-system-prompt.md was already stale before my edit: core.rebuild-index shows audience=OPERATOR at line 30-32 but CoreOperationCatalog.java:430 declares Audience.USER (tempdoc 598) — pre-existing drift, out of scope for tempdoc 689 item 3 — `scripts/ci/url-probe-system-prompt.md:32` (2026-07-07)

### obs:unanchored-error-7 — capture_evidence crashes on Windows with a libuv fail-fast (`Assertion failed: !(handle->flags & UV_
`kind: defect?` `anchor: none` `seen: 1` `first: 2026-07-07` `last: 2026-07-07`
- [ ] capture_evidence crashes on Windows with a libuv fail-fast (`Assertion failed: !(handle->flags & UV_HANDLE_CLOSING), src/win/async.c`) after capturing api-status/api-health — blocks durable EvidenceBundle capture; live verification had to fall back to manual /mcp HTTP calls (tempdoc 658) — `scripts/…/capture-evidence` (MCP dev tool) (2026-07-07)

### obs:record-merge — Dev-tooling test-coverage gap (surfaced by 684): record-merge.mjs has NO dedicated test, and prepare
`kind: defect?` `anchor: record-merge.mjs` `seen: 3` `first: 2026-07-07` `last: 2026-07-16`
- [ ] Dev-tooling test-coverage gap (surfaced by 684): record-merge.mjs has NO dedicated test, and prepare-worktree.cjs's item-3 gradle-spawn fix was verified only by static path-reasoning (no live run of npm-ci + installDist). 684 added the first test for remove-worktree.cjs (test-remove-worktree.cjs); the sibling lifecycle scripts remain a regression-net gap. Task-shaped, not tempdoc-shaped; a real prepare-worktree integration test is heavy (npm ci + installDist) so weigh unit-level spawn-path assertion vs full integration. — scripts/dev/prepare-worktree.cjs, scripts/agent-analytics/record-merge.mjs (2026-07-07)
- [ ] session-merges.ndjson is fragmented per-worktree: remove-worktree/record-merge resolve repoRoot from __dirname, so a teardown run from inside a worktree appends to THAT worktree's tmp/agent-telemetry/session-merges.ndjson, not the main checkout's (211 rows). Any outcome join reading only one root sees a partial ledger. Observed while testing the attribution fix (tempdoc 739 follow-up) — `scripts/agent-analytics/record-merge.mjs` (2026-07-15)
- [ ] record-merge.mjs backfill on a diverged local main links the session to the LOCAL merge commit (858e819a 'Merge branch main…', classes as 'other') instead of the public squash commit — baseline-economics merge classing gets polluted; backfill should resolve the squash on origin/main — `scripts/agent-analytics/record-merge.mjs` (2026-07-16)

### obs:unanchored-general-62 — worker.log: native ORT stderr (ANSI-colored CUDA/BFCArena OOM traces) is captured with NUL-byte-inte
`kind: defect?` `anchor: none` `seen: 1` `first: 2026-07-07` `last: 2026-07-07`
- [ ] worker.log: native ORT stderr (ANSI-colored CUDA/BFCArena OOM traces) is captured with NUL-byte-interleaved wide-char garbling (e.g. ' [ 1 ; 3 1 m 2 0 2 6 - 0 7 - 0 7...'), making the line unparseable by grep/ripgrep (reports 'binary file matches'); root cause looks like a UTF-16-as-UTF-8 decode mismatch on the native-process stderr redirect. Seen during tempdoc-691 pipeline profiling — F:\justsearch-public\tmp\headless-eval-data\logs\worker.log around 2026-07-07T17:26:47 and 17:32:59. (2026-07-07)

### obs:unanchored-general-63 — worker.log floods DEBUG 'Loaded analyzers catalog from repo path' (i.j.a.lucene.analyzers.SsotAnalyz
`kind: follow-up?` `anchor: none` `seen: 1` `first: 2026-07-07` `last: 2026-07-07`
- [ ] worker.log floods DEBUG 'Loaded analyzers catalog from repo path' (i.j.a.lucene.analyzers.SsotAnalyzerRegistry) at extremely high frequency (multiple times per ms) on grpc-default-executor threads during backfill/enrichment, one load-and-parse per request with no evident in-memory cache — candidate hot-path inefficiency. Seen during tempdoc-691 pipeline profiling, e.g. 2026-07-07T17:32:58-59 in worker.log. (2026-07-07)

### obs:unanchored-general-64 — Benchmarks module's default relative model-dir args (e.g. models/onnx/gte-multilingual-base) resolve
`kind: defect?` `anchor: none` `seen: 1` `first: 2026-07-07` `last: 2026-07-07`
- [ ] Benchmarks module's default relative model-dir args (e.g. models/onnx/gte-multilingual-base) resolve against rootProject.projectDir, but a fresh git worktree has only empty scaffold dirs for models/ (the actual .onnx LFS binaries are untracked-in-main-only per repo git status) — any bench run from a worktree needs an explicit absolute --*-model-dir= override pointing at the main checkout — `modules/benchmarks/build.gradle.kts` (encoderBatchSweepBench task) / F:\justsearch-public\models\onnx\gte-multilingual-base (2026-07-07)

### obs:onnxembeddingencoder — Parent-doc embedding recomputes chunk vectors that are immediately discarded: EmbeddingService.embed
`kind: defect?` `anchor: embed/onnx/OnnxEmbeddingEncoder.java` `seen: 3` `first: 2026-07-07` `last: 2026-07-11`
- [ ] Parent-doc embedding recomputes chunk vectors that are immediately discarded: EmbeddingService.embedDocumentBatch (embed/EmbeddingService.java:365-381) only keeps result.vector() (the pooled parent vector) from OnnxEmbeddingEncoder.embedBatchWithChunking's per-chunk GPU work; the actual CHUNK_VECTOR field is filled by a second, independent embedding pass over CHUNK_CONTENT (pre-split by ChunkSplitter during primary indexing, loop/ops/CombinedEnrichmentBackfillOps.java:210-226) — i.e. long documents' content is chunk-embedded twice with different chunk boundaries (encoder's 512/128-overlap window vs ChunkSplitter's own window) — `embed/onnx/OnnxEmbeddingEncoder.java:385-448` (2026-07-07)
- [ ] OnnxEmbeddingEncoder.buildAssembly hardcodes tokenizer path as modelDir.resolve("tokenizer.json") instead of modelDir.resolve(manifest.tokenizer()) — BertNerInference correctly uses the manifest field; embedding encoder ignores a declared non-default tokenizer filename — `modules/worker-core/src/main/java/io/justsearch/indexerworker/embed/onnx/OnnxEmbeddingEncoder.java:159` (2026-07-11)
- [ ] OnnxEmbeddingEncoder.createChunks raw id-slice windows CLS-pool a non-[CLS] token on windows 2+ (708 offline A/B: 0.105 vs 0.745 R@10 with proper per-window special tokens); F-031's single-pass path moots it up to 8192 tokens, but any residual window-mean path for docs >8192 tokens still carries the artifact — `modules/worker-core/src/main/java/io/justsearch/indexerworker/embed/onnx/OnnxEmbeddingEncoder.java:525` (2026-07-11)

### obs:remotedocumentservice — RemoteDocumentService.mapRetrieveContextResponse hardcodes docsUsed=0 on the rich-params retrieve pa
`kind: defect?` `anchor: modules/app-services/src/main/java/io/justsearch/app/services/worker/RemoteDocumentService.java` `seen: 1` `first: 2026-07-07` `last: 2026-07-07`
- [ ] RemoteDocumentService.mapRetrieveContextResponse hardcodes docsUsed=0 on the rich-params retrieve path, violating ContextResult javadoc (docsUsed = full docs used when chunksUsed==0) — a worker-signaled FULLTEXT_FALLBACK reports docsUsed=0 despite using full documents — `modules/app-services/src/main/java/io/justsearch/app/services/worker/RemoteDocumentService.java:415` (found during tempdoc 655 review; MCP answer hint fixed to use citation parentDocId instead) (2026-07-07)

### obs:inference-status-response — contract-surfaces: registered InferenceStatusResponse with EMPTY consumers — its generated FE Zod ty
`kind: follow-up?` `anchor: inference-status-response.ts` `seen: 1` `first: 2026-07-07` `last: 2026-07-07`
- [ ] contract-surfaces: registered InferenceStatusResponse with EMPTY consumers — its generated FE Zod type (`inference-status-response.ts`, 683-onboarded) has no parse-boundary consumer; 663 Stage-4 typed FE consumption unfinished (`inferencePoll.ts:13` untyped `mode: string`). Follow-up for 663/688: wire the FE via `inferenceStatusResponseSchema` or drop the codegen target. (2026-07-07)

### obs:unanchored-error-9 — Search results can surface a raw internal file with a GUID-shaped name and no title/summary/context
`kind: defect?` `anchor: none` `seen: 1` `first: 2026-07-07` `last: 2026-07-07`
- [ ] Search results can surface a raw internal file with a GUID-shaped name and no title/summary/context (e.g. `ba54e809-7654-4c3d-bd8a-ffa51f223c16.md`) — likely eval/test tooling residue leaking into the live index; needs investigation into why it's indexed and surfaced unlabeled — noticed during a UI design-comparison pass, not yet root-caused. (2026-07-07)

### obs:unanchored-general-68 — Pre-existing markdownlint MD031 violations (10x, fenced code blocks not surrounded by blank lines) i
`kind: environment?` `anchor: none` `seen: 1` `first: 2026-07-07` `last: 2026-07-07`
- [ ] Pre-existing markdownlint MD031 violations (10x, fenced code blocks not surrounded by blank lines) in .claude/skills/ci-triage/SKILL.md — `.claude/skills/ci-triage/SKILL.md:45-101`, predates the phase-3-observability-nightly deletion work (2026-07-07)

### obs:bench — jseval `bench-concurrency --output-dir` doubles as the corpus base_dir (passed to corpora.load) — po
`kind: defect?` `anchor: scripts/jseval/jseval/commands/bench.py` `seen: 1` `first: 2026-07-07` `last: 2026-07-07`
- [ ] jseval `bench-concurrency --output-dir` doubles as the corpus base_dir (passed to corpora.load) — pointing it anywhere but the datasets/ parent makes it fail with FileNotFoundError, and results get written into datasets/ — `scripts/jseval/jseval/commands/bench.py:62-63` (2026-07-07)

### obs:unanchored-general-69 — License-and-notices CI job failed 2x this session with different transient causes (Gradle-distributi
`kind: environment?` `anchor: none` `seen: 1` `first: 2026-07-08` `last: 2026-07-08`
- [ ] License-and-notices CI job failed 2x this session with different transient causes (Gradle-distribution download timeout, then Maven Central 403) before succeeding on 3rd attempt — checkLicense runs with --no-configuration-cache --no-parallel and no caching, worth investigating whether enabling caching would reduce exposure to network flakes — .github/workflows/ci.yml:92-120 (2026-07-08)

### obs:unanchored-general-72 — jseval readiness off-by-one hangs large MIRACL fetches: `corpus-fetch-miracl --n-docs 40000` materia
`kind: defect?` `anchor: none` `seen: 1` `first: 2026-07-08` `last: 2026-07-08`
- [ ] jseval readiness off-by-one hangs large MIRACL fetches: `corpus-fetch-miracl --n-docs 40000` materialized 40000 docs but readiness floor expected 40001 (one passage rejected as empty), so the run blocks on `indexed_doc_count_below_floor(40000/40001)` until timeout — floor should tolerate rejected/empty docs (found tempdoc 701 E4) (2026-07-08)

### obs:corpus-fetch — jseval corpus-fetch-clerc streams the full CLERC `collection.doc.tsv.gz` (GB-scale) line-by-line wit
`kind: defect?` `anchor: corpus_fetch.py` `seen: 4` `first: 2026-07-09` `last: 2026-07-12`
- [ ] jseval corpus-fetch-clerc streams the full CLERC `collection.doc.tsv.gz` (GB-scale) line-by-line with `timeout=None` and NO progress output (`corpus_fetch.py:204-216`), so a ~200-doc sample can silently run 15-40+ min with a 0-byte log — looks like a hang but isn't. Add progress logging / a size hint / a bounded timeout (found tempdoc 701 R2) (2026-07-09)
- [ ] Repeated multi-GB downloads per worktree: datasets (CLERC re-fetched per worktree since datasets/ is gitignored+per-tree and teardown deletes it) and ML scratch (per-worktree HF_HOME/venv) — models/ solved this via shared main-checkout resolution; corpus fetchers + HF_HOME need the same shared-cache pattern (recipe+signature-keyed, still gitignored/transient for licensing) — `scripts/jseval/jseval/corpus_fetch.py` (2026-07-10)
- [ ] jseval corpus-fetch-clerc can stall indefinitely on a slow HF/AWS connection with zero log output and no timeout (25+ min, 0-byte log, CPU idle, one ESTABLISHED conn) — needs a socket timeout/retry + progress lines — `scripts/jseval/jseval/corpus_fetch.py` (2026-07-11)
- [ ] 709 MIRACL/BEIR cache-routing ineffective: `apply_ir_datasets_home()` sets IR_DATASETS_HOME AFTER `import ir_datasets`, but ir_datasets bakes each dataset's cache path at import (registration), so downloads still land in ~/.ir_datasets not the shared root (verified: docs_handler path = C:\Users\Elias\.ir_datasets\miracl\...). Env must be set before import — `scripts/jseval/jseval/corpus_fetch.py:114` (2026-07-12)

### obs:runtime-config-ownership-matrix — runtime-config-ownership-matrix stale: verify-runtime-config-matrix fails on 6 env/sysprop pairs mis
`kind: environment?` `anchor: docs/reference/configuration/runtime-config-ownership-matrix.md` `seen: 2` `first: 2026-07-08` `last: 2026-07-10`
- [ ] runtime-config-ownership-matrix stale: verify-runtime-config-matrix fails on 6 env/sysprop pairs missing from the matrix (JUSTSEARCH_MODE, JUSTSEARCH_RERANK_JUDGE_ARBITRATION_*, JUSTSEARCH_RERANK_JUDGE_BLEND_*); pre-existing, unrelated to doc-drift edits; needs a matrix regen — `docs/reference/configuration/runtime-config-ownership-matrix.md` (2026-07-08)
- [ ] runtime-config-ownership-matrix.md is stale vs verify-runtime-config-matrix.mjs (exit 1): 6 missing env/sysprop pairs (JUSTSEARCH_MODE + 5x JUSTSEARCH_RERANK_JUDGE_*) — pre-existing drift unrelated to 706's two new index.ocr rows, found while verifying those (2026-07-10)

### obs:corpus-build — corpus-build (build_golden) does not clean the target golden corpus-dir before materializing — regen
`kind: environment?` `anchor: corpus_build.py` `seen: 1` `first: 2026-07-08` `last: 2026-07-08`
- [ ] corpus-build (build_golden) does not clean the target golden corpus-dir before materializing — regenerating a corpus with FEWER docs leaves stale files from the prior gen (observed: re-gen 3120->2736 left 5462 files in corpus-dir, silently polluting ingest). build_golden should wipe/recreate corpus-dir, or warn on pre-existing contents — `scripts/jseval/jseval/corpus_build.py build_golden` (2026-07-08)

### obs:unanchored-error-10 — eval-run logs commit machine context by default — the PR-117 scrub found 3,900+ 'C:\Users\<name>' oc
`kind: follow-up?` `anchor: none` `seen: 1` `first: 2026-07-10` `last: 2026-07-10`
- [ ] eval-run logs commit machine context by default — the PR-117 scrub found 3,900+ 'C:\Users\<name>' occurrences across 10 committed Inspect/agent-eval log JSONs (all escape depths incl. the Claude project-slug dash form); nothing structurally prevents recurrence when the next run's logs are committed. Candidate fix: a gitleaks.toml machine-path rule (pattern like Users[\/-]{1,8}(?!<user>)\w+) or a scrub step in the log-committing path — `scripts/jseval/624-run-*/logs/` precedent (2026-07-10)

### obs:extractionsandboxfactory — Reported (sidecar audit, unverified by me): in_process extraction sandbox reportedly uses one long-l
`kind: environment?` `anchor: modules/worker-services/.../extract/ExtractionSandboxFactory.java` `seen: 1` `first: 2026-07-10` `last: 2026-07-10`
- [ ] Reported (sidecar audit, unverified by me): in_process extraction sandbox reportedly uses one long-lived single-thread executor, so a single non-interruptible native hang (Tika/PDFBox/Tesseract) could wedge ALL future extraction until Worker restart; verify and consider executor replacement/watchdog — `modules/worker-services/.../extract/ExtractionSandboxFactory.java` / `TimeboxedContentExtractor.java` (2026-07-10)

### obs:unanchored-general-75 — Worker log shows ~40 'Loaded analyzers catalog from repo path' loads PER DOCUMENT during 686 realdoc
`kind: defect?` `anchor: none` `seen: 1` `first: 2026-07-10` `last: 2026-07-10`
- [ ] Worker log shows ~40 'Loaded analyzers catalog from repo path' loads PER DOCUMENT during 686 realdocs-v1 ingest (3,958 loads for 98 docs) — analyzers catalog appears un-cached on some per-doc/per-chunk path; possible indexing-throughput defect (or eval-mode-only artifact); verify against production wiring — `worker log 2026-07-10, tmp/headless-eval-data/logs/worker.log` (2026-07-10)

### obs:knowledgesearchcontroller-error — /api/knowledge/search sharp edges found while harvesting index stats: facets return silently EMPTY o
`kind: lesson?` `anchor: KnowledgeSearchController.java` `seen: 1` `first: 2026-07-10` `last: 2026-07-10`
- [ ] /api/knowledge/search sharp edges found while harvesting index stats: facets return silently EMPTY on this path (no error), 'lucene' querySyntax field-term queries cap totalHits/results at 100 and match-all (*:*) returns 0 — an agent can read false per-value counts (all values = 100) without an error. jseval lacks an index-field-distribution utility; folder-files + projection is the workaround — `KnowledgeSearchController.java` / jseval feature gap (2026-07-10)

### obs:unanchored-missing-7 — STALE observation correction: obs:vdu-pdf-fixtures-local-env claims eng.traineddata missing — on 202
`kind: defect?` `anchor: none` `seen: 1` `first: 2026-07-10` `last: 2026-07-10`
- [ ] STALE observation correction: obs:vdu-pdf-fixtures-local-env claims eng.traineddata missing — on 2026-07-10 the file EXISTED at F:/scoop/persist/tesseract/tessdata/ (was overwritten with tessdata_fast variant during 686 setup, disclosed in 686); tesseract verified working. Retire or re-scope the observation. (2026-07-10)

### obs:writepathops — STRUCTURAL TRAP confirmed live: KnnFloatVectorField (VECTOR) is non-stored and silently DESTROYED by
`kind: follow-up?` `anchor: modules/adapters-lucene/src/main/java/io/justsearch/adapters/lucene/runtime/WritePathOps.java` `seen: 1` `first: 2026-07-10` `last: 2026-07-10`
- [ ] STRUCTURAL TRAP confirmed live: KnnFloatVectorField (VECTOR) is non-stored and silently DESTROYED by any subsequent readModifyWrite; chunk docs get re-queued (WritePathOps.java:471) but PARENT docs do not — any new enrichment pass that writes VECTOR in its own RMW before another stage's RMW loses the vector with status still COMPLETED (no error, no signal). The combined pass's one-RMW bundling is the only thing upholding this undeclared invariant. Candidate for 710 (invariant should be declared/enforced or vectors preserved in RMW) — `modules/adapters-lucene/src/main/java/io/justsearch/adapters/lucene/runtime/WritePathOps.java:471` (2026-07-10)

### obs:bertnerinference — NER tokenizer construction lacked explicit truncation=false/padding=false (unlike SPLADE/embed's tok
`kind: follow-up?` `anchor: modules/worker-core/src/main/java/io/justsearch/indexerworker/ner/BertNerInference.java` `seen: 1` `first: 2026-07-10` `last: 2026-07-10`
- [ ] NER tokenizer construction lacked explicit truncation=false/padding=false (unlike SPLADE/embed's tokenizer setup) — latent because inferBatch only ever called single-text tokenizer.encode() before tempdoc 710 Move 3 introduced batchEncode(); fixed as part of Move 3, but the underlying DJL batchEncode-vs-encode default-padding asymmetry is worth a general note for any future tokenizer construction site — `modules/worker-core/src/main/java/io/justsearch/indexerworker/ner/BertNerInference.java:108` (2026-07-10)

### obs:unanchored-general-77 — main checkout shows models/*.onnx (gte, ner, reranker, splade) as untracked (??) despite .gitattribu
`kind: defect?` `anchor: none` `seen: 1` `first: 2026-07-11` `last: 2026-07-11`
- [ ] main checkout shows models/*.onnx (gte, ner, reranker, splade) as untracked (??) despite .gitattributes LFS patterns and merged 691 PRs — git lfs ls-files fails 'bad revision' for them; possibly never git-added on main. Needs LFS-state triage — `models/` (2026-07-11)

### obs:modelcapabilityresolver — Pre-existing: Jackson tools.jackson.databind JsonNode.isTextual()/asText() are deprecated in the ver
`kind: environment?` `anchor: modules/ort-common/src/main/java/io/justsearch/ort/ModelCapabilityResolver.java` `seen: 1` `first: 2026-07-11` `last: 2026-07-11`
- [ ] Pre-existing: Jackson tools.jackson.databind JsonNode.isTextual()/asText() are deprecated in the version in use; used throughout ModelCapabilityResolver.resolvePrefixes/resolveLabelMapping — `modules/ort-common/src/main/java/io/justsearch/ort/ModelCapabilityResolver.java` (surfaced under -Xlint:deprecation, out of scope for tempdoc 711 Item 3) (2026-07-11)

### obs:unanchored-general-78 — Recurring orchestration failure mode (2x this session): a stopped/idle subagent is NOT re-woken when
`kind: lesson?` `anchor: none` `seen: 1` `first: 2026-07-11` `last: 2026-07-11`
- [ ] Recurring orchestration failure mode (2x this session): a stopped/idle subagent is NOT re-woken when its own backgrounded shell jobs complete — completion notifications are lost if they fire while the agent is between turns; orchestrator had to detect completion via on-disk evidence (result-file mtimes, GPU idle) and manually SendMessage-resume both times. Candidate agent-lessons.md entry: long-running background jobs inside subagents need orchestrator-side completion polling, or the subagent should poll rather than background-and-wait — platform behavior, not repo bug (2026-07-11)

### obs:documentpane — Stale comments in DocumentPane.ts reference retired InspectorPane.ts (e.g. line 10, line 60 'Mirrors
`kind: defect?` `anchor: modules/ui-web/src/shell-v0/components/documentPane/DocumentPane.ts` `seen: 1` `first: 2026-07-11` `last: 2026-07-11`
- [ ] Stale comments in DocumentPane.ts reference retired InspectorPane.ts (e.g. line 10, line 60 'Mirrors InspectorPane's local VisualExtractionEvidence shape') — that component was removed (only referenced in UnifiedChatView.test.ts's 'retired jf-inspector-pane never appears' regression test); the comments should point at the actual current pattern origin instead — `modules/ui-web/src/shell-v0/components/documentPane/DocumentPane.ts:10,60` (2026-07-11)

### obs:supervisiondecision — SupervisionDecision has 3 pre-existing surviving ConditionalsBoundaryMutator mutants (backoffMs L99/
`kind: environment?` `anchor: modules/app-services/src/main/java/io/justsearch/app/services/worker/SupervisionDecision.java` `seen: 1` `first: 2026-07-11` `last: 2026-07-11`
- [ ] SupervisionDecision has 3 pre-existing surviving ConditionalsBoundaryMutator mutants (backoffMs L99/L104, decide L80) — worker-supervision seam already reports 88% strength in report-pit-strength.mjs, unrelated to tempdoc 677 work — `modules/app-services/src/main/java/io/justsearch/app/services/worker/SupervisionDecision.java` (2026-07-11)

### obs:ui-a11y-baseline-v1 — chat/grounding-badge surface missing from governance/ui-a11y-baseline.v1.json — ui-a11y-gate structu
`kind: defect?` `anchor: ui-a11y-baseline.v1.json` `seen: 1` `first: 2026-07-12` `last: 2026-07-12`
- [ ] chat/grounding-badge surface missing from governance/ui-a11y-baseline.v1.json — ui-a11y-gate structurally can't exercise the grounding badge (no chat entry). Extend baseline surface list to cover chat. Surfaced by 720 per-sentence UX audit. (2026-07-12)

### obs:llamaserveropscrashtelemetrytest — FLAKY test: LlamaServerOpsCrashTelemetryTest.java:111 ConcurrentModificationException failed main CI
`kind: environment?` `anchor: LlamaServerOpsCrashTelemetryTest` `seen: 2` `first: 2026-07-12` `last: 2026-07-12`
- [ ] FLAKY test: LlamaServerOpsCrashTelemetryTest.java:111 ConcurrentModificationException failed main CI run 29210412900 (merge 491412d6) but passed on PR #171 with identical content (app-inference module, unrelated to the FE-only per-sentence change). Concurrency race in the crash-telemetry test; candidate for a defensive copy / synchronized collection at line 111 or a stress-test. (2026-07-12)
- [ ] LlamaServerOpsCrashTelemetryTest ('reaching MAX_CRASHES fires goOfflineFromMaxCrashes') FAILED on main-push CI run 29152742519 (2026-07-11, docs-only observations-fold diff, zero app-inference overlap; same lane green on PR #142 minutes earlier) — crash-telemetry timing-flake candidate; if it recurs, needs the flaky-IPC state-polling treatment — `modules/app-inference LlamaServerOpsCrashTelemetryTest` (recovered from dead-session worktree 691-takeover) (2026-07-12)

### obs:combinedenrichmentbackfillops — Comment over-attributes Lucene #15068 (an MMapDirectory mmap resource leak, not data loss) as a vect
`kind: defect?` `anchor: modules/worker-services/src/main/java/io/justsearch/indexerworker/loop/ops/CombinedEnrichmentBackfillOps.java` `seen: 4` `first: 2026-07-11` `last: 2026-07-11`
- [ ] Comment over-attributes Lucene #15068 (an MMapDirectory mmap resource leak, not data loss) as a vector-loss / commit-cadence rationale — `modules/worker-services/src/main/java/io/justsearch/indexerworker/loop/ops/CombinedEnrichmentBackfillOps.java:627-630` (found during 717 research; behavior fine, comment only) (2026-07-11)
- [ ] Combined pass parent lane stamps parent-status markers (EMBEDDING_STATUS/NER_STATUS COMPLETED) onto chunk docs picked up via the splade-status query — chunk docs end up carrying parent lifecycle fields they never own — `modules/worker-services/src/main/java/io/justsearch/indexerworker/loop/ops/CombinedEnrichmentBackfillOps.java:330` (2026-07-11)
- [ ] A chunk doc pending both chunk_embedding and splade sits in BOTH combined-pass caches (parentIdCache via splade-status query, chunkIdCache via chunk-embedding query) and can be popped twice into one batch — double-added to embedDocIds, double-embedded — `modules/worker-services/src/main/java/io/justsearch/indexerworker/loop/ops/CombinedEnrichmentBackfillOps.java:199-260` (2026-07-11)
- [ ] INTERMITTENT fresh-build chunk-death anomaly (chartered as tempdoc 717): a fresh --clean ingest SOMETIMES produces an index with the entire chunk_merge leg absent (vector nDCG 0.34 not 0.62), silently. Observed degenerate: 713 control run, 712 A/B-1 OFF arm. Observed healthy: 713 §M-5 probe, 712 A/B-2 both arms (first try). Not deterministic; refuted as "always dead," confirmed as occasional — `modules/worker-services/.../CombinedEnrichmentBackfillOps.java:372` (2026-07-11)

### obs:indexcountops — IndexCountOps.computeCorpusProfile() iterates weight.scorer(leaf) directly (raw DocIdSetIterator loo
`kind: defect?` `anchor: modules/adapters-lucene/src/main/java/io/justsearch/adapters/lucene/runtime/IndexCountOps.java` `seen: 1` `first: 2026-07-11` `last: 2026-07-11`
- [ ] IndexCountOps.computeCorpusProfile() iterates weight.scorer(leaf) directly (raw DocIdSetIterator loop) without checking leaf.reader().getLiveDocs() — bypasses IndexSearcher.searchLeaf's live-docs filtering (confirmed via javap: searchLeaf calls LeafReader.getLiveDocs() before scoring, but computeCorpusProfile's manual scorer iteration never does), so parentCount/token-bucket stats may include deleted-but-unmerged docs — `modules/adapters-lucene/src/main/java/io/justsearch/adapters/lucene/runtime/IndexCountOps.java:344-365` (2026-07-11)

### obs:indexingdocumentops — parent_token_count feedback/telemetry has no exact-vs-estimated provenance flag — after tempdoc 717
`kind: defect?` `anchor: modules/worker-services/src/main/java/io/justsearch/indexerworker/loop/ops/IndexingDocumentOps.java` `seen: 1` `first: 2026-07-11` `last: 2026-07-11`
- [ ] parent_token_count feedback/telemetry has no exact-vs-estimated provenance flag — after tempdoc 717 fix A it may hold a char-based estimate (SPLADE-cold-start fallback) indistinguishable from an exact SPLADE count for offline distribution analysis — `modules/worker-services/src/main/java/io/justsearch/indexerworker/loop/ops/IndexingDocumentOps.java:391-419` (717 review Finding 5; low blast radius, feedback pipeline only) (2026-07-11)

### obs:unanchored-general-8 — Reranker is load-bearing / first-stage fusion squanders a strong dense leg: on battlefield-en-scale-
`kind: defect?` `anchor: none` `seen: 1` `first: 2026-07-12` `last: 2026-07-12`
- [ ] Reranker is load-bearing / first-stage fusion squanders a strong dense leg: on battlefield-en-scale-v1 (2026-07-12, current main) client 'full' fusion (BM25+dense+SPLADE RRF, no CE) scored nDCG@10=0.279 — FAR below vector-alone 0.722 and server-hybrid-with-CE 0.697. The cross-encoder does all the recovery (0.279->0.697); first-stage fusion weighting buries the strong dense hit. Fragility worth an owner — relevant to 636 (fusion-order)/712/713. Artifacts: scripts/jseval/tmp/eval-results/*battlefield-en-scale-v1* (2026-07-12)

### obs:unanchored-general-9 — Retrieval on battlefield-en-scale-v1 (2736 docs) is now healthy: agent-hybrid nDCG@10=0.697 on curre
`kind: defect?` `anchor: none` `seen: 1` `first: 2026-07-12` `last: 2026-07-12`
- [ ] Retrieval on battlefield-en-scale-v1 (2736 docs) is now healthy: agent-hybrid nDCG@10=0.697 on current main, up from 0.163 in the 2026-07-08 PR#117 run — the 717 chunk-death fix + 713 dense-authority consolidation fixed the dense leg (vector 0.09->0.722); chunk_completeness verdict=ok (30099 chunks). REFUTES the prior '624 scale corpus is unfixably out-of-band' conclusion; unblocks a meaningful 624 agent-utility ACCURACY measurement (the with-tool arm now retrieves well). (2026-07-12)

### obs:unanchored-general-12 — 624 agent-utility A/B pilot on fixed retrieval (2026-07-12, battlefield-en-scale-v1, haiku, n=12 pai
`kind: defect?` `anchor: none` `seen: 1` `first: 2026-07-12` `last: 2026-07-12`
- [ ] 624 agent-utility A/B pilot on fixed retrieval (2026-07-12, battlefield-en-scale-v1, haiku, n=12 paired): B(with JustSearch) uses -25.7% mean / -28.9% median context tokens vs A(grep), 95% CI [-13%,-41%] excludes 0 (robust); B acc 100% vs A 87.5% but McNemar p=1.0 (both near ceiling -> no headroom, the recurring problem 707's pillar-1 corpus targets); adoption 7.1% (grep suffices on this easy subset -> rational non-adoption). Token-efficiency robust; accuracy underpowered until grep genuinely struggles. Artifacts: scripts/jseval/624-pilot-2026-07-12/ (2026-07-12)

### obs:encoder-drift — encoder_drift._write_baseline has zero call sites (baseline capture moved to calibrate-drift-baselin
`kind: follow-up?` `anchor: scripts/jseval/jseval/projections/encoder_drift.py` `seen: 1` `first: 2026-07-11` `last: 2026-07-11`
- [ ] encoder_drift._write_baseline has zero call sites (baseline capture moved to calibrate-drift-baseline per C-1.8.1) — dead code candidate — `scripts/jseval/jseval/projections/encoder_drift.py:229` (2026-07-11)

### obs:chunkdocumentwriter — tempdoc 718: expose ChunkDocumentWriter.CHUNK_THRESHOLD_CHARS (modules/worker-services/src/main/java
`kind: defect?` `anchor: ChunkDocumentWriter.java` `seen: 1` `first: 2026-07-11` `last: 2026-07-11`
- [ ] tempdoc 718: expose ChunkDocumentWriter.CHUNK_THRESHOLD_CHARS (modules/worker-services/src/main/java/io/justsearch/indexerworker/rag/ChunkDocumentWriter.java:28) via /api/status or a config surface so jseval's offline chunk-completeness oracle (scripts/jseval/jseval/chunk_completeness.py CHUNK_THRESHOLD_CHARS) reads it instead of mirroring it -- a dual-source-of-truth that will silently drift if the Java constant ever changes. (2026-07-11)

### obs:pdfocrenginetest — PdfOcrEngineTest.interruptDestroysAllRegisteredChildren flaked on main-push CI AGAIN (run 2915593054
`kind: environment?` `anchor: PdfOcrEngineTest` `seen: 1` `first: 2026-07-12` `last: 2026-07-12`
- [ ] PdfOcrEngineTest.interruptDestroysAllRegisteredChildren flaked on main-push CI AGAIN (run 29155930543, 2026-07-11, PR #145 push, zero OCR overlap; second main-push occurrence after run 29129172631 on 2026-07-10) — recurrence condition fired; needs 706-style state-polling hardening, 706-owner lane — `modules/worker-services PdfOcrEngineTest` (recovered from dead-session worktree 691-takeover) (2026-07-12)

### obs:dataset-cache — 709 resume gap: an interrupted CLERC raw fetch leaves an orphaned `.tmp-*` staging dir under the cac
`kind: defect?` `anchor: scripts/jseval/jseval/dataset_cache.py` `seen: 2` `first: 2026-07-12` `last: 2026-07-13`
- [ ] 709 resume gap: an interrupted CLERC raw fetch leaves an orphaned `.tmp-*` staging dir under the cache root (observed: ~6.3GB collection.doc.tsv.gz at scripts/jseval/tmp/dataset-fetch-cache/clerc-raw/.<key>.tmp-*); `store()` neither resumes nor GCs it, so the next fetch re-downloads the full 6.7GB — `scripts/jseval/jseval/dataset_cache.py:150` (2026-07-12)
- [ ] 709/173 interaction: pinning _CLERC_REVISION changed the clerc-raw dataset-cache key, orphaning the completed 7.7GB resolve/main entry (7df857..) — next fetch re-downloads and now hits HF anonymous-download 403 (AccessDenied at CDN hop, resolver quota fine); migrated entry to pinned key 0f0aba86.. via hardlinks+signature.json this session (content signature a23d916b.. unchanged, HF API confirms main sha == pinned rev). Residual: no cache-key migration/GC story on revision bumps, and the orphaned 6.3GB .tmp-* staging dir from the 07-11 dead fetch still leaks — `scripts/jseval/jseval/dataset_cache.py:150` (2026-07-13)

### obs:test-correction-probe — Pre-existing: scripts/jseval/tests/test_correction_probe.py default-manifest tests fail on main beca
`kind: environment?` `anchor: test_correction_probe.py` `seen: 2` `first: 2026-07-13` `last: 2026-07-16`
- [ ] Pre-existing: scripts/jseval/tests/test_correction_probe.py default-manifest tests fail on main because jseval/data/correction-eval-queries.v1.json was never committed (absent since v0.1.0) — full pytest suite is 2-red on a clean main checkout. Noted during 719 takeover; not caused by 719 branch. (2026-07-13)
- [ ] tests/test_correction_probe.py::TestLoadManifest fails since v0.1.0: jseval/data/correction-eval-queries.v1.json was never committed (no jseval/data dir in history) — the full jseval pytest suite has never been green on the public repo; either commit the data file or skip-with-reason — `scripts/jseval/tests/test_correction_probe.py` (2026-07-16)

### obs:unanchored-general-14 — Empty dir .claude/worktrees/adoption-legibility still not removable (EBUSY, process-held) as of 2026
`kind: defect?` `anchor: none` `seen: 1` `first: 2026-07-14` `last: 2026-07-14`
- [ ] Empty dir .claude/worktrees/adoption-legibility still not removable (EBUSY, process-held) as of 2026-07-14 — rmdir+PowerShell both fail; retry after reboot or when the holding process exits — `.claude/worktrees/adoption-legibility` (2026-07-14)

### obs:llamaserveropscrashtelemetrytest-flake — Flaky test: LlamaServerOpsCrashTelemetryTest 'Brain give-up: reaching MAX_CRASHES fires goOfflineFro
`kind: environment?` `anchor: modules/app-inference/src/test/java/.../LlamaServerOpsCrashTelemetryTest.java` `seen: 2` `first: 2026-07-14` `last: 2026-07-15`
- [ ] Flaky test: LlamaServerOpsCrashTelemetryTest 'Brain give-up: reaching MAX_CRASHES fires goOfflineFromMaxCrashes' threw java.util.ConcurrentModificationException at line 111 on CI (PR #179 run 29311644690, unrelated Python-only diff) — test-internal race, likely iterating telemetry while crash loop appends — `modules/app-inference/src/test/java/.../LlamaServerOpsCrashTelemetryTest.java:111` (2026-07-14)
- [ ] FLAKY (4th instance): LlamaServerOpsCrashTelemetryTest 'Brain give-up: reaching MAX_CRASHES fires goOfflineFromMaxCrashes' threw ConcurrentModificationException at line 111 on PR #200 CI (run 29416058201, agent-tooling diff = zero Java — hooks/rules/skills only). 221 tests, 1 failed. Recurring on unrelated diffs since 2026-07-11 (docs-only, Python-only, now hooks-only) — the test-internal race is real and re-run-masked every time; it has now cost 4 sessions a red-CI investigation. Worth the defensive-copy/synchronized-collection fix at line 111 — `modules/app-inference/src/test/java/io/justsearch/app/inference/LlamaServerOpsCrashTelemetryTest.java:111` (2026-07-15)

### obs:registryentry — Pre-existing: CI 'Build (no model blobs)' emits a MissingOverride annotation for modules/app-agent-a
`kind: environment?` `anchor: RegistryEntry.java` `seen: 2` `first: 2026-07-13` `last: 2026-07-16`
- [ ] Pre-existing: CI 'Build (no model blobs)' emits a MissingOverride annotation for modules/app-agent-api/src/main/java/io/justsearch/agent/api/registry/RegistryEntry.java#41 (id implements method in Declaration; expected @Override) — non-fatal, surfaces on every main push run. Noticed during 719 publish; no Java in that diff. (2026-07-13)
- [ ] CI annotation on green main run 29492063792: [MissingOverride] RegistryEntry.id expected @Override — `modules/app-agent-api/src/main/java/io/justsearch/agent/api/registry/RegistryEntry.java:41` (pre-existing, not from PR #209) (2026-07-16)

### obs:unanchored-general-19 — Dev stack (dev-runner) backend died silently mid-session ~2026-07-14 00:20 during light MCP-only loa
`kind: defect?` `anchor: none` `seen: 1` `first: 2026-07-14` `last: 2026-07-14`
- [ ] Dev stack (dev-runner) backend died silently mid-session ~2026-07-14 00:20 during light MCP-only load (a few SDK agent sessions hitting /mcp; run 561cb894, port 62520) — quick_health running:false, backend logs empty of errors. Second start ran fine. Unexplained; watch for recurrence under agent-session load. Noted during 725 derisk. (2026-07-14)

### obs:commitment-v1 — 707/719 cross-platform replay gotcha: committed 707 commitment.v1.json digests hash CRLF build-time
`kind: lesson?` `anchor: commitment.v1.json` `seen: 1` `first: 2026-07-14` `last: 2026-07-14`
- [ ] 707/719 cross-platform replay gotcha: committed 707 commitment.v1.json digests hash CRLF build-time bytes, but git text=auto stores LF — a fresh LF checkout's fabricated-queries/meta/recipe files hash differently than their own manifests; rebuilding via corpus-query-stratum-build + corpus-inject-real reproduces all committed signatures exactly (proven 2026-07-14, en-legal-clerc 1k members). Outsider replay docs should say regenerate-then-verify, not hash-the-checkout. Discovered during 725 A/B corpus prep. (2026-07-14)

### obs:unanchored-general-20 — Main checkout observed on branch mcpb-packaging (not main) with in-flight uncommitted work during 72
`kind: defect?` `anchor: none` `seen: 1` `first: 2026-07-14` `last: 2026-07-14`
- [ ] Main checkout observed on branch mcpb-packaging (not main) with in-flight uncommitted work during 725 publish (2026-07-14) — branch-safety rule 1 says the main checkout stays on main; if deliberate (owner packaging session), fine, but parallel agents' merge workflows assume main and had to route around it. Not touched. (2026-07-14)

### obs:agent-utility-inspect-gate-red — 725 A/B smoke live defect: offered-vs-declared MCP tool-name assertion is not condition-gated — cond
`kind: defect?` `anchor: scripts/jseval/jseval/agent_utility_inspect.py` `seen: 1` `first: 2026-07-14` `last: 2026-07-14`
- [ ] 725 A/B smoke live defect: offered-vs-declared MCP tool-name assertion is not condition-gated — condition-A cells (mcp_servers=[], 0 tools) are marked errored against the non-empty declared canonical surface, voiding the baseline arm and emptying measured{} — `scripts/jseval/jseval/agent_utility_inspect.py:605` (2026-07-14)

### obs:unifiedchatview — "New chat" button is state-gated (thread.length > 0 && !agentMode) and doesn't render on a fresh/emp
`kind: defect?` `anchor: modules/ui-web/src/shell-v0/views/UnifiedChatView.ts` `seen: 1` `first: 2026-07-14` `last: 2026-07-14`
- [ ] "New chat" button is state-gated (thread.length > 0 && !agentMode) and doesn't render on a fresh/empty chat surface, with no other entry point (keyboard-shortcuts doc lists none) — found via tempdoc 727 friction mining — `modules/ui-web/src/shell-v0/views/UnifiedChatView.ts:2114-2117` (2026-07-14)

### obs:unanchored-flake — Browser-automation viewport-resize flakiness (innerWidth/outerWidth mismatch, resize not taking effe
`kind: lesson?` `anchor: none` `seen: 1` `first: 2026-07-14` `last: 2026-07-14`
- [ ] Browser-automation viewport-resize flakiness (innerWidth/outerWidth mismatch, resize not taking effect) seen in 2 same-day claude-in-chrome UI-capture sessions — unconfirmed whether claude-in-chrome-side (multi-tab contention, matches agent-lessons.md too-many-tabs lesson) or a JustSearch-side bug; needs a targeted repro — found via tempdoc 727 friction mining (2026-07-14)

### obs:unanchored-general-23 — Windows/git-bash 'system cannot find the path specified' recurring across many test names inside a C
`kind: defect?` `anchor: none` `seen: 1` `first: 2026-07-14` `last: 2026-07-14`
- [ ] Windows/git-bash 'system cannot find the path specified' recurring across many test names inside a CI coverage-check loop — producing script not identified by static search (checked check-*coverage*.mjs / check-*tier*.mjs, none use spawnSync/execFileSync per-test-name); needs live repro — found via tempdoc 727 friction mining (2026-07-14)

### obs:check-always-loaded-budget — always-loaded-budget gate: .claude/rules/branch-safety.md and tier-register.md were ALREADY over the
`kind: environment?` `anchor: check-always-loaded-budget` `seen: 1` `first: 2026-07-14` `last: 2026-07-14`
- [ ] always-loaded-budget gate: .claude/rules/branch-safety.md and tier-register.md were ALREADY over their byte ceiling before tempdoc 727's session touched them (pre-existing debt from earlier tempdocs' additions, e.g. rows 38/39 + docs-ride-along section) — this session's own additions (2 new tier-register rows + 1 branch-safety.md section, both required by the prose-tier-register gate) made the pre-existing overage somewhat larger. Tension between two of this repo's own gates: prose-tier-register requires new anchored rules to be registered in tier-register.md; always-loaded-budget caps that same file's growth and never ratchets the ceiling up. Needs a real trim/reconciliation pass by whoever owns these files' content, not a cosmetic shrink of just the newest 2 rows out of step with the other ~40 — `node scripts/ci/check-always-loaded-budget.mjs` (2026-07-14)

### obs:unanchored-gate-red — always-loaded-budget gate now also fails on CLAUDE.md (24260/22656 B) and .claude/rules/hooks-refere
`kind: environment?` `anchor: none` `seen: 1` `first: 2026-07-14` `last: 2026-07-14`
- [ ] always-loaded-budget gate now also fails on CLAUDE.md (24260/22656 B) and .claude/rules/hooks-reference.md (2839/2740 B), in addition to the already-logged branch-safety.md/tier-register.md overages — confirmed via `git diff origin/main` that neither file was touched by tempdoc 727's worktree, so this is separate pre-existing drift from other sessions landing on main, not caused by this work. See docs/tempdocs/727-session-transcript-friction-mining.md Fit-review closure section. (2026-07-14)

### obs:unanchored-drift-3 — main checkout (F:\justsearch-public) is on branch 'mcpb-packaging' (a stale, unpushed feature branch
`kind: environment?` `anchor: none` `seen: 1` `first: 2026-07-14` `last: 2026-07-14`
- [ ] main checkout (F:\justsearch-public) is on branch 'mcpb-packaging' (a stale, unpushed feature branch, tip commit dated 2026-07-14 02:31, well before this session started) instead of 'main' — pre-existing anomaly, not caused by this session. bash-guard blocks 'git checkout <branch>' unconditionally in the main worktree, so this can't be fixed from inside a session without either explicit user action or a deliberate hook bypass. Flagged for the repo owner to decide whether/how to fix. (2026-07-14)

### obs:devmode — the ts-any governance gate (gates/ts-any/baseline.txt, tempdoc 530 sec2.5) is red on origin/main's a
`kind: environment?` `anchor: devMode.ts` `seen: 1` `first: 2026-07-14` `last: 2026-07-14`
- [ ] the ts-any governance gate (gates/ts-any/baseline.txt, tempdoc 530 sec2.5) is red on origin/main's actual HEAD (401c1ae) independent of any merge from today's session: modules/ui-web/src/api/devMode.ts and modules/ui-web/src/shell-v0/streaming/MultiplexedStream.ts each have an unregistered '(import.meta as any).env?.DEV' cast, present since commit daa74bd (tempdoc 683 PR #77) and a9694aa (tempdoc 662 PR #22) respectively -- neither was ever added to the baseline file, which has been untouched since the initial-release seed commit (29579e5). Confirmed this gate is NOT wired into the public .github/workflows/ci.yml (no match for 'governance'/'ts-any'), so main's actual public CI is unaffected and shows success (gh run list --branch main) -- this is a local/manual-governance-only finding, not a CI-red condition. Found via tempdoc 727's publish verification pass; not fixed here (unrelated pre-existing debt, out of this tempdoc's scope). (2026-07-14)

### obs:unanchored-general-28 — Embedding fingerprint apparently not persisted across worker restarts: an index healthy in-session (
`kind: defect?` `anchor: none` `seen: 1` `first: 2026-07-14` `last: 2026-07-14`
- [ ] Embedding fingerprint apparently not persisted across worker restarts: an index healthy in-session (compat REBUILDING->complete, CHUNK_HYBRID serving) re-flags BLOCKED_LEGACY/LEGACY_INDEX_NO_FINGERPRINT on plain dev-stack restart, blocking the dense leg until full re-embed; reproduced twice during 725 live validation (worktree .dev-data and main .dev-data) — `modules/indexer-worker` compat/fingerprint persistence (2026-07-14)

### obs:unanchored-general-40 — Second unexplained dev-stack death under MCP-only agent load: backend died at/near the end of a 40-c
`kind: defect?` `anchor: none` `seen: 1` `first: 2026-07-14` `last: 2026-07-14`
- [ ] Second unexplained dev-stack death under MCP-only agent load: backend died at/near the end of a 40-cell utility campaign (all cells completed; death caught by the post-eval surface re-capture); first instance was logged during 725 derisk. Pattern: sustained MCP tool-call load — `dev-runner backend lifecycle` (2026-07-14)

### obs:unanchored-error-2 — app-services:integrationTest crashes the forked test-worker JVM with java.io.EOFException before any
`kind: environment?` `anchor: none` `seen: 1` `first: 2026-07-14` `last: 2026-07-14`
- [ ] app-services:integrationTest crashes the forked test-worker JVM with java.io.EOFException before any test runs (no test-results XML updated) — unrelated to tempdoc 730 dev-runner/build.gradle.kts changes; likely environment/resource contention (GPU/native lib) on this machine — `modules/app-services/build.gradle.kts` (integrationTest task) (2026-07-14)

### obs:unanchored-red-test — Local Gradle build cache (F:/caches) had a stale/corrupted entry for :modules:ipc-common:compileJava
`kind: defect?` `anchor: none` `seen: 1` `first: 2026-07-14` `last: 2026-07-14`
- [ ] Local Gradle build cache (F:/caches) had a stale/corrupted entry for :modules:ipc-common:compileJava missing RequestIdClientInterceptor.class, causing cascading app-services compile failures and NoClassDefFoundError test failures unrelated to source changes — resolved locally via --no-build-cache; not caused by tempdoc 732 items 1/2 work. (2026-07-14)

### obs:unanchored-flake-2 — app-services:integrationTest fails reproducibly (isolated + full-build) with Gradle's own internal t
`kind: environment?` `anchor: none` `seen: 1` `first: 2026-07-14` `last: 2026-07-14`
- [ ] app-services:integrationTest fails reproducibly (isolated + full-build) with Gradle's own internal test-results binary I/O (java.io.EOFException / NoSuchFileException on in-progress-results-generic.bin), unrelated to module content — module never touched by tempdoc 731 I2 work; looks like environment/build-output infra flakiness on this machine, not a code defect — `modules/app-services/build.gradle.kts` (2026-07-14)

### obs:retrievecontextparams — Worktree .claude/worktrees/725-response-legibility had concurrent agent-session activity during this
`kind: environment?` `anchor: RetrieveContextParams.java` `seen: 1` `first: 2026-07-14` `last: 2026-07-14`
- [ ] Worktree .claude/worktrees/725-response-legibility had concurrent agent-session activity during this task (~24 unrelated files modified: RetrieveContextParams.java, RemoteDocumentService.java, SqliteJobQueue.java, mcp/*.java, dev-runner.cjs, jseval/**, new tempdocs 736/731/732/733) — violates branch-safety.md 'never share a worktree'. Caused transient Gradle build-output corruption (ipc-common missing proto classes, app-services:integrationTest Gradle-internal SerializableTestResultStore EOFException/NoSuchFileException while writing its own HTML report) unrelated to tempdoc 730 code — resolved by clearing stale build/classes and build/test-results dirs and retrying — `.claude/worktrees/725-response-legibility` (shared worktree, 2026-07-14) (2026-07-14)

### obs:mcptoolsurface — resources/read path still uses raw multi-entry Map.of (salted wire key order) at McpToolSurface.java
`kind: follow-up?` `anchor: McpToolSurface.java` `seen: 2` `first: 2026-07-14` `last: 2026-07-14`
- [ ] resources/read path still uses raw multi-entry Map.of (salted wire key order) at McpToolSurface.java:1220,1233,1250,1269,1442 + the {type,text} content blocks — same JVM-salt class as the fixed resources/list; follow-up candidate — `McpToolSurface.java:1220` (2026-07-14)
- [ ] Pre-existing latent: corpus newlines in preview/answer text can mimic column-0 response furniture (Found N results / Hints lines); mitigation sketch: indent continuation lines; LOW-MODERATE, unchanged by 732 — `McpToolSurface.java:738-785` (2026-07-14)

### obs:exposure-contrast — Concurrent Chain A activity in the shared worktree 725-response-legibility swept my (Chain B, tempdo
`kind: follow-up?` `anchor: exposure_contrast.py` `seen: 1` `first: 2026-07-14` `last: 2026-07-14`
- [ ] Concurrent Chain A activity in the shared worktree 725-response-legibility swept my (Chain B, tempdoc 736) uncommitted exposure_contrast.py/utility_comparison.py/test edits into commit a86d05b, whose message is entirely about unrelated 736-733 tempdoc docs — a git add -A/commit boundary violation of branch-safety.md's 'stage your own files explicitly' guidance, not caused by me. Code content verified correct (full pytest green) regardless of which commit it lives in, but commit-message attribution is now misleading and the orchestrator should consider recomposing history before merge — `git show a86d05b --stat` (repo root). (2026-07-14)

### obs:knowledgeserver — tempdoc 730 A4: KnowledgeServer.maybeAutoStartEmbeddingRebuildAllPendingBestEffort's chunk-exclusion
`kind: environment?` `anchor: modules/indexer-worker/src/main/java/io/justsearch/indexerworker/server/KnowledgeServer.java` `seen: 1` `first: 2026-07-14` `last: 2026-07-14`
- [ ] tempdoc 730 A4: KnowledgeServer.maybeAutoStartEmbeddingRebuildAllPendingBestEffort's chunk-exclusion doc-count math (totalDocs - chunkDocs) is unit-untested at the KnowledgeServer level (only exercised indirectly via ECC-level tests that don't use chunks) — pre-existing gap, not touched by this increment — `modules/indexer-worker/src/main/java/io/justsearch/indexerworker/server/KnowledgeServer.java:1295-1300` (2026-07-14)

### obs:unanchored-general-44 — Entity-filter cluster expansion turns a single-doc planted code (entity_persons=Cavby8) into 41 hits
`kind: defect?` `anchor: none` `seen: 1` `first: 2026-07-14` `last: 2026-07-14`
- [ ] Entity-filter cluster expansion turns a single-doc planted code (entity_persons=Cavby8) into 41 hits — disambiguation-cluster over-expansion on short synthetic codes; precision concern for entity-filtered retrieval — `entity facet filter expansion` (2026-07-14)

### obs:dev-runner-drift-2 — Backend-death root cause substantially resolved: deaths coincide with other sessions' lease takeover
`kind: follow-up?` `anchor: dev-runner.cjs` `seen: 1` `first: 2026-07-14` `last: 2026-07-14`
- [ ] Backend-death root cause substantially resolved: deaths coincide with other sessions' lease takeovers (30s lease not renewed during long-running probes/campaigns that make no MCP dev-tool calls; observed live — session f3e41644 reclaimed mid-probe, stale_reclaim written by ITS runner). Issue 6 reframes from product crash to contention semantics; candidate fix: lease renewal heartbeat during utility-run/long ops — `scripts/dev/dev-runner.cjs lease model` (2026-07-14)

### obs:check-tempdoc-numbers — check-tempdoc-numbers has a blind spot: it only reports a number claimed by 2+ IN-FLIGHT worktrees,
`kind: defect?` `anchor: scripts/ci/check-tempdoc-numbers.mjs` `seen: 2` `first: 2026-07-15` `last: 2026-07-17`
- [ ] check-tempdoc-numbers has a blind spot: it only reports a number claimed by 2+ IN-FLIGHT worktrees, excluding any basename already on origin (`newBasenames` filter). So an in-flight tempdoc colliding with one already merged to main passes green — reproduced live: branch worktree-ui-audit-density-review's 696-simple-detailed-disclosure vs main's 696-dev-tooling-jdk-resolution went undetected while the check flagged an unrelated 720 collision in the same run — `scripts/ci/check-tempdoc-numbers.mjs:117` (2026-07-15)
- [ ] check-tempdoc-numbers reports two pre-existing cross-worktree number collisions (#720: memory-injector-plan vs p1a-context-prepend-plan; #729: gjf-removal vs the stale pre-rename copy of 734 in worktree sandbox-validation) — the affected sessions may not know until their own merge-time run — `scripts/ci/check-tempdoc-numbers.mjs` (2026-07-17)

### obs:expected-state-v1 — Possible stale expected-state entry: [ui-web-typecheck-ts5101] declares ui-web `npm run typecheck` R
`kind: environment?` `anchor: expected-state.v1.json` `seen: 1` `first: 2026-07-15` `last: 2026-07-15`
- [ ] Possible stale expected-state entry: [ui-web-typecheck-ts5101] declares ui-web `npm run typecheck` RED repo-wide (TS5101 baseUrl vs pinned typescript 6.x) 'pre-existing on main since ~2026-07-01', but it exits 0 green in a worktree merged up to origin/main (2026-07-15, verified twice). A known-failure entry that no longer reproduces is worse than none — it invites dismissing a REAL typecheck failure as the known one. Verify against main and retire if fixed — `scripts/**/expected-state.v1.json` (2026-07-15)

### obs:ui-selectors — ui_selectors.py SEARCH_INPUT/TID_SEARCH_INPUT/CSS_SEARCH_INPUT_TEXTAREA are stale post-tempdoc-687:
`kind: defect?` `anchor: scripts/jseval/jseval/ui_selectors.py` `seen: 1` `first: 2026-07-15` `last: 2026-07-15`
- [ ] ui_selectors.py SEARCH_INPUT/TID_SEARCH_INPUT/CSS_SEARCH_INPUT_TEXTAREA are stale post-tempdoc-687: the live composer textarea has no role=searchbox, no aria-label, no data-testid=search-input, so _type_and_search (and every ui-shot step chained off search-results, e.g. chat-mode, qa-response, filters-chips) fails under --fixtures in this worktree. Live-verified fix: locate via 'jf-composer textarea' instead. — `scripts/jseval/jseval/ui_selectors.py:14-15,45-46,90` (2026-07-15)

### obs:token-names-generated — origin/main is RED on `gen-token-names --check`: `tokens.css` gained `--glass-blur-scale` + `--text-
`kind: defect?` `anchor: modules/ui-web/src/shell-v0/themes/token-names.generated.ts` `seen: 1` `first: 2026-07-15` `last: 2026-07-15`
- [ ] origin/main is RED on `gen-token-names --check`: `tokens.css` gained `--glass-blur-scale` + `--text-info` without a matching regen, so `token-names.generated.ts` is stale ON MAIN (verified 2026-07-15: token sources byte-identical between origin/main and a branch that never touched them, yet a clean regen adds 4 lines). Blocks the ui-web gate set for ANY branch touching ui-web, not just the one that finds it. Fix = `node scripts/ci/gen-token-names.mjs` — `modules/ui-web/src/shell-v0/themes/token-names.generated.ts` (2026-07-15)

### obs:consult-register-v1 — The `ui-web-gates` recipe documents a kernel-gate command that does not work: `node scripts/governan
`kind: defect?` `anchor: governance/consult-register.v1.json` `seen: 1` `first: 2026-07-15` `last: 2026-07-15`
- [ ] The `ui-web-gates` recipe documents a kernel-gate command that does not work: `node scripts/governance/run.mjs --gate ambient-purity,style-literal-ratchet,... --mode gate` (comma-separated) exits 2 with "gate id 'ambient-purity,style-literal-ratchet' not found" — run.mjs takes ONE gate id per invocation (verified 2026-07-15: comma form exit 2, single form exit 0). Risk is a silent skip of all 6 kernel gates, or exit 2 misread as a gate FAILURE. This recipe is the authority CLAUDE.md's pre-merge table defers to for every `modules/ui-web/src/**` edit — `governance/consult-register.v1.json:28` (2026-07-15)

### obs:unanchored-flake-3 — Flaky test on public main: `:modules:app-inference:test` failed the 'Unit tests (platform-contracts)
`kind: environment?` `anchor: none` `seen: 1` `first: 2026-07-15` `last: 2026-07-15`
- [ ] Flaky test on public main: `:modules:app-inference:test` failed the 'Unit tests (platform-contracts)' CI lane on main at 646465a7 — a DOCS-ONLY commit (observations.md + a shard deletion) that cannot causally touch Java. Conclusive: 58a82275 contains every change from 646465a7 plus one line and PASSED the same lane, and add9d620 (the real code change) passed it too — so the tree is not the cause. Effect is a transiently RED public main from an unrelated docs merge, which erodes the 'is main green?' signal the publish workflow depends on. Worth identifying + quarantining the specific flaky test (report is runner-local HTML; not in the fetchable log) — `modules/app-inference/src/test/java/io/justsearch/app/inference/` (2026-07-15)

### obs:unanchored-general-48 — Reranker is load-bearing / first-stage fusion squanders a strong dense leg: on battlefield-en-scale-
`kind: defect?` `anchor: none` `seen: 1` `first: 2026-07-12` `last: 2026-07-12`
- [ ] Reranker is load-bearing / first-stage fusion squanders a strong dense leg: on battlefield-en-scale-v1 (2026-07-12, current main) client 'full' fusion (BM25+dense+SPLADE RRF, no CE) scored nDCG@10=0.279 — FAR below vector-alone 0.722 and server-hybrid-with-CE 0.697. The cross-encoder does all the recovery (0.279->0.697); first-stage fusion weighting buries the strong dense hit. Fragility worth an owner — relevant to 636 (fusion-order)/712/713. Artifacts: scripts/jseval/tmp/eval-results/*battlefield-en-scale-v1* (2026-07-12)

### obs:unanchored-general-53 — Retrieval on battlefield-en-scale-v1 (2736 docs) is now healthy: agent-hybrid nDCG@10=0.697 on curre
`kind: defect?` `anchor: none` `seen: 1` `first: 2026-07-12` `last: 2026-07-12`
- [ ] Retrieval on battlefield-en-scale-v1 (2736 docs) is now healthy: agent-hybrid nDCG@10=0.697 on current main, up from 0.163 in the 2026-07-08 PR#117 run — the 717 chunk-death fix + 713 dense-authority consolidation fixed the dense leg (vector 0.09->0.722); chunk_completeness verdict=ok (30099 chunks). REFUTES the prior '624 scale corpus is unfixably out-of-band' conclusion; unblocks a meaningful 624 agent-utility ACCURACY measurement (the with-tool arm now retrieves well). (2026-07-12)

### obs:unanchored-general-54 — 624 agent-utility A/B pilot on fixed retrieval (2026-07-12, battlefield-en-scale-v1, haiku, n=12 pai
`kind: defect?` `anchor: none` `seen: 1` `first: 2026-07-12` `last: 2026-07-12`
- [ ] 624 agent-utility A/B pilot on fixed retrieval (2026-07-12, battlefield-en-scale-v1, haiku, n=12 paired): B(with JustSearch) uses -25.7% mean / -28.9% median context tokens vs A(grep), 95% CI [-13%,-41%] excludes 0 (robust); B acc 100% vs A 87.5% but McNemar p=1.0 (both near ceiling -> no headroom, the recurring problem 707's pillar-1 corpus targets); adoption 7.1% (grep suffices on this easy subset -> rational non-adoption). Token-efficiency robust; accuracy underpowered until grep genuinely struggles. Artifacts: scripts/jseval/624-pilot-2026-07-12/ (2026-07-12)

### obs:mcpprotocolhandler — The installed v0.1.0 app (F:\JustSearch-test, ui jars built 2026-04-28) contains no McpProtocolHandl
`kind: defect?` `anchor: modules/ui/src/main/java/io/justsearch/ui/api/mcp/McpProtocolHandler.java` `seen: 1` `first: 2026-07-14` `last: 2026-07-14`
- [ ] The installed v0.1.0 app (F:\JustSearch-test, ui jars built 2026-04-28) contains no McpProtocolHandler — POST /mcp is not served by the shipped release, yet docs/reference/mcp-production-server.md's '~2 minutes' flow and the README describe connecting Claude Desktop to the installed app's /mcp. The MCP surface only exists in builds after v0.1.0 — `modules/ui/src/main/java/io/justsearch/ui/api/mcp/McpProtocolHandler.java` (2026-07-14)

### obs:check-tempdoc-numbers-general — Pre-existing cross-worktree tempdoc #720 collision: 720-memory-injector-plan.md (agent-a439b6b675c7d
`kind: environment?` `anchor: check-tempdoc-numbers` `seen: 5` `first: 2026-07-14` `last: 2026-07-16`
- [ ] Pre-existing cross-worktree tempdoc #720 collision: 720-memory-injector-plan.md (agent-a439b6b675c7d35e5) vs 720-p1a-context-prepend-plan.md (agent-adcdb24cb87068a9c) — check-tempdoc-numbers fails until one is renumbered (2026-07-14)
- [ ] check-tempdoc-numbers exits 1 on two pre-existing cross-worktree collisions: #720 (agent worktrees) and #729 (725-response-legibility vs sandbox-validation) — neither introduced by the release-asset-set branch; owners of those worktrees need to renumber. (2026-07-14)
- [ ] Tempdoc number collision on 729, live right now: '729-0.2.0-sandbox-convergence.md' (502 lines, uncommitted in the sandbox-validation worktree, authored 2026-07-14, exists NOWHERE in git) vs '729-java-formatting-not-enforced.md' (worktree 729-gjf-removal, another live session). Neither is on main, so check-tempdoc-numbers can't see the unbacked one — the cross-worktree collision check only compares what is committed. The sandbox doc is at risk of both loss and renumbering — `docs/tempdocs/729-*` (2026-07-15)
- [ ] check-tempdoc-numbers reports pre-existing cross-worktree collisions: #720 (agent-a439b6b675c7d35e5 vs agent-adcdb24cb87068a9c) and #729 (729-gjf-removal vs sandbox-validation) — owners will hit the gate at merge; noted so it isn't a surprise (2026-07-16)
- [ ] check-tempdoc-numbers reports live cross-worktree collisions at #729 (729-gjf-removal vs sandbox-validation) and #742 (742-gate-input-contract vs 742-residue-removal) — owners of those worktrees must renumber before merge; not this session's trees (2026-07-16)

### obs:check-always-loaded-budget-gate-red — always-loaded-budget gate is RED on origin/main (pre-existing, not from this branch): 4 files over c
`kind: environment?` `anchor: scripts/ci/check-always-loaded-budget.mjs` `seen: 4` `first: 2026-07-15` `last: 2026-07-17`
- [ ] always-loaded-budget gate is RED on origin/main (pre-existing, not from this branch): 4 files over ceiling — CLAUDE.md +1604B, branch-safety.md +1892B, hooks-reference.md +99B, tier-register.md +1579B — `scripts/ci/check-always-loaded-budget.mjs` (2026-07-15)
- [ ] always-loaded-budget ratchet fails on origin/main already: CLAUDE.md (+1604 B over), agent-lessons.md, branch-safety.md, hooks-reference.md, tier-register.md are all OVER their ceilings, and the check isn't wired into the public CI workflow so nothing catches the drift. The ratchet only bites the honest agent who runs it locally — the always-loaded set is ~1.6 KB past its own cap on main today — `scripts/ci/check-always-loaded-budget.mjs` (2026-07-15)
- [ ] check-always-loaded-budget.mjs is red on main (5 files ~9KB over ceilings, predates 742 followups) AND wired to no CI lane or kernel gate — an unenforced ratchet accumulating debt silently; needs an owner editorial trim pass + a decision on wiring it (742-class: unevaluated assertion channel) — `scripts/ci/check-always-loaded-budget.mjs` (2026-07-16)
- [ ] always-loaded-budget ratchet already failing pre-existing (CLAUDE.md, agent-lessons.md, branch-safety.md, tier-register.md all OVER ceiling before this session's edits) — worktree takeover-743, base commit a47cd644 — `scripts/ci/check-always-loaded-budget.mjs` (2026-07-17)

### obs:unanchored-general-56 — Skill-vs-CLAUDE.md contradiction (same class as tempdoc 739 F-3): `.claude/skills/publish/SKILL.md`
`kind: environment?` `anchor: none` `seen: 1` `first: 2026-07-15` `last: 2026-07-15`
- [ ] Skill-vs-CLAUDE.md contradiction (same class as tempdoc 739 F-3): `.claude/skills/publish/SKILL.md` says 'strongly consider just delegating all of the mechanical/overview work of the PR/merge to a subagent', but CLAUDE.md's model-routing rule says 'Never delegate: ... merge/publish, irreversible actions, main-checkout writes'. An agent following the skill violates CLAUDE.md; following CLAUDE.md ignores the skill. Found live while running /publish. The skill is untracked/local-only, so the fix cannot ride along in-repo — same blast radius as 739 F-3. (2026-07-15)

### obs:bash-guard — `bash-guard.mjs` has the same input.cwd gap that tempdoc 739 fixed in `docs-granularity-hint.mjs`: i
`kind: defect?` `anchor: scripts/agent-analytics/hooks/bash-guard.mjs` `seen: 1` `first: 2026-07-15` `last: 2026-07-15`
- [ ] `bash-guard.mjs` has the same input.cwd gap that tempdoc 739 fixed in `docs-granularity-hint.mjs`: it decides 'am I in the main worktree?' from `input.cwd` and does not account for a leading `cd <worktree> &&` in the command (nor `git -C <path>`). Hit live: a `git checkout -b` intended for a worktree was blocked because the session cwd happened to be the main checkout. Worse than the hint case because bash-guard BLOCKS — a false positive stops work rather than just nagging. Fix is the same shape as 739's `gitPushCwd`. `scripts/agent-analytics/hooks/bash-guard.mjs` (2026-07-15)

### obs:agenttoolsoperationcatalog — core.ingest-files (AgentToolsOperationCatalog.java:220-236) declares zero RequiredCapability and zer
`kind: defect?` `anchor: modules/app-services/src/main/java/io/justsearch/app/services/registry/operations/AgentToolsOperationCatalog.java` `seen: 1` `first: 2026-07-15` `last: 2026-07-15`
- [ ] core.ingest-files (AgentToolsOperationCatalog.java:220-236) declares zero RequiredCapability and zero OperationAvailability despite IngestOperationHandler/IngestTool writing into the worker-backed index — unlike core.search-index it has no fallback availability gate either, so a worker-down dispatch reaches the handler raw instead of a clean CAPABILITY_UNAVAILABLE denial — `modules/app-services/src/main/java/io/justsearch/app/services/registry/operations/AgentToolsOperationCatalog.java:212-236` (2026-07-15)

### obs:737-ai-runtime-lifecycle-model — tempdoc 737 Phase-4 subset: tempdoc's §12d line-ref for CapabilityAvailabilityTest.java:43-53 'circu
`kind: defect?` `anchor: docs/tempdocs/737-ai-runtime-lifecycle-model.md` `seen: 1` `first: 2026-07-15` `last: 2026-07-15`
- [ ] tempdoc 737 Phase-4 subset: tempdoc's §12d line-ref for CapabilityAvailabilityTest.java:43-53 'circular pin' fossil does not correspond to an actual circular-pinning test in current source (it's the still-valid generic inferenceOnlineDerivesNotInferenceCapability test, kept); only the GpuAvailable-based test needed replacement — noted as a stale line-citation, not acted on beyond documenting — `docs/tempdocs/737-ai-runtime-lifecycle-model.md:913` (2026-07-15)

### obs:operation-v1 — Full governance kernel run (node scripts/governance/run.mjs --mode gate) shows pre-existing contract
`kind: environment?` `anchor: SSOT/schemas/operation.v1.json` `seen: 1` `first: 2026-07-15` `last: 2026-07-15`
- [ ] Full governance kernel run (node scripts/governance/run.mjs --mode gate) shows pre-existing contract-projection gate failure (schema-types-drift + buf-cli-missing) unrelated to the runtime-state register work — likely another in-flight tempdoc-737 chunk's uncommitted SSOT/schemas/operation.v1.json edit (IndexedRoot/GpuAvailable RequiredCapability removal, §12d) not yet regenerated — `SSOT/schemas/operation.v1.json` (2026-07-15)

### obs:inferencecapabilitywiring — tempdoc 737 Phase-3 chat-offering gap: InferenceCapabilityWiring.attachInferenceModeListener transit
`kind: defect?` `anchor: modules/app-services/src/main/java/io/justsearch/app/services/bootstrap/phases/InferenceCapabilityWiring.java` `seen: 1` `first: 2026-07-15` `last: 2026-07-15`
- [ ] tempdoc 737 Phase-3 chat-offering gap: InferenceCapabilityWiring.attachInferenceModeListener transitions InferenceCapability to READY purely on mode==ONLINE, without consulting spec.chatEnabled — so a VDU procedure that brings the engine up under soft-off (chatEnabled=false) would project chat as available to users despite the user disabling it. §12c projection concern; the reconciler already stamps REASON_ENGINE_UP_FOR_BACKGROUND on RuntimeStatus but the FE capability/aiVerdict projection doesn't yet read spec — `modules/app-services/src/main/java/io/justsearch/app/services/bootstrap/phases/InferenceCapabilityWiring.java:58` (2026-07-15)

### obs:runtime-state-v1 — Phase 2b VDU reroute (OfflineCoordinator/OfflineCoordinatorBuilder) referenced io.justsearch.app.ser
`kind: defect?` `anchor: governance/runtime-state.v1.json` `seen: 1` `first: 2026-07-15` `last: 2026-07-15`
- [ ] Phase 2b VDU reroute (OfflineCoordinator/OfflineCoordinatorBuilder) referenced io.justsearch.app.services.runtimestate but was never added to governance/runtime-state.v1.json, failing the runtime-state gate's unregistered-referencer check; backfilled two consumer rows in tempdoc 737 Phase 2a — `governance/runtime-state.v1.json` (2026-07-15)

### obs:status — Post-737-Phase4: /api/inference/mode REST (BrainRuntimeServiceImpl.switchInferenceMode) still switch
`kind: follow-up?` `anchor: modules/ui-web/src/api/domains/status.ts` `seen: 1` `first: 2026-07-15` `last: 2026-07-15`
- [ ] Post-737-Phase4: /api/inference/mode REST (BrainRuntimeServiceImpl.switchInferenceMode) still switches mode DIRECTLY (foreign change the reconciler may revert to spec); FE buttons + api/domains/status.ts switchInferenceMode may now be dead — candidate for retirement with the switch-inference-mode alias (§12d). — `modules/ui-web/src/api/domains/status.ts:35` (2026-07-15)

### obs:aiinstallservice — ECC never re-syncs on mid-session embedding-model arrival: production relies on AiInstallService.try
`kind: defect?` `anchor: AiInstallService.java` `seen: 1` `first: 2026-07-14` `last: 2026-07-14`
- [ ] ECC never re-syncs on mid-session embedding-model arrival: production relies on AiInstallService.tryRestartWorkerBestEffort (AiInstallService.java:958-972) which is best-effort — on its silent failure the old worker keeps a stale UNAVAILABLE ECC forever; no arrival-side provider listener exists (EmbeddingProviderLifecycle.setEmbeddingProvider never notifies, only unload does). Needs its own design pass (notification seam + re-entrancy-safe refresh + recovery re-run). Refresh-guard hardening landed with the 734 fixes. (2026-07-14)

### obs:indexstatusops — worker.core.pendingJobs (StatusResponse) is actually the combined PENDING+PROCESSING queueDepth (Ind
`kind: defect?` `anchor: IndexStatusOps.java` `seen: 1` `first: 2026-07-14` `last: 2026-07-14`
- [ ] worker.core.pendingJobs (StatusResponse) is actually the combined PENDING+PROCESSING queueDepth (IndexStatusOps.java:241 -> WorkerStatusMapper.java:75) — misleading name; renaming is a wire-contract change, flagging instead of fixing. Sibling migration.pendingJobsCount is PENDING-only. (2026-07-14)

### obs:crossencoderreranker — search/rerank span's reranker.output_documents.*.document.content attr deliberately captures multi-p
`kind: defect?` `anchor: modules/reranker/src/main/java/io/justsearch/reranker/CrossEncoderReranker.java` `seen: 1` `first: 2026-07-14` `last: 2026-07-14`
- [ ] search/rerank span's reranker.output_documents.*.document.content attr deliberately captures multi-paragraph document text (owner-amended span-privacy contract, tempdoc 553 Phase D) — bounded to OpenInferenceSpans.MAX_CONTENT_CHARS=1024 chars/doc already; not an accidental full-content capture — `modules/reranker/src/main/java/io/justsearch/reranker/CrossEncoderReranker.java:174-176`, `modules/telemetry/src/main/java/io/justsearch/telemetry/OpenInferenceSpans.java:45,90-93` (2026-07-14)

### obs:ndjsonspanexporter — Head request-tracing emitter wrote unescaped newlines into traces.ndjson (422/10494 = 4.02% unparsea
`kind: defect?` `anchor: NdjsonSpanExporter.json` `seen: 1` `first: 2026-07-14` `last: 2026-07-14`
- [x] Head request-tracing emitter wrote unescaped newlines into traces.ndjson (422/10494 = 4.02% unparseable lines in a real round's evidence) — FIXED in commit 61f9c51 (`NdjsonSpanExporter.json()` now escapes the full RFC 8259 set; regression test added). Retained here only so the fold doesn't resurrect the stale open form. (2026-07-14)

### obs:unanchored-general-57 — SSE /api/advisory/authorization-pending/stream returns 200-empty text/plain without Accept: text/eve
`kind: follow-up?` `anchor: none` `seen: 1` `first: 2026-07-14` `last: 2026-07-14`
- [ ] SSE /api/advisory/authorization-pending/stream returns 200-empty text/plain without Accept: text/event-stream -- indistinguishable from no-pendings; smoke round lost probes to it. Candidate fix: 406 on wrong Accept, or documented list endpoint. Needs host live-stack regression test. (2026-07-14)

### obs:unanchored-general-58 — Smoke round: unified-chat showed 'Searching 7 files' while /api/knowledge/status said docCount 8 rig
`kind: follow-up?` `anchor: none` `seen: 1` `first: 2026-07-14` `last: 2026-07-14`
- [ ] Smoke round: unified-chat showed 'Searching 7 files' while /api/knowledge/status said docCount 8 right after ingest -- UI/API count lag, ui-api-truthfulness-under-load class; old 0.2.0 build, recheck on candidate rebuild. (2026-07-14)

### obs:config — Dev models/ lacks citation-scorer weights (only config.json+tokenizer.json) while model-registry.v2.
`kind: defect?` `anchor: config.json` `seen: 1` `first: 2026-07-14` `last: 2026-07-14`
- [ ] Dev models/ lacks citation-scorer weights (only config.json+tokenizer.json) while model-registry.v2.json ships a 23MB INT8 model.onnx every real install downloads — dev never exercises citation scoring; shipped system does MORE than the measured one. Audited 2026-07-14 (22/28 registry assets hash-verified identical dev-vs-registry). (2026-07-14)

### obs:embed-model-manifest — CPU-path embedding model diverges dev-vs-shipped: shipped embed-model_manifest.json (130B) says cpu=
`kind: defect?` `anchor: embed-model_manifest.json` `seen: 2` `first: 2026-07-14` `last: 2026-07-14`
- [ ] CPU-path embedding model diverges dev-vs-shipped: shipped embed-model_manifest.json (130B) says cpu=model.onnx (FP32); local models/onnx/gte-multilingual-base/model_manifest.json (1057B) says cpu=model_fp16.onnx — different vectors + different embeddingFingerprint (the index-compat key), so dev cannot detect CPU-path embedding regressions. The local manifest's _comment ('no separate fp32 CPU build') is provably false — model.onnx FP32 sits beside it and the registry ships it. GPU path is byte-identical (verified). (2026-07-14)
- [ ] Registry ships only embed-model_manifest.json; ner/reranker/splade capability manifests exist locally (710 Wave 2) but never reach a user — works by luck because ModelManifest.loadOrDefault's default equals what they'd declare. 710:468-479 knowingly keeps this WARN-not-fail-fast, gated on the 657 shipping event; 710:620 TODO (verify zero capability WARNs) was never extended to the download path. Also: registry ships no prefix_config.json/config.json for embedding, so prefixes resolve by fallback coincidence. (2026-07-14)

### obs:splade-model-manifest — 409 D5/D10/D11 still live (cosmetic, non-blocking): justsearch-releases README:78 says nomic-embed-t
`kind: defect?` `anchor: splade-model_manifest.json` `seen: 1` `first: 2026-07-14` `last: 2026-07-14`
- [ ] 409 D5/D10/D11 still live (cosmetic, non-blocking): justsearch-releases README:78 says nomic-embed-text vs :122 gte-multilingual-base (shipped is gte); models-v1 ships sha256sums-models.txt while alpha.27 ships SHA256SUMS.txt (two names, one artifact); splade-model_manifest.json released but unreferenced by the registry. Also tempdoc 726 frontmatter says the justsearch-releases repo 'is gone' but it is live+public hosting 24 of 28 model assets (726 body :310 says so correctly — frontmatter self-contradicts). (2026-07-14)

### obs:sandbox-launch — sandbox-launch.py auto-maps the CURRENT checkout's models/ — in a worktree that is configs-only (42M
`kind: environment?` `anchor: sandbox-launch.py` `seen: 1` `first: 2026-07-14` `last: 2026-07-14`
- [ ] sandbox-launch.py auto-maps the CURRENT checkout's models/ — in a worktree that is configs-only (42MB, no .onnx/.gguf; weights live in the main checkout), so a pre-staged-models round staged from a worktree silently gets no models and fails NO_EMBEDDING_MODEL. Use --no-models (fresh-install) or --models-dir F:\justsearch-public\models explicitly. Harness should resolve models from the main checkout like dev-runner.cjs:442-448 does, or fail loud when the resolved dir has no weights. (2026-07-14)

### obs:check-coverage — check_coverage.py credits surface-tier coverage from screenshot FILENAMES, never image content — pro
`kind: follow-up?` `anchor: check_coverage.py` `seen: 1` `first: 2026-07-14` `last: 2026-07-14`
- [ ] check_coverage.py credits surface-tier coverage from screenshot FILENAMES, never image content — proven by the 0.2.0 qualifying round: 01-first-paint.png is majority-occluded by a terminal window (window focus not controlled before capture) yet would credit core first-paint coverage. Filename-crediting is unverifiable and effectively spoofable; consider requiring the round to state what each image shows, or a cheap content check (e.g. non-uniform pixels in the app's window rect via the gui/ harness's known window bounds). (2026-07-14)

### obs:unanchored-general-59 — Sandbox evidence time-alignment gap: the 0.2.0 round's api-api-*.json snapshots were all captured in
`kind: environment?` `anchor: none` `seen: 1` `first: 2026-07-14` `last: 2026-07-14`
- [ ] Sandbox evidence time-alignment gap: the 0.2.0 round's api-api-*.json snapshots were all captured in ONE round-end batch (22:17Z), ~30min after the screenshots they'd need to corroborate (23:48-23:50 local), so a UI/API contradiction can only be proven screenshot-vs-screenshot. sandbox-CLAUDE.md already says run collect-evidence.ps1 'early and after each major step' — the round ran it ~twice. Consider having the GUI harness snapshot the relevant API endpoint alongside each surface capture. (2026-07-14)

### obs:unanchored-missing-2 — 0.2.0 round payload nits (non-blocking, from raw evidence): (1) /api/knowledge/search searchTrace sh
`kind: defect?` `anchor: none` `seen: 1` `first: 2026-07-14` `last: 2026-07-14`
- [ ] 0.2.0 round payload nits (non-blocking, from raw evidence): (1) /api/knowledge/search searchTrace showed cross-encoder skipped/DEADLINE_EXCEEDED on one pre-restart query — reranker latency-budget miss, silently degrades ranking; (2) indexCapabilities schema drifts between snapshots of the same endpoint (crossEncoderAvailable present post-restart, absent warm/pre-restart); (3) /api/gpu/capabilities effective.cuda = {functional:null, source:none, confidence:UNKNOWN} while sibling effective.cudaAvailable:true — dead/unpopulated field; (4) modelVariants.cuda-runtime reports {skipped:true, skipReason:'No variant'} while install-status says state:'installed' for the same package — same field name, two meanings, legibility risk. (2026-07-14)

### obs:messages — Locale catalogs still carry React-era msgids whose source refs are retired .tsx paths (ADR-0032), e.
`kind: defect?` `anchor: modules/ui-web/src/locales/en/messages.po` `seen: 1` `first: 2026-07-14` `last: 2026-07-14`
- [ ] Locale catalogs still carry React-era msgids whose source refs are retired .tsx paths (ADR-0032), e.g. "AI Online" attributed to BrainSimplePanel.tsx — the Lit shell-v0 views don't use lingui, so these entries are stale/unreachable — `modules/ui-web/src/locales/en/messages.po:442` (2026-07-14)

### obs:chatcontroller — ChatController.handleCompact (loadHistory-based prefix build, :302) no longer 423s immediately on a
`kind: defect?` `anchor: modules/ui/src/main/java/io/justsearch/ui/api/ChatController.java` `seen: 1` `first: 2026-07-14` `last: 2026-07-14`
- [ ] ChatController.handleCompact (loadHistory-based prefix build, :302) no longer 423s immediately on a locked session now that loadHistory degrades instead of throwing (tempdoc 727 conversation-lock fix) — it now attempts an LLM summarize() call over placeholder/opaque content before failing at the final compactContext() write (cipher.seal still throws KeyLockedException there). Not a correctness/security regression (the write is still blocked), just a wasted LLM call in an edge case (locked + reachable + compact attempted) that's out of scope for this fix — `modules/ui/src/main/java/io/justsearch/ui/api/ChatController.java:302` (2026-07-14)

### obs:resourceview-render-test — ResourceView.render.test.ts has a scratch/debug probe test literally named 'SCRATCH PROBE ... FORCE-
`kind: defect?` `anchor: modules/ui-web/src/shell-v0/components/ResourceView.render.test.ts` `seen: 1` `first: 2026-07-14` `last: 2026-07-14`
- [ ] ResourceView.render.test.ts has a scratch/debug probe test literally named 'SCRATCH PROBE ... FORCE-FAIL-TO-SHOW-STATE' that always fails (asserts an object equals a string) — looks like leftover debug scaffolding, not a real assertion — `modules/ui-web/src/shell-v0/components/ResourceView.render.test.ts:250` (2026-07-14)

### obs:mcpprotocolhandlertest — MCP conformance defect (found live 2026-07-15 driving the shipped MCPB bridge against a dev stack):
`kind: environment?` `anchor: McpProtocolHandlerTest` `seen: 1` `first: 2026-07-14` `last: 2026-07-14`
- [ ] MCP conformance defect (found live 2026-07-15 driving the shipped MCPB bridge against a dev stack): POST /mcp answers the mandatory lifecycle NOTIFICATION 'notifications/initialized' with {"jsonrpc":"2.0","id":null,"error":{"code":-32601,"message":"Method not found: notifications/initialized"}}. TWO defects: (1) the method is unimplemented though the MCP lifecycle requires the client to send it after initialize; (2) JSON-RPC 2.0 forbids ANY response to a notification (no id) — responding with id:null is a protocol violation. Real-world bite: a spec-correct client that expects no reply desynchronizes its read loop (reproduced — it hung scripts/sandbox/mcp-typed-confirm.ps1). tools/call + initialize otherwise work and the TYPED_CONFIRM gate fires correctly. Owner: MCP conformance (655); regression home: McpProtocolHandlerTest (modules/ui/src/test/java/io/justsearch/ui/api/mcp/). (2026-07-14)

### obs:runtimeactivationservicetest — Stale tempdoc reference in test comment: RuntimeActivationServiceTest labels its WARN-suppression ca
`kind: defect?` `anchor: modules/app-services/src/test/java/io/justsearch/app/services/ai/runtime/RuntimeActivationServiceTest.java` `seen: 1` `first: 2026-07-15` `last: 2026-07-15`
- [ ] Stale tempdoc reference in test comment: RuntimeActivationServiceTest labels its WARN-suppression cases `// --------------- Tempdoc 727 F-3 ---------------` but 727's F-3 is the verify-worktree-base hook (unrelated); these cases belong to tempdoc 734's F-3 (false 'leftover from a previous build' WARN). Likely fallout from 734 being renumbered from 729. — `modules/app-services/src/test/java/io/justsearch/app/services/ai/runtime/RuntimeActivationServiceTest.java:232` (2026-07-15)

### obs:enforcer — hook-integrity 'bite' is two-tier and its docstring overclaims: kind:'command-signal' genuinely spaw
`kind: defect?` `anchor: scripts/governance/gates/hook-integrity/enforcer.mjs` `seen: 2` `first: 2026-07-15` `last: 2026-07-15`
- [ ] hook-integrity 'bite' is two-tier and its docstring overclaims: kind:'command-signal' genuinely spawns the hook with crafted violating stdin and asserts the exit code (enforcer.mjs:65-103), but kind:'unit' only does existsSync(testPath) (enforcer.mjs:195-197) — it never runs the test, so it proves a FILE EXISTS, not that the hook bites. The enforcer's own docstring (enforcer.mjs:11-12) claims bite 'proves the hook EMITS its block signal', true only for the command-signal half. Either narrow the docstring or promote unit-kind bites to executed proofs. — `scripts/governance/gates/hook-integrity/enforcer.mjs:11` (2026-07-15)
- [ ] hook-integrity's bite guarantee covers 3 of 35 hooks — measured 2026-07-15. The enforcer skips bite entirely for non-blocking roles (`if (entry.role !== 'blocking') continue`), and kind:'unit' only does existsSync(testPath) rather than executing. Census of governance/agent-hooks.v1.json: advisory 22 with NO bite + 4 unit + 1 command-signal (merge-full-suite-hint, added today, the first advisory hook with an executable spec); blocking 3 command-signal + 3 unit; telemetry 2 none. So only the 3 blocking+command-signal hooks are ever proven to fire; every hint hook's bite is decorative or absent. Same class as the two already found today (unit-kind existsSync; 22 real sandbox tests never run by CI) — a proof that exists but never executes. Fixing it is a program, not a patch: enabling advisory execution needs bites authored for 26 hooks. Candidate tempdoc. — `scripts/governance/gates/hook-integrity/enforcer.mjs:195` (2026-07-15)

### obs:pendingauthorizationbridge-test — RECURRING FE test-blindness pattern: shell-v0 tests mock the operation dispatcher to ALWAYS SUCCEED,
`kind: follow-up?` `anchor: modules/ui-web/src/shell-v0/operations/pendingAuthorizationBridge.test.ts` `seen: 1` `first: 2026-07-15` `last: 2026-07-15`
- [ ] RECURRING FE test-blindness pattern: shell-v0 tests mock the operation dispatcher to ALWAYS SUCCEED, so no test can observe a dispatch failure — and two live bugs hid behind exactly this. (1) BrainSurface.indexing-escape.test.ts's host_.data.invokeOperation mock unconditionally resolves, so 5 green revert-proven tests coexisted with a permanently dead button (the circular capability gate). (2) pendingAuthorizationBridge.test.ts mocks approveAndExecutePending with .mockResolvedValue({executed:true}) only, so the 410-swallow at pendingAuthorizationBridge.ts:154-160 was never exercised. Both tests assert what the FE DISPATCHES, never that the dispatch can succeed or that its failure is surfaced. Worth a convention: a dispatcher mock should have a failure-path test alongside every success-path one. — `modules/ui-web/src/shell-v0/operations/pendingAuthorizationBridge.test.ts` (2026-07-15)

### obs:mcp-typed-confirm — mcp-typed-confirm.mjs's PENDING_ID auto-resolution never fires — live-verified broken twice. resolve
`kind: environment?` `anchor: scripts/sandbox/mcp-typed-confirm.mjs` `seen: 2` `first: 2026-07-15` `last: 2026-07-15`
- [ ] mcp-typed-confirm.mjs's PENDING_ID auto-resolution never fires — live-verified broken twice. resolvePendingId() is called with the MCP TOOL name ('justsearch_ingest') but the SSE advisory record's classExtras.operationId is the WIRE OPERATION id ('core.ingest-files'); findPendingId sees a present-but-non-matching operationId, keeps recursing and returns null, so every PASS run prints 'WARN: could not resolve pendingId' and the operator is back to hand-scraping SSE with curl — the exact toil the script exists to remove. Shipped in de5b90e0; its author verified it only against a SYNTHETIC HTTP server replicating the wire shape, which is why the mismatch survived. Fix: pass 'core.ingest-files', or drop the wantOperationId filter (a solo driver only ever has one live gate). — `scripts/sandbox/mcp-typed-confirm.mjs:441` (2026-07-15)
- [ ] mcp-typed-confirm.mjs defaults --bridge to scripts/sandbox/index.js, which only exists inside a staged sandbox share (sandbox-launch.py copies it there). Running the driver from a dev worktree therefore fails until you pass --bridge packaging/mcpb/server/index.js explicitly. Not a defect (the default is right for its target environment) but the failure is silent-ish and cost a live-verification agent a detour; a clearer 'bridge not found — pass --bridge <path> when running outside a staged sandbox' error, or defaulting to the packaging path when the staged copy is absent, would remove it. — `scripts/sandbox/mcp-typed-confirm.mjs:77` (2026-07-15)

### obs:securitysurface — Security & Privacy surface renders STALE encryption state on initial load of a reused tab: the Conve
`kind: defect?` `anchor: modules/ui-web/src/shell-v0/views/SecuritySurface.ts` `seen: 1` `first: 2026-07-15` `last: 2026-07-15`
- [ ] Security & Privacy surface renders STALE encryption state on initial load of a reused tab: the Conversations summary row + Chat Encryption panel showed 'Encrypted (passphrase) · locked' while GET /api/conversations/encryption returned {"state":"not_configured"}; only a hard reload reconciled it. Distinct from the poll-driven lock propagation (tempdoc 734 G.5, fixed 68e1507c) — this is the INITIAL-LOAD path disagreeing with the API, i.e. a third authority for one fact after the two already found (statusConfig fork in BrainSurface, DATA PROTECTION row staleness). Found during live verification 2026-07-15. — `modules/ui-web/src/shell-v0/views/SecuritySurface.ts` (2026-07-15)

### obs:check-golden-parity — Golden-parity rebuild-variance CALIBRATED (n=3 clean scifact rebuilds, dev GPU-FP16, same corpus/cod
`kind: environment?` `anchor: scripts/sandbox/check_golden_parity.py` `seen: 1` `first: 2026-07-15` `last: 2026-07-15`
- [ ] Golden-parity rebuild-variance CALIBRATED (n=3 clean scifact rebuilds, dev GPU-FP16, same corpus/code/model, 30 query-observations, 2026-07-15). Pure build-to-build variance: overlap min=9 max=10 mean=9.87, never <9; top-1 mismatches 0/30; only q02 and q06 ever move, by exactly 1 doc; q08 is 10/10 across every pair. embeddingFingerprint + docCount (5184) identical across A/B/C. CONCLUSION: a sandbox round's 8/10 with q06/q08 below the 7-overlap bar is OUTSIDE the rebuild envelope by a wide margin — it is a REAL signal about the installed build, NOT the HNSW-tail noise four rounds (734 deferred item; sandbox-CLAUDE.md ~254-265) assumed. MIN_OVERLAP=7 is too LENIENT vs the measured floor of 9, not too strict. Does NOT establish the CAUSE — most likely CPU-FP32 (sandbox, CPU-only) vs GPU-FP16 (check_golden_parity docstring flags this), a hypothesis for a human. Full data: scratchpad/parity-calibration (ephemeral). — `scripts/sandbox/check_golden_parity.py:64` (2026-07-15)

### obs:brainsurface — MERGE-ORDER CONSTRAINT (release 0.2.0): PR #184 (worktree-release-asset-set) must merge to main BEFO
`kind: defect?` `anchor: modules/ui-web/src/shell-v0/views/BrainSurface.ts` `seen: 1` `first: 2026-07-15` `last: 2026-07-15`
- [ ] MERGE-ORDER CONSTRAINT (release 0.2.0): PR #184 (worktree-release-asset-set) must merge to main BEFORE the 737 branch (worktree-737-ai-runtime). Both carry the A1 inference-mode band-aid (BrainSurface.ts, BrainSurface.indexing-escape.test.ts, aiStateStore.ts, aiVerdict.ts) in their shared base; 737 SUPERSEDES and deletes it. #184-first is clean (737's diff already accounts for A1 in its base and cleanly removes it); 737-first CONFLICTS (main would have the rewrite while #184 still re-adds the deleted test + A1-shaped BrainSurface). Owner chose two separate PRs + order-them (2026-07-15), not A1 surgery. Note: 'zero shared files' held BETWEEN the two branches but NOT relative to main. — `modules/ui-web/src/shell-v0/views/BrainSurface.ts` (2026-07-15)

### obs:734-0-2-0-sandbox-convergence — PR #184 BODY must be corrected before merge (independent review 2026-07-15): (1) surface that #184 s
`kind: environment?` `anchor: docs/tempdocs/734-0.2.0-sandbox-convergence.md` `seen: 1` `first: 2026-07-15` `last: 2026-07-15`
- [ ] PR #184 BODY must be corrected before merge (independent review 2026-07-15): (1) surface that #184 ships the dead 'Resume Chat AI' button — the undotted A1 band-aid, live-verified non-functional, superseded by 737; (2) disambiguate the A.1/A1 naming trap — dotted 'A.1' (dense/hybrid retrieval) IS fixed and verified across rounds 3-4, undotted 'A1' (BrainSurface indexing→online button, CoreOperationCatalog:803 circular gate) is NOT, and the PR body's 'A.1 blocker fixed' line reads as covering both. A merger skimming the body could ship a prominent dead button believing all AI-runtime work is verified. Mitigation already in plan: no installer is cut in the #184→737 merge window, so the dead button never reaches a user; 737 removes it. — `docs/tempdocs/734-0.2.0-sandbox-convergence.md:744` (2026-07-15)

### obs:719-reproducible-public-agent-utility-benchmark — 719 source-identity prose is behind the implementation: #178 (725) added mcp_initialize_identity + s
`kind: defect?` `anchor: docs/tempdocs/719-reproducible-public-agent-utility-benchmark.md` `seen: 1` `first: 2026-07-15` `last: 2026-07-15`
- [ ] 719 source-identity prose is behind the implementation: #178 (725) added mcp_initialize_identity + session-config exposure mode to the captured surface, and utility-claim-policy.v1.json now requires verified_exposure_mode — 719's boundary section still names only the tools/list hash. Extend the prose when 719 is next touched — `docs/tempdocs/719-reproducible-public-agent-utility-benchmark.md:32` (2026-07-15)

### obs:unanchored-drift-7 — tempdoc 737 status header still says 'NOT MERGED — no PR until owner's word' but PR #193 (f7d8e03f)
`kind: defect?` `anchor: none` `seen: 1` `first: 2026-07-15` `last: 2026-07-15`
- [ ] tempdoc 737 status header still says 'NOT MERGED — no PR until owner's word' but PR #193 (f7d8e03f) is its merge commit — stale status, refresh on next touch — `docs/tempdocs/737*.md:1` (2026-07-15)

### obs:707-pillar1-inband-utility-corpus — 707 chain-2 engine finding candidate: German pure-synonym semantic bridging collapses with corpus si
`kind: follow-up?` `anchor: docs/tempdocs/707-pillar1-inband-utility-corpus.md` `seen: 2` `first: 2026-07-16` `last: 2026-07-16`
- [ ] 707 chain-2 engine finding candidate: German pure-synonym semantic bridging collapses with corpus size on the current encoder (DE v2 gold, zero lexical overlap: hybrid 0.21-0.27 at 1k -> 0.043 at 10k, union recall 0.40 -> 0.10; CLERC EN same design holds 0.32 at 10k). Routes to the encoder-representation lane (708-successor), not corpus design — `docs/tempdocs/707-pillar1-inband-utility-corpus.md` §Chain-2 (2026-07-16)
- [ ] Subagent watcher-strand pattern (2x this session, 2026-07-16): workers stop mid-task 'waiting for background pytest via Monitor' and never resume — the Monitor notification does not reach a completed/stopped agent turn; each needed a SendMessage resume with 'run it foreground'. Same family as the main-loop watcher failures being investigated; worker briefs should mandate synchronous verification, and the harness fix should consider agent-scoped monitors dying with the agent turn — `docs/tempdocs/707-pillar1-inband-utility-corpus.md` (supervision sections) (2026-07-16)

### obs:unanchored-drift-10 — Main checkout parked on a stale feature branch (mcpb-packaging, 18 behind origin/main) means EVERY s
`kind: defect?` `anchor: none` `seen: 1` `first: 2026-07-15` `last: 2026-07-15`
- [ ] Main checkout parked on a stale feature branch (mcpb-packaging, 18 behind origin/main) means EVERY session starting there loads 18-commit-old CLAUDE.md + .claude/rules/* — this session ran without tier-register rows 38-42 (incl. squash-merge-verify-content-not-ancestry and no-merge-without-authorization) and hit the exact trap row 40 documents. Rules staleness is invisible to the agent — `.claude/rules/` (2026-07-15)

### obs:unanchored-error-8 — CLAUDE.md pitfall says '*.onnx are Git LFS-tracked, do not gitignore model files', but *.onnx was NE
`kind: lesson?` `anchor: none` `seen: 1` `first: 2026-07-15` `last: 2026-07-15`
- [ ] CLAUDE.md pitfall says '*.onnx are Git LFS-tracked, do not gitignore model files', but *.onnx was NEVER tracked in this repo's history (0 commits), origin/main tracks 0 .onnx, and CI has a 'Build (no model blobs)' job — meanwhile 2.6 GB of untracked+unignored .onnx sits in the main checkout where a 'git add -A' would stage it (the same move that leaked the 4 orchestration skills, rule 39). Pitfall row may be inherited from a context where models are committed — `CLAUDE.md:Common Pitfalls` (2026-07-15)

### obs:unanchored-drift-11 — CLAUDE.md + branch-safety.md still say the main checkout is 'F:\JustSearch' — stale. That private cl
`kind: defect?` `anchor: none` `seen: 1` `first: 2026-07-15` `last: 2026-07-15`
- [ ] CLAUDE.md + branch-safety.md still say the main checkout is 'F:\JustSearch' — stale. That private clone (remote eliasjustus/JustSearch, 9 .onnx via LFS) was last committed 2026-06-25 and has 1 worktree; the live repo is F:\justsearch-public (public remote, committed today, 19 worktrees, 487 tempdocs). Consequence: the never-checkout-in-main rule reads as not applying to the checkout it actually governs. Not fixed here — branch-safety.md is being edited by PR #202, so this needs to land without a collision — `CLAUDE.md:Parallel Agents` (2026-07-15)

### obs:localapiserver — Verify product /mcp Streamable-HTTP conformance against current spec (single path supports POST+GET;
`kind: defect?` `anchor: modules/ui/src/main/java/io/justsearch/ui/api/LocalApiServer.java` `seen: 1` `first: 2026-07-14` `last: 2026-07-14`
- [ ] Verify product /mcp Streamable-HTTP conformance against current spec (single path supports POST+GET; server MUST validate Origin header vs DNS-rebinding) — app registers POST+DELETE /mcp at `modules/ui/src/main/java/io/justsearch/ui/api/LocalApiServer.java:567`; surfaced during tempdoc 728 research; owned by product/655 conformance, not 728 (2026-07-14)

### obs:unanchored-general-60 — Background-watcher double failure mode (2026-07-16): a gh-polling background Bash spun 25+ min on a
`kind: defect?` `anchor: none` `seen: 1` `first: 2026-07-16` `last: 2026-07-16`
- [ ] Background-watcher double failure mode (2026-07-16): a gh-polling background Bash spun 25+ min on a fabricated full SHA (guessed tail instead of git rev-parse — verify-dont-guess applies to command ARGUMENTS too) producing a 0-byte output file, AND its 900s timeout neither killed it nor emitted a task-notification (TaskStop found it alive at ~25 min). Silence was indistinguishable from progress until a disk-state check read the empty file — the founder's 30-min pull-based wakeup-loop pattern (this session) is the antidote; resolve-then-watch loops should fail closed after N empty polls instead of spinning (2026-07-16)

### obs:inventory — inventory.py's _main() write path omits newline="\n" (unlike sibling committed-artifact writers e.g.
`kind: defect?` `anchor: scripts/jseval/jseval/commands/inventory.py` `seen: 1` `first: 2026-07-16` `last: 2026-07-16`
- [ ] inventory.py's _main() write path omits newline="\n" (unlike sibling committed-artifact writers e.g. commands/corpus.py's _write_recipe) -- write_text() on Windows emits CRLF; harmless only because the repo's blanket `* text=auto eol=lf` gitattribute normalizes it back to LF at commit time -- `scripts/jseval/jseval/commands/inventory.py:51` (2026-07-16)

### obs:release — CRLF-writer family continues: jseval release (--out release.v1.json) and changeset-new both write pl
`kind: defect?` `anchor: scripts/jseval/jseval/commands/release.py` `seen: 1` `first: 2026-07-16` `last: 2026-07-16`
- [ ] CRLF-writer family continues: jseval release (--out release.v1.json) and changeset-new both write platform-default newlines (git warned CRLF->LF at commit, 2026-07-16). Parsed-not-hashed consumers so no integrity break today, but same class as the 707 commitment bake-in — sweep remaining write_text sites for newline= in scripts/jseval — `scripts/jseval/jseval/commands/release.py` (2026-07-16)

### obs:gen-public-agent-utility — gen-public-agent-utility.mjs renders factually stale outward text post-#205: the README generated bl
`kind: defect?` `anchor: scripts/docs/gen-public-agent-utility.mjs` `seen: 1` `first: 2026-07-16` `last: 2026-07-16`
- [ ] gen-public-agent-utility.mjs renders factually stale outward text post-#205: the README generated block still claims 'the checked-in policy has no required campaign matrix / CLERC and MIRACL-DE lack certificates' — false since the policy went ACTIVE and en-legal-clerc reached fully-certified (#205). The generator's no-accepted-result boilerplate hardcodes pre-ratification state instead of reading the policy/certification files. Outward-facing factual drift on public README — high priority for whoever owns 719's public surfaces — `scripts/docs/gen-public-agent-utility.mjs` (2026-07-16)

### obs:unanchored-general-61 — DE encoder-lane charter opened as tempdoc 748 (German semantic bridging 10k collapse) — closes the 7
`kind: defect?` `anchor: none` `seen: 1` `first: 2026-07-16` `last: 2026-07-16`
- [ ] DE encoder-lane charter opened as tempdoc 748 (German semantic bridging 10k collapse) — closes the 707 chain-2 'routed to encoder lane' condition; DE remains 1k-only secondary stratum until 748 closes (2026-07-16)

### obs:unanchored-general-65 — main checkout's local main is ~4 docs(743) commits + merge-commits ahead of origin/main (branch prot
`kind: defect?` `anchor: none` `seen: 1` `first: 2026-07-16` `last: 2026-07-16`
- [ ] main checkout's local main is ~4 docs(743) commits + merge-commits ahead of origin/main (branch protection strands direct commits) — needs a batch docs PR from whoever owns the 743 session; pullers get recurring 'Merge branch main' commits until then (2026-07-16)

### obs:unanchored-drift-16 — modules/ui-web/README.md still describes the frontend as React + TypeScript + Vite with Zustand stor
`kind: defect?` `anchor: none` `seen: 1` `first: 2026-07-16` `last: 2026-07-16`
- [ ] modules/ui-web/README.md still describes the frontend as React + TypeScript + Vite with Zustand stores / React hooks (lines 3, 91, 93) — stale vs Hard Invariant #5 (frontend is Lit, ADR-0032); out of scope for tempdoc 742 residue-removal (only touched the Playwright-script rows this pass) — `modules/ui-web/README.md:3` (2026-07-16)

### obs:docs-validate — docs-validate.mjs crashes (uncaught YAMLException from gray-matter) on docs/tempdocs/530-*.md's fron
`kind: environment?` `anchor: scripts/docs/docs-validate.mjs` `seen: 2` `first: 2026-07-16` `last: 2026-07-16`
- [ ] docs-validate.mjs crashes (uncaught YAMLException from gray-matter) on docs/tempdocs/530-*.md's frontmatter — an unescaped mid-line value breaks js-yaml block-mapping parsing; pre-existing, unrelated to the synonyms-loader removal (742) — `scripts/docs/docs-validate.mjs` + `docs/tempdocs/530-discipline-gate-kernel-four-layer-design.md:6` (approx, 'updated:' line) (2026-07-16)
- [ ] docs-validate.mjs crashes with YAMLException on docs/tempdocs/530-*.md frontmatter — pre-existing, found during 742 synonyms-reader removal — `scripts/docs/docs-validate.mjs` (2026-07-16)

### obs:gen-liveness-constants — check-liveness-constants-regen fails on main: SPDX mass-commit (11c306af) stamped the generated Live
`kind: environment?` `anchor: scripts/codegen/gen-liveness-constants.mjs` `seen: 1` `first: 2026-07-16` `last: 2026-07-16`
- [ ] check-liveness-constants-regen fails on main: SPDX mass-commit (11c306af) stamped the generated LivenessWindows.java but gen-liveness-constants.mjs doesn't emit SPDX headers — regen check red since 2026-06-23, pre-existing, unrelated to 742 — `scripts/codegen/gen-liveness-constants.mjs` (2026-07-16)

### obs:unanchored-general-66 — Sonnet subagent bulk-edit corrupted UTF-8 in 47 Java files (cp1252 round-trip mojibake) during the 7
`kind: lesson?` `anchor: none` `seen: 1` `first: 2026-07-16` `last: 2026-07-16`
- [ ] Sonnet subagent bulk-edit corrupted UTF-8 in 47 Java files (cp1252 round-trip mojibake) during the 742 IndexDocument rename — only 3 tests caught it (language-detection assertions); repaired by regenerating from HEAD with a node UTF-8-safe transform + asserting zero added non-ASCII diff lines. Candidate agent-lessons rule: subagent bulk file edits on Windows must use UTF-8-safe tooling (node/Edit tool), never PowerShell Get/Set-Content defaults — `scripts/agent-analytics/hooks/` (2026-07-16)

### obs:enforcer-test — Pre-existing test failure: operation-surface enforcer.test.mjs 'no forbidden file present -> pass' g
`kind: environment?` `anchor: scripts/governance/gates/operation-surface/enforcer.test.mjs` `seen: 1` `first: 2026-07-16` `last: 2026-07-16`
- [ ] Pre-existing test failure: operation-surface enforcer.test.mjs 'no forbidden file present -> pass' gets verdict operation-surface/vacuous-scan (fail); its harness also exits 0 despite the failure, masking it from node --test-per-file CI — `scripts/governance/gates/operation-surface/enforcer.test.mjs:1` (2026-07-16)

### obs:21-agent-analytics-pipeline — Canonical doc 21-agent-analytics-pipeline.md predates the 622 OTel path: it documents zero of otlp-s
`kind: environment?` `anchor: docs/explanation/21-agent-analytics-pipeline.md` `seen: 1` `first: 2026-07-16` `last: 2026-07-16`
- [ ] Canonical doc 21-agent-analytics-pipeline.md predates the 622 OTel path: it documents zero of otlp-sink.py/otlp-viewer/outcome-session/record-merge/baseline-economics, lists 8 of ~40 hooks, and its headline 'Content is never stored' (`docs/explanation/21-agent-analytics-pipeline.md:72`) is true only of the hook/input-summarizer path — the OTel path stores full prompts + raw API bodies (OTEL_LOG_USER_PROMPTS/TOOL_CONTENT/RAW_API_BODIES=1). Both stay local so the local-only posture holds; the claim is scope-drifted, not a leak. Found during 745 investigation. (2026-07-16)

### obs:transcript-cost — otlp-sink.py getPricing() falls back to DEFAULT_PRICING (sonnet-4-5) for any unrecognized model id —
`kind: follow-up?` `anchor: scripts/agent-analytics/lib/transcript-cost.mjs` `seen: 2` `first: 2026-07-16` `last: 2026-07-16`
- [ ] otlp-sink.py getPricing() falls back to DEFAULT_PRICING (sonnet-4-5) for any unrecognized model id — `scripts/agent-analytics/lib/transcript-cost.mjs:48-53` — i.e. a silent mis-price at the lib layer. 743 Phase-1's 'unknown models are bucketed loudly, never silently priced' holds only because callers separately call the surface-unknown helper; a future caller that forgets inherits silent wrong dollars. Consider making the fallback fail-loud at the lib boundary. Noticed during 745 investigation, not fixed (out of scope). (2026-07-16)
- [ ] Possible 4th pricing defect: sonnet-5 may be on a $2/$10 intro rate through 2026-08-31 reverting to $3/$15 on 2026-09-01, while `scripts/agent-analytics/lib/transcript-cost.mjs:23` hardcodes the $3/$15 sticker rate — if true we overstate sonnet-5 ~50% today. REPORTED BY PROBE, NOT INDEPENDENTLY VERIFIED — verify against Anthropic pricing before acting. Neither ccusage nor Usage-Monitor handles the dated cliff either; date-conditional pricing is unimplemented everywhere. See tempdoc 745 F-10. (2026-07-16)

### obs:unanchored-gate-red-3 — 743's published Phase-1 baseline ($21,410; 85.1/14.9 orchestrator/worker) is computed by a parser wi
`kind: defect?` `anchor: none` `seen: 1` `first: 2026-07-16` `last: 2026-07-16`
- [ ] 743's published Phase-1 baseline ($21,410; 85.1/14.9 orchestrator/worker) is computed by a parser with 3 verified bugs found during the 745 takeover — cross-file dedup overcount, first-vs-last snapshot undercounting subagent output -30%, and a 1h-cache-tier collapse underpricing 100% of cache writes. Direction: total cost understated; split biased toward orchestrator — the same axis 743's live prediction 1 tests. Recompute after the parser fix before testing that prediction. See tempdoc 745 F-6/F-7. (2026-07-16)

### obs:otlp-sink — otlp-sink.py's only third-party dependency (`opentelemetry-proto`, hard-imported at `scripts/agent-a
`kind: defect?` `anchor: scripts/agent-analytics/otlp-sink.py` `seen: 1` `first: 2026-07-16` `last: 2026-07-16`
- [ ] otlp-sink.py's only third-party dependency (`opentelemetry-proto`, hard-imported at `scripts/agent-analytics/otlp-sink.py:17-22`) is declared NOWHERE in the repo — no requirements.txt/pyproject, only a passing mention in tempdoc 622. On a fresh checkout `otlp-sink-ensure` spawns the sink detached with stdio:'ignore', so an ImportError kills it SILENTLY and telemetry stops with no symptom — the same silent-failure class as the chunked-encoding bug (743) and the rotation bug (745 F-2), now three times in one file. 745 pins it in CI; declaring it properly (requirements file + a startup check in otlp-sink-ensure) is unowned. (2026-07-16)

### obs:unanchored-gate-red-4 — ~~FIFTH cost bug (745 item B, found while implementing D4, deliberately NOT fixed — needs an owner d
`kind: defect?` `anchor: none` `seen: 1`
- [x] ~~FIFTH cost bug (745 item B, found while implementing D4, deliberately NOT fixed — needs an owner decision): last-snapshot-wins discards real usage when a transcript re-carries a turn with an ALL-ZERO usage snapshot.~~ **RESOLVED IN THE SAME PR (#221) — this note was written mid-implementation and is superseded; do not read it as an open item.** The bug was real and is FIXED (745 F-11: an all-zero snapshot never displaces a non-zero one), pinned by three tests incl. an order-independence test. Two corrections to this note's own content: (a) its numbers (1,477 keys / 1.309G cache_read / $22,539) predate the reviewer's cross-file-guard fix and the tiered-cache alignment — the settled figures are 1,455 keys / 1.288G cache_read and a ~$22,100 baseline (745 F-12/F-13); (b) its claim that 'ccusage makes the identical error' was REFUTED by the differential — ccusage does not have this bug (745 F-11 correction). No owner decision is outstanding.

### obs:run — PR #215's gate-input contract makes a bare local `node scripts/governance/run.mjs --mode gate` exit
`kind: follow-up?` `anchor: run.mjs` `seen: 1` `first: 2026-07-16` `last: 2026-07-16`
- [ ] PR #215's gate-input contract makes a bare local `node scripts/governance/run.mjs --mode gate` exit 1 on a fresh worktree — npm-audit/module-deps/dead-code/dead-code-jvm now fail CLOSED on missing inputs rather than passing vacuously. Correct behaviour and each names its remedy, but two things compound it: the failures are invisible in the output tail (only the bare exit code shows them), and public CI does NOT run the kernel at all, so local is the only place it ever runs. An agent who greps the tail for ': fail' sees nothing and concludes green. Producing all four inputs (report-npm-audit.mjs, module-deps.mjs, knip:report, :modules:dead-code-audit:test) then gives 34/34 exit 0. Consider a one-shot 'produce kernel inputs' script or a note in the pre-merge table. Found during 745 publish. (2026-07-16)

### obs:event-writer — SAME-CLASS DEFECT LEFT UNFIXED by 745: `scripts/agent-analytics/lib/event-writer.mjs` rotates events
`kind: defect?` `anchor: scripts/agent-analytics/lib/event-writer.mjs` `seen: 1` `first: 2026-07-16` `last: 2026-07-16`
- [ ] SAME-CLASS DEFECT LEFT UNFIXED by 745: `scripts/agent-analytics/lib/event-writer.mjs` rotates events.ndjson at 10MB via `fs.renameSync(filePath, filePath + '.prev')`, which SILENTLY OVERWRITES the existing .prev — identical data loss to the otlp-sink rotation 745 F-2 just fixed, only implicit instead of an explicit os.remove. It is LIVE: dispatch.mjs + export-session-env.mjs write it, and telemetry-io reads it. Measured 2026-07-16: events.ndjson 6.8MB against the 10MB trigger, with 10.49MB in .prev that the next rotation destroys. Per structural-defects-no-repeat one documented instance proves the class — 745 fixed one instance and left the sibling. Deliberately NOT bolted onto PR #221 (different subsystem/consumers; the PR was already independently reviewed and green — adding an unreviewed change would bypass that review). Remedy is known and cheap: mirror otlp-sink.py's archive+per-stream-retention pattern. TOP FOLLOWUP. (2026-07-16)

### obs:unanchored-gate-red-5 — 743 prediction-1 early datum (N=1, do NOT read as confirmation): session 805279a4 (745 implementatio
`kind: defect?` `anchor: none` `seen: 1` `first: 2026-07-16` `last: 2026-07-16`
- [ ] 743 prediction-1 early datum (N=1, do NOT read as confirmation): session 805279a4 (745 implementation, post-2026-07-15 delegation policy) teardown-costed at $148.01 with an orchestrator/worker split of **75.1% / 24.9%** — well below the recomputed 84.0% window baseline, i.e. directionally what prediction 1 expects. Caveats matter more than the number: N=1; this session was atypically delegation-heavy by design (~10 subagents incl. 4 opus workers); and 743 finding 6 says fine effects are unreadable at this scale — one session is an anecdote, not a reading. Recorded because it is the first post-policy session measured on the FIXED instrument (the 84.0% baseline and this row share a parser). Prediction 2 is meanwhile CONFIRMED not pending: costs.ndjson went 1 -> 5 rows, 4 of them record-merge teardowns in one day — 'still sparse in two weeks' did not happen. (2026-07-16)

### obs:unanchored-drift-17 — modules/ui-web/README.md still describes the frontend as React + TypeScript + Vite with Zustand stor
`kind: defect?` `anchor: none` `seen: 1` `first: 2026-07-16` `last: 2026-07-16`
- [ ] modules/ui-web/README.md still describes the frontend as React + TypeScript + Vite with Zustand stores / React hooks (lines 3, 91, 93) — stale vs Hard Invariant #5 (frontend is Lit, ADR-0032); out of scope for tempdoc 742 residue-removal (only touched the Playwright-script rows this pass) — `modules/ui-web/README.md:3` (2026-07-16)

### obs:fold-observations — fold-observations.mjs is NOT idempotent for MERGED entries — re-folding a shard inflates the `seen`
`kind: follow-up?` `anchor: scripts/agent-analytics/fold-observations.mjs` `seen: 1` `first: 2026-07-16` `last: 2026-07-16`
- [ ] fold-observations.mjs is NOT idempotent for MERGED entries — re-folding a shard inflates the `seen` ranking signal. Mechanism: an entry that merges into an existing condition has its text rewritten in the store (occurrence appended), so on a later fold of the same shard it no longer matches verbatim, misses the exact-duplicate skip, and merges AGAIN (seen++). Entries that OPEN a condition round-trip fine. Observed live 2026-07-16: shard 70bf04ea was folded, then session 70bf04ea appended 2 entries and restored the file via PR #222; re-folding its 8 entries gave 5 exact-duplicate skips instead of 6, i.e. one already-folded entry double-counted. Impact is small (seen is a ranking signal, not a gate) but it is silent and compounds with every modify/delete shard race — which will recur, since a shard can be appended to after a fold reads it. Candidate fix: key the duplicate check on a stable entry hash rather than the post-merge text. — `scripts/agent-analytics/fold-observations.mjs:107` (2026-07-16)

### obs:coreworkflowcatalog — justsearch_dev_start defaults to the MAIN CHECKOUT's installed dist even when called from a worktree
`kind: defect?` `anchor: modules/app-services/src/main/java/io/justsearch/app/services/conversation/CoreWorkflowCatalog.java` `seen: 1` `first: 2026-07-16` `last: 2026-07-16`
- [ ] justsearch_dev_start defaults to the MAIN CHECKOUT's installed dist even when called from a worktree session -- silently testing stale code with no error. Must pass distFrom:<worktree-path> explicitly after editing Java in a worktree, not just re-run installDist. Cost real debugging time verifying tempdoc 734/744's core.workflow-run fix (2026-07-16) -- `modules/app-services/src/main/java/io/justsearch/app/services/conversation/CoreWorkflowCatalog.java`, `WorkflowShapeRunner.java`. (2026-07-16)

### obs:unanchored-general-67 — DE encoder-lane charter opened as tempdoc 748 (German semantic bridging 10k collapse) — closes the 7
`kind: defect?` `anchor: none` `seen: 1` `first: 2026-07-16` `last: 2026-07-16`
- [ ] DE encoder-lane charter opened as tempdoc 748 (German semantic bridging 10k collapse) — closes the 707 chain-2 'routed to encoder lane' condition; DE remains 1k-only secondary stratum until 748 closes (2026-07-16)

### obs:unanchored-general-70 — main checkout's local main is ~4 docs(743) commits + merge-commits ahead of origin/main (branch prot
`kind: defect?` `anchor: none` `seen: 1` `first: 2026-07-16` `last: 2026-07-16`
- [ ] main checkout's local main is ~4 docs(743) commits + merge-commits ahead of origin/main (branch protection strands direct commits) — needs a batch docs PR from whoever owns the 743 session; pullers get recurring 'Merge branch main' commits until then (2026-07-16)

### obs:unanchored-general-71 — 707 corpus reproduction path CONFIRMED end-to-end for the first time (741 §8 flagged never-run): fre
`kind: defect?` `anchor: none` `seen: 1` `first: 2026-07-16` `last: 2026-07-16`
- [ ] 707 corpus reproduction path CONFIRMED end-to-end for the first time (741 §8 flagged never-run): fresh worktree, corpus-fetch-clerc/-enron-raw cache hits reproduced recipe pins byte-exactly, corpus-inject-real regenerated all 8 cells with all 16 certification hashes matching — Step-2 prep, ~6 min total (2026-07-16)

### obs:corpus-certify — 707 certification signs corpus.jsonl+qrels but utility-run's staged binding hashes the raw corpus-di
`kind: follow-up?` `anchor: scripts/jseval/jseval/corpus_certify.py` `seen: 1` `first: 2026-07-16` `last: 2026-07-16`
- [ ] 707 certification signs corpus.jsonl+qrels but utility-run's staged binding hashes the raw corpus-dir files — strict --corpus-certification can never pass on a certified member (Step-2 ran declared-signature mode with a recorded hash-equivalence chain instead). Follow-up: add corpus_dir_signature to corpus-certify-member + thread it through utility-run strict mode — `scripts/jseval/jseval/corpus_certify.py:617` (2026-07-16)

### obs:utility-calibrate — Step-2 campaign harness lesson: utility-calibrate's pooled-pilot timeout (p95x2) underestimates the
`kind: lesson?` `anchor: scripts/jseval/jseval/utility_calibrate.py` `seen: 3` `first: 2026-07-17` `last: 2026-07-18`
- [ ] Step-2 campaign harness lesson: utility-calibrate's pooled-pilot timeout (p95x2) underestimates the A-arm (grep) tail on 10k corpora — A-arm timeout attrition (32%) voided comparability exactly where the tool wins; next campaign needs per-arm timeout calibration pre-run — `scripts/jseval/jseval/utility_calibrate.py` (2026-07-17)
- [ ] Design-interaction lesson (phase-2 email-10k): exhaustion-as-failure ITT scoring requires IDENTICAL per-arm budgets — per-arm timeout application (built to fix Step-2's slow-arm starvation) inverted the bias and starved the fast arm (B floor-clamped 120s < its own p95; 26/60 B exhaustions, accuracy delta flipped negative as artifact). Rule: calibrate per arm, apply max() to all arms. Cross-increment interactions need a review lens of their own — `scripts/jseval/jseval/utility_calibrate.py` (2026-07-17)
- [ ] chain-level banked calibration.json survives across git SHA changes between launch attempts — v4 confirmatory chain adopted a 23:33 calibration pinned at 92ec2e6d into a 079e63e5 run, splitting the config cohort and failing recompose (incident #5, tempdoc 624). Fix direction: utility-calibrate should stamp git_sha into calibration.json and chains should invalidate banked calibrations on mismatch — `scripts/jseval/jseval/utility_calibrate.py:252` (2026-07-18)

### obs:agent-utility-observations — utility claim policy treats resource-exhausted cells (wall-clock/USD budget) as EXCLUDED rather than
`kind: defect?` `anchor: scripts/jseval/jseval/agent_utility_observations.py` `seen: 1` `first: 2026-07-17` `last: 2026-07-17`
- [ ] utility claim policy treats resource-exhausted cells (wall-clock/USD budget) as EXCLUDED rather than as ITT failures — the conventional exhaustion-as-failure outcome rule would have made Step-2's matrices complete (60/60 pairs) instead of comparability-voided; encode it as the pre-registered primary rule before the next campaign, then re-verdict Step-2 offline via 719 replay — `scripts/jseval/jseval/agent_utility_observations.py:96` (2026-07-17)

### obs:chain-phase2 — chain-phase2 first launch: serve_up call frame silently aborted ~2s in (no marker, no branch log; ch
`kind: defect?` `anchor: scripts/jseval/chain-phase2.bat` `seen: 1` `first: 2026-07-17` `last: 2026-07-17`
- [ ] chain-phase2 first launch: serve_up call frame silently aborted ~2s in (no marker, no branch log; child backend healthy) — unreproduced on identical relaunch; poll-loop iteration tracing now baked into the chain; signature: FAIL-serve_up at +2s with empty markers dir — reopen if it recurs — `scripts/jseval/chain-phase2.bat` (2026-07-17)

### obs:backend — Eval campaigns rebuild identical indexes repeatedly (legal-10k built 3x in 12h for the same corpus_s
`kind: defect?` `anchor: scripts/jseval/jseval/backend.py` `seen: 2` `first: 2026-07-17` `last: 2026-07-17`
- [ ] Eval campaigns rebuild identical indexes repeatedly (legal-10k built 3x in 12h for the same corpus_signature x config_cohort_key) — a content-addressed index cache keyed on exactly those two pins would keep the fresh-build validity guarantee while amortizing ~50min/build; belongs to the 676/pillar-6 isolated eval lane; note the 716 retirement of --clean protected-set reuse was about UNKEYED reuse, which this design avoids — `scripts/jseval/jseval/backend.py` (2026-07-17)
- [ ] jseval backend lifecycle can bind to the WRONG backend: start_backend health-polls the fixed port (33221) but runHeadlessEval falls back to an ephemeral port when 33221 is occupied (observed live: stale process answered 33221 while the spawned Head logged 'Local API Server started on port 57198'); jseval then ran its whole 49-min lifecycle against the stale process with exit masked. Fix direction: verify the polled backend's identity (PID or /api/debug/state justsearch.data.dir) against the spawned proc — `scripts/jseval/jseval/backend.py:156` (2026-07-17)

### obs:docs-granularity-hint — docs-granularity-hint fired on push for worktree-round6-writeup despite the branch's actual diff vs
`kind: defect?` `anchor: scripts/agent-analytics/hooks/docs-granularity-hint.mjs` `seen: 1` `first: 2026-07-16` `last: 2026-07-16`
- [ ] docs-granularity-hint fired on push for worktree-round6-writeup despite the branch's actual diff vs origin/main spanning 4 files (canonical doc + 2 tempdocs + code) -- exactly the multi-file/canonical-doc case the rule says should NOT trigger it. Possible hook logic bug (maybe checking only the latest commit's diff, not the full branch-vs-base diff) -- `scripts/agent-analytics/hooks/docs-granularity-hint.mjs` (2026-07-16)

### obs:check-store-recoverability — scripts/ci/*.test.mjs sibling-convention tests (e.g. check-store-recoverability.test.mjs, check-read
`kind: defect?` `anchor: check-store-recoverability` `seen: 1` `first: 2026-07-17` `last: 2026-07-17`
- [ ] scripts/ci/*.test.mjs sibling-convention tests (e.g. check-store-recoverability.test.mjs, check-readiness-reason-codes.test.mjs, and the new scripts/ci/lib/tempdoc-scan.test.mjs) have no auto-discovery runner analogous to scripts/agent-analytics/run-all-tests.mjs (745 D6) — most are never invoked by ci.yml at all, only a hand-picked subset (pack-mcpb, check-mcpb-consistency, check-public-agent-utility) is wired; a new sibling test is silently dead unless someone remembers to add a ci.yml step — `scripts/ci/**/*.test.mjs` (2026-07-17)

### obs:test-check-coverage — Concurrent subagents editing the same worktree make the shared test suite transiently red: a worker
`kind: environment?` `anchor: test_check_coverage.py` `seen: 1` `first: 2026-07-17` `last: 2026-07-17`
- [ ] Concurrent subagents editing the same worktree make the shared test suite transiently red: a worker running the full suite mid-flight saw 6 `test_check_coverage.py` failures that were another worker's in-progress edits, and logged them as "pre-existing" (they were not; the integrated tree is 213/213 green). Worker briefs should scope the acceptance suite to owned files, or the orchestrator should serialize suite runs -- `scripts/sandbox/` (2026-07-17)

### obs:unanchored-general-73 — Sandbox rounds 5 and 6 archived NO service logs (only round 4 has them, hand-copied) — a host-side i
`kind: environment?` `anchor: none` `seen: 1` `first: 2026-07-17` `last: 2026-07-17`
- [ ] Sandbox rounds 5 and 6 archived NO service logs (only round 4 has them, hand-copied) — a host-side investigation into a real search-quality finding had to fall back to round 4's worker.log to answer an ONNX-session question; fixed forward in collect-evidence.ps1 (tempdoc 750), but rounds 5/6's logs are gone for good — `scripts/sandbox/collect-evidence.ps1` (2026-07-17)

### obs:note-observation — A session's observation shard can reach `origin/main` inside ANOTHER session's PR: PR #229 branched
`kind: defect?` `anchor: scripts/agent-analytics/note-observation.mjs` `seen: 1` `first: 2026-07-17` `last: 2026-07-17`
- [ ] A session's observation shard can reach `origin/main` inside ANOTHER session's PR: PR #229 branched from local `main` after this session committed its shard, so its squash carried entry 1 upstream under a different commit identity — producing an add/add conflict when this session's own PR later merged `origin/main` (resolved as the union; no loss). Shards are per-session by design but ride whatever branch happens to contain them — `scripts/agent-analytics/note-observation.mjs` (2026-07-17)

### obs:unanchored-general-74 — Founder finding (2026-07-17): no agent ever proactively flagged the corpus/index-rebuild inefficienc
`kind: lesson?` `anchor: none` `seen: 1` `first: 2026-07-17` `last: 2026-07-17`
- [ ] Founder finding (2026-07-17): no agent ever proactively flagged the corpus/index-rebuild inefficiency (4x ~50min identical rebuilds in 24h; GPU monopolized, develocity externality on all other sessions) — mechanisms: endured-friction-isnt-a-finding bias (agents build supervision AROUND delays instead of filing them), the 716 validity ratchet (reuse pattern-matched to danger, keyed-vs-unkeyed never articulated), dollars instrumented/GPU-hours unmeasured, and the founder's overnight-window workaround signaling the cost as pre-accepted. Instance owned by tempdoc 751; the CLASS fix candidates: (a) GPU-hours/wall-clock ledger on run manifests + periodic projection (duration family precedent), (b) postmortem handle 'friction-you-schedule-around-is-a-finding' in agent-postmortems.md (2026-07-17)

### obs:index-identity — 751 index-cache chain-integration finding (live, 14:49): passing an explicit corpus-dir SUBDIR as th
`kind: defect?` `anchor: scripts/jseval/jseval/index_identity.py` `seen: 2` `first: 2026-07-17` `last: 2026-07-17`
- [ ] 751 index-cache chain-integration finding (live, 14:49): passing an explicit corpus-dir SUBDIR as the cache's corpus axis makes corpus_signature come back empty (dataset-dir mode expects corpus.jsonl+qrels) → cache disables fail-quiet → chain topologies silently lose all caching. The §I.5 corpus-dir assert should also ACCEPT subdir mode (files-mode signature) or resolve the parent; fail-quiet was correct but a one-line WARN naming the remedy would have saved a diagnosis cycle — `scripts/jseval/jseval/index_identity.py` (2026-07-17)
- [ ] 751 §P.3.5 chain-integration spec (live findings, 2026-07-17 14:49-15:00, three attempts): (1) explicit corpus-dir SUBDIR → selector signature empty → fail-quiet disable (needs files-mode or parent resolution + a WARN); (2) two-boot chain topology (publish via jseval-run pass, adopt via wrapper) breaks on F-A corpus_dir_path binding unless publisher and adopter resolve the IDENTICAL path — jseval's default resolution picks tmp/eval-corpora while chains use datasets/<cell>/corpus-dir; (3) tmp/eval-corpora staging carries a .source_signature sidecar that would index as a stray 10002nd doc AND its watched root trips utility-run's stray-root gate against the datasets/ convention. Campaign reverted to fresh-build; integration needs a designed seam (e.g. index-cache warm CLI or publish-from-wrapper) not chain-side improvisation — `scripts/jseval/jseval/index_identity.py` (2026-07-17)

### obs:jvmbaseconventionsplugin — Google Java Format never runs: `enableGjf` defaults false and is set nowhere, and toolchain/local/CI
`kind: defect?` `anchor: build-logic/src/main/kotlin/conventions/JvmBaseConventionsPlugin.kt` `seen: 1` `first: 2026-07-15` `last: 2026-07-15`
- [ ] Google Java Format never runs: `enableGjf` defaults false and is set nowhere, and toolchain/local/CI JDK are all 25, so the `currentMajor < 23` gate always takes the else-branch — spotless on Java is only trimTrailingWhitespace+endWithNewline. Proven: registered tasks are `spotlessJavaSources*`, no `spotlessJava`. Contradicts CLAUDE.md "Build fails on PMD/Spotless violations". Phase-1-era "until GJF compatibility lands" conditional that silently became permanent — `build-logic/src/main/kotlin/conventions/JvmBaseConventionsPlugin.kt:168` (2026-07-15)

### obs:ragcontext — Documented config knob `JUSTSEARCH_RAG_TOP_K` is unwired, not obsolete: it resolves via `ResolvedCon
`kind: defect?` `anchor: modules/app-services/src/main/java/io/justsearch/app/services/conversation/spi/RAGContext.java` `seen: 1` `first: 2026-07-15` `last: 2026-07-15`
- [ ] Documented config knob `JUSTSEARCH_RAG_TOP_K` is unwired, not obsolete: it resolves via `ResolvedConfigBuilder.java:1523` into `Rag.ragTopK` which has zero readers, while the real value is the hardcoded `DEFAULT_TOP_K = 5` that `extractTopK` falls back to. Doc promises "Number of chunks to retrieve for RAG context (default 5)"; setting it does nothing. Unit test `ragTopKClamped()` certifies the inert path — `modules/app-services/src/main/java/io/justsearch/app/services/conversation/spi/RAGContext.java:55` (2026-07-15)

### obs:resolvedconfig — Config dead-surface at scale: 70 of 342 `ResolvedConfig` record components are inert (55 zero call s
`kind: defect?` `anchor: modules/configuration/src/main/java/io/justsearch/configuration/resolved/ResolvedConfig.java` `seen: 1` `first: 2026-07-15` `last: 2026-07-15`
- [ ] Config dead-surface at scale: 70 of 342 `ResolvedConfig` record components are inert (55 zero call sites, 15 test-only) across 2440 java files. Verified not reflectively reached (snapshot mapper writes LinkedHashMap<String,String>, EffectiveConfigController reads resolutions()), no method refs, not read by key, not on the wire. They split into OBSOLETE (delete) vs UNWIRED/shadowed (wire up) — so neither bulk delete nor bulk wire is safe; needs a classification pass — `modules/configuration/src/main/java/io/justsearch/configuration/resolved/ResolvedConfig.java:410` (2026-07-15)

### obs:wholeprogramdeadcodetest — Dead-code gates have a substrate blind spot: `dead-code-jvm` emits only `"kind": "class"` (19 entrie
`kind: defect?` `anchor: modules/dead-code-audit/src/test/java/io/justsearch/deadcode/WholeProgramDeadCodeTest.java` `seen: 1` `first: 2026-07-15` `last: 2026-07-15`
- [ ] Dead-code gates have a substrate blind spot: `dead-code-jvm` emits only `"kind": "class"` (19 entries, report-only/never fails, ratcheted) and `dead-code` wraps Knip for TS exports. Neither covers java MEMBERS, config keys, YAML, or gradle properties — the substrates where the config/GJF/OTLP dead surface actually lives. The whole-program import already sees all visibilities, so member granularity is emit-side, not analysis-side — `modules/dead-code-audit/src/test/java/io/justsearch/deadcode/WholeProgramDeadCodeTest.java:1` (2026-07-15)

### obs:application — Dead phase-1 OTLP block survives byte-identical and was re-parented from top-level `telemetry:` to n
`kind: defect?` `anchor: config/application.yaml` `seen: 1` `first: 2026-07-15` `last: 2026-07-15`
- [ ] Dead phase-1 OTLP block survives byte-identical and was re-parented from top-level `telemetry:` to nested under `index:` during file growth (document indexing nominally owning OTLP export config). No reader: no putYaml binding references export/otlp, and real OTLP wiring in TracingBootstrap is env-var driven — `config/application.yaml:129` (2026-07-15)

### obs:synonyms-en — ADR-0043 per-language-synonym gate cannot see the surviving residue: `check-language-agnostic-analys
`kind: defect?` `anchor: config/synonyms_en.txt` `seen: 1` `first: 2026-07-15` `last: 2026-07-15`
- [ ] ADR-0043 per-language-synonym gate cannot see the surviving residue: `check-language-agnostic-analysis.mjs` scans `synonymDir: "SSOT/catalogs"` with pattern /^synonyms\./, but the phase-1 files that actually survive are `config/synonyms_en.txt` / `synonyms_de.txt` — wrong directory AND wrong name shape (underscore, not dot). The SSOT/catalogs destination they were being migrated TO was deleted; the abandoned source survives unguarded. Only consumer is a permanently-dead branch in docs-validate.mjs (comments-only file gives an empty synonymMap) — `governance/language-agnostic-analysis.v1.json:5` (2026-07-15)

### obs:591-dependency-hygiene-triage — Root cause of the GJF gap is a misread green, not an oversight: tempdoc 236:144 defers google-java-f
`kind: defect?` `anchor: docs/tempdocs/591-dependency-hygiene-triage.md` `seen: 1` `first: 2026-07-15` `last: 2026-07-15`
- [ ] Root cause of the GJF gap is a misread green, not an oversight: tempdoc 236:144 defers google-java-format 1.34.1 deliberately ("Reformats all Java; must be isolated commit"), and tempdoc 591:105 records "spotlessCheck green — no reformat ... rule-neutral" as evidence the bump was safe. But spotlessCheck is green because GJF does not run at all on JDK 25 (see the enableGjf note) — it only checks trailing whitespace/newline. The deferral was priced as "formatting stays as-is"; its actual cost is "formatting is unenforced". Decision needed: accept unenforced java formatting and correct the CLAUDE.md claim, or land the isolated 1.34.1 reformat commit already scoped in 236 — `docs/tempdocs/591-dependency-hygiene-triage.md:105` (2026-07-15)

### obs:embeddingbackfillops — EmbeddingBackfillOps.processChunkEmbeddingBackfill Phase 1 fetches CHUNK_CONTENT via one getDocument
`kind: defect?` `anchor: modules/worker-services/src/main/java/io/justsearch/indexerworker/loop/ops/EmbeddingBackfillOps.java` `seen: 2` `first: 2026-07-12` `last: 2026-07-17`
- [ ] EmbeddingBackfillOps.processChunkEmbeddingBackfill Phase 1 fetches CHUNK_CONTENT via one getDocumentField() call per chunk instead of the batched getDocumentFieldsBatch() CombinedEnrichmentBackfillOps already uses — noticed while designing tempdoc 720 (P1a prepend) — `modules/worker-services/src/main/java/io/justsearch/indexerworker/loop/ops/EmbeddingBackfillOps.java:350-367` (2026-07-12)
- [ ] FAILED-status chunks silently stop being retried and remain vector/splade-less with no serve-time gate — EmbeddingBackfillOps/SpladeBackfillOps select PENDING only; retry-count fields exist but nothing surfaces or re-drives the FAILED population (749 scope-investigation census site 8) — `modules/worker-services/.../loop/ops/EmbeddingBackfillOps.java:325` (2026-07-17)

### obs:run-error — jseval run --dataset mixed/legal-clerc-200 --max-queries 0 --pipeline --start-backend --clean --json
`kind: defect?` `anchor: scripts/jseval/jseval/run.py` `seen: 1` `first: 2026-07-17` `last: 2026-07-17`
- [ ] jseval run --dataset mixed/legal-clerc-200 --max-queries 0 --pipeline --start-backend --clean --json completed exit 0 but wrote NO eval-results run dir / summary.json (2026-07-17 10:26-10:30 run; enrichment completed and was stamped, backend stopped 3s later) — summary write appears skipped or crashed silently on the no-queries path — `scripts/jseval/jseval/run.py` (2026-07-17)

### obs:unanchored-missing-5 — Manual observations.d conflict resolution can silently drop content: merge 603cc5bf claimed 'content
`kind: defect?` `anchor: none` `seen: 1` `first: 2026-07-17` `last: 2026-07-17`
- [ ] Manual observations.d conflict resolution can silently drop content: merge 603cc5bf claimed 'content-preserving' fold of 3 deleted shards but shard cfa87fbc's record-merge mis-link bullet (from 5a90bf44) was never folded — verified absent from observations.md by exact-string grep; rescued via worktree-rescue-720-docs. Process gap: content-preservation claims in fold merges are checked per-shard, not per-bullet — `docs/observations.md` (2026-07-17)

### obs:run-gh — run-gh.mjs checks-wait races a just-pushed catch-up commit: it matches the PREVIOUS commit's green r
`kind: defect?` `anchor: scripts/dev/run-gh.mjs` `seen: 1` `first: 2026-07-17` `last: 2026-07-17`
- [ ] run-gh.mjs checks-wait races a just-pushed catch-up commit: it matches the PREVIOUS commit's green run and exits 0 while the new run is still pending — observed twice in one merge session (PRs 237, 239); it should key on the head-SHA's runs, not the PR's latest completed run — `scripts/dev/run-gh.mjs` (2026-07-17)

### obs:test-utility-evidence — test_historical_fixture_semantic_digest_repinned_after_624_itt_change RED on confirmatory-campaign —
`kind: environment?` `anchor: scripts/jseval/tests/test_utility_evidence.py` `seen: 1` `first: 2026-07-17` `last: 2026-07-17`
- [ ] test_historical_fixture_semantic_digest_repinned_after_624_itt_change RED on confirmatory-campaign — pre-existing WIP activation of utility-claim-policy.v1.json (draft->active, populated required_strata) changes the fixture claim_verdict/semantic_digest; digest needs re-pinning by the policy-activation author — `scripts/jseval/tests/test_utility_evidence.py:356` (2026-07-17)

### obs:agent-utility-inspect-drift — corpus-root review nit (follow-up): root mode ATTESTS the staged corpus-dir hash (corpus_dir_files_s
`kind: follow-up?` `anchor: scripts/jseval/jseval/agent_utility_inspect.py` `seen: 1` `first: 2026-07-17` `last: 2026-07-17`
- [ ] corpus-root review nit (follow-up): root mode ATTESTS the staged corpus-dir hash (corpus_dir_files_signature) but never enforces corpus-dir ≡ corpus.jsonl derivation — a stale explosion would pass all checks while agents search divergent text; add a fail-closed derivation check (count + sampled-content or a derivation signature) to root mode — `scripts/jseval/jseval/agent_utility_inspect.py:1143` (2026-07-17)

### obs:index-cache-cmd — 751 warm bug (live, confirmatory launch 22:23): index-cache warm --corpus-dir ran TWO ingest passes
`kind: defect?` `anchor: scripts/jseval/jseval/commands/index_cache_cmd.py` `seen: 1` `first: 2026-07-17` `last: 2026-07-17`
- [ ] 751 warm bug (live, confirmatory launch 22:23): index-cache warm --corpus-dir ran TWO ingest passes of the same root within one backend lifetime — the readiness doc-count floor ACCUMULATED (1001+1001=2002 expected) while path-dedup keeps the index at 1001 → unmeetable readiness wall, warm spun 25+ min GPU-idle past the 600s health timeout. Two sub-bugs: (a) cumulative floor across repeated same-root ingest requests, (b) the warm's second ingest pass itself; campaign reverted to fresh-build — `scripts/jseval/jseval/commands/index_cache_cmd.py` (2026-07-17)

### obs:step2-budget-guard — Budget-guard design lesson (fired 2x): max-extrapolation running guards over-project when the most e
`kind: lesson?` `anchor: scripts/jseval/step2-budget-guard.py` `seen: 1` `first: 2026-07-17` `last: 2026-07-17`
- [ ] Budget-guard design lesson (fired 2x): max-extrapolation running guards over-project when the most expensive dataset calibrates first — order campaigns cheapest-first, or extrapolate from the MEAN of known estimates with the cap as the safety margin — `scripts/jseval/step2-budget-guard.py` (2026-07-17)

### obs:unanchored-flake-4 — Flaky CI lane: 'Unit tests (platform-contracts)' failed on PR #245 whose diff is a .bat + two markdo
`kind: environment?` `anchor: none` `seen: 1` `first: 2026-07-17` `last: 2026-07-17`
- [ ] Flaky CI lane: 'Unit tests (platform-contracts)' failed on PR #245 whose diff is a .bat + two markdown files (cannot affect Java tests; main green at base) — rerun-once applied; if this lane flakes again it needs its own investigation — CI run 29614120711 (2026-07-17)

### obs:agent-utility-inspect-flake — verified_tool_surface claim gate is structurally unsatisfiable at current SDK flake rate: observed_m
`kind: environment?` `anchor: scripts/jseval/jseval/agent_utility_inspect.py` `seen: 1` `first: 2026-07-18` `last: 2026-07-18`
- [ ] verified_tool_surface claim gate is structurally unsatisfiable at current SDK flake rate: observed_mcp_tool_surface_hash is None whenever the agent SDK's get_mcp_status() returns nothing (known-flaky, tempdoc 675/725) — confirmatory campaign saw 4-12 unverified B-cells per 60-cell stratum (~8%); P(all ~240 cells verified) ≈ 0. Needs either capture hardening (retry/fallback surface evidence) or a founder-ratified policy amendment (e.g. minimum surface-verification rate + single-hash consistency) — `scripts/jseval/jseval/agent_utility_inspect.py:848` (2026-07-18)

### obs:chain-confirm — Claude Code CLI auto-updated 2.1.212→2.1.214 mid-campaign-night, splitting agent_cohort_key between
`kind: defect?` `anchor: scripts/jseval/chain-confirm.bat` `seen: 1` `first: 2026-07-18` `last: 2026-07-18`
- [ ] Claude Code CLI auto-updated 2.1.212→2.1.214 mid-campaign-night, splitting agent_cohort_key between the v4 strata and the email-1k rerun (incident #6, tempdoc 624) — future campaign chains should pin harness identity for the whole cohort window (e.g. DISABLE_AUTOUPDATER=1 in the chain env) so a multi-run cohort can't be torn by a background update — `scripts/jseval/chain-confirm.bat` env block (2026-07-18)

### obs:utility-claim-policy — ITT usage evidence incomplete for exhausted cells → composed cost_usd/token efficiency intervals una
`kind: defect?` `anchor: scripts/jseval/jseval/utility_claim_policy.py` `seen: 1` `first: 2026-07-18` `last: 2026-07-18`
- [ ] ITT usage evidence incomplete for exhausted cells → composed cost_usd/token efficiency intervals unavailable ("incomplete ITT usage evidence") → per-stratum outcome caps at adoption-only even where ITT accuracy delta is significant (legal-1k +0.217 p=0.001); capture usage for exhausted cells or define a pre-registered imputation for the efficiency family — `scripts/jseval/jseval/utility_claim_policy.py:505` (2026-07-18)

### obs:gen-scorecard — gen-scorecard.mjs and gen-public-benchmark.mjs are wired into NO CI workflow — scorecard.md and meth
`kind: defect?` `anchor: gen-scorecard.mjs` `seen: 1` `first: 2026-07-18` `last: 2026-07-18`
- [ ] gen-scorecard.mjs and gen-public-benchmark.mjs are wired into NO CI workflow — scorecard.md and methodology.md silently drifted a full release cycle (2026-07-01 → 2026-07-16, caught by the 2026-07-18 numbers audit); wire both --check modes into the public-claims CI job — `.github/workflows/ci.yml` public-claims job (2026-07-18)

### obs:utility — 758 reviewer MINORs: (a) utility-run WITHOUT --calibration has no CLI-drift assert (chain path cover
`kind: defect?` `anchor: scripts/jseval/jseval/commands/utility.py` `seen: 1` `first: 2026-07-21` `last: 2026-07-21`
- [ ] 758 reviewer MINORs: (a) utility-run WITHOUT --calibration has no CLI-drift assert (chain path covered; bare path relies on DISABLE_AUTOUPDATER), (b) calibration SHA-binding detects commit movement but not dirty-tree drift at same HEAD (git_dirty stamped, unchecked) — `scripts/jseval/jseval/commands/utility.py:349-357` (2026-07-21)

### obs:utility-recompose — 757 reviewer MINOR: taint condition 'b_exhausted AND usage_truncated' — the conjunct is redundant to
`kind: defect?` `anchor: scripts/jseval/jseval/utility_recompose.py` `seen: 1` `first: 2026-07-21` `last: 2026-07-21`
- [ ] 757 reviewer MINOR: taint condition 'b_exhausted AND usage_truncated' — the conjunct is redundant today (classification implies both) but becomes a hole if stamp/classification ever diverge; gate on usage_truncated alone — `scripts/jseval/jseval/utility_recompose.py:243`. 755 reviewer MINOR: sanitize emits surface_evidence/mcp_surface_fallback nulls unconditionally (composed-record byte-identity holds; evidence-line layer differs from 757's omitted-when-absent style) — `scripts/jseval/jseval/utility_evidence.py:239-240` (2026-07-21)

### obs:queries — jseval retrieval-eval consumes only MultiHop-format queries (q['query'] + evidence_list); 635-corpor
`kind: defect?` `anchor: queries.json` `seen: 1` `first: 2026-07-18` `last: 2026-07-18`
- [ ] jseval retrieval-eval consumes only MultiHop-format queries (q['query'] + evidence_list); 635-corpora queries.json (evidence_ids, and needle-burial-v1's has null 'query') yield empty queries → 0% metrics silently. Discovered running the 749 reachability eval — `scripts/jseval/jseval/agent_retrieval_eval.py:load_queries` (2026-07-18)

### obs:ingest — main checkout tmp/eval-corpora/mixed/legal-clerc-200 holds 199 materialized .txt files vs 198 corpus
`kind: defect?` `anchor: scripts/jseval/jseval/ingest.py` `seen: 1` `first: 2026-07-17` `last: 2026-07-17`
- [ ] main checkout tmp/eval-corpora/mixed/legal-clerc-200 holds 199 materialized .txt files vs 198 corpus.jsonl docs with a present .source_signature sidecar — either an orphan-file hole in the 635 verified-projection guard (skip_existing never deletes files when the corpus shrinks) or a pre-guard stale file; a materialize-clears-orphans check would close the class — `scripts/jseval/jseval/ingest.py:300` (2026-07-17)

### obs:unanchored-missing-8 — Manual observations.d conflict resolution can silently drop content: merge 603cc5bf claimed 'content
`kind: defect?` `anchor: none` `seen: 1` `first: 2026-07-17` `last: 2026-07-17`
- [ ] Manual observations.d conflict resolution can silently drop content: merge 603cc5bf claimed 'content-preserving' fold of 3 deleted shards but shard cfa87fbc's record-merge mis-link bullet (from 5a90bf44) was never folded — verified absent from observations.md by exact-string grep; rescued via worktree-rescue-720-docs. Process gap: content-preservation claims in fold merges are checked per-shard, not per-bullet — `docs/observations.md` (2026-07-17)

### obs:unanchored-general-79 — Installer size stated inconsistently across surfaces: 853 MB (README.md:57) vs ~748 MB (.claude/skil
`kind: defect?` `anchor: none` `seen: 1` `first: 2026-07-21` `last: 2026-07-21`
- [ ] Installer size stated inconsistently across surfaces: 853 MB (README.md:57) vs ~748 MB (.claude/skills/installer/SKILL.md:16) vs 741 MB (build-installer.yml:202) — found during tempdoc 760 Phase-1 gap audit (2026-07-21)

### obs:732-response-surface-residuals — 732's concise-default decision attributes the halved Reads-per-search to the text-tier Preview line,
`kind: defect?` `anchor: docs/tempdocs/732-response-surface-residuals.md` `seen: 1` `first: 2026-07-21` `last: 2026-07-21`
- [ ] 732's concise-default decision attributes the halved Reads-per-search to the text-tier Preview line, but delivery-tier measurement only began at campaign V (725:1714) and shows 98.9% structured-json — the Preview line is not delivered to structured-preferring clients, so the D->T->U Reads-halving (725:1492) may be attributable to structuredContent excerpts instead. 732's "no measurement changes it" conclusion may rest on an undelivered component — `docs/tempdocs/732-response-surface-residuals.md:126` (2026-07-21)

### obs:test-delivery-tier-735 — RESOLVED — NOT a flake. jseval `test_delivery_tier_735.py::test_delivered_fields_on_answer_fixture_t
`kind: environment?` `anchor: scripts/jseval/tests/test_delivery_tier_735.py` `seen: 1` `first: 2026-07-21` `last: 2026-07-21`
- [x] RESOLVED — NOT a flake. jseval `test_delivery_tier_735.py::test_delivered_fields_on_answer_fixture_top_level_only` failed once in a full-suite run, then passed everywhere. Cause: a concurrent-edit race inside one worktree, not test-order dependence. The orchestrator re-captured the recorded fixture (answer `facets` removed at surface 0.5.0) at **17:37:24** and updated that test's assertion (`facets: True` → `False`) at **17:38:17** — a **53-second window** in which the fixture on disk and the assertion disagreed. A subagent's full-suite run started inside that window and hit exactly that test. Lesson (orchestration, mine): do not run a live fixture refresh in a worktree while a delegated agent is running the suite there — two agents mutating one worktree concurrently produces exactly this phantom. The subagent was right to refuse to wave it through; it simply could not see the concurrent edits. No code defect; nothing to fix — `scripts/jseval/tests/test_delivery_tier_735.py:81` (2026-07-21)

### obs:delivery-tier-probe-735 — delivery_tier_probe_735.py's `_LOCAL_PATH_RE` redaction only matches drive-letter paths (`X:\...`) —
`kind: defect?` `anchor: scripts/jseval/experiments/delivery_tier_probe_735.py` `seen: 1` `first: 2026-07-21` `last: 2026-07-21`
- [ ] delivery_tier_probe_735.py's `_LOCAL_PATH_RE` redaction only matches drive-letter paths (`X:\...`) — a UNC capture (`\server\share`) would survive into a committed fixture. Not hit by any current capture; found during the 770 pre-push scan — `scripts/jseval/experiments/delivery_tier_probe_735.py` (2026-07-21)

### obs:unanchored-general-80 — bundleSidecarResources fails config-cache STORE due to pre-existing config-cache-incompatible deps (
`kind: environment?` `anchor: none` `seen: 1` `first: 2026-07-21` `last: 2026-07-21`
- [ ] bundleSidecarResources fails config-cache STORE due to pre-existing config-cache-incompatible deps (headlessDist uses configurations.runtimeClasspath script-object refs; stageOrtCudaVariant Sync; generateWorkerAotCache captures a Sync task) — installer packaging path is not config-cacheable; run with --no-configuration-cache — `modules/ui/build.gradle.kts:308,753,~1000` (2026-07-21)

### obs:unanchored-general-81 — jseval debt from the 767-771 arc: known-RED correction-probe pair (restore data file or retire); per
`kind: defect?` `anchor: none` `seen: 1` `first: 2026-07-22` `last: 2026-07-22`
- [ ] jseval debt from the 767-771 arc: known-RED correction-probe pair (restore data file or retire); per-query eval artifacts capped at depth-10 (analyses wanted k>=20 — make depth configurable); no per-call cost invoicing (spend figures are cap-bounded estimates); index-cache entries not reusable across commits for certification-shaped flows (2026-07-22)

### obs:unanchored-general-82 — platform lessons from the 767 certification arc for agent-lessons.md rider: tracked background tasks
`kind: lesson?` `anchor: none` `seen: 1` `first: 2026-07-22` `last: 2026-07-22`
- [ ] platform lessons from the 767 certification arc for agent-lessons.md rider: tracked background tasks killed at ~60min and TaskStop does not kill child bash loops (detached Start-Process driver + self-terminating <590s polls is the working pattern); subagents park mid-campaign to 'wait for monitors' unless briefed to wait in-turn — two stalls this arc (2026-07-22)

### obs:unanchored-drift-18 — Head and Worker classpaths ship 4 same-library-different-version jar pairs (jackson-core/databind 3.
`kind: defect?` `anchor: none` `seen: 1` `first: 2026-07-22` `last: 2026-07-22`
- [ ] Head and Worker classpaths ship 4 same-library-different-version jar pairs (jackson-core/databind 3.1.0 vs 2.20.0, kotlin-stdlib 2.4.0 vs 2.2.21, commons-text 1.14.0 vs 1.15.0) — cross-process version drift, found during 772 installer itemization of `lib/` vs `lib/worker/` (CI artifact run 29874382035) (2026-07-22)

### obs:unanchored-general-83 — Installer itemization (772 §I) shows lucene-core/lucene-analysis-common on the HEAD shipped classpat
`kind: defect?` `anchor: none` `seen: 1` `first: 2026-07-22` `last: 2026-07-22`
- [ ] Installer itemization (772 §I) shows lucene-core/lucene-analysis-common on the HEAD shipped classpath (lib/, not lib/worker/) — modules/ui/build.gradle.kts:46 depends on adapters-lucene, :124-125 runtimeOnly lucene — tier-register row 1's justification wording ('Lucene types are not on Head's classpath') doesn't match the shipped artifact; reconcile wording vs reality (the ArchUnit test presumably checks something narrower) (2026-07-22)

### obs:unanchored-general-84 — justsearch-releases THIRD_PARTY_NOTICES.txt has no NVIDIA entry for the CUDA/cuDNN redistributables 
`kind: environment?` `anchor: none` `seen: 1` `first: 2026-07-22` `last: 2026-07-22`
- [ ] justsearch-releases THIRD_PARTY_NOTICES.txt has no NVIDIA entry for the CUDA/cuDNN redistributables in ort-cuda-runtime-12.4.zip / cudnn-9-runtime-12.4.zip (NVIDIA redistributable-list EULA terms) — pre-existing gap noticed while adding the ONNX Runtime MIT notice (772 §J) (2026-07-22)

### obs:lifecyclecontracttest — LifecycleContractTest (modules/ui) flakes under parallel full-suite load on dev machine: 2/10 tests 
`kind: environment?` `anchor: modules/ui/src/test/java/io/justsearch/ui/api/LifecycleContractTest.java` `seen: 1` `first: 2026-07-22` `last: 2026-07-22`
- [ ] LifecycleContractTest (modules/ui) flakes under parallel full-suite load on dev machine: 2/10 tests (statusReadinessDegradesIndexServingWhenThroughputStalls :495, statusReadinessPendingInferenceOfflineWhenRuntimeIdle :398) hit 3s HttpTimeoutException against in-process LocalApiServer, green in isolation — same class as the 'Flaky IPC tests' pitfall; consider awaitPort-style tolerance or longer client timeout — `modules/ui/src/test/java/io/justsearch/ui/api/LifecycleContractTest.java:398,495` (2026-07-22)

## Parked

### obs:actionledgerprojection — ActionLedgerProjection.deterministicId `:`-join is injection-safe only because all-but-last discrimi
`kind: follow-up?` `anchor: modules/app-observability/src/main/java/io/justsearch/app/observability/ledger/ActionLedgerProjection.java` `seen: 2` `first: 2026-05-27` `last: 2026-05-27` `status: parked (deferred — revisit per condition note (triage 2026-07-12))`
- [ ] ActionLedgerProjection.deterministicId `:`-join is injection-safe only because all-but-last discriminators come from colon-free NamespacedId/enum domains; not structurally guaranteed if a free-form field is ever added before the last position — consider length-prefix or escaping if discriminator set grows — `modules/app-observability/src/main/java/io/justsearch/app/observability/ledger/ActionLedgerProjection.java:142` (2026-05-27)
- [ ] `ActionLedgerProjection.deterministicId` colon-join is collision-safe only because non-final discriminators are colon-free (NamespacedId/enum); adding a free-form discriminator before the last position could re-introduce id aliasing — consider length-prefixing or escaping if that changes — `modules/app-observability/src/main/java/io/justsearch/app/observability/ledger/ActionLedgerProjection.java:142` (2026-05-27)

### obs:readinessnotice — Banner cause wording for `lambdamart.not_configured` uses the generic fallback ("Degraded: lambdamar
`kind: follow-up?` `anchor: modules/ui-web/src/shell-v0/state/readinessNotice.ts` `seen: 2` `first: 2026-06-12` `last: 2026-06-17` `status: parked (deferred — revisit per condition note (triage 2026-07-12))`
- [ ] Banner cause wording for `lambdamart.not_configured` uses the generic fallback ("Degraded: lambdamart.not_configured"); consider a CAUSE_ROWS entry or excluding LAMBDAMART from the reindex-banner causes (it is DEGRADED-capped noise per StatusLifecycleHandler) — `modules/ui-web/src/shell-v0/state/readinessNotice.ts` (2026-06-12)
- [ ] Readiness banner reads "Semantic search degraded — Showing keyword results" while doc-level dense AUTO search actually serves HYBRID (proven live, 598 PART XI/§A4) — the §53 capability-vs-actuality split: banner keys off passage(chunk) embeddings + LambdaMART, not doc-level dense availability. Consider scoping the banner copy to passage-grounded Q&A vs document search — `modules/ui-web/src/shell-v0/state/readinessNotice.ts` (2026-06-17)

### obs:events — `claude-notifications-go` plugin doubles every Stop event (~500-565ms per Stop) — runs alongside `di
`kind: defect?` `anchor: tmp/agent-telemetry/events.ndjson` `seen: 1` `first: 2026-04-28` `last: 2026-04-28` `status: parked (deferred — revisit per condition note (triage 2026-07-12))`
- [ ] `claude-notifications-go` plugin doubles every Stop event (~500-565ms per Stop) — runs alongside `dispatch.mjs`. If Stop latency ever becomes a complaint, this is the surface to look at; user-scope plugin in `~/.claude/settings.json`. — `tmp/agent-telemetry/events.ndjson` (2026-04-28)

### obs:remoteindexingjobsbridge — Slice 445 follow-up: RemoteIndexingJobsBridge has no auto-reconnect on stream onError. Stop emitting
`kind: follow-up?` `anchor: RemoteIndexingJobsBridge.java` `seen: 1` `first: 2026-05-06` `last: 2026-05-06` `status: parked (deferred — revisit per condition note (triage 2026-07-12))`
- [ ] Slice 445 follow-up: RemoteIndexingJobsBridge has no auto-reconnect on stream onError. Stop emitting until next start(). Acceptable for V1 (worker is long-running, channel reconnect handles transient blips); revisit if a future TABULAR Resource demands stricter freshness — modules/app-services/src/main/java/io/justsearch/app/services/worker/RemoteIndexingJobsBridge.java:170 (2026-05-06)

### obs:pluginregistry — V1.5 alpha: `customElements.define(tag, Class)` cannot be un-defined per HTML spec. Plugin uninstall
`kind: environment?` `anchor: modules/ui-web/src/shell-v0/plugin-api/PluginRegistry.ts` `seen: 1` `first: 2026-05-07` `last: 2026-05-07` `status: parked (deferred — revisit per condition note (triage 2026-07-12))`
- [ ] V1.5 alpha: `customElements.define(tag, Class)` cannot be un-defined per HTML spec. Plugin uninstall removes catalog entries + surface-port handlers, but the class registration persists in `customElements`. Hot-reload re-installs use the same registration; re-define throws. Mitigated by `if (!customElements.get(tag))` guard. V1.5.1 polish: see 470 §B.A.4 / §B.D for sandboxing roadmap (Compartment-Loader integration). — `modules/ui-web/src/shell-v0/plugin-api/PluginRegistry.ts` (2026-05-07)

### obs:searchexecutor — Decompose SearchExecutor (990→1031 LOC, grandfathered) — extract chunk-merge subsystem (mergeChunkRe
`kind: follow-up` `anchor: modules/worker-services/src/main/java/io/justsearch/indexerworker/services/execute/SearchExecutor.java` `seen: 2` `first: 2026-05-26` `last: 2026-07-11` `status: parked (deferred — revisit per condition note (triage 2026-07-12))`
- [ ] Decompose SearchExecutor (990→1031 LOC, grandfathered) — extract chunk-merge subsystem (mergeChunkResults / executeChunkBranchFusion / collapse helpers, ~250 LOC) into a ChunkMergeExecutor collaborator — `modules/worker-services/src/main/java/io/justsearch/indexerworker/services/execute/SearchExecutor.java` (2026-05-26)
- [ ] 717 hypothesis CORRECTED (via 718 live smoke): the intermittent chunk-death is QUERY-TIME not build-time — degenerate build had 4293 chunk docs / 4293 embeddings completed / 100% coverage yet chunk_merge fired for 0 queries + vector nDCG 0.34. Investigate SearchExecutor/SearchPlanner chunk-merge activation, not the enrichment write path — `modules/worker-services/.../execute/SearchExecutor.java:527` (2026-07-11)

### obs:search — 553 Phase 4b (552 FE barrel→knowledge_pb migration) deferred: knowledge_pb SearchTrace is a branded
`kind: follow-up?` `anchor: modules/ui-web/src/api/domains/search.ts` `seen: 1` `first: 2026-05-27` `last: 2026-05-27` `status: parked (deferred — revisit per condition note (triage 2026-07-12))`
- [ ] 553 Phase 4b (552 FE barrel→knowledge_pb migration) deferred: knowledge_pb SearchTrace is a branded Message<> type — plain JSON isn't assignable, so type-only re-point fails typecheck; the real path-A migration (fromJson) re-architects the FE search parse path, is user-visible (browser-validate), and is opt-in per capability-vs-mandate. Owned by tempdoc 552. — `modules/ui-web/src/api/domains/search.ts` (2026-05-27)

### obs:tasklist — 550 Fix-E follow-ups (deferred, low value): (a) rail `queued` count chip has no drill-down to list w
`kind: follow-up?` `anchor: TaskList.ts` `seen: 1` `first: 2026-05-28` `last: 2026-05-28` `status: parked (deferred — revisit per condition note (triage 2026-07-12))`
- [ ] 550 Fix-E follow-ups (deferred, low value): (a) rail `queued` count chip has no drill-down to list which files are queued (TaskList.ts); (b) main checkout modules/ui/build/install holds worktree jars from a verification deploy — restore via `./gradlew build` from the main checkout. (2026-05-28)

### obs:schemas-general — 564 Phase 3 follow-up: migrate the agent session SNAPSHOT surface (`GET /api/chat/sessions/{id}`, `/
`kind: follow-up?` `anchor: modules/ui-web/src/api/schemas.ts` `seen: 1` `first: 2026-06-03` `last: 2026-06-03` `status: parked (deferred — revisit per condition note (triage 2026-07-12))`
- [ ] 564 Phase 3 follow-up: migrate the agent session SNAPSHOT surface (`GET /api/chat/sessions/{id}`, `/session/last`, transcript) off the hand `.loose()` AgentSessionSnapshotSchema to a record→schema→Zod projection — deferred this pass because it returns the full free-form session meta (messages/agentProfiles/handoffHistory) and risks wire changes to the resume path — `modules/ui-web/src/api/schemas.ts` / `AgentController.handleSessionDetail` (2026-06-03)

### obs:indexing — 564 Phase 5 follow-up: migrate the remaining indexing FE surfaces off raw casts — the substrate fail
`kind: follow-up?` `anchor: modules/ui-web/src/api/domains/indexing.ts` `seen: 1` `first: 2026-06-03` `last: 2026-06-03` `status: parked (deferred — revisit per condition note (triage 2026-07-12))`
- [ ] 564 Phase 5 follow-up: migrate the remaining indexing FE surfaces off raw casts — the substrate failed-jobs/roots variants (`handleListFailedJobsSubstrate`/`handleRootsSubstrate`, which carry the pathHash→path resolution), `suggested-roots`, and `excludes/apply` — to record→schema→Zod parse-boundary validation; only the legacy `/api/indexing/failed-jobs` surface was migrated this pass — `modules/ui-web/src/api/domains/indexing.ts` (2026-06-03)

### obs:evidenceprojection — 565 ⑤ grounding-coverage indicator (design-feature, specified — the "presentation can outrun groundi
`kind: follow-up?` `anchor: evidenceProjection.ts` `seen: 2` `first: 2026-06-05` `last: 2026-07-15` `status: parked (deferred — revisit per condition note (triage 2026-07-12))`
- [ ] 565 ⑤ grounding-coverage indicator (design-feature, specified — the "presentation can outrun grounding" answer): surface "M of T sentences grounded" so polish can't lend false confidence to thin grounding. HONEST approach (avoid FE/​backend sentence-split inconsistency): have `AgentCitationResolver` (which already splits the answer into sentences to match) return the TOTAL sentence count; carry it on `AgentDone` (a `groundedSentenceTotal` field) alongside the existing `citations`; FE shows `answerCitations.length` / total. The RAG path already computes `sentencesMatched/sentencesTotal` (`CitationMatchResult`) + a tiered `EvidenceScore` (`evidenceProjection.ts`) to mirror. (2026-06-05)
- [ ] Disclosure leak (tempdoc 728-class): `answerFrameLabel()` renders 'Based on your documents — per-sentence grounding not verified' unconditionally — it never consults uiMode, so Simple-mode users see technical vocabulary. Introduced into the settled zero-cite path by tempdoc 720 / PR #171 reclassifying grounded->sourced. Out of 728's declared scope (evidence surface was deemed conformant); is live evidence FOR 728's thesis that disclosure needs a gate, not review — `modules/ui-web/src/shell-v0/components/chat/evidenceProjection.ts:182` (2026-07-15)

### obs:tokeneditorplugin-general — Token Editor nudge + role 'fail' badge are near-unreachable at WCAG-AA floor 4.5: deriveForeground p
`kind: follow-up?` `anchor: modules/ui-web/src/shell-v0/plugins/token-editor/TokenEditorPlugin.ts` `seen: 1` `first: 2026-06-15` `last: 2026-06-15` `status: parked (deferred — revisit per condition note (triage 2026-07-12))`
- [ ] Token Editor nudge + role 'fail' badge are near-unreachable at WCAG-AA floor 4.5: deriveForeground picks optimal black/white (min achievable ~4.58:1 ≥ 4.5), so a role essentially never reports !meets — the nudge (576 §6 B6) only fires if a role's floor is raised above ~4.58 (e.g. to AAA 7). Consider re-targeting the nudge to AAA or the APCA signal to make it useful — `modules/ui-web/src/shell-v0/plugins/token-editor/TokenEditorPlugin.ts` + `themes/themeRoles.ts` (2026-06-15)

### obs:routemanifestcontroller — Investigate GET /api/meta/routes returning HTTP 500 on a dev stack at a fuller/other route set (Rout
`kind: follow-up?` `anchor: modules/ui/src/main/java/io/justsearch/ui/api/RouteManifestController.java` `seen: 1` `first: 2026-06-15` `last: 2026-06-15` `status: parked (deferred — revisit per condition note (triage 2026-07-12))`
- [ ] Investigate GET /api/meta/routes returning HTTP 500 on a dev stack at a fuller/other route set (RouteManifestController.build) — my code is null-safe & returned 200/201 live this session; foreign-stack 500 unreproducible from my side. Consider per-route resilience so the diagnostic endpoint degrades vs 500s — `modules/ui/src/main/java/io/justsearch/ui/api/RouteManifestController.java` (2026-06-15)

### obs:http — Demo mode (`?demo=true`) is orphaned from the retired React app — `resolveApiEndpoint` (src/api/http
`kind: follow-up?` `anchor: http.ts` `seen: 1` `first: 2026-06-19` `last: 2026-06-19` `status: parked (deferred — revisit per condition note (triage 2026-07-12))`
- [ ] Demo mode (`?demo=true`) is orphaned from the retired React app — `resolveApiEndpoint` (src/api/http.ts) has no `?demo`→'demo' path, so the demo handling in src/api/domains/*/streams.ts + src/mocks/fixtures.mjs never fires for the live shell-v0 boot. Re-wiring it would restore a valuable no-backend data mode for fast offline UI iteration (tempdoc 615 §11 candidate). (2026-06-19)

### obs:control — PRODUCT FOLLOW-UP (615 §43.4, dev-noise not a11y defect): jf-control's 559 self-check false-positive
`kind: follow-up?` `anchor: modules/ui-web/src/shell-v0/components/Control.ts` `seen: 1` `first: 2026-06-21` `last: 2026-06-21` `status: parked (deferred — revisit per condition note (triage 2026-07-12))`
- [ ] PRODUCT FOLLOW-UP (615 §43.4, dev-noise not a11y defect): jf-control's 559 self-check false-positives on the doc-recommended slot-text-only `jf-button` pattern → dev-console noise on every Settings render. Fix: give the 2 buttons a `label` (the `Revoke` pattern, `SettingsSurface.ts:2160`; WCAG-2.5.3-clean since label==visible) OR refine the self-check to account for slotted content — `modules/ui-web/src/shell-v0/components/Control.ts:545`. (2026-06-21)

### obs:unanchored-general — `claude-code-warp` marketplace registered in `~/.claude/settings.json` `extraKnownMarketplaces` but
`kind: defect?` `anchor: none` `seen: 1` `first: 2026-04-28` `last: 2026-04-28` `status: parked (deferred — revisit per condition note (triage 2026-07-12))`
- [ ] `claude-code-warp` marketplace registered in `~/.claude/settings.json` `extraKnownMarketplaces` but no plugin from it is enabled — dead marketplace registration. — `~/.claude/settings.json` (2026-04-28)

### obs:unanchored-general-2 — Smoke item 7a end-to-end unverified — head heap pressure for 60s requires either lowered -Xmx on run
`kind: defect?` `anchor: none` `seen: 1` `first: 2026-05-05` `last: 2026-05-05` `status: parked (deferred — revisit per condition note (triage 2026-07-12))`
- [ ] Smoke item 7a end-to-end unverified — head heap pressure for 60s requires either lowered -Xmx on runHeadlessEval or a head-side allocation harness; none exists. Rule engine substrate is unit-tested but live dwell-time path is unvalidated — `modules/app-services/.../rules` (2026-05-05)

### obs:unanchored-general-3 — 442 follow-up: FE-side click-time arg prompting for OperationInvocation recoveries (e.g., core.bulk-
`kind: follow-up?` `anchor: none` `seen: 1` `first: 2026-05-08` `last: 2026-05-08` `status: parked (deferred — revisit per condition note (triage 2026-07-12))`
- [ ] 442 follow-up: FE-side click-time arg prompting for OperationInvocation recoveries (e.g., core.bulk-reindex(corpusIds)) — deferred from impl-B closure (2026-05-08)

### obs:unanchored-general-4 — 508 §13 verification scoreboard (2026-05-18): live-verified on running worktree backend port 33221 →
`kind: follow-up?` `anchor: none` `seen: 1` `status: parked (deferred — revisit per condition note (triage 2026-07-12))`
- [ ] 508 §13 verification scoreboard (2026-05-18): live-verified on running worktree backend port 33221 → V1 Phase B dispatcher path ✓, V2 audience filter (3 branches) ✓, V4 IntentRouter routing via fallback ✓, V5 selection bridge kind propagation ✓, V6 profile-switch theme rebind ✓. V3 file-size cap deferred (Tauri-only — `scan_plugins` short-circuits in browser dev mode; needs Tauri shell build).

### obs:unanchored-general-6 — 635 multilingual member: German questions are clean but answer VALUES are English (_ATTRS pool, e.g.
`kind: defect?` `anchor: none` `seen: 1` `first: 2026-06-23` `last: 2026-06-23` `status: parked (deferred — revisit per condition note (triage 2026-07-12))`
- [ ] 635 multilingual member: German questions are clean but answer VALUES are English (_ATTRS pool, e.g. 'the year 1602') — for a fully-authentic multilingual member the attribute pool should be localized (2026-06-23)

### obs:unanchored-general-7 — First-run search degraded window: a fresh/legacy Lucene index logs 'Embedding compatibility: BLOCKED
`kind: defect?` `anchor: none` `seen: 1` `first: 2026-06-23` `last: 2026-06-23` `status: parked (deferred — revisit per condition note (triage 2026-07-12))`
- [ ] First-run search degraded window: a fresh/legacy Lucene index logs 'Embedding compatibility: BLOCKED_LEGACY (no embedding fingerprint)' and blocks hybrid/vector queries until an auto-forced REBUILDING reindex completes — during that window the DEFAULT (hybrid) search returns weak/empty results while only mode:text BM25 works. Self-healing, but worth confirming first-run UX (and that the FE/telemetry signals 'index warming' rather than looking like a search failure). (2026-06-23)

### obs:unanchored-error-3 — IA: Agent (`core.agent-surface`) and Chat (`core.unified-chat-surface`) overlap conceptually — Agent
`kind: defect?` `anchor: none` `seen: 1` `first: 2026-05-29` `last: 2026-05-29` `status: parked (deferred — revisit per condition note (triage 2026-07-12))`
- [ ] IA: Agent (`core.agent-surface`) and Chat (`core.unified-chat-surface`) overlap conceptually — Agent has a Chat tab; Chat has a Tools shape. 557 Q10 fixed the label leak + kept them separate (validated distinct: Agent=tool autonomy Watch/Assist/Auto, Chat=Q&A). Whether to consolidate is a larger IA/backend product decision. (2026-05-29)

### obs:unanchored-general-21 — 564 follow-up: the agent surface (/api/chat/sessions, /api/chat/agent/history) serializes untyped `M
`kind: follow-up?` `anchor: none` `seen: 1` `first: 2026-05-31` `last: 2026-05-31` `status: parked (deferred — revisit per condition note (triage 2026-07-12))`
- [ ] 564 follow-up: the agent surface (/api/chat/sessions, /api/chat/agent/history) serializes untyped `Map<String,Object>` (no backend record) — its FE `validateWithFallback` cannot be migrated to a generated Zod until the backend introduces typed AgentSessionSummary/AgentBatchSummary records (a backend-typing effort, out of 564's FE-projection scope) (2026-05-31)

### obs:unanchored-general-25 — 565 ④ grounding-readiness signal (design-feature, specified): when an agent answer settles with sear
`kind: follow-up?` `anchor: none` `seen: 1` `first: 2026-06-05` `last: 2026-06-05` `status: parked (deferred — revisit per condition note (triage 2026-07-12))`
- [ ] 565 ④ grounding-readiness signal (design-feature, specified): when an agent answer settles with search activity but 0 sources because chunk-enrichment wasn't ready (the WARN condition in AgentSession.collectGroundingSources), surface a quiet "grounding pending — index still enriching" badge rather than a bare "no sources". HONEST approach: emit `groundingReady` on `AgentEvent.AgentDone` (derived in groundedDone from the worker's `chunkVectorsReady` — needs the status threaded into the agent loop) + FE badge near the Sources affordance. Reuses the §13.8 wire-add pattern (descriptor + regen + FE). (2026-06-05)

### obs:unanchored-general-29 — 565 §18: the grounding badge ("Grounded · N of M sentences") renders only on the AGENT answer path (
`kind: follow-up?` `anchor: none` `seen: 1` `first: 2026-06-09` `last: 2026-06-09` `status: parked (deferred — revisit per condition note (triage 2026-07-12))`
- [ ] 565 §18: the grounding badge ("Grounded · N of M sentences") renders only on the AGENT answer path (reads agentCtrl.answerSources/Citations); it does NOT render on the Documents/RAG grounded answer, which IS grounded (live: 5 sources + inline [n] cites). Decide whether the badge should extend to the RAG answer path — `UnifiedChatView.renderGroundingBadge` (2026-06-09)

### obs:unanchored-general-32 — Free-chat answers fabricate (n)-style citation markers with zero grounding; the 577 Ext I uncited-ho
`kind: follow-up?` `anchor: none` `seen: 1` `first: 2026-06-12` `last: 2026-06-12` `status: parked (deferred — revisit per condition note (triage 2026-07-12))`
- [ ] Free-chat answers fabricate (n)-style citation markers with zero grounding; the 577 Ext I uncited-honesty note covers only the agent path's [n] shape — consider extending to the free-chat/RAG plain renders (2026-06-12)

### obs:unanchored-general-33 — Zero-observer park eviction depends on the backend SEEING the SSE close (`writeEvent`→false). Throug
`kind: follow-up?` `anchor: none` `seen: 1` `first: 2026-06-13` `last: 2026-06-13` `status: parked (deferred — revisit per condition note (triage 2026-07-12))`
- [ ] Zero-observer park eviction depends on the backend SEEING the SSE close (`writeEvent`→false). Through the Vite DEV-proxy a browser tab-close is masked (Vite keeps its upstream SSE open), so the park doesn't fire when driven purely through the dev browser — works on a DIRECT connection (production/Tauri topology). Consider Javalin managed `SseClient.onClose` for proxy-independent disconnect detection. — `AgentController.handleRunStream/handleAttachStream` (2026-06-13)

### obs:unanchored-general-43 — Onramp demo corpus: on a tiny index, Document Q&A (RAG) top-k can surface the corpus README.md + lef
`kind: follow-up?` `anchor: none` `seen: 1` `first: 2026-07-01` `last: 2026-07-01` `status: parked (deferred — revisit per condition note (triage 2026-07-12))`
- [ ] Onramp demo corpus: on a tiny index, Document Q&A (RAG) top-k can surface the corpus README.md + leftover docs over the fabricated-fact content docs, giving a 'not in the documents' answer for a content query; raw Search is clean. Consider excluding README from the ingested set or seeding a truly clean index for the demo — `examples/onramp-corpus/README.md` (2026-07-01)

### obs:unanchored-general-45 — A fresh/clean dev-stack index auto-seeds the app's own built-in help docs (ssot/docs/help/*.md), whi
`kind: follow-up?` `anchor: none` `seen: 1` `first: 2026-07-02` `last: 2026-07-02` `status: parked (deferred — revisit per condition note (triage 2026-07-12))`
- [ ] A fresh/clean dev-stack index auto-seeds the app's own built-in help docs (ssot/docs/help/*.md), which compete in RAG citation retrieval against any bundled reference corpus (onramp, demo-corpus, future BYO) — observed live while validating tempdoc 669's demo corpus: `ai-features.md` outranked the actual demo content for a topical question. Not a tempdoc-669-specific defect (same auto-seed applies to onramp/BYO); worth a dedicated clean-index option if a fully pristine cited-answer demo recording is ever needed. (2026-07-02)

### obs:unanchored-general-47 — First live Onramp Smoke dispatch (run 28607534344) failed at 6m20s with 'FAIL stack start timed out'
`kind: defect?` `anchor: none` `seen: 1` `first: 2026-07-02` `last: 2026-07-02` `status: parked (deferred — revisit per condition note (triage 2026-07-12))`
- [ ] First live Onramp Smoke dispatch (run 28607534344) failed at 6m20s with 'FAIL stack start timed out' — cold windows-latest runner needs a larger STACK-START budget (distinct from the settle budget FIX-4 already raised to 90s) or a Gradle-cache warm step in onramp-smoke.yml. Failure was loud+correctly-labeled (FIX-4 working as intended); lane is advisory, non-blocking. (2026-07-02)

### obs:observation-shard-hint — Follow-up (tempdoc 680 retrospective): a small PostToolUse Write hint for NEW docs/tempdocs/*.md fil
`kind: lesson?` `anchor: scripts/agent-analytics/hooks/observation-shard-hint.mjs` `seen: 1` `first: 2026-07-07` `last: 2026-07-07` `status: parked (deferred — revisit per condition note (triage 2026-07-12))`
- [ ] Follow-up (tempdoc 680 retrospective): a small PostToolUse Write hint for NEW docs/tempdocs/*.md files in the main checkout ('commit the draft — worktrees branch from commits, not working trees') would mechanize the draft-commit lesson; third incident of the class (#446 + two in the 680 cycle) meets the rule-of-three bar — `scripts/agent-analytics/hooks/observation-shard-hint.mjs` is the template (2026-07-07)

### obs:searchorchestrator — SearchOrchestrator.warmUp() doc-count guard doesn't account for post-boot index deletion races (docC
`kind: follow-up?` `anchor: modules/worker-services/src/main/java/io/justsearch/indexerworker/services/SearchOrchestrator.java` `seen: 1` `first: 2026-07-07` `last: 2026-07-07` `status: parked (deferred — revisit per condition note (triage 2026-07-12))`
- [ ] SearchOrchestrator.warmUp() doc-count guard doesn't account for post-boot index deletion races (docCount>0 at boot, later trimmed to 0 by user action before deferred model init completes) — theoretical edge, not exercised by current tests — `modules/worker-services/src/main/java/io/justsearch/indexerworker/services/SearchOrchestrator.java:172` (2026-07-07)

### obs:workflow-signal-health — Onramp Smoke proof-lane has no freshness-surfacing (workflow-signal-health.mjs runs nowhere) — it ro
`kind: defect?` `anchor: scripts/ci/workflow-signal-health.mjs` `seen: 1` `first: 2026-07-08` `last: 2026-07-08` `status: parked (deferred — revisit per condition note (triage 2026-07-12))`
- [ ] Onramp Smoke proof-lane has no freshness-surfacing (workflow-signal-health.mjs runs nowhere) — it rotted red-unnoticed ~5 days. Cadence needs a standing external dispatcher = owner/infra decision (tempdoc 656 §K3/§K.5) — `scripts/ci/workflow-signal-health.mjs` (2026-07-08)

### obs:unanchored-general-76 — Combined enrichment backfill caps chunk-embedding at ~50 chunks/cycle (~3.5min cycles) — an 85k-chun
`kind: defect?` `anchor: none` `seen: 1` `first: 2026-07-10` `last: 2026-07-10` `status: parked (deferred — revisit per condition note (triage 2026-07-12))`
- [ ] Combined enrichment backfill caps chunk-embedding at ~50 chunks/cycle (~3.5min cycles) — an 85k-chunk corpus (mixed/realdocs-v1) would take days to drain; doc-level enrichment dominates cycles while the chunk backlog crawls. Throughput pacing question for 691's domain — `CombinedEnrichmentBackfillOps/BackfillScheduler`, observed live 2026-07-10 (2026-07-10)

### obs:sessionoptionsapplier — ORT arena shrinkage (memory.enable_memory_arena_shrinkage=gpu:0) is enabled on EVERY run via Session
`kind: follow-up?` `anchor: modules/ort-common/src/main/java/io/justsearch/ort/SessionOptionsApplier.java` `seen: 1` `first: 2026-07-10` `last: 2026-07-10` `status: parked (deferred — revisit per condition note (triage 2026-07-12))`
- [ ] ORT arena shrinkage (memory.enable_memory_arena_shrinkage=gpu:0) is enabled on EVERY run via SessionOptionsApplier.java:109-115, but ORT issue reports say it is unreliable for CUDA (doesn't cleanly free after peak-then-small) and adds per-run latency — never validated locally; cheap A/B candidate on next throughput dev-stack session. Found by 710 S-C.R research pass — `modules/ort-common/src/main/java/io/justsearch/ort/SessionOptionsApplier.java:109` (2026-07-10)

### obs:batch-557-deferred — 557 deferred residuals (Q2 tri-state env-blocked; minor MacroDryRun wording)
`kind: follow-up` `anchor: none` `seen: 2` `first: 2026-05-29` `last: 2026-05-29` `status: parked (deferred — revisit per condition note (triage 2026-07-12))`
- [ ] 557 Q2 tri-state universality DEFERRED (2026-05-29): fail-open is fixed (Health shows degraded), but extending Maybe<T> to every observed-state field is env-blocked for live verification (dev SSE won't reliably disconnect). Revisit with a forced-disconnected harness if needed.
- [ ] 557 minor: describeChange navigate ("view → <raw route>") still raw in the MacroDryRun diff (low-visibility, not the Q7 browser surfaces). Humanize via present({kind:'route'}) at the MacroDryRun render if revisited. (2026-05-29)

### obs:cc-68619-recursion-mitigation — claude-code#68619 subagent recursion mitigation (never general-purpose for fan-out; CLAUDE_CODE_FORK_SUBAGENT no-op)
`kind: environment` `anchor: none` `seen: 1` `first: 2026-06-25` `last: 2026-06-25` `status: parked (deferred — revisit per condition note (triage 2026-07-12))`
- [ ] **[TEMPORARY MITIGATION — REMOVE WHEN [`anthropics/claude-code#68619`](https://github.com/anthropics/claude-code/issues/68619) is fixed by Anthropic]** Built-in `general-purpose`/`claude` subagent types carry the `Agent`/`Task` tool and are prompt-primed to delegate, so under the June-2026 recursion regression (#68619) they spawn child subagents with **no working depth cap** (`CLAUDE_CODE_FORK_SUBAGENT=0` is ignored — bug #1) → runaway fan-out, catastrophic token burn, and memory thrash. **Incident 2026-06-25:** a handful of parallel `general-purpose` research agents burned **~15% of a weekly Max-20x limit in ~5 min** and thrashed the machine into a restart (lost in-flight work). No compensation/credit reported by anyone on #68619; no Anthropic response on the thread as of 2026-06-25. **Mitigation — apply until upstream fix:** (1) **NEVER** use `general-purpose`/`claude` agent types for research/fan-out — use the read-only **`Explore`** type (it has **no** `Agent` tool, so it physically cannot spawn children), or do web research **inline** with `WebSearch`/`WebFetch`. (2) If a custom agent is ever added under `.claude/agents/`, do **not** grant it the `Agent`/`Task` tool. (3) Defense-in-depth: `CLAUDE_CODE_FORK_SUBAGENT=0` added to `.claude/settings.json` (currently a no-op per bug #1; forward-compatible once Anthropic honors the flag). **On resolution of #68619, remove this entry AND the `CLAUDE_CODE_FORK_SUBAGENT` env flag from `.claude/settings.json`.** — `.claude/settings.json` + agent-spawning policy (2026-06-25)

### obs:workspacetimeline-v2-deferred — WorkspaceTimeline V2 sessionId join — deferred until UX feedback shows weight
`kind: follow-up` `anchor: none` `seen: 1` `first: 2026-04-28` `last: 2026-04-28` `status: parked (deferred — revisit per condition note (triage 2026-07-12))`
- [ ] WorkspaceTimeline V2 — join file-operation batches to their originating sessionId. V1 ships timestamp-only merge because threading sessionId through `ToolDefinition.execute(args)` requires either a ThreadLocal hack on `FileOperationLog` or an SPI extension (new `ToolContext` parameter on `ToolDefinition`). Defer until UX feedback shows the sessionId join carries weight. Tempdoc 415 C43 follow-up. (2026-04-28)

### obs:c28-notification-continuity-deferred — C28 notification→session continuity — deferred until budget notifications fire often enough
`kind: follow-up` `anchor: none` `seen: 1` `first: 2026-04-28` `last: 2026-04-28` `status: parked (deferred — revisit per condition note (triage 2026-07-12))`
- [ ] C28 (Notification-to-Session Continuity) — deferred per tempdoc 415 follow-up plan §Recommended product-decision defaults #2. Tauri notification onClick → `view-session` event → AgentView Sessions tab + selected sessionId. Defer until budget-warning notifications fire often enough to make this worth wiring. (2026-04-28)

### obs:webview2-lna-watch — WebView2 Local Network Access enforcement rollout — could affect the loopback invariant
`kind: follow-up` `anchor: modules/shell/src-tauri` `seen: 1` `first: 2026-07-01` `last: 2026-07-01` `status: parked (deferred — revisit per condition note (triage 2026-07-12))`
- [ ] WebView2 is actively rolling out Local Network Access (LNA) enforcement (versions 143-145, currently disabled via kill-switch/opt-in flag `msWebViewAllowLocalNetworkAccessChecks`, upstream Chromium spec still evolving) — a future default-on flip could affect every loopback call this app makes (the `loopback-only-network` Hard Invariant), potentially requiring an OS permission prompt or silently blocking requests. Chrome's own LNA exempts same-space (loopback-to-loopback) requests, which JustSearch's Head/Worker architecture likely qualifies for, but WebView2's exemption rules aren't confirmed to match. Found while researching tempdoc 662 (SSE connection budget); relevant to the whole app's network architecture, not specific to 662's multiplexer — worth a proactive test with the WebView2 test flag before this becomes enabled by default. Sources: https://github.com/MicrosoftEdge/WebView2Announcements/issues/126, https://learn.microsoft.com/en-us/deployedge/ms-edge-local-network-access — `modules/shell/src-tauri` (2026-07-01)

### obs:ocr-default-off-decision — OCR stays OFF by default (602 R10 product decision); absence-legibility deferred pending a signal that does not exist yet
`kind: follow-up` `anchor: modules/configuration/src/main/java/io/justsearch/configuration/ConfigKey.java` `seen: 1` `first: 2026-06-17` `last: 2026-06-17` `status: parked (deferred — revisit per condition note (triage 2026-07-12))`
- [ ] **Product decision (tempdoc 602 R10):** OCR stays OFF by default (`index.ocr.enabled` default null→off; `ConfigKey.INDEX_OCR_ENABLED`). For a "personal files" user, screenshots/scanned receipts are then not findable by content. Flipping the default is a `/search-quality` + product call (extraction cost/latency/quality), not an FE change. The "explain the absence" legibility (a zero-result image-text search telling the user "image text isn't searched — OCR is off") is DEFERRED: it needs a signal that a query *would* have matched image text, which does not exist today (a reusable hook is the existing `files/ocr_limits_exceeded` reason + an empty-state). Recorded, not fixed. — `modules/configuration/src/main/java/io/justsearch/configuration/ConfigKey.java` (2026-06-17)
