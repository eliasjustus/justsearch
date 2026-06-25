"""Tests for the cross-corpus recall-attribution profile (tempdoc 636)."""

from __future__ import annotations

from jseval.recall_profile import build_recall_profile, recommend


def _proj(leg_miss, leak, judge_low, ok, *, status="ok", judged=100, mism=0):
    """A minimal staged_recall_accounting-shaped projection dict."""
    agg = {} if status != "ok" else {
        "leg_miss_rate": leg_miss, "leak_rate": leak,
        "judge_low_rate": judge_low, "ok_rate": ok,
        "leg_union_recall": 1.0 - leg_miss, "judge_headroom_ceiling": 0.15,
    }
    return {
        "status": status,
        "n_queries_judged": judged,
        "aggregate": agg,
        "per_leg_recall": {"vector": 0.5, "lexical": 0.6, "splade": 0.2},
        "reconciliation": {"checked": judged, "mismatches": mism},
    }


class TestRecommend:
    def test_each_bucket_has_a_candidate_lever(self):
        assert "F1" in recommend("LEG_MISS") or "component" in recommend("LEG_MISS")
        assert "recall-complete" in recommend("CASCADE_LEAK")
        assert "judge" in recommend("JUDGE_RANK_LOW")

    def test_no_dominant_bucket(self):
        assert "strong" in recommend(None).lower() or "OK_RANK1" in recommend(None)


class TestBuildRecallProfile:
    def test_aggregate_dominant_bucket_and_recommendation(self):
        # legal-like (leg-miss heavy) + academic-like (judge heavy) → judge-rank dominates the mean.
        profile = build_recall_profile({
            "mixed/courtlistener-200": _proj(0.28, 0.07, 0.10, 0.55),
            "scifact": _proj(0.07, 0.01, 0.25, 0.67),
        })
        assert profile["schema"] == "recall-profile.v1"
        assert profile["n_members"] == 2 and profile["n_ok"] == 2
        mean = profile["aggregate"]["mean_failure_shares"]
        assert abs(mean["JUDGE_RANK_LOW"] - 0.175) < 1e-9   # (0.10+0.25)/2
        assert abs(mean["LEG_MISS"] - 0.175) < 1e-9         # (0.28+0.07)/2
        assert abs(mean["CASCADE_LEAK"] - 0.04) < 1e-9      # leak small everywhere
        # tie between LEG_MISS and JUDGE_RANK_LOW at 0.175 → max() picks deterministically;
        # the point is CASCADE_LEAK is NOT dominant (the real cross-corpus finding).
        assert profile["aggregate"]["dominant_bucket"] != "CASCADE_LEAK"
        assert profile["recommendation"]  # non-empty candidate lever
        assert "LEG_MISS" in profile["aggregate"]["fp_mapping"]

    def test_per_corpus_dominant_failure(self):
        profile = build_recall_profile({"legal": _proj(0.28, 0.07, 0.10, 0.55)})
        row = profile["members"][0]
        assert row["dominant_failure"] == "LEG_MISS"
        assert row["reconciliation_mismatches"] == 0

    def test_non_ok_projection_excluded_from_aggregate(self):
        profile = build_recall_profile({
            "ok": _proj(0.10, 0.05, 0.20, 0.65),
            "bad": _proj(None, None, None, None, status="insufficient-modes"),
        })
        assert profile["n_members"] == 2 and profile["n_ok"] == 1
        # the bad corpus is a member row but contributes nothing to the mean.
        assert profile["aggregate"]["mean_failure_shares"]["JUDGE_RANK_LOW"] == 0.20

    def test_empty_input(self):
        profile = build_recall_profile({})
        assert profile["n_members"] == 0 and profile["aggregate"]["dominant_bucket"] is None
