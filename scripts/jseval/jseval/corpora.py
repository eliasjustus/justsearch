"""Unified dataset loading (BEIR via ir-datasets, golden set, mixed-corpus)."""

from __future__ import annotations

import json
import warnings
from datetime import datetime, timezone
from pathlib import Path

import ir_datasets

from .types import CorpusMeta, QueryRecord

BEIR_DATASETS: dict[str, str] = {
    "scifact": "beir/scifact/test",
    "nfcorpus": "beir/nfcorpus/test",
    "arguana": "beir/arguana",
    "fiqa": "beir/fiqa/test",
    "webis-touche2020": "beir/webis-touche2020",
}

_STALENESS_DAYS = 90


def load(
    name: str,
    base_dir: Path | None = None,
) -> tuple[dict[str, QueryRecord], dict[str, dict[str, int]], CorpusMeta]:
    """Load a dataset.

    Returns:
        queries: {query_id: QueryRecord(text, annotations)}
        qrels:   {query_id: {doc_id: relevance_int}}
        meta:    CorpusMeta
    """
    if name in BEIR_DATASETS:
        return _load_beir(name)
    if name.startswith("golden/") or name.startswith("mixed/"):
        source = "golden" if name.startswith("golden/") else "mixed"
        return _load_local(name, source, base_dir or _default_base_dir())
    raise ValueError(
        f"Unknown dataset: {name!r}. "
        f"Expected a BEIR name ({', '.join(sorted(BEIR_DATASETS))}), "
        f"or a path starting with 'golden/' or 'mixed/'."
    )


# ---------------------------------------------------------------------------
# BEIR loading
# ---------------------------------------------------------------------------

def _load_beir(name: str) -> tuple[dict[str, QueryRecord], dict[str, dict[str, int]], CorpusMeta]:
    dataset = ir_datasets.load(BEIR_DATASETS[name])
    queries = {q.query_id: QueryRecord(text=q.text) for q in dataset.queries_iter()}
    qrels = dataset.qrels_dict()
    meta = CorpusMeta(
        name=name,
        source="beir",
        doc_count=dataset.docs_count(),
        query_count=len(queries),
    )
    return queries, qrels, meta


# ---------------------------------------------------------------------------
# Local loading (golden set / mixed-corpus)
# ---------------------------------------------------------------------------

def _load_local(
    name: str,
    source: str,
    base_dir: Path,
) -> tuple[dict[str, QueryRecord], dict[str, dict[str, int]], CorpusMeta]:
    dataset_dir = base_dir / name
    if not dataset_dir.is_dir():
        raise FileNotFoundError(f"Dataset directory not found: {dataset_dir}")

    queries = _read_queries_jsonl(dataset_dir / "queries.jsonl")
    qrels = _read_qrels_tsv(dataset_dir / "qrels" / "test.tsv")
    doc_count = _read_corpus_doc_count(dataset_dir / "corpus.jsonl")

    has_expected_terms = (dataset_dir / "expected_terms.json").is_file()

    meta = CorpusMeta(
        name=name,
        source=source,
        doc_count=doc_count,
        query_count=len(queries),
        has_expected_terms=has_expected_terms,
    )

    # Staleness checks for golden sets
    meta_path = dataset_dir / "metadata.json"
    if meta_path.is_file():
        _validate_golden_set(meta_path, queries, meta)

    return queries, qrels, meta


def _read_queries_jsonl(path: Path) -> dict[str, QueryRecord]:
    if not path.is_file():
        raise FileNotFoundError(f"Queries file not found: {path}")
    queries: dict[str, QueryRecord] = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line:
            continue
        obj = json.loads(line)
        qid = str(obj["_id"])
        text = obj["text"]
        annotations = {k: v for k, v in obj.items() if k not in ("_id", "text")}
        queries[qid] = QueryRecord(text=text, annotations=annotations)
    return queries


def _read_qrels_tsv(path: Path) -> dict[str, dict[str, int]]:
    if not path.is_file():
        raise FileNotFoundError(f"Qrels file not found: {path}")
    qrels: dict[str, dict[str, int]] = {}
    lines = path.read_text(encoding="utf-8").splitlines()
    if not lines:
        return qrels

    start = 0
    first = lines[0]
    if "query-id" in first.lower() or "query_id" in first.lower():
        start = 1

    for line in lines[start:]:
        line = line.strip()
        if not line:
            continue
        cols = line.split("\t")
        if len(cols) >= 4 and start == 0:
            # TREC format: query-id  iter  doc-id  relevance
            qid, did, rel = cols[0], cols[2], int(cols[3])
        elif len(cols) >= 3:
            # BEIR format: query-id  corpus-id  score
            qid, did, rel = cols[0], cols[1], int(cols[2])
        else:
            continue
        qrels.setdefault(qid, {})[did] = rel
    return qrels


def _read_corpus_doc_count(path: Path) -> int:
    if not path.is_file():
        return 0
    count = 0
    for line in path.read_text(encoding="utf-8").splitlines():
        if line.strip():
            count += 1
    return count


def _validate_golden_set(
    meta_path: Path,
    queries: dict[str, QueryRecord],
    meta: CorpusMeta,
) -> None:
    data = json.loads(meta_path.read_text(encoding="utf-8"))
    meta.version = data.get("version")
    meta.created_date = data.get("created_date")

    # Age warning
    created = data.get("created_date")
    if created:
        try:
            created_dt = datetime.fromisoformat(created).replace(tzinfo=timezone.utc)
            age_days = (datetime.now(timezone.utc) - created_dt).days
            if age_days > _STALENESS_DAYS:
                warnings.warn(
                    f"Golden set is {age_days} days old — consider refreshing judgments.",
                    stacklevel=3,
                )
        except (ValueError, TypeError):
            pass

    # Query type coverage
    dist = data.get("query_type_distribution", {})
    if dist:
        missing = [qt for qt, count in dist.items() if count == 0]
        if missing:
            warnings.warn(
                f"Golden set does not cover query types: {', '.join(missing)} "
                f"(0 of {len(queries)} queries).",
                stacklevel=3,
            )

    # tempdoc 635 — corpus-as-governed-artifact: parse the identity/provenance/
    # certification fields into CorpusMeta and warn on a self-demo corpus that is
    # un-certified or carries a failed/stale certification. Non-fatal (warnings only),
    # mirroring the staleness/coverage checks above.
    meta.contamination_class = data.get("contamination_class")
    meta.closed_book_certification = data.get("closed_book_certification")
    meta.fidelity = data.get("fidelity")
    meta.type_axis = data.get("type_axis")
    meta.suite = data.get("suite")
    meta.generation_provenance = data.get("generation_provenance")
    meta.corpus_signature = data.get("corpus_signature")

    # A self-demonstration corpus (suite member) must declare its contamination class
    # and carry a passing closed-book certification — else the "clean" claim is unbacked.
    if meta.suite:
        if not meta.contamination_class:
            warnings.warn(
                f"Self-demo corpus '{meta.name}' (suite={meta.suite}) declares no "
                f"contamination_class — the clean claim is uncertified.",
                stacklevel=3,
            )
        cert = meta.closed_book_certification or {}
        if not cert:
            warnings.warn(
                f"Self-demo corpus '{meta.name}' has no closed_book_certification — "
                f"run `jseval corpus-certify` before trusting it.",
                stacklevel=3,
            )
        elif not cert.get("passed", False):
            warnings.warn(
                f"Self-demo corpus '{meta.name}' FAILED closed-book certification "
                f"(closed_book_accuracy={cert.get('closed_book_accuracy')}) — it is "
                f"contamination-shortcuttable, not contamination-resistant.",
                stacklevel=3,
            )
        # The fidelity (retrieval-difficulty) axis is co-equal to the memory axis
        # (tempdoc 635 §D.5): a suite member must also be non-trivial yet retrievable.
        # Symmetric to the closed-book check: a MISSING verdict is flagged too (Issue-D —
        # the two axes must be validated alike, not just an explicit fail).
        fid = meta.fidelity or {}
        if "passed" not in fid:
            warnings.warn(
                f"Self-demo corpus '{meta.name}' has no fidelity verdict — "
                f"run `jseval corpus-fidelity` before trusting it (the retrieval-difficulty "
                f"axis is co-equal to closed-book).",
                stacklevel=3,
            )
        elif not fid.get("passed", False):
            warnings.warn(
                f"Self-demo corpus '{meta.name}' FAILED the fidelity gate "
                f"(nDCG@10={fid.get('retrieval_ndcg')} outside band {fid.get('band')}, "
                f"shortcut_leaks={fid.get('shortcut_leak_rate')}) — it is trivially easy, "
                f"broken, or shortcut-leaky, not a realistic-difficulty self-demo.",
                stacklevel=3,
            )


def _default_base_dir() -> Path:
    """Default base directory for local datasets (repo root / datasets)."""
    from ._paths import REPO_ROOT

    return REPO_ROOT / "datasets"
