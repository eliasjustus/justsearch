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
### §B.1b Evidence-offset sidecar carried through materialization (2026-07-27)

**The gap.** `corpus_inject.build_source` writes an `evidence_offsets.json` sidecar recording
where each injected gold sentence lands in its assembled host doc
(`scripts/jseval/jseval/corpus_inject.py:469` writer, `:660` write site) — but it wrote it into
the *injection source* dir, which `corpus-inject-real` creates as a `tempfile.TemporaryDirectory`
and destroys when the command returns (`scripts/jseval/jseval/commands/corpus.py:202-211`).
`corpus_build.build_golden` never carried it forward, so **no materialized dataset dir ever had
one** — verified on the campaign's live cells (`tmp/781-v2-datasets/mixed/*` held only
`corpus-dir/ corpus.jsonl invocations.v1.jsonl metadata.json qrels/ queries.json`). Consequence:
the PRIMARY (generator-metadata) resolution tier of `jseval offset-recall` was exercised by
fixtures only, and the F-040 curve was not obtainable from a real run.

Measured, not assumed: on a freshly materialized `en-legal-clerc-1k-verbose` with the sidecar
removed, `offset-recall` reports `resolved=0/50 unresolved=50 (metadata=0 string=0)` — the
string-location fallback resolves *nothing* on these cells (the gold answers are not verbatim in
the first-hop doc), so pre-fix there was no curve at all on a 781 v2 cell, not merely a degraded
one.

**The fix.** `corpus_build.build_golden` copies the source's `evidence_offsets.json` into the
materialized dataset dir verbatim (`scripts/jseval/jseval/corpus_build.py:135-146`). The
**writer** side was adjusted, not the loader: `offset_recall.load_corpus` already looks for
`<corpus_dir>/evidence_offsets.json` (`offset_recall.py:277-283`) and reads doc text from the
same `text` field `corpus.jsonl` reproduces byte-for-byte, so the two agreed on name and location
already — the only missing link was the file. Additive and inert for non-injected corpora (a real
benchmark source has no sidecar; the copy is skipped).

**Signature invariance — empirical, not just by reading.** `corpus_signature` is
`sha256(corpus.jsonl bytes + qrels/test.tsv bytes)` (`corpus_identity.py:20-27` — exactly those
two files, never a directory listing). Re-materialized `781-corpora/en-legal-clerc/1000-verbose/`
from its committed recipe + fabricated gold into a scratch dir (host pool read-only; no committed
bytes, `datasets/`, or `tmp/781-v2-datasets/` touched):

```
fresh corpus_signature (sidecar PRESENT) : 6df707031abcd296773a0bf8c6a7750bb0b8704ce4ab4035105cf88b8df01fae
committed corpus_signature (781 cert)    : 6df707031abcd296773a0bf8c6a7750bb0b8704ce4ab4035105cf88b8df01fae
signature WITHOUT sidecar on disk        : 6df707031abcd296773a0bf8c6a7750bb0b8704ce4ab4035105cf88b8df01fae
query_gold_sha256 / assembled_digest     : MATCH committed (2797469a…f565 / a303e74a…d167)
```

So tempdoc 781's committed certification is untouched by the sidecar's presence: same signature
with it, without it, and equal to the certified value.

**Metadata tier resolving on a REAL materialized cell** (synthetic run dir over the fresh cell —
the recall numbers are not a result, the tier resolution is):

```
Offset-recall (schema=offset-recall.v1, k=10)
  resolved=50/50 unresolved=0 (metadata=50 string=0) no_gold=0
```

**Consequence.** Metadata-resolved offset-recall curves on the 781 v2 cells are now obtainable
from a GPU eval run: materialize (or re-materialize) a cell, run the eval, then point
`jseval offset-recall <run_dir> --corpus-dir <cell>` at it. Cells materialized *before* this
change lack the sidecar and must be re-materialized (signature-stable, as proven above) to get
the primary tier. Regression coverage:
`tests/test_corpus_inject.py::test_evidence_offset_sidecar_survives_materialization_and_drives_metadata_tier`
(carry-forward + signature invariance + loader + metadata-beats-string) and
`::test_build_golden_without_sidecar_writes_none`.

### §B.1c First PRIMARY (metadata-resolved) offset-recall curves on real 781 v2 cells (2026-07-28)

§B.1b's fix paid off: three 781 v2 cells were re-materialized and evaluated, and
`jseval offset-recall` resolved **50/50 via the metadata tier on every cell**
(`by_source: {metadata: 50, string_match: 0, query_locus: 0}`, `curves_are_proxy: false`,
`schema offset-recall.v2`, `k=10`). These are the **first non-proxy F-040 curves** — the
primary evidence §B.1a said was missing.

Artifacts (`offset_recall.json`, all mtime 2026-07-28 07:42):
`tmp/781-certification/c1-en-legal-clerc-1k-verbose/`,
`tmp/781-certification/c1-en-legal-clerc-10k-verbose/`,
`tmp/781-certification/c1-en-email-enron-raw-10k-verbose/`.

**The axis is offset-into-document, not retrieval depth.** Bins are the character offset
of the injected gold sentence within its assembled host doc
(`0-1k / 1k-2k / 2k-4k / 4k-8k / 8k+`). Anything calling these "depth curves" means offset
depth; they say nothing about result-list rank cutoffs.

#### Recall@10 by offset bin (bin `n` in parentheses; `--` = empty bin)

| cell | leg | 0-1k | 1k-2k | 2k-4k | 4k-8k | 8k+ |
|---|---|---|---|---|---|---|
| legal-1k-verbose | **hybrid** | **0.571** (7) | 0.500 (2) | 0.727 (11) | 0.500 (14) | **0.438** (16) |
| legal-1k-verbose | vector | 0.571 (7) | 0.500 (2) | 0.455 (11) | 0.571 (14) | 0.313 (16) |
| legal-1k-verbose | splade | 0.857 (7) | 0.000 (2) | 0.000 (11) | 0.000 (14) | 0.000 (16) |
| legal-1k-verbose | lexical | 0.000 (7) | 0.000 (2) | 0.000 (11) | 0.000 (14) | 0.000 (16) |
| legal-10k-verbose | **hybrid** | 0.286 (7) | 0.500 (2) | 0.182 (11) | 0.286 (14) | 0.125 (16) |
| legal-10k-verbose | vector | 0.143 (7) | 0.000 (2) | 0.091 (11) | 0.286 (14) | 0.063 (16) |
| legal-10k-verbose | splade | 0.143 (7) | 0.000 (2) | 0.000 (11) | 0.000 (14) | 0.000 (16) |
| legal-10k-verbose | lexical | 0.000 (7) | 0.000 (2) | 0.000 (11) | 0.000 (14) | 0.000 (16) |
| enron-10k-verbose | **hybrid** | **0.636** (33) | 0.625 (8) | 0.400 (5) | **0.250** (4) | -- (0) |
| enron-10k-verbose | vector | 0.576 (33) | 0.500 (8) | 0.200 (5) | 0.250 (4) | -- (0) |
| enron-10k-verbose | splade | 0.091 (33) | 0.000 (8) | 0.000 (5) | 0.000 (4) | -- (0) |
| enron-10k-verbose | lexical | 0.000 (33) | 0.000 (8) | 0.000 (5) | 0.000 (4) | -- (0) |

#### What the curves actually say (read carefully — one of them is not a decay)

1. **enron-10k is a clean, monotonic offset decay: 0.636 → 0.625 → 0.400 → 0.250.** Every
   step is downward. This is the cleanest support the program has that *offset within the
   document* is a real retrieval axis. Caveat: its deepest populated bin is `4k-8k` (the
   `8k+` bin is empty — emails are short), so this decay is measured over a *shallower*
   offset range than legal's, and its tail bins are thin (n=5, n=4).

2. **legal-1k is NOT monotonic: 0.571 → 0.500 → 0.727 → 0.500 → 0.438.** The `2k-4k` bin
   is the *highest* point on the curve, not a way-station on a decline. The headline
   "0.571 → 0.438" is a first-bin-vs-last-bin comparison, and quoting it as a decay curve
   overstates what the data shows. With bin `n` of 7/2/11/14/16, single-query moves swing
   a bin by 0.06-0.50 (`1k-2k` is two queries). **Do not cite legal-1k as evidence of a
   monotonic offset effect.**

3. **legal-10k is also non-monotonic (0.286 → 0.500 → 0.182 → 0.286 → 0.125) and, more
   importantly, uniformly depressed.** Comparing it against legal-1k bin-for-bin, recall
   falls at *every* offset bin (0.571→0.286, 0.727→0.182, 0.500→0.286, 0.438→0.125) —
   roughly halving throughout. **On legal, the 10× corpus-size floor dominates the offset
   axis rather than interacting with it.** That is consistent with F-040's "representational
   at every granularity" verdict and is the more decision-relevant signal in this table than
   any within-cell offset trend.

4. **The lexical leg is 0.000 in every bin of every cell** — 15 of 15 populated bins. The
   camouflage rebuild (767/781) removed the lexical leg entirely on these strata, exactly
   as pre-registered. **SPLADE is near-dead too**, non-zero only in the shallowest bin
   (legal-1k 0.857, legal-10k 0.143, enron 0.091) and 0.000 everywhere deeper — the
   512-token truncation signature of F-033, now visible as an offset curve. Hybrid on these
   cells is effectively *vector + CE*, and hybrid tracks vector's shape throughout.

#### Status against §C

This delivers the first half of §C acceptance item 1 (the instrument's curves exist, are
cheap to re-run, and are now register-recorded — see the F-040 annotation). It does **not**
deliver item 2: no intervention has been attempted, and legal-10k remains in its floor band.
The honest scope limit is bin sample size — 50 queries per cell spread across 5 bins gives
n=2 to n=16, which supports the cross-cell comparison in reading 3 far better than any
within-cell offset gradient in readings 1-2. **A larger per-cell query budget is the
prerequisite for treating within-cell offset curves as decision-grade.**

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
