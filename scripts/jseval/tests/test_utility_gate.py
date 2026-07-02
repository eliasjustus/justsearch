"""Tests for utility_gate.py — the agent-utility standing regression detection gate (tempdoc 673)."""

from __future__ import annotations

import json
from pathlib import Path

from jseval import utility_gate

_UTIL_SMOKE_ROOT = Path(__file__).resolve().parents[1] / "util-smoke"

# A minimal 2-condition (A/C only) record, condition C = with-tool = top-level accuracy.with_tool.
_RECORD_2COND = {
    "schema": "utility-comparison.v1",
    "coverage": {"contamination_class": "private-synthetic"},
    "measured": {
        "golden/util-smoke": {
            "haiku": {
                "primary_arm": None,
                "accuracy": {"baseline": 1.0, "with_tool": 1.0},
            },
        },
    },
}

# A minimal 3-condition (A/B/C) record where B is primary and C is relegated to arms.substitution_c.
_RECORD_3COND = {
    "schema": "utility-comparison.v1",
    "coverage": {"contamination_class": "private-synthetic"},
    "measured": {
        "mixed/multihop-rag": {
            "haiku": {
                "primary_arm": "addition_b",
                "accuracy": {"baseline": 0.8, "with_tool": 0.8},  # this is B's accuracy, NOT C's
                "arms": {
                    "substitution_c": {"accuracy": {"baseline": 0.8, "with_tool": 1.0}},
                    "addition_b": {"accuracy": {"baseline": 0.8, "with_tool": 0.8}},
                },
            },
        },
    },
}

# A single record legitimately measuring TWO corpora at once (compose_utility groups cells by
# (corpus, model) across ALL run_summaries handed to it — nothing restricts one record to one
# corpus). Used to regression-guard the --update-baseline merge-scope bug (post-impl review #1).
_RECORD_MULTI_CORPUS = {
    "schema": "utility-comparison.v1",
    "coverage": {"contamination_class": "private-synthetic"},
    "measured": {
        "golden/util-smoke": {
            "haiku": {"primary_arm": None, "accuracy": {"baseline": 1.0, "with_tool": 1.0}},
        },
        "mixed/multihop-rag": {
            # deliberately LOWER than the pinned 0.92 baseline -- must NOT get silently re-pinned
            # when a --update-baseline call only targets golden/util-smoke.
            "haiku": {"primary_arm": None, "accuracy": {"baseline": 0.8, "with_tool": 0.40}},
        },
    },
}

_BASE = {"baselines": {"golden/util-smoke": {"c_floor_min": 1.0, "tolerance_abs": 0.15}}}


def test_within_floor_passes():
    rep = utility_gate.evaluate(_BASE, _RECORD_2COND, "golden/util-smoke")
    assert rep["exit_code"] == 0
    assert rep["verdict"] == "PASS"
    # "skip" is the agent-identity-stable check: neither _BASE nor _RECORD_2COND records a
    # cli_version, so it's a backward-compatible no-op (tempdoc 673 D9), not a failure.
    assert {c["status"] for c in rep["checks"]} == {"ok", "skip"}
    assert rep["current"] == 1.0


def test_c_floor_regression_fails():
    dropped = json.loads(json.dumps(_RECORD_2COND))
    dropped["measured"]["golden/util-smoke"]["haiku"]["accuracy"]["with_tool"] = 0.5
    rep = utility_gate.evaluate(_BASE, dropped, "golden/util-smoke")
    assert rep["exit_code"] == 1
    assert next(c for c in rep["checks"] if c["name"] == "c-floor-no-regression")["status"] == "fail"


def test_within_tolerance_band_passes():
    # floor=1.0, tolerance=0.15 -> limit=0.85; a drop to 0.86 is still within tolerance.
    within = json.loads(json.dumps(_RECORD_2COND))
    within["measured"]["golden/util-smoke"]["haiku"]["accuracy"]["with_tool"] = 0.86
    rep = utility_gate.evaluate(_BASE, within, "golden/util-smoke")
    assert rep["exit_code"] == 0


def test_unpinned_corpus_skips():
    rep = utility_gate.evaluate({"baselines": {}}, _RECORD_2COND, "golden/util-smoke")
    assert rep["exit_code"] == 0
    assert rep["checks"][0]["status"] == "skip"


def test_missing_cell_is_data_error():
    rep = utility_gate.evaluate(_BASE, {"measured": {}}, "golden/util-smoke")
    assert rep["exit_code"] == 2


def test_malformed_baseline_is_data_error():
    rep = utility_gate.evaluate(
        {"baselines": {"golden/util-smoke": {"c_floor_min": "not-a-number"}}},
        _RECORD_2COND, "golden/util-smoke",
    )
    assert rep["exit_code"] == 2


def test_c_floor_extraction_prefers_arms_when_primary_is_b():
    # The 3-condition record's top-level accuracy is B's (0.8); C's real accuracy (1.0) lives in
    # arms.substitution_c — the gate must read the latter, never mistake B's number for C's.
    base = {"baselines": {"mixed/multihop-rag": {"c_floor_min": 1.0, "tolerance_abs": 0.1}}}
    rep = utility_gate.evaluate(base, _RECORD_3COND, "mixed/multihop-rag")
    assert rep["current"] == 1.0
    assert rep["exit_code"] == 0


def test_c_floor_extraction_returns_none_when_primary_is_b_without_arms():
    cell = {"primary_arm": "addition_b", "accuracy": {"with_tool": 0.8}}  # no arms breakdown at all
    assert utility_gate._c_floor(cell) is None


def test_derive_baselines_is_measured_not_hand_typed():
    derived = utility_gate.derive_baselines({"r1": _RECORD_2COND, "r2": _RECORD_3COND})
    assert derived["derived_from_runs"] is True
    assert derived["baselines"]["golden/util-smoke"]["c_floor_min"] == 1.0
    assert derived["baselines"]["golden/util-smoke"]["agent_model"] == "haiku"
    assert derived["baselines"]["mixed/multihop-rag"]["c_floor_min"] == 1.0


def test_c_floor_extraction_against_real_committed_records():
    """Cross-check _c_floor against the actual on-disk util-smoke records (tempdoc 673 confidence
    pass evidence) — guards against the extraction logic drifting from the real record shape."""
    out_record = json.loads(
        (_UTIL_SMOKE_ROOT / "out" / "utility-comparison.v1.json").read_text(encoding="utf-8"))
    cell = out_record["measured"]["golden/util-smoke"]["haiku"]
    assert utility_gate._c_floor(cell) == 1.0

    floor_record = json.loads(
        (_UTIL_SMOKE_ROOT / "floor-inspect" / "utility-comparison.v1.json").read_text(encoding="utf-8"))
    cell = floor_record["measured"]["mixed/multihop-rag"]["haiku"]
    assert utility_gate._c_floor(cell) == 0.92

    abc_record = json.loads(
        (_UTIL_SMOKE_ROOT / "abc-validate" / "utility-comparison.v1.json").read_text(encoding="utf-8"))
    cell = abc_record["measured"]["mixed/multihop-rag"]["haiku"]
    # primary_arm is addition_b (B) here; _c_floor must resolve C via arms.substitution_c (1.0),
    # NOT the top-level accuracy.with_tool (0.8, which is B's).
    assert cell.get("primary_arm") == "addition_b"
    assert utility_gate._c_floor(cell) == 1.0


# --- Post-implementation review fixes (2026-07-02) ------------------------------------------------


def test_check_schema_rejects_cross_corpus():
    err = utility_gate.check_schema({"schema": "utility-comparison-cross-corpus.v1"})
    assert err is not None
    assert "utility-comparison-cross-corpus.v1" in err


def test_check_schema_accepts_v1():
    assert utility_gate.check_schema({"schema": "utility-comparison.v1"}) is None


def test_evaluate_rejects_cross_corpus_record():
    # measured.haiku in a real cross-corpus record is NOT a corpus -> without the schema guard this
    # would either silently miss the cell (safe-ish) or, if the "corpus" arg happened to collide with
    # a model name, silently misread a stratified aggregate as a corpus's condition-C floor.
    cross_corpus_shaped = {"schema": "utility-comparison-cross-corpus.v1", "measured": {"haiku": {}}}
    rep = utility_gate.evaluate(_BASE, cross_corpus_shaped, "golden/util-smoke")
    assert rep["exit_code"] == 2
    assert next(c for c in rep["checks"] if c["name"] == "schema-supported")["status"] == "fail"


def test_evaluate_rejects_missing_schema():
    no_schema = {"measured": _RECORD_2COND["measured"]}
    rep = utility_gate.evaluate(_BASE, no_schema, "golden/util-smoke")
    assert rep["exit_code"] == 2


def test_derive_baselines_skips_cross_corpus_records():
    cross_corpus_shaped = {
        "schema": "utility-comparison-cross-corpus.v1",
        "measured": {"haiku": {"pooled": {"accuracy": {"with_tool": 1.0}}}},
    }
    derived = utility_gate.derive_baselines({"bad": cross_corpus_shaped, "good": _RECORD_2COND})
    # "haiku" must never appear as a spurious corpus key derived from the cross-corpus shape.
    assert "haiku" not in derived["baselines"]
    assert derived["baselines"]["golden/util-smoke"]["c_floor_min"] == 1.0


def test_update_baseline_cli_scopes_relaxation_guard_to_one_corpus(tmp_path):
    """Regression guard for the merge-scope bug: --update-baseline for ONE corpus must never
    silently re-pin a DIFFERENT corpus present in the same multi-corpus record, bypassing the
    baseline_shift relaxation guard for that other corpus."""
    from click.testing import CliRunner

    from jseval.cli import main

    record_path = tmp_path / "record.json"
    record_path.write_text(json.dumps(_RECORD_MULTI_CORPUS), encoding="utf-8")

    baselines_path = tmp_path / "utility-ratchet-baselines.v1.json"
    baselines_path.write_text(json.dumps({
        "schema": "utility-ratchet-baseline.v1",
        "baselines": {
            "golden/util-smoke": {"c_floor_min": 0.5, "tolerance_abs": 0.15},
            "mixed/multihop-rag": {"c_floor_min": 0.92, "tolerance_abs": 0.05},
        },
    }), encoding="utf-8")

    # golden/util-smoke's new measured floor (1.0) is an IMPROVEMENT over its pinned 0.5 -> allowed.
    # mixed/multihop-rag's measured floor in the SAME record (0.40) is a large RELAXATION vs its
    # pinned 0.92 -> must be refused if the CLI ever tried to write it, but this call never targets
    # mixed/multihop-rag at all, so its baseline entry must be completely untouched.
    result = CliRunner().invoke(main, [
        "utility-gate", "--record", str(record_path), "--corpus", "golden/util-smoke",
        "--baselines", str(baselines_path), "--update-baseline",
    ])
    assert result.exit_code == 0, result.output

    written = json.loads(baselines_path.read_text(encoding="utf-8"))
    assert written["baselines"]["golden/util-smoke"]["c_floor_min"] == 1.0  # updated, as requested
    assert written["baselines"]["mixed/multihop-rag"]["c_floor_min"] == 0.92  # UNCHANGED


# --- D8: corpus-authenticity admission gate (design-conformance fix, 2026-07-02) -------------------

_REALISTIC_RECORD = {
    "schema": "utility-comparison.v1",
    "coverage": {"contamination_class": "public-pre-cutoff"},  # NOT the fabricated/engineered marker
    "measured": {
        "mixed/multihop-rag": {
            "haiku": {"primary_arm": None, "accuracy": {"baseline": 0.8, "with_tool": 0.92}},
        },
    },
}


def test_check_admission_rejects_realistic_corpus():
    err = utility_gate.check_admission(_REALISTIC_RECORD)
    assert err is not None
    assert "public-pre-cutoff" in err


def test_check_admission_accepts_private_synthetic():
    assert utility_gate.check_admission(_RECORD_2COND) is None


def test_check_admission_rejects_missing_coverage():
    assert utility_gate.check_admission({"schema": "utility-comparison.v1"}) is not None


def test_derive_baselines_skips_realistic_corpus_by_default():
    derived = utility_gate.derive_baselines({"bad": _REALISTIC_RECORD, "good": _RECORD_2COND})
    assert "mixed/multihop-rag" not in derived["baselines"]
    assert derived["baselines"]["golden/util-smoke"]["c_floor_min"] == 1.0


def test_derive_baselines_allow_realistic_corpus_override():
    derived = utility_gate.derive_baselines(
        {"bad": _REALISTIC_RECORD, "good": _RECORD_2COND}, allow_realistic_corpus=True)
    assert derived["baselines"]["mixed/multihop-rag"]["c_floor_min"] == 0.92
    assert derived["baselines"]["golden/util-smoke"]["c_floor_min"] == 1.0


def test_cli_update_baseline_refuses_realistic_corpus_by_default(tmp_path):
    from click.testing import CliRunner

    from jseval.cli import main

    record_path = tmp_path / "realistic.json"
    record_path.write_text(json.dumps(_REALISTIC_RECORD), encoding="utf-8")
    baselines_path = tmp_path / "baselines.json"
    baselines_path.write_text(json.dumps({"schema": "utility-ratchet-baseline.v1", "baselines": {}}),
                              encoding="utf-8")

    result = CliRunner().invoke(main, [
        "utility-gate", "--record", str(record_path), "--corpus", "mixed/multihop-rag",
        "--baselines", str(baselines_path), "--update-baseline",
    ])
    assert result.exit_code == 2
    # the baseline file must be untouched — the refusal happens before any write.
    assert json.loads(baselines_path.read_text(encoding="utf-8"))["baselines"] == {}


def test_cli_update_baseline_allow_realistic_corpus_override(tmp_path):
    from click.testing import CliRunner

    from jseval.cli import main

    record_path = tmp_path / "realistic.json"
    record_path.write_text(json.dumps(_REALISTIC_RECORD), encoding="utf-8")
    baselines_path = tmp_path / "baselines.json"
    baselines_path.write_text(json.dumps({"schema": "utility-ratchet-baseline.v1", "baselines": {}}),
                              encoding="utf-8")

    result = CliRunner().invoke(main, [
        "utility-gate", "--record", str(record_path), "--corpus", "mixed/multihop-rag",
        "--baselines", str(baselines_path), "--update-baseline", "--allow-realistic-corpus",
    ])
    assert result.exit_code == 0, result.output
    written = json.loads(baselines_path.read_text(encoding="utf-8"))
    assert written["baselines"]["mixed/multihop-rag"]["c_floor_min"] == 0.92


def test_cli_gate_derive_warns_and_skips_realistic_corpus(tmp_path):
    from click.testing import CliRunner

    from jseval.cli import main

    good_path = tmp_path / "good.json"
    good_path.write_text(json.dumps(_RECORD_2COND), encoding="utf-8")
    bad_path = tmp_path / "bad.json"
    bad_path.write_text(json.dumps(_REALISTIC_RECORD), encoding="utf-8")
    out_path = tmp_path / "out.json"

    result = CliRunner().invoke(main, [
        "utility-gate-derive", "--records", f"{good_path},{bad_path}", "--out", str(out_path),
    ])
    assert result.exit_code == 0, result.output
    assert "WARN: skipping" in result.output
    assert "public-pre-cutoff" in result.output
    written = json.loads(out_path.read_text(encoding="utf-8"))
    assert "mixed/multihop-rag" not in written["baselines"]
    assert written["baselines"]["golden/util-smoke"]["c_floor_min"] == 1.0


# --- D9: cli_version drift as a genuine third verdict state (design-conformance fix, 2026-07-02) ---

_BASE_WITH_VERSION = {
    "baselines": {
        "golden/util-smoke": {
            "c_floor_min": 1.0, "tolerance_abs": 0.15, "cli_version": "2.1.183 (Claude Code)",
        },
    },
}


def _record_with(version=None, with_tool=1.0):
    rec = json.loads(json.dumps(_RECORD_2COND))
    rec["measured"]["golden/util-smoke"]["haiku"]["accuracy"]["with_tool"] = with_tool
    if version is not None:
        rec["cohort"] = {"cli_version": version}
    return rec


def test_compare_cli_version_ok_when_equal():
    assert utility_gate.compare_cli_version("2.1.183", "2.1.183") == ("ok", None)


def test_compare_cli_version_skip_when_either_missing():
    verdict, _ = utility_gate.compare_cli_version(None, "2.1.183")
    assert verdict == "skip"
    verdict, _ = utility_gate.compare_cli_version("2.1.183", None)
    assert verdict == "skip"


def test_compare_cli_version_mismatch_when_different():
    verdict, reason = utility_gate.compare_cli_version("2.2.000", "2.1.183")
    assert verdict == "mismatch"
    assert "2.2.000" in reason and "2.1.183" in reason


def test_regression_with_matching_cli_version_is_fail():
    rep = utility_gate.evaluate(
        _BASE_WITH_VERSION, _record_with(version="2.1.183 (Claude Code)", with_tool=0.5),
        "golden/util-smoke")
    assert rep["exit_code"] == 1
    assert rep["verdict"] == "FAIL"


def test_regression_with_drifted_cli_version_is_inconclusive():
    rep = utility_gate.evaluate(
        _BASE_WITH_VERSION, _record_with(version="2.2.000 (Claude Code)", with_tool=0.5),
        "golden/util-smoke")
    assert rep["exit_code"] == 2
    assert rep["verdict"] == "INCONCLUSIVE"
    identity_check = next(c for c in rep["checks"] if c["name"] == "agent-identity-stable")
    assert identity_check["status"] == "warn"


def test_regression_with_no_recorded_cli_version_stays_fail_backward_compatible():
    # neither the pinned baseline nor the record carries cli_version -> skip, not a false INCONCLUSIVE.
    rep = utility_gate.evaluate(_BASE, _record_with(version=None, with_tool=0.5), "golden/util-smoke")
    assert rep["exit_code"] == 1
    assert rep["verdict"] == "FAIL"


def test_pass_stays_pass_regardless_of_cli_version_drift():
    rep = utility_gate.evaluate(
        _BASE_WITH_VERSION, _record_with(version="2.2.000 (Claude Code)", with_tool=1.0),
        "golden/util-smoke")
    assert rep["exit_code"] == 0
    assert rep["verdict"] == "PASS"
    # the drift is still surfaced for transparency, just doesn't change the verdict.
    identity_check = next(c for c in rep["checks"] if c["name"] == "agent-identity-stable")
    assert identity_check["status"] == "warn"


def test_verdict_field_matches_exit_code_for_data_errors():
    rep = utility_gate.evaluate(_BASE, {"measured": {}}, "golden/util-smoke")
    assert rep["exit_code"] == 2
    assert rep["verdict"] == "INCONCLUSIVE"


def test_derive_baselines_captures_cli_version():
    record = _record_with(version="2.1.183 (Claude Code)")
    derived = utility_gate.derive_baselines({"r": record})
    assert derived["baselines"]["golden/util-smoke"]["cli_version"] == "2.1.183 (Claude Code)"
