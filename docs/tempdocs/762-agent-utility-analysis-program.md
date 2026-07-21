---
title: "agent-utility analysis program (post-v5): decompose the accepted-but-small numbers into ranked levers before spending on another campaign — umbrella roadmap + targets + cross-lane synthesis"
type: tempdocs
status: "chartered (2026-07-21). Lanes 763/764/765 chartered; §T targets folded in (researched 2026-07-21); §L synthesis open until all lanes close."
created: 2026-07-21
author: agent (Fable orchestration), founder-directed 2026-07-21 ("hold off from running any more of these runs … analyse the current run and try to find all issues/areas that need improvement, so we can actually produce real big numbers")
category: eval-analysis / agent-utility / program-umbrella
related:
  - 624-agentic-retrieval-eval-rebuild   # closed campaign history; v5 ACCEPTED evidence (PR #263)
  - 763-retrieval-attribution-lane       # lane 1: where do with-tool failures actually happen
  - 764-eval-validity-lane               # lane 2: do the questions/judge/n measure what we think
  - 765-agent-economics-lane             # lane 3: token/time anatomy + next-campaign design inputs
  - 719-reproducible-public-agent-utility-benchmark
  - 704-measurement-substrate-correct-data-program
---

> Umbrella. This tempdoc is the founder decision document: it holds the target
> definition (§T), the known-priors table lanes must not rediscover (§P), the
> shared data inventory (§D), the program constraints (§C), and the cross-lane
> ranked lever table (§L — the program's deliverable). The evidence lives in the
> three lane tempdocs; each lane folds its verdict rows into §L at close.

# 762 — agent-utility analysis program (post-v5)

## §A. Why this program exists

Campaign v5 (2026-07-21) was **accepted** under policy `agent-utility-public-v2`
— the program's first promoted claim — but the promoted claim is adoption-only
(agents adopt the tool at 98–100%; no numeric benefit language licensed). The
scientific numbers underneath are real but small:

| Stratum | acc A (no tool) | acc B (tool) | Δ | p | completion A→B |
|---|---|---|---|---|---|
| legal-1k | 0.033 | 0.200 | **+0.167** | 0.013 | 0.97→0.97 |
| legal-10k | 0.000 | 0.167 | **+0.167** | 0.002 | 0.58→0.92 |
| email-1k | 0.533 | 0.417 | −0.117 | 0.27 | 0.87→0.95 |
| email-10k | 0.217 | 0.200 | −0.017 | 1.0 | 0.57→0.75 |

Founder decision (2026-07-21): **no further paid campaign runs and no broad
public-doc updates** until the current run's ~480 fully-instrumented cells have
been mined for every failure mode and improvement lever. A campaign costs ~$90
and ~4h15m wall clock; the transcripts we already own answer most questions for
compute + a few dollars.

## §B. Program structure

Three lanes, grouped by **remedy owner** (who has to act on the finding), not by
data source — each lane is a self-contained charter executed by its own
orchestrating agent in its own session/worktree (founder-owned):

| Lane | Tempdoc | Remedy owner | Core question |
|---|---|---|---|
| Retrieval attribution | 763 | the engine (+ search-quality register) | For every failed with-tool cell: bad query, engine miss, result unused, synthesis failure, or judge error? |
| Eval validity | 764 | the eval design | Do the questions discriminate, is the judge right, is n=60 enough? |
| Agent economics & ergonomics | 765 | tool surface + next-campaign design | Where do tokens/time go, what kills cells, what would a USD-binding benefit campaign need? |

The umbrella (this doc) owns: §T targets, §P priors, §D data inventory, §C
constraints, §L synthesis. Lanes read the umbrella first, then execute their own
charter only.

## §P. Known priors — do NOT rediscover these

1. **The 10k accuracy floor is model-capability-bound.** Phase-2 probe: same
   eval, sonnet-B scored 0.600 where haiku-B scored 0.067. Lanes analyzing low
   absolute B accuracy at 10k must treat "haiku can't do hop-2 synthesis at this
   context discipline" as the established first-order explanation and look for
   what's *left over*, not re-derive it.
2. **The email accuracy-null is replicated 5×** (Step-2, v4 ×2 strata, v5 ×2
   strata). The A-arm baseline (grep/read) already reaches 0.53 at email-1k —
   the leading hypothesis is that the email questions are answerable without
   retrieval (corpus doesn't discriminate). Lane 764 owns this question.
3. **The regime pattern is replicated 3×** (Step-2, v4, v5): legal = significant
   accuracy uplift both scales; email = null; baseline completion collapses at
   10k in both domains while the with-tool arm holds (0.57–0.58 → 0.75–0.92).
4. **Instrumentation is trustworthy as of v5.** Zero incidents; identity gates
   all green (single CLI cohort 2.1.216, git_dirty false, surface rate 0.9167,
   exposure identity carried). Analysis can take the captured fields at face
   value; the residual capture caveats are documented in 757 (wall-clock cost
   truncation on exhausted cells fails closed, direction-conservative).
5. **Test-retest wobble is visible at n=60**: legal-1k Δ was +0.255 (v4-era
   evidence) vs +0.167 (v5). Lane 764 owns quantifying the noise floor; other
   lanes must not over-interpret per-stratum deltas beyond it.

## §D. Shared data inventory (all lanes read from here)

All paths below are on the founder's machine; the campaign working data is
**unversioned** — the `step2-powered` worktree must NOT be removed until all
lanes close.

- **v5 full run data** (transcripts, calibrations, serve logs):
  `F:\justsearch-public\.claude\worktrees\step2-powered\scripts\jseval\tmp\confirm2\`
  — four stratum dirs (`en-legal-clerc-{1k,10k}-verbose`,
  `en-email-enron-raw-{1k,10k}-verbose`), each with `logs/` (Inspect AI eval
  logs incl. full per-sample transcripts + tool calls), `out/utility-comparison.v1.json`
  (the per-run record: per-cell observability — `tool_call_sequence`,
  `tool_result_digests`, `toolsearch_targets`, `surface_evidence`,
  `usage_truncated`, `error_class`, adoption funnel
  `first_mcp_call_index`/`mcp_call_share`, duration/censoring, judge scores),
  `calibration.json`, `serve.log`; plus `combined/utility-comparison-cross-corpus.v1.json`
  (the ACCEPTED verdict record) and `chain-confirm.log`.
- **v4 + rerun data** (for test-retest): same worktree, `tmp\confirm\`.
- **Committed evidence** (records only, no transcripts — the citable copy):
  `scripts/jseval/624-run-2026-07-21-relaunch/` on `main`.
- **Questions + gold**: `scripts/jseval/707-corpora/<corpus>/{1000,10000}-verbose/fabricated-queries.json`
  (`<corpus>` ∈ `en-legal-clerc`, `en-email-enron-raw`), in the step2-powered
  worktree; per-qid question text + gold answer + provenance.
- **Corpus documents**: same `707-corpora` tree (+ `corpus-dir` recorded in each
  calibration); certifications in `structural-certification.v1.json`.
- **Prebuilt indexes** (751 content-addressed store, adoption takes seconds):
  `F:\justsearch-public\tmp\index-cache\entries\` — 13 entries incl.
  `16e134bb…` (email-1k) and `6d15611e…` (email-10k); match entries to strata
  via each entry's `selector_key`/`identity` vs the calibration's corpus-dir.
- **jseval invocation environment** (Windows): set
  `PYTHONPATH=F:\justsearch-public\.claude\worktrees\step2-powered\scripts\jseval`,
  `PYTHONUTF8=1`, `INSPECT_DISPLAY=none` for any jseval/Inspect invocation from
  another checkout (tempdocs 716/675).

## §C. Program constraints (founder, 2026-07-21)

1. **No paid campaign runs.** Analysis only. Local compute (GPU serve boots,
   `ai_activate` local model) is authorized, including dev-stack takeover.
2. **Small API budget per lane is fine** (order a few dollars — e.g. judge
   tiebreaks, query-reformulation probes). Anything that looks like a
   mini-campaign (systematic re-running of arms) is out of scope — that is the
   *next* campaign, designed from this program's output.
3. **No broad public-doc updates** from the adoption result; publication via the
   623 pipeline stays a founder-only decision.
4. **Dev stack is shared** — `quick_health` first; respect ownership/lease rules
   (`/dev-stack`); declare `leaseDurationSec` for long replay sessions.
5. Lane findings routing: durable retrieval truths → **search-quality register**
   (load `/search-quality` before closing lane 763); one-liner out-of-scope
   findings → observation shards (`note-observation.mjs`); lane verdicts → §L
   here. Tempdocs stay working history.

## §T. Targets (researched 2026-07-21, opus researcher; folded verbatim)

### T1. Metric frame

Rank the metrics by how well they survive a skeptic who can run the harness:

1. **Paired accuracy uplift (with-tool − no-tool), per stratum, ITT** — this is
   the design's native strength and the honest headline *shape*. But it only
   lands if the with-tool absolute number clears a credibility floor (T2); a 6×
   ratio off a 0.033 base reads as "both arms fail" to an outside dev.
2. **Completion-rate rescue at scale** — A→B completion 0.58→0.92 on legal-10k
   is an *uncommon, defensible* metric almost nobody publishes. "The tool lets
   the agent finish the task at corpus scales where filesystem search runs it
   out of budget" is a real, legible claim. Promote this to co-headline.
3. **Cost-per-correct-answer / cost-of-pass** (tokens or $ per solved question,
   both arms). This is the framing that made token-efficiency stories travel
   (tempdoc 719 §"Why now") and has a published academic home (Cost-of-Pass,
   arXiv 2504.13359).
4. **Tool adoption when offered** (98–100%) — a supporting stat, never a
   headline (adoption ≠ benefit; it's the denominator that makes uplift
   meaningful).

**Never headline:** raw absolute accuracy of the haiku arm (0.167–0.20) — it
sits an order of magnitude below every published comparator (T2). Also never
headline an ∞/undefined ratio (legal-10k 0.000→0.167) — reframe as
completion-rescue or absolute-lift, not a multiplier.

### T2. Target magnitudes (with sources)

The closest published methodological cousin is **FRAMES** (multi-hop,
retrieval-vs-no-retrieval, *same model*): Gemini-1.5-Pro scores **40.8% with no
retrieval → 66% with multi-step retrieval** — a **1.6× / +25pp** lift
([arXiv 2409.12941](https://arxiv.org/abs/2409.12941)). That sets the bar:

- **With-tool per-protocol accuracy on the hero stratum should reach
  ~0.55–0.70.** That places JustSearch *inside* the FRAMES with-retrieval band
  (0.66) and the enterprise-RAG band — Onyx **72.4**, OpenAI File Search
  **61.0**, RAGFlow **50.2**, AnythingLLM **35.6** on EnterpriseRAG-Bench
  correctness ([onyx.app/enterpriserag-bench](https://onyx.app/enterpriserag-bench)).
  Below ~0.35 you are under the weakest named tool; above ~0.60 you are in the
  credible middle of the pack.
- **Uplift ratio ≥ 1.6×** is the floor (FRAMES). **2–3×** beats the strongest
  marketing comparators — Glean's blind-preference "1.9× vs ChatGPT, 1.6× vs
  Claude" ([glean.com/blog/enterprise-search-evaluation-2026](https://www.glean.com/blog/enterprise-search-evaluation-2026))
  and GAIA's Memento-Skills 80% vs BM25 50% (1.6×). Agentic-RAG papers
  routinely show larger swings (static 34%→agentic 89%; DSPy ReAct 24%→51%),
  so a 2×+ *with a real absolute floor* is defensible, not exceptional.
- **Completion rescue:** the existing +34pp (0.58→0.92) is already publishable
  as-is — no comparator publishes this cut, so there is no "too small" anchor
  to lose to.

### T3. Credible claim shapes for this design

Ranked, most→least defensible:

1. **"At scale, the tool rescues both completion and accuracy where filesystem
   search collapses."** Legal-10k: completion 0.58→0.92, accuracy 0.000→0.167.
   The *scale-dependence* (small gap at 1k, large at 10k) is the story — it
   matches the published pattern that retrieval value grows with corpus/hop
   difficulty (FRAMES; agentic-RAG multi-hop). Strongest because it's a
   mechanism, not just a delta.
2. **Paired uplift with an absolute floor** — credible *only* once the
   with-tool arm clears ~0.55 (T2). Requires the stronger model (T5).
3. **Cost-per-correct-answer** — credible and cheap; grep burns budget on
   failed exploratory reads (tempdoc 719). Pairs naturally with SWE-bench's
   "%Resolved + total $" convention and tau-bench tool-use framing.
4. **Capability-tier scaling curve** (haiku→sonnet→opus, with-tool accuracy) —
   credible as a *research* figure, honest about model-boundedness. Weak as a
   *marketing* headline unless the top of the curve is strong.

**Weak / avoid:** raw absolute haiku accuracy; any single ratio off a near-zero
base; adoption-as-benefit; email strata as headline (honest null results —
report, never lead).

### T4. Comparability warnings

Our numbers are **not raw-comparable** to the published comparators, and the
gap is systematic and *downward* for us:

- **ITT + exhaustion-as-failure zeroes any run that exceeds budget.** FRAMES,
  EnterpriseRAG-Bench, and GAIA report **per-protocol answer correctness with
  generous/uncapped budgets**. Publishing ITT next to their per-protocol number
  understates us by exactly the completion gap (legal-10k: 42% of A-arm cells
  never finished).
- **Bridge, don't inflate:** headline the ITT number for honesty, but *always*
  publish the per-protocol accuracy beside it and the completion rate that
  separates them, and state the budget explicitly. The three together
  (ITT / per-protocol / completion) are more defensible than any competitor's
  single number, and turn the ITT discount into a *rigor* selling point.
- **Multi-hop / hop-2 is intrinsically hard** — FRAMES no-retrieval is 40.8%,
  GAIA Level-2 frontier ~45%. Read our absolutes against *those* hard
  baselines, not single-hop RAG demos hitting 90%+. Say so.
- **Synthetic-contaminated private corpus ≠ Wikipedia.** Deliberately
  harder-to-contaminate — a validity strength, but it means no external
  leaderboard is a true peer; the reproducible-by-clone artifact (719) is what
  substitutes for leaderboard comparability.

### T5. Model-tier implication

**Running the headline campaign on a stronger model is the single biggest
number-mover**, and the published literature corroborates that
agentic-retrieval accuracy is model-tier-bound. Internal probe: sonnet-B
**0.600** vs haiku-B **0.067** on legal-10k (~9×). External: GAIA shows a
30–50pt spread on identical tasks driven by scaffold+model (scaffolded Sonnet
4.5 at 74.6% vs a bare weak model at 44.8%); GAIA Level-2 (multi-step,
tool-coordination) is where weaker models collapse — exactly our hop-2 regime.

**Recommendation:** the hero campaign should headline on **sonnet-class (or
opus)** where the with-tool arm plausibly reaches the T2 floor (~0.55–0.70),
and present haiku as the *lower bound* of a capability curve — not the
headline. Credibility caveat: a stronger model also lifts the *no-tool* arm
(it greps/reads better too), so the **uplift may compress** even as absolute
accuracy rises. Pre-register both arms at the new tier; do not assume the
+0.167 delta survives — the *absolute* with-tool number is what the model
change is buying, and that is the number that was "too small to market."

### T6. Sources

- FRAMES (closest cousin; 40.8%→66% same-model retrieval lift): https://arxiv.org/abs/2409.12941
- EnterpriseRAG-Bench leaderboard (Onyx 72.4 … AnythingLLM 35.6): https://onyx.app/enterpriserag-bench
- Glean blind-preference eval (1.9×/1.6×): https://www.glean.com/blog/enterprise-search-evaluation-2026
- GAIA model/scaffold spread + tool-use sensitivity: https://www.marktechpost.com/2026/04/26/top-7-benchmarks-that-actually-matter-for-agentic-reasoning-in-large-language-models/
- Cost-of-Pass economic framework: https://arxiv.org/pdf/2504.13359
- Agentic multi-hop uplift figures (A-RAG, DSPy ReAct): https://arxiv.org/html/2602.03442v1
- Internal: `README.md:147-156` (fail-closed publication status);
  `docs/reference/benchmarks/agent-utility.md:9-38` (ITT/exhaustion contract);
  tempdoc 719 (magnitude-isn't-the-moat; token/retrieval-step metrics).

**Bottom line:** the numbers that would "land" are a **hero stratum with
with-tool per-protocol accuracy ≥ ~0.55–0.70 (FRAMES/Onyx band), a ≥1.6–2×
paired uplift on that real absolute floor, and the scale-dependent completion
rescue (0.58→0.92) as co-headline** — and the fastest way there is re-running
the hero campaign on a sonnet/opus-class model, not tuning the engine. (The
lanes exist to establish what *else* must be true first: 763 whether the engine
leaks accuracy on its own, 764 whether the instrument can certify those
magnitudes, 765 what it costs.)

## §L. Ranked lever table (cross-lane synthesis — the program deliverable)

Filled as lanes close. Each lane appends rows; the umbrella owner ranks them.

| Rank | Lever | Owner tag | Evidence (lane §) | Expected effect on headline numbers | Cost to pull |
|---|---|---|---|---|---|
| — | *(open until lanes report)* | engine / eval / model-tier / campaign-design | | | |

Closure: when all three lanes are folded, this table + §T becomes the design
input for the next paid campaign (expected shape: USD-binding benefit campaign,
757's receipts groundwork; model tier and corpus/question set chosen from the
levers above). The program closes with that campaign's pre-registration, not
with this tempdoc.
