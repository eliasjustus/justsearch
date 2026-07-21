# Research

JustSearch is a local-first retrieval engine (hybrid BM25 + dense + learned-sparse + cross-encoder rerank),
exposed to Claude-based agents over MCP, with a reproducible evaluation harness (`jseval`) behind every
number it publishes. This page states the actual research position honestly: what's measured, what's
deferred, and what a collaborator would find useful to work on.

## What's measured, and how

Every retrieval-quality number JustSearch publishes traces to one canonical, reproducible release object
(`scripts/jseval/release.v1.json`) — a single sweep of the production-default pipeline across a fixed
corpus set, at one commit, on stated hardware, with cited external baselines shown honestly (not as a "we
beat X" claim — see the comparison-class notes in
[`docs/reference/benchmarks/methodology.md`](docs/reference/benchmarks/methodology.md)). The result numbers
cannot silently drift from that object; every table anywhere in the docs is a generated projection of it,
never hand-transcribed.

**The one claim we think is genuinely defensible and hard to copy:** multilingual-competitive retrieval
*without any per-language tuning* — no per-language analyzers, stopwords, stemmers, or synonym lists. That's
an architectural stance (ICU + NFC + lowercase only), not a tuning trick, and it's falsifiable against the
MIRACL de/fr numbers in the release. Most retrieval systems add per-language machinery instead of avoiding
it; this is the finding worth someone else's scrutiny. **One honest caveat on that number:** our MIRACL
subsample uses a smaller sampled document pool than MIRACL's own official multi-million-passage collection,
on a different split (test vs. the published dev baselines) — so it is not a clean head-to-head win against
the official leaderboard, only evidence that dropping per-language tuning doesn't show a measurable quality
collapse on our own corpus. Full comparison-class detail: `docs/reference/benchmarks/methodology.md`.

## What's deferred, honestly

<!-- agent-utility:generated:start - run: node scripts/docs/gen-public-agent-utility.mjs -->

No agent-utility result is currently accepted for publication. No agent-utility result has passed the active scientific claim policy; the 2026-07-18 confirmatory campaign was rejected on identity-verification gates. The checked-in claim policy (`agent-utility-public-v2`) is active and fully resolved: it pins a required four-stratum campaign matrix (CLERC legal + Enron email, each at 1k and 10k documents), a model cohort, and its scientific margins. One pre-registered confirmatory campaign has run against it (2026-07-18); the policy rejected promotion on identity-verification gates, and the complete evidence — including both voided runs — is committed under `scripts/jseval/624-run-2026-07-18-confirmatory/`. Owner decisions, certifications, and any paid rerun require separate authorization; the harness does not invent them.

The latest rejected campaign record (with its policy-evaluated verdict and per-gate reasons) lives in the evidence directory above; earlier sanitized pilot evidence is retained as a rejected fixture and can be recomposed without credentials, a backend, or model calls. A result can appear here only after an immutable bundle replays, passes the settled policy, and is explicitly selected by the owner.

```bash
cd scripts/jseval
python -m jseval utility-recompose --evidence tests/fixtures/agent-utility-rejected-2026-07-12/observations.v1.jsonl --output-dir out
python -m jseval utility-replay --publication <publication-id>
```

<!-- agent-utility:generated:end -->

A related, smaller open question: JustSearch's retrieval-time context-sufficiency classifier (used by
agent-facing endpoints to signal whether retrieved context can actually answer a query) has never been
validated against a labeled dataset — its precision/recall are currently unknown. This is a bounded,
tractable research task (build ~20–30 labeled `(query, context) → answerable?` pairs, measure the classifier
against them) that hasn't been resourced yet.

## Reproducing and extending this work

The retrieval-quality release above is reproducible from a clone — see
[`docs/reference/benchmarks/methodology.md`](docs/reference/benchmarks/methodology.md#reproduce-it) for the
exact commands and corpus slugs. The corpora, fetch recipes, and evaluation harness are all in the repo;
nothing needs to be requested from us to start. Agent-utility evidence is separately replayable at zero
cost only after an accepted publication exists; a live rerun requires licensed corpus sources, model
credentials, a running backend, and explicit budget authorization. See
[`docs/reference/benchmarks/agent-utility.md`](docs/reference/benchmarks/agent-utility.md).

## Looking for a research or institutional collaborator

If you work on information retrieval, agentic evaluation methodology, or a document-heavy scientific/
scholarly workflow and think a reproducible, local-first retrieval backend would be useful to your own
research question — rather than the other way around — we'd like to hear from it. Open an issue, or reach
out directly. We're specifically interested in partners who already have a real document collection and
research question, not a generic "let's collaborate."
