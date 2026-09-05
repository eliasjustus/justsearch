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

**§A's "Review round", "Delta review round", and "O7 round" (Phase 1 independent-review fix checklists)** moved to `docs/tempdocs/915-evidence/review-rounds.md` (size-cap split, 930 §19.3 F4).

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

