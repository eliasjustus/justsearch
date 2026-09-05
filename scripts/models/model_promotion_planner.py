#!/usr/bin/env python3
"""Produce a deterministic, write-free promotion plan for one model package.

The planner deliberately stops before building, uploading, editing the registry, or
running a model.  It turns a staged candidate plus recorded evidence into a reviewable
set of blockers.  Exit status 0 means the candidate is ready or an explicit no-op;
exit status 1 means the plan contains blockers; exit status 2 is invalid input.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Any
from urllib.parse import urlparse


SCHEMA_VERSION = 1
INDEX_AFFECTING_PACKAGES = frozenset({"embedding", "splade", "ner"})


@dataclass(frozen=True)
class AdapterPolicy:
    package_id: str
    artifact_format: str
    builder: str | None
    preparation: str
    required_variants: tuple[tuple[str, str], ...]
    required_supporting_files: tuple[str, ...]
    required_evidence: tuple[str, ...]
    index_affecting: bool


COMMON_PROJECTIONS = ("model-inventory", "notices")

ADAPTERS: dict[str, AdapterPolicy] = {
    "embedding": AdapterPolicy(
        "embedding", "onnx", None,
        "No compatible checked-in builder: build-embedding.py targets EmbeddingGemma Q4/INT8, not the registry's GTE FP32/FP16 package.",
        (("CPU", "FP32"), ("CUDA", "FP16")),
        ("tokenizer.json", "pooling_config.json", "model_manifest.json"),
        ("cpu-production", "gpu-production", "live-behavior", "quality-search", "migration"),
        True,
    ),
    "splade": AdapterPolicy(
        "splade", "onnx", "scripts/models/build-splade.py",
        "Use the role builder, then review its build.json and staged closure.",
        (("CPU", "FP32"), ("CUDA", "FP16")),
        ("tokenizer.json", "vocab.txt", "idf.json", "config.json"),
        ("cpu-production", "gpu-production", "live-behavior", "quality-search", "migration"),
        True,
    ),
    "ner": AdapterPolicy(
        "ner", "onnx", "scripts/models/build-ner.py",
        "Use the role builder, then review its build.json and staged closure.",
        (("CPU", "INT8"), ("CUDA", "FP16")),
        ("tokenizer.json", "config.json"),
        ("cpu-production", "gpu-production", "live-behavior", "quality-ner", "migration"),
        True,
    ),
    "reranker": AdapterPolicy(
        "reranker", "onnx", None,
        "No compatible checked-in builder: build-crossencoder.py emits one INT8 CPU variant, not the registry's FP32/FP16 package.",
        (("CPU", "FP32"), ("CUDA", "FP16")),
        ("tokenizer.json", "config.json"),
        ("cpu-production", "gpu-production", "live-behavior", "quality-reranker"),
        False,
    ),
    "citation-scorer": AdapterPolicy(
        "citation-scorer", "onnx", "scripts/models/build-crossencoder.py",
        "Use the CPU-only cross-encoder builder, then review its build.json and staged closure.",
        (("CPU", "INT8"),),
        ("tokenizer.json", "config.json"),
        ("cpu-production", "live-behavior", "quality-citation"),
        False,
    ),
    "chat": AdapterPolicy(
        "chat", "gguf", None,
        "Stage externally produced GGUF bytes with per-file immutable source and quantization provenance.",
        (("LLAMA_SERVER", "GGUF"),), ("mmproj-F16.gguf",),
        ("cpu-production", "gpu-production", "live-behavior", "quality-chat"),
        False,
    ),
    "chat-compact": AdapterPolicy(
        "chat-compact", "gguf", None,
        "Stage externally produced GGUF bytes with per-file immutable source and quantization provenance.",
        (("LLAMA_SERVER", "GGUF"),), ("mmproj-F16.gguf",),
        ("cpu-production", "gpu-production", "live-behavior", "quality-chat"),
        False,
    ),
}

EXCLUDED_PACKAGES = frozenset({"cuda-runtime"})

if frozenset(key for key, value in ADAPTERS.items() if value.index_affecting) != INDEX_AFFECTING_PACKAGES:
    raise RuntimeError("model-promotion index classification drifted from embedding/SPLADE/NER")


class InvalidInput(ValueError):
    pass


def _canonical(value: Any) -> Any:
    if isinstance(value, dict):
        return {key: _canonical(value[key]) for key in sorted(value)}
    if isinstance(value, list):
        return [_canonical(item) for item in value]
    return value


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest().upper()


def _artifact_records(package: dict[str, Any]) -> list[tuple[str, dict[str, Any]]]:
    records: list[tuple[str, dict[str, Any]]] = []
    for kind, key in (("variant", "variants"), ("supporting", "supportingFiles")):
        for record in package.get(key, []):
            records.append((kind, record))
    return records


def _is_immutable_url(url: str) -> bool:
    parsed = urlparse(url)
    if parsed.scheme != "https" or not parsed.netloc:
        return False
    segments = [segment for segment in parsed.path.split("/") if segment]
    if "latest" in (segment.lower() for segment in segments):
        return False
    if "resolve" in segments:
        index = segments.index("resolve")
        return index + 1 < len(segments) and segments[index + 1].lower() not in {"main", "master", "head"}
    if "releases" in segments and "download" in segments:
        index = segments.index("download")
        return index + 1 < len(segments)
    return False


def _require_string(value: Any, path: str) -> str:
    if not isinstance(value, str) or not value.strip():
        raise InvalidInput(f"{path} must be a non-empty string")
    return value


def _is_hex(value: Any, length: int) -> bool:
    return isinstance(value, str) and re.fullmatch(rf"[A-Fa-f0-9]{{{length}}}", value) is not None


def _require_uri(value: Any, path: str) -> str:
    text = _require_string(value, path)
    parsed = urlparse(text)
    if not parsed.scheme or any(character.isspace() for character in text):
        raise InvalidInput(f"{path} must be an absolute URI")
    if parsed.scheme in {"http", "https"} and not parsed.netloc:
        raise InvalidInput(f"{path} must be an absolute URI")
    return text


def _require_object(value: Any, path: str, *, nonempty: bool = False) -> dict[str, Any]:
    if not isinstance(value, dict) or (nonempty and not value):
        qualifier = "a non-empty object" if nonempty else "an object"
        raise InvalidInput(f"{path} must be {qualifier}")
    return value


def _require_array(value: Any, path: str) -> list[Any]:
    if not isinstance(value, list):
        raise InvalidInput(f"{path} must be an array")
    return value


def _require_keys(value: dict[str, Any], required: set[str], path: str) -> None:
    missing = sorted(required - set(value))
    if missing:
        raise InvalidInput(f"{path} is missing required properties: {', '.join(missing)}")


def _validate_artifact_shape(artifact: Any, path: str, *, variant: bool) -> None:
    value = _require_object(artifact, path)
    required = {"filename", "sha256", "sizeBytes", "downloadUrl"}
    if variant:
        required |= {"precision", "targetEP"}
    _require_keys(value, required, path)
    _require_string(value.get("filename"), f"{path}.filename")
    if not _is_hex(value.get("sha256"), 64):
        raise InvalidInput(f"{path}.sha256 must be 64 hexadecimal characters")
    size = value.get("sizeBytes")
    if isinstance(size, bool) or not isinstance(size, int) or size < 0:
        raise InvalidInput(f"{path}.sizeBytes must be a non-negative integer")
    _require_uri(value.get("downloadUrl"), f"{path}.downloadUrl")
    if variant:
        _require_string(value.get("precision"), f"{path}.precision")
        _require_string(value.get("targetEP"), f"{path}.targetEP")


def _validate_document_shape(candidate: dict[str, Any], package_id: str) -> dict[str, Any]:
    allowed = {
        "schemaVersion", "packageId", "stagedRoot", "proposedPackage",
        "provenance", "remoteVerification", "evidence", "projections",
    }
    unexpected = sorted(set(candidate) - allowed)
    if unexpected:
        raise InvalidInput(f"candidate contains unknown properties: {', '.join(unexpected)}")
    _require_keys(candidate, allowed, "candidate")
    if candidate.get("schemaVersion") != SCHEMA_VERSION:
        raise InvalidInput(f"candidate.schemaVersion must be {SCHEMA_VERSION}")
    if candidate.get("packageId") != package_id:
        raise InvalidInput("candidate.packageId must match --package")
    proposed = candidate.get("proposedPackage")
    if not isinstance(proposed, dict) or proposed.get("id") != package_id:
        raise InvalidInput("candidate.proposedPackage.id must match --package")
    _require_keys(proposed, {"id", "termsUrl", "license", "variants", "supportingFiles"}, "candidate.proposedPackage")
    _require_uri(proposed.get("termsUrl"), "candidate.proposedPackage.termsUrl")
    _require_string(proposed.get("license"), "candidate.proposedPackage.license")
    _require_string(candidate.get("stagedRoot"), "candidate.stagedRoot")
    variants = _require_array(proposed.get("variants"), "candidate.proposedPackage.variants")
    supporting = _require_array(proposed.get("supportingFiles"), "candidate.proposedPackage.supportingFiles")
    for index, artifact in enumerate(variants):
        _validate_artifact_shape(artifact, f"candidate.proposedPackage.variants[{index}]", variant=True)
    for index, artifact in enumerate(supporting):
        _validate_artifact_shape(artifact, f"candidate.proposedPackage.supportingFiles[{index}]", variant=False)

    provenance = _require_object(candidate.get("provenance"), "candidate.provenance")
    kind = provenance.get("kind")
    if kind == "onnx-build":
        _require_keys(provenance, {"kind", "source", "buildCommand", "toolVersions", "outputs"}, "candidate.provenance")
        source = _require_object(provenance.get("source"), "candidate.provenance.source")
        _require_keys(source, {"repository", "revision"}, "candidate.provenance.source")
        _require_string(source.get("repository"), "candidate.provenance.source.repository")
        if not _is_hex(source.get("revision"), 40):
            raise InvalidInput("candidate.provenance.source.revision must be 40 hexadecimal characters")
        _require_string(provenance.get("buildCommand"), "candidate.provenance.buildCommand")
        _require_object(provenance.get("toolVersions"), "candidate.provenance.toolVersions", nonempty=True)
        _require_object(provenance.get("outputs"), "candidate.provenance.outputs", nonempty=True)
    elif kind == "gguf-source":
        _require_keys(provenance, {"kind", "sources", "quantization", "toolVersions"}, "candidate.provenance")
        _require_object(provenance.get("sources"), "candidate.provenance.sources", nonempty=True)
        quantization = _require_object(provenance.get("quantization"), "candidate.provenance.quantization")
        _require_keys(quantization, {"tool", "format"}, "candidate.provenance.quantization")
        _require_string(quantization.get("tool"), "candidate.provenance.quantization.tool")
        _require_string(quantization.get("format"), "candidate.provenance.quantization.format")
        _require_object(provenance.get("toolVersions"), "candidate.provenance.toolVersions", nonempty=True)
    else:
        raise InvalidInput("candidate.provenance.kind must be onnx-build or gguf-source")

    remote = _require_object(candidate.get("remoteVerification"), "candidate.remoteVerification")
    for name, proof_value in remote.items():
        proof = _require_object(proof_value, f"candidate.remoteVerification.{name}")
        _require_keys(proof, {"status", "url", "sha256", "sizeBytes"}, f"candidate.remoteVerification.{name}")
        if proof.get("status") != "pass":
            raise InvalidInput(f"candidate.remoteVerification.{name}.status must be pass")
        _require_uri(proof.get("url"), f"candidate.remoteVerification.{name}.url")
        if not _is_hex(proof.get("sha256"), 64):
            raise InvalidInput(f"candidate.remoteVerification.{name}.sha256 must be 64 hexadecimal characters")
        size = proof.get("sizeBytes")
        if isinstance(size, bool) or not isinstance(size, int) or size < 0:
            raise InvalidInput(f"candidate.remoteVerification.{name}.sizeBytes must be a non-negative integer")

    evidence = _require_object(candidate.get("evidence"), "candidate.evidence")
    for name, evidence_value in evidence.items():
        record = _require_object(evidence_value, f"candidate.evidence.{name}")
        _require_keys(record, {"status", "ref"}, f"candidate.evidence.{name}")
        if record.get("status") not in {"pass", "no-change"}:
            raise InvalidInput(f"candidate.evidence.{name}.status must be pass or no-change")
        _require_string(record.get("ref"), f"candidate.evidence.{name}.ref")

    projections = _require_object(candidate.get("projections"), "candidate.projections")
    for name, projection_value in projections.items():
        record = _require_object(projection_value, f"candidate.projections.{name}")
        _require_keys(record, {"status", "ref", "result", "diff"}, f"candidate.projections.{name}")
        if record.get("status") not in {"pass", "no-change"}:
            raise InvalidInput(f"candidate.projections.{name}.status must be pass or no-change")
        _require_string(record.get("ref"), f"candidate.projections.{name}.ref")
        _require_string(record.get("result"), f"candidate.projections.{name}.result")
        if not isinstance(record.get("diff"), str):
            raise InvalidInput(f"candidate.projections.{name}.diff must be a string")
    return proposed


def _find_current(registry: dict[str, Any], package_id: str) -> dict[str, Any]:
    packages = registry.get("packages")
    if not isinstance(packages, list):
        raise InvalidInput("registry.packages must be an array")
    matches = [package for package in packages if package.get("id") == package_id]
    if len(matches) != 1:
        raise InvalidInput(f"registry must contain exactly one package {package_id!r}")
    return matches[0]


def _requirement(requirements: list[dict[str, str]], req_id: str, category: str, ok: bool, message: str) -> None:
    requirements.append({
        "id": req_id,
        "category": category,
        "status": "pass" if ok else "block",
        "message": message,
    })


def _validate_policy_coverage(registry: dict[str, Any]) -> None:
    model_ids = {package.get("id") for package in registry.get("packages", [])} - EXCLUDED_PACKAGES
    if model_ids != set(ADAPTERS):
        missing = sorted(model_ids - set(ADAPTERS))
        stale = sorted(set(ADAPTERS) - model_ids)
        raise InvalidInput(f"adapter coverage mismatch: missing={missing}, stale={stale}")


def _validate_provenance(
    policy: AdapterPolicy,
    provenance: dict[str, Any],
    proposed: dict[str, Any],
    requirements: list[dict[str, str]],
) -> None:
    if policy.artifact_format == "onnx":
        required = {
            "kind": provenance.get("kind") == "onnx-build",
            "source.repository": bool(provenance.get("source", {}).get("repository")),
            "source.revision": _is_hex(provenance.get("source", {}).get("revision"), 40),
            "buildCommand": bool(provenance.get("buildCommand")),
            "toolVersions": bool(provenance.get("toolVersions")),
        }
        outputs = provenance.get("outputs") if isinstance(provenance.get("outputs"), dict) else {}
        for variant in proposed.get("variants", []):
            output = outputs.get(variant.get("filename"), {})
            required[f"outputs.{variant.get('filename')}.sha256"] = str(output.get("sha256", "")).upper() == str(variant.get("sha256", "")).upper()
            required[f"outputs.{variant.get('filename')}.transformations"] = isinstance(output.get("transformations"), list) and bool(output["transformations"])
    else:
        sources = provenance.get("sources") if isinstance(provenance.get("sources"), dict) else {}
        required = {"kind": provenance.get("kind") == "gguf-source"}
        for _, artifact in _artifact_records(proposed):
            source = sources.get(artifact.get("filename"), {})
            required[f"sources.{artifact.get('filename')}.repository"] = bool(source.get("repository"))
            required[f"sources.{artifact.get('filename')}.revision"] = _is_hex(source.get("revision"), 40)
            required[f"sources.{artifact.get('filename')}.sourceSha256"] = _is_hex(source.get("sourceSha256"), 64)
        required["quantization.tool"] = bool(provenance.get("quantization", {}).get("tool"))
        required["quantization.format"] = bool(provenance.get("quantization", {}).get("format"))
        required["toolVersions"] = bool(provenance.get("toolVersions"))
    missing = sorted(key for key, valid in required.items() if not valid)
    _requirement(
        requirements, "provenance-complete", "provenance", not missing,
        "complete immutable build provenance" if not missing else f"missing or inconsistent provenance: {', '.join(missing)}",
    )


def build_plan(registry: dict[str, Any], candidate: dict[str, Any], candidate_path: Path, package_id: str) -> dict[str, Any]:
    if package_id in EXCLUDED_PACKAGES:
        raise InvalidInput(f"{package_id} is a runtime package and is excluded from model promotion")
    if package_id not in ADAPTERS:
        raise InvalidInput(f"unsupported model package: {package_id}")
    _validate_policy_coverage(registry)
    policy = ADAPTERS[package_id]
    current = _find_current(registry, package_id)
    proposed = _validate_document_shape(candidate, package_id)
    requirements: list[dict[str, str]] = []

    variants = proposed.get("variants") if isinstance(proposed.get("variants"), list) else []
    actual_variant_shapes = {(str(v.get("targetEP", "")).upper(), str(v.get("precision", "")).upper()) for v in variants}
    missing_shapes = sorted(set(policy.required_variants) - actual_variant_shapes)
    _requirement(
        requirements, "variant-closure", "closure", not missing_shapes,
        "all role variants present" if not missing_shapes else f"missing variants: {missing_shapes}",
    )

    supporting = proposed.get("supportingFiles") if isinstance(proposed.get("supportingFiles"), list) else []
    supporting_names = {record.get("filename") for record in supporting}
    missing_support = sorted(set(policy.required_supporting_files) - supporting_names)
    _requirement(
        requirements, "supporting-file-closure", "closure", not missing_support,
        "all role supporting files present" if not missing_support else f"missing supporting files: {', '.join(missing_support)}",
    )

    staged_root = (candidate_path.parent / candidate["stagedRoot"]).resolve()
    candidate_root = candidate_path.parent.resolve()
    if candidate_root not in staged_root.parents and staged_root != candidate_root:
        raise InvalidInput("candidate.stagedRoot must remain within the candidate directory")

    current_by_name = {record.get("filename"): record for _, record in _artifact_records(current)}
    current_by_url = {record.get("downloadUrl"): record for _, record in _artifact_records(current)}
    staged_artifacts: list[dict[str, Any]] = []
    all_files_valid = True
    urls_valid = True
    reused_changed_urls: list[str] = []
    duplicate_names: set[str] = set()
    seen_names: set[str] = set()
    for kind, artifact in _artifact_records(proposed):
        filename = artifact.get("filename")
        if not isinstance(filename, str) or not filename or Path(filename).name != filename:
            raise InvalidInput("artifact filenames must be plain file names")
        if filename in seen_names:
            duplicate_names.add(filename)
        seen_names.add(filename)
        path = staged_root / filename
        resolved_path = path.resolve()
        if staged_root not in resolved_path.parents:
            raise InvalidInput(f"staged artifact escapes stagedRoot: {filename}")
        exists = path.is_file()
        observed_sha = _sha256(path) if exists else None
        observed_size = path.stat().st_size if exists else None
        declared_sha = str(artifact.get("sha256", "")).upper()
        declared_size = artifact.get("sizeBytes")
        file_valid = (
            exists
            and _is_hex(declared_sha, 64)
            and isinstance(declared_size, int)
            and declared_size >= 0
            and observed_sha == declared_sha
            and observed_size == declared_size
        )
        all_files_valid = all_files_valid and file_valid
        url = str(artifact.get("downloadUrl", ""))
        immutable = _is_immutable_url(url)
        urls_valid = urls_valid and immutable
        old = current_by_name.get(filename)
        changed = old is None or str(old.get("sha256", "")).upper() != declared_sha or old.get("sizeBytes") != declared_size
        old_at_url = current_by_url.get(url)
        if old_at_url and (
            str(old_at_url.get("sha256", "")).upper() != declared_sha
            or old_at_url.get("sizeBytes") != declared_size
        ):
            reused_changed_urls.append(filename)
        staged_artifacts.append(_canonical({
            "kind": kind,
            "filename": filename,
            "path": path.relative_to(candidate_root).as_posix() if path.is_relative_to(candidate_root) else str(path),
            "declaredSha256": declared_sha,
            "declaredSizeBytes": declared_size,
            "observedSha256": observed_sha,
            "observedSizeBytes": observed_size,
            "changed": changed,
            "downloadUrl": url,
            "immutableUrl": immutable,
        }))
    _requirement(requirements, "artifact-names-unique", "closure", not duplicate_names,
                 "artifact filenames are unique" if not duplicate_names else f"duplicate artifact filenames: {', '.join(sorted(duplicate_names))}")
    staged_entries = list(staged_root.iterdir()) if staged_root.is_dir() else []
    staged_names = {entry.name for entry in staged_entries if entry.is_file()}
    unexpected_staged = sorted(staged_names - seen_names)
    nested_staged = sorted(entry.name for entry in staged_entries if entry.is_dir())
    exact_staged_closure = not unexpected_staged and not nested_staged
    staged_closure_details = unexpected_staged + [f"{name}/" for name in nested_staged]
    _requirement(
        requirements, "staged-directory-closure", "closure", exact_staged_closure,
        "staged directory contains only declared artifacts" if exact_staged_closure else f"undeclared staged entries: {', '.join(staged_closure_details)}",
    )
    _requirement(requirements, "staged-bytes-match", "closure", all_files_valid,
                 "staged bytes match declared SHA-256 and size" if all_files_valid else "one or more staged files are missing or disagree with declared SHA-256/size")
    _requirement(requirements, "immutable-urls", "publication", urls_valid,
                 "all download URLs use immutable coordinates" if urls_valid else "one or more download URLs are mutable or unsupported")
    _requirement(requirements, "changed-bytes-new-url", "publication", not reused_changed_urls,
                 "changed bytes use new coordinates" if not reused_changed_urls else f"changed bytes reuse current URL: {', '.join(sorted(reused_changed_urls))}")

    _validate_provenance(policy, candidate["provenance"], proposed, requirements)

    proposed_canonical = _canonical(proposed)
    current_canonical = _canonical(current)
    content_changed = any(artifact["changed"] for artifact in staged_artifacts)
    explicit_noop = proposed_canonical == current_canonical and not content_changed

    remote = candidate.get("remoteVerification") if isinstance(candidate.get("remoteVerification"), dict) else {}
    remote_missing: list[str] = []
    for artifact in staged_artifacts:
        proof = remote.get(artifact["filename"], {})
        valid = (
            proof.get("status") == "pass"
            and proof.get("url") == artifact["downloadUrl"]
            and str(proof.get("sha256", "")).upper() == artifact["declaredSha256"]
            and proof.get("sizeBytes") == artifact["declaredSizeBytes"]
        )
        if not valid:
            remote_missing.append(artifact["filename"])
    _requirement(requirements, "remote-byte-verification", "publication", not remote_missing,
                 "remote bytes match proposed registry facts" if not remote_missing else f"missing or inconsistent remote verification: {', '.join(sorted(remote_missing))}")

    evidence = candidate["evidence"]
    for evidence_id in policy.required_evidence:
        record = evidence.get(evidence_id, {})
        valid = record.get("status") in {"pass", "no-change"} and bool(record.get("ref"))
        _requirement(requirements, f"evidence-{evidence_id}", "evidence", valid,
                     f"{evidence_id} evidence recorded" if valid else f"missing passing {evidence_id} evidence with a reviewable ref")

    license_approval = evidence.get("license-approval", {})
    license_approved = (
        license_approval.get("status") == "pass"
        and bool(license_approval.get("ref"))
        and license_approval.get("license") == proposed.get("license")
    )
    _requirement(
        requirements, "license-approval", "license", license_approved,
        "proposed license has explicit approval evidence" if license_approved
        else "missing passing license approval tied to the proposed package license",
    )

    projections = candidate["projections"]
    for projection_id in COMMON_PROJECTIONS:
        record = projections.get(projection_id, {})
        valid_status = record.get("status") in {"pass", "no-change"}
        valid_diff = (
            isinstance(record.get("diff"), str)
            and ((record.get("status") == "pass" and bool(record["diff"].strip()))
                 or (record.get("status") == "no-change" and record["diff"] == ""))
        )
        valid = valid_status and bool(record.get("ref")) and bool(record.get("result")) and valid_diff
        _requirement(
            requirements, f"projection-{projection_id}", "projection", valid,
            f"{projection_id} projection result and diff recorded" if valid
            else f"missing consistent {projection_id} projection result/diff with a reviewable ref",
        )

    if explicit_noop:
        unchanged_projections = all(
            projections.get(projection, {}).get("status") == "no-change"
            and projections.get(projection, {}).get("diff") == ""
            for projection in COMMON_PROJECTIONS
        )
        _requirement(
            requirements, "no-op-projections", "projection", unchanged_projections,
            "explicit no-op has no generated projection drift" if unchanged_projections else "explicit no-op requires no-change evidence for model-inventory and notices",
        )

    notice_fields_changed = sorted(
        field for field in ("label", "targetDir", "license", "termsUrl")
        if current.get(field) != proposed.get(field)
    )
    identity_changed = any(field in notice_fields_changed for field in ("label", "targetDir", "termsUrl"))
    license_changed = current.get("license") != proposed.get("license")
    notice_action = "regenerate" if notice_fields_changed else "no-op"
    notices = projections.get("notices", {})
    notice_consistent = notices.get("status") == ("pass" if notice_action == "regenerate" else "no-change")
    _requirement(requirements, "notice-projection-classification", "projection", notice_consistent,
                 f"notice projection correctly classified as {notice_action}" if notice_consistent else f"notice projection must be {'pass' if notice_action == 'regenerate' else 'no-change'}")

    blockers = [requirement["id"] for requirement in requirements if requirement["status"] == "block"]
    status = "blocked" if blockers else ("no-op" if explicit_noop else "ready")
    plan = {
        "schemaVersion": SCHEMA_VERSION,
        "packageId": package_id,
        "status": status,
        "indexMigration": "required" if policy.index_affecting and content_changed else "not-required",
        "adapter": {
            "format": policy.artifact_format,
            "builder": policy.builder,
            "preparation": policy.preparation,
            "requiredVariants": [{"targetEP": ep, "precision": precision} for ep, precision in policy.required_variants],
            "requiredSupportingFiles": list(policy.required_supporting_files),
            "requiredEvidence": list(policy.required_evidence),
            "requiredProjections": list(COMMON_PROJECTIONS),
            "licenseApprovalRequired": True,
            "indexAffecting": policy.index_affecting,
        },
        "changes": {
            "packageRecordChanged": proposed_canonical != current_canonical,
            "identityChanged": identity_changed,
            "licenseChanged": license_changed,
            "noticeFieldsChanged": notice_fields_changed,
            "noticeProjection": notice_action,
        },
        "currentPackage": current_canonical,
        "proposedPackage": proposed_canonical,
        "provenance": _canonical(candidate["provenance"]),
        "remoteVerification": _canonical(candidate["remoteVerification"]),
        "evidence": _canonical(candidate["evidence"]),
        "projections": _canonical(candidate["projections"]),
        "artifacts": sorted(staged_artifacts, key=lambda item: (item["kind"], item["filename"])),
        "requirements": requirements,
        "blockers": blockers,
    }
    return _canonical(plan)


def render_human(plan: dict[str, Any]) -> str:
    lines = [
        f"Model promotion plan: {plan['packageId']}",
        f"Status: {plan['status']}",
        f"Index migration: {plan['indexMigration']}",
        f"Notice projection: {plan['changes']['noticeProjection']}",
        "Provenance:",
        json.dumps(plan["provenance"], sort_keys=True, ensure_ascii=False),
        "Remote verification:",
    ]
    for filename in sorted(plan["remoteVerification"]):
        proof = plan["remoteVerification"][filename]
        lines.append(
            f"- {filename}: {proof['status']} {proof['url']} "
            f"sha256={proof['sha256']} size={proof['sizeBytes']}"
        )
    lines.append("Evidence:")
    for evidence_id in sorted(plan["evidence"]):
        record = plan["evidence"][evidence_id]
        suffix = f" license={record['license']}" if record.get("license") else ""
        lines.append(f"- {evidence_id}: {record['status']} ref={record['ref']}{suffix}")
    lines.append("Projections:")
    for projection_id in sorted(plan["projections"]):
        record = plan["projections"][projection_id]
        lines.append(
            f"- {projection_id}: {record['status']} result={record['result']} "
            f"ref={record['ref']} diff={json.dumps(record['diff'], ensure_ascii=False)}"
        )
    if plan["blockers"]:
        lines.append("Blockers:")
        by_id = {item["id"]: item for item in plan["requirements"]}
        lines.extend(f"- {blocker}: {by_id[blocker]['message']}" for blocker in plan["blockers"])
    else:
        lines.append("Blockers: none")
    return "\n".join(lines) + "\n"


def _load_json(path: Path, label: str) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise InvalidInput(f"cannot read {label} {path}: {exc}") from exc
    if not isinstance(value, dict):
        raise InvalidInput(f"{label} must contain a JSON object")
    return value


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--registry", type=Path, required=True)
    parser.add_argument("--package", required=True)
    parser.add_argument("--candidate", type=Path, required=True)
    parser.add_argument("--format", choices=("json", "human"), default="json")
    args = parser.parse_args(argv)
    try:
        registry = _load_json(args.registry, "registry")
        candidate = _load_json(args.candidate, "candidate")
        plan = build_plan(registry, candidate, args.candidate.resolve(), args.package)
    except InvalidInput as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 2
    if args.format == "json":
        print(json.dumps(plan, indent=2, sort_keys=True, ensure_ascii=False))
    else:
        print(render_human(plan), end="")
    return 1 if plan["status"] == "blocked" else 0


if __name__ == "__main__":
    raise SystemExit(main())
