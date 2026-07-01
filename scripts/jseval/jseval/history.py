"""Metric history over time (SQLite-backed) with trend detection.

Tempdoc 400 LR4-h extensions (Phase 3, 2026-04-22):

- ``manifest_hash`` column on ``runs`` — cohort identity from
  ``jseval.manifest.compute_manifest``. Populated on insert and used
  by :func:`check_trend` for cohort-aware windowing.
- ``envelope_metrics`` table — normalized one-row-per-(run, mode, metric)
  shape for non-determinism-envelope σ values. Replaces the original
  tempdoc 400 spec's single-metric flat column (see §26.6 Decision 3).

Schema evolution is **additive + idempotent**: new columns + tables
materialize on first connect; pre-Phase-3 databases keep their rows
and gain ``manifest_hash`` NULL for legacy runs. This matches the
Phase 2.0 identity-fix philosophy — identity widens without breaking
existing artifacts.
"""

from __future__ import annotations

import logging
import sqlite3
import statistics
from pathlib import Path

log = logging.getLogger(__name__)

_ENVELOPE_METRICS_SCHEMA = """
CREATE TABLE IF NOT EXISTS envelope_metrics (
    run_id INTEGER NOT NULL,
    cohort_hash TEXT NOT NULL,
    mode TEXT NOT NULL,
    metric TEXT NOT NULL,
    mean REAL NOT NULL,
    stdev REAL NOT NULL,
    n INTEGER NOT NULL,
    calibrated_at TEXT NOT NULL,
    PRIMARY KEY (run_id, mode, metric),
    FOREIGN KEY (run_id) REFERENCES runs(id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_envelope_cohort
    ON envelope_metrics(cohort_hash);
CREATE INDEX IF NOT EXISTS idx_envelope_metric
    ON envelope_metrics(metric, mode);
"""

_RUNS_TABLE_SCHEMA = """
CREATE TABLE IF NOT EXISTS runs (
    id INTEGER PRIMARY KEY,
    timestamp TEXT NOT NULL,
    git_sha TEXT,
    dataset TEXT NOT NULL,
    mode TEXT NOT NULL,
    ndcg_10 REAL, map_10 REAL, mrr_10 REAL, recall_10 REAL, p1 REAL,
    mean_latency_ms REAL, context_hit_rate REAL,
    ce_p50_ms REAL, primary_docs_s REAL, enrich_docs_s REAL, resident_bytes REAL,
    retrieval_p50_ms REAL, unaccounted_p50_ms REAL,
    comparable INTEGER NOT NULL DEFAULT 1,
    manifest_hash TEXT
);
"""

_RUNS_INDEXES_SCHEMA = """
CREATE INDEX IF NOT EXISTS idx_runs_dataset_mode
    ON runs(dataset, mode, timestamp);
CREATE INDEX IF NOT EXISTS idx_runs_manifest_hash
    ON runs(manifest_hash);
"""

_REGRESSION_THRESHOLD = 0.95  # warn if latest < 95% of window mean

# tempdoc 640 R3: perf families trended alongside quality. Additive columns on `runs` (mirrors the
# manifest_hash migration). `_TREND_METRIC` maps a public metric name -> (column, lower_is_better) so
# check_trend can trend any family direction-aware (nDCG higher-better; latency/footprint lower-better).
# tempdoc 647: the latency-decomposition stages join the trend set — `retrieval_p50_ms` and, most
# importantly, `unaccounted_p50_ms` (the CPT "unaccounted" remainder) so *dark-latency creep over
# commits* is catchable on the trend path, without needing a calibrate-cohort gate.
_PERF_HISTORY_COLUMNS = (
    "ce_p50_ms", "primary_docs_s", "enrich_docs_s", "resident_bytes",
    "retrieval_p50_ms", "unaccounted_p50_ms",
)
_TREND_METRIC: dict[str, tuple[str, bool]] = {
    "nDCG@10": ("ndcg_10", False),
    "ce_p50_ms": ("ce_p50_ms", True),
    "primary_docs_s": ("primary_docs_s", False),
    "enrich_docs_s": ("enrich_docs_s", False),
    "resident_bytes": ("resident_bytes", True),
    "retrieval_p50_ms": ("retrieval_p50_ms", True),
    "unaccounted_p50_ms": ("unaccounted_p50_ms", True),
}


def append_run(
    summary: dict,
    mode: str,
    aggregate_metrics: dict[str, float],
    comparable: bool,
    output_dir: Path,
    *,
    mean_latency_ms: float | None = None,
    context_hit_rate: float | None = None,
    manifest_hash: str | None = None,
    envelope: dict | None = None,
    perf_metrics: dict | None = None,
) -> int:
    """Append a run summary to the SQLite history database.

    Returns the newly inserted run's ``id`` (SQLite lastrowid), which
    callers can use to cross-reference :func:`envelope_metrics` rows.

    When ``envelope`` is provided, one row per (mode, metric) is also
    written into ``envelope_metrics`` sourced from
    ``envelope['metrics'][mode]``. The envelope document must follow
    the schema produced by :func:`jseval.calibrate.compute_envelope`
    (schema_version 1): ``{cohort_hash, metrics: {mode: {metric:
    {mean, stdev, n}}}, calibrated_at, ...}``.
    """
    db_path = _db_path(output_dir)
    with _connect(db_path) as conn:
        pm = perf_metrics or {}
        cursor = conn.execute(
            """INSERT INTO runs
               (timestamp, git_sha, dataset, mode,
                ndcg_10, map_10, mrr_10, recall_10, p1,
                mean_latency_ms, context_hit_rate,
                ce_p50_ms, primary_docs_s, enrich_docs_s, resident_bytes,
                retrieval_p50_ms, unaccounted_p50_ms,
                comparable, manifest_hash)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            (
                summary.get("timestamp"),
                summary.get("git_sha"),
                summary.get("dataset"),
                mode,
                aggregate_metrics.get("nDCG@10"),
                aggregate_metrics.get("AP@10"),
                aggregate_metrics.get("RR@10"),
                aggregate_metrics.get("R@10"),
                aggregate_metrics.get("P@1"),
                mean_latency_ms,
                context_hit_rate,
                pm.get("ce_p50_ms"),
                pm.get("primary_docs_s"),
                pm.get("enrich_docs_s"),
                pm.get("resident_bytes"),
                pm.get("retrieval_p50_ms"),
                pm.get("unaccounted_p50_ms"),
                1 if comparable else 0,
                manifest_hash,
            ),
        )
        run_id = cursor.lastrowid
        inserted, skipped = _insert_envelope_metrics(conn, run_id, mode, envelope)
        if inserted or skipped:
            # Phase 6 / 6.11: log the envelope-metrics write outcome so
            # operators can see when rows were dropped due to incomplete
            # envelope shape (previously silent).
            log.debug(
                "envelope_metrics: run_id=%d mode=%s inserted=%d skipped=%d",
                run_id, mode, inserted, skipped,
            )
        return run_id


def _insert_envelope_metrics(
    conn: sqlite3.Connection,
    run_id: int,
    mode: str,
    envelope: dict | None,
) -> tuple[int, int]:
    """Populate envelope_metrics rows for ``(run_id, mode, *)``.

    Returns ``(inserted, skipped)`` — how many metric rows landed
    in the table vs how many were dropped because mean/stdev/n were
    missing or the envelope shape was incomplete. Phase 6 / 6.11
    replaces the prior silent-drop behavior so callers can log the
    discrepancy.

    Silent-skip conditions:

    - ``envelope`` is None or not a dict → ``(0, 0)``.
    - ``envelope['metrics'][mode]`` is missing (cohort not calibrated
      in this mode) → ``(0, 0)``.
    - ``cohort_hash`` or ``calibrated_at`` missing → ``(0, 0)``.
    - individual metric rows with missing mean/stdev/n → counted as
      skipped.

    Uses ``INSERT OR REPLACE`` for idempotence: re-ingesting an
    identical envelope produces the same row set.

    Phase 6 / 6.11: ``run_id`` is now written as INTEGER (schema type
    matches ``runs.id INTEGER``). SQLite loose-typing makes legacy
    TEXT values still JOIN when cast explicitly; new writes are
    properly typed.
    """
    if not isinstance(envelope, dict):
        return 0, 0
    cohort_hash = envelope.get("cohort_hash")
    metrics_block = envelope.get("metrics") or {}
    calibrated_at = envelope.get("calibrated_at")
    if not cohort_hash or not calibrated_at:
        return 0, 0
    per_mode = metrics_block.get(mode)
    if not isinstance(per_mode, dict):
        return 0, 0
    rows: list[tuple] = []
    skipped = 0
    for metric_name, stats in per_mode.items():
        if not isinstance(stats, dict):
            skipped += 1
            continue
        mean = stats.get("mean")
        stdev = stats.get("stdev")
        n = stats.get("n")
        if mean is None or stdev is None or n is None:
            skipped += 1
            continue
        rows.append((
            int(run_id), cohort_hash, mode, metric_name,
            float(mean), float(stdev), int(n), calibrated_at,
        ))
    if not rows:
        return 0, skipped
    conn.executemany(
        """INSERT OR REPLACE INTO envelope_metrics
           (run_id, cohort_hash, mode, metric, mean, stdev, n, calibrated_at)
           VALUES (?, ?, ?, ?, ?, ?, ?, ?)""",
        rows,
    )
    return len(rows), skipped


def get_history(
    dataset: str,
    mode: str,
    output_dir: Path,
    limit: int = 20,
    *,
    manifest_hash: str | None = None,
) -> list[dict]:
    """Get recent runs for a dataset/mode, most recent first.

    When ``manifest_hash`` is provided, results are additionally
    filtered to rows matching that cohort. Callers that want a
    cross-cohort view should pass ``manifest_hash=None``.
    """
    db_path = _db_path(output_dir)
    if not db_path.exists():
        return []

    sql = (
        "SELECT id, timestamp, git_sha, dataset, mode, "
        "ndcg_10, map_10, mrr_10, recall_10, p1, "
        "mean_latency_ms, context_hit_rate, "
        "ce_p50_ms, primary_docs_s, enrich_docs_s, resident_bytes, "
        "retrieval_p50_ms, unaccounted_p50_ms, "
        "comparable, manifest_hash "
        "FROM runs "
        "WHERE dataset = ? AND mode = ?"
    )
    params: list = [dataset, mode]
    if manifest_hash is not None:
        sql += " AND manifest_hash = ?"
        params.append(manifest_hash)
    sql += " ORDER BY timestamp DESC LIMIT ?"
    params.append(limit)

    with _connect(db_path) as conn:
        cursor = conn.execute(sql, params)
        columns = [desc[0] for desc in cursor.description]
        return [dict(zip(columns, row)) for row in cursor.fetchall()]


def get_envelope_metrics(
    output_dir: Path,
    *,
    cohort_hash: str | None = None,
    metric: str | None = None,
    mode: str | None = None,
    limit: int = 100,
) -> list[dict]:
    """Return rows from ``envelope_metrics`` with optional filters.

    Useful for LR4-b bootstrap CI / LR4-g drift projections that want
    to weight results against the current cohort's calibrated σ.
    """
    db_path = _db_path(output_dir)
    if not db_path.exists():
        return []

    clauses: list[str] = []
    params: list = []
    if cohort_hash is not None:
        clauses.append("cohort_hash = ?")
        params.append(cohort_hash)
    if metric is not None:
        clauses.append("metric = ?")
        params.append(metric)
    if mode is not None:
        clauses.append("mode = ?")
        params.append(mode)
    where = f" WHERE {' AND '.join(clauses)}" if clauses else ""
    params.append(limit)

    sql = (
        "SELECT run_id, cohort_hash, mode, metric, mean, stdev, n, "
        "calibrated_at FROM envelope_metrics"
        f"{where} ORDER BY calibrated_at DESC LIMIT ?"
    )
    with _connect(db_path) as conn:
        cursor = conn.execute(sql, params)
        columns = [desc[0] for desc in cursor.description]
        return [dict(zip(columns, row)) for row in cursor.fetchall()]


def check_trend(
    dataset: str,
    mode: str,
    output_dir: Path,
    *,
    manifest_hash: str | None = None,
    metric: str = "nDCG@10",
) -> dict:
    """Check if latest run shows regression against historical window.

    When ``manifest_hash`` is provided, the window is restricted to
    runs in the same cohort (i.e. ``manifest_hash`` match) — this is
    the §26.6 Decision 3 "cohort-aware" semantic. When omitted, the
    legacy cross-cohort window is preserved for backwards
    compatibility.

    ``metric`` selects which family is trended (tempdoc 640 R3): ``nDCG@10``
    (default, higher-is-better) or a perf family (``ce_p50_ms`` /
    ``primary_docs_s`` / ``enrich_docs_s`` / ``resident_bytes``). The
    regression direction follows the metric (latency/footprint are
    lower-is-better, so a regression is an *increase*). Rows with a NULL
    value for the chosen metric (e.g. legacy runs pre-dating the perf
    columns) are skipped, so a perf trend reports ``insufficient_data``
    until enough perf-carrying runs accrue.

    Returns a dict with 'status' ('ok', 'regression', 'insufficient_data')
    and diagnostic details.
    """
    column, lower = _TREND_METRIC.get(metric, ("ndcg_10", False))
    rows = get_history(dataset, mode, output_dir, limit=20,
                       manifest_hash=manifest_hash)
    comparable = [
        r for r in rows
        if r.get("comparable") and isinstance(r.get(column), (int, float))
    ]

    if len(comparable) < 2:
        result: dict = {"status": "insufficient_data", "metric": metric,
                        "comparable_runs": len(comparable)}
        if manifest_hash is not None:
            result["manifest_hash"] = manifest_hash
        return result

    latest = comparable[0]
    window = comparable[1:6]  # up to 5 previous runs

    if not window:
        return {"status": "insufficient_data", "metric": metric, "comparable_runs": 1}

    latest_val = float(latest[column])
    window_values = [float(r[column]) for r in window]
    window_mean = statistics.mean(window_values)

    result = {
        "metric": metric,
        "latest_value": latest_val,
        "window_mean": round(window_mean, 6),
        "lower_is_better": lower,
        "window_size": len(window),
        "total_comparable": len(comparable),
    }
    if metric == "nDCG@10":  # back-compat keys for existing consumers
        result["latest_ndcg_10"] = latest_val
        result["window_mean_ndcg_10"] = round(window_mean, 6)
    if manifest_hash is not None:
        result["manifest_hash"] = manifest_hash

    def _regressed(latest_v: float, ref_mean: float) -> bool:
        # direction-aware: lower-is-better regresses by INCREASING past the ref;
        # higher-is-better regresses by FALLING below it (the legacy nDCG behaviour).
        if ref_mean <= 0:
            return False
        return (latest_v > ref_mean / _REGRESSION_THRESHOLD) if lower \
            else (latest_v < ref_mean * _REGRESSION_THRESHOLD)

    # N < 8: percentage threshold
    if len(comparable) < 8:
        if _regressed(latest_val, window_mean):
            result["status"] = "regression"
            bound = window_mean / _REGRESSION_THRESHOLD if lower else window_mean * _REGRESSION_THRESHOLD
            result["detail"] = (
                f"{metric} {latest_val:.4g} {'>' if lower else '<'} {bound:.4g} "
                f"(vs window mean {window_mean:.4g})"
            )
        else:
            result["status"] = "ok"
        return result

    # N >= 8: Student's t-test
    from scipy import stats as sp_stats

    baseline = [float(r[column]) for r in comparable[1:]]
    t_stat, p_value = sp_stats.ttest_1samp(baseline, latest_val)
    result["t_stat"] = round(t_stat, 4)
    result["p_value"] = round(p_value, 4)

    baseline_mean = statistics.mean(baseline)
    worse = (latest_val > baseline_mean) if lower else (latest_val < baseline_mean)
    if p_value < 0.05 and worse:
        result["status"] = "regression"
        result["detail"] = (
            f"t-test p={p_value:.4f} (significant), latest "
            f"{'above' if lower else 'below'} baseline mean"
        )
    else:
        result["status"] = "ok"

    return result


# ---------------------------------------------------------------------------
# Internal
# ---------------------------------------------------------------------------

def _db_path(output_dir: Path) -> Path:
    return output_dir / "eval-history.db"


def _connect(db_path: Path) -> sqlite3.Connection:
    db_path.parent.mkdir(parents=True, exist_ok=True)
    conn = sqlite3.connect(str(db_path))
    # Phase 6 / 6.11: enable foreign-key enforcement. SQLite ships with
    # FKs off by default — they must be turned on per-connection for
    # ON DELETE CASCADE on envelope_metrics.run_id to fire.
    conn.execute("PRAGMA foreign_keys = ON")
    # Table first (no-op on pre-existing), ALTER next (legacy DBs gain
    # manifest_hash), indexes last (idx_runs_manifest_hash needs the
    # column to exist).
    conn.executescript(_RUNS_TABLE_SCHEMA)
    _migrate_runs_manifest_hash(conn)
    _migrate_runs_perf_columns(conn)
    conn.executescript(_RUNS_INDEXES_SCHEMA)
    conn.executescript(_ENVELOPE_METRICS_SCHEMA)
    return conn


def _migrate_runs_manifest_hash(conn: sqlite3.Connection) -> None:
    """Add ``manifest_hash`` to a pre-existing ``runs`` table.

    ``CREATE TABLE IF NOT EXISTS`` does not re-evaluate the column list
    on a pre-existing table, so databases created before LR4-h need an
    explicit ``ALTER TABLE``. Idempotent: only executes when the column
    is absent.
    """
    existing = {row[1] for row in conn.execute("PRAGMA table_info(runs)")}
    if "manifest_hash" not in existing:
        conn.execute("ALTER TABLE runs ADD COLUMN manifest_hash TEXT")


def _migrate_runs_perf_columns(conn: sqlite3.Connection) -> None:
    """Add the tempdoc 640 R3 perf-family columns to a pre-existing ``runs`` table.

    Same idempotent ``ALTER TABLE ADD COLUMN`` pattern as :func:`_migrate_runs_manifest_hash`
    (``CREATE TABLE IF NOT EXISTS`` does not add columns to an existing table). Legacy rows keep
    NULL for these columns; new runs populate them via :func:`append_run`'s ``perf_metrics``.
    """
    existing = {row[1] for row in conn.execute("PRAGMA table_info(runs)")}
    for col in _PERF_HISTORY_COLUMNS:
        if col not in existing:
            conn.execute(f"ALTER TABLE runs ADD COLUMN {col} REAL")
