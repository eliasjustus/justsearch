"""Tempdoc 716: defaults-only compose across `run` → gates.

Tax 1 regression: a defaults `jseval run` writes under
`DEFAULT_JSEVAL_DATA_DIR/eval-results/`, and every gate's `--data-dir` now DEFAULTS
to `DEFAULT_JSEVAL_DATA_DIR` — so a defaults-only run followed by a defaults-only
gate composes with no operator path reasoning. These tests pin the CLI wiring (the
default actually reaches the command body), not just the constants (test_paths.py
pins those).

Tempdoc 930 §18.1 row 7 retired the `calibrate` half of this composition (no cohort
envelope was ever produced), so only the `run` → `gate` leg remains.
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

        # Fixture mirrors what a defaults-only `run` produces under the shared root.
        run_dir = tmp_path / "eval-results" / "20260711T000000_scifact"
        _write_json(run_dir / "summary.json", {"dataset": "scifact"})
        _write_json(run_dir / "manifest.json", {"manifest_hash": "h1"})

        report_out = tmp_path / "report.json"
        result = CliRunner().invoke(main, ["gate", "--report-out", str(report_out)])

        report = json.loads(report_out.read_text(encoding="utf-8"))
        # The default root was consulted (not CWD, not a backend dir) …
        assert report["data_dir"] == str(tmp_path)
        # … and run discovery under <default>/eval-results found the run:
        # exit must NOT be 2 (2 = run-layout miss — the Tax-1 symptom
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
        result = CliRunner().invoke(main, ["gate", "--report-out", str(report_out)])
        assert result.exit_code == 2
        report = json.loads(report_out.read_text(encoding="utf-8"))
        assert report["checks"][0]["name"] == "run-dir-present"
