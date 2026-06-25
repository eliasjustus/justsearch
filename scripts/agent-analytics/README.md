# `scripts/agent-analytics/` — agent hooks + maintainer analytics

Two kinds of thing live here:

- **`hooks/` + `lib/`** — the Claude Code discipline hooks: blocking *guards* (e.g. preventing
  destructive git in the main checkout) and just-in-time *hints*. The hook **wiring** lives in
  `.claude/settings.json`; the shared helpers are in `lib/`.
- **Everything else** (`otlp-sink.py`, `*-session.mjs`, `generate-dashboard.mjs`, `otlp-viewer/`, …)
  — **maintainer** telemetry/analytics tooling for measuring agent-assisted development.

**Contributors don't need any of this** — it is published for transparency (see
[`/MAINTAINING.md`](../../MAINTAINING.md)). The analytics tooling is maintainer-only and is not
wired to run on a fresh clone; telemetry capture is local-only and never leaves the machine.
