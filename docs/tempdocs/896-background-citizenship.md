---
title: "Background citizenship: OS process priority for the Worker and extraction children, job-object resource limits, and one low-disk policy"
type: tempdocs
status: CHARTERED (2026-09-02) — not started; BLOCKED-SOFT on 885's live arms landing (read its §Status first)
created: 2026-09-02
updated: 2026-09-02
lane: 887 L7
model: opus (takeover)
parent: 887-improvement-landscape-register
coordination: "⇢ founder lane C (885, IN PROGRESS): 885 owns application-level pacing (foreground-load gauge, duty cycle, extraction pool). This lane adds OS-level levers UNDER it and must not modify pacing code. Start only after 885's chunk-3 live arms are recorded, and re-measure with the same jseval arms."
related:
  - 885-decision-review-lane-c-runtime-lifecycle-and-isolation   # §45 decision 3, §511 baseline table, §555 duty counter
  - 410-adversarial-ingestion-resilience §5   # extraction sandbox as a failure domain
  - 628-index-durability-corruption-recovery  # index-side recovery (disk-full sibling)
  - 824-f1-install-reliability-design         # install-time free-space preflight
---

# 896 — Background citizenship

## Briefing for the agent picking this up

Fresh start. Read this file, 887 Appendix A2 (§2.3, 2.5, 2.8), then tempdoc 885 in full
(long; use offset/limit) — especially decision 3 (`:45`), the baseline table (`:511`), and its
§Status for whether the post-duty-cycle arms have been run. Load `/jseval` and `/dev-stack`
(shared stack; lease). Work in a worktree. Win32 calls go through the existing FFM pattern in
`modules/app-util/.../WindowsJobObject.java` (no JNI). Three PRs: priority, job limits, disk
policy. Every change is measured with the same `jseval run --search-load-qpm` arms 885 used so
the founder can read before/after on one table.

## Thesis

Nothing sets an OS scheduling class: zero `SetPriorityClass|PROCESS_MODE_BACKGROUND|BELOW_NORMAL`
hits; the Worker job object sets only `KILL_ON_JOB_CLOSE` while its memory/process-limit fields
are declared and never written (`WindowsJobObject.java:74-77,172-175`); the extraction child has
`-Xmx` + a parent-PID watchdog only. Disk-full is three uncoordinated thresholds (Lucene string
sniff → `INDEX_DISK_FULL` PERMANENT, SQLite fail-open probe, telemetry two-tier) with no resume.

## Decisions made for you

- **Priority:** Worker process → `BELOW_NORMAL_PRIORITY_CLASS` at spawn (WorkerSpawner) — always,
  not dynamically. Extraction sandbox children → `BELOW_NORMAL` + `PROCESS_MODE_BACKGROUND_BEGIN`
  (lowest CPU + I/O priority; they are throwaway and CPU-bound). **Not** background mode for the
  Worker itself: it also serves search RPCs, and background I/O priority can starve them. Head
  stays normal. Off-Windows: no-op.
- **Job limits:** extraction children run inside their own job object with
  `JOB_OBJECT_LIMIT_PROCESS_MEMORY` = policy heap + 512 MiB and `ActiveProcessLimit` = 1 (a parser
  must not spawn). `worker-services` gets the job-object dependency 885 avoided — record the
  reason 885 avoided it and why it is acceptable now (the FFM class is dependency-free).
- **Low-disk:** one `DiskPressure` authority in the Worker (it owns the writes), sampled per
  commit and per minute: `warn` at max(2 GiB, 5%), `critical` at 512 MiB on the data-dir volume.
  Critical → stop *admitting* new ingest jobs (queue accepts, loop pauses), surface readiness
  reason `DISK_CRITICAL` with the free-bytes figure, refuse model download (already), and
  auto-resume when free space returns above warn. `INDEX_DISK_FULL` becomes recoverable
  (`ErrorClass.TRANSIENT` with the resume path named). Telemetry keeps its own thresholds (it
  must never block on the Worker) but reports the same figure.

## Scope

1. Priority classes via FFM (`SetPriorityClass` on the spawned handle; children via
   `CREATE_*`-independent post-spawn call) + a `justsearch.worker.priority` override (`normal`
   for benchmarks). Measure: 885's arms (b) and (c) before/after — search p50/p95 (**add p99**)
   and indexing throughput; acceptance is search p95 ≤ baseline and throughput regression ≤ 10%.
2. Job object for extraction children + chaos test extending
   `ExtractionSandboxChaosTest` (child allocates past the limit → `SANDBOX_FAILED` typed outcome,
   no Worker impact).
3. `DiskPressure` + readiness reason code + resume; tests fake the `FileStore` figures; live check
   by filling a small VHD to critical and back.
4. Update `03-knowledge-server.md` (duty cycle section) and `02-process-coordination.md` with the
   priority and disk policy (load `/docs-maintenance`); `check-readiness-reason-codes` for the
   new code.

## Acceptance criteria

- §Status holds the before/after table with 885's exact arm definitions and run ids.
- `./gradlew.bat :modules:app-util:test :modules:worker-services:test :modules:app-services:test`
  green; `build -x test`; `check-readiness-reason-codes` green; `--gate operation-surface` if any
  operation surface changed.
- Live: `quick_health { detail: "full" }` shows the Worker at below-normal; Task Manager confirms.

## Constraints

- Never edit `ForegroundLoad`, `IndexingPacing`, the extraction pool, or the MMF activity byte.
- If 885's arms are not yet recorded, run *only* the baseline arms yourself and stop — do not
  produce an "after" against a moving base.
- Non-goals: user-facing pause/schedule settings (fable lane, waits on 885), AppContainer /
  restricted tokens (research; note in §Status if the job object proves insufficient).

## Status

(unstarted)
