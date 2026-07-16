from __future__ import annotations

import json
import base64
import hashlib
from pathlib import Path

from jseval import corpus_build, corpus_certify, corpus_inject, corpus_query_strata


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


def test_checked_in_scientific_policy_is_ratified_clerc_four_cell():
    """Founder-ratified 2026-07-16 (tempdoc 707 owner-decision sheet item 1): the policy is
    ACTIVE with exactly the CLERC four-cell matrix; floors derived from the measured chain-1/2
    candidates (band low = measured - 0.08, union min = measured - 0.10, leak max = measured
    + 0.05, closed-book <= 0.15). The DE member is deliberately ABSENT (1k-only secondary
    stratum, never claim-bearing until the encoder-lane finding resolves) — an unknown member
    must still hard-fail."""
    policy = json.loads(corpus_certify.SCIENTIFIC_POLICY_PATH.read_text(encoding="utf-8"))
    assert policy["status"] == "active" and policy["unresolved"] == []
    cells = corpus_certify._active_scientific_policy_cells(policy, member="en-legal-clerc")
    assert set(cells) == {
        "mixed/en-legal-clerc-1k-verbose", "mixed/en-legal-clerc-1k-short-natural",
        "mixed/en-legal-clerc-10k-verbose", "mixed/en-legal-clerc-10k-short-natural"}
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
                # en-legal-clerc is fully certified since 2026-07-16 (founder-ratified
                # ACTIVE policy, 16/16 gates) — full certification requires every gate
                # to have actually passed, and only the claim-matrix member may carry it.
                assert member["name"] == "en-legal-clerc"
                assert set(certification["scientific_gates"].values()) == {"passed"}
            else:
                assert set(certification["scientific_gates"].values()) <= {
                    "pending-model-run", "pending-backend-run",
                }
    assert {member["name"] for member in members} == {
        "en-legal-clerc", "de-miracl", "en-email-enronqa",
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
