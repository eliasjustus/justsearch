"""Canonical path constants for jseval.

All paths that jseval creates or reads are anchored to the repository root,
so invocation CWD never affects behavior.
"""

from __future__ import annotations

import os
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

# Default output directories, all under scripts/jseval/tmp/
_JSEVAL_TMP: Path = REPO_ROOT / "scripts" / "jseval" / "tmp"
DEFAULT_EVAL_RESULTS: Path = _JSEVAL_TMP / "eval-results"
DEFAULT_BENCH_CLAIM_A: Path = _JSEVAL_TMP / "bench" / "claim-a"
DEFAULT_BENCH_TRACK_G: Path = _JSEVAL_TMP / "bench" / "track-g"


def default_corpus_dir(dataset_name: str) -> Path:
    """Default materialization directory for a dataset."""
    return _JSEVAL_TMP / "eval-corpora" / dataset_name
