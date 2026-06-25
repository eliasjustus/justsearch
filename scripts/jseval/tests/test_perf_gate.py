"""Tests for perf_gate.py — the performance ratchet (tempdoc 640)."""

from __future__ import annotations

from jseval.perf_gate import (
    derive_resident_model_bytes,
    evaluate,
    project_release_to_perf_baselines,
    project_run_to_perf_baselines,
    run_dataset_ok,
)

BASELINES = {
    "baselines": {
        "beir/scifact": {
            "mode": "hybrid",
            "metrics": {"ce_p50_ms": 170.0, "primary_docs_s": 300.0, "enrich_docs_s": 25.0},
            "bands": {"ce_p50_ms": 1.25, "primary_docs_s": 0.65, "enrich_docs_s": 0.75},
        },
    },
}


def _summary(mode: str, *, ce_p50=None, primary=None, enrich=None) -> dict:
    pm: dict = {}
    if ce_p50 is not None:
        pm["stage_timing_stats"] = {"cross_encoder_ms": {"p50": ce_p50}}
    summary: dict = {"per_mode": {mode: pm}}
    ing: dict = {}
    if enrich is not None:
        ing["docs_per_sec"] = enrich
    if primary is not None:
        ing["pipeline_summary"] = {"primary_indexing": {"docs_per_s": primary}}
    if ing:
        summary["ingest"] = ing
    return summary


def _ok(mode="hybrid"):
    # well inside every band: latency a touch higher, throughput a touch lower.
    return _summary(mode, ce_p50=180.0, primary=290.0, enrich=24.0)


def test_within_band_passes():
    report = evaluate(BASELINES, _ok(), "beir/scifact")
    assert report["exit_code"] == 0
    assert {c["status"] for c in report["checks"]} == {"ok"}


def test_ce_latency_regression_fails():
    # 230/170 = 1.35 > 1.25 band → regression on the dominant-cost metric.
    report = evaluate(BASELINES, _summary("hybrid", ce_p50=230.0, primary=290.0, enrich=24.0),
                      "beir/scifact")
    assert report["exit_code"] == 1
    ce = next(c for c in report["checks"] if c["name"] == "ce_p50_ms")
    assert ce["status"] == "fail"


def test_throughput_drop_fails():
    # primary 150/300 = 0.50 < 0.65 band → throughput regression.
    report = evaluate(BASELINES, _summary("hybrid", ce_p50=175.0, primary=150.0, enrich=24.0),
                      "beir/scifact")
    assert report["exit_code"] == 1
    prim = next(c for c in report["checks"] if c["name"] == "primary_docs_s")
    assert prim["status"] == "fail"


def test_at_band_edge_passes():
    # exactly 1.25x is NOT a regression (compare_ratio uses strict greater-than).
    report = evaluate(BASELINES, _summary("hybrid", ce_p50=170.0 * 1.25, primary=290.0, enrich=24.0),
                      "beir/scifact")
    assert report["exit_code"] == 0


def test_missing_pinned_metric_is_data_error():
    # ce_p50 absent from the run, but it is pinned → exit 2 (cannot evaluate).
    report = evaluate(BASELINES, _summary("hybrid", ce_p50=None, primary=290.0, enrich=24.0),
                      "beir/scifact")
    assert report["exit_code"] == 2
    ce = next(c for c in report["checks"] if c["name"] == "ce_p50_ms")
    assert ce["status"] == "fail"
    # the exit-2 detail names the run shape that produces the missing metric (fix #1c)
    assert "--ce" in ce["detail"]


def test_unpinned_dataset_does_not_gate():
    report = evaluate(BASELINES, _ok("full"), "mixed/cord19-qddf")
    assert report["exit_code"] == 0
    assert report["checks"][0]["status"] == "skip"


def test_footprint_is_best_effort_not_data_error():
    # A baseline that pins resident_bytes, but no manifest → footprint SKIPs (best-effort),
    # it must NOT turn the gate into a data error; the other metrics still pass.
    baselines = {
        "baselines": {
            "beir/scifact": {
                "mode": "hybrid",
                "metrics": {"ce_p50_ms": 170.0, "resident_bytes": 2_000_000_000},
                "bands": {"ce_p50_ms": 1.25, "resident_bytes": 1.05},
            },
        },
    }
    report = evaluate(baselines, _summary("hybrid", ce_p50=180.0), "beir/scifact", manifest=None)
    assert report["exit_code"] == 0
    foot = next(c for c in report["checks"] if c["name"] == "resident_bytes")
    assert foot["status"] == "skip"


def test_llm_footprint_included_when_ai_online(tmp_path):
    # Build the minimal models-dir layout derive_resident_model_bytes recognizes (onnx/ + splade/).
    models = tmp_path / "models"
    for d in ("onnx/gte-multilingual-base", "onnx/reranker", "onnx/ner", "splade/naver-splade-v3"):
        (models / d).mkdir(parents=True)
        (models / d / "model.onnx").write_bytes(b"x" * 1000)
    (models / "Qwen.gguf").write_bytes(b"y" * 5_000_000)  # the LLM weights
    base_mf = {"splade_model_path": str(models / "splade" / "naver-splade-v3"), "embed_gpu": False}
    online = {  # AI-online snapshot names the active gguf (tempdoc 640 R1, verified live)
        "model_fingerprints": base_mf,
        "inference_status_snapshot": {"activeModelId": "Qwen.gguf", "hasVisionCapability": False},
    }
    offline = {"model_fingerprints": base_mf, "inference_status_snapshot": {"activeModelId": None}}
    online_bytes = derive_resident_model_bytes(online)
    offline_bytes = derive_resident_model_bytes(offline)
    assert online_bytes is not None and offline_bytes is not None
    # the LLM gguf (5 MB) is added on the AI-online run; the offline run is ONNX-only
    assert online_bytes - offline_bytes == 5_000_000


def test_derive_footprint_returns_none_without_paths():
    assert derive_resident_model_bytes(None) is None
    assert derive_resident_model_bytes({}) is None
    assert derive_resident_model_bytes({"model_fingerprints": {}}) is None
    # bogus path → None (no crash)
    assert derive_resident_model_bytes(
        {"model_fingerprints": {"splade_model_path": "/no/such/splade/x/model.onnx"}}
    ) is None


# --- projection from a green run (the regeneration discipline) --------------

def test_projection_round_trips_to_pass():
    """A baseline projected from a run gates that same run at exit 0 (ratio 1.0)."""
    green = _summary("hybrid", ce_p50=164.0, primary=306.0, enrich=25.0)
    proj = project_run_to_perf_baselines(green, "beir/scifact", "hybrid", src="rel-test")
    assert proj["projected_from_run"] is True
    entry = proj["baselines"]["beir/scifact"]
    assert entry["metrics"]["ce_p50_ms"] == 164.0
    assert "rel-test" in entry["src"]
    report = evaluate(proj, green, "beir/scifact")
    assert report["exit_code"] == 0


def test_projection_omits_unavailable_metrics():
    # A run with only latency → the projected baseline pins only ce_p50_ms.
    green = _summary("hybrid", ce_p50=150.0)
    proj = project_run_to_perf_baselines(green, "beir/scifact", "hybrid")
    assert set(proj["baselines"]["beir/scifact"]["metrics"]) == {"ce_p50_ms"}


# --- tempdoc 640: perf projects from the canonical release (closes the per-run fork) ---

_RELEASE = {
    "release_id": "rel-test",
    "measured": {
        "beir/scifact": {
            "config_mode": "hybrid",
            "metrics": {"nDCG@10": 0.76, "ce_p50_ms": 160.0},  # per-mode: quality + CE latency
            "run_metrics": {"primary_docs_s": 100.0, "enrich_docs_s": 20.0, "resident_bytes": 2_000_000_000},
        },
        "ocr/messy": {"config_mode": "hybrid", "metrics": {"WER": 0.08}},  # no perf family → skipped
    },
}


def test_project_release_extracts_perf_family_and_skips_quality_only():
    proj = project_release_to_perf_baselines(_RELEASE)
    assert proj["projected_from_release"] is True
    b = proj["baselines"]
    assert set(b) == {"beir/scifact"}  # ocr/messy (no perf metrics) is skipped
    m = b["beir/scifact"]["metrics"]
    assert m["ce_p50_ms"] == 160.0          # from the per-mode `metrics` map
    assert m["primary_docs_s"] == 100.0     # from the per-run `run_metrics` map
    assert m["resident_bytes"] == 2_000_000_000.0
    assert "rel-test" in b["beir/scifact"]["src"]


def test_projected_release_floor_gates_a_run():
    proj = project_release_to_perf_baselines(_RELEASE)
    # within band → exit 0 (CE 168 < 160*1.25=200; primary 95 > 100*0.65; footprint skips w/o manifest)
    ok = _summary("hybrid", ce_p50=168.0, primary=95.0, enrich=19.0)
    assert evaluate(proj, ok, "beir/scifact")["exit_code"] == 0
    # CE latency regressed (210 > 200) → exit 1
    bad = _summary("hybrid", ce_p50=210.0, primary=95.0, enrich=19.0)
    assert evaluate(proj, bad, "beir/scifact")["exit_code"] == 1


# --- tempdoc 640 R2: envelope-derived band (data-driven; graceful fallback) ---

def test_envelope_band_is_data_driven_with_graceful_fixed_fallback():
    base = {"baselines": {"beir/scifact": {"mode": "hybrid", "metrics": {"ce_p50_ms": 160.0}}}}
    run = _summary("hybrid", ce_p50=180.0)  # ratio 1.125 vs baseline 160
    # No cohort envelope -> the FIXED band (1.25) applies -> 1.125 <= 1.25 -> pass (unchanged behavior).
    assert evaluate(base, run, "beir/scifact", manifest=None)["exit_code"] == 0
    # Cohort envelope present (CE-stage mean 160, sd 8 -> CV 0.05 -> band 1+2*0.05 = 1.10):
    # 1.125 > 1.10 -> regression. Proves the band adapts to measured noise, tighter than the guess.
    mani = {"non_determinism_envelope": {"metrics": {"hybrid": {
        "ce_p50_ms": {"mean": 160.0, "stdev": 8.0, "n": 5}}}}}
    rep = evaluate(base, run, "beir/scifact", manifest=mani)
    assert rep["exit_code"] == 1
    ce = next(c for c in rep["checks"] if c["name"] == "ce_p50_ms")
    assert ce["status"] == "fail" and "envelope" in ce["detail"]


# --- review fix #2: the file-level default_bands must be honored -------------

def test_file_level_default_bands_are_honored():
    # Entry has NO per-metric `bands`; the file-level `default_bands` must drive the verdict.
    baselines = {
        "default_bands": {"ce_p50_ms": 1.05},
        "baselines": {"x/y": {"mode": "hybrid", "metrics": {"ce_p50_ms": 100.0}}},
    }
    # 110/100 = 1.10 > the file band 1.05 -> regression. Under the module default (1.25) this
    # would pass, so a fail here proves evaluate reads the FILE band, not the hardcoded constant.
    assert evaluate(baselines, _summary("hybrid", ce_p50=110.0), "x/y")["exit_code"] == 1
    # within the file band passes
    assert evaluate(baselines, _summary("hybrid", ce_p50=104.0), "x/y")["exit_code"] == 0


# --- review fix #4: dataset-match guard --------------------------------------

def test_run_dataset_ok_guards_cross_corpus():
    assert run_dataset_ok({"dataset": "scifact"}, "scifact") is True
    assert run_dataset_ok({"dataset": "mixed/enron-qa"}, "scifact") is False
    # None-tolerant: unverifiable -> do not block
    assert run_dataset_ok(None, "scifact") is True
    assert run_dataset_ok({}, "scifact") is True
    # canonical match: a run tagged `scifact` gates against the release's canonical `beir/scifact`
    # (tempdoc 640 live-validation finding — the release-projection path keys on the canonical slug)
    assert run_dataset_ok({"dataset": "scifact"}, "beir/scifact") is True
    assert run_dataset_ok({"dataset": "mixed/enron-qa"}, "beir/scifact") is False
