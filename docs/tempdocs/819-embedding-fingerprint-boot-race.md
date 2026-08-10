---
title: "Embedding-fingerprint lifecycle: the fresh-profile boot race that makes the empty-index fast path unreachable, and certification of a rebuild with zero successful embeddings"
type: tempdocs
status: "open — reproduced live 2026-08-10; implementation in progress"
created: 2026-08-10
author: "agent (diagnostic session abd79263, GPU-saturation investigation)"
related:
  - 730   # Issue A (fingerprint durability) — diagnosed the symptom, recorded the cause as UNREPRODUCED; this tempdoc reproduces it
  - 805   # G.1 gave the Tauri shell its ordered-shutdown-before-force-kill path; dev-runner was not swept
  - 598   # embedding fingerprint compat controller + first durability regression
  - 813   # "settled numerator" precedent (terminal = COMPLETED + FAILED) considered and deliberately NOT followed here
---

# 819 — Embedding-fingerprint boot race and zero-evidence certification

Two defects, both **reproduced live** rather than audited. Discovered while investigating why a dev
stack saturated the GPU immediately on start: it was re-embedding a 574-doc index that had been stuck
unstamped since generation `g-20260731-162634`.

This tempdoc closes an open question in **730**, whose frontmatter records Issue A as *"live restart
anomaly unreproduced under production-fidelity tests"*. 730 fixed the shutdown and completion stamp
paths; the origination mechanism below was never found, which is why the symptom persisted.

## A — The empty-index fast path is structurally unreachable

`EmbeddingCompatibilityController.refresh()` (`EmbeddingCompatibilityController.java:119-137`) takes a
fast path when the stored fingerprint is blank **and** `docCount == 0`: state COMPATIBLE, reason
`NEW_INDEX_NO_FINGERPRINT`, fingerprint stamped on the first commit. Otherwise it takes
`BLOCKED_LEGACY`, which the auto-rescue converts into a full re-embed.

On a fresh profile the fast path can never fire:

| time | event |
|---|---|
| 21:55:38 | bundled help docs (`ssot/docs/help/*.md`, 5 files) ingested **and committed** by the indexing loop |
| 21:55:44 | `ECC.refresh()` runs → `BLOCKED_LEGACY (index has no embedding fingerprint; docCount=7)` |
| 21:55:44 | auto-rescue → `REBUILDING` (`parentDocs=5, reMarkedPending=0`) |

Evidence: `modules/ui-web/.dev-data/logs/worker.log:402-405`, on a freshly-emptied data dir, **no kill
and no prior state involved**.

The ordering is structural, not a race in the timing sense:

- `appServices.startIndexingLoop()` runs **synchronously** at `KnowledgeServer.java:702`.
- The ECC is constructed and refreshed inside `initDeferredModels()` (`KnowledgeServer.java:1005-1009`),
  scheduled **asynchronously** at `:719` via `CompletableFuture.supplyAsync` (hence the observed
  `ForkJoinPool.commonPool-worker-1` thread), behind multi-second ONNX model composition.
- `docCount` is a **live** query (`IndexCountOps.java:74-81`), so it reflects post-ingestion state.
- Help ingestion is `KnowledgeServerBootstrap.tryIngestHelpFiles` (`:570-616`, `submitBatch` at `:615`
  with `force_reindex=true`), fired from `completeReadyInitialization()` (`:262`) on first boot.

**Not dev-only.** `KnowledgeServerBootstrap` ships in `app-services` → `app-launcher`; the only exemption
is the `justsearch.eval.mode` system property. Every first launch of the desktop app takes this path.

Impact is modest in isolation (5 help docs re-embedded) but the state it produces is the same
`BLOCKED_LEGACY` that, on a profile with a real corpus, costs a full re-embed on **every** boot until one
rebuild is allowed to run to completion.

### Why 730 could not reproduce this

730's addendum concluded *"no code-level defect reproduced… on two independent fidelity levels (graceful
close and genuine hard-kill via `IndexWriter.rollback()`)"* (`730-worker-lifecycle-integrity.md:559-562`)
and correctly refused to call it a closed non-issue. Its tests were **worker-level**: they index documents
themselves, then close and reopen.

The missing ingredient is that the trigger is not worker-level at all. `tryIngestHelpFiles` lives in
`app-services` (`KnowledgeServerBootstrap`) and reaches the Worker over gRPC from the **Head**, during the
window before `initDeferredModels` has constructed the ECC. A worker-level test never invokes it, so the
index under test is genuinely empty at `refresh()` time and the fast path fires correctly — the defect
disappears exactly when you isolate the worker.

Two corroborating details:
- Eval mode skips help ingest specifically *"so a fresh index truly starts empty"*
  (`KnowledgeServerBootstrap.java:572-578`). Any reproduction attempt under `justsearch.eval.mode`
  therefore removes the trigger by construction.
- 730 asked (`:566-573`) that any re-run capture *"the `EmbeddingCompatibilityController` BLOCKED_LEGACY
  warn line … which logs `docCount`"*. That is precisely the artifact this session captured:
  `BLOCKED_LEGACY (index has no embedding fingerprint; docCount=7)`. The non-zero `docCount` on a
  freshly-emptied profile **is** the finding 730 was asking for.

Consequence for the fix: the regression test must exercise the boot ordering with documents already
committed the way the Head's help batch commits them. A test that indexes documents from inside the
worker and reopens will pass both before and after the fix.

## B — Certification on `pending == 0` accepts "everything failed"

`checkRebuildCompletion` (`EmbeddingCompatibilityController.java:238-254`) certifies when
`pendingEmbeddingCount == 0` and stamps the fingerprint. Failed documents are not pending, so a rebuild
in which **every** document failed satisfies it.

Observed live: `embeddingDocCount=5 completed=0 pending=0 failed=5 coverage=0%` →
`embeddingCompatState=COMPATIBLE, FINGERPRINT_MATCH`. The index now asserts an attestation that is false
and `allowQueryEmbeddings()` (`:286-288`) permits dense/hybrid retrieval against zero vectors.

The trigger in this instance was environmental (`ORT_FAIL … CUDNN failure 1002
CUDNN_STATUS_SUBLIBRARY_LOADING_FAILED`), but the certification admits it regardless of cause.

The controller's javadoc justifies the rule with *"coverage==100% algebraically implies pending==0"* —
true, but the converse does not hold under failures, and the javadoc does not claim it does. The
`pending == 0` condition is the absence of outstanding work, which is **not** evidence of success.

**813's "settled numerator" precedent was considered and does not apply.** There
(`IndexingService.java:350-352`, `RootCoverageCountsTest.java:75-96`), terminal states count as settled so
a permanently-failed doc does not pin a *progress* number below 100% — a number nobody acts on. The
fingerprint is an *attestation that gates serving*. Different obligation; the precedent does not license
certifying on zero success. It does correctly cover the poison-pill case (some succeeded, some
permanently failed), which must still certify.

## C — Retries are not real, which is why B is cheap to reach

- `IndexingDocumentOps.java:237-241` marks a doc `FAILED` on the **first** `RuntimeException`, with no
  retry counter. The 3-strike escalation (`EmbeddingBackfillOps.computeEmbeddingFailureUpdate`,
  `:300-308`) is backfill-only, and the backfill selects only `PENDING` (`:48`) — so an inline-failed doc
  is **never retried**.
- `EmbeddingRecoveryOps.java:151-153` re-marks status but not `embedding_retry_count`, so a doc rescued
  from FAILED gets exactly **one** attempt per boot before flipping back.

Together these make "every document FAILED" reachable from a single transient fault.

## D — dev-runner force-kill (lower severity, measured NOT to be a cause)

`dev-runner` stops with `taskkill /PID <pid> /T /F` (`scripts/dev/dev-runner.cjs:1910`) and never posts
`/api/lifecycle/shutdown`, so `IndexingLoop.finalizeShutdownCommit()` (`:739,766`) cannot run.

**Measured**: a stamped index survives `/F` and returns `COMPATIBLE`/`FINGERPRINT_MATCH` on restart. So
this is not an origination cause — an earlier draft of this investigation claimed it was, and the
experiment refuted that. What it does remove is the backstop for a rebuild that completes exactly as the
loop stops. 805 G.1 gave the Tauri shell the ordered path (`modules/shell/src-tauri/src/lib.rs:145-154`);
the dev-runner was not swept.

Two implementation notes worth keeping, both found in review rather than in the original design:

- **Ordering was load-bearing and initially wrong.** `stopRun` kills the runner first with `taskkill /T`,
  and the backend is a non-detached child of the runner (`spawnLogged` sets no `detached: true`), so a
  graceful attempt placed just before the *backend's* own kill would find the JVM already swept away by
  the runner's tree kill. The graceful POST must precede the entire kill sequence. Confirmed against a
  captured stop-report predating the change: backend `aliveBeforeKill: false` with
  `taskkillStderrTail: "process … not found"`.
- **Suppressing the racing self-exit report needs a cross-process signal.** The supervisor's
  `backend.on('exit')` handler writes `writeSelfExitStopReport` and guards it with an *in-process*
  `reaping` flag; a `stop` invocation is a different OS process and cannot set it. A per-run marker file
  (`<runDir>/graceful-shutdown.json`) carries the signal instead.
  *Known limitation:* if the `stop` process dies between writing the marker and deleting it, a genuine
  backend crash later in that same run would be suppressed from the death report — the diagnostic loss
  730 B2 closed. The window is narrow and contained to one run directory; accepted rather than adding a
  staleness guard, but it is a real edge and should be revisited if death diagnosability regresses.

## Design

Full rationale, rejected alternatives, and the adversarial review that corrected the first design are in
the approved plan. Summary of what is being implemented:

1. **Construct + `refresh()` + wire the ECC synchronously before `startIndexingLoop()`.**
   `EmbeddingFingerprint.get()` is a cached file SHA with no dependency on any loaded model
   (`EmbeddingFingerprint.java:37-43`), so nothing in `initDeferredModels` is required. The **rescue**
   (`maybeAutoStartEmbeddingRebuildForBlockedLegacyBestEffort`) stays late — it needs a `RunningRuntime`
   for the re-mark (`EmbeddingRecoveryOps.java:90-97`). Both ECC suppliers become field-re-reading
   lambdas, because `DeferredRuntime.upgradeWriter()` builds a new `RuntimeSession`.
   *Rejected:* capturing an open-time doc count mirroring `RuntimeSession.openTimeCommitUserData` — it
   fixes the symptom via a proxy, needs cross-cutting surgery, and cannot help a populated profile.
2. **Permit the stamp iff `parentDocCount == 0` OR at least one successful embedding was observed**,
   implemented as a monotone `volatile` latch read by `fingerprintToStamp()` (which is on the
   every-commit hot path via `CommitOps.commit()` — no index query may go there). A failed count read
   must REFUSE, never read as "empty" (`IndexCountOps` swallows `IOException` to 0). Refusal sets a
   distinct terminal reason code and logs once at ERROR.
3. **Make retry real**: route inline failure through the 3-strike escalation; reset
   `embedding_retry_count` on re-mark.
4. **dev-runner graceful stop** mirroring the shell (separable).

## Verification

The acceptance criterion is the live reproduction, not the unit tests: empty the dev data dir, start the
stack, and confirm `/api/status` `worker.compatibility` reports `COMPATIBLE`/`FINGERPRINT_MATCH` rather
than `REBUILDING`. Today that deterministically reports `REBUILDING`.

Two coverage gaps are why both defects shipped, and both must be closed:
- no test boots on a genuinely empty index and asserts COMPATIBLE;
- no test passes a non-zero `failed` count to `checkRebuildCompletion`.
