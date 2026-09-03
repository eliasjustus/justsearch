---
title: "Lane D: one truthful index fingerprint, stable document identity, and the reindex bundle"
type: tempdocs
status: "PHASE 1 IMPLEMENTED (2026-09-03) — five parity keys replaced by index_fingerprint + boosts_fp; the Head no longer disables the parity guard; production default is BLUE_GREEN_MIGRATE with a bounded repeat-rebuild brake. Phases 2 (document identity) and 3 (reindex bundle) are PENDING."
created: 2026-09-03
updated: 2026-09-03
lane: D (decision re-examination programme, wave 2)
model: opus (implementation)
category: index-identity
coordination: "→ Lane E hands lane D chunk-size and threshold numbers; lane D owns every schema-shaped change (programme rule 4, 'one migration'). → Lanes D and E merge before one release so existing installs pay for one rebuild, carried by the blue/green default introduced here. → Lane B: ADR-0007's entity-boost amendment is a Phase 3 concern; Phase 1 does not touch the entity fields."
related:
  - 804-index-schema-mismatch-reindex-noop        # the untruthful fingerprint; the "do not fix this by enabling the guard" finding this phase supersedes
  - 912-wave1-residue-worker-watcher-and-commit-floor  # open items 1 and 2, folded in here
  - 883-decision-review-lane-a-config-and-context-budget
  - 885-decision-review-lane-c-runtime-lifecycle-and-isolation
  - 702-vector-threshold-calibration              # thresholds calibrated against the EUCLIDEAN score scale; Phase 3 recalibrates
  - 617-store-conversion
---

# Lane D: index fingerprint, document identity, and the reindex bundle

Lane D of the decision re-examination programme. The brief (`lane-D-index-identity-migration.md`,
written before wave 1 merged) is the contract; every `file:line` in it was a hypothesis, and §B
records what re-verification found. Phase 1 is implemented in this PR. Phases 2 and 3 are pending.

---

## §A Scope checklist

### Phase 1 — one truthful index fingerprint, blue/green as the production default (IN PROGRESS → implemented here)

- [x] A1. Replace the five parity keys with two: `index_fingerprint` (rebuild-requiring) and
      `boosts_fp` (benign).
- [x] A2. `index_fingerprint` = SHA-256 over canonical JSON of the *effective* index shape (exact
      input list in §C.1).
- [x] A3. Exclude everything that does not change what is on disk (boosts, k1/b, `ef_search`,
      `rmwPolicy` annotations, the intent grammar).
- [x] A4. Delete the intent-grammar coupling: `SsotCommitMetadataSource` no longer sources the
      index's identity from `SSOT/versions/catalog.json`. (Deviation recorded in §C.6.)
- [x] A5. Sweep every consumer of `schema_ver` / `schema_fp` / `index_schema_fp` / `analyzer_fp`
      from commit user data or `state.json` (full list in §B.3).
- [x] A6. Delete both `allow_mismatch=true` sites in `HeadlessApp`; keep the key as an explicit
      operator escape only.
- [x] A7. Policy default: prod `BLUE_GREEN_MIGRATE`, dev `REBUILD_BACKUP_FIRST`.
- [x] A8. Blue/green triggers on `index_fingerprint` mismatch, not only on the embedding sha.
- [x] A9. Rate-limit repeat rebuilds (state, key, bound) so a corrupt index cannot loop.
- [x] A10. Green verification compares the new key.
- [x] A11. Tests (a) fingerprint-mismatch migration, (b) same under `FAIL_CLOSED`, (c) corrupt
      `state.json`, (d) repeat-rebuild rate limit, (e) `boosts_fp` alone does not trigger,
      (f) commit-reason attribution for cutover and switch-buffer replay.
- [x] A12. Update `docs/explanation/11-index-schema-migration.md`, `04-storage-engine.md`,
      `18-adapters-lucene-deep-dive.md`; regenerate the docs indexes.
- [x] A13. Retire with a sweep: every `schema_ver` / `index_schema_fp` / `analyzer_fp` /
      `allow_mismatch` / `intent_v1.schema_ver` hit across code, config, gates, baselines, docs,
      jseval and ui-web deleted or relabelled.

### Wave-1 fold-ins (Phase 1)

- [x] W1. Tempdoc 912 item 1 — `CommitOps.commitWithBuildState` recorded the blue/green cutover
      commit as `UNKNOWN`. Added `CommitReason.MIGRATION_CUTOVER` and made the reason a required
      parameter.
- [x] W2. Tempdoc 912 item 2 — `KnowledgeServerMigrationOps` called the low-level
      `CommitOps.commit()`, bypassing the funnel. Routed through `commitAndTrack` with
      `CommitReason.SWITCH_BUFFER_REPLAY`, and made the bypass a compile error rather than an
      allowlist entry (the primitive is package-private now).
- [x] W3. Tempdoc 884 cross-lane request — `docs/reference/architectural-risks.md` RISK-011's
      instrument moved from `none - lane D has no tempdoc yet` to `tempdoc:915#C Design (Phase 1),
      tightened`.

### Phase 2 — stable document identity (PENDING)

- [ ] B1. Mint `doc_uid` once per logical document; preserve across rename, re-extraction, full
      reindex.
- [ ] B2. Persist the path→uid map in SQLite next to the existing path store (ADR-0028:
      hash-keyed reverse lookups); decide and document which file.
- [ ] B3. Deterministic chunk uids: `uid + "#" + chunkIndex`.
- [ ] B4. `IndexingDocumentOps`, `ChunkDocumentWriter`, `WritePathOps.updateDocumentPaths` and the
      migration enumerator use the store.
- [ ] B5. Feedback/GPL stores key on `doc_uid` with a one-time backfill from path
      (`LabelProjection`, `FeatureSnapshot`, `AgentDispositionWiring`, `GplTrainingTripleStore`).
- [ ] B6. Tests: rename keeps uid; delete-and-reindex keeps uid; same content → different uids;
      the label store survives a full rebuild.

### Phase 3 — the reindex bundle, one migration for users (PENDING)

- [ ] C1. Quantized vectors by default, with jseval nDCG@10 / recall@50 evidence (delta ≤ 1%
      absolute), index size and RSS before/after; binary-quantized HNSW on `chunk_vector` as a
      report-only experiment.
- [ ] C2. Pin vector similarity: add `vector.similarity: dot_product` to both catalog copies,
      construct the field with an explicit `FieldType`, add a unit-norm encoder test, recalibrate
      the 702 thresholds with jseval evidence, update `SsotValidatorFingerprintTest`.
- [ ] C3. Stop storing `chunk_content` (`stored:false`, still indexed); slice the parent `content`
      by `chunk_start_char`/`chunk_end_char`; measure the per-hit stored-field cost.
- [ ] C4. Delete the `entity_*_text` fields and the entity text-boost path; keep facets on
      `entity_*_raw`; tell lane B for ADR-0007's amendment.
- [ ] C5. Replace the English stop-word list with a document-frequency signal; verify comparable
      per-language skip rates on the multilingual eval sets.
- [ ] C6. Chunk size: take lane E's number; change nothing about chunking except that its
      parameters are already fingerprint inputs (done in Phase 1 — see §C.1).

---

## §B Pre-implementation pass

Every `file:line` claim in the brief, re-verified against this worktree at base `39d38f73`.
Verdict counts: **21 verified · 6 moved · 5 wrong · 1 superseded**.

### §B.1 "What the audit found" 1-7

| # | Claim | Verdict | Evidence |
|---|---|---|---|
| 1a | `ParityDiagnostics.java:15-29` declares rebuild-requiring keys `analyzer_fp`, `schema_ver`, `index_schema_fp` | **verified** | `ParityDiagnostics.java:15-16` (`PARITY_KEYS`), `:28-29` (`REBUILD_REQUIRING_KEYS`) — pre-change |
| 1b | `SsotCommitMetadataSource.java:68-80` sources `schema_ver` from `SSOT/versions/catalog.json → intent_v1.schema_ver` | **moved (73-79)** | `String schemaVer = versions.path("intent_v1").path("schema_ver").asText();` at `:74`, `out.put("schema_ver", schemaVer)` at `:79` |
| 1c | `index_schema_fp` is the catalog-file SHA re-hashed with the vector dimension, so any byte edit flips it | **wrong (half of it is dead code)** | `SsotCommitMetadataSource.java:85-93` — the re-hash runs only if `vectorDimensionOverride` is set, and the only setter (`:55`) had **no production caller**: `KnowledgeServer.java:1576` set it on a *locally constructed* instance inside the metadata-supplier lambda, while `IndexStatusOps.java:1099` and `KnowledgeServerMigrationOps.java:318` each built a **fresh no-arg** `SsotCommitMetadataSource` for the *expected* value. So under BGE-M3 the commit path and the two comparison paths computed different fingerprints. Latent, because the guard never fired (1d). Fixed in §D as a process-wide provider. |
| 1d | `HeadlessApp.java:265-267` and `:673-674` set `allow_mismatch=true` unconditionally | **moved (`:352`, `:783`)** | `System.setProperty("justsearch.index.parity.allow_mismatch", "true");` at both. (`docs/explanation/11-index-schema-migration.md:106` cited `:267`/`:607` — also stale.) |
| 1e | `ComponentsFactory.checkFieldSchemaCompatibility` (`:433-462`) is the only live detector | **moved (435-522)** | Method spans `:435-522`; `FieldInfos.getMergedFieldInfos(reader)` at `:453`; the throw is at `:505-513`, outside the cited range. The "only live detector" claim is corroborated by `docs/explanation/11-index-schema-migration.md:108`. |
| 2a | `normalizeSchemaMismatchPolicy` at `ResolvedConfigBuilder.java:991-1002` | **wrong** | `:991-1002` is `buildPorts()` + `clampPort()`. The method is at `:1009-1020`; call site `:1539-1541`. |
| 2b | Defaults `FAIL_CLOSED` in prod, `REBUILD_BACKUP_FIRST` in dev | **verified** | `ResolvedConfigBuilder.java:1010-1012` |
| 2c | `KnowledgeServer.java:562-596` triggers blue/green **only** on `embedding_model_sha256` mismatch | **wrong** | Two triggers exist. The embedding-fp branch moved to `:586-633`. A **second** trigger already existed: `KnowledgeServer.java:634-667` catches `IndexRuntimeIOException.Reason.SCHEMA_MISMATCH` and starts blue/green with `MigrationSource.SCHEMA_MISMATCH`. It never fired because `allow_mismatch=true` stopped the guard from ever raising it (1d) and because `RuntimeSession.java:526-561` only rethrows SCHEMA_MISMATCH under a non-`REBUILD_BACKUP_FIRST` policy. **Consequence for the design: A8 is mostly a matter of enabling the guard and flipping the default, not of writing a new trigger.** |
| 2d | `docs/explanation/11-index-schema-migration.md:85-95` reads as if blue/green were the mechanism | **verified, with a caveat** | Lines 85-95 list `BLUE_GREEN_MIGRATE` as a coequal policy with no caveat in that span; lines 102-109 ("Enforcement status (2026-08)") do disclose that the fingerprint detector is disabled. The brief's framing holds only if a reader stops at :95 — but that section is now wrong in the other direction and is rewritten in this PR. |
| 3a | `doc_id` is the absolute path (`IndexingDocumentOps.java:143-149`) | **verified** | `fields.put(SchemaFields.DOC_ID, absolutePath);` at `:144` |
| 3b | `doc_uid` is `UUID.randomUUID()` on every write (`ChunkDocumentWriter.java:132`) | **verified**, and the parent too | `ChunkDocumentWriter.java:132`; parent at `IndexingDocumentOps.java:145` |
| 3c | Rename rewrites parent plus up to 10,000 chunks (`WritePathOps.java:536-583`) | **verified** | `WritePathOps.updateDocumentPaths` spans `:536-592`; `searcher.search(chunkQuery, 10_000)` at `:569` |
| 3d | Feedback/LambdaMART/GPL key on `docId` = path | **verified** | `LabelProjection.java:64`, `FeatureSnapshot.java:25`, `GplTrainingTripleStore.java:370`, `AgentDispositionWiring.java:106`; source is `SearchTool.java:388` (`parent_doc_id`, populated with the path at `WritePathOps.java:563`) |
| 4 | `JustSearchCodec.java:28-66` — quantization demoted, no-arg constructor is float32 | **verified** | `JustSearchCodec.java:39-45`, `this(float32Format())` |
| 5a | `FieldMapper.java:422-431` uses `new KnnFloatVectorField(id, vec)` (Lucene default EUCLIDEAN) | **verified** | `FieldMapper.java:428` |
| 5b | Vectors L2-normalised by `OnnxEmbeddingEncoder:1062-1076` | **verified** | `OnnxEmbeddingEncoder.java:1062-1076`, `l2Normalize` |
| 5c | `fields.v1.json:172-197` declares `dimension` only | **verified** | `:182` and `:195`; the only two `dimension` occurrences. Root and adapters-lucene copies are byte-identical (SHA-256 `ef8291…f18aa4`). |
| 6a | `content` (`:53-62`) and `chunk_content` (`:301-308`) both `stored:true`; 62 of 67 fields stored | **verified exactly** | 67 fields, 62 with `stored:true` |
| 6b | `entity_*_text` at `:379-394`, written by `NerBackfillOps.java:216-233`, read only when `entity_boost > 0` (default `ResolvedConfigBuilder.java:1345`) | **wrong on two of three** | `:379-394` covers only `entity_persons_raw` + `entity_persons_text`; all three `_text` fields span `:379-427`. `NerBackfillOps` writes at `:211-228` (`applyEntityFieldUpdates`), not `:216-233`. `ResolvedConfigBuilder.java:1345` is `justsearch.rerank.enabled`; the entity boost default is `:1428` (`resolveDouble("justsearch.search.entity_boost", 0.0)`). |
| 7 | `HybridSearchOps.java:78-91,133-146` — ~80 English stop words gate the vector leg | **verified** | `STOP_WORDS` declared `:81-91` (82 words), `shouldSkipVectorSearch` `:133-146` |

### §B.2 "Design to implement" claims (Phases 2-3, verified now so Phase 2/3 do not re-litigate)

| # | Claim | Verdict | Evidence |
|---|---|---|---|
| D1 | `ComponentsFactory.java:179-186` `quantEnabled` default | **verified** | `:183`, `Boolean.TRUE.equals(idx.vectorQuantizationEnabled())` → false unless explicitly true |
| D2 | `TextQueryOps.java:64-66,183-220` entity text-boost path | **verified** | `ENTITY_TEXT_FIELDS` `:63-66`; `resolveEntityBoost` / `combineMultiField` `:180-221` |
| D3 | `HighlightingOps.java:63,66,278-307` reads chunk stored fields | **wrong** | `HighlightingOps.java` has **zero** references to `SchemaFields.CHUNK_CONTENT`. `:63`/`:66` read `CONTENT_PREVIEW`/`TITLE`/`CONTENT`; `:278-307` uses `CONTENT`. Chunk text arrives as a plain string parameter from the caller. **Phase 3 C3's consumer list is wrong here.** |
| D4 | `RagContextOps.java:815-822,835,1044,1422` reads `chunk_content` | **wrong** | `:815`, `:835`, `:1044` read `SchemaFields.CONTENT` (whole-doc), not `CHUNK_CONTENT`. The single real `CHUNK_CONTENT` read is at `:1418` (`excerptTextFor`), with a `CONTENT` fallback at `:1422`. |
| D5 | `ChunkSearchOps.java:334-347` chunk stored-field allowlist | **verified** | `SchemaFields.CHUNK_CONTENT` in `storedAllowlist` at `:337` |
| D6 | Path store: `SqlitePathResolutionStore` / `PathResolutionStore` | **verified** | `modules/worker-core/.../path/PathResolutionStore.java`; `modules/indexer-worker/.../queue/SqlitePathResolutionStore.java`; table `path_resolution(path_hash PK, normalized_path, last_seen_at, removed_at)` at `SqliteSchema.java:161-168`. `governance/store-recoverability.v1.json:574-606` covers it under the shared `jobs-db` row (same SQLite file), which already lists `SqlitePathResolutionStore.java` in `implementationSources`. **Phase 2 therefore needs no new register row if the uid map lives in `jobs.db`.** |
| D7 | RISK-011 instrument grammar | **verified** | `docs/reference/architectural-risks.md:43-70`; `tempdoc:<NNN>#<heading substring>` resolves when `docs/tempdocs/<NNN>-*.md` exists and contains a heading containing the substring |

### §B.3 Consumer sweep — everything that read a retired key

This is the Phase 1 sweep list; every row is handled in §D.

**Commit user-data producers/validators:** `SsotCommitMetadataSource.java` (producer),
`RequiredFieldsCommitMetadataValidator.java:11-19,31-36`,
`SSOT/schemas/indexing/commit-metadata.schema.json:8-29`.

**Parity/guard:** `ParityDiagnostics.java:15-16,28-29,44-50,59`,
`IndexMetadataParityGuard.java:24,56-65`.

**Status surface:** `IndexStatusOps.java:667-679` (`buildCompatibility`), `:1097-1171`
(`safeSchemaFingerprintCurrent/Stored`, `safeSchemaCompatState`, `isReindexRequired`,
`reindexRequiredReason`).

**Migration:** `KnowledgeServerMigrationOps.java:316-327` (green verification).

**Telemetry:** `CommitMetadataSpanAttrs.java:19-27` (`KEYS`),
`NdjsonSpanExporter.java:70-77` (`ALLOWED_ATTRS`, the `commit.*` allowlist).

**jseval:** `index_identity.py:136-139,772,778-781`, `manifest.py:73-80,187,192`,
`release.py:75-86`, `preflight.py:150-152`, `projections/_spike_schema.py:73-80,126-133`, plus
fixtures in `tests/test_index_identity.py`, `test_manifest.py`, `test_release.py`,
`test_preflight.py`, `test_spike_schema.py`.

**Tests constructing commit-metadata maps:** `OpenTimeCommitUserDataTest`, `CommitOpsTest`,
`ParityGuardTest`, `CommitMetadataIntegrationTest`, `RequiredFieldsCommitMetadataValidatorTest`,
`GreenCutoverEmbeddingFpVerifyTest`, `VduStatusTransitionsTest`, `PruneByPathPrefixTest`,
`FolderBrowseEngineTest`, `DeleteByCollectionTest`, `InvariantSuiteIT`,
`SchemaMismatchStatusContractTest`, `TracingLocalExportTest`, `NerBackfillOpsTest`.

**Docs:** `docs/explanation/11`, `04`, `06`, `08`, `09`, `18`;
`docs/reference/index-schema-mismatch-reindex-noop.md`;
`docs/reference/configuration/environment-variables.md`;
`docs/decisions/0014-pipeline-definition-removal.md:51`.

**Checked and NOT consumers** (recorded so a later reader does not re-derive it):
`scripts/ci/check-live-witness.mjs` (no matches), `governance/**` (no file names any of the five
keys or any of `SsotCommitMetadataSource` / `ParityDiagnostics` / `IndexMetadataParityGuard` /
`CommitOps`), `config/**` yaml (no `index.parity` / `schema_mismatch` keys),
`scripts/jseval/jseval/ratchet_kernel.py` (its `allow_mismatch` is an unrelated cohort-engine
flag), `SSOT/versions/catalog.json:3` and `ssot_snapshot.json:3` (the SSOT *input*
`intent_v1.schema_ver`, a different concept from the derived commit-metadata key of the same name —
still read for `/infra/capabilities` at `CapabilitiesService.java:282`),
`modules/prompt-support/**` (prompt-pack `schema_ver`), `contracts/wire/status.proto:22,393`
(envelope `schema_version`).

**Wire surface (deliberately NOT renamed — see §C.7):** `contracts/wire/status.proto:213-223`
(`CompatibilityStatusView.index_schema_fp_current/stored`, `index_schema_compat_state`,
`reindex_required`), `:429-435` (`SchemaStatusGroup.fp_current/fp_stored/compat_state`),
`modules/ipc-common/src/main/proto/indexing.proto:764-777` (`CompatibilityStatus`),
`modules/ui-web/src/shell-v0/state/readinessNotice.ts:358-391,442-446,453`.

### §B.4 One pre-existing doc-drift fix, ridden along

`readinessNotice.ts:358-391`'s comment cites `IndexStatusOps.java:995` for the schema-fp helpers;
they are at `:1097-1171`. Corrected in this PR (§D.14).

---

## §C Design (Phase 1), tightened

### §C.1 `index_fingerprint` — the exact input list

SHA-256 over a canonical JSON document (`IndexFingerprint.canonicalJson`). Keys are emitted from
`TreeMap`s, so **key order is lexicographic at every level**; the `fields` array is sorted by `id`
and each field's `roles` array is sorted. The rendering is stable across JVMs and platforms.

| Key | Read from | Why it is in |
|---|---|---|
| `rendering_version` | `IndexFingerprint.RENDERING_VERSION` | An escape hatch to invalidate every index when the rendering itself changes shape, without pretending an input moved. |
| `catalog_schema_version` | `SSOT/catalogs/fields.v1.json → version` | The catalog author's deliberate break lever. |
| `analyzer_fp` | `SsotAnalyzerRegistry.AnalyzerFingerprintingService` over all analyzer ids | Index-time analysis decides the postings on disk. |
| `vector_format` | `index.vector.quantization.enabled` → `float32` \| `int8_sq` | A different `KnnVectorsFormat` is a different on-disk encoding. |
| `hnsw.m`, `hnsw.ef_construction` | `ResolvedConfig.Index.vectorHnswM/vectorHnswEfConstruction` | These two shape the graph that is written. |
| `chunking.target_tokens` / `overlap_tokens` / `min_tokens` / `algorithm_version` | `ChunkSplitter.DEFAULT_CHUNK_TOKENS` / `DEFAULT_OVERLAP_TOKENS` / `MIN_CHUNK_TOKENS` / `ALGORITHM_VERSION` | Chunk boundaries are on-disk shape. `ALGORITHM_VERSION` is new (§D.3) and is the lever lane E bumps if the splitting *algorithm* changes with the token counts unchanged. |
| `embedding_model_sha256` | `EmbeddingFingerprint.get()` via the installed provider | The model whose output is stored in `vector` / `chunk_vector`. |
| `splade_model_sha256` | `SpladeFingerprint.get()` via the installed provider | The model whose output is stored in the sparse fields. |
| `fields[]` | the physical projection of `fields.v1.json` (§C.2) | What each document actually carries. |

### §C.2 The physical projection — the actual fix for 804

Per field, sorted by `id`: `id`, `type`, `stored`, `doc_values`, `multi_valued`, `analyzer`,
`roles` (sorted), and for vector fields `vector.dimension` + `vector.similarity`.

`roles` **is** physical: `FieldMapper.java:379,392,407` and `ComponentsFactory.java:468` branch on
`roles.contains("filter")` to pick the Lucene field construction.

`vector.dimension` uses the **effective** runtime dimension when one is installed
(`IndexFingerprint.effectiveVectorDimension()`), not the catalog's declared 768 — see §B.1 claim 1c
for why the previous instance-setter approach was silently inconsistent.

`vector.similarity` falls back to `"euclidean"`, which is what Lucene's two-arg
`KnnFloatVectorField` constructor actually applies (`FieldMapper.java:428`). Recording the real
default is what makes Phase 3's `dot_product` a genuine fingerprint change rather than a no-op.

**`rmwPolicy` is excluded.** `FieldMapper.validateRmwPolicies` rejects an `rmwPolicy` on any stored
or doc-values field, so by construction it can only describe fields that are never read back from
disk: it steers runtime read-modify-write preservation and changes no bytes. This single exclusion
is the whole of 804's complaint — three annotation-only catalog edits each demanded a reindex of a
physically compatible index. Pinned by `CatalogPhysicalProjectionTest`, falsified as §F F12.

### §C.3 What is excluded, and why

- **Query-time scoring** — BM25 `k1`/`b` (`similarity_fp`), field boosts (`boosts_fp`), HNSW
  `ef_search`. They change ranking, not storage; a reindex would be a pure cost.
- **The intent grammar, prompt packs, templates, synonyms** — `schema_ver`, `schema_fp`,
  `grammar_*`, `template_ver`, `prompt_pack_hash`, `synonyms_hash`. None of them ever touched the
  index. `schema_ver` was the false detector: pinned at `"1.0.0"` since 2026-01-04, it could never
  fire.
- **`rmwPolicy`** — see §C.2.
- **`field_catalog_hash`** — retained as a separate observability key (it is the honest answer to
  "which catalog file was on disk"), but it is not the identity and not a parity key.

### §C.4 `boosts_fp` — the benign key

Unchanged: SHA-256 of the deterministic `index.boosts` map. It stays a parity key because a
mismatch is worth reporting (the running config disagrees with what the index was built under) and
is never worth a reindex, so it routes to the read-only branch, not to `SCHEMA_MISMATCH`.

### §C.5 Indeterminate is not a mismatch (the tri-state rule)

A model fingerprint is tri-state:

- `NOT_CONFIGURED` — no model file resolvable for this deployment. A determinate answer; hashes as
  JSON `null`.
- `PRESENT` — digest read.
- `INDETERMINATE` — a model file exists but its digest could not be read.

If any input is `INDETERMINATE`, `IndexFingerprint.compute` returns empty, **no fingerprint is
stamped**, and `ParityDiagnostics.diff` skips the key when either side is blank. A transiently
unreadable model file must not be indistinguishable from a swapped one, because the consequence of
the latter is now an automatic full rebuild (`green-masked-destructive`). The same rule flows
through: `IndexStatusOps.safeSchemaCompatState` reports `UNAVAILABLE`, never `COMPATIBLE`; green
verification **refuses** the promotion rather than promoting on an absence of evidence.

`EmbeddingFingerprint` already distinguishes the two absences (`modelPath()` vs `get()`);
`SpladeFingerprint` did not, so it gained a `modelPath()` accessor (§D.9).

### §C.6 Where the two keys are written

Lucene commit user data only. `state.json` does **not** carry the fingerprint — it gains three
brake fields instead (§C.8). Producer: `SsotCommitMetadataSource.build()`, wrapped by
`EmbeddingMetadataOverlay` in the Worker.

The three sites that build the fingerprint independently — the commit path, the parity guard's
"expected" snapshot (`commitOps::buildMetadataSnapshot`), and the green-cutover verification — must
agree. The two inputs only the Worker's model modules can see are published through process-wide
providers installed once in `KnowledgeServer` before the first commit
(`IndexFingerprint.installModelFingerprintProviders`, `installEffectiveVectorDimension`), the same
shape as `ConfigStore.globalOrNull()`. Everything else (Head, tests, tools) sees the
`NOT_CONFIGURED` default, consistently.

**Deviation from the brief.** The brief says `SsotCommitMetadataSource` "no longer reads
`SSOT/versions/catalog.json` for the index". It no longer reads it *for the index*: the
`intent_v1.schema_ver` read is deleted and `schema_ver` is gone from commit metadata entirely. The
file is still read for `grammar_ver` and `template_ver`, which were never parity keys and are
consumed as intent-pipeline observability (ADR-0014, telemetry spans, jseval). Deleting those would
retire an observability surface this lane does not own. **Open question O1 for the programme owner.**

### §C.7 Policy, trigger, and the operator escape

`normalizeSchemaMismatchPolicy` (`ResolvedConfigBuilder.java:1009-1020`): prod default
`FAIL_CLOSED` → **`BLUE_GREEN_MIGRATE`**; dev stays `REBUILD_BACKUP_FIRST`. The old prod default
was the wrong answer for a desktop app — a schema-changing upgrade left the user with an index that
refused to open and no path forward. `REBUILD_BACKUP_FIRST` is the *destructive* branch (it moves
the directory aside and rebuilds empty), which is fine for a developer and wrong for a shipped
install; `SchemaMismatchPolicyBranchTest` pins all three.

**The trigger needed no new code** (§B.1 claim 2c): `KnowledgeServer.java:634-667` already starts
blue/green on `SCHEMA_MISMATCH`. What was missing was that the guard never raised it. Deleting the
two `HeadlessApp` `allow_mismatch=true` set-sites (`:352`, `:783`) is what turns the whole chain on.

`justsearch.index.parity.allow_mismatch` survives as an **operator** escape only: nothing sets it,
its `EnvRegistry` row is unchanged, and its doc rows now say operator rather than "set in dev".
Dev and jseval do **not** need it set — dev resolves to `REBUILD_BACKUP_FIRST`, which handles a
mismatch by rebuilding rather than by refusing, so a developer's schema edit still just works.

### §C.8 The repeat-rebuild brake

A green that never finishes leaves the same mismatch on the next boot, so an unbounded auto-start
rebuilds forever. `IndexGenerationManager.State` gains `auto_rebuild_key` (the `index_fingerprint`
the attempt targeted), `auto_rebuild_count`, `auto_rebuild_first_ms`, persisted through the existing
atomic `writeState` (`format_version` stays 2; absent fields read as null on an older file).

- Bound: `MAX_AUTO_REBUILD_ATTEMPTS = 3` per target fingerprint. Enough to absorb a crash mid-build
  or a disk that fills and clears; few enough that an unbuildable index stops costing a full
  rebuild every boot.
- Keyed by target, so a later unrelated upgrade is not refused for an earlier one's failures.
- Recorded **before** the rebuild starts — a build that crashes the process must still spend its
  attempt, or a crash loop is invisible to the brake.
- Cleared by `promoteBuildingGenerationToActive`: a completed cutover is the proof it converged.
- On exhaustion `KnowledgeServer` logs an operator-actionable error naming `auto_rebuild_count` and
  rethrows, leaving the index untouched.

### §C.9 Green verification

`KnowledgeServerMigrationOps.verifyGreenMetadata` compares `index_fingerprint` instead of
`index_schema_fp`, and gains a third refusal: if *this runtime* cannot compute an expected
fingerprint, it cannot attest that the green it built is the shape it meant to build, so it refuses
the promotion and the cutover retries next boot.

### §C.10 Reason codes

**No reason code was added, removed, or renamed.** The user-facing vocabulary
(`index.schema_mismatch`, `BLOCKED_LEGACY`, `BLOCKED_MISMATCH`, `UNAVAILABLE`, `COMPATIBLE`,
`schema_mismatch`, `legacy_index`, `embedding_mismatch`, `embedding_legacy`) describes a
relationship between a stored and an expected fingerprint, and that relationship is unchanged — only
which fingerprint is compared changed. `check-readiness-reason-codes` and
`check-search-degradation-reason-codes` are run in §G anyway.

Two **commit reasons** were added (an internal telemetry vocabulary, not a reason code):
`MIGRATION_CUTOVER` (`migration/cutover`) and `SWITCH_BUFFER_REPLAY`
(`migration/switch-buffer-replay`).

### §C.11 Wire field names kept

`status.proto`'s `index_schema_fp_current` / `index_schema_fp_stored` /
`index_schema_compat_state` and `indexing.proto`'s `schema_fp_current` / `schema_fp_stored` are the
literal `/api/status` JSON keys the Lit frontend reads. They name a *concept* — "the index's schema
fingerprint, current vs stored" — which `index_fingerprint` still is, now computed truthfully. The
programme puts UI/frontend internals out of scope for every lane, so renaming them would be a
gratuitous FE-breaking change carrying no new truth. Their comments are relabelled instead.
**Open question O2.**

### §C.12 New/changed config keys

**None.** No new `System.getenv`/`getProperty` outside `io.justsearch.configuration`; no new
`EnvRegistry` row; `index.schema_mismatch.policy` and
`justsearch.index.parity.allow_mismatch` keep their names and resolution. Only the *default value*
of the former changed, which is not config-surface growth. `config-surface` is run in §G.

---

## §D Implementation log

| # | Change | Location |
|---|---|---|
| D.1 | New `IndexFingerprint`: tri-state `ModelFingerprint`, `FieldShape` / `Chunking` / `Hnsw` / `Inputs`, `compute` → `Optional<String>`, `canonicalJson`, process-wide model + vector-dimension providers | `modules/adapters-lucene/src/main/java/io/justsearch/adapters/lucene/commit/IndexFingerprint.java` (new, 310 lines) |
| D.2 | `SsotCommitMetadataSource`: deleted the `intent_v1.schema_ver` read, `schema_ver`, `index_schema_fp`, `analyzer_fp`, and the dead `setVectorDimensionOverride`; added `indexFingerprint()`, `projectFields()`, `parseCatalog()`, `vectorFormat()`, `DEFAULT_VECTOR_SIMILARITY` | `SsotCommitMetadataSource.java:40,62-118,120-208` |
| D.3 | `ChunkSplitter.ALGORITHM_VERSION = "v1"` (additive; lane E bumps it if the splitting algorithm changes) | `ChunkSplitter.java:91-99` |
| D.4 | `ParityDiagnostics`: `PARITY_KEYS` → `{index_fingerprint, boosts_fp}`, `REBUILD_REQUIRING_KEYS` → `{index_fingerprint}`, hints rewritten, the `index_schema_fp`-only blank skip generalised to a both-sides tri-state skip | `ParityDiagnostics.java:15-31,44-52,60-70,88-90` |
| D.5 | `IndexMetadataParityGuard`: message and comments describe `index_fingerprint`; the `allow_mismatch` branch documents that nothing sets it any more | `IndexMetadataParityGuard.java:56-73` |
| D.6 | `HeadlessApp`: both `allow_mismatch=true` set-sites deleted | `HeadlessApp.java:351` (was `:352`), `:781` (was `:783`) |
| D.7 | `ResolvedConfigBuilder`: prod default → `BLUE_GREEN_MIGRATE` | `ResolvedConfigBuilder.java:1009-1026` |
| D.8 | `KnowledgeServer`: installs the model-fingerprint + effective-vector-dimension providers before the first commit; repeat-rebuild brake in the `SCHEMA_MISMATCH` catch; metadata supplier simplified (the dead instance override removed) | `KnowledgeServer.java:507-520`, `:638-670`, `:1566-1580` |
| D.9 | `SpladeFingerprint`: `CachedResult` widened with the resolved model path; new `modelPath()` so "not configured" and "digest unreadable" are distinguishable | `SpladeFingerprint.java:46-60,77,90,95,99` |
| D.10 | `IndexGenerationManager`: `State` gains the three brake fields (all 7 construction sites); `MAX_AUTO_REBUILD_ATTEMPTS`, `recordAutoRebuildAttempt`, `autoRebuildAttemptsFor`; `promoteBuildingGenerationToActive` clears the brake | `IndexGenerationManager.java:77-101`, `:320-390`, and the widened constructors |
| D.11 | `CommitOps`: `commitWithBuildState` takes a required `CommitReason` (912 item 1); the low-level `commit()` is package-private so the cross-module bypass is a compile error (912 item 2) | `CommitOps.java:83`, `:143-158` |
| D.12 | `CommitReason`: `MIGRATION_CUTOVER`, `SWITCH_BUFFER_REPLAY` | `CommitReason.java:39-40` |
| D.13 | `KnowledgeServerMigrationOps`: cutover commit attributed `MIGRATION_CUTOVER`; switch-buffer replay routed through the funnel as `SWITCH_BUFFER_REPLAY`; green verification compares `index_fingerprint` and refuses on an uncomputable expected value | `KnowledgeServerMigrationOps.java:224-227`, `:316-345`, `:775-786` |
| D.14 | `IndexStatusOps`: both fingerprint helpers read `IndexFingerprint.COMMIT_META_KEY`; the `UNAVAILABLE` branch documents that an absent answer is not a clean bill | `IndexStatusOps.java:1097-1148` |
| D.15 | `RequiredFieldsCommitMetadataValidator`: `schema_ver`/`analyzer_fp` off the required list; `index_fingerprint` validated as well-formed **when present** (optional by the tri-state rule) | `RequiredFieldsCommitMetadataValidator.java:10-45` |
| D.16 | `commit-metadata.schema.json`: `schema_ver` / `index_schema_fp` / `analyzer_fp` removed; `index_fingerprint` added with a description | `SSOT/schemas/indexing/commit-metadata.schema.json` |
| D.17 | `CommitMetadataSpanAttrs.KEYS` and `NdjsonSpanExporter.ALLOWED_ATTRS`: 8 `commit.*` keys → 7 | `CommitMetadataSpanAttrs.java:19-26`, `NdjsonSpanExporter.java:70-76` |
| D.18 | `CommitFunnelArchTest`: the routed 912 §D.2 open item is closed by construction; doc updated to say so instead of widening an allowlist | `CommitFunnelArchTest.java:26-31,84-88` |
| D.19 | jseval: `index_identity.py`, `manifest.py`, `preflight.py`, `release.py`, `projections/_spike_schema.py` + 5 test files | `scripts/jseval/**` |
| D.20 | Docs: `11-index-schema-migration.md` (fingerprint section, enforcement status 2026-09, policy defaults + brake), `04-storage-engine.md`, `06-configuration-ssot.md`, `08-observability.md`, `09-testing-strategy.md`, `18-adapters-lucene-deep-dive.md`, `index-schema-mismatch-reindex-noop.md` (superseded banner + inline strikes), `environment-variables.md`, ADR-0014 (dated append, history not rewritten) | `docs/**` |
| D.21 | RISK-011 instrumented to `tempdoc:915#C Design (Phase 1), tightened`; notes explain why it stays open rather than closed | `docs/reference/architectural-risks.md:264-282` |
| D.22 | New tests: `IndexFingerprintTest`, `CatalogPhysicalProjectionTest`, `SchemaMismatchPolicyBranchTest`, `IndexRebuildBrakeTest`; extended `CommitReasonAccountingTest`, `InvariantSuiteIT`; updated 14 fixture files | see §G |

---

## §E Post-implementation critical pass

**E1. Wrong-gate check — every gate the change depends on, set-site grepped.**

- `allow_mismatch`: grepped for every set-site of `justsearch.index.parity.allow_mismatch`.
  Remaining writers after the change: only `OpenTimeCommitUserDataTest:38-47` (a test that sets and
  restores it deliberately). `WorkerSpawner.java` *forwards* `INDEX_PARITY_ALLOW_MISMATCH` when
  present — it does not set it, so with nothing setting it the Worker inherits nothing. Verified by
  grep, not by symbol existence.
- `schemaMismatchPolicy`: the blue/green branch compares `"blue_green_migrate".equalsIgnoreCase(...)`
  against a value `normalizeSchemaMismatchPolicy` returns as `"BLUE_GREEN_MIGRATE"`. Case-insensitive
  comparison, so the flip does reach the branch — and `SchemaMismatchPolicyBranchTest` fails if it
  ever stops doing so (F1 in §F breaks the gate and the test reds).
- The brake gates on `attempt > MAX_AUTO_REBUILD_ATTEMPTS` after an increment, so attempts 1-3 run
  and the 4th refuses. Pinned by `repeatRebuildsForTheSameTargetExhaustTheBudget`.

**E2. Audit conclusions independently re-read.** Two subagent claims were load-bearing and both were
re-read at source before use: (i) "`KnowledgeServer` triggers blue/green only on the embedding sha"
— re-read `KnowledgeServer.java:634-667` and found the second, pre-existing SCHEMA_MISMATCH trigger,
which **changed the design** (§B.1 claim 2c); (ii) "`check-live-witness` is not a consumer" — grepped
the file directly, confirmed zero matches.

**E3. Test precision — passes for the right reason.**
- `IndexRebuildBrakeTest.aTruncatedStateFileIsRecoveredFromTheBackupSnapshot` was **rewritten after
  it failed**: my first version asserted a corrupt `state.json` reads as absent. It does not —
  `loadStateBestEffort:757-774` falls back to `state.json.prev`. Asserting the weaker (and wrong)
  property would have documented a worse product than the one that exists. The test now pins the
  real behaviour, and a separate test covers the genuinely-unrecoverable case.
- `ParityGuardTest.parityGuardMarksReadOnlyOnMismatch` was **deleted, not converted**: it flipped
  `similarity_fp`, which is no longer a parity key at all, so the property it tested no longer
  exists. `parityGuardCatchesBoostsMismatch` already covers the surviving read-only branch. Deleting
  it is retirement, not weakening — the branch it exercised is still covered.
- `SchemaMismatchPolicyBranchTest` asserts document counts, not just exception types: "FAIL_CLOSED
  throws" would pass even if it had destroyed the index first.
- `ResolvedConfigBuilderTest` asserts the exact value `BLUE_GREEN_MIGRATE`, not merely "not
  FAIL_CLOSED", so an accidental flip to the destructive `REBUILD_BACKUP_FIRST` fails there.

**E4. Tri-state lookups.** Three places now distinguish unknown from healthy and from mismatched:
`IndexFingerprint.compute` (empty on INDETERMINATE), `ParityDiagnostics.diff` (skips when *either*
side is blank — previously only the stored side, and only for one key), `verifyGreenMetadata`
(refuses on an uncomputable expected value). §F F3, F4 falsify the first two.

**E5. Asymmetric lifecycle.** `installModelFingerprintProviders` has a matching
`resetModelFingerprintProviders` used by `IndexFingerprintTest`'s `@AfterEach`, so a test that
installs a throwing provider cannot leak it into the rest of the fork.

**E6. Actionable findings from this pass: 2** — the corrupt-state assertion (E3) and the
second blue/green trigger (E2/§B.1 2c). Both changed the implementation.

---

## §F Falsification record

Every new or modified guarantee was broken once, run, watched fail with the expected assertion, and
restored. Driver: `tmp/falsify.sh` + `tmp/falsify-patch.py` (deleted before commit).

| ID | Break | Test that caught it | Failing assertion |
|---|---|---|---|
| F1 | `REBUILD_REQUIRING_KEYS` → `Set.of()` | `ParityGuardTest`, `SchemaMismatchPolicyBranchTest` | `parityGuardTriggersRebuildOnFingerprintMismatch` FAILED; `blueGreenMigratePropagatesTheMismatchAndKeepsEveryDocument` FAILED (`SchemaMismatchPolicyBranchTest.java:127`); `failClosedRefusesAndKeepsEveryDocument` FAILED (`:158`) |
| F2 | drop `boosts_fp` from `PARITY_KEYS` | `ParityGuardTest` | `parityGuardCatchesBoostsMismatch` FAILED (`ParityGuardTest.java:68`) |
| F3 | `diff()` blank-skip → only skip when both sides null | `InvariantSuiteIT` | `anIndeterminateExpectedFingerprintIsNotAMismatch` FAILED (`InvariantSuiteIT.java:99`) |
| F4 | `compute()` no longer returns empty on INDETERMINATE | `IndexFingerprintTest` | `anIndeterminateModelYieldsNoFingerprintAtAll` FAILED (`IndexFingerprintTest.java:261`) |
| F5 | `hnsw.ef_construction` hard-coded to 0 | `IndexFingerprintTest` | `everyPhysicalInputMovesTheFingerprint` FAILED (`IndexFingerprintTest.java:172`) |
| F6 | `commitWithBuildState` ignores its reason, passes `UNKNOWN` | `CommitReasonAccountingTest` | `theCutoverCommitIsAttributedToMigrationCutoverNotUnknown` FAILED (`CommitReasonAccountingTest.java:114`) |
| F7 | funnel skips `pendingDocs.set(0L)` | `CommitReasonAccountingTest` | `theSwitchBufferReplayCommitIsCountedAndNotifiesTheListener` FAILED (`:143`) |
| F8 | brake's `sameTarget` always false | `IndexRebuildBrakeTest` | `repeatRebuildsForTheSameTargetExhaustTheBudget` FAILED (`IndexRebuildBrakeTest.java:75`); `aSuccessfulCutoverClearsTheBrake` FAILED (`:111`) |
| F9 | cutover carries the brake forward instead of clearing it | `IndexRebuildBrakeTest` | `aSuccessfulCutoverClearsTheBrake` FAILED (`:118`) |
| F10 | prod default back to `FAIL_CLOSED` | `ResolvedConfigBuilderTest` | `null/blank defaults to BLUE_GREEN_MIGRATE in prod mode` FAILED (`ResolvedConfigBuilderTest.java:1495`) |
| F11 | `startMigration` repoints `active_generation` to Green | `IndexRebuildBrakeTest` | `migrationBuildsGreenBesideBlueAndOnlyPromotionSwitches` FAILED (`IndexRebuildBrakeTest.java:43`) |
| F12 | `rmwPolicy` put back into the physical projection | `CatalogPhysicalProjectionTest` | `theProjectionDropsRmwPolicyEntirely` FAILED (`:89`); `anRmwPolicyAnnotationDoesNotCostTheUserAReindex` FAILED (`:75`) |

**F12 is the reason this section exists.** On the first falsification run F12 reported
*NO FAILURE OBSERVED*: the headline property of the whole phase — that an annotation-only catalog
edit no longer costs a reindex — was pinned by nothing. `CatalogPhysicalProjectionTest` was written
in response, and `projectFields` / `parseCatalog` were extracted from `indexFingerprint()` to make
it directly testable. A test that cannot be made to fail is not evidence; a property with no test
is not a guarantee.

---

## §G Verification results

Gradle home: `C:\Users\Elias\AppData\Local\Temp\jsgh-R1` (isolated from the other lanes).
Results are recorded in the §G table of the report-back below and in the PR body.

---

## Cross-lane requests

- **Lane E** — `ChunkSplitter.ALGORITHM_VERSION` is new and additive (`ChunkSplitter.java:91-99`).
  If your PR touches `ChunkSplitter`, expect a one-constant merge. Bump it when the splitting
  *algorithm* changes with the token counts unchanged; the token counts themselves are already
  fingerprint inputs, so your chunk-size number is picked up automatically once either PR merges.
- **Lane B** — RISK-011 is now instrumented at `tempdoc:915#C Design (Phase 1), tightened` and is
  deliberately left **Monitoring**, not closed; the notes say why. ADR-0007's entity-boost amendment
  stays a Phase 3 concern; nothing in Phase 1 touches the entity fields.
- **Lane C** — Phase 1 touches `IndexGenerationManager` (`worker-core`) and `IndexStatusOps`
  (`worker-services`), which are not in lane D's declared file list but are on the migration /
  status path the brief's sweep instruction reaches. Flagging in case of overlap.
- **Programme owner** — see the two open questions below.

## Open items

1. **O1 (decision needed).** `SsotCommitMetadataSource` still reads `SSOT/versions/catalog.json`
   for `grammar_ver` / `template_ver`, which are not index identity. Delete them from commit
   metadata too (a wider observability retirement, touching ADR-0014, telemetry and jseval), or
   keep? §C.6.
2. **O2 (decision needed).** The proto/FE field names still say `schema_fp` / `index_schema_fp`.
   Keep (current choice, §C.11) or rename in a follow-up that lane D does not own?
3. **O3.** The full blue/green loop end-to-end — Blue serving live queries while Green ingests, then
   a real cutover — is verified at the state-machine and policy-branch level here, not with a
   running Worker. A live jseval/dev-stack pass belongs to the wave-2 release window.
4. **O4 (routed, pre-existing).** Phase 3's consumer list for `chunk_content` is wrong:
   `HighlightingOps` never reads it, and three of the four cited `RagContextOps` lines read whole-doc
   `CONTENT` (§B.2 D3/D4). Phase 3 must re-derive that list rather than trust the brief.
5. **O5 (routed, pre-existing, latent).** The vector-dimension override was applied only to the
   commit path's instance, never to the two comparison paths (§B.1 claim 1c). Fixed here as a
   side-effect; recorded because it is the exact shape of defect the one-fingerprint design exists
   to prevent, and it survived undetected only because the guard was off.

## Report-back

See the PR body and §F/§G. Extended by Phases 2 and 3.
