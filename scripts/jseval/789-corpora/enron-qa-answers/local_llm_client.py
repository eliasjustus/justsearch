"""Minimal HTTP client for an out-of-band llama-server instance (tempdoc 789 naturalistic prep).

Not a jseval CLI command -- this substrate is a one-off $0 preparation step (answer
synthesis + closed-book contamination screen), run against a llama-server started
out-of-band per the 782 Section I recipe (a free port, outside the shared dev-stack
port 33221, so it does not contend with another worker's backend). Deterministic
settings (temperature=0, fixed seed) -- Qwen3.5 is a reasoning model, so the HTTP
response separates `reasoning_content` (thinking trace, discarded) from `content`
(the final answer, what callers want).
"""
from __future__ import annotations

import json
import urllib.request


def chat(base_url: str, prompt: str, *, max_tokens: int = 700, seed: int = 42,
         timeout_s: int = 120, enable_thinking: bool = False) -> str:
    """One-shot chat completion. Returns the `content` field, stripped.

    Qwen3.5 is a reasoning model that defaults to emitting a `reasoning_content`
    thinking trace before `content` -- on a longer prompt (a multi-KB email) the trace
    alone can exceed a modest `max_tokens` budget, leaving `content` empty with
    `finish_reason: "length"` (observed live: 18/20 empty on the first synthesis pass
    at max_tokens=700 with thinking on). `chat_template_kwargs.enable_thinking=false`
    (Qwen3 chat-template convention, confirmed live against this server) skips the
    trace entirely -- callers that want deterministic short answers should leave this
    at its default (False).
    """
    payload = {
        "messages": [{"role": "user", "content": prompt}],
        "temperature": 0,
        "seed": seed,
        "max_tokens": max_tokens,
        "chat_template_kwargs": {"enable_thinking": enable_thinking},
    }
    req = urllib.request.Request(
        f"{base_url}/v1/chat/completions",
        data=json.dumps(payload).encode("utf-8"),
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=timeout_s) as resp:
        body = json.loads(resp.read().decode("utf-8"))
    return (body["choices"][0]["message"].get("content") or "").strip()


def health(base_url: str, timeout_s: int = 3) -> bool:
    try:
        with urllib.request.urlopen(f"{base_url}/health", timeout=timeout_s) as resp:
            return resp.status == 200
    except Exception:
        return False
