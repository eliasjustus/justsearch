"""Tests for _paths.py — worktree→main asset resolution (tempdoc 644 Axis 1)."""

from __future__ import annotations

from unittest.mock import patch

import jseval._paths as paths


class TestMainRepoRoot:
    def test_resolves_worktree_to_main(self, tmp_path):
        # Layout: <main>/.git/worktrees/<name> is the worktree's gitdir; the worktree's
        # .git is a FILE pointing at it. main_repo_root() must walk back to <main>.
        main = tmp_path / "main"
        wt_gitdir = main / ".git" / "worktrees" / "feature"
        wt_gitdir.mkdir(parents=True)
        (main / "models").mkdir()

        wt = tmp_path / "feature"
        wt.mkdir()
        (wt / ".git").write_text(f"gitdir: {wt_gitdir}\n", encoding="utf-8")

        with patch.object(paths, "REPO_ROOT", wt):
            assert paths.main_repo_root() == main.resolve()

    def test_falls_back_when_main_checkout(self, tmp_path):
        # A main checkout has .git as a DIRECTORY → no worktree walk, return REPO_ROOT.
        repo = tmp_path / "repo"
        (repo / ".git").mkdir(parents=True)

        with patch.object(paths, "REPO_ROOT", repo):
            assert paths.main_repo_root() == repo

    def test_falls_back_on_garbage_git_file(self, tmp_path):
        repo = tmp_path / "repo"
        repo.mkdir()
        (repo / ".git").write_text("not a gitdir line\n", encoding="utf-8")

        with patch.object(paths, "REPO_ROOT", repo):
            assert paths.main_repo_root() == repo


class TestJsevalDataDirConstants:
    """Tempdoc 716: DEFAULT_JSEVAL_DATA_DIR is the canonical jseval-owned root."""

    def test_eval_results_is_child_of_jseval_data_dir(self):
        assert paths.DEFAULT_EVAL_RESULTS == paths.DEFAULT_JSEVAL_DATA_DIR / "eval-results"

    def test_eval_results_value_unchanged_by_716_refactor(self):
        # The 716 constant introduction must be a no-op for every existing
        # --output-dir default: same literal path as the pre-716 definition.
        assert paths.DEFAULT_EVAL_RESULTS == (
            paths.REPO_ROOT / "scripts" / "jseval" / "tmp" / "eval-results"
        )

    def test_jseval_data_dir_is_jseval_tmp(self):
        assert paths.DEFAULT_JSEVAL_DATA_DIR == paths.REPO_ROOT / "scripts" / "jseval" / "tmp"

    def test_backend_data_dir_matches_backend_default(self, monkeypatch):
        # Mirrors backend.start_backend's `resolved_root / "tmp" / "headless-eval-data"`
        # fallback — the two definitions must not drift.
        monkeypatch.undo()  # conftest's autouse legacy-root isolation patches this constant
        assert paths.DEFAULT_BACKEND_DATA_DIR == paths.REPO_ROOT / "tmp" / "headless-eval-data"


class TestSharedModelsDir:
    def test_prefers_main_models(self, tmp_path):
        main = tmp_path / "main"
        wt_gitdir = main / ".git" / "worktrees" / "feature"
        wt_gitdir.mkdir(parents=True)
        (main / "models").mkdir()

        wt = tmp_path / "feature"
        wt.mkdir()
        (wt / "models").mkdir()  # worktree also has a models/ (pointer-only in real life)
        (wt / ".git").write_text(f"gitdir: {wt_gitdir}\n", encoding="utf-8")

        with patch.object(paths, "REPO_ROOT", wt):
            assert paths.shared_models_dir() == (main / "models").resolve()

    def test_falls_back_to_local_models(self, tmp_path):
        # Not a worktree (main checkout) and only a local models/ exists.
        repo = tmp_path / "repo"
        (repo / ".git").mkdir(parents=True)
        (repo / "models").mkdir()

        with patch.object(paths, "REPO_ROOT", repo):
            assert paths.shared_models_dir() == repo / "models"

    def test_none_when_no_models(self, tmp_path):
        repo = tmp_path / "repo"
        (repo / ".git").mkdir(parents=True)

        with patch.object(paths, "REPO_ROOT", repo):
            assert paths.shared_models_dir() is None
