---
title: "Format breadth corpus and duplicate measurement: a real multi-format extraction corpus with marker assertions (eml/mbox/epub/zip/obsidian/code), plus the near-duplicate rate measurement 639 asked for before any dedup design"
type: tempdocs
status: CHARTERED (2026-09-02) — not started; DEPENDS on 686 (real binary-document corpus) — build the shared asset here if 686 has not
created: 2026-09-02
updated: 2026-09-02
lane: 887 L13
model: opus (takeover)
parent: 887-improvement-landscape-register
related:
  - 686-real-pdf-corpus-and-tika-pressure-measurement   # OPEN: "no real binary-document corpus exists"
  - 705-document-extraction-improvement-and-tax-reduction  # CLOSE-WITH-SPLIT: evidence-starved
  - 786-extraction-quality-scorecard   # chartered, unstarted measurement lane
  - 639-candidate-set-integrity-ann-recall-and-result-dedup  # stub: "first step is measurement, not a fix"
  - 314-simhash-dedup                  # abandoned 2026-03/05: "deprioritize until real-user duplicate evidence exists"
  - 709-shared-dataset-fetch-cache     # use jseval corpus-fetch-*, never ad-hoc downloads
  - 741-eval-corpora-are-derived-artifacts / 692-corpus-provenance-integrity
---

# 897 — Format breadth corpus and duplicate measurement

## Briefing for the agent picking this up

Fresh start. Read this file, 887 Appendix A5 (§5.2, 5.3, 5.6), then 686 and 639. Load `/jseval`
(corpus tooling, `dataset-cache-hint` will push you to `corpus-fetch-*`) and `/search-quality`
(register must be updated at close if you touch any quality baseline). Work in a worktree.
This is a **measurement lane**: it produces a corpus, tests, and numbers. It does not add
extractors or dedup code. Two PRs: corpus + tests; measurement report (§M here) plus a 639
update.

## Thesis

Tika auto-detect is the format policy (no allowlist), so email stores, ebooks, archives, code,
and notes exports are all *nominally* indexed, but the only tests are PDF/Office/text/CSV/JSON
and a no-crash zip case (`NastyCorpusTest.java:185-230`); zero `epub|.eml|mbox|.pst` hits in any
test or corpus. Tables are flattened to header-annotated text with no numeric typing. Nothing in
the pipeline collapses near-duplicates and no one has measured how common they are in a real
personal corpus (639).

## Scope

1. **Corpus** (provenance-clean, licensed, cached via `jseval corpus-fetch-*`, registered per
   692): per format ≥ 20 documents with **known marker strings** planted or documented:
   `.eml` + `.mbox` (public mailing-list archives), `.epub` (Gutenberg), `.zip` containing
   Office + text (built from existing fixtures), an Obsidian vault export (wiki-links,
   front-matter), a small source repo (Java/TS/Python), `.xlsx` with typed numeric columns and
   merged headers, `.pptx` with notes, `.odt`, `.rtf`, `.msg` if a free sample exists. Record
   the license per source in the corpus manifest.
2. **Marker searchability tests** in the `OfficeMarkerSearchabilityTest` pattern: ingest, then
   assert each marker is retrievable by keyword search *and* by the structured path (headings/
   tables) where applicable. Failures are findings, not things to relax — classify each as
   Tika-unsupported / sandbox-routed-and-lost / chunking-lost / policy-skipped with `file:line`.
3. **Table fidelity probe:** for the `.xlsx`/`.csv` members, assert the triplet serialization
   (`StructuredDocument.appendTable`) preserves header→value pairs for merged/multi-row headers;
   report where it does not. No numeric typing work (5.2 stays FABLE).
4. **Duplicate-rate measurement:** an offline script (`scripts/jseval/jseval/commands/dup-rate`)
   that computes simhash-64 over extracted text (shingled) for a corpus and reports exact-dup,
   near-dup (≤3 bits), and "version-family" candidates (same stem + `v2|final|copy|(1)` patterns)
   as a fraction of documents and of top-10 result sets for the eval query set. Run on: demo
   corpus (669), legal-10k, email-10k, and this lane's corpus. Product code untouched.
5. **Update 639** with the measured rate and a recommendation (design / defer), and 686 with the
   corpus location if 686 is still open.

## Acceptance criteria

- Corpus manifest under the jseval corpus registry with per-file license + sha256;
  `node scripts/ci/check-*` corpus provenance checks (692) green.
- New tests run under `./gradlew.bat :modules:worker-services:test` (tag them `@Tag("corpus")`
  if runtime > 60 s and document the flag in `09-testing-strategy.md`).
- §M table: per format — files, markers, found-by-keyword %, found-by-structure %, failure
  class; per corpus — dup-rate figures with n.
- `/search-quality` register: a "format breadth" row pointing here (no baseline change).

## Constraints

- No new extractors, no transcription pipeline (audio/video = owner decision), no dedup in
  product code, no Tika config changes — findings only. If a one-line policy fix
  (e.g. an extension wrongly in `IngestionSkipPolicy`) is verified, fix it and say so.
- Non-goals: VLM/OCR quality (607/797), eval relevance baselines.

## Status

(unstarted)
