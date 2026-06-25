"""LLM judge-ceiling probe (tempdoc 636 §5) — *realistic* judge headroom.

The ``staged_recall_accounting`` projection already gives the **AI-free ceiling**:
a *perfect* judge over the leg-union pool would reach nDCG = ``leg_union_recall``,
so ``judge_headroom_ceiling = leg_union_recall − final_ndcg`` bounds what any
better judge could add given the current recall. This probe answers the *next*
question — **how much of that ceiling a real local LLM captures** — by reranking
the same leg-union pool with the model and comparing nDCG@10 to the run's final.

It is the lower-confidence, AI-dependent sibling (tempdoc 636 confidence pass):

- **Bias guardrails (literature pass):** LLM-as-judge has position / self-preference
  / length bias. We apply the **order-swap** guardrail — rank the pool forward and
  reversed and report top-1 agreement as a *position-sensitivity* band — and treat
  the number as a **biased ceiling estimate, not truth** (with a single local model,
  self-preference cannot be removed; the signal is coarse "is there headroom").
  **Self-preference guardrail (external-research Finding 2):** the judge model should
  be **different** from any model the engine uses as a *generator* (a judge favours its
  own generations). The CLI emits a non-fatal warning when the served judge model name
  matches an engine-generator hint; choose a distinct judge for a trustworthy ceiling.
- **Graceful degradation:** if the AI runtime is unavailable, the live rank_fn raises
  :class:`AIUnavailable`; the CLI reports ``AI_UNAVAILABLE`` and exits 0 — the
  projection's AI-free ceiling already stands.

The orchestrator :func:`judge_ceiling_report` is pure over a ``rank_fn`` callable,
so the bias/scoring logic is unit-testable with a mock ranker (no live model).
"""

from __future__ import annotations

import json
import logging
from pathlib import Path
from typing import Callable

import ir_measures

from . import scoring
from .projections import staged_recall_accounting as _sra

log = logging.getLogger(__name__)

# (query_text, candidate_doc_ids, id->text) -> reranked doc_ids
RankFn = Callable[[str, list[str], dict[str, str]], list[str]]


class AIUnavailable(RuntimeError):
    """Raised when the live LLM rank_fn cannot reach an active model."""


def assemble_pool(run_dir: Path, top_n: int | None = None) -> dict[str, list[str]]:
    """Leg-union candidate pool ``{qid: [doc_id]}`` from a run's leg modes."""
    present = _sra._present_modes(run_dir)
    legs = [m for m in _sra.LEG_MODES if m in present]
    pool: dict[str, list[str]] = {}
    for m in legs:
        for qid, docs in _sra._ranked_by_qid(run_dir, m).items():
            seen = pool.setdefault(qid, [])
            for d in (docs[:top_n] if top_n else docs):
                if d not in seen:
                    seen.append(d)
    return pool


def _score_ranking(qrels: dict, rankings: dict[str, list[str]]) -> float | None:
    """nDCG@10 of ``{qid: [doc_id in rank order]}`` against ``qrels``."""
    scored = [
        ir_measures.ScoredDoc(query_id=qid, doc_id=d, score=float(len(ids) - i))
        for qid, ids in rankings.items()
        for i, d in enumerate(ids)
    ]
    if not scored:
        return None
    return scoring.evaluate(qrels, scored).get("nDCG@10")


def judge_ceiling_report(
    pool: dict[str, list[str]],
    queries: dict[str, str],
    qrels: dict,
    rank_fn: RankFn,
    *,
    final_ndcg: float | None,
    ceiling: float | None = None,
    texts: dict[str, str] | None = None,
) -> dict:
    """Rerank the pool with ``rank_fn`` (+ order-swap) and score the headroom.

    Pure: the LLM lives behind ``rank_fn``, so this is unit-testable with a mock.
    """
    texts = texts or {}
    fwd_rankings: dict[str, list[str]] = {}
    checked = 0
    top1_agree = 0
    for qid, cands in pool.items():
        if not cands or qid not in qrels:
            continue
        q = queries.get(qid, "")
        fwd = rank_fn(q, cands, texts)
        rev = rank_fn(q, list(reversed(cands)), texts)  # order-swap guardrail
        checked += 1
        if fwd and rev and fwd[0] == rev[0]:
            top1_agree += 1
        fwd_rankings[qid] = fwd

    llm_ndcg = _score_ranking(qrels, fwd_rankings)
    realized = (llm_ndcg - final_ndcg) if (llm_ndcg is not None and final_ndcg is not None) else None
    capture = (
        (realized / ceiling) if (realized is not None and ceiling not in (None, 0)) else None
    )
    return {
        "status": "ok",
        "n_queries": len(fwd_rankings),
        "llm_ndcg": llm_ndcg,
        "final_ndcg": final_ndcg,
        "judge_headroom_ceiling": ceiling,
        "headroom_realized": realized,
        "capture_fraction": capture,
        "position_sensitivity": {
            "checked": checked,
            "top1_agreement": (top1_agree / checked) if checked else None,
        },
        "caveat": "biased ceiling estimate (single local model → self-preference not removable); coarse signal only",
    }


def make_chat_rank_fn(llm_url: str, model: str = "local", timeout: float = 120.0) -> RankFn:
    """Live OpenAI-compatible rank_fn (reuses the agent-eval chat path).

    POSTs to ``{llm_url}/v1/chat/completions`` with a structured-output rerank
    request. Raises :class:`AIUnavailable` on any failure so the caller degrades.
    """
    import httpx

    schema = {
        "type": "object",
        "properties": {"ranking": {"type": "array", "items": {"type": "string"}}},
        "required": ["ranking"],
        "additionalProperties": False,
    }
    sys_prompt = (
        "You are a search relevance judge. Given a query and candidate documents "
        "(each shown as 'id: text'), return the candidate ids ordered most-relevant "
        "first. Use only the given ids. Respond as JSON {\"ranking\": [ids...]}."
    )

    def rank_fn(query: str, cand_ids: list[str], texts: dict[str, str]) -> list[str]:
        listing = "\n".join(
            f"{cid}: {(texts.get(cid, '') or '')[:300]}" for cid in cand_ids
        )
        body = {
            "model": model,
            "messages": [
                {"role": "system", "content": sys_prompt},
                {"role": "user", "content": f"Query: {query}\n\nCandidates:\n{listing}"},
            ],
            "max_tokens": 512,
            "temperature": 0.0,
            "chat_template_kwargs": {"enable_thinking": False},
            "response_format": {
                "type": "json_schema",
                "json_schema": {"name": "rerank", "strict": True, "schema": schema},
            },
        }
        try:
            resp = httpx.post(f"{llm_url}/v1/chat/completions", json=body, timeout=timeout)
            data = resp.json()
            if "choices" not in data:
                raise AIUnavailable(data.get("error", {}).get("message", "no choices"))
            ranked = json.loads(data["choices"][0]["message"]["content"]).get("ranking", [])
        except AIUnavailable:
            raise
        except Exception as e:  # noqa: BLE001 — any failure ⇒ degrade gracefully
            raise AIUnavailable(str(e)) from e
        # keep only known ids; append any the model omitted in original order.
        out = [c for c in ranked if c in cand_ids]
        for c in cand_ids:
            if c not in out:
                out.append(c)
        return out

    return rank_fn


def served_model_name(llm_url: str, timeout: float = 5.0) -> str | None:
    """Best-effort name of the model served at ``llm_url`` (``GET /v1/models``).

    Used by the CLI's self-preference guardrail (judge ≠ engine generator). Never
    raises — returns ``None`` if the endpoint is unreachable or shapeless.
    """
    import httpx

    try:
        data = httpx.get(f"{llm_url}/v1/models", timeout=timeout).json()
    except Exception:  # noqa: BLE001 — advisory only; any failure ⇒ no name
        return None
    models = data.get("models") or data.get("data") or []
    if isinstance(models, list) and models and isinstance(models[0], dict):
        return models[0].get("model") or models[0].get("name") or models[0].get("id")
    return None


def load_doc_texts(corpus_dir: Path) -> dict[str, str]:
    """Best-effort ``{doc_id: text}`` from a BEIR-style ``docs.jsonl``.

    Accepts ``_id``/``id`` and ``text``/``contents``/``title`` shapes. Returns
    ``{}`` if the corpus file is absent (the probe then ranks text-light).
    """
    out: dict[str, str] = {}
    path = corpus_dir / "docs.jsonl"
    if not path.is_file():
        return out
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line:
            continue
        try:
            d = json.loads(line)
        except json.JSONDecodeError:
            continue
        did = d.get("_id") or d.get("id")
        if did is None:
            continue
        out[str(did)] = str(d.get("text") or d.get("contents") or d.get("title") or "")
    return out
