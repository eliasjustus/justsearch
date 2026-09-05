"""Focused coverage for Tier-2 context-truncation measurement."""

from __future__ import annotations

from unittest.mock import MagicMock

from jseval.agent_retrieval_eval import Tier2Result, _aggregate_tier2, run_tier2_eval


def _result(
    question_type: str,
    *,
    truncated: bool,
    answer_in_context: bool = False,
    error: str = "",
) -> Tier2Result:
    return Tier2Result(
        query="q",
        answer="42",
        question_type=question_type,
        answer_in_context=answer_in_context,
        context_truncated=truncated,
        error=error,
    )


def test_aggregate_tier2_reports_context_truncated_rate_over_valid_results():
    aggregate = _aggregate_tier2([
        _result("comparison", truncated=True, answer_in_context=True),
        _result("comparison", truncated=False),
        _result("temporal", truncated=True, answer_in_context=True),
        _result("temporal", truncated=True, answer_in_context=False, error="llm failed"),
    ])

    assert aggregate["context_truncated_rate"] == 0.6667
    assert aggregate["answer_in_context_rate"] == 0.6667
    assert aggregate["by_type"]["comparison"]["context_truncated_rate"] == 0.5
    assert aggregate["by_type"]["comparison"]["answer_in_context_rate"] == 0.5
    assert aggregate["by_type"]["temporal"]["context_truncated_rate"] == 1.0
    assert aggregate["by_type"]["temporal"]["answer_in_context_rate"] == 1.0


def test_run_tier2_eval_captures_retrieve_context_truncated_flag(monkeypatch):
    monkeypatch.setattr(
        "jseval.agent_retrieval_eval.served_model_name",
        lambda _llm_url: "Qwen3.5-9B-Instruct-Q4_K_M.gguf",
    )

    retrieve_response = MagicMock()
    retrieve_response.raise_for_status = MagicMock()
    retrieve_response.json.return_value = {
        "context": "some retrieved context containing 42",
        "quality": {"chunks_included": 1, "truncated": True},
    }
    retrieve_client = MagicMock()
    retrieve_client.post.return_value = retrieve_response
    monkeypatch.setattr(
        "jseval.agent_retrieval_eval.httpx.Client",
        lambda *, base_url, timeout: retrieve_client,
    )

    llm_response = MagicMock()
    llm_response.json.return_value = {
        "choices": [{"message": {"content": (
            '{"answer": "42", "evidence_summary": "because", "confidence": "high"}'
        )}}],
        "usage": {"completion_tokens": 3},
    }
    monkeypatch.setattr(
        "jseval.agent_retrieval_eval.httpx.post",
        MagicMock(return_value=llm_response),
    )

    aggregate = run_tier2_eval([
        {"query": "What is the answer?", "answer": "42", "question_type": "single"},
    ])

    assert aggregate["results"][0]["context_truncated"] is True
    assert aggregate["results"][0]["answer_in_context"] is True
    assert aggregate["answer_in_context_rate"] == 1.0
    assert aggregate["context_truncated_rate"] == 1.0
    assert aggregate["by_type"]["single"]["context_truncated_rate"] == 1.0
