# 717 — Intermittent fresh-build chunk-death: a fresh --clean ingest sometimes silently ships an index with no chunk_merge leg

- **status:** seed — takeover pending (chartered 2026-07-11 from the 712/713 measurement work;
  no investigation performed yet)
- **created:** 2026-07-11

## Charter question

Why does a fresh `--clean` full-pipeline ingest of the same corpus **sometimes** produce a live
index whose entire chunk sub-system is absent — every mode missing the `chunk_merge` leg, dense
retrieval scoring ~0.34 instead of ~0.62 — with no error, no failed status, no OOM? What makes
it intermittent, and what makes it silent?

## Evidence (measured, this session — the reason this is chartered, not a hunch)

The anomaly was hit repeatedly while measuring unrelated tempdocs, on the **shipped default
path** (late-chunking on, RMW preservation #139 present):

| run | fresh build | outcome | vector nDCG@10 | chunk_merge leg |
|---|---|---|---|---|
| 713 control (first arm) | yes | **degenerate** | 0.3403 | absent |
| 713 §M-5 probe | yes | healthy | 0.6185 | present |
| 712 A/B-1 OFF arm | yes | **degenerate** | 0.3403 | absent (all modes) |
| 712 A/B-2 both arms | yes | healthy (first try) | 0.6187 / 0.6184 | present |

So: **not deterministic** (M-5 refuted "always dead"), **not never** (two independent degenerate
hits). The degenerate state is a strict quality halving that ships silently — the index reports
COMPLETED, gates that don't pin the chunk leg pass, and only a per-mode leg inspection or a
vector-nDCG cliff reveals it. Distinct from F-032 (which was a *deterministic* RMW destruction,
fixed in #139); this is a *timing/nondeterminism* survivor of that class or a separate one.

## Suspected mechanism (for the takeover to confirm or refute — do not assume)

The 712 A/B observations logged adjacent shard entries that are candidate roots:
- Combined-pass parent lane stamps parent `EMBEDDING_STATUS`/`NER_STATUS=COMPLETED` onto chunk
  docs picked up via the splade-status query (`CombinedEnrichmentBackfillOps.java:330`) — a chunk
  doc can be marked parent-COMPLETED before its chunk vector is durably written.
- A chunk doc pending both chunk_embedding and splade sits in BOTH combined-pass caches and can
  be popped twice into one batch (`CombinedEnrichmentBackfillOps.java:199-260`) — double-embed /
  race on the same doc's write.
- The `--pipeline` enrichment-complete wait may return before chunk vectors are committed/merged
  under some interleaving (the "COMPLETED without data" family).

## Cheapest evidence (the takeover's first question)

Loop a fresh `--clean` legal-clerc build N times (say 10) capturing, per run: the vector-mode
legs, a read-only Lucene chunk-vector count (711-style probe), and the combined-pass worker
counters — to (a) measure the hit rate, (b) correlate degenerate runs with a specific counter/log
signature, (c) determine whether a `--pipeline` wait-condition tightening or a
readiness-gate on the chunk leg makes it disappear. A cheap same-corpus repeat harness; no code
change needed to gather the rate.

## Why it matters / scope

This silently halves retrieval quality on chunked-corpus deployments some fraction of the time,
and every fresh-build measurement in the 691/711/712/713 arc is now suspect unless its chunk leg
was health-verified. A fix likely belongs to the F-032 lineage
(`CombinedEnrichmentBackfillOps` / the `--pipeline` readiness contract), but the diagnosis must
come first (`audit-without-test`: a regression test that reproduces the degenerate index is the
bar before any fix).

## Relations

- tempdoc 711 (F-032 RMW preservation — the deterministic sibling, fixed), 712 (§Step-4 A/B where
  it recurred), 713 (§M-5 where it was first quarantined), 691 (late-chunking / combined pass).
- Register: F-032, F-035, F-036; the health-gate convention (`chunk_merge` in vector legs) that
  712/713 adopted is the interim guard until this is fixed.
