---
title: 732 — response-surface residuals
type: tempdocs
status: open — planned (725 remediation program), awaiting orchestrator review
created: 2026-07-14
author: agent (area agent, tempdoc 725 remediation program)
related: [725, 655]
---

# 732 — response-surface residuals

Area tempdoc for issues 3, 7, 8 of tempdoc 725's consolidated remaining-issues inventory
(`docs/tempdocs/725-agent-tool-adoption-legibility.md` §"Issue-remediation program"). Scope per
the program map: **concise-default decision memo; excerpt sanitization posture; resources/list
ordering**. This is the smallest of the five area tempdocs — issue 8 is a one-line, already-solved-
pattern fix; issue 7 is a scoped posture decision; issue 3 (the concise-mode memo) is the only
genuinely open question and is this tempdoc's main deliverable. Phases kept proportionate.

All context below is drawn from tempdoc 725's "Settled design #2," "De-risking pass #2,"
"Level-2 implementation," and "Response-shape A/B — results and judgment" sections
(2026-07-14) plus direct source reads on this branch (`worktree-725-response-legibility`,
level-2 already landed, unpushed). No new live probes were run for this tempdoc — see RESEARCH.

---

# THEORIZE

## The concise-mode decision is a cohort-allocation question, not a size question

The task framing ("should the default flip, stay opt-in, or ship with guidance") looks like a
single binary but is actually two different levers wearing one flag, because `response_format:
concise` does two structurally different things per tool:

- On `justsearch_search` (`McpToolSurface.java:707-712`), concise **omits the Preview line
  entirely** — `if (!concise) { ... Preview: ... }`. Rank/title/score, Path, Matched/Match-basis,
  and the summary/degradation/coverage lines all survive; only the match-centered excerpt window
  is dropped.
- On `justsearch_answer` (`McpToolSurface.java:600-620`, `buildConciseAnswerText`), concise
  **trims, not omits** — caps at the 3 highest-rank sections and 600 chars each (vs. up to 20
  sections at fuller length), still with the self-describing header (`:523-533`) and truncation
  remedy. The passage text survives in reduced volume; it is never removed to zero.

This asymmetry matters because the redesign's single strongest measured effect — Reads-per-search
halved (1.04→0.60, tempdoc 725 §"Deep analysis of Campaign T") — is causally attributed to the
match-centered **Preview** line (richer previews let the agent judge relevance without opening the
file). Concise-on-search is not "the same information, smaller" — it is the literal removal of the
mechanism that produced the flagship win. Concise-on-answer is a volume dial on a tool whose job
was never scent (it returns an evidence pack for synthesis, not a menu to browse), so trimming
passages there costs comprehensiveness but not the Read-avoidance mechanism.

## Curb-cut framing applied to the default-flip question

Tempdoc 725's theorization names weakest-cohort design ("no lever should *cost* strong cohorts to
help weak ones") as the acceptance frame for Tier-1/2 changes. Apply it here directly: the
redesign was validated on haiku (the intentionally weak cohort for this program), and the
Read-halving effect is exactly the kind of win curb-cut design targets — cheap for strong agents,
load-bearing for weak ones (a strong model might infer relevance from title + matched terms alone;
a weak one benefits from seeing the actual sentence). **A default flip to concise on `search`
removes a demonstrated weak-cohort win to chase an undemonstrated (single-seed, one-cohort,
churn-dominated per tempdoc 725's own admission) token saving.** That is the opposite of the
curb-cut trade the design otherwise makes throughout — every other 725 lever adds facts at zero
behavioral cost; a search-default flip is the one candidate that has a real cost to the cohort the
whole program exists to help.

`answer` does not have this asymmetry — its concise mode keeps *some* passage text at every rank
shown, so a default flip there degrades gracefully rather than deleting the lever. This is the
structural basis for the "concise default for `answer` only" middle option the task names — it
isn't a compromise chosen for its own sake, it falls out of which tool's concise mode is
subtractive-to-zero vs. subtractive-to-smaller.

## Organic adoption is itself evidence, and it is favorable to the no-flip case

5/20 B-cells (25%) invoked `response_format` unprompted, from the schema `description` field alone
(`McpToolSurface.java:260-264`) — no default flip, no instructions-text change, no client
configuration. That the channel *works* without forcing it is the strongest argument for
strengthening the description/instructions channel before touching the default: it is a $0,
zero-regression-risk lever that is already partially proven, whereas a default flip is an
unproven one-way behavioral change across every client regardless of cohort.

---

# RESEARCH

**Not warranted beyond tempdoc 725's existing research pass #2** (`docs/tempdocs/725-...md`
§"Research pass #2", Lanes 1-3, 2026-07-14). Justification: research pass #2 already answered
every externally-sourced question this decision depends on —

- the descriptive-not-imperative grammar constraint (Lane 1, Anthropic first-party guidance) —
  binding on how any instructions-text guidance for issue 3 may be worded;
- first-party precedent for `response_format: concise|detailed` as a token-economics lever
  (Lane 1: "a `response_format: concise|detailed` enum cut a worked example from 206→72 tokens")
  — already the basis for the shipped schema, not a new question;
- MCP spec/ecosystem: no result-shape or verbosity convention exists to conform to or break
  (Lane 2) — nothing has changed since 2026-07-14 that would revise this;
- the model-agnosticism axiom (Theorization, tempdoc 725) already classifies
  `response_format` as a Tier-2 economics lever ("ship on evidence") — the axiom, not new
  literature, is what this memo's evidence bar must satisfy.

The only thing genuinely new here is *product telemetry* (Campaign T's per-cell forensics,
already collected) and *cohort-allocation reasoning* (THEORIZE above) — neither is an internet-
research question. Issues 7 and 8 are pure code-posture and code-defect questions with no
external research surface (issue 7's posture question is resolved by re-reading Lane 1's own
untrusted-tool-content framing, already cited in the design below; issue 8 is a mechanical JVM
ordering defect with a proven in-repo fix pattern).

---

# DESIGN

## Issue 3 — concise-mode decision memo (main deliverable)

### Evidence recap (from tempdoc 725, not re-derived)

| Signal | Value | Source |
|---|---|---|
| Detailed-mode token cost vs 0.2.0 | +15% median cache tokens | §"Results", Campaign T vs D |
| Reads-per-search | 1.04 → 0.60 (halved) | §"Deep analysis of Campaign T" |
| Concise-mode answer size | ~4x reduction (10.4KB→2.5KB, live probe) | §"Level-2 implementation" |
| Organic `response_format` adoption | 5/20 B-cells (25%), from schema description alone | §"Deep analysis of Campaign T" |
| Accuracy delta (detailed vs 0.2.0) | +5.3pp, churn-dominated, not ship-worthy | §"Results" + §"Deep analysis" |
| Cohort coverage | haiku only (single cohort) | §"Response-shape A/B — results" |
| Seed coverage | 1 (pre-registered rule: accuracy decisions need seeds ≥3) | §"Consolidated remaining-issues inventory" item 15 |

### Recommendation

**Do not flip the default on `justsearch_search`. Consider a scoped `justsearch_answer`-only
default flip, gated on a cheap follow-up measurement below. Ship the $0 instructions/description
strengthening now, unconditionally.**

Three-part recommendation, ordered by confidence:

1. **`search` stays `detailed` by default — high confidence, no further evidence needed.** The
   Preview line is the mechanism behind the one demonstrated behavioral win (Reads halved).
   Flipping search's default trades a measured weak-cohort benefit for an unmeasured saving,
   which is backwards under the curb-cut acceptance frame this whole design already commits to.
   This is a design-consistency argument, not a new empirical claim — no measurement changes it.

2. **`answer`-only default flip is a live option, not decided here — gated on the measurement
   plan below.** `answer`'s concise mode is subtractive-to-smaller, not subtractive-to-zero, so it
   doesn't carry search's regression risk. It is also plausibly the dominant contributor to the
   measured +15% (answer's sections run up to 4096 chars × up to 20, vs. search's per-hit addition
   of a few short lines) — but Campaign T's telemetry pools both tools, so this is a hypothesis,
   not yet a fact. Do not ship the flip until the split-by-tool re-analysis (item (a) below)
   confirms the +15% is answer-dominated; if search itself is a meaningful contributor to the
   +15% even with its own concise mode untouched, that changes nothing about `search`'s own
   default (recommendation 1 stands regardless) but weakens the case that flipping only `answer`
   captures "most" of the saving.

3. **Ship the instructions/description strengthening immediately — no gate, no spend.** The
   25% organic-adoption datum proves the description channel already converts. Revise
   `RESPONSE_FORMAT_SCHEMA`'s description (`McpToolSurface.java:260-264`) and, per 655's
   single-sourced carrier, `TOOL_SELECTION_GUIDANCE` (`McpToolSurface.java:97-105`) to add one
   descriptive (never imperative) sentence naming the size/detail tradeoff explicitly — e.g.
   "`response_format: concise` returns fewer, shorter passages/omits per-hit previews for lower
   token cost; `detailed` (default) includes full match previews and rationale." This is
   descriptive fact about the result, not a command about the next call — compliant with the
   research pass's binding grammar rule and the model-agnostic axiom (adds information, no
   behavioral dependence). Keep the addition to roughly one sentence per site — 655's own
   ADR-0015 rationale for `TOOL_SELECTION_GUIDANCE` warns against small-model description bloat;
   the guidance stays comfortably inside a per-string budget on the order of the existing text
   (~500 chars today), nowhere near a size where bloat risk applies. README/client-config
   guidance is a secondary, lower-priority channel: it reaches the human operator configuring an
   MCP client, not the calling agent mid-session (per research pass Lane 1/R1: tool descriptions
   are the pre-search-visible surface; a README is not visible to the model at all) — worth a
   one-line mention in `mcp-production-server.md`, not a substitute for the schema/instructions
   change.

### Which cohort is hurt by a default flip (the analysis the task asked for)

- **`search` default flip → hurts weak agents most, directly.** The Preview line is the
  information-scent mechanism; a weak agent (haiku, the validated cohort) is the one shown to
  change behavior (fewer Reads) when it's present. Removing it by default removes exactly the
  signal weak agents were shown to use. Strong agents may tolerate the loss better (more able to
  infer relevance from title/matched-terms/score alone) but this is untested, not a basis for the
  flip either.
- **`answer` default flip → cohort-neutral by construction.** Every cohort still receives passage
  text at every shown rank; the flip trims volume uniformly. The risk here is comprehensiveness
  (fewer passages might omit the one that answers a multi-document question), not cohort-
  differential harm — a different, smaller-magnitude risk than search's.

### Measurement plan (cheap evidence first, owner-gated spend clearly marked)

(a) **$0, no owner gate — re-analysis of already-collected Campaign T data.** Split the existing
per-cell forensics (`scripts/jseval/tmp/725-response-ab/`, `725-forensics-T/`) by tool call
(`justsearch_answer` vs `justsearch_search`) to attribute the +15% median cache-token cost between
the two tools. This is a reporting query over data already captured — no new campaign, no spend.
Settles whether recommendation (2)'s "answer is the dominant contributor" hypothesis holds.

(b) **$0, no owner gate — static token-count probe.** Call both MCP tools directly (no agent
loop) across the 20 committed corpus queries in both `response_format` values and measure
text-block byte/token size. Cross-checks whether the "~4x" concise-answer reduction generalizes
across the query set or was one favorable example (tempdoc 725's own probe was a single live
validation, not a swept measurement). This is the same kind of static harness work already used
for `exposure_contrast` fixtures — no live model calls, no cost.

(c) **Owner-gated — a dedicated `answer`-only default-flip A/B**, haiku, B-condition only (search
untouched, so no A-arm needed), ~20 cells, seeds ≥3 per the tempdoc's own pre-registered rule for
any accuracy-based ship decision (item 15 of the consolidated inventory). Budget: proportionally
smaller than the $9-12/campaign full A/B since it isolates one tool and one arm — estimate
~$3-5 at the measured ~$0.22/cell rate for 3 seeds × ~20 cells is actually closer to $13, so this
should be scoped down (e.g. seed ≥3 on a 10-query subset) before authorization, or run at seed=1
as a smoke first and only extend to seeds≥3 if the direction looks promising — owner decides
budget shape at authorization time, per the existing 725 pattern (pre-register bars before
results are seen).

Ship gate: recommendation (1) is not gated on any of (a)-(c) — it follows from the design's own
acceptance frame, already settled. Recommendation (2) (the `answer`-only flip) requires (a) and
(b) as prerequisites and (c) before shipping as a *default* (as opposed to leaving it opt-in
indefinitely, which needs nothing further). Recommendation (3) ships now.

## Issue 7 — excerpt/preview raw-echo posture

### Sites (verbatim, this branch)

- `McpToolSurface.buildHitPreview` (`McpToolSurface.java:812-847`) — returns
  `window.text()` from either an excerpt-region window (worker-produced, full-content-sourced,
  `region.text()`) or a `content_preview`-field window (`fieldValue`, capped 4096 chars); **neither
  branch calls any sanitizer.**
- `McpToolSurface.callAnswer` (`:536`) — `result.context()` (detailed mode, the worker's
  `ContextBudgeter`-assembled `[From: label]\n<content>` text) appended raw.
- `McpToolSurface.buildConciseAnswerText` (`:600-620`) — per-section `window.text()`
  (`section.content()` windowed to 600 chars) appended raw at `:616`.
- Contrast — `McpSearchResultFormatter.sanitize` (`McpSearchResultFormatter.java:64-77`) strips
  control chars **and** `"`/`\`, and **is** applied, via `filterInformative`/`informativeTerms`/
  `informativeOccurrences` (`:84-153`), to matched *terms* only (the `Matched: "term"` line).

### Posture options weighed

1. **Strip control chars from excerpt/preview text — recommended.** Not the same operation as
   `sanitize()`: that method's quote/backslash stripping exists for a *different, structural*
   reason — a corpus term interpolated inside the `"..."`-delimited `Matched:` span would
   otherwise let an embedded `"` break out of the quotes in the rendered plain-text block. Excerpt
   and preview text is **not** quote-delimited (rendered as `Preview: <text>\n`, no wrapping
   quotes), so a literal `"` or `\` in corpus text — routine in CLERC legal text (citations,
   quoted holdings, possessives) — poses no delimiter-breaking risk there and must not be
   stripped. Control characters (embedded NUL/ESC/other C0-C1 controls) are a different, narrower
   concern: transport/rendering hygiene against corrupted or malformed source bytes, not content
   sanitization against adversarial injection — defensible regardless of the trust posture taken
   on the text's *content*.
2. **Balance quotes on the truncated window — rejected.** The window is deliberately a cut of a
   larger source (that's the entire point of the truncation-remedy grammar,
   `McpSearchResultFormatter.TRUNCATION_REMEDY`); quote-balancing a window that is honestly
   presented as partial is a losing, corpus-specific heuristic that risks corrupting legal
   citations exactly where the task warns against it.
3. **Do nothing — defensible, and the status quo (review NOTE) already chose it once.** Anthropic's
   own model guidance treats tool-result content as untrusted data on the *consuming* side
   (research pass #2 Lane 1) — the calling agent, not the server, is the trust boundary. This
   is a legitimate position; it's why the opus reviewer recorded it as a NOTE, not a MAJOR/MINOR,
   during level-2 review.

### Recommendation

Adopt option 1: extract the control-char-only half of `McpSearchResultFormatter.sanitize()` into
a new narrower helper (e.g. `stripControlChars(String)`), reuse it inside `sanitize()` itself (no
behavior change to the existing `Matched:` path — it already strips control chars as one of its
three conditions at `:71`) and apply the new helper at the three sites above. This is corpus-
fidelity-preserving (quotes, backslashes, all printable legal-text punctuation untouched), narrow
in scope (one new pure helper, no new machinery), and closes the one genuinely defensible gap
(malformed bytes rendering as visible corruption) without re-opening the "do nothing" question the
review already settled for printable content.

## Issue 8 — `resources/list` ordering residual

### Site

`McpToolSurface.resource()` (`McpToolSurface.java:1429-1430`):

```java
private static Map<String, Object> resource(String uri, String name, String description) {
  return Map.of("uri", uri, "name", name, "description", description, "mimeType", "application/json");
}
```

4-entry `Map.of` — the exact defect class the level-1 increment already fixed for `tool()`
(`:1377-1386`), `schema()` (`:1388-1395`), `propStringArray()`/`propEnum()` (`:1418-1427`), all of
which now route through `orderedMap(Object...)` (`:1406-1412`, doc comment there explains the
JDK `ImmutableCollections.MapN` per-JVM-salted iteration order this fixes). `resource()` was named
explicitly as the out-of-scope residual in tempdoc 725's Level-1 implementation section
(`:645-646`, "feeds `resources/list` (outside scope, trivial follow-up)"). The `resources` list
itself is already an `ArrayList` in declared push order (`:1140-1177`), so only the per-resource
key order is affected, not the resource-array order.

### Fix

One-line change: replace the `Map.of(...)` body with
`return orderedMap("uri", uri, "name", name, "description", description, "mimeType", "application/json");`
— reuses the existing helper, zero new machinery, matches the pattern already applied throughout
this file.

### Test

Mirror `McpProtocolHandlerTest.toolsList_returnsCuratedFiveTools`'s order assertion
(`McpProtocolHandlerTest.java:212-222`) for `resources/list`: POST a `{"method":"resources/list"}`
JSON-RPC request through `handler.handlePost(ctx)`, parse the captured result via Jackson (which
deserializes JSON objects into `LinkedHashMap`, preserving the wire order), and assert
`List.copyOf(resources.get(0).keySet())` equals `List.of("uri", "name", "description",
"mimeType")` — same technique, same file, new test method (e.g.
`resourcesList_returnsDeterministicKeyOrder`).

---

# DERISK

## Per-uncertainty verdicts

- **Issue 3's cohort-effect reasoning (THEORIZE/DESIGN above) — confidence: reasoned from
  in-hand evidence, not independently re-verified live.** The claim "Preview drives the
  Read-halving effect" is the tempdoc 725 authors' own causal attribution
  (§"Deep analysis of Campaign T": "richer previews demonstrably reduce file opening"), not a
  controlled ablation (concise-search vs detailed-search was never itself A/B'd — only
  0.2.0-vs-0.3.0-detailed and organic within-B adoption were measured). **Ask (owner-gated, not
  performed here per this area's scope — live probes route through the orchestrator's stack
  lease):** a cheap live check would be comparing Reads-per-search specifically on the 5 cells
  that organically chose concise vs the 15 that stayed detailed within Campaign T's own B-arm —
  this is a **$0 re-analysis of already-collected data** (folds into measurement plan item (a))
  and would directly test the causal claim this memo's core recommendation rests on. This is the
  single highest-value cheap check available and should be run before recommendation (1) is
  treated as fully closed, even though it does not require new spend.
- **Issue 3's "answer dominates the +15%" hypothesis — confidence: plausible, unverified.**
  Stated as a hypothesis throughout DESIGN, not a fact; measurement plan (a) is the intended
  resolution, not yet run.
- **Issue 7's site inventory — confidence: high, source-verified.** All four sites (three
  unsanitized, one sanitized-for-a-different-reason) were read directly on this branch, not
  inferred from the tempdoc's prose summary.
- **Issue 8's fix — confidence: very high.** Identical pattern to four already-shipped fixes in
  the same file, same helper reused, no new design risk. This is the "trivial" item the brief
  named it as.

## Confidence: 7/10

Held back from higher by: issue 3's core causal claim (Preview → Read-halving) is inherited from
tempdoc 725's own attribution rather than independently re-derived or ablation-tested by this area
tempdoc — the recommendation is sound *given* that attribution, but the attribution itself is
single-source. Issues 7 and 8 are individually 9-10/10 (source-verified, pattern-matched fixes)
but issue 3 is the harder question and caps the area's overall confidence. Held back from lower
by: no part of the recommendation requires new live-stack access to be actionable — recommendation
(1) and (3) are ready to implement now on existing evidence; only recommendation (2)'s default
flip is genuinely gated.

## Model/effort recommendation

**Sonnet, medium effort**, for all three implementation items (issue 3's description/instructions
text + measurement item (a)/(b) analysis scripts; issue 7's helper extraction + call-site wiring +
tests; issue 8's one-line fix + test) — all are bounded, verifiable, pattern-matched to existing
code in the same file. **Opus refute-first review** before commit, consistent with level-2's
staffing (the same file, same review cadence) — specifically to check the issue-7 helper doesn't
accidentally regress the `Matched:` path's existing control-char stripping, and that issue 3's
wording stays descriptive-not-imperative under the grammar rule. No item in this tempdoc needs
opus-tier *implementation* effort; the analysis (a)/(b) items are read-only re-analysis of
existing logs, well within sonnet's range.

---

# PLAN

## Implementable now (no owner gate)

1. **Issue 8 fix.** Change `resource()` to use `orderedMap(...)`
   (`McpToolSurface.java:1429-1430`). Add `resourcesList_returnsDeterministicKeyOrder` to
   `McpProtocolHandlerTest.java`, mirroring the existing `tools/list` order assertion. Verify:
   `./gradlew.bat :modules:ui:test --tests McpProtocolHandlerTest`.
2. **Issue 7 fix.** Extract `stripControlChars(String)` from `McpSearchResultFormatter.sanitize`
   (`McpSearchResultFormatter.java:64-77`); have `sanitize()` call it as its first step (no
   behavior change — verify with existing `sanitize()` unit tests, which must stay green
   unmodified). Apply `stripControlChars` at the three raw-echo sites: both return points of
   `buildHitPreview` (`McpToolSurface.java:824`, `:840`, `:844`, `:846`), `result.context()` at
   `callAnswer` (`:536`), and the per-section window text in `buildConciseAnswerText` (`:616`).
   Add unit tests asserting: (a) a control character is stripped from excerpt/preview/answer
   output; (b) a literal `"` and `\` in excerpt/preview/answer text **survive** unchanged
   (the corpus-fidelity assertion — this is the test that would catch an accidental over-broad
   reuse of `sanitize()` instead of the new narrower helper). Verify: full
   `McpSearchResultFormatter`/`McpToolSurface` test suites green.
3. **Issue 3, recommendation (3).** Revise `RESPONSE_FORMAT_SCHEMA`'s description
   (`McpToolSurface.java:260-264`) and `TOOL_SELECTION_GUIDANCE`
   (`McpToolSurface.java:97-105`) with one descriptive sentence each naming the concise/detailed
   tradeoff (wording drafted in DESIGN above; final wording is main-loop judgment per the
   delegating-to-subagents rule — a subagent may draft, main loop approves the exact string
   before commit, consistent with tempdoc 725's own staffing note that "pre-registration wording
   and response-text style decisions stay main-loop"). This is a schema/instructions-text change
   only — no version bump required if it's judged non-breaking (text-only, no new field/enum
   value), but confirm against `McpContractVersions.java` and the existing bump precedent
   (tempdoc 725 U7) before deciding; if any doubt, bump per that precedent (single-sourced
   constant, cheap). Add/update the `McpProtocolHandlerTest` description-content assertions and
   `mcp-production-server.md`.
4. **Issue 3, measurement (a) and (b).** Write the split-by-tool re-analysis of
   `scripts/jseval/tmp/725-response-ab/` / `725-forensics-T/` (item (a)) and the static
   token-count sweep script (item (b)). Both are $0, read-only/direct-call analysis — no
   campaign, no agent loop, no dev-stack lease needed for (a) (pure log re-analysis); (b) needs
   a running dev stack (direct MCP tool calls, no LLM) but not an agent/eval harness — a normal
   dev-stack session suffices, request via the orchestrator's stack lease per this program's
   orchestration note.

## Owner-decision-gated (not implementable by this area alone)

5. **Issue 3, recommendation (2) — the `answer`-only default flip itself.** Do not flip pending
   (a)+(b) results. If they confirm answer-dominance of the +15%, present the scoped A/B (item
   (c)) to the owner for budget authorization before running; do not run without explicit
   spend approval, consistent with tempdoc 725's own "run is owner-gated" pattern for every
   prior A/B in this program.
6. **The DERISK ask** (re-analyzing Reads-per-search for the 5 organic-concise vs 15
   detailed cells within Campaign T's existing B-arm) — folds into measurement item 4/(a) above,
   so it has no separate gate; flagging here only so it isn't dropped, since it directly
   underwrites recommendation (1)'s causal premise even though (1) itself ships without waiting
   for it.

## Verification

Items 1-3 verify via `./gradlew.bat build -x test` (compile) + `./gradlew.bat :modules:ui:test`
(the new and existing MCP tests) — no dev-stack or live-model tier is needed for correctness of
the code changes themselves. Item 4 needs a dev-stack session (direct tool calls only, per above).
Per `docs/reference/contributing/slice-execution.md`, an independent reviewer (≠ implementer)
should static-review items 1-3 before commit, consistent with level-2's own review cadence for
this same file.
