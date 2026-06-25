"""Per-run environment fingerprint (E-J-N11, tempdoc 391).

Captures a small snapshot of machine state at the start of a jseval run — the
observability this provides is **informational only**. It is not used as a
comparability gate: cross-session runs on a multi-purpose dev PC will
routinely have different fingerprints (different top-N processes, different
power plan, different driver version), and that's expected. The fingerprint
gives post-hoc context for explaining variance when reading runs back.

All probes are best-effort: any missing tool, permission error, or platform
mismatch yields a null/empty field, never a failed run.

Design rules:
- Windows-first (primary dev environment). Linux/macOS fall back to nulls
  for OS-specific probes rather than pretending to probe.
- Process and service enumeration go through ``psutil`` (tempdoc 396) — no
  subprocess shell-outs for those probes. The remaining probes (nvidia-smi,
  powercfg) stay on subprocess because psutil has no equivalent API.
- Keep the field set small. Adding a field is cheap; removing one later is
  expensive because tooling may start relying on it.
- Record the probe tool's stdout verbatim when useful — don't over-normalise.
"""

from __future__ import annotations

import logging
import platform
import subprocess
from datetime import datetime, timezone

import psutil

log = logging.getLogger(__name__)

_PROBE_TIMEOUT_S = 5


def capture_env_fingerprint() -> dict:
    """Capture a best-effort environment fingerprint.

    Returns a dict with at minimum ``captured_at`` and ``platform`` set.
    All other fields are nullable — missing tools produce None, not errors.
    """
    fp: dict = {
        "captured_at": datetime.now(timezone.utc).isoformat(),
        "platform": {
            "system": platform.system(),
            "release": platform.release(),
            "machine": platform.machine(),
        },
        "gpu": _probe_gpu(),
        "power_plan": _probe_power_plan(),
        "services": _probe_services(),
        "top_processes": _probe_top_processes(),
    }
    return fp


def _run(cmd: list[str], *, timeout: int = _PROBE_TIMEOUT_S) -> str | None:
    """Run a command, return stdout stripped, or None on any failure."""
    try:
        result = subprocess.run(
            cmd,
            capture_output=True,
            text=True,
            timeout=timeout,
            check=False,
        )
    except (FileNotFoundError, subprocess.TimeoutExpired, OSError) as e:
        log.debug("env_fingerprint probe %s failed: %s", cmd[0], e)
        return None
    if result.returncode != 0:
        log.debug("env_fingerprint probe %s returned %d", cmd[0], result.returncode)
        return None
    return (result.stdout or "").strip() or None


def _probe_gpu() -> dict:
    """Query nvidia-smi for driver + current GPU state."""
    out = _run(
        [
            "nvidia-smi",
            "--query-gpu=driver_version,name,temperature.gpu,utilization.gpu,"
            "memory.used,memory.total,clocks.gr,clocks.mem",
            "--format=csv,noheader,nounits",
        ]
    )
    if not out:
        return {"available": False}
    # nvidia-smi returns one line per GPU. Snapshot GPU 0.
    first_line = out.splitlines()[0]
    parts = [p.strip() for p in first_line.split(",")]
    if len(parts) < 8:
        return {"available": True, "raw": first_line}
    return {
        "available": True,
        "driver_version": parts[0],
        "name": parts[1],
        "temp_c": _int_or_none(parts[2]),
        "util_pct": _int_or_none(parts[3]),
        "mem_used_mb": _int_or_none(parts[4]),
        "mem_total_mb": _int_or_none(parts[5]),
        "gr_clock_mhz": _int_or_none(parts[6]),
        "mem_clock_mhz": _int_or_none(parts[7]),
    }


def _probe_power_plan() -> str | None:
    """Return the active Windows power plan name; None on non-Windows.

    psutil does not expose power-plan state on Windows, so powercfg remains.
    """
    if platform.system() != "Windows":
        return None
    out = _run(["powercfg", "/getactivescheme"])
    return out


def _probe_services() -> dict:
    """Snapshot a curated set of Windows services that affect dev-box variance.

    The services list is intentionally small — the subset most documented to
    interfere with benchmarks (per tempdoc 390 § Tier 1 dirty-state warning).
    Uses ``psutil.win_service_get`` (tempdoc 396) instead of shelling out to
    ``sc query``.
    """
    if platform.system() != "Windows":
        return {}
    # observations.md `#157`: Windows Defender's actual service name is
    # ``WinDefend`` on Win10/11. Both the prior ``sc query`` path and the
    # current ``psutil.win_service_get`` path returned ``unknown`` for the
    # legacy ``"Defender"`` name (no such service registered).
    names = ["UsoSvc", "wuauserv", "WSearch", "WinDefend", "DiagTrack", "SysMain"]
    result: dict[str, str] = {}
    for name in names:
        try:
            svc = psutil.win_service_get(name)
            status = svc.status()
            result[name] = status.upper() if status else "unknown"
        except psutil.NoSuchProcess:
            result[name] = "unknown"
        except Exception as e:
            log.debug("env_fingerprint service probe %s failed: %s", name, e)
            result[name] = "unknown"
    return result


def _probe_top_processes(limit: int = 5) -> list[dict]:
    """Return top-N processes by cumulative CPU time; best-effort, cross-platform.

    Uses ``psutil.process_iter`` (tempdoc 396). Replaces the former PowerShell
    subprocess probe which intermittently blocked for ~10 s on enterprise
    Windows installs where PowerShell is disabled or stripped.
    """
    try:
        procs: list[dict] = []
        for p in psutil.process_iter(["name", "pid", "cpu_times", "memory_info"]):
            try:
                info = p.info
                ct = info.get("cpu_times")
                cpu_s = round(ct.user + ct.system, 1) if ct else None
                mi = info.get("memory_info")
                ws_mb = round(mi.rss / 1024 / 1024) if mi else None
                procs.append(
                    {
                        "name": info.get("name"),
                        "pid": info.get("pid"),
                        "cpu_seconds": cpu_s,
                        "ws_mb": ws_mb,
                    }
                )
            except (psutil.NoSuchProcess, psutil.AccessDenied):
                continue
        procs.sort(key=lambda x: x["cpu_seconds"] or 0, reverse=True)
        return procs[:limit]
    except Exception as e:
        log.debug("env_fingerprint: top_processes probe failed: %s", e)
        return []


def _int_or_none(s: str) -> int | None:
    try:
        return int(s)
    except (ValueError, TypeError):
        return None


__all__ = ["capture_env_fingerprint"]
