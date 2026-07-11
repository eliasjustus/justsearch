"""Shared, cross-worktree cache for raw dataset-fetch network artifacts (tempdoc 709).

Problem: each git worktree has its own `datasets/` + `scripts/jseval/tmp/eval-corpora/`
(both gitignored, per this project's universal "fetch, never commit" corpus-licensing
policy — see `corpus_fetch.py`'s module docstring and the "Corpus provenance note" entries
in `docs/reference/search-quality-register.md`), and worktree teardown deletes them. So a
GB-scale fetch (e.g. `jseval corpus-fetch-clerc`'s CLERC collection stream, ~15+ min) reruns
from scratch in every worktree, even same-day, even for the exact same recorded recipe.

This module gives the FETCH layer the same shared-cache pattern `shared_models_dir()`
(`_paths.py`, tempdoc 644 Axis 1) already gives model binaries: resolve a cache root under
the MAIN checkout (via `main_repo_root()`) so every worktree dedupes against one copy.

Licensing posture is unchanged by this cache: it is gitignored (`scripts/jseval/tmp/` is
already wholesale gitignored — see `.gitignore:218` — and the cache lives under that tree),
never committed, and purely a network-trip deduplication. Nothing about the "fetch fresh,
never redistribute" policy changes; this just avoids re-*fetching* the same fresh bytes N
times a day across N worktrees.

Caches the RAW upstream artifacts (the actual downloaded bytes), not a fetcher's sampled
output — sampling is cheap and seed-dependent, but the raw bytes are the same regardless of
seed, so caching them also serves future different-seed samples for free (strictly better
than caching one seed's sampled output).
"""

from __future__ import annotations

import contextlib
import hashlib
import json
import logging
import os
import shutil
import tempfile
import time
from pathlib import Path

from ._paths import main_repo_root
from .corpus_identity import corpus_signature

log = logging.getLogger(__name__)

_SIGNATURE_FILE = "signature.json"
_DEFAULT_SUBDIR = ("scripts", "jseval", "tmp", "dataset-fetch-cache")


def cache_root() -> Path | None:
    """Resolve the shared dataset-fetch cache root, or ``None`` when caching is disabled.

    ``JUSTSEARCH_DATASET_CACHE`` env wins if set: empty string or ``"0"`` disables caching
    entirely (every fetcher falls back to its pre-709 direct-fetch behavior); any other
    value is used verbatim as the cache root (an explicit override for e.g. a shared CI
    cache mount). Otherwise defaults to a directory under the MAIN checkout — the same
    worktree-to-main resolution `shared_models_dir()` already uses (tempdoc 644 Axis 1) —
    so every worktree's `corpus-fetch-clerc`/`corpus-fetch-miracl` shares one cache instead
    of each re-downloading GB-scale collections.

    Never raises. Callers that go on to *write* must still handle the directory being
    unwritable (permission errors, read-only mount, etc.) by falling back to a direct,
    uncached fetch — fail OPEN, never fail CLOSED on cache trouble (a cache must never make
    a fetch fail that would have succeeded without it).
    """
    override = os.environ.get("JUSTSEARCH_DATASET_CACHE")
    if override is not None:
        if override.strip() in ("", "0"):
            return None
        return Path(override)
    try:
        root = main_repo_root()
    except OSError:
        return None
    if root is None:
        return None
    for part in _DEFAULT_SUBDIR:
        root = root / part
    return root


def _entry_key(fetcher: str, params: dict) -> str:
    """Deterministic cache-entry directory name for a (fetcher, recipe-identity params) pair."""
    canonical = json.dumps({"fetcher": fetcher, "params": params}, sort_keys=True, default=str)
    return hashlib.sha256(canonical.encode("utf-8")).hexdigest()[:32]


def _entry_dir(root: Path, fetcher: str, params: dict) -> Path:
    return root / fetcher / _entry_key(fetcher, params)


def lookup(fetcher: str, params: dict, *, filenames: list[str]) -> Path | None:
    """Return the verified cache-entry directory for ``(fetcher, params)``, or ``None`` on a miss.

    A cache hit is verified via `corpus_identity.corpus_signature` (its explicit ``files=``
    mode, over ``filenames`` in the given fixed order) against the `signature.json` recorded
    at write time — a mismatch, a missing signature file, or a missing member file is all
    treated as a MISS (a corrupted/incomplete entry), never used as-is. This is the same
    lesson tempdoc 635 already recorded: an unverified cache once produced a silent
    nDCG-0.0 run. Reuses the established signature algorithm rather than inventing a second
    one (conform, don't fork).
    """
    root = cache_root()
    if root is None:
        return None
    entry_dir = _entry_dir(root, fetcher, params)
    sig_path = entry_dir / _SIGNATURE_FILE
    if not sig_path.is_file():
        return None
    try:
        recorded = json.loads(sig_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return None
    files = [entry_dir / name for name in filenames]
    if not all(f.is_file() for f in files):
        return None
    actual = corpus_signature(entry_dir, files=files)
    if actual is None or actual != recorded.get("signature"):
        log.warning(
            "Dataset cache entry %s failed signature verification -- treating as a miss "
            "(will refetch and overwrite).", entry_dir)
        return None
    return entry_dir


def store(fetcher: str, params: dict, *, filenames: list[str], populate) -> Path:
    """Populate a new cache entry and return its directory, publishing it atomically.

    ``populate(tmp_dir: Path) -> None`` must write exactly ``filenames`` into ``tmp_dir``
    (the actual network fetch happens inside this callback). The entry is assembled in a
    temp directory under the cache root's per-fetcher subdir, then published atomically: if
    a prior entry already occupies ``final_dir`` (a stale/corrupted entry being refetched, or
    a genuinely concurrent writer), it is first atomically moved aside and deleted, then the
    new entry is atomically renamed into place — a concurrent reader either sees the prior
    entry, a miss, or the new entry, never a partial write; no locking is needed. If a
    second, truly concurrent writer wins the final publish rename, this one discards its own
    copy — acceptable because cached content is deterministic per ``params``, so either
    writer's bytes would be equivalent (tempdoc 709 concurrency design). Refetch-after-
    corruption is NOT the same case as that race: it must always end with the new content in
    place, which the move-aside-then-publish sequence (rather than relying on
    ``os.replace``'s reject-if-non-empty behavior alone) guarantees.

    Raises whatever ``populate`` raises (i.e. the underlying network error) on fetch
    failure; raises ``RuntimeError`` if the cache is disabled (callers should check
    `cache_root()` first and skip calling `store` entirely when it is ``None``).
    """
    root = cache_root()
    if root is None:
        raise RuntimeError("dataset cache is disabled (JUSTSEARCH_DATASET_CACHE=0/empty)")
    fetcher_dir = root / fetcher
    fetcher_dir.mkdir(parents=True, exist_ok=True)
    final_dir = _entry_dir(root, fetcher, params)
    tmp_dir = Path(tempfile.mkdtemp(prefix=f".{final_dir.name}.tmp-", dir=fetcher_dir))
    try:
        populate(tmp_dir)
        files = [tmp_dir / name for name in filenames]
        missing = [str(f) for f in files if not f.is_file()]
        if missing:
            raise RuntimeError(f"populate() did not write expected file(s): {missing}")
        signature = corpus_signature(tmp_dir, files=files)
        (tmp_dir / _SIGNATURE_FILE).write_text(
            json.dumps(
                {
                    "fetcher": fetcher,
                    "params": params,
                    "filenames": filenames,
                    "signature": signature,
                    "written_at": time.time(),
                },
                sort_keys=True,
            ),
            encoding="utf-8",
        )
        if final_dir.exists():
            # A prior entry occupies this slot -- either stale/corrupted (a refetch, per the
            # signature-mismatch path in `lookup()`) or a genuinely concurrent writer. Move it
            # aside atomically first so the publish below always lands the new content, rather
            # than relying on `os.replace`'s reject-if-non-empty behavior (which would silently
            # keep corrupted content in place on a refetch -- confirmed the wrong outcome live).
            stale_dir = fetcher_dir / f".{final_dir.name}.stale-{os.getpid()}-{time.time_ns()}"
            try:
                os.replace(final_dir, stale_dir)
            except OSError:
                stale_dir = None
            else:
                shutil.rmtree(stale_dir, ignore_errors=True)
        try:
            os.replace(tmp_dir, final_dir)
        except OSError:
            # A second writer raced us into final_dir's slot between our move-aside and our
            # publish -- last-writer-wins is acceptable (deterministic content per `params`);
            # discard ours.
            log.info(
                "Dataset cache entry %s already published by a concurrent writer -- discarding "
                "this writer's copy.", final_dir)
            shutil.rmtree(tmp_dir, ignore_errors=True)
        return final_dir
    except BaseException:
        shutil.rmtree(tmp_dir, ignore_errors=True)
        raise


def apply_ir_datasets_home() -> None:
    """Point `ir_datasets`' own on-disk download cache at the shared dataset-fetch cache root
    (tempdoc 709), so MIRACL (`corpus_fetch.fetch_miracl_sample`) and every BEIR dataset
    (`corpora._load_beir`) also dedupe their downloads across worktrees.

    Config-only: `ir_datasets.util.home_path()` already resolves its cache directory from
    `IR_DATASETS_HOME` (`Path(os.environ.get('IR_DATASETS_HOME', Path.home() / '.ir_datasets'))`)
    and does its own integrity verification of what it downloads, so this needs no new
    caching or signature-verification code of its own for this source — only pointing its
    existing env var at our shared root. A no-op when the shared cache is disabled/unavailable
    (`cache_root()` is `None`) or when the environment already set `IR_DATASETS_HOME` itself
    (lowest precedence — mirrors `backend.py`'s `JUSTSEARCH_MODELS_DIR` resolution, tempdoc 644
    Axis 1). Idempotent and cheap enough to call on every `ir_datasets.load()` call site.
    """
    if os.environ.get("IR_DATASETS_HOME"):
        return
    root = cache_root()
    if root is None:
        return
    os.environ["IR_DATASETS_HOME"] = str(root / "ir_datasets")


@contextlib.contextmanager
def cached_dir(fetcher: str, params: dict, *, filenames: list[str], populate):
    """The one entry point fetchers should use: yields a directory containing ``filenames``,
    either a verified cache hit, a freshly-populated (and now cached) entry, or — when the
    cache is disabled or unavailable — an ephemeral directory from a direct uncached fetch.

    Never raises solely because the cache is unavailable — a write failure under the cache
    root (permissions, read-only mount, disk full) falls back to an uncached direct fetch
    rather than failing the whole operation (fail OPEN, tempdoc 709 pinned constraint b).

    A cache-hit or newly-cached directory is durable and is never deleted on exit (that's
    the whole point — it survives for the next call, in this or another worktree). Only the
    disabled/unavailable-cache ephemeral fallback directory is cleaned up on exit.
    """
    hit = lookup(fetcher, params, filenames=filenames)
    if hit is not None:
        log.info("Dataset cache hit for %s at %s (skipping network fetch).", fetcher, hit)
        yield hit
        return

    root = cache_root()
    entry_dir: Path | None = None
    if root is not None:
        # This try/except is scoped tightly around `store()` itself -- NOT around the
        # `yield` below -- so an OSError raised later by the caller's own body (while it's
        # using the yielded directory) is never mistaken for a cache-write failure.
        try:
            entry_dir = store(fetcher, params, filenames=filenames, populate=populate)
        except OSError:
            log.warning(
                "Dataset cache root %s unavailable for writing -- falling back to an "
                "uncached direct fetch.", root, exc_info=True)
    if entry_dir is not None:
        yield entry_dir
        return

    tmp_dir = Path(tempfile.mkdtemp(prefix=f"{fetcher}-nocache-"))
    try:
        populate(tmp_dir)
        yield tmp_dir
    finally:
        shutil.rmtree(tmp_dir, ignore_errors=True)
