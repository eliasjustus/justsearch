---
title: "Filesystem reality: junctions/symlinks/long paths/network media classification, access-denied ledger rows, AV guidance, and a scale + Unicode torture matrix"
type: tempdocs
status: CHARTERED (2026-09-02) — not started
created: 2026-09-02
updated: 2026-09-02
lane: 887 L3
model: opus (takeover)
parent: 887-improvement-landscape-register
related:
  - 410-adversarial-ingestion-resilience   # hostile-input matrix; the open rows ARE this lane's spec
  - 418-worker-owned-filesystem-traversal  # traversal authority is the Worker
  - 419-unused-user-agent-capability-discovery  # ledger backend shipped, FE slice unbuilt
  - 626-incremental-indexing-correctness   # convergence authority; delete_detection_unverified
  - 885-decision-review-lane-c-runtime-lifecycle-and-isolation  # extraction pool / sandbox (do not touch)
---

# 889 — Filesystem reality

## Briefing for the agent picking this up

Fresh start. Read this file, then 887 Appendix A2 (evidence, `file:line`) and tempdoc 410's
"Hostile-Input Behavior Matrix" plus its lines 288, 374, 705, 1087, 1923 (the still-open rows).
Work in a worktree. The traversal code is `modules/worker-services/.../services/{SyncDirectoryOps,
WorkerScanOps}.java`, admission is `WorkerIngestionAuthority.java`, reason codes are
`modules/worker-core/.../ingest/IngestionReasonCodes.java`, the ledger contract is
`docs/explanation/03-knowledge-server.md` §ledger. Do not touch the extraction pool, sandbox, or
pacing code — 885 (founder lane C) is live there. Every new skip/defer must be **non-silent**:
a typed reason code and a ledger row, exactly as `CloudPlaceholderRecorder.java:19-27` does for
OneDrive placeholders. The `03-knowledge-server.md:283` sentence naming three "deliberately
deprioritized" gaps routed to a retired store is the doc you will be correcting.

## Thesis

The walk handles one special case well (cloud placeholders: detected, deferred, ledgered,
privacy-safe) and every other one silently or not at all: junctions and symlinks are followed
with default link options (`WorkerScanOps.java:185`), permission-denied files are dropped at
`log.debug` with no ledger row (`SyncDirectoryOps.java:323-329`, `WorkerScanOps.java:238-245`),
long paths, network and removable media have no handling, and no test exercises multi-GB files,
millions of files, deep nesting, or bidi/NFC filenames.

## Scope

1. **Reparse-point classification.** Detect junctions, symlinks, and other reparse points via
   `BasicFileAttributes.isSymbolicLink()`/`isOther()` + `dos:attributes` `FILE_ATTRIBUTE_REPARSE_POINT`
   in `preVisitDirectory`/`visitFile`. Policy (decided): **skip by default** with reason
   `SKIPPED_POLICY` / sub-reason `REPARSE_POINT`, ledgered like placeholders; a watched root that
   is itself a junction is admitted (the user chose it). This closes the junction-duplicate gap
   named at `03-knowledge-server.md:283`. Loop protection stays (`realPath` visited-set).
2. **Access-denied becomes visible.** `visitFileFailed` and `!isReadable` paths emit a ledger row
   with `IngestionReasonCodes.UNREADABLE` and increment `filesSkipped` in *both* walks
   (`SyncDirectoryOps` counts nothing today). Distinguish `AccessDeniedException` from other
   `IOException`s. Root-level: `RootLifecycleOps` gains a `ROOT_UNREADABLE` state next to
   `ROOT_NOT_DIRECTORY` (`:56`) so a Controlled-Folder-Access-blocked root is reported, not spun on.
3. **Long paths and media.** Verify `\\?\`-length paths (>260) index on Windows under the JDK's
   long-path support; add a test creating a 300-char path. Classify network (`UNC`, mapped) and
   removable roots via `FileStore` and surface it in the root's status projection (no policy
   change — the founder decides later whether to gate them; this lane only makes them legible).
4. **FE consumer for the ledger rows — owned by 906.** Owner-approved rescope
   (2026-09-06): 906 builds the minimal Library summary with readable counts by
   outcome/reason. This lane owns the filesystem producers and any additional
   reason rows they require; consume/extend the 906 presentation rather than
   building a second panel. The broader scan-progress and filename-resolution
   plans in `docs/how-to/library-indexing-activity-panel.md` are not implied by
   this handoff. See 906 §U for implementation and verification status.
5. **Antivirus interaction.** (a) Ship user-facing guidance: a `docs/how-to/antivirus-exclusions.md`
   naming the data dir, model dir, and sidecar exes, linked from the troubleshooting help file
   (`SSOT/docs/help/troubleshooting.md`, which is auto-ingested). (b) Detection heuristic: a burst
   of `AccessDenied`/sharing-violation failures on the **data dir** (not corpus) within a window
   → readiness reason code `DATA_DIR_CONTENTION` with the remedy text. Wire through
   `LifecycleReasonCode.java` + `readinessNotice.ts`; run `check-readiness-reason-codes`.
6. **Scale + Unicode torture matrix** (tagged tests, not default CI): 1,000,000 zero-byte files
   (verifies queue watermarks and the 200k delete-detection cap's `delete_detection_unverified`
   signal), one 5 GB file (verifies `INPUT_TOO_LARGE` without reading it), depth-200 nesting,
   filenames with RTL marks, combining characters, NFD vs NFC pairs, emoji. `PathNormalizer`
   gains NFC normalization *of the comparison key only* (do not rewrite paths on disk).

## Acceptance criteria

- Unit/integration tests for each new reason code and for both walks; the matrix in item 6 runs
  under `-PincludeTortureTests=true` (mirror the soak flag pattern in
  `modules/system-tests/build.gradle.kts:30`) and is documented in `09-testing-strategy.md`.
- `03-knowledge-server.md:277-283` rewritten: the ledger contract lists every new reason code;
  the "deliberately deprioritized" sentence is gone or names a tracked item.
- `node scripts/governance/run.mjs --gate operation-surface --mode gate` and
  `check-readiness-reason-codes` green; `./gradlew.bat :modules:worker-services:test
  :modules:worker-core:test :modules:app-services:test`; ui-web gates for item 4.
- Live check: `ai`-less dev stack, ingest a folder containing a junction, an unreadable file
  (deny ACL), and a OneDrive-style placeholder fixture; `GET /api/diagnostics/ingestion/*` shows
  three distinct reasons; the FE surface shows them.

## Constraints

- Do not change pacing, the extraction pool, or sandbox launch (885).
- Skip policy for reparse points is decided above; do not re-litigate — if evidence says
  "follow by default" is right, write it in §Status and ask.
- Non-goals: OS process priority / I/O priority (lane 896), disk-full policy (896), per-file
  forget (891), non-Windows portability (owner decision).

## Status

(unstarted)
