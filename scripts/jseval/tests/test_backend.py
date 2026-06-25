"""Tests for backend.py — backend lifecycle management."""

from __future__ import annotations

import time
from pathlib import Path
from unittest.mock import MagicMock, patch

import httpx
import pytest

from jseval._paths import REPO_ROOT
from jseval.backend import _wait_for_inference, start_backend, stop_backend


class TestRepoRoot:
    def test_returns_path(self):
        assert REPO_ROOT.is_dir()
        # Should contain gradlew.bat (or gradlew on non-Windows)
        assert (REPO_ROOT / "gradlew.bat").is_file() or (REPO_ROOT / "gradlew").is_file()


class TestStopBackend:
    def test_already_exited(self):
        proc = MagicMock()
        proc.poll.return_value = 0
        proc.returncode = 0
        stop_backend(proc)
        # Should not call taskkill or terminate

    @patch("jseval.backend.os.name", "nt")
    @patch("jseval.backend.subprocess.run")
    def test_windows_taskkill(self, mock_run):
        proc = MagicMock()
        proc.poll.return_value = None
        proc.pid = 12345
        stop_backend(proc)
        mock_run.assert_called_once()
        args = mock_run.call_args[0][0]
        assert "taskkill" in args
        assert "/T" in args
        assert "/F" in args
        assert "12345" in args

    @patch("jseval.backend.os.name", "posix")
    def test_posix_terminate(self):
        proc = MagicMock()
        proc.poll.return_value = None
        proc.pid = 12345
        proc.wait.return_value = 0
        stop_backend(proc)
        proc.terminate.assert_called_once()


class TestWaitForInference:
    """Tests for _wait_for_inference (369)."""

    def _make_proc(self, alive: bool = True):
        proc = MagicMock()
        proc.poll.return_value = None if alive else 1
        proc.returncode = 1
        return proc

    @patch("jseval.backend.httpx.Client")
    @patch("jseval.backend._HEALTH_POLL_SEC", 0.01)
    def test_returns_none_on_online(self, mock_client_cls):
        """Success case: inference is online immediately."""
        mock_resp = MagicMock()
        mock_resp.json.return_value = {"mode": "online"}
        mock_client = MagicMock()
        mock_client.__enter__ = MagicMock(return_value=mock_client)
        mock_client.__exit__ = MagicMock(return_value=False)
        mock_client.get.return_value = mock_resp
        mock_client_cls.return_value = mock_client

        result = _wait_for_inference("http://localhost:33221", time.monotonic() + 5, self._make_proc())
        assert result is None  # success

    @patch("jseval.backend.httpx.Client")
    @patch("jseval.backend._HEALTH_POLL_SEC", 0.01)
    def test_transitions_then_online(self, mock_client_cls):
        """Inference transitions from transitioning to online."""
        responses = [
            {"mode": "transitioning"},
            {"mode": "transitioning"},
            {"mode": "online"},
        ]
        call_count = 0

        mock_client = MagicMock()
        mock_client.__enter__ = MagicMock(return_value=mock_client)
        mock_client.__exit__ = MagicMock(return_value=False)

        def get_side_effect(url):
            nonlocal call_count
            resp = MagicMock()
            resp.json.return_value = responses[min(call_count, len(responses) - 1)]
            call_count += 1
            return resp

        mock_client.get.side_effect = get_side_effect
        mock_client_cls.return_value = mock_client

        result = _wait_for_inference("http://localhost:33221", time.monotonic() + 5, self._make_proc())
        assert result is None
        assert call_count >= 3

    @patch("jseval.backend.httpx.Client")
    @patch("jseval.backend._HEALTH_POLL_SEC", 0.01)
    def test_offline_timeout_gives_diagnostic(self, mock_client_cls):
        """Stays offline — returns diagnostic mentioning common causes."""
        mock_resp = MagicMock()
        mock_resp.json.return_value = {"mode": "offline"}
        mock_client = MagicMock()
        mock_client.__enter__ = MagicMock(return_value=mock_client)
        mock_client.__exit__ = MagicMock(return_value=False)
        mock_client.get.return_value = mock_resp
        mock_client_cls.return_value = mock_client

        # Deadline already passed
        result = _wait_for_inference("http://localhost:33221", time.monotonic() + 0.05, self._make_proc())
        assert result is not None
        assert "autostart may have failed" in result
        assert "JUSTSEARCH_SERVER_EXE" in result

    @patch("jseval.backend.httpx.Client")
    @patch("jseval.backend._HEALTH_POLL_SEC", 0.01)
    def test_transitioning_timeout_gives_diagnostic(self, mock_client_cls):
        """Stuck transitioning — returns diagnostic about model load."""
        mock_resp = MagicMock()
        mock_resp.json.return_value = {"mode": "transitioning"}
        mock_client = MagicMock()
        mock_client.__enter__ = MagicMock(return_value=mock_client)
        mock_client.__exit__ = MagicMock(return_value=False)
        mock_client.get.return_value = mock_resp
        mock_client_cls.return_value = mock_client

        result = _wait_for_inference("http://localhost:33221", time.monotonic() + 0.05, self._make_proc())
        assert result is not None
        assert "model load exceeded timeout" in result

    @patch("jseval.backend._HEALTH_POLL_SEC", 0.01)
    def test_process_exit_gives_diagnostic(self):
        """Backend process dies — returns diagnostic with exit code."""
        proc = self._make_proc(alive=False)
        result = _wait_for_inference("http://localhost:33221", time.monotonic() + 5, proc)
        assert result is not None
        assert "process exited" in result
        assert "rc=1" in result


class TestStartBackendDataDirResolution:
    """Regression: start_backend must honor JUSTSEARCH_DATA_DIR from env.

    Without this, Phase-3 artifacts.write_run's telemetry mirror reads
    from the caller's expected data_dir while the backend writes to a
    different default, producing "no-encoder-spans" LR4-g / empty LR4-f
    projections on live integration smokes.
    """

    @patch("jseval.backend.subprocess.Popen")
    @patch("jseval.backend._wait_for_health", return_value=True)
    def test_env_data_dir_overrides_default(self, _health, mock_popen, tmp_path, monkeypatch):
        target = tmp_path / "cohort-data"
        target.mkdir()
        monkeypatch.setenv("JUSTSEARCH_DATA_DIR", str(target))

        mock_proc = MagicMock()
        mock_popen.return_value = mock_proc

        info = start_backend()
        # Resolved data_dir should be the env-supplied path, NOT the
        # tmp/headless-eval-data fallback.
        assert info.data_dir == target
        # Popen invocation received the env var pointed at the target.
        env = mock_popen.call_args.kwargs["env"]
        assert env["JUSTSEARCH_DATA_DIR"] == str(target)

    @patch("jseval.backend.subprocess.Popen")
    @patch("jseval.backend._wait_for_health", return_value=True)
    def test_explicit_arg_overrides_env(self, _health, mock_popen, tmp_path, monkeypatch):
        env_path = tmp_path / "from-env"
        arg_path = tmp_path / "from-arg"
        env_path.mkdir()
        arg_path.mkdir()
        monkeypatch.setenv("JUSTSEARCH_DATA_DIR", str(env_path))

        mock_proc = MagicMock()
        mock_popen.return_value = mock_proc

        info = start_backend(data_dir=arg_path)
        # Explicit arg still wins when both are present.
        assert info.data_dir == arg_path

    @patch("jseval.backend.subprocess.Popen")
    @patch("jseval.backend._wait_for_health", return_value=True)
    def test_falls_back_to_default_when_neither_set(self, _health, mock_popen,
                                                     tmp_path, monkeypatch):
        monkeypatch.delenv("JUSTSEARCH_DATA_DIR", raising=False)
        mock_proc = MagicMock()
        mock_popen.return_value = mock_proc

        info = start_backend()
        # Default path under REPO_ROOT/tmp/headless-eval-data.
        assert info.data_dir.name == "headless-eval-data"

    @patch("jseval.backend.subprocess.Popen")
    @patch("jseval.backend._wait_for_health", return_value=True)
    def test_relative_env_path_resolves_against_repo_root(
        self, _health, mock_popen, monkeypatch,
    ):
        """Tempdoc 400 §23.8 D-2 regression.

        A relative ``JUSTSEARCH_DATA_DIR`` is ambiguous — Python resolves
        against its own CWD, Java resolves against Gradle's CWD
        (REPO_ROOT). Historically the two frames diverged when calibrate
        spawned sub-runs from ``scripts/jseval``: Python's rmtree hit
        ``scripts/jseval/tmp/...`` while Java wrote to
        ``REPO_ROOT/tmp/...``, so ``--clean`` never wiped the real index
        and run 2 stalled on ``indexed_doc_count_below_floor``. Fix: the
        Python side resolves relative paths against REPO_ROOT, matching
        Java's frame at the one boundary where disagreement mattered.
        """
        monkeypatch.setenv("JUSTSEARCH_DATA_DIR", "tmp/sub/cohort-data")
        # Simulate the calibrate-spawned subprocess where Python's CWD
        # is NOT REPO_ROOT (the historical trigger for the divergence).
        import os as _os
        original_cwd = _os.getcwd()
        try:
            _os.chdir(str(REPO_ROOT / "scripts" / "jseval"))
            mock_proc = MagicMock()
            mock_popen.return_value = mock_proc

            info = start_backend()
            # Must resolve against REPO_ROOT, not scripts/jseval.
            expected = (REPO_ROOT / "tmp" / "sub" / "cohort-data").resolve()
            assert info.data_dir == expected
            # The Popen env matches the Python-side resolved path, so a
            # Java subprocess reading JUSTSEARCH_DATA_DIR with cwd=REPO_ROOT
            # would land on the same absolute directory.
            env = mock_popen.call_args.kwargs["env"]
            assert env["JUSTSEARCH_DATA_DIR"] == str(expected)
        finally:
            _os.chdir(original_cwd)

    @patch("jseval.backend.subprocess.Popen")
    @patch("jseval.backend._wait_for_health", return_value=True)
    def test_absolute_env_path_is_not_rewritten(
        self, _health, mock_popen, tmp_path, monkeypatch,
    ):
        """Absolute paths must pass through untouched — the REPO_ROOT
        rewrite only applies to the relative case."""
        absolute = tmp_path / "absolute-data"
        absolute.mkdir()
        monkeypatch.setenv("JUSTSEARCH_DATA_DIR", str(absolute))
        mock_proc = MagicMock()
        mock_popen.return_value = mock_proc

        info = start_backend()
        assert info.data_dir == absolute


class TestStartBackendCleanPreservesCohortBaselines:
    """Regression: --clean must preserve cohort_baselines/ + legacy sidecars."""

    @patch("jseval.backend.subprocess.Popen")
    @patch("jseval.backend._wait_for_health", return_value=True)
    def test_clean_preserves_cohort_baselines(self, _health, mock_popen,
                                              tmp_path):
        data_dir = tmp_path / "data"
        (data_dir / "cohort_baselines" / "hash-a").mkdir(parents=True)
        (data_dir / "cohort_baselines" / "hash-a" / "envelope.json").write_text(
            "{}", encoding="utf-8")
        (data_dir / "non_determinism_envelopes").mkdir()
        (data_dir / "non_determinism_envelopes" / "legacy.json").write_text(
            "{}", encoding="utf-8")
        # Simulated transient state that SHOULD be wiped.
        (data_dir / "index").mkdir()
        (data_dir / "index" / "segments.json").write_text("{}", encoding="utf-8")
        (data_dir / "app.lock").write_text("", encoding="utf-8")

        mock_proc = MagicMock()
        mock_popen.return_value = mock_proc

        start_backend(data_dir=data_dir, clean=True)

        # Protected.
        assert (data_dir / "cohort_baselines" / "hash-a" / "envelope.json").is_file()
        assert (data_dir / "non_determinism_envelopes" / "legacy.json").is_file()
        # Wiped.
        assert not (data_dir / "index").exists()
        assert not (data_dir / "app.lock").exists()

    @patch("jseval.backend.subprocess.Popen")
    @patch("jseval.backend._wait_for_health", return_value=True)
    def test_clean_on_empty_data_dir_is_noop(self, _health, mock_popen, tmp_path):
        data_dir = tmp_path / "data"
        data_dir.mkdir()
        mock_proc = MagicMock()
        mock_popen.return_value = mock_proc
        start_backend(data_dir=data_dir, clean=True)
        assert data_dir.is_dir()
