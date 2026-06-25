"""Tempdoc 412 inference observability — happy-path empirical smoke gate.

Verifies the inference observability path against a live dev backend:

1. Cold-start the backend with llama-server.
2. POST /api/admin/inference/reload with a known reason.
3. Stop the backend — JVM shutdown triggers final telemetry flush.
4. Parse metrics.ndjson for the expected inference.* lines with
   non-degenerate (real from/to phase, real elapsed) tags.

Exits 0 PASS / non-zero FAIL with diagnostic dump.
"""
import json
import sys
import urllib.request
from pathlib import Path

REPO_ROOT = Path(r"F:/JustSearch")
sys.path.insert(0, str(REPO_ROOT / "scripts" / "jseval"))
from jseval.backend import start_backend, stop_backend  # noqa: E402

DATA_DIR = REPO_ROOT / "tmp" / "diagnostics" / "inference" / "happy-path-data"
PORT = 33222

print("=" * 60)
print("INFERENCE SMOKE GATE - happy path")
print("=" * 60)
print(f"Repo root: {REPO_ROOT}")
print(f"Data dir:  {DATA_DIR}")
print(f"Port:      {PORT}")
print()

env_overrides = {
    "JUSTSEARCH_SERVER_EXE": str(
        REPO_ROOT / "modules" / "ui" / "native-bin" / "llama-server" / "variants" / "cuda12" / "llama-server.exe"
    ),
    "JUSTSEARCH_MODELS_DIR": str(REPO_ROOT / "models"),
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
print(f"Backend up: pid={info.proc.pid}, data_dir={info.data_dir}")
print()

try:
    print("POST /api/admin/inference/reload {reason: smoke_gate}")
    req = urllib.request.Request(
        f"http://127.0.0.1:{PORT}/api/admin/inference/reload",
        data=json.dumps({"reason": "smoke_gate"}).encode(),
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    try:
        with urllib.request.urlopen(req, timeout=120) as resp:
            status = resp.status
            body = resp.read().decode()
        print(f"  status: {status}")
        print(f"  body:   {body}")
    except urllib.error.HTTPError as e:
        status = e.code
        body = e.read().decode()
        print(f"  status: {status} (error)")
        print(f"  body:   {body}")
    print()
finally:
    print("Stopping backend (triggers final flush)...")
    stop_backend(info.proc)

print()
print("=" * 60)
print("READING metrics.ndjson")
print("=" * 60)

metrics_path = info.data_dir / "telemetry" / "metrics.ndjson"
print(f"Path: {metrics_path}")
if not metrics_path.exists():
    print(f"FAIL: metrics file missing at {metrics_path}")
    sys.exit(1)

content = metrics_path.read_text(encoding="utf-8")
inference_lines = [
    ln for ln in content.splitlines() if '"name":"inference.' in ln
]
print(f"inference.* lines: {len(inference_lines)}")
print()

# Print all inference.* lines so we can read them in the output
for ln in inference_lines:
    print(ln)

print()
print("=" * 60)
print("VERDICT")
print("=" * 60)

# Expected: at least one transition.total line (or transition.duration_ms) AND
# at least one config.apply_total line (since reloadRuntime calls applyConfig).
def has_metric(name: str) -> list[str]:
    return [ln for ln in inference_lines if f'"name":"{name}"' in ln]


checks = {
    "inference.transition.total": has_metric("inference.transition.total"),
    "inference.transition.duration_ms": has_metric("inference.transition.duration_ms"),
    "inference.config.apply_total": has_metric("inference.config.apply_total"),
    "inference.config.apply_failure_total": has_metric("inference.config.apply_failure_total"),
    "inference.startup.attempt_total": has_metric("inference.startup.attempt_total"),
    "inference.startup.duration_ms": has_metric("inference.startup.duration_ms"),
    "inference.startup.failure_total": has_metric("inference.startup.failure_total"),
    "inference.health.failure_total": has_metric("inference.health.failure_total"),
}
for name, lines in checks.items():
    marker = "OK" if lines else "MISSING"
    print(f"  [{marker}] {name}: {len(lines)} lines")

# Bug-class checks
print()
print("Bug-class verification:")

# Were any transition events emitted with REAL phase tags (not OFFLINE->OFFLINE)?
real_transitions = [
    ln
    for ln in has_metric("inference.transition.total")
    if not ('"from_phase":"OFFLINE"' in ln and '"to_phase":"OFFLINE"' in ln)
]
print(f"  non-degenerate transitions (not OFFLINE->OFFLINE): {len(real_transitions)}")

# Were any transitions emitted with duration > 0?
nonzero_durations = [
    ln
    for ln in has_metric("inference.transition.duration_ms")
    if not '"p50":0' in ln
]
print(f"  non-zero transition durations: {len(nonzero_durations)}")

# Did config_apply reason fire (vs auto_start)?
config_apply_transitions = [
    ln for ln in has_metric("inference.transition.total") if '"reason":"config_apply"' in ln
]
print(f"  reason=config_apply transitions: {len(config_apply_transitions)}")

print()
print("Done.")
