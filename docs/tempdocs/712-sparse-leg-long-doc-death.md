# 712 — Sparse-leg long-doc death: SPLADE truncation on long documents (F-030's sibling)

- **status:** seed — takeover pending (chartered 2026-07-11 from the 711 close-out; no
  investigation performed yet)
- **created:** 2026-07-11

## Charter question

Is SPLADE's near-zero contribution on long-document corpora a truncation artifact — the exact
sibling of F-030/F-031's dense window-mean death, transposed to the sparse leg — and if so,
what representation fixes it?

## Evidence that motivates the charter (verified, citable)

- `splade` mode on `mixed/legal-clerc-200` scores nDCG@10 **0.059–0.0591 in every measurement**
  (tempdoc 666 first baseline; 691 §N full-mode runs; 711's 2026-07-11 full-mode gate run at
  b88e76e) while `lexical` scores 0.686–0.689 on the same queries — the sparse leg contributes
  ~nothing where BM25 thrives.
- Suspected mechanism: whole-doc SPLADE encodes only the first ~512 tokens; legal-clerc parents
  run tens of thousands of tokens. Same shape as F-030 (window-mean dilution killed dense;
  fixed by F-031/F-032 — dense now 0.618 on this corpus, sparse still dead).
- 691 §G named "SPLADE whole-doc projection" as candidate scope; 710's restraint list
  explicitly deferred it pending its own evidence (`structural-defects-no-repeat`: critique
  substance, but evidence must demand the build). This tempdoc is that evidence pass.
- NOTE: chunk docs carry no SPLADE fields at all (711 E2 audit: `SpladeBackfillOps` has zero
  `is_chunk` handling; chunks get dense-only enrichment) — so there is no chunk-level sparse
  signal to merge today.

## Cheapest evidence (the takeover's first question)

An offline experiment in the 708-harness style (`scripts/jseval/experiments/`, precedent:
`late_chunk_cls_check_691.py`): encode legal-clerc parents with (a) production truncated
whole-doc SPLADE vs (b) per-chunk SPLADE at ChunkSplitter boundaries with max-pooling /
term-union merge, score chunk- and doc-level retrieval offline. If (b) ≫ (a), the truncation
hypothesis is confirmed and the design question becomes the write/merge shape; if (b) ≈ (a),
the sparse leg's weakness is encoder-domain (708's lane), not representation, and this tempdoc
should close with a null verdict.

## Constraints / relations

- Any chunk-level SPLADE fields are new non-stored `FeatureField`s → MUST declare an
  `rmwPolicy` in `fields.v1.json` (711's startup fail-fast enforces this).
- Enrichment cost is a first-class output: per-chunk SPLADE multiplies encode calls; the 691
  register tracks enrichment docs/s baselines.
- Register: `docs/reference/search-quality-register.md` (read before, update before close).
  Related findings: F-013 (splade quality vs BGE-M3 sparse), F-030/F-031/F-032.
