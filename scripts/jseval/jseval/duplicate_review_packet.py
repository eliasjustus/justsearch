"""Deterministic, sensitive local review packets for duplicate calibration.

This module does not produce aggregate evidence or select a threshold.  It
materializes sampled document pairs, including extracted text, solely for
human labeling in uncommitted local storage.
"""

from __future__ import annotations

import hashlib
import json
import math
import re
from collections import Counter, defaultdict
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Mapping, Sequence

from . import duplicate_prevalence as analyzer
from ._paths import REPO_ROOT


SCHEMA = "jseval.duplicate-review-packet.v1"
LOCAL_REVIEW_ROOT = REPO_ROOT / "scripts" / "jseval" / "tmp"
_SHA256_RE = re.compile(r"[0-9a-f]{64}\Z")
_ARTIFACT_FIELDS = frozenset(
    {
        "schema",
        "input",
        "algorithm",
        "denominators",
        "byte_exact",
        "content_exact",
        "near_duplicate",
        "privacy",
        "artifact_hash",
    }
)
_ANALYZER_CONFIG_FIELDS = frozenset(
    {
        "shingle_width",
        "simhash_bits",
        "max_hamming",
        "bands",
        "jaccard_thresholds",
        "exhaustive_slice_size",
        "bootstrap_draws",
        "seed",
        "max_candidate_pairs",
    }
)
_ANALYZER_INPUT_FIELDS = frozenset(
    {
        "source_kind",
        "content_interpretation",
        "corpus_identity",
        "observations_digest",
        "extraction_identity",
        "source_projection_identity",
    }
)
_CORPUS_IDENTITY_FIELDS = frozenset(
    {
        "kind",
        "profile_id",
        "schema",
        "signature",
        "file_count",
        "total_bytes",
        "admission_policy_digest",
    }
)


class DuplicateReviewPacketError(ValueError):
    """Review inputs are inconsistent, unsafe, or not analyzable."""


def validate_review_packet_destination(path: Path | str) -> Path:
    """Resolve a text-bearing packet only inside jseval's known gitignored tmp root."""

    destination = Path(path).resolve(strict=False)
    private_root = LOCAL_REVIEW_ROOT.resolve(strict=False)
    if destination == private_root or not destination.is_relative_to(private_root):
        raise DuplicateReviewPacketError(
            f"review packet destination must be a file under private gitignored root {private_root}"
        )
    return destination


@dataclass(frozen=True)
class ReviewPacketConfig:
    """Pre-registered analyzer and review-sampling parameters."""

    analysis_config: analyzer.AnalysisConfig
    per_stratum_quota: int
    calibration_fraction: float = 0.5
    seed: int = 0


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
        raise DuplicateReviewPacketError("review inputs must be canonical UTF-8 JSON values") from exc


def _digest(value: object) -> str:
    return hashlib.sha256(_canonical_bytes(value)).hexdigest()


def _validate_review_config(config: ReviewPacketConfig) -> None:
    if not isinstance(config, ReviewPacketConfig):
        raise DuplicateReviewPacketError("config must be a ReviewPacketConfig")
    if not isinstance(config.analysis_config, analyzer.AnalysisConfig):
        raise DuplicateReviewPacketError("analysis_config must be an AnalysisConfig")
    try:
        analyzer.validate_config(config.analysis_config)
    except analyzer.DuplicatePrevalenceError as exc:
        raise DuplicateReviewPacketError(f"invalid analysis config: {exc}") from exc
    if config.analysis_config.exhaustive_slice_size < 2:
        raise DuplicateReviewPacketError(
            "review analysis_config.exhaustive_slice_size must be at least 2"
        )
    if (
        isinstance(config.per_stratum_quota, bool)
        or not isinstance(config.per_stratum_quota, int)
        or config.per_stratum_quota < 1
    ):
        raise DuplicateReviewPacketError("per_stratum_quota must be a positive integer")
    if (
        isinstance(config.calibration_fraction, bool)
        or not isinstance(config.calibration_fraction, (int, float))
        or not math.isfinite(config.calibration_fraction)
        or not 0 < config.calibration_fraction < 1
    ):
        raise DuplicateReviewPacketError("calibration_fraction must be finite and in (0, 1)")
    if isinstance(config.seed, bool) or not isinstance(config.seed, int):
        raise DuplicateReviewPacketError("seed must be an integer")


def _analysis_config_payload(config: analyzer.AnalysisConfig) -> dict[str, Any]:
    return {
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


def _validate_artifact(
    artifact: Mapping[str, Any],
    *,
    observations: Sequence[analyzer.DocumentObservation],
    observations_digest: str,
    source_kind: str,
    analysis_config: analyzer.AnalysisConfig,
) -> dict[str, Any]:
    if not isinstance(artifact, Mapping) or set(artifact) != _ARTIFACT_FIELDS:
        raise DuplicateReviewPacketError("analyzer artifact has an unexpected top-level shape")
    if artifact["schema"] != analyzer.SCHEMA:
        raise DuplicateReviewPacketError("analyzer artifact schema is unsupported")
    artifact_hash = artifact["artifact_hash"]
    if not isinstance(artifact_hash, str) or _SHA256_RE.fullmatch(artifact_hash) is None:
        raise DuplicateReviewPacketError("analyzer artifact_hash must be a lowercase SHA-256")

    inputs = artifact["input"]
    if not isinstance(inputs, Mapping) or set(inputs) != _ANALYZER_INPUT_FIELDS:
        raise DuplicateReviewPacketError("analyzer artifact input is malformed")
    if inputs.get("observations_digest") != observations_digest:
        raise DuplicateReviewPacketError("artifact/observation commitment mismatch")
    if inputs.get("source_kind") != source_kind:
        raise DuplicateReviewPacketError("artifact/observation source-kind mismatch")
    corpus_identity = inputs.get("corpus_identity")
    if not isinstance(corpus_identity, Mapping) or set(corpus_identity) != _CORPUS_IDENTITY_FIELDS:
        raise DuplicateReviewPacketError("analyzer corpus identity is malformed")
    signature = corpus_identity.get("signature")
    if not isinstance(signature, str) or _SHA256_RE.fullmatch(signature) is None:
        raise DuplicateReviewPacketError("analyzer corpus signature is malformed")

    algorithm = artifact["algorithm"]
    expected_algorithm_fields = {
        "id",
        "content_normalization",
        "near_tokenizer",
        "short_document_rule",
        "clustering",
        "config",
    }
    source_projection = inputs.get("source_projection_identity")
    sampled_source_projection = (
        source_kind == analyzer.SOURCE_BODY_PROXY
        and isinstance(source_projection, Mapping)
        and source_projection.get("schema") == analyzer.SAMPLED_SOURCE_PROJECTION_SCHEMA
    )
    if sampled_source_projection:
        expected_algorithm_fields.add("scope")
    if not isinstance(algorithm, Mapping) or set(algorithm) != expected_algorithm_fields:
        raise DuplicateReviewPacketError("analyzer algorithm provenance is malformed")
    if (
        algorithm["id"] != analyzer.ALGORITHM
        or algorithm["content_normalization"]
        != "unicode-nfc-line-endings-unicode-whitespace-v1"
        or algorithm["near_tokenizer"] != "unicode-nfc-casefold-alphanumeric-v1"
        or algorithm["short_document_rule"]
        != "one-tagged-whole-token-sequence-shingle"
        or algorithm["clustering"] != "single-linkage-connected-components"
    ):
        raise DuplicateReviewPacketError("analyzer algorithm provenance is unsupported")
    if sampled_source_projection and algorithm.get("scope") != analyzer.ENRON_SAMPLE_SCOPE:
        raise DuplicateReviewPacketError("analyzer sample scope is unsupported")
    artifact_config = algorithm["config"]
    if not isinstance(artifact_config, Mapping) or set(artifact_config) != _ANALYZER_CONFIG_FIELDS:
        raise DuplicateReviewPacketError("analyzer config is malformed")
    if dict(artifact_config) != _analysis_config_payload(analysis_config):
        raise DuplicateReviewPacketError("review/analyzer config mismatch")

    near = artifact["near_duplicate"]
    if not isinstance(near, Mapping):
        raise DuplicateReviewPacketError("analyzer near-duplicate result is malformed")
    decision = near.get("decision")
    if (
        not isinstance(decision, Mapping)
        or decision.get("status") != "UNDECIDED"
        or decision.get("selected_threshold") is not None
    ):
        raise DuplicateReviewPacketError("review packets require an UNDECIDED analyzer artifact")
    candidate_generation = near.get("candidate_generation")
    if (
        not isinstance(candidate_generation, Mapping)
        or candidate_generation.get("method")
        != "fixed-64-bit-simhash-disjoint-bands-v1"
    ):
        raise DuplicateReviewPacketError("analyzer fingerprint provenance is unsupported")
    for section_name in ("denominators", "byte_exact", "content_exact", "near_duplicate"):
        section = artifact.get(section_name)
        if not isinstance(section, Mapping):
            raise DuplicateReviewPacketError(f"analyzer {section_name} result is malformed")
        if sampled_source_projection:
            if section.get("scope") != analyzer.ENRON_SAMPLE_SCOPE:
                raise DuplicateReviewPacketError("analyzer sample scope is unsupported")
        elif "scope" in section:
            raise DuplicateReviewPacketError("sample scope is forbidden for this analyzer artifact")

    measurement_identity: Mapping[str, Any] | None
    try:
        if source_kind == analyzer.SOURCE_BODY_PROXY:
            measurement_identity = inputs.get("source_projection_identity")
            if not isinstance(measurement_identity, Mapping) or inputs.get("extraction_identity") is not None:
                raise DuplicateReviewPacketError("source-body proxy lacks its projection identity")
            validated_identity = analyzer.validate_source_projection_identity(
                measurement_identity, observations, corpus_signature=signature
            )
        else:
            measurement_identity = inputs.get("extraction_identity")
            if not isinstance(measurement_identity, Mapping) or inputs.get("source_projection_identity") is not None:
                raise DuplicateReviewPacketError("production input lacks its extraction identity")
            validated_identity = analyzer.validate_extraction_identity(
                measurement_identity, observations, corpus_signature=signature
            )
    except analyzer.DuplicatePrevalenceError as exc:
        raise DuplicateReviewPacketError(f"invalid measurement identity: {exc}") from exc
    if dict(measurement_identity) != validated_identity:
        raise DuplicateReviewPacketError("measurement identity is not canonical")
    measurement_digest = measurement_identity.get("digest")
    measurement_schema = measurement_identity.get("schema")
    if not isinstance(measurement_digest, str) or _SHA256_RE.fullmatch(measurement_digest) is None:
        raise DuplicateReviewPacketError("measurement identity digest is malformed")
    if not isinstance(measurement_schema, str) or not measurement_schema:
        raise DuplicateReviewPacketError("measurement identity schema is malformed")

    unhashed = dict(artifact)
    unhashed.pop("artifact_hash")
    if _digest(unhashed) != artifact_hash:
        raise DuplicateReviewPacketError("analyzer artifact hash mismatch")
    return {
        "analyzer_artifact_hash": artifact_hash,
        "source_kind": source_kind,
        "corpus_identity": dict(corpus_identity),
        "observations_digest": observations_digest,
        "measurement_identity": {
            "schema": measurement_schema,
            "digest": measurement_digest,
        },
        "corpus_signature": signature,
        "algorithm": dict(algorithm),
        "fingerprint": {
            "method": candidate_generation["method"],
            "banding_guarantee": candidate_generation.get("banding_guarantee"),
        },
    }


def _validate_observations(
    observations: Sequence[analyzer.DocumentObservation],
    analysis_config: analyzer.AnalysisConfig,
) -> tuple[tuple[analyzer.DocumentObservation, ...], dict[str, frozenset[tuple[str, ...]]]]:
    if isinstance(observations, (str, bytes)) or not isinstance(observations, Sequence):
        raise DuplicateReviewPacketError("observations must be a sequence")
    for observation in observations:
        if not isinstance(observation, analyzer.DocumentObservation):
            raise DuplicateReviewPacketError("every observation must be a DocumentObservation")
    try:
        observations_digest = analyzer.observation_commitment(observations)
    except analyzer.DuplicatePrevalenceError as exc:
        raise DuplicateReviewPacketError(str(exc)) from exc
    del observations_digest
    ordered = tuple(sorted(observations, key=lambda item: item.opaque_id))
    if not ordered:
        raise DuplicateReviewPacketError("review observations must not be empty")
    source_kinds = {item.source_kind for item in ordered}
    if len(source_kinds) != 1 or not source_kinds <= {
        analyzer.SOURCE_BODY_PROXY,
        analyzer.PRODUCTION_EXTRACTED,
    }:
        raise DuplicateReviewPacketError("review observations must use one supported source kind")
    shingles: dict[str, frozenset[tuple[str, ...]]] = {}
    for item in ordered:
        if _SHA256_RE.fullmatch(item.opaque_id) is None:
            raise DuplicateReviewPacketError("review opaque IDs must be privacy-safe SHA-256 values")
        if item.format_id is not None and (
            not isinstance(item.format_id, str)
            or not item.format_id
            or "\x00" in item.format_id
            or "\r" in item.format_id
            or "\n" in item.format_id
        ):
            raise DuplicateReviewPacketError("format IDs must be nonempty single-line text")
        if item.extraction_status != "success" or not isinstance(item.extracted_text, str):
            raise DuplicateReviewPacketError("review observations must all be successful extractions")
        if not analyzer.normalize_content(item.extracted_text):
            raise DuplicateReviewPacketError("review observations must have nonempty normalized text")
        item_shingles = analyzer.token_shingles(
            item.extracted_text, analysis_config.shingle_width
        )
        if not item_shingles:
            raise DuplicateReviewPacketError("review observations must have analyzable text tokens")
        shingles[item.opaque_id] = item_shingles
    return ordered, shingles


def _partition(
    ordered: Sequence[analyzer.DocumentObservation],
    *,
    candidate_edges: Sequence[tuple[str, str]],
    artifact_hash: str,
    fraction: float,
    seed: int,
) -> dict[str, tuple[str, ...]]:
    components = analyzer.connected_components(
        (item.opaque_id for item in ordered), candidate_edges
    )
    ranked = sorted(
        components,
        key=lambda component: (
            _digest(["review-family-split-v1", artifact_hash, seed, list(component)]),
            component,
        ),
    )
    total = len(ordered)
    target = total * fraction
    possible: list[tuple[float, int]] = []
    cumulative = 0
    for cutoff, component in enumerate(ranked[:-1], start=1):
        cumulative += len(component)
        if 2 <= cumulative <= total - 2:
            possible.append((abs(cumulative - target), cutoff))
    if not possible:
        raise DuplicateReviewPacketError(
            "at least two candidate-disjoint families and two documents per split are required"
        )
    _, cutoff = min(possible)
    return {
        "calibration": tuple(sorted(item for group in ranked[:cutoff] for item in group)),
        "holdout": tuple(sorted(item for group in ranked[cutoff:] for item in group)),
    }


def threshold_band(similarity: float, thresholds: Sequence[float]) -> str:
    """Return the deterministic threshold interval containing ``similarity``."""

    if similarity < thresholds[0]:
        return f"below-{json.dumps(thresholds[0])}"
    for lower, upper in zip(thresholds, thresholds[1:]):
        if similarity < upper:
            return f"at-or-above-{json.dumps(lower)}-below-{json.dumps(upper)}"
    return f"at-or-above-{json.dumps(thresholds[-1])}"


def _length_ratio_bin(left: int, right: int) -> int:
    smaller, larger = sorted((left, right))
    candidate = larger.bit_length() - smaller.bit_length()
    if larger < smaller << candidate:
        candidate -= 1
    return max(candidate, 0)


def _stratum(
    left: analyzer.DocumentObservation,
    right: analyzer.DocumentObservation,
    *,
    similarity: float,
    token_counts: Mapping[str, int],
    thresholds: Sequence[float],
) -> dict[str, Any]:
    return {
        "threshold_band": threshold_band(similarity, thresholds),
        "format_relation": (
            "same-format" if left.format_id == right.format_id else "cross-format"
        ),
        "log2_length_ratio_bin": _length_ratio_bin(
            token_counts[left.opaque_id], token_counts[right.opaque_id]
        ),
    }


def _pair_id(observations_digest: str, left: str, right: str) -> str:
    return _digest(["duplicate-review-pair-v1", observations_digest, left, right])


def _population_for_split(
    split: str,
    ids: Sequence[str],
    *,
    by_id: Mapping[str, analyzer.DocumentObservation],
    shingles: Mapping[str, frozenset[tuple[str, ...]]],
    fingerprints: Mapping[str, int],
    formats: Mapping[str, str | None],
    token_counts: Mapping[str, int],
    content_group_sizes: Mapping[str, int],
    content_digests: Mapping[str, str],
    corpus_signature: str,
    observations_digest: str,
    analysis_config: analyzer.AnalysisConfig,
) -> dict[str, list[dict[str, Any]]]:
    subset = analyzer.select_exhaustive_slice(
        ids,
        formats=formats,
        token_counts=token_counts,
        content_group_sizes=content_group_sizes,
        content_digests=content_digests,
        limit=analysis_config.exhaustive_slice_size,
        seed_material={
            "purpose": "exhaustive-slice-v1",
            "corpus_signature": corpus_signature,
            "seed": analysis_config.seed,
        },
    )
    subset_fingerprints = {opaque_id: fingerprints[opaque_id] for opaque_id in subset}
    candidates = analyzer.simhash_candidate_pairs(
        subset_fingerprints,
        bits=analysis_config.simhash_bits,
        max_hamming=analysis_config.max_hamming,
        max_candidate_pairs=analysis_config.max_candidate_pairs,
    )
    control_pairs = {
        (left, right)
        for index, left in enumerate(subset)
        for right in subset[index + 1 :]
        if (left, right) not in candidates
    }

    result: dict[str, list[dict[str, Any]]] = {
        "candidate": [],
        "exhaustive-control": [],
    }
    for frame, pairs in (("candidate", candidates), ("exhaustive-control", control_pairs)):
        for left_id, right_id in sorted(pairs):
            similarity = analyzer.jaccard_similarity(shingles[left_id], shingles[right_id])
            result[frame].append(
                {
                    "pair_id": _pair_id(observations_digest, left_id, right_id),
                    "split": split,
                    "sampling_frame": frame,
                    "stratum": _stratum(
                        by_id[left_id],
                        by_id[right_id],
                        similarity=similarity,
                        token_counts=token_counts,
                        thresholds=analysis_config.jaccard_thresholds,
                    ),
                    "similarity": similarity,
                    "left_id": left_id,
                    "right_id": right_id,
                }
            )
    return result


def _sample_populations(
    populations: Mapping[str, Mapping[str, Sequence[Mapping[str, Any]]]],
    *,
    by_id: Mapping[str, analyzer.DocumentObservation],
    token_counts: Mapping[str, int],
    artifact_hash: str,
    observations_digest: str,
    config: ReviewPacketConfig,
) -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
    records: list[dict[str, Any]] = []
    summaries: list[dict[str, Any]] = []
    for split in ("calibration", "holdout"):
        for frame in ("candidate", "exhaustive-control"):
            strata: dict[str, list[Mapping[str, Any]]] = defaultdict(list)
            stratum_values: dict[str, Mapping[str, Any]] = {}
            for pair in populations[split][frame]:
                key = _canonical_bytes(pair["stratum"]).decode("utf-8")
                strata[key].append(pair)
                stratum_values[key] = pair["stratum"]
            for key in sorted(strata):
                population = strata[key]
                ranked = sorted(
                    population,
                    key=lambda pair: (
                        _digest(
                            [
                                "duplicate-review-stratum-sample-v1",
                                artifact_hash,
                                config.seed,
                                split,
                                frame,
                                pair["pair_id"],
                            ]
                        ),
                        pair["pair_id"],
                    ),
                )
                selected = ranked[: config.per_stratum_quota]
                probability = len(selected) / len(population)
                weight = 1.0 / probability
                summaries.append(
                    {
                        "split": split,
                        "sampling_frame": frame,
                        "stratum": dict(stratum_values[key]),
                        "population_count": len(population),
                        "selected_count": len(selected),
                        "inclusion_probability": probability,
                    }
                )
                for pair in selected:
                    left = by_id[pair["left_id"]]
                    right = by_id[pair["right_id"]]
                    records.append(
                        {
                            "pair_id": pair["pair_id"],
                            "split": split,
                            "sampling_frame": frame,
                            "stratum": dict(pair["stratum"]),
                            "inclusion_probability": probability,
                            "sampling_weight": weight,
                            "similarity": pair["similarity"],
                            "left": {
                                "opaque_id": left.opaque_id,
                                "format_id": left.format_id,
                                "token_count": token_counts[left.opaque_id],
                                "text": left.extracted_text,
                            },
                            "right": {
                                "opaque_id": right.opaque_id,
                                "format_id": right.format_id,
                                "token_count": token_counts[right.opaque_id],
                                "text": right.extracted_text,
                            },
                            "label": None,
                            "labeler": None,
                            "notes": None,
                        }
                    )
    records.sort(key=lambda row: (row["split"], row["sampling_frame"], row["pair_id"]))
    summaries.sort(
        key=lambda row: (
            row["split"],
            row["sampling_frame"],
            _canonical_bytes(row["stratum"]),
        )
    )
    return records, summaries


def build_review_packet(
    observations: Sequence[analyzer.DocumentObservation],
    analyzer_artifact: Mapping[str, Any],
    *,
    config: ReviewPacketConfig,
) -> dict[str, Any]:
    """Build a deterministic text-bearing packet for local human review."""

    _validate_review_config(config)
    ordered, shingles = _validate_observations(observations, config.analysis_config)
    source_kind = ordered[0].source_kind
    observations_digest = analyzer.observation_commitment(ordered)
    provenance = _validate_artifact(
        analyzer_artifact,
        observations=ordered,
        observations_digest=observations_digest,
        source_kind=source_kind,
        analysis_config=config.analysis_config,
    )
    by_id = {item.opaque_id: item for item in ordered}
    token_counts = {
        item.opaque_id: len(analyzer.tokenize_near_duplicate(item.extracted_text or ""))
        for item in ordered
    }
    formats = {item.opaque_id: item.format_id for item in ordered}
    content_digests = {
        item.opaque_id: hashlib.sha256(
            analyzer.normalize_content(item.extracted_text or "").encode("utf-8")
        ).hexdigest()
        for item in ordered
    }
    content_group_sizes = Counter(content_digests.values())
    fingerprints = {
        opaque_id: analyzer.simhash64(item_shingles)
        for opaque_id, item_shingles in shingles.items()
    }
    global_candidates = analyzer.simhash_candidate_pairs(
        fingerprints,
        bits=config.analysis_config.simhash_bits,
        max_hamming=config.analysis_config.max_hamming,
        max_candidate_pairs=config.analysis_config.max_candidate_pairs,
    )
    partition = _partition(
        ordered,
        candidate_edges=tuple(global_candidates),
        artifact_hash=provenance["analyzer_artifact_hash"],
        fraction=config.calibration_fraction,
        seed=config.seed,
    )
    populations = {
        split: _population_for_split(
            split,
            ids,
            by_id=by_id,
            shingles=shingles,
            fingerprints=fingerprints,
            formats=formats,
            token_counts=token_counts,
            content_group_sizes=content_group_sizes,
            content_digests=content_digests,
            corpus_signature=provenance["corpus_signature"],
            observations_digest=observations_digest,
            analysis_config=config.analysis_config,
        )
        for split, ids in partition.items()
    }
    records, summaries = _sample_populations(
        populations,
        by_id=by_id,
        token_counts=token_counts,
        artifact_hash=provenance["analyzer_artifact_hash"],
        observations_digest=observations_digest,
        config=config,
    )

    safe_provenance = {key: value for key, value in provenance.items() if key != "corpus_signature"}
    frame_totals = []
    for split in ("calibration", "holdout"):
        for frame in ("candidate", "exhaustive-control"):
            matching = [
                summary
                for summary in summaries
                if summary["split"] == split and summary["sampling_frame"] == frame
            ]
            frame_totals.append(
                {
                    "split": split,
                    "sampling_frame": frame,
                    "population_count": sum(item["population_count"] for item in matching),
                    "selected_count": sum(item["selected_count"] for item in matching),
                }
            )
    packet: dict[str, Any] = {
        "schema": SCHEMA,
        "artifact_kind": "sensitive-local-review-packet",
        "sensitivity": "local-review-text",
        "intended_persistence": "uncommitted-local-only",
        "source": safe_provenance,
        "review_config": {
            "per_stratum_quota": config.per_stratum_quota,
            "calibration_fraction": config.calibration_fraction,
            "seed": config.seed,
        },
        "partition": {
            "method": "pre-pair-candidate-family-disjoint-split-v1",
            "calibration": {
                "document_count": len(partition["calibration"]),
                "opaque_ids": list(partition["calibration"]),
            },
            "holdout": {
                "document_count": len(partition["holdout"]),
                "opaque_ids": list(partition["holdout"]),
            },
            "cross_split_pairs_excluded": True,
        },
        "sampling": {
            "target_universe": "all-pairs-within-each-split-deterministic-exhaustive-subset",
            "candidate_population": (
                "simhash-candidates-within-per-split-deterministic-exhaustive-subset"
            ),
            "control_population": (
                "noncandidate-all-pairs-within-per-split-deterministic-exhaustive-subset"
            ),
            "frames": frame_totals,
            "strata": summaries,
        },
        "records": records,
        "label_status": "UNLABELED",
        "near_duplicate_decision": {
            "status": "UNDECIDED",
            "selected_threshold": None,
        },
    }
    packet["packet_hash"] = _digest(packet)
    return packet


__all__ = [
    "LOCAL_REVIEW_ROOT",
    "SCHEMA",
    "DuplicateReviewPacketError",
    "ReviewPacketConfig",
    "build_review_packet",
    "threshold_band",
    "validate_review_packet_destination",
]
