"""Provider-agnostic external-LLM-grader HTTP client (tempdoc 624 §M.9 "U-Founder-4
revised" -- cross-family LLM grader panel replaces bulk human labeling).

The founder decision: calibrate the local judge against a stratified 30-50 sample
graded independently by frontier models from DIFFERENT provider families than
BOTH the agent-under-test (Claude Haiku) and the existing local judge (Qwen) --
e.g. a GPT-class and/or Gemini-class grader -- reporting their MUTUAL cross-family
agreement (kappa + CI), not agreement against real human raters. This module is
the small, provider-agnostic HTTP client that makes those calls; `utility_judge
.run_cross_family_calibration` wires it into the existing stratified-sampling /
Cohen's-kappa machinery (`sample_for_calibration`, `rater_agreement_report`, ...),
which is reused verbatim -- it was already provider-agnostic.

**No real vendor endpoint, API key, or model name is hardcoded anywhere in this
module.** `GraderConfig` is caller-supplied configuration; adding a second
provider is a new `GraderConfig` value, not new code. Nothing in this module
calls the network unless a caller explicitly constructs a config and invokes
`call_grader_once` / `call_grader_dual_order` -- there is no default endpoint.

Mirrors `utility_judge._judge_once`'s dual-order call shape (grade once with the
reference first, once with the candidate first, to cancel position bias -- an
established discipline in this codebase, not new here) and the `claude` subprocess
convention's `--max-budget-usd` hard-cap idea (`agent_retrieval_eval.py`,
`utility_calibrate.py`) -- generalized here to a call-COUNT ceiling
(`GraderCallBudget`), since a per-call price is a caller-supplied fact of the
provider, not something this module can charge in dollars from a raw HTTP body.
"""

from __future__ import annotations

from dataclasses import dataclass, field

import httpx

_DEFAULT_GRADER_SYSTEM_PROMPT = (
    "You grade a question-answering system. Decide whether the CANDIDATE answer is "
    "factually correct given the REFERENCE answer. Reply with exactly 'YES' if the "
    "candidate conveys the same factual answer as the reference, or 'NO' otherwise. "
    "Ignore phrasing, verbosity, ordering, and formatting differences."
)


@dataclass(frozen=True)
class GraderConfig:
    """Everything needed to call one external LLM grader over an OpenAI-compatible
    chat-completions endpoint (the shape most frontier providers expose directly or
    via a compatibility layer). A second provider is a NEW `GraderConfig` value --
    not new code -- since `endpoint_url`/`model`/`headers` are all caller-supplied.

    ``name`` is a short label used for reporting (`rater_kind`, per-grader cost
    breakdown) -- NOT sent to the provider. ``headers`` carries whatever auth the
    provider needs (e.g. ``{"Authorization": "Bearer ..."}`` or a custom API-key
    header) -- deliberately a plain dict rather than a single "auth token" field, so
    providers with non-Bearer auth schemes are still just configuration.
    """

    name: str
    endpoint_url: str
    model: str
    headers: dict[str, str] = field(default_factory=dict)
    timeout_sec: float = 60.0
    system_prompt: str = _DEFAULT_GRADER_SYSTEM_PROMPT
    max_tokens: int = 4
    temperature: float = 0.0


class GraderCallBudgetExceeded(RuntimeError):
    """Raised when a run would exceed its configured hard cap on total external
    grader HTTP calls. Raised BEFORE the call that would exceed the cap is made,
    not after -- an unbounded loop (e.g. a bug in sample sizing) fails loudly
    instead of silently placing one call over budget."""


class GraderCallBudget:
    """A hard, shared ceiling on total external-grader HTTP calls across a run.

    Not a rate limiter -- a call-COUNT ceiling, the cost-safety mechanism this
    client provides so a caller (e.g. `run_cross_family_calibration`, which loops
    over N samples x M graders x 2 dual-order calls) cannot silently balloon spend
    past what was estimated and confirmed (`estimate_cross_family_cost`). Shared
    across every grader in a panel by passing the same instance to each call.
    """

    def __init__(self, max_calls: int):
        if max_calls <= 0:
            raise ValueError("max_calls must be positive")
        self.max_calls = max_calls
        self.calls_made = 0

    def consume(self, n: int = 1) -> None:
        if self.calls_made + n > self.max_calls:
            raise GraderCallBudgetExceeded(
                f"grader call budget exceeded: would reach {self.calls_made + n} "
                f"calls, cap is {self.max_calls}")
        self.calls_made += n


def call_grader_once(config: GraderConfig, question: str, reference: str, candidate: str,
                      *, ref_first: bool, budget: GraderCallBudget | None = None) -> bool:
    """One external-grader HTTP call in a fixed order.

    Mirrors `utility_judge._judge_once`'s prompt/parsing shape exactly (same
    question/reference/candidate framing, same "YES"-prefix verdict parsing) but
    is HTTP-generic: no llama-server-specific defaults, an explicit `headers` dict
    for provider auth, and a per-call timeout from `config`. Dual-order calling
    (this function invoked twice with `ref_first` flipped -- see
    `call_grader_dual_order`) cancels position bias, the same discipline this
    codebase's own local-judge calls already use.

    If `budget` is given, consumes one call from it BEFORE making the HTTP
    request -- raises `GraderCallBudgetExceeded` instead of ever exceeding the cap.
    """
    if budget is not None:
        budget.consume(1)
    if ref_first:
        user = (f"Question: {question}\nREFERENCE answer: {reference}\n"
                f"CANDIDATE answer: {candidate}\n\nIs the candidate correct? YES or NO.")
    else:
        user = (f"Question: {question}\nCANDIDATE answer: {candidate}\n"
                f"REFERENCE answer: {reference}\n\nIs the candidate correct? YES or NO.")
    body = {
        "model": config.model,
        "messages": [{"role": "system", "content": config.system_prompt},
                     {"role": "user", "content": user}],
        "max_tokens": config.max_tokens,
        "temperature": config.temperature,
    }
    r = httpx.post(config.endpoint_url, json=body, headers=config.headers,
                    timeout=config.timeout_sec)
    r.raise_for_status()
    txt = ((r.json().get("choices") or [{}])[0].get("message", {}).get("content") or "").strip().upper()
    return txt.startswith("YES")


def call_grader_dual_order(config: GraderConfig, question: str, reference: str, candidate: str,
                            *, budget: GraderCallBudget | None = None) -> bool | None:
    """Dual-order call: grade once reference-first, once candidate-first.

    Agreement between the two orders -> that verdict; disagreement -> abstain
    (``None``) -- the same cancel-position-bias-then-abstain discipline
    `utility_judge.judge_logs` already applies to its own local-judge dual-order
    calls (mirrored here, not shared code, since this client is HTTP-generic
    while `_judge_once` is coupled to the local llama-server proxy).
    """
    v1 = call_grader_once(config, question, reference, candidate, ref_first=True, budget=budget)
    v2 = call_grader_once(config, question, reference, candidate, ref_first=False, budget=budget)
    return v1 if v1 == v2 else None


def estimate_cross_family_cost(n_samples: int, graders: list[GraderConfig],
                                price_table: dict[str, float], *, dual_order: bool = True) -> dict:
    """Pure, standalone cost estimator (tempdoc 624 §M.9 item 3).

    Computes ``n_samples * len(graders) * (2 if dual_order else 1) * price_per_call``
    for each grader in `graders`, looking up its per-call price by ``grader.name``
    in `price_table` (a plain ``{name: price_per_call_usd}`` mapping the caller
    supplies -- pricing is deliberately NOT a `GraderConfig` field, so the HTTP
    client itself carries no pricing knowledge). Raises `KeyError` if a grader's
    name is missing from `price_table` -- fail loud rather than silently pricing an
    un-costed grader at $0 and under-reporting spend.

    Deliberately NOT threaded through `utility_calibrate.calibrate`, which solves a
    different, agent+search-backend-shaped cost problem (readiness/timeout
    calibration for the agent-eval run itself, not the grader-panel's own HTTP
    spend). No network call happens here -- this is pure arithmetic, meant to be
    printed and confirmed BEFORE any real grader call is made.
    """
    calls_per_grader = n_samples * (2 if dual_order else 1)
    call_count = calls_per_grader * len(graders)
    per_grader_cost = {g.name: round(calls_per_grader * price_table[g.name], 6) for g in graders}
    cost_estimate_usd = round(sum(per_grader_cost.values()), 6)
    return {
        "cost_estimate_usd": cost_estimate_usd,
        "call_count": call_count,
        "n_samples": n_samples,
        "n_graders": len(graders),
        "dual_order": dual_order,
        "per_grader": per_grader_cost,
    }
