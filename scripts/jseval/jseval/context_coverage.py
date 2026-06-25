"""Context/excerpt quality evaluation — token coverage of expected evidence."""

from __future__ import annotations

import re
import unicodedata

from . import retriever
from .types import EvidenceSpec, QueryCoverage

STOPWORDS = frozenset({
    "a", "an", "and", "are", "as", "at", "be", "been", "being", "but", "by",
    "can", "could", "did", "do", "does", "for", "from", "had", "has", "have",
    "he", "her", "hers", "him", "his", "how", "if", "in", "into", "is", "it",
    "its", "itself", "may", "might", "must", "no", "not", "of", "on", "or",
    "our", "ours", "she", "should", "so", "than", "that", "the", "their",
    "theirs", "them", "themselves", "then", "there", "these", "they", "this",
    "those", "to", "under", "until", "up", "was", "were", "what", "when",
    "where", "which", "while", "who", "whom", "why", "will", "with", "would",
    "you", "your", "yours",
})

_TOKEN_RE = re.compile(r"[a-z0-9]+")
_DIGIT_RE = re.compile(r"^\d+$")
_NON_ALNUM_RE = re.compile(r"[^a-z0-9\s]+")


def tokenize_evidence(text: str) -> list[str]:
    """Tokenize text for evidence matching.

    NFKD normalize → strip combining marks → lowercase → extract [a-z0-9]+ runs →
    remove stopwords → min-length filter (3+ for words, 2+ for numbers) → dedup.
    """
    decomposed = unicodedata.normalize("NFKD", text)
    # Strip combining marks (accents) so "résumé" → "resume"
    normalized = "".join(
        c for c in decomposed if unicodedata.category(c) != "Mn"
    ).lower()
    tokens = _TOKEN_RE.findall(normalized)
    out: list[str] = []
    seen: set[str] = set()
    for t in tokens:
        if t in STOPWORDS or t in seen:
            continue
        if _DIGIT_RE.match(t):
            if len(t) < 2:
                continue
        elif len(t) < 3:
            continue
        seen.add(t)
        out.append(t)
    return out


def normalize_evidence_text(text: str) -> str:
    """Normalize text for coverage matching: NFKD → lowercase → strip non-alnum."""
    normalized = unicodedata.normalize("NFKD", text).lower()
    return _NON_ALNUM_RE.sub(" ", normalized).strip()


def _normalize_explicit_terms(raw: list) -> list[str]:
    """Normalize an explicit term list (from annotations)."""
    out: list[str] = []
    seen: set[str] = set()
    for term in raw:
        t = normalize_evidence_text(str(term))
        if not t or t in seen:
            continue
        # Single-word terms must be >= 3 chars (or 2+ digit number)
        words = t.split()
        if len(words) == 1:
            if _DIGIT_RE.match(t) and len(t) < 2:
                continue
            if not _DIGIT_RE.match(t) and len(t) < 3:
                continue
        seen.add(t)
        out.append(t)
    return out


def extract_expected_evidence(annotations: dict, query_text: str) -> EvidenceSpec:
    """Extract expected evidence from query annotations or fallback to query text.

    Priority:
    1. Explicit term fields (expectedTerms, expected_terms, etc.)
    2. Required facts fields (requiredFacts, required_facts)
    3. Fallback: tokenized query text
    """
    # Stage 1: explicit term fields
    for field_name in (
        "expectedTerms", "expected_terms", "evidenceTerms",
        "evidence_terms", "expectedKeywords", "expected_keywords",
    ):
        val = annotations.get(field_name)
        if val and isinstance(val, list) and len(val) > 0:
            normalized = _normalize_explicit_terms(val)
            if normalized:
                return EvidenceSpec(source=field_name, terms=normalized, units=normalized)

    # Stage 2: fact fields
    for field_name in ("requiredFacts", "required_facts"):
        val = annotations.get(field_name)
        if val and isinstance(val, list) and len(val) > 0:
            # Terms: tokenize all facts, deduplicate across
            all_tokens: list[str] = []
            seen: set[str] = set()
            for fact in val:
                for t in tokenize_evidence(str(fact)):
                    if t not in seen:
                        seen.add(t)
                        all_tokens.append(t)
            # Units: normalized fact phrases
            units = _normalize_explicit_terms(val)
            if all_tokens or units:
                return EvidenceSpec(source=field_name, terms=all_tokens, units=units)

    # Stage 3: fallback to query text
    return EvidenceSpec(
        source="query_terms",
        terms=tokenize_evidence(query_text),
        units=[],
    )


def _excerpt_texts_from_hit(hit: dict) -> list[str]:
    """Extract excerpt text from a hit, with fallback to content fields."""
    # Primary: excerptRegions
    regions = hit.get("excerptRegions")
    if isinstance(regions, list):
        texts = [r["text"] for r in regions if isinstance(r.get("text"), str) and r["text"]]
        if texts:
            return texts

    # Fallback: fields
    fields = hit.get("fields") or {}
    fallback: list[str] = []
    for key in ("content_preview", "title", "filename"):
        val = fields.get(key)
        if isinstance(val, str) and val:
            fallback.append(val)
    return fallback


def _coverage_for_text(haystack: str, terms: list[str]) -> tuple[float, list[str]]:
    """Compute term coverage: substring match each term in normalized haystack."""
    if not terms:
        return 0.0, []
    normalized = unicodedata.normalize("NFKD", haystack).lower()
    matched = [t for t in terms if t in normalized]
    return len(matched) / len(terms), matched


def _coverage_for_units(
    haystack: str, units: list[str],
) -> tuple[float | None, list[str]]:
    """Compute unit coverage: substring match or token-set containment."""
    if not units:
        return None, []
    normalized = normalize_evidence_text(haystack)
    haystack_tokens = set(tokenize_evidence(haystack))
    matched: list[str] = []
    for unit in units:
        if unit in normalized:
            matched.append(unit)
        else:
            unit_tokens = tokenize_evidence(unit)
            if unit_tokens and all(t in haystack_tokens for t in unit_tokens):
                matched.append(unit)
    return len(matched) / len(units), matched


def compute_query_coverage(
    raw_response: dict,
    evidence: EvidenceSpec,
    qrels_for_query: dict[str, int],
) -> QueryCoverage:
    """Compute context coverage for a single query's response."""
    hits = raw_response.get("results", [])

    relevant_doc_retrieved = False
    relevant_excerpt_available = False
    best_term_cov = None
    best_term_count = 0
    best_unit_cov = None
    best_unit_count = 0
    top1_term_cov = None
    top1_unit_cov = None

    for rank, hit in enumerate(hits):
        try:
            doc_id = retriever.resolve_doc_id(hit)
        except ValueError:
            continue

        rel = qrels_for_query.get(doc_id, 0)
        if rel <= 0:
            continue

        relevant_doc_retrieved = True
        excerpts = _excerpt_texts_from_hit(hit)
        if not excerpts:
            continue

        relevant_excerpt_available = True
        combined = " ".join(excerpts)

        tc, tc_matched = _coverage_for_text(combined, evidence.terms)
        uc, uc_matched = _coverage_for_units(combined, evidence.units)

        if best_term_cov is None or tc > best_term_cov:
            best_term_cov = tc
            best_term_count = len(tc_matched)
        if uc is not None and (best_unit_cov is None or uc > best_unit_cov):
            best_unit_cov = uc
            best_unit_count = len(uc_matched)

        if rank == 0:
            top1_term_cov = tc
            top1_unit_cov = uc

    return QueryCoverage(
        qid=raw_response.get("query_id", ""),
        expected_term_source=evidence.source,
        expected_term_count=len(evidence.terms),
        expected_unit_count=len(evidence.units),
        relevant_doc_retrieved=relevant_doc_retrieved,
        relevant_excerpt_available=relevant_excerpt_available,
        best_term_coverage=best_term_cov,
        top1_term_coverage=top1_term_cov,
        best_unit_coverage=best_unit_cov,
        top1_unit_coverage=top1_unit_cov,
        best_matched_term_count=best_term_count,
        best_matched_unit_count=best_unit_count,
    )


def summarize_coverage(
    coverages: list[QueryCoverage],
    thresholds: list[float],
) -> dict:
    """Aggregate per-query coverage into a summary dict."""
    total = len(coverages)
    if total == 0:
        result: dict = {
            "query_count": 0,
            "relevant_doc_hit_rate": 0,
            "relevant_excerpt_available_rate": 0,
            "mean_best_term_coverage": None,
            "mean_top1_term_coverage": None,
            "mean_best_unit_coverage": None,
            "mean_top1_unit_coverage": None,
            "annotated_unit_query_count": 0,
        }
        for t in thresholds:
            key = f"context_hit_rate_gte_{t:.2f}".replace(".", "_")
            result[key] = 0
        return result

    doc_hits = sum(1 for c in coverages if c.relevant_doc_retrieved)
    excerpt_avail = sum(1 for c in coverages if c.relevant_excerpt_available)
    annotated = sum(1 for c in coverages if c.expected_unit_count > 0)

    def _mean_non_none(values: list[float | None]) -> float | None:
        valid = [v for v in values if v is not None]
        return sum(valid) / len(valid) if valid else None

    result = {
        "query_count": total,
        "relevant_doc_hit_rate": doc_hits / total,
        "relevant_excerpt_available_rate": excerpt_avail / total,
        "mean_best_term_coverage": _mean_non_none(
            [c.best_term_coverage for c in coverages],
        ),
        "mean_top1_term_coverage": _mean_non_none(
            [c.top1_term_coverage for c in coverages],
        ),
        "mean_best_unit_coverage": _mean_non_none(
            [c.best_unit_coverage for c in coverages],
        ),
        "mean_top1_unit_coverage": _mean_non_none(
            [c.top1_unit_coverage for c in coverages],
        ),
        "annotated_unit_query_count": annotated,
        "query_terms_fallback_count": sum(
            1 for c in coverages if c.expected_term_source == "query_terms"
        ),
        "query_terms_fallback_rate": sum(
            1 for c in coverages if c.expected_term_source == "query_terms"
        ) / total,
    }
    for t in thresholds:
        key = f"context_hit_rate_gte_{t:.2f}".replace(".", "_")
        count = sum(
            1 for c in coverages
            if c.best_term_coverage is not None and c.best_term_coverage >= t
        )
        result[key] = count / total

    return result
