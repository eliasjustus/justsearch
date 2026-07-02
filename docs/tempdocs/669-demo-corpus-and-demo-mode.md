---
title: "Deterministic demo corpus and demo mode — substrate for reproducible public demo assets and the five-minute onramp's first corpus"
type: tempdocs
status: open — design-request stub (2026-07-01); no design yet. Re-based 2026-07-02 on the merged 656 onramp (PR #44) — `examples/onramp-corpus/` now exists as the Tier-0 seed; this doc owns the demo-grade delta.
created: 2026-07-01
updated: 2026-07-02
author: filed by agent on founder direction
category: product / onboarding / demo
related:
  - 656-five-minute-agent-runtime-onramp
  - 657-install-modes-and-model-pack-decomposition
  - 642-byo-files-self-demo-eval
  - 664-eval-corpus-integrity-and-verified-identity
---

# 669 — Deterministic demo corpus and demo mode

## Purpose

Public demo assets (screenshots, a GIF/video of a cited answer over a messy multilingual corpus,
fully offline) cannot yet be produced reproducibly. **Update 2026-07-02:** the merged 656 onramp
(PR #44) shipped `examples/onramp-corpus/` — four tiny English markdown files proving the Tier-0
zero-model smoke path — which is a *seed*, not the demo substrate: it is monolingual, text-only,
and deliberately trivial. This tempdoc designs the demo-grade layer on top of (or alongside) it:

- a small, redistributable, deliberately **messy** corpus (mixed formats, at least two languages,
  at least one scanned/OCR document) — the corpus that shows off what the Tier-0 smoke corpus
  cannot (multilingual retrieval, OCR, citations over heterogeneous files),
- a way to load it to a known-ready indexed state (including the enriched/AI-ready tiers, not just
  Tier 0),
- enough determinism that a demo can be re-recorded after UI changes without re-staging by hand.

## Boundary

- The corpus must be redistributable (license-clean) and contain nothing private.
- This is substrate work: recording/publishing the actual demo assets is founder work, out of scope.
- Do not fork eval-corpus machinery — reuse the 664 identity/verification approach where corpus
  integrity matters.

## First questions (for the design pass)

- Corpus source: synthesized, public-domain assembly, or derived from an existing eval corpus?
- Relationship to `examples/onramp-corpus/` (shipped by 656): extend it in place, or a sibling
  `examples/demo-corpus/` with the onramp corpus kept minimal for the Tier-0 smoke?
- Where does "demo mode" live — a dev-runner flag, a first-run option, or a separate ingest
  command? (656's doctor/smoke pattern — `scripts/dev/doctor.mjs`,
  `scripts/dev/test-onramp-first-success.mjs` — is the shipped precedent to project from.)
- Overlap with 642 (BYO-files self-demo): does 642's harness become the demo loader?
