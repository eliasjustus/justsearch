---
title: "314: SimHash Near-Duplicate Detection"
type: tempdoc
status: done
created: 2026-03-17
depends-on: [309]
---

# 314: SimHash Near-Duplicate Detection

## Purpose

Add near-duplicate detection to the search pipeline. Personal file collections
accumulate duplicates (file copies, email forwards, versioned drafts) that
clutter search results. No dedup code exists in the codebase today.

## Background (from tempdoc 309 §7)

309 §7 researched dedup placement, fingerprint granularity, and thresholds:

- **Placement**: After chunk merge, before CE reranking. This preserves cross-
  retriever consensus signals during fusion and maximizes CE budget efficiency.
  Pipeline: `RETRIEVAL → FUSION → CHUNK MERGE → DEDUP → RERANKING`
- **Fingerprint**: Per-parent document, stored on chunks as DocValues (same
  pattern as `parent_token_count`). Chunks of the same document should NOT be
  deduplicated against each other.
- **Threshold**: Hamming distance ≤ 2 (not ≤ 3) for passage-length text. RETSim
  (Google, ICLR 2024) found SimHash performs poorly on short text.
- **Two-tier approach**: SimHash at index time (cheap, exact/near-exact), optional
  embedding-based semantic dedup at query time (catches paraphrases).

## Scope

- [ ] SimHash fingerprint computation at ingest time
- [ ] New `simhash` DocValues field in schema (`fields.v1.json`)
- [ ] Fingerprint stored on both parent and chunk documents
- [ ] Post-chunk-merge dedup stage in `SearchOrchestrator`
- [ ] Hamming distance ≤ 2 collapse (keep highest-scored copy)
- [ ] Skip reason `DEDUP_NEAR_DUPLICATE` in pipeline execution report
- [ ] Tests for fingerprint computation, dedup logic, and search integration

## Prevalence experiment (2026-03-17)

SimHash scan of available BEIR corpora (10K random pairs, Hamming k=1,2,3):

| Corpus | Docs | k≤1 | k≤2 | k≤3 |
|--------|------|-----|-----|-----|
| FiQA | 57,453 | 0% | 0% | 0% |
| NFCorpus | 3,633 | 0% | 0% | 0% |

**Zero near-duplicates in BEIR corpora.** These are curated academic datasets —
not representative of personal file collections. The experiment proves that BEIR
corpora cannot validate dedup, not that dedup is unnecessary. A test with real
personal data (file copies, email forwards, versioned drafts) is needed to
justify the implementation effort. **Deprioritize until real-user duplicate
evidence exists.**

## Out of scope

- Semantic/embedding-based dedup (future enhancement)
- Cross-index dedup (only within a single search result set)
- Dedup in the agent/RAG path (same mechanism, just needs wiring)

---

## Staleness review (2026-05-18)

Open with no closure activity in >60 days. Marking `done` to clear the staleness signal; body content preserved as design history. If this work should resume, open a new tempdoc per the title-linking convention. Classification: ABANDONED. Stale for 62 days at audit time.

