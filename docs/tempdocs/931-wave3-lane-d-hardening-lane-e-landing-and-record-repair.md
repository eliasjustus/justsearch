---
title: "Wave 3: lane D hardening, lane E landing, and record repair after the Codex continuation"
type: tempdocs
status: "IMPLEMENTED, AWAITING MERGE GO-AHEAD (2026-09-05). Every §B item done or recorded-skipped; PRs #643 (lane E), #645 (A+1b), #646 (C0+evidence), #647 (B+1d) green and unmerged; #648 (C2+C1+1a/1c/1e+findings 5/6) draft pending the C1 campaign. Owner decisions in §E: 1f identity/revision, chunk-SPLADE flag semantics (8), lane F."
created: 2026-09-05
updated: 2026-09-05
lane: D/E/F (decision re-examination programme, wave 3)
model: fable (orchestration), opus (implementation subagents)
category: index-identity, search-quality, publication-hygiene
related:
  - 915-lane-d-index-fingerprint-identity-and-reindex-bundle   # the work this wave hardens and publishes
  - 916-lane-e-search-quality-rederivation                     # the closeout this wave lands
  - 917-lane-f-derisk-and-consumer-audit                       # committed here, still read-only
  - 918-wave2-kernel-residue-repin-enforcement-and-ci-gate-wiring
  - 920-codex-cli-dual-harness-migration                       # why the continuation ran as Codex
---

# Wave 3: lane D hardening, lane E landing, and record repair

## §A Starting state (2026-09-05, verified)

The wave-2 handoff was continued by Codex agents on 2026-09-03/04 (thread "Take over Claude session
work", `01a06726…`). What they produced, and where it sat when this wave started:

| Package | Commit (local) | Location | Problem |
|---|---|---|---|
| Lane D PR-A identity store | `27995a05` | merged into **local** `main` (`189719e0`) | unpublished; tempdoc records "merged" only locally |
| Lane D PR-C0 DF dense-skip | `7618997c` | local `main` | tempdoc 915:1964 says "must not merge until six-corpus evidence"; merged anyway (owner-directed local merge) |
| Lane D PR-C2 unstored chunk text | `2dac6bd6` | local `main` | tempdoc 915:2018 says the same; storage measurement never run |
| Lane D PR-C1 codec V2 / Int8 / dot_product | `12acd8e1` | branch stacked on the local merge | 3-4 h campaign deferred |
| Lane D PR-B feedback by UID | `c3175a69` | branch stacked on the local merge | "survives rebuild" test never rebuilds |
| Lane E Part 1 closeout, Part 3 parked, Part 4 routed | `1c5f50df` on `worktree-lane-E-part1` | contains the lane D merge **and** local main's 292-commit divergence | pushing it to #622 would squash lane D + unrelated history under a lane E title |
| Lane D2 §P2/§P3 pre-implementation passes | `6075a1a6` on `worktree-lane-D2` | unmerged | the per-claim verification tables and O15/O16 never reached main-bound history |
| Tempdoc 917 (lane F consumer audit) | uncommitted in `lane-F` worktree | — | never committed |

All five lane D commits have empty bodies and no PR. Independent code audit (read-only, opus,
`file:line` at `codex/lane-d-pr-b`, spot-checked by the orchestrator) found:

1. **Silent wrong chunk text** — `WritePathOps.java:444-470` reconstructs `chunk_content` on any
   chunk RMW by slicing the parent `content` the *current* searcher shows. Parent write and chunk
   regeneration are separate coordinator calls (`JobBatchWriter.java:111,139`), so an NRT refresh
   between them exposes new parent content with old chunk docs; only an over-length slice throws.
2. **Unconditional full-index scan on every Worker boot** — `KnowledgeServer.java:881-883` imports
   identities with no empty-store/generation guard, accumulating every parent in heap, on the
   critical path before the switch buffer drains; a parent without `doc_uid`/`doc_id` docvalues
   throws `CORRUPT_INDEX` and exits the Worker.
3. **One zero/non-finite embedding aborts the whole indexing batch** — `IndexingCoordinator.java:361`
   calls `toDocument` in the batch loop with no per-document catch; `VectorNormalization` throws.
4. **`LabelStoreSurvivesRebuildTest` never rebuilds an index** — it regenerates the derived label
   file; the rebuild property is delegated by comment to PR-A's test.
5. **Forced global reindex** — C1 flips both the quantization default (`int8_sq`) and the similarity
   default (`dot_product`), both fingerprint inputs. Intended, but C2 is on local main and C1 is not,
   so publishing them separately would make users rebuild twice.
6. Lesser: `doc_uid` now appears in chunk-only hits under the default projection
   (`SearchResponseBuilder.java:648-652`); `PathHash` applies no Unicode NFC; identity rows persist
   after delete, so a deleted file replaced by an unrelated one at the same path inherits its
   feedback (path-slot semantics, raised by the independent design review).

Also from the independent design review, verified at source: Phase 1's fingerprint is all-or-nothing
(`IndexFingerprint.compute` returns empty when any model digest is INDETERMINATE and
`IndexMetadataParityGuard` then only warns, `:170-185`), so an unreadable NER model file disables the
vector-dimension check it does not depend on. And lane E's OHR incumbent artifact records 105
staged-recall reconciliation mismatches over 962 queries
(`…/t500-o50-r0/20260903T125636_mixed_ohr-bench-clean/projections/staged_recall_accounting.json`).

## §B Plan

Owner constraints for this wave: proceed autonomously through plan, design and implementation; any
benchmark or eval that would take longer than **2 hours** is skipped and recorded with its estimate.

| # | Item | Owner in this wave | Estimate | Decision |
|---|---|---|---|---|
| 0a | Rebuild lane E closeout as a content diff on `origin/main` (`worktree-wave3`, branch `worktree-wave3`) | orchestrator | done | `e29119ca`; no `src/main` change vs `origin/main` |
| 0b | Rebuild the lane D stack on `origin/main` by cherry-pick (`worktree-wave3-d`) | orchestrator | done | A `7a2aa3b8` → C0 `1adca15d` → C2 `8db980c9` → C1 `3de10e34` → B `98cefd18`; conflicts: `EnvRegistry` lifecycle-stage arg (×2), `module-filter.yml` deleted upstream (#641), config matrix regenerated |
| 0c | Restore the lane-D2 §P2/§P3 passes into 915 as an append-only appendix | orchestrator | done | `4556b3d5` |
| 0d | Commit tempdoc 917 as-is | orchestrator | trivial | in the D branch |
| 1a | Chunk RMW revision guard (audit finding 1) | subagent A (adapters-lucene) | code | **done** `4d5275a1` → draft #648 (§C.1, §D) |
| 1b | Boot identity import: skip when already imported, stream in batches, do not kill the Worker on a legacy parent (finding 2) | subagent B (indexer-worker) | code | **done** `f04b985b` → #645 (§C.2, §D) |
| 1c | Zero/non-finite embedding: drop the vector field for that document, keep the document, count it (finding 3) | subagent A | code | **done** `4d5275a1` → #648 (§C.3) |
| 1d | Make the PR-B rebuild claim testable at the wire (finding 4) | subagent C | code | **done** `f8ed7549` (1d half) → #647 (§C.4) |
| 1e | Fingerprint: compare the determinate inputs when a model digest is indeterminate | subagent C | code | **done** `f8ed7549` + `bc968416` (symmetric) → #648 (§C.5) |
| 1g | *(found by 3c)* C2 combined-backfill loop on flag-off chunks; `rag.chunk_splade.enabled` honoured by one lane of three (findings 5, 6) | subagent (opus) | code | **done** `58c3c344` + `286a97bc` → #648 (§D, F-059) |
| 1f | Identity vs content revision (path-slot semantics) | — | design only | §C.6; not implemented this wave (Head+Worker contract, needs owner decision on feedback scoping) |
| 2a | Lane E: diagnose the 105 OHR reconciliation mismatches | subagent D (read-only over artifacts) | ≤30 min | §C.7 |
| 2b | Enron replicate set for 384/25 | — | ~1.5 h | **skipped with reason**: the pre-registered rule uses `max(σ, 0.0068)`, so extra replicates cannot lower the +2σ line below 0.0136 and 384/25's +0.0061 cannot clear it; only an owner amendment of the rule would change the verdict |
| 3a | C0 six-corpus check (C5b): per-language dense-skip rate | orchestrator, live | ~35 min (index-only, no enrichment wait) | **done, 17 min**: legal 2.0 %, five corpora 0.0 %; comparable per language (§D, F-058) |
| 3b | C0 quality no-regression on the two cheapest fully-enriched corpora (legal, miracl-de) | orchestrator, live | ~25 min | **done, paired vs PR-A control**: legal +0.0075 (inside 2σ), miracl-de identical — **C0 passes C5b** (§D, F-058) |
| 3c | C2 storage measurement (index bytes, one corpus) | orchestrator, live | ~10 min, rides on 3b's legal build | **done**: legal `fdt` 21.03 → 5.09 MB (−75.8 %); totals not comparable across merge states (§D, F-059). Found findings 5 and 6 on the way (row 1g) |
| 3d | C1 quality/recall campaign | — | 3-4 h (tempdoc 915 §P3.F) | **skipped**; C1 stays bundled with C2 in draft #648 |
| 4 | Publication: PRs off `origin/main` | orchestrator | — | **done**: (i) #643 lane E (replaces #622); (ii) #645 A+1b; (iii) #646 C0 + evidence + record; (v) #647 B+1d; (iv) #648 draft C2+C1+1a/1c/1e+1g. Stacked #645 ← #646 ← #647 ← #648; all CI green (#647 via manual dispatch, its PR event never fired). **None merged — awaiting an explicit go-ahead** |
| 5 | Lane F | — | — | untouched beyond committing 917; derisk and go/no-go remain gated on all of D landing plus owner confirmation |

Register obligations: `/search-quality` loaded before this wave; rows added for 3a-3c evidence (or
their skip) before close.

## §C Design

### §C.1 Chunk RMW revision guard (1a)

**Invariant to add:** a chunk document carries the identity of the parent content revision it was cut
from, and RMW reconstruction refuses to slice any other revision.

- New catalog field `chunk_parent_content_sha256` on chunk documents: `stored:true`, not indexed,
  not docvalues (64 hex chars). Both catalog copies (`SSOT/catalogs/fields.v1.json` and the
  adapters-lucene mirror), schema unchanged in shape. It is a physical field, so `index_fingerprint`
  moves; this rides in the same C2+C1 bundle that already moves it, so users pay for one rebuild.
- `ChunkDocumentWriter` computes `sha256(parentContent)` once per parent and writes it on every chunk.
- `WritePathOps.preserveChunkContent` compares the stored hash against `sha256(parentContent)` of the
  parent it read; mismatch → `IOException("chunk_content revision mismatch …")`, the same fail-closed
  path as a missing parent. The chunk is stale by definition (its parent was rewritten and
  regeneration is pending or in flight), so refusing the RMW loses nothing: regeneration deletes it.
- Old-shape chunks (no hash stored) are impossible after the bundle's reindex; the reader still
  treats a missing hash as a mismatch (fail closed), which the legacy-upgrade test asserts.
- Tests: (i) same-length different parent content → refuse, nothing written; (ii) unchanged parent →
  identical posting preserved (existing test extended); (iii) fingerprint moves
  (`CatalogPhysicalProjectionTest`).
- Alternative rejected: storing the parent length only — an equal-length rewrite is exactly the
  silent case.

### §C.2 Boot identity import guard (1b)

- New table `document_identity_import(generation_id TEXT PRIMARY KEY, imported_at INTEGER,
  parents_seen INTEGER, parents_imported INTEGER)` in `jobs.db`; schema V12 with the same
  migration/backup/refusal machinery as V11. `generation_id` is the active generation directory name.
- `importDocumentIdentitiesFromActiveIndex()` runs the scan only when the store has zero identity
  rows **or** there is no import row for the active generation. Rationale: after the first import,
  every parent written through admission already resolved through the store, and a Green built by
  migration re-ingests through the store; the scan is only needed to seed from an index that predates
  the store or after a wiped/restored `jobs.db`.
- The scan streams: parents are upserted in batches of 1,000 inside one transaction per batch; no
  whole-index list in heap.
- A live parent missing `doc_id` or `doc_uid` docvalues is counted and skipped (WARN once with the
  count), not fatal. Such a parent gets a fresh identity at its next admission. The Worker must not
  die on a legacy shape the fingerprint will make it rebuild anyway.
- Tests: second boot performs no scan (spy/counter); wiped store re-imports; a parent missing
  `doc_uid` is skipped with the count reported; batch boundary (1,001 parents) imports all.

### §C.3 Zero-vector handling (1c)

- `FieldMapper` catches `IllegalArgumentException` from `VectorNormalization` for the dense fields,
  omits that field, and reports it through a per-batch counter exposed on the write path's result
  (`droppedVectorFields`), logged at WARN with the document id (rate-limited).
- The document keeps every other field, so it stays reachable lexically; `embedding_status` for it
  is set to FAILED with a reason so backfill accounting is truthful (if the status field is present
  in the same batch; otherwise the writer records the drop and the next status pass sees the field
  absent).
- Test: a batch of three documents where the middle one has a zero vector writes all three, the
  middle one without `vector`, and the counter reads 1.

### §C.4 PR-B rebuild claim at the wire (1d)

- Rename `LabelStoreSurvivesRebuildTest` to `LabelStoreRegenerationKeepsUidKeysTest` — it proves
  derived-label regeneration, and the name should say so.
- Add to `DocumentIdentityBootImportTest` (indexer-worker, real `KnowledgeServer` over a real
  generation layout) an assertion on the **search response**: after the Blue→Green migration, a
  search for the document returns a hit whose generic fields carry the same `doc_uid` that Blue
  served. That is the exact value Head keys feedback on, so the rebuild property is now exercised
  where PR-B consumes it.

### §C.5 Fingerprint with an indeterminate model digest (1e)

- The commit metadata already carries the canonical inputs JSON next to the digest? **Verify first**
  (`SsotCommitMetadataSource`); if only the digest is stored, add `index_fingerprint_inputs`
  (canonical JSON) to commit metadata — it is small and lets a mismatch name the input that moved.
- When `IndexFingerprint.compute` is empty because of an INDETERMINATE model, the parity guard compares
  the stored inputs JSON with the expected inputs JSON **minus** the indeterminate keys. A difference
  in any determinate input (fields, vector dimension, similarity, codec, chunking, Lucene major) is
  a rebuild-requiring mismatch exactly as before; only the indeterminate inputs are declined, and the
  WARN names them.
- A legacy commit without the inputs JSON keeps today's behaviour (decline with WARN).
- Tests: NER model unreadable + vector dimension changed → SCHEMA_MISMATCH; NER unreadable + nothing
  else changed → open with WARN naming `ner_model_sha256`.

### §C.6 Identity vs content revision (1f) — design only

The identity store keys a path slot: rows persist after delete, so `resolve(path)` after an
unrelated replacement returns the old UID and feedback follows it. The correct contract:

- identity survives edits and verified moves; content revision advances on every content change;
- confirmed deletion followed by replacement must not inherit feedback, but temporary absence or a
  cloud placeholder must not establish deletion;
- feedback captures `(doc_uid, content_revision)` so an old judgment is scoped to the content it
  judged.

Cheapest implementation: the parent document already stores a content hash for freshness
(`FileFreshnessSnapshot`); carry it into the search hit's generic fields alongside `doc_uid`, persist
it on `FeatureSnapshot.HitFeatures`, and let projection treat a revision mismatch as "stale label"
rather than dropping it. Deletion semantics need an owner decision (grace period vs explicit
confirmation). **Not implemented this wave.**

### §C.7 Lane E OHR reconciliation mismatches (2a)

`staged_recall_accounting` self-reconciles its presence call against the harness's recorded recall;
F-051 already notes it is invalid at `top_n != 10`. The OHR incumbent arm ran at the default depth,
so the 105/962 mismatches are unexplained. Read-only diagnosis over the artifacts: classify the 105
by cause (rank-11-plus, doc-id normalisation, chunk-parent mapping, duplicate qrels). Outcome routes
to either a projection fix (if the population differs) or an F-057 caveat.

## §D Execution log

- 2026-09-05 §B 0a-0c done (commits listed in the table). `build -x test -PskipWebBuild=true` on
  the rebuilt stack (`354bec0d`): **BUILD SUCCESSFUL** (exit 0, fresh Gradle home `F:\jsgh-W3`).
  Lane E branch (`e29119ca`): `:modules:worker-services:compileTestJava
  :modules:system-tests:compileIntegrationTestJava` exit 0.
- 2026-09-05 §C.5 premise checked: `SsotCommitMetadataSource.java:77` stores only the digest under
  `index_fingerprint`; the canonical inputs JSON is not persisted, so 1e adds
  `index_fingerprint_inputs` (subagent C brief says so).
- 2026-09-05 2a diagnosed (read-only, opus, spot-checked): the 105 OHR mismatches are a TREC
  **parser** defect — `staged_recall_accounting.py:136-139` takes `parts[2]` as the doc id while
  `_write_trec_run` (`artifacts.py:277-280`) writes ids unquoted and OHR ids contain spaces. All
  105 are "projection absent / harness present"; right-anchored parsing gives 0 mismatches and
  `final_recall` 0.9875 = the harness's own `R@10`. Corrected OHR `leg_union_recall` ≈ 0.989
  (was 0.879); the offset is exactly 105/962 on all 12 OHR arms, so OHR deltas hold and legal/enron
  (0 mismatches) are unaffected. The Part 1 verdict does not change: nDCG@10 and R@10 come from
  `ir_measures` over in-memory `ScoredDoc`s and never touch the TREC file. Fix delegated to the
  lane E branch (subagent D).
- 2026-09-05 C5b instrument: jseval now persists `denseStatus`/`denseReason` per query and
  `scripts/jseval/915_c0_skip_rate.py` rolls run directories up (`d48b7045`, 3 tests, one
  falsified).
- 2026-09-05 subagents A (§C.1+§C.3), B (§C.2), C (§C.4+§C.5), D (2a fix + register) spawned on
  `worktree-wave3-fix-{a,b,c}` and `worktree-wave3`; results below when they return.
- 2026-09-05 **D returned** (`7ab58d3d` on `worktree-wave3`): one TREC authority
  `scripts/jseval/jseval/trec.py` (right-anchored reader, tab-delimited writer), the same defect fixed
  in `fusion_attribution_784.py` and `metric_order_ab.mjs`; 12 new parser tests + a space-bearing
  gold reconciliation test, five falsifications; corrected OHR incumbent `final_recall` 0.9875 =
  the harness's `R@10` to the last digit, `leg_union_recall` 0.9886, mismatches 0. Riders on F-057,
  F-025, F-028; 916 §L.8. No union-recall pin covers OHR, none changed. **PR #643 opened**
  (replaces draft #622; not merged). Its `build -x test` failed only on the two load-flake
  integration classes named in the expected-state pin (`LambdaMartBenchmarkTest` p50 12.1 ms vs
  5 ms; `SchemaMismatchStatusContractTest` Worker spawn starved) while three agent builds ran;
  isolated re-run pending.
- 2026-09-05 **B returned** (`f04b985b`, cherry-picked as `36c42174`): schema V12
  `document_identity_import`, guard in new `DocumentIdentityBootImport` (scan only when the store is
  empty or the serving generation has no import row), `DocumentFieldOps.scanParentDocumentIdentities`
  streams 1,000-row batches, missing `doc_id`/`doc_uid` counted as `parents_skipped` with one WARN
  instead of `CORRUPT_INDEX`; 13 tests each falsified once; module suites 2,512 tests / 0 failures;
  `check-store-recoverability` OK (jobs-db `currentVersion` 12), `adr-coverage` pass. Design note
  kept from B: a migrated V11 database gets an empty import table on purpose, so the first V12 boot
  scans once more rather than asserting an import this binary never observed.
- 2026-09-05 #643's two integration failures re-run in isolation: both green (`EXIT=0`, 0
  failures in the result XML) — load flake as the pin predicts, not a defect. **PR #645 opened**
  from `worktree-wave3-pa` (PR-A `1d37fa63` + the §C.2 fix `ee0d00af` on `origin/main`); its
  `build -x test` passed with no integration failure. Not merged.
- 2026-09-05 **C returned** (`f8ed7549`, cherry-picked clean as `0194c380`). §C.4: the test is
  renamed to what it proves (`LabelStoreRegenerationKeepsUidKeysTest`) and the Blue→Green claim is
  asserted where PR-B reads it — `DocumentIdentityBootImportTest` now commits Green, closes,
  promotes, reboots and reads `doc_uid` off the gRPC `SearchResponse` fields map. Load-bearing
  finding: searching the in-flight server queries **Blue** (`searchLifecycle` stays Blue during a
  migration, `KnowledgeServer.java:659-660`) and would have passed on Blue's own uid; the test
  reproduces the production cutover (`KnowledgeServerMigrationOps.java:257-264`) instead. §C.5:
  `index_fingerprint_inputs` (canonical JSON) stamped on every commit beside the digest, declared
  in `commit-metadata.schema.json`; `IndexFingerprint.differingInputs` (fields keyed by `id`, not
  ordinal; unparseable ⇒ no difference); `ParityDiagnostics.determinateInputDiff` runs only when the
  expected digest is uncomputable and both sides carry inputs, drops the runtime's indeterminate
  model keys from both sides and reports any remaining difference under `index_fingerprint` (same
  `SCHEMA_MISMATCH`, same brake, no new reason code; the inputs key is deliberately not a
  `PARITY_KEYS` member so one shape change is not reported twice). `compute` stays all-or-nothing.
  Guard WARN now distinguishes "checked WITHOUT the model digests" from "NOT being checked". 9 new
  tests, each falsified once (records in C's report); adapters-lucene 678 / indexer-worker 357 /
  app-services 2,541 — 0 failures; `build -x test` green. Non-ASCII in the diff: `—`/`→`/`§` in
  prose only; no NUL. Residual (§E 4): the fallback fires only when the **expected** digest is
  blank; a stored-indeterminate + expected-computable pair still declines (stored digest blank).
- 2026-09-05 **A returned** (`4d5275a1`, cherry-picked clean as `b87a1838`). §C.1: new stored keyword
  `chunk_parent_content_sha256` (catalog + generated mirror; `SchemaFields` hand-edited), hashed
  once per parent by `ChunkDocumentWriter` (the only production chunk writer — `RagContextOps:878`
  is read-side) via new `indexing/chunking/ChunkParentRevision`; `WritePathOps.preserveChunkContent`
  compares stored vs live revision and throws `parent content revision mismatch` — a missing stored
  revision (pre-931 chunk) fails closed too, riding the same reindex the fingerprint move forces.
  `validateRmwPolicies` now refuses `rederive-parent-slice` without the companion stored field.
  Incidental fix: `FieldMapper.addFields` wrote nothing for a stored keyword with
  `docValues:false`. §C.3: `FieldMapper.toDocument(fields, report)` pre-detects unnormalizable
  DOT_PRODUCT vectors, omits the field, sets the paired status to `FAILED`, one WARN per write +
  `index.runtime.vector_dropped_total`; `wouldMaterialize` deliberately untouched (rejecting there
  would abort the batch in FAIL mode, the defect being fixed). `rejectsZeroAndNonFiniteVectors…`
  rewritten to assert drop-not-throw (the throw was the defect). 8 tests, each falsified once;
  adapters-lucene 675 / indexer-worker 357 / worker-services + 5 more modules green; SSOT manifest
  regenerated, `ssot-catalog-sync` pass, `check-language-agnostic-analysis` OK; `build -x test`
  green. Diff: `—`/`§` only, no NUL. Routed residual → §E 5.
- 2026-09-05 **#645 CI red on `test-to-code`**: `worker-core` 63.2% → 62.9% (PR-A added 197
  production lines — `DocumentIdentityStore`, `PathHash`, `InfraContext` — with no worker-core
  test). Fixed at the root, not by changeset: three worker-core contract tests (`PathHashTest`:
  FIPS vector pins SHA-256-over-UTF-8, no path leakage, blank refusal, facade delegation;
  `DocumentIdentityStoreUnavailableTest`: every method incl. the read-only ones refuses — an empty
  `lookup`/zero `identityCount` would look like a healthy fresh store; `InfraContextDefaultsTest`:
  null → fail-closed default on both constructors, a wired store is kept). Gate local: pass
  (ratio 64.9%). Same tests cherry-picked to `wave3-d`.
- 2026-09-05 **C follow-up returned** (`bc968416`, cherry-picked clean as `d829ef7c`) — §E 4 closed.
  `determinateInputComparisonAvailable` = (either digest blank) AND both inputs present; ignore
  list = runtime-indeterminate models ∪ model keys that are JSON `null` on the stored side when
  the stored digest is blank (`IndexFingerprint.MODEL_INPUT_KEYS` is now the one source of the
  three key names; canonical bytes unchanged). Behaviour change beyond the brief, accepted: for
  stored-blank/expected-present the old path was not a decline but `LEGACY_INDEX_HINT` → one-time
  rebuild; with inputs recorded the guard can now tell "pre-fingerprint index" from "committed
  under an unreadable model", so an index that recorded inputs is compared, not rebuilt
  (`ParityDiagnostics.java:128-160`, gate re-read: `inputsCompared` short-circuits the legacy
  branch only when the fallback actually ran). `IndexStatusOps` routes its stored-blank branch
  through the same predicate + `diff`, so the status banner cannot demand a reindex the guard is
  not performing. Two fixtures (`SchemaMismatchPolicyBranchTest.withoutFingerprint`,
  `WorkerBootFixture.NO_FINGERPRINT`) had stopped modelling a legacy index once inputs were
  stamped — fixtures fixed, assertions untouched. 6 falsifications recorded; adapters-lucene 680 /
  worker-services 1,170 / indexer-worker 357 — 0 failures; `build -x test` green. Diff `—`/`§`
  only, no NUL.
- 2026-09-05 **#645 second CI run: `test-to-code` green, `Unit tests (search-worker)` red** —
  `DocumentIdentityBootImportTest.blueUidIsImportedBeforePausedMigrationReindexesIntoGreen` at the
  `chunks > 1` assertion (passed on the first CI run and on every local run). Cause, verified at
  source: the chunks are written in a pass after the parent
  (`ChunkDocumentWriter.regenerateChunksFromExistingParent`, `GrpcIngestService.java:897-903`,
  `KnowledgeServerMigrationOps.java:676`), so `awaitGreenUid` (parent uid visible) is a weaker
  condition than the one-shot chunk scan asserted next. Fix: `awaitGreenChunks` polls for ≥2 chunk
  hits of that parent with the same 15 s bound and the same state/queue/failure diagnostics; the
  per-chunk uid assertions are unchanged. Test precision fix, not a weakening — a migration that
  never derives chunks still fails, with state in the message.
- 2026-09-05 **PR staging (§B row 4)**, all linear off #645 so each PR's diff is its own commits:
  `worktree-wave3-c0` (PR-C0: C0 `48d91deb`, C5b instrument `7fd30198`, matrix regen `9b26290d`) →
  `worktree-wave3-b` (PR-B `aba26cc8` + 1d `dd72d8a9`) → `worktree-wave3-draft` (C2 `6c281e65`, C1
  `88ed1f17`, 1e `0ed2a53e` + `9d305df5`, 1a/1c `4702642f`). C's first commit was split by path
  (1d: the two tests + 915; 1e: the rest). Draft tip == `wave3-d` tree outside `docs/tempdocs`
  (only the ownership matrix differed before `9b26290d`). PR-B onto A+C0 without C2/C1: only the
  915 tempdoc conflicted (status lines + the append-only history block) — resolved with the
  cherry-picked side; the final 915 text is synced from `wave3-d` at the draft tip.
- 2026-09-05 **Combined `wave3-d` verification** (A+B+C+C-follow-up+worker-core tests, tip
  `4eadc9bf`): `build -x test -PskipWebBuild=true` BUILD SUCCESSFUL; `cleanTest … --no-build-cache`
  on worker-core 318 / adapters-lucene 687 / indexer-worker 367 / worker-services 1,171 /
  app-services 2,541 / indexing 78 — **5,162 tests, 0 failures, 0 errors** (counts from the result
  XML, not the console). Flake fix `c0e5dbf5` pushed to #645 (falsified: minimum 1000 → RED at
  `DocumentIdentityBootImportTest.java:292`); cherry-picked to c0 clean, and to b/draft/d with one
  conflict against C's wire block (resolved: the one-shot `chunks > 1` line dropped, the wire block
  and the helper both kept; the three copies are byte-identical); merged test 5/5 green in `wave3-d`.
- 2026-09-05 **3a started** from `worktree-wave3-c0` (PR-C0 tip; `build -x test` green, tree
  clean). Shape check first: the planner's dense skip needs `canDense`, which is the *query*
  encoder succeeding (`SearchPlanner.java:81`), not corpus embedding coverage, so the runs are
  index-only (`--modes hybrid --start-backend --clean`, no `--embedding`/`--pipeline`;
  `--embedding` would gate on 99.9 % embedding coverage, `readiness.py:207`). First attempt as a
  tracked background task was stopped externally ~1 min in (backend still booting); the stale
  `jseval-29188` foreign-run record was removed and 20 idle Gradle test workers (6.6 GB) reclaimed
  with `gradlew --stop`. Relaunched as a detached `Start-Process` driver
  (`%TEMP%\c5b-driver.cmd`, logs in `%TEMP%\c5b\`) per `agent-lessons` — order: legal, scifact,
  miracl-de, miracl-fr, enron, ohr-bench-clean; roll-up with `scripts/jseval/915_c0_skip_rate.py`.
  Second attempt failed in 30 s on every corpus: the worktree had no `datasets/` (gitignored corpora
  live in the main checkout; `wave3-d` carries a junction). Junction added to `wave3-c0`, `wave3-pa`,
  `wave3-c2`; orphaned backend JVMs and the `jseval-28816` record cleaned; third launch ran clean.
- 2026-09-05 **3a result (C5b per-language skip rate)** — six index-only hybrid runs, PR-C0 tip
  `0bb0b8cb`, 17 min wall-clock, every run `comparable=True`, readiness passed, run dirs
  `wave3-c0/scripts/jseval/tmp/eval-results/20260905T02{3748,4019,4219,4446,4824,5247}_*`:

  | corpus | lang | queries | dense reported | executed | planner skips | skip rate | by reason |
  |---|---|---:|---:|---:|---:|---:|---|
  | mixed/legal-clerc-200 | en | 200 | 200 | 196 | 4 | 0.020 | `SKIPPED_NO_DISCRIMINATIVE_TERM`=4 |
  | beir/scifact | en | 300 | 300 | 300 | 0 | 0.000 | — |
  | mixed/enron-qa | en | 300 | 300 | 300 | 0 | 0.000 | — |
  | mixed/ohr-bench-clean | en | 962 | 962 | 962 | 0 | 0.000 | — |
  | mixed/miracl-de-2k | de | 305 | 305 | 305 | 0 | 0.000 | — |
  | mixed/miracl-fr-2k | fr | 343 | 343 | 343 | 0 | 0.000 | — |

  Reading (interrogated, not just read off): the DF rule fires only when the *rarest* analyzed
  query term occurs in ≥ 25 % of `content` documents (`SearchPlanner.java:224-230`,
  `SearchInputCapture.java:259-263`; ≥ 100 field docs, all six qualify), so a natural-language
  query almost never qualifies — 4 legal citation queries did, which proves the gate path is live on
  this branch rather than inert. Per-language: en 0–2 %, de 0 %, fr 0 % — the rates are comparable
  across languages, which is the C5b criterion; the retired English `STOP_WORDS` rule could only
  ever fire for English. Consequence worth stating plainly: at the default 0.25 the dense skip is
  effectively off on every pre-registered corpus, so C0's cost is "dense runs on ~every query" (the
  old rule's English-only savings are gone) and its benefit is locale invariance, not latency.
  nDCG@10 from these index-only runs (no embeddings, no SPLADE) is not a quality number; 3b is.
- 2026-09-05 **3b/3c design** (paired, same machine, same hour, `--pipeline --embedding`, fresh
  `--clean` index each): C0 arm = `wave3-c0` tip `0bb0b8cb` (A+C0); control = `wave3-pa` tip
  `c0e5dbf5` (A only) — the paired control isolates C0 from the 832 rebaseline's different commit
  and hardware; C2 bytes = `wave3-c2` at `6c281e65` (A+C0+B+C2, **no C1**, so quantization cannot
  confound the byte count) vs the pre-C2 legal index of the control arm (C0 moves no physical field:
  its diff is search-ops only, and the two miracl-de indexes below agree byte-for-byte on `vec` and
  within 0.7 % on `fdt`). `summary.json`'s `ingest.index_size_bytes` is the `/api/status`
  `indexSizeBytes` snapshot taken mid-ingest and is NOT usable for 3c (it reported 16.1 MB vs 33.6 MB
  for two indexes that are 40.9 MB vs 43.0 MB on disk); 3c reads the on-disk index after the run,
  total and by Lucene file type, because segment-merge state (`cfs`) varies run to run.
- 2026-09-05 **3b miracl-de**: C0 arm nDCG@10 **0.8575** / P@1 0.6656 / R@10 0.9967; control
  **0.8575** / 0.6656 / 0.9967 — identical to four decimals, every leg observed on both
  (`cross_encoder, dense, hybrid, query_classification`), `comparable=True`. Expected: the DF rule
  fired on 0/305 queries and the retired English stop-word rule could never fire on German, so C0
  is a no-op on this corpus by construction; the identity of the numbers doubles as a run-to-run
  determinism check. Scorecard (832, hybrid) 0.857 — same value. On-disk index: C0 arm 40.9 MB
  (83 files; `vec` 19.33 MB, `fdt` 3.98 MB), control 43.0 MB (96 files; `vec` 19.33 MB, `fdt`
  4.00 MB) — the 2.1 MB gap is `cfs` (6.5 vs 9.1 MB, un-merged small segments), not content.
  First control-arm legal attempt failed on the missing `datasets/` junction (junction creation in
  the earlier PowerShell-in-bash call silently did not happen; re-created from the PowerShell tool
  and verified); re-run queued after the C2 bytes run.
- 2026-09-05 **3c run exposed a C2 defect (finding 5, new).** The C2 arm (`wave3-c2` @ `6c281e65`,
  A+C0+B+C2, no C1) took **1,793 s** to reach `--pipeline` readiness on legal vs **292 s** on the C0
  arm. Worker log: 231 × "Combined enrichment backfill hit its 5000ms cycle budget after 1 batches
  with ZERO stage advancement … the non-converging shape" followed by hundreds of "Combined
  backfill: N document(s) reached terminal SPLADE FAILED: [chunk:…(chunk-splade-disabled)]";
  embeddings sat at 55 % and NER at 76/199 for ~25 min while the loop spun, then completed within
  a minute once the chunks were terminal-FAILED. Cause at source (`git show 6c281e65 --
  CombinedEnrichmentBackfillOps.java`): pre-C2 the chunk branch enrolled SPLADE only
  `if (chunkSpladeEnabled && spladeAvailable)` and otherwise left a flag-off chunk alone; C2 added
  an `else if (SPLADE_STATUS_PENDING)` escalation through the retry seam
  (`CombinedEnrichmentBackfillOps.java:505-528`), so with `rag.chunk_splade.enabled` off (the eval
  default) every chunk is rewritten every cycle with retry+1, nothing advances, and the shared
  cycle budget starves the other stages until the retry cap fails every chunk. Quality: C2 arm
  nDCG@10 **0.5834** / R@10 0.815 vs C0 arm **0.5989** / 0.825 (−0.0155, beyond lane E's 2σ line of
  0.0136); Σ`totalHits` 12,009 vs 12,360; only 12/200 top-10 lists identical; ids are parent paths
  on both arms, CE/dense/chunk-merge statuses alike — so the candidate set itself changed (chunks in
  terminal FAILED excluded from a leg, or the reconstruction path). C2's on-disk legal index: 29.1 MB
  / 43 files (`fdt` 5.02 MB, `vec` 12.67 MB, `cfs` 4.81 MB) — not comparable to the C0 arm's 38.8 MB
  until the enrichment defect is fixed (a FAILED chunk writes no vectors, so `vec` is understated).
  Diagnosis + fix delegated (opus, `worktree-wave3-draft`; fix to be cherry-picked to `wave3-c2`
  for a clean re-measurement without C1). The draft PR cannot be undrafted on the C1 campaign alone
  now: this is a correctness defect in C2, recorded in §E 6. The Codex closeout's "short-checked"
  C2 (915 §P3.D) did not run `--pipeline` on any corpus — this is what the six-corpus/enrichment
  campaign was for.
- 2026-09-05 **3b legal (control re-run green)**: control (A only, `c0e5dbf5`) nDCG@10 **0.5914** /
  P@1 0.365 / R@10 0.830, readiness 284 s; C0 arm **0.5989** / 0.390 / 0.825, readiness 292 s.
  Δ(C0 − control) = **+0.0075 nDCG@10**, inside lane E's legal 2σ line (0.0136, F-057) — no
  regression; not claimed as an improvement either. Both arms every leg observed, `comparable=True`,
  Σ hits 12,360 vs 12,322. Calibration worth keeping: even these two no-C2 arms share only 24/200
  identical top-10 lists (20 queries up, 21 down), so top-10 churn is run-to-run noise on legal and
  only the mean and the hit count carry signal — which is why the C2 arm's −0.0155 / −351 hits
  stands and its 188/200 churn does not add to it. **3b verdict: C0 passes C5b** (skip rates
  comparable per language, no material quality loss on legal or miracl-de; scifact/enron/fr/OHR
  covered by 3a for skip rate only — their fully-enriched no-regression runs would add ~1.5 h and
  were not run under the 2 h rule; recorded as a limit, not a pass).
- 2026-09-05 **3c pre-C2 reference (control-arm legal index, on disk after the run)**: 74.66 MB /
  142 files — `fdt` 21.03 MB, `vec` 30.49 MB, `cfs` 7.85 MB, `pos` 7.40 MB, `doc` 3.48 MB. The C2
  arm's 29.11 MB (`fdt` 5.02 MB, `vec` 12.67 MB) is only meaningful on `fdt` (stored fields,
  −76 %, the C2 target) until the finding-5 fix restores chunk vectors; final 3c number after the
  re-measurement.
- 2026-09-05 **PR #646 (PR-C0) opened**, base `worktree-wave3-pa` (#645), head `worktree-wave3-c0`
  `d52a26e6`: C0 `48d91deb` + C5b instrument `7fd30198` + matrix regen `9b26290d` + the flake fix +
  the wave-3 record (931, 917, register F-058 + both skill mirrors). Not merged. Register: F-058
  added (newest-first, above F-056; F-057 is lane E's on #643, so the number is reserved rather
  than reused); the `.agents` copy is *manually maintained* (no generated markers) — F-058 inserted
  by hand at the same position, `check-codex-agent-parity` OK. `check-tempdoc-numbers` fails on
  this machine with a **#930 collision** between other agents' worktrees (`930-evidence` in
  `930-oss-stop`/`agent-*` vs `930-replace-bounded-areas-with-maintained-oss.md`) — not this
  branch's number, not reproducible in a single checkout (CI); routed here for the 930 owner, no
  action taken.
- 2026-09-05 **PR #647 (PR-B) opened**, base `worktree-wave3-c0` (#646), head `worktree-wave3-b`
  `9cab5966`: PR-B `aba26cc8` + the 1d wire test `dd72d8a9` + matrix regen + flake fix.
  `build -x test` BUILD SUCCESSFUL; app-services 2,541 / indexer-worker 367 — 0 failures (result
  XML). Not merged. Stack as published: #645 (A) ← #646 (C0) ← #647 (B) ← draft (C2+C1+1a/1c/1e,
  blocked on §E 6).
- 2026-09-05 **#646 first CI: `Public claims` red on the always-loaded budget ratchet** —
  `CLAUDE.md` 22,615 B > ceiling 22,589 B by 26 B, from C0's pre-merge-table row growing
  ("adapter/Worker search paths … + word-list scanner self-test"). Fixed by wording, not by
  ratcheting up (`c734b521`: same two checks named, −28 B); `check-always-loaded-budget` and
  `check-premerge-table` pass; propagated to b/draft/d. Both #646 and #647 re-running.
- 2026-09-05 **Finding 5 fixed** (subagent, opus; `58c3c344` on `worktree-wave3-draft`,
  cherry-picked clean to `wave3-c2` as `3e163740` and to `wave3-d` as `a40083ad`). Root cause
  chain, verified at source: `ChunkDocumentWriter.java:210` stamps `splade_status=PENDING` on every
  chunk unconditionally (pre-existing); the combined lane's SPLADE-pending selection
  (`queryDocIdsByField(SPLADE_STATUS, PENDING)`) never excluded chunks, so ~4,122 chunk ids competed
  with 199 parents for 100 batch slots; C2's `IS_CHUNK` routing sent them into the chunk branch and
  its new flag-off `else if` bumped `splade_retry_count` (max 3, `SchemaFields.java:202`) every
  cycle; a retry bump is not progress for `CombinedOutcome`
  (`CombinedEnrichmentBackfillOps.java:1198-1210`), so `BackfillScheduler.java:265/299` tripped the
  budget 231 times. `rag.chunk_splade.enabled` default `false` (`ResolvedConfigBuilder.java:1694`).
  Fix: new `DocumentFieldOps.queryNonChunkDocIdsByField` (same term query, `MUST_NOT is_chunk`),
  used for the SPLADE-pending selection when the flag is off; the flag-off escalation arm removed —
  a flag-off chunk is not a SPLADE candidate at all and its `PENDING` stands (reversible; a
  `COMPLETED_EMPTY` would be the F-032 status lie and terminal); the same gate added to the
  blank-content chunk arm; a stale `BackfillContext` comment corrected. Three falsifications
  recorded; worker-services 1,174 / adapters-lucene 690 — 0 failures; `build -x test` green.
  Hypotheses for the quality delta: (a) chunks in terminal SPLADE FAILED carry no postings for the
  query-side chunk-SPLADE leg, which `SearchExecutor.java:653` runs off `pipeline.spladeEnabled`,
  not the write-side flag — LIVE, leading; (b) reconstruction — negative (`ChunkSplitter.java:826`
  offset law + `DocumentFieldOpsChunkSliceTest`); (c) `SearchResponseBuilder` — negative
  (entity text feeds excerpt spans only). Re-measurement on `wave3-c2` @ `3e163740` running.
  Routed (§E 8): the individual SPLADE/BGE-M3 lanes are flag-unaware and encode chunk SPLADE
  regardless, so the write-side flag only decides which lane wins a race; 712's "default stays OFF"
  verdict overstates what the flag turns off.
- 2026-09-05 **Finding 5 re-measured (`wave3-c2` @ `3e163740`, legal, fresh, `--pipeline`)**: the
  loop is gone — readiness **235 s** (was 1,793), no loop WARNs, no terminal-FAILED chunks. The
  quality gap is **not** gone: nDCG@10 **0.5751** / P@1 0.355 / R@10 0.805 vs control 0.5914 /
  0.365 / 0.830 (Δ −0.0163, beyond 2σ) and C0 arm 0.5989; Σ hits **12,004** vs 12,322 (−318);
  28 queries down / 14 up vs control. So hypothesis (a) was the loop's *symptom*, not the ranking
  cause. New clue from the on-disk index: C2+fix legal `vec` **13.29 MB** (60 files) vs control
  **30.49 MB** (142 files) with 4,122 chunk docs on both, while jseval reported "Chunk vectors
  100 %" and `ann_proof PASS` on both — 4,122 × 1024 × 4 B ≈ 16.9 MB, about the missing amount.
  Working hypothesis (finding 5b): chunk vectors absent with `chunk_embedding_status` COMPLETED —
  the F-032 class (711), most likely C2's `rederive-parent-slice` RMW dropping `chunk_vector` on a
  later write. Two probes running: (i) subagent — direct Lucene inspection of both index dirs
  (chunk docs / chunk docs with a KNN value / COMPLETED statuses), then root cause + fix on the
  draft; (ii) orchestrator — `--modes vector,lexical,hybrid --skip-ingest` re-query of both
  existing indexes to localise the loss by leg. C2's `fdt` 5.10 MB vs control 21.03 MB (−76 %)
  stands as the storage effect once the vector question is settled. `wave3-c2` = C2 + fix, no C1,
  no RMW revision guard (4702642f is on the draft only).
- 2026-09-05 #646 second CI red: `skills-sync --check` (the `.claude` search-quality mirror was two
  lines stale on that branch — its register header differs from `wave3-d`'s); regenerated on the
  branch (`10030799`); b/draft/d already in sync.
- 2026-09-05 **Finding 5b per-leg re-query** (same two legal indexes, no clean, `--skip-ingest`,
  `--modes vector,lexical,hybrid`, minutes after the fresh runs, all arms `comparable=True`):

  | arm | vector nDCG@10 / R@10 / Σhits | lexical | hybrid |
  |---|---|---|---|
  | control (`wave3-pa`) | 0.6148 / 0.820 / 13,269 | 0.6873 / 0.855 / 11,257 | 0.5900 / 0.830 / 12,341 |
  | C2+fix (`wave3-c2`) | 0.6215 / 0.830 / 12,762 | 0.6858 / 0.855 / 11,245 | 0.5861 / 0.815 / 12,006 |

  Readings. (1) Vector-only quality on C2 is equal-or-better, so "chunk vectors missing" is
  refuted by behaviour; the `vec` byte gap is best explained by dead bytes in the control's
  un-merged 142-file segment set (deleted-but-unmerged docs keep their vectors on disk).
  (2) The settled hybrid gap is **−0.004** (inside σ); the fresh-run −0.016 was measured while the
  flag-unaware individual SPLADE lane was still encoding 4,122 chunks after parent-only readiness
  (readiness counts parents, `IndexCountOps.querySpladeFeatureCounts`) — C2 hybrid on the same
  index moved 0.5751 → 0.5861 in ten minutes with hits unchanged (12,004 → 12,006), the control
  0.5914 → 0.5900. **Measurement lesson:** on a chunked corpus, `--pipeline` readiness is not
  "enrichment finished"; query only after the chunk lanes are idle (§E 9). (3) What is real on
  every run: C2 returns fewer hits — vector −507 (−3.8 %), hybrid −335, lexical ≈ equal — with
  R@10 unchanged or higher. Subagent redirected to explain that (chunk candidate count / collapse /
  `totalHits` semantics in C2's `ChunkSearchOps` change) rather than to a vector-preservation fix.
- 2026-09-05 **Finding 5b settled (subagent, direct Lucene counts on both index dirs, read-only).**
  Chunk docs 4,122 / with `chunk_vector` 4,122 / `chunk_embedding_status` COMPLETED 4,122 on BOTH
  arms; parents 199/199/199. No F-032 shape. `maxDoc/numDocs` 6,950/4,321 (2,629 tombstones) on
  the control vs 4,543/4,321 (222) on C2 — the `vec` byte gap is dead weight in the control's
  un-merged segments. Reconstruction byte-exact: 7,781,026 chars of stored `chunk_content` on the
  control = 7,781,026 chars re-derived on C2, 0 mismatches. Hit-count residual: no C2 code path
  removes hits (`ChunkSearchOps.buildChunkHits` still returns every pending hit,
  `ChunkSearchOps.java:405`; lexical identical on 103/200 full lists; HNSW probe k=50…400 returns
  exactly k on both, distinct parents within 0.5 %); the loss is diffuse (177 queries, −2…−9 each)
  and tracks BM25 collection statistics inflated by tombstones (`chunk_content` docCount 6,734 vs
  4,344; `court` df 4,613 vs 2,972). **Verdict: not a C2 regression** — vector-only nDCG is higher
  on C2 at equal R@10; treat ±0.01 between arms with different merge state as tombstone noise, or
  force-merge before comparing (§E 10).
- 2026-09-05 **Finding 6 (new, real, fixed): `rag.chunk_splade.enabled` was honoured by 1 of 3
  lanes.** `SpladeBackfillOps.java:58` and `BgeM3BackfillOps.java:66` selected `splade_status=
  PENDING` with no chunk filter while `ChunkDocumentWriter.java:210` stamps PENDING on every chunk;
  masked before the loop fix because the combined lane drove every chunk to FAILED first. Index
  counts: control chunk `splade_status` FAILED=4,122 / postings on 216 docs; C2+loop-fix
  PENDING=2,222 / COMPLETED=2,099 / postings on 2,149 docs — ~1,900 chunks encoded by the
  flag-unaware lanes *after* parent-only readiness released the eval, i.e. a retrieval leg
  populated by however far backfill got before the harness queried (the 5b confound, now with a
  mechanism). Fix `286a97bc` (draft) / `4a02db77` (`wave3-c2`) / `db156f85` (`wave3-d`): both lanes
  take `chunkSpladeEnabled` and use `queryNonChunkDocIdsByField` when off; `BackfillScheduler`
  wires four sites; the outstanding-work gate moves to new `IndexCountOps.countNonChunkByField`
  (without it a parked chunk population pins `backfillDidWork` true forever). Three falsifications;
  worker-services 1,176 / adapters-lucene 691 — 0 failures; `build -x test` green on both.
  **Product consequence for the owner:** with the flag now meaning OFF on every lane, a default
  install writes **no** chunk SPLADE postings, while the query-side chunk-SPLADE leg
  (`SearchExecutor.java:653`) still runs off `pipeline.spladeEnabled`; the 832 scorecard was
  measured on the race (some chunks encoded). Re-measuring legal on `wave3-c2` @ `4a02db77` to
  put a number on "flag truly off" vs the control. §E 8 → closed by this fix except for the
  query-side flag question, which stays open there.
- 2026-09-05 **Final C2 legal (`wave3-c2` @ `4a02db77`, both fixes, fresh, `--pipeline`)**:
  readiness **215 s**, Worker log clean (two model-precision notices only), every leg observed,
  `comparable=True`; hybrid nDCG@10 **0.5776** / P@1 0.360 / R@10 0.805; Σ hits 12,001. Vs the
  control's settled 0.5900: **−0.0125**, inside the F-057 2σ line (0.0136); 27 queries down / 16
  up. The number equals the 832 scorecard's legal hybrid (0.578, F-058 context), which is the
  reading: with chunk SPLADE **truly off** the engine scores what the scorecard recorded, and the
  control's +0.012 came from postings the flag-unaware lanes encoded on the race (finding 6), plus
  tombstone-inflated BM25 statistics (5b). **3b/3c verdict for C2: no regression attributable to
  C2** — the storage change is byte-exact on reconstruction and the ranking difference has a
  named, non-C2 mechanism; what the owner has to decide is the chunk-SPLADE flag (712), which
  wave 3 made honest rather than silently half-on.
- 2026-09-05 **3c final (legal, on disk after the run, by Lucene file type)**: C2 **25.74 MB /
  58 files** vs control **74.66 MB / 142 files**. Attributable to C2: stored fields `fdt`
  **5.09 MB vs 21.03 MB (−75.8 %)** — `chunk_content` and `entity_*_text` no longer stored. Not
  attributable: `vec` 12.11 vs 30.49, `pos` 3.33 vs 7.40, `doc` 1.53 vs 3.48, `cfs` 1.84 vs 7.85 —
  the control carries 2,629 tombstoned docs in un-merged segments (§E 10); the same count of live
  vectors exists on both. Lane E's pre-C2 56.65 MB (500/50, 916) is a different merge state and
  is not used as the reference. Register F-059.
- 2026-09-05 **Closeout.** `wave3-d` tip (= draft #648 code + record): `build -x test` BUILD
  SUCCESSFUL; `cleanTest … --no-build-cache` worker-services 1,176 / adapters-lucene 691 /
  indexer-worker 367 — 0 failures, 0 errors (result XML); earlier combined tip 5,162/0 across six
  modules. Draft #648 opened (base #647, head `286a97bc`). Fix worktrees `wave3-fix-{a,b,c}`
  removed with their branches (all commits cherry-picked); `wave3-c2` and `wave3-pa` kept as the
  measurement arms (their index dirs are the 5b evidence; snapshot before further forensics — the
  re-query runs opened writers that merged segments). Time spent on live measurement: 3a 17 min,
  3b/3c 5 pipeline runs + 1 re-run + 2 re-queries + 2 C2 re-measurements ≈ 95 min — under the 2 h
  rule, with 3d (C1) the one skip.
- 2026-09-05 **#648 first CI: two reds, both draft-only code** (`4aad952d`, cherry-picked to
  `wave3-d` as `58b5460a`). (1) `UnreferencedCodeTest` (app-launcher, whole-program): four
  members with no main-code reference after the wave-3 fixes — `WritePathOps.indexDocument(Map)`
  and `FieldMapper.toDocument(Map)` (pre-931 overloads superseded by the
  `DroppedVectorReport`-carrying ones), `SsotCommitMetadataSource.indexFingerprint()` (superseded
  by `fingerprintInputs()`), write-only `FieldDef.vectorSimilarity`. Deleted; 33 test call sites
  pass the report explicitly. The scanner is its own falsification (red before, 28/0 after).
  (2) `test-to-code` on `modules/indexing` 95.1 % → 93.0 %: A's `ChunkParentRevision` was tested
  only from worker-services. `ChunkParentRevisionTest` (6 cases) pins the hash contract in its own
  module; falsified once — uppercase hex → **4** of 6 RED (the commit message says 2; the XML says
  4) — restored 6/6 green; gate pass (indexing back above its 95.1 % pin). Suites: adapters-lucene 691 / indexing 84
  / app-launcher scanner 28 — 0 failures; `build -x test` green.

## §E Open items

1. C1 campaign (3-4 h) — deferred; draft PR carries C2+C1 together.
2. 1f identity/revision contract — designed, owner decision needed.
3. Lane F derisk — after D lands and owner confirmation.
4. **Closed** (`bc968416` / `d829ef7c`, see §D). §C.5 residual: the determinate-input fallback only runs when the *expected* digest is
   uncomputable. An index committed while a model file was unreadable (stored digest blank,
   inputs present) opened later by a runtime that can read every model (expected digest present)
   still declines the comparison, though both sides carry inputs. Same bug-class as §C.5 itself
   (an index can stay unchecked for as long as no new commit re-stamps the digest), so it is
   fixed in this wave, not deferred: `determinateInputComparisonAvailable` becomes "either digest
   blank, both inputs present" with the ignore list taken from the union of both sides' unresolved
   models. Delegated back to subagent C as a follow-up commit.
5. Read-path twin of §C.1 (from A): `DocumentFieldOps.getDocumentContent(chunkId)`
   (`DocumentFieldOps.java:256`) and the projection reconstruction at `ReadPathOps.java:150-157`
   rebuild a chunk's text from the *current* parent with no revision check, so between a parent
   rewrite and its chunk RMW a read can return a slice of the newer revision. §C.1 scoped the guard
   to the write path (the durable defect); the read-side window closes on the chunk RMW that now
   fails closed, so it is bounded, not silent-forever. Fix shape: compare
   `chunk_parent_content_sha256` on read and surface a not-yet-consistent status instead of the
   slice. Owner: lane D Phase 3 (C0 owns chunk-text reconstruction).
6. **C2 enrichment defect (finding 5, §D 3c entry):** with `rag.chunk_splade.enabled` off, C2's
   combined backfill escalates every PENDING chunk through the SPLADE retry seam each cycle — a
   non-converging loop that starves embedding/NER for ~25 min on a 198-doc corpus and ends with
   every chunk terminal-FAILED; the C2 arm also loses −0.0155 nDCG@10 and 3 % of hits on legal.
   Blocks the C2/C1 draft independently of the C1 campaign. In progress (delegated); closes when
   the fix is on `worktree-wave3-draft` + `wave3-d`, the loop converges in one cycle with the flag
   off (test), and the legal re-measurement on `wave3-c2` lands within 2σ of the C0 arm with
   readiness in the same order of magnitude.
7. Pre-existing, both arms: 39 legal chunks reach terminal SPLADE FAILED with reason
   `blank-content` (C0 arm) / `blank-chunk-content` (C2 relabel) — a chunk whose content read is
   blank at backfill time. Not caused by this wave; owner: tempdoc 717 (chunk content invariant).
8. **`rag.chunk_splade.enabled` is write-side only and lane-inconsistent** (from the finding-5
   diagnosis): `SpladeBackfillOps.java:58` and `BgeM3BackfillOps.java:66` select
   `SPLADE_STATUS=PENDING` with no `is_chunk` filter and no flag check, so chunk SPLADE gets encoded
   whenever one of those lanes wins the race, while the combined lane honours the flag; the
   query-side chunk-SPLADE leg (`SearchExecutor.java:653`) runs off `pipeline.spladeEnabled`
   regardless. Tempdoc 712's F-036 "default stays OFF" reads as "feature off"; it is not. Left
   as-is in this wave because it is the pre-C2 behaviour the C0 baseline was measured under.
   Owner decision: make the flag mean one thing on every lane (and `ChunkDocumentWriter.java:210`
   should not stamp `PENDING` for a stage the configuration may never run). Owner: tempdoc 712.
9. **jseval `--pipeline` readiness is parent-only on chunked corpora** (§D 5b): `spladeCoveragePercent`
   counts whole documents (`IndexCountOps.querySpladeFeatureCounts`), so the query phase can start
   while the individual SPLADE lane is still encoding thousands of chunk docs; a legal hybrid number
   moved +0.011 between "readiness passed" and ten minutes later on the same index. Fix shape: gate
   readiness (or add a `chunk_splade` coverage term when the query-side chunk-SPLADE leg is on) and
   record the lane-idle time in `summary.json`. Owner: jseval (tempdoc 782 readiness contract).
   Until then, treat fresh-run hybrid deltas ≤ ~0.015 on chunked corpora as unsettled and re-query.
10. **Paired-arm comparisons need equal merge state** (§D 5b settled): two fresh indexes of the
    same corpus differed 2,629 vs 222 tombstones, which inflates BM25 collection statistics on one
    arm (`chunk_content` docCount 6,734 vs 4,344) and moved hit counts by 3–4 % with no code
    cause. Fix shape: jseval force-merges (or the Worker exposes a "settle" call) before the query
    phase when a run is claim-bearing, and records `maxDoc/numDocs` per index in `summary.json`.
    Owner: jseval.
11. Status of 6/8 after `286a97bc`: **6 closed** (loop converges in one cycle, readiness 235 s,
    quality gap attributed to 5b/10, not C2); **8 closed on the write side** (every lane honours
    the flag) — the query-side leg still runs regardless of the flag; owner decision remains
    (tempdoc 712).
