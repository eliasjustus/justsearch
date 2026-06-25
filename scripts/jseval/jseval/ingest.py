"""Corpus ingestion with backpressure and queue-depth monitoring."""

from __future__ import annotations

import json
import logging
import time
from pathlib import Path

import httpx

from .readiness import flatten_status, wait_index_idle, wait_pipeline_complete
from .types import IngestConfig

log = logging.getLogger(__name__)

# Backpressure defaults (from PS1 BeirEval.Indexing.psm1)
HIGH_WATERMARK = 90_000
LOW_WATERMARK = 70_000
BACKPRESSURE_POLL_SEC = 2.0
BATCH_SIZE = 200
REQUEST_TIMEOUT_SEC = 25.0


def ingest_and_wait(
    config: IngestConfig,
    docs_dir: Path,
    *,
    corpus_doc_count: int = 0,
    poll_interval_sec: float = 2.0,
    stable_polls_required: int = 2,
) -> dict:
    """Add docs_dir as a watched root and wait for indexing to complete.

    Returns a summary dict with timing and throughput info.
    """
    start = time.monotonic()

    # Get initial doc count for floor calculation
    initial_count = _get_indexed_doc_count(config.base_url)
    if corpus_doc_count > 0:
        # Expect corpus to be ADDED to whatever already exists.
        # Use initial + corpus as the floor, not max(initial, corpus),
        # so a dirty index (with pre-existing data) doesn't pass prematurely.
        expected_min = initial_count + corpus_doc_count
    else:
        expected_min = -1

    # Add the watched root
    add_watched_root(config.base_url, docs_dir)

    # Wait for the file watcher to start scanning.  After add_watched_root()
    # returns, the watcher enumerates files asynchronously.  Without this
    # delay, wait_index_idle() can see IDLE/0-pending before any jobs are
    # queued and pass immediately.
    # [-1c] Scale timeout with corpus size — 30s is too short for large corpora.
    watcher_timeout = _watcher_settle_timeout(corpus_doc_count)
    watcher_active = _wait_for_watcher_activity(
        config.base_url, initial_count, poll_interval_sec,
        timeout_sec=watcher_timeout,
    )

    if not watcher_active and expected_min > initial_count:
        # Watcher didn't start — corpus is likely already indexed.
        # Don't use the additive floor or we'll block forever.
        log.info(
            "Watcher didn't queue new jobs; corpus may already be indexed. "
            "Relaxing doc-count floor from %d to %d.",
            expected_min, initial_count,
        )
        expected_min = initial_count

    # Timeline recording (item 5).
    from . import timeline as tl_mod
    timeline_rows: list[dict] = []
    on_snapshot = None
    if config.timeline_path is not None or config.pipeline:
        def on_snapshot(elapsed_s: float, snap: dict) -> None:
            timeline_rows.append(tl_mod.snapshot_to_row(elapsed_s, snap))

    # Wait for indexing (and optionally all enrichments) to finish.
    # Per-stage enabled state is published by the Worker on the status snapshot
    # (tempdoc 394 follow-up); wait_pipeline_complete reads it directly and
    # skips checks for disabled stages.
    if config.pipeline:
        readiness = wait_pipeline_complete(
            config.base_url,
            expected_doc_count_min=expected_min,
            timeout_sec=config.index_timeout_sec,
            poll_interval_sec=poll_interval_sec,
            stable_polls_required=stable_polls_required,
            on_snapshot=on_snapshot,
            json_mode=config.json_mode,
            process_check=config.process_check,
        )
    else:
        readiness = wait_index_idle(
            config.base_url,
            expected_doc_count_min=expected_min,
            dense_enabled=config.dense_enabled,
            splade_enabled=config.splade_enabled,
            timeout_sec=config.index_timeout_sec,
            poll_interval_sec=poll_interval_sec,
            stable_polls_required=stable_polls_required,
            on_snapshot=on_snapshot,
            json_mode=config.json_mode,
            process_check=config.process_check,
        )

    elapsed = time.monotonic() - start
    final_count = readiness.snapshot.get("indexedDocuments", 0)
    docs_indexed = final_count - initial_count

    snapshot = readiness.snapshot
    summary: dict = {
        "readiness_passed": readiness.passed,
        "failure_reasons": readiness.failure_reasons,
        "initial_doc_count": initial_count,
        "final_doc_count": final_count,
        "docs_indexed": docs_indexed,
        "elapsed_sec": round(elapsed, 2),
        "docs_per_sec": round(docs_indexed / elapsed, 1) if elapsed > 0 else 0,
        "index_size_bytes": snapshot.get("indexSizeBytes"),
    }

    # Write timeline TSV (item 5).
    if config.timeline_path and timeline_rows:
        tl_mod.write_timeline_tsv(timeline_rows, config.timeline_path)

    # Compute pipeline summary (item 6).
    if config.pipeline and timeline_rows:
        summary["pipeline_summary"] = tl_mod.compute_pipeline_summary(timeline_rows)

    if readiness.passed:
        log.info(
            "Indexing complete: %d docs in %.1fs (%.1f docs/s)",
            docs_indexed, elapsed, summary["docs_per_sec"],
        )
    else:
        log.error(
            "Indexing did not complete: %s", readiness.failure_reasons,
        )

    return summary


def add_watched_root(
    base_url: str,
    docs_dir: Path,
    timeout_sec: float = 1800.0,
) -> None:
    """Add a directory as a watched root for JustSearch indexing."""
    abs_path = str(docs_dir.resolve())
    log.info("Adding watched root: %s", abs_path)

    with httpx.Client(base_url=base_url, timeout=timeout_sec) as client:
        resp = client.post(
            "/api/indexing/roots",
            json={"path": abs_path},
        )
        resp.raise_for_status()
    log.info("Watched root added successfully")


def ingest_batches(
    base_url: str,
    docs_dir: Path,
    *,
    batch_size: int = BATCH_SIZE,
    high_watermark: int = HIGH_WATERMARK,
    low_watermark: int = LOW_WATERMARK,
    poll_sec: float = BACKPRESSURE_POLL_SEC,
    request_timeout_sec: float = REQUEST_TIMEOUT_SEC,
) -> int:
    """Submit files in batches with backpressure.

    Returns the number of batches submitted.
    """
    files = sorted(docs_dir.glob("*.txt"))
    total_files = len(files)
    if total_files == 0:
        log.warning("No .txt files found in %s", docs_dir)
        return 0

    log.info("Ingesting %d files in batches of %d", total_files, batch_size)
    batches_submitted = 0

    with httpx.Client(base_url=base_url, timeout=request_timeout_sec) as client:
        for i in range(0, total_files, batch_size):
            batch = files[i : i + batch_size]

            # Backpressure: wait if queue is too full
            _wait_for_backpressure(client, high_watermark, low_watermark, poll_sec)

            # Submit batch
            _submit_batch(client, batch)
            batches_submitted += 1

            if batches_submitted % 25 == 0 or i + batch_size >= total_files:
                log.info(
                    "Submitted %d/%d files (%d batches)",
                    min(i + batch_size, total_files), total_files, batches_submitted,
                )

    log.info("Batch ingestion complete: %d batches", batches_submitted)
    return batches_submitted


def prepare_corpus(
    dataset_name: str,
    config: IngestConfig,
    corpus_dir: Path | None = None,
) -> dict:
    """Materialize (if needed) and ingest a dataset.

    Returns an ingest summary dict with timing/throughput info.
    """
    from ._paths import default_corpus_dir

    resolved_dir = corpus_dir or default_corpus_dir(dataset_name)
    corpus_doc_count = _ensure_materialized(dataset_name, resolved_dir, corpus_dir)
    return ingest_and_wait(config, resolved_dir, corpus_doc_count=corpus_doc_count)


# The materialization cache is a VERIFIED PROJECTION of the source (tempdoc 635
# verification-binding): for a local (golden/mixed) corpus the cache is reused only when its
# `.source_signature` sidecar still matches the source's canonical `corpus_signature`; on any
# identity change it is cleared and re-materialized. This closes the stale-cache class — a
# regenerated corpus silently re-ingesting the previous cache (→ nDCG 0.0 / a cert that lies
# about which corpus it measured) — structurally, at the source→materialization boundary.
_SIDECAR = ".source_signature"


def _source_signature(dataset_name: str) -> str | None:
    """Canonical `corpus_signature` of a LOCAL (golden/mixed) source, else None.

    Returns None for BEIR / unknown datasets — those caches come from immutable
    ir-datasets versions and cannot go stale via regeneration, so they keep the plain
    materialize-if-empty behaviour.
    """
    if not (dataset_name.startswith("golden/") or dataset_name.startswith("mixed/")):
        return None
    from .corpora import _default_base_dir
    from .corpus_identity import corpus_signature
    return corpus_signature(_default_base_dir() / dataset_name)


def _materialize_into(dataset_name: str, resolved_dir: Path) -> None:
    from . import materialize as mat_mod
    from .corpora import BEIR_DATASETS
    if dataset_name in BEIR_DATASETS:
        import ir_datasets
        ds = ir_datasets.load(BEIR_DATASETS[dataset_name])
        mat_mod.materialize(ds.docs_iter(), resolved_dir, skip_existing=True)
    elif dataset_name.startswith("golden/") or dataset_name.startswith("mixed/"):
        corpus_jsonl = _find_corpus_jsonl(dataset_name)
        mat_mod.materialize(_iter_corpus_jsonl(corpus_jsonl), resolved_dir, skip_existing=True)
    else:
        raise ValueError(f"Cannot materialize unknown dataset: {dataset_name!r}")


def _ensure_materialized(
    dataset_name: str, resolved_dir: Path, corpus_dir: Path | None
) -> int:
    """Ensure ``resolved_dir`` holds the CURRENT corpus; return its ``.txt`` doc count.

    Reuses the cache only when it is a verified projection of the source (sidecar signature
    matches); otherwise clears the stale ``.txt`` files + sidecar and re-materializes. The
    sentinel + sidecar are not ``.txt`` so they never inflate the count.
    """
    txt_count = len(list(resolved_dir.glob("*.txt"))) if resolved_dir.is_dir() else 0

    if corpus_dir is not None:
        # Explicit --corpus-dir: use as-is, never materialize into it.
        if txt_count == 0:
            raise FileNotFoundError(
                f"--corpus-dir {resolved_dir} has no .txt files. "
                f"Materialize first with: jseval materialize "
                f"--dataset {dataset_name} --output-dir {resolved_dir}"
            )
        log.info("Using explicit corpus dir: %s (%d files)", resolved_dir, txt_count)
        return txt_count

    src_sig = _source_signature(dataset_name)
    sidecar = resolved_dir / _SIDECAR
    cached_sig = sidecar.read_text(encoding="utf-8").strip() if sidecar.is_file() else None

    fresh = txt_count == 0
    stale = src_sig is not None and cached_sig != src_sig  # changed source, or never recorded
    if fresh or stale:
        if stale and not fresh:
            log.info(
                "Corpus %s changed (cache sig %s != source %s) — re-materializing %s",
                dataset_name, (cached_sig or "none")[:12], (src_sig or "")[:12], resolved_dir,
            )
            for p in resolved_dir.glob("*.txt"):
                p.unlink()
            sidecar.unlink(missing_ok=True)
        else:
            log.info("Materializing corpus for %s to %s", dataset_name, resolved_dir)
        _materialize_into(dataset_name, resolved_dir)
        if src_sig is not None:
            (resolved_dir / _SIDECAR).write_text(src_sig, encoding="utf-8")
    else:
        log.info("Corpus already materialized at %s (%d files; identity verified)",
                 resolved_dir, txt_count)

    return len(list(resolved_dir.glob("*.txt")))


def _find_corpus_jsonl(dataset_name: str) -> Path:
    """Locate corpus.jsonl for a golden/mixed dataset."""
    from .corpora import _default_base_dir
    path = _default_base_dir() / dataset_name / "corpus.jsonl"
    if not path.is_file():
        raise FileNotFoundError(f"corpus.jsonl not found at {path}")
    return path


def _iter_corpus_jsonl(path: Path):
    """Iterate dicts from a BEIR-format corpus.jsonl file."""
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if line:
            yield json.loads(line)


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

_WATCHER_SETTLE_MIN_SEC = 30.0
_WATCHER_SETTLE_MAX_SEC = 300.0


def _watcher_settle_timeout(corpus_doc_count: int) -> float:
    """Compute watcher settle timeout proportional to corpus size.

    Small corpora (< 3000 docs): 30s (original default).
    Large corpora: scales up to 300s max.  Heuristic: 1s per 100 docs,
    clamped to [30, 300].
    """
    if corpus_doc_count <= 0:
        return _WATCHER_SETTLE_MIN_SEC
    scaled = max(_WATCHER_SETTLE_MIN_SEC, corpus_doc_count / 100.0)
    return min(scaled, _WATCHER_SETTLE_MAX_SEC)


def _wait_for_watcher_activity(
    base_url: str,
    initial_doc_count: int,
    poll_sec: float,
    *,
    timeout_sec: float = _WATCHER_SETTLE_MIN_SEC,
) -> bool:
    """Wait until the file watcher starts queueing jobs or indexing new docs.

    After add_watched_root(), the watcher scans files asynchronously.  If we
    start the readiness poll immediately, we can see IDLE + 0 pending and
    pass before any files are queued.  This helper waits until we see either
    pendingJobs > 0, indexState != IDLE, or doc count has grown — any of
    which means the watcher has started working.

    Returns True if activity was detected, False on timeout (the corpus dir
    may already be fully indexed, in which case no new activity appears).
    """
    deadline = time.monotonic() + timeout_sec
    log.debug("Waiting up to %.0fs for watcher activity", timeout_sec)
    with httpx.Client(base_url=base_url, timeout=10) as client:
        while time.monotonic() < deadline:
            time.sleep(poll_sec)
            try:
                resp = client.get("/api/status")
                resp.raise_for_status()
                data = flatten_status(resp.json())
            except Exception:
                continue
            pending = (
                data.get("pendingJobs", 0)
                + data.get("pendingJobsCount", 0)
                + data.get("processingJobsCount", 0)
            )
            if pending > 0:
                log.debug("Watcher active: %d pending jobs", pending)
                return True
            if data.get("indexState") != "IDLE":
                log.debug("Watcher active: indexState=%s", data.get("indexState"))
                return True
            if data.get("indexedDocuments", 0) > initial_doc_count:
                log.debug("Watcher active: doc count grew")
                return True

    log.debug("Watcher settle timeout (%.0fs) — corpus may already be indexed",
              timeout_sec)
    return False


def _get_indexed_doc_count(base_url: str) -> int:
    try:
        with httpx.Client(base_url=base_url, timeout=10) as client:
            resp = client.get("/api/status")
            resp.raise_for_status()
            return flatten_status(resp.json()).get("indexedDocuments", 0)
    except Exception:
        return 0


def _wait_for_backpressure(
    client: httpx.Client,
    high_watermark: int,
    low_watermark: int,
    poll_sec: float,
) -> None:
    """Block if the queue depth exceeds the high watermark."""
    while True:
        try:
            resp = client.get("/api/status")
            resp.raise_for_status()
            depth = flatten_status(resp.json()).get("pendingJobs", 0)
        except Exception:
            time.sleep(poll_sec)
            continue

        if depth < high_watermark:
            return

        log.debug(
            "Backpressure: queue depth %d >= %d, waiting for <= %d",
            depth, high_watermark, low_watermark,
        )
        while depth > low_watermark:
            time.sleep(poll_sec)
            try:
                resp = client.get("/api/status")
                resp.raise_for_status()
                depth = flatten_status(resp.json()).get("pendingJobs", 0)
            except Exception:
                continue
        return  # Queue drained below low watermark


def _submit_batch(client: httpx.Client, files: list[Path]) -> None:
    """Submit a batch of files for ingestion."""
    paths = [str(f.resolve()) for f in files]
    resp = client.post("/api/knowledge/ingest", json={"paths": paths})
    resp.raise_for_status()
