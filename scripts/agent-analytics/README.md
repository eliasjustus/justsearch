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

## Signature census (743 P-L)

`mine-friction.mjs`/`analyze-session.mjs` (the alive 727 friction-mining pass) judge whole
sessions via an LLM. `signature-census.mjs` is the cheap, mechanical complement: it scans every
session in a window for a small seeded table of known recurring error signatures (the `& "gh.exe"`
PowerShell-call-operator class, cp1252/`UnicodeEncodeError`, quoting-EOF, `gh` exit-code
misreads, deferred-tool schema-not-loaded, `/tmp`-vs-scratchpad path misses, edit-not-read) and
counts them — no LLM call, no judgment. Run it as part of the periodic mining pass:

```
node scripts/agent-analytics/signature-census.mjs --since <window-start>
```

It is semi-automatic by design (tempdoc 743 P-L): the census only **proposes** counts. Each
signature whose count clears the ratchet threshold (≥5 in the window) gets a **disposition** at
the next mining-pass review session — exactly one of **root-fix** (a P-K-class exec-substrate
fix), **fire-time hint** (a new redirect hook, registered the normal way — `agent-hooks.v1.json`
+ tier-register + `hook-integrity` gate), or explicit **wontfix**. Census output must **never**
land in always-loaded prose (`CLAUDE.md`/`.claude/rules/`) — the always-loaded-budget ratchet is
the guard against that self-poisoning failure mode. Falsifier: two consecutive mining passes
whose dispositions nobody implements means the loop is dead weight — stop running it.

Shares its transcript discovery/parse substrate (`lib/transcript-store.mjs`) with the rescued
`overhead-taxonomy.mjs` (T1 overhead measurement) and `transcript-spine.mjs` (evidence-lane
per-turn condenser) — see that module's header for the discovery-layout caveat.
