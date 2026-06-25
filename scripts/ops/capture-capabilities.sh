#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPO_ROOT="$ROOT_DIR"

cd "$ROOT_DIR"

SUMMARY_DIR="$REPO_ROOT/tmp/agent-evidence/_summaries"
mkdir -p "$SUMMARY_DIR"
TS="$(date +%Y%m%d-%H%M%S)"
RUN_LOG="$SUMMARY_DIR/capture-capabilities-$TS.log"

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
    --scenario=capture-capabilities
    --api-base-url=none
    --out-root=tmp/agent-evidence
    --trace=false
    --attach-label=capabilities
    --attach-file="$RUN_LOG"
    --external-status="$ext_status"
  )
  if [[ "$ext_status" == "failed" ]]; then
    cap_args+=(--external-error="capture-capabilities failed (exit=$exit_code)")
  fi
  if [[ -d "$REPO_ROOT/reports/phase7/capabilities" ]]; then
    cap_args+=(--attach-dir="$REPO_ROOT/reports/phase7/capabilities")
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

echo "[capture-capabilities] Running integration test to regenerate reports/phase7/capabilities" >&2
./gradlew :modules:app-services:integrationTest --tests '*CapabilitiesViewIntegrationTest'

echo "[capture-capabilities] Completed. See reports/phase7/capabilities for payloads." >&2
