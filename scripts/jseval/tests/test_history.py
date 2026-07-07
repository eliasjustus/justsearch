"""Tests for history.py — SQLite metric history (incl. LR4-h)."""

from __future__ import annotations

import sqlite3

import pytest

from jseval.history import (
    append_run,
    check_trend,
    get_envelope_metrics,
    get_history,
)


def _make_summary(dataset="scifact", git_sha="abc123", ts="2026-03-16T14:00:00Z"):
    return {"timestamp": ts, "git_sha": git_sha, "dataset": dataset}


def _append(output_dir, mode="hybrid", ndcg=0.7, comparable=True,
            manifest_hash=None, envelope=None, **kwargs):
    summary = _make_summary(**{k: v for k, v in kwargs.items()
                               if k in ("dataset", "git_sha", "ts")})
    if "ts" in kwargs:
        summary["timestamp"] = kwargs["ts"]
    metrics = {
        "nDCG@10": ndcg, "AP@10": 0.4, "RR@10": 0.6, "R@10": 0.8, "P@1": 0.5,
    }
    return append_run(
        summary, mode, metrics, comparable, output_dir,
        manifest_hash=manifest_hash, envelope=envelope,
    )


def _envelope(cohort_hash="cohort-abc", modes=("hybrid",),
              metrics=("nDCG@10", "P@1"), mean=0.7, stdev=0.001, n=5,
              calibrated_at="2026-04-22T06:00:00Z"):
    """Build a Phase-2-compatible envelope document for test insertion."""
    block = {m: {metric: {"mean": mean, "stdev": stdev, "n": n}
                 for metric in metrics}
             for m in modes}
    return {
        "schema_version": 1,
        "cohort_hash": cohort_hash,
        "calibrated_at": calibrated_at,
        "git_sha": "abc123",
        "n_runs": n,
        "metrics": block,
    }


class TestAppendAndRetrieve:
    def test_append_and_get(self, tmp_path):
        _append(tmp_path, ts="2026-03-16T14:00:00Z", ndcg=0.70)
        _append(tmp_path, ts="2026-03-16T15:00:00Z", ndcg=0.72)
        _append(tmp_path, ts="2026-03-16T16:00:00Z", ndcg=0.71)

        rows = get_history("scifact", "hybrid", tmp_path)
        assert len(rows) == 3
        # Most recent first
        assert rows[0]["ndcg_10"] == 0.71
        assert rows[2]["ndcg_10"] == 0.70

    def test_creates_table_on_first_use(self, tmp_path):
        db_path = tmp_path / "eval-history.db"
        assert not db_path.exists()

        _append(tmp_path)
        assert db_path.exists()

        rows = get_history("scifact", "hybrid", tmp_path)
        assert len(rows) == 1

    def test_filter_by_dataset_mode(self, tmp_path):
        _append(tmp_path, dataset="scifact", mode="hybrid")
        _append(tmp_path, dataset="scifact", mode="lexical")
        _append(tmp_path, dataset="nfcorpus", mode="hybrid")

        rows = get_history("scifact", "hybrid", tmp_path)
        assert len(rows) == 1

    def test_empty_history(self, tmp_path):
        rows = get_history("scifact", "hybrid", tmp_path)
        assert rows == []


class TestCheckTrend:
    def test_insufficient_data(self, tmp_path):
        _append(tmp_path, ndcg=0.7)
        result = check_trend("scifact", "hybrid", tmp_path)
        assert result["status"] == "insufficient_data"

    def test_regression_detected(self, tmp_path):
        # 5 good runs then one bad
        for i in range(5):
            _append(tmp_path, ts=f"2026-03-{10+i}T14:00:00Z", ndcg=0.70)
        _append(tmp_path, ts="2026-03-16T14:00:00Z", ndcg=0.60)  # 14% drop

        result = check_trend("scifact", "hybrid", tmp_path)
        assert result["status"] == "regression"

    def test_ok_trend(self, tmp_path):
        for i in range(5):
            _append(tmp_path, ts=f"2026-03-{10+i}T14:00:00Z", ndcg=0.70)
        _append(tmp_path, ts="2026-03-16T14:00:00Z", ndcg=0.69)

        result = check_trend("scifact", "hybrid", tmp_path)
        assert result["status"] == "ok"

    def test_non_comparable_excluded(self, tmp_path):
        _append(tmp_path, ts="2026-03-10T14:00:00Z", ndcg=0.70, comparable=True)
        _append(tmp_path, ts="2026-03-11T14:00:00Z", ndcg=0.30, comparable=False)
        _append(tmp_path, ts="2026-03-12T14:00:00Z", ndcg=0.69, comparable=True)

        result = check_trend("scifact", "hybrid", tmp_path)
        assert result["status"] == "ok"


class TestManifestHashColumn:
    """LR4-h: manifest_hash on runs table."""

    def test_manifest_hash_persists_and_returns(self, tmp_path):
        _append(tmp_path, manifest_hash="cohort-xyz")
        rows = get_history("scifact", "hybrid", tmp_path)
        assert rows[0]["manifest_hash"] == "cohort-xyz"

    def test_manifest_hash_null_for_legacy_insertions(self, tmp_path):
        _append(tmp_path)  # no manifest_hash
        rows = get_history("scifact", "hybrid", tmp_path)
        assert rows[0]["manifest_hash"] is None

    def test_manifest_hash_index_exists(self, tmp_path):
        """Asserting the index is actually created (not just named in SQL)."""
        _append(tmp_path, manifest_hash="c")
        with sqlite3.connect(str(tmp_path / "eval-history.db")) as conn:
            indexes = {row[0] for row in conn.execute(
                "SELECT name FROM sqlite_master WHERE type='index'"
            )}
        assert "idx_runs_manifest_hash" in indexes


class TestCheckTrendCohortAware:
    """LR4-h: check_trend honours manifest_hash filter (§26.6 Decision 3)."""

    def _seed_two_cohorts(self, tmp_path):
        # Cohort A: 5 runs at 0.70
        for i in range(5):
            _append(tmp_path, ts=f"2026-03-{10+i}T14:00:00Z",
                    ndcg=0.70, manifest_hash="A")
        # Cohort B: 5 runs at 0.30 (would look like a regression if we
        # mixed them together)
        for i in range(5):
            _append(tmp_path, ts=f"2026-03-{10+i}T15:00:00Z",
                    ndcg=0.30, manifest_hash="B")

    def test_cohort_filter_isolates_window(self, tmp_path):
        self._seed_two_cohorts(tmp_path)
        # Add a "current" run in cohort A at 0.69 — within noise.
        _append(tmp_path, ts="2026-03-20T14:00:00Z",
                ndcg=0.69, manifest_hash="A")
        result = check_trend("scifact", "hybrid", tmp_path, manifest_hash="A")
        assert result["status"] == "ok"
        assert result["manifest_hash"] == "A"
        assert result["total_comparable"] == 6  # 5 seed + 1 current

    def test_cross_cohort_view_sees_both(self, tmp_path):
        self._seed_two_cohorts(tmp_path)
        # Without a manifest_hash filter we observe all 10 runs.
        result = check_trend("scifact", "hybrid", tmp_path)
        assert result["status"] in {"ok", "regression"}
        assert result["total_comparable"] == 10
        assert "manifest_hash" not in result

    def test_insufficient_data_in_cohort(self, tmp_path):
        _append(tmp_path, manifest_hash="solo")
        result = check_trend("scifact", "hybrid", tmp_path, manifest_hash="solo")
        assert result["status"] == "insufficient_data"
        assert result["manifest_hash"] == "solo"


class TestEnvelopeMetricsTable:
    """LR4-h: envelope_metrics normalized table (§26.6 Decision 3)."""

    def test_insert_rows_from_envelope(self, tmp_path):
        env = _envelope(cohort_hash="c1",
                        modes=("hybrid",),
                        metrics=("nDCG@10", "P@1"))
        _append(tmp_path, mode="hybrid", manifest_hash="c1", envelope=env)

        rows = get_envelope_metrics(tmp_path, cohort_hash="c1")
        assert {(r["metric"], r["mode"]) for r in rows} == {
            ("nDCG@10", "hybrid"), ("P@1", "hybrid"),
        }
        for r in rows:
            assert r["cohort_hash"] == "c1"
            assert r["n"] == 5
            assert r["calibrated_at"] == "2026-04-22T06:00:00Z"

    def test_no_insert_when_envelope_none(self, tmp_path):
        _append(tmp_path, manifest_hash="c1", envelope=None)
        assert get_envelope_metrics(tmp_path) == []

    def test_no_insert_when_mode_missing_from_envelope(self, tmp_path):
        env = _envelope(cohort_hash="c1", modes=("hybrid",))
        # Insert for mode='lexical' but envelope only covers 'hybrid'.
        _append(tmp_path, mode="lexical", manifest_hash="c1", envelope=env)
        assert get_envelope_metrics(tmp_path, mode="lexical") == []

    def test_multi_mode_envelope(self, tmp_path):
        env = _envelope(cohort_hash="c1",
                        modes=("hybrid", "lexical"),
                        metrics=("nDCG@10",))
        _append(tmp_path, mode="hybrid", manifest_hash="c1", envelope=env)
        _append(tmp_path, mode="lexical", manifest_hash="c1", envelope=env)

        hybrid_rows = get_envelope_metrics(tmp_path, mode="hybrid")
        lexical_rows = get_envelope_metrics(tmp_path, mode="lexical")
        assert len(hybrid_rows) == 1
        assert len(lexical_rows) == 1
        assert hybrid_rows[0]["metric"] == "nDCG@10"
        assert lexical_rows[0]["metric"] == "nDCG@10"

    def test_filter_by_metric(self, tmp_path):
        env = _envelope(cohort_hash="c1",
                        modes=("hybrid",),
                        metrics=("nDCG@10", "P@1", "R@10"))
        _append(tmp_path, mode="hybrid", manifest_hash="c1", envelope=env)

        ndcg = get_envelope_metrics(tmp_path, metric="nDCG@10")
        assert len(ndcg) == 1
        assert ndcg[0]["metric"] == "nDCG@10"

    def test_returns_empty_when_db_missing(self, tmp_path):
        # No db, no append — envelope_metrics still returns []
        assert get_envelope_metrics(tmp_path) == []

    def test_skips_metric_rows_missing_mean_stdev_or_n(self, tmp_path):
        env = {
            "schema_version": 1,
            "cohort_hash": "c1",
            "calibrated_at": "2026-04-22T06:00:00Z",
            "metrics": {
                "hybrid": {
                    "nDCG@10": {"mean": 0.7, "stdev": 0.001, "n": 5},
                    "P@1": {"mean": 0.5, "stdev": 0.002},  # missing n
                    "R@10": {"stdev": 0.001, "n": 5},  # missing mean
                },
            },
        }
        _append(tmp_path, mode="hybrid", manifest_hash="c1", envelope=env)
        rows = get_envelope_metrics(tmp_path)
        assert {r["metric"] for r in rows} == {"nDCG@10"}

    def test_silently_ignores_envelope_without_cohort_hash(self, tmp_path):
        env = {
            "schema_version": 1,
            # No cohort_hash.
            "calibrated_at": "2026-04-22T06:00:00Z",
            "metrics": {"hybrid": {"nDCG@10": {
                "mean": 0.7, "stdev": 0.001, "n": 5}}},
        }
        _append(tmp_path, mode="hybrid", manifest_hash="c1", envelope=env)
        assert get_envelope_metrics(tmp_path) == []


class TestMigration:
    """LR4-h: schema migrates a pre-existing db without the new column."""

    def test_legacy_db_gains_manifest_hash_on_reopen(self, tmp_path):
        db_path = tmp_path / "eval-history.db"
        # Create a legacy-shaped DB by hand (no manifest_hash column).
        with sqlite3.connect(str(db_path)) as conn:
            conn.execute("""
                CREATE TABLE runs (
                    id INTEGER PRIMARY KEY,
                    timestamp TEXT NOT NULL,
                    git_sha TEXT,
                    dataset TEXT NOT NULL,
                    mode TEXT NOT NULL,
                    ndcg_10 REAL, map_10 REAL, mrr_10 REAL, recall_10 REAL, p1 REAL,
                    mean_latency_ms REAL, context_hit_rate REAL,
                    comparable INTEGER NOT NULL DEFAULT 1
                )
            """)
            conn.execute(
                "INSERT INTO runs (timestamp, dataset, mode, ndcg_10, comparable) "
                "VALUES (?, ?, ?, ?, ?)",
                ("2026-03-10T14:00:00Z", "scifact", "hybrid", 0.42, 1),
            )

        # Now a fresh append_run should migrate and succeed.
        _append(tmp_path, manifest_hash="new", ndcg=0.71)
        rows = get_history("scifact", "hybrid", tmp_path)
        assert len(rows) == 2
        legacy = next(r for r in rows if r["ndcg_10"] == 0.42)
        new = next(r for r in rows if r["ndcg_10"] == 0.71)
        assert legacy["manifest_hash"] is None
        assert new["manifest_hash"] == "new"

    def test_reconnect_is_idempotent(self, tmp_path):
        _append(tmp_path, manifest_hash="a")
        _append(tmp_path, manifest_hash="b")
        _append(tmp_path, manifest_hash="c")
        rows = get_history("scifact", "hybrid", tmp_path, limit=10)
        assert {r["manifest_hash"] for r in rows} == {"a", "b", "c"}


class TestPerfTrend:
    """tempdoc 640 R3: perf families trended alongside quality, direction-aware."""

    def _append_perf(self, output_dir, ce, ts, retrieval=4.0, unaccounted=20.0):
        summary = {"timestamp": ts, "git_sha": "abc", "dataset": "scifact"}
        return append_run(
            summary, "hybrid",
            {"nDCG@10": 0.7, "AP@10": 0.4, "RR@10": 0.6, "R@10": 0.8, "P@1": 0.5},
            True, output_dir,
            perf_metrics={"ce_p50_ms": ce, "primary_docs_s": 90.0,
                          "enrich_docs_s": 12.0, "resident_bytes": 2_000_000_000,
                          "retrieval_p50_ms": retrieval, "unaccounted_p50_ms": unaccounted},
        )

    def test_perf_columns_persist(self, tmp_path):
        self._append_perf(tmp_path, ce=150.0, ts="2026-06-24T10:00:00Z")
        row = get_history("scifact", "hybrid", tmp_path)[0]
        assert row["ce_p50_ms"] == 150.0
        assert row["primary_docs_s"] == 90.0
        assert row["resident_bytes"] == 2_000_000_000
        # tempdoc 647: the latency-decomposition columns persist too
        assert row["retrieval_p50_ms"] == 4.0
        assert row["unaccounted_p50_ms"] == 20.0

    def test_unaccounted_creep_is_flagged_direction_aware(self, tmp_path):
        # tempdoc 647: a rising `unaccounted` remainder (dark-latency creep) is a lower-is-better
        # regression — the observability the design wanted, on the trend path (no calibrate cohort).
        for day in ("01", "02", "03"):
            self._append_perf(tmp_path, ce=150.0, ts=f"2026-06-{day}T10:00:00Z", unaccounted=20.0)
        self._append_perf(tmp_path, ce=150.0, ts="2026-06-20T10:00:00Z", unaccounted=35.0)  # latest
        rep = check_trend("scifact", "hybrid", tmp_path, metric="unaccounted_p50_ms")
        assert rep["status"] == "regression" and rep["lower_is_better"] is True
        # CE is flat over the same rows -> the creep is scoped to the unaccounted metric
        assert check_trend("scifact", "hybrid", tmp_path, metric="ce_p50_ms")["status"] == "ok"

    def test_latency_regression_is_direction_aware(self, tmp_path):
        # Stable CE-stage history at 150 ms, then the latest jumps to 200 ms. For a
        # lower-is-better metric that INCREASE is the regression (the legacy nDCG path
        # treats a DECREASE as the regression — this proves the direction flips).
        for day in ("01", "02", "03"):
            self._append_perf(tmp_path, ce=150.0, ts=f"2026-06-{day}T10:00:00Z")
        self._append_perf(tmp_path, ce=200.0, ts="2026-06-20T10:00:00Z")  # newest = latest
        lat = check_trend("scifact", "hybrid", tmp_path, metric="ce_p50_ms")
        assert lat["status"] == "regression" and lat["lower_is_better"] is True
        # nDCG over the same rows is flat (all 0.7) -> ok: the perf regression is metric-scoped.
        assert check_trend("scifact", "hybrid", tmp_path, metric="nDCG@10")["status"] == "ok"

    def test_legacy_db_gains_perf_columns_on_reopen(self, tmp_path):
        db_path = tmp_path / "eval-history.db"
        with sqlite3.connect(str(db_path)) as conn:  # legacy shape: no perf columns
            conn.execute("""
                CREATE TABLE runs (
                    id INTEGER PRIMARY KEY, timestamp TEXT NOT NULL, git_sha TEXT,
                    dataset TEXT NOT NULL, mode TEXT NOT NULL,
                    ndcg_10 REAL, map_10 REAL, mrr_10 REAL, recall_10 REAL, p1 REAL,
                    mean_latency_ms REAL, context_hit_rate REAL,
                    comparable INTEGER NOT NULL DEFAULT 1)
            """)
            conn.execute(
                "INSERT INTO runs (timestamp, dataset, mode, ndcg_10, comparable) "
                "VALUES (?, ?, ?, ?, ?)",
                ("2026-03-10T14:00:00Z", "scifact", "hybrid", 0.42, 1),
            )
        self._append_perf(tmp_path, ce=150.0, ts="2026-06-24T10:00:00Z")  # migrates on reopen
        rows = get_history("scifact", "hybrid", tmp_path)
        legacy = next(r for r in rows if r["ndcg_10"] == 0.42)
        assert legacy["ce_p50_ms"] is None  # legacy row keeps NULL
        assert any(r["ce_p50_ms"] == 150.0 for r in rows)
        # tempdoc 647: the idempotent migration also adds the latency-decomposition columns
        assert legacy["unaccounted_p50_ms"] is None  # legacy row keeps NULL for the new column too
        assert any(r["unaccounted_p50_ms"] == 20.0 for r in rows)


class TestAppendRunReturnsRunId:
    def test_returns_lastrowid(self, tmp_path):
        rid1 = _append(tmp_path, manifest_hash="a")
        rid2 = _append(tmp_path, manifest_hash="b")
        assert rid2 == rid1 + 1

    def test_envelope_metrics_bind_to_correct_run_id(self, tmp_path):
        env_a = _envelope(cohort_hash="A", metrics=("nDCG@10",))
        env_b = _envelope(cohort_hash="B", metrics=("nDCG@10",))
        rid_a = _append(tmp_path, manifest_hash="A", envelope=env_a)
        rid_b = _append(tmp_path, manifest_hash="B", envelope=env_b)

        rows = get_envelope_metrics(tmp_path, limit=100)
        by_cohort = {r["cohort_hash"]: r for r in rows}
        # Phase 6 / 6.11: run_id is stored as INTEGER now (was TEXT)
        # so JOINs against runs.id work without CAST.
        assert by_cohort["A"]["run_id"] == rid_a
        assert by_cohort["B"]["run_id"] == rid_b


class TestForeignKeyCascade:
    """Phase 6 / 6.11: FK constraint with ON DELETE CASCADE."""

    def test_delete_run_cascades_to_envelope_metrics(self, tmp_path):
        env = _envelope(cohort_hash="c1", metrics=("nDCG@10",))
        rid = _append(tmp_path, manifest_hash="c1", envelope=env)
        # Baseline: one envelope row present.
        assert len(get_envelope_metrics(tmp_path)) == 1

        with sqlite3.connect(str(tmp_path / "eval-history.db")) as conn:
            # FKs are off by default on a new connection; must enable.
            conn.execute("PRAGMA foreign_keys = ON")
            conn.execute("DELETE FROM runs WHERE id = ?", (rid,))
            conn.commit()

        # envelope_metrics row should have been deleted by cascade.
        assert get_envelope_metrics(tmp_path) == []

    def test_foreign_keys_enabled_on_connect(self, tmp_path):
        _append(tmp_path, manifest_hash="c1")
        # The library's own _connect has PRAGMA foreign_keys=ON.
        # Verify by opening one + checking the pragma.
        from jseval.history import _connect
        with _connect(tmp_path / "eval-history.db") as conn:
            result = conn.execute("PRAGMA foreign_keys").fetchone()
            assert result[0] == 1


class TestInsertEnvelopeMetricsCounts:
    """Phase 6 / 6.11: `_insert_envelope_metrics` returns (inserted, skipped)."""

    def test_direct_call_returns_counts(self, tmp_path):
        from jseval.history import _connect, _insert_envelope_metrics

        # Prepare a DB with a runs row so the FK passes.
        _append(tmp_path, manifest_hash="c1")
        env = {
            "schema_version": 1,
            "cohort_hash": "c1",
            "calibrated_at": "2026-04-22T00:00:00Z",
            "metrics": {
                "hybrid": {
                    "nDCG@10": {"mean": 0.7, "stdev": 0.001, "n": 5},
                    "P@1": {"mean": 0.5, "stdev": 0.002},  # missing n → skipped
                    "R@10": {"stdev": 0.001, "n": 5},  # missing mean → skipped
                },
            },
        }
        with _connect(tmp_path / "eval-history.db") as conn:
            # Use existing run_id=1 from the earlier _append.
            inserted, skipped = _insert_envelope_metrics(conn, 1, "hybrid", env)
        assert inserted == 1
        assert skipped == 2
