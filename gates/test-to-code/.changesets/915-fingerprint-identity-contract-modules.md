---
classification: declared-regression
tempdoc: 915
---
Five modules dip below their seeded ratio because Phase 1 added production lines to
**contract-shaped** modules whose tests, by design, live where the behaviour lives. The gate counts
`src/main/**.java` against `src/test/**.java` per module; none of these five is an uncovered change,
and in every case the cheap in-module test that would restore the ratio would be padding.

| module | pin → measured | production added (gate-visible) | where the behaviour is tested |
|---|---|---|---|
| `modules/app-api` | 397 → 396 | +11: the `WORKER_INDEX_SCHEMA_MISMATCH` enum row, its javadoc, and its arm in `retentionClassOf` | `app-services` `KnowledgeServerWorkerDownCodeTest` + `SchemaMismatchFatalArcTest`, `ui` `StatusLifecycleWorkerReasonTest`, and the `check-readiness-reason-codes` gate (56 emittable / 50 worded, producer direction verified) |
| `modules/ipc-common` | 806 → 798 | +8: the `WorkerFatalReasonMarker.INDEX_SCHEMA_MISMATCH` constant and its javadoc | `app-services` `SchemaMismatchFatalArcTest` (marker → reason-code arc) and `indexer-worker` `PreOpenSchemaMismatchBootTest` (the write side) |
| `modules/indexing` | 963 → 951 | +26: `ChunkSplitter`'s two relocated constants and `ALGORITHM_VERSION` | `InvariantSuiteIT` grew **+177 lines** for exactly this, but it lives in `src/integrationTest`, which this gate does not count; also `adapters-lucene` `IndexFingerprintTest` |
| `modules/configuration` | 638 → 637 | +41: `effectiveVectorHnswM()` / `effectiveVectorHnswEfConstruction()` + `normalizeSchemaMismatchPolicy` | `ResolvedConfigBuilderTest` **in this module** (+22, a real case added for the policy fallback), plus `IndexFingerprintTest` and `PreOpenSchemaMismatchBootTest` |
| `modules/telemetry` | 733 → 732 | **−1** — this is a retirement, not an addition: `CommitMetadataSpanAttrs.KEYS` went 8 → 7, and the test row for the retired key went with it (−2) | the remaining seven keys, unchanged |

Branch-wide the movement is the other way: `adapters-lucene` +1351 test lines, `indexer-worker` +1137,
`worker-services` +411, `app-services` +359, `ui` +115 — seven modules show
`test-to-code/rebalance-available`. The five rows below are repinned to their measured values in this
same commit, per `declared-regression-without-repin`; the improved rows are deliberately **not**
raised here, since ratcheting a floor other lanes are working under is the gate's own `--rebalance`
path to run, not a side effect of this PR.
