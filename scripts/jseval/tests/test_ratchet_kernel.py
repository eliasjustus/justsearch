"""Tests for ratchet_kernel.py — the shared engine-quality gate kernel (tempdoc 640 K)."""

from __future__ import annotations

import json

import pytest

from jseval import ratchet_kernel as rk


def test_load_baselines_inline_passthrough(tmp_path):
    p = tmp_path / "b.json"
    p.write_text(json.dumps({"baselines": {"x": {"metrics": {"m": 1}}}}), encoding="utf-8")
    doc = rk.load_baselines_doc(p)  # no current_release -> returned as-is
    assert doc["baselines"]["x"]["metrics"]["m"] == 1


def test_load_baselines_projects_from_current_release_and_merges_fallback(tmp_path):
    (tmp_path / "release.v1.json").write_text(json.dumps({"measured": {"x": {}}}), encoding="utf-8")
    p = tmp_path / "b.json"
    p.write_text(json.dumps({
        "current_release": "release.v1.json",
        "fallback_baselines": {"y": {"floor": 0.1}},
    }), encoding="utf-8")
    doc = rk.load_baselines_doc(p, project_release=lambda r, b: {"baselines": {"x": {"floor": 0.5}}})
    assert doc["baselines"]["x"]["floor"] == 0.5   # projected from the release
    assert doc["baselines"]["y"]["floor"] == 0.1   # fallback merged (release wins on conflict)


def test_load_baselines_missing_release_degrades_to_fallback(tmp_path):
    p = tmp_path / "b.json"
    p.write_text(json.dumps({
        "current_release": "nope.json",
        "fallback_baselines": {"y": {"floor": 0.1}},
    }), encoding="utf-8")
    doc = rk.load_baselines_doc(p, project_release=lambda r, b: {"baselines": {"x": {}}})
    assert doc["baselines"] == {"y": {"floor": 0.1}}  # pointer dead -> fallback, no crash


def test_build_summary_surfaces_family_fields():
    report = {"exit_code": 1, "dataset": "d", "mode": "hybrid",
              "checks": [{"name": "ce_p50_ms", "status": "fail"}]}
    assert rk.build_summary(report, ("mode",)) == {
        "exit_code": 1, "dataset": "d", "mode": "hybrid", "checks": {"ce_p50_ms": "fail"}}
    # leak/relevance-style fields
    rep2 = {"exit_code": 0, "dataset": "d", "current": 0.1, "baseline": 0.2, "floor": 0.25, "checks": []}
    assert rk.build_summary(rep2, ("current", "baseline", "floor")) == {
        "exit_code": 0, "dataset": "d", "current": 0.1, "baseline": 0.2, "floor": 0.25, "checks": {}}


# --- tempdoc 644: engine-set homogeneity gate -------------------------------

def test_compare_engine_sets_ok_skip_mismatch():
    assert rk.compare_engine_sets(["dense", "reranker"], ["reranker", "dense"])[0] == "ok"
    assert rk.compare_engine_sets(None, ["dense"])[0] == "skip"          # backward-compat
    assert rk.compare_engine_sets(["dense"], None)[0] == "skip"
    verdict, reason = rk.compare_engine_sets(["dense", "splade"], ["dense", "reranker", "splade"])
    assert verdict == "mismatch"
    assert "reranker" in reason


def _baseline_with_release(tmp_path, engines):
    (tmp_path / "release.v1.json").write_text(
        json.dumps({"cohort": {"realized_engines": engines}}), encoding="utf-8")
    p = tmp_path / "baselines.json"
    p.write_text(json.dumps({"current_release": "release.v1.json"}), encoding="utf-8")
    return p


def _run_dir(tmp_path, engines):
    rd = tmp_path / "run"
    rd.mkdir()
    (rd / "manifest.json").write_text(
        json.dumps({"model_fingerprints": {"realized_engines": engines}}), encoding="utf-8")
    return rd


def test_baseline_engine_set_reads_release_cohort(tmp_path):
    p = _baseline_with_release(tmp_path, ["dense", "reranker", "splade"])
    assert rk.baseline_engine_set(p) == ["dense", "reranker", "splade"]


def test_baseline_engine_set_none_when_no_release(tmp_path):
    p = tmp_path / "b.json"
    p.write_text(json.dumps({"baselines": {}}), encoding="utf-8")
    assert rk.baseline_engine_set(p) is None


def test_assert_cohort_engines_mismatch_exits_2(tmp_path):
    # baseline CE-on, HEAD run CE-off (the silent worktree trap, now caught at comparison time).
    p = _baseline_with_release(tmp_path, ["dense", "reranker", "splade"])
    rd = _run_dir(tmp_path, ["dense", "splade"])
    with pytest.raises(SystemExit) as exc:
        rk.assert_cohort_engines(rd, p)
    assert exc.value.code == 2


def test_assert_cohort_engines_match_is_silent(tmp_path):
    p = _baseline_with_release(tmp_path, ["dense", "reranker", "splade"])
    rd = _run_dir(tmp_path, ["reranker", "dense", "splade"])  # order-insensitive
    rk.assert_cohort_engines(rd, p)  # no raise


def test_assert_cohort_engines_skip_when_baseline_unrecorded(tmp_path):
    p = tmp_path / "b.json"
    p.write_text(json.dumps({"baselines": {}}), encoding="utf-8")  # no release pointer → skip
    rd = _run_dir(tmp_path, ["dense", "splade"])
    rk.assert_cohort_engines(rd, p)  # no raise — backward-compatible


def test_assert_cohort_engines_allow_mismatch_overrides(tmp_path):
    p = _baseline_with_release(tmp_path, ["dense", "reranker", "splade"])
    rd = _run_dir(tmp_path, ["dense", "splade"])
    rk.assert_cohort_engines(rd, p, allow_mismatch=True)  # no raise
