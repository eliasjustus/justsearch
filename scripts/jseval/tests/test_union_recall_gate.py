"""Tests for the recall-completeness ratchet (tempdoc 699 / D-005 recall-survival sibling)."""

from __future__ import annotations

import json

from jseval.union_recall_gate import (
    DEFAULT_TOLERANCE_ABS,
    derive_baselines,
    evaluate,
    project_release_to_baselines,
)


def _proj(union_recall, status="ok"):
    return {"status": status, "aggregate": {"leg_union_recall": union_recall}}


def _baselines(dataset, floor, tol=0.05):
    return {"baselines": {dataset: {"leg_union_recall_min": floor, "tolerance_abs": tol}}}


class TestUnionRecallGate:
    def test_unpinned_dataset_does_not_gate(self):
        rep = evaluate({"baselines": {}}, _proj(0.9), "mixed/enron-qa")
        assert rep["exit_code"] == 0
        assert rep["checks"][0]["status"] == "skip"

    def test_above_floor_ok(self):
        rep = evaluate(_baselines("d", 0.80), _proj(0.78), "d")  # 0.78 >= 0.80-0.05
        assert rep["exit_code"] == 0
        assert rep["checks"][0]["status"] == "ok"

    def test_below_floor_regression(self):
        rep = evaluate(_baselines("d", 0.80), _proj(0.70), "d")  # 0.70 < 0.80-0.05
        assert rep["exit_code"] == 1
        assert rep["checks"][0]["status"] == "fail"

    def test_missing_projection_status_exit_2(self):
        rep = evaluate(_baselines("d", 0.80), _proj(None, status="insufficient-modes"), "d")
        assert rep["exit_code"] == 2

    def test_missing_union_recall_exit_2(self):
        rep = evaluate(_baselines("d", 0.80), {"status": "ok", "aggregate": {}}, "d")
        assert rep["exit_code"] == 2

    def test_tolerance_default_used_when_absent(self):
        # floor 0.80, no per-corpus tolerance → default 0.05 → limit 0.75.
        bl = {"baselines": {"d": {"leg_union_recall_min": 0.80}}, "tolerance_default_abs": 0.05}
        assert evaluate(bl, _proj(0.75), "d")["exit_code"] == 0
        assert evaluate(bl, _proj(0.74), "d")["exit_code"] == 1


class TestDeriveBaselines:
    def test_measured_rate_becomes_floor(self):
        out = derive_baselines({"mixed/enron-qa": _proj(0.91)})
        row = out["baselines"]["mixed/enron-qa"]
        assert row["leg_union_recall_min"] == 0.91  # the *measured* rate, not measured-tol
        assert row["tolerance_abs"] == DEFAULT_TOLERANCE_ABS
        assert out["schema"] == "union-recall-gate-baseline.v1"
        assert out["derived_from_runs"] is True

    def test_per_corpus_tolerance_override(self):
        out = derive_baselines(
            {"d": _proj(0.85)}, tolerance_default_abs=0.05,
            per_corpus_tolerance={"d": 0.02})
        assert out["baselines"]["d"]["tolerance_abs"] == 0.02
        assert out["tolerance_default_abs"] == 0.05

    def test_skips_non_ok_or_missing_projection(self):
        out = derive_baselines({
            "ok": _proj(0.88),
            "bad": _proj(None, status="insufficient-modes"),
            "empty": {"status": "ok", "aggregate": {}},
        })
        assert set(out["baselines"]) == {"ok"}  # only the well-formed projection pins

    def test_roundtrip_measured_run_passes_regression_fires(self):
        # A run at the measured rate passes; a run below measured-tolerance fires.
        derived = derive_baselines({"d": _proj(0.85)}, tolerance_default_abs=0.05)
        assert evaluate(derived, _proj(0.85), "d")["exit_code"] == 0   # at baseline → pass
        assert evaluate(derived, _proj(0.80), "d")["exit_code"] == 0   # within tolerance → pass
        assert evaluate(derived, _proj(0.79), "d")["exit_code"] == 1   # below → regression


def test_union_recall_gate_derive_canonicalizes_bare_beir_slug(tmp_path):
    import json as _json
    from click.testing import CliRunner
    from jseval.cli import main

    data_dir = tmp_path
    run_dir = data_dir / "eval-results" / "20260701_000000_scifact"
    (run_dir / "projections").mkdir(parents=True)
    (run_dir / "projections" / "staged_recall_accounting.json").write_text(
        _json.dumps({"status": "ok", "aggregate": {"leg_union_recall": 0.93}}), encoding="utf-8")
    out_path = tmp_path / "out.json"

    r = CliRunner().invoke(main, [
        "union-recall-gate-derive", "--data-dir", str(data_dir), "--datasets", "scifact",
        "--out", str(out_path),
    ])
    assert r.exit_code == 0, r.output
    derived = _json.loads(out_path.read_text(encoding="utf-8"))
    # the RAW input "scifact" is what locates the run directory (named from jseval run's literal
    # --dataset argument), but the OUTPUT key is canonicalized to match relevance-/perf-/leak-gate.
    assert "beir/scifact" in derived["baselines"]
    assert "scifact" not in derived["baselines"]
    assert derived["baselines"]["beir/scifact"]["leg_union_recall_min"] == 0.93


# --- current_release pointer + fallback_baselines (mirrors leak-gate's tempdoc 683 pattern) ---

class TestReleaseProjection:
    def _release(self, union_recall=None, release_id="rel-test-2026-01-01"):
        doc = {"schema": "release.v1", "release_id": release_id, "cohort": {"git_sha": "abc123def4"}}
        if union_recall is not None:
            doc["union_recall"] = union_recall
        return doc

    def test_projects_release_union_recall_section(self):
        rel = self._release(union_recall={"beir/scifact": {"leg_union_recall": 0.90}})
        out = project_release_to_baselines(rel, tolerance_default_abs=0.05)
        row = out["baselines"]["beir/scifact"]
        assert row["leg_union_recall_min"] == 0.90
        assert row["tolerance_abs"] == 0.05
        assert row["src"] == "projected from release rel-test-2026-01-01"
        assert out["projected_from_release"] is True

    def test_projects_nothing_without_union_recall_section(self):
        out = project_release_to_baselines(self._release())
        assert out["baselines"] == {}

    def test_per_corpus_tolerance_override(self):
        rel = self._release(union_recall={"d": {"leg_union_recall": 0.5}})
        out = project_release_to_baselines(
            rel, tolerance_default_abs=0.05, per_corpus_tolerance={"d": 0.01})
        assert out["baselines"]["d"]["tolerance_abs"] == 0.01

    def test_pointer_falls_back_when_release_has_no_union_recall_section(self, tmp_path):
        """With a pointer to a release that carries no `union_recall` section, the loaded
        baselines are exactly the fallback values — gate behavior unchanged vs. an inline file."""
        from jseval.ratchet_kernel import load_baselines_doc

        fallback = {"mixed/enron-qa": {"leg_union_recall_min": 0.87, "tolerance_abs": 0.05}}
        (tmp_path / "release.v1.json").write_text(
            json.dumps({"schema": "release.v1", "measured": {}}), encoding="utf-8")
        bp = tmp_path / "union-recall-gate-baselines.v1.json"
        bp.write_text(json.dumps({
            "schema": "union-recall-gate-baseline.v1",
            "current_release": "release.v1.json",
            "tolerance_default_abs": 0.05,
            "fallback_baselines": fallback,
        }), encoding="utf-8")
        doc = load_baselines_doc(bp, project_release=lambda rel, base:
                                 project_release_to_baselines(rel, tolerance_default_abs=0.05))
        assert doc["baselines"] == fallback
        # And evaluate over the loaded doc behaves exactly like the old inline shape.
        assert evaluate(doc, _proj(0.83), "mixed/enron-qa")["exit_code"] == 0
        assert evaluate(doc, _proj(0.81), "mixed/enron-qa")["exit_code"] == 1

    def test_pointer_prefers_release_union_recall_section_over_fallback(self, tmp_path):
        from jseval.ratchet_kernel import load_baselines_doc

        (tmp_path / "release.v1.json").write_text(json.dumps({
            "schema": "release.v1", "release_id": "rel-test-2026-01-01",
            "union_recall": {"mixed/enron-qa": {"leg_union_recall": 0.95}},
        }), encoding="utf-8")
        bp = tmp_path / "union-recall-gate-baselines.v1.json"
        bp.write_text(json.dumps({
            "current_release": "release.v1.json",
            "tolerance_default_abs": 0.05,
            "fallback_baselines": {
                "mixed/enron-qa": {"leg_union_recall_min": 0.5, "tolerance_abs": 0.05},
                "beir/scifact": {"leg_union_recall_min": 0.93, "tolerance_abs": 0.05},
            },
        }), encoding="utf-8")
        doc = load_baselines_doc(bp, project_release=lambda rel, base:
                                 project_release_to_baselines(rel, tolerance_default_abs=0.05))
        assert doc["baselines"]["mixed/enron-qa"]["leg_union_recall_min"] == 0.95  # release wins
        assert doc["baselines"]["beir/scifact"]["leg_union_recall_min"] == 0.93  # fallback survives
