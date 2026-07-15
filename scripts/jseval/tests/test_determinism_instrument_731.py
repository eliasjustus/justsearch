"""Tests for experiments/determinism_instrument_731.py — tempdoc 731 Increment I5.

The instrument script lives under experiments/ (a deliberate non-CLI-surface choice, see its
module docstring), so it is loaded here via importlib from its file path rather than a
package import. No live HTTP calls: `capture()` imports httpx lazily and is not exercised —
only the query-file loader, the pass loaders (both input shapes), and the pure compare logic
are under test, all against synthetic fixtures.
"""

from __future__ import annotations

import importlib.util
import json
import sys
from pathlib import Path

import pytest

_MODULE_PATH = (
    Path(__file__).resolve().parent.parent / "experiments" / "determinism_instrument_731.py"
)
_spec = importlib.util.spec_from_file_location("determinism_instrument_731", _MODULE_PATH)
di = importlib.util.module_from_spec(_spec)
sys.modules[_spec.name] = di
_spec.loader.exec_module(di)  # type: ignore[union-attr]


# ---------------------------------------------------------------------------
# load_queries
# ---------------------------------------------------------------------------

class TestLoadQueries:
    def test_json_list_shape(self, tmp_path):
        path = tmp_path / "queries.json"
        path.write_text(
            json.dumps([{"qid": "q1", "query": "foo"}, {"qid": "q2", "query": "bar"}]),
            encoding="utf-8",
        )
        out = di.load_queries(path)
        assert out == [{"qid": "q1", "query": "foo"}, {"qid": "q2", "query": "bar"}]

    def test_beir_jsonl_shape(self, tmp_path):
        path = tmp_path / "queries.jsonl"
        path.write_text(
            '{"_id": "q1", "text": "foo"}\n{"_id": "q2", "text": "bar"}\n', encoding="utf-8"
        )
        out = di.load_queries(path)
        assert out == [{"qid": "q1", "query": "foo"}, {"qid": "q2", "query": "bar"}]

    def test_missing_fields_raise(self, tmp_path):
        path = tmp_path / "queries.json"
        path.write_text(json.dumps([{"qid": "q1"}]), encoding="utf-8")
        with pytest.raises(ValueError):
            di.load_queries(path)


# ---------------------------------------------------------------------------
# Pure compare primitives
# ---------------------------------------------------------------------------

class TestJaccard:
    def test_identical(self):
        assert di.jaccard(["a", "b", "c"], ["a", "b", "c"], 10) == 1.0

    def test_disjoint(self):
        assert di.jaccard(["a", "b"], ["c", "d"], 10) == 0.0

    def test_partial_overlap(self):
        # top-3: {a,b,c} vs {a,b,d} -> intersection 2, union 4
        assert di.jaccard(["a", "b", "c"], ["a", "b", "d"], 3) == pytest.approx(0.5)

    def test_both_empty_is_perfect_agreement(self):
        assert di.jaccard([], [], 10) == 1.0

    def test_respects_k_window(self):
        # Only the first 2 of each considered; disjoint there even though full overlaps
        a = ["a", "b", "x"]
        b = ["c", "d", "x"]
        assert di.jaccard(a, b, 2) == 0.0


class TestRankShifts:
    def test_stable_rank(self):
        a_ids = ["d1", "d2", "d3"]
        b_ids = ["d1", "d2", "d3"]
        shifts = di.rank_shifts(a_ids, b_ids, 3)
        assert all(s["shift"] == 0 and not s["dropped"] for s in shifts)

    def test_shifted_rank(self):
        a_ids = ["d1", "d2", "d3"]
        b_ids = ["d2", "d3", "d1"]
        shifts = di.rank_shifts(a_ids, b_ids, 3)
        d1 = next(s for s in shifts if s["doc_id"] == "d1")
        assert d1["rank_a"] == 1 and d1["rank_b"] == 3 and d1["shift"] == 2

    def test_dropped_doc(self):
        a_ids = ["d1", "d2", "d3"]
        b_ids = ["d2", "d3", "d4"]  # d1 fell entirely out of the captured window
        shifts = di.rank_shifts(a_ids, b_ids, 3)
        d1 = next(s for s in shifts if s["doc_id"] == "d1")
        assert d1["dropped"] is True
        assert d1["rank_b"] is None
        assert d1["shift"] is None

    def test_swing_magnitude_uses_floor_for_dropped(self):
        shifts = [
            {"doc_id": "d1", "rank_a": 1, "rank_b": None, "shift": None, "dropped": True},
            {"doc_id": "d2", "rank_a": 2, "rank_b": 4, "shift": 2, "dropped": False},
        ]
        # dropped floor: out_of_topk_floor(11) - rank_a(1) = 10, larger than the shift of 2
        assert di.swing_magnitude(shifts, out_of_topk_floor=11) == 10

    def test_swing_magnitude_empty(self):
        assert di.swing_magnitude([], out_of_topk_floor=11) == 0


# ---------------------------------------------------------------------------
# Gating tiers
# ---------------------------------------------------------------------------

def _record(qid, doc_ids, decision_kind=None, effective_mode=None, gating_weight=None,
            source="raw-capture"):
    return di.QueryRecord(
        qid=qid, doc_ids=doc_ids, decision_kind=decision_kind, effective_mode=effective_mode,
        gating_weight=gating_weight, source=source,
    )


class TestGatingDiffers:
    def test_tier2_flip_detected(self):
        a = _record("q1", ["d1"], gating_weight=0.75)
        b = _record("q1", ["d1"], gating_weight=0.3)
        g = di.gating_differs(a, b, threshold=0.5)
        assert g["tier"] == "fine"
        assert g["tier2_low_signal_a"] is False
        assert g["tier2_low_signal_b"] is True
        assert g["flip"] is True

    def test_tier2_stable_no_flip(self):
        a = _record("q1", ["d1"], gating_weight=0.75)
        b = _record("q1", ["d1"], gating_weight=0.74)
        g = di.gating_differs(a, b, threshold=0.5)
        assert g["tier"] == "fine"
        assert g["flip"] is False

    def test_falls_back_to_tier1_when_weight_unavailable(self):
        a = _record("q1", ["d1"], decision_kind="multi_leg", gating_weight=None)
        b = _record("q1", ["d1"], decision_kind="sparse_shortcut", gating_weight=None)
        g = di.gating_differs(a, b, threshold=0.5)
        assert g["tier"] == "coarse"
        assert g["tier1_flip"] is True
        assert g["flip"] is True

    def test_tier1_no_flip_when_stable(self):
        a = _record("q1", ["d1"], decision_kind="multi_leg", effective_mode="hybrid")
        b = _record("q1", ["d1"], decision_kind="multi_leg", effective_mode="hybrid")
        g = di.gating_differs(a, b, threshold=0.5)
        assert g["flip"] is False

    def test_effective_mode_change_counts_as_tier1_flip(self):
        a = _record("q1", ["d1"], effective_mode="hybrid")
        b = _record("q1", ["d1"], effective_mode="bm25_only")
        g = di.gating_differs(a, b, threshold=0.5)
        assert g["tier1_flip"] is True


# ---------------------------------------------------------------------------
# compare_passes end-to-end (synthetic QueryRecord maps)
# ---------------------------------------------------------------------------

class TestComparePasses:
    def test_known_overlap_and_shift(self):
        pass_a = {"q1": _record("q1", ["d1", "d2", "d3", "d4"])}
        pass_b = {"q1": _record("q1", ["d1", "d3", "d2", "d5"])}
        report = di.compare_passes(pass_a, pass_b, top_k=4, rank_shift_top_n=3)
        q = report["per_query"][0]
        assert q["qid"] == "q1"
        # top-4: {d1,d2,d3,d4} vs {d1,d3,d2,d5} -> intersection 3, union 5
        assert q["jaccard_top_k"] == pytest.approx(3 / 5)
        d3 = next(s for s in q["rank_shifts"] if s["doc_id"] == "d3")
        assert d3["rank_a"] == 3 and d3["rank_b"] == 2 and d3["shift"] == -1

    def test_missing_queries_reported_not_silently_dropped(self):
        pass_a = {"q1": _record("q1", ["d1"]), "q2": _record("q2", ["d2"])}
        pass_b = {"q1": _record("q1", ["d1"])}
        report = di.compare_passes(pass_a, pass_b)
        assert report["aggregate"]["query_count"] == 1
        assert report["aggregate"]["missing_in_pass_b"] == ["q2"]

    def test_gating_flip_case_drives_confirmed_verdict(self):
        # Two queries: one flips gating AND swings hard; one is stable with a small swing.
        pass_a = {
            "flip_q": _record("flip_q", ["d1", "d2", "d3"], gating_weight=0.75),
            "stable_q": _record("stable_q", ["e1", "e2", "e3"], gating_weight=0.75),
        }
        pass_b = {
            # d1 (rank 1 in A) falls completely out of pass B's window -> large swing
            "flip_q": _record("flip_q", ["d9", "d8", "d2"], gating_weight=0.3),
            "stable_q": _record("stable_q", ["e1", "e2", "e3"], gating_weight=0.74),
        }
        report = di.compare_passes(pass_a, pass_b, top_k=3, rank_shift_top_n=3)
        assert report["aggregate"]["gating_flip_count"] == 1
        assert report["h4b"]["verdict"] == "confirmed"

    def test_no_flips_verdict(self):
        pass_a = {"q1": _record("q1", ["d1", "d2"], gating_weight=0.75)}
        pass_b = {"q1": _record("q1", ["d1", "d2"], gating_weight=0.74)}
        report = di.compare_passes(pass_a, pass_b, top_k=2)
        assert report["h4b"]["verdict"] == "no_gating_flips_observed"
        assert report["aggregate"]["gating_flip_count"] == 0

    def test_gating_tier_used_reports_coarse_when_no_weights(self):
        pass_a = {"q1": _record("q1", ["d1"], decision_kind="multi_leg")}
        pass_b = {"q1": _record("q1", ["d1"], decision_kind="multi_leg")}
        report = di.compare_passes(pass_a, pass_b)
        assert report["aggregate"]["gating_tier_used"] == "coarse"


# ---------------------------------------------------------------------------
# load_pass — shape auto-detection against on-disk fixtures
# ---------------------------------------------------------------------------

class TestLoadPassRawCapture:
    def _write_capture(self, dir_path: Path, qid: str, *, doc_ids, decision_kind=None,
                        effective_mode=None, vector_rrf=None, vector_rank=None):
        dir_path.mkdir(parents=True, exist_ok=True)
        trace = []
        if vector_rrf is not None and vector_rank is not None:
            trace = [
                {
                    "id": "dense-retrieval",
                    "detail": {"vector_rrf": vector_rrf, "vector_rank": vector_rank},
                }
            ]
        response = {
            "results": [{"id": d, "score": 1.0, "trace": trace if i == 0 else []}
                        for i, d in enumerate(doc_ids)],
            "searchTrace": {"decisionKind": decision_kind, "effectiveMode": effective_mode},
        }
        (dir_path / f"{qid}.json").write_text(json.dumps(response), encoding="utf-8")

    def test_loads_doc_ids_and_tier1_fields(self, tmp_path):
        pass_dir = tmp_path / "pass-a"
        self._write_capture(
            pass_dir, "q1", doc_ids=["d1", "d2"],
            decision_kind="multi_leg", effective_mode="hybrid",
        )
        records = di.load_pass(pass_dir)
        assert records["q1"].doc_ids == ["d1", "d2"]
        assert records["q1"].decision_kind == "multi_leg"
        assert records["q1"].effective_mode == "hybrid"
        assert records["q1"].source == "raw-capture"

    def test_manifest_sidecar_is_ignored(self, tmp_path):
        pass_dir = tmp_path / "pass-a"
        self._write_capture(pass_dir, "q1", doc_ids=["d1"])
        (pass_dir / "_manifest.json").write_text("{}", encoding="utf-8")
        records = di.load_pass(pass_dir)
        assert set(records) == {"q1"}

    def test_tier2_weight_derived_from_dense_retrieval_stage(self):
        # vectorWeight = vector_rrf * (rrf_k + vector_rank); with rrf_k=60, rank=1:
        # weight=0.75 -> vector_rrf = 0.75 / 61
        rrf = 0.75 / 61
        results = [{"id": "d1", "trace": [
            {"id": "dense-retrieval", "detail": {"vector_rrf": rrf, "vector_rank": 1}}
        ]}]
        weight = di._extract_gating_weight(results, rrf_k=60)
        assert weight == pytest.approx(0.75, abs=1e-6)

    def test_tier2_weight_none_when_detail_absent(self):
        results = [{"id": "d1", "trace": [{"id": "dense-retrieval", "detail": {}}]}]
        assert di._extract_gating_weight(results, rrf_k=60) is None

    def test_tier2_ignores_non_dense_stage_detail(self):
        # A fusion-stage detail carrying an unrelated key must not be misread as the gating weight.
        results = [{"id": "d1", "trace": [
            {"id": "fusion", "detail": {"rrf": 0.5, "rrf_base": 0.5}}
        ]}]
        assert di._extract_gating_weight(results, rrf_k=60) is None


class TestLoadPassJsevalRunDir:
    def test_loads_predicted_doc_ids_and_decision_kind(self, tmp_path):
        entries = [
            {
                "qid": "q1",
                "predictedDocIds": ["d1", "d2"],
                "decision_kind": "multi_leg",
                "effectiveMode": "hybrid",
            }
        ]
        (tmp_path / "hybrid_per_query.json").write_text(json.dumps(entries), encoding="utf-8")
        records = di.load_pass(tmp_path)
        assert records["q1"].doc_ids == ["d1", "d2"]
        assert records["q1"].decision_kind == "multi_leg"
        assert records["q1"].gating_weight is None  # Tier 2 unavailable from this shape
        assert records["q1"].source == "jseval-per-query"

    def test_ambiguous_modes_require_explicit_selection(self, tmp_path):
        (tmp_path / "hybrid_per_query.json").write_text("[]", encoding="utf-8")
        (tmp_path / "bm25_per_query.json").write_text("[]", encoding="utf-8")
        with pytest.raises(ValueError):
            di.load_pass(tmp_path)
        # explicit mode resolves the ambiguity
        records = di.load_pass(tmp_path, mode="hybrid")
        assert records == {}

    def test_unknown_explicit_mode_raises(self, tmp_path):
        (tmp_path / "hybrid_per_query.json").write_text("[]", encoding="utf-8")
        with pytest.raises(ValueError):
            di.load_pass(tmp_path, mode="nonexistent")


# ---------------------------------------------------------------------------
# CLI surface smoke checks
# ---------------------------------------------------------------------------

class TestCli:
    def test_help_exits_zero(self, capsys):
        with pytest.raises(SystemExit) as exc:
            di.build_arg_parser().parse_args(["--help"])
        assert exc.value.code == 0

    def test_capture_without_queries_errors(self, tmp_path):
        rc = di.main(["--capture", "--out", str(tmp_path)])
        assert rc == 2
