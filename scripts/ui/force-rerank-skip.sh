#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
LOG_FILE="${ROOT_DIR}/reports/phase8/ui/rerank-skip.log"
GRADLE_ARGS=()

SUMMARY_DIR="$ROOT_DIR/tmp/agent-evidence/_summaries"
mkdir -p "$SUMMARY_DIR"
TS="$(date +%Y%m%d-%H%M%S)"
RUN_META="$SUMMARY_DIR/force-rerank-skip-$TS.meta.json"

finalize_evidence() {
  local exit_code="$?"
  local ext_status="passed"
  if [[ "$exit_code" -ne 0 ]]; then
    ext_status="failed"
  fi

  set +e
  cd "$ROOT_DIR" >/dev/null 2>&1

  cat >"$RUN_META" <<EOF
{
  "schema": "force-rerank-skip.v1",
  "task": "io.justsearch.app.services.search.SearchRuntimeBootstrapIntegrationTest.rerankStageSkipsWhenBudgetWindowCollapses",
  "log_file": "$(printf '%s' "$LOG_FILE" | sed 's/\"/\\\\\"/g')"
}
EOF

  local cap_args=(
    node modules/ui-web/scripts/capture-evidence-bundle.mjs
    --scenario=force-rerank-skip
    --api-base-url=none
    --out-root=tmp/agent-evidence
    --trace=false
    --attach-label=rerank_skip
    --attach-file="$RUN_META"
    --external-status="$ext_status"
  )
  if [[ -f "$LOG_FILE" ]]; then
    cap_args+=(--attach-file="$LOG_FILE")
  fi
  if [[ "$ext_status" == "failed" ]]; then
    cap_args+=(--external-error="force-rerank-skip failed (exit=$exit_code)")
  fi

  BUNDLE_DIR="$("${cap_args[@]}")"
  CAP_EXIT="$?"
  BUNDLE_DIR="$(printf '%s' "${BUNDLE_DIR:-}" | tail -n 1 | tr -d '\r')"
  echo "EvidenceBundle: ${BUNDLE_DIR:-<none>}" >&2

  if [[ -n "${BUNDLE_DIR:-}" ]]; then
    node scripts/evidence/validate-evidencebundle-v1.mjs "$BUNDLE_DIR" || exit_code=1
    node scripts/evidence/validate-determinism-budget-v1.mjs "$BUNDLE_DIR" || true
  else
    echo "ERROR: Evidence capture produced no bundle path (exit=$CAP_EXIT)" >&2
    if [[ "$exit_code" -eq 0 ]]; then exit_code=1; fi
  fi

  exit "$exit_code"
}

trap finalize_evidence EXIT

usage() {
  cat <<'USAGE'
Usage: scripts/ui/force-rerank-skip.sh [options] [-- <extra_gradle_args>]

Run the SearchRuntimeBootstrap integration scenario that forces a rerank deadline skip
(`reason_code=rerank_skipped_deadline`) and capture output for UI diagnostics verification.

Options:
  --log <path>   Path to write the captured Gradle output (default: reports/phase8/ui/rerank-skip.log)
  -h, --help     Show this help message.

Any arguments after `--` are passed directly to Gradle.
USAGE
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --log)
      LOG_FILE="$2"; shift 2;;
    -h|--help)
      usage; exit 0;;
    --)
      shift
      GRADLE_ARGS+=("$@")
      break;;
    *)
      echo "Unknown argument: $1" >&2
      usage
      exit 1;;
  esac
done

mkdir -p "$(dirname "$LOG_FILE")"
cd "$ROOT_DIR"

TASK='io.justsearch.app.services.search.SearchRuntimeBootstrapIntegrationTest.rerankStageSkipsWhenBudgetWindowCollapses'
CMD=("./gradlew" ":modules:app-services:integrationTest" "--tests" "$TASK" "--console=plain" "--info")
if [[ ${#GRADLE_ARGS[@]} -gt 0 ]]; then
  CMD+=("${GRADLE_ARGS[@]}")
fi

echo "[force-rerank-skip] Running: ${CMD[*]}" >&2
if command -v tee >/dev/null 2>&1; then
  "${CMD[@]}" | tee "$LOG_FILE"
  STATUS=${PIPESTATUS[0]}
else
  "${CMD[@]}" > "$LOG_FILE"
  STATUS=$?
fi

if [[ $STATUS -ne 0 ]]; then
  echo "[force-rerank-skip] Gradle task failed (exit $STATUS)" >&2
  exit $STATUS
fi

if grep -q "rerank_skipped_deadline" "$LOG_FILE"; then
  echo "[force-rerank-skip] ✅ reason_code=rerank_skipped_deadline present in output" >&2
else
  echo "[force-rerank-skip] ⚠️ rerank_skipped_deadline not detected in $LOG_FILE" >&2
fi
