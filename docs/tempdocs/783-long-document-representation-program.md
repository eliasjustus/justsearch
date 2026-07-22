---
title: "long-document representation program — instrument, then attack, the retrieval floor that survives everything else (F-040 successor)"
type: tempdocs
status: "chartered (2026-07-22). Theorize → research → design arc REQUIRED before any engine code; the first buildable is the instrument (§B.1), not an intervention."
created: 2026-07-22
author: agent (Fable orchestration), chartered from the 2026-07-22 remaining-work map (founder-directed)
category: search-engine / retrieval-representation
related:
  - 774-passage-first-retrieval-program  # predecessor; Stage 3 killed by probe — its ambition transfers here with better instruments
  - 775-evidence-span-authority          # the delivery half; this is the representation half
  - 748 (de-miracl 10k collapse)         # the multilingual face of the same scale problem
  - 704 (measurement program Pillar 1)   # difficulty-successor lineage
---

## §A. Problem

The engine's deepest measured weakness is scale-dependent context starvation on long
documents (register F-040): legal-10k union recall sits at 0.08–0.14 while enron holds
0.44–0.49; bridge sentences at median offset ~5,000 chars are beyond what the indexed
representation makes reachable. Two facts pin the diagnosis: (a) Variant C (doc-lead
prepend) recovered 2×+ recall at 10k — so the *information* is recoverable, the floor is
representation policy, not an encoder ceiling; (b) Variant C failed the real-task control —
so naive global context injection is not the answer. Everything shipped this arc (F-041
preview, EvidenceSpan, flips) improved what happens AFTER retrieval finds a document; this
program is about the documents retrieval never surfaces at all.

## §B. Program shape (instrument-first — the lesson this program keeps re-learning)

1. **Per-offset recall instrumentation (build FIRST, before any intervention):** a standing
   diagnostic that answers "at what character offset does gold evidence stop being
   retrievable, per corpus, per leg?" — recall curves binned by gold-evidence offset,
   emitted from existing rank-of-gold capture (768) + chunk provenance
   (`chunk_start_char`). Every intervention below is judged against the curve it moves,
   not an aggregate nDCG alone. Home: jseval; runs on the 781 rebuilt corpora and the real
   catalog (legal-clerc-200).
2. **Intervention space to theorize (NOT commitments — the design pass ranks them):**
   hierarchical coarse-to-fine retrieval (doc-level candidate set → passage-level scoring)
   as a first-class pipeline stage rather than chunk-merge's bolt-on; late-interaction
   scoring (ColBERT-class) for long docs; windowed-representation policy changes (window
   count/stride/pooling for docs past the single-pass boundary, extending F-031); passage-
   first indexing (774 Stage 3's design, re-derisked with §B.1's instrument this time);
   query-side decomposition for bridge-entity queries (F-039 showed the join key is
   definitional — the designer-name oracle ranks 1–2 everywhere, meaning two-step retrieval
   already works when the join key is known).
3. **Constraint inheritance:** D-005 (reacts to query + content, never corpus identity);
   language-agnostic analysis (ADR-0043) — no per-language anything; F-016 weak prior —
   capability lives in selection/representation, not new required parameters; perf ratchets
   (640) hold at shipped defaults.

## §C. Acceptance (program-level; each slice pins its own)

- The instrument exists, is cheap to run, and its curves are in the register as the standing
  characterization of the floor (supersedes the current single-number F-040 citation).
- At least one intervention moves legal-10k union recall out of the 0.08–0.14 band on
  rebuilt (leak-free) corpora WITHOUT regressing the real-task control class that killed
  Variant C, and without short-doc regression (scifact/enron flat within envelope).
- Honest negative outcomes are register findings too — this program's value is knowing
  where the floor actually is, not shipping any particular fix.

## §D. Notes

- legal-10k stays EXCLUDED from hero claims (782) regardless of this program's progress;
  un-excluding it is a founder decision gated on this program's results.
- The 748 German collapse is the multilingual face of the same problem — 748 stays its own
  lane but should consume §B.1's instrument rather than building a second one.
