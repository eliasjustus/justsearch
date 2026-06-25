"""Tests for context_coverage.py — evidence extraction and coverage computation."""

from __future__ import annotations

from jseval.context_coverage import (
    _coverage_for_text,
    _coverage_for_units,
    _excerpt_texts_from_hit,
    compute_query_coverage,
    extract_expected_evidence,
    summarize_coverage,
    tokenize_evidence,
)


# ---------------------------------------------------------------------------
# tokenize_evidence
# ---------------------------------------------------------------------------

class TestTokenizeEvidence:
    def test_basic(self):
        assert tokenize_evidence("Hello World testing") == ["hello", "world", "testing"]

    def test_stopwords_removed(self):
        tokens = tokenize_evidence("the quick brown fox and the lazy dog")
        assert "the" not in tokens
        assert "and" not in tokens
        assert "quick" in tokens

    def test_min_length_words(self):
        # "a" and "ab" are too short, "abc" passes
        assert tokenize_evidence("a ab abc abcd") == ["abc", "abcd"]

    def test_numbers(self):
        # Single-digit numbers dropped, 2+ digit kept
        assert tokenize_evidence("1 12 123") == ["12", "123"]

    def test_dedup(self):
        assert tokenize_evidence("test test test") == ["test"]

    def test_unicode_nfkd(self):
        # Accented characters should be decomposed
        tokens = tokenize_evidence("café résumé naïve")
        assert "cafe" in tokens
        assert "resume" in tokens
        assert "naive" in tokens

    def test_empty(self):
        assert tokenize_evidence("") == []
        assert tokenize_evidence("the and but") == []


# ---------------------------------------------------------------------------
# extract_expected_evidence
# ---------------------------------------------------------------------------

class TestExtractExpectedEvidence:
    def test_explicit_terms(self):
        ev = extract_expected_evidence(
            {"expectedTerms": ["gravity", "force", "mass"]}, "what is gravity",
        )
        assert ev.source == "expectedTerms"
        assert "gravity" in ev.terms
        assert "force" in ev.terms
        assert ev.units == ev.terms  # explicit terms → units = terms

    def test_expected_terms_snake_case(self):
        ev = extract_expected_evidence(
            {"expected_terms": ["search", "ranking"]}, "query",
        )
        assert ev.source == "expected_terms"

    def test_required_facts(self):
        ev = extract_expected_evidence(
            {"requiredFacts": ["tax liability in 1977", "board policy"]}, "query",
        )
        assert ev.source == "requiredFacts"
        # Terms should be tokenized from facts
        assert "tax" in ev.terms
        assert "liability" in ev.terms
        assert "1977" in ev.terms
        # Units should be normalized fact phrases
        assert len(ev.units) == 2

    def test_fallback_to_query_text(self):
        ev = extract_expected_evidence({}, "what is gravity")
        assert ev.source == "query_terms"
        assert "gravity" in ev.terms
        assert ev.units == []

    def test_priority_order(self):
        # expectedTerms beats requiredFacts
        ev = extract_expected_evidence(
            {"expectedTerms": ["gravity"], "requiredFacts": ["force"]}, "query",
        )
        assert ev.source == "expectedTerms"

    def test_empty_annotations_list(self):
        ev = extract_expected_evidence(
            {"expectedTerms": []}, "fallback query text",
        )
        assert ev.source == "query_terms"


# ---------------------------------------------------------------------------
# coverage computation
# ---------------------------------------------------------------------------

class TestCoverageForText:
    def test_full_match(self):
        cov, matched = _coverage_for_text("gravity and force are related", ["gravity", "force"])
        assert cov == 1.0
        assert len(matched) == 2

    def test_partial_match(self):
        cov, matched = _coverage_for_text("gravity is real", ["gravity", "force", "mass"])
        assert abs(cov - 1 / 3) < 0.01
        assert matched == ["gravity"]

    def test_no_match(self):
        cov, matched = _coverage_for_text("unrelated text", ["gravity", "force"])
        assert cov == 0.0
        assert matched == []

    def test_empty_terms(self):
        cov, _ = _coverage_for_text("anything", [])
        assert cov == 0.0


class TestCoverageForUnits:
    def test_empty_units(self):
        cov, _ = _coverage_for_units("anything", [])
        assert cov is None

    def test_substring_match(self):
        cov, matched = _coverage_for_units(
            "the tax liability in 1977 was significant",
            ["tax liability"],
        )
        assert cov == 1.0
        assert "tax liability" in matched

    def test_token_set_match(self):
        # "liability" and "tax" appear but not as the exact phrase "tax liability"
        cov, matched = _coverage_for_units(
            "the liability for tax purposes was high",
            ["tax liability"],
        )
        assert cov == 1.0
        assert "tax liability" in matched

    def test_no_match(self):
        cov, matched = _coverage_for_units(
            "unrelated text about nothing",
            ["tax liability", "board policy"],
        )
        assert cov == 0.0
        assert matched == []


# ---------------------------------------------------------------------------
# excerpt extraction
# ---------------------------------------------------------------------------

class TestExcerptTextsFromHit:
    def test_from_regions(self):
        hit = {"excerptRegions": [{"text": "first"}, {"text": "second"}]}
        assert _excerpt_texts_from_hit(hit) == ["first", "second"]

    def test_empty_regions(self):
        hit = {"excerptRegions": [{"text": ""}]}
        # Falls through to fallback
        assert _excerpt_texts_from_hit(hit) == []

    def test_fallback_to_fields(self):
        hit = {"fields": {"content_preview": "preview text", "title": "A Title"}}
        texts = _excerpt_texts_from_hit(hit)
        assert "preview text" in texts
        assert "A Title" in texts

    def test_no_data(self):
        assert _excerpt_texts_from_hit({}) == []


# ---------------------------------------------------------------------------
# compute_query_coverage integration
# ---------------------------------------------------------------------------

class TestComputeQueryCoverage:
    def test_relevant_hit_with_excerpt(self):
        from jseval.types import EvidenceSpec

        response = {
            "query_id": "q1",
            "results": [
                {
                    "score": 5.0,
                    "fields": {"filename": "doc1.txt"},
                    "excerptRegions": [{"text": "Gravity and force are fundamental concepts"}],
                },
            ],
        }
        evidence = EvidenceSpec(source="test", terms=["gravity", "force", "mass"])
        qrels = {"doc1": 1}
        cov = compute_query_coverage(response, evidence, qrels)
        assert cov.relevant_doc_retrieved is True
        assert cov.relevant_excerpt_available is True
        assert cov.best_term_coverage is not None
        assert cov.best_term_coverage > 0.5

    def test_no_relevant_hits(self):
        from jseval.types import EvidenceSpec

        response = {
            "query_id": "q1",
            "results": [
                {"score": 5.0, "fields": {"filename": "irrelevant.txt"}},
            ],
        }
        evidence = EvidenceSpec(source="test", terms=["gravity"])
        qrels = {"doc1": 1}  # doc1 is relevant, but irrelevant.txt was returned
        cov = compute_query_coverage(response, evidence, qrels)
        assert cov.relevant_doc_retrieved is False
        assert cov.best_term_coverage is None


# ---------------------------------------------------------------------------
# summarize_coverage
# ---------------------------------------------------------------------------

class TestSummarizeCoverage:
    def test_empty(self):
        s = summarize_coverage([], [0.25, 0.5])
        assert s["query_count"] == 0

    def test_basic_rates(self):
        from jseval.types import QueryCoverage

        coverages = [
            QueryCoverage(
                qid="q1", expected_term_source="test",
                expected_term_count=3, expected_unit_count=0,
                relevant_doc_retrieved=True, relevant_excerpt_available=True,
                best_term_coverage=0.67, top1_term_coverage=0.67,
                best_unit_coverage=None, top1_unit_coverage=None,
                best_matched_term_count=2, best_matched_unit_count=0,
            ),
            QueryCoverage(
                qid="q2", expected_term_source="test",
                expected_term_count=2, expected_unit_count=0,
                relevant_doc_retrieved=True, relevant_excerpt_available=False,
                best_term_coverage=None, top1_term_coverage=None,
                best_unit_coverage=None, top1_unit_coverage=None,
                best_matched_term_count=0, best_matched_unit_count=0,
            ),
        ]
        s = summarize_coverage(coverages, [0.25, 0.5])
        assert s["query_count"] == 2
        assert s["relevant_doc_hit_rate"] == 1.0
        assert s["relevant_excerpt_available_rate"] == 0.5
        assert s["mean_best_term_coverage"] == 0.67
        assert s["context_hit_rate_gte_0_50"] == 0.5
