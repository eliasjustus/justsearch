---
title: "369: Eval Backend LLM Support"
status: done
created: 2026-03-29
completed: 2026-03-30
---

# 369: Eval Backend LLM Support

## Problem

The eval backend (`runHeadlessEval`) has no supported way to start
with the LLM (Brain/llama-server) enabled. The Gradle task doesn't
forward `JUSTSEARCH_AI_AUTOSTART_ENABLED`, and even when manually
configured, the llama-server health check times out during model
loading (hardcoded 30s, insufficient for 9B+) — requiring a manual
`/api/inference/mode` API call to recover.

This was encountered during tempdoc 363 (Transparent Query
Enhancement) where every Track B/C validation cycle required
a multi-step manual ritual. The spike scripts worked around it
by calling llama-server directly on port 8080 — bypassing
JustSearch's inference pipeline entirely.

## What should work

```bash
# Single command: backend with LLM, ready to use
./gradlew.bat :modules:ui:runHeadlessEval -Pllm=true
```

Or via jseval:

```bash
python -m jseval run --start-backend --llm --dataset multihop ...
```

The backend should auto-detect the model in `models/`, find the
llama-server executable, start it with GPU acceleration, wait for
the model to finish loading (not a fixed 30s timeout), and report
ready only when the LLM is actually available.

## Implemented (commits on main)

### Gradle wiring (`-Pllm=true`)

- [x] Add `JUSTSEARCH_AI_AUTOSTART_ENABLED` and `JUSTSEARCH_AI_AUTOSTART_DISABLED`
      to `HEADLESS_AI_ENV_VARS` so they're forwarded when set in the caller's env
- [x] In `applyHeadlessEvalContract()`, when Gradle property `llm` is set to `true`:
  - Set env `JUSTSEARCH_AI_AUTOSTART_ENABLED=true` + sysprop `justsearch.ai.autostart.enabled=true`
  - Set health check timeout to 180s via new sysprop

### Configurable health check timeout

- [x] In `LlamaServerOps`, read `HEALTH_CHECK_TIMEOUT_MS` from system property
      `justsearch.inference.health_check_timeout_ms` with 30000 default (preserves
      existing behavior for production)
- [x] In `applyHeadlessEvalContract()` when `llm=true`, set this sysprop to 180000
      (3 min — sufficient for 9B models on modest hardware)
- [x] Add progress logging in `waitForServerHealth()`: log every 10s with elapsed time
      so the user knows the model is still loading (not hung)

### jseval `--llm` flag

- [x] Add `--llm` flag to `cmd_run` in `cli.py` (requires `--start-backend`)
- [x] When `--llm`, inject `JUSTSEARCH_AI_AUTOSTART_ENABLED=true` into `env_overrides`
- [x] Pass `-Pllm=true` to Gradle command when `llm=True`
- [x] Single monotonic deadline shared between index health + inference readiness
- [x] `_wait_for_inference()` polls `GET /api/inference/status` for `mode == "online"`
- [x] Actionable diagnostics on failure (offline: missing exe/DLLs; transitioning:
      model too large; process exit: crash with rc)
- [x] 5 unit tests for `_wait_for_inference` covering all branches
- [x] Add `llm.enabled` key to `run_config.py` `_ENV_MAP`

### Server exe auto-detection (Java)

- [x] Extend `InferenceConfig.findServerExecutable()` with dev-layout
      search paths (Tauri resources + source build) when `repo.root` is set
- [x] Remove interim Gradle auto-detection from `applyHeadlessEvalContract()`

## E2E validated

```
20:31:34  Starting backend... -Pllm=true
20:31:44  Backend healthy (10s)
20:31:44  LLM inference available (instant — model loaded during startup)
20:31:46  Ingesting 5184 docs...
20:32:20  Done: 5184 docs in 36s (144 docs/s), GPU active
20:32:21  Backend stopped
```

## Remaining: move server exe detection to Java

### Why the Gradle auto-detection is wrong

The Gradle auto-detection (interim, above) hardcodes the Tauri shell
module's internal resource path. Problems:

- **Cross-module coupling**: eval pipeline depends on desktop
  packaging layout. Tauri upgrade or restructure breaks it silently.
- **Duplicate logic**: Java already has `InferenceConfig.findServerExecutable()`
  with proper multi-location scanning, CUDA variant selection, DLL
  checks, subdirectory scanning, and 9 existing tests. The Gradle
  script duplicates a subset of that in a different language.
- **Windows-only**: checks for `llama-server.exe`, won't work on
  Linux/macOS.
- **Misses `third_party/`**: developers who build llama.cpp from
  source won't get auto-detection.

### How comparable projects handle this

Research of Ollama, LocalAI, Jan.ai, LM Studio, llama-cpp-python,
and HuggingFace TGI shows a clear pattern hierarchy:

| Pattern | Used By | Fit for JustSearch |
|---------|---------|-------------------|
| Spawn-and-probe (run binary, ask what GPUs it sees) | Ollama | Overkill — one binary, one GPU variant |
| Capabilities map + meta-backends | LocalAI | Overkill — no backend gallery |
| Versioned directory layout (filesystem IS manifest) | Jan, nvm | Overkill — no multi-version coexistence |
| **Exe-relative path + dev fallbacks** | **Ollama (primary)** | **Best fit** |
| PATH lookup | TGI | Too fragile for desktop app |
| Build-time selection | llama-cpp-python | Not applicable — runtime detection needed |

**Ollama's pattern** is the closest match: it resolves `LibOllamaPath`
once at init by probing a priority list relative to the executable:
1. `{exe_dir}/lib/ollama` (production)
2. `{exe_dir}/build/lib/ollama` (dev fallback)
3. `{cwd}/build/lib/ollama` (dev fallback)

Same algorithm, different search roots. No manifest, no duplication.
JustSearch should do the same thing: extend the existing Java search
with dev-layout roots, not add a parallel detection in Gradle.

### Correct fix

Extend `InferenceConfig.findServerExecutable()` to search dev-layout
paths when `justsearch.repo.root` is set. The existing CUDA variant
selection, DLL checks, and platform logic all apply automatically.

**Search order (existing + new):**

```
1. JUSTSEARCH_SERVER_EXE env var override              (existing)
2. {baseDir}/native-bin/llama-server/                   (existing — production)
3. {repoRoot}/modules/shell/.../native-bin/llama-server/ (NEW — dev bundled)
4. {repoRoot}/third_party/llama.cpp/build/bin/Release/   (NEW — source build)
5. Sorted subdirectory scan                             (existing)
6. Canonical fallback path                              (existing)
```

Steps 3–4 only activate when `justsearch.repo.root` is set (which
`applyHeadlessEvalContract()` already provides). In production
desktop installs, `repo.root` is unset — zero risk to production
detection. The existing `findCudaVariant()` call applies to step 3
automatically (it checks `variants/cuda12/` under the found dir).

**Implementation (done):**

- [x] In `findServerExecutable()`, after the repo-root baseline search
      fails, probe `{repoRoot}/modules/shell/src-tauri/resources/headless/`
      using `findExistingServerExecutable()` (CUDA variant selection applies)
- [x] Add `findSourceBuildExecutable()` helper for
      `{repoRoot}/third_party/llama.cpp/build/bin/{Release,}/`
- [x] Remove Gradle auto-detection block from `applyHeadlessEvalContract()`
- [x] 3 new tests in `InferenceConfigServerExeTest.java`: dev-layout
      discovery, CUDA variant preference in dev layout, source-build fallback
- [x] Docs already correct (auto-detection note in place, override documented)

**Actual diff:** +30 Java (detection), +60 Java (3 tests), -21 Kotlin
(removed Gradle detection). 3 files changed.

## Files changed

| File | Change |
|------|--------|
| `modules/ui/build.gradle.kts` | Autostart env vars in `HEADLESS_AI_ENV_VARS`; `-Pllm=true` handling |
| `modules/app-inference/.../InferenceConfig.java` | Dev-layout + source-build search paths in `findServerExecutable()` |
| `modules/app-inference/.../InferenceConfigServerExeTest.java` | 3 new tests for dev-layout and source-build discovery |
| `modules/app-inference/.../LlamaServerOps.java` | Configurable health timeout via sysprop; progress logging |
| `scripts/jseval/jseval/cli.py` | `--llm` flag with validation |
| `scripts/jseval/jseval/backend.py` | `llm` param, `-Pllm=true` passthrough, single-deadline inference readiness polling with diagnostics |
| `scripts/jseval/jseval/run_config.py` | `llm.enabled` env map entry |
| `scripts/jseval/tests/test_backend.py` | 5 tests for `_wait_for_inference` |
| `docs/reference/jseval-pipeline-reference.md` | `--llm` flag in examples, capabilities, flags table |
| `.claude/skills/jseval/SKILL.md` | Synced from canonical doc |
