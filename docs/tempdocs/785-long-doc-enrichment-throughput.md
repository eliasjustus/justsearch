---
title: "long-doc enrichment throughput — profile and fix the 3-hour progress bar (legal enriches at 1.0 doc/s vs 20-33 elsewhere)"
type: tempdocs
status: "chartered (2026-07-22). Profiling-first: no optimization lands before the profile names the dominant cost; acceptance is a measured multiple at unchanged quality."
created: 2026-07-22
author: agent (Fable orchestration), chartered from the 2026-07-22 remaining-work map (founder-directed)
category: indexing-pipeline / performance
related:
  - 784-chunk-splade-engine-integration  # will ADD long-doc enrichment work; sequence the measurements
  - 640 (perf relative ratchets)          # the quality/latency guardrails this lane must keep green
---

## §A. Problem

Enrichment throughput on long-document corpora is ~1.0 doc/s (legal-clerc-200, release
scorecard engine table) vs 20.5 (scifact), 23–33 (miracl), 6.2 (enron). A user pointing
JustSearch at a 10k-document legal/report corpus waits ~3 hours before search quality
claims apply to their content. Nobody has profiled WHY the multiplier is ~20-30×— candidate
suspects (unverified): NER over full long-doc content, single-pass + windowed embedding
double work, chunk explosion (per-chunk encoder calls), SPLADE windowing, extraction
re-parsing, backfill batching policy interacting badly with doc size. The eval pipeline
already emits per-stage timings (`Combined backfill: … embed=…, splade=…, ner=…` worker
lines + jseval `--timeline`), so the raw signal largely exists — unaggregated.

## §B. Scope

1. **Profile:** per-stage, per-doc-size-bin cost attribution on legal-clerc-200 (and one
   781 long-doc stratum), from existing timing lines + targeted instrumentation only where
   the existing signal is too coarse. Output: a cost table naming the dominant stage(s) and
   their scaling shape (linear in chars? in chunks? superlinear?).
2. **Fix what the profile indicts** — candidate classes, chosen by evidence: batching across
   docs for the dominant encoder; window/stride policy for enrichment stages that don't
   need full coverage; NER scope policy on long docs (if NER dominates: does entity quality
   for search actually need the full tail?); parallelism/queueing if the bottleneck is
   scheduling rather than compute.
3. **Guardrails:** 640 ratchets green; retrieval quality flat within the cohort envelope on
   affected corpora (a throughput win that costs recall on 783's floor is a loss); GPU
   memory envelope respected (arena limits, F-031 history).

## §C. Acceptance

- The profile is a tempdoc section with numbers (stage × size-bin), not an impression.
- A measured end-to-end throughput multiple on legal-class corpora at unchanged quality
  (target set AFTER the profile — pre-committing a number before attribution is the
  interrogate-results failure mode).
- Register engine-performance note updated; 784 coordination note (their measurements must
  not interleave on the same window).

## §D. Notes

- This is a product-experience lane as much as an engineering one: if the honest ceiling is
  modest, progressive availability (search-while-enriching semantics, which the readiness/
  compatibility machinery partially supports) is a legitimate §B.2 alternative to raw speed.
