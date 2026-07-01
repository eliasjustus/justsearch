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
    """nDCG@10 of ``{qid: [doc_id in rank order]}`` against ``qrels``.

    ``qrels`` is filtered to the queries actually present in ``rankings`` before scoring.
    Without this, ``ir_measures`` silently scores every qrels query NOT present in
    ``rankings`` as 0 and folds it into the mean — on a capped run (``--max-queries``), the
    qrels file still holds the corpus's full query set, so passing it through unfiltered
    dilutes the result by (queries actually judged / total corpus queries) — a confirmed,
    real bug found via the tempdoc 643 sibling-thread ce-replay cross-check (2026-07-01):
    an ~8x dilution on a 40-of-300-query scifact run. Every caller of this function wants
    "nDCG over exactly the queries in rankings," never "credit missing queries as zero."
    """
    scoped_qrels = {qid: rels for qid, rels in qrels.items() if qid in rankings}
    scored = [
        ir_measures.ScoredDoc(query_id=qid, doc_id=d, score=float(len(ids) - i))
        for qid, ids in rankings.items()
        for i, d in enumerate(ids)
    ]
    if not scored:
        return None
    return scoring.evaluate(scoped_qrels, scored).get("nDCG@10")


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
    attempted = 0
    skipped_qids: list[str] = []
    for qid, cands in pool.items():
        if not cands or qid not in qrels:
            continue
        attempted += 1
        q = queries.get(qid, "")
        # Tempdoc 643 U1 (first live run, 2026-07-01): a single query's malformed/truncated
        # response (a real, observed per-query failure mode -- not every response fits any
        # fixed token budget, e.g. when a served model doesn't fully honor enable_thinking) must
        # not discard the OTHER 39 real, already-paid-for LLM calls. Only genuine unavailability
        # (every attempted query fails) still degrades the whole probe -- see the all-failed
        # check below, which mirrors AIUnavailable's original all-or-nothing intent for that case.
        try:
            fwd = rank_fn(q, cands, texts)
            rev = rank_fn(q, list(reversed(cands)), texts)  # order-swap guardrail
        except AIUnavailable:
            skipped_qids.append(qid)
            continue
        checked += 1
        if fwd and rev and fwd[0] == rev[0]:
            top1_agree += 1
        fwd_rankings[qid] = fwd

    if attempted > 0 and checked == 0:
        raise AIUnavailable(f"all {attempted} attempted queries failed (genuine unavailability)")

    llm_ndcg = _score_ranking(qrels, fwd_rankings)
    realized = (llm_ndcg - final_ndcg) if (llm_ndcg is not None and final_ndcg is not None) else None
    capture = (
        (realized / ceiling) if (realized is not None and ceiling not in (None, 0)) else None
    )
    return {
        "status": "ok",
        "n_queries": len(fwd_rankings),
        "n_skipped": len(skipped_qids),
        "skipped_qids": skipped_qids,
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


def make_chat_rank_fn(llm_url: str, model: str = "local", timeout: float = 300.0) -> RankFn:
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
            # Tempdoc 643 U1 (first live run of this probe, 2026-07-01): 512 was too tight for a
            # realistic leg-union pool (observed up to 29 candidates on scifact) -- multi-digit
            # numeric doc-ids tokenize to several tokens each, and not every served model fully
            # honors enable_thinking=False, so the ranking array was getting truncated mid-string
            # (a real "Unterminated string" JSON-parse failure, not a hypothetical one). Sized
            # together with make_chat_rank_fn's default `timeout` (300s) for ~7 tok/s CPU
            # inference (observed live) -- 2048 tokens comfortably fits in that budget with margin.
            "max_tokens": 2048,
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


def load_ce_scores(run_dir: Path, mode: str | None = None) -> dict[str, dict[str, float]]:
    """``{qid: {doc_id: ce_score}}`` from the final mode's ``judgeSignals`` (already captured
    per query by the production pipeline — no live model, no LLM, no new eval run).

    ``mode`` defaults to the run's preferred final mode (``hybrid`` else ``full``), matching
    ``staged_recall_accounting.FINAL_MODE_PREFERENCE``.
    """
    if mode is None:
        present = _sra._present_modes(run_dir)
        mode = next((m for m in _sra.FINAL_MODE_PREFERENCE if m in present), None)
    out: dict[str, dict[str, float]] = {}
    if mode is None:
        return out
    path = run_dir / f"{mode}_per_query.json"
    if not path.is_file():
        return out
    for rec in json.loads(path.read_text(encoding="utf-8")):
        qid = rec.get("qid")
        if not qid:
            continue
        scores = {
            s["docId"]: s["ce_score"]
            for s in (rec.get("judgeSignals") or [])
            if s.get("ce_score") is not None
        }
        if scores:
            out[qid] = scores
    return out


def ce_replay_rankings(
    pool: dict[str, list[str]], ce_scores_by_qid: dict[str, dict[str, float]],
) -> dict[str, list[str]]:
    """Re-rank each query's leg-union pool by the production CE's own already-captured score.

    Candidates the CE actually scored are sorted by that score, descending, first; candidates
    outside its window (never scored) keep the pool's original order, appended after — the CE
    rendered no judgment on them, so this reflects exactly what re-applying the shipped judge
    (not a bigger window, not a different judge) over this pool would produce.
    """
    out: dict[str, list[str]] = {}
    for qid, cands in pool.items():
        scores = ce_scores_by_qid.get(qid, {})
        scored = [c for c in cands if c in scores]
        unscored = [c for c in cands if c not in scores]
        scored.sort(key=lambda c: scores[c], reverse=True)
        out[qid] = scored + unscored
    return out


def ce_replay_report(
    pool: dict[str, list[str]],
    ce_scores_by_qid: dict[str, dict[str, float]],
    qrels: dict,
    *,
    final_ndcg: float | None,
    ceiling: float | None = None,
) -> dict:
    """AI-free realizable-headroom probe: how much of ``ceiling`` does the *actual production
    judge* (not a different/LLM judge — see :func:`judge_ceiling_report`) capture when replayed
    over the full leg-union pool, using only its own already-captured scores.

    No live model, no LLM, no new eval run — pure reprocessing of already-archived data. This is
    a distinct question from the live judge-ceiling probe: it asks whether the judge that is
    actually shipped would do better given a bigger pool, not whether a different judge would.
    """
    rankings = ce_replay_rankings(pool, ce_scores_by_qid)
    replay_ndcg = _score_ranking(qrels, rankings)
    realized = (
        (replay_ndcg - final_ndcg) if (replay_ndcg is not None and final_ndcg is not None) else None
    )
    capture = (
        (realized / ceiling) if (realized is not None and ceiling not in (None, 0)) else None
    )
    n_scored_queries = sum(1 for qid in pool if ce_scores_by_qid.get(qid))
    return {
        "status": "ok",
        "n_queries": len(rankings),
        "n_ce_scored_queries": n_scored_queries,
        "ce_replay_ndcg": replay_ndcg,
        "final_ndcg": final_ndcg,
        "judge_headroom_ceiling": ceiling,
        "headroom_realized": realized,
        "capture_fraction": capture,
        "caveat": (
            "the production CE's window (not the full pool) bounds what it could have scored; "
            "un-scored candidates keep their pool order, so this cannot credit the judge for "
            "docs outside its own input window — see 'enlarge the CE window' as a distinct lever"
        ),
    }
