"""Backend lifecycle management — start, wait, stop (item 1)."""

from __future__ import annotations

import dataclasses
import logging
import os
import shutil
import subprocess
import time
from pathlib import Path

import httpx

from ._paths import REPO_ROOT, shared_models_dir

log = logging.getLogger(__name__)

_DEFAULT_PORT = 33221
_HEALTH_POLL_SEC = 2.0
_HEALTH_TIMEOUT_SEC = 120.0
_LLM_HEALTH_TIMEOUT_SEC = 240.0  # 369: LLM model loading adds significant time


@dataclasses.dataclass
class BackendInfo:
    """Return value from start_backend() — includes the data_dir for log access."""

    proc: subprocess.Popen
    data_dir: Path


def start_backend(
    *,
    repo_root: Path | None = None,
    data_dir: Path | None = None,
    port: int = _DEFAULT_PORT,
    clean: bool = False,
    env_overrides: dict[str, str] | None = None,
    health_timeout_sec: float = _HEALTH_TIMEOUT_SEC,
    llm: bool = False,
) -> BackendInfo:
    """Start runHeadlessEval and wait for the backend to become healthy.

    Returns a BackendInfo with the Popen handle and data directory path.
    When llm=True, passes -Pllm=true to Gradle and waits for inference readiness.
    Uses a single deadline for all readiness checks.
    """
    resolved_root = repo_root or REPO_ROOT
    # Honor JUSTSEARCH_DATA_DIR pre-set by callers (e.g. jseval calibrate
    # pointing every sub-run at the same cohort baseline registry).
    # Only fall back to the default when neither arg nor env was supplied.
    if data_dir is None:
        env_data_dir = os.environ.get("JUSTSEARCH_DATA_DIR")
        data_dir = Path(env_data_dir) if env_data_dir else None
    # Tempdoc 400 §23.8 D-2: if the resulting path is relative, resolve it
    # against REPO_ROOT — that's the resolution frame the Gradle Java
    # subprocess uses (cwd=resolved_root). Historically, Python resolved
    # against its own CWD (which becomes scripts/jseval when calibrate
    # spawns jseval run), so rmtree targeted a different absolute path
    # than where Java actually wrote. Single frame, no mismatch.
    if data_dir is not None and not data_dir.is_absolute():
        data_dir = (resolved_root / data_dir).resolve()
    resolved_data = data_dir or (resolved_root / "tmp" / "headless-eval-data")
    gradlew_name = "gradlew.bat" if os.name == "nt" else "gradlew"
    gradlew = resolved_root / gradlew_name

    if not gradlew.is_file():
        raise FileNotFoundError(f"{gradlew_name} not found at {gradlew}")

    if clean and resolved_data.is_dir():
        # Tempdoc 400 Phase 3: preserve cohort_baselines/ (envelope +
        # span_distributions facets) and non_determinism_envelopes/
        # (legacy Phase-2 sidecars) across --clean so long-term
        # calibration survives ingest resets. Everything else in the
        # data dir is index/queue/telemetry state that --clean is
        # meant to wipe.
        log.info("Cleaning data directory: %s (preserving cohort_baselines/, non_determinism_envelopes/)", resolved_data)
        _protected = {"cohort_baselines", "non_determinism_envelopes"}
        for child in resolved_data.iterdir():
            if child.name in _protected:
                continue
            if child.is_dir():
                shutil.rmtree(child, ignore_errors=True)
            else:
                try:
                    child.unlink()
                except OSError:
                    pass

    env = os.environ.copy()
    env["JUSTSEARCH_DATA_DIR"] = str(resolved_data)
    env["JUSTSEARCH_API_PORT"] = str(port)
    if env_overrides:
        env.update(env_overrides)

    # Tempdoc 644 Axis 1: when launched from a git worktree, the worktree's own models/
    # holds only LFS pointer files, so reranker/dense/SPLADE discovery silently fails and
    # the cross-encoder turns off → wrong-but-plausible hybrid numbers. Default
    # JUSTSEARCH_MODELS_DIR to the MAIN checkout's models (mirrors dev-runner.cjs:428-434).
    # Lowest precedence: a caller/env/run-config JUSTSEARCH_MODELS_DIR always wins.
    if not env.get("JUSTSEARCH_MODELS_DIR"):
        shared_models = shared_models_dir()
        if shared_models is not None:
            env["JUSTSEARCH_MODELS_DIR"] = str(shared_models)
            log.info("Resolved JUSTSEARCH_MODELS_DIR=%s (shared models)", shared_models)

    cmd = [
        str(gradlew),
        ":modules:ui:runHeadlessEval",
        "--no-configuration-cache",
        "--quiet",
    ]
    # 369: Pass -Pllm=true so Gradle enables autostart + longer health timeout.
    if llm:
        cmd.append("-Pllm=true")

    log.info("Starting backend: %s (port=%d, data=%s)", " ".join(cmd), port, resolved_data)

    proc = subprocess.Popen(
        cmd,
        cwd=str(resolved_root),
        env=env,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        creationflags=subprocess.CREATE_NEW_PROCESS_GROUP if os.name == "nt" else 0,
    )

    # 369: Single deadline for all readiness checks (index health + inference).
    effective_timeout = max(health_timeout_sec, _LLM_HEALTH_TIMEOUT_SEC) if llm else health_timeout_sec
    deadline = time.monotonic() + effective_timeout
    base_url = f"http://127.0.0.1:{port}"
    log.info("Waiting for backend to become healthy (timeout=%ds, llm=%s)...", effective_timeout, llm)

    if not _wait_for_health(base_url, deadline, proc):
        stop_backend(proc)
        raise RuntimeError(
            f"Backend did not become healthy within {effective_timeout}s"
        )

    log.info("Backend healthy on port %d (PID=%d)", port, proc.pid)

    # 369: When LLM is requested, also wait for inference to come online.
    # Shares the same deadline — no independent second timeout.
    if llm:
        remaining = deadline - time.monotonic()
        log.info("Waiting for LLM inference (%.0fs remaining)...", remaining)
        diag = _wait_for_inference(base_url, deadline, proc)
        if diag is not None:
            stop_backend(proc)
            raise RuntimeError(
                f"LLM inference did not become available: {diag}"
            )
        log.info("LLM inference available")

    return BackendInfo(proc=proc, data_dir=resolved_data)


def stop_backend(proc: subprocess.Popen) -> None:
    """Stop the backend by killing the process tree.

    Uses taskkill /T /F on Windows (canonical pattern from dev-runner.cjs).
    """
    if proc.poll() is not None:
        log.info("Backend already exited (rc=%d)", proc.returncode)
        return

    pid = proc.pid
    log.info("Stopping backend (PID=%d)...", pid)

    if os.name == "nt":
        # Windows: must kill process tree, not just the root.
        subprocess.run(
            ["taskkill", "/PID", str(pid), "/T", "/F"],
            capture_output=True,
        )
    else:
        proc.terminate()
        try:
            proc.wait(timeout=10)
        except subprocess.TimeoutExpired:
            proc.kill()

    log.info("Backend stopped")


def _wait_for_health(
    base_url: str,
    deadline: float,
    proc: subprocess.Popen,
) -> bool:
    """Poll /api/status until the backend responds with indexAvailable=true.

    Args:
        deadline: absolute monotonic time after which to give up.
    """
    while time.monotonic() < deadline:
        # Check if process died
        if proc.poll() is not None:
            log.error("Backend process exited prematurely (rc=%d)", proc.returncode)
            return False

        try:
            with httpx.Client(base_url=base_url, timeout=5) as client:
                resp = client.get("/api/status")
                resp.raise_for_status()
                data = resp.json()
                if data.get("indexAvailable"):
                    return True
                w = data.get("worker") or {}
                c = w.get("core") or {}
                log.debug("Backend responding but not ready: indexState=%s",
                          c.get("indexState"))
        except Exception:
            pass

        time.sleep(_HEALTH_POLL_SEC)

    return False


def _wait_for_inference(
    base_url: str,
    deadline: float,
    proc: subprocess.Popen,
) -> str | None:
    """Poll /api/inference/status until inference mode is 'online' (369).

    Args:
        deadline: absolute monotonic time after which to give up.

    Returns:
        None on success, or a diagnostic string explaining why it failed.
    """
    last_mode = "unknown"
    while time.monotonic() < deadline:
        if proc.poll() is not None:
            return f"backend process exited (rc={proc.returncode})"

        try:
            with httpx.Client(base_url=base_url, timeout=5) as client:
                resp = client.get("/api/inference/status")
                resp.raise_for_status()
                data = resp.json()
                last_mode = data.get("mode", "offline")
                if last_mode == "online":
                    return None  # success
                if last_mode == "transitioning":
                    log.debug("Inference mode transitioning (model loading)...")
                else:
                    log.debug("Inference mode: %s", last_mode)
        except Exception:
            pass

        time.sleep(_HEALTH_POLL_SEC)

    if last_mode == "offline":
        return (
            "inference stayed offline (autostart may have failed silently). "
            "Check app.log for 'Failed to start llama-server' warnings. "
            "Common causes: JUSTSEARCH_SERVER_EXE not set, missing DLLs, "
            "or no .gguf model in models/"
        )
    if last_mode == "transitioning":
        return (
            "inference still transitioning (model load exceeded timeout). "
            "The model may be too large for available resources, or the "
            "health check timeout needs to be increased"
        )
    return f"inference mode was '{last_mode}' at timeout"
