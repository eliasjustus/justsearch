---
title: "Wire Codex CLI into the OTLP Sink"
type: how-to
status: stable
description: "Point OpenAI Codex CLI's native OTel exporter at the repo's local otlp-sink.py so its token usage lands in the same gen_ai.usage-normalised metrics stream as Claude Code, for cross-harness cost/context analysis."
related: tempdoc 886 §10.3 option B, §12 PR 3
audience: maintainer
---

# Wire Codex CLI into the OTLP Sink

## Why

Tempdoc 886 found that the machine this repo is developed on runs two
agent harnesses side by side — Claude Code and OpenAI Codex CLI — and
that Claude-only cost/context measurement was missing roughly a third
of actual agent spend (886 §9). Both harnesses can emit native OTel
telemetry; `scripts/agent-analytics/otlp-sink.py` already receives
Claude Code's stream and is harness-generic by construction (no
Claude-specific names anywhere in the receiver). §10.3 surveyed four
ways to fold Codex in and picked **option B (OTLP-only) as the live/
timing layer**, alongside log adapters (`lib/ledger/codex-adapter.mjs`)
as the source of truth for historical analysis. This doc is the OTLP
half: wiring Codex's own `[otel]` exporter at the same sink.

`otlp-sink.py` normalises both harnesses' token-usage metrics onto the
OTel GenAI semantic-convention vocabulary (`gen_ai.usage`,
`gen_ai.token.kind` in `input`/`output`/`cache_read`/`cache_creation`/
`reasoning`) additively — the original `claude_code.token.usage` /
`codex.turn.token_usage` records are kept unchanged, and a normalised
twin is appended alongside for every point whose raw type is known.
`gen_ai.system` on each normalised record identifies which harness
produced it (`claude-code` or `codex-cli`), so a reader over
`tmp/agent-telemetry/otlp/metrics.ndjson` never needs a branch per
harness once Codex is wired.

## Configuration

Codex CLI reads OTel settings from `~/.codex/config.toml`. Add (or
merge into an existing `[otel]` section):

```toml
[otel]
environment = "dev"
log_user_prompt = false

[otel.exporter]
otlp-http = { endpoint = "http://127.0.0.1:4318", protocol = "binary" }
```

- `log_user_prompt = false` keeps prompt text out of the emitted
  `codex.user_prompt` log event — this repo's telemetry capture is
  local-only and never leaves the machine, but redaction at the
  source is still the safer default.
- `endpoint` targets the same loopback port (`4318`) and path
  (`/v1/metrics`, `/v1/traces`, `/v1/logs`) the sink already listens
  on for Claude Code — no second receiver, no second port.
- `protocol = "binary"` (OTLP/HTTP protobuf) matches what the sink's
  `ROUTES` table decodes; this is the same wire format Claude Code
  already sends.

Restart Codex CLI (or start a fresh session) after editing the config
so it picks up the new `[otel]` block.

## The sink must be running

For a Claude Code session, the `otlp-sink-ensure` hook starts
`otlp-sink.py` automatically — see
`docs/explanation/21-agent-analytics-pipeline.md`. A **Codex-only**
session (no Claude Code session active in this repo at the same time)
has no such hook, so start the sink by hand before running Codex:

```bash
python scripts/agent-analytics/otlp-sink.py --port 4318
```

Leave it running in a background terminal for the duration of the
Codex session; it appends to `tmp/agent-telemetry/otlp/{metrics,
traces,logs}.ndjson` exactly as it does for Claude Code, and rotates/
prunes those streams the same way (`docs/explanation/
21-agent-analytics-pipeline.md`, `RETENTION` in `otlp-sink.py`).

**Volume note:** the `gen_ai.usage` normalisation (886 §12 PR 3) writes an
additional record per mapped token-usage data point alongside the original —
roughly **doubling** `metrics.ndjson` volume for every point that gets a
twin. `RETENTION["metrics"]` is `None` (never pruned — metrics is the sole
cost-baseline source, see `otlp-sink.py`'s module comment), so this is
disk growth that accumulates indefinitely, not a self-cleaning cost; the
main checkout's `tmp/agent-telemetry/otlp/` already carries ~146 MB of
metrics archives as of this writing. This is a stated tradeoff, not an
oversight — changing the retention policy is an owner decision, not made
here.

## One-session smoke check

After a short Codex turn or two with `[otel]` wired as above, confirm
normalised Codex records actually landed:

```bash
grep -c '"gen_ai.system": "codex-cli"' tmp/agent-telemetry/otlp/metrics.ndjson
```

A count greater than `0` confirms the wiring end to end: Codex's
exporter reached the sink, `decode_metrics` recognised
`codex.turn.token_usage`, and `GENAI_TOKEN_MAP` produced at least one
normalised `gen_ai.usage` record. `0` (or the file not existing) means
one of: the `[otel]` block wasn't picked up (restart Codex), the sink
isn't listening on `4318` (check for a bound-port error), or the turn
ran before the sink started (start the sink first, then run Codex).

This is a **user-run step** — it needs a live Codex CLI session, which
an agent worktree cannot start on your behalf.

## Optional: Codex hook equivalents (forward pointer, not governed)

Tempdoc 886 §12 PR 4 adds two Claude Code hints that read context/cost
off the ledger at the moment they matter: `spawn-cost-hint.mjs`
(prints a spawn's cost/calls/model on return) and
`context-ceiling-hint.mjs` (warns at 300k/500k resident context
tokens). Codex CLI has its own `hooks.json` mechanism
(`~/.codex/hooks.json`) that could run equivalent checks against the
same `lib/ledger/codex-adapter.mjs` data — e.g. a `session-end` hook
that prints context growth for the just-finished turn. This is **not
wired or governed by this repo** (`agent-hooks.v1.json` and the
tier-register only cover Claude Code hooks); it is noted here only so
a future PR 4-equivalent for Codex has a starting pointer instead of
re-discovering that Codex hooks exist.

## References

- `scripts/agent-analytics/otlp-sink.py` — `GENAI_TOKEN_MAP`,
  `_genai_normalize`, `ROUTES`.
- `scripts/agent-analytics/lib/telemetry-io.mjs` — `loadCostsFromOtlp`
  prefers `gen_ai.usage` records when present, with a fallback for
  archives written before this change.
- `docs/tempdocs/886-agent-token-efficiency-review.md` §10.3 (option
  B), §11 row A6, §12 PR 3.
- `docs/explanation/21-agent-analytics-pipeline.md` — sink hook
  wiring, rotation/retention policy, the harness-neutral session
  ledger.
