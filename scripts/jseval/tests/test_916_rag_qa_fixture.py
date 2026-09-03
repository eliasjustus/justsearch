"""Tempdoc 916 Part 1 — offline validation of the committed `rag-qa-v1` recipe.

No live claude, no dev stack, no paid API, no backend. Two tiers:

* **Always** — the recipe's schema, its self-consistency, and the derivation rule exercised
  hermetically against a hand-built corpus. These cannot be satisfied by an absent dataset.
* **When the corpora are present** — regenerate and assert the pinned digests still hold. Skipped
  with an explicit reason when a corpus is missing, so a skip is never mistaken for a pass.
"""

from __future__ import annotations

import hashlib
import importlib.util
import json
import sys
from pathlib import Path

import pytest

JSEVAL_ROOT = Path(__file__).resolve().parents[1]
FIXTURE_DIR = JSEVAL_ROOT / "916-corpora" / "rag-qa-v1"
RECIPE_PATH = FIXTURE_DIR / "recipe.json"

REQUIRED_TOP_LEVEL = {
    "schema",
    "name",
    "tempdoc",
    "method",
    "generator",
    "llm_used",
    "content_committed",
    "derivation",
    "parameters",
    "corpora",
    "instrument",
    "known_limits",
}
REQUIRED_PER_CORPUS = {
    "question_shape",
    "questions",
    "gold_docs",
    "eligible_qrel_queries",
    "skipped_gold_below_min_chars",
    "skipped_span_unresolved",
    "query_gold_sha256",
    "evidence_offsets_sha256",
    "source",
    "license",
}
SHA256_LEN = 64


def _generator():
    sys.path.insert(0, str(JSEVAL_ROOT))
    spec = importlib.util.spec_from_file_location(
        "rag_qa_v1_generate", FIXTURE_DIR / "generate.py"
    )
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


@pytest.fixture(scope="module")
def recipe() -> dict:
    return json.loads(RECIPE_PATH.read_text(encoding="utf-8"))


def test_recipe_schema(recipe):
    missing = REQUIRED_TOP_LEVEL - set(recipe)
    assert not missing, f"recipe.json is missing required keys: {sorted(missing)}"
    assert recipe["schema"] == "916-rag-qa.v1"
    assert recipe["method"] == "qrel-derived-span-v1"
    assert recipe["llm_used"] is False, "this fixture must never be LLM-generated"
    assert recipe["content_committed"] is False, (
        "committing corpus text would break the convention every other NNN-corpora member follows"
    )
    assert (JSEVAL_ROOT.parents[1] / recipe["generator"]).exists()


def test_recipe_per_corpus_entries_are_complete_and_self_consistent(recipe):
    assert set(recipe["corpora"]) == {
        "mixed/enron-qa",
        "mixed/legal-clerc-200",
        "beir/scifact",
    }
    for name, entry in recipe["corpora"].items():
        missing = REQUIRED_PER_CORPUS - set(entry)
        assert not missing, f"{name} is missing {sorted(missing)}"
        for digest_key in ("query_gold_sha256", "evidence_offsets_sha256"):
            digest = entry[digest_key]
            assert len(digest) == SHA256_LEN and set(digest) <= set("0123456789abcdef"), (
                f"{name}.{digest_key} is not a lowercase hex sha256"
            )
        assert entry["questions"] == recipe["parameters"]["per_corpus"], (
            f"{name} declares {entry['questions']} questions but per_corpus is "
            f"{recipe['parameters']['per_corpus']}"
        )
        assert 0 < entry["gold_docs"] <= entry["questions"], (
            f"{name}: gold_docs must be positive and cannot exceed the question count"
        )
        assert entry["skipped_span_unresolved"] == 0, (
            f"{name}: an unresolved span means locate_offset returned None, which the recipe must "
            "not silently pin"
        )


def test_the_two_digests_of_a_corpus_differ(recipe):
    """A copy-paste of one digest into the other slot would make both files look pinned."""
    for name, entry in recipe["corpora"].items():
        assert entry["query_gold_sha256"] != entry["evidence_offsets_sha256"], name


def test_span_rule_is_deterministic_and_prefers_overlap():
    gen = _generator()
    # Two sentences overlap the question, so the rule must pick the BETTER one rather than
    # merely the only one — a max-vs-min mutation has to be able to fail this.
    doc = (
        "A passing mention of the office in an otherwise unrelated note about the weather. "
        "The quarterly bandwidth market report was circulated to the San Francisco office. "
        "A third sentence padded with many many many many many many many many many words."
    )
    question = "bandwidth market report San Francisco office"
    span, score = gen.best_span(question, doc)
    assert "bandwidth market report" in span
    assert score > 0
    # Total rule: the same inputs must give the same output, every time.
    assert gen.best_span(question, doc) == (span, score)
    # And the span must be locatable back into the raw document.
    assert gen.locate_offset(doc, span) == doc.index(span)


def test_span_rule_returns_none_rather_than_a_silent_first_sentence():
    gen = _generator()
    doc = "Alpha beta gamma delta epsilon zeta eta theta iota kappa lambda mu nu."
    assert gen.best_span("", doc) is None
    assert gen.best_span("qqqq wwww eeee rrrr", doc) is None, (
        "no shared token must be 'unresolved', never sentence 0 — a silent fallback would fake "
        "the gold span for every off-topic question"
    )


def test_sqrt_denominator_stops_a_long_sentence_winning_on_length_alone():
    gen = _generator()
    filler = " ".join(f"word{i}" for i in range(200))
    # Both sentences contain ALL FOUR query terms, so the raw overlap ties at 4 and only the
    # length normalization can separate them. The long one is placed FIRST, so a rule without the
    # sqrt denominator would resolve the tie to it via the earliest-sentence tie-break.
    doc = (
        f"The report also mentions alpha beta gamma delta and {filler}. "
        "The report names alpha beta gamma delta."
    )
    span, _ = gen.best_span("alpha beta gamma delta", doc)
    assert span.startswith("The report names alpha"), (
        "the short, densely-matching sentence must win over the long one containing the same "
        "terms; got: " + span[:80]
    )


def _corpus_present(name: str) -> bool:
    if name.startswith("mixed/"):
        return (
            JSEVAL_ROOT.parents[1] / "datasets" / name.replace("/", "/") / "corpus.jsonl"
        ).exists()
    return False  # BEIR lives in the shared ir_datasets cache; regeneration is checked per-corpus


@pytest.mark.parametrize("name", ["mixed/enron-qa", "mixed/legal-clerc-200"])
def test_regenerating_reproduces_the_pinned_digests(name, recipe, tmp_path):
    if not _corpus_present(name):
        pytest.skip(f"{name} is not materialized in this checkout (datasets/ is gitignored)")
    gen = _generator()
    built = gen.build(
        name,
        recipe["parameters"]["per_corpus"],
        recipe["parameters"]["min_gold_chars"],
    )
    out = tmp_path / name.replace("/", "_")
    out.mkdir(parents=True)
    qpath = out / "queries.json"
    opath = out / "evidence_offsets.json"
    qpath.write_text(
        json.dumps(built["entries"], indent=2, ensure_ascii=False, sort_keys=True),
        encoding="utf-8",
        newline="\n",
    )
    opath.write_text(
        json.dumps(built["offsets"], indent=2, ensure_ascii=False, sort_keys=True),
        encoding="utf-8",
        newline="\n",
    )
    entry = recipe["corpora"][name]
    assert hashlib.sha256(qpath.read_bytes()).hexdigest() == entry["query_gold_sha256"]
    assert hashlib.sha256(opath.read_bytes()).hexdigest() == entry["evidence_offsets_sha256"]
    assert len(built["entries"]) == entry["questions"]
    assert len(built["offsets"]["offsets"]) == entry["gold_docs"]
    assert built["offsets"]["schema"] == "evidence-offsets.v1", (
        "the gold-span sidecar must reuse the existing representation, not fork a new one"
    )
