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
**defect** → the owning domain register (`docs/reference/search-quality-register.md`,
`docs/reference/inference-runtime-register.md`) or its owning tempdoc — the standing
`docs/reference/issues/` registers were retired in tempdoc 821 §7 D5; **environment** (facts about
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
`kind: defect` `anchor: modules/ui-web/src/shell-v0/chrome/Shell.ts` `seen: 5` `first: 2026-05-25` `last: 2026-08-12`
- [ ] 3 unused eslint-disable(no-console) directive warnings — `modules/ui-web/src/shell-v0/chrome/Shell.ts:409,753,1253` (2026-05-25)
- [ ] Shell.ts has 4 redundant `// eslint-disable-next-line no-console` directives above `console.warn` calls (config allows warn) — lines ~426/804/811/1346 (2026-05-30)
- [ ] 565 §12.3.E: two SourcesPane instances exist at wide viewport (the docked rail + the dormant display:none OverlayHost drawer), both subscribed to agentSession+selectedSource — redundant render work, not a bug; consider gating the drawer mount out in agent mode — `Shell.ts`/`SourcesPane.ts` (2026-06-04)
- [ ] UI tooltip advertises Ctrl+L as "Copy URL" but the registered mod+l keybinding is shell.focus-composer — tooltip is wrong, pressing Ctrl+L focuses the search bar — `modules/ui-web/src/shell-v0/chrome/Shell.ts:2237` vs `:924` (2026-07-28)
- [ ] Shipped global keybinding `mod+k` (shell.toggle-palette) is registered without a `when` clause on a window-CAPTURE listener at boot, so no later-loaded surface can scope or pre-empt it — a surface-local palette double-fires with the shell's (live-measured, tempdoc 822 slice 4) — `modules/ui-web/src/shell-v0/chrome/Shell.ts:927`, `modules/ui-web/src/shell-v0/commands/KeybindingRegistry.ts:178` (2026-08-12)

### obs:agent-tool-arg-coercion — Agent tool schema rejects string-typed numbers ("limit":"10") — burns an iteration every session; no coercion at tool boundary
`kind: defect` `anchor: modules/app-services/.../registry/executor/OperationInputSchemaValidator.java` `seen: 2` `first: 2026-05-30` `last: 2026-06-11`
- [ ] Agent search tool rejects string `limit` arg ('string found, integer expected') — model passed {"limit":"1"}, wasted one agent iteration before retrying with integer; tool should coerce or schema should constrain — `modules/app-agent` core_search_index arg handling (2026-05-30)
- [ ] Agent loop burns iteration 1 every session on the same schema rejection: LLM emits `"limit":"10"` (string), OperationInputSchemaValidator rejects ('string found, integer expected'), no coercion at the tool boundary — recurs across sessions (live-verified 2026-06-11, tempdoc 577 §2.9). Consider lenient numeric coercion or prompt-side schema hinting — `modules/app-services/.../registry/executor/OperationInputSchemaValidator.java` (2026-06-11)

### obs:activate — V1.5 dev-mode: Vite middleware adds `?import` query to dynamic-import URLs targeting `public/` stati
`kind: lesson?` `anchor: modules/ui-web/dev-examples/custom-ui-focus/activate.js` `seen: 2` `first: 2026-05-07` `last: 2026-05-07`
- [ ] V1.5 dev-mode: Vite middleware adds `?import` query to dynamic-import URLs targeting `public/` static files, returning 500. Workaround: fetch source as text + emit as `data:` URL. Production Tauri builds don't go through Vite; this is dev-only. — `modules/ui-web/dev-examples/custom-ui-focus/activate.js` (2026-05-07)
- [ ] V1.5 alpha: `btoa()` rejects non-Latin1 characters; UTF-8-encoded JS source needs `TextEncoder` + byte-string conversion before base64. Pattern documented in `dev-examples/custom-ui-focus/activate.js`. — `modules/ui-web/dev-examples/custom-ui-focus/activate.js:fetch-and-package` (2026-05-07)

### obs:index — undoAllByOriginator/undoLastEffectByOriginator do not skip pendingOutcome:'rejected' entries; a reje
`kind: defect` `anchor: modules/ui-web/src/shell-v0/substrates/effects/index.ts` `seen: 2` `first: 2026-05-25` `last: 2026-05-26`
- [ ] undoAllByOriginator/undoLastEffectByOriginator do not skip pendingOutcome:'rejected' entries; a rejected (vetoed, never-dispatched) agent effect with a derivable inverse would be 'undone' by 'Undo all AI actions' — `modules/ui-web/src/shell-v0/substrates/effects/index.ts:494,514` (2026-05-25)
- [ ] navigate effects are an imperfect fit for the Effect-cursor undo/redo (543-fwd #1): surfaces append query params (?q=) producing secondary navigations + the router canonicalizes URLs, so cursor-redo of a navigate is unreliable live despite the re-journal suppression. Proper fix: route navigation undo/redo through NavigationJournal's own history, or exclude navigate from the Effect-cursor (it has its own history model). `modules/ui-web/src/shell-v0/substrates/effects/index.ts` (2026-05-26)

### obs:agentsessioncontroller — Agent Sessions list: FE `SessionListItem` reads `startedAtEpochMs`/`status` but backend `toSessionSu
`kind: defect?` `anchor: modules/ui-web/src/shell-v0/controllers/AgentSessionController.ts` `seen: 4` `first: 2026-06-03` `last: 2026-08-18`
- [ ] Agent Sessions list: FE `SessionListItem` reads `startedAtEpochMs`/`status` but backend `toSessionSummary` emits `startedAt`/`state` — the time+status meta renders empty (field-name mismatch) — `modules/ui-web/src/shell-v0/controllers/AgentSessionController.ts:85` / `AgentRunStore.java:424` (2026-06-03)
- [ ] Agent chat ALWAYS forced to core_ingest_files on turn 1 (every run, any prompt). Root cause: FE `AgentSessionController.BUILTIN_PROFILES` is a lone {agentId:'manager', tools:[]} sent as agentProfiles + initialAgentId='manager'; backend `AgentTurnPolicy.shouldForceToolCall` treats any non-null non-'primary' activeId as a sub-agent → E0a fires on turn 1 → `buildE0aTools` restricts tools to core.ingest-files + handoff, but there is no other agent to hand off to and 'manager' has no tools → only core_ingest_files is callable. Mismatch: E0a expects a manager+workers team; the default is a lone manager. Fix: default the single-window chat agent to single-agent (initialAgentId=null / agentProfiles=[]) or name it 'primary'. Pre-existing (not 565). — AgentSessionController.ts:162,854 / AgentTurnPolicy.java:29 / AgentStepRunner.java:181,663 (2026-06-09)
- [ ] governance kernel `contract-projection` is red on an undeclared FE wire consumer: AgentSessionController.ts imports schema-types/agent-sessions-response but is not in contract-surfaces.v1.json — `modules/ui-web/src/shell-v0/controllers/AgentSessionController.ts` (2026-08-18)
- [ ] FE reattach to a PARKED agent run attaches then drops: after a mid-run reload the window correctly did GET /api/chat/runs/live then POST /api/chat/runs/{id}/observe (both 200), but the enumeration reported observerCount 0 seconds later while a node observer on the same run held it at 1 — so the browser's observe stream closed rather than staying attached; unclear if the liveWatchdog (604) aborts a parked run that only heartbeats — `modules/ui-web/src/shell-v0/controllers/AgentSessionController.ts` (2026-08-18)

### obs:searchstate — Search surface meta-line displays "2ms" while the actual /api/knowledge/search round trip is ~1.2s w
`kind: defect?` `anchor: modules/ui-web/src/shell-v0/state/searchState.ts` `seen: 3` `first: 2026-06-12` `last: 2026-08-07`
- [ ] Search surface meta-line displays "2ms" while the actual /api/knowledge/search round trip is ~1.2s with AI online — which field feeds processingTimeMs needs tracing — `modules/ui-web/src/shell-v0/state/searchState.ts` (2026-06-12)
- [ ] FE search result mapping has no defensive handling for a raw `chunk:`-prefixed hit id: `path = fields.path ?? r.id` silently renders "chunk:uuid…" as the filename if chunk-merge is skipped (pure-dense result sets). Hard to diagnose if chunk-merge ever regresses. — `modules/ui-web/src/shell-v0/state/searchState.ts:492` (2026-06-17)
- [ ] Live backend returned results.length=5 with matchCount=4 for one query (818 search-v2 live smoke, dev corpus) — matchCount below the returned window size is a 597-class count inversion at the API level, worth a look at the KnowledgeSearchResponse count fields — `modules/ui-web/src/shell-v0/state/searchState.ts:80` (2026-08-07)

### obs:ui-check — ui-shot color_scheme="light" steps render the dark app theme (persisted theme wins over prefers-colo
`kind: defect` `anchor: scripts/jseval/jseval/ui_check.py` `seen: 3` `first: 2026-06-12` `last: 2026-08-03`
- [ ] ui-shot color_scheme="light" steps render the dark app theme (persisted theme wins over prefers-color-scheme) — light-theme shots don't validate light tokens visually — `scripts/jseval/jseval/ui_check.py` (2026-06-12)
- [ ] ui-shot/ui-check chat steps target the retired React inspector (search-input, inspector-pane, context-state pills) or a broken ?shell-demo bypass — none render the live shell-v0 UnifiedChatView, so the main chat surface has no visual-verification coverage — `scripts/jseval/jseval/ui_check.py` (2026-06-19)
- [ ] No ui-shot step covers the Activity/AUDIT surface (jf-activity-surface) — the round-10 F9 regression home asks for a ui-shot with a non-empty ledger, but the step registry's isolated views list has no activity entry — `scripts/jseval/jseval/ui_check.py:906` (2026-08-03)

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
`kind: defect?` `anchor: remove-worktree.cjs` `seen: 14` `first: 2026-06-21` `last: 2026-08-07`
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
- [ ] remove-worktree's holder detection misses the agent's OWN shell cwd: after a `cd` into a worktree earlier in the same Bash session, removal fails with 'no holder found by command line' even when run from the repo root — a plain retry after the shell has moved out succeeds. Extends the known cwd-drift lesson — `scripts/dev/remove-worktree.cjs` (2026-07-31)
- [ ] Worker-session jseval auto-serve Vite (port 5176) outlived its worker agent and held the agent worktree against remove-worktree — command line shows MAIN's node_modules path via the junction, so holder detection by command line misses it; remedy that worked: kill the vite PID (verified via Win32_Process), then remove-worktree succeeds — scripts/dev/remove-worktree.cjs (2026-08-07)

### obs:healthsurface-flake — HealthSurface 'Recent events' renders NO ConditionStore conditions: `hs.events` is empty for ALL con
`kind: defect?` `anchor: HealthSurface.ts` `seen: 2` `first: 2026-06-22` `last: 2026-08-12`
- [ ] HealthSurface 'Recent events' renders NO ConditionStore conditions: `hs.events` is empty for ALL conditions (ai.not-ready, embedding.blocked, at-rest.unprotected, etc.) despite the /api/health/events/stream snapshot carrying them (a fresh same-origin fetch gets them fine). HealthSurface's persistent SSE subscription (`HealthSurface.ts:571-624`) isn't populating this.events — possibly dev-stack reconnect/stale-port flakiness. Affects all conditions equally; unrelated to 629. (2026-06-22)
- [ ] HealthSurface reads retained fields raw while sibling lines gate on snapshotLive: 'Up to date' at `views/HealthSurface.ts:888` and index-state rows at `:1032,:1113-1114` — residual C1 members not covered by the 821 fix wave (2026-08-12)

### obs:default-index — Dev-stack: orphaned dev-runner/Worker processes accumulate across sessions and hold `index/default.i
`kind: defect?` `anchor: index/default.index.lock` `seen: 1` `first: 2026-06-22` `last: 2026-06-22`
- [ ] Dev-stack: orphaned dev-runner/Worker processes accumulate across sessions and hold `index/default.index.lock` + squat port 5173, crash-looping new Workers (`Index base path is already locked`) and tearing the stack down — symptom looks like a code boot failure but isn't. Recover: kill stray java/node dev PIDs + delete the stale lock; run `dev-runner.cjs start` as a BARE persistent background process (its children are in a KILL_ON_JOB_CLOSE Job Object, so a timeout/pipe wrapper kills the whole stack). Hit during 629 LAYER live-validation. (2026-06-22)

### obs:agent-utility-inspect — jseval utility-run (Inspect eval_set): re-invoking a FULLY-COMPLETED set errors 'log file not associ
`kind: defect?` `anchor: agent_utility_inspect.py` `seen: 7` `first: 2026-06-22` `last: 2026-07-28`
- [ ] jseval utility-run (Inspect eval_set): re-invoking a FULLY-COMPLETED set errors 'log file not associated with a task' — needs --log-dir-allow-dirty; partial-crash resume uses the now-pinned deterministic eval_set_id. tempdoc 624 run-governance validation — `scripts/jseval/jseval/agent_utility_inspect.py:run_utility_eval` (2026-06-22)
- [ ] furniture_markers all-False in the one live L1 capture cell despite extraction verified correct on both content shapes against live 0.3.1 responses (evidence_pack=True in-process) — child-agent-session content path discrepancy, unrecoverable from redacted logs; settle via one debug-instrumented cell — `scripts/jseval/jseval/agent_utility_inspect.py:548-621` (2026-07-14)
- [ ] Inspect eval_set post-hoc retry is structurally unavailable: completed evals with per-sample errors are terminal, and task identity embeds live-captured MCP surface/initialize fields so a fresh backend can never adopt an old log ('log not associated') — retry budget must be designed into the run — `scripts/jseval/jseval/agent_utility_inspect.py:1278` (2026-07-17)
- [ ] Tier-probe harness lesson: Claude CLI background haiku calls void every cell of a non-haiku campaign via the resolved-model cohort guard (39/40 sonnet cells, 'resolved provider model changed within one cell') — pin ANTHROPIC_DEFAULT_HAIKU_MODEL + CLAUDE_CODE_SUBAGENT_MODEL to the campaign tier via --agent-env for any non-haiku run; consider a harness-level default — `scripts/jseval/jseval/agent_utility_inspect.py` (2026-07-17)
- [ ] 782 campaign harness: rank-of-gold capture dead — MCP delivery emits hit identity as 'path' but _gold_rank_capture reads only h.get('id') — `scripts/jseval/jseval/agent_utility_inspect.py:921`; fix alongside the mixed-model-guard exhaustion-label masking (1404 vs 1424) (2026-07-28)
- [ ] test_run_utility_eval_resumes_a_multi_sample_full_completion fails under full-suite load when the worktree's git state changes mid-run (a commit landing between the two run_utility_eval invocations makes the source-identity sidecar's source_git_state differ) — passes in isolation; the resume test is sensitive to concurrent working-tree mutation — `scripts/jseval/jseval/agent_utility_inspect.py:2009` (2026-07-28)
- [ ] v5 agent-utility Inspect logs do NOT persist search-result payloads visible to the agent (redacted to sha256/len/shape in _record_cell); attribution of gold-in-topk requires replay, not log inspection — `scripts/jseval/jseval/agent_utility_inspect.py:481-490,877-883` (2026-07-21)

### obs:healtheventstreamcontroller — Health 'Recent events' SSE (/api/health/events/stream) delivered NOTHING across two fresh dev stacks
`kind: environment?` `anchor: modules/ui/src/main/java/io/justsearch/ui/api/HealthEventStreamController.java` `seen: 1` `first: 2026-06-23` `last: 2026-06-23`
- [ ] Health 'Recent events' SSE (/api/health/events/stream) delivered NOTHING across two fresh dev stacks — eventCount 0 for ALL conditions incl. at-rest.unprotected, while FDE=NOT_ENCRYPTED + encryption not_configured (conditions ARE asserted by the taps on /api/status). Broadens the logged aiStateStore frozen-status finding: the whole event-delivery layer (SSE + status poll) is flaky/broken in current dev sessions. App-wide, pre-existing, out of 629. — `modules/ui/src/main/java/io/justsearch/ui/api/HealthEventStreamController.java` (2026-06-23)

### obs:aistatestore — HealthSurface this.status frozen post-mount — observed_at stuck for minutes while /api/status advanc
`kind: follow-up?` `anchor: modules/ui-web/src/shell-v0/state/aiStateStore.ts` `seen: 2` `first: 2026-06-23` `last: 2026-08-06`
- [ ] HealthSurface this.status frozen post-mount — observed_at stuck for minutes while /api/status advances; subscribeAiState callback (HealthSurface.ts:502) sets this.status unconditionally so the shared aiStateStore poll isn't propagating. App-wide (every status field), surfaced during a reconnecting dev stack — investigate whether the statusPoll/aiStateStore stalls after a connection disruption. — `modules/ui-web/src/shell-v0/state/aiStateStore.ts` (2026-06-23)
- [ ] computeCapabilities gates `rag` on `indexedDocuments > 0` — an index holding ONLY default-excluded collections (agent-history) reports rag-capable while a default-scope retrieval can return nothing; the 811 C-4 `searchableDocuments` field is the truthful input, deliberately out of scope for the count fix — `modules/ui-web/src/shell-v0/state/aiStateStore.ts:333` (2026-08-06)

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

### obs:05-ai-architecture — Stale chat-model name in canonical docs: `docs/explanation/05-ai-architecture.md` (e.g. lines 15,167
`kind: defect?` `anchor: docs/explanation/05-ai-architecture.md` `seen: 3` `first: 2026-06-25` `last: 2026-07-30`
- [ ] Stale chat-model name in canonical docs: `docs/explanation/05-ai-architecture.md` (e.g. lines 15,167,226,457) + `06-configuration-ssot.md:82` name the retired `Qwen3VL-8B-Thinking` as the current/default generative LLM. Actual default is `Qwen3.5-9B` (only model on disk; `model-inventory.md:177` + `legal/ai-runtime-and-model-redistribution.md:79` already correct; no `Qwen3VL` anywhere in `modules/*/src/main`). 579-class canonical-vs-code drift, 2nd instance of the stale-technical-claim class (tempdoc 650) — reconcile 05/06 with a careful pass, not a blind find-replace (the reasoning/Thinking discussion may be model-specific). (2026-06-25)
- [ ] 05-ai-architecture.md 'Frontend rendering' section still describes the retired React `useAppAI.ts` hook and the old `meta` citation event; needs a frontend-stack (Lit/shell-v0) refresh beyond the engine-citation event rename — `docs/explanation/05-ai-architecture.md:390` (2026-07-08)
- [ ] docs/explanation/05-ai-architecture.md claims 'Models are bundled in the installer as flat assets (~40 MB total)' — same class as the ADR-0024 retraction (round 7 measured zero .onnx bytes after a clean install; CI sets skipOnnxModels unconditionally); unverified whether the ~40 MB refers to tokenizer/config assets or is stale — `docs/explanation/05-ai-architecture.md:463` (2026-07-30)

### obs:llm-bench — llm-bench discover_doc_ids uses `*:*` which returns 0 in semantic-search dev stacks (real queries wo
`kind: defect?` `anchor: scripts/jseval/jseval/llm_bench.py` `seen: 1` `first: 2026-06-24` `last: 2026-06-24`
- [ ] llm-bench discover_doc_ids uses `*:*` which returns 0 in semantic-search dev stacks (real queries work) — the bench can't auto-discover docs there, so token/latency benching needs an index that serves `*:*` or an explicit docId — `scripts/jseval/jseval/llm_bench.py` (2026-06-24)

### obs:gitleaks — gitleaks.toml allowlists `third_party/.*` as 'vendored upstream (llama.cpp etc.)' — that tree was re
`kind: defect?` `anchor: docs/business/go-to-market/cutover-package/gitleaks.toml` `seen: 1` `first: 2026-06-24` `last: 2026-06-24`
- [ ] gitleaks.toml allowlists `third_party/.*` as 'vendored upstream (llama.cpp etc.)' — that tree was removed in tempdoc 632, so the allowlist rule is now inert; drop it during the 634 cutover gitleaks pass — `docs/business/go-to-market/cutover-package/gitleaks.toml:11` (2026-06-24)

### obs:16-gpu-booster-pack — Canonical drift: `docs/explanation/16-gpu-booster-pack.md` presents the GPU Booster Pack as the curr
`kind: environment?` `anchor: docs/explanation/16-gpu-booster-pack.md` `seen: 1` `first: 2026-06-24` `last: 2026-06-24`
- [ ] Canonical drift: `docs/explanation/16-gpu-booster-pack.md` presents the GPU Booster Pack as the current GPU-runtime delivery mechanism, but tempdoc 632 recorded the founder correction that the booster pack is LEGACY and the live mechanism is the AI-brain install (AiInstallService downloading the model-registry cuda-runtime package). Doc needs a reframe (pre-existing drift, surfaced by 632's NVIDIA accept-and-document work) — `docs/explanation/16-gpu-booster-pack.md` (2026-06-24)

### obs:search-quality-register — Doc/code drift: search-quality register D-004 still says leg-arbitration 'SHIPPED (default off)' / '
`kind: defect?` `anchor: docs/reference/search-quality-register.md` `seen: 5` `first: 2026-06-24` `last: 2026-07-28`
- [x] ~~Doc/code drift: search-quality register D-004 still says leg-arbitration 'SHIPPED (default off)' / 'default-on not recommended' (`docs/reference/search-quality-register.md:585-605`), but shipped code has BOTH leg-arbitration + recall-complete default TRUE (`ResolvedConfigBuilder.java:1497,1513`) per tempdoc 636 final decision; F-024 + a recall-complete D-row also need reconciling. (2026-06-24, tempdoc 636 take-over)~~ (2026-06-24)
- [x] RESOLVED (2026-08-19): D-004 annotated with a dated STATUS SUPERSEDED note (default ON since the 2026-06-24 F-024 user decision; code cites `ResolvedConfigBuilder.java:1631/:1647`) — the drift was register-internal; code, `environment-variables.md`, tempdoc 636, and F-024 already agreed. The note also records that all post-2026-06-24 default-config baselines (incl. release `832-rebaseline-2026-08-14`) are both-levers-ON measurements. (2026-08-19)
- [x] ~~search-quality-register.md has TWO entries numbered F-030 (tempdoc 678 encoder-domain-mismatch, ~line 595, and tempdoc 706 OCR comparability, ~line 579) — pre-existing numbering collision found during 691 Phase-L takeover; register owner should renumber one — `docs/reference/search-quality-register.md`~~ RESOLVED (2026-08-19): collision resolved by canonicalizing the already-cited suffixed forms F-030(706)/F-030(678) via a numbering-collision note in the register (renumbering would break existing tempdoc citations); bare F-030 is retired from future assignment. (2026-07-10)
- [ ] Residual hybrid-vs-lexical gap on legal-clerc post-F-032 (hybrid 0.5592/0.5609 vs lexical 0.6891 at b88e76e) is fusion territory — out of 708 scope, needs its own owner — `docs/reference/search-quality-register.md` (2026-07-11)
- [x] ~~search-quality-register.md has a DUPLICATE finding ID F-030 — used for BOTH the 706 scanned-PDF-OCR-engine finding AND the 678 encoder-domain-mismatch finding. Register hygiene: distinct findings need distinct IDs (renumber one, e.g. the 706 OCR one). Found during 705 re-investigation; register is 678/708's domain — `docs/reference/search-quality-register.md`~~ RESOLVED (2026-08-19): same item as the 2026-07-10 line above — see that resolution. (2026-07-12)
- [ ] Register Dataset Catalog lists mixed/ohr-bench-tika-pdf as 999 docs, but the 786 sweep's summary.json reports doc_count 1000 / final_doc_count 1001 like the other three OHR arms — catalog row may be stale — `docs/reference/search-quality-register.md:33` (2026-07-28)

### obs:resourceapimodule — ResourceApiModule.shutdown() never calls intentStreamController::shutdown — its heartbeat scheduler
`kind: environment?` `anchor: modules/ui/src/main/java/io/justsearch/ui/api/ResourceApiModule.java` `seen: 2` `first: 2026-06-30` `last: 2026-07-14`
- [ ] ResourceApiModule.shutdown() never calls intentStreamController::shutdown — its heartbeat scheduler thread leaks on module shutdown (pre-existing, found while wiring tempdoc 662's ShellEventsStreamController shutdown) — `modules/ui/src/main/java/io/justsearch/ui/api/ResourceApiModule.java:472-494` (2026-06-30)
- [ ] HIGH (found by follow-up audit of the 0.2.0 round, NOT by the round itself): enabling chat encryption permanently breaks GET /api/thread/{id} — the unified chat surface's own endpoint. ResourceApiModule.java:310-313 constructs a SECOND FileConversationStore via the single-arg ctor, which is StoreCipher.disabled() (FileConversationStore.java:49-51) — a permanently-disabled cipher, unrelated to the primary store's live one (ConversationApiAssembly.java:204-209). StoreCipher.open throws KeyLockedException on any JSEv1: line when !key.enabled(), and loadOwnMessages (FileConversationStore.java:121) only catches IOException, so it propagates -> 500 -> unifiedThreadClient.ts:210 swallows !res.ok and renders an EMPTY thread with no banner. ALWAYS broken once any message was written post-encryption-setup — not just while locked (the disabled cipher never consults the live key). Contrast listSessions:216-227 which DOES catch KeyLockedException and degrades gracefully. No test covers the single-arg ctor against a sealed store. Fix surface: ResourceApiModule.java:312-313 (inject the live cipher) + a KeyLocked-aware read path. (2026-07-14)

### obs:resourceview — Tempdoc 662: after migrating startIndexingJobsBridge onto the shared MultiplexedStream, an open core
`kind: defect?` `anchor: modules/ui-web/src/shell-v0/components/ResourceView.ts` `seen: 1` `first: 2026-06-30` `last: 2026-06-30`
- [ ] Tempdoc 662: after migrating startIndexingJobsBridge onto the shared MultiplexedStream, an open core.indexing-jobs Resource view (ResourceView.ts, generic subscribePooled by URL) no longer shares a socket with the always-on bridge — it opens its own lazy socket instead of pooling with the bridge as before. Minor, documented tradeoff (well under the 6-connection budget); a future pass could teach ResourceView's generic SSE_STREAM mechanism to also check the shell-events multiplexer for any of the 5 multiplexed streamIds — `modules/ui-web/src/shell-v0/components/ResourceView.ts` + `modules/ui-web/src/shell-v0/substrates/tasks/indexingJobsBridge.ts:330-385` (2026-06-30)

### obs:branch-safety — branch-safety.md claims '.claude/settings.local.json is tracked' but it is gitignored (seeded from s
`kind: defect?` `anchor: .claude/rules/branch-safety.md` `seen: 3` `first: 2026-07-01` `last: 2026-08-04`
- [ ] branch-safety.md claims '.claude/settings.local.json is tracked' but it is gitignored (seeded from settings.local.json.example) — doc drift — `.claude/rules/branch-safety.md:20` (2026-07-01)
- [ ] Pre-existing markdownlint MD031/MD040 violations (7x, fenced code blocks) in .claude/rules/branch-safety.md — `.claude/rules/branch-safety.md:26-67`, predates the session-695-retro-followup docs work (2026-07-07)
- [ ] Subagent brief lesson: a W3 worker used a bare git stash/pop pair for a verification despite branch-safety.md's shared-stash rule being natively inherited — no damage (a foreign worktree-725 stash entry survived untouched, verified), and the worker self-reported. Briefs that say 'no git commands' should name the stash rule and its reason (shared stack across worktrees) explicitly; inherited prose did not hold under a local 'read-only enough' judgment call — `.claude/rules/branch-safety.md` (2026-08-04)

### obs:test-pipeline — test-pipeline.mjs fails at line 361 (JSON.parse of empty intervene output for realLargeFile large-fi
`kind: environment?` `anchor: scripts/agent-analytics/test-pipeline.mjs` `seen: 3` `first: 2026-07-01` `last: 2026-07-22`
- [ ] test-pipeline.mjs fails at line 361 (JSON.parse of empty intervene output for realLargeFile large-file test) on origin/main too — pre-existing/environmental, not from tempdoc 618 — `scripts/agent-analytics/test-pipeline.mjs:361` (2026-07-01)
- [ ] test-pipeline.mjs has multiple stale/pre-existing failures on this machine (1f expects no additionalContext but intervene.mjs emits the auto-limit note for any >8KB file; 3a/3b hardcode D:\code\JustSearch; 10/11 expect retired guidance text incl. BrainView.tsx) — pre-dates 683; capture-evidence-bundle.mjs restoration fixed 1b/1c — `scripts/agent-analytics/test-pipeline.mjs:354` (2026-07-06)
- [ ] test-pipeline.mjs Test 10 is stale (not CI-wired): asserts subagent-guide brief includes 'BrainView.tsx' and is <500 chars, but the current hook says UnifiedChatView.ts and emits a multi-KB brief — pre-existing, would already fail if run — `scripts/agent-analytics/test-pipeline.mjs:1303,1332` (2026-07-22)

### obs:corpus — jseval corpus-fidelity's default --modes bm25_splade structurally cannot pass semantic=True self-dem
`kind: follow-up?` `anchor: corpus.py` `seen: 1` `first: 2026-07-01` `last: 2026-07-01`
- [ ] jseval corpus-fidelity's default --modes bm25_splade structurally cannot pass semantic=True self-demo corpora (no dense leg, and these corpora are specifically designed so lexical/SPLADE-only retrieval fails at the entry point) -- all 5 635-corpora/* corpora scored 0.017-0.214 nDCG (FAIL, too-hard) under the default mode but 0.53-0.84 (PASS, in-band) under --modes hybrid. Consider whether corpus-fidelity's default mode should be hybrid for semantic=True corpora, or whether the CLI should warn when certifying a semantic corpus under a dense-less mode -- scripts/jseval/jseval/commands/corpus.py cmd_corpus_fidelity default --modes (2026-07-01)

### obs:hybridsearchops — Stale code comments say recall-complete pool is 'default off' but resolved default is true — `Hybrid
`kind: defect?` `anchor: HybridSearchOps.java` `seen: 4` `first: 2026-06-30` `last: 2026-08-06`
- [ ] Stale code comments say recall-complete pool is 'default off' but resolved default is true — `HybridSearchOps.java:477`, `SearchExecutor.java:758`, `EnvRegistry.java:972`; also CE javadoc still names 'MiniLM-L6-v2' (model is gte-multilingual-reranker-base) at `RerankerConfig.java:59`, `KnowledgeSearchEngine.java:158-161`. Found during tempdoc 643 investigation. (2026-06-30)
- [x] ~~Low-signal fusion fallback constants drift from documented config defaults: HybridSearchOps.java:45-46 hardcodes DEFAULT_VECTOR_ONLY_CAP_LOW_SIGNAL=10 / DEFAULT_VECTOR_RRF_WEIGHT_LOW_SIGNAL=0.3 while claiming to match ResolvedConfig defaults, but ResolvedConfigBuilder.java:1480-1481 defaults are 3 / 0.25 — the no-config fallback path silently uses different fusion parameters than the documented defaults. Found during read-only constants-provenance sweep 2026-07-06.~~ RESOLVED (2026-08-19): constants aligned to 3 / 0.25 and every `DEFAULT_*` in `HybridSearchOps` is now pinned against the `ResolvedConfigBuilder` default in `CalibrationConstantsTest`, converting the header comment's claim into a test. Framing correction from the verification pass: the fallback fires only when `session.resolvedConfig == null`, which the production constructor cannot produce (`RuntimeSession.java:270-271`, `resolveFromConfigStore` falls through to a built config `:405-414`; null only in the test-mode constructor `:229`) — so this was a lying comment + divergent test-only fallback, never a live fusion misconfiguration. (2026-07-06)
- [ ] Engine robustness (for 636/643): fusion CAN bury a RETRIEVED gold below the returned top-10 when one leg is degraded (CASCADE_LEAK); the recall-complete splice is not wired into the shipped 3-way CC path and SPLADE is unprotected (`HybridSearchOps.java:477-490`, `SearchExecutor.java runThreeWay`). Latent, non-biting on healthy realistic corpora (MIRACL CASCADE_LEAK≈0.03) — measured in tempdoc 701 E3 (2026-07-08)
- [ ] `searchHybrid`'s dense leg calls `readPathOps.searchVector(v, l)` with no filter at all — no chunk exclusion, no collection scope — while the BM25 leg beside it goes through `applyRuntimeFilters`; production-unreachable after the null-filters fix but a live trap for any future null-filter caller — `modules/adapters-lucene/src/main/java/io/justsearch/adapters/lucene/runtime/HybridSearchOps.java:542` (2026-08-06)

### obs:jfhealthevent — 526 §17 review note 1 — JfHealthEvent.handleConditionClick skip-on-button is overly broad; if a card
`kind: defect?` `anchor: modules/ui-web/src/shell-v0/aggregate-substrate/components/JfHealthEvent.ts` `seen: 1`
- [ ] 526 §17 review note 1 — JfHealthEvent.handleConditionClick skip-on-button is overly broad; if a card later grows a non-recovery button, that click will also suppress selection. Use `data-recovery-op` attribute or a more specific selector. — `modules/ui-web/src/shell-v0/aggregate-substrate/components/JfHealthEvent.ts` (2026-05-21) — NOTE (403 Round 5): investigated, NOT changed — the recovery buttons aren't in `JfHealthEvent` or its `healthEventActivityRow` strategy (the host listener catches bubbled clicks), and it's unclear what `closest('button')` matches given the shell uses `jf-button`/`jf-control` custom elements (which `closest('button')` would NOT match). A blind selector swap risks a regression. Correct fix needs the actual click-target inventory first; current broad skip is safe, the defect is latent (no non-recovery button exists yet).

### obs:citationspanel — 526 §17 review note 3 — T1A citation anchor publish has no regression test; if `event.currentTarget`
`kind: defect?` `anchor: modules/ui-web/src/shell-v0/components/chat/CitationsPanel.ts` `seen: 1` `first: 2026-05-21` `last: 2026-05-21`
- [ ] 526 §17 review note 3 — T1A citation anchor publish has no regression test; if `event.currentTarget` rect extraction regresses, only manual browser testing would catch it. — `modules/ui-web/src/shell-v0/components/chat/CitationsPanel.ts:222` (2026-05-21)

### obs:dev-runner — Dev-runner is bound to main repo path (`F:/JustSearch`) and cannot live-verify Java backend changes
`kind: defect?` `anchor: scripts/dev/dev-runner.cjs` `seen: 8` `first: 2026-07-14` `last: 2026-08-19`
- [ ] Dev-runner is bound to main repo path (`F:/JustSearch`) and cannot live-verify Java backend changes made in worktrees. Worsened by main's gradle currently failing with a snakeyaml lockfile issue (`Resolved 'org.snakeyaml:snakeyaml-engine:3.0.1' which is not part of the dependency lock state`). Net effect: tempdoc 530 §4.2 `/api/governance/state` endpoint compiled cleanly in the worktree (class present in worktree's installed jar; route registered in source) but could not be live-HTTP-verified due to this contradiction. Resolution path: fix main's lockfile, or extend dev-runner to honor worktree CWD. — `scripts/dev/dev-runner.cjs` + `F:/JustSearch` main lockfile (2026-05-21, tempdoc 530 Pass-7 Phase D2)
- [ ] justsearch_dev_stop broke after its origin worktree was torn down: the MCP dev server resolves scripts/dev/dev-runner.cjs from the session-inject-time worktree path (removed adoption-legibility) -> MODULE_NOT_FOUND; stack had to be killed by PID tree. dev-runner stop path should resolve from the run record or repoRoot, not the session cwd at inject time. Hit 2026-07-14 during 725 A/B teardown; no stop-report written for run b1784f21. (2026-07-14)
- [ ] dev-runner does not capture Head-process stdout (backend.stdout.log is 27 bytes, only the port line), so Head-side VDU/ inference failures cannot be diagnosed from logs at all — only worker.log is written — `scripts/dev/dev-runner.cjs` (2026-07-29)
- [ ] dev-runner stopRun hard-kills with `taskkill /PID <pid> /T /F` (`scripts/dev/dev-runner.cjs:1910`) and never posts `/api/lifecycle/shutdown`, so `IndexingLoop.finalizeShutdownCommit()` (`IndexingLoop.java:739,766`) never runs. NOT a cause of unstamped indexes on its own — measured: a stamped index survives `/F` and returns COMPATIBLE/FINGERPRINT_MATCH on restart. It removes the shutdown backstop for a rebuild that completes right as the loop stops, and stops mid-rebuild leave the index unstamped. Tempdoc 805 G.1 gave the Tauri shell the ordered-shutdown path (`modules/shell/src-tauri/src/lib.rs:145-154`) but did not sweep the dev-runner (2026-08-10)
- [ ] A fresh worktree dev-data has no AI package registered, so ai_activate fails RUNTIME_VARIANT_NOT_INSTALLED even though the shared cuda12 llama-server IS staged at the main checkout — the two provisioning axes (runtime exe vs. installed package) are not distinguished in the failure message — `scripts/dev/dev-runner.cjs:457` (2026-08-14)
- [ ] dev-runner captures almost no Head log output (backend.stdout.log = 1 port line, backend.stderr.log = JVM warnings only, no Head log file under <dataDir>/logs/) so tail_log cannot diagnose Head-side behaviour — `scripts/dev/dev-runner.cjs:1439` (2026-08-18)
- [ ] dev-runner captured backend_stdout/backend_stderr were EMPTY (0 bytes) for a healthy run, so tail_log could not surface an application WARN that definitely fired — `scripts/dev/dev-runner.cjs` (2026-08-18)
- [ ] justsearch.dev.stop left an ORPHANED llama-server (holding 6.5 GB VRAM + its port) when the backend had respawned inference after a mid-run kill; the stop's orphan detection missed the respawned child and manual Stop-Process was needed — `scripts/dev/dev-runner.cjs` (2026-08-19)

### obs:logger — Governance `ts-any` gate: silent-growth across ~16 files untouched by 549 (logger.ts, platform.ts, t
`kind: environment?` `anchor: logger.ts` `seen: 1` `first: 2026-05-26` `last: 2026-05-26`
- [ ] Governance `ts-any` gate: silent-growth across ~16 files untouched by 549 (logger.ts, platform.ts, tauriRuntime.ts, WalkthroughCard.ts, HoverPreviewHost.ts, dev-fixtures.ts, stateValidator.ts, etc.) — pre-existing baseline drift (ungated under manual-only CI); needs a ts-any baseline rebalance or per-file changesets — `modules/ui-web/src` (2026-05-26)

### obs:actionledgerview — 550 thesis II NIT (independent review 2026-05-27): `ActionLedgerView` went stream-only and dropped t
`kind: defect?` `anchor: modules/ui-web/src/shell-v0/components/ActionLedgerView.ts` `seen: 2` `first: 2026-08-19` `last: 2026-08-19`
- [ ] 550 thesis II NIT (independent review 2026-05-27): `ActionLedgerView` went stream-only and dropped the error banner; a permanently-unreachable backend renders "No activity yet." indistinguishable from a genuinely empty ledger (no onError/connection-state signal on `openActionLedgerStream`). — `modules/ui-web/src/shell-v0/components/ActionLedgerView.ts`
- [ ] strip-token-fallbacks --check is RED on main (6 design-token fallbacks remain) — `modules/ui-web/src/shell-v0/components/ActionLedgerView.ts`, `modules/ui-web/src/shell-v0/components/RecentsMenu.ts` (2026-08-19)

### obs:isolatedbackendfixture — LESSON (static-green != live-working, 2026-05-27): merging 138 commits of `main` into a long-lived b
`kind: lesson?` `anchor: modules/system-tests/.../harness/IsolatedBackendFixture.java` `seen: 1` `first: 2026-05-27` `last: 2026-05-27`
- [ ] LESSON (static-green != live-working, 2026-05-27): merging 138 commits of `main` into a long-lived branch (worktree-550-impl) compiled green and passed all unit + FE tests, but ALL 3 live E2E suites failed — `IsolatedBackendFixture`'s readiness probe string-matched `"worker":{"state":"READY"`, which tempdoc 548's lifecycle-enum collapse had silently changed to the proto-prefixed `"LIFECYCLE_STATE_READY"` on the wire. The worker booted fine (worker.log: models loaded + indexing, no errors) — ready-but-undetectable. Only the live tier caught it. Takeaway: a string-matching test fixture against a wire/serialization shape is brittle across a serialization change landed on another branch; after a big merge, re-run the LIVE tier, not just compile+unit. Probe now accepts both forms — `modules/system-tests/.../harness/IsolatedBackendFixture.java:296` (2026-05-27)

### obs:tokens — `gen-token-names --check` is stale on main: `--surface-content-max-width` (added to styles/tokens.cs
`kind: defect?` `anchor: tokens.css` `seen: 2` `first: 2026-06-04` `last: 2026-08-08`
- [ ] `gen-token-names --check` is stale on main: `--surface-content-max-width` (added to styles/tokens.css by 559 commit 77d32f5f2) is missing from themes/token-names.generated.ts — run `node scripts/ci/gen-token-names.mjs` (2026-06-04)
- [ ] tokens.css's UNLAYERED [data-theme="light"] block (--selection-bg / --scrollbar-thumb / --scrollbar-thumb-hover) beats every user palette, since unlayered normal declarations outrank @layer user-theme — so a light-appearance user theme cannot own its selection or scrollbar colour (an amber selection persists under a green palette). The layered light block at line 425 is fine; only this trailing unlayered one leaks — `modules/ui-web/src/styles/tokens.css:838` (2026-08-08)

### obs:selectioncontextinjector — SelectionContextInjector.java uses a raw `"\n\n---\n\n"` separator literal instead of a canonical co
`kind: environment?` `anchor: modules/app-services/src/main/java/io/justsearch/app/services/conversation/spi/SelectionContextInjector.java` `seen: 3` `first: 2026-06-03` `last: 2026-08-18`
- [ ] SelectionContextInjector.java uses a raw `"\n\n---\n\n"` separator literal instead of a canonical constant (SeparatorConstantDrift test was red on main; allowlisted as the sanctioned escape during tempdoc 554 impl). Structural fix: hoist a shared SECTION_SEPARATOR constant to a module app-services can reach. — `modules/app-services/src/main/java/io/justsearch/app/services/conversation/spi/SelectionContextInjector.java:285` (2026-06-03)
- [ ] SelectionContextInjector.citationsEvent hardcodes chunkIndex 0 in the SSE map while the stashed citation is what the matcher uses — a selection has no chunk ordinal either (836 §8.4 applied only to DocAccess) — `modules/app-services/src/main/java/io/justsearch/app/services/conversation/spi/SelectionContextInjector.java:444` (2026-08-17)
- [ ] Selection-summarize stall (836 §9.9) is NOT a core.summarize problem — the same shape streams to done with body.docId (836 IMPL.6b); narrows it to the selection path — `modules/app-services/src/main/java/io/justsearch/app/services/conversation/spi/SelectionContextInjector.java` (2026-08-18)

### obs:index-general — Packaged Tauri CSP likely blocks the `index.html` Google Fonts `<link href="https://fonts.googleapis
`kind: defect?` `anchor: modules/ui-web/index.html` `seen: 3` `first: 2026-06-05` `last: 2026-08-08`
- [ ] Packaged Tauri CSP likely blocks the `index.html` Google Fonts `<link href="https://fonts.googleapis.com…">` (Plus Jakarta Sans display font): CSP `style-src 'self' 'unsafe-inline'` (no googleapis) + `font-src 'self' data:` (no gstatic) — works in vite dev, silently drops the display font in packaged builds — `modules/ui-web/index.html:26` vs `modules/shell/src-tauri/tauri.conf.json:70` (2026-06-05)
- [ ] ui-web index.html loads /src/main.jsx and titles the document 'ui-web' — a .jsx entry filename and a scaffold title on a Lit/shell-v0 app; left untouched as out of scope for the identity kernel — `modules/ui-web/index.html:22` (2026-08-06)
- [ ] index.html links Google Fonts over the network for --font-display ('Plus Jakarta Sans'), so a local-first install silently loses its display face offline; no font is bundled (no @font-face, no woff/ttf assets in modules/ui-web) — `modules/ui-web/index.html:26` (2026-08-08)

### obs:coreplugin — Surface audience drift: `core.health-surface` + `core.activity-surface` are USER in Java CoreSurface
`kind: defect?` `anchor: modules/ui-web/src/shell-v0/plugin-api/CorePlugin.ts` `seen: 2` `first: 2026-06-09` `last: 2026-08-19`
- [x] ~~Surface audience drift: `core.health-surface` + `core.activity-surface` are USER in Java CoreSurfaceCatalog but OPERATOR in FE CorePlugin.ts — two-authority drift (tempdoc 571 CI-2) — `modules/ui-web/src/shell-v0/plugin-api/CorePlugin.ts:109,151` (2026-06-09)~~ RESOLVED (2026-08-19): settled by 857 PR-B — CorePlugin.ts now declares both USER, matching CoreSurfaceCatalog.java; both KNOWN_PARITY_DRIFT entries deleted.
- [x] ~~FE/Java surface audience drift: core.health-surface and core.activity-surface declare audience OPERATOR in CorePlugin.ts but Audience.USER in CoreSurfaceCatalog.java; FE re-declaration wins in the shell, so the wire catalog is wrong about who sees them (found by 852-S0's new parity leg, recorded in KNOWN_PARITY_DRIFT) — `modules/ui-web/src/shell-v0/plugin-api/CorePlugin.ts:179` (2026-08-19)~~ RESOLVED (2026-08-19): settled by 857 PR-B — CorePlugin.ts now declares both USER, matching CoreSurfaceCatalog.java; both KNOWN_PARITY_DRIFT entries deleted.

### obs:coreplugin-missing — FE-only surfaces: `core.memory-surface` + `core.command-palette` exist in FE CorePlugin.ts but are a
`kind: defect?` `anchor: modules/ui-web/src/shell-v0/plugin-api/CorePlugin.ts` `seen: 1` `first: 2026-06-09` `last: 2026-06-09`
- [ ] FE-only surfaces: `core.memory-surface` + `core.command-palette` exist in FE CorePlugin.ts but are absent from the Java CoreSurfaceCatalog served by /api/registry/surfaces (tempdoc 571 CI-2) — `modules/ui-web/src/shell-v0/plugin-api/CorePlugin.ts:89,137` (2026-06-09)

### obs:runcontrolintent — §30 "stop a run" wiring gap — VERIFY whether stopping an agent run actually halts the BACKEND loop o
`kind: follow-up?` `anchor: modules/ui-web/src/shell-v0/controllers/runControlIntent.ts` `seen: 1` `first: 2026-06-11` `last: 2026-06-11`
- [ ] §30 "stop a run" wiring gap — VERIFY whether stopping an agent run actually halts the BACKEND loop or leaks tokens/compute. Verified facts: (a) the only full cancel, `cancelSession()`, does `this.abortController?.abort()` (the *controller's* stream) **+ `DELETE /api/chat/sessions/{id}`** (the real backend cancel) — `AgentSessionController.ts:1126`; (b) it is reachable ONLY through the `halt` RunDirective, which has ZERO live dispatchers (only a doc-comment mentions `dispatchRunControl({kind:'halt'})`); (c) the actual live stop affordance is the composer's `@composer-cancel → this.abortController?.abort()` — but `this` is the VIEW (`UnifiedChatView.ts:1723`), whose abortController is a DIFFERENT one than the agent controller's, and it sends NO backend DELETE. So clicking stop during an agent run may abort the wrong (idle) stream and leave the backend `AgentLoopService` iterating. Open questions for a live pass: does the SSE-disconnect from `abort()` make the backend stop on its own, or does the loop keep running? Should a real "stop the agent" control be wired to `dispatchRunControl({kind:'halt'})`? (The §30 comments themselves are now ACCURATE — `5914193e5` fixed the earlier halt-vs-abort confusion; this is the substantive residual.) — `modules/ui-web/src/shell-v0/controllers/runControlIntent.ts`, `AgentSessionController.ts:1126`, `UnifiedChatView.ts:1723` (2026-06-11)

### obs:knowledgesearchengine — Search result count is nondeterministic across runs of the same query: LLM query expansion success-v
`kind: defect?` `anchor: modules/app-services/.../KnowledgeSearchEngine.java` `seen: 6` `first: 2026-06-12` `last: 2026-08-12`
- [ ] Search result count is nondeterministic across runs of the same query: LLM query expansion success-vs-timeout changes totalHits (~12 vs 31 observed); backend determinism/timeout policy question for the search-quality domain — `modules/app-services/.../KnowledgeSearchEngine.java` (expansion eligibility ~line 337) (2026-06-12)
- [ ] CE-gate probe verdict (776 stack-window, SUPERSEDES 774 J.2): DOCS_TOO_LONG never fires under eval — reading 3 (average never populated AS THE GATE READS IT) is TRUE; readings 1 (gate-never-fires) + 2 (leg-mislabel) REFUTED by live evidence. Dual mechanism: (1) gate reads WorkerStatusCache.cachedAvgContentLengthChars (KnowledgeSearchEngine.java:860), populated ONLY by WorkerStatusCache.status() (WorkerStatusCache.java:153) i.e. the head /api/knowledge/status projection; jseval polls ONLY /api/status (worker projection) never /api/knowledge/status, so cache stays 0 -> gate reads 0 -> CE runs on 35508-char legal-clerc-200 docs. (2) worker OperationalMetrics avg is session-lifetime (recordContentLength <- JobBatchWriter.java:147); a boot over an existing index has avg=0. Live proof: same query CE=executed with no prior knowledge-status call, CE=skipped:DOCS_TOO_LONG after one; reboot-over-index avg=0 CE=executed even with refresh. Fix routes to: source the avg from worker telemetry on the search/index path, not the lazy status projection; consider persisting it as an index property. Evidence: tmp/analysis-624/776/stack-window/ce-gate-probe/ — `modules/app-services/src/main/java/io/justsearch/app/services/worker/KnowledgeSearchEngine.java:860` (2026-07-22)
- [ ] Orchestrator brief-premise errors: two in one arc (787 item 4b assumed jseval sends the deprecated mode shape — it doesn't, KnowledgeSearchEngine.java:683 sets pipeline unconditionally; 783 §B.1 assumed real corpora carry answer strings for offset resolution — legal-clerc/miracl-de/miracl-fr all have answer:""). Both cost a worker round-trip and were caught only after implementation. Candidate rule: before briefing work that depends on a data/field/code shape, verify that shape (one grep or one python -c) IN the brief-writing step, and cite it in the brief — briefs already require file:line evidence from workers but not from the brief author (2026-07-27)
- [ ] UPDATE (supersedes the earlier two-instance note): FOUR orchestrator brief-premise errors in one chartering sitting, each refutable by one command, each caught only after a full worker round-trip — 787-4b (assumed jseval sends the deprecated wire shape; KnowledgeSearchEngine.java:683 sets pipeline unconditionally), 783 B.1 (assumed real corpora carry answer strings; legal-clerc/miracl-de/miracl-fr all answer:""), 784 (assumed chunk-SPLADE unbuilt; shipped 2026-07-11 behind rag.chunk_splade.enabled), 785 (assumed a 20-30x per-doc enrichment anomaly; normalized by bytes legal is the FASTEST corpus at 35.5kB/s vs scifact 30.8kB/s — wrong unit, no pathology). Proposed rule: charters/briefs must run the one refuting command per load-bearing factual premise and cite the result inline — the same file:line evidence standard briefs already impose on workers, applied to the brief author (2026-07-27)
- [ ] Search totalHits nondeterminism mechanism corrected (821 §L.1): the tombstoned rerank gate is inert at defaults; the LIVE cause is the EXPANSION_BUDGET_MS race — `KnowledgeSearchEngine.java:783-816` (2026-08-12)
- [ ] Second instance of the lazy-cache-cold pattern: `KnowledgeSearchEngine.java:1264-1267` indexCapabilities reads `RemoteKnowledgeClient.cachedOperationalView` (:762-774) populated only by /api/status — same class as the avgContentLengthChars cache (2026-08-12)

### obs:searchplanner — Live worker returns chunk-merge skipped(SKIPPED_QUERY_SYNTAX) even for simple/absent querySyntax, co
`kind: defect?` `anchor: modules/worker-services/.../plan/SearchPlanner.java` `seen: 4` `first: 2026-06-12` `last: 2026-08-12`
- [ ] Live worker returns chunk-merge skipped(SKIPPED_QUERY_SYNTAX) even for simple/absent querySyntax, contradicting SearchPlanner.planChunkMerge on main which only skips for LUCENE — suspect stale worker dist on the shared dev stack; re-verify after fresh installDist — `modules/worker-services/.../plan/SearchPlanner.java:252` (2026-06-12)
- [ ] No collection-enumeration endpoint exists: the Library's 811 C-2a "Other sources" section must enumerate via a `*:*` lucene-syntax facet search because a blank query short-circuits to EmptyQueryDecision before facets are planned — a first-class `GET /api/indexing/collections` returning {collection,docCount}[] would replace the probe — `modules/worker-services/src/main/java/io/justsearch/indexerworker/services/plan/SearchPlanner.java:73` (2026-08-06)
- [ ] Facets silently omitted with no reason code: page-2+ (non-blank cursor) never facets, multi-leg additionally requires non-blank queryString — `SearchPlanner.java:124-128,170-174`; and FacetingEngine returns empty+truncated=false on IOException and silent empty maps for non-facetable fields — `FacetingEngine.java:198-200,108-124` (2026-08-12)
- [ ] Worker-side 100-cap on search limit is unsignaled and undocumented: `SearchPlanner.java:37,194-197` clamps silently, response has no requested-vs-applied echo, api-contract-map.md:441 lists limit unbounded (contrast MCP's honest max-50) (2026-08-12)

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

### obs:server — Dev-stack ownership gates only `start` (spawn); a non-owner agent can POST `/api/knowledge/ingest`,
`kind: defect?` `anchor: scripts/dev/justsearch-dev-mcp/server.mjs` `seen: 4` `first: 2026-06-17` `last: 2026-08-18`
- [ ] Dev-stack ownership gates only `start` (spawn); a non-owner agent can POST `/api/knowledge/ingest`, `/api/indexing/reindex|gc|migration`, or `reload` against a peer's running stack with no owner check — ownership grants no exclusivity over the mutating/lifecycle surface — `scripts/dev/justsearch-dev-mcp/server.mjs` (2026-06-17)
- [ ] Fresh worktree dev-data has no AI chat-model pack imported; POST /api/ai/runtime/activate fails MODEL_PATH_REQUIRED even with llama-server auto-staged. /api/ai/packs/* expects a packaged manifest (end-user Install-AI flow), not a local-file import. Workaround: GET/POST full /api/settings/v2 with llm.modelPath set to a real local GGUF, then retry activate. Worth a documented dev-stack shortcut. — `scripts/dev/justsearch-dev-mcp/server.mjs:2432-2520` (2026-07-01)
- [ ] MCP dev-tools cannot reach /infra/capabilities or /infra/health (absent from fetch_api_json map + api_call allowlist) and have no raw-gRPC or no-JVM GPU probe — the 4 unique hand-tools covering those niches were owner-deleted (742 followup) so the gap is now uncovered; candidates: add both /infra endpoints to the MCP allowlist, optionally a gpu/nvml preflight probe — `scripts/dev/justsearch-dev-mcp/server.mjs:930` (2026-07-16)
- [ ] dev-MCP api_call allowlist has no `POST /api/operations/{id}/invoke`, so operation-catalog handlers (core.rebuild-index, core.bulk-reindex) cannot be invoked live through the sanctioned tool — `scripts/dev/justsearch-dev-mcp/server.mjs:1008` (2026-08-18)

### obs:searchresultsrenderer — **(602 R3 spillover)** `SearchResultsRenderer` (the `x-ui-renderer='search-results'` declared-surfac
`kind: defect?` `anchor: modules/ui-web/src/shell-v0/renderers/controls/SearchResultsRenderer.ts` `seen: 1` `first: 2026-06-18` `last: 2026-06-18`
- [ ] **(602 R3 spillover)** `SearchResultsRenderer` (the `x-ui-renderer='search-results'` declared-surface renderer) is a THIRD search-result renderer that reads raw `hit` fields and does NOT use the shared `projectResultView` view-model or the `resultRowPresentation` path/highlight authority — so it can drift from the two governed rows. Own `ResultHit` shape + no query in scope (can't highlight). Folding it onto the shared projection is a separate step (or 570's grand result-as-projection). — `modules/ui-web/src/shell-v0/renderers/controls/SearchResultsRenderer.ts:67` (2026-06-18)

### obs:fixtures — `modules/ui-web/src/mocks/fixtures.mjs` (+ fixtures.d.mts/.test.ts) is orphaned React-era demo data
`kind: follow-up?` `anchor: modules/ui-web/src/mocks/fixtures.mjs` `seen: 1` `first: 2026-06-19` `last: 2026-06-19`
- [ ] `modules/ui-web/src/mocks/fixtures.mjs` (+ fixtures.d.mts/.test.ts) is orphaned React-era demo data — not referenced by shell-v0; candidate for deletion (tempdoc 615 React-residue audit) (2026-06-19)

### obs:resourceregistry-test — resourceRegistry.test.ts "produces the four expected registrations" fails in the FULL vitest suite b
`kind: environment?` `anchor: resourceRegistry.test.ts` `seen: 2` `first: 2026-06-19` `last: 2026-08-13`
- [ ] resourceRegistry.test.ts "produces the four expected registrations" fails in the FULL vitest suite but passes in isolation — global resource-view-renderer registry pollution / order-dependence (pre-existing on main HEAD f3e002117, confirmed via pre-merge worktree run; not 609). Owner: 421/610/613 renderer-registry. Fix: reset the registry in beforeEach or isolate the count test. (2026-06-19)
- [ ] ui-web full-suite flakes under load: resourceRegistry.test.ts 'four expected registrations' (5s timeout) + EnvelopeStream.test.ts heartbeat-watchdog reconnect both fail in the full run and pass in isolation — `modules/ui-web/src/shell-v0/renderers/resourceRegistry.test.ts` (2026-08-13)

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
`kind: defect?` `anchor: scripts/jseval/release.v1.json` `seen: 3` `first: 2026-07-01` `last: 2026-07-31`
- [ ] mixed/enron-qa has no committed fetch/materialization mechanism (datasets/ is gitignored, no corpus-fetch-enron equivalent exists) -- a sibling gap to the MIRACL/CourtListener issue tempdoc 666 fixed; a fresh worktree cannot reproduce it. Blocked recomposing release.v1.json with a fully cohort-consistent 5-corpus set -- `scripts/jseval/release.v1.json` measured.mixed/enron-qa._cohort_note (2026-07-01)
- [ ] Published release cohort is unmeasurable: `release.v1.json` records no `run_dir` and no matching run directory survives on disk, so README nDCG numbers cannot be re-scored or re-derived — the composed release does not point back at the runs it came from (tempdoc 800) (2026-07-31)
- [ ] README calls the benchmark run 'one reproducible release run' but release.v1.json records no artifact pointer and the runs are gone — the reproducibility claim is unbacked for the CURRENT published table even after 802 makes it recordable going forward — `README.md:120` (2026-07-31)

### obs:staged-recall-accounting — staged_recall_accounting.py module docstring 'Output shape v1' example (lines ~40-56) is stale — mis
`kind: defect?` `anchor: staged_recall_accounting.py` `seen: 2` `first: 2026-06-30` `last: 2026-08-19`
- [ ] staged_recall_accounting.py module docstring 'Output shape v1' example (lines ~40-56) is stale — missing oracle_judge_ndcg_ceiling, judge_headroom_ceiling, fp_mapping which the actual produce() output includes. Noticed while adding judge_rank_histogram during tempdoc 643 Stage 1b. (2026-06-30)
- [ ] staged_recall_accounting's self-reconciliation is invalid when top_n != 10: it cross-checks depth-top_n presence against the harness's fixed R@10, so a --top-k 100 run reports exactly its rank_11_plus count as 'mismatches' (measured: 12/50 on de-miracl-1k-verbose k100, 0/50 on the same cell at k10) — reconciliation should compare at matching depth or declare itself k10-only — scripts/jseval/jseval/projections/staged_recall_accounting.py (2026-08-19, 748 G.3 campaign) (2026-08-19)

### obs:test-compare — compare_runs.compare_pipeline_timing has no unit test (test_compare.py covers compare() + per_query_
`kind: environment?` `anchor: test_compare.py` `seen: 1` `first: 2026-07-01` `last: 2026-07-01`
- [ ] compare_runs.compare_pipeline_timing has no unit test (test_compare.py covers compare() + per_query_diff only) — pre-existing gap, noticed while adding compare_stage_decomposition (tempdoc 647) (2026-07-01)

### obs:0024-app-packaging-nsis-per-user-download — ADR-0024 stale: claims '~748 MB installer, no models bundled, all ~8.5 GB downloaded post-install' b
`kind: defect?` `anchor: docs/decisions/0024-app-packaging-nsis-per-user-download.md` `seen: 1` `first: 2026-07-01` `last: 2026-07-01` `status: proposed-retire (observation INVERTED — see the 2026-07-30 correction line; ADR-0024's update block retracted, nothing left to fix)`
- [x] ~~ADR-0024 stale: claims '~748 MB installer, no models bundled, all ~8.5 GB downloaded post-install' but stageOnnxModels (includeOnnxModels defaults true) bundles ~3.5 GB ONNX retrieval models + CPU llama-server — only GGUF chat + cuda-runtime are download-on-demand~~ — `modules/ui/build.gradle.kts:384` vs `docs/decisions/0024-app-packaging-nsis-per-user-download.md:37-52` (2026-07-01)
- [x] CORRECTION (2026-07-30, sandbox round 7): the line above read the Gradle *default* (`includeOnnxModels`) as a description of the shipped artifact and was wrong — `.github/workflows/build-installer.yml` has set `ORG_GRADLE_PROJECT_skipOnnxModels: "true"` unconditionally since the initial public commit (2026-06-25), predating the observation, and round 7 measured zero `.onnx` bytes after a clean install. ADR-0024 was NOT stale; the 2026-07-02 update block it produced was, and has been retracted. Do not re-derive the bundled-models claim from the property default — it describes an unbuildable local configuration (~3.5 GB of ONNX would exceed the ~2 GB 32-bit NSIS limit). — `.github/workflows/build-installer.yml:198` (2026-07-30)

### obs:test-report-ci-walltime-attribution — scripts/ci/test-*.mjs unit tests (test-report-ci-walltime-attribution, test-report-unit-test-attribu
`kind: defect?` `anchor: scripts/ci/test-report-ci-walltime-attribution.mjs` `seen: 1` `first: 2026-07-02` `last: 2026-07-02`
- [ ] scripts/ci/test-*.mjs unit tests (test-report-ci-walltime-attribution, test-report-unit-test-attribution) are not run by any CI workflow — no node --test lane; regressions in these scripts are caught only by their live CI invocation, not the unit tests — `scripts/ci/test-report-ci-walltime-attribution.mjs` (2026-07-02)

### obs:multiplexedstream — governance kernel: ts-any gate fails on modules/ui-web/src/shell-v0/streaming/MultiplexedStream.ts (
`kind: environment?` `anchor: MultiplexedStream.ts` `seen: 3` `first: 2026-07-02` `last: 2026-07-30`
- [ ] governance kernel: ts-any gate fails on modules/ui-web/src/shell-v0/streaming/MultiplexedStream.ts (`(import.meta as any).env`) — pre-existing since PR #22 (tempdoc 662), not registered in gates/ts-any/baseline.txt. Not part of any tempdoc-655 work. (2026-07-02)
- [ ] ts-any gate fails pre-existing on search-thread base: MultiplexedStream.ts:60 (import.meta as any) exists at base 2ef7396 with ratchet baseline 0 — 683-era addition without a changeset/rebalance — `modules/ui-web/src/shell-v0/streaming/MultiplexedStream.ts:60` (2026-07-07)
- [ ] Bug-class: a stream listener invocation is not proof of a frame — EnvelopeStream.notify (open/error/watchdog) and MultiplexedStream.broadcastConnectionChange re-deliver the SAME seq; any consumer stamping liveness inside its listener will falsely mark itself healthy on a mere reconnect (fixed for the indexing-jobs bridge in 798 B4 via a seq guard; other consumers unaudited) — `modules/ui-web/src/shell-v0/streaming/MultiplexedStream.ts:341` (2026-07-30)

### obs:dev-runner-drift — justsearch_dev_start defaults to launching the backend from the MAIN checkout's installed dist (cwd=
`kind: follow-up?` `anchor: scripts/dev/dev-runner.cjs` `seen: 1` `first: 2026-07-02` `last: 2026-07-02`
- [ ] justsearch_dev_start defaults to launching the backend from the MAIN checkout's installed dist (cwd=main repo, ui.bat), not the calling worktree's — even when called from inside a worktree session. Verifying a worktree-local Java fix live against the dev stack silently ran unmodified main-branch code for ~40 minutes (evidence looked stale after 3 restarts + hard-cleans) until distFrom was passed explicitly pointing at the worktree path. The tool schema documents this (`distFrom`, tempdoc 606 Piece 4) but nothing nudges an agent to set it — worth a hook-hint or MCP tool default when sessionId resolves to a worktree cwd. `scripts/dev/dev-runner.cjs`, `scripts/dev/justsearch-dev-mcp/server.mjs` (2026-07-02)

### obs:pendingauthorizationbridge — PR#55 (655 MCP policy) post-merge review F1 (medium): duplicate approval ceremony for browser-origin
`kind: defect?` `anchor: pendingAuthorizationBridge.ts` `seen: 1` `first: 2026-07-02` `last: 2026-07-02`
- [ ] PR#55 (655 MCP policy) post-merge review F1 (medium): duplicate approval ceremony for browser-originated gated invocations — pendingAuthorizationBridge.ts subscribes with no transport filter and its handledIds guard never fires on the in-page invoke-first path (Shell.ts:1048), so a gated in-page call queues TWO identical dialogs (second approval 410s harmlessly). One-line fix: filter bridge to transport==='MCP' (field already serialized) + a bridge test pinning non-MCP broadcast -> no presentation; also unhardcode InvocationProvenance.mcp in AuthorizationController.executeApprovedPending:271. Lower: F2 schema-drift guard manual for browse/ingest (fails safe); F4 no serverInfo.version patch bump despite gated-ingest flow change (defensible). (2026-07-02)

### obs:unanchored — scoop python/java `current` symlinks are Windows-unfriendly (mid-session regression, likely needs `s
`kind: defect?` `anchor: none` `seen: 1` `first: 2026-04-23` `last: 2026-04-23`
- [ ] scoop python/java `current` symlinks are Windows-unfriendly (mid-session regression, likely needs `scoop reset` with admin or reinstall) — blocks running `jseval` / java-based CLIs without fully-qualified versioned paths — `F:\scoop\apps\{python,temurin25-jdk}\current` (2026-04-23)

### obs:unanchored-general-5 — 635 suite-profile: --records-root agent-record lookup expects tmp/635-<dataset-name>/ but the prose
`kind: defect?` `anchor: none` `seen: 1` `first: 2026-06-23` `last: 2026-06-23`
- [ ] 635 suite-profile: --records-root agent-record lookup expects tmp/635-<dataset-name>/ but the prose flagship records live in tmp/635-prose-v2/ (name mismatch) — agent_acc_delta shows only for members whose record dir matches the dataset name (2026-06-23)

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

### obs:unanchored-general-18 — Backend message catalog `registry-surface.en.properties` lacks entries for `token-editor-surface` an
`kind: defect?` `anchor: none` `seen: 1` `first: 2026-05-29` `last: 2026-05-29`
- [ ] Backend message catalog `registry-surface.en.properties` lacks entries for `token-editor-surface` and `command-palette`; both id-derive ("Token Editor"/"Command Palette"). Add authored label/description for a complete surface-label authority. (2026-05-29)

### obs:unanchored-gate-red-2 — 553 Phase 2b (clone tripwire gate) deferred: jscpd/CPD not installed + no CPD gradle task; a clone g
`kind: follow-up?` `anchor: none` `seen: 1` `first: 2026-05-27` `last: 2026-05-27`
- [ ] 553 Phase 2b (clone tripwire gate) deferred: jscpd/CPD not installed + no CPD gradle task; a clone gate needs a new heavy devDependency for reduction-grade ratchet value (keystone covers the high-value declared class). Clean path: `npm i -D jscpd` + ratcheting-baseline `clone` gate. — `scripts/governance/gates` (2026-05-27)

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

### obs:unanchored-general-35 — `@types/dompurify@^3.2.0` in ui-web devDeps is a redundant STUB — dompurify (now 3.4.10) ships its o
`kind: defect?` `anchor: none` `seen: 1` `first: 2026-06-16` `last: 2026-06-16`
- [ ] `@types/dompurify@^3.2.0` in ui-web devDeps is a redundant STUB — dompurify (now 3.4.10) ships its own type definitions; `npm install` warns "you do not need this installed". Removable from `modules/ui-web/package.json` (typecheck stays green without it). — `modules/ui-web/package.json:60` (2026-06-16)

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

### obs:inferencehandlers — VDU offline processing (post-672) drains in ~100-doc batches and then stops with vduProcessing=false
`kind: defect?` `anchor: modules/ui/src/main/java/io/justsearch/ui/api/InferenceHandlers.java` `seen: 1` `first: 2026-07-03` `last: 2026-07-03`
- [ ] VDU offline processing (post-672) drains in ~100-doc batches and then stops with vduProcessing=false while visualTextNeededCount>0 — the idle/energy auto-trigger does not chain batches on a loaded queue; each batch needed a manual POST /api/offline/process re-trigger (observed live draining synth-scan-v1's 360 docs at ~9.3 docs/min per batch). Whether per-batch stop-without-continuation is intended energy behavior or a gap belongs to 672's owner. — `modules/ui/src/main/java/io/justsearch/ui/api/InferenceHandlers.java:555` (2026-07-03)

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

### obs:unanchored-drift-20 — ui-shot step skeleton-library fails in every harness mode (live --no-demo, --fixtures, demo): rail c
`kind: defect?` `anchor: none` `seen: 1` `first: 2026-07-06` `last: 2026-07-06`
- [ ] ui-shot step skeleton-library fails in every harness mode (live --no-demo, --fixtures, demo): rail click reaches the library surface but data-testid skeleton-library never becomes visible; the e2e_view_delay_ms=4000 skeleton-hold mechanism has no matching selectors in modules/ui-web/src (grep empty) — step vs FE drift predating worktree 683; found during the 683 liveness census (2026-07-06)

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
- [ ] SCOPE NARROWED (2026-08-19, per-occurrence verification): the line above no longer describes the shipped default path — F-031's late-chunking single-pass lane (default true, `ResolvedConfigBuilder.java:1160`) routes chunked parents through `embedWithSpans` (one forward pass, no discarded window vectors, `CombinedEnrichmentBackfillOps.java:563-574,651`). The double-work mechanism itself is intact (`OnnxEmbeddingEncoder.embedBatchWithChunking:584-665` still discards `chunkVectors` at `EmbeddingService.java:421`) and remains REACHABLE on four lanes: (1) docs over the 8192-token single-pass limit (`:658-662`, counter `longDocWindowed`); (2) GPU arena OOM fallback (`:664-678`, counter `arenaOomWindowed`); (3) `late_chunking_enabled=false`; (4) the non-combined embed lane when <2 enrichment stages are available (`BackfillScheduler.java:632-635,669` → `EmbeddingBackfillOps.java:100`). Fix-worthiness should be measurement-gated: read the `longDocWindowed`/`arenaOomWindowed` counters (already logged, `CombinedEnrichmentBackfillOps.java:1098-1111`) off a live long-doc ingest before designing; the honest scope of any fix is "one chunk-boundary authority", not plumbing. (2026-08-19)
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
`kind: environment?` `anchor: docs/reference/configuration/runtime-config-ownership-matrix.md` `seen: 3` `first: 2026-07-08` `last: 2026-08-06`
- [ ] runtime-config-ownership-matrix stale: verify-runtime-config-matrix fails on 6 env/sysprop pairs missing from the matrix (JUSTSEARCH_MODE, JUSTSEARCH_RERANK_JUDGE_ARBITRATION_*, JUSTSEARCH_RERANK_JUDGE_BLEND_*); pre-existing, unrelated to doc-drift edits; needs a matrix regen — `docs/reference/configuration/runtime-config-ownership-matrix.md` (2026-07-08)
- [ ] runtime-config-ownership-matrix.md is stale vs verify-runtime-config-matrix.mjs (exit 1): 6 missing env/sysprop pairs (JUSTSEARCH_MODE + 5x JUSTSEARCH_RERANK_JUDGE_*) — pre-existing drift unrelated to 706's two new index.ocr rows, found while verifying those (2026-07-10)
- [ ] verify-runtime-config-matrix FAILs on main-derived tree: missing env/sysprop pair JUSTSEARCH_APP_VERSION | justsearch.app.version — pre-existing, unrelated to 813 docs edits — `docs/reference/configuration/runtime-config-ownership-matrix.md` (2026-08-06)

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

### obs:writepathops — STRUCTURAL TRAP confirmed live: KnnFloatVectorField (VECTOR) is non-stored and silently DESTROYED by
`kind: follow-up?` `anchor: modules/adapters-lucene/src/main/java/io/justsearch/adapters/lucene/runtime/WritePathOps.java` `seen: 1` `first: 2026-07-10` `last: 2026-07-10`
- [x] ~~STRUCTURAL TRAP confirmed live: KnnFloatVectorField (VECTOR) is non-stored and silently DESTROYED by any subsequent readModifyWrite; chunk docs get re-queued (WritePathOps.java:471) but PARENT docs do not — any new enrichment pass that writes VECTOR in its own RMW before another stage's RMW loses the vector with status still COMPLETED (no error, no signal). The combined pass's one-RMW bundling is the only thing upholding this undeclared invariant. Candidate for 710 (invariant should be declared/enforced or vectors preserved in RMW) — `modules/adapters-lucene/src/main/java/io/justsearch/adapters/lucene/runtime/WritePathOps.java:471`~~ (2026-07-10)
- [x] RESOLVED (2026-08-19, per-occurrence code verification): fixed by tempdoc 711 / F-032. Every non-stored/non-docValues field declares an `rmwPolicy` in `fields.v1.json` (vector `:180`, chunk_vector `:193`, splade `:433`); enforcement is structural inside `readModifyWrite` (`WritePathOps.java:354`, engine `:384-428`); startup fail-fast for undeclared fragile fields is invoked at open time (`FieldMapper.validateRmwPolicies` `:198-245`, called from `ComponentsFactory.java:124`); a null re-read with COMPLETED status downgrades to PENDING (`WritePathOps.java:408-418`) and `StatusArtifactContract.enforce` (`WritePathOps.java:365`, default mode FAIL) rejects COMPLETED-without-artifact. No doc-rebuild bypass remains (3 `toDocument` call sites, all covered); regression suite `RmwFieldPreservationTest` incl. same-doc-twice-in-one-batch. (2026-08-19)

### obs:bertnerinference — NER tokenizer construction lacked explicit truncation=false/padding=false (unlike SPLADE/embed's tok
`kind: follow-up?` `anchor: modules/worker-core/src/main/java/io/justsearch/indexerworker/ner/BertNerInference.java` `seen: 2` `first: 2026-07-10` `last: 2026-07-27`
- [ ] NER tokenizer construction lacked explicit truncation=false/padding=false (unlike SPLADE/embed's tokenizer setup) — latent because inferBatch only ever called single-text tokenizer.encode() before tempdoc 710 Move 3 introduced batchEncode(); fixed as part of Move 3, but the underlying DJL batchEncode-vs-encode default-padding asymmetry is worth a general note for any future tokenizer construction site — `modules/worker-core/src/main/java/io/justsearch/indexerworker/ner/BertNerInference.java:108` (2026-07-10)
- [ ] NER emits zero 'NER per-call profile' lines on a full legal-clerc-200 enrichment run (22.2s of NER work) — PROFILE_LOG_INTERVAL=100 never reached, so NER has no encoder-level log attribution — `modules/worker-core/src/main/java/io/justsearch/indexerworker/ner/BertNerInference.java:62` (2026-07-27)

### obs:modelcapabilityresolver — Pre-existing: Jackson tools.jackson.databind JsonNode.isTextual()/asText() are deprecated in the ver
`kind: environment?` `anchor: modules/ort-common/src/main/java/io/justsearch/ort/ModelCapabilityResolver.java` `seen: 1` `first: 2026-07-11` `last: 2026-07-11`
- [ ] Pre-existing: Jackson tools.jackson.databind JsonNode.isTextual()/asText() are deprecated in the version in use; used throughout ModelCapabilityResolver.resolvePrefixes/resolveLabelMapping — `modules/ort-common/src/main/java/io/justsearch/ort/ModelCapabilityResolver.java` (surfaced under -Xlint:deprecation, out of scope for tempdoc 711 Item 3) (2026-07-11)

### obs:unanchored-general-78 — Recurring orchestration failure mode (2x this session): a stopped/idle subagent is NOT re-woken when
`kind: lesson?` `anchor: none` `seen: 1` `first: 2026-07-11` `last: 2026-07-11`
- [ ] Recurring orchestration failure mode (2x this session): a stopped/idle subagent is NOT re-woken when its own backgrounded shell jobs complete — completion notifications are lost if they fire while the agent is between turns; orchestrator had to detect completion via on-disk evidence (result-file mtimes, GPU idle) and manually SendMessage-resume both times. Candidate agent-lessons.md entry: long-running background jobs inside subagents need orchestrator-side completion polling, or the subagent should poll rather than background-and-wait — platform behavior, not repo bug (2026-07-11)

### obs:documentpane — Stale comments in DocumentPane.ts reference retired InspectorPane.ts (e.g. line 10, line 60 'Mirrors
`kind: defect?` `anchor: modules/ui-web/src/shell-v0/components/documentPane/DocumentPane.ts` `seen: 3` `first: 2026-07-11` `last: 2026-08-19`
- [ ] Stale comments in DocumentPane.ts reference retired InspectorPane.ts (e.g. line 10, line 60 'Mirrors InspectorPane's local VisualExtractionEvidence shape') — that component was removed (only referenced in UnifiedChatView.test.ts's 'retired jf-inspector-pane never appears' regression test); the comments should point at the actual current pattern origin instead — `modules/ui-web/src/shell-v0/components/documentPane/DocumentPane.ts:10,60` (2026-07-11)
- [ ] DocumentPane fetches /api/preview with a hard-coded maxChars=5000 and never reads the response's 'truncated' flag (PreviewController.java:171), so a citation past char ~5000 silently highlights nothing — scrollToHighlight finds no .hl-strong and no-ops — `modules/ui-web/src/shell-v0/components/documentPane/DocumentPane.ts:279` (2026-08-18)
- [ ] 849 S9: DocumentPane.emitVisibleRange emits WINDOW-relative data-line indices on `pane-visible-range`, whose contract documents absolute document lines — latent (no production consumer today), becomes wrong the moment one reads it — `modules/ui-web/src/shell-v0/components/documentPane/DocumentPane.ts:emitVisibleRange` (2026-08-19)

### obs:supervisiondecision — SupervisionDecision has 3 pre-existing surviving ConditionalsBoundaryMutator mutants (backoffMs L99/
`kind: environment?` `anchor: modules/app-services/src/main/java/io/justsearch/app/services/worker/SupervisionDecision.java` `seen: 1` `first: 2026-07-11` `last: 2026-07-11`
- [ ] SupervisionDecision has 3 pre-existing surviving ConditionalsBoundaryMutator mutants (backoffMs L99/L104, decide L80) — worker-supervision seam already reports 88% strength in report-pit-strength.mjs, unrelated to tempdoc 677 work — `modules/app-services/src/main/java/io/justsearch/app/services/worker/SupervisionDecision.java` (2026-07-11)

### obs:ui-a11y-baseline-v1 — chat/grounding-badge surface missing from governance/ui-a11y-baseline.v1.json — ui-a11y-gate structu
`kind: defect?` `anchor: ui-a11y-baseline.v1.json` `seen: 4` `first: 2026-07-12` `last: 2026-08-19`
- [ ] chat/grounding-badge surface missing from governance/ui-a11y-baseline.v1.json — ui-a11y-gate structurally can't exercise the grounding badge (no chat entry). Extend baseline surface list to cover chat. Surfaced by 720 per-sentence UX audit. (2026-07-12)
- [ ] governance/ui-a11y-baseline.v1.json declares $schema './ui-a11y-baseline.schema.json' but that file does not exist (the sibling ui-proportion-baseline.schema.json does) — the a11y register is schema-unvalidated — `governance/ui-a11y-baseline.v1.json:2` (2026-08-06)
- [ ] governance/ui-a11y-baseline.v1.json declares \ './ui-a11y-baseline.schema.json' but that file does not exist (ui-proportion-baseline.schema.json's description also cites it as the mirrored shape) — dangling schema reference, no validation of the a11y register — `governance/ui-a11y-baseline.v1.json:2` (2026-08-06)
- [ ] UX audit: governance/ui-a11y-baseline.v1.json has no entry for the search-v3 window, the DocumentPane reading surface, or the reasoning block, so ui-a11y-gate structurally cannot see axe violations there (two SERIOUS ones measured today) — `governance/ui-a11y-baseline.v1.json` (2026-08-19)

### obs:combinedenrichmentbackfillops — Comment over-attributes Lucene #15068 (an MMapDirectory mmap resource leak, not data loss) as a vect
`kind: defect?` `anchor: modules/worker-services/src/main/java/io/justsearch/indexerworker/loop/ops/CombinedEnrichmentBackfillOps.java` `seen: 7` `first: 2026-07-11` `last: 2026-08-14`
- [ ] Comment over-attributes Lucene #15068 (an MMapDirectory mmap resource leak, not data loss) as a vector-loss / commit-cadence rationale — `modules/worker-services/src/main/java/io/justsearch/indexerworker/loop/ops/CombinedEnrichmentBackfillOps.java:627-630` (found during 717 research; behavior fine, comment only) (2026-07-11)
- [ ] Combined pass parent lane stamps parent-status markers (EMBEDDING_STATUS/NER_STATUS COMPLETED) onto chunk docs picked up via the splade-status query — chunk docs end up carrying parent lifecycle fields they never own — `modules/worker-services/src/main/java/io/justsearch/indexerworker/loop/ops/CombinedEnrichmentBackfillOps.java:330` (2026-07-11)
- [ ] A chunk doc pending both chunk_embedding and splade sits in BOTH combined-pass caches (parentIdCache via splade-status query, chunkIdCache via chunk-embedding query) and can be popped twice into one batch — double-added to embedDocIds, double-embedded — `modules/worker-services/src/main/java/io/justsearch/indexerworker/loop/ops/CombinedEnrichmentBackfillOps.java:199-260` (2026-07-11)
- [x] ~~INTERMITTENT fresh-build chunk-death anomaly (chartered as tempdoc 717): a fresh --clean ingest SOMETIMES produces an index with the entire chunk_merge leg absent (vector nDCG 0.34 not 0.62), silently. Observed degenerate: 713 control run, 712 A/B-1 OFF arm. Observed healthy: 713 §M-5 probe, 712 A/B-2 both arms (first try). Not deterministic; refuted as "always dead," confirmed as occasional — `modules/worker-services/.../CombinedEnrichmentBackfillOps.java:372`~~ (2026-07-11)
- [x] RESOLVED (2026-08-19, per-occurrence verification): fixed by tempdoc 717 (`d37578a8`, 2026-07-12) — `parent_token_count` index-time estimate fallback (`IndexingDocumentOps.java:444-454`, estimate `length/3` so a 2000-char chunked doc clears the 512 short threshold) + `isShortCorpus` majority-coverage fail-open (`CorpusProfile.java:62-70`); follow-on hardening skips deleted-but-unmerged parents in the profile (`IndexCountOps.java:776-782`). Every recorded occurrence predates the fix; no recurrence in any later run (all post-713 legal-clerc register rows carry chunk_merge). Residual found during this verification and fixed in the same PR as this note: jseval's chunk-completeness corroborator waived ALL `SKIPPED_SHORT_CORPUS`/`SKIPPED_NO_CHUNK_DOCS` skips unconditionally (`run.py` 715-defect-1 waiver), which would have silently passed a 717-class recurrence — the waiver is now conditioned on the offline chunk expectation agreeing the corpus is short. Register F-035 quarantine note reconciled with the 717 resolution in the same PR. (2026-08-19)
- [ ] Stale perf comment: 'Per-doc at 2.0ms/call is near-optimal' for NER — measured 74ms/doc on legal-clerc-200 (22161ms/299 ops) — `modules/worker-services/src/main/java/io/justsearch/indexerworker/loop/ops/CombinedEnrichmentBackfillOps.java:577` (2026-07-27)
- [ ] SPLADE/NER backfill stage starvation live-witnessed (813 post-merge review): six consecutive combined cycles selected splade=100 docs but executed 0ms splade/ner work because the embed stage consumed the whole 5000ms budget first — CombinedEnrichmentBackfillOps.java:678-687 abort branch. Consequence: 813's terminal states (fully searchable / Up to date) unreachable while embedding work keeps arriving; UI reports it truthfully as enriching. Pre-existing (798 D2c budget design), not introduced by 813. Needs a worker-side budget-fairness slice (reserve per-stage slice or rotate first claim). Evidence: session 487f0d1b task b2died21y worker log 12:10:02-12:10:28. (2026-08-06)
- [ ] Eval-path enrichment: 63 chunk-doc terminal SPLADE FAILED escalations per miracl-fr-2k run when chunk-SPLADE is off — chunk docs seeded splade_status=PENDING get pulled into the parent branch by the splade-status query and escalated as blank-content; invisible to the readiness gate because splade counts exclude IS_CHUNK docs — `modules/worker-services/src/main/java/io/justsearch/indexerworker/loop/ops/CombinedEnrichmentBackfillOps.java:534` (2026-08-14)

### obs:indexingdocumentops — parent_token_count feedback/telemetry has no exact-vs-estimated provenance flag — after tempdoc 717
`kind: defect?` `anchor: modules/worker-services/src/main/java/io/justsearch/indexerworker/loop/ops/IndexingDocumentOps.java` `seen: 1` `first: 2026-07-11` `last: 2026-07-11`
- [ ] parent_token_count feedback/telemetry has no exact-vs-estimated provenance flag — after tempdoc 717 fix A it may hold a char-based estimate (SPLADE-cold-start fallback) indistinguishable from an exact SPLADE count for offline distribution analysis — `modules/worker-services/src/main/java/io/justsearch/indexerworker/loop/ops/IndexingDocumentOps.java:391-419` (717 review Finding 5; low blast radius, feedback pipeline only) (2026-07-11)

### obs:unanchored-general-8 — Reranker is load-bearing / first-stage fusion squanders a strong dense leg: on battlefield-en-scale-
`kind: defect?` `anchor: none` `seen: 1` `first: 2026-07-12` `last: 2026-07-12`
- [ ] Reranker is load-bearing / first-stage fusion squanders a strong dense leg: on battlefield-en-scale-v1 (2026-07-12, current main) client 'full' fusion (BM25+dense+SPLADE RRF, no CE) scored nDCG@10=0.279 — FAR below vector-alone 0.722 and server-hybrid-with-CE 0.697. The cross-encoder does all the recovery (0.279->0.697); first-stage fusion weighting buries the strong dense hit. Fragility worth an owner — relevant to 636 (fusion-order)/712/713. Artifacts: scripts/jseval/tmp/eval-results/*battlefield-en-scale-v1* (2026-07-12)

### obs:encoder-drift — encoder_drift._write_baseline has zero call sites (baseline capture moved to calibrate-drift-baselin
`kind: follow-up?` `anchor: scripts/jseval/jseval/projections/encoder_drift.py` `seen: 1` `first: 2026-07-11` `last: 2026-07-11`
- [ ] encoder_drift._write_baseline has zero call sites (baseline capture moved to calibrate-drift-baseline per C-1.8.1) — dead code candidate — `scripts/jseval/jseval/projections/encoder_drift.py:229` (2026-07-11)

### obs:chunkdocumentwriter — tempdoc 718: expose ChunkDocumentWriter.CHUNK_THRESHOLD_CHARS (modules/worker-services/src/main/java
`kind: defect?` `anchor: ChunkDocumentWriter.java` `seen: 2` `first: 2026-07-11` `last: 2026-08-06`
- [ ] tempdoc 718: expose ChunkDocumentWriter.CHUNK_THRESHOLD_CHARS (modules/worker-services/src/main/java/io/justsearch/indexerworker/rag/ChunkDocumentWriter.java:28) via /api/status or a config surface so jseval's offline chunk-completeness oracle (scripts/jseval/jseval/chunk_completeness.py CHUNK_THRESHOLD_CHARS) reads it instead of mirroring it -- a dual-source-of-truth that will silently drift if the Java constant ever changes. (2026-07-11)
- [ ] Chunk documents carry no COLLECTION field, so the default agent-history collection exclusion cannot be enforced on the chunk branch — agent-history chunks can enter the fused candidate union whenever no pathPrefix/doc_ids filter is set — `modules/worker-services/src/main/java/io/justsearch/indexerworker/rag/ChunkDocumentWriter.java:114` (2026-08-06)

### obs:pdfocrenginetest — PdfOcrEngineTest.interruptDestroysAllRegisteredChildren flaked on main-push CI AGAIN (run 2915593054
`kind: environment?` `anchor: PdfOcrEngineTest` `seen: 1` `first: 2026-07-12` `last: 2026-07-12`
- [ ] PdfOcrEngineTest.interruptDestroysAllRegisteredChildren flaked on main-push CI AGAIN (run 29155930543, 2026-07-11, PR #145 push, zero OCR overlap; second main-push occurrence after run 29129172631 on 2026-07-10) — recurrence condition fired; needs 706-style state-polling hardening, 706-owner lane — `modules/worker-services PdfOcrEngineTest` (recovered from dead-session worktree 691-takeover) (2026-07-12)

### obs:dataset-cache — 709 resume gap: an interrupted CLERC raw fetch leaves an orphaned `.tmp-*` staging dir under the cac
`kind: defect?` `anchor: scripts/jseval/jseval/dataset_cache.py` `seen: 2` `first: 2026-07-12` `last: 2026-07-13`
- [ ] 709 resume gap: an interrupted CLERC raw fetch leaves an orphaned `.tmp-*` staging dir under the cache root (observed: ~6.3GB collection.doc.tsv.gz at scripts/jseval/tmp/dataset-fetch-cache/clerc-raw/.<key>.tmp-*); `store()` neither resumes nor GCs it, so the next fetch re-downloads the full 6.7GB — `scripts/jseval/jseval/dataset_cache.py:150` (2026-07-12)
- [ ] 709/173 interaction: pinning _CLERC_REVISION changed the clerc-raw dataset-cache key, orphaning the completed 7.7GB resolve/main entry (7df857..) — next fetch re-downloads and now hits HF anonymous-download 403 (AccessDenied at CDN hop, resolver quota fine); migrated entry to pinned key 0f0aba86.. via hardlinks+signature.json this session (content signature a23d916b.. unchanged, HF API confirms main sha == pinned rev). Residual: no cache-key migration/GC story on revision bumps, and the orphaned 6.3GB .tmp-* staging dir from the 07-11 dead fetch still leaks — `scripts/jseval/jseval/dataset_cache.py:150` (2026-07-13)

### obs:registryentry — Pre-existing: CI 'Build (no model blobs)' emits a MissingOverride annotation for modules/app-agent-a
`kind: environment?` `anchor: RegistryEntry.java` `seen: 2` `first: 2026-07-13` `last: 2026-07-16`
- [ ] Pre-existing: CI 'Build (no model blobs)' emits a MissingOverride annotation for modules/app-agent-api/src/main/java/io/justsearch/agent/api/registry/RegistryEntry.java#41 (id implements method in Declaration; expected @Override) — non-fatal, surfaces on every main push run. Noticed during 719 publish; no Java in that diff. (2026-07-13)
- [ ] CI annotation on green main run 29492063792: [MissingOverride] RegistryEntry.id expected @Override — `modules/app-agent-api/src/main/java/io/justsearch/agent/api/registry/RegistryEntry.java:41` (pre-existing, not from PR #209) (2026-07-16)

### obs:commitment-v1 — 707/719 cross-platform replay gotcha: committed 707 commitment.v1.json digests hash CRLF build-time
`kind: lesson?` `anchor: commitment.v1.json` `seen: 1` `first: 2026-07-14` `last: 2026-07-14`
- [ ] 707/719 cross-platform replay gotcha: committed 707 commitment.v1.json digests hash CRLF build-time bytes, but git text=auto stores LF — a fresh LF checkout's fabricated-queries/meta/recipe files hash differently than their own manifests; rebuilding via corpus-query-stratum-build + corpus-inject-real reproduces all committed signatures exactly (proven 2026-07-14, en-legal-clerc 1k members). Outsider replay docs should say regenerate-then-verify, not hash-the-checkout. Discovered during 725 A/B corpus prep. (2026-07-14)

### obs:unifiedchatview — "New chat" button is state-gated (thread.length > 0 && !agentMode) and doesn't render on a fresh/emp
`kind: defect?` `anchor: modules/ui-web/src/shell-v0/views/UnifiedChatView.ts` `seen: 16` `first: 2026-07-14` `last: 2026-08-19`
- [ ] "New chat" button is state-gated (thread.length > 0 && !agentMode) and doesn't render on a fresh/empty chat surface, with no other entry point (keyboard-shortcuts doc lists none) — found via tempdoc 727 friction mining — `modules/ui-web/src/shell-v0/views/UnifiedChatView.ts:2114-2117` (2026-07-14)
- [ ] Fourth affordance-to-shape resolver: UnifiedChatView.currentShapeId() maps affordance->shape by hand for answer-frame purposes, parallel to compose.resolveShape/resolveDispatchShape — a fork that can drift — `modules/ui-web/src/shell-v0/views/UnifiedChatView.ts:4448` (2026-07-31)
- [ ] New chat in the Delegate rung clears the chat thread but not the shared AgentSessionController run state (resetRunState is private), so a finished agent run survives a 'New chat' — pre-existing, surfaced by round-14 finding 14's header fix — `modules/ui-web/src/shell-v0/views/UnifiedChatView.ts:2033` (2026-08-06)
- [ ] Export in the Delegate rung exports only `this.thread`; a pure agent run (content in agentCtrl.conversation, empty chat thread) still has no Export route — round-14 finding 14's residual — `modules/ui-web/src/shell-v0/views/UnifiedChatView.ts:1938` (2026-08-06)
- [ ] handleCardFork ("Search again") clears explicitAffordance but not schemaAttached, so forking back to a live search while a schema is attached derives 'extract' instead of the retrieve floor — `modules/ui-web/src/shell-v0/views/UnifiedChatView.ts:3735` (2026-08-05)
- [ ] Agent affordance send path clears the draft and routes to the /api/chat/agent* controller endpoints with NO 423 locked gate — a locked store loses the user's text there (the noteRefusedWhileLocked recovery is wired only into the /api/chat/dispatch stream path) — `modules/ui-web/src/shell-v0/views/UnifiedChatView.ts:5673-5686` (2026-08-06)
- [ ] check-controls-a11y is RED on main: 1 title-on-disabled instance over baseline 0 in the shipped chat window — `modules/ui-web/src/shell-v0/views/UnifiedChatView.ts:2096` (2026-08-14)
- [ ] check-controls-a11y RED on main, not in expected-state.v1.json: title-on-disabled button (1 > baseline 0) — `modules/ui-web/src/shell-v0/views/UnifiedChatView.ts:2096` (2026-08-14)
- [ ] check-controls-a11y is RED on origin/main (title-on-disabled, baseline 0) but is not listed in governance/expected-state.v1.json, so every ui-web branch re-diagnoses it — `modules/ui-web/src/shell-v0/views/UnifiedChatView.ts:2096` (2026-08-14)
- [ ] check-controls-a11y is RED on origin/main (1 title-on-disabled instance, baseline 0) but is not in expected-state.v1.json — either fix or record it — `modules/ui-web/src/shell-v0/views/UnifiedChatView.ts:2096` (2026-08-17)
- [ ] check-controls-a11y is RED on main (title-on-disabled in UnifiedChatView, 1 > baseline 0) but is not in expected-state.v1.json's known-red list — either fix or register it — `modules/ui-web/src/shell-v0/views/UnifiedChatView.ts:2143` (2026-08-18)
- [ ] check-controls-a11y is RED on main (1 title-on-disabled instance over baseline 0) — `modules/ui-web/src/shell-v0/views/UnifiedChatView.ts:2143`; not in expected-state.v1.json, so every ui-web PR re-discovers it (2026-08-19)
- [ ] 847 §1.5b has a fifth arm not in the design: the LEXICAL rag.citation_delta handler writes word-overlap scores straight into this.citations, which sourceGrounding turns into a panel tier — `modules/ui-web/src/shell-v0/views/UnifiedChatView.ts:5995` (2026-08-19)
- [ ] UnifiedChatView reopens on the SEARCH tier after a page reload, so a restored conversation's transcript is not rendered until the reader switches back to a conversation tier (affordance is not restored with the conversation) — `modules/ui-web/src/shell-v0/views/UnifiedChatView.ts:3083` (2026-08-19)
- [ ] check-controls-a11y still RED on main and still not in expected-state.v1.json — re-verified during 852 S2 review; the gate reports it at `modules/ui-web/src/shell-v0/views/UnifiedChatView.ts:2137` now (the shard's older note says :2143 — the line number drifts, the finding does not) (2026-08-19)
- [ ] check-controls-a11y is RED on main (not in expected-state.v1.json): 1 title-on-disabled instance over baseline 0 — `modules/ui-web/src/shell-v0/views/UnifiedChatView.ts:2137` (2026-08-19)

### obs:unanchored-flake — Browser-automation viewport-resize flakiness (innerWidth/outerWidth mismatch, resize not taking effe
`kind: lesson?` `anchor: none` `seen: 1` `first: 2026-07-14` `last: 2026-07-14`
- [ ] Browser-automation viewport-resize flakiness (innerWidth/outerWidth mismatch, resize not taking effect) seen in 2 same-day claude-in-chrome UI-capture sessions — unconfirmed whether claude-in-chrome-side (multi-tab contention, matches agent-lessons.md too-many-tabs lesson) or a JustSearch-side bug; needs a targeted repro — found via tempdoc 727 friction mining (2026-07-14)

### obs:unanchored-general-23 — Windows/git-bash 'system cannot find the path specified' recurring across many test names inside a C
`kind: defect?` `anchor: none` `seen: 1` `first: 2026-07-14` `last: 2026-07-14`
- [ ] Windows/git-bash 'system cannot find the path specified' recurring across many test names inside a CI coverage-check loop — producing script not identified by static search (checked check-*coverage*.mjs / check-*tier*.mjs, none use spawnSync/execFileSync per-test-name); needs live repro — found via tempdoc 727 friction mining (2026-07-14)

### obs:devmode — the ts-any governance gate (gates/ts-any/baseline.txt, tempdoc 530 sec2.5) is red on origin/main's a
`kind: environment?` `anchor: devMode.ts` `seen: 1` `first: 2026-07-14` `last: 2026-07-14`
- [ ] the ts-any governance gate (gates/ts-any/baseline.txt, tempdoc 530 sec2.5) is red on origin/main's actual HEAD (401c1ae) independent of any merge from today's session: modules/ui-web/src/api/devMode.ts and modules/ui-web/src/shell-v0/streaming/MultiplexedStream.ts each have an unregistered '(import.meta as any).env?.DEV' cast, present since commit daa74bd (tempdoc 683 PR #77) and a9694aa (tempdoc 662 PR #22) respectively -- neither was ever added to the baseline file, which has been untouched since the initial-release seed commit (29579e5). Confirmed this gate is NOT wired into the public .github/workflows/ci.yml (no match for 'governance'/'ts-any'), so main's actual public CI is unaffected and shows success (gh run list --branch main) -- this is a local/manual-governance-only finding, not a CI-red condition. Found via tempdoc 727's publish verification pass; not fixed here (unrelated pre-existing debt, out of this tempdoc's scope). (2026-07-14)

### obs:unanchored-general-28 — Embedding fingerprint apparently not persisted across worker restarts: an index healthy in-session (
`kind: defect?` `anchor: none` `seen: 1` `first: 2026-07-14` `last: 2026-07-14`
- [ ] Embedding fingerprint apparently not persisted across worker restarts: an index healthy in-session (compat REBUILDING->complete, CHUNK_HYBRID serving) re-flags BLOCKED_LEGACY/LEGACY_INDEX_NO_FINGERPRINT on plain dev-stack restart, blocking the dense leg until full re-embed; reproduced twice during 725 live validation (worktree .dev-data and main .dev-data) — `modules/indexer-worker` compat/fingerprint persistence (2026-07-14)

### obs:retrievecontextparams — Worktree .claude/worktrees/725-response-legibility had concurrent agent-session activity during this
`kind: environment?` `anchor: RetrieveContextParams.java` `seen: 1` `first: 2026-07-14` `last: 2026-07-14`
- [ ] Worktree .claude/worktrees/725-response-legibility had concurrent agent-session activity during this task (~24 unrelated files modified: RetrieveContextParams.java, RemoteDocumentService.java, SqliteJobQueue.java, mcp/*.java, dev-runner.cjs, jseval/**, new tempdocs 736/731/732/733) — violates branch-safety.md 'never share a worktree'. Caused transient Gradle build-output corruption (ipc-common missing proto classes, app-services:integrationTest Gradle-internal SerializableTestResultStore EOFException/NoSuchFileException while writing its own HTML report) unrelated to tempdoc 730 code — resolved by clearing stale build/classes and build/test-results dirs and retrying — `.claude/worktrees/725-response-legibility` (shared worktree, 2026-07-14) (2026-07-14)

### obs:mcptoolsurface — resources/read path still uses raw multi-entry Map.of (salted wire key order) at McpToolSurface.java
`kind: follow-up?` `anchor: McpToolSurface.java` `seen: 2` `first: 2026-07-14` `last: 2026-07-14`
- [ ] resources/read path still uses raw multi-entry Map.of (salted wire key order) at McpToolSurface.java:1220,1233,1250,1269,1442 + the {type,text} content blocks — same JVM-salt class as the fixed resources/list; follow-up candidate — `McpToolSurface.java:1220` (2026-07-14)
- [ ] Pre-existing latent: corpus newlines in preview/answer text can mimic column-0 response furniture (Found N results / Hints lines); mitigation sketch: indent continuation lines; LOW-MODERATE, unchanged by 732 — `McpToolSurface.java:738-785` (2026-07-14)

### obs:knowledgeserver — tempdoc 730 A4: KnowledgeServer.maybeAutoStartEmbeddingRebuildAllPendingBestEffort's chunk-exclusion
`kind: environment?` `anchor: modules/indexer-worker/src/main/java/io/justsearch/indexerworker/server/KnowledgeServer.java` `seen: 2` `first: 2026-07-14` `last: 2026-08-10`
- [ ] tempdoc 730 A4: KnowledgeServer.maybeAutoStartEmbeddingRebuildAllPendingBestEffort's chunk-exclusion doc-count math (totalDocs - chunkDocs) is unit-untested at the KnowledgeServer level (only exercised indirectly via ECC-level tests that don't use chunks) — pre-existing gap, not touched by this increment — `modules/indexer-worker/src/main/java/io/justsearch/indexerworker/server/KnowledgeServer.java:1295-1300` (2026-07-14)
- [ ] A fresh profile can never reach the "empty index -> COMPATIBLE" fast path: the built-in help docs (`ssot/docs/help/*.md`) are ingested AND committed before `EmbeddingCompatibilityController.refresh()` runs, so refresh sees docCount>0 with no embedding fingerprint and takes BLOCKED_LEGACY -> auto-rescue -> full REBUILD. Reproduced on a freshly-emptied `.dev-data` with no kill involved: help commit 21:55:38, `BLOCKED_LEGACY (docCount=7)` 21:55:44 (`worker.log:402-405`). The commit stamps the schema fingerprint (unconditional supplier) but withholds the embedding one (state-gated `ecc::fingerprintToStamp`, `KnowledgeServer.java:1031`), which is the asymmetry `EmbeddingCompatibilityController.java:186-199` describes. Harmless on a small profile (heals in seconds once the rebuild drains), but on a profile with a real corpus the rebuild must run to `pending==0` or the index stays unstamped and EVERY later boot restarts the full GPU re-embed — the observed 574-doc index stuck since generation g-20260731-162634 (2026-08-10)

### obs:unanchored-general-44 — Entity-filter cluster expansion turns a single-doc planted code (entity_persons=Cavby8) into 41 hits
`kind: defect?` `anchor: none` `seen: 1` `first: 2026-07-14` `last: 2026-07-14`
- [ ] Entity-filter cluster expansion turns a single-doc planted code (entity_persons=Cavby8) into 41 hits — disambiguation-cluster over-expansion on short synthetic codes; precision concern for entity-filtered retrieval — `entity facet filter expansion` (2026-07-14)

### obs:dev-runner-drift-2 — Backend-death root cause substantially resolved: deaths coincide with other sessions' lease takeover
`kind: follow-up?` `anchor: dev-runner.cjs` `seen: 1` `first: 2026-07-14` `last: 2026-07-14`
- [ ] Backend-death root cause substantially resolved: deaths coincide with other sessions' lease takeovers (30s lease not renewed during long-running probes/campaigns that make no MCP dev-tool calls; observed live — session f3e41644 reclaimed mid-probe, stale_reclaim written by ITS runner). Issue 6 reframes from product crash to contention semantics; candidate fix: lease renewal heartbeat during utility-run/long ops — `scripts/dev/dev-runner.cjs lease model` (2026-07-14)

### obs:check-tempdoc-numbers — check-tempdoc-numbers has a blind spot: it only reports a number claimed by 2+ IN-FLIGHT worktrees,
`kind: defect?` `anchor: scripts/ci/check-tempdoc-numbers.mjs` `seen: 6` `first: 2026-07-15` `last: 2026-08-18`
- [ ] check-tempdoc-numbers has a blind spot: it only reports a number claimed by 2+ IN-FLIGHT worktrees, excluding any basename already on origin (`newBasenames` filter). So an in-flight tempdoc colliding with one already merged to main passes green — reproduced live: branch worktree-ui-audit-density-review's 696-simple-detailed-disclosure vs main's 696-dev-tooling-jdk-resolution went undetected while the check flagged an unrelated 720 collision in the same run — `scripts/ci/check-tempdoc-numbers.mjs:117` (2026-07-15)
- [ ] check-tempdoc-numbers reports two pre-existing cross-worktree number collisions (#720: memory-injector-plan vs p1a-context-prepend-plan; #729: gjf-removal vs the stale pre-rename copy of 734 in worktree sandbox-validation) — the affected sessions may not know until their own merge-time run — `scripts/ci/check-tempdoc-numbers.mjs` (2026-07-17)
- [ ] check-tempdoc-numbers treats a gate changeset as claiming a tempdoc number, so a changeset named after the tempdoc that justifies it collides with that tempdoc. Hit twice today in different worktrees (#801 in 798-remaining, #803 here); the natural naming convention — name the changeset after its tempdoc — is exactly what trips it. Workaround is a non-numeric changeset filename — `scripts/ci/check-tempdoc-numbers.mjs` (tempdoc 803) (2026-07-31)
- [ ] check-tempdoc-numbers reports a cross-worktree NUMBER COLLISION on #822: 822-search-v3-window.md [worktree:822-curated] vs 822-donor + 822-t3code-search-window.md [worktree:822-t3code-window] — two sibling sessions claimed 822 concurrently (2026-08-14, seen during charters PR #443 prep; not caused by 825-828). One of those sessions must renumber before merge — `scripts/ci/check-tempdoc-numbers.mjs` (2026-08-13)
- [ ] check-tempdoc-numbers misses tempdoc-vs-tempdoc collisions across worktrees: #843 is claimed by BOTH 843-deepseek-harness-relevance-and-mcp-interop.md (worktree dsh-mcp-probe) and 843-streaming-producer-wedge.md, and the checker stays silent — it only reported the #840/#842 tempdoc-vs-changeset pairs. The cross-worktree guard has a blind spot in exactly the case parallel agents hit most — `scripts/ci/check-tempdoc-numbers.mjs` (2026-08-18)
- [ ] Pre-existing tempdoc number collision on #840: 840-model-download-restructure.md vs 840-retire-model-registry-mirror.md (sibling worktree gates/ssot-catalog-sync) — check-tempdoc-numbers fails standalone — `scripts/ci/check-tempdoc-numbers.mjs` (2026-08-18)

### obs:expected-state-v1 — Possible stale expected-state entry: [ui-web-typecheck-ts5101] declares ui-web `npm run typecheck` R
`kind: environment?` `anchor: expected-state.v1.json` `seen: 3` `first: 2026-07-15` `last: 2026-08-17`
- [ ] Possible stale expected-state entry: [ui-web-typecheck-ts5101] declares ui-web `npm run typecheck` RED repo-wide (TS5101 baseUrl vs pinned typescript 6.x) 'pre-existing on main since ~2026-07-01', but it exits 0 green in a worktree merged up to origin/main (2026-07-15, verified twice). A known-failure entry that no longer reproduces is worse than none — it invites dismissing a REAL typecheck failure as the known one. Verify against main and retire if fixed — `scripts/**/expected-state.v1.json` (2026-07-15)
- [ ] expected-state.v1.json entry `governance-gates-standing-red` is stale on three counts: it matches a `verifyGovernanceGates` Gradle task that does not exist in this repo, and warns about class-size/ui-bundle gates that were deliberately retired for go-public (tempdoc 634, per discipline-gate-kernel.md frontmatter). Its cited observation conditions are also absent from this repo's observations.md. reviewBy 2026-08-03 — `scripts/agent-analytics/expected-state.v1.json:40-45` (2026-07-30)
- [ ] check-controls-a11y (UnifiedChatView.ts:2096) and strip-token-fallbacks (ActionLedgerView.ts, RecentsMenu.ts) are RED on main but not listed in expected-state.v1.json, unlike their two neighbours — re-observed during 837 S3/S4 — `governance/expected-state.v1.json` (2026-08-17)

### obs:ui-selectors — ui_selectors.py SEARCH_INPUT/TID_SEARCH_INPUT/CSS_SEARCH_INPUT_TEXTAREA are stale post-tempdoc-687:
`kind: defect?` `anchor: scripts/jseval/jseval/ui_selectors.py` `seen: 1` `first: 2026-07-15` `last: 2026-07-15`
- [ ] ui_selectors.py SEARCH_INPUT/TID_SEARCH_INPUT/CSS_SEARCH_INPUT_TEXTAREA are stale post-tempdoc-687: the live composer textarea has no role=searchbox, no aria-label, no data-testid=search-input, so _type_and_search (and every ui-shot step chained off search-results, e.g. chat-mode, qa-response, filters-chips) fails under --fixtures in this worktree. Live-verified fix: locate via 'jf-composer textarea' instead. — `scripts/jseval/jseval/ui_selectors.py:14-15,45-46,90` (2026-07-15)

### obs:token-names-generated — origin/main is RED on `gen-token-names --check`: `tokens.css` gained `--glass-blur-scale` + `--text-
`kind: defect?` `anchor: modules/ui-web/src/shell-v0/themes/token-names.generated.ts` `seen: 12` `first: 2026-07-15` `last: 2026-08-13`
- [ ] origin/main is RED on `gen-token-names --check`: `tokens.css` gained `--glass-blur-scale` + `--text-info` without a matching regen, so `token-names.generated.ts` is stale ON MAIN (verified 2026-07-15: token sources byte-identical between origin/main and a branch that never touched them, yet a clean regen adds 4 lines). Blocks the ui-web gate set for ANY branch touching ui-web, not just the one that finds it. Fix = `node scripts/ci/gen-token-names.mjs` — `modules/ui-web/src/shell-v0/themes/token-names.generated.ts` (2026-07-15)
- [ ] gen-token-names --check is RED on main (token-names.generated.ts stale, 223 tokens) — inputs are tokens.css + public/themes only, untouched by 818 — `modules/ui-web/src/shell-v0/themes/token-names.generated.ts` (2026-08-07)
- [ ] gen-token-names + gen-component-vocabulary --check are stale on main (missing jf-app-update-banner, --measure-notice/prose/text-entry) — regen not run when those landed; `modules/ui-web/src/shell-v0/themes/token-names.generated.ts` (2026-08-07)
- [ ] check-ci gen-token-names --check is stale on this base (223 tokens) with tokens.css/palette untouched — regen was skipped by an earlier tokens.css edit — `modules/ui-web/src/shell-v0/themes/token-names.generated.ts` (2026-08-12)
- [ ] gen-token-names --check is stale on main: token-names.generated.ts missing --measure-notice/--measure-prose/--measure-text-entry (tokens.css edited without regen) — `modules/ui-web/src/shell-v0/themes/token-names.generated.ts` (2026-08-12)
- [ ] gen-token-names --check is RED on this branch's base: token-names.generated.ts missing --measure-notice/--measure-prose/--measure-text-entry (regenerating adds 3 tokens; unrelated to search-v3) — `modules/ui-web/src/shell-v0/themes/token-names.generated.ts` (2026-08-12)
- [ ] gen-token-names --check is stale on main: three --measure-* tokens (added to styles/tokens.css) are missing from the generated token-names list, so the ui-web gate set is red independent of any current change — `modules/ui-web/src/shell-v0/themes/token-names.generated.ts` (2026-08-12)
- [ ] gen-token-names --check is stale on main: tokens.css defines --measure-notice/--measure-prose/--measure-text-entry but token-names.generated.ts lacks them — `modules/ui-web/src/shell-v0/themes/token-names.generated.ts` (2026-08-13)
- [ ] token-names.generated.ts is stale on the 822 branch base: gen-token-names --check fails wanting 3 --measure-* tokens (--measure-notice/-prose/-text-entry) that were added without regenerating — `modules/ui-web/src/shell-v0/themes/token-names.generated.ts` (2026-08-13)
- [ ] gen-token-names --check is RED on a clean-of-tokens tree (token-names.generated.ts stale at 223 tokens) — not in expected-state.v1.json; pre-existing, unrelated to any token edit — `modules/ui-web/src/shell-v0/themes/token-names.generated.ts` (2026-08-13)
- [ ] gen-token-names --check is RED pre-existing on 822 branch: token-names.generated.ts missing --measure-notice/--measure-prose/--measure-text-entry (unrelated to S2; reverted the regen to keep the slice diff scoped) — `modules/ui-web/src/shell-v0/themes/token-names.generated.ts` (2026-08-13)
- [ ] check-gen-token-names --check is RED on main: token-names.generated.ts is missing --measure-notice/--measure-prose/--measure-text-entry, added by #403 (tempdoc 818) without regenerating — `modules/ui-web/src/shell-v0/themes/token-names.generated.ts` (2026-08-13)

### obs:consult-register-v1 — The `ui-web-gates` recipe documents a kernel-gate command that does not work: `node scripts/governan
`kind: defect?` `anchor: governance/consult-register.v1.json` `seen: 2` `first: 2026-07-15` `last: 2026-08-17`
- [ ] The `ui-web-gates` recipe documents a kernel-gate command that does not work: `node scripts/governance/run.mjs --gate ambient-purity,style-literal-ratchet,... --mode gate` (comma-separated) exits 2 with "gate id 'ambient-purity,style-literal-ratchet' not found" — run.mjs takes ONE gate id per invocation (verified 2026-07-15: comma form exit 2, single form exit 0). Risk is a silent skip of all 6 kernel gates, or exit 2 misread as a gate FAILURE. This recipe is the authority CLAUDE.md's pre-merge table defers to for every `modules/ui-web/src/**` edit — `governance/consult-register.v1.json:28` (2026-07-15)
- [ ] consult-register ui-web-gates recipe prescribes `run.mjs --gate a,b,c,... --mode gate` (comma list) but run.mjs's --gate is singular (`--gate <id>  run only the named gate`); the comma form exits non-zero with 'gate id not found', so an agent following the recipe verbatim sees a FAIL that is a syntax error, not a gate result — and could mistake it for a real failure or vice versa. Recipe should list the six gates as separate invocations (all six pass individually) — `governance/consult-register.v1.json` ui-web-gates recipe[1] vs `scripts/governance/run.mjs` --gate (2026-08-17)

### obs:unanchored-flake-3 — Flaky test on public main: `:modules:app-inference:test` failed the 'Unit tests (platform-contracts)
`kind: environment?` `anchor: none` `seen: 1` `first: 2026-07-15` `last: 2026-07-15`
- [ ] Flaky test on public main: `:modules:app-inference:test` failed the 'Unit tests (platform-contracts)' CI lane on main at 646465a7 — a DOCS-ONLY commit (observations.md + a shard deletion) that cannot causally touch Java. Conclusive: 58a82275 contains every change from 646465a7 plus one line and PASSED the same lane, and add9d620 (the real code change) passed it too — so the tree is not the cause. Effect is a transiently RED public main from an unrelated docs merge, which erodes the 'is main green?' signal the publish workflow depends on. Worth identifying + quarantining the specific flaky test (report is runner-local HTML; not in the fetchable log) — `modules/app-inference/src/test/java/io/justsearch/app/inference/` (2026-07-15)

### obs:unanchored-general-48 — Reranker is load-bearing / first-stage fusion squanders a strong dense leg: on battlefield-en-scale-
`kind: defect?` `anchor: none` `seen: 1` `first: 2026-07-12` `last: 2026-07-12`
- [ ] Reranker is load-bearing / first-stage fusion squanders a strong dense leg: on battlefield-en-scale-v1 (2026-07-12, current main) client 'full' fusion (BM25+dense+SPLADE RRF, no CE) scored nDCG@10=0.279 — FAR below vector-alone 0.722 and server-hybrid-with-CE 0.697. The cross-encoder does all the recovery (0.279->0.697); first-stage fusion weighting buries the strong dense hit. Fragility worth an owner — relevant to 636 (fusion-order)/712/713. Artifacts: scripts/jseval/tmp/eval-results/*battlefield-en-scale-v1* (2026-07-12)

### obs:unanchored-general-54 — 624 agent-utility A/B pilot on fixed retrieval (2026-07-12, battlefield-en-scale-v1, haiku, n=12 pai
`kind: defect?` `anchor: none` `seen: 1` `first: 2026-07-12` `last: 2026-07-12`
- [ ] 624 agent-utility A/B pilot on fixed retrieval (2026-07-12, battlefield-en-scale-v1, haiku, n=12 paired): B(with JustSearch) uses -25.7% mean / -28.9% median context tokens vs A(grep), 95% CI [-13%,-41%] excludes 0 (robust); B acc 100% vs A 87.5% but McNemar p=1.0 (both near ceiling -> no headroom, the recurring problem 707's pillar-1 corpus targets); adoption 7.1% (grep suffices on this easy subset -> rational non-adoption). Token-efficiency robust; accuracy underpowered until grep genuinely struggles. Artifacts: scripts/jseval/624-pilot-2026-07-12/ (2026-07-12)

### obs:unanchored-general-56 — Skill-vs-CLAUDE.md contradiction (same class as tempdoc 739 F-3): `.claude/skills/publish/SKILL.md`
`kind: environment?` `anchor: none` `seen: 1` `first: 2026-07-15` `last: 2026-07-15`

### obs:bash-guard — `bash-guard.mjs` has the same input.cwd gap that tempdoc 739 fixed in `docs-granularity-hint.mjs`: i
`kind: defect?` `anchor: scripts/agent-analytics/hooks/bash-guard.mjs` `seen: 1` `first: 2026-07-15` `last: 2026-07-15`
- [ ] `bash-guard.mjs` has the same input.cwd gap that tempdoc 739 fixed in `docs-granularity-hint.mjs`: it decides 'am I in the main worktree?' from `input.cwd` and does not account for a leading `cd <worktree> &&` in the command (nor `git -C <path>`). Hit live: a `git checkout -b` intended for a worktree was blocked because the session cwd happened to be the main checkout. Worse than the hint case because bash-guard BLOCKS — a false positive stops work rather than just nagging. Fix is the same shape as 739's `gitPushCwd`. `scripts/agent-analytics/hooks/bash-guard.mjs` (2026-07-15)

### obs:agenttoolsoperationcatalog — core.ingest-files (AgentToolsOperationCatalog.java:220-236) declares zero RequiredCapability and zer
`kind: defect?` `anchor: modules/app-services/src/main/java/io/justsearch/app/services/registry/operations/AgentToolsOperationCatalog.java` `seen: 1` `first: 2026-07-15` `last: 2026-07-15`
- [ ] core.ingest-files (AgentToolsOperationCatalog.java:220-236) declares zero RequiredCapability and zero OperationAvailability despite IngestOperationHandler/IngestTool writing into the worker-backed index — unlike core.search-index it has no fallback availability gate either, so a worker-down dispatch reaches the handler raw instead of a clean CAPABILITY_UNAVAILABLE denial — `modules/app-services/src/main/java/io/justsearch/app/services/registry/operations/AgentToolsOperationCatalog.java:212-236` (2026-07-15)

### obs:status — Post-737-Phase4: /api/inference/mode REST (BrainRuntimeServiceImpl.switchInferenceMode) still switch
`kind: follow-up?` `anchor: modules/ui-web/src/api/domains/status.ts` `seen: 1` `first: 2026-07-15` `last: 2026-07-15`
- [ ] Post-737-Phase4: /api/inference/mode REST (BrainRuntimeServiceImpl.switchInferenceMode) still switches mode DIRECTLY (foreign change the reconciler may revert to spec); FE buttons + api/domains/status.ts switchInferenceMode may now be dead — candidate for retirement with the switch-inference-mode alias (§12d). — `modules/ui-web/src/api/domains/status.ts:35` (2026-07-15)

### obs:aiinstallservice — ECC never re-syncs on mid-session embedding-model arrival: production relies on AiInstallService.try
`kind: defect?` `anchor: AiInstallService.java` `seen: 5` `first: 2026-07-14` `last: 2026-08-18`
- [ ] ECC never re-syncs on mid-session embedding-model arrival: production relies on AiInstallService.tryRestartWorkerBestEffort (AiInstallService.java:958-972) which is best-effort — on its silent failure the old worker keeps a stale UNAVAILABLE ECC forever; no arrival-side provider listener exists (EmbeddingProviderLifecycle.setEmbeddingProvider never notifies, only unload does). Needs its own design pass (notification seam + re-entrancy-safe refresh + recovery re-run). Refresh-guard hardening landed with the 734 fixes. (2026-07-14)
- [ ] cleanupBitsTmpFiles deletes every *.tmp in the model target dir, not just BITS' BIT*.tmp scratch — an unrelated temp file in modelsDir would be collateral — `modules/app-services/src/main/java/io/justsearch/app/services/ai/install/AiInstallService.java:1329` (2026-07-30)
- [ ] AiInstallService's five io.justsearch.app.api.AiInstallService implementations lack @Override (errorprone MissingOverride warnings at build time) — `modules/app-services/src/main/java/io/justsearch/app/services/ai/install/AiInstallService.java:194,212,359,382,393` (2026-07-30)
- [ ] GET /api/ai/install/status returns packages: [] on a machine that is idle and NOT fully installed (applyInstalledFromPlan only populates the list when installedFully flips true) — so a categorised component list cannot be rendered from status alone before/outside a run; the manifest endpoint is the only full list — `modules/app-services/src/main/java/io/justsearch/app/services/ai/install/AiInstallService.java:applyInstalledFromPlan` (2026-08-18)
- [ ] startInstall leaves running=true if operationLeases.register throws — the try/catch that resets it only wraps Thread.ofVirtual().start(), so an OperationAdmissionClosedException from the pre-plan lease wedges the service at INSTALL_ALREADY_RUNNING — `modules/app-services/src/main/java/io/justsearch/app/services/ai/install/AiInstallService.java:697` (2026-08-18)

### obs:indexstatusops — worker.core.pendingJobs (StatusResponse) is actually the combined PENDING+PROCESSING queueDepth (Ind
`kind: defect?` `anchor: IndexStatusOps.java` `seen: 2` `first: 2026-07-14` `last: 2026-08-06`
- [ ] worker.core.pendingJobs (StatusResponse) is actually the combined PENDING+PROCESSING queueDepth (IndexStatusOps.java:241 -> WorkerStatusMapper.java:75) — misleading name; renaming is a wire-contract change, flagging instead of fixing. Sibling migration.pendingJobsCount is PENDING-only. (2026-07-14)
- [ ] enrichment chunk pendingCount is status-derived while coverage is presence-truthful (717 F-032 'status lies'), so a completion gate reading pending under-fires when chunk_embedding_status says COMPLETED but the vector is absent — `modules/worker-services/src/main/java/io/justsearch/indexerworker/services/IndexStatusOps.java:632` (2026-08-06)

### obs:crossencoderreranker — search/rerank span's reranker.output_documents.*.document.content attr deliberately captures multi-p
`kind: defect?` `anchor: modules/reranker/src/main/java/io/justsearch/reranker/CrossEncoderReranker.java` `seen: 1` `first: 2026-07-14` `last: 2026-07-14`
- [ ] search/rerank span's reranker.output_documents.*.document.content attr deliberately captures multi-paragraph document text (owner-amended span-privacy contract, tempdoc 553 Phase D) — bounded to OpenInferenceSpans.MAX_CONTENT_CHARS=1024 chars/doc already; not an accidental full-content capture — `modules/reranker/src/main/java/io/justsearch/reranker/CrossEncoderReranker.java:174-176`, `modules/telemetry/src/main/java/io/justsearch/telemetry/OpenInferenceSpans.java:45,90-93` (2026-07-14)

### obs:unanchored-general-57 — SSE /api/advisory/authorization-pending/stream returns 200-empty text/plain without Accept: text/eve
`kind: follow-up?` `anchor: none` `seen: 1` `first: 2026-07-14` `last: 2026-07-14`
- [ ] SSE /api/advisory/authorization-pending/stream returns 200-empty text/plain without Accept: text/event-stream -- indistinguishable from no-pendings; smoke round lost probes to it. Candidate fix: 406 on wrong Accept, or documented list endpoint. Needs host live-stack regression test. (2026-07-14)

### obs:unanchored-general-58 — Smoke round: unified-chat showed 'Searching 7 files' while /api/knowledge/status said docCount 8 rig
`kind: follow-up?` `anchor: none` `seen: 1` `first: 2026-07-14` `last: 2026-07-14`
- [ ] Smoke round: unified-chat showed 'Searching 7 files' while /api/knowledge/status said docCount 8 right after ingest -- UI/API count lag, ui-api-truthfulness-under-load class; old 0.2.0 build, recheck on candidate rebuild. (2026-07-14)

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

### obs:chatcontroller — ChatController.handleCompact (loadHistory-based prefix build, :302) no longer 423s immediately on a
`kind: defect?` `anchor: modules/ui/src/main/java/io/justsearch/ui/api/ChatController.java` `seen: 1` `first: 2026-07-14` `last: 2026-07-14`
- [ ] ChatController.handleCompact (loadHistory-based prefix build, :302) no longer 423s immediately on a locked session now that loadHistory degrades instead of throwing (tempdoc 727 conversation-lock fix) — it now attempts an LLM summarize() call over placeholder/opaque content before failing at the final compactContext() write (cipher.seal still throws KeyLockedException there). Not a correctness/security regression (the write is still blocked), just a wasted LLM call in an edge case (locked + reachable + compact attempted) that's out of scope for this fix — `modules/ui/src/main/java/io/justsearch/ui/api/ChatController.java:302` (2026-07-14)

### obs:runtimeactivationservicetest — Stale tempdoc reference in test comment: RuntimeActivationServiceTest labels its WARN-suppression ca
`kind: defect?` `anchor: modules/app-services/src/test/java/io/justsearch/app/services/ai/runtime/RuntimeActivationServiceTest.java` `seen: 1` `first: 2026-07-15` `last: 2026-07-15`
- [ ] Stale tempdoc reference in test comment: RuntimeActivationServiceTest labels its WARN-suppression cases `// --------------- Tempdoc 727 F-3 ---------------` but 727's F-3 is the verify-worktree-base hook (unrelated); these cases belong to tempdoc 734's F-3 (false 'leftover from a previous build' WARN). Likely fallout from 734 being renumbered from 729. — `modules/app-services/src/test/java/io/justsearch/app/services/ai/runtime/RuntimeActivationServiceTest.java:232` (2026-07-15)

### obs:enforcer — hook-integrity 'bite' is two-tier and its docstring overclaims: kind:'command-signal' genuinely spaw
`kind: defect?` `anchor: scripts/governance/gates/hook-integrity/enforcer.mjs` `seen: 3` `first: 2026-07-15` `last: 2026-08-17`
- [ ] hook-integrity 'bite' is two-tier and its docstring overclaims: kind:'command-signal' genuinely spawns the hook with crafted violating stdin and asserts the exit code (enforcer.mjs:65-103), but kind:'unit' only does existsSync(testPath) (enforcer.mjs:195-197) — it never runs the test, so it proves a FILE EXISTS, not that the hook bites. The enforcer's own docstring (enforcer.mjs:11-12) claims bite 'proves the hook EMITS its block signal', true only for the command-signal half. Either narrow the docstring or promote unit-kind bites to executed proofs. — `scripts/governance/gates/hook-integrity/enforcer.mjs:11` (2026-07-15)
- [ ] hook-integrity's bite guarantee covers 3 of 35 hooks — measured 2026-07-15. The enforcer skips bite entirely for non-blocking roles (`if (entry.role !== 'blocking') continue`), and kind:'unit' only does existsSync(testPath) rather than executing. Census of governance/agent-hooks.v1.json: advisory 22 with NO bite + 4 unit + 1 command-signal (merge-full-suite-hint, added today, the first advisory hook with an executable spec); blocking 3 command-signal + 3 unit; telemetry 2 none. So only the 3 blocking+command-signal hooks are ever proven to fire; every hint hook's bite is decorative or absent. Same class as the two already found today (unit-kind existsSync; 22 real sandbox tests never run by CI) — a proof that exists but never executes. Fixing it is a program, not a patch: enabling advisory execution needs bites authored for 26 hooks. Candidate tempdoc. — `scripts/governance/gates/hook-integrity/enforcer.mjs:195` (2026-07-15)
- [ ] wire gate cannot version-track a validation-only proto change: relaxing a buf.validate pattern is invisible to `buf breaking`, so a matching VERSION bump is rejected as phantom-version — `scripts/governance/gates/wire/enforcer.mjs` (2026-08-17)

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
`kind: environment?` `anchor: scripts/sandbox/check_golden_parity.py` `seen: 2` `first: 2026-07-15` `last: 2026-07-30`
- [ ] Golden-parity rebuild-variance CALIBRATED (n=3 clean scifact rebuilds, dev GPU-FP16, same corpus/code/model, 30 query-observations, 2026-07-15). Pure build-to-build variance: overlap min=9 max=10 mean=9.87, never <9; top-1 mismatches 0/30; only q02 and q06 ever move, by exactly 1 doc; q08 is 10/10 across every pair. embeddingFingerprint + docCount (5184) identical across A/B/C. CONCLUSION: a sandbox round's 8/10 with q06/q08 below the 7-overlap bar is OUTSIDE the rebuild envelope by a wide margin — it is a REAL signal about the installed build, NOT the HNSW-tail noise four rounds (734 deferred item; sandbox-CLAUDE.md ~254-265) assumed. MIN_OVERLAP=7 is too LENIENT vs the measured floor of 9, not too strict. Does NOT establish the CAUSE — most likely CPU-FP32 (sandbox, CPU-only) vs GPU-FP16 (check_golden_parity docstring flags this), a hypothesis for a human. Full data: scratchpad/parity-calibration (ephemeral). — `scripts/sandbox/check_golden_parity.py:64` (2026-07-15)
- [ ] 750/734 finding-5 harness gaps (opus dossier 2026-07-30): (1) v2 golden-parity baseline never committed — check_golden_parity.py silently degrades to overlap-only (PARITY_UNCALIBRATED_POPULATION); (2) no reranker identity/EP fingerprint check despite CE solely ordering the top-10 — `check_golden_parity.py:276`; (3) golden parity has no row in governance/sandbox-coverage.v1.json; (4) dead config keys written-never-read `ResolvedConfigBuilder.java:505-508` (search.rerank.enabled, search.hybrid.ann_k/bm25_k), workers.ai.* and index.pipeline.profile zero consumers; (5) 750 frontmatter contradicts its own Implementation section. (2026-07-30)

### obs:brainsurface — MERGE-ORDER CONSTRAINT (release 0.2.0): PR #184 (worktree-release-asset-set) must merge to main BEFO
`kind: defect?` `anchor: modules/ui-web/src/shell-v0/views/BrainSurface.ts` `seen: 3` `first: 2026-07-15` `last: 2026-08-18`
- [ ] MERGE-ORDER CONSTRAINT (release 0.2.0): PR #184 (worktree-release-asset-set) must merge to main BEFORE the 737 branch (worktree-737-ai-runtime). Both carry the A1 inference-mode band-aid (BrainSurface.ts, BrainSurface.indexing-escape.test.ts, aiStateStore.ts, aiVerdict.ts) in their shared base; 737 SUPERSEDES and deletes it. #184-first is clean (737's diff already accounts for A1 in its base and cleanly removes it); 737-first CONFLICTS (main would have the rewrite while #184 still re-adds the deleted test + A1-shaped BrainSurface). Owner chose two separate PRs + order-them (2026-07-15), not A1 surgery. Note: 'zero shared files' held BETWEEN the two branches but NOT relative to main. — `modules/ui-web/src/shell-v0/views/BrainSurface.ts` (2026-07-15)
- [ ] BrainSurface compat callout's schema detail line still reads the flat 'Schema incompatible' for both BLOCKED_LEGACY and BLOCKED_MISMATCH, while the embedding arm distinguishes the two (fingerprint missing vs mismatch) — asymmetric technical detail — `modules/ui-web/src/shell-v0/views/BrainSurface.ts:1851` (2026-08-06)
- [ ] BrainSurface renders rows with class `data-row` (renderTierBreakdown / renderModels / renderSearchQualityFeatures) but its static styles define no `.data-row` rule, so those rows are unstyled — the rule exists only in HealthSurface.ts and security/atRestCard.ts — `modules/ui-web/src/shell-v0/views/BrainSurface.ts:2193` (2026-08-18)

### obs:719-reproducible-public-agent-utility-benchmark — 719 source-identity prose is behind the implementation: #178 (725) added mcp_initialize_identity + s
`kind: defect?` `anchor: docs/tempdocs/719-reproducible-public-agent-utility-benchmark.md` `seen: 1` `first: 2026-07-15` `last: 2026-07-15`
- [ ] 719 source-identity prose is behind the implementation: #178 (725) added mcp_initialize_identity + session-config exposure mode to the captured surface, and utility-claim-policy.v1.json now requires verified_exposure_mode — 719's boundary section still names only the tools/list hash. Extend the prose when 719 is next touched — `docs/tempdocs/719-reproducible-public-agent-utility-benchmark.md:32` (2026-07-15)

### obs:707-pillar1-inband-utility-corpus — 707 chain-2 engine finding candidate: German pure-synonym semantic bridging collapses with corpus si
`kind: follow-up?` `anchor: docs/tempdocs/707-pillar1-inband-utility-corpus.md` `seen: 2` `first: 2026-07-16` `last: 2026-07-16`
- [ ] 707 chain-2 engine finding candidate: German pure-synonym semantic bridging collapses with corpus size on the current encoder (DE v2 gold, zero lexical overlap: hybrid 0.21-0.27 at 1k -> 0.043 at 10k, union recall 0.40 -> 0.10; CLERC EN same design holds 0.32 at 10k). Routes to the encoder-representation lane (708-successor), not corpus design — `docs/tempdocs/707-pillar1-inband-utility-corpus.md` §Chain-2 (2026-07-16)
- [ ] Subagent watcher-strand pattern (2x this session, 2026-07-16): workers stop mid-task 'waiting for background pytest via Monitor' and never resume — the Monitor notification does not reach a completed/stopped agent turn; each needed a SendMessage resume with 'run it foreground'. Same family as the main-loop watcher failures being investigated; worker briefs should mandate synchronous verification, and the harness fix should consider agent-scoped monitors dying with the agent turn — `docs/tempdocs/707-pillar1-inband-utility-corpus.md` (supervision sections) (2026-07-16)

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

### obs:unanchored-general-61 — DE encoder-lane charter opened as tempdoc 748 (German semantic bridging 10k collapse) — closes the 7
`kind: defect?` `anchor: none` `seen: 1` `first: 2026-07-16` `last: 2026-07-16`
- [ ] DE encoder-lane charter opened as tempdoc 748 (German semantic bridging 10k collapse) — closes the 707 chain-2 'routed to encoder lane' condition; DE remains 1k-only secondary stratum until 748 closes (2026-07-16)

### obs:docs-validate — docs-validate.mjs crashes (uncaught YAMLException from gray-matter) on docs/tempdocs/530-*.md's fron
`kind: environment?` `anchor: scripts/docs/docs-validate.mjs` `seen: 3` `first: 2026-07-16` `last: 2026-08-06`
- [ ] docs-validate.mjs crashes (uncaught YAMLException from gray-matter) on docs/tempdocs/530-*.md's frontmatter — an unescaped mid-line value breaks js-yaml block-mapping parsing; pre-existing, unrelated to the synonyms-loader removal (742) — `scripts/docs/docs-validate.mjs` + `docs/tempdocs/530-discipline-gate-kernel-four-layer-design.md:6` (approx, 'updated:' line) (2026-07-16)
- [ ] docs-validate.mjs crashes with YAMLException on docs/tempdocs/530-*.md frontmatter — pre-existing, found during 742 synonyms-reader removal — `scripts/docs/docs-validate.mjs` (2026-07-16)
- [ ] `scripts/docs/docs-validate.mjs` crashes on a YAML parse error in tempdoc 530's frontmatter — pre-existing, reproduced with unrelated edits stashed — `docs/tempdocs/530-class-size-ratchet-automation.md:1` (2026-08-06)

### obs:gen-liveness-constants — check-liveness-constants-regen fails on main: SPDX mass-commit (11c306af) stamped the generated Live
`kind: environment?` `anchor: scripts/codegen/gen-liveness-constants.mjs` `seen: 1` `first: 2026-07-16` `last: 2026-07-16`
- [ ] check-liveness-constants-regen fails on main: SPDX mass-commit (11c306af) stamped the generated LivenessWindows.java but gen-liveness-constants.mjs doesn't emit SPDX headers — regen check red since 2026-06-23, pre-existing, unrelated to 742 — `scripts/codegen/gen-liveness-constants.mjs` (2026-07-16)

### obs:enforcer-test — Pre-existing test failure: operation-surface enforcer.test.mjs 'no forbidden file present -> pass' g
`kind: environment?` `anchor: scripts/governance/gates/operation-surface/enforcer.test.mjs` `seen: 1` `first: 2026-07-16` `last: 2026-07-16`
- [ ] Pre-existing test failure: operation-surface enforcer.test.mjs 'no forbidden file present -> pass' gets verdict operation-surface/vacuous-scan (fail); its harness also exits 0 despite the failure, masking it from node --test-per-file CI — `scripts/governance/gates/operation-surface/enforcer.test.mjs:1` (2026-07-16)

### obs:21-agent-analytics-pipeline — Canonical doc 21-agent-analytics-pipeline.md predates the 622 OTel path: it documents zero of otlp-s
`kind: environment?` `anchor: docs/explanation/21-agent-analytics-pipeline.md` `seen: 1` `first: 2026-07-16` `last: 2026-07-16`
- [ ] Canonical doc 21-agent-analytics-pipeline.md predates the 622 OTel path: it documents zero of otlp-sink.py/otlp-viewer/outcome-session/record-merge/baseline-economics, lists 8 of ~40 hooks, and its headline 'Content is never stored' (`docs/explanation/21-agent-analytics-pipeline.md:72`) is true only of the hook/input-summarizer path — the OTel path stores full prompts + raw API bodies (OTEL_LOG_USER_PROMPTS/TOOL_CONTENT/RAW_API_BODIES=1). Both stay local so the local-only posture holds; the claim is scope-drifted, not a leak. Found during 745 investigation. (2026-07-16)

### obs:transcript-cost — otlp-sink.py getPricing() falls back to DEFAULT_PRICING (sonnet-4-5) for any unrecognized model id —
`kind: follow-up?` `anchor: scripts/agent-analytics/lib/transcript-cost.mjs` `seen: 7` `first: 2026-07-16` `last: 2026-08-18`
- [ ] otlp-sink.py getPricing() falls back to DEFAULT_PRICING (sonnet-4-5) for any unrecognized model id — `scripts/agent-analytics/lib/transcript-cost.mjs:48-53` — i.e. a silent mis-price at the lib layer. 743 Phase-1's 'unknown models are bucketed loudly, never silently priced' holds only because callers separately call the surface-unknown helper; a future caller that forgets inherits silent wrong dollars. Consider making the fallback fail-loud at the lib boundary. Noticed during 745 investigation, not fixed (out of scope). (2026-07-16)
- [ ] Possible 4th pricing defect: sonnet-5 may be on a $2/$10 intro rate through 2026-08-31 reverting to $3/$15 on 2026-09-01, while `scripts/agent-analytics/lib/transcript-cost.mjs:23` hardcodes the $3/$15 sticker rate — if true we overstate sonnet-5 ~50% today. REPORTED BY PROBE, NOT INDEPENDENTLY VERIFIED — verify against Anthropic pricing before acting. Neither ccusage nor Usage-Monitor handles the dated cliff either; date-conditional pricing is unimplemented everywhere. See tempdoc 745 F-10. (2026-07-16)
- [ ] transcript-cost.mjs pricing table lacks claude-opus-5 — 206.5M tokens across 2 sessions costed at $0 in baseline-economics rerun 2026-07-28 (warning fired as designed); add pricing via claude-api reference before next analysis — `scripts/agent-analytics/lib/transcript-cost.mjs` (2026-07-28)
- [ ] Cost tooling prices claude-opus-5 at $0 — it is absent from the PRICING table, so 36,139 corpus turns / 7.85G cache-read tokens (52% of all cache-read) fail closed to zero and every baseline-economics/cost-session total silently excludes the dominant model. Verified rates (platform.claude.com/docs/en/about-claude/pricing, 2026-08-18) are IDENTICAL to Opus 4.8, i.e. the existing `OPUS_CURRENT` row — one-line fix. CORRECTION to this note's first version: it also claimed a missing >200k long-context tier; that is WRONG — Claude 4.6+ include the full 1M window at standard pricing, so there is nothing to add — `scripts/agent-analytics/lib/transcript-cost.mjs:63` (2026-08-17, corrected 2026-08-18)
- [ ] Sonnet 5's scheduled price increase in `transcript-cost.mjs` will silently activate on 2026-09-01 and overprice every Sonnet 5 turn by 50%. The pricing page now states the $2/$10 introductory rate "is now the standard price" and "the previously scheduled increase to $3/$15 per million input/output tokens on September 1, 2026 will not occur". `SONNET_5_INTRO_ENDS_MS` + the `schedule` array need removing so Sonnet 5 resolves to the flat $2/$10 row — `scripts/agent-analytics/lib/transcript-cost.mjs:39,71` (2026-08-18)
- [ ] `OPUS_4_7_FAST` prices Opus 4.7 fast mode at $30/$150, but fast mode is NOT available on Opus 4.7 at all (requests with `speed:"fast"` return an error); the row should go. Conversely there is no fast row for `claude-opus-5`, which DOES support it at $10/$50. Currently inert (corpus has zero fast turns, verified 745) but wrong in both directions — `scripts/agent-analytics/lib/transcript-cost.mjs:55-61` (2026-08-18)
- [ ] Cost tooling prices claude-opus-5 at $0 — it is absent from the PRICING table, so 36,139 corpus turns / 7.85G cache-read tokens (52% of all cache-read) fail closed to zero and every baseline-economics/cost-session total silently excludes the dominant model. Also no >200k long-context tier despite sessions running near 1M ctx — `scripts/agent-analytics/lib/transcript-cost.mjs:63` (2026-08-17)

### obs:otlp-sink — otlp-sink.py's only third-party dependency (`opentelemetry-proto`, hard-imported at `scripts/agent-a
`kind: defect?` `anchor: scripts/agent-analytics/otlp-sink.py` `seen: 1` `first: 2026-07-16` `last: 2026-07-16`
- [ ] otlp-sink.py's only third-party dependency (`opentelemetry-proto`, hard-imported at `scripts/agent-analytics/otlp-sink.py:17-22`) is declared NOWHERE in the repo — no requirements.txt/pyproject, only a passing mention in tempdoc 622. On a fresh checkout `otlp-sink-ensure` spawns the sink detached with stdio:'ignore', so an ImportError kills it SILENTLY and telemetry stops with no symptom — the same silent-failure class as the chunked-encoding bug (743) and the rotation bug (745 F-2), now three times in one file. 745 pins it in CI; declaring it properly (requirements file + a startup check in otlp-sink-ensure) is unowned. (2026-07-16)

### obs:run — PR #215's gate-input contract makes a bare local `node scripts/governance/run.mjs --mode gate` exit
`kind: follow-up?` `anchor: run.mjs` `seen: 2` `first: 2026-07-16` `last: 2026-08-13`
- [ ] PR #215's gate-input contract makes a bare local `node scripts/governance/run.mjs --mode gate` exit 1 on a fresh worktree — npm-audit/module-deps/dead-code/dead-code-jvm now fail CLOSED on missing inputs rather than passing vacuously. Correct behaviour and each names its remedy, but two things compound it: the failures are invisible in the output tail (only the bare exit code shows them), and public CI does NOT run the kernel at all, so local is the only place it ever runs. An agent who greps the tail for ': fail' sees nothing and concludes green. Producing all four inputs (report-npm-audit.mjs, module-deps.mjs, knip:report, :modules:dead-code-audit:test) then gives 34/34 exit 0. Consider a one-shot 'produce kernel inputs' script or a note in the pre-merge table. Found during 745 publish. (2026-07-16)
- [ ] consult-register ui-web-gates recipe prescribes `scripts/governance/run.mjs --gate a,b,c` but the runner does not split on commas (fails 'gate id not found'); gates must be run one --gate at a time — `governance/consult-register.v1.json:ui-web-gates` (2026-08-13)

### obs:event-writer — SAME-CLASS DEFECT LEFT UNFIXED by 745: `scripts/agent-analytics/lib/event-writer.mjs` rotates events
`kind: defect?` `anchor: scripts/agent-analytics/lib/event-writer.mjs` `seen: 1` `first: 2026-07-16` `last: 2026-07-16`
- [ ] SAME-CLASS DEFECT LEFT UNFIXED by 745: `scripts/agent-analytics/lib/event-writer.mjs` rotates events.ndjson at 10MB via `fs.renameSync(filePath, filePath + '.prev')`, which SILENTLY OVERWRITES the existing .prev — identical data loss to the otlp-sink rotation 745 F-2 just fixed, only implicit instead of an explicit os.remove. It is LIVE: dispatch.mjs + export-session-env.mjs write it, and telemetry-io reads it. Measured 2026-07-16: events.ndjson 6.8MB against the 10MB trigger, with 10.49MB in .prev that the next rotation destroys. Per structural-defects-no-repeat one documented instance proves the class — 745 fixed one instance and left the sibling. Deliberately NOT bolted onto PR #221 (different subsystem/consumers; the PR was already independently reviewed and green — adding an unreviewed change would bypass that review). Remedy is known and cheap: mirror otlp-sink.py's archive+per-stream-retention pattern. TOP FOLLOWUP. (2026-07-16)

### obs:fold-observations — fold-observations.mjs is NOT idempotent for MERGED entries — re-folding a shard inflates the `seen`
`kind: follow-up?` `anchor: scripts/agent-analytics/fold-observations.mjs` `seen: 3` `first: 2026-07-16` `last: 2026-08-06`
- [ ] fold-observations.mjs is NOT idempotent for MERGED entries — re-folding a shard inflates the `seen` ranking signal. Mechanism: an entry that merges into an existing condition has its text rewritten in the store (occurrence appended), so on a later fold of the same shard it no longer matches verbatim, misses the exact-duplicate skip, and merges AGAIN (seen++). Entries that OPEN a condition round-trip fine. Observed live 2026-07-16: shard 70bf04ea was folded, then session 70bf04ea appended 2 entries and restored the file via PR #222; re-folding its 8 entries gave 5 exact-duplicate skips instead of 6, i.e. one already-folded entry double-counted. Impact is small (seen is a ranking signal, not a gate) but it is silent and compounds with every modify/delete shard race — which will recur, since a shard can be appended to after a fold reads it. Candidate fix: key the duplicate check on a stable entry hash rather than the post-merge text. — `scripts/agent-analytics/fold-observations.mjs:107` (2026-07-16)
- [ ] fold-observations.mjs --apply resolves repoRoot from the script location, not cwd — invoked from a worktree it silently folds the MAIN checkout instead (observed 2026-07-17; required a manual transplant of the fold into the PR branch + restore of main's tree). It should honor cwd or take an explicit --root — `scripts/agent-analytics/fold-observations.mjs` (2026-07-17)
- [ ] fold-observations.mjs has no base-freshness guard: run in a checkout behind origin/main it silently produces a fold that ROLLS BACK newer upstream conditions (seen-counts, whole entries) — caught only by diff review before publication (2026-08-06 near-miss; rebuilt on current base). Consider refusing --apply when HEAD is behind origin/main on docs/observations.md — `scripts/agent-analytics/fold-observations.mjs` (2026-08-06)

### obs:coreworkflowcatalog — justsearch_dev_start defaults to the MAIN CHECKOUT's installed dist even when called from a worktree
`kind: defect?` `anchor: modules/app-services/src/main/java/io/justsearch/app/services/conversation/CoreWorkflowCatalog.java` `seen: 1` `first: 2026-07-16` `last: 2026-07-16`
- [ ] justsearch_dev_start defaults to the MAIN CHECKOUT's installed dist even when called from a worktree session -- silently testing stale code with no error. Must pass distFrom:<worktree-path> explicitly after editing Java in a worktree, not just re-run installDist. Cost real debugging time verifying tempdoc 734/744's core.workflow-run fix (2026-07-16) -- `modules/app-services/src/main/java/io/justsearch/app/services/conversation/CoreWorkflowCatalog.java`, `WorkflowShapeRunner.java`. (2026-07-16)

### obs:unanchored-general-67 — DE encoder-lane charter opened as tempdoc 748 (German semantic bridging 10k collapse) — closes the 7
`kind: defect?` `anchor: none` `seen: 1` `first: 2026-07-16` `last: 2026-07-16`
- [ ] DE encoder-lane charter opened as tempdoc 748 (German semantic bridging 10k collapse) — closes the 707 chain-2 'routed to encoder lane' condition; DE remains 1k-only secondary stratum until 748 closes (2026-07-16)

### obs:unanchored-general-70 — main checkout's local main is ~4 docs(743) commits + merge-commits ahead of origin/main (branch prot
`kind: defect?` `anchor: none` `seen: 1` `first: 2026-07-16` `last: 2026-07-16`
- [ ] main checkout's local main is ~4 docs(743) commits + merge-commits ahead of origin/main (branch protection strands direct commits) — needs a batch docs PR from whoever owns the 743 session; pullers get recurring 'Merge branch main' commits until then (2026-07-16)

### obs:corpus-certify — 707 certification signs corpus.jsonl+qrels but utility-run's staged binding hashes the raw corpus-di
`kind: follow-up?` `anchor: scripts/jseval/jseval/corpus_certify.py` `seen: 1` `first: 2026-07-16` `last: 2026-07-16`
- [ ] 707 certification signs corpus.jsonl+qrels but utility-run's staged binding hashes the raw corpus-dir files — strict --corpus-certification can never pass on a certified member (Step-2 ran declared-signature mode with a recorded hash-equivalence chain instead). Follow-up: add corpus_dir_signature to corpus-certify-member + thread it through utility-run strict mode — `scripts/jseval/jseval/corpus_certify.py:617` (2026-07-16)

### obs:utility-calibrate — Step-2 campaign harness lesson: utility-calibrate's pooled-pilot timeout (p95x2) underestimates the
`kind: lesson?` `anchor: scripts/jseval/jseval/utility_calibrate.py` `seen: 3` `first: 2026-07-17` `last: 2026-07-18`
- [ ] Step-2 campaign harness lesson: utility-calibrate's pooled-pilot timeout (p95x2) underestimates the A-arm (grep) tail on 10k corpora — A-arm timeout attrition (32%) voided comparability exactly where the tool wins; next campaign needs per-arm timeout calibration pre-run — `scripts/jseval/jseval/utility_calibrate.py` (2026-07-17)
- [ ] Design-interaction lesson (phase-2 email-10k): exhaustion-as-failure ITT scoring requires IDENTICAL per-arm budgets — per-arm timeout application (built to fix Step-2's slow-arm starvation) inverted the bias and starved the fast arm (B floor-clamped 120s < its own p95; 26/60 B exhaustions, accuracy delta flipped negative as artifact). Rule: calibrate per arm, apply max() to all arms. Cross-increment interactions need a review lens of their own — `scripts/jseval/jseval/utility_calibrate.py` (2026-07-17)
- [ ] chain-level banked calibration.json survives across git SHA changes between launch attempts — v4 confirmatory chain adopted a 23:33 calibration pinned at 92ec2e6d into a 079e63e5 run, splitting the config cohort and failing recompose (incident #5, tempdoc 624). Fix direction: utility-calibrate should stamp git_sha into calibration.json and chains should invalidate banked calibrations on mismatch — `scripts/jseval/jseval/utility_calibrate.py:252` (2026-07-18)

### obs:agent-utility-observations — utility claim policy treats resource-exhausted cells (wall-clock/USD budget) as EXCLUDED rather than
`kind: defect?` `anchor: scripts/jseval/jseval/agent_utility_observations.py` `seen: 2` `first: 2026-07-17` `last: 2026-07-28`
- [ ] utility claim policy treats resource-exhausted cells (wall-clock/USD budget) as EXCLUDED rather than as ITT failures — the conventional exhaustion-as-failure outcome rule would have made Step-2's matrices complete (60/60 pairs) instead of comparability-voided; encode it as the pre-registered primary rule before the next campaign, then re-verdict Step-2 offline via 719 replay — `scripts/jseval/jseval/agent_utility_observations.py:96` (2026-07-17)
- [ ] Judge provenance gap: a composed utility record scored through a judge-overlay.json still stamps cohort.judge.kind='substring-em' (taken from task metadata), so the record understates how its correctness was produced — pre-existing on both the log and evidence paths, digest-affecting to change — `scripts/jseval/jseval/agent_utility_observations.py:107` (2026-07-28)

### obs:backend — Eval campaigns rebuild identical indexes repeatedly (legal-10k built 3x in 12h for the same corpus_s
`kind: defect?` `anchor: scripts/jseval/jseval/backend.py` `seen: 3` `first: 2026-07-17` `last: 2026-07-31`
- [ ] Eval campaigns rebuild identical indexes repeatedly (legal-10k built 3x in 12h for the same corpus_signature x config_cohort_key) — a content-addressed index cache keyed on exactly those two pins would keep the fresh-build validity guarantee while amortizing ~50min/build; belongs to the 676/pillar-6 isolated eval lane; note the 716 retirement of --clean protected-set reuse was about UNKEYED reuse, which this design avoids — `scripts/jseval/jseval/backend.py` (2026-07-17)
- [ ] jseval backend lifecycle can bind to the WRONG backend: start_backend health-polls the fixed port (33221) but runHeadlessEval falls back to an ephemeral port when 33221 is occupied (observed live: stale process answered 33221 while the spawned Head logged 'Local API Server started on port 57198'); jseval then ran its whole 49-min lifecycle against the stale process with exit masked. Fix direction: verify the polled backend's identity (PID or /api/debug/state justsearch.data.dir) against the spawned proc — `scripts/jseval/jseval/backend.py:156` (2026-07-17)
- [ ] jseval's startup `Capability warning: reranker_cpu_only` can be WRONG — it fires before GPU realization completes; the same run's manifest recorded `reranker_gpu: true` + `rerankerOrtCuda.available: true`. Register finding F-026's 'reranker ran on CPU' caveat is sourced from this warning and may be mistaken — `scripts/jseval/jseval/backend.py` (tempdoc 802) (2026-07-31)

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
`kind: defect?` `anchor: scripts/agent-analytics/note-observation.mjs` `seen: 2` `first: 2026-07-17` `last: 2026-08-04`
- [ ] A session's observation shard can reach `origin/main` inside ANOTHER session's PR: PR #229 branched from local `main` after this session committed its shard, so its squash carried entry 1 upstream under a different commit identity — producing an add/add conflict when this session's own PR later merged `origin/main` (resolved as the union; no loss). Shards are per-session by design but ride whatever branch happens to contain them — `scripts/agent-analytics/note-observation.mjs` (2026-07-17)
- [ ] note-observation dual-write divergence: this session's shard accumulated DIFFERENT entries in the main-checkout copy vs the worktree copy (5 entries missed by the #355 fold, recovered in #360) — the helper appears to resolve repo root from cwd at call time, so cd-drift or hook-context writes fork the shard; it should resolve the SESSION's worktree deterministically — `scripts/agent-analytics/note-observation.mjs` (2026-08-04)

### obs:unanchored-general-74 — Founder finding (2026-07-17): no agent ever proactively flagged the corpus/index-rebuild inefficienc
`kind: lesson?` `anchor: none` `seen: 1` `first: 2026-07-17` `last: 2026-07-17`
- [ ] Founder finding (2026-07-17): no agent ever proactively flagged the corpus/index-rebuild inefficiency (4x ~50min identical rebuilds in 24h; GPU monopolized, develocity externality on all other sessions) — mechanisms: endured-friction-isnt-a-finding bias (agents build supervision AROUND delays instead of filing them), the 716 validity ratchet (reuse pattern-matched to danger, keyed-vs-unkeyed never articulated), dollars instrumented/GPU-hours unmeasured, and the founder's overnight-window workaround signaling the cost as pre-accepted. Instance owned by tempdoc 751; the CLASS fix candidates: (a) GPU-hours/wall-clock ledger on run manifests + periodic projection (duration family precedent), (b) postmortem handle 'friction-you-schedule-around-is-a-finding' in agent-postmortems.md (2026-07-17)

### obs:index-identity — 751 index-cache chain-integration finding (live, 14:49): passing an explicit corpus-dir SUBDIR as th
`kind: defect?` `anchor: scripts/jseval/jseval/index_identity.py` `seen: 2` `first: 2026-07-17` `last: 2026-07-17`
- [ ] 751 index-cache chain-integration finding (live, 14:49): passing an explicit corpus-dir SUBDIR as the cache's corpus axis makes corpus_signature come back empty (dataset-dir mode expects corpus.jsonl+qrels) → cache disables fail-quiet → chain topologies silently lose all caching. The §I.5 corpus-dir assert should also ACCEPT subdir mode (files-mode signature) or resolve the parent; fail-quiet was correct but a one-line WARN naming the remedy would have saved a diagnosis cycle — `scripts/jseval/jseval/index_identity.py` (2026-07-17)
- [ ] 751 §P.3.5 chain-integration spec (live findings, 2026-07-17 14:49-15:00, three attempts): (1) explicit corpus-dir SUBDIR → selector signature empty → fail-quiet disable (needs files-mode or parent resolution + a WARN); (2) two-boot chain topology (publish via jseval-run pass, adopt via wrapper) breaks on F-A corpus_dir_path binding unless publisher and adopter resolve the IDENTICAL path — jseval's default resolution picks tmp/eval-corpora while chains use datasets/<cell>/corpus-dir; (3) tmp/eval-corpora staging carries a .source_signature sidecar that would index as a stray 10002nd doc AND its watched root trips utility-run's stray-root gate against the datasets/ convention. Campaign reverted to fresh-build; integration needs a designed seam (e.g. index-cache warm CLI or publish-from-wrapper) not chain-side improvisation — `scripts/jseval/jseval/index_identity.py` (2026-07-17)

### obs:resolvedconfig — Config dead-surface at scale: 70 of 342 `ResolvedConfig` record components are inert (55 zero call s
`kind: defect?` `anchor: modules/configuration/src/main/java/io/justsearch/configuration/resolved/ResolvedConfig.java` `seen: 1` `first: 2026-07-15` `last: 2026-07-15`
- [ ] Config dead-surface at scale: 70 of 342 `ResolvedConfig` record components are inert (55 zero call sites, 15 test-only) across 2440 java files. Verified not reflectively reached (snapshot mapper writes LinkedHashMap<String,String>, EffectiveConfigController reads resolutions()), no method refs, not read by key, not on the wire. They split into OBSOLETE (delete) vs UNWIRED/shadowed (wire up) — so neither bulk delete nor bulk wire is safe; needs a classification pass — `modules/configuration/src/main/java/io/justsearch/configuration/resolved/ResolvedConfig.java:410` (2026-07-15)

### obs:wholeprogramdeadcodetest — Dead-code gates have a substrate blind spot: `dead-code-jvm` emits only `"kind": "class"` (19 entrie
`kind: defect?` `anchor: modules/dead-code-audit/src/test/java/io/justsearch/deadcode/WholeProgramDeadCodeTest.java` `seen: 1` `first: 2026-07-15` `last: 2026-07-15`
- [ ] Dead-code gates have a substrate blind spot: `dead-code-jvm` emits only `"kind": "class"` (19 entries, report-only/never fails, ratcheted) and `dead-code` wraps Knip for TS exports. Neither covers java MEMBERS, config keys, YAML, or gradle properties — the substrates where the config/GJF/OTLP dead surface actually lives. The whole-program import already sees all visibilities, so member granularity is emit-side, not analysis-side — `modules/dead-code-audit/src/test/java/io/justsearch/deadcode/WholeProgramDeadCodeTest.java:1` (2026-07-15)

### obs:embeddingbackfillops — EmbeddingBackfillOps.processChunkEmbeddingBackfill Phase 1 fetches CHUNK_CONTENT via one getDocument
`kind: defect?` `anchor: modules/worker-services/src/main/java/io/justsearch/indexerworker/loop/ops/EmbeddingBackfillOps.java` `seen: 3` `first: 2026-07-12` `last: 2026-08-10`
- [ ] EmbeddingBackfillOps.processChunkEmbeddingBackfill Phase 1 fetches CHUNK_CONTENT via one getDocumentField() call per chunk instead of the batched getDocumentFieldsBatch() CombinedEnrichmentBackfillOps already uses — noticed while designing tempdoc 720 (P1a prepend) — `modules/worker-services/src/main/java/io/justsearch/indexerworker/loop/ops/EmbeddingBackfillOps.java:350-367` (2026-07-12)
- [ ] FAILED-status chunks silently stop being retried and remain vector/splade-less with no serve-time gate — EmbeddingBackfillOps/SpladeBackfillOps select PENDING only; retry-count fields exist but nothing surfaces or re-drives the FAILED population (749 scope-investigation census site 8) — `modules/worker-services/.../loop/ops/EmbeddingBackfillOps.java:325` (2026-07-17)
- [ ] Chunk embeddings may have the same first-fault-FAILED-never-retried shape that tempdoc 819 C fixed for parent docs — the chunk backfill selects only CHUNK_EMBEDDING_STATUS=PENDING and chunk_embedding_retry_count is not reset by any rescue path — `modules/worker-services/src/main/java/io/justsearch/indexerworker/loop/ops/EmbeddingBackfillOps.java:325-329` (2026-08-10)

### obs:run-error — jseval run --dataset mixed/legal-clerc-200 --max-queries 0 --pipeline --start-backend --clean --json
`kind: defect?` `anchor: scripts/jseval/jseval/run.py` `seen: 2` `first: 2026-07-17` `last: 2026-07-28`
- [ ] jseval run --dataset mixed/legal-clerc-200 --max-queries 0 --pipeline --start-backend --clean --json completed exit 0 but wrote NO eval-results run dir / summary.json (2026-07-17 10:26-10:30 run; enrichment completed and was stamped, backend stopped 3s later) — summary write appears skipped or crashed silently on the no-queries path — `scripts/jseval/jseval/run.py` (2026-07-17)
- [ ] jseval `--qu` / `--filter-norm` are unreachable in eval mode: both require `--llm`, which cannot bring the engine up under the read-only IN_MEMORY eval settings store (tempdoc 782 §I) — `scripts/jseval/jseval/commands/run.py:144-156` (2026-07-28)

### obs:unanchored-missing-5 — Manual observations.d conflict resolution can silently drop content: merge 603cc5bf claimed 'content
`kind: defect?` `anchor: none` `seen: 1` `first: 2026-07-17` `last: 2026-07-17`
- [ ] Manual observations.d conflict resolution can silently drop content: merge 603cc5bf claimed 'content-preserving' fold of 3 deleted shards but shard cfa87fbc's record-merge mis-link bullet (from 5a90bf44) was never folded — verified absent from observations.md by exact-string grep; rescued via worktree-rescue-720-docs. Process gap: content-preservation claims in fold merges are checked per-shard, not per-bullet — `docs/observations.md` (2026-07-17)

### obs:run-gh — run-gh.mjs checks-wait races a just-pushed catch-up commit: it matches the PREVIOUS commit's green r
`kind: defect?` `anchor: scripts/dev/run-gh.mjs` `seen: 2` `first: 2026-07-17` `last: 2026-08-13`
- [ ] run-gh.mjs checks-wait races a just-pushed catch-up commit: it matches the PREVIOUS commit's green run and exits 0 while the new run is still pending — observed twice in one merge session (PRs 237, 239); it should key on the head-SHA's runs, not the PR's latest completed run — `scripts/dev/run-gh.mjs` (2026-07-17)
- [ ] Publish-conveyor stopping rule was wrong (self-audit 2026-08-13 campaign): all 12 CI reruns were unnecessary — Integration tests lane is continue-on-error:true (ci.yml:533) and NOT in required_status_checks.contexts, every attempt-1 run conclusion was already 'success', PRs were mergeable at UNSTABLE; the orchestrator's CLEAN-only merge gate was stricter than branch protection and converted advisory-lane flakes into ~2.4h of serial stall + 102min re-execution. Fix: merge gate should consult required contexts; never rerun a lane that cannot change mergeability — `.claude/skills/publish/SKILL.md` + scripts/dev/run-gh.mjs checks-wait (2026-08-13)

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

### obs:utility-claim-policy — ITT usage evidence incomplete for exhausted cells → composed cost_usd/token efficiency intervals una
`kind: defect?` `anchor: scripts/jseval/jseval/utility_claim_policy.py` `seen: 1` `first: 2026-07-18` `last: 2026-07-18`
- [ ] ITT usage evidence incomplete for exhausted cells → composed cost_usd/token efficiency intervals unavailable ("incomplete ITT usage evidence") → per-stratum outcome caps at adoption-only even where ITT accuracy delta is significant (legal-1k +0.217 p=0.001); capture usage for exhausted cells or define a pre-registered imputation for the efficiency family — `scripts/jseval/jseval/utility_claim_policy.py:505` (2026-07-18)

### obs:gen-scorecard — gen-scorecard.mjs and gen-public-benchmark.mjs are wired into NO CI workflow — scorecard.md and meth
`kind: defect?` `anchor: gen-scorecard.mjs` `seen: 1` `first: 2026-07-18` `last: 2026-07-18`
- [ ] gen-scorecard.mjs and gen-public-benchmark.mjs are wired into NO CI workflow — scorecard.md and methodology.md silently drifted a full release cycle (2026-07-01 → 2026-07-16, caught by the 2026-07-18 numbers audit); wire both --check modes into the public-claims CI job — `.github/workflows/ci.yml` public-claims job (2026-07-18)

### obs:utility — 758 reviewer MINORs: (a) utility-run WITHOUT --calibration has no CLI-drift assert (chain path cover
`kind: defect?` `anchor: scripts/jseval/jseval/commands/utility.py` `seen: 1` `first: 2026-07-21` `last: 2026-07-21`
- [ ] 758 reviewer MINORs: (a) utility-run WITHOUT --calibration has no CLI-drift assert (chain path covered; bare path relies on DISABLE_AUTOUPDATER), (b) calibration SHA-binding detects commit movement but not dirty-tree drift at same HEAD (git_dirty stamped, unchecked) — `scripts/jseval/jseval/commands/utility.py:349-357` (2026-07-21)

### obs:queries — jseval retrieval-eval consumes only MultiHop-format queries (q['query'] + evidence_list); 635-corpor
`kind: defect?` `anchor: queries.json` `seen: 1` `first: 2026-07-18` `last: 2026-07-18`
- [ ] jseval retrieval-eval consumes only MultiHop-format queries (q['query'] + evidence_list); 635-corpora queries.json (evidence_ids, and needle-burial-v1's has null 'query') yield empty queries → 0% metrics silently. Discovered running the 749 reachability eval — `scripts/jseval/jseval/agent_retrieval_eval.py:load_queries` (2026-07-18)

### obs:unanchored-missing-8 — Manual observations.d conflict resolution can silently drop content: merge 603cc5bf claimed 'content
`kind: defect?` `anchor: none` `seen: 1` `first: 2026-07-17` `last: 2026-07-17`
- [ ] Manual observations.d conflict resolution can silently drop content: merge 603cc5bf claimed 'content-preserving' fold of 3 deleted shards but shard cfa87fbc's record-merge mis-link bullet (from 5a90bf44) was never folded — verified absent from observations.md by exact-string grep; rescued via worktree-rescue-720-docs. Process gap: content-preservation claims in fold merges are checked per-shard, not per-bullet — `docs/observations.md` (2026-07-17)

### obs:unanchored-general-79 — Installer size stated inconsistently across surfaces: 853 MB (README.md:57) vs ~748 MB (.claude/skil
`kind: defect?` `anchor: none` `seen: 1` `first: 2026-07-21` `last: 2026-07-21`

### obs:732-response-surface-residuals — 732's concise-default decision attributes the halved Reads-per-search to the text-tier Preview line,
`kind: defect?` `anchor: docs/tempdocs/732-response-surface-residuals.md` `seen: 1` `first: 2026-07-21` `last: 2026-07-21`
- [ ] 732's concise-default decision attributes the halved Reads-per-search to the text-tier Preview line, but delivery-tier measurement only began at campaign V (725:1714) and shows 98.9% structured-json — the Preview line is not delivered to structured-preferring clients, so the D->T->U Reads-halving (725:1492) may be attributable to structuredContent excerpts instead. 732's "no measurement changes it" conclusion may rest on an undelivered component — `docs/tempdocs/732-response-surface-residuals.md:126` (2026-07-21)

### obs:delivery-tier-probe-735 — delivery_tier_probe_735.py's `_LOCAL_PATH_RE` redaction only matches drive-letter paths (`X:\...`) —
`kind: defect?` `anchor: scripts/jseval/experiments/delivery_tier_probe_735.py` `seen: 1` `first: 2026-07-21` `last: 2026-07-21`
- [ ] delivery_tier_probe_735.py's `_LOCAL_PATH_RE` redaction only matches drive-letter paths (`X:\...`) — a UNC capture (`\server\share`) would survive into a committed fixture. Not hit by any current capture; found during the 770 pre-push scan — `scripts/jseval/experiments/delivery_tier_probe_735.py` (2026-07-21)

### obs:unanchored-general-80 — bundleSidecarResources fails config-cache STORE due to pre-existing config-cache-incompatible deps (
`kind: environment?` `anchor: none` `seen: 1` `first: 2026-07-21` `last: 2026-07-21`
- [ ] bundleSidecarResources fails config-cache STORE due to pre-existing config-cache-incompatible deps (headlessDist uses configurations.runtimeClasspath script-object refs; stageOrtCudaVariant Sync; generateWorkerAotCache captures a Sync task) — installer packaging path is not config-cacheable; run with --no-configuration-cache — `modules/ui/build.gradle.kts:308,753,~1000` (2026-07-21)

### obs:unanchored-general-81 — jseval debt from the 767-771 arc: known-RED correction-probe pair (restore data file or retire); per
`kind: defect?` `anchor: none` `seen: 1` `first: 2026-07-22` `last: 2026-07-22`
- [ ] jseval debt from the 767-771 arc: known-RED correction-probe pair (restore data file or retire); per-query eval artifacts capped at depth-10 (analyses wanted k>=20 — make depth configurable); no per-call cost invoicing (spend figures are cap-bounded estimates); index-cache entries not reusable across commits for certification-shaped flows (2026-07-22)

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
`kind: environment?` `anchor: modules/ui/src/test/java/io/justsearch/ui/api/LifecycleContractTest.java` `seen: 2` `first: 2026-07-22` `last: 2026-07-22`
- [ ] LifecycleContractTest (modules/ui) flakes under parallel full-suite load on dev machine: 2/10 tests (statusReadinessDegradesIndexServingWhenThroughputStalls :495, statusReadinessPendingInferenceOfflineWhenRuntimeIdle :398) hit 3s HttpTimeoutException against in-process LocalApiServer, green in isolation — same class as the 'Flaky IPC tests' pitfall; consider awaitPort-style tolerance or longer client timeout — `modules/ui/src/test/java/io/justsearch/ui/api/LifecycleContractTest.java:398,495` (2026-07-22)
- [ ] LifecycleContractTest ('idle PENDING inference maps to OFFLINE') flaked with HttpTimeoutException under full-suite parallel load on the 774 publish gate (1/558; passes in isolation; unchanged since v0.1.0) — timing-sensitive lifecycle test, candidate for awaitPort-style hardening. — `modules/ui/src/test/.../LifecycleContractTest.java:398` (2026-07-22)

### obs:unanchored-gate-red-7 — Register judge-stage conclusions predating F-041 (F-026 judge-blend/'obvious judge levers dead', 636
`kind: defect?` `anchor: none` `seen: 1` `first: 2026-07-22` `last: 2026-07-22`
- [ ] Register judge-stage conclusions predating F-041 (F-026 judge-blend/'obvious judge levers dead', 636 judge-rank-bound headroom profile, F-002) were all measured with a preview-blind CE (input = title + doc-head snippet). Tempdoc 777 builds on F-026 as its evidence base — it should re-baseline its listwise/judge experiments under evidence-coherent CE input (search.evidence_preview.enabled) before designing, or risk activating levers whose measured deadness was an artifact of the old CE input. — `774 §L / F-041` (2026-07-22)

### obs:unanchored-general-85 — jseval debt from the 767-771 arc: known-RED correction-probe pair (restore data file or retire); per
`kind: defect?` `anchor: none` `seen: 1` `first: 2026-07-22` `last: 2026-07-22`
- [ ] jseval debt from the 767-771 arc: known-RED correction-probe pair (restore data file or retire); per-query eval artifacts capped at depth-10 (analyses wanted k>=20 — make depth configurable); no per-call cost invoicing (spend figures are cap-bounded estimates); index-cache entries not reusable across commits for certification-shaped flows (2026-07-22)

### obs:unanchored-general-87 — A LOCKED agent worktree (agent-ace19997b9499c758) was removed mid-run by an external cleanup, wiping
`kind: defect?` `anchor: none` `seen: 1` `first: 2026-07-22` `last: 2026-07-22`
- [ ] A LOCKED agent worktree (agent-ace19997b9499c758) was removed mid-run by an external cleanup, wiping its working tree + most of tmp/ and severing .git — cost a live jseval probe its legal-1k artifacts and forced a worktree re-creation to commit; locked worktrees should be exempt from automated removal — `.claude/worktrees/` (2026-07-22)

### obs:unanchored-flake-5 — Flaky CI lane SECOND STRIKE: 'Unit tests (platform-contracts)' failed on PR #259 whose diff is jseva
`kind: environment?` `anchor: none` `seen: 1` `first: 2026-07-21` `last: 2026-07-21`
- [ ] Flaky CI lane SECOND STRIKE: 'Unit tests (platform-contracts)' failed on PR #259 whose diff is jseval Python + markdown (cannot affect Java) — first strike was PR #245 (2026-07-17, same shape). Two occurrences in 4 days on doc/python-only diffs: this lane now needs its own investigation (timing-sensitive test? runner memory?) per the first note's trigger — CI run on branch pub-757h, head 3d7d8e5b (2026-07-21)

### obs:unanchored-error-11 — 778 smoke bycatch: POST /api/knowledge/disposition swallows malformed JSON as silent 204 (never-fail
`kind: follow-up?` `anchor: none` `seen: 1` `first: 2026-07-22` `last: 2026-07-22`
- [ ] 778 smoke bycatch: POST /api/knowledge/disposition swallows malformed JSON as silent 204 (never-fail-the-FE catch, debug-only log) — masks client bugs; consider a WARN-level log or a 400-on-parse-error while keeping 204 for store failures — KnowledgeSearchController.handleDisposition:197-200 (2026-07-22)

### obs:unanchored-general-90 — downloadLlamaCudaPrebuilt has NO sha256 pin (comment: 'hash check disabled for large file') while th
`kind: defect?` `anchor: none` `seen: 1` `first: 2026-07-22` `last: 2026-07-22`
- [ ] downloadLlamaCudaPrebuilt has NO sha256 pin (comment: 'hash check disabled for large file') while the CPU prebuilt fail-closes on llamaPrebuiltSha256 — the two ~GB GPU assets are the unpinned half of the supply chain; found during 760 mirror-producer investigation — `modules/ui/build.gradle.kts:566-570` (2026-07-22)

### obs:unanchored-general-91 — Subagent incident (760 sandbox harness, 2026-07-22): a worker 'functionally validating' the destruct
`kind: environment?` `anchor: none` `seen: 1` `first: 2026-07-22` `last: 2026-07-22`
- [ ] Subagent incident (760 sandbox harness, 2026-07-22): a worker 'functionally validating' the destructive-by-design guest script ran it HOST-SIDE; its host-global HKCU uninstall-key lookup found the pre-existing F:\JustSearch-test install and silently ran its real uninstaller /S (registry key, shortcuts, uninstall.exe gone; resources\headless remnant left). Root-caused: destructive-by-design scripts need execution-environment guards, not header warnings — WDAGUtilityAccount guard added, host-side run now refuses (exit 99). Brief lesson: 'do not execute' must explicitly cover host-side dry-runs of guest payloads (2026-07-22)

### obs:corpus-general — legal-clerc-200 corpus.jsonl has 198 records but 199 .txt files are ingested and indexedDocuments re
`kind: defect?` `anchor: datasets/mixed/legal-clerc-200/corpus.jsonl` `seen: 1` `first: 2026-07-27` `last: 2026-07-27`
- [ ] legal-clerc-200 corpus.jsonl has 198 records but 199 .txt files are ingested and indexedDocuments reaches 199 — one-document discrepancy between recipe output and ingested corpus — `datasets/mixed/legal-clerc-200/corpus.jsonl` vs `scripts/jseval/tmp/eval-corpora/mixed/legal-clerc-200/` (2026-07-27)

### obs:member-v1 — 781 member.v1.json remaining_gates is pre-#311 residue — both members still list the 4 scientific ga
`kind: defect?` `anchor: scripts/jseval/781-corpora/en-email-enron-raw/member.v1.json` `seen: 1` `first: 2026-07-28` `last: 2026-07-28`
- [ ] 781 member.v1.json remaining_gates is pre-#311 residue — both members still list the 4 scientific gates as remaining while structural-certification.v1.json says fully-certified/32-32 green; the machine authority is corpus_certify.py:815-817 reading the certification file — `scripts/jseval/781-corpora/en-email-enron-raw/member.v1.json` (2026-07-28)

### obs:unanchored-missing-9 — Eval backfill wedge (2026-07-28 arm A1): jseval --clean left a PARTIAL index (4321 docs present, emb
`kind: follow-up?` `anchor: none` `seen: 1` `first: 2026-07-28` `last: 2026-07-28`
- [ ] Eval backfill wedge (2026-07-28 arm A1): jseval --clean left a PARTIAL index (4321 docs present, embedding fingerprint missing) -> worker entered BLOCKED_LEGACY auto-rebuild recovery and the backfill scheduler never cycled (zero Combined-backfill lines, splade 0%, chunks stalled 41%, 32min). Two candidate defects: (a) --clean partial-wipe not fail-closed when files locked; (b) legacy-rebuild recovery path can wedge the scheduler (734-family finalize class). Wedged log banked at tmp/781-certification/wedge-A1-worker.log (2026-07-28)

### obs:unanchored-gate-red-8 — CORRECTION to the A1-wedge note above: BLOCKED_LEGACY is NOT a wedge signature — it appears transien
`kind: follow-up?` `anchor: none` `seen: 1` `first: 2026-07-28` `last: 2026-07-28`
- [ ] CORRECTION to the A1-wedge note above: BLOCKED_LEGACY is NOT a wedge signature — it appears transiently on every fresh ingest (yesterday's healthy baseline log has it 2x; recovery stamps the fingerprint and proceeds). The run-1 wedge evidence is solely: zero Combined-backfill lines + splade 0% + chunks 41% static for 32min post-embed. Run-2 on a clean dir is healthy (GPU 83%, splade progressing at 30s), so the wedge is a one-off race in the recovery-finalize path, not a merge regression. Candidate defect narrows to: recovery-finalize can fail to release the backfill scheduler when recovery engages on a PARTIALLY-cleaned index (fingerprint file missing but index populated) (2026-07-28)

### obs:runtimespecstore — AI autostart silently no-ops under eval mode: InferenceWiring.seedAutostartSpec logs 'seeded runtime
`kind: defect?` `anchor: modules/app-services/src/main/java/io/justsearch/app/services/runtimestate/RuntimeSpecStore.java` `seen: 1` `first: 2026-07-28` `last: 2026-07-28`
- [ ] AI autostart silently no-ops under eval mode: InferenceWiring.seedAutostartSpec logs 'seeded runtime spec chatEnabled=true' but RuntimeSpecStore.setChatEnabled writes into the IN_MEMORY read-only settings store (POST /api/settings/v2 -> 409 SETTINGS_READ_ONLY), so RuntimeReconciler computes effective=false and never spawns llama-server; jseval start_backend(llm=True) then always fails 'inference stayed offline' under runHeadlessEval — `modules/app-services/src/main/java/io/justsearch/app/services/runtimestate/RuntimeSpecStore.java:61-70` (2026-07-28)

### obs:corpus-leak — Corpus leak instruments are ASCII/English-only: `_TOKEN_RE=[a-z0-9']+` splits German umlauts and `_S
`kind: defect?` `anchor: scripts/jseval/jseval/corpus_leak.py` `seen: 1` `first: 2026-07-29` `last: 2026-07-29`
- [ ] Corpus leak instruments are ASCII/English-only: `_TOKEN_RE=[a-z0-9']+` splits German umlauts and `_STOPWORDS` holds only English function words, so `query_overlap_report`/`ngram_selectivity_report` mis-measure any non-English member (tempdoc 748 Phase 0) — `scripts/jseval/jseval/corpus_leak.py:60-91` (2026-07-29)

### obs:corpus-generate-error — tempdoc 748: rebuilt de-miracl gold still carries a single-token grep anchor — 'Standort' has gold c
`kind: follow-up?` `anchor: scripts/jseval/jseval/corpus_generate.py` `seen: 1` `first: 2026-07-29` `last: 2026-07-29`
- [ ] tempdoc 748: rebuilt de-miracl gold still carries a single-token grep anchor — 'Standort' has gold coverage 0.500 vs native 0.007 in every cell (Youden J 0.493), because the German semantic-descriptor frame is `Standort: {type}, {place}.` in body AND title while German-Wikipedia natives almost never use the word. Invisible to every gated instrument: ngram_selectivity is n=5, rare_token_leak's df_floor=5 is far below the token's df. Same class as 776 §H (instrument gap). Candidate fix: render the DE descriptor bare (drop the literal 'Standort'), mirroring EN's 'The {type} in the {place}' whose frame word is ubiquitous — `scripts/jseval/jseval/corpus_generate.py:551` (2026-07-29)

### obs:hybridfusionutils — wrong-gate defect: justsearch.splade.zero_weight_min_tokens is one constant read by BOTH spladeParen
`kind: defect?` `anchor: modules/adapters-lucene/src/main/java/io/justsearch/adapters/lucene/runtime/HybridFusionUtils.java` `seen: 1` `first: 2026-07-29` `last: 2026-07-29`
- [ ] wrong-gate defect: justsearch.splade.zero_weight_min_tokens is one constant read by BOTH spladeParentLengthMultiplier (Stage 3A leg) and chunkBranchParentLengthMultiplier (Stage 3B whole-vs-chunk branch ramp) — a SPLADE-named knob silently retunes branch balance ~4x; needs separate bounds defaulting to 1024/4096 — `modules/adapters-lucene/src/main/java/io/justsearch/adapters/lucene/runtime/HybridFusionUtils.java:26-27,826-834` (2026-07-29)

### obs:readpathops — Multi-valued keyword fields reach the wire with two different joiners depending on read path — store
`kind: defect?` `anchor: modules/adapters-lucene/src/main/java/io/justsearch/adapters/lucene/runtime/ReadPathOps.java` `seen: 1` `first: 2026-07-29` `last: 2026-07-29`
- [ ] Multi-valued keyword fields reach the wire with two different joiners depending on read path — stored-fields merge with ' | ' while the DocValues projection joins with ', ' — so any consumer splitting a multi-value field must guess, and ', ' is ambiguous with commas inside entity values (14/50 legal bridge entities are 'Name, ST' shaped) — `modules/adapters-lucene/src/main/java/io/justsearch/adapters/lucene/runtime/ReadPathOps.java:184` vs `SearchResultFormatter.java:63` (2026-07-29)

### obs:0018-vlm-pdf-extraction-via-chat-model — JUSTSEARCH_LAYOUT_ENABLED is documented as the VDU/VLM enable flag but zero code reads it — grep of
`kind: defect?` `anchor: docs/decisions/0018-vlm-pdf-extraction-via-chat-model.md` `seen: 1` `first: 2026-07-29` `last: 2026-07-29`
- [ ] JUSTSEARCH_LAYOUT_ENABLED is documented as the VDU/VLM enable flag but zero code reads it — grep of modules/ returns no hits; the real gating is capability-based (mmproj present + VRAM + LLM online) — `docs/decisions/0018-vlm-pdf-extraction-via-chat-model.md:26` (2026-07-29)

### obs:inferenceconfig — Setting llm.modelPath via POST /api/settings/v2 silently disables the VDU vision tier: usingLlmModel
`kind: defect?` `anchor: modules/app-inference/src/main/java/io/justsearch/app/inference/InferenceConfig.java` `seen: 1` `first: 2026-07-29` `last: 2026-07-29`
- [ ] Setting llm.modelPath via POST /api/settings/v2 silently disables the VDU vision tier: usingLlmModelOverride && !MMPROJ_MODEL.isSet() nulls mmprojPath, so VDU blocks on vdu.missing_mmproj with no user-facing explanation — `modules/app-inference/src/main/java/io/justsearch/app/inference/InferenceConfig.java:159-170` (2026-07-29)

### obs:api-contract-map — POST /api/knowledge/search doc_ids scoping does not restrict the result set (measured: 2 doc_ids ret
`kind: defect?` `anchor: docs/reference/api-contract-map.md` `seen: 2` `first: 2026-07-29` `last: 2026-08-03`
- [ ] POST /api/knowledge/search doc_ids scoping does not restrict the result set (measured: 2 doc_ids returned 20 unrelated docs, totalHits 78), contradicting the contract's 'TermInSetQuery on PATH — scopes search to specific documents' — `docs/reference/api-contract-map.md:424` (2026-07-29)
- [ ] Pre-existing mojibake in docs/reference/api-contract-map.md: literal bytes "â€”" (double-encoded em-dash) appear in the Indexing Excludes API section instead of a proper em-dash — `docs/reference/api-contract-map.md:581,591` (2026-08-03)

### obs:chunk-completeness — chunk_completeness guard is blind on a `raw_files` corpus — it computes `expected` from corpus.jsonl
`kind: defect?` `anchor: scripts/jseval/jseval/chunk_completeness.py` `seen: 1` `first: 2026-07-29` `last: 2026-07-29`
- [ ] chunk_completeness guard is blind on a `raw_files` corpus — it computes `expected` from corpus.jsonl, which such a corpus does not have, so mixed/ohr-bench-pdf-live reported `expected: 0, observed: 3144, verdict: chunk-free` against an index with 3144 real chunk documents — `scripts/jseval/jseval/chunk_completeness.py` (2026-07-29)

### obs:judge-overlay — Reconcile: stats-analysis worker (tmp/hero-arc-analysis/stats) reported validating 120/120 vs commit
`kind: defect?` `anchor: judge-overlay.json` `seen: 1` `first: 2026-07-28` `last: 2026-07-28`
- [ ] Reconcile: stats-analysis worker (tmp/hero-arc-analysis/stats) reported validating 120/120 vs committed records using RAW substring EM via read_inspect_observations WITHOUT overlay, but PR #322 established finalize_logs auto-applies judge-overlay.json and 5 w2 cells flip (legal A|q5 x3, B|0|q8; enron B|2|q1) — both claims cannot be exactly true; re-check which reader the matrix actually used before trusting per-cell em_correct at the flipped cells (2026-07-28)

### obs:unanchored-general-94 — Hard Invariant #1 enforcement claim vs lockfile fact: tier-register row 1 states 'Lucene types are n
`kind: defect?` `anchor: none` `seen: 1` `first: 2026-07-28` `last: 2026-07-28`
- [ ] Hard Invariant #1 enforcement claim vs lockfile fact: tier-register row 1 states 'Lucene types are not on Head's classpath', but lucene-core:10.4.0 is on modules/ui's compileClasspath AND runtimeClasspath — `modules/ui/gradle.lockfile`. ArchUnit may still forbid Head *imports* (the real invariant), but the register's stated mechanism is inaccurate as written. Found during tempdoc 792 R6 derisking. (2026-07-28)

### obs:check-tempdoc-status-staleness — 787 status flipped to MERGED-with-retained-bracket is still flagged STALE by the staleness linter —
`kind: defect?` `anchor: scripts/ci/check-tempdoc-status-staleness.mjs` `seen: 1` `first: 2026-07-29` `last: 2026-07-29`
- [ ] 787 status flipped to MERGED-with-retained-bracket is still flagged STALE by the staleness linter — FP class (a) (bracket text not excluded from marker scan) is still unfixed, so the convention the linter's own report recommends produces a new FP each time it is applied — `scripts/ci/check-tempdoc-status-staleness.mjs:37` (2026-07-29)

### obs:plant-defects — sandbox test suite: test_plant_defects fails to import on a bare system Python (needs Pillow); 209/2
`kind: environment?` `anchor: scripts/sandbox/plant_defects.py` `seen: 1` `first: 2026-07-31` `last: 2026-07-31`
- [ ] sandbox test suite: test_plant_defects fails to import on a bare system Python (needs Pillow); 209/210 pass otherwise — `scripts/sandbox/plant_defects.py` (2026-07-31)

### obs:unanchored-drift-21 — Head and Worker classpaths ship 4 same-library-different-version jar pairs (jackson-core/databind 3.
`kind: defect?` `anchor: none` `seen: 1` `first: 2026-07-22` `last: 2026-07-22`
- [ ] Head and Worker classpaths ship 4 same-library-different-version jar pairs (jackson-core/databind 3.1.0 vs 2.20.0, kotlin-stdlib 2.4.0 vs 2.2.21, commons-text 1.14.0 vs 1.15.0) — cross-process version drift, found during 772 installer itemization of `lib/` vs `lib/worker/` (CI artifact run 29874382035) (2026-07-22)

### obs:unanchored-general-95 — Installer itemization (772 §I) shows lucene-core/lucene-analysis-common on the HEAD shipped classpat
`kind: defect?` `anchor: none` `seen: 1` `first: 2026-07-22` `last: 2026-07-22`
- [ ] Installer itemization (772 §I) shows lucene-core/lucene-analysis-common on the HEAD shipped classpath (lib/, not lib/worker/) — modules/ui/build.gradle.kts:46 depends on adapters-lucene, :124-125 runtimeOnly lucene — tier-register row 1's justification wording ('Lucene types are not on Head's classpath') doesn't match the shipped artifact; reconcile wording vs reality (the ArchUnit test presumably checks something narrower) (2026-07-22)

### obs:unanchored-general-96 — justsearch-releases THIRD_PARTY_NOTICES.txt has no NVIDIA entry for the CUDA/cuDNN redistributables
`kind: environment?` `anchor: none` `seen: 1` `first: 2026-07-22` `last: 2026-07-22`
- [ ] justsearch-releases THIRD_PARTY_NOTICES.txt has no NVIDIA entry for the CUDA/cuDNN redistributables in ort-cuda-runtime-12.4.zip / cudnn-9-runtime-12.4.zip (NVIDIA redistributable-list EULA terms) — pre-existing gap noticed while adding the ONNX Runtime MIT notice (772 §J) (2026-07-22)

### obs:tier-register — Ported skill copy has a bad find-replace: `.agents/skills/governance/SKILL.md` says `.Codex/rules/ti
`kind: defect?` `anchor: .Codex/rules/tier-register.md` `seen: 2` `first: 2026-07-30` `last: 2026-08-06`
- [ ] docsApiDriftCheck fails pre-existing: tier-register row 3 quotes the banned endpoints as literal examples and the checker has no allow-marker for a rule that must name them — `docs/reference/contributing/tier-register.md:40` (2026-08-06)

### obs:release-v1-schema — release.v1.schema.json lags compose(): `union_recall` has been emitted since tempdoc 701 but is not
`kind: defect?` `anchor: scripts/jseval/release.v1.schema.json` `seen: 1` `first: 2026-07-31` `last: 2026-07-31`
- [ ] release.v1.schema.json lags compose(): `union_recall` has been emitted since tempdoc 701 but is not declared as a schema property (additionalProperties:true hides it) — `scripts/jseval/release.v1.schema.json` (tempdoc 802) (2026-07-31)

### obs:test-release — `scripts/jseval/tests/` (132 pytest files) is invoked NOWHERE in CI — same orphaned-layer class as 7
`kind: defect?` `anchor: test_release.py` `seen: 1` `first: 2026-07-31` `last: 2026-07-31`
- [ ] `scripts/jseval/tests/` (132 pytest files) is invoked NOWHERE in CI — same orphaned-layer class as 745 D6 (node instrument) and 799's CI-lint tier. 802 wired only test_release.py; the rest has never run under CI's locked env, so a wholesale enable needs its own triage pass — `.github/workflows/ci.yml` (2026-07-31)

### obs:read-corpus — INCIDENT: a research subagent of this session deleted untracked files from the MAIN worktree in viol
`kind: defect?` `anchor: read_corpus.py` `seen: 1` `first: 2026-07-30` `last: 2026-07-30`
- [ ] INCIDENT: a research subagent of this session deleted untracked files from the MAIN worktree in violation of never-delete-untracked-in-main: explore_corpus.{mjs,py}, read_corpus.py, search_corpus.{ps1,sh}, test_exploration.txt, tmp-rules.patch (unrecoverable — untracked). They may have belonged to the concurrent justsearch-public-c2 session. Subagents don't inherit branch-safety hooks; read-only briefs need an explicit no-delete clause. (2026-07-30)

### obs:unanchored-error-12 — IsolatedBackendFixture integration tier is red on main: :modules:system-tests integrationTest classp
`kind: environment?` `anchor: none` `seen: 1` `first: 2026-07-30` `last: 2026-07-30`
- [ ] IsolatedBackendFixture integration tier is red on main: :modules:system-tests integrationTest classpath has no project(":modules:ui"), so the spawned child JVM inherits a classpath without HeadlessApp — 5 E2E classes fail at @BeforeAll with ClassNotFoundException: io.justsearch.ui.HeadlessApp — `modules/system-tests/build.gradle.kts:157` (2026-07-30)

### obs:workflow-signal-policy-v1 — check-branch-protection reports FAIL on main: required status check 'Windows-native tests' is in wor
`kind: environment?` `anchor: scripts/ci/workflow-signal-policy.v1.json` `seen: 2` `first: 2026-07-30` `last: 2026-08-14`
- [ ] check-branch-protection reports FAIL on main: required status check 'Windows-native tests' is in workflow-signal-policy.v1.json requiredStatusChecks but not configured in GitHub branch protection — `scripts/ci/workflow-signal-policy.v1.json:23` (2026-07-30)
- [ ] workflow-signal-policy.v1.json declares 9 CI requiredStatusChecks but live branch protection requires only 6 CI contexts — 'Windows-native tests' and 'Shell crate tests (Rust)' are declared-but-not-required, so check-branch-protection reports drift; reconcile the policy or the protection (surfaced during merge-queue prep PR #463, 2026-08-14) — `scripts/ci/workflow-signal-policy.v1.json` (2026-08-14)

### obs:unanchored-general-97 — ci-walltime 'needs' omits windows-native-tests, so the wall-clock critical-path attribution can miss
`kind: defect?` `anchor: none` `seen: 1` `first: 2026-07-30` `last: 2026-07-30`
- [ ] ci-walltime 'needs' omits windows-native-tests, so the wall-clock critical-path attribution can miss that lane's timing — `.github/workflows/ci.yml:452` (2026-07-30)

### obs:bgem3backfillops — BgeM3BackfillOps blank-content branch: `isChunk` is always false there (isChunk implies non-blank ch
`kind: defect?` `anchor: modules/worker-services/src/main/java/io/justsearch/indexerworker/loop/ops/BgeM3BackfillOps.java` `seen: 1` `first: 2026-07-30` `last: 2026-07-30`
- [ ] BgeM3BackfillOps blank-content branch: `isChunk` is always false there (isChunk implies non-blank chunkContent, which becomes `content`), so the CHUNK_EMBEDDING_STATUS arm is unreachable — kept for symmetry with the parent path — `modules/worker-services/src/main/java/io/justsearch/indexerworker/loop/ops/BgeM3BackfillOps.java:106` (2026-07-30)

### obs:workersignalbus — WorkerSignalBus javadoc lists a MockWorkerSignalBus testFixtures impl that does not exist anywhere i
`kind: defect?` `anchor: modules/worker-core/src/main/java/io/justsearch/indexerworker/coordination/WorkerSignalBus.java` `seen: 1` `first: 2026-07-30` `last: 2026-07-30`
- [ ] WorkerSignalBus javadoc lists a MockWorkerSignalBus testFixtures impl that does not exist anywhere in the repo (stale reference) — `modules/worker-core/src/main/java/io/justsearch/indexerworker/coordination/WorkerSignalBus.java:21` (2026-07-30)

### obs:sqlitequeueswitchbufferops — JobQueue.jobStateCounts() (SqliteQueueSwitchBufferOps.stateCounts) is a full table scan of jobs incl
`kind: defect?` `anchor: modules/indexer-worker/src/main/java/io/justsearch/indexerworker/queue/SqliteQueueSwitchBufferOps.java` `seen: 1` `first: 2026-07-30` `last: 2026-07-30`
- [ ] JobQueue.jobStateCounts() (SqliteQueueSwitchBufferOps.stateCounts) is a full table scan of jobs incl. DONE/FAILED under the queue lock; queueDepth() is the only index-served aggregate — status surfaces polling jobStateCounts will degrade as the ledger grows — `modules/indexer-worker/src/main/java/io/justsearch/indexerworker/queue/SqliteQueueSwitchBufferOps.java:70` (2026-07-30)

### obs:unanchored-drift-22 — checked-in gradle.lockfiles are stale vs a full regen: resolveAndLockAll --write-locks adds 'org.jet
`kind: defect?` `anchor: none` `seen: 1` `first: 2026-07-30` `last: 2026-07-30`
- [ ] checked-in gradle.lockfiles are stale vs a full regen: resolveAndLockAll --write-locks adds 'org.jetbrains.kotlin:kotlin-metadata-jvm:2.3.0=dependencyAnalysisKotlinMetadataClasspath' to all 34 lockfiles — `modules/core/gradle.lockfile` (2026-07-30)

### obs:goldencorpusintegrationtest — PRE-EXISTING (verified 2026-07-30, not caused by 798): GoldenCorpusIntegrationTest 'Cross-encoder re
`kind: environment?` `anchor: GoldenCorpusIntegrationTest` `seen: 1` `first: 2026-07-30` `last: 2026-07-30`
- [ ] PRE-EXISTING (verified 2026-07-30, not caused by 798): GoldenCorpusIntegrationTest 'Cross-encoder reranker does not degrade TEXT mode Recall@3' fails with 'ConfigStore not initialized — call setGlobal() at startup'. Reproduced with the 798 system-tests classpath change reverted, so it predates that work. Went unnoticed because NO CI lane runs :modules:system-tests:integrationTest (ci.yml:302 runs only :test; check depends on test only) — the same blind spot that let the whole IsolatedBackendFixture tier rot to ClassNotFoundException: HeadlessApp. (2026-07-30)

### obs:batchupdateintegrationtest — BatchUpdateIntegrationTest writes splade_status=COMPLETED with no splade artifact and only passes be
`kind: defect?` `anchor: modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/BatchUpdateIntegrationTest.java` `seen: 1` `first: 2026-07-30` `last: 2026-07-30`
- [ ] BatchUpdateIntegrationTest writes splade_status=COMPLETED with no splade artifact and only passes because the test runtime defaults to ValidationMode.WARN — under FAIL these fixtures would trip the 798 status/artifact contract — `modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/BatchUpdateIntegrationTest.java:54` (2026-07-30)

### obs:fieldmapper — FieldMapper.addFields 'splade' case does an unchecked cast to Map<String,Float> and would ClassCastE
`kind: defect?` `anchor: modules/adapters-lucene/src/main/java/io/justsearch/adapters/lucene/runtime/FieldMapper.java` `seen: 1` `first: 2026-07-30` `last: 2026-07-30`
- [ ] FieldMapper.addFields 'splade' case does an unchecked cast to Map<String,Float> and would ClassCastException on a non-Map value instead of failing validation — `modules/adapters-lucene/src/main/java/io/justsearch/adapters/lucene/runtime/FieldMapper.java:431` (2026-07-30)

### obs:spladebackfillops — SpladeBackfillOps/CombinedEnrichmentBackfillOps put encodeBatch elements into the SPLADE update map
`kind: defect?` `anchor: modules/worker-services/src/main/java/io/justsearch/indexerworker/loop/ops/SpladeBackfillOps.java` `seen: 1` `first: 2026-07-30` `last: 2026-07-30`
- [ ] SpladeBackfillOps/CombinedEnrichmentBackfillOps put encodeBatch elements into the SPLADE update map with no per-element null guard — same class as the 798-F5 BgeM3 fix, which now omits the key when null — `modules/worker-services/src/main/java/io/justsearch/indexerworker/loop/ops/SpladeBackfillOps.java:198` (2026-07-30)

### obs:unifiedchatrequest — Layout trade-off from the CONVERSATION_ZONES reading-column track floor: at wide viewports below ~70
`kind: defect?` `anchor: modules/ui-web/src/shell-v0/views/unifiedChatRequest.ts` `seen: 2` `first: 2026-07-30` `last: 2026-07-31`
- [ ] Layout trade-off from the CONVERSATION_ZONES reading-column track floor: at wide viewports below ~70rem the three zone floors (24rem conversation + 15rem rail + 24rem pane) plus 5 grid gaps no longer fit, so .conversation-zone overflows its container horizontally instead of starving the reading column — measured 15px of overflow at 1050px with all three zones mounted. Considered acceptable vs. the 102px column it replaces, but the real cure is a wider breakpoint for mounting the document pane — `modules/ui-web/src/shell-v0/views/unifiedChatRequest.ts:143` (2026-07-30)
- [ ] Chat zone still overflows in agent mode when the evidence rail AND reading pane are both mounted: floors 24rem + 15rem + 24rem + 5x1.5rem gutters = 70.5rem, above the 64rem container threshold the wide layout commits at (up to 104px). 798 round 8 fixed the two-zone case structurally via a container query; closing this needs a mount threshold for whichever zone yields — `modules/ui-web/src/shell-v0/views/unifiedChatRequest.ts:146` (2026-07-31)

### obs:overlayhost — The OverlayHost .top-right slot's total DOWNWARD height is still unbounded: three independent compon
`kind: defect?` `anchor: modules/ui-web/src/shell-v0/chrome/OverlayHost.ts` `seen: 1` `first: 2026-07-30` `last: 2026-07-30`
- [ ] The OverlayHost .top-right slot's total DOWNWARD height is still unbounded: three independent components share it (jf-provenance-badge, jf-advisory-toast-host, jf-plugin-error-overlay), each unaware of the others' height, and the slot has no max-height/scroll. Capping the toast host at 3 bounds only one contributor — `modules/ui-web/src/shell-v0/chrome/OverlayHost.ts:57` (2026-07-30)

### obs:packs — packs.ts AiInstallStatus still models the retired per-asset wire shape (assets: AiInstallAssetStatus
`kind: defect?` `anchor: modules/ui-web/src/api/domains/packs.ts` `seen: 1` `first: 2026-07-30` `last: 2026-07-30`
- [ ] packs.ts AiInstallStatus still models the retired per-asset wire shape (assets: AiInstallAssetStatus[]) while /api/ai/install/status returns packages[] (AiInstallStatus.PackageStatus) — same staleness class as the manifest types just rewritten; getAiInstallStatus/startAiInstall/cancelAiInstall/repairAiInstall all return it and have no FE callers — `modules/ui-web/src/api/domains/packs.ts:76-104` (2026-07-30)

### obs:check-ui-step-coverage — check-ui-step-coverage.mjs and check-layout-purity.mjs both document themselves as wired into ci.yml
`kind: defect?` `anchor: scripts/ci/check-ui-step-coverage.mjs` `seen: 1` `first: 2026-07-31` `last: 2026-07-31`
- [ ] check-ui-step-coverage.mjs and check-layout-purity.mjs both document themselves as wired into ci.yml but appear in no workflow (799 class-1: assertion channel whose docs assert an evaluator it lacks) — `scripts/ci/check-ui-step-coverage.mjs:26` (2026-07-31)

### obs:unanchored-general-98 — mixed/legal-clerc-200 retrieval changed SYSTEMATICALLY between two sessions at different commits, sa
`kind: defect?` `anchor: none` `seen: 1`
- [ ] mixed/legal-clerc-200 retrieval changed SYSTEMATICALLY between two sessions at different commits, same corpus signature (`90d4300d…`, 198 docs): 802-session runs (git f3f6909e) vs 803-session runs (git 40841ad9) agree on only 6-7/200 top-10 doc SETS (Jaccard 0.54), while the three 803 runs agree with EACH OTHER at Jaccard 0.964-0.977 and cluster at nDCG 0.5783/0.5811/0.5800. So this is NOT run-to-run nondeterminism (an earlier version of this note said it was, from a 2-run comparison that confounded session with commit) — something between those commits moved legal retrieval a lot while enron/scifact/miracl reproduced to 0.0001 across the same gap. Cause unidentified; #350 is the large change in that window but is app-update work with no obvious retrieval path — `datasets/mixed/legal-clerc-200` (tempdoc 803)

### obs:readiness — jseval `--pipeline` does not always guarantee SPLADE readiness: a miracl-fr-2k run at the same commi
`kind: environment?` `anchor: scripts/jseval/jseval/readiness.py` `seen: 2` `first: 2026-07-31` `last: 2026-07-31`
- [ ] jseval `--pipeline` does not always guarantee SPLADE readiness: a miracl-fr-2k run at the same commit returned `comparable=false / readiness_failed: splade_requested_but_splade_features_not_ready` on all four modes, while an earlier run of the same corpus was comparable=true. Flaky readiness gate — a run that reaches eval with incomplete enrichment silently produces incomparable numbers — `scripts/jseval/jseval/readiness.py` (tempdoc 803) (2026-07-31)
- [ ] miracl-fr-2k SPLADE enrichment fails on exactly 1 doc of 5408 via the EVAL ingest path (runHeadlessEval + syncDirectory) — reproduced 3/3 — but 0 failures ingesting the identical corpus through the dev-stack API path. Not corpus content, not the gate. One FAILED doc trips readiness.py's zero-tolerance `spladeFailedCount > 0` clause at 99.98% coverage (dense by contrast allows 0.1% missing). Likely surfaced rather than caused by #339 — `scripts/jseval/jseval/readiness.py:474` (tempdoc 803) (2026-07-31)

### obs:unanchored-flake-6 — Integration tier test 'Consent Capsule Recovery E2E (tempdoc 550 Slice A1) > initializationError' is
`kind: environment?` `anchor: none` `seen: 1` `first: 2026-07-31` `last: 2026-07-31`
- [ ] Integration tier test 'Consent Capsule Recovery E2E (tempdoc 550 Slice A1) > initializationError' is FLAKY on the Windows CI runner: failed on PR #354 run 30647641329, passed on re-run 30648104856 with the same branch content, and passed on main at the same base. initializationError = class setup failure, consistent with port/resource contention rather than a logic break — `modules/system-tests` (tempdoc 803) (2026-07-31)

### obs:threat-model — threat-model.md has zero mention of the in-app updater: 617 added network egress (release-descriptor
`kind: defect?` `anchor: docs/reference/security/threat-model.md` `seen: 1` `first: 2026-07-31` `last: 2026-07-31`
- [ ] threat-model.md has zero mention of the in-app updater: 617 added network egress (release-descriptor fetch + artifact download) to a 'nothing leaves your machine' product without reconciling the threat model — surfaced by the consult hint while fixing F1; needs 617-owner reconciliation — `docs/reference/security/threat-model.md` (2026-07-31)

### obs:unanchored-error-14 — collect-evidence.ps1's GET-ladder error path reads `$_.Exception.Response.GetResponseStream()`, whic
`kind: environment?` `anchor: none` `seen: 1` `first: 2026-08-03` `last: 2026-08-03`
- [ ] collect-evidence.ps1's GET-ladder error path reads `$_.Exception.Response.GetResponseStream()`, which PS 5.1 has already drained into `$_.ErrorDetails.Message` -- so error bodies for failed GET rungs are recorded as empty (fixed only in the new Invoke-ApiRequest helper) -- `scripts/sandbox/collect-evidence.ps1:412` (2026-08-03)

### obs:effectiveconfigintegrationtest — EffectiveConfigIntegrationTest.java and EffectiveConfigRuntimeIntegrationTest.java are 1-byte empty
`kind: defect?` `anchor: modules/ui/src/integrationTest/java/io/justsearch/ui/api/EffectiveConfigIntegrationTest.java` `seen: 1` `first: 2026-08-03` `last: 2026-08-03`
- [ ] EffectiveConfigIntegrationTest.java and EffectiveConfigRuntimeIntegrationTest.java are 1-byte empty files — dead stubs that silently contribute zero coverage — `modules/ui/src/integrationTest/java/io/justsearch/ui/api/EffectiveConfigIntegrationTest.java:1` (2026-08-03)

### obs:fields-v1 — index_schema_fp is a content hash of fields.v1.json, so annotation-only catalog edits flip schema_mi
`kind: follow-up?` `anchor: fields.v1.json` `seen: 1` `first: 2026-08-03` `last: 2026-08-03`
- [ ] index_schema_fp is a content hash of fields.v1.json, so annotation-only catalog edits flip schema_mismatch with zero physical consequence; a physical-schema fingerprint (or per-field consequence classes) would make the advisory truthful — docs corrected to reality in 804 W4, enforcement decision deferred — `adapters-lucene SsotCommitMetadataSource.java:81` (2026-08-03)

### obs:manifest — packaging/mcpb/manifest.json version is stuck at 0.1.0 while the release + server.json are 0.2.0 — s
`kind: defect?` `anchor: packaging/mcpb/manifest.json` `seen: 1` `first: 2026-08-03` `last: 2026-08-03`
- [ ] packaging/mcpb/manifest.json version is stuck at 0.1.0 while the release + server.json are 0.2.0 — sync-version.ps1 syncs tauri.conf.json/package.json/Cargo.toml/server.json but never the MCPB bundle manifest (same version-truth class as round-10 F12) — `packaging/mcpb/manifest.json:6` (2026-08-03)

### obs:golden-parity-v2-dev — finding-5 measurement result (session e4497288, 2026-07-30): dev-vs-dev golden parity is BIT-IDENTIC
`kind: environment?` `anchor: golden-parity-v2-dev.json` `seen: 1` `first: 2026-07-30` `last: 2026-07-30`
- [ ] finding-5 measurement result (session e4497288, 2026-07-30): dev-vs-dev golden parity is BIT-IDENTICAL (dense delta 0.000e+00, identical order, 2 runs same stack) while dev-vs-sandbox shows 1.7e-2..6.8e-2 on all 10 queries vs a 2.0e-4 envelope. All 3 failures are kind:semantic; all 6 keyword pass. 'golden #1 in top-3' passes on ALL 10 including the 3 failures. Baseline calibration block itself states overlap floors were sampled only on the same-machine population. Fresh v2 baseline at tmp/finding5/golden-parity-v2-dev.json (uncommitted). (2026-07-30)

### obs:check-public-agent-utility — check-public-agent-utility.mjs fails locally with 'No module named click' (python env lacks jseval d
`kind: defect?` `anchor: scripts/ci/check-public-agent-utility.mjs` `seen: 1` `first: 2026-07-30` `last: 2026-07-30`
- [ ] check-public-agent-utility.mjs fails locally with 'No module named click' (python env lacks jseval deps) — the offline-replay delegation makes the gate unrunnable on a dev box without the jseval venv; PYTHONPATH remedy doesn't help since deps are missing, not the module path — `scripts/ci/check-public-agent-utility.mjs` (2026-07-30)

### obs:field-catalog-schema — fields.v1.json schema permits 4 per-field keys that NO consumer reads: unique, facet, indexOptions,
`kind: defect?` `anchor: SSOT/schemas/indexing/field-catalog.schema.json` `seen: 1` `first: 2026-07-30` `last: 2026-07-30`
- [ ] fields.v1.json schema permits 4 per-field keys that NO consumer reads: unique, facet, indexOptions, omitNorms — both parse paths (FieldCatalogDef @JsonCreator and FieldMapper.loadCatalogTree) drop them silently; also two independent parse paths exist for the same catalog, so a new key must be threaded through BOTH — `SSOT/schemas/indexing/field-catalog.schema.json:21-51` (2026-07-30)

### obs:unanchored-flake-7 — FLAKE in the newly-wired integration CI lane (2026-07-31): 'Indexing -> action-ledger projection coh
`kind: environment?` `anchor: none` `seen: 1` `first: 2026-07-31` `last: 2026-07-31`
- [ ] FLAKE in the newly-wired integration CI lane (2026-07-31): 'Indexing -> action-ledger projection coherence (tempdoc 550 Thesis III) > initializationError' FAILED then PASSED on retry within the same run (job 91035561746) — IsolatedBackendFixture spawn is timing-sensitive under CI load. Lane is advisory (continue-on-error) so it did not block, but a flaky fixture spawn will erode trust in the lane that was just added to stop the tier rotting. Tier is otherwise 80 tests / 1 failed / 42 skipped, down from 5 hard failures pre-#339/#342. (2026-07-31)

### obs:ui-step-index — ui_step_index.json's UnifiedChatView.ts array is missing the pre-existing `chat-occlusion` step (onl
`kind: environment?` `anchor: scripts/jseval/jseval/ui_step_index.json` `seen: 1` `first: 2026-08-06` `last: 2026-08-06`
- [ ] ui_step_index.json's UnifiedChatView.ts array is missing the pre-existing `chat-occlusion` step (only chat-mode/qa-response/chat-proportion were listed) — pre-existing gap, not introduced by W4 — `scripts/jseval/jseval/ui_step_index.json:20-23` (2026-08-06)

### obs:unanchored-red-test-2 — ui-web full unit suite prints repeated unhandled AggregateError/ECONNREFUSED 127.0.0.1:3000 stderr n
`kind: defect?` `anchor: none` `seen: 1` `first: 2026-08-06` `last: 2026-08-06`
- [ ] ui-web full unit suite prints repeated unhandled AggregateError/ECONNREFUSED 127.0.0.1:3000 stderr noise (a test dials a dev server that is not running); suite is green, so the failure is swallowed rather than asserted — `modules/ui-web` full `npm run test:unit:run` (2026-08-06)

### obs:unifiedchatstyles — CLOSED by 814 W3 (render gated on `evidenceRailMounted()`; the container-query hide rule deleted). 8
`kind: follow-up?` `anchor: modules/ui-web/src/shell-v0/views/unifiedChatStyles.ts` `seen: 2` `first: 2026-08-06` `last: 2026-08-06`
- [x] CLOSED by 814 W3 (render gated on `evidenceRailMounted()`; the container-query hide rule deleted). 814 D5: at wide the toolbar 'Sources · N' chip was only CSS-hidden (display:none), so it remained a third TEXT-level render of the source-count fact — a text/phrase-based status-fact probe (814 D7.3) would flag it even though geometry says it is not painted; consider gating the render on evidenceRailMounted() instead — `modules/ui-web/src/shell-v0/views/unifiedChatStyles.ts:928` (2026-08-06)
- [ ] Visual pass 814: (a) degradation pill's collapse chevron floats slightly detached at the banner's right edge next to the remedy button — alignment polish candidate; (b) landing escalation strip renders three affordance treatments in one row (plain text active rung, dimmed text, boxed button) and omits Structured in retrieve-landing — pre-existing, reads ragged — `modules/ui-web/src/shell-v0/views/unifiedChatStyles.ts` (2026-08-06)

### obs:status-facts-v1 — 814 D7.3: the status-facts register's 'Reduced capability' phrase is the INFO-severity verdict headl
`kind: defect?` `anchor: governance/status-facts.v1.json` `seen: 1` `first: 2026-08-06` `last: 2026-08-06`
- [ ] 814 D7.3: the status-facts register's 'Reduced capability' phrase is the INFO-severity verdict headline, which post-finding-9 renders no banner — so on the chat-bands fixture (degraded/warn -> 'Service degraded') the singleton probe measures count 0 and can never witness the duplication D5 fixes; the register needs the warn/error headline too, or a fixture variant that mints an info verdict — `governance/status-facts.v1.json:8` (2026-08-06)

### obs:searchpersourceexecutor — SearchPerSourceExecutor's insufficient-results backfill max()es totalHits/matchCount against a respo
`kind: defect?` `anchor: modules/app-services/src/main/java/io/justsearch/app/services/worker/SearchPerSourceExecutor.java` `seen: 1` `first: 2026-08-06` `last: 2026-08-06`
- [ ] SearchPerSourceExecutor's insufficient-results backfill max()es totalHits/matchCount against a response from a DIFFERENT (source-unfiltered) query, so a merged multi-source count can exceed the per-source populations it merged — `modules/app-services/src/main/java/io/justsearch/app/services/worker/SearchPerSourceExecutor.java:134` (2026-08-06)

### obs:knowledgesearchrequest — 'collection' is accepted+echoed on POST/GET /api/indexing/roots but is not reachable as a search fil
`kind: defect?` `anchor: modules/app-api/src/main/java/io/justsearch/app/api/knowledge/KnowledgeSearchRequest.java` `seen: 1` `first: 2026-08-06` `last: 2026-08-06`
- [ ] 'collection' is accepted+echoed on POST/GET /api/indexing/roots but is not reachable as a search filter from the UI surface, so a labelled corpus cannot be queried by its label (round-14 human-validation finding 4, secondary) — `modules/app-api/src/main/java/io/justsearch/app/api/knowledge/KnowledgeSearchRequest.java:50` (2026-08-06)

### obs:librarysurface — The 'Process pending enrichment' trigger cannot show its pending count: jf-operation renders the cat
`kind: defect?` `anchor: modules/ui-web/src/shell-v0/views/LibrarySurface.ts` `seen: 1` `first: 2026-08-06` `last: 2026-08-06`
- [ ] The 'Process pending enrichment' trigger cannot show its pending count: jf-operation renders the catalog label with no per-instance override by design, and LibrarySurface plumbs no enrichment queue depth — round-14 finding 11's unimplemented half — `modules/ui-web/src/shell-v0/views/LibrarySurface.ts:830` (2026-08-06)

### obs:component-vocabulary-generated — `gen-component-vocabulary --check` is stale on origin/main@50121ccd: the generated tag union is miss
`kind: defect?` `anchor: modules/ui-web/src/shell-v0/renderers/component-vocabulary.generated.ts` `seen: 12` `first: 2026-08-04` `last: 2026-08-07`
- [ ] `gen-component-vocabulary --check` is stale on origin/main@50121ccd: the generated tag union is missing `jf-app-update-banner`, so the ui-web gate set fails for anyone touching `modules/ui-web/src/**` — `modules/ui-web/src/shell-v0/renderers/component-vocabulary.generated.ts:23` (2026-08-06)
- [ ] component-vocabulary.generated.ts is stale on main — jf-app-update-banner missing, so `node scripts/ci/gen-component-vocabulary.mjs --check` fails for an unrelated reason on any ui-web branch — `modules/ui-web/src/shell-v0/renderers/component-vocabulary.generated.ts:23` (2026-08-06)
- [ ] check-consequence-classification and gen-component-vocabulary --check are stale/red on main independent of any bundle: component-vocabulary.generated.ts misses jf-app-update-banner — `modules/ui-web/src/shell-v0/renderers/component-vocabulary.generated.ts:23` (2026-08-04)
- [ ] gen-component-vocabulary --check is RED on main: component-vocabulary.generated.ts is missing jf-app-update-banner (AppUpdateBanner.ts landed in PR #350 without a regen). Not in expected-state.v1.json, so every ui-web bundle now sees a red gate it did not cause — `modules/ui-web/src/shell-v0/renderers/component-vocabulary.generated.ts:23` (2026-08-04)
- [ ] gen-component-vocabulary --check is stale (131 components) with the generated file itself unmodified — pre-existing or produced by a sibling worktree's UnifiedChatView/unifiedChatStyles edits; not caused by tempdoc 807 W1 — `modules/ui-web/src/shell-v0/renderers/component-vocabulary.generated.ts` (2026-08-05)
- [ ] gen-component-vocabulary --check is stale on round-13-record: jf-app-update-banner missing from the generated union (unrelated to the liveness work) — `modules/ui-web/src/shell-v0/renderers/component-vocabulary.generated.ts:22` (2026-08-05)
- [ ] check-gen-component-vocabulary --check is RED on origin/main — 'jf-app-update-banner' missing from the generated vocabulary (regen step skipped when the component landed) — `modules/ui-web/src/shell-v0/renderers/component-vocabulary.generated.ts` (2026-08-06)
- [ ] component-vocabulary.generated.ts is stale on this base: gen-component-vocabulary --check fails because jf-app-update-banner (AppUpdateBanner.ts) was never regenerated in — `modules/ui-web/src/shell-v0/renderers/component-vocabulary.generated.ts:23` (2026-08-06)
- [ ] check-gen-component-vocabulary is stale on main: 'jf-app-update-banner' is defined but missing from component-vocabulary.generated.ts — regen not committed with the banner (out of scope for 812; reverted rather than sweeping an unrelated component into an unrelated PR) — `modules/ui-web/src/shell-v0/renderers/component-vocabulary.generated.ts` (2026-08-06)
- [ ] check-gen-component-vocabulary is stale on origin/main — the generated ComponentTag union is missing `jf-app-update-banner` (pre-existing; regenerating is a one-command fix but out of scope for the 813 §20 branch) — `modules/ui-web/src/shell-v0/renderers/component-vocabulary.generated.ts:23` (2026-08-06)
- [ ] component-vocabulary.generated.ts is stale on main (missing `jf-app-update-banner`), so `gen-component-vocabulary --check` fails for anyone editing modules/ui-web/src — `modules/ui-web/src/shell-v0/renderers/component-vocabulary.generated.ts:23` (2026-08-06)
- [ ] component-vocabulary.generated.ts was stale on main before 818 (missing `jf-app-update-banner`) — an earlier PR defined the element without running gen-component-vocabulary; the 818 regen swept it — `modules/ui-web/src/shell-v0/renderers/component-vocabulary.generated.ts` (2026-08-07)

### obs:resultscard — search-ui-behavior.md points the Help badge at the JsonForms renderer dir (`shell-v0/renderers/`), b
`kind: defect?` `anchor: shell-v0/components/searchResults/ResultsCard.ts` `seen: 2` `first: 2026-08-06` `last: 2026-08-19`
- [ ] search-ui-behavior.md points the Help badge at the JsonForms renderer dir (`shell-v0/renderers/`), but the search result row renders in `shell-v0/components/searchResults/ResultsCard.ts` — stale path pointer in a canonical doc — `docs/reference/search-ui-behavior.md:647` (2026-08-06)
- [ ] UX audit: chat-surface result cards fail contrast in the light palettes — axe color-contrast 1.47:1 (#fdfcfa on #ffc97c) on jf-results-card snapshot chips, plus 4.32:1 on the History/new-chat triggers and status-bar conn/endpoint text; pre-existing, unrelated to 846/847/848 — `modules/ui-web/src/shell-v0/components/searchResults/ResultsCard.ts` (2026-08-19)

### obs:search-ui-behavior — search-ui-behavior.md 'Result Row Anatomy' still describes density modes, a rich-mode metadata line,
`kind: defect?` `anchor: docs/reference/search-ui-behavior.md` `seen: 1` `first: 2026-08-06` `last: 2026-08-06`
- [ ] search-ui-behavior.md 'Result Row Anatomy' still describes density modes, a rich-mode metadata line, match pills and a per-row checkbox; the current ONE row renderer draws only kind-icon/title/line-anchor/row-actions/path/snippet/why-disclosure — same doc-vs-code drift class as the never-implemented Help pill — `docs/reference/search-ui-behavior.md:247` (2026-08-06)

### obs:folderstatus — folderStatus words EVERY provisional stability cause as 'Rebuilding…', so a lost-contact folder row
`kind: defect?` `anchor: modules/ui-web/src/shell-v0/state/folderStatus.ts` `seen: 1` `first: 2026-08-06` `last: 2026-08-06`
- [ ] folderStatus words EVERY provisional stability cause as 'Rebuilding…', so a lost-contact folder row claims a rebuild that is not happening (channel-stale / worker-restart / catching-up all land here) — `modules/ui-web/src/shell-v0/state/folderStatus.ts:99` (2026-08-06)

### obs:unanchored-drift-23 — resolveAndLockAll adds `org.jetbrains.kotlin:kotlin-metadata-jvm:2.3.0=dependencyAnalysisKotlinMetad
`kind: environment?` `anchor: none` `seen: 1` `first: 2026-08-06` `last: 2026-08-06`
- [ ] resolveAndLockAll adds `org.jetbrains.kotlin:kotlin-metadata-jvm:2.3.0=dependencyAnalysisKotlinMetadataClasspath` to all 34 module lockfiles — pre-existing global drift, unrelated to any single dependency change; makes every lockfile regen a 34-file diff — `modules/*/gradle.lockfile` (2026-08-06)

### obs:backfillscheduler — Individual-backfill path still has whole-batch atomic units: processChunkEmbeddingBackfill/processNe
`kind: defect?` `anchor: modules/worker-services/src/main/java/io/justsearch/indexerworker/loop/BackfillScheduler.java` `seen: 1` `first: 2026-08-06` `last: 2026-08-06`
- [ ] Individual-backfill path still has whole-batch atomic units: processChunkEmbeddingBackfill/processNerBackfill/processSpladeBackfill/BGE-M3 have no in-batch budget checkpoint and no bulk-delete-epoch check, so root removal + cycle budget are still unenforceable there (809 finding 3 fixed only the combined path the owner measured) — `modules/worker-services/src/main/java/io/justsearch/indexerworker/loop/BackfillScheduler.java:304` (2026-08-06)

### obs:lib — pipeline-stage-plugins.v1.json has NEVER shipped in the NSIS bundle (rounds 8/10/11 installers all l
`kind: defect?` `anchor: modules/shell/src-tauri/src/lib.rs` `seen: 2` `first: 2026-08-04` `last: 2026-08-10`
- [ ] pipeline-stage-plugins.v1.json has NEVER shipped in the NSIS bundle (rounds 8/10/11 installers all lack it) yet lib.rs:712 passes the dangling -Djustsearch.plugins.manifest path to the Head unconditionally, and the Head silently tolerates it — so pipeline-stage plugins are effectively OFF in every packaged install. Either that is intended (then the shell arg + verify-installer expectation are vestigial and the bundle staging is correct) or the packaged app is silently missing a feature — needs an owner call — `modules/shell/src-tauri/src/lib.rs:712` (2026-08-04)
- [ ] Tauri shell's ordered-shutdown budget may be too tight: `wait_for_child_exit(Duration::from_secs(8))` at `modules/shell/src-tauri/src/lib.rs:149`, but Head's ordered close measured 7.3s on this machine (tempdoc 819 live verification) — only 0.7s margin, so a slower machine could time out and force-kill the JVM mid-shutdown, losing IndexingLoop.finalizeShutdownCommit(). dev-runner now uses 15s for the same wait. (2026-08-10)

### obs:unanchored-drift-24 — modules/shell/src-tauri is not rustfmt-clean (platform_paths.rs:146,189,205; updater.rs:686) and CI
`kind: defect?` `anchor: none` `seen: 1` `first: 2026-08-04` `last: 2026-08-04`
- [ ] modules/shell/src-tauri is not rustfmt-clean (platform_paths.rs:146,189,205; updater.rs:686) and CI has no `cargo fmt --check` step, so Rust formatting drifts unobserved — `.github/workflows/ci.yml:481` (2026-08-04)

### obs:preview-squash-message — Process failure worth a postmortem line: PR #364 merged with the generic squash title 'fix 805 verif
`kind: follow-up?` `anchor: scripts/ci/preview-squash-message.mjs` `seen: 1` `first: 2026-08-04` `last: 2026-08-04`
- [ ] Process failure worth a postmortem line: PR #364 merged with the generic squash title 'fix 805 verify capture' onto public main (249640e0) — gh pr create --fill on a MULTI-commit branch titles from the branch name, preview-squash-message correctly warned (1 warning), but the preview had been bundled into the same background command as checks-wait and the merge fired on the PASS notification without reading the preview lines above it. Rule that would have held: preview runs and is READ in its own step before any merge command exists; never bundle preview+watch. Title is permanent; body content was accurate. — `scripts/ci/preview-squash-message.mjs` (2026-08-04)

### obs:check-token-health — check_token_health.py vs deliberate enforcement probes: round 12's one and only violation (POST /api
`kind: environment?` `anchor: scripts/sandbox/check_token_health.py` `seen: 1` `first: 2026-08-04` `last: 2026-08-04`
- [ ] check_token_health.py vs deliberate enforcement probes: round 12's one and only violation (POST /api/knowledge/search 401 in 1.39ms) was the round's own declared no-token control probe proving prod=true — good round practice the mechanical check cannot distinguish from a defect. Small follow-up: let a round declare control probes in a sidecar (e.g. evidence/token-health-declared-probes.json with route+approx timestamp) that the check attributes and excludes, keeping everything else blocking — `scripts/sandbox/check_token_health.py` (2026-08-04)

### obs:runeventstore — RunEventStore.readEvents() returns List.of() when sealed+locked with an inline 'documented limitatio
`kind: defect?` `anchor: modules/app-agent/src/main/java/io/justsearch/agent/RunEventStore.java` `seen: 2` `first: 2026-08-04` `last: 2026-08-04`
- [ ] RunEventStore.readEvents() returns List.of() when sealed+locked with an inline 'documented limitation' comment — the same locked-vs-empty collapse 806 W1 fixed for memory; the Activity ledger still answers 'no events' when it means 'cannot read' — `modules/app-agent/src/main/java/io/justsearch/agent/RunEventStore.java:177` (2026-08-04)
- [ ] 806 C5 residue (scoped out of W2, comment upgraded only): RunEventStore.readEvents returns empty while the chat cipher is locked, so GET /api/chat/sessions/{id}/events answers 404 'No events found' and .../transcript downloads events:[] — the same unreadable-is-not-empty defect W1 fixed for memory. Fix needs the 423/STORE_LOCKED treatment threaded through AgentRunQueryService.threadEvents, which changes what a locked chat thread renders and needs live verification — `modules/app-agent/src/main/java/io/justsearch/agent/RunEventStore.java:196` (2026-08-04)

### obs:model-registry-v2 — Released first-party model packs ship stale/missing model_manifest.json: the embedding pack's releas
`kind: defect?` `anchor: modules/ui/src/main/resources/ai/model-registry.v2.json` `seen: 1` `first: 2026-08-05` `last: 2026-08-05`
- [ ] Released first-party model packs ship stale/missing model_manifest.json: the embedding pack's released asset (135 bytes) has no `capabilities` block and maps cpu->model_fp16.onnx (repo copy says model.onnx), and ner/reranker/citation/splade ship no manifest at all — so a clean install cannot declare context_length/prefixes and the resolver falls back. Needs regenerated assets uploaded to justsearch-releases models-v1 + supportingFiles sha256/URL entries — `modules/ui/src/main/resources/ai/model-registry.v2.json:44-50` (tempdoc 807 item 2) (2026-08-05)

### obs:strip-token-fallbacks — strip-token-fallbacks --check is RED on the same two files as the two documented known-red gates (Ac
`kind: follow-up?` `anchor: scripts/ci/strip-token-fallbacks.mjs` `seen: 1` `first: 2026-08-05` `last: 2026-08-05`
- [ ] strip-token-fallbacks --check is RED on the same two files as the two documented known-red gates (ActionLedgerView.ts 2, RecentsMenu.ts 4) but is not itself listed in expected-state.v1.json — consider adding it so agents do not re-diagnose it — `scripts/ci/strip-token-fallbacks.mjs` (2026-08-05)

### obs:runtimereconcilertest — Flaky under full-suite parallelism (807 cross-cutting run): RuntimeReconcilerTest.specWriteDuringPro
`kind: environment?` `anchor: modules/app-services/src/test/java/io/justsearch/app/services/runtimestate/RuntimeReconcilerTest.java` `seen: 1` `first: 2026-08-05` `last: 2026-08-05`
- [ ] Flaky under full-suite parallelism (807 cross-cutting run): RuntimeReconcilerTest.specWriteDuringProcedure_deferredUntilEnd threw AccessDeniedException at :256 during a forced --rerun-tasks suite, then passed cleanly when re-run alone (BUILD SUCCESSFUL 9s). Zero app-services changes in the campaign diff, so it is not causal — likely a Windows temp-file lock under concurrent test execution. Worth a look if it recurs: the test writes a spec file under a @TempDir while other suites run — `modules/app-services/src/test/java/io/justsearch/app/services/runtimestate/RuntimeReconcilerTest.java:256` (2026-08-05)

### obs:verdict — Status bar still projects a RETAINED stability cause while the backend is dead: with indexState=UNAV
`kind: defect?` `anchor: modules/ui-web/src/shell-v0/state/verdict.ts` `seen: 1` `first: 2026-08-05` `last: 2026-08-05`
- [ ] Status bar still projects a RETAINED stability cause while the backend is dead: with indexState=UNAVAILABLE retained and contact aged out, verdict stays transitioning/worker-restart so statusLabel reads 'Restarting...' (tone info) even though snapshotLive is false and the CONN dot is red — computeStability's retained-field branches outrank the reachability-driven channel-stale branch — `modules/ui-web/src/shell-v0/state/verdict.ts:103` (2026-08-05)

### obs:agent — GET /api/chat/agent/history is vestigial for agent-run accountability: it reads FileOperationLog (fi
`kind: defect?` `anchor: modules/ui-web/src/api/domains/agent.ts` `seen: 1` `first: 2026-08-05` `last: 2026-08-05`
- [ ] GET /api/chat/agent/history is vestigial for agent-run accountability: it reads FileOperationLog (file-mutation undo batches) only, while agent runs record to AgentRunStore + /api/action-ledger (tempdoc 561 P-B1); the endpoint name over-promises and a stale FE wrapper remains — `modules/ui-web/src/api/domains/agent.ts:148`, `modules/ui/src/main/java/io/justsearch/ui/api/AgentRoutes.java:84` (2026-08-05)

### obs:ui — jseval ui-proportion-gate writes its JSON report to stderr, not stdout — anyone redirecting stdout g
`kind: defect?` `anchor: scripts/jseval/jseval/commands/ui.py` `seen: 1` `first: 2026-08-06` `last: 2026-08-06`
- [ ] jseval ui-proportion-gate writes its JSON report to stderr, not stdout — anyone redirecting stdout gets an empty file (refute-first review 2026-08-06) — `scripts/jseval/jseval/commands/ui.py` (2026-08-06)

### obs:unanchored-missing-10 — wire gate reports pass while emitting an error-level buf-cli-missing finding — a missing buf CLI mak
`kind: defect?` `anchor: none` `seen: 1` `first: 2026-08-06` `last: 2026-08-06`
- [ ] wire gate reports pass while emitting an error-level buf-cli-missing finding — a missing buf CLI makes the proto breaking-change check vacuously green instead of failing closed — `scripts/governance/gates/wire/` (2026-08-06)

### obs:indexed-root-v1 — Some SSOT schema baselines are hand-authored in record-declaration order while the victools generato
`kind: defect?` `anchor: SSOT/schemas/indexed-root.v1.json` `seen: 1` `first: 2026-08-06` `last: 2026-08-06`
- [ ] Some SSOT schema baselines are hand-authored in record-declaration order while the victools generator emits alphabetically-sorted properties, so a recapture reorders the whole file (semantically inert, large diff) — `SSOT/schemas/indexed-root.v1.json` (2026-08-06)

### obs:indexedrootviewschematest — SSOT/schemas/indexed-root.v1.json was committed in record order but the victools generator emits alp
`kind: defect?` `anchor: modules/app-api/src/test/java/io/justsearch/app/api/indexing/IndexedRootViewSchemaTest.java` `seen: 1` `first: 2026-08-06` `last: 2026-08-06`
- [ ] SSOT/schemas/indexed-root.v1.json was committed in record order but the victools generator emits alphabetical order, so any recapture rewrites the whole file (813 Slice A hand-edited it) — `modules/app-api/src/test/java/io/justsearch/app/api/indexing/IndexedRootViewSchemaTest.java:93` (2026-08-06)

### obs:ingeststarvatione2etest — IngestStarvationE2ETest (798 T4) fails on hosted CI first attempts with initializationError/TimeoutE
`kind: defect?` `anchor: IngestStarvationE2ETest` `seen: 1` `first: 2026-08-06` `last: 2026-08-06`
- [ ] IngestStarvationE2ETest (798 T4) fails on hosted CI first attempts with initializationError/TimeoutException in the IsolatedBackendFixture spawn — 2 of 3 first attempts across PRs #377/#384 (runs 31070845964 attempt 1, 31093519435), passes locally (tests=1 failures=0) and on CI rerun. Recurring loaded-runner spawn-timeout pattern, not a product defect; the fixture readiness timeout likely needs headroom on hosted runners — scripts/../system-tests IsolatedBackendFixture. (2026-08-06)

### obs:ragcontextops — `buildRagFilters` never populates `collection`/`docIds` (the `RetrieveContextRequest` proto has no c
`kind: defect?` `anchor: modules/adapters-lucene/src/main/java/io/justsearch/adapters/lucene/runtime/RagContextOps.java` `seen: 6` `first: 2026-08-06` `last: 2026-08-19`
- [ ] `buildRagFilters` never populates `collection`/`docIds` (the `RetrieveContextRequest` proto has no collection field), so the RAG path has no producer of an explicit collection scope — the withIncludeChunks copy fix restores propagation, but nothing sets it there yet — `modules/adapters-lucene/src/main/java/io/justsearch/adapters/lucene/runtime/RagContextOps.java:919` (2026-08-06)
- [ ] RagContextOps.runBudgetLoop adds a hit to `used` on STOPPED_BUDGET (mapAppendResult folds it into APPENDED_AND_STOPPED), so a budget-stopped chunk becomes a citation/source with no context section the model ever saw — `modules/worker-services/src/main/java/io/justsearch/indexerworker/services/RagContextOps.java:1704` (2026-08-13)
- [ ] Citation startLine/endLine are 1-based at the producer (countNewlinesBefore+1) but DocumentPane's DocumentLineRange is 0-based inclusive, and no hop subtracts one — every citation highlight is off by one line; Sv3Main.ts:100's '0-based inclusive' comment is false — `modules/worker-services/src/main/java/io/justsearch/indexerworker/services/RagContextOps.java:872` (2026-08-18)
- [ ] RagContextOps.mapAppendResult collapses STOPPED_BUDGET into APPENDED_AND_STOPPED and runBudgetLoop then adds the hit to 'used', so a budget-exhausted retrieval emits a citation and increments chunksIncluded for a passage that appended nothing — citations.size() can exceed sections.size() by one — `modules/worker-services/src/main/java/io/justsearch/indexerworker/services/RagContextOps.java:1706-1721` (2026-08-18)
- [ ] 849 S11 (backend, pre-existing): RagContextOps fabricates startChar=searchFrom when indexOf misses, so those citations carry offsets that point at the wrong text — with 849 slice 2's excerpt witness they now suppress with a 'document may have changed' explanation that is FALSE (the document is fine; the producer guessed) — `modules/worker-services/src/main/java/io/justsearch/indexerworker/services/RagContextOps.java:859` (2026-08-19)
- [ ] 849 follow-up: no honest per-source retrieval-strength readout exists — ContextCitation.score is the raw Lucene hit score (RRF-fused hybrid caps ~0.09, BM25 unbounded) so no fixed tier scale can band it; the mode-independent alternative is RANK WITHIN THE TURN's source list (position is already available FE-side and needs no calibration) — a real design question, deferred out of 849 slice 3 — `modules/worker-services/src/main/java/io/justsearch/indexerworker/services/RagContextOps.java:395` (2026-08-19)

### obs:index-general-2 — check-runtime-manifest-closure fails pre-existing on 2 sibling-file reads of runtime/api-port.txt ou
`kind: environment?` `anchor: packaging/mcpb/server/index.js` `seen: 1` `first: 2026-08-06` `last: 2026-08-06`
- [ ] check-runtime-manifest-closure fails pre-existing on 2 sibling-file reads of runtime/api-port.txt outside the publisher — `packaging/mcpb/server/index.js:33`, `scripts/sandbox/mcp-typed-confirm.mjs:109` (2026-08-06)

### obs:store-recoverability-v1 — store-recoverability register drift: durable-grants ownedPaths says intent/durable-grants.json but t
`kind: defect?` `anchor: governance/store-recoverability.v1.json` `seen: 1` `first: 2026-08-06` `last: 2026-08-06`
- [ ] store-recoverability register drift: durable-grants ownedPaths says intent/durable-grants.json but the store writes ui/durable-grants.json — `governance/store-recoverability.v1.json` vs `modules/app-services/src/main/java/io/justsearch/app/services/intent/DurableGrantStore.java:297` (2026-08-06)

### obs:uisettingsstore — JUSTSEARCH_HOME base-dir resolution is duplicated verbatim in two stores with no shared helper — `mo
`kind: defect?` `anchor: modules/app-services/src/main/java/io/justsearch/app/services/settings/UiSettingsStore.java` `seen: 2` `first: 2026-08-06` `last: 2026-08-14`
- [ ] JUSTSEARCH_HOME base-dir resolution is duplicated verbatim in two stores with no shared helper — `modules/app-services/src/main/java/io/justsearch/app/services/settings/UiSettingsStore.java:104`, `modules/app-services/src/main/java/io/justsearch/app/services/intent/DurableGrantStore.java:282` (2026-08-06)
- [ ] RuntimeReconcilerTest.specWriteDuringProcedure_deferredUntilEnd flaked once with AccessDeniedException renaming settings.json.tmp in a JUnit temp dir (Windows file-lock race; passed on immediate re-run) — `modules/app-services/src/main/java/io/justsearch/app/services/settings/UiSettingsStore.java:98` (2026-08-14)

### obs:remoteknowledgeclient — Watched-root scans drop their own collection: `RootLifecycleOps.ScanRootFn` has no collection parame
`kind: environment?` `anchor: modules/app-services/src/main/java/io/justsearch/app/services/worker/RemoteKnowledgeClient.java` `seen: 4` `first: 2026-08-06` `last: 2026-08-13`
- [ ] Watched-root scans drop their own collection: `RootLifecycleOps.ScanRootFn` has no collection parameter and `RemoteKnowledgeClient` wires a literal `null`, so a root added with `collection:"x"` indexes docs untagged while `/api/indexing/roots` reports `"x"` — pre-existing incoherence, natural C-2 follow-up now that ingest inheritance writes the label the root's own scan never does — `modules/app-services/src/main/java/io/justsearch/app/services/worker/RemoteKnowledgeClient.java:273` (2026-08-06)
- [ ] Watched-root scans dispatch RemoteKnowledgeClient.scanRoot directly (bypassing KnowledgeHttpApiAdapter), so their jobs carry a worker-minted scanId but the Head never opens the scan with ScanRollupLedger — a watched-root scan leaves per-doc rows with no rollup row — `modules/app-services/src/main/java/io/justsearch/app/services/worker/RemoteKnowledgeClient.java:270` (2026-08-06)
- [ ] re-observation: a watched root's own scan drops its collection tag — ScanRootFn has no collection parameter and the production wiring passes null, so documents admitted by the root's initial walk carry no `collection` field (baseline now pinned by WatchedRootScanCollectionBaselineTest) — `modules/app-services/src/main/java/io/justsearch/app/services/worker/RemoteKnowledgeClient.java:273` (2026-08-06)
- [ ] RemoteKnowledgeClient with maxRetries=0 builds an invalid gRPC service config and throws IllegalStateException 'maxAttempts must be greater than 1' at connect() — setting JUSTSEARCH_WORKER_MAX_RETRIES=0 breaks the client at boot (found while writing PR #439's deadline test; a retry-disable knob that hard-crashes is a config-surface defect) — `modules/app-services/src/main/java/io/justsearch/app/services/worker/RemoteKnowledgeClient.java` (2026-08-13)

### obs:agenttoolfactory — MCP/agent ingest uses a SECOND KnowledgeHttpApiAdapter instance that never receives setScanProgressR
`kind: defect?` `anchor: modules/app-services/src/main/java/io/justsearch/app/services/bootstrap/phases/AgentToolFactory.java` `seen: 1` `first: 2026-08-06` `last: 2026-08-06`
- [ ] MCP/agent ingest uses a SECOND KnowledgeHttpApiAdapter instance that never receives setScanProgressRegistry (nor 812's setScanRollupLedger), so agent-driven directory ingests emit no scan-progress SSE and no scan-rollup audit row — `modules/app-services/src/main/java/io/justsearch/app/services/bootstrap/phases/AgentToolFactory.java:61` (2026-08-06)

### obs:facts — The core.files fact has two labels in two authorities: facts.ts declares label 'Files' while StatusD
`kind: defect?` `anchor: modules/ui-web/src/shell-v0/display/facts.ts` `seen: 1` `first: 2026-08-06` `last: 2026-08-06`
- [ ] The core.files fact has two labels in two authorities: facts.ts declares label 'Files' while StatusDeck's aria-label says 'Documents indexed' — a screen-reader user and a sighted user hear/see different names for the same value — `modules/ui-web/src/shell-v0/display/facts.ts:181`, `modules/ui-web/src/shell-v0/components/StatusDeck.ts:84` (2026-08-06)

### obs:unanchored-drift-25 — `:modules:app-api:updateSchemas` always FAILS on its first run after a record change (the SchemaDrif
`kind: defect?` `anchor: none` `seen: 1` `first: 2026-08-06` `last: 2026-08-06`
- [ ] `:modules:app-api:updateSchemas` always FAILS on its first run after a record change (the SchemaDrift/CrossLanguageContract assertions run in the same task that writes the regen), so every schema update needs the task invoked twice — the failure text says 'regenerate with <this exact command>', which reads like the command did not work — `modules/app-api/build.gradle.kts` (updateSchemas task) (2026-08-06)

### obs:watchedrootsstore — store-recoverability register declares the watched-roots store as `watched-roots.json` (hyphen) but
`kind: defect?` `anchor: modules/app-services/src/main/java/io/justsearch/app/services/worker/WatchedRootsStore.java` `seen: 1` `first: 2026-08-06` `last: 2026-08-06`
- [ ] store-recoverability register declares the watched-roots store as `watched-roots.json` (hyphen) but the real file is `watched_roots.json` (underscore), so any register-derived path handling for that row silently matches nothing — `governance/store-recoverability.v1.json:watched-roots` vs `modules/app-services/src/main/java/io/justsearch/app/services/worker/WatchedRootsStore.java:24` (2026-08-06)

### obs:dev-all — second hand-maintained soft-clean keep list still forks the store-recoverability register (dev-runne
`kind: defect?` `anchor: modules/ui-web/scripts/dev-all.cjs` `seen: 1` `first: 2026-08-06` `last: 2026-08-06`
- [ ] second hand-maintained soft-clean keep list still forks the store-recoverability register (dev-runner.cjs now derives its AUTHORED set; this copy does not) — `modules/ui-web/scripts/dev-all.cjs:55` (2026-08-06)

### obs:indexingcontroller — POST /api/indexing/roots still 500s on a non-string `collection` (unchecked Map<String,String> cast
`kind: defect?` `anchor: modules/ui/src/main/java/io/justsearch/ui/api/IndexingController.java` `seen: 1` `first: 2026-08-06` `last: 2026-08-06`
- [ ] POST /api/indexing/roots still 500s on a non-string `collection` (unchecked Map<String,String> cast -> ClassCastException) where POST /api/knowledge/ingest 400s on the same input — `modules/ui/src/main/java/io/justsearch/ui/api/IndexingController.java:158` (2026-08-06)

### obs:sourcespane — axe nested-interactive (serious, 3 nodes) in the docked evidence rail: `.source[role="button"]` rows
`kind: environment?` `anchor: modules/ui-web/src/shell-v0/components/SourcesPane.ts` `seen: 2` `first: 2026-08-06` `last: 2026-08-14`
- [ ] axe nested-interactive (serious, 3 nodes) in the docked evidence rail: `.source[role="button"]` rows have focusable descendants — newly VISIBLE via tempdoc 814 §D8's `chat-evidence-rail` capture, pre-existing in the component; the two new steps were deliberately NOT added to the a11y baseline rather than baselining a real defect — `modules/ui-web/src/shell-v0/components/SourcesPane.ts` (2026-08-06)
- [ ] Three surfaces of one cross-surface selection now disagree on the aria idiom: MarkdownBlock marks and CitationsPanel cards REMOVE aria-current when unselected, SourcesPane writes aria-current="false" — `modules/ui-web/src/shell-v0/components/SourcesPane.ts:372` (2026-08-14)

### obs:mark-dark — Dark-ground app icon is near-invisible over light grounds: icon.ico/icns/app-PNGs ship ink #eceef1 o
`kind: defect?` `anchor: brand/mark-dark.svg` `seen: 1` `first: 2026-08-06` `last: 2026-08-06`
- [ ] Dark-ground app icon is near-invisible over light grounds: icon.ico/icns/app-PNGs ship ink #eceef1 on a transparent field per the mark spec, so on a white Explorer background the mass and its slot both vanish — spec-mandated, raised as an owner question in tempdoc 815 §7 — `brand/mark-dark.svg` (2026-08-06)

### obs:facetingengine — FacetingEngine truncation OMITS rather than undercounts: on exceeding maxDocsScanned it breaks out o
`kind: defect?` `anchor: modules/adapters-lucene/src/main/java/io/justsearch/adapters/lucene/runtime/FacetingEngine.java` `seen: 1` `first: 2026-08-06` `last: 2026-08-06`
- [ ] FacetingEngine truncation OMITS rather than undercounts: on exceeding maxDocsScanned it breaks out of the segment loop, so a facet value living only in a later segment vanishes entirely — any consumer treating facets as complete without checking facetsTruncated can silently lose whole categories — `modules/adapters-lucene/src/main/java/io/justsearch/adapters/lucene/runtime/FacetingEngine.java:100` (2026-08-06)

### obs:envelopestream-test — Flaky under full-suite load only: EnvelopeStream heartbeat-watchdog reconnect test expects >=2 sourc
`kind: environment?` `anchor: modules/ui-web/src/shell-v0/streaming/EnvelopeStream.test.ts` `seen: 8` `first: 2026-08-06` `last: 2026-08-19`
- [ ] Flaky under full-suite load only: EnvelopeStream heartbeat-watchdog reconnect test expects >=2 sources, got 1; passes in isolation — `modules/ui-web/src/shell-v0/streaming/EnvelopeStream.test.ts:488` (2026-08-06)
- [ ] ui-web full-suite flake under load: wall-clock watchdog tests fail only in the full parallel run, pass alone — EnvelopeStream watchdog + one resourceRegistry occurrence — `modules/ui-web/src/shell-v0/streaming/EnvelopeStream.test.ts:488` (2026-08-12)
- [ ] EnvelopeStream heartbeat-watchdog test is load-flaky: failed once in a full ui-web run, passed in isolation and on re-run — `modules/ui-web/src/shell-v0/streaming/EnvelopeStream.test.ts` (2026-08-13)
- [ ] Flaky under full-suite load: EnvelopeStream watchdog reconnect test expects >=2 sources after a 70ms wait and got 1 (passes in isolation; timing-sensitive) — `modules/ui-web/src/shell-v0/streaming/EnvelopeStream.test.ts:488` (2026-08-13)
- [ ] EnvelopeStream heartbeat-watchdog reconnect test is timing-flaky under full-suite parallel load (passes 24/24 in isolation; intermittent fail in npm run test:unit:run) — `modules/ui-web/src/shell-v0/streaming/EnvelopeStream.test.ts` (2026-08-14)
- [ ] ui-web unit suite shows two intermittent flakes under parallel-agent CPU load, both green on re-run and unrelated to the slice: EnvelopeStream.test.ts:488 heartbeat watchdog (expected 1 to be >= 2), and an ECONNREFUSED 127.0.0.1:3000 dump with no recoverable test name — `modules/ui-web/src/shell-v0/streaming/EnvelopeStream.test.ts:488` (2026-08-14)
- [ ] Flaky under full-suite load: EnvelopeStream 'reconnects when the heartbeat-absence watchdog expires' failed once in a 425-file run (real-timer 70ms wait), green in isolation — `modules/ui-web/src/shell-v0/streaming/EnvelopeStream.test.ts:488` (2026-08-18)
- [ ] ui-web unit suite flake: EnvelopeStream heartbeat-watchdog case asserts a 70ms real-time reconnect and fails under parallel load (green in isolation) — `modules/ui-web/src/shell-v0/streaming/EnvelopeStream.test.ts:488` (2026-08-19)

### obs:ui-shot — jseval ui-shot / ui-*-gate auto-serve leaks its Vite child process — two survived my session in the
`kind: defect?` `anchor: scripts/jseval/jseval/ui_shot.py` `seen: 1` `first: 2026-08-06` `last: 2026-08-06`
- [ ] jseval ui-shot / ui-*-gate auto-serve leaks its Vite child process — two survived my session in the worktree and their open handle on node_modules/lightningcss-win32-x64-msvc/lightningcss.win32-x64-msvc.node made `gradlew build` fail with npm EPERM unlink at :modules:ui:installWebDependencies (looks like a build break, is a stale lock) — `scripts/jseval/jseval/ui_shot.py` (2026-08-06)

### obs:unanchored-error-15 — CI triage datum: system-tests isolated-backend E2E init TimeoutException reproduced twice on job-lev
`kind: defect?` `anchor: none` `seen: 1` `first: 2026-08-06` `last: 2026-08-06`
- [ ] CI triage datum: system-tests isolated-backend E2E init TimeoutException reproduced twice on job-level reruns of one PR run (docs-only diff, main green on same code) but vanished on a FULL fresh 'gh run rerun' — job-level rerun appears to reuse a poisoned workspace/runner state; prefer full rerun for this signature — `.github/workflows/ci.yml` (2026-08-06, PR #388) (2026-08-06)

### obs:searchresponsebuilder — Multi-leg facet query AND matchCount rebuilt with hardcoded SIMPLE syntax, ignoring request querySyn
`kind: defect?` `anchor: modules/worker-services/.../respond/SearchResponseBuilder.java` `seen: 2` `first: 2026-08-08` `last: 2026-08-12`
- [ ] Multi-leg facet query AND matchCount rebuilt with hardcoded SIMPLE syntax, ignoring request querySyntax — facet counts/matchCount computed over a different parse than the results for LUCENE-syntax queries — `modules/worker-services/.../respond/SearchResponseBuilder.java:237,286` (2026-08-12)
- [ ] ROOT-CAUSED (supersedes the 2026-08-08 matchCount note): matchCount counts only the lexical BM25 predicate population (`SearchResponseBuilder.java:170-174,274-300`) while HYBRID results are a 3-leg CC fusion window admitting dense/SPLADE-only rows (`SearchExecutor.java:400-478`, cc_zero_exclude defaults false `ResolvedConfigBuilder.java:1542`) — so refined-pass rows can exceed matchCount. 597 §8.3 never modeled the mixed case; its invariant checked matchCount vs facets only. Fix is backend count-semantics design (true union count across contributing legs, or per-hit leg provenance for an honest split label) — needs its own tempdoc-scale decision. (2026-08-08)

### obs:unanchored-general-101 — SCAN_MODE_FORCE_REINDEX is accepted by WorkerScanOps.ScanRequest (:364-378) but never consulted in t
`kind: defect?` `anchor: none` `seen: 1` `first: 2026-08-12` `last: 2026-08-12`
- [ ] SCAN_MODE_FORCE_REINDEX is accepted by WorkerScanOps.ScanRequest (:364-378) but never consulted in the scan body — the mode is inert, so no working re-tag route exists via scan (821 §L.3) (2026-08-12)

### obs:searchv2view — search-v2: the ⇥ flip lens survives typing — L1 says it dies with the draft, but onInput never clear
`kind: defect?` `anchor: modules/ui-web/src/shell-v0/views/search-v2/SearchV2View.ts` `seen: 1` `first: 2026-08-08` `last: 2026-08-08`
- [ ] search-v2: the ⇥ flip lens survives typing — L1 says it dies with the draft, but onInput never clears `flipped` (only Escape/commit do), so a Tab-flip made for one draft still applies after the draft is rewritten — `modules/ui-web/src/shell-v0/views/search-v2/SearchV2View.ts:2300` (2026-08-08)

### obs:core-sepia-focus — core.sepia-focus (a LIGHT palette) defines accent fills but no text-grade role tokens, so --text-suc
`kind: defect?` `anchor: modules/ui-web/public/themes/core.sepia-focus.css` `seen: 1` `first: 2026-08-08` `last: 2026-08-08`
- [ ] core.sepia-focus (a LIGHT palette) defines accent fills but no text-grade role tokens, so --text-success/-danger/-warning/-tint fall through to tokens.css's DARK values (light tints) on its cream surfaces — measured 1.39-1.92:1, far below AA; same gap for the highlight/link roles (577 Phase 7) which no built-in palette defines — `modules/ui-web/public/themes/core.sepia-focus.css:61` (2026-08-08)

### obs:unanchored-general-102 — `EmbeddingCompatibilityController.checkRebuildCompletion` certifies on `pendingEmbeddingCount == 0`
`kind: defect?` `anchor: none` `seen: 1` `first: 2026-08-10` `last: 2026-08-10`
- [ ] `EmbeddingCompatibilityController.checkRebuildCompletion` certifies on `pendingEmbeddingCount == 0` (`:238-251`), which "every document FAILED" satisfies exactly as well as "every document succeeded" — so a wholly-failed rebuild stamps the fingerprint and marks the index COMPATIBLE at 0% vector coverage. Observed live: `embeddingDocCount=5 completed=0 pending=0 failed=5 coverage=0%` with `embeddingCompatState=COMPATIBLE, FINGERPRINT_MATCH`. The javadoc's justification ("coverage==100% algebraically implies pending==0") holds in that direction only; the converse fails under failures. Trigger in this instance was environmental (`ORT_FAIL ... CUDNN failure 1002 CUDNN_STATUS_SUBLIBRARY_LOADING_FAILED`), but the certification admits it regardless of cause (2026-08-10)

### obs:unanchored-general-103 — sign-vendored-payload.ps1 .DESCRIPTION claims it sets JUSTSEARCH_REQUIRE_SIGNING=true for the child
`kind: defect?` `anchor: none` `seen: 1` `first: 2026-08-12` `last: 2026-08-12`
- [ ] sign-vendored-payload.ps1 .DESCRIPTION claims it sets JUSTSEARCH_REQUIRE_SIGNING=true for the child sign-windows.ps1, but no code path ever sets it — the child's own fail-closed never engages; only the script's independent Authenticode re-verify catches an unsigned PE — `scripts/release/sign-vendored-payload.ps1:21` (2026-08-12)

### obs:rootlifecycleops — Mojibake (cp1252 round-trip) in a Java comment — `modules/app-services/src/main/java/io/justsearch/a
`kind: defect?` `anchor: modules/app-services/src/main/java/io/justsearch/app/services/worker/RootLifecycleOps.java` `seen: 2` `first: 2026-08-12` `last: 2026-08-14`
- [ ] Mojibake (cp1252 round-trip) in a Java comment — `modules/app-services/src/main/java/io/justsearch/app/services/worker/RootLifecycleOps.java` (2026-08-12)
- [ ] WatchedRootScanCollectionTest$ProductionWireForwarding flakes under full-suite load: JUnitException 'Failed to close extension context' from a background walkAndSubmit thread still writing during @TempDir teardown; passes on isolate-rerun — `modules/app-services/src/main/java/io/justsearch/app/services/worker/RootLifecycleOps.java:262` (2026-08-14)

### obs:unanchored-flake-8 — Integration tests (system-tests tier) flake: IsolatedBackendFixture spawn TimeoutException on 'Opera
`kind: environment?` `anchor: none` `seen: 1` `first: 2026-08-12` `last: 2026-08-12`
- [ ] Integration tests (system-tests tier) flake: IsolatedBackendFixture spawn TimeoutException on 'Operation Preview E2E' initializationError, passes on rerun (PR #413, run 31642907904) — `modules/system-tests/.../IsolatedBackendFixture` (2026-08-12)

### obs:airoutes — GET /api/ai/install/plan-preview is bound in AiRoutes.java:104 but absent from modules/ui-web/src/ap
`kind: defect?` `anchor: modules/ui/src/main/java/io/justsearch/ui/api/routes/AiRoutes.java` `seen: 1` `first: 2026-08-12` `last: 2026-08-12`
- [ ] GET /api/ai/install/plan-preview is bound in AiRoutes.java:104 but absent from modules/ui-web/src/api/generated/{apiRoutes.ts,route-manifest.snapshot.json} — the generated route manifest is missing a live route — `modules/ui/src/main/java/io/justsearch/ui/api/routes/AiRoutes.java:104` (2026-08-12)

### obs:530-class-size-ratchet-automation — docs-validate.mjs crashes (YAMLException, unhandled) on malformed frontmatter in docs/tempdocs/530-c
`kind: environment?` `anchor: docs/tempdocs/530-class-size-ratchet-automation.md` `seen: 2` `first: 2026-08-12` `last: 2026-08-13`
- [ ] docs-validate.mjs crashes (YAMLException, unhandled) on malformed frontmatter in docs/tempdocs/530-class-size-ratchet-automation.md — pre-existing since the initial public release; the validator should report the offending file instead of throwing — `docs/tempdocs/530-class-size-ratchet-automation.md:6` (2026-08-12)
- [ ] scripts/docs/docs-validate.mjs crashes (unhandled YAMLException) on tempdoc 530's unquoted `updated:` value containing a colon+parenthetical — validator aborts before validating anything; pre-existing on main — `docs/tempdocs/530-class-size-ratchet-automation.md:6` (2026-08-13)

### obs:unanchored-general-104 — NSIS !uninstfinalize failures are silent by construction: Tauri emits the hook without an exit-code
`kind: environment?` `anchor: none` `seen: 1` `first: 2026-08-12` `last: 2026-08-12`
- [ ] NSIS !uninstfinalize failures are silent by construction: Tauri emits the hook without an exit-code comparison, so a failed uninstaller signing leaves the build green (round-16 F3 shipped an unsigned uninstall.exe this way). No gate detects it; only the sandbox PE sweep does — `modules/shell/src-tauri/nsis: tauri-bundler nsis/mod.rs:311` (2026-08-12)

### obs:eliasjustus-justsearch-locale-en-us — packaging/winget/eliasjustus.JustSearch.locale.en-US.yaml still carries Publisher: TODO-OWNER-PUBLIS
`kind: defect?` `anchor: packaging/winget/eliasjustus.JustSearch.locale.en-US.yaml` `seen: 1` `first: 2026-08-12` `last: 2026-08-12`
- [ ] packaging/winget/eliasjustus.JustSearch.locale.en-US.yaml still carries Publisher: TODO-OWNER-PUBLISHER-NAME while the NSIS bundle publisher is now the signing CN 'Elias Justus' — `packaging/winget/eliasjustus.JustSearch.locale.en-US.yaml:11` (2026-08-12)

### obs:tauri-updater-conf — Nothing asserts tauri.updater.conf.json plugins.updater.pubkey == repo var JUSTSEARCH_UPDATE_ARTIFAC
`kind: defect?` `anchor: modules/shell/src-tauri/tauri.updater.conf.json` `seen: 1` `first: 2026-08-13` `last: 2026-08-13`
- [ ] Nothing asserts tauri.updater.conf.json plugins.updater.pubkey == repo var JUSTSEARCH_UPDATE_ARTIFACT_PUBLIC_KEY — silent drift would ship updater artifacts signed for a key clients don't pin; add to the release preflight (surfaced by v0.2.0 tag run 31697222656) — `modules/shell/src-tauri/tauri.updater.conf.json` (2026-08-13)

### obs:tauri-conf — tauri.conf.json declares bundle icons 32x32.png / 128x128.png / 128x128@2x.png but modules/shell/src
`kind: defect?` `anchor: modules/shell/src-tauri/tauri.conf.json` `seen: 2` `first: 2026-08-06` `last: 2026-08-19`
- [ ] tauri.conf.json declares bundle icons 32x32.png / 128x128.png / 128x128@2x.png but modules/shell/src-tauri/icons/ contains only icon.icns + icon.ico — the PNG variants are missing on disk — `modules/shell/src-tauri/tauri.conf.json:30` (2026-08-06)
- [ ] 823a's cross-era over-install validation (old Software\justsearch key -> new 'Elias Justus' publisher, no migration shim) was deferred past GA by owner adjudication (734:3664 'lane re-runs naturally in the next release's rounds') and has never run: round 16 exercised old-over-old (predates 01f32282), round 17's warm-reinstall lane was 'unobservable' (host restart). v0.2.0 is public so the pre-#410-key population is no longer bounded to dev/sandbox machines - the next release's sandbox round should treat old-key-over-new-key over-install as BLOCKING, not best-effort: stage a pre-01f32282 build, over-install current, observe RestorePreviousInstallLocation fallback + uninstaller handoff + no dangling ARP entry - .github/workflows/build-installer.yml / modules/shell/src-tauri/tauri.conf.json:16 (2026-08-19, Wave-4 verification) (2026-08-19)

### obs:serve-worktree-fe — TaskStop on a serve-worktree-fe.cjs background task orphans its child Vite: the node vite.js child s
`kind: defect?` `anchor: serve-worktree-fe.cjs` `seen: 1` `first: 2026-08-07` `last: 2026-08-07`
- [ ] TaskStop on a serve-worktree-fe.cjs background task orphans its child Vite: the node vite.js child survives, holds rolldown-binding native .node files open, and blocks worktree removal (remove-worktree then guts the tree .git-link-first, leaving the known no-.git shell). Second leak variant beside the jseval auto-serve one logged this session — remedy: after TaskStop, check for node processes with the worktree path in the command line before remove-worktree. (2026-08-07)

### obs:workerscanopstest — WorkerScanOpsTest.backpressureWaiterInvokedWhenQueueAboveHighWatermark times out at 30s under full-s
`kind: follow-up?` `anchor: modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerScanOpsTest.java` `seen: 1` `first: 2026-08-07` `last: 2026-08-07`
- [ ] WorkerScanOpsTest.backpressureWaiterInvokedWhenQueueAboveHighWatermark times out at 30s under full-suite load (passes in isolation; sibling cancellation test took 25.9s) — timing-sensitive queue-depth wait, candidate for condition-polling — `modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerScanOpsTest.java` (2026-08-07)

### obs:otlp-sink-ensure — Agent-observability OTel sink is DEAD and failed silently: nothing listening on 127.0.0.1:4318, last
`kind: defect?` `anchor: scripts/agent-analytics/hooks/otlp-sink-ensure.mjs` `seen: 1` `first: 2026-08-13` `last: 2026-08-13`
- [ ] Agent-observability OTel sink is DEAD and failed silently: nothing listening on 127.0.0.1:4318, last data 2026-07-29 14:01 (32 files in tmp/agent-telemetry/otlp) — otlp-sink-ensure SessionStart hook is fail-open with no alarm, so tempdoc 622's span/cost attribution has been unavailable for ~2 weeks unnoticed. Needs a liveness check or loud degradation notice in the hook — `scripts/agent-analytics/hooks/otlp-sink-ensure.mjs` (found during publish-workflow velocity analysis, 2026-08-14) (2026-08-13)

### obs:unanchored-missing-4 — Windows Gradle cache save has NEVER succeeded in CI: setup-java cache-save fails with unquoted-path
`kind: defect?` `anchor: none` `seen: 1` `first: 2026-08-13` `last: 2026-08-13`
- [ ] Windows Gradle cache save has NEVER succeeded in CI: setup-java cache-save fails with unquoted-path tar error ('C:\Program ... tar.exe exit code 2') emitted as ##[warning] so lanes stay green — every Windows lane (54% = 32.5h of 59.9h daily runner-time on 2026-08-13) runs 100% cold ('gradle cache is not found', 0 FROM-CACHE), and ci-walltime budgets are calibrated against cold runs — `.github/workflows/ci.yml` setup-java cache:gradle on windows lanes (runs 31750644388, 31742266298) (2026-08-13)

### obs:unanchored-general-9 — Supply-chain gap: no wrapper-validation step exists in any workflow (grep wrapper-validation .github
`kind: follow-up?` `anchor: none` `seen: 1` `first: 2026-08-14` `last: 2026-08-14`
- [ ] Supply-chain gap: no wrapper-validation step exists in any workflow (grep wrapper-validation .github/workflows -> 0 hits) — gradle-wrapper.jar integrity was never CI-checked until PR #446's setup-gradle (which validates by default); consider keeping an explicit gradle/actions/wrapper-validation step or documenting reliance on setup-gradle's built-in validation — `.github/workflows/ci.yml` (refuter finding, 2026-08-14) (2026-08-14)

### obs:jvmbaseconventionsplugin — JvmBaseConventionsPlugin retry wiring has a silent-null path: the outer 'catch (_: Exception) { null
`kind: environment?` `anchor: build-logic/src/main/kotlin/conventions/JvmBaseConventionsPlugin.kt` `seen: 1` `first: 2026-08-14` `last: 2026-08-14`
- [ ] JvmBaseConventionsPlugin retry wiring has a silent-null path: the outer 'catch (_: Exception) { null }' around getTestRetry (~:123-127) falls through to findByName('retry') -> null -> 'retryExt?.let' skips ALL retry config with no warning — if the Develocity extension shape ever changes, CI retries silently vanish; only the inner reflection failure warns — `build-logic/src/main/kotlin/conventions/JvmBaseConventionsPlugin.kt:123` (refuter finding, 2026-08-14; pre-existing, out of scope for PR #447 which warns in its own override block) (2026-08-14)

### obs:ci-walltime-policy-v1 — TRIGGER ~2026-08-21 (needs ~1 week of warm-cache data): recalibrate ci-walltime budgets to the post-
`kind: follow-up?` `anchor: scripts/ci/ci-walltime-policy.v1.json` `seen: 1` `first: 2026-08-14` `last: 2026-08-14`
- [ ] TRIGGER ~2026-08-21 (needs ~1 week of warm-cache data): recalibrate ci-walltime budgets to the post-#446 warm baseline — current ceilings assume the dead-cache regime (Windows-native ~6.3min mean; now ~1.9min over n=6 post-migration main runs, 2026-08-14), so a 2x regression fits inside old budgets unnoticed. Set honest warm percentiles from a week of main runs — `scripts/ci/ci-walltime-policy.v1.json` (829 R2 follow-up) (2026-08-14)

### obs:overhead-taxonomy — TRIGGER ~2026-08-21: re-measure WAITING%% via overhead-taxonomy.mjs over the post-R1 week (OTel capt
`kind: follow-up?` `anchor: scripts/agent-analytics/overhead-taxonomy.mjs` `seen: 1` `first: 2026-08-14` `last: 2026-08-14`
- [ ] TRIGGER ~2026-08-21: re-measure WAITING%% via overhead-taxonomy.mjs over the post-R1 week (OTel capturing again since 2026-08-14) — baselines: 19.29%% orchestrator session 776e10cd, 12.10%% 11-session window, 12.47%% 4-week (746 T1). If unmoved by R1's fewer wait cycles, the ack-against-bloated-cache mechanism dominates and the next lever is batching PRs (publish-skill policy, no code) — `scripts/agent-analytics/overhead-taxonomy.mjs` (829 F4 follow-up) (2026-08-14)

### obs:docs-lint — docs-lint.yml targets runs-on [self-hosted, Windows, X64, justsearch-perf] but the only registered r
`kind: defect?` `anchor: .github/workflows/docs-lint.yml` `seen: 1` `first: 2026-08-14` `last: 2026-08-14`
- [ ] docs-lint.yml targets runs-on [self-hosted, Windows, X64, justsearch-perf] but the only registered runner carries labels [self-hosted, Windows, X64, gpu] — no justsearch-perf label exists, so the job can never schedule (24h queue timeout) — `.github/workflows/docs-lint.yml:20` (org-transfer research finding, 2026-08-14) (2026-08-14)

### obs:unanchored-general-12 — Actions cache store measured ~11.0GB / 75 caches at 2026-08-14 ~09:30 UTC despite the ~01:00 prune t
`kind: defect?` `anchor: none` `seen: 1` `first: 2026-08-14` `last: 2026-08-14`
- [ ] Actions cache store measured ~11.0GB / 75 caches at 2026-08-14 ~09:30 UTC despite the ~01:00 prune to 3.4GB — setup-gradle's per-job cache entries may regrow past the 10GB cap quickly; check whether its cache-cleanup default suffices or per-job entry granularity needs tuning (relevant to the #446 acceptance window) — `gh api repos/eliasjustus/justsearch/actions/cache/usage` (2026-08-14)

### obs:unanchored-error — ORG TRANSFER STAGED, HELD BY OWNER (2026-08-14): org justsearch-app created (free, owner eliasjustus
`kind: follow-up?` `anchor: none` `seen: 1` `first: 2026-08-14` `last: 2026-08-14`
- [ ] ORG TRANSFER STAGED, HELD BY OWNER (2026-08-14): org justsearch-app created (free, owner eliasjustus, OAuth restrictions removed); CI merge-queue-ready via PR #463 (merge_group triggers, cla-assistant merge-group no-op, license-free gitleaks CLI); pre-transfer snapshots (branch-protection JSON, 6 Actions variables incl. values, secret names, runner registration) at tmp/agent-telemetry/pre-transfer-snapshot/ (gitignored — variable values must never be committed). Remaining when owner says go: POST /repos/eliasjustus/justsearch/transfer {new_owner: justsearch-app} -> verify protection/variables vs snapshot -> re-register justsearch-gpu-runner -> sweep 47 hardcoded eliasjustus/justsearch refs (53 justsearch-releases refs unaffected) -> re-point local remotes + worktrees -> enable merge queue on the classic protection rule + decide strict:false. Full research: 829 R4 + the two org-transfer research reports (this session) (2026-08-14)

### obs:unanchored-general-14 — ORG TRANSFER EXECUTED (2026-08-14, owner-authorized): repo is now justsearch-app/justsearch — protec
`kind: defect?` `anchor: none` `seen: 1` `first: 2026-08-14` `last: 2026-08-14`
- [ ] ORG TRANSFER EXECUTED (2026-08-14, owner-authorized): repo is now justsearch-app/justsearch — protection/variables/secrets survived intact (verified vs snapshot), strict=false via API, MERGE QUEUE LIVE via ruleset main-merge-queue (SQUASH, ALLGREEN, id 20851694; classic-protection web form silently failed to persist queue settings twice — ruleset API is the reliable path), gpu-runner re-registered to the new URL (service actions.runner.justsearch-app-justsearch.*), local remotes re-pointed, 6 verified-merged worktrees removed (792/439/424/418/425/423). Kept: 818 (open PR #404), 819 (takeover in flight), 822 (active), a7925a (FE handoff, 827), 4 no-PR branches (795, help-content-accuracy, a990f6, ae5ff7 — unpublished work, owners' call). Old eliasjustus/justsearch path redirects — NEVER create a repo at that name (2026-08-14)

### obs:unanchored-general-15 — MERGE QUEUE LIVE-VALIDATED (2026-08-14, PR #468 first through): merge-group CI + CLA no-op both gree
`kind: follow-up?` `anchor: none` `seen: 1` `first: 2026-08-14` `last: 2026-08-14`
- [ ] MERGE QUEUE LIVE-VALIDATED (2026-08-14, PR #468 first through): merge-group CI + CLA no-op both green on event=merge_group; squash landed with PR_TITLE/PR_BODY exactly (ADR-0045 contract holds under the queue — previously staff-comment-only evidence). Enqueue mechanics: repo setting allow_auto_merge must be true (gh's enqueue path uses enablePullRequestAutoMerge) and gh pr merge takes NO strategy flag under a queue (queue owns SQUASH via ruleset main-merge-queue). Publish flow is now: PR green -> gh pr merge <n> -> queue handles catch-up CI + merge autonomously; the manual update-branch conveyor is retired — `.claude/skills/publish/SKILL.md` + agent-guide §3.7 need the corresponding doc sweep (follow-up) (2026-08-14)

### obs:embeddingcompatibilitycontroller — Embedding attestation counts PARENT EMBEDDING_STATUS==COMPLETED while retrieval serves CHUNK vectors
`kind: environment?` `anchor: modules/worker-services/.../EmbeddingCompatibilityController.java` `seen: 1` `first: 2026-08-14` `last: 2026-08-14`
- [ ] Embedding attestation counts PARENT EMBEDDING_STATUS==COMPLETED while retrieval serves CHUNK vectors, and on BLOCKED_MISMATCH forced reindex nothing re-marks COMPLETED->PENDING before REBUILDING (maybeAutoStartRebuildForBlockedLegacy is legacy-only, EmbeddingCompatibilityController.java:311-312) — so certification can pass on old-model parent successes; pre-existing since 726 removed the queue-depth gate, deferred to 826/F3 per the #470 review (2026-08-14) — `modules/worker-services/.../EmbeddingCompatibilityController.java` (2026-08-14)

### obs:materialize — jseval materializes a `.source_signature` sidecar INTO the watched corpus dir, so every eval index c
`kind: defect?` `anchor: scripts/jseval/jseval/materialize.py` `seen: 1` `first: 2026-08-14` `last: 2026-08-14`
- [ ] jseval materializes a `.source_signature` sidecar INTO the watched corpus dir, so every eval index carries one extra non-corpus document (miracl-fr-2k: 5407 files but spladeDocCount 5408) — dilutes every corpus's doc counts and denominators — `scripts/jseval/jseval/materialize.py` (2026-08-14)

### obs:verify-runtime-config-matrix — verify-runtime-config-matrix fails on main (pre-existing, not from this branch): missing env/sysprop
`kind: environment?` `anchor: scripts/docs/verify-runtime-config-matrix.mjs` `seen: 1` `first: 2026-08-18` `last: 2026-08-18`
- [ ] verify-runtime-config-matrix fails on main (pre-existing, not from this branch): missing env/sysprop pair JUSTSEARCH_APP_VERSION | justsearch.app.version — `scripts/docs/verify-runtime-config-matrix.mjs` (2026-08-18)

### obs:contract-surfaces-v1 — contract-projection gate fails on main (pre-existing, not from this branch): AgentSessionController.
`kind: environment?` `anchor: governance/contract-surfaces.v1.json` `seen: 2` `first: 2026-08-18` `last: 2026-08-18`
- [ ] contract-projection gate fails on main (pre-existing, not from this branch): AgentSessionController.ts imports a generated wire module but is not a declared consumer — `governance/contract-surfaces.v1.json` / `modules/ui-web/src/shell-v0/controllers/AgentSessionController.ts` (2026-08-18)
- [ ] contract-projection gate fails on a pre-existing undeclared consumer: AgentSessionController.ts imports schema-types/agent-sessions-response but is not listed in governance/contract-surfaces.v1.json — `governance/contract-surfaces.v1.json` (2026-08-18)

### obs:registry-snapshot — Vacuous-green gate class: `host-owns-truth` and `runtime-witness` emit a WARNING and return PASS whe
`kind: defect?` `anchor: tmp/consumer-presence/registry-snapshot.json` `seen: 1` `first: 2026-08-18` `last: 2026-08-18`
- [ ] Vacuous-green gate class: `host-owns-truth` and `runtime-witness` emit a WARNING and return PASS when `tmp/consumer-presence/registry-snapshot.json` is absent — which is the normal local state (tmp/ is gitignored; the snapshot only exists after :modules:app-services:test RegistrySnapshotExporterTest runs). With the snapshot generated they evaluate real content (runtime-witness confirms 16 declared operations), so the logic is sound and only the missing-input path is wrong. Contrast `config-surface`, which FAILS with kernel/input-missing on the same condition — same kernel, two treatments. Missing input should not report pass (handles: unreachable-seed-green, green-masked-destructive) — `scripts/governance/gates/host-owns-truth/` (2026-08-18)

### obs:urlsource — Deeplink hash is not honored on backend-less boot: URLSource's boot read loses to the default-surfac
`kind: defect?` `anchor: URLSource.ts` `seen: 1` `first: 2026-08-12` `last: 2026-08-12`
- [ ] Deeplink hash is not honored on backend-less boot: URLSource's boot read loses to the default-surface race when backend fetches are refused; surface mounts only via a post-boot popstate re-dispatch. Reproduced against a bare worktree Vite serve with core.search-v3-surface (and likely affects search-v2 deeplinks under the same conditions) — modules/ui-web/src/shell-v0/router/sources/URLSource.ts:55 (2026-08-12)

### obs:recentsmenu — 4 ui-web gate reds pre-exist on local main @0063a8f4 in files untouched by 822 slice 3: check-theme-
`kind: defect?` `anchor: modules/ui-web/src/shell-v0/components/RecentsMenu.ts` `seen: 4` `first: 2026-08-12` `last: 2026-08-19`
- [ ] 4 ui-web gate reds pre-exist on local main @0063a8f4 in files untouched by 822 slice 3: check-theme-token-closure (3 ghost tokens in RecentsMenu.ts), gen-token-names --check stale (223), strip-token-fallbacks (RecentsMenu.ts 4, ActionLedgerView.ts 2), check-accent-as-text (ActionLedgerView.ts 1 > baseline 0) — `modules/ui-web/src/shell-v0/components/RecentsMenu.ts` (2026-08-12)
- [ ] strip-token-fallbacks --check is RED on main: 6 design-token fallbacks remain in ActionLedgerView.ts (2) and RecentsMenu.ts (4) — `modules/ui-web/src/shell-v0/components/RecentsMenu.ts` (2026-08-14)
- [ ] strip-token-fallbacks --check RED on main, not in expected-state.v1.json: 6 design-token fallbacks remain (ActionLedgerView.ts 2, RecentsMenu.ts 4) — same two files as the known theme-token-closure/accent-as-text reds — `modules/ui-web/src/shell-v0/components/RecentsMenu.ts` (2026-08-14)
- [ ] strip-token-fallbacks --check is RED on main (6 design-token fallbacks) — `modules/ui-web/src/shell-v0/components/RecentsMenu.ts`, `modules/ui-web/src/shell-v0/components/ActionLedgerView.ts`; same two files as the recorded theme-token-closure / accent-as-text reds, so one cleanup would clear three gates (2026-08-19)

### obs:unanchored-general-16 — /api/status reports worker.core.indexedDocuments=0 + indexState DEGRADED + indexServing NOT_READY wh
`kind: defect?` `anchor: none` `seen: 1` `first: 2026-08-12` `last: 2026-08-12`
- [ ] /api/status reports worker.core.indexedDocuments=0 + indexState DEGRADED + indexServing NOT_READY while /api/knowledge/search serves 325 hits from the same index — status-vs-serving mismatch on a fresh worktree dev stack (runId aeb93837), 819-adjacent; reproduced 2026-08-13 (2026-08-12)

### obs:knip-report — governance dead-code gate is red across whole directories on this branch (search-v3 + search-v2 + ap
`kind: environment?` `anchor: tmp/knip-report.json` `seen: 1` `first: 2026-08-13` `last: 2026-08-13`
- [ ] governance dead-code gate is red across whole directories on this branch (search-v3 + search-v2 + api/domains/packs + TaskList show 0 -> N unused exports vs a stale knip baseline) — pre-existing, unrelated to the edits in it — `tmp/knip-report.json` (2026-08-13)

### obs:unanchored-general-17 — ui-web unit runs emit ECONNREFUSED 127.0.0.1:3000 stderr noise (unstubbed relative-URL fetches escap
`kind: defect?` `anchor: none` `seen: 1` `first: 2026-08-13` `last: 2026-08-13`
- [ ] ui-web unit runs emit ECONNREFUSED 127.0.0.1:3000 stderr noise (unstubbed relative-URL fetches escaping to happy-dom's default origin) across many suites incl. untouched views/search-v2 — harmless but obscures real failures — `modules/ui-web/src/shell-v0/views/search-v2` (2026-08-13)

### obs:searchv3view — Search v3: Escape is claimed by the citation pane while a document is open, so an in-progress sideba
`kind: defect?` `anchor: modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.ts` `seen: 6` `first: 2026-08-13` `last: 2026-08-19`
- [ ] Search v3: Escape is claimed by the citation pane while a document is open, so an in-progress sidebar rename loses its cancel key in that state (F8 brief's specified order) — `modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.ts:1085` (2026-08-13)
- [ ] sv3 sidebar resize grip has no visible focus indicator (keyboard-resizable but focus-invisible) — `modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.ts:1990` (2026-08-14)
- [ ] sv3 cold-boot ordering: SearchV3View.restoreLastViewed early-returns when the pointed-at session is already listed, so neither refreshRecord nor the /history companion load runs on that path — pre-existing shape, inherited by 852 S1 — `modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.ts:757-758` (2026-08-19)
- [ ] 852 S2 follow-up (F5): SearchV3View.refreshHistory guards the SESSION but not request ORDER, so two reloads of the same conversation can land out of order and leave the older /history standing — refreshRecord's AbortController is the shape of the fix — `modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.ts:838` (2026-08-19)
- [ ] 852 S2 nit (F8): the four per-turn context-menu entries all carry the same 'history' icon, so the menu's glyph column distinguishes nothing — `modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.ts:945` (2026-08-19)
- [ ] sv3 pane survives a CONVERSATION switch: closePane() runs on New session but not on onSessionSelect or 852-S3's new openBranch/version-pager routes, so a citation pane from conversation A stays open over conversation B (849 slice 3 makes the stale header go null rather than lie, but the document itself is still the old conversation's evidence) — `modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.ts:1315" (2026-08-19)

### obs:unanchored-general-19 — Slice F10 live probe wrote one real conversation into the shared stack (opening question 'probe stan
`kind: defect?` `anchor: none` `seen: 1` `first: 2026-08-13` `last: 2026-08-13`
- [ ] Slice F10 live probe wrote one real conversation into the shared stack (opening question 'probe standard rung'), created while proving the effort payload end-to-end — harmless dev data, delete if the owner tidies conversations (2026-08-13)

### obs:sv3main — sv3 turn can render the ungrounded verdict AND a redundant "0 sources" note for the same fact (compl
`kind: defect?` `anchor: modules/ui-web/src/shell-v0/views/search-v3/Sv3Main.ts` `seen: 3` `first: 2026-08-13` `last: 2026-08-19`
- [ ] sv3 turn can render the ungrounded verdict AND a redundant "0 sources" note for the same fact (completed ask, evidence non-null, zero sources/matches) — `modules/ui-web/src/shell-v0/views/search-v3/Sv3Main.ts:1231-1244` (2026-08-13)
- [ ] Search v3: a completed ask with evidence != null and zero sources says the same fact twice — the degraded-ungrounded verdict AND a '0 sources' note in the same tail row (observed live at the 640px floor); pre-existing, out of F11's scope — `modules/ui-web/src/shell-v0/views/search-v3/Sv3Main.ts:1273` + `components/chat/evidenceProjection.ts:184` (2026-08-13)
- [ ] v3 sources panel never receives sourceCoverage: jf-citations-panel already words 'Retrieved · not examined' (CitationsPanel.ts:66,80,554) but Sv3Main never binds .sourceCoverage — pre-existing v3-vs-UCV honesty gap (847 §2.4 amendment) — `modules/ui-web/src/shell-v0/views/search-v3/Sv3Main.ts:1377` (2026-08-19)

### obs:registry-v1 — Head/worker citation contract is outside every governance gate and internally inconsistent: --gate w
`kind: defect?` `anchor: registry.v1.json` `seen: 1` `first: 2026-08-13` `last: 2026-08-13`
- [ ] Head/worker citation contract is outside every governance gate and internally inconsistent: --gate wire watches only contracts/wire (governance/registry.v1.json:350-367), while chunk_index means 'index into chunks array' at indexing.proto:456 and :502 but 'ordinal within parent doc' at :495 — modules/ipc-common/src/main/proto/indexing.proto:456 (2026-08-13)

### obs:ragcontext — RAGContext token-budget truncation cuts trailing context sections but still emits rag.citations for
`kind: defect?` `anchor: modules/app-services/src/main/java/io/justsearch/app/services/conversation/spi/RAGContext.java` `seen: 4` `first: 2026-08-13` `last: 2026-08-19`
- [ ] RAGContext token-budget truncation cuts trailing context sections but still emits rag.citations for all of them, so the FE can show a source whose text never reached the model — `modules/app-services/src/main/java/io/justsearch/app/services/conversation/spi/RAGContext.java:235` (2026-08-13)
- [ ] done-payload contextBreakdown.retrieved (480) can exceed the Head's computed input budget (460) by ~4% — two different token accountings for one quantity; harmless today but a drifting pair — `modules/app-services/src/main/java/io/justsearch/app/services/conversation/spi/RAGContext.java:218` (2026-08-18)
- [ ] RAG context budgeter overcommits ~2x: both RAGContext call sites use computeSafeInputBudgetTokens(8192,1024) — hardcoded 8192 vs real n_ctx 4096 (InferenceConfig :101,:459) and fixed 1024 reserve ignoring request maxTokens AND the server-side reasoning budget — Thorough (maxTokens 3072, topK 12) passes the budgeter and 400s at llama-server (probe: 5227 vs 4096, deterministic) — `modules/app-services/.../spi/RAGContext.java:251,348` + `core/util/TokenEstimation.java:101-106` (2026-08-18)
- [ ] 849 MEDIUM-3 deeper fix (backend): the streaming citation matcher is handed EVERY kept citation regardless of what the context cut did with it, and scores against chunk text it re-fetches by (parentDocId, chunkIndex) — so a DROPPED passage can be 'matched' against an answer the model wrote without ever seeing it; slice 3 suppresses the contradiction at the presentation layer, the root fix is filtering dropped citations out of the matcher's input — `modules/app-services/src/main/java/io/justsearch/app/services/conversation/spi/RAGContext.java:429` (2026-08-19)

### obs:markdownblock — happy-dom + DOMPurify 3.4.11 silently drop the FIRST element of every sanitized fragment (DOMPurify'
`kind: defect?` `anchor: modules/ui-web/src/shell-v0/components/chat/MarkdownBlock.ts` `seen: 2` `first: 2026-08-13` `last: 2026-08-19`
- [ ] happy-dom + DOMPurify 3.4.11 silently drop the FIRST element of every sanitized fragment (DOMPurify's '<remove></remove>' prefix trick mis-parses), so a markdown unit test whose fixture opens with a heading sees it as bare text — a real-browser render is unaffected — `modules/ui-web/src/shell-v0/components/chat/MarkdownBlock.ts:479` (2026-08-13)
- [ ] decorateCitations flattens DOM text nodes with no separator, so a <br> or an element boundary fuses adjacent words into one token (measured: 'index  \nand' -> 'indexand'; '<summary>More detail</summary>' + para -> 'detailthe') and the key loses its mark — `modules/ui-web/src/shell-v0/components/chat/MarkdownBlock.ts:740` (2026-08-19)

### obs:sv3sessionrow — Sv3SessionRow declares an `inflight` property (dim-on-busy treatment) that the sidebar never sets —
`kind: defect?` `anchor: modules/ui-web/src/shell-v0/views/search-v3/Sv3SessionRow.ts` `seen: 1` `first: 2026-08-14` `last: 2026-08-14`
- [ ] Sv3SessionRow declares an `inflight` property (dim-on-busy treatment) that the sidebar never sets — dead prop since 822 F3 — `modules/ui-web/src/shell-v0/views/search-v3/Sv3SessionRow.ts:428` (2026-08-14)

### obs:tempdoc-scan-test — scripts/ci/lib/tempdoc-scan.test.mjs is not invoked by any CI workflow step or the agent-analytics a
`kind: defect?` `anchor: tempdoc-scan.test.mjs` `seen: 1` `first: 2026-08-14` `last: 2026-08-14`
- [ ] scripts/ci/lib/tempdoc-scan.test.mjs is not invoked by any CI workflow step or the agent-analytics auto-discovery runner (which only walks scripts/agent-analytics/) — a test layer nothing runs, the exact 745 D6 failure mode — `.github/workflows/ci.yml:102` (2026-08-14)

### obs:lambdamartbenchmarktest — LambdaMartBenchmarkTest p50-latency assertion is load-sensitive: fails under concurrent build load (
`kind: defect?` `anchor: modules/app-services/src/integrationTest/java/io/justsearch/app/services/gpl/LambdaMartBenchmarkTest.java` `seen: 1` `first: 2026-08-14` `last: 2026-08-14`
- [ ] LambdaMartBenchmarkTest p50-latency assertion is load-sensitive: fails under concurrent build load (6.54ms vs 5ms threshold), passes in isolation — a wall-clock threshold in a non-isolated suite — `modules/app-services/src/integrationTest/java/io/justsearch/app/services/gpl/LambdaMartBenchmarkTest.java` (2026-08-14)

### obs:watchedrootscancollectiontest — WatchedRootScanCollectionTest$ProductionWireForwarding fails on Windows with 'Failed to delete temp
`kind: defect?` `anchor: modules/app-services/src/test/java/io/justsearch/app/services/worker/WatchedRootScanCollectionTest.java` `seen: 1` `first: 2026-08-14` `last: 2026-08-14`
- [ ] WatchedRootScanCollectionTest$ProductionWireForwarding fails on Windows with 'Failed to delete temp directory ... <root>, data' (JUnit @TempDir teardown IOException, not an assertion) — seen under concurrent Gradle load; assertions passed — `modules/app-services/src/test/java/io/justsearch/app/services/worker/WatchedRootScanCollectionTest.java` (2026-08-14)

### obs:sv3-ask — Search v3 Thorough rung (topK 12 + maxTokens 3072) overflows an n_ctx=4096 llama-server: llama-serve
`kind: defect?` `anchor: modules/ui-web/src/shell-v0/views/search-v3/sv3-ask.ts` `seen: 2` `first: 2026-08-14` `last: 2026-08-14`
- [ ] Search v3 Thorough rung (topK 12 + maxTokens 3072) overflows an n_ctx=4096 llama-server: llama-server returns HTTP 400 ('request (5878 tokens) exceeds the available context size') and the turn surfaces as a bare LLM_ERROR with no explanation of the context overflow — the rung's passage count and token ceiling are not checked against the running server's n_ctx — `modules/ui-web/src/shell-v0/views/search-v3/sv3-ask.ts:139-148` (2026-08-14)
- [ ] Effort-rung parameters are not ctx-aware: Thorough (topK 12 + maxTokens 3072) can build a request larger than the running llama-server's n_ctx, and nothing checks passage count x token ceiling against /props n_ctx before dispatch — the honest overflow message now surfaces (835 §10f) but the request is still built too large — `modules/ui-web/src/shell-v0/views/search-v3/sv3-ask.ts:139-148` (2026-08-14)

### obs:schemas — A runHeadlessEval Head+Worker started outside the dev-runner is invisible to quick_health (lease onl
`kind: follow-up?` `anchor: scripts/dev/justsearch-dev-mcp/schemas.mjs` `seen: 1` `first: 2026-08-14` `last: 2026-08-14`
- [ ] A runHeadlessEval Head+Worker started outside the dev-runner is invisible to quick_health (lease only knows runs it started), so a 'free' verdict can precede a 100%-GPU neighbour — contaminated a measurement round; consider a process/nvidia-smi probe in quick_health — `scripts/dev/justsearch-dev-mcp/schemas.mjs:89-117` (2026-08-14)

### obs:unanchored-general-20 — Pushes to an open PR did not trigger the CI workflow (only CLA Assistant ran) despite ci.yml declari
`kind: defect?` `anchor: none` `seen: 1` `first: 2026-08-14` `last: 2026-08-14`
- [ ] Pushes to an open PR did not trigger the CI workflow (only CLA Assistant ran) despite ci.yml declaring a bare 'on: pull_request:' — three synchronize pushes produced no CI run, so a PR's green checks can silently predate its current head; had to dispatch ci.yml manually to validate — `.github/workflows/ci.yml:9-13` (2026-08-14)

### obs:agentsteprunner — Agent runs persist state="LLM_STREAMING" (AgentStepRunner.java:449) but LifecycleState has no such c
`kind: lesson?` `anchor: modules/app-agent/src/main/java/io/justsearch/agent/AgentStepRunner.java` `seen: 1` `first: 2026-08-14` `last: 2026-08-14`
- [ ] Agent runs persist state="LLM_STREAMING" (AgentStepRunner.java:449) but LifecycleState has no such constant, so LifecycleState.parse maps it to READY_FOR_LLM — the tempdoc-834 R7 downgrade hazard is live in the persisted vocabulary today; also makes a gate-parked run report resumable=false — `modules/app-agent/src/main/java/io/justsearch/agent/AgentStepRunner.java:449` (2026-08-14)

### obs:installcompleteness — test-efficacy gate: install-completeness no-coverage rose 0 to 1 — packagesWithMissingRequiredFiles(
`kind: environment?` `anchor: modules/app-services/src/main/java/io/justsearch/app/services/ai/install/InstallCompleteness.java` `seen: 1` `first: 2026-08-14` `last: 2026-08-14`
- [ ] test-efficacy gate: install-completeness no-coverage rose 0 to 1 — packagesWithMissingRequiredFiles() arrived with #413 without a covering test; pre-existing on main, unrelated to 836 — `modules/app-services/src/main/java/io/justsearch/app/services/ai/install/InstallCompleteness.java:211` (2026-08-14)

### obs:build-counter — build-counter accumulated 6 false failures from successful gradle builds piped through `| grep | hea
`kind: defect?` `anchor: scripts/agent-analytics/hooks/build-counter.mjs` `seen: 1` `first: 2026-08-14` `last: 2026-08-14`
- [ ] build-counter accumulated 6 false failures from successful gradle builds piped through `| grep | head` — the pipe's exit code (1 on no-match) is what the hook records, blocking further builds; the pipe-mask-hint covers the reporting half but not the counter — `scripts/agent-analytics/hooks/build-counter.mjs` (2026-08-14)

### obs:records — search-v2 records.ts groundedSentencesLabel is a third phrasing of the grounding-coverage line, inde
`kind: defect?` `anchor: modules/ui-web/src/shell-v0/views/search-v2/records.ts` `seen: 1` `first: 2026-08-17` `last: 2026-08-17`
- [ ] search-v2 records.ts groundedSentencesLabel is a third phrasing of the grounding-coverage line, independent of evidenceProjection.groundingCoverage — `modules/ui-web/src/shell-v0/views/search-v2/records.ts:435` (2026-08-17)

### obs:ssestreamchannel — SseStreamChannel.publish assigns the seq before taking any lock, so two concurrent publishers can ap
`kind: defect?` `anchor: modules/app-observability/src/main/java/io/justsearch/app/observability/stream/SseStreamChannel.java` `seen: 1` `first: 2026-08-17` `last: 2026-08-17`
- [ ] SseStreamChannel.publish assigns the seq before taking any lock, so two concurrent publishers can append to the ring out of seq order (framesSince then replays unsorted) — `modules/app-observability/src/main/java/io/justsearch/app/observability/stream/SseStreamChannel.java:116` (2026-08-17)

### obs:knowledgeserverbootstrap — Worker fatal-reason marker is never read on the supervised-restart path: killing a running Worker ra
`kind: environment?` `anchor: modules/app-services/src/main/java/io/justsearch/app/services/worker/KnowledgeServerBootstrap.java` `seen: 1` `first: 2026-08-18` `last: 2026-08-18`
- [ ] Worker fatal-reason marker is never read on the supervised-restart path: killing a running Worker races checkHealth's (current==READY) branch against the supervisor's onRecovering, and the supervisor won 3 of 4 live trials with the marker left unread on disk — so worker.index_corrupt effectively only fires at Head bootstrap, not for a mid-session corrupt-index crash (pre-existing, predates 837 S3) — `modules/app-services/src/main/java/io/justsearch/app/services/worker/KnowledgeServerBootstrap.java:645` (2026-08-18)

### obs:vite-config — Vite dev proxy never propagates client disconnect to the backend: proxyRes.pipe(res) with no res.on(
`kind: defect?` `anchor: modules/ui-web/vite.config.js` `seen: 2` `first: 2026-08-18` `last: 2026-08-18`
- [ ] Vite dev proxy never propagates client disconnect to the backend: proxyRes.pipe(res) with no res.on('close') teardown leaves the proxy->backend SSE socket open forever (measured: 471s after client death, SseClient.onClose still not fired) — `modules/ui-web/vite.config.js:155` (2026-08-18)
- [ ] Stale comment references retired ui-bundle ratchet gate (removed tempdoc 634) as if still active — `modules/ui-web/vite.config.js:250` ("declared emergency-override (gates/ui-bundle/.changesets/)") and `.gitignore:47-48` ("ui-bundle enforcer's metric-computation path") (2026-08-18)

### obs:adversarialcorpusingestiontest — worker-services full-suite flakes under parallel load: AdversarialCorpusIngestionTest 'directory (no
`kind: environment?` `anchor: modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/AdversarialCorpusIngestionTest.java` `seen: 1` `first: 2026-08-18` `last: 2026-08-18`
- [ ] worker-services full-suite flakes under parallel load: AdversarialCorpusIngestionTest 'directory (non-regular source) is admission-skipped' and WorkerMethvinWatcherTest.createEventCarriesTheFilesRealSizeToTheQueue each failed once in back-to-back `gradlew test` runs and passed in isolation — filesystem/timing-sensitive, unrelated to the changed modules — `modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/AdversarialCorpusIngestionTest.java` (2026-08-18)

### obs:paths — justsearch_dev_start resolves an absolute dataDir under the MAIN repo root to a repo-relative path,
`kind: defect?` `anchor: scripts/dev/justsearch-dev-mcp/paths.mjs` `seen: 1` `first: 2026-08-18` `last: 2026-08-18`
- [ ] justsearch_dev_start resolves an absolute dataDir under the MAIN repo root to a repo-relative path, which the dev-runner then re-resolves against the worktree when distFrom is set — pointing at a sibling worktree's .dev-data silently created a NEW empty data dir at <worktree>/.claude/worktrees/<sibling>/... instead (had to junction it back) — `scripts/dev/justsearch-dev-mcp/paths.mjs:48` (2026-08-18)

### obs:agentcontroller — Legacy raw-Context agent SSE route never evicts a dead initiating observer: writeOrEvict's CLIENT_GO
`kind: environment?` `anchor: modules/ui/src/main/java/io/justsearch/ui/api/AgentController.java` `seen: 1` `first: 2026-08-18` `last: 2026-08-18`
- [ ] Legacy raw-Context agent SSE route never evicts a dead initiating observer: writeOrEvict's CLIENT_GONE did not fire across 3 iterations / ~2.5 min with the client gone, so observerCount stays >0 and the WATCH zero-observer park is unreachable there; the heartbeat on that route uses the NON-evicting writeEvent by design (834 §8). Pre-existing — the eviction trigger is mechanism-identical pre/post the 834 hub deletion — `modules/ui/src/main/java/io/justsearch/ui/api/AgentController.java:137` (2026-08-18)

### obs:baseline — governance kernel `dead-code` gate is red on a stale ratchet baseline — `gates/dead-code/baseline.tx
`kind: defect?` `anchor: gates/dead-code/baseline.txt` `seen: 2` `first: 2026-08-18` `last: 2026-08-18`
- [ ] governance kernel `dead-code` gate is red on a stale ratchet baseline — `gates/dead-code/baseline.txt` is dated 2026-07-16 and predates the whole search-v3 view set, so ~15 untouched files report silent-growth — `gates/dead-code/baseline.txt:1` (2026-08-18)
- [ ] dead-code (knip) gate is RED on main: gates/dead-code/baseline.txt is pinned at 2026-07-16 and 20 of 23 findings are drift in untouched files (search-v2/*, search-v3/*, TaskList.ts, knowledge-search-response.ts, packs.ts's long-dead v1 install fns) — needs a baseline refresh, and a per-PR declared-growth changeset would wrongly absorb all of it (growthCovered is PR-wide) — `gates/dead-code/baseline.txt` (2026-08-18)

### obs:prepare-worktree — hook-integrity gate (new in #475) is RED on any worktree whose gitignored `.claude/settings.local.js
`kind: defect?` `anchor: scripts/dev/prepare-worktree.cjs` `seen: 1` `first: 2026-08-18` `last: 2026-08-18`
- [ ] hook-integrity gate (new in #475) is RED on any worktree whose gitignored `.claude/settings.local.json` was seeded before #475 — 6 unwired-hook findings for hooks that ARE in the tracked .example; prepare-worktree.cjs leaves an existing local settings file as-is, so it never picks up newly-added hooks — `scripts/dev/prepare-worktree.cjs` (2026-08-18)

### obs:agent-sessions-response — contract-projection/undeclared-consumer matches a generated-module mention in a DOC COMMENT, not onl
`kind: defect?` `anchor: generated/schema-types/agent-sessions-response.ts` `seen: 1` `first: 2026-08-18` `last: 2026-08-18`
- [ ] contract-projection/undeclared-consumer matches a generated-module mention in a DOC COMMENT, not only real imports — AgentSessionController.ts:170 has cited `generated/schema-types/agent-sessions-response.ts` in prose since before #478 and is flagged as an undeclared consumer though it imports nothing; keeps the gate red for a non-fact — `modules/ui-web/src/shell-v0/controllers/AgentSessionController.ts:170` (2026-08-18)

### obs:check-controls-a11y — check-controls-a11y RED on main: title-on-disabled button in UnifiedChatView (gate reports shell-v0/
`kind: environment?` `anchor: check-controls-a11y` `seen: 1` `first: 2026-08-18` `last: 2026-08-18`
- [ ] check-controls-a11y RED on main: title-on-disabled button in UnifiedChatView (gate reports shell-v0/views/UnifiedChatView.ts:2143, baseline 0) — not in expected-state.v1.json alongside the other pre-existing ui-web gate reds (2026-08-18)

### obs:onlinemodeops — Selection-stall root cause (D5 diagnosis): one-shot process-wide wedge — all streaming chats share E
`kind: defect?` `anchor: OnlineModeOps.java` `seen: 2` `first: 2026-08-18` `last: 2026-08-19`
- [ ] Selection-stall root cause (D5 diagnosis): one-shot process-wide wedge — all streaming chats share Executors.newSingleThreadExecutor + onlineRequestLock held across onChunk/onReasoning callbacks + unbounded latch.await; one non-returning stream task parks the LLM thread forever and later dispatches emit rag.citations then queue. Two surviving park candidates (body-read-never-terminates under budget-0 era responses vs cross-thread SSE write park); discriminator = jcmd thread dump at stall. NOTE: observed pre-#464 (budget 0); re-verify presentation under the 512 default before fixing — `OnlineModeOps.java:74-80,597,651-732` + `ConversationEngine.java:531,564` (2026-08-18)
- [ ] RAGContext's structure-blind truncation flattens all newlines and destroys SECTION_SEPARATOR + '[n] label' headers, so when the safety net fires OnlineModeOps.formatContextAsNumberedPassages parses no header and silently falls back to its running counter — the exact 'second, independently-derived numbering could disagree with sources[n-1]' its own javadoc warns against — `modules/app-inference/src/main/java/io/justsearch/app/inference/OnlineModeOps.java:1129` (2026-08-19)

### obs:queryrewriteinjector — Latent cross-request deadlock shape: QueryRewriteInjector.chatCompletion (on core.rag-ask) takes the
`kind: defect?` `anchor: QueryRewriteInjector.java` `seen: 1` `first: 2026-08-18` `last: 2026-08-18`
- [ ] Latent cross-request deadlock shape: QueryRewriteInjector.chatCompletion (on core.rag-ask) takes the same onlineRequestLock the streaming path holds across callbacks — injector-phase LLM call queuing behind a wedged/slow stream from another dispatch — `QueryRewriteInjector.java:92` + `OnlineModeOps.java:73,597` (2026-08-18)

### obs:840-model-download-restructure — check-tempdoc-numbers reports a live #840 collision between 840-model-download-restructure.md (4 wor
`kind: environment?` `anchor: docs/tempdocs/840-model-download-restructure.md` `seen: 1` `first: 2026-08-18` `last: 2026-08-18`
- [ ] check-tempdoc-numbers reports a live #840 collision between 840-model-download-restructure.md (4 worktrees) and 840-retire-model-registry-mirror.md (gates/ssot-catalog-sync changeset in worktree 840-download-restructure) — pre-existing, blocks the gate for any agent running it — `docs/tempdocs/840-model-download-restructure.md` (2026-08-18)

### obs:unanchored-error-2 — CI 'License and notices' lane failed on main run 32187508386 (#482 push, 2026-08-18) with 'Error res
`kind: follow-up?` `anchor: none` `seen: 1` `first: 2026-08-19` `last: 2026-08-19`
- [ ] CI 'License and notices' lane failed on main run 32187508386 (#482 push, 2026-08-18) with 'Error resolving plugin com.gradle.develocity:4.5.0 / Gradle Central Plugin Repository disabled due to earlier error' — transient plugin-portal outage, next main push run green; if seen again consider mirroring/caching the plugin or continue-on-error classification for this failure class — .github/workflows/ci.yml (license-and-notices) (2026-08-19)

### obs:test-ui-proportion-gate — Pre-existing jseval pytest failures unrelated to chunk-completeness work: 38 failures across test_ui
`kind: environment?` `anchor: test_ui_proportion_gate.py` `seen: 1` `first: 2026-08-19` `last: 2026-08-19`
- [ ] Pre-existing jseval pytest failures unrelated to chunk-completeness work: 38 failures across test_ui_proportion_gate.py, test_corpus_inject.py, test_corpus_schema.py, test_leak_gate.py, test_union_recall_gate.py, test_utility_comparison.py (env/fixture drift, not touched by this session) — `scripts/jseval/tests/` (2026-08-19)

### obs:corpus-inject — corpus_inject._cross_process_assembly fails on Python 3.13: it spawns python scripts/jseval/jseval/c
`kind: lesson?` `anchor: corpus_inject.py` `seen: 1` `first: 2026-08-19` `last: 2026-08-19`
- [ ] corpus_inject._cross_process_assembly fails on Python 3.13: it spawns python scripts/jseval/jseval/corpus_inject.py, putting the package dir on sys.path[0] where jseval/types.py shadows stdlib types -> ImportError: cannot import name 'GenericAlias', failing the determinism check gating every 707/748-family assembly; workaround PYTHONSAFEPATH=1; fix is spawning via -m or setting PYTHONSAFEPATH in the spawn env — scripts/jseval/jseval/corpus_inject.py (2026-08-19, 748 G.3 campaign) (2026-08-19)

### obs:ratchet-kernel — jseval relevance-gate --data-dir resolves the LATEST run in eval-results/ regardless of --dataset: a
`kind: defect?` `anchor: scripts/jseval/jseval/ratchet_kernel.py` `seen: 1` `first: 2026-08-19` `last: 2026-08-19`
- [ ] jseval relevance-gate --data-dir resolves the LATEST run in eval-results/ regardless of --dataset: a dir holding both a scifact and an enron-qa run reported the scifact nDCG as 'current' under dataset mixed/enron-qa and FAILED the gate — a silent wrong-dataset comparison, not an error — `scripts/jseval/jseval/ratchet_kernel.py` (2026-08-19)

### obs:resolvedconfigbuilder — PRODUCT truthfulness gap found by F-052's campaign: justsearch.rerank.deadline_ms defaults to 200ms 
`kind: defect?` `anchor: ResolvedConfigBuilder.java` `seen: 1` `first: 2026-08-19` `last: 2026-08-19`
- [ ] PRODUCT truthfulness gap found by F-052's campaign: justsearch.rerank.deadline_ms defaults to 200ms while the incumbent CE's stage p50 on long-doc corpora is ~165ms — real queries silently lose the CE under jitter (rerank RPC returns skipped, delivered in fusion order) with NO reason code, no searchTrace degradation signal, no rag_meta hint; only per-hit judgeSignals.ce_score=null shows it. Owner decision: raise the deadline vs surface the skip as a SearchReasonCode (or both) — do not change unilaterally, latency tradeoff — ResolvedConfigBuilder.java:1262 / EnvRegistry.java:747 / KnowledgeSearchEngine rerank call (2026-08-19, F-052) (2026-08-19)

### obs:test-leak-gate — jseval leak-gate + union-recall-gate pointer tests still pin release_id 715-rebaseline-2026-07-16 bu
`kind: defect?` `anchor: scripts/jseval/tests/test_leak_gate.py` `seen: 1` `first: 2026-08-19` `last: 2026-08-19`
- [ ] jseval leak-gate + union-recall-gate pointer tests still pin release_id 715-rebaseline-2026-07-16 but release.v1.json is now 832-rebaseline-2026-08-14 — both fail — `scripts/jseval/tests/test_leak_gate.py:204`, `scripts/jseval/tests/test_union_recall_gate.py:199` (2026-08-19)

### obs:aiinstallcontroller — AiInstallController carries 5 unused imports predating tempdoc 840 (BrainInstallService, OnlineAiSer
`kind: defect?` `anchor: modules/ui/src/main/java/io/justsearch/ui/api/AiInstallController.java` `seen: 1` `first: 2026-08-17` `last: 2026-08-17`
- [ ] AiInstallController carries 5 unused imports predating tempdoc 840 (BrainInstallService, OnlineAiService, EnterprisePolicyService, UiSettingsStore, java.util.Map) — surfaced while decoupling the controller onto the app-api interface (840 B3); only the self-orphaned KnowledgeServerBootstrap import was removed — `modules/ui/src/main/java/io/justsearch/ui/api/AiInstallController.java:5-14` (2026-08-17)

### obs:updater — In-app updater buffers the ENTIRE NSIS installer in memory before staging (`let bytes = update.downl
`kind: defect?` `anchor: modules/shell/src-tauri/src/updater.rs` `seen: 1` `first: 2026-08-18` `last: 2026-08-18`
- [ ] In-app updater buffers the ENTIRE NSIS installer in memory before staging (`let bytes = update.download(...)` returns Vec<u8>); for a multi-hundred-MB installer that is a large transient allocation. Not fixable in isolation: tauri-plugin-updater's `Update::download()` hands the caller only `chunk.len()`, never the chunk bytes, and accumulates internally — streaming to disk means bypassing the library method and reimplementing the HTTP GET + Ed25519/pubkey verification path. Documented while wiring download progress (840 U7); left deliberately untouched — `modules/shell/src-tauri/src/updater.rs:370-373` (2026-08-18)

### obs:workermethvinwatchertest — Flaky: WorkerMethvinWatcherTest.createEventCarriesTheFilesRealSizeToTheQueue failed once in a full .
`kind: environment?` `anchor: modules/worker-services/src/test/java/io/justsearch/indexerworker/watcher/WorkerMethvinWatcherTest.java` `seen: 1` `first: 2026-08-18` `last: 2026-08-18`
- [ ] Flaky: WorkerMethvinWatcherTest.createEventCarriesTheFilesRealSizeToTheQueue failed once in a full ./gradlew test run and passed on isolated re-run and on a prior full run (filesystem-watcher timing) — `modules/worker-services/src/test/java/io/justsearch/indexerworker/watcher/WorkerMethvinWatcherTest.java:93` (2026-08-18)

### obs:schemamismatchstatuscontracttest — Flaky integration test: SchemaMismatchStatusContractTest.setup() can time out after 30s on Jetty/Jav
`kind: environment?` `anchor: modules/ui/src/test/java/io/justsearch/ui/api/SchemaMismatchStatusContractTest.java` `seen: 1` `first: 2026-08-18` `last: 2026-08-18`
- [ ] Flaky integration test: SchemaMismatchStatusContractTest.setup() can time out after 30s on Jetty/Javalin server start under load (one-off; passed on re-run) — `modules/ui/src/test/java/io/justsearch/ui/api/SchemaMismatchStatusContractTest.java:110` (2026-08-18)

### obs:ai-install-status — dead-code gate: generated `aiInstallStatusSchema` (Zod validator, tempdoc 840 Phase 4) has no consum
`kind: defect?` `anchor: modules/ui-web/src/api/generated/schema-types/ai-install-status.ts` `seen: 1` `first: 2026-08-18` `last: 2026-08-18`
- [ ] dead-code gate: generated `aiInstallStatusSchema` (Zod validator, tempdoc 840 Phase 4) has no consumer — 0 to 1 unused export over the pinned baseline; the generated Zod half is emitted but never used to validate the wire payload — `modules/ui-web/src/api/generated/schema-types/ai-install-status.ts:69` (2026-08-18)

### obs:installcomponents — installComponents.ts module docstring claims its shapes feed three surfaces (Brain panel, System Sel
`kind: defect?` `anchor: modules/ui-web/src/shell-v0/state/installComponents.ts` `seen: 1` `first: 2026-08-18` `last: 2026-08-18`
- [ ] installComponents.ts module docstring claims its shapes feed three surfaces (Brain panel, System Self-View, task tray) but only BrainSurface.ts imports composeComponentGroups/composeComponentRow — `modules/ui-web/src/shell-v0/state/installComponents.ts:11` (2026-08-18)

### obs:13-ai-setup-and-verification — docs/explanation/13 still points the AI-install backend service at modules/ui/src/main/java/io/justs
`kind: defect?` `anchor: docs/explanation/13-ai-setup-and-verification.md` `seen: 1` `first: 2026-08-18` `last: 2026-08-18`
- [ ] docs/explanation/13 still points the AI-install backend service at modules/ui/src/main/java/io/justsearch/ui/ai/install/AiInstallService.java; the class lives in app-services (modules/app-services/src/main/java/io/justsearch/app/services/ai/install/AiInstallService.java) — stale since the service moved out of ui — `docs/explanation/13-ai-setup-and-verification.md:102` (2026-08-18)

### obs:summarizationstyle — SummarizationStyle carries unscoped 'provided text' framing like the RAGQAStyle access-denial defect
`kind: defect?` `anchor: modules/app-services/src/main/java/io/justsearch/app/services/conversation/spi/SummarizationStyle.java` `seen: 1` `first: 2026-08-18` `last: 2026-08-18`
- [ ] SummarizationStyle carries unscoped 'provided text' framing like the RAGQAStyle access-denial defect (845) but has no say-so/access clause; left unchanged as an unprobed different shape — re-check if summarize ever denies access — `modules/app-services/src/main/java/io/justsearch/app/services/conversation/spi/SummarizationStyle.java:30` (2026-08-18)

### obs:tokenestimation — TokenEstimation.truncateIfNeeded re-inflates any cap below MIN_BUDGET back to 256 (`cap = Math.max(M
`kind: defect?` `anchor: modules/core/src/main/java/io/justsearch/core/util/TokenEstimation.java` `seen: 1` `first: 2026-08-18` `last: 2026-08-18`
- [ ] TokenEstimation.truncateIfNeeded re-inflates any cap below MIN_BUDGET back to 256 (`cap = Math.max(MIN_BUDGET, maxContextTokens)`), so a 0/near-0 input budget still yields ~256 tokens of prompt; residual after 845 fixed the budgeter itself — `modules/core/src/main/java/io/justsearch/core/util/TokenEstimation.java:124` (2026-08-18)

### obs:agentloopservice — AgentLoopService unboxes configuredContextTokens() without a null check; both context accessors are 
`kind: defect?` `anchor: modules/app-agent/src/main/java/io/justsearch/agent/AgentLoopService.java` `seen: 1` `first: 2026-08-18` `last: 2026-08-18`
- [ ] AgentLoopService unboxes configuredContextTokens() without a null check; both context accessors are nullable Integer defaults, so a non-Online runtime NPEs on the token-budget line — `modules/app-agent/src/main/java/io/justsearch/agent/AgentLoopService.java:446` (2026-08-18)

### obs:conversationengine — Quick rung (maxTokens 512) + thinking ON returns an EMPTY answer — reasoning consumed all 512 comple
`kind: defect?` `anchor: modules/app-services/src/main/java/io/justsearch/app/services/conversation/ConversationEngine.java` `seen: 3` `first: 2026-08-18` `last: 2026-08-19`
- [ ] Quick rung (maxTokens 512) + thinking ON returns an EMPTY answer — reasoning consumed all 512 completion tokens, 0 answer chars (tempdoc 835 B3 regression reproduced live 2026-08-18, arm A1) — `modules/app-services/src/main/java/io/justsearch/app/services/conversation/ConversationEngine.java:72` (2026-08-18)
- [ ] claimMatches persistence hop is stringly-keyed and invisible to the execution-surface scan — `ConversationEngine.java:846-865` (tempdoc 847 design pass) (2026-08-18)
- [ ] 848 scope limit: the ANSWER plane persists no assistant record (so no reasoning) when a turn fails — streamLlm rethrows before the reasoning flush and the caller emitError-returns before persistedAssistant; the agent plane keeps it via the journal fold (D-7). Closing it means persisting a partial assistant turn on error — a turn-semantics change — `modules/app-services/src/main/java/io/justsearch/app/services/conversation/ConversationEngine.java:644` (2026-08-19)

### obs:ragqastyle — Thinking-mode 'no access to indexed files' denials are prompt-scoped, not retrieval: identical bytes
`kind: defect?` `anchor: modules/app-services/.../spi/RAGQAStyle.java` `seen: 2` `first: 2026-08-18` `last: 2026-08-18`
- [ ] Thinking-mode 'no access to indexed files' denials are prompt-scoped, not retrieval: identical bytes of context in all arms (probe, controls isolated enableThinking alone); the reasoning pass infers the 'provided documents' are not the user's files because RAGQAStyle never says they are, then obeys its say-so clause — `modules/app-services/.../spi/RAGQAStyle.java:27-31` (2026-08-18)
- [ ] Residual from 845: a DIRECT capability question ('can you access my files?') still yields 'I cannot directly access your files' post-prompt-fix — confounded by the product-docs corpus (model reasons product-vs-assistant split); deconfound with a non-JustSearch corpus before any further prompt wording change — `modules/app-services/.../spi/RAGQAStyle.java:25` (tempdoc 845 A4) (2026-08-18)

### obs:check-shape-handler-regen — check-shape-handler-regen is an npm alias wired into no workflow — regen drift possible — `modules/u
`kind: defect?` `anchor: check-shape-handler-regen` `seen: 1` `first: 2026-08-18` `last: 2026-08-18`
- [ ] check-shape-handler-regen is an npm alias wired into no workflow — regen drift possible — `modules/ui-web/package.json` (tempdoc 847 design pass) (2026-08-18)

### obs:check-offline-single-sense — check-offline-single-sense allow-list is keyed by exact file path, so renaming/moving an allow-liste
`kind: defect?` `anchor: scripts/ci/check-offline-single-sense.mjs` `seen: 1` `first: 2026-08-19` `last: 2026-08-19`
- [ ] check-offline-single-sense allow-list is keyed by exact file path, so renaming/moving an allow-listed file silently orphans the entry instead of failing — a gate that degrades quietly under refactor — `scripts/ci/check-offline-single-sense.mjs:50` (2026-08-19)

### obs:model-manifest — App runtime rewrites the TRACKED models/onnx/gte-multilingual-base/model_manifest.json in the main c
`kind: defect?` `anchor: model_manifest.json` `seen: 1` `first: 2026-08-19` `last: 2026-08-19`
- [ ] App runtime rewrites the TRACKED models/onnx/gte-multilingual-base/model_manifest.json in the main checkout (capabilities block deleted; likely ingest-time embedding-compatibility resolution) — working-tree pollution that blocks pulls and destroys curated capability facts; runtime rewrites belong in dataDir, not the repo copy (2026-08-19)

### obs:fileconversationstore — Pre-existing: FileConversationStore's decrypt-failure placeholder writes role:"locked" into the hist
`kind: environment?` `anchor: modules/app-services/src/main/java/io/justsearch/app/services/conversation/FileConversationStore.java` `seen: 1` `first: 2026-08-19` `last: 2026-08-19`
- [ ] Pre-existing: FileConversationStore's decrypt-failure placeholder writes role:"locked" into the history, and the new buildLlmInput projection passes any role through — a locked message reaches the model as {role:'locked', content:''}. chatTurn filters it for display, but the LLM-input side has no role whitelist — `modules/app-services/src/main/java/io/justsearch/app/services/conversation/FileConversationStore.java:149-155` (2026-08-19)

### obs:unanchored-general-22 — GitHub pull_request synchronize events intermittently do not trigger the CI workflow (twice today: P
`kind: defect?` `anchor: none` `seen: 1` `first: 2026-08-19` `last: 2026-08-19`
- [ ] GitHub pull_request synchronize events intermittently do not trigger the CI workflow (twice today: PR 493 e5dbc68f, PR 495 5186fa9e) — agents covered with explicit workflow_dispatch; check trigger config or GitHub-side delivery — `.github/workflows/ci.yml` (2026-08-19)

### obs:streamingcitationmatcher — StreamingCitationMatcher's mid-stream draft segmentation still splits raw markdown as prose (Locale.
`kind: defect?` `anchor: modules/app-services/src/main/java/io/justsearch/app/services/conversation/spi/StreamingCitationMatcher.java` `seen: 1` `first: 2026-08-19` `last: 2026-08-19`
- [ ] StreamingCitationMatcher's mid-stream draft segmentation still splits raw markdown as prose (Locale.ENGLISH, same fusion 847 S5 removed on the scored path) — `modules/app-services/src/main/java/io/justsearch/app/services/conversation/spi/StreamingCitationMatcher.java:225` (2026-08-19)

### obs:unanchored-general-31 — resolveAndLockAll adds kotlin-metadata-jvm:2.3.0=dependencyAnalysisKotlinMetadataClasspath to every 
`kind: environment?` `anchor: none` `seen: 1` `first: 2026-08-19` `last: 2026-08-19`
- [ ] resolveAndLockAll adds kotlin-metadata-jvm:2.3.0=dependencyAnalysisKotlinMetadataClasspath to every module lockfile — pre-existing skew on main, rides along in any PR that regenerates locks — `modules/core/gradle.lockfile` (2026-08-19)

### obs:unanchored-general-34 — gradlew generateLicenseReport fails on Gradle 9 (unsafe cross-project resolution of :modules:adapter
`kind: defect?` `anchor: none` `seen: 1` `first: 2026-08-19` `last: 2026-08-19`
- [ ] gradlew generateLicenseReport fails on Gradle 9 (unsafe cross-project resolution of :modules:adapters-lucene:runtimeClasspath); only checkLicense --no-parallel produces the report locally — `.github/workflows/ci.yml:314` (2026-08-19)

### obs:runtimeactivationservice — RuntimeActivationService.resolveVariantsRoot resolves from the repo root and ignores JUSTSEARCH_SERV
`kind: lesson?` `anchor: modules/app-services/src/main/java/io/justsearch/app/services/ai/runtime/RuntimeActivationService.java` `seen: 1` `first: 2026-08-19` `last: 2026-08-19`
- [ ] RuntimeActivationService.resolveVariantsRoot resolves from the repo root and ignores JUSTSEARCH_SERVER_EXE, so a worktree dev stack reports RUNTIME_VARIANT_NOT_INSTALLED even when the dev-runner resolved the shared main-checkout cuda12 exe; workaround is junctioning modules/ui/native-bin/llama-server — `modules/app-services/src/main/java/io/justsearch/app/services/ai/runtime/RuntimeActivationService.java:1687` (2026-08-19)

### obs:markdownhighlightruntime — 846 UX audit: main checkout's modules/ui-web/node_modules was missing `highlight.js` (declared in pa
`kind: defect?` `anchor: modules/ui-web/src/shell-v0/components/markdown/markdownHighlightRuntime.ts` `seen: 1` `first: 2026-08-19` `last: 2026-08-19`
- [ ] 846 UX audit: main checkout's modules/ui-web/node_modules was missing `highlight.js` (declared in package.json+lock since PR #489), so the dev stack served a Vite 'Failed to resolve import' error overlay that blocked the whole app — post-merge `npm install` drift, not a code defect — `modules/ui-web/src/shell-v0/components/markdown/markdownHighlightRuntime.ts:17` (2026-08-19)

### obs:sv3-tokens-css — UX audit: Search v3 window's light palette is unwired — sv3-tokens.css.ts puts the whole light set b
`kind: defect?` `anchor: modules/ui-web/src/shell-v0/views/search-v3/sv3-tokens.css.ts` `seen: 1` `first: 2026-08-19` `last: 2026-08-19`
- [ ] UX audit: Search v3 window's light palette is unwired — sv3-tokens.css.ts puts the whole light set behind `:host([theme='light'])` and nothing sets that attribute from data-theme, so app-light leaves v3 painting dark inks over light global tokens (measured: inline code chip 1.08:1, mode toggle 2.05:1 in the reading pane) — `modules/ui-web/src/shell-v0/views/search-v3/sv3-tokens.css.ts:333` (2026-08-19)

### obs:ui-proportion-baseline-v1 — UX audit: governance/ui-proportion-baseline.v1.json tracks only two chat-surface elements, so ui-pro
`kind: defect?` `anchor: governance/ui-proportion-baseline.v1.json` `seen: 1` `first: 2026-08-19` `last: 2026-08-19`
- [ ] UX audit: governance/ui-proportion-baseline.v1.json tracks only two chat-surface elements, so ui-proportion-gate cannot see DocumentPane chrome growth (846 moved a line-height:1.6 host rule onto the pane; header +~5px, toggle row +~5px measured) — `governance/ui-proportion-baseline.v1.json` (2026-08-19)

### obs:llamaserverops — UX audit: the dev stack launches llama-server with --reasoning-budget 0 (log: 'reasoning-budget: act
`kind: defect?` `anchor: modules/app-inference/src/main/java/io/justsearch/app/inference/LlamaServerOps.java` `seen: 1` `first: 2026-08-19` `last: 2026-08-19`
- [ ] UX audit: the dev stack launches llama-server with --reasoning-budget 0 (log: 'reasoning-budget: activated, budget=0 tokens'), so no effort rung can produce a reasoning block on a dev run; sv3 Effort=Thorough also 400s at the 4096 dev context (6121-token request) — `modules/app-inference/src/main/java/io/justsearch/app/inference/LlamaServerOps.java:259` (2026-08-19)

### obs:app-services-0-2-0 — Stale installDist silently serves old wire: dev stack ran `modules/ui/build/install/ui/lib/app-servi
`kind: defect?` `anchor: modules/ui/build/install/ui/lib/app-services-0.2.0.jar` `seen: 1` `first: 2026-08-19` `last: 2026-08-19`
- [ ] Stale installDist silently serves old wire: dev stack ran `modules/ui/build/install/ui/lib/app-services-0.2.0.jar` dated 2026-08-13 emitting the pre-822-§3b `chunkIndex` + no `scorer` citation payload, while `modules/app-services/build/libs/app-services-0.2.0.jar` (same version string, rebuilt same day) had `sourceIndex`+`scorer`; dev.start did not refresh the dist and quick_health freshness did not flag it — `modules/ui/build/install/ui/lib/` (2026-08-19)

### obs:sv3-context — 852 S2 follow-up (F6): an effective-context floor whose message maps to no rendered turn renders NO 
`kind: follow-up?` `anchor: modules/ui-web/src/shell-v0/views/search-v3/sv3-context.ts` `seen: 2` `first: 2026-08-19` `last: 2026-08-19`
- [ ] 852 S2 follow-up (F6): an effective-context floor whose message maps to no rendered turn renders NO divider and dims nothing, while the backend still truncates the prompt at it — consider rendering the bar line + Restore whenever contextFloor is set but unresolved — `modules/ui-web/src/shell-v0/views/search-v3/sv3-context.ts:130` (2026-08-19)
- [ ] 852 S2 follow-up (F4): the turn-context projection is lossy, so an excluded message id mapping to no rendered turn is invisible to the hidden-turn count AND unreachable by Include all — a /history-side count (ledger length vs resolved turns) would surface it — `modules/ui-web/src/shell-v0/views/search-v3/sv3-context.ts:160` (2026-08-19)

### obs:sv3-citation-anchor — 849 S10: a citation whose endChar<=startChar yields a null anchor, so the v3 pane opens at the docum
`kind: defect?` `anchor: sv3-citation-anchor.ts` `seen: 1` `first: 2026-08-19` `last: 2026-08-19`
- [ ] 849 S10: a citation whose endChar<=startChar yields a null anchor, so the v3 pane opens at the document head with NO message — the pane cannot distinguish 'opened without a citation' from 'citation carried an unusable span' without a new event field; a fourth honest reason worth adding when the header lands (slice 3) — `modules/ui-web/src/shell-v0/views/search-v3/sv3-citation-anchor.ts:sv3CitationAnchor` (2026-08-19)

### obs:sv3-branch — sv3 version pager: versionsAt allocates `[...conversations]` per turn although siblingSessionsAt alr
`kind: defect?` `anchor: modules/ui-web/src/shell-v0/views/search-v3/sv3-branch.ts` `seen: 1` `first: 2026-08-19` `last: 2026-08-19`
- [ ] sv3 version pager: versionsAt allocates `[...conversations]` per turn although siblingSessionsAt already .slice()s internally — gratuitous per-render allocation on the F12 path (852 S3 review L7) — `modules/ui-web/src/shell-v0/views/search-v3/sv3-branch.ts:193` (2026-08-19)

### obs:searchv3view-pane-test — SearchV3View.pane.test.ts rag.citation_matches fixtures omit `parentDocId`, which the wire shape req
`kind: defect?` `anchor: modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.pane.test.ts` `seen: 1` `first: 2026-08-19` `last: 2026-08-19`
- [ ] SearchV3View.pane.test.ts rag.citation_matches fixtures omit `parentDocId`, which the wire shape requires and the grounding join uses as a guard — any assertion routed through sourceGrounding there silently reads 'not cited' — `modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.pane.test.ts:651` (2026-08-19)

## Parked

### obs:actionledgerprojection — ActionLedgerProjection.deterministicId `:`-join is injection-safe only because all-but-last discrimi
`kind: follow-up?` `anchor: modules/app-observability/src/main/java/io/justsearch/app/observability/ledger/ActionLedgerProjection.java` `seen: 2` `first: 2026-05-27` `last: 2026-05-27` `status: parked (deferred — revisit per condition note (triage 2026-07-12))`
- [ ] ActionLedgerProjection.deterministicId `:`-join is injection-safe only because all-but-last discriminators come from colon-free NamespacedId/enum domains; not structurally guaranteed if a free-form field is ever added before the last position — consider length-prefix or escaping if discriminator set grows — `modules/app-observability/src/main/java/io/justsearch/app/observability/ledger/ActionLedgerProjection.java:142` (2026-05-27)
- [ ] `ActionLedgerProjection.deterministicId` colon-join is collision-safe only because non-final discriminators are colon-free (NamespacedId/enum); adding a free-form discriminator before the last position could re-introduce id aliasing — consider length-prefixing or escaping if that changes — `modules/app-observability/src/main/java/io/justsearch/app/observability/ledger/ActionLedgerProjection.java:142` (2026-05-27)

### obs:readinessnotice — Banner cause wording for `lambdamart.not_configured` uses the generic fallback ("Degraded: lambdamar
`kind: follow-up?` `anchor: modules/ui-web/src/shell-v0/state/readinessNotice.ts` `seen: 3` `first: 2026-06-12` `last: 2026-08-04` `status: parked (deferred — revisit per condition note (triage 2026-07-12))`
- [ ] Banner cause wording for `lambdamart.not_configured` uses the generic fallback ("Degraded: lambdamart.not_configured"); consider a CAUSE_ROWS entry or excluding LAMBDAMART from the reindex-banner causes (it is DEGRADED-capped noise per StatusLifecycleHandler) — `modules/ui-web/src/shell-v0/state/readinessNotice.ts` (2026-06-12)
- [ ] Readiness banner reads "Semantic search degraded — Showing keyword results" while doc-level dense AUTO search actually serves HYBRID (proven live, 598 PART XI/§A4) — the §53 capability-vs-actuality split: banner keys off passage(chunk) embeddings + LambdaMART, not doc-level dense availability. Consider scoping the banner copy to passage-grounded Q&A vs document search — `modules/ui-web/src/shell-v0/state/readinessNotice.ts` (2026-06-17)
- [ ] 805 G.2 follow-up: an info-severity passage gap (chunk_embedding.in_progress alone) still takes the calm info branch claiming 'results are still fully semantic' — the passage branch sits after the info branch by design, so the passage caveat is only reachable at warn severity — `modules/ui-web/src/shell-v0/state/readinessNotice.ts:566` (2026-08-04)

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
`kind: follow-up` `anchor: modules/worker-services/src/main/java/io/justsearch/indexerworker/services/execute/SearchExecutor.java` `seen: 3` `first: 2026-05-26` `last: 2026-07-27` `status: parked (deferred — revisit per condition note (triage 2026-07-12))`
- [ ] Decompose SearchExecutor (990→1031 LOC, grandfathered) — extract chunk-merge subsystem (mergeChunkResults / executeChunkBranchFusion / collapse helpers, ~250 LOC) into a ChunkMergeExecutor collaborator — `modules/worker-services/src/main/java/io/justsearch/indexerworker/services/execute/SearchExecutor.java` (2026-05-26)
- [ ] 717 hypothesis CORRECTED (via 718 live smoke): the intermittent chunk-death is QUERY-TIME not build-time — degenerate build had 4293 chunk docs / 4293 embeddings completed / 100% coverage yet chunk_merge fired for 0 queries + vector nDCG 0.34. Investigate SearchExecutor/SearchPlanner chunk-merge activation, not the enrichment write path — `modules/worker-services/.../execute/SearchExecutor.java:527` (2026-07-11)
- [ ] Chunk-branch CC fusion applies spladeParentLengthMultiplier (0.0 at parent >=4096 tokens) to the CHUNK splade leg, suppressing per-chunk sparse by the PARENT's length — the gate compensates for a truncation chunking removes; zeroes 77.8% of legal-clerc-200 and plausibly confounds F-036/Q-017 — `modules/worker-services/src/main/java/io/justsearch/indexerworker/services/execute/SearchExecutor.java:912` (2026-07-27)

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
