"""Closed-book corpus certification (tempdoc 635).

The certification gate that backs a corpus's "contamination-resistant" claim: a
corpus is certified clean only when the model **fails it closed-book** (cannot answer
from memory) below a threshold. This promotes the existing `closed_book_filter`
mechanism (tempdoc 624, a *run-time* query filter) to a *corpus-build-time*
certification — the corpus analog of `comparability` gating a run. The same tool is
the §C-5 self-generation guard: a synthetic corpus the model can guess fails here.

Verdict is **derived**, never hand-asserted (the R-1b non-negotiable). De-risk pass
confirmed discrimination: 0% on fabricated synthetic vs 38% on contaminated public.

Corpus-type-conditional certification (design §D.5): closed-book is the *shared
behavioral sanity check* across all corpus types; for `private-synthetic` it is also
the primary guarantee (fabricated facts are clean by construction, and closed-book≈0
confirms they are not guessable). For `post-cutoff` it is a sanity check only — the
primary guarantee there is time-partition + membership, out of scope for this gate.

Runs via the `claude` CLI (no JustSearch dev stack needed).
"""

from __future__ import annotations

import tempfile
from datetime import datetime, timezone
from pathlib import Path

_REQUIRED_PROVENANCE_KEYS = (
    "axis", "lang", "seed", "hops", "distractor_ratio", "semantic", "n_chains", "doc_words",
)

# Default: a corpus passes if at most 15% of its queries are answerable closed-book.
# Synthetic/fabricated corpora should be ~0%; public-news corpora ran ~38% (624 B2).
DEFAULT_THRESHOLD = 0.15


def retrieval_difficulty_label(ndcg_at_10: float) -> str:
    """Retrieval-difficulty bucket from a retrieval run's nDCG@10 (post-run).

    This is the §D.5 *fidelity* axis — how hard the corpus is *to retrieve* — and it
    can only be measured by an actual retrieval run, NOT by the no-stack closed-book
    cert. A low nDCG@10 means the retriever struggles (hard); a high one means the
    corpus is easy to retrieve. (Distinct from `memory_independence`, which measures
    how hard the corpus is to answer *from memory*.)
    """
    if ndcg_at_10 >= 0.8:
        return "easy"
    if ndcg_at_10 >= 0.5:
        return "moderate"
    return "hard"


def certify_corpus(
    queries: list[dict],
    *,
    model: str = "haiku",
    threshold: float = DEFAULT_THRESHOLD,
    concurrency: int = 8,
    now: str | None = None,
) -> dict:
    """Certify a corpus contamination-resistant via a closed-book pass.

    ``queries`` is the agent-format list of ``{query, answer, ...}`` dicts (answers are
    required — closed-book scores the model's recall against them). Returns a dict with
    two blocks for the corpus ``metadata.json``:

    - ``closed_book_certification`` — the measured verdict (closed-book accuracy, the
      model/date it was certified against, the threshold, and ``passed``).
    - ``fidelity`` — ``memory_independence`` (= 1 − closed-book accuracy: the share of
      queries that cannot be answered from memory, so genuinely need retrieval).
      ``retrieval_difficulty`` is left ``null`` here and populated **post-retrieval-run**
      from nDCG@10 (:func:`retrieval_difficulty_label`) — the no-stack cert measures the
      *memory* axis, not the *retrieval-difficulty* axis (§D.5); conflating the two was
      the review's Issue 2.
    """
    # Imported lazily so the module loads without inspect/agent extras.
    from jseval.utility_calibrate import closed_book_filter

    n = len(queries)
    retained, n_memorizable = closed_book_filter(
        queries, model=model, concurrency=concurrency
    )
    closed_book_accuracy = (n_memorizable / n) if n else 0.0
    retrieval_dependence = 1.0 - closed_book_accuracy
    passed = closed_book_accuracy <= threshold
    stamped = now or datetime.now(timezone.utc).date().isoformat()

    return {
        "closed_book_certification": {
            "closed_book_accuracy": round(closed_book_accuracy, 4),
            "n_queries": n,
            "n_memorizable": n_memorizable,
            "model": model,
            "date": stamped,
            "threshold": threshold,
            "passed": passed,
            "method": "closed-book-slot-guess",
        },
        "fidelity": {
            "memory_independence": round(retrieval_dependence, 4),
            "retrieval_difficulty": None,  # populated post-retrieval-run from nDCG@10
            "method": "closed-book",
        },
    }


def descriptor_collision_report(docs: list[dict], queries: list[dict] | None = None) -> dict:
    """Detect documents that share an identical ``title`` (tempdoc 664).

    A generated corpus mints each chain's distinctive descriptor into its head document's
    ``title``. When a distractor's randomly-drawn descriptor exactly reproduces a gold chain's
    descriptor, the two documents become textually indistinguishable on that signal — but qrels
    mark only the gold pair relevant, so the "wrong" document is a false negative, not a genuine
    hard negative. Confirmed empirically (tempdoc 664 confidence pass) on the committed
    ``golden/needle-burial-v1`` corpus: 24 colliding title groups across 51/280 docs, 7 of which
    involve a gold chain (qrel-corrupting); the other 17 are lower-severity distractor-only
    duplicates (wasted diversity, but no mislabeled qrel).

    ``queries`` (optional) supplies each query's ``evidence_ids`` (the gold doc IDs), letting a
    collision be classified gold-involved (qrel-corrupting -> fails) vs. distractor-only
    (reported, does not fail). Without ``queries``, collisions are still reported but none are
    classified gold-involved, so ``passed`` cannot go ``False`` on that basis alone.
    """
    gold_ids: set[str] = set()
    for q in queries or []:
        gold_ids.update(q.get("evidence_ids") or [])

    by_title: dict[str, list[str]] = {}
    for d in docs:
        title, doc_id = d.get("title"), d.get("_id")
        if not title or not doc_id:
            continue
        by_title.setdefault(title, []).append(doc_id)

    groups: list[dict] = []
    n_docs_involved = 0
    n_gold_involved = 0
    for title, ids in by_title.items():
        if len(ids) <= 1:
            continue
        involves_gold = any(i in gold_ids for i in ids)
        groups.append({"title": title, "doc_ids": ids, "involves_gold": involves_gold})
        n_docs_involved += len(ids)
        if involves_gold:
            n_gold_involved += 1

    return {
        "n_groups": len(groups),
        "n_docs_involved": n_docs_involved,
        "n_gold_involved": n_gold_involved,
        "groups": groups,
        "passed": n_gold_involved == 0,
        "method": "exact-title-match",
    }


def regeneration_determinism_report(generation_provenance: dict | None) -> dict:
    """Verify a corpus's "seeded -> reproducible" claim by actually regenerating it (tempdoc 664).

    Spawns ``corpus_generate.generate()`` in two SEPARATE Python processes with the corpus's own
    recorded ``generation_provenance`` and diffs the output — the exact experiment that found the
    original ``hash(axis)`` non-determinism bug (confirmed empirically: 280/280 docs differed
    between two "identical seed" runs pre-fix), now a standing certification-time check rather than
    a one-off pytest guard. Runs in separate processes deliberately: an in-process call would hide
    any per-process-random source (like the original bug) because such sources are stable *within*
    one process.

    Returns a skip verdict (``passed: None``) when the provenance is missing, hand-authored (not
    ``method: "procedural-fabricated"``), or incomplete (missing any of ``axis/lang/seed/hops/
    distractor_ratio/semantic/n_chains/doc_words`` — the full parameter set needed to reconstruct
    the exact ``generate()`` call). A skip is not a failure: it means this check cannot be run, not
    that the corpus is unreproducible. Confirmed cheap: a full ~280-doc regeneration costs ~0.1s, so
    running it twice at certify-time is not a performance concern.
    """
    method = "cross-process-regeneration-diff"
    gp = generation_provenance or {}
    if gp.get("method") != "procedural-fabricated":
        return {"passed": None, "method": method,
                "reason": f"not applicable: generation method is {gp.get('method')!r}, "
                          f"not 'procedural-fabricated'"}
    missing = [k for k in _REQUIRED_PROVENANCE_KEYS if k not in gp]
    if missing:
        return {"passed": None, "method": method,
                "reason": f"not applicable: generation_provenance is missing {missing} "
                          f"(a corpus certified before tempdoc 664's provenance-completeness fix)"}

    from . import corpus_generate as _cg

    with tempfile.TemporaryDirectory() as td:
        result = _cg.regenerate_and_diff(
            Path(td) / "run1", Path(td) / "run2",
            axis=gp["axis"], lang=gp["lang"], seed=gp["seed"], hops=gp["hops"],
            distractor_ratio=gp["distractor_ratio"], semantic=gp["semantic"],
            n_chains=gp["n_chains"], doc_words=gp["doc_words"],
        )

    if not result["ok"]:
        return {"passed": False, "method": method, "reason": result["error"]}

    return {
        "passed": not result["mismatched_files"],
        "method": method,
        "mismatched_files": result["mismatched_files"],
    }
