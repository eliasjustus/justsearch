"""Canonical path constants for jseval.

All paths that jseval creates or reads are anchored to the repository root,
so invocation CWD never affects behavior.
"""

from __future__ import annotations

import os
import re
from pathlib import Path


def _resolve_repo_root() -> Path:
    """
    Resolve the repository root.

    Default: derive from this module's location (`parents[3]` ≡ repo root).

    observations.md fix: when jseval is pip-installed but invoked from a
    git worktree (CWD is a different repo root), the module location points
    at the *primary* checkout, not the active worktree. The launcher would
    then run gradle/build artifacts against the wrong tree. Detect a
    worktree-shaped CWD and prefer it. A directory is treated as a repo
    root when it contains a `.git` entry (file or dir — git worktrees use
    a `.git` *file* that points at the main repo's `.git/worktrees/<name>`).

    Override with `JUSTSEARCH_REPO_ROOT=<absolute-path>` to short-circuit
    detection (CI, hermetic test runs).
    """
    override = os.environ.get("JUSTSEARCH_REPO_ROOT")
    if override:
        candidate = Path(override).resolve()
        if candidate.is_dir():
            return candidate

    module_root = Path(__file__).resolve().parents[3]

    # Walk up from CWD looking for a .git entry (file = worktree, dir = repo).
    cwd = Path.cwd().resolve()
    for ancestor in (cwd, *cwd.parents):
        if (ancestor / ".git").exists():
            # Sanity-check: an unrelated git repo elsewhere on disk shouldn't
            # claim ownership. Require a `scripts/jseval` peer directory so
            # we only redirect when CWD looks like a JustSearch checkout.
            if (ancestor / "scripts" / "jseval").is_dir():
                return ancestor
            break  # found a .git but not JustSearch — fall through to module_root

    return module_root


REPO_ROOT: Path = _resolve_repo_root()


def main_repo_root() -> Path:
    """Resolve the MAIN checkout root, even when ``REPO_ROOT`` is a linked git worktree.

    A linked worktree's ``.git`` is a *file* containing
    ``gitdir: <main>/.git/worktrees/<name>``. Walk that back to the main checkout so that
    large shared assets (models, native runtime) resolve against the binary-bearing main
    tree rather than the worktree's pointer-only copy.

    Mirrors ``scripts/dev/dev-runner.cjs`` ``resolveMainRepoRoot`` (tempdoc 618 §2 / 644
    Axis 1). Falls back to ``REPO_ROOT`` when not in a worktree or on any parse failure.
    """
    git_path = REPO_ROOT / ".git"
    try:
        if git_path.is_file():
            content = git_path.read_text(encoding="utf-8").strip()
            match = re.match(r"^gitdir:\s*(.+)$", content)
            if match:
                # gitdir → <main>/.git/worktrees/<name>; up 3 levels → <main>.
                git_dir = (REPO_ROOT / match.group(1)).resolve()
                return git_dir.parents[2]
    except (OSError, IndexError):
        pass
    return REPO_ROOT


def shared_models_dir() -> Path | None:
    """The ``models/`` directory a stack-bound run should use.

    Prefers the MAIN checkout's ``models/`` (which holds the LFS binaries) over a
    worktree's pointer-only copy, so worktree eval discovers the reranker/dense/SPLADE
    models instead of silently disabling them (tempdoc 644 Axis 1; mirrors
    ``dev-runner.cjs:428-434``). Returns the first existing candidate, or ``None`` if
    neither the main nor the local ``models/`` exists.
    """
    main_models = main_repo_root() / "models"
    if main_models.is_dir():
        return main_models
    local_models = REPO_ROOT / "models"
    if local_models.is_dir():
        return local_models
    return None


# Default output directories, all under scripts/jseval/tmp/
_JSEVAL_TMP: Path = REPO_ROOT / "scripts" / "jseval" / "tmp"
DEFAULT_EVAL_RESULTS: Path = _JSEVAL_TMP / "eval-results"
DEFAULT_BENCH_CLAIM_A: Path = _JSEVAL_TMP / "bench" / "claim-a"
DEFAULT_BENCH_TRACK_G: Path = _JSEVAL_TMP / "bench" / "track-g"


def default_corpus_dir(dataset_name: str) -> Path:
    """Default materialization directory for a dataset."""
    return _JSEVAL_TMP / "eval-corpora" / dataset_name
