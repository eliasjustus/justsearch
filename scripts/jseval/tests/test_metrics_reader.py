"""Tests for metrics_reader.py (tempdoc 400 LR3-b)."""

from __future__ import annotations

import json
from datetime import datetime, timezone
from pathlib import Path

from jseval.metrics_reader import iter_merged, read_merged


def _write(path: Path, records: list[dict]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        "\n".join(json.dumps(r) for r in records) + "\n",
        encoding="utf-8",
    )


def _mk(t: str, name: str, value: float) -> dict:
    return {"t": t, "name": name, "type": "gauge", "value": value, "tags": {}}


class TestReadMerged:
    def test_empty_dir(self, tmp_path):
        assert read_merged(tmp_path) == []

    def test_only_head(self, tmp_path):
        _write(tmp_path / "telemetry" / "metrics.ndjson", [
            _mk("2026-04-21T20:00:00Z", "head.jvm.uptime_ms", 100.0),
        ])
        records = read_merged(tmp_path)
        assert len(records) == 1
        assert records[0]["source"] == "head"
        assert records[0]["name"] == "head.jvm.uptime_ms"

    def test_only_worker(self, tmp_path):
        _write(tmp_path / "telemetry" / "metrics-worker.ndjson", [
            _mk("2026-04-21T20:00:00Z", "worker.job_queue.depth", 5.0),
        ])
        records = read_merged(tmp_path)
        assert len(records) == 1
        assert records[0]["source"] == "worker"

    def test_both_files_sorted_by_timestamp(self, tmp_path):
        _write(tmp_path / "telemetry" / "metrics.ndjson", [
            _mk("2026-04-21T20:00:02Z", "h1", 1.0),
            _mk("2026-04-21T20:00:04Z", "h2", 2.0),
        ])
        _write(tmp_path / "telemetry" / "metrics-worker.ndjson", [
            _mk("2026-04-21T20:00:01Z", "w1", 10.0),
            _mk("2026-04-21T20:00:03Z", "w2", 20.0),
        ])
        names = [r["name"] for r in read_merged(tmp_path)]
        assert names == ["w1", "h1", "w2", "h2"]

    def test_skips_missing_files_without_error(self, tmp_path):
        # Only worker file present; head file missing.
        _write(tmp_path / "telemetry" / "metrics-worker.ndjson", [
            _mk("2026-04-21T20:00:00Z", "w", 1.0),
        ])
        records = read_merged(tmp_path)
        assert len(records) == 1 and records[0]["source"] == "worker"

    def test_skips_unparseable_lines(self, tmp_path):
        path = tmp_path / "telemetry" / "metrics.ndjson"
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(
            '{"t":"2026-04-21T20:00:00Z","name":"ok","type":"gauge","value":1}\n'
            "not-json-garbage\n"
            '{"t":"2026-04-21T20:00:01Z","name":"ok2","type":"gauge","value":2}\n',
            encoding="utf-8",
        )
        records = read_merged(tmp_path)
        assert [r["name"] for r in records] == ["ok", "ok2"]

    def test_time_window_filter(self, tmp_path):
        _write(tmp_path / "telemetry" / "metrics.ndjson", [
            _mk("2026-04-21T20:00:01Z", "a", 1.0),
            _mk("2026-04-21T20:00:02Z", "b", 2.0),
            _mk("2026-04-21T20:00:03Z", "c", 3.0),
            _mk("2026-04-21T20:00:04Z", "d", 4.0),
        ])
        since = datetime(2026, 4, 21, 20, 0, 2, tzinfo=timezone.utc)
        until = datetime(2026, 4, 21, 20, 0, 4, tzinfo=timezone.utc)
        records = read_merged(tmp_path, since=since, until=until)
        # since is inclusive, until is exclusive.
        assert [r["name"] for r in records] == ["b", "c"]

    def test_source_tag_attached(self, tmp_path):
        _write(tmp_path / "telemetry" / "metrics.ndjson", [
            _mk("2026-04-21T20:00:00Z", "h", 1.0),
        ])
        _write(tmp_path / "telemetry" / "metrics-worker.ndjson", [
            _mk("2026-04-21T20:00:01Z", "w", 1.0),
        ])
        by_source = {r["name"]: r["source"] for r in read_merged(tmp_path)}
        assert by_source == {"h": "head", "w": "worker"}


class TestIterMerged:
    def test_yields_records_in_order(self, tmp_path):
        _write(tmp_path / "telemetry" / "metrics.ndjson", [
            _mk("2026-04-21T20:00:02Z", "h", 1.0),
        ])
        _write(tmp_path / "telemetry" / "metrics-worker.ndjson", [
            _mk("2026-04-21T20:00:01Z", "w", 1.0),
        ])
        ordered = list(iter_merged(tmp_path))
        assert [r["name"] for r in ordered] == ["w", "h"]
