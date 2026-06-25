"""Tests for correction_probe.py — spell-correction quality probe."""

from __future__ import annotations

from jseval.correction_probe import (
    CorrectionQuery,
    classify,
    compute_metrics,
    load_manifest,
)


# ---------------------------------------------------------------------------
# classify — pure function, no mocking needed
# ---------------------------------------------------------------------------

def _resp(applied: bool, corrected: str | None = None) -> dict:
    """Build a response in the canonical unified-trace shape.

    Tempdoc 549 Phase E4: correction is carried by the trace's CORRECTION stage
    (status=executed, detail=corrected query); SearchIntrospection was retired.
    """
    stage = {
        "id": "correction",
        "status": "executed" if applied else "skipped",
        "detail": corrected if applied else None,
    }
    return {"searchTrace": {"stages": [stage]}}


class TestClassify:
    def test_tp(self):
        cq = CorrectionQuery("srch", "search", "typo")
        assert classify(cq, _resp(True, "search")) == "TP"

    def test_tp_case_insensitive(self):
        cq = CorrectionQuery("srch", "Search", "typo")
        assert classify(cq, _resp(True, "search")) == "TP"

    def test_fn(self):
        cq = CorrectionQuery("srch", "search", "typo")
        assert classify(cq, _resp(False)) == "FN"

    def test_wc(self):
        cq = CorrectionQuery("srch", "search", "typo")
        assert classify(cq, _resp(True, "stretch")) == "WC"

    def test_fp(self):
        cq = CorrectionQuery("search", None, "correct_spelling")
        assert classify(cq, _resp(True, "research")) == "FP"

    def test_tn(self):
        cq = CorrectionQuery("search", None, "correct_spelling")
        assert classify(cq, _resp(False)) == "TN"

    def test_wc_null_corrected(self):
        cq = CorrectionQuery("srch", "search", "typo")
        assert classify(cq, _resp(True, None)) == "WC"

    def test_missing_introspection_is_no_correction(self):
        # Defensive: a response without introspection.correction classifies as no-correction.
        cq = CorrectionQuery("srch", "search", "typo")
        assert classify(cq, {}) == "FN"


# ---------------------------------------------------------------------------
# compute_metrics
# ---------------------------------------------------------------------------

class TestComputeMetrics:
    def test_perfect(self):
        classifications = [
            {"classification": "TP"}, {"classification": "TP"},
            {"classification": "TN"}, {"classification": "TN"},
        ]
        m = compute_metrics(classifications)
        assert m["precision"] == 1.0
        assert m["recall"] == 1.0
        assert m["false_positive_rate"] == 0.0

    def test_mixed(self):
        classifications = [
            {"classification": "TP"},   # 1 correct
            {"classification": "FN"},   # 1 missed
            {"classification": "WC"},   # 1 wrong
            {"classification": "FP"},   # 1 false alarm
            {"classification": "TN"},   # 1 correct negative
        ]
        m = compute_metrics(classifications)
        # precision = 1 / (1 + 1 + 1) = 1/3
        assert abs(m["precision"] - 1 / 3) < 0.01
        # recall = 1 / (1 + 1) = 0.5
        assert abs(m["recall"] - 0.5) < 0.01
        # fpr = 1 / (1 + 1) = 0.5
        assert abs(m["false_positive_rate"] - 0.5) < 0.01

    def test_no_typos(self):
        classifications = [
            {"classification": "TN"}, {"classification": "TN"},
        ]
        m = compute_metrics(classifications)
        assert m["precision"] is None
        assert m["recall"] is None
        assert m["false_positive_rate"] == 0.0

    def test_empty(self):
        m = compute_metrics([])
        assert m["total"] == 0
        assert m["precision"] is None


# ---------------------------------------------------------------------------
# load_manifest
# ---------------------------------------------------------------------------

class TestLoadManifest:
    def test_loads_default(self):
        manifest = load_manifest(None)
        assert len(manifest) > 0
        # Check structure
        assert manifest[0].query
        assert manifest[0].error_type

    def test_has_typo_and_control_queries(self):
        manifest = load_manifest(None)
        types = {q.error_type for q in manifest}
        assert "correct_spelling" in types
        # At least one misspelling type
        typo_types = types - {"correct_spelling"}
        assert len(typo_types) > 0

    def test_custom_manifest(self, tmp_path):
        import json

        p = tmp_path / "custom.json"
        p.write_text(json.dumps({
            "queries": [
                {"query": "tset", "expected_correction": "test", "type": "typo"},
                {"query": "correct", "expected_correction": None, "type": "correct_spelling"},
            ],
        }), encoding="utf-8")
        manifest = load_manifest(p)
        assert len(manifest) == 2
        assert manifest[0].expected_correction == "test"
        assert manifest[1].expected_correction is None
