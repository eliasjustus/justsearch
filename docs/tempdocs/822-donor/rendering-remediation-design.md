# Rendering remediation — the executable design for the four causes

```
status: DESIGN COMPLETE (no code written)
created: 2026-08-13
author: design agent (implemented none of it; the gap report's author implemented none of F1-F9)
input:  docs/tempdocs/822-donor/response-rendering-gap.md  (the problem statement; its file:line
        evidence is the map — every claim below was re-verified against source, corrections noted)
charter: docs/tempdocs/822-t3code-search-window.md §2 (donor laws), §3 (import-bridge clause),
        §4b SCOPE GRANT + CONTAINMENT RULE
donor:  docs/tempdocs/822-donor/t3code-system.md (T3 Code, MIT, T3 Tools Inc.)
scope:  design only. Every section below is written so an implementer executes it without judgment
        calls; where a judgment call is genuinely the owner's it appears in §6, not inline.
```

---

## 0. The contract this design works under

**CONTAINMENT (charter §4b).** Shipped-window visual behavior must not change except where a named
defect is being fixed. This design honors it three ways, in descending strength:

1. **Identical defaults.** Every geometry value extracted into a custom property keeps *exactly* the
   literal it replaced as its default (`border: none`, `line-height: 1.6`, …), so an un-overriding
   consumer renders byte-alike by construction, not by inspection.
2. **Opt-in variant.** Every rule that does not exist today (headings, tables, `hr`, `img`, task
   lists, `:last-child` zeroing) lands behind a host attribute the shipped consumers do not set.
   Adding those rules unconditionally *would* change shipped rendering (UA default → designed), and
   no defect is named for the shipped window's unstyled headings — so they stay off there.
3. **Named-defect exceptions, enumerated.** Exactly four shipped-visible behavior changes are
   intended, each tied to a defect in the gap report: §3.3's wrong-target citation link, §3.4's
   non-monotonic mark palette, §3.2's underline wall, §3.1's surviving raw brackets. They are listed
   again in §5.5 so a reviewer can check the diff against a closed list.

**Corrections to the gap report, verified at source** (it is dated history; these are not criticisms
of it, they are the map's errata):

- *"three shipped consumers"* → **four shipped consumer files, ten shipped call sites**:
  `UnifiedChatView.ts:4491,5120,5450,5455,5462,5566,5571`, `SummarizeView.ts:235`,
  `NavigateView.ts:143`, `ReasoningBlock.ts:181` (nested), plus sv3's `Sv3Main.ts:901,1116`.
- *"no measure cap"* is true of **sv3 only**. The shipped chat already caps:
  `unifiedChatStyles.ts:543-549` sets `max-inline-size: var(--measure-prose)` (`tokens.css:352`,
  `88ch`) on the assistant block. The measure is a **consumer** concern; no component change.
- The `[From: …]` header has **three emitters**, not one: `ContextBudgeter.java:95`,
  `TokenAwareBudgeter.java:130`, `DocumentService.java:144` (default impl). All three must move
  together, and there is a **real parser** downstream — `OnlineModeOps.formatContextAsNumberedPassages`
  does `startsWith("[From: ")` — plus six test files asserting the literal.
- The gap report's `sv3-ask.ts:120-131` citation-merge citation is stale; the merge is
  `sv3-ask.ts:207-223` (`mergeClaim`), fed from `:297-314` (delta) and `:315-328` (matches).
- **`chunkIndex` is not one field with two meanings — it is two fields with one name.** On
  `rag.citations[i]` it correctly means the chunk's ordinal inside its parent document (it is what
  `lookupChunkContent` needs). On a citation *match* it means the position in that array. The defect
  is the shared name plus one wrong producer, not the concept.

**Verification stance.** Static green ≠ live working (`static-green ≠ live-working`). Every slice in
§5 carries both a test inventory and a live-verification line; the two that need a loaded model say
so explicitly (`ai-offline-isnt-a-wall`).

---

## 1. CAUSE 1 — PROMPT SHAPE (`RAGQAStyle`)

### 1.1 What is wrong

`RAGQAStyle.java:25-32` is the only `PromptContributor` on the ask path (`RAGAskShape`, priority 10)
and says nothing about output form. The model therefore emits prose with no headings, no backticks,
no paths. Precedent that a prompt in this package may specify markdown shape:
`URLEmissionGrammar.java:71-99` (`##` headings, fenced blocks, inline backticks, `**bold run-in:**`).

### 1.2 The instruction text (verbatim; implement exactly this)

A **new contributor**, not an edit to `RAGQAStyle`'s fragment. Rationale below in §1.3.

```java
private static final String FRAGMENT_TEXT =
    "Write the answer in Markdown, and let the answer's real shape choose the markup.\n\n"
        + "- Plain paragraphs are the default. A question with one answer gets one paragraph:"
        + " no heading, no list, no closing summary.\n"
        + "- Use a `##` heading only when the answer genuinely has two or more distinct parts,"
        + " and then give every part one.\n"
        + "- Use a numbered or bulleted list only when the content is already a list"
        + " (steps, alternatives, enumerated findings). Do not split one thought into bullets.\n"
        + "- Put file names, paths, commands, identifiers, values and other literal strings in"
        + " backticks. Use a fenced code block for anything spanning more than one line.\n"
        + "- Do not invent sections, headings or list items to make a short answer look thorough,"
        + " and do not restate the answer at the end.";
```

Five bullets, ~110 tokens of input budget. Every clause is a *shape* rule; none asks for more
content, and three of the five are explicit anti-inflation constraints — because the failure mode a
9B model has when told "use headings" is to emit a four-heading skeleton over two facts.

**What it must NOT contain, and why:** no "be thorough / be detailed / give examples" (length
inflation against a 1024-token budget), no "cite sources like [1]" (that already exists once in
`RAGQAStyle` — a second citation instruction is exactly the kind of duplicate authority §3 fixes),
no tone/persona words (the identity preamble owns that).

### 1.3 Where it lands, per package convention

New file `modules/app-services/src/main/java/io/justsearch/app/services/conversation/spi/AnswerShapeGrammar.java`:

- Stateless singleton with `public static final String ID = "core.answer-shape-grammar"` and
  `INSTANCE`, mirroring `RAGQAStyle.java:21-23` / `SummarizationStyle.java`.
- `PromptFragment(FRAGMENT_TEXT, 20)`. Priority 20: after the identity preamble (10), before catalog
  descriptors (50-69) and dynamic context (80-99) — the tiering documented at
  `URLEmissionGrammar.java:52-54`.
- Plain `+`-concatenated `String` constants. **No Java text blocks** — the package uses none
  (verified across `URLEmissionGrammar`, `SummarizationStyle`, `RAGQAStyle`), and Spotless/PMD are
  tuned to the existing style.
- Registered by adding `AnswerShapeGrammar.ID` to `RAGAskShape.definition()`'s
  `promptContributorIds` (today `List.of(RAGQAStyle.ID)`, `shapes/RAGAskShape.java:63`).

**Why a separate contributor rather than editing `RAGQAStyle`'s string** — three reasons, all
mechanical: (a) the A/B in §1.5 becomes a one-line arm switch (id present or absent in the shape
definition) instead of a rebuild against two edited constants; (b) the same shape guidance is wanted
later for the summarize and agent tiers, and a second copy of the text is the fork this codebase
keeps paying for; (c) `RAGQAStyleTest.java:37,39-40` asserts substrings of `RAGQAStyle`'s text
(`"documents"`, `"not in the documents"`) — leaving that fragment untouched keeps the existing suite
meaningful instead of re-pointing its assertions at a merged blob.

**New test** `AnswerShapeGrammarTest.java`, mirroring `RAGQAStyleTest`'s shape: id equality, priority
`20`, statelessness (same output for two request bodies), and two content assertions that encode the
*intent* rather than the wording — `text().contains("backticks")` and a regex asserting the fragment
contains at least one negative constraint (`/[Dd]o not/`). Do **not** golden-test the whole string;
§1.5 exists to change it.

### 1.4 The numbered-context prerequisite (interplay with §3a)

**Question asked: does shape guidance without numbered context sections worsen invented ordinals?
Answer: yes, mildly, and the ordering consequence is binding.**

Mechanism: `RAGQAStyle.java:29-31` asks for `[1]`, `[2]`; the context carries no numbers (§3a); the
model invents them (`[0]`, `[20]`, `[26]` measured). Shape guidance does not add a citation
instruction, but it *lengthens and structures* the answer — more sentences, more list items, each a
site where the model re-asserts a bracket. The gap report measured 4 of 4 literals surviving on an
unstructured answer; a headed, listed answer offers strictly more of them.

**Binding consequence:** cause 1 ships **after** slices S1-S3 (§5.1). Two independent nets must be in
place before the prompt change reaches a user: (i) the numbering contract makes an in-range `[n]`
true (§3a), and (ii) the literal-token disposition makes an out-of-range `[n]` visibly demoted rather
than plain prose (§3a.4). Either alone is sufficient to keep the regression at zero; the design ships
both because they are separately correct.

### 1.5 EVALUATION — the live A/B the implementer runs

The 9B model's compliance with shape instructions is **unknown**. This protocol is the acceptance
gate for §1.2; the fragment text is provisional until it passes.

**Preconditions.** Dev stack leased (`leaseDurationSec: 3600` — a 48-run campaign is minutes of
session silence), `ai_activate` (~11 s) so the run is not `AI_OFFLINE`, an indexed corpus of ≥200
documents the questions actually have answers in. Slices S1-S3 and S5-S6 already merged, so the A/B
judges the answer as it will actually render.

**Arms.** `A` = `RAGAskShape.definition()` without `AnswerShapeGrammar.ID`; `B` = with it. Switch by
rebuilding with the one-line registration commented — do not fork the prompt string.

**Corpus of prompts (N = 12, fixed before the run, recorded in the tempdoc log).** Six *multi-part*
questions ("how does X work", "what are the differences between X and Y", "walk me through Z") and
six *single-fact* questions ("what port does X bind", "which file defines Y", "when was Z decided").
The split is the point: arm B must gain structure on the first six and must **not** gain it on the
last six.

**Runs.** 12 prompts × 2 arms × 2 repeats = **48 dispatches**. Two repeats because no sampling
parameters are sent on this path (`ConversationEngine.java:780-786` returns null sampling unless
`enableThinking`), so `llama-server`'s own defaults apply and a single run is not reproducible.
Dispatch: `POST {apiBase}/api/chat/dispatch` with the ask shape body (`sv3-ask.ts:334` is the shape
of the call), `effort: standard`, a fresh `conversationId` per dispatch. Capture the full SSE stream
per run to `scratchpad/ab/<arm>-<prompt>-<repeat>.jsonl`.

**Measured per run (a script over the captured streams — no eyeballing):**

| # | Metric | Extraction |
|---|---|---|
| M1 | headings emitted | count of `^#{1,6} ` lines in the answer text |
| M2 | inline-code runs | count of balanced `` `…` `` spans |
| M3 | fenced blocks | count of ` ``` ` pairs |
| M4 | list lines | count of `^\s*([-*]|\d+\.)\s` lines |
| M5 | answer length | characters, and sentences via the same split `countSentences` uses |
| M6 | verified claims | `claimMatches.matches.length` from the `done` payload |
| M7 | coverage | `cited / total` computed exactly as `groundingCoverage` does |
| M8 | frame | `answerFrame(shape, sourceCount, coverage, chunkPrecise, settled=true)` |
| M9 | surviving raw brackets | `[n]` tokens with `n` outside `[1, sourceCount]` |

**Acceptance (all four must hold; otherwise iterate the wording, ≤3 cycles per the donor research's
critic-loop plateau, then report to the owner rather than iterating further):**

1. **Structure gained where it belongs** — on multi-part questions, arm B's M1 ≥ 1 in ≥ 8 of 12 runs
   (6 prompts × 2 repeats) *and* M2 (backticks) strictly greater than arm A's on ≥ 9 of 12.
2. **No fabricated structure** — on single-fact questions, arm B's M1 ≥ 1 in ≤ 1 of 12 runs, and M5
   (characters) grows by ≤ 25 % over arm A's median. A heading on a one-fact answer is a *failure*,
   not a partial success.
3. **Substance does not regress** — arm B's median M6 and M7 are within ±15 % of arm A's, and no arm-B
   run drops to M6 = 0 where its arm-A twin was > 0. (Substance is *not* judged by reading the prose:
   the verified-claim count is the objective proxy, and it is the number the honesty surfaces show.)
4. **Frame verdicts unchanged distributionally** — the arm-level histogram of M8 over
   {grounded, partially-grounded, sourced, ungrounded} shifts by ≤ 2 runs in any bucket out of 24,
   and no arm-B run frames `ungrounded` where its twin framed otherwise. M9 must not increase.

**Reporting.** A table of the four acceptance lines with the measured numbers, plus the 48-run raw
metrics, appended to this tempdoc's log. `interrogate-results` applies: if arm B wins, confirm the
win came from the fragment and not from a warm cache, a re-index between arms, or a different
corpus — run the arms **interleaved** (A,B,A,B per prompt), not blocked, to remove drift.

#### AMENDED post-cycle-1, owner-ratified (2026-08-13)

Two 48-run campaigns (cycle 0 = the §1.2 text; cycle 1 = the reordered text) showed that criteria 1
and 3 as originally written measure partly the wrong thing. The owner ratified the recalibration
below; it replaces the corresponding clauses above and **decides S6 on one final run**. Criteria 2
and 4 are unchanged. Measured rationale, then the amended text:

- **C1 heading half — the denominator was wrong.** Cycle 1 moved arm B from 2/12 to 7/12 headed
  multi-part runs against an arm-A baseline of **0/12** — an unambiguous effect that still missed a
  bar of 8. Four of the five misses were prompts `mp2` and `mp5`, which produced **zero headings in
  both arms across both repeats**: the questions were labelled multi-part but the corpus answers
  them in one part. Heading those would violate C2, which the design itself calls a *failure*, not
  a partial success. So the metric must not count twins where no arm ever saw a multi-part answer.
- **C1 backtick half — the baseline is saturated.** §1.1 assumed a baseline emitting no backticks;
  it emits them in 18 of 24 runs. Across 24 twins cycle 1 scored **9 wins / 6 ties / 9 losses** —
  symmetric noise — and two wording cycles moved the multi-part half only 4→6 of 12. "Strictly
  greater on 9 of 12" is unreachable by wording on this model, and it encodes the wrong intent: the
  guidance's job here is to *not break* an already-good behaviour.
- **C3 collapse — knife-edge.** All three cycle-1 collapses were `1 → 0` claims, and two of the
  three arm-B answers were *longer* than their twins (mp5-r2: 452→808 chars, 5 sentences, 0
  matches). That is matcher variance at a twin value of one, not a substance regression — while the
  medians, the criterion's real substance signal, were exactly flat (0.0 % / 0.0 %).

**Amended acceptance (C1 and C3 only; all four must still hold):**

1. **Structure gained where it belongs.** *Heading half:* the denominator is the set of
   **qualifying** multi-part twins. A prompt **qualifies** iff at least one of its four runs (either
   arm, either repeat) produced a genuinely multi-part answer, evidenced by M1 ≥ 1; a prompt that
   produced no heading anywhere is disqualified, not failed. Arm B's M1 ≥ 1 in **≥ 2/3 of qualifying
   twins**, with a floor of **6 qualifying twins** (3 prompts × 2 repeats) — below that the corpus
   has not been exercised enough to judge, and the run is repeated with replacement prompts
   pre-verified multi-part by a 2-probe check. *(The qualifier is deliberately lenient and can only
   remove a twin no arm ever headed; it cannot manufacture a pass, because arm A's headings qualify
   a prompt just as arm B's do.)* *Backtick half:* re-derived as **non-regression** — arm B emits
   fewer backtick spans than its twin in **at most 2 of 24** twins. Ties and wins both count; the
   grammar must not degrade a baseline that already backticks identifiers.
2. *(unchanged)*
3. **Substance does not regress.** Arm B's median M6 and M7 stay within ±15 % of arm A's —
   **unchanged, and still the criterion's primary signal**. A **collapse** is now a within-twin drop
   of **≥ 2 claims AND ≥ 50 % relative**; a `1 → 0` twin is no longer a collapse. No collapse may
   occur.
4. *(unchanged)*

**Cycle budget closed.** This amendment buys one final run, judged on a **fresh** 48-dispatch
schedule (re-scoring an earlier campaign under new criteria would be marking one's own homework).
Pass ⇒ the grammar's provisional clause is satisfied and its default flips ON. Fail ⇒ S6 closes with
the grammar default-OFF and this evidence recorded; no further wording cycles.

#### OUTCOME of the deciding run (fresh 48 dispatches, amended criteria) — **FAIL, S6 closes default-OFF**

| # | Amended criterion | Measured | Verdict |
|---|---|---|---|
| 1 | headings in ≥ 2/3 of qualifying twins (floor 6); backtick spans regress in ≤ 2/24 | **8/10** qualifying headed (needed 7) ✔; **11/24** backtick regressions ✘ | FAIL |
| 2 | single-fact: headings ≤ 1/12; median length growth ≤ 25 % | 1/12; **−46.0 %** | PASS |
| 3 | median M6/M7 within ±15 %; no collapse (≥ 2 claims and ≥ 50 %) | claims **−66.7 %**, coverage **−15.6 %**, **8** collapses | FAIL |
| 4 | frame histogram shift ≤ 2/24; no new `ungrounded`; M9 not up | shift **6**; 0 new ungrounded; M9 0→0 | FAIL |

Qualifying prompts: mp1, mp3, mp4, mp5, mp6 (only mp2 disqualified — the amended denominator worked
as intended, and the heading half **passed** on it). Frames — A: 5 grounded / 18 partially-grounded /
1 sourced; B: 4 / 13 / **7** sourced.

**Why it failed, mechanically** (not noise — arm A was stable across both campaigns: median 816→759
chars, 2→3 claims; only arm B moved): the fragment's anti-inflation clauses compress the answer's
**sentence count** — 22→7, 17→5, 16→2, 13→2 in the collapsing twins. The citation matcher runs
**per sentence**, so M6 falls by construction whenever prose shortens, and a sentence-poor answer
matches nothing at all: arm B produced 7 zero-cited runs to arm A's 1, which is what drove C4's
histogram shift from `partially-grounded` into `sourced`. That last part is a **real user-visible
honesty regression**, not a metrology artifact: the same answer frames as "sourced, not per-sentence
verified" more often. Two independent grounds to not ship, so the verdict does not rest on the
metric critique below.

**Recorded for whoever revisits this** — M6 ("verified claims") is substantially a *length* proxy:
it counts matched sentences, so any guidance that shortens prose lowers it whether or not
information was lost. Coverage (M7 = cited/total) degraded far less (0.50→0.46) because both terms
shrink together. A future attempt at answer-shape guidance on this model should either pick a
length-invariant substance metric or pair de-inflation with a matcher that scores clauses rather
than sentences. Effect size also varied run-to-run on an identical jar (cycle 1: medians flat, 3
knife-edge collapses; deciding run: −66.7 % claims, 8 collapses), so the fragment's substance cost
is not merely small-but-stable — it is unstable, which is its own argument against shipping.

**Closure.** `AnswerShapeGrammar` stays registered on `core.rag-ask` and **default-OFF**
(opt-in via the request flag); the shipped ask path is byte-identical to pre-S6. Campaign artifacts:
three 48-run capture sets (cycle 0, cycle 1, deciding run) with per-run M1-M9.

### 1.6 Token budget

`DEFAULT_MAX_TOKENS = 1024` (`ConversationEngine.java:65`) is a per-request-overridable fallback
(`parseMaxTokens`, `:771-778`); sv3 already overrides it per effort rung (`sv3-ask.ts` `quick` 512 /
`thorough` 3072). **No change.** The gap report's C4 ("raise the budget if C1 lands") is declined for
now with a falsifier instead: acceptance criterion 2 measures length growth; if arm B's answers are
truncated mid-structure (an unterminated fence or heading at the 1024-token wall in > 1 of 24 runs),
*that* is the evidence to raise the default, and the number to raise it to comes from the measured
overflow, not from a guess.

---

## 2. CAUSE 2 — MARKDOWNBLOCK GEOMETRY EXPOSURE

### 2.1 The mechanism, and why this one

`Sv3Main.ts:245-251` records the blocker and the live measurement confirms it: the bridge can only
re-point custom properties the component *reads*; every geometry value in `MarkdownBlock.ts:285-414`
is a hard-coded literal, and headings/tables/`hr`/`img` have no rule at all.

Two mechanisms, split by whether a rule exists today:

**(A) Tokens with identical defaults** — for values on rules that already exist. Declared on the
component's own `:host`, consumed with a bare `var(--md-*)`:

```css
:host {
  --md-line-height: 1.6;      /* … the full table in §2.2 */
}
.md-content p { margin: var(--md-block-gap) 0; }
```

A consumer overrides by targeting the **host element** from its own stylesheet — `Sv3Main.ts:252`
already does exactly this (`.sv3-markdown` is a class on `<jf-markdown-block>` itself,
`Sv3Main.ts:902,1116`), and an outer-tree rule matching the host beats a `:host` rule regardless of
specificity. **The known limit, stated so nobody trips on it:** a `:host` declaration also blocks
*inheritance*, so a nested block a consumer cannot select — `ReasoningBlock.ts:181`'s trace renderer,
inside another shadow root — keeps the shipped geometry even when an ancestor sets `--md-*`. That is
the intended outcome here (a compact reasoning trace should not adopt prose rhythm); if it is ever
wanted, the remedy is one forwarding block in `ReasoningBlock.ts:120` (`--md-*: inherit` per
property), named and deferred.

*Alternative considered and rejected:* `var(--md-x, <literal>)` with no `:host` declaration, which
would let ancestor inheritance through. Rejected because it duplicates every default at its use site
(a second authority for the value — the exact thing `strip-token-fallbacks.mjs` exists to kill) and
because it leaves the names undefined unless some consumer happens to define them, which makes
`check-theme-token-closure` red the day sv3 stops overriding one.

**(B) An opt-in host attribute** — for rules that do not exist today. `<jf-markdown-block prose>`
enables a block of rules under `:host([prose])`. A shipped consumer that does not set the attribute
cannot be reached by any of them; containment is then a property of the selector, not of a value
comparison.

**Why the two are not one mechanism:** a token cannot express "this rule exists". Giving `h2` a
default of "whatever the UA does" is not expressible in CSS, and giving it *any* declared default
changes shipped rendering the moment a model emits a heading.

**License containment (charter §1, §12).** No donor literal enters the shared component. The
variant's defaults are expressed in the **shipped** token vocabulary (`--font-size-xs|sm|md|lg|xl`,
`tokens.css:296-300`), and the donor's numbers arrive only through the sv3 override — where they land
on sv3's own token sheet, which already carries `search-v3/THIRD-PARTY-NOTICES.md`. This is not a
formality: `--font-size-sv3-xl|lg|base|sm` = `1.25 / 1.125 / 1 / 0.875 rem`
(`sv3-tokens.css.ts:277-282`) are *already exactly* the donor's heading scale, so the sv3 override is
`--md-h1-size: var(--font-size-sv3-xl)` and no rem literal is copied anywhere.

### 2.2 The token table — name, identical default, sv3 override

Every "default" column entry is the literal currently in `MarkdownBlock.ts` at the cited line. An
implementer who changes one of these values in the same diff has broken containment.

| # | Property | Applies to | **Identical default** (today, line) | sv3 override (donor target) |
|---|---|---|---|---|
| 1 | `--md-line-height` | `:host` | `1.6` (:290) | `1.625` |
| 2 | `--md-block-gap` | `p`, `ul`, `ol` margin | `0.25em` (:317, :345) | `var(--space-2-5)` (10 px ≈ donor 0.65rem) |
| 3 | `--md-block-gap-wide` | `pre`, `blockquote` margin | `0.5em` (:337, :362) | `var(--space-2-5)` |
| 4 | `--md-item-gap` | `li` margin | `0.125em` (:349) | `0` (the variant's `li + li` carries the gap) |
| 5 | `--md-list-indent` | `ul`, `ol` padding-left | `1.25rem` (:346) | unchanged |
| 6 | `--md-code-border` | inline `code` | `none` (absent today) | `1px solid var(--border)` |
| 7 | `--md-code-radius` | inline `code` | `0.25rem` (:328) | `var(--radius-sm)` (6 px) |
| 8 | `--md-code-padding` | inline `code` | `0.125rem 0.375rem` (:327) | `0.1rem 0.35rem` |
| 9 | `--md-code-size` | inline `code` | `var(--font-size-sm)` (:330) | `var(--font-size-sv3-xs)` (12 px) |
| 10 | `--md-code-font` | inline `code` | `monospace` (:329) | `var(--font-mono)` |
| 11 | `--md-pre-radius` | `pre` | `0.375rem` (:335) | `var(--radius)` (10 px) |
| 12 | `--md-pre-padding` | `pre` | `0.625rem 0.75rem` (:334) | unchanged |
| 13 | `--md-quote-border` | `blockquote` border-left | `3px solid var(--border-subtle)` (:360) | `2px solid var(--border)` |
| 14 | `--md-quote-padding` | `blockquote` padding-left | `0.75rem` (:361) | `0.8rem` |
| 15 | `--md-link-decoration` | `a` | `underline` (:353) | `none` |

Notes an implementer will otherwise get wrong:

- **#6 is a shorthand token, not a width token.** `border: var(--md-code-border)` with default `none`
  computes to zero width — byte-identical to declaring no border. A `1px solid transparent` default
  would shift every inline chip by 2 px and is wrong.
- **#9's default is the `var()` itself**, not `0.8125rem`. Nesting a token reference inside a custom
  property default is fine and keeps the existing bridge (`Sv3Main.ts:277`) working unchanged.
- **#15** pairs with one unconditional addition: `.md-content a:hover { text-decoration: underline }`.
  With the default `underline` at rest this is a no-op everywhere shipped (already underlined); with
  sv3's `none` it restores the donor's hover affordance. Zero shipped delta, so it does not need the
  variant.
- **Not tokenized, deliberately:** `background: var(--surface-tertiary)` on `code`/`pre`, all
  `--text-*` colors, `--font-size-xs` on `pre code`. These already read tokens the bridge re-points;
  wrapping a token in a second token is a fork, not an exposure.
- **Not in scope:** body color at 80 % alpha (gap report §2.3 — ours is the higher-contrast surface;
  owner taste, §6 Q4).

### 2.3 The non-tokenizable deltas, and how they land

All of these go behind `:host([prose])`. The shipped consumers never set the attribute; sv3 sets it
at both call sites (`Sv3Main.ts:901,1116`).

| Delta | Rule added under `:host([prose])` | Default value source |
|---|---|---|
| **headings h1-h6** | size, `font-weight: var(--md-heading-weight)`, `line-height: var(--md-heading-line-height)`, `margin: var(--md-heading-margin)` (asymmetric) | h1 `var(--font-size-xl)`, h2 `var(--font-size-lg)`, h3 `var(--font-size-md)`, h4-h6 `var(--font-size-sm)`; weight `600`; lh `1.3`; margin `1.25rem 0 0.5rem`. h6 takes `--text-secondary`. sv3 re-points each to its `--font-size-sv3-*` step. |
| **tables** | `table { width:100%; border-collapse: collapse; font-size: var(--md-table-size) }`, `th,td { padding: var(--md-table-cell-padding); border-bottom: var(--md-table-rule) }`, `th { text-align: start; font-weight: 600 }`, and the wrapper below | size `var(--font-size-xs)`, padding `0.45rem 0.75rem`, rule `1px solid var(--border-subtle)` |
| **table overflow** | the renderer emits a bare `<table>`; the variant adds `.md-content table { display: block; overflow-x: auto; max-inline-size: 100% }` | — (no wrapper element is synthesized; **do not** post-process the DOM for this — `unsafeHTML` re-renders would fight it) |
| **`hr`** | `border: 0; border-top: var(--md-rule); margin: var(--md-block-gap-wide) 0` | `1px solid var(--border-subtle)` |
| **`img`** | `max-inline-size: 100%; height: auto; border-radius: var(--md-pre-radius)` | — |
| **task lists** | `li:has(> input[type=checkbox]) { list-style: none; margin-inline-start: calc(var(--md-list-indent) * -1) }`, checkbox `margin-inline-end: 0.4em` | — |
| **first/last-child zeroing** | `> :first-child { margin-block-start: 0 } > :last-child { margin-block-end: 0 }` for every block child (today only `p`, :319-324) | — |
| **`li + li`** | `margin-block-start: var(--md-item-adjacent-gap)` (default `0.25rem`), replacing symmetric `li` margins when the variant is on | — |
| **nested marker cascade** | `ul ul { list-style: circle } ul ul ul { list-style: square }`, `ol ol { list-style: lower-alpha } ol ol ol { list-style: lower-roman }` | — |
| **blockquote all-sides padding** | `padding: var(--md-quote-padding)` (four-sided) instead of `padding-left` | — |
| **footnotes / GFM alerts** | **out of scope**, named: our `md.parse` config emits no footnote or alert markup today. Adding the parser extension is its own slice; a CSS rule for markup that never appears is dead code. |

**Mechanism test (this is the containment proof, not a nicety):**
`MarkdownBlock.geometry.test.ts` asserts (a) the component's rendered output for a fixture containing
`h2`, `table`, `hr`, `img` and a nested list contains **no** matching rule when the attribute is
absent — implemented by parsing `MarkdownBlock.styles.cssText` and asserting every selector for those
elements appears only inside a `:host([prose])` block; and (b) a **frozen-defaults table**: the 15
default values in §2.2 are asserted verbatim against `styles.cssText`, so a later edit that "tidies"
`0.125em` to `2px` fails loudly. Both are source-level assertions on purpose: happy-dom does not
compute cascaded shadow styles reliably, and the live check belongs in §2.5.

### 2.4 Consumer regression matrix

| Consumer | Call sites | Sets `prose`? | Asserted unchanged by |
|---|---|---|---|
| `UnifiedChatView.ts` | :4491, :5120, :5450, :5455, :5462, :5566, :5571 | no | `UnifiedChatView.test.ts` (existing structural + the one `getComputedStyle` at :2098) + ui-shot `qa-response`, `chat-mode`, `citation-highlight` before/after `ui-diff` |
| `SummarizeView.ts` | :235 | no | existing suite + ui-shot `summarize-done`, `streaming` |
| `NavigateView.ts` | :143 | no | existing suite (renders `format="plain"` — no block rules apply at all) |
| `ReasoningBlock.ts` | :181 (nested) | no | `Sv3Main.imports.test.ts:232-236` contrast + closure assertions |
| `Sv3Main.ts` | :901, :1116 | **yes** | `SearchV3View.markdown.test.ts` (structure), new measured ui-shot step (§5.4) |

**The gate that will fire and must be answered, not worked around:**
`Sv3Main.imports.test.ts`'s `assertClosed()` requires every `var(--x)` in `MarkdownBlock.ts`'s source
to be re-pointed by the sv3 bridge. All 15 new names must therefore appear either in
`Sv3Main.ts`'s `.sv3-markdown` block (where 13 of them belong anyway) or in the test's explicit
allow-list with a reason. `--md-list-indent` and `--md-pre-padding` (unchanged by sv3) go in the
allow-list with the reason *"sv3 keeps the shipped value"*; the whole `--md-*` set goes in the
`jf-reasoning-block` call's allow-list with the reason *"the reasoning trace keeps shipped geometry
(§2.1)"*. Weakening `assertClosed` itself is the predictable evasion and is forbidden.

`check-theme-token-closure` is satisfied by the `:host` declarations (the gate accepts a component
`:host`/`static styles` definition site — `check-theme-token-closure.mjs:5-8`). `check-color-tokens`
is satisfied because no new rule carries a color literal. `gen-component-vocabulary` is not tripped
(no new element). `style-literal-ratchet` moves in the favorable direction.

### 2.5 The measure cap (sv3 only)

`Sv3Main.ts` `.answer` (:230) gains `max-inline-size: var(--measure-prose)` and the sv3 token sheet
defines `--measure-prose: 48rem` (donor `max-w-3xl`, `MessagesTimeline.tsx:553`). This reuses the
shipped concept name (`tokens.css:352`) rather than minting a second measure vocabulary, and it stays
out of the shared component because the measure is the *column's* property — which is why the shipped
chat already sets it on its own container (`unifiedChatStyles.ts:548`).

---

## 3. CAUSE 3 — CITATION-CHAIN CORRECTNESS

Four defects. (b) is the load-bearing one: it is a correctness bug that produces a wrong-target
hyperlink, and it is independent of everything cosmetic.

### 3a. Numbered context sections

**Defect.** The prompt asks for `[1]`, `[2]` (`RAGQAStyle.java:29-31`); the context the model is shown
labels sections `[From: <label>]` with no number (`ContextBudgeter.java:95`). The model invents
ordinals; `normalizeLiteralCitationTokens` (`MarkdownBlock.ts:523-549`) can only rescue an `[n]` that
matches an existing citation *label*, so `[0]`, `[20]`, `[26]` survive as prose.

**The numbering is already available and already aligned — this is the part that makes the fix
cheap.** In `RagContextOps` the budget loop appends to `used` and to the budgeter's `sections` in the
same iteration; the response is built as `chunks[i]` from `usedHits` (:338-377) and `sections[i]` with
`setChunkIndex(i)` (:379-388, and again at :1102-1113 for the doc-level leg). The head passes those
chunks through in order (`RemoteDocumentService`), `RAGContext.java:238-243` stashes them as
`ATTR_CITATIONS` and emits them in order as `rag.citations` (:248-266). So **section _i_ ⇔
`rag.citations[i]` ⇔ the FE's `sources[i]`, by construction.** The number to print is the one the
`Section` record already carries (`ContextBudgeter.java:38` `sectionIndex`).

**Fix.**

1. `ContextBudgeter.appendSection`: `String header = "[" + (sections.size() + 1) + "] " + label + "\n";`
   — 1-based, matching the `[1]`-style the prompt asks for and the FE's `label = refIdx + 1`
   (`citationResolve.ts:36`).
2. Identically in `TokenAwareBudgeter.appendSection` (`:130`) and `DocumentService.java:144`'s default
   impl. **All three, in the same commit** (`retire-with-a-sweep`: leaving one emitter on the old
   format is a silent per-path divergence, and the char-based/token-aware choice is a config toggle).
3. `OnlineModeOps.formatContextAsNumberedPassages` (`:985-987`) parses `startsWith("[From: ")` to
   build `<passage>` tags for the online path. Update the parser to the new header and keep its
   passage numbering keyed to the header's number rather than its own running counter — the two are
   now the same number, and that is the point.
4. `McpToolSurface` passes `result.context()` through as raw text; numbered headers are strictly more
   legible there. No change, but re-run `McpTierEquivalenceGoldenTest` (it holds a golden).

**Test updates (all pre-existing assertions on the literal string — expected, not collateral):**
`ContextBudgeterTest.java:21,29,44`, `RemoteDocumentServiceContextBudgetTest.java:117-124`,
`GrpcSearchServiceRetrieveContextTest.java:733-734`, `OnlineModeOpsTest.java:114,122-124`,
`McpTierEquivalenceTest.java:276`, `McpTierEquivalenceGoldenTest.java:226,245`. **New** assertions,
not just re-pointed strings: (i) the *n*-th appended section's header number equals *n*, (ii) the
budget arithmetic still holds when the header grows by the digits (the header length feeds
`overhead`, so a 3-digit number must not silently overflow the budget — assert an exact
`maxChars` boundary case), (iii) an `OnlineModeOps` round-trip asserting passage *n* carries section
*n*'s label.

**Wire / back-compat analysis.** The header is a **prompt-internal string**. It is not in
`contracts/wire` (`--gate wire` watches `contracts/wire` only —
`governance/registry.v1.json:350-367`), it is not a proto field, it is not persisted (records store
the structured `citations` array, `RAGDoneEnricher.java:94-108`, never the context text), and **no FE
code parses it** (the FE never sees the raw context). The only structural parser is
`OnlineModeOps`, handled above. **Conclusion: no contract change, no migration, no gate.**

**4. The FE half — the literal-token disposition (this is what makes the numbering visible).**
Once `[n]` is true, `MarkdownBlock` must dispose of *every* bracket in the prose. Today it leaves any
`[n]` that has no matching claim, and only demotes brackets in an `ungrounded` frame
(`neutralizePseudoCitations`, :246-283).

- New optional property `sourceCount: number = 0` on `MarkdownBlock` (set by the three RAG consumers
  from the sources array they already hold; `NavigateView` leaves it 0).
- Pipeline order becomes: `decorateCitations` → `normalizeLiteralCitationTokens` → `disposeLiterals`.
- `normalizeLiteralCitationTokens` keeps today's behavior for `n` that matches a real citation label
  (strip when already marked, upgrade otherwise).
- **New:** for `1 ≤ n ≤ sourceCount` with no matcher claim, upgrade to a mark carrying
  `class="cite-ref cite-claimed"` and **no grounding tier** — the model asserted this source; the
  matcher did not verify it. It is deep-linkable (the source exists) but must not read as verified.
  Resolution goes through the one resolver: a new `resolveClaimedCitation(sources, n)` in
  `citationResolve.ts` returning the same `Citation` detail shape with `similarity` absent.
- **New:** for `n` outside `[1, sourceCount]` — a number that cannot refer to any source — wrap in
  `.pseudo-cite` **in every frame**, not only `ungrounded`.
- When `sourceCount === 0` the last two rules are inert, so `NavigateView` and every non-RAG consumer
  are untouched (containment).

**One existing test is intentionally superseded** and the implementer must not "fix" it silently:
`MarkdownBlock.test.ts`'s case asserting that a bare `[n]` is left alone in a default (`grounded`)
frame encodes tempdoc 577 Move 3's rule *"only demote in an ungrounded answer"*. Under the numbering
contract an out-of-range `[n]` in a grounded answer is a fabricated ordinal, which is exactly what
§3.1 named as a defect. Re-point that test to assert the new rule (out-of-range → `.pseudo-cite`
in any frame; in-range → `.cite-claimed`) and record the supersession in the test's comment with a
pointer to this section.

### 3b. THE numbering contract (the `59`-against-5-sources defect)

**One sentence, and everything else follows from it:**

> **On a citation _match_, the index field means the position of the source in the `rag.citations`
> array of the same turn. The chunk's ordinal inside its parent document is a different fact, lives
> only on `rag.citations[i].chunkIndex`, and never travels on a match.**

**Which side changes: the streaming side, and only it.** The post-hoc path already satisfies the
contract, verified end to end: `RemoteDocumentService.matchCitations` (:504-536) builds
`chunkDocIds`/`chunkIndices` 1:1 from the citations array in order; `CitationMatchOps.execute`
(:159-165) resolves chunk text *by array position*; `CitationScorer.scoreAll` returns the position it
was given; `CitationMatchOps` (:175) forwards it unmodified. The proto comment already says so
(`indexing.proto:502` *"index into request arrays"*). The streaming path, in the *same loop that has
the right value*, throws it away: `StreamingCitationMatcher.matchSentenceLexical:246-274` iterates
`for (int i …)` and emits `c.chunkIndex()` (:271) — the record's per-document ordinal
(`DocumentService.java:246`).

**Fix, in three parts:**

1. **Value.** `StreamingCitationMatcher.java:271` emits `i`, the loop index. One line.
2. **Name, so the two facts can never re-conflate.** Rename the field on *match* payloads to
   `sourceIndex` — the name the agent tier already uses for exactly this meaning
   (`AgentSentenceCite.sourceIndex`, consumed at `citationResolve.ts:66`). Three payload sites:
   `StreamingCitationMatcher.java:271` (delta) and `:292` (matches), and the proto field
   `CitationMatchEntry.chunk_index` → `source_index` in
   `modules/ipc-common/src/main/proto/indexing.proto:502` (field *number* unchanged, so the head↔worker
   wire stays compatible and the two ship in one installer anyway).
   `MatchCitationsRequest.chunk_indices` (:495) keeps its name — it genuinely carries per-document
   ordinals for `lookupChunkContent`. `rag.citations[i].chunkIndex` also keeps its name and meaning.
3. **FE readers.** `sv3-ask.ts:305,325` and `UnifiedChatView.ts:5921,5982-5985` read the new name.
   `matchesFromRecord` (`UnifiedChatView.ts:5293-5299`) additionally accepts the legacy key —
   `m.sourceIndex ?? m.chunkIndex` — with a dated comment. This is **not** a code shim: persisted
   records are user data, and their stored values were *already* the correct positional numbers
   (`claimMatches` is persisted from `StreamingCitationMatcher.onDone`'s **authoritative**
   `documents.matchCitations` call, :137-155 — the wrong delta values were never persisted). So old
   conversations render correctly under the new reader, and **no migration is needed.**

**Provenance separation — required, or the value fix is not enough.** Both windows merge delta and
match refs into one `Set` (`sv3-ask.ts:207-223`; `UnifiedChatView.ts:5972-5985`) and the resolver
takes `sourceRefs[0]`. Deltas arrive first, so *the first ref of any doubly-matched sentence is the
lexical one*. Split the accumulator into two ref sets — `verifiedRefs` (from `rag.citation_matches`)
and `lexicalRefs` (from `rag.citation_delta`) — and resolve **only** from `verifiedRefs`. A claim with
no verified ref produces no mark. (This is the same seam §3d needs; do them in one slice.)

**The `sources[refIdx] ?? sources[0]` fallback becomes an honest failure — exactly:**

```ts
// citationResolve.ts, claimsToCitations
const refIdx = cl.verifiedRefs[0];
if (refIdx === undefined) continue;      // unverified claim ⇒ no mark at all
const s = sources[refIdx];
if (!s) continue;                        // out-of-range ⇒ NO Citation is minted
```

- **No wrong-target link can be constructed.** There is no path from an unresolvable index to a
  `Citation`, so no `.cite-ref` can carry the wrong `parentDocId`. `resolveAnswerCitations` (:66-67)
  already does this for the agent tier; the RAG resolver joins it, and the two stop diverging.
- **The dropped claim is not silently absorbed.** `groundingCoverage` counts `cited = grounded +
  weak` over the citations array (`evidenceProjection.ts:372-383`), so a dropped claim makes the
  answer read `Grounded · 4 of 6` instead of `5 of 6`, and `answerFrame` (:128) moves to
  `partially-grounded`. The honesty surface degrades *because* the evidence degraded. That is the
  behavior we want and it is asserted, not assumed (test below).
- **A model-authored `[n]` still renders**, as `.cite-claimed` or `.pseudo-cite` per §3a.4 — so
  "unresolvable mark renders as a pseudo-cite, never a wrong-target link" holds on both channels: the
  claim channel drops, the literal channel demotes.

**Tests.** Java: `matchSentenceLexical` emits `i` for a citations list whose records carry
non-positional `chunkIndex` values (the current code passes only because the fixture's values happen
to coincide — the new fixture must make them differ, e.g. chunk ordinals `7, 3, 19`). FE:
`claimsToCitations` returns `[]` for `verifiedRefs = [59]` with 5 sources (today it returns a
`Citation` pointing at source 1 — assert the old behavior is gone, not merely that the new one
works); a claim with only lexical refs yields no `Citation`; `groundingCoverage`/`answerFrame` over
the dropped-claim case yields `partially-grounded`. Cross-window: the same three assertions run
against `sv3-ask`'s merge and `UnifiedChatView`'s merge — one defect, two accumulators.

### 3c. The missing `.cite-ref.cite-ungrounded` rule

**Defect.** `MarkdownBlock.ts:383-397` declares `.cite-ref` (blue `--text-tint`) and
`.cite-ref.cite-weak` (gray `--text-secondary`) and no `cite-ungrounded` rule, so the *weakest* tier
inherits the *strongest* tier's blue (measured identical: `oklch(0.707 0.165 254.624)`).

**Value.** `.cite-ref.cite-ungrounded { color: var(--accent-warning); }`. The reasoning is the tier
vocabulary's own, not a fresh color choice: the sentence-body channel is already monotonic and
already uses exactly these three — none / `1px dotted var(--text-secondary)` /
`1px dotted var(--accent-warning)` (:307-315). Giving the mark the same three colors makes mark and
underline say the same thing, and `--accent-warning` is distinct from both grounded blue and weak
gray in hue *and* in the tier ordering. The token is already bridged by sv3 (`Sv3Main.ts:271`).

**On the retired `StreamingTextBlock`:** its grounding grammar is recoverable (thresholds 0.5/0.2,
labels grounded/weak/ungrounded, whole-answer heat-map coloring — tempdoc 565 §487-560), but **its
ungrounded color literal is not**: the file predates this repository's first public commit and no
tree in history contains it (pickaxe over all 1157 reachable commits found only tempdoc prose). Do
not cite a historical value; `--accent-warning` is chosen on the tier-vocabulary argument above.

**Honest note on reachability.** After §3d, a *cross-encoder* claim is ≥ the matcher threshold
(0.5) by construction, so `groundingClass` can only return `grounded` or `weak` on the RAG path and
this rule is defensive. It is still required: the agent tier's `resolveAnswerCitations` passes a
similarity from a different producer, the threshold is configurable, and a rule whose absence silently
inverts a trust signal is not something to leave to luck.

**Test.** A fixture claim at `similarity: 0.2` renders a mark whose class list contains
`cite-ungrounded`, and a source-level assertion that the stylesheet declares a `color` for
`.cite-ref.cite-ungrounded` that is textually different from both the `.cite-ref` and
`.cite-ref.cite-weak` declarations (monotonicity as a *source* invariant survives happy-dom's
inability to cascade).

### 3d. The score-scale mismatch

**What the two scores actually are** (read at source, because the recommendation turns on it):

- `evidenceProjection.ts:243-255` states the tier thresholds are anchored to the **cross-encoder**
  matcher cutoff: `TIER_MEDIUM = 0.5` *is* `DEFAULT_CITATION_SIMILARITY_THRESHOLD`, `TIER_HIGH = 0.6`
  sits just above it. A cross-encoder score is a sigmoid over a ms-marco MiniLM logit
  (`CitationScorer.extractScores:232-248`) — a calibrated-ish P(relevant) for *this sentence against
  this passage*.
- `StreamingCitationMatcher.matchSentenceLexical:241-278` emits `overlap = hits / significantWords`:
  the fraction of the *passage's* ≥4-character words that appear anywhere in the sentence, accepted
  at `hits ≥ 2 || (significantWords ≤ 3 && hits ≥ 1) || overlap ≥ 0.5`. Two matching words in a
  20-significant-word passage scores **0.10**.

They are not the same quantity measured on two instruments. One is a relevance probability; the other
is a coverage ratio whose denominator is the passage's vocabulary size. A 20-word passage and a
4-word passage cannot produce comparable numbers even between two lexical matches.

**Options.**

| Option | What it does | Verdict |
|---|---|---|
| **A. Provenance gate** | Tag each claim's refs/score by producer (already distinguishable — `onRagCitationDelta` vs `onRagCitationMatches` are separate handlers in both windows). Only cross-encoder scores mint a `GroundingTier`; lexical-only claims mint no mark, no underline, and are excluded from `groundingCoverage`'s counts structurally. | **RECOMMENDED** |
| B. Separate lexical thresholds | Pick a second threshold pair for overlap. | **Rejected.** It would mint a second tier authority for one displayed verdict — precisely the fork §15.A closed (four sites, two threshold sets) — and there is no evidence base from which to pick the numbers. It also cannot work: the ratio's denominator varies per passage. |
| C. Normalize the scales | Map overlap onto the cross-encoder scale. | **Rejected.** No monotone mapping exists between "fraction of a passage's long words present" and "P(this sentence is supported)". Any mapping would be an invented calibration wearing a normalization's clothes. |

**Recommendation: A, and it is FE-only.** No wire change and no backend change are needed, because the
provenance is already carried by *which event arrived* — the design that looked like it needed a
`scorer` tag on the payload does not. Concretely, in both `sv3-ask.ts` (`mergeClaim`) and
`UnifiedChatView.ts` (`onRagCitationDelta` / `onRagCitationMatches`): keep `verifiedScore` and
`lexicalScore` separately instead of `Math.max`-ing them into one number; the `Claim.score` handed to
`claimsToCitations` is the **verified** score or the claim yields no citation at all (§3b's resolver
already drops a claim with no verified ref, so this is one property, not two).

**What this changes and what it must not.** Lexical-only sentences stop carrying marks and dotted
underlines — the underline wall's supply is cut at the source. `groundingCoverage` is *structurally*
unaffected for the common case (a 0.10 score already counted as neither grounded nor weak) but is
**intentionally** affected in the rare high-overlap case: a lexical-only sentence scoring ≥ 0.6 today
counts as `grounded` and lifts the frame. Under A it does not. That is a deliberate honesty
improvement — word overlap is not grounding evidence — and it is the one place where this fix can
move the frame line, so the A/B in §1.5 (criterion 4) and the unit test below both watch it.

**The underline-density acceptance test (687 R1c's falsifier, made runnable).**

`MarkdownBlock.ts:302-306` states the principle ("mark the exception, not the rule… an indicator that
is on for nearly every sentence carries no information"); the gap report measured **98.5 %** of body
characters underlined. The test:

- **Fixture** (`realistic`): a 6-sentence answer; 5 claims from `rag.citation_matches` at
  similarities `0.81, 0.74, 0.69, 0.63, 0.55`, plus 4 lexical-only deltas at `0.10-0.33` on sentences
  the matcher did not verify. This is the shape the gap report measured, minus the defect.
- **Assertions:** (1) exactly one `.cite-sentence.grounding-weak` span (the 0.55) and zero
  `.grounding-ungrounded`; (2) **underlined characters ≤ 25 % of the answer body's characters**;
  (3) zero marks trace to a lexical-only claim.
- **X = 25 %, and the reasoning:** the mark is an *exception* marker, so its information content is
  its rarity. Below the high bar on more than one sentence in four, the reader's eye has no rare
  target left and the indicator becomes texture — the inversion the component's own comment
  describes. 25 % is one-in-four sentences on a typical 4-8 sentence answer, i.e. the largest density
  at which "look at the marked one" is still an instruction. **Honest limit:** 25 % is a design bound
  on a fixture, not a claim about the live distribution. The live cross-encoder distribution is still
  the §15.A residual; if it turns out to pile just above 0.5 even after A, the finding is a
  **`TIER_HIGH` calibration** question (one knob, one authority), not a render-time cap.
- **A second, adversarial fixture** asserts the bound is not a blanket cap: an answer whose claims are
  *all* at 0.52-0.58 renders *all* six sentences marked. A genuinely weak answer must look weak.

**On B11 (the runtime density guard).** Not recommended. Collapsing per-sentence marks when density
exceeds a threshold would hide a miscalibrated scorer behind a presentation rule — symptom, not root
cause. The density belongs in a **test**, where it fails the build, rather than in the renderer, where
it silently rewrites the honesty surface. Recorded as owner question §6 Q1 because the gap report's
E1 raised it as taste.

---

## 4. CAUSE 4 — SUBSTANCE ROUTING (documented option only)

T3 Code's counts, per-directory file tallies and class names are the output of a tool-using loop over
a repository (grep, count, read), not a formatting property; `core.rag-ask` retrieves chunks and
summarizes them, and no prompt or renderer change produces a number nobody counted — the honest frame
is tempdoc 820's on latency (inference-bound; no FE change moves it). The one real lever already
exists in this window: `core.agent-run` has a `SearchTool` with chunk-precise citations
(`agent/tools/SearchTool.java:354-359`) and is hosted here as of slice F2, so a "which area of code
does X" question is an *agent-shape* question that is currently dispatched to the ask shape. Routing
between the two — automatically by question classification, or explicitly by an escalation affordance
in the composer — is deferred-tier product work (the 818 escalating-input thesis, which the charter
§4b holds until the deferred conversation), and this design deliberately proposes no mechanism for
it: an automatic classifier that silently upgrades an ask into a multi-tool run changes the cost,
latency and trust properties of a keystroke, which is an owner decision and not a rendering fix.

---

## 5. CROSS-CUTTING

### 5.1 Slicing

Six slices, serialized (the charter's implementation queue is one worker at a time). The order is
chosen so that **no slice is ever verified against content that does not exist, and no user-visible
regression exists between any two slices.**

| # | Slice | Tier | Depends on | Why here |
|---|---|---|---|---|
| **S1** | **The numbering contract** — §3b (value + rename + provenance split + honest resolver) | backend (Java + proto) & shared FE | — | Pure correctness; independent of everything cosmetic; the gap report's own "should not wait on a cosmetic queue". |
| **S2** | **Tier provenance & palette** — §3d (FE provenance gate, density tests) + §3c (`cite-ungrounded` rule) | shared FE | S1 (same accumulator seam) | Kills the underline wall's supply at the source. Same files as S1's FE half — doing it second avoids two rewrites of one merge function. |
| **S3** | **The `[n]` contract** — §3a (three header emitters + `OnlineModeOps` parser + tests) and §3a.4 (`sourceCount`, claimed/pseudo disposition) | backend & shared FE | S1 (labels are only true once indices are) | Makes the model's brackets true and disposes of the rest. Prerequisite for S6. |
| **S4** | **Geometry exposure** — §2.2 (15 tokens, identical defaults) + sv3 bridge re-point + `assertClosed` allow-list entries | shared FE + sv3 | — (independent of S1-S3) | Provably zero visual delta anywhere; the containment slice. |
| **S5** | **The prose variant** — §2.3 (`:host([prose])` rules) + sv3 opts in + §2.5 measure cap | shared FE + sv3 | S4 (the variant reads the tokens) | Verified against **fixtures** in the donor dialect — the gap report's decisive experiment, promoted to a test. Shipped surfaces cannot reach any of it. |
| **S6** | **Answer shape** — §1.2-1.3 (`AnswerShapeGrammar`) + §1.5 (the 48-run A/B) | backend | S3 (brackets), S5 (so the A/B judges the real rendering) | Last on purpose: the model starts emitting headings and tables only once there is something to render them with, and the A/B's structure metrics are only meaningful against the finished surface. |

**Not in scope, named so they are not silently dropped** (all from the gap report's change list):
B7 code-block chrome (header/copy/highlighting), B12 file-reference chips + per-extension icon colors,
GFM alerts and footnotes (no parser support today), the donor's 80 %-alpha body text (owner taste),
D2 agent routing (§4).

### 5.2 File-ownership boundary with the citation-mark presentation session

Both workstreams edit `MarkdownBlock.ts`. The boundary is by **line range and by concern**, and it is
enforceable by reading the diff:

- **This workstream owns:** the block-geometry rules and the new `--md-*` tokens
  (`static styles` lines 285-381 and the new `:host([prose])` block); the citation *data* path —
  `decorateCitations`' inputs, `normalizeLiteralCitationTokens`, the new literal disposition,
  `sourceCount`; `citationResolve.ts`'s index mapping and the removal of the `sources[0]` fallback;
  every backend file in S1/S3/S6.
- **The citation-mark presentation session owns:** the mark *appearance* — `.cite-ref`,
  `.cite-ref.cite-*`, `.cite-sentence.grounding-*`, `.pseudo-cite` (lines 382-413), the marker markup,
  its ARIA and its hover surface (`makeMarker`, :552-...).
- **Two deliberate crossings, each with a default so neither session blocks the other:**
  1. **§3c's `.cite-ref.cite-ungrounded` rule** lands in the presentation session's range. Whichever
     session reaches it first lands it with the value in §3c; the other rebases. This design supplies
     the value and the monotonicity test so the rule is not re-litigated.
  2. **§3a.4's `cite-claimed` class** is minted by this workstream's data path but *styled* by the
     presentation session. Default until then: inherit `.cite-ref`'s geometry with
     `color: var(--text-secondary)` and no tier class — i.e. visually a weak mark. The requirement
     handed over is semantic, not visual: *a model-asserted, matcher-unverified reference must not
     render like a verified one.*
- **`citationResolve.ts`:** this workstream owns `claimsToCitations`' resolution logic and adds
  `resolveClaimedCitation`. If the presentation session needs a hover/aria change it touches the
  `Citation` shape's presentation fields (`hover`), not the mapping.

### 5.3 Test inventory per slice

| Slice | Java | FE unit | Cross-window |
|---|---|---|---|
| S1 | `StreamingCitationMatcherTest`: emits `i` (fixture with non-positional chunk ordinals `7,3,19`); payload key is `sourceIndex`; `CitationMatchOps`/proto round-trip under the renamed field | `citationResolve.test.ts`: out-of-range ⇒ `[]` (and explicitly: the old `sources[0]` result is gone); lexical-only claim ⇒ no citation; `evidenceProjection` frame degrades to `partially-grounded` on a dropped claim | the same three assertions against `sv3-ask` merge **and** `UnifiedChatView` merge; `matchesFromRecord` reads a legacy `chunkIndex` record |
| S2 | — | `MarkdownBlock.test.ts` density fixtures (realistic ≤ 25 %, adversarial all-marked); `cite-ungrounded` class + source-level monotonicity | provenance split asserted in both windows |
| S3 | `ContextBudgeterTest` / `TokenAwareBudgeter` numbering + budget-boundary with multi-digit headers; `OnlineModeOpsTest` passage *n* ⇔ section *n*; MCP golden re-baselined | `MarkdownBlock.test.ts`: in-range literal ⇒ `cite-claimed`; out-of-range ⇒ `.pseudo-cite` in a `grounded` frame (superseded test, §3a.4); `sourceCount = 0` ⇒ today's behavior | — |
| S4 | — | `MarkdownBlock.geometry.test.ts` frozen-defaults table (15 values verbatim); `Sv3Main.imports.test.ts` closure passes with the new allow-list entries | existing `UnifiedChatView` / `SummarizeView` suites must pass **unchanged** — an edit to either is a containment failure |
| S5 | — | variant-gated selector assertion (no heading/table/hr/img selector outside `:host([prose])`); a donor-dialect fixture rendered with and without the attribute | `SearchV3View.markdown.test.ts` extended with the donor fixture (h2 + table + backticks + nested list) |
| S6 | `AnswerShapeGrammarTest` (id, priority 20, statelessness, intent assertions); `RAGAskShape` contributor-id list | — | — |

Every slice additionally runs: `./gradlew.bat spotlessApply` then `./gradlew.bat build -x test`, the
affected module tests, and for any `modules/ui-web/src/` edit the **full** `ui-web-gates` recipe from
`governance/consult-register.v1.json` (21 `check-*` scripts + the 6-gate kernel run + typecheck +
`test:unit:run`) — the whole recipe, not a chosen subset (`subset-isnt-the-suite`).

### 5.4 Live-verification checklist per slice

| Slice | Needs a loaded model? | Procedure |
|---|---|---|
| S1 | **Yes** | Dev stack + `ai_activate`; one ask against a corpus returning ≥ 5 sources; assert in the captured SSE that every match's `sourceIndex` is `< sources.length`, that no rendered superscript exceeds the source count, and that clicking mark *k* deep-links to source *k* (the `59`-against-5 reproduction must fail to reproduce). |
| S2 | **Yes** | Same session; measure the rendered answer's underlined-character fraction in the live window exactly as the gap report did (`getComputedStyle` over `.cite-sentence` spans) and record it next to the 98.5 % baseline. A fixture cannot produce the live score distribution — this is the number that tells us whether the §15.A residual is still open. |
| S3 | **Yes** | Same session; capture the *prompt* actually sent (the context block) and assert headers read `[1] label`; then count surviving raw brackets in the rendered answer — target zero, each either a mark or a `.pseudo-cite`. |
| S4 | No — fixtures only | `jseval ui-shot` on `citation-highlight`, `qa-response`, `summarize-done`, `chat-mode` **before** and **after**; `jseval ui-diff` each pair; every diff must be clean. Because the fixed geometry probe does not descend into `.md-content`, add the markdown internals to the step's selector set (or capture a targeted screenshot pair) — a landmark-only diff is not evidence about the prose. |
| S5 | No — fixtures only | Same four steps must still diff clean (shipped surfaces do not set `prose`), plus a new sv3 step rendering the donor-dialect fixture, with **measured** assertions against the donor numbers (heading size/weight/margins, table cell padding + rules, inline-code border/size, block rhythm, measure ≤ 48 rem). New step ⇒ `check-ui-step-coverage`. |
| S6 | **Yes** | The 48-run A/B of §1.5, interleaved arms, lease ≥ 3600 s. |

Dev-stack discipline: `quick_health` first; if another session holds it, the owner decides
(`OWNER_CONFLICT` / `CONTENTION`); stop the stack at the end of each campaign.

### 5.5 The closed list of intended shipped-visual changes

Everything else must be byte-identical. A reviewer checks the diff against exactly these:

1. A citation mark whose target index is out of range is **no longer rendered** (was: a link to
   source 1). — §3b, defect §3.3.
2. A sentence matched only by the lexical streaming scorer **no longer carries a mark or dotted
   underline**, and no longer contributes to the grounded/weak counts. — §3d, defect §3.2.
3. An `ungrounded` mark renders amber instead of the grounded blue. — §3c, defect §3.4.
4. A bracket in the prose is now always either a mark or a muted `.pseudo-cite`, in every frame,
   in a RAG consumer (`sourceCount > 0`). — §3a.4, defect §3.1.

### 5.6 Register / gate touchpoints

- **`--gate wire`: not triggered.** It watches `contracts/wire` (`registry.v1.json:350-367`) and the
  citation shape is not there. **This is itself a finding**, not a clearance: the head↔worker citation
  contract lives in `modules/ipc-common/src/main/proto/indexing.proto`, outside every governance gate,
  and that proto is *internally inconsistent about its own field's meaning* (`:456` positional,
  `:495` per-document, `:502` positional). S1 fixes the code and the one comment; the coverage gap is
  logged to the observations inbox, not fixed here (out of scope).
- **`execution-surfaces` register: not touched** — nothing here references `SearchTrace`.
- **`check-search-degradation-reason-codes` / `check-readiness-reason-codes`: not touched** — no new
  reason code; the honesty degradation of §3b travels through the existing frame vocabulary.
- **`ui-web-gates` recipe: fully in scope for S2-S5.** The three that will actually have an opinion:
  `check-theme-token-closure` (satisfied by the `:host` defaults), `check-color-tokens` (satisfied —
  no new literal), `strip-token-fallbacks --check` (satisfied — no fallback syntax is introduced).
- **`check-ui-step-coverage`: triggered by S5's new step.**
- **`gen-component-vocabulary --check`: not triggered** (no new custom element).
- **THIRD-PARTY-NOTICES:** unchanged. By §2.1's license containment no donor-derived literal enters a
  shared file; sv3's existing `search-v3/THIRD-PARTY-NOTICES.md` continues to cover the overrides.
- **`maintain-doc-hint` / consult register:** S1 and S3 change a contract's *meaning*; the governing
  prose is this tempdoc plus the field comments in `indexing.proto` and `citationResolve.ts` — update
  both comment sites in the same commit rather than leaving the corrected semantics only here.

---

## 6. Open questions — owner-level only

1. **Underline density: test-only bound, or a runtime cap too?** This design recommends the bound live
   in a test (§3d) and rejects the runtime collapse (B11) as symptom-suppression. The gap report's E1
   framed it as taste. If the owner wants the runtime guard regardless, it is ~15 lines and belongs in
   S2 — but it should then be labelled a presentation *policy*, not a fix.
2. **Should a model-asserted, matcher-unverified `[n]` be clickable at all?** §3a.4 makes it a
   deep-linkable `cite-claimed` mark on the argument that the source demonstrably exists and the
   reader benefits from reaching it. The stricter reading of the honesty laws is that only a *verified*
   claim earns a link, and an unverified one should be muted like a pseudo-cite. This is the gap
   report's E2/E3 in its sharpest form.
3. **`AnswerShapeGrammar` for the summarize and agent tiers too?** §1.3 designs it as a reusable
   contributor but registers it only on `core.rag-ask`. Extending it is a one-line registration per
   shape and a re-run of the A/B per shape; not proposed here because each tier's answer shape is its
   own product question.
4. **The donor's 80 %-alpha body text (gap report E4).** Ours is measurably the *higher*-contrast
   surface; adopting the donor value lowers contrast deliberately. Taste, and it interacts with
   `check-contrast-matrix` — not designed here.
```
