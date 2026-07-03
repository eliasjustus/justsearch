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

## The agent-utility question, answered honestly — and what's still deferred

**Does JustSearch's retrieval actually help an agent complete a task, not just rank passages well?** An
earlier internal number (quoted informally as "92% accuracy / 62% cheaper") turned out on audit to conflate
two unrelated measurements from a 50-query, single-model eval with no real comparison arm, and has been
retracted rather than published. The rebuild (tracked internally as tempdoc 624) replaced it with a
cohort-identified, condition-paired comparison harness — seeded paired runs, per-cell tool-restriction
verification, an LLM judge with cross-family calibration, explicit comparability accounting — and the first
certified round of that measurement has now been run (2026-07-03). The result is an honest null:

<!-- agent-utility-claim:begin -->
> On two held-out, closed-book-certified, contamination-free synthetic corpora of buried-fact multi-hop
> retrieval queries (English and German; 390 documents each; paraphrase-bridged descriptors, collision-free
> by construction), an agent with JustSearch's MCP retrieval added to its existing file tools showed **no
> measurable effect on accuracy** (pooled n=260 paired, Δ −0.027, McNemar p=0.476; per-corpus Δ −0.069 /
> p=0.200 and +0.015 / p=0.860) **and no measurable token-cost difference** (mean Δ +449 unique tokens,
> CI95 [−1467, +2376]). Every cell completed (zero exclusions); tool restrictions were verified per cell
> from tool-call traces; answers were judge-scored (hybrid EM → local LLM judge, zero verdict flips) with
> the judge calibrated against a two-model cross-family panel (κ ≥ 0.94, labeled non-human). This
> measurement covers text corpora only — a degraded-scan member was designed but is currently unmeasurable
> (the degradation defeats both the agent's vision and the extraction pipeline). Replacing file tools
> entirely with retrieval (substitution) was separately measured significantly harmful and is reported
> diagnostically only.
<!-- agent-utility-claim:end -->

(The numbers above are projected from the committed run records under `scripts/jseval/624-run-2026-07-03/`
and checked in CI — they cannot silently drift from the measurement.)

What we think the null means, honestly: it is informative about the regime it tested — a few-hundred-
document, clean-text corpus that an agent with generic file tools can already navigate on its own. In that
regime, adding retrieval neither helped nor hurt, and we won't claim otherwise. It does not test the regime
the product thesis actually lives in. Round two is designed for exactly that regime: corpora at a scale
where brute-force exploration is infeasible; real format heterogeneity (email, PDF, Office documents); a
cross-lingual member where the query language differs from the document language — grep-proof by
construction; pre-registered coverage-shaped claims (the fraction of queries only answerable via retrieval)
rather than mean deltas; and a model-tier sensitivity check. That round is designed but not yet resourced.
**This is the open research question a grant is well-suited to fund** — not because we're confident of the
outcome (round one is exactly why we aren't), but because the methodology to find out rigorously already
exists and the honest round-one answer makes the next measurement better-posed, not weaker.

The round-one run also surfaced product findings now tracked as ordinary engineering work — most notably
that the extraction pipeline does not yet abstain cleanly on degraded scanned documents, which is being
fixed independently of any research claim.

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
