"""CLI-level wiring tests for register F-052's --allow-ce-degradation escape hatch across all
four ratchet gate commands (relevance/perf/leak/union-recall). Confirms the flag is actually
threaded to `ratchet_kernel.assert_ce_coverage` at each of the four call sites, not just declared
-- a wrong-gate mistake here (flag exists but never reaches the assertion) would be invisible to
the unit-level `assert_ce_coverage` tests in test_ratchet_kernel.py.

Sibling of test_chunk_completeness_gate_wiring.py; same isolation trick: an UNPINNED dataset
(empty `baselines`) makes the family gate trivially exit 0, so what is under test is only "did
the CE-coverage guard fire / not fire."
"""

from __future__ import annotations

import json

from click.testing import CliRunner

from jseval.cli import main


def _degraded_run_dir(tmp_path, name="run"):
    rd = tmp_path / name
    rd.mkdir()
    (rd / "summary.json").write_text(json.dumps({
        "dataset": "d", "per_mode": {},
        "ce_coverage": {
            "verdict": "degraded-ce",
            "tolerance": 0.02,
            "reasons": ["hybrid: degraded-ce -- 98/200 CE-eligible queries reranked; "
                        "102 silent drop(s) {'DEADLINE_EXCEEDED': 102}"],
            "per_mode": {"hybrid": {"verdict": "degraded-ce", "applied": 98, "eligible": 200,
                                    "silent_drops": 102}},
        },
    }), encoding="utf-8")
    return rd


def _degraded_run_dir_with_projection(tmp_path, name="run"):
    rd = _degraded_run_dir(tmp_path, name)
    (rd / "projections").mkdir()
    (rd / "projections" / "staged_recall_accounting.json").write_text(
        json.dumps({"status": "ok", "aggregate": {"leak_rate": 0.0, "leg_union_recall": 1.0}}),
        encoding="utf-8")
    return rd


def _empty_baselines(tmp_path, name):
    p = tmp_path / name
    p.write_text(json.dumps({"baselines": {}}), encoding="utf-8")
    return p


class TestRelevanceGateCeWiring:
    def test_degraded_run_exits_2_without_override(self, tmp_path):
        rd = _degraded_run_dir(tmp_path)
        baselines = _empty_baselines(tmp_path, "relevance.json")
        r = CliRunner().invoke(main, [
            "relevance-gate", "--dataset", "d", "--run-dir", str(rd),
            "--baselines", str(baselines),
        ])
        assert r.exit_code == 2, r.output
        assert "ce-coverage guard" in r.output

    def test_override_flag_lets_it_through(self, tmp_path):
        rd = _degraded_run_dir(tmp_path)
        baselines = _empty_baselines(tmp_path, "relevance.json")
        r = CliRunner().invoke(main, [
            "relevance-gate", "--dataset", "d", "--run-dir", str(rd),
            "--baselines", str(baselines), "--allow-ce-degradation",
        ])
        assert r.exit_code == 0, r.output  # unpinned dataset -> skip, once past the CE guard


class TestPerfGateCeWiring:
    def test_degraded_run_exits_2_without_override(self, tmp_path):
        rd = _degraded_run_dir(tmp_path)
        baselines = _empty_baselines(tmp_path, "perf.json")
        r = CliRunner().invoke(main, [
            "perf-gate", "--dataset", "d", "--run-dir", str(rd), "--baselines", str(baselines),
        ])
        assert r.exit_code == 2, r.output
        assert "ce-coverage guard" in r.output

    def test_override_flag_lets_it_through(self, tmp_path):
        rd = _degraded_run_dir(tmp_path)
        baselines = _empty_baselines(tmp_path, "perf.json")
        r = CliRunner().invoke(main, [
            "perf-gate", "--dataset", "d", "--run-dir", str(rd),
            "--baselines", str(baselines), "--allow-ce-degradation",
        ])
        assert r.exit_code == 0, r.output


class TestLeakGateCeWiring:
    def test_degraded_run_exits_2_without_override(self, tmp_path):
        rd = _degraded_run_dir(tmp_path)  # no projection needed -- the CE guard fires first
        baselines = _empty_baselines(tmp_path, "leak.json")
        r = CliRunner().invoke(main, [
            "leak-gate", "--dataset", "d", "--run-dir", str(rd), "--baselines", str(baselines),
        ])
        assert r.exit_code == 2, r.output
        assert "ce-coverage guard" in r.output

    def test_override_flag_lets_it_through(self, tmp_path):
        rd = _degraded_run_dir_with_projection(tmp_path)
        baselines = _empty_baselines(tmp_path, "leak.json")
        r = CliRunner().invoke(main, [
            "leak-gate", "--dataset", "d", "--run-dir", str(rd),
            "--baselines", str(baselines), "--allow-ce-degradation",
        ])
        assert r.exit_code == 0, r.output


class TestUnionRecallGateCeWiring:
    def test_degraded_run_exits_2_without_override(self, tmp_path):
        rd = _degraded_run_dir(tmp_path)  # no projection needed -- the CE guard fires first
        baselines = _empty_baselines(tmp_path, "union.json")
        r = CliRunner().invoke(main, [
            "union-recall-gate", "--dataset", "d", "--run-dir", str(rd),
            "--baselines", str(baselines),
        ])
        assert r.exit_code == 2, r.output
        assert "ce-coverage guard" in r.output

    def test_override_flag_lets_it_through(self, tmp_path):
        rd = _degraded_run_dir_with_projection(tmp_path)
        baselines = _empty_baselines(tmp_path, "union.json")
        r = CliRunner().invoke(main, [
            "union-recall-gate", "--dataset", "d", "--run-dir", str(rd),
            "--baselines", str(baselines), "--allow-ce-degradation",
        ])
        assert r.exit_code == 0, r.output


def test_env_var_override_works_without_the_flag(tmp_path, monkeypatch):
    monkeypatch.setenv("JUSTSEARCH_ALLOW_CE_DEGRADATION", "1")
    rd = _degraded_run_dir(tmp_path)
    baselines = _empty_baselines(tmp_path, "relevance.json")
    r = CliRunner().invoke(main, [
        "relevance-gate", "--dataset", "d", "--run-dir", str(rd), "--baselines", str(baselines),
    ])
    assert r.exit_code == 0, r.output
