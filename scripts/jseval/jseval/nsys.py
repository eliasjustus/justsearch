"""Nsight Systems CUDA capture and analysis (356 Step 4).

Wraps nsys CLI to capture system-wide CUDA kernel activity during a
pipeline run, then queries the resulting SQLite for structured summaries.
"""

from __future__ import annotations

import logging
import os
import shutil
import sqlite3
import subprocess
import time
from pathlib import Path

log = logging.getLogger(__name__)

_KNOWN_NSYS_PATH = Path("D:/tools/NsightSystems/target-windows-x64/nsys.exe")


def find_nsys() -> Path | None:
    """Find nsys executable: check PATH first, then known install path."""
    on_path = shutil.which("nsys")
    if on_path:
        return Path(on_path)
    if _KNOWN_NSYS_PATH.is_file():
        return _KNOWN_NSYS_PATH
    return None


def start_capture(output_path: Path) -> tuple[subprocess.Popen, str]:
    """Start nsys with system-wide CUDA tracing wrapping a dummy target.

    Returns (nsys Popen handle, session name). Stop with stop_capture().
    """
    nsys = find_nsys()
    if nsys is None:
        raise FileNotFoundError("nsys not found on PATH or at known install path")

    output_path.parent.mkdir(parents=True, exist_ok=True)

    # Named session so stop_capture() can address it directly (Fix 1).
    session_name = f"jseval-{int(time.time())}"

    # Dummy target that keeps nsys alive. nsys profile requires a target
    # process; system-wide CUDA tracing captures all GPU activity regardless.
    dummy = ["cmd.exe", "/c", "ping -n 3600 127.0.0.1 > nul"] if os.name == "nt" \
        else ["sleep", "3600"]

    cmd = [
        str(nsys), "profile",
        f"--session-new={session_name}",
        "--trace=cuda",
        "--cuda-trace-scope=system-wide",
        "--cuda-memory-usage=true",
        f"--output={output_path}",
        "--force-overwrite=true",
        "--show-output=false",
        "--",
    ] + dummy

    log.info("Starting Nsight Systems capture: %s (session=%s)", output_path, session_name)
    proc = subprocess.Popen(
        cmd,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        creationflags=subprocess.CREATE_NEW_PROCESS_GROUP if os.name == "nt" else 0,
    )

    # Brief wait for nsys to initialize
    time.sleep(2)
    if proc.poll() is not None:
        raise RuntimeError(f"nsys exited immediately with code {proc.returncode}")

    log.info("Nsight Systems capture started (PID=%d, session=%s)", proc.pid, session_name)
    return proc, session_name


def stop_capture(
    nsys_proc: subprocess.Popen, session_name: str, output_path: Path
) -> Path | None:
    """Stop nsys capture and wait for the report file.

    Returns the .nsys-rep path, or None if capture failed.
    """
    nsys = find_nsys()
    if nsys is None or nsys_proc.poll() is not None:
        return None

    log.info("Stopping Nsight Systems capture (session=%s)...", session_name)

    # Use the known session name directly (Fix 1 — no session list parsing).
    try:
        result = subprocess.run(
            [str(nsys), "stop", f"--session={session_name}"],
            capture_output=True, text=True, timeout=120,
        )
        log.info("nsys stop output: %s", result.stdout.strip()[:200])
    except (subprocess.TimeoutExpired, OSError) as e:
        log.warning("nsys stop failed: %s, terminating process", e)

    # Ensure the process is dead
    if nsys_proc.poll() is None:
        nsys_proc.terminate()
        try:
            nsys_proc.wait(timeout=30)
        except subprocess.TimeoutExpired:
            nsys_proc.kill()

    # Look for the .nsys-rep file
    rep_path = Path(f"{output_path}.nsys-rep")
    if rep_path.is_file():
        log.info("Nsight Systems report: %s (%.1f MB)",
                 rep_path, rep_path.stat().st_size / 1e6)
        return rep_path

    log.warning("Nsight Systems report not found: %s", rep_path)
    return None


def query_gpu_profile(nsys_rep_path: Path) -> dict:
    """Query the nsys report for kernel and memcpy summaries.

    Exports to SQLite first, then runs SQL queries.
    """
    nsys = find_nsys()
    if nsys is None:
        return {}

    # Export to SQLite
    sqlite_path = nsys_rep_path.with_suffix(".sqlite")
    if not sqlite_path.is_file():
        log.info("Exporting nsys report to SQLite...")
        subprocess.run(
            [str(nsys), "export", "--type=sqlite",
             f"--output={sqlite_path}", str(nsys_rep_path)],
            capture_output=True, text=True, timeout=120,
        )

    if not sqlite_path.is_file():
        log.warning("SQLite export failed: %s", sqlite_path)
        return {}

    try:
        return _query_sqlite(sqlite_path)
    except Exception as e:
        log.warning("Failed to query nsys SQLite: %s", e)
        return {}


def _query_sqlite(sqlite_path: Path) -> dict:
    """Run kernel and memcpy summary queries on the nsys SQLite."""
    conn = sqlite3.connect(str(sqlite_path))
    try:
        # Check if kernel table exists
        tables = {row[0] for row in conn.execute(
            "SELECT name FROM sqlite_master WHERE type='table'"
        ).fetchall()}

        result: dict = {}

        if "CUPTI_ACTIVITY_KIND_KERNEL" in tables and "StringIds" in tables:
            result["kernels"] = _query_kernels(conn)

        if "CUPTI_ACTIVITY_KIND_MEMCPY" in tables:
            result["memcpy"] = _query_memcpy(conn)

        return result
    finally:
        conn.close()


def _query_kernels(conn: sqlite3.Connection) -> dict:
    """Query kernel summary: total count, duration, top 10 by time."""
    # Total
    row = conn.execute(
        "SELECT COUNT(*), SUM(end - start) FROM CUPTI_ACTIVITY_KIND_KERNEL"
    ).fetchone()
    total_count = row[0] or 0
    total_ns = row[1] or 0

    # Top 10 by duration
    rows = conn.execute("""
        SELECT s.value AS name, COUNT(*) AS cnt, SUM(k.end - k.start) AS total_ns
        FROM CUPTI_ACTIVITY_KIND_KERNEL k
        JOIN StringIds s ON k.demangledName = s.id
        GROUP BY s.value
        ORDER BY total_ns DESC
        LIMIT 10
    """).fetchall()

    top_kernels = []
    for name, cnt, kern_ns in rows:
        # Shorten long kernel names for readability
        short_name = name
        if len(short_name) > 80:
            short_name = short_name[:77] + "..."
        top_kernels.append({
            "name": short_name,
            "count": cnt,
            "total_ms": round(kern_ns / 1e6, 1),
            "avg_us": round(kern_ns / cnt / 1e3, 1) if cnt > 0 else 0,
            "pct": round(kern_ns / total_ns * 100, 1) if total_ns > 0 else 0,
        })

    return {
        "total_count": total_count,
        "total_duration_ms": round(total_ns / 1e6, 1),
        "top_kernels": top_kernels,
    }


def _query_memcpy(conn: sqlite3.Connection) -> dict:
    """Query memcpy summary by direction."""
    rows = conn.execute("""
        SELECT copyKind, COUNT(*), SUM(end - start), SUM(bytes)
        FROM CUPTI_ACTIVITY_KIND_MEMCPY
        GROUP BY copyKind
    """).fetchall()

    # copyKind: 1=H2D, 2=D2H, 8=D2D
    kind_map = {1: "h2d", 2: "d2h", 8: "d2d"}
    result: dict = {}
    for kind, count, total_ns, total_bytes in rows:
        prefix = kind_map.get(kind, f"kind{kind}")
        result[f"{prefix}_count"] = count
        result[f"{prefix}_ms"] = round((total_ns or 0) / 1e6, 1)
        result[f"{prefix}_bytes"] = total_bytes or 0

    return result
