---
title: GPU Env Var Propagation for runHeadless
status: done
created: 2026-03-26
---

# 347 - GPU Env Var Propagation for runHeadless

## Problem

ONNX models (embedding, SPLADE, NER) load on CPU and consume
up to 25 GB RAM. GPU is available (12 GB VRAM, driver 595.79)
but the GPU enable flags don't reach the Worker process.

Setting JUSTSEARCH_EMBED_GPU_ENABLED=true in the shell has no
effect. The /api/status endpoint reports:
  embedOrtCuda: configured=false, attempted=false, available=false
  failureReason: "GPU not configured"

## Root Cause

The runHeadless Gradle task (modules/ui/build.gradle.kts:1785)
forwards env vars to the Head JVM via the environment() call.
The Head then spawns the Worker as a separate process.

The HEADLESS_AI_ENV_VARS list (line 1618) was missing the GPU
per-model flags. A fix was applied (line 1811-1814) to also
forward HEADLESS_GPU_ENV_VARS, but it still doesn't work because:

1. Gradle configuration cache: System.getenv() at configuration
   time captures env vars when the Gradle daemon starts, not
   when the task runs. Even with --no-daemon, the configuration
   cache may reuse stale env snapshots.

2. Shell env var persistence: each Bash tool call in Claude Code
   gets a fresh shell. `export VAR=value` doesn't persist across
   calls. Inline `VAR=value ./gradlew.bat` should work but
   doesn't because of issue #1.

3. The auto-detect path (line 1819-1824) detects CUDA DLLs and
   auto-enables SPLADE GPU, but does NOT auto-enable embed GPU
   or NER GPU. Only SPLADE gets auto-enabled on CUDA detection.

## What Was Tried

- `export JUSTSEARCH_EMBED_GPU_ENABLED=true` before gradlew: no effect (daemon caches env)
- `JUSTSEARCH_EMBED_GPU_ENABLED=true ./gradlew.bat`: no effect (same reason)
- `./gradlew.bat --no-daemon` with inline env vars: no effect (config cache)
- `-Djustsearch.embed.gpu.enabled=true` on gradlew: no effect (sysprop goes to Gradle JVM, not app JVM)
- Added HEADLESS_GPU_ENV_VARS forwarding to runHeadless (line 1811-1814): code correct but env vars aren't in the Gradle process to begin with

## Impact

Without GPU, the embedding model loads on CPU and allocates
~25 GB of RAM during embedding backfill and query-time vector
encoding. This makes the backend unstable and causes OOM errors
during eval runs.

The eval (tempdoc 346) cannot produce reliable condition C
results because the backend crashes under memory pressure.

## Potential Fixes

1. Auto-enable embed GPU when CUDA DLLs are detected (like
   SPLADE auto-enable at line 1822-1824). Add similar blocks
   for EMBED and NER.

2. Use a config file (YAML or properties) instead of env vars
   for GPU settings. The jseval YAML config approach (eval-run.yaml)
   already supports this for runHeadlessEval.

3. Set the flags in gradle.properties or local.properties which
   Gradle reads before configuration.

4. Use the runHeadlessEval task which has applyHeadlessEvalContract()
   that properly forwards HEADLESS_GPU_ENV_VARS.

## Related

- Tempdoc 337: Unified GPU policy
- Tempdoc 329: Env var / sysprop bridge removal
- Tempdoc 346: Agent retrieval eval (blocked by this issue)
- CLAUDE.md: "Windows env vars unreliable — pass config via -D"
- modules/ui/build.gradle.kts lines 1618-1650, 1785-1825

---

## Staleness review (2026-05-18)

Marked `done` after per-doc triage in the Shape-2 staleness audit.

GPU env-var propagation issue for runHeadless. CLAUDE.md captures the lesson ("Windows env vars unreliable — pass config via -D"); the immediate issue is documented as guidance rather than open work.

Body content preserved as design history per the README's promotion
policy. If this work should resume, open a new tempdoc citing this one
by title.

