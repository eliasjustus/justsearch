"""Tests for the watched-roots scope check (tempdoc 624 As-built #7 hardening).

Watched roots are accretive and persistent (RootLifecycleOps.addWatchedRoot,
WatchedRootsState — modules/app-services/.../worker/): a broader/stale root added at
any point in a dataDir's life keeps indexing everything beneath it, including sibling
gold-answer-key files, invisibly. `check_watched_roots_scoped` (and `check_readiness`,
which now composes it) must fail loudly with the stray root's path visible whenever the
live `/api/indexing/roots` response reports anything other than exactly the corpus's own
`corpus_dir`.
"""

from __future__ import annotations

from unittest.mock import MagicMock, patch

import pytest

from jseval.utility_calibrate import (
    StrayWatchedRootError,
    _normalize_root_path,
    assert_watched_roots_scoped,
    base_url_from_mcp_config,
    check_watched_roots_scoped,
)


def _mock_roots_response(paths):
    resp = MagicMock()
    resp.json.return_value = {
        "roots": [{"collection": "default", "path": p, "fileCount": 3} for p in paths]
    }
    resp.raise_for_status = MagicMock()
    return resp


@patch("jseval.utility_calibrate.httpx.Client")
def test_stray_broader_root_fails_with_path_in_message(MockClient):
    mock_client = MagicMock()
    MockClient.return_value.__enter__ = MagicMock(return_value=mock_client)
    MockClient.return_value.__exit__ = MagicMock(return_value=False)

    corpus_dir = r"F:\eval\golden\synth-scan-v1\corpus-dir"
    stray_parent = r"F:\eval\golden\synth-scan-v1"  # the corpus's own PARENT — the leak shape
    mock_client.get.return_value = _mock_roots_response([stray_parent])

    result = check_watched_roots_scoped("http://localhost:8080", corpus_dir)

    assert result.passed is False
    assert "stray_watched_root" in result.failure_reasons
    # The stray root's actual path must be visible in the error so an operator
    # knows what to remove.
    assert any(stray_parent in reason for reason in result.failure_reasons)


@patch("jseval.utility_calibrate.httpx.Client")
def test_exactly_scoped_root_passes(MockClient):
    mock_client = MagicMock()
    MockClient.return_value.__enter__ = MagicMock(return_value=mock_client)
    MockClient.return_value.__exit__ = MagicMock(return_value=False)

    corpus_dir = r"F:\eval\golden\battlefield-en-v1\corpus-dir"
    mock_client.get.return_value = _mock_roots_response([corpus_dir])

    result = check_watched_roots_scoped("http://localhost:8080", corpus_dir)

    assert result.passed is True
    assert result.failure_reasons == []


@patch("jseval.utility_calibrate.httpx.Client")
def test_multiple_equivalent_roots_do_not_false_positive(MockClient):
    # Same directory reported with different separator/case/trailing-slash — must
    # normalize to the same path and NOT be treated as strays.
    mock_client = MagicMock()
    MockClient.return_value.__enter__ = MagicMock(return_value=mock_client)
    MockClient.return_value.__exit__ = MagicMock(return_value=False)

    corpus_dir = r"F:\eval\golden\battlefield-en-v1\corpus-dir"
    variants = [
        corpus_dir,
        corpus_dir.lower(),
        corpus_dir + "\\",
        corpus_dir.replace("\\", "/"),
    ]
    mock_client.get.return_value = _mock_roots_response(variants)

    result = check_watched_roots_scoped("http://localhost:8080", corpus_dir)

    assert result.passed is True
    assert result.failure_reasons == []


@patch("jseval.utility_calibrate.httpx.Client")
def test_multiple_stray_roots_all_listed(MockClient):
    mock_client = MagicMock()
    MockClient.return_value.__enter__ = MagicMock(return_value=mock_client)
    MockClient.return_value.__exit__ = MagicMock(return_value=False)

    corpus_dir = r"F:\eval\golden\synth-scan-v1\corpus-dir"
    stray_a = r"F:\eval\golden\synth-scan-v1"
    stray_b = r"F:\eval\other-corpus"
    mock_client.get.return_value = _mock_roots_response([corpus_dir, stray_a, stray_b])

    result = check_watched_roots_scoped("http://localhost:8080", corpus_dir)

    assert result.passed is False
    combined = " ".join(result.failure_reasons)
    assert stray_a in combined
    assert stray_b in combined


@patch("jseval.utility_calibrate.httpx.Client")
def test_empty_roots_response_passes(MockClient):
    mock_client = MagicMock()
    MockClient.return_value.__enter__ = MagicMock(return_value=mock_client)
    MockClient.return_value.__exit__ = MagicMock(return_value=False)

    mock_client.get.return_value = _mock_roots_response([])

    result = check_watched_roots_scoped(
        "http://localhost:8080", r"F:\eval\golden\battlefield-en-v1\corpus-dir")

    assert result.passed is True


@patch("jseval.utility_calibrate.httpx.Client")
def test_endpoint_unreachable_fails_loudly(MockClient):
    import httpx as real_httpx

    mock_client = MagicMock()
    MockClient.return_value.__enter__ = MagicMock(return_value=mock_client)
    MockClient.return_value.__exit__ = MagicMock(return_value=False)
    mock_client.get.side_effect = real_httpx.ConnectError("connection refused")

    result = check_watched_roots_scoped(
        "http://localhost:8080", r"F:\eval\golden\battlefield-en-v1\corpus-dir")

    assert result.passed is False
    assert any("watched_roots_endpoint_unreachable" in r for r in result.failure_reasons)


class TestNormalizeRootPath:
    def test_case_and_separator_insensitive(self):
        a = _normalize_root_path(r"F:\Eval\Golden\corpus-dir")
        b = _normalize_root_path("f:/eval/golden/corpus-dir/")
        assert a == b

    def test_different_dirs_differ(self):
        a = _normalize_root_path(r"F:\Eval\Golden\corpus-dir")
        b = _normalize_root_path(r"F:\Eval\Golden")
        assert a != b


# --- assert_watched_roots_scoped: the eval-executing (raise, not warn) call site ---


@patch("jseval.utility_calibrate.httpx.Client")
def test_assert_watched_roots_scoped_raises_on_stray_root(MockClient):
    mock_client = MagicMock()
    MockClient.return_value.__enter__ = MagicMock(return_value=mock_client)
    MockClient.return_value.__exit__ = MagicMock(return_value=False)

    corpus_dir = r"F:\eval\golden\synth-scan-v1\corpus-dir"
    stray_parent = r"F:\eval\golden\synth-scan-v1"
    mock_client.get.return_value = _mock_roots_response([stray_parent])

    with pytest.raises(StrayWatchedRootError) as exc_info:
        assert_watched_roots_scoped("http://localhost:8080", corpus_dir)
    # the offending path must be visible in the raised message, not just logged
    assert stray_parent in str(exc_info.value)


@patch("jseval.utility_calibrate.httpx.Client")
def test_assert_watched_roots_scoped_passes_silently_when_scoped(MockClient):
    mock_client = MagicMock()
    MockClient.return_value.__enter__ = MagicMock(return_value=mock_client)
    MockClient.return_value.__exit__ = MagicMock(return_value=False)

    corpus_dir = r"F:\eval\golden\battlefield-en-v1\corpus-dir"
    mock_client.get.return_value = _mock_roots_response([corpus_dir])

    assert_watched_roots_scoped("http://localhost:8080", corpus_dir)  # must not raise


@patch("jseval.utility_calibrate.httpx.Client")
def test_assert_watched_roots_scoped_raises_on_unreachable_endpoint(MockClient):
    import httpx as real_httpx

    mock_client = MagicMock()
    MockClient.return_value.__enter__ = MagicMock(return_value=mock_client)
    MockClient.return_value.__exit__ = MagicMock(return_value=False)
    mock_client.get.side_effect = real_httpx.ConnectError("connection refused")

    with pytest.raises(StrayWatchedRootError) as exc_info:
        assert_watched_roots_scoped(
            "http://localhost:8080", r"F:\eval\golden\battlefield-en-v1\corpus-dir")
    assert "watched_roots_endpoint_unreachable" in str(exc_info.value)


# --- base_url_from_mcp_config: reuse the eval's existing --mcp-config plumbing ---


class TestBaseUrlFromMcpConfig:
    def test_extracts_and_strips_mcp_suffix(self, tmp_path):
        cfg = tmp_path / "mcp.json"
        cfg.write_text(
            '{"mcpServers":{"justsearch":{"url":"http://127.0.0.1:56300/mcp"}}}',
            encoding="utf-8")
        assert base_url_from_mcp_config(str(cfg)) == "http://127.0.0.1:56300"

    def test_returns_none_for_empty_mcp_servers(self, tmp_path):
        # The condition-A empty config carries no justsearch server at all.
        cfg = tmp_path / "mcp.json"
        cfg.write_text('{"mcpServers":{}}', encoding="utf-8")
        assert base_url_from_mcp_config(str(cfg)) is None

    def test_returns_none_for_missing_file(self, tmp_path):
        assert base_url_from_mcp_config(str(tmp_path / "does-not-exist.json")) is None

    def test_returns_none_for_malformed_json(self, tmp_path):
        cfg = tmp_path / "mcp.json"
        cfg.write_text("not json", encoding="utf-8")
        assert base_url_from_mcp_config(str(cfg)) is None


# --- cmd_utility_calibrate CLI wiring: exit non-zero on failed readiness ---


class TestUtilityCalibrateCliExitCode:
    def _invoke(self, tmp_path, monkeypatch, fake_calib):
        from click.testing import CliRunner

        from jseval import utility_calibrate as ucal
        from jseval.cli import main

        queries = tmp_path / "queries.json"
        queries.write_text("[]", encoding="utf-8")
        corpus_dir = tmp_path / "corpus"
        corpus_dir.mkdir()
        output = tmp_path / "calibration.json"

        monkeypatch.setattr(ucal, "calibrate", lambda **kwargs: fake_calib)

        runner = CliRunner()
        result = runner.invoke(main, [
            "utility-calibrate",
            "--queries", str(queries),
            "--corpus-dir", str(corpus_dir),
            "--base-url", "http://127.0.0.1:1",
            "--output", str(output),
        ])
        return result, output

    def test_exits_nonzero_when_readiness_failed(self, tmp_path, monkeypatch):
        fake_calib = {
            "readiness_passed": False,
            "readiness_reasons": ["stray_watched_root", "some stray root message"],
            "config_cohort_key": "k",
            "timeout_s": 120,
            "concurrency": 8,
            "retained_query_indices": [],
            "n_dropped_contaminated": 0,
            "n_cells": 0,
            "cost_estimate_usd": 0.0,
            "time_estimate_min": 0.0,
        }
        result, output = self._invoke(tmp_path, monkeypatch, fake_calib)

        assert result.exit_code == 1
        assert "READINESS FAILED" in result.output
        # the calibration is still written out for post-mortem inspection --
        # the exit code, not a missing file, is what signals "don't trust this."
        assert output.exists()

    def test_exits_zero_when_readiness_passed(self, tmp_path, monkeypatch):
        fake_calib = {
            "readiness_passed": True,
            "readiness_reasons": [],
            "config_cohort_key": "k",
            "timeout_s": 120,
            "concurrency": 8,
            "retained_query_indices": [0, 1],
            "n_dropped_contaminated": 0,
            "n_cells": 2,
            "cost_estimate_usd": 0.1,
            "time_estimate_min": 1.0,
        }
        result, output = self._invoke(tmp_path, monkeypatch, fake_calib)

        assert result.exit_code == 0
        assert "READINESS FAILED" not in result.output
        assert output.exists()
