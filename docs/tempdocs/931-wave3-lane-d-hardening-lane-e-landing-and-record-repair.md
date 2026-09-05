---
title: "Wave 3: lane D hardening, lane E landing, and record repair after the Codex continuation"
type: tempdocs
status: "IN PROGRESS — planned and designed 2026-09-05; implementation running"
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
| 1a | Chunk RMW revision guard (audit finding 1) | subagent A (adapters-lucene) | code | see §C.1 |
| 1b | Boot identity import: skip when already imported, stream in batches, do not kill the Worker on a legacy parent (finding 2) | subagent B (indexer-worker) | code | see §C.2 |
| 1c | Zero/non-finite embedding: drop the vector field for that document, keep the document, count it (finding 3) | subagent A | code | see §C.3 |
| 1d | Make the PR-B rebuild claim testable at the wire (finding 4) | subagent C | code | see §C.4 |
| 1e | Fingerprint: compare the determinate inputs when a model digest is indeterminate | subagent C | code | see §C.5 |
| 1f | Identity vs content revision (path-slot semantics) | — | design only | §C.6; not implemented this wave (Head+Worker contract, needs owner decision on feedback scoping) |
| 2a | Lane E: diagnose the 105 OHR reconciliation mismatches | subagent D (read-only over artifacts) | ≤30 min | §C.7 |
| 2b | Enron replicate set for 384/25 | — | ~1.5 h | **skipped with reason**: the pre-registered rule uses `max(σ, 0.0068)`, so extra replicates cannot lower the +2σ line below 0.0136 and 384/25's +0.0061 cannot clear it; only an owner amendment of the rule would change the verdict |
| 3a | C0 six-corpus check (C5b): per-language dense-skip rate | orchestrator, live | ~35 min (index-only, no enrichment wait) | run if the eval machine is free; skip rate is a planner decision that needs only the `content` index |
| 3b | C0 quality no-regression on the two cheapest fully-enriched corpora (legal, miracl-de) | orchestrator, live | ~25 min | run after 3a |
| 3c | C2 storage measurement (index bytes, one corpus) | orchestrator, live | ~10 min, rides on 3b's legal build | compare against the lane E sweep's pre-C2 legal index (56.65 MB at 500/50) |
| 3d | C1 quality/recall campaign | — | 3-4 h (tempdoc 915 §P3.F) | **skipped**; C1 stays bundled with C2 in a draft PR |
| 4 | Publication: PRs off `origin/main` | orchestrator | — | (i) lane E closeout replaces #622; (ii) lane D PR-A + 1b; (iii) PR-C0 + 3a/3b evidence; (iv) draft: C2 + C1 + 1a + 1c + 1e; (v) PR-B + 1d. No merge without an explicit go-ahead |
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
