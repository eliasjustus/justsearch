"""Tests for artifacts.py — output artifact generation."""

from __future__ import annotations

import json
from pathlib import Path
from unittest.mock import MagicMock

from jseval.artifacts import (
    _build_per_query_entries,
    _mirror_telemetry,
    _write_trec_run,
    write_run,
)


def _mock_mode_result():
    return {
        "aggregate_metrics": {"nDCG@10": 0.731, "AP@10": 0.412},
        "per_query_metrics": {
            "q1": {"nDCG@10": 0.85, "AP@10": 0.5},
            "q2": {"nDCG@10": 0.62, "AP@10": 0.32},
        },
        "scored_docs": [
            MagicMock(query_id="q1", doc_id="d1", score=2.0),
            MagicMock(query_id="q1", doc_id="d2", score=1.0),
            MagicMock(query_id="q2", doc_id="d3", score=1.5),
        ],
        "raw_responses": [
            # Tempdoc 549 Phase E4: effectiveMode/decisionKind come from the unified searchTrace.
            {"query_id": "q1", "tookMs": 15, "totalHits": 10,
             "searchTrace": {"effectiveMode": "HYBRID"}},
            {"query_id": "q2", "tookMs": 20, "totalHits": 5,
             "searchTrace": {"effectiveMode": "HYBRID"}},
        ],
        "run_evidence": {"error_count": 0},
        "ann_proof": MagicMock(status="PASS", reasons=[], rates={}),
        "comparability": MagicMock(comparable=True, reasons=[]),
    }


def _mock_summary():
    return {
        "timestamp": "2026-03-16T14:00:00Z",
        "git_sha": "abc123",
        "dataset": "scifact",
        "modes": ["hybrid"],
        "doc_count": 100,
        "query_count": 2,
        "per_mode": {
            "hybrid": {
                "aggregate_metrics": {"nDCG@10": 0.731},
                "comparable": True,
                "comparability_reasons": [],
                "ann_proof_status": "PASS",
                "error_count": 0,
            },
        },
    }


class TestWriteRun:
    def test_creates_directory(self, tmp_path):
        summary = _mock_summary()
        mode_results = {"hybrid": _mock_mode_result()}
        qrels = {"q1": {"d1": 1, "d2": 0}, "q2": {"d3": 1}}

        run_dir = write_run(summary, mode_results, qrels, tmp_path)
        assert run_dir.is_dir()
        assert (run_dir / "summary.json").is_file()

    def test_summary_json_content(self, tmp_path):
        summary = _mock_summary()
        mode_results = {"hybrid": _mock_mode_result()}
        qrels = {"q1": {"d1": 1}}

        run_dir = write_run(summary, mode_results, qrels, tmp_path)
        data = json.loads((run_dir / "summary.json").read_text())
        assert data["dataset"] == "scifact"
        assert data["git_sha"] == "abc123"
        assert "per_mode" in data

    def test_per_query_json_content(self, tmp_path):
        summary = _mock_summary()
        mode_results = {"hybrid": _mock_mode_result()}
        qrels = {"q1": {"d1": 1, "d2": 0}, "q2": {"d3": 1}}

        run_dir = write_run(summary, mode_results, qrels, tmp_path)
        entries = json.loads((run_dir / "hybrid_per_query.json").read_text())
        assert len(entries) == 2
        assert entries[0]["qid"] == "q1"
        assert entries[0]["ndcgAtK"] == 0.85
        assert entries[0]["tookMs"] == 15
        assert entries[0]["predictedDocIds"] == ["d1", "d2"]

    def test_trec_run_format(self, tmp_path):
        summary = _mock_summary()
        mode_results = {"hybrid": _mock_mode_result()}
        qrels = {"q1": {"d1": 1}}

        run_dir = write_run(summary, mode_results, qrels, tmp_path)
        trec = (run_dir / "hybrid_run.trec").read_text().strip().split("\n")
        assert len(trec) == 3  # 2 docs for q1, 1 for q2

        # First line: highest score first
        parts = trec[0].split()
        assert parts[0] == "q1"  # qid
        assert parts[1] == "Q0"  # iter
        assert parts[3] == "1"   # rank
        assert parts[5] == "jseval_hybrid"  # run name


class TestBuildPerQueryEntries:
    def test_merges_metrics_and_response(self):
        mr = _mock_mode_result()
        qrels = {"q1": {"d1": 1, "d2": 0}, "q2": {"d3": 1}}
        entries = _build_per_query_entries("hybrid", mr, qrels)

        assert len(entries) == 2
        q1 = entries[0]
        assert q1["qid"] == "q1"
        assert q1["ndcgAtK"] == 0.85
        assert q1["tookMs"] == 15
        assert q1["effectiveMode"] == "HYBRID"
        assert q1["totalRelevant"] == 1  # d1 has rel=1

    def test_total_relevant_counts(self):
        mr = _mock_mode_result()
        qrels = {"q1": {"d1": 2, "d2": 1, "d3": 0}, "q2": {"d3": 1}}
        entries = _build_per_query_entries("hybrid", mr, qrels)

        q1 = entries[0]
        assert q1["totalRelevant"] == 2  # d1 (rel=2) + d2 (rel=1)

    def test_judge_signals_extracted_per_hit(self):
        # Tempdoc 643: per-hit CE score + per-leg ranks persisted from the response's results/trace.
        mr = _mock_mode_result()
        mr["raw_responses"][0]["results"] = [
            {
                "id": "d1.txt",
                "trace": [
                    {"id": "sparse-retrieval", "rank": 1, "score": 5.0},
                    {"id": "splade-retrieval", "rank": 3, "score": 2.0},
                    {"id": "dense-retrieval", "rank": 2, "score": 0.8},
                    {"id": "fusion", "score": 1.1},
                    {"id": "cross-encoder", "score": 4.2},
                ],
            },
        ]
        qrels = {"q1": {"d1": 1, "d2": 0}, "q2": {"d3": 1}}
        entries = _build_per_query_entries("hybrid", mr, qrels)

        q1 = entries[0]
        assert len(q1["judgeSignals"]) == 1
        sig = q1["judgeSignals"][0]
        assert sig["docId"] == "d1"
        assert sig["bm25_rank"] == 1
        assert sig["splade_rank"] == 3
        assert sig["dense_rank"] == 2
        assert sig["ce_score"] == 4.2

        # q2 has no "results" key in the mock raw_response → empty, not an error.
        assert entries[1]["judgeSignals"] == []

    def test_judge_signals_skips_unresolvable_hit(self):
        # A hit whose doc-id can't be resolved is skipped (logged), not a crash.
        mr = _mock_mode_result()
        mr["raw_responses"][0]["results"] = [
            {"id": "unresolvable-bare-id", "trace": []},
            {"id": "d2.txt", "trace": [{"id": "cross-encoder", "score": 1.0}]},
        ]
        qrels = {"q1": {"d1": 1}, "q2": {"d3": 1}}
        entries = _build_per_query_entries("hybrid", mr, qrels)

        q1 = entries[0]
        assert len(q1["judgeSignals"]) == 1
        assert q1["judgeSignals"][0]["docId"] == "d2"


class TestMirrorTelemetry:
    """LR4-d: artifacts.write_run copies telemetry NDJSON into run_dir."""

    def test_missing_telemetry_dir_is_no_op(self, tmp_path):
        # data_dir has no telemetry/ subdir → no files copied, no raise.
        data_dir = tmp_path / "data"
        run_dir = tmp_path / "run"
        data_dir.mkdir()
        run_dir.mkdir()
        copied = _mirror_telemetry(data_dir, run_dir)
        assert copied == []

    def test_copies_all_known_files(self, tmp_path):
        data_dir = tmp_path / "data"
        run_dir = tmp_path / "run"
        (data_dir / "telemetry").mkdir(parents=True)
        for name in ("traces.ndjson", "metrics.ndjson", "metrics-worker.ndjson"):
            (data_dir / "telemetry" / name).write_text(f"stub-{name}", encoding="utf-8")
        run_dir.mkdir()
        copied = _mirror_telemetry(data_dir, run_dir)
        assert set(copied) == {"traces.ndjson", "metrics.ndjson", "metrics-worker.ndjson"}
        for name in copied:
            assert (run_dir / name).read_text(encoding="utf-8") == f"stub-{name}"

    def test_ignores_unknown_files(self, tmp_path):
        data_dir = tmp_path / "data"
        run_dir = tmp_path / "run"
        (data_dir / "telemetry").mkdir(parents=True)
        (data_dir / "telemetry" / "stray.log").write_text("x", encoding="utf-8")
        run_dir.mkdir()
        copied = _mirror_telemetry(data_dir, run_dir)
        assert copied == []

    def test_write_run_with_data_dir_mirrors(self, tmp_path):
        data_dir = tmp_path / "data"
        (data_dir / "telemetry").mkdir(parents=True)
        (data_dir / "telemetry" / "metrics.ndjson").write_text(
            '{"name":"x","value":1}\n', encoding="utf-8",
        )
        summary = _mock_summary()
        mode_results = {"hybrid": _mock_mode_result()}
        qrels = {"q1": {"d1": 1}, "q2": {"d3": 1}}
        run_dir = write_run(summary, mode_results, qrels, tmp_path / "out",
                            data_dir=data_dir)
        assert (run_dir / "metrics.ndjson").is_file()

    def test_write_run_without_data_dir_skips_mirror(self, tmp_path):
        summary = _mock_summary()
        mode_results = {"hybrid": _mock_mode_result()}
        qrels = {"q1": {"d1": 1}, "q2": {"d3": 1}}
        run_dir = write_run(summary, mode_results, qrels, tmp_path / "out")
        assert not (run_dir / "metrics.ndjson").exists()


class TestMirrorTelemetryRotatedSiblings:
    """Tempdoc 400 §23.9.3 D-3 regression: rotated telemetry shards
    (pattern ``traces.<ts>.ndjson`` / ``metrics.<ts>.ndjson``) must
    be concatenated with the active file into the run_dir mirror.

    Pre-fix, only the active file was copied; ingest-heavy runs that
    crossed the 10 MB rotation threshold lost up to 77% of their
    spans (§23.9.3 measurement: 39,154 emitted → 8,985 mirrored,
    ALL indexing.* spans lost).
    """

    def test_rotated_siblings_concatenated_with_active(self, tmp_path):
        data_dir = tmp_path / "data"
        tel = data_dir / "telemetry"
        tel.mkdir(parents=True)
        # Simulate rotation: one rotated sibling + one active file,
        # each with distinct span payloads. NdjsonSpanExporter names
        # rotated files `traces.<yyyyMMdd-HHmmss>.ndjson`.
        (tel / "traces.20260422-134805.ndjson").write_text(
            '{"name":"indexing.batch","span_id":"a"}\n'
            '{"name":"indexing.extract","span_id":"b"}\n',
            encoding="utf-8",
        )
        (tel / "traces.ndjson").write_text(
            '{"name":"search/retrieval","span_id":"c"}\n',
            encoding="utf-8",
        )
        run_dir = tmp_path / "run"
        run_dir.mkdir()
        copied = _mirror_telemetry(data_dir, run_dir)
        assert "traces.ndjson" in copied
        mirrored = (run_dir / "traces.ndjson").read_text(encoding="utf-8")
        # All 3 spans present, rotated-first then active.
        lines = mirrored.strip().split("\n")
        assert len(lines) == 3
        assert '"span_id":"a"' in lines[0]
        assert '"span_id":"b"' in lines[1]
        assert '"span_id":"c"' in lines[2]

    def test_multiple_rotated_siblings_sorted_chronologically(self, tmp_path):
        """With N rotated siblings, the mirror orders them by filename
        timestamp (ascending), then appends the active file last."""
        data_dir = tmp_path / "data"
        tel = data_dir / "telemetry"
        tel.mkdir(parents=True)
        (tel / "traces.20260422-100000.ndjson").write_text('"first"\n',
                                                             encoding="utf-8")
        (tel / "traces.20260422-110000.ndjson").write_text('"second"\n',
                                                             encoding="utf-8")
        (tel / "traces.20260422-120000.ndjson").write_text('"third"\n',
                                                             encoding="utf-8")
        (tel / "traces.ndjson").write_text('"active"\n', encoding="utf-8")
        run_dir = tmp_path / "run"
        run_dir.mkdir()
        _mirror_telemetry(data_dir, run_dir)
        mirrored = (run_dir / "traces.ndjson").read_text(encoding="utf-8")
        lines = mirrored.strip().split("\n")
        assert lines == ['"first"', '"second"', '"third"', '"active"']

    def test_rotated_without_active_still_mirrored(self, tmp_path):
        """If the exporter rotated but produced no new active spans
        before the run ended, the rotated shard is still mirrored."""
        data_dir = tmp_path / "data"
        tel = data_dir / "telemetry"
        tel.mkdir(parents=True)
        (tel / "traces.20260422-134805.ndjson").write_text(
            '"rotated-only"\n', encoding="utf-8",
        )
        # NO active traces.ndjson
        run_dir = tmp_path / "run"
        run_dir.mkdir()
        copied = _mirror_telemetry(data_dir, run_dir)
        assert "traces.ndjson" in copied
        assert (run_dir / "traces.ndjson").read_text(encoding="utf-8") \
            == '"rotated-only"\n'

    def test_missing_trailing_newline_on_rotated_shard_auto_fixed(self, tmp_path):
        """If a rotated file didn't end with \\n (unusual; would happen
        if rotation caught mid-write), the concat must insert a newline
        between shards so no two JSON records fuse into one parse
        error."""
        data_dir = tmp_path / "data"
        tel = data_dir / "telemetry"
        tel.mkdir(parents=True)
        (tel / "traces.20260422-134805.ndjson").write_bytes(
            b'{"span":"rotated-no-newline"}',  # no trailing \n
        )
        (tel / "traces.ndjson").write_text(
            '{"span":"active"}\n', encoding="utf-8",
        )
        run_dir = tmp_path / "run"
        run_dir.mkdir()
        _mirror_telemetry(data_dir, run_dir)
        mirrored = (run_dir / "traces.ndjson").read_text(encoding="utf-8")
        lines = mirrored.strip().split("\n")
        assert len(lines) == 2
        assert '"rotated-no-newline"' in lines[0]
        assert '"active"' in lines[1]

    def test_applies_to_metrics_files_too(self, tmp_path):
        """The rotation pattern applies to metrics.ndjson +
        metrics-worker.ndjson the same way."""
        data_dir = tmp_path / "data"
        tel = data_dir / "telemetry"
        tel.mkdir(parents=True)
        (tel / "metrics.20260422-134805.ndjson").write_text('"m-rotated"\n',
                                                              encoding="utf-8")
        (tel / "metrics.ndjson").write_text('"m-active"\n', encoding="utf-8")
        (tel / "metrics-worker.20260422-134805.ndjson").write_text(
            '"w-rotated"\n', encoding="utf-8",
        )
        (tel / "metrics-worker.ndjson").write_text('"w-active"\n',
                                                     encoding="utf-8")
        run_dir = tmp_path / "run"
        run_dir.mkdir()
        _mirror_telemetry(data_dir, run_dir)
        assert (run_dir / "metrics.ndjson").read_text(encoding="utf-8") \
            == '"m-rotated"\n"m-active"\n'
        assert (run_dir / "metrics-worker.ndjson").read_text(encoding="utf-8") \
            == '"w-rotated"\n"w-active"\n'

    def test_single_source_uses_copy2_semantics(self, tmp_path):
        """When only the active file exists (no rotation occurred), the
        single-source fast path (shutil.copy2) is used. Verifies output
        is byte-identical to input — no accidental newline insertion."""
        data_dir = tmp_path / "data"
        tel = data_dir / "telemetry"
        tel.mkdir(parents=True)
        payload = b'{"span":"s1"}\n{"span":"s2"}\n'
        (tel / "traces.ndjson").write_bytes(payload)
        run_dir = tmp_path / "run"
        run_dir.mkdir()
        _mirror_telemetry(data_dir, run_dir)
        assert (run_dir / "traces.ndjson").read_bytes() == payload
