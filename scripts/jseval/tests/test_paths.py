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
