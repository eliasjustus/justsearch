from __future__ import annotations

import json
from pathlib import Path

from jseval import corpus_build, corpus_certify, corpus_inject, corpus_query_strata


def _write_jsonl(path: Path, rows: list[dict]) -> None:
    path.write_text("".join(json.dumps(row) + "\n" for row in rows), encoding="utf-8")


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


def test_checked_in_707_member_recipes_are_strict_and_license_fail_closed():
    import jsonschema

    root = Path(__file__).parents[1]
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
            assert certification["fully_certified"] is False
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
