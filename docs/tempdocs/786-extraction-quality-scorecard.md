---
title: "extraction-quality scorecard — stand up the 623 §F sibling: measured numbers for the quality dimension the retrieval scorecard explicitly excludes"
type: tempdocs
status: "chartered (2026-07-22). Measurement lane; the corpora already exist in the catalog (OHR-bench family, tempdoc 252) — the work is running the current stack against them and wiring the envelope discipline retrieval already has."
created: 2026-07-22
author: agent (Fable orchestration), chartered from the 2026-07-22 remaining-work map (founder-directed)
category: extraction / measurement
related:
  - 623 (release scorecard)  # §F names the extraction-quality sibling this lane builds
  - 252 (OHR-bench corpora)  # ohr-bench-{clean,got-moderate,mineru-moderate,tika-pdf} — 962 queries each, already cataloged
---

## §A. Problem

The Release Scorecard states its own boundary: it measures retrieval ranking quality and
"does NOT measure document extraction / OCR / VDU routing quality" (623 §F). But the
product's promise is search over real, messy files — PDFs, scans, office docs — where
extraction quality upper-bounds everything downstream. The measurement assets exist and are
idle: four OHR-bench variants in the catalog (ground-truth text vs GOT-OCR vs MinerU vs
original-PDFs-through-Tika, 962 extractive queries each, last validated 2026-03), which
isolate the extraction variable by construction (same queries, same content, different
extraction paths). There are currently NO numbers for the shipped extractor at HEAD.

## §B. Scope

1. **Baseline sweep:** hybrid-mode runs across the four OHR-bench variants at HEAD
   defaults. The clean-vs-tika delta IS the extraction-quality cost of the shipped Tika
   path; clean-vs-GOT/MinerU brackets what better extraction would buy.
2. **Per-domain breakdown** (OHR-bench spans 7 domains) — extraction cost is not uniform;
   the table should say WHERE the shipped path loses (tables? scanned? multi-column?).
3. **VDU routing check:** where the VDU-eligibility path exists (VduEligibility tests,
   local Tesseract env), measure whether routing decisions correlate with the docs the
   Tika path loses — routing precision is only meaningful against measured loss.
4. **Scorecard wiring:** an internal extraction scorecard section (register or a sibling
   doc per 623 §F's design), with the same cohort-envelope noise discipline retrieval has;
   NOT the public scorecard (623 publication stays founder-gated).
5. **Catalog hygiene ride-along:** re-validate the four OHR rows (Last Validated 2026-03)
   against the current committed content, same as any dataset touch.

## §C. Acceptance

- A filled table: 4 variants × hybrid nDCG@10 (+ per-domain) at a named git SHA, register-
  recorded with confidence tiers.
- The extraction-cost headline (clean minus tika) stated with CI, plus the bracketed
  headroom (GOT/MinerU deltas).
- A routed conclusion: either "extraction cost is material → charter the improvement lane
  with these numbers as its baseline" or "cost is within X% → extraction is not the
  bottleneck; recorded, no lane" — both are wins; the void is the only loss.

## §D. Notes

- Pure measurement lane: no extractor changes in scope. Any improvement work gets its own
  charter with this lane's table as the baseline (instrument-first, again).
- Windows local env caveat: the Tesseract/tessdata-dependent tests are known-red locally
  (expected-state) — the eval path must not silently depend on that env; verify the
  extraction actually ran per-variant (interrogate the doc counts, not just the scores).
