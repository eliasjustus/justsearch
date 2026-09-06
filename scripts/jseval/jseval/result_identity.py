"""Collision-safe, run-local identity for delivered search results.

The normal jseval document id is deliberately the BEIR/qrel id. It is not a
safe join key for duplicate analysis: resolving a hit strips its directory and
extension, so two different indexed files can collapse to the same value.

This module creates a local-only sidecar while the raw response still contains
the indexed path/id. Source identities are retained only in memory. Persisted
ids are random-run-namespaced opaque labels, never plain hashes of paths.
"""

from __future__ import annotations

import hashlib
import hmac
import json
import re
import uuid
from collections.abc import Mapping, Sequence
from dataclasses import dataclass

from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric.ed25519 import (
    Ed25519PrivateKey,
    Ed25519PublicKey,
)

from . import duplicate_prevalence as duplicate_prevalence
from .retriever import resolve_doc_id


SIDECAR_SCHEMA = "jseval.result-identity-sidecar.v1"
CLUSTER_ASSIGNMENT_SCHEMA = "jseval.result-cluster-assignment.v1"
ANCHOR_SCHEMA = "jseval.result-identity-anchor.v1"
SIDECAR_FILENAME = "result_identity.v1.json"
PRIVACY_CLASSIFICATION = "local-run-opaque"

_HEX_SHA256 = re.compile(r"^[0-9a-f]{64}$")
_ED25519_SIGNATURE = re.compile(r"^[0-9a-f]{128}$")
_OPAQUE_ID = re.compile(r"^rdoc-[0-9a-f]{32}-[0-9]{8}$")
_CLUSTER_ID = re.compile(r"^rcluster-[0-9a-f]{32}-[0-9]{8}$")


class ResultIdentityError(ValueError):
    """The result-identity sidecar cannot be built or reconciled safely."""


@dataclass(frozen=True, repr=False)
class ContentExactResultIdentityInputs:
    """Private, in-process inputs for one production content-exact join.

    Paths, extracted text, HMAC material, and the Ed25519 private key are
    intentionally carried only by this value.  ``artifacts.write_run`` consumes
    it directly; no intermediate scratch artifact or environment transport is
    permitted.
    """

    observations: Sequence[duplicate_prevalence.DocumentObservation]
    aliases_by_opaque_id: Mapping[str, Sequence[str]]
    result_alias_commitment_key: bytes
    result_mapping_signing_key: bytes
    corpus_identity: Mapping
    extraction_identity: Mapping
    analysis_artifact: Mapping
    config: duplicate_prevalence.AnalysisConfig


def _exact_keys(value: Mapping, expected: set[str], context: str) -> None:
    actual = set(value)
    if actual != expected:
        missing = sorted(expected - actual)
        extra = sorted(actual - expected)
        raise ResultIdentityError(f"{context} keys mismatch: missing={missing}, extra={extra}")


def _canonical_sha256(value: object) -> str:
    encoded = json.dumps(
        value,
        sort_keys=True,
        separators=(",", ":"),
        ensure_ascii=False,
        allow_nan=False,
    ).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()


def raw_document_aliases(hit: Mapping) -> tuple[str, ...]:
    """Return the collision-safe source identity of a raw API hit.

    A bare filename is intentionally insufficient. Full indexed path wins,
    then provenance path, then the unmodified Worker/Head hit id. The caller
    must discard this value after assigning its run-local opaque id.
    """
    if not isinstance(hit, Mapping):
        raise ResultIdentityError("raw result hit must be an object")
    fields = hit.get("fields") or {}
    provenance = hit.get("provenance") or {}
    if not isinstance(fields, Mapping) or not isinstance(provenance, Mapping):
        raise ResultIdentityError("raw result fields/provenance must be objects")
    candidates = (
        ("path", fields.get("path")),
        ("provenance-path", provenance.get("path")),
        ("hit-id", hit.get("id")),
    )
    aliases = tuple(
        f"{kind}\0{value}"
        for kind, value in candidates
        if isinstance(value, str) and value
    )
    if aliases:
        return aliases
    raise ResultIdentityError(
        "raw result has no collision-safe path/provenance path/hit id; filename alone is unsafe"
    )


def raw_document_identity(hit: Mapping) -> str:
    """Return the preferred raw identity alias for identity-only capture."""
    return raw_document_aliases(hit)[0]


def _build_result_identity_sidecar(
    mode_results: Mapping[str, Mapping],
    *,
    corpus_signature: str | None = None,
    observation_by_alias: Mapping[str, str] | None = None,
    fingerprint_by_observation: Mapping[str, str] | None = None,
    analysis_artifact_sha256: str | None = None,
    result_mapping_signing_key: bytes | None = None,
) -> dict:
    """Build a closed, path-free sidecar from raw delivered hits.

    This also proves a one-to-one, delivered-order reconciliation with the
    already-normalized ``scored_docs`` sequence. Hits skipped by
    ``retrieve(..., allow_errors=True)`` are skipped here by the same legacy-id
    resolution rule.
    """
    if not isinstance(mode_results, Mapping) or not mode_results:
        raise ResultIdentityError("mode_results must be a non-empty object")
    if corpus_signature is not None and (
        not isinstance(corpus_signature, str) or not _HEX_SHA256.fullmatch(corpus_signature)
    ):
        raise ResultIdentityError("corpus_signature must be null or lowercase hex SHA-256")

    run_instance = uuid.uuid4().hex
    opaque_by_raw: dict[str, str] = {}
    legacy_by_opaque: dict[str, str] = {}
    fingerprint_by_opaque: dict[str, str] = {}
    modes: list[dict] = []

    for mode in sorted(mode_results):
        mode_result = mode_results[mode]
        if not isinstance(mode, str) or not mode or not isinstance(mode_result, Mapping):
            raise ResultIdentityError("mode_results must map non-empty mode names to objects")

        docs_by_qid: dict[str, list[str]] = {}
        for scored in mode_result.get("scored_docs") or []:
            qid = getattr(scored, "query_id", None)
            doc_id = getattr(scored, "doc_id", None)
            if not isinstance(qid, str) or not qid or not isinstance(doc_id, str) or not doc_id:
                raise ResultIdentityError(f"mode {mode!r} has malformed scored_docs")
            docs_by_qid.setdefault(qid, []).append(doc_id)

        response_by_qid: dict[str, Mapping] = {}
        for response in mode_result.get("raw_responses") or []:
            if not isinstance(response, Mapping):
                raise ResultIdentityError(f"mode {mode!r} raw response must be an object")
            qid = response.get("query_id")
            if not isinstance(qid, str) or not qid:
                raise ResultIdentityError(f"mode {mode!r} raw response has no query_id")
            if qid in response_by_qid:
                raise ResultIdentityError(f"mode {mode!r} has duplicate raw response for {qid!r}")
            response_by_qid[qid] = response

        metric_qids = set((mode_result.get("per_query_metrics") or {}).keys())
        missing_responses = (metric_qids | set(docs_by_qid)) - set(response_by_qid)
        if missing_responses:
            raise ResultIdentityError(
                f"mode {mode!r} has scored/metric qids without raw responses: "
                f"{sorted(missing_responses)}"
            )

        queries: list[dict] = []
        for qid in sorted(response_by_qid):
            response = response_by_qid[qid]
            if "results" not in response:
                if response.get("error") and not docs_by_qid.get(qid):
                    raw_hits = []
                else:
                    raise ResultIdentityError(
                        f"mode {mode!r} query {qid!r} has no raw delivered results"
                    )
            else:
                raw_hits = response["results"]
            if not isinstance(raw_hits, list):
                raise ResultIdentityError(
                    f"mode {mode!r} query {qid!r} raw results must be an array"
                )

            expected_docs = docs_by_qid.get(qid, [])
            hits: list[dict] = []
            for raw_hit in raw_hits:
                try:
                    legacy_doc_id = resolve_doc_id(dict(raw_hit))
                except ValueError:
                    continue
                aliases = raw_document_aliases(raw_hit)
                if observation_by_alias is None:
                    raw_identity = aliases[0]
                    fingerprint = None
                else:
                    missing_aliases = [alias for alias in aliases if alias not in observation_by_alias]
                    if missing_aliases:
                        raise ResultIdentityError(
                            "raw hit exposes an alias absent from the production observation catalog"
                        )
                    matched = {observation_by_alias[alias] for alias in aliases}
                    if len(matched) != 1:
                        raise ResultIdentityError(
                            f"raw hit aliases resolve to {len(matched)} production observations; expected one"
                        )
                    observation_id = next(iter(matched))
                    raw_identity = f"observation\0{observation_id}"
                    fingerprint = (fingerprint_by_observation or {}).get(observation_id)
                    if fingerprint is None:
                        raise ResultIdentityError(
                            "delivered hit has no successful non-empty production content fingerprint"
                        )
                position = len(hits)
                if position >= len(expected_docs) or expected_docs[position] != legacy_doc_id:
                    observed = expected_docs[position] if position < len(expected_docs) else None
                    raise ResultIdentityError(
                        f"mode {mode!r} query {qid!r} delivered hit {position + 1} "
                        f"does not reconcile: raw={legacy_doc_id!r}, scored={observed!r}"
                    )
                opaque_id = opaque_by_raw.get(raw_identity)
                if opaque_id is None:
                    opaque_id = f"rdoc-{run_instance}-{len(opaque_by_raw) + 1:08d}"
                    opaque_by_raw[raw_identity] = opaque_id
                    legacy_by_opaque[opaque_id] = legacy_doc_id
                    if fingerprint is not None:
                        fingerprint_by_opaque[opaque_id] = fingerprint
                elif legacy_by_opaque[opaque_id] != legacy_doc_id:
                    raise ResultIdentityError(
                        "one raw document identity resolved to multiple legacy document ids"
                    )
                hits.append({
                    "delivered_rank": position + 1,
                    "legacy_doc_id": legacy_doc_id,
                    "opaque_id": opaque_id,
                })
            if len(hits) != len(expected_docs):
                raise ResultIdentityError(
                    f"mode {mode!r} query {qid!r} delivered/scored hit counts do not reconcile: "
                    f"raw_resolved={len(hits)}, scored={len(expected_docs)}"
                )
            queries.append({"qid": qid, "hits": hits})
        modes.append({"mode": mode, "queries": queries})

    sidecar = {
        "schema": SIDECAR_SCHEMA,
        "privacy": {
            "classification": PRIVACY_CLASSIFICATION,
            "contains_source_paths": False,
            "contains_plain_path_hashes": False,
        },
        "run_instance": run_instance,
        "corpus_signature": corpus_signature,
        "document_count": len(opaque_by_raw),
        "modes": modes,
    }
    if observation_by_alias is not None:
        if not isinstance(analysis_artifact_sha256, str) or not _HEX_SHA256.fullmatch(
            analysis_artifact_sha256
        ):
            raise ResultIdentityError("content-exact capture requires an analysis artifact hash")
        cluster_by_fingerprint = {
            fingerprint: f"rcluster-{run_instance}-{index:08d}"
            for index, fingerprint in enumerate(sorted(set(fingerprint_by_opaque.values())), start=1)
        }
        if result_mapping_signing_key is None:
            raise ResultIdentityError("content-exact capture requires a mapping signing key")
        cluster_assignments = {
            "schema": CLUSTER_ASSIGNMENT_SCHEMA,
            "cluster_source": {
                "analysis_artifact_sha256": analysis_artifact_sha256,
                "analysis_artifact_filename": "duplicate_prevalence.v1.json",
                "semantics": "normalized-content-exact",
                "decision": "CONFIRMED",
                "threshold": None,
            },
            "assignments": [
                {
                    "opaque_id": opaque,
                    "content_fingerprint_sha256": fingerprint_by_opaque[opaque],
                    "cluster_id": cluster_by_fingerprint[fingerprint_by_opaque[opaque]],
                }
                for opaque in sorted(fingerprint_by_opaque)
            ],
        }
        sidecar["cluster_assignments"] = cluster_assignments
        signing_key = _mapping_private_key(result_mapping_signing_key)
        cluster_assignments["mapping_signature_ed25519"] = signing_key.sign(
            _mapping_attestation_payload(sidecar)
        ).hex()
    validate_result_identity_sidecar(sidecar)
    return sidecar


def build_result_identity_sidecar(
    mode_results: Mapping[str, Mapping],
    *,
    corpus_signature: str | None = None,
) -> dict:
    """Build identity-only capture; no duplicate claim is emitted."""
    return _build_result_identity_sidecar(
        mode_results,
        corpus_signature=corpus_signature,
    )


def build_content_exact_result_identity_sidecar(
    mode_results: Mapping[str, Mapping],
    *,
    observations: Sequence[duplicate_prevalence.DocumentObservation],
    aliases_by_opaque_id: Mapping[str, Sequence[str]],
    result_alias_commitment_key: bytes,
    result_mapping_signing_key: bytes,
    corpus_identity: Mapping,
    extraction_identity: Mapping,
    analysis_artifact: Mapping,
    config: duplicate_prevalence.AnalysisConfig,
) -> dict:
    """Build a sidecar joined to revalidated production content-exact clusters.

    Private aliases are consumed only in memory. The complete P4 analysis is
    deterministically re-run over the supplied P6 observations; callers cannot
    self-assert cluster labels, confirmation, or artifact provenance.
    """
    alias_commitment = result_alias_commitment(
        aliases_by_opaque_id,
        key=result_alias_commitment_key,
    )
    if extraction_identity.get("result_aliases_hmac_sha256") != alias_commitment:
        raise ResultIdentityError(
            "production alias catalog does not match the extraction snapshot commitment"
        )
    expected_public_key = result_mapping_public_key(result_mapping_signing_key)
    if extraction_identity.get("result_mapping_public_key_ed25519") != expected_public_key:
        raise ResultIdentityError(
            "mapping signing key does not match the extraction snapshot verification key"
        )
    expected = duplicate_prevalence.analyze(
        observations,
        corpus_identity=corpus_identity,
        extraction_identity=extraction_identity,
        config=config,
    )
    if dict(analysis_artifact) != expected:
        raise ResultIdentityError(
            "duplicate-prevalence artifact does not match deterministic re-analysis"
        )
    corpus_signature = expected["input"]["corpus_identity"]["signature"]
    if any(item.source_kind != duplicate_prevalence.PRODUCTION_EXTRACTED for item in observations):
        raise ResultIdentityError("result clusters require production-extracted observations")
    observation_ids = {item.opaque_id for item in observations}
    if set(aliases_by_opaque_id) != observation_ids:
        raise ResultIdentityError("private alias catalog must cover production observations exactly")

    observation_by_alias: dict[str, str] = {}
    for opaque_id in sorted(aliases_by_opaque_id):
        aliases = aliases_by_opaque_id[opaque_id]
        if not isinstance(aliases, Sequence) or isinstance(aliases, (str, bytes)) or not aliases:
            raise ResultIdentityError("each production observation requires raw identity aliases")
        for alias in aliases:
            if not isinstance(alias, str) or not alias or "\0" not in alias:
                raise ResultIdentityError("production observation alias is malformed")
            kind, _separator, value = alias.partition("\0")
            if kind not in {"path", "provenance-path", "hit-id"} or not value:
                raise ResultIdentityError("production observation alias kind/value is unsupported")
            previous = observation_by_alias.setdefault(alias, opaque_id)
            if previous != opaque_id:
                raise ResultIdentityError("one raw identity alias maps to multiple observations")

    fingerprint_by_observation: dict[str, str] = {}
    for item in observations:
        if item.extraction_status != "success" or item.extracted_text is None:
            continue
        normalized = duplicate_prevalence.normalize_content(item.extracted_text)
        if normalized:
            fingerprint_by_observation[item.opaque_id] = hashlib.sha256(
                normalized.encode("utf-8")
            ).hexdigest()

    return _build_result_identity_sidecar(
        mode_results,
        corpus_signature=corpus_signature,
        observation_by_alias=observation_by_alias,
        fingerprint_by_observation=fingerprint_by_observation,
        analysis_artifact_sha256=expected["artifact_hash"],
        result_mapping_signing_key=result_mapping_signing_key,
    )


def result_alias_commitment(
    aliases_by_opaque_id: Mapping[str, Sequence[str]],
    *,
    key: bytes,
) -> str:
    """HMAC a private observation-to-alias catalog without exposing path hashes.

    The key belongs to private scratch and must never be persisted beside the
    aggregate artifact. A minimum 256-bit key keeps the committed value from
    becoming a dictionary-testable hash of private paths.
    """
    if not isinstance(key, bytes) or len(key) < 32:
        raise ResultIdentityError("result alias commitment key must contain at least 32 bytes")
    if not isinstance(aliases_by_opaque_id, Mapping):
        raise ResultIdentityError("private alias catalog must be a mapping")
    documents = []
    for opaque_id in sorted(aliases_by_opaque_id):
        aliases = aliases_by_opaque_id[opaque_id]
        if not isinstance(opaque_id, str) or not opaque_id:
            raise ResultIdentityError("private alias catalog has an invalid observation id")
        if not isinstance(aliases, Sequence) or isinstance(aliases, (str, bytes)) or not aliases:
            raise ResultIdentityError("each production observation requires raw identity aliases")
        if any(not isinstance(alias, str) or not alias for alias in aliases):
            raise ResultIdentityError("private alias catalog contains a malformed alias")
        documents.append({"opaque_id": opaque_id, "aliases": sorted(set(aliases))})
    payload = {
        "schema": "jseval.result-alias-commitment.v1",
        "documents": documents,
    }
    encoded = json.dumps(
        payload,
        sort_keys=True,
        separators=(",", ":"),
        ensure_ascii=False,
        allow_nan=False,
    ).encode("utf-8")
    return hmac.new(
        key,
        b"jseval.result-alias-commitment.v1\0" + encoded,
        hashlib.sha256,
    ).hexdigest()


def _mapping_private_key(key: bytes) -> Ed25519PrivateKey:
    if not isinstance(key, bytes) or len(key) != 32:
        raise ResultIdentityError("result mapping signing key must contain exactly 32 bytes")
    return Ed25519PrivateKey.from_private_bytes(key)


def result_mapping_public_key(signing_key: bytes) -> str:
    """Return the raw Ed25519 verification key for an in-scratch signing key."""
    return _mapping_private_key(signing_key).public_key().public_bytes(
        encoding=serialization.Encoding.Raw,
        format=serialization.PublicFormat.Raw,
    ).hex()


def _mapping_attestation_payload(sidecar: Mapping) -> bytes:
    """Canonical complete decorated-sidecar payload, excluding its signature."""
    unsigned = dict(sidecar)
    assignments = dict(unsigned["cluster_assignments"])
    assignments.pop("mapping_signature_ed25519", None)
    unsigned["cluster_assignments"] = assignments
    encoded = json.dumps(
        unsigned,
        sort_keys=True,
        separators=(",", ":"),
        ensure_ascii=False,
        allow_nan=False,
    ).encode("utf-8")
    return b"jseval.result-mapping-attestation.v1\0" + encoded


def verify_result_mapping_attestation(
    sidecar: Mapping,
    analysis_artifact: Mapping,
) -> None:
    """Verify the persisted result/cluster join against the extraction key."""
    validate_result_identity_sidecar(sidecar)
    extraction_identity = ((analysis_artifact.get("input") or {}).get("extraction_identity") or {})
    public_hex = extraction_identity.get("result_mapping_public_key_ed25519")
    if not isinstance(public_hex, str) or not _HEX_SHA256.fullmatch(public_hex):
        raise ResultIdentityError(
            "analysis artifact has no valid result mapping verification key"
        )
    signature_hex = sidecar["cluster_assignments"]["mapping_signature_ed25519"]
    try:
        public_key = Ed25519PublicKey.from_public_bytes(bytes.fromhex(public_hex))
        public_key.verify(
            bytes.fromhex(signature_hex),
            _mapping_attestation_payload(sidecar),
        )
    except (InvalidSignature, ValueError) as exc:
        raise ResultIdentityError(
            "result mapping attestation does not match the persisted sidecar"
        ) from exc


def build_result_identity_anchor(
    sidecar: Mapping,
    analysis_artifact: Mapping | None = None,
) -> dict:
    """Build the independently persisted summary anchor for a sidecar."""
    validate_result_identity_sidecar(sidecar)
    mapping_public_key = None
    analysis_artifact_sha256 = None
    if "cluster_assignments" in sidecar:
        if not isinstance(analysis_artifact, Mapping):
            raise ResultIdentityError("decorated result identity anchor requires analysis artifact")
        try:
            duplicate_prevalence.validate_artifact_hash(analysis_artifact)
        except duplicate_prevalence.DuplicatePrevalenceError as exc:
            raise ResultIdentityError(f"invalid analysis artifact for result anchor: {exc}") from exc
        analysis_artifact_sha256 = analysis_artifact["artifact_hash"]
        source_hash = sidecar["cluster_assignments"]["cluster_source"][
            "analysis_artifact_sha256"
        ]
        if analysis_artifact_sha256 != source_hash:
            raise ResultIdentityError("result anchor analysis hash does not match sidecar source")
        extraction_identity = ((analysis_artifact.get("input") or {}).get("extraction_identity") or {})
        mapping_public_key = extraction_identity.get("result_mapping_public_key_ed25519")
        if not isinstance(mapping_public_key, str) or not _HEX_SHA256.fullmatch(mapping_public_key):
            raise ResultIdentityError("result anchor requires analyzer-committed mapping public key")
        verify_result_mapping_attestation(sidecar, analysis_artifact)
    elif analysis_artifact is not None:
        raise ResultIdentityError("identity-only result anchor must not claim an analysis artifact")
    return {
        "schema": ANCHOR_SCHEMA,
        "run_instance": sidecar["run_instance"],
        "sidecar_content_sha256": _canonical_sha256(sidecar),
        "analysis_artifact_sha256": analysis_artifact_sha256,
        "mapping_public_key_ed25519": mapping_public_key,
    }


def verify_result_identity_anchor(
    sidecar: Mapping,
    analysis_artifact: Mapping | None,
    anchor: object,
) -> None:
    """Require the sidecar and analyzer chain to match the trusted run summary."""
    if not isinstance(anchor, Mapping):
        raise ResultIdentityError("run summary has no result identity anchor")
    _exact_keys(
        anchor,
        {
            "schema", "run_instance", "sidecar_content_sha256",
            "analysis_artifact_sha256", "mapping_public_key_ed25519",
        },
        "result identity anchor",
    )
    if anchor.get("schema") != ANCHOR_SCHEMA:
        raise ResultIdentityError("unsupported result identity anchor schema")
    expected = build_result_identity_anchor(sidecar, analysis_artifact)
    if dict(anchor) != expected:
        raise ResultIdentityError("result identity sidecar does not match its run summary anchor")


def validate_result_identity_sidecar(sidecar: object) -> None:
    """Validate the closed v1 sidecar, including ambiguity constraints."""
    if not isinstance(sidecar, Mapping):
        raise ResultIdentityError("result identity sidecar must be an object")
    allowed_root = {
        "schema", "privacy", "run_instance", "corpus_signature", "document_count", "modes",
    }
    if "cluster_assignments" in sidecar:
        allowed_root.add("cluster_assignments")
    _exact_keys(sidecar, allowed_root, "result identity sidecar")
    if sidecar["schema"] != SIDECAR_SCHEMA:
        raise ResultIdentityError(f"unsupported result identity schema: {sidecar['schema']!r}")
    privacy = sidecar["privacy"]
    if not isinstance(privacy, Mapping):
        raise ResultIdentityError("result identity privacy must be an object")
    _exact_keys(
        privacy,
        {"classification", "contains_source_paths", "contains_plain_path_hashes"},
        "result identity privacy",
    )
    if privacy != {
        "classification": PRIVACY_CLASSIFICATION,
        "contains_source_paths": False,
        "contains_plain_path_hashes": False,
    }:
        raise ResultIdentityError("result identity privacy contract is not local/path-free")
    run_instance = sidecar["run_instance"]
    if not isinstance(run_instance, str) or not re.fullmatch(r"[0-9a-f]{32}", run_instance):
        raise ResultIdentityError("run_instance must be 32 lowercase hex characters")
    corpus_signature = sidecar["corpus_signature"]
    if corpus_signature is not None and (
        not isinstance(corpus_signature, str) or not _HEX_SHA256.fullmatch(corpus_signature)
    ):
        raise ResultIdentityError("corpus_signature must be null or lowercase hex SHA-256")
    if type(sidecar["document_count"]) is not int or sidecar["document_count"] < 0:
        raise ResultIdentityError("document_count must be a non-negative integer")
    if not isinstance(sidecar["modes"], list):
        raise ResultIdentityError("result identity modes must be an array")

    mode_names: set[str] = set()
    opaque_to_legacy: dict[str, str] = {}
    for mode_record in sidecar["modes"]:
        if not isinstance(mode_record, Mapping):
            raise ResultIdentityError("result identity mode record must be an object")
        _exact_keys(mode_record, {"mode", "queries"}, "result identity mode")
        mode = mode_record["mode"]
        if not isinstance(mode, str) or not mode or mode in mode_names:
            raise ResultIdentityError("result identity mode names must be unique and non-empty")
        mode_names.add(mode)
        if not isinstance(mode_record["queries"], list):
            raise ResultIdentityError(f"result identity queries for {mode!r} must be an array")
        qids: set[str] = set()
        for query in mode_record["queries"]:
            if not isinstance(query, Mapping):
                raise ResultIdentityError("result identity query record must be an object")
            _exact_keys(query, {"qid", "hits"}, "result identity query")
            qid = query["qid"]
            if not isinstance(qid, str) or not qid or qid in qids:
                raise ResultIdentityError(f"query ids in mode {mode!r} must be unique and non-empty")
            qids.add(qid)
            if not isinstance(query["hits"], list):
                raise ResultIdentityError(f"result identity hits for {mode!r}/{qid!r} must be an array")
            query_opaque_ids: set[str] = set()
            for index, hit in enumerate(query["hits"], start=1):
                if not isinstance(hit, Mapping):
                    raise ResultIdentityError("result identity hit must be an object")
                _exact_keys(
                    hit,
                    {"delivered_rank", "legacy_doc_id", "opaque_id"},
                    "result identity hit",
                )
                if hit["delivered_rank"] != index or type(hit["delivered_rank"]) is not int:
                    raise ResultIdentityError(
                        f"delivered ranks for {mode!r}/{qid!r} must be contiguous from 1"
                    )
                legacy = hit["legacy_doc_id"]
                opaque = hit["opaque_id"]
                if not isinstance(legacy, str) or not legacy:
                    raise ResultIdentityError("legacy_doc_id must be a non-empty string")
                if not isinstance(opaque, str) or not _OPAQUE_ID.fullmatch(opaque):
                    raise ResultIdentityError("opaque_id is not a v1 run-local document id")
                if not opaque.startswith(f"rdoc-{run_instance}-"):
                    raise ResultIdentityError("opaque_id belongs to a different run_instance")
                if opaque in query_opaque_ids:
                    raise ResultIdentityError(
                        f"one opaque document appears multiple times in {mode!r}/{qid!r}"
                    )
                query_opaque_ids.add(opaque)
                previous = opaque_to_legacy.setdefault(opaque, legacy)
                if previous != legacy:
                    raise ResultIdentityError("one opaque_id maps to multiple legacy_doc_ids")

    if sidecar["document_count"] != len(opaque_to_legacy):
        raise ResultIdentityError(
            "document_count does not equal the number of unique opaque document ids"
        )
    if "cluster_assignments" in sidecar:
        assignments = _validate_cluster_assignments(sidecar["cluster_assignments"])
        unknown = set(assignments) - set(opaque_to_legacy)
        missing = set(opaque_to_legacy) - set(assignments)
        if unknown or missing:
            raise ResultIdentityError(
                "cluster assignments must cover sidecar documents exactly: "
                f"missing={sorted(missing)}, unknown={sorted(unknown)}"
            )


def _validate_cluster_assignments(value: object) -> dict[str, str]:
    if not isinstance(value, Mapping):
        raise ResultIdentityError("cluster_assignments must be an object")
    _exact_keys(
        value,
        {"schema", "cluster_source", "assignments", "mapping_signature_ed25519"},
        "cluster assignments",
    )
    if value["schema"] != CLUSTER_ASSIGNMENT_SCHEMA:
        raise ResultIdentityError(f"unsupported cluster assignment schema: {value['schema']!r}")
    signature = value["mapping_signature_ed25519"]
    if not isinstance(signature, str) or not _ED25519_SIGNATURE.fullmatch(signature):
        raise ResultIdentityError("mapping_signature_ed25519 must be a raw Ed25519 signature")
    source = value["cluster_source"]
    if not isinstance(source, Mapping):
        raise ResultIdentityError("cluster_source must be an object")
    _exact_keys(
        source,
        {
            "analysis_artifact_sha256", "analysis_artifact_filename",
            "semantics", "decision", "threshold",
        },
        "cluster source",
    )
    digest = source["analysis_artifact_sha256"]
    if not isinstance(digest, str) or not _HEX_SHA256.fullmatch(digest):
        raise ResultIdentityError("analysis_artifact_sha256 must be lowercase hex SHA-256")
    if source["analysis_artifact_filename"] != "duplicate_prevalence.v1.json":
        raise ResultIdentityError("cluster_source analysis artifact filename is unsupported")
    semantics = source["semantics"]
    if semantics != "normalized-content-exact":
        raise ResultIdentityError(
            "v1 result clusters support only revalidated normalized-content-exact semantics"
        )
    if source["decision"] != "CONFIRMED":
        raise ResultIdentityError("cluster_source decision must be CONFIRMED")
    threshold = source["threshold"]
    if threshold is not None:
        raise ResultIdentityError("exact cluster semantics require a null threshold")
    if not isinstance(value["assignments"], list):
        raise ResultIdentityError("cluster assignment records must be an array")
    out: dict[str, str] = {}
    cluster_by_fingerprint: dict[str, str] = {}
    fingerprint_by_cluster: dict[str, str] = {}
    for record in value["assignments"]:
        if not isinstance(record, Mapping):
            raise ResultIdentityError("cluster assignment record must be an object")
        _exact_keys(
            record,
            {"opaque_id", "content_fingerprint_sha256", "cluster_id"},
            "cluster assignment record",
        )
        opaque = record["opaque_id"]
        fingerprint = record["content_fingerprint_sha256"]
        cluster = record["cluster_id"]
        if not isinstance(opaque, str) or not _OPAQUE_ID.fullmatch(opaque):
            raise ResultIdentityError("cluster assignment opaque_id is invalid")
        if not isinstance(fingerprint, str) or not _HEX_SHA256.fullmatch(fingerprint):
            raise ResultIdentityError("content_fingerprint_sha256 must be lowercase hex SHA-256")
        if not isinstance(cluster, str) or not _CLUSTER_ID.fullmatch(cluster):
            raise ResultIdentityError("cluster_id must be an opaque rcluster-* id")
        if opaque in out:
            raise ResultIdentityError(f"ambiguous duplicate cluster assignment for {opaque!r}")
        previous_cluster = cluster_by_fingerprint.setdefault(fingerprint, cluster)
        if previous_cluster != cluster:
            raise ResultIdentityError("one content fingerprint maps to multiple cluster ids")
        previous_fingerprint = fingerprint_by_cluster.setdefault(cluster, fingerprint)
        if previous_fingerprint != fingerprint:
            raise ResultIdentityError("one cluster id maps to multiple exact content fingerprints")
        out[opaque] = cluster
    return out


def reconcile_delivered_hits(
    sidecar: Mapping,
    mode: str,
    per_query_entries: Sequence[Mapping],
) -> dict[str, list[str]]:
    """Reconcile delivered ``predictedDocIds`` to exactly one opaque id each."""
    validate_result_identity_sidecar(sidecar)
    mode_matches = [record for record in sidecar["modes"] if record["mode"] == mode]
    if len(mode_matches) != 1:
        raise ResultIdentityError(f"result identity sidecar has no unique mode {mode!r}")
    side_queries = {record["qid"]: record["hits"] for record in mode_matches[0]["queries"]}
    entry_queries: dict[str, list[str]] = {}
    for entry in per_query_entries:
        if not isinstance(entry, Mapping):
            raise ResultIdentityError("per-query result entry must be an object")
        qid = entry.get("qid")
        predicted = entry.get("predictedDocIds")
        if not isinstance(qid, str) or not qid or qid in entry_queries:
            raise ResultIdentityError("per-query qids must be unique and non-empty")
        if not isinstance(predicted, list) or any(not isinstance(v, str) or not v for v in predicted):
            raise ResultIdentityError(f"predictedDocIds for {qid!r} must be non-empty strings")
        entry_queries[qid] = predicted
    if set(side_queries) != set(entry_queries):
        raise ResultIdentityError(
            f"sidecar/per-query qids do not reconcile for mode {mode!r}: "
            f"sidecar={sorted(side_queries)}, per_query={sorted(entry_queries)}"
        )
    out: dict[str, list[str]] = {}
    for qid in sorted(entry_queries):
        hits = side_queries[qid]
        predicted = entry_queries[qid]
        if len(hits) != len(predicted):
            raise ResultIdentityError(
                f"sidecar/per-query hit counts do not reconcile for {mode!r}/{qid!r}"
            )
        opaque_ids: list[str] = []
        for index, (hit, legacy) in enumerate(zip(hits, predicted, strict=True), start=1):
            if hit["delivered_rank"] != index or hit["legacy_doc_id"] != legacy:
                raise ResultIdentityError(
                    f"sidecar entry does not uniquely reconcile delivered rank {index} "
                    f"for {mode!r}/{qid!r}"
                )
            opaque_ids.append(hit["opaque_id"])
        out[qid] = opaque_ids
    return out


def cluster_assignments_by_opaque_id(sidecar: Mapping) -> dict[str, str] | None:
    """Return validated cluster assignments, or ``None`` when not yet attached."""
    validate_result_identity_sidecar(sidecar)
    if "cluster_assignments" not in sidecar:
        return None
    return _validate_cluster_assignments(sidecar["cluster_assignments"])
