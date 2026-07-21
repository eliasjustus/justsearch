"""Tests for the running budget guard's extrapolation legibility (tempdoc 758 §C).

`step2-budget-guard.py` extrapolates missing calibrations at max(known) — conservative, and the
authoritative driver of the abort decision (UNCHANGED). §C adds a mean-based projection printed
alongside for legibility, and names the ordering sensitivity in the abort message so a future
chain author doesn't rediscover that max-extrapolation over-projects with expensive-first
ordering ($31.77 → $127.08 vs ~$90 true; the 2026-07-17 cheapest-first reorder).

The script has a hyphenated filename (not importable as a module), so it is loaded via
`importlib.util.spec_from_file_location` — the same pattern as test_delivery_tier_735.py.
"""

from __future__ import annotations

import importlib.util
import json
from pathlib import Path

import pytest

_SCRIPT = Path(__file__).parents[1] / "step2-budget-guard.py"


def _load_guard():
    spec = importlib.util.spec_from_file_location("step2_budget_guard", _SCRIPT)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def _write_calibrations(tmp_path: Path, costs: list[float]) -> str:
    """Write one calibration.json per cost under its own subdir; return the chain-style glob."""
    for i, c in enumerate(costs):
        d = tmp_path / f"d{i}"
        d.mkdir()
        (d / "calibration.json").write_text(json.dumps({"cost_estimate_usd": c}), encoding="utf-8")
    return str(tmp_path / "*" / "calibration.json")


def _run(monkeypatch, glob: str, cap: float, total: int) -> int:
    guard = _load_guard()
    monkeypatch.setattr(
        "sys.argv",
        ["step2-budget-guard.py", "--glob", glob, "--cap", str(cap), "--total", str(total)],
    )
    return guard.main()


def test_both_projections_printed_under_cap(tmp_path, monkeypatch, capsys):
    # known=[10, 20], total=4 → 2 missing. max-based: 30 + 20*2 = 70 (< cap 100) → rc 0.
    glob = _write_calibrations(tmp_path, [10.0, 20.0])
    rc = _run(monkeypatch, glob, cap=100.0, total=4)
    out = capsys.readouterr().out
    assert rc == 0
    # Authoritative max-based projection present and labelled.
    assert "PROJECTED TOTAL = $70.00" in out
    assert "max-based, authoritative" in out
    # Mean-based projection printed alongside: 30 + mean(15)*2 = 60, labelled informational.
    assert "mean-based projection" in out
    assert "$60.00" in out


def test_abort_message_names_ordering_sensitivity(tmp_path, monkeypatch, capsys):
    # Expensive-first: known=[40, 20], total=4 → max-based 60 + 40*2 = 140 (> cap 100) → rc 1.
    glob = _write_calibrations(tmp_path, [40.0, 20.0])
    rc = _run(monkeypatch, glob, cap=100.0, total=4)
    err = capsys.readouterr().err
    assert rc == 1
    assert "ABORT" in err
    # The ordering-sensitivity lesson must be named in the abort message (tempdoc 758 §C).
    assert "max-extrapolation over-projects when the most expensive dataset calibrates first" in err
    assert "cheapest-first ordering recommended" in err
    # Mean-based figure surfaced for reference: 60 + mean(30)*2 = 120.
    assert "$120.00" in err


def test_abort_decision_stays_max_based_even_when_mean_is_under_cap(tmp_path, monkeypatch, capsys):
    # Precision guard: the abort decision must remain driven by the MAX projection, unchanged.
    # known=[40, 5], total=4: max-based = 45 + 40*2 = 125 (> 100) → ABORT, while the mean-based
    # projection = 45 + 22.5*2 = 90 (< 100) would NOT abort. rc must be 1 (max drives it).
    glob = _write_calibrations(tmp_path, [40.0, 5.0])
    rc = _run(monkeypatch, glob, cap=100.0, total=4)
    out_err = capsys.readouterr()
    assert rc == 1, "abort decision must stay max-based, not switch to the informational mean"
    assert "$90.00" in (out_err.out + out_err.err)   # mean projection is shown...
    assert "ABORT" in out_err.err                     # ...but the run still aborts on max


def test_zero_missing_projects_sum_only(tmp_path, monkeypatch, capsys):
    # All datasets calibrated: n_missing=0 → both projections equal sum(known); no extrapolation.
    glob = _write_calibrations(tmp_path, [10.0, 20.0, 30.0, 15.0])
    rc = _run(monkeypatch, glob, cap=100.0, total=4)
    out = capsys.readouterr().out
    assert rc == 0
    assert "PROJECTED TOTAL = $75.00" in out
    assert "mean-based projection" in out
