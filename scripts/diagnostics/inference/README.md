# Inference observability fault-injection diagnostics

Empirical end-to-end smoke gates for `InferenceLifecycleManager`'s
catalog-backed metric pipeline. Each script cold-starts a backend via
`jseval.backend.start_backend`, drives a specific scenario, then parses
`telemetry/metrics.ndjson` to assert the right typed `inference.*` event
fired with non-degenerate tags.

These were written for tempdoc 412 Path C (2026-04-27) and merged via
`fda5add2e`. They surfaced two bugs that unit tests missed:

- **Bug E** — admin-reload `reason` wiring (fixed in
  `OnlineAiServiceImpl.applyRuntimeOverridesAdmin` + `OnlineAiRuntimeControl`).
- **Bug F** — silent process-death in metrics (fixed by adding
  `events.onHealthFailure(PROCESS_DIED, ...)` to
  `LlamaServerOps.handleServerCrash`).

Both bug fixes are now also locked in by checked-in regression tests
(`OnlineAiServiceImplTest.applyRuntimeOverridesAdmin_routesAdminTriggeredReasonToManager`,
`LlamaServerOpsCrashTelemetryTest.bugF_processDeath_emitsTypedHealthFailure`).
The scripts here remain useful when a future change to the inference
runtime needs a quick end-to-end sanity check that the catalog still
fires honestly under real conditions.

## Scripts

| Script | What it injects | Asserts |
|---|---|---|
| `happy_path.py` | Cold start with valid model + admin reload | `inference.transition.total{from_phase=OFFLINE, to_phase=ONLINE, reason=user_switch}` + non-zero `transition.duration_ms` bucket |
| `startup_failure.py` | `JUSTSEARCH_LLM_MODEL_PATH` → nonexistent file | `inference.startup.failure_total` fires with synthesized `code=unknown` (carries `[invalid_config]` in detail) |
| `health_failure.py` | `taskkill /F /IM llama-server.exe` mid-flight | `inference.health.failure_total{severity=restart_triggered, code=process_died}` fires |
| `config_failure.py` | `POST /api/settings/v2` with bogus `llmModelPath` then `POST /api/inference/reload` | Currently blocked by eval-mode `SETTINGS_READ_ONLY` protection; equivalent path exercised by `bugD_nullConfigEmitsConfigApplyFailure` unit test |

## Running

```bash
# From repo root
python scripts/diagnostics/inference/happy_path.py
python scripts/diagnostics/inference/startup_failure.py
python scripts/diagnostics/inference/health_failure.py
```

Each script is self-contained: cold-starts its own backend on a unique
port (33222–33225), uses a per-script data directory under
`tmp/diagnostics/inference/<script>-data`, and stops the backend on exit.
Exit code: `0` PASS / non-zero FAIL with a diagnostic dump of all
`inference.*` lines from the run.

## Prerequisites

- llama-server binary at `modules/ui/native-bin/llama-server/variants/cuda12/llama-server.exe`
- A GGUF model in `models/` (`Qwen_Qwen3.5-9B-Q4_K_M.gguf` is the default)
- jseval available at `scripts/jseval/`

When run from a worktree, set `env_overrides` in the script to point at
the canonical repo's binary + models dir (the worktree itself does not
ship llama-server). The shipped versions assume execution from the
canonical repo root `F:\JustSearch`.
