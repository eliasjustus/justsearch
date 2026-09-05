---
title: "Lane D: one truthful index fingerprint, stable document identity, and the reindex bundle"
type: tempdocs
status: "PHASE 1 MERGED; PHASE 2 PR-A IMPLEMENTED AND LOCALLY VERIFIED, PR-B PENDING; PHASE 3 PR-C0 IMPLEMENTED AND SHORT-CHECKED, PR-C2/PR-C1 PENDING (2026-09-03). The required six-corpus PR-C0 evaluation and PR-C1's overnight evidence campaign remain deferred and block their respective merges."
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
records what re-verification found. Phase 1 is merged. Phase 2 is split between the implemented
Worker-side PR-A and the pending Head-side PR-B. Phase 3 PR-C0 is implemented and short-checked;
PR-C2 and PR-C1 remain pending, and the deferred evidence campaigns still block merge.

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

**§A's "Review round", "Delta review round", and "O7 round" (Phase 1 independent-review fix checklists)** moved to `docs/tempdocs/915-evidence/review-rounds.md` (size-cap split, 930 §19.3 F4).

### Phase 2 — stable document identity (PR-A IMPLEMENTED; PR-B PENDING)

- [x] B1. Mint `doc_uid` once per logical document; preserve across API-supported rename,
      re-extraction, and full reindex.
- [x] B2. Persist the `path_hash`→uid map in SQLite next to the existing path store (ADR-0028:
      hash-keyed reverse lookups); decide and document which file.
- [x] B3. Deterministic chunk uids: `uid + "#" + chunkIndex`.
- [x] B4. Worker admission carries the store-resolved UID through `IndexingDocumentOps` and
      `ChunkDocumentWriter`; `GrpcIngestService` re-keys it around API path updates; `KnowledgeServer`
      imports serving-index identities before normal or migration indexing starts.
- [ ] B5. PR-B moves new feedback/GPL writes to `doc_uid` keys. Accepted compatibility rule:
      **no path-to-uid backfill** for pre-Phase-2 rows; legacy path-keyed rows remain readable as
      legacy data, while newly projected feedback/triples use uid keys.
- [x] B6a. PR-A test sources cover durable mint/reopen/import, distinct paths with equal content,
      API rename, delete/reindex, v10→v11 migration and rollback, chunk uid determinism, serving-
      index boot import, Blue→Green preservation, retry/idempotency, and fail-closed behavior.
      Execution evidence is recorded separately from this implementation checklist.
- [ ] B6b. PR-B owns the remaining label-store-survives-full-rebuild row.

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

**§B (pre-implementation pass: brief re-verification, §B.1–§B.4)** moved to `docs/tempdocs/915-evidence/pre-implementation-audit.md` (size-cap split, 930 §19.3 F4).

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

**§C.8 (the repeat-rebuild brake: design plus the two-correction incident writeup)** moved to `docs/tempdocs/915-evidence/design-postmortems.md` (size-cap split, 930 §19.3 F4).

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

**§C.13–§C.18 (deferred-writer reporting, what moving the decision earlier broke, the status-surface fix, refusal-vs-crash, and self-restart evidence loss)** moved to `docs/tempdocs/915-evidence/design-postmortems.md` (size-cap split, 930 §19.3 F4).

### §C.15 New/changed config keys

**None.** No new `System.getenv`/`getProperty` outside `io.justsearch.configuration`; no new
`EnvRegistry` row; `index.schema_mismatch.policy` and
`justsearch.index.parity.allow_mismatch` keep their names and resolution. Only the *default value*
of the former changed, which is not config-surface growth. `config-surface` is run in §G.

---

**§D–§G (implementation log, post-implementation critical pass, falsification record, verification results)** moved to `docs/tempdocs/915-evidence/implementation-and-verification-log.md` (size-cap split, 930 §19.3 F4).

---

**Cross-lane requests (Phase 1)** moved to `docs/tempdocs/915-evidence/report-back.md` (size-cap split, 930 §19.3 F4).

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

**Report-back's "PRs", "Items: done, deviated, skipped", "Evidence", "Measurements", "Cross-lane", and "Residue routed"** moved to `docs/tempdocs/915-evidence/report-back.md` (size-cap split, 930 §19.3 F4).

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

**Live product validation (2026-09-03): arm table, defects, observations, deviations, machine signature, and the two re-validation runs** moved to `docs/tempdocs/915-evidence/live-product-validation.md` (size-cap split, 930 §19.3 F4).

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
rename re-key, chunk UID derivation, canonical documentation, and governance update. PR-B alone owns
Head-side feedback/GPL re-keying (`FeatureSnapshot(s)`, `SearchTool`, `KnowledgeSearchController`,
`LabelProjection`, `AgentDispositionWiring`, and `GplTrainingTripleStore`) and the label-survival
test. PR-A does not expose UID on the wire or change the frontend. Lane E overlaps only
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
| 7 | Feedback labels survive a full rebuild by UID | **PR-B** | Pending: `LabelStoreSurvivesRebuildTest` |
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
