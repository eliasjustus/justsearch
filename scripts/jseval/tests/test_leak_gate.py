"""Tests for the recall-leak ratchet (tempdoc 636 / D-005)."""

from __future__ import annotations

from jseval.leak_gate import DEFAULT_TOLERANCE_ABS, derive_baselines, evaluate


def _proj(leak_rate, status="ok"):
    return {"status": status, "aggregate": {"leak_rate": leak_rate}}


def _baselines(dataset, ceiling, tol=0.05):
    return {"baselines": {dataset: {"leak_rate_max": ceiling, "tolerance_abs": tol}}}


class TestLeakGate:
    def test_unpinned_dataset_does_not_gate(self):
        rep = evaluate({"baselines": {}}, _proj(0.9), "mixed/enron-qa")
        assert rep["exit_code"] == 0
        assert rep["checks"][0]["status"] == "skip"

    def test_under_ceiling_ok(self):
        rep = evaluate(_baselines("d", 0.10), _proj(0.12), "d")  # 0.12 <= 0.10+0.05
        assert rep["exit_code"] == 0
        assert rep["checks"][0]["status"] == "ok"

    def test_over_ceiling_regression(self):
        rep = evaluate(_baselines("d", 0.10), _proj(0.20), "d")  # 0.20 > 0.10+0.05
        assert rep["exit_code"] == 1
        assert rep["checks"][0]["status"] == "fail"

    def test_missing_projection_status_exit_2(self):
        rep = evaluate(_baselines("d", 0.10), _proj(None, status="insufficient-modes"), "d")
        assert rep["exit_code"] == 2

    def test_missing_leak_rate_exit_2(self):
        rep = evaluate(_baselines("d", 0.10), {"status": "ok", "aggregate": {}}, "d")
        assert rep["exit_code"] == 2

    def test_tolerance_default_used_when_absent(self):
        # ceiling 0.10, no per-corpus tolerance → default 0.05 → limit 0.15.
        bl = {"baselines": {"d": {"leak_rate_max": 0.10}}, "tolerance_default_abs": 0.05}
        assert evaluate(bl, _proj(0.15), "d")["exit_code"] == 0
        assert evaluate(bl, _proj(0.16), "d")["exit_code"] == 1


class TestDeriveBaselines:
    def test_measured_rate_becomes_ceiling(self):
        out = derive_baselines({"mixed/enron-qa": _proj(0.07)})
        row = out["baselines"]["mixed/enron-qa"]
        assert row["leak_rate_max"] == 0.07  # the *measured* rate, not measured+tol
        assert row["tolerance_abs"] == DEFAULT_TOLERANCE_ABS
        assert out["schema"] == "leak-gate-baseline.v1"
        assert out["derived_from_runs"] is True

    def test_per_corpus_tolerance_override(self):
        out = derive_baselines(
            {"d": _proj(0.10)}, tolerance_default_abs=0.05,
            per_corpus_tolerance={"d": 0.02})
        assert out["baselines"]["d"]["tolerance_abs"] == 0.02
        assert out["tolerance_default_abs"] == 0.05

    def test_skips_non_ok_or_missing_projection(self):
        out = derive_baselines({
            "ok": _proj(0.12),
            "bad": _proj(None, status="insufficient-modes"),
            "empty": {"status": "ok", "aggregate": {}},
        })
        assert set(out["baselines"]) == {"ok"}  # only the well-formed projection pins

    def test_roundtrip_measured_run_passes_regression_fires(self):
        # A run at the measured rate passes; a run beyond measured+tolerance fires.
        derived = derive_baselines({"d": _proj(0.10)}, tolerance_default_abs=0.05)
        assert evaluate(derived, _proj(0.10), "d")["exit_code"] == 0   # at baseline → pass
        assert evaluate(derived, _proj(0.15), "d")["exit_code"] == 0   # within tolerance → pass
        assert evaluate(derived, _proj(0.16), "d")["exit_code"] == 1   # beyond → regression


# tempdoc 664 (twelfth pass): `leak-gate-derive` CLI-level canonicalization -- the bare-name-vs-
# canonical-slug inconsistency (leak-gate-baselines.v1.json had "scifact" while relevance-/perf-gate
# use "beir/scifact") was traced to this command never canonicalizing its --datasets input.

def test_leak_gate_derive_canonicalizes_bare_beir_slug(tmp_path):
    import json as _json
    from click.testing import CliRunner
    from jseval.cli import main

    data_dir = tmp_path
    run_dir = data_dir / "eval-results" / "20260701_000000_scifact"
    (run_dir / "projections").mkdir(parents=True)
    (run_dir / "projections" / "staged_recall_accounting.json").write_text(
        _json.dumps({"status": "ok", "aggregate": {"leak_rate": 0.02}}), encoding="utf-8")
    out_path = tmp_path / "out.json"

    r = CliRunner().invoke(main, [
        "leak-gate-derive", "--data-dir", str(data_dir), "--datasets", "scifact",
        "--out", str(out_path),
    ])
    assert r.exit_code == 0, r.output
    derived = _json.loads(out_path.read_text(encoding="utf-8"))
    # the RAW input "scifact" is what locates the run directory (named from jseval run's literal
    # --dataset argument), but the OUTPUT key is canonicalized to match relevance-/perf-gate.
    assert "beir/scifact" in derived["baselines"]
    assert "scifact" not in derived["baselines"]
    assert derived["baselines"]["beir/scifact"]["leak_rate_max"] == 0.02
