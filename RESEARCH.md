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
rank passages well — remains open. We have twice declined to publish a number that did not survive our own
audit.** An earlier internal number (quoted informally as "92% accuracy / 62% cheaper") turned out on audit
to conflate two unrelated measurements from a 50-query, single-model eval with no real comparison arm, and
has been retracted rather than published.

The rebuild (tracked internally as tempdoc 624) then completed its measurement machinery — a
cohort-identified, condition-paired comparison record, an LLM judge, per-cell tool-call trace capture, run
governance — and executed a fully-governed certified paired run: an agent with generic file tools versus the
same agent plus JustSearch over MCP, on English and German multi-hop corpora. That run appeared to produce a
null result. Before any claim was published, our own per-cell trace audit of the run discovered that the
with-tool arm never actually had the tools: the harness's MCP config lacked a required `"type"` field, the
Claude CLI silently dropped the server entry, and zero MCP tool invocations exist in any of the 260
with-tool cells — verified five independent ways, including a live config probe. The apparent null is
therefore an A-vs-A replication: a well-governed measurement of the noise floor between two identical arms
(its paired figure, Δ−0.027 at p=0.476, is that noise floor — not a utility result), and the drafted claim
text was withdrawn. This is the audit machinery doing its job — the same per-cell trace capture built to
satisfy the methodology bar is what caught the defect. The harness now fail-fasts on that config shape and
asserts the *offered* tool surface per cell from the CLI's own init event; the affected records are
annotated as arm-invalidated rather than deleted, so the history stays inspectable.

So no valid with-tool measurement exists yet, and the rerun is pre-registered before its outcomes exist:
first a small adoption pilot (when the tools are actually offered under a neutral prompt, do agents use them
at all), then the true certified English/German run, then extensions to a larger corpus (~2–4k documents,
where the file-tools baseline's grep strategy is projected to hit its budget limits) and a cross-lingual
condition. The mechanism analysis that survived the invalidation gives us pre-registered **predictions — not
results**: on English, parity to modest gain (the file-tools baseline is genuinely strong at a few hundred
documents); on German, a potentially large gain, because the engine ranks language-invariantly while the
file-tools baseline's synonym-guessing degrades in German. **This is exactly the kind of open, uncertain
question a research grant is well-suited to fund** — not because we're confident of the outcome, but because
the methodology to find out rigorously already exists, has now demonstrably caught its own defects, and
isn't yet resourced.

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
