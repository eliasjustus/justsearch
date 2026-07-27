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

## §B.1a. The instrument resolved on ZERO real corpora — the query-locus proxy (2026-07-27)

**The finding.** §B.1 shipped (PR #299) with two resolution sources: generator metadata
(`evidence_offsets.json` sidecar) and a fallback that string-locates the query's `answer`
inside the gold doc. The charter assumed the fallback covers real corpora. It does not —
measured 2026-07-22, run against the real legal-clerc-200 baseline the instrument reported
**resolved=0/200, every query `no_evidence_string`**: CLERC queries carry `answer: ""`
(citation-retrieval — the gold *document* is the answer, there is no answer span). Same for
`miracl-de-2k` (0/305 non-empty answers) and `miracl-fr-2k` (0/343). So before this slice
the instrument produced a curve on injected/synthetic corpora only, and on **no** real
benchmark corpus — an incomplete charter item, not a tuning gap.

**What was added (`scripts/jseval/jseval/query_locus.py`).** A third, explicitly-labeled
resolution source, ranked strictly LAST (metadata > answer-string > query_locus), reached
only for queries the two measured sources failed on:

> **query-locus proxy** — the character offset of the highest-scoring 1,000-char window of
> the gold document, scored by the summed corpus-local rarity of the DISTINCT query terms it
> contains. Rarity is BM25-style IDF computed over the corpus itself, so ubiquitous words
> demote themselves without any stopword list or per-language artifact (ADR-0043 holds).
> Deterministic, offline, no model, no network. Cost: one `O(total tokens)` document-frequency
> pass over the corpus, then `O(H)` per query (two-pointer sweep over the `H` gold-doc
> positions carrying a weighted query term). 200 queries × ~28k-char docs runs in seconds.

**Validity caveat (load-bearing — do not drop it when citing a proxy curve).** The proxy
answers *"where does the query's distinguishing content sit in the gold doc"*, which is a
**different question** from *"where does the answer sit"*. For citation-retrieval corpora the
two largely coincide by construction (the cited passage is both what the citing text quotes
around and the evidence); for factoid corpora they can diverge — query terms in the lead,
answer buried deeper. Accounting is therefore **separate and never merged**: `by_source`
counts `query_locus` on its own, every `per_query` row carries `source` +
`resolution_class`, and proxy offsets are curved into a distinct `proxy_curves` block
(`curves` stays measured-only, `curves_are_proxy: false`). The stdout table prints two
labeled sections and suffixes every proxy row's mode with `~proxy`. Schema bumped to
`offset-recall.v2`. This labeling is the control against the failure mode this program has
already been burned by once (a title-prepend probe reporting its own leak as a win).

**Measured curve — legal-clerc-200 baseline** (`20260722T194256_mixed_legal-clerc-200`,
hybrid leg, k=10, 200/200 now resolved, all via proxy):

```
  resolved=200/200 unresolved=0 (metadata=0 string=0 query_locus=200) no_gold=0
  [measured] offsets from generator metadata / answer-string location:
    (no queries resolved from this source)
  [PROXY (query_locus) … NOT where the answer sits] window_chars=1000:
  mode                bin           n   recall@k  medRank
  hybrid~proxy        0-1k         12      0.667        2
  hybrid~proxy        1k-2k         7      0.857        1
  hybrid~proxy        2k-4k        13      0.769      2.5
  hybrid~proxy        4k-8k        38      0.816        1
  hybrid~proxy        8k+         130      0.846        2
```

**What it says about F-040 on this corpus: nothing — no offset effect is visible, and that
is the honest result.** recall@10 is flat at 0.67–0.86 with no monotone decline (overall
0.825); the lowest bin is the *shallowest* one (n=12, noise-dominated), the opposite of the
F-040 shape. Three interrogations, none of which produced a gradient:

- Binning by *relative* position instead of absolute offset (quartiles of offset/doc_len,
  median 0.43 — the proxy is not degenerate, it spreads across documents rather than
  collapsing to the lead): recall 0.824 / 0.803 / 0.864 / 0.824.
- Binning by gold-doc length: `<10k` 0.750 (n=16), `10–30k` 0.856 (n=90), `30–60k` 0.815
  (n=65), `60k+` 0.793 (n=29). Flat.
- The variable that *does* predict recall here is query↔gold lexical anchoring, not offset:
  splitting at the median fraction of query rare-term weight captured by the best window
  (median only 0.129 — CLERC queries are long citing paragraphs, most of whose terms are
  absent from the cited case), recall@10 is **0.700** on the weak-anchor half vs **0.950** on
  the strong-anchor half. Bins were not retuned to chase a gradient.

Two readings are consistent with this and this slice does not settle between them: (i)
legal-clerc-200 documents at k=10 simply do not exhibit the F-040 offset cliff; (ii) the
proxy's weak anchoring on citation-retrieval queries (median 13% coverage) blurs any true
offset signal. A proxy curve is evidence about the proxy's question, and this one says the
offset axis is not where legal-clerc-200's retrieval outcome is decided.

**Primary evidence for F-040 is unchanged.** Metadata-resolved curves on the 781 v2 injected
cells — where `corpus_inject` records the exact injected-sentence offset — remain the
**primary, non-proxy** evidence for F-040. Proxy curves extend the instrument's *coverage* to
answer-span-less corpora; they do not replace, and must never be pooled with, measured
offsets. (Note: no `evidence_offsets.json` sidecar is materialized under `datasets/` in this
checkout, so the metadata path is currently exercised by fixture tests only — re-materializing
a 781 v2 cell is what turns the primary evidence back on.)

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
