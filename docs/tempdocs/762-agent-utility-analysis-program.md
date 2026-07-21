---
title: "agent-utility analysis program (post-v5): decompose the accepted-but-small numbers into ranked levers before spending on another campaign — umbrella roadmap + targets + cross-lane synthesis"
type: tempdocs
status: "analysis complete (2026-07-21). Lanes 763/764/765 EXECUTED same day (orchestrator-run with pinned opus workers — founder redirected execution from founder-run lanes); §L filled; §X theorization + §X.6 research answers folded; successor program: tempdoc 766 (eval-content rebuild)."
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
data source — each lane is a self-contained charter. (Execution correction,
2026-07-21: the founder redirected these ANALYSIS lanes to the program
orchestrator — all three were executed same-day by pinned opus workers;
founder-run lanes are the IMPLEMENTATION lanes chartered under tempdoc 766.)

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

| Rank | Lever | Owner tag | Evidence (lane results) | Expected effect on headline numbers | Cost to pull |
|---|---|---|---|---|---|
| 1 | Rebuild eval content on camouflaged real-doc injection + multi-schema questions (supersedes the narrower "re-fabricate email / cull dead qids" levers) | eval-design | 763 replay + 764 email verdict + mechanism probe + §X | makes every other number defensible; removes the −0.117 email drag and the 1.8× dead-qid dilution at 10k | tempdoc 767 |
| 2 | Sonnet-class hero campaign, USD-capped | model-tier / campaign-design | 765 §5 (~$0.37/cell intro); §P.1 probe sonnet-B 0.600; §T.5 | 0.167 → plausibly 0.55–0.70 with-tool (the §T.2 credibility floor) | ~$90–135 (Option A) |
| 3 | Rank-of-gold capture at run time + closed-book gate at hero tier | eval-infra | 763 phase-1 (payloads redacted → replay was forced); research Q4 (S effort, exact change points) | every future campaign self-attributes; no forensic replay program again | tempdoc 768 |
| 4 | Engine: bridge-entity retrieval fix (register F-039) | engine | 763 replay: 17 B2 cells, legal-only, 6%→28% with scale | unblocks up to 13% of with-tool failures; protects the scale story | tempdoc 769 |
| 5 | Tool surface: snippet economy + fetch(doc_id) + result salience | engine / tool-surface | 765 §3 (payload median ~13k chars; mcp_call_share ~0.5; 157 full-file Reads) + 763 B3-search (11 cells, gold rank 1–7 unopened) | recovers ~9% of failures; frees hop-2 synthesis budget at 10k | tempdoc 770 — **[SUPERSEDED 2026-07-21 — tempdoc 770 landed (PR #268).** `fetch(doc_id)` is **withdrawn**, not deferred: `hit.id` IS the filesystem path (identical in 14,617/14,617 measured hits), so `fetch(id)` reduces to `fetch(path)` — a tool returning a file the agent can already `Read`. The salience/passage-span lever is also withdrawn: the CLI delivers `structuredContent` only, so the text tier this lever reshaped is never sent to the model (0 of 1078 payloads carried `TRUNCATION_REMEDY`). What 770 shipped instead: ~16–31% payload reduction, truthful tool descriptions, and a characterized truncation cliff. Read 770 §0 before relying on this row.]** |
| 6 | USD-cap per-cell budgets (not wall-clock) + ITT/per-protocol/completion triple reporting | campaign-design | 765 §1 (69/86 exhausted cells lost cost receipts); §T.4 bridge | 100% cost receipts → defensible cost-per-correct headline (B $0.197 < A $0.280/cell) | config |
| 7 | n=120/stratum only if certifying Δ≈0.15; n=60 suffices at the §T target Δ≥0.25 | campaign-design | 764 power tables (n@80%: 92 for 0.15, 32 for 0.25) | right-sizes the next campaign's spend | config |

Lane results are folded into each lane tempdoc (763/764/765 §Results); raw
artifacts under `tmp/analysis-624/` (unversioned, session-machine). Instrument
verdicts worth pinning: judge error ~0/1,269 (substring scorer, anti-leak by
construction — the naming-leak numeric suffix is neutralized ONLY by
full-string matching); B1 bad-query = 0; B5 judge-error = 0; B0
died-before-retrieval ≈ 0.

Closure: when all three lanes are folded, this table + §T becomes the design
input for the next paid campaign (expected shape: USD-binding benefit campaign,
757's receipts groundwork; model tier and corpus/question set chosen from the
levers above). The program closes with that campaign's pre-registration, not
with this tempdoc.

## §X. Theorization — rebuilding the eval content on real-document injection (2026-07-21, founder-directed)

> Context. All three lanes + two follow-up probes completed 2026-07-21 (results
> fold pending). They exposed structural issues beyond the §L lever framing,
> and the founder directed a rebuild of the test *content* on the
> real-text-injection mode. This section theorizes the rebuild before design;
> it is exploration, not the design. The lane tempdocs opened by the design
> phase supersede anything here.

### §X.1 The five structural findings the rebuild must answer

1. **Construct validity**: all 480 v5 cells instantiate ONE question schema
   (2-hop structure→designer→value) over 40 synthetic gold payloads placed
   among real distractors. The 3×-replicated "legal wins / email null"
   regime pattern reduced to vocabulary overlap between the generator's
   wording and the distractor domain (mechanism probe: `spa` matches 22% of
   legal docs, ~0 of Enron) — a generator artifact, not a domain finding.
2. **Instrument aim**: replay census attributed ~77% of with-tool failures to
   model synthesis over already-surfaced evidence, ~13% to the engine. The
   benchmark mostly meters the model, not the product.
3. **Parameter dependence**: the completion-rescue headline is a function of
   the arbitrary ~215s wall-clock cap; a skeptic asks "who picked 215s?".
4. **Optics under reproduction**: a clone-and-inspect skeptic finds toy
   template docs ("crimson ferrolite 0002") and a naming convention that leaks
   the gold's numeric suffix (designer *N* ↔ value 000*N*; neutralized today
   only by the full-string substring scorer).
5. **Epicycle risk**: patching the current generator (vocab swap, dead-qid
   cull, leak fix) yields bigger numbers a skeptic can still dismantle at
   layer 1-2. CORRECTED AT RESEARCH (2026-07-21, code wins over this
   section's earlier draft): the 707 pipeline's real-text-injection mode IS
   implemented and WAS used — `corpus_inject.py:assemble` interleaves each
   gold payload into a real host document (live-confirmed: gold sentences
   inside a genuine legal memorandum), with host mapping + cross-interpreter
   determinism proof. What is missing is **camouflage**: the injected payload
   (entity names from syllable-pair minting, `_FILLER` sentences) is
   domain-alien and trivially greppable inside the real host — so the
   substrate is real, the payload is not. The rebuild is therefore a payload
   problem, not an injection-machinery problem.

**What survives untouched** (do not redesign): paired ITT + exhaustion
semantics, identity/provenance gates, cost receipts (757), the
reject-until-clean promotion pipeline, substring-scorable golds (~0 judge
error over 1,269 cells), the 751 index-cache, and the now-proven zero-spend
replay/attribution method.

### §X.2 Framings considered

- **Frame A — fix the corpus only** (inject facts into real docs, keep one
  schema): answers #4-5, partially #1; leaves #2-3. Insufficient alone.
- **Frame B — fix the instrument aim**: make the engine's contribution a
  *measured quantity*, not a forensic reconstruction — capture rank-of-gold
  per search call at run time (this analysis needed a replay program to get
  it; ids+ranks are tiny and dodge the payload-redaction problem), and report
  an "evidence surfaced" metric beside task accuracy. Also: run the hero on a
  model tier strong enough that synthesis stops dominating (sonnet probe
  0.600), so residual failure variance points at the engine.
- **Frame C — claim-first design**: fix the marketable claim shapes first (§T3:
  scale-dependent rescue, cost-per-correct, uplift-with-floor) and design
  measurement backwards from them, so we don't build another instrument that
  is precise about the wrong thing.
- **Frame D — evidence portfolio**: private injected corpus as the hero
  (contamination-proof) + one public established benchmark as a comparability
  anchor (accepting its contamination risk, labeled as such). Anchor is
  optional scope; the hero is the requirement.

The rebuild should take B + C as governing frames, A as one workstream inside
them, D as optional later scope.

### §X.3 Key design tensions to resolve at design time

- **Camouflage (inverse confound)**: injected sentences with vocabulary alien
  to the host doc are trivially greppable — the current failure inverted.
  Injected facts must be lexically domain-native (entity names shaped like the
  domain's names, sentences in the host register). This is the hard technical
  problem of injection; it deserves its own certification metric, not hope.
- **Machine-checkable vs pattern-greppable golds**: substring scoring is a
  proven asset, but format-uniform golds ("xxx yyy 0NNN") are greppable *by
  format*. Golds should be domain-plausible, format-diverse, exact-matchable
  after normalization — and the numeric naming leak must die.
- **Certification as gates, not audits**: this analysis' one-off measurements
  should become automated corpus-certification checks — distractor-flood index
  (grep-hit rate of question vocabulary against the distractor pile),
  no-corpus-arm solvability probe (contamination control ≈ 0), naming-leak
  check, gold dispersion, dead/ceiling calibration band. A corpus that fails
  certification never reaches a paid campaign.
- **Question-schema diversity**: ≥3 schemas (single-fact lookup, 2-hop bridge,
  aggregation/multi-doc synthesis; candidates: temporal "latest", negation) —
  each stresses different engine behavior, and schema-stratified reporting
  stops one template from being the whole benchmark.
- **Parameter robustness**: report at ≥2 budget points and ≥2 model tiers
  (haiku floor + sonnet hero); USD-binding budgets (765: closes cost receipts
  for 100% of cells).
- **Third arm**: a no-corpus-access arm as the contamination/leak control —
  cheap (short cells) and answers the strongest skeptic objection directly.
- **Licensing**: a reproducible-by-clone benchmark needs redistributable or
  fetch-scripted corpora (Enron/CLERC via the 709 cache pattern; verify
  redistribution posture at research).
- **Comparability break**: v4/v5 strata are retired history, not a baseline to
  preserve; do not contort the new design for continuity.

### §X.4 Candidate lane decomposition (to validate in design)

- **R1 — corpus & generator lane**: injection engine (camouflage, schema
  library, gold format), deterministic recipe regeneration (741:
  corpora are derived artifacts), certification gate suite (§X.3).
- **R2 — harness & attribution lane**: rank-of-gold capture at run time,
  third arm, USD budgets, dual-budget reporting, schema-stratified records +
  claim-policy/pre-registration update (power-informed n from 764: n=60
  suffices at Δ≥0.20-0.25; n≈120 for Δ=0.15).
- **R3 — engine lane** (independent, from §L): bridge-entity/near-dup ranking
  fix — the 17-cell B2 class, reproductions banked; register F-number.
- **R4 — tool-surface lane** (independent, from §L): snippet economy +
  result salience (11 B3-search cells; payload median ~13k chars).
- **Hero campaign**: founder-gated run after R1+R2 certify and (ideally)
  R3+R4 land, so the campaign measures the improved product on the credible
  instrument. Not a lane — a pre-registered event.

Recurring shape worth naming: **attributable-by-construction evaluation** —
every failure mechanically attributable to engine/model/eval from captured
fields alone, no forensic replay program. This is `verify-don't-guess` applied
to the eval substrate, and it is what made this analysis cost ~$0 where the
next one should cost ~nothing.

### §X.5 Open questions for the research pass

1. What does the 707 pipeline actually implement for real-text-injection
   (code, not recipe prose)? Effort to a working camouflaged injector?
2. Corpus redistribution/licensing posture for Enron + CLERC (and candidates
   for a third domain, e.g. code or wiki-style docs)?
3. What do published injection-style benchmarks do about camouflage and leak
   certification (NIAH descendants, RULER-class, fiction-QA designs)?
4. Rank-of-gold capture: where in the agent harness do search results pass
   through capture (McpToolSurface → agent_utility_inspect), and what is the
   cheapest persistence point that avoids the payload-redaction constraint?
5. Is there an established public anchor benchmark worth adopting for Frame D
   that JustSearch can index locally?

### §X.6 Research answers (2026-07-21, two opus researchers; condensed)

1. **707 injection status (Q1)**: implemented and used — see the §X.1.5
   correction. Effort for the camouflaged rebuild: **M-L**, dominated by
   domain-native entity naming + host-register sentence generation; the
   injection / determinism-proof / certification machinery is all reusable.
   Naming-leak source: `corpus_generate.py:320-323` (value code minted from
   the same monotonic counter as the last entity uid). Certification today:
   structural checks + SCIENTIFIC_GATES {closed_book (threshold 0.15),
   retrieval_calibration, union_recall, leak_floor}; missing: distractor-flood
   index, naming/format-leak check, gold-dispersion metric.
2. **Licensing (Q2)**: Enron = public-domain with a live privacy duty (fetch +
   inject locally; never republish modified mailboxes; PII-scrub published
   samples). CLERC rests on the Caselaw Access Project = **CC0 since 2024-03**
   (legally clear; every modified opinion must carry a machine-visible
   "synthetically altered" banner — integrity, not copyright). Third-stratum
   candidate: US gov documents (17 USC §105). Standard for all strata:
   fetch-then-inject deterministic recipes (741/709 patterns).
3. **Camouflage practice (Q3)**: lift Faithfulness-QA's recipe — NER-type +
   length-band-matched entity substitution from a host-derived entity bank
   (arXiv 2604.25313), with automated construction filters as gates; NoCha's
   minimal-pair device as the un-guessability certifier; the fabricated
   entity is itself the contamination control (closed-book arm floors ≈0 by
   construction — the current 707 closed_book gate already measures this).
4. **Rank-of-gold capture (Q4)**: **S effort** — (a) propagate `evidence_ids`
   into `Sample.metadata` (`agent_utility_inspect.py:1101-1102`); (b) extend
   `_tool_result_digest_entry` (:767-804, call site :881-883) to parse the
   structured `content.results[]` (ids + scores are rank-ordered at
   `McpEvidenceProjection.java:72/79`) into
   `ordered_doc_ids`/`scores`/`gold_rank`. Ids+ranks are tiny — outside the
   payload-redaction rationale.
5. **Public anchor (Q5)**: FRAMES (Apache-2.0; 824 multi-hop questions;
   publishes the exact same-model 40.8%→66% paired-uplift shape); fallback
   MultiHop-RAG (ODC-BY, self-contained 609 docs). Optional Frame-D scope —
   deliberately NOT chartered in the 766 program v1.

