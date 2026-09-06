"""Collision-safe result identity and cluster-sidecar tests (tempdoc 897 P5)."""

from __future__ import annotations

import copy
import hashlib
import json
from pathlib import Path
from types import SimpleNamespace

import jsonschema
import pytest

from jseval import duplicate_prevalence as dp
from jseval.result_identity import (
    ResultIdentityError,
    build_result_identity_anchor,
    build_content_exact_result_identity_sidecar,
    build_result_identity_sidecar,
    raw_document_aliases,
    reconcile_delivered_hits,
    result_alias_commitment,
    result_mapping_public_key,
    validate_result_identity_sidecar,
    verify_result_mapping_attestation,
    verify_result_identity_anchor,
)


def _hit(path: str) -> dict:
    leaf = path.replace("\\", "/").rsplit("/", 1)[-1]
    return {"id": path, "fields": {"path": path, "filename": leaf}}


def _mode_result(paths_by_qid: dict[str, list[str]]) -> dict:
    scored = []
    raw = []
    metrics = {}
    for qid, paths in paths_by_qid.items():
        metrics[qid] = {"R@10": 0.0}
        hits = [_hit(path) for path in paths]
        raw.append({"query_id": qid, "results": hits})
        for hit in hits:
            leaf = hit["fields"]["filename"]
            doc_id = leaf.rsplit(".", 1)[0].lower()
            scored.append(SimpleNamespace(query_id=qid, doc_id=doc_id, score=1.0))
    return {"scored_docs": scored, "raw_responses": raw, "per_query_metrics": metrics}


def _entries(paths_by_qid: dict[str, list[str]]) -> list[dict]:
    return [
        {
            "qid": qid,
            "predictedDocIds": [
                path.replace("\\", "/").rsplit("/", 1)[-1].rsplit(".", 1)[0].lower()
                for path in paths
            ],
        }
        for qid, paths in sorted(paths_by_qid.items())
    ]


def _schema() -> dict:
    path = Path(__file__).parents[1] / "result-identity-sidecar.v1.schema.json"
    return json.loads(path.read_text(encoding="utf-8"))


def _duplicate_schema() -> dict:
    path = Path(__file__).parents[1] / "duplicate-prevalence.v1.schema.json"
    return json.loads(path.read_text(encoding="utf-8"))


def _canonical_digest(value: object) -> str:
    payload = json.dumps(
        value, sort_keys=True, separators=(",", ":"), ensure_ascii=False, allow_nan=False,
    ).encode("utf-8")
    return hashlib.sha256(payload).hexdigest()


def _production_sidecar(paths_by_qid: dict[str, list[str]], text_by_path: dict[str, str]):
    alias_commitment_key = b"test-only-private-alias-key-32b!"
    mapping_signing_key = bytes(range(32))
    unique_paths = sorted({path for paths in paths_by_qid.values() for path in paths})
    observations = [
        dp.DocumentObservation(
            opaque_id=f"observation-{index}",
            raw_sha256=hashlib.sha256(f"raw:{path}".encode()).hexdigest(),
            extracted_text=text_by_path[path],
            format_id=path.rsplit(".", 1)[-1],
            source_kind=dp.PRODUCTION_EXTRACTED,
            extraction_status="success",
        )
        for index, path in enumerate(unique_paths, start=1)
    ]
    corpus_identity = {
        "profile_id": None,
        "signature": "b" * 64,
        "kind": "raw-files",
        "schema": "jseval.raw-corpus-manifest.v1",
        "file_count": len(observations),
        "total_bytes": len(observations) * 10,
        "manifest_pointer": "PRIVATE-CORPUS-PATH/strict.json",
        "admission_policy": {
            "JUSTSEARCH_INGESTION_SKIP_PATTERNS": "default",
            "JUSTSEARCH_INGESTION_SKIP_EXTENSIONS": "default",
            "JUSTSEARCH_INGESTION_SKIP_DIRECTORY_NAMES": "default",
        },
    }
    path_by_observation = dict(zip((item.opaque_id for item in observations), unique_paths, strict=True))
    aliases = {
        opaque_id: raw_document_aliases(_hit(path))
        for opaque_id, path in path_by_observation.items()
    }
    unsigned_extraction = {
        "schema": dp.EXTRACTION_SNAPSHOT_SCHEMA,
        "corpus_signature": corpus_identity["signature"],
        "observations_digest": dp.observation_commitment(observations),
        "source_kind": dp.PRODUCTION_EXTRACTED,
        "extractor_build": "worker@test",
        "extraction_policy_digest": "d" * 64,
        "result_aliases_hmac_sha256": result_alias_commitment(
            aliases,
            key=alias_commitment_key,
        ),
        "result_mapping_public_key_ed25519": result_mapping_public_key(mapping_signing_key),
        "document_count": len(observations),
        "reconciliation": {
            "status": "matched",
            "expected_count": len(observations),
            "exported_count": len(observations),
            "unique_opaque_ids": len(observations),
        },
    }
    extraction_identity = {
        "digest": _canonical_digest(unsigned_extraction),
        **unsigned_extraction,
    }
    config = dp.AnalysisConfig(
        shingle_width=1,
        max_hamming=3,
        jaccard_thresholds=(0.8,),
        exhaustive_slice_size=20,
        bootstrap_draws=10,
        seed=7,
        max_candidate_pairs=1_000,
    )
    analysis = dp.analyze(
        observations,
        corpus_identity=corpus_identity,
        extraction_identity=extraction_identity,
        config=config,
    )
    sidecar = build_content_exact_result_identity_sidecar(
        {"hybrid": _mode_result(paths_by_qid)},
        observations=observations,
        aliases_by_opaque_id=aliases,
        result_alias_commitment_key=alias_commitment_key,
        result_mapping_signing_key=mapping_signing_key,
        corpus_identity=corpus_identity,
        extraction_identity=extraction_identity,
        analysis_artifact=analysis,
        config=config,
    )
    return (
        sidecar, analysis, observations, aliases, corpus_identity,
        extraction_identity, config, alias_commitment_key, mapping_signing_key,
    )


def test_same_leaf_in_different_directories_and_cross_format_stay_distinct():
    paths = {
        "q1": ["/a/report.txt", "/b/report.txt", "/b/report.pdf"],
        "q2": ["/a/report.txt"],
    }
    sidecar = build_result_identity_sidecar({"hybrid": _mode_result(paths)})
    reconciled = reconcile_delivered_hits(sidecar, "hybrid", _entries(paths))

    assert len(set(reconciled["q1"])) == 3
    assert reconciled["q2"] == [reconciled["q1"][0]]
    assert all(value == "report" for value in _entries(paths)[0]["predictedDocIds"])
    assert "/a/" not in json.dumps(sidecar) and "/b/" not in json.dumps(sidecar)


def test_absent_and_ambiguous_sidecar_entries_fail_closed():
    paths = {"q1": ["/a/report.txt", "/b/report.txt"]}
    sidecar = build_result_identity_sidecar({"hybrid": _mode_result(paths)})
    entries = _entries(paths)

    absent = copy.deepcopy(sidecar)
    absent["modes"][0]["queries"][0]["hits"].pop()
    with pytest.raises(ResultIdentityError, match="document_count|hit counts"):
        reconcile_delivered_hits(absent, "hybrid", entries)

    ambiguous = copy.deepcopy(sidecar)
    hits = ambiguous["modes"][0]["queries"][0]["hits"]
    hits[1]["opaque_id"] = hits[0]["opaque_id"]
    ambiguous["document_count"] = 1
    with pytest.raises(ResultIdentityError, match="appears multiple times"):
        validate_result_identity_sidecar(ambiguous)


def test_legacy_unresolvable_hit_is_skipped_before_raw_identity_is_required():
    mode_result = _mode_result({"q1": ["/a/kept.txt"]})
    mode_result["raw_responses"][0]["results"].insert(0, {})

    sidecar = build_result_identity_sidecar({"hybrid": mode_result})

    hits = sidecar["modes"][0]["queries"][0]["hits"]
    assert len(hits) == 1
    assert hits[0]["legacy_doc_id"] == "kept"


def test_schema_and_manual_validator_reject_unknown_or_wrong_version_fields():
    sidecar = build_result_identity_sidecar({"hybrid": _mode_result({"q1": ["/a/x.txt"]})})
    jsonschema.validate(sidecar, _schema())

    wrong = copy.deepcopy(sidecar)
    wrong["schema"] = "jseval.result-identity-sidecar.v2"
    with pytest.raises(ResultIdentityError, match="unsupported"):
        validate_result_identity_sidecar(wrong)
    with pytest.raises(jsonschema.ValidationError):
        jsonschema.validate(wrong, _schema())

    extra = copy.deepcopy(sidecar)
    extra["source_path"] = "/private/x.txt"
    with pytest.raises(ResultIdentityError, match="keys mismatch"):
        validate_result_identity_sidecar(extra)
    with pytest.raises(jsonschema.ValidationError):
        jsonschema.validate(extra, _schema())


def test_schema_and_manual_validator_both_reject_non_null_exact_threshold():
    sidecar, *_ = _production_sidecar({"q1": ["/a/x.txt"]}, {"/a/x.txt": "text"})
    sidecar["cluster_assignments"]["cluster_source"]["threshold"] = 0.8

    with pytest.raises(ResultIdentityError, match="null threshold"):
        validate_result_identity_sidecar(sidecar)
    with pytest.raises(jsonschema.ValidationError):
        jsonschema.validate(sidecar, _schema())


def test_content_exact_clusters_are_derived_from_revalidated_production_observations():
    paths = {"q1": ["/a/x.txt", "/b/x.pdf", "/c/y.txt"]}
    sidecar, analysis, *_ = _production_sidecar(
        paths,
        {"/a/x.txt": "same text", "/b/x.pdf": "same   text", "/c/y.txt": "other text"},
    )

    jsonschema.validate(sidecar, _schema())
    jsonschema.validate(analysis, _duplicate_schema())
    verify_result_mapping_attestation(sidecar, analysis)
    anchor = build_result_identity_anchor(sidecar, analysis)
    verify_result_identity_anchor(sidecar, analysis, anchor)
    serialized_analysis = json.dumps(analysis)
    assert "/a/x.txt" not in serialized_analysis
    assert "test-only-private-alias-key" not in serialized_analysis
    records = sidecar["cluster_assignments"]["assignments"]
    assert records[0]["cluster_id"] == records[1]["cluster_id"]
    assert records[0]["content_fingerprint_sha256"] == records[1]["content_fingerprint_sha256"]
    assert records[2]["cluster_id"] != records[0]["cluster_id"]
    assert sidecar["cluster_assignments"]["cluster_source"] == {
        "analysis_artifact_sha256": analysis["artifact_hash"],
        "analysis_artifact_filename": "duplicate_prevalence.v1.json",
        "semantics": "normalized-content-exact",
        "decision": "CONFIRMED",
        "threshold": None,
    }
    assert all(
        record["cluster_id"].startswith(f"rcluster-{sidecar['run_instance']}-")
        for record in records
    )

    duplicate = copy.deepcopy(sidecar)
    duplicate["cluster_assignments"]["assignments"].append(
        copy.deepcopy(duplicate["cluster_assignments"]["assignments"][0])
    )
    with pytest.raises(ResultIdentityError, match="ambiguous duplicate"):
        validate_result_identity_sidecar(duplicate)


def test_content_exact_builder_rejects_artifact_or_alias_tampering():
    paths = {"q1": ["/a/x.txt", "/b/x.txt"]}
    (
        sidecar, analysis, observations, aliases, corpus, extraction, config,
        commitment_key, mapping_signing_key,
    ) = _production_sidecar(
        paths,
        {"/a/x.txt": "same text", "/b/x.txt": "same text"},
    )
    assert sidecar["cluster_assignments"]["cluster_source"]["decision"] == "CONFIRMED"

    tampered = copy.deepcopy(analysis)
    tampered["content_exact"]["duplicate_documents"] = 0
    with pytest.raises(ResultIdentityError, match="does not match deterministic re-analysis"):
        build_content_exact_result_identity_sidecar(
            {"hybrid": _mode_result(paths)},
            observations=observations,
            aliases_by_opaque_id=aliases,
            result_alias_commitment_key=commitment_key,
            result_mapping_signing_key=mapping_signing_key,
            corpus_identity=corpus,
            extraction_identity=extraction,
            analysis_artifact=tampered,
            config=config,
        )

    swapped_aliases = copy.deepcopy(aliases)
    observation_ids = sorted(swapped_aliases)
    swapped_aliases[observation_ids[0]], swapped_aliases[observation_ids[1]] = (
        swapped_aliases[observation_ids[1]],
        swapped_aliases[observation_ids[0]],
    )
    with pytest.raises(ResultIdentityError, match="extraction snapshot commitment"):
        build_content_exact_result_identity_sidecar(
            {"hybrid": _mode_result(paths)},
            observations=observations,
            aliases_by_opaque_id=swapped_aliases,
            result_alias_commitment_key=commitment_key,
            result_mapping_signing_key=mapping_signing_key,
            corpus_identity=corpus,
            extraction_identity=extraction,
            analysis_artifact=analysis,
            config=config,
        )
