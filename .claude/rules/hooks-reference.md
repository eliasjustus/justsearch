<!-- budget: always-loaded; ceiling in scripts/ci/always-loaded-budget.v1.json (ratchets down) — tempdoc 620. -->

# Hooks That Affect Your Behavior

These hooks fire automatically. When one blocks you, don't retry — adapt.

> **Kill switch (tempdoc 520 P1c):** set `JUSTSEARCH_DISABLE_HOOKS=1` to disable all
> session-affecting hooks (the four blocking guards + dispatch / compact-save /
> compact-restore) for fast recovery if a hook misbehaves. Shared hook plumbing
> (stdin read, repoRoot, atomic write, kill switch) lives in
> `scripts/agent-analytics/lib/hook-base.mjs`.

## bash-guard (PreToolUse → Bash)
**Blocks in main worktree:** `git checkout <branch>`, `git switch`, `git reset --hard`, `git clean -f`, `git restore .`, and whole-tree `git checkout -- .` — but **single-file restore `git checkout -- <path>` is allowed** (tempdoc 520 P0c).
**Blocks everywhere:** `git push --force`, unconditional `sleep >= 1s`
**Redirects:** ***bare* (flagless, unchained)** `cat`/`head`/`tail`/`grep`/`rg` → use Read/Grep tools instead. Flagged invocations (`cat -n`, `grep -A 3`) and pipelines (`cat f | …`) are allowed (tempdoc 520 P0a/P0b).
**If blocked on sleep:** Use `jseval --start-backend --pipeline` for backend lifecycle. Short condition-based polling sleeps remain allowed when tied to a real readiness check, for example `while ! curl ...; do sleep 0.2; done`.
**If blocked on git:** Use worktrees for branch work.
**If blocked on file tools:** Use dedicated Read/Grep tools.

## repeat-guard (PreToolUse → all tools)
**Blocks:** 3+ consecutive identical tool calls (same tool + same arguments).
**Excludes:** Build commands (handled by build-counter).
**If blocked:** Vary your approach — different search terms, different file, or ask the user.

## build-counter (PreToolUse → Bash, Gradle commands)
**Blocks:** Gradle build/test commands after 3+ consecutive failures.
**If blocked:** Stop retrying. Diagnose the root cause. Read error output carefully. Fix the underlying issue before building again.

## intervene (PreToolUse → Read, Edit)
**Read:** Auto-injects `limit: 200` for files >8 KB without offset/limit. Blocks unbounded reads after 10 on the same file.
**Edit:** Tracks edit counts (no blocking).
**If blocked on Read:** Use offset/limit for large files. If you've read a file 10 times, use the content you already have.

## compact-save / compact-restore (PreCompact / SessionStart / SessionEnd)
**Behavior:** Saves session state (top files, edited files, git-modified files) before compaction. Restores it on resume by writing a session-stamped `.claude/rules/compaction-state.md` — which now leads with a **Current worktree** block (toplevel dir + branch), so your worktree/branch is surfaced automatically post-compaction (tempdoc 620 Part V, mechanizing `after-compaction-verify`). **compact-restore also runs on SessionEnd** to delete that file so it never bleeds into the next session's pre-hook rules load (tempdoc 520 P0d).
**After compaction:** The state block already shows your dir + branch — confirm it matches the work you expect; `pwd` / `git branch --show-current` remain the fallback on a non-compaction session start.

## ui-shot-hint (PostToolUse → Edit, Write)
**Behavior:** When you edit a file in `modules/ui-web/src/**/*.{ts,tsx}`, this hook tells you which screenshot steps are affected.
**Action:** Run the suggested `jseval ui-shot <step>` command, then `Read` the PNG. Load `/ui-check` for full reference.

## consult-doc-hint (PreToolUse → Edit, Write)
**Behavior:** Before you edit a file in a governed region (currently `modules/ui-web/src/shell-v0/**`), this hook pushes a pointer to the ONE governing decision-doc (ADR-0032 "frontend is Lit" + the presentation kernel `docs/explanation/27`) into your context — the "Consult" step of the tempdoc 579 behavioral protocol (canonical docs are ~0.1% of agent reads, so the doc is *pushed*, not waited-for). Self-filters by path (the shared `GOVERNED_REGIONS` map in `scripts/agent-analytics/lib/governed-regions.mjs`); honors `JUSTSEARCH_DISABLE_HOOKS=1`; never blocks.
**Action:** Read the named decision before changing behavior; if your change supersedes it, update that doc as part of the same work. (Related local-only tool, not a hook: `node scripts/ci/report-doc-relevance.mjs` — the "Retire" signal — flags canonical docs read 0× and wired into no skill; needs local agent transcripts, so it is intentionally not CI-wired.)

## maintain-doc-hint (Stop)
**Behavior:** The exit-side complement to `consult-doc-hint` (the "Maintain" step of the tempdoc 579 protocol). When you try to finish a turn, if you edited a governed region this session (same shared `GOVERNED_REGIONS` map — currently `modules/ui-web/src/shell-v0/**`) **without** touching its governing doc, it **blocks once per region per session** (`{"decision":"block"}`) asking you to update the doc or say why not. Two de-dupe layers: `stop_hook_active` guards re-blocks within one forced continuation, and a per-session marker file (`tmp/agent-telemetry/maintain-nudged-<session>.json`) guards across turns (the transcript is cumulative, so a session-wide check would otherwise nag every turn-end). Narrow scope; honors `JUSTSEARCH_DISABLE_HOOKS=1`; fail-open on any error (never blocks on a bug).
**Action:** If the change altered documented behavior, update the governing doc in the same turn. **Escape hatch:** if no documented behavior changed, just say so — that satisfies the block and you can finish. (Consult pushes the doc going in; Maintain checks it coming out.)

## docs-regen-hint (PostToolUse → Edit, Write)
**Behavior:** When you edit a `.md` file in `docs/explanation/`, `docs/reference/`, `docs/how-to/`, or `docs/decisions/`, this hook reminds you to run the post-edit regeneration sequence.
**Action:** Run `node scripts/docs/llmstxt-generate.mjs` and `node scripts/docs/skills-sync.mjs` before committing. Load `/docs-maintenance` for full checklist.

## tempdoc-age-hint (PostToolUse → Read)
**Behavior:** When you `Read` a `docs/tempdocs/NNN-*.md`, this hook injects the doc's date/status + how many higher-numbered (newer) tempdocs exist — so staleness is visible at the moment of reading (tempdoc 620 Part V, mechanizing `tempdocs-are-dated-history`). Self-filters to tempdoc Reads; fail-open; never blocks.
**Action:** Treat an older tempdoc as dated history — verify its claims against `main` + canonical docs before trusting; a newer-numbered tempdoc or shipped code supersedes it.

## lockfile-hint (PostToolUse → Edit)
**Behavior:** When you edit a `build.gradle.kts` file, this hook reminds you to regenerate lockfiles if dependencies changed.
**Action:** If you changed dependencies, run `./gradlew.bat --no-configuration-cache resolveAndLockAll --write-locks`. Load `/lockfile` for full workflow.

## ssot-hint (PostToolUse → Edit, Write)
**Behavior:** When you edit a file in `SSOT/catalogs/` or `adapters-lucene/src/main/resources/SSOT/catalogs/`, this hook warns about the dual-copy sync requirement.
**Action:** If you edited the root copy, also update the classpath copy (and vice versa). Load `/ssot-catalog` for full checklist. **Now also enforced at ~100% by the `ssot-catalog-sync` discipline gate** — a divergence fails CI, not just an advisory hint.

## test-edit-hint (PostToolUse → Edit, Write)
**Behavior:** When you edit a `*Test.java` (any sourceset: test, integrationTest, systemTest) or `*.test.ts` / `*.test.tsx` file, this hook surfaces the exact `--tests` filter / `vitest` path filter that re-runs only that test class.
**Action:** Run the suggested command before commit. The suggestion is the same defect-class guard caught by observations.md item #5 (the 2026-05-08 ConfigWiringTest assertion-class mismatch shipped because the test wasn't re-run after edit).

## stress-test-hint (PostToolUse → Edit, Write)
**Behavior:** When you edit a file in `modules/ort-common/` that is a stress-test subject (`NativeSessionHandle`, `SessionHandle`, `OrtSessionAssembler`, `OnnxSessionCache`, `OrtCudaHelper`) or a `*StressTest.java`, this hook reminds you to dispatch CI with `runStress=true`.
**Action:** Run `gh workflow run ci.yml -f runStress=true` after your changes are committed. ADR-0026 manual-only CI means stress tests only run when explicitly dispatched.

## governance-hint (PostToolUse → Edit, Write)
**Behavior:** When you edit a discipline-gate baseline file (`scripts/ci/npm-audit-ratchet-baseline.v1.json`, `.claude/rules/tier-register.md`) or a changeset under `gates/<id>/.changesets/`, this hook surfaces the relevant `node scripts/governance/run.mjs --gate <id>` command + `--explain <ruleId>` / `--suggest-changeset` next steps. (The `class-size` / `ui-bundle` baselines were removed with those gates — tempdoc 634.)
**Action:** Run the suggested command. Load `/governance` for the kernel's classification grammar.

## governance-precommit-hint (PreToolUse → Bash, `git commit`)
**Behavior:** Before a `git commit` that touches discipline-gate baselines, this hook detects available rebalances (pinned values that could shrink) and emits a hint. Does NOT auto-write the baseline — the rebalance is explicit.
**Action:** If the hint reports a rebalance available, run `node scripts/governance/run.mjs --gate <id> --rebalance` to update the baseline, then re-stage and commit.

## seam-hint (PostToolUse → Write)
**Behavior:** When you `Write` a NEW production Java class in a module that hosts registered logic seams (`governance/logic-seams.v1.json`) and the class is branch/arithmetic-dense AND IO-free, this hook asks whether it is a law-bearing seam to declare (tempdoc 555 §5, the authoring-time oracle). It self-filters: only seam-bearing modules, only unregistered classes, never `Edit`s / IO-heavy / DTO files.
**Action:** If the new class is pure logic whose failure mode is a silent wrong value (scoring, precedence, offsets, budgets, state guards), add it to `governance/logic-seams.v1.json` with its law + a guard test so the `test-efficacy` gate measures whether its tests bite. Otherwise ignore — it stays out of the register by design.

## search-engine-hint (PostToolUse → Edit, Write)
**Behavior:** When you edit retrieval-engine source that can change ranking quality, performance, OR recall-survival (`modules/{adapters-lucene,reranker,worker-services,search,app-search}/src/**`, or `app-services/.../{worker,gpl}/`), this hook reminds you to re-run an eval + the **three** retrieval ratchets — the Q-010 relevance ratchet (nDCG@10), the tempdoc-640 performance ratchet (latency/throughput/footprint), and the tempdoc-636/D-005 recall-leak ratchet (cascade-leak rate — a leg's correct answer dropped before the judge). It ALSO fires on **inference-path** edits (`modules/{app-inference,prompt-support}/src/**`, `app-services/.../{conversation,inference}/`) — a distinct subject — nudging the tempdoc-640-L **LLM-generation-latency ratchet** (`jseval llm-bench` + `llm-gate`: TTFT / e2e / tokens-sec). So a regression on any axis fails loudly rather than coasting (the engine's missing continuous-quality pressure). Self-filters by path; never blocks.
**Action:** Run a **full** measurement eval with the **leg modes** (the leak gate reads the `staged_recall_accounting` projection, which needs `vector,lexical,splade` + the hybrid final in one run; perf-gate also needs the cross-encoder + pipeline timeline): `jseval run --start-backend --clean --pipeline --ce --embedding --splade --dataset scifact --modes vector,lexical,splade,hybrid`, then `python -m jseval relevance-gate --data-dir <dir> --dataset beir/scifact`, `python -m jseval perf-gate --data-dir <dir> --dataset scifact`, **and** `python -m jseval leak-gate --data-dir <dir> --dataset beir/scifact`. (Relevance + leak key on `beir/scifact`; perf + the run use the raw slug `scifact` — all intended.) Floors live in `scripts/jseval/{relevance,perf}-ratchet-baselines.v1.json` + `leak-gate-baselines.v1.json`; re-pin perf with `perf-gate --update-baseline`, leak with `leak-gate-derive --datasets <slugs>`. Load `/jseval` for flags. (Relevance-gate checks nDCG@10 *mean* regression — distinct from `jseval gate`, nDCG@10 *stdev* drift; perf-gate checks relative ratio bands; leak-gate checks the cross-mode `leak_rate` against a measured ceiling+tolerance.)

## ui-shot-cleanup (SessionEnd)
**Behavior:** Kills any Vite dev server started by `ui-shot` (port 5174) and removes the PID file.
**No action needed** — this is transparent.

## mcp-session-inject (PreToolUse → MCP tools)
**Behavior:** Auto-injects session ID into `justsearch-dev` MCP tool calls.
**No action needed** — this is transparent.

## subagent-guide (SubagentStart)
**Behavior:** Injects a baseline project brief into every subagent (which sees no CLAUDE.md / rules / parent hooks): the Hard Invariants **live-projected from CLAUDE.md** via `lib/hard-invariants.mjs` (single authority — can't drift; tempdoc 620 Part V), platform notes, agent discipline, the subagent risk profile, and the observations protocol.
**Action:** Still brief the *task-specific* part when you delegate; the baseline is covered. (Also wired but transparent: `dispatch.mjs`, `export-session-env.mjs`.)

## otlp-sink-ensure (SessionStart)
**Behavior:** Idempotently ensures the local OTLP telemetry sink is running so native Claude Code telemetry capture is automatic (tempdoc 622 §S1). Probes `127.0.0.1:4318`; if nothing is listening, spawns `otlp-sink.py` **detached** so it outlives the hook and is shared across concurrent sessions. No SessionEnd kill (that would drop capture for other live sessions). Fail-open; CI-safe (returns early under `JUSTSEARCH_DISABLE_HOOKS=1`).
**No action needed** — capture is automatic. Run `python scripts/agent-analytics/otlp-sink.py` manually only if you start a session with hooks disabled.
