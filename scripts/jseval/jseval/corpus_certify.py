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

from datetime import datetime, timezone

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
