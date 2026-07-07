"""Tests for the staged_recall_accounting projection (tempdoc 636 / D-005)."""

from __future__ import annotations

from pathlib import Path

from jseval.projections.staged_recall_accounting import (
    BUCKETS,
    PROJECTION,
    produce,
)


def _pq(qid: str, predicted: list[str], recall: float, ndcg: float | None = None):
    """Minimal per-query entry (only the fields produce() reads)."""
    e = {"qid": qid, "predictedDocIds": predicted, "recallAtK": recall}
    if ndcg is not None:
        e["ndcgAtK"] = ndcg
    return e


def _write_trec(run_dir: Path, mode: str, ranked: dict[str, list[str]]) -> None:
    """Write a score-ranked ``{mode}_run.trec`` (file order = rank order)."""
    lines = []
    for qid, docs in ranked.items():
        for i, d in enumerate(docs, start=1):
            lines.append(f"{qid} Q0 {d} {i} {1.0 / i:.4f} jseval_{mode}")
    (run_dir / f"{mode}_run.trec").write_text("\n".join(lines) + "\n", encoding="utf-8")


class TestProjectionContract:
    def test_registered_metadata(self):
        assert PROJECTION.name == "staged_recall_accounting"
        assert PROJECTION.schema_version == 1
        assert PROJECTION.produce is produce

    def test_fp_mapping_annotation(self, synthetic_run_dir):
        # Conform-vocab annotation (Seven Failure Points) — present on both shapes,
        # an annotation not a rename (the bucket keys stay authoritative).
        # Tempdoc 643 correction (2026-07-01): FP2 "Missed the Top Ranked Documents" is defined
        # verbatim as "didn't rank highly enough to be RETURNED" -- that's CASCADE_LEAK, not
        # JUDGE_RANK_LOW (which has no canonical FP match and is deliberately omitted).
        synthetic_run_dir.with_per_query("hybrid", [_pq("q1", ["g"], 1.0)])
        synthetic_run_dir.with_qrels({"q1": {"g": 1}})
        out = produce(synthetic_run_dir.run_dir)  # insufficient-modes shape
        assert out["fp_mapping"]["LEG_MISS"].startswith("FP1")
        assert out["fp_mapping"]["CASCADE_LEAK"].startswith("FP2")
        assert "JUDGE_RANK_LOW" not in out["fp_mapping"]  # no canonical FP match
        assert "OK_RANK1" not in out["fp_mapping"]  # success case has no failure point

    def test_insufficient_modes_no_leg(self, synthetic_run_dir):
        # Only a final mode present (no vector/lexical/splade) → insufficient.
        synthetic_run_dir.with_per_query("hybrid", [_pq("q1", ["g"], 1.0)])
        synthetic_run_dir.with_qrels({"q1": {"g": 1}})
        out = produce(synthetic_run_dir.run_dir)
        assert out["status"] == "insufficient-modes"
        assert out["buckets"] == {b: [] for b in BUCKETS}

    def test_insufficient_modes_no_final(self, synthetic_run_dir):
        # Only a leg mode present (no hybrid/full) → insufficient.
        synthetic_run_dir.with_per_query("vector", [_pq("q1", ["g"], 1.0)])
        synthetic_run_dir.with_qrels({"q1": {"g": 1}})
        out = produce(synthetic_run_dir.run_dir)
        assert out["status"] == "insufficient-modes"


class TestBucketClassification:
    def _build(self, srd):
        rd = srd.run_dir
        # vector leg (no trec → predictedDocIds fallback for presence).
        srd.with_per_query("vector", [
            _pq("q_ok", ["g_ok"], 1.0),
            _pq("q_judge", ["g_judge"], 1.0),
            _pq("q_leak", ["g_leak", "x"], 1.0),   # leg HAS the gold
            _pq("q_miss", ["x", "y"], 0.0),         # leg never found it
        ])
        # hybrid final: per_query for reconciliation + trec for authoritative rank.
        # ndcgAtK drives final_ndcg → judge_headroom_ceiling.
        srd.with_per_query("hybrid", [
            _pq("q_ok", ["g_ok", "x"], 1.0, ndcg=1.0),
            _pq("q_judge", ["x", "y", "g_judge"], 1.0, ndcg=0.5),
            _pq("q_leak", ["x", "y", "z"], 0.0, ndcg=0.0),    # final DROPPED it
            _pq("q_miss", ["x", "y", "z"], 0.0, ndcg=0.0),
        ])
        _write_trec(rd, "hybrid", {
            "q_ok": ["g_ok", "x"],            # gold rank 1
            "q_judge": ["x", "y", "g_judge"],  # gold rank 3
            "q_leak": ["x", "y", "z"],         # gold absent
            "q_miss": ["x", "y", "z"],         # gold absent
        })
        srd.with_qrels({
            "q_ok": {"g_ok": 1}, "q_judge": {"g_judge": 1},
            "q_leak": {"g_leak": 1}, "q_miss": {"g_miss": 1},
        })
        return produce(rd)

    def test_each_bucket_classified(self, synthetic_run_dir):
        out = self._build(synthetic_run_dir)
        assert out["status"] == "ok"
        assert out["leg_modes"] == ["vector"]
        assert out["final_mode"] == "hybrid"
        b = out["buckets"]
        assert b["OK_RANK1"] == ["q_ok"]
        assert b["JUDGE_RANK_LOW"] == ["q_judge"]
        assert b["CASCADE_LEAK"] == ["q_leak"]
        assert b["LEG_MISS"] == ["q_miss"]

    def test_aggregate_rates(self, synthetic_run_dir):
        out = self._build(synthetic_run_dir)
        agg = out["aggregate"]
        assert out["n_queries_judged"] == 4
        assert agg["leak_rate"] == 0.25
        assert agg["leg_miss_rate"] == 0.25
        assert agg["judge_low_rate"] == 0.25
        assert agg["ok_rate"] == 0.25
        assert agg["leg_union_recall"] == 0.75   # g present in vector for 3/4
        assert agg["final_recall"] == 0.5         # in final top-N for 2/4
        # judge-headroom ceiling: oracle (== leg_union_recall 0.75) − final_ndcg (mean
        # of 1.0,0.5,0,0 = 0.375) = 0.375.
        assert agg["oracle_judge_ndcg_ceiling"] == 0.75
        assert abs(agg["final_ndcg"] - 0.375) < 1e-9
        assert abs(agg["judge_headroom_ceiling"] - 0.375) < 1e-9
        assert out["reconciliation"] == {"checked": 4, "mismatches": 0}
        # Tempdoc 643: q_judge lands at final rank 3 -> rank_3_5 bucket.
        assert agg["judge_rank_histogram"] == {
            "rank_2": 0, "rank_3_5": 1, "rank_6_10": 0, "rank_11_plus": 0,
        }


class TestJudgeRankHistogram:
    """Tempdoc 643: in-bucket rank distribution for JUDGE_RANK_LOW."""

    def _build_at_rank(self, srd, rank: int):
        rd = srd.run_dir
        filler = [f"x{i}" for i in range(rank - 1)]
        ranked_docs = filler + ["g"]
        srd.with_per_query("vector", [_pq("q1", ["g"], 1.0)])
        srd.with_per_query("hybrid", [_pq("q1", ranked_docs, 1.0)])
        _write_trec(rd, "hybrid", {"q1": ranked_docs})
        srd.with_qrels({"q1": {"g": 1}})
        return produce(rd)

    def test_rank_2_bucket(self, synthetic_run_dir):
        out = self._build_at_rank(synthetic_run_dir, 2)
        assert out["buckets"]["JUDGE_RANK_LOW"] == ["q1"]
        h = out["aggregate"]["judge_rank_histogram"]
        assert h == {"rank_2": 1, "rank_3_5": 0, "rank_6_10": 0, "rank_11_plus": 0}

    def test_rank_3_5_bucket_boundaries(self, synthetic_run_dir):
        for rank in (3, 5):
            out = self._build_at_rank(synthetic_run_dir, rank)
            assert out["aggregate"]["judge_rank_histogram"]["rank_3_5"] == 1, rank

    def test_rank_6_10_bucket_boundaries(self, synthetic_run_dir):
        for rank in (6, 10):
            out = self._build_at_rank(synthetic_run_dir, rank)
            assert out["aggregate"]["judge_rank_histogram"]["rank_6_10"] == 1, rank

    def test_rank_11_plus_overflow(self, synthetic_run_dir):
        out = self._build_at_rank(synthetic_run_dir, 11)
        assert out["aggregate"]["judge_rank_histogram"]["rank_11_plus"] == 1

    def test_ok_rank1_not_counted_in_histogram(self, synthetic_run_dir):
        out = self._build_at_rank(synthetic_run_dir, 1)
        assert out["buckets"]["OK_RANK1"] == ["q1"]
        h = out["aggregate"]["judge_rank_histogram"]
        assert sum(h.values()) == 0


class TestJudgeLowCostWeight:
    """Tempdoc 643 (E3): [0,1] cost-weighted severity over judge_rank_histogram."""

    def _build_at_rank(self, srd, rank: int):
        rd = srd.run_dir
        filler = [f"x{i}" for i in range(rank - 1)]
        ranked_docs = filler + ["g"]
        srd.with_per_query("vector", [_pq("q1", ["g"], 1.0)])
        srd.with_per_query("hybrid", [_pq("q1", ranked_docs, 1.0)])
        _write_trec(rd, "hybrid", {"q1": ranked_docs})
        srd.with_qrels({"q1": {"g": 1}})
        return produce(rd)

    def test_all_rank_2_is_near_free(self, synthetic_run_dir):
        out = self._build_at_rank(synthetic_run_dir, 2)
        assert abs(out["aggregate"]["judge_low_cost_weight"] - 0.1) < 1e-9

    def test_all_rank_6_10_is_full_cost(self, synthetic_run_dir):
        out = self._build_at_rank(synthetic_run_dir, 8)
        assert abs(out["aggregate"]["judge_low_cost_weight"] - 1.0) < 1e-9

    def test_mixed_buckets_weighted_average(self, synthetic_run_dir):
        # 2 at rank_2 (0.1 each) + 1 at rank_3_5 (0.4) + 1 at rank_6_10 (1.0), over 4 total:
        # (2*0.1 + 1*0.4 + 1*1.0) / 4 = 1.6 / 4 = 0.4 -- matches the module docstring's example.
        rd = synthetic_run_dir.run_dir
        synthetic_run_dir.with_per_query("vector", [
            _pq("q1", ["g1"], 1.0), _pq("q2", ["g2"], 1.0),
            _pq("q3", ["g3"], 1.0), _pq("q4", ["g4"], 1.0),
        ])
        synthetic_run_dir.with_per_query("hybrid", [
            _pq("q1", ["x0", "g1"], 1.0), _pq("q2", ["x0", "g2"], 1.0),
            _pq("q3", ["x0", "x1", "x2", "g3"], 1.0),
            _pq("q4", ["x0", "x1", "x2", "x3", "x4", "x5", "g4"], 1.0),
        ])
        _write_trec(rd, "hybrid", {
            "q1": ["x0", "g1"], "q2": ["x0", "g2"],
            "q3": ["x0", "x1", "x2", "g3"], "q4": ["x0", "x1", "x2", "x3", "x4", "x5", "g4"],
        })
        synthetic_run_dir.with_qrels({
            "q1": {"g1": 1}, "q2": {"g2": 1}, "q3": {"g3": 1}, "q4": {"g4": 1},
        })
        out = produce(rd)
        assert out["aggregate"]["judge_rank_histogram"] == {
            "rank_2": 2, "rank_3_5": 1, "rank_6_10": 1, "rank_11_plus": 0,
        }
        assert abs(out["aggregate"]["judge_low_cost_weight"] - 0.4) < 1e-9

    def test_empty_histogram_is_none(self, synthetic_run_dir):
        # No JUDGE_RANK_LOW queries at all (only OK_RANK1) -> nothing to weight.
        out = self._build_at_rank(synthetic_run_dir, 1)
        assert out["aggregate"]["judge_low_cost_weight"] is None


class TestTrecRankAuthority:
    """Confidence-pass finding: predictedDocIds is response-order, not score-order.

    The projection must take *rank* from the score-ranked ``.trec``, so a gold
    doc at response-position 2 but score-rank 1 buckets as OK_RANK1, not
    JUDGE_RANK_LOW.
    """

    def test_rank_from_trec_not_predicteddocids(self, synthetic_run_dir):
        rd = synthetic_run_dir.run_dir
        synthetic_run_dir.with_per_query("vector", [_pq("q1", ["g"], 1.0)])
        # response-order puts gold at position 2 ...
        synthetic_run_dir.with_per_query("hybrid", [_pq("q1", ["x", "g"], 1.0)])
        # ... but the score-ranked trec puts it at rank 1.
        _write_trec(rd, "hybrid", {"q1": ["g", "x"]})
        synthetic_run_dir.with_qrels({"q1": {"g": 1}})
        out = produce(rd)
        assert out["buckets"]["OK_RANK1"] == ["q1"]
        assert out["buckets"]["JUDGE_RANK_LOW"] == []
        assert out["reconciliation"]["mismatches"] == 0


class TestReconciliation:
    def test_mismatch_detected(self, synthetic_run_dir):
        """recallAtK>0 but gold absent from the final ranked list → 1 mismatch."""
        rd = synthetic_run_dir.run_dir
        synthetic_run_dir.with_per_query("vector", [_pq("q1", ["g"], 1.0)])
        synthetic_run_dir.with_per_query("hybrid", [_pq("q1", ["x", "y"], 1.0)])  # claims recall
        _write_trec(rd, "hybrid", {"q1": ["x", "y"]})  # but gold absent
        synthetic_run_dir.with_qrels({"q1": {"g": 1}})
        out = produce(rd)
        assert out["reconciliation"] == {"checked": 1, "mismatches": 1}
        # gold in leg, absent from final → cascade leak.
        assert out["buckets"]["CASCADE_LEAK"] == ["q1"]


class TestUnjudged:
    def test_query_without_qrels_excluded(self, synthetic_run_dir):
        rd = synthetic_run_dir.run_dir
        synthetic_run_dir.with_per_query("vector", [_pq("q1", ["g"], 1.0), _pq("q2", ["g"], 1.0)])
        synthetic_run_dir.with_per_query("hybrid", [_pq("q1", ["g"], 1.0), _pq("q2", ["g"], 1.0)])
        _write_trec(rd, "hybrid", {"q1": ["g"], "q2": ["g"]})
        synthetic_run_dir.with_qrels({"q1": {"g": 1}})  # q2 has no gold
        out = produce(rd)
        assert out["n_queries_judged"] == 1
        all_qids = [q for v in out["buckets"].values() for q in v]
        assert all_qids == ["q1"]


class TestCappedRun:
    """Regression: a capped run (--max-queries) must not count un-executed qrels
    entries as phantom LEG_MISS — attribution restricts to queries actually run."""

    def test_unrun_qrels_query_excluded(self, synthetic_run_dir):
        rd = synthetic_run_dir.run_dir
        # Only q1 was executed (present in per_query); q_unrun is in qrels but never queried.
        synthetic_run_dir.with_per_query("vector", [_pq("q1", ["g"], 1.0)])
        synthetic_run_dir.with_per_query("hybrid", [_pq("q1", ["g"], 1.0)])
        _write_trec(rd, "hybrid", {"q1": ["g"]})
        synthetic_run_dir.with_qrels({"q1": {"g": 1}, "q_unrun": {"gx": 1}})
        out = produce(rd)
        assert out["n_queries_judged"] == 1            # q_unrun excluded, not judged
        assert out["buckets"]["LEG_MISS"] == []        # NOT a phantom leg-miss
        all_qids = [q for v in out["buckets"].values() for q in v]
        assert all_qids == ["q1"]
