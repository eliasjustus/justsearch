"""CLI-level wiring tests for tempdoc 718's --allow-chunk-incompleteness escape hatch across
all four ratchet gate commands (relevance/perf/leak/union-recall). Confirms the flag is actually
threaded to `ratchet_kernel.assert_chunk_completeness` at each of the four call sites, not just
declared -- a wrong-gate mistake here (flag exists but never reaches the assertion) would be
invisible to the unit-level `assert_chunk_completeness` tests in test_ratchet_kernel.py.

Each test uses an UNPINNED dataset (empty `baselines`) so the family-specific `evaluate()` call
-- if the chunk guard lets a run through -- trivially exits 0 ("skip"), isolating the assertion
under test to "did the chunk guard fire / not fire," not the family gate's own logic.
"""

from __future__ import annotations

import json

from click.testing import CliRunner

from jseval.cli import main


def _degenerate_run_dir(tmp_path, name="run"):
    rd = tmp_path / name
    rd.mkdir()
    (rd / "summary.json").write_text(json.dumps({
        "dataset": "d", "per_mode": {},
        "chunk_completeness": {
            "expected": 50, "observed": 0, "verdict": "degenerate",
            "reasons": ["expected_chunk_docs=50 > 0 but observed chunk_doc_count == 0"],
        },
    }), encoding="utf-8")
    return rd


def _degenerate_run_dir_with_projection(tmp_path, name="run"):
    rd = _degenerate_run_dir(tmp_path, name)
    (rd / "projections").mkdir()
    (rd / "projections" / "staged_recall_accounting.json").write_text(
        json.dumps({"status": "ok", "aggregate": {"leak_rate": 0.0, "leg_union_recall": 1.0}}),
        encoding="utf-8")
    return rd


def _empty_baselines(tmp_path, name):
    p = tmp_path / name
    p.write_text(json.dumps({"baselines": {}}), encoding="utf-8")
    return p


class TestRelevanceGateChunkWiring:
    def test_degenerate_run_exits_2_without_override(self, tmp_path):
        rd = _degenerate_run_dir(tmp_path)
        baselines = _empty_baselines(tmp_path, "relevance.json")
        r = CliRunner().invoke(main, [
            "relevance-gate", "--dataset", "d", "--run-dir", str(rd),
            "--baselines", str(baselines),
        ])
        assert r.exit_code == 2, r.output
        assert "chunk-completeness guard" in r.output

    def test_override_flag_lets_it_through(self, tmp_path):
        rd = _degenerate_run_dir(tmp_path)
        baselines = _empty_baselines(tmp_path, "relevance.json")
        r = CliRunner().invoke(main, [
            "relevance-gate", "--dataset", "d", "--run-dir", str(rd),
            "--baselines", str(baselines), "--allow-chunk-incompleteness",
        ])
        assert r.exit_code == 0, r.output  # unpinned dataset -> skip, once past the chunk guard


class TestPerfGateChunkWiring:
    def test_degenerate_run_exits_2_without_override(self, tmp_path):
        rd = _degenerate_run_dir(tmp_path)
        baselines = _empty_baselines(tmp_path, "perf.json")
        r = CliRunner().invoke(main, [
            "perf-gate", "--dataset", "d", "--run-dir", str(rd),
            "--baselines", str(baselines),
        ])
        assert r.exit_code == 2, r.output
        assert "chunk-completeness guard" in r.output

    def test_override_flag_lets_it_through(self, tmp_path):
        rd = _degenerate_run_dir(tmp_path)
        baselines = _empty_baselines(tmp_path, "perf.json")
        r = CliRunner().invoke(main, [
            "perf-gate", "--dataset", "d", "--run-dir", str(rd),
            "--baselines", str(baselines), "--allow-chunk-incompleteness",
        ])
        assert r.exit_code == 0, r.output


class TestLeakGateChunkWiring:
    def test_degenerate_run_exits_2_without_override(self, tmp_path):
        rd = _degenerate_run_dir(tmp_path)  # no projection needed -- chunk guard fires first
        baselines = _empty_baselines(tmp_path, "leak.json")
        r = CliRunner().invoke(main, [
            "leak-gate", "--dataset", "d", "--run-dir", str(rd),
            "--baselines", str(baselines),
        ])
        assert r.exit_code == 2, r.output
        assert "chunk-completeness guard" in r.output

    def test_override_flag_lets_it_through(self, tmp_path):
        rd = _degenerate_run_dir_with_projection(tmp_path)
        baselines = _empty_baselines(tmp_path, "leak.json")
        r = CliRunner().invoke(main, [
            "leak-gate", "--dataset", "d", "--run-dir", str(rd),
            "--baselines", str(baselines), "--allow-chunk-incompleteness",
        ])
        assert r.exit_code == 0, r.output


class TestUnionRecallGateChunkWiring:
    def test_degenerate_run_exits_2_without_override(self, tmp_path):
        rd = _degenerate_run_dir(tmp_path)  # no projection needed -- chunk guard fires first
        baselines = _empty_baselines(tmp_path, "union.json")
        r = CliRunner().invoke(main, [
            "union-recall-gate", "--dataset", "d", "--run-dir", str(rd),
            "--baselines", str(baselines),
        ])
        assert r.exit_code == 2, r.output
        assert "chunk-completeness guard" in r.output

    def test_override_flag_lets_it_through(self, tmp_path):
        rd = _degenerate_run_dir_with_projection(tmp_path)
        baselines = _empty_baselines(tmp_path, "union.json")
        r = CliRunner().invoke(main, [
            "union-recall-gate", "--dataset", "d", "--run-dir", str(rd),
            "--baselines", str(baselines), "--allow-chunk-incompleteness",
        ])
        assert r.exit_code == 0, r.output


def test_env_var_override_works_without_the_flag(tmp_path, monkeypatch):
    # The JUSTSEARCH_ALLOW_CHUNK_INCOMPLETENESS=1 escape hatch, not the CLI flag.
    monkeypatch.setenv("JUSTSEARCH_ALLOW_CHUNK_INCOMPLETENESS", "1")
    rd = _degenerate_run_dir(tmp_path)
    baselines = _empty_baselines(tmp_path, "relevance.json")
    r = CliRunner().invoke(main, [
        "relevance-gate", "--dataset", "d", "--run-dir", str(rd), "--baselines", str(baselines),
    ])
    assert r.exit_code == 0, r.output
