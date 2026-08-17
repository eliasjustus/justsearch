---
title: "Prompt-cache efficiency of agent sessions: hit rate is fine, context size is the bill — invalidation anatomy, delegation cache economics, and the measurement gap that hid all of it"
type: tempdocs
status: "investigated (2026-08-18, session 0a20e5bf). Read-only measurement over the local transcript corpus; no code changed. Three findings land: (1) ~94% of priceable spend is context re-presentation, not generation; (2) the repo's own cost tooling prices the dominant model at $0, so every existing economics figure excludes it; (3) subagents pay a 5-minute TTL that costs 34.3% of their cache-write. NO fix implemented — see §7 for what is actionable and what is not."
created: 2026-08-18
author: agent session 0a20e5bf (Opus 5, 1M context)
category: agent-process / agent-economics / observability
related:
  - 745-oss-first-observability          # built the cost parser this analysis leans on; its "100% 1h tier" claim is now stale — see §3
  - 743                                   # baseline economics + the shared transcript substrate
  - 765-agent-economics-lane             # token/time anatomy of eval cells — different corpus, same cost question
  - 622-agent-telemetry-native-otel-migration  # native OTel carries cacheRead/cacheCreation per request; the escape hatch for §4's open question
  - 620                                   # always-loaded budget ratchet — governs the cheapest lever, see §6
---

> **Scope.** This tempdoc measures how agent sessions in this repo interact with
> Anthropic prompt caching. It is analysis, not a change proposal. Everything in
> §1–§5 is measured from local transcripts; §6 is interpretation; §7 separates
> what is actionable from what is not. Probe scripts were scratchpad-only and are
> reproduced in §8 so the numbers can be recomputed.

## 1. Corpus and method

`~/.claude/projects/*justsearch*/**.jsonl` — 846 transcripts (71 main sessions,
775 subagent transcripts), ~62.6k usage-bearing turns.

Per-turn fields used: `usage.cache_read_input_tokens`,
`usage.cache_creation_input_tokens`, the
`usage.cache_creation.{ephemeral_5m,ephemeral_1h}_input_tokens` split,
`input_tokens`, `output_tokens`, `message.model`, `timestamp`.

Dedup is `(message.id, requestId)`, first-wins. **Caveat:** tempdoc 745 item B
established that subagent transcripts persist *streaming partials* and that
last-wins is correct for output tokens. First-wins therefore understates
`output_tokens` here by roughly the margin 745 measured (~30%). This does not
affect the cache columns (which do not grow across partials), so §2–§5 stand;
every ratio with `output` in the denominator in §5 is, if anything,
**conservative** — the true read:output ratios are lower than quoted but the
main-vs-subagent *comparison* is unaffected since both sides share the bias.

## 2. Headline: the cache is working; that is not the problem

Corpus-wide read:write = **38.8 : 1**. In hit-rate terms caching is healthy and
there is no "we forgot to cache" story to tell.

The cost structure is the story:

| Cost line | Tokens | Share of priceable spend |
|---|---:|---:|
| cache_read | 15,173M | **64.5%** |
| cache_write | 391M | **29.9%** |
| output | 20.3M | 5.6% |
| input | 0.6M | 0.0% |

**~94% of spend is re-presenting context, not generating tokens.** Prompt-cache
efficiency in this repo is therefore not a caching question at all — it is a
context-size question wearing a caching costume. A 10× cache discount applied to
a 1M-token prefix re-read across thousands of turns is still the dominant bill.

## 3. Cache-write anatomy: three different things wearing one label

Splitting `cache_creation_input_tokens` by *why* it was paid:

| Cause | Events / turns | Tokens | Share of all cache-write |
|---|---:|---:|---:|
| extension (normal per-turn delta) | 61,558 turns | 148.3M | 37.9% |
| **invalidation (prefix lost)** | **556 events** | **178.1M** | **45.5%** |
| cold start (new session/subagent) | 518 events | 65.1M | 16.6% |

**556 events cost more than 61,558 normal turns.** Any analysis that reports
cache-write as one number cannot see this.

Invalidations by apparent cause:

| Cause | Share of invalidation tokens |
|---|---:|
| TTL expiry, main (>60m idle) | 24.1% |
| TTL expiry, subagent (>5m idle) | 16.0% |
| **in-TTL, cause not determined** | **56.4%** |
| model switch (fable↔opus) | 3.2% |
| compaction | 0.4% |

**Compaction is negligible (0.4%)** — worth stating plainly, because it is the
usual suspect and it is innocent.

**TTL tiering is split by agent kind and is harness-set, not ours.** Main
sessions write 100% 1h-tier (170.4M / 16,402 turns); subagents write 100%
5m-tier (221.2M / 46,188 turns). Zero mixing, no exceptions in the corpus. This
**supersedes tempdoc 745 §400's "100.000% of cache writes are 1h tier"** — that
was true of the main-transcript corpus it measured, and stopped being true when
subagent transcripts entered the sample.

## 4. The in-TTL invalidations: what they are, and what I could not determine

305 in-TTL events, **100.7M rewrite tokens**, across 96 transcripts, heavily
concentrated (top 6 main sessions ≈ 47M).

**What is established:** the prefix always collapses to a small floor. 293 of 305
events land at a `cache_read` of 10k–40k, clustering at ~15k / ~25k / ~30k. In
other words an invalidation never costs a little — it loses *the entire
conversation body* and falls back to the system+tools block. The cost of each
event is therefore proportional to how long the conversation was when it fired,
which is why the long sessions dominate the table.

**A hypothesis I raised and then refuted.** A lift analysis over the 3 turns
preceding each event ranked `~hook-injected-context` first at **lift 16.1**
(60.2% of invalidations vs 3.7% baseline). Dumping the raw entries showed this
was **confounded**: the matched entries were `stop_hook_summary` / `turn_duration`
markers that sit at *user-turn boundaries*, and user turns are simply where new
work (and new tool loading) begins. Corpus-wide there are only 75 `type:"system"`
entries in total, yet the feature matched 180 events — the detector was not
measuring what its label claimed. **Hooks do not invalidate the cache.**

**A second hypothesis, also refuted.** The first five events dumped were all
first-use of `mcp__claude-in-chrome__*` — tool-schema loading, which genuinely
does invalidate everything after the tools block. But that sample came from a
single browser-heavy session. Generalised across all 305 events, first-use of a
new tool family explains only **12.5% of events / 9.0% of rewrite** (26
claude-in-chrome, 17 ToolSearch, 2 justsearch-dev). Real, small, not the answer.

**What remains undetermined:** ~87% of in-TTL invalidations have no cause
visible in the data. This is the wall flagged before the work started —
**transcripts record token *usage*, not the prompt prefix**, so client-side
breakpoint reassignment and server-side eviction are indistinguishable here.
Remaining candidates, none confirmed: cache-breakpoint re-anchoring as the body
grows past a threshold, capacity eviction before TTL, or concurrent-session
contention (this repo routinely runs 3–4 agents at once). Settling it needs a
layer that sees the request, not the transcript — tempdoc 622's native OTel
`claude_code.llm_request` spans are the nearest available escape hatch. **I am
not going to assign a cause I cannot evidence.**

## 5. Delegation cache economics

775 spawns in the corpus. Subagents are **74% of all turns** and their cache-read
(7,982M) exceeds main's (7,230M).

| | subagent | main |
|---|---:|---:|
| turns | 46,309 | 16,480 |
| cache_read | 7,982M | 7,230M |
| cache_write | 221.4M | 170.5M |
| read per output-token | **1,331 : 1** | **502 : 1** |

Three separate costs, measured:

- **Spawn cost** — 43k tokens of cold prefix per spawn, 33.0M total, **14.9% of
  subagent cache-write**. A subagent cannot share the parent's cache; the brief,
  CLAUDE.md, rules and tool schemas are re-written per spawn.
- **5-minute TTL penalty** — 275 expiry rewrites, **75.9M tokens = 34.3% of
  subagent cache-write**, concentrated in **117 of 775** subagents. This is more
  than double the spawn cost. The profile fits long-running workers idling across
  a `./gradlew.bat build` or a jseval pass — exactly the delegated mechanical work
  CLAUDE.md's routing rule asks for, on the one tier that expires in 5 minutes.
- **Amortisation** — 83 spawns do ≤10 turns, with terrible cold-write-per-output
  ratios (15,657 / 58 / 110 by bucket). But in absolute terms they are only 3.0M
  of 33.0M cold write. **Short spawns are wasteful per unit and negligible in
  aggregate** — not the lever, despite being the intuitive target.

By worker model: sonnet subagents have the *worst* read:output ratio (1,700:1 vs
opus 1,251:1), though sonnet's lower cache-read rate ($0.20/M vs $0.50/M) more
than offsets it per dollar.

**What this does not establish.** These are the *cost* side only. Delegation's
benefit — preserved orchestrator context, parallelism, avoided compaction — is
not measurable from cache columns, so **this section does not show the
delegate-by-default rule is wrong.** That rule carries its own falsifier
(cost-per-shipped-merge over ~2 months), which needs the merge-outcome join
`record-merge.mjs` already provides. Testing it is a separate piece of work.

## 6. The measurement gap that hid all of this

**`claude-opus-5` is absent from `PRICING` in
`scripts/agent-analytics/lib/transcript-cost.mjs:63`.** `findPricing` fails
closed (correctly, per 745 item B bug 4) and returns null — so **36,139 turns and
7.85G cache-read tokens, 52% of all cache-read, are priced at $0.** Every
`baseline-economics` / `cost-session` total silently excludes the dominant model.
The $8,865 in §2 is the *priceable minority*; the true figure is materially
higher and skewed further toward cache_read. There is also no >200k long-context
tier despite sessions running near 1M. Logged to the observations inbox.

Beyond pricing: the substrate already parses every cache field correctly and
several readers total them — but **nothing computes hit ratio, invalidation
events, TTL-expiry loss, or write-cause attribution.** The data has been sitting
in the transcripts the whole time. `overhead-taxonomy.mjs` comes closest and
still treats `input + cache_read` as an undifferentiated cost of a run.

**And the one governed surface is the cheapest lever.** `always-loaded-budget.v1.json`
ratchets CLAUDE.md + `.claude/rules/*` to 55,287 bytes (~14k tokens) with an
audited bump log — real discipline, aimed at ~11% of a typical 126k cold write.
Tool schemas, skill loads and hook injections make up the rest and are budgeted
nowhere.

## 7. What is actionable, and what is not

**Actionable, evidence-backed:**

1. **Fix the pricing table** (opus-5 row + a >200k long-context tier). Until then
   every economics claim in this repo understates by roughly half. Cheapest,
   highest-leverage item here.
2. **Add cache-efficiency metrics to the existing analytics** — hit ratio,
   write-cause split (extension / invalidation / cold), TTL-expiry loss. The
   parser already has the fields; this is a reader, not a substrate change.
3. **The subagent 5m-TTL penalty is a real, bounded 75.9M-token cost** with an
   identifiable profile (117 workers idling across long builds). Worth a design
   pass on whether long builds belong inside a delegated worker's window.

**Not actionable on current evidence:**

4. The 87% unidentified in-TTL invalidations. Needs request-layer visibility
   (622's OTel spans). Do not guess a cause.
5. The delegate-by-default rule. Cost side measured, benefit side not. Do not
   touch the rule on half the equation.

**The convergence worth noticing:** branch 1 and branch 2 both terminate at the
same lever. Invalidations cost the whole conversation body; delegation costs
1,331 read-tokens per output-token. Neither is fixed by better caching — both
shrink with **smaller working contexts**. That is the same lever §6 says is
governed only at its cheapest 11%.

## 8. Reproducing

Scratchpad probes (session-local, not committed): corpus inventory + read:write
ratio and gap bucketing; tier-by-kind + extension/invalidation/cold split + cost
attribution; invalidation cause classification; lift analysis (the refuted one);
raw-entry dump; floor/first-use generalisation; delegation economics. Each reads
only `~/.claude/projects/*justsearch*/**.jsonl` and writes nothing. Re-deriving
them is a few dozen lines against `lib/transcript-store.mjs` +
`lib/transcript-cost.mjs`; if any of §7's items proceed, they should be written
as a proper reader under `scripts/agent-analytics/` rather than rebuilt ad hoc.

## 9. Method note for the next agent

Two hypotheses in §4 survived a first look and died on the second — one to
confounding (`lift 16.1` on a mislabelled feature), one to sample bias (5 raw
dumps that all came from one session). Both would have shipped as findings if the
lift table or the dump had been treated as the result. The rule that caught them
was *interrogate results* — specifically, checking whether an expected-looking
result happened for the reason assumed. Budget for that step when working this
corpus; it cost two extra probes and changed the conclusion twice.
