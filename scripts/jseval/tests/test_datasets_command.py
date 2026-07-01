"""Tests for `jseval datasets`' gate-coverage visibility (tempdoc 664, seventh pass).

`_gate_coverage()` and `cmd_datasets` read the three committed ratchet baseline files
(relevance/perf/leak) to report which gate(s), if any, currently pin a floor for each listed
dataset -- making today's silent coverage gap visible rather than something each gate separately
hand-picks. Pure read-only file-parsing logic; no live backend, no dev stack.
"""

from __future__ import annotations

import json

from click.testing import CliRunner

from jseval.cli import main
from jseval.commands.ops import _gate_coverage


def _write_baselines(path, datasets):
    path.write_text(json.dumps({"baselines": {d: {} for d in datasets}}), encoding="utf-8")


def test_gate_coverage_reads_real_committed_files():
    """No mocking -- reads the actual committed baseline files in this repo, confirming the
    parents[2] path resolution (mirroring the sixth pass's gates.py fix) is correct here too."""
    coverage = _gate_coverage()
    assert set(coverage) == {"relevance-gate", "perf-gate", "leak-gate"}
    # beir/scifact is committed as gated (canonical slug) in ALL THREE baseline files at HEAD.
    # tempdoc 664 (twelfth pass): leak-gate-baselines.v1.json's bare-name key was fixed at its
    # source (cmd_leak_gate_derive now canonicalizes) rather than worked around downstream.
    assert "beir/scifact" in coverage["relevance-gate"]
    assert "beir/scifact" in coverage["perf-gate"]
    assert "beir/scifact" in coverage["leak-gate"]


def test_gate_coverage_missing_file_is_gates_nothing_not_a_crash(tmp_path, monkeypatch):
    import jseval.commands.ops as ops_mod

    # Point `Path(__file__)` resolution at an empty dir with none of the three files present.
    monkeypatch.setattr(ops_mod, "__file__", str(tmp_path / "nested" / "commands" / "ops.py"))
    coverage = _gate_coverage()
    assert coverage == {"relevance-gate": set(), "perf-gate": set(), "leak-gate": set()}


def test_cmd_datasets_reports_gated_and_ungated(tmp_path, monkeypatch):
    """A dataset present in one baseline file and absent from the others is reported accurately;
    a dataset in none of them is reported as ungated (empty list, not missing/crashing)."""
    root = tmp_path / "scripts" / "jseval"
    root.mkdir(parents=True)
    _write_baselines(root / "relevance-ratchet-baselines.v1.json", ["mixed/courtlistener-200"])
    _write_baselines(root / "perf-ratchet-baselines.v1.json", [])
    _write_baselines(root / "leak-gate-baselines.v1.json", [])

    import jseval.commands.ops as ops_mod
    monkeypatch.setattr(ops_mod, "__file__", str(root / "jseval" / "commands" / "ops.py"))

    datasets_dir = tmp_path / "datasets"
    (datasets_dir / "mixed" / "courtlistener-200").mkdir(parents=True)
    (datasets_dir / "mixed" / "enron-qa").mkdir(parents=True)  # deliberately ungated
    monkeypatch.setattr("jseval.corpora._default_base_dir", lambda: datasets_dir)

    r = CliRunner().invoke(main, ["--json", "datasets"])
    assert r.exit_code == 0, r.output
    listed = {d["name"]: d for d in json.loads(r.output)}
    assert listed["mixed/courtlistener-200"]["gated_by"] == ["relevance-gate"]
    assert listed["mixed/enron-qa"]["gated_by"] == []


def test_cmd_datasets_canonicalizes_beir_short_names(tmp_path, monkeypatch):
    """BEIR entries are listed by their bare name (e.g. 'scifact') but baseline files key on the
    canonical slug ('beir/scifact') -- gated_by must resolve through that canonicalization."""
    root = tmp_path / "scripts" / "jseval"
    root.mkdir(parents=True)
    _write_baselines(root / "relevance-ratchet-baselines.v1.json", ["beir/scifact"])
    _write_baselines(root / "perf-ratchet-baselines.v1.json", [])
    _write_baselines(root / "leak-gate-baselines.v1.json", [])

    import jseval.commands.ops as ops_mod
    monkeypatch.setattr(ops_mod, "__file__", str(root / "jseval" / "commands" / "ops.py"))
    monkeypatch.setattr("jseval.corpora._default_base_dir", lambda: tmp_path / "empty-datasets")

    r = CliRunner().invoke(main, ["--json", "datasets"])
    assert r.exit_code == 0, r.output
    listed = {d["name"]: d for d in json.loads(r.output)}
    assert listed["scifact"]["gated_by"] == ["relevance-gate"]
