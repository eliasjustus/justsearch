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
import json
import hashlib

_REQUIRED_PROVENANCE_KEYS = (
    "axis", "lang", "seed", "hops", "distractor_ratio", "semantic", "n_chains", "doc_words",
)

# Default: a corpus passes if at most 15% of its queries are answerable closed-book.
# Synthetic/fabricated corpora should be ~0%; public-news corpora ran ~38% (624 B2).
DEFAULT_THRESHOLD = 0.15


def certify_materialized_family(
    datasets_dir: str | Path,
    *,
    member: str,
    dataset_names: dict[str, dict[str, str]],
    commitment_dirs: dict[str, dict[str, str | Path]],
) -> dict:
    """Run every zero-cost structural certification over a 707 member family.

    ``dataset_names`` maps size strings to ``{verbose, short-natural}`` dataset
    names. Scientific/model-backed gates are reported separately and never
    inferred from these structural checks.
    """
    from .corpus_identity import corpus_signature

    base = Path(datasets_dir)
    results: dict[str, dict] = {}
    datasets: dict[tuple[str, str], dict] = {}
    for size, variants in sorted(dataset_names.items(), key=lambda item: int(item[0])):
        results[size] = {}
        for variant, name in sorted(variants.items()):
            root = base / "mixed" / name
            metadata = json.loads((root / "metadata.json").read_text(encoding="utf-8"))
            docs = [
                json.loads(line)
                for line in (root / "corpus.jsonl").read_text(encoding="utf-8").splitlines()
                if line.strip()
            ]
            queries = json.loads((root / "queries.json").read_text(encoding="utf-8"))
            actual_signature = corpus_signature(root)
            regeneration = regeneration_determinism_report(
                metadata.get("generation_provenance"))
            commitment = _validate_commitment(
                Path(commitment_dirs[size][variant]),
                metadata.get("generation_provenance") or {},
            )
            collisions = descriptor_collision_report(docs, queries)
            variant_values = {query.get("query_variant") for query in queries}
            family_ids = [query.get("query_family_id") for query in queries]
            checks = {
                "size": len(docs) == int(size) == metadata.get("corpus_size"),
                "signature": bool(actual_signature)
                and actual_signature == metadata.get("corpus_signature"),
                "query_variant": variant_values == {variant},
                "query_family_ids": (
                    len(family_ids) == len(set(family_ids))
                    and all(family_ids)
                ),
                "cross_process_regeneration": regeneration.get("passed") is True,
                "immutable_commitment": commitment.get("passed") is True,
                "descriptor_collision": collisions.get("passed") is True,
            }
            results[size][variant] = {
                "dataset": f"mixed/{name}",
                "corpus_signature": actual_signature,
                "query_count": len(queries),
                "checks": checks,
                "regeneration": regeneration,
                "commitment": commitment,
                "descriptor_collision": {
                    key: collisions[key]
                    for key in ("n_groups", "n_docs_involved", "n_gold_involved", "passed", "method")
                },
                "passed": all(checks.values()),
            }
            datasets[(size, variant)] = {
                "docs": {doc["_id"]: doc for doc in docs},
                "queries": queries,
                "qrels": (root / "qrels" / "test.tsv").read_bytes(),
            }

    family_checks = {}
    for variant in ("verbose", "short-natural"):
        small = datasets[("1000", variant)]
        large = datasets[("10000", variant)]
        family_checks[variant] = {
            "queries_identical_across_sizes": small["queries"] == large["queries"],
            "qrels_identical_across_sizes": small["qrels"] == large["qrels"],
            "one_k_docs_are_subset_of_ten_k": all(
                large["docs"].get(doc_id) == doc for doc_id, doc in small["docs"].items()),
        }
    verbose = datasets[("1000", "verbose")]["queries"]
    short = datasets[("1000", "short-natural")]["queries"]
    family_checks["strata"] = {
        "same_family_ids": [q["query_family_id"] for q in verbose]
        == [q["query_family_id"] for q in short],
        "same_answers_and_evidence": [
            (q["answer"], q["evidence_ids"]) for q in verbose
        ] == [(q["answer"], q["evidence_ids"]) for q in short],
        "distinct_query_text": all(
            left["query"] != right["query"] for left, right in zip(verbose, short)),
        "same_corpus_and_qrels_per_size": all(
            datasets[(size, "verbose")]["docs"]
            == datasets[(size, "short-natural")]["docs"]
            and datasets[(size, "verbose")]["qrels"]
            == datasets[(size, "short-natural")]["qrels"]
            for size in ("1000", "10000")
        ),
    }
    structural_passed = (
        all(cell["passed"] for variants in results.values() for cell in variants.values())
        and all(
            value
            for checks in family_checks.values()
            for value in checks.values()
        )
    )
    return {
        "schema": "707-corpus-structural-certification.v1",
        "member": member,
        "status": "structurally-certified" if structural_passed else "failed",
        "datasets": results,
        "family_checks": family_checks,
        "structural_passed": structural_passed,
        "scientific_gates": {
            "closed_book": "pending-model-run",
            "retrieval_calibration": "pending-backend-run",
            "union_recall": "pending-backend-run",
            "leak_floor": "pending-backend-run",
        },
        "fully_certified": False,
    }


def _sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def _validate_commitment(root: Path, materialized_provenance: dict) -> dict:
    """Verify committed recipe/input bytes and tie them to materialized metadata."""
    manifest_path = root / "commitment.v1.json"
    if not manifest_path.is_file():
        return {"passed": False, "reason": "missing commitment.v1.json"}
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    if manifest.get("schema") != "707-corpus-commitment.v1":
        return {"passed": False, "reason": "unsupported commitment schema"}
    files = manifest.get("files") or {}
    required = {
        "recipe.json", "fabricated-docs.jsonl", "fabricated-queries.json",
        "fabricated-meta.json",
    }
    if set(files) != required:
        return {"passed": False, "reason": "commitment file matrix is incomplete"}
    if any(not (root / name).is_file() or _sha256(root / name) != digest
           for name, digest in files.items()):
        return {"passed": False, "reason": "committed input digest mismatch"}
    recipe = json.loads((root / "recipe.json").read_text(encoding="utf-8"))
    projected = dict(materialized_provenance)
    projected.pop("fabrication_provenance", None)
    if recipe != projected:
        return {"passed": False, "reason": "recipe disagrees with materialized provenance"}
    evidence = recipe.get("assembly_determinism") or {}
    if (
        recipe.get("method") != "real-text-injection-v1"
        or evidence.get("passed") is not True
        or evidence.get("method") != "cross-process-regeneration-diff"
        or evidence.get("digest") != recipe.get("assembled_digest")
    ):
        return {"passed": False, "reason": "invalid cross-process regeneration evidence"}
    return {
        "passed": True,
        "manifest_sha256": _sha256(manifest_path),
        "recipe_sha256": files["recipe.json"],
    }
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
    if gp.get("method") == "real-text-injection-v1":
        evidence = gp.get("assembly_determinism") or {}
        valid = (
            evidence.get("passed") is True
            and evidence.get("method") == "cross-process-regeneration-diff"
            and evidence.get("digest") == gp.get("assembled_digest")
            and isinstance(evidence.get("digest"), str)
            and len(evidence.get("digest")) == 64
        )
        return {
            "passed": valid,
            "method": evidence.get("method"),
            "digest": evidence.get("digest"),
            "reason": None if valid else "invalid or untied assembly determinism evidence",
        }
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
