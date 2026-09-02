---
title: "Inference runtime residuals: RAM-derived Worker heap, device-lost handling on the ORT run path, VRAM re-plan on activation, llama-server slot pinning; speculative decoding as a research note"
type: tempdocs
status: CHARTERED (2026-09-02) — not started
created: 2026-09-02
updated: 2026-09-02
lane: 887 L19
model: opus (takeover)
parent: 887-improvement-landscape-register
coordination: "⇢ founder lane C (885) owns pacing; ⇢ lane A (883, ADR-0047) owns the context-window ladder — extend, do not re-derive. Battery-aware indexing pacing is deferred until 885 lands (an energy-reduced input already defers backfill via `LoopPacingPolicy.shouldRunBackfill`; the user-facing pause-on-battery lever is `user-indexing-policy` (907), corrected 2026-09-02)."
related:
  - 883-decision-review-lane-a-config-and-context-budget   # ADR-0047 ladder; memory-plan flags -np/-kvu/-fa/-fit
  - 737-ai-runtime-lifecycle-model     # spec/status reconcile; where activation happens
  - 630-os-sleep-resume-robustness     # EnergyState / PowerStatusView
  - 311 / 349 / 352 (ORT runtime, ort-common)
  - 841-agent-prompt-cache-efficiency  # prompt cache on the agent path
  - 845-rag-budget-and-prompt-scope
---

# 898 — Inference runtime residuals

## Briefing for the agent picking this up

Fresh start. Read this file and 887 Appendix A1 (§1.3, 1.5, 1.7). Load `/inference-runtime`
(register; update it before closing) and `/dev-stack`. Work in a worktree. Code homes:
`modules/app-inference/.../LlamaServerOps.java` (argv, VRAM plan), `OnlineModeOps.java`
(requests), `modules/ort-common/.../NativeSessionHandle.java` (sessions),
`modules/app-services/.../worker/WorkerSpawner.java` (heap). Verify with a live model
(`ai_activate`, `use-every-verification-tier`), not just unit tests. Four PRs.

## Thesis

Only VRAM drives hardware adaptation. Worker heap is a fixed configured constant
(`WorkerSpawner.java:461-464` `-Xms=-Xmx=config.workerHeapSize()`); ORT recovery catches
session *creation* failures but nothing on `session.run` (`NativeSessionHandle.java:658-666`);
free VRAM is read once per launch (`LlamaServerOps.java:253-256`); the app never pins a
conversation to a llama-server slot or sets `cache_prompt`, so with `-np > 1` prefix-cache reuse
is luck (zero `id_slot|cache_prompt|--slot-save-path` hits); speculative decoding is absent.

## Decisions made for you

- **Heap:** default Worker heap = clamp(physical RAM × 0.25, 1 GiB, 6 GiB) unless
  `justsearch.worker.heap` is set explicitly (explicit wins, unchanged). Source physical RAM via
  `OperatingSystemMXBean.getTotalMemorySize()` (already used in `MachineFingerprint.java:50`).
  Keep `-Xms=-Xmx`. Surface the derived value in `/api/debug/state`.
- **Device-lost:** wrap `session.run` failures; classify `CUDA_ERROR_*`/device-lost/OOM via the
  existing `FailureCause.classifyGpuInitException` family (extend it); on device-lost →
  close + recreate the session once, then fall to the CPU session for the 60 s retry window
  that already exists (`DEFAULT_GPU_RETRY_INTERVAL_MS`). Never swallow: emit the inference
  transition log entry (`NdjsonInferenceTransitionLog`).
- **VRAM re-plan:** re-read free VRAM at every *activation* (spec → online transition in the
  reconciler), not continuously; if it dropped below the rung's need, step down per ADR-0047's
  ladder (existing `relaunchAtLowerContextRung`). No new polling.
- **Slot pinning:** with `-np <slots>`, assign `id_slot` per conversation (LRU over slots) and
  send `cache_prompt: true` explicitly; measure prefill tokens/latency on turn 2+ of a 5-turn
  RAG conversation before/after with `jseval`. `--slot-save-path` is out (disk churn).
- **Speculative decoding:** research note only (§N): candidate draft models per chat profile,
  VRAM cost, expected speedup from llama.cpp reports, and what the model pack (840) would need.
  No code.

## Scope

1. Heap derivation + debug-state field + test (fake RAM sizes) + `06-configuration-ssot.md` note.
2. Device-lost classification + recreate-once + transition log + a fault-injection test using
   the existing chaos hooks in `ort-common` tests.
3. Activation-time VRAM re-plan in the reconciler path 737 defined; test with a stubbed NVML.
4. Slot pinning + `cache_prompt` + measurement table in §M (compact and standard profiles).
5. §N speculative decoding research note; update `docs/reference/inference-runtime-register.md`.

## Acceptance criteria

- `./gradlew.bat :modules:app-inference:test :modules:ort-common:test :modules:app-services:test`;
  `build -x test`; `check-runtime-manifest-closure` if debug-state shape changes.
- Live: `ai_activate {chatProfile:"standard"}`; a 5-turn conversation shows turn-2+ prompt-eval
  tokens near zero in llama-server's `/metrics`; §M has before/after.
- `/inference-runtime` register updated (heap rule, device-lost path, slot policy).

## Constraints

- Do not touch the ladder's rung values or the budget fractions (883 / ADR-0047 probes will
  fail if you do — run `--gate adr-coverage`).
- Do not add battery inputs to indexing pacing (885); the VDU consumer stays as is.
- Non-goals: non-NVIDIA backends (owner re-read, 887 §S), ARM64, thermal sensing.

## Status

(unstarted)
