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

**The agent-utility question — does JustSearch's retrieval actually help an agent complete a task, not just
rank passages well — is not yet answered credibly, and the preliminary signal is not obviously flattering.**
An earlier internal number (quoted informally as "92% accuracy / 62% cheaper") turned out on audit to
conflate two unrelated measurements from a 50-query, single-model eval with no real comparison arm, and has
been retracted rather than published. The rebuild (tracked internally as tempdoc 624) has its machinery
built — a cohort-identified, condition-paired comparison record, an LLM-judge, run governance — but the
project's own preliminary data from that rebuild shows the **realistic** comparison (an agent that already
has generic file tools, plus JustSearch) at **+0.00 accuracy / roughly 8% token savings** — not the +0.20
accuracy / ~40% figure from a more favorable but less realistic comparison arm (an agent with no file access
at all). The tempdoc names this directly as its central open question: does a properly-powered, honest
measurement even support the claim, or does the finding need to be reframed rather than just measured
harder? As of this writing, a methodology plan for that measurement exists and founder decisions on it are
resolved — but the run itself, and therefore the honest answer, has not happened yet. **This is exactly the
kind of open, uncertain question a research grant is well-suited to fund** — not because we're confident of
the outcome, but because the methodology to find out rigorously already exists and isn't yet resourced.

A related, smaller open question: JustSearch's retrieval-time context-sufficiency classifier (used by
agent-facing endpoints to signal whether retrieved context can actually answer a query) has never been
validated against a labeled dataset — its precision/recall are currently unknown. This is a bounded,
tractable research task (build ~20–30 labeled `(query, context) → answerable?` pairs, measure the classifier
against them) that hasn't been resourced yet.

## Reproducing and extending this work

Everything above is runnable from a clone — see
[`docs/reference/benchmarks/methodology.md`](docs/reference/benchmarks/methodology.md#reproduce-it) for the
exact commands and corpus slugs. The corpora, fetch recipes, and evaluation harness are all in the repo;
nothing needs to be requested from us to start.

## Looking for a research or institutional collaborator

If you work on information retrieval, agentic evaluation methodology, or a document-heavy scientific/
scholarly workflow and think a reproducible, local-first retrieval backend would be useful to your own
research question — rather than the other way around — we'd like to hear from it. Open an issue, or reach
out directly. We're specifically interested in partners who already have a real document collection and
research question, not a generic "let's collaborate."
