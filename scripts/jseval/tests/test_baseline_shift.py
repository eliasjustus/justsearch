"""Tests for baseline_shift.py — the ratchet baseline-relaxation-justification convention
ported from the discipline-gate kernel (tempdoc 664).

Pure-function / fixture tests over tmp_path directories — no git, no dev stack (`baseline_ref`
is left None throughout, matching the JS kernel's `fixtureMode`: every .md file present is
loaded, no git-diff scoping needed for these unit tests).
"""

from __future__ import annotations

import pytest

from jseval.baseline_shift import (
    BaselineRelaxedWithoutJustificationError,
    assert_baseline_not_relaxed,
    load_changesets,
)


def _write_changeset(dir_, name, *, classification="baseline-relaxation",
                      gate="perf-gate", dataset="beir/scifact:ce_p50_ms", tempdoc="636", body="reason"):
    # Quote every scalar: an unquoted `*` is YAML alias syntax (invalid here), and an unquoted
    # digit string type-infers as an int — quoting keeps the fixture's YAML unambiguous.
    dir_.mkdir(parents=True, exist_ok=True)
    (dir_ / name).write_text(
        f'---\nclassification: "{classification}"\ngate: "{gate}"\ndataset: "{dataset}"\n'
        f'tempdoc: "{tempdoc}"\n---\n{body}\n',
        encoding="utf-8",
    )


# --- load_changesets ---------------------------------------------------------

def test_load_changesets_reads_frontmatter(tmp_path):
    d = tmp_path / ".changesets"
    _write_changeset(d, "one.md")
    decls = load_changesets(d)
    assert len(decls) == 1
    assert decls[0]["classification"] == "baseline-relaxation"
    assert decls[0]["gate"] == "perf-gate"
    assert decls[0]["tempdoc"] == "636"


def test_load_changesets_ignores_readme(tmp_path):
    d = tmp_path / ".changesets"
    d.mkdir()
    (d / "README.md").write_text("---\nclassification: baseline-relaxation\n---\nignored\n", encoding="utf-8")
    assert load_changesets(d) == []


def test_load_changesets_missing_dir_returns_empty(tmp_path):
    assert load_changesets(tmp_path / "nope") == []


def test_load_changesets_skips_files_without_classification(tmp_path):
    d = tmp_path / ".changesets"
    d.mkdir()
    (d / "no-fm.md").write_text("just a body, no frontmatter\n", encoding="utf-8")
    assert load_changesets(d) == []


# --- assert_baseline_not_relaxed ---------------------------------------------

def test_relaxation_without_changeset_raises(tmp_path):
    d = tmp_path / ".changesets"
    d.mkdir()
    with pytest.raises(BaselineRelaxedWithoutJustificationError):
        assert_baseline_not_relaxed(
            160.0, 200.0, lower_is_better=True,  # latency went UP -> worse -> relaxation
            gate="perf-gate", dataset="beir/scifact:ce_p50_ms", changesets_dir=d,
        )


def test_relaxation_with_matching_justified_changeset_passes(tmp_path):
    d = tmp_path / ".changesets"
    _write_changeset(d, "one.md", gate="perf-gate", dataset="beir/scifact:ce_p50_ms", tempdoc="636")
    assert_baseline_not_relaxed(
        160.0, 200.0, lower_is_better=True,
        gate="perf-gate", dataset="beir/scifact:ce_p50_ms", changesets_dir=d,
    )  # does not raise


def test_relaxation_with_wildcard_gate_and_dataset_passes(tmp_path):
    d = tmp_path / ".changesets"
    _write_changeset(d, "one.md", gate="*", dataset="*", tempdoc="636")
    assert_baseline_not_relaxed(
        0.10, 0.20, lower_is_better=True,
        gate="leak-gate", dataset="mixed/enron-qa", changesets_dir=d,
    )  # does not raise


def test_changeset_without_tempdoc_does_not_justify(tmp_path):
    d = tmp_path / ".changesets"
    d.mkdir()
    (d / "one.md").write_text(
        "---\nclassification: baseline-relaxation\ngate: perf-gate\n"
        "dataset: beir/scifact:ce_p50_ms\ntempdoc: \n---\nno justification\n",
        encoding="utf-8",
    )
    with pytest.raises(BaselineRelaxedWithoutJustificationError):
        assert_baseline_not_relaxed(
            160.0, 200.0, lower_is_better=True,
            gate="perf-gate", dataset="beir/scifact:ce_p50_ms", changesets_dir=d,
        )


def test_changeset_for_a_different_dataset_does_not_justify(tmp_path):
    d = tmp_path / ".changesets"
    _write_changeset(d, "one.md", gate="perf-gate", dataset="beir/scifact:ce_p50_ms", tempdoc="636")
    with pytest.raises(BaselineRelaxedWithoutJustificationError):
        assert_baseline_not_relaxed(
            0.6, 0.5, lower_is_better=False,  # a DIFFERENT dataset:metric relaxing
            gate="perf-gate", dataset="mixed/enron-qa:nDCG@10", changesets_dir=d,
        )


def test_improvement_never_needs_a_changeset(tmp_path):
    d = tmp_path / ".changesets"
    d.mkdir()  # empty — no changesets at all
    assert_baseline_not_relaxed(
        160.0, 140.0, lower_is_better=True,  # latency went DOWN -> better
        gate="perf-gate", dataset="beir/scifact:ce_p50_ms", changesets_dir=d,
    )
    assert_baseline_not_relaxed(
        0.70, 0.75, lower_is_better=False,  # nDCG went UP -> better
        gate="release", dataset="beir/scifact:nDCG@10", changesets_dir=d,
    )


def test_first_ever_pin_never_needs_a_changeset(tmp_path):
    d = tmp_path / ".changesets"
    d.mkdir()
    assert_baseline_not_relaxed(
        None, 999.0, lower_is_better=True,
        gate="perf-gate", dataset="beir/scifact:ce_p50_ms", changesets_dir=d,
    )


# tempdoc 664 (twelfth pass): the `jseval changeset-new` scaffolding tool round-trips through the
# exact parser above -- no new frontmatter format, just less friction to author one.

def test_changeset_new_scaffolds_a_file_load_changesets_accepts(tmp_path):
    from click.testing import CliRunner
    from jseval.cli import main

    d = tmp_path / ".changesets"
    r = CliRunner().invoke(main, [
        "changeset-new", "--gate", "perf-gate", "--dataset", "beir/scifact:ce_p50_ms",
        "--tempdoc", "664", "--reason", "test reason", "--changesets-dir", str(d),
    ])
    assert r.exit_code == 0, r.output

    decls = load_changesets(d)
    assert len(decls) == 1
    assert decls[0]["classification"] == "baseline-relaxation"
    assert decls[0]["gate"] == "perf-gate"
    assert decls[0]["dataset"] == "beir/scifact:ce_p50_ms"
    assert decls[0]["tempdoc"] == "664"


def test_changeset_new_refuses_to_overwrite(tmp_path):
    from click.testing import CliRunner
    from jseval.cli import main

    d = tmp_path / ".changesets"
    args = ["changeset-new", "--gate", "leak-gate", "--dataset", "golden/needle-burial-v1",
            "--tempdoc", "664", "--changesets-dir", str(d)]
    r1 = CliRunner().invoke(main, args)
    assert r1.exit_code == 0, r1.output
    r2 = CliRunner().invoke(main, args)
    assert r2.exit_code != 0
    assert "already exists" in r2.output
