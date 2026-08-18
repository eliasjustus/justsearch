"""Tests for jseval.agent_retrieval_eval's compact-model guard (tempdoc 842 §2.5,
review finding N3).

The guard probes the served chat model's identity before a Tier-2 run and
refuses to proceed against a compact/dev-tier model (which systematically
poisons quality baselines) unless the caller explicitly opts in via
``allow_compact_model``. It shipped with zero tests. This file covers:

  - ``COMPACT_MODEL_MARKERS`` case-insensitive substring matching, including
    the tempdoc 842 D1 "qwen3.5-2b" contemplated-rung marker.
  - ``run_tier2_eval`` raises ``CompactModelNotAllowedError`` BEFORE any query
    processing when the served model is compact and not allowed.
  - ``run_tier2_eval`` does not raise, and stamps the aggregate, when the
    caller opts in with ``allow_compact_model=True``.
  - The probe-failure (``served_model_name`` returns ``None``) and standard-
    model paths both degrade open with no raise.
"""

from __future__ import annotations

from unittest.mock import MagicMock

import pytest

from jseval.agent_retrieval_eval import (
    COMPACT_MODEL_MARKERS,
    CompactModelNotAllowedError,
    run_tier2_eval,
)

_LLM_URL = "http://127.0.0.1:8080"
_BASE_URL = "http://127.0.0.1:33221"


def _is_compact_marker_match(served_model: str) -> bool:
    """Mirrors the case-insensitive substring check ``run_tier2_eval`` applies
    against ``COMPACT_MODEL_MARKERS`` (agent_retrieval_eval.py, run_tier2_eval)."""
    return any(m in served_model.lower() for m in COMPACT_MODEL_MARKERS)


class TestCompactModelMarkerMatching:
    """Marker matching is a case-insensitive substring test over all three
    markers, and must not fire on standard (non-compact) served names."""

    @pytest.mark.parametrize("served_model", [
        "Qwen3.5-4B-Q4_K_M.gguf",
        "qwen3-1.7b-anything",
        "QWEN3.5-2B-x",
    ])
    def test_matches_compact_markers_case_insensitively(self, served_model):
        assert _is_compact_marker_match(served_model) is True

    @pytest.mark.parametrize("served_model", [
        "Qwen3.5-9B-Instruct-Q4_K_M.gguf",
        "gemma-2-9b",
    ])
    def test_does_not_match_standard_names(self, served_model):
        assert _is_compact_marker_match(served_model) is False


def _fake_retrieve_context_client(monkeypatch):
    """Stubs the httpx.Client used for the retrieve-context call so
    run_tier2_eval never touches a live backend."""
    resp = MagicMock()
    resp.raise_for_status = MagicMock()
    resp.json.return_value = {
        "context": "some retrieved context",
        "quality": {"chunks_included": 1},
    }
    mock_client = MagicMock()
    mock_client.post.return_value = resp

    def _fake_client(*, base_url, timeout):
        return mock_client

    monkeypatch.setattr("jseval.agent_retrieval_eval.httpx.Client", _fake_client)
    return mock_client


def _fake_llm_post(monkeypatch):
    """Stubs the module-level httpx.post used for the local-LLM chat call so
    run_tier2_eval never touches a live llama-server."""
    llm_resp = MagicMock()
    llm_resp.json.return_value = {
        "choices": [{"message": {
            "content": '{"answer": "42", "evidence_summary": "because", "confidence": "high"}',
        }}],
        "usage": {"completion_tokens": 3},
    }
    fake_post = MagicMock(return_value=llm_resp)
    monkeypatch.setattr("jseval.agent_retrieval_eval.httpx.post", fake_post)
    return fake_post


def _one_query():
    return [{"query": "What is the answer?", "answer": "42", "question_type": "single"}]


def _forbid_network(monkeypatch):
    """Makes any attempt to open an httpx.Client or issue an httpx.post fail
    the test immediately, instead of attempting a real (loopback) connection.
    Used on the raise path to prove -- deterministically, not by timing --
    that the guard raises strictly before Step 1 (retrieve-context) would run."""
    def _fail_client(*, base_url, timeout):
        raise AssertionError("httpx.Client() was constructed -- guard did not "
                              "raise before query processing began")

    def _fail_post(*args, **kwargs):
        raise AssertionError("httpx.post() was called -- guard did not raise "
                              "before query processing began")

    monkeypatch.setattr("jseval.agent_retrieval_eval.httpx.Client", _fail_client)
    monkeypatch.setattr("jseval.agent_retrieval_eval.httpx.post", _fail_post)


class TestRunTier2EvalCompactModelGuardRaises:
    def test_raises_before_any_query_processing_when_not_allowed(self, monkeypatch):
        monkeypatch.setattr(
            "jseval.agent_retrieval_eval.served_model_name",
            lambda llm_url: "Qwen3.5-4B-Q4_K_M.gguf",
        )
        _forbid_network(monkeypatch)

        with pytest.raises(CompactModelNotAllowedError) as exc_info:
            run_tier2_eval(_one_query(), base_url=_BASE_URL, llm_url=_LLM_URL)

        message = str(exc_info.value)
        assert "Qwen3.5-4B-Q4_K_M.gguf" in message
        assert "--allow-compact-model" in message

    def test_default_allow_compact_model_is_false(self, monkeypatch):
        monkeypatch.setattr(
            "jseval.agent_retrieval_eval.served_model_name",
            lambda llm_url: "qwen3-1.7b-instruct",
        )
        _forbid_network(monkeypatch)

        with pytest.raises(CompactModelNotAllowedError):
            run_tier2_eval(_one_query(), base_url=_BASE_URL, llm_url=_LLM_URL)


class TestRunTier2EvalCompactModelGuardAllowed:
    def test_allow_compact_model_true_does_not_raise_and_stamps_aggregate(self, monkeypatch):
        monkeypatch.setattr(
            "jseval.agent_retrieval_eval.served_model_name",
            lambda llm_url: "Qwen3.5-4B-Q4_K_M.gguf",
        )
        _fake_retrieve_context_client(monkeypatch)
        _fake_llm_post(monkeypatch)

        agg = run_tier2_eval(
            _one_query(), base_url=_BASE_URL, llm_url=_LLM_URL,
            allow_compact_model=True,
        )

        assert agg["served_model"] == "Qwen3.5-4B-Q4_K_M.gguf"
        assert agg["compact_model_allowed"] is True


class TestRunTier2EvalServedModelProbeDegradesOpen:
    def test_probe_failure_returning_none_does_not_raise(self, monkeypatch):
        monkeypatch.setattr(
            "jseval.agent_retrieval_eval.served_model_name",
            lambda llm_url: None,
        )
        _fake_retrieve_context_client(monkeypatch)
        _fake_llm_post(monkeypatch)

        agg = run_tier2_eval(_one_query(), base_url=_BASE_URL, llm_url=_LLM_URL)

        assert agg["served_model"] is None
        assert "compact_model_allowed" not in agg

    def test_standard_served_name_does_not_raise_and_has_no_compact_key(self, monkeypatch):
        monkeypatch.setattr(
            "jseval.agent_retrieval_eval.served_model_name",
            lambda llm_url: "Qwen3.5-9B-Instruct-Q4_K_M.gguf",
        )
        _fake_retrieve_context_client(monkeypatch)
        _fake_llm_post(monkeypatch)

        agg = run_tier2_eval(_one_query(), base_url=_BASE_URL, llm_url=_LLM_URL)

        assert agg["served_model"] == "Qwen3.5-9B-Instruct-Q4_K_M.gguf"
        assert "compact_model_allowed" not in agg
