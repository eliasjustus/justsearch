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

**Human-calibration machinery (tempdoc 624 §M.4/§T.3):** `agreement_rate` above is
a *self-consistency* statistic (judge vs. the cheap EM fallback), not a validated
accuracy figure against ground truth. `sample_for_calibration` /
`rater_agreement_report` / `attach_human_calibration` build the calibration
protocol the design settled on: a stratified sample of judge verdicts (oversampling
the EM-disagreement cases, where judge error concentrates), scored by >=2
INDEPENDENT raters, reporting both judge-vs-rater-majority agreement and
rater-vs-rater agreement (Cohen's kappa for exactly 2 raters, Krippendorff's alpha
for 3+ — not yet built, see `rater_agreement_report`), each with a bootstrap CI.
Real human raters are not available in an autonomous pipeline; `run_calibration_dry_run`
proves the machinery end-to-end with two independent AGENT-SUBSTITUTE heuristic raters
instead — the emitted `rater_kind` field says so explicitly so the number is never
mistaken for a real calibration figure.
"""

from __future__ import annotations

import json
import random
from pathlib import Path

import httpx
import numpy as np

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


def _iter_eval_records(log_dir: str):
    """Yield raw per-sample records across every EvalLog in `log_dir`.

    The log-walking core shared by `judge_logs` (scoring) and
    `collect_calibration_texts` (the calibration dry run's text lookup) — kept as
    one generator so the two never drift on which files/samples are skipped.
    Yields ``{"cond", "seed", "qid", "question", "reference", "candidate", "em"}``.
    """
    from inspect_ai.log import read_eval_log

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
            yield {"cond": cond, "seed": seed, "qid": qid, "question": question,
                   "reference": ref, "candidate": cand, "em": em}


def judge_logs(log_dir: str, *, judge_url: str = "http://127.0.0.1:8080",
               judge_model: str | None = None) -> dict:
    """Hybrid re-score over the EvalLogs. Returns the overlay artifact (dict).

    Shape: ``{"judge_identity", "stats", "scores": {"<cond>|<seed>|<qid>":
    {"em", "judge", "final"}}}``. ``judge`` is null for EM auto-passes and for
    null/abstention queries (the judge grades factual answers, not abstention —
    those stay EM). On any judge-call failure the cell falls back to EM (graceful).
    """
    model = judge_model or _probe_judge_model(judge_url)
    scores: dict = {}
    em_pass = judged = agree = disagree = flips = call_failures = 0

    for rec in _iter_eval_records(log_dir):
        cond, seed, qid = rec["cond"], rec["seed"], rec["qid"]
        ref, cand, question, em = rec["reference"], rec["candidate"], rec["question"], rec["em"]
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


# --- Human-calibration machinery (tempdoc 624 §M.4 / §T.3) -----------------------


def sample_for_calibration(scores: dict, *, n: int = 40, seed: int = 0,
                            disagreement_frac: float = 0.6) -> list[str]:
    """Draw a deterministic, stratified sample of `scores`-dict keys for calibration.

    Splits `scores` into two strata: **disagreement** (the judge fired and its
    verdict differs from the raw EM call — ``judge is not None and em != judge``,
    i.e. a judge-flip/rescue) and everything else. Disagreement is where judge
    error concentrates (§M.4) and EM-agreement cells are high-precision by
    construction, so the disagreement stratum is oversampled to
    `disagreement_frac` of the sample (default 60%) — far above its natural
    frequency, which is typically a small fraction of all items. Deterministic
    given `seed` (stdlib `random.Random`): identical inputs -> identical sample,
    so repeated calibration passes over the same overlay are reproducible.
    Returns a plain list of ``"<cond>|<seed>|<qid>"`` keys, size <= min(n, len(scores)).
    """
    keys = sorted(scores.keys())  # deterministic base ordering before any RNG use
    if not keys or n <= 0:
        return []
    n = min(n, len(keys))
    frac = max(0.0, min(1.0, disagreement_frac))
    disagreement_keys = [k for k in keys
                          if scores[k].get("judge") is not None
                          and scores[k]["em"] != scores[k]["judge"]]

    rng = random.Random(seed)
    n_dis = min(len(disagreement_keys), round(n * frac))
    sampled_dis = rng.sample(disagreement_keys, n_dis) if n_dis else []

    remaining_pool = [k for k in keys if k not in sampled_dis]
    n_rest = n - len(sampled_dis)
    sampled_rest = rng.sample(remaining_pool, min(n_rest, len(remaining_pool))) if n_rest > 0 else []

    sample = sampled_dis + sampled_rest
    rng.shuffle(sample)
    return sample


def collect_calibration_texts(log_dir: str, keys) -> dict:
    """Re-read the EvalLogs and return the (question, reference, candidate) text
    a rater needs, for exactly the sampled ``keys`` (typically
    `sample_for_calibration`'s output). ``{key: {"question","reference","candidate"}}``.
    """
    wanted = set(keys)
    out: dict = {}
    if not wanted:
        return out
    for rec in _iter_eval_records(log_dir):
        key = f"{rec['cond']}|{rec['seed']}|{rec['qid']}"
        if key in wanted:
            out[key] = {"question": rec["question"], "reference": rec["reference"],
                        "candidate": rec["candidate"]}
    return out


def cohens_kappa(labels_a, labels_b) -> float:
    """Cohen's kappa for exactly two raters over parallel boolean label sequences.

    ``kappa = (p_o - p_e) / (1 - p_e)`` where ``p_o`` is observed agreement and
    ``p_e`` is chance-expected agreement given each rater's marginal YES rate.
    The degenerate case (``p_e == 1``, i.e. both raters gave every item the same
    single label) can only occur alongside perfect observed agreement, so it is
    defined as ``kappa = 1.0`` rather than dividing by zero.
    """
    a = np.asarray(list(labels_a), dtype=bool)
    b = np.asarray(list(labels_b), dtype=bool)
    if len(a) != len(b):
        raise ValueError("cohens_kappa needs two label sequences of equal length")
    if len(a) == 0:
        raise ValueError("cohens_kappa needs at least one labeled item")
    po = float(np.mean(a == b))
    p_a1, p_b1 = float(np.mean(a)), float(np.mean(b))
    pe = p_a1 * p_b1 + (1 - p_a1) * (1 - p_b1)
    if pe >= 1.0 - 1e-12:
        return 1.0
    return (po - pe) / (1 - pe)


def bootstrap_kappa_ci(labels_a, labels_b, *, n_resamples: int = 2000,
                        alpha: float = 0.05, seed: int = 42) -> tuple[float, float]:
    """Bootstrap confidence interval (default 95%) for `cohens_kappa`, by
    resampling item pairs with replacement. Deterministic given `seed`."""
    a = np.asarray(list(labels_a), dtype=bool)
    b = np.asarray(list(labels_b), dtype=bool)
    n = len(a)
    if n == 0:
        raise ValueError("bootstrap_kappa_ci needs at least one labeled item")
    if n == 1:
        k = cohens_kappa(a, b)
        return k, k
    rng = np.random.default_rng(seed)
    boots = np.empty(n_resamples)
    for i in range(n_resamples):
        idx = rng.integers(0, n, size=n)
        boots[i] = cohens_kappa(a[idx], b[idx])
    lo = float(np.percentile(boots, 100 * alpha / 2))
    hi = float(np.percentile(boots, 100 * (1 - alpha / 2)))
    return lo, hi


def rater_majority_vote(rater_labels: list[list[bool]]) -> list:
    """Per-item majority vote across N parallel rater label lists.

    Generalizes past 2 raters by construction (N-rater vote counting), even
    though only the N=2 path is exercised today. Returns ``None`` for an item
    with no strict majority (only possible when N is even — e.g. 2 raters
    disagreeing 1-1) so callers can drop true ties rather than guess a label.
    """
    n_raters = len(rater_labels)
    if n_raters == 0:
        return []
    n_items = len(rater_labels[0])
    out = []
    for i in range(n_items):
        votes = [rater_labels[r][i] for r in range(n_raters)]
        yes = sum(1 for v in votes if v)
        no = n_raters - yes
        out.append(True if yes > no else False if no > yes else None)
    return out


def rater_agreement_report(judge_verdicts: list[bool], raters: list[list[bool]], *,
                            n_resamples: int = 2000, seed: int = 42) -> dict:
    """Compute both calibration agreement statistics over one sample (§M.4):

    - **judge-vs-rater-majority agreement** — the judge's own verdicts against the
      raters' majority vote (items with no strict majority are dropped and counted
      in ``n_dropped_ties``).
    - **rater-vs-rater agreement** — the natural-disagreement baseline the judge's
      own agreement should be read against, not a bare number in isolation.

    Both as Cohen's kappa (exactly 2 raters) with a bootstrap CI. Structured to
    generalize to Krippendorff's alpha for 3+ raters later (`rater_majority_vote`
    already N-rater-general); 3+ raters isn't implemented yet — only the 2-rater
    dry-run path is built/tested (tempdoc 624 §M.4/§T.3).
    """
    n_raters = len(raters)
    if n_raters < 2:
        raise ValueError("rater_agreement_report needs >= 2 independent raters")
    if n_raters >= 3:
        raise NotImplementedError(
            "3+ raters needs Krippendorff's alpha -- not implemented; only the 2-rater "
            "path is built/tested (tempdoc 624 §M.4/§T.3).")

    rater_a, rater_b = raters
    rvr_kappa = cohens_kappa(rater_a, rater_b)
    rvr_lo, rvr_hi = bootstrap_kappa_ci(rater_a, rater_b, n_resamples=n_resamples, seed=seed)

    majority = rater_majority_vote(raters)
    pairs = [(j, m) for j, m in zip(judge_verdicts, majority) if m is not None]
    n_dropped_ties = len(majority) - len(pairs)
    if pairs:
        j_labels, m_labels = zip(*pairs)
        jvm_kappa = cohens_kappa(j_labels, m_labels)
        jvm_lo, jvm_hi = bootstrap_kappa_ci(j_labels, m_labels, n_resamples=n_resamples, seed=seed)
    else:
        jvm_kappa = jvm_lo = jvm_hi = None

    return {
        "n": len(judge_verdicts),
        "n_dropped_ties": n_dropped_ties,
        "judge_vs_rater_agreement": {"value": jvm_kappa, "ci_low": jvm_lo, "ci_high": jvm_hi},
        "rater_vs_rater_agreement": {"value": rvr_kappa, "ci_low": rvr_lo, "ci_high": rvr_hi},
    }


def _rater_substitute_token_overlap(question: str, reference: str, candidate: str) -> bool:
    """Agent-substitute rater A: token-Jaccard-overlap heuristic (>=50% of the
    reference's word set appears in the candidate). NOT an LLM call, and NOT
    `_judge_once`'s prompt/logic — an independent decision rule used ONLY for the
    mechanism-proving dry run (`run_calibration_dry_run`). Not a substitute for
    real human labeling.
    """
    ref_tokens = {t for t in reference.lower().split() if t}
    cand_tokens = {t for t in candidate.lower().split() if t}
    if not ref_tokens:
        return not cand_tokens
    overlap = len(ref_tokens & cand_tokens) / len(ref_tokens)
    return overlap >= 0.5


def _rater_substitute_containment(question: str, reference: str, candidate: str) -> bool:
    """Agent-substitute rater B: a SECOND, independently-designed heuristic —
    whether the reference's longest word appears verbatim in the candidate.
    Deliberately a different decision rule than rater A (anchor-containment vs.
    set-overlap), so the two "raters" aren't the same check under two names.
    Dry-run mechanism proof only — NOT a substitute for real human labeling.
    """
    ref = reference.lower().strip()
    cand = candidate.lower()
    if not ref:
        return not cand.strip()
    words = [w.strip(".,!?;:\"'()") for w in ref.split()]
    words = [w for w in words if len(w) > 2]
    if not words:
        return ref in cand
    anchor = max(words, key=len)
    return anchor in cand


def run_calibration_dry_run(log_dir: str, overlay: dict, *, n: int = 40, seed: int = 0) -> dict:
    """Mechanism-proving dry run of the human-calibration pipeline (§M.4/§T.3).

    Draws a stratified sample from `overlay["scores"]`, fetches the sampled
    items' text, scores each with TWO INDEPENDENT AGENT-SUBSTITUTE heuristic
    raters (deterministic, non-LLM, mutually distinct — see
    `_rater_substitute_token_overlap` / `_rater_substitute_containment`), and
    computes both agreement statistics via `rater_agreement_report`.

    **This proves the sampling + agreement machinery is wired correctly
    end-to-end. It does NOT produce a usable calibration number** — genuine
    human raters are not available in this fully-autonomous pipeline. The
    returned ``rater_kind`` field says so explicitly; treat any kappa here as a
    mechanism check, never as a validated judge-accuracy figure.
    """
    scores = overlay.get("scores", {})
    sample_keys = sample_for_calibration(scores, n=n, seed=seed)
    texts = collect_calibration_texts(log_dir, sample_keys) if sample_keys else {}

    rater_a_labels, rater_b_labels, judge_labels, used_keys = [], [], [], []
    for k in sample_keys:
        t = texts.get(k)
        if t is None:
            continue  # sample key not found in the logs (shouldn't happen) -- skip defensively
        rater_a_labels.append(
            _rater_substitute_token_overlap(t["question"], t["reference"], t["candidate"]))
        rater_b_labels.append(
            _rater_substitute_containment(t["question"], t["reference"], t["candidate"]))
        judge_labels.append(bool(scores[k]["final"]))
        used_keys.append(k)

    base = {"rater_kind": "agent-substitute, NOT human", "n": len(used_keys), "sample_qids": used_keys}
    if len(used_keys) < 2:
        return {
            **base,
            "judge_vs_rater_agreement": {"value": None, "ci_low": None, "ci_high": None},
            "rater_vs_rater_agreement": {"value": None, "ci_low": None, "ci_high": None},
            "note": "sample has fewer than 2 items -- no agreement statistic computed",
        }

    report = rater_agreement_report(judge_labels, [rater_a_labels, rater_b_labels], seed=seed)
    return {
        **base,
        "n_dropped_ties": report["n_dropped_ties"],
        "judge_vs_rater_agreement": report["judge_vs_rater_agreement"],
        "rater_vs_rater_agreement": report["rater_vs_rater_agreement"],
    }


def attach_human_calibration(overlay: dict, log_dir: str, *, n: int = 40, seed: int = 0) -> dict:
    """Mutate + return `overlay` with a `human_calibration` block attached
    (additive — `judge_identity`/`stats`/`scores` are untouched). See
    `run_calibration_dry_run` for what is actually computed today (a mechanism
    dry run with agent-substitute raters, not real human calibration).
    """
    overlay["human_calibration"] = run_calibration_dry_run(log_dir, overlay, n=n, seed=seed)
    return overlay


def write_overlay(log_dir: str, overlay: dict) -> str:
    """Persist the overlay dict verbatim as ``judge-overlay.json``.

    Generic over shape — any additive key (e.g. `human_calibration`, see
    `attach_human_calibration`) is written the same way as `judge_identity` /
    `stats` / `scores`; nothing here special-cases individual fields.
    """
    path = Path(log_dir) / "judge-overlay.json"
    path.write_text(json.dumps(overlay, indent=2), encoding="utf-8")
    return str(path)
