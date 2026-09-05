<!-- Sidecar of docs/tempdocs/915-lane-d-index-fingerprint-identity-and-reindex-bundle.md — moved here per the tempdoc size-cap split (930 §19.3 F4, 2026-09-05). Headings below are unchanged from the main file; this directory is exempt from check-tempdoc-size.mjs. -->

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
