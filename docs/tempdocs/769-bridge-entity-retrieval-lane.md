---
title: "bridge-entity retrieval lane: fix register F-039 — structure-descriptive queries must reach designer-keyed gold past near-duplicate decoys; the one engine-owned failure class in the agent-utility census, and it scales with corpus size"
type: tempdocs
status: "chartered (2026-07-21). Founder-run implementation lane."
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
