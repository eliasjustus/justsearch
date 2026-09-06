from __future__ import annotations

import hashlib
import json

import pytest

from jseval import duplicate_prevalence as dp


def _digest(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def _canonical_digest(value: object) -> str:
    payload = json.dumps(
        value, sort_keys=True, separators=(",", ":"), ensure_ascii=False, allow_nan=False
    ).encode("utf-8")
    return hashlib.sha256(payload).hexdigest()


def _identity(count: int, *, signature: str | None = None) -> dict:
    return {
        "profile_id": None,
        "signature": signature or _digest("corpus"),
        "kind": "raw-files",
        "schema": "jseval.raw-corpus-manifest.v1",
        "file_count": count,
        "total_bytes": count * 10,
        "manifest_pointer": "PRIVATE-CORPUS-PATH/strict.json",
        "admission_policy": {
            "JUSTSEARCH_INGESTION_SKIP_PATTERNS": "default",
            "JUSTSEARCH_INGESTION_SKIP_EXTENSIONS": "default",
            "JUSTSEARCH_INGESTION_SKIP_DIRECTORY_NAMES": "default",
        },
    }


def _extraction_identity(
    observations: list[dp.DocumentObservation],
    *,
    corpus_identity: dict | None = None,
    source_kind: str = dp.PRODUCTION_EXTRACTED,
) -> dict:
    count = len(observations)
    corpus = corpus_identity or _identity(count)
    unsigned = {
        "schema": dp.EXTRACTION_SNAPSHOT_SCHEMA,
        "corpus_signature": corpus["signature"],
        "observations_digest": dp.observation_commitment(observations),
        "source_kind": source_kind,
        "extractor_build": "worker@abc123",
        "extraction_policy_digest": _digest("policy"),
        "document_count": count,
        "reconciliation": {
            "status": "matched",
            "expected_count": count,
            "exported_count": count,
            "unique_opaque_ids": count,
        },
    }
    return {"digest": _canonical_digest(unsigned), **unsigned}


def _observation(
    opaque_id: str,
    text: str | None,
    *,
    raw: str | None = None,
    format_id: str | None = "txt",
    source_kind: str = dp.SOURCE_BODY_PROXY,
) -> dp.DocumentObservation:
    return dp.DocumentObservation(
        opaque_id=opaque_id,
        raw_sha256=raw or _digest(f"raw:{opaque_id}"),
        extracted_text=text,
        format_id=format_id,
        source_kind=source_kind,
        extraction_status="success" if text is not None else "failed",
    )


def _config(**overrides) -> dp.AnalysisConfig:
    values = {
        "shingle_width": 1,
        "simhash_bits": 64,
        "max_hamming": 63,
        "jaccard_thresholds": (0.5, 0.9),
        "exhaustive_slice_size": 100,
        "bootstrap_draws": 200,
        "seed": 7,
        "max_candidate_pairs": 10_000,
    }
    values.update(overrides)
    return dp.AnalysisConfig(**values)


def test_normalization_is_nfc_line_ending_whitespace_and_case_preserving():
    assert dp.normalize_content("  Cafe\u0301\r\n\tMIXED\u2003Case!  ") == "Café MIXED Case!"
    assert dp.normalize_content("A") != dp.normalize_content("a")
    assert dp.normalize_content("word!") != dp.normalize_content("word")


def test_tokenization_short_rule_empty_rule_and_hand_jaccard():
    assert dp.tokenize_near_duplicate("Straße, CAFÉ!") == ("strasse", "café")
    assert dp.token_shingles("", 3) == frozenset()
    assert dp.token_shingles("Alpha beta", 3) == frozenset({("\0short", "alpha", "beta")})
    left = dp.token_shingles("a b c", 1)
    right = dp.token_shingles("a b d", 1)
    assert dp.jaccard_similarity(left, right) == pytest.approx(0.5)
    assert dp.jaccard_similarity(frozenset(), frozenset()) == 0.0


def test_planted_byte_content_near_and_nonduplicate_groups():
    same_raw = _digest("same raw bytes")
    observations = [
        _observation("a", "Café\r\nWorld", raw=same_raw),
        _observation("b", "totally different", raw=same_raw),
        _observation("c", "Cafe\u0301  \tWorld"),
        _observation("d", "alpha beta gamma delta"),
        _observation("e", "alpha beta gamma epsilon"),
        _observation("f", "orange pear"),
    ]

    result = dp.analyze(observations, corpus_identity=_identity(6), config=_config())

    assert result["byte_exact"]["duplicate_groups"] == [{"digest": same_raw, "size": 2}]
    assert result["byte_exact"]["duplicate_documents"] == 2
    assert result["content_exact"]["duplicate_documents"] == 2
    threshold = result["near_duplicate"]["threshold_sweep"][0]
    assert threshold["duplicate_documents"] == 4
    assert threshold["eligible_documents"] == 6
    assert threshold["size_histogram"] == {"1": 2, "2": 2}
    assert result["near_duplicate"]["decision"]["status"] == "UNDECIDED"
    assert result["near_duplicate"]["decision"]["selected_threshold"] is None


@pytest.mark.parametrize("max_hamming", [0, 1, 2, 3, 7, 15, 31, 63])
def test_h_plus_one_bands_cover_every_pair_inside_hamming_radius(max_hamming):
    changed = sum(1 << bit for bit in range(max_hamming))
    fingerprints = {"zero": 0, "changed": changed}
    candidates = dp.simhash_candidate_pairs(
        fingerprints, max_hamming=max_hamming, max_candidate_pairs=100
    )
    assert dp.hamming_distance(fingerprints["zero"], fingerprints["changed"]) == max_hamming
    assert tuple(sorted(("zero", "changed"))) in candidates


def test_high_jaccard_pair_can_miss_simhash_candidates():
    left = dp.token_shingles("a b c", 1)
    right = dp.token_shingles("a b d", 1)
    assert dp.jaccard_similarity(left, right) == 0.5
    fingerprints = {"left": dp.simhash64(left), "right": dp.simhash64(right)}
    assert fingerprints["left"] != fingerprints["right"]
    assert dp.simhash_candidate_pairs(
        fingerprints, max_hamming=0, max_candidate_pairs=1
    ) == frozenset()


def test_single_linkage_chaining_is_reported_as_one_component():
    observations = [
        _observation("a", "alpha beta"),
        _observation("b", "alpha beta gamma"),
        _observation("c", "beta gamma"),
    ]
    result = dp.analyze(
        observations,
        corpus_identity=_identity(3),
        config=_config(jaccard_thresholds=(0.6,)),
    )
    near = result["near_duplicate"]["threshold_sweep"][0]
    assert near["confirmed_edge_count"] == 2
    assert near["size_histogram"] == {"3": 1}
    assert near["largest_component"] == 3
    assert result["algorithm"]["clustering"] == "single-linkage-connected-components"


def test_empty_text_is_excluded_from_exact_and_near_while_short_text_is_included():
    observations = [
        _observation("empty-a", ""),
        _observation("empty-b", " \r\n\t"),
        _observation("short", "one token"),
        _observation("failed", None),
    ]
    result = dp.analyze(
        observations,
        corpus_identity=_identity(4),
        config=_config(shingle_width=5, jaccard_thresholds=(0.8,)),
    )
    assert result["content_exact"]["duplicate_documents"] == 0
    assert result["denominators"] == {
        "manifest_files": 4,
        "source_observations": 4,
        "extraction_successes": 3,
        "extraction_failures": 1,
        "content_exact_analyzable_nonempty_documents": 1,
        "content_exact_excluded_empty_documents": 2,
        "near_analyzable_nonempty_documents": 1,
        "near_excluded_empty_documents": 2,
    }


def test_seeded_component_bootstrap_is_repeatable_and_records_seed_change():
    observations = [
        _observation("a", "same"),
        _observation("b", "same"),
        _observation("c", "different"),
    ]
    first = dp.analyze(observations, corpus_identity=_identity(3), config=_config(seed=11))
    repeated = dp.analyze(list(reversed(observations)), corpus_identity=_identity(3), config=_config(seed=11))
    changed = dp.analyze(observations, corpus_identity=_identity(3), config=_config(seed=12))

    assert first == repeated
    first_interval = first["content_exact"]["stability_interval"]
    changed_interval = changed["content_exact"]["stability_interval"]
    assert first_interval["seed_material_sha256"] != changed_interval["seed_material_sha256"]
    assert 0 <= first_interval["low"] <= first_interval["high"] <= 1
    assert "not population uncertainty" in first_interval["interpretation"]


def test_exhaustive_slice_measures_candidate_miss_and_zero_positive_threshold():
    observations = [
        _observation("left", "a b c"),
        _observation("right", "a b d"),
    ]
    result = dp.analyze(
        observations,
        corpus_identity=_identity(2),
        config=_config(max_hamming=0, exhaustive_slice_size=2),
    )
    recall = result["near_duplicate"]["exhaustive_slice"]["candidate_recall_by_threshold"]
    assert recall[0] == {
        "threshold": 0.5,
        "positive_pair_count": 1,
        "captured_pair_count": 0,
        "missed_pair_count": 1,
        "candidate_recall": 0.0,
        "status": "measured",
    }
    assert recall[1]["positive_pair_count"] == 0
    assert recall[1]["candidate_recall"] is None
    assert recall[1]["status"] == "undefined-no-positive-pairs"
    assert "no deterministic Jaccard-recall guarantee" in result["near_duplicate"]["candidate_generation"]["banding_guarantee"]


@pytest.mark.parametrize(
    "mutate",
    [
        lambda identity: identity.pop("signature"),
        lambda identity: identity.update(signature="A" * 64),
        lambda identity: identity.update(kind="legacy"),
        lambda identity: identity.update(total_bytes=-1),
        lambda identity: identity["admission_policy"].pop("JUSTSEARCH_INGESTION_SKIP_PATTERNS"),
    ],
)
def test_malformed_strict_corpus_identity_is_rejected(mutate):
    identity = _identity(1)
    mutate(identity)
    with pytest.raises(dp.DuplicatePrevalenceError):
        dp.analyze([_observation("a", "text")], corpus_identity=identity, config=_config())


def test_opaque_ids_and_observation_contract_are_fail_closed():
    duplicate_ids = [_observation("same", "a"), _observation("same", "b")]
    with pytest.raises(dp.DuplicatePrevalenceError, match="unique"):
        dp.analyze(duplicate_ids, corpus_identity=_identity(2), config=_config())

    malformed_hash = _observation("a", "text")
    malformed_hash = dp.DocumentObservation(
        opaque_id=malformed_hash.opaque_id,
        raw_sha256="not-a-hash",
        extracted_text=malformed_hash.extracted_text,
        format_id=malformed_hash.format_id,
        source_kind=malformed_hash.source_kind,
        extraction_status=malformed_hash.extraction_status,
    )
    with pytest.raises(dp.DuplicatePrevalenceError, match="SHA-256"):
        dp.analyze([malformed_hash], corpus_identity=_identity(1), config=_config())


def test_production_input_requires_complete_matching_reconciliation():
    observations = [_observation("a", "text", source_kind=dp.PRODUCTION_EXTRACTED)]
    with pytest.raises(dp.DuplicatePrevalenceError, match="requires extraction identity"):
        dp.analyze(observations, corpus_identity=_identity(1), config=_config())

    incomplete = _extraction_identity(observations)
    incomplete.pop("extractor_build")
    with pytest.raises(dp.DuplicatePrevalenceError, match="incomplete"):
        dp.analyze(
            observations,
            corpus_identity=_identity(1),
            extraction_identity=incomplete,
            config=_config(),
        )

    mismatch = _extraction_identity(observations)
    mismatch["reconciliation"]["unique_opaque_ids"] = 0
    with pytest.raises(dp.DuplicatePrevalenceError, match="did not match"):
        dp.analyze(
            observations,
            corpus_identity=_identity(1),
            extraction_identity=mismatch,
            config=_config(),
        )

    result = dp.analyze(
        observations,
        corpus_identity=_identity(1),
        extraction_identity=_extraction_identity(observations),
        config=_config(),
    )
    assert result["input"]["content_interpretation"] == "production-extracted-content"
    assert result["input"]["extraction_identity"]["reconciliation"]["status"] == "matched"

    with pytest.raises(dp.DuplicatePrevalenceError, match="observation count"):
        dp.analyze(
            observations,
            corpus_identity=_identity(2),
            extraction_identity=_extraction_identity(observations),
            config=_config(),
        )


def test_production_snapshot_is_bound_to_supported_schema_manifest_and_observations():
    corpus = _identity(1, signature=_digest("corpus-a"))
    observations = [_observation("a", "original", source_kind=dp.PRODUCTION_EXTRACTED)]
    extraction = _extraction_identity(observations, corpus_identity=corpus)

    changed = [_observation("a", "changed", source_kind=dp.PRODUCTION_EXTRACTED)]
    with pytest.raises(dp.DuplicatePrevalenceError, match="supplied observations"):
        dp.analyze(
            changed,
            corpus_identity=corpus,
            extraction_identity=extraction,
            config=_config(),
        )

    other_corpus = _identity(1, signature=_digest("corpus-b"))
    with pytest.raises(dp.DuplicatePrevalenceError, match="raw corpus manifest"):
        dp.analyze(
            observations,
            corpus_identity=other_corpus,
            extraction_identity=extraction,
            config=_config(),
        )

    wrong_schema = dict(extraction)
    wrong_schema["schema"] = "jseval.extracted-content-snapshot.v9"
    with pytest.raises(dp.DuplicatePrevalenceError, match="unsupported snapshot schema"):
        dp.analyze(
            observations,
            corpus_identity=corpus,
            extraction_identity=wrong_schema,
            config=_config(),
        )

    tampered_build = dict(extraction)
    tampered_build["extractor_build"] = "worker@different"
    with pytest.raises(dp.DuplicatePrevalenceError, match="committed metadata"):
        dp.analyze(
            observations,
            corpus_identity=corpus,
            extraction_identity=tampered_build,
            config=_config(),
        )

    wrong_digest = dict(extraction)
    wrong_digest["digest"] = _digest("wrong-snapshot")
    with pytest.raises(dp.DuplicatePrevalenceError, match="committed metadata"):
        dp.analyze(
            observations,
            corpus_identity=corpus,
            extraction_identity=wrong_digest,
            config=_config(),
        )


def test_source_body_proxy_binds_archive_manifest_without_one_to_one_count_or_extraction_claim():
    observations = [
        _observation("message-a", "body a", raw=_digest("body a")),
        _observation("message-b", "body b", raw=_digest("body b")),
    ]
    corpus = _identity(1)
    stage_counts = {
        "raw_member": 3,
        "parsed_body": 3,
        "eligible_body": 2,
        "retained_body": 2,
    }
    projection = dp.build_enron_source_projection_identity(
        observations,
        corpus_signature=corpus["signature"],
        min_words=10,
        stage_counts=stage_counts,
    )
    result = dp.analyze(
        observations,
        corpus_identity=corpus,
        source_projection_identity=projection,
        config=_config(),
    )
    assert result["denominators"]["manifest_files"] == 1
    assert result["denominators"]["source_observations"] == 2
    assert result["input"]["content_interpretation"] == "source-body-proxy"
    assert result["input"]["observations_digest"] == dp.observation_commitment(observations)
    assert result["input"]["source_projection_identity"]["stage_counts"] == stage_counts
    assert result["input"]["source_projection_identity"]["policy"]["min_words"] == 10

    changed = [
        _observation("message-a", "changed a", raw=_digest("changed a")),
        _observation("message-b", "changed b", raw=_digest("changed b")),
    ]
    changed_projection = dp.build_enron_source_projection_identity(
        changed,
        corpus_signature=corpus["signature"],
        min_words=10,
        stage_counts=stage_counts,
    )
    changed_result = dp.analyze(
        changed,
        corpus_identity=corpus,
        source_projection_identity=changed_projection,
        config=_config(),
    )
    assert changed_result["artifact_hash"] != result["artifact_hash"]

    with pytest.raises(dp.DuplicatePrevalenceError, match="supplied observations"):
        dp.analyze(
            changed,
            corpus_identity=corpus,
            source_projection_identity=projection,
            config=_config(),
        )

    other_corpus = _identity(1, signature=_digest("other-archive"))
    with pytest.raises(dp.DuplicatePrevalenceError, match="raw corpus manifest"):
        dp.analyze(
            observations,
            corpus_identity=other_corpus,
            source_projection_identity=projection,
            config=_config(),
        )

    tampered = dict(projection)
    tampered["policy"] = {**projection["policy"], "min_words": 11}
    with pytest.raises(dp.DuplicatePrevalenceError, match="committed metadata"):
        dp.analyze(
            observations,
            corpus_identity=corpus,
            source_projection_identity=tampered,
            config=_config(),
        )

    wrong_counts = {**stage_counts, "eligible_body": 1, "retained_body": 1}
    with pytest.raises(dp.DuplicatePrevalenceError, match="eligible-body stage count"):
        dp.build_enron_source_projection_identity(
            observations,
            corpus_signature=corpus["signature"],
            min_words=10,
            stage_counts=wrong_counts,
        )

    invalid_body_digest = [_observation("message-a", "body a", raw=_digest("different"))]
    with pytest.raises(dp.DuplicatePrevalenceError, match="digests must match"):
        dp.build_enron_source_projection_identity(
            invalid_body_digest,
            corpus_signature=corpus["signature"],
            min_words=10,
            stage_counts={
                "raw_member": 1,
                "parsed_body": 1,
                "eligible_body": 1,
                "retained_body": 1,
            },
        )

    failed_eligible = [_observation("message-a", None)]
    with pytest.raises(dp.DuplicatePrevalenceError, match="successful text observation"):
        dp.build_enron_source_projection_identity(
            failed_eligible,
            corpus_signature=corpus["signature"],
            min_words=10,
            stage_counts={
                "raw_member": 1,
                "parsed_body": 1,
                "eligible_body": 1,
                "retained_body": 1,
            },
        )

    duplicate_bodies = [
        _observation("message-a", "same body", raw=_digest("same body")),
        _observation("message-b", "same body", raw=_digest("same body")),
    ]
    with pytest.raises(dp.DuplicatePrevalenceError, match="distinct eligible body digests"):
        dp.build_enron_source_projection_identity(
            duplicate_bodies,
            corpus_signature=corpus["signature"],
            min_words=10,
            stage_counts={
                "raw_member": 2,
                "parsed_body": 2,
                "eligible_body": 2,
                "retained_body": 2,
            },
        )

    with pytest.raises(dp.DuplicatePrevalenceError, match="must not claim"):
        dp.analyze(
            observations,
            corpus_identity=_identity(1),
            extraction_identity=_extraction_identity(observations, source_kind=dp.SOURCE_BODY_PROXY),
            config=_config(),
        )


@pytest.mark.parametrize(
    ("field", "value"),
    [
        ("max_hamming", "3"),
        ("exhaustive_slice_size", "10"),
        ("bootstrap_draws", "20"),
        ("max_candidate_pairs", "100"),
    ],
)
def test_malformed_integer_config_is_domain_error(field, value):
    config_values = _config().__dict__ | {field: value}
    with pytest.raises(dp.DuplicatePrevalenceError):
        dp.analyze(
            [_observation("a", "text")],
            corpus_identity=_identity(1),
            config=dp.AnalysisConfig(**config_values),
        )


def test_mixed_type_jaccard_thresholds_fail_with_domain_error():
    config_values = _config().__dict__ | {"jaccard_thresholds": (0.8, "invalid")}
    with pytest.raises(dp.DuplicatePrevalenceError, match="finite values"):
        dp.analyze(
            [_observation("a", "text")],
            corpus_identity=_identity(1),
            config=dp.AnalysisConfig(**config_values),
        )


def test_malformed_observation_type_is_domain_error_before_sorting():
    with pytest.raises(dp.DuplicatePrevalenceError, match="DocumentObservation"):
        dp.analyze([object()], corpus_identity=_identity(1), config=_config())


def test_lone_surrogates_fail_with_domain_error_at_input_boundary():
    observation = _observation("a", "bad\ud800text")
    with pytest.raises(dp.DuplicatePrevalenceError, match="valid UTF-8"):
        dp.analyze([observation], corpus_identity=_identity(1), config=_config())

    identity = _identity(1)
    identity["admission_policy"]["JUSTSEARCH_INGESTION_SKIP_PATTERNS"] = "bad\ud800policy"
    with pytest.raises(dp.DuplicatePrevalenceError, match="valid UTF-8"):
        dp.analyze([_observation("a", "text")], corpus_identity=identity, config=_config())


def test_artifact_hash_is_input_order_invariant_and_config_sensitive():
    observations = [_observation("b", "alpha beta"), _observation("a", "alpha gamma")]
    first = dp.analyze(observations, corpus_identity=_identity(2), config=_config(seed=1))
    reordered = dp.analyze(list(reversed(observations)), corpus_identity=_identity(2), config=_config(seed=1))
    changed = dp.analyze(observations, corpus_identity=_identity(2), config=_config(seed=2))

    assert first == reordered
    assert first["artifact_hash"] == reordered["artifact_hash"]
    assert first["artifact_hash"] != changed["artifact_hash"]
    unhashed = dict(first)
    artifact_hash = unhashed.pop("artifact_hash")
    canonical = json.dumps(
        unhashed, sort_keys=True, separators=(",", ":"), ensure_ascii=False, allow_nan=False
    ).encode("utf-8")
    assert hashlib.sha256(canonical).hexdigest() == artifact_hash


def test_aggregate_artifact_does_not_emit_ids_paths_or_text():
    private_marker = "PRIVATE-secret-customer-filename-and-text"
    observations = [
        _observation(
            f"opaque-{private_marker}",
            private_marker,
            format_id=f"format-{private_marker}",
        )
    ]
    identity = _identity(1)
    identity["admission_policy"]["JUSTSEARCH_INGESTION_SKIP_PATTERNS"] = private_marker
    result = dp.analyze(observations, corpus_identity=identity, config=_config())
    serialized = json.dumps(result, sort_keys=True)

    assert private_marker not in serialized
    assert "PRIVATE-CORPUS-PATH" not in serialized
    assert result["privacy"] == {
        "mode": "aggregate-only",
        "document_ids_emitted": False,
        "paths_emitted": False,
        "text_emitted": False,
    }


def test_candidate_explosion_fails_instead_of_truncating():
    observations = [_observation("a", "a"), _observation("b", "b")]
    with pytest.raises(dp.DuplicatePrevalenceError, match="refuses to truncate"):
        dp.analyze(
            observations,
            corpus_identity=_identity(2),
            config=_config(max_candidate_pairs=0),
        )
