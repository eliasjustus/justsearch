#!/usr/bin/env bash
set -euo pipefail

show_help() {
  cat <<'EOF'
Usage: scripts/test-support/publish-artifacts.sh --source=<dir> --dest=<dir> --timestamp=<iso8601>

Required flags:
  --source       Directory containing freshly generated goldens (usually reports/phase10/goldens/<ts>).
  --dest         Destination directory for nightly snapshots (e.g. reports/phase10/goldens/nightly).
  --timestamp    ISO-8601 timestamp recorded in nightly-index.json (e.g. 2025-10-24T02-00-00Z).
EOF
}

SOURCE=""
DEST=""
TIMESTAMP=""

for arg in "$@"; do
  case $arg in
    --source=*)
      SOURCE="${arg#*=}"
      ;;
    --dest=*)
      DEST="${arg#*=}"
      ;;
    --timestamp=*)
      TIMESTAMP="${arg#*=}"
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

if [[ -z "$SOURCE" || -z "$DEST" || -z "$TIMESTAMP" ]]; then
  echo "Missing required arguments." >&2
  show_help >&2
  exit 64
fi

REPO_ROOT="$(git rev-parse --show-toplevel 2>/dev/null || pwd)"

abspath() {
  python3 - "$REPO_ROOT" "$1" <<'PY'
import sys
from pathlib import Path
root = Path(sys.argv[1]).resolve()
target = Path(sys.argv[2])
if not target.is_absolute():
    target = (root / target).resolve()
print(target)
PY
}

relpath() {
  python3 - "$REPO_ROOT" "$1" <<'PY'
import sys
from pathlib import Path
root = Path(sys.argv[1]).resolve()
target = Path(sys.argv[2]).resolve()
print(target.relative_to(root))
PY
}

SOURCE_ABS="$(abspath "$SOURCE")"
DEST_ABS="$(abspath "$DEST")"
TARGET_DIR="${DEST_ABS}/${TIMESTAMP}"

if [[ ! -d "$SOURCE_ABS" ]]; then
  echo "Source directory does not exist: $SOURCE" >&2
  exit 1
fi

rm -rf "$TARGET_DIR"
mkdir -p "$TARGET_DIR"
cp -R "$SOURCE_ABS"/. "$TARGET_DIR"/

REPORT_TOOL="${REPO_ROOT}/tools/goldens/report.py"
python3 "$REPORT_TOOL" --input "$TARGET_DIR" --output "$TARGET_DIR/diff-report.html"

INDEX_PATH="$(dirname "$DEST_ABS")/nightly-index.json"
TARGET_REL="$(relpath "$TARGET_DIR")"
python3 "$REPO_ROOT/scripts/test-support/update_nightly_index.py" \
  "$INDEX_PATH" "$TIMESTAMP" "$TARGET_REL"

echo "[publish-artifacts] published ${TARGET_REL}"
