---
title: "319: Multi-Language SPLADE Suppression"
type: tempdoc
status: done
created: 2026-03-17
depends-on: [309]
---

# 319: Multi-Language SPLADE Suppression

## Purpose

Suppress SPLADE contribution for non-English documents. The current SPLADE model
(naver/splade-v3) uses an English BERT backbone — non-English tokens decompose
into many subword fragments, producing noisy sparse expansions that dilute
fusion quality.

## Background (from tempdoc 309 §20)

309 §20 researched multilingual retrieval:

- **BM25** with ICU analyzer handles Unicode segmentation for most languages.
- **SPLADE** (English BERT backbone) should not be trusted for non-English text.
  SPLADE-X (mBERT backbone) exists for CLIR but is a different model.
- **nomic-embed-text-v1.5** is English-focused. Non-English quality is undefined.
- **`LanguageUtils.detectLanguage()`** already exists at ingest with Unicode
  block heuristics (CJK, Cyrillic, Arabic, etc.).
- **BGE-M3** (100+ languages, Java ONNX impl) is the future multilingual
  embedding model candidate.

## Current state

- `LanguageUtils.detectLanguage()` runs at ingest, detects language via Unicode blocks
- Detected language stored as metadata
- No SPLADE suppression for non-English content
- No query-side language detection
- SPLADE parent-length modulation mechanism (§26) exists and can be reused for
  language-based suppression

## Scope

- [ ] At ingest time: tag documents with detected language in a DocValues field
  (or use existing `language` stored field if available)
- [ ] In `HybridFusionUtils.fuseWithCC3`: suppress SPLADE weight to 0 for
  documents with non-English language tag (reuse the parent-length modulation
  pattern — same `spladeWeight` mechanism, different signal)
- [ ] In SPLADE backfill: optionally skip SPLADE encoding for non-English docs
  (saves compute; no value in noisy expansions)
- [ ] Tests: verify SPLADE weight = 0 for non-English docs in fusion

## Experiment prerequisites (assessed 2026-03-17)

**No non-English BEIR dataset is cached locally.** MIRACL is the standard
multilingual retrieval benchmark but is 25-2800x larger than SciFact per language:

| MIRACL subset | Docs | Queries (dev) |
|--------------|------|---------------|
| Swahili (smallest) | 132K | 482 |
| Korean | — | 213 |
| French | 14.6M | 343 |

A practical experiment would sample 2-5K docs from a MIRACL language (matching
SciFact scale) and use dev queries. This requires downloading MIRACL via
`ir-datasets`, sampling, materializing, and running a full ingest + SPLADE
backfill cycle — several hours of setup for a low-priority item.

**Deprioritize** unless multilingual users report quality issues.

## Out of scope

- Query-side language detection (too unreliable for short queries — 309 §20)
- Multilingual embedding model swap (BGE-M3 — separate tempdoc)
- Per-language BM25 analyzer configuration (ICU handles basics)

---

## Staleness review (2026-05-18)

Open with no closure activity in >60 days. Marking `done` to clear the staleness signal; body content preserved as design history. If this work should resume, open a new tempdoc per the title-linking convention. Classification: ABANDONED. Stale for 62 days at audit time.

