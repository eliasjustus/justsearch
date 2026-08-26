# 867 — Citation mark species: model-authored refs vs verified marks

```
status:  THEORIZED (2026-08-26) — investigation + research + theorization appended §1–§6; no design freeze. No design freeze, no
         implementation, no deliverable beyond this tempdoc's own growth. The owner is
         theorizing in this dedicated thread; your job is to build the decision surface,
         not to decide.
created: 2026-08-26
follows: 847 (citation correctness — the "marks follow the cross-encoder, not the model"
         policy), 849 (evidence reader + retrieved-vs-received idiom), 859 §7 (the
         mark-UX cluster, owner-observed live), 865 (delegate evidence authority)
```

## The issue, plainly

An answer can carry **two different kinds of citation artifact at once**, and today they
are rendered as if they were one thing:

1. **Model-authored refs** — the LLM types `[1]`, `[2]`, `[3]` into its own prose. These
   are *claims* by the model about what supports its sentences. They are never verified.
2. **Verified marks** — the cross-encoder checks which sentences are actually supported
   by which retrieved passages and mints real, clickable superscript marks. These are
   the honest artifact; policy (847) is deliberately "marks follow the cross-encoder,
   not the model."

The policy is right. The **presentation** is the problem. Confirmed live on the standard
profile (2026-08-25 session, recorded in 859's validation record):

- Dead literal `[1] [2] [3]` text sits in the answer beside working superscripts that
  read `5`, `4`, `5` — including the literal `[2]` immediately followed by verified mark
  `5` (the **silent override**: the model said one source, the matcher attached another,
  and the reader sees both with no explanation).
- Fail-closed unverified refs render as inert plain text that *looks* broken rather than
  *communicating* "unverified."
- The matcher extracts a marker mid-sentence and appends it at the sentence end, mangling
  prose ("trade-offs4", "retrieval5").
- The sources panel can say "not cited" about a document the answer's own prose names.

All four are consequences of one representational gap: **the UI has no visual vocabulary
for "the model claimed this" as distinct from "the system verified this."**

## Where to look (verify line numbers against current main — they drift)

- `modules/ui-web/src/shell-v0/components/chat/MarkdownBlock.ts` — where marks render
  into prose; the `.cite-ref` span, the dedup by `(parentDocId, startLine)` key, the
  24×24 hit-area `::after` (recent). Search `cite-ref`, `citeKey`.
- `modules/ui-web/src/shell-v0/components/chat/citationResolve.ts` — `resolveAnswerCitations`,
  the producer gate (`isVerifiedProducer`), where the model's textual refs are (or are
  not) parsed/stripped/matched. This is where "the model wrote [2]" meets "the matcher
  says 5".
- `modules/ui-web/src/shell-v0/components/chat/recordEvidence.ts` — `admittedMatches`,
  the shared producer-gating authority (847 S1). Any new artifact class must respect it.
- `modules/ui-web/src/shell-v0/components/chat/evidenceProjection.ts` — `sourceGrounding`,
  `evidenceTier`, `SourceExamination` (now a 4-member union after #548), the panel's
  grouping. The "not cited" wording lives downstream of here.
- `modules/ui-web/src/shell-v0/components/chat/CitationsPanel.ts` — the sources panel,
  tier groups, the "Retrieved · …" badge family (now including "grounding check did not
  complete", "not examined", "never sent to the model" — #548/#551/#553).
- Worker side, for understanding only: `DocumentService.matchCitations`,
  `CitationMatchOps` (the matcher that mints sentence-level cites), and
  `AnswerSegmentation` (commonmark sentence segmentation — relevant to the mid-sentence
  marker extraction defect).
- `governance/execution-surfaces.v1.json` — any new representation of citation data must
  be registered projection-not-fork (the register's rules apply to whatever this
  theorization eventually proposes).
- History: `docs/tempdocs/847-*.md` (the policy's rationale), `859-sv3-live-findings.md`
  §7 (the owner's original observation + the live confirmation), `865-*.md` §7.3/§7.6
  (the honesty-vocabulary discipline: no new tier without a producer that can honestly
  mint it — the cosine-panel lesson is binding precedent).

## Questions the theorization should answer (not decide — surface)

1. **Taxonomy.** How many artifact species actually exist? At least: verified mark;
   model ref that the matcher independently confirmed (agreement); model ref the matcher
   overrode (disagreement); model ref with no verification at all; matcher mark with no
   model ref (the matcher found support the model didn't claim). Which of these deserve
   distinct rendering, and which collapse?
2. **The silent override.** When model says [2] and matcher says 5 — is that a rendering
   problem (show both, explain), a resolution problem (prefer one), or a signal worth
   surfacing to the user ("the model's own citation didn't check out")?
3. **Strip vs. mark vs. annotate.** Three families: strip model refs from prose entirely
   (cleanest text, loses the model's claim trail); render them as a visibly distinct
   species (honest, busier); annotate only disagreements. What does each cost in reading
   experience, and what prior art exists (research pass: how do other RAG products
   render unverified vs verified citations — Perplexity, Gemini, Copilot, scholarly
   tools)?
4. **The prose-mangling defect** (mid-sentence extraction → end-of-sentence append) is
   mechanical, not taste — can it be fixed independently of the species decision, and
   should it be carved out as a standalone slice regardless of the outcome here?
5. **Panel wording.** "Not cited" about a document the answer names is technically true
   (the matcher didn't cite it) and reads false. Does the species vocabulary fix this
   for free, or does the panel need its own "named in the answer, not verified" state —
   and can that state be minted honestly (what producer knows it)?
6. **Interaction with the delegate plane** — 865 established per-source acquisition/
   inclusion honesty; any new species vocabulary must compose with those badges, not
   fork a parallel system.

## Constraints that bind whatever comes out

- The 847 policy (marks follow the verifier) is settled — do not reopen it.
- No new verification *tier* without a producer that can honestly mint it (865 §7.3's
  ban rationale; the #548 review's 'unexamined'-is-a-budget-fact ruling).
- One projection authority per artifact class (execution-surfaces register discipline).
- Presentation-authority changes require the measured audit at closure (axe + contrast,
  auditor ≠ committer).

## First task

Investigation, research, theorization. Read the files above at source, reproduce the
four defects' mechanisms in code terms, run the external research pass on prior art,
and grow this tempdoc with the taxonomy, the option space, tradeoffs, risks, and hidden
assumptions. **No design freeze. No implementation. No PR.** The owner decides the
direction after theorization.

---

## 1. Investigation (2026-08-26, `main`) — the four defects, mechanically

Every `file:line` below was opened. Sub-agent findings were re-read at source for the load-bearing
ones (`MarkdownBlock.ts:502-510`, `:958-985`, `evidenceProjection.ts:130-144`,
`citationResolve.ts:76/:155`).

### 1.0 The one fact underneath all four

**The system solicits the model's `[n]` and then never parses it as data.** Both prompts ask for
inline refs (`RAGQAStyle.java:51-54`, `AgentPromptComposer.java:50-53`), and `ContextBudgeter.java:37`
heads each context section `[n] label` precisely so the ordinals resolve. The verified mark's label
is `sourceIndex + 1` (`citationResolve.ts:76`, `:155`) — **the same numbering space**. So "model said
`[2]`, matcher says `5`" is a *comparable* disagreement about one source list, not an
apples/oranges artifact. Yet the only code that ever touches the model's refs is three regexes in
`MarkdownBlock.ts` (`:161-166`, `:528-534`, `:965`), all of which mutate DOM and forget. The
backend (`AnswerSegmentation`, `CitationMatchOps`) sees `[2]` as plain `Text` characters inside the
sentence key — the cross-encoder scores them as part of the sentence; nothing reads them as a ref.
No wire field (`CitationMatchEntry`, `indexing.proto:517-531`) can carry "the model claimed source i
for sentence j".

### 1.1 Defect (a) — dead `[1] [2] [3]` and the silent override

`normalizeLiteralCitationTokens` (`MarkdownBlock.ts:958-985`) gives a literal `[n]` exactly three
fates, keyed on whether `n` is the label of a gate-passed `Citation`:

| `n` in `byLabel`? | label already got a tier-1 mark? | fate |
|---|---|---|
| yes | yes (`insertedLabels.has`) | **deleted** (`:976`) |
| yes | no | **replaced in place** by a real marker — tier 2, no underline (`:978-980`) |
| **no** | — | **untouched dead text** (`.filter` at `:968`; `byLabel.size===0` early return `:960`) |

Row 3 is the observed `[2]` + superscript `5`: the cross-encoder tied nothing to source index 1, so
label 2 is absent from `this.citations`; the literal survives verbatim and the verified mark for the
sentence lands at the anchored run's `endIndex` immediately after it. **No code anywhere compares
the two numbers.** The information to detect agreement/disagreement is present at render time
(`byLabel` + `insertedLabels` + the anchored group's labels) and is thrown away at the end of the
call.

### 1.2 Defect (b) — fail-closed refs render as inert prose

Producer gates return `[]` (`citationResolve.ts:42/:48/:146`, `recordEvidence.ts:80`; admits only
`CROSS_ENCODER` or absent, `evidenceProjection.ts:410-412`). With `citations=[]`:
`decorateCitations` never runs (`:502`), tier 2 early-returns (`:960`), and the one mechanism that
*would* mute model refs — `neutralizePseudoCitations` → `.pseudo-cite` (`:712-715`) — is gated on
`frame === 'ungrounded'` (`:508`). `answerFrame` yields `'ungrounded'` only for a declared
ungrounded-LLM shape or `sourceCount === 0` (`evidenceProjection.ts:131`); the fail-closed case has
sources and zero cites, which settles to **`'sourced'`** (`:139-142`). **The muting vocabulary
exists and is unreachable in exactly the case that needs it.** (577 Move 3 built it for the
zero-sources frame; the sourced-but-unverified frame was not in its charter.)

### 1.3 Defect (c) — mid-sentence extraction, end-of-sentence append, glued digits

Not a worker behaviour. The worker emits `(sentenceIndex, sentenceText, sourceIndex, similarity)` —
no offsets. Position is decided entirely in the FE:

1. The verified mark lands at the word-run's `endIndex` (`:826`, `:850-854`), i.e. after the
   sentence's last matched token — never where the model put its ref. A mid-sentence model `[n]`
   whose label was inserted there is then **deleted** by row 1 above. Observable: the ref vanishes
   mid-sentence, a digit appears at the end.
2. The tier-2 regex is `/\s?\[(\d+)\]/g` (`:965`) — it **consumes the preceding space**. Stripping
   `" [4]"` from `"trade-offs [4]"` leaves the marker abutting the word with only
   `margin-left:0.1em` (`:644`). That is exactly `trade-offs4` / `retrieval5`. Same `\s?` on the
   upgrade branch, so an upgraded mid-sentence ref is glued too.

**Answer to Q4: yes, this is mechanical and separable** — a one-character regex surface plus a
decision on whether a stripped ref leaves its whitespace. It moves no policy. See §3.3.

Coupling to note: the model's digits are word-tokens on *both* sides of the `matchWordRun` prefix
match (key and DOM). Stripping refs before segmentation on the backend would desync the anchoring
unless the DOM side stripped identically — the exact drift class 847 §2.1 was built to eliminate.
Any "strip" option must strip on the DOM side only, or on both by one shared rule.

### 1.4 Defect (d) — "not cited" about a document the answer names

`sourceGrounding` (`evidenceProjection.ts:686-724`) sees only array position, the cross-encoder
`CitationMatch[]`, optional coverage and optional `groundingIncomplete`; its four-member state
machine (`:715-721`) yields `cited | grounding-incomplete | unexamined | examined-uncited`, and
`sourceGroundingLabel:737` words the last as "Retrieved · not cited". **No producer in the chain
sees answer prose**; there is no title/filename match and no model-ref list. "Named in the answer"
has no producer today — under 865 §7.3's ban, a fifth state cannot be minted until one exists
(§3.4).

### 1.5 Governance surprise

`MarkdownBlock.ts` — the surface that decides what a citation artifact *claims* — is **not
registered** in `governance/execution-surfaces.v1.json` (named only inside another entry's note,
`:213`); `recordEvidence.ts` likewise. Any species work is the natural trigger to register it; today
the register would not catch a fork authored there.

### 1.6 Tier 2 is already a species — and wears a colour it did not earn

`MarkdownBlock.ts:947-955` documents the tier-2 mark as "source-level attribution placed by the
model". But it renders with the same `.cite-ref` class and a tier colour from `cite.similarity`
(`:994`) — a cross-encoder score for a *different sentence* than the one the literal sits in. The
`Citation` type (`:56-80`) has no origin field, so after rendering neither the panel, the legend
(`Sv3Main.ts:761-767`) nor the marks can tell a tier-1 mark from a tier-2 one. 847 §7 Q4 flagged
exactly this ("should a tier-2 mark be visually distinguishable?") and deferred it; 839 §7 deferred
`.cite-claimed` as "blocked on the literal-citation disposition". **This tempdoc is that
disposition.**

## 2. Taxonomy (Q1)

Cross the two authorities. Rows: what the model wrote at a sentence. Columns: what the matcher
minted for it. Current rendering in the cells.

| model ref ↓ / matcher → | verified mark for source **k** | none |
|---|---|---|
| `[k]` (agree) | mark `k` at run end; literal deleted (row 1) — **agreement is invisible** | tier-2 upgrade: mark `k`, no underline, cross-encoder colour borrowed from another sentence (§1.6) — or dead text if label k is absent from every citation |
| `[j]`, j≠k (disagree) | mark `k` + dead literal `[j]` side by side — **the silent override** | dead literal `[j]` (or tier-2 upgrade to j if j is a verified label elsewhere — a *wrong-sentence* mark) |
| none | mark `k` — the matcher found support the model didn't claim | nothing (honest) |

Seven cells, five distinct *situations*:

- **S1 verified-agreed** (model k, matcher k)
- **S2 verified-unclaimed** (model none, matcher k)
- **S3 verified-overridden** (model j, matcher k≠j)
- **S4 claimed-unverified** (model j, matcher none) — including the fail-closed whole-answer case
- **S5 nothing**

Which deserve distinct rendering? The honest minimum is **three visual species**, not five:
S1 and S2 collapse (the verifier's assertion is identical; whether the model also claimed it is
provenance trivia the reader does not need on the mark — it *could* live in the hover), S5 needs
nothing, leaving: **verified** (S1/S2), **claimed-only** (S4), and the question of whether S3 is a
third species or an annotation on a verified mark (Q2, §3.2).

Note the tier-2 path is a *fourth* thing today: "model placed, verifier confirmed the source at
sentence granularity elsewhere". It is S1 with the sentence unknown. It should be folded into the
species decision rather than kept as an unnamed accident of control flow.

## 3. The option space

### 3.1 Q3 — strip / mark / annotate, as a lattice

| family | what the reader sees | keeps model's claim trail? | new producer needed? | reading cost |
|---|---|---|---|---|
| **A. Strip** all model refs; marks are verifier-only | cleanest prose; the panel is the only place a "named" source shows | no (lost) | no — a DOM regex extension (`:965` without the `byLabel` filter) | lowest |
| **B. Mute** (render claimed-only refs as `.pseudo-cite`-style muted literal) | `[2]` greyed, non-interactive, tooltip "the model cited this; not verified" | yes | no — un-gate `neutralizePseudoCitations` from the frame (`:508`), or route row 3 of §1.1 into it | low |
| **C. Distinct species** (claimed-only refs become a second mark idiom, clickable to the source, visibly not-verified) | two mark shapes in prose | yes | **FE parse of `[n]`→source mapping** (exists implicitly via numbering space) — no backend fact | medium (busier, needs legend + contrast work) |
| **D. Annotate disagreement only** (S3 gets a glyph/tooltip; S4 falls to A or B) | prose as today, plus one honest signal where model and verifier differ | partial | no — compare `byLabel`/anchored labels at render time | low-medium |
| **E. Correct** (RARR-style: rewrite/replace the model's ref with the verifier's) | `[2]`→`5` silently | no | no | lowest — but it is the *silent override made silent-er* |

**E is rejected on 847 grounds** — it makes the model's assertion wear the verifier's number; the
one surface whose job is telling authorities apart would merge them. **A is tempting and is the
Perplexity/Copilot/ChatGPT default (research §5): presence/absence only.** Its cost is the claim
trail: the reader loses "the model *thought* source 2 supported this and the verifier disagreed",
which is the single most diagnostic signal this system has about model quality.

The families compose: **B+D** (mute claimed-only, annotate overridden) covers S3 and S4 with zero
new producers and no new tier; **C+D** is the maximal honest surface. The tempdoc's own framing
("the UI has no visual vocabulary for *claimed* vs *verified*") points at B-or-C for S4, and D for S3.

### 3.2 Q2 — the silent override

Three readings, and they are not exclusive:

- **Rendering problem** → D. Show both, explain. Cost: a glyph and a legend entry; the glyph must
  pass contrast at superscript size (839's audit regime).
- **Resolution problem** → the current code *already* resolves (verifier wins; 847 settled that).
  The defect is that the loser is left lying in the prose. B fixes the corpse; D labels it.
- **Signal** → S3 is a *per-answer model-quality fact*: "N of the model's own citations did not
  check out". That aggregate has no home today (the honesty line counts sentences grounded, not
  refs contradicted). It could be a line in the sources disclosure — and it is mintable with no
  backend change, from the same render-time comparison. Whether it's worth a line is the owner's
  call; it is the only surveyed idea with no prior art anywhere (research §5, "what nobody does").

Hidden assumption to name: **that the model's `[j]` refers to the source list at all.** Local
models routinely emit refs the prompt did not ask for or number from 1 regardless of context
(847 rejected-alt A(ii)). A `[j]` with `j > sourceCount` is not a disagreement, it is noise — any
species logic needs a "resolves to a source" pre-check, else B/C would mint a claimed-only ref
pointing nowhere.

### 3.3 Q4 — the prose-mangling carve-out

Yes, standalone, regardless of outcome. Scope: `MarkdownBlock.ts:965` (`\s?`), the strip branch
(`:976` — leave the space, or collapse a double space), and whether a deleted/upgraded ref's
whitespace is preserved. Tests extend `MarkdownBlock.test.ts`. No presentation-authority
implications beyond a pixel of spacing; no policy moved. Risk: the `endIndex` extension over
trailing non-whitespace (`:323-325`) may currently *rely* on the digit being glued — check the
T-suite for a shape like `word [4].` before changing.

### 3.4 Q5 — panel wording

"Not cited" is doubly wrong-reading: (i) the matcher-timeout case, already carved out by 865 §7.3
(`grounding-incomplete`); (ii) the case here — the model *named* the source and the verifier found
no sentence for it. Does species vocabulary fix (ii) for free? **Partly.** If S4 refs render as
claimed-only marks that click through to source j, the panel entry for j is reachable *from* the
prose, and "not cited" reads as "not *verified*" by contrast. But the badge text itself still
asserts a verdict on a source the model cited.

Can a "named, unverified" state be minted honestly? The producer would be the **FE render-time
ref parse** — a model-output fact, not a verifier fact. That is new: today every grounding-adjacent
state derives from a verifier or a budget. 865 §7.3's rule ("no tier without a producer that can
honestly mint it") is satisfied by a producer that says *only* "the model wrote `[j]` and `j`
resolves to this source" — it claims nothing about support. The honesty constraint is on the
*wording*: "Cited by the model · not verified" is mintable; "Relevant" is not. Whether this is a
fifth `SourceExamination` member or a separate boolean beside the examination axis is a
projection-vs-fork question for the design pass (865 §3.8c's "don't overload the axis" precedent
argues for a separate flag: examination is what the verifier did; claimed is what the model did).

### 3.5 Q6 — composition with the delegate plane

The delegate wire (`AgentSentenceCite`, `{sentenceText, sourceIndex, similarity}`) is thinner:
no `sentenceIndex` (derived by identical-text grouping, `citationResolve.ts:105-117`). Species
logic that lives **entirely in the FE renderer** (B, C, D above) composes automatically — both
planes hand `MarkdownBlock` the same `Citation[]` and the same raw markdown. Species logic that
needs a backend fact would fork immediately (two producers, two carriers). **This is the strongest
argument for keeping the species decision FE-only:** it is the only placement with one authority
across both planes. 865's acquisition/inclusion badges are on the *source*; species are on the
*mark*; they do not overlap unless Q5 mints a per-source "claimed" flag — which would then sit
beside 865's badges as one more per-source fact from a declared producer, not a parallel system.

## 4. Risks, tradeoffs, hidden assumptions

1. **Busier prose.** Every family except A adds ink. 839's measured audit found even a 9 % card
   wash tripped axe; a second superscript idiom at 13×16 px needs the 24×24 hit-area treatment
   (`::after`) and contrast work before it ships. Presentation-authority → `ux-audit-closure`.
2. **The ref→source resolution is a numbering-space assumption**, not a contract. It holds because
   `ContextBudgeter` and `citationResolve` independently agree on 1-based source order. Nothing
   pins it; a re-ordered or de-duplicated source list would silently rebind every model ref. A
   species design that *depends* on it should pin it with a test across the two sites.
3. **Streaming.** All of this runs post-settle (`!isStreaming`, `:502/:508`). Mid-stream the
   model's literal refs show as plain text by design; a species idiom must not flash from plain →
   muted → verified across the settle boundary in a way that reads as marks "moving".
4. **The tier-2 colour borrow (§1.6) is a present-tense honesty defect** independent of any new
   species — a tier-2 mark should carry either no tier colour or a "claimed" idiom. Cheapest fix is
   inside whatever B/C ships.
5. **The claimed-unverified count as a quality signal (§3.2)** would be the first user-facing
   metric derived from model output rather than a verifier. If surfaced, word it as what the model
   did, never as what the sources say.
6. **Anchoring coupling (§1.3)** — any strip must be DOM-side or symmetric.
7. **Register hygiene (§1.5)** — register `MarkdownBlock.ts` (and `recordEvidence.ts`) as
   projections before adding a species representation, or the `execution-surface` gate cannot
   protect the new field.
8. **Assumed but unmeasured:** how often each of S1–S4 occurs on the standard profile. One
   `jseval` pass over a citation dataset with a render-time counter would turn the taxonomy from
   plausible into weighted — and would tell the owner whether S3 is a curiosity or a headline.

## 5. Research pass — prior art (2026-08-26)

Sources: Google Gemini help ("double-check response"), Vertex AI grounding/`groundingSupports`
docs, Check Grounding API, Anthropic Citations API docs, Perplexity docs, ChatGPT search help,
Scite help/paper, NotebookLM, Kagi/You.com docs; papers AIS/AutoAIS (Rashkin 2021, Honovich
2022, Gao 2023), ALCE (EMNLP 2023), RARR (ACL 2023), "Generation-Time vs Post-hoc Citation"
(arXiv 2509.21557), "Cited but Not Verified" (arXiv 2605.06635).

- **Dominant pattern is presence/absence.** Perplexity, Copilot/Bing, ChatGPT, NotebookLM: the model
  cites during generation; a citation is either shown at full confidence or not shown. No middle
  state, no "unverified" idiom. Vertex's Check Grounding API applies a *threshold* — below it the
  citation is silently dropped (our fail-closed, family A).
- **Anthropic Citations API**: model chooses what to cite, API guarantees the pointer resolves to
  real source text — pointer validity, not entailment. Still presence/absence in the UI.
- **Gemini "double-check"** is the one live product with a disagreement idiom: a *separate*
  post-hoc search pass colours statements green (similar content found), orange (different or
  nothing found), none (can't evaluate). Explicitly decoupled from the answer's own citations — a
  second opinion, not an annotation of the first. Closest to family D, but on sentences, not refs.
- **Vertex confidence scores per support were removed for Gemini 2.5+** — the industry moved
  *away* from per-citation confidence, not toward it.
- **Scite** (scholarly, human citations): Supporting / Contrasting / Mentioning chips with context —
  the one rich three-way vocabulary, never adopted for LLM inline refs.
- **RARR**: on failed verification, *edit the text* to fit the evidence (family E) — a correction
  strategy, rejected here on 847 grounds.
- **ALCE / "Cited but Not Verified"**: model-authored citations fail full support ~50 % of the time
  (ALCE ELI5); link validity >94 % but factual-support accuracy 39–77 % across 14 models. The
  verifier-over-model policy is well-founded; the field has an *evaluation* vocabulary and no
  *rendering* vocabulary for the failure.
- **What nobody does:** render "the model claimed X, cited Y, Y does not support X" as a distinct,
  labelled state in the same interface as the verified marks. Family D / the S3 signal would be
  first-of-kind — which is both the opportunity and the warning (no borrowed conventions; the legend
  must teach it).

## 5b. Research pass 2 — what the marks LOOK LIKE in frontier products (2026-08-26)

Second, visual-only pass (owner-requested). [P] = vendor primary, [S] = secondary/teardown.
Under-evidenced rows: Claude.ai chrome, Kagi.

| product | mark in prose | hover / click | passage-level? | sources surface | verification state |
|---|---|---|---|---|---|
| ChatGPT search | favicon + publisher **pill**, end-of-claim, `+N` collapse [S] | hover preview (desktop only) → click opens source; "Sources" button → right sidebar [P] | no | right sidebar | none |
| ChatGPT Deep Research | inline links; raw token is `【57†L18-L22】` (source + line range, hidden by renderer) [S] | — | line-range pointer exists in the token, not exposed | run sidebar persists as source inventory | none |
| Claude.ai | inline citations (chrome undocumented) | — | **yes by data model**: `cited_text` + locator (page / char range), never sub-sentence [P] | — | none |
| Gemini app | inline links + "Sources" button → side panel; carries URL and Workspace/file sources in one chrome [P] | click | no | side panel | **double-check**: per-sentence green / orange / none, applied post-hoc via a "G" button; green wording explicitly disclaims provenance ("not necessarily what Gemini used") [P]. Colour-only. Semantics explained only in help centre. |
| Google AI Mode / Overviews | numbered marks + underlined phrases with external-link glyph; hover preview cards (Dec 2025) [P/S] | click → overlay with excerpts, clustered sources, and an "Ask about" control [S] | excerpt in overlay | overlay / side rail | none; subscription-source label |
| Perplexity | domain **chip + count** (`northjersey +3`) end-of-claim; legacy superscript `[n]` [S] | click → popover with title, snippet, `1/2` prev/next [S] | no | favicon stack "10 sources" row + persistent panel + Links tab [S] | **shield** badge: Government / Academic / Trusted — rates the *domain*, not the claim [P]; "check sources" on text selection (discoverable only after highlighting) |
| Copilot (consumer) | numbered footnote markers + favicon citation bar below [S] | 2025: hit area **narrowed** to the marker alone (was whole line) [S] | no | bar below | none |
| M365 Copilot | inline numbered refs; hover card: open-in-side-pane / Ask Copilot / more actions [P] | Sources button → side pane; Teams scrolls left pane to the cited message [P/S] | **"deep citations"** (GA ~Feb 2026): link to the passage, not the file [P] | side pane | none |
| NotebookLM | small numbers in **grey ovals** [S] | hover = quoted text; click → left Source guide jumps to and highlights the exact sentence [S] | **yes** | left panel | none; per-answer renumbering (known confusion) |
| Grok | markdown `[1][2]` links; `no_inline_citations` API flag [P] | — | no | list | none |
| Le Chat | globe / news icon on the response; inline links; Sources button [P] | — | no | bottom panel | none |
| Open WebUI / Dify / Onyx | `[source_id]` markers; plain link list under message [P] | no passage jump (top open request: text-fragments for HTML, page+bbox for PDF, keyboard claim→source, marker-hide toggle) [P] | no | list | none |
| Vercel AI Elements `InlineCitation` (component idiom) | hostname badge + `+N`; hover card with title/URL/quote; carousel `1/5`; keyboard-navigable [P] | | quote block | | none |

**Findings that bear on 867:**

1. **Three dominant idioms.** (i) identity chip with `+N` collapse (Perplexity, ChatGPT, the
   component libraries); (ii) numbered marker + separate sources surface — the footnote lineage we
   are in (NotebookLM, Copilot, Grok, Le Chat, OSS); (iii) hyperlink-on-phrase with hover preview
   (Google). The side panel has beaten the bottom bibliography everywhere.
2. **Trend 2024→2026:** marks carry *identity* without interaction (favicon/domain → for us:
   file-type glyph + filename); claim-level attachment; multi-source collapse; and **file → passage
   deep links** (NotebookLM first, M365 "deep citations" 2026, Open WebUI's top ask). Our
   `.cite-sentence` underline + anchored pane is already on the right side of this trend; the
   `+N`/grouped-mark idiom (847 §2.1e emits grouped labels in one split) is the cheap thing we lack.
3. **Nobody but Gemini renders claimed-vs-verified**, and Gemini does it as a *separate, opt-in,
   post-hoc pass over sentences*, colour-only, with the legend in a help article. Perplexity's
   shield rates domains, not claims. **No product marks an uncited sentence, and no product renders
   a citation that failed verification.** So 867's family D (annotate the override) and family
   B/C (visible claimed-only species) have no shipped precedent to borrow a glyph from; the legend
   (`Sv3Main.ts:761-767`) must teach it, and the signal must not be colour-alone (Gemini's
   documented a11y defect).
4. **Legends are absent in-product across the field** — a consistent gap, and one this repo
   already fills (the sv3 legend). Any new species goes into that legend, not a help page.
5. **Hit targets:** the only documented change is Copilot *shrinking* the target to the marker; no
   vendor publishes sizes. Our 24×24 `::after` is ahead of the field; a second species must keep it.
6. **Pitfalls to design around:** NotebookLM's per-answer renumbering (unstable ids — our labels are
   per-turn source ordinals, same hazard if a species links by number rather than by `parentDocId`);
   Open WebUI's document-level grouping collapsing distinct chunks (cite the chunk on the mark,
   dedup by file in the panel — which is what `(parentDocId, startLine)` already does).
7. **Translation to local files:** the transferable idioms are exactly the document-grounded ones
   — NotebookLM click-to-jump-and-highlight (we have it), Claude's quote+locator payload as the
   stored shape (our `CitationMatchEntry` carries sentenceText but no source-side locator beyond
   `parentDocId`/window — worth noting for 849's reader), M365's hover card with open / ask-about /
   more. Chip identity = file-type glyph + filename; "domain" = folder; recency = mtime.

Implication for the option lattice (§3.1): the field's answer to S4 is uniformly **A (strip /
never show)** — presence/absence. Choosing B or C is a deliberate departure with no borrowed
convention; choosing D is first-of-kind. That is not an argument against either; it is the cost
line the legend and audit must pay.

## 6. What the owner is choosing between (surface, not decision)

1. **S4 treatment:** A (strip), B (mute, reuse `.pseudo-cite`), or C (claimed species, clickable).
2. **S3 treatment:** leave to the S4 rule (the loser gets stripped/muted), or D (annotate), and
   whether the aggregate "N model citations did not check out" earns a line.
3. **Panel:** whether Q5's "cited by the model · not verified" per-source flag is minted (FE
   render-time producer, separate from the examination axis) or whether the mark-level species is
   judged sufficient.
4. **Carve-outs regardless:** the `\s?` glue fix (§3.3); un-gate or re-route the muting for the
   `sourced`-with-zero-cites frame (§1.2 — arguably a 577 Move 3 gap, a bug not a design); the
   tier-2 colour borrow (§1.6); register `MarkdownBlock.ts` (§1.5).
5. **Measurement first?** §4.8 — one eval pass to weight S1–S4 before choosing ink.
