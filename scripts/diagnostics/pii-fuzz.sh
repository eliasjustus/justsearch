#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
TARGET_ROOT="${JUSTSEARCH_HOME:-$HOME/.justsearch}"
REPORT_FILE="${ROOT_DIR}/reports/phase8/ui/diagnostics/pii-fuzz.log"
TS="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

usage() {
  cat <<'USAGE'
Usage: scripts/diagnostics/pii-fuzz.sh [options]

Inject synthetic telemetry/log entries containing PII-like fields to validate UI redaction logic.

Options:
  --root <dir>   Target JustSearch data directory (default: $HOME/.justsearch or JUSTSEARCH_HOME)
  --report <path>  Path to write the synthetic injection summary (default: reports/phase8/ui/diagnostics/pii-fuzz.log)
  -h, --help     Show this help message.
USAGE
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --root)
      TARGET_ROOT="$2"; shift 2;;
    --report)
      REPORT_FILE="$2"; shift 2;;
    -h|--help)
      usage; exit 0;;
    *)
      echo "Unknown argument: $1" >&2
      usage
      exit 1;;
  esac
done

TELEMETRY_DIR="$TARGET_ROOT/telemetry"
LOG_DIR="$TARGET_ROOT/logs"
mkdir -p "$TELEMETRY_DIR" "$LOG_DIR"

METRICS_FILE="$TELEMETRY_DIR/metrics.ndjson"
TRACES_FILE="$TELEMETRY_DIR/traces.ndjson"
LOG_FILE="$LOG_DIR/app.log"

cat >> "$METRICS_FILE" <<METRICS
{"timestamp":"$TS","name":"ui.diagnostics.pii_fuzz","type":"counter","value":1,"tags":{"reason_code":"pii_fuzz","user_email":"alice@example.com","ip_address":"203.0.113.5"}}
{"timestamp":"$TS","name":"ui.diagnostics.pii_fuzz","type":"counter","value":1,"tags":{"reason_code":"pii_fuzz","user_email":"bob@example.com","ip_address":"198.51.100.42"}}
METRICS

cat >> "$TRACES_FILE" <<TRACES
{"traceId":"fuzz-$TS","spanId":"0001","parentId":null,"name":"ui.fuzz","kind":"INTERNAL","timestamp":"$TS","durationMs":12,"attributes":{"justsearch.user.email":"carol@example.com","user_full_name":"Carol Example","pii_sample":"SSN 123-45-6789"}}
{"traceId":"fuzz-$TS","spanId":"0002","parentId":"0001","name":"ui.fuzz.child","kind":"INTERNAL","timestamp":"$TS","durationMs":4,"attributes":{"user_phone":"+1-415-555-1234","notes":"Contains PII"}}
TRACES

cat >> "$LOG_FILE" <<LOG
$TS INFO  [UI/Diagnostics] pii_fuzz user_email=dan@example.com account_id=ACC-123456789 trace_id=fuzz-$TS context="sensitive payload"
$TS WARN  [UI/Diagnostics] pii_fuzz user_email=erin@example.com ip=192.0.2.55 upload_path="/Users/erin/Documents/private-plan.pdf"
LOG

if [[ -n "$REPORT_FILE" ]]; then
  mkdir -p "$(dirname "$REPORT_FILE")"
  cat > "$REPORT_FILE" <<REPORT
pii-fuzz run at $TS
target_root=$TARGET_ROOT
metrics_file=$METRICS_FILE
traces_file=$TRACES_FILE
log_file=$LOG_FILE
REPORT
fi

echo "[pii-fuzz] Injected synthetic entries into:"
echo "  $METRICS_FILE"
echo "  $TRACES_FILE"
echo "  $LOG_FILE"
