---
title: "Pillar-1 in-band utility corpus: real-text distractor mass (legal+email, EN+DE) + fabricated injected gold — the measuring stick for the powered 624 Step-2 run, satisfying all seven 704 requirements at once"
type: tempdocs
status: "incomplete — CLERC and MIRACL-DE 1k/10k members exist at verbose and short-natural strata with structural certification, but closed-book, retrieval calibration, union-recall, and leak gates remain. EnronQA is non-claim-eligible until its source license is resolved. No paid run or completion claim is authorized. 2026-07-14 takeover: founder GPU-budget + claim-shape decisions recorded and gate-run execution plan set (§Takeover 2); pre-run unblockers in progress."
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
> building on it. Nothing here authorizes spend.

## Current implementation fold (2026-07-13; incomplete through 719 repair)

The reusable substrate is productionized: `corpus-inject-real` deterministically selects real
hosts/distractors, appends or interleaves committed fabricated gold, assembles through the existing
corpus builder, regenerates in two independent Python processes, and commits only recipes, host IDs, and
fabricated inputs—not licensed host text. Every cell now has a digest manifest tying those immutable
inputs and its exact recipe to materialized provenance; certification rejects forged or untied
cross-process evidence. Certification recognizes `real-text-injection-v1`.
Strict member recipes exist for EN legal (CAP/CLERC path), DE MIRACL, and EN EnronQA. CLERC and
MIRACL-DE are materialized at 1k and 10k in separate verbose and deterministic short-natural
query strata. Their structural records certify signatures, fixed answers/evidence across strata,
1k-to-10k nesting, zero gold-involved descriptor collisions, and cross-process regeneration. They
also pin CLERC to Hugging Face revision `ef042f8ab436f78704f17faa0a866d1b2b862f6f` and MIRACL-DE to
`miracl-v1.0` through `ir_datasets` 0.5.11. They are license-eligible but not scientifically certified. EnronQA is explicitly
`claim_eligible:false`: its upstream dataset card supplies no license, so the recipe fails closed.

The 719 promotion boundary now extends this certification path: it accepts scientific evidence only
as an exact four-cell 1k/10k by verbose/short-natural matrix. Gate-specific typed measurements carry
no thresholds or `passed` assertion: certification recomputes them against the separate checked-in
pre-run policy, which is deliberately draft until the owner settles its exact cells and thresholds.
Policy and evidence bind the exact query-and-gold digest separately from the corpus signature. Gate
artifacts embed the canonical measurement plus backend run-manifest/projection bytes. The source-time
snapshot embeds and hashes the exact policy, certificate, and gate bytes only when structural, closed-book,
retrieval-calibration, union-recall, and leak evidence all pass; policy evaluation and replay validate
those embedded bytes again. Utility-run captures that snapshot and rechecks it on resume. This machinery does
not upgrade the current structural records; their scientific gates are still pending.

Still gated: closed-book certification, member retrieval calibration, union recall, leak floor, and
any paid adoption/powered run. EnronQA additionally requires an approved licensing-clean source.
The earlier email probe proves mechanism only; it cannot override license.

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
   > **CORRECTION (2026-07-12, takeover — do not build on the sentence above verbatim; full analysis
   > in §Investigation & verdict).** Its *attribution* is superseded and its *cited numbers are
   > F-032 artifacts.* Per-leg on legal (CLERC), origin/main: dense is now ALIVE — `vector`-mode
   > nDCG@10 **0.6185** (≈ lexical 0.6891; register:229), up from 0.060; the "raw dense R@10
   > 0.10/0.145" and "+3.0 pts chunk" figures are register-flagged as F-032-destroyed-chunk-vector
   > artifacts (register:820), not the shipped engine. SPLADE is revivable to 0.2588 with the shipped
   > `rag.chunk_splade.enabled` flag but stays default-OFF (hybrid-neutral at +108% cost, F-036). The
   > "encoder question" 708 was spun off to answer CLOSED 2026-07-11 with **NO MODEL SWAP** — the
   > deadness was construction (F-031 window-mean + F-032 chunk destruction), both shipped fixed
   > (#131/#139), not encoder-domain fit. **BUT** the practical conclusion survives for a *different*
   > reason: production `hybrid` on legal is still **0.556–0.562, below lexical 0.689** — an
   > unexplained residual fusion gap (708 punted it to the inbox), so the EN-legal member is still
   > lexical-dominated *as shipped*. Rewrite §1 to anchor on hybrid 0.556–0.562 + the open fusion gap,
   > NOT "dense is dead" and NOT the stale public Scorecard 0.516 (715 re-baseline unexecuted).
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

## Investigation & verdict — takeover (2026-07-12, Fable orchestration)

Read-only takeover investigation: 3 parallel Sonnet research threads (engine-truth currency,
build-readiness inventory, consumer/program currency) + first-hand reads of 704 (program frame), 708
(closure), and the search-quality register. **No code, no design changes, no spend** — this appendix
records findings + an explicit verdict only. Citations are against `origin/main`; note this worktree
HEAD (`2fb2b01`) is **2 commits behind** origin/main, which already carries the 717/718
chunk-integrity fix (#154/#155) — rebase before any build.

### Finding 1 — the Branch-B premise is superseded in *mechanism*, unchanged in *practical effect*

707's whole "measure the engine as-is, BM25+SPLADE-carried, don't flatter dense" framing rests on
Branch B = "encoders can't separate legal content (encoder-domain mismatch)." That attribution is
**falsified** on origin/main:
- **Dense on legal: ALIVE.** `vector`-mode nDCG@10 **0.6185** (R@10 0.825), ≈ lexical 0.6891
  (register:229, run `bc4bcd8`), up from 0.060 pre-fix. 708 CLOSED 2026-07-11 **NO MODEL SWAP**: the
  incumbent `gte-multilingual-base` "was never domain-limited" — deadness was construction (F-031
  window-mean/missing-CLS dilution + F-032 RMW chunk-vector destruction), both shipped fixed via
  #131/#139 (708:4,681-683). Shipped legal vector captures ~96% of the 0.643 offline ceiling.
- **SPLADE on legal: revivable-but-deliberately-OFF.** 0.0591 default → 0.2588 with
  `rag.chunk_splade.enabled`, but hybrid doesn't move (+108% enrichment cost) → stays default-OFF
  (F-036, register:750-767).
- **The number 707 actually anchors on is UNCHANGED: production `hybrid` = 0.556–0.562, still BELOW
  lexical 0.689** (register:230,754) — an unexplained *residual fusion gap*, punted to the
  observations inbox by 708 as out-of-scope (708:699). So §1's conclusion (EN-legal is
  lexical-dominated as shipped) survives, but for a fusion reason, not a dead-encoder reason.
- **Two hygiene traps for the builder:** (a) the specific figures 707 cites (dense R@10 0.10/0.145,
  "+3.0 pts chunk") are register-flagged **F-032 artifacts** (register:820) — do not import them;
  (b) the public Release Scorecard still shows legal `hybrid` **0.516** from the 2026-07-01 release
  — three fixes stale, because **715 (release re-baseline) is unexecuted** (status "seed",
  founder-scheduled). 707 must not treat the Scorecard as current.

### Finding 2 — build-readiness: everything exists except the real-text injector

Inventory (each cited): CLERC fetch (`corpus-fetch-clerc`, `corpus.py:101`/`corpus_fetch.py:207`),
Enron (`convert-enronqa-to-beir.py`), MIRACL-de (`corpus-fetch-miracl` + 666 recipe), the
triple-injectivity descriptor math (`corpus_generate.py:199-263`), closed-book + descriptor-collision
gates (`corpus_certify.py:53-154`), `union-recall-gate-derive`/`leak-gate-derive` (`gates.py:406,495`),
and the two-verbosity query-variant command (`corpus-query-variant`) — **all EXIST and are directly
callable.** The `legal-clerc-4k` 10⁴-doc distractor recipe already exists **and was already run once**
(701/F-029, register:858-880) — directly reusable as the EN-legal 10⁴ member's distractor mass.

The **one piece with zero prior art anywhere in the repo** is the **real-text injector**: the
mechanism that splices a fabricated multi-hop chain into a real host document at controllable
paraphrase distance while (a) passing the closed-book gate on real legal boilerplate and (b) staying
deterministically certifiable. `corpus_generate.generate()` only builds *fully-synthetic* corpora; a
grep across `scripts/` for inject/interleave/splice/append-gold found only false positives. Two
supporting gaps ride with it: the `regeneration_determinism` gate is hard-coupled to
`method:"procedural-fabricated"` (needs a new "real+injected" provenance branch), and a
descriptor-location decision (host docs already carry unrelated real titles).

### Finding 3 — consumer is live and unchanged; the remaining non-corpus blockers

624's Step-2 authority section (2026-07-10, verbatim-identical on origin/main) still reads: the
powered "~$60-90 founder-signed" run happens "on the 704 pillar-1 in-band corpus … which does not
exist yet" (624:4406-4408). 704 still names 707 as pillar-1's implementation; **Pillar 5 (the gating
prerequisite) is now CLEARED**. No tempdoc 709-718 defers, re-scopes, or redirects 707. Remaining
blockers *besides building the corpus*: the **640 Int8/Float32 quantization config-freeze** (still
open, FW-008) must land before baselines are pinned; both **spend gates ($3 smoke → $60-90 run)
remain founder-gated and unauthorized**. The 717/718 chunk-integrity bug that would have threatened
build fidelity is **fixed and merged** — the dense-revival premise no longer rides a coin-flip build.

### Finding 4 — no duplication, no displacement

707 retires nothing and duplicates nothing when built on the existing machinery: 686/`realdocs-v1`
is the *extraction/binary* leg (govdocs1+NapierOne), a coordinating sibling 707 already names — not
overlap; 635 is *extended* (new corpus type), not forked; 666's recipe/transient-fetch pattern and
`legal-clerc-4k` are *reused*. The only net-new build is the injector + provenance branch +
descriptor-location decision.

### VERDICT

**Should it be done? YES.** The *need* is already validated by prior evidence, not pending: 701 E3
proved the all-synthetic family structurally couples grep-difficulty and retrieval-difficulty (a
valid multi-thousand-doc member was unconstructible), and 704's seven-requirement gap analysis shows
every existing family fails ≥3 — so the powered U0 run has no valid corpus without this artifact. The
consumer (624 Step-2) is live and points here exclusively. Nothing supersedes it.

**Should it be done NOW?** Split by cost:
- **Now, ~$0, no new gate:** (i) the premise refresh — rewrite §1 per the CORRECTION above (dense is a
  real leg; anchor on hybrid 0.556–0.562 + the open fusion gap; drop the F-032-artifact numbers and
  the stale 0.516); (ii) rebase this line onto a base that includes #154/#155.
- **The cheapest decisive evidence — and the real risk — is DESIGN FEASIBILITY, not need.** The need
  is settled; what is unproven is whether fact-injection into *real legal boilerplate* passes the
  closed-book + descriptor-collision gates as cleanly as full fabrication (704's own first-question
  #2; F-029 warns real near-duplicate geometry differs from synthetic filler). That evidence **does
  not exist yet** and is buildable for **~$0** (local LLM): inject one fabricated chain into a handful
  of real CLERC docs, run `corpus-certify` closed-book + `descriptor_collision_report`. This probe
  should gate committing to the full 10³/10⁴ build — if injection can't pass closed-book on real
  legal text, the entire "real-text distractor + injected gold" substrate needs rethinking before any
  spend.
- **Founder-gated, unchanged:** the 640 config-freeze, then the $3 smoke (must show headroom +
  adoption replicate), then the $60-90 powered run.

**What it displaces/duplicates:** nothing retired; additive only — a new "real+injected" corpus type
extending 635's generator and the existing certification suite. The single genuinely-new artifact is
the real-text injector.

**One-line bottom line:** 707 is correctly on the U0 critical path and unblocked, but it was authored
on a since-falsified engine premise (§1) and its one hard, unbuilt mechanism (real-text injection past
the closed-book gate) is unproven — so the honest next move is a ~$0 premise-refresh + injection
feasibility probe, *then* the founder-gated build and spend, in that order.

## Feasibility probe — corrected framing + findings (2026-07-12)

Setting up the "injection→closed-book" probe surfaced that **the probe as this doc framed it (Q1) and
as 704 framed it (Q2) rests on a mistaken model of the closed-book gate.** Established by reading, at
zero cost:

**Finding P-1 — the closed-book gate is host-text-INVARIANT.** `closed_book_filter`
(`scripts/jseval/jseval/utility_calibrate.py:277-286`) builds its prompt purely from `q['query']` and
scores the model's memory answer against `q['answer']` — **it never sees the corpus documents.** So an
injected corpus and a pure-fabricated corpus built from the *same chains* get an **identical**
closed-book verdict by construction; the host text cannot change it. Therefore 707-Q1 ("which
injection style passes the closed-book gate most cleanly") and 704-Q2 ("does injection into real text
pass the closed-book gate as cleanly as full fabrication") are **non-questions as posed** — closed-book
is invariant to injection style and host realism (the only exception is if the *query wording* itself
quotes real host text, which 707's fabricated-entity synonym-descriptor queries deliberately do not).

**Finding P-2 — the contamination risk (R1) is structurally resolved, not something to test.** Gold
answers are fabricated attribute strings (`corpus_generate.py:42-47`, e.g. "ochre ferrolite 0047"),
unguessable by construction; closed-book ≈0 confirms it and is host-invariant per P-1. Injecting into
real text does not add contamination risk. So the closed-book cert stays a mandatory *sanity* gate on
the queries, but it is NOT the injection-feasibility discriminator.

**Finding P-3 — the REAL feasibility discriminator is RETRIEVABILITY against real distractors
(the F-029 axis), which needs a retrieval run.** `certify_corpus` itself says the retrieval-difficulty
axis "can only be measured by an actual retrieval run, NOT the no-stack cert"
(`corpus_certify.py:37-50`). The load-bearing question 707 must answer is: when a fabricated
synonym-descriptor chain is spliced into real professional prose, does the query still identify the
injected host **above real distractors**, or does the real text's near-duplicate geometry (F-029)
swamp the descriptor? This is exactly 707 Honest-constraint #2. It is host-DEPENDENT and per-member.

**Finding P-4 — descriptor placement is a real, forced design decision.** `descriptor_collision_report`
keys collisions on exact `title` match (`corpus_certify.py:128-133`); the fabricated generator "mints
each chain's descriptor into its head document's title" (`corpus_certify.py:110-111`). A real host doc
already carries its own unrelated real title, so 707 must decide where the injected chain's descriptor
lives — mint it into the host title (cleanest for the existing gate, but alters host metadata), carry
it in the body (needs the collision gate extended to scan body descriptors), or both. The probe uses
the design choice recorded below and reports collision behavior.

**Finding P-5 — probe economics (measured, not estimated).** Real-email substrate (Enron) is
**already in the HF cache** (`~/.cache/huggingface/datasets/MichaelR207___enron_qa_0922`) → free to
materialize. The dev stack is **free** (`quick_health: running=false`). The CLERC legal collection is a
**6.7 GB** fetch, currently UNcached (only an orphaned `.tmp-` staging dir from a fetch that died
~11h ago; not touched). So the cheapest decisive probe runs on the **real-email member first**
(free, unblocked, no paid-agent calls — jseval retrieval is local/GPU); the legal member's
F-029-specific retrievability is the scoped follow-up once CLERC is warm.

**Corrected cheapest-decisive-probe design (email-first):** materialize a small real-Enron distractor
mass; fabricate K gold chains via the existing generator; inject each into a distinct real email host
(two styles: append-paragraph vs interleaved-sentence; descriptor minted into host title AND kept in
body); assemble against real-email distractors; ingest + retrieval-eval (lexical/vector/hybrid) →
nDCG@10 / R@10 on the injected gold; plus descriptor-collision (pure) and a one-shot closed-book
sanity pass. Decisive read: does the injected gold retrieve above real distractors, and does the
grep-fails/dense-bridges semantic design survive real prose? Results appended below when the run
completes.

### Probe RESULTS (2026-07-12) — injection substrate VALIDATED on real professional (email) text

Ran on merged base `c20c8ba` (includes the 717/718 chunk-integrity fixes), dev stack, `jseval run
--modes lexical,vector,hybrid --pipeline --start-backend --clean`. 12 gold chains (hops=1, semantic
descriptors), n_chains×2 = 24 injected gold docs, 400 real Enron (dasovich-j) distractors, needle
~100 words into ~300-word emails.

| mode | control-fab (pure fabrication) | inject-append (needle in real email) | inject-interleave |
|---|---|---|---|
| lexical | nDCG@10 **0.313** · R@10 0.417 | **0.178** · R@10 0.250 | **0.178** · R@10 0.250 |
| vector (dense) | **1.000** · R@10 1.000 | **0.916** · R@10 **1.000** | **0.946** · R@10 **1.000** |
| hybrid | 0.887 · R@10 1.000 | 0.891 · R@10 **1.000** | **0.916** · R@10 **1.000** |

**Verdict: the "real-text distractor mass + fabricated fact injection" substrate WORKS** — for the
retrievability axis (P-3), which the closed-book gate could not test. Findings:

1. **Injected gold survives real-text dilution.** Dense retrieves the injected gold at **0.92–0.95
   nDCG@10, R@10 1.000** (every gold doc in the top-10) against 400 real email distractors — within
   noise of the pure-fabrication baseline (1.000). Splicing a fabricated synonym-descriptor chain into
   real professional prose does **not** break dense retrievability.
2. **The grep-fails / retrieval-wins gap WIDENS in real text, it doesn't narrow.** Lexical *drops*
   from 0.313 (fab) to **0.178** (real) — the real email adds more non-matching tokens, burying any
   incidental lexical signal — while dense holds at ~0.93. So the "retrieval beats a grep-agent"
   mechanism U0 measures is **stronger** on real text (gap 0.18→0.93), the opposite of a risk. This is
   the U0 thesis in miniature on the exact substrate 707 proposes.
3. **Chunking is the load-bearing mechanism, and the 717/718 fixes are a hard prerequisite.** The
   `chunk_merge` leg is ACTIVE on both injected corpora (real emails cross the chunk threshold) but was
   ABSENT on the short control docs — chunk-level dense isolates the needle chunk from whole-doc
   dilution, which is why dense stays ~0.93 despite the host swamping the whole-doc vector. The
   corpus-build integrity guard (718) + the chunk-death fix (717) I merged are therefore not incidental
   hygiene — they are a **precondition for this substrate to measure correctly** (a degenerate index
   that drops the chunk leg would silently collapse the gold's retrievability).
4. **Injection style: interleave ≥ append** (vector 0.946 vs 0.916; P@1 0.917 vs 0.833; hybrid 0.916
   vs 0.891) — both strong, interleave marginally better (needle sentences land in more chunks). 707
   can adopt interleave; the difference is within 12-query noise, so not load-bearing.
5. **Certification behaves as P-1..P-4 predicted:** descriptor-collision PASSES (0 gold-involved; the 9
   collision groups are real-email `Subject: Re:` duplicates among distractors, non-fatal); the gold's
   fabricated answers keep closed-book clean host-invariantly; regeneration-determinism SKIPS
   (`method != procedural-fabricated`) — confirming the provenance-schema extension P-4 flagged is
   required for the real+injected type.

**Honest scope — what this validates and what it does NOT.** VALIDATED: the injection *mechanism* on
real **email** (one of 707's three members), at a ~100-word-needle / ~300-word-host ratio, n=12
(`comparable=False` small-n flag — the 0.18-vs-0.93 signal is large and unambiguous, but this is a
feasibility probe, not a certified measurement). NOT yet validated: the **EN-legal (CLERC) member**,
whose F-029 near-duplicate boilerplate geometry is the harder case and whose docs are ~100× longer
(median 28.5k chars → the needle is 1 chunk among ~50, vs 1-of-2 here) — that needs the 6.7 GB CLERC
fetch (currently uncached) + a rerun, and is the scoped next probe. The DE member is untested here
too. So: mechanism proven on real professional text; per-member F-029 calibration (707
Honest-constraint #2) remains the open build-time task, now with a validated method to calibrate
against.

**Net for 707:** the one unbuilt, unproven mechanism this doc hinged on — real-text fact injection —
is now built (`scripts/jseval/experiments/inject_707_probe.py`) and shown to produce a corpus where
dense retrieval beats lexical by ~0.75 nDCG on real professional text, with a healthy chunk leg. The
substrate is viable; the remaining build work is engineering (per-member calibration, the
provenance-schema branch for the determinism gate, the CLERC/DE members) plus the founder-gated spend,
not an open feasibility question.

## Takeover 2 — founder decisions + scientific-gate execution plan (2026-07-14, Fable orchestration)

Second takeover, from a fresh worktree on `origin/main` post-#173 (the 719 merge that landed this
doc's materialization fold). Inputs: a founder conversation (2026-07-13/14) that set a GPU budget and
a claim-shape preference, plus a read-only mechanics inventory (Sonnet, this session; load-bearing
claims re-verified first-hand at `backend.py:23,61` and `commands/corpus.py:347`).

### Founder decisions recorded (binding inputs, 2026-07-13/14)

1. **GPU budget:** total dev-stack takeover for 707's scientific gates is capped at **one to two
   ~7-hour overnight windows** ("while I'm sleeping"). Daytime shakedown runs of small members are
   acceptable; the 10k builds go overnight.
2. **Claim shape: size-trend, not single-point.** The founder prefers a trend claim (fixed queries,
   grep-headroom opening with scale) over one large-corpus point. **This is already satisfied by the
   materialized four-cell matrix** ({1k,10k} × {verbose,short-natural}, nesting structurally
   certified): utility measured at both sizes at fixed queries IS the two-point size trend.
   **Decision: no intermediate rungs (2.5k/5k) will be added.** A 4-rung ladder was sketched in the
   founder conversation before #173's materialization was discovered; intermediate rungs would break
   the 719 exact-matrix promotion policy, cost extra GPU enrichment, and add no claim value (the
   claim pipeline accepts only the ratified matrix). If finer-grained retrieval-vs-size curves are
   ever wanted, they are non-claim exploratory runs and ride outside this doc's certification path.

### Execution plan for the four pending scientific gates

**Pre-work (daytime, no GPU takeover):**
- **(a) Startup-timeout lever.** Both prior gate attempts died at jseval's fixed 120 s backend health
  boundary (`backend.py:23`; kwarg exists at `:61` but no CLI/env thread-through), root cause
  undiagnosed. Fix: make the boundary overridable (env `JSEVAL_HEALTH_TIMEOUT_SEC`), then diagnose
  the real startup cost on the next live run rather than guessing.
- **(b) Closed-book CLI layout gap.** `corpus-certify` hardcodes `datasets/golden/<name>`
  (`commands/corpus.py:347`); 707 members live under `datasets/mixed/`. Fix: accept a
  family-qualified `--dataset mixed/<name>` (legacy bare names keep resolving to `golden/`).
- **(c) CLERC source re-fetch.** The 6.7 GB CLERC collection is NOT in the 709 shared cache (only an
  orphaned `.tmp-*` staging dir from a dead fetch — the known resume gap, observations:821). Network
  cost only; run in background ahead of the overnight window.
- **(d) Shakedown on the cheapest member.** Validate the full chain (materialize → ingest →
  retrieval-calibration + union-recall/leak projections → `corpus-scientific-evidence-build` →
  `corpus-certify-member`) on **MIRACL-DE-1k** end-to-end BEFORE spending an overnight window. This
  also live-verifies the 700 poison-pill escalation (compile+unit-verified only) and produces the
  first floor candidates for the draft policy thresholds.

**Overnight window(s) (the founder-budgeted GPU takeover):**
- Known harness constraint: `corpus-fidelity --start-backend` forces `--clean` (fresh ingest per
  invocation; `commands/corpus.py:460-468`), and the two query strata of a size share identical
  corpus bytes but live under different dataset dirs — so the naive path is 8 ingests (2 members × 2
  sizes × 2 strata). The runbook shares one live backend per (member, size) across both strata via
  `--base-url` where the harness allows, targeting **4 ingests**: CLERC-10k (dominant, est. ~2-2.5 h
  at legal-doc enrichment rates per 691; wide error band — 50× extrapolation), CLERC-1k (~15 min),
  MIRACL-DE-10k + 1k (well under 1 h combined at wiki-doc rates). Estimated total ~4-6 h → fits ONE
  window; the second window stays in reserve for re-runs/diagnosis.
- Operational discipline: detached `Start-Process` + done-marker + Monitor (background-shell runs die
  ~10 min in — 691 lost two A/B runs this way); 718's fail-closed completeness guard is the safety
  net against a silently degenerate index; executor runs uninterrupted (675 hard-kill resume
  unsupported).
- **Closed-book gate is NOT GPU work:** it runs via the `claude` CLI (haiku) against the query sets,
  no dev stack — but it IS a paid API call (est. low single-digit dollars across cells). Listed in
  the owner sheet below rather than silently spent.

### Owner-decision sheet (blocks promotion; none agent-decidable — 719 Increment 9)

1. **Ratify the 707 scientific policy** (`scripts/jseval/707-corpus-certification-policy.v1.json`,
   currently `status:"draft"`, empty `required_cells` — a code-enforced block at
   `corpus_certify.py:257-282`). Proposed flow: the shakedown + derivation runs
   (`union-recall-gate-derive`/`leak-gate-derive`) produce per-member floor candidates → founder
   ratifies cells + thresholds → policy flips `active` → certified runs re-execute under it.
2. **FW-008 / Int8-Float32 cohort pin** (640) — must land before baselines are pinned.
3. **Campaign matrix + statistics:** exact members/strata/query count/seed count, the meaning of
   "n≥100 paired", model cohort (haiku default per jseval cost policy), and numeric grep-headroom /
   adoption / accuracy-language thresholds.
4. **EnronQA email member:** resolve the `MichaelR207/enron_qa_0922` license, replace the email
   source, or run the campaign on an EN-legal + DE two-member matrix. (Note: `claim_eligible:false`
   is currently data in `member.v1.json`, not a runtime-enforced gate — flagged for 719's boundary.)
5. **Spend authorizations, in order:** closed-book certification (~$1-3, claude CLI), the capped ~$3
   adoption smoke, the powered run cap (~$60-90). Smoke must show headroom + adoption replicate
   before the powered run (unchanged 624 gates).

### Executed pre-work (2026-07-14, same session — all committed on this branch)

1. **Full rematerialization from committed recipes verified cross-session** — the first independent
   regeneration of the #173 artifacts. CLERC 14k host pool re-fetched (see 3) and MIRACL-DE 30k pool
   re-fetched (ir_datasets cache hit); pool sha256s match the recipes' `real_source_sha256` pins
   exactly; all 8 member cells regenerate **byte-exactly** (`assembled_digest` matches every
   committed recipe; all 8 datasets materialized in this worktree; both members re-certify
   `structurally-certified` from this checkout, corpus signatures identical to the recorded ones).
2. **Commitment-manifest CRLF bake-in found and repaired (all 8 cells were broken on origin/main).**
   `write_commitment`/strata writers used platform-default newlines → CRLF on Windows → the
   manifests recorded sha256s over CRLF bytes that git's `eol=lf` normalization then rewrote at
   commit — so `commitment.v1.json` could NEVER verify against a fresh checkout (self-consistency
   check failed for recipe.json on all 8 cells + fabricated-queries/meta on the 4 short-natural
   cells). Since 719's source capture rejects byte-drifted certification, this would have hard-failed
   the campaign at capture time. Fix: `newline="\n"` on every writer of git-committed artifacts
   (`corpus_inject.py` recipe/commitment, `corpus_query_strata.py` gold outputs,
   `commands/corpus.py` certification/evidence/666-recipe writers); all 8 commitments + both
   structural certifications regenerated in place (only hash fields + EOL changed — content and
   digests are proven identical by 1); regression test
   `test_commitment_files_are_checkout_stable`.
3. **CLERC raw source recovered without re-download.** The #173 revision-pinning changed the
   `clerc-raw` dataset-cache key, orphaning the completed 7.7 GB `resolve/main` entry; a re-fetch now
   hits HF's anonymous-download 403 (AccessDenied at the CDN hop; no HF token configured on this
   machine). Recovered by migrating the entry to the pinned key via hardlinks (HF API confirms
   `main`'s sha == the pinned revision; the entry's content signature `a23d916b…` matches the pool
   recipe's `raw_source_signature`, and the regenerated pool hash matches `real_source_sha256` —
   fail-closed layers all verify). Logged to the observations shard; residual: no cache-key
   migration story on revision bumps, orphaned 6.3 GB `.tmp-*` staging dir still leaks, and a fresh
   machine cannot re-fetch CLERC anonymously until HF quota/token is addressed (**owner note: a free
   HF token on this box removes the 403 class**).
4. **Startup-timeout lever + family-qualified `--dataset` on all four gate CLIs** landed with tests
   (`corpus-certify`, `corpus-fidelity`, `corpus-probe` were `golden/`-hardcoded — the 719 gate
   attempts could not even point at a 707 member; this, not only the 120 s boundary, blocked them).
5. **Live shakedown result (2026-07-14, ~01:10):** the full chain is green — with a warm Gradle the
   backend is healthy in **8 s** (the 719 "120 s boundary" failures were cold-build, now moot),
   MIRACL-DE-1k ingests + fully enriches in ~47-60 s, and the diagnostic probe confirms the corpus
   works as designed: control search rank=1, dense 7/20 / hybrid 8/20 head@top10 (mean rank 1.75),
   **bm25_splade 0/20** — the DE grep-collapse prediction observed live. Calibration runs must use
   `--embedding` with `hybrid` as the headline mode; bm25_splade-only reads as a false FAIL.
   **Gate-run chain HALTED by founder (2026-07-14):** one cell completed (de-miracl-1k-short-natural
   fidelity, rc=0), chain killed cleanly mid-step-2, GPU released. The runbook
   (`overnight-707-gates.bat`, session scratchpad) is parked; overnight windows are founder-scheduled
   only — do not fire the chain without an explicit go. **Windows-EOL coupling of `corpus_signature`:** materialized datasets are
   written with platform-default newlines, so all recorded corpus signatures are Windows-CRLF-locked
   (a Linux materialization would produce different signatures). Acceptable while the campaign runs
   on this box; flagged as an owner decision — LF-canonical dataset writers force a one-time
   signature re-baseline of all 8 cells (cheap: regenerate + re-certify, ~10 min, no GPU).
