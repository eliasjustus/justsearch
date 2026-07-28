---
status: "chartered (2026-07-28). Owner lane for the delivery-shape work tempdoc 788 §3.A theorized. Phase 1 (behavioral telemetry) is $0 and implementation-licensed; Phase 2 (framing probe, paid cells) is design-licensed but SPEND IS FOUNDER-GATED. Source evidence: register F-043, 782 §I, the transcript census + statistics under tmp/hero-arc-analysis/ (methodology in 782 §I / F-043)."
created: 2026-07-28
---

# 789 — Agent-delivery shape lane: behavioral telemetry + the framing probe

## Why this lane exists (one paragraph of evidence)

The 782 hero campaign's mechanism analysis showed the tool's accuracy deficit is a
delivery-shape effect, not a retrieval effect: the name-pivot rate (does any tool call carry
the hop-1 entity's name) predicted enron-1k outcomes perfectly in the baseline arm (27/28
correct cells pivoted, 0/32 wrong cells did) and the tool arm's pivot deficit (16 vs 27)
matched its net loss (−11 vs −12); 38% of breaks were pure hop-1 stopping; the tool arm
abstained 2× baseline at identical fabrication counts, including replay-verified cells
denying gold present in their own output; `justsearch_answer` involvement anti-correlated
with success in all three strata. Retrieval was exonerated (span carriage 0.80–0.88; the
gold was routinely delivered). The response shape terminates the loop.

## Phase 1 — behavioral telemetry (implementation-licensed, $0)

Make the census's explanatory metrics standing, per-run outputs of the agent-utility
harness, so no future campaign needs a hand census:

1. **Per-cell behavioral record** emitted alongside the existing observation projection:
   - `name_pivot` (any tool-call input contains a token of a delivered entity that is not in
     the question — generalized from the census's hop-1-person definition; the census script
     `tmp/hero-arc-analysis/census/pivot.py` is the reference implementation),
   - `hop1_stop` (final answer names a delivered intermediate entity, gold absent),
   - `abstained` / `fabricated_specific` / `format_near_miss` (the wrongness taxonomy,
     scripted classifier from `wrongness.v1.json`'s rules),
   - `fallback_after_mcp` (any native file-tool call after the first MCP call),
   - `searched_before_grep` (bool), `distinct_queries`, `post_search_reads` (exists in §E.4
     derivation — promote it into the harness),
   - per-tool mix (`justsearch_search` / `justsearch_answer` counts).
2. **Per-turn receipts**: persist per-turn cost/usage into the transcript metadata (the burn
   analysis found NO per-turn series is currently persisted — `burn.v1.json` documents every
   field checked). Needed for burn curves and any budget-matched design.
3. **Validation bar**: replay the shipped classifiers over the 2026-07-28 window-2 logs and
   reproduce the census numbers (pivot 27/16 etc., `name-pivot.v1.json`,
   `wrongness.v1.json`) exactly. That replay IS the acceptance test — no new spend.
4. Composer surfaces the aggregates in `utility-comparison.v1.json` under a new
   `behavioral` block (descriptive only; no gate reads it yet — substrate-with-consumer:
   the Phase-2 probe is the named consumer).

Honest limits: the generalized `name_pivot` definition must be validated against the
hop-1-person ground truth on the hero corpora before it is trusted on any other corpus; the
entity source is delivered-span NER or corpus-side entity fields — decide at design time and
record which.

## Phase 2 — the framing probe (design-licensed; measured cells FOUNDER-GATED)

One controlled question: **does response framing move the behavioral metrics and accuracy,
holding retrieval fixed?** Arms are framings of the SAME retrieval results:

- F0 (control): current delivery.
- F1 (continuation): delivered spans carry intermediate-entity marking + one continuation
  sentence ("this sentence names ⟨entity⟩; n further documents reference it").
- F2 (evidence-not-answer): answer-shaped prose replaced by explicit evidence framing
  ("matches terms X,Y; not necessarily an answer").
- F3 (calibrated absence): empty/thin results carry coverage + explicit
  absence-is-not-evidence framing.
- (Candidate F4, only if cheap: compact-first delivery — ids + one-line spans, expand on
  demand — which also attacks the exhaustion-loop economics: exhausted tool cells ran ~3×
  the MCP calls at $0.040/turn.)

Design constraints inherited from 782's lessons: question-level statistics as the primary
test (cell-level McNemar overstates — 782 §I second-pass), budget-matched arms, the
mixed-model guard env pins, gitignored run dir, dry-run the scoring policy against a
synthetic record before first spend. Naturalistic replication requirement (788 §4): before
any framing ships as default, its effect must be checked on a natural-question set
(enron-qa), not only fabricated chains — the Goodhart guard.

Decision rule to pre-register at probe charter time (not here): which metric movement
licenses shipping which framing, and the fallback if framings help behavior but not
accuracy.

## Explicitly out of scope for this lane

Engine-side hop-2 (multi-hop inside `justsearch_answer`, 788 §3.A.4) — a competing branch
that Phase 2's results should arbitrate before anyone charters it; retrieval changes of any
kind; MCP schema changes (F-016: schema complexity hurts small models — framing must live in
response CONTENT, not new parameters, until measured otherwise).

## Relation to standing principles

This lane is the D-005 observability move one layer up (788 §2): the delivery layer observed
by continuation-survival. Phase 1 is deliberately the cheap observability half shipped
before any behavioral lever — the same sequencing D-005 used (staged recall accounting
before fusion levers).

## Phase 1 implementation log (2026-07-28)

Shipped; $0 (no model call anywhere in the work or its acceptance test). Phase 2 remains
design-only — nothing in this increment changes response framing.

### What shipped

| Charter item | Where | Note |
|---|---|---|
| 1. per-cell behavioral record | `scripts/jseval/jseval/agent_behavioral.py` (new) | classifier core; `agent_utility_observations.read_inspect_observations` emits `behavioral` on every observation |
| 1. delivered-span half | `agent_utility_inspect._record_cell` → `metadata.behavioral_delivered` | `name_pivot` / `hop1_stop` need raw tool-RESULT text, which exists only in-process |
| 2. per-turn receipts | `agent_utility_inspect._one_attempt` → `metadata.turn_receipts` | one entry per streamed `AssistantMessage` |
| 3. replay validation | `scripts/jseval/experiments/replay_behavioral_789.py` + `tests/test_replay_behavioral_789.py` | reproduces the census exactly (table below) |
| 4. composer block | `utility_recompose.finalize_observation_groups` → `record["behavioral"]` | descriptive only; excluded from `semantic_digest` |
| 6. tests | `tests/test_agent_behavioral_789.py` (29 cases) | classifier units, capture, composer neutrality, evidence round-trip |

Field set as chartered: `name_pivot`, `hop1_stop`, `abstained` / `fabricated_specific` /
`format_near_miss` (+ `wrong_value`, `harness_error` to close the taxonomy),
`fallback_after_mcp`, `searched_before_grep`, `distinct_queries`, `post_search_reads`,
per-tool `tool_mix`. `fallback_after_mcp` uses the charter's wider definition (ANY native
file tool after the first MCP call); the census's Grep-only variant ships alongside as
`grep_fallback_after_mcp` rather than replacing it — on window-2 they differ a lot (B arm,
enron-10k: 53 vs 22), so collapsing them would have silently redefined a published number.

### Replay validation (charter item 3 — the acceptance bar)

Offline over the 2026-07-28 window-2 hero logs, no spend. Every number below is asserted,
not eyeballed; the script exits non-zero on any disagreement.

```
stratum|arm                        cells  pivot hop1_stop  abst  fabr  near srch1st
-----------------------------------------------------------------------------------
en-email-enron-raw-10k-verbose|A      60     23         8    23     9     0       0
en-email-enron-raw-10k-verbose|B      60     22         9    21    10     0      60
en-email-enron-raw-1k-verbose|A       60     27        16    11    19     0       0
en-email-enron-raw-1k-verbose|B       60     16        20    21    19     1      60
en-legal-clerc-1k-verbose|A           60     17        12    24    13     0       0
en-legal-clerc-1k-verbose|B           60     20        12    27     9     1      60

OK: window-2 census reproduced exactly by the shipped classifiers
```

Matches `name-pivot.v1.json` (pivot, correct/wrong-with-pivot), `wrongness.v1.json` (all
five classes) and `hop1-stopping.v1.json` (`stopped_at_hop1`, `gold_present_in_answer`)
cell-for-cell, plus `searched_before_grep` at 60/60 per with-tool stratum (0/60 baseline —
the A arm is offered no MCP tools, so it cannot search first, and asserting that too keeps
the metric from passing vacuously).

### The generalized-vs-census definitions

The charter required emitting both wherever a generalization diverges from the census's
corpus-specific rule. Two axes, and they landed differently:

* **Identifier shape (wrongness).** The census hand-wrote a 6-alternative regex of the
  injected identifier shapes. The harness derives the shape from each cell's OWN gold
  answer (`gold_shape_pattern`: uppercase runs → `[A-Z]{n}`, digit runs → `\d{n}`,
  lowercase runs stay literal). The replay runs BOTH and asserts they agree — they do, on
  all 360 cells. So no second field was needed here; the divergence check is the assertion
  that keeps it that way.
* **Entity source (`name_pivot` / `hop1_stop`).** These genuinely differ and both ship.
  The census supplied the hop-1 person from corpus ground truth; the harness default
  extracts entities from the DELIVERED tool-result text and drops any whose long tokens
  the question already contained. The replay binds the census (supplied-entity) arm; the
  generalized arm is unit-covered.

**Honest limit (unchanged from the charter, now concrete):** the generalized entity source
cannot be cross-validated on window-2, because an Inspect log persists tool-result DIGESTS,
never content — the entities a cell was handed are unrecoverable from those logs. So on
every pre-789 log `name_pivot`/`hop1_stop` are `null`, a TRI-STATE the schema, sanitizer and
aggregate all preserve (`{"true": 0, "known": 0}`, never counted as "did not pivot"). The
two sources become directly comparable on the first campaign recorded with this increment in
place; until then the generalized definition is unit-validated, not corpus-validated.

### Per-turn receipts — what the SDK actually exposes

`burn.v1.json` was right that nothing per-turn was persisted; it was not right that nothing
per-turn EXISTS. `AssistantMessage.usage` is a per-message receipt on the SDK dataclass
(the same field `usage_accum` was already max-folding into a lower bound), so one entry per
assistant turn is now recorded: `{i, model, stop_reason, usage, tool_calls_issued}`.

**Limitation, recorded rather than papered over:** the SDK exposes NO per-turn USD.
`total_cost_usd` exists only on the terminal `ResultMessage`. The series is therefore
per-turn TOKENS plus a terminal cost scalar — enough for a token-burn curve and for
budget-matched design at token granularity; a per-turn dollar curve remains available only
by apportioning terminal cost, which the harness does not do and does not fabricate. Burn
curves that need dollars must state the apportionment they assume.

### Descriptive-only, and why it is digest-excluded

The composed record's `behavioral` block is attached AFTER `claim_verdict` is computed, so
no gate, verdict or comparability rule can read it by construction (asserted:
`test_behavioral_block_changes_neither_the_verdict_nor_the_digest`). It is excluded from
`semantic_digest` on a weaker rationale than the four fields already excluded, and the pin
test now says so verbatim: the block is NOT a re-derivation of already-digested content (the
wrongness classes derive from answer text the record does not carry). It is excluded because
including it would change the digest of any pre-789 campaign recomposed from its raw logs,
breaking the publication builder's recompose check on already-published records. When Phase
2 gives the block a deciding consumer it moves INTO the digest as a declared schema change.

Verified on the real record: recomposing window-2 from raw logs produces a populated block
(B/enron-10k: `abstained` 21, `fabricated_specific` 10, `searched_before_grep` 60/60,
`justsearch_search` 205, `justsearch_answer` 68 — matching `tool-mix.v1.json`) and the
identical `semantic_digest` with and without the block.

### Verification

`python -m pytest scripts/jseval/tests/ -q` → 2576 passed / 3 failed before the two pin
tests were deliberately updated (`_NON_SEMANTIC_TOP_LEVEL_FIELDS` and the observation-schema
key set — both exist precisely to make this kind of growth a visible, justified act) and the
known `test_percentile_within_bounds` flake; green after. No Java touched.

## Phase 2 implementation log — framing substrate (2026-07-28)

Shipped; $0 (no model call anywhere in the work or its verification). This increment ships the
three framings as **flag-gated substrate, ALL DEFAULT OFF** — it does NOT run the probe and does
NOT change any shipped delivery. Measured cells remain founder-gated.

### Seam map

The framings live entirely in MCP response CONTENT. No MCP schema or tool parameter changed
(F-016), no retrieval path changed.

| Seam | File:line | Role |
|---|---|---|
| Framing logic (all three) | `modules/ui/src/main/java/io/justsearch/ui/api/mcp/McpDeliveryFraming.java` (new, 1) | Pure static functions + `Settings` record; unit-testable without a backend |
| F1 computation | `McpToolSurface.java:955` (`buildSearchContent`, per-hit loop) | Continuation line per delivered hit |
| F2/F3 computation | `McpToolSurface.java:1001` / `:1008` (`buildSearchContent`, tail) | Response-level header + absence note |
| F2 answer computation | `McpToolSurface.java:619` (`buildAnswerContent`) | The one framing the charter applies to `justsearch_answer` |
| F2 text placement (search) | `McpToolSurface.java:1062` (`renderSearchText`, head) | Header leads the delivery |
| F1 text placement | `McpToolSurface.java:1099` (`renderSearchText`, per-hit) | Line directly under the excerpt it qualifies |
| F3 text placement | `McpToolSurface.java:1116` (`renderSearchText`, after result count) | Block under "Found N results" |
| F2 text placement (answer) | `McpToolSurface.java:649` (`renderAnswerText`, head) | Prepends above the pre-existing "Evidence pack" line |
| Structured-tier projection | `McpEvidenceProjection.java:91` (per-hit `continuation`), `:100`/`:103` (`evidenceHeader`/`absenceNote`), `:288` (answer `evidenceHeader`) | Tier equivalence (735 G3) — a structuredContent-only client must sit inside the probe arm too |
| Content models | `McpSearchResponseContent.java:27`, `McpAnswerResponseContent.java:25` | Framings are content-model facts, computed once, read by both renderers |
| Settings resolution | `McpDeliveryFraming.resolveSettings()`; call sites `McpToolSurface.java:518` (answer), `:841` (search) | Mirrors `resolveDeliveryBudgetBytes()`; null store → `Settings.OFF` |

### Config names (and why)

Followed the existing `search.mcp_delivery.budget_bytes` precedent (same subsystem, same file),
with the four related settings grouped into a nested `McpFraming` record mirroring the established
`Search.Corrections` pattern rather than adding four flat components to an already-large record.

| Key (`-D` sysprop) | Env var | Default |
|---|---|---|
| `search.mcp_framing.continuation_enabled` | `JUSTSEARCH_SEARCH_MCP_FRAMING_CONTINUATION_ENABLED` | `false` |
| `search.mcp_framing.evidence_not_answer_enabled` | `JUSTSEARCH_SEARCH_MCP_FRAMING_EVIDENCE_NOT_ANSWER_ENABLED` | `false` |
| `search.mcp_framing.calibrated_absence_enabled` | `JUSTSEARCH_SEARCH_MCP_FRAMING_CALIBRATED_ABSENCE_ENABLED` | `false` |
| `search.mcp_framing.thin_result_floor_bytes` | `JUSTSEARCH_SEARCH_MCP_FRAMING_THIN_RESULT_FLOOR_BYTES` | `400` |

**What the probe arms will set** (one framing per arm, per the charter):

* F0 (control) — nothing set. This is the shipped default.
* F1 — `-Dsearch.mcp_framing.continuation_enabled=true`
* F2 — `-Dsearch.mcp_framing.evidence_not_answer_enabled=true`
* F3 — `-Dsearch.mcp_framing.calibrated_absence_enabled=true`

Sysprops are preferred over env vars on this platform (Windows env vars unreliable). Framings
compose, so a later 2-factor cell can set any subset without code change.

### Rendered before/after (captured from the real renderers, not hand-written)

**F1 — continuation.** Query: *"what happened to the Q3 hedging memo"*.

```
 [1] Q3 hedging memo (score: 0.91)
     Path: docs/memos/q3-hedging.md
     Preview: Please route the Q3 hedging memo through Vince Kaminski before the Friday close.
     Matched: "hedging" in content_preview
+    note: this excerpt names "Vince Kaminski" — 12 of the documents matching this search also
+    reference it. If that is an intermediate fact rather than your answer, a follow-up search
+    for it may locate the final answer.
```

(rendered as one unwrapped line). A query that already names the entity renders identically to
OFF — asserted by `f1SuppressedWhenQueryNamesEntity`.

**F2 — evidence, not answer.** Search (`justsearch_search`):

```
+Retrieval evidence — 1 document matches on "hedging". These are lexical and semantic matches to
+your query, not verified answers to your question — read the excerpts and judge for yourself
+whether they answer it.
+
 [1] Q3 hedging memo (score: 0.91)
     ...
```

Answer (`justsearch_answer`) — the charter's carve-out, and it was NOT structurally messy, so it
ships as chartered:

```
+Retrieval evidence — 2 passages from 2 documents, selected by lexical and semantic match to your
+query. This is retrieved evidence, not a verified answer to your question — the passages may be
+relevant without containing the answer.
+
 Evidence pack: 2 passages from 2 documents (retrieval mode: HYBRID). No synthesized answer is
 included. Pack selection: 2 of 4 candidate passages (retrieval coverage 0.50).
```

**F3 — calibrated absence.** Zero-result search for *"quarterly hedging policy"*:

```
 Found 0 results (took 6ms).
+
+10432 documents are indexed and were searched for "quarterly hedging policy". No document
+matched. Absence of results is not evidence of absence: the index may phrase the fact
+differently, the document may not be indexed, or the match may sit in a field this query did not
+reach. Before concluding the information does not exist, try alternate phrasings or narrower
+terms; if you have native file tools, reading or grepping the source directory directly will
+settle it.
 Hints:
 - No results found. Try broader terms, or use justsearch_status to check what's indexed.
```

### Decisions the charter left open, and how they were settled

**Entity source (F1) — the facet snapshot, not query-time NER.** `callSearch` already requests
facets over `entity_persons_raw` / `entity_organizations_raw` / `entity_locations_raw`
(`McpToolSurface.java:796-798`), so the response arrives carrying the per-document NER entity
values (tempdoc 326) for the matched set, with counts. F1 reads that snapshot and keeps only
entities the delivered excerpt actually contains. Zero new query paths, zero query-time NER.
Per-hit entity fields were checked first and are NOT carried on `Hit.fields()` — the facet
snapshot is the only existing surface that has them.

**Count semantics — scoped wording, not the charter's phrasing.** The charter suggested "the
corpus contains further documents referencing it". The available count is a MATCHED-document
tally, not a corpus-wide document frequency: `FacetingEngine` tallies facet values over the docs
matching this query (its own comment: "facet value <= matchedDocs by construction"). Reporting it
as a corpus count would be a fabricated statistic, and a true corpus count needs a second query
path the charter rules out. So the count ships with honest scoped wording — *"N of the documents
matching this search also reference it"*. This is a deliberate deviation from the charter's
example sentence, not an oversight.

**Thin-result measure (F3).** Delivered body = per-hit title + path + preview + matched terms,
summed. Deliberately excludes response scaffolding (result count, facets, hints) so boilerplate
cannot lift a substantively empty delivery over the floor, and excludes the framing lines
themselves so F1's continuation lines cannot suppress F3 — asserted by
`continuationDoesNotInflateBodyBytes`.

**Corpus coverage source (F3).** `KnowledgeStatus.docCount()` via the adapter — the same surface
`justsearch_status` already renders. Read at most once per search and only when F3 is on. When
unavailable it returns `-1` and the coverage clause is omitted rather than guessed
(`unavailableDocCountOmitsCoverage`).

### Verification

* `./gradlew.bat build -x test` — green.
* `./gradlew.bat test` (full unit suite) — green.
* New: `McpDeliveryFramingTest` (21 cases: F1 entity-not-in-query incl. partial-overlap and
  case-insensitivity, F2 header composition and plural agreement, F3 zero/thin/substantive
  boundary and missing-doc-count, Settings composition and null-store resolution) and
  `McpFramingRenderSnapshotTest` (10 cases: rendered before/after per framing, both tiers,
  F1+F3 composition, and the all-OFF control arm).
* **The strongest default-off evidence is a test I did not write:** `McpTierEquivalenceGoldenTest`
  asserts the text renderer reproduces byte-goldens captured from 0.3.1, and it stayed green
  through this increment — so the shipped default is byte-identical to pre-789 delivery, not
  merely believed to be. `McpTierEquivalenceTest`'s reflective guard also forced every new
  content-model component to be declared and projected onto `structuredContent`, which is why the
  framings reach both tiers rather than text only.

### Honest limits / notes for the probe

* **Governor interaction.** `McpDeliveryGovernor` re-renders through a view lambda when it drops
  tail results; framing settings and the doc count are resolved OUTSIDE the lambda, so every
  degradation step renders under one framing decision. A consequence worth knowing at probe time:
  because the F3 thin measure runs on the SURVIVING hits, a heavily-degraded delivery can newly
  trip the thin floor. That is arguably correct (the agent really did receive little text), but it
  means F3's trigger rate is not independent of payload size.
* **F1 emits at most one line per hit and 3 per response**, so its payload cost is bounded; the
  cap is a constant in `McpDeliveryFraming`, not a config key, because the probe does not vary it.
* **`response_format: concise` suppresses F1 in the TEXT tier.** Caught by the post-implementation
  critical-analysis pass, not by a test: concise mode omits the `Preview:` line, so a continuation
  reading *"this excerpt names X"* would have pointed at text the agent was never shown in that
  tier. The line is now gated on the same `!concise` condition as the preview it annotates. The
  structured tier is unaffected — it carries per-hit `excerpts` unconditionally, so the claim stays
  true there and the fact is projected regardless of density (`f1SuppressedInConciseTextTier`).
  Probe arms use the default (verbose) density, so this does not narrow F1's arm.
* **F1 is silent when NER has not completed** (no entity facets → empty vocabulary). A probe arm
  must confirm enrichment coverage before attributing an F1 null-effect to the framing rather than
  to a missing entity source.
* Not done here (correctly out of scope): engine-side hop-2, retrieval changes, the F4
  compact-first candidate, and the naturalistic-replication (enron-qa) check the 788 §4 Goodhart
  guard requires before any framing ships as a DEFAULT.

## Phase 2 probe pre-registration — 789-P2-probe-2026-07-28 (frozen at first measured cell; amendments dated)

Written BEFORE any measured cell. Substrate: the F1/F2/F3 framings merged in #321 (all
default-off, byte-golden-verified inert when off); the behavioral telemetry merged in #319.
Founder spend authorization 2026-07-28: "you can proceed with the remaining items 2 and 3"
(item 2 = this probe, presented as a few tens of dollars).

**Question.** Does response framing move the tool arm's behavioral metrics and accuracy,
retrieval held fixed?

**Arms (backend -D sysprops at serve boot; one framing per arm, no compositions):**
F0 control (all off) | F1 search.mcp_framing.continuation_enabled=true |
F2 search.mcp_framing.evidence_not_answer_enabled=true |
F3 search.mcp_framing.calibrated_absence_enabled=true.

**Design.** Stratum mixed/en-email-enron-raw-1k-verbose only (the question-systematic
mechanism stratum; pivot deficit 11). Condition B ONLY (framings cannot affect arm A; the
campaign's A baseline is context, not a test arm). The frozen 20 qids, seeds {0,1}.
4 x 20 x 2 = 160 cells, sonnet, max_budget $0.80/cell (hero-comparable), est ~$56
(campaign B mean $0.358/cell), ABORT if projected total exceeds $90. Env pins per the 782
Amendment-2 lesson (ANTHROPIC_DEFAULT_HAIKU_MODEL=claude-sonnet-5,
CLAUDE_CODE_SUBAGENT_MODEL=claude-sonnet-5); gitignored run dir; detached serve + driver;
porcelain-0 asserted per arm.

**Primary outcomes (behavioral, from the #319 block):** name_pivot rate, hop1_stop rate,
abstained rate; secondary: fallback_after_mcp, post_search_reads, EM accuracy vs F0, cost,
duration, exhaustions. **Primary test:** question-level paired sign-flip permutation
(Fx vs F0 per qid, seeds as replicates), alpha=0.05. n=20 questions powers only large
effects — this probe is pre-registered as a DIRECTION-FINDER, not a shipping gate.

**Decision rule (pre-registered).** A framing GRADUATES to the naturalistic replication
step (enron-qa) if (a) pivot or hop1_stop improves at question-level p<0.05, OR (b) it
point-improves >=2 behavioral primaries AND accuracy point-delta >= 0. A framing is
DROPPED if its accuracy point-delta < -0.05 regardless of behavioral movement. Anything
else: inconclusive — report, no ship, founder decides any larger probe. NO framing ships
as default from this probe alone; naturalistic replication is a hard prerequisite
(the 788 S4 Goodhart guard).

**Validity checks (pre-registered, per arm before analysis):** single resolved model per
cell (mixed-model guard clean); one MCP tool-surface hash across ALL arms (framing is
response content, not tool schema — a surface split voids the probe); a 1-query smoke per
arm asserting the framing text APPEARS in the delivered payload (positive control) and a
F0 smoke asserting its ABSENCE; zero leak-suspect cells; behavioral block present on every
record. Known limits recorded up front: single synthetic stratum, B-only, 2 seeds, and the
generalized name_pivot definition (validated against this corpus's ground truth in the P1
replay, exact agreement on 360/360 cells).
