# Phase 13 - Paging evidence

This folder holds the deterministic paging harness outputs that back the Phase 13 acceptance bullets.

- `linux-dev/iteration-*.json` - raw `SearchDump` responses captured via `scripts/phase13/paging-matrix.sh`.
- `repeatability.json` - aggregated hashes + doc_id/PIT summaries per platform. The harness now records doc ordering, cursor extras, and commit metadata so regressions can be diffed quickly.

To refresh the evidence after seeding the local data dir:

```bash
scripts/phase13/paging-matrix.sh \
  --platform=$(uname -s)-dev \
  --data-dir=~/.justsearch-smoke \
  --query="phase13 repeatability" \
  --page=1
```

The script runs the `:modules:app-services:runSearchDump` Gradle task 20 times (with `JUSTSEARCH_LLM_ENABLED=false` to keep translator stages disabled), stores each JSON output under `reports/phase13/paging/<platform>/iteration-N.json`, and rewrites `repeatability.json` with doc-order and PIT consistency checks. The report is meant to be checked into the repo (for traceability) and uploaded as a CI artifact whenever Phase 13 verification is triggered. CI runners can piggyback on `scripts/test-support/run-matrix.sh --phase13-paging` to keep evidence fresh whenever the golden matrix executes.
