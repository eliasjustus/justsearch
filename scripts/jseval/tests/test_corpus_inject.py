from __future__ import annotations

import json
import base64
import hashlib
import random
from pathlib import Path

import pytest

from jseval import (
    corpus_build,
    corpus_certify,
    corpus_inject,
    corpus_leak,
    corpus_query_strata,
)


def _gate_artifact(
    gate: str,
    *,
    member: str = "fixture-member",
    dataset: str = "mixed/fixture",
    signature: str = "c" * 64,
    query_gold_sha256: str = "b" * 64,
    query_count: int = 20,
) -> dict:
    if gate == "closed_book":
        block = {
            "closed_book_accuracy": 0.0, "n_queries": query_count,
            "n_memorizable": 0, "model": "fixture-model", "date": "2026-07-13",
            "threshold": 0.15, "passed": True, "method": "closed-book-slot-guess",
        }
        measurement = {
            "closed_book_certification": block,
            "fidelity": {"memory_independence": 1.0, "method": "closed-book"},
        }
    elif gate == "retrieval_calibration":
        measurement = {
            "retrieval_ndcg": 0.7, "retrieval_ndcg_by_mode": {"hybrid": 0.7},
            "retrieval_mode": "hybrid", "comparable": True,
            "comparability_reasons": [], "shortcut_leak_rate": 0.0,
            "n_shortcut_leaks": 0, "n_shortcut_queries": query_count,
            "band": [0.4, 0.85], "in_band": True,
            "retrieval_difficulty": "moderate", "passed": True,
            "method": "retrieval-nDCG + single-doc-shortcut-probe",
        }
    else:
        measurement = {
            "status": "ok",
            "aggregate": {"leg_union_recall": 0.95, "leak_rate": 0.01},
        }
    measurement_blob = _source_blob(measurement)
    manifest_blob = None if gate == "closed_book" else _source_blob(
        _source_manifest(dataset, signature)
    )
    return {
        "schema": "707-corpus-scientific-measurement.v1",
        "member": member, "dataset": dataset, "corpus_signature": signature,
        "query_gold_sha256": query_gold_sha256,
        "query_count": query_count, "gate": gate,
        "source_artifacts": {
            "measurement": measurement_blob,
            "run_manifest": manifest_blob,
        },
    }


def _source_blob(value: object) -> dict:
    raw = json.dumps(value, sort_keys=True).encode("utf-8")
    return {
        "sha256": hashlib.sha256(raw).hexdigest(),
        "artifact_base64": base64.b64encode(raw).decode("ascii"),
    }


def _source_manifest(dataset: str, signature: str) -> dict:
    from jseval.manifest import _compute_cohort_hash

    manifest = {
        "dataset": dataset,
        "git_sha": "a" * 40,
        "corpus_identity": {"profile_id": dataset, "signature": signature},
        "eval_protocol_hash": "7" * 64,
    }
    manifest["manifest_hash"] = _compute_cohort_hash(manifest)
    return manifest


def _gate_threshold(gate: str) -> dict:
    return {
        "closed_book": {"maximum_accuracy": 0.15},
        "retrieval_calibration": {
            "ndcg_band": [0.4, 0.85], "shortcut_leak_rate_max": 0.1,
        },
        "union_recall": {"minimum": 0.9},
        "leak_floor": {"maximum": 0.05},
    }[gate]


def _gate_evidence(gate: str, **kwargs) -> dict:
    artifact = _gate_artifact(gate, **kwargs)
    raw = json.dumps(artifact, sort_keys=True).encode("utf-8")
    valid, observed, threshold = corpus_certify._derive_scientific_verdict(
        artifact,
        member=artifact["member"], dataset=artifact["dataset"],
        corpus_signature=artifact["corpus_signature"],
        query_gold_sha256=artifact["query_gold_sha256"],
        gate=gate, query_count=artifact["query_count"],
        threshold=_gate_threshold(gate),
    )
    assert valid
    return {
        "passed": valid,
        "status": "passed",
        "sha256": hashlib.sha256(raw).hexdigest(),
        "artifact_base64": base64.b64encode(raw).decode("ascii"),
        "observed": observed,
        "threshold": threshold,
    }


def _write_jsonl(path: Path, rows: list[dict]) -> None:
    path.write_text("".join(json.dumps(row) + "\n" for row in rows), encoding="utf-8")


def _complete_certificate(
    member: str, dataset: str, signature: str, gates: dict, *,
    query_count: int = 20, query_gold_sha256: str = "b" * 64,
) -> dict:
    cells = {}
    policy_cells = []
    for size in ("1000", "10000"):
        cells[size] = {}
        for variant in ("verbose", "short-natural"):
            cell_dataset = (
                dataset if (size, variant) == ("1000", "verbose")
                else f"{dataset}-{size}-{variant}"
            )
            cell_gates = gates if (size, variant) == ("1000", "verbose") else {
                gate: _gate_evidence(
                    gate, member=member, dataset=cell_dataset,
                    signature=signature, query_gold_sha256=query_gold_sha256,
                    query_count=query_count,
                )
                for gate in corpus_certify.SCIENTIFIC_GATES
            }
            policy_cells.append({
                "member": member,
                "dataset": cell_dataset,
                "corpus_signature": signature,
                "query_gold_sha256": query_gold_sha256,
                "query_count": query_count,
                "thresholds": {
                    gate: _gate_threshold(gate)
                    for gate in corpus_certify.SCIENTIFIC_GATES
                },
            })
            cells[size][variant] = {
                "dataset": cell_dataset,
                "corpus_signature": signature,
                "query_gold_sha256": query_gold_sha256,
                "query_count": query_count,
                "checks": {
                    "size": True, "signature": True, "query_variant": True,
                    "query_family_ids": True, "cross_process_regeneration": True,
                    "immutable_commitment": True, "descriptor_collision": True,
                    "indistinguishability": True, "field_selectivity": True,
                },
                "regeneration": {
                    "passed": True,
                    "method": "cross-process-regeneration-diff",
                    "digest": "a" * 64,
                    "reason": None,
                },
                "commitment": {
                    "passed": True,
                    "manifest_sha256": "b" * 64,
                    "recipe_sha256": "c" * 64,
                },
                "descriptor_collision": {
                    "n_groups": 0,
                    "n_docs_involved": 0,
                    "n_gold_involved": 0,
                    "passed": True,
                    "method": "exact-title-match",
                },
                "indistinguishability": {
                    "id_shape_separability": 0.1,
                    "id_shape_native_base_rate": 0.2,
                    "id_shape_rule": "len(id) <= 7",
                    "id_shape_passed": True,
                    "ngram_max_gold_coverage": 0.3,
                    "ngram_native_base_rate": 0.4,
                    "ngram_passed": True,
                    "passed": True,
                    "method": "null-calibrated-id-shape-and-ngram-selectivity",
                },
                "field_selectivity": {
                    "worst_field": "title",
                    "max_field_separability": 0.1,
                    "native_base_rate": 0.2,
                    "n_fields_compared": 2,
                    "passed": True,
                    "method": "field-presence-null-calibrated-separability",
                },
                "scientific_gates": cell_gates,
                "passed": True,
                "fully_certified": True,
            }
    policy_raw = json.dumps({
        "schema": "707-corpus-certification-policy.v1",
        "status": "active",
        "unresolved": [],
        "required_cells": policy_cells,
    }, sort_keys=True).encode("utf-8")
    return {
        "schema": "707-corpus-structural-certification.v1",
        "member": member,
        "status": "fully-certified",
        "datasets": cells,
        "family_checks": {
            "verbose": {
                "queries_identical_across_sizes": True,
                "qrels_identical_across_sizes": True,
                "one_k_docs_are_subset_of_ten_k": True,
            },
            "short-natural": {
                "queries_identical_across_sizes": True,
                "qrels_identical_across_sizes": True,
                "one_k_docs_are_subset_of_ten_k": True,
            },
            "strata": {
                "same_family_ids": True,
                "same_answers_and_evidence": True,
                "distinct_query_text": True,
                "same_corpus_and_qrels_per_size": True,
            },
        },
        "structural_passed": True,
        "scientific_gates": {gate: "passed" for gate in corpus_certify.SCIENTIFIC_GATES},
        "fully_certified": True,
        "scientific_policy": {
            "sha256": hashlib.sha256(policy_raw).hexdigest(),
            "artifact_base64": base64.b64encode(policy_raw).decode("ascii"),
        },
    }


def _certification_snapshot_fixture(
    *,
    member: str = "fixture-member",
    dataset: str = "fixture",
    signature: str = "c" * 64,
    query_gold_sha256: str = "b" * 64,
    query_count: int = 20,
) -> dict:
    gates = {
        gate: _gate_evidence(
            gate, member=member, dataset=dataset,
            signature=signature, query_gold_sha256=query_gold_sha256,
            query_count=query_count,
        )
        for gate in corpus_certify.SCIENTIFIC_GATES
    }
    certificate = _complete_certificate(
        member, dataset, signature, gates, query_count=query_count,
        query_gold_sha256=query_gold_sha256,
    )
    raw = json.dumps(certificate, sort_keys=True).encode("utf-8")
    return {
        "schema": "707-corpus-certification-snapshot.v1",
        "member": member,
        "dataset": dataset,
        "size": 1000,
        "query_variant": "verbose",
        "query_count": query_count,
        "query_gold_sha256": query_gold_sha256,
        "corpus_signature": signature,
        "certification_sha256": hashlib.sha256(raw).hexdigest(),
        "certification_base64": base64.b64encode(raw).decode("ascii"),
        "scientific_gates": gates,
        "fully_certified": True,
    }


def _fixture(tmp_path):
    real = tmp_path / "real"
    gold = tmp_path / "gold"
    real.mkdir()
    gold.mkdir()
    real_docs = [
        {"_id": f"real-{index}", "title": f"Host {index}",
         "text": (f"Real host private sentence {index}. " * 80)}
        for index in range(6)
    ]
    fabricated = [{
        "_id": "gold-1", "title": "Quenby ferrolite descriptor",
        "text": "The fabricated Quenby attribute is ochre ferrolite 0047. Another linked fact follows.",
    }]
    queries = [{
        "query": "Which invented material belongs to the marsh engineer?",
        "answer": "ochre ferrolite 0047",
        "evidence_ids": ["gold-1"],
        "question_type": "semantic",
    }]
    _write_jsonl(real / "corpus.jsonl", real_docs)
    _write_jsonl(gold / "docs.jsonl", fabricated)
    (gold / "queries.json").write_text(json.dumps(queries), encoding="utf-8")
    (gold / "meta.json").write_text(json.dumps({
        "type_axis": "prose",
        "generation_provenance": {"method": "procedural-fabricated", "seed": 7},
    }), encoding="utf-8")
    return real, gold


def test_real_text_injection_is_deterministic_and_certifiable(tmp_path):
    real, gold = _fixture(tmp_path)
    source_one = tmp_path / "source-one"
    source_two = tmp_path / "source-two"
    kwargs = dict(
        seed=707, n_distractors=3, style="interleave",
        real_source_id="fixture-real-v1", license_id="test-only",
    )
    meta_one = corpus_inject.build_source(real, gold, source_one, **kwargs)
    meta_two = corpus_inject.build_source(real, gold, source_two, **kwargs)

    assert (source_one / "docs.jsonl").read_bytes() == (source_two / "docs.jsonl").read_bytes()
    provenance = meta_one["generation_provenance"]
    assert provenance["assembled_digest"] == meta_two["generation_provenance"]["assembled_digest"]
    assert provenance["assembly_determinism"]["method"] == "cross-process-regeneration-diff"
    assert corpus_certify.regeneration_determinism_report(provenance)["passed"] is True

    materialized = tmp_path / "datasets" / "mixed" / "fixture"
    metadata = corpus_build.build_golden(source_one, materialized, now="2026-07-13")
    assert metadata["corpus_size"] == 4
    assert metadata["query_count"] == 1
    assert metadata["corpus_signature"]

    docs = [json.loads(line) for line in (source_one / "docs.jsonl").read_text().splitlines()]
    queries = json.loads((source_one / "queries.json").read_text())
    assert corpus_certify.descriptor_collision_report(docs, queries)["passed"] is True


def test_commitment_contains_fabricated_inputs_and_ids_but_no_real_host_text(tmp_path):
    real, gold = _fixture(tmp_path)
    source = tmp_path / "source"
    meta = corpus_inject.build_source(
        real, gold, source, seed=707, n_distractors=2, style="append",
        real_source_id="fixture-real-v1", license_id="test-only",
    )
    commitment = corpus_inject.write_commitment(
        tmp_path / "commitment", gold, meta["generation_provenance"]
    )
    committed_text = "\n".join(
        path.read_text(encoding="utf-8") for path in commitment.iterdir()
    )
    assert "ochre ferrolite 0047" in committed_text
    assert "host_id" in committed_text
    assert "Real host private sentence" not in committed_text
    assert corpus_certify._validate_commitment(
        commitment, meta["generation_provenance"]
    )["passed"] is True

    recipe_path = commitment / "recipe.json"
    forged = json.loads(recipe_path.read_text(encoding="utf-8"))
    forged["assembly_determinism"] = {
        "passed": True,
        "method": "cross-process-regeneration-diff",
        "digest": "f" * 64,
    }
    recipe_path.write_text(json.dumps(forged), encoding="utf-8")
    assert corpus_certify._validate_commitment(
        commitment, meta["generation_provenance"]
    )["passed"] is False


def test_forged_real_text_determinism_evidence_fails_closed():
    provenance = {
        "method": "real-text-injection-v1",
        "assembled_digest": "a" * 64,
        "assembly_determinism": {
            "passed": True,
            "method": "cross-process-regeneration-diff",
            "digest": "b" * 64,
        },
    }
    assert corpus_certify.regeneration_determinism_report(provenance)["passed"] is False


def test_scientific_gate_evidence_is_exact_and_snapshot_is_hash_bound(tmp_path):
    gates = {}
    for gate in corpus_certify.SCIENTIFIC_GATES:
        path = tmp_path / f"{gate}.json"
        path.write_text(json.dumps(_gate_artifact(gate)), encoding="utf-8")
        gates[gate] = corpus_certify._validate_scientific_evidence(
            path, member="fixture-member", dataset="mixed/fixture",
            corpus_signature="c" * 64, query_gold_sha256="b" * 64,
            gate=gate, query_count=20,
            threshold=_gate_threshold(gate),
        )
        assert gates[gate]["passed"] is True

    certification = tmp_path / "certification.json"
    certification.write_text(json.dumps(_complete_certificate(
        "fixture-member", "mixed/fixture", "c" * 64, gates,
    )), encoding="utf-8")

    snapshot = corpus_certify.certification_snapshot(
        certification, dataset="mixed/fixture", expected_signature="c" * 64,
    )
    assert snapshot["fully_certified"] is True
    assert snapshot["certification_sha256"] == corpus_certify._sha256(certification)
    assert corpus_certify.certification_snapshot_valid(snapshot) is True
    tampered_snapshot = json.loads(json.dumps(snapshot))
    tampered_snapshot["scientific_gates"]["closed_book"]["artifact_base64"] = (
        tampered_snapshot["scientific_gates"]["closed_book"]["artifact_base64"][:-4] + "AAAA"
    )
    assert corpus_certify.certification_snapshot_valid(tampered_snapshot) is False

    partial = json.loads(certification.read_text(encoding="utf-8"))
    partial["datasets"].pop("10000")
    certification.write_text(json.dumps(partial), encoding="utf-8")
    import pytest
    with pytest.raises(ValueError, match="not fully certified"):
        corpus_certify.certification_snapshot(
            certification, dataset="mixed/fixture", expected_signature="c" * 64,
        )

    forged = tmp_path / "forged.json"
    artifact = json.loads((tmp_path / "closed_book.json").read_text(encoding="utf-8"))
    artifact["dataset"] = "mixed/other"
    forged.write_text(json.dumps(artifact), encoding="utf-8")
    assert corpus_certify._validate_scientific_evidence(
        forged, member="fixture-member", dataset="mixed/fixture",
        corpus_signature="c" * 64, query_gold_sha256="b" * 64,
        gate="closed_book", query_count=20,
        threshold=_gate_threshold("closed_book"),
    )["passed"] is False

    claimed = tmp_path / "claimed.json"
    claimed_artifact = _gate_artifact("closed_book")
    claimed_artifact["passed"] = True
    claimed.write_text(json.dumps(claimed_artifact), encoding="utf-8")
    assert corpus_certify._validate_scientific_evidence(
        claimed, member="fixture-member", dataset="mixed/fixture",
        corpus_signature="c" * 64, query_gold_sha256="b" * 64,
        gate="closed_book", query_count=20,
        threshold=_gate_threshold("closed_book"),
    )["passed"] is False

    threshold_injected = tmp_path / "threshold-injected.json"
    threshold_artifact = _gate_artifact("closed_book")
    threshold_artifact["threshold"] = {"maximum_accuracy": 1.0}
    threshold_injected.write_text(json.dumps(threshold_artifact), encoding="utf-8")
    assert corpus_certify._validate_scientific_evidence(
        threshold_injected, member="fixture-member", dataset="mixed/fixture",
        corpus_signature="c" * 64, query_gold_sha256="b" * 64,
        gate="closed_book", query_count=20,
        threshold=_gate_threshold("closed_book"),
    )["passed"] is False

    failing = tmp_path / "failing.json"
    failing_artifact = _gate_artifact("union_recall")
    failing_source = corpus_certify._decode_source_blob(
        failing_artifact["source_artifacts"]["measurement"]
    )
    failing_source["aggregate"]["leg_union_recall"] = 0.2
    failing_artifact["source_artifacts"]["measurement"] = _source_blob(failing_source)
    failing.write_text(json.dumps(failing_artifact), encoding="utf-8")
    assert corpus_certify._validate_scientific_evidence(
        failing, member="fixture-member", dataset="mixed/fixture",
        corpus_signature="c" * 64, query_gold_sha256="b" * 64,
        gate="union_recall", query_count=20,
        threshold=_gate_threshold("union_recall"),
    )["passed"] is False

    worst = tmp_path / "worst.json"
    worst_artifact = _gate_artifact("closed_book")
    worst_source = corpus_certify._decode_source_blob(
        worst_artifact["source_artifacts"]["measurement"]
    )
    worst_source["closed_book_certification"]["closed_book_accuracy"] = 1.0
    worst_source["closed_book_certification"]["n_memorizable"] = 20
    worst_artifact["source_artifacts"]["measurement"] = _source_blob(worst_source)
    worst.write_text(json.dumps(worst_artifact), encoding="utf-8")
    assert corpus_certify._validate_scientific_evidence(
        worst, member="fixture-member", dataset="mixed/fixture",
        corpus_signature="c" * 64, query_gold_sha256="b" * 64,
        gate="closed_book", query_count=20,
        threshold=_gate_threshold("closed_book"),
    )["passed"] is False

    source_tampered = tmp_path / "source-tampered.json"
    source_artifact = _gate_artifact("retrieval_calibration")
    source_artifact["source_artifacts"]["run_manifest"]["sha256"] = "0" * 64
    source_tampered.write_text(json.dumps(source_artifact), encoding="utf-8")
    assert corpus_certify._validate_scientific_evidence(
        source_tampered, member="fixture-member", dataset="mixed/fixture",
        corpus_signature="c" * 64, query_gold_sha256="b" * 64,
        gate="retrieval_calibration", query_count=20,
        threshold=_gate_threshold("retrieval_calibration"),
    )["passed"] is False

    for leak_count, leak_rate in ((20, 0.0), (0, 1.0)):
        inconsistent = _gate_artifact("retrieval_calibration")
        fidelity = corpus_certify._decode_source_blob(
            inconsistent["source_artifacts"]["measurement"]
        )
        fidelity["n_shortcut_leaks"] = leak_count
        fidelity["shortcut_leak_rate"] = leak_rate
        inconsistent["source_artifacts"]["measurement"] = _source_blob(fidelity)
        inconsistent_path = tmp_path / f"inconsistent-{leak_count}.json"
        inconsistent_path.write_text(json.dumps(inconsistent), encoding="utf-8")
        assert corpus_certify._validate_scientific_evidence(
            inconsistent_path, member="fixture-member", dataset="mixed/fixture",
            corpus_signature="c" * 64, query_gold_sha256="b" * 64,
            gate="retrieval_calibration", query_count=20,
            threshold=_gate_threshold("retrieval_calibration"),
        )["passed"] is False

    embedded_certificate = json.loads(
        base64.b64decode(snapshot["certification_base64"]).decode("utf-8")
    )
    policy_cells = corpus_certify._scientific_policy_cells_from_snapshot(
        embedded_certificate["scientific_policy"],
        member="fixture-member",
    )
    with pytest.raises(ValueError, match="identity mismatch"):
        corpus_certify._policy_threshold(
            policy_cells, dataset="mixed/fixture", corpus_signature="c" * 64,
            query_gold_sha256="f" * 64, query_count=20, gate="closed_book",
        )


def test_checked_in_scientific_policy_is_ratified_two_member_eight_cell():
    """Founder-ratified 2026-07-16 (tempdoc 707 owner-decision sheet item 1 + same-day
    email-cell ratification): the policy is ACTIVE with exactly the CLERC four-cell matrix
    plus the EN-email four-cell matrix; floors derived from the measured candidates (band
    low = measured - 0.08, union min = measured - 0.10, leak max = measured + margin,
    closed-book <= 0.15). The DE member is deliberately ABSENT (1k-only secondary stratum,
    never claim-bearing until the encoder-lane finding resolves) — an unknown member must
    still hard-fail."""
    policy = json.loads(corpus_certify.SCIENTIFIC_POLICY_PATH.read_text(encoding="utf-8"))
    assert policy["status"] == "active" and policy["unresolved"] == []
    cells = corpus_certify._active_scientific_policy_cells(policy, member="en-legal-clerc")
    assert set(cells) == {
        "mixed/en-legal-clerc-1k-verbose", "mixed/en-legal-clerc-1k-short-natural",
        "mixed/en-legal-clerc-10k-verbose", "mixed/en-legal-clerc-10k-short-natural"}
    email_cells = corpus_certify._active_scientific_policy_cells(
        policy, member="en-email-enron-raw")
    assert set(email_cells) == {
        "mixed/en-email-enron-raw-1k-verbose", "mixed/en-email-enron-raw-1k-short-natural",
        "mixed/en-email-enron-raw-10k-verbose",
        "mixed/en-email-enron-raw-10k-short-natural"}
    import pytest
    with pytest.raises(ValueError):
        corpus_certify._active_scientific_policy_cells(policy, member="de-miracl")


def test_scientific_evidence_builder_binds_canonical_source_and_query_bytes(tmp_path):
    dataset = tmp_path / "dataset"
    (dataset / "qrels").mkdir(parents=True)
    (dataset / "corpus.jsonl").write_text('{"_id":"d1","text":"x"}\n', encoding="utf-8")
    (dataset / "qrels" / "test.tsv").write_text("query-id\tcorpus-id\tscore\nq0\td1\t1\n", encoding="utf-8")
    (dataset / "queries.json").write_text('[{"query":"q","answer":"a"}]', encoding="utf-8")
    measurement = tmp_path / "closed.json"
    measurement.write_text(json.dumps(corpus_certify.certify_corpus.__name__), encoding="utf-8")

    artifact = corpus_certify.build_scientific_measurement_artifact(
        member="fixture", dataset="mixed/fixture", dataset_dir=dataset,
        gate="closed_book", measurement_path=measurement,
    )

    assert artifact["query_gold_sha256"] == corpus_certify._sha256(dataset / "queries.json")
    assert corpus_certify._decode_source_blob(
        artifact["source_artifacts"]["measurement"]
    ) == "certify_corpus"
    import pytest
    with pytest.raises(ValueError, match="requires a run manifest"):
        corpus_certify.build_scientific_measurement_artifact(
            member="fixture", dataset="mixed/fixture", dataset_dir=dataset,
            gate="retrieval_calibration", measurement_path=measurement,
        )


def test_certification_snapshot_rejects_failed_or_wrong_signature(tmp_path):
    path = tmp_path / "certification.json"
    path.write_text(json.dumps({
        "schema": "707-corpus-structural-certification.v1",
        "member": "fixture",
        "datasets": {},
        "fully_certified": False,
    }), encoding="utf-8")
    import pytest
    with pytest.raises(ValueError, match="not fully certified"):
        corpus_certify.certification_snapshot(path, dataset="mixed/fixture")


def test_checked_in_707_member_recipes_are_strict_and_license_fail_closed():
    import jsonschema

    root = Path(__file__).parents[1]
    jsonschema.validate(
        json.loads((root / "707-corpus-certification-policy.v1.json").read_text(encoding="utf-8")),
        json.loads((root / "707-corpus-certification-policy.v1.schema.json").read_text(encoding="utf-8")),
    )
    schema = json.loads((root / "707-corpus-member.v1.schema.json").read_text(encoding="utf-8"))
    members = []
    for path in sorted((root / "707-corpora").glob("*/member.v1.json")):
        member = json.loads(path.read_text(encoding="utf-8"))
        jsonschema.validate(member, schema)
        members.append(member)
        if member["real_source"]["license_status"] != "verified":
            assert member["claim_eligible"] is False
            assert member["structural_certification"] is None
        if member["structural_certification"]:
            certification = json.loads(
                (path.parent / member["structural_certification"]).read_text(encoding="utf-8"))
            assert certification["structural_passed"] is True
            if certification["fully_certified"]:
                # en-legal-clerc (2026-07-16 AM) and en-email-enron-raw (2026-07-16 PM,
                # email-cell ratification) are fully certified under the founder-ratified
                # ACTIVE policy — full certification requires every gate to have actually
                # passed, and only claim-matrix members may carry it.
                assert member["name"] in {"en-legal-clerc", "en-email-enron-raw"}
                assert set(certification["scientific_gates"].values()) == {"passed"}
            else:
                assert set(certification["scientific_gates"].values()) <= {
                    "pending-model-run", "pending-backend-run",
                }
    assert {member["name"] for member in members} == {
        "en-legal-clerc", "de-miracl", "en-email-enron-raw",
    }


def test_short_natural_query_strata_are_deterministic_and_bounded(tmp_path):
    en_source = Path(__file__).parents[1] / "635-corpora" / "needle-burial-v1"
    de_source = Path(__file__).parents[1] / "635-corpora" / "synth-multiling-de-v1"
    for language, source in (("en", en_source), ("de", de_source)):
        first = tmp_path / f"{language}-one"
        second = tmp_path / f"{language}-two"
        corpus_query_strata.build_short_natural_source(source, first, language=language)
        corpus_query_strata.build_short_natural_source(source, second, language=language)
        assert (first / "queries.json").read_bytes() == (second / "queries.json").read_bytes()
        queries = json.loads((first / "queries.json").read_text(encoding="utf-8"))
        assert all(query["query_variant"] == "short-natural" for query in queries)
        assert all(5 <= len(query["query"].split()) <= 12 for query in queries)
        assert len({query["query_family_id"] for query in queries}) == len(queries)


def test_commitment_files_are_checkout_stable(tmp_path):
    """CRLF bake-in regression (2026-07-14): write_commitment must emit bytes that
    survive git's eol=lf normalization unchanged, so the manifest's recorded sha256
    stays matchable from any fresh checkout. The 2026-07-13 materialization wrote
    platform-default CRLF on Windows, baking unmatchable hashes into all 8 committed
    member cells (repaired same session). Guard: no CR bytes, and every manifest
    hash must verify against the file's own on-disk bytes immediately after write."""
    import hashlib

    from jseval import corpus_inject

    gold = tmp_path / "gold"
    gold.mkdir()
    (gold / "docs.jsonl").write_bytes(b'{"id": "g1", "title": "T", "text": "body"}\n')
    (gold / "queries.json").write_bytes(b'[{"query": "q", "answer": "a"}]\n')
    (gold / "meta.json").write_bytes(b'{"generation_provenance": {"seed": 1}}\n')

    root = corpus_inject.write_commitment(
        tmp_path / "commit", gold, {"seed": 1, "assembled_digest": "x"})
    manifest = json.loads((root / "commitment.v1.json").read_text(encoding="utf-8"))
    for name, recorded in manifest["files"].items():
        raw = (root / name).read_bytes()
        assert b"\r" not in raw, f"{name} contains CR bytes — git eol=lf will rewrite it"
        assert hashlib.sha256(raw).hexdigest() == recorded, f"{name} hash not self-consistent"
    assert b"\r" not in (root / "commitment.v1.json").read_bytes()


# --- abbreviation-aware sentence splitting (tempdoc 767) --------------------
#
# `_split_sentences` fixes a defect where the naive `(?<=[.!?])\s+` splitter
# shattered legal citations mid-string (e.g. at "U.S." in "477 U.S. 242"),
# letting `_interleave` insert fabricated sentences INSIDE a real citation and
# corrupt host-document realism. These tests cover the reported real-world
# citation, normal sentence boundaries, each abbreviation class named in the
# fix's brief, the genuinely ambiguous "abbreviation immediately followed by a
# new sentence" case, and cross-run determinism.


def test_real_world_citation_is_not_split():
    """The exact reported regression case: Anderson v. Liberty Lobby must survive
    intact as a single sentence, not shatter at 'v.' or 'U.S.'."""
    citation = "Anderson v. Liberty Lobby, Inc., 477 U.S. 242, 248 (1986)."
    text = f"The engineer built the bridge. {citation} Winters here are quiet."
    sentences = corpus_inject._split_sentences(text)
    assert citation.strip() in [s.strip() for s in sentences]
    assert not any("U.S." in s and s.strip() != citation for s in sentences)


def test_real_world_citation_survives_interleave_injection():
    """End-to-end: gold sentences must never land inside the citation."""
    host = (
        "The building was designed by the engineer Tasdell272. "
        "Anderson v. Liberty Lobby, Inc., 477 U.S. 242, 248 (1986). "
        "The surrounding district is known for long winters and quiet markets."
    )
    gold = (
        "The fabricated Quenby attribute is ochre ferrolite 0047. "
        "Another linked fact follows."
    )
    result = corpus_inject._interleave(host, gold)
    assert "Anderson v. Liberty Lobby, Inc., 477 U.S. 242, 248 (1986)." in result
    # No fabricated sentence boundary falls between "U.S." and the rest of the
    # citation, and none falls between "v." and "Liberty".
    assert "U.S. The fabricated" not in result
    assert "U.S. Another" not in result
    assert "v. The fabricated" not in result
    assert "v. Another" not in result


def test_normal_sentence_boundaries_still_split():
    sentences = corpus_inject._split_sentences("Foo happened. Bar followed.")
    assert [s.strip() for s in sentences] == ["Foo happened.", "Bar followed."]


def test_legal_reporter_abbreviations_not_split():
    cases = [
        "The court cited 492 U.S. 33 directly.",
        "Damages were set per 42 U.S.C. 1988.",
        "See 156 F.2d 27 for background.",
        "See 156 F. 2d 27 for background.",
        "The panel followed 88 F.3d 900 exactly.",
        "The panel followed 88 F. 3d 900 exactly.",
        "Applying S. Ct. review here.",
        "Under Cal. law the claim fails.",
        "Filed under N.Y. procedure rules.",
        "Widget Inc. shipped the part.",
        "Widget Corp. shipped the part.",
        "Widget Co. shipped the part.",
        "Widget Ltd. shipped the part.",
        "See No. 12-345 for the docket.",
        "Id. at 12 confirms the point.",
        "2 Ed. 2d covers the topic.",
        "12 F. Supp. 2d covers the topic.",
        "Reviewed by the Cir. panel.",
        "Filed in the Dist. court below.",
        "Under Fed. rules this applies.",
        "The Bar Ass'n filed a brief.",
    ]
    for text in cases:
        sentences = [s.strip() for s in corpus_inject._split_sentences(text) if s.strip()]
        assert len(sentences) == 1, f"unexpected split for: {text!r} -> {sentences}"


def test_titles_not_split():
    cases = [
        "Mr. Jones signed the form.",
        "Mrs. Jones signed the form.",
        "Ms. Jones signed the form.",
        "Dr. Jones signed the form.",
        "Hon. Jones presided that day.",
        "Reviewed by Jones Jr. today.",
        "Reviewed by Jones Sr. today.",
        "Located on St. James street.",
    ]
    for text in cases:
        sentences = [s.strip() for s in corpus_inject._split_sentences(text) if s.strip()]
        assert len(sentences) == 1, f"unexpected split for: {text!r} -> {sentences}"


def test_general_prose_abbreviations_not_split():
    cases = [
        "Bring supplies, e.g. rope and water.",
        "Bring supplies, i.e. rope and water.",
        "Pack snacks, etc. before leaving.",
        "Filed vs. the opposing party.",
        "Costs were approx. ten dollars.",
    ]
    for text in cases:
        sentences = [s.strip() for s in corpus_inject._split_sentences(text) if s.strip()]
        assert len(sentences) == 1, f"unexpected split for: {text!r} -> {sentences}"


def test_single_capital_initials_not_split():
    """Single-letter initials in names, e.g. the judge byline 'GORDON J. QUIST'."""
    cases = [
        "GORDON J. QUIST, District Judge.",
        "Opinion by A. Smith today.",
        "Signed by J. Doe below.",
    ]
    for text in cases:
        sentences = [s.strip() for s in corpus_inject._split_sentences(text) if s.strip()]
        assert len(sentences) == 1, f"unexpected split for: {text!r} -> {sentences}"


def test_decimal_number_sequence_not_split():
    sentences = [
        s.strip() for s in corpus_inject._split_sentences(
            "The rate is 3. 14 percent approximately. It never changes."
        ) if s.strip()
    ]
    assert sentences == ["The rate is 3. 14 percent approximately.", "It never changes."]


def test_ambiguous_abbreviation_then_new_sentence_documents_tradeoff():
    """Genuinely ambiguous case: an abbreviation legitimately ends a sentence and a
    new, capitalized sentence follows immediately. A stdlib-regex splitter cannot
    perfectly disambiguate this from a mid-citation period without semantic
    context. This implementation always suppresses the boundary after a known
    abbreviation (see the docstring on `_split_sentences` for the rationale): it
    protects host realism by never splitting — and therefore never injecting —
    inside a citation/title, at the cost of occasionally under-splitting two
    genuinely separate sentences into one. That is the intended, documented
    behaviour, not a bug.
    """
    text = "Filed in 1998 in the U.S. The court then ruled against the defendant."
    sentences = [s.strip() for s in corpus_inject._split_sentences(text) if s.strip()]
    assert sentences == [text]


def test_split_sentences_is_deterministic():
    text = (
        "Anderson v. Liberty Lobby, Inc., 477 U.S. 242, 248 (1986). "
        "Mr. Jones filed under 42 U.S.C. 1988. GORDON J. QUIST presided. "
        "The rate is 3.14 percent, e.g. as measured. Later cases agreed."
    )
    first = corpus_inject._split_sentences(text)
    second = corpus_inject._split_sentences(text)
    assert first == second


def test_punctuation_prefixed_abbreviation_not_split():
    """Regression: parenthesized/bracketed/quoted citations are the norm in legal
    text, e.g. "(Fed. Cir. 1995)" — so the token immediately before a candidate
    boundary routinely carries leading punctuation ("(Fed."). The abbreviation
    lookup previously matched the RAW token against `_ABBREVIATIONS`, so
    "(fed." (lowercased) never matched "fed." in the set and the text was
    split apart at "(Fed." — reproduced live via
    `_split_sentences('See Markman v. Westview Instruments, 52 F.3d 967, 34
    USPQ2d 1321 (Fed. Cir. 1995). Then this.')`, which returned 3 parts with
    the citation shattered at "(Fed." Leading punctuation must be stripped
    before the abbreviation/initial lookup."""
    cases = [
        ("See Markman v. Westview Instruments, 52 F.3d 967, 34 USPQ2d 1321 "
         "(Fed. Cir. 1995). Then this."),
        ("See Markman v. Westview Instruments, 52 F.3d 967, 34 USPQ2d 1321 "
         "[Fed. Cir. 1995]. Then this."),
        ('See Markman v. Westview Instruments, 52 F.3d 967, 34 USPQ2d 1321 '
         '("Fed. Cir. 1995"). Then this.'),
        ("The panel followed ('Cir. 1995') precedent directly. It controlled."),
        ("Filed by (Mr. Jones) that day. The clerk noted it."),
    ]
    for text in cases:
        sentences = [s.strip() for s in corpus_inject._split_sentences(text) if s.strip()]
        assert len(sentences) == 2, f"unexpected split for: {text!r} -> {sentences}"
        assert sentences[1] in ("Then this.", "It controlled.", "The clerk noted it.")


def test_reported_bug_case_produces_exactly_two_parts():
    """The exact string the coordinator reported as failing (3 parts, citation
    shattered at '(Fed.') must now produce exactly 2 parts with the citation
    intact end-to-end."""
    text = (
        "See Markman v. Westview Instruments, 52 F.3d 967, 34 USPQ2d 1321 "
        "(Fed. Cir. 1995). Then this."
    )
    sentences = [s.strip() for s in corpus_inject._split_sentences(text) if s.strip()]
    assert sentences == [
        "See Markman v. Westview Instruments, 52 F.3d 967, 34 USPQ2d 1321 (Fed. Cir. 1995).",
        "Then this.",
    ]


# ---------------------------------------------------------------------------
# mint_native_shaped_ids — closing the document-ID enumeration channel (767 I.3)
# ---------------------------------------------------------------------------

_CLERC_IDS = [str(1000731 + index * 7919) for index in range(200)]
_ENRON_IDS = [
    f"dasovich-j/dasovich-j/{folder}/{100 + index}."
    for folder in ("inbox", "all_documents", "sent")
    for index in range(50)
]
_MIRACL_IDS = [f"{140 + index}#{index % 30}" for index in range(200)]


def test_minted_ids_are_collision_free_against_hosts_and_each_other():
    minted = corpus_inject.mint_native_shaped_ids(_CLERC_IDS, 40, random.Random(707))
    assert len(minted) == 40
    assert len(set(minted)) == 40
    assert not set(minted) & set(_CLERC_IDS)


def test_minted_ids_match_the_host_character_class_and_length():
    for native in (_CLERC_IDS, _ENRON_IDS, _MIRACL_IDS):
        minted = corpus_inject.mint_native_shaped_ids(native, 20, random.Random(707))
        native_charset = set("".join(native))
        native_lengths = {len(i) for i in native}
        # Characters are a SUBSET of the hosts' by construction (only digits are
        # redrawn), which is what makes qrels-TSV and filename safety inherited
        # rather than separately asserted.
        assert set("".join(minted)) <= native_charset
        assert {len(i) for i in minted} <= native_lengths


def test_minted_ids_are_deterministic_for_the_same_rng_seed():
    first = corpus_inject.mint_native_shaped_ids(_CLERC_IDS, 25, random.Random(707))
    second = corpus_inject.mint_native_shaped_ids(_CLERC_IDS, 25, random.Random(707))
    assert first == second
    # Order-insensitive in the host list: the donor pool is sorted before any draw,
    # so a reordered (or duplicated) host corpus mints the identical ids.
    shuffled = list(reversed(_CLERC_IDS)) + _CLERC_IDS[:10]
    assert corpus_inject.mint_native_shaped_ids(shuffled, 25, random.Random(707)) == first


def test_minted_ids_never_carry_a_qrels_breaking_character():
    # qrels/test.tsv rows are tab-separated and newline-delimited (corpus_build), so a
    # tab/CR/LF in a doc id would silently corrupt the relevance judgments.
    for native in (_CLERC_IDS, _ENRON_IDS, _MIRACL_IDS):
        for minted in corpus_inject.mint_native_shaped_ids(native, 20, random.Random(707)):
            assert not any(bad in minted for bad in ("\t", "\n", "\r"))
            assert minted.strip() == minted and minted


def test_minting_fails_closed_when_no_host_id_has_a_digit_run():
    with pytest.raises(ValueError, match="digit run"):
        corpus_inject.mint_native_shaped_ids(["alpha", "beta"], 3, random.Random(707))


def test_minting_fails_closed_when_the_host_id_space_is_exhausted():
    # A single 1-digit donor offers 10 values, 1 of which is taken.
    with pytest.raises(ValueError, match="collision-free"):
        corpus_inject.mint_native_shaped_ids(["a1"], 40, random.Random(707))


def test_assemble_remaps_gold_ids_and_query_evidence_consistently(tmp_path):
    real_docs = [
        {"_id": doc_id, "title": "Host", "text": ("Real host sentence here. " * 80)}
        for doc_id in _CLERC_IDS[:20]
    ]
    fabricated = [
        {"_id": "breldac18", "title": "T1", "text": "Fabricated fact one."},
        {"_id": "brelker20", "title": "T2", "text": "Fabricated fact two."},
    ]
    queries = [{"query": "q", "answer": "a", "evidence_ids": ["breldac18", "brelker20"]}]

    docs, remapped, report = corpus_inject.assemble(
        real_docs, fabricated, queries, seed=707, n_distractors=5
    )

    doc_ids = {d["_id"] for d in docs}
    assert "breldac18" not in doc_ids and "brelker20" not in doc_ids
    # Every evidence id still resolves to a document in the assembled corpus — the
    # invariant corpus_build.build_golden raises on.
    assert set(remapped[0]["evidence_ids"]) <= doc_ids
    assert all(i.isdigit() for i in remapped[0]["evidence_ids"])
    # The caller's input is not mutated (assemble stays pure).
    assert queries[0]["evidence_ids"] == ["breldac18", "brelker20"]
    assert remapped[0]["query"] == "q" and remapped[0]["answer"] == "a"
    # The mapping is recorded so a skeptic can reconnect the committed fabricated
    # inputs to the assembled cell.
    assert [m["fabricated_id"] for m in report["gold_id_mapping"]] == [
        "breldac18", "brelker20"
    ]
    assert {m["assembled_id"] for m in report["gold_id_mapping"]} == set(
        remapped[0]["evidence_ids"]
    )
    assert all("fabricated_id" in row for row in report["host_mapping"])


def test_assembled_gold_ids_defeat_the_id_shape_leak_gate():
    # The end-to-end statement of this lane: the assembled cell's gold ids are not
    # separable from its natives by any simple rule over ids alone.
    real_docs = [
        {"_id": doc_id, "title": "Host", "text": ("Real host sentence here. " * 80)}
        for doc_id in _CLERC_IDS
    ]
    fabricated = [
        {"_id": f"breldac{index}", "title": "T", "text": "Fabricated fact."}
        for index in range(10)
    ]
    queries = [
        {"query": f"q{index}", "answer": "a", "evidence_ids": [f"breldac{index}"]}
        for index in range(10)
    ]
    docs, remapped, _report = corpus_inject.assemble(
        real_docs, fabricated, queries, seed=707, n_distractors=150
    )

    report = corpus_leak.id_shape_report(docs, remapped)
    assert report["n_gold"] == 10
    assert report["passed"] is True
    assert report["gold_shape_classes"] == {"all-digits": 10}

    # Control: the SAME corpus with the pre-767 id convention on the gold docs is
    # caught, so the pass above is the remapping's doing and not the gate going quiet.
    gold_ids = {q["evidence_ids"][0] for q in remapped}
    unfixed = [
        {**d, "_id": f"breldac{index}"} if d["_id"] in gold_ids else d
        for index, d in enumerate(docs)
    ]
    unfixed_queries = [
        {"query": q["query"], "evidence_ids": [
            next(u["_id"] for u, o in zip(unfixed, docs) if o["_id"] == q["evidence_ids"][0])
        ]}
        for q in remapped
    ]
    assert corpus_leak.id_shape_report(unfixed, unfixed_queries)["passed"] is False


def test_gold_ids_are_minted_from_the_host_min_words_ELIGIBLE_population_only():
    # Regression (tempdoc 767 rebuild): the donor pool was every id in `real_docs`, but
    # the natives a cell actually contains are only those passing `host_min_words`. On
    # CLERC the filter is not id-independent — short opinions skew to high ids — so gold
    # landed in a different numeric distribution from its own neighbours and `id_shape`
    # separated the 1k legal cell at J=0.177 against a 0.151 null.
    #
    # Here the two populations are made disjoint by construction: eligible ids all begin
    # `1000`, ineligible ones `9000`. `_perturb_digit_runs` redraws only the trailing 3
    # digits, so a minted id's first four digits name the population it was drawn from.
    eligible = [
        {"_id": f"1000{index:03d}", "title": "H", "text": ("Real host sentence here. " * 80)}
        for index in range(200)
    ]
    ineligible = [
        {"_id": f"9000{index:03d}", "title": "H", "text": "Too short to host."}
        for index in range(200)
    ]
    fabricated = [
        {"_id": f"breldac{index}", "title": "T", "text": "Fabricated fact."}
        for index in range(10)
    ]
    queries = [
        {"query": f"q{index}", "answer": "a", "evidence_ids": [f"breldac{index}"]}
        for index in range(10)
    ]
    docs, remapped, _report = corpus_inject.assemble(
        eligible + ineligible, fabricated, queries,
        seed=707, n_distractors=150, host_min_words=60,
    )
    minted = sorted({e for q in remapped for e in q["evidence_ids"]})
    assert len(minted) == 10
    assert all(m.startswith("1000") for m in minted), minted

    # And the wider real-id set still governs COLLISION exclusion, which is what keeps a
    # minted id off an ineligible host that a later, lower `host_min_words` would admit.
    assert not {m for m in minted} & {d["_id"] for d in eligible + ineligible}


# ---------------------------------------------------------------------------
# host (native) title synthesis — the tempdoc 781 §B.1 camouflaged-metadata fix.
# `assemble` used to write `title: ""` onto every native distractor while injected gold
# docs kept a populated title, so a field whose mere PRESENCE separated gold from native
# (field_selectivity J=1.0) that the 3.0x TITLE_BOOST lexical leg rewarded.
# ---------------------------------------------------------------------------

# A realistic email host: a `Subject:` header the synthesizer reads off structurally, then
# a body. No gold answer text appears here (gold is interleaved into the body at assembly).
_EMAIL_HOST_BODIES = [
    "Subject: Re: Q3 gas nominations and the pipeline schedule\n\n"
    "Please review the attached nominations before the Friday call. " * 12,
    "Subject: Fwd: revised counterparty credit memo\n\n"
    "The credit team flagged two exposures we should discuss next week. " * 12,
    "Subject: lunch\n\nAre we still on for the team lunch on Thursday at noon downtown. " * 12,
    "Subject:   \n\nNo subject on this one, just a quick note about the meeting agenda items. " * 12,
]
# A realistic legal-opinion host: no header at all, so the synthesizer falls to the opening
# sentence / caption fragment. Abbreviation-aware splitting keeps citations intact.
_LEGAL_HOST_BODIES = [
    "Anderson v. Liberty Lobby, Inc., 477 U.S. 242, 248 (1986), governs the summary "
    "judgment standard applied here. " * 12,
    "The plaintiff appeals the district court's dismissal of its antitrust claims under "
    "Fed. R. Civ. P. 12(b)(6). " * 12,
    "This matter comes before the court on the defendant's motion for reconsideration. " * 12,
    "In re Grand Jury Subpoena, the confidentiality question turns on the crime-fraud "
    "exception as articulated by this circuit. " * 12,
]


def _host_docs(bodies: list[str], id_base: int, count: int) -> list[dict]:
    """Native hosts with EMPTY titles (as real enron/CLERC hosts carry), cycling `bodies`.

    IDs are wide integers (like the real CLERC bare-integer host ids) so the gold-id mint
    has a roomy digit space to perturb — the shape of the id is orthogonal to this test."""
    return [
        {"_id": str(id_base + index * 7919), "title": "", "text": bodies[index % len(bodies)]}
        for index in range(count)
    ]


def test_synthesize_host_title_is_deterministic_for_same_seed_and_content():
    for body in _EMAIL_HOST_BODIES + _LEGAL_HOST_BODIES:
        a = corpus_inject._synthesize_host_title(body, seed=707)
        b = corpus_inject._synthesize_host_title(body, seed=707)
        assert a == b and a  # stable and non-empty


def test_synthesize_host_title_reads_the_subject_line_and_strips_reply_markers():
    title = corpus_inject._synthesize_host_title(_EMAIL_HOST_BODIES[0], seed=707)
    # The "Re:" plumbing is stripped; the content survives, no header colon leaks in.
    assert not title.lower().startswith("re")
    assert "subject" not in title.lower()
    assert "\n" not in title and title == title.strip()
    assert "nominations" in title.lower()


def test_synthesize_host_title_falls_to_opening_sentence_for_headerless_legal_text():
    title = corpus_inject._synthesize_host_title(_LEGAL_HOST_BODIES[0], seed=707)
    # The abbreviation-aware splitter must not shatter "477 U.S. 242" into the title.
    assert title.startswith("Anderson v. Liberty Lobby")
    assert "\n" not in title


def test_synthesize_host_title_reacts_to_seed_not_corpus_identity():
    # Same content, different cell seed -> the target length may differ, but the routine
    # never reads anything but (content, seed): identical (content, seed) is identical.
    body = _LEGAL_HOST_BODIES[1]
    assert corpus_inject._synthesize_host_title(body, seed=1) == \
        corpus_inject._synthesize_host_title(body, seed=1)
    # And an empty host yields an empty title (fails safe rather than inventing text).
    assert corpus_inject._synthesize_host_title("", seed=707) == ""


def _title_cell(bodies: list[str], id_base: int, seed: int = 707, n_distractors: int = 120):
    """Assemble a cell whose natives are `bodies`-shaped hosts with empty input titles."""
    # Gold titles mirror `corpus_generate`'s observed 3-7-word shapes ("The <entity>" /
    # "The <type> in the <place>") so the shape-parity assertion is meaningful.
    _gold_titles = [
        "The Qualibtors Controcharge",                      # 3
        "The Hento Furnishings, TN",                        # 4
        "The granary in the pine forest",                   # 6
        "The Newson",                                       # 2
        "The observatory in the derelict watchtower",       # 6
        "The Jtal Hol-ale",                                 # 3
        "The printing house in the market square",          # 7
        "The foundry in the western district",              # 6
    ]
    gold_source = [
        {"_id": f"breldac{index}", "title": _gold_titles[index],
         "text": f"The fabricated attribute is ochre ferrolite {index:04d}."}
        for index in range(8)
    ]
    queries = [
        {"query": f"q{index}", "answer": "a", "evidence_ids": [f"breldac{index}"]}
        for index in range(8)
    ]
    hosts = _host_docs(bodies, id_base, count=8 + n_distractors)
    docs, remapped, _report = corpus_inject.assemble(
        hosts, gold_source, queries, seed=seed, n_distractors=n_distractors, host_min_words=10,
    )
    return docs, remapped


def test_assemble_populates_native_titles_at_full_presence_parity():
    docs, remapped = _title_cell(_EMAIL_HOST_BODIES, 2000731)
    gold, native = corpus_leak._split_gold_native(docs, remapped)
    assert gold and native
    # Every native now carries a populated title (the presence leak is closed).
    assert all(corpus_leak._is_field_populated(d.get("title")) for d in native)
    assert all(corpus_leak._is_field_populated(d.get("title")) for d in gold)


def test_assemble_native_title_lengths_overlap_gold_title_lengths():
    # Shape-class parity (loose): the synthesized native title word-count distribution
    # must OVERLAP the gold titles', not merely be present. Ranges intersecting is the
    # loose bar this asserts.
    docs, remapped = _title_cell(_LEGAL_HOST_BODIES, 3000731)
    gold, native = corpus_leak._split_gold_native(docs, remapped)
    gold_lens = sorted(len(str(d["title"]).split()) for d in gold)
    native_lens = sorted(len(str(d["title"]).split()) for d in native)
    assert min(native_lens) <= max(gold_lens) and max(native_lens) >= min(gold_lens)


def test_assemble_defeats_the_field_selectivity_title_leak_before_and_after():
    # The acceptance demo (tempdoc 781 §B.1): pre-change the native title was `""`, so
    # field_selectivity separated gold from native at J=1.0; post-change it passes.
    docs, remapped = _title_cell(_EMAIL_HOST_BODIES, 2000731)

    after = corpus_leak.field_selectivity_report(docs, remapped)
    assert after["passed"] is True
    assert after["per_field"]["title"]["passed"] is True
    assert after["per_field"]["title"]["native_population_rate"] == 1.0
    assert after["per_field"]["title"]["separability"] <= \
        after["per_field"]["title"]["native_base_rate"]

    # Reconstruct the pre-change output (native title blanked) to show it FAILED at 1.0.
    gold_ids = {e for q in remapped for e in q.get("evidence_ids", [])}
    before_docs = [
        d if d["_id"] in gold_ids else {**d, "title": ""} for d in docs
    ]
    before = corpus_leak.field_selectivity_report(before_docs, remapped)
    assert before["passed"] is False
    assert before["worst_field"] == "title"
    assert before["per_field"]["title"]["separability"] == 1.0
    assert before["per_field"]["title"]["native_population_rate"] == 0.0


def test_assemble_native_title_is_not_answer_bearing():
    # The synthesized title derives from the host's OWN text, never the interleaved gold,
    # so no gold answer surface ("ochre ferrolite ####") can appear in a native title.
    docs, remapped = _title_cell(_EMAIL_HOST_BODIES, 2000731)
    _gold, native = corpus_leak._split_gold_native(docs, remapped)
    assert not any("ferrolite" in str(d["title"]).lower() for d in native)
