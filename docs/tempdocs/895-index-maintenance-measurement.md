---
title: "Index maintenance, measurement half: bytes-per-document, cold-open latency, idle footprint, WAL bounds, int8 A/B — evidence for the generation-GC and quantization decisions"
type: tempdocs
status: CHARTERED (2026-09-02) — not started
created: 2026-09-02
updated: 2026-09-02
lane: 887 L6
model: opus (takeover)
parent: 887-improvement-landscape-register
coordination: "⇢ founder lane D (index identity + migration, planned after 885): generation GC policy is D's; this lane MEASURES and bounds WAL only"
related:
  - 640-engine-performance-budget-latency-throughput-footprint  # excluded index_size_bytes from the ratchet (merge non-determinism)
  - 647-engine-performance-attribution-and-budget-allocation
  - 628-index-durability-corruption-recovery   # generation lifecycle for rebuilds
  - 713-dense-authority-consolidation          # kept both dense vectors (footprint doubled)
  - 278-decision-log / 278-research-archive    # the only idle-CPU numbers (2026-03)
  - 302-startup-performance-phase-2            # JVM startup numbers; not index warmup
  - 885 (decision review lane C) item 19       # NRT/commit cadence — do not touch
---

# 895 — Index maintenance, measurement half

## Briefing for the agent picking this up

Fresh start. Read this file and 887 Appendix A4 (§4.1-4.3, 4.7). Load `/jseval` and `/dev-stack`
before measuring (shared dev stack: `quick_health` first; declare `leaseDurationSec` for
campaigns). Load `/search-quality` before the int8 A/B — the relevance ratchet and the register
must be updated if you change any default (you will not; you report). Work in a worktree. One
small code PR (WAL bounds) and one measurement report (this tempdoc §M) that the founder uses
to decide int8-default and to brief lane D on GC policy. Do not implement generation GC.

## Thesis

Old `indices/<gen>/` directories are reclaimed only by a manual `core.index-gc`
(`IndexGenerationManager.java:752` "not currently invoked by default"); the SQLite WAL has no
checkpoint/size bound (`SqliteJobQueue.java:249-254` sets WAL + `auto_vacuum=2` only); int8 HNSW
is built and default-off (`JustSearchCodec.java:39-44`) with the only bytes/doc figure being
arithmetic (`18-adapters-lucene-deep-dive.md:129`); Worker-start→first-search on a large index
is unmeasured; the only idle-footprint numbers are a March spot check on one machine.

## Scope

1. **WAL bounds (code, small).** Set `PRAGMA wal_autocheckpoint` (default 1000 pages is fine;
   make it explicit) and `PRAGMA journal_size_limit` (decision: 64 MiB) at open; add a metric for
   WAL file size to the existing sentinel cleanup path (`KnowledgeServer.java:1681-1735`). Test:
   WAL never exceeds the limit + one checkpoint across a 200k-job churn.
2. **Bytes per document.** Using jseval's fixed corpora (legal-1k/10k, email-1k/10k — the same
   strata 762 used), measure index dir size / doc count after a clean ingest + final commit, split
   by Lucene file family (`.vec`/`.vex` dense, `_ps.fdt` stored, SPLADE postings, doc values) —
   a script under `scripts/jseval/jseval/commands/` (`index-footprint`). Report float32 vs int8
   (`index.vector.quantization.enabled=true`) on the same corpus. Three runs each; 640 excluded
   this metric for CV 11-62% — report the CV and whether a `forceMerge(1)`-then-measure protocol
   makes it stable enough to ratchet.
3. **int8 quality A/B.** Same corpora, relevance eval per `/search-quality` with quantization on
   vs off; report nDCG/recall deltas against the ratchet's noise band. This is the founder's
   input for flipping the default; note that blue/green re-embed (doc 11) makes the flip
   migratable — say what a migration would cost (time on legal-10k).
4. **Cold-open latency.** Worker process start → first successful search on legal-10k, cold OS
   cache (drop via `RAMMap`-equivalent or reboot; state which) vs warm, with and without the
   encoder ONNX session pre-loaded. Attribute: JVM/AOT, index open, HNSW graph residency, first
   ONNX load. If a warmup step (touch `.vex`, one dummy query at open) buys >30%, propose it in
   §M as a lane-D or 885 item — do not implement here.
5. **Idle footprint.** 10-minute idle window, three states (Brain offline; Brain online idle;
   Brain online + VDU offline trigger armed): Head/Worker/llama-server CPU%, RSS, GPU utilization
   and VRAM (`nvidia-smi --query-gpu`), disk writes, wakeups (`ETW`-free proxy: context switches
   via `Get-Counter`). Compare to 278's 2026-03 numbers. Propose a budget in §M.

## Acceptance criteria

- Item 1: unit test + `./gradlew.bat :modules:indexer-worker:test`; `build -x test` green.
- Items 2-5: §M holds tables with machine fingerprint (`MachineFingerprint`), corpus ids,
  run ids, and raw artifact paths under `scripts/jseval/runs/`; every number has n≥3 or is
  marked single-run. No number in prose without its table.
- `/search-quality` register updated only if a baseline was touched (it should not be).

## Constraints

- Do not implement generation GC, forced merges, or codec default changes — measure and report.
- Do not touch commit cadence / NRT (885 item 19) or pacing.
- Dev-stack contention rules from `/dev-stack` apply; no fire-and-forget campaigns.
- Non-goals: search QoS under indexing (885 owns p50/p95; p99 capture is a one-line ask to 885).

## Status

(unstarted)
