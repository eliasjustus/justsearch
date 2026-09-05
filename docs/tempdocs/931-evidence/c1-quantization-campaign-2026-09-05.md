# 931 evidence: lane D PR-C1 int8 quantization campaign (2026-09-05)

Sidecar of `docs/tempdocs/931-wave3-lane-d-hardening-lane-e-landing-and-record-repair.md`
(§D entry "C1 campaign", §E item 1). Pre-registered rule: tempdoc 915 §P3.F
(`915-evidence/appendix-a-preimplementation-passes.md` "ACCEPT int8_sq by default iff") and
FW-008 in the search-quality register.

## Setup

- Branch: `worktree-wave3-c1` at `a71f1cd8d` (draft #662 = C1 alone on top of `main` after #660),
  merged with `origin/main` the same day. Backend started by jseval (`--start-backend`) from the
  worktree on port 33221; arm selected by `JUSTSEARCH_INDEX_VECTOR_QUANTIZATION_ENABLED`.
- Arms: A0 = float32 (flag `false`), A1 = int8 (flag `true`); every arm a fresh index
  (`--clean --fresh-index`), `--pipeline` readiness, `--settle-index` (expunge-only at the time;
  the full force-merge landed later in #685), same machine (RTX 4070), same day, one arm at a time.
- Command per arm: `python -m jseval --json run --dataset <ds> --modes <modes> --pipeline
  --start-backend --clean --fresh-index --embedding --settle-index --output-dir <dir>`.
- Driver: `tmp/c1-campaign.cmd` (quality arms) and `tmp/c1-bench2.cmd` (bench arms) in the
  `wave3-c1` worktree; evaluator `tmp/evaluate-c1-campaign.cjs`; fixture generator
  `tmp/make-vector-fixture.cjs` + `tmp/append-sentinel.cjs` (20,000 clustered unit vectors, dim 768,
  64 centroids, 200 queries, exact brute-force top-50 truth, deterministic seeds).
- Arm identity proven from each run's `manifest.status_snapshot.worker.vectorFormat`
  (`vectorFormatActual`, float32 / quantized segment counts) and the bench log
  (`index.vector.quantization.enabled=true`).

## Quality arms (jseval, 300 / 300 / 200 queries)

| arm/dataset | mode | nDCG@10 | R@10 | P@1 | p50 ms | errors | settled | deleted after settle | vector format (f32/q segs) |
|---|---|---|---|---|---|---|---|---|---|
| A0/scifact | hybrid | 0.7605 | 0.8976 | 0.630 | 5.0 | 0 | true | 181 | FLOAT32 (12/0) |
| A0/scifact | vector | 0.7344 | 0.8626 | 0.607 | 2.0 | 0 | true | 181 | FLOAT32 (12/0) |
| A0/enron-qa | hybrid | 0.7985 | 0.9133 | 0.670 | 10.0 | 0 | true | 3400 | FLOAT32 (8/0) |
| A0/enron-qa | vector | 0.5845 | 0.7667 | 0.407 | 2.0 | 0 | true | 3400 | FLOAT32 (8/0) |
| A0/legal-clerc-200 | hybrid | 0.5814 | 0.8250 | 0.350 | 17.0 | 0 | true | 0 | FLOAT32 (12/0) |
| A1/scifact | hybrid | 0.7597 | 0.8942 | 0.630 | 5.0 | 0 | true | 1044 | INT8_SQ (0/10) |
| A1/scifact | vector | 0.7329 | 0.8592 | 0.607 | 2.0 | 0 | true | 1044 | INT8_SQ (0/10) |
| A1/enron-qa | hybrid | 0.7985 | 0.9133 | 0.670 | 10.0 | 0 | true | 6066 | INT8_SQ (0/8) |
| A1/enron-qa | vector | 0.5934 | 0.7767 | 0.417 | 2.0 | 0 | true | 6066 | INT8_SQ (0/8) |
| A1/legal-clerc-200 | hybrid | 0.5809 | 0.8250 | 0.350 | 17.0 | 0 | true | 224 | INT8_SQ (0/13) |

The A0 arms reproduce the 832 scorecard (scifact 0.757, enron 0.796, legal 0.578) to within
the usual run noise, so the control is sound.

| dataset | mode | ΔnDCG@10 (A1−A0) | ΔR@10 | rule (≥ −0.010 both) |
|---|---|---|---|---|
| scifact | hybrid | −0.0008 | −0.0033 | PASS |
| scifact | vector | −0.0015 | −0.0033 | PASS |
| enron-qa | hybrid | −0.0000 | 0.0000 | PASS |
| enron-qa | vector | +0.0089 | +0.0100 | PASS |
| legal-clerc-200 (report-only) | hybrid | −0.0005 | 0.0000 | — |

Caveat: the arms did not share a merge state (deleted docs 181 vs 1044 on scifact, 3400 vs 6066 on
enron) because the expunge-only settle leaves segments under Lucene's 10 % deleted-fraction
threshold untouched; #685 switches `--settle-index` to a full force-merge for future pairs. The
deltas above are an order of magnitude inside the tolerance, so the caveat does not change the
quality verdict.

## ANN benchmark (`EngineVectorIndexBench`, 20,000 × 768, k = 50, 200 truth queries)

| arm | recall@50 | index bytes | vector bytes (raw) | time-to-searchable ms | docs/s | query p50 ms | query p95 ms |
|---|---|---|---|---|---|---|---|
| A0 float32 | 0.9940 | 62,775,843 | 61,443,072 | 6,043 | 3,310 | 3.19 | 4.20 |
| A1 int8 | 0.9740 | 78,523,466 | 61,443,072 | 6,159 | 3,247 | 3.86 | 5.83 |

- recall ratio A1/A0 = 0.9799 (915 rule: ≥ 0.97 → PASS; FW-008 wording "no more than 0.01
  absolute below" → 0.020 below → FAIL).
- `index_size_bytes` rose 25.1 % instead of falling → FAIL under every pre-registered wording.
  Cause: `JustSearchCodecV2` uses `Lucene104HnswScalarQuantizedVectorsFormat`, which keeps the
  raw float32 vectors (`.vec`, 61.4 MB here) next to the int8 copy (~15.4 MB) for re-quantization
  on merge. Int8 SQ therefore cannot shrink the on-disk index with this format by construction;
  its possible benefit is search-time resident memory, which the campaign did not measure (no
  RSS instrument in the bench).
- Query latency did not improve either (p50 +0.67 ms, p95 +1.6 ms) on this in-process fixture.
- RSS: not reported (FW-008 asked for it; the bench has no such metric).

## Quantization gate (`VectorQuantizationGate`, 20,000 × 768, k = 50)

All four modes ok (float/quant × compound on/off, 50/50 hits, `has_vemq` only on the
quantized non-compound arm): the codec is restart- and merge-safe. Not a quality instrument.

## Verdict

**Do not ship int8 as the write default.** Two of the four pre-registered legs miss (index bytes
up 25 %; recall@50 0.020 absolute below float32 under the FW-008 wording), and 915 §P3.F says any
single miss means the flip does not ship and the finding is recorded against FW-008. Ranking
quality on the registered corpora is unaffected within noise, which is recorded as the useful
half of the result. Draft #662 closed with this evidence; Float32 stays the default.

## Artifacts

Copied out of the worktree before its removal to
`F:\justsearch-public\tmp\c1-campaign-2026-09-05\` (jseval `summary.json` / `manifest.json` per
arm, bench `result.json` per arm, gate `result.json` + `summary.md`, driver scripts, evaluator,
fixture generator). The 145 MB fixture is regenerable from the generator with its fixed seeds.
