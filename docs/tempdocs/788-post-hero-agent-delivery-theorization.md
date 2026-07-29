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
