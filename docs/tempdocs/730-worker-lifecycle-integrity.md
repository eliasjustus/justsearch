---
title: "Worker/dev-runner lifecycle integrity: durable embedding-fingerprint persistence across restart, and diagnosability of backend death under sustained MCP-only agent load"
type: tempdocs
status: "open — planned (725 remediation program), awaiting orchestrator review"
created: 2026-07-14
author: "agent (area agent, tempdoc 725 remediation program)"
related:
  - 725   # parent remediation program (agent tool-adoption legibility); live-validation surfaced both issues
  - 598   # embedding fingerprint compat controller + first durability regression (593 §H reopen B-1)
  - 629   # store-recoverability register + gate (StoreCatalog)
  - 332   # worker lifecycle phases
---

# 730 — Worker/dev-runner lifecycle integrity

Two lifecycle defects surfaced during 725 live validation (2026-07-14). Both are **durability/observability**
problems in the Worker + dev-runner boundary, not search-quality problems.

- **Issue A (fingerprint durability).** An index that is healthy in-session (compat
  `REBUILDING → COMPATIBLE`, `CHUNK_HYBRID` serving) re-flags `BLOCKED_LEGACY` /
  `LEGACY_INDEX_NO_FINGERPRINT` (`embeddingFingerprintStored: ""`) on a plain worker restart,
  blocking the dense leg until a full re-embed. Reproduced twice today (worktree + main `.dev-data`).
- **Issue B (death diagnosability).** The backend disappears under sustained MCP-only agent load
  (2 occurrences). The evidence needed to diagnose it is being destroyed by the logging design, so
  the honest verdict is *inconclusive* and the deliverable is an observability ratchet, not a guessed fix.

**Root-cause status up front:** Issue A is **diagnosed to a specific structural defect with on-disk
proof** (§THEORIZE / §DESIGN). Issue B is **inconclusive by construction** — the diagnostic artifact
was overwritten before it could be read (§THEORIZE B).

---

## THEORIZE

### A. Embedding fingerprint — framing and the proven defect

**Framing chosen: the fingerprint is durable *index metadata*, not a runtime cache.** The compat
fingerprint is written into Lucene **commit userData** by `EmbeddingMetadataOverlay.build()`
(`modules/indexer-worker/.../embed/EmbeddingMetadataOverlay.java:57-69`) and read back at startup by
`EmbeddingCompatibilityController.refresh()` (`modules/worker-core/.../embed/EmbeddingCompatibilityController.java:97-137`).
Commit userData persists on disk with the segment. So the *storage medium* is durable; the defect is
that the **write is conditionally skipped** while a sibling fingerprint is written unconditionally.

**On-disk proof (the smoking gun).** The current active generation
`…/.dev-data/index/default/indices/g-20260714-134648/segments_1w` contains, in its commit userData
(printable-run dump):

- `splade_model_sha256@ab14869593a2539c86e65bfb0f2f64ac…` — **PRESENT** (offset 844)
- `embedding_model_sha256` — **ABSENT**
- `build_state COMPLETE`, `commit_time 2026-07-14T14:07:15Z`, `vector_format float32`
- `_9f.vec` = 38 MB of HNSW dense vectors — **dense vectors exist**

So the index holds **dense vectors + the SPLADE fingerprint but no embedding fingerprint**. On restart
`refresh()` sees `storedFp == null`, `docCount > 0`, and falls to `BLOCKED_LEGACY` /
`LEGACY_INDEX_NO_FINGERPRINT` (`EmbeddingCompatibilityController.java:101-118`) — matching
`embeddingFingerprintStored: ""` exactly. The dense vectors are present but unusable.

**The mechanism (dual defect):**

1. **Asymmetric stamping gate.** The overlay is handed two suppliers
   (`KnowledgeServer.java:1354-1357`): the SPLADE supplier is `SpladeFingerprint::get` —
   *unconditional*; the embedding supplier is `() -> embeddingFingerprintSupplier.get().get()`
   (`KnowledgeServer.java:485-486`), which is `ecc::fingerprintToStamp`
   (`KnowledgeServer.java:1011`, default `Optional::empty` at `:218-219`).
   `fingerprintToStamp()` returns the fp **only** when the ECC is `COMPATIBLE`, or `REBUILDING &&
   rebuildCompleted` (`EmbeddingCompatibilityController.java:258-269`). Any commit that finalizes the
   index while the ECC is not in one of those states persists SPLADE's fp but omits embedding's.

2. **Self-perpetuating ratchet.** Once *one* `COMPLETE` commit lands with `docCount > 0` and no
   embedding fp, every restart resolves `BLOCKED_LEGACY` → `fingerprintToStamp()` returns empty →
   the next commit cannot stamp → the index is stuck `BLOCKED_LEGACY` until a **forced full re-embed**.
   The only auto-rescue, `maybeAutoStartRebuildForLegacyAllPending`
   (`EmbeddingCompatibilityController.java:173-190`), refuses when `completed != 0` — and here
   `_9f.vec` proves embeddings already completed, so it will not fire. The dense leg is dead until a
   manual reindex.

**Why the sibling SPLADE fp is fine** is the tell: identical medium, identical overlay, different
supplier. The bug is not "commit userData doesn't persist" (598's `EmbeddingFingerprintDurabilityTest`
already proves it does); it is "the production wiring only *offers* the embedding fp to the overlay
under a state condition the finalizing commit doesn't always satisfy."

**The intended stamp-persist path exists but is REBUILDING-only.** `tryFinalizeRebuild()`
(`modules/worker-services/.../loop/EmbeddingProviderLifecycle.java:256-289`) is documented as "the only
point where an intentional empty commit is forced to persist the updated fingerprint metadata" — but it
returns early unless `state() == REBUILDING` (`:259-261`). An index that reached `COMPATIBLE` via the
fresh-index path (`docCount == 0 → NEW_INDEX_NO_FINGERPRINT → COMPATIBLE`,
`EmbeddingCompatibilityController.java:104-109`) never enters REBUILDING, so its fp persistence depends
entirely on an *ordinary* commit happening while `currentFingerprint != null` and state stays
`COMPATIBLE`. If embeddings are deferred at that moment (e.g. the 598-R4 GPU handoff releases the
embed session when chat comes Online — `EmbeddingProviderLifecycle.java:168-203`) or the model isn't
yet producing a fingerprint, the finalizing commit persists without the embedding fp and the ratchet
engages.

**Hidden assumptions being challenged:**
- "COMPATIBLE in memory ⇒ COMPATIBLE persisted." False: in-memory state (set by
  `checkRebuildCompletion`, `:199-213`) is not itself a commit; persistence needs a *subsequent* commit
  that the supplier is willing to stamp.
- "The durability test covers this." It does not — see §DERISK (`unreachable-seed-green`).

**Principle candidate:** *an index must self-describe its enrichment lineage durably and
symmetrically — every enrichment fingerprint an index depends on for serving must be persisted by the
same unconditional rule, or its absence must fail the serving decision closed rather than silently
degrade.* Retirement condition: if a fingerprint is ever *legitimately* absent for a served enrichment
(e.g. a truly optional leg), this over-constrains and should be relaxed to a per-leg policy.

### B. Backend death — framing and why it's inconclusive

**Candidate framings:** (i) resource exhaustion (Java heap OOM, native/off-heap ORT/CUDA/direct-buffer
leak, port/handle exhaustion under sustained `/mcp` dispatch); (ii) an unhandled defect on the MCP
dispatch path; (iii) a supervision gap (dev-runner reaper killing a live-but-abandoned stack).

**What the evidence says, and its fatal gap.** For run `e388f09a` (the 40-cell campaign):
- `stop-report.json` → `disposition: "reaped_abandoned"`, `killedPids: [21440, 15344]`
  (frontend + `ui.bat` shell), `taskkillExitCode: 0`. This is a **reaper disposition**, not a crash
  signal — the run was reaped as abandoned (owning session stale), and the `ui.bat` shell was still
  alive to be killed. Whether the JVM grandchild had already died is **not recorded**.
- `backend.stderr.log` holds only the JDK `sun.misc.Unsafe` startup warnings; `backend.stdout.log`
  only `JUSTSEARCH_API_PORT=61638`. These are the **launcher** streams; the JVM's real log is
  `worker.log`.
- `…/.dev-data/logs/worker.log` is a **single file keyed to the data dir**, and its first/last lines
  are `2026-07-14T16:06:58 … 16:08:29` — a **later** run. The death run (13:46–16:04) is **overwritten**.
- No `*.hprof` heap dump exists anywhere, because **neither the head
  (`modules/ui/build.gradle.kts:1799-1802`) nor the worker (`modules/indexer-worker/build.gradle.kts:266-270`)
  sets `-Xmx` or `-XX:+HeapDumpOnOutOfMemoryError`** in the dev-runner path. (The "128–256 MB head heap"
  is the installer/chaos-harness profile — `system-tests/.../WorkerProcessManager.java:203` uses
  `-Xmx256m`; the dev-runner JVMs run at JVM-default heap, so a *Java-heap* OOM in dev is less likely
  than the framing assumes, which pushes suspicion toward native/off-heap or supervision.)

**Prior occurrence corroborates the observability gap, not a cause:** `observations.md:840-842`
(`obs:unanchored-general-19`) — "backend died silently mid-session ~00:20 under light MCP-only load
(run 561cb894, port 62520) … backend logs empty of errors. Second start ran fine. Unexplained."
Same signature: gone, no error trace.

**Verdict:** *inconclusive by construction.* The one artifact that would separate OOM vs defect vs
supervision-reap — the death run's own JVM log + exit code — is destroyed by (a) a shared, per-data-dir
(not per-run) `worker.log` overwritten on the next start, and (b) no exit-code / termination-reason
capture in the stop-report. **The correct deliverable is the observability ratchet that makes the next
death diagnosable**, plus the cheap resource-exhaustion guards (heap-dump-on-OOM) that cost nothing if
the cause is elsewhere.

---

## RESEARCH

**Internet research is NOT warranted for the core work.** Both issues are internal lifecycle: Issue A
is fully diagnosed against our own source + on-disk artifact; Issue B's blocker is *our* logging design,
not an unknown third-party failure mode. No external source can substitute for the death run's
overwritten log.

**Bounded exception (only if the observability ratchet later points there):** if per-run logs from a
*future* death show a native/off-heap growth signature, a cited-summary check of known
ORT/gRPC/Netty direct-buffer leak patterns (`-Dio.netty.leakDetection`, ORT session/allocator
lifecycle) would be justified — summaries only, no code copied. Not now: we have no signature to match.

---

## DESIGN

Root-cause-driven; extends existing machinery rather than adding a parallel authority.

### A. Fingerprint durability — make persistence unconditional and symmetric

**A1 (root fix). Persist the embedding fingerprint by the same unconditional rule as SPLADE, gated on
model availability rather than ECC compat-state.** The compat *serving* decision (block/allow) must stay
ECC-driven, but the *stamp* must not be withheld once the model that produced the vectors is known.
Concretely: the embedding supplier handed to the overlay should return the current embedding fingerprint
whenever an embedding model is loaded and `currentFingerprint != null` (mirroring
`SpladeFingerprint::get`), decoupled from `COMPATIBLE`/`rebuildCompleted`. `fingerprintToStamp()`'s
state-gating (`EmbeddingCompatibilityController.java:258-269`) is the defect surface; the fp that
describes *which model wrote these vectors* is a fact about the write, not about the compat verdict.

**A2 (belt-and-suspenders). Break the ratchet at `refresh()`.** When `refresh()` would resolve
`BLOCKED_LEGACY` but the index actually contains completed embeddings from a *known-current* model
(dense vectors present, `completed > 0`), that is the mixed-lineage case the block exists for — but if
the vectors were written by the *current* fingerprint (provable once A1 stamps them), it must resolve
`COMPATIBLE`. A1 makes A2 mostly moot; keep A2 only as the migration story for already-corrupted
on-disk indices (see A4).

**A3. Force a stamp-persisting commit on the non-REBUILDING completion path too.** `tryFinalizeRebuild`
(`EmbeddingProviderLifecycle.java:256-289`) is REBUILDING-only; the fresh-index →COMPATIBLE path has no
equivalent guarantee that *some* commit carried the fp before the queue drained. Add a
finalize-on-first-compatible-drain that fires the same intentional commit when the ECC transitions to
COMPATIBLE with `docCount > 0` and no persisted embedding fp yet. (With A1 this is redundant for new
writes but closes the deferred-embeddings/GPU-handoff timing window named in §THEORIZE A.)

**A4. Migration for existing corrupted generations.** The reproduced on-disk generation
(`g-20260714-134648`) is already stuck. Options, in preference order: (a) a one-shot startup
reconciliation that, when dense vectors + a known-current model are present but the embedding fp is
absent, stamps it via an empty commit (safe iff we can prove the vectors match the current model — the
same proof A2 needs); (b) failing that proof, surface an explicit operator-facing
`LEGACY_INDEX_NO_FINGERPRINT` reason that names re-embed as the remedy (today's behaviour, but legibly).
Name the orphan: **`maybeAutoStartRebuildForLegacyAllPending`'s `completed == 0` guard**
(`EmbeddingCompatibilityController.java:181`) is the reason auto-rescue silently refuses the
`completed > 0` case — decide whether A4(a) supersedes it or sits beside it.

**Pre-merge checks that apply to A:** none of the fingerprint files are `StoreCatalog` construction
sites, so `check-store-recoverability` does **not** apply. If any change touches indexing-job lifecycle
surfaces, run `--gate operation-surface`. Worker unit-test modules: `:modules:worker-core`,
`:modules:worker-services`, `:modules:indexer-worker`.

### B. Death diagnosability — observability ratchet (the deliverable, since the cause is inconclusive)

**B1. Per-run app log (the load-bearing fix).** `worker.log` must not be a single per-data-dir file
overwritten on the next start (proven data-loss: the death run's log is gone). Either write the JVM log
into the run directory (`tmp/dev-runner/runs/<id>/logs/worker.log`) alongside the launcher streams, or
copy/rotate `worker.log` into the run dir at stop **before** the next start can truncate it. This alone
converts every future death from "inconclusive" to "diagnosable."

**B2. Termination-reason + exit-code capture in `stop-report.json`.** Today it records `killedPids` +
`taskkillExitCode` but not whether each PID was **alive before** the kill. Add a pre-kill liveness probe
per PID so `reaped_abandoned` can distinguish "backend already dead, reaper cleaned up the shell" from
"backend live, reaper killed an abandoned-but-healthy stack." Capture the backend JVM's own exit code
when it self-exits.

**B3. Heap-dump-on-OOM (cheap, no-cost-if-wrong).** Add `-XX:+HeapDumpOnOutOfMemoryError
-XX:HeapDumpPath=<run-dir>` (and a sane `-Xmx` so a runaway is bounded and dumps rather than thrashing)
to the dev-runner head + worker JVM args. If the cause is a Java-heap OOM, the next death leaves a
`.hprof`; if it isn't, the flag is inert.

**B4. Native/off-heap breadcrumb (conditional).** Since dev heap is unbounded, favour native leak as a
hypothesis: enabling a low-overhead direct-buffer / allocator counter in the periodic worker heartbeat
line would let B1's per-run log show growth-to-death. Design only; wire iff B1's first captured death
lacks a Java-heap signature.

**Existing assets to reuse:** the JFR recording task already exists
(`modules/ui/build.gradle.kts:2047`, `-XX:StartFlightRecording`) — a `duration`/`filename` variant
pointed at the run dir is a ready-made B1 companion for the head. The generation layout's `state.json`
+ `.justsearch-index-generation.json` are the durable per-generation record; B1 should sit beside them,
not invent a new store.

---

## DERISK

### Static-retirable vs live-probe

**Issue A — a unit/integration test WITHOUT a live stack is feasible and is the gold standard here.**
The existing `EmbeddingFingerprintDurabilityTest`
(`modules/indexer-worker/.../embed/EmbeddingFingerprintDurabilityTest.java`) already does a real
Lucene close→reopen at `Files.createTempDirectory` — but it is **`unreachable-seed-green`**: it seeds
the overlay with `EmbeddingMetadataOverlay.createSupplier(() -> Optional.of(FP))` (`:51`, `:68`),
i.e. an *unconditional* supplier, so it exercises the SPLADE-shaped path and never touches the
production `ecc::fingerprintToStamp` gate that is the actual defect. It passes precisely because it
avoids the bug.

**The reproducing test (high-confidence, no live stack):** drive the *real* ECC-gated supplier through
the lifecycle — construct the ECC + overlay wired as production does (`ecc::fingerprintToStamp`), take
the index through `BLOCKED_LEGACY → REBUILDING`, finalize, commit, close→reopen, and assert the
embedding fp survived and a fresh ECC resolves `COMPATIBLE`. A negative control: finalize the drain
*without* the A3 forced commit and assert it reverts (proving the test bites on the real hole). This is
the `audit-driven-fixes-need-test` obligation for A. **Feasibility: high** — all the pieces
(`IndexSchema.fromCatalog(...).atPath(dir).open()`, `commitAndTrack`, `latestCommitUserDataBestEffort`)
are already used by the existing test.

**Live-probe asks (restart-cycle, orchestrator-leased):**
- **A-live:** ingest a small corpus on a fresh generation → assert `embedding_model_sha256` present in
  the served commit userData on disk → restart → assert `/api/status`
  `embeddingFingerprintStored != ""` and compat `COMPATIBLE`, dense leg serving. Then repeat via the
  `BLOCKED_LEGACY → REBUILDING → COMPATIBLE` path (the reproduced one).
- **B-live:** a sustained MCP-only load soak with B1 in place, to capture a per-run log across a death.

**Confidence:** Issue A root cause **9/10** (on-disk artifact + wiring asymmetry are dispositive; the
only residual is *which* of the two timing windows fired in the reproduced run, which the reproducing
test will pin). Issue B cause **2/10** (inconclusive — that is the finding); the *observability design*
confidence is **8/10**.

**Model/effort recommendation:** A1+A3 + the reproducing test — one bounded `sonnet` implementation
chunk with self-verifying acceptance (test red→green). A4 migration — `sonnet`, but flag the
"prove vectors match current model" sub-decision for orchestrator/owner review before coding. B1/B2/B3 —
dev-runner (Node) + gradle JVM-args, one `sonnet` chunk; B1 is the priority.

---

## PLAN

### Increment 1 — reproduce Issue A red (test-first)
- Add a reproducing test (per §DERISK) in `modules/indexer-worker` beside the existing durability test,
  wired to the **production** `ecc::fingerprintToStamp` supplier. **Verify:** test is RED against
  current `main`, and fails for the right reason (embedding fp absent after reopen, SPLADE fp present).

### Increment 2 — root fix A1 + A3
- A1: make the embedding fingerprint supplier stamp on model-availability, symmetric with
  `SpladeFingerprint::get`, without weakening the ECC *serving* gate.
- A3: force a stamp-persisting commit on the first COMPATIBLE-with-docs drain outside REBUILDING.
- **Verify:** Increment-1 test GREEN; `:modules:worker-core`, `:modules:worker-services`,
  `:modules:indexer-worker` module tests green; `./gradlew.bat build -x test`. Confirm no regression in
  `EmbeddingCompatibilityControllerTest` / `IndexingLoopTest` (the `onFingerprintStamped` verify at
  `IndexingLoopTest.java:1116`).

### Increment 3 — migration A4 for already-corrupted generations
- Startup reconciliation OR legible operator remedy (owner-gated sub-decision). **Verify:** a test that
  seeds an on-disk generation with dense vectors + SPLADE fp + no embedding fp (mirroring
  `g-20260714-134648`) and asserts the chosen A4 behaviour. **Orphan teardown:** reconcile
  `maybeAutoStartRebuildForLegacyAllPending`'s `completed == 0` guard with A4 — either supersede or
  document why it stays.

### Increment 4 — death observability ratchet B1/B2/B3
- B1 per-run worker log (priority); B2 stop-report exit-code/liveness; B3 heap-dump-on-OOM + bounded
  `-Xmx` in the dev-runner JVM args. **Verify B1:** start→stop→start twice, assert run N's
  `worker.log` still exists and is distinct after run N+1 starts (the exact overwrite this fixes).
  **Verify B2:** a stop-report from a self-exited backend records its exit code; a reaped one records
  per-PID liveness. **Verify B3:** unit-force an OOM in a throwaway harness (or assert the flags are on
  the generated `ui.bat` / worker command).

### Increment 5 — live validation (orchestrator-leased) & closure
- Run A-live and B-live (§DERISK). Independent reviewer (≠ implementer) per slice-execution honor-system.
- Update `/inference-runtime` register if the ECC stamping contract changes. Fold observation shards.

### Subagent split
- **Worker A (sonnet):** Increments 1–2 (reproducing test + A1/A3). Self-verifying (red→green).
- **Worker B (sonnet):** Increment 4 (B1/B2/B3, dev-runner + gradle). Independent of Worker A.
- **Worker C (sonnet, gated):** Increment 3 (A4 migration) — after the orchestrator resolves the
  "prove vectors match current model" sub-decision.
- Increment 5 stays main-loop (lease + evidence judgment + closure), not delegated.

**Brief inline for each worker:** Hard Invariants (Head never touches Lucene — all index IO via the
Worker), primary-source `file:line` evidence required for load-bearing claims, and the specific
acceptance test that must go green. Do not delegate the lease/live-run or the A4 sub-decision.

---

## Increment-4 review findings (2026-07-14, review-changes cycle)

Independent review of the Increment-4 (B1/B2/B3) diff surfaced four items, all applied in the
same pass. Files: `scripts/dev/dev-runner.cjs`, `scripts/dev/test-dev-runner-death-observability.mjs`,
`modules/indexer-worker/build.gradle.kts`.

**(a) Live-probe root cause: MCP-driven stop is a LATENT half, not an active one, until merge.**
`scripts/dev/justsearch-dev-mcp/server.mjs` resolves `devRunnerPath` once at module load
(`resolveRepoRoot()`, `server.mjs:596`) and the `justsearch.dev.start` tool handler accepts a
`distFrom` override that lets a worktree agent point the *start* at its own checkout's
`dev-runner.cjs` (`server.mjs:691-719`) — but the `justsearch.dev.stop` handler has no such
override; it always spawns `devRunnerPath` (`server.mjs:1893,1933`), i.e. the dev-runner.cjs of
**whichever checkout the MCP server process itself was launched from**. Consequence: B1's
per-run worker.log preservation and B2's stop-report (both implemented in `stopRun()`) only run
against a code path exercised via `dev_stop` when that call executes the *server's own* checkout's
dev-runner — which is `main`'s, not this worktree's, until this branch merges and the MCP server
process is restarted. The half of B1/B2 that IS active today is the self-exit path
(`backend.on('exit')` inside `cmdStart`, `writeSelfExitStopReport` — see B2's comment at
`dev-runner.cjs` ~line 587), because `cmdStart` runs inside whichever process invoked `dev_start`,
which DOES honor `distFrom` and so can run this worktree's own dev-runner. Net: B1/B2 are fully
implemented and unit-verified, but their `dev_stop`-driven half is latent until (1) this work lands
on `main` and (2) the MCP server process is restarted to pick it up — file this as an operational
note for whoever runs Increment 5's live validation, not a code defect.

(`writeSelfExitStopReport` — the self-exit half that IS active today — is defined at
`dev-runner.cjs:664`, current line count after this pass's edits.)

**(b) Heap-cap inversion (Fix 1).** `buildHeadJavaOpts` (dev-runner.cjs) and `runWorkerStandalone`
(indexer-worker/build.gradle.kts) previously defaulted `-Xmx` to `2g`/`1g` respectively even when
`JUSTSEARCH_HEAD_HEAP`/`JUSTSEARCH_WORKER_HEAP` were unset. Review finding: a default cap can
*induce* an artifact OOM in the exact death scenario B3 exists to diagnose — a process that would
otherwise have run fine at JVM-default heap gets OOM-killed by an arbitrary dev-time bound, and the
resulting heap dump documents an artifact of the diagnostic tooling, not the real defect. Both call
sites now emit the dump flags (`-XX:+HeapDumpOnOutOfMemoryError`, `-XX:HeapDumpPath=...`)
unconditionally and emit `-Xmx<value>` ONLY when the corresponding env var is explicitly set —
no default bound. Verified via the two `buildHeadJavaOpts` unit tests (defaults: dump flags
present, no `-Xmx` token at all; override: `-Xmx<value>` present) and a
`:modules:indexer-worker:runWorkerStandalone --dry-run` config-time check.

**(c) Ownership guard for `preserveWorkerLog` (Fix 2).** The originally-sketched guard
("current file's mtime ≥ the readiness-stamp's mtime ⇒ still ours") is unsound: a worker.log
legitimately grows during a run (mtime always advances), but so does a *later* run's overwrite of
the same shared path — that later mtime is *also* ≥ the earlier run's stamp, so the naive guard
would file run B's content as run A's "verified" log (a silent mislabel, worse than the original
overwrite-loses-everything bug it replaces). Two designs were evaluated:
- **First choice: birthtime identity** (WorkerSpawner rotates by *renaming* worker.log →
  worker.log.1 → worker.log.2 on the next spawn; a rename preserves birthtime, a fresh spawn's
  newly-created file gets a new one). A live probe against this Windows/NTFS checkout disproved
  the assumption: NTFS *file-system tunneling* hands a file freshly created at a just-vacated path
  (via rename-away or unlink+recreate) the OLD file's birthtime back, making the rotated file and
  a brand-new replacement indistinguishable by birthtime alone. Reproduced directly
  (create → rename-away → recreate-at-same-path → recreated file's `birthtimeMs` matched the
  original's).
- **Implemented (fallback named in the review plan): size-monotonicity + rotation-name matching.**
  A stamp `{size, mtimeMs}` is captured at readiness (`captureWorkerLogStamp`, called once
  `waitForBackendReady` confirms HTTP-readiness in `cmdStart`, so WorkerSpawner's own
  startup/rotation has settled) and stored on `run.json` as `workerLogStamp`. At stop time,
  `preserveWorkerLog` treats a same-path file as still-ours only if its size and mtime are both
  ≥ the stamp (an append-only log never shrinks while a run owns it — a size drop is the tell that
  the path was rotated/replaced under us) → `ownership:'verified'`. If the current file fails that
  check, `worker.log.1`/`worker.log.2` are checked the same way → `ownership:'heuristic',
  source:'rotated'`. If nothing matches → `preserved:false, reason:'ownership_unverified'` (never a
  silent mislabel). A `run.json` with no stamp (pre-Increment-4-review runs) keeps the prior
  best-effort copy behavior, tagged `ownership:'unstamped'`. New test
  `testPreserveWorkerLogOwnershipGuard` in `test-dev-runner-death-observability.mjs` exercises all
  four outcomes, including the exact mislabel construction this guard exists to prevent.

**(d) Environmental note: concurrent Gradle corrupts shared caches in this tree.** Two independent
workers on this program hit Gradle config-cache/build-cache corruption from running builds
concurrently against the same checkout (shared `.gradle` state, not per-worktree). Orchestration
for this program now serializes Gradle verification steps across parallel workers rather than
fanning them out — noted here since it shaped how Increment 4's fix-review-verify cycle was run
(the `--dry-run` config-time check above, plus the two death-observability/pruning/admission test
scripts, were run one at a time, not concurrently).

---

## A1/A3 post-review correction (2026-07-14, MAJOR finding fix)

Independent adversarial review of the A1/A3 delta found a MAJOR defect in **A1 as originally
implemented**: it swapped `KnowledgeServer.java`'s embedding-fingerprint supplier from the
ECC-gated `ecc::fingerprintToStamp` to the unconditional `EmbeddingFingerprint::get`, reasoning the
stamp is "a fact about the write" decoupled from compat state. That reasoning was **review-refuted
as a mixed-provenance over-claim**: a forced reindex is in-place/incremental
(`JobBatchExtractor.java:193-212` — no wipe), so an interrupted BLOCKED_MISMATCH/BLOCKED_LEGACY ->
REBUILDING run holds a genuinely MIXED old/new-model vector set. Stamping unconditionally meant an
ordinary commit landing mid-rebuild persisted the NEW model's fingerprint over that mixed index;
restart then resolved COMPATIBLE and silently served the mixture — exactly the class of bug the
gate exists to prevent. The old gate correctly kept that state BLOCKED.

**Shipped shape, post-correction:** A1's supplier swap is **reverted** —
`KnowledgeServer.java:1022-1023` is back to `embeddingFingerprintSupplier.set(ecc::fingerprintToStamp)`.
The REAL gap A1 was reacting to — a rebuild completion (or fresh-COMPATIBLE transition) with no
SUBSEQUENT commit ever landing, leaving the fingerprint unpersisted and the next restart
re-flagging BLOCKED_LEGACY even though the rebuild genuinely completed — is closed at two guarantee
call sites instead of by weakening the stamp gate:

1. **`EmbeddingProviderLifecycle.tryFinalizeRebuild()`** (`modules/worker-services/src/main/java/io/justsearch/indexerworker/loop/EmbeddingProviderLifecycle.java:256-289`,
   unchanged) already fires its own `commitAndTrack(REBUILD_STAMP)` + `onFingerprintStamped()` the
   moment `checkRebuildCompletion` flips state — this always worked when the loop reached a
   subsequent idle/batch iteration.
2. **`IndexingLoop.finalizeShutdownCommit()`** (`modules/worker-services/src/main/java/io/justsearch/indexerworker/loop/IndexingLoop.java`,
   new — extracted from the former inline "Final commit on shutdown" block) is the actual root-cause
   fix: the old shutdown block only committed when `indexedSinceCommit > 0`, a check irrelevant to
   whether the ECC's completion needs a forced empty stamp commit. A plain worker restart right
   after rebuild completion (or a fresh-COMPATIBLE transition with no further writes) previously hit
   this exact gap — the loop stopped before its next idle/batch iteration could retry, and the
   shutdown commit skipped entirely because there was nothing else pending. `finalizeShutdownCommit()`
   now calls `tryFinalizeEmbeddingRebuild()` + `tryFinalizeFreshCompatibleEmbeddingStamp()`
   unconditionally before the `indexedSinceCommit`-gated commit — both self-gate on ECC state and
   are no-ops when nothing needs stamping, so this is the same wiring the idle/batch path already
   uses, not a parallel mechanism.

**A3 hardening** (`EmbeddingProviderLifecycle.tryFinalizeFreshCompatibleStamp()`): now additionally
requires the LIVE `EmbeddingFingerprint.get()` to be present, not just the ECC's cached
`currentFingerprint()` snapshot (the two can diverge if the model goes offline after the ECC's last
`refresh()`), and backs off to at most one retry per 5 minutes after a failed forced commit
(reset immediately if the ECC leaves COMPATIBLE), instead of retrying on every idle drain
(~once/sec).

**Tests reworked**: `EmbeddingFingerprintProductionWiringDurabilityTest` (indexer-worker) now wires
the real, reverted `ecc::fingerprintToStamp` supplier (not the review-refuted unconditional one) and
covers the ratchet reproduction (positive: guarantee commit persists; negative: no commit reverts to
BLOCKED_LEGACY), the mixed-provenance guard (an ordinary mid-rebuild commit must stay BLOCKED on
reopen — this is RED under the old unconditional supplier), and the existing negative control.
`IndexingLoopTest#finalizeShutdownCommitFiresRebuildStampWithNoIndexedSinceCommit` (worker-services)
pins the actual shutdown-wiring fix directly (disable/red -> restore/green verified).
`EmbeddingProviderLifecycleTest` gained coverage for the A3 live-fingerprint + backoff hardening.

**Bonus hardening (Scope-3, unrelated module):** `SqliteJobQueue.pollPending`'s claim UPDATE now
also guards with `WHERE state = 'PENDING'`, not just the upstream SELECT — defense-in-depth for a
hypothetical future multi-consumer change, with a direct SQL-shape test in `JobQueueTest`.

---

## A4 migration (2026-07-14, Increment 3)

Shipped as detection-plus-rebuild, not back-stamping, per the orchestrator-resolved semantics: an
on-disk generation showing the corruption signature (docCount > 0, embedding fingerprint absent from
commit userData, `completed > 0` evidencing dense vectors already exist — the exact shape reproduced
by `g-20260714-134648`) cannot have its provenance retroactively proven, so
`EmbeddingCompatibilityController.maybeAutoStartRebuildForLegacyUnattestedVectors(docCount, completed)`
(`modules/worker-core/.../embed/EmbeddingCompatibilityController.java`) is added as a sibling to
`maybeAutoStartRebuildForLegacyAllPending` and wired as its fallback at the same startup/refresh call
site (`KnowledgeServer.maybeAutoStartEmbeddingRebuildAllPendingBestEffort`,
`modules/indexer-worker/.../server/KnowledgeServer.java:1289-1315`): it transitions BLOCKED_LEGACY →
REBUILDING (the same forced-reindex path a user-initiated reindex takes), letting the index earn a
legitimate stamp once the rebuild completes through the already-commit-guaranteed paths from the
A1/A3 correction (`EmbeddingProviderLifecycle.tryFinalizeRebuild()` /
`IndexingLoop.finalizeShutdownCommit()`) — making the recovery self-terminating on the next restart.
Operators can tell the two auto-rescue paths apart via a new diagnostic-only
`lastAutoRescueReason()` accessor (`"legacy_all_pending"` vs. `"embedding_legacy_unattested_vectors"`,
also logged at INFO) that is deliberately kept OUT of the `SearchReasonCode` wire contract — both
paths still resolve the shared, contract-stable `"REBUILD_IN_PROGRESS"` `reasonCode()` for query-time
degradation messaging, so this migration does not touch `searchTraceExplain.ts` or
`check-search-degradation-reason-codes`. **Guard reconciliation:** the original method's
`completed == 0` guard is NOT loosened or superseded — it is preserved verbatim for its original
purpose (proving *nothing* has been embedded yet is what makes a blind, consent-free rebuild safe
without the new method's re-embed cost), and the new method's own `completed > 0` guard is its exact
complement, so the two guards partition the BLOCKED_LEGACY space with no overlap and no case falls
through unhandled. Tests: `EmbeddingCompatibilityControllerTest` (worker-core) gained three ECC-level
guard tests (`maybeAutoStartRebuildForLegacyUnattestedVectorsTransitionsToRebuilding`,
`...IsConservative`, `...DoesNotFireOnProperlyStampedGeneration`); a new
`EmbeddingFingerprintLegacyUnattestedVectorsMigrationTest` (indexer-worker), harness-modeled on
`EmbeddingFingerprintProductionWiringDurabilityTest`, seeds the exact on-disk signature and drives
reopen → detect → REBUILDING (new reason) → completion-guarantee commit → reopen → COMPATIBLE with
the legitimately-earned (not back-stamped) fingerprint, plus the two named negative controls
(properly-stamped generation doesn't trigger it; genuinely-empty all-pending generation still uses
the original rescue path).

---

## Live-falsification follow-up (2026-07-14, boot-time re-read investigation)

**Trigger.** After 90b02c0/7a46ca1, a live dev-runner restart cycle (HARD taskkill, no graceful
shutdown, following a completed rebuild where `/api/status` had already shown COMPATIBLE + storedFp
present) reportedly re-resolved `embeddingFingerprintStored: ""` → BLOCKED_LEGACY, with A4's
auto-rescue firing again. On-disk inspection at that time showed
`index/default/indices/g-20260714-134648/segments_22` containing `embedding_model_sha256` — so
persistence had worked and the suspected defect was the boot-time re-read.

**Investigation.** Traced the exact production boot path: `KnowledgeServer.java:505`
(`hasLuceneSegments(activeIndexPath)` → true once segments exist) selects `builder.openDeferred()`
(`RuntimeSession.Mode.DEFERRED`, read-only-first), and `ecc.refresh()`
(`KnowledgeServer.java:1009`) reads via `ingestLifecycle::latestCommitUserDataBestEffort`
→ `RuntimeSession.latestCommitUserDataBestEffort()` (`RuntimeSession.java:653-665`), which opens a
**fresh** `DirectoryReader.open(directory)` on every call — independent of any writer/searcher state,
independent of `openTimeCommitUserData`'s open-time snapshot. Every existing 730 test
(`EmbeddingFingerprintProductionWiringDurabilityTest`, `EmbeddingFingerprintDurabilityTest`) reopens
via `IndexSchema.atPath(dir).open()` (read-write `RUNNING` mode) — never `openDeferred()` — so none of
them actually exercised production's restart shape, matching this tempdoc's own suspicion
("the unit tests' reopen... find the exact divergence").

Two new regression tests were added to close that gap, both mirroring production exactly:

1. **`(d)` — DEFERRED boot, graceful close.** Stamps the fingerprint via the real
   `EmbeddingCompatibilityController`/`EmbeddingMetadataOverlay` production wiring (same two-phase
   late-binding shape as `KnowledgeServer.java:485-486` + the supplier set-site), closes gracefully,
   then reopens via `.openDeferred()` (not `.open()`) and asserts COMPATIBLE + the stored fingerprint.
   **Result: GREEN on the first run** — DEFERRED-mode boot reads the commit userData identically to a
   read-write reopen.
2. **`(d)`, hardened — true hard-kill semantics.** Rewrote the same scenario to *not* call
   `RuntimeSession.close()` at all after the guarantee commit: a new
   `abandonWithoutGracefulClose()` helper (reflection into the package-private `RunningRuntime.session()`
   → `RuntimeSession.snapshot` → `LifecycleSnapshot.writer()`, required because those internals are
   package-private to `io.justsearch.adapters.lucene.runtime` and this test lives in
   `io.justsearch.indexerworker.embed`) calls `IndexWriter.rollback()` directly. This is a materially
   more faithful hard-kill than a graceful close: it releases the Lucene write-lock without running
   `RuntimeSession.close()`'s clean-shutdown path at all, so `CleanShutdownMarker` is genuinely never
   written (the same precondition a real `taskkill` leaves behind) — triggering
   `ComponentsFactory.java:158-166`'s "dirty-open escalation" (FULL integrity scan) on the next open, a
   precondition the graceful-close variant above never exercised. **Result: still GREEN** — DEFERRED
   boot reads the stamped fingerprint correctly even under a genuinely unclean shutdown.

Also independently reconciled with a read-only inspection of the actual live `.dev-data` index
directory (which the orchestrator's rebuild was running in, at the time): `state.json`'s
`active_generation` matches `g-20260714-134648`; that directory has exactly one `segments_N` file
(`segments_22`, no higher generation superseding it — ruling out an ordinary post-stamp commit
silently dropping the key, since `CommitOps.commit()` fully rebuilds commit metadata from
`CommitMetadataSource.build()` on every call via `IndexWriter.setLiveCommitData()` — a **replace**, not
a merge — so any commit at a moment `ecc.state()` isn't COMPATIBLE would in principle erase a prior
stamp; this was a live structural concern investigated and ruled out here specifically because no
superseding generation exists and the periodic commit-timer (`CommitOps.timerTick()`,
`CommitOps.java:332-335`) only fires when `pendingDocs > 0`, so an idle worker between the guarantee
commit and a later hard-kill issues no further commits); the file's raw bytes contain both
`embedding_model_sha256` and `splade_model_sha256` with a plausible, complete-looking `commit_time`
and no follow-on generation.

Also checked and ruled out: `IndexMetadataParityGuard.checkOnOpen()` (a third, independent
`DirectoryReader.open` read used only for schema-parity diagnostics, not gating); `ecc.refresh()` has
exactly one production call site (`KnowledgeServer.java:1009`), so no stale/second-refresh race exists;
`EmbeddingProviderLifecycle`'s `embeddingCompatController` is wired from the *same* `ecc` instance via
`DefaultWorkerAppServices.wireEmbeddingCompatController` (no split-ownership `standalone-capability`
gap); the installed worker dist in this worktree's `.dev-data` (`build-stamp.txt`, all lib jars
uniformly timestamped from one `installDist` run) is consistent with a fresh, post-fix build, not an
obviously stale one.

**Outcome: no code-level defect reproduced.** Per `interrogate-results` / `audit-driven-fixes-need-test`,
a static/live claim is a hypothesis until a test proves it; here the test says the opposite of the
hypothesis, on two independent fidelity levels (graceful close and genuine hard-kill via
`IndexWriter.rollback()`). The mixed-provenance guard and the gated `ecc::fingerprintToStamp` supplier
were not touched. **This addendum does not claim the live observation was wrong** — only that it was
not reproduced by a test that faithfully mirrors production's boot mechanics as closely as this
investigation could construct. Before treating this as a closed non-issue, the live falsification
should be re-run with: (a) a guaranteed-fresh worker dist
(`./gradlew.bat :modules:indexer-worker:installDist` immediately before the restart, to positively rule
out a stale-JVM/stale-dist explanation — `justsearch_dev_start` does not always reinstall when
upstream tasks report UP-TO-DATE, a documented pitfall for the Head process and plausibly analogous for
the Worker's own `installDist`/`build-stamp.txt` mechanism), and (b) the worker log captured across the
exact restart, specifically the `EmbeddingCompatibilityController` BLOCKED_LEGACY warn line
(`EmbeddingCompatibilityController.java:115-118`, which logs `docCount`) and whatever logs the
resolved `activeIndexPath`/generation id at that boot, to confirm the same generation directory was
opened both before and after the kill.

**New tests** (both green): `EmbeddingFingerprintProductionWiringDurabilityTest` gained
`(d) productionDeferredBootReadsStoredFingerprintAfterGuaranteeCommit` and the
`abandonWithoutGracefulClose` helper (indexer-worker module,
`modules/indexer-worker/src/test/java/io/justsearch/indexerworker/embed/EmbeddingFingerprintProductionWiringDurabilityTest.java`).
These are a net-new coverage gap closed regardless of this addendum's outcome: production's actual
restart path (`openDeferred()` after a genuinely unclean shutdown) was previously untested by any test
in this class.
