---
title: "Lane D: one truthful index fingerprint, stable document identity, and the reindex bundle"
type: tempdocs
status: "PHASE 1 IMPLEMENTED + INDEPENDENT REVIEW APPLIED (2026-09-03) — five parity keys replaced by index_fingerprint + boosts_fp; the Head no longer disables the parity guard; production default is BLUE_GREEN_MIGRATE with a bounded repeat-rebuild brake. Phases 2 (document identity) and 3 (reindex bundle) are PENDING."
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

### Review round (independent review returned NEEDS-FIXES; all decisions applied)

- [x] R-B1. **The guard was inert on every pre-PR index.** A blanket blank-side skip meant a legacy
      index (which has a blank stored `index_fingerprint` forever) could never mismatch. Blank
      STORED on a rebuild-requiring key is now a mismatch carrying a
      `legacy-index-without-fingerprint` hint — the deliberate one-time upgrade rebuild. Blank
      EXPECTED still skips, now with a once-per-boot WARN naming the unresolved input.
- [x] R-B2. `readinessNotice.ts`'s comment still described the retired file-hash and cited a stale
      line; §B.4/§D.14 claimed a fix that had not been made. Comment relabelled (the relabel §C.11
      promised); §B.4/§D.14 corrected below.
- [x] R-S1. HNSW params are hashed as **effective** values, so an explicitly-written default is no
      longer a spurious reindex. One home for the fallbacks (`ResolvedConfig.Index`).
- [x] R-S2. Added `chunking.threshold_chars` and `preview.max_chars` — both decide what is written.
- [x] R-S3. Added `ner_model_sha256` via a new `NerFingerprint`, mirroring `SpladeFingerprint`.
- [x] R-S4. Added `analysis.lucene_version` + `analysis.icu_version`.
- [x] R-S5. Brake exhaustion opens Blue **read-only** instead of failing `start()`; new reason code
      `index.rebuild_brake_exhausted`; uncomputable targets get no budget; the metadata build inside
      the catch is guarded so it cannot mask the original mismatch.
- [x] R-S6. The second `allow_mismatch` writer (a test) now states it is exercising the operator
      escape; `WorkerSpawner`'s comment no longer calls it a dev/demo bypass; §E1 corrected.
- [x] R-S7. A missing SPLADE/NER model file is `NOT_CONFIGURED`, not `INDETERMINATE`.
- [x] R-S8. The env-var row documents the per-mode default.
- [x] R-S9. Added the green-verification third-refusal test and a wire-level assertion that both new
      commit reasons reach `index.runtime.commit_total` as their own series.
- [x] R-nits. Replaced-set corrected, line references refreshed, the `CommitFunnelArchTest` claim
      restated (the allowlist did not shrink — the bypass was closed by making
      `CommitOps.commit()` package-private).

### Delta review round (B3 blocker + S10-S13 + nits)

- [x] R-B3. **Brake exhaustion exited the Worker.** The `return;` inside `start()` skipped gRPC bind,
      the port write, the indexing loop, the sentinel and the `appServices` construction that builds
      `IndexStatusOps` — so `getPort()` was -1, `blockUntilShutdown()` returned at once, and
      `IndexerWorker.main` fell through to a silent exit 0. `start()` now sets `rebuildBrakeExhausted`
      and falls through (§C.8, §D.24).
- [x] R-S10. `BrakeExhaustedWorkerServesReadOnlyTest` boots a real `KnowledgeServer` into the
      exhausted state and asserts the whole chain over gRPC: port bound + running, the status payload
      carrying `BLOCKED_REBUILD_BRAKE`/`rebuild_brake_exhausted`, a search answered from Blue, and the
      operator rebuild clearing the brake. It found a second defect on its first run (§D.24).
- [x] R-S11. §C.8's "rethrows" and §C.10's "no reason code added" were both false. Corrected above.
- [x] R-S12. `SchemaMismatchPolicyBranchTest` now runs the **key-absent** fixture through all three
      policy branches, not only the present-but-different one — the absent case is the shape the
      entire installed base arrives in.
- [x] R-S13. The chunk constants have one owner (`ChunkSplitter`, in `modules:indexing`, already an
      `api` dependency of adapters-lucene). The adapters-lucene mirror and its drift test are gone,
      and two further private copies of `4096` were repointed. The phantom test name in the mirror's
      Javadoc went with it.
- [x] R-nits. `major.minor` analysis versions; the bench reads the effective HNSW accessors; the wire
      test parses `tags.reason` instead of substring-matching; the once-per-boot WARN is resettable
      and tested; the legacy hint no longer names a cause it cannot know; `ParityDiagnostics` and
      `IndexStatusOps` share one legacy predicate and the fresh-empty-index case is pinned on both
      sides; line citations refreshed; §G integration-test counts re-measured.

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

`readinessNotice.ts:358-391`'s comment described `index_schema_fp` as a content hash of
`fields.v1.json` and cited `IndexStatusOps.java:995` for the comparison; the helpers are at
`:1097-1180` and the compared value is now `index_fingerprint`.

**Correction (review round):** the first cut of this tempdoc claimed this was fixed, and it was not —
the PR touched no `modules/ui-web` file at all, so the comment stayed wrong and the claim was false.
It is fixed now (§D.23), and the ui-web gate set + `npm run typecheck` were run for that edit.

---

## §C Design (Phase 1), tightened

### §C.1 `index_fingerprint` — the exact input list

**The replaced set, stated exactly** (the class Javadoc says the same, and the two must not drift):
`index_fingerprint` replaces **four** of the five keys that were parity-checked — `schema_ver`,
`analyzer_fp`, `index_schema_fp`, `similarity_fp`. The fifth, `boosts_fp`, survives unchanged as the
benign key (§C.4). `schema_fp` (the search-intent schema hash) was never a parity key and stays
plain observability. "Five keys become two" is the count of *parity keys before and after*, not a
claim that five were deleted.

SHA-256 over a canonical JSON document (`IndexFingerprint.canonicalJson`). Keys are emitted from
`TreeMap`s, so **key order is lexicographic at every level**; the `fields` array is sorted by `id`
and each field's `roles` array is sorted. The rendering is stable across JVMs and platforms.

| Key | Read from | Why it is in |
|---|---|---|
| `rendering_version` | `IndexFingerprint.RENDERING_VERSION` | An escape hatch to invalidate every index when the rendering itself changes shape, without pretending an input moved. |
| `catalog_schema_version` | `SSOT/catalogs/fields.v1.json → version` | The catalog author's deliberate break lever. |
| `analyzer_fp` | `SsotAnalyzerRegistry.AnalyzerFingerprintingService` over all analyzer ids | Index-time analysis decides the postings on disk. |
| `vector_format` | `index.vector.quantization.enabled` → `float32` \| `int8_sq` | A different `KnnVectorsFormat` is a different on-disk encoding. |
| `hnsw.m`, `hnsw.ef_construction` | `ResolvedConfig.Index.effectiveVectorHnswM()` / `effectiveVectorHnswEfConstruction()` | These two shape the graph that is written. **Effective**, not raw: the config is nullable and the codec falls back to 16/200, so hashing the raw value made writing a default out explicitly look like a schema change. One home for the fallback constants, read by both the codec and the fingerprint. |
| `preview.max_chars` | `ChunkDocumentWriter.CONTENT_PREVIEW_MAX_CHARS` (mirrored) | Bounds `content_preview`, a `stored:true` field. |
| `analysis.lucene_version`, `analysis.icu_version` | `org.apache.lucene.util.Version.LATEST`, `com.ibm.icu.util.VersionInfo.ICU_VERSION` | The libraries that do index-time analysis. An upgrade changes the postings with every descriptor unchanged. Deliberately coarse (§C.3). |
| `ner_model_sha256` | `NerFingerprint.get()` via the installed provider | `entity_*_raw` are `stored`+`docValues` fields written from NER output (`NerBackfillOps.java:217`), so the model is index content. |
| `chunking.target_tokens` / `overlap_tokens` / `min_tokens` / `threshold_chars` / `algorithm_version` | `ChunkSplitter.DEFAULT_CHUNK_TOKENS` / `DEFAULT_OVERLAP_TOKENS` / `MIN_CHUNK_TOKENS` / `ChunkDocumentWriter.CHUNK_THRESHOLD_CHARS` (mirrored) / `ChunkSplitter.ALGORITHM_VERSION` | Chunk boundaries are on-disk shape, and `threshold_chars` decides whether chunk documents exist at all. `ALGORITHM_VERSION` is new (§D.3) and is the lever lane E bumps if the splitting *algorithm* changes with the token counts unchanged. |
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
- **Not excluded, but deliberately coarse:** `analysis.lucene_version` / `analysis.icu_version`. A
  Lucene or ICU minor bump will trigger one rebuild even where the analysis did not actually change.
  That cost is accepted because the alternative is a postings change that no detector can see —
  every per-field descriptor stays identical while the tokens on disk differ.
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

A **missing** model file is `NOT_CONFIGURED`, not `INDETERMINATE` — most installs have no SPLADE or
NER model, and reading their absence as "no answer" would switch the parity check off on every one
of them. Only a resolvable model file whose digest cannot be read is indeterminate.

If any input is `INDETERMINATE`, `IndexFingerprint.compute` returns empty, **no fingerprint is
stamped**, and `ParityDiagnostics.diff` skips the key when the *expected* side is blank, with a
once-per-boot WARN from the guard naming the unresolved input (a check that is not running must not
look like a check that passed). A transiently
unreadable model file must not be indistinguishable from a swapped one, because the consequence of
the latter is now an automatic full rebuild (`green-masked-destructive`). The same rule flows
through: `IndexStatusOps.safeSchemaCompatState` reports `UNAVAILABLE`, never `COMPATIBLE`; green
verification **refuses** the promotion rather than promoting on an absence of evidence.

`EmbeddingFingerprint` already distinguishes the two absences (`modelPath()` vs `get()`);
`SpladeFingerprint` did not, so it gained a `modelPath()` accessor (§D.9).

### §C.5a A blank STORED fingerprint is a mismatch (the legacy path)

The tri-state above is about the *expected* side. The **stored** side is not symmetric, and the first
cut of this change got that wrong: it skipped a blank stored value too, which meant every index built
before this key existed had a blank stored side forever and could never mismatch — the guard was
inert on exactly the installs it exists to protect (independent review, reproduced: diffs on a legacy
index = 0).

An index whose physical shape was never recorded cannot be shown to match this runtime. So a blank
stored value on a **rebuild-requiring** key is a mismatch, carrying the
`ParityDiagnostics.LEGACY_INDEX_HINT` (`legacy-index-without-fingerprint`) so the log and the status
surface say *why* the migration started — not "your shape changed" but "this index predates the
record". Under the production `BLUE_GREEN_MIGRATE` default that is one rebuild, beside the live
index, with search serving throughout: the deliberate one-time upgrade the programme's wave-2
release rule already assumes ("existing installs pay for one rebuild, carried by the blue/green
default"). A blank stored value on the **benign** key still skips — an unverifiable `boosts_fp` is
not worth reporting, let alone acting on.

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
- On exhaustion `KnowledgeServer` logs an operator-actionable error, opens the active generation
  **read-only**, and **finishes starting**.

**Corrected twice, and the second correction is the interesting one.** The first cut *rethrew*, which
is the dead-end `FAIL_CLOSED` produced, arriving three boots later. The second cut stopped rethrowing
but `return`-ed out of `start()` — which skipped `createGrpcServer`/`grpcServer.start()`,
`signalBus.writePort`, `appServices.startIndexingLoop()`, `startSentinelThread()`, and the whole
`infraCtx`/`appServices` construction that builds `GrpcIngestService` and with it `IndexStatusOps`.
The Worker exited 0 with no port and no fatal-reason marker, so *both* things this design promises —
"Blue keeps serving" and "the status surface says why" — were unreachable, while every unit test and
the reason-code gate stayed green. `start()` now sets a state and falls through:

| Step | With the brake exhausted |
|---|---|
| runtimes | active generation opens read-only; ingest == search; no Green |
| switch-buffer drain | skipped (no writer to drain into) |
| gRPC create + start, `writePort` | run — the Worker binds and is discoverable |
| indexing loop | not started, logged at ERROR (its only job is to write into a read-only runtime) |
| sentinel | runs |

Recovery is `core.rebuild-index` → `startMigration(user_requested_rebuild)` → cutover →
`promoteBuildingGenerationToActive`, which clears `auto_rebuild_*`. The exhaustion log line names it.

This is the `audit-without-test` case in its purest form: the constant existed, the mapping existed,
the gate counted it — and nothing could emit it. Only `BrakeExhaustedWorkerServesReadOnlyTest`, which
boots a real `KnowledgeServer` and reads the answer off the wire, could tell. It then immediately
found a *second* defect the static reasoning had missed (§D.24).

### §C.9 Green verification

`KnowledgeServerMigrationOps.verifyGreenMetadata` compares `index_fingerprint` instead of
`index_schema_fp`, and gains a third refusal: if *this runtime* cannot compute an expected
fingerprint, it cannot attest that the green it built is the shape it meant to build, so it refuses
the promotion and the cutover retries next boot.

### §C.10 Reason codes

**One reason code was added: `INDEX_REBUILD_BRAKE_EXHAUSTED` → `index.rebuild_brake_exhausted`.**
(The first cut of this section said none was, which was true when it was written and false by the
time the brake stopped being a dead end.) It exists because exhaustion is a state with a *different
remedy* from a plain mismatch: waiting will not fix it, and the user has to start a rebuild. It
travels on the existing wire — `IndexStatusOps` reports compat state `BLOCKED_REBUILD_BRAKE` with
`reindexRequiredReason = rebuild_brake_exhausted`, and `StatusLifecycleHandler.compatBlockedReason`
maps that to the code — so no proto change was needed. `readinessNotice.ts` carries the worded row
and the rebuild remedy; `check-readiness-reason-codes` sees 55 emittable codes / 49 worded rows (was
54/48).

Nothing else changed. The rest of the user-facing vocabulary
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
| D.1 | New `IndexFingerprint`: tri-state `ModelFingerprint`, `FieldShape` / `Chunking` / `Hnsw` / `Analysis` / `Inputs`, `compute` → `Optional<String>`, `canonicalJson`, `indeterminateInputs()` / `indeterminateModelInputs()`, process-wide model + vector-dimension providers | `modules/adapters-lucene/src/main/java/io/justsearch/adapters/lucene/commit/IndexFingerprint.java` (new) |
| D.2 | `SsotCommitMetadataSource`: deleted the `intent_v1.schema_ver` read, `schema_ver`, `index_schema_fp`, `analyzer_fp`, and the dead `setVectorDimensionOverride`; added `indexFingerprint()`, `projectFields()`, `parseCatalog()`, `vectorFormat()`, `DEFAULT_VECTOR_SIMILARITY` | `SsotCommitMetadataSource.java:40,62-118,120-208` |
| D.3 | `ChunkSplitter.ALGORITHM_VERSION = "v1"` (additive; lane E bumps it if the splitting algorithm changes) | `ChunkSplitter.java:91-99` |
| D.4 | `ParityDiagnostics`: `PARITY_KEYS` → `{index_fingerprint, boosts_fp}`, `REBUILD_REQUIRING_KEYS` → `{index_fingerprint}`, hints rewritten; blank-side handling made asymmetric (§C.5a): blank *expected* skips, blank *stored* on a rebuild-requiring key is a mismatch carrying `LEGACY_INDEX_HINT` | `ParityDiagnostics.java` — `PARITY_KEYS`, `REBUILD_REQUIRING_KEYS`, `LEGACY_INDEX_HINT`, `diff()` |
| D.5 | `IndexMetadataParityGuard`: message and comments describe `index_fingerprint`; the `allow_mismatch` branch documents that nothing sets it any more | `IndexMetadataParityGuard.java:56-73` |
| D.6 | `HeadlessApp`: both `allow_mismatch=true` set-sites deleted | `HeadlessApp.java:351` (was `:352`), `:781` (was `:783`) |
| D.7 | `ResolvedConfigBuilder`: prod default → `BLUE_GREEN_MIGRATE` | `ResolvedConfigBuilder.java:1009-1026` |
| D.8 | `KnowledgeServer`: installs the model-fingerprint + effective-vector-dimension providers before the first commit; repeat-rebuild brake in the `SCHEMA_MISMATCH` catch; metadata supplier simplified (the dead instance override removed) | `KnowledgeServer.java:507-520`, `:638-670`, `:1566-1580` |
| D.9 | `SpladeFingerprint`: `CachedResult` widened with the resolved model path; new `modelPath()` so "not configured" and "digest unreadable" are distinguishable | `SpladeFingerprint.java:46-60,77,90,95,99` |
| D.10 | `IndexGenerationManager`: `State` gains the three brake fields (all **8** `new State(` construction sites — `startMigration`, `setMigrationPaused`, `updateMigrationState`, `promoteBuildingGenerationToActive`, `rollbackToPreviousGeneration`, `newIdleState`, `normalizeAndUpgradeStateIfNeeded`, `recordAutoRebuildAttempt`); `MAX_AUTO_REBUILD_ATTEMPTS`, `recordAutoRebuildAttempt`, `autoRebuildAttemptsFor`; `promoteBuildingGenerationToActive` clears the brake | `IndexGenerationManager.java` — `State` record, `recordAutoRebuildAttempt` / `autoRebuildAttemptsFor`, and the widened constructors |
| D.11 | `CommitOps`: `commitWithBuildState` takes a required `CommitReason` (912 item 1); the low-level `commit()` is package-private so the cross-module bypass is a compile error (912 item 2) | `CommitOps.java:83`, `:143-158` |
| D.12 | `CommitReason`: `MIGRATION_CUTOVER`, `SWITCH_BUFFER_REPLAY` | `CommitReason.java:39-40` |
| D.13 | `KnowledgeServerMigrationOps`: cutover commit attributed `MIGRATION_CUTOVER`; switch-buffer replay routed through the funnel as `SWITCH_BUFFER_REPLAY`; green verification compares `index_fingerprint` and refuses on an uncomputable expected value | `KnowledgeServerMigrationOps.java:229`, `:325-345`, `:793` |
| D.14 | `IndexStatusOps`: both fingerprint helpers read `IndexFingerprint.COMMIT_META_KEY`; the `UNAVAILABLE` branch documents that an absent answer is not a clean bill | `IndexStatusOps.java:1101`, `:1124`, `:1138-1139` |
| D.15 | `RequiredFieldsCommitMetadataValidator`: `schema_ver`/`analyzer_fp` off the required list; `index_fingerprint` validated as well-formed **when present** (optional by the tri-state rule) | `RequiredFieldsCommitMetadataValidator.java:10-45` |
| D.16 | `commit-metadata.schema.json`: `schema_ver` / `index_schema_fp` / `analyzer_fp` removed; `index_fingerprint` added with a description | `SSOT/schemas/indexing/commit-metadata.schema.json` |
| D.17 | `CommitMetadataSpanAttrs.KEYS` and `NdjsonSpanExporter.ALLOWED_ATTRS`: 8 `commit.*` keys → 7 | `CommitMetadataSpanAttrs.java:19-26`, `NdjsonSpanExporter.java:70-76` |
| D.18 | `CommitFunnelArchTest`: the routed 912 §D.2 open item is closed by construction. To be exact, since the first cut of this row overstated it — **the `ALLOWED` allowlist is unchanged** (still `CommitOps`, `RuntimeSession`, `ComponentsFactory`). What closed the cross-module bypass is that `CommitOps.commit()` is package-private now, so `KnowledgeServerMigrationOps` cannot call it at all; only the class Javadoc changed | `CommitFunnelArchTest.java` (Javadoc only) |
| D.19 | jseval: `index_identity.py`, `manifest.py`, `preflight.py`, `release.py`, `projections/_spike_schema.py` + 5 test files | `scripts/jseval/**` |
| D.20 | Docs: `11-index-schema-migration.md` (fingerprint section, enforcement status 2026-09, policy defaults + brake), `04-storage-engine.md`, `06-configuration-ssot.md`, `08-observability.md`, `09-testing-strategy.md`, `18-adapters-lucene-deep-dive.md`, `index-schema-mismatch-reindex-noop.md` (superseded banner + inline strikes), `environment-variables.md`, ADR-0014 (dated append, history not rewritten) | `docs/**` |
| D.21 | RISK-011 instrumented to `tempdoc:915#C Design (Phase 1), tightened`; notes explain why it stays open rather than closed | `docs/reference/architectural-risks.md:264-282` |
| D.22 | New tests: `IndexFingerprintTest`, `CatalogPhysicalProjectionTest`, `SchemaMismatchPolicyBranchTest`, `IndexRebuildBrakeTest`; extended `CommitReasonAccountingTest`, `InvariantSuiteIT`; updated 14 fixture files | see §G |
| D.24 | **Delta-review round (B3).** `KnowledgeServer.start()` no longer returns on brake exhaustion: it sets `rebuildBrakeExhausted`, opens Blue read-only, and falls through the rest of the sequence. Five previously skipped sites and what each does now — `createGrpcServer` + `grpcServer.start()`: run, so the port is real; `signalBus.writePort(boundPort)`: runs, so the Head discovers the Worker; `infraCtx`/`appServices` construction: runs, which is what builds `GrpcIngestService` and with it `IndexStatusOps`, the only producer of the new reason code; `appServices.startIndexingLoop()`: deliberately NOT started (guarded flag) with an ERROR naming the recovery path, because its only job is to write into a read-only runtime; `startSentinelThread()`: runs. `drainSwitchBufferBestEffort()` is also skipped — a read-only runtime is not a `RunningRuntime`, so it could only have logged a WARN and dropped the ops | `KnowledgeServer.java` — the `rebuildBrakeExhausted` field, the brake branch, the drain guard, the loop guard |
| D.25 | **The second defect, found by the test written for the first.** `IndexStatusOps.buildCore` dereferenced `indexingLoop` unconditionally. With no loop started that NPEs, and `GrpcIngestService.indexStatus` catches `RuntimeException` and returns a stub response with `core.state=ERROR` and NO compatibility sub-message — so `BLOCKED_REBUILD_BRAKE` was still unreachable, now silently. Null-guarded. This was ALSO a pre-existing latent hole: `DefaultWorkerAppServices.startIndexingLoop` already guards for a null loop (deferred-writer mode), so a status RPC arriving before the writer upgrade could blank the whole payload | `IndexStatusOps.java` — `buildCore`'s `setLastCommitTimestamp` |
| D.26 | **S10.** `BrakeExhaustedWorkerServesReadOnlyTest` — boots a real `KnowledgeServer` over a seeded generation layout with an exhausted brake, then asserts over gRPC: `isRunning()` + `getPort() > 0`; `schemaCompatState=BLOCKED_REBUILD_BRAKE` and `reindexRequiredReason=rebuild_brake_exhausted` and `reindexRequired`; a `Search` RPC answered from Blue; and `startMigration` + `promoteBuildingGenerationToActive` clearing the brake | `BrakeExhaustedWorkerServesReadOnlyTest.java` (new) |
| D.27 | **S12.** `SchemaMismatchPolicyBranchTest` gains a `withoutFingerprint()` source and `seedLegacyIndex`, and runs the key-absent fixture through all three policy values | `SchemaMismatchPolicyBranchTest.java` |
| D.28 | **S13.** `ChunkSplitter` (in `modules:indexing`, already an `api` dep of adapters-lucene) owns `CHUNK_THRESHOLD_CHARS` and `CONTENT_PREVIEW_MAX_CHARS`. `SsotCommitMetadataSource` reads them; its mirror and `ChunkWriterFingerprintMirrorTest` are deleted (with them the phantom `ChunkDocumentWriterFingerprintInputsTest` name); `ChunkDocumentWriter` re-exports them the way it already re-exported `CHUNK_TARGET_TOKENS`; two further private `4096` copies in `IndexingDocumentOps` and `GrpcIngestService` now point at the one owner | `ChunkSplitter.java`, `SsotCommitMetadataSource.java`, `ChunkDocumentWriter.java`, `IndexingDocumentOps.java`, `GrpcIngestService.java` |
| D.29 | **Nits.** `majorMinor()` truncates the analysis versions to `major.minor`; `EngineVectorIndexBench` reports the effective HNSW accessors instead of its own 16/200; the wire test parses `tags.reason` into a map instead of substring-matching a line; `resetUncomputableWarnedForTest()` makes the once-per-boot latch testable and `InvariantSuiteIT` asserts one WARN across three opens; `LEGACY_INDEX_HINT` no longer claims the index predates the key (it cannot know that); `ParityDiagnostics.diff` takes `docCount` and both consumers call `isIndexWithoutRecordedFingerprint` | see §F round 3 |
| D.30 | **New tests for the nits.** `SchemaCompatFreshInstallTest` (a fresh empty index is COMPATIBLE, an index holding documents of unrecorded shape is BLOCKED_LEGACY); `ParityGuardTest.anEmptyIndexWithoutAFingerprintIsNotAMigrationCandidate`; `FingerprintInputSourcesTest.aPatchLevelLibraryBumpDoesNotMoveTheFingerprint` and `theChunkFingerprintInputsComeFromTheSplitterNotFromACopy`; `InvariantSuiteIT.aFreshEmptyIndexWithNoFingerprintIsNotMigrated` and `theUncomputableFingerprintWarningIsEmittedOncePerBoot` | see §F round 3 |
| D.23 | **Review round.** `ParityDiagnostics`: `LEGACY_INDEX_HINT` + the asymmetric blank-side rule (§C.5a). `IndexMetadataParityGuard`: `warnIfFingerprintUncomputable`, once per process. `ResolvedConfig.Index`: `DEFAULT_VECTOR_HNSW_M` / `DEFAULT_VECTOR_HNSW_EF_CONSTRUCTION` + `effectiveVectorHnsw*()`; `ComponentsFactory` reads them instead of its own 16/200. `IndexFingerprint`: `Analysis`, `threshold_chars`, `preview.max_chars`, `ner_model_sha256`, three-arg provider install. New `NerFingerprint` (worker-core). `SpladeFingerprint`: a missing model file is `NOT_CONFIGURED`. `ChunkDocumentWriter.CONTENT_PREVIEW_MAX_CHARS` made public; both constants mirrored into `SsotCommitMetadataSource` with a drift test. `KnowledgeServer`: `recordAutoRebuildAttemptOrSkip` (no budget for an unattributable boot), `expectedIndexFingerprintOrNull` (guarded), and exhaustion opens Blue read-only. `LifecycleReasonCode.INDEX_REBUILD_BRAKE_EXHAUSTED`; `StatusLifecycleHandler.compatBlockedReason` maps `BLOCKED_REBUILD_BRAKE`; `IndexStatusOps` produces it. `readinessNotice.ts`: corrected comment + new `index.rebuild_brake_exhausted` row, added to `REINDEX_CAUSE_CODES`. `WorkerSpawner` comment; `SchemaMismatchStatusContractTest` states its escape use; `environment-variables.md` documents the per-mode default | see §F round 2 |

---

## §E Post-implementation critical pass

**E1. Wrong-gate check — every gate the change depends on, set-site grepped.**

- `allow_mismatch`: grepped for every set-site of `justsearch.index.parity.allow_mismatch`.
  **Correction (review round):** the first cut of this bullet said there was one remaining writer.
  There were two — `OpenTimeCommitUserDataTest:38-47` and `SchemaMismatchStatusContractTest:76`, the
  latter in a file this PR modified. Both are tests that set and restore it deliberately, and both
  now say in a comment that they are exercising the operator escape on purpose. No production code
  sets it. `WorkerSpawner.java` *forwards* `INDEX_PARITY_ALLOW_MISMATCH` when
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
`IndexFingerprint.compute` (empty on INDETERMINATE), `ParityDiagnostics.diff`, `verifyGreenMetadata`
(refuses on an uncomputable expected value). §F F3, F4 falsify the first two.

**Corrected in the review round.** The first cut of this bullet claimed the `diff` skip was the
tri-state handling and treated symmetry as the improvement. It was the defect: skipping a blank
*stored* value made the guard inert on every index built before this key existed — the whole
installed base. The skip is now asymmetric (§C.5a), and "unknown" on the expected side is no longer
silent either: `IndexMetadataParityGuard.warnIfFingerprintUncomputable` logs once per process naming
the unresolved input, because a check that is not running must not look like a check that passed.
§F G1-G3 falsify the new rule.

**E5. Asymmetric lifecycle.** `installModelFingerprintProviders` has a matching
`resetModelFingerprintProviders` used by `IndexFingerprintTest`'s `@AfterEach`, so a test that
installs a throwing provider cannot leak it into the rest of the fork.

**E6. Actionable findings from this pass: 2** — the corrupt-state assertion (E3) and the
second blue/green trigger (E2/§B.1 2c). Both changed the implementation.

**E8. What the SECOND pass missed, and why (delta-review round).** The review-round critical pass
walked the brake change and asked whether the reason code was wired on both sides. It was. What it
never asked was whether anything could reach the code that emits it — and the answer was no, twice
over: `start()` returned before `appServices` existed (§D.24), and even after that was fixed the
status builder NPE'd before reaching the compatibility sub-message (§D.25). Both were invisible to
every unit test, to `check-readiness-reason-codes`, and to a careful reading of the diff, because
each one is a fact about a *composition* rather than about any file in it.

The transferable form: **a reason code is not shipped when the constant, the mapping and the gate
exist — it is shipped when something can emit it end to end.** The only instrument that could tell
was a test that boots the real server and reads the wire, and it found the second defect within
seconds of the first run. `audit-without-test`, at composition scale.

**E7. What this pass missed, and why (review round).** The independent review found the inert-guard
defect (B1) that E4 had walked straight past. The pass asked "does this conflate unknown with
healthy?" and answered yes-handled — but only for the *expected* side, because that is the side the
tri-state design was about. The stored side was never interrogated, so a blanket skip written for
one narrow case (a key that legitimately had no stored value) silently generalised to the case that
mattered. The transferable lesson: when a guard is made symmetric, check each side against its own
adverse scenario — symmetry is an aesthetic property, not a correctness one, and the legacy index is
exactly the `green-masked-destructive` shape (a green my dev machine's freshly-built index happened
to satisfy). E7 also revises E6: **actionable findings, counting the review: 3.**

---

## §F Falsification record

Every new or modified guarantee was broken once, run, watched fail with the expected assertion, and
restored. Driver: `tmp/falsify.sh` + `tmp/falsify-patch.py` (deleted before commit).

| ID | Break | Test that caught it | Failing assertion |
|---|---|---|---|
| F1 | `REBUILD_REQUIRING_KEYS` → `Set.of()` | `ParityGuardTest`, `SchemaMismatchPolicyBranchTest`, `InvariantSuiteIT` | four tests red, not three — `parityGuardTriggersRebuildOnFingerprintMismatch` FAILED; `aLegacyIndexWithNoFingerprintIsMigratedRatherThanIgnored` FAILED; `blueGreenMigratePropagatesTheMismatchAndKeepsEveryDocument` FAILED (`SchemaMismatchPolicyBranchTest.java:127`); `failClosedRefusesAndKeepsEveryDocument` FAILED (`:158`) |
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

### Round 3 — one break per delta-review decision

| ID | Break | Test that caught it | Observed failure |
|---|---|---|---|
| G15 | the early `return;` restored inside `start()` | `BrakeExhaustedWorkerServesReadOnlyTest` | `the Worker must not treat an exhausted brake as a fatal start ==> expected: <true> but was: <false>` |
| G16 | `indexingLoop` null-guard removed from `buildCore` | `BrakeExhaustedWorkerServesReadOnlyTest` | `the compat state is the wire carrier for the new reason code ==> expected: <BLOCKED_REBUILD_BRAKE> but was: <>` |
| G17 | the `docCount` term dropped, so an empty index migrates | `ParityGuardTest`, `SchemaCompatFreshInstallTest`, `InvariantSuiteIT` | `an empty index has no content that could have been written under the wrong shape ==> expected: <false> but was: <true>`; `aFreshEmptyIndexIsCompatibleRatherThanBlockedLegacy` FAILED; `aFreshEmptyIndexWithNoFingerprintIsNotMigrated` FAILED with `IndexRuntimeIOException: Index was built with a different effective index shape…` |
| G18 | the status path stops calling the shared predicate | `SchemaCompatFreshInstallTest` | `aFreshEmptyIndexIsCompatibleRatherThanBlockedLegacy` FAILED — "reporting BLOCKED_LEGACY here would demand a rebuild on first launch" |
| G19 | analysis versions hashed at patch level | `FingerprintInputSourcesTest` | `aPatchLevelLibraryBumpDoesNotMoveTheFingerprint` FAILED — `expected: <10.3> but was: <10.3.1>` |
| G20 | the chunk-constant mirror re-introduced | `FingerprintInputSourcesTest` | `the mirror is gone: read the splitter's constant, do not re-copy it ==> expected: not equal but was: <CHUNK_THRESHOLD_CHARS>` |
| G21 | the blank-stored skip restored, so legacy indexes never migrate | `SchemaMismatchPolicyBranchTest` | all three legacy-fixture branches FAILED: `Expected IndexRuntimeIOException to be thrown, but nothing was thrown` (BLUE_GREEN_MIGRATE, FAIL_CLOSED) and `expected: <0> but was: <1>` (REBUILD_BACKUP_FIRST) |
| G22 | the once-per-boot WARN latch removed | `InvariantSuiteIT` | `three opens, one warning ==> expected: <1> but was: <3>` |
| G23 | the schema-mismatch `try` put back inside the `else` branch only (i.e. not covering a resumed migration) | `BrakeExhaustedWorkerServesReadOnlyTest` | `java.io.IOException: Failed to start KnowledgeServer` … `Caused by: IndexRuntimeIOException: Index was built with a different effective index shape than this runtime produces (index_fingerprint mismatch)` at `KnowledgeServer.start(:577)` — observed directly, before the hoist |

**G15 failed as NO FAILURE OBSERVED twice, for two different reasons, and both were mine.**

The first time, the harness's `replace` silently matched nothing, so the break was never applied and a
green run got recorded as a weak test. The second time the break *was* applied and the test still
passed — because the test never reached the branch at all. Its config used
`justsearch.index.schema_mismatch.policy`; the real key is un-prefixed
(`ResolvedConfigBuilder:1545`), so the policy silently fell back to the dev default
`REBUILD_BACKUP_FIRST`, the mismatch was "recovered" destructively instead of propagating, and every
remaining assertion passed on evidence that had nothing to do with the brake: the port is bound on
any boot, search answers on any boot, and `BLOCKED_REBUILD_BRAKE` is read out of `state.json`, which
the test wrote itself.

The fix is `rebuildBrakeExhaustedForTest()` and a precondition assertion. That is the general shape:
**when every observable consequence of a branch is also true without it, the test needs a witness
that the branch ran** — otherwise it is measuring the fixture. This is the third time in this tempdoc
that a falsification run caught a test passing for the wrong reason (F12, G12, G15), and the only one
where two independent causes stacked.

### Round 2 — one break per review decision

| ID | Break | Test that caught it | Failing assertion |
|---|---|---|---|
| G1 | blank-stored skip restored for all keys (the original defect) | `InvariantSuiteIT` | `aLegacyIndexWithNoFingerprintIsMigratedRatherThanIgnored` FAILED (`:103`); `aLegacyIndexDiffNamesItselfAsLegacyRatherThanAsAShapeChange` FAILED (`:118`) |
| G2 | legacy diff carries the generic hint instead of naming itself | `InvariantSuiteIT` | `aLegacyIndexDiffNamesItselfAsLegacyRatherThanAsAShapeChange` FAILED (`InvariantSuiteIT.java:119`) |
| G3 | benign keys migrated too (a legacy `boosts_fp` would rebuild) | `InvariantSuiteIT` | `aBlankBenignKeyOnALegacyIndexIsNotAMismatch` FAILED (`:139`) |
| G4 | the HNSW fallback diverges from the codec's | `FingerprintInputSourcesTest` | `anExplicitlyWrittenHnswDefaultIsIndistinguishableFromLeavingItUnset` FAILED (`:36`) |
| G5 | `chunking.threshold_chars` dropped from the hash | `IndexFingerprintTest` | `everyPhysicalInputMovesTheFingerprint` FAILED (`:86`) |
| G6 | `preview.max_chars` dropped from the hash | `IndexFingerprintTest` | `everyPhysicalInputMovesTheFingerprint` FAILED (`:86`) |
| G7 | the mirrored `CHUNK_THRESHOLD_CHARS` drifts from `ChunkDocumentWriter` | `ChunkWriterFingerprintMirrorTest` | `theFingerprintMirrorsTheChunkWriterConstants` FAILED (`:22`) |
| G8 | `ner_model_sha256` no longer contributes | `IndexFingerprintTest` | `everyPhysicalInputMovesTheFingerprint` FAILED (`:86`) |
| G9 | `analysis.lucene_version` / `icu_version` pinned to a constant | `IndexFingerprintTest` | `everyPhysicalInputMovesTheFingerprint` FAILED (`:86`) |
| G10 | brake exhaustion forgotten across a restart | `IndexRebuildBrakeTest` | `exhaustionIsRememberedAcrossARestart` FAILED (`:135`); `repeatRebuildsForTheSameTargetExhaustTheBudget` FAILED (`:82`); `aSuccessfulCutoverClearsTheBrake` FAILED |
| G11 | a missing SPLADE model file becomes INDETERMINATE again | `SpladeFingerprintTriStateTest` | `aDirectoryWithNoModelFileIsNotConfiguredRatherThanIndeterminate` FAILED (`:51`) |
| G12 | green verification promotes on an uncomputable expected fingerprint | `GreenCutoverEmbeddingFpVerifyTest` | `expected fingerprint uncomputable -> green REJECTED rather than promoted blind` FAILED (`:84`) |
| G13 | both migration reasons collapse to `unknown` on the wire | `IndexRuntimeWireFormatRegressionTest` | `theMigrationCommitReasonsReachTheWireAsTheirOwnSeries` FAILED (`:291`) |
| G14 | the indeterminate sentinel removed, so unattributable boots share one budget | `KnowledgeServerBrakeSentinelTest` | `anUncomputableTargetNeverConsumesBudget` FAILED (`:30`) |

**G12 caught a test-precision defect in my own new test.** On its first run G12 reported *NO FAILURE
OBSERVED*: with the refusal branch removed the code fell through to the mismatch branch and returned
`false` anyway, so the test passed — for a reason that was not true. The test now captures the logger
and asserts the refusal *reason*, not merely the verdict. A test that cannot be made to fail is not
evidence; a test that fails for the wrong reason is worse, because it looks like evidence.

**F12 is the reason this section exists.** On the first falsification run F12 reported
*NO FAILURE OBSERVED*: the headline property of the whole phase — that an annotation-only catalog
edit no longer costs a reindex — was pinned by nothing. `CatalogPhysicalProjectionTest` was written
in response, and `projectFields` / `parseCatalog` were extracted from `indexFingerprint()` to make
it directly testable. A test that cannot be made to fail is not evidence; a property with no test
is not a guarantee.

---

## §G Verification results

Gradle home: `C:\Users\Elias\AppData\Local\Temp\jsgh-R1` (isolated from the other lanes).
Re-run in full after the DELTA-review round; every line below is from that run. The integration-test counts in the previous version of this table were wrong — they were read from a results directory holding an earlier run's XML, which is the same stale-artefact mistake the round-3 falsification harness made (§F). Counts here are from a `cleanIntegrationTest` run.

| Command | Result |
|---|---|
| `spotlessApply -PskipWebBuild=true` | exit 0 |
| `build -x test -PskipWebBuild=true` | BUILD SUCCESSFUL |
| `cleanTest test -PskipWebBuild=true --no-build-cache --continue` | BUILD SUCCESSFUL in 4m 12s — **1455 suites, 8887 tests, 0 failures, 0 errors, 26 skipped** (counted from `TEST-*.xml`, not from the console) |
| `cleanIntegrationTest :modules:indexing:integrationTest --no-build-cache` | BUILD SUCCESSFUL — `InvariantSuiteIT` **9 tests**, 0 failures. Forced: an unforced attempt reports `UP-TO-DATE`, which is a replay, not a run |
| `:modules:ui:integrationTest` | BUILD SUCCESSFUL — **9 tests across 4 suites**, 1 skipped, 0 failures (includes `SchemaMismatchStatusContractTest`). The earlier "16 across 5" was a stale-directory miscount, as the reviewer said |
| `:modules:worker-core:test` (named explicitly — the brake tests live there) | BUILD SUCCESSFUL — included in the full-suite totals above; run standalone as well |
| `:modules:indexer-worker:test --tests "*BrakeExhaustedWorkerServesReadOnlyTest*"` | BUILD SUCCESSFUL — the emit-chain test boots a real `KnowledgeServer`, so this is the first tier in this tempdoc above "compiles and unit-tests" |
| Full kernel: `governance/run.mjs --produce-inputs --mode gate` | 33 pass, 1 fail — `ts-any`, **inherited**. (Two gates went red during this round and were FIXED, not baselined: `prose-tier-register` wanted a register row for the new `falsify-restore-from-backup` rule anchor — row 47 added; `config-surface` correctly reported `vectorHnswM` / `vectorHnswEfConstruction` as accessors no production code calls, because the bench nit moved its last two callers to the effective accessors — the effective accessors now call them.) All 5 findings are `ts-any/silent-growth` in files this branch does not touch (`citationResolve.test.ts`, `MarkdownBlock.ts`, `indexingProgress.ts`, `sv3-sessions.test.ts`, `searchResultViewModel.ts`); pinned as `ts-any-gate-counts-english-prose` (the gate scores the English word "any" in comments). `readinessNotice.ts`, the one ui-web file this branch edits, is not among them. |
| `check-readiness-reason-codes` | OK — 55 emittable codes, 49 worded rows (was 54/48: the new code is wired on both sides) |
| `check-live-witness` · `check-store-recoverability` · `check-search-degradation-reason-codes` · `check-language-agnostic-analysis` · `check-tempdoc-numbers` · `check-premerge-table` | all OK |
| `docs/verify-canonical-doc-links.mjs` · `llmstxt-generate --check` · `skills-sync --check` · `verify-runtime-config-matrix` | OK (156 files) · OK (115 docs) · OK (5 skills) · OK (yaml=111, pairs=250, rows=306) |
| `docs-validate.mjs` | exit 1, **inherited** — repo-wide `heading-case` advisories, pinned as `docs-validate-heading-case-repo-wide`; no finding names a heading this branch touched |
| `run-ui-web-gates.mjs` (the `ui-web-gates` recipe) | **40/40 passed** |
| `cd modules/ui-web && npm run typecheck` | exit 0 |
| `cd modules/ui-web && npm run test:unit:run` | 468 files, **6267 tests passed** |
| Diff hygiene | NUL bytes in the diff: 0. Every added non-ASCII line is an intended em-dash / `§` / `→`; no `Ã` / `â€` / `Â` mojibake. No whole-file CRLF rewrite — `--numstat` shows large adds only for genuinely new files. |

**Not run, and why.** No live-stack verification: the brief forbids starting the dev stack, the eval backend, or any JVM on the shared ports, and the orchestrator owns those resources. The blue/green loop is therefore verified at the state-machine and policy-branch level only — recorded as open item O3, not as a passed tier.

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

1. **O1 — CLOSED (owner decision, review round).** `grammar_ver` / `template_ver` stay: they are
   observability with live consumers, and retiring them is separate work if it ever happens. The
   index's identity does not depend on them (§C.6).
2. **O2 — TRACKED, owner: UI/wire lane.** The proto/FE field names still say `schema_fp` /
   `index_schema_fp_*` (`contracts/wire/status.proto:213-223,429-435`,
   `modules/ipc-common/src/main/proto/indexing.proto:764-777`). They name a concept
   `index_fingerprint` still is, so they are correct-but-dated rather than wrong. Renaming is a
   follow-up lane D does not own; the comment relabel promised by §C.11 is done here (§D.23).
3. **O3 — SCHEDULED.** The full blue/green loop end-to-end (Blue serving live queries while Green
   ingests, then a real cutover) is verified at the state-machine and policy-branch level here, not
   with a running Worker. The programme owner schedules the live run after lane E's measurement
   window closes; the reviewer's 5-arm procedure is the script.
4. **O4 (routed, pre-existing).** Phase 3's consumer list for `chunk_content` is wrong:
   `HighlightingOps` never reads it, and three of the four cited `RagContextOps` lines read whole-doc
   `CONTENT` (§B.2 D3/D4). Phase 3 must re-derive that list rather than trust the brief.
5. **O5 (routed, pre-existing, latent).** The vector-dimension override was applied only to the
   commit path's instance, never to the two comparison paths (§B.1 claim 1c). Fixed here as a
   side-effect; recorded because it is the exact shape of defect the one-fingerprint design exists
   to prevent, and it survived undetected only because the guard was off.
6. **O7 — OPEN, and it limits what this PR delivers. The blue/green trigger is not reached on the
   ordinary boot path.** Found while making the S10 test honest, verified by direct execution rather
   than by reading:

   - The normal branch opens the active generation with `openDeferred()` whenever it has segments
     (`KnowledgeServer.java`, `useDeferredWriter = hasLuceneSegments(activeIndexPath)`), and
     `RuntimeSession` maps `Mode.DEFERRED` to `openReadOnly = true`.
   - `ComponentsFactory.build:110-122` swallows a guard failure when `readOnly`, logging
     `"Index open guard reported a mismatch at {} but continuing in read-only mode"`.
   - So a boot whose index shape genuinely changed does **not** raise `SCHEMA_MISMATCH` from the
     initial open. The mismatch surfaces later, from `DeferredRuntime.upgradeWriter()` inside
     `initDeferredModels()`, whose `catch (Exception e)` logs
     `"Background model initialization failed (non-fatal)"`.

   Consequence: on the path most installs take, the guard WARNs, the writer upgrade fails silently,
   ingestion is dead, and **no migration starts** — the status surface still reports
   `BLOCKED_MISMATCH`/`reindex_required`, so the user is told, but the automatic blue/green rebuild
   this PR makes the production default does not run. What lands here is still correct and necessary
   (the fingerprint is truthful, the guard enforces where it is consulted, the brake is bounded and
   no longer a dead end, and the resumed-migration branch is now covered — §D.24, G23). What is NOT
   yet true is "the Worker rebuilds by itself on the common boot".

   The fix is a design decision I am not taking unilaterally, because the read-only swallow exists
   for a good reason (Blue must tolerate a mismatch while Green rebuilds): either `Mode.DEFERRED` is
   distinguished from `Mode.READ_ONLY` at `ComponentsFactory:110` so a rebuild-requiring mismatch
   propagates from a deferred open, or `initDeferredModels` stops treating `SCHEMA_MISMATCH` from
   `upgradeWriter()` as non-fatal and routes it into the same handler. Owner decision required.

7. **O6 — correction to my own earlier report.** I reported
   `BatchUpdateIntegrationTest.concurrentRmwOnSameDocIdSerializedByCoordinator_402` as an unpinned
   load flake and asked whether to pin it. That was wrong: it is already pinned
   (`adapters-lucene-batchupdate-rmw-coordinator-load-flake`), as is the `OnnxEmbeddingEncoder`
   long-doc forensic case. No pin is needed and none was added.

## Report-back

See the PR body and §F/§G. Extended by Phases 2 and 3.
