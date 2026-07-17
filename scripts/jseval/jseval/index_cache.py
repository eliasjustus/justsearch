"""On-disk store for input-addressed eval-index cache entries (tempdoc 751, WP2).

Problem this closes: every eval run rebuilds its corpus index from scratch even when the
exact same corpus bytes were already built under the exact same engine/config identity
minutes or hours earlier (tempdoc 751 measured this at ~50 min/10k-doc build, repeated 3x in
~12h for one byte-identical corpus x config). This module is the STORE half of that cache:
given a quiesced, COMPLETE eval data dir plus its identity key, publish it as a reusable
entry; given a candidate selector key, look up whether a usable entry exists.

It does not compute the identity key itself (that is WP1's `selector_key`/confirm-adoption
machinery, per tempdoc 751's design section M.1-M.2) and it does not decide whether to adopt
a hit into a live run (that is WP3's orchestration in `backend.py`'s lifecycle). This module
only knows how to publish, look up, adopt, touch, list, and prune entries on disk.

Deliberate divergence from `dataset_cache.py` (tempdoc 709), the proven in-repo template
this module mirrors structurally (root resolution shape, atomic-publish shape, log tone):
`dataset_cache` is fail-OPEN, because its cached bytes are cheap to refetch -- a corrupted
or doubtful entry there just means "fetch again". THIS store guards adoption decisions that
feed measurement validity, so it is fail-CLOSED for those decisions: any doubt, any
mismatch, any unverifiable entry is a MISS and is NEVER adopted. The one place this store
is deliberately NOT fail-closed is publish()'s own error handling -- cache trouble (disk
full, a locked file, an unexpected exception while copying) must never break a fresh-build
run that would otherwise have succeeded, so publish() catches its own failures, logs a
WARNing, and returns None rather than raising into the caller.

Important limit on what `lookup()` proves: the checks here (entry.json parses, schema
matches, state.json exists, the active generation dir exists) are heuristic integrity checks
only -- cheap enough to run before a backend even starts, but not authoritative. The
authoritative check is WP1's confirm_adoption step, which runs AFTER the candidate entry has
been copied into a live run and the running backend's own surfaces are read back and
compared. A `lookup()` hit is a nomination, never a guarantee (tempdoc 751's M.2 two-phase
adoption protocol: "a static equality check is a candidate selector, never a validity
authority").
"""

from __future__ import annotations

import json
import logging
import os
import shutil
import time
from dataclasses import dataclass
from pathlib import Path

from ._paths import main_repo_root

log = logging.getLogger(__name__)

_ENV_VAR = "JUSTSEARCH_INDEX_CACHE"
_DEFAULT_SUBDIR = ("tmp", "index-cache")
_ENTRIES_DIRNAME = "entries"
_ENTRY_DOC_FILENAME = "entry.json"
_DATA_SUBDIRNAME = "data"
_SCHEMA_VERSION = "index-cache-entry.v1"
_SELECTOR_KEY_DIRNAME_LEN = 32

# Runtime residue that a fresh boot regenerates -- never part of the published artifact.
# Excluded only at the TOP LEVEL of the copied data dir (a nested dir/file that happens to
# share one of these names, e.g. inside index/, is copied verbatim).
_EXCLUDED_TOP_LEVEL_NAMES = frozenset({"logs", "telemetry", "app.lock", "worker_signal.lock"})

# Safety margin before a publish is allowed to prune an entry -- a publish still in flight
# (or one that just landed) must not be evicted by a concurrent prune() call.
_PRUNE_PROTECT_SECONDS = 600


@dataclass(frozen=True)
class CacheEntry:
    """A resolved cache entry: its directory on disk plus its parsed `entry.json`."""

    dir: Path
    doc: dict


def cache_root() -> Path | None:
    """Resolve the index-cache store root, or ``None`` when caching is disabled.

    ``JUSTSEARCH_INDEX_CACHE`` env wins if set: empty string or ``"0"`` disables the cache
    entirely (every caller falls back to today's unconditional fresh build); any other value
    is used verbatim as the store root (an explicit override, e.g. a shared campaign mount).
    Otherwise defaults to ``<main checkout>/tmp/index-cache`` -- the same worktree-to-main
    resolution `dataset_cache.cache_root()` uses (tempdoc 644 Axis 1 / 709), so every
    worktree's eval runs share one store instead of each maintaining its own.

    Never raises. Callers must still treat cache trouble as fail-quiet for anything that
    would otherwise break a fresh build (see module docstring); this function itself simply
    returns ``None`` on any resolution failure, mirroring `dataset_cache.cache_root()`.
    """
    override = os.environ.get(_ENV_VAR)
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


def _entries_root(root: Path) -> Path:
    return root / _ENTRIES_DIRNAME


def _entry_dir(root: Path, selector_key: str) -> Path:
    return _entries_root(root) / selector_key[:_SELECTOR_KEY_DIRNAME_LEN]


def _dir_size(path: Path) -> int:
    """Total bytes of all files under ``path`` (best-effort; unreadable files are skipped)."""
    total = 0
    for child in path.rglob("*"):
        if child.is_file():
            try:
                total += child.stat().st_size
            except OSError:
                continue
    return total


def _copy_data_dir(src: Path, dest: Path) -> None:
    """Copy ``src`` to ``dest`` verbatim, excluding the top-level runtime-residue names."""

    def _ignore(dir_path: str, names: list[str]) -> set[str]:
        if Path(dir_path) == src:
            return {name for name in names if name in _EXCLUDED_TOP_LEVEL_NAMES}
        return set()

    shutil.copytree(src, dest, ignore=_ignore)


def lookup(selector_key: str) -> CacheEntry | None:
    """Return the candidate cache entry for ``selector_key``, or ``None`` on a miss.

    Fail-closed: every check below treats any doubt as a miss, never raises, and logs at
    WARNING (structural corruption) or INFO (an ordinary/expected miss shape). This is a
    heuristic integrity check only -- see the module docstring's note on `confirm_adoption`
    being the authoritative check that actually runs after adoption.
    """
    root = cache_root()
    if root is None:
        return None
    entry_dir = _entry_dir(root, selector_key)
    entry_doc_path = entry_dir / _ENTRY_DOC_FILENAME
    if not entry_doc_path.is_file():
        return None
    try:
        doc = json.loads(entry_doc_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        log.warning(
            "Index cache entry %s has an unreadable entry.json (%s) -- treating as a miss.",
            entry_dir, exc)
        return None
    if doc.get("schema_version") != _SCHEMA_VERSION:
        log.warning(
            "Index cache entry %s has unexpected schema_version=%r (want %r) -- treating as "
            "a miss.", entry_dir, doc.get("schema_version"), _SCHEMA_VERSION)
        return None
    if doc.get("selector_key") != selector_key:
        log.warning(
            "Index cache entry %s selector_key %r does not match the requested key %r -- "
            "treating as a miss.", entry_dir, doc.get("selector_key"), selector_key)
        return None
    state_path = entry_dir / _DATA_SUBDIRNAME / "index" / "default" / "state.json"
    if not state_path.is_file():
        log.info(
            "Index cache entry %s is missing %s -- treating as a miss.", entry_dir, state_path)
        return None
    try:
        state = json.loads(state_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        log.warning(
            "Index cache entry %s has an unreadable state.json (%s) -- treating as a miss.",
            entry_dir, exc)
        return None
    active_generation = state.get("active_generation")
    if not active_generation:
        log.info(
            "Index cache entry %s state.json has no active_generation -- treating as a miss.",
            entry_dir)
        return None
    generation_dir = (
        entry_dir / _DATA_SUBDIRNAME / "index" / "default" / "indices" / active_generation
    )
    if not generation_dir.is_dir():
        log.info(
            "Index cache entry %s active_generation dir %s is missing -- treating as a miss.",
            entry_dir, generation_dir)
        return None
    return CacheEntry(dir=entry_dir, doc=doc)


def publish(
    data_dir: Path, selector_key: str, identity_doc: dict, attestation: dict
) -> Path | None:
    """Publish ``data_dir`` as a new cache entry keyed on ``selector_key``.

    Fail-quiet, per the module docstring: any refusal condition or any unexpected failure
    logs at WARNING and returns ``None`` -- this function never raises into a caller running
    a fresh build. Refusal conditions (checked in order, each a WARNING + None):

    - the cache is disabled (`cache_root()` is ``None``) -- logged at INFO, not a problem;
    - ``attestation["build_state"] != "COMPLETE"`` (the quiesce guard, tempdoc 751's derisk
      section O.2: "quiesced" means enrichment-complete + final commit stamped, which
      build_state COMPLETE is defined to track);
    - ``data_dir`` has no ``index/default/state.json`` (not a real built index);
    - free disk on the store volume is below 2x ``data_dir``'s size.

    On success, publishes near-atomically (build under a sibling ``.tmp-<pid>-<time_ns>/``
    dir, then move-aside-then-rename into place) exactly like `dataset_cache.store()`'s
    pattern, and returns the published entry directory (never the ``data/`` subdirectory).
    "Near": a crash between the move-aside of a stale prior entry and the final rename
    leaves the slot empty and leaks a tmp/stale dir -- readers see a plain miss (fail
    closed) and the next publish self-heals the slot, so the window is crash-only and
    benign, but it is not strictly atomic (review fix F-D).
    """
    root = cache_root()
    if root is None:
        log.info("Index cache publish for %s skipped: cache is disabled.", selector_key)
        return None
    if attestation.get("build_state") != "COMPLETE":
        log.warning(
            "Index cache publish for %s refused: attestation build_state=%r != COMPLETE "
            "(quiesce guard) -- entries are never published from a build that has not "
            "finished.", selector_key, attestation.get("build_state"))
        return None
    state_path = data_dir / "index" / "default" / "state.json"
    if not state_path.is_file():
        log.warning(
            "Index cache publish for %s refused: %s is missing -- this does not look like a "
            "built index.", selector_key, state_path)
        return None

    entries_root = _entries_root(root)
    try:
        entries_root.mkdir(parents=True, exist_ok=True)
    except OSError as exc:
        log.warning(
            "Index cache publish for %s refused: could not create %s (%s).",
            selector_key, entries_root, exc)
        return None

    try:
        data_size = _dir_size(data_dir)
        usage = shutil.disk_usage(entries_root)
    except OSError as exc:
        log.warning(
            "Index cache publish for %s refused: disk usage check failed (%s).",
            selector_key, exc)
        return None
    if usage.free < 2 * data_size:
        log.warning(
            "Index cache publish for %s refused: %d bytes free < 2x data dir size (%d "
            "bytes) -- refusing to risk filling the store volume.",
            selector_key, usage.free, data_size)
        return None

    final_dir = _entry_dir(root, selector_key)
    tmp_dir = entries_root / f".tmp-{os.getpid()}-{time.time_ns()}"
    try:
        tmp_dir.mkdir(parents=True, exist_ok=False)
        _copy_data_dir(data_dir, tmp_dir / _DATA_SUBDIRNAME)
        entry_doc = {
            "schema_version": _SCHEMA_VERSION,
            "selector_key": selector_key,
            "identity": identity_doc,
            "attestation": attestation,
            "published_at": time.time(),
            "last_adopted_at": None,
        }
        (tmp_dir / _ENTRY_DOC_FILENAME).write_text(
            json.dumps(entry_doc, sort_keys=True, default=str), encoding="utf-8")
        if final_dir.exists():
            # A prior entry already occupies this slot -- either stale (a republish of the
            # same selector_key, e.g. after a key-completeness fix) or a genuinely concurrent
            # writer. Move it aside atomically first so the publish below always lands the
            # new content, rather than relying on os.replace()'s reject-if-non-empty
            # behavior, which would silently leave the prior entry in place on a republish.
            stale_dir = entries_root / f".{final_dir.name}.stale-{os.getpid()}-{time.time_ns()}"
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
            # publish. Last-writer-wins is acceptable here: two entries published for the
            # same selector_key describe the same (corpus x config x engine) inputs, so
            # either writer's copy is equivalent-or-better (same rationale as
            # `dataset_cache.store()`'s concurrent-writer race). Discard this writer's copy.
            log.info(
                "Index cache entry %s was already published by a concurrent writer -- "
                "discarding this writer's copy.", final_dir)
            shutil.rmtree(tmp_dir, ignore_errors=True)
        return final_dir
    except Exception as exc:
        # Fail-quiet, per the module docstring: cache trouble during a fresh build must
        # never turn into a run failure. Whatever caused this (a locked file, a copy error,
        # an OS-level surprise) is logged and swallowed; the caller's fresh build proceeds
        # as if the cache did not exist.
        log.warning("Index cache publish for %s failed and was abandoned: %s", selector_key, exc)
        shutil.rmtree(tmp_dir, ignore_errors=True)
        return None


def adopt(entry: CacheEntry, dest_data_dir: Path) -> None:
    """Copy ``entry``'s data into ``dest_data_dir``, which must already exist and be empty.

    Raises ``ValueError`` if ``dest_data_dir`` does not exist or is not empty (the caller is
    expected to have created a fresh, empty data dir for the run). Raises whatever
    ``shutil.copytree`` raises on a copy failure (a locked file, a permissions error, running
    out of disk) -- unlike `publish()`, this is NOT fail-quiet: an adoption the caller cannot
    trust must fall back to a fresh build, which requires the caller to see the failure.
    """
    if not dest_data_dir.is_dir():
        raise ValueError(
            f"adopt() destination {dest_data_dir} must already exist as an empty directory")
    if any(dest_data_dir.iterdir()):
        raise ValueError(f"adopt() destination {dest_data_dir} must be empty")
    # dirs_exist_ok=True: dest_data_dir itself already exists (asserted empty above); this
    # merges the entry's data/ contents into it rather than requiring copytree to create the
    # destination itself, which is the Windows-friendly form of this copy.
    shutil.copytree(entry.dir / _DATA_SUBDIRNAME, dest_data_dir, dirs_exist_ok=True)


def touch(entry: CacheEntry) -> None:
    """Stamp ``entry``'s ``last_adopted_at`` with the current time.

    Best-effort: a failure to read or write ``entry.json`` is logged at WARNING and
    swallowed -- a stamping failure must not turn a successful adoption into a run failure.
    """
    entry_doc_path = entry.dir / _ENTRY_DOC_FILENAME
    try:
        doc = json.loads(entry_doc_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        log.warning(
            "Index cache touch() for %s could not read entry.json (%s) -- not stamped.",
            entry.dir, exc)
        return
    doc["last_adopted_at"] = time.time()
    try:
        entry_doc_path.write_text(json.dumps(doc, sort_keys=True, default=str), encoding="utf-8")
    except OSError as exc:
        log.warning(
            "Index cache touch() for %s could not write entry.json (%s) -- not stamped.",
            entry.dir, exc)


def list_entries() -> list[CacheEntry]:
    """List every parseable entry in the store, or ``[]`` when the cache is disabled/empty.

    An entry whose ``entry.json`` cannot be parsed is skipped (logged at WARNING), not
    raised -- the same fail-closed-per-entry posture as `lookup()`.
    """
    root = cache_root()
    if root is None:
        return []
    entries_root = _entries_root(root)
    if not entries_root.is_dir():
        return []
    result: list[CacheEntry] = []
    for child in sorted(entries_root.iterdir()):
        if not child.is_dir() or child.name.startswith("."):
            continue
        entry_doc_path = child / _ENTRY_DOC_FILENAME
        if not entry_doc_path.is_file():
            continue
        try:
            doc = json.loads(entry_doc_path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as exc:
            log.warning(
                "Index cache entry %s has an unreadable entry.json (%s) -- skipping.",
                child, exc)
            continue
        result.append(CacheEntry(dir=child, doc=doc))
    return result


def _recency_stamp(entry: CacheEntry) -> float:
    return entry.doc.get("last_adopted_at") or entry.doc.get("published_at") or 0.0


def _is_publish_protected(entry: CacheEntry, now: float) -> bool:
    published_at = entry.doc.get("published_at") or 0.0
    return (now - published_at) < _PRUNE_PROTECT_SECONDS


def prune(max_entries: int = 8, max_bytes: int | None = None) -> list[Path]:
    """Evict entries LRU-by-``(last_adopted_at or published_at)`` and return the removed dirs.

    Two independent budgets, both respected: at most ``max_entries`` entries survive, and (if
    ``max_bytes`` is given) total store size is brought at-or-under it by evicting the oldest
    survivors first. Neither budget can evict an entry published less than 10 minutes ago
    (`_PRUNE_PROTECT_SECONDS`) -- a publish-in-progress safety margin, so a prune racing a
    publish can never delete the entry that publish just landed.

    An entry that fails to remove (``OSError`` -- e.g. a locked file on Windows) is logged at
    WARNING and skipped, not raised: one locked entry must not abort the whole prune.
    """
    entries = list_entries()
    if not entries:
        return []
    now = time.time()

    ordered_newest_first = sorted(entries, key=_recency_stamp, reverse=True)
    keep: list[CacheEntry] = []
    removal_candidates: list[CacheEntry] = []
    for entry in ordered_newest_first:
        if len(keep) < max_entries or _is_publish_protected(entry, now):
            keep.append(entry)
        else:
            removal_candidates.append(entry)
    removal_candidates.sort(key=_recency_stamp)  # oldest first

    removed: list[Path] = []
    for entry in removal_candidates:
        try:
            shutil.rmtree(entry.dir, ignore_errors=False)
        except OSError as exc:
            log.warning("Index cache prune could not remove %s (%s) -- skipping.", entry.dir, exc)
            continue
        removed.append(entry.dir)

    if max_bytes is not None:
        remaining = sorted(keep, key=_recency_stamp)  # oldest first
        sizes = {entry.dir: _dir_size(entry.dir) for entry in remaining}
        total = sum(sizes.values())
        for entry in remaining:
            if total <= max_bytes:
                break
            if _is_publish_protected(entry, now):
                continue
            try:
                shutil.rmtree(entry.dir, ignore_errors=False)
            except OSError as exc:
                log.warning(
                    "Index cache prune could not remove %s (%s) -- skipping.", entry.dir, exc)
                continue
            total -= sizes[entry.dir]
            removed.append(entry.dir)

    return removed
