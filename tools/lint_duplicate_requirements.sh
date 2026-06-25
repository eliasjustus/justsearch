#!/usr/bin/env bash
set -euo pipefail

fail=0
# within-file exact duplicate detector (normalized)
while IFS= read -r file; do
  mapfile -t lines < <(nl -ba "$file" \
    | rg '^\s*\d+\s*[-*]\s*(MUST|MUST NOT|SHOULD|SHALL)\b' -n \
    | sed -E 's/^([[:space:]]*[0-9]+)[[:space:]]*/\1:/')

  declare -A seen=()
  for row in "${lines[@]}"; do
    ln=${row%%:*}; rest=${row#*:}
    norm=$(echo "$rest" \
      | sed -E 's/`[^`]+`/<CODE>/g' \
      | tr '[:upper:]' '[:lower:]' \
      | tr -s ' ' \
      | sed -E 's/[[:space:]]+$//' \
      | sed -E 's/[[:punct:]]$//')
    key="$norm"
    if [[ -n "${seen[$key]:-}" ]]; then
      echo "::error file=$file,line=$ln::Duplicate normative bullet (within file): $norm (first at ${seen[$key]})"
      fail=1
    else
      seen[$key]="$ln"
    fi
  done
  unset seen
done < <(rg -l --glob '!**/_generated/**' -g 'docs/**/*.md' '^\s*[-*]\s*(MUST|MUST NOT|SHOULD|SHALL)\b')

exit $fail


