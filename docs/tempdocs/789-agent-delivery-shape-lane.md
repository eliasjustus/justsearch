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
