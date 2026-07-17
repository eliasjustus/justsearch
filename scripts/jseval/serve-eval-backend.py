#!/usr/bin/env python
"""Persistent eval-backend server for the Step-2 powered agent-utility campaign.

Authored for `chain-step2.bat` (do NOT confuse with `jseval dev`, which blocks on
Ctrl-C and cannot be clean-stopped from a detached batch). This process:

  1. start_backend(clean=..., port=...)  -> runHeadlessEval on JUSTSEARCH_API_PORT
     (default 33221). That backend serves BOTH the REST API and the /mcp endpoint
     on the same Javalin port (verified: LocalApiServer.java:575-578 mounts
     `/mcp` whenever mcpProtocolHandler != null, and HeadlessApp always builds a
     non-null one -> `/mcp` is live at http://127.0.0.1:<port>/mcp with no flag).
  2. writes <ready-file> once healthy (the batch polls for it).
  3. blocks until <stop-file> appears (or SIGINT/SIGTERM/SIGBREAK), then calls
     stop_backend() for an ORPHAN-SAFE teardown (backend.py handles the surviving
     Worker JVM), and writes <stopped-file>.
  4. on any start/ingest exception, writes <failed-file> and exits non-zero so the
     batch fails fast instead of polling <ready-file> forever.

Marker-file protocol (never a pipe, never a masked exit): the batch reads exit
codes and the presence of ready/stopped/failed files, so a crash mid-way leaves a
legible state.
"""
from __future__ import annotations

import argparse
import signal
import sys
import time
import traceback
from pathlib import Path


def _unlink(p: str) -> None:
    try:
        Path(p).unlink()
    except FileNotFoundError:
        pass


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=33221)
    ap.add_argument("--clean", action="store_true")
    ap.add_argument("--ready-file", required=True)
    ap.add_argument("--stop-file", required=True)
    ap.add_argument("--stopped-file", required=True)
    ap.add_argument("--failed-file", required=True)
    a = ap.parse_args()

    # Fresh markers for this lifecycle.
    for m in (a.ready_file, a.stopped_file, a.failed_file, a.stop_file):
        _unlink(m)

    from jseval import backend as backend_mod

    try:
        info = backend_mod.start_backend(clean=a.clean, port=a.port)
    except Exception:  # noqa: BLE001 - fail-closed: surface to the batch
        Path(a.failed_file).write_text(traceback.format_exc(), encoding="utf-8")
        print("serve-eval-backend: start_backend FAILED", file=sys.stderr)
        return 3

    Path(a.ready_file).write_text(f"ready port={a.port}\n", encoding="utf-8")
    print(f"serve-eval-backend: healthy on port {a.port} (PID={info.proc.pid})")

    stop = {"now": False}

    def _stop(*_):
        stop["now"] = True

    signal.signal(signal.SIGINT, _stop)
    signal.signal(signal.SIGTERM, _stop)
    if hasattr(signal, "SIGBREAK"):  # Windows console break
        signal.signal(signal.SIGBREAK, _stop)

    try:
        while not stop["now"] and not Path(a.stop_file).exists():
            time.sleep(2.0)
    finally:
        try:
            backend_mod.stop_backend(info.proc, data_dir=info.data_dir)
        finally:
            Path(a.stopped_file).write_text("stopped\n", encoding="utf-8")
            print("serve-eval-backend: stopped (orphan-safe)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
