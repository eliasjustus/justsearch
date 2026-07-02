"""Tests for the per-run performance-attribution report builder + command (tempdoc 647)."""

from __future__ import annotations

import json

from click.testing import CliRunner

from jseval.commands.analysis import build_perf_report, cmd_perf_report


def _summary(mode: str, stages: dict[str, dict], total_p50: float | None = None) -> dict:
    pm: dict = {"stage_timing_stats": {k: dict(v) for k, v in stages.items()}}
    if total_p50 is not None:
        pm["latency_stats"] = {"p50_ms": total_p50}
    return {"per_mode": {mode: pm}, "modes": [mode]}


def _models(tmp_path, with_llm: bool = True):
    models = tmp_path / "models"
    for d in ("onnx/gte-multilingual-base", "onnx/reranker", "onnx/ner", "splade/naver-splade-v3"):
        (models / d).mkdir(parents=True)
        (models / d / "model.onnx").write_bytes(b"x" * 1_000_000)
    if with_llm:
        (models / "Qwen.gguf").write_bytes(b"y" * 5_000_000)
    return models


def _manifest(models, online: bool = True) -> dict:
    return {
        "model_fingerprints": {
            "splade_model_path": str(models / "splade" / "naver-splade-v3"),
            "ner_model_path": str(models / "onnx" / "ner"),
            "embed_gpu": False,
        },
        "inference_status_snapshot": {"activeModelId": "Qwen.gguf" if online else None},
    }


class TestBuildPerfReport:
    def test_latency_reads_materialized_shares_sorted_by_p50(self):
        s = _summary(
            "hybrid",
            {
                "retrieval_ms": {"p50": 4, "p95": 6, "share": 0.03},
                "cross_encoder_ms": {"p50": 150, "p95": 165, "share": 0.85},
                "unaccounted_ms": {"p50": 20, "p95": 30, "share": 0.12},
            },
            total_p50=176,
        )
        lat = build_perf_report(s, None, "hybrid")["latency"]
        assert lat["total_p50_ms"] == 176
        # stages sorted by p50 descending: CE, unaccounted, retrieval
        assert [x["stage"] for x in lat["stages"]] == [
            "cross_encoder_ms", "unaccounted_ms", "retrieval_ms"]
        # the share is READ from the record verbatim — not recomputed
        assert lat["stages"][0]["share"] == 0.85
        assert lat["stages"][0]["p95_ms"] == 165

    def test_old_format_without_shares_is_graceful(self):
        s = _summary("hybrid", {
            "retrieval_ms": {"p50": 4, "p95": 6}, "cross_encoder_ms": {"p50": 150, "p95": 165}})
        lat = build_perf_report(s, None, "hybrid")["latency"]
        assert lat["stages"][0]["share"] is None  # pre-647 runs materialized no share

    def test_no_latency_when_mode_or_stage_data_absent(self):
        assert build_perf_report({"per_mode": {"hybrid": {}}}, None, "hybrid")["latency"] is None
        s = _summary("hybrid", {"cross_encoder_ms": {"p50": 1}})
        assert build_perf_report(s, None, None)["latency"] is None  # unresolved mode

    def test_footprint_allocation_with_display_shares(self, tmp_path):
        foot = build_perf_report({}, _manifest(_models(tmp_path)), "hybrid")["footprint"]
        assert foot is not None
        assert "llm_bytes" in [c["component"] for c in foot["components"]]  # AI-online
        # display shares close to 1 and total == sum of components (the allocation closes)
        assert abs(sum(c["share"] for c in foot["components"]) - 1.0) < 1e-3
        assert foot["total_bytes"] == sum(c["bytes"] for c in foot["components"])
        # sorted by bytes descending → the LLM (5 MB) leads
        assert foot["components"][0]["component"] == "llm_bytes"

    def test_footprint_omits_llm_when_offline(self, tmp_path):
        foot = build_perf_report({}, _manifest(_models(tmp_path), online=False), "hybrid")["footprint"]
        assert "llm_bytes" not in [c["component"] for c in foot["components"]]

    def test_footprint_none_without_manifest(self):
        assert build_perf_report({}, None, "hybrid")["footprint"] is None


class TestPerfReportCommand:
    """The thin `jseval perf-report` CLI wrapper (tempdoc 647)."""

    def _write_run(self, tmp_path):
        run = tmp_path / "run"
        run.mkdir()
        summary = {
            "modes": ["hybrid"],
            "per_mode": {"hybrid": {
                "stage_timing_stats": {
                    "cross_encoder_ms": {"p50": 150, "p95": 165, "share": 0.85},
                    "retrieval_ms": {"p50": 4, "p95": 6, "share": 0.03},
                    "unaccounted_ms": {"p50": 20, "p95": 30, "share": 0.12},
                },
                "latency_stats": {"p50_ms": 176},
            }},
        }
        (run / "summary.json").write_text(json.dumps(summary), encoding="utf-8")
        return run

    def test_cli_renders_text_and_json(self, tmp_path):
        run = self._write_run(tmp_path)
        text = CliRunner().invoke(cmd_perf_report, [str(run), "--mode", "hybrid"], obj={})
        assert text.exit_code == 0
        assert "stage decomposition" in text.output and "cross_encoder_ms" in text.output
        js = CliRunner().invoke(
            cmd_perf_report, [str(run), "--mode", "hybrid"], obj={"json": True})
        assert js.exit_code == 0
        assert json.loads(js.output)["latency"]["stages"][0]["stage"] == "cross_encoder_ms"

    def test_missing_summary_is_a_friendly_error(self, tmp_path):
        empty = tmp_path / "notarun"
        empty.mkdir()
        r = CliRunner().invoke(cmd_perf_report, [str(empty)], obj={})
        assert r.exit_code != 0 and "no summary.json" in r.output
