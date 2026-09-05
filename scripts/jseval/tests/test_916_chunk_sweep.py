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
    deadline_ms: int = sweep.CAMPAIGN_DEADLINE_MS,
    threshold_chars: int | None = None,
    silent_drops: int = 1, chunk_docs: int | None = 4130,
    chunk_branch: bool | None = True,
) -> Path:
    """Build `<out>/<slug>/<tag>/run-0/` with the artifacts `load()` reads."""
    armdir = out / corpus.replace("/", "_") / sweep.arm_tag(target, overlap, rep, deadline_ms)
    run_dir = armdir / "run-0"
    summary = {
        "git_sha": "deadbeef",
        "dataset": corpus,
        "modes": ["lexical", "vector", "splade", "hybrid"],
        "ce_coverage": {
            "verdict": ce_verdict,
            "per_mode": {"hybrid": {"coverage": 0.995, "silent_drops": silent_drops,
                                    "eligible": 199}},
        },
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
        "env_overrides": {sweep.KEY_TARGET: str(target), sweep.KEY_OVERLAP: str(overlap),
                          sweep.KEY_MIN: str(sweep.min_tokens_for(target)),
                          sweep.KEY_DEADLINE: str(deadline_ms)},
    }
    if threshold_chars is not None:
        summary["env_overrides"][sweep.KEY_THRESHOLD] = str(threshold_chars)
    if chunk_branch is not None:
        summary["per_mode"]["hybrid"]["pipeline_tracking"] = {
            "observed": (["dense", "chunk_merge", "branch_fusion"] if chunk_branch
                         else ["dense", "branch_fusion"])}
        summary["per_mode"]["hybrid"]["stage_timing_stats"] = (
            {"chunk_merge_ms": 2.0} if chunk_branch else {"retrieval_ms": 3.0})
    if chunk_docs is not None:
        summary["chunk_completeness"] = {
            "expected": 194, "observed": chunk_docs, "verdict": "ok",
            "threshold_chars": (sweep.SHIPPED_THRESHOLD_CHARS if threshold_chars is None
                                else threshold_chars)}
    _write_json(run_dir / "summary.json", summary)
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


def _mark_complete(
    armdir: Path, target: int, overlap: int, *, threshold_chars: int | None = None,
    deadline_ms: int = sweep.CAMPAIGN_DEADLINE_MS, machine_dirty: bool = False,
) -> None:
    metrics = sweep.capture_arm_metrics(
        str(armdir), target, overlap, threshold_chars=threshold_chars,
        deadline_ms=deadline_ms, machine_dirty=machine_dirty)
    contract = sweep.arm_contract(
        target, overlap, threshold_chars, deadline_ms,
        run_id=metrics.get("run_id"), git_sha=metrics.get("git_sha"))
    _write_json(armdir / sweep.ARM_COMPLETION, contract)
    (armdir / sweep.ARM_DONE).write_text("done\n", encoding="utf-8")


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
            '  JUSTSEARCH_RERANK_DEADLINE_MS: "2000"\n'
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

        rc = sweep.run_arm(str(tmp_path), "mixed/enron-qa", 128, 0, 0,
                           quiet_gate=False, runner=spy)
        assert rc is None
        assert calls == []
        assert not (armdir / "arm.yaml").exists()
        line = json.loads((tmp_path / "progress.jsonl").read_text(encoding="utf-8").strip())
        assert line["skipped"] is True
        assert line["arm"] == "t128-o0-r0"

    def test_arm_without_done_marker_runs_and_marks_done(self, tmp_path, monkeypatch):
        monkeypatch.setattr(sweep, "signature", lambda *a, **k: {"games": "", "games_blocking": []})
        seen = {}

        def spy(cmd, cwd, log_path, env):
            seen["cmd"] = cmd
            seen["env"] = env
            return 0

        rc = sweep.run_arm(str(tmp_path), "mixed/enron-qa", 128, 50, 0,
                           quiet_gate=False, runner=spy)
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
        monkeypatch.setattr(sweep, "signature", lambda *a, **k: {"games": "", "games_blocking": []})
        rc = sweep.run_arm(str(tmp_path), "mixed/enron-qa", 384, 25, 0,
                           quiet_gate=False, runner=lambda *a: 3)
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

class TestChunkBranchTriState:
    """The chunk branch is a THREE-state reading, and the smoke arm proved why (916 §K.9)."""

    def test_observed_list_containing_chunk_merge_is_ran_true(self):
        summary = {"per_mode": {"hybrid": {
            "pipeline_tracking": {"observed": ["dense", "chunk_merge", "branch_fusion"]},
            "stage_timing_stats": {"chunk_merge_ms": 12.0}}}}
        got = sweep._chunk_branch(summary, "hybrid")
        assert got["ran"] is True
        assert got["chunk_merge_ms_present"] is True

    def test_observed_list_without_chunk_merge_is_ran_false(self):
        # `branch_fusion` is present and `chunk_merge` is not, on purpose: the two stages travel
        # together in a real run, so a fixture that omitted both could not tell a reader keying on
        # `chunk_merge` from one keying on `branch_fusion`.
        summary = {"per_mode": {"hybrid": {
            "pipeline_tracking": {"observed": ["dense", "branch_fusion", "cross_encoder"]},
            "stage_timing_stats": {"retrieval_ms": 3.0}}}}
        got = sweep._chunk_branch(summary, "hybrid")
        assert got["ran"] is False

    def test_absent_tracking_is_unknown_not_false(self):
        """The exact conflation the smoke arm exposed: absent must never read as 'did not run'."""
        assert sweep._chunk_branch({"per_mode": {"hybrid": {}}}, "hybrid")["ran"] is None
        assert sweep._chunk_branch({}, "hybrid")["ran"] is None
        partial = {"per_mode": {"hybrid": {"pipeline_tracking": {}, "stage_timing_stats": {"x": 1}}}}
        assert sweep._chunk_branch(partial, "hybrid")["ran"] is None


class TestDocsSourceLabelling:
    """`docs_s` must always say which source it came from — the smoke arms used two different ones."""

    def test_prefers_run_metrics_then_primary_indexing_then_ingest(self):
        assert sweep._first_not_none(None, None, 0.9) == 0.9
        assert sweep._first_not_none(8.4, 1.0, 0.9) == 8.4
        assert sweep._first_not_none(None, 1.0, 0.9) == 1.0
        assert sweep._first_not_none(None, None, None) is None


# --------------------------------------------------------------------------
# CE deadline as an arm-invariant campaign constant (owner decision 2026-09-03)
# --------------------------------------------------------------------------

class TestCampaignDeadlineConstant:
    def test_every_arm_carries_the_constant_including_the_incumbent(self):
        """A constant written only on SOME arms is an axis wearing a constant's name."""
        for target, overlap in sweep.arm_matrix():
            assert '  JUSTSEARCH_RERANK_DEADLINE_MS: "2000"\n' in sweep.arm_yaml(target, overlap)

    def test_the_control_arm_overrides_it_to_the_shipped_value(self):
        y = sweep.arm_yaml(500, 50, deadline_ms=sweep.SHIPPED_DEADLINE_MS)
        assert '  JUSTSEARCH_RERANK_DEADLINE_MS: "200"\n' in y
        assert '"2000"' not in y

    def test_the_control_arm_cannot_collide_with_the_campaign_incumbent(self):
        """Both are 500/50 rep 0; only the deadline differs, so the tag must differ."""
        assert sweep.arm_tag(500, 50, 0) == "t500-o50-r0"
        assert sweep.arm_tag(500, 50, 0, sweep.SHIPPED_DEADLINE_MS) == "t500-o50-d200-r0"

    def test_the_campaign_constant_leaves_the_sweep_tags_unsuffixed(self):
        """The twelve sweep arms keep their pre-decision directory names."""
        for target, overlap in sweep.arm_matrix():
            assert "-d" not in sweep.arm_tag(target, overlap, 0)

    def test_run_arm_writes_the_requested_deadline_into_the_arm_yaml(self, tmp_path, monkeypatch):
        monkeypatch.setattr(sweep, "signature", lambda *a, **k: {"games": "", "games_blocking": []})
        sweep.run_arm(str(tmp_path), "mixed/legal-clerc-200", 500, 50, 0,
                      deadline_ms=sweep.SHIPPED_DEADLINE_MS, quiet_gate=False,
                      runner=lambda *a: 0)
        armdir = tmp_path / "mixed_legal-clerc-200" / "t500-o50-d200-r0"
        assert '  JUSTSEARCH_RERANK_DEADLINE_MS: "200"\n' in (
            armdir / "arm.yaml").read_text(encoding="utf-8")
        doc = json.loads((armdir / "arm-metrics.json").read_text(encoding="utf-8"))
        assert doc["rerank_deadline_ms"] == 200

    def test_load_reads_the_deadline_back_from_the_runs_own_record(self, tmp_path):
        """Not from the driver's intent: `run.py:222` records what the run was given."""
        _make_arm(tmp_path, "mixed/legal-clerc-200", 500, 50, 0, ndcg=0.58,
                  deadline_ms=sweep.SHIPPED_DEADLINE_MS)
        rec = sweep.load(tmp_path / "mixed_legal-clerc-200" / "t500-o50-d200-r0")
        assert rec["env_deadline_ms"] == "200"


# --------------------------------------------------------------------------
# Quiet-machine gate: WAIT, not void
# --------------------------------------------------------------------------

class TestQuietGate:
    @pytest.mark.parametrize("games,util,mem,expected", [
        ("", 9, 1107, True),
        ("", 24, 2999, True),
        ("LeagueClient", 9, 1107, False),
        ("RiotClientServices,LeagueClient", 2, 900, False),
        ("", 25, 1107, False),
        ("", 90, 1107, False),
        ("", 9, 3000, False),
        ("", 9, 9000, False),
        ("", None, 1107, False),
        ("", 9, None, False),
        ("ERR nvidia-smi missing", 9, 1107, False),
    ])
    def test_truth_table(self, games, util, mem, expected):
        assert sweep.machine_is_quiet(games, util, mem) is expected

    def test_an_unreadable_probe_is_dirty_not_clean(self):
        """We-could-not-tell and nothing-is-running are different states."""
        assert sweep.machine_is_quiet("ERR boom", 1, 100) is False
        assert sweep.machine_is_quiet(None, 1, 100) is False
        assert sweep.blocking_games("ERR boom") is None
        assert sweep.blocking_games(None) is None

    def test_riot_launcher_helpers_are_observed_but_do_not_block(self):
        """Measured 2026-09-03: these two idle from boot at this box's GPU baseline.

        They must still be RECORDED — the filter is about what blocks, not what is seen.
        """
        raw = "RiotClientCrashHandler,RiotClientServices"
        assert sweep.blocking_games(raw) == []
        assert sweep.machine_is_quiet(raw, 17, 1161) is True

    def test_a_real_game_alongside_the_helpers_still_blocks(self):
        """The exclusion must not swallow a game that happens to share the launcher."""
        raw = "RiotClientServices,LeagueClient"
        assert sweep.blocking_games(raw) == ["LeagueClient"]
        assert sweep.machine_is_quiet(raw, 5, 1100) is False

    def test_the_launcher_window_is_not_excluded(self):
        """`RiotClientUx` renders; only the back-end services are non-blocking."""
        assert "RiotClientUx" not in sweep.GAME_PROCESS_NONBLOCKING
        assert sweep.blocking_games("RiotClientUx") == ["RiotClientUx"]

    @staticmethod
    def _scripted(monkeypatch, seq):
        calls = {"n": 0}
        monkeypatch.setattr(sweep, "games_probe",
                            lambda: seq[min(calls["n"], len(seq) - 1)][0])

        def fake_gpu():
            i = min(calls["n"], len(seq) - 1)
            calls["n"] += 1
            return (seq[i][2], seq[i][1], "raw")

        monkeypatch.setattr(sweep, "gpu_probe", fake_gpu)
        monkeypatch.setattr(sweep.time, "sleep", lambda s: None)
        return calls

    def test_waits_until_quiet_then_returns(self, tmp_path, monkeypatch):
        calls = self._scripted(monkeypatch, [
            ("LeagueClient", 80, 6000), ("LeagueClient", 40, 6000),
            ("", 5, 1100), ("", 5, 1100), ("", 5, 1100)])
        sweep.wait_for_quiet(str(tmp_path), "x", quiet_sec=10, poll_sec=5)
        assert calls["n"] == 4     # 2 dirty samples, then 2 consecutive quiet ones

    def test_a_single_quiet_sample_is_not_enough(self, tmp_path, monkeypatch):
        """A game client's GPU use is bursty; the streak resets on any dirty sample."""
        calls = self._scripted(monkeypatch, [
            ("", 5, 1100), ("LeagueClient", 80, 6000), ("", 5, 1100), ("", 5, 1100)])
        sweep.wait_for_quiet(str(tmp_path), "x", quiet_sec=10, poll_sec=5)
        assert calls["n"] == 4

    def test_times_out_rather_than_proceeding_dirty(self, tmp_path, monkeypatch):
        self._scripted(monkeypatch, [("LeagueClient", 80, 6000)])
        with pytest.raises(RuntimeError, match="quiet-machine gate timed out"):
            sweep.wait_for_quiet(str(tmp_path), "x", quiet_sec=10, poll_sec=5, timeout_sec=-1)
        last = (tmp_path / "signatures.jsonl").read_text(
            encoding="utf-8").strip().splitlines()[-1]
        assert json.loads(last)["tag"] == "x:quiet-gate-timeout"

    def test_tft_is_on_the_process_list(self):
        for name in ("League", "Riot", "VALORANT", "cs2", "TFT"):
            assert name in sweep.GAME_PROCESS_PATTERN


# --------------------------------------------------------------------------
# A dirty arm is RE-RUN, never counted
# --------------------------------------------------------------------------

def _monitor(dirty_on):
    """An `ArmMonitor` stand-in reporting dirty on the attempts named in `dirty_on`."""
    state = {"n": 0}

    class Fake:
        def __init__(self, out, tag, interval_sec=30):
            self.observed = ["LeagueClient"]
            self.dirty = False

        def __enter__(self):
            state["n"] += 1
            self.dirty = state["n"] in dirty_on
            return self

        def __exit__(self, *exc):
            return False

    return Fake, state


class TestDirtyArmIsRerun:
    def test_a_dirty_window_moves_the_attempt_aside_and_re_runs(self, tmp_path, monkeypatch):
        monkeypatch.setattr(sweep, "signature", lambda *a, **k: {"games": "", "games_blocking": []})
        fake, state = _monitor({1})
        monkeypatch.setattr(sweep, "ArmMonitor", fake)
        rc = sweep.run_arm(str(tmp_path), "mixed/legal-clerc-200", 500, 50, 0,
                           quiet_gate=False, runner=lambda *a: 0)
        armdir = tmp_path / "mixed_legal-clerc-200" / "t500-o50-r0"
        assert state["n"] == 2 and rc == 0
        assert (armdir / sweep.ARM_DONE).exists()
        # The contaminated attempt is preserved, not deleted.
        assert (tmp_path / "mixed_legal-clerc-200" / "t500-o50-r0.dirty1").is_dir()

    def test_a_dirty_arm_that_exhausts_retries_is_left_resumable(self, tmp_path, monkeypatch):
        monkeypatch.setattr(sweep, "signature", lambda *a, **k: {"games": "", "games_blocking": []})
        fake, _ = _monitor({1, 2, 3})
        monkeypatch.setattr(sweep, "ArmMonitor", fake)
        sweep.run_arm(str(tmp_path), "mixed/legal-clerc-200", 500, 50, 0,
                      quiet_gate=False, max_dirty_retries=1, runner=lambda *a: 0)
        armdir = tmp_path / "mixed_legal-clerc-200" / "t500-o50-r0"
        # Exit 0, but the window was dirty: ARM.done must NOT be written, or a resumed
        # driver would skip a contaminated arm and the roll-up would count it.
        assert not (armdir / sweep.ARM_DONE).exists()
        doc = json.loads((armdir / "arm-metrics.json").read_text(encoding="utf-8"))
        assert doc["machine_dirty"] is True

    def test_a_clean_window_writes_done_and_no_dirty_copy(self, tmp_path, monkeypatch):
        monkeypatch.setattr(sweep, "signature", lambda *a, **k: {"games": "", "games_blocking": []})
        rc = sweep.run_arm(str(tmp_path), "mixed/legal-clerc-200", 384, 25, 0,
                           quiet_gate=False, runner=lambda *a: 0)
        armdir = tmp_path / "mixed_legal-clerc-200" / "t384-o25-r0"
        assert rc == 0 and (armdir / sweep.ARM_DONE).exists()
        assert not (tmp_path / "mixed_legal-clerc-200" / "t384-o25-r0.dirty1").exists()
        doc = json.loads((armdir / "arm-metrics.json").read_text(encoding="utf-8"))
        assert doc["machine_dirty"] is False

    def test_a_game_in_the_pre_signature_alone_makes_the_arm_dirty(self, tmp_path, monkeypatch):
        """The mid-arm monitor is one of three witnesses, not the only one."""
        monkeypatch.setattr(
            sweep, "signature",
            lambda out, tag: {"games_blocking": ["LeagueClient"]} if tag.endswith(":pre")
            else {"games_blocking": []})
        sweep.run_arm(str(tmp_path), "mixed/legal-clerc-200", 256, 0, 0,
                      quiet_gate=False, max_dirty_retries=0, runner=lambda *a: 0)
        assert not (tmp_path / "mixed_legal-clerc-200" / "t256-o0-r0" / sweep.ARM_DONE).exists()


# --------------------------------------------------------------------------
# Arm selection and the null-column campaign stop
# --------------------------------------------------------------------------

class TestArmSelection:
    def test_default_is_the_full_matrix(self):
        assert sweep.parse_arms(None) == sweep.arm_matrix()
        assert sweep.parse_arms("") == sweep.arm_matrix()

    def test_explicit_selection_preserves_order_and_parses_pairs(self):
        assert sweep.parse_arms("500/50,128/0, 384/25") == [(500, 50), (128, 0), (384, 25)]


class TestNewColumnsReadTheirOwnField:
    """Value precision, not just presence: each new column must come from its own source."""

    def test_p1_and_the_ce_columns_are_not_borrowed_from_a_neighbour(self, tmp_path):
        _make_arm(tmp_path, "mixed/legal-clerc-200", 500, 50, 0,
                  ndcg=0.58, r10=0.5, silent_drops=7, chunk_docs=4122)
        rec = sweep.load(tmp_path / "mixed_legal-clerc-200" / "t500-o50-r0")
        assert rec["p1"] == pytest.approx(0.6)          # not r10's 0.5, not ndcg's 0.58
        assert rec["ce_silent_drops"] == 7
        assert rec["ce_coverage_frac"] == pytest.approx(0.995)
        assert rec["ce_eligible"] == 199
        # `observed` (the chunk documents written), not `expected` (the parents), which is 194.
        assert rec["chunk_docs"] == 4122
        assert rec["chunk_parents_expected"] == 194


class TestDecisionBearingNulls:
    def test_a_complete_arm_has_no_null_decision_columns(self, tmp_path):
        _make_arm(tmp_path, "mixed/legal-clerc-200", 500, 50, 0, ndcg=0.58)
        assert sweep.decision_bearing_nulls(
            tmp_path / "mixed_legal-clerc-200" / "t500-o50-r0") == []

    def test_a_missing_truncation_projection_is_reported_not_silently_dashed(self, tmp_path):
        _make_arm(tmp_path, "mixed/legal-clerc-200", 500, 50, 0, ndcg=0.58, trunc_rate=None)
        assert sweep.decision_bearing_nulls(
            tmp_path / "mixed_legal-clerc-200" / "t500-o50-r0") == ["trunc_rate"]

    def test_a_missing_chunk_completeness_block_is_reported(self, tmp_path):
        _make_arm(tmp_path, "mixed/legal-clerc-200", 500, 50, 0, ndcg=0.58, chunk_docs=None)
        assert sweep.decision_bearing_nulls(
            tmp_path / "mixed_legal-clerc-200" / "t500-o50-r0") == ["chunk_docs"]

    def test_an_arm_with_no_run_at_all_reports_every_column(self, tmp_path):
        (tmp_path / "empty").mkdir()
        assert sweep.decision_bearing_nulls(tmp_path / "empty") == list(sweep.DECISION_BEARING)


class TestHistoricalRunGuard:
    def test_run_refuses_when_temporary_binding_is_absent(self, tmp_path):
        registry = tmp_path / "EnvRegistry.java"
        registry.write_text("// shipping tree: experiment channel retired\n", encoding="utf-8")
        with pytest.raises(RuntimeError, match="run mode is historical"):
            sweep.require_sweep_channel(str(registry))

    def test_preflight_accepts_only_a_source_containing_all_four_keys(self, tmp_path):
        registry = tmp_path / "EnvRegistry.java"
        registry.write_text("\n".join((
            sweep.KEY_TARGET, sweep.KEY_OVERLAP, sweep.KEY_MIN, sweep.KEY_THRESHOLD)),
            encoding="utf-8")
        sweep.require_sweep_channel(str(registry))


class TestStructuredArmCompletion:
    def _complete(self, tmp_path, *, target=500, overlap=50, threshold_chars=None,
                  machine_dirty=False, chunk_branch=True):
        armdir = _make_arm(
            tmp_path, "mixed/legal-clerc-200", target, overlap, 0, ndcg=0.58,
            threshold_chars=threshold_chars, chunk_branch=chunk_branch)
        _mark_complete(
            armdir, target, overlap, threshold_chars=threshold_chars,
            machine_dirty=machine_dirty)
        return armdir

    def test_matching_clean_arm_is_reusable(self, tmp_path):
        armdir = self._complete(tmp_path)
        expected = sweep.arm_contract(500, 50, None, sweep.CAMPAIGN_DEADLINE_MS)
        assert sweep.completed_arm_problems(
            str(armdir), expected=expected, require_chunk_branch=True) == []

    def test_stale_done_marker_cannot_change_requested_arm(self, tmp_path):
        armdir = self._complete(tmp_path, target=500, overlap=50)
        expected = sweep.arm_contract(384, 50, None, sweep.CAMPAIGN_DEADLINE_MS)
        problems = sweep.completed_arm_problems(
            str(armdir), expected=expected, require_chunk_branch=True)
        assert any("target_tokens mismatch" in p for p in problems)

    def test_threshold_override_is_part_of_resume_identity(self, tmp_path):
        armdir = self._complete(tmp_path, threshold_chars=2400)
        expected = sweep.arm_contract(500, 50, 2000, sweep.CAMPAIGN_DEADLINE_MS)
        problems = sweep.completed_arm_problems(
            str(armdir), expected=expected, require_chunk_branch=True)
        assert any("threshold_override mismatch" in p for p in problems)

    @pytest.mark.parametrize("chunk_branch", [False, None])
    def test_arm_corpus_requires_proven_chunk_branch(self, tmp_path, chunk_branch):
        armdir = self._complete(tmp_path, chunk_branch=chunk_branch)
        expected = sweep.arm_contract(500, 50, None, sweep.CAMPAIGN_DEADLINE_MS)
        problems = sweep.completed_arm_problems(
            str(armdir), expected=expected, require_chunk_branch=True)
        assert any("chunk branch" in p for p in problems)

    def test_dirty_machine_window_cannot_complete(self, tmp_path):
        armdir = self._complete(tmp_path, machine_dirty=True)
        expected = sweep.arm_contract(500, 50, None, sweep.CAMPAIGN_DEADLINE_MS)
        problems = sweep.completed_arm_problems(
            str(armdir), expected=expected, require_chunk_branch=True)
        assert "machine window is not proven clean" in problems

    def test_plain_done_marker_without_contract_is_not_reusable(self, tmp_path):
        armdir = _make_arm(
            tmp_path, "mixed/legal-clerc-200", 500, 50, 0, ndcg=0.58)
        sweep.capture_arm_metrics(
            str(armdir), 500, 50, deadline_ms=sweep.CAMPAIGN_DEADLINE_MS,
            machine_dirty=False)
        (armdir / sweep.ARM_DONE).write_text("done\n", encoding="utf-8")
        expected = sweep.arm_contract(500, 50, None, sweep.CAMPAIGN_DEADLINE_MS)
        problems = sweep.completed_arm_problems(
            str(armdir), expected=expected, require_chunk_branch=True)
        assert "structured arm completion missing or unreadable" in problems


class TestPhaseFailsClosed:
    @staticmethod
    def _args(tmp_path):
        return sweep.argparse.Namespace(
            out=str(tmp_path), corpora="mixed/legal-clerc-200", arms="500/50", reps=1,
            threshold_chars=None, deadline_ms=sweep.CAMPAIGN_DEADLINE_MS, phase="test",
            quiet_sec=0, gpu_idle_pct=100, gpu_idle_mem_mib=99_999,
            no_quiet_gate=True, mode="hybrid")

    def test_nonzero_arm_stops_without_phase_markers(self, tmp_path, monkeypatch):
        monkeypatch.setattr(sweep, "require_sweep_channel", lambda: None)
        monkeypatch.setattr(sweep, "run_arm", lambda *a, **k: 3)

        with pytest.raises(RuntimeError, match="exited 3"):
            sweep.do_run(self._args(tmp_path))

        assert not (tmp_path / "mixed_legal-clerc-200" / "testCORPUS.done").exists()
        assert not (tmp_path / "testRUN.done").exists()

    def test_null_decision_field_retracts_arm_done_and_stops(self, tmp_path, monkeypatch):
        armdir = tmp_path / "mixed_legal-clerc-200" / "t500-o50-r0"

        def fake_run(*args, **kwargs):
            armdir.mkdir(parents=True)
            (armdir / sweep.ARM_DONE).touch()
            return 0

        monkeypatch.setattr(sweep, "require_sweep_channel", lambda: None)
        monkeypatch.setattr(sweep, "run_arm", fake_run)
        monkeypatch.setattr(
            sweep, "completed_arm_problems",
            lambda *a, **k: ["decision-bearing nulls: trunc_rate"])

        with pytest.raises(RuntimeError, match="decision-bearing nulls: trunc_rate"):
            sweep.do_run(self._args(tmp_path))

        assert not (armdir / sweep.ARM_DONE).exists()
        assert not (tmp_path / "mixed_legal-clerc-200" / "testCORPUS.done").exists()
        assert not (tmp_path / "testRUN.done").exists()

    def test_chain_launcher_propagates_a_failed_phase(self):
        launcher = (_SCRIPT.parent / "916_launch_chain.ps1").read_text(encoding="utf-8")
        assert "`$ErrorActionPreference = 'Stop'" in launcher
        assert "if (`$LASTEXITCODE -ne 0) { exit `$LASTEXITCODE }" in launcher
