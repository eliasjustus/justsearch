# Observations shard — session cfa87fbc-7be4-4f7f-bab7-f6df7072e627

> Per-session inbox shard (tempdoc 618 Seam C). Append-only; do not share with
> other sessions. Folded into docs/observations.md `## Inbox` by
> `node scripts/agent-analytics/fold-observations.mjs`.

- [ ] check-tempdoc-numbers reports two pre-existing cross-worktree number collisions (#720: memory-injector-plan vs p1a-context-prepend-plan; #729: gjf-removal vs the stale pre-rename copy of 734 in worktree sandbox-validation) — the affected sessions may not know until their own merge-time run — `scripts/ci/check-tempdoc-numbers.mjs` (2026-07-17)
- [ ] Concurrent subagents editing the same worktree make the shared test suite transiently red: a worker running the full suite mid-flight saw 6 `test_check_coverage.py` failures that were another worker's in-progress edits, and logged them as "pre-existing" (they were not; the integrated tree is 213/213 green). Worker briefs should scope the acceptance suite to owned files, or the orchestrator should serialize suite runs -- `scripts/sandbox/` (2026-07-17)
- [ ] Sandbox rounds 5 and 6 archived NO service logs (only round 4 has them, hand-copied) — a host-side investigation into a real search-quality finding had to fall back to round 4's worker.log to answer an ONNX-session question; fixed forward in collect-evidence.ps1 (tempdoc 750), but rounds 5/6's logs are gone for good — `scripts/sandbox/collect-evidence.ps1` (2026-07-17)
- [ ] A session's observation shard can reach `origin/main` inside ANOTHER session's PR: PR #229 branched from local `main` after this session committed its shard, so its squash carried entry 1 upstream under a different commit identity — producing an add/add conflict when this session's own PR later merged `origin/main` (resolved as the union; no loss). Shards are per-session by design but ride whatever branch happens to contain them — `scripts/agent-analytics/note-observation.mjs` (2026-07-17)
