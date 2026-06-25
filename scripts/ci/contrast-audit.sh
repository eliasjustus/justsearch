#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
DEFAULT_URL="http://127.0.0.1:7878/catalog/index.html"
OUTPUT_FILE="$ROOT_DIR/reports/phase8/ui/contrast/contrast-audit.json"
AXE_ARGS=()

SUMMARY_DIR="$REPO_ROOT/tmp/agent-evidence/_summaries"
mkdir -p "$SUMMARY_DIR"
TS="$(date +%Y%m%d-%H%M%S)"
RUN_LOG="$SUMMARY_DIR/contrast-audit-$TS.log"

finalize_evidence() {
  local exit_code="$?"
  local ext_status="passed"
  if [[ "$exit_code" -ne 0 ]]; then
    ext_status="failed"
  fi

  set +e
  cd "$REPO_ROOT" >/dev/null 2>&1

  local cap_args=(
    node modules/ui-web/scripts/capture-evidence-bundle.mjs
    --scenario=contrast-audit
    --api-base-url=none
    --out-root=tmp/agent-evidence
    --trace=false
    --attach-label=contrast
    --attach-file="$RUN_LOG"
    --external-status="$ext_status"
  )
  if [[ "$ext_status" == "failed" ]]; then
    cap_args+=(--external-error="contrast-audit failed (exit=$exit_code)")
  fi
  if [[ -f "$OUTPUT_FILE" ]]; then
    cap_args+=(--attach-file="$OUTPUT_FILE")
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

exec > >(tee -a "$RUN_LOG") 2>&1

usage() {
  cat <<'USAGE'
Usage: scripts/contrast-audit.sh [options] [-- <extra_axe_args>]

Run axe-core focused on WCAG color contrast rules and store the JSON report.

Options:
  --url <url>    Catalog URL to scan (default: http://127.0.0.1:7878/catalog/index.html)
  --out <path>   Output JSON path (default: reports/phase8/ui/contrast/contrast-audit.json)
  -h, --help     Show this help message.

Any arguments after `--` are passed to `npx @axe-core/cli`.
USAGE
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --url)
      DEFAULT_URL="$2"; shift 2;;
    --out)
      OUTPUT_FILE="$2"; shift 2;;
    -h|--help)
      usage; exit 0;;
    --)
      shift
      AXE_ARGS+=("$@")
      break;;
    *)
      echo "Unknown argument: $1" >&2
      usage
      exit 1;;
  esac
done

command -v npx >/dev/null 2>&1 || { echo "npx is required" >&2; exit 1; }
mkdir -p "$(dirname "$OUTPUT_FILE")"

CMD=(npx --yes @axe-core/cli "$DEFAULT_URL" --save "$OUTPUT_FILE" --exit 0 --rules color-contrast --tags wcag2aa)
if [[ ${#AXE_ARGS[@]} -gt 0 ]]; then
  CMD+=("${AXE_ARGS[@]}")
fi

echo "[contrast-audit] Running: ${CMD[*]}" >&2
"${CMD[@]}"

if [[ -f "$OUTPUT_FILE" && -n "$(command -v jq)" ]]; then
  jq '.violations |= sort_by(.impact)' "$OUTPUT_FILE" > "$OUTPUT_FILE.tmp" 2>/dev/null && mv "$OUTPUT_FILE.tmp" "$OUTPUT_FILE"
fi
