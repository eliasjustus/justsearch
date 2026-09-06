"""Strict Enron eligible-body adapter for duplicate-prevalence analysis."""

from __future__ import annotations

import gzip
import hashlib
import json
import os
import random
import re
import shutil
import tarfile
import tempfile
import unicodedata
from collections import Counter
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Mapping

from . import duplicate_prevalence as prevalence
from .corpus_fetch import iter_enron_source_stages
from .raw_corpus_manifest import (
    RawCorpusManifestError,
    build_raw_manifest,
    validate_raw_manifest,
)


INPUT_SCHEMA_V1 = "jseval.duplicate-prevalence-input.v1"
INPUT_SCHEMA_V2 = "jseval.duplicate-prevalence-input.v2"
INPUT_SCHEMA = INPUT_SCHEMA_V1
SOURCE_KIND = "enron-eligible-body-proxy"
_TOP_LEVEL_FIELDS = frozenset({"schema", "source", "analysis"})
_SOURCE_FIELDS = frozenset({"kind", "raw_root", "tarball", "min_words"})
_SOURCE_FIELDS_V2 = frozenset({*_SOURCE_FIELDS, "eligible_sample"})
_ELIGIBLE_SAMPLE_FIELDS = frozenset({"method", "size", "seed"})
_ELIGIBLE_SAMPLE_METHOD = "algorithm-r-reservoir-without-replacement-v1"
_ANALYSIS_FIELDS = frozenset(
    {
        "shingle_width",
        "simhash_bits",
        "max_hamming",
        "jaccard_thresholds",
        "exhaustive_slice_size",
        "bootstrap_draws",
        "seed",
        "max_candidate_pairs",
    }
)
_WINDOWS_DRIVE_RE = re.compile(r"^[A-Za-z]:")
_ADMISSION_POLICY_KEYS = (
    "JUSTSEARCH_INGESTION_SKIP_PATTERNS",
    "JUSTSEARCH_INGESTION_SKIP_EXTENSIONS",
    "JUSTSEARCH_INGESTION_SKIP_DIRECTORY_NAMES",
)
_STAGE_KEYS = ("raw_member", "parsed_body", "eligible_body", "retained_body")


class EnronDuplicatePrevalenceError(ValueError):
    """The Enron adapter input or source archive is invalid."""


@dataclass(frozen=True)
class EnronSourceSpec:
    raw_root: Path
    tarball: str
    min_words: int
    eligible_sample: "EnronEligibleSampleSpec | None" = None


@dataclass(frozen=True)
class EnronEligibleSampleSpec:
    method: str
    size: int
    seed: int


@dataclass(frozen=True)
class EnronAnalysisRequest:
    source: EnronSourceSpec
    config: prevalence.AnalysisConfig


def _object_without_duplicate_keys(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise EnronDuplicatePrevalenceError(f"duplicate JSON object key: {key}")
        result[key] = value
    return result


def _reject_json_constant(value: str) -> None:
    raise EnronDuplicatePrevalenceError(f"non-finite JSON number is not allowed: {value}")


def _exact_fields(value: object, expected: frozenset[str], label: str) -> Mapping[str, Any]:
    if not isinstance(value, Mapping) or set(value) != expected:
        raise EnronDuplicatePrevalenceError(
            f"{label} must contain exactly {sorted(expected)}"
        )
    return value


def _canonical_relative_path(value: object, label: str) -> str:
    if not isinstance(value, str) or not value or "\x00" in value:
        raise EnronDuplicatePrevalenceError(f"{label} must be a non-empty relative path")
    if "\\" in value or value.startswith(("/", "//")) or _WINDOWS_DRIVE_RE.match(value):
        raise EnronDuplicatePrevalenceError(f"{label} must be a canonical POSIX relative path")
    parts = value.split("/")
    if any(part in {"", ".", ".."} for part in parts):
        raise EnronDuplicatePrevalenceError(f"{label} contains an empty, dot, or dot-dot component")
    if unicodedata.normalize("NFC", value) != value:
        raise EnronDuplicatePrevalenceError(f"{label} must use exact NFC")
    try:
        value.encode("utf-8")
    except UnicodeEncodeError as exc:
        raise EnronDuplicatePrevalenceError(f"{label} must be valid UTF-8") from exc
    return value


def load_input_payload(path: Path | str) -> Mapping[str, Any]:
    """Load one duplicate-prevalence request without losing duplicate-key checks."""
    try:
        raw = Path(path).read_text(encoding="utf-8")
        payload = json.loads(
            raw,
            object_pairs_hook=_object_without_duplicate_keys,
            parse_constant=_reject_json_constant,
        )
    except EnronDuplicatePrevalenceError:
        raise
    except (OSError, UnicodeError, json.JSONDecodeError) as exc:
        raise EnronDuplicatePrevalenceError(f"cannot read input spec: {exc}") from exc

    return _exact_fields(payload, _TOP_LEVEL_FIELDS, "input spec")


def parse_analysis_config(value: object) -> prevalence.AnalysisConfig:
    """Parse the shared, closed analysis block used by every source adapter."""

    analysis = _exact_fields(value, _ANALYSIS_FIELDS, "analysis")
    thresholds = analysis["jaccard_thresholds"]
    if not isinstance(thresholds, list):
        raise EnronDuplicatePrevalenceError("analysis.jaccard_thresholds must be a JSON array")
    try:
        config = prevalence.AnalysisConfig(
            shingle_width=analysis["shingle_width"],
            simhash_bits=analysis["simhash_bits"],
            max_hamming=analysis["max_hamming"],
            jaccard_thresholds=tuple(thresholds),
            exhaustive_slice_size=analysis["exhaustive_slice_size"],
            bootstrap_draws=analysis["bootstrap_draws"],
            seed=analysis["seed"],
            max_candidate_pairs=analysis["max_candidate_pairs"],
        )
        prevalence.validate_config(config)
    except (TypeError, ValueError) as exc:
        raise EnronDuplicatePrevalenceError(f"invalid analysis configuration: {exc}") from exc
    return config


def input_source_kind(path: Path | str) -> str:
    """Return the strictly loaded source discriminator for command dispatch."""

    top = load_input_payload(path)
    source = top["source"]
    if not isinstance(source, Mapping) or not isinstance(source.get("kind"), str):
        raise EnronDuplicatePrevalenceError("source.kind must be a string")
    return source["kind"]


def load_input_spec(path: Path | str) -> EnronAnalysisRequest:
    """Load and strictly validate one versioned Enron adapter request."""

    top = load_input_payload(path)

    schema = top["schema"]
    if schema not in {INPUT_SCHEMA_V1, INPUT_SCHEMA_V2}:
        raise EnronDuplicatePrevalenceError(
            f"input spec schema must be {INPUT_SCHEMA_V1!r} or {INPUT_SCHEMA_V2!r}"
        )
    if not isinstance(top["source"], Mapping):
        raise EnronDuplicatePrevalenceError("source must be an object")
    source_kind = top["source"].get("kind")
    if source_kind == "production-extracted":
        raise EnronDuplicatePrevalenceError(
            "production-extracted input requires the P6 extracted-content snapshot adapter"
        )
    if source_kind != SOURCE_KIND:
        raise EnronDuplicatePrevalenceError(
            f"unsupported source.kind {source_kind!r}; only {SOURCE_KIND!r} is available"
        )
    source = _exact_fields(
        top["source"],
        _SOURCE_FIELDS_V2 if schema == INPUT_SCHEMA_V2 else _SOURCE_FIELDS,
        "source",
    )
    raw_root = source["raw_root"]
    if not isinstance(raw_root, str) or not raw_root or "\x00" in raw_root:
        raise EnronDuplicatePrevalenceError("source.raw_root must be a non-empty local path")
    try:
        raw_root.encode("utf-8")
    except UnicodeEncodeError as exc:
        raise EnronDuplicatePrevalenceError("source.raw_root must be valid UTF-8") from exc
    tarball = _canonical_relative_path(source["tarball"], "source.tarball")
    min_words = source["min_words"]
    if isinstance(min_words, bool) or not isinstance(min_words, int) or min_words < 0:
        raise EnronDuplicatePrevalenceError("source.min_words must be a non-negative integer")

    eligible_sample = None
    if schema == INPUT_SCHEMA_V2:
        sample = _exact_fields(
            source["eligible_sample"], _ELIGIBLE_SAMPLE_FIELDS, "source.eligible_sample"
        )
        method = sample["method"]
        size = sample["size"]
        seed = sample["seed"]
        if method != _ELIGIBLE_SAMPLE_METHOD:
            raise EnronDuplicatePrevalenceError(
                f"source.eligible_sample.method must be {_ELIGIBLE_SAMPLE_METHOD!r}"
            )
        if isinstance(size, bool) or not isinstance(size, int) or not 1 <= size <= 5_000:
            raise EnronDuplicatePrevalenceError(
                "source.eligible_sample.size must be an integer between 1 and 5000"
            )
        if isinstance(seed, bool) or not isinstance(seed, int) or seed < 0:
            raise EnronDuplicatePrevalenceError(
                "source.eligible_sample.seed must be a non-negative integer"
            )
        eligible_sample = EnronEligibleSampleSpec(method=method, size=size, seed=seed)

    config = parse_analysis_config(top["analysis"])
    return EnronAnalysisRequest(
        source=EnronSourceSpec(Path(raw_root), tarball, min_words, eligible_sample),
        config=config,
    )


def _corpus_identity(raw_identity: Any) -> dict[str, Any]:
    identity = raw_identity.to_corpus_identity()
    identity["manifest_pointer"] = None
    identity["admission_policy"] = {key: "default" for key in _ADMISSION_POLICY_KEYS}
    return identity


def _safe_tar_members(archive: tarfile.TarFile) -> list[tarfile.TarInfo]:
    regular: list[tarfile.TarInfo] = []
    seen: set[str] = set()
    for member in archive.getmembers():
        if not member.isfile():
            continue
        name = _canonical_relative_path(member.name, "tar member name")
        if name in seen:
            raise EnronDuplicatePrevalenceError("archive contains duplicate regular member names")
        seen.add(name)
        regular.append(member)
    return sorted(regular, key=lambda member: member.name)


def _opaque_id(corpus_signature: str, member_name: str) -> str:
    material = f"{corpus_signature}\0{member_name}".encode("utf-8")
    return hashlib.sha256(material).hexdigest()


def _analyze_request_with_observations(
    request: EnronAnalysisRequest,
) -> tuple[dict[str, Any], tuple[prevalence.DocumentObservation, ...]]:
    try:
        raw_identity = build_raw_manifest(request.source.raw_root)
    except RawCorpusManifestError as exc:
        raise EnronDuplicatePrevalenceError(f"raw corpus validation failed: {exc}") from exc
    files = raw_identity.manifest["files"]
    if len(files) != 1 or files[0]["path"] != request.source.tarball:
        raise EnronDuplicatePrevalenceError(
            "raw corpus manifest must contain exactly the declared Enron archive"
        )

    root = request.source.raw_root.resolve(strict=True)
    tarball_path = root.joinpath(*request.source.tarball.split("/"))
    stage_counts = {stage: 0 for stage in _STAGE_KEYS}
    observations: list[prevalence.DocumentObservation] = []
    sample = request.source.eligible_sample
    sample_rng = random.Random(sample.seed) if sample is not None else None
    eligible_seen = 0
    raw_body_digest_counts: Counter[str] = Counter()
    normalized_content_digest_counts: Counter[str] = Counter()
    try:
        with tempfile.TemporaryDirectory() as scratch:
            plain_tar = Path(scratch) / "enron-source.tar"
            with gzip.open(tarball_path, "rb") as compressed, plain_tar.open("wb") as output:
                shutil.copyfileobj(compressed, output, length=1024 * 1024)
            with tarfile.open(plain_tar, mode="r:") as archive:
                members = _safe_tar_members(archive)

                def messages():
                    for member in members:
                        extracted = archive.extractfile(member)
                        yield member.name, extracted.read() if extracted is not None else None

                for event in iter_enron_source_stages(messages(), min_words=request.source.min_words):
                    stage_counts[event.stage] += 1
                    if event.stage != "eligible_body":
                        continue
                    if event.body is None or event.body_sha256 is None:
                        raise EnronDuplicatePrevalenceError(
                            "eligible-body stage emitted incomplete content"
                        )
                    observation = prevalence.DocumentObservation(
                        opaque_id=_opaque_id(raw_identity.digest, event.member_name),
                        raw_sha256=event.body_sha256,
                        extracted_text=event.body,
                        format_id="enron-email-body",
                        source_kind=prevalence.SOURCE_BODY_PROXY,
                        extraction_status="success",
                    )
                    if sample is None:
                        observations.append(observation)
                        continue

                    # Census and sampling occur at eligible_body, before the subsequent
                    # first-SHA retention stage can remove an exact-body duplicate.
                    eligible_seen += 1
                    raw_body_digest_counts[event.body_sha256] += 1
                    normalized = prevalence.normalize_content(event.body)
                    if normalized:
                        normalized_digest = hashlib.sha256(normalized.encode("utf-8")).hexdigest()
                        normalized_content_digest_counts[normalized_digest] += 1
                    if len(observations) < sample.size:
                        observations.append(observation)
                    else:
                        assert sample_rng is not None
                        replacement = sample_rng.randrange(eligible_seen)
                        if replacement < sample.size:
                            observations[replacement] = observation
    except EnronDuplicatePrevalenceError:
        raise
    except (OSError, EOFError, gzip.BadGzipFile, tarfile.TarError) as exc:
        raise EnronDuplicatePrevalenceError(f"cannot read Enron source archive: {exc}") from exc

    corpus_identity = _corpus_identity(raw_identity)
    if sample is None:
        projection_identity = prevalence.build_enron_source_projection_identity(
            observations,
            corpus_signature=raw_identity.digest,
            min_words=request.source.min_words,
            stage_counts=stage_counts,
        )
    else:
        if eligible_seen != stage_counts["eligible_body"]:
            raise EnronDuplicatePrevalenceError(
                "eligible-body census did not cover the complete sampling frame"
            )
        projection_identity = prevalence.build_enron_sampled_source_projection_identity(
            observations,
            corpus_signature=raw_identity.digest,
            min_words=request.source.min_words,
            stage_counts=stage_counts,
            sample_size=sample.size,
            sample_seed=sample.seed,
            raw_body_digest_counts=raw_body_digest_counts,
            normalized_content_digest_counts=normalized_content_digest_counts,
        )
    artifact = prevalence.analyze(
        observations,
        corpus_identity=corpus_identity,
        source_projection_identity=projection_identity,
        config=request.config,
    )
    try:
        validate_raw_manifest(request.source.raw_root, raw_identity.manifest)
    except RawCorpusManifestError as exc:
        raise EnronDuplicatePrevalenceError(
            f"raw corpus changed during duplicate-prevalence analysis: {exc}"
        ) from exc
    return artifact, tuple(observations)


def _analyze_request(request: EnronAnalysisRequest) -> dict[str, Any]:
    artifact, _observations = _analyze_request_with_observations(request)
    return artifact


def analyze_input_spec(path: Path | str) -> dict[str, Any]:
    """Load an input spec and analyze its strict Enron source projection."""

    return _analyze_request(load_input_spec(path))


def analyze_input_spec_with_observations(
    path: Path | str,
) -> tuple[dict[str, Any], tuple[prevalence.DocumentObservation, ...]]:
    """Analyze once and retain the private observations for a local review packet."""

    return analyze_request_with_observations(load_input_spec(path))


def analyze_request_with_observations(
    request: EnronAnalysisRequest,
) -> tuple[dict[str, Any], tuple[prevalence.DocumentObservation, ...]]:
    """Analyze one parsed request while retaining private review observations."""

    if not isinstance(request, EnronAnalysisRequest):
        raise EnronDuplicatePrevalenceError("request must be an EnronAnalysisRequest")
    return _analyze_request_with_observations(request)


def write_artifact_atomic(path: Path | str, artifact: Mapping[str, Any]) -> Path:
    """Write one UTF-8 artifact through a same-directory atomic replacement."""

    destination = Path(path)
    destination.parent.mkdir(parents=True, exist_ok=True)
    temporary: Path | None = None
    try:
        with tempfile.NamedTemporaryFile(
            mode="w",
            encoding="utf-8",
            newline="\n",
            dir=destination.parent,
            prefix=f".{destination.name}.",
            suffix=".tmp",
            delete=False,
        ) as stream:
            temporary = Path(stream.name)
            json.dump(artifact, stream, sort_keys=True, indent=2, ensure_ascii=False, allow_nan=False)
            stream.write("\n")
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, destination)
        temporary = None
    finally:
        if temporary is not None:
            try:
                temporary.unlink()
            except FileNotFoundError:
                pass
    return destination


__all__ = [
    "INPUT_SCHEMA",
    "INPUT_SCHEMA_V1",
    "INPUT_SCHEMA_V2",
    "SOURCE_KIND",
    "EnronAnalysisRequest",
    "EnronDuplicatePrevalenceError",
    "EnronEligibleSampleSpec",
    "EnronSourceSpec",
    "analyze_input_spec",
    "analyze_input_spec_with_observations",
    "analyze_request_with_observations",
    "input_source_kind",
    "load_input_payload",
    "load_input_spec",
    "parse_analysis_config",
    "write_artifact_atomic",
]
