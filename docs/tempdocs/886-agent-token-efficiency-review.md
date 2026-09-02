---
title: "886 — Agent token efficiency review: context residency is the cost, not cache misses"
status: "IN PROGRESS 2026-09-02 — PR 1 ledger committed; PR 2 readers in flight"
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

### PR 4 — control shims (worktree `886-ledger-4`, after PR 1)

- `hooks/spawn-cost-hint.mjs` (PostToolUse/Agent, advisory): on return, resolve the spawn's transcript via the ledger and print one line — calls, peak context, cost, model.
- `hooks/context-ceiling-hint.mjs` (PostToolUse, advisory, once per threshold): last usage from `transcript_path`; at 300k and 500k print the number and the two remedies (`/compact <hint>` at a boundary; `/rewind` when abandoning a path).
- Registration: `settings.local.json.example`, `agent-hooks.v1.json` (`role: advisory`), tier-register rows, `*.test.mjs`; `hook-integrity` gate green. Codex `hooks.json` equivalents documented as optional in the PR 3 how-to, not governed.
- Routing: Sonnet-high.

### PR 5 — migrate the stale readers, role map, pricing provider (worktree `886-ledger-5`)

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
