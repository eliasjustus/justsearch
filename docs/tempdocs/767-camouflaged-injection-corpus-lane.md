---
title: "camouflaged-injection corpus lane: domain-native payload generator (entity bank + register templates), multi-schema questions, leak-free golds, and the certification gates that prove all of it — the 766 program's corpus half"
type: tempdocs
status: "chartered (2026-07-21). Founder-run implementation lane."
created: 2026-07-21
author: agent (Fable orchestration), 766 program charter
category: eval-design / corpus-generation
related:
  - 766-eval-content-rebuild-program   # umbrella: design decisions D2-D5, D8 — READ FIRST (and 762 §X for evidence)
  - 707-pillar1-inband-utility-corpus  # the pipeline being extended
  - 741-eval-corpora-are-derived-artifacts
---

> Charter. Execute after 766 §B (D2/D3/D4/D5/D8) and 762 §X.6.1/.3. The
> injection machinery, determinism proofs, and certification framework
> already exist — this lane replaces the PAYLOAD and adds GATES.

# 767 — camouflaged-injection corpus lane

## §A. Work items

1. **Entity bank + minting (D2).** Harvest a typed entity bank from each
   host corpus (deterministic NER or per-domain heuristic harvesting —
   mechanism is yours; determinism given corpus+seed is mandatory, no LLM in
   the build path). Mint gold entities type- and length-matched to native
   entities, collision-checked against the bank (must not exist in any real
   doc) and against each other. Kill the syllable-pair minting path.
2. **Register-matched fact sentences (D2).** Per-domain sentence templates
   in the host register (email prose for Enron hosts, judicial prose for
   CLERC hosts) replacing `_FILLER` and the current template sentences.
   Interleave via the existing `corpus_inject.assemble` seam.
3. **Leak-free golds (D5).** Format-diverse, domain-plausible gold values;
   value minting decoupled from entity uid counters (delete
   `corpus_generate.py:320-323`'s coupling); exact-match after
   normalization stays the scoring contract.
4. **Multi-schema questions (D4).** Single-fact lookup + 2-hop bridge +
   multi-doc aggregation, each with per-schema difficulty calibration;
   `question_type` labeling per the register's hop-count vocabulary note.
5. **Certification gates (D3).** In `corpus_certify` (structural-check and
   SCIENTIFIC_GATES seams, see 762 §X.6.1): distractor-flood index,
   injected-entity indistinguishability, naming/format-leak, gold
   dispersion, per-schema difficulty band. Wire thresholds into the 707
   certification policy file; a failing corpus is unbuildable into a
   campaign.
6. **Strata + distribution (D8).** Regenerate en-legal-clerc and
   en-email-enron-raw strata (1k/10k) on the new payload; "synthetically
   altered" header stamp as a build invariant; PII-scrub gate for published
   Enron samples; fetch-then-inject recipes only (no modified real docs
   committed). Optional (explicitly severable): third gov-docs stratum.
7. **Register duty.** Update the search-quality register Dataset Catalog
   (new strata rows; annotate the v5 strata as superseded-for-claims) and
   `/search-quality` + re-run `retrieval_calibration` baselines for the new
   strata before closing.

## §B. Acceptance

- All 766 §D orphan items 1/2 deleted in this lane's PR (retire-with-a-sweep).
- New strata pass ALL certification gates including the new five; closed_book
  ≈ 0 at haiku AND at the hero tier (coordinate with 768 for the tier run).
- A grep-simulation probe (the 764 mechanism-probe method) shows the
  baseline arm's expected grep experience is now vocabulary-matched across
  strata — the confound is measured dead, not assumed dead.
- Determinism: cross-interpreter regeneration proof green (existing seam).
- jseval suite green; `check-language-agnostic-analysis` unaffected (payload
  generation is corpus tooling, not engine analysis).

## §C. Constraints

- Zero paid-API in the build path (local model via `ai_activate` permitted
  for VALIDATION probes only, never generation).
- Windows: PYTHONUTF8=1; Edit/Write or python UTF-8 scripts only.
- 762 §D data inventory for all prior-campaign references; step2-powered
  worktree read-only.
