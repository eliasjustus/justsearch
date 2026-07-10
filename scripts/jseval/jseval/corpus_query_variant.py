"""Query-variant dataset generation (tempdoc 678 §Pillar-5 E5-C).

E5-C's query-shape sweep needs the SAME corpus + qrels as an existing local
(``golden/``/``mixed/``) dataset, with the queries transformed — to discriminate
whether dense/SPLADE near-dead recall on CLERC-shaped legal text is a query-shape
artifact (verbose citing-sentence queries dilute the query embedding) or a doc-side
one. This module builds two variants: ``keyword`` — a deterministic, LLM-free
keyword-extraction transform (the licensing-clean control the sweep needs) — and
``llm-reduced`` — a running-backend local-LLM rewrite of each verbose query into a
short natural-language search phrase (the realistic-query-shape sibling; needs a
live backend, see ``--api-url``). The transform registry (:data:`_TRANSFORMS`) is
the extension point both variants are registered through.

Single source -> one projection: ``corpus.jsonl`` and ``qrels/`` are copied
verbatim (unchanged — only the queries move), ``queries.jsonl``/``queries.json``
are rewritten with transformed query text, and a ``metadata.json`` records the
variant's identity/provenance. Deliberately does NOT copy ``corpus-dir/``: the
retrieval-eval ingestion path (``jseval.ingest._materialize_into``) always
re-derives materialized doc files from ``corpus.jsonl`` (never reads
``corpus-dir/`` back in), and the corpus itself is unchanged here, so
``corpus-dir/`` carries no information a query-variant consumer needs — see that
module's docstring for the "single source -> projections" contract this mirrors.
"""

from __future__ import annotations

import hashlib
import json
import re
import shutil
from collections import Counter
from dataclasses import dataclass
from pathlib import Path
from typing import Callable

import httpx

from .judge_ceiling import served_model_name

TOOL_VERSION = "jseval.corpus_query_variant/1"

# Output-name shorthand per variant (``--suffix`` defaults to this).
VARIANT_SUFFIXES: dict[str, str] = {
    "keyword": "kw",
    "llm-reduced": "llm",
}

_TOKEN_RE = re.compile(r"[a-z0-9]+")
_MIN_TOKEN_LEN = 3

# --- llm-reduced transform: fixed prompt + deterministic-sampling plumbing ------------------

# Verbatim, module-level fixed prompt (tempdoc 678 §Pillar-5 E5-C spec) — hashed into metadata
# (sha256) so a run's provenance ties to the EXACT prompt text used, not just a version number.
LLM_REDUCE_PROMPT = (
    "Rewrite the following legal text excerpt as a short natural-language search "
    "query (one line, 5-12 words) that a lawyer would type to find the case or "
    "document this excerpt cites or discusses. Output ONLY the query text, "
    "nothing else.\n\nExcerpt: {query}"
)

# Same relative endpoint judge_ceiling.make_chat_rank_fn posts to — the running backend's
# OpenAI-compatible chat-completions route (OpenAiCompatController), just pointed at a
# different base URL (the JustSearch backend, not a standalone llama-server).
LLM_REDUCED_ENDPOINT = "/v1/chat/completions"

_LLM_REDUCE_TIMEOUT = 300.0  # mirrors judge_ceiling.make_chat_rank_fn's default
_LLM_REDUCE_SEED = 42
_LLM_REDUCE_MAX_TOKENS = 64
_MAX_LLM_QUERY_CHARS = 200

# Deterministic-sampling params actually sent with every llm-reduced request (temperature 0 +
# fixed seed, mirroring judge_ceiling's enable_thinking=False guard against reasoning preamble
# eating the token budget) — recorded verbatim into metadata.json for provenance.
_SAMPLING_PARAMS: dict = {
    "temperature": 0.0,
    "seed": _LLM_REDUCE_SEED,
    "max_tokens": _LLM_REDUCE_MAX_TOKENS,
    "chat_template_kwargs": {"enable_thinking": False},
}


@dataclass
class LlmReduceContext:
    """Runtime context threaded into :func:`llm_reduced_variant` (api_url + discovered model
    identity + timeout) — the keyword transform's ``df``/``top_k`` equivalent for the LLM path."""

    api_url: str
    model: str | None
    timeout: float = _LLM_REDUCE_TIMEOUT


TransformFn = Callable[
    [str, "Counter[str]", int, "LlmReduceContext | None"], tuple[str, bool]
]


def _tokenize(text: str) -> list[str]:
    """Lowercase + split on non-alphanumeric — the one tokenization rule this module uses,
    shared by both DF computation and query transforms so they agree on what a "token" is."""
    return _TOKEN_RE.findall(text.lower())


def document_frequencies(corpus_path: Path) -> "Counter[str]":
    """Document frequency of every token across a ``corpus.jsonl`` (title + text), counting
    each token once per document regardless of how many times it appears in that document."""
    df: Counter[str] = Counter()
    for line in corpus_path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line:
            continue
        obj = json.loads(line)
        text = " ".join(part for part in (obj.get("title", ""), obj.get("text", "")) if part)
        df.update(set(_tokenize(text)))
    return df


def keyword_variant(
    text: str, df: "Counter[str]", top_k: int, llm_ctx: "LlmReduceContext | None" = None
) -> tuple[str, bool]:
    """Deterministic keyword-extraction transform (no randomness, no seed, no LLM).

    ``llm_ctx`` is accepted-and-ignored — it exists only so this function matches the shared
    :data:`TransformFn` signature ``build_query_variant`` calls uniformly across variants.

    Tokenize ``text`` the same way :func:`document_frequencies` tokenized the corpus; drop
    tokens shorter than :data:`_MIN_TOKEN_LEN` or absent from the corpus vocabulary (they
    carry no retrieval signal); rank the remaining DISTINCT tokens by ascending corpus DF
    (rarest = most discriminative), ties broken by first-occurrence order in the query; keep
    the top ``top_k`` by that ranking; re-emit the kept tokens in their ORIGINAL QUERY ORDER
    (order matters for phrase-ish behavior — this is not a bag-of-words shuffle).

    Returns ``(new_text, used_fallback)``. When zero tokens are eligible, falls back to the
    original ``text`` unchanged and reports ``used_fallback=True`` so the caller can count it.
    """
    tokens = _tokenize(text)
    first_occurrence: dict[str, int] = {}
    for i, tok in enumerate(tokens):
        if len(tok) < _MIN_TOKEN_LEN or tok not in df:
            continue
        if tok not in first_occurrence:
            first_occurrence[tok] = i

    if not first_occurrence:
        return text, True

    ranked = sorted(first_occurrence.items(), key=lambda kv: (df[kv[0]], kv[1]))
    kept_terms = {tok for tok, _ in ranked[:top_k]}

    seen: set[str] = set()
    ordered_unique: list[str] = []
    for tok in tokens:
        if tok in kept_terms and tok not in seen:
            seen.add(tok)
            ordered_unique.append(tok)
    return " ".join(ordered_unique), False


def _extract_query_line(raw: str) -> str:
    """Reduce a raw LLM response to the one-line query candidate.

    Splits on lines, drops blank ones, and takes the LAST non-empty line — some served models
    (reasoning-capable ones especially) prepend a preamble ("Sure, here's a search query:")
    despite the prompt's "Output ONLY the query text" instruction; the actual answer is reliably
    the final line. Caps at :data:`_MAX_LLM_QUERY_CHARS`. Returns ``""`` (never ``None``) when
    the response is empty/whitespace-only, so the caller's emptiness check is a simple falsy test.
    """
    lines = [ln.strip() for ln in (raw or "").splitlines() if ln.strip()]
    if not lines:
        return ""
    return lines[-1][:_MAX_LLM_QUERY_CHARS]


def _call_llm_reduce(api_url: str, model: str | None, query_text: str, timeout: float) -> str:
    """One chat-completion request for the llm-reduced transform.

    Mirrors ``judge_ceiling.make_chat_rank_fn``'s client/endpoint/timeout/parsing plumbing
    exactly: same ``httpx.post`` call shape, same relative endpoint
    (:data:`LLM_REDUCED_ENDPOINT`), same "``choices`` missing -> raise" response-shape check.
    Does not use judge_ceiling's ``response_format`` JSON-schema constraint (that schema is
    specific to its structured rerank-array task) — this transform asks for free natural-language
    text, parsed by :func:`_extract_query_line` instead. Raises on any transport/response failure;
    the caller (:func:`llm_reduced_variant`) is the fallback boundary.
    """
    prompt = LLM_REDUCE_PROMPT.format(query=query_text)
    body = {
        "model": model or "local",
        "messages": [{"role": "user", "content": prompt}],
        **_SAMPLING_PARAMS,
    }
    resp = httpx.post(f"{api_url}{LLM_REDUCED_ENDPOINT}", json=body, timeout=timeout)
    data = resp.json()
    if "choices" not in data:
        raise RuntimeError(data.get("error", {}).get("message", "no choices"))
    return data["choices"][0]["message"]["content"]


def llm_reduced_variant(
    text: str, df: "Counter[str]", top_k: int, llm_ctx: "LlmReduceContext | None"
) -> tuple[str, bool]:
    """Local-LLM natural-phrase query reduction (tempdoc 678 §Pillar-5 E5-C).

    ``df``/``top_k`` are accepted-and-ignored (kept only for :data:`TransformFn` signature
    uniformity with :func:`keyword_variant`). Requires ``llm_ctx`` (an :class:`LlmReduceContext`
    carrying ``api_url``) — raises if absent, since there is no meaningful fallback for a
    misconfigured caller (as opposed to a per-query runtime failure, which DOES fall back).

    Falls back to the unmodified original ``text`` — returning ``used_fallback=True`` so the
    caller counts it, the same contract :func:`keyword_variant` uses — on ANY per-query failure
    (network error, timeout, malformed/truncated response) or an empty/whitespace-only response,
    so one bad query never aborts the rest of the batch.
    """
    if llm_ctx is None:
        raise ValueError("llm_reduced_variant requires an LlmReduceContext (api_url)")
    try:
        raw = _call_llm_reduce(llm_ctx.api_url, llm_ctx.model, text, llm_ctx.timeout)
        candidate = _extract_query_line(raw)
    except Exception:  # noqa: BLE001 — any failure -> fall back to the original query text
        return text, True
    if not candidate:
        return text, True
    return candidate, False


_TRANSFORMS: dict[str, TransformFn] = {
    "keyword": keyword_variant,
    "llm-reduced": llm_reduced_variant,
}

_TRANSFORM_DESCRIPTIONS: dict[str, str] = {
    "keyword": (
        "Deterministic, LLM-free keyword extraction: tokenize on "
        r"[a-z0-9]+ over the lowercased query; drop tokens shorter than "
        f"{_MIN_TOKEN_LEN} chars or absent from the source corpus vocabulary; "
        "rank remaining distinct tokens by ascending corpus document frequency "
        "(rarest first, ties broken by first-occurrence order); keep the top "
        "top_k by that ranking; re-emit the kept tokens in their original "
        "query order. Falls back to the unmodified original query text when "
        "zero tokens are eligible."
    ),
    "llm-reduced": (
        "Local-LLM natural-phrase reduction: POSTs each query to the running backend's "
        "OpenAI-compatible /v1/chat/completions with a fixed prompt template and deterministic "
        "sampling (temperature 0, fixed seed), asking for a short (5-12 word) natural-language "
        "search phrase a lawyer would type. Takes the last non-empty line of the response "
        "(handles preamble/multi-line output), capped at 200 chars. Falls back to the "
        "unmodified original query text on any per-query failure or empty/whitespace response."
    ),
}


def available_variants() -> list[str]:
    return sorted(_TRANSFORMS)


def build_query_variant(
    source_dir: Path, dest_dir: Path, *, variant: str, top_k: int, api_url: str | None = None,
) -> dict:
    """Build a query-variant dataset at ``dest_dir`` from the local dataset at ``source_dir``.

    Copies ``corpus.jsonl`` and ``qrels/`` verbatim; rewrites ``queries.jsonl`` (and
    ``queries.json`` if present) with the transform applied to the query-text field; writes
    ``metadata.json`` with identity/provenance. Returns the written metadata dict.

    ``api_url`` is required for ``variant="llm-reduced"`` (the running backend's OpenAI-
    compatible base URL) and unused otherwise. The CLI layer additionally rejects
    ``--api-url`` for ``keyword`` (a UX guard against a mismatched-intent invocation); this
    function only enforces the llm-reduced side, so direct callers get the same protection.
    """
    if variant not in _TRANSFORMS:
        raise ValueError(
            f"Unknown variant: {variant!r}. Available: {available_variants()}"
        )
    if variant == "llm-reduced" and not api_url:
        raise ValueError(
            "variant='llm-reduced' requires api_url (the running backend's "
            "OpenAI-compatible endpoint, e.g. http://127.0.0.1:33221)"
        )
    transform = _TRANSFORMS[variant]

    corpus_path = source_dir / "corpus.jsonl"
    if not corpus_path.is_file():
        raise FileNotFoundError(f"Source corpus.jsonl not found: {corpus_path}")
    qrels_src = source_dir / "qrels"
    if not qrels_src.is_dir():
        raise FileNotFoundError(f"Source qrels/ not found: {qrels_src}")
    queries_jsonl_src = source_dir / "queries.jsonl"
    if not queries_jsonl_src.is_file():
        raise FileNotFoundError(f"Source queries.jsonl not found: {queries_jsonl_src}")

    df = document_frequencies(corpus_path)

    llm_model: str | None = None
    llm_ctx: LlmReduceContext | None = None
    if variant == "llm-reduced":
        # Discover the served model's identity the same way judge_ceiling's CLI wiring does
        # (its self-preference guardrail) -- best-effort, never raises; recorded into metadata
        # regardless so provenance shows exactly what model produced this dataset's queries.
        llm_model = served_model_name(api_url)
        llm_ctx = LlmReduceContext(api_url=api_url, model=llm_model)

    dest_dir.mkdir(parents=True, exist_ok=True)
    shutil.copy2(corpus_path, dest_dir / "corpus.jsonl")

    qrels_dest = dest_dir / "qrels"
    if qrels_dest.exists():
        shutil.rmtree(qrels_dest)
    shutil.copytree(qrels_src, qrels_dest)

    lines = [
        ln.strip()
        for ln in queries_jsonl_src.read_text(encoding="utf-8").splitlines()
        if ln.strip()
    ]
    total_queries = 0
    fallback_count = 0
    out_lines: list[str] = []
    for i, line in enumerate(lines, start=1):
        obj = json.loads(line)
        total_queries += 1
        new_text, used_fallback = transform(obj["text"], df, top_k, llm_ctx)
        if used_fallback:
            fallback_count += 1
        new_obj = dict(obj)
        new_obj["text"] = new_text
        out_lines.append(json.dumps(new_obj, ensure_ascii=False))
        if variant == "llm-reduced" and i % 25 == 0:
            print(f"[corpus-query-variant llm-reduced] {i}/{len(lines)} queries", flush=True)
    (dest_dir / "queries.jsonl").write_text(
        "".join(line + "\n" for line in out_lines), encoding="utf-8"
    )

    queries_json_src = source_dir / "queries.json"
    if queries_json_src.is_file():
        data = json.loads(queries_json_src.read_text(encoding="utf-8"))
        for entry in data:
            new_text, _used_fallback = transform(entry["query"], df, top_k, llm_ctx)
            entry["query"] = new_text
        (dest_dir / "queries.json").write_text(
            json.dumps(data, ensure_ascii=False, indent=1) + "\n", encoding="utf-8"
        )

    try:
        from .corpus_identity import corpus_signature

        source_signature = corpus_signature(source_dir)
    except Exception:
        source_signature = "unavailable"

    metadata = {
        "source_dataset": _dataset_display_name(source_dir),
        "source_corpus_signature": source_signature,
        "variant": variant,
        "top_k": top_k,
        "tool_version": TOOL_VERSION,
        "transform_description": _TRANSFORM_DESCRIPTIONS[variant],
        "total_queries": total_queries,
        "fallback_count": fallback_count,
    }
    if variant == "llm-reduced":
        metadata["llm_reduced"] = {
            "api_url": api_url,
            "endpoint": LLM_REDUCED_ENDPOINT,
            "prompt_sha256": hashlib.sha256(LLM_REDUCE_PROMPT.encode("utf-8")).hexdigest(),
            "model": llm_model,
            "sampling_params": dict(_SAMPLING_PARAMS),
            "fallback_count": fallback_count,
        }
    (dest_dir / "metadata.json").write_text(
        json.dumps(metadata, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    return metadata


def _dataset_display_name(source_dir: Path) -> str:
    """Best-effort ``golden/<name>`` / ``mixed/<name>`` display form of a resolved source dir."""
    parts = source_dir.parts
    for prefix in ("golden", "mixed"):
        if prefix in parts:
            idx = parts.index(prefix)
            return "/".join(parts[idx:])
    return source_dir.name
