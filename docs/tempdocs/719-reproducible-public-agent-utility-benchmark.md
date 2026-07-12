---
title: "Reproducible public agent-utility benchmark: convert the internal token-efficiency measurement into a one-command, outsider-runnable public artifact — the trust/attention layer that separates JustSearch from the unverifiable-claim crowd. A skeptic clones the repo, runs one command, and gets the same numbers we publish. Reproducibility (not magnitude) is the moat: the competitive research found attention tracks reproducibility + narrative + install-friction, NOT the size of the percentage."
type: tempdocs
status: open — direction/pickup (2026-07-12). No implementation here. Owns the PROGRAM of turning the 624 measurement into a public, reproducible benchmark artifact. First concrete deliverable is cheap and uses data already in hand (the retrieval-step re-slice); the corpus and the powered run are owned/gated elsewhere and consumed, not built, here.
created: 2026-07-12
author: agent (Fable, strategy session) — filed at founder request after the token-efficiency competitive-benchmark research
category: eval-infrastructure / go-to-market / benchmark-publication / search-quality
related:
  - 624-agentic-retrieval-eval-rebuild            # the internal measurement machinery this publishes (utility-run / utility-comparison.v1)
  - 707-pillar1-inband-utility-corpus              # the contamination-free in-band corpus this benchmark ships/consumes (built there, not here)
  - 704-measurement-substrate-correct-data-program # the substrate program; this is its outward-facing conversion step (pillars beyond measurement)
  - 667-benchmark-release-external-baselines-and-research-md  # the existing public benchmark-release surface (RESEARCH.md, release.v1.json) this extends
  - 623-reproducible-benchmark-release             # the canonical-record + reproducible-release discipline this conforms to
---

> NOTE: Noncanonical working tempdoc. Direction/pickup document in the 654-660 / 704 style: purpose,
> boundary, first questions — not a design or implementation. Verify every cited number against the
> named tempdocs before building on it.

# 719 — Reproducible public agent-utility benchmark

## The goal (north star)

An outside developer clones the public repo, runs **one command**, and reproduces the same
agent-utility numbers JustSearch publishes — token-efficiency first, accuracy second. The artifact is
**runnable, not just readable**: raw per-query results, the corpus, the harness, and the exact command
are all public, so a skeptic can re-derive the headline rather than trust it.

This is the **conversion step**: turning the internal `utility-comparison.v1` measurement (owned by
624) into an external, self-serve artifact. It is the trust/attention layer, not more measurement.

## Why this, why now (the evidence)

A competitive-benchmark research pass (2026-07-12) on the agent-tooling projects that won attention by
leading with token-efficiency produced one decisive, counter-intuitive finding:

- **Magnitude is not the moat.** Cq won ~225 HN points with **no token number at all** (pure
  narrative); Code-review-graph claimed **49× fewer tokens** and got **12 points**. Same category,
  and the number-size predicted attention *backwards*. Attention tracked **reproducibility +
  narrative + install-friction**, not the size of the percentage.
- **The "96-99%" blockbusters are a different, easier metric** (fixed MCP-schema-overhead elimination,
  or isolated retrieval-step tokens with no accuracy gate) — NOT JustSearch's harder, accuracy-gated,
  end-to-end doc-QA measurement. The one true methodological cousin (CodeGraph: same agent
  with-vs-without the tool, real task, end-to-end tokens) scored **47% mean / 59% median** and went
  viral (0→47k stars) largely on **published raw per-repo numbers + reproducibility**, not on the
  percentage alone.
- JustSearch's honest current number (~**25.7% fewer tokens end-to-end**, accuracy-gated, CI reported,
  n=12 pilot) is roughly half CodeGraph's, on a *harder-measured* basis — respectable, not
  blockbuster. Its genuine, uncopyable edge is **rigor + honesty**, which only becomes an asset if it
  is *legible to outsiders* — i.e. reproducible.

So the bottleneck to "showing off" is not a bigger number; it is the **public artifact around the
number**. This tempdoc owns that artifact (minus the demo, see Boundary).

## What this owns

1. **A one-command public reproduction.** From a clean clone: fetch/build the committed benchmark
   corpus, run the agent-utility comparison, emit the same headline metrics. Conforms to 623's
   canonical-record + reproducible-release discipline; extends 667's existing public benchmark surface
   (`RESEARCH.md`, `release.v1.json`), does not fork it.
2. **Published raw results, not just a headline.** Per-query token/accuracy deltas, the exact model,
   corpus signature, CLI version, and CI/cohort identity — the CodeGraph lesson (raw per-repo numbers
   published) applied here. The honest-methodology story (the placebo-arm catch, the synthetic-artifact
   correction) made concrete as a reproducibility narrative.
3. **Two honest metrics, clearly distinguished** (the research's legitimate-reframe finding):
   - **End-to-end token efficiency** (the ~25% number): total context to answer a real question,
     accuracy held equal. The rigorous headline.
   - **Retrieval-step-only token cost** (Semble's methodology): tokens to *locate* the correct
     document vs tokens to *answer* — a distinct cut of the same experiment, plausibly a much larger
     honest gap (grep burns tokens on failed exploratory searches). **Published as a separate,
     labeled metric, never as a rebrand of the 25%.** This is the single cheapest path to a
     peers-frame-competitive number and uses data already in hand.
4. **A benchmark that an outsider can trust *because* they can run it** — the reproducibility itself is
   the differentiator vs the unverifiable-claim crowd.

## First concrete deliverable (cheap, do first)

**The retrieval-step re-slice of the existing pilot data.** The n=12 (and any future) pilot's per-cell
tool-call logs already record token counts per tool call. Slice them into "tokens spent locating the
right document(s)" vs "tokens spent reading/answering," for both arms, and report the located-phase
delta as a distinct metric. ~$0 (no new agent run — reads existing logs). Verify feasibility against
the captured per-cell `tool_calls` token fields first; if the phase boundary isn't cleanly derivable
from current logs, that gap is this tempdoc's first small harness ask.

## Boundary (what this does NOT own)

- **NOT the demo.** The visual/airplane-mode demo is explicitly out of scope for now (founder
  direction 2026-07-12) — it will come, but not here.
- **NOT the corpus construction.** The contamination-free, in-band, grep-stressing corpus is 707's
  (pillar-1). This benchmark *consumes and ships* it; if 707's corpus isn't ready, this can bootstrap
  on the existing fabricated corpus (`battlefield-en-scale-v1`) with the honest caveat that grep
  succeeds there (no accuracy headroom) — the token-efficiency + retrieval-step metrics are still
  valid on it (contamination-robust), which is exactly why token-efficiency leads.
- **NOT the powered accuracy run.** The ~$60-90 certified accuracy run is a founder spend gate (624
  §Step-2). This tempdoc makes whatever run *is* authorized reproducible; it does not authorize spend.
- **NOT engine changes** (encoder-domain fit is 708; fusion is 712/713). A benchmark measures; it does
  not fix.
- **NOT a code-search benchmark.** JustSearch's wedge is messy heterogeneous *documents*, not code
  (native agent grep already wins on code, and the NL encoder is domain-mismatched on code — see the
  encoder-domain-mismatch finding, 678/708). A code benchmark would be fighting on the peers' turf
  where JustSearch is structurally weaker. If a code run is ever done, it is a private *diagnostic*
  ("does it even work on code?"), not a headline benchmark — and not this tempdoc's deliverable.

## First questions for the next agent

1. Is the retrieval-step phase boundary derivable from the existing per-cell `tool_calls` token logs,
   or does the harness need a small capture addition? (Do this first — it is the cheapest honest
   bigger-number.)
2. What is the *minimum* corpus that can ship publicly with the repo (license-clean, small enough to
   clone, contamination-free) so the one-command reproduction works out-of-the-box — 707's pillar-1
   corpus, a subset, or the fabricated `battlefield-en-scale-v1` source (regenerable, already
   committed)?
3. Where does the reproduction command live so an outsider finds it in 30 seconds — a `RESEARCH.md`
   section (667), a top-level `BENCHMARK.md`, a `make benchmark`? (Install-friction is an attention
   variable per the research.)
4. What is the honest headline sentence, and does it lead with token-efficiency (contamination-robust,
   ready) rather than accuracy (gated on 707 + the powered run)?
