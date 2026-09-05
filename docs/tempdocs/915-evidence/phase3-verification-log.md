# 915 — Phase 3 local verification and deferred-evidence sections (§P3.C, §P3.E, §P3.F)

Split verbatim from `docs/tempdocs/915-lane-d-index-fingerprint-identity-and-reindex-bundle.md` (size-cap split, 930 §19.3 F4).

### §P3.C PR-C0 local verification (2026-09-03)

Focused Java tests cover common-term skipping, discriminative-term retention, tiny-corpus behavior,
dense-only and direct-RAG recall, truthful traces, field-local QPP denominators, retired entity-query
behavior, configuration defaults/clamping, and the zeroed wire/status tombstone. Focused UI tests
cover exact reason wording and fixture compatibility. The language-agnostic-analysis,
search-degradation-reason-code, ADR-coverage, and config-surface gates pass; the generated runtime
configuration matrix remains exactly `yaml_keys=111`, `env_sysprop_pairs=250`, `config_keys=56`.

The six-corpus evaluation and hour-long benchmarks were not run. PR-C0 may be reviewed and stacked
upon locally, but it must not merge until the six-corpus acceptance evidence is recorded here.


### §P3.E PR-C2 local verification and deferred evidence (2026-09-03)

Before changing the storage flag, the adversarial offset-law test passed against stored chunk text.
After the catalog flip, the same CRLF/non-BMP/fence case passed through parent-slice synthesis. The
focused adapter tests additionally prove indexed-but-not-stored physical shape, exact BM25 output,
sibling batching with one parent read, generic projection behavior, malformed-geometry omission,
and single/batch/path RMW posting preservation. Focused worker tests cover embedding, SPLADE, BGE-M3,
combined enrichment, raw-only NER writes, and raw-field evidence selection. The chunk-regeneration
and status-schema contracts also pass.

The affected short-suite expansion passed for `configuration`, `indexing`, `adapters-lucene`, and
`app-api`. Its first pass usefully exposed higher-level fixtures that still created independently
stored chunk text without parent-backed offsets; those fixtures were converted to the production
parent-slice shape. The final full rerun passed **1,166 worker-services tests** (0 failures, 2
skipped) and **357 indexer-worker tests** (0 failures, 12 skipped). The focused adapter contract set
passed 104 tests. `ssotValidate` and `ssot-tools:test` pass; generated field constants and skill
projections are current; SSOT catalog sync, ADR coverage, language-agnostic analysis, canonical-link,
LLM-index, Markdown, tempdoc-number, and pre-merge-table checks pass.

The hour-scale storage/read-cost benchmark was deliberately not run in this work window. No storage
or latency reduction is claimed yet, and PR-C2 must not merge until that pre-registered measurement
is run and recorded. PR-C1 is described separately in §P3.F; its implementation stays on draft PR #648
until its quality campaign runs (split from the C2 PR on 2026-09-05, tempdoc 931).


### §P3.F PR-C1 implementation, focused verification, and deferred evidence (2026-09-03)

`JustSearchCodecV2` is a new Lucene SPI name whose vector format is always a
`PerFieldKnnVectorsFormat`. New segments therefore persist the concrete vector format name and
suffix needed to reopen them after a restart. The no-arg V2 writer selects unsigned-byte scalar
quantization; an explicit `index.vector.quantization.enabled=false` selects Float32. Both use the
fingerprint-owned HNSW defaults 16/200. The old `JustSearchCodec` SPI remains registered and keeps
its no-arg Float32 reader so legacy Float32 segments remain readable. A legacy segment written as
Int8 under the old outer-only name cannot be reconstructed generically and requires rebuild.

The codec regression suite writes more than 100 documents per segment to both `vector` and
`chunk_vector`, closes and reopens through SPI, performs real KNN queries, and checks persisted
per-field format metadata. It covers V2 Float32, V2 Int8, mixed V2 segments consolidated by a
force-merge, and a legacy Float32 → V2 Int8 upgrade/merge. `VectorFormatDetector` reads through the
actual soft-deletion wrapper, requires complete and consistent known per-field format metadata, and
fails closed on partial, unknown, or conflicting segment evidence. It uses commit metadata only when
there are no vector-bearing segments; a text-only index can report the configured overall policy but
still reports zero vector-segment counts.

Both dense fields declare `dot_product` in the canonical and packaged field catalogs. `FieldMapper`
constructs an explicit Lucene `FieldType`; BGE-M3 output plus the Worker query boundary and final
Lucene indexing/query boundaries normalize dense vectors and reject zero/non-finite inputs. The
physical-projection test, rather than the analyzer-only `SsotValidatorFingerprintTest`, proves a
similarity change moves `index_fingerprint`. Candidate low-signal and arbitration thresholds are
`0.40` and `0.50` in DOT_PRODUCT score space.

The independent refute-first review found and drove fixes for the soft-deletion detector boundary,
fail-closed classification tests, text-only observability, benchmark artifact configuration drift,
benchmark sentinel normalization, effective-default commit metadata/fingerprint coverage, stale
canonical codec prose, and an RMW fixture that could otherwise hide normalization. After those fixes,
the full affected suite passed: **283 configuration tests**, **670 adapters-lucene tests**, **304
worker-core tests** (16 skipped), **1,170 worker-services tests** (2 skipped), **108 benchmark tests**,
and **17 ssot-tools tests**, all with zero failures or errors. `ssotValidate` also passed for five
artifacts and four golden intents. The final repository-wide `build -x test` check passed, as did the
LLM index, skill projection, canonical-link, module-boundary, runtime-configuration matrix, canonical
Markdown, tempdoc Markdown, ADR coverage, SSOT catalog sync, locale-invariance, tempdoc-number, and
pre-merge-table checks. This is implementation evidence, not quality acceptance.

The short-run evidence pointers are the successful command outputs from:

- `.\gradlew.bat :modules:configuration:test :modules:adapters-lucene:test
  :modules:worker-core:test :modules:worker-services:test :modules:benchmarks:test
  :modules:ssot-tools:test ssotValidate`; fresh JUnit XML is under each affected module's
  `build/test-results/test/` directory.
- `.\gradlew.bat build -x test`.
- `node scripts/docs/llmstxt-generate.mjs --check`,
  `node scripts/docs/skills-sync.mjs --check`,
  `node scripts/docs/verify-canonical-doc-links.mjs`,
  `node scripts/architecture/module-deps.mjs --check-canonical`, and
  `node scripts/docs/verify-runtime-config-matrix.mjs`.
- `npm run lint:md` and
  `npx markdownlint docs/tempdocs/915-lane-d-index-fingerprint-identity-and-reindex-bundle.md`.
- `node scripts/governance/run.mjs --gate adr-coverage --mode gate`,
  `node scripts/governance/run.mjs --gate ssot-catalog-sync --mode gate`,
  `node scripts/ci/check-language-agnostic-analysis.mjs`,
  `node scripts/ci/check-tempdoc-numbers.mjs`, and
  `node scripts/ci/check-premerge-table.mjs`.

The deferred campaign remains a merge prerequisite and must use jseval where applicable:

1. Compare Float32 and Int8 on scifact and Enron in hybrid and vector modes; require absolute
   nDCG@10 and R@10 deltas ≤ 0.010, zero query errors, ANN proof, complete chunk coverage, and the
   same corpus/query/config identity.
2. Run `EngineVectorIndexBench` with recall@50 ≥ 97% for Float32 and Int8 no more than one absolute
   point below it; report index bytes and process RSS before/after.
3. Confirm the `0.40` / `0.50` similarity-space candidates on scifact and the legal corpus in hybrid
   mode under the same comparability rules.
4. Run `SINGLE_BIT_QUERY_NIBBLE` on `chunk_vector` as report-only evidence; it cannot become a
   default from this campaign.

A clean pass is expected to take roughly 3–4 machine-hours and 4–6 hours if reruns are needed. No
quality, recall, storage, RSS, or latency improvement is claimed until those artifacts are recorded.

