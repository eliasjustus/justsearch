---
title: "886 — Agent token efficiency review: context residency is the cost, not cache misses"
status: "IMPLEMENTED 2026-09-02 on branch worktree-886-ledger-1 (PR 1-5b stacked, each independently reviewed) — awaiting owner go-ahead to open the PR; behavioural levers (§4) remain owner policy"
created: 2026-09-02
updated: 2026-09-02 (§9 scope correction, §10 theorization)
supersedes-in-part: 841 (§7 'prefix is proportionate, not the lever' and the 'no measurable context bloat' framing — see §3)
---

# 886 — Agent token efficiency review

**Thesis.** Over 2026-08-01..09-02 the repo spent ~$18.8k on Claude Code (transcript-priced,
`lib/transcript-cost.mjs` rates). The existing stack explains *why cache writes happen* (841) and
*what overhead categories exist* (743 T1), but no reader measures the variable that actually sets
the bill: **context tokens re-presented per API call**. Median main-loop call carries **415k**
tokens; median subagent call carries **177k**; 88% of subagent spend runs on Opus in workers of
120-1,365 calls. Prior conclusions that "compaction cost is negligible" and "the always-loaded
prefix is proportionate" measured the *cost of compacting* and the *prefix as a share of one cold
start* — not the cost of *not* compacting, nor the prefix multiplied by 99,000 calls.

## 1. Method

- Window: sessions whose transcript mtime ≥ 2026-08-01 (57 main + 1,030 subagent transcripts,
  14,782 main + 84,283 subagent API calls). Dedup on `message.id` (Claude Code writes one JSONL
  line per content block; usage repeats per line).
- Context per call = `input + cache_read + cache_creation` from `message.usage`.
- Prices from `lib/transcript-cost.mjs` (Fable cache-read $1.00/M, Opus 5 $0.50/M, Sonnet 5 $0.20/M).
- Existing readers run first: `baseline-economics.mjs`, `cache-efficiency.mjs`,
  `overhead-taxonomy.mjs --since 2026-08-01`, `context-attribution.mjs --all`, `cost-session.mjs --all`.
- Three throwaway readers in `tmp/tokeff/` (`deep.mjs`, `deep3.mjs`, `deep4.mjs`) computed what
  the stack lacks: per-call context distribution, spawn ledger joined to `subagents/*.meta.json`,
  and a *compounded residency* model (each context piece costs tokens × calls-it-stays-resident ×
  cache-read rate). §6 proposes productionising them.

## 2. Findings (window 2026-08-01..09-02)

### 2.1 Spend shape

| | Main loop | Subagents | Total |
|---|---|---|---|
| API calls | 14,782 | 84,283 | 99,065 |
| Context tokens re-presented | 6.54B | 17.88B | 24.4B |
| Transcript-priced cost | $8,168 | $10,602 | $18,770 |
| Context/output token ratio | 605:1 | 1,563:1 | |

91.7% of spend is cache read + write; output is 8.3% (`cache-efficiency.mjs`). Thinking is ≈0%
of resident context. So the bill is a function of **(context size per call) × (calls)** and almost
nothing else.

### 2.2 Context per call — the unmeasured variable

Main loop, per-call context: p50 **415k**, p75 627k, p90 782k, p99 942k, max 997k. 74.8% of all
main-loop context tokens sit in calls with >400k context. First-call (cold) context is p50 66k, so
sessions grow from 66k to 500k+ and stay there: 9 compactions in the window, **8 manual, 1 auto
(at 1.0M)**, median pre-compaction context 947k, min 447k. Summaries are 9-29k tokens; a
compaction takes ~160s wall clock. (Corrected twice during PR 2: the throwaway reader's "22"
counted boundary lines, of which each Claude compaction writes two, and its file-mtime window
admitted two late-July events; `context-residency.mjs --since 2026-08-01` is the reproducible
figure.)

Subagents, per-call: p50 **177k**, p90 398k, p99 632k.

Cache-read cost of context **above a 200k cap** (an upper bound on what a 200k ceiling would
save, before re-orientation costs): main $3,190 + subagents $2,225 = **$5.4k ≈ 29% of window
spend**. Above 400k: $1.9k.

Note on `autoCompactWindow: 600000` (user settings, added after 2026-08-26, uncommitted rules
edit): the corpus shows the only auto-compaction firing at 1.0M, so this setting is **unverified
in practice** — check the next long session's compaction trigger before relying on it.

### 2.3 Subagent economics — where the money went

| Requested model | Spawns | Cost | Calls |
|---|---|---|---|
| opus | 696 | $10,047 | 69,944 |
| sonnet | 307 | $544 | 14,136 |
| unset/inherit/haiku | 27 | $11 | 209 |

- 770 of 775 `Agent` calls set an explicit model (the CLAUDE.md rule holds); the choice resolved
  to **opus 77% of the time**. Opus spawns average $14.4 vs $1.77 for Sonnet (5× rate × longer runs).
- Spawn length is the multiplier: spawns with **≥120 calls are 20% of spawns and 72% of subagent
  cost** ($7.6k). The top 20 spawns (all `general-purpose`/opus, "Implement <tempdoc> slice"
  workers, 361-1,365 calls, peak context 462k-920k) cost $86-355 each — i.e. the "worker-grade
  loop" the delegation rule routes out of the orchestrator is being run at orchestrator-grade
  context on an orchestrator-grade model, without the hook layer (parent hooks don't fire inside).
- Brief length p50 3.9k chars, p90 7.4k — briefs are not the cost.
- 841's "subagents are 2× more cache-efficient than main" (262:1) does not hold in this window:
  1,563:1. Cause: the August workers run 400-900k contexts for hundreds of calls.

### 2.4 Compounded residency — what each context piece actually costs

Priced as tokens × calls resident × cache-read rate (chars/4 rescaled to actual usage; ±15%):

| Piece | Cost | Share |
|---|---|---|
| Subagent prefix (system prompt + CLAUDE.md/rules + tool schemas + brief) | ~$3.2k | 23% |
| Subagent tool results (Bash $1.75k, Read $1.44k, rest <$0.1k each) | $3.4k | 25% |
| Main prefix | ~$1.6k | 12% |
| Subagent `tool_use` inputs (Write/Edit bodies $0.9k) | $1.5k | 11% |
| Main tool results (Bash $0.54k, Read $0.32k) | $1.3k | 9% |
| Main user-side text (prompts, task notifications, system reminders) | $1.1k | 8% |
| Main `tool_use` inputs (Agent briefs $0.37k, Write/Edit $0.33k) | $1.2k | 8% |

The prefix is ~35% of re-presentation cost because it is resident in every one of 99k calls.
841 §7 judged it "proportionate" as 21% of *one* cold start; multiplied through, every 10k tokens
of always-loaded prefix costs ≈ $600 per 5-week window at the current call volume.

### 2.5 Other measured items

- WAITING (task-notification wake-ups): 1,201 turns, **9.5% of window tokens** (2.12B) — each
  wake-up re-presents the full ~500k main context. Tempdoc 746 shipped the CI-wait fix; the class
  persists because the cost is per-wake-up × context size, not per-poll.
- Tool results >5k chars are 3.6% of main results but 37.8% of main result chars; in subagents
  10% of results / 52.5% of chars. Largest single results: a 244k-char `settings.json` validation
  error, 60k-char Reads of tempdocs/scratchpads.
- Re-reads of the same file in the main loop: 36 in the window — not a lever (confirms 841 §10).
- Hook friction 0.4%, post-compaction re-orientation 0.001% — confirms 743 T1.
- OTLP sink is live (`:4318` answers) and `tmp/agent-telemetry/otlp/` rotates ~21MB trace files;
  `cost-session.mjs --source otlp` ($17.3k / 31 sessions) and transcript pricing ($19.6k / 62
  sessions since 06-18) are never reconciled against each other.
- `overhead-taxonomy.mjs` defaults to a hardcoded 2026-06-18..07-16 window and reports 0 sessions
  when run bare today.

## 3. What this changes about prior conclusions

| Prior claim | Source | Status after 886 |
|---|---|---|
| "No measurable context bloat" | 841 §1 | Measured per-turn *growth*, not absolute context. Absolute context is the cost. |
| "Prefix is proportionate, not the lever" | 841 §7 | True per cold start; false per call-volume (§2.4). |
| "Compaction/re-orientation cost negligible (0.03%)" | 743 T1 | Still true — and it is the *argument for* compacting more, not less. |
| "Subagents 2× more cache-efficient" | 841 §5 | Window-dependent; inverted again in August by long Opus workers (§2.3). |
| "Subagent 5m-TTL penalty, owner call ~$150" | 841 §7 | Moving subagents to 1h TTL is **net negative** at current volume: writes 381.5M × ($10 − $6.25)/M ≈ +$1.4k vs expiry rewrites saved 104M × $6.25/M ≈ $0.65k. Do not set `subagentPromptCacheTtl: 1h`. |
| "Delegate by default; contested, judge by 2026-09-14" | CLAUDE.md, 743 P-C | The falsifier's rework instrument still does not exist (858 §8). §2.3 supplies the cost side; a spawn ledger (§6.2) is the missing input. |

## 4. Ranked levers (estimated per 5-week window at August volume)

1. **Bound context per call** — main and subagents. `/compact <hint>` at task boundaries in the
   main loop; for workers, chunk briefs so a spawn ends before ~150 calls (CLAUDE.md already says
   "chunk long refactors" — the ledger shows it is not happening). Upper bound $5.4k (29%);
   realistic $2.5-3.5k after summary/re-orientation costs. Verify `autoCompactWindow` fires.
2. **Model routing for implementation workers** — Opus is 77% of spawn decisions and 95% of
   spawn cost. Sonnet at the same run length is 5× cheaper per token; moving a third of
   implementation workers saves ~$2-3k. Quality gate stays: "judge the output, not the price tag"
   (CLAUDE.md) — measure rework, don't assume.
3. **Shrink the resident prefix** — main 66k / sub ~40k tokens per call. Targets: MCP tool
   schemas kept deferred (`ENABLE_TOOL_SEARCH`), skills listing (`skillListingMaxDescChars` 250 ×
   ~50 skills), CLAUDE.md 22KB + rules 32KB (docs guidance: <200 lines; the always-loaded-budget
   ratchet caps bytes but the multiplier is call volume). ~$600 per 10k tokens removed.
4. **Tool-output shaping at the source** — PreToolUse rewrite of `gradlew`/`npm test` to
   failure-only output; `BASH_MAX_OUTPUT_LENGTH` below 30k for exploratory shells; Read with
   offset/limit on tempdocs/scratchpads (the 60k-char Reads). ~$0.6-0.9k.
5. **WAITING** — batch independent spawns into one message; prefer foreground for spawns you
   would block on anyway; every avoided wake-up saves one full-context re-read (~$0.25-0.50 at
   500k on Fable). ~$0.5-1k.
6. **Do not**: subagent 1h TTL (net negative, §3); `MAX_THINKING_TOKENS` (thinking ≈0% resident,
   Fable ignores it); adopt Langfuse/Phoenix/AgentOps (they add trace UI + evals, not a
   measurement this stack lacks — research pass 2026-09-02, sources in §8).

## 5. External research (2026-09-02 pass, condensed)

- Anthropic docs: Claude Code splits prompt-cache TTL into *main* (1h on subscription) and
  *everything else incl. subagents* (5m); overrides `promptCacheTtl` / `subagentPromptCacheTtl`
  (v2.1.242+); per-agent `experimental.cacheTtl` (v2.1.248+). Cache is scoped per machine +
  directory **including worktrees**; forks read the parent's cache, fresh subagents don't.
  Invalidators: model/effort switch, fast mode, MCP connect, plugin toggles, compaction, CC
  upgrade. `BASH_MAX_OUTPUT_LENGTH` default 30k chars; `MAX_MCP_OUTPUT_TOKENS` 25k; `/usage`
  reports "expected rebuild (compaction or tool-result clearing)" from v2.1.251.
- OTel: `claude_code.cost.usage` carries `agent.name`/`skill.name`/`mcp_tool.name` but custom
  names collapse to `custom`; no per-tool cache attribute — transcripts remain the only source
  for per-tool residency.
- Effect sizes: deferred tool schemas −85% tool-schema tokens (Anthropic); server-side
  tool-result clearing −84% tokens on a 100-turn task (Anthropic cookbook); MCP tool pruning
  −19..62% per workflow (GitHub); METR (2026-07): $/trial varies 10× across agents and does not
  correlate with pass rate; Jellyfish: cost per merged PR $0.28-$89 across 200 companies (this
  repo: $125 attributed, W33 $73 / W34 $275 / W35 $191).
- Known harness bug: first `SendMessage` resume of a subagent shows `cache_read=0` (issue
  #44724, closed not-planned) — a cold re-read per resume; 216 SendMessage calls in the window.

## 6. Stack extensions proposed (observability, local-only)

Each is a reader on `lib/transcript-store.mjs` + `lib/transcript-cost.mjs`, with tests, following
743's opportunistic-migration rule. Prototypes exist in `tmp/tokeff/` (not for commit as-is).

1. **`context-residency.mjs`** — per-call context distribution (main/sub, per model), cost
   above a configurable cap, compaction ledger (trigger, pre/post tokens, duration), and the
   compounded residency table (§2.4). This is the report that would have surfaced §2.2 a month
   ago. `--json` for trend use.
2. **`spawn-economics.mjs`** — spawn ledger joined to `subagents/*.meta.json` (agentType,
   requested vs actual model, description, calls, peak context, cost, brief length), buckets by
   run length, top-N. Doubles as the cost half of the 743 delegate-by-default falsifier
   (due 2026-09-14); the rework half still needs a git-churn join (858 §8).
3. **`spawn-cost-hint` (PostToolUse/Agent, non-blocking)** — on a spawn's return, read its
   transcript and print one line: calls, peak context, cost, model. Closes the blind spot that the
   orchestrator never sees what a delegation cost at the moment it judges the result. Register in
   `governance/agent-hooks.v1.json` + tier-register like every other hint.
4. **`context-ceiling-hint` (PostToolUse, once per threshold)** — from `transcript_path`, last
   usage; at 300k/500k emit "context at Nk tokens; `/compact <hint>` at the next boundary, or
   `/rewind` if abandoning a path". Advisory only; the number is the point.
5. **Fixes**: `overhead-taxonomy.mjs` default window → trailing 30 days; a
   `reconcile-cost-sources.mjs` (or a flag on `cost-session`) that diffs OTLP-priced vs
   transcript-priced totals per session and names the unpriced/unknown-model residue (the
   `<synthetic>` and `unknown` rows are the same 858 §9.1 defect).

Not proposed: dashboards (858 D1 retired them), a composite score (858 §4.5), external platforms.

## 7. Open questions / verification steps

- Does `autoCompactWindow: 600000` fire? Next long session: check the compaction ledger.
- Sonnet-worker quality: run the next two implementation slices as Sonnet workers with the same
  brief shape and compare rework (reverts/follow-up fixes within 7 days) — the measurement the
  falsifier needs anyway.
- Exact prefix composition: `/context` at session start in main and inside one general-purpose
  spawn (system prompt vs tools vs CLAUDE.md vs skills listing) — §2.4 infers it from first-call
  usage only.
- 268 in-TTL undetermined invalidations (841 §4) remain unexplained; not the lever, still open.
- `861-w5-agent-spawn-sweep.test.mjs` is flaky standalone (failed once, passed on retry with no
  code change, 2026-09-02; reproduced by the independent reviewer). Pinned in
  `expected-state.v1.json` (`agent-analytics-spawn-sweep-test-flaky-under-load`, review by
  2026-09-30) with an exit probe; the fix belongs to tempdoc 861 W5, not here.
- PR 5b review (approve, four cosmetic findings): the subagent edit check now also counts
  `MultiEdit` (disclosed in-code); the four migrated readers export nothing and have no direct
  tests, so their guards protect against a bare `import()` only; `friction-timeline`'s default
  now spans all `/justsearch/i` project dirs (was: main checkout only, and a non-existent dir
  from a worktree); guard style is bare `main()` in three files and `.catch` in one — precedent
  (`cost-session.mjs`) is bare, left as is.
- The ledger's `ToolEvent.outputChars` counts TEXT content blocks only (`lib/transcript-store.mjs`'s
  `extractToolResultText`) — an `image` block (a screenshot) is invisible to it, which is exactly why
  `context-attribution.mjs` keeps its own local, image-inclusive char computation rather than adopting
  `outputChars` (886 §12 PR 5a outcome: a parity run found `outputChars` undercounts
  `mcp__claude-in-chrome__computer`/`browser_batch`/screenshot-`Read` by 84–99%). A future ledger field
  — `ToolEvent.outputBlocks: {text, image}` (counts or chars per block kind) — would let a consumer
  choose the right axis instead of every image-aware reader re-deriving its own local scan; not
  designed here, just flagged as the closure this gap needs.

## 8. Sources

Anthropic docs: monitoring-usage, prompt-caching, costs, sub-agents, settings-reference,
env-vars, tools-reference (code.claude.com/docs/en/…); anthropic.com/engineering
advanced-tool-use, effective-context-engineering-for-ai-agents; claude.com/blog
lessons-from-building-claude-code-prompt-caching-is-everything; Manus context-engineering post;
platform.claude.com cookbook tool-use-context-engineering; github.blog improving-token-efficiency-
in-github-agentic-workflows; metr.org 2026-07-21 expenditure-horizon, 2026-07-24 metrics-of-model-
ability; jellyfish.co ai-token-usage-monitoring; github.com/anthropics/claude-code/issues/44724.

## 9. Scope correction (2026-09-02, after owner review)

The owner's position: the observability stack and the efficiency levers should not be
Claude-specific, and cloning an existing cross-provider project should be considered before
extending the homegrown one. Two facts settle the scope question:

- **The machine runs two harnesses.** Local Codex CLI rollouts (`~/.codex/sessions`, 289
  sessions with usage) carried 5.1B input tokens in July 2026 and 0.8B in August, 96-97% served
  from cache, across ~13 project directories; Claude transcripts also span projects other than
  this repo. §2 measured one harness in one repo — roughly a third of actual usage.
- **The Codex rollout format is readable today.** Per-turn `token_count` events carry
  `last_token_usage {input_tokens, cached_input_tokens, output_tokens, reasoning_output_tokens}`
  and `model_context_window`; the model rides on the surrounding `turn_context`. Codex median
  input context per turn is 129k (p90 210k) against Claude's main-loop 415k — the harnesses will
  have different top levers even under one measurement.

Cross-provider survey (2026-09-02): **ccusage** (Rust, ~15 harnesses incl. both of ours, splits
cache read/write, LiteLLM pricing) is the only project covering both log formats, but emits
day/model/session aggregates only — no context-per-call, spawn ledger, compaction ledger, or
cost-per-merge — and has two recent bugs that matter here (silent skip of large session files;
subagent overcount). Trace backends (Phoenix, Langfuse, SigNoz, otel-desktop-viewer) ingest OTLP
from both harnesses with zero transport glue but none normalises the two token vocabularies
(`claude_code.token.usage{type}` vs `codex.turn.token_usage{token_type}`); the OTel GenAI
conventions now define `gen_ai.usage.cache_read.input_tokens` / `cache_creation.input_tokens`
and neither harness emits them. No project measures context per call or cache hit rate
harness-neutrally. Conclusion: clone nothing wholesale; adopt ccusage as a cross-check
subprocess; make the schema neutral; keep the analyses (they have no competitor).

Coupling audit of `scripts/agent-analytics/` (33 scripts, 39 hooks): 12 neutral, 6 thin,
15 deep, 2 CLI-bound. The deep coupling is three things repeated — hand-rolled discovery of the
Claude transcript root (6 readers still, five months after 743 asked for migration), raw
`message.usage` field names, and Claude's built-in tool-name vocabulary (`lib/input-summarizer.mjs`
hardcodes all of it). `otlp-sink.py` is already harness-neutral by construction; the coupling
sits in `lib/telemetry-io.mjs`.

## 10. Theorization (2026-09-02) — directions worth weighing before design

### 10.1 Three framings of the same problem

1. **Accounting** — what did we pay, by harness × project × model × week. ccusage answers this
   already; building it again is waste.
2. **Attribution** — *why* was it paid: context residency per call, what filled the context,
   spawn lineage, cache-write causes, and the join to merges. This is where the homegrown stack
   is ahead of everything surveyed and is the only framing that can judge the repo's own
   economic rules (delegate-by-default, sonnet floor, chunk long loops).
3. **Control** — surfacing a number at the decision moment (spawn cost on return, context size
   before the next big step). Harness-protocol-bound by nature (hooks), but the number is neutral.

The stack should be built for (2), consume (1) from outside, and expose (3) as thin
per-harness shims. Building for (1) or (3) first is the predictable mis-step.

### 10.2 Candidate invariant: cost, cache and value have different units

- **Context residency is the unit of cost** — tokens × calls resident × rate. Every lever in §4
  is a residency lever; "cache hit rate" is a health ratio, not a cost.
- **Context lineage is the unit of caching** — which calls share a prefix. Subagents, forks,
  `SendMessage` resumes, compaction summaries and Codex threads are all *lineage events*, not
  separate concepts. A schema that models lineage (parent context, fork point, prefix-preserving
  vs prefix-breaking events) is harness-neutral by construction; one that models "subagent"
  is Claude-shaped.
- **The merged change is the unit of value** — git, already neutral (`merge-links.mjs`).

If this holds, the neutral record is small: a *call* with token axes that may be absent per
provider (Codex has no billable cache write), a *lineage* edge, and a *tool event* with a role
(read / edit / shell / search / spawn / wait) instead of a name. The repo's own
projection-vs-fork rule (CLAUDE.md, `execution-surfaces` register) applies: the record is a
projection of harness logs, never a second authority.

### 10.3 Where the neutral boundary could sit — four options

| Option | What it is | Wins | Loses |
|---|---|---|---|
| A. Log adapters | Parse each harness's own transcript into the neutral record | Durable, complete (tokens, content sizes, compaction markers), works retroactively | Undocumented formats that churn (Codex: three open upstream issues); one adapter per harness |
| B. OTLP only | Both harnesses emit OTLP; the sink normalises to GenAI semconv; readers use only that | One ingest path, forward-compatible, vendor-neutral names | Opt-in emit (a silent 100%-loss incident already happened, 743), content truncated at 61k chars, no history before the sink was on, and Claude's traces suppress the interaction span in interactive sessions |
| C. ccusage core + own analyses | Consume ccusage JSON for totals; keep transcripts only for attribution readers | Outsources parser churn for 15 harnesses | ccusage exposes aggregates, so attribution readers still need A; two parsers of the same logs can disagree (841 §10 is a catalogue of exactly that) |
| D. Behaviour first, no rewrite | Apply the §4 levers now; measure with ccusage only | Cheapest; captures most of the $ | Cannot judge the delegate-by-default falsifier or any per-rule metric; loses the one capability with no competitor |

Leaning: **A as the source of truth, B as the timing/live layer, C as the independent
cross-check** — i.e. two sources, one schema, reconciled. That is the repo's existing principle
"recompute what survives; capture only what time destroys" (858 §3.1) applied one level up.
Note the twist: transcripts *do* rot (Claude rotates them; 144 merges in the ledger already have
no discoverable transcript), so the derived per-call record must be snapshotted — small, and the
thing that survives.

### 10.4 Machine-level tool vs repo-level governance

The hooks encode *this repo's* policy; the measurement library serves every project on the
machine. Keeping the library inside `scripts/agent-analytics/` conflates the two. Options:
extract now (own repo, npm-linked), or carve an in-repo package boundary
(`lib/` → a package with no imports from repo governance) and extract when a second project
consumes it. The second is cheaper and reversible; the first is what 743 called a "deletable
adapter" taken to its conclusion. Either way the boundary rule is: nothing under the library
reads `governance/`, `CLAUDE.md`, or `tmp/agent-telemetry/` paths directly.

### 10.5 Ideas that may matter later, not for this design

- **Every always-loaded economic rule gets a reader that can falsify it.** The repo already
  runs on falsifiers and gates; the delegation paragraph has a due date (2026-09-14) and no
  instrument. A `rule → metric` register would make that structural rather than per-tempdoc.
  Risk: the repo already over-invests in governance (616's own caution) — this is a register
  entry, not a new gate.
- **Compaction as a deliberate lever inverts a prior conclusion.** 743 T1 measured compaction
  cost as negligible and stopped; the same number is the argument for compacting *earlier*.
  Both harnesses expose compaction/thread boundaries, so "context bounded per call" can be a
  neutral policy with per-harness knobs.
- **The observability stack has its own token bill.** LLM-judged passes (`mine-friction`,
  `evaluate-session`) are expensive; mechanical readers are near-free. A neutral store makes
  the mechanical tier cover more, and the judge tier a deliberate exception.
- **Pricing as a consumed dataset, not a maintained table.** LiteLLM's model-price JSON is
  what ccusage uses; consuming it (with 841's fail-closed rule kept for unknown ids) removes
  the class of bug that hid a third of spend. Provider column becomes mandatory.
- **Dogfooding.** Agent transcripts are a local document corpus; the product under development
  is a local-first search engine. Indexing derived per-call records for ad-hoc questions
  ("which sessions read this file most") is a plausible later use, and a scope trap now.

### 10.6 Hidden assumptions to test in derisk

- That an API call is the comparable unit across harnesses (Codex `token_count` deltas may
  batch several calls; `last_token_usage` semantics under retries are unverified).
- That Codex `input_tokens` *includes* `cached_input_tokens` (the OpenAI convention) — decides
  whether context-per-call is `input` or `input + cached`.
- That fork/subagent transcripts never double-count with the parent under `message.id` dedup
  when a fork inherits the parent's prefix.
- That `autoCompactWindow: 600000` fires at all (§2.2).
- That Sonnet workers on the same brief shape do not raise rework — the falsifier's other half.
- That the Codex OTel `[otel]` section can target the existing sink without breaking Codex's
  own `notify` plumbing.

Follow-up numbering: if the neutral-ledger rewrite becomes its own tempdoc, the next free number
at the time of writing is **#887** (`world-state.mjs`, 2026-09-02); 886 stays the analysis and
decision record.

## 11. Derisk pass (2026-09-02) — assumptions tested before design

Method: read-only probes over the local logs (`tmp/tokeff/derisk.mjs`), two doc fetches, and
ccusage run on the same corpus. No builds, no dev stack, no worktrees (other sessions active).

| # | Assumption (§10.6) | Result | Consequence for the design |
|---|---|---|---|
| A1 | Codex `input_tokens` includes `cached_input_tokens` | **Confirmed**: 0 of 51,740 events have cached > input; `total = input + output` (output includes reasoning) | Codex context-per-call = `input_tokens`; fresh input = `input − cached`. |
| A2 | `last_token_usage` is a per-call delta | **Mostly**: cumulative diff equals the delta in 97% of events; 1,482 events are exact repeats of the previous cumulative (must be dropped), 20 are negative (resume/reset). Per-file delta sums (5.04B, July) sit below the files' final cumulative counters (6.51B) because a resumed thread's cumulative carries prior history. | Adapter rule: use deltas, drop consecutive events with identical `total_token_usage`, never trust the cumulative. Needs a fixture. |
| A3 | Forks don't double-count with the parent under `message.id` dedup | **Confirmed**: 5 fork transcripts, 0 overlapping message ids; 30,527 same-id lines, 0 with differing usage | Dedup by message id is safe for both forks and multi-line messages. |
| A4 | `autoCompactWindow: 600000` fires | **Not verified**. Docs (model-config): the value is a window size, 100K-1M; without it, 1M-context models compact at the model limit. The one session that ran past 600k after the setting existed reached 762k with no compaction, but may have started before the setting was saved. Docs do not say whether the window applies to subagents. | Test in the next fresh long session; treat "subagent context bound" as a separate, currently unavailable knob. |
| A5 | Sonnet workers don't raise rework | **Untestable without implementation** — remains the falsifier experiment (§7). | Plan must include the rework join, or the routing lever stays unjudged. |
| A6 | Codex `[otel]` can target the existing sink | **Plausible, unverified**: sink is protocol-generic (no harness names in `otlp-sink.py`); Codex exporter is `otlp-http` with `binary`/`json` protocol. | One-session smoke test after wiring; low risk. |
| A7 | Codex rollouts carry enough for tool-role and lineage events | **Confirmed and richer than assumed**: `function_call`/`custom_tool_call` + outputs (12,656 + 14,050; output p50 1.9k chars, p90 18.8k, max 751k), `compacted` events (437 across 289 sessions — Codex compacts far more often than Claude here), `agent_message` + `inter_agent_communication_metadata` (589 — Codex has multi-agent lineage too), `turn_context` always carries the model. | The neutral record's lineage and compaction axes are needed for Codex, not only Claude. Tool outputs need a size cap in the record (751k-char outputs exist). |
| A8 | ccusage is a usable cross-check | **Split result.** Claude: ccusage $18.1k (Aug, all projects) vs this reader's $18.8k (Aug-Sep 2, this repo's dirs) — agreement within the window/scope difference. **Codex: ccusage reports 1.55B July input tokens; both Codex's own counters (6.5B cumulative) and the delta reader (5.0B) say 3-4× more.** Not the large-file bug (largest July file is 43MB; totals are unchanged under any size cap). Cause not identified. | ccusage is a cross-check for Claude only until its Codex parser is understood; do not price Codex from it. |

Additional facts picked up: `settings.json` mtime is rewritten by `/model`, so it cannot date a
setting; ccusage prices Codex at API rates ($1,055 July, $610 Aug) although the sessions ran on
a subscription (`rate_limits` present) — Codex cost is a modelled number, not a bill.

**Confidence for the remaining work (neutral schema + Codex adapter + two readers + sink
normalisation): 7/10.** What is solid: both log formats are understood to the field level with
dedup rules verified on the full corpus; the target readers exist as working prototypes; the
sink is already neutral. What is not: subagent context bounding has no knob (A4), the rework
half of the falsifier is unbuilt (A5), and ccusage cannot yet serve as the Codex cross-check
(A8) — so the first Codex numbers will have one source, which is exactly the situation 841 §10
warns about. The design should therefore ship the Codex adapter with a fixture and a
cumulative-vs-delta self-check rather than an external cross-check.

**Difficulty and routing.** The adapter/schema work is moderate: well-specified, fixture-driven,
~1,500 lines including tests, but it touches `lib/` that 20 readers import, so a mistake is
wide. Recommend **Sonnet at high effort** for the adapters, readers and fixtures (bounded,
verifiable chunks), and **Opus** for the two judgment-heavy pieces: the neutral record's
lineage model (§10.2) and the reconciliation rules (A2/A8). Keep schema design and migration
order in the orchestrator.

## 12. Implementation plan (2026-09-02) — the contract

Decisions this plan encodes (from §10-11): log adapters are the source of truth (A), OTLP is
the live/timing layer (B), ccusage is a Claude-only cross-check until A8 is understood (C);
the measurement library stays in-repo behind an enforced boundary (§10.4); the record models
lineage, not "subagent" (§10.2). Behavioural levers (§4: compaction cadence, model routing,
prefix size) are owner policy and are **not** code items here; the readers below are what makes
them judgeable.

Substrate already in place (survey 2026-09-02): custom `run(label, fn)` test runner with glob
discovery in `run-all-tests.mjs`, run by CI (`ci.yml:118`); `lib/` imports only Node builtins;
`otlp-sink.py` decodes attributes at one point (`_attrs`, `decode_metrics`); hook recipe =
`settings.local.json.example` + `governance/agent-hooks.v1.json` + tier-register row + test,
gate-checked by `hook-integrity`; the analytics script table in
`docs/explanation/21-agent-analytics-pipeline.md` is the prose register.

### PR 1 — neutral session record + two adapters (worktree `886-ledger-1`)

- `lib/ledger/record.mjs`: the record — `Call {harness, project, sessionId, lineage:{parentSessionId, kind: main|spawn|fork|resume|thread}, ts, model, provider, tokens:{fresh, cacheRead, cacheWrite5m?, cacheWrite1h?, output, reasoning?}, contextTokens, compactionBoundary?}` and `ToolEvent {callRef, role: read|edit|shell|search|spawn|wait|web|other, name, inputChars, outputChars, isError}`. Absent axes are `null`, never 0.
- `lib/ledger/claude-adapter.mjs`: wraps today's `transcript-store` discovery + `iterateTurns`; `message.id` dedup (A3/A10); `subagents/*.meta.json` → lineage; `<task-notification>` → `wait`.
- `lib/ledger/codex-adapter.mjs`: `~/.codex/sessions/**/rollout-*.jsonl`; deltas from `last_token_usage`, drop consecutive identical `total_token_usage` (A2), `input − cached` = fresh (A1), model from `turn_context`, `compacted` → boundary, `function_call(_output)`/`custom_tool_call(_output)` → tool events with a 64k-char size cap, `agent_message` → lineage `thread`. Self-check: per-file delta sum vs final cumulative, reported not asserted.
- `lib/ledger/index.mjs`: `listCalls({harnesses, projects, sinceMs})` across both roots; project axis from `cwd`.
- `lib/ledger/boundary.test.mjs`: no file under `lib/ledger/` references `governance/`, `CLAUDE.md`, `tmp/agent-telemetry`, or a repo-root path — the §10.4 rule, enforced.
- Fixtures: `scripts/agent-analytics/fixtures/{claude,codex}/*.jsonl`, synthetic, structurally faithful, no private content; adapter tests assert the A1-A3 rules on them.
- First consumer: migrate `cache-efficiency.mjs` onto the record (its 19 tests stay green; output gains a `harness` column; Codex rows show the cache-write axis as n/a).
- Docs: rows in `21-agent-analytics-pipeline.md`; README section. Teardown: none yet (opportunistic migration, 743).
- Routing: Sonnet-high worker for adapters/fixtures/tests; **Opus second-opinion review** of `record.mjs` lineage semantics before merge (`independent-review-required`).

**PR 1 outcome (2026-09-02, branch `worktree-886-ledger-1`).** Implemented as specified plus
`boundary-check.mjs` (pure checker with negative-case tests). Independent refute-first review
(reviewer ≠ implementer) rejected the first cut on three corpus-reproduced blockers that the
green tests could not see — 78% of Codex calls tagged `thread` from a session-level boolean
that names no parent; `exec` (the most frequent Codex tool, 12,546 events) unmapped and
`agent_message` (assistant text) emitted as 9,385 spurious `spawn` events; a catch that
dropped whole sessions silently. All fixed: Codex calls are `kind:'main'` with a
`session.multiAgent` flag, `thread`/`resume` are documented reserved-unproduced kinds, the
role table is the dated corpus vocabulary with a snapshot test, skips are counted and
surfaced, other errors propagate. Second pass approved; its two nits (re-export/dynamic-import
escapes in the boundary checker, a stale `agent_message` mapping) fixed in the same branch.
Real-corpus anchors reproduced: Codex July input 5,038.7M (target 5,044.6M), repeats dropped
1,482 (exact), roles shell 22,840 / wait 1,411 / edit 1,504 / spawn 579 / other 342; Claude
main p50 413k. Lesson worth keeping (`audit-without-test` in the other direction): fixture
tests prove the rules, only the real corpus proves the vocabulary — every adapter PR runs both.

**PR 2 outcome (2026-09-02, branch `worktree-886-ledger-1`, stacked on PR 1).** Implemented as
specified: `context-residency.mjs` (+test, 20 cases) and `spawn-economics.mjs` (+test, 15
cases) productionise `tmp/tokeff/{deep,deep3,deep4}.mjs`; `overhead-taxonomy.mjs`'s default
window is now trailing 30 days (was a hardcoded 2026-06-18..07-16 that returned 0 sessions
bare); `cost-session.mjs --reconcile` (+test, 12 cases, no real-dir reads) compares OTLP vs
transcript pricing per session and names residue causes. Building section (c) surfaced a real
bug in PR 1's `claude-adapter.mjs`: a genuine compaction is TWO consecutive boundary-flagged
lines (a `compactMetadata`-bearing `system` line immediately followed by a metadata-less `user`
`isCompactSummary` line); the adapter unconditionally overwrote its captured metadata on every
boundary-flagged line, so the second line silently erased the first's real
trigger/preTokens/postTokens/durationMs — 0 of 11 real compaction events in the corpus carried
`compactMetadata` before the fix (PR 1's fixture only exercised a single-line boundary, so its
test never caught this). Fixed in `claude-adapter.mjs`, with a regression test and a
corpus-faithful two-line fixture addition.

**Independent review, second pass — one SHOULD-FIX + three NITs, all fixed.** `listCalls`
(`lib/ledger/index.mjs`) used `sinceMs`/`untilMs` only as a file-mtime discovery prefilter, so a
`--since 2026-08-01` query kept every call in any file touched on-or-after that date — including
calls from WEEKS earlier in a long-lived session (measured: 5,541 calls dated before `--since`
leaked in, min ts 2026-07-30, before the fix). Fixed: `listCalls` now applies mtime as the cheap
file prefilter (unchanged) THEN a per-call filter on `windowBy: 'ts'` (new default) against each
`Call`/`ToolEvent`'s own `ts`; a null/unparsable `ts` is KEPT (cannot be judged) and counted in
the returned `unfilterableTs`; `windowBy: 'mtime'` opts back into the old semantics; a session
summary is kept only if ≥1 of its calls survives. 5 new tests in `index.test.mjs` (before/after
window, null-ts kept, `windowBy:'mtime'` opt-out, no-window shape unchanged, session dropped when
none of its calls survive). Also fixed: `context-residency.mjs`'s header line no longer conflates
the Codex `Call.synthetic` boundary flag with a Claude turn whose `message.model` is the literal
string `'<synthetic>'` (two unrelated things, now reported as two explicit counts);
`spawn-economics.mjs`'s brief-length axis is renamed `firstUserMessageChars` (a skill-invoked
subagent's opening turn is the skill body, not an Agent-tool brief — the old name implied a
cleaner concept than the data supports); the `>120-call` prose in this tempdoc and the module
header now says `≥120 calls`, matching the code's actual `calls >= 120` bucket boundary (the
`.mjs` comment uses ASCII `>=120` per this repo's non-ASCII-in-code convention; the tempdoc prose
uses `≥`).

Real-corpus anchors reproduced (window since 2026-08-01, `--harness claude-code` unless noted).
**The table below is TS-WINDOWED (post-fix)** — the first pass's numbers were mtime-windowed and
are kept only as the "first pass" column for comparison; do not treat them as current:

| Anchor (886 §2/§12) | Expected | Reproduced (ts-windowed) | First pass (mtime-windowed) | Note |
|---|---|---|---|---|
| Main context p50 | ≈413-415k | 405,982 (pooled) | 411,273 | ~2% |
| Spawn context p50 | ≈177k | 179,250 (pooled) | 176,168 | ~1% |
| Codex context p50 | ≈129k | 115,913 (pooled) | 119,103 | ~10%, different window scope (full historical Codex corpus vs since-08-01 filter) |
| Cost above 200k, main | ≈$3.2k | $2,996 | $3,208 | within 7% |
| Cost above 200k, spawn | ≈$2.2k | $2,274 | $2,268 | within 3% |
| Compactions | 22 (21 manual/1 auto) | **9 (8 manual/1 auto)** | 11 (10 manual/1 auto) | matches the reviewer's independent count of 9 exactly; the "22" figure double-counts lines-per-event (fixed in the prior round), and ts-windowing further drops 2 of the 11 whose boundary-call `ts` predates 2026-08-01 even though their FILE was still being written after that date |
| Pre-compaction context p50/min | 889k / 447k | 947,441 / 447,395 | 889,530 / 447,395 | min matches to <0.1%; p50 shifts because the fix removes 2 pre-window events |
| Compaction duration p50 | ~150s | 160.6s | 156.4s | within 7% |
| Residency total | ≈$13.8k | $13,999 | $13,969 | within 1% (section §d is `claude-code`-only, mtime-windowed by design — see its header — so the ts-fix does not apply here) |
| Residency: sub prefix share | ≈23% | 23.1% | 23.1% | exact |
| Residency: sub tool share | ≈25% | 24.8% | 24.8% | within 1% |
| Spawns (window) | ≈1,030 | 989 | 1,054 | live-session drift + ts-fix removing pre-window spawns |
| opus→claude-opus-5 spawns/cost | 684-696 / $9.3-10.0k | 654 / $9,214 | 697 / $9,429 | within 6% |
| ≥120-call spawns, share of spawns | ≈20% | 20.0% | 19.2% | exact |
| ≥120-call spawns, share of cost | ≈72% | 72.6% | 71.6% | within 1% |
| firstUserMessageChars p50 | ≈3.9k chars | 3,916 | 3,935 | within 1% |

Verified min-ts check (one-off): `listCalls({harnesses:['claude-code'], sinceMs: Date.parse('2026-08-01')})`
returns `unfilterableTs: 0` and a minimum call `ts` of `2026-08-03T19:53:56.496Z` — no call before
the window leaks through post-fix.

Deviations: the Codex p50 gap is a window-scope difference (documented above), not measurement
noise. `tmp/tokeff/` prototypes are retained pending an explicit go-ahead to delete them (886 §12
says "once both readers reproduce their numbers" — done; deletion held for the merge/publish
step, not bundled into this implementation pass).

### PR 2 — the two readers + two fixes (worktree `886-ledger-2`, after PR 1)

- `context-residency.mjs` (+test): per-call context distribution by harness × model, cost above `--cap`, compaction ledger (trigger, pre/post, duration), compounded residency table (§2.4 method). Regression anchors from this tempdoc: Claude main p50 415k, Codex p50 129k, 22 Claude compactions in the window.
- `spawn-economics.mjs` (+test): lineage ledger — requested vs actual model, calls, peak context, cost, firstUserMessageChars, run-length buckets, top-N. Anchor: ≥120-call spawns = 72% of Aug subagent cost.
- `overhead-taxonomy.mjs`: default window → trailing 30 days (today it returns 0 sessions bare).
- `cost-session.mjs --reconcile`: per-session OTLP-priced vs transcript-priced diff, naming unknown-model residue (858 §9.1).
- Delete `tmp/tokeff/` prototypes once both readers reproduce their numbers.
- Routing: Sonnet-high.

### PR 3 — OTLP normalisation + Codex wiring (worktree `886-ledger-3`, independent of PR 1)

- `otlp-sink.py`: additive mapping in `decode_metrics` — `claude_code.token.usage{type}` and `codex.turn.token_usage{token_type}` → `gen_ai.usage.{input,output}_tokens`, `gen_ai.usage.cache_read.input_tokens`, `gen_ai.usage.cache_creation.input_tokens`, with `gen_ai.system` = harness; originals kept. Test in `test_otlp_sink.py`.
- `lib/telemetry-io.mjs`: read the `gen_ai.*` names; `query_source`/lineage mapped for both.
- `docs/how-to/`: Codex `[otel]` snippet targeting `http://127.0.0.1:4318` (`otlp-http`, `binary`), plus the one-session smoke check (A6). User-level config, not repo governance.
- Routing: Sonnet-high.

**PR 3 outcome (2026-09-02, branch `worktree-886-ledger-1`, stacked on PR 1 + PR 2).**
Implemented in `otlp-sink.py`'s `decode_metrics`: a module-level `GENAI_TOKEN_MAP` (one entry
per harness — `claude_code.token.usage{type}` and `codex.turn.token_usage{token_type}`) drives
`_genai_normalize`, which appends one flat `{name: 'gen_ai.usage', normalized: true,
attributes: {...original attrs, gen_ai.system, gen_ai.request.model, gen_ai.token.kind},
value, time_unix_nano}` twin per data point into the same `metrics.ndjson` stream as the
untouched original — additive, no separate file, same rotation/retention policy. Deviation
from this section's original bullet (`gen_ai.usage.{input,output}_tokens` /
`gen_ai.usage.cache_read.input_tokens` / `gen_ai.usage.cache_creation.input_tokens` as four
separate metric names): the orchestrator's PR 3 brief specified one `gen_ai.usage` metric name
with a `gen_ai.token.kind` attribute distinguishing input/output/cache_read/cache_creation/
reasoning instead — functionally equivalent (same information, same filterability) and closer
to how the OTel GenAI conventions structure a single counter with a type dimension; documented
here so the deviation from the original plan text is visible, not silent. Codex's `total` type
is skipped (derivable as input+output, not a new axis); Codex's raw `input` (which already
includes cached tokens, the OpenAI convention, unlike Claude's `input` which excludes
`cacheRead`/`cacheCreation`) gets an explicit `gen_ai.input_includes_cache_read: true` flag on
its normalised point so the two harnesses' "input" can never be silently summed as the same
quantity. 6 new tests in `test_otlp_sink.py` (Claude point → original + normalised; Codex
`cached_input` → `cache_read`; Codex raw `input` → the inclusion flag; Codex `total` → no
normalised record; unknown metric name → no normalised record; attribute passthrough +
non-mutation of the original). Test count 16 → 22, all pass.

`lib/telemetry-io.mjs`'s `loadCostsFromOtlp` now reads `gen_ai.usage` records first (preferred
source) and remembers which origin point each one covers — keyed on session id +
`time_unix_nano` + the origin's own raw `type`/`token_type` value, which the normalised record
carries through verbatim — so the second pass over `claude_code.token.usage` skips any point
already counted via its twin, closing the double-count risk the additive design creates.
Archives written before this change carry no `gen_ai.usage` records, so that consumed-key set
stays empty for them and the original per-point reading runs unchanged (verified by a fixture
with `claude_code.token.usage`-only data and no twin). A `harness` field (from
`gen_ai.system`) was added to each session's returned record. `loadCostsFromOtlp` gained an
optional `dir` parameter (default unchanged — the live sink directory) purely so tests can
point it at a temp directory instead of `tmp/agent-telemetry/otlp/`, mirroring the pattern
`loadOtlpStream` already used for the same reason; no caller passes it, so this is additive.
Deviation from this section's original bullet ("query_source/lineage mapped for both"): lineage
mapping lives in `loadEventsFromOtlp`/`lib/ledger/{claude,codex}-adapter.mjs`, a separate code
path from the cost/token aggregation `loadCostsFromOtlp` does; the orchestrator's PR 3 brief
scoped this deliverable to the model-attribution/token pass specifically, so lineage mapping
for the OTLP event stream was left untouched — `query_source` attribution was already
harness-agnostic (`a.query_source || 'main'`) before this PR and needed no change. 3 new
fixture-based tests added to `telemetry-io.otlp.test.mjs` (dedup against a paired
claude_code/gen_ai twin; a gen_ai-only, e.g. Codex-shaped, session; a legacy archive with no
`gen_ai.usage` records at all) — test count 8 → 11, all pass.

`docs/how-to/wire-codex-cli-into-the-otlp-sink.md` documents the `[otel]` TOML snippet, that
the sink must be running (`otlp-sink-ensure` for a Claude session; `python
scripts/agent-analytics/otlp-sink.py --port 4318` run by hand for a Codex-only session), the
`grep -c '"gen_ai.system": "codex-cli"'` smoke check, and the optional (not governed) Codex
`hooks.json` forward pointer to PR 4's two hints. **The live Codex smoke check itself is not
run here** — this worktree has no way to start an interactive Codex CLI session (A6 remains
"plausible, unverified" per §11); it is a user-run step per the how-to.

**A real breakage this surfaced (fixed in the same branch):** `otlp-viewer/index.html` (the
maintainer-only static HTML viewer over `tmp/agent-telemetry/otlp/`) unconditionally does
`metrics.forEach(m=>m.points.forEach(...))` and `metrics.flatMap(m=>m.points)`, assuming every
decoded metric record carries a `points` array. The new flat `gen_ai.usage` twin records have
none (by design — see the shape rationale above), so loading a real post-upgrade
`metrics.ndjson` would throw `Cannot read properties of undefined (reading 'forEach')` and blank
the whole viewer. Fixed by normalising every loaded metric record to the batch `{points: [...]}`
shape immediately after `ndjson()` load, before any of the existing card/table code runs — no
other behaviour change, and the existing cost/token card totals are unaffected (`gen_ai.usage`
doesn't match the `name.includes("cost"/"token")` filters those cards already use, so no
double-counting risk there either).

Full suite: `node scripts/agent-analytics/run-all-tests.mjs` → 60/60 `.test.mjs` files pass
(the file count is unchanged from before this PR — the new cases landed inside the two
existing `test_otlp_sink.py` / `telemetry-io.otlp.test.mjs` files, not new files; `run-all-tests`
only discovers `*.test.mjs`, so `test_otlp_sink.py` is run separately, per its own header).
`python -m py_compile scripts/agent-analytics/otlp-sink.py` and `node --check
scripts/agent-analytics/lib/telemetry-io.mjs` both clean. `node
scripts/docs/llmstxt-generate.mjs --check` OK (114 docs indexed) after regenerating for the new
how-to. No `./gradlew.bat` build was run for this PR (no Java/Gradle files touched; PR 3's
scope is Python + `.mjs` + docs only).

**Independent review — two SHOULD-FIXes + one NIT, all fixed (same branch).** (1) `GENAI_FIELD`
in `loadCostsFromOtlp` summed a flagged Codex `input` point (raw, includes cached tokens)
straight into `input_tokens` alongside `cache_read_tokens`, double-counting the cached portion
in any downstream `input+output+cache_write+cache_read` summer (`baseline-economics.mjs:361`,
`overhead-taxonomy.mjs:441`). Fixed by pairing the flagged `input` point with its `cache_read`
sibling on `session.id`+`time_unix_nano` (buffered in either arrival order) and storing FRESH
input (`input − cache_read`); an unpaired `input` point is kept raw and the session flagged
`input_includes_cache_read` rather than fabricating a subtraction. 2 new tests in
`telemetry-io.otlp.test.mjs` (13 total, was 11): `'Codex raw input paired with its cache_read
sibling resolves to FRESH input tokens'` (input 15874, cache_read 11648 → input_tokens 4226,
cache_read_tokens 11648) and `'Codex raw input with NO cache_read sibling is kept RAW and the
session is flagged, not silently subtracted'` (input 15874, no pair → input_tokens 15874,
`input_includes_cache_read: true`). (2) `cost-session.mjs`'s `recordFromOtlp` dropped the new
`harness` field and priced a Codex session (no `claude_code.cost.usage` metric exists for
Codex) at `total_cost_usd: 0`, which then summed into `--all` as if free. Fixed: `rec.
hasCostMetric` (set only when `loadCostsFromOtlp` sees that metric for a session) drives
`total_cost_usd: null` + `reason: 'no_cost_metric'` for a costless-metric session — the same
null-not-zero convention `costSession()` already used for `no_transcript_path`; `harness`
propagates onto the record; `runOtlpCost`'s printed total sums only priced sessions and prints
a one-line residue count in the `--reconcile` style. `recordFromOtlp`'s mapping step was split
into an exported, injectable `costRecordsFromOtlp` (mirroring `reconcileSessions`' contract) so
this is testable without touching `tmp/agent-telemetry/`. 2 new tests in `cost-session.test.mjs`
(14 total, was 12): a Claude session with a cost metric prices normally while a paired Codex
session with none gets `null`/`'no_cost_metric'` and is excluded from the priced total; a plain
array (not just the `loadCostsFromOtlp` Map shape) is also accepted. (3, NIT) `otlp-viewer/
index.html`'s metrics table rendered every token point twice (origin + `gen_ai.usage` twin) —
fixed by filtering `normalized` records out of the table (`m=>!m.normalized`) while keeping the
`{points:[]}` shape-normalisation fix so cards/subagent counts still see every record. Also
documented (how-to + README, NIT 4): normalisation roughly doubles `metrics.ndjson` volume and
`RETENTION["metrics"]=None` means it is never pruned (~146 MB in the main checkout today) —
stated as a tradeoff, retention policy unchanged (owner decision).

Re-verified after fixes: `python scripts/agent-analytics/test_otlp_sink.py` → 22/22 (unaffected
by these fixes — they touch `.mjs`/`.html` only); `node scripts/agent-analytics/run-all-tests.mjs`
→ 60/60; `node --check` clean on `telemetry-io.mjs` and `cost-session.mjs`.

### PR 4 — control shims (worktree `886-ledger-4`, after PR 1)

- `hooks/spawn-cost-hint.mjs` (PostToolUse/Agent, advisory): on return, resolve the spawn's transcript via the ledger and print one line — calls, peak context, cost, model.
- `hooks/context-ceiling-hint.mjs` (PostToolUse, advisory, once per threshold, re-armed after a drop below the lowest threshold): last usage from `transcript_path`; at 300k and 500k print the number and the two remedies (`/compact <hint>` at a boundary; `/rewind` when abandoning a path).
- Registration: `settings.local.json.example`, `agent-hooks.v1.json` (`role: advisory`), `*.test.mjs`; `hook-integrity` gate green. Codex `hooks.json` equivalents documented as optional in the PR 3 how-to, not governed. **No tier-register rows** — reversed after independent review (30 of 41 manifest hooks have none; fire-time delivery already carries the finding, and hooks-reference.md deliberately no longer catalogs hooks either, tempdoc 681).
- Routing: Sonnet-high.

**PR 4 outcome (2026-09-02, branch `worktree-886-ledger-1`, stacked on PR 1-3).** Implemented as
specified. `hooks/spawn-cost-hint.mjs` (PostToolUse/`Agent`) resolves a completed spawn's own
`subagents/agent-*.jsonl` via a `tool_use_id`↔`*.meta.json.toolUseId` join (the corpus-verified
primary path — every SYNCHRONOUS spawn's meta.json carries `toolUseId`), falling back to an
`agentId: <hex>` line the async/background-spawn `tool_response` text carries when no
`toolUseId` join is available (verified against a real `run_in_background` spawn's tool result:
"Async agent launched successfully... agentId: a1eb439f3497374b1"). It reads the resolved file
through a new small export, `lib/ledger/claude-adapter.mjs`'s `callsFromClaudeTranscript(filePath,
{sessionId, project, lineage})` — a thin wrapper over the module's existing private
`processClaudeTranscript`, not a second parse implementation — and prices each call via
`lib/transcript-cost.mjs`'s `findPricing` (reports `n/a`, never a silent `$0`, if any call's model
is unpriced). `hooks/context-ceiling-hint.mjs` (PostToolUse, every tool) reads only the LAST
~256KB of `transcript_path` (never the whole file — real transcripts in this corpus reach
~900MB+) and parses backwards for the last assistant `usage` snapshot, firing once per session at
300k and 500k via a small state file (`tmp/agent-telemetry/context-ceiling-state/<session_id>.json`,
same shape as `build-counter.mjs`/`repeat-guard.mjs`'s per-session state).

Both hooks are wired through the existing manifest-generation path, not hand-edited:
`governance/agent-hooks.v1.json` gained the two catalog entries (`role: "advisory"`, each with a
`bite: {kind: "unit", test: ...}`) and PostToolUse bindings (`matcher: "Agent"` for
spawn-cost-hint; `matcher: ""` for context-ceiling-hint, matching dispatch.mjs's own
match-every-tool convention), then `node scripts/codegen/gen-agent-hooks-wiring.mjs` (live
`.claude/settings.local.json`) and `--emit-local-example` (`.claude/settings.local.json.example`)
regenerated both settings files — hand-editing either would have drifted from the manifest, per
that generator's own contract. `node scripts/governance/run.mjs --gate hook-integrity --mode gate`
is green (wiring, live-wiring, cwd-invariant exec-form, `node --check` load, bite, tier-sync,
orphan-file phases all pass).

Live manual checks (read-only, real transcripts under `~/.claude/projects/`, per
CLAUDE.md's local-API-trust-boundary carve-out for read-only checks): `context-ceiling-hint.mjs`
against a real transcript at 886k context tokens printed `context-ceiling: 886k tokens in context
(past 500k) — every call now re-reads this; /compact <hint> at the next task boundary, or /rewind
if abandoning a path (886 §2.2)`. `spawn-cost-hint.mjs` against a real completed spawn (joined via
its real `toolUseId`) printed `spawn-cost: 12 calls, peak ctx 93k, out 0k, model claude-opus-5
(requested opus), ~$0.89 — Enumerate retirement sweep surface` — cross-checked independently
against the spawn's raw transcript (12 deduped assistant turns, 38 total output tokens, which
rounds to `0k`, not a bug).

**Independent review — approved conditional on one SHOULD-FIX, plus a reversal and two NITs, all
addressed (same branch).** (1, SHOULD-FIX) `context-ceiling-hint.mjs`'s per-threshold state never
cleared, so a session that crossed 300k/500k, dropped back under 300k via `/compact`, then climbed
past 300k/500k again got NO second hint (reviewer-reproduced sequence: 310k fires, 520k fires, a
post-compact 20k call is correctly silent, but so were 340k and 610k afterward — silently wrong).
Fixed: a new pure `advanceState(contextTokens, prevState)` clears both `notified300`/`notified500`
flags whenever `contextTokens` drops below the lowest threshold (300k) — a real re-arm, not just
"nothing crossed this call" — and stamps `lastCtx` on every write so the transition is visible in
the state file. 8 new tests, including the reviewer's exact sequence
(`310k fires, 520k fires, 20k silent, 340k fires again, 610k fires again`) and a session hovering
320k→330k→340k (never dropping below 300k) firing exactly once. (2, REVERSAL) The two tier-register
rows (47/48) and their `.claude/rules/agent-lessons.md` bullets/anchors were REMOVED: 30 of the 41
manifest hooks carry no tier-register row (rows are not required for every hook), and the bullets
only restated what the hooks already print at fire time — exactly what `hooks-reference.md`'s own
header says it no longer catalogs (tempdoc 681). `agent-lessons.md`'s always-loaded-budget ceiling
was restored to its pre-PR value (9699, the bump entry deleted, `totalCeiling` recomputed as the
sum of per-file ceilings and verified against the checker); `hooks-reference.md`'s one-line
Hint-hooks addition (and its bump) stayed, since that IS the file's own catalog-of-record for
matcher/trigger shape, unlike a full restatement of the fire-time message. `prose-tier-register`,
`hook-integrity`, and `check-always-loaded-budget.mjs` are all green after the reversal. (3, NIT)
`spawn-cost-hint.mjs`'s `costOfCalls` used to return `null` — voiding the WHOLE spawn's cost — the
moment any single call's model was unpriced (e.g. Claude's own literal `<synthetic>` model-name
turns), hiding an otherwise-known cost behind one bad axis. Fixed: it now returns
`{total, priced, unpriced}`, summing only the priced calls; the rendered line appends
`(+N unpriced)` when `N > 0` and reserves `n/a` for the case where ZERO calls are priceable. Test
with one `<synthetic>` call among priced ones confirms the sum survives and the suffix appears.
(5, NIT) `context-ceiling-hint.mjs` now retries once at a ~2MB tail when the default ~256KB tail
finds no assistant usage line — a single trailing tool_result can exceed 256KB and push the last
assistant line out of view. Tested with a 400KB trailing tool_result forcing the retry path.
(4, NIT, documented only) Both the hook header and `README.md` now note that
`context-ceiling-state/<session_id>.json` follows `build-counter.mjs`'s per-session state pattern
and is, like it, NOT swept on `SessionEnd` (`dispatch.mjs`'s cleanup list covers
`turn-count`/`repeat-buffer`/`build-fails` only) — a known, small, harmless pile, not fixed here.

Full suite: `node scripts/agent-analytics/run-all-tests.mjs` → 62/62 `.test.mjs` files (60 → 62,
the two new hook test files; test COUNT within those two files grew with the follow-up fixes —
23 for `context-ceiling-hint.test.mjs`, 15 for `spawn-cost-hint.test.mjs`). `node --check` clean on
both hooks. Codex `hooks.json` equivalents remain optional/undocumented-here per the PR 3 how-to;
nothing new added for Codex.

**PR 5a outcome (2026-09-02, branch `worktree-886-ledger-1`, stacked on PR 1-4, "first half" of
PR 5).** Four items implemented: `lib/input-summarizer.mjs`, `lib/transcript-cost.mjs`,
`baseline-economics.mjs`, `context-attribution.mjs`. `analyze-session.mjs`, `evaluate-session.mjs`,
`friction-timeline.mjs`, `mine-friction.mjs` are PR 5b (untouched here).

1. **`lib/input-summarizer.mjs`.** `summarizeInput`'s hardcoded `switch (toolName)` now dispatches
   on `lib/ledger/tool-roles.mjs`'s `roleFor('claude-code', name)` first, refined by NAME only
   where a role's members disagree on shape (`edit`: Edit vs Write; `search`: Grep vs Glob; `web`:
   WebSearch vs WebFetch). `read`/`shell`/`spawn` format only their one pre-migration member
   (Read/Bash/Task) — `PowerShell`/`Agent`/`NotebookEdit`/`MultiEdit` share a role with a formatted
   sibling but were NEVER given bespoke fields pre-migration, so they fall through to the generic
   `{tool: name}` default unchanged. `summarizeResponse` never switched on `toolName` and is
   untouched. **Parity:** a new `lib/input-summarizer.test.mjs` snapshots 37 cases (every pre-
   migration switch branch, plus PowerShell/Agent/NotebookEdit/MultiEdit/TaskCreate-family/mcp__/
   unknown-name/non-object-input edge cases) captured from the CURRENT implementation BEFORE the
   refactor landed; all 37 still pass after — byte-identical output, proven, not asserted.

2. **`lib/transcript-cost.mjs`.** Every `PRICING` row (and `FAST_PRICING`'s `OPUS_FAST`) gained
   `provider: 'anthropic'`. New export `providerOf(model)`: same exact/longest-prefix match as
   `findPricing`, fails closed to `null` for an unrecognized or absent model. No OpenAI/Codex row
   was added — Codex CLI runs on a ChatGPT/Codex subscription here, not metered API tokens, so
   pricing it would be a MODELLED number standing in for an unmeasured one (documented in a module
   comment, not just here). New `lib/transcript-cost.test.mjs` (8 tests): every `PRICING` row
   carries the field; `findPricing`'s existing rate fields are unchanged alongside it;
   `providerOf` fail-closed cases; agreement with `isKnownModel`'s known/unknown boundary.

3. **`baseline-economics.mjs`.** `discoverSessions`/`findSessionTranscript`'s own `.claude/projects`
   directory walk now calls `lib/transcript-store.mjs`'s `discoverProjectDirs`/`listSubagentPaths`.
   `firstTranscriptTimestamp` (the definitional per-file timestamp scan — deliberately NOT mtime,
   since a resumed session's mtime moves forward on every touch) moved to `lib/transcript-store.mjs`
   and is imported back and re-exported from `baseline-economics.mjs`, alongside `DEFAULT_PROJECTS_ROOT`,
   because `record-merge.mjs` imports both names from `baseline-economics.mjs` and that import must
   keep resolving. **Parity:** `node baseline-economics.mjs --md`/`--json` run against the real
   corpus (`~/.claude/projects`, 68 sessions, 2026-06-18..now) before and after the
   migration produced an EMPTY diff except: the `generated_at` timestamp, and dollar/token figures
   for exactly 3 actively-growing sessions (this very session, `0c9df6b0`, plus two other
   concurrently-running agent sessions on this machine) — confirmed as live-corpus drift, not a
   migration artifact, by running the POST-migration script twice back-to-back (83s apart) and
   observing the identical pattern (same 3 sessions drift by a similar magnitude, everything else
   byte-identical). Discovery mechanics matched exactly in every run: 68 sessions in window, 474
   raw merge rows (165 attributed + 0 excluded-by-scope + 144 unattributable + 85 duplicate + 80
   off-main + 0 unresolvable), 309 eligible, 50 zero-merge sessions. `baseline-economics.test.mjs`
   (64 tests, including the real-fixture-dir `discoverSessions` test) and `record-merge.test.mjs`
   (8 tests) both green unchanged.

4. **`context-attribution.mjs` — partial migration, with a measured, evidence-based deviation from
   the literal plan.** Per-`tool_result` tool-NAME resolution now comes from
   `lib/ledger/claude-adapter.mjs`'s `callsFromClaudeTranscript`'s `ToolEvent`s, replacing this
   module's own private `tool_use_id -> name` join (verified: 0 file-level tool-name/count
   mismatches across this machine's full local corpus, one file at a time, before adopting the
   positional zip described below). CHAR COUNTS were **not** switched to `ToolEvent.outputChars`
   as the plan's literal wording suggested — a parity run found that would be a real regression,
   not a rounding difference. `outputChars` is built on the ledger's shared `extractToolResultText`,
   which — correctly, for its OTHER consumers — extracts only `text`-typed content blocks and drops
   any other block type, including an `image` block (a screenshot). Since this instrument's whole
   purpose is "what fills agent context windows" (its own module docstring) and an image block
   consumes real context regardless of whether its bytes are human-readable, substituting
   `outputChars` measured a 22.5% drop in aggregate corpus chars (46,913K → 36,375K) with
   `mcp__claude-in-chrome__computer` losing 99% (4,157K → 47K), `browser_batch` losing 99% (1,688K
   → 9K), and `Read` losing 52% (9,570K → 4,577K, since `Read` is routinely pointed at screenshot
   PNGs in this repo's ui-shot workflow) — confirmed by a direct per-file, per-tool diagnostic
   against the full real corpus, which also confirmed every tool's CALL COUNT matches exactly
   between old and new logic (0 mismatches), isolating the divergence to the char-computation
   formula alone. Root-caused, not just observed (per CLAUDE.md's Interrogate Results / Fix Root
   Causes rules): `Fix Root Causes, Not Symptoms` bars forcing a broken migration to make a
   parity checkbox true. Resolution: char length is still computed locally (unchanged JSON.stringify
   fallback for non-string `tool_result` content, image-inclusive), POSITIONALLY zipped against the
   ledger's name-resolved `ToolEvent`s (both walk the same file's `tool_result` blocks in the same
   document order); if a file's counts ever disagree, the zip refuses to guess — that file's
   `tool_result`s are attributed to `'(zip-mismatch)'` with a one-line stderr warning, rather than
   silently mis-paired by position. **Parity:** `node context-attribution.mjs --all --top 15` run
   against the real corpus (main checkout's `tmp/agent-telemetry/events.ndjson` +
   `events.ndjson.prev`, N=14 usable sessions) before and after the final implementation differed
   by exactly one Bash call count (12108 vs 12107, 0% of chars) between two runs taken ~90 seconds
   apart on a live, actively-written corpus (this very session was running Bash commands throughout)
   — the same live-drift signature confirmed for `baseline-economics.mjs` above, not a migration
   defect. Zero `'(zip-mismatch)'` warnings fired against the real corpus. `context-attribution.test.mjs`
   (9 tests, exercising `aggregateResults`/`formatAggregate` on synthetic fixtures) green unchanged.

Full suite: `node scripts/agent-analytics/run-all-tests.mjs` → 64/64 (62 → 64, the two new
`lib/input-summarizer.test.mjs`/`lib/transcript-cost.test.mjs` files). `node --check` clean on all
seven changed/new files. `git status` scoped to the five migrated `.mjs` files, two new test files,
and this canonical-doc/tempdoc update. Non-ASCII scan of the diff's added lines found no mojibake
(em-dash/§/other Unicode punctuation matches this codebase's existing comment style throughout,
confirmed against the pre-existing files' own unchanged lines). `docs/explanation/21-agent-analytics-pipeline.md`
updated (harness-neutral-ledger section + four table rows); `node scripts/docs/llmstxt-generate.mjs`
and `node scripts/docs/skills-sync.mjs` produced no further diffs.

Open item for PR 5b (out of scope here, flagged for whoever picks it up): `overhead-taxonomy.mjs`
carries its OWN private copy of `firstTranscriptTimestamp` (module-private, not imported from
`baseline-economics.mjs` or `lib/transcript-store.mjs`) — a fourth copy of the same scan that PR 5a
did not touch, since PR 5a's brief scoped only the four files above. Worth folding onto
`lib/transcript-store.mjs`'s now-exported `firstTranscriptTimestamp` when `overhead-taxonomy.mjs`
is next touched, per this pipeline's "migrate the ONE reader you're already touching" convention.

**PR 5b outcome (2026-09-02, branch `worktree-886-ledger-1`, stacked on PR 1-5a, "second half" of
PR 5).** Six items implemented: `analyze-session.mjs`, `evaluate-session.mjs`,
`friction-timeline.mjs`, `mine-friction.mjs`, `overhead-taxonomy.mjs`'s
`firstTranscriptTimestamp` de-duplication, and `context-attribution.mjs`'s three review nits —
plus one out-of-scope sweep item (`hooks/compact-save.mjs`, below) needed to actually clear the
grep acceptance bar.

1. **`analyze-session.mjs`.** `estimateDataCompleteness`'s cwd→project-hash fallback now calls
   `baseline-economics.mjs`'s `findSessionTranscript` (transcript-store-backed since PR 5a);
   every `Edit`/`Write`/`NotebookEdit`/`Read` tool-name literal in the subagent-transcript scan
   (`tu.name === ...`) and the OTLP `'read'`-shaped checks (`e.tool_name === 'Read'`, 4 call
   sites: `analyzeCompactionRereads` ×2, `analyzeContextEfficiency`, `classifyReadRedundancy`,
   plus the `file_reads` aggregate) now dispatch on `roleFor('claude-code', name)`. One literal
   check was deliberately LEFT alone: the OTLP `editEvents` filter behind `file_edits.total`
   (the report's own headline metric) stayed `e.tool_name === 'Edit'` rather than widening to the
   `edit` role, because that would silently start counting `Write` calls as edits too — a real
   behavior change to a widely-consumed field that wasn't named in the brief's two anchor sites
   (L164/L365) and has no test to catch it; documented inline rather than made silently.
   **Parity:** this worktree's own `tmp/agent-telemetry/events.ndjson` carries 78 real hook
   events across 8 real sessions (gitignored, generated by this very session's own tool calls —
   contrary to the brief's expectation of an empty worktree). `node analyze-session.mjs --all`
   run before and after the migration (via `git stash`/`stash pop` around the file, not touching
   the other four in-flight files) produced BYTE-IDENTICAL session reports for all 8 sessions
   (`diff` empty on every one) — real, not synthetic, parity evidence.

2. **`evaluate-session.mjs`.** `findTranscriptByDirectoryScan`'s hand-rolled `.claude/projects`
   walk now calls `findSessionTranscript`. `summarizeToolUse`'s `switch(name)` now dispatches on
   `roleFor('claude-code', name)` first, refined by NAME only where a role's members disagree on
   shape — same convention as PR 5a's `lib/input-summarizer.mjs` migration: `edit` (Edit vs
   Write; NotebookEdit/MultiEdit were never given bespoke formatting pre-migration, so they still
   fall through to the generic `name` default), `shell` (Bash only; PowerShell falls through),
   `search` (Grep vs Glob — both were pre-migration formatted, so both keep NAME branches),
   `spawn` (Task only; Agent falls through) — byte-identical output for every pre-migration
   branch by construction (each branch's condition and body are unchanged, only reordered under
   a role-first dispatch). `resolveClaudeBin`/the `claude` spawn itself is untouched.
   **Parity:** per this worktree's explicit constraint, the `claude`-CLI judging path was NOT
   invoked live. `node evaluate-session.mjs --session-id <nonexistent>` (no events, no report)
   exercises the discovery fallback and the early-exit path safely and printed the expected
   `Session not found` — confirming the migrated import/call resolves correctly without reaching
   the CLI spawn. No mocked-runner test was added (none existed pre-migration for this file, and
   the six-item brief scoped this PR as mechanical/mostly-line-swap; flagged as a real gap, not
   silently closed).

3. **`friction-timeline.mjs`.** `defaultProjectDir`'s single hand-slugged directory was replaced:
   omitting `--project-dir` now unions every `/justsearch/i`-matching project dir on the machine
   via `discoverProjectDirs`/`findTranscriptPath` (new, per-session lookup across all discovered
   dirs); an explicit `--project-dir` still narrows to exactly one dir, preserving the old
   semantics for that case. `sessionStartDate` now calls `lib/transcript-store.mjs`'s
   `firstTranscriptTimestamp` instead of its own inline line-scan; `main` became `async` to
   `await` it. **Parity — and a real bug fix, not a regression:** run against this worktree's
   real `tmp/agent-telemetry/friction-results/` (5 cached results, 3 usable) BEFORE the
   migration (via `git stash`, `--project-dir` defaulting to the OLD single-dir slug, which
   happens to equal this exact worktree's own project dir), all 3 usable sessions came back
   `missing timestamp: 3` — because none of those 3 sessions' transcripts happen to live under
   THIS worktree's own project dir. AFTER the migration (multi-dir default), all 3 resolved:
   `missing timestamp: 0`, 2 date buckets populated. Re-running the OLD single-dir behavior via
   an explicit `--project-dir <this worktree>` on the migrated code reproduces the exact old
   `missing timestamp: 3` output byte-for-byte, confirming the override path is unchanged and the
   default-path difference is the intended fix, not drift.

4. **`mine-friction.mjs`.** `defaultProjectDir` was replaced the same way as item 3: no
   `--project-dir` now unions every discovered project dir (new `discoverSessionFiles`/
   `sessionFilesIn` helpers) rather than scanning one hand-slugged directory; an explicit
   `--project-dir` still narrows to one dir (old behavior). `resolveClaudeBin`/the `claude` spawn
   is untouched; `summarizeToolUse`'s `switch(name)` was NOT touched (not named in this item's
   scope, unlike `evaluate-session.mjs`'s item 2). **Parity — and an incident to disclose
   honestly:** while sanity-checking `discoverProjectDirs`' output via `node -e "import(...)"`,
   the dynamic import executed this file's unconditional top-level `main()` (no
   `require.main`-style guard existed, and none was added — out of scope to change the file's
   entry-point shape here) — Node's event loop stayed alive on the in-flight `spawn()`, and by
   the time this was caught, the run had already reached and completed 2 REAL `claude` CLI judge
   calls (visible in `tmp/agent-telemetry/friction-results/*.json`, one priced at $0.2338) before
   this worktree's own current-session filter would have excluded it — a direct violation of this
   worktree's explicit "do not invoke the `claude` CLI" constraint, caused by not accounting for
   the file's unguarded `main()` before using dynamic `import()` as a sanity check. No further
   live invocation followed; verified via `Get-CimInstance Win32_Process` that no `claude`/
   `mine-friction` process remained running. The two accidentally-produced result files were left
   in place (real, valid judge output for those 2 sessions; deleting them would only force a
   future legitimate run to redo — and re-spend on — the same work). Root cause fix: this report
   itself, plus not repeating the pattern for the remaining files (`friction-timeline.mjs`
   was verified live specifically because it does not shell out to `claude`).

5. **`overhead-taxonomy.mjs`.** Its private `firstTranscriptTimestamp` copy (flagged as the one
   outstanding item by the PR 5a review, above) was deleted after confirming it was
   BYTE-IDENTICAL to `lib/transcript-store.mjs`'s exported one (`function firstTranscriptTimestamp
   ... }` bodies compared verbatim, 1082/1082 chars matching) — a pure de-duplication. The now-
   unused `readline` import was removed too. **Parity:** `node overhead-taxonomy.mjs --since
   2026-08-01 --until 2026-09-03` run before/after (via `git stash`) against the real corpus
   differed only in `window_total_tokens` (23.170.000.653 → 23.174.270.579, +0.018%) and two
   category share percentages by 0.001-0.002 points — running the POST-migration script twice
   more in a row (back-to-back) reproduced the identical drift SHAPE (tokens and the WAITING
   turn count both kept climbing between runs), the same live-corpus-drift signature PR 5a
   documented for `baseline-economics.mjs`/`context-attribution.mjs`, not a migration defect.

6. **`context-attribution.mjs` — three review nits from PR 5a.** (a) The `'(zip-mismatch)'`
   fallback now calls `addTo` once PER folded `tool_result` instead of once for the summed
   total — `count` no longer sticks at 1 while `chars` holds the full sum. The zip/aggregation
   step was split into a new pure `attributeFromArrays(names, chars, {filePath})`, exported for
   test (same "pure, entries-in" shape as `lib/ledger/codex-adapter.mjs`'s
   `processCodexEntries`), since the mismatch branch is unreachable through the real file-reading
   path by the very construction argument in (b) below and so could not otherwise be exercised.
   3 new tests added to `context-attribution.test.mjs` (9 → 12): a normal zip, a length-mismatch
   zip (asserts `count === 3` for 3 folded results, not 1), and a 0-length-chars edge case.
   (b) The "positionally zippable" justification comment no longer cites a corpus scan
   ("0 file-level mismatches") as proof — reworded to state the two joins cannot disagree BY
   CONSTRUCTION, since both are the structurally identical predicate (`streamLines`-equivalent
   line scan, `entry.type === 'user'` + `Array.isArray(content)` + `b.type === 'tool_result'`;
   verified against `lib/ledger/claude-adapter.mjs`'s `processClaudeTranscript` source, which
   literally calls `streamLines`, vs this module's own `localToolResultChars`, which
   re-implements the identical scan by hand) — the fallback is defensive, not a case expected to
   fire. (c) A note now documents that an orphan/forward-referenced `tool_result` is labelled
   `'(unknown)'` by the ledger adapter (verified: `claude-adapter.mjs`'s
   `name: use?.name ?? '(unknown)'`), where this module's OWN pre-PR-5a private join used the
   bare `'unknown'` (verified against `git show 84d2653a^:...context-attribution.mjs`) — the
   adapter's label is kept (no code change; PR 5a already inherited it), documented in both the
   module comment and the doc table row. `context-attribution.test.mjs`: 12/12 green.

**Out-of-scope sweep: `hooks/compact-save.mjs`.** Not one of the six brief items, but the
acceptance grep (`grep -rn "'.claude', *'projects'\|\.claude/projects" scripts/agent-analytics
--include=*.mjs` must list only `lib/transcript-store.mjs` plus comments/tests) turned up a real
code hit here: `MEMORY_DIR`'s own hand-rolled `path.join(process.env.HOME || process.env.USERPROFILE
|| '', '.claude', 'projects', ...)`. This is NOT a session-transcript discovery (it locates a
`memory/MEMORY.md` artifact under the CURRENT repo's own project dir, not `<sessionId>.jsonl`), so
`discoverProjectDirs`'s multi-dir session-transcript model doesn't directly apply — but the
`'.claude', 'projects'` ROOT computation is identical to `DEFAULT_PROJECTS_ROOT`, so that part was
swapped for the import, keeping the per-repo slug computation local (documented inline as to why).
Verified `os.homedir()` (`DEFAULT_PROJECTS_ROOT`'s source) and `process.env.HOME || USERPROFILE`
resolve to the identical path on this machine — no behavior change. `node --check` clean; full
suite unaffected (no dedicated `compact-save.test.mjs` exists).

Full suite: `node scripts/agent-analytics/run-all-tests.mjs` → 64/64 (unchanged file count — no
new test FILES, only 3 new tests inside the existing `context-attribution.test.mjs`, 9 → 12).
`node --check` clean on all 8 changed `.mjs` files. `git status` scoped to 7 changed `.mjs` source
files (6 in-scope + `compact-save.mjs`), 1 changed test file, and this canonical-doc/tempdoc
update. Grep sweep (`'.claude', *'projects'\|\.claude/projects`) now lists only
`lib/transcript-store.mjs`'s definition plus comments (in `baseline-economics.mjs`,
`evaluate-session.mjs`, `compact-save.mjs`, `lib/ledger/claude-adapter.mjs`, `lib/telemetry-io.mjs`,
and one test file) — zero remaining code hits outside `lib/transcript-store.mjs`. Non-ASCII scan of
the diff's added lines found only the codebase's existing em-dash/§/arrow punctuation, no mojibake.
`docs/explanation/21-agent-analytics-pipeline.md` updated (boundary-rule paragraph, new PR 5b
paragraph, 6 table-row updates); `node scripts/docs/llmstxt-generate.mjs --check` OK.

- Move `baseline-economics`, `analyze-session`, `evaluate-session`, `friction-timeline`,
  `mine-friction`, `context-attribution` onto the ledger; delete their hand-rolled discovery
  (`baseline-economics.mjs:277-349` and peers). One or two readers per PR if the diff is large.
- `lib/input-summarizer.mjs`: tool-name switch → per-harness role map (`lib/ledger/tool-roles.mjs`), consumed by the adapters.
- `lib/transcript-cost.mjs`: provider column; optional LiteLLM price dataset as input with 841's fail-closed rule kept.
- Routing: Sonnet; mechanical.

### Validation (every PR)

`node scripts/agent-analytics/run-all-tests.mjs`; `python scripts/agent-analytics/test_otlp_sink.py` (PR 3); `node scripts/governance/run.mjs --gate hook-integrity --mode gate` (PR 4); `node scripts/docs/llmstxt-generate.mjs --check` when docs change; each reader run on the real corpus with the anchors above; `./gradlew.bat build -x test` once per PR before ready (shared-Gradle convention: one build at a time, no dev stack needed anywhere in this plan). Merge gate: `subset-isnt-the-suite`.

### Orchestration and contention

One worktree at a time while other sessions are active; PRs 1 → 2 → 4 sequential, PR 3 can interleave. Subagent briefs carry the record schema verbatim, the fixture rule (synthetic only), UTF-8 edit rule, and the anchors as acceptance criteria. No PR is opened without an explicit go-ahead.
