---
status: "chartered (2026-07-28) as the SINGLE design home for the next agent-utility campaign. Design work licensed; ALL measured spend founder-gated. Nothing here is frozen — this charter collects the design axes so the eventual preregistration (a successor to 782 §E) freezes ONE coherent protocol instead of five conflicting ones. Source evidence: register F-043, 782 §I/§J, tmp/hero-arc-analysis/."
created: 2026-07-28
---

# 791 — Campaign v2 charter: the design axes the hero campaign's evidence dictates

## What v1 (782) established, in campaign-design terms

Accepted/adoption-only at sonnet, addition arm, 1k–10k fabricated 2-hop corpora, $0.80/cell:
adoption 1.0; no accuracy benefit; question-level negative NOT significant anywhere; the
mechanism is delivery-shape (hop-1 satisficing + abstention miscalibration), not retrieval;
the baseline arm degrades −5.8 pts under 10× scale while the tool arm is flat; exhaustion
asymmetry penalizes the tool arm under fixed caps (15% of the pooled delta); the tool arm is
25–43% faster in every stratum.

## Design axes for v2 (each with the evidence that put it here)

1. **Substitution arm** (tool-only vs file-tools-only): the unmeasured configuration, and
   the one matching the no-filesystem deployment class (cloud/sandboxed/MCP-only agents)
   where the tool is the only channel. v1's addition arm cannot answer it.
2. **Scale as the primary axis** (1k → 10k → 50k → 100k+, one question set): v1's one
   pro-product mechanism signal (baseline decays, tool flat; pivot deficit vanishing at 10k)
   predicts a crossover — v2 should be designed to FIND or FALSIFY it. Prerequisite: the
   tool arm's harness fragility at 10k (12 vs 4 stream/budget errors) must be fixed and
   smoke-verified before any 50k spend.
3. **Budget design**: fixed per-cell caps bias against the tool arm. Options to decide at
   freeze: compute-normalized arms; a cap high enough that exhaustion is rare in both arms;
   or reporting the accuracy-per-dollar / accuracy-per-minute frontier as a co-primary
   (v1's speed data suggests time-bounded frames where the tool already wins). Phase-1
   telemetry from tempdoc 789 (per-turn receipts) is the enabling instrumentation.
4. **Statistics**: question-level cluster-aware tests as the preregistered PRIMARY (v1's
   cell-level McNemar treats seeds as independent and overstates; the question-level test
   cleared α nowhere). Seeds stay for variance estimation, not for n-inflation. This is a
   policy-v5 item and should be dry-run against a synthetic record before freeze (the
   50-vs-20 lesson: BOTH v1 freeze defects were policy-vs-design incompatibilities reachable
   only at run/compose time).
5. **Model-tier axis** (the strategic one): haiku measurably benefited (624 token savings);
   sonnet did not (782). One campaign shape across ≥2 model tiers measures utility as a
   function of agent capability — directly testing the "value shrinks each model generation"
   headwind. Smallest honest version: one stratum × two tiers.
6. **Naturalistic stratum**: at least one natural-question stratum (enron-qa exists,
   real QA pairs) beside the fabricated chains, so behavioral findings (satisficing,
   abstention) are checked off-distribution from the generator that showed an
   offset-placement confound (engine-joins offset analysis: file-tools-arm offset
   correlations = generator artifact). Generator hygiene (independent randomization of gold
   placement) rides here if fabricated strata are regenerated.
7. **Delivery-shape interaction**: if tempdoc 789's Phase-2 probe ships a winning framing
   before v2 freezes, v2 measures WITH it (the product as it will ship); if not, v2 carries
   the framing question as an explicit arm or explicitly excludes it. Do not let the two
   campaigns' scopes silently overlap — 789 owns framing-vs-framing, 791 owns
   tool-vs-baseline.

## Sequencing constraints (not a plan)

789 Phase 1 (telemetry, $0) strictly before v2 freeze — v2's behavioral metrics come from
it. 790 (extraction fallback) is independent but if it ships, v2's corpora/index cohort
must pin it explicitly (cohort identity, 782's lesson). The frozen-protocol machinery
(cells.v1.json shape, preflight, budget guard, detached-driver operations, gitignored run
dirs, --agent-env pins) carries over from 782 §E/§I as-is; the operational playbook is
proven.

## Founder decision points this charter parks

Total budget and go/no-go; which axes make the cut if budget bounds the design (the
axes above are ranked roughly by information-per-dollar: 2 > 1 > 3/4 (free, design-only)
> 5 > 6); whether any resulting number publishes (623 pipeline, founder-only, unchanged).
