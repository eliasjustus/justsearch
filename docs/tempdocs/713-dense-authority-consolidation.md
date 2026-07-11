# 713 — Dense-representation authority: is the parent single-pass vector redundant now that chunk vectors live? (post-F-032 missing cell)

- **status:** takeover complete — verdict CONDITIONAL GO (2026-07-11); theorize/design/plan +
  decisive A/B in progress this session; implementation gated on founder approval
- **created:** 2026-07-11
- **updated:** 2026-07-11

## Charter question

With chunk vectors alive (F-032), does the whole-doc parent VECTOR for chunked documents still
earn its cost — or can the dense representation consolidate on chunk vectors (+MaxP/chunk_merge)
as the single authority, retiring the long-doc single-pass machinery F-031 shipped?

## Evidence that motivates the charter (verified, citable)

- The measured cells on `mixed/legal-clerc-200` at shipped defaults (711 live verify, corpus
  sha256 630f5376…): chunks-dead + parent-single-pass = vector **0.3401**; chunks-alive +
  parent-single-pass = vector **0.6180**. The cell that decides this tempdoc — chunks-alive
  WITHOUT the parent single-pass (parent on legacy window-mean, or absent) — has **never been
  measured**. F-031's lever was evaluated strictly before anyone knew every chunk vector was
  being destroyed (F-032).
- 691 §Phase M offline: pure chunk-CLS exact-NN MaxP reaches nDCG@10 **0.64 / R@10 0.85** on
  this corpus — chunk granularity alone approaches the current live 0.618.
- Cost side: 711's live run shows **101 of 198** legal parents took the `longDocWindowed`
  deferral path (the 8192-token batch-1 passes + second-RMW complexity). If the parent vector
  is redundant for chunked docs, that machinery and its cost can go.
- The parent-vs-chunk representation fork is the ORIGINAL E-5 finding that chartered 710 —
  consolidating to one authority would resolve it structurally, not just manage it.

## Cheapest evidence

ONE pipeline A/B on legal-clerc (plus scifact/enron short-doc controls if the first arm moves):
current defaults vs parent-single-pass disabled for chunked docs (chunk vectors + chunk_merge
carry the leg; non-chunked docs keep their single whole-doc vector). Compare vector/hybrid
nDCG + enrichment wall. Parity or better → consolidation is justified; a real regression →
F-031's lever earns its keep and this closes with "keep both, document why."

## Constraints / relations

- Honest framing REQUIRED: this potentially reverses part of 691's shipped F-031 lever — that
  is not a criticism of 691; the landscape changed under it (F-032). Do not frame as cleanup.
- Do NOT touch 708's encoder-domain question; this tempdoc holds the encoder fixed.
- GPU/dev-stack is shared — verify free before any run.
- Register: `docs/reference/search-quality-register.md` (read before, update before close).
  Related: F-030/F-031/F-032; 691 §G/§M/§N; 710 S-B dataflow map.

---

## Takeover investigation (2026-07-11) — verdict: CONDITIONAL GO

Investigated in worktree `713-dense` (branch `worktree-713-dense`, base `2d324ca` =
`origin/main`). Reading pass over the register (F-030/F-031/F-032, Open Questions), the two
enrichment code paths, and the 691/711 A/B history. No code changed; the decisive measurement
was pre-registered and launched (see §Measurement).

### T-1. The charter cell is genuinely unmeasured (verified)

The register's `mixed/legal-clerc-200` block has rows for exactly two of the four
(chunks × parent-vector) quadrants: chunks-dead + parent-single-pass (`f12ded5`, vector
**0.3401**) and chunks-alive + parent-single-pass (`b88e76e`, vector **0.6184** / hybrid
**0.5609**). No row measures **chunks-alive + parent-WITHOUT-single-pass**. There is no
`Q-016` in the register's Open Questions — the F-031 finding's "settles the 691 Q-016 draft"
refers to a 691-local draft, not a standing register question. So the charter question is not
duplicating an existing open item and the cell it names has never been produced. Verified.

### T-2. The deciding A/B arm is ZERO-CODE (verified in source)

`CombinedEnrichmentBackfillOps.java:335-346` gates the single-pass strategy on
`isLateChunkingParent = context.lateChunkingEnabled() && embedAvailable && …PENDING… &&
hasChunkDocs(...)`. With `lateChunkingEnabled=false`, a chunked parent falls through to the
`else if` → `embedDocIds`/`embedContents` = the ordinary **windowed (window-mean) batch**
(`:343-346`, embed at `:433-435`). Chunk-vector writing is gated by the **independent**
`context.chunkVectorsEnabled()` (`:199`), which is unaffected. Config defaults
(`ResolvedConfigBuilder.java`): `justsearch.embed.late_chunking_enabled` = **true** (`:1060`,
env-overridable), `rag.chunk_vectors.enabled` = **true** (`:1520`). So the missing cell is
produced by exactly one env flag — `JUSTSEARCH_EMBED_LATE_CHUNKING_ENABLED=false` — with chunk
vectors left on, and needs **no code**. This is the arm the seed predicted; source confirms it.

### T-3. Strong prior for near-parity (must still be measured, not assumed)

Two independent priors point toward the single-pass parent contributing little *on this corpus*:
- **711's engine-run counters**: `singlePass=135, longDocWindowed=101, arenaOomWindowed=0`. A
  large share of legal parents ALREADY fall back to window-mean at shipped defaults because they
  exceed the 8192-token single-pass limit (F-030: CLERC median 28.5k chars, 97% chunked). The
  single-pass benefit only reaches a subset of parents today.
- **691 §Phase M offline**: pure chunk-CLS exact-NN MaxP = nDCG@10 **0.64** / R@10 0.85 on this
  corpus — chunk granularity alone meets/exceeds the current live 0.618. The chunk leg is
  plausibly the real carrier of vector-mode quality here.

Per `interrogate-results`, these are hypotheses; the A/B decides. Confound to control: enrichment
completeness — the arm must reach full COMPLETED enrichment (check `singlePass`/`longDocWindowed`
/`arenaOomWindowed` counters and chunk_vector doc-count in the worker log, as 711 did) so a lower
number can't be an under-enriched index rather than a representation difference.

### T-4. What it displaces / duplicates

Consolidation would retire the **F-031** long-doc single-pass machinery: the `embedWithSpans`
whole-doc path used for parents, the `late_chunking_enabled` / `late_chunking_context_length`
config surface, and the Phase 3a-i fold-in sub-phase in `CombinedEnrichmentBackfillOps`
(`:372-431`). It does **not** touch chunk vectors (F-032 / RMW preservation) or the encoder
(708's encoder-domain lane — held fixed per charter). It structurally resolves the parent-vs-chunk
representation fork that chartered 710/E-5.

**Cost-saving caveat (interrogate before claiming):** the charter frames the single-pass machinery
as a cost worth reclaiming, but 711 measured **no throughput cost** for the current defaults arm
(engine 130.8 s vs control 141.2 s — engine *faster*, noise-level). So the primary payoff of
consolidation is **structural simplicity + fork resolution**, not a measured wall-clock win. The
A/B's enrichment-wall delta must be read honestly; if it is ~zero, the case rests on simplicity.

### T-5. Honest framing (charter constraint, upheld)

A "retire the single-pass" outcome partially reverses 691's shipped F-031 lever. This is a
**landscape change (F-032)**, not cleanup of a mistake: F-031 was evaluated correctly against the
index state then visible, before anyone knew every chunk vector was being destroyed post-write.
Consolidation is only on the table *because* F-032 revived the chunk leg. Any write-up frames it
that way.

### Verdict — CONDITIONAL GO (investigate now; implement only on parity-or-better)

**Should this be done, now?** The *investigation* — yes, now. The deciding evidence is the cheapest
possible (one zero-code config flag, one pipeline A/B on an existing corpus), it does not exist, and
it is the sole gate on an otherwise-well-scoped, fork-resolving simplification. This is not a
"wait for evidence X" deferral: evidence X is a ~10-minute measurement I can run this session.

**The implementation (retiring machinery) is gated on the A/B result:**
- Vector/hybrid **parity-or-better** with parent single-pass OFF → consolidation justified; proceed
  to design/plan the retirement (chunk vectors + chunk_merge become the single dense authority for
  chunked docs; non-chunked docs keep their one whole-doc vector).
- A **real regression** (beyond the corpus ±2σ envelope) → F-031's lever earns its keep; close
  as "keep both, document why" and record the measured cost of the single-pass as its justification.

Because the arm was fully determined during this reading pass (zero-code) and the GPU was idle, the
A/B was launched now to overlap its compute with the theorize/design writing (§Measurement).

---

## Theorization (2026-07-11) — before design

### TH-1. Three ways to frame the question

1. **"Redundant parent vector"** (charter's): does the whole-doc single-pass earn its cost on top
   of chunk vectors? Binary retire/keep.
2. **"Which dense representation is canonical"** (710/E-5 fork lens): a chunked doc's dense identity
   has TWO authorities today — the parent whole-doc vector and the per-chunk vectors, reconciled at
   query time by `chunk_merge` (MaxP). The question is less "is single-pass redundant" than "is the
   dense doc-vector a fork we should collapse."
3. **"Granularity vs context-length attribution"**: F-031 attributed the 0.06→0.34 vector revival to
   *context length* (one 8192-tok pass beats window-mean) — but that was measured against **dead
   chunks** (F-032). Post-F-032, how much of vector-mode quality is *chunk granularity* vs *parent
   context-length* is itself unmeasured. The zero-code A/B (parent window-mean vs parent single-pass,
   chunks alive in both) is precisely the attribution experiment for framing 3, not only the
   retire/keep experiment for framing 1.

### TH-2. The corpus-generalization trap (largest hidden assumption)

The charter's "retire it" framing implicitly assumes the legal-clerc result generalizes. It likely
does **not**, and this is the most important theorization finding:
- F-031 shipped with **measured controls**: enron-qa **+7% vector / +1.3% hybrid**, scifact neutral.
  Enron long emails chunk and *benefit* from the single-pass. Retiring it globally would knowingly
  reintroduce a **−7% enron vector regression** — a `fix-root-causes` / evidence-regression the
  register would catch.
- So even a clean legal-parity result does **not** license a global default flip. The honest space
  of outcomes is three, not two:
  - (a) parity on legal **and** controls neutral → consolidate globally (simplest, fork resolved);
  - (b) parity on legal but controls (enron) regress → the single-pass is a **corpus-dependent
    lever** (same shape as F-004 CC-weights, FW-001 CE-gating) — keep it, and the finding is
    "whole-doc context helps where doc semantics are coherent (email threads), is redundant where
    they are not (legal citation docs)";
  - (c) legal itself regresses → F-031 earns its keep outright, close "keep both."
- Therefore the design must treat **enron-qa (and scifact) as required controls**, not optional
  follow-ups — a default change is only in scope under outcome (a).

### TH-3. What the zero-code arm does and does NOT isolate

The arm sets the parent for chunked docs to **window-mean** (still present in the merge), not
**absent**. So it measures single-pass-parent vs window-mean-parent (chunks alive in both), not
parent-present vs parent-absent. Reasoning ladder:
- If window-mean-parent+chunks ≈ single-pass-parent+chunks → the parent *representation quality* is
  near-irrelevant on this corpus; MaxP over chunk vectors dominates the merge (consistent with 691
  §M chunk-CLS MaxP 0.64 ≥ live 0.618). Then dropping the parent vector *entirely* for chunked docs
  is low-risk — but confirming that needs a second, non-zero-code arm (parent-absent). Prefer to
  reason from the zero-code result first (charter guidance) and only build the parent-absent probe
  if the parent still looks load-bearing.
- If single-pass-parent > window-mean-parent materially → context-length is a real contributor even
  with chunks alive; F-031's mechanism survives F-032 and the machinery stays.

### TH-4. Cost honesty (interrogate the charter's premise)

The charter motivates removal partly by cost ("101/198 parents took the deferral path, 8192-tok
batch-1 passes + second-RMW complexity"). But 711 measured the current-defaults arm as
throughput-**neutral** (130.8 s engine vs 141.2 s control — engine faster, noise). Two things follow:
the second-RMW complexity was already deleted by F-032 (single bundled write per doc), so that cost
is gone regardless of 713; and the residual single-pass GPU compute did not move the wall. The case
for consolidation therefore rests on **structural simplicity + fork resolution**, and the write-up
must not overstate a cost saving the measurement won't support. The A/B's enrichment-wall column is
the honest test — report it even (especially) if ~zero.

### TH-5. Recurring system shapes

- **One-canonical-representation applied to the dense vector** (553/636 lineage, already the rule for
  `SearchTrace`). The dense doc-vector is a candidate fork; 713 either collapses it or consciously
  declares it a *governed* dual-representation with a per-member reason to change (as BM25/SPLADE/
  dense legs are deliberately plural). Multiplicity is fine when each member answers a *different*
  question (AHA); it is a fork when both answer "what is this doc's dense vector" and will drift.
- **Landscape-change lever re-evaluation.** F-032 changed the substrate F-031 was tuned against.
  "A leg-reviving fix should re-open the levers tuned against the dead-leg state" is a reusable
  practice this tempdoc is the first instance of — worth an observation, not a hard rule.

---

## Design (2026-07-11)

The design is **outcome-selected by the A/B** (§Measurement); all three branches are specified so
the founder can approve the whole decision tree, not just one path. Scope is deliberately matched to
the zero-code arm: it supports the *parent = window-mean* form (search-side unchanged), not
*parent absent* (which would need a second arm + a search-side change and is out of scope here).

### D-1. Orphan map (what a retirement removes — verified in source)

If the A/B licenses retiring the single-pass (outcome a), these are the exact orphaned surfaces —
their deletion/tombstoning is **this tempdoc's work**, not a later sweep:
- `CombinedEnrichmentBackfillOps` Phase 3a-i fold-in (`:372-431`): `lateChunkingDocIds`/
  `lateChunkingContents`, the `isLateChunkingParent` gate (`:335-339`), the `singlePass`/
  `longDocWindowed`/`arenaOomWindowed` counters, `isArenaOomFailure`, `hasChunkDocs`, `NO_SPANS`,
  and the `BackfillContext.lateChunkingEnabled` field + its wiring in `BackfillScheduler` /
  `IndexingLoop` / `InferenceCompositionRoot`.
- Config surface: `justsearch.embed.late_chunking_enabled` + `late_chunking_context_length`
  (`ResolvedConfigBuilder:1058-1075`, `EnvRegistry:~320-334`, the `ResolvedConfig` fields, the YAML
  keys, `ResolvedConfigBuilderTest`).
- **`EmbeddingProvider.embedWithSpans` and its whole span-handling path**: verified to have exactly
  **one production caller** (`:386`, always `NO_SPANS`). The `charSpans` argument is
  **production-dead already** — it is the span-mean chunk-vector technique (arXiv:2409.04701) that
  691 measured and *did NOT ship* (offline 0.64→0.41 on CLS-pooled models). Retiring the single-pass
  therefore also orphans the span path end-to-end (`OnnxEmbeddingEncoder.embedWithSpans` +
  `EmbeddingService.embedWithSpans` + `EmbeddingShape` span machinery + the two
  `…LateChunking…`/`…LongDocForensic…` encoder tests). This is a meaningful simplification — but it
  removes a *tested, deliberately-retained* capability, so the plan tombstones it with a pointer to
  the 691 §M evidence rather than silently deleting, in case a future non-CLS encoder (708's lane)
  revisits canonical late chunking.

Not touched by any branch: chunk-vector writing (`chunkVectorsEnabled` path, F-032), `chunk_merge`
search fusion, non-chunked-doc embedding (one whole-doc vector via the normal batch — those docs
never enter the single-pass because `hasChunkDocs` is false), and the encoder itself (708).

### D-2. Branch A — global consolidation (if legal parity AND enron/scifact controls neutral)

Flip `justsearch.embed.late_chunking_enabled` default to **false**, then delete the orphan surface in
D-1 (a default flip alone leaves dead config; the tempdoc-is-your-contract rule means the retirement
is the deletion, not just the flip). Result: chunked docs' parent vector = window-mean (cheap,
already computed in the normal batch), and the dense authority for chunked docs is carried by
chunk vectors + `chunk_merge` MaxP. The parent vector remains present (window-mean) so the search
side and the union-recall/leak gates are structurally unchanged; only its *derivation* simplifies.
Non-chunked docs: identical to today.

### D-3. Branch B — corpus-dependent lever (if legal parity but enron/scifact regress)

The single-pass is **not** a redundant fork; it is a corpus-adaptive quality lever (F-004 / FW-001
family). Design conclusion: **keep it, default-on, document the boundary** — "whole-doc context
revives the parent vector where document semantics are coherent (email threads, academic abstracts)
and is redundant where chunk granularity already separates the content (legal citation docs)."
No new structure: building runtime corpus-detection to toggle it would add exactly the per-corpus
apparatus F-004 already declined to build (mode selection is left to the operator). 713 closes as a
**finding**, not a code change. This is the most likely outcome given TH-2.

### D-4. Branch C — F-031 vindicated (if legal itself regresses)

The single-pass earns its keep even on the corpus that motivated the doubt. Close "keep both";
record the measured legal regression as F-031's standing justification. No code change.

### D-5. Reach — the design's principle and its retirement condition

- **Principle (name): one canonical dense representation per document; multiple dense *vectors* are
  legitimate only when each answers a different retrieval question.** The parent whole-doc vector and
  the per-chunk vectors are a *fork* iff they both answer "what is this doc's dense identity" and are
  merged as interchangeable (today's `chunk_merge`). They are *not* a fork if the parent answers
  "whole-doc topical gist" and chunks answer "best-matching passage" — distinct questions, MaxP is
  the reconciliation. **Where else it applies:** SPLADE has the same parent/chunk structure
  (`chunk_merge` on the sparse leg too) — the same question could be asked of the SPLADE parent.
  **Existing violation candidate:** none proven; 713's A/B is the instrument that decides whether the
  *dense* pair is a fork or a governed dual-representation.
- **Evidence it earns its keep:** the A/B resolves the dense pair one way or the other with a single
  measurement; if the principle is real, the same one-measurement test transfers to the SPLADE pair
  without new apparatus.
- **Retirement condition:** if the A/B shows the parent and chunk vectors are genuinely
  non-substitutable on *every* tested corpus (each contributes independently everywhere), then
  "collapse the fork" is the wrong frame — they are a deliberate dual-representation and the principle
  retires into "governed multiplicity," not "one authority." Do not keep invoking a
  fork-collapse principle against a pair the evidence says is genuinely dual.
- **Guard against premature abstraction:** no generalized "representation registry" or SPLADE-side
  change is built now — 713 only touches the dense pair its measurement covers. The SPLADE
  transfer is *recorded as candidate scope*, not built.

---

## Implementation plan (2026-07-11) — FOR FOUNDER APPROVAL

No plan-mode/ExitPlanMode is available to this run, so this is written as an approval-gated plan.
**No feature code has been written; no PR opened.** Only the tempdoc and the decisive measurement
(read-only eval) are produced this session. Nothing below executes without an explicit go-ahead.

### Step 0 — decisive A/B (this session; §Measurement holds results)

Zero-code. `mixed/legal-clerc-200`, modes `vector,hybrid`, byte-identical corpus, back-to-back
detached runs on the same machine (711 template):
- **CONTROL** = shipped defaults (`late_chunking_enabled=true`): expected ≈ vector 0.618 / hybrid
  0.559 (reproduces `b88e76e`; a same-session re-run removes cross-commit doubt).
- **ARM** = `JUSTSEARCH_EMBED_LATE_CHUNKING_ENABLED=false`, chunk vectors on: the never-measured cell.
- Counter/confound capture per run (worker log): `singlePass` / `longDocWindowed` / `arenaOomWindowed`
  and `chunk_vector` doc-count, to prove both arms reached full COMPLETED enrichment (control for
  under-enrichment before reading any delta — `interrogate-results`).
- Decision rule vs the corpus ±2σ envelope: parity-or-better → Branch A candidate (pending controls);
  regression → Branch C.

### Step 0b — required controls BEFORE any default flip (gates Branch A)

Per TH-2, a legal-parity result does **not** license a global default change on its own, because
F-031 shipped a measured **+7% enron vector** gain. Run the same two-arm A/B on **`mixed/enron-qa`**
and **`beir/scifact`**. Only if both are neutral (within envelope) does Branch A proceed; if enron
regresses, the outcome is Branch B.

### Step 1 — Branch A execution (ONLY if Step 0 + 0b clear it) — approval-gated

1. Flip the `late_chunking_enabled` default to `false` in `ResolvedConfigBuilder` and delete the
   orphan surface enumerated in **D-1** in the same PR (teardown rides along — the config flip alone
   would leave dead machinery, violating tempdoc-is-your-contract).
2. Tombstone (not silent-delete) the `embedWithSpans` span path with a pointer to 691 §M, since it is
   a deliberately-retained but now production-dead capability (D-1).
3. **Delegable to a `sonnet` subagent** (bounded, mechanical, verifiable): the orphan deletion +
   test-fixture updates, with a self-contained brief listing the exact D-1 sites and the acceptance
   criterion "`./gradlew.bat build -x test` green + `:modules:worker-services:test` +
   `:modules:worker-core:test` + `:modules:configuration:test` green." Orchestrator keeps: the A/B
   judgment, the register update, and the merge.
4. Validation: `spotlessApply` → `build -x test` → the three module test suites → a fresh
   full-mode gate run on legal-clerc (`lexical,splade,vector,hybrid`) confirming union-recall ≥ 0.87
   / relevance + leak gates green (the F-032 publish-step shape) → re-confirm enron/scifact controls.
5. Register: update the `mixed/legal-clerc-200` baseline block (new default rows), move the charter
   cell from "never measured" to a Finding (new F-0xx, honest F-032-landscape framing), add the
   enron/scifact control rows, run `node scripts/docs/skills-sync.mjs` if the register is touched.

### Step 2 — Branch B or C (doc-only) — no approval needed beyond register update

Record the finding (corpus-dependent lever / F-031 vindicated) in the register Findings + the
`late_chunking` config note; no code change. Add the SPLADE-parent transfer (D-5) to Open Questions
if Branch A/B suggests it is live. Fold observation shards; update this tempdoc status to closed.

### Out of scope (explicit)

Parent-vector-*absent* variant (needs a second arm + search-side `chunk_merge` change); the encoder-
domain question (708); any SPLADE-side change (recorded as candidate scope only); building runtime
corpus-detection to auto-toggle the lever (F-004 already declined that apparatus).
