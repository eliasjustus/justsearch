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

from jseval.utility_calibrate import (
    _normalize_root_path,
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
