---
title: "German semantic bridging collapses at 10k: the engine's semantic leg bridges zero-lexical-overlap German descriptors at ~half CLERC strength at 1k and goes dark at 10⁴ (hybrid 0.043, union recall 0.10) on post-F-031/F-032 code — the 707 engine finding, chartered as its own attribution investigation (NOT a 708 re-litigation)"
type: tempdocs
status: "open — charter/stub (2026-07-16). No implementation here; the takeover/theorize/design passes expand this document. DE remains a 1k-only secondary stratum, never claim-bearing, until this closes."
created: 2026-07-16
author: agent (Fable orchestration), chartered at founder direction ("go ahead as you suggested") after the 707 chain-2 verdict routed the finding to an encoder-lane successor
category: search-quality / dense-retrieval / multilingual / attribution
related:
  - 707-pillar1-inband-utility-corpus       # the measurement that produced the finding (chain-2 8-cell matrix, DE v2 recalibration verdict)
  - 708-encoder-domain-fit-legal-professional-text  # CLOSED (NO MODEL SWAP, EN-legal): do not re-litigate its bake-off; this doc is the *German-at-scale* question 708 never tested
  - 678-nl-question-query-robustness        # the pillar-5 attribution playbook this charter reuses (eliminate-in-order discipline)
  - 636-retrieval-buried-signal-long-documents  # staged-recall instrument — the diagnostic machinery here too
  - 704-measurement-substrate-correct-data-program  # program frame; scoped-claim principle applies to any close of this doc
---

> NOTE: Noncanonical working tempdoc. STUB + investigation charter. Verify every inherited
> number against tempdoc 707's chain-2 table and the cited run artifacts before building on it.

# 748 — German semantic bridging at scale (the 707 DE engine finding)

## The question (falsifiable)

On `mixed/de-miracl-*` (real German Wikipedia distractor mass + fabricated DE v2 gold at
hops=1, pure zero-lexical-overlap synonym descriptors), measured 2026-07-16 on one engine
cohort ≡ origin/main post-#201 (i.e. WITH the F-031/F-032 fixes shipped):

| cell | hybrid nDCG@10 | union recall | lexical leg |
|---|---|---|---|
| de-miracl-1k-verbose (v2) | 0.2053 | 0.40 | 0.0 |
| de-miracl-1k-short-natural (v2) | 0.2660 | 0.40 | 0.0 |
| de-miracl-10k-verbose (v2) | **0.0431** | **0.10** | 0.0 |
| de-miracl-10k-short-natural (v2) | **0.0428** | **0.10** | 0.0 |

The lexical zero is the *pre-registered, confirmed* German grep-collapse prediction — not the
finding. The finding is the **semantic leg**: it bridges German synonym descriptors at roughly
half CLERC's strength at 1k and collapses by 10⁴ (union recall 0.40 → 0.10), while the same
engine on EN legal text stays in-band at 10k (0.3238) and on EN email reaches 0.66–0.80.

**This doc's question:** is the 10k collapse a property of (a) the incumbent encoders'
*German* representation quality, (b) a scale/candidate-depth interaction specific to this
corpus shape (ANN/fusion starving a weak-but-nonzero leg as distractor mass grows), (c) the
DE v2 *gold design* (pure zero-lexical-overlap descriptors are a strictly harder task than
CLERC/email gold — a corpus artifact, not an engine defect), or (d) German text mechanics
(compounding/tokenization) in chunking/indexing?

## What is already eliminated (do not re-test)

- **Chain length:** DE v2 regenerated at hops=1 (parity with CLERC) moved 1k by only
  +0.02–0.04 and 10k not at all (707 chain-2, commit 8a562519). Largely refuted.
- **Query shape:** verbose vs short-natural differ by ≤0.06 at 1k, ≈0.000 at 10k. Not the axis.
- **F-031/F-032 construction defects:** fixed and shipped before this cohort ran; the EN
  members' health on the same cohort is the positive control.
- **708's EN-legal bake-off verdict (NO MODEL SWAP)** stands for EN-legal; nothing here
  reopens it. 708 never measured German at 10⁴ — that gap is exactly this charter.

## First experiments (all ~$0, short local GPU runs, signature-bound)

1. **Gold-design control (decides (c) cheaply):** build an *EN* gold stratum with the identical
   zero-lexical-overlap synonym-descriptor construction on the existing EN distractor mass
   (raw-Enron or CLERC hosts) at 1k+10k. If EN-synonym gold also halves at 1k and collapses at
   10k, the finding is task-shape, not German — close with a scoped claim and re-design DE gold
   with partial anchors (707's option (b), now justified by a design pass).
2. **Staged-recall decomposition on the existing de-miracl-10k artifacts** (projections already
   on disk from the 4 runs): where does gold exit the funnel — embedding candidate set, ANN
   truncation, or fusion rank? Distinguishes (a) from (b) without a single new run.
3. **Candidate-depth sweep at 10k** (if 2 implicates depth): does raising the vector leg's
   candidate K recover union recall? A recovery ⇒ (b), a knob decision; no recovery ⇒ (a).
4. **Chunk-granularity probe on DE** (708 measured +3pts on EN-legal — expected low yield, but
   German compounding makes doc-vs-chunk a live variable; one run per size).

## Boundary

- **No corpus redesign here** — 707 owns corpus design; this doc may *recommend* a DE gold
  iteration but not execute one.
- **No model swap without 708-grade evidence** — any candidate-encoder claim needs the same
  signature-bound bake-off discipline (Gate 0, sign tests) 708 set as precedent.
- **No claim changes until closed** — DE stays 1k-only secondary stratum, stratum-scoped
  claims only (the ratified policy deliberately excludes DE cells).
- Close = an attribution verdict ((a)/(b)/(c)/(d) or a scoped combination) + either a routed
  fix with an owner, or an honest scoped claim per 704's scoped-claims principle.
