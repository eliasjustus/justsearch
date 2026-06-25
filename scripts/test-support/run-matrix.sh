#!/usr/bin/env bash
set -euo pipefail

show_help() {
  cat <<'EOF'
Usage: scripts/test-support/run-matrix.sh --mode=<mode> --budget-profile=<profile> --sample-set=<set> --reports-dir=<dir> [options]

Required flags:
  --mode               One of inproc|indexer-worker|hybrid.
  --budget-profile     Budget profile identifier (search.desktop-default.v1, desktop-tight, etc.).
  --sample-set         Fixture set to run (catalog-smoke|paging-stress|budget-tight).
  --reports-dir        Directory (relative to repo root) for reports/phase10/goldens artifacts.

Optional Phase 13 flags:
  --phase13-paging             Run scripts/phase13/paging-matrix.sh after the matrix task.
  --phase13-automation         Run scripts/phase13/automation-check.sh after the matrix task.
  --paging-platform=<name>     Platform label for paging evidence (default: uname -s).
  --paging-data-dir=<path>     Seeded JUSTSEARCH_DATA_DIR to reuse (default: ~/.justsearch-smoke).
  --paging-query=<string>      Query text for paging harness (default: \"phase13 repeatability\").
  --paging-page=<n>            Page number for SearchDump (default: 1).
  --paging-iterations=<n>      Number of SearchDump iterations (default: 20).

This script launches the simulate workflow, captures goldens, emits paging traces, and updates
reports/phase10 metadata (manifest.json, pr-matrix-summary.json, paging traces, etc.).
EOF
}

MODE=""
BUDGET=""
SAMPLE_SET=""
REPORTS_DIR=""
PHASE13_PAGING=false
PHASE13_AUTOMATION=false
PAGING_PLATFORM="$(uname -s 2>/dev/null || echo unknown)"
PAGING_DATA_DIR="${HOME}/.justsearch-smoke"
PAGING_QUERY="phase13 repeatability"
PAGING_PAGE=1
PAGING_ITERATIONS=20

for arg in "$@"; do
  case $arg in
    --mode=*)
      MODE="${arg#*=}"
      ;;
    --budget-profile=*)
      BUDGET="${arg#*=}"
      ;;
    --reports-dir=*)
      REPORTS_DIR="${arg#*=}"
      ;;
    --sample-set=*)
      SAMPLE_SET="${arg#*=}"
      ;;
    --phase13-paging)
      PHASE13_PAGING=true
      ;;
    --phase13-automation)
      PHASE13_AUTOMATION=true
      ;;
    --paging-platform=*)
      PAGING_PLATFORM="${arg#*=}"
      ;;
    --paging-data-dir=*)
      PAGING_DATA_DIR="${arg#*=}"
      ;;
    --paging-query=*)
      PAGING_QUERY="${arg#*=}"
      ;;
    --paging-page=*)
      PAGING_PAGE="${arg#*=}"
      ;;
    --paging-iterations=*)
      PAGING_ITERATIONS="${arg#*=}"
      ;;
    --help|-h)
      show_help
      exit 0
      ;;
    *)
      echo "Unknown argument: $arg" >&2
      show_help >&2
      exit 64
      ;;
  esac
done

if [[ -z "$MODE" || -z "$BUDGET" || -z "$SAMPLE_SET" || -z "$REPORTS_DIR" ]]; then
  echo "Missing required arguments." >&2
  show_help >&2
  exit 64
fi

case "$MODE" in
  inproc|indexer-worker|hybrid) ;;
  *)
    echo "Unsupported mode: $MODE" >&2
    exit 64
    ;;
esac

REPO_ROOT="$(git rev-parse --show-toplevel 2>/dev/null || pwd)"
cd "$REPO_ROOT"

SUMMARY_DIR="$REPO_ROOT/tmp/agent-evidence/_summaries"
mkdir -p "$SUMMARY_DIR"
TS="$(date +%Y%m%d-%H%M%S)"
RUN_LOG="$SUMMARY_DIR/run-matrix-$TS.log"
RUN_META="$SUMMARY_DIR/run-matrix-$TS.meta.json"

finalize_evidence() {
  local exit_code="$?"
  local ext_status="passed"
  if [[ "$exit_code" -ne 0 ]]; then
    ext_status="failed"
  fi

  set +e

  # Best-effort: record run inputs for debugging without scraping logs.
  cat >"$RUN_META" <<EOF
{
  "schema": "run-matrix.v1",
  "mode": "$(printf '%s' "${MODE}" | sed 's/\"/\\\\\"/g')",
  "budget_profile": "$(printf '%s' "${BUDGET}" | sed 's/\"/\\\\\"/g')",
  "sample_set": "$(printf '%s' "${SAMPLE_SET}" | sed 's/\"/\\\\\"/g')",
  "reports_dir": "$(printf '%s' "${REPORTS_DIR}" | sed 's/\"/\\\\\"/g')",
  "phase13_paging": ${PHASE13_PAGING},
  "phase13_automation": ${PHASE13_AUTOMATION}
}
EOF

  local reports_abs="$REPO_ROOT/$REPORTS_DIR"

  local cap_args=(
    node modules/ui-web/scripts/capture-evidence-bundle.mjs
    --scenario=run-matrix
    --api-base-url=none
    --out-root=tmp/agent-evidence
    --trace=false
    --attach-label=matrix
    --attach-file="$RUN_LOG"
    --attach-file="$RUN_META"
    --external-status="$ext_status"
  )

  if [[ "$ext_status" == "failed" ]]; then
    cap_args+=(--external-error="run-matrix failed (exit=$exit_code)")
  fi

  if [[ -d "$reports_abs" ]]; then
    cap_args+=(--attach-dir="$reports_abs")
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

# Capture all output to a run log (still prints to console).
exec > >(tee -a "$RUN_LOG") 2>&1

CMD=(./gradlew :modules:test-support:goldenSmoke "--console=plain" "-Pmode=${MODE}" "-PsampleSet=${SAMPLE_SET}" "-PbudgetProfile=${BUDGET}" "-PreportsDir=${REPORTS_DIR}")
echo "[run-matrix] exec: ${CMD[*]}" >&2
"${CMD[@]}"

if [[ "${PHASE13_PAGING}" == "true" ]]; then
  echo "[run-matrix] running Phase 13 paging harness" >&2
  scripts/phase13/paging-matrix.sh \
    --platform="${PAGING_PLATFORM}" \
    --data-dir="${PAGING_DATA_DIR}" \
    --query="${PAGING_QUERY}" \
    --page="${PAGING_PAGE}" \
    --iterations="${PAGING_ITERATIONS}"
fi

if [[ "${PHASE13_AUTOMATION}" == "true" ]]; then
  echo "[run-matrix] running Phase 13 automation harness" >&2
  scripts/phase13/automation-check.sh
fi
