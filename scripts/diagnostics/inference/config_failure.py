"""Tempdoc 412 follow-up Path C — config-apply-failure smoke gate.

Empirically verifies inference.config.apply_failure_total fires when
applyConfig validation fails. Approach:
1. Cold-start backend healthy with valid model
2. POST /api/settings/v2 with bogus llmModelPath
3. POST /api/inference/reload (reads settings + applyRuntimeOverrides)
4. config.validate() throws → mapFailure(INVALID_CONFIG) →
   recordAndEmitFailure(configApplyContext=true) → onConfigApplyFailure fires
5. Stop & verify
"""

import json
import sys
import time
import urllib.request
import urllib.error
from pathlib import Path

REPO_ROOT = Path(r"F:/JustSearch")
sys.path.insert(0, str(REPO_ROOT / "scripts" / "jseval"))
from jseval.backend import start_backend, stop_backend  # noqa: E402

DATA_DIR = REPO_ROOT / "tmp" / "diagnostics" / "inference" / "config-failure-data"
PORT = 33225
BOGUS_MODEL = str(REPO_ROOT / "models" / "__definitely_not_a_real_model__.gguf")

print("=" * 60)
print("INFERENCE SMOKE GATE - config-apply failure (bogus settings)")
print("=" * 60)
print("Note: blocked by SETTINGS_READ_ONLY in eval mode; the equivalent path")
print("is exercised by InferenceLifecycleManagerIdentityTest#bugD_nullConfig...")
print()

env_overrides = {
    "JUSTSEARCH_SERVER_EXE": str(
        REPO_ROOT / "modules" / "ui" / "native-bin" / "llama-server" / "variants" / "cuda12" / "llama-server.exe"
    ),
    "JUSTSEARCH_MODELS_DIR": str(REPO_ROOT / "models"),
    "JUSTSEARCH_AI_ENABLED": "true",
    "JUSTSEARCH_LLM_MODEL_PATH": str(REPO_ROOT / "models" / "Qwen_Qwen3.5-9B-Q4_K_M.gguf"),
}

info = start_backend(
    repo_root=REPO_ROOT,
    data_dir=DATA_DIR,
    port=PORT,
    clean=True,
    llm=True,
    health_timeout_sec=180,
    env_overrides=env_overrides,
)
print(f"Backend up: pid={info.proc.pid}")

try:
    # Verify online via /api/inference/status (the reliable surface).
    req = urllib.request.Request(f"http://127.0.0.1:{PORT}/api/inference/status")
    with urllib.request.urlopen(req, timeout=10) as resp:
        body = json.loads(resp.read().decode())
        print(f"  /api/inference/status mode={body.get('mode')}")

    # Step 1: POST settings v2 with a bogus model path.
    print("\nPOST /api/settings/v2 with bogus llmModelPath")
    settings_body = {
        "llm": {
            "modelPath": BOGUS_MODEL,
        }
    }
    req = urllib.request.Request(
        f"http://127.0.0.1:{PORT}/api/settings/v2",
        data=json.dumps(settings_body).encode(),
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            print(f"  status: {resp.status}")
            print(f"  body:   {resp.read().decode()[:400]}")
    except urllib.error.HTTPError as e:
        print(f"  status: {e.code} (error)")
        print(f"  body:   {e.read().decode()[:400]}")

    # Step 2: POST /api/inference/reload — should fail validation with bogus path.
    print("\nPOST /api/inference/reload (expect 500 from validation failure)")
    req = urllib.request.Request(
        f"http://127.0.0.1:{PORT}/api/inference/reload",
        data=b"{}",
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    try:
        with urllib.request.urlopen(req, timeout=120) as resp:
            print(f"  status: {resp.status}")
            print(f"  body:   {resp.read().decode()[:400]}")
    except urllib.error.HTTPError as e:
        print(f"  status: {e.code} (error)")
        print(f"  body:   {e.read().decode()[:400]}")

    # Allow a flush window.
    time.sleep(2)
finally:
    print("\nStopping backend (triggers final flush)...")
    stop_backend(info.proc)

metrics_path = DATA_DIR / "telemetry" / "metrics.ndjson"
print(f"\nReading metrics from: {metrics_path}")
content = metrics_path.read_text(encoding="utf-8")
inference_lines = [ln for ln in content.splitlines() if '"name":"inference.' in ln]
config_fail_lines = [
    ln for ln in inference_lines
    if '"name":"inference.config.apply_failure_total"' in ln
]

print(f"\ninference.* total lines: {len(inference_lines)}")
print(f"inference.config.apply_failure_total lines: {len(config_fail_lines)}")
print()
for ln in config_fail_lines[:6]:
    print(ln)

print()
print("=" * 60)
print("VERDICT")
print("=" * 60)
if config_fail_lines:
    print("[OK] config-apply-failure metric fired with bogus model path")
    sys.exit(0)
else:
    print("[FAIL] no config-apply-failure metric fired")
    print("\nAll inference.* lines for diagnosis:")
    for ln in inference_lines[:30]:
        print(ln)
    sys.exit(2)
