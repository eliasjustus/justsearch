---
title: "bridge-entity retrieval lane: fix register F-039 — structure-descriptive queries must reach designer-keyed gold past near-duplicate decoys; the one engine-owned failure class in the agent-utility census, and it scales with corpus size"
type: tempdocs
status: "chartered (2026-07-21); theorized 2026-07-21 (§D) — diagnosis-before-design MANDATED: run the §D measurement plan (M1 first) before any fix design. Founder-run implementation lane."
created: 2026-07-21
author: agent (Fable orchestration), 766 program charter
category: search-quality / retrieval
related:
  - 766-eval-content-rebuild-program   # umbrella — READ FIRST
  - 763-retrieval-attribution-lane     # §F Results: the census that isolated F-039
  - 733-second-hop-compensation        # adjacent second-hop work — check for overlap before designing
---

> Charter. Load `/search-quality` before starting (F-039 is the entry; F-025
> legal leg-miss, F-037 pack curation, F-029/F-030-family are the
> neighborhood — do not re-derive them). This lane is independent of
> 767/768 and can run in parallel.

# 769 — bridge-entity retrieval lane

## §A. The defect (register F-039, evidence banked)

17/127 with-tool failures in the v5 census are engine-owned: for "value
associated with the designer of ⟨structure⟩" questions, every issued query
(4–12 per cell) AND every reasonable reformulation carries *structure*
vocabulary, while both gold docs are keyed on the *bridge entity* (the
designer). Hybrid retrieval returns near-duplicate synthetic decoys and
high-scoring real hard negatives instead; gold absent from top-20. Legal
only (email: zero); worsens 6%→28% of failures from 1k→10k. Reproductions:
`tmp/analysis-624/763/replay/classification_summary.json` + `replay_raw.*`;
the replay harness re-adopts the exact campaign index in ~16s
(`replay_stratum.py`, pinned-entry adoption per 763 §F note).

## §B. Scope and direction (design is the lane's — constraints only)

- Diagnose before designing: is the miss recall (gold never in candidate
  pools), fusion (a leg finds it, fusion drops it — F-025's leg-miss vs
  leak decomposition applies; `jseval recall-profile` is the instrument), or
  ranking (in pool, outranked by decoys)? Route the fix accordingly.
- D-005 governs: no per-corpus router; capability levers only (guarantees,
  leak-freeness, component quality). A "legal mode" is not an acceptable
  shape.
- Beware corpus-artifact overfit: the near-duplicate decoy crowd is partly
  a template artifact of the CURRENT corpora; 767's camouflaged strata will
  change the decoy landscape. Verify any fix against BOTH the banked
  reproductions and (once available) a 767-rebuilt stratum.

## §C. Acceptance

- The 17 B2 cells' issued queries reach agent-visible top-k (search k=10 /
  answer k=5) on replay against the banked indexes — or a written analysis
  of the residual cells explaining why they are corpus-artifact-bound.
- No regression: register relevance/leak/union-recall/perf gates green on
  the pinned corpora (scifact, legal-clerc-200, enron-qa, miracl); full
  suite + `./gradlew.bat build` green.
- Register updated before close: F-039 resolution note + any new baselines
  (that duty is this lane's, per the register rule).

## §D. Theorize (2026-07-21): hypothesis tree + measurement-first plan

> **DO NOT DESIGN YET.** This section deliberately proposes no fix. The
> program design pass ruled that designing before instrumented diagnosis would
> speculate ahead of data. The lane executor runs the M1–M5 plan below
> FIRST; the routing column decides whether 769 stays an engine lane at all
> (H4 could reframe it to a 770 surface question or a wontfix-with-rationale).
> Priors are honest starting bets, not conclusions — the killer measurement
> overrides the prior every time.

**Two facts that bind the priors (from evidence, not assumption):**
1. On legal, dense + SPLADE are near-dead (F-030/F-031/F-034, no model swap);
   the only live leg is BM25. So a bridge that structure-vocab can't reach
   *lexically* has no semantic leg to rescue it — this couples every
   hypothesis to lexical overlap between query and gold.
2. **Same query shape sometimes succeeds.** In the same census, B3 siblings
   with identical "⟨structure⟩ … designer value" shape DID surface designer-
   keyed gold single-shot (e.g. `B|q6` → `faldac13` rank 1; `B|q9` →
   `faldac19` rank 3). So the shape is not categorically unanswerable — the
   B2 cells differ by *degree* (crowding / overlap), which lifts the crowd-out
   hypothesis and weakens the absolute "no single query can ever do this" form
   of H4. The `fal-`/`crag`-stem doc families (faldac13/faldac33/wendcrag32/
   kancrag5) look like template near-dup families — the likely B2-vs-B3
   differentiator.

**Hypothesis tree** (evidence pattern → killer measurement → cost → routes to):

- **H3 — near-duplicate crowd-out (prior ~0.35).** Gold is in the merged
  pool but 40 template-identical decoys + real CLERC hard negatives occupy
  top-20. *Confirm/refute:* pool inspection at depth + dedup pass — if gold
  sits at rank ~21–60 and the ranks above it are near-dups of each other,
  a diversify/dedup step lifts it. Couples Q-013 (near-dup distractor =
  dedup's half, not a judge defect). *Cost:* ~$0 (M4 over cached pools).
  *Routes:* **769 engine fix** — a corpus-agnostic dedup/diversify capability
  lever (D-005-legal: assumes nothing about the corpus; funnel leak-freeness).
- **H4 — retrieval is being asked to do a join (prior ~0.25).** `1_hop` =
  2-entity chain; the designer is the *bridge* and the query names only the
  structure, so the designer-keyed gold is reachable only via a key the query
  never carries. *Confirm/refute (the reframe measurement):* designer-name
  oracle probe (M3) — inject the gold designer's name as the query; if gold
  then surfaces, the miss is a missing join key, not an engine ranking defect.
  *Cost:* ~$0. *Routes:* **770 surface / wontfix-with-rationale** — a two-step
  retrieve affordance (allowed under D-005 as a runtime-signal reaction to the
  query's own results, NOT a per-corpus router). Honest consequence: if H4
  dominates, 769's engine premise is technically true (wrong docs returned)
  but the *fix* is not engine-owned — the lane reframes.
- **H1 — leg-recall floor / representation (prior ~0.20).** Gold has near-zero
  structure lexical overlap; with dense dead, no leg surfaces it at any depth.
  *Confirm/refute:* candidate-depth sweep to k=1000 per leg via recall-profile
  (M2) — gold absent everywhere → floor. *Cost:* ~$0. *Routes:* **F-030
  representation territory / wontfix-in-769** (model-swap already ruled out;
  encoder-level, out of this lane's reach) — record with rationale.
- **H2 — fusion/branch-merge drop (prior ~0.10).** One leg surfaces gold deep
  but fusion/pre-merge truncation drops it below 20. *Confirm/refute:* F-025
  staged-recall leak decomposition (M2) — leg-recall includes gold, final
  top-20 doesn't. *Cost:* ~$0. *Routes:* **769 fusion-truncation leak fix**
  (D-005: "fusion is a ranking step, not a recall gate"). Low prior: with two
  near-dead legs there is little for fusion to leak.
- **H5 — corpus-artifact confound (prior ~0.10 as sole cause; MANDATORY
  modifier on all).** The near-dup crowd is partly a 707-template artifact
  (~40 template-identical gold/decoy docs); 767's camouflaged rebuild changes
  the decoy landscape. *Confirm/refute:* re-run replay on a 767-rebuilt
  stratum (M5). *Cost:* ~$0 but *blocked on 767 landing.* *Routes:* **767
  corpus** (miss evaporates → 769 closes as corpus-artifact-bound) or confirms
  engine-owned.

**Measurement plan — cheapest-decisive-first, all ~$0 (cached banked indexes,
replay adopt ~16s, no re-index, no paid API; recall-survival is the AI-free
signal per D-005, so no LLM judge is needed):**

1. **M1 — gold-doc content inspection** (near-zero; no backend). Read the 17
   cells' gold docs (`rellmond29`,`falven30`,`limker1`,`tasholt2`,… under
   `scripts/jseval/707-corpora/en-legal-clerc/{1000,10000}-verbose/`) and the
   template families around them. Single decisive fork: is the structure name
   present-but-outranked (→H3) or absent, gold keyed only on the designer
   (→H4/H1)? The `"grain-grinding"` quoted single-term reformulation already
   *missed* top-20 — a hint gold lacks the structure term — but M1 confirms it
   directly. Run this first.
2. **M2 — candidate-depth sweep + per-leg recall** (`jseval recall-profile` +
   `replay_stratum.py`, cached). For each B2 cell's issued queries retrieve
   k=100/500/1000 per leg; record gold's per-leg rank. Separates H1 (nowhere)
   from H2 (deep-then-leaked) from H3 (in-pool, outranked). This IS F-025's
   staged-recall instrument applied to these cells — no new instrument.
3. **M3 — designer-name oracle probe** (cached). Issue the gold designer name
   (from M1) as the query. Surfaces gold → H4 (reframe to 770); still misses →
   H1 (representation). The killer measurement for the engine-vs-surface fork.
4. **M4 — pool dedup/near-dup analysis** (cached; couples Q-013). For in-pool-
   but-deep cells, count how many ranks above gold are template near-dups; test
   whether a dedup collapse lifts gold into top-20 → confirms H3 + sizes the
   engine lever.
5. **M5 — 767-rebuilt stratum re-run** (deferred until 767 lands). Tests H5 and
   gates the acceptance clause on corpus-artifact-bound residuals; run against
   both the banked reproductions AND the camouflaged rebuild (§B constraint).

**First to run: M1** — it forks the entire tree at near-zero cost before any
backend spins up. M2 is the first backend measurement. Only after M1–M4 return
does the lane pick a fix (or reframe/close); no design precedes them.
