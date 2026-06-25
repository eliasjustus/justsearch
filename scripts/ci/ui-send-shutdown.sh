#!/usr/bin/env bash
set -euo pipefail

signal="INT"
pattern="io.justsearch.ui.App"
wait_secs=0
pid=""
dry_run=0

usage() {
  cat <<'USAGE'
Usage: scripts/ui-send-shutdown.sh [options]

Send a shutdown signal to the running JustSearch UI process.

Options:
  -p, --pid <pid>        Target process id. Overrides --match.
  -m, --match <pattern>  Command-line pattern to locate the UI process (default: io.justsearch.ui.App).
  -s, --signal <name>    Signal to send (default: INT).
  -w, --wait <seconds>   Wait up to N seconds for the process to exit after signaling.
      --dry-run          Print the target pid without sending a signal.
  -h, --help             Show this help message.

Examples:
  scripts/ui-send-shutdown.sh
  scripts/ui-send-shutdown.sh --match "modules:ui:run"
  scripts/ui-send-shutdown.sh --pid 12345 --signal TERM --wait 5
USAGE
}

err() {
  echo "ERROR: $*" >&2
  exit 1
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    -p|--pid)
      [[ $# -ge 2 ]] || err "Missing value for $1"
      pid="$2"
      shift 2
      ;;
    -m|--match)
      [[ $# -ge 2 ]] || err "Missing value for $1"
      pattern="$2"
      shift 2
      ;;
    -s|--signal)
      [[ $# -ge 2 ]] || err "Missing value for $1"
      signal="$2"
      shift 2
      ;;
    -w|--wait)
      [[ $# -ge 2 ]] || err "Missing value for $1"
      wait_secs="$2"
      shift 2
      ;;
    --dry-run)
      dry_run=1
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      err "Unknown argument: $1"
      ;;
  esac
done

if [[ -n "$pid" ]]; then
  [[ "$pid" =~ ^[0-9]+$ ]] || err "PID must be numeric"
else
  command -v pgrep >/dev/null 2>&1 || err "pgrep is required to resolve processes"
  mapfile -t candidates < <(pgrep -f "$pattern" || true)
  [[ "${#candidates[@]}" -gt 0 ]] || err "No process matches pattern '$pattern'"
  if [[ "${#candidates[@]}" -gt 1 ]]; then
    echo "Multiple processes matched pattern '$pattern': ${candidates[*]}" >&2
    echo "Using first match: ${candidates[0]}" >&2
  fi
  pid="${candidates[0]}"
fi

echo "Signaling PID $pid with SIG${signal}" >&2
if [[ "$dry_run" -eq 0 ]]; then
  kill "-${signal}" "$pid" || err "Failed to send signal ${signal} to ${pid}"
fi

if [[ "$wait_secs" -gt 0 && "$dry_run" -eq 0 ]]; then
  for ((i=0; i<wait_secs*10; i++)); do
    if ! kill -0 "$pid" 2>/dev/null; then
      echo "Process ${pid} exited" >&2
      exit 0
    fi
    sleep 0.1
  done
  err "Process ${pid} still running after ${wait_secs}s"
fi
