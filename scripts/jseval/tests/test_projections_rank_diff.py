"""Tests for rank_diff projection (tempdoc 400 LR4-e)."""

from __future__ import annotations

import json
from pathlib import Path

from tests.conftest import SyntheticRunDir

from jseval.projections.rank_diff import PROJECTION, produce


def _make_entries(ndcg_by_qid: dict[str, float], docs_by_qid: dict[str, list[str]]):
    return [
        {
            "qid": qid,
            "query": f"query {qid}",
            "mode": "hybrid",
            "ndcgAtK": ndcg,
            "apAtK": ndcg * 0.6,
            "mrrAtK": ndcg * 0.8,
            "recallAtK": min(1.0, ndcg * 1.1),
            "p1AtK": 1.0 if ndcg >= 0.5 else 0.0,
            "predictedDocIds": docs_by_qid.get(qid, []),
            "totalRelevant": 1,
        }
        for qid, ndcg in sorted(ndcg_by_qid.items())
    ]


def _build_run(parent: Path, name: str, cohort_hash: str,
               ndcg_by_qid: dict[str, float],
               docs_by_qid: dict[str, list[str]] | None = None,
               qrels: dict[str, dict[str, int]] | None = None) -> SyntheticRunDir:
    docs_by_qid = docs_by_qid or {}
    s = SyntheticRunDir(parent / name)
    s.with_manifest({"manifest_hash": cohort_hash})
    s.with_per_query("hybrid", _make_entries(ndcg_by_qid, docs_by_qid))
    if qrels is not None:
        s.with_qrels(qrels)
    return s


class TestDiscovery:
    def test_no_manifest_status(self, synthetic_run_dir):
        # No manifest.json in run_dir.
        result = produce(synthetic_run_dir.run_dir)
        assert result["status"] == "no-manifest"

    def test_no_cohort_hash_status(self, synthetic_run_dir):
        synthetic_run_dir.with_manifest({"timestamp": "2026-04-22T06:00:00Z"})
        result = produce(synthetic_run_dir.run_dir)
        assert result["status"] == "no-cohort-hash"

    def test_no_prior_cohort(self, tmp_path):
        run = _build_run(tmp_path, "20260422T070000_scifact", "cohort-a",
                         {"q1": 0.7, "q2": 0.5})
        result = produce(run.run_dir)
        assert result["status"] == "no-prior-cohort"
        assert result["cohort_hash"] == "cohort-a"

    def test_prior_with_different_cohort_ignored(self, tmp_path):
        _build_run(tmp_path, "20260422T060000_scifact", "cohort-OTHER",
                   {"q1": 0.9, "q2": 0.8})
        current = _build_run(tmp_path, "20260422T070000_scifact", "cohort-a",
                             {"q1": 0.7, "q2": 0.5})
        result = produce(current.run_dir)
        assert result["status"] == "no-prior-cohort"

    def test_later_sibling_not_used(self, tmp_path):
        # A sibling with a later timestamp (future run) is not a "prior".
        _build_run(tmp_path, "20260422T080000_scifact", "cohort-a",
                   {"q1": 0.9, "q2": 0.8})
        current = _build_run(tmp_path, "20260422T070000_scifact", "cohort-a",
                             {"q1": 0.7, "q2": 0.5})
        result = produce(current.run_dir)
        assert result["status"] == "no-prior-cohort"


class TestRegressionDetection:
    def test_regressions_captured(self, tmp_path):
        _build_run(tmp_path, "20260422T060000_scifact", "cohort-a",
                   {"q1": 0.9, "q2": 0.8, "q3": 0.7},
                   docs_by_qid={"q1": ["d1", "d2"],
                                "q2": ["d3", "d4"],
                                "q3": ["d5"]})
        current = _build_run(tmp_path, "20260422T070000_scifact", "cohort-a",
                             {"q1": 0.3, "q2": 0.8, "q3": 0.2},
                             docs_by_qid={"q1": ["d2", "d1"],
                                          "q2": ["d3", "d4"],
                                          "q3": ["d6"]},
                             qrels={"q1": {"d1": 1}, "q2": {"d3": 1},
                                    "q3": {"d5": 1}})
        result = produce(current.run_dir)
        assert result["status"] == "ok"
        assert result["cohort_hash"] == "cohort-a"
        assert result["prior_run"] == "20260422T060000_scifact"
        hybrid = result["modes"]["hybrid"]
        assert hybrid["status"] == "ok"
        # Two queries regressed (q1 0.9→0.3 and q3 0.7→0.2), q2 stable.
        assert hybrid["regression_count"] == 2
        assert hybrid["total_compared"] == 3
        qids = [r["qid"] for r in hybrid["regressions"]]
        assert "q1" in qids and "q3" in qids
        assert "q2" not in qids

    def test_picks_latest_prior(self, tmp_path):
        # Two prior runs in same cohort → latest-prior wins.
        _build_run(tmp_path, "20260422T060000_scifact", "cohort-a",
                   {"q1": 0.2})  # Even older
        _build_run(tmp_path, "20260422T063000_scifact", "cohort-a",
                   {"q1": 0.9})  # Latest prior
        current = _build_run(tmp_path, "20260422T070000_scifact", "cohort-a",
                             {"q1": 0.3}, qrels={"q1": {"d1": 1}})
        result = produce(current.run_dir)
        assert result["prior_run"] == "20260422T063000_scifact"
        # Against the latest prior (0.9), 0.3 is a regression.
        assert result["modes"]["hybrid"]["regression_count"] == 1


class TestMissingModes:
    def test_mode_missing_in_prior(self, tmp_path):
        # Prior has `hybrid_per_query.json`; current has both `hybrid`
        # and `lexical`. Lexical gets missing-mode status.
        _build_run(tmp_path, "20260422T060000_scifact", "cohort-a",
                   {"q1": 0.7, "q2": 0.5})
        current = _build_run(tmp_path, "20260422T070000_scifact", "cohort-a",
                             {"q1": 0.3, "q2": 0.2})
        # Add a lexical per_query only to the current run.
        current.with_per_query("lexical", _make_entries({"q1": 0.4, "q2": 0.3}, {}))
        result = produce(current.run_dir)
        assert result["modes"]["hybrid"]["status"] == "ok"
        assert result["modes"]["lexical"]["status"] == "missing-mode"


class TestProjectionRegistration:
    def test_module_export(self):
        assert PROJECTION.name == "rank_diff"
        assert PROJECTION.schema_version == 1

    def test_bootstrap_registers(self, tmp_path):
        from jseval.projections import registry, run_all_discovered
        from jseval.projections.base import reset_registry_for_tests

        reset_registry_for_tests()
        # Build an empty run dir so run_all_discovered doesn't crash.
        rd = SyntheticRunDir(tmp_path / "run")
        run_all_discovered(rd.run_dir)
        assert "rank_diff" in registry()
        reset_registry_for_tests()


class TestOutputDocument:
    def test_output_file_contents(self, tmp_path):
        from jseval.projections import run_all_discovered
        from jseval.projections.base import reset_registry_for_tests

        reset_registry_for_tests()
        _build_run(tmp_path, "20260422T060000_scifact", "cohort-a",
                   {"q1": 0.9, "q2": 0.8})
        current = _build_run(tmp_path, "20260422T070000_scifact", "cohort-a",
                             {"q1": 0.3, "q2": 0.8},
                             qrels={"q1": {"d1": 1}, "q2": {"d3": 1}})
        run_all_discovered(current.run_dir)
        doc = json.loads((current.run_dir / "projections" / "rank_diff.json")
                         .read_text(encoding="utf-8"))
        assert doc["projection_name"] == "rank_diff"
        assert doc["schema_version"] == 1
        assert doc["status"] == "ok"
        assert "hybrid" in doc["modes"]
        reset_registry_for_tests()
