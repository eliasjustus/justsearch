"""Tempdoc 716: defaults-only compose across `run` → gates/calibrate.

Tax 1 regression (docs/observations.md:1933): a defaults `jseval run` writes
under `DEFAULT_JSEVAL_DATA_DIR/eval-results/`, and every gate's `--data-dir`
now DEFAULTS to `DEFAULT_JSEVAL_DATA_DIR` — so a defaults-only run followed by
a defaults-only gate composes with no operator path reasoning. These tests pin
the CLI wiring (the default actually reaches the command body), not just the
constants (test_paths.py pins those).
"""

from __future__ import annotations

import json

from click.testing import CliRunner

from jseval.cli import main


def _write_json(path, doc):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(doc), encoding="utf-8")


class TestGateDefaultDataDir:
    def test_gate_defaults_to_jseval_data_dir_and_finds_run(self, tmp_path, monkeypatch):
        # Re-point the gates module's captured default (the option lambda
        # closes over this module global).
        import jseval.commands.gates as gates_mod
        monkeypatch.setattr(gates_mod, "DEFAULT_JSEVAL_DATA_DIR", tmp_path)

        # Fixture mirrors what defaults-only `calibrate` + `run` produce under
        # the shared root: an envelope and an eval-results run.
        _write_json(tmp_path / "cohort_baselines" / "h1" / "envelope.json", {
            "cohort_hash": "h1", "schema_version": 1,
            "metrics": {"full": {"nDCG@10": {"mean": 0.8, "stdev": 0.001, "n": 5}}},
        })
        _write_json(
            tmp_path / "eval-results" / "20260711T000000_scifact" / "summary.json",
            {"dataset": "scifact"},
        )

        report_out = tmp_path / "report.json"
        result = CliRunner().invoke(main, [
            "gate", "--baseline-stdev", "0.001", "--tolerance-pct", "10",
            "--report-out", str(report_out),
        ])

        report = json.loads(report_out.read_text(encoding="utf-8"))
        # The default root was consulted (not CWD, not a backend dir) …
        assert report["data_dir"] == str(tmp_path)
        # … and run discovery under <default>/eval-results found the run:
        # exit must NOT be 2 (2 = envelope/run-layout miss — the Tax-1 symptom
        # "no eval-results run directory with summary.json").
        assert report["run_dir"].endswith("20260711T000000_scifact")
        assert result.exit_code != 2

    def test_gate_defaults_still_fail_closed_when_root_empty(self, tmp_path, monkeypatch):
        # Dropping exists=True from the option must NOT soften the domain
        # error: an empty/missing default root still exits 2 with the
        # domain-specific message, not a click path-validation error.
        import jseval.commands.gates as gates_mod
        monkeypatch.setattr(gates_mod, "DEFAULT_JSEVAL_DATA_DIR", tmp_path / "absent")

        report_out = tmp_path / "report.json"
        result = CliRunner().invoke(main, [
            "gate", "--baseline-stdev", "0.001", "--tolerance-pct", "10",
            "--report-out", str(report_out),
        ])
        assert result.exit_code == 2
        report = json.loads(report_out.read_text(encoding="utf-8"))
        assert report["checks"][0]["name"] == "envelope-present"


class TestCalibrateFlagSplit:
    def test_data_dir_is_envelope_root_and_backend_dir_is_separate(
            self, tmp_path, monkeypatch):
        """Tempdoc 716: cmd_calibrate's --data-dir now means the jseval-owned
        envelope root (same as every sibling command); --backend-data-dir
        carries the Worker-target concern the old flag conflated into it."""
        import jseval.calibrate as cal_mod

        captured = {}

        def fake_calibrate(*, dataset, modes, runs, backend_data_dir,
                           envelope_dir, max_queries):
            captured.update(backend=backend_data_dir, envelope=envelope_dir)
            return {"cohort_hash": "h", "n_runs": runs, "metrics": {}}

        monkeypatch.setattr(cal_mod, "calibrate", fake_calibrate)

        envroot = tmp_path / "jseval-root"
        backend = tmp_path / "backend-data"
        result = CliRunner().invoke(main, [
            "calibrate", "--dataset", "scifact", "--modes", "full",
            "--runs", "2", "--data-dir", str(envroot),
            "--backend-data-dir", str(backend),
        ])
        assert result.exit_code == 0, result.output
        assert captured["envelope"] == envroot
        assert captured["backend"] == backend

    def test_backend_dir_defaults_from_env_then_constant(self, tmp_path, monkeypatch):
        import jseval.calibrate as cal_mod
        import jseval.commands.calibrate as cmd_mod

        captured = {}

        def fake_calibrate(**kwargs):
            captured.update(kwargs)
            return {"cohort_hash": "h", "n_runs": 2, "metrics": {}}

        monkeypatch.setattr(cal_mod, "calibrate", fake_calibrate)

        env_backend = tmp_path / "env-backend"
        monkeypatch.setenv("JUSTSEARCH_DATA_DIR", str(env_backend))
        result = CliRunner().invoke(main, [
            "calibrate", "--dataset", "scifact", "--modes", "full", "--runs", "2",
        ])
        assert result.exit_code == 0, result.output
        assert captured["backend_data_dir"] == env_backend
        # And the envelope root defaulted to the module's jseval data root.
        assert captured["envelope_dir"] == cmd_mod.DEFAULT_JSEVAL_DATA_DIR
