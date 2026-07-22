---
title: "chunk-SPLADE engine integration — cash in the measured 5-9x sparse-leg revival on long docs (F-033 → production)"
type: tempdocs
status: "chartered (2026-07-22). The offline result is banked (tempdoc 712 / F-033 / Q-017); this lane is engine integration behind a flag, following the F-031 dense-leg playbook."
created: 2026-07-22
author: agent (Fable orchestration), chartered from the 2026-07-22 remaining-work map (founder-directed)
category: search-engine / sparse-retrieval
related:
  - 712 (offline chunk-SPLADE probe)     # the banked measurement this lane productionizes
  - 783-long-document-representation-program  # sibling; 784 is the sparse face of the same scale problem
  - 775-evidence-span-authority          # chunk provenance conventions to reuse
---

## §A. Problem

The SPLADE leg is effectively dead on long documents: 512-token encoder truncation means a
legal doc's sparse representation covers its head only (register F-033). Measured: legal
splade-mode 0.059 at defaults, while offline per-chunk SPLADE over the whole doc revives it
to **0.327 (max-pool merge) / 0.545 (chunk-MaxP)** — the largest measured, unclaimed
headroom in the engine (Q-017). This is the exact structural twin of the dense leg's F-031
(single-pass long-doc vectors, 0.060→0.297 at defaults), whose playbook — flag-gated engine
integration, fresh-build re-measure, defaults flip on evidence — already worked once.

## §B. Scope

1. Per-chunk SPLADE encoding at enrichment time for docs past the truncation boundary
   (reuse the existing chunking + `chunk_start_char` provenance; no second chunking
   authority), with a merge policy at query time: start with the two offline-validated
   candidates (max-pool merge; chunk-MaxP) behind one flag + one policy knob.
2. Index/storage shape for chunk-level sparse vectors — conform to how chunk dense vectors
   are stored (adapters-lucene); Worker-side only (Head never touches Lucene).
3. Measurement: fresh `--clean` builds; legal-clerc-200 + the 781 rebuilt strata for the
   target effect; scifact/enron/miracl as no-regression controls; both merge policies
   measured; enrichment-throughput delta captured (this ADDS encoder work on long docs —
   coordinate with 785's profiling so the two lanes don't measure past each other).
4. Ship default-OFF with the measured case; the defaults flip is a founder decision with
   register re-pins, per the now-established F-041/775 pattern.

## §C. Acceptance

- Legal splade-mode row moves into the offline-predicted band (0.3–0.55) on a fresh build,
  flag-on; hybrid-mode contribution measured (fusion may need no change — CC already
  weights splade 0.2).
- No short-doc regression (controls flat within the cohort envelope).
- Enrichment throughput delta quantified and judged acceptable (or windowed/bounded).
- Language-agnostic invariant untouched (the multilingual SPLADE model stack stays the
  only sparse authority; no per-language artifacts).
- Register: F-033 updated engine-integrated; Q-017 closed with citation.

## §D. Notes

- Storage growth is the known risk (sparse vectors × chunks); measure index size delta and
  state it — do not silently accept unbounded growth (a cap/pruning policy is in scope if
  the delta is material).
