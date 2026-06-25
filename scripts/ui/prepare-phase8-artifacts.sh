#!/usr/bin/env bash
#
# Prepare the directory layout used by the Phase 8 acceptance evidence capture.
# The script only creates folders (and optional .gitkeep placeholders) so that
# subsequent data collection commands can stream logs/screenshots into the
# expected paths without manual setup.
#
# Usage:
#   scripts/ui/prepare-phase8-artifacts.sh [--with-placeholders]
#
# Options:
#   --with-placeholders  Create empty .gitkeep files inside each directory to
#                        simplify browsing or checking the layout into source
#                        control if desired.
#
# The script is idempotent and safe to run multiple times.

set -euo pipefail

WITH_PLACEHOLDERS=false
while [[ $# -gt 0 ]]; do
  case "$1" in
    --with-placeholders)
      WITH_PLACEHOLDERS=true
      shift
      ;;
    -h|--help)
      grep '^#' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
      exit 0
      ;;
    *)
      echo "Unknown argument: $1" >&2
      exit 1
      ;;
  esac
done

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
TARGETS=(
  "reports/phase8/ui/streaming"
  "reports/phase8/ui/diagnostics"
  "reports/phase8/ui/a11y"
  "reports/phase8/ui/contrast"
  "reports/phase8/ui/perf"
  "reports/phase8/ui/catalog"
  "reports/phase8/ui/automation"
)

echo "[phase8] Preparing evidence directories under ${ROOT_DIR}"
for dir in "${TARGETS[@]}"; do
  abs="${ROOT_DIR}/${dir}"
  mkdir -p "${abs}"
  echo "  - ensured ${dir}"
  if [[ "${WITH_PLACEHOLDERS}" == "true" ]]; then
    touch "${abs}/.gitkeep"
  fi
done

echo "[phase8] Done."
