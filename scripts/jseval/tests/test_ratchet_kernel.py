"""Tests for ratchet_kernel.py — the shared engine-quality gate kernel (tempdoc 640 K)."""

from __future__ import annotations

import json

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
