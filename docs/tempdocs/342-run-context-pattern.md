---
title: "342: Ingest Config Pattern for Parameter Threading"
type: tempdoc
status: done
created: 2026-03-22
---

> NOTE: Noncanonical doc (architecture). May drift.

# 342: Ingest Config Pattern for Parameter Threading

## Problem

Adding a parameter to a deep call chain requires updating every
intermediate function signature. In tempdoc 335 item 16, adding
`process_check` (a callback to detect backend crashes) required
changes to 4 function signatures:

```
cmd_run → _do_run → prepare_corpus → ingest_and_wait
                                    → wait_pipeline_complete
                                    → _poll_until_stable
```

Missing `prepare_corpus` (the wrapper) caused a runtime crash on
the first verification run.

**Known bug:** `wait_index_idle` accepts `process_check` in its
signature but silently drops it — never forwards it to
`_poll_until_stable` (line 78-81 of `readiness.py`). The
backend-crash guard is inactive when `pipeline=False`.

## Investigation Findings

### Caller map (verified)

| Function | Production callers | Test callers |
|---|---|---|
| `prepare_corpus` | `cli.py:_do_run` (all kwargs) | 3 in `test_ingest.py` (positional, minimal) |
| `ingest_and_wait` | `ingest.py:prepare_corpus` (10 kwargs), **`ingest_bench.py`** (4 kwargs) | Mocked only |
| `wait_index_idle` | `ingest.py:ingest_and_wait` (single caller) | Not called directly |
| `wait_pipeline_complete` | `ingest.py:ingest_and_wait` (single caller) | Not called directly |
| `execute_run` | `cli.py:_do_run` (15 kwargs), `cli.py:cmd_requery` (13 kwargs) | 4 in `test_run.py` (positional) |

### Key constraints

1. **`ingest_bench.py`** calls `ingest_and_wait` directly with only
   4 kwargs (no pipeline/timeline/process_check). Any config object
   must work for this minimal caller too.

2. **`execute_run` has 17 params but shallow forwarding** — its
   internal calls are flat (readiness: 4 params, retriever: 7).
   It does NOT have the deep-threading problem. Out of scope.

3. **`_do_run` has exactly one caller** (`cmd_run`). Its width is
   not a maintenance problem — the forwarding chain below it is.

4. **Parameter overlap analysis** — the ingest and query phases
   share `base_url`, `embedding`/`dense_enabled`, `splade_enabled`
   but diverge significantly. A full `RunContext` holding query
   params (`modes`, `top_k`, `lambdamart`, `thresholds`, etc.)
   would over-couple the ingest chain to concerns it never uses.

### Design decision: `IngestConfig` (scoped), not `RunContext` (god object)

A full `RunContext` covering all 19 `_do_run` params would:
- Give `prepare_corpus` access to `top_k`, `lambdamart`, `thresholds` (irrelevant)
- Force `ingest_bench.py` to construct an object with 10+ unused query fields
- Be the 19-param `_do_run` signature in dataclass form — no real improvement

Instead, `IngestConfig` holds exactly the parameters shared across
the ingest chain (`prepare_corpus` → `ingest_and_wait` → `wait_*`):

```python
@dataclass
class IngestConfig:
    """Configuration for the ingest/readiness phase of an eval run."""
    base_url: str
    dense_enabled: bool = False
    splade_enabled: bool = False
    pipeline: bool = False
    timeline_path: Path | None = None
    json_mode: bool = False
    process_check: Callable | None = None
    index_timeout_sec: float = 7200.0
```

**What's excluded and why:**
- `dataset`, `corpus_dir` — `prepare_corpus`-specific (kept as explicit params)
- `poll_interval_sec`, `stable_polls_required` — `ingest_and_wait` defaults,
  never set from CLI
- `corpus_doc_count`, `docs_dir` — computed at call time, not configuration
- All query params — different concern, different call chain

### Boundary

```
cmd_run ──→ _do_run ──→ prepare_corpus(dataset, config, corpus_dir)
                    │         │
                    │         └→ ingest_and_wait(config, docs_dir, corpus_doc_count)
                    │               │
                    │               ├→ wait_pipeline_complete(... explicit kwargs ...)
                    │               └→ wait_index_idle(... explicit kwargs ...)
                    │                       └→ _poll_until_stable(... explicit kwargs ...)
                    │
                    └→ execute_run(... stays as-is, individual kwargs ...)
```

`IngestConfig` flows from `_do_run` → `prepare_corpus` → `ingest_and_wait`.
The `wait_*` functions stay with explicit kwargs — they're single-caller
functions and `ingest_and_wait` unpacks config fields for them. The
`process_check` forwarding bug is fixed directly (one-line fix in
`wait_index_idle`).

### Java (worker services)

Already solved. `BackfillContext` (11 fields), `GpuDiagnosticSuppliers`
(5 fields), and `InfraContext` (11 fields) all follow this pattern.
No changes needed.

## Related

- **334** item 3: `CombinedEnrichmentBackfillOps` already uses a
  context record pattern for backfill operations.
- **335** item 16: `process_check` threading exposed the fragility
  of positional parameter chains in jseval.
- **336** (Status Response Composability): related principle —
  group related fields into typed structures.

## Implementation Plan

- [x] 1. Fix `wait_index_idle` bug: forward `process_check` to `_poll_until_stable`
- [x] 2. Define `IngestConfig` dataclass in `scripts/jseval/jseval/types.py`
- [x] 3. Refactor `prepare_corpus` to accept `IngestConfig` + explicit `dataset`/`corpus_dir`
- [x] 4. Refactor `ingest_and_wait` to accept `IngestConfig` + explicit `docs_dir`/`corpus_doc_count`
- [x] 5. Update `_do_run` in `cli.py` to construct `IngestConfig`
- [x] 6. Update `ingest_bench.py` to construct minimal `IngestConfig`
- [x] 7. Update test call sites in `test_ingest.py` and `test_ingest_bench.py`
- [x] 8. Verify: `python -m jseval --help` works, 319/319 tests pass
