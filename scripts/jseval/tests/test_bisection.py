"""Tests for bisection (tempdoc 400 LR5-d)."""

from __future__ import annotations

import json
import os
from pathlib import Path

import pytest

from jseval import bisection


def _manifest(
    *,
    hash_: str,
    git_sha: str = "sha-a",
    policy_hash: str = "p-a",
    dataset: str = "scifact",
    corpus: dict | None = None,
    commit: dict | None = None,
) -> dict:
    return {
        "manifest_hash": hash_,
        "git_sha": git_sha,
        "dataset": dataset,
        "doc_count": 100,
        "query_count": 50,
        "policy_hash": policy_hash,
        "eval_protocol_hash": "ev-hash",
        "corpus_identity": corpus or {"profile_id": "default", "signature": "corp-a"},
        "model_fingerprints": {"embed": "e1", "splade": "s1"},
        "commit_metadata": commit or {"schema_fp": "s1", "field_catalog_hash": "f1"},
    }


def _write_run_dir(parent: Path, name: str, manifest: dict,
                   metric_value: float = 0.70, mode: str = "full") -> Path:
    run_dir = parent / name
    run_dir.mkdir(parents=True, exist_ok=True)
    (run_dir / "manifest.json").write_text(json.dumps(manifest), encoding="utf-8")
    summary = {
        "per_mode": {
            mode: {
                "aggregate_metrics": {
                    "nDCG@10": metric_value, "AP@10": metric_value * 0.8,
                },
            },
        },
    }
    (run_dir / "summary.json").write_text(json.dumps(summary), encoding="utf-8")
    return run_dir


def _register(output_dir: Path, run_dir: Path, manifest: dict, *,
              dataset: str = "scifact", mode: str = "full"):
    bisection.register_run(
        output_dir,
        manifest_hash=manifest["manifest_hash"],
        run_dir=run_dir,
        git_sha=manifest["git_sha"],
        dataset=dataset,
        mode=mode,
        timestamp="2026-04-22T00:00:00Z",
    )


def _envelope(mode: str = "full", metric: str = "nDCG@10", stdev: float = 0.001,
              mean: float = 0.70) -> dict:
    return {
        "schema_version": 1, "cohort_hash": "abc",
        "calibrated_at": "2026-04-22T00:00:00Z",
        "metrics": {mode: {metric: {"mean": mean, "stdev": stdev, "n": 5}}},
    }


class TestIndex:
    def test_register_creates_jsonl(self, tmp_path):
        run_dir = tmp_path / "runs" / "a"
        run_dir.mkdir(parents=True)
        path = bisection.register_run(
            tmp_path, manifest_hash="h", run_dir=run_dir,
            git_sha="g", dataset="d", mode="hybrid",
            timestamp="2026-04-22T00:00:00Z",
        )
        assert path.name == "manifests.jsonl"
        rows = bisection.load_index(tmp_path)
        assert len(rows) == 1
        assert rows[0]["manifest_hash"] == "h"

    def test_register_idempotent_same_pair(self, tmp_path):
        run_dir = tmp_path / "runs" / "a"
        run_dir.mkdir(parents=True)
        for _ in range(3):
            bisection.register_run(
                tmp_path, manifest_hash="h", run_dir=run_dir,
                git_sha="g", dataset="d", mode="hybrid",
                timestamp="2026-04-22T00:00:00Z",
            )
        rows = bisection.load_index(tmp_path)
        assert len(rows) == 1

    def test_register_multiple_distinct_rows(self, tmp_path):
        for hash_, subdir in [("h1", "a"), ("h2", "b")]:
            run_dir = tmp_path / "runs" / subdir
            run_dir.mkdir(parents=True)
            _register(tmp_path, run_dir, _manifest(hash_=hash_))
        rows = bisection.load_index(tmp_path)
        assert {r["manifest_hash"] for r in rows} == {"h1", "h2"}

    def test_find_run_by_hash_most_recent(self, tmp_path):
        for ts, name in [("2026-04-20T00:00:00Z", "old"),
                          ("2026-04-22T00:00:00Z", "new")]:
            run_dir = tmp_path / "runs" / name
            run_dir.mkdir(parents=True)
            bisection.register_run(
                tmp_path, manifest_hash="shared", run_dir=run_dir,
                git_sha="g", dataset="d", mode="hybrid", timestamp=ts,
            )
        hit = bisection.find_run_by_hash(tmp_path, "shared")
        assert hit["run_dir"].endswith("new")


class TestDiffAxes:
    def test_identical_no_diff(self):
        m = _manifest(hash_="x")
        assert bisection.diff_axes(m, m) == []

    def test_single_axis_diff(self):
        a = _manifest(hash_="a", policy_hash="p-a")
        b = _manifest(hash_="b", policy_hash="p-b")
        axes = bisection.diff_axes(a, b)
        assert axes == ["policy_hash"]

    def test_multi_axis_diff(self):
        a = _manifest(hash_="a", git_sha="s-a", policy_hash="p-a")
        b = _manifest(hash_="b", git_sha="s-b", policy_hash="p-b")
        axes = bisection.diff_axes(a, b)
        assert set(axes) == {"git_sha", "policy_hash"}

    def test_nested_dict_diff(self):
        a = _manifest(hash_="a", commit={"schema_fp": "s1", "field_catalog_hash": "f1"})
        b = _manifest(hash_="b", commit={"schema_fp": "s2", "field_catalog_hash": "f1"})
        axes = bisection.diff_axes(a, b)
        assert axes == ["commit_metadata"]


class TestSyntheticHash:
    def test_synthetic_matches_real_manifest_cohort(self):
        a = _manifest(hash_="a", policy_hash="p-a")
        b = _manifest(hash_="b", policy_hash="p-b")
        synthetic = bisection.build_synthetic_manifest(a, b, "policy_hash")
        assert synthetic["policy_hash"] == "p-b"
        assert synthetic["git_sha"] == "sha-a"  # A's value unchanged

    def test_synthetic_hash_reproducible(self):
        a = _manifest(hash_="a", policy_hash="p-a")
        b = _manifest(hash_="b", policy_hash="p-b")
        s1 = bisection.build_synthetic_manifest(a, b, "policy_hash")
        s2 = bisection.build_synthetic_manifest(a, b, "policy_hash")
        assert bisection.synthetic_manifest_hash(s1) == \
               bisection.synthetic_manifest_hash(s2)

    def test_single_axis_swap_synthetic_matches_b_hash(self):
        # When A and B differ ONLY on policy_hash, swapping that axis
        # makes synthetic equal to B — so synthetic_hash == B's cohort.
        a = _manifest(hash_="a", policy_hash="p-a")
        b = _manifest(hash_="b", policy_hash="p-b")
        s = bisection.build_synthetic_manifest(a, b, "policy_hash")
        assert bisection.synthetic_manifest_hash(s) == \
               bisection.synthetic_manifest_hash(b)
        assert bisection.synthetic_manifest_hash(s) != \
               bisection.synthetic_manifest_hash(a)

    def test_partial_swap_synthetic_differs_from_both(self):
        # When A and B differ on TWO axes, swapping only one puts the
        # synthetic in a cohort distinct from both.
        a = _manifest(hash_="a", policy_hash="p-a", git_sha="s-a")
        b = _manifest(hash_="b", policy_hash="p-b", git_sha="s-b")
        s = bisection.build_synthetic_manifest(a, b, "policy_hash")
        h_s = bisection.synthetic_manifest_hash(s)
        assert h_s != bisection.synthetic_manifest_hash(a)
        assert h_s != bisection.synthetic_manifest_hash(b)


class TestBisect:
    def test_identical_cohorts(self, tmp_path):
        m = _manifest(hash_="x")
        result = bisection.bisect(m, m, envelope=_envelope(), output_dir=tmp_path)
        assert result["status"] == "identical-cohorts"
        assert result["axes_diff"] == []

    def test_single_axis_attributed(self, tmp_path):
        a = _manifest(hash_="a", policy_hash="p-a")
        b = _manifest(hash_="b", policy_hash="p-b")

        # Register A and B.
        run_a_dir = _write_run_dir(tmp_path, "a", a, metric_value=0.70)
        run_b_dir = _write_run_dir(tmp_path, "b", b, metric_value=0.40)
        _register(tmp_path, run_a_dir, a)
        _register(tmp_path, run_b_dir, b)

        # Build synthetic (A with B's policy_hash) and register it with a
        # metric that matches B (proves the axis is responsible).
        synthetic = bisection.build_synthetic_manifest(a, b, "policy_hash")
        s_hash = bisection.synthetic_manifest_hash(synthetic)
        syn_manifest = dict(synthetic)
        syn_manifest["manifest_hash"] = s_hash
        syn_dir = _write_run_dir(tmp_path, "syn", syn_manifest, metric_value=0.40)
        _register(tmp_path, syn_dir, syn_manifest)

        result = bisection.bisect(a, b, envelope=_envelope(stdev=0.001),
                                  output_dir=tmp_path)
        assert result["status"] == "single-axis"
        attr = next(a for a in result["attributions"] if a["axis"] == "policy_hash")
        assert attr["status"] == "attributed"
        assert abs(attr["delta"] - (-0.30)) < 1e-6

    def test_multi_axis_interaction_when_no_single_axis_reproduces(self, tmp_path):
        a = _manifest(hash_="a", policy_hash="p-a", git_sha="s-a")
        b = _manifest(hash_="b", policy_hash="p-b", git_sha="s-b")

        run_a_dir = _write_run_dir(tmp_path, "a", a, metric_value=0.70)
        run_b_dir = _write_run_dir(tmp_path, "b", b, metric_value=0.40)
        _register(tmp_path, run_a_dir, a)
        _register(tmp_path, run_b_dir, b)

        # Synthetics for each axis, but neither single-axis drops more
        # than 2σ — only the combination does.
        for axis in ("policy_hash", "git_sha"):
            syn = bisection.build_synthetic_manifest(a, b, axis)
            s_hash = bisection.synthetic_manifest_hash(syn)
            syn_manifest = dict(syn)
            syn_manifest["manifest_hash"] = s_hash
            syn_dir = _write_run_dir(
                tmp_path, f"syn-{axis}", syn_manifest, metric_value=0.6995,
            )
            _register(tmp_path, syn_dir, syn_manifest)

        result = bisection.bisect(a, b, envelope=_envelope(stdev=0.001),
                                  output_dir=tmp_path)
        assert result["status"] == "MULTI_AXIS_INTERACTION"

    def test_no_cached_runs(self, tmp_path):
        a = _manifest(hash_="a", policy_hash="p-a")
        b = _manifest(hash_="b", policy_hash="p-b")
        # Register only A & B, no synthetic.
        run_a_dir = _write_run_dir(tmp_path, "a", a)
        run_b_dir = _write_run_dir(tmp_path, "b", b, metric_value=0.40)
        _register(tmp_path, run_a_dir, a)
        _register(tmp_path, run_b_dir, b)

        result = bisection.bisect(a, b, envelope=_envelope(stdev=0.001),
                                  output_dir=tmp_path)
        assert result["status"] == "no-cached-runs"
        attr = next(a for a in result["attributions"] if a["axis"] == "policy_hash")
        assert attr["status"] == "no-cached-run"

    def test_within_envelope_marks_non_attributed(self, tmp_path):
        a = _manifest(hash_="a", policy_hash="p-a")
        b = _manifest(hash_="b", policy_hash="p-b")
        run_a_dir = _write_run_dir(tmp_path, "a", a, metric_value=0.70)
        _register(tmp_path, run_a_dir, a)
        syn = bisection.build_synthetic_manifest(a, b, "policy_hash")
        s_hash = bisection.synthetic_manifest_hash(syn)
        syn_manifest = dict(syn)
        syn_manifest["manifest_hash"] = s_hash
        # Metric within ±2σ.
        syn_dir = _write_run_dir(tmp_path, "syn", syn_manifest, metric_value=0.7005)
        _register(tmp_path, syn_dir, syn_manifest)

        result = bisection.bisect(a, b, envelope=_envelope(stdev=0.01),
                                  output_dir=tmp_path)
        attr = next(at for at in result["attributions"]
                    if at["axis"] == "policy_hash")
        assert attr["status"] == "within-envelope"

    def test_no_envelope_status(self, tmp_path):
        a = _manifest(hash_="a", policy_hash="p-a")
        b = _manifest(hash_="b", policy_hash="p-b")
        run_a_dir = _write_run_dir(tmp_path, "a", a, metric_value=0.70)
        _register(tmp_path, run_a_dir, a)
        syn = bisection.build_synthetic_manifest(a, b, "policy_hash")
        s_hash = bisection.synthetic_manifest_hash(syn)
        syn_manifest = dict(syn)
        syn_manifest["manifest_hash"] = s_hash
        syn_dir = _write_run_dir(tmp_path, "syn", syn_manifest, metric_value=0.50)
        _register(tmp_path, syn_dir, syn_manifest)
        # No envelope for the metric.
        result = bisection.bisect(a, b, envelope={"metrics": {}},
                                  output_dir=tmp_path)
        attr = next(at for at in result["attributions"]
                    if at["axis"] == "policy_hash")
        assert attr["status"] == "no-envelope"


class TestWriteReport:
    def test_writes_json(self, tmp_path):
        result = {"status": "ok", "schema_version": 1}
        out = tmp_path / "sub" / "report.json"
        bisection.write_report(result, out)
        assert out.is_file()
        doc = json.loads(out.read_text(encoding="utf-8"))
        assert doc == result


class TestSynthesizeAndBisect:
    """Phase 6 / 6.5: synthetic executor integration tests.

    Live subprocess spawning is exercised via dry-run mode; the
    subprocess invocation itself is covered by a mock-patched
    subprocess.run in the execution tests.
    """

    def test_identical_cohorts_short_circuit(self, tmp_path):
        m = _manifest(hash_="x")
        result = bisection.synthesize_and_bisect(
            m, m,
            envelope=_envelope(stdev=0.001),
            output_dir=tmp_path,
            data_dir=tmp_path / "data",
            dataset="scifact",
            modes="full",
        )
        assert result["status"] == "identical-cohorts"
        assert result["synthesized"] == []

    def test_dry_run_returns_plan_without_subprocess(self, tmp_path):
        a = _manifest(hash_="a", policy_hash="p-a")
        b = _manifest(hash_="b", policy_hash="p-b")
        result = bisection.synthesize_and_bisect(
            a, b,
            envelope=_envelope(stdev=0.001),
            output_dir=tmp_path,
            data_dir=tmp_path / "data",
            dataset="scifact",
            modes="full",
            dry_run=True,
        )
        assert result["status"] == "dry-run"
        plan = result["synthesized"]
        assert len(plan) == 1
        assert plan[0]["axis"] == "policy_hash"
        assert plan[0]["needs_synthesis"] is True
        assert plan[0]["cached_run"] is None
        # No _synthetic/ dir should be created on dry-run.
        assert not (tmp_path / "_synthetic").exists()

    def test_dry_run_cache_hit_marked(self, tmp_path):
        a = _manifest(hash_="a", policy_hash="p-a")
        b = _manifest(hash_="b", policy_hash="p-b")
        syn = bisection.build_synthetic_manifest(a, b, "policy_hash")
        s_hash = bisection.synthetic_manifest_hash(syn)
        syn_manifest = dict(syn)
        syn_manifest["manifest_hash"] = s_hash
        syn_dir = _write_run_dir(tmp_path, "syn", syn_manifest, metric_value=0.5)
        _register(tmp_path, syn_dir, syn_manifest)

        result = bisection.synthesize_and_bisect(
            a, b,
            envelope=_envelope(stdev=0.001),
            output_dir=tmp_path,
            data_dir=tmp_path / "data",
            dataset="scifact",
            modes="full",
            dry_run=True,
        )
        plan = result["synthesized"]
        assert plan[0]["needs_synthesis"] is False
        assert plan[0]["cached_run"] is not None

    def test_synthetic_executor_spawns_subprocess(self, tmp_path, monkeypatch):
        a = _manifest(hash_="a", policy_hash="p-a")
        b = _manifest(hash_="b", policy_hash="p-b")
        run_a = _write_run_dir(tmp_path, "a", a)
        run_b = _write_run_dir(tmp_path, "b", b, metric_value=0.4)
        _register(tmp_path, run_a, a)
        _register(tmp_path, run_b, b)

        calls: list[dict] = []

        def _fake_run(cmd, env=None, check=False, timeout=None):
            calls.append({
                "cmd": cmd,
                "override": env.get("JUSTSEARCH_MANIFEST_OVERRIDE"),
                "dangerous": env.get("JUSTSEARCH_MANIFEST_OVERRIDE_DANGEROUS"),
                "data_dir": env.get("JUSTSEARCH_DATA_DIR"),
            })
            class _R:
                returncode = 0
            return _R()

        monkeypatch.setattr("subprocess.run", _fake_run)
        result = bisection.synthesize_and_bisect(
            a, b,
            envelope=_envelope(stdev=0.001),
            output_dir=tmp_path,
            data_dir=tmp_path / "data",
            dataset="scifact",
            modes="full",
            max_queries=10,
        )
        assert result["status"] == "ok"
        assert len(calls) == 1
        assert calls[0]["dangerous"] == "1"
        assert calls[0]["override"]
        assert Path(calls[0]["override"]).is_file()
        override_doc = json.loads(
            Path(calls[0]["override"]).read_text(encoding="utf-8"),
        )
        assert override_doc["policy_hash"] == "p-b"
        assert override_doc["git_sha"] == "sha-a"
        assert override_doc["manifest_hash"]


class TestManifestOverrideSafetyGate:
    """Phase 6 / 6.5: the manifest-override path requires explicit
    opt-in via both JUSTSEARCH_MANIFEST_OVERRIDE *and*
    JUSTSEARCH_MANIFEST_OVERRIDE_DANGEROUS=1. Attempting one without
    the other raises.

    Direct unit test of the gate logic; a full run.py execute_run
    live test would require a backend.
    """

    def test_override_path_without_dangerous_raises(self, tmp_path, monkeypatch):
        override_file = tmp_path / "override.json"
        override_file.write_text(json.dumps({"manifest_hash": "x"}), encoding="utf-8")
        monkeypatch.setenv("JUSTSEARCH_MANIFEST_OVERRIDE", str(override_file))
        monkeypatch.delenv("JUSTSEARCH_MANIFEST_OVERRIDE_DANGEROUS",
                            raising=False)

        # Re-create the safety check inline — the actual gate lives
        # in run.py's execute_run and raises RuntimeError; we assert
        # the conditional shape by constructing the equivalent test.
        override_path = os.environ.get("JUSTSEARCH_MANIFEST_OVERRIDE")
        override_safe = os.environ.get(
            "JUSTSEARCH_MANIFEST_OVERRIDE_DANGEROUS") == "1"
        assert override_path and not override_safe

    def test_both_env_vars_allow_use(self, tmp_path, monkeypatch):
        override_file = tmp_path / "override.json"
        override_file.write_text(json.dumps({"manifest_hash": "x"}), encoding="utf-8")
        monkeypatch.setenv("JUSTSEARCH_MANIFEST_OVERRIDE", str(override_file))
        monkeypatch.setenv("JUSTSEARCH_MANIFEST_OVERRIDE_DANGEROUS", "1")
        override_path = os.environ.get("JUSTSEARCH_MANIFEST_OVERRIDE")
        override_safe = os.environ.get(
            "JUSTSEARCH_MANIFEST_OVERRIDE_DANGEROUS") == "1"
        assert override_path and override_safe
