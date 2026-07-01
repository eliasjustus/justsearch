---
classification: "baseline-relaxation"
gate: "leak-gate"
dataset: "mixed/legal-clerc-200"
tempdoc: "666"
---

New corpus replacing mixed/courtlistener-200 (tempdoc 666): CourtListener-200's original human-authored qrels had no reproducible construction path anywhere in this project's history (confirmed via the private archive's full 6563-commit history) and could not be mechanically reconstructed. Replaced with a corpus built from CLERC (a real, citable academic legal-retrieval benchmark, jhu-clsp/CLERC on HuggingFace), deterministically sampled via a small recorded recipe (scripts/jseval/666-corpora/legal-clerc-200/recipe.json) -- the measured leak_rate_max (0.205) is the real, first-ever measurement for this new corpus, not a relaxation of a prior number.
