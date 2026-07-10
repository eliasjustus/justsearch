---
title: "Measurement-substrate program: the long-term structural work that makes CORRECT retrieval/agent-utility data possible — six pillars distilled from the 624/701 campaign's failure dataset (hybrid corpus construction, eval↔production pipeline fidelity, fail-closed validity envelopes, measurement economics, the dense-legs attribution prerequisite, an isolated eval lane) with sequencing and ownership routing"
type: tempdocs
status: open — direction/program frame (2026-07-10). No implementation here; this doc owns the PROGRAM (the priority order + the unowned pieces) and routes everything already owned to its owner (675/676/678/691/639). Pillar 5 (dense-legs attribution) is the explicit FIRST pickup — it gates pillar 1's design.
created: 2026-07-10
author: agent (Fable orchestration) — distilled from the 624 scale-campaign + 701 investigation failure dataset, at founder request ("what long-term work makes this path possible, so we get correct data")
category: eval-infrastructure / search-quality / agent-eval / measurement-governance
related:
  - 624-agentic-retrieval-eval-rebuild            # the campaign whose incidents are this doc's evidence base (placebo arm, leaks, calibration economics)
  - 701-retrieval-quality-corpus-size-robustness   # the artifact-vs-defect investigation + F-029 (corpus-dependent robustness; dense legs dead on CLERC)
  - 635-contamination-resistant-eval-corpus        # owns corpus generation machinery; pillar 1 extends its design space
  - 675-agent-eval-executor-v2-in-process          # owns the executor; pillars 3+4's vehicle (validity envelope, memoized arm, resume)
  - 676-headless-eval-product-contract             # owns the eval-mode product contract; pillar 6's vehicle
  - 678-nl-question-query-robustness               # owns verbose-query dilution; pillar 5 is its highest-stakes instance
  - 686-real-pdf-corpus-and-tika-pressure-measurement  # sibling realism gap (binary/extraction leg); coordinate, don't absorb
  - 691-corpus-build-throughput                    # owns enrichment throughput; pillar 4/6's iteration-speed floor
  - 639-candidate-set-integrity-ann-recall-and-result-dedup  # owns large-N ANN questions; P1 stays parked there
---

> NOTE: Noncanonical working tempdoc. This is a DIRECTION document in the 654-660 style: purpose,
> evidence, boundaries, and first questions — not a design or an implementation plan. Verify every
> cited number against the named tempdocs/register before building on it.

# 704 — Measurement substrate: what must change so the campaign produces CORRECT data

## Why this document exists (the failure dataset)

The 624/701 campaign (2026-07-07 → 07-10) produced more measurement-validity incidents than results,
and the incidents are a coherent dataset about WHY correct data has been structurally hard:

- **The placebo arm** (624 pass 23): every with-tool measurement ever taken had silently never
  received the tools; caught only by per-cell trace capture built late.
- **Answer-key leaks** (624 pass 21/8-9): two channels (`--add-dir` traversal, accretive watched
  roots); the harness asserted forbidden states, never expected states.
- **The synthetic-corpus artifact** (701 E1-E4): the scale corpus's retrieval collapse was the
  measuring stick, not the product — grep-expense and retrieval-difficulty were the same knob in the
  all-synthetic design, so a valid multi-thousand-doc in-band corpus was UNCONSTRUCTIBLE.
- **Corpus-dependent robustness** (701 F-029): "size-robust on realistic corpora" was true-yet-nearly-
  misleading until scoped — diverse Wikipedia flat, repetitive-real legal text degrading (union recall
  0.875→0.705 over 198→4k docs at fixed queries).
- **Dense legs dead on the ICP shape** (F-029): dense/SPLADE R@10 ≤0.15 on CLERC at every size —
  "hybrid" is de facto BM25-only on legal-shaped retrieval; unattributed (product gap vs corpus
  artifact), and it poisons every downstream corpus/product decision until answered.
- **Instrument-production divergence**: eval `full` mode runs WITHOUT the cross-encoder
  (`retriever.py:23`); E5b could not even confirm CE activation; `ann_proof` evidence-availability
  (0.455 at 4k) was discovered post-run instead of gated pre-run.
- **Silent infrastructure failure**: the enrichment batch-length crash-loop (fixed on this branch,
  5 sites) wedged a run invisibly; a hard-killed run cannot resume (675); the shared dev stack lost
  one enrichment to a takeover mid-run.
- **Economics**: one tier × 380 queries calibrated at ~$220 / ~12 h; significance on the observed
  +16.7% delta needs n≈100+; the 655 steering loop needs REPEATED pilots.

One meta-signature unites these: **almost none of this is retrieval-model work.** The engine is not
the bottleneck; *knowing the truth about the engine* is.

## The six pillars (priority-ordered by causal dependency)

### Pillar 5 first — the dense-legs attribution (gates everything; ~$0; 678's lane)

> COORDINATION (2026-07-10, session close): an in-flight tempdoc
> `702-dense-fusion-score-calibration-euclidean-cosine` existed in the main checkout (uncommitted)
> when this doc was written — its title suggests a fusion-score-calibration (euclidean-vs-cosine)
> mismatch hypothesis for the dead dense leg, a candidate cause the (a)/(b) split below does not
> cover. Whoever picks up pillar 5 must read that doc first; 701's unexplained `ann_proof`
> anomaly (dense evidence 0.455 on a pipeline-complete index) may share the same root.
> Update (2026-07-10, post-close): that doc LANDED on main as PR #118 — it is committed, not
> in-flight; read it before any pillar-5 work.

Why are dense+SPLADE near-dead on CLERC-shaped legal retrieval (R@10 ≤0.15 at 198 docs, fully
comparable)? Two live explanations with OPPOSITE consequences: (a) **product gap** — verbose
citing-sentence queries dilute embeddings (678's mechanism at its extreme, compounded by very long
case docs) → fix query-side (reduction/reformulation/passage-level matching), then re-measure; (b)
**corpus artifact** — CLERC's queries are citation sentences, not user queries → the ICP corpus needs
realistic *query* construction, which redirects pillar 1. This attribution is a prerequisite, not a
follow-up: pillar 1's corpus design forks on the answer. First experiment shape: the same 198-doc
corpus with progressively reduced/reformulated queries through the staged-recall instrument — if
dense recall recovers with short queries, it's (a)-flavored with a known lever; if not, doc-side
(length/chunking) attribution follows.

### Pillar 1 — hybrid corpus construction (the measuring stick; extends 635)

Correct data needs a corpus satisfying SEVEN requirements at once: (a) contamination-free,
(b) retrieval-in-band, (c) grep-stressing at scale, (d) statistically realistic text (BM25 AND
embeddings behave as in production), (e) reproducible, (f) size-variable at fixed queries,
(g) ICP-shaped — *including query realism, not just document realism* (F-029's lesson). Every
existing family fails ≥3 (synthetic: b,d; diverse-public: a,g; repetitive-real: a,b-for-dense).

The structural design: **real-text distractor mass + fabricated fact injection.** Real (licensed or
template-derived) documents supply realistic statistics and grep-cost by volume; fabricated multi-hop
chains injected into a subset supply contamination-free gold whose difficulty is tuned by *paraphrase
distance* — an independent knob. This decouples the axes the all-synthetic design provably couples
(701 E3: distractors built from the query's own vocabulary make grep-expense and retrieval-difficulty
one knob). `needle-burial-v1` is the embryo. The local LLM is the anti-templating tool —
**determinism by commitment** (generate once, commit, certify via the existing closed-book/collision/
determinism gates), not by templating. Every downstream measurement inherits its validity from this
artifact.

### Pillar 2 — eval↔production pipeline fidelity (one pipeline, two entry points)

Numbers published from a pipeline that diverges from production carry an asterisk a hostile reviewer
will find. Rule: the eval harness exercises the IDENTICAL retrieval path `justsearch_answer` serves
over MCP; every deliberate delta (no-CE mode, eval-only flags) is a declared, register-governed
exception — the projection-not-fork discipline applied to pipeline *configuration*. Known current
deltas to close or declare: CE off in eval `full` (`retriever.py:23`), CE-activation unverifiable
from the summary (no `cross_encoder_ms`), pre-run evidence-availability not gated (`ann_proof`
discovered post-hoc).

### Pillar 3 — fail-closed validity envelopes (positive controls as machinery)

The placebo/leak/crash-loop incidents share one signature: **assertions covered forbidden states,
never expected states.** The long-term form: a per-run VALIDITY CERTIFICATE — preflight (expected
tool surface offered; corpus signature match; enrichment coverage incl. per-qrel evidence
availability; clean isolated staging; config cohort match) + postflight (adoption>0 in forced arms;
`comparable=True`; zero leak suspects) — and the harness **refuses to emit a record** when any
fails, rather than emitting one somebody must remember to distrust. 624 built most pieces
individually under fire; the work is unifying them into one envelope. Vehicle: 675's executor v2
(which also fixes resume-across-hard-kill and per-cell forensics).

### Pillar 4 — measurement economics (correct must be affordable, or corners return)

Three structural cost-cutters: **memoized invariant arm** (the grep-only baseline does not change
when only the product's MCP surface changes — cache it across 655 steering iterations instead of
re-buying it; shape already named in 675); **two-tier measurement** (cheap standing smoke
continuously — `utility-gate` — certified runs rarely); **CI-tight metrics preferred at small n**
(token/cost deltas over accuracy McNemar where the claim allows). Enrichment throughput (691) is the
same pillar's other face: iteration speed bounds how much correct data is affordable.

### Pillar 6 — an isolated eval lane (substrate)

The shared single-GPU dev stack loses runs to takeovers; GPU-bound gates cannot run in hosted CI, so
every quality ratchet is advisory. Long-term: the 676 headless-eval contract as an isolated lane (own
data-dir/lease, never contending with dev), and eventually a self-hosted GPU runner lane so the
ratchets ENFORCE rather than advise. Explicitly a later pillar — it hardens a path the earlier
pillars must first make correct.

## The meta-principle (register-grade)

**Instrument-then-ratchet, plus scoped claims.** A measured-but-ungated property is a latent
regression surface (701's principle); this campaign adds: *a claim without its corpus-class scope is
a lie by generalization* ("size-robust" was true and nearly misleading until F-029 scoped it to
diverse corpora). Long-term, the claim pipeline (623 release → scorecard → README) should carry
corpus-class scope as a mandatory field on any published number — structurally, not editorially.

## Sequencing

**5 → 1 → (3+2 together, via 675/676) → 4 → 6.** The first two decide what correct data even looks
like; the middle two make it trustworthy; the last two make it sustainable. Pillar 5 is a bounded,
$0, few-session investigation and is the explicit first pickup.

## Boundary (what this doc does NOT own)

- **No engine/retrieval-model work.** The evidence says the bottleneck is measurement, not models;
  any engine lever (query reduction, fusion weights) belongs to its owner (678/580/643) AFTER
  attribution.
- **Owned elsewhere, routed not absorbed:** executor v2 + memoized arm + resume (675); eval-mode
  product contract / isolated lane (676); verbose-query attribution mechanics (678); enrichment
  throughput (691); large-N ANN one-off (639); binary/extraction realism (686); corpus generator
  mechanics (635). This doc owns the program frame and the currently-UNOWNED pieces: hybrid corpus
  design (with 635), the unified validity envelope (with 675), pipeline-fidelity conformance, and
  the scoped-claim field.
- **No spend decisions.** The powered Step-2 run and tier sweep remain founder calls (624); this
  program neither authorizes nor blocks them — it makes their results worth the money.

## First questions for the next agent

1. (Pillar 5) Does dense recall on legal-clerc-200 recover when the 200 CLERC queries are reduced to
   short user-style queries (same qrels)? One staged-recall run per query variant, ~$0.
2. (Pillar 1) What licensed/local real-text substrate can serve as distractor mass at 10⁴-10⁵ docs
   (686's real-PDF corpus? locally-generated template corpora? user-donated fixtures?) — and does
   fact-injection into real text pass the closed-book gate as cleanly as full fabrication?
3. (Pillar 2) Enumerate the full eval-vs-production config delta (start from `retriever.py:23` and
   the `full`-mode resolution) — how many deltas exist, and which are load-bearing for published
   numbers?
