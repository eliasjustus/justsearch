"""Output artifacts: metrics JSON, per-query JSON, TREC-format run files."""

from __future__ import annotations

import json
import logging
import shutil
from datetime import datetime, timezone
from pathlib import Path

from . import provenance
from .retriever import resolve_doc_id

log = logging.getLogger(__name__)


# Phase-3 LR4 projections consume telemetry NDJSON from the run dir, not
# the data dir. Copying at ``write_run`` time makes each run_dir self-
# describing and keeps the Projection contract on a single path argument.
_TELEMETRY_FILES_TO_MIRROR = (
    "traces.ndjson",
    "metrics.ndjson",
    "metrics-worker.ndjson",
)


def write_run(
    summary: dict,
    mode_results: dict,
    qrels: dict[str, dict[str, int]],
    output_dir: Path,
    query_records=None,
    data_dir: Path | None = None,
) -> Path:
    """Write all artifacts for a run. Returns the run directory path.

    When ``data_dir`` is provided, any telemetry NDJSON files present
    under ``<data_dir>/telemetry/`` are copied into ``run_dir`` so
    Phase-3 projections can consume them with nothing but the run_dir
    path. Missing files are skipped without error.
    """
    ts = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S")
    dataset_slug = summary["dataset"].replace("/", "_")
    run_dir = output_dir / f"{ts}_{dataset_slug}"
    run_dir.mkdir(parents=True, exist_ok=True)

    # summary.json
    _write_json(run_dir / "summary.json", summary)

    # manifest.json (tempdoc 400 LR1-a) — separate artefact for bisection
    # tooling; also embedded inside summary.json.
    manifest = summary.get("manifest")
    if isinstance(manifest, dict):
        _write_json(run_dir / "manifest.json", manifest)

    # qrels.json (tempdoc 400 LR4-e) — LR4-e + LR4-c need the relevance
    # judgments to annotate rank-diff projections; also useful for any
    # offline re-scoring against the exact query set this run used.
    _write_json(run_dir / "qrels.json", qrels)

    # Per-mode artifacts
    for mode, mr in mode_results.items():
        # per_query.json
        per_query_entries = _build_per_query_entries(
            mode, mr, qrels, query_records,
        )
        _write_json(run_dir / f"{mode}_per_query.json", per_query_entries)

        # TREC run file
        _write_trec_run(
            run_dir / f"{mode}_run.trec",
            mr["scored_docs"],
            run_name=f"jseval_{mode}",
        )

    # Mirror telemetry NDJSON into run_dir (Phase 3 LR4-*).
    copied = _mirror_telemetry(data_dir, run_dir) if data_dir is not None else []
    log.info(
        "Wrote %d artifacts to %s (telemetry mirrored: %s)",
        1 + 2 * len(mode_results), run_dir, copied or "none",
    )
    return run_dir


def _mirror_telemetry(data_dir: Path, run_dir: Path) -> list[str]:
    """Copy known telemetry NDJSON files from data_dir to run_dir.

    Tempdoc 400 §23.9.3 D-3 fix: the underlying NdjsonSpanExporter +
    NdjsonMetricExporter rotate files at a size threshold
    (``JUSTSEARCH_TELEMETRY_TRACES_MAX_MB``, default 10 MB). Rotation
    renames the active file to ``<stem>.<yyyyMMdd-HHmmss>.ndjson`` and
    starts a new active file. Pre-fix, this mirror only copied the
    **active** file — so ingest-heavy runs that crossed the rotation
    threshold mid-run lost up to 77% of their spans to any Layer-4
    projection reading from ``run_dir``. Measured in §23.9.3: ALL
    ``indexing.*`` spans (100%), 69% of ``encoder.ort_run`` +
    ``lease.acquire``, 44% of ``enrichment.batch``.

    Fix: for each canonical file name, also find rotated siblings
    matching ``<stem>.<ts>.<ext>`` and concatenate them (in
    timestamp-sorted filename order, rotated-first-then-active) into
    the mirrored file. Consumers that read a single
    ``run_dir/traces.ndjson`` now see the complete span stream.

    Returns the list of canonical names actually mirrored. Missing
    sources are silently skipped — the data dir may not have telemetry
    yet, and the run still succeeds.
    """
    telemetry_dir = data_dir / "telemetry"
    if not telemetry_dir.is_dir():
        return []
    copied: list[str] = []
    for name in _TELEMETRY_FILES_TO_MIRROR:
        sources = _collect_rotated_siblings(telemetry_dir, name)
        if not sources:
            continue
        dst = run_dir / name
        try:
            _concat_ndjson(sources, dst)
            copied.append(name)
        except OSError as e:
            log.debug("telemetry mirror failed for %s: %s", name, e)
    return copied


def _collect_rotated_siblings(telemetry_dir: Path, canonical_name: str) -> list[Path]:
    """Return the list of files to mirror for a given canonical NDJSON
    file, in timestamp-sorted filename order (rotated-oldest-first,
    then active last). Rotated siblings match the NdjsonSpanExporter
    pattern ``<stem>.<ts>.<ext>`` where ``<ts>`` is ``yyyyMMdd-HHmmss``.

    Active file is placed last so concatenation produces a
    chronologically-ordered stream — any span-time-based Layer-4
    projection (rate_timeline, encoder_drift) sees the same ordering
    it would have seen during live emission.
    """
    stem, _, ext = canonical_name.partition(".")
    if not ext:
        return []
    # Rotated pattern: <stem>.<ts>.<ext>
    rotated = sorted(
        p for p in telemetry_dir.glob(f"{stem}.*.{ext}")
        if p.name != canonical_name
    )
    active = telemetry_dir / canonical_name
    out: list[Path] = list(rotated)
    if active.is_file():
        out.append(active)
    return out


def _concat_ndjson(sources: list[Path], dst: Path) -> None:
    """Concatenate one or more NDJSON files into ``dst`` byte-for-byte.

    For the common single-source case, delegates to ``shutil.copy2``
    (preserves mtime). For the multi-source rotated case, opens ``dst``
    for writing + streams each source in order, inserting a newline
    between sources if the preceding file didn't end with one.
    """
    if len(sources) == 1:
        shutil.copy2(sources[0], dst)
        return
    with dst.open("wb") as out:
        for src in sources:
            with src.open("rb") as f:
                last_byte = b""
                while True:
                    chunk = f.read(1 << 20)  # 1 MiB
                    if not chunk:
                        break
                    out.write(chunk)
                    last_byte = chunk[-1:]
            if last_byte and last_byte != b"\n":
                out.write(b"\n")


def _build_per_query_entries(
    mode: str,
    mode_result: dict,
    qrels: dict[str, dict[str, int]],
    query_records=None,
) -> list[dict]:
    """Build per-query output entries by merging metrics with response fields."""
    per_query_metrics = mode_result["per_query_metrics"]
    raw_responses = mode_result["raw_responses"]

    # Index raw responses by query_id
    response_by_qid: dict[str, dict] = {}
    for r in raw_responses:
        qid = r.get("query_id")
        if qid:
            response_by_qid[qid] = r

    # Index scored docs by query_id (ordered)
    docs_by_qid: dict[str, list[str]] = {}
    for sd in mode_result["scored_docs"]:
        docs_by_qid.setdefault(sd.query_id, []).append(sd.doc_id)

    entries = []
    for qid in sorted(per_query_metrics.keys()):
        metrics = per_query_metrics[qid]
        resp = response_by_qid.get(qid, {})
        total_relevant = sum(1 for rel in qrels.get(qid, {}).values() if rel > 0)

        # Tempdoc 549 Phase E4: decision_kind comes SOLELY from the unified SearchTrace
        # (trace.decisionKind) — the legacy SearchIntrospection.decision was retired.
        trace = resp.get("searchTrace") or {}
        decision_kind = trace.get("decisionKind") if isinstance(trace, dict) else None

        # Tempdoc 643: per-hit judge-arbitration signals (CE score + per-leg ranks), read from
        # each hit's own trace slice — always-on structural data (no debug/include_detail flag
        # needed). Feeds the U1/U2 pre-design measurements (rank distribution, CE-on/off signal
        # separation); not consumed by existing per-query metrics. Doc-id resolution failures are
        # skipped (mirroring retriever.retrieve's allow_errors fallback at retriever.py:133-142)
        # rather than aborting the whole per-query artifact write over one malformed hit.
        judge_signals = []
        for h in (resp.get("results") or []):
            if not isinstance(h, dict):
                continue
            try:
                doc_id = resolve_doc_id(h)
            except ValueError as e:
                log.warning("judgeSignals: doc ID resolution failed for query %s: %s", qid, e)
                continue
            judge_signals.append({"docId": doc_id, **provenance.extract_judge_signals(h)})

        entry = {
            "qid": qid,
            "query": query_records[qid].text if query_records and qid in query_records else None,
            "mode": mode,
            "ndcgAtK": metrics.get("nDCG@10"),
            "apAtK": metrics.get("AP@10"),
            "mrrAtK": metrics.get("RR@10"),
            "recallAtK": metrics.get("R@10"),
            "p1AtK": metrics.get("P@1"),
            "predictedDocIds": docs_by_qid.get(qid, []),
            "totalRelevant": total_relevant,
            "tookMs": resp.get("tookMs"),
            "totalHits": resp.get("totalHits"),
            # Tempdoc 549 Phase E4: effectiveMode from the trace (flat field retired).
            "effectiveMode": trace.get("effectiveMode") if isinstance(trace, dict) else None,
            "vectorBlocked": resp.get("vectorBlocked"),
            "vectorBlockedReason": resp.get("vectorBlockedReason"),
            "hybridFallback": resp.get("hybridFallback"),
            "hybridFallbackReason": resp.get("hybridFallbackReason"),
            "chunkMergeApplied": resp.get("chunkMergeApplied"),
            "chunkMergeReason": resp.get("chunkMergeReason"),
            # 525: decision_kind dimension for stratified eval bucketing.
            "decision_kind": decision_kind,
            "error": resp.get("error"),
            # Tempdoc 643: per-hit {docId, bm25/splade/dense rank+score, fusion_score, ce_score}.
            "judgeSignals": judge_signals,
        }
        entries.append(entry)

    return entries


def _write_trec_run(path: Path, scored_docs: list, run_name: str) -> None:
    """Write a TREC-format run file: qid Q0 docid rank score run_name."""
    # Group by qid and assign ranks
    by_qid: dict[str, list] = {}
    for sd in scored_docs:
        by_qid.setdefault(sd.query_id, []).append(sd)

    lines = []
    for qid in sorted(by_qid.keys()):
        docs = sorted(by_qid[qid], key=lambda d: d.score, reverse=True)
        for rank, sd in enumerate(docs, 1):
            lines.append(f"{qid} Q0 {sd.doc_id} {rank} {sd.score:.6f} {run_name}")

    path.write_text("\n".join(lines) + "\n" if lines else "", encoding="utf-8")


def _write_json(path: Path, data) -> None:
    path.write_text(json.dumps(data, indent=2, default=str), encoding="utf-8")
