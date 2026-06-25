"""Hybrid EM->LLM judge for the agent-utility eval (tempdoc 624 §C-6 / E-5).

Substring-EM is below the 2026 bar — "the first thing a hostile reviewer attacks".
This is the credible hybrid floor: **EM is a high-precision auto-PASS; the
EM-MISSES are adjudicated by an LLM judge from a DIFFERENT model family than the
claude agent under test** (the local llama-server — the self-preference control),
**dual-order** to cancel position bias (a >10 pp confound), **abstaining to the EM
verdict on disagreement**.

It runs **post-hoc over the Inspect EvalLogs** — the agent answers + targets are
already stored, so judging is decoupled from the expensive agent run and
re-judgeable without re-running agents (and validatable for free against the
committed floor logs). Emits a `judge-overlay.json` (final verdicts + the judge
identity that enters the cohort key + the EM-vs-judge **agreement rate**, the E-5
caveat as a field).
"""

from __future__ import annotations

import json
from pathlib import Path

import httpx

from jseval.agent_manifest import judge_identity
from jseval.agent_retrieval_eval import _score_answer
from jseval.manifest import _sha256_canonical

_JUDGE_SYSTEM = (
    "You grade a question-answering system. Decide whether the CANDIDATE answer is "
    "factually correct given the REFERENCE answer. Reply with exactly 'YES' if the "
    "candidate conveys the same factual answer as the reference, or 'NO' otherwise. "
    "Ignore phrasing, verbosity, ordering, and formatting differences."
)
_JUDGE_PROMPT_HASH = _sha256_canonical(_JUDGE_SYSTEM)
_NULL_GT = "insufficient information"


def _probe_judge_model(judge_url: str) -> str | None:
    """Best-effort: the served model id (for the judge identity / cohort key).

    Handles both OpenAI (`{"data":[{"id"}]}`) and the JustSearch/Ollama-style
    (`{"models":[{"name"|"model"}]}`) `/v1/models` shapes."""
    try:
        body = httpx.get(f"{judge_url}/v1/models", timeout=10.0).json()
        items = body.get("data") or body.get("models") or []
        if not items:
            return None
        m = items[0]
        return m.get("id") or m.get("name") or m.get("model")
    except Exception:
        return None


def _judge_once(judge_url, model, question, reference, candidate, *, ref_first: bool) -> bool:
    """One judge call in a fixed order (dual-order is two calls with ref_first flipped)."""
    if ref_first:
        user = (f"Question: {question}\nREFERENCE answer: {reference}\n"
                f"CANDIDATE answer: {candidate}\n\nIs the candidate correct? YES or NO.")
    else:
        user = (f"Question: {question}\nCANDIDATE answer: {candidate}\n"
                f"REFERENCE answer: {reference}\n\nIs the candidate correct? YES or NO.")
    body = {
        "model": model or "local",
        "messages": [{"role": "system", "content": _JUDGE_SYSTEM},
                     {"role": "user", "content": user}],
        "max_tokens": 4, "temperature": 0.0,
        "chat_template_kwargs": {"enable_thinking": False},
    }
    r = httpx.post(f"{judge_url}/v1/chat/completions", json=body, timeout=60.0)
    txt = ((r.json().get("choices") or [{}])[0].get("message", {}).get("content") or "").strip().upper()
    return txt.startswith("YES")


def _target_text(s) -> str:
    t = getattr(s, "target", None)
    if isinstance(t, str):
        return t
    if isinstance(t, list) and t:
        return str(t[0])
    return str(t or "")


def judge_logs(log_dir: str, *, judge_url: str = "http://127.0.0.1:8080",
               judge_model: str | None = None) -> dict:
    """Hybrid re-score over the EvalLogs. Returns the overlay artifact (dict).

    Shape: ``{"judge_identity", "stats", "scores": {"<cond>|<seed>|<qid>":
    {"em", "judge", "final"}}}``. ``judge`` is null for EM auto-passes and for
    null/abstention queries (the judge grades factual answers, not abstention —
    those stay EM). On any judge-call failure the cell falls back to EM (graceful).
    """
    from inspect_ai.log import read_eval_log

    model = judge_model or _probe_judge_model(judge_url)
    scores: dict = {}
    em_pass = judged = agree = disagree = flips = call_failures = 0

    for lf in sorted(Path(log_dir).glob("*.json")):
        if lf.name in ("eval-set.json", "logs.json"):
            continue
        try:
            log = read_eval_log(lf.as_posix())
        except Exception:
            continue
        if not getattr(log, "eval", None):
            continue
        cond = (log.eval.metadata or {}).get("condition")
        for s in (log.samples or []):
            if (s.metadata or {}).get("error"):
                continue
            seed = int(s.epoch or 1) - 1
            qid = str(s.id)
            ref = _target_text(s)
            cand = (getattr(s.output, "completion", "") if s.output else "") or ""
            question = s.input if isinstance(s.input, str) else ""
            em = _score_answer(ref, cand)
            final, judge_verdict = em, None
            # Judge only EM-missed, NON-null queries (abstention stays EM).
            if not em and ref.lower().strip().rstrip(".") != _NULL_GT:
                try:
                    v1 = _judge_once(judge_url, model, question, ref, cand, ref_first=True)
                    v2 = _judge_once(judge_url, model, question, ref, cand, ref_first=False)
                    judged += 1
                    if v1 == v2:
                        agree += 1
                        judge_verdict = v1
                        final = v1
                        if v1:
                            flips += 1  # judge rescued an EM-miss
                    else:
                        disagree += 1  # position-bias disagreement -> abstain to EM
                except Exception:
                    call_failures += 1
            else:
                em_pass += int(em)
            scores[f"{cond}|{seed}|{qid}"] = {"em": em, "judge": judge_verdict, "final": final}

    stats = {
        "em_auto_pass": em_pass, "judged_misses": judged,
        "judge_flips": flips, "judge_disagreements": disagree,
        "agreement_rate": (round(agree / judged, 4) if judged else None),
        "call_failures": call_failures,
        "degraded_to_em": (judged == 0 and call_failures > 0),
    }
    return {
        "judge_identity": judge_identity(
            kind=("hybrid-em-llm" if judged > 0 else "substring-em"),
            model=model, version=model, prompt_hash=_JUDGE_PROMPT_HASH),
        "stats": stats,
        "scores": scores,
    }


def write_overlay(log_dir: str, overlay: dict) -> str:
    path = Path(log_dir) / "judge-overlay.json"
    path.write_text(json.dumps(overlay, indent=2), encoding="utf-8")
    return str(path)
