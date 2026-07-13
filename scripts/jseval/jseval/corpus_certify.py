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
import base64
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
SCIENTIFIC_GATES = (
    "closed_book", "retrieval_calibration", "union_recall", "leak_floor",
)
_CELL_CHECKS = {
    "size", "signature", "query_variant", "query_family_ids",
    "cross_process_regeneration", "immutable_commitment", "descriptor_collision",
}
_FAMILY_CHECKS = {
    "queries_identical_across_sizes", "qrels_identical_across_sizes",
    "one_k_docs_are_subset_of_ten_k",
}
_STRATA_CHECKS = {
    "same_family_ids", "same_answers_and_evidence", "distinct_query_text",
    "same_corpus_and_qrels_per_size",
}
_HEX = frozenset("0123456789abcdef")
SCIENTIFIC_POLICY_PATH = Path(__file__).parents[1] / "707-corpus-certification-policy.v1.json"


def _is_sha256(value: object) -> bool:
    return isinstance(value, str) and len(value) == 64 and set(value) <= _HEX


def _is_git_sha(value: object) -> bool:
    return isinstance(value, str) and len(value) == 40 and set(value) <= _HEX


def certify_materialized_family(
    datasets_dir: str | Path,
    *,
    member: str,
    dataset_names: dict[str, dict[str, str]],
    commitment_dirs: dict[str, dict[str, str | Path]],
    scientific_evidence: dict[str, dict[str, dict[str, str | Path]]] | None = None,
    scientific_policy_path: str | Path | None = None,
) -> dict:
    """Run every zero-cost structural certification over a 707 member family.

    ``dataset_names`` maps size strings to ``{verbose, short-natural}`` dataset
    names. Scientific/model-backed gates are reported separately and never
    inferred from these structural checks.
    """
    from .corpus_identity import corpus_signature

    base = Path(datasets_dir)
    policy_snapshot = None
    policy_cells = {}
    if scientific_evidence:
        policy_path = Path(scientific_policy_path or SCIENTIFIC_POLICY_PATH)
        raw_policy = policy_path.read_bytes()
        policy = json.loads(raw_policy.decode("utf-8"))
        policy_cells = _active_scientific_policy_cells(policy, member=member)
        policy_snapshot = {
            "sha256": hashlib.sha256(raw_policy).hexdigest(),
            "artifact_base64": base64.b64encode(raw_policy).decode("ascii"),
        }
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
            query_gold_sha256 = _sha256(root / "queries.json")
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
            gate_evidence = {
                gate: _validate_scientific_evidence(
                    Path(scientific_evidence[size][variant][gate]),
                    member=member,
                    dataset=f"mixed/{name}",
                    corpus_signature=actual_signature,
                    query_gold_sha256=query_gold_sha256,
                    gate=gate,
                    query_count=len(queries),
                    threshold=_policy_threshold(
                        policy_cells,
                        dataset=f"mixed/{name}",
                        corpus_signature=actual_signature,
                        query_gold_sha256=query_gold_sha256,
                        query_count=len(queries),
                        gate=gate,
                    ),
                )
                for gate in SCIENTIFIC_GATES
            } if scientific_evidence else {
                gate: {
                    "passed": False,
                    "status": "pending-model-run" if gate == "closed_book"
                    else "pending-backend-run",
                }
                for gate in SCIENTIFIC_GATES
            }
            structural_cell_passed = all(checks.values())
            results[size][variant] = {
                "dataset": f"mixed/{name}",
                "corpus_signature": actual_signature,
                "query_gold_sha256": query_gold_sha256,
                "query_count": len(queries),
                "checks": checks,
                "regeneration": regeneration,
                "commitment": commitment,
                "descriptor_collision": {
                    key: collisions[key]
                    for key in ("n_groups", "n_docs_involved", "n_gold_involved", "passed", "method")
                },
                "scientific_gates": gate_evidence,
                "passed": structural_cell_passed,
                "fully_certified": (
                    structural_cell_passed
                    and all(item.get("passed") is True for item in gate_evidence.values())
                ),
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
    fully_certified = (
        structural_passed
        and all(
            cell["fully_certified"]
            for variants in results.values() for cell in variants.values()
        )
    )
    gate_status = {
        gate: (
            "passed" if all(
                cell["scientific_gates"][gate].get("passed") is True
                for variants in results.values() for cell in variants.values()
            ) else (
                "failed" if scientific_evidence else
                ("pending-model-run" if gate == "closed_book" else "pending-backend-run")
            )
        )
        for gate in SCIENTIFIC_GATES
    }
    report = {
        "schema": "707-corpus-structural-certification.v1",
        "member": member,
        "status": (
            "fully-certified" if fully_certified else
            ("structurally-certified" if structural_passed else "failed")
        ),
        "datasets": results,
        "family_checks": family_checks,
        "structural_passed": structural_passed,
        "scientific_gates": gate_status,
        "fully_certified": fully_certified,
    }
    if policy_snapshot is not None:
        report["scientific_policy"] = policy_snapshot
    return report


def _active_scientific_policy_cells(policy: object, *, member: str) -> dict[str, dict]:
    """Return the exact active pre-run policy cells for one member."""
    if not isinstance(policy, dict) or set(policy) != {
        "schema", "status", "unresolved", "required_cells",
    } or (
        policy.get("schema") != "707-corpus-certification-policy.v1"
        or policy.get("status") != "active"
        or policy.get("unresolved") != []
        or not isinstance(policy.get("required_cells"), list)
    ):
        raise ValueError("707 scientific policy is draft or malformed")
    selected = [cell for cell in policy["required_cells"] if cell.get("member") == member]
    if len(selected) != 4:
        raise ValueError("707 scientific policy must contain exactly four cells for the member")
    by_dataset = {}
    for cell in selected:
        if not isinstance(cell, dict) or set(cell) != {
            "member", "dataset", "corpus_signature", "query_gold_sha256",
            "query_count", "thresholds",
        } or cell.get("dataset") in by_dataset:
            raise ValueError("707 scientific policy contains a malformed or duplicate cell")
        thresholds = cell.get("thresholds") or {}
        if set(thresholds) != set(SCIENTIFIC_GATES):
            raise ValueError("707 scientific policy gate matrix is incomplete")
        by_dataset[cell["dataset"]] = cell
    return by_dataset


def _policy_threshold(
    cells: dict[str, dict], *, dataset: str, corpus_signature: str,
    query_gold_sha256: str, query_count: int, gate: str,
) -> dict:
    cell = cells.get(dataset) or {}
    if (
        cell.get("corpus_signature") != corpus_signature
        or cell.get("query_gold_sha256") != query_gold_sha256
        or cell.get("query_count") != query_count
    ):
        raise ValueError(f"707 scientific policy identity mismatch for {dataset}")
    threshold = (cell.get("thresholds") or {}).get(gate)
    if not isinstance(threshold, dict):
        raise ValueError(f"707 scientific policy threshold missing for {dataset}:{gate}")
    return threshold


def _sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def build_scientific_measurement_artifact(
    *,
    member: str,
    dataset: str,
    dataset_dir: str | Path,
    gate: str,
    measurement_path: str | Path,
    run_manifest_path: str | Path | None = None,
) -> dict:
    """Package canonical gate outputs into the self-contained 707 evidence envelope."""
    if gate not in SCIENTIFIC_GATES:
        raise ValueError(f"unsupported scientific gate: {gate}")
    from .corpus_identity import corpus_signature

    root = Path(dataset_dir)
    queries_path = root / "queries.json"
    queries = json.loads(queries_path.read_text(encoding="utf-8"))
    signature = corpus_signature(root)
    if not _is_sha256(signature):
        raise ValueError("materialized corpus signature is unavailable")
    measurement = Path(measurement_path)
    json.loads(measurement.read_text(encoding="utf-8"))
    manifest = Path(run_manifest_path) if run_manifest_path else None
    if gate == "closed_book" and manifest is not None:
        raise ValueError("closed_book evidence must not supply a run manifest")
    if gate != "closed_book" and manifest is None:
        raise ValueError(f"{gate} evidence requires a run manifest")
    if manifest is not None:
        json.loads(manifest.read_text(encoding="utf-8"))

    def blob(path: Path) -> dict:
        raw = path.read_bytes()
        return {
            "sha256": hashlib.sha256(raw).hexdigest(),
            "artifact_base64": base64.b64encode(raw).decode("ascii"),
        }

    return {
        "schema": "707-corpus-scientific-measurement.v1",
        "member": member,
        "dataset": dataset,
        "corpus_signature": signature,
        "query_gold_sha256": _sha256(queries_path),
        "query_count": len(queries),
        "gate": gate,
        "source_artifacts": {
            "measurement": blob(measurement),
            "run_manifest": blob(manifest) if manifest is not None else None,
        },
    }


def _validate_scientific_evidence(
    path: Path,
    *,
    member: str,
    dataset: str,
    corpus_signature: str,
    query_gold_sha256: str,
    gate: str,
    query_count: int,
    threshold: dict,
) -> dict:
    """Validate one typed 707 measurement, derive its verdict, and bind its bytes."""
    if not path.is_file():
        return {"passed": False, "status": "missing", "path": path.name}
    try:
        artifact = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return {"passed": False, "status": "malformed", "path": path.name}
    valid, observed, threshold = _derive_scientific_verdict(
        artifact,
        member=member,
        dataset=dataset,
        corpus_signature=corpus_signature,
        query_gold_sha256=query_gold_sha256,
        gate=gate,
        query_count=query_count,
        threshold=threshold,
    )
    raw = path.read_bytes()
    return {
        "passed": valid,
        "status": "passed" if valid else "failed",
        "sha256": hashlib.sha256(raw).hexdigest(),
        "artifact_base64": base64.b64encode(raw).decode("ascii"),
        "observed": observed,
        "threshold": threshold,
    }


def _derive_scientific_verdict(
    artifact: object,
    *,
    member: str,
    dataset: str,
    corpus_signature: str,
    query_gold_sha256: str,
    gate: str,
    query_count: int,
    threshold: dict,
) -> tuple[bool, object, object]:
    """Parse a gate-specific canonical measurement and recompute pass/fail."""
    if not isinstance(artifact, dict) or set(artifact) != {
        "schema", "member", "dataset", "corpus_signature", "query_gold_sha256", "query_count",
        "gate", "source_artifacts",
    }:
        return False, None, None
    sources = artifact.get("source_artifacts")
    common = (
        artifact.get("schema") == "707-corpus-scientific-measurement.v1"
        and artifact.get("member") == member
        and artifact.get("dataset") == dataset
        and artifact.get("corpus_signature") == corpus_signature
        and artifact.get("query_gold_sha256") == query_gold_sha256
        and _is_sha256(query_gold_sha256)
        and artifact.get("query_count") == query_count
        and artifact.get("gate") == gate
        and isinstance(sources, dict)
        and isinstance(threshold, dict)
    )
    if not common:
        return False, None, threshold

    try:
        measurement_source = _decode_source_blob(sources.get("measurement"))
        manifest_source = (
            None if gate == "closed_book"
            else _decode_source_blob(sources.get("run_manifest"))
        )
    except (TypeError, ValueError, UnicodeDecodeError, json.JSONDecodeError):
        return False, None, threshold
    if set(sources) != {"measurement", "run_manifest"}:
        return False, None, threshold
    if gate == "closed_book":
        if sources.get("run_manifest") is not None:
            return False, None, threshold
        source = (measurement_source or {}).get("closed_book_certification") or {}
        measurement = {
            key: source.get(key)
            for key in (
                "closed_book_accuracy", "n_queries", "n_memorizable",
                "model", "date", "method",
            )
        }
    else:
        from .manifest import _compute_cohort_hash

        if not isinstance(manifest_source, dict) or (
            manifest_source.get("dataset") != dataset
            or not _is_git_sha(manifest_source.get("git_sha"))
            or (manifest_source.get("corpus_identity") or {}).get("signature")
            != corpus_signature
            or manifest_source.get("manifest_hash") != _compute_cohort_hash(manifest_source)
        ):
            return False, None, threshold
        if gate == "retrieval_calibration":
            source = measurement_source or {}
            measurement = {
                key: source.get(key)
                for key in (
                    "retrieval_ndcg", "retrieval_ndcg_by_mode", "retrieval_mode",
                    "comparable", "comparability_reasons", "shortcut_leak_rate",
                    "n_shortcut_leaks", "n_shortcut_queries", "method",
                )
            }
            measurement.update({
                "n_queries": query_count,
                "run_manifest_sha256": sources["run_manifest"]["sha256"],
            })
        else:
            aggregate = (measurement_source or {}).get("aggregate") or {}
            measurement = {
                "status": (measurement_source or {}).get("status"),
                "leg_union_recall": aggregate.get("leg_union_recall"),
                "leak_rate": aggregate.get("leak_rate"),
                "n_queries": query_count,
                "run_manifest_sha256": sources["run_manifest"]["sha256"],
                "method": "staged_recall_accounting",
            }
    if not isinstance(measurement, dict):
        return False, measurement, threshold

    number = lambda value: isinstance(value, (int, float)) and not isinstance(value, bool)
    if gate == "closed_book":
        if set(measurement) != {
            "closed_book_accuracy", "n_queries", "n_memorizable", "model",
            "date", "method",
        } or set(threshold) != {"maximum_accuracy"}:
            return False, measurement, threshold
        accuracy = measurement.get("closed_book_accuracy")
        n_queries = measurement.get("n_queries")
        n_memorizable = measurement.get("n_memorizable")
        maximum = threshold.get("maximum_accuracy")
        structural = (
            number(accuracy) and isinstance(n_queries, int) and not isinstance(n_queries, bool)
            and isinstance(n_memorizable, int) and not isinstance(n_memorizable, bool)
            and n_queries == query_count > 0 and 0 <= n_memorizable <= n_queries
            and abs(float(accuracy) - round(n_memorizable / n_queries, 4)) <= 1e-9
            and isinstance(measurement.get("model"), str) and bool(measurement["model"])
            and isinstance(measurement.get("date"), str) and bool(measurement["date"])
            and measurement.get("method") == "closed-book-slot-guess"
            and number(maximum) and 0 <= float(maximum) <= 1
        )
        return bool(structural and float(accuracy) <= float(maximum)), measurement, threshold

    if gate == "retrieval_calibration":
        if set(measurement) != {
            "retrieval_ndcg", "retrieval_ndcg_by_mode", "retrieval_mode",
            "comparable", "comparability_reasons", "shortcut_leak_rate",
            "n_shortcut_leaks", "n_shortcut_queries", "n_queries",
            "run_manifest_sha256", "method",
        } or set(threshold) != {"ndcg_band", "shortcut_leak_rate_max"}:
            return False, measurement, threshold
        ndcg = measurement.get("retrieval_ndcg")
        by_mode = measurement.get("retrieval_ndcg_by_mode")
        mode = measurement.get("retrieval_mode")
        leak = measurement.get("shortcut_leak_rate")
        band = threshold.get("ndcg_band")
        leak_max = threshold.get("shortcut_leak_rate_max")
        structural = (
            number(ndcg) and isinstance(by_mode, dict) and isinstance(mode, str)
            and set(by_mode) and number(by_mode.get(mode))
            and abs(float(ndcg) - float(by_mode[mode])) <= 1e-9
            and measurement.get("comparable") is True
            and isinstance(measurement.get("comparability_reasons"), list)
            and not measurement["comparability_reasons"]
            and number(leak) and 0 <= float(leak) <= 1
            and isinstance(measurement.get("n_shortcut_leaks"), int)
            and not isinstance(measurement.get("n_shortcut_leaks"), bool)
            and isinstance(measurement.get("n_shortcut_queries"), int)
            and not isinstance(measurement.get("n_shortcut_queries"), bool)
            and 0 < measurement["n_shortcut_queries"] <= query_count
            and 0 <= measurement["n_shortcut_leaks"] <= measurement["n_shortcut_queries"]
            and abs(
                float(leak)
                - round(
                    measurement["n_shortcut_leaks"]
                    / measurement["n_shortcut_queries"],
                    4,
                )
            ) <= 1e-9
            and measurement.get("n_queries") == query_count
            and _is_sha256(measurement.get("run_manifest_sha256"))
            and measurement.get("method") == "retrieval-nDCG + single-doc-shortcut-probe"
            and isinstance(band, list) and len(band) == 2 and all(number(v) for v in band)
            and 0 <= float(band[0]) <= float(band[1]) <= 1
            and number(leak_max) and 0 <= float(leak_max) <= 1
        )
        return bool(
            structural
            and float(band[0]) <= float(ndcg) <= float(band[1])
            and float(leak) <= float(leak_max)
        ), measurement, threshold

    if gate in {"union_recall", "leak_floor"}:
        if set(measurement) != {
            "status", "leg_union_recall", "leak_rate", "n_queries",
            "run_manifest_sha256", "method",
        }:
            return False, measurement, threshold
        union = measurement.get("leg_union_recall")
        leak = measurement.get("leak_rate")
        structural = (
            measurement.get("status") == "ok"
            and number(union) and 0 <= float(union) <= 1
            and number(leak) and 0 <= float(leak) <= 1
            and measurement.get("n_queries") == query_count
            and _is_sha256(measurement.get("run_manifest_sha256"))
            and measurement.get("method") == "staged_recall_accounting"
        )
        if gate == "union_recall":
            minimum = threshold.get("minimum") if set(threshold) == {"minimum"} else None
            return bool(structural and number(minimum) and 0 <= float(minimum) <= 1
                        and float(union) >= float(minimum)), measurement, threshold
        maximum = threshold.get("maximum") if set(threshold) == {"maximum"} else None
        return bool(structural and number(maximum) and 0 <= float(maximum) <= 1
                    and float(leak) <= float(maximum)), measurement, threshold
    return False, measurement, threshold


def _decode_source_blob(blob: object) -> object:
    if not isinstance(blob, dict) or set(blob) != {"sha256", "artifact_base64"}:
        raise ValueError("scientific source artifact is malformed")
    raw = base64.b64decode(blob.get("artifact_base64"), validate=True)
    if hashlib.sha256(raw).hexdigest() != blob.get("sha256"):
        raise ValueError("scientific source artifact digest mismatch")
    return json.loads(raw.decode("utf-8"))


def certification_snapshot(
    certification_path: str | Path,
    *,
    dataset: str,
    expected_signature: str | None = None,
) -> dict:
    """Return the exact fully-certified member cell captured by a claim run."""
    path = Path(certification_path)
    certification = json.loads(path.read_text(encoding="utf-8"))
    if certification.get("schema") != "707-corpus-structural-certification.v1":
        raise ValueError("unsupported 707 corpus certification schema")
    if not _complete_certification_document(certification):
        raise ValueError("707 corpus certification is not fully certified")
    matches = []
    for size, variants in (certification.get("datasets") or {}).items():
        for variant, cell in (variants or {}).items():
            if cell.get("dataset") == dataset:
                matches.append((size, variant, cell))
    if len(matches) != 1:
        raise ValueError(f"707 certification must contain exactly one {dataset!r} cell")
    size, variant, cell = matches[0]
    signature = cell.get("corpus_signature")
    if expected_signature and signature != expected_signature:
        raise ValueError("707 certification corpus signature disagrees with materialized corpus")
    gates = cell.get("scientific_gates") or {}
    if (
        cell.get("passed") is not True
        or cell.get("fully_certified") is not True
        or set(gates) != set(SCIENTIFIC_GATES)
        or any(
            item.get("passed") is not True
            or not _is_sha256(item.get("sha256"))
            or not _embedded_gate_valid(
                item,
                member=certification.get("member"),
                dataset=dataset,
                corpus_signature=signature,
                query_gold_sha256=cell.get("query_gold_sha256"),
                gate=gate,
                query_count=cell.get("query_count"),
            )
            for gate, item in gates.items()
        )
    ):
        raise ValueError("707 certification cell is incomplete or failed")
    return {
        "schema": "707-corpus-certification-snapshot.v1",
        "member": certification.get("member"),
        "dataset": dataset,
        "size": int(size),
        "query_variant": variant,
        "query_count": cell.get("query_count"),
        "query_gold_sha256": cell.get("query_gold_sha256"),
        "corpus_signature": signature,
        "certification_sha256": _sha256(path),
        "certification_base64": base64.b64encode(path.read_bytes()).decode("ascii"),
        "scientific_gates": {gate: gates[gate] for gate in SCIENTIFIC_GATES},
        "fully_certified": True,
    }


def _embedded_gate_valid(
    evidence: object,
    *,
    member: str,
    dataset: str,
    corpus_signature: str,
    query_gold_sha256: str,
    gate: str,
    query_count: int,
) -> bool:
    """Re-hash and re-evaluate a gate carried inside a certificate or snapshot."""
    if not isinstance(evidence, dict) or set(evidence) != {
        "passed", "status", "sha256", "artifact_base64", "observed", "threshold",
    }:
        return False
    encoded = evidence.get("artifact_base64")
    if not isinstance(encoded, str):
        return False
    try:
        raw = base64.b64decode(encoded, validate=True)
        artifact = json.loads(raw.decode("utf-8"))
    except (ValueError, UnicodeDecodeError, json.JSONDecodeError):
        return False
    valid, observed, threshold = _derive_scientific_verdict(
        artifact,
        member=member,
        dataset=dataset,
        corpus_signature=corpus_signature,
        query_gold_sha256=query_gold_sha256,
        gate=gate,
        query_count=query_count,
        threshold=evidence.get("threshold"),
    )
    return (
        valid
        and evidence.get("passed") is True
        and evidence.get("status") == "passed"
        and hashlib.sha256(raw).hexdigest() == evidence.get("sha256")
        and evidence.get("observed") == observed
        and evidence.get("threshold") == threshold
    )


def certification_snapshot_valid(snapshot: object) -> bool:
    """Validate the self-contained 707 snapshot preserved by claim evidence."""
    if not isinstance(snapshot, dict) or set(snapshot) != {
        "schema", "member", "dataset", "size", "query_variant", "query_count",
        "query_gold_sha256", "corpus_signature", "certification_sha256",
        "certification_base64", "scientific_gates",
        "fully_certified",
    }:
        return False
    if (
        snapshot.get("schema") != "707-corpus-certification-snapshot.v1"
        or not snapshot.get("member")
        or not snapshot.get("dataset")
        or not isinstance(snapshot.get("size"), int)
        or snapshot["size"] < 1
        or snapshot.get("query_variant") not in {"verbose", "short-natural"}
        or not isinstance(snapshot.get("query_count"), int)
        or snapshot["query_count"] < 1
        or not _is_sha256(snapshot.get("corpus_signature"))
        or not _is_sha256(snapshot.get("query_gold_sha256"))
        or not _is_sha256(snapshot.get("certification_sha256"))
        or snapshot.get("fully_certified") is not True
        or set(snapshot.get("scientific_gates") or {}) != set(SCIENTIFIC_GATES)
    ):
        return False
    try:
        certification_raw = base64.b64decode(
            snapshot.get("certification_base64"), validate=True,
        )
        certification = json.loads(certification_raw.decode("utf-8"))
    except (TypeError, ValueError, UnicodeDecodeError, json.JSONDecodeError):
        return False
    if (
        hashlib.sha256(certification_raw).hexdigest()
        != snapshot["certification_sha256"]
        or not _complete_certification_document(certification)
        or certification.get("member") != snapshot["member"]
    ):
        return False
    matching_cells = [
        (int(size), variant, cell)
        for size, variants in certification["datasets"].items()
        for variant, cell in variants.items()
        if cell.get("dataset") == snapshot["dataset"]
    ]
    if len(matching_cells) != 1:
        return False
    size, variant, cell = matching_cells[0]
    if (
        size != snapshot["size"]
        or variant != snapshot["query_variant"]
        or cell.get("query_count") != snapshot["query_count"]
        or cell.get("query_gold_sha256") != snapshot["query_gold_sha256"]
        or cell.get("corpus_signature") != snapshot["corpus_signature"]
        or cell.get("scientific_gates") != snapshot["scientific_gates"]
    ):
        return False
    return all(
        _embedded_gate_valid(
            evidence,
            member=snapshot["member"],
            dataset=snapshot["dataset"],
            corpus_signature=snapshot["corpus_signature"],
            query_gold_sha256=snapshot["query_gold_sha256"],
            gate=gate,
            query_count=snapshot["query_count"],
        )
        for gate, evidence in snapshot["scientific_gates"].items()
    )


def _complete_certification_document(certification: dict) -> bool:
    """Require the exact four-cell 707 family, not a self-declared partial certificate."""
    if set(certification) != {
        "schema", "member", "status", "datasets", "family_checks",
        "structural_passed", "scientific_gates", "fully_certified", "scientific_policy",
    }:
        return False
    if (
        not certification.get("member")
        or certification.get("status") != "fully-certified"
        or certification.get("structural_passed") is not True
        or certification.get("fully_certified") is not True
        or certification.get("scientific_gates")
        != {gate: "passed" for gate in SCIENTIFIC_GATES}
    ):
        return False
    try:
        policy_cells = _scientific_policy_cells_from_snapshot(
            certification.get("scientific_policy"), member=certification["member"],
        )
    except (TypeError, ValueError, UnicodeDecodeError, json.JSONDecodeError):
        return False
    datasets = certification.get("datasets") or {}
    if set(datasets) != {"1000", "10000"}:
        return False
    seen_datasets = set()
    for size, variants in datasets.items():
        if set(variants or {}) != {"verbose", "short-natural"}:
            return False
        for variant, cell in variants.items():
            if set(cell or {}) != {
                "dataset", "corpus_signature", "query_gold_sha256", "query_count", "checks",
                "regeneration", "commitment", "descriptor_collision",
                "scientific_gates", "passed", "fully_certified",
            }:
                return False
            signature = cell.get("corpus_signature")
            if (
                cell.get("passed") is not True
                or cell.get("fully_certified") is not True
                or not isinstance(cell.get("query_count"), int)
                or cell["query_count"] < 1
                or not _is_sha256(signature)
                or not _is_sha256(cell.get("query_gold_sha256"))
                or set(cell.get("checks") or {}) != _CELL_CHECKS
                or not all(value is True for value in cell["checks"].values())
            ):
                return False
            regeneration = cell.get("regeneration") or {}
            commitment = cell.get("commitment") or {}
            collision = cell.get("descriptor_collision") or {}
            if (
                set(regeneration) != {"passed", "method", "digest", "reason"}
                or regeneration.get("passed") is not True
                or regeneration.get("method") != "cross-process-regeneration-diff"
                or not _is_sha256(regeneration.get("digest"))
                or set(commitment) != {"passed", "manifest_sha256", "recipe_sha256"}
                or commitment.get("passed") is not True
                or not _is_sha256(commitment.get("manifest_sha256"))
                or not _is_sha256(commitment.get("recipe_sha256"))
                or set(collision) != {
                    "n_groups", "n_docs_involved", "n_gold_involved", "passed", "method",
                }
                or collision.get("passed") is not True
                or collision.get("n_gold_involved") != 0
                or collision.get("method") != "exact-title-match"
            ):
                return False
            gates = cell.get("scientific_gates") or {}
            if set(gates) != set(SCIENTIFIC_GATES):
                return False
            for gate, evidence in gates.items():
                try:
                    policy_threshold = _policy_threshold(
                        policy_cells,
                        dataset=cell["dataset"],
                        corpus_signature=signature,
                        query_gold_sha256=cell["query_gold_sha256"],
                        query_count=cell["query_count"],
                        gate=gate,
                    )
                except ValueError:
                    return False
                if (
                    set(evidence or {}) != {
                        "passed", "status", "sha256", "artifact_base64",
                        "observed", "threshold",
                    }
                    or evidence.get("passed") is not True
                    or evidence.get("status") != "passed"
                    or not _is_sha256(evidence.get("sha256"))
                    or evidence.get("observed") is None
                    or evidence.get("threshold") is None
                    or evidence.get("threshold") != policy_threshold
                    or not _embedded_gate_valid(
                        evidence,
                        member=certification["member"],
                        dataset=cell["dataset"],
                        corpus_signature=signature,
                        query_gold_sha256=cell["query_gold_sha256"],
                        gate=gate,
                        query_count=cell["query_count"],
                    )
                ):
                    return False
            if cell.get("dataset") in seen_datasets:
                return False
            seen_datasets.add(cell.get("dataset"))
    if seen_datasets != set(policy_cells):
        return False
    family = certification.get("family_checks") or {}
    if set(family) != {"verbose", "short-natural", "strata"}:
        return False
    return all(
        set(family[variant] or {}) == _FAMILY_CHECKS
        and all(value is True for value in family[variant].values())
        for variant in ("verbose", "short-natural")
    ) and (
        set(family["strata"] or {}) == _STRATA_CHECKS
        and all(value is True for value in family["strata"].values())
    )


def _scientific_policy_cells_from_snapshot(snapshot: object, *, member: str) -> dict[str, dict]:
    if not isinstance(snapshot, dict) or set(snapshot) != {"sha256", "artifact_base64"}:
        raise ValueError("scientific policy snapshot is malformed")
    raw = base64.b64decode(snapshot.get("artifact_base64"), validate=True)
    if hashlib.sha256(raw).hexdigest() != snapshot.get("sha256"):
        raise ValueError("scientific policy digest mismatch")
    return _active_scientific_policy_cells(
        json.loads(raw.decode("utf-8")), member=member,
    )


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
