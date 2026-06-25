#!/usr/bin/env bash
set -euo pipefail

host="127.0.0.1"
port="7585"
freq_hz="2"
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
log_path="${ROOT_DIR}/reports/phase8/ui/diagnostics/health-flap.json"
duration_sec=0

usage() {
  cat <<'USAGE'
Usage: scripts/diagnostics/health-flap.sh [options]

Simulate infra health flapping by serving a loopback-only /infra/health endpoint that toggles
between healthy and degraded snapshots. Point JUSTSEARCH_INFRA_HEALTH_HOST/PORT at the selected
host/port before starting the UI to exercise degraded banner hysteresis.

Options:
  --host <addr>      Bind address (default: 127.0.0.1)
  --port <port>      Listen port (default: 7585)
  --frequency <hz>   Toggle frequency in Hertz (default: 2)
  --duration <sec>   Stop automatically after N seconds (0 = run until interrupted)
  --log <path>       Path to write emitted snapshots as JSON (default: reports/phase8/ui/diagnostics/health-flap.json)
  --no-log           Disable snapshot logging
  -h, --help         Show this help.
USAGE
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --host)
      host="$2"; shift 2;;
    --port)
      port="$2"; shift 2;;
    --frequency)
      freq_hz="$2"; shift 2;;
    --duration)
      duration_sec="$2"; shift 2;;
    --log)
      log_path="$2"; shift 2;;
    --no-log)
      log_path=""
      shift;;
    -h|--help)
      usage; exit 0;;
    *)
      echo "Unknown argument: $1" >&2; usage; exit 1;;
  esac
done

command -v python3 >/dev/null 2>&1 || { echo "python3 is required" >&2; exit 1; }

if [[ -n "$log_path" ]]; then
  mkdir -p "$(dirname "$log_path")"
fi

python3 - "$host" "$port" "$freq_hz" "$duration_sec" "$log_path" <<'PYCODE'
import json
import signal
import sys
import threading
import time
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

host, port, freq_hz, duration, log_path = sys.argv[1:]
freq_hz = float(freq_hz)
duration = int(duration)
period = 1.0 / freq_hz if freq_hz > 0 else 0.5
start = time.monotonic()
stop_event = threading.Event()

state_lock = threading.Lock()
state = {"degraded": False, "last_toggle": start}
history = []

def snapshot(degraded: bool):
    now = datetime.now(timezone.utc).isoformat()
    overall = "degraded" if degraded else "healthy"
    translator_status = "degraded" if degraded else "healthy"
    handshake_age = 2500 if degraded else 25
    return {
        "status": overall,
        "generatedAt": now,
        "components": [
            {
                "componentId": "translator",
                "status": translator_status,
                "reasonCode": "translator_handshake_stale" if degraded else None,
                "metrics": {"handshake_age_ms": handshake_age},
            },
            {
                "componentId": "indexing",
                "status": "healthy",
                "reasonCode": None,
                "metrics": {"nrt_lag_ms": 250},
            },
            {
                "componentId": "ann_cache",
                "status": "healthy",
                "reasonCode": None,
                "metrics": {"ready_percent": 95},
            },
        ],
        "metadata": {"source": "health-flap-sim", "frequency_hz": freq_hz},
    }

log_file = None
if log_path:
    log_file = open(log_path, "w", encoding="utf-8")

def maybe_toggle():
    with state_lock:
        now = time.monotonic()
        if period > 0 and now - state["last_toggle"] >= period:
            state["degraded"] = not state["degraded"]
            state["last_toggle"] = now

class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args, **kwargs):
        return

    def do_GET(self):
        if self.path != "/infra/health":
            self.send_error(404, "Unknown path")
            return
        maybe_toggle()
        with state_lock:
            payload = snapshot(state["degraded"])
        body = json.dumps(payload).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)
        if log_file is not None:
            history.append(payload)

server = ThreadingHTTPServer((host, int(port)), Handler)

def shutdown(*_):
    stop_event.set()
    server.shutdown()

signal.signal(signal.SIGINT, shutdown)
signal.signal(signal.SIGTERM, shutdown)

thread = threading.Thread(target=server.serve_forever, daemon=True)
thread.start()

print(f"[health-flap] Serving on http://{host}:{port}/infra/health (frequency={freq_hz}Hz)", flush=True)
if duration > 0:
    stop_event.wait(duration)
    shutdown()
else:
    stop_event.wait()

thread.join()
server.server_close()
if log_file is not None:
    json.dump({"snapshots": history}, log_file, indent=2)
    log_file.write("\n")
    log_file.close()
print("[health-flap] Stopped", flush=True)
PYCODE
