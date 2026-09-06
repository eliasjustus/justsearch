"""Deterministic descriptive duplicate-prevalence analysis.

The analyzer deliberately separates three notions of duplication:

* byte exact: identical caller-supplied SHA-256 digests;
* content exact: identical normalized extracted text;
* near duplicate: a SimHash candidate followed by full token-shingle Jaccard.

SimHash banding only guarantees candidates within the configured Hamming
radius.  It makes no recall guarantee for a Jaccard threshold, so candidate
recall is measured independently on a deterministic exhaustive slice.

The returned artifact is aggregate-only: it contains neither document IDs,
paths, nor extracted text.  Production-extracted input is accepted only with
a complete, reconciled extraction identity.
"""

from __future__ import annotations

import hashlib
import json
import math
import random
import re
import unicodedata
from collections import Counter, defaultdict
from dataclasses import dataclass
from typing import Any, Iterable, Mapping, Sequence

from .raw_corpus_manifest import RAW_CORPUS_MANIFEST_SCHEMA


SCHEMA = "jseval.duplicate-prevalence.v1"
ALGORITHM = "duplicate-prevalence.v1"
EXTRACTION_SNAPSHOT_SCHEMA = "jseval.extracted-content-snapshot.v1"
EXTRACTION_SNAPSHOT_SCHEMA_V2 = "jseval.extracted-content-snapshot.v2"
OBSERVATION_COMMITMENT_SCHEMA = "jseval.duplicate-observations.v1"
SOURCE_PROJECTION_SCHEMA = "jseval.source-body-projection.v1"
SAMPLED_SOURCE_PROJECTION_SCHEMA = "jseval.source-body-projection.v2"
ENRON_POPULATION_CENSUS_SCHEMA = "jseval.enron-population-exact-census.v1"
ENRON_PROJECTION_PRODUCER = "jseval.corpus-fetch.enron-source-stages.v1"
SOURCE_BODY_PROXY = "source-body-proxy"
PRODUCTION_EXTRACTED = "production-extracted"
_SOURCE_KINDS = frozenset({SOURCE_BODY_PROXY, PRODUCTION_EXTRACTED})
_SHA256_RE = re.compile(r"[0-9a-f]{64}\Z")
_SAFE_ID_RE = re.compile(r"[A-Za-z0-9][A-Za-z0-9._@:+-]{0,255}\Z")
_ADMISSION_KEYS = frozenset(
    {
        "JUSTSEARCH_INGESTION_SKIP_PATTERNS",
        "JUSTSEARCH_INGESTION_SKIP_EXTENSIONS",
        "JUSTSEARCH_INGESTION_SKIP_DIRECTORY_NAMES",
    }
)
_ENRON_STAGE_KEYS = ("raw_member", "parsed_body", "eligible_body", "retained_body")
_ENRON_SAMPLE_METHOD = "algorithm-r-reservoir-without-replacement-v1"
_ENRON_SAMPLE_FRAME = "eligible_body-before-sha-retention"
ENRON_SAMPLE_SCOPE = "frozen-uniform-eligible-body-sample"
_ENRON_POPULATION_SCOPE = "all-eligible-body-occurrences-before-sha-retention"


class DuplicatePrevalenceError(ValueError):
    """The requested analysis is invalid or cannot be completed exactly."""


@dataclass(frozen=True)
class DocumentObservation:
    """One manifest-bound document observation.

    ``extraction_status`` is either ``"success"`` (and text is present) or
    ``"failed"`` (and text is absent).  ``opaque_id`` is used only while
    computing and is never emitted in the aggregate artifact.
    """

    opaque_id: str
    raw_sha256: str
    extracted_text: str | None
    format_id: str | None
    source_kind: str
    extraction_status: str


@dataclass(frozen=True)
class AnalysisConfig:
    """Pinned algorithm parameters for one deterministic analysis."""

    shingle_width: int = 5
    simhash_bits: int = 64
    max_hamming: int = 3
    jaccard_thresholds: tuple[float, ...] = (0.8, 0.9)
    exhaustive_slice_size: int = 100
    bootstrap_draws: int = 20_000
    seed: int = 0
    max_candidate_pairs: int = 1_000_000


def _canonical_bytes(value: object) -> bytes:
    try:
        return json.dumps(
            value,
            sort_keys=True,
            separators=(",", ":"),
            ensure_ascii=False,
            allow_nan=False,
        ).encode("utf-8")
    except (TypeError, ValueError, UnicodeEncodeError) as exc:
        raise DuplicatePrevalenceError("analysis inputs must be canonical UTF-8 JSON values") from exc


def _sha256(value: object) -> str:
    return hashlib.sha256(_canonical_bytes(value)).hexdigest()


def _require_sha256(value: object, field: str) -> str:
    if not isinstance(value, str) or _SHA256_RE.fullmatch(value) is None:
        raise DuplicatePrevalenceError(f"{field} must be a lowercase SHA-256 digest")
    return value


def _require_utf8(value: str, field: str) -> str:
    try:
        value.encode("utf-8")
    except UnicodeEncodeError as exc:
        raise DuplicatePrevalenceError(f"{field} must be valid UTF-8 text") from exc
    return value


def normalize_content(text: str) -> str:
    """Return content-exact normalization v1.

    The transform is locale-neutral: canonical Unicode composition, canonical
    line endings, collapse every Unicode whitespace run to one ASCII space,
    and trim.  It intentionally preserves case and punctuation.
    """

    if not isinstance(text, str):
        raise DuplicatePrevalenceError("extracted text must be a string")
    _require_utf8(text, "extracted text")
    normalized = unicodedata.normalize("NFC", text.replace("\r\n", "\n").replace("\r", "\n"))
    return " ".join(normalized.split())


def tokenize_near_duplicate(text: str) -> tuple[str, ...]:
    """Locale-neutral case-folded runs of Unicode alphanumeric characters."""

    if not isinstance(text, str):
        raise DuplicatePrevalenceError("extracted text must be a string")
    _require_utf8(text, "extracted text")
    folded = unicodedata.normalize("NFC", text).casefold()
    tokens: list[str] = []
    current: list[str] = []
    for character in folded:
        if character.isalnum():
            current.append(character)
        elif current:
            tokens.append("".join(current))
            current = []
    if current:
        tokens.append("".join(current))
    return tuple(tokens)


def token_shingles(text: str, width: int) -> frozenset[tuple[str, ...]]:
    """Return unique token shingles, with one tagged shingle for short text."""

    if isinstance(width, bool) or not isinstance(width, int) or width < 1:
        raise DuplicatePrevalenceError("shingle_width must be a positive integer")
    tokens = tokenize_near_duplicate(text)
    if not tokens:
        return frozenset()
    if len(tokens) < width:
        return frozenset({("\0short", *tokens)})
    return frozenset(tuple(tokens[index : index + width]) for index in range(len(tokens) - width + 1))


def jaccard_similarity(
    left: frozenset[tuple[str, ...]], right: frozenset[tuple[str, ...]]
) -> float:
    """Full set Jaccard; empty sets are non-analyzable, never duplicates."""

    if not left or not right:
        return 0.0
    return len(left & right) / len(left | right)


def _shingle_hash(shingle: tuple[str, ...]) -> int:
    return int.from_bytes(hashlib.sha256(_canonical_bytes(list(shingle))).digest()[:8], "big")


def simhash64(shingles: frozenset[tuple[str, ...]]) -> int:
    """Return an unweighted fixed 64-bit SimHash; bit ties resolve to zero."""

    if not shingles:
        raise DuplicatePrevalenceError("SimHash is undefined for an empty shingle set")
    totals = [0] * 64
    for shingle in sorted(shingles):
        value = _shingle_hash(shingle)
        for bit in range(64):
            totals[bit] += 1 if value & (1 << bit) else -1
    result = 0
    for bit, total in enumerate(totals):
        if total > 0:
            result |= 1 << bit
    return result


def hamming_distance(left: int, right: int) -> int:
    return (left ^ right).bit_count()


def _band_ranges(bits: int, max_hamming: int) -> tuple[tuple[int, int], ...]:
    band_count = max_hamming + 1
    base, remainder = divmod(bits, band_count)
    ranges: list[tuple[int, int]] = []
    start = 0
    for band in range(band_count):
        width = base + (1 if band < remainder else 0)
        ranges.append((start, width))
        start += width
    return tuple(ranges)


def simhash_candidate_pairs(
    fingerprints: Mapping[str, int],
    *,
    bits: int = 64,
    max_hamming: int,
    max_candidate_pairs: int,
) -> frozenset[tuple[str, str]]:
    """Return all pairs colliding in one of ``h+1`` disjoint bands.

    The partition gives a pigeonhole guarantee for Hamming distance ``<= h``.
    Candidate growth is never truncated: exceeding the explicit bound fails.
    """

    if bits != 64:
        raise DuplicatePrevalenceError("only the fixed 64-bit SimHash is supported")
    if (
        isinstance(max_hamming, bool)
        or not isinstance(max_hamming, int)
        or not 0 <= max_hamming < bits
    ):
        raise DuplicatePrevalenceError("max_hamming must be in [0, 63]")
    if (
        isinstance(max_candidate_pairs, bool)
        or not isinstance(max_candidate_pairs, int)
        or max_candidate_pairs < 0
    ):
        raise DuplicatePrevalenceError("max_candidate_pairs must be non-negative")
    if any(
        not isinstance(opaque_id, str)
        or not opaque_id
        or isinstance(value, bool)
        or not isinstance(value, int)
        or not 0 <= value < (1 << bits)
        for opaque_id, value in fingerprints.items()
    ):
        raise DuplicatePrevalenceError("fingerprints must map non-empty IDs to unsigned 64-bit integers")
    ranges = _band_ranges(bits, max_hamming)
    buckets: dict[tuple[int, int], list[str]] = defaultdict(list)
    for opaque_id in sorted(fingerprints):
        value = fingerprints[opaque_id]
        for band, (start, width) in enumerate(ranges):
            mask = (1 << width) - 1
            buckets[(band, (value >> start) & mask)].append(opaque_id)

    candidates: set[tuple[str, str]] = set()
    for bucket_key in sorted(buckets):
        members = buckets[bucket_key]
        for left_index, left in enumerate(members):
            for right in members[left_index + 1 :]:
                candidates.add((left, right))
                if len(candidates) > max_candidate_pairs:
                    raise DuplicatePrevalenceError(
                        "SimHash candidate count exceeds max_candidate_pairs; "
                        "analysis refuses to truncate candidates"
                    )
    return frozenset(candidates)


def _validate_config(config: AnalysisConfig) -> None:
    if config.simhash_bits != 64:
        raise DuplicatePrevalenceError("only the fixed 64-bit SimHash is supported")
    if (
        isinstance(config.max_hamming, bool)
        or not isinstance(config.max_hamming, int)
        or not 0 <= config.max_hamming < 64
    ):
        raise DuplicatePrevalenceError("max_hamming must be in [0, 63]")
    if (
        isinstance(config.exhaustive_slice_size, bool)
        or not isinstance(config.exhaustive_slice_size, int)
        or config.exhaustive_slice_size < 0
    ):
        raise DuplicatePrevalenceError("exhaustive_slice_size must be a non-negative integer")
    if (
        isinstance(config.bootstrap_draws, bool)
        or not isinstance(config.bootstrap_draws, int)
        or config.bootstrap_draws < 1
    ):
        raise DuplicatePrevalenceError("bootstrap_draws must be a positive integer")
    if isinstance(config.seed, bool) or not isinstance(config.seed, int):
        raise DuplicatePrevalenceError("seed must be an integer")
    if (
        isinstance(config.max_candidate_pairs, bool)
        or not isinstance(config.max_candidate_pairs, int)
        or config.max_candidate_pairs < 0
    ):
        raise DuplicatePrevalenceError("max_candidate_pairs must be a non-negative integer")
    token_shingles("validation", config.shingle_width)
    if not isinstance(config.jaccard_thresholds, tuple) or not config.jaccard_thresholds:
        raise DuplicatePrevalenceError("jaccard_thresholds must be a non-empty tuple")
    if any(
        isinstance(value, bool)
        or not isinstance(value, (int, float))
        or not math.isfinite(value)
        or not 0 < value <= 1
        for value in config.jaccard_thresholds
    ):
        raise DuplicatePrevalenceError("Jaccard thresholds must be finite values in (0, 1]")
    if tuple(sorted(set(config.jaccard_thresholds))) != config.jaccard_thresholds:
        raise DuplicatePrevalenceError("jaccard_thresholds must be unique and increasing")


def validate_config(config: AnalysisConfig) -> AnalysisConfig:
    """Validate and return a pinned analysis configuration."""

    if not isinstance(config, AnalysisConfig):
        raise DuplicatePrevalenceError("config must be an AnalysisConfig")
    _validate_config(config)
    return config


def _validate_corpus_identity(
    identity: Mapping[str, Any], document_count: int, *, require_count_match: bool
) -> dict[str, Any]:
    if not isinstance(identity, Mapping):
        raise DuplicatePrevalenceError("corpus_identity must be a mapping")
    required = {
        "profile_id",
        "signature",
        "kind",
        "schema",
        "file_count",
        "total_bytes",
        "manifest_pointer",
        "admission_policy",
    }
    if set(identity) != required:
        raise DuplicatePrevalenceError("corpus_identity must be the complete strict P3 raw identity")
    profile_id = identity["profile_id"]
    if profile_id is not None and (
        not isinstance(profile_id, str) or _SAFE_ID_RE.fullmatch(profile_id) is None
    ):
        raise DuplicatePrevalenceError("corpus_identity.profile_id must be null or a safe identifier")
    if profile_id is not None:
        _require_utf8(profile_id, "corpus_identity.profile_id")
    if identity["kind"] != "raw-files":
        raise DuplicatePrevalenceError("corpus_identity must identify a raw-files corpus")
    if identity["schema"] != RAW_CORPUS_MANIFEST_SCHEMA:
        raise DuplicatePrevalenceError("corpus_identity has an unsupported manifest schema")
    signature = _require_sha256(identity["signature"], "corpus_identity.signature")
    file_count = identity["file_count"]
    total_bytes = identity["total_bytes"]
    if isinstance(file_count, bool) or not isinstance(file_count, int) or file_count < 0:
        raise DuplicatePrevalenceError("corpus_identity.file_count must be non-negative")
    if require_count_match and file_count != document_count:
        raise DuplicatePrevalenceError("observation count does not match corpus_identity.file_count")
    if isinstance(total_bytes, bool) or not isinstance(total_bytes, int) or total_bytes < 0:
        raise DuplicatePrevalenceError("corpus_identity.total_bytes must be non-negative")
    pointer = identity["manifest_pointer"]
    if pointer is not None and (not isinstance(pointer, str) or not pointer):
        raise DuplicatePrevalenceError("corpus_identity.manifest_pointer must be null or non-empty")
    if pointer is not None:
        _require_utf8(pointer, "corpus_identity.manifest_pointer")
    admission = identity["admission_policy"]
    if not isinstance(admission, Mapping) or set(admission) != _ADMISSION_KEYS:
        raise DuplicatePrevalenceError("corpus_identity.admission_policy is incomplete")
    if any(not isinstance(value, str) for value in admission.values()):
        raise DuplicatePrevalenceError("corpus admission policy values must be strings")
    for key, value in admission.items():
        _require_utf8(value, f"corpus_identity.admission_policy.{key}")
    # Deliberately omit the manifest pointer: it may disclose a private path.
    return {
        "kind": "raw-files",
        "profile_id": profile_id,
        "schema": RAW_CORPUS_MANIFEST_SCHEMA,
        "signature": signature,
        "file_count": file_count,
        "total_bytes": total_bytes,
        "admission_policy_digest": _sha256({key: admission[key] for key in sorted(admission)}),
    }


def _validate_extraction_identity(
    identity: Mapping[str, Any] | None,
    *,
    source_kind: str,
    corpus_signature: str,
    observations_digest: str,
    corpus_count: int,
) -> dict[str, Any] | None:
    if identity is None:
        if source_kind == PRODUCTION_EXTRACTED:
            raise DuplicatePrevalenceError(
                "production-extracted input requires extraction identity and reconciliation"
            )
        return None
    if source_kind == SOURCE_BODY_PROXY:
        raise DuplicatePrevalenceError("source-body-proxy input must not claim production extraction identity")
    if not isinstance(identity, Mapping):
        raise DuplicatePrevalenceError("extraction_identity must be a mapping")
    required = {
        "schema",
        "digest",
        "corpus_signature",
        "observations_digest",
        "source_kind",
        "extractor_build",
        "extraction_policy_digest",
        "document_count",
        "reconciliation",
    }
    optional_result_binding = {
        "result_aliases_hmac_sha256",
        "result_mapping_public_key_ed25519",
    }
    allowed = required | optional_result_binding
    if set(identity) not in (required, allowed):
        raise DuplicatePrevalenceError("extraction_identity is incomplete")
    extraction_schema = identity["schema"]
    if extraction_schema not in {EXTRACTION_SNAPSHOT_SCHEMA, EXTRACTION_SNAPSHOT_SCHEMA_V2}:
        raise DuplicatePrevalenceError("extraction_identity has an unsupported snapshot schema")
    digest = _require_sha256(identity["digest"], "extraction_identity.digest")
    if _require_sha256(identity["corpus_signature"], "extraction_identity.corpus_signature") != corpus_signature:
        raise DuplicatePrevalenceError("extraction identity is not bound to this raw corpus manifest")
    if (
        _require_sha256(identity["observations_digest"], "extraction_identity.observations_digest")
        != observations_digest
    ):
        raise DuplicatePrevalenceError("extraction identity is not bound to the supplied observations")
    policy_digest = _require_sha256(
        identity["extraction_policy_digest"], "extraction_identity.extraction_policy_digest"
    )
    result_aliases_hmac_sha256 = (
        _require_sha256(
            identity["result_aliases_hmac_sha256"],
            "extraction_identity.result_aliases_hmac_sha256",
        )
        if "result_aliases_hmac_sha256" in identity
        else None
    )
    result_mapping_public_key_ed25519 = (
        _require_sha256(
            identity["result_mapping_public_key_ed25519"],
            "extraction_identity.result_mapping_public_key_ed25519",
        )
        if "result_mapping_public_key_ed25519" in identity
        else None
    )
    if (result_aliases_hmac_sha256 is None) != (result_mapping_public_key_ed25519 is None):
        raise DuplicatePrevalenceError(
            "extraction result binding requires both alias HMAC and mapping verification key"
        )
    if identity["source_kind"] != source_kind:
        raise DuplicatePrevalenceError("extraction identity source kind does not match observations")
    build = identity["extractor_build"]
    if not isinstance(build, str) or _SAFE_ID_RE.fullmatch(build) is None:
        raise DuplicatePrevalenceError("extraction_identity.extractor_build must be a safe identifier")
    if (
        isinstance(identity["document_count"], bool)
        or not isinstance(identity["document_count"], int)
        or identity["document_count"] != corpus_count
    ):
        raise DuplicatePrevalenceError("extraction document count does not match corpus count")
    reconciliation = identity["reconciliation"]
    v1_reconciliation = {
        "status",
        "expected_count",
        "exported_count",
        "unique_opaque_ids",
    }
    v2_reconciliation = v1_reconciliation | {
        "indexed_count",
        "terminal_excluded_count",
        "partial_success_count",
        "terminal_exclusion_reasons",
    }
    expected_reconciliation = (
        v2_reconciliation if extraction_schema == EXTRACTION_SNAPSHOT_SCHEMA_V2 else v1_reconciliation
    )
    if not isinstance(reconciliation, Mapping) or set(reconciliation) != expected_reconciliation:
        raise DuplicatePrevalenceError("extraction reconciliation is incomplete")
    count_fields = ("expected_count", "exported_count", "unique_opaque_ids")
    expected_status = (
        "matched-with-disposition-accounting"
        if extraction_schema == EXTRACTION_SNAPSHOT_SCHEMA_V2
        else "matched"
    )
    if reconciliation["status"] != expected_status or any(
        isinstance(reconciliation[field], bool)
        or not isinstance(reconciliation[field], int)
        or reconciliation[field] != corpus_count
        for field in count_fields
    ):
        raise DuplicatePrevalenceError("extraction reconciliation did not match the corpus")
    normalized_reconciliation: dict[str, Any] = {
        "status": expected_status,
        "expected_count": corpus_count,
        "exported_count": corpus_count,
        "unique_opaque_ids": corpus_count,
    }
    if extraction_schema == EXTRACTION_SNAPSHOT_SCHEMA_V2:
        disposition_fields = (
            "indexed_count",
            "terminal_excluded_count",
            "partial_success_count",
        )
        if any(
            isinstance(reconciliation[field], bool)
            or not isinstance(reconciliation[field], int)
            or reconciliation[field] < 0
            for field in disposition_fields
        ):
            raise DuplicatePrevalenceError("extraction disposition counts must be non-negative integers")
        indexed_count = reconciliation["indexed_count"]
        excluded_count = reconciliation["terminal_excluded_count"]
        partial_count = reconciliation["partial_success_count"]
        reasons = reconciliation["terminal_exclusion_reasons"]
        if indexed_count + excluded_count != corpus_count or partial_count > indexed_count:
            raise DuplicatePrevalenceError("extraction disposition counts do not reconcile to the corpus")
        if not isinstance(reasons, Mapping) or any(
            not isinstance(reason, str)
            or _SAFE_ID_RE.fullmatch(reason) is None
            or isinstance(count, bool)
            or not isinstance(count, int)
            or count <= 0
            for reason, count in reasons.items()
        ):
            raise DuplicatePrevalenceError("terminal exclusion reasons are invalid")
        if sum(reasons.values()) != excluded_count:
            raise DuplicatePrevalenceError("terminal exclusion reasons do not match the exclusion count")
        normalized_reconciliation.update(
            {
                "indexed_count": indexed_count,
                "terminal_excluded_count": excluded_count,
                "partial_success_count": partial_count,
                "terminal_exclusion_reasons": {
                    reason: reasons[reason] for reason in sorted(reasons)
                },
            }
        )
    unsigned_identity = {key: identity[key] for key in identity if key != "digest"}
    if _sha256(unsigned_identity) != digest:
        raise DuplicatePrevalenceError("extraction snapshot digest does not match its committed metadata")
    return {
        "schema": extraction_schema,
        "digest": digest,
        "corpus_signature": corpus_signature,
        "observations_digest": observations_digest,
        "source_kind": source_kind,
        "extractor_build": build,
        "extraction_policy_digest": policy_digest,
        **(
            {"result_aliases_hmac_sha256": result_aliases_hmac_sha256}
            if result_aliases_hmac_sha256
            else {}
        ),
        **(
            {"result_mapping_public_key_ed25519": result_mapping_public_key_ed25519}
            if result_mapping_public_key_ed25519
            else {}
        ),
        "document_count": corpus_count,
        "reconciliation": normalized_reconciliation,
    }


def _validate_stage_counts(stage_counts: object, observation_count: int) -> dict[str, int]:
    counts = _validate_stage_counts_without_observation_match(stage_counts)
    if counts["eligible_body"] != observation_count:
        raise DuplicatePrevalenceError("eligible-body stage count does not match observations")
    return counts


def build_enron_source_projection_identity(
    observations: Sequence[DocumentObservation],
    *,
    corpus_signature: str,
    min_words: int,
    stage_counts: Mapping[str, int],
) -> dict[str, Any]:
    """Build the privacy-safe identity for an Enron eligible-body projection."""

    ordered = _validate_observations(observations)
    if not ordered or any(item.source_kind != SOURCE_BODY_PROXY for item in ordered):
        raise DuplicatePrevalenceError("Enron projection observations must be source-body proxies")
    if any(item.extraction_status != "success" or item.extracted_text is None for item in ordered):
        raise DuplicatePrevalenceError("every eligible Enron body must be a successful text observation")
    if any(
        hashlib.sha256(item.extracted_text.encode("utf-8")).hexdigest() != item.raw_sha256
        for item in ordered
    ):
        raise DuplicatePrevalenceError("eligible Enron body digests must match the projected body text")
    checked_signature = _require_sha256(corpus_signature, "source_projection.corpus_signature")
    if isinstance(min_words, bool) or not isinstance(min_words, int) or min_words < 0:
        raise DuplicatePrevalenceError("source projection min_words must be a non-negative integer")
    counts = _validate_stage_counts(stage_counts, len(ordered))
    if counts["retained_body"] != len({item.raw_sha256 for item in ordered}):
        raise DuplicatePrevalenceError("retained-body count must equal distinct eligible body digests")
    unsigned: dict[str, Any] = {
        "schema": SOURCE_PROJECTION_SCHEMA,
        "corpus_signature": checked_signature,
        "observations_digest": _observation_commitment(ordered),
        "producer": ENRON_PROJECTION_PRODUCER,
        "measurement_stage": "eligible_body",
        "policy": {
            "text_decode": "utf-8-errors-replace",
            "line_endings": "crlf-to-lf",
            "body_selection": "first-blank-line-then-strip",
            "word_count": "python-str-split",
            "member_admission": "tar-regular-files-only",
            "member_order": "unicode-codepoint-member-name",
            "retention": "first-body-sha256",
            "min_words": min_words,
        },
        "observation_count": len(ordered),
        "stage_counts": counts,
    }
    return {"digest": _sha256(unsigned), **unsigned}


def build_enron_sampled_source_projection_identity(
    observations: Sequence[DocumentObservation],
    *,
    corpus_signature: str,
    min_words: int,
    stage_counts: Mapping[str, int],
    sample_size: int,
    sample_seed: int,
    raw_body_digest_counts: Mapping[str, int],
    normalized_content_digest_counts: Mapping[str, int],
) -> dict[str, Any]:
    """Commit a full exact census while retaining only a frozen Enron body sample."""

    ordered = _validate_observations(observations)
    if any(item.source_kind != SOURCE_BODY_PROXY for item in ordered):
        raise DuplicatePrevalenceError("Enron sample observations must be source-body proxies")
    if any(item.extraction_status != "success" or item.extracted_text is None for item in ordered):
        raise DuplicatePrevalenceError("every sampled Enron body must be a successful text observation")
    if any(
        hashlib.sha256(item.extracted_text.encode("utf-8")).hexdigest() != item.raw_sha256
        for item in ordered
    ):
        raise DuplicatePrevalenceError("sampled Enron body digests must match the projected body text")
    checked_signature = _require_sha256(corpus_signature, "source_projection.corpus_signature")
    if isinstance(min_words, bool) or not isinstance(min_words, int) or min_words < 0:
        raise DuplicatePrevalenceError("source projection min_words must be a non-negative integer")
    if isinstance(sample_size, bool) or not isinstance(sample_size, int) or not 1 <= sample_size <= 5_000:
        raise DuplicatePrevalenceError("source projection sample size must be between 1 and 5000")
    if isinstance(sample_seed, bool) or not isinstance(sample_seed, int) or sample_seed < 0:
        raise DuplicatePrevalenceError("source projection sample seed must be a non-negative integer")

    counts = _validate_stage_counts_without_observation_match(stage_counts)
    population_count = counts["eligible_body"]
    expected_sample_count = min(sample_size, population_count)
    if len(ordered) != expected_sample_count:
        raise DuplicatePrevalenceError("reservoir sample count does not match its frame and requested size")
    if sum(raw_body_digest_counts.values()) != population_count:
        raise DuplicatePrevalenceError("raw-body census count does not match eligible-body population")
    normalized_count = sum(normalized_content_digest_counts.values())
    if normalized_count > population_count:
        raise DuplicatePrevalenceError("normalized-content census exceeds eligible-body population")
    raw_commitment = _digest_counter_commitment(
        raw_body_digest_counts, purpose="enron-eligible-raw-body-sha256"
    )
    content_commitment = _digest_counter_commitment(
        normalized_content_digest_counts,
        purpose="enron-eligible-normalized-content-sha256",
    )
    if counts["retained_body"] != len(raw_body_digest_counts):
        raise DuplicatePrevalenceError("retained-body count must equal distinct eligible body digests")

    census_unsigned: dict[str, Any] = {
        "schema": ENRON_POPULATION_CENSUS_SCHEMA,
        "scope": _ENRON_POPULATION_SCOPE,
        "eligible_occurrences": population_count,
        "content_exact_analyzable_nonempty_documents": normalized_count,
        "content_exact_excluded_empty_documents": population_count - normalized_count,
        "raw_body_digest_counts_commitment": raw_commitment,
        "normalized_content_digest_counts_commitment": content_commitment,
        "byte_exact": {
            "interpretation": "source-body-bytes-proxy",
            **_compact_exact_census(raw_body_digest_counts),
        },
        "content_exact": {
            "interpretation": "source-body-proxy",
            **_compact_exact_census(normalized_content_digest_counts),
        },
        "near_duplicate": {
            "status": "unmeasured",
            "reason": "full-population body texts are not retained",
        },
    }
    census = {"digest": _sha256(census_unsigned), **census_unsigned}
    unsigned: dict[str, Any] = {
        "schema": SAMPLED_SOURCE_PROJECTION_SCHEMA,
        "corpus_signature": checked_signature,
        "observations_digest": _observation_commitment(ordered),
        "producer": ENRON_PROJECTION_PRODUCER,
        "measurement_stage": "eligible_body",
        "policy": {
            "text_decode": "utf-8-errors-replace",
            "line_endings": "crlf-to-lf",
            "body_selection": "first-blank-line-then-strip",
            "word_count": "python-str-split",
            "member_admission": "tar-regular-files-only",
            "member_order": "unicode-codepoint-member-name",
            "retention": "first-body-sha256",
            "min_words": min_words,
        },
        "sampling": {
            "method": _ENRON_SAMPLE_METHOD,
            "frame": _ENRON_SAMPLE_FRAME,
            "requested_size": sample_size,
            "seed": sample_seed,
            "population_count": population_count,
            "sample_count": len(ordered),
            "per_occurrence_inclusion_probability": (
                len(ordered) / population_count if population_count else None
            ),
        },
        "observation_count": len(ordered),
        "stage_counts": counts,
        "population_exact_census": census,
    }
    return {"digest": _sha256(unsigned), **unsigned}


def _validate_stage_counts_without_observation_match(stage_counts: object) -> dict[str, int]:
    if not isinstance(stage_counts, Mapping) or set(stage_counts) != set(_ENRON_STAGE_KEYS):
        raise DuplicatePrevalenceError("source projection stage_counts are incomplete")
    if any(
        isinstance(stage_counts[key], bool)
        or not isinstance(stage_counts[key], int)
        or stage_counts[key] < 0
        for key in _ENRON_STAGE_KEYS
    ):
        raise DuplicatePrevalenceError("source projection stage counts must be non-negative integers")
    counts = {key: stage_counts[key] for key in _ENRON_STAGE_KEYS}
    if not (
        counts["raw_member"]
        >= counts["parsed_body"]
        >= counts["eligible_body"]
        >= counts["retained_body"]
    ):
        raise DuplicatePrevalenceError("source projection stage counts must be monotonically non-increasing")
    return counts


def _validate_source_projection_identity(
    identity: Mapping[str, Any] | None,
    *,
    source_kind: str,
    corpus_signature: str,
    observations_digest: str,
    observations: Sequence[DocumentObservation],
) -> dict[str, Any] | None:
    observation_count = len(observations)
    if identity is None:
        return None
    if source_kind != SOURCE_BODY_PROXY:
        raise DuplicatePrevalenceError("production-extracted input must not claim a source-body projection")
    if not isinstance(identity, Mapping):
        raise DuplicatePrevalenceError("source_projection_identity must be a mapping")
    if identity.get("schema") == SAMPLED_SOURCE_PROJECTION_SCHEMA:
        return _validate_sampled_source_projection_identity(
            identity,
            corpus_signature=corpus_signature,
            observations_digest=observations_digest,
            observations=observations,
        )
    required = {
        "schema",
        "digest",
        "corpus_signature",
        "observations_digest",
        "producer",
        "measurement_stage",
        "policy",
        "observation_count",
        "stage_counts",
    }
    if set(identity) != required:
        raise DuplicatePrevalenceError("source_projection_identity is incomplete")
    if identity["schema"] != SOURCE_PROJECTION_SCHEMA:
        raise DuplicatePrevalenceError("source projection has an unsupported schema")
    digest = _require_sha256(identity["digest"], "source_projection.digest")
    if _require_sha256(identity["corpus_signature"], "source_projection.corpus_signature") != corpus_signature:
        raise DuplicatePrevalenceError("source projection is not bound to this raw corpus manifest")
    if (
        _require_sha256(identity["observations_digest"], "source_projection.observations_digest")
        != observations_digest
    ):
        raise DuplicatePrevalenceError("source projection is not bound to the supplied observations")
    if identity["producer"] != ENRON_PROJECTION_PRODUCER:
        raise DuplicatePrevalenceError("source projection has an unsupported producer")
    if identity["measurement_stage"] != "eligible_body":
        raise DuplicatePrevalenceError("source projection must measure eligible_body before deduplication")
    if (
        isinstance(identity["observation_count"], bool)
        or not isinstance(identity["observation_count"], int)
        or identity["observation_count"] != observation_count
    ):
        raise DuplicatePrevalenceError("source projection observation count does not match observations")
    policy = identity["policy"]
    required_policy = {
        "text_decode": "utf-8-errors-replace",
        "line_endings": "crlf-to-lf",
        "body_selection": "first-blank-line-then-strip",
        "word_count": "python-str-split",
        "member_admission": "tar-regular-files-only",
        "member_order": "unicode-codepoint-member-name",
        "retention": "first-body-sha256",
    }
    if not isinstance(policy, Mapping) or set(policy) != {*required_policy, "min_words"}:
        raise DuplicatePrevalenceError("source projection policy is incomplete")
    if any(policy[key] != value for key, value in required_policy.items()):
        raise DuplicatePrevalenceError("source projection policy does not match the supported Enron stages")
    min_words = policy["min_words"]
    if isinstance(min_words, bool) or not isinstance(min_words, int) or min_words < 0:
        raise DuplicatePrevalenceError("source projection min_words must be a non-negative integer")
    counts = _validate_stage_counts(identity["stage_counts"], observation_count)
    if any(
        item.extraction_status != "success"
        or item.extracted_text is None
        or hashlib.sha256(item.extracted_text.encode("utf-8")).hexdigest() != item.raw_sha256
        for item in observations
    ):
        raise DuplicatePrevalenceError("source projection observations are not eligible Enron body views")
    if counts["retained_body"] != len({item.raw_sha256 for item in observations}):
        raise DuplicatePrevalenceError("retained-body count must equal distinct eligible body digests")
    unsigned_identity = {key: identity[key] for key in identity if key != "digest"}
    if _sha256(unsigned_identity) != digest:
        raise DuplicatePrevalenceError("source projection digest does not match its committed metadata")
    return {
        "schema": SOURCE_PROJECTION_SCHEMA,
        "digest": digest,
        "corpus_signature": corpus_signature,
        "observations_digest": observations_digest,
        "producer": ENRON_PROJECTION_PRODUCER,
        "measurement_stage": "eligible_body",
        "policy": {**required_policy, "min_words": min_words},
        "observation_count": observation_count,
        "stage_counts": counts,
    }


def _validate_sampled_source_projection_identity(
    identity: Mapping[str, Any],
    *,
    corpus_signature: str,
    observations_digest: str,
    observations: Sequence[DocumentObservation],
) -> dict[str, Any]:
    required = {
        "schema",
        "digest",
        "corpus_signature",
        "observations_digest",
        "producer",
        "measurement_stage",
        "policy",
        "sampling",
        "observation_count",
        "stage_counts",
        "population_exact_census",
    }
    if set(identity) != required:
        raise DuplicatePrevalenceError("sampled source_projection_identity is incomplete")
    digest = _require_sha256(identity["digest"], "source_projection.digest")
    if _require_sha256(identity["corpus_signature"], "source_projection.corpus_signature") != corpus_signature:
        raise DuplicatePrevalenceError("source projection is not bound to this raw corpus manifest")
    if _require_sha256(identity["observations_digest"], "source_projection.observations_digest") != observations_digest:
        raise DuplicatePrevalenceError("source projection is not bound to the supplied sample")
    if identity["producer"] != ENRON_PROJECTION_PRODUCER:
        raise DuplicatePrevalenceError("source projection has an unsupported producer")
    if identity["measurement_stage"] != "eligible_body":
        raise DuplicatePrevalenceError("source projection must measure eligible_body before deduplication")
    if (
        isinstance(identity["observation_count"], bool)
        or not isinstance(identity["observation_count"], int)
        or identity["observation_count"] != len(observations)
    ):
        raise DuplicatePrevalenceError("source projection observation count does not match sample")

    required_policy = {
        "text_decode": "utf-8-errors-replace",
        "line_endings": "crlf-to-lf",
        "body_selection": "first-blank-line-then-strip",
        "word_count": "python-str-split",
        "member_admission": "tar-regular-files-only",
        "member_order": "unicode-codepoint-member-name",
        "retention": "first-body-sha256",
    }
    policy = identity["policy"]
    if not isinstance(policy, Mapping) or set(policy) != {*required_policy, "min_words"}:
        raise DuplicatePrevalenceError("source projection policy is incomplete")
    if any(policy[key] != value for key, value in required_policy.items()):
        raise DuplicatePrevalenceError("source projection policy does not match the supported Enron stages")
    min_words = policy["min_words"]
    if isinstance(min_words, bool) or not isinstance(min_words, int) or min_words < 0:
        raise DuplicatePrevalenceError("source projection min_words must be a non-negative integer")

    counts = _validate_stage_counts_without_observation_match(identity["stage_counts"])
    sampling = identity["sampling"]
    sampling_fields = {
        "method",
        "frame",
        "requested_size",
        "seed",
        "population_count",
        "sample_count",
        "per_occurrence_inclusion_probability",
    }
    if not isinstance(sampling, Mapping) or set(sampling) != sampling_fields:
        raise DuplicatePrevalenceError("source projection sampling contract is incomplete")
    if sampling["method"] != _ENRON_SAMPLE_METHOD or sampling["frame"] != _ENRON_SAMPLE_FRAME:
        raise DuplicatePrevalenceError("source projection sampling method or frame is unsupported")
    requested_size = sampling["requested_size"]
    seed = sampling["seed"]
    if isinstance(requested_size, bool) or not isinstance(requested_size, int) or not 1 <= requested_size <= 5_000:
        raise DuplicatePrevalenceError("source projection sample size must be between 1 and 5000")
    if isinstance(seed, bool) or not isinstance(seed, int) or seed < 0:
        raise DuplicatePrevalenceError("source projection sample seed must be a non-negative integer")
    population_count = counts["eligible_body"]
    sample_count = min(requested_size, population_count)
    sampling_counts = (sampling["population_count"], sampling["sample_count"])
    if any(isinstance(value, bool) or not isinstance(value, int) for value in sampling_counts):
        raise DuplicatePrevalenceError("source projection sampling counts must be integers")
    if sampling["population_count"] != population_count or sampling["sample_count"] != sample_count:
        raise DuplicatePrevalenceError("source projection sampling counts do not match the stage frame")
    if sample_count != len(observations):
        raise DuplicatePrevalenceError("source projection sample count does not match observations")
    expected_probability = sample_count / population_count if population_count else None
    probability = sampling["per_occurrence_inclusion_probability"]
    if isinstance(probability, bool) or probability != expected_probability:
        raise DuplicatePrevalenceError("source projection inclusion probability is inconsistent")

    census = identity["population_exact_census"]
    census_fields = {
        "schema",
        "digest",
        "scope",
        "eligible_occurrences",
        "content_exact_analyzable_nonempty_documents",
        "content_exact_excluded_empty_documents",
        "raw_body_digest_counts_commitment",
        "normalized_content_digest_counts_commitment",
        "byte_exact",
        "content_exact",
        "near_duplicate",
    }
    if not isinstance(census, Mapping) or set(census) != census_fields:
        raise DuplicatePrevalenceError("population exact census is incomplete")
    if census["schema"] != ENRON_POPULATION_CENSUS_SCHEMA or census["scope"] != _ENRON_POPULATION_SCOPE:
        raise DuplicatePrevalenceError("population exact census schema or scope is unsupported")
    census_digest = _require_sha256(census["digest"], "population_exact_census.digest")
    if (
        isinstance(census["eligible_occurrences"], bool)
        or not isinstance(census["eligible_occurrences"], int)
        or census["eligible_occurrences"] != population_count
    ):
        raise DuplicatePrevalenceError("population exact census does not match eligible-body count")
    analyzable = census["content_exact_analyzable_nonempty_documents"]
    excluded = census["content_exact_excluded_empty_documents"]
    if any(isinstance(value, bool) or not isinstance(value, int) or value < 0 for value in (analyzable, excluded)):
        raise DuplicatePrevalenceError("population exact census content counts must be non-negative integers")
    if analyzable + excluded != population_count:
        raise DuplicatePrevalenceError("population exact census content counts do not cover the population")
    _require_sha256(census["raw_body_digest_counts_commitment"], "population exact raw commitment")
    _require_sha256(
        census["normalized_content_digest_counts_commitment"],
        "population exact content commitment",
    )
    byte_exact = census["byte_exact"]
    content_exact = census["content_exact"]
    if not isinstance(byte_exact, Mapping) or byte_exact.get("interpretation") != "source-body-bytes-proxy":
        raise DuplicatePrevalenceError("population byte-exact interpretation is unsupported")
    if not isinstance(content_exact, Mapping) or content_exact.get("interpretation") != "source-body-proxy":
        raise DuplicatePrevalenceError("population content-exact interpretation is unsupported")
    _validate_compact_exact_census(
        {key: value for key, value in byte_exact.items() if key != "interpretation"},
        expected_count=population_count,
        label="population_exact_census.byte_exact",
    )
    _validate_compact_exact_census(
        {key: value for key, value in content_exact.items() if key != "interpretation"},
        expected_count=analyzable,
        label="population_exact_census.content_exact",
    )
    if census["near_duplicate"] != {
        "status": "unmeasured",
        "reason": "full-population body texts are not retained",
    }:
        raise DuplicatePrevalenceError("population near-duplicate census must remain unmeasured")
    census_unsigned = {key: census[key] for key in census if key != "digest"}
    if _sha256(census_unsigned) != census_digest:
        raise DuplicatePrevalenceError("population exact census digest does not match its metadata")
    if any(
        item.extraction_status != "success"
        or item.extracted_text is None
        or hashlib.sha256(item.extracted_text.encode("utf-8")).hexdigest() != item.raw_sha256
        for item in observations
    ):
        raise DuplicatePrevalenceError("source projection sample is not composed of eligible body views")
    unsigned_identity = {key: identity[key] for key in identity if key != "digest"}
    if _sha256(unsigned_identity) != digest:
        raise DuplicatePrevalenceError("source projection digest does not match its committed metadata")
    return dict(identity)


def _validate_observations(observations: Sequence[DocumentObservation]) -> tuple[DocumentObservation, ...]:
    if isinstance(observations, (str, bytes)) or not isinstance(observations, Sequence):
        raise DuplicatePrevalenceError("observations must be a sequence")
    for observation in observations:
        if not isinstance(observation, DocumentObservation):
            raise DuplicatePrevalenceError("every observation must be a DocumentObservation")
        if not isinstance(observation.opaque_id, str) or not observation.opaque_id:
            raise DuplicatePrevalenceError("opaque IDs must be non-empty strings")
        _require_utf8(observation.opaque_id, "opaque_id")
    ordered = sorted(observations, key=lambda observation: observation.opaque_id)
    seen: set[str] = set()
    source_kinds: set[str] = set()
    for observation in ordered:
        if observation.opaque_id in seen:
            raise DuplicatePrevalenceError("opaque IDs must be non-empty and unique")
        seen.add(observation.opaque_id)
        _require_sha256(observation.raw_sha256, "raw_sha256")
        if observation.format_id is not None and not isinstance(observation.format_id, str):
            raise DuplicatePrevalenceError("format_id must be null or a string")
        if observation.format_id is not None:
            _require_utf8(observation.format_id, "format_id")
        if observation.source_kind not in _SOURCE_KINDS:
            raise DuplicatePrevalenceError("unsupported source_kind")
        source_kinds.add(observation.source_kind)
        if observation.extraction_status == "success":
            if not isinstance(observation.extracted_text, str):
                raise DuplicatePrevalenceError("successful extraction requires text")
            _require_utf8(observation.extracted_text, "extracted_text")
        elif observation.extraction_status == "failed":
            if observation.extracted_text is not None:
                raise DuplicatePrevalenceError("failed extraction must not include text")
        else:
            raise DuplicatePrevalenceError("extraction_status must be success or failed")
    if len(source_kinds) != 1:
        raise DuplicatePrevalenceError("one analysis cannot mix source kinds")
    return tuple(ordered)


def _observation_commitment(ordered: Sequence[DocumentObservation]) -> str:
    return _sha256(
        {
            "schema": OBSERVATION_COMMITMENT_SCHEMA,
            "documents": [
                {
                    "opaque_id": item.opaque_id,
                    "raw_sha256": item.raw_sha256,
                    "extracted_text": item.extracted_text,
                    "format_id": item.format_id,
                    "source_kind": item.source_kind,
                    "extraction_status": item.extraction_status,
                }
                for item in ordered
            ],
        }
    )


def observation_commitment(observations: Sequence[DocumentObservation]) -> str:
    """Commit to the complete validated observation batch in opaque-id order."""

    return _observation_commitment(_validate_observations(observations))


def validate_extraction_identity(
    identity: Mapping[str, Any],
    observations: Sequence[DocumentObservation],
    *,
    corpus_signature: str,
) -> dict[str, Any]:
    """Validate a production snapshot identity against its exact observation batch."""

    ordered = _validate_observations(observations)
    if any(item.source_kind != PRODUCTION_EXTRACTED for item in ordered):
        raise DuplicatePrevalenceError("extraction identity requires production-extracted observations")
    validated = _validate_extraction_identity(
        identity,
        source_kind=PRODUCTION_EXTRACTED,
        corpus_signature=_require_sha256(corpus_signature, "corpus_signature"),
        observations_digest=_observation_commitment(ordered),
        corpus_count=len(ordered),
    )
    assert validated is not None
    return validated


def validate_source_projection_identity(
    identity: Mapping[str, Any],
    observations: Sequence[DocumentObservation],
    *,
    corpus_signature: str,
) -> dict[str, Any]:
    """Validate an Enron projection identity against its exact eligible bodies."""

    ordered = _validate_observations(observations)
    if any(item.source_kind != SOURCE_BODY_PROXY for item in ordered):
        raise DuplicatePrevalenceError("source projection requires source-body proxy observations")
    validated = _validate_source_projection_identity(
        identity,
        source_kind=SOURCE_BODY_PROXY,
        corpus_signature=_require_sha256(corpus_signature, "corpus_signature"),
        observations_digest=_observation_commitment(ordered),
        observations=ordered,
    )
    assert validated is not None
    return validated


def _components(ids: Iterable[str], edges: Iterable[tuple[str, str]]) -> tuple[tuple[str, ...], ...]:
    ordered_ids = tuple(sorted(ids))
    parent = {item: item for item in ordered_ids}

    def find(item: str) -> str:
        while parent[item] != item:
            parent[item] = parent[parent[item]]
            item = parent[item]
        return item

    for left, right in sorted(edges):
        left_root, right_root = find(left), find(right)
        if left_root != right_root:
            if left_root < right_root:
                parent[right_root] = left_root
            else:
                parent[left_root] = right_root
    grouped: dict[str, list[str]] = defaultdict(list)
    for item in ordered_ids:
        grouped[find(item)].append(item)
    return tuple(sorted((tuple(items) for items in grouped.values()), key=lambda item: item))


def connected_components(
    ids: Iterable[str], edges: Iterable[tuple[str, str]]
) -> tuple[tuple[str, ...], ...]:
    """Return deterministic single-linkage components including singleton ids."""

    return _components(ids, edges)


def _percentile(sorted_values: Sequence[float], probability: float) -> float:
    if len(sorted_values) == 1:
        return sorted_values[0]
    position = probability * (len(sorted_values) - 1)
    lower = math.floor(position)
    upper = math.ceil(position)
    if lower == upper:
        return sorted_values[lower]
    fraction = position - lower
    return sorted_values[lower] * (1 - fraction) + sorted_values[upper] * fraction


def _component_interval(
    components: Sequence[Sequence[str]], *, draws: int, seed_material: Mapping[str, Any]
) -> dict[str, Any]:
    seed_digest = _sha256(seed_material)
    rng = random.Random(int(seed_digest, 16))
    samples: list[float] = []
    for _ in range(draws):
        sampled = [components[rng.randrange(len(components))] for _ in components]
        denominator = sum(len(component) for component in sampled)
        duplicate_members = sum(len(component) for component in sampled if len(component) >= 2)
        samples.append(duplicate_members / denominator if denominator else 0.0)
    samples.sort()
    return {
        "method": "seeded-component-percentile-bootstrap-v1",
        "unit": "single-linkage-component-including-singletons",
        "draws": draws,
        "seed_material_sha256": seed_digest,
        "central_percentile_mass": 0.95,
        "low": _percentile(samples, 0.025),
        "high": _percentile(samples, 0.975),
        "interpretation": "component-resampling stability, not population uncertainty",
    }


def _size_distribution(components: Sequence[Sequence[str]]) -> dict[str, Any]:
    sizes = sorted(len(component) for component in components)
    duplicate_documents = sum(size for size in sizes if size >= 2)
    denominator = sum(sizes)
    histogram = Counter(sizes)
    return {
        "eligible_documents": denominator,
        "duplicate_documents": duplicate_documents,
        "prevalence": duplicate_documents / denominator if denominator else None,
        "component_count": len(sizes),
        "duplicate_component_count": sum(count for size, count in histogram.items() if size >= 2),
        "singleton_count": histogram.get(1, 0),
        "largest_component": max(sizes, default=0),
        "size_histogram": {str(size): histogram[size] for size in sorted(histogram)},
        "size_percentiles": {
            "p50": _percentile(sizes, 0.50) if sizes else None,
            "p95": _percentile(sizes, 0.95) if sizes else None,
        },
    }


def _exact_result(
    digest_by_id: Mapping[str, str], *, draws: int, seed_material: Mapping[str, Any]
) -> dict[str, Any]:
    by_digest: dict[str, list[str]] = defaultdict(list)
    for opaque_id, digest in sorted(digest_by_id.items()):
        by_digest[digest].append(opaque_id)
    components = tuple(tuple(members) for _, members in sorted(by_digest.items()))
    result = _size_distribution(components)
    result["duplicate_groups"] = [
        {"digest": digest, "size": len(members)}
        for digest, members in sorted(by_digest.items())
        if len(members) >= 2
    ]
    result["stability_interval"] = (
        _component_interval(components, draws=draws, seed_material=seed_material)
        if components
        else None
    )
    return result


def _weighted_percentile(histogram: Mapping[int, int], probability: float) -> float | None:
    total = sum(histogram.values())
    if total == 0:
        return None

    def value_at(index: int) -> int:
        remaining = index
        for value, count in sorted(histogram.items()):
            if remaining < count:
                return value
            remaining -= count
        raise AssertionError("weighted percentile index exceeded population")

    position = probability * (total - 1)
    lower = math.floor(position)
    upper = math.ceil(position)
    if lower == upper:
        return float(value_at(lower))
    fraction = position - lower
    return value_at(lower) * (1 - fraction) + value_at(upper) * fraction


def _digest_counter_commitment(counter: Mapping[str, int], *, purpose: str) -> str:
    _require_utf8(purpose, "digest-counter purpose")
    hasher = hashlib.sha256()
    hasher.update(b"jseval.digest-counts.v1\0")
    hasher.update(purpose.encode("utf-8"))
    hasher.update(b"\0")
    for digest in sorted(counter):
        count = counter[digest]
        checked_digest = _require_sha256(digest, f"{purpose}.digest")
        if isinstance(count, bool) or not isinstance(count, int) or count <= 0:
            raise DuplicatePrevalenceError(f"{purpose} counts must be positive integers")
        hasher.update(checked_digest.encode("ascii"))
        hasher.update(b"\0")
        hasher.update(str(count).encode("ascii"))
        hasher.update(b"\n")
    return hasher.hexdigest()


def _compact_exact_census_from_histogram(sizes: Mapping[int, int]) -> dict[str, Any]:
    eligible_documents = sum(size * component_count for size, component_count in sizes.items())
    component_count = sum(sizes.values())
    duplicate_documents = sum(
        size * count for size, count in sizes.items() if size >= 2
    )
    return {
        "eligible_documents": eligible_documents,
        "duplicate_documents": duplicate_documents,
        "prevalence": (
            duplicate_documents / eligible_documents if eligible_documents else None
        ),
        "component_count": component_count,
        "duplicate_component_count": sum(
            count for size, count in sizes.items() if size >= 2
        ),
        "singleton_count": sizes.get(1, 0),
        "largest_component": max(sizes, default=0),
        "size_histogram": {str(size): sizes[size] for size in sorted(sizes)},
        "size_percentiles": {
            "p50": _weighted_percentile(sizes, 0.50),
            "p95": _weighted_percentile(sizes, 0.95),
        },
    }


def _compact_exact_census(counter: Mapping[str, int]) -> dict[str, Any]:
    return _compact_exact_census_from_histogram(Counter(counter.values()))


def _validate_compact_exact_census(value: object, *, expected_count: int, label: str) -> dict[str, Any]:
    fields = {
        "eligible_documents",
        "duplicate_documents",
        "prevalence",
        "component_count",
        "duplicate_component_count",
        "singleton_count",
        "largest_component",
        "size_histogram",
        "size_percentiles",
    }
    if not isinstance(value, Mapping) or set(value) != fields:
        raise DuplicatePrevalenceError(f"{label} is incomplete")
    integer_fields = (
        "eligible_documents",
        "duplicate_documents",
        "component_count",
        "duplicate_component_count",
        "singleton_count",
        "largest_component",
    )
    if any(
        isinstance(value[field], bool)
        or not isinstance(value[field], int)
        or value[field] < 0
        for field in integer_fields
    ):
        raise DuplicatePrevalenceError(f"{label} counts must be non-negative integers")
    prevalence_value = value["prevalence"]
    if prevalence_value is not None and (
        isinstance(prevalence_value, bool)
        or not isinstance(prevalence_value, (int, float))
        or not 0 <= prevalence_value <= 1
    ):
        raise DuplicatePrevalenceError(f"{label}.prevalence must be null or a probability")
    percentiles = value["size_percentiles"]
    if not isinstance(percentiles, Mapping) or set(percentiles) != {"p50", "p95"}:
        raise DuplicatePrevalenceError(f"{label}.size_percentiles is incomplete")
    if any(
        percentile is not None
        and (
            isinstance(percentile, bool)
            or not isinstance(percentile, (int, float))
            or percentile < 0
        )
        for percentile in percentiles.values()
    ):
        raise DuplicatePrevalenceError(f"{label}.size_percentiles must be null or non-negative")
    histogram = value["size_histogram"]
    if not isinstance(histogram, Mapping):
        raise DuplicatePrevalenceError(f"{label}.size_histogram must be a mapping")
    parsed: dict[int, int] = {}
    for raw_size, count in histogram.items():
        if not isinstance(raw_size, str) or not raw_size.isdigit() or raw_size.startswith("0"):
            raise DuplicatePrevalenceError(f"{label}.size_histogram keys must be positive integers")
        size = int(raw_size)
        if isinstance(count, bool) or not isinstance(count, int) or count <= 0:
            raise DuplicatePrevalenceError(f"{label}.size_histogram counts must be positive integers")
        parsed[size] = count
    expected = _compact_exact_census_from_histogram(parsed)
    if expected["eligible_documents"] != expected_count or dict(value) != expected:
        raise DuplicatePrevalenceError(f"{label} does not match its exact size histogram")
    return expected


def _length_bin(token_count: int) -> str:
    return "0" if token_count == 0 else str(token_count.bit_length() - 1)


def _exhaustive_slice(
    ids: Sequence[str],
    *,
    formats: Mapping[str, str | None],
    token_counts: Mapping[str, int],
    content_group_sizes: Mapping[str, int],
    content_digests: Mapping[str, str],
    limit: int,
    seed_material: Mapping[str, Any],
) -> tuple[str, ...]:
    strata: dict[tuple[str, str, bool], list[str]] = defaultdict(list)
    seed_digest = _sha256(seed_material)
    for opaque_id in ids:
        stratum = (
            formats[opaque_id] or "unknown",
            _length_bin(token_counts[opaque_id]),
            content_group_sizes[content_digests[opaque_id]] >= 2,
        )
        strata[stratum].append(opaque_id)
    for stratum in strata:
        strata[stratum].sort(key=lambda item: (_sha256([seed_digest, item]), item))
    ordered_strata = sorted(strata, key=lambda item: (_sha256([seed_digest, list(item)]), item))
    selected: list[str] = []
    offsets = {stratum: 0 for stratum in ordered_strata}
    target = min(limit, len(ids))
    while len(selected) < target:
        progressed = False
        for stratum in ordered_strata:
            offset = offsets[stratum]
            if offset < len(strata[stratum]) and len(selected) < target:
                selected.append(strata[stratum][offset])
                offsets[stratum] += 1
                progressed = True
        if not progressed:
            break
    return tuple(sorted(selected))


def select_exhaustive_slice(
    ids: Sequence[str],
    *,
    formats: Mapping[str, str | None],
    token_counts: Mapping[str, int],
    content_group_sizes: Mapping[str, int],
    content_digests: Mapping[str, str],
    limit: int,
    seed_material: Mapping[str, Any],
) -> tuple[str, ...]:
    """Select the analyzer's deterministic format/length/exact-status slice."""

    return _exhaustive_slice(
        ids,
        formats=formats,
        token_counts=token_counts,
        content_group_sizes=content_group_sizes,
        content_digests=content_digests,
        limit=limit,
        seed_material=seed_material,
    )


def analyze(
    observations: Sequence[DocumentObservation],
    *,
    corpus_identity: Mapping[str, Any],
    extraction_identity: Mapping[str, Any] | None = None,
    source_projection_identity: Mapping[str, Any] | None = None,
    config: AnalysisConfig = AnalysisConfig(),
) -> dict[str, Any]:
    """Analyze duplicate prevalence and return a deterministic aggregate artifact."""

    _validate_config(config)
    ordered = _validate_observations(observations)
    source_kind = ordered[0].source_kind if ordered else SOURCE_BODY_PROXY
    validated_corpus = _validate_corpus_identity(
        corpus_identity,
        len(ordered),
        require_count_match=source_kind == PRODUCTION_EXTRACTED,
    )
    observations_digest = _observation_commitment(ordered)
    validated_extraction = _validate_extraction_identity(
        extraction_identity,
        source_kind=source_kind,
        corpus_signature=validated_corpus["signature"],
        observations_digest=observations_digest,
        corpus_count=len(ordered),
    )
    validated_projection = _validate_source_projection_identity(
        source_projection_identity,
        source_kind=source_kind,
        corpus_signature=validated_corpus["signature"],
        observations_digest=observations_digest,
        observations=ordered,
    )
    sampled_source_projection = (
        validated_projection is not None
        and validated_projection["schema"] == SAMPLED_SOURCE_PROJECTION_SCHEMA
    )

    successful = tuple(item for item in ordered if item.extraction_status == "success")
    disposition_denominators: dict[str, Any] = {}
    if (
        validated_extraction is not None
        and validated_extraction["schema"] == EXTRACTION_SNAPSHOT_SCHEMA_V2
    ):
        reconciliation = validated_extraction["reconciliation"]
        if len(successful) != reconciliation["indexed_count"]:
            raise DuplicatePrevalenceError(
                "production observation dispositions do not match indexed/excluded reconciliation"
            )
        disposition_denominators = {
            "extraction_partial_successes": reconciliation["partial_success_count"],
            "terminal_excluded_documents": reconciliation["terminal_excluded_count"],
            "terminal_exclusion_reasons": reconciliation["terminal_exclusion_reasons"],
        }
    raw_digest_by_id = {item.opaque_id: item.raw_sha256 for item in ordered}
    normalized = {item.opaque_id: normalize_content(item.extracted_text or "") for item in successful}
    content_digest_by_id = {
        opaque_id: hashlib.sha256(text.encode("utf-8")).hexdigest()
        for opaque_id, text in normalized.items()
        if text
    }
    shingles = {
        item.opaque_id: token_shingles(item.extracted_text or "", config.shingle_width)
        for item in successful
    }
    analyzable_ids = tuple(sorted(opaque_id for opaque_id, value in shingles.items() if value))
    empty_count = len(successful) - len(analyzable_ids)
    fingerprints = {opaque_id: simhash64(shingles[opaque_id]) for opaque_id in analyzable_ids}
    candidates = simhash_candidate_pairs(
        fingerprints,
        bits=config.simhash_bits,
        max_hamming=config.max_hamming,
        max_candidate_pairs=config.max_candidate_pairs,
    )
    candidate_jaccard = {
        pair: jaccard_similarity(shingles[pair[0]], shingles[pair[1]])
        for pair in sorted(candidates)
    }

    content_counts = Counter(content_digest_by_id.values())
    formats = {item.opaque_id: item.format_id for item in successful}
    token_counts = {
        item.opaque_id: len(tokenize_near_duplicate(item.extracted_text or ""))
        for item in successful
    }
    slice_ids = _exhaustive_slice(
        analyzable_ids,
        formats=formats,
        token_counts=token_counts,
        content_group_sizes=content_counts,
        content_digests=content_digest_by_id,
        limit=config.exhaustive_slice_size,
        seed_material={
            "purpose": "exhaustive-slice-v1",
            "corpus_signature": validated_corpus["signature"],
            "seed": config.seed,
        },
    )
    exhaustive_pairs: dict[tuple[str, str], float] = {}
    for left_index, left in enumerate(slice_ids):
        for right in slice_ids[left_index + 1 :]:
            exhaustive_pairs[(left, right)] = jaccard_similarity(shingles[left], shingles[right])

    threshold_sweep: list[dict[str, Any]] = []
    recall_sweep: list[dict[str, Any]] = []
    for threshold in config.jaccard_thresholds:
        confirmed = tuple(
            pair for pair, similarity in sorted(candidate_jaccard.items()) if similarity >= threshold
        )
        components = _components(analyzable_ids, confirmed)
        distribution = _size_distribution(components)
        distribution.update(
            {
                "threshold": threshold,
                "confirmed_edge_count": len(confirmed),
                "stability_interval": (
                    _component_interval(
                        components,
                        draws=config.bootstrap_draws,
                        seed_material={
                            "purpose": "near-component-bootstrap-v1",
                            "corpus_signature": validated_corpus["signature"],
                            "seed": config.seed,
                            "threshold": threshold,
                        },
                    )
                    if components
                    else None
                ),
            }
        )
        threshold_sweep.append(distribution)

        positives = {pair for pair, similarity in exhaustive_pairs.items() if similarity >= threshold}
        captured = positives & candidates
        recall_sweep.append(
            {
                "threshold": threshold,
                "positive_pair_count": len(positives),
                "captured_pair_count": len(captured),
                "missed_pair_count": len(positives - candidates),
                "candidate_recall": len(captured) / len(positives) if positives else None,
                "status": "measured" if positives else "undefined-no-positive-pairs",
            }
        )

    config_payload = {
        "shingle_width": config.shingle_width,
        "simhash_bits": config.simhash_bits,
        "max_hamming": config.max_hamming,
        "bands": config.max_hamming + 1,
        "jaccard_thresholds": list(config.jaccard_thresholds),
        "exhaustive_slice_size": config.exhaustive_slice_size,
        "bootstrap_draws": config.bootstrap_draws,
        "seed": config.seed,
        "max_candidate_pairs": config.max_candidate_pairs,
    }
    artifact: dict[str, Any] = {
        "schema": SCHEMA,
        "input": {
            "source_kind": source_kind,
            "content_interpretation": (
                "production-extracted-content" if source_kind == PRODUCTION_EXTRACTED else "source-body-proxy"
            ),
            "corpus_identity": validated_corpus,
            "observations_digest": observations_digest,
            "extraction_identity": validated_extraction,
            "source_projection_identity": validated_projection,
        },
        "algorithm": {
            "id": ALGORITHM,
            **({"scope": ENRON_SAMPLE_SCOPE} if sampled_source_projection else {}),
            "content_normalization": "unicode-nfc-line-endings-unicode-whitespace-v1",
            "near_tokenizer": "unicode-nfc-casefold-alphanumeric-v1",
            "short_document_rule": "one-tagged-whole-token-sequence-shingle",
            "clustering": "single-linkage-connected-components",
            "config": config_payload,
        },
        "denominators": {
            **({"scope": ENRON_SAMPLE_SCOPE} if sampled_source_projection else {}),
            "manifest_files": validated_corpus["file_count"],
            "source_observations": len(ordered),
            "extraction_successes": len(successful),
            "extraction_failures": len(ordered) - len(successful),
            **disposition_denominators,
            "content_exact_analyzable_nonempty_documents": len(content_digest_by_id),
            "content_exact_excluded_empty_documents": len(successful) - len(content_digest_by_id),
            "near_analyzable_nonempty_documents": len(analyzable_ids),
            "near_excluded_empty_documents": empty_count,
        },
        "byte_exact": {
            **({"scope": ENRON_SAMPLE_SCOPE} if sampled_source_projection else {}),
            "interpretation": (
                "manifest-document-bytes"
                if source_kind == PRODUCTION_EXTRACTED
                else "source-body-bytes-proxy"
            ),
            **_exact_result(
                raw_digest_by_id,
                draws=config.bootstrap_draws,
                seed_material={
                    "purpose": "byte-exact-bootstrap-v1",
                    "corpus_signature": validated_corpus["signature"],
                    "seed": config.seed,
                },
            ),
        },
        "content_exact": {
            **({"scope": ENRON_SAMPLE_SCOPE} if sampled_source_projection else {}),
            "interpretation": (
                "production-extracted-content" if source_kind == PRODUCTION_EXTRACTED else "source-body-proxy"
            ),
            **_exact_result(
                content_digest_by_id,
                draws=config.bootstrap_draws,
                seed_material={
                    "purpose": "content-exact-bootstrap-v1",
                    "corpus_signature": validated_corpus["signature"],
                    "seed": config.seed,
                },
            ),
        },
        "near_duplicate": {
            **({"scope": ENRON_SAMPLE_SCOPE} if sampled_source_projection else {}),
            "decision": {
                "status": "UNDECIDED",
                "selected_threshold": None,
                "reason": "calibration and disjoint holdout review are outside this computational slice",
            },
            "candidate_generation": {
                "method": "fixed-64-bit-simhash-disjoint-bands-v1",
                "candidate_pair_count": len(candidates),
                "banding_guarantee": (
                    f"all pairs with SimHash Hamming distance <= {config.max_hamming}; "
                    "no deterministic Jaccard-recall guarantee"
                ),
                "truncation": "none-fail-if-limit-exceeded",
            },
            "exhaustive_slice": {
                "selection": "deterministic-format-length-exact-status-round-robin-v1",
                "document_count": len(slice_ids),
                "pair_count": len(exhaustive_pairs),
                "candidate_recall_by_threshold": recall_sweep,
            },
            "threshold_sweep": threshold_sweep,
        },
        "privacy": {
            "mode": "aggregate-only",
            "document_ids_emitted": False,
            "paths_emitted": False,
            "text_emitted": False,
        },
    }
    artifact["artifact_hash"] = _sha256(artifact)
    return artifact


def validate_artifact_hash(artifact: Mapping[str, Any]) -> dict[str, Any]:
    """Validate the closed root and self-consistent hash of a v1 artifact.

    Consumers with the private observations should prefer re-running
    :func:`analyze` and comparing the complete result. This lighter check is
    for archived aggregate-only consumers that cannot reconstruct private
    observations but still need to reject truncation or mutation.
    """
    if not isinstance(artifact, Mapping):
        raise DuplicatePrevalenceError("duplicate-prevalence artifact must be a mapping")
    required = {
        "schema", "input", "algorithm", "denominators", "byte_exact",
        "content_exact", "near_duplicate", "privacy", "artifact_hash",
    }
    if set(artifact) != required:
        raise DuplicatePrevalenceError("duplicate-prevalence artifact root is incomplete")
    if artifact["schema"] != SCHEMA:
        raise DuplicatePrevalenceError("duplicate-prevalence artifact schema is unsupported")
    digest = _require_sha256(artifact["artifact_hash"], "artifact_hash")
    unsigned = {key: artifact[key] for key in artifact if key != "artifact_hash"}
    if _sha256(unsigned) != digest:
        raise DuplicatePrevalenceError("duplicate-prevalence artifact hash does not match content")
    return dict(artifact)


__all__ = [
    "ALGORITHM",
    "AnalysisConfig",
    "DocumentObservation",
    "DuplicatePrevalenceError",
    "ENRON_PROJECTION_PRODUCER",
    "ENRON_SAMPLE_SCOPE",
    "ENRON_POPULATION_CENSUS_SCHEMA",
    "EXTRACTION_SNAPSHOT_SCHEMA",
    "EXTRACTION_SNAPSHOT_SCHEMA_V2",
    "OBSERVATION_COMMITMENT_SCHEMA",
    "PRODUCTION_EXTRACTED",
    "SCHEMA",
    "SOURCE_BODY_PROXY",
    "SOURCE_PROJECTION_SCHEMA",
    "SAMPLED_SOURCE_PROJECTION_SCHEMA",
    "analyze",
    "build_enron_source_projection_identity",
    "build_enron_sampled_source_projection_identity",
    "connected_components",
    "hamming_distance",
    "jaccard_similarity",
    "normalize_content",
    "observation_commitment",
    "select_exhaustive_slice",
    "simhash64",
    "simhash_candidate_pairs",
    "validate_artifact_hash",
    "token_shingles",
    "tokenize_near_duplicate",
    "validate_config",
    "validate_extraction_identity",
    "validate_source_projection_identity",
]
