"""Tests for the tempdoc 916 chunk-size sweep driver + its SPLADE projection.

Hermetic and offline: no backend, no network, no subprocess. The driver's only
process spawns (`signature`, the jseval `run`) are either not reached on the
paths exercised here or injected as a spy.

`916_chunk_sweep.py` is a top-level script whose name is not an identifier, so it
is loaded by path rather than imported.
"""

from __future__ import annotations

import importlib.util
import json
import math
from pathlib import Path

import pytest

from jseval.projections import splade_truncation as st

_SCRIPT = Path(__file__).resolve().parent.parent / "916_chunk_sweep.py"


def _load_driver():
    spec = importlib.util.spec_from_file_location("chunk_sweep_916", _SCRIPT)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


sweep = _load_driver()


# --------------------------------------------------------------------------
# Synthetic run artifacts (real shapes: jseval/run.py `_build_summary`,
# projections/staged_recall_accounting.py, SpladeTruncationEvidence.snapshot()).
# --------------------------------------------------------------------------

def _write_json(path: Path, doc) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(doc, indent=2, sort_keys=True), encoding="utf-8")


def _make_arm(
    out: Path, corpus: str, target: int, overlap: int, rep: int, *,
    ndcg: float, r10: float = 0.5, leak: float = 0.1, union: float = 0.9,
    ce_verdict: str = "ok", comparable: bool = True,
    index_bytes: int = 1024 * 1024, docs_per_s: float = 40.0,
    trunc_rate: float | None = 0.25,
) -> Path:
    """Build `<out>/<slug>/<tag>/run-0/` with the artifacts `load()` reads."""
    armdir = out / corpus.replace("/", "_") / sweep.arm_tag(target, overlap, rep)
    run_dir = armdir / "run-0"
    _write_json(run_dir / "summary.json", {
        "dataset": corpus,
        "modes": ["lexical", "vector", "splade", "hybrid"],
        "ce_coverage": {"verdict": ce_verdict},
        "per_mode": {
            "hybrid": {
                "aggregate_metrics": {"nDCG@10": ndcg, "AP@10": 0.3, "RR@10": 0.4,
                                      "R@10": r10, "P@1": 0.6},
                "comparable": comparable,
            },
        },
        "ingest": {
            "index_size_bytes": index_bytes,
            "docs_per_sec": docs_per_s / 2.0,
            "pipeline_summary": {"primary_indexing": {"docs_per_s": docs_per_s}},
        },
        "run_metrics": {"primary_docs_s": docs_per_s, "enrich_docs_s": docs_per_s / 2.0},
        "env_overrides": {sweep.KEY_TARGET: str(target), sweep.KEY_OVERLAP: str(overlap)},
    })
    _write_json(run_dir / "projections" / "staged_recall_accounting.json", {
        "projection_name": "staged_recall_accounting",
        "schema_version": 1,
        "aggregate": {"leak_rate": leak, "leg_union_recall": union, "final_recall": 0.45},
    })
    if trunc_rate is not None:
        _write_json(run_dir / "projections" / "splade_truncation.json", {
            "projection_name": "splade_truncation",
            "schema_version": 1,
            "available": True,
            "reason": None,
            "truncation_rate": trunc_rate,
            "documents_encoded": 400,
            "documents_truncated": 100,
        })
    return armdir


def _records(out: Path, corpus: str, target: int, overlap: int, reps: int) -> list:
    return [sweep.load(out / corpus.replace("/", "_") / sweep.arm_tag(target, overlap, r))
            for r in range(reps)]


# --------------------------------------------------------------------------
# Roll-up math
# --------------------------------------------------------------------------

class TestRollupMath:
    def test_mean_and_sigma_over_three_replicates(self, tmp_path):
        """0.10 / 0.20 / 0.30 -> mean 0.20, sample sigma 0.10 (hand-computed)."""
        for rep, ndcg in enumerate((0.10, 0.20, 0.30)):
            _make_arm(tmp_path, "mixed/enron-qa", 256, 25, rep, ndcg=ndcg)
        recs = _records(tmp_path, "mixed/enron-qa", 256, 25, 3)
        assert all(r is not None and r["admissible"] for r in recs)

        s = sweep.summarize_arm(recs, floor=0.0068)
        assert s["n_admissible"] == 3
        assert s["void"] is False
        assert s["ndcg"]["mean"] == pytest.approx(0.20)
        # sqrt(((0.1)^2 + 0 + (0.1)^2) / 2) = 0.1
        assert s["ndcg"]["sigma"] == pytest.approx(0.10)
        assert s["ndcg"]["sigma_is_floor"] is False

    def test_void_replicate_excluded_from_mean_and_sigma(self, tmp_path):
        """A 4th, void replicate with a wild value must move neither the mean nor sigma."""
        for rep, ndcg in enumerate((0.10, 0.20, 0.30)):
            _make_arm(tmp_path, "mixed/enron-qa", 256, 25, rep, ndcg=ndcg)
        _make_arm(tmp_path, "mixed/enron-qa", 256, 25, 3, ndcg=0.90,
                  ce_verdict="degraded", comparable=False)
        recs = _records(tmp_path, "mixed/enron-qa", 256, 25, 4)
        assert recs[3]["admissible"] is False

        s = sweep.summarize_arm(recs, floor=0.0068)
        assert s["n_total"] == 4
        assert s["n_admissible"] == 3
        assert s["ndcg"]["mean"] == pytest.approx(0.20)
        assert s["ndcg"]["sigma"] == pytest.approx(0.10)
        # The void value would have dragged the mean to 0.375 had it been averaged in.
        assert s["ndcg"]["mean"] != pytest.approx(0.375)

    def test_comparable_false_alone_is_void(self, tmp_path):
        """`ce_coverage.verdict == "ok"` is not sufficient; `comparable` must be True."""
        _make_arm(tmp_path, "mixed/enron-qa", 128, 0, 0, ndcg=0.7,
                  ce_verdict="ok", comparable=False)
        recs = _records(tmp_path, "mixed/enron-qa", 128, 0, 1)
        assert recs[0]["admissible"] is False
        s = sweep.summarize_arm(recs, floor=0.0068)
        assert s["void"] is True
        assert s["ndcg"]["mean"] is None

    def test_single_admissible_replicate_reports_the_floor(self, tmp_path):
        """n=1 has no observed spread: report --floor, not a fake sigma of 0."""
        _make_arm(tmp_path, "mixed/enron-qa", 384, 50, 0, ndcg=0.42)
        recs = _records(tmp_path, "mixed/enron-qa", 384, 50, 1)
        s = sweep.summarize_arm(recs, floor=0.0068)
        assert s["n_admissible"] == 1
        assert s["ndcg"]["mean"] == pytest.approx(0.42)
        assert s["ndcg"]["sigma"] == pytest.approx(0.0068)
        assert s["ndcg"]["sigma_is_floor"] is True

    def test_r50_is_null_because_jseval_never_emits_it(self, tmp_path):
        """R@50 is absent from `scoring.DEFAULT_METRICS`; it is recorded null, not R@10."""
        _make_arm(tmp_path, "mixed/enron-qa", 500, 0, 0, ndcg=0.5, r10=0.77)
        rec = _records(tmp_path, "mixed/enron-qa", 500, 0, 1)[0]
        assert rec["r50"] is None
        assert rec["r10"] == pytest.approx(0.77)
        s = sweep.summarize_arm([rec], floor=0.0068)
        assert s["r50"]["mean"] is None
        assert sweep.fmt_ms(s["r50"]) == "-"

    def test_index_size_and_docs_per_s_come_from_the_summary(self, tmp_path):
        _make_arm(tmp_path, "mixed/enron-qa", 500, 0, 0, ndcg=0.5,
                  index_bytes=5 * 1024 * 1024, docs_per_s=37.5, trunc_rate=0.5)
        rec = _records(tmp_path, "mixed/enron-qa", 500, 0, 1)[0]
        assert rec["index_bytes"] == 5 * 1024 * 1024
        assert rec["primary_docs_s"] == pytest.approx(37.5)
        assert rec["trunc_rate"] == pytest.approx(0.5)
        s = sweep.summarize_arm([rec], floor=0.0068)
        assert sweep.fmt_mb(s["index_bytes"]) == "5.0"

    def test_mean_sigma_ignores_nulls_and_empty(self):
        assert sweep.mean_sigma([], 0.5) == (None, None, False)
        assert sweep.mean_sigma([None, None], 0.5) == (None, None, False)
        mean, sigma, floored = sweep.mean_sigma([1.0, None, 3.0], 0.5)
        assert mean == pytest.approx(2.0)
        assert sigma == pytest.approx(math.sqrt(2.0))
        assert floored is False


# --------------------------------------------------------------------------
# min_tokens derivation
# --------------------------------------------------------------------------

class TestMinTokens:
    @pytest.mark.parametrize("target,expected", [(128, 25), (256, 51), (384, 76), (500, 100)])
    def test_scaled_floor_per_target(self, target, expected):
        assert sweep.min_tokens_for(target) == expected

    def test_target_500_reproduces_the_incumbent(self):
        """500 // 5 == 100 is the shipped ChunkSplitter.MIN_CHUNK_TOKENS: incumbent unchanged."""
        assert sweep.min_tokens_for(500) == 100

    def test_never_below_one(self):
        assert sweep.min_tokens_for(4) == 1
        assert sweep.min_tokens_for(0) == 1

    def test_every_arm_gets_a_floor_at_or_under_a_fifth_of_target(self):
        for target, _ in sweep.arm_matrix():
            assert 1 <= sweep.min_tokens_for(target) <= target / 5


# --------------------------------------------------------------------------
# Arm matrix + yaml
# --------------------------------------------------------------------------

class TestArmMatrix:
    def test_exactly_twelve_arms_in_order(self):
        assert sweep.arm_matrix() == [
            (128, 0), (128, 25), (128, 50),
            (256, 0), (256, 25), (256, 50),
            (384, 0), (384, 25), (384, 50),
            (500, 0), (500, 25), (500, 50),
        ]
        assert len(sweep.arm_matrix()) == 12
        assert len(set(sweep.arm_matrix())) == 12

    def test_yaml_env_block_content(self):
        assert sweep.arm_yaml(128, 50) == (
            "env:\n"
            '  JUSTSEARCH_CHUNKING_SWEEP_TARGET_TOKENS: "128"\n'
            '  JUSTSEARCH_CHUNKING_SWEEP_OVERLAP_TOKENS: "50"\n'
            '  JUSTSEARCH_CHUNKING_SWEEP_MIN_TOKENS: "25"\n'
        )

    def test_threshold_chars_is_absent_unless_asked(self):
        """The 4th key is held fixed across the sweep, so it must not appear by default."""
        for target, overlap in sweep.arm_matrix():
            assert sweep.KEY_THRESHOLD not in sweep.arm_yaml(target, overlap)
        assert '  JUSTSEARCH_CHUNKING_SWEEP_THRESHOLD_CHARS: "2000"\n' in sweep.arm_yaml(
            128, 0, threshold_chars=2000)

    def test_evidence_path_uses_forward_slashes(self):
        y = sweep.arm_yaml(256, 25, evidence_path=r"F:\out\arm\evidence.json")
        assert '  JUSTSEARCH_SPLADE_EVIDENCE_PATH: "F:/out/arm/evidence.json"\n' in y
        assert "\\" not in y

    def test_arm_tag_and_label_are_distinct_per_arm(self):
        tags = {sweep.arm_tag(t, o, 0) for t, o in sweep.arm_matrix()}
        labels = {sweep.arm_label(t, o) for t, o in sweep.arm_matrix()}
        assert len(tags) == 12
        assert len(labels) == 12
        assert sweep.arm_tag(128, 50, 2) == "t128-o50-r2"
        assert sweep.arm_label(128, 50) == "128/50"


# --------------------------------------------------------------------------
# Resumability
# --------------------------------------------------------------------------

class TestResumability:
    def test_arm_with_done_marker_is_skipped(self, tmp_path, monkeypatch):
        calls = []
        monkeypatch.setattr(sweep, "signature", lambda *a, **k: calls.append("sig"))
        armdir = tmp_path / "mixed_enron-qa" / sweep.arm_tag(128, 0, 0)
        armdir.mkdir(parents=True)
        (armdir / sweep.ARM_DONE).write_text("done\n", encoding="utf-8")

        def spy(cmd, cwd, log_path, env):
            calls.append("ran")
            return 0

        rc = sweep.run_arm(str(tmp_path), "mixed/enron-qa", 128, 0, 0, runner=spy)
        assert rc is None
        assert calls == []
        assert not (armdir / "arm.yaml").exists()
        line = json.loads((tmp_path / "progress.jsonl").read_text(encoding="utf-8").strip())
        assert line["skipped"] is True
        assert line["arm"] == "t128-o0-r0"

    def test_arm_without_done_marker_runs_and_marks_done(self, tmp_path, monkeypatch):
        monkeypatch.setattr(sweep, "signature", lambda *a, **k: None)
        seen = {}

        def spy(cmd, cwd, log_path, env):
            seen["cmd"] = cmd
            seen["env"] = env
            return 0

        rc = sweep.run_arm(str(tmp_path), "mixed/enron-qa", 128, 50, 0, runner=spy)
        armdir = tmp_path / "mixed_enron-qa" / "t128-o50-r0"
        assert rc == 0
        assert (armdir / sweep.ARM_DONE).exists()
        assert '  JUSTSEARCH_CHUNKING_SWEEP_MIN_TOKENS: "25"\n' in (
            armdir / "arm.yaml").read_text(encoding="utf-8")
        # Every arm is a full reindex: --clean --pipeline, never --skip-ingest.
        assert "--clean" in seen["cmd"] and "--pipeline" in seen["cmd"]
        assert "--skip-ingest" not in seen["cmd"]
        assert seen["env"]["JSEVAL_HEALTH_TIMEOUT_SEC"] == "300"

    def test_nonzero_exit_leaves_the_arm_resumable(self, tmp_path, monkeypatch):
        monkeypatch.setattr(sweep, "signature", lambda *a, **k: None)
        rc = sweep.run_arm(str(tmp_path), "mixed/enron-qa", 384, 25, 0,
                           runner=lambda *a: 3)
        armdir = tmp_path / "mixed_enron-qa" / "t384-o25-r0"
        assert rc == 3
        assert not (armdir / sweep.ARM_DONE).exists()
        assert (armdir / "arm-metrics.json").exists()


# --------------------------------------------------------------------------
# splade_truncation projection
# --------------------------------------------------------------------------

_SNAPSHOT = {
    "capturedAt": "2026-09-03T10:00:00Z",
    "modelPath": "F:/models/onnx/splade/model.onnx",
    "maxSequenceLength": 512,
    "derivedWindowOverlapTokens": 64,
    "documentsEncoded": 1000,
    "documentsTruncated": 250,
    "truncationRate": 0.25,
    "maxObservedTokens": 4096,
    "meanObservedTokens": 311.5,
    "derivedWindowCountHistogram": {"1": 750, "2": 200, "3": 50},
    "tokenCountBuckets": {"le_max_seq_len": 750, "max_seq_len_to_2x": 200, "2x_to_4x": 50},
}


class TestSpladeTruncationProjection:
    def test_missing_path_is_soft_none(self, tmp_path):
        assert st.read_evidence(None) is None
        assert st.read_evidence(tmp_path / "nope.json") is None

    def test_produce_on_a_bare_run_dir_is_empty_not_an_error(self, tmp_path, monkeypatch):
        monkeypatch.delenv(st.EVIDENCE_ENV_VAR, raising=False)
        doc = st.produce(tmp_path)
        assert doc["available"] is False
        assert st.EVIDENCE_ENV_VAR in doc["reason"]
        assert doc["truncation_rate"] is None
        assert doc["documents_encoded"] is None

    def test_parses_a_snapshot_verbatim(self, tmp_path):
        p = tmp_path / "evidence.json"
        p.write_text(json.dumps(_SNAPSHOT), encoding="utf-8")
        rec = st.read_evidence(p)
        assert rec["available"] is True
        assert rec["documents_encoded"] == 1000
        assert rec["documents_truncated"] == 250
        assert rec["truncation_rate"] == pytest.approx(0.25)
        assert rec["max_observed_tokens"] == 4096
        assert rec["mean_observed_tokens"] == pytest.approx(311.5)
        assert rec["max_sequence_length"] == 512
        assert rec["derived_window_overlap_tokens"] == 64
        assert rec["source_path"] == str(p)
        # Histograms are deliberately not carried into the normalized record.
        assert "derivedWindowCountHistogram" not in rec
        assert "tokenCountBuckets" not in rec

    def test_path_resolves_from_summary_env_overrides(self, tmp_path, monkeypatch):
        monkeypatch.delenv(st.EVIDENCE_ENV_VAR, raising=False)
        evidence = tmp_path / "arm" / "evidence.json"
        evidence.parent.mkdir(parents=True)
        evidence.write_text(json.dumps(_SNAPSHOT), encoding="utf-8")
        run_dir = tmp_path / "run-0"
        run_dir.mkdir()
        (run_dir / "summary.json").write_text(
            json.dumps({"env_overrides": {st.EVIDENCE_ENV_VAR: str(evidence)}}),
            encoding="utf-8")

        path, how = st.resolve_evidence_path(run_dir)
        assert path == str(evidence)
        assert how == "summary.env_overrides"
        doc = st.produce(run_dir)
        assert doc["available"] is True
        assert doc["resolved_from"] == "summary.env_overrides"
        assert doc["truncation_rate"] == pytest.approx(0.25)

    def test_declared_path_that_is_absent_is_soft(self, tmp_path, monkeypatch):
        monkeypatch.delenv(st.EVIDENCE_ENV_VAR, raising=False)
        run_dir = tmp_path / "run-0"
        run_dir.mkdir()
        (run_dir / "summary.json").write_text(
            json.dumps({"env_overrides": {st.EVIDENCE_ENV_VAR: str(tmp_path / "gone.json")}}),
            encoding="utf-8")
        doc = st.produce(run_dir)
        assert doc["available"] is False
        assert "absent or unreadable" in doc["reason"]
        assert doc["truncation_rate"] is None

    def test_corrupt_evidence_is_soft(self, tmp_path):
        p = tmp_path / "evidence.json"
        p.write_text("{not json", encoding="utf-8")
        assert st.read_evidence(p) is None

    def test_registered_under_its_own_name(self):
        from jseval import projections

        projections._bootstrap()
        assert "splade_truncation" in projections.registry()
        assert st.PROJECTION.name == "splade_truncation"
        assert st.PROJECTION.schema_version == 1
