"""Tempdoc 412 follow-up Path C — startup-failure smoke gate. Tempdoc 518 P3 update.

Empirically verifies typed inference failure metrics fire under a real cold-start
failure. Approach: point JUSTSEARCH_LLM_MODEL_PATH at a file that does not exist,
then trigger switchToOnlineMode via admin reload. config.validate() throws
INVALID_CONFIG → ConfigFailure → per-category routing in
InferenceTelemetryAdapter.onStartupFailure → inference.config.apply_failure_total
fires with code=invalid_config (NOT synthesized inference.startup.failure_total
with code=unknown, which was the pre-518 Bug D shape).

Tempdoc 518 P3 resolved observations.md #99 by adopting option (c): per-category
metric routing. The adapter pattern-matches on InferenceFailure sub-record:
- StartupFailure → inference.startup.failure_total{code=StartupCode.wireValue}
- TransitionFailure → inference.transition.failure_total{code=TransitionCode.wireValue}
- ConfigFailure → inference.config.apply_failure_total{code=ConfigCode.wireValue}
- HealthFailure → inference.health.failure_total{code=HealthCode.wireValue}

This script accepts ANY of those categorical metrics firing — the previous
"code=unknown synthesis" shape is no longer expected and would indicate
a regression.
"""

import json
import sys
import urllib.request
import urllib.error
from pathlib import Path

REPO_ROOT = Path(r"F:/JustSearch")
sys.path.insert(0, str(REPO_ROOT / "scripts" / "jseval"))
from jseval.backend import start_backend, stop_backend  # noqa: E402

DATA_DIR = REPO_ROOT / "tmp" / "diagnostics" / "inference" / "startup-failure-data"
PORT = 33223
BOGUS_MODEL = str(REPO_ROOT / "models" / "__definitely_not_a_real_model__.gguf")

print("=" * 60)
print("INFERENCE SMOKE GATE - startup failure (bogus model path)")
print("=" * 60)
print(f"Repo root:   {REPO_ROOT}")
print(f"Bogus model: {BOGUS_MODEL}")
print(f"Port:        {PORT}")
print()

env_overrides = {
    "JUSTSEARCH_SERVER_EXE": str(
        REPO_ROOT / "modules" / "ui" / "native-bin" / "llama-server" / "variants" / "cuda12" / "llama-server.exe"
    ),
    "JUSTSEARCH_MODELS_DIR": str(REPO_ROOT / "models"),
    "JUSTSEARCH_LLM_MODEL_PATH": BOGUS_MODEL,
}

# The backend may not even reach 'healthy' state if the bootstrap inference path
# tries cold-start with a bad model. Use a short health timeout — we don't need a
# healthy backend, we need a process that survived long enough to emit the failure.
try:
    info = start_backend(
        repo_root=REPO_ROOT,
        data_dir=DATA_DIR,
        port=PORT,
        clean=True,
        llm=True,
        health_timeout_sec=120,
        env_overrides=env_overrides,
    )
    print(f"Backend up: pid={info.proc.pid}")
    backend_healthy = True
except Exception as e:
    print(f"Backend did not become healthy (expected for this scenario): {e}")
    backend_healthy = False
    info = None

# If healthy, attempt admin reload as a fallback trigger; either way, stop the
# backend afterwards so the final flush captures whatever failure events fired.
try:
    if backend_healthy and info is not None:
        try:
            print("\nPOST /api/admin/inference/reload {reason: startup_fail_smoke}")
            req = urllib.request.Request(
                f"http://127.0.0.1:{PORT}/api/admin/inference/reload",
                data=json.dumps({"reason": "startup_fail_smoke"}).encode(),
                headers={"Content-Type": "application/json"},
                method="POST",
            )
            try:
                with urllib.request.urlopen(req, timeout=120) as resp:
                    print(f"  status: {resp.status}")
                    print(f"  body:   {resp.read().decode()}")
            except urllib.error.HTTPError as e:
                print(f"  status: {e.code} (error)")
                print(f"  body:   {e.read().decode()}")
        except Exception as ex:
            print(f"  reload threw: {ex}")
finally:
    if info is not None:
        print("\nStopping backend (triggers final flush)...")
        stop_backend(info.proc)

# Locate metrics file: if start_backend failed, info is None — try the data_dir directly.
metrics_path = DATA_DIR / "telemetry" / "metrics.ndjson"
print(f"\nReading metrics from: {metrics_path}")
if not metrics_path.exists():
    print(f"FAIL: metrics file missing at {metrics_path}")
    sys.exit(1)

content = metrics_path.read_text(encoding="utf-8")
inference_lines = [ln for ln in content.splitlines() if '"name":"inference.' in ln]
failure_lines = [
    ln
    for ln in inference_lines
    if '"name":"inference.startup.failure_total"' in ln
    or '"name":"inference.transition.failure_total"' in ln
    or '"name":"inference.config.apply_failure_total"' in ln
    or '"name":"inference.health.failure_total"' in ln
]
# Tempdoc 518 P3: assert NO `code=unknown` synthesis remains on the startup metric.
# The pre-518 Bug D path funnelled non-StartupFailure sub-records through
# StartupCode.UNKNOWN; per-category routing eliminates that synthesis.
unknown_synth_lines = [
    ln
    for ln in inference_lines
    if '"name":"inference.startup.failure_total"' in ln and '"code":"unknown"' in ln
]

print(f"\ninference.* total lines:    {len(inference_lines)}")
print(f"inference.*failure* lines: {len(failure_lines)}")
print()
for ln in failure_lines:
    print(ln)

print()
print("=" * 60)
print("VERDICT")
print("=" * 60)
if unknown_synth_lines:
    print(
        "[FAIL] inference.startup.failure_total fired with code=unknown — "
        "the pre-518 Bug D synthesis path regressed. Tempdoc 518 P3 expected per-category routing."
    )
    for ln in unknown_synth_lines:
        print(ln)
    sys.exit(3)
elif failure_lines:
    print(
        "[OK] typed failure metric fired under bogus-model-path scenario "
        "(no code=unknown synthesis — tempdoc 518 P3 per-category routing honored)"
    )
    sys.exit(0)
else:
    print("[FAIL] no failure metric fired — startup-failure path is not honest")
    print()
    print("All inference.* lines for diagnosis:")
    for ln in inference_lines:
        print(ln)
    sys.exit(2)
