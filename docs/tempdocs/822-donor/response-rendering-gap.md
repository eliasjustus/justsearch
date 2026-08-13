# Why our answer looks nothing like T3 Code's — decomposition + change list

```
status: COMPLETE
created: 2026-08-13
author: independent investigator (implemented none of F1-F9)
question: owner, after running the same prompt in T3 Code and in the Search v3 window —
  "why does it appear so different still? … name all changes."
method: source reading (both trees) + live measurement in the running stack's own FE at
  http://localhost:5173 (read-only: fixture markdown pinned onto a live `jf-sv3-main`
  region via DOM property override, no ask sent, no stack lifecycle touched, injected
  nodes removed and the tab closed afterwards) + primary-source Java prompt trace
donor: T3 Code @ scratchpad/t3code (MIT, T3 Tools Inc.) — cites are `apps/web/src/…`
```

---

## 0. The headline

The perceived gap is **not one gap**. It decomposes into four independent causes, and they
are not equally weighted:

| # | Cause | Share of the perceived gap | Fixable where |
|---|---|---|---|
| 1 | **Content shape** — our model emits no headings, no backticks, no paths | **largest** | prompt (one string) |
| 2 | **Renderer geometry** — MarkdownBlock declares no headings, no tables, a borderless non-stepped code chip, ~3x tighter rhythm, no measure cap | large, and *latent* (invisible until cause 1 is fixed) | shared component |
| 3 | **Honesty overlay** — 98.5 % of the answer body underlined, 4 surviving raw `[n]`, a non-monotonic mark palette, a source label reading `59` against 5 sources | large and *unique to us* | matcher + component + prompt |
| 4 | **Content substance** — paths, counts, class names | irreducible on this path | engine / accepted |

The load-bearing asymmetry: **causes 1 and 3 are cheap and produce most of the visible
difference; cause 2 is the expensive one and produces none of it until cause 1 lands.**
Styling headings that the model never emits changes nothing on screen.

---

## 1. CAUSE 1 — ENGINE/CONTENT (confirmed; the single largest share)

### 1.1 The decisive experiment

A fixture answer written in exactly T3 Code's markdown dialect (h2, backticked identifiers
and paths, a numbered list with bold run-ins, a GFM table, an inline doc link, a bold
`**Short answer:**` close) was pinned onto the live `jf-sv3-main` region and rendered by
our own shipped `jf-markdown-block` under the v3 bridge.

**Result: most of it renders well.** The h2 is bold, white, correctly the largest thing on
screen. The `1./2./3.` list with **bold run-ins** renders exactly like the donor's. Italics,
`<strong>`, the link, the tight paragraph rhythm — all fine. Nothing about the shipped
renderer prevents a T3-Code-shaped answer from looking like a T3-Code answer.

**What renders badly** (measured, §2): the inline code chips are nearly invisible (no
border, no size step-down, generic `monospace`), the table is raw browser-default, the
heading margins are UA `0.83em` (17.4 px) against a 3.5 px paragraph rhythm, and the answer
runs the full 1636 px panel width (~131 characters per line).

**Conclusion: the dominant term is that our model emits none of that markdown at all.**

### 1.2 Why it emits none of it — verified at the source

The entire instruction for the ask path is one fragment
(`modules/app-services/src/main/java/io/justsearch/app/services/conversation/spi/RAGQAStyle.java:26-32`,
priority 10, the only `PromptContributor` in `RAGAskShape.definition()`,
`shapes/RAGAskShape.java:63`):

> "You are a helpful assistant that answers questions based on provided documents. Only
> answer based on the document content. If the answer is not in the documents, say so. Cite
> sources inline with a bracketed number like [1], [2] at the end of the sentence they
> support; do NOT append a separate Citations, Sources, or References list at the end — the
> interface displays the sources."

There is **no mention of markdown, headings, bullets, code formatting, backticks, or
length** anywhere on this path. The observed opening "Based on the provided documents…" is
not instructed text — it is the model paraphrasing "answers questions based on provided
documents" back at us, which is itself a signal that this one sentence is doing all the
shaping work.

**Precedent in our own codebase that a prompt may specify markdown shape:**
`spi/URLEmissionGrammar.java:71-99` (`NavigateChatShape`, priority 50) instructs `##`
headings, fenced code blocks, inline backticks and `**bold run-in:**` labels. It lives in
the same package as `RAGQAStyle`. So cause-1 remediation has an in-tree pattern, not a new
invention.

**Decoding budget:** `maxTokens` defaults to `DEFAULT_MAX_TOKENS = 1024`
(`conversation/ConversationEngine.java:65`); no temperature/top_p is sent on this path
(`ConversationEngine.java:780-786` returns null sampling unless `enableThinking` is set, and
`OnlineModeOps.java:378-381` only forwards non-null sampling). Model:
`Qwen_Qwen3.5-9B-Q4_K_M.gguf` (`InferenceConfig.java:232`).

---

## 2. CAUSE 2 — RENDERER GEOMETRY (confirmed; the F4 "recorded MarkdownBlock geometry gap",
now quantified end to end)

Ours: `modules/ui-web/src/shell-v0/components/chat/MarkdownBlock.ts:285-414` (`static
styles`) + the v3 bridge `views/search-v3/Sv3Main.ts:252-292`.
Donor: `apps/web/src/index.css:1803-2147` + `components/chat/ChatMarkdown.tsx`.
"Ours (measured)" = `getComputedStyle` in the live window, dark theme, 14 px base.

### 2.1 Every styling delta, both values

| Element | Donor | Ours (measured) | Delta |
|---|---|---|---|
| body size / line-height | 14 px / **1.625** (`ChatMarkdown.tsx:1698-1703`, `text-sm leading-relaxed`) | 14 px / 1.6 (22.4 px) (`MarkdownBlock.ts:286-293`) | negligible |
| body colour | `--foreground` at **80 % alpha** (`text-foreground/80`) | `--foreground` at **100 %** | **ours is HIGHER contrast** — see §2.3 |
| **measure** | column `max-w-3xl` = **768 px** (`MessagesTimeline.tsx:553`) | **none** — measured 1636 px, ~131 chars/line | the biggest silent legibility delta |
| **block rhythm** | `p, ul, ol, blockquote, pre, table-container { margin: 0.65rem 0 }` = **10.4 px** (`index.css:1818-1825`), first/last child `0` | `p { margin: .25em }` = **3.5 px**; `ul/ol` 3.5 px; `pre` 7 px (`MarkdownBlock.ts:316-347`) | **~3x tighter**, no `:last-child` zero for lists |
| **headings** | h1 `1.25rem`, h2 `1.125rem`, h3 `1rem`, h4-6 `0.875rem`; weight **600**; line-height **1.3**; margin **`1.25rem 0 0.5rem`** (asymmetric); h6 muted (`index.css:1827-1859`) | **nothing declared** → UA defaults: h2 = **21 px**, weight **700**, line-height 1.6, margin **17.43 px top AND bottom** | wrong size, wrong weight, symmetric UA margins 5x the paragraph rhythm |
| **inline code** | `border: 1px solid var(--border)`; radius **0.375rem** (6 px); bg `--muted`; padding `0.1rem 0.35rem`; colour `--foreground`; **font-size 0.75rem (12 px)**; family `var(--font-mono)` (`index.css:1967-1974`, `:1634-1637`) | **no border**; radius **4 px**; bg `--muted` (bridge ✓); padding `2px 6px`; colour `--foreground` ✓; **font-size 14 px — no step-down**; family **`monospace`** (generic keyword) | 4 deltas: missing border, 4 vs 6 px radius, **14 vs 12 px**, generic vs `--font-mono`. The 14 px mono renders visibly *larger* than the surrounding sans — identifiers dominate the line instead of sitting inside it |
| **code block** | full component: wrapper `border`+`bg-secondary`, radius `var(--radius)` **10 px**, header with **file-type icon + title** in mono 11 px, **wrap toggle + copy button**, **Shiki** highlighting (`ChatMarkdown.tsx:645-693,745-779`; `index.css:1991-2078`) | bare `<pre>`: bg `--muted`, radius 6 px, padding `10px 12px`, `pre code` steps to `--font-size-xs` ✓ | no chrome, no copy, no highlighting, no language/file label |
| **tables** | `width:100%`, `border-collapse: collapse`, font-size **0.75rem**, th/td padding **`0.45rem 0.75rem`**, thead + tbody bottom rules at `border` 60 % mix, cell truncate `max-width:24rem` + expand, wrapped in a container carrying the block rhythm (`index.css:2101-2135`), plus a `MarkdownTable` component (collapse/expand, CSV export) | **nothing declared** → UA table: `border-collapse: separate`, td padding **1 px**, **no rules**, `th` centred, shrink-to-fit (measured **259 px** wide) | the single worst-looking element; unusable as rendered |
| **list items** | `li + li { margin-top: 0.25rem }` (gap *between* only); nested marker cascade disc→circle→square, decimal→lower-alpha→lower-roman (`index.css:1861-1889`) | `li { margin: .125em 0 }` = 1.75 px each side, no cascade | rhythm + no nesting vocabulary |
| **links** | colour `--info-foreground`, **`text-decoration: none`**, hover = dotted radial-gradient underline (`index.css:1907-1918`) | colour `--text-tint` → `--info-foreground` ✓, **`text-decoration: underline` always** | **ours underlines links at rest — colliding with the grounding underline channel (§3.2)** |
| **file references** | `MarkdownFileLink` chip from BOTH `[label](path)` links AND path-shaped inline code: `inline-flex`, gap `0.33em`, radius `0.5em`, `border-border/70`, `bg-accent/40`, `px-[0.5em] py-[0.08em]`, 12 px, `text-foreground`, + a per-extension coloured icon (`composerInlineChip.ts:4-13`, `FileTagChip.tsx:12`, `ChatMarkdown.tsx:1084-1298,1545-1644`) | **none** — a link is a blue underlined word | the pill-with-icon the owner saw |
| **blockquote** | `2px solid var(--border)`, padding `0.8rem`, `--muted-foreground` | `3px solid --border-subtle`, padding-left `0.75rem`, `--text-secondary` | minor |
| hr / img / task lists / footnotes / GFM alerts | all styled (`index.css:1892-1965`; alerts as a `blockquote`→`role=note` swap with per-kind palettes, `ChatMarkdown.tsx:186-220`) | **nothing declared** | UA defaults |
| streaming | none (Shiki cache bypass only) | **`mendMarkdown` closes partial fences/bold/inline-code** (`MarkdownBlock.ts:73-117`) | **ours is better** |

### 2.2 Where the amber came from — corrected

The owner read some chips as amber/warm. The donor has **no amber inline-code variant**.
The amber is the **file-type icon inside a file-reference chip**: `PierreEntryIcon.tsx:7-93`
maps an extension token to a `[light, dark]` hex pair, and `json`/`html`/`rust`/`swift`/`zip`
resolve to `["#d47628", "#ffa359"]`; `markdown` resolves to green `["#199f43", "#5ecc71"]`
(so the `26-extension-substrate.md` pill carries a **green** md glyph). No semantic variant,
no language hint — a per-extension lookup table. Any adoption of "amber chips" is really the
adoption of the file-chip subsystem plus an extension→colour map.

### 2.3 The contrast reading is inverted — do not "raise contrast"

The owner read ours as "flatter/grayer". Measured, our body text is `oklch(0.97 0 0)` at full
alpha; the donor's is the same token at **80 %**. Ours is the higher-contrast surface. What
reads as gray is (a) the dotted underlines and muted marks covering 98.5 % of the body
(§3.2), and (b) the absence of the chips/heading that give the donor page its dark
punctuation. **The remedy is §3 and §2.1's chip row, not a colour change.**

### 2.4 Why the bridge cannot fix this

`Sv3Main.ts:245-251` already records it, and the live measurement confirms the mechanism: the
bridge can only re-point custom properties the component *reads*
(`--text-primary/-secondary/-tint`, `--surface-tertiary`, `--border-subtle`, `--accent-*`,
`--font-size-sm/-xs`, durations). Every geometry value above is a **hard-coded literal inside
`static styles`**, and headings/tables/hr/img have **no rule at all** — a shadow-DOM UA
default is not reachable from a host custom property. The only v3-scoped lever that changes
anything on this list is a `max-inline-size` on `.answer`, which is Sv3Main's own element.

---

## 3. CAUSE 3 — HONESTY OVERLAY (ours alone; two working-as-designed misreads and three
defects)

Live probe: a realistic 6-claim RAG answer with the owner's own marker set, rendered through
the shipped weave.

### 3.1 The raw brackets — DEFECT, root-caused at the prompt/context contract

Measured: **4 of 4 literal brackets survived** (`[0] [26] [2] [20]`).

Mechanism, verified at both ends:

1. `RAGQAStyle.java:29-31` **asks** the model to "Cite sources inline with a bracketed number
   like [1], [2]".
2. The context block it is asked to cite **carries no numbers**: every section header is
   `"[From: " + label + "]"` (`modules/indexing/.../rag/ContextBudgeter.java:94-95`,
   separator `"\n\n---\n\n"`).
3. So the model has no number→source mapping and invents ordinals — `[0]`, `[20]`, `[26]`.
4. `MarkdownBlock.normalizeLiteralCitationTokens:523-549` strips or upgrades a literal `[n]`
   **only when `n` is an existing citation label** (`byLabel.has(Number(m[1]))`, :533). Labels
   are 1-based (`citationResolve.ts:36`), so `[0]` can never match, and `[26]`/`[20]` cannot
   match a 5-source answer. Everything else survives as prose.

This is a **defect, not a density misread**: the prompt asks for a notation the context never
defines. Note the follow-on — when `citations.length === 0`, `decorateCitations` never runs
(`MarkdownBlock.ts:240-242`), so **no** literal is normalised and *every* bracket survives.

### 3.2 The underline wall — WORKING AS DESIGNED, INVERTED AT DENSITY, on top of a scorer defect

Measured on the 6-claim answer: **832 of 845 characters (98.5 %) of the answer body carried a
dotted grounding underline**, 6 of 6 sentences marked, spans of 75-176 characters each. The
final paragraph was fully underlined, exactly as the owner saw.

`MarkdownBlock.ts:302-315` states the principle explicitly ("mark the exception, not the
rule… An indicator that is on for nearly every sentence carries no information") and 687 R1c
names it as its own retirement condition. **At the observed density the principle is
inverted, and the code's own comment is the falsifier.** So: the *rendering* is
working-as-designed; the *density* it is fed is the defect. Two mechanisms produce it:

**(a) Two different scorers share one tier scale (defect).** Tier thresholds
`TIER_HIGH = 0.6` / `TIER_MEDIUM = 0.5` (`evidenceProjection.ts:254-256`) are explicitly
anchored to the **cross-encoder** matcher cutoff (`DEFAULT_CITATION_SIMILARITY_THRESHOLD =
0.5`), so "every cited sentence is ≥ 0.5, hence at least weak". But the *streaming* path
feeds claims from a **lexical word-overlap** scorer:
`StreamingCitationMatcher.matchSentenceLexical:239-277` accepts a match when
`hits >= MIN_WORD_HITS (2) || (significantWords <= 3 && hits >= 1) || overlap >= 0.5` and
emits `score = overlap`. Two matching ≥4-char words in a 20-word sentence → **score 0.1**,
which `groundingClass` files as **`ungrounded`** → amber dotted underline. The FE merges it
with `Math.max` (`sv3-ask.ts:120-131`), so a low lexical score persists on any sentence the
cross-encoder never matched. Net: the underline band is populated by a scorer whose numbers
the thresholds were never calibrated for — the exact cross-authority drift 565 §15.A closed
*within* the FE, reopened across the process boundary.

**(b) The band is only 0.1 wide, immediately above the matcher cutoff.** Everything the
cross-encoder does emit is ≥ 0.5 by construction, and `[0.5, 0.6)` is "weak" → dotted. Only
≥ 0.6 renders clean. A distribution piled just above its own cutoff underlines nearly
everything.

### 3.3 The `59` superscript against 5 sources — DEFECT (cross-process index conflation)

`chunkIndex` carries **two different meanings** under one field name:

- **Post-hoc path (correct):** `CitationScorer.java:274` documents `chunkIndex` as "index into
  the chunks list" — i.e. the position in the citations array — and
  `RemoteDocumentService.java:512-525` passes the citations in order. `UnifiedChatView.ts:5297`
  states this contract explicitly.
- **Streaming path (wrong):** `StreamingCitationMatcher.java:271` emits `c.chunkIndex()` from
  the `ContextCitation` record — the chunk's **ordinal within its parent document** (paired
  with `chunkTotal`, `DocumentService.java:246-249`).

The FE consumes both as a source position (`sv3-ask.ts:208-215`; `UnifiedChatView.ts:5921`),
then `citationResolve.ts:29-36` does `label = refIdx + 1` and `sources[refIdx] ?? sources[0]`.
Consequences: a superscript numbered **59** in a 5-source answer, and a click that
**silently deep-links to source 1** instead of failing. The `?? sources[0]` fallback is what
converts a contract violation into a wrong-but-plausible citation.

### 3.4 The mark palette is non-monotonic — DEFECT

Measured `getComputedStyle` on all three tiers:

| class | colour | reading |
|---|---|---|
| `.cite-ref.cite-grounded` | `oklch(0.707 0.165 254.624)` (blue `--info-foreground`) | prominent |
| `.cite-ref.cite-weak` | `srgb 0.506…` (gray `--muted-foreground`) | recessive |
| `.cite-ref.cite-ungrounded` | **`oklch(0.707 0.165 254.624)` — the same blue as grounded** | prominent |

`MarkdownBlock.ts:383-397` defines `.cite-ref` (blue) and `.cite-ref.cite-weak` (gray) and
**no `.cite-ref.cite-ungrounded` rule**, so the weakest tier inherits the strongest tier's
colour. The sentence underline is correctly monotonic (gray dotted → amber dotted), so a
single ungrounded claim currently renders as an **amber-underlined sentence closed by a
confident blue mark**. That is the mixed "blue superscript + noisy underline" texture the
owner described.

### 3.5 Superscript-vs-bracket mixing — a consequence, not a cause

Two notations appear because §3.1 leaves unmatched literals in the prose while the weave adds
resolved superscripts beside them. Fixing §3.1 removes the mixing; no separate change needed.

---

## 4. CAUSE 4 — CONTENT SUBSTANCE (accepted difference / model-and-tooling-bound)

T3 Code's answer carried mention counts ("~1,400 hits in ~500 files"), per-directory file
counts, and specific class names. That is not a formatting property — it is the output of a
**tool-using loop over the repository** (grep, count, read). Our `core.rag-ask` path retrieves
document chunks and summarises them; there is no counting tool in that shape, and a 1024-token
budget on a Q4 9B model is not the binding constraint on that class of answer — *the absence
of the tool call is*.

Honest framing precedent: tempdoc 820 recorded the analogous result on latency (TTFT is
inference-bound; no FE change moves it). The same discipline applies here — prompt shaping
can buy the *shape* of a T3 Code answer at 9B; it cannot buy its *substance*.

**The one non-accepted lever:** `core.agent-run` already exists in this window and already
has a `SearchTool` with chunk-precise citations (`agent/tools/SearchTool.java:354-359`). A
repo-exploring answer is an agent-shape question, not a rag-ask question. Routing "which area
of code…" questions to the agent shape is a product decision, not a rendering fix.

---

## 5. CHANGE LIST — every named change, tiered

### (a) v3-scoped — bridge / CSS / fixture (the F9 pattern; Sv3Main only)

| id | change | file | note |
|---|---|---|---|
| A1 | **Cap the measure**: `max-inline-size: 48rem` on `.answer` (donor `max-w-3xl` = 768 px) | `Sv3Main.ts` `.answer` (:230) | measured 1636 px / ~131 chars per line today; one declaration |
| A2 | Bridge `--font-mono` **if** B13 makes the component read it | `Sv3Main.ts:252-279` | no-op until B13 |
| A3 | Re-point `--text-tint` for `.cite-ref` only, **if** E-3 chooses a mark recolour without touching the component | `Sv3Main.ts` | cosmetic half-measure; B10 is the real fix |

**This tier is nearly empty, and that is the finding**: everything else on the list is a
hard-coded literal or a missing rule inside a shared component (§2.4).

### (b) MarkdownBlock / shared-component slice (the recorded geometry gap, grown to scope —
touches `UnifiedChatView`, `SummarizeView`, `ReasoningBlock`, Search v3)

| id | change | value |
|---|---|---|
| B1 | **Heading rules h1-h6** (currently none → UA defaults) | donor: 1.25/1.125/1/0.875 rem, weight 600, lh 1.3, margin `1.25rem 0 0.5rem`, h6 muted |
| B2 | **Block rhythm** `p, ul, ol, blockquote, pre` → `0.65rem 0`; `:first-child`/`:last-child` → 0 for all block types | today 0.25em/0.5em, `:first/:last` only on `p` |
| B3 | **Inline-code chip**: add `1px solid var(--border-subtle)`, radius 6 px, `font-size: var(--font-size-xs)` (12 px), `font-family: var(--font-mono, ui-monospace, …)` | highest ratio on the whole list |
| B4 | **Table rules** (currently none): `width:100%`, `border-collapse: collapse`, 12 px, th/td `0.45rem 0.75rem`, header + row bottom rules, and an `overflow-x:auto` container | today a 259 px UA table with 1 px padding |
| B5 | `li + li { margin-top: 0.25rem }` replacing symmetric `li` margins; nested marker cascade | |
| B6 | **Links: no rest-state underline**, underline on hover/focus | frees the underline channel for grounding marks (§3.2) |
| B7 | **Code-block chrome**: language/file header + copy button (+ optional highlighting) | own sub-slice; largest item here |
| B8 | Rules for `hr`, `img`, task lists, footnotes (currently unstyled) | |
| B9 | **Expose geometry** as custom properties or `::part()`s so a host window can retune without forking | the structural fix `Sv3Main.ts:250-251` asks for; blocks a repeat of this audit |
| B10 | **`.cite-ref.cite-ungrounded` colour** — currently inherits grounded blue (§3.4) | monotonic tier palette |
| B11 | **Underline-density guard**: when the marked fraction of the answer exceeds a threshold, collapse per-sentence marks to a single per-answer statement | 687 R1c's own retirement condition, now met at 98.5 % |
| B12 | **File-reference chip**: detect path-shaped inline code + doc links → pill with a file-type icon (our `Icon.ts` is the same Lucide set the donor draws from) | the donor's most distinctive inline element |
| B13 | Replace the generic `monospace` keyword with `var(--font-mono, …)` | pairs with A2 |

### (c) Ask-pipeline prompt shaping

| id | change | file |
|---|---|---|
| C1 | **Add output-shape guidance** to the RAG-ask fragment: a short `##` section heading, backticks around identifiers/paths/filenames, numbered list with bold run-ins, a bold `**Short answer:**` close | `spi/RAGQAStyle.java:26-32`; pattern precedent `spi/URLEmissionGrammar.java:71-99` |
| C2 | **Number the context sections** — `[1] <label>` instead of `[From: <label>]` — so the `[n]` the prompt already asks for maps to a real source | `rag/ContextBudgeter.java:94-95` (+ the FE/agent readers of that header) |
| C3 | *Alternative to C2*: **stop asking for `[n]` entirely** — the UI weaves marks post-hoc and owns source presentation, so the model's brackets are pure noise | `RAGQAStyle.java:29-31`; see E2 |
| C4 | Raise the ask-path token budget if C1 lands (headings + lists + a close cost tokens against a 1024 default) | `ConversationEngine.java:65` |
| C5 | **Fix the streaming lexical score contract**: either stop feeding lexical overlap into the grounding tier, or carry a scorer tag so `groundingClass` is applied only to cross-encoder scores | `StreamingCitationMatcher.java:239-277` + `evidenceProjection.ts:254-256` |
| C6 | **Fix the `chunkIndex` conflation**: emit the citation-array position (loop index `i`) on the streaming path, or rename the field per meaning; remove the `?? sources[0]` silent fallback | `StreamingCitationMatcher.java:271`; `citationResolve.ts:29-36` |

### (d) Engine / content-bound — accepted difference or model-upgrade territory

| id | item | verdict |
|---|---|---|
| D1 | Mention counts, per-directory file counts, concrete class names | **accepted** on `core.rag-ask` — requires a tool loop, not a bigger model |
| D2 | Route "which area of code…" style questions to `core.agent-run` (has `SearchTool`) | product decision; the capability already exists |
| D3 | Frontier-vs-9B answer quality in general | accepted; frame it the way 820 framed TTFT |

### (e) Owner-taste decisions

| id | question |
|---|---|
| E1 | Underline density: a cap (B11), a threshold raise, or drop per-sentence marks for grounded answers entirely? |
| E2 | Unmatched raw `[n]`: **suppress** (strip), **mute** (reuse `.pseudo-cite`), or **make them meaningful** (C2)? C3 vs C2 is the same question from the prompt end. |
| E3 | Should a superscript show at all when its target resolves by fallback (§3.3), or should an unresolvable mark be dropped? |
| E4 | Do we want the donor's 80 %-alpha body text? Ours is currently *higher* contrast (§2.3) — this is a taste call, not a fix. |
| E5 | File-reference chips: adopt the pill + per-extension icon colours (B12), or keep plain links? |

---

## 6. Top 5 by perceived-gap-closed per unit effort

1. **C1 — prompt shape guidance** (one string, one file). Unlocks headings, backticked
   identifiers, bold run-ins, a bold close. Nothing in tier (b) is visible without it. It is
   also the only change that moves the answer's *silhouette* rather than its texture.
2. **B3 — the inline-code chip** (4 CSS declarations). Turns every identifier and path into a
   donor-grade chip: border, 12 px step-down, real `--font-mono`, 6 px radius. Highest
   ratio on the list; pairs with C1 (which supplies the backticks to style).
3. **C5 + B11 — kill the underline wall.** 98.5 % of the body is currently underlined; C5
   removes the miscalibrated lexical scores feeding the band, B11 caps what survives.
   Removes the "wall of links" reading in one stroke and restores the exception-mark
   principle the component's own comment asserts.
4. **C2 (+E2) — numbered context sections.** Root-causes the raw `[0]/[20]/[26]` noise at the
   contract instead of filtering it downstream, and makes the model's own citations true.
5. **B1 + B2 + A1 — heading scale, block rhythm, measure cap.** ~25 lines of CSS plus one
   declaration. Replaces UA 21 px/17.4 px headings with the donor's designed scale, triples
   the block rhythm to 10.4 px, and cuts the line from ~131 to ~95 characters.

Runners-up, high value but larger: **C6** (the `59`-against-5-sources defect — correctness,
not appearance, and should not wait on a cosmetic queue), **B4** (tables — invisible today
because the model emits none; becomes the worst element the moment C1 lands), **B12** (file
chips), **B7** (code-block chrome).

**Sequencing consequence:** C1 must land *before or with* B1/B3/B4, or the styling slice
verifies against content that does not exist. C6 is independent of all of it.
