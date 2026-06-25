---
title: Hot-Reload Product Features (Config / SSOT / Index Path)
type: tempdocs
status: open
---

# 416 — Hot-Reload Product Features (Config / SSOT / Index Path)

## Status

**OPEN.** Created 2026-04-25. Product-feature follow-up to tempdoc 406's
shipped substrate. The lifecycle pattern + admin reload endpoint shipped
by 406 are the structural prerequisites; this tempdoc proposes the
*user-visible features* they unblock.

## Context

Three "future features" listed in
`docs/future-features/service-identity-lifecycle-pattern.md:95-97` are
all variants of "edit something → no restart required":

1. **Hot config reload (no process restart)** — runtime config changes
   (cache sizes, thread pools, timeouts) without restart
2. **Hot SSOT reload (synonyms / analyzers / field catalog)** — search
   quality changes without re-ingest or restart
3. **In-place index path migration (move data dir)** — relocate
   `JUSTSEARCH_DATA_DIR` without losing index state

All three were *blocked* before 406 by the lack of a holder-swap pattern
on `LuceneRuntime`. After 406 + the gRPC `ReloadRuntime` rpc + the
`POST /api/admin/runtime/reload` endpoint, the substrate exists. The
trigger mechanism (filesystem watcher, REST call from UI, etc.) and the
config-change-detection logic are the remaining work.

This tempdoc groups all three because they share the same trigger
mechanism (config-change → admin reload) and would benefit from being
designed together so the trigger logic isn't built three times.

## Common architecture

```
[Config source] -> [Watcher / change detector] -> [Coalescer] -> [Admin reload via internal API call]
     ^                       ^                        ^                       ^
  ssot/*.json,         filesystem watch         debounce so a            uses the gRPC
  application.yaml,    or polled config         5-line YAML edit          ReloadRuntime
  data dir env var     stream provider          doesn't trigger 5         shipped by 406
                                                reloads
```

The config-change-to-reload path is the same for all three; only the
*config source* differs. Per-feature work is the watcher implementation
+ the change-detection predicate (don't reload if nothing semantically
changed).

## Per-feature scope

### 416-A — Hot config reload

**Source**: `application.yaml` + env vars (resolved by
`ResolvedConfigBuilder`).

**Trigger**: filesystem watch on `application.yaml`. Env-var changes
won't surface (process env is fixed at start) — that's a known
limitation; document and accept.

**Change detection**: re-resolve `ResolvedConfig` from disk; diff
against current; if any field that affects runtime substrate differs,
trigger reload. Many config keys don't affect the runtime (UI settings,
log levels) — those should not trigger reload.

**Per-key impact taxonomy** (proposed; verify against actual config
keys in `EnvRegistry.java` and `ConfigKey.java`):

| Key category | Reload required? |
|---|---|
| Lucene tuning (RAM buffer, commit interval) | Yes — `index.runtime` rebuild |
| Embedding model selection | Yes — see 413 (embedding service reload) |
| LLM model selection | Yes — see 412 (inference reload) |
| Logging level | No — already hot-changeable via `/api/debug/log-level` |
| UI settings | No — Head-only, no Worker round-trip |
| Telemetry config | Maybe — case-by-case |

**Out of scope**: per-user config (vs. system config). The current
config is single-user single-machine, so this distinction doesn't yet
exist.

### 416-B — Hot SSOT reload

**Source**: `SSOT/catalogs/*.json` (synonyms, fields, analyzers,
boosts).

**Trigger**: filesystem watch on the catalog directory.

**Change detection**: easy — every catalog has a fingerprint hash that
appears in commit metadata. Compare current loaded fingerprint vs. file
fingerprint; if different, reload.

**Special case — Field catalog changes are NOT a hot reload**: adding
a new field to `fields.v1.json` doesn't update existing documents (per
CLAUDE.md "Stale index after field changes"). The reload only re-binds
the runtime to the new catalog; existing docs still lack the new field.
Operator must run `jseval run --reset` for full effect. Document this
clearly in the user-facing message ("synonyms updated; for field
changes, please re-index").

**Per-catalog impact**:
- `synonyms.json` — affects search-time analyzer chain. Hot reload =
  immediate effect on subsequent queries.
- `analyzers.json` — affects both index-time and search-time. Hot
  reload affects search-time only; index-time changes need re-ingest.
- `fields.v1.json` — see "Special case" above.
- `boosts.json` — affects rerank weights. Hot reload = immediate.

**Operator UX**: if Worker is mid-ingest when a synonyms change is
detected, the reload should wait for the current batch to drain (which
is exactly what `RunningRuntime.drainAndClose` already does — perfect
fit). The 406 substrate handles this.

### 416-C — In-place index path migration

**Source**: `JUSTSEARCH_DATA_DIR` env var (or equivalent config key).

**Trigger**: explicit operator action — `POST /api/admin/runtime/migrate-data-dir`
with body `{"newPath": "..."}`. NOT a watcher (file moves are
intentional, not file-edits).

**Mechanism**: extension of `swapRuntime` to take a target path arg,
with a pre-step that copies/moves the data directory:

```java
public synchronized void migrateDataDir(Path newDataDir, String reason) {
  // 1. Drain current runtime
  // 2. Close I/O handles on old dir
  // 3. Atomic rename or copy old dir contents to new dir
  // 4. Re-open runtime on new dir
  // 5. Update JUSTSEARCH_DATA_DIR env (or config) for restarts
}
```

**Risk**: this is the most destructive of the three features. Wants:
- A "dry run" mode that validates the target path is suitable
- A rollback if the new-dir open fails
- Locking so concurrent calls don't race
- Clear UX: "moving 5 GB index... estimated 2 minutes"

**Out of scope for V1**: cross-volume moves (different filesystems —
need actual file copy, not rename), incremental migrations.

## Critical files (cross-cutting)

**New (one watcher service):**
- `modules/configuration/src/main/java/.../ConfigChangeWatcher.java` (or similar) — single watcher service that handles 416-A and 416-B
- `modules/ui/src/main/java/.../api/AdminMigrateDataDirController.java` (for 416-C)

**Modified (likely):**
- `modules/configuration/src/main/java/.../ResolvedConfigBuilder.java` — diff-detection helper
- `modules/indexer-worker/src/main/java/.../KnowledgeServer.java` — `migrateDataDir` helper alongside `swapRuntime`
- `modules/ipc-common/src/main/proto/indexing.proto` — `MigrateDataDir` rpc
- `modules/ui/src/main/java/.../api/LocalApiServer.java` — admin endpoints
- `modules/ui-web/src/components/views/SettingsView.tsx` — operator UX surface (a "Reload" button + a "Move data dir" workflow)

## Out of scope (defer to follow-ups)

- File-watcher-triggered reload as a *default* behavior. V1 of 416-A and
  416-B should be opt-in (env flag or config key) — auto-reload on YAML
  change is a real product behavior, not just instrumentation. Some
  ops teams consider auto-reload risky.
- Per-runtime A/B testing of catalog changes. ("Run new synonyms.json
  on 10% of queries.") Different feature class.
- Schema migration tooling (when `fields.v1.json` adds a field that
  needs backfill of historical docs). Tempdoc-worthy in its own right.

## Sequencing

This is materially larger than 412–415. Estimate: ~2 weeks total, but
splittable.

**Recommended ordering:**

1. **416-B (Hot SSOT reload) first** (~3 days). Highest user-visible
   payoff (search quality changes are visible per-query). Lowest risk
   (catalog changes don't move data). Best testbed for the watcher
   architecture.
2. **416-A (Hot config reload) second** (~4 days). Builds on the
   watcher; needs the per-key impact taxonomy + safe re-resolution
   logic. More complex change-detection.
3. **416-C (In-place migration) third** (~5 days). Most invasive.
   Needs the data-move logic, rollback, UX flow. Best done after the
   admin endpoint patterns are battle-tested by A and B.

Each can ship independently as a separate PR.

## Dependencies

- **Hard dep on 406 (shipped)**: the holder-swap pattern + admin reload
  endpoint are prerequisites.
- **Soft dep on 412/413**: if hot-reload of model config (416-A) needs
  to swap the inference or embedding model, the admin endpoints from
  412 and 413 should exist (or 416 builds them).
- Mostly independent of 414 and 415.

## Validation gates

For each feature:

- **416-A**: edit application.yaml to change `lucene.commit.interval_ms`;
  verify next commit uses the new interval; verify
  `index.runtime.swap_started_total{reason="config_reload"}` in NDJSON.
- **416-B**: edit `SSOT/catalogs/synonyms.json` to add a new synonym;
  query a doc that hits the new synonym; verify result matches; verify
  `index.runtime.swap_started_total{reason="ssot_reload"}` in NDJSON.
- **416-C**: trigger `POST /api/admin/runtime/migrate-data-dir`; verify
  index returns from new path; verify pre-migration data still
  searchable; verify `index.runtime.swap_started_total{reason="data_dir_migration"}`
  in NDJSON.
