"""Tests for run.py — orchestration (all dependencies mocked)."""

from __future__ import annotations

import os
from unittest.mock import MagicMock, patch

from jseval.run import (
    METRIC_CONTRACT,
    _compute_latency_stats,
    _compute_pipeline_tracking,
    _compute_qrels_summary,
    _compute_score_stats,
    _get_corpus_identity,
    execute_run,
)
from jseval.types import AnnProofResult, ComparabilityResult, QueryRecord, ReadinessResult


def _setup_mocks(
    mock_corpora, mock_readiness, mock_retriever, mock_scoring,
    mock_provenance, mock_ann, mock_comp,
    queries=None, qrels=None,
):
    queries = queries or {
        "q1": QueryRecord(text="test query"),
        "q2": QueryRecord(text="another query"),
    }
    qrels = qrels or {"q1": {"d1": 1}, "q2": {"d2": 1}}
    mock_corpora.load.return_value = (
        queries, qrels,
        MagicMock(name="scifact", doc_count=100, query_count=len(queries)),
    )
    mock_readiness.check_search_ready.return_value = ReadinessResult(passed=True)
    mock_retriever.retrieve.return_value = (
        [MagicMock(query_id="q1", doc_id="d1", score=1.0)],
        [{"query_id": "q1", "tookMs": 15}],
    )
    mock_scoring.evaluate.return_value = {"nDCG@10": 0.731}
    mock_scoring.evaluate_per_query.return_value = {"q1": {"nDCG@10": 0.731}}
    mock_provenance.extract_query_evidence.return_value = {
        "effective_mode": "HYBRID", "error": None,
    }
    mock_provenance.aggregate_run_evidence.return_value = {
        "error_count": 0, "component_status_counts": {},
    }
    mock_ann.compute_ann_proof.return_value = AnnProofResult(status="PASS")
    mock_comp.determine_comparability.return_value = ComparabilityResult(comparable=True)


_MOCK_STACK = [
    "jseval.run.history_mod",
    "jseval.run.artifacts_mod",
    "jseval.run.comparability_mod",
    "jseval.run.ann_proof_mod",
    "jseval.run.provenance",
    "jseval.run.scoring",
    "jseval.run.retriever",
    "jseval.run.readiness",
    "jseval.run.corpora",
]


@patch(*_MOCK_STACK[:1])
@patch(*_MOCK_STACK[1:2])
@patch(*_MOCK_STACK[2:3])
@patch(*_MOCK_STACK[3:4])
@patch(*_MOCK_STACK[4:5])
@patch(*_MOCK_STACK[5:6])
@patch(*_MOCK_STACK[6:7])
@patch(*_MOCK_STACK[7:8])
@patch(*_MOCK_STACK[8:9])
def test_execute_run_basic_flow(
    mock_corpora, mock_readiness, mock_retriever, mock_scoring,
    mock_provenance, mock_ann, mock_comp, mock_artifacts, mock_history,
):
    _setup_mocks(mock_corpora, mock_readiness, mock_retriever, mock_scoring,
                 mock_provenance, mock_ann, mock_comp)

    summary = execute_run("scifact", "http://localhost:8080", ["hybrid"])

    assert summary["dataset"] == "scifact"
    assert "hybrid" in summary["per_mode"]
    assert summary["per_mode"]["hybrid"]["comparable"] is True
    assert "metric_contract" in summary
    assert "qrels_summary" in summary
    assert "corpus_identity" in summary
    assert "pipeline_tracking" in summary["per_mode"]["hybrid"]
    mock_corpora.load.assert_called_once()
    mock_retriever.retrieve.assert_called_once()


@patch(*_MOCK_STACK[:1])
@patch(*_MOCK_STACK[1:2])
@patch(*_MOCK_STACK[2:3])
@patch(*_MOCK_STACK[3:4])
@patch(*_MOCK_STACK[4:5])
@patch(*_MOCK_STACK[5:6])
@patch(*_MOCK_STACK[6:7])
@patch(*_MOCK_STACK[7:8])
@patch(*_MOCK_STACK[8:9])
def test_execute_run_skip_readiness(
    mock_corpora, mock_readiness, mock_retriever, mock_scoring,
    mock_provenance, mock_ann, mock_comp, mock_artifacts, mock_history,
):
    _setup_mocks(mock_corpora, mock_readiness, mock_retriever, mock_scoring,
                 mock_provenance, mock_ann, mock_comp)

    execute_run("scifact", "http://localhost:8080", ["lexical"], skip_readiness=True)
    mock_readiness.check_search_ready.assert_not_called()


@patch(*_MOCK_STACK[:1])
@patch(*_MOCK_STACK[1:2])
@patch(*_MOCK_STACK[2:3])
@patch(*_MOCK_STACK[3:4])
@patch(*_MOCK_STACK[4:5])
@patch(*_MOCK_STACK[5:6])
@patch(*_MOCK_STACK[6:7])
@patch(*_MOCK_STACK[7:8])
@patch(*_MOCK_STACK[8:9])
def test_execute_run_max_queries(
    mock_corpora, mock_readiness, mock_retriever, mock_scoring,
    mock_provenance, mock_ann, mock_comp, mock_artifacts, mock_history,
):
    # 50 queries in dataset, cap at 5
    queries = {f"q{i}": QueryRecord(text=f"query {i}") for i in range(50)}
    qrels = {f"q{i}": {f"d{i}": 1} for i in range(50)}
    _setup_mocks(mock_corpora, mock_readiness, mock_retriever, mock_scoring,
                 mock_provenance, mock_ann, mock_comp, queries=queries, qrels=qrels)

    execute_run("scifact", "http://localhost:8080", ["lexical"], max_queries=5)

    # Retriever should receive only 5 queries
    call_args = mock_retriever.retrieve.call_args
    passed_queries = call_args[0][0]
    assert len(passed_queries) == 5


@patch(*_MOCK_STACK[:1])
@patch(*_MOCK_STACK[1:2])
@patch(*_MOCK_STACK[2:3])
@patch(*_MOCK_STACK[3:4])
@patch(*_MOCK_STACK[4:5])
@patch(*_MOCK_STACK[5:6])
@patch(*_MOCK_STACK[6:7])
@patch(*_MOCK_STACK[7:8])
@patch(*_MOCK_STACK[8:9])
def test_execute_run_qrels_filter(
    mock_corpora, mock_readiness, mock_retriever, mock_scoring,
    mock_provenance, mock_ann, mock_comp, mock_artifacts, mock_history,
):
    # Queries has keys not in qrels — should be filtered
    queries = {
        "q1": QueryRecord(text="query 1"),
        "q2": QueryRecord(text="query 2"),
        "q_no_qrels": QueryRecord(text="no judgments"),
    }
    qrels = {"q1": {"d1": 1}, "q2": {"d2": 1}}
    _setup_mocks(mock_corpora, mock_readiness, mock_retriever, mock_scoring,
                 mock_provenance, mock_ann, mock_comp, queries=queries, qrels=qrels)

    execute_run("scifact", "http://localhost:8080", ["lexical"])

    call_args = mock_retriever.retrieve.call_args
    passed_queries = call_args[0][0]
    assert "q_no_qrels" not in passed_queries
    assert len(passed_queries) == 2


# ---------------------------------------------------------------------------
# Unit tests for helper functions
# ---------------------------------------------------------------------------

class TestComputeQrelsSummary:
    def test_binary(self):
        qrels = {"q1": {"d1": 1, "d2": 0}, "q2": {"d3": 1}}
        s = _compute_qrels_summary(qrels)
        assert s["relevance_mode"] == "binary"
        assert s["query_count"] == 2
        assert s["max_relevance"] == 1
        assert s["queries_with_relevant"] == 2

    def test_graded(self):
        qrels = {"q1": {"d1": 2, "d2": 1}, "q2": {"d3": 0}}
        s = _compute_qrels_summary(qrels)
        assert s["relevance_mode"] == "graded"
        assert s["max_relevance"] == 2
        assert s["queries_with_relevant"] == 1  # q2 has no relevant docs

    def test_empty(self):
        s = _compute_qrels_summary({})
        assert s["query_count"] == 0
        assert s["queries_with_relevant"] == 0


class TestComputePipelineTracking:
    def test_lexical_all_observed(self):
        # BM25 (sparse) is implicit — no components expected or observed
        evidence = {"component_status_counts": {}}
        pt = _compute_pipeline_tracking("lexical", evidence)
        assert pt["requested"] == []
        assert pt["mismatch_reasons"] == []

    def test_hybrid_dense_missing(self):
        # hybrid expects dense; sparse is implicit and not tracked
        evidence = {"component_status_counts": {}}
        pt = _compute_pipeline_tracking("hybrid", evidence)
        assert "requested_dense_but_not_observed" in pt["mismatch_reasons"]

    def test_unexpected_component(self):
        evidence = {"component_status_counts": {
            "splade": {"executed": 10},
        }}
        pt = _compute_pipeline_tracking("lexical", evidence)
        assert "unexpected_splade_observed" in pt["mismatch_reasons"]

    def test_empty_evidence(self):
        pt = _compute_pipeline_tracking("hybrid", {"component_status_counts": {}})
        assert len(pt["mismatch_reasons"]) == 1  # only dense missing


class TestGetCorpusIdentity:
    def test_reads_env_vars(self):
        with patch.dict(os.environ, {
            "JUSTSEARCH_CORPUS_PROFILE_ID": "stub-jaccard",
            "JUSTSEARCH_CORPUS_SIGNATURE": "abc123",
        }):
            ci = _get_corpus_identity()
            assert ci["profile_id"] == "stub-jaccard"
            assert ci["signature"] == "abc123"

    def test_missing_env_vars(self):
        with patch.dict(os.environ, {}, clear=True):
            ci = _get_corpus_identity()
            assert ci["profile_id"] is None
            assert ci["signature"] is None

    def test_computes_mixed_signature_from_files(self, tmp_path):
        # tempdoc 623 ③: a mixed corpus is pinned by sha256(corpus.jsonl + qrels/test.tsv).
        from types import SimpleNamespace
        ds = "mixed/demo-200"
        ddir = tmp_path / ds
        (ddir / "qrels").mkdir(parents=True)
        (ddir / "corpus.jsonl").write_text('{"_id":"d1","text":"hello"}\n', encoding="utf-8")
        (ddir / "qrels" / "test.tsv").write_text("q1\td1\t1\n", encoding="utf-8")
        meta = SimpleNamespace(source="mixed", name=ds)
        with patch.dict(os.environ, {}, clear=True):
            sig = _get_corpus_identity(ds, meta, {}, base_dir=tmp_path)["signature"]
        assert isinstance(sig, str) and len(sig) == 64
        # deterministic: same inputs → same signature
        with patch.dict(os.environ, {}, clear=True):
            sig2 = _get_corpus_identity(ds, meta, {}, base_dir=tmp_path)["signature"]
        assert sig == sig2
        # content change → different signature
        (ddir / "corpus.jsonl").write_text('{"_id":"d1","text":"CHANGED"}\n', encoding="utf-8")
        with patch.dict(os.environ, {}, clear=True):
            assert _get_corpus_identity(ds, meta, {}, base_dir=tmp_path)["signature"] != sig

    def test_computes_beir_signature_from_id_and_qrels(self):
        # tempdoc 623 ③: BEIR is pinned by sha256({ir_datasets_id, qrels}) — .txt files
        # materialize only after ingest, so hash the stable id + binary qrels.
        from types import SimpleNamespace
        meta = SimpleNamespace(source="beir", name="scifact")
        with patch.dict(os.environ, {}, clear=True):
            sig = _get_corpus_identity("scifact", meta, {"q1": {"d1": 1}})["signature"]
        assert isinstance(sig, str) and len(sig) == 64
        # different qrels → different signature
        with patch.dict(os.environ, {}, clear=True):
            assert _get_corpus_identity("scifact", meta, {"q1": {"d2": 1}})["signature"] != sig

    def test_env_signature_overrides_computed(self):
        from types import SimpleNamespace
        meta = SimpleNamespace(source="beir", name="scifact")
        with patch.dict(os.environ, {"JUSTSEARCH_CORPUS_SIGNATURE": "operator-pin"}):
            assert _get_corpus_identity("scifact", meta, {"q1": {"d1": 1}})["signature"] == "operator-pin"


class TestComputeLatencyStats:
    def test_basic(self):
        responses = [
            {"query_id": "q1", "tookMs": 10},
            {"query_id": "q2", "tookMs": 20},
            {"query_id": "q3", "tookMs": 30},
            {"query_id": "q4", "tookMs": 40},
            {"query_id": "q5", "tookMs": 50},
        ]
        s = _compute_latency_stats(responses)
        assert s["query_count"] == 5
        assert s["mean_ms"] == 30.0
        assert s["p50_ms"] == 30
        assert s["min_ms"] == 10
        assert s["max_ms"] == 50

    def test_skips_errors(self):
        responses = [
            {"query_id": "q1", "tookMs": 10},
            {"query_id": "q2", "error": "failed"},
            {"query_id": "q3", "tookMs": 30},
        ]
        s = _compute_latency_stats(responses)
        assert s["query_count"] == 2
        assert s["mean_ms"] == 20.0

    def test_empty(self):
        s = _compute_latency_stats([])
        assert s["query_count"] == 0

    def test_single_query(self):
        s = _compute_latency_stats([{"query_id": "q1", "tookMs": 42}])
        assert s["query_count"] == 1
        assert s["p50_ms"] == 42
        assert s["p95_ms"] == 42
        assert s["p99_ms"] == 42


class TestComputeScoreStats:
    def test_basic(self):
        responses = [
            {"query_id": "q1", "results": [
                {"score": 0.9}, {"score": 0.5}, {"score": 0.3},
            ]},
            {"query_id": "q2", "results": [
                {"score": 0.7}, {"score": 0.4},
            ]},
        ]
        s = _compute_score_stats(responses, top_k=10)
        assert s["mean_top1_score"] == round((0.9 + 0.7) / 2, 4)
        assert s["mean_topk_score"] is not None

    def test_skips_errors(self):
        responses = [
            {"query_id": "q1", "results": [{"score": 0.8}]},
            {"query_id": "q2", "error": "failed"},
        ]
        s = _compute_score_stats(responses, top_k=10)
        assert s["mean_top1_score"] == 0.8

    def test_empty(self):
        s = _compute_score_stats([], top_k=10)
        assert s["mean_top1_score"] is None

    def test_respects_top_k(self):
        responses = [
            {"query_id": "q1", "results": [
                {"score": 1.0}, {"score": 0.8}, {"score": 0.6}, {"score": 0.1},
            ]},
        ]
        s = _compute_score_stats(responses, top_k=2)
        assert s["mean_topk_score"] == round((1.0 + 0.8) / 2, 4)


class TestMetricContract:
    def test_static_values(self):
        assert METRIC_CONTRACT["gain_function"] == "linear"
        assert METRIC_CONTRACT["unjudged_policy"] == "not_relevant"
