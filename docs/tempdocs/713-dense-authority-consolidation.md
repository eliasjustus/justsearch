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
