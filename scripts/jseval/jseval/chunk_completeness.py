"""Eval-time chunk-completeness validity guard (tempdoc 718).

A fresh index build can silently ship with its chunk sub-system absent (tempdoc 717): a
degenerate build reads bit-for-bit identical to a legitimately chunk-free corpus at the
*pipeline output* layer -- ``chunkDocCount=0``, ``chunkVectorCoveragePercent=0.0`` both ways
(``LuceneRuntimeTypes.coveragePercent()`` returns ``0.0`` when ``total==0``, never a vacuous
100%). No attempted-vs-completed counter exists, so the *observed* signal alone cannot
distinguish "short docs, nothing to chunk" from "build degenerated."

The disambiguator is an OFFLINE signal the degeneracy can't touch: :func:`expected_chunk_docs`
computes, straight from the corpus TEXT, how many documents SHOULD have produced chunk docs --
before/independent of any ingest, so a broken enrichment pipeline can never move this number.
:func:`chunk_completeness_verdict` is the pure comparison of that offline expectation against
the post-enrichment observed counts; :mod:`jseval.ratchet_kernel` enforces it at the gate seam.
"""

from __future__ import annotations

import json
from dataclasses import dataclass, field
from pathlib import Path

# Mirrors ChunkDocumentWriter.CHUNK_THRESHOLD_CHARS -- a document's extracted content only
# produces chunk docs when its length is >= this many characters (modules/worker-services/src/
# main/java/io/justsearch/indexerworker/rag/ChunkDocumentWriter.java:28, applied identically by
# IndexingDocumentOps.java:352). DUAL-SOURCE-OF-TRUTH RISK (named in tempdoc 718 §Settled
# design): this is a jseval-side mirror of a Java constant and will silently drift if the Java
# value ever changes. Follow-up filed to expose the threshold via /api/status so this oracle
# reads it instead of mirroring it (see docs/observations.d/).
CHUNK_THRESHOLD_CHARS = 2000


def expected_chunk_docs(corpus_jsonl_path: Path | str) -> int:
    """Offline (spoof-proof) count of corpus docs that SHOULD produce chunk docs.

    Reads a BEIR-format ``corpus.jsonl`` (``{"_id", "title", "text"}`` lines -- the format
    ``corpus_build.build_golden`` writes for every golden/mixed self-demo corpus) and
    reconstructs each doc's ingested content exactly as
    ``jseval.materialize.materialize()`` does: ``f"{title}\\n\\n{text}"`` when a title is
    present, else bare ``text`` (materialize.py:57) -- the same string
    ``ChunkDocumentWriter``/``IndexingDocumentOps`` apply the threshold to at index time.
    A doc counts when ``len(content) >= CHUNK_THRESHOLD_CHARS``.

    This is a pure read of the corpus TEXT, computed before/independent of any ingest --
    the enrichment pipeline's own failure can never move it. ``expected_chunk_docs() > 0``
    means "this corpus SHOULD produce chunk docs."

    Returns ``0`` when ``corpus_jsonl_path`` doesn't exist -- e.g. a BEIR dataset materialized
    on the fly via ``ir_datasets`` (no local ``corpus.jsonl``, tempdoc 718 scope: golden/mixed
    self-demo corpora, where the twice-observed 717 degeneracy was measured). A corpus this
    function cannot read has nothing to expect chunks from, so BEIR runs correctly get a
    ``chunk-free`` verdict (never gated) rather than a spurious "0 expected."
    """
    path = Path(corpus_jsonl_path)
    if not path.is_file():
        return 0
    count = 0
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line:
            continue
        doc = json.loads(line)
        title = doc.get("title") or ""
        text = doc.get("text") or ""
        content = f"{title}\n\n{text}" if title else text
        if len(content) >= CHUNK_THRESHOLD_CHARS:
            count += 1
    return count


@dataclass
class ChunkCompletenessResult:
    """Verdict for the eval-time chunk-completeness validity guard (tempdoc 718).

    ``verdict`` is one of ``"ok"`` (expected>0, observed healthy), ``"chunk-free"``
    (expected==0 -- legitimately nothing to chunk, not a degeneracy), or ``"degenerate"``
    (expected>0 but the observed signals say the chunk sub-system didn't run). ``reasons`` is
    NEVER boolean-only (matches :class:`jseval.types.ComparabilityResult` /
    :func:`jseval.ratchet_kernel.compare_engine_sets`) -- always a legible list, even on
    ``ok``/``chunk-free`` (documenting *why*, not just *that*).
    """

    expected: int
    observed: int
    verdict: str
    reasons: list[str] = field(default_factory=list)


DEFAULT_COVERAGE_FLOOR = 99.9


def chunk_completeness_verdict(
    expected_chunk_docs: int,
    observed_chunk_doc_count: int,
    observed_coverage_pct: float | None,
    chunk_merge_observed: bool,
    *,
    coverage_floor: float = DEFAULT_COVERAGE_FLOOR,
) -> ChunkCompletenessResult:
    """Pure verdict comparing the offline expectation to the post-enrichment observation.

    ``expected_chunk_docs>0 AND (observed_chunk_doc_count==0 OR observed_coverage_pct<
    coverage_floor OR NOT chunk_merge_observed)`` -> ``degenerate`` (fail closed).
    ``expected_chunk_docs==0`` -> ``chunk-free`` (legitimately, pass) -- checked FIRST so a
    genuinely chunk-free corpus (e.g. all-short-doc golden set) never reads the observed
    signals at all, matching the anti-spoof design: the offline signal alone decides
    applicability. Otherwise (both signals present and consistent) -> ``ok``.

    ``observed_coverage_pct=None`` (the field absent from ``/api/status`` -- an older backend,
    or chunking disabled) is treated as ``0.0`` for the comparison, mirroring
    ``LuceneRuntimeTypes.coveragePercent()``'s own "never a vacuous 100%" contract when
    ``total==0``. ``chunk_merge_observed`` is a query-time corroborator from
    ``per_mode.vector.pipeline_tracking.observed`` -- callers that did not run ``vector`` mode
    this run should pass ``True`` (corroborator not applicable, never a strike on its own).
    """
    if expected_chunk_docs <= 0:
        return ChunkCompletenessResult(
            expected=expected_chunk_docs,
            observed=observed_chunk_doc_count,
            verdict="chunk-free",
            reasons=[
                "expected_chunk_docs == 0 -- no corpus doc reaches the chunk threshold, "
                "so 0 observed chunk docs is the correct outcome, not a degeneracy"
            ],
        )

    coverage = observed_coverage_pct if observed_coverage_pct is not None else 0.0
    reasons: list[str] = []
    if observed_chunk_doc_count <= 0:
        reasons.append(
            f"expected_chunk_docs={expected_chunk_docs} > 0 but observed chunk_doc_count == 0"
        )
    if coverage < coverage_floor:
        reasons.append(
            f"observed chunkVectorCoveragePercent={coverage} < floor {coverage_floor}"
        )
    if not chunk_merge_observed:
        reasons.append(
            "chunk_merge absent from vector mode's pipeline_tracking.observed "
            "(query-time corroborator)"
        )

    if reasons:
        return ChunkCompletenessResult(
            expected=expected_chunk_docs,
            observed=observed_chunk_doc_count,
            verdict="degenerate",
            reasons=reasons,
        )
    return ChunkCompletenessResult(
        expected=expected_chunk_docs,
        observed=observed_chunk_doc_count,
        verdict="ok",
        reasons=[
            f"expected_chunk_docs={expected_chunk_docs}, observed_chunk_doc_count="
            f"{observed_chunk_doc_count}, coverage={coverage} >= floor {coverage_floor}, "
            f"chunk_merge observed"
        ],
    )
