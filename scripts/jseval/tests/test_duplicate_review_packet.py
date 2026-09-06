from __future__ import annotations

import hashlib
import json

import pytest

from jseval import duplicate_prevalence as dp
from jseval import duplicate_review_packet as review


def _digest(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def _analysis_config(**overrides) -> dp.AnalysisConfig:
    values = {
        "shingle_width": 1,
        "simhash_bits": 64,
        "max_hamming": 0,
        "jaccard_thresholds": (0.5, 0.8),
        "exhaustive_slice_size": 100,
        "bootstrap_draws": 50,
        "seed": 13,
        "max_candidate_pairs": 10_000,
    }
    values.update(overrides)
    return dp.AnalysisConfig(**values)


def _review_config(
    analysis_config: dp.AnalysisConfig | None = None, **overrides
) -> review.ReviewPacketConfig:
    values = {
        "analysis_config": analysis_config or _analysis_config(),
        "per_stratum_quota": 2,
        "calibration_fraction": 0.5,
        "seed": 29,
    }
    values.update(overrides)
    return review.ReviewPacketConfig(**values)


def _observation(
    name: str,
    text: str,
    *,
    format_id: str = "eml-body",
    source_kind: str = dp.SOURCE_BODY_PROXY,
) -> dp.DocumentObservation:
    return dp.DocumentObservation(
        opaque_id=_digest(f"opaque:{name}"),
        raw_sha256=_digest(text),
        extracted_text=text,
        format_id=format_id,
        source_kind=source_kind,
        extraction_status="success",
    )


def _observations() -> list[dp.DocumentObservation]:
    return [
        _observation("a1", "alpha beta gamma delta", format_id="eml-body"),
        _observation("a2", "alpha beta gamma delta", format_id="eml-body"),
        _observation("a3", "alpha beta gamma delta", format_id="text-body"),
        _observation("a4", "alpha beta gamma delta", format_id="text-body"),
        _observation("a5", "alpha beta gamma epsilon", format_id="eml-body"),
        _observation("b1", "red blue green yellow", format_id="eml-body"),
        _observation("b2", "one two three four", format_id="text-body"),
        _observation("b3", "cat dog bird fish", format_id="eml-body"),
        _observation("b4", "spring summer autumn winter", format_id="text-body"),
        _observation("b5", "north south east west", format_id="eml-body"),
        _observation("b6", "circle square triangle line", format_id="text-body"),
        _observation("b7", "copper silver gold iron", format_id="eml-body"),
    ]


def _corpus_identity(count: int) -> dict:
    return {
        "profile_id": None,
        "signature": _digest("raw-corpus"),
        "kind": "raw-files",
        "schema": "jseval.raw-corpus-manifest.v1",
        "file_count": 1,
        "total_bytes": 1234,
        "manifest_pointer": None,
        "admission_policy": {
            "JUSTSEARCH_INGESTION_SKIP_PATTERNS": "default",
            "JUSTSEARCH_INGESTION_SKIP_EXTENSIONS": "default",
            "JUSTSEARCH_INGESTION_SKIP_DIRECTORY_NAMES": "default",
        },
    }


def _artifact(
    observations: list[dp.DocumentObservation], config: dp.AnalysisConfig
) -> dict:
    stage_counts = {
        "raw_member": len(observations),
        "parsed_body": len(observations),
        "eligible_body": len(observations),
        "retained_body": len({item.raw_sha256 for item in observations}),
    }
    corpus = _corpus_identity(len(observations))
    projection = dp.build_enron_source_projection_identity(
        observations,
        corpus_signature=corpus["signature"],
        min_words=0,
        stage_counts=stage_counts,
    )
    return dp.analyze(
        observations,
        corpus_identity=corpus,
        source_projection_identity=projection,
        config=config,
    )


def _packet(
    observations: list[dp.DocumentObservation] | None = None,
    *,
    analysis_config: dp.AnalysisConfig | None = None,
    review_config: review.ReviewPacketConfig | None = None,
) -> dict:
    docs = observations or _observations()
    analysis = analysis_config or _analysis_config()
    artifact = _artifact(docs, analysis)
    return review.build_review_packet(
        docs,
        artifact,
        config=review_config or _review_config(analysis),
    )


def test_packet_is_input_order_invariant_and_hash_is_semantic():
    observations = _observations()
    analysis = _analysis_config()
    artifact = _artifact(observations, analysis)
    config = _review_config(analysis)

    first = review.build_review_packet(observations, artifact, config=config)
    reordered = review.build_review_packet(list(reversed(observations)), artifact, config=config)

    assert first == reordered
    unhashed = dict(first)
    packet_hash = unhashed.pop("packet_hash")
    canonical = json.dumps(
        unhashed, sort_keys=True, separators=(",", ":"), ensure_ascii=False, allow_nan=False
    ).encode("utf-8")
    assert hashlib.sha256(canonical).hexdigest() == packet_hash


def test_document_partition_precedes_pairs_and_is_disjoint():
    observations = _observations()
    analysis = _analysis_config()
    packet = _packet(observations, analysis_config=analysis)
    calibration = set(packet["partition"]["calibration"]["opaque_ids"])
    holdout = set(packet["partition"]["holdout"]["opaque_ids"])

    assert calibration
    assert holdout
    assert calibration.isdisjoint(holdout)
    shingles = {
        item.opaque_id: dp.token_shingles(item.extracted_text or "", analysis.shingle_width)
        for item in observations
    }
    fingerprints = {opaque_id: dp.simhash64(value) for opaque_id, value in shingles.items()}
    candidates = dp.simhash_candidate_pairs(
        fingerprints,
        max_hamming=analysis.max_hamming,
        max_candidate_pairs=analysis.max_candidate_pairs,
    )
    assert all(
        {left, right} <= calibration or {left, right} <= holdout
        for left, right in candidates
    )
    for record in packet["records"]:
        pair_ids = {record["left"]["opaque_id"], record["right"]["opaque_id"]}
        assert pair_ids <= (calibration if record["split"] == "calibration" else holdout)


def test_strata_counts_inclusion_probabilities_and_weights_are_exact():
    packet = _packet(review_config=_review_config(per_stratum_quota=1))
    summaries = {
        (
            item["split"],
            item["sampling_frame"],
            json.dumps(item["stratum"], sort_keys=True),
        ): item
        for item in packet["sampling"]["strata"]
    }
    assert any(item["population_count"] > 1 for item in summaries.values())
    for record in packet["records"]:
        summary = summaries[
            (
                record["split"],
                record["sampling_frame"],
                json.dumps(record["stratum"], sort_keys=True),
            )
        ]
        probability = summary["selected_count"] / summary["population_count"]
        assert record["inclusion_probability"] == probability
        assert record["sampling_weight"] == 1 / probability
        assert summary["selected_count"] <= 1


def test_candidate_and_exhaustive_control_frames_do_not_overlap():
    packet = _packet(review_config=_review_config(per_stratum_quota=100))
    candidate = {
        item["pair_id"] for item in packet["records"] if item["sampling_frame"] == "candidate"
    }
    control = {
        item["pair_id"]
        for item in packet["records"]
        if item["sampling_frame"] == "exhaustive-control"
    }
    assert candidate
    assert control
    assert candidate.isdisjoint(control)
    assert "noncandidate-all-pairs" in packet["sampling"]["control_population"]
    for split in ("calibration", "holdout"):
        document_count = packet["partition"][split]["document_count"]
        population = sum(
            row["population_count"]
            for row in packet["sampling"]["frames"]
            if row["split"] == split
        )
        assert population == document_count * (document_count - 1) // 2


def test_threshold_boundaries_and_planted_near_non_near_records():
    thresholds = (0.5, 0.8)
    assert review.threshold_band(0.49, thresholds) == "below-0.5"
    assert review.threshold_band(0.5, thresholds) == "at-or-above-0.5-below-0.8"
    assert review.threshold_band(0.799, thresholds) == "at-or-above-0.5-below-0.8"
    assert review.threshold_band(0.8, thresholds) == "at-or-above-0.8"

    packet = _packet(review_config=_review_config(per_stratum_quota=100))
    similarities = {record["similarity"] for record in packet["records"]}
    assert 1.0 in similarities
    assert any(value < 0.5 for value in similarities)


def test_quota_applies_separately_to_every_exact_split_frame_stratum():
    packet = _packet(review_config=_review_config(per_stratum_quota=1))
    assert packet["sampling"]["strata"]
    assert all(item["selected_count"] == 1 for item in packet["sampling"]["strata"])
    for frame in packet["sampling"]["frames"]:
        expected = sum(
            item["selected_count"]
            for item in packet["sampling"]["strata"]
            if item["split"] == frame["split"]
            and item["sampling_frame"] == frame["sampling_frame"]
        )
        assert frame["selected_count"] == expected


def test_packet_contains_review_text_but_no_path_or_member_metadata_fields():
    packet = _packet(review_config=_review_config(per_stratum_quota=100))
    assert packet["sensitivity"] == "local-review-text"
    assert packet["intended_persistence"] == "uncommitted-local-only"
    assert any(record["left"]["text"] for record in packet["records"])

    def keys(value):
        if isinstance(value, dict):
            for key, child in value.items():
                yield key
                yield from keys(child)
        elif isinstance(value, list):
            for child in value:
                yield from keys(child)

    emitted_keys = set(keys(packet))
    assert not emitted_keys & {"path", "raw_root", "tarball", "member", "member_name"}


def test_observation_commitment_and_analysis_config_mismatches_fail_closed():
    observations = _observations()
    analysis = _analysis_config()
    artifact = _artifact(observations, analysis)
    changed = list(observations)
    changed[0] = _observation("changed", "changed document body")

    with pytest.raises(review.DuplicateReviewPacketError, match="commitment mismatch"):
        review.build_review_packet(changed, artifact, config=_review_config(analysis))
    with pytest.raises(review.DuplicateReviewPacketError, match="config mismatch"):
        review.build_review_packet(
            observations,
            artifact,
            config=_review_config(_analysis_config(seed=99)),
        )


def test_rehashed_outer_artifact_cannot_rebind_measurement_identity():
    observations = _observations()
    analysis = _analysis_config()
    artifact = _artifact(observations, analysis)
    artifact["input"]["source_projection_identity"]["policy"]["min_words"] = 99
    unhashed = dict(artifact)
    unhashed.pop("artifact_hash")
    artifact["artifact_hash"] = hashlib.sha256(
        json.dumps(
            unhashed, sort_keys=True, separators=(",", ":"), ensure_ascii=False, allow_nan=False
        ).encode("utf-8")
    ).hexdigest()

    with pytest.raises(review.DuplicateReviewPacketError, match="invalid measurement identity"):
        review.build_review_packet(observations, artifact, config=_review_config(analysis))


def test_v1_artifact_cannot_borrow_sample_scope():
    observations = _observations()
    analysis = _analysis_config()
    artifact = _artifact(observations, analysis)
    artifact["algorithm"]["scope"] = dp.ENRON_SAMPLE_SCOPE
    unhashed = dict(artifact)
    unhashed.pop("artifact_hash")
    artifact["artifact_hash"] = hashlib.sha256(
        json.dumps(
            unhashed, sort_keys=True, separators=(",", ":"), ensure_ascii=False, allow_nan=False
        ).encode("utf-8")
    ).hexdigest()

    with pytest.raises(review.DuplicateReviewPacketError, match="algorithm provenance"):
        review.build_review_packet(observations, artifact, config=_review_config(analysis))


def test_packet_requires_two_documents_in_each_candidate_disjoint_split():
    observations = _observations()[:3]
    analysis = _analysis_config()
    with pytest.raises(review.DuplicateReviewPacketError, match="two documents per split"):
        review.build_review_packet(
            observations,
            _artifact(observations, analysis),
            config=_review_config(analysis),
        )


@pytest.mark.parametrize("slice_size", [0, 1])
def test_packet_requires_a_reviewable_exhaustive_slice(slice_size):
    observations = _observations()
    analysis = _analysis_config(exhaustive_slice_size=slice_size)
    with pytest.raises(review.DuplicateReviewPacketError, match="at least 2"):
        review.build_review_packet(
            observations,
            _artifact(observations, analysis),
            config=_review_config(analysis),
        )


@pytest.mark.parametrize("quota", [0, -1, True, 1.5, "2"])
def test_invalid_quota_is_rejected(quota):
    observations = _observations()
    analysis = _analysis_config()
    with pytest.raises(review.DuplicateReviewPacketError, match="quota"):
        review.build_review_packet(
            observations,
            _artifact(observations, analysis),
            config=_review_config(analysis, per_stratum_quota=quota),
        )


def test_failed_empty_mixed_and_nonopaque_proxy_documents_are_rejected():
    analysis = _analysis_config()
    valid = _observations()

    failed = list(valid)
    failed[0] = dp.DocumentObservation(
        opaque_id=failed[0].opaque_id,
        raw_sha256=failed[0].raw_sha256,
        extracted_text=None,
        format_id=failed[0].format_id,
        source_kind=failed[0].source_kind,
        extraction_status="failed",
    )
    with pytest.raises(review.DuplicateReviewPacketError, match="successful"):
        review._validate_observations(failed, analysis)

    empty = list(valid)
    empty[0] = _observation("empty", "   ")
    with pytest.raises(review.DuplicateReviewPacketError, match="nonempty"):
        review._validate_observations(empty, analysis)

    mixed = list(valid)
    mixed[0] = _observation("production", "production body", source_kind=dp.PRODUCTION_EXTRACTED)
    with pytest.raises(review.DuplicateReviewPacketError, match="mix source|one supported source"):
        review._validate_observations(mixed, analysis)

    unsafe_id = list(valid)
    unsafe_id[0] = dp.DocumentObservation(
        opaque_id="maildir/person/message",
        raw_sha256=unsafe_id[0].raw_sha256,
        extracted_text=unsafe_id[0].extracted_text,
        format_id=unsafe_id[0].format_id,
        source_kind=unsafe_id[0].source_kind,
        extraction_status="success",
    )
    with pytest.raises(review.DuplicateReviewPacketError, match="privacy-safe"):
        review._validate_observations(unsafe_id, analysis)

    duplicate = list(valid)
    duplicate[1] = dp.DocumentObservation(
        opaque_id=duplicate[0].opaque_id,
        raw_sha256=duplicate[1].raw_sha256,
        extracted_text=duplicate[1].extracted_text,
        format_id=duplicate[1].format_id,
        source_kind=duplicate[1].source_kind,
        extraction_status="success",
    )
    with pytest.raises(review.DuplicateReviewPacketError, match="unique"):
        review._validate_observations(duplicate, analysis)


def test_non_json_artifact_and_nonfinite_review_config_fail_closed():
    observations = _observations()
    analysis = _analysis_config()
    malformed = _artifact(observations, analysis)
    malformed["input"]["corpus_identity"]["profile_id"] = float("nan")
    with pytest.raises(review.DuplicateReviewPacketError, match="canonical UTF-8 JSON"):
        review.build_review_packet(
            observations,
            malformed,
            config=_review_config(analysis),
        )

    with pytest.raises(review.DuplicateReviewPacketError, match="calibration_fraction"):
        review.build_review_packet(
            observations,
            _artifact(observations, analysis),
            config=_review_config(analysis, calibration_fraction=float("nan")),
        )


def test_records_are_unlabeled_and_packet_cannot_claim_a_near_decision():
    packet = _packet(review_config=_review_config(per_stratum_quota=100))
    assert packet["label_status"] == "UNLABELED"
    assert packet["near_duplicate_decision"] == {
        "status": "UNDECIDED",
        "selected_threshold": None,
    }
    assert all(
        record["label"] is None
        and record["labeler"] is None
        and record["notes"] is None
        for record in packet["records"]
    )
    def keys(value):
        if isinstance(value, dict):
            for key, child in value.items():
                yield key
                yield from keys(child)
        elif isinstance(value, list):
            for child in value:
                yield from keys(child)

    emitted_keys = set(keys(packet))
    assert "precision" not in emitted_keys
    assert "recall" not in emitted_keys
