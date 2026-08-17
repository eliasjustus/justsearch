---
title: "Prompt-cache efficiency of agent sessions: hit rate is fine, context size is the bill — invalidation anatomy, delegation cache economics (which inverted under measurement), and the pricing gap that hid all of it"
type: tempdocs
status: "investigated + reader SHIPPED (2026-08-18, session 0a20e5bf). `scripts/agent-analytics/cache-efficiency.mjs` + 19 tests land the analysis as a repeatable reader; every number below is now reproduced by it. Two findings from the hand-probe pass INVERTED when the reader applied correct last-wins dedup — see §5 and §10. NOT fixed: the opus-5 pricing row (blocked, needs published rates) and the subagent-TTL design question (owner call)."
created: 2026-08-18
updated: 2026-08-18
author: agent session 0a20e5bf (Opus 5, 1M context)
category: agent-process / agent-economics / observability
related:
  - 745-oss-first-observability          # built the cost parser; its "100% 1h tier" claim is superseded (§3), and its bug-2 dedup fix is what inverted §5
  - 743                                   # baseline economics + the shared transcript substrate this reader consumes
  - 765-agent-economics-lane             # token/time anatomy of eval cells — different corpus, same cost question
  - 622-agent-telemetry-native-otel-migration  # native OTel carries cacheRead/cacheCreation per request; the escape hatch for §4's open question
  - 620                                   # always-loaded budget ratchet — governs the cheapest lever, see §6
---

> **Scope.** How agent sessions in this repo interact with Anthropic prompt
> caching. §1–§6 are measured and reproducible via
> `node scripts/agent-analytics/cache-efficiency.mjs`. §7 separates actionable
> from blocked. §10 is a method post-mortem: four claims died during this work,
> two of them after being written down as findings.

## 1. Corpus and method

`~/.claude/projects/*justsearch*/**.jsonl` — 844 transcripts (66 main sessions,
778 subagent transcripts), 63,155 usage-bearing turns.

Per-turn fields: `usage.cache_read_input_tokens`,
`usage.cache_creation_input_tokens`, the
`usage.cache_creation.{ephemeral_5m,ephemeral_1h}_input_tokens` split,
`input_tokens`, `output_tokens`, `message.model`, `timestamp`.

**Dedup is `(message.id, requestId)` keeping the LAST usage snapshot**, per
tempdoc 745 item B bug 2 — subagent transcripts persist streaming partials that
grow (`output_tokens: 5, 5, 5, 5, 5, 291`). This is not a footnote: an earlier
first-wins pass through this same corpus undercounted subagent output by ~2×
and produced an inverted headline. See §10.

## 2. Headline: the cache is working; that is not the problem

Corpus-wide read:write = **38.9 : 1**. In hit-rate terms caching is healthy and
there is no "we forgot to cache" story to tell.

The cost structure is the story (priceable subset — see §6):

| Cost line | $ | Share |
|---|---:|---:|
| cache_read | 5,730 | **63.3%** |
| cache_write | 2,656 | **29.3%** |
| output | 662 | 7.3% |
| input | 1 | 0.0% |

**92.7% of priceable spend is re-presenting context, not generating tokens.**
Prompt-cache efficiency here is not a caching question — it is a context-size
question wearing a caching costume. A 10× cache discount on a 1M-token prefix
re-read across thousands of turns is still the dominant bill.

## 3. Cache-write anatomy: three different things wearing one label

| Cause | Turns / events | Tokens | Share of cache-write |
|---|---:|---:|---:|
| extension (normal per-turn delta) | 62,079 | 149.3M | 38.0% |
| **invalidation (prefix lost)** | **557** | **178.3M** | **45.4%** |
| cold start (new session/subagent) | 519 | 65.1M | 16.6% |

**557 events cost more than 62,079 normal turns.** Any report that shows
cache-write as one number cannot see this. That is the gap this reader closes.

Invalidations by cause:

| Cause | n | Tokens | Share |
|---|---:|---:|---:|
| **in-TTL, undetermined** | 308 | 100.9M | **56.6%** |
| TTL expiry | 219 | 71.0M | 39.8% |
| model switch (fable↔opus) | 15 | 5.5M | 3.1% |
| compaction | 15 | 0.8M | 0.5% |

**Compaction is negligible — 16 boundaries in the entire corpus, 0.9M tokens.**
Worth stating plainly because it is the usual suspect and it is innocent.
(Compaction usually produces a *cold* prefix rather than a shrinking one, so the
reader counts it independently of the write-cause split; a per-line flag was
silently eaten by the partial-dedup, which is what made this counter read 0 —
regression-tested.)

**TTL tiering is split by agent kind and is harness-set.** Main sessions write
100% 1h-tier (170.8M); subagents write 100% 5m-tier (221.9M). Zero mixing. This
**supersedes tempdoc 745 §400's "100.000% of cache writes are 1h tier"** — true
of the main-only corpus it measured, false once subagent transcripts entered.

## 4. The in-TTL invalidations: what they are, and what I could not determine

308 events, **100.9M rewrite tokens** — the single largest identified block of
cache waste, and its cause is unknown.

**What is established:** the prefix always collapses to a small floor. 293 of 305
large events land at a `cache_read` of 10k–40k, clustering at ~15k / ~25k / ~30k.
An invalidation never costs a little — it loses *the entire conversation body*
and falls back to the system+tools block. Each event's cost is therefore
proportional to conversation length at the moment it fires, which is why six long
main sessions carry ~47M of the total.

**Two hypotheses raised and refuted** — see §10 for how, since both were briefly
believed:
- Hook-injected context (ranked first at lift 16.1) — **confounded**. The matched
  entries are turn-boundary markers, and user turns are simply where new work
  begins. Hooks do not invalidate the cache.
- Tool-schema loading — **real but small**. Convincing in the first five raw
  dumps, but those all came from one browser-heavy session. Generalised, first-use
  of a new tool family explains **12.5% of events / 9.0% of rewrite**.

**Where they happen — established (second pass, corpus-wide):**

- **81% of in-TTL invalidations (245/301) occur at a USER-TURN boundary.** Given
  a user turn, invalidation probability is 8.8% against a 0.48% base rate —
  lift 18.3. The mechanism is tied to whatever the client does when a new user
  turn begins, not to anything the assistant does mid-turn.
- **A user turn is necessary-ish but nowhere near sufficient**: ~91% of user
  turns invalidate nothing.
- **Session-state changes are NOT the discriminator.** `relocated` (a cwd change
  / worktree entry) looked compelling in a ground-truth case study of this
  session — the markers sat exactly at the invalidating turn — but corpus-wide
  only 53 of 2,900 `relocated` markers coincide with one (1.8%), and the
  *without*-relocated subset scores **higher** (lift 18.8 vs 18.3). Killed.
  Same for `mode` / `permission-mode` / `ai-title` / `worktree-state` (lift 3–4,
  all explained by co-occurrence with user turns).
- **A small context never loses one: 0 of 159 user turns below a 50k prefix
  invalidated.** Rate peaks at 100–400k (~17%) and *falls* above 700k (5.8%) —
  non-monotonic, and most likely **survivorship**: a session only accumulates a
  700k prefix if invalidations are not resetting it, so the large buckets are
  enriched with stable sessions. Causation cannot be read off this.

**What remains undetermined:** what separates the ~9% of user turns that
invalidate from the 91% that do not. **Transcripts record token *usage*, not the
prompt prefix**, so client-side breakpoint re-anchoring and server-side eviction
stay indistinguishable. Note the earlier claim that tempdoc 622's OTel spans are
"the escape hatch" is **overstated** — those spans most likely carry the same
token counts, not the prefix structure; nothing observed so far shows Claude Code
logs the system/tools/breakpoint layout anywhere. Closing this probably needs a
**controlled experiment** (drive a session to ~200k, then vary one factor per
user turn), not more archaeology. The reader labels these
`in-ttl-undetermined` and that label is honest, not a placeholder someone forgot
to fill in.

**The one actionable consequence today** is the same lever as everything else:
invalidation cost is proportional to prefix size, and below ~50k it did not
happen at all in this corpus. Shorter working contexts shrink both the
probability and the blast radius, without needing the mechanism identified.

## 5. Delegation cache economics — the finding inverted

778 spawns. Subagents are **74% of all turns** and their cache-read slightly
exceeds main's.

| | subagent | main |
|---|---:|---:|
| turns | 46,309 | 16,480 |
| **read per output-token** | **262 : 1** | **500 : 1** |

**Subagents are roughly twice as cache-efficient per output token as the main
loop, not 2.6× worse.** The earlier hand-probe claimed the opposite (1,331:1 vs
502:1); that pass used first-wins dedup, which discards the grown streaming
partials that subagent transcripts are full of, halving their apparent output.
The correct last-wins dedup reverses the direction. **This measurement supports
CLAUDE.md's delegate-by-default routing rule rather than questioning it.**

The real, surviving costs of delegation:

- **Spawn cost** — 43k tokens of cold prefix per spawn, 33.1M total. A subagent
  cannot share the parent's cache; brief, CLAUDE.md, rules and tool schemas are
  re-written per spawn.
- **5-minute TTL penalty** — 118 expiry rewrites, **28.2M tokens ≈ 12.7% of
  subagent cache-write**. (An earlier probe put this at 75.9M / 34.3% by counting
  any long-gap turn rather than only genuine prefix losses; 28.2M is the
  defensible figure.) The profile still fits workers idling across a
  `./gradlew.bat build` or jseval pass — the delegated mechanical work CLAUDE.md
  asks for, on the one tier that expires in five minutes.
- **Short spawns** are wasteful per unit and negligible in aggregate (~3.0M of
  33.1M cold write). Not the lever, despite being the intuitive target.

**Still not established:** the *benefit* side — preserved orchestrator context,
parallelism, avoided compaction — is not visible in cache columns. The routing
rule's own falsifier (cost-per-shipped-merge over ~2 months) needs the
merge-outcome join `record-merge.mjs` provides. Separate work.

## 6. The pricing gap that hid all of this

**`claude-opus-5` is absent from `PRICING` in
`scripts/agent-analytics/lib/transcript-cost.mjs:63`.** `findPricing` fails
closed (correctly, per 745 bug 4) and returns null — so **36,514 turns and
7,927M cache-read tokens, 51.9% of all cache-read, price at $0.** Every
`baseline-economics` / `cost-session` total silently excludes the dominant model.
The $9,050 in §2 is the *priceable minority*. There is also no >200k
long-context tier despite sessions running near 1M.

The reader now prints this as a loud `!!` block on every run, so the gap
announces itself rather than hiding behind a plausible total. **Adding the row
itself is blocked** — it needs published Opus 5 rates, and inventing one is
precisely the failure 745 bug 4 fixed.

**The one governed surface is the cheapest lever.**
`always-loaded-budget.v1.json` ratchets CLAUDE.md + `.claude/rules/*` to 55,287
bytes (~14k tokens) with an audited bump log — real discipline, aimed at ~11% of
a typical 126k cold write. Tool schemas, skill loads and hook injections are the
rest and are budgeted nowhere.

## 7. What is actionable, and what is not

**Done in this tempdoc:**

1. `scripts/agent-analytics/cache-efficiency.mjs` — hit ratio, write-cause split,
   invalidation attribution, TTL tier by kind, delegation economics, pricing
   coverage. 19 tests, auto-discovered into CI by `run-all-tests.mjs`.

**Actionable, blocked on an input:**

2. **Add the opus-5 pricing row** (+ a >200k long-context tier). Needs published
   rates. Until then every economics figure in the repo understates by ~half.

**Actionable, owner call:**

3. **The subagent 5m-TTL penalty** (28.2M, 118 events, concentrated in workers
   idling across long builds). Whether long builds belong inside a delegated
   worker's window touches CLAUDE.md's routing rule.

**Not actionable on current evidence:**

4. The ~87% undetermined in-TTL invalidations. Needs 622's request-layer spans.
   Do not guess a cause.

**The convergence worth noticing:** invalidations cost the whole conversation
body, and context re-presentation is 92.7% of spend. Neither is fixed by better
caching — both shrink with **smaller working contexts**, the lever currently
governed only at its cheapest 11%.

## 8. Reproducing

```
node scripts/agent-analytics/cache-efficiency.mjs           # human-readable
node scripts/agent-analytics/cache-efficiency.mjs --json    # machine-readable
node scripts/agent-analytics/cache-efficiency.mjs --since 2026-08-01
node scripts/agent-analytics/cache-efficiency.test.mjs      # 19 classifier + regression tests
```

Reads only `~/.claude/projects/*justsearch*/**.jsonl`; writes nothing. Discovery
from `lib/transcript-store.mjs`, pricing from `lib/transcript-cost.mjs` — it adds
classification, not a fourth transcript parser.

## 9. Substrate change

`lib/transcript-store.mjs`'s `iterateTurns` gained `model`, `messageId`,
`requestId`, `isCompactBoundary`. **Additive** — existing consumers destructure
what they need and are unaffected. They exist so a cost-shaped reader can dedupe
and bucket by model without hand-rolling its own parser, which is the drift that
module was created to stop.

## 10. Method post-mortem — four claims died, two after being written down

Recorded because the failure pattern is repeatable, not for confession value.

1. **Lift 16.1 on `~hook-injected-context`** — a confounded correlate. Died on
   reading the raw entries.
2. **Tool-schema loading as *the* cause** — sample bias; the first five dumps all
   came from one session. Died on generalising to all 305 events.
3. **"Only 75 `type:system` entries exist corpus-wide"** — cited in the first
   draft as evidence against #1. **False**: the corpus holds 2,432
   `stop_hook_summary` + 2,482 `turn_duration`. The probe that produced it
   terminated early after five dumps, so its "corpus-wide" count was really one
   file. The conclusion it supported survives on other grounds; the evidence line
   was retracted. *Citing wrong evidence for a right conclusion is still wrong.*
4. **"Subagents burn 2.6× the read per output token"** — inverted by correct
   last-wins dedup (§5). It had been written into the tempdoc, the commit
   message, and a verbal summary before the reader caught it.
5. **"Working-directory relocation invalidates the prefix"** — a ground-truth
   case study (this session: `relocated` + `worktree-state` markers sitting
   exactly at the one invalidating turn, 6 other user turns clean) that looked
   airtight at n=1 and died at n=2,900. **A case study with known ground truth is
   still n=1.**
6. **"92.7% of spend is context re-presentation" as an ISSUE** — retracted as a
   finding. It is near-tautological: every transformer turn re-reads its context,
   which is why caching exists. It is a useful *navigational* fact (output-side
   tuning is pointless here) but it is not evidence of waste. Establishing waste
   needs a different measurement — what fraction of a long context is never
   referenced again — which this tempdoc did not do.

**A correction to correction #1.** The first lift analysis (`~hook-injected-
context`, lift 16.1) was dismissed as pure confounding. That over-corrected: it
was pointing at the right *location* — user-turn boundaries — while mislabelling
the *mechanism* as hooks. The signal was real; the causal story was not.
Retracting a whole result because its explanation is wrong throws away the part
that was right.

**What caught #3 and #4 was productizing the analysis.** Both survived the
hand-probe pass; neither survived being rebuilt as a tool that had to run over
the whole corpus with the repo's own established semantics. The lesson is not
"probe more carefully" — it is that **a scratchpad probe and a reader are not the
same evidence tier**, and a finding should not be reported as settled while it
still rests only on the former. The dedup semantics that inverted #4 were already
documented in 745; the probe simply did not apply them.
