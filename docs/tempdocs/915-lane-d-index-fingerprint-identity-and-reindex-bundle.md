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

**Phase 1 complete.** Extended by Phases 2 and 3, which have not started.

### PRs

- **#620** — `feat(915): one truthful index fingerprint, blue/green as the production default (lane D
  phase 1)`. Open, green, **not merged** (no merge authorisation given). It carries the whole of
  Phase 1 plus the wave-1 fold-ins and eight rounds of review/validation fixes. No other PR was
  opened for this tempdoc; the canonical-doc and tempdoc edits ride along in it (`docs-ride-along`).

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
