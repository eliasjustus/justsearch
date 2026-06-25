"""Agent retrieval eval — measure JustSearch retrieve-context quality on MultiHop-RAG.

Phase 1: Retrieval quality (no agent, deterministic, $0 cost).
  Calls retrieve-context REST API for each question, checks if ground truth
  evidence documents appear in the retrieved chunks.

Phase 2: Agent comparison (requires Claude Code CLI, costs API tokens).
  Runs Claude Code with/without JustSearch MCP tools, scores answers against
  ground truth. Three conditions: A (file tools only), B (file + JustSearch),
  C (JustSearch only).
"""

from __future__ import annotations

import json
import logging
import re
import subprocess
import time
from dataclasses import dataclass, field, asdict
from pathlib import Path

import httpx

log = logging.getLogger(__name__)

_DEFAULT_BASE_URL = "http://127.0.0.1:33221"


@dataclass
class RetrievalResult:
    query: str
    answer: str
    question_type: str
    evidence_files: list[str]      # ground-truth article filenames
    retrieved_doc_ids: list[str] = field(default_factory=list)  # from chunk parent_doc_id
    context_tokens: int = 0
    chunks_included: int = 0
    chunks_considered: int = 0
    best_score: float = 0.0
    score_gap: float = 0.0
    coverage: float = 0.0
    retrieval_mode: str = ""
    evidence_found: int = 0
    evidence_total: int = 0
    answer_in_context: bool = False
    latency_ms: int = 0
    # Hits@K — was the first evidence doc found at rank <= K?
    hit_at_1: bool = False
    hit_at_3: bool = False
    hit_at_5: bool = False
    hit_at_10: bool = False
    reciprocal_rank: float = 0.0   # 1/rank of first evidence doc, 0 if not found


REFLECTION_PROMPT = (
    "Reflect on your experience answering the previous question using the tools available to you. "
    "What specific actions did you try that didn't work or produced unexpected results? "
    "What filter values, parameters, or query patterns did you attempt that failed? "
    "Be specific about exact tool calls and error conditions, not aspirational features."
)


_ABSTENTION_PHRASES = [
    "insufficient information",
    "cannot find",
    "unable to find",
    "not found in",
    "no articles",
    "does not contain",
    "not present in",
    "could not find",
    "no relevant",
    "no documents",
    "doesn't exist",
    "does not exist",
    "not in the corpus",
    "not in the index",
]


def _score_answer(ground_truth: str, agent_answer: str) -> bool:
    """Score whether the agent's answer is correct.

    For null queries (ground truth = "Insufficient information."),
    accept any answer that indicates the agent correctly abstained
    (e.g., "I cannot find...", "unable to find...", "no articles...").
    For all other queries, use exact substring matching.
    """
    gt = ground_truth.lower().strip().rstrip(".")
    answer = agent_answer.lower()
    if gt == "insufficient information":
        return any(phrase in answer for phrase in _ABSTENTION_PHRASES)
    return gt in answer


@dataclass
class AgentResult:
    query: str
    answer: str
    question_type: str
    condition: str
    model: str
    agent_answer: str = ""
    correct: bool = False
    cost_usd: float = 0.0
    input_tokens: int = 0
    cache_creation_tokens: int = 0
    cache_read_tokens: int = 0
    output_tokens: int = 0
    num_turns: int = 0
    duration_ms: int = 0
    error: str = ""
    reflection: str = ""
    tool_calls: list = field(default_factory=list)  # [{tool, params_summary, response_summary}]


def load_queries(queries_path: Path) -> list[dict]:
    """Load MultiHop-RAG eval queries from JSON file."""
    with open(queries_path, encoding="utf-8") as f:
        return json.load(f)


def build_title_to_filename(corpus_dir: Path) -> dict[str, str]:
    """Build mapping from article title -> filename by reading frontmatter.

    Handles encoding quirks: smart quotes, trailing special chars, truncation.
    Returns dict like {"The FTX trial is bigger than Sam Bankman-Fried": "article_042.md"}.
    """
    import re
    title_to_file: dict[str, str] = {}
    for fn in sorted(corpus_dir.iterdir()):
        if fn.suffix != ".md":
            continue
        with open(fn, encoding="utf-8") as f:
            header = f.read(500)
        m = re.search(r'title:\s*"(.+?)"', header)
        if m:
            title_to_file[m.group(1).strip()] = fn.name
    log.info("Built title→filename mapping: %d articles", len(title_to_file))
    return title_to_file


def _normalize_title(title: str) -> str:
    """Normalize a title for fuzzy matching.

    Strips smart quotes, non-ASCII chars, and lowercases. Handles the
    MultiHop-RAG dataset's encoding quirks (curly quotes, ellipsis, etc.).
    """
    # Replace common smart quote variants with ASCII equivalents
    t = title.replace("\u201c", '"').replace("\u201d", '"')
    t = t.replace("\u2018", "'").replace("\u2019", "'")
    t = t.replace("\u2026", "...").replace("\ufffd", "")
    return t.encode("ascii", "ignore").decode().lower().strip()


def resolve_evidence_titles(query: dict) -> list[str]:
    """Extract unique evidence titles from a query's evidence_list.

    Returns normalized titles (lowercase, stripped) for matching against
    retrieved document parent_doc_id paths.
    """
    titles: list[str] = []
    for ev in query.get("evidence_list", []):
        title = ev.get("title", "").strip()
        if title and title not in titles:
            titles.append(title)
    return titles


def _doc_id_matches_title(doc_id: str, evidence_title: str) -> bool:
    """Check if a parent_doc_id path contains the evidence title.

    parent_doc_id is a full path like:
      d:\\code\\...\\the ftx trial is bigger than sam bankman-fried.txt
    evidence_title is like:
      The FTX trial is bigger than Sam Bankman-Fried

    We extract the filename (without extension), lowercase both, and
    check if the evidence title is a prefix of the filename or vice versa.
    """
    # Extract filename without extension from path
    fn = doc_id.replace("\\", "/").split("/")[-1]
    fn_stem = fn.rsplit(".", 1)[0] if "." in fn else fn
    fn_lower = fn_stem.lower().strip()
    ev_lower = evidence_title.lower().strip()

    # Exact match or prefix match (titles may be truncated in filenames)
    if fn_lower == ev_lower:
        return True
    if fn_lower.startswith(ev_lower[:60]) or ev_lower.startswith(fn_lower[:60]):
        return True
    return False


def _extract_title_from_path(path: str) -> str:
    """Extract filename from a full path."""
    return path.replace("\\", "/").split("/")[-1]


# ============================================================
# Phase 1: Retrieval quality (no agent)
# ============================================================


def run_retrieval_eval(
    queries: list[dict],
    base_url: str = _DEFAULT_BASE_URL,
    top_k: int = 10,
    max_tokens: int = 8192,
    question_types: list[str] | None = None,
    max_queries: int | None = None,
    corpus_dir: Path | None = None,  # kept for CLI compat, not used for matching
) -> dict:
    """Run Tier 1 retrieval eval against the retrieve-context REST API.

    Calls retrieve-context for each query, then checks:
    - Whether ground-truth evidence documents appear in retrieved chunks
      (matched by comparing evidence titles against parent_doc_id paths)
    - Whether the ground-truth answer string appears in the context
    - Hits@K (K=1,3,5,10) and MRR for evidence document retrieval
    """
    client = httpx.Client(base_url=base_url, timeout=30.0)
    results: list[RetrievalResult] = []

    # Filter by question type if specified
    filtered = queries
    if question_types:
        filtered = [q for q in queries if q["question_type"] in question_types]
    if max_queries:
        filtered = filtered[:max_queries]

    log.info("Running retrieval eval: %d queries (top_k=%d, max_tokens=%d)",
             len(filtered), top_k, max_tokens)

    for i, q in enumerate(filtered):
        # Resolve evidence titles for this query
        evidence_titles = resolve_evidence_titles(q)

        start = time.monotonic()
        try:
            resp = client.post("/api/knowledge/retrieve-context", json={
                "query": q["query"],
                "top_k": top_k,
                "max_tokens": max_tokens,
            })
            resp.raise_for_status()
            data = resp.json()
        except Exception as e:
            log.warning("Query %d failed: %s", i, e)
            results.append(RetrievalResult(
                query=q["query"], answer=q["answer"],
                question_type=q["question_type"],
                evidence_files=evidence_titles,
                evidence_total=len(evidence_titles),
            ))
            continue

        latency = int((time.monotonic() - start) * 1000)
        quality = data.get("quality", {})
        chunks = data.get("chunks", [])
        context = data.get("context", "")

        # Extract unique parent_doc_ids from chunks, preserving rank order
        retrieved_doc_ids: list[str] = []
        for c in chunks:
            doc_id = c.get("parent_doc_id", "")
            if doc_id and doc_id not in retrieved_doc_ids:
                retrieved_doc_ids.append(doc_id)

        # Match evidence titles against retrieved doc IDs (by title in path)
        evidence_found = 0
        for ev_title in evidence_titles:
            if any(_doc_id_matches_title(did, ev_title) for did in retrieved_doc_ids):
                evidence_found += 1

        # Compute Hits@K and MRR: find rank of first evidence doc
        hit_at_1 = hit_at_3 = hit_at_5 = hit_at_10 = False
        reciprocal_rank = 0.0
        for rank_idx, doc_id in enumerate(retrieved_doc_ids):
            if any(_doc_id_matches_title(doc_id, ev) for ev in evidence_titles):
                rank = rank_idx + 1  # 1-indexed
                hit_at_1 = rank <= 1
                hit_at_3 = rank <= 3
                hit_at_5 = rank <= 5
                hit_at_10 = rank <= 10
                reciprocal_rank = 1.0 / rank
                break  # MRR uses first relevant result

        # Check if ground truth answer appears in context
        answer_in_context = q["answer"].lower() in context.lower()

        results.append(RetrievalResult(
            query=q["query"],
            answer=q["answer"],
            question_type=q["question_type"],
            evidence_files=evidence_titles,
            retrieved_doc_ids=retrieved_doc_ids,
            context_tokens=len(context) // 4,  # rough estimate
            chunks_included=quality.get("chunks_included", 0),
            chunks_considered=quality.get("chunks_considered", 0),
            best_score=quality.get("best_score", 0.0),
            score_gap=quality.get("score_gap", 0.0),
            coverage=quality.get("coverage", 0.0),
            retrieval_mode=quality.get("retrieval_mode", ""),
            evidence_found=evidence_found,
            evidence_total=len(evidence_titles),
            answer_in_context=answer_in_context,
            latency_ms=latency,
            hit_at_1=hit_at_1,
            hit_at_3=hit_at_3,
            hit_at_5=hit_at_5,
            hit_at_10=hit_at_10,
            reciprocal_rank=reciprocal_rank,
        ))

        if (i + 1) % 50 == 0 or (i + 1) == len(filtered):
            log.info("  %d/%d queries processed", i + 1, len(filtered))

    client.close()
    return _aggregate_retrieval(results)


def _aggregate_retrieval(results: list[RetrievalResult]) -> dict:
    """Aggregate retrieval results into summary metrics with Hits@K and MRR."""
    if not results:
        return {"error": "no results"}

    total = len(results)
    # Only compute evidence metrics on queries that have evidence files
    with_evidence = [r for r in results if r.evidence_total > 0]
    n_ev = len(with_evidence) if with_evidence else 1  # avoid div/0

    answer_in_context = sum(1 for r in results if r.answer_in_context)
    avg_tokens = sum(r.context_tokens for r in results) / total
    avg_chunks = sum(r.chunks_included for r in results) / total
    avg_latency = sum(r.latency_ms for r in results) / total
    avg_best_score = sum(r.best_score for r in results) / total
    avg_coverage = sum(r.coverage for r in results) / total

    # Standard retrieval metrics (only on queries with evidence)
    hits_at_1 = sum(1 for r in with_evidence if r.hit_at_1) / n_ev
    hits_at_3 = sum(1 for r in with_evidence if r.hit_at_3) / n_ev
    hits_at_5 = sum(1 for r in with_evidence if r.hit_at_5) / n_ev
    hits_at_10 = sum(1 for r in with_evidence if r.hit_at_10) / n_ev
    mrr = sum(r.reciprocal_rank for r in with_evidence) / n_ev
    avg_evidence_recall = (
        sum(r.evidence_found / r.evidence_total for r in with_evidence) / n_ev
    )

    # Per question type breakdown
    by_type: dict[str, list[RetrievalResult]] = {}
    for r in results:
        by_type.setdefault(r.question_type, []).append(r)

    type_summary = {}
    for qtype, typed_results in by_type.items():
        n = len(typed_results)
        typed_ev = [r for r in typed_results if r.evidence_total > 0]
        n_te = len(typed_ev) if typed_ev else 1
        type_summary[qtype] = {
            "count": n,
            "answer_in_context_rate": sum(1 for r in typed_results if r.answer_in_context) / n,
            "hits_at_1": sum(1 for r in typed_ev if r.hit_at_1) / n_te,
            "hits_at_5": sum(1 for r in typed_ev if r.hit_at_5) / n_te,
            "hits_at_10": sum(1 for r in typed_ev if r.hit_at_10) / n_te,
            "mrr": sum(r.reciprocal_rank for r in typed_ev) / n_te,
            "avg_evidence_recall": sum(r.evidence_found / r.evidence_total for r in typed_ev) / n_te if typed_ev else 0,
            "avg_context_tokens": sum(r.context_tokens for r in typed_results) / n,
            "avg_best_score": sum(r.best_score for r in typed_results) / n,
            "avg_latency_ms": sum(r.latency_ms for r in typed_results) / n,
        }

    return {
        "phase": "retrieval",
        "total_queries": total,
        "queries_with_evidence": len(with_evidence),
        "answer_in_context_rate": round(answer_in_context / total, 4),
        "hits_at_1": round(hits_at_1, 4),
        "hits_at_3": round(hits_at_3, 4),
        "hits_at_5": round(hits_at_5, 4),
        "hits_at_10": round(hits_at_10, 4),
        "mrr": round(mrr, 4),
        "avg_evidence_recall": round(avg_evidence_recall, 4),
        "avg_context_tokens": round(avg_tokens),
        "avg_chunks_included": round(avg_chunks, 1),
        "avg_latency_ms": round(avg_latency),
        "avg_best_score": round(avg_best_score, 3),
        "avg_coverage": round(avg_coverage, 3),
        "by_type": type_summary,
        "results": [asdict(r) for r in results],
    }


# ============================================================
# Tier 2: Single-shot RAG (retrieve + local LLM, $0 cost)
# ============================================================

_TIER2_SYSTEM_PROMPT = (
    "The context below is drawn from multiple documents and together contains "
    "the information needed to answer the question. Identify the relevant "
    "portions across all passages, synthesize them, and provide a direct answer. "
    "For yes/no questions, answer Yes or No. "
    "Only if after reviewing all passages the answer is genuinely absent, "
    "say \"Insufficient information\"."
)

# Paper's original prompt (from qa_llama.py in yixuantt/MultiHop-RAG repo).
# Used for ablation runs to isolate retrieval quality from prompt effects.
# Appended with structured format for observability (ANSWER/EVIDENCE/CONFIDENCE).
_TIER2_PAPER_PROMPT = (
    "Below is a question followed by some context from different sources. "
    "Please answer the question based on the context. The answer to the "
    "question is a word or entity. If the provided information is insufficient "
    "to answer the question, respond 'Insufficient Information'. "
    "Answer directly without explanation.\n\n"
    "Respond with EXACTLY this format:\n"
    "ANSWER: <your answer>\n"
    "EVIDENCE: <one sentence from the context that supports your answer>\n"
    "CONFIDENCE: high, medium, or low"
)

_TIER2_JSON_SCHEMA = {
    "type": "object",
    "properties": {
        "answer": {"type": "string"},
        "evidence_summary": {
            "type": "string",
            "description": "One sentence: what in the context supports this answer",
        },
        "confidence": {"type": "string", "enum": ["high", "medium", "low"]},
    },
    "required": ["answer", "evidence_summary", "confidence"],
    "additionalProperties": False,
}

_TIER2_ABSTENTION = [
    "insufficient information", "cannot find", "not found",
    "does not contain", "not present", "could not find",
    "no relevant", "cannot determine", "not mentioned",
    "no information", "not enough information", "unable to determine",
    "unable to find", "not available", "cannot answer",
]


@dataclass
class Tier2Result:
    query: str
    answer: str  # ground truth
    question_type: str
    llm_answer: str = ""
    evidence_summary: str = ""
    confidence: str = ""
    correct_exact: bool = False
    correct_substring: bool = False
    correct_has_intersection: bool = False  # paper's raw scoring
    retrieval_chunks: int = 0
    context_tokens: int = 0
    latency_retrieve_ms: int = 0
    latency_llm_ms: int = 0
    completion_tokens: int = 0
    error: str = ""


def _score_tier2(ground_truth: str, llm_answer: str) -> tuple[bool, bool, bool]:
    """Score with exact match, substring match, and paper's has_intersection.

    Returns (exact_match, substring_match, has_intersection).
    """
    gt = ground_truth.lower().strip().rstrip(".")
    ans = llm_answer.lower().strip().rstrip(".")

    if gt == "insufficient information":
        is_abstention = any(p in ans for p in _TIER2_ABSTENTION)
        return is_abstention, is_abstention, is_abstention

    exact = gt == ans
    substring = gt in ans

    # Paper's has_intersection: raw word split, any common word
    gt_words = set(ground_truth.lower().split())
    ans_words = set(llm_answer.lower().split())
    has_inter = len(gt_words & ans_words) > 0

    return exact, substring, has_inter


# ---- Source existence check (pre-retrieval abstention) ----

# Multi-word sources are safe to match as bare substrings (low collision risk).
# Single-word sources (nature, fortune, people) need publication-context patterns
# to avoid matching common English words.
_SINGLE_WORD_CONTEXT = re.compile(
    r"(?:article|report|piece|story|coverage|analysis)"
    r"(?:\s+(?:from|by|in|published\s+(?:by|in)))\s+([A-Z][A-Za-z]+)"
    r"|(?:from|by|according\s+to)\s+(?:an?\s+)?(?:article\s+(?:from|by|in)\s+)?"
    r"([A-Z][A-Za-z]+?)(?:\s+(?:article|report|,|and|about|detailing))"
    r"|'([A-Z][A-Za-z\s]+?)'\s*(?:article|report)"
)

# Non-corpus publications that appear in null queries (common news sources
# not in the MultiHop-RAG corpus). Extend as needed for other corpora.
_EXTRA_KNOWN_SOURCES = [
    "financial times", "the guardian", "the washington post",
    "al jazeera", "the telegraph", "daily mail", "sky news",
    "associated press", "usa today", "abc news", "nbc news",
    "politico", "the atlantic", "the economist", "rolling stone",
    "billboard", "tmz", "times of india", "hindustan times",
    "reuters", "bloomberg",
]


def _build_corpus_source_set(base_url: str, timeout: float = 30.0) -> set[str]:
    """Fetch available meta_source values from the search API facets."""
    import httpx as _hx
    try:
        # Use a broad query ("the") instead of "*" which fails Lucene's QueryParser.
        resp = _hx.post(
            f"{base_url}/api/knowledge/search",
            json={"query": "the", "limit": 0, "mode": "text",
                  "facets": {"include": True, "fields": [{"field": "meta_source", "size": 200}]}},
            timeout=timeout,
        )
        facets = resp.json().get("facets", {}).get("meta_source", {})
        return {k.lower().strip() for k in facets}
    except Exception as e:
        log.warning("Failed to fetch corpus sources for source check: %s", e)
        return set()


def _extract_mentioned_sources(query_text: str, corpus_sources: set[str]) -> list[str]:
    """Extract publication names mentioned in a query.

    Multi-word sources are matched as substrings (safe). Single-word sources
    are only matched in publication-context patterns to avoid false positives.
    """
    q_lower = query_text.lower()
    found: set[str] = set()

    # Multi-word sources: safe to match as substrings
    all_multi = [s for s in corpus_sources if len(s.split()) >= 2]
    all_multi += [s for s in _EXTRA_KNOWN_SOURCES if len(s.split()) >= 2]
    for src in all_multi:
        if src in q_lower:
            found.add(src)

    # Single-word sources: require publication context
    for m in _SINGLE_WORD_CONTEXT.finditer(query_text):
        for g in m.groups():
            if g:
                found.add(g.lower().strip())

    return list(found)


def _source_in_corpus(src: str, corpus_sources: set[str]) -> bool:
    """Check if a source name matches any corpus source (prefix match)."""
    return any(src in cs or cs.startswith(src) for cs in corpus_sources)


def _should_abstain_source_check(
    query_text: str, corpus_sources: set[str],
) -> tuple[bool, list[str]]:
    """Check if the query mentions sources absent from the corpus.

    Returns (should_abstain, absent_sources).
    """
    mentioned = _extract_mentioned_sources(query_text, corpus_sources)
    if not mentioned:
        return False, []
    absent = [s for s in mentioned if not _source_in_corpus(s, corpus_sources)]
    return len(absent) > 0, absent


def run_tier2_eval(
    queries: list[dict],
    base_url: str = _DEFAULT_BASE_URL,
    llm_url: str = "http://127.0.0.1:8080",
    top_k: int = 10,
    max_context_tokens: int = 8192,
    question_types: list[str] | None = None,
    max_queries: int | None = None,
    structured: bool = True,
    use_paper_prompt: bool = False,
    source_check: bool = False,
    checkpoint_dir: Path | None = None,
) -> dict:
    """Run Tier 2 single-shot RAG eval: retrieve-context + local LLM answer.

    Calls retrieve-context for each query, sends context + question to
    the local llama-server, scores the answer against ground truth.
    $0 cost (local LLM). Requires backend + llama-server running.

    When source_check=True, queries mentioning sources absent from the
    corpus are answered with "Insufficient Information" without calling
    the LLM (deterministic pre-retrieval abstention).
    """
    import httpx as _httpx

    client = _httpx.Client(base_url=base_url, timeout=30.0)
    results: list[Tier2Result] = []

    # Build corpus source set for source existence check
    corpus_sources: set[str] = set()
    source_check_abstentions = 0
    if source_check:
        corpus_sources = _build_corpus_source_set(base_url)
        log.info("Source check enabled: %d sources in corpus", len(corpus_sources))

    filtered = queries
    if question_types:
        filtered = [q for q in queries if q["question_type"] in question_types]
    if max_queries:
        filtered = filtered[:max_queries]

    log.info("Running Tier 2 eval: %d queries (top_k=%d, structured=%s, source_check=%s)",
             len(filtered), top_k, structured, source_check)

    for i, q in enumerate(filtered):
        result = Tier2Result(
            query=q["query"],
            answer=q["answer"],
            question_type=q["question_type"],
        )

        # Step 1: Retrieve context
        t0 = time.monotonic()
        try:
            resp = client.post("/api/knowledge/retrieve-context", json={
                "query": q["query"],
                "top_k": top_k,
                "max_tokens": max_context_tokens,
            })
            resp.raise_for_status()
            data = resp.json()
        except Exception as e:
            result.error = f"retrieve failed: {e}"
            results.append(result)
            continue

        context = data.get("context", "")
        result.retrieval_chunks = data.get("quality", {}).get("chunks_included", 0)
        result.context_tokens = len(context) // 4
        result.latency_retrieve_ms = int((time.monotonic() - t0) * 1000)

        # Source existence check: if query mentions sources not in corpus, abstain
        if source_check and corpus_sources:
            should_abstain, absent = _should_abstain_source_check(q["query"], corpus_sources)
            if should_abstain:
                result.llm_answer = "Insufficient Information"
                result.evidence_summary = f"source_check: absent sources {absent}"
                result.confidence = "deterministic"
                result.correct_exact, result.correct_substring, result.correct_has_intersection = (
                    _score_tier2(q["answer"], result.llm_answer)
                )
                source_check_abstentions += 1
                results.append(result)
                continue

        # Step 2: Call local LLM
        system_prompt = _TIER2_PAPER_PROMPT if use_paper_prompt else _TIER2_SYSTEM_PROMPT
        user_msg = f"Context:\n{context}\n\nQuestion: {q['query']}"
        llm_body: dict = {
            "model": "local",
            "messages": [
                {"role": "system", "content": system_prompt},
                {"role": "user", "content": user_msg},
            ],
            "max_tokens": 512,
            "temperature": 0.1,
            "chat_template_kwargs": {"enable_thinking": False},
        }
        # Always request logprobs for observability
        llm_body["logprobs"] = True
        llm_body["top_logprobs"] = 3

        if structured and not use_paper_prompt:
            llm_body["response_format"] = {
                "type": "json_schema",
                "json_schema": {
                    "name": "eval_answer",
                    "strict": True,
                    "schema": _TIER2_JSON_SCHEMA,
                },
            }

        t1 = time.monotonic()
        rdata = None
        for attempt in range(5):
            try:
                llm_resp = _httpx.post(
                    f"{llm_url}/v1/chat/completions",
                    json=llm_body,
                    timeout=120.0,
                )
                rdata = llm_resp.json()
                if "choices" in rdata:
                    break
                err_msg = rdata.get("error", {}).get("message", "")
                if "Loading model" in err_msg or "loading" in err_msg.lower():
                    log.warning("  Q%d attempt %d: model loading, waiting 10s...", i + 1, attempt + 1)
                    time.sleep(10)
                    continue
                # Non-recoverable LLM error
                break
            except Exception as e:
                if attempt < 4:
                    log.warning("  Q%d attempt %d failed: %s, retrying in 10s...", i + 1, attempt + 1, e)
                    time.sleep(10)
                    continue
                result.error = f"llm failed after 5 attempts: {e}"
                results.append(result)
                rdata = None
                break

        if rdata is None:
            if not result.error:
                result.error = "llm failed: no response after retries"
            results.append(result)
            continue
        if "choices" not in rdata:
            result.error = f"llm error: {rdata.get('error', {}).get('message', 'unknown')}"
            results.append(result)
            continue
        raw_content = rdata["choices"][0]["message"]["content"].strip()
        result.completion_tokens = rdata.get("usage", {}).get("completion_tokens", 0)

        result.latency_llm_ms = int((time.monotonic() - t1) * 1000)

        # Parse structured output (JSON schema or ANSWER: format)
        if "ANSWER:" in raw_content:
            # Parse ANSWER:/EVIDENCE:/CONFIDENCE: format
            for line in raw_content.split("\n"):
                line = line.strip()
                if line.upper().startswith("ANSWER:"):
                    result.llm_answer = line.split(":", 1)[1].strip()
                elif line.upper().startswith("EVIDENCE:"):
                    result.evidence_summary = line.split(":", 1)[1].strip()
                elif line.upper().startswith("CONFIDENCE:"):
                    result.confidence = line.split(":", 1)[1].strip().lower()
            if not result.llm_answer:
                result.llm_answer = raw_content
        elif structured and not use_paper_prompt:
            try:
                parsed = json.loads(raw_content)
                result.llm_answer = parsed.get("answer", "")
                result.evidence_summary = parsed.get("evidence_summary", "")
                result.confidence = parsed.get("confidence", "")
            except json.JSONDecodeError:
                result.llm_answer = raw_content
        else:
            result.llm_answer = raw_content

        # Score
        result.correct_exact, result.correct_substring, result.correct_has_intersection = (
            _score_tier2(q["answer"], result.llm_answer)
        )

        results.append(result)

        if (i + 1) % 50 == 0 or (i + 1) == len(filtered):
            correct_so_far = sum(1 for r in results if r.correct_substring)
            errors_so_far = sum(1 for r in results if r.error)
            log.info("  %d/%d processed (%d correct, %d errors)",
                     i + 1, len(filtered), correct_so_far, errors_so_far)

        # Checkpoint every 100 queries (resume-friendly for long runs)
        if checkpoint_dir and ((i + 1) % 100 == 0 or (i + 1) == len(filtered)):
            _save_checkpoint(checkpoint_dir, results, i + 1, len(filtered))

    client.close()
    if source_check:
        log.info("Source check abstentions: %d/%d", source_check_abstentions, len(filtered))
    return _aggregate_tier2(results)


def _save_checkpoint(checkpoint_dir: Path, results: list[Tier2Result], done: int, total: int) -> None:
    """Save intermediate results for crash recovery."""
    checkpoint_dir.mkdir(parents=True, exist_ok=True)
    cp_file = checkpoint_dir / "tier2-checkpoint.json"
    cp_data = {
        "done": done,
        "total": total,
        "results": [asdict(r) for r in results],
    }
    cp_file.write_text(json.dumps(cp_data, indent=2, default=str), encoding="utf-8")
    log.debug("  Checkpoint saved: %d/%d to %s", done, total, cp_file)


def _aggregate_tier2(results: list[Tier2Result]) -> dict:
    """Aggregate Tier 2 results."""
    if not results:
        return {"error": "no results"}

    total = len(results)
    errors = sum(1 for r in results if r.error)
    valid = [r for r in results if not r.error]
    n = len(valid) if valid else 1

    exact_correct = sum(1 for r in valid if r.correct_exact)
    substr_correct = sum(1 for r in valid if r.correct_substring)
    hi_correct = sum(1 for r in valid if r.correct_has_intersection)
    avg_latency_ret = sum(r.latency_retrieve_ms for r in valid) / n
    avg_latency_llm = sum(r.latency_llm_ms for r in valid) / n
    avg_ctx_tokens = sum(r.context_tokens for r in valid) / n
    avg_compl_tokens = sum(r.completion_tokens for r in valid) / n

    # Confidence distribution
    conf_dist = {}
    for r in valid:
        conf_dist[r.confidence] = conf_dist.get(r.confidence, 0) + 1

    # Per question type
    by_type: dict[str, list[Tier2Result]] = {}
    for r in results:
        by_type.setdefault(r.question_type, []).append(r)

    type_summary = {}
    for qtype, typed in by_type.items():
        tv = [r for r in typed if not r.error]
        tn = len(tv) if tv else 1
        type_summary[qtype] = {
            "count": len(typed),
            "errors": sum(1 for r in typed if r.error),
            "accuracy_exact": sum(1 for r in tv if r.correct_exact) / tn,
            "accuracy_substring": sum(1 for r in tv if r.correct_substring) / tn,
            "accuracy_has_intersection": sum(1 for r in tv if r.correct_has_intersection) / tn,
            "avg_latency_ms": round(sum(r.latency_retrieve_ms + r.latency_llm_ms for r in tv) / tn),
        }

    return {
        "tier": "tier2_single_shot_rag",
        "total_queries": total,
        "errors": errors,
        "accuracy_exact": round(exact_correct / n, 4),
        "accuracy_substring": round(substr_correct / n, 4),
        "accuracy_has_intersection": round(hi_correct / n, 4),
        "avg_latency_retrieve_ms": round(avg_latency_ret),
        "avg_latency_llm_ms": round(avg_latency_llm),
        "avg_context_tokens": round(avg_ctx_tokens),
        "avg_completion_tokens": round(avg_compl_tokens),
        "confidence_distribution": conf_dist,
        "by_type": type_summary,
        "results": [asdict(r) for r in results],
    }


def format_tier2_console(result: dict) -> str:
    """Format Tier 2 results for console output."""
    lines = [
        "=== Tier 2: Single-Shot RAG ===",
        f"Queries: {result['total_queries']} (errors: {result.get('errors', 0)})",
        "",
        "--- Accuracy ---",
        f"  has_intersection (paper): {result.get('accuracy_has_intersection', 0):.1%}",
        f"  Substring:               {result.get('accuracy_substring', 0):.1%}",
        f"  Exact match:             {result.get('accuracy_exact', 0):.1%}",
        "",
        "--- Latency ---",
        f"  Retrieve: {result.get('avg_latency_retrieve_ms', 0)}ms",
        f"  LLM:      {result.get('avg_latency_llm_ms', 0)}ms",
        f"  Total:    {result.get('avg_latency_retrieve_ms', 0) + result.get('avg_latency_llm_ms', 0)}ms",
        "",
        f"Avg context tokens: {result.get('avg_context_tokens', 0)}",
        f"Avg completion tokens: {result.get('avg_completion_tokens', 0)}",
        f"Confidence: {result.get('confidence_distribution', {})}",
        "",
        "--- By Question Type ---",
    ]
    for qtype, stats in sorted(result.get("by_type", {}).items()):
        lines.append(
            f"  {qtype}: n={stats['count']} "
            f"hi={stats.get('accuracy_has_intersection', 0):.0%} "
            f"substr={stats.get('accuracy_substring', 0):.0%} "
            f"exact={stats.get('accuracy_exact', 0):.0%} "
            f"avg={stats.get('avg_latency_ms', 0)}ms"
        )
    return "\n".join(lines)


# ============================================================
# Phase 2: Agent comparison (requires Claude Code CLI)
# ============================================================


def run_agent_eval(
    queries: list[dict],
    corpus_dir: str,
    mcp_config_path: str | None = None,
    model: str = "haiku",
    condition: str = "A",
    max_queries: int | None = None,
    max_budget_per_query: float = 0.50,
    question_types: list[str] | None = None,
    isolated: bool = True,
    parallel: int = 1,
) -> dict:
    """Run Phase 2 agent eval using Claude Code CLI.

    When isolated=True (default), runs from a temp directory outside
    the repo to avoid CLAUDE.md contamination. The agent sees no
    project-specific config — just a vanilla Claude Code instance.

    When parallel > 1, runs up to N queries concurrently via ThreadPoolExecutor.
    Each query gets its own temp directory. Results are logged as they complete.
    """
    filtered = queries
    if question_types:
        filtered = [q for q in queries if q["question_type"] in question_types]
    if max_queries:
        filtered = filtered[:max_queries]

    import shutil
    claude_bin = shutil.which("claude")
    if not claude_bin:
        return {"error": "claude CLI not found in PATH"}

    log.info("Running agent eval: %d queries, model=%s, condition=%s, parallel=%d",
             len(filtered), model, condition, parallel)

    def _run_single_query(i: int, q: dict) -> AgentResult:
        """Run a single eval query — self-contained, safe for concurrent execution."""
        import tempfile
        query_cwd = tempfile.mkdtemp(prefix=f"jseval-agent-{i}-") if isolated else corpus_dir

        prompt = (
            f"Answer the following question using only the documents in {corpus_dir}. "
            f"Do not use prior knowledge. Be concise. "
            f"Question: {q['query']}"
        )

        cmd = [
            claude_bin, "-p", prompt,
            "--model", model,
            "--output-format", "stream-json",
            "--verbose",
            "--max-budget-usd", str(max_budget_per_query),
            "--permission-mode", "bypassPermissions",
            "--add-dir", corpus_dir,
        ]

        if condition == "A":
            empty_mcp = Path(query_cwd) / "_empty_mcp.json"
            empty_mcp.write_text('{"mcpServers":{}}', encoding="utf-8")
            cmd.extend(["--strict-mcp-config", "--mcp-config", str(empty_mcp)])
        elif condition == "B":
            if mcp_config_path:
                cmd.extend(["--strict-mcp-config", "--mcp-config", mcp_config_path])
        elif condition == "C":
            if mcp_config_path:
                cmd.extend(["--strict-mcp-config", "--mcp-config", mcp_config_path])
            cmd.extend(["--disallowedTools", "Read,Grep,Glob"])

        result = AgentResult(
            query=q["query"], answer=q["answer"],
            question_type=q["question_type"],
            condition=condition, model=model,
        )

        try:
            proc = subprocess.run(
                cmd, capture_output=True, text=True, timeout=180,
                cwd=query_cwd, encoding="utf-8", errors="replace",
            )
            stdout = proc.stdout.strip()
            stderr = proc.stderr.strip() if proc.stderr else ""

            # Parse stream-json: extract tool calls and final result from line-delimited JSON
            tool_calls = []
            data = None
            session_id = ""
            for line in stdout.split("\n"):
                line = line.strip()
                if not line:
                    continue
                try:
                    event = json.loads(line)
                except json.JSONDecodeError:
                    continue
                etype = event.get("type", "")
                if etype == "assistant":
                    msg = event.get("message", {})
                    for block in msg.get("content", []):
                        if block.get("type") == "tool_use":
                            tool_name = block.get("name", "")
                            tool_input = block.get("input", {})
                            # Summarize params (keep it compact for storage)
                            summary = {}
                            for k, v in tool_input.items():
                                if isinstance(v, str) and len(v) > 100:
                                    summary[k] = v[:100] + "..."
                                elif isinstance(v, (dict, list)):
                                    summary[k] = json.dumps(v)[:100]
                                else:
                                    summary[k] = v
                            tool_calls.append({"tool": tool_name, "input": summary})
                        elif block.get("type") == "tool_result":
                            # Attach response summary to last tool call
                            if tool_calls:
                                content = block.get("content", "")
                                if isinstance(content, list):
                                    content = " ".join(
                                        c.get("text", "")[:200] for c in content if isinstance(c, dict)
                                    )
                                elif isinstance(content, str):
                                    content = content[:200]
                                tool_calls[-1]["response_preview"] = str(content)[:200]
                elif etype == "result":
                    data = event
                    session_id = event.get("session_id", "")

            result.tool_calls = tool_calls

            if data is None:
                result.error = stderr[:500] if stderr else f"exit code {proc.returncode}"
            elif data.get("is_error"):
                result.error = data.get("result", "unknown error")
                result.agent_answer = data.get("result", "")
                result.cost_usd = data.get("total_cost_usd", 0.0)
                result.duration_ms = data.get("duration_ms", 0)
                result.num_turns = data.get("num_turns", 0)
                usage = data.get("usage", {})
                result.input_tokens = usage.get("input_tokens", 0)
                result.cache_creation_tokens = usage.get("cache_creation_input_tokens", 0)
                result.cache_read_tokens = usage.get("cache_read_input_tokens", 0)
                result.output_tokens = usage.get("output_tokens", 0)
            else:
                result.agent_answer = data.get("result", "")
                result.cost_usd = data.get("total_cost_usd", 0.0)
                result.duration_ms = data.get("duration_ms", 0)
                result.num_turns = data.get("num_turns", 0)
                usage = data.get("usage", {})
                result.input_tokens = usage.get("input_tokens", 0)
                result.cache_creation_tokens = usage.get("cache_creation_input_tokens", 0)
                result.cache_read_tokens = usage.get("cache_read_input_tokens", 0)
                result.output_tokens = usage.get("output_tokens", 0)
                result.correct = _score_answer(q["answer"], result.agent_answer)

                # Reflection: run synchronously within this query's thread
                if condition in ("B", "C") and not result.error and session_id:
                    result.reflection = _get_reflection(claude_bin, session_id, query_cwd)

        except subprocess.TimeoutExpired:
            result.error = "timeout (180s)"
        except Exception as e:
            result.error = str(e)

        log.info("  [%d/%d] %s model=%s correct=%s cost=$%.3f turns=%d",
                 i + 1, len(filtered), condition, model,
                 result.correct, result.cost_usd, result.num_turns)
        if result.reflection:
            log.info("    reflection: %s", result.reflection[:150])
        return result

    # Execute queries: sequential (parallel=1) or concurrent (parallel>1)
    if parallel <= 1:
        results = [_run_single_query(i, q) for i, q in enumerate(filtered)]
    else:
        import concurrent.futures
        results = [None] * len(filtered)
        with concurrent.futures.ThreadPoolExecutor(max_workers=parallel) as executor:
            future_to_idx = {
                executor.submit(_run_single_query, i, q): i
                for i, q in enumerate(filtered)
            }
            for future in concurrent.futures.as_completed(future_to_idx):
                idx = future_to_idx[future]
                try:
                    results[idx] = future.result()
                except Exception as e:
                    results[idx] = AgentResult(
                        query=filtered[idx]["query"],
                        answer=filtered[idx]["answer"],
                        question_type=filtered[idx]["question_type"],
                        condition=condition, model=model, error=str(e),
                    )

    return _aggregate_agent(results, condition, model)


def _get_reflection(claude_bin: str, session_id: str, cwd: str) -> str:
    """Resume the agent session and ask for reflection on tool usage."""
    try:
        proc = subprocess.run(
            [claude_bin, "-p", REFLECTION_PROMPT,
             "--resume", session_id,
             "--output-format", "json",
             "--max-budget-usd", "0.50",
             "--permission-mode", "bypassPermissions"],
            capture_output=True, text=True, timeout=60, cwd=cwd,
        )
        stdout = proc.stdout.strip()
        if stdout:
            data = json.loads(stdout)
            return data.get("result", "")
        else:
            stderr = proc.stderr.strip() if proc.stderr else ""
            log.debug("Reflection empty output (rc=%d): %s", proc.returncode, stderr[:200])
    except Exception as e:
        log.debug("Reflection failed: %s", e)
    return ""


def _aggregate_agent(results: list[AgentResult], condition: str, model: str) -> dict:
    """Aggregate agent results into summary metrics."""
    if not results:
        return {"error": "no results"}

    total = len(results)
    errors = sum(1 for r in results if r.error)
    valid = [r for r in results if not r.error]
    n_valid = len(valid)

    if n_valid == 0:
        return {
            "phase": "agent", "condition": condition, "model": model,
            "total_queries": total, "errors": errors, "error": "all queries failed",
            "results": [asdict(r) for r in results],
        }

    correct = sum(1 for r in valid if r.correct)
    avg_cost = sum(r.cost_usd for r in valid) / n_valid
    avg_turns = sum(r.num_turns for r in valid) / n_valid
    avg_duration = sum(r.duration_ms for r in valid) / n_valid
    avg_cache_creation = sum(r.cache_creation_tokens for r in valid) / n_valid

    return {
        "phase": "agent",
        "condition": condition,
        "model": model,
        "total_queries": total,
        "errors": errors,
        "accuracy": correct / n_valid,
        "avg_cost_usd": round(avg_cost, 4),
        "avg_turns": round(avg_turns, 1),
        "avg_duration_ms": round(avg_duration),
        "avg_unique_content_tokens": round(avg_cache_creation),
        "total_cost_usd": round(sum(r.cost_usd for r in valid), 4),
        "results": [asdict(r) for r in results],
    }


# ============================================================
# Console formatting
# ============================================================


def format_retrieval_console(result: dict) -> str:
    """Format Phase 1 retrieval results for console output."""
    lines = [
        "=== Retrieval Quality (Tier 1) ===",
        f"Queries: {result['total_queries']} ({result.get('queries_with_evidence', '?')} with evidence)",
        "",
        "--- Standard Metrics ---",
        f"  Hits@1:  {result.get('hits_at_1', 0):.1%}",
        f"  Hits@3:  {result.get('hits_at_3', 0):.1%}",
        f"  Hits@5:  {result.get('hits_at_5', 0):.1%}",
        f"  Hits@10: {result.get('hits_at_10', 0):.1%}",
        f"  MRR:     {result.get('mrr', 0):.4f}",
        f"  Evidence recall: {result.get('avg_evidence_recall', 0):.1%}",
        f"  Answer in context: {result['answer_in_context_rate']:.1%}",
        "",
        "--- Pipeline Stats ---",
        f"  Avg context tokens: {result['avg_context_tokens']}",
        f"  Avg chunks included: {result['avg_chunks_included']}",
        f"  Avg latency: {result['avg_latency_ms']}ms",
        f"  Avg best score: {result['avg_best_score']}",
        f"  Avg coverage: {result['avg_coverage']:.1%}",
        "",
        "--- By Question Type ---",
    ]
    for qtype, stats in sorted(result.get("by_type", {}).items()):
        lines.append(
            f"  {qtype}: n={stats['count']} "
            f"Hits@1={stats.get('hits_at_1', 0):.0%} "
            f"Hits@10={stats.get('hits_at_10', 0):.0%} "
            f"MRR={stats.get('mrr', 0):.3f} "
            f"answer_in_ctx={stats['answer_in_context_rate']:.0%}"
        )
    return "\n".join(lines)


def format_agent_console(result: dict) -> str:
    """Format Phase 2 agent results for console output."""
    condition = result.get('condition', '?')
    model = result.get('model', '?')
    lines = [
        f"=== Phase 2: Agent Comparison (condition={condition}, model={model}) ===",
        f"Queries: {result.get('total_queries', result.get('total', 0))} "
        f"(errors: {result.get('errors', 0)})",
        f"Accuracy: {result.get('accuracy', 0):.1%}",
        f"Avg cost: ${result.get('avg_cost_usd', 0):.4f}",
        f"Avg turns: {result.get('avg_turns', 0)}",
        f"Avg duration: {result.get('avg_duration_ms', 0)}ms",
        f"Avg unique content tokens: {result.get('avg_unique_content_tokens', 0)}",
        f"Total cost: ${result.get('total_cost_usd', 0):.4f}",
    ]
    return "\n".join(lines)
