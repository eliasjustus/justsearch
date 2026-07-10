---
title: "Pillar-1 in-band utility corpus: real-text distractor mass (legal+email, EN+DE) + fabricated injected gold — the measuring stick for the powered 624 Step-2 run, satisfying all seven 704 requirements at once"
type: tempdocs
status: "open — DESIGN (2026-07-10). Substrate decision RATIFIED by founder 2026-07-10 (real licensed legal+email distractor mass, EN + DE members, fabricated injected fact-chains as gold — option 1 of 704 pillar 1's three families). Attribution inputs from the 678 §Pillar-5 campaign are folded in (E5-A→D); The E5-C-v2 verdict LANDED same day: BRANCH B (encoder-domain mismatch — dense dead at every query shape; see 678 §Final attribution verdict). §Query construction proceeds on Branch B; the encoder question routes to its own investigation, not here. No implementation yet; no spend authorized here (the ~$3 smoke and the ~$60-90 powered run remain founder gates, 624 §Step-2 definition)."
created: 2026-07-10
author: agent (Fable orchestration) — filed at founder request after the pillar-5 attribution campaign; substrate choice founder-ratified same day
category: eval-infrastructure / corpus-design / agent-utility / search-quality
related:
  - 704-measurement-substrate-correct-data-program   # owns the program frame; this doc implements its pillar 1
  - 624-agentic-retrieval-eval-rebuild               # the consumer: Step 2 (current authority section, 2026-07-10) runs on THIS corpus
  - 635-contamination-resistant-eval-corpus          # owns the generator/certify machinery this design reuses
  - 678-nl-question-query-robustness                 # the pillar-5 attribution evidence (E5-A..D) that shaped §Query construction and §Honest constraints
  - 686-real-pdf-corpus-and-tika-pressure-measurement # sibling realism gap (binary/extraction leg); coordinate, don't absorb
  - 701-retrieval-quality-corpus-size-robustness     # E3: why all-synthetic corpora are structurally invalid at scale (the coupled-knob proof)
  - 666-mixed-corpus-reproducibility                 # the recipe/transient-fetch/licensing pattern this design conforms to
---

> NOTE: Noncanonical working tempdoc. Verify every cited number against the named tempdocs before
> building on it. This doc is a DESIGN; nothing here authorizes spend.

# 707 — The pillar-1 in-band utility corpus

## Why this corpus must exist (one paragraph)

Every corpus family used so far fails ≥3 of 704's seven requirements: all-synthetic corpora couple
grep-difficulty and retrieval-difficulty into one knob (701 E3 — a valid multi-thousand-doc member
was UNCONSTRUCTIBLE), public corpora are contamination-exposed for gold, and the small battlefield
corpora are headroom-free (grep baseline 0.9). The powered 624 Step-2 run — the citable U0 number —
inherits its validity from this artifact. Per 624's Step-2 authority section (2026-07-10), the run
happens on THIS corpus or not at all.

## The seven requirements (704 pillar 1, restated as acceptance criteria)

(a) contamination-free gold · (b) retrieval-in-band · (c) grep-stressing at scale ·
(d) statistically realistic text (BM25 AND embeddings behave as in production) ·
(e) reproducible · (f) size-variable at fixed queries · (g) ICP-shaped, including query realism.

## Ratified structural design (founder decision 2026-07-10)

**Real-text distractor mass + fabricated fact injection.**

- **Distractor mass (requirements c, d, g):** real licensed documents supply realistic statistics
  and real grep cost by volume. Members:
  - **EN-legal:** Caselaw Access Project text via the CLERC fetch path (CC0 underlying; recipe
    committed, content fetched transiently — the exact 666 pattern; nothing CLERC-added is
    redistributed). The paying-ICP document shape.
  - **EN-email:** Enron (established in-repo acquisition path, `convert-enronqa-to-beir.py`).
  - **DE member:** German real text (German legal text if a licensing-clean source is found;
    fallback MIRACL-de Wikipedia via the committed 666 recipe). The DE member carries the
    pre-registered strongest prediction (engine language-invariant; file-tools synonym-guessing
    collapses in German) — and per §Honest constraints below, it is where the engine's
    multilingual SPLADE+BM25 advantage is real today.
- **Gold (requirements a, b, f):** fabricated multi-hop fact-chains INJECTED into a subset of the
  real documents (embryo: `needle-burial-v1`; generator machinery: 635 + the 624 triple-injectivity
  descriptor space). Difficulty tuned by paraphrase distance — an independent knob, decoupled from
  distractor volume by construction (the direct fix for 701 E3). Injection sites and chain content
  are generated once with the local LLM and committed (determinism-by-commitment) — the fabricated
  gold is OURS and committable even though the surrounding real text is not; the corpus assembles
  reproducibly from (committed recipe + committed injections + transient licensed fetch).
- **Size (requirements c, f):** 10³ member (headroom opens near 2.7k docs per the 624 scale matrix;
  grep baseline fell 0.9 → 0.567) and a 10⁴ member, same queries at both sizes (f). Enrichment cost
  at 10⁴ is bounded by 691's throughput work; the 700 escalation fix (merged #122) protects the
  ingest from poison-pill stalls.

## Query construction — RESOLVED to Branch B (E5-C-v2 verdict, 2026-07-10)

E5-C proved query verbosity is a *load-bearing variable*, not a nuisance: BM25 LOSES 22.5 recall
points under keyword reduction on legal text, while dense stayed dead at both operating points.
Therefore, regardless of branch: **queries ship at TWO committed verbosity operating points**
(natural verbose question + short natural phrase), evaluated as strata, never averaged silently —
this is 704's scoped-claims principle applied inside the corpus.

- **Branch A — E5-C-v2 shows natural short queries recover dense:** query realism means both
  operating points are product-realistic (users and agents type short phrases; agents paste long
  questions). The dense leg's contribution then differs by stratum and the utility claim must be
  stratum-scoped. 678's per-leg query lever becomes a product workstream (not this doc).
- **Branch B — E5-C-v2 shows dense stays dead on natural short queries too:** attribution lands on
  encoder-domain fit for legal-shaped text. The corpus then must NOT be designed to flatter dense:
  the EN-legal member measures utility on the engine as it is (BM25+SPLADE-carried, per E5-D the
  RAG chunk surface reaches 0.68 gold-in-context in ~3 docs on lexical chunks), and the encoder
  question routes to its own tempdoc (model choice / domain eval — 636/580 territory), NOT to
  corpus design or 678.

## Honest constraints (from the E5-A→D evidence — do not design around them silently)

1. **Do not assume the dense leg contributes on legal-shaped documents.** Measured: raw dense R@10
   0.10 (verbose) / 0.145 (keyword) at 198 docs; +3.0 points at chunk granularity. Any corpus-level
   projection of "hybrid advantage" on the EN-legal member is dishonest until the encoder question
   resolves. The DE member and the email member carry the semantic-leg story for now.
2. **The injected-gold difficulty knob (paraphrase distance) must be calibrated per member** — the
   636/needle machinery calibrated it on synthetic filler; real legal boilerplate has different
   near-duplicate geometry (F-029).
3. **Certification gates are unchanged and mandatory:** closed-book (gold unanswerable without the
   corpus), descriptor-collision (0 gold-involved), regeneration-determinism, and the union-recall
   floor (F-028) pinned per member once measured.

## What this doc does NOT own

- Spend decisions (624: the ~$3 adoption smoke on this corpus, then the ~$60-90 powered run —
  founder gates, in that order; smoke must show headroom + adoption replicate before the run).
- The binary/extraction realism leg (686 — coordinate the EN-legal member's format choice with it;
  plain text first, PDF variants are 686's question).
- The encoder-domain question if Branch B lands (own tempdoc).
- Generator implementation details (635's machinery; extend, don't fork).

## First implementation questions (for pickup)

1. Injection mechanics into real text: append-style paragraphs vs interleaved sentences — which
   passes the closed-book gate most cleanly while keeping the host doc's statistics intact? (704's
   own first-question #2.)
2. German legal-text source with fetch-and-rebuild-compatible licensing (Rechtsprechung im Internet
   / openJur terms?) — else fall back to MIRACL-de.
3. Per-member union-recall/leak floor derivation runs once assembled (`union-recall-gate-derive`,
   `leak-gate-derive`).
