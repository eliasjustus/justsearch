---
status: "theorization (2026-07-28) — no design settled, no implementation licensed. Source evidence: the 782 hero campaign (register F-043, 782 §I/§J) and the second-pass analyses (per-cell statistics, transcript behavior census, per-query engine joins; scripts + outputs under tmp/hero-arc-analysis/, methodology summarized in 782 §I and the F-043 register entry). Everything here is a candidate direction, deliberately unfiltered by feasibility."
created: 2026-07-28
---

# 788 — Post-hero theorization: the agent-delivery layer, and what the campaign's mechanism evidence points at

The 2026-07-28 hero campaign produced an accepted **adoption-only** verdict (F-043): agents
adopt the MCP tool universally and gain no measured accuracy from it in the addition arm. The
second-pass analyses then produced something rarer than the verdict — a **mechanism**: the
tool's response shape makes a capable agent *stop reasoning* at intermediate facts
(name-pivot rate deficit ≈ the entire net loss), *abstain* twice as often at identical
fabrication rates, and treat synthesized answers as terminal. Meanwhile the same data showed
the baseline arm degrading under 10× corpus scale while the tool arm stayed flat, and the
engine's unique capability (paraphrase bridging) working — once — where no grep could.

This tempdoc theorizes what work all of that *indicates*, explicitly ignoring feasibility,
cost, and sequencing. Nothing here is a plan.

## 1. The reframe the evidence forces

A retrieval tool exposed to an agent is not a ranker with a wire format. It is an
**epistemic actuator**: its response shape sets the agent's stopping policy, its failure
shape sets the agent's iteration policy, and its synthesis shape sets the agent's trust
policy. The campaign measured all three, by accident, before we had words for them:

- A fluent, question-shaped result **terminates** the loop (hop-1 satisficing, 38% of breaks).
- A cheap, legible *no-match* (grep's 14-byte failure) **licenses iteration**; a heavy,
  authoritative-looking empty search result licenses **giving up** (abstention 2× baseline;
  replay-verified cells denying gold present in their own output).
- A synthesized answer (`justsearch_answer`) is **more terminal still** — anti-correlated
  with success in all three strata.

Grep, the "dumb" competitor, wins not by finding more but by *structurally forcing the next
move* — the only way to see the second document is to type the entity's name. The product's
losing margin was not recall; it was that its responses feel like answers.

## 2. The broader principle candidate (system shape, not yet a design)

This is D-005's funnel-and-judge principle recurring **one layer up**. D-005: a pipeline
stage must not silently drop a correct *candidate*; observe every stage by recall-survival.
The hero evidence says the delivery layer is itself a funnel stage, and what it drops is not
candidates but **continuations** — the agent's next correct reasoning step. Candidate name:
**reasoning-survival** (or continuation-survival). Its staged-recall-accounting analogue
already exists in embryo: the census's behavioral metrics (name-pivot rate, hop-1-stop rate,
abstention-at-identical-fabrication, post-search read rate, fallback-after-MCP rate)
*explained the entire campaign* and were computed after the fact by hand. The indicated
shape: these become standing, per-run instrumentation of any agent-facing eval — the
delivery layer observed by continuation-survival, not payload correctness. (Deliberately not
designed here; the D-005 lesson is that the observability half comes first and cheap.)

A second, uncomfortable principle candidate: **adoption is not the target; calibrated
adoption is.** The 655 affordance work moved adoption 7% → 100%, and the campaign then showed
100% adoption with zero benefit — the tool is now *over-trusted* relative to its measured
value. The 624-era 7% may have been closer to rational. A tool that agents reach for exactly
when it helps (and skip when grep is better) would outperform both extremes; tool-choice
rationality is itself measurable from existing transcripts.

## 3. Direction families the evidence indicates

### A. The agent-delivery layer (response framing as a product surface)

1. **Intermediate-fact marking / continuation affordance.** Delivered spans that name a
   corpus-frequent entity could carry an explicit signal: this sentence names an entity with
   further references (n docs); the answer to a who/which/what-value question may require a
   second retrieval. The maximal version is a `next_hops` field (entities in delivered spans
   + their document frequency). The minimal version is one sentence of framing text.
2. **Anti-satisficing framing.** Deliver results as *evidence matching terms X, Y* rather
   than answer-shaped prose; explicitly disclaim answerhood. The risk (worth its own probe):
   framing that is too hedged gets ignored, too pushy erodes trust — this is a measurable
   A/B, not an aesthetic choice.
3. **Calibrated negative results — an epistemic honesty contract.** When a query returns
   nothing or little, say what that does and does not mean: coverage (n docs indexed, m
   touched), nearest-miss terms, an explicit "absence of results is not evidence of absence;
   consider alternate phrasings or file tools." The abstention-miscalibration evidence says
   the current empty result reads as authoritative. No retrieval product does this; it is
   the product-level twin of the measurement-honesty thesis.
4. **Move the second hop into the engine.** The alternative to prompting the agent to
   continue is doing the continuation internally: `justsearch_answer` detects that its
   retrieved fact names a bridge entity, re-queries it, and chains — multi-hop synthesis
   where it is testable and deterministic, instead of hoping the agent pivots. (This inverts
   770's withdrawn `fetch`: the missing piece was never a fetch tool, it was who owns hop 2.)
5. **Cheap-failure, compact-first delivery.** Grep's virtue is economic: failures cost ~14
   bytes, so five wrong guesses are fine. Search responses cost 15–25 KB and real dollars
   (exhausted tool cells are retrieval *loops* at ~$0.040/turn). Indicated: compact-first
   responses (ids + one-line spans) with expansion on demand — making iteration as cheap as
   grep makes it. This simultaneously attacks the exhaustion asymmetry.
6. **A delivery-shape eval axis.** Same retrieval, different framings, measured by the
   behavioral metrics (§2) plus accuracy. A genuinely novel measurement dimension —
   "delivery-shape utility" — and cheap, since retrieval is held constant.

### B. Engine work

7. **Extraction dropout fallback.** The −13.7% extraction tax is carried 83% by ~12% of
   queries whose gold documents extracted to *empty text*. Indicated: detect empty/trivial
   extraction at index time and fall back (OCR/VLM chain); never silently index an empty
   doc — surface extraction quality to search. Cheap, targeted, and the "better OCR engine"
   alternative is already measurement-rejected (F-042: GOT statistically ties Tika).
8. **Ensemble/racing extraction.** The three extractors fail on mostly *different*
   documents (worst-50 overlap 0.11–0.16). Per-document extractor selection — even
   crude racing on the dropout set — should beat any single engine on this data.
9. **Fusion attribution before fusion adaptivity.** Step-0's protective-gate *decision*
   stands, but its mechanism story ("flat weight hurts where the leg is weak") was refuted
   per-query — the harm is uncorrelated with leg strength. Indicated: a per-query fusion
   attribution study (who contributed what, where harm concentrates) as the prerequisite for
   any score-aware/adaptive weighting design; the chunk-splade lever stays parked behind it.
10. **Paraphrase-bridging reliability as its own axis.** The engine's unique demonstrated
    value bridged works→mint once and failed reactor→power-station six times out of six.
    Indicated: a synonym-pair probe suite measuring bridge reliability by paraphrase
    distance — the engine-side counterpart of the delivery work, since agents provably will
    not reformulate (query containment 0.74 in the question, 0.18 in gold).
    **BUILT + FIRST RESULTS (2026-07-29) → tempdoc 796.** The suite exists
    (`scripts/jseval/experiments/paraphrase_bridge_suite.py`, three tiers, five arms, pairs
    extracted from the generator rather than hand-authored). Headline: bridging is a steep step
    function of isolated pair cosine with a knee near 0.65, and the generator's own pools straddle
    it; the lexical control bridges 0/180 pairs, so everything recovered here is the semantic
    stack's doing. **The reactor anchor does not reproduce** — `power station → reactor` bridges
    about as well at the descriptor level (tier P rank 2/21, tier S rank 2/100) as the case that
    succeeded, so q0's 6/6 hero failure is not a failure of the paraphrase pair. The in-corpus tier
    that would discriminate the remaining explanations — query shape vs host dilution vs a
    downstream/agent cause — is scripted but **deferred to a serialized compute slot** (796
    §Deferred), with a pre-registered hypothesis. If it lands on query shape, this item and §3.A's
    delivery work are the same problem from two ends.

### C. Measurement & methodology

11. **Question-level statistics as the preregistered primary.** The cell-level McNemar
    treats seeds as independent and overstates significance (p=0.045 cell-level vs 0.136
    question-level on the decisive stratum). A future policy revision should make the
    cluster-aware question-level test primary.
12. **Behavioral telemetry as standing harness output** (§2) — pivot rate, hop-1-stop rate,
    abstention taxonomy, fallback-after-MCP, per-turn receipts (currently not persisted —
    burn curves were underivable). The census took a day of agent work; the harness could
    emit it for free.
13. **Budget-matched or frontier designs.** Fixed per-cell caps penalize the tool arm
    (exhaustion asymmetry, 15% of the delta). Alternatives: compute-normalized arms, or
    reporting the accuracy-per-dollar frontier instead of accuracy at a cap — the speed
    data (tool cells 25–43% faster) suggests the tool may already win time-bounded frames.
14. **The scale axis, extended.** Baseline −5.8 points at 10×, tool flat, pivot deficit
    vanishing at scale: the crossover experiment (1k → 100k on one question set) is now a
    designed, falsifiable target — and doubles as the product's thesis in one chart. The
    tool arm's harness fragility at 10k (3× the stream/budget errors) is the prerequisite fix.
15. **The substitution arm** — the deployment class with no filesystem (cloud/sandboxed
    agents) is unmeasured and is the configuration where the tool is the *only* channel.
16. **Capability-interaction curve.** Haiku measurably benefited (624 token savings); sonnet
    did not (hero). Indicated: utility as a function of agent-model strength — one campaign
    shape across model tiers. Strategically decisive: it tests the "value shrinks every
    model generation" headwind directly.
17. **Naturalistic replication.** Every mechanism finding rests on fabricated 2-hop chains.
    The satisficing/abstention behaviors should be checked on natural questions (enron-qa
    exists) before the delivery layer is tuned to synthetic chains — the Goodhart risk of
    fixing exactly what the eval measures is real.
18. **Generator hygiene.** The offset join found offset↔outcome correlations in the
    file-tools arm, where offset cannot matter — a corpus-generation confound (question
    family entangled with placement). Indicated: independent randomization of gold placement
    in the generator.

### D. Product / strategic

19. **The epistemic-contract differentiator.** Calibrated negatives, coverage statements,
    intermediate-fact honesty (§A.1–3) as *the* agent-facing differentiator — the
    measurement-honesty moat expressed in the product itself rather than beside it.
20. **A response-contract convention.** The delivery-layer findings generalize beyond this
    product: any MCP retrieval tool shapes agent epistemics. A small public convention for
    agentic-retrieval responses (intermediate flags, coverage, calibrated absence) is a
    standards-shaped positioning play backed by unusually direct evidence.
21. **Time/cost framing of the existing result.** Tool cells are faster in every stratum. If
    delivery fixes reach accuracy parity, the supportable claim becomes "same accuracy,
    ~30% faster, flat under scale" — a different and honest pitch that the current data
    already half-supports.

## 4. Hidden assumptions and risks worth keeping visible

- **"Adoption 1.0 is success"** — challenged above (§2); over-trust is a failure mode with
  the same telemetry signature as product-market fit.
- **"The agent should do the hops"** — §A.4 inverts it; both branches deserve a probe before
  either is built.
- **"Accuracy is the utility"** — the speed/cost data suggest bounded-resource frames where
  the current tool already wins; the choice of estimand is itself a product decision.
- **Overfitting the fix to the probe** — delivery changes tuned on synthetic 2-hop chains
  must replicate on natural tasks (§C.17) or they are eval-ware.
- **Framing backfire** — continuation prompts and disclaimers can be ignored or can erode
  trust; §A.2/A.6's A/B is the guard.
- **Single-model, single-family evidence** — all mechanism claims are sonnet-on-Claude;
  §C.16 is the generality check.

## 5. What this is not

No item above is licensed for implementation by this tempdoc. The likely next concrete
steps (delivery-shape probe design, dropout-fallback design, v2 campaign charter) each
deserve their own numbered tempdoc with pre-registered acceptance criteria; this document
exists so those charters start from the full option space rather than the first idea.

---

## 6. Tool-choice rationality findings (hero census, post-hoc) — 2026-07-29

§2 asserted, without evidence, that **100% MCP adoption is over-trust rather than fit**. A
$0 offline analysis pass over the already-captured hero transcripts now tests that assertion
directly. It is a *post-hoc census of existing logs* — no new campaign, no counterfactual
replay, no spend. The answer is **yes, by three independent cuts**, with the limits below.

**Provenance.** Scripts and outputs live under `tmp/hero-arc-analysis/tool-choice/`
(uncommitted analysis artifacts, not repo content): `common.py` (shared loader + trajectory
classifier), `q1_choice_map.py` → `q1_choice_map.v1.json`, `q2_counterfactual_leaning.py` →
`q2_counterfactual_leaning.v1.json`, `q3_rationality_score.py` → `q3_rationality_score.v1.json`,
`q4_framing_effect.py` → `q4_framing_effect.v1.json`, prose summary in `findings.txt`. Every
number is re-derivable with
`PYTHONUTF8=1 PYTHONPATH=<repo>/scripts/jseval python qN_*.py`. All reads go through
`jseval.agent_utility_observations.read_inspect_observations` (`require_complete=False`,
**judge overlay NOT applied** — substring-EM only, the same convention
`tmp/hero-arc-analysis/stats/matrix.v1.json` uses). Each output JSON embeds the classification
rules verbatim in its `classification_rules` field.

**Scope.** Hero window-1 (`782-run-2026-07-28-hero-window1`) + window-2
(`782-run-2026-07-28b-hero`): 3 strata × A/B × 3 seeds = **360 B cells**, each with a paired A
(grep-only) twin on the same (window, stratum, seed, qid); plus the 789 framing probe (F0/F1/F2,
B-only, 40 attempted cells each, all `en-email-enron-raw-1k-verbose`). F3 is excluded — never
composed, no `utility-comparison.v1.json`.

**Trajectory classification.** Each B cell's `tool_call_sequence` is reduced to an M/N string
(M = MCP search/answer call, N = Grep/Read/Glob/Bash; other tools dropped by design), then
bucketed by switch count and block order into: MCP-only, MCP-then-native-fallback,
native-dominant-after-first-MCP, interleaved (≥2 switches; `native-first-then-mcp` folded in),
and native-only-despite-offer (**observed n=0** in hero).

### 6.1 Q1 — paired-cell choice map (`q1_choice_map.v1.json`)

Of 360 B cells, **299 have a usable paired A twin** (both attempted+scored, neither
harness-excluded):

| verdict | n / 299 | share |
|---|---:|---:|
| over-trust candidate (B underperforms its own A twin) | 65 | **21.7%** |
| fit (B beats its own A twin) | 37 | **12.4%** |
| tie, both correct | 68 | 22.7% |
| tie, both wrong | 129 | 43.1% |

Over-trust outnumbers fit **~1.8:1** at the paired-cell level. By trajectory class
(over-trust rate / fit rate, derivable pairs):

| class | over-trust | fit | n |
|---|---:|---:|---:|
| MCP-only | 30.8% | 7.7% | 26 |
| MCP-then-native-fallback | 25.0% | 2.3% | 44 |
| native-dominant-after-first-MCP | 30.1% | 8.2% | 73 |
| **interleaved** | 15.4% | **17.9%** | 156 |

**Interleaved is the only net-positive class** — the agent treating MCP as one signal among
several (≥2 switches) rather than trusting a single pass or abandoning it after one fallback.

### 6.2 Q2 — per-qid win-rate gap and behavioral flags (`q2_counterfactual_leaning.v1.json`)

Mean gap = B-class accuracy minus that qid's A accuracy, averaged over (stratum, qid) groups:

| class | mean gap vs A | qid-groups ≥ A |
|---|---:|---:|
| MCP-only | −0.250 | 43.8% |
| MCP-then-native-fallback | −0.262 | 44.8% |
| native-dominant-after-first-MCP | −0.133 | 55.3% |
| **interleaved** | **+0.054** | 67.2% |

A single clean fallback is not enough; only repeated cross-checking closes the gap on average.
Behavioral flags, pooled over all B cells:

| class | abstained | fabricated_specific |
|---|---:|---:|
| MCP-only | 23.1% | **42.3%** |
| MCP-then-native-fallback | 44.7% | **42.6%** |
| interleaved | 33.5% | **17.4%** |
| native-dominant-after-first-MCP | **63.2%** | 14.5% |

The two MCP-first classes fabricate most (commit to an MCP-shaped answer and stop);
native-dominant abstains most (heavy native searching after MCP ends in "not found").
Interleaved has both the lowest fabrication and a below-median abstention rate.

**Honest gap, not estimated:** `hop1_stop` is `None` for **every** hero window-1/window-2 cell —
both windows predate the delivered-span behavioral fields shipped in 789/#319 (confirmed
directly: 0 cells have `entity_source` set). Q2's hop-1 question is therefore answerable only
via the probe (§6.4), and is reported as not-derivable rather than inferred.

### 6.3 Q3 — rationality score and choice-oracle bound (`q3_rationality_score.v1.json`)

Channels per (stratum, qid): A (grep-only), `B_mcp_heavy` (MCP-only + MCP-then-native-fallback),
`B_mixed` (interleaved + native-dominant). A B cell is "rational" iff its own channel is in its
qid's empirically-best channel set. **Stated limit, up front: this is an in-sample /
retrospective score** — the best channel is computed from the very cells scored against it
(typically 1–6 cells per channel per qid, no held-out fold). Descriptive, not "the agent could
have known this in advance."

- Overall rational-choice rate (B cells): **49.1% (155/316)**
- …of which **114/316** qid-groups had "no MCP at all" as the sole best channel (any MCP use
  scores irrational there)
- Shape-conditional rate (excluding those 114 — "given MCP use was justified, was the shape
  right?"): **76.7% (155/202)**
- Per stratum: en-email-1k **33.6%** | en-email-10k **55.9%** | en-legal-clerc-1k **58.6%**
- Per B-channel: `B_mcp_heavy` **24.7%** | `B_mixed` **56.4%**

Choice-oracle upper bound (**explicitly an upper bound, same in-sample caveat**):

| | accuracy | Δ |
|---|---:|---:|
| observed B | 35.1% | — |
| full oracle (may also choose "no MCP at all" per qid) | 57.9% | **+22.8 pts** |
| shape-only oracle (must use MCP; may choose the shape) | 44.7% | +9.6 pts |

**~2.4× more theoretical upside comes from knowing when *not* to touch the tool than from using
it in the right shape once committed.** With the 76.7% shape-conditional number, this is the
sharpest support for §2's reading: the failure is over-adoption, not technique.

### 6.4 Q4 — framing effect on choice, 789 Phase-2 probe (`q4_framing_effect.v1.json`)

Accuracy is ITT (correct / all 40 attempted cells, excluded counted wrong) — recomputed from
source and matching the previously-cited figures exactly (F0 11/40 = 0.275 with 6 excluded,
F1 17/40 = 0.425 with 3 excluded).

| metric | F0 | F1 | F2 |
|---|---:|---:|---:|
| accuracy (ITT) | 0.275 | **0.425** (+0.150) | 0.325 (+0.050) |
| hop1_stop rate | 0.350 | **0.225** (−0.125) | 0.250 (−0.100) |
| abstained | 0.375 | 0.300 (−0.075) | 0.300 (−0.075) |
| native-followup-after-MCP | 0.850 | 0.900 (+0.050) | 0.900 (+0.050) |
| 2nd-search-or-more | 0.925 | 0.950 (+0.025) | 0.825 (−0.100) |
| name_pivot | 0.850 | 0.900 (+0.050) | 0.800 (−0.050) |

Trajectory shift F0 → F1 (n=40 each): MCP-only 6 → 4; **MCP-then-native-fallback 4 → 8
(doubled)**; interleaved 18 → 19 (flat); native-dominant 12 → 9.

F1 (continuation framing) moves trajectory choice in the direction §2 theorized — it roughly
doubles the clean single-fallback share at the expense of MCP-only and native-dominant cells,
and **nearly halves the hop-1-stop rate**, which plausibly explains most of its +15pp accuracy
gain. F2 (evidence-not-answer framing) moves the same dials the same direction but more weakly,
for a much smaller gain, while *reducing* the 2nd-search (−10pp) and name-pivot (−5pp) rates
versus F0 — so F2's benefit appears to come from a different, less continuation-driven
mechanism than F1's.

### 6.5 What this changes here, and what it does not

It converts §2's over-trust hypothesis from an assertion into a measured claim, and it
re-weights the option space: the dominant lever indicated by Q3 is **adoption calibration**
(when the tool should be reached for at all — §A.1–3's calibrated-negative/coverage family and
§A.6's framing work), not within-tool technique. Q4 shows framing is a real, cheap lever on the
same pathway with retrieval held fixed. **Nothing here is licensed for implementation** — §5
still governs; this is evidence for the charters, not a design.

**Limits carried forward verbatim from `findings.txt` (do not over-read):**

- Q1/Q2/Q3 are **correlational / in-sample**; no counterfactual replay exists.
- `hop1_stop` is **not derivable** for all hero window-1/window-2 cells (pre-789 logs) — the
  hop-1 evidence comes only from the Q4 probe.
- Trajectory classes are a 2-symbol (M/N) reduction of the full tool vocabulary;
  ToolSearch/Task/WebFetch calls are dropped from the reduced trace **by design** (documented in
  `common.py`), not silently miscounted.
- Q3's oracle bound assumes perfect per-qid foreknowledge drawn from the same outcomes being
  scored — an upper bound, never an achievable target.
- The Q4 probe is a **single stratum** (`en-email-enron-raw-1k-verbose`), n=40 per framing — a
  3-arm shift on 40 cells each, not a high-power replication.
