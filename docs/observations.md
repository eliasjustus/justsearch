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
`kind: environment?` `anchor: utility_comparison.py` `seen: 2` `first: 2026-07-02` `last: 2026-07-02`
- [ ] Pre-existing (unrelated to tempdoc 624 utility_comparison.py work): tests/test_agent_retrieval_eval.py::test_build_disallowed_tools_condition_{a,b,c}_* fail with 'Extra items in the left set: Skill' — a disallowed-tools set assertion out of sync with agent_retrieval_eval.py, in already-uncommitted worktree changes predating this session. (2026-07-02)
- [ ] utility_comparison._pair_observations only reads a_by_seed[seed][0]/c_by_seed[seed][0] — if a cell's cell_summaries ever contain >1 summary at the SAME (seed, arm) pair (e.g. a corpus-signature refresh landing at the same seed the _default_corpus_stratify docstring anticipates), all but the first summary's per_query is silently dropped rather than merged; the existing stratify test avoids this by using distinct seeds per signature — `scripts/jseval/jseval/utility_comparison.py:298-300` (2026-07-02)

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
`kind: defect?` `anchor: remove-worktree.cjs` `seen: 7` `first: 2026-06-21` `last: 2026-07-10`
- [ ] Second orphaned worktree dir `.claude/worktrees/597-chat-count` is on disk but unregistered (not in `git worktree list`) — same failed-removal class as 587; removable via `node scripts/dev/remove-worktree.cjs` with owner approval (618 §15) (2026-06-21)
- [ ] remove-worktree.cjs: two defects seen 2026-07-07 — (a) its record-merge step attributes the merge to whatever session id happens to sit in the invoking checkout's tmp/agent-telemetry/current-session-id (linked a neighbouring session, then 'link skipped' from a fresh worktree; the tearing-down session cannot pass its own id), and (b) the EPERM long-path delete fallback throws 'filename, directory name, or volume label syntax is incorrect' — the \\?\ fallback path construction is broken, so any held-handle worktree fails removal twice. (2026-07-07)
- [ ] remove-worktree.cjs record-merge misattribution RE-OBSERVED 2026-07-07 (681 teardown): linked session 20097c0b (neighbour's id in main checkout's current-session-id) to a local merge commit instead of the tearing-down session 06f94413 -> squash f604144; backfilled manually in session-merges.ndjson — `scripts/dev/remove-worktree.cjs` (2026-07-07)
- [ ] reportHolders (scripts/dev/remove-worktree.cjs, added by 684/#82) still self-matches: its Win32_Process CommandLine -like '*<base>*' filter (excluding only its own powershell $PID) STILL matches the removal script's OWN node process and bash wrapper, because the target worktree path is in THEIR argv (observed live 2026-07-07: 'PID 536: node.exe ... remove-worktree.cjs .claude/worktrees/obs-cleanup'). Cheap fix for a future dev-tooling batch: also exclude the removal process tree (e.g. CommandLine -notlike '*remove-worktree*' and the parent node/bash PIDs). Fundamental cwd-holder limit (Win32_Process has no cwd) remains separate/out-of-scope. — scripts/dev/remove-worktree.cjs:94-113 (2026-07-07)
- [ ] Process gap: no cleanup path for worktree-* branches on closed-but-unmerged PRs — delete_branch_on_merge only fires on actual merge; scripts/dev/remove-worktree.cjs:158-216 only deletes local branch/worktree, never touches origin (2026-07-07)
- [ ] scripts/dev/remove-worktree.cjs intermittently fails to delete a worktree directory with EPERM/'used by another process' even with no obviously-holding process (retry usually succeeds, but not always — hit a case this session requiring git worktree prune + manual rmdir fallback) — scripts/dev/remove-worktree.cjs (2026-07-08)
- [ ] remove-worktree.cjs cannot remove the CALLING session's own start-worktree — the session's MCP server processes (justsearch-dev server.mjs etc.) hold cwd inside it until session exit; remove-worktree correctly deletes other worktrees (624-step0-campaign removed fine post-684-fix) but self-removal needs a post-session step. Suggest: remove-worktree detect-and-say 'owned by live session <id>, rerun after it exits' instead of a bare EPERM-style failure — `scripts/dev/remove-worktree.cjs` (2026-07-10)

### obs:healthsurface-flake — HealthSurface 'Recent events' renders NO ConditionStore conditions: `hs.events` is empty for ALL con
`kind: defect?` `anchor: HealthSurface.ts` `seen: 1` `first: 2026-06-22` `last: 2026-06-22`
- [ ] HealthSurface 'Recent events' renders NO ConditionStore conditions: `hs.events` is empty for ALL conditions (ai.not-ready, embedding.blocked, at-rest.unprotected, etc.) despite the /api/health/events/stream snapshot carrying them (a fresh same-origin fetch gets them fine). HealthSurface's persistent SSE subscription (`HealthSurface.ts:571-624`) isn't populating this.events — possibly dev-stack reconnect/stale-port flakiness. Affects all conditions equally; unrelated to 629. (2026-06-22)

### obs:default-index — Dev-stack: orphaned dev-runner/Worker processes accumulate across sessions and hold `index/default.i
`kind: defect?` `anchor: index/default.index.lock` `seen: 1` `first: 2026-06-22` `last: 2026-06-22`
- [ ] Dev-stack: orphaned dev-runner/Worker processes accumulate across sessions and hold `index/default.index.lock` + squat port 5173, crash-looping new Workers (`Index base path is already locked`) and tearing the stack down — symptom looks like a code boot failure but isn't. Recover: kill stray java/node dev PIDs + delete the stale lock; run `dev-runner.cjs start` as a BARE persistent background process (its children are in a KILL_ON_JOB_CLOSE Job Object, so a timeout/pipe wrapper kills the whole stack). Hit during 629 LAYER live-validation. (2026-06-22)

### obs:agent-utility-inspect — jseval utility-run (Inspect eval_set): re-invoking a FULLY-COMPLETED set errors 'log file not associ
`kind: defect?` `anchor: agent_utility_inspect.py` `seen: 1` `first: 2026-06-22` `last: 2026-06-22`
- [ ] jseval utility-run (Inspect eval_set): re-invoking a FULLY-COMPLETED set errors 'log file not associated with a task' — needs --log-dir-allow-dirty; partial-crash resume uses the now-pinned deterministic eval_set_id. tempdoc 624 run-governance validation — `scripts/jseval/jseval/agent_utility_inspect.py:run_utility_eval` (2026-06-22)

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
`kind: environment?` `anchor: modules/ui/src/main/java/io/justsearch/ui/api/ResourceApiModule.java` `seen: 1` `first: 2026-06-30` `last: 2026-06-30`
- [ ] ResourceApiModule.shutdown() never calls intentStreamController::shutdown — its heartbeat scheduler thread leaks on module shutdown (pre-existing, found while wiring tempdoc 662's ShellEventsStreamController shutdown) — `modules/ui/src/main/java/io/justsearch/ui/api/ResourceApiModule.java:472-494` (2026-06-30)

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
`kind: defect?` `anchor: scripts/dev/dev-runner.cjs` `seen: 1`
- [ ] Dev-runner is bound to main repo path (`F:/JustSearch`) and cannot live-verify Java backend changes made in worktrees. Worsened by main's gradle currently failing with a snakeyaml lockfile issue (`Resolved 'org.snakeyaml:snakeyaml-engine:3.0.1' which is not part of the dependency lock state`). Net effect: tempdoc 530 §4.2 `/api/governance/state` endpoint compiled cleanly in the worktree (class present in worktree's installed jar; route registered in source) but could not be live-HTTP-verified due to this contradiction. Resolution path: fix main's lockfile, or extend dev-runner to honor worktree CWD. — `scripts/dev/dev-runner.cjs` + `F:/JustSearch` main lockfile (2026-05-21, tempdoc 530 Pass-7 Phase D2)

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
`kind: defect?` `anchor: scripts/agent-analytics/hooks/ui-shot-cleanup.mjs` `seen: 1` `first: 2026-06-16` `last: 2026-06-16`
- [ ] ui-shot-cleanup.mjs exists on disk but is not wired in .claude/settings.local.json (hooks-reference.md documents it as a SessionEnd hook) — `scripts/agent-analytics/hooks/ui-shot-cleanup.mjs` (2026-06-16)

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
`kind: defect?` `anchor: scripts/dev/justsearch-dev-mcp/server.mjs` `seen: 2` `first: 2026-06-17` `last: 2026-07-01`
- [ ] Dev-stack ownership gates only `start` (spawn); a non-owner agent can POST `/api/knowledge/ingest`, `/api/indexing/reindex|gc|migration`, or `reload` against a peer's running stack with no owner check — ownership grants no exclusivity over the mutating/lifecycle surface — `scripts/dev/justsearch-dev-mcp/server.mjs` (2026-06-17)
- [ ] Fresh worktree dev-data has no AI chat-model pack imported; POST /api/ai/runtime/activate fails MODEL_PATH_REQUIRED even with llama-server auto-staged. /api/ai/packs/* expects a packaged manifest (end-user Install-AI flow), not a local-file import. Workaround: GET/POST full /api/settings/v2 with llm.modelPath set to a real local GGUF, then retry activate. Worth a documented dev-stack shortcut. — `scripts/dev/justsearch-dev-mcp/server.mjs:2432-2520` (2026-07-01)

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
`kind: defect?` `anchor: record-merge.mjs` `seen: 1` `first: 2026-07-07` `last: 2026-07-07`
- [ ] Dev-tooling test-coverage gap (surfaced by 684): record-merge.mjs has NO dedicated test, and prepare-worktree.cjs's item-3 gradle-spawn fix was verified only by static path-reasoning (no live run of npm-ci + installDist). 684 added the first test for remove-worktree.cjs (test-remove-worktree.cjs); the sibling lifecycle scripts remain a regression-net gap. Task-shaped, not tempdoc-shaped; a real prepare-worktree integration test is heavy (npm ci + installDist) so weigh unit-level spawn-path assertion vs full integration. — scripts/dev/prepare-worktree.cjs, scripts/agent-analytics/record-merge.mjs (2026-07-07)

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
`kind: defect?` `anchor: scripts/jseval/jseval/dataset_cache.py` `seen: 1` `first: 2026-07-12` `last: 2026-07-12`
- [ ] 709 resume gap: an interrupted CLERC raw fetch leaves an orphaned `.tmp-*` staging dir under the cache root (observed: ~6.3GB collection.doc.tsv.gz at scripts/jseval/tmp/dataset-fetch-cache/clerc-raw/.<key>.tmp-*); `store()` neither resumes nor GCs it, so the next fetch re-downloads the full 6.7GB — `scripts/jseval/jseval/dataset_cache.py:150` (2026-07-12)

### obs:test-correction-probe — Pre-existing: scripts/jseval/tests/test_correction_probe.py default-manifest tests fail on main beca
`kind: environment?` `anchor: test_correction_probe.py` `seen: 1` `first: 2026-07-13` `last: 2026-07-13`
- [ ] Pre-existing: scripts/jseval/tests/test_correction_probe.py default-manifest tests fail on main because jseval/data/correction-eval-queries.v1.json was never committed (absent since v0.1.0) — full pytest suite is 2-red on a clean main checkout. Noted during 719 takeover; not caused by 719 branch. (2026-07-13)

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
`kind: follow-up?` `anchor: evidenceProjection.ts` `seen: 1` `first: 2026-06-05` `last: 2026-06-05` `status: parked (deferred — revisit per condition note (triage 2026-07-12))`
- [ ] 565 ⑤ grounding-coverage indicator (design-feature, specified — the "presentation can outrun grounding" answer): surface "M of T sentences grounded" so polish can't lend false confidence to thin grounding. HONEST approach (avoid FE/​backend sentence-split inconsistency): have `AgentCitationResolver` (which already splits the answer into sentences to match) return the TOTAL sentence count; carry it on `AgentDone` (a `groundedSentenceTotal` field) alongside the existing `citations`; FE shows `answerCitations.length` / total. The RAG path already computes `sentencesMatched/sentencesTotal` (`CitationMatchResult`) + a tiered `EvidenceScore` (`evidenceProjection.ts`) to mirror. (2026-06-05)

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
