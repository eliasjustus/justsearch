---
title: "Lane D: one truthful index fingerprint, stable document identity, and the reindex bundle"
type: tempdocs
status: "PHASE 1 MERGED; PHASE 2 PR-A AND PR-B IMPLEMENTED AND LOCALLY VERIFIED; PHASE 3 PR-C0 IMPLEMENTED AND SHORT-CHECKED, PR-C2/PR-C1 PENDING IN A SEPARATE STACKED DRAFT (2026-09-05). PR-C0 six-corpus evaluation and PR-C1 evidence campaign: see tempdoc 931 §B rows 3a-3d."
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
records what re-verification found. Phase 1 is merged. Phase 2 PR-A and PR-B are implemented and
locally verified. Phase 3 PR-C0 is implemented and short-checked; PR-C2 and PR-C1 follow in a
stacked draft (tempdoc 931 §B row 4), and the deferred evidence campaigns still block merge.

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

### O7 round — the mismatch decision no longer depends on the open mode

- [x] R-O7a. **Pre-open detection.** `KnowledgeServer.start()` reads the last commit's user data off
      the directory and diffs it BEFORE choosing an open mode, then dispatches to the existing policy
      handler. Same `ParityDiagnostics` call as the guard — one predicate, not a fork (§C.12).
- [x] R-O7b. The `ComponentsFactory` guard stays as a second line, and is now genuinely second: it
      can no longer be the only line, because the decision has already been made.
- [x] R-O7c. `initDeferredModels()`'s `catch (Exception)` no longer files a `SCHEMA_MISMATCH` under
      "non-fatal". It is reported loudly, with the reason and the remedy (§C.13 says why loudly
      rather than propagated).
- [x] R-O7d. Six boot-level tests (a-e) plus the classifier, each falsified (§F round 4).
- [x] R-O7e. `11-index-schema-migration.md`: the caveat is replaced by the mechanism. O7 CLOSED.

### Phase 2 — stable document identity (PR-A AND PR-B IMPLEMENTED)

- [x] B1. Mint `doc_uid` once per logical document; preserve across API-supported rename,
      re-extraction, and full reindex.
- [x] B2. Persist the `path_hash`→uid map in SQLite next to the existing path store (ADR-0028:
      hash-keyed reverse lookups); decide and document which file.
- [x] B3. Deterministic chunk uids: `uid + "#" + chunkIndex`.
- [x] B4. Worker admission carries the store-resolved UID through `IndexingDocumentOps` and
      `ChunkDocumentWriter`; `GrpcIngestService` re-keys it around API path updates; `KnowledgeServer`
      imports serving-index identities before normal or migration indexing starts.
- [x] B5. PR-B moves new feedback/GPL writes to `doc_uid` keys. Accepted compatibility rule:
      **no path-to-uid backfill** for pre-Phase-2 rows; legacy path-keyed rows remain readable as
      legacy data, while newly projected feedback/triples use uid keys.
- [x] B6a. PR-A test sources cover durable mint/reopen/import, distinct paths with equal content,
      API rename, delete/reindex, v10→v11 migration and rollback, chunk uid determinism, serving-
      index boot import, Blue→Green preservation, retry/idempotency, and fail-closed behavior.
      Execution evidence is recorded separately from this implementation checklist.
- [x] B6b. PR-B owns the remaining label-store-survives-full-rebuild row.

### Phase 3 — the reindex bundle, one migration for users (PR-C0 IMPLEMENTED; PR-C2/PR-C1 PENDING)

- [ ] C1. Quantized vectors by default, with jseval nDCG@10 / recall@50 evidence (delta ≤ 1%
      absolute), index size and RSS before/after; binary-quantized HNSW on `chunk_vector` as a
      report-only experiment.
- [ ] C2. Pin vector similarity: add `vector.similarity: dot_product` to both catalog copies,
      construct the field with an explicit `FieldType`, add a unit-norm encoder test, recalibrate
      the 702 thresholds with jseval evidence, update `SsotValidatorFingerprintTest`.
- [ ] C3. Stop storing `chunk_content` (`stored:false`, still indexed); slice the parent `content`
      by `chunk_start_char`/`chunk_end_char`; measure the per-hit stored-field cost.
- [ ] C4. Delete the `entity_*_text` fields and the entity text-boost path; keep facets on
      `entity_*_raw`; tell lane B for ADR-0007's amendment. PR-C0 has already retired the functional
      `entity_boost` configuration/query path while preserving the public status field as a zeroed
      compatibility tombstone; physical field deletion remains PR-C2.
- [x] C5a. Replace the English stop-word list with a field-local document-frequency signal, move the
      decision into `SearchPlanner`, and report deliberate dense skips truthfully with typed reasons.
- [ ] C5b. Verify comparable per-language skip rates and no material quality loss on the six
      pre-registered multilingual eval corpora. This remains required before PR-C0 may merge.
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

### §C.12 Detection happens before the open, not inside it

The defect O7 named: the mismatch decision depended on HOW the index was being opened. The active
generation takes `openDeferred()` whenever it has segments; `RuntimeSession` maps `Mode.DEFERRED` to
a read-only open; and `ComponentsFactory` only *logs* a guard failure when `readOnly`. So on the boot
path most installs take — an existing index, with documents, whose shape changed — nothing was
raised and no migration started. The status surface still said `reindex_required`, which is exactly
why it survived two review rounds: the user was told, so nothing looked broken.

**The question is about the bytes on disk, so it is answered from the bytes on disk.**
`IndexMetadataParityGuard.inspectCommittedParity(path, expected)` opens an `FSDirectory` and a
`DirectoryReader`, reads `getIndexCommit().getUserData()` and `numDocs()`, and calls the same
`ParityDiagnostics.diff(stored, expected, docCount)` every other consumer calls. No writer, no
`RuntimeSession`, no open-mode choice — and no second implementation, which is what keeps the
legacy-blank rule (§C.5a), the empty-index exclusion and the model tri-state (§C.5) identical at both
sites by construction rather than by agreement. `checkOnOpen()` was refactored to call it, so there
is literally one implementation.

Dispatch, once a rebuild-requiring diff is found:

| Policy | Pre-open action | Why |
|---|---|---|
| `BLUE_GREEN_MIGRATE` | raise `SCHEMA_MISMATCH` | the existing boot handler already builds Green beside a read-only Blue, brake included |
| `FAIL_CLOSED` | raise `SCHEMA_MISMATCH` | the same handler rethrows; refusing is the policy |
| `REBUILD_BACKUP_FIRST` | do **not** raise; force a WRITABLE open | its backup-then-rebuild recovery lives in `RuntimeSession.openComponentsWithRecovery` and is the one implementation of that policy. Duplicating it here to satisfy a symmetry would be the fork this whole tempdoc is about |

**Ordering, verified rather than assumed.** The expected fingerprint needs the model providers and
the effective vector dimension installed first. `installModelFingerprintProviders` was already early
enough (`KnowledgeServer.java:555`, right after `logConfiguration()`), but
`installEffectiveVectorDimension` was **not** — it lived inside `buildIndexRuntime`, which does not
run until after the point where pre-open detection now happens. Left alone, a BGE-M3 install would
have compared a boot-time fingerprint computed with the catalog's declared 768 against a stored one
written at 1024, and migrated every boot. It is hoisted to the same early site
(`effectiveVectorDimensionSupplier()`), so there is now exactly one install for both inputs, before
any comparison.

### §C.13 A mismatch from the deferred writer upgrade is not "non-fatal"

The second half of O7. `DeferredRuntime.upgradeWriter()` runs inside `initDeferredModels()`, whose
`catch (Exception e)` logged everything as `"Background model initialization failed (non-fatal)"`.
A `SCHEMA_MISMATCH` there is not a degraded capability — it is a stopped ingestion pipeline, because
the index cannot accept writes under this runtime's shape.

It is now **reported loudly rather than propagated**, and the choice is deliberate: this runs on a
background `CompletableFuture` with no caller left to receive an exception, `start()` having long
returned. Propagation would mean inventing a channel (a flag polled by the sentinel, a callback into
the boot handler) whose only job is to carry a condition the next boot's pre-open detection handles
correctly anyway — and the status surface already reports `BLOCKED_MISMATCH` with `reindex_required`
from the same fingerprint comparison, so the user is not waiting on a restart to be told. What was
missing was never the propagation; it was that the line said "non-fatal".

### §C.14 What moving the decision earlier broke

Two blockers, both from the same root: a check that used to run *inside* the open now runs *before*
it, so it lost the envelope the open provided and gained a decision the open never had to make.

**B4 — an unreadable commit is not a mismatch.** `inspectCommittedParity` raised `CORRUPT_INDEX`
when the directory could not be read. Inside the open that was harmless — `RuntimeSession`
caught it two frames up and ran backup-then-empty recovery, which ships enabled
(`index.auto_recovery: true`). At the new call site there is nothing above it but `start()`, so a
corrupt index that used to self-heal at boot killed the Worker instead. And because the cause of an
unreadable commit is an `IndexFormatTooOldException`, the same throw swallowed the legitimate
older-Lucene-major upgrade path. The method now answers one of *three* things, not two: match,
mismatch, or **could not read** — the third is a WARN naming the cause class and an empty diff list,
and the open decides (recovery, format upgrade, or the second-line guard). Every one of
`CorruptIndexException`, `IndexFormatTooOld/TooNew` and `IndexNotFoundException` is an
`IOException`, so one catch covers them, and `ComponentsFactory` classifies corruption on the
reader/writer open independently — nothing is lost by declining here.

**B5 — a mismatched Green must be abandoned, not retried.** Round 3 hoisted the schema-mismatch
`try` over the resumed-migration branch, which was necessary and not sufficient.
`IndexGenerationManager.startMigration` deliberately no-ops while a migration is in flight, so the
handler re-resolved the *same* Green and re-opened it with the builder that had just thrown: the
second `SCHEMA_MISMATCH` was raised inside the catch, uncaught, and `start()` died — on attempts 1,
2 and 3, i.e. three dead Workers before the brake that exists to bound exactly this repetition could
report anything. `abandonBuildingGeneration(reason)` is the missing move: clear
`building_generation`, return `migration_state` to `IDLE`, mark the directory for deletion, carry
the auto-rebuild budget over unchanged (abandoning a Green is not evidence the rebuild converged, so
it must not refresh the brake). One attempt is then spent and a fresh Green allocated — or, if the
budget is gone, the read-only Blue fall-through runs as before. `start()` returns in every case.

**Ride-along.** The catch opened a second read-only runtime on Blue when the resumed branch had
already opened one, overwriting the field without closing it — a leaked `Directory` +
`SearcherManager` holding Windows handles on Blue for the Worker's whole lifetime, in the
brake-exhausted state, which is the state that keeps running. Blue is held in a local and reused.

**Unknown policy values.** `normalizeSchemaMismatchPolicy` returned an unrecognised value verbatim,
which used to be merely inert. After §C.12 it was not: pre-open detection forces a writable open for
any policy it does not recognise, the guard raises, recovery refuses the destructive rebuild and the
Worker fails to start. A typo in one config key must not be a boot failure — it falls back to the
mode default with a WARN, in the config layer, so every consumer sees the same answer.

**Empty-index symmetry.** The `docCount` exclusion was applied only where the *stored* fingerprint
was blank, so a 0-document index with a *stale* one still took the changed branch and would have
spent a full blue/green migration rebuilding nothing. Both branches consult
`ParityDiagnostics.holdsNothingToMigrate(docCount)` now, and `IndexStatusOps` reports through it, so
the guard and the status surface cannot disagree about an empty index in one direction after
agreeing in the other. What actually happens to such an index: `CommitOps.setLiveCommitData`
replaces the whole user-data map, so the next commit **re-stamps** it.

### §C.16 The status surface describes the generation being SEARCHED

Live validation (2026-09-03) found three defects on one wire, and one cause behind all of them:
`IndexStatusOps` was fed entirely from the **ingest** runtime, which is the wrong authority for
every question the compatibility surface answers.

| Field | What it said | What it means |
|---|---|---|
| `indexSchemaFpStored` / `indexSchemaCompatState` / `reindexRequired` | mid-migration: Green's fingerprint, hence `COMPATIBLE` / `false` | the shape of the index the user's queries reach — that is Blue |
| the same three, braked | `""`, and `""` routes to `BLOCKED_LEGACY` if the brake check ever stops shadowing it | there is no ingest runtime at all in that state, so the supplier was null |
| `indexedDocuments` | `jobQueue.completedCount()` — DONE rows in `jobs.db`, pruned, unrelated to corpus size (observed: 5, against 205 searchable) | documents in the generation being written, or, when none is being written, the one being served |

The fix is the wiring, not a special case. `storedVectorFormat`, `openTimeCommitUserData` and
`latestCommitUserDataBestEffort` come from `searchLifecycle`, which is non-null in every state
including braked and deferred; `docCount` falls back to the serving reader before it falls back to a
queue counter. The **mid-migration contract the reviewer asked to be pinned then falls out of the
ordinary comparison**: Blue carries the old shape, so `BLOCKED_MISMATCH` + `schema_mismatch` +
`reindexRequired` hold for as long as the rebuild runs, next to `migration.state = MIGRATING`. There
is no `MIGRATING` value in the compat vocabulary and adding one would be worse than useless —
`WorkerSnapshotTap`'s `SCHEMA_COMPAT_TABLE` maps exactly `BLOCKED_LEGACY` and `BLOCKED_MISMATCH`, and
an unmapped value takes the preserve-prior + WARN-once branch, which FREEZES a stale condition
(tempdoc 726 F5's documented failure mode). `docCount` still reports Green while Green exists: that
is the build's progress, and it is the one number that legitimately describes the generation being
written.

This also settles §C.13's argument for reporting rather than propagating a deferred-upgrade mismatch.
That argument rested on "the status surface already reports `BLOCKED_MISMATCH` with
`reindex_required`", which was true only while no migration was in flight. It is true in every state
now — but it was the claim that was wrong, not the sentence, and the comparison is what was fixed.

### §C.17 A refusal is not a crash

`FAIL_CLOSED` did its job live — the index was left byte-identical — and the user was told the Worker
had crashed. `WorkerFatalReasonMarker` existed for exactly this and carried one value,
`index_corrupt`, written only when `isCorruptIndexCause(e)`; a `SCHEMA_MISMATCH` refusal fell through
to a bare `System.exit(1)`, so the Head reported `Worker process crashed (exit code 1) before writing
port to signal file` with `/api/health` unreachable and the real cause visible only in `worker.log`.

`index_schema_mismatch` joins the vocabulary, written from the same `catch` via the classifier §C.13
already added, read by the same `KnowledgeServerBootstrap.workerDownCode` funnel, and worded as
`worker.index_schema_mismatch`. A separate code rather than reusing `worker.index_corrupt` because
the remedy differs: the index is intact and the wrong shape, so the answer is a policy that permits a
rebuild, not a corruption repair.

### §C.18 What a self-restart takes with it

The cutover restart is the only shutdown the Worker performs on its own, and it is not a `stop()`:
the monitor calls `initiateShutdown()`, which shuts the gRPC server and lets `main` unwind. Two facts
were lost in that window, both because they were left to a later step:

- the **clean-shutdown marker**, so every boot after a cutover logged `Unclean previous shutdown
  detected` for the freshly promoted generation and paid a FULL integrity verification for an index
  that had just been committed and verified;
- the **metrics snapshot**, whose cadence is 60 s against a restart ~20 s after the migration starts,
  which is why `commit_by_reason` never carried `migration/cutover` in a live run despite both emit
  sites being production code (D4).

`preserveEvidenceBeforeRestart` writes both at the point they are true — immediately after the
promotion — instead of hoping the shutdown sequence completes first. Best-effort and independent: a
failure costs an integrity scan or a counter, never a cutover.

### §C.15 New/changed config keys

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
| D.31 | **O7.** `IndexMetadataParityGuard`: `inspectCommittedParity(Path, Supplier<Map<String,Object>>)` and `schemaMismatch()` extracted; `checkOnOpen()` now calls both, so the pre-open and open-time paths share one implementation and one message. (The `Supplier` is G30's fix: an eager `Map` parameter built the expected metadata before the index-exists check.) | `IndexMetadataParityGuard.java:72`, `:119` |
| D.32 | **O7.** `KnowledgeServer.start()`: pre-open detection before the open-mode choice, and dispatch | `KnowledgeServer.java:608` (`preOpenMismatch`), `:611` (`inspectCommittedParity`), `:613` (`requiresRebuild`), `:653` (`policyHandledInCatch`), `:656` (raise), `:658` (`useDeferredWriter … && !preOpenMismatch`), `:751` (the existing handler) |
| D.33 | **O7.** `installEffectiveVectorDimension` hoisted out of `buildIndexRuntime` to the early install site, so the boot-time comparison sees the same dimension as every later one under BGE-M3 | `KnowledgeServer.java` — `effectiveVectorDimensionSupplier()`, installed beside `installModelFingerprintProviders` |
| D.34 | **O7.** `logBackgroundInitFailure(Exception)` + `isSchemaMismatch(Throwable)`: a schema mismatch escaping the deferred writer upgrade is reported as stopped ingestion, not as a non-fatal background failure | `KnowledgeServer.java:1008`, `:1026`, called at `:1546` |
| D.35 | **O7 tests.** `WorkerBootFixture` (shared boot scaffolding: production catalog, seed with matching / foreign / absent fingerprint, config publication, layout) and `PreOpenSchemaMismatchBootTest` (a-e plus the classifier) | both new, `modules/indexer-worker/src/test/.../server/` |
| D.24 | **Delta-review round (B3).** `KnowledgeServer.start()` no longer returns on brake exhaustion: it sets `rebuildBrakeExhausted`, opens Blue read-only, and falls through the rest of the sequence. Five previously skipped sites and what each does now — `createGrpcServer` + `grpcServer.start()`: run, so the port is real; `signalBus.writePort(boundPort)`: runs, so the Head discovers the Worker; `infraCtx`/`appServices` construction: runs, which is what builds `GrpcIngestService` and with it `IndexStatusOps`, the only producer of the new reason code; `appServices.startIndexingLoop()`: deliberately NOT started (guarded flag) with an ERROR naming the recovery path, because its only job is to write into a read-only runtime; `startSentinelThread()`: runs. `drainSwitchBufferBestEffort()` is also skipped — a read-only runtime is not a `RunningRuntime`, so it could only have logged a WARN and dropped the ops | `KnowledgeServer.java` — the `rebuildBrakeExhausted` field, the brake branch, the drain guard, the loop guard |
| D.25 | **The second defect, found by the test written for the first.** `IndexStatusOps.buildCore` dereferenced `indexingLoop` unconditionally. With no loop started that NPEs, and `GrpcIngestService.indexStatus` catches `RuntimeException` and returns a stub response with `core.state=ERROR` and NO compatibility sub-message — so `BLOCKED_REBUILD_BRAKE` was still unreachable, now silently. Null-guarded. This was ALSO a pre-existing latent hole: `DefaultWorkerAppServices.startIndexingLoop` already guards for a null loop (deferred-writer mode), so a status RPC arriving before the writer upgrade could blank the whole payload | `IndexStatusOps.java` — `buildCore`'s `setLastCommitTimestamp` |
| D.26 | **S10.** `BrakeExhaustedWorkerServesReadOnlyTest` — boots a real `KnowledgeServer` over a seeded generation layout with an exhausted brake, then asserts over gRPC: `isRunning()` + `getPort() > 0`; `schemaCompatState=BLOCKED_REBUILD_BRAKE` and `reindexRequiredReason=rebuild_brake_exhausted` and `reindexRequired`; a `Search` RPC answered from Blue; and `startMigration` + `promoteBuildingGenerationToActive` clearing the brake | `BrakeExhaustedWorkerServesReadOnlyTest.java` (new) |
| D.27 | **S12.** `SchemaMismatchPolicyBranchTest` gains a `withoutFingerprint()` source and `seedLegacyIndex`, and runs the key-absent fixture through all three policy values | `SchemaMismatchPolicyBranchTest.java` |
| D.28 | **S13.** `ChunkSplitter` (in `modules:indexing`, already an `api` dep of adapters-lucene) owns `CHUNK_THRESHOLD_CHARS` and `CONTENT_PREVIEW_MAX_CHARS`. `SsotCommitMetadataSource` reads them; its mirror and `ChunkWriterFingerprintMirrorTest` are deleted (with them the phantom `ChunkDocumentWriterFingerprintInputsTest` name); `ChunkDocumentWriter` re-exports them the way it already re-exported `CHUNK_TARGET_TOKENS`; two further private `4096` copies in `IndexingDocumentOps` and `GrpcIngestService` now point at the one owner | `ChunkSplitter.java`, `SsotCommitMetadataSource.java`, `ChunkDocumentWriter.java`, `IndexingDocumentOps.java`, `GrpcIngestService.java` |
| D.29 | **Nits.** `majorMinor()` truncates the analysis versions to `major.minor`; `EngineVectorIndexBench` reports the effective HNSW accessors instead of its own 16/200; the wire test parses `tags.reason` into a map instead of substring-matching a line; `resetUncomputableWarnedForTest()` makes the once-per-boot latch testable and `InvariantSuiteIT` asserts one WARN across three opens; `LEGACY_INDEX_HINT` no longer claims the index predates the key (it cannot know that); `ParityDiagnostics.diff` takes `docCount` and both consumers call `isIndexWithoutRecordedFingerprint` | see §F round 3 |
| D.30 | **New tests for the nits.** `SchemaCompatFreshInstallTest` (a fresh empty index is COMPATIBLE, an index holding documents of unrecorded shape is BLOCKED_LEGACY); `ParityGuardTest.anEmptyIndexWithoutAFingerprintIsNotAMigrationCandidate`; `FingerprintInputSourcesTest.aPatchLevelLibraryBumpDoesNotMoveTheFingerprint` and `theChunkFingerprintInputsComeFromTheSplitterNotFromACopy`; `InvariantSuiteIT.aFreshEmptyIndexWithNoFingerprintIsNotMigrated` and `theUncomputableFingerprintWarningIsEmittedOncePerBoot` | see §F round 3 |
| D.36 | **B4.** `inspectCommittedParity` no longer raises `CORRUPT_INDEX`. One `catch (IOException)` — which covers `CorruptIndexException`, `IndexFormatTooOld/TooNew` and `IndexNotFoundException` — WARNs with the cause class and returns an empty diff list, so the open path keeps its corruption recovery, its format upgrade and its second-line guard. `isCorruption` and three imports swept with it | `IndexMetadataParityGuard.java:89-111` |
| D.37 | **B5.** `IndexGenerationManager.abandonBuildingGeneration(reason)`: clears `building_generation`, returns `migration_state` to `IDLE`, marks the directory for deletion (after the pointer write, so a crash leaves an orphan the GC reaps rather than a pointer into a deleting directory), and carries the auto-rebuild budget over unchanged | `IndexGenerationManager.java:273` |
| D.38 | **B5.** The schema-mismatch handler abandons a mismatched Green before it retries, so `startMigration` allocates a fresh one instead of no-opping and handing back the generation that just threw. `start()` returns with Blue read-only whether the budget is fresh or spent | `KnowledgeServer.java:763-773` (the `inProgress` abandon, `:772`), `:777` (`blue`) |
| D.39 | **B5 ride-along.** Blue is held in a local (`blueReadOnly`, `KnowledgeServer.java:627`) and reused by both catch branches, instead of a second read-only runtime being opened over the first and the first leaked | `KnowledgeServer.java:627`, `:777`, `:799`, `:815` |
| D.40 | **S15.** `normalizeSchemaMismatchPolicy`'s `default` branch falls back to the mode default with a WARN instead of returning an unrecognised value verbatim — the shape `normalizeIntegrityCheck` already used | `ResolvedConfigBuilder.java:1009-1039` |
| D.41 | **Nit.** `ParityDiagnostics.holdsNothingToMigrate(docCount)`: the empty-index exclusion now applies to the changed branch as well as the blank one, and `IndexStatusOps` reports through the same predicate | `ParityDiagnostics.java`, `IndexStatusOps.java` (`safeSchemaCompatState`) |
| D.42 | **Round-4 tests.** `ResumedMigrationMismatchBootTest` (B5 at a FRESH budget — the boot the brake test cannot reach); `PreOpenSchemaMismatchBootTest` +4 (`PRE-OPEN` WARN asserted under FAIL_CLOSED (S14); `REBUILD_BACKUP_FIRST` backs Blue up before emptying it, asserted on the backup's doc count (S15); an unrecognised policy boots; a corrupt index still self-heals (B4)); `ParityGuardTest` +3 (unreadable commit, empty-index-with-stale-fingerprint, expected-metadata build count on an existing index); `SchemaCompatFreshInstallTest` +1; `ResolvedConfigBuilderTest` +1. `BrakeExhaustedWorkerServesReadOnlyTest` rewritten onto `WorkerBootFixture` (S16) and drives the `startMigration` RPC rather than the generation manager (S17) | see §F round 4 |
| D.43 | **B4 consequence.** `RecoveryIntegrationTest`'s "Gap D" assertion inverted: the parity guard is still invoked on the open path and must no longer raise `CORRUPT_INDEX`. The test's outcome assertions (backup, fresh index, writes accepted) are untouched and are what proves the open path still recovers. Also `SchemaMismatchStatusContractTest` seeds one document — its fixture committed an EMPTY index with a bogus fingerprint, which the empty-index rule now (correctly) reports COMPATIBLE | `RecoveryIntegrationTest.java`, `SchemaMismatchStatusContractTest.java` |
| D.44 | **D1/D3.** `GrpcIngestService`: `storedVectorFormat`, `openTimeCommitUserData` and `latestCommitUserDataBestEffort` are read from `searchLifecycle`, not `ingestLifecycle`. `queryVectorFormatActual` and `configuredVectorFormat` stay on ingest — one is a query-path fact, the other is config | `GrpcIngestService.java` (the `IndexStatusOps` construction) |
| D.45 | **D2.** `docCount` falls back to the SERVING reader before `jobQueue.completedCount()`. The building-generation branch is untouched, so a migration still reports Green's progress | `IndexStatusOps.java` (`buildStatusResponse`) |
| D.46 | **FAIL_CLOSED visibility.** `WorkerFatalReasonMarker.INDEX_SCHEMA_MISMATCH`; written from `KnowledgeServer`'s outer `catch` via `isSchemaMismatch(e)`; read by `KnowledgeServerBootstrap.workerDownCode` into `LifecycleReasonCode.WORKER_INDEX_SCHEMA_MISMATCH` (`STICKY`, same class as its corruption sibling) with a remedy detail; worded in `readinessNotice.ts` and added to the impairing set | `WorkerFatalReasonMarker.java`, `KnowledgeServer.java`, `KnowledgeServerBootstrap.java`, `LifecycleReasonCode.java`, `readinessNotice.ts` |
| D.47 | **Cutover restart + D4.** `preserveEvidenceBeforeRestart(context, promoted)` writes the clean-shutdown marker for the promoted generation and flushes the metrics snapshot, immediately after the promotion and before `initiateShutdownAction`. New `flushTelemetryAction` component on `CutoverContext`, wired to `KnowledgeServer.flushTelemetryBestEffort` | `KnowledgeServerMigrationOps.java`, `KnowledgeServer.java` |
| D.48 | **Nit.** The unreadable-commit WARN is latched on `path\|exceptionClass`, so one unreadable index produces one line across the pre-open check and the open-time guard — and a second, different unreadable generation still says so | `IndexMetadataParityGuard.java` |
| D.49 | **Round-5 tests.** `MidMigrationCompatSurfaceTest` (a real `GrpcIngestService` over two different runtimes — a wiring test, because handing `IndexStatusOps` the values under test is the mistake itself); `CutoverRestartEvidenceTest`; `BrakeExhaustedWorkerServesReadOnlyTest` +count/fingerprint assertions and a second case that clears `auto_rebuild_*` from `state.json` by hand and asserts the next boot migrates as a MISMATCH rather than as a legacy index; `PreOpenSchemaMismatchBootTest` asserts the fatal-reason marker under FAIL_CLOSED and exactly ONE unreadable-commit WARN | see §F round 6 |
| D.50 | **O14.** `CleanShutdownMarker.consumeWasClean` split into `wasClean` (non-destructive) + `consume`; `ComponentsFactory`'s dirty-open escalation only reads, and the consume happens at `new IndexWriter`. New `CleanShutdownMarkerLifecycleTest`: five consecutive read-only boots leave the marker in place, a writable open clears it, a clean close re-writes it, and a writer that never closes leaves it absent | `CleanShutdownMarker.java`, `ComponentsFactory.java` |
| D.51 | **R1, the latch.** `KnowledgeServerBootstrap`: `latchedIndexFatalVerdict` remembers whichever fatal index verdict `workerDownCode` reads out of the one-shot marker, and re-offers it once the marker is gone; `isIndexFatal()` widens the `supervisionVerdictHeld()` carve-out from `WORKER_INDEX_CORRUPT` to both codes; `indexFatalCode()` / `indexFatalDetail()` expose it. The latch is cleared by a capability listener on READY — registered in the constructor rather than at the two sites that write READY today, so a future READY path inherits it | `KnowledgeServerBootstrap.java` — the field, the ctor listener, `workerDownCode`, `latchIndexFatal`, `isIndexFatal`, `transitionWorkerDown` |
| D.52 | **R1, the ladder decision (made, not inherited).** Corruption did **not** short-circuit the respawn ladder before this — `BootRecoveryDecision.decide()` had vetoes for `clientBound` / `gaveUp` / `restartExhaustedHeld` / `supervisionActive` and nothing else — so the coordinator's "the same way corruption does, **if** corruption does" resolved to a decision rather than a copy. Decision: **yes, for both axes.** New `Veto.INDEX_FATAL` + `Input.indexFatalHeld`, ranked below supervision's terminal verdict and above the attempt budget. It reads the bootstrap LATCH, not `pendingReason()`: the marker read can land inside a suppressed arc, and gating on the wire state would make the veto depend on whether anyone had spoken | `BootRecoveryDecision.java` |
| D.53 | **R1, the narration + the hatch.** `KnowledgeServerHealthMonitor`: `currentRecoveryInput(operatorRequested)`; `gaveUpVeto` records WHICH veto latched the give-up; `narrateGiveUp`'s `INDEX_FATAL` arm stamps the latched cause when the capability does not already hold it (the case where the whole boot arc was suppressed and nothing else will ever say it). An operator request withholds the veto **and** re-opens that one give-up — the budget and the supervision vetoes are untouched, because the documented remedy for both fatal index causes is a settings or filesystem change the next spawn reads | `KnowledgeServerHealthMonitor.java` |
| D.54 | **R1, the user-facing string.** `HeadlessApp.startErrorFor(bootstrap, e)` prefers the latched remedy over `summarizeStartError(e)`, so `knowledgeServerStartError` — rendered verbatim in the 503 body — names the refusal instead of "Worker process crashed (exit code 1) before writing port to signal file". The `:540-542` narration comment now enumerates both fatal index codes and the latch | `HeadlessApp.java` |
| D.55 | **R1 tests.** New `SchemaMismatchFatalArcTest` (7 cases over a real bootstrap + a real monitor: the suppressed-attempt arc, the ladder short-circuit, STICKY through recovering/exhausted, the supervision-guard carve-out, the give-up narrating a swallowed cause, the READY clear, and a no-marker control) and `HeadlessAppStartErrorTest` (3). `BootRecoveryDecisionTest` +4 ranking cases; `StatusLifecycleWorkerReasonTest` +1 wire-projection case driving the full live sequence | see §F round 8 |
| D.56 | **R2 — a wrong-gate inside R1's own fix.** The `INDEX_FATAL` give-up skipped its capability write when the reason slot already held the cause. That compared the REASON and ignored the HEALTH, so an operator-requested attempt that re-refused left the capability parked at the `RECOVERING` the arm had set before the spawn — `readinessNotice.ts` renders that as "recovering" for a condition that never recovers on its own, and live R2 watched it sit there for 120 s. The write is unconditional now; it cannot double-narrate, because `WorkerCapability.transition` fires listeners only when the health OR the effective reason changes and the sticky reason is retained | `KnowledgeServerHealthMonitor.java` — `narrateGiveUp`'s `INDEX_FATAL` arm |
| D.57 | **R2 — the second half.** A failed attempt only `return`ed, so nothing re-asked the decision until the next tick. `settleAfterFailedAttempt()` re-runs the same pure function on the same executor thread and narrates through the same funnel. Scoped to `Veto.INDEX_FATAL`: it is the only veto whose cause is known-terminal the instant the attempt fails, and pulling the budget-exhaustion give-up forward would change a timing `KnowledgeServerBootRecoveryTest.arcGivesUpOnceAfterTheBudget` pins — which is exactly how that scoping error was caught (the unscoped first cut turned that test red) | `KnowledgeServerHealthMonitor.java` |
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

**E9. The O7 round's own finding, and who found it.** Extracting the guard's inspection into a
reusable static changed *when* the expected metadata is built: the original method checked the index
existed before calling the supplier, and the extracted version called it first. Nothing in the O7
work noticed — not the six new boot tests, not the critical read of the diff. What noticed was
`CommitMetadataIntegrationTest.metadataSourceSupplierInvokedPerBuild`, a three-year-old assertion
that the supplier is invoked exactly once per commit, in the full suite (§F G30). That is the
argument for running the whole suite rather than the affected modules: the tests that catch a
refactor's side effects are, by definition, not the tests you were thinking about.

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
| F1 | `REBUILD_REQUIRING_KEYS` → `Set.of()` | `ParityGuardTest`, `SchemaMismatchPolicyBranchTest`, `InvariantSuiteIT` | four tests red, not three — `parityGuardTriggersRebuildOnFingerprintMismatch` FAILED; `aLegacyIndexWithNoFingerprintIsMigratedRatherThanIgnored` FAILED; `blueGreenMigratePropagatesTheMismatchAndKeepsEveryDocument` FAILED; `failClosedRefusesAndKeepsEveryDocument` FAILED (cited by NAME, not line: later rounds inserted fixtures above both and the line numbers this row originally carried drifted) |
| F2 | drop `boosts_fp` from `PARITY_KEYS` | `ParityGuardTest` | `parityGuardCatchesBoostsMismatch` FAILED (by name, same reason as F1) |
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

### Round 7 — O14, the clean-shutdown marker's owner

### Round 8 — R1, the refusal that never reached the user

Driver `tmp/915-r1-falsify.mjs` (deleted before commit), same contract. It caught itself once: the
first run reported six DRIVER ERRORs, because `execFileSync` cannot launch a `.bat` without
`shell: true`. That is postmortem #29's zero-XML invariant doing exactly its job — the six cases
would otherwise have read as "every break survived".

| ID | Break | Test that caught it | Observed failure |
|---|---|---|---|
| G53 | drop the latch fallback in `workerDownCode` — the marker is one-shot again | `SchemaMismatchFatalArcTest` | three cases red. `the attempt that consumed the marker was suppressed; without the latch the ONE narrating call reports worker.spawn.failed … ==> expected: <worker.index_schema_mismatch>`; and `STICKY means the ladder's narration changes the HEALTH and leaves the cause alone ==> expected: <worker.index_schema_mismatch> but was: <worker.spawn_recovery_exhausted>` — the terminal string the validator saw, verbatim |
| G54 | narrow the supervision-guard carve-out back to `WORKER_INDEX_CORRUPT` | `SchemaMismatchFatalArcTest` | `the guard's carve-out covered worker.index_corrupt only, so the refusal was logged as 'not overwriting supervision's verdict' and thrown away` |
| G55 | remove the `INDEX_FATAL` veto — the ladder spends its budget again | `SchemaMismatchFatalArcTest`, `BootRecoveryDecisionTest` | `expected: <GIVE_UP> but was: <STAND_DOWN>`; `the worker wrote its refusal to disk, so every attempt re-reads the same bytes … expected: <GIVE_UP>`; and the arc case `expected: <worker.index_schema_mismatch> but was: <worker.recovering>` — i.e. an attempt was spent |
| G56 | stop the give-up narrating a cause the boot arc swallowed | `SchemaMismatchFatalArcTest` | `expected: <worker.index_schema_mismatch> but was: <worker.not_connected>` — the shape where the whole boot arc was suppressed and nothing else will ever say it |
| G57 | never clear the latch on READY | `SchemaMismatchFatalArcTest` | `READY is where the latch is dropped; this assertion fails if only the capability's ReasonRetention clears and the bootstrap keeps its copy ==> expected: <null>`. The anti-staleness direction is pinned too: without it an OOM death an hour later is reported as the old schema mismatch |
| G58 | `knowledgeServerStartError` falls back to the spawn symptom | `HeadlessAppStartErrorTest` | `the user must be told which setting produced the refusal; got: Worker process crashed (exit code 1) before writing port to signal file` — the live string, character for character |
| G59a | **R2.** restore the reason-only guard on the give-up stamp | `SchemaMismatchFatalArcTest` | `expected: <DEGRADED> but was: <RECOVERING>` — the live symptom exactly: the reason slot was right the whole time and the HEALTH was the lie |
| G59b | **R2.** stop settling after a failed attempt — wait for the next tick | `SchemaMismatchFatalArcTest` | same assertion. Both halves are load-bearing: the guard made the write a no-op, and without the settle nothing calls the funnel until a tick lands |

| ID | Break | Test that caught it | Observed failure |
|---|---|---|---|
| G50 | make the read-only open consume the marker again | `CleanShutdownMarkerLifecycleTest` | `boot 1: a read-only open writes nothing, so it cannot make the index unclean — and must not report that it did ==> expected: <true> but was: <false>` |
| G51 | stop consuming at the writer open — a crash would look clean | `CleanShutdownMarkerLifecycleTest` | `the writer consumed it ==> expected: <false> but was: <true>`; `a writer exists now, so this session CAN die mid-commit … ==> expected: <false> but was: <true>`. Both directions pinned: the fix must not turn a real crash into a clean boot |
| G52 | re-fuse the read and the invalidation inside `wasClean()` | `CleanShutdownMarkerLifecycleTest` | same first assertion — the separation is the fix, not the call site |

### Round 6 — the live-validation defects

Driver `tmp/falsify5.mjs` (deleted before commit), same contract as round 5.

| ID | Break | Test that caught it | Observed failure |
|---|---|---|---|
| G41 | D1: read the stored commit metadata off the INGEST runtime again | `MidMigrationCompatSurfaceTest`, `BrakeExhaustedWorkerServesReadOnlyTest` | `the stored shape reported is the one the user's searches reach ==> expected: <bbbb…> but was: <a4a0b1e6…>` (Green's own fingerprint, which is the live defect verbatim); `the stored fingerprint is Blue's, not the empty string … ==> expected: <64> but was: <0>`; `the braked worker reports the shape Blue actually carries ==> expected: <ffff…> but was: <>` |
| G42 | D2: fall back to `jobQueue.completedCount()` again when nothing is being written | `BrakeExhaustedWorkerServesReadOnlyTest` | `Blue holds documents, so the braked worker must not report zero ==> expected: <true> but was: <false>` |
| G43 | D2 the other way: report the SERVING count even while a Green is being built | `MidMigrationCompatSurfaceTest` | `documents indexed still counts GREEN while one is being built … ==> expected: <1> but was: <2>`. Both directions are pinned, so the fix cannot drift into hiding a rebuild's progress |
| G44 | stop writing the fatal-reason marker for a refused schema mismatch | `PreOpenSchemaMismatchBootTest` | `the dying worker must say WHY it refused, in the channel the Head reads ==> expected: <index_schema_mismatch> but was: <null>` |
| G45 | stop writing the clean-shutdown marker before the cutover restart | `CutoverRestartEvidenceTest` | `the promoted generation has just been committed and verified — the next boot must not pay a FULL integrity scan for it ==> expected: <true> but was: <false>` (both cases) |
| G46 | stop flushing telemetry before the cutover restart | `CutoverRestartEvidenceTest` | `and the cutover's own counters are written before they are lost ==> expected: <true> but was: <false>` |
| G47 | log the unreadable-commit WARN unlatched again | `PreOpenSchemaMismatchBootTest` | `… and say it ONCE: the pre-open check and the open-time guard ask the same question of the same bytes …` |
| G48 | anti-fixture: make the brake never latch | `BrakeExhaustedWorkerServesReadOnlyTest` | `precondition: the boot actually took the exhausted-brake path ==> expected: <true> but was: <false>` and `precondition: the brake is spent` — i.e. every count and fingerprint assertion in that file is guarded by a precondition that the state under test was actually reached |
| G49 | drop the Head's marker→reason-code mapping | `KnowledgeServerWorkerDownCodeTest` | **First run: NO FAILURE OBSERVED — nothing observed the Head half.** The Worker's write and the vocabulary were pinned; the mapping that turns the marker into `worker.index_schema_mismatch` was not, which is the `substrate-without-consumer` shape. A case was added, and the same break now reds it: `expected: <worker.index_schema_mismatch> but was: <worker.spawn.failed>` — the exact string the live validator saw |

### Round 5 — B4, B5 and the round-4 review items

Driver `tmp/falsify4.mjs` (deleted before commit), rebuilt on postmortem #29's remedy: byte
copy-aside backups, a per-case anchor assertion, per-case result wipes, and a final byte-compare
proving the restore. It grew a second guard this round, from its own failure — see below.

| ID | Break | Test that caught it | Observed failure |
|---|---|---|---|
| G31 | B4: restore the `CORRUPT_INDEX` throw in `inspectCommittedParity` | `PreOpenSchemaMismatchBootTest` | `aCorruptIndexStillSelfHealsAtBoot` FAILED — `java.io.IOException: Failed to start KnowledgeServer`, which is the blocker verbatim: a corrupt index that recovers today takes the Worker down instead |
| G32 | B5: stop abandoning a mismatched Green before retrying | `ResumedMigrationMismatchBootTest` | `aResumedMigrationWithAMismatchedGreenStartsOverInsteadOfKillingTheWorker` FAILED — `java.io.IOException: Failed to start KnowledgeServer`. At a FRESH budget, i.e. boot #1 of 3 |
| G33 | B5 ride-along: open a second read-only runtime on Blue instead of reusing the first | `ResumedMigrationMismatchBootTest` | `Blue is opened once, and reused: the handler must not open a second runtime over it ==> expected: <1> but was: <2>` |
| G34 | S14: stop emitting the `PRE-OPEN` marker, routing untouched | `PreOpenSchemaMismatchBootTest` | `the pre-open routing must be what refused; got: [Starting KnowledgeServer…, …]` — the assertion the reviewer asked for, and it distinguishes pre-open routing from the second line, which the old `isSchemaMismatch`+`IDLE` pair did not |
| G35 | S15: return an unrecognised policy value verbatim again | `ResolvedConfigBuilderTest`, `PreOpenSchemaMismatchBootTest` | `expected: <REBUILD_BACKUP_FIRST> but was: <blue-green-migrat>`; and `anUnrecognisedPolicyFallsBackInsteadOfKillingTheWorker` FAILED with `Failed to start KnowledgeServer` — the boot failure the pass-through now causes, not merely inertness |
| G36a | empty-index nit, guard half: drop the exclusion from the CHANGED branch | `ParityGuardTest` | `an index with no documents has no content whose shape could be wrong ==> expected: <true> but was: <false>` |
| G36b | empty-index nit, status half: report `BLOCKED_MISMATCH` regardless | `SchemaCompatFreshInstallTest` | `a stale shape recorded against no documents describes nothing … ==> expected: <COMPATIBLE> but was: <BLOCKED_MISMATCH>` |
| G37 | S15: stop forcing a writable open for `REBUILD_BACKUP_FIRST` | `PreOpenSchemaMismatchBootTest` | `the mismatched index must be backed up, never deleted: this policy empties the active generation and the backup is the user's only copy ==> expected: not <null>` |
| G38 | S17: make the `startMigration` RPC reject | `BrakeExhaustedWorkerServesReadOnlyTest` | `the operator rebuild is reachable from here: BREAK G38 ==> expected: <true> but was: <false>` — which is the point of S17: arm (d) now fails when the WIRE fails, where the fixture call could not have noticed |
| G39 | S16 interrogation: seed through `FieldCatalogDef.forTesting(768)`, the catalog the inline copy used | — | **NO FAILURE OBSERVED, and that is the honest answer.** The stamped fingerprint comes from `SsotCommitMetadataSource` either way, so the forked catalog changed nothing observable here. S16 is drift prevention, not a live defect — recorded as such rather than claimed as a fix |

| G40 | (not injected — a real consequence of B4, caught by the full suite) `RecoveryIntegrationTest.corruptIndexAutoRecoveryProducesBackupAndFreshIndex` asserted the parity guard was what re-classified the read failure as `CORRUPT_INDEX` ("Gap D wiring proof") | `RecoveryIntegrationTest` | `parity guard should have surfaced an IndexRuntimeIOException(CORRUPT_INDEX) … Observed exceptions: [] ==> expected: <true> but was: <false>`. Every OUTCOME assertion in the same test still passed — backup taken, fresh index served, new writes accepted — so what B4 removed is a redundant classification, not the recovery. The mechanism assertion is inverted rather than deleted: the guard must **not** raise, because the identical throw from the pre-open site is the blocker |

**Two harness defects, both caught by the driver's own guards rather than by luck.**

1. Every case reported `NO FAILURE OBSERVED` on the first run. The cause was not the code: `execSync`
   runs through `cmd.exe`, where `./gradlew.bat` does not resolve, so nothing ran — and the driver
   had just wiped the results directory, so "no failures found" and "no tests found" were the same
   observation. This is postmortem #29's *other* half, and the fix is a rule the driver now enforces:
   **a case with zero result XMLs is a DRIVER ERROR, never a survived break.** With that assertion in
   place the second run reported nine driver errors instead of nine false negatives, which is what
   sent us to look at the invocation (`.\gradlew.bat` inside a JS template literal is a third bug —
   `\g` is not an escape, so the string became `.gradlew.bat`; an absolute path settled it).
2. `SchemaCompatFreshInstallTest.anEmptyIndexWithAStaleFingerprintIsCompatible` survived G36b when it
   was first written. It passed for the wrong reason: `buildStatus()` passed `null` for the
   open-time commit user-data supplier, so `safeSchemaFingerprintStored()` returned `""` and the case
   routed through the BLANK branch, never reaching the comparison it exists to pin. The test now
   supplies the stale snapshot explicitly, and G36b reds.

### Round 4 — the O7 fix

| ID | Break | Test that caught it | Observed failure |
|---|---|---|---|
| G24 | pre-open detection removed (`preOpenMismatch = false`) — i.e. back to deciding inside the open | `PreOpenSchemaMismatchBootTest` | `an index whose shape changed must start migrating at boot …` FAILED; `aLegacyIndexWithSegmentsMigratesAtBoot` FAILED; `aChangedShapeOnAnIndexWithSegmentsIsRefusedUnderFailClosed` FAILED with `Expected java.io.IOException to be thrown, but nothing was thrown` |
| G25 | FAIL_CLOSED dropped from `policyHandledInCatch` | — | **NO FAILURE OBSERVED, and the diagnosis is the result.** With the pre-open raise gone the open is still forced writable, so the open-time guard raises and the same handler refuses: the property survives on the second line. Recorded rather than discarded — it is the defence-in-depth §C.12 claims, demonstrated |
| G25b | both lines broken (`policyHandledInCatch` narrowed AND `useDeferredWriter` no longer forced off) | `PreOpenSchemaMismatchBootTest` | `aChangedShapeOnAnIndexWithSegmentsIsRefusedUnderFailClosed` FAILED — `Expected java.io.IOException to be thrown, but nothing was thrown` |
| G26 | detection over-triggers (`preOpenMismatch = true`) | `PreOpenSchemaMismatchBootTest` | `a matching index must not be migrated — a detector that fires on everything is not a detector ==> expected: <IDLE> but was: <MIGRATING>`; `aFreshEmptyIndexDoesNotMigrateAtBoot` FAILED |
| G27 | the deferred-upgrade mismatch filed as non-fatal again | `PreOpenSchemaMismatchBootTest` | `a stopped ingestion pipeline is not a degraded capability; got: [Background model initialization failed (non-fatal), …]` |
| G28 | `isSchemaMismatch` stops at the top-level exception | `PreOpenSchemaMismatchBootTest` | `schemaMismatchIsRecognisedThroughTheCauseChain` FAILED (`expected: <true> but was: <false>`); two more, including `FAIL_CLOSED must refuse for the schema-mismatch reason, not some incidental failure` |
| G29 | the empty-index exclusion dropped from the shared predicate | `PreOpenSchemaMismatchBootTest` | `a first launch must not spend a rebuild on an index with nothing in it (the shared ParityDiagnostics predicate …)` FAILED — the point being that breaking it in `ParityDiagnostics` breaks the NEW site too, which is what "one predicate" is supposed to mean |

| G30 | (not injected — this one was a real defect, caught by an existing test) the extracted `inspectCommittedParity` took a built `Map` instead of a `Supplier`, so `checkOnOpen` built the expected metadata BEFORE checking whether the index exists — an extra catalog hash and model-digest read on every open of a fresh index | `CommitMetadataIntegrationTest` | `metadataSourceSupplierInvokedPerBuild` FAILED — `expected: <2> but was: <3>`, i.e. the supplier ran once per commit plus once for a parity check with nothing to compare. Fixed by making the parameter a lazy `Supplier` |

**G25 is the useful one.** A break that produces no failure is a claim about the harness or about
the code, and this time it was about the code: the property held because a second, independent
mechanism enforced it. That is worth knowing and worth writing down — but it is not a passing
falsification, so G25b breaks both lines and gets the real answer. The alternative, quietly recording
G25 as "caught", is how a redundant guard becomes an unexamined assumption.

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

The harness half of that is recorded as `falsify-restore-from-backup` in `agent-postmortems.md` #29 — deliberately there and not in `agent-lessons.md`, whose always-loaded ratchet had zero headroom (`check-always-loaded-budget` failed the first attempt by exactly the bytes the new bullet added).

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
Re-run in full after O14; every line below is from that run. The integration-test counts in the previous version of this table were wrong — they were read from a results directory holding an earlier run's XML, which is the same stale-artefact mistake the round-3 falsification harness made (§F). Counts here are from a `cleanIntegrationTest` run.

| Command | Result |
|---|---|
| `spotlessApply -PskipWebBuild=true` | exit 0 |
| `build -x test -PskipWebBuild=true` | BUILD SUCCESSFUL |
| `cleanTest test -PskipWebBuild=true --no-build-cache --continue` | BUILD SUCCESSFUL in 4m 9s — **1460 suites, 8912 tests, 0 failures, 0 errors, 26 skipped**. The run before it was RED, usefully: `RecoveryIntegrationTest.cleanCloseWritesMarkerAndOpenConsumesIt` encoded the superseded contract (ANY open consumes), which is the defect O14 names. Renamed and re-pointed at the writer, with the read-only half asserted the other way (counted from `TEST-*.xml`, not from the console). The run before this one was RED, and usefully so: `RecoveryIntegrationTest` caught a real consequence of B4 that no targeted run would have (§F G40) — the same value the O7 round got from G30 |
| `cleanIntegrationTest :modules:indexing:integrationTest --no-build-cache` | BUILD SUCCESSFUL — `InvariantSuiteIT` **9 tests**, 0 failures. Forced: an unforced attempt reports `UP-TO-DATE`, which is a replay, not a run |
| `:modules:indexer-worker:test --tests "*PreOpenSchemaMismatchBootTest*"` | BUILD SUCCESSFUL — **10** boot-level cases now (a-e, the classifier, and the four added this round: the FAIL_CLOSED `PRE-OPEN` WARN, `REBUILD_BACKUP_FIRST` backup-before-empty, an unrecognised policy, a corrupt index self-healing). Boot-level because every one of them passes at unit level against the defect it pins |
| `:modules:indexer-worker:test --tests "*ResumedMigrationMismatchBootTest*"` | BUILD SUCCESSFUL — B5 at a FRESH budget, the boot the brake test structurally cannot reach |
| `:modules:ui:integrationTest` | BUILD SUCCESSFUL — **9 tests across 4 suites**, 1 skipped, 0 failures (includes `SchemaMismatchStatusContractTest`). The earlier "16 across 5" was a stale-directory miscount, as the reviewer said |
| `:modules:worker-core:test` (named explicitly — the brake tests live there) | BUILD SUCCESSFUL — included in the full-suite totals above; run standalone as well |
| `:modules:indexer-worker:test --tests "*BrakeExhaustedWorkerServesReadOnlyTest*"` | BUILD SUCCESSFUL — the emit-chain test boots a real `KnowledgeServer`, so this is the first tier in this tempdoc above "compiles and unit-tests" |
| Full kernel: `governance/run.mjs --produce-inputs --mode gate` | 35 gates evaluated, 34 pass, 1 fail (`test-efficacy` skipped) — `ts-any`, **inherited**. (Two gates went red during this round and were FIXED, not baselined: `prose-tier-register` wanted a register row for the new `falsify-restore-from-backup` rule anchor — row 47 added; `config-surface` correctly reported `vectorHnswM` / `vectorHnswEfConstruction` as accessors no production code calls, because the bench nit moved its last two callers to the effective accessors — the effective accessors now call them.) All `ts-any` findings are `silent-growth` in files this branch does not touch (`citationResolve.test.ts`, `MarkdownBlock.ts`, `indexingProgress.ts`, `sv3-sessions.test.ts`, `searchResultViewModel.ts`); pinned as `ts-any-gate-counts-english-prose` (the gate scores the English word "any" in comments). `readinessNotice.ts`, the one ui-web file this branch edits, is not among them. |
| `check-readiness-reason-codes` | OK — **56 emittable codes, 50 worded rows** (was 55/49: `worker.index_schema_mismatch` is wired on both sides, and the producer direction confirms an emit site exists) |
| `check-live-witness` · `check-store-recoverability` · `check-search-degradation-reason-codes` · `check-language-agnostic-analysis` · `check-tempdoc-numbers` · `check-premerge-table` | all OK |
| `docs/verify-canonical-doc-links.mjs` · `llmstxt-generate --check` · `skills-sync --check` · `verify-runtime-config-matrix` | OK (156 files) · OK (115 docs) · OK (5 skills) · OK (yaml=111, pairs=250, rows=306) |
| `docs-validate.mjs` | exit 1, **inherited** — repo-wide `heading-case` advisories, pinned as `docs-validate-heading-case-repo-wide`; no finding names a heading this branch touched |
| `run-ui-web-gates.mjs` (the `ui-web-gates` recipe) | **40/40 passed**, re-run this round: `readinessNotice.ts` gained the `worker.index_schema_mismatch` row, so the trigger fired and the recipe was run rather than carried forward |
| `cd modules/ui-web && npm run typecheck` | exit 0 |
| `cd modules/ui-web && npm run test:unit:run` | 468 files, **6267 tests passed** |
| `:modules:worker-services:test --tests "*MidMigrationCompatSurfaceTest*"` | BUILD SUCCESSFUL — the D1 wiring test, driving a real `GrpcIngestService` over two different runtimes |
| `:modules:indexer-worker:test --tests "*CutoverRestartEvidenceTest*"` | BUILD SUCCESSFUL — 2 cases, including the independence of the two facts when the flush throws |
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
6. **O7 — CLOSED.** The blue/green trigger was unreachable on the ordinary boot path: the active
   generation opens deferred, a deferred open is a read-only open, and `ComponentsFactory` logs a
   guard failure rather than raising it. Fixed by moving detection ahead of the open-mode choice
   (§C.12) and by refusing to file a deferred-upgrade mismatch as non-fatal (§C.13). Verified at boot
   level, not unit level, because the defect was never in a unit: §F G24-G29, including the
   legacy-index case that was the real upgrade path this broke.

7. **O8 — CLOSED (round-4 review, B4).** Moving detection ahead of the open took it outside the
   corruption-recovery envelope, so a corrupt index that used to self-heal at boot killed the
   Worker — and the same throw swallowed the older-Lucene-major upgrade path. Pre-open inspection is
   non-fatal on any unreadable commit now (§C.14); §F G31, plus G40, which is the more interesting
   evidence: the existing `RecoveryIntegrationTest` caught the consequence in the full suite before
   any targeted run would have.
8. **O9 — CLOSED (round-4 review, B5).** A resumed migration whose Green was itself mismatched
   killed `start()` on attempts 1-3, because `startMigration` no-ops on an in-flight migration and
   the handler retried the same generation. The Green is abandoned and rebuilt now, one attempt is
   spent, and Blue is reused rather than re-opened (§C.14); §F G32, G33.
9. **O10 — OPEN, and it is the reason O3 matters.** Every property in §C.14 is verified at boot
   level against a real `KnowledgeServer` over a real generation layout, which is a strictly higher
   tier than the unit level the defects hid below — but still not a running Worker under a real
   corpus. The reviewer's 7-arm live procedure, which the programme owner schedules, is the tier
   that can falsify the ordering claims (backup taken before the writer touches Blue; Blue serving
   throughout a real cutover) rather than merely observing their file-system traces.

10. **O11 — ROUTED, owner lane C / the 885 successor.** The braked ingest queue is unbounded and
    silent. Live arm 3: with ingestion stopped the watcher re-enqueued the whole corpus
    (`pendingJobs = 200`), a newly created file took it to 201, and it stayed there for 90 s with
    `searchableDocuments` pinned — no cap, and no backpressure anywhere on the status surface. After
    recovery all 201 drained correctly, so this is a missing bound, not a leak.
11. **O12 — ROUTED, owner dev-tooling lane.** `core.rebuild-index` needs a two-phase confirm that no
    document mentions: `POST /api/operations/core.rebuild-index/invoke` returns
    `CONFIRMATION_REQUIRED` (gate `TYPED_CONFIRM`, risk `HIGH`) with a `pendingId`, which must be
    approved via `POST /api/authorizations/approve` and re-invoked with a `confirmationToken`; under
    prod every mutating call also needs `X-JustSearch-Session` from `GET /api/mcp/token`. Neither
    `mcp-dev-tools.md` nor the api-contract-map says so, and the live validator had to discover it.
12. **O13 — ROUTED, owner lane C. Pre-existing, not this PR.** Every deferred-open boot over an
    existing index logs 6-8 of `Lucene health check failed: SearcherManager not available (runtime
    closed?)`; fresh-index boots log none. `SearcherBridge.java` is byte-identical between
    `39d38f73` and this branch, so it predates the change.
13. **O14 — CLOSED (owner decision).** A read-only open consumed the clean-shutdown marker and
    never wrote one back, so a Worker serving Blue read-only for its whole life — a migration, and
    every boot of the braked state — left Blue permanently marked unclean and paid a FULL integrity
    verification on every subsequent boot (`g-20260903-052152`, five consecutive boots). This was a
    wrong-gate: the marker guards against a WRITER dying mid-commit, and a read-only open has no
    writer, so it can neither dirty the index nor earn the right to invalidate the evidence. Reading
    the answer and invalidating it are separate acts now (`wasClean` / `consume`); the consume moves
    to the `new IndexWriter` site, which is the moment an unclean death becomes possible, and sits
    outside the integrity-tier block because whether the next boot SCANS is a different question
    from whether this session could dirty the index. §F G50-G52.

14. **O6 — correction to my own earlier report.** I reported
   `BatchUpdateIntegrationTest.concurrentRmwOnSameDocIdSerializedByCoordinator_402` as an unpinned
   load flake and asked whether to pin it. That was wrong: it is already pinned
   (`adapters-lucene-batchupdate-rmw-coordinator-load-flake`), as is the `OnnxEmbeddingEncoder`
   long-doc forensic case. No pin is needed and none was added.

## Report-back

**Phase 1 is merged. Phase 2 PR-A is implemented and locally verified; PR-B and Phase 3 remain
pending.** The accepted Phase 2 design, split, and PR-A evidence are captured in §P2 below.

### PRs

- **#620** — `feat(915): one truthful index fingerprint, blue/green as the production default (lane D
  phase 1)`. **Merged** at `b9b1c2c0`. It carries the whole of Phase 1 plus the wave-1 fold-ins and
  eight rounds of review/validation fixes. Phase 2 is intentionally split into Worker-side PR-A and
  Head-side PR-B (§P2.D); Phase 3 remains separate.

### Items: done, deviated, skipped

**Done as specified:** A1-A3, A5-A13 and W1-W5 (§A checkboxes, evidence per row in §D).

**Deviated, with the reason recorded where the deviation lives:**

- **A4** — `SsotCommitMetadataSource` no longer sources the index's identity from
  `SSOT/versions/catalog.json`, but `grammar_ver` / `template_ver` were **not** deleted: they are
  observability with live consumers, and the index's identity does not depend on them (§C.6, O1 —
  closed by owner decision). The coupling is cut; the fields stay.
- **A2 input list** — grew during the review round beyond the original draft: `ner_model_sha256`,
  `threshold_chars`, `preview.max_chars`, `ChunkSplitter.ALGORITHM_VERSION`, and
  `analysis.lucene_version` / `analysis.icu_version` were added, and HNSW `m`/`ef_construction` are
  hashed as the **effective** values rather than the raw nullable config. Each addition is a physical
  input that was missing, not scope creep; the `lucene_version`/`icu_version` pair is deliberately
  coarse (one rebuild per library bump) and says so in `11-index-schema-migration.md`.
- **A9** — the brake bounds auto-rebuilds per `index_fingerprint` (`MAX_AUTO_REBUILD_ATTEMPTS = 3`),
  and the exhausted state **serves Blue read-only** rather than refusing to start. That is more than
  "rate-limit"; it came out of delta-review B3 and is what makes the state observable at all.

**Skipped, and why:** nothing on the Phase 1 list. The two things NOT done are scheduled work, not
skips: the live blue/green loop was open item O3 until the validator ran it (now closed by the
2026-09-03 arms), and the size/RSS measurements belong to Phase 3.

### Evidence

- **Static:** §G (full kernel 35 gates / 1 inherited `ts-any` fail; `check-readiness-reason-codes`
  56 emittable / 50 worded; ui-web 40/40; 6267 FE unit tests; full JVM suite green under
  `cleanTest --no-build-cache`).
- **Falsification:** §F, F1-F5 and G30-G59b — every new or modified guarantee broken once, watched
  fail with the observed assertion text, restored from byte copies. Two driver defects were caught by
  the driver's own zero-XML invariant rather than by luck (§F rounds 5 and 8).
- **Live, by an independent validator (not the implementer):**
  - `419aadb7` — the seven-arm run at `12955fe9`. Arms 0, 2-6 PASS; arm 1 produced D1-D4.
  - `51c7e1c2` — re-validation at `403f4b30`. Arms 1, 3, 4, 5, 6 PASS (D1-D4, O14, the
    budget-cleared-by-hand path and the legacy upgrade all confirmed live); one FAIL, R1.
  - `56e75cd7` — arm 2 re-run at `c06d8b25`. All ten assertions PASS; one residual, R2, fixed here.
  - Arm 2's headline fact: under `FAIL_CLOSED` the index was left **byte-identical** — `state.json`
    SHA-256 `E3BF2686…` before and after, 26 index files identical by name and size.

### Measurements

The only Phase 1 measurement is the **fingerprint input list itself** — what is in the hash and what
is deliberately out. It is documented as current truth in
`docs/explanation/11-index-schema-migration.md` § "Index fingerprint (`index_fingerprint`)": in are
the catalog schema version, the per-field physical projection, the analyzer definitions,
`vector_format`, effective HNSW `m`/`ef_construction`, the chunking parameters + splitter algorithm
version, `preview.max_chars`, `analysis.lucene_version`/`icu_version`, and the three model shas; out
are `rmwPolicy` annotations, all query-time scoring (BM25 `k1`/`b`, boosts, `ef_search`), and the
search-intent grammar / prompt packs / templates.

**No index-size or RSS numbers are reported, by design** — they are Phase 3's subject, and quoting a
number here that nothing measured would be worse than the gap.

**The search-quality register was not updated, because Phase 1 changed no number.** Every exclusion
above is exclusion of a *query-time* lever from an *index-identity* hash: retrieval behaviour,
fusion, reranking and the eval baselines are untouched. The register is for numbers that moved.

### Cross-lane

- **Lane E — authorised, four lines, after #620 merges.** `SsotCommitMetadataSource` reads the
  chunking constants from `ChunkSplitter`; lane E may change those reads to the `effectiveChunk*()`
  accessors. It is a mechanical four-line edit and lane D has no objection — but it must land
  **after** #620, not as a conflicting concurrent edit, because the same file is rewritten by A4.
  Also standing: `ChunkSplitter.ALGORITHM_VERSION` is new and additive; bump it when the splitting
  *algorithm* changes with token counts unchanged (token counts are already fingerprint inputs).
- **Lane B** — RISK-011 instrumented at `tempdoc:915#C`, deliberately left **Monitoring**.
- **Lane C** — Phase 1 touches `IndexGenerationManager` (worker-core) and `IndexStatusOps`
  (worker-services), outside lane D's declared file list but on the migration/status path.
- **Lane F** — two things to know. (1) The worker↔Head fatal-reason channel now carries **two**
  codes, `index_corrupt` and `index_schema_mismatch`, and the Head **latches** either on read because
  the marker is one-shot; anything new that consumes `WorkerFatalReasonMarker` must not assume a
  second read is possible. (2) `BootRecoveryDecision` gained `Veto.INDEX_FATAL`, so a fatal index
  cause now short-circuits the respawn ladder for **both** axes — a behaviour change on the
  corruption axis too, and the operator hatch is the documented exemption.

### Residue routed

- **O2** (proto/FE field names still say `schema_fp`) → UI/wire lane, TRACKED.
- **O8** (braked ingest queue is unbounded and silent) → lane C / 885 successor.
- **O9** (`TYPED_CONFIRM` + `X-JustSearch-Session` for `core.rebuild-index` undocumented) →
  dev-tooling lane.
- **O10** (`SearcherManager not available` health-check noise on deferred-open boots, pre-existing)
  → lane C.
- **D4** (`commit_by_reason` never carries `migration/cutover` live) → 912 metrics lane; the
  ≤30-line half that was in files lane D owns (the cutover flush) is done here.
- **`falsify-restore-from-backup`** → postmortem #29 + the `agent-lessons.md` handle list, paid for
  by a trim elsewhere in that file (the byte budget is ratcheted).

### What Phase 2 and Phase 3 must know

1. **`index_fingerprint` is now the single rebuild-requiring key.** Adding an input costs every user
   a rebuild; adding a query-time lever must cost nothing. If Phase 2 or 3 introduces a physical
   input, it goes in the hash **and** in the `11-…md` list, which is the doc a reader is entitled to
   treat as complete.
2. **The production default is `BLUE_GREEN_MIGRATE`.** A change that moves the fingerprint no longer
   bricks a boot; it starts a migration the user pays for in disk and time. Phase 3's size work
   should measure that cost, since it is now the default path.
3. **The compatibility surface describes the generation being SEARCHED**, never the one being
   written. Four fields were wired the wrong way round and the live run caught it; the rule and its
   two exceptions are documented.
4. **Tri-state model fingerprints: indeterminate is not a mismatch.** Any new model input must
   preserve that, or an unconfigured model becomes a rebuild trigger.
5. **The repeat-rebuild brake bounds Phase 3's experiments too.** Three auto-rebuilds against the
   same fingerprint and the worker serves Blue read-only until an operator intervenes.

## Live product validation (2026-09-03)

Independent live validation of PR #620 at head `12955fe9` (round 5), by a validator who is not the
implementer. Seven arms from the round-4 reviewer's written procedure, run against the **already
built** Head + Worker dists in this worktree — no rebuild, no production-code edit. Artefacts and
per-arm logs under `tmp/915-live/` (gitignored): `logs/<tag>-env.txt` (the exact env each arm
booted with), `logs/<tag>-worker.log`, `artefacts/<tag>-*.json`.

### Arm table

| Arm | Purpose | Verdict | Evidence pointer |
|---|---|---|---|
| 0 | Build Blue at a synthetic old fingerprint (`JUSTSEARCH_INDEX_VECTOR_HNSW_M=32`, prod) | **PASS** | `logs/arm0-worker.log`, `artefacts/arm0-api_status.json`; 205 docs, `indexSchemaFpStored = indexSchemaFpCurrent = 9814df37…`, `COMPATIBLE`, `state.json` IDLE with no `auto_rebuild_*` |
| 1 | Blue/green cutover as the prod default; the O7 headline | **PASS (6/8), 1 FAIL, 1 not verifiable** | `logs/arm1-worker-migration.log`; see D1 and D4 below |
| 2 | `FAIL_CLOSED` refuses without destroying anything | **PASS** | `logs/arm2-*`; `state.json` byte-identical (SHA-256 `E3BF2686…` before and after), 26 index files identical by name+size |
| 3 | Rebuild brake, read-only serving, `core.rebuild-index` recovery | **PASS (7/7)** | `logs/arm3-driver.log`, `logs/arm3-b4-recovery-worker.log`; two side findings D2, D3 |
| 4 | O7 deferred-open regression plus the negative re-boot | **PASS** | `logs/arm4b-worker.log` (PRE-OPEN on the 2nd boot), `logs/arm4c-worker.log` (0 PRE-OPEN, 0 PARITY_DIFF, 0 migrations) |
| 5 | Legacy (`39d38f73`) index upgrade: migrate once, clean 2nd boot | **PASS** | `logs/arm5-upgrade-worker.log` (`stored=<missing>`), `logs/arm5-second-worker.log` (0 PRE-OPEN) |
| 6 | B4 corrupt index (round-5 expectations) | **PASS** | `logs/arm6-corrupt-worker.log`; the Worker self-heals and starts — B4 is fixed live |

**Headline.** O7 is real on the boot path installs actually take. On an index **with segments** — the
`openDeferred()` path that pre-O7 swallowed the mismatch on — the pre-open detection fires and starts
the migration, in three independent shapes: a changed shape (arm 1, arm 4), a legacy index with no
recorded fingerprint (arm 5), and a refusal under `FAIL_CLOSED` (arm 2). The detector is not
trigger-happy: with the shape unchanged it emits nothing (arm 4c, arm 5 second boot).

Verbatim, arm 1 (`logs/arm1-worker-migration.log`), the O7 evidence line **before** the handler:

```
07:26:06.436 WARN PRE-OPEN PARITY_DIFF key=index_fingerprint stored=9814df37…3a3 expected=02353ae7…23b hint="The effective index shape changed (…). Reindex or run schema migration."
07:26:06.578 WARN Schema mismatch detected on active generation …\indices\g-20260903-052152. Starting Blue/Green migration (policy=BLUE_GREEN_MIGRATE, attempt 1 of 3)...
```

`FAIL_CLOSED` is refused from the **pre-open** site, not the open-time guard — the S14 distinction,
observed live (`logs/arm2-worker.log`): the stack is
`IndexMetadataParityGuard.schemaMismatch(IndexMetadataParityGuard.java:120)` under
`KnowledgeServer.start(KnowledgeServer.java:656)`, which is the pre-open throw, and the index is left
byte-identical.

**B4 and B5 are fixed, verified live.** Arm 6: an 8-junk-byte `segments_5` no longer kills the
Worker. `inspectCommittedParity` logs the non-fatal WARN and hands the question to the open, which
recovers:

```
07:48:07.282 WARN Could not read committed parity metadata at …\g-20260903-054724 (IndexFormatTooOldException: …)
07:48:07.344 WARN Corrupted index detected at …. Auto-recovery enabled, attempting backup-first rebuild...
07:48:07.453 WARN Index at … was recovered to empty (reason=corrupt_index). Rebuilding from source via blue/green...
```

`/api/health` reachable, no `worker-fatal-reason`, and the doc count returns to its pre-corruption
value (55) with search restored (`totalHits` 55 to 55). B5: the brake ladder (arm 3) produced
`auto_rebuild_count` 1, 2, 3 on three **live** Workers, none of which died; boot 4 hit the brake and
kept serving. No boot in the campaign wrote a `worker-fatal-reason` marker — not even the deliberate
`FAIL_CLOSED` arm.

**Fingerprint determinism.** The same config produced the same fingerprint across five independent
index builds and three data dirs: default → `02353ae765fd…`, `HNSW_M=32` → `9814df378e0e…`. The two
swap symmetrically depending on which side of the lever the index was built on.

### Defects

1. **D1 — mid-migration, `/api/status` reports `COMPATIBLE` / `reindex_required=false` where the
   procedure asserts `BLOCKED_MISMATCH` / `schema_mismatch` / `true`** (arm 1 assertion 5; the one
   FAIL). Observed twice, independently:
   - arm 1, 07:26:26, `migration.state=MIGRATING`, `buildingGenerationId=g-20260903-052606`:
     `indexSchemaFpStored=02353ae7…` (Green's, equal to current), `indexSchemaCompatState=COMPATIBLE`,
     `reindexRequired=false`, `embeddingCompatReason=NEW_INDEX_NO_FINGERPRINT`.
   - arm 4b, 07:40:54, mid-migration: `fpStored=9814df378e0e…` (again the current runtime's own
     fingerprint), `compat=COMPATIBLE`, `reindexRequired=False`.

   Cause, read at source: `IndexStatusOps.safeSchemaFingerprintStored()` (`IndexStatusOps.java:1120-1137`)
   reads the **ingest** runtime's commit user data. During a blue/green migration the ingest runtime
   is GREEN, freshly created and stamped with the CURRENT fingerprint, so `current.equals(stored)` at
   `:1162` yields `COMPATIBLE` and `isReindexRequired()` (`:1186-1194`) is false. Consequence: while
   the user's searches are answered from the stale-shape Blue, the compat surface says the index is
   compatible and needs no reindex; only `migration.state` / `migrationSource=schema_mismatch` carries
   the story. §C does not pin the mid-migration value, so this may be intended — but §C.13's argument
   for *not* propagating the deferred-upgrade mismatch rests on "the status surface already reports
   `BLOCKED_MISMATCH` with `reindex_required` from the same fingerprint comparison", and that sentence
   holds only while no migration is in flight. Either the assertion or that sentence needs correcting.

2. **D2 — in the exhausted-brake state, `/api/status` reports `worker.core.indexedDocuments = 5`
   while `searchableDocuments = 205` and `migration.activeIndexedDocuments = 205`** (arm 3). Stable,
   not a warm-up transient: 3 reads over 21 s and 8 further reads over 90 s, all `indexed=5
   searchable=205`. In the brake branch `ingestLifecycle == searchLifecycle == blue`, and
   `KnowledgeStatusView.java:87` fills `indexedDocuments` from `ks.docCount()`, i.e.
   `IndexCountOps.java:81` `searcher.getIndexReader().numDocs()`, which for Blue is 205. After the
   `core.rebuild-index` recovery the two agree again (206 == 206), so the disagreement is specific to
   the brake state. A surface that headlines "documents indexed" tells a braked user 5 when 205 are
   searchable.

3. **D3 — in the exhausted-brake state, `indexSchemaFpStored` is reported as the empty string**
   (arm 3), although the same boot's own PRE-OPEN line proves Blue's last commit carries
   `9814df37…`. It is on the wire in two places: `compatibility.indexSchemaFpStored` and
   `schema.fpStored`. The user-visible verdict is still right for the right reason —
   `safeSchemaCompatState()` (`IndexStatusOps.java:1139-1170`) tests the brake at `:1147` before it
   reaches the stored-fingerprint branches — but the empty value would drive the `stored.isEmpty()`
   to `BLOCKED_LEGACY` branch at `:1153-1160` if the brake check were ever reordered, or if the
   budget were cleared by hand, which the brake's own ERROR message tells users to do. A read-only
   open of Blue appears not to populate `openTimeCommitUserData`.

4. **D4 — arm 1 assertion 7 is not verifiable by the instrument it names.** `commit_by_reason` never
   contains `migration/cutover` for a fast migration. The worker metric snapshot cadence is 60 s
   (observed 05:22:52 / 05:23:52 / 05:24:52 in `telemetry/metrics-worker.ndjson`) and the cutover
   restarts the Worker about 20 s after the migration starts (`Migration cutover complete. Restarting
   worker to open new active generation...`), so the migration session's counters are discarded
   before any snapshot is written. `migration/switch-buffer-replay` is additionally not expected in
   this arm — `switchBufferDepth` was 0 throughout. Both emit sites are live production code
   (`KnowledgeServerMigrationOps.java:229` and `:793`); what is missing is a flush on the cutover
   restart, or a different instrument for the assertion.

### Observations (not defects, routed here for the owner)

- **The `FAIL_CLOSED` refusal does not reach the user as a schema mismatch.** Arm 2's Head reports
  `knowledgeServerStartError: "Worker process crashed (exit code 1) before writing port to signal
  file"` and readiness `workerControlPlane: worker.spawn_recovery_exhausted` /
  `indexServing: index.not_healthy`; `/api/health` is unreachable entirely. The `index_fingerprint`
  mismatch exists only in `worker.log`. `FAIL_CLOSED` is not the prod default, so this is a
  non-default path — but it is the path an operator who sets the policy lands on.
- **The braked ingest queue is unbounded and silent.** Arm 3: with ingestion stopped, the watcher
  re-enqueued the whole corpus (`pendingJobs = 200`), and one newly created file took it to 201,
  where it stayed for 90 s with `searchableDocuments` pinned at 205. No cap and no backpressure on
  the status surface. After recovery all 201 drained and the new file became searchable.
- **The cutover's own Worker restart is recorded as an unclean shutdown.** The next boot logs
  `Unclean previous shutdown detected at …\<green> — running FULL integrity verification`, so every
  blue/green cutover buys a full integrity verification on the following boot.
- **Duplicate WARN on the corrupt path.** `Could not read committed parity metadata …` is logged
  twice per boot (07:48:07.282 pre-open, 07:48:07.343 open-time guard) — one condition, two lines.
- **Pre-existing, NOT lane D:** every deferred-open boot over an existing index logs 6-8 of
  `Lucene health check failed: SearcherManager not available (runtime closed?)` (arm 4c, arm 5
  second boot); fresh-index boots log none (arm 0, arm 5 base). `SearcherBridge.java` is identical
  between `39d38f73` and `12955fe9`, so this predates the PR.

### Deviations from the written procedure

1. **Backend launcher.** The procedure specified jseval. `scripts/jseval/jseval/backend.py:260-262`
   boots `:modules:ui:runHeadlessEval` through Gradle, which would rebuild (contradicting the
   no-rebuild constraint) and, worse, pins the **eval** contract —
   `justsearch.ui.settings.mode=IN_MEMORY`, `justsearch.eval.mode=true`, and
   `JUSTSEARCH_CONFIG=modules/ui/src/main/resources/headless-config/application.yaml` — i.e. not the
   production config that carries `index.auto_recovery: true` and the prod schema-mismatch default
   this validation is about. Every arm therefore launched the already-built dist directly:
   `modules/ui/build/install/ui/bin/ui.bat` with `JAVA_OPTS=-Djustsearch.prod=true`,
   `JUSTSEARCH_PROD=true`, `JUSTSEARCH_CONFIG` **unset** (so `config/application.yaml` applies),
   models from the main checkout, ORT CUDA from the shared `tmp/ort-variant-test/cuda-12.4-v1.24.3`.
   Harness: `tmp/915-live/{boot,boot-base,stop,probe,api,arm3}.ps1`.
2. **Config reached the Worker JVM, proved per arm** (the [R1] discipline). Head side,
   `GET /api/debug/effective-config`: `justsearch.prod = true (source jvm_arg)`,
   `index.vector.hnsw.m = 32 (source env_var, detail JUSTSEARCH_INDEX_VECTOR_HNSW_M)`,
   `index.schema_mismatch.policy` unset so the prod default `BLUE_GREEN_MIGRATE` applies
   (`ResolvedConfigBuilder.java:1016`). Worker side, `worker-config-snapshot.json` plus
   `worker.log`: `Config: index.vector.hnsw.m=32 (worker_snapshot:…, ordinal=450)` and
   `Config: justsearch.prod=true (worker_snapshot…)`.
3. **The `justsearch_dev_*` MCP tools were not used** — they drive the dev-runner stack, not a dist
   boot. Hygiene was checked directly (`netstat` for 33221, `Win32_Process` for Head/Worker JVMs,
   `nvidia-smi`, game processes) before the campaign and after every arm.
4. **Arms 2 and 3 restored a byte copy of the arm-0 data dir** (`tmp/915-live/data-arm0-snapshot`)
   instead of re-running the arm-0 ingest. Deterministic and faster; the restored generation was
   verified identical by file name plus size.
5. **Arm 1 assertion 4 (search served from Blue mid-migration) was captured in arm 4b, not arm 1.**
   Arm 1's migration cut over in about 20 s and my query landed in the Worker-restart window
   (`Worker capability unavailable / worker.lost`). Arm 4b is the same code path under the same
   policy and gave the assertion cleanly: `servingSearchGenerationId = g-20260903-053945` (the
   pre-mismatch generation), `activeIndexedDocuments = 206`, `searchTotalHits = 147` — identical to
   the pre-migration baseline.
6. **Arm 3 assertion 6: the UI render was NOT exercised.** The Head dist was built with
   `-PskipWebBuild`. What was asserted is the API reason code that selects the string —
   `readiness.components.indexServing = { state: DEGRADED, reasonCode: index.rebuild_brake_exhausted }`
   — and that `readinessNotice.ts:399-404` carries the procedure's quoted wording verbatim, severity
   `warn`, remedy `operationId: 'core.rebuild-index'`.
7. **Arm 6 assertions were rewritten for round 5**, as instructed: the expectation is no longer "the
   Worker fails to start and writes `worker-fatal-reason`" but "the pre-open inspection warns
   non-fatally and the open's recovery runs". That is what happened.
8. **`core.rebuild-index` needs a two-phase confirm the procedure does not mention.**
   `POST /api/operations/core.rebuild-index/invoke` returns `CONFIRMATION_REQUIRED` (gate
   `TYPED_CONFIRM`, risk `HIGH`) with a `pendingId`; the recovery arm therefore ran
   `POST /api/authorizations/approve {pendingId}` and re-invoked with `confirmationToken`. Under prod
   every mutating call also needs `X-JustSearch-Session`, obtained from `GET /api/mcp/token`.
9. **The arm-5 base is legacy for the key under test, not "pre-fingerprint" in general.** `39d38f73`
   stamps `index_schema_fp` (`SsotCommitMetadataSource.java:93` at that commit) and its own
   `/api/status` reported `indexSchemaFpStored = 79566bb5…`. It carries no `index_fingerprint`, which
   is exactly what the PR's key needs, and the head's PRE-OPEN line confirmed it: `stored=<missing>`.

### Machine signature

`nvidia-smi`: NVIDIA GeForce RTX 4070, 12282 MiB total. **Before:** 819 MiB used, 0 % util, port
33221 free, 0 Head/Worker JVMs (2 Gradle daemons plus 20 compiler daemons from sibling worktrees),
0 game processes. **After:** 805 MiB used, 1 % util, port 33221 free, 0 Head/Worker JVMs, 0 game
processes. Every arm ran alone; the port was confirmed free between arms.

Timings (local, 2026-09-03): arm 0 ingest 07:21:52 to 07:23:03 (205 docs) · arm 1 boot to PRE-OPEN
07:26:06.436, cutover complete 07:26:25.924, new Worker up 07:26:59 · arm 2 boot to refusal
07:30:39.750 (sub-second) · arm 3 four-boot ladder 07:32:44 to 07:32:58, brake at 07:32:57.976,
recovery invoked 07:38:15 and cutover 07:39:13 · arm 4 seed 07:39:45 to 07:40:00, mismatch boot
07:40:30, cutover 07:40:58 · arm 5 base ingest 07:44:24 to 07:44:50, upgrade PRE-OPEN 07:45:17 ·
arm 6 corrupt boot 07:48:06, recovery 07:48:07.45, rebuilt 07:49:10.

### Re-validation on `403f4b30`

Second independent live pass, same validator, after lane D fixed D1-D4, the FAIL_CLOSED marker, the
cutover clean-shutdown marker, the duplicate WARN and O14. Dists were confirmed to carry the
round-6/7 code before any arm ran: `WorkerFatalReasonMarker.INDEX_SCHEMA_MISMATCH =
"index_schema_mismatch"` and the `CleanShutdownMarker.wasClean` / `consume` split are both present
in the shipped `indexer-worker` jars (`javap` on the installed dist, not on the source tree).
Fingerprint determinism held across heads: the same two values as round 1 — defaults
`02353ae765fd…`, `HNSW_M=32` `9814df378e0e…`.

| Arm | What this head had to prove | Verdict | Evidence |
|---|---|---|---|
| 1 | D1 (stored fp = **Blue's**, `BLOCKED_MISMATCH`), D4 (`commit_by_reason`), and no unclean/full-scan on the cutover restart | **PASS** | `logs/r2arm1b-driver.log`, `artefacts/r2arm1-midmigration.json` |
| 2 | FAIL_CLOSED surfaces as `worker.index_schema_mismatch` + a `worker-fatal-reason` marker | **FAIL (Head half)** — marker half PASS | `logs/r2arm2-driver.log`, `logs/r2arm2b-driver.log` (arm 2c), defect **R1** |
| 3 | D2 (`indexedDocuments == searchableDocuments`), D3 (`fpStored` = Blue's), budget-cleared-by-hand, O14 five read-only boots | **PASS** | `logs/r2arm3-driver.log`, `logs/r2arm3b-driver.log`, `artefacts/r2arm3b-braked.json` |
| 4 | O7 on the deferred-open path, the negative re-boot, and search served from Blue mid-migration | **PASS** | `logs/r2arm4-driver.log` |
| 5 | Legacy `39d38f73` index: `stored=<missing>`, migrate once, silent second boot | **PASS** | `logs/r2arm56-driver.log`, `logs/r2arm5b-driver.log` |
| 6 | The unreadable-commit WARN is latched to ONE line (D.48) | **PASS** | `logs/r2arm56-driver.log`, `data-arm6/logs/worker.log.1` |

**D1 — fixed, confirmed twice in opposite directions.** Mid-migration, the compat surface now reports
the generation the user's searches actually reach. Arm 1 (09:08:39, Blue built at `HNSW_M=32`):

```
migrationState=MIGRATING  servingSearchGenerationId=g-20260903-070331  servingIngestGenerationId=g-20260903-070817
indexSchemaFpStored=9814df378e0e…   <- BLUE's, not Green's
indexSchemaFpCurrent=02353ae765fd…
indexSchemaCompatState=BLOCKED_MISMATCH  reindexRequired=True  reindexRequiredReason=schema_mismatch
readiness.indexServing = DEGRADED / index.schema_mismatch
indexedDocuments=768  buildingIndexedDocuments=768  searchableDocuments=1005
```

Arm 4b (09:21:49) is the same surface with the fingerprints swapped (Blue built at defaults), and it
also carries the assertion round 1 kept losing to the cutover-restart window — **search is served
from Blue throughout**: `servingSearch = g-20260903-072031` (= the pre-mismatch generation),
`activeIndexedDocuments = 1005` = N0, `searchHits = 100` = the pre-migration baseline. The
`indexedDocuments` / `searchableDocuments` split (768 vs 1005) is the intended one — "documents
indexed" counts the generation being written, "searchable" counts what a query can reach — which is
what §F G43 pins in the other direction.

**D4 — fixed.** `commit_by_reason` read from `<dataDir>/telemetry/` after arm 1 now contains
`"migration/cutover": 1`. `migration/switch-buffer-replay` is absent and correctly so:
`switchBufferDepth` was 0 for the whole cutover, so that reason never fired.

**Cutover restart — fixed.** The worker the cutover restarts logs `unclean=0 fullscan=0` in arm 1 and
again in arm 4b. Round 1's `Unclean previous shutdown detected at …\<green> — running FULL integrity
verification` is gone.

**D2 / D3 — fixed.** Braked state (arm 3b, worker connected, stable at t+20 s):

```
indexedDocuments=1005  searchableDocuments=1005  activeIndexedDocuments=1005   (round 1: 5 vs 205)
indexSchemaFpStored=9814df378e0e…                                             (round 1: "")
indexSchemaFpCurrent=02353ae765fd…  indexSchemaCompatState=BLOCKED_REBUILD_BRAKE
reindexRequired=True  reindexRequiredReason=rebuild_brake_exhausted
readiness.indexServing = DEGRADED / index.rebuild_brake_exhausted
servingSearchGenerationId=g-20260903-070331  migrationState=IDLE  buildingGenerationId=
worker.log: brakeLine=1  ingestStopped=1
```

The four-boot ladder itself was run for real first and produced `auto_rebuild_count` 1 → 2 → 3 on
three live Workers, then the brake on boot 4 with `count=4`, `migration_state IDLE`, no building
generation.

**Budget cleared by hand — PASS.** With `auto_rebuild_*` removed from `state.json`, the next boot
migrates as a MISMATCH, not as a legacy index — the PRE-OPEN line carries `stored=9814df378e0e…` and
the *changed-shape* hint (`"The effective index shape changed (field catalog, analyzers, vector
format/dimension, HNSW build params, chunking, or an embedding/SPLADE model)…"`), not
`index-without-fingerprint`, and it restarts at `attempt 1 of 3` with `auto_rebuild_count: 1`.

**O14 — PASS.** Five consecutive read-only (braked) boots of the same Blue: `unclean=0 fullscan=0` on
every one, and `g-20260903-070331.clean-shutdown` still present on disk after the fifth. This is only
testable because the harness now stops the product the way a user does (see deviation 1).

**Arm 5 — PASS.** `PRE-OPEN PARITY_DIFF key=index_fingerprint stored=<missing> expected=02353ae765fd…
hint="index-without-fingerprint: this index carries no recorded index_fingerprint, so its physical
shape cannot be verified. Rebuilding once records it."` then `attempt 1 of 3`; one cutover
(`active g-20260903-072854 → g-20260903-072938`); second boot `preOpen=0 parityDiff=0 blueGreen=0
unclean=0 fullscan=0`, `COMPATIBLE`, `reindexRequired=False`, 205 docs.

**Arm 6 — PASS, single WARN.** `unreadableCommitWarns=1` (round 1: 2), then
`Corrupted index detected … attempting backup-first rebuild` → `recovered to empty (reason=corrupt_index).
Rebuilding from source via blue/green`. Worker starts, no `worker-fatal-reason`, doc count back to 55
and search back to 55 hits.

#### Defect

**R1 — `worker.index_schema_mismatch` never reaches Head readiness under FAIL_CLOSED. The Worker
half of D.46 works; the Head half does not.**

*Worker half PASS.* `<dataDir>/worker-fatal-reason` is written and contains exactly
`index_schema_mismatch` — read off disk at 09:10:02.007 (arm 2) and again at 09:14:13.301 (arm 2c).

*Head half FAIL.* The complete `readiness.components.workerControlPlane.reasonCode` sequence across
the whole supervision arc (boot 09:13:39, watched to a terminal state that then held unchanged for
85 s):

```
09:13:41.361  NOT_READY  worker.spawn.failed
09:13:52.132  DEGRADED   worker.recovering
09:16:06.475  NOT_READY  worker.spawn_recovery_exhausted     <- terminal, stable through 09:17:31
```

`worker.index_schema_mismatch` never appears, in any component, at any point. `/api/health` returns
503 throughout and `knowledgeServerStartError` stays
`"Worker process crashed (exit code 1) before writing port to signal file"` — the exact string
D.46's own test comment quotes as the thing it set out to replace. The first narrated verdict already
carried the generic code: `HeadlessApp.java:548` logged
`Knowledge Server failed to start: … (worker reason: worker.spawn.failed) — boot recovery armed` at
09:13:41.256, while the marker was on disk.

*Mechanism, from source — the implementer's to pin with a test, not asserted here.*
`KnowledgeServerBootstrap.transitionWorkerDown` (`:787-809`) calls `workerDownCode` at `:788`, which
calls `WorkerFatalReasonMarker.readAndClear` at `:759` — consuming the one-shot marker — and then
returns early **without applying the verdict** at `:798` (`narrationSuppressed()`, true while "a retry
or recovery arc owns it", i.e. for the whole boot-recovery ladder) or at `:805`
(`supervisionVerdictHeld()`, whose carve-out at `:800` reads
`down.code() != LifecycleReasonCode.WORKER_INDEX_CORRUPT` — `WORKER_INDEX_SCHEMA_MISMATCH` is not
carved out). Once the marker is consumed no later call can recover the cause, which is precisely the
hazard the corruption axis has a latch for.

*Why the round-6 test is green.* `KnowledgeServerWorkerDownCodeTest` has
`corruptCauseSurvivesTheSupervisionSequence` — an end-to-end latch case over restart-then-give-up —
for the corruption axis, but `schemaMismatchMarkerOverridesTheGenericCode` calls
`transitionWorkerDown` exactly once on a fresh bootstrap, so it meets neither guard. This is the same
`substrate-without-consumer` shape §F G49 was added to catch, one level further down: G49 pinned the
*mapping*, nothing pins the *arc*. `HeadlessApp.java:540-542`'s own comment corroborates the missed
sweep — it still enumerates the reachable codes as "worker.spawn.failed, worker.index_corrupt, or
supervision's terminal worker.restart_exhausted", with the round-6 addition absent.

*What arm 2 did pass:* `state.json` byte-identical across the arm (SHA-256 `F84DDB07…` before and
after), only the Blue generation on disk, `preOpen=1`, `failedStart=1` — FAIL_CLOSED still refuses
without touching anything, exactly as in round 1. The `readinessNotice.ts:282-285` wording row for
`worker.index_schema_mismatch` exists; it is simply unreachable.

#### Deviations from the round-1 harness and the written procedure

1. **`stop.ps1` now uses the product's ordered shutdown** (`POST /api/lifecycle/shutdown`,
   `LifecycleApiModule`) with `taskkill` only as `-Hard`. Round 1 used `taskkill` throughout, which is
   a crash by definition: it left every generation dirty, which made round 1's "the post-cutover
   reboot logs unclean" observation un-interpretable and would have made O14 untestable — the clean
   marker never exists after a kill. Arms that want an unclean stop (arm 2, and the sweeps between
   arms) pass `-Hard` explicitly.
2. **Arms 1 and 4 use a new deterministic 1000-file corpus** (`tmp/915-live/corpus1000`). At 200 files
   the migration completes in 20-40 s while `/api/status` first answers at ~55 s, so the
   mid-migration surface — the entire object of the D1 assertion — is not observable. Arms 2 and 3
   reuse that 1000-doc Blue through a byte snapshot; arms 5 and 6 keep the 200- and 50-file corpora.
3. **Arm 1's mid-migration capture was taken by hand from the session** at 09:08:39, after the
   driver's own readiness check proved too loose (it accepted an `/api/status` that answers before the
   worker payload exists). Arm 4b then captured the same surface unattended at 09:21:49, so D1 rests
   on two independent observations rather than one hand-taken sample.
4. **Arm 3's braked compat capture was re-taken** (`r2arm3b`) by seeding `auto_rebuild_count: 4`
   directly into `state.json` — the same fixture `BrakeExhaustedWorkerServesReadOnlyTest` uses —
   because the first capture landed before the worker was connected and read all zeros. The
   four-boot ladder itself was still run for real, and its counts are the ones reported.
5. **Arm 5's `stored=<missing>` line was re-captured** (`r2arm5b`) by tailing `worker.log` while the
   migration ran. The first pass lost it: the cutover-restarted Worker truncates `worker.log` before
   the driver copies it.
6. **Arm 2 needed the full supervision arc.** The first two passes sampled readiness at t+2 s and
   t+60 s and would have reported a transient (`worker.spawn.failed`, `worker.recovering`) as the
   verdict. Only the third pass, watching to a terminal code that then held for 85 s, is the evidence
   quoted above.
7. **The UI render is still not exercised** (`-PskipWebBuild`). Reason codes are asserted on the API;
   wording is read from `readinessNotice.ts`.
8. **Machine contention, disclosed rather than hidden.** A Riot Client / League / TFT / Overwolf stack
   started at 09:26:39, overlapping arm 6 (09:26:49-09:28:44) and the arm-5 re-capture
   (09:28:52-09:30:55). Every assertion in those two arms is a log string, a document count or a
   `state.json` field — none is a throughput measurement — so the contention does not bear on their
   verdicts.
9. **The Head's own log is not in the arm's data dir.** `logback.xml:29` resolves the log path from
   the **sysprop** `justsearch.data.dir`, which this harness does not set (it sets the env var), so
   Head logs land in `build/headless-data/logs/headless-backend.log` at the worktree root. That file
   is where the `HeadlessApp.java:548` line quoted in R1 was read.

#### Machine signature

`nvidia-smi`: NVIDIA GeForce RTX 4070, 12282 MiB total. **Before (09:03):** 902 MiB used, port 33221
free, 0 Head/Worker JVMs, 0 game processes. **After (09:31):** 2180 MiB used, 23 % util, port 33221
free, 0 Head/Worker JVMs, 3 game-client processes (deviation 8). Arms ran one at a time; the port was
confirmed free between arms.

Timings: arm 0'' ingest 09:03:29 → 09:08:08 (1005 docs) · arm 1 PRE-OPEN 09:08:17.651, mid-migration
capture 09:08:39, cutover 09:08:33 → settled 09:09:32 · arm 2 boot 09:13:39 → terminal readiness
09:16:06 · arm 3 ladder 09:18:15 → brake 09:18:27, O14 boots 09:18:59 → 09:20:10, cleared-budget boot
09:20:14 · arm 3b 09:23:36 → 09:24:20 · arm 4 09:20:29 → 09:23:16 · arm 5 09:24:31 → 09:26:42, arm 5b
09:28:52 → 09:30:55 · arm 6 09:26:49 → 09:28:44.

### Arm 2 re-run on `c06d8b25`

R1 is fixed. Dist verified first: `BootRecoveryDecision$Veto` in the shipped Head jar carries
`NONE, SUPERVISION_ENGAGED, RESTART_EXHAUSTED, INDEX_FATAL` (`javap` on
`modules/ui/build/install/ui/lib/app-services-0.2.0.jar`, not on the source tree). Same 1000-doc
Blue at `HNSW_M=32` as the round-2 pass, booted with `JUSTSEARCH_INDEX_SCHEMA_MISMATCH_POLICY=FAIL_CLOSED`.

| # | Assertion | Verdict | Evidence |
|---|---|---|---|
| A1 | terminal readiness is `NOT_READY worker.index_schema_mismatch`, not `spawn_recovery_exhausted` | **PASS** | reached 10:15:22.072 (t+2.6 s), unchanged for the whole 200 s watch to 10:18:39 |
| A2 | the policy remedy is carried | **PASS** | verbatim in `knowledgeServerStartError` and in the Head log; see the note below on where it does *not* appear |
| A3 | `/api/health` payload names the mismatch | **PASS** | `HTTP 503` + 419-byte body: `lifecycle.reason_code = worker.index_schema_mismatch` **and** `components.worker.reason_code = worker.index_schema_mismatch` |
| A4 | `knowledgeServerStartError` names the mismatch | **PASS** | the full remedy sentence, quoted below |
| A5 | the fatal-reason marker is on disk | **PASS** | `worker-fatal-reason` = `index_schema_mismatch`, observed 10:20:07.192 at a 50 ms poll |
| A6 | the ladder gives up early instead of burning the budget | **PASS** | `spawnAttempts=1`, `bootRecoveryReattempts=0` |
| A7 | the index is still untouched | **PASS** | `state.json` SHA-256 identical; only `g-20260903-070331` + its `.clean-shutdown` on disk |
| A8/A9 | the operator path re-opens the `INDEX_FATAL` give-up | **PASS** | `POST /api/worker/restart` → `HTTP 202 {"recovery":"ACCEPTED"}`, followed by a new spawn |
| A10 | with the policy flipped, the migration starts | **PASS** | `PRE-OPEN PARITY_DIFF` → `Starting Blue/Green migration (policy=BLUE_GREEN_MIGRATE, attempt 1 of 3)`, `auto_rebuild_count: 1` |

**A1/A4 — the refusal now reaches the user as itself.** Round 2 produced
`worker.spawn.failed → worker.recovering → worker.spawn_recovery_exhausted` over ~150 s, with
`knowledgeServerStartError = "Worker process crashed (exit code 1) before writing port to signal
file"`. At this head, 2.6 s after boot:

```
readiness.components.workerControlPlane = NOT_READY / worker.index_schema_mismatch
readiness.composites.retrieval          = NOT_READY / [worker.index_schema_mismatch, index.not_healthy, lambdamart.not_configured]
knowledgeServerStartError = "The search index was built with a different index shape than this
  version writes, and index.schema_mismatch.policy=FAIL_CLOSED refuses to rebuild it. Set the policy
  to BLUE_GREEN_MIGRATE to rebuild alongside the existing index, or rebuild the index yourself."
```

**A6 — the ladder short-circuits.** One `Spawning worker:` for the whole boot, zero boot-recovery
re-attempts, and the veto is narrated rather than silent (Head log, UTF-8):

```
10:15:20.313 INFO  Spawning worker: …                                   <- the only one
10:15:21.537 WARN  Knowledge Server failed to start: The search index was built with a different index shape … (worker …
10:15:31.538 ERROR Boot recovery declining to re-attempt: the worker refused with worker.index_schema_mismatch — the condition is on disk, so re-spawning would read the same bytes and refuse the same way
```

Round 2 burned `Boot recovery: re-attempting … (1/4)`, `(2/4)`, `(3/4)` with 10/20/40 s backoff before
landing on `worker.spawn_recovery_exhausted` ~150 s in.

**A8/A9 — the operator exemption is reachable over HTTP and works.** The path is
`POST /api/worker/restart` (`InferenceRoutes.java:26` → `InferenceHandlers.handleRestartWorker` →
`routeToRecoveryAuthority` → `KnowledgeServerHealthMonitor.requestRecoveryNow()` →
`currentRecoveryInput(true)`, which withholds the `INDEX_FATAL` veto and clears the latched give-up
for that veto only). Observed:

```
10:18:40.379  POST /api/worker/restart -> HTTP 202 {"recovery":"ACCEPTED","success":true}
10:18:40.367  Boot recovery: re-attempting Knowledge Server start (1/4)     <- the give-up re-opened
10:18:40.370  Spawning worker: …
10:18:41.488  Boot recovery attempt 1 failed: … crashed (exit code 1) …
10:18:41.558  Boot recovery declining to re-attempt: the worker refused with worker.index_schema_mismatch …
```

The re-spawn refuses again and re-latches, which is correct: the policy had not changed yet.

**A10 — the remedy.** `index.schema_mismatch.policy` is forwarded to the Worker as a `-D` at spawn
from the Head's boot-time resolved config (`WorkerSpawner.WORKER_FORWARDED_PROPS`), so flipping it
needs a new Head process — the operator exemption re-opens the *give-up*, it cannot change the
*policy*. Rebooted with `BLUE_GREEN_MIGRATE`: `PRE-OPEN PARITY_DIFF key=index_fingerprint
stored=9814df378e0e… expected=02353ae765fd…` then `Starting Blue/Green migration (policy=BLUE_GREEN_MIGRATE,
attempt 1 of 3)`, `building_generation g-20260903-081908`, `auto_rebuild_count: 1`.

#### Residual finding

**R2 — after an operator-requested retry that re-refuses, readiness is left on the transient
`worker.recovering` instead of re-latching the terminal code.** Reproduced twice: after
`POST /api/worker/restart` the component goes `DEGRADED / worker.recovering` and stays there —
observed continuously for 120 s (10:21:05 → 10:23:05) and again 25 s after the first request — even
though the Head has already narrated
`Boot recovery declining to re-attempt: the worker refused with worker.index_schema_mismatch` at
ERROR. `knowledgeServerStartError` keeps the remedy sentence throughout, so the user is not left
without the cause, but the readiness component — the surface `readinessNotice.ts` renders — reports
"recovering" indefinitely for a condition that by the Head's own reasoning will never recover on its
own. The latch works for the automatic ladder (A1); it is the operator-requested arm that leaves the
transient in place. Scoped as a follow-up, not a re-opening of R1.

#### Corrections to my own round-2 measurements

- **`/api/health` was never empty.** Round 2 recorded "unreachable (503)" and this round's first pass
  recorded an empty body; both were my probe, not the product — `Invoke-RestMethod` throws on 503 and
  my error-stream read returned nothing. `StatusLifecycleHandler.java:1137-1141` does
  `ctx.status(503).json(snapshot)`, and `curl -i` shows the 419-byte body quoted in A3. The round-1/2
  claim that the FAIL_CLOSED refusal "reaches the user only in worker.log" was therefore wrong about
  `/api/health` specifically; it was right about the *content*, which named a spawn crash rather than
  the refusal until this head.
- **A2, where the remedy is not.** `ReadinessComponentView` (`:12-14`) has fields
  `state, reasonCode, source, observedAt, stale, …` and no detail field at all, so the remedy sentence
  is not on the readiness component by construction. It is carried by `knowledgeServerStartError`
  (D.55's `startErrorFor`) and by `readinessNotice.ts`'s own wording row. Not a defect; my first probe
  looked for a field that does not exist.

#### Out of scope, pre-existing

`LifecycleSnapshotTap: no mapping for dim=WORKER_CONTROL_PLANE state=NOT_READY
reasonCode=worker.index_schema_mismatch; preserving any prior assertion (unknown reason ≠ healthy)`
fires on every boot of this arm. The same WARN fired in round 2 for `worker.starting` and
`worker.recovering`, so the tap's mapping table is generically incomplete rather than newly broken by
the round-8 code — but the new code does inherit the gap.

#### Machine signature

RTX 4070, 12282 MiB. Before: 976 MiB used, 2 % util, port 33221 free, 0 Head/Worker JVMs. After: port
33221 free, 0 Head/Worker JVMs. Riot/League client processes were resident but idle throughout
(GPU at 2 %); every assertion in this arm is a log string, an HTTP status/body or a `state.json`
field, none is a throughput measurement. Boots: 10:15:19 (main arc), 10:20:05 (marker + health +
operator follow-up), 10:23:35 (`/api/health` re-measurement).

---

## §P2 Phase 2 — accepted design and PR boundaries

This section carries forward the accepted Phase 2 design from the pre-implementation pass. It is the
authority for the implementation split; the older Phase 1 report above remains historical evidence
and must not be read as saying that Phase 2 is still unstarted.

### §P2.B Verified facts and invariants

- Lucene already stores a random parent `doc_uid`, but before PR-A no durable authority mapped a
  logical document's path to that UID outside an index generation. The stable identity therefore
  has to be recovered from the serving index once, then owned outside Blue and Green.
- Identity is content-independent: two paths with byte-identical content receive different UIDs.
  Content hashes and file keys are not identity authorities.
- There is one minting authority. Admission resolves or mints through the SQLite store before
  extraction; an unavailable or corrupt authority fails the job closed for queue retry. There is no
  random fallback.
- Phase 2 adds no search, MCP, protobuf, or frontend field. `doc_uid` remains an internal stored
  field used by Worker-side indexing and, in PR-B, Head-side projection/storage plumbing.
- Phase 2 does not change the physical index shape, so it does not bump `RENDERING_VERSION` or the
  Phase 1 index fingerprint. Existing parent UIDs are imported instead of rewritten.

### §P2.C Implemented PR-A design

1. **Store and migration.** Schema V11 adds
   `document_identity(path_hash PRIMARY KEY, doc_uid NOT NULL UNIQUE, first_seen_at, last_seen_at)`
   plus the UID uniqueness index to the existing `jobs.db`. It stores no raw path, has no scheduled
   GC, and uses the same transaction, backup, future-version refusal, and recovery boundary as the
   queue. `governance/store-recoverability.v1.json` therefore updates the existing `jobs-db` row from
   its stale version 7 to the actual version 11; there is no new durable-store row, `StoreCatalog`
   member, corruption-policy term, catalog copy, or updater/wire change.
2. **Resolve and mint.** The Worker resolves the normalized path hash beside the existing
   path-resolution admission step. A new UUID is minted only when that hash is absent. The resolved
   UID travels through extraction and write plumbing and is written to the parent document.
3. **Boot import and rebuild.** Before indexing begins, the Worker scans only parent documents in
   the serving Lucene index and atomically imports missing `doc_id`/`doc_uid` pairs. Existing SQLite
   rows win, including after a completed rename. During Blue/Green migration, Blue is the import
   source and Green re-ingests through the ordinary store-backed path; no UID is copied directly
   from Blue to Green.
4. **Rename and deletion.** An API-driven rename re-keys the SQLite mapping before rewriting Lucene
   path fields and preserves the moving source UID even when a stale historical destination row
   exists. A retry after an identity-only move is successful. Delete does not remove the mapping,
   so later re-indexing reuses it. Filesystem-watcher renames still arrive as delete plus create and
   are explicitly outside the preservation contract.
5. **Chunks.** Chunk UIDs are deterministic: `parentDocUid + "#" + chunkIndex`. Regeneration uses
   the same formula, with no second identity store or schema field.
6. **Recovery limit.** A pre-V11 `jobs.db.bak` can be restored and migrated, after which boot import
   reconstructs missing mappings from the serving index. If the identity database and backup **and**
   the serving index are all lost or unreadable, old random UIDs cannot be reconstructed; later
   admission mints new identities and UID-keyed derived/feedback links may be orphaned. This is the
   accepted total-data-loss boundary.

### §P2.D Boundaries, tests, docs, and gates

**PR split.** PR-A is the Worker-side migration, store, boot import, admission/write plumbing,
rename re-key, chunk UID derivation, canonical documentation, and governance update. PR-B owns
Head-side feedback/GPL re-keying (`FeatureSnapshot(s)`, `SearchTool`, `KnowledgeSearchController`,
`LabelProjection`, `AgentDispositionWiring`, and `GplTrainingTripleStore`) and the label-survival
test. Implementation review proved that a collapsed chunk-only result did not otherwise carry its
parent UID to Head, so PR-B also owns the minimal `SearchResponseBuilder.resolveParentMetadata`
enrichment that places the parent `doc_uid` in the existing generic fields map. This adds no
protobuf field and requires no frontend change. Lane E overlaps only
`ChunkDocumentWriter.java`; whichever change lands second must rebase while preserving both Lane E's
chunk constants and Lane D's UID derivation.

The pre-implementation plan listed **ten conceptual test rows**. Its older “six of ten” PR-A label
was a counting error: PR-A owns nine rows and PR-B owns only the label-survival row.

| # | Contract row | Owner | Implemented source/evidence |
|---:|---|---|---|
| 1 | Mint once, reopen, re-key, import | PR-A | `SqliteDocumentIdentityStoreTest` |
| 2 | Equal content at different paths gets different UIDs | PR-A | `SqliteDocumentIdentityStoreTest.distinctPathHashesReceiveDistinctContentIndependentUids` |
| 3 | API rename moves paths and preserves UID | PR-A | `GrpcIngestServiceDocumentIdentityTest.renameRekeysStoreAndPreservesEveryUid` |
| 4 | Delete then re-index preserves UID | PR-A | `GrpcIngestServiceDocumentIdentityTest.deleteAndReindexPreservesUid` |
| 5 | Full Blue/Green rebuild preserves the imported UID | PR-A | `DocumentIdentityBootImportTest.blueUidIsImportedBeforePausedMigrationReindexesIntoGreen` |
| 6 | Chunk UID regeneration is deterministic | PR-A | `ChunkDocumentWriterTest`; `GrpcIngestServiceChunkRegenerationTest` |
| 7 | Feedback labels survive a full rebuild by UID | **PR-B** | `LabelStoreRegenerationKeepsUidKeysTest` (Head half: the derived store re-projects to the same UID keys) **+** `DocumentIdentityBootImportTest.blueUidIsImportedBeforePausedMigrationReindexesIntoGreen` (Worker half: the UID survives Blue→Green and is read back off the production gRPC search response's `fields["doc_uid"]`, the value Head keys feedback on) — *split and renamed 2026-09-05, tempdoc 931 §C.4; the single-test entry named a rebuild the test never performed* |
| 8 | V10→V11 migration/refusal/rollback preserves queue data | PR-A | `JobQueueMigrationTest` |
| 9 | Backup restore and fresh-store boot import recover identity | PR-A | `JobQueueMigrationTest`; `DocumentIdentityBootImportTest` |
| 10 | ADR-0028 path-free schema and fail-closed authority | PR-A | runnable ADR probe targets `JobQueueMigrationTest#migratesV10ToV11WithPathFreeIdentitySchemaAndPreservesJobs`; `DocumentIdentityScanTest`; `SqliteDocumentIdentityStoreTest.unavailableStoreFailsClosed` |

Retry, idempotency, blank-rename refusal, stale-index precedence, boot-order, parent-only import,
production gRPC wiring, canonical parent/chunk path linkage, non-destructive missing-parent-UID
refusal, and rename refusal during the cutover fence are additional PR-A regressions beyond the
original ten-row matrix.

Required documentation is carried by `docs/explanation/04-storage-engine.md`,
`docs/explanation/11-index-schema-migration.md`,
`docs/explanation/18-adapters-lucene-deep-dive.md`, the ADR-0028 amendment, its decision-log row, and
the `jobs-db` recoverability entry. No `docs/llms.txt` regeneration is required because no indexed
title or description changed; the ADR decision-log projection has been refreshed.

**PR-A local verification (2026-09-03).** The focused identity, migration, rename, chunk,
fail-closed, and adversarial-ingestion tests passed. Clean module suites passed for `worker-core`,
`adapters-lucene`, `worker-services`, and `indexer-worker` (the first combined run exposed a brittle
corruption fixture whose file-midpoint overwrite no longer hit SQLite metadata after schema V11;
the test now corrupts page 1 deterministically, passed alone, and the full clean indexer-worker
suite then passed). Formatting and Markdown lint passed. `adr-coverage`,
`check-store-recoverability`, `check-language-agnostic-analysis`, `check-live-witness`,
`check-tempdoc-numbers`, `check-premerge-table`, `check:llmstxt`, and `git diff --check` all passed.
The wire gate and SSOT catalog regeneration are not required because there is no wire or catalog
change. The hour-scale Lane E benchmark campaign is deliberately not part of PR-A.

### §P2.E Accepted decisions (Q1–Q8)

- **Q1 — Head files:** grant `FeatureSnapshots.java`, `SearchTool.java`, and
  `KnowledgeSearchController.java` to PR-B so UID reaches Head-side feedback plumbing without a wire
  or frontend change.
- **Q2 — Worker ownership:** the expired Lane C grants do not block PR-A. Worker-services,
  indexer-worker, and `KnowledgeServer.java` identity changes belong to PR-A; coordinate the single
  `ChunkDocumentWriter.java` overlap with Lane E by rebase.
- **Q3 — legacy feedback:** accept **no backfill**. Pre-Phase-2 snapshots contain no UID and Head has
  no path-to-UID authority. Old rows keep their path keys, new rows use UID keys, and derived GPL
  triples are re-projected.
- **Q4 — versioning:** do not bump `RENDERING_VERSION`. First boot imports existing stored parent
  UIDs, and Phase 3's later physical-shape changes will move the fingerprint independently.
- **Q5 — updater closed set:** route the latent “new durable-store row” updater refusal to its
  governance owner. PR-A adds no row, so it neither triggers nor fixes that separate issue.
- **Q6 — register version:** update `jobs-db.currentVersion` from the stale 7 to 11, matching
  `SqliteSchema.TARGET_VERSION` after the V10→V11 migration.
- **Q7 — retention:** accept no GC for identity rows. Revisit only from measured table growth, not a
  calendar schedule.
- **Q8 — authority failure:** fail closed and let the queue retry. Never mint from a fallback
  authority when the durable identity store is unavailable.

---

## Phase 3 implementation report-back

### §P3.A Accepted PR order and evidence boundary

The accepted order is PR-A → PR-C0 → PR-C2 → PR-C1 → lane E constants → PR-B. PR-C0 is deliberately
fingerprint-neutral. PR-C2 and PR-C1 each move the fingerprint for independently attributable
storage/codec changes; all fingerprint-moving PRs still land before one release so users pay for one
rebuild. PR-C1 remains blocked by its codec/versioning work and 12–18 machine-hour evidence campaign.

The hour-scale Lane E benchmarks are not PR-C0 verification and were not run. PR-C0's six-corpus
multilingual comparison is also deferred for the current work window, but it is **not waived**: its
pre-registered skip-rate and relevance criteria remain a merge prerequisite.

### §P3.B PR-C0 implemented semantics

- QPP now carries the `content` field's own `IndexReader.getDocCount(field)` denominator and the
  minimum analyzed-term `docFreq / fieldDocCount` fraction. Chunk documents therefore cannot inflate
  the denominator for a field they do not contain.
- The planner skips dense retrieval only when another retrieval leg is runnable. Dense-only/vector
  requests and direct RAG remain recall-first and always run dense. Empty QPP and corpora below 100
  field documents never trigger the document-frequency skip.
- The existing short-query rule remains independent at four characters. The new DF threshold is
  `index.hybrid.vector_skip_min_df_fraction` / `JUSTSEARCH_INDEX_VECTOR_SKIP_MIN_DF_FRACTION`, default
  `0.25`, clamped to `[0,1]`. It replaces the retired `entity_boost` key one-for-one, preserving the
  configuration-surface pins at `111 / 250 / 56`.
- Deliberate planner skips use `SKIPPED_SHORT_QUERY` or `SKIPPED_NO_DISCRIMINATIVE_TERM`, separate
  from embedding/encoding failure. The trace now reports the dense stage as skipped with that typed
  reason, and chunk merging omits a dense vector when the planner skipped the leg.
- The English `STOP_WORDS` collection and all four adapter-level skip guards are deleted. A fifth
  language-agnostic-analysis check rejects authored `Set.of`/`List.of` natural-language word lists
  in the query path.
- The functional `entity_boost` resolver, environment/system-property registration, and query
  construction path are retired. Status/protobuf field 9 remains present and is always projected as
  `0.0`, preserving compatibility. The physical `entity_*_text` fields and writers remain until
  PR-C2, so PR-C0 does not move `index_fingerprint`.

PR-C0 avoids KNN search and fusion work for a skipped dense leg. It does **not** avoid query-embedding
generation, which still happens before planning; performance claims must preserve that distinction.

### §P3.C PR-C0 local verification (2026-09-03)

Focused Java tests cover common-term skipping, discriminative-term retention, tiny-corpus behavior,
dense-only and direct-RAG recall, truthful traces, field-local QPP denominators, retired entity-query
behavior, configuration defaults/clamping, and the zeroed wire/status tombstone. Focused UI tests
cover exact reason wording and fixture compatibility. The language-agnostic-analysis,
search-degradation-reason-code, ADR-coverage, and config-surface gates pass; the generated runtime
configuration matrix remains exactly `yaml_keys=111`, `env_sysprop_pairs=250`, `config_keys=56`.

The six-corpus evaluation and hour-long benchmarks were not run. PR-C0 may be reviewed and stacked
upon locally, but it must not merge until the six-corpus acceptance evidence is recorded here.

### §P3.D PR-C2 implemented semantics

- The canonical and runtime-mirror catalogs keep `chunk_content` analyzed and indexed but set
  `stored:false`. Generic projections, chunk search, citation matching, embedding, SPLADE, BGE-M3,
  and combined enrichment reconstruct the value from stored parent `content` plus
  `chunk_start_char`/`chunk_end_char`.
- Reconstruction is the exact Java UTF-16 substring with an exclusive end offset. It performs no
  trimming or normalization, preserves CRLF, Markdown fences, whitespace, and surrogate pairs, and
  produces no fabricated text on a missing parent or malformed/out-of-range geometry. Generic and
  batch projections omit the unresolved value; the chunk-search result keeps its pre-existing
  empty-string fallback. Batch paths read each distinct parent at most once.
- `chunk_content` declares the dedicated `rederive-parent-slice` RMW policy. The policy is legal only
  on that exact text field. Single, batch, and path-update RMW lanes reconstruct the old posting from
  the old parent snapshot before applying an unrelated update; missing or invalid reconstruction
  fails closed instead of silently erasing indexed chunk text.
- BGE-M3 routing now determines chunkness from `is_chunk`, not from the former stored-content
  presence. Combined enrichment also classifies pending documents structurally, preventing a chunk
  discovered through another cache from receiving parent-only vector or NER status.
- The three analyzed `entity_persons_text`, `entity_organizations_text`, and
  `entity_locations_text` fields, schema constants, and NER writers are deleted. The retained
  multi-valued `entity_*_raw` fields continue to serve filters, facets, and evidence-span membership.
- The physical projection test proves both changes move `index_fingerprint` relative to the legacy
  shape. The `rmwPolicy` annotation itself remains excluded from the fingerprint.

Two independent audits found gaps before the short-check boundary. First, citation matching reads
through generic `ReadPathOps`, not `ChunkSearchOps`; synthesis was added there rather than changing
the citation contract. Second, Lucene RMW recreates a document from readable values, so merely
removing storage would erase the chunk posting on any unrelated update; the dedicated policy and
old-parent reconstruction close that hole. The combined-enrichment tests then exposed a third
routing edge: reconstructed content allowed chunks to arrive through the SPLADE cache, so structural
`is_chunk` routing was made authoritative across all pending IDs.

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
is run and recorded. PR-C1 is described separately in §P3.F; its quality campaign remains deferred.

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

### §P3.G PR-B stable feedback identity implementation (2026-09-03)

New feedback snapshots and GPL triples now use the stable parent `doc_uid` as their persisted
document key. `FeatureSnapshot.HitFeatures.docId` is the primary key: UID for new rows and the
historical path for legacy rows. Its new nullable `sourceDocId` is only a path-oriented correlation
alias so existing UI dispositions and agent citations can still join without a frontend or protobuf
change. Old unversioned and version-1 snapshot rows whose hits lack `sourceDocId` remain readable and
path-keyed; there is deliberately no path-to-UID backfill.

`KnowledgeSearchHitIdentity` is the single Head-side projection from a search hit to source path and
stable parent UID. Whole-document results use `doc_uid` directly. Collapsed chunk-only results are
enriched in the Worker's existing generic fields map with the parent `doc_uid`; malformed,
conflicting, or missing identities fail closed and do not create new feedback. Narrow HTTP
projections temporarily request that field for capture and remove it before returning the response,
while the agent's separate non-rendered feedback metadata carries both the path alias and UID.

Projection resolves path aliases to the interaction's UID-bearing snapshot and persists the UID in
the existing `ResultDisposition.docId` and GPL JSON `doc_id` properties. It deduplicates repeated
hits by stable document key, drops ambiguous aliases, and coalesces repeated explicit dispositions
by UID: a positive dominates a negative and the strongest positive grade wins. One logical document
therefore cannot receive contradictory or duplicate labels through renamed path aliases. The
property name remains compatible; its value is a UID for new real-feedback rows and may be a path
for legacy or synthetic rows.

> **Note 2026-09-05 (tempdoc 931 §C.4).** The paragraph below is the record as written on
> 2026-09-03 and is left intact. Two corrections since: the class is now
> `LabelStoreRegenerationKeepsUidKeysTest` (it regenerates the derived label store; it never
> rebuilt an index), and "composes with PR-A's real Blue→Green identity test" is no longer a
> delegation by comment — `DocumentIdentityBootImportTest.blueUidIsImportedBeforePausedMigrationReindexesIntoGreen`
> now asserts the surviving `doc_uid` on the production gRPC search response, i.e. at the wire where
> PR-B's consumer reads it.

The fast rebuild contract is covered by `LabelStoreSurvivesRebuildTest`: it persists authored UID
snapshots and dispositions, deletes and regenerates the derived label store twice, and asserts the
same exact UIDs after each rebuild. It composes with PR-A's real Blue→Green identity test instead of
opening Lucene from Head. Compatibility, alias ambiguity, repeated-hit deduplication, controller
projection, agent capture, chunk-parent enrichment, and old-hit deserialization have focused Java
regressions. The jseval feedback reader now accepts the Java writer's version-1 envelope as well as
legacy unversioned rows; its compatibility suite covers both UID- and path-keyed output and mirrors
the Java disposition-coalescing precedence.

The final affected-suite sweep passed **194 app-api tests**, **653 app-agent tests**, **2,524
app-services tests** (3 skipped), **933 ui tests** (1 skipped), and **1,170 worker-services tests**
(2 skipped), all with zero failures or errors. The focused contract command passed first, including
the new identity, projection, rebuild, controller, agent, and chunk-parent cases. The Python reader
suite passed 7 tests. The repository-wide `build -x test` compile/static/assembly check passed, as did
the LLM index, skill projection, canonical-link, module-boundary, runtime-configuration matrix,
canonical Markdown, tempdoc Markdown, ADR coverage, SSOT catalog sync, locale-invariance,
tempdoc-number, and pre-merge-table checks. `git diff --check` also passed.

Independent refute-first review found and drove three substantive fixes before that final sweep:
stable-UID coalescing for renamed aliases, fail-closed rejection of orphaned or nested child UIDs,
and identical disposition precedence in the Python reader. No correctness, integration,
compatibility, or security objection survived re-review.

No ranking, candidate-generation, or scoring decision changed in PR-B, so the deferred multi-hour
Phase 3 quality and storage campaigns were not run and no quality or performance claim is made here.

---

## Appendix A — Phase 2 and Phase 3 pre-implementation passes (transplanted verbatim, wave 3)

> Transplanted 2026-09-05 from branch `worktree-lane-D2` (commit `6075a1a6`, lines 1197-2112 of this file there) by the wave-3 orchestrator. The Codex continuation that implemented PR-A/C0/C2/C1/B on 2026-09-03 read these sections but condensed them into §P2/§P3 above instead of carrying them over, so the per-claim `file:line` verification tables (§P2.B 17 verified / 2 moved / 3 wrong; §P3.B 16 verified / 2 moved / 11 wrong / 13 new), the O15 codec finding that PR-C1 answers, O16, and the orchestrator decisions were absent from `main`-bound history. Tempdocs are append-only; this appendix restores the evidence chain without rewriting the sections above. Section numbering inside the appendix is as it was on lane-D2.

## Phase 2 — pre-implementation pass and design

Written on `worktree-lane-D2` at base `56e75cd7` (stacked on PR #620 = Phase 1). Reading and design
only: no build, no test run, no dev stack (lane E owns the machine). Every `file:line` below was
opened on THIS worktree. Phase 1's §B.2 already verified D1-D7; those are reused, not redone, and
§B's WRONG list (1c, 2a, 2c, 6b, D3, D4) is not re-litigated here.

### §P2.B Pre-implementation verification

Verdict counts: **17 verified · 2 moved · 3 wrong · 1 not-applicable · 6 new facts the brief does
not state**.

#### The identity write path

| # | Claim | Verdict | Evidence |
|---|---|---|---|
| P1 | `doc_id` = absolute path, `IndexingDocumentOps.java` ~143-149 | **moved (`:145`)** | `fields.put(SchemaFields.DOC_ID, absolutePath)` at `:145`; the value is `PathNormalizer.normalizePath(filePath.toAbsolutePath().toString())` at `:135`. Phase 1's §B recorded `:144` against base `39d38f73`; the file drifted by one line. |
| P2 | `doc_uid` = `UUID.randomUUID()` per write (parent) | **moved (`:146`)** | `fields.put(SchemaFields.DOC_UID, java.util.UUID.randomUUID().toString())` at `IndexingDocumentOps.java:146` (§B said `:145`). |
| P3 | `doc_uid` = `UUID.randomUUID()` per write (chunk), `ChunkDocumentWriter.java:132` | **verified** | `ChunkDocumentWriter.java:132`. |
| P4 | (not in the brief) a chunk's `doc_id` is **not** a path | **new fact** | `ChunkDocumentWriter.java:130-131` uses `ChunkIds.newChunkDocId()`; `ChunkIds.java:30` `CHUNK_PREFIX = "chunk:"`, `:51-53` returns `"chunk:" + UUID`. The class carries a `PERMANENT COMPAT` marker (`:21`). So "`doc_id` is the absolute path" is true of parents only. |
| P5 | Rename rewrites parent + up to 10,000 chunks, `WritePathOps.java:536-583` | **verified (span is `:536-592`)** | `updateDocumentPaths` at `:536`; `searcher.search(chunkQuery, 10_000)` at `:569`; loop `:574-583`; return `:591`. |
| P6 | (not in the brief) rename does **not** touch `doc_uid` | **new fact** | `updateDocumentPaths` updates only `DOC_ID`/`PATH`/`FILENAME` on the parent (`:551-553`) and `PARENT_DOC_ID`/`PATH` on chunks (`:580-581`). Both go through `readModifyWrite`, which reconstructs the document from its stored fields (`:325-347`) — so a **stored** `doc_uid` survives a rename with no code change at all. |

#### Feedback / GPL keying

| # | Claim | Verdict | Evidence |
|---|---|---|---|
| P7 | Feedback/LambdaMART/GPL key on `docId` = path | **verified** | `FeatureSnapshot.java:25` (`HitFeatures.docId`); `LabelProjection.java:64` (`byDoc.putIfAbsent(h.docId(), h)`), `:73`, `:89`, `:94`; `AgentDispositionWiring.java:106` (`str(f.get("docId"))`); `GplTrainingTripleStore.java:362`, `:370` (`node.put("doc_id", docId)`). |
| P8 | (extends the brief) where the path enters | **new fact** | Three producers, **none granted to this lane**: `SearchTool.java:388` `f.put("docId", hit.fields().getOrDefault("parent_doc_id", hit.id()))`; `FeatureSnapshots.java:43` `hit.id()`; `KnowledgeSearchController.java:171` takes `docId` verbatim from the FE POST body, and the FE posts `hit.id` (`modules/ui-web/src/shell-v0/state/searchState.ts:169-183`, callers `ResultsCard.ts:281`, `UnifiedChatView.ts:4528`). |

#### The path store, `jobs.db`, and ADR-0028

| # | Claim | Verdict | Evidence |
|---|---|---|---|
| P9 | `SqlitePathResolutionStore` / `PathResolutionStore` schema | **verified** | Interface `modules/worker-core/.../path/PathResolutionStore.java:28-77` (`record`/`markRemoved`/`lookup`/`pruneByRootPrefix`/`pruneOldRemoved`, `Resolution` record at `:76-77`). DDL `SqliteSchema.java:162-183`: `path_resolution(path_hash, normalized_path, last_seen_at, removed_at)` + indexes on `normalized_path` and `removed_at`. Impl opens **its own** connection to the same file (`SqlitePathResolutionStore.java:62-79`), UPSERT at `:92-106`. |
| P10 | `jobs.db` already holds the plain path | **verified** | `path_resolution.normalized_path` (`SqliteSchema.java:162-168`) plus the `jobs` rows themselves — `governance/store-recoverability.v1.json` `jobs-db.encryptionNote`: "Indexing-job rows are keyed on the user's absolute file paths … in the clear". |
| P11 | (new) `jobs.db` survives blue/green | **new fact** | `KnowledgeServer.java:452` `Path dbPath = dataDir.resolve("jobs.db")` — at the dataDir root. Index generations live under `index/*/…` (`index-generations` row `ownedPaths`). A generation swap cannot touch it. |
| P12 | ADR-0028 constraints | **verified** | `docs/decisions/0028-scoped-reverse-path-hash-lookup.md:65-71` — `path_resolution` is "the only persistent place where raw paths are stored after admission"; `:86-91` ArchUnit `LibraryResolveHashOnlyCallerPin`; `:93-112` retention (90 days after `removed_at`, **immediate** prune on unwatch); `:225-230` `MIGRATE_V6_TO_V7_ADD_PATH_RESOLUTION`. **Consequence:** a uid stored *inside* `path_resolution` would be destroyed by that retention and by prune-on-unwatch — the uid map must be a separate table with its own lifecycle. |
| P13 | (new) the register's `jobs-db` version is stale | **wrong (register)** | `SqliteSchema.java:34` `TARGET_VERSION = 10` (ladder runs to `MIGRATE_V9_TO_V10_ADD_FIRST_FAILED_AT`, `:283`), but `governance/store-recoverability.v1.json` `jobs-db` says `"format": "SQLite WAL schema v7"`, `"currentVersion": 7`, `readableLegacyVersions: [0..6]`. |
| P14 | (new) `jobs.db` corruption is quarantine-then-restore, not delete | **new fact** | `KnowledgeServer.java:476-489` catches SQLite code 11 → `handleCorruptDatabase` (`:2576-2606`): moves the file to `.corrupt` (preserved), quarantines the WAL, then restores from `.bak` if one exists. `corruptionPolicy: FAIL_OR_REBUILD_DERIVED_QUEUE` is accurate. |

#### The migration enumerator

| # | Claim | Verdict | Evidence |
|---|---|---|---|
| P15 | Green re-ingests from **source files**, not from Blue's stored fields | **verified** | `KnowledgeServerMigrationOps.enqueueAllFilesUnderRoots` `:904-1008`: `Files.walk(root)` `:934`, `.filter(Files::isRegularFile).filter(Files::isReadable)` `:935`, `batch.add(JobQueue.EnqueueEntry.stat(path))` `:974`, `jobQueue.enqueueEntries(batch)` `:976`/`:986`. Corroborated by `docs/explanation/11-index-schema-migration.md:392` ("the migration enumerator (which walks the filesystem and enqueues jobs)"). **Consequence: uids do NOT survive a rebuild for free. The store is load-bearing, exactly as the brief assumed.** |
| P16 | (new) Green resolves through the same in-process seam | **new fact** | The enqueued jobs are drained by the same `IndexingLoop` → `JobBatchExtractor` → `JobBatchWriter` → `IndexingDocumentOps.buildDocument` (`JobBatchWriter.java:111-124`) in the same Worker JVM, against the same `jobs.db`. Combined with P11, "Green writes the same uids Blue had" is true **by construction**, not by a copy step. |

#### Every reader of `doc_uid`, every writer of `doc_id`, chunk identity

| # | Claim | Verdict | Evidence |
|---|---|---|---|
| P17 | Readers of `doc_uid` today | **verified — Java + two docs only** | Production: `FieldMapper.java:323-327` (`validatePrimaryKeySupport` — missing ⇒ "Field catalog missing doc_uid tiebreaker"; non-docValues ⇒ throw), `:576-578` (`resolveDocUid`); `RuntimeSession.java:132,280,320` (`uidField`); `ReadPathOps.java:141` (always in the stored allowlist), `:365` (id fallback), `:377-378` (stripped from the response unless explicitly projected); `ChunkSearchOps.java:480`; `FolderBrowseEngine.java:211`; `IndexingCoordinator.java:247-256` (`MISSING_UID_FIELD` validation). Docs: `docs/explanation/04-storage-engine.md:52`, `docs/explanation/18-adapters-lucene-deep-dive.md:500`. Catalog: `SSOT/catalogs/fields.v1.json:15-24` + the byte-identical `modules/adapters-lucene/src/main/resources/SSOT/catalogs/fields.v1.json:16`. **Zero** hits in any `.proto`, in `modules/ui-web/**`, in `scripts/jseval/**`, in the MCP surface (`McpToolSurface.java` exposes `path`, `:998`), and `governance/execution-surfaces.v1.json` contains the string zero times. |
| P18 | `doc_uid` must be made `stored`/docValues so RMW preserves it (F-021/F-032 `rmwPolicy`) | **wrong as stated — already satisfied, and a policy would be REJECTED** | `fields.v1.json:15-24`: `doc_uid` is already `"stored": true, "docValues": true`, roles `["sort","tiebreak"]`. `FieldMapper.validateRmwPolicies` (`:198-245`) throws for a stored/docValues field that declares one: `:239-243` "declares rmwPolicy … but is stored or docValues-backed (RMW preserves it already) — remove the policy". Only the three genuinely fragile fields carry a policy (`fields.v1.json:180`, `:193`, `:433`). So Phase 2 must **not** add an `rmwPolicy` to `doc_uid`; the preservation the brief wants is already structural (P6). |
| P19 | Writers of `doc_id` | **verified — three production sites** | `IndexingDocumentOps.java:145` (parent, path); `ChunkDocumentWriter.java:131` (chunk, `chunk:<uuid>`); `WritePathOps.java:551` (rename). `ChunkSearchOps.java:356` and `FolderBrowseEngine.java:445` write it into *response* maps, not into the index. |
| P20 | Chunk identity fields and their joins | **verified** | `parent_doc_id` (`fields.v1.json:280-286`, keyword, stored+docValues, role `filter`), `chunk_index` (`:287-293`, long, stored+docValues), `is_chunk` (`:273-279`), `chunk_total` (`:294-300`). Joined by `TermQuery`/`termInSetFilter` on `parent_doc_id` at `ChunkSearchOps.java:122,189,516,704` and `WritePathOps.java:186,215,563`; surfaced to the agent at `SearchTool.java:357-362`. |
| P21 | `chunk_uid` exists anywhere | **verified absent** | Zero hits for `chunk_uid`/`CHUNK_UID` across `--include=*.{java,json,ts,py,proto,md,mjs,cjs}`, node_modules excluded. |
| P22 | Citations (`AgentCitationResolver`, `parentDocId`/`chunkIndex`, F-049) | **verified** | `AgentCitationResolver.java:15-16` (agent hits carry `parentDocId`+`chunkIndex`), `:85` (a RETRIEVED source matches on that pair), `:102`, `:120` (the old positional re-derivation is gone). `docs/reference/search-quality-register.md:1235-1260` (F-049): the wire field was renamed `chunk_index` → `source_index` on `CitationMatchEntry` with the field number unchanged, and "the *retrieval* citation shape still carries a genuine `chunkIndex` … the document-relative ordinal". **All of it is path- and ordinal-keyed and Phase 2 changes none of it.** |
| P23 | Undo journal / agent-history transcripts keyed on `doc_id`/path | **not applicable** | `FileOperationLog.java:28` writes `{dataDir}/file-operations/{batchId}.json`; `:31`, `:35-38`, `:81-83` key undo on the real filesystem path plus a content digest, because an undo restores *files*. It is not an index concept and `doc_uid` is irrelevant to it. No transcript/history class in `modules/app-agent` keys on `doc_id`; agent sources are keyed by `path` (`AgentSession.java:644`). |
| P24 | FE deep-link uses `doc_id`/path | **verified** | `SearchTool.java:340` (`path`), `:357-362` (`parentDocId`, `chunkIndex`, `startLine`/`endLine`); the FE posts `hit.id` for dispositions (`searchState.ts:173,182,191`) and sends `docIds` filters built from the same id space (`unifiedChatRequest.ts:98`). |

#### Governance: what a new store actually costs

| # | Claim | Verdict | Evidence |
|---|---|---|---|
| P25 | "a new SQLite table/file needs … the floor ratchet advanced" | **wrong** | `governance/store-corruption-policies.v1.json` `ratchet.note`: "`durableStoreRows` is a **FLOOR**, not an equality: rows are meant to be added". `scripts/ci/check-store-recoverability.mjs:653-657` fails only when `rows < durableStoreRows`. The register holds **42** rows today against a floor of **36**. Adding a row requires no ratchet edit; *removing* one does. |
| P26 | (new) adding a durable-store **row** is an upgrade hazard | **new fact** | `scripts/release/app-release-assets.mjs:77-100` generates the release descriptor's `compatibility` table 1:1 from the register (`ownerId`←`id`, `role`←`recoverability`, `formatVersion`←`currentVersion`, `readableSourceVersions`←legacy ∪ current), refusing any non-`READY` row (`:78-80`). `modules/shell/src-tauri/src/updater.rs:32` embeds the register with `include_str!` **at the installed build's build time**, and `validate_store_compatibility:874-876` refuses when `release_owners.len() != local.durable_stores.len()` — "Release compatibility table is not a closed set". So a release that **adds a row** is refused by every already-installed build. `:884-892` likewise refuses any change to a row's `owner`, `recoverability` or `reconciliation`; `:893-901` requires the new release's `readableSourceVersions` to contain the installed `currentVersion`. Latent today (pre-v0.1.0, no installed base) — routed as Q5. |
| P27 | `UpgradeReconciliationProbe`'s closed owner set | **verified** | `loadOwnerRegister` (`:197-226`) reads `/governance/store-recoverability.v1.json` from the classpath, and **any** row whose `status` is not `READY` collapses the whole register to an error (`:209-211`) — the probe then refuses to attest the upgrade. It exposes `(id, currentVersion, owner)` per row (`:212-216`) and rejects duplicate ids (`:219-221`). A new row must therefore be `READY` in the same commit it appears. |
| P28 | (new) a new store class is auto-discovered by the gate | **new fact** | `scripts/governance/lib/persistence-write-scan.mjs:14` matches `DriverManager.getConnection` as a write-creating idiom; `check-store-recoverability.mjs:741` feeds the scan into the register check. A new `Sqlite…Store` opening its own connection (the `SqlitePathResolutionStore.java:70` pattern) must appear in some row's `implementationSources`. |
| P29 | Fingerprint physical projection | **verified** | `IndexFingerprint.java:38-59` (the in-list) and `:61-73` (the out-list, which explicitly excludes `rmwPolicy`); the per-field projection is exactly `id, type, stored, docValues, multiValued, analyzer, roles` + vector `dimension`/`similarity` — `SsotCommitMetadataSource.projectFields:206-238`, `IndexFingerprint.java:282-288`. Pinned by `CatalogPhysicalProjectionTest.java:83-160` (`aStoredFlagFlipIsAReindex:110`, `aRoleChangeIsAReindex:119`, `aDeletedFieldIsAReindex:128`, `theProjectionDropsRmwPolicyEntirely:92`). |

---

### §P2.C Design, tightened

#### (1) The uid

**Format.** A v4 UUID rendered canonically (`java.util.UUID.randomUUID().toString()`, 36 chars) —
122 random bits, **content-independent by construction**, which is what makes "two files with the
same content get different uids" a property rather than a test. Explicitly *not* a content hash and
not a path hash: both would collide on duplicate content and both would change when the file
changes. Reusing `UUID.randomUUID()` also means the value shape in the index is unchanged from
today, so nothing downstream that already tolerates a `doc_uid` string has to learn a new format.

**Where minted.** At **admission**, immediately beside the existing ADR-0028 record call —
`JobBatchExtractor.java:192-193` already has `envelope.pathHash()`, `envelope.normalizedPath()` and
`envelope.observedAtMs()` in hand. One call, `identityStore.resolve(pathHash, nowMs)`, returns the
existing uid or mints one, in a single `INSERT … ON CONFLICT DO NOTHING` + `SELECT` under the
store's `ReentrantLock` (the `SqlitePathResolutionStore.java:90-111` pattern). Minting at admission
rather than at document build means the uid exists before any extraction or embedding work, and one
resolve serves both the parent write and the chunk writes.

**Persistence.** A new table in **`jobs.db`** (not a new file — see (6)):

```sql
CREATE TABLE IF NOT EXISTS document_identity (
  path_hash     TEXT PRIMARY KEY,
  doc_uid       TEXT NOT NULL UNIQUE,
  first_seen_at INTEGER NOT NULL,
  last_seen_at  INTEGER NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_document_identity_uid ON document_identity(doc_uid);
```

Added by `MIGRATE_V10_TO_V11_ADD_DOCUMENT_IDENTITY` in `SqliteSchema.java`, `TARGET_VERSION` 10 → 11
(P13 also corrects the register's stale `7`). **Hash-keyed, and it holds no raw path** — that is the
ADR-0028 alignment: `path_resolution` remains literally "the only persistent place where raw paths
are stored after admission" (ADR-0028 `:65-71`), and the identity table cannot leak a path even if a
diagnostic export ever reached it, so it does not enlarge the `LibraryResolveHashOnlyCallerPin`
hazard class. It is a *separate* table, not a column on `path_resolution`, precisely because
`path_resolution` is pruned by retention and wiped on unwatch (ADR-0028 `:93-112`) and a uid must
outlive both (P12).

**Lookups.** path → uid: `sha256Hex(normalizedPath)` (the same digest the envelope already carries)
→ primary-key hit. uid → path: `document_identity` by the unique uid index → `path_hash` →
`PathResolutionStore.lookup(path_hash)` → path, subject to ADR-0028's caller rules. The deliberate
two hops keep the uid store path-free.

**Rename.** `identityStore.rekey(oldPathHash, newPathHash, nowMs)` — an UPDATE of the primary key.
The uid is **not** re-minted. Nothing changes in Lucene for the uid: RMW reconstructs from stored
fields, so the stored `doc_uid` rides through `updateDocumentPaths` untouched (P6).

**Delete-and-reindex, and retention.** The row is kept and `last_seen_at` bumped on every sighting;
there is **no `removed_at`, and no GC**. Justification: the row is two hex strings and two longs
(~120 bytes); ADR-0028's own sizing (`:134-137`) puts a 100K-document corpus at 10-20 MB *including*
the path column, so identity is a fraction of an already-accepted cost. Against that, any GC window
is exactly the window in which the label store silently orphans — the brief's survival requirement
is unconditional, so the retention must be too. The privacy argument that justifies pruning
`path_resolution` does not transfer: this table holds no path and a random uid discloses nothing. If
growth ever matters, the only correct GC is "no row for a path outside every watched root **and**
with no label referencing its uid" — a two-authority condition spanning Head and Worker, which is
why v1 does not attempt it (open question Q7).

**Chunk uid.** `chunkUid = parentUid + "#" + chunkIndex`, deterministic, written into the chunk
document's own `doc_uid` field at `ChunkDocumentWriter.java:132`. **No `chunk_uid` catalog field is
added** (P21 confirmed none exists to reuse; adding one would be the only thing in Phase 2 that
moves the fingerprint — see (3)). Uniqueness for the tiebreak role holds: a parent uid is a UUID and
never contains `#`, so no chunk uid can collide with a parent uid or with another chunk's. The shape
is not novel — existing fixtures already use `"doc-0#0"` (`BatchUpdateIntegrationTest.java:42`,
`ChunkSearchIntegrationTest.java:826`).

#### (2) Index fields

- `doc_uid`: **no catalog change**. It is already `stored:true, docValues:true` with roles
  `["sort","tiebreak"]` (P18). It must **not** gain an `rmwPolicy` — `FieldMapper:239-243` rejects
  one on a stored/docValues field. Only its *value semantics* change.
- `chunk_uid`: **not added**. The chunk's identity is its own `doc_uid` (above).
- **`doc_id` stays the Lucene primary key.** Recommended for Phase 2, with reasons: `idField` is
  resolved from the catalog role `id` (`FieldMapper.java:169-173`, `resolvePrimaryKey:569-573`) and
  drives `readModifyWrite`'s `updateDocument(new Term(idField, docId), …)` (`WritePathOps.java:317`,
  `:373`), six `DocumentFieldOps` term queries (`:81,123,182,255,304,358`), `ChunkSearchOps`
  (`:461,565,735`), the `docIds` filter surface the FE sends (`unifiedChatRequest.ts:98`,
  `searchState.scope.test.ts:124`), and the MCP/deep-link id space. Re-keying it buys nothing in
  Phase 2 — the whole point of `doc_uid` is that identity no longer *needs* to be the primary key —
  and costs a user-visible id change. Phase 3 or never.
- **What rename still rewrites.** Unchanged: parent `doc_id`/`path`/`filename`
  (`WritePathOps.java:551-553`) and, for up to 10,000 chunks, `parent_doc_id`/`path` (`:580-581`).
- **Can the 10,000-chunk rewrite drop to a docValues-only update?** **No, not in Phase 2.**
  `parent_doc_id` and `path` are *indexed* keyword fields that the chunk joins match by `TermQuery`
  (`ChunkSearchOps.java:122,189,516,704`; `WritePathOps.java:186,215,563`). `IndexWriter`'s
  docValues-only update rewrites the column, not the postings, so the join would keep matching the
  old path — a silent wrong-result bug, not a slow one. The rewrite can only shrink after the joins
  are re-keyed onto a `parent_doc_uid`, which is a Phase-3-or-later change with its own fingerprint
  cost. Stated here so a later reader does not rediscover it as an easy win.

#### (3) Fingerprint impact

**`index_fingerprint` does not move in Phase 2.** The physical projection is exactly
`id, type, stored, docValues, multiValued, analyzer, roles` (+ vector `dimension`/`similarity`) —
`IndexFingerprint.java:38-59`, `SsotCommitMetadataSource.projectFields:206-238`, pinned by
`CatalogPhysicalProjectionTest.java:92-160`. Phase 2 adds no field, deletes none, and changes no
attribute of `doc_uid`, so **no projected key changes**; `catalog_schema_version` moves only if
`fields.v1.json`'s `"version": "1.0.0"` (`:3`) is bumped, and it is not. This contradicts the
brief's assumption that "`doc_uid`/`chunk_uid` catalog changes are physical-shape changes" — they
would have been, had a `chunk_uid` field been added; the design avoids that.

**The ordering constraint this creates.** Without a fingerprint move there is no rebuild, so an
existing index keeps its per-write random `doc_uid` values until each document is next written, while
the store already holds the stable ones — a window in which a hit's `doc_uid` disagrees with the
store. Two ways to close it, and the recommendation is the first:

1. **Ship Phase 2 in the same release as Phase 3** (the programme's own wave-2 rule already merges
   lanes D and E before one release). Phase 3's catalog edits — `vector.similarity`,
   `chunk_content` `stored:false`, deleting `entity_*_text` — all move projected keys, and that one
   rebuild converges every uid. Zero extra cost.
2. If Phase 2 must ship alone, bump `IndexFingerprint.RENDERING_VERSION` `"1"` → `"2"` (`:94`, whose
   Javadoc names exactly this case) and say so in the PR body — a full rebuild bought deliberately.

#### (4) Feedback / GPL re-keying, and the backfill

The insight that keeps this small: the **path is only a join key inside one interaction**, and it is
the `FeatureSnapshot` — not the disposition — that has to carry durable identity. So:

- `FeatureSnapshot.HitFeatures` (granted) gains a nullable `String docUid` component.
- `FeatureSnapshots.capture` / `hitFeatures` (`:39-50`) and `SearchTool.feedbackFeatures` (`:385-395`)
  populate it from the hit's field map. `ReadPathOps.buildStoredAllowlist:141` **already** fetches
  `doc_uid` for every hit; `:377-378` only strips it when the caller did not project it, so the Head
  simply asks for it in a projection it already controls. No new retrieval work.
- `LabelProjection` (granted) writes `hf.docUid()` when present and falls back to `d.docId()`.
- `GplTrainingTripleStore.appendWithFeatures` (granted, `:360-370`) keeps its `doc_id` JSON key —
  renaming it would break the trainer's format for no gain — and documents it as "uid when known,
  path for pre-Phase-2 rows".
- `AgentDispositionWiring` (granted, `:101-119`) reads `docUid` off the feedback-features map.
- **No FE change, no disposition-wire change**: the FE keeps posting `hit.id`
  (`searchState.ts:173`), which still joins the snapshot; only the durable triple key changes.

**Backfill — the honest answer.** There is no sound path→uid backfill for pre-Phase-2 rows: their
snapshots never carried a uid, and the Head cannot resolve path→uid without a new Worker RPC
(head-never-touches-Lucene, and the store is Worker-side). Recommendation: **no backfill.** The
derived `real-feedback-triples.ndjson` is already declared "a rebuildable projection of these two
inputs" (`StoreCatalog.java:22-23`), so it is simply re-projected; pre-Phase-2 rows keep their path
key and exactly the survival they have today (none across a rebuild) — Phase 2 makes new labels
durable without pretending to rescue old ones. If the owner wants a real backfill anyway, the
minimal honest shape is a `ResolveDocUids(paths[])` Worker RPC, run once at Head boot when a
`feedback/.uid-backfill-v1` marker file is absent, writing the marker only after a clean pass;
idempotent by the marker and by re-projection being a pure function of its inputs. Recorded as Q3
rather than silently dropped.

#### (5) The migration enumerator — Green writes the same uids

Green re-ingests from **source files** (P15), so the uids cannot survive in the index; they survive
in the store. They are the *same* uids by construction, and the construction has three legs, each
verified: the enumerator enqueues into the same `JobQueue` (`KnowledgeServerMigrationOps.java:976`);
the same `IndexingLoop`/`JobBatchExtractor`/`JobBatchWriter` drains it in the same Worker JVM
(`JobBatchWriter.java:111`); and the store lives in `jobs.db` at the dataDir root, which no
generation swap touches (P11). The `resolve(pathHash)` for a path already seen returns the existing
row — Green mints nothing.

**The seam:** `DocumentIdentityStore` is injected exactly like `PathResolutionStore` —
`IndexingLoopOptions.java:39-45` (nullable, defaults to a NOOP), `IndexingLoop.java:136-139,341`,
supplier handed to the batch classes at `:419`. Interface in `worker-core` (so `worker-services` and
`indexer-worker` share it without inverting module direction, the reason given at
`PathResolutionStore.java:14-16`), SQLite impl in `indexer-worker/queue`.

**Named test:** `MigrationPreservesDocUidTest` — index a document, capture its `doc_uid`, drive a
blue/green cutover, assert the Green document carries the identical uid **and** that the store row's
`first_seen_at` is unchanged (so a green cannot come from a coincidental re-mint).

#### (6) Store-recoverability

**No new register row.** Phase 1's §B.2 D6 already established that the `jobs-db` row's `ownedPaths`
cover the file and that `SqlitePathResolutionStore.java` is listed in its `implementationSources`;
P26 turns that from a convenience into a design constraint (a row added after v0.1.0 refuses the
in-app update for every existing install). Row edits, all safe under `updater.rs:884-901`:

- `currentVersion` 7 → 11, `readableLegacyVersions` `[0..10]`, `format` `"SQLite WAL schema v11"`
  — this both carries the new migration and fixes the pre-existing drift (P13).
- `implementationSources` += `modules/indexer-worker/.../queue/SqliteDocumentIdentityStore.java`
  (required by the write-site scan, P28); `tests` += its unit test.
- `encryptionNote` / a new `corruptionNote` extended to state what the identity table holds (a
  path *hash* and a content-independent random uid — no raw path, nothing path-derived).

**Unchanged, and must stay unchanged** (`updater.rs:884-892` refuses a release that alters them):
`owner: WORKER`, `recoverability: DERIVED`, `reconciliation`, and
`corruptionPolicy: FAIL_OR_REBUILD_DERIVED_QUEUE`.

**Why `DERIVED` stays honest, and why the existing policy fits.** A random uid is not derivable from
the source file — but it *is* recoverable from the live index, which stores `doc_uid` and `doc_id`
per document (`fields.v1.json:5-24`, both `stored:true`). So the store's recovery ladder is:
`.bak` restore (`KnowledgeServer.java:2602-2606`) → else rebuild `document_identity` by walking the
active index's non-chunk documents → else re-mint. That is exactly
`FAIL_OR_REBUILD_DERIVED_QUEUE`'s stated meaning ("either fails loudly or is rebuilt from the
durable source it derives from"), so **no vocabulary value needs coining** — which is the right
outcome, because `store-corruption-policies.v1.json`'s `extensionProcedure` warns that "26 values
for 36 rows is already close to one-per-row" and a near-synonym is drift. Both authorities lost at
once is a total-data-loss scenario in which orphaned labels are not the interesting damage; that
limit is documented, not hidden.

**Floor ratchet: no change** (P25 — 36 is a floor, 42 rows exist).

**`UpgradeReconciliationProbe`:** unaffected beyond reporting `jobs-db`'s new `currentVersion`. Its
closed owner set is the row list, which does not change. Every row must remain `READY` (`:209-211`)
— trivially satisfied since no row is added or downgraded.

**The rejected alternative,** recorded so it is not re-proposed: a separate `document_identity.db`
classified `AUTHORED` would pull it into the encrypted backup (a genuine benefit — uids would
survive a restore) but requires a new register row (P26 upgrade refusal), a new `StoreCatalog` enum
entry, and an `AUTHORED` **SQLite** store, a combination with no precedent (every sealed store today
is ndjson/json — `StoreCatalog.java:16-26`, `Framing` at `:29-38`). Not worth it for Phase 2.

#### (7) Tests

The brief's four, plus the ones this design implies:

| Test | Asserts | Precision note |
|---|---|---|
| `DocumentIdentityStoreTest` | mint-once idempotency; `resolve` after reopen returns the same uid; `rekey` moves the uid to the new hash and leaves `first_seen_at` alone | the uid must be read back from a **reopened** connection, or the test passes on an in-memory cache |
| `DocumentIdentityStoreTest.sameContentDifferentUids` | two paths with byte-identical content get different uids | the brief's requirement; a property of the mint, not of the corpus |
| `RenamePreservesDocUidTest` (adapters-lucene) | after `updateDocumentPaths`, the parent's `doc_uid` is byte-identical **and** `doc_id`/`path`/`parent_doc_id` did move | asserting only "uid unchanged" would pass if the rename silently did nothing |
| `DeleteAndReindexPreservesDocUidTest` | delete by path, re-ingest, uid identical | exercises the store, not the index — delete must not remove the identity row |
| `MigrationPreservesDocUidTest` | full blue/green cutover; Green's uid == Blue's (see (5)) | the `first_seen_at` assertion distinguishes "preserved" from "re-minted identically by luck" |
| `ChunkUidDeterminismTest` | every chunk's `doc_uid` == `parentUid + "#" + chunk_index`; regenerating chunks yields identical uids | `regenerateChunks` deletes and rewrites (`ChunkDocumentWriter.java:102`), so this is a real re-derivation |
| `LabelStoreRegenerationKeepsUidKeysTest` (was `LabelStoreSurvivesRebuildTest`) **+** `DocumentIdentityBootImportTest.blueUidIsImportedBeforePausedMigrationReindexesIntoGreen` | Head half: deleting and re-projecting the derived label store yields the same uid keys. Worker half: the uid survives Blue→Green and comes back on the gRPC search response's `fields["doc_uid"]` | the B6 headline; must run the projection, not just compare strings — and the rebuild half must be asserted where a rebuild actually happens (renamed/split 2026-09-05, tempdoc 931 §C.4) |
| `JobQueueMigrationTest` (extend) | the V10→V11 ladder applies; a V11 database refuses to open under a V10 build | the refusal path already exists (`SqliteQueueMigrationOps.java:60-65`, `:107-112`) |
| `DocumentIdentityCorruptionTest` | `.bak` restore keeps the identity table; **and** the no-`.bak` branch rebuilds from the index rather than throwing | `green-masked-destructive`: test the adverse precondition, not only the happy one |
| `DocumentIdentityAdr0028Test` | `document_identity` declares no path-shaped column, and the store is not reachable from the diagnostic-export call tree | sibling of `LibraryResolveHashOnlyCallerPin` (ADR-0028 `:86-91`) |

#### (8) Docs, catalogs, gates

- `docs/explanation/04-storage-engine.md:52` — the `doc_uid` row currently ends "not stable across
  full reindex"; it becomes stable, and the sentence must name the store that makes it so. Add a
  short "Document identity" subsection under §Schema Management (`:44`).
- `docs/explanation/11-index-schema-migration.md` — near `:392`, state that uids are carried across
  a rebuild by the identity store and **not** by the index, because Green re-ingests from source.
- `docs/explanation/18-adapters-lucene-deep-dive.md:500` — `doc_uid` is "Tiebreaker for search-after"
  **and** the stable document identity.
- `docs/decisions/0028-*.md` — a short amendment: a second hash-keyed table now shares `jobs.db`,
  holds no raw path, and is deliberately outside `path_resolution`'s retention. Editing
  `docs/decisions/**` triggers `--gate adr-coverage`.
- **SSOT catalogs: no edit.** Both copies stay byte-identical (SHA-256 `ef8291…f18aa4`, re-verified
  on this worktree), so the `/ssot-catalog` dual-copy step has nothing to do. `check-language-agnostic-analysis`
  still runs because its subject list includes `adapters-lucene/**`, which PR-A edits.
- `check-store-recoverability` for the register row; `node scripts/ci/check-store-recoverability.mjs`.
- **`--gate wire`: not required.** No `.proto` carries `doc_uid` (P17) and none gains it.
- **Wire visibility recommendation: internal only in Phase 2.** `doc_uid` stays off the search
  response, off MCP, and off the FE. F-016's schema-bloat argument applies directly: a uid is
  meaningless to an agent that already has `path` and cannot do anything with it, so exposing it
  buys nothing and costs every consumer a field to ignore. The single crossing is Head-internal —
  `FeatureSnapshots` reading `doc_uid` out of the projected field map (see (4)) — which is a
  projection request, not a schema change.

#### (9) PR plan — two PRs

- **PR-A — store, mint, index write, migration (B1-B4, six of the ten tests).**
  `SqliteSchema` V10→V11, `DocumentIdentityStore` (worker-core) + `SqliteDocumentIdentityStore`
  (indexer-worker), wiring through `IndexingLoopOptions`/`IndexingLoop`/`JobBatchExtractor`/
  `JobBatchWriter`/`DefaultWorkerAppServices`, the uid write in `IndexingDocumentOps` and
  `ChunkDocumentWriter`, the rename re-key, the `jobs-db` register row, docs 04/11/18 + the ADR
  amendment.
- **PR-B — feedback/GPL re-keying (B5, the label-survives-rebuild test).**
  `FeatureSnapshot`, `FeatureSnapshots`, `SearchTool`, `LabelProjection`, `AgentDispositionWiring`,
  `GplTrainingTripleStore`.

Reasons for splitting rather than one PR: PR-A is Worker-side and carries a SQLite migration, which
is the part that wants an unmixed diff and its own review; PR-B is Head-side feedback plumbing and
is **blocked on a scope extension that does not exist yet** (Q1) — bundling them would hold the
migration hostage to a grant decision. PR-B is also the only half that can be deferred without
leaving anything half-built: PR-A alone is complete and correct, it just does not yet spend the uid.

#### (10) Risks and open questions

| # | Question | Recommendation |
|---|---|---|
| Q1 | B5 needs `FeatureSnapshots.java`, `SearchTool.java` and `KnowledgeSearchController.java`, none granted to lane D and none claimed by another lane. | **Grant them.** Three small, feedback-only edits. Without them B5 cannot be implemented honestly at all — the uid never reaches the Head. |
| Q2 | `SqliteSchema.java`, `JobBatchExtractor.java`, `JobBatchWriter.java`, `IndexingLoop.java`, `IndexingLoopOptions.java`, `DefaultWorkerAppServices.java` fall under lane C's wholesale `modules/worker-services/**` + `modules/indexer-worker/**` grant (lane-C brief, Files-this-lane-owns). | **Explicit carve-out for lane D**, agreed with lane C before PR-A opens. The edits are additive wiring (one new injected store, one resolve call), not pacing or extraction — the boundary lane C's brief actually protects. |
| Q3 | No honest path→uid backfill exists for pre-Phase-2 feedback rows. | **No backfill**; re-project the derived triples and let pre-Phase-2 rows keep their path key. The RPC-plus-marker shape is specified in (4) if the owner disagrees. |
| Q4 | Phase 2 alone does not move the fingerprint, so index uids stay stale until each document is rewritten. | **Ship Phase 2 with Phase 3 in one release** (the wave-2 rule already requires it). If Phase 2 must ship alone, bump `RENDERING_VERSION` to `"2"`. |
| Q5 | Adding **any** durable-store row after v0.1.0 makes `updater.rs:874-876` refuse the in-app update for every installed build (P26). Latent now, permanent later. | **Route to lane B (governance)** as its own item. Lane D's design sidesteps it; the defect is real and gets worse the day a release ships. |
| Q6 | `jobs-db.currentVersion` is 7 against `TARGET_VERSION = 10` (P13) — the register has been wrong for three migrations. | **Fix it in PR-A**, which edits that row anyway. Bumping to 11 without first correcting the base would compound a wrong number rather than replace it. |
| Q7 | Identity rows are never GC'd. | **Accept.** ~120 bytes/document against a store ADR-0028 already sized at 10-20 MB per 100K documents. Revisit only on a measured table size, never on a schedule. |
| Q8 | `IndexingCoordinator.validate:247-256` fails a document with a blank `doc_uid`. If the identity store is NOOP (deferred boot, tests), the resolve must still return something. | The mint falls back to a fresh `UUID.randomUUID()` when the store is NOOP — today's exact behaviour, so a NOOP store degrades to the status quo instead of failing writes. Assert it in `DocumentIdentityStoreTest`. |

---

### §P2.D Cross-lane

- **Lane E (`ChunkDocumentWriter.java`).** Lane D changes exactly one line in it — the `doc_uid`
  write at `:132`. It does **not** touch `CHUNK_TARGET_TOKENS` / `CHUNK_OVERLAP_TOKENS` /
  `CHUNK_THRESHOLD_CHARS` (read at `:107` and `:115`), which are lane E's. The conflict surface is
  the file, not the semantics: coordinate merge order, and whichever lands second rebases.
- **Lane C.** Q2's six files, plus `KnowledgeServer.java`, which both lanes touch (lane C: sandbox
  wiring; lane D: the migration block and, now, the identity-store construction beside the existing
  `PathResolutionStore` construction at `:865`).
- **UI lane / frontend (out of scope for the whole programme).** **No FE change is required** under
  this design. Deep links stay path-keyed (`SearchTool.java:357-362`), the disposition POST keeps
  sending `hit.id` (`searchState.ts:173`), and `doc_uid` never reaches the wire. If the owner
  overrides (8) and makes `doc_uid` wire-visible, that becomes an FE change and needs its own grant.
- **Lane B (governance).** Two items: Q5 (the updater's closed-set refusal, a real latent defect) and
  — only if the owner rejects (6) in favour of a separate `document_identity.db` — the fact that an
  `AUTHORED` SQLite store has no precedent in `StoreCatalog`'s `Framing` vocabulary. Under the
  recommended design the `corruptionPolicy` vocabulary needs **no** new value.

### §P2.E Orchestrator decisions (2026-09-03, wave-2 orchestrator)

Every design choice in §P2.C (1)–(9) is **accepted as written**. The open questions resolve as:

- **Q1 (grant).** `FeatureSnapshots.java`, `SearchTool.java`, `KnowledgeSearchController.java` are
  granted to lane D for PR-B. No active lane owns them (lane C is closed and merged; the UI lane's
  scope is `modules/ui-web/**`), so the file-ownership contract has no other claimant.
- **Q2 (lane C carve-out).** Lane C's wholesale `worker-services/**` + `indexer-worker/**` grant
  expired when lane C closed (#602 merged). The six wiring files and `KnowledgeServer.java` are lane
  D's for PR-A. Whichever of lane D / lane E lands second on `ChunkDocumentWriter.java` rebases.
- **Q3 (no backfill).** Accepted, recorded as a deviation from the brief with the reason in §P2.C(4):
  pre-Phase-2 snapshots carry no uid and the Head cannot resolve path→uid. Old rows keep path keys;
  new rows key on `doc_uid`; the derived triples file is re-projected.
- **Q4 (release ordering / rendering bump).** No `RENDERING_VERSION` bump in Phase 2. Existing
  documents already carry a random per-write `doc_uid`; PR-A's first boot **imports** those uids from
  the index's stored `doc_uid`+`doc_id` into `document_identity` (the same rebuild-from-index path the
  corruption policy names), so the random uid becomes the stable one without a rebuild. Phase 3's
  catalog edits move the fingerprint anyway; Phase 2 does not need to.
- **Q5 (updater closed set).** Routed to the governance lane (918 successor) as a latent defect: the
  register cannot gain a `durableStores` row after the first release without `updater.rs:874-876`
  refusing in-app updates. Not lane D's; Phase 2 adds no row, so it does not trigger it.
- **Q6.** Fix `jobs-db.currentVersion` (7 → the real `SqliteSchema.TARGET_VERSION` at PR-A time) in
  PR-A, with the migration V10→V11 bumping it once more.
- **Q7 (no GC).** Accepted.
- **Q8 (store unavailable).** **Fail closed**: if `document_identity` cannot be read or written, the
  indexing write for that document fails and is retried by the job queue; never mint a fallback uid.
  Two authorities for one identity is the fork this phase exists to remove.

Sequencing: PR-A and PR-B are built after #620 merges and after lane E's Part 1 sweep window
releases the machine (Gradle builds contaminate its throughput columns). Phase 3's pre-implementation
pass runs in this worktree next, read-only.

---

## Phase 3 — pre-implementation pass and design

Written on `worktree-lane-D2` at base `5fef799c` (stacked on PR #620 = Phase 1, and on §P2). Reading
and design only: **no build, no test run, no dev stack, no jseval** (lane E owns the machine). Every
`file:line` below was opened on THIS worktree. Phase 1's §B.2 already verified D1-D7 and found the
brief's `chunk_content` consumer list wrong (D3/D4, open item O4); those verdicts are reused, not
redone. Lucene facts were read out of the resolved artifact
(`lucene-core-10.4.0.jar`, `gradle/libs.versions.toml:9`) with `javap`, not from memory.

### §P3.B Pre-implementation verification

Verdict counts over P1-P42: **16 verified · 2 moved · 11 wrong · 13 new facts the brief does
not state**. The wrong list is P3, P4, P5, P6, P17, P23, P30, P31, P38, P40, P42.

#### The codec and quantization (brief item 4, C1)

| # | Claim | Verdict | Evidence |
|---|---|---|---|
| P1 | `JustSearchCodec` no-arg constructor is Float32, demoted "for backwards compatibility with existing indexes" | **verified** | `JustSearchCodec.java:39-45` — `this(float32Format())`, comment at `:40-43`. Quantized path `:81-87`. |
| P2 | `ComponentsFactory` `quantEnabled` default false unless explicitly true | **verified (moved to :187)** | `ComponentsFactory.java:187` `Boolean.TRUE.equals(idx.vectorQuantizationEnabled())`; format choice `:189-194`; `cfg.setCodec(new JustSearchCodec(kf))` `:196`. Config key `index.vector.quantization.enabled` (`EnvRegistry.java:1005-1007`, `ResolvedConfigBuilder.java:1555`). |
| P3 | The quantized format is `Lucene104HnswScalarQuantizedVectorsFormat`, "7-bit scalar, confidence interval 0.99" | **wrong (the Javadoc is stale)** | The class exists (`JustSearchCodec.java:86`) but in Lucene **10.4.0 it has no confidence-interval parameter at all** and the 2-arg `(m, ef)` constructor defaults to `ScalarEncoding.UNSIGNED_BYTE` (8-bit), not 7-bit — `javap -c Lucene104HnswScalarQuantizedVectorsFormat` shows `getstatic ScalarEncoding.UNSIGNED_BYTE` in both the no-arg and the `(int,int)` constructor. `JustSearchCodec.java:22-23` ("7-bit scalar", "Confidence Interval: 0.99") describes the retired Lucene99 format. Fix the Javadoc in the same PR. |
| P4 | Binary quantization needs a different Lucene format class | **wrong** | Same class. `Lucene104ScalarQuantizedVectorsFormat.ScalarEncoding` exposes `UNSIGNED_BYTE`, `PACKED_NIBBLE`, `SEVEN_BIT`, `SINGLE_BIT_QUERY_NIBBLE`, `DIBIT_QUERY_NIBBLE`; and the format's SPI `NAME` is literally `"Lucene104HnswBinaryQuantizedVectorsFormat"`. So the brief's "binary-quantized HNSW experiment" is a **third constructor argument**, not a new dependency — matching the register's FW-008 post-cutoff note (`search-quality-register.md:3186`). |
| P5 | "Lucene reads segments written with a different `KnnVectorsFormat` and rewrites them on merge" | **WRONG, and this is the blocker for C1** | `JustSearchCodec.knnVectorsFormat()` (`:52-55`) returns the **raw** format, overriding `FilterCodec`'s delegation to `Lucene104Codec`'s `PerFieldKnnVectorsFormat`. On read, `SegmentInfos.readCodec` resolves the codec by NAME through SPI (`javap SegmentInfos` → `Codec.forName`), i.e. the **no-arg** `JustSearchCodec()` = Float32; and `SegmentCoreReaders` calls `codec.knnVectorsFormat().fieldsReader(state)` directly (`javap SegmentCoreReaders`, offsets 373/378). Nothing records which format wrote the segment. The two formats' files do not overlap (`Lucene99HnswVectorsFormat` `.vem`/`.vex` + `Lucene99FlatVectorsFormat` `.vemf`/`.vec`; `Lucene104ScalarQuantizedVectorsFormat` `.vemq`/`.veq`), so a quantized segment opened by a fresh JVM is unreadable. **Consequence: flipping the default is not a one-line change** — see §P3.C(1)a. This also retro-explains the 2026-01-21 demotion comment exactly. |
| P6 | `VectorFormatDetector` "already reports mixed states" | **wrong in practice** | The `MIXED` branch exists (`VectorFormatDetector.java:132-133`) but is unreachable on a real index: `inspect()` prefers commit metadata (`:74-84`) and `vector_format` is written on **every** commit (`SsotCommitMetadataSource.java:141-149`), so it always reports the writer's *current* setting for *all* segments; and the `inspectSegments` fallback reads `segmentInfo.getCodec().knnVectorsFormat()` (`:112-113`), which by P5 is the SPI-default Float32 for every JustSearchCodec segment. No test in `VectorFormatDetectorTest` (`:20-190`) constructs a mixed index. The detector is honest only about what the *writer* is configured to do. |
| P7 | (new fact) `JustSearchCodec` is SPI-registered under one name | **new** | `modules/adapters-lucene/src/main/resources/META-INF/services/org.apache.lucene.codecs.Codec` contains exactly `io.justsearch.adapters.lucene.runtime.JustSearchCodec`. One name = one read-time format choice for the whole installed base. |

#### Similarity and the 702 thresholds (brief item 5, C2)

| # | Claim | Verdict | Evidence |
|---|---|---|---|
| P8 | `FieldMapper` builds the vector field with the 2-arg `KnnFloatVectorField`, so similarity is EUCLIDEAN | **verified** | `FieldMapper.java:428` `doc.add(new KnnFloatVectorField(def.id, vec));`; `javap KnnFloatVectorField` shows the 2-arg constructor delegating with `getstatic VectorSimilarityFunction.EUCLIDEAN`. |
| P9 | Similarity is settable per field | **verified** | Lucene 10.4 offers `KnnFloatVectorField(String, float[], VectorSimilarityFunction)` **and** `(String, float[], FieldType)` plus `createFieldType(int, VectorSimilarityFunction)` (`javap KnnFloatVectorField`). A per-field `FieldType` built once per `FieldDef` is the right shape (it also lets `chunk_vector` differ from `vector` if we ever want that). |
| P10 | Vectors are L2-normalised by the encoder | **verified** | `OnnxEmbeddingEncoder.java:1062-1076` (`l2Normalize`) — reused from §B.1 5b, not re-litigated. |
| P11 | EUCLIDEAN score scale is `1/(1+d²)` and DOT_PRODUCT is `(1+dot)/2` | **verified exactly** | `javap VectorSimilarityFunction$1.compare` → `VectorUtil.squareDistance` then `normalizeDistanceToUnitInterval`, whose bytecode is `1/(1+x)`. `VectorSimilarityFunction$2.compare` → `dotProduct` then `normalizeToUnitInterval`, whose bytecode is `max(0, (1+x)/2)`. For unit vectors `d² = 2−2cos`, so `score_euc = 1/(3−2cos)` — which is what the code comments already say. |
| P12 | 702 calibrated `vector_low_signal_top_score_threshold` "and friends" | **verified, and the set is exactly two** | `HybridSearchOps.java:51` `DEFAULT_VECTOR_LOW_SIGNAL_THRESHOLD = 0.294` (comment `:47-49` records the intent as cosine-score **0.40**); `HybridSearchOps.java:74` `ARBITRATION_DENSE_CONFIDENT_MIN = 1.0/3.0` (comment `:62-72` records the intent as **cos ≥ 0**). Config counterpart `ResolvedConfigBuilder.java:1723` (`index.hybrid.vector_low_signal_top_score_threshold`, default 0.294, comment `:1721-1722`), env row `EnvRegistry.java:1059`. Both are pinned with their derivations by `CalibrationConstantsTest.java:19-42`. A repo-wide grep for `tempdoc 702` returns only these files plus `ResolvedConfig.java:831` and two test comments — **no third calibrated threshold exists**. |
| P13 | (new fact) the intent values were authored in cosine-score space | **new, and it makes C2 arithmetic-exact** | Both comments state the *intended* gate in cosine terms and the current value as its EUCLIDEAN image. Pinning `dot_product` therefore **restores the original intent** rather than inventing new numbers. |
| P14 | (new fact) CC fusion min-max-normalises each leg | **new** | `HybridFusionUtils.java:381-390`, `:746-751`. Min-max normalisation is invariant to a monotone *affine* rescale, but `1/(3−2cos)` (convex) → `(1+cos)/2` (linear) is not affine, so the dense leg's relative *spacing* changes and fused ranks can move even though per-leg ranks cannot. **The two constants map by exact arithmetic; the fused outcome still needs one confirmation run.** |
| P15 | The catalog declares `dimension` only, and the schema does not admit `similarity` | **verified** | `SSOT/catalogs/fields.v1.json` `vector`/`chunk_vector` both `{"dimension": 768}`; `SSOT/schemas/indexing/field-catalog.schema.json` `$defs.field.properties.vector` requires `dimension` with **`additionalProperties: false`** — a `similarity` key fails validation today. |
| P16 | The fingerprint already reads a per-field similarity | **verified — Phase 1 built the seam** | `SsotCommitMetadataSource.projectFields:217-220` reads `vector.similarity` when present and falls back to `DEFAULT_VECTOR_SIMILARITY = "euclidean"` (`:36-40`); `IndexFingerprint.java:292` emits it. So adding the catalog key moves `index_fingerprint` **automatically**. |
| P17 | "Update `SsotValidatorFingerprintTest`" | **wrong target** | `modules/ssot-tools/src/test/java/io/justsearch/ssot/tools/SsotValidatorFingerprintTest.java:26-49` pins the SHA-256 of the **`content_all` analyzer descriptor**, nothing about vectors or `fields[]`. It is untouched by C2. The pins that actually move are `CatalogPhysicalProjectionTest` (`modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/commit/CatalogPhysicalProjectionTest.java`) and `CalibrationConstantsTest.java:19-42`. |
| P18 | (new fact) Lucene 10.4 does **not** enforce unit norm for DOT_PRODUCT | **new** | `VectorUtil.isUnitVector` (tolerance 1e-4) is referenced only by `util/quantization/ScalarQuantizer` and `OptimizedScalarQuantizer` — no writer-side validation, no "not normalized" message anywhere in the jar. So a non-unit vector will not throw; it will silently score outside [0,1]. The encoder unit-norm test the brief asks for is therefore **the only** guard, and it matters more than the brief assumes. It also means quantization quality is coupled to normalisation, which is an argument for doing C1 and C2 together. |

#### Stored content and chunk geometry (brief item 6, C3)

| # | Claim | Verdict | Evidence |
|---|---|---|---|
| P19 | `content` and `chunk_content` both `stored:true`; 62 of 67 fields stored | **verified exactly (recounted)** | `SSOT/catalogs/fields.v1.json`: 67 fields, 62 with `stored:true`; `content` `:54`, `chunk_content` `:302`. Root and adapters-lucene copies byte-identical (SHA-256 `ef82915009…f18aa4`). |
| P20 | Chunk docs carry `chunk_start_char`/`chunk_end_char` | **verified** | Catalog `:310`, `:317` (`long`, `stored`+`docValues`, roles `filter,sort`); written at `ChunkDocumentWriter.java:140-141`. |
| P21 | The offsets are into the parent's stored `content`, post-normalisation | **verified, with the law stated in source** | `ChunkSplitter.java:826` states the invariant verbatim: *"The offset law is: `original.substring(startChar, endChar) == chunk.content()`"*, with `contentOffset` (`:793`) absorbing the leading strip and `:827-830` absorbing per-chunk strip. The `original` is the string passed to `regenerateChunks`, and the parent stores **that same string**: `IndexingDocumentOps.java:149` `fields.put(SchemaFields.CONTENT, extraction.content())` vs `:407,416` passing that same `extraction.content()` into `regenerateChunks`. The two VDU rewrite paths keep the pairing (`GrpcIngestService.java:804` writes `CONTENT` then `:835` re-chunks the same `extractedContent`; `KnowledgeServerMigrationOps.java:668-679` likewise). The law is already property-pinned (`ChunkSplitterCoreTest.java:383,489,837-841`; `ChunkTiling.java:18`). |
| P22 | (new fact) chunk documents carry **no** `content` field | **new** | `ChunkDocumentWriter.regenerateChunks` (`:92-196`) never puts `SchemaFields.CONTENT`. So `chunk_content` is a chunk doc's only text — removing `stored` leaves it BM25-searchable (indexed) with zero retrievable text of its own. |
| P23 | The `chunk_content` consumer list is `HighlightingOps` + 4×`RagContextOps` + `ChunkSearchOps` | **wrong — the real list is 15 sites in 9 files, and 4 of them are index-time** | Query-time: `ChunkSearchOps.java:337` (stored allowlist) and `:358-359` (projection); `RagContextOps.java:382`, `:1296`, `:1418` (`excerptTextFor`), `:1776-1787`, `:1807-1811`, and a **write** at `:878`; `SearchResponseBuilder.java:495` (wire strip), `:559` (evidence preview, F-041/774), `:605` (excerpt source); `CitationMatchOps.java:509,513`; `DocumentFieldOps.java:221` (stored-extraction routing). Index-time (the half the brief misses entirely): `EmbeddingBackfillOps.java:353-368`, `SpladeBackfillOps.java:90-92`, `CombinedEnrichmentBackfillOps.java:402,458-464,537-540,577-579`, `BgeM3BackfillOps.java:100-106`. |
| P24 | (new fact) `BgeM3BackfillOps` uses non-blank `chunk_content` as the **is-a-chunk predicate** | **new, and it breaks silently** | `BgeM3BackfillOps.java:100-106`: `boolean isChunk = chunkContent != null && !chunkContent.isBlank();` with the comment "Only ChunkDocumentWriter ever populates CHUNK_CONTENT". With `stored:false` this predicate becomes permanently false and every chunk would be re-embedded as a *parent*. Must switch to `SchemaFields.IS_CHUNK` (stored+docValues keyword). |
| P25 | (new fact) `HighlightingOps` still receives chunk text — indirectly | **new** | §B.2 D3 is literally right (`HighlightingOps` has no `CHUNK_CONTENT` reference), but `SearchResponseBuilder.java:559-572` copies the chunk text into `spanFields[CONTENT_PREVIEW]` and passes that map to `HighlightingOps.computeMatchSpans*` (`:589`). So highlighting **is** a chunk-text consumer, through the caller. |
| P26 | (new fact) a per-hit whole-document stored read is already on the excerpt path | **new** | `SearchResponseBuilder.java:608` calls `documentFieldOps.getDocumentContent(hit.docId())` for every non-chunk hit with excerpts on. The cost shape C3 introduces for chunk hits is therefore already paid for whole-doc hits — a precedent, and the natural comparison for the measurement. |
| P27 | (new fact) `FieldCatalogDef` is a **third** hand-maintained copy of the catalog | **new** | `modules/configuration/src/main/java/io/justsearch/configuration/FieldCatalogDef.java:109-160` (`forChunkTesting`) mirrors the SSOT field list in Java, including `chunk_content` `stored=true` at `:142`, `chunk_start_char`/`chunk_end_char` at `:144-145`, and two `new VectorSpec(vectorDim)` rows with no similarity slot. `/ssot-catalog`'s dual-copy rule covers two files; this is the third, and every `fields[]` change in C2/C3/C4 must touch it. |

#### The entity path (brief item 6, C4)

| # | Claim | Verdict | Evidence |
|---|---|---|---|
| P28 | Three `entity_*_text` fields, written by `NerBackfillOps` | **verified (§B.1 6b's corrected lines hold)** | Catalog `:388`, `:404`, `:420`; writes `NerBackfillOps.java:217`, `:221-222`, `:226` inside `applyEntityFieldUpdates` (`:211-228`). Value is `String.join(" ", raw)` — **the same tokens as the `_raw` field**, which is what F-010 (`search-quality-register.md:2556-2560`) refuted. |
| P29 | `entity_boost` defaults to 0.0 | **verified (moved to :1446)** | `ResolvedConfigBuilder.java:1446` `resolveDouble("justsearch.search.entity_boost", 0.0)`; env row `EnvRegistry.java:382`; adapters-lucene constant `TextQueryOps.java:56` `ENTITY_BOOST = 0.0f`. |
| P30 | The `_text` fields are "read only when `entity_boost > 0`" | **WRONG — there is a second, unconditional consumer** | `SearchResponseBuilder.concatDocEntityText` (`:744-756`) concatenates all three `_text` fields on every hit and feeds the result to `EvidenceSpanSelector` (`:613`, `docEntityText`). It does not consult the boost. Deleting the fields without repointing this is a silent evidence-span regression. |
| P31 | The DisjunctionMaxQuery goes "6 disjuncts → 3" | **wrong about today** | `TextQueryOps.combineMultiField:180-207`: the disjunct list is built conditionally, and `hasEntities` requires `entityBoost > 0.0f` (`:187`), which is false by default — so the shipped query already has **3** disjuncts (content + title + author). `buildEntityFieldQueries:230-232` early-returns `List.of()` when the boost is ≤ 0, so there is no parse cost either. C4 removes the *possibility* of 6, not a live 6. `docs/explanation/23-search-pipeline-overview.md:112` says "up to 6 disjuncts: `content` + `title`×3.0 + 3 entity text fields×2.0" — stale twice over (author is missing from the list; ×2.0 is not the default) and fixed as a ride-along. |
| P32 | ADR-0007's `any-of` probe must pass before and after | **verified — the predicate is safe** | `governance/adr-probes.v1.json:100-122`, id `adr-0007-entity-boost-retired-or-off`. Alternative 1 = `grep-present` for the literal `resolveDouble\("justsearch\.search\.entity_boost", 0\.0\)` in `ResolvedConfigBuilder.java`, `expect: 1`. Alternative 2 = `grep-absent` for `entity_persons_text\|entity_organizations_text\|entity_locations_text` in `SSOT/catalogs/fields.v1.json`. After C4, alternative 1 fails and alternative 2 passes, so the `any-of` passes. Its `note` cites `ResolvedConfigBuilder.java:1336` — stale (P29 says `:1446`); the note is prose, the grep is line-agnostic, so it is a ride-along comment fix, not a gate risk. |

#### The stop-word list and the replacement signal (brief item 7, C5)

| # | Claim | Verdict | Evidence |
|---|---|---|---|
| P33 | ~80 English stop words gate the vector leg | **verified (82 words)** | `HybridSearchOps.java:81-91` (`STOP_WORDS`), consumed at `:142` only for a **single-word** query; `shouldSkipVectorSearch:133-146`. |
| P34 | Callers of the heuristic | **verified, four** | `HybridSearchOps.java:361` (`executeHybrid`, the whole-doc hybrid leg reached from `SearchExecutor.java:318,324`), and `ChunkSearchOps.java:557`, `:619`, `:729` (the chunk branch). |
| P35 | `vector_skip_min_chars` | **verified** | Default 4 at `ResolvedConfigBuilder.java:1715`, fallback `HybridSearchOps.java:52`, env row `EnvRegistry.java:1041`, pinned by `CalibrationConstantsTest.java:78-82`. |
| P36 | "who else reads `numDocs`/`docFreq` at query time" — cost of the replacement signal | **new fact, and it makes the signal nearly free** | The exact computation the brief specifies **already runs on every lexical query**: `SearchInputCapture.computeQpp:232-262` analyses the query with the index analyzer and calls `TextQueryOps.getQppSignals(CONTENT, terms)` (`TextQueryOps.java:568-598`), which in **one** searcher acquisition reads `reader.numDocs()` (`:576`) and `reader.docFreq(t)` per term (`:591`), then derives `queryScope = Π(1 − df/numDocs)` (`SearchInputCapture.java:260`). A second accessor `TextQueryOps.getTermDocFreqs(field, terms)` (`:550`) already exists in the same package as `HybridSearchOps`. There is **no new per-query cost** if the decision reuses one of these. Caveat: `computeQpp` runs only when `sparseEnabled || spladeEnabled` (`SearchInputCapture.java:191`). |
| P37 | (new fact) the vector-skip is **invisible on the wire, and the trace actively lies about it** | **new, and it blocks the brief's own verification plan** | `SearchTrace.Degradation` has `vectorBlocked`/`vectorBlockedReason` (`SearchTrace.java:60-66`) and a `DENSE_RETRIEVAL` stage (`:79`), but `SearchTraceProjector.legsOfMultiLeg:157-176` (`:168` is the `Bm25Dense` arm) derives dense status **purely from the planned `LegSet`**: `case LegSet.Bm25Dense bd -> new LegExec(EXECUTED, null, EXECUTED, null, …)`. `shouldSkipVectorSearch` fires *below* that, inside `HybridSearchOps.executeHybrid:361`. So for every skipped query the trace reports `dense-retrieval: executed` with no reason. `SearchReasonCode` (`:32-71`) has no member for a heuristic skip either. **The "skip rate per language" measurement the brief asks for cannot be taken today** — and this is the same class of defect as register F-012 (a leg reported as executed when it was not). |
| P38 | The gate would catch a per-language artifact | **wrong** | `scripts/ci/check-language-agnostic-analysis.mjs:1-21` enforces four things: locale-invariant analyzers, no `content_<lang>` catalog field, no non-empty per-language synonym/dictionary file **under the catalogs dir**, and no `content_<lang>` literal on the query path. A hardcoded English word list in a Java query-path file matches none of them. ADR-0043's own backstop did not see the artifact ADR-0043 forbids. |
| P39 | (new fact) `config-surface` is a shrink-only ratchet | **new** | `gates/config-surface/baseline.txt`: `yaml_keys 111`, `env_sysprop_pairs 250`, `config_keys 56`; `scripts/governance/gates/config-surface/classifications.mjs:1-4` "only-shrinks", growth requires a `declared-growth` changeset. Plus `dead-config.mjs`: a key that resolves but is read by nothing **fails**, so C4 must delete `justsearch.search.entity_boost` end to end, not just its consumer. |

#### Measurement instruments (C1/C3 evidence)

| # | Claim | Verdict | Evidence |
|---|---|---|---|
| P40 | jseval reports recall@50 | **wrong** | `scripts/jseval/jseval/metric_families.py:63` — the relevance family is exactly `nDCG@10, P@1, R@10, RR@10, AP@10`. Union recall is a separate family, `leg_union_recall` (`:165`). Latency is `ce_p50_ms, retrieval_p50_ms` (`:81`). |
| P41 | (new fact) ANN recall@k **does** exist — in the benchmark harness | **new, and it is the better instrument** | `EngineVectorIndexBench.java:469-474,573-589,636-639` computes exact-KNN-vs-ANN recall at the configured query limit and emits `recall_at_k` / `recall_k`, alongside `index_size_bytes` (`:628`), `vector_bytes_total` (`:630`), `query_p50_ms`/`p95` (`:633-634`) and the knob echo `ann_quantization_enabled` (`:728`) with an `index.vector.quantization.enabled` override (`:204-205`). This is exactly the float32-vs-int8 comparison C1 needs, and it is deterministic. |
| P42 | jseval captures index size and RSS | **half-wrong** | `index_size_bytes` is captured (`scripts/jseval/jseval/ingest.py:140`, from the status snapshot's `indexSizeBytes`) but is **deliberately excluded from the perf gate** as too noisy (CV 11-62%, `perf_gate.py:22`, register `:2878`) — usable as a reported number, not as a gate. **RSS is not captured at all**: jseval's `perf-footprint` family (`metric_families.py:105-118`) is *model-file* bytes (`resident_bytes` = summed model footprint), and the only process-RSS reading in the whole harness is the best-effort top-5 snapshot in `env_fingerprint.py:186-196` (`ws_mb`). C1's RSS column must be measured explicitly (§P3.C(1)d). |

---

### §P3.C Design, tightened

#### (1) Quantized vectors by default

**(a) The blocker first: the codec cannot decide the format at construction time.** By P5/P7, the
format a segment was written with is recorded nowhere, and the read-time codec is always the SPI
no-arg instance. Flipping `quantEnabled` to true would produce an index that the *next boot* cannot
open. Three options, and the recommendation is the third:

1. *Do nothing and flip anyway.* Rejected: it is the unreadable-after-restart bug above.
2. *Return `Lucene104Codec`'s `PerFieldKnnVectorsFormat` (delete the `knnVectorsFormat()` override
   and use `getKnnVectorsFormatForField`).* This is Lucene's own mechanism — the format's SPI name
   goes into the FieldInfo attributes and the reader resolves it per field per segment, which makes
   mixed segments genuinely work and makes P5 true. But it is **not backward compatible**:
   `PerFieldKnnVectorsFormat$FieldsReader` throws `IllegalStateException` when the attribute is
   absent (`javap` offsets 84-123), and every existing segment lacks it. Blue must stay readable
   while Green is built, so this alone is fatal.
3. **Recommended — version the codec by name, which is what codec names are for.** Keep
   `JustSearchCodec` exactly as it is (Float32 raw format, no behaviour change) as the **read-only
   legacy reader** for pre-migration segments, and add `JustSearchCodecV2` registered as a second
   SPI entry, whose `knnVectorsFormat()` delegates to the base codec's `PerFieldKnnVectorsFormat`
   with `getKnnVectorsFormatForField` returning the configured format. New segments carry the name
   `JustSearchCodecV2`; old segments carry `JustSearchCodec`; `SegmentInfos.readCodec` picks the
   right one per segment with no version sniffing and no shim logic. A mixed index reads correctly,
   merges correctly (the merged segment is written by the writer's codec, V2), and `float32` /
   `int8_sq` / a future binary encoding are all one config value from then on.
   `JustSearchCodec`'s constructor taking a format becomes unused in production and is deleted from
   the write path; the class keeps only the no-arg reader constructor.

   `VectorFormatDetector` is repointed at the same time: `inspectSegments` becomes the primary
   (`PerFieldKnnVectorsFormat` makes it truthful), commit metadata becomes the fallback, and the
   `MIXED` branch gains its first test (a two-segment index written with both formats).

**(b) What flips.** `ComponentsFactory.java:187` default becomes `!Boolean.FALSE.equals(...)`, i.e.
on unless explicitly disabled; `JustSearchCodec.java:22-23`'s stale Javadoc is corrected (P3);
`quantizedFormat(m, ef)` (`:85-87`) keeps `ScalarEncoding.UNSIGNED_BYTE` (the 2-arg constructor's
default, 8 bits — this is the "int8" the field name and the fingerprint value already claim).
**Both `vector` and `chunk_vector` switch**: the format is chosen per *codec*, and under (a)3 per
*field*, but there is no reason to split them — one encoder, one normalisation, one evidence set.
Config surface: **no new key** (`index.vector.quantization.enabled` already exists,
`EnvRegistry.java:1005-1007`), only its default changes, which is not surface growth (§C.15's
precedent). `vector_format` in the fingerprint goes `float32` → `int8_sq` through
`SsotCommitMetadataSource.java:141-149` with no code change.

**(c) The pre-registered accept rule.** Decide before running, per the brief's ≤ 1 % absolute bar:

> **ACCEPT int8_sq by default iff**, on `scifact` and one lexical-heavy corpus (`enron-qa`),
> `nDCG@10(int8) ≥ nDCG@10(float32) − 0.010` **and** `R@10(int8) ≥ R@10(float32) − 0.010` on both,
> **and** ANN `recall_at_k` at k=50 from `EngineVectorIndexBench` is ≥ 0.97 of the float32 arm,
> **and** `index_size_bytes` falls. Any single miss → the flip does not ship and the finding is
> recorded against FW-008. `retrieval_p50_ms` is reported, not gated (it is expected to *improve*).

**(d) Arms (exact commands).** Run detached overnight (the 60-minute task kill,
`agent-lessons.md`), one Gradle build at a time.

```
# Quality — end-to-end, per arm; --clean forces a full rebuild so the arm's codec is the one on disk
jseval run --dataset scifact  --modes hybrid,vector --pipeline --start-backend --clean --embedding --json
jseval run --dataset enron-qa --modes hybrid,vector --pipeline --start-backend --clean --embedding --json
#   arm A0: index.vector.quantization.enabled=false   (float32 baseline, today's default)
#   arm A1: index.vector.quantization.enabled=true    (int8_sq)

# ANN recall@50 + size, deterministic, no eval corpus needed
./gradlew.bat :modules:benchmarks:run -PmainClass=io.justsearch.benchmarks.EngineVectorIndexBench \
    -PbenchArgs="--query-limit 50 --quantization-enabled false"    # A0
./gradlew.bat :modules:benchmarks:run -PmainClass=io.justsearch.benchmarks.EngineVectorIndexBench \
    -PbenchArgs="--query-limit 50 --quantization-enabled true"     # A1
#   read recall_at_k, recall_k, index_size_bytes, vector_bytes_total, query_p50_ms (:628-639)

# Binary-quantized experiment on chunk_vector — REPORT ONLY, no accept rule
#   arm A2: getKnnVectorsFormatForField("chunk_vector") ->
#           new Lucene104HnswScalarQuantizedVectorsFormat(ScalarEncoding.SINGLE_BIT_QUERY_NIBBLE, m, ef)
#           (P4: same class, third constructor); same two commands as A1.
```

**(e) RSS (P42 — jseval does not capture it).** Measure the **Worker** process (`IndexerWorker`; the
Head never touches Lucene, so the HNSW graph is not in its address space). Procedure per arm: start
the stack, ingest, wait for enrichment complete, run 200 warm vector queries, then sample the
Worker's `psutil.Process(pid).memory_info().rss` three times 10 s apart and report the median. The
pid comes from the dev-runner's process record (`quick_health { detail: "full" }`). Report as a
table, not a gate — one machine, no cross-machine evidence (which is FW-008's actual open item,
`search-quality-register.md:3186`).

**(f) Mixed-segment behaviour after the flip.** With (a)3, an index whose Blue segments are
`JustSearchCodec`/float32 and whose newly-merged segments are `JustSearchCodecV2`/int8_sq reads
correctly, and Lucene rewrites the old segments into the new format on merge. But note the design
does **not** rely on that: the fingerprint moves, so blue/green rebuilds Green wholly under V2. The
mixed state is a *transitional dev-machine* state (and the operator-escape state), not the user's
upgrade path.

#### (2) Similarity pinned to `dot_product`

**Change set.** (i) `SSOT/schemas/indexing/field-catalog.schema.json` — add
`"similarity": {"type": "string", "enum": ["euclidean", "dot_product", "cosine", "maximum_inner_product"]}`
to `$defs.field.properties.vector.properties` (the object is `additionalProperties: false`, P15, so
the enum is the whole permission). The enum is the four `VectorSimilarityFunction` members, spelled
in the catalog's snake_case; the mapping to the Lucene enum is one `switch`. (ii) Both catalog
copies gain `"similarity": "dot_product"` on `vector` and `chunk_vector` (`/ssot-catalog` dual-copy;
they must stay byte-identical — assert the SHA again). (iii) `FieldCatalogDef.VectorSpec` gains the
same field and `forChunkTesting`'s two vector rows are updated (P27 — the third copy).
(iv) `FieldMapper` builds one `FieldType` per vector `FieldDef` at construction
(`KnnFloatVectorField.createFieldType(dim, similarity)`) and `:428` becomes
`new KnnFloatVectorField(def.id, vec, def.vectorFieldType)`. (v) An encoder test asserting
`|‖v‖ − 1| < 1e-4` on real `OnnxEmbeddingEncoder` output for ASCII, CJK, and empty-ish inputs —
load-bearing because Lucene will not check (P18).

**Fingerprint.** `fields[].vector.similarity` moves `euclidean` → `dot_product` with **no
fingerprint code change** (P16). `CatalogPhysicalProjectionTest` updates; `SsotValidatorFingerprintTest`
does **not** (P17).

**Threshold recalibration.** For unit vectors `d² = 2 − 2cos`, so `score_euc = 1/(3 − 2cos)` and
`score_dot = (1 + cos)/2`. The closed-form map from any EUCLIDEAN threshold `t` is

> `cos = (3 − 1/t)/2`  →  **`t_dot = (5 − 1/t)/4`**

| Key | Site | Old (EUCLIDEAN) | New (arithmetic) | Needs jseval? |
|---|---|---|---|---|
| `ARBITRATION_DENSE_CONFIDENT_MIN` | `HybridSearchOps.java:74` | `1.0/3.0` | **`0.5`** (intent cos ≥ 0) | no — exact |
| `DEFAULT_VECTOR_LOW_SIGNAL_THRESHOLD` | `HybridSearchOps.java:51` | `0.294` | **`0.40`** (the original intent, P13) | no — exact |
| `index.hybrid.vector_low_signal_top_score_threshold` default | `ResolvedConfigBuilder.java:1723` | `0.294` | **`0.40`** | no — exact |
| `CalibrationConstantsTest` derivations | `:19-42` | `1/(3−2cos)` | `(1+cos)/2` | no — rewrite the comments + both assertions |
| `ARBITRATION_OVERLAP_MAX` | `HybridSearchOps.java:77` | `0.1` | `0.1` | no — rank Jaccard, score-free |
| `bm25IncoherenceMin`, `bm25_low_signal_*` | `HybridSearchOps.java:262,286`, `ResolvedConfigBuilder.java:1724-1725` | — | unchanged | no — intra-BM25 |
| `cc_alpha` / `cc_weight_*` | `ResolvedConfigBuilder.java:1707-1711,1729` | — | unchanged **by arithmetic** | **yes** — P14: min-max normalisation is not invariant to the non-affine warp, so fused ranks can move |

`ResolvedConfigBuilder.java:1721-1722`'s comment ("Default is in EUCLIDEAN score space … not the
cosine-score space the field was originally calibrated for") is deleted, not edited — the deviation
it documents ceases to exist.

**Recalibration evidence (one confirmation run, not a re-derivation).** Because the two constants
map exactly, the arm answers a narrower question: *does the fused ranking move?*

```
jseval run --dataset scifact  --modes hybrid --pipeline --start-backend --clean --embedding --json   # B0 euclidean+float32 (today)
jseval run --dataset scifact  --modes hybrid --pipeline --start-backend --clean --embedding --json   # B1 dot_product only
jseval run --dataset enron-qa --modes hybrid --pipeline --start-backend --clean --embedding --json   # B2 dot_product, second corpus
```
Accept: `|ΔnDCG@10| ≤ 0.005` on both, and the low-signal gate fires on a comparable fraction of
queries (read `decisionKind` + the dense-stage status from the trace). A larger move means the
warp mattered and `cc_alpha` needs its own sweep — hand that to lane E rather than guessing.
Record the run ids in the register under FW-008 and F-023/636.

#### (3) Stop storing `chunk_content`

**The change.** `chunk_content` becomes `"stored": false` in all three catalog copies (P27), stays
`type: text`, `analyzer: icu`, role `highlight` — so BM25 on the chunk branch
(`ChunkSearchOps.java:106,165,238`) is untouched. `fields[].stored` is a projected key, so the
fingerprint moves (P16 / §C.2).

**Prove the offset law before removing the copy.** The law is stated in source and property-pinned
at the *splitter* level (P21), but nothing pins it at the *index* level. Add
`ChunkOffsetSliceEqualsStoredChunkContentTest` (adapters-lucene or system-tests, over a real
`IndexingCoordinator`): index a document whose content exercises leading/trailing Unicode
whitespace, CRLF, a Markdown fence, and non-BMP characters; for **every** chunk doc assert
`parentContent.substring(chunk_start_char, chunk_end_char).equals(chunk_content)` byte for byte.
This test runs **before** the `stored:false` change lands (it must pass on today's index), and stays
afterwards with the right-hand side replaced by the newly-sliced value. That converts "is it the
same string?" from an assumption into a red/green fact — the `audit-without-test` discipline applied
to a data-shape claim.

**Rewriting the readers (P23's real list).** Two mechanisms, not one:

- *Query-time (per chunk hit).* `ChunkSearchOps`' chunk-hit projection (`:330-375`) already holds
  `searcher.storedFields()` and iterates `topDocs.scoreDocs`. Restructure as a **two-pass batched
  read**: pass 1 collects each chunk's `parent_doc_id` + offsets from the (unchanged) stored
  allowlist minus `CHUNK_CONTENT`; pass 2 resolves the **distinct** parents (one `TermQuery` seek on
  `doc_id` each, reusing `DocumentFieldOps`' existing lookup), reads each parent's `content` once,
  and slices. Cost is *one parent read per distinct parent*, not per hit — chunk hits cluster
  heavily on few parents (chunk-merge exists precisely because they do). `RagContextOps`,
  `CitationMatchOps` and `SearchResponseBuilder` then read the same populated
  `fields[CHUNK_CONTENT]` they read today, so **their call sites do not change at all** — the
  substitution happens once, at the projection. `RagContextOps.java:878` (a write) and
  `excerptTextFor:1418`'s `CONTENT` fallback keep working unchanged.
- *Index-time (backfill).* `CombinedEnrichmentBackfillOps.java:388-402` already does a batched
  stored-field fetch per doc batch; extend that batch to fetch the distinct parents' `content` and
  slice, and drop `CHUNK_CONTENT` from `fieldsToFetch` (`:402`). `EmbeddingBackfillOps.java:353`,
  `SpladeBackfillOps.java:90-92` and `BgeM3BackfillOps.java:100-106` get the same helper. **P24 is a
  separate fix in the same PR**: `BgeM3BackfillOps`' `isChunk` predicate moves to
  `SchemaFields.IS_CHUNK`.
- `DocumentFieldOps.java:221`'s stored-extraction routing drops its `CHUNK_CONTENT` clause.

**Measuring the cost the brief asks for.** "Stored-field cost per chunk hit" is measurable without a
new instrument: `retrieval_p50_ms` (`metric_families.py:81`) covers the Worker-side retrieval stage
including hit projection, and `ce_p50_ms` isolates the cross-encoder so a CE-stage move would be
noise, not signal. Arms: `jseval run --dataset scifact --modes hybrid --pipeline --start-backend
--clean --embedding --json`, before and after, ×3 repeats each (the CV on `retrieval_p50_ms` is the
gate-able one per `perf_gate.py`). **Decision rule, pre-registered:** if median `retrieval_p50_ms`
rises by **> 15 %** across three paired repeats, abandon `stored:false` for a `chunk_preview`
(the first `preview.max_chars` characters of the chunk, already a fingerprint input via
`ChunkDocumentWriter.CONTENT_PREVIEW_MAX_CHARS`) and keep offsets for the full text. Note P26: a
whole-doc stored read per hit is *already* on the excerpt path, so the prior is that this is cheap.

**Size.** `index_size_bytes` from the status snapshot (`ingest.py:140`) before/after on a fixed
corpus, reported not gated (P42). Also report `chunk_completeness` (`scripts/jseval/jseval/
chunk_completeness.py`) — it counts chunk *documents*, which must not change; a move there means the
write path broke, not the storage.

#### (4) Delete `entity_*_text` and the entity text-boost path

**Deletions.** `TextQueryOps.java:55-56` (`ENTITY_BOOST`), `:63-66` (`ENTITY_TEXT_FIELDS`), `:129`
(the call), `:187` + `:199` (the `hasEntities` branch in `combineMultiField`), `:217`
(`resolveEntityBoost`), `:230` (`buildEntityFieldQueries`); `NerBackfillOps.java:217,221-222,226`
(the three `_TEXT` puts, keeping every `_RAW` put); `SchemaFields.java:146,150,154` and their entries
in `SchemaFields`' field-name list; the three catalog fields at
`SSOT/catalogs/fields.v1.json:388,404,420` **and** the adapters-lucene copy; the config key end to
end — `EnvRegistry.java:382`, `ResolvedConfigBuilder.java:1446`, the `ResolvedConfig.Search`
component and its accessor, and the runtime-config-matrix regeneration (P39: a resolving-but-unread
key **fails** `dead-config`, so a partial deletion is worse than none). Facets on `entity_*_raw` are
untouched, as is the disambiguation-cluster filter expansion
(`23-search-pipeline-overview.md:133`).

**P30 is the item the brief missed and it must be handled here.**
`SearchResponseBuilder.concatDocEntityText:744-756` feeds `EvidenceSpanSelector` unconditionally.
Repoint it to `entity_persons_raw` / `entity_organizations_raw` / `entity_locations_raw`: the `_text`
value is literally `String.join(" ", raw)` (P28), so the token bag is identical *provided* the hit's
field map renders a multi-valued stored keyword field as a joined string. **Open question Q-P3-2**
below, because that rendering is a `ReadPathOps` detail I did not open here and the answer decides
whether this is a one-line repoint or needs a join at the projection.

**Governance.** `fields[]` shrinks by three → fingerprint moves (P16). `config-surface`
`env_sysprop_pairs` 250 → 249, a shrink, so the baseline is tightened in the same PR (P39).
ADR-0007's probe passes by alternative 2 (P32); its stale line citation is corrected as a ride-along.
**Lane B notification** (cross-lane request, below): ADR-0007's amendment can now say *"the entity
text-boost fields are retired; entity FACETS on `entity_*_raw` are the shipped answer, and the
probe's surviving alternative is the catalog absence"*. Docs:
`23-search-pipeline-overview.md:98` (drop the `_text` half of the NER row) and `:112` (rewrite the
DMQ description — 3 disjuncts, `content` + `title` + `author`, per P31).

**No quality arm is required** and claiming one would be dishonest: the boost is 0.0 by default
(P29), `hasEntities` is false, and `buildEntityFieldQueries` returns empty — the produced Lucene
query is byte-identical before and after. What *does* need a check is the evidence-span path: a unit
test asserting `EvidenceSpanSelector` receives the same `docEntityText` from `_raw` as it did from
`_text` for a hit with all three entity kinds populated.

#### (5) Replace the stop-word list with a document-frequency signal

**The signal.** Skip the dense leg when **no** analyzed query term is discriminative:

> `skip ⟺ queryText.length() < vector_skip_min_chars` **OR**
> `∀ t ∈ analyze(query) : docFreq(content, t) / numDocs ≥ F`

with `F = 0.25` as the default. Rationale for 0.25 rather than a tighter value: the 82-word English
list is, empirically, *very* common words; on a 5k-document corpus "the" has df/N ≈ 1.0 and a topical
term ≈ 0.01, so any F in [0.1, 0.5] separates them — 0.25 is the midpoint of the plateau and errs
toward *running* the dense leg (a wrongly-run dense leg costs latency; a wrongly-skipped one costs
recall, which is worse). New config key `index.hybrid.vector_skip_min_df_fraction`
(`EnvRegistry` row + `ResolvedConfig.HybridSearch` component + `ResolvedConfigBuilder` resolve, clamped
to [0,1]), which nets **exactly against** the `justsearch.search.entity_boost` deletion in (4) —
`env_sysprop_pairs` stays at 250 and no `declared-growth` changeset is needed (P39), *if* (4) and
(5) ship in the same PR. `vector_skip_min_chars` is kept unchanged (P35) and stays a *separate*
constant: F-036 / 784 §K is the standing lesson about one constant serving two levers.

**Where the decision lives — and the defect that decides it.** P37 is a blocker, not a detail: the
skip currently happens below the planner, so `SearchTraceProjector` reports
`dense-retrieval: executed` for every skipped query, and the brief's own multilingual verification
(skip rate per language) is unmeasurable. Two options:

1. *Keep it in `HybridSearchOps`* and read df through the sibling `TextQueryOps.getTermDocFreqs`
   (`:550`) that the class can already reach. ~20 lines, entirely inside lane D's files. But the
   reason still cannot reach the trace, so P37 stays open and the measurement stays impossible.
2. **Recommended — move the decision into `SearchPlanner.selectLegSet`** (`SearchPlanner.java:146`),
   where the leg set is chosen and where the QPP signals (P36) are already in `SearchInputs` by
   construction (`SearchInputCapture.java:191` runs before `plan`). A query whose terms are all
   non-discriminative plans `LegSet.Bm25Only` instead of `Bm25Dense`, so
   `SearchTraceProjector.legsOfMultiLeg:162-163` emits `denseStatus=SKIPPED` with the reason **for
   free**, `SearchTrace.Degradation.vectorBlockedReason` is populated through the existing path, and
   `HybridSearchOps.shouldSkipVectorSearch` plus `STOP_WORDS` are **deleted outright** along with
   their four call sites (P34) — the chunk branch inherits the same decision because it runs under
   the same plan. This is a `retire-with-a-sweep` deletion, not a rewrite, and it fixes P37 as a
   side effect rather than as extra work.

   Cost of (2): `computeQpp` currently runs only when a lexical leg is enabled
   (`SearchInputCapture.java:191`). That is exactly the right condition — **never skip the dense
   leg when it is the only leg** (a dense-only or vector-mode query must always run dense), so the
   guard becomes explicit rather than incidental. `getQppSignals` already returns per-term `docFreqs`
   and `numDocs`, so the marginal per-query cost is **zero**; nothing new is read.

**New reason code.** `SearchReasonCode` (`:32-71`) gains one member in the search-routing partition,
`SKIPPED_NO_DISCRIMINATIVE_TERM`, plus a `DEGRADATION_REASON_WORDING` entry in
`modules/ui-web/src/…/searchTraceExplain.ts` and a row in
`governance/search-degradation-reason-codes.v1.json` — otherwise
`check-search-degradation-reason-codes` fails FORWARD (no raw code to users). Wording must not name a
mechanism the user cannot act on: *"Semantic ranking was skipped — every word in this query is
common across your documents."*

**Empty / tiny index.** `numDocs == 0` → `getQppSignals` returns `QppSignals(0, …)` and
`computeQpp` returns `ZERO` (`SearchInputCapture.java:243`). Rule: **`numDocs <= 0` never skips** (no
denominator, no evidence, and on a cold corpus the dense leg is the one with a chance). Below a
floor of 100 documents, also never skip: df/N on a 3-document index is meaningless and would skip
everything. Both are one-line guards with their own unit tests.

**Determinism for the eval gate.** df/numDocs changes as the corpus grows, so the *same query* can
skip at ingest-time T and not at T+1 — unlike the stop-word list, which was deterministic. This is
acceptable and in fact correct (the signal is "is this term discriminative *in this corpus*"), but
the eval harness must be pinned: jseval arms run against a fully-ingested, enrichment-complete index
(`--clean` + wait for `stage_complete`), which is already the register's standing protocol, so the
denominator is fixed within a run. State this in the register entry so a future reader does not read
run-to-run variation as a regression.

**ADR-0043 compliance.** The lever is bucket C (language-agnostic): it authors no per-language
artifact, derives entirely from the index's own term statistics through the ICU analyzer, and a new
language needs nothing. It is strictly *more* compliant than what it replaces. **And it closes a
gate hole (P38):** the same PR extends `check-language-agnostic-analysis.mjs` with a fifth check —
no authored word list (a literal `Set.of("a", "an", "the", …)`-shaped collection of natural-language
words) in the search-engine query path, register-driven from
`governance/language-agnostic-analysis.v1.json`. Without that, the artifact ADR-0043 forbids can be
reintroduced by the next agent exactly as it was introduced the first time.

**Multilingual verification plan.** Only possible after option (2); the field that reports it is the
dense stage's status+reason on `SearchTrace` (`SearchTrace.java:79`, `StageStatus.SKIPPED`,
projected by `SearchTraceProjector.java:118`), aggregated per run.

```
# Per corpus, TWO arms — and note these need NO --clean between arms: item (5) is query-time only,
# the index is byte-identical, so one ingest serves both. This is the cheap half of the campaign.
for D in scifact enron-qa legal-en miracl-de miracl-fr miracl-zh; do
  jseval run --dataset $D --modes hybrid --pipeline --start-backend --clean --embedding --json  # C0: stop-word list
  jseval run --dataset $D --modes hybrid --pipeline --embedding --json                          # C1: df signal (same index)
done
```
Accept: per-corpus dense-skip rate under C1 within **±5 percentage points** across the six corpora
(the point of the change is that the skip rate stops being an English-only artifact — under C0 the
non-English corpora skip ~0 % because no query word is in the English list, which is the bug), and
`nDCG@10` no worse than C0 − 0.005 on every corpus.

#### (6) Chunk size — lane E's number

Lane D changes **nothing**. `ChunkSplitter.DEFAULT_CHUNK_TOKENS` / `DEFAULT_OVERLAP_TOKENS` /
`MIN_CHUNK_TOKENS` / `ALGORITHM_VERSION` and `ChunkDocumentWriter.CHUNK_THRESHOLD_CHARS` are already
fingerprint inputs (§C.1), so lane E's constants PR moves `index_fingerprint` by itself and the
rebuild it needs is carried by Phase 1's blue/green default. The one coordination point is
§P2.D's note that lane D touches `ChunkDocumentWriter.java:132` (the `doc_uid` write) while lane E
touches `:107,115` — file conflict, not semantic; whichever lands second rebases. If lane E instead
hands over an `effectiveChunk*()` accessor edit, that is lane E's four lines in lane E's PR after
#620 merges; lane D neither writes nor reviews it beyond confirming the fingerprint picks it up.

#### (7) Bundle sequencing and PRs

Items 1-4 each move `index_fingerprint`; item 5 does **not** (query-time only). The release-level
constraint is the programme's, not the fingerprint's: *all* fingerprint-moving PRs land before one
release tag, so users pay one rebuild.

| PR | Contents | Moves fingerprint? | Blocked on |
|---|---|---|---|
| **PR-C0** | (5) df-based vector skip + `SKIPPED_NO_DISCRIMINATIVE_TERM` + the P37 planner fix + the `check-language-agnostic-analysis` fifth check; **and** (4)'s config-key deletion so the two config-surface deltas net to zero | **no** | nothing — can go first, even before Phase 2 |
| **PR-C1** | (1) codec versioning + quantization default-on, **and** (2) similarity pinned, with the schema + three catalog copies + `FieldMapper` `FieldType` + threshold recalibration | **yes** (1 move: `vector_format` + `fields[].vector.similarity`) | the evidence arms A0/A1/B0/B1/B2 green against the accept rules |
| **PR-C2** | (3) `chunk_content` `stored:false` + the batched parent slice + the offset-law test, **and** (4)'s catalog/field/query-path deletion | **yes** (1 move: `fields[]`) | the offset-law test green on the *pre-change* index |
| lane E | chunk constants | **yes** (1 move: `chunking.*`) | lane E's own sweep |

**Order:** PR-A (Phase 2 identity store) → PR-C0 → PR-C2 → PR-C1 → lane E → PR-B (Phase 2 feedback
re-keying, which is fingerprint-irrelevant and can slot anywhere after PR-A). PR-A first so the
identity store exists before the first rebuild, which then writes stable uids rather than importing
them (§P2.E Q4 makes either order correct; this one is strictly better). PR-C2 before PR-C1 because
C2's risk is a *correctness* risk (does the slice equal the stored string?) best discovered without a
simultaneous codec change in the diff, and C1's risk is a *quality* risk best measured against a
storage shape that has already settled.

**Why not one PR.** Bundling C1+C2+lane E would move the fingerprint once on `main` instead of
three times, but it would destroy evidence attribution: a −0.01 nDCG in a diff that changed the
vector encoding, the similarity function, the chunk text storage *and* the chunk size is
uninterpretable, and the `interrogate-results` rule then has nothing to interrogate. Three moves on
`main` cost three dev-machine rebuilds on small corpora; one uninterpretable eval costs the decision.
**Recommendation: accept three fingerprint moves on `main`, one rebuild for users.** Make that
explicit in each PR body so a reader does not think the release rule was violated.

#### (8) Verification matrix

| Gate / tier | Triggered by | Note |
|---|---|---|
| `check-language-agnostic-analysis` | catalog edits (C1/C2), and the new fifth check (C0) | run for every catalog-touching PR |
| `/ssot-catalog` dual-copy | C1 (similarity), C2 (`stored`, field removal) | re-assert the two copies' SHA-256 equality; **plus** the third copy `FieldCatalogDef.java` (P27), which the skill does not cover — flag it to the skill owner |
| `--gate wire` | only if `commit-metadata.schema.json` changes | it does **not** in Phase 3 — the fingerprint's *inputs* change, its wire shape does not |
| `--gate adr-coverage` + `adr-probes` | C2/C4 (ADR-0007 probe, ADR-0043 amendment) | probe must pass **before and after** (P32) |
| `config-surface` (+ `dead-config`) | C0 (key added), C4 (key removed) | net zero if bundled as in (7); `dead-config` fails on a half-deleted key |
| `check-live-witness` | none expected | `RegistrySnapshotExporter`/`LiveWitness` untouched; assert by running it |
| `check-search-degradation-reason-codes` | C0's new reason code | needs the FE wording entry + register row in the same PR |
| `check-store-recoverability` | none | Phase 3 adds no store |
| Full unit suite + `chaos`/migration tier | C1, C2 | the migration tier is the one that exercises Blue-readable-while-Green-builds, i.e. P5's fix |
| jseval arms A0/A1/A2, B0/B1/B2, C0/C1 per corpus | C1, C2, C5 | pre-registered accept rules in (1)(c), (2), (3), (5) |
| `EngineVectorIndexBench` recall@50 + size | C1 | P41 — the instrument jseval lacks |
| Size / RSS tables | C1, C2, C3 | reported, not gated (P42) |
| Independent review | all three PRs | reviewer ≠ committer (`independent-review-required`) |
| **Live validation** | PR-C1 above all | the blue/green upgrade **from a float32/EUCLIDEAN index**: build an index on `main`, upgrade the binary to the PR, confirm the fingerprint mismatch is detected pre-open, Blue serves reads throughout, Green is built under `JustSearchCodecV2`+`dot_product`+`int8_sq`, cutover completes, and a post-cutover restart opens the new index (the P5 falsifier — *this is the arm that would have caught the unreadable-after-restart bug*). This is arm 5's shape from the round-4 reviewer's procedure, re-pointed; O3/O10 already schedule it. |

#### (9) Risks and open questions for the orchestrator

| # | Question | Recommendation |
|---|---|---|
| Q-P3-1 | P5 turns C1 from "flip a default" into "version the codec". That is materially more work than the brief scoped, and it touches the read path for every existing index. | **Do it, in PR-C1, as designed in (1)(a)3.** The alternative is shipping a default that makes indexes unreadable after restart. It is also the fix that makes P6 (mixed-state detection) honest and makes any future format change — binary quantization included — a config value. Note it is *not* a backwards-compatibility shim: it is Lucene's own codec-naming mechanism used as intended. |
| Q-P3-2 | P30's repoint of `concatDocEntityText` to `entity_*_raw` assumes a multi-valued stored keyword renders as a joined string in the hit field map. I did not open `ReadPathOps`' multi-value rendering. | **Verify at implementation time, first thing in PR-C2.** If it renders only the first value, join at the projection instead (three lines in `ReadPathOps`' stored extraction), still lane D's file. Either way the evidence-span unit test in (4) is the falsifier. |
| Q-P3-3 | (5) option 2 edits `SearchPlanner.java` and `SearchTraceProjector.java` (worker-services, plan/respond packages), neither in lane D's declared file list. | **Grant them for PR-C0.** They are the only honest home for a leg-selection decision, and P37 is a live defect on `main` regardless of lane D. Lane C is closed and merged (§P2.E Q2), so no other lane claims them. If the grant is refused, fall back to (5) option 1 and route P37 to lane E as a search-observability defect — but then the brief's multilingual verification cannot be performed and that must be recorded as a deviation. |
| Q-P3-4 | The brief asks for recall@50 and jseval has none (P40). | **Split the metric between instruments:** `EngineVectorIndexBench` `recall_at_k` at k=50 for ANN recall (the quantization question), jseval `R@10` + `leg_union_recall` for end-to-end relevance. Recorded as a deviation from the brief's wording, not from its intent. |
| Q-P3-5 | Three fingerprint moves on `main` instead of one. | **Accept** (7)'s reasoning: evidence attribution over rebuild count; users still see one rebuild. If the owner prefers one move, the only sound way is to merge C1 and C2 as a stacked pair with *separate measured arms taken on the branch before squashing* — same evidence, one public commit. |
| Q-P3-6 | `JustSearchCodec.java:22-23` documents parameters that Lucene 10.4 does not have (P3); `23-search-pipeline-overview.md:112` documents a query shape that has not shipped since the boost defaulted to 0 (P31). Both are pre-existing. | **Ride-along fixes** in PR-C1 and PR-C2 respectively (verified one-line-class doc drift, per `log-pre-existing-issues`). |
| Q-P3-7 | Lane E is mid-sweep and both lanes will want the machine for these arms. The Phase-3 campaign is roughly **20 fingerprint-moving arms** (each `--clean`: cold start + full ingest + enrichment + query pass) plus **12 query-only arms** (no re-ingest). At the register's observed ingest rates that is on the order of **12-18 machine-hours**, best run as one detached overnight driver per PR. | **Schedule PR-C1's arms as a single overnight campaign after lane E's Part 1 window closes**, with `leaseDurationSec` set for the whole run. Do not interleave with lane E. The estimate is mine, from arm count × the register's ingest wall-clock figures (`search-quality-register.md:78`) — replace it with lane E's measured `primary_docs_s`/`enrich_docs_s` before committing to a window. |
| Q-P3-8 | P37 means every historical trace-derived claim about dense-leg execution on short/stop-word queries was wrong, including anything in the register that read `dense: executed`. | **Do not audit history.** Fix forward in PR-C0 and add one line to the register under F-012 (the closest existing entry, same defect class) noting that pre-PR-C0 traces over-report dense execution for queries under `vector_skip_min_chars` or single stop words. |

### §P3.E Orchestrator decisions (2026-09-03, wave-2 orchestrator)

All eight recommendations in §P3.C(9) are **accepted**. Specifically:

- **Q-P3-1** codec versioning (`JustSearchCodecV2` + legacy read-only reader) is in scope for PR-C1;
  O15 is a blocker for any quantization default and is fixed there, never worked around.
- **Q-P3-3** `SearchPlanner.java` and `SearchTraceProjector.java` are granted to lane D for PR-C0
  (no active lane claims them; 854 is an unstarted charter and its W2-fix touches
  `HybridFusionUtils`/`KnowledgeSearchEngine`, not the planner). PR-C0 also fixes O16.
- **Q-P3-4** split instruments (bench for ANN recall@50, jseval for R@10 + `leg_union_recall`) —
  recorded as a wording deviation from the brief, same as 916 §K.
- **Q-P3-5** three fingerprint moves on `main`, one rebuild for users — accepted; the release note
  names it.
- **Q-P3-7** PR-C1's evidence arms run as one detached overnight campaign after lane E's Part 1
  window; the machine-hour figure is re-estimated from lane E's measured `primary_docs_s` /
  `enrich_docs_s` before a window is armed.
- The threshold table's "exact arithmetic" rows still get one confirming jseval arm (scifact +
  legal, hybrid) in PR-C1 — a closed-form mapping is an argument, not a measurement.

**PR order on `main`:** #620 (Phase 1) → PR-A → PR-C0 → PR-C2 → PR-C1 → lane E constants → PR-B.
Implementation of PR-A starts when lane E's sweep releases the machine (Gradle contaminates its
throughput columns) and #620 has been re-checked on the operator arm (R2).

---

