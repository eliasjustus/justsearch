# 846 — Markdown rendering substrate: one parser, one ramp, one code theme

    status:  DESIGNED + IMPLEMENTED (frontend-only; typecheck + unit suite green)
    created: 2026-08-19
    updated: 2026-08-19
    scope:   modules/ui-web only — no backend, no contract, no gate change
    follows: 822 (block geometry S4 + prose variant S5 — the `--md-*` vocabulary this
             work moves without renaming), 839 (citation-mark presentation — whose
             hooks this work must leave intact; a separate charter re-designs
             citation anchoring after this merges)

## 1. Subject

The app parses and dresses markdown in **two** places that never agreed with each other:

| | chat renderer | inspector renderer |
|---|---|---|
| module | `components/chat/MarkdownBlock.ts` | `components/documentPane/markdownBlockMap.ts` + `DocumentPane.ts` |
| parser | module-scoped `new Marked({ breaks: true, gfm: true })` | a **second**, independent `new Marked({ breaks: true, gfm: true })` |
| granularity | whole answer text | per top-level block (needed for `highlightRange` line mapping) |
| typography | ~190 lines of `.md-content` rules + the `:host([prose])` ramp | `.blocks .block { margin: 0.25em 0 }` and nothing else |
| code blocks | plain monospace | plain monospace |

Consequences this tempdoc closes:

1. **Two parser authorities.** Two `Marked` instances configured by copy. A change to one silently
   does not reach the other; the `markdownBlockMap` header even documents the duplication as
   deliberate ("one markdown authority, just fed one block at a time") while constructing a second
   instance three lines later.
2. **The inspector's markdown is unstyled.** A `.md` file opened in `DocumentPane`'s Rendered mode
   renders through user-agent defaults: 32px `<h1>`, browser list indents, an unstyled `<table>`, a
   `<pre>` with no surface. The one surface in the app whose entire job is *reading a document* is
   the one surface that does not dress documents.
3. **No syntax highlighting anywhere.** Every fenced code block in every answer and every rendered
   file is undifferentiated monospace.
4. **`breaks: true` for model output.** A language model emits real markdown paragraphs; `breaks`
   converts every single newline inside a paragraph into a `<br>`, so a model that soft-wraps its
   prose gets its paragraphs chopped into ragged lines. The option was chosen for chat-message
   ergonomics (where a human's Enter should be a line break) and then applied to LLM prose and to
   `.md` files on disk, neither of which is chat-message text.
5. **`stripTrailingCitationBlock` deletes model output unconditionally.** It runs on every render,
   including when the component has **no** citations to show. Its whole justification (565 §13.8) is
   "the UI is the single source authority" — but when the UI is showing no sources, deleting the
   model's own trailing source list removes information and replaces it with nothing.

## 2. Design

Four shared modules under a new `components/markdown/` directory — the neutral home neither consumer
owns, so neither import is a component reaching into another component's folder.

### 2.1 One configured parser — `markdownRenderer.ts`

```
createMarkdownRenderer({ breaks }) → { render(source): string }   // marked.parse + DOMPurify.sanitize
```

The factory owns everything both consumers had in common — `gfm: true`, `async: false`, the
`DOMPurify.sanitize` step that every parse must be followed by — and takes as its **one** argument
the thing they legitimately differ on. Consumers keep their own parse *granularity*: `MarkdownBlock`
renders the whole answer, `markdownBlockMap` renders one block at a time (that per-block split is
what carries the source line ranges `highlightRange` maps onto, and it stays).

`breaks` is a required parameter with no default. A default is what produced the current state: one
consumer's ergonomic choice inherited by every other consumer that never thought about it.

**Why sanitization moves into the factory too:** the pair `parse → sanitize` is a security-relevant
invariant, and it was duplicated. A third consumer that copies only the parse half is a real defect;
a factory whose only output is already-sanitized HTML cannot be misused that way.

### 2.2 `breaks` per consumer

| consumer | value | why |
|---|---|---|
| `MarkdownBlock` (all 10 shipped call sites + the 2 sv3 ones) | **`false`** | Every call site renders **model-generated** text — the agent answer, the RAG answer, the extract, the summary/navigate streams, the reasoning trace. Models emit real markdown: blank-line paragraphs, lists, fences. `breaks: true` turns their soft-wrapped prose into forced line breaks. |
| `markdownBlockMap` (DocumentPane rendered mode) | **`false`** | Rendered mode is reachable only for `.md`/`.markdown` files (`DocumentPane` disables the toggle otherwise). Authored markdown files are hard-wrapped by convention — 80 or 100 columns — and every standard renderer of a `.md` *file* (GitHub's file view, CommonMark, VS Code preview) joins those lines into a paragraph. `breaks: true` renders a hard-wrapped file as a column of ragged short lines that does not match the file's appearance anywhere else. |

The charter asked for a stated reason before changing DocumentPane's file rendering; the paragraph
above is it. Nothing about the line-range mapping changes: `markdownBlockMap` splits blocks itself
(blank lines, fences, ATX headings) *before* `marked` ever sees a block, so `breaks` cannot move a
block boundary — it only decides whether the lines *inside* one block reflow.

**No site renders user-authored text as markdown.** Verified across the whole shell: the ten
`<jf-markdown-block>` call sites are enumerated (and their count frozen) in
`MarkdownBlock.geometry.test.ts`, and user turns in `UnifiedChatView` render as plain inline text,
never through the block. So the "ON for user-authored messages" half of the charter has no
consumer today; the parameter exists and is required, so the day a user-composed surface appears it
must state its own answer rather than inherit one.

### 2.3 One typography ramp — `markdownStyles.ts`

`markdownTypography` is a Lit `css` result carrying, **verbatim**, what `MarkdownBlock` already had:

* the `:host` rule — the fifteen `--md-*` geometry tokens plus the six host typography declarations;
* every `.md-content` element rule (p / code / pre / pre code / ul / ol / li / a / a:hover / strong /
  blockquote);
* the whole `:host([prose])` block — its nine variant tokens and every heading / table / hr / img /
  task-list rule.

Both consumers put it **first** in their `static styles` array, so each keeps the last word over any
declaration it needs to differ on.

Three properties of this move are load-bearing and each is asserted:

* **The token indirection survives byte-identically.** The names, the defaults and the use sites are
  the ones 822 froze, so Search v3's `.sv3-markdown` bridge keeps re-pointing exactly what it
  re-pointed before. `MarkdownBlock.geometry.test.ts` reads `MarkdownBlock.styles` — an array is
  flattened and joined, so the containment proof (frozen defaults, resolved-declaration equality,
  selector gating, the variant's own defaults, truncate/expand) runs against the moved rules
  unchanged and unedited. **That test not needing a single edit is the evidence the move is
  faithful**, and it is why the rules were moved rather than rewritten.
* **`MarkdownBlock` keeps only what is chat's.** `.md-content.plain`, the cursor, the citation
  marks, the cited-sentence tiers, `.pseudo-cite` — none of that is markdown typography, and none of
  it moves. In particular no second `:host` rule may be declared alongside the shared one (two
  `:host` rules would make "which rule declares the tokens" ambiguous, and the containment proof
  reads the one).
* **`DocumentPane` opts into the prose variant.** The variant exists for exactly what a document is —
  headings, tables, rules, images, task lists — so the pane declares a reflected `prose` property
  (default `true`) and its rendered-block container carries the `md-content` class the shared rules
  are scoped to. It reaches the ramp through the *same* attribute Search v3 uses, not a parallel one.

The known cost, stated rather than discovered later: adopting the shared sheet gives the pane host
the `:host` typography declarations too (`font-size: var(--font-size-sm)`, `line-height: 1.6`,
`word-wrap: break-word`). The pane already sets its own `display`, `color` and `font-family` in a
later rule, and its header/toggle/provenance rows all declare their own `font-size`, so what is
actually affected is the document body — which is the thing that was supposed to get the ramp.

### 2.4 Syntax highlighting — `markdownHighlight.ts` + `markdownHighlightRuntime.ts`

**Choice: `highlight.js` v11, `lib/core` + an explicit language list, behind a lazy `import()`.**

Considered and rejected:

* **shiki** — the quality leader (real TextMate grammars, VS Code themes), and the wrong shape for
  this app: its default engine is a WASM regex build, and its themes are JSON files carrying
  hard-coded hex per scope. Both fight this codebase — `check-color-tokens` bans bare hex in
  `modules/ui-web/src/**/*.ts`, and a WASM asset is a second thing the offline installer must ship
  and the CSP must permit.
* **prismjs** — comparable size to `highlight.js`, but its language files are side-effecting globals
  designed around a `<script>`-tag era, which composes badly with ESM code-splitting.
* **hand-rolled tokenizer** — no dependency, and no correctness: a regex highlighter that is wrong on
  nested strings/comments is worse than plain monospace, which is at least honest.

`highlight.js` wins on the axes this app actually has: pure JS (no WASM, no worker, no `eval`), a
synchronous `hljs.highlight(code, { language })` call, per-language ESM modules that tree-shake, a
class-based output (`hljs-keyword`, `hljs-string`, …) that lets **us** supply the theme from design
tokens instead of importing someone's hex, and BSD-3-Clause.

**Language set** (19, chosen as what a local-first desktop search app's answers and indexed repos
actually contain): bash, c, cpp, csharp, css, diff, go, ini/toml, java, javascript, json, kotlin,
markdown, python, rust, sql, typescript, xml/html, yaml. Anything else falls back to plain — see
below. The list is one array in the runtime chunk; adding a language is one import + one register.
Aliases come with each grammar (`js`, `ts`, `py`, `sh`, `yml`, `html`, `toml`, `c++`, `cs`, …), so
the lookup asks `hljs.getLanguage` rather than matching the list textually.

**Bundle discipline — measured, not asserted.** `npm run build` emits
`dist/assets/markdownHighlightRuntime-*.js` at **90.78 kB (29.13 kB gzipped)** as its own chunk;
the main `index` chunk is unchanged apart from the loader and the theme's class names. Verified
causally rather than by inspection of the config: a Python-grammar-only string (`nonlocal`) appears
**1×** in the lazy chunk and **0×** in `index`, while the only `hljs` text in `index` is the CSS
class names of the theme. `markdownHighlight.ts` (loader + DOM pass) is what the components import;
the core and grammars live in `markdownHighlightRuntime.ts`, reached only through a dynamic
`import()` whose emitted specifier is the relative chunk filename (`base: './'` in the Tauri build)
— no CDN, so the shipped `script-src 'self'` CSP is satisfied and the app stays fully offline. This
is the pattern `PluginLoader`'s lazy `import('ses')` already established.

**Fallback is the default, not the error path.** The pass runs over settled content only and, for
each `pre > code`:

* highlighter not loaded yet → the block stays plain monospace, a load is kicked off, and every code
  block in that root is re-visited when it resolves;
* no language class, an unknown language, a load failure, or a throw from `hljs` → the block is
  marked handled and left as plain monospace.

So "unknown language" and "highlighter unavailable" render identically to today. The pass is
idempotent (a `data-hl` marker) and skipped while streaming (content churns per frame, and mended
fences would flicker).

**The highlighted HTML is NOT re-sanitized**, which was a deliberate reversal of the first cut. The
input is the block's `textContent` — never markup, since the markdown was already parsed *and*
sanitized by §2.1's factory — and `hljs` escapes what it is handed, so there is no unescaped-HTML
path into the assignment. Sanitizing again bought nothing and cost something real: `DOMPurify` under
happy-dom unwraps the first element of any fragment (a known test-environment defect, documented in
`MarkdownBlock.geometry.test.ts`), so it silently ate the first highlighted token in every test. The
invariant stays where it belongs — at the boundary where untrusted markdown ENTERS, which is the
factory.

**Selector.** The pass matches `pre > code, code[class*="language-"]`. The second half is not
belt-and-braces: `DocumentPane` sanitizes each block separately, and a sanitizer that unwraps the
outer element leaves a `<code>` with no `<pre>` parent. A `language-` class is only ever emitted for
a fence, so the second selector cannot reach an inline code chip.

**Theme from existing tokens, not new ones.** The eight scope groups map onto `--text-*` role tokens
that already exist in `tokens.css` for every palette (comment → `--text-tertiary` + italic, keyword →
`--text-command`, string → `--text-success`, number/literal → `--text-highlight`, title/function →
`--text-link`, type/built-in → `--text-chat`, meta/attr → `--text-secondary`, everything else
inherits `--text-primary`). This buys theme completeness for free (light, dark and the two
high-contrast palettes all define these), avoids minting a parallel colour vocabulary, and keeps
`check-color-tokens` / `check-accent-as-text` clean by construction: no hex, no `--accent-*` as text.

### 2.5 Conditional citation stripping

`stripTrailingCitationBlock` stays exactly as it is — a pure, unit-tested function. What changes is
**when the component calls it**: only when `this.citations.length > 0`.

The justification for deleting a model's trailing "Sources:" list is that the UI is showing those
sources itself. When `citations` is empty the UI is showing nothing, so the deletion has no
justification left and silently costs the reader whatever the model wrote.

Accepted trade-off, recorded: citations attach post-stream (the matcher runs at `AgentDone`), so
during streaming `citations` is empty and a trailing list the model is in the middle of writing is
now visible for the remainder of the stream instead of being suppressed from the first token. That
is the correct direction of the trade — a brief flash of real model output beats silently deleting
model output in the case where nothing replaces it — and it is the same window in which the answer's
citation marks are not yet woven either.

## 3. What this supersedes / orphans

* The second `new Marked(...)` in `markdownBlockMap.ts` — **deleted**, not left as a fallback.
* The duplicated `parse → DOMPurify.sanitize` pair in both consumers — replaced by the factory's
  single implementation.
* `DocumentPane`'s private `0.25em` block rhythm — the literal is gone; the rule now READS
  `--md-block-gap`. The wrapper element itself is not orphaned and must not be: it is what carries
  `data-line-start`/`data-line-end`, and because each rendered block is its own wrapper the ramp's
  `p:first-child` / `p:last-child` zeroing fires on every paragraph, so the wrapper is exactly where
  the inter-block gap belongs and nothing double-counts it.
* Nothing else is orphaned: no file, gate, baseline or doc names the removed constructs.

## 4. Reach — the principle, and its retirement condition

The shape here is not "markdown" — it is **a substrate with two consumers where the second consumer
was written by copying the first**. Both defects follow from that one act: the copied `Marked`
options fork on the day either side is tuned, and the *uncopied* stylesheet is why one surface has
typography and the other has none. Copying is a lossy fork: what gets copied is whatever the copier
noticed.

Stated generally: **when a second consumer of an existing capability appears, the shared part becomes
a module and the differing part becomes a parameter** — the differing part being named explicitly
(here: `breaks`) rather than inherited by default, so the fork surfaces as an argument at the call
site instead of as a divergence nobody reads.

Where else this already applies, named without building anything for it now:

* `DocumentPane`'s file header explicitly documents a *third* instance of the same move — it
  duplicates `InspectorPane`'s `previewProvenanceLabel` / `previewEvidenceDetail` helpers with the
  reason "this component owns only new files, so the shared logic is duplicated rather than
  extracted". That is the same trade this tempdoc is paying back, still outstanding.
* The retired `StreamingTextBlock` (565 §15.B) was the *successful* application of the principle:
  two renderers with one weave collapsed into one renderer with a `format` parameter.

**Evidence it earns its keep:** a change to markdown behaviour (an option, a rule, a language) that
lands in one file and is visible in both surfaces. **Retirement condition:** if a consumer ever needs
the shared module bent — a conditional inside `createMarkdownRenderer` keyed on *which caller* is
asking, or a `:host([prose])` rule that exists only for the pane — the two consumers do not in fact
share a reason to change, and the right move is to split them again rather than grow a parameter
list. One parameter is a seam; three parameters that only one caller ever varies is a fork wearing a
module's clothes.

## 5. Implementation

New files (`modules/ui-web/src/shell-v0/components/markdown/`):

* `markdownRenderer.ts` — `createMarkdownRenderer({ breaks })`.
* `markdownStyles.ts` — `markdownTypography`, `markdownCodeHighlight`.
* `markdownHighlight.ts` — `highlightCodeBlocks(root)`, `loadHighlighter()`, `isHighlighterLoaded()`,
  `HIGHLIGHT_LANGUAGES`.
* `markdownHighlightRuntime.ts` — the lazily-imported chunk: `hljs` core + the 18 registrations.
* `markdown.test.ts` — the new behaviour (factory options, breaks-off, conditional strip, highlight
  + its unknown-language / not-yet-loaded / alias / idempotency cases, the pane's ramp adoption).
* `markdownHighlight.failure.test.ts` — the load-FAILURE fallback, in its own file because making
  the chunk genuinely unavailable needs a file-scoped `vi.mock`. Asserting that fallback against a
  chunk that loads fine is the `green-masked-destructive` shape: it passes for the wrong reason.

Edited:

* `components/chat/MarkdownBlock.ts` — imports the factory (`breaks: false`), the shared sheet, and
  the highlight pass; gates the strip on `citations.length`; keeps the citation weave, the streaming
  mend and the rAF throttle untouched.
* `components/documentPane/markdownBlockMap.ts` — second `Marked` deleted; renders through the
  factory (`breaks: false`).
* `components/documentPane/DocumentPane.ts` — adopts the shared sheet, `prose` property, the
  `md-content` container class, the highlight pass; its own `.blocks .block` margin removed.
* `components/documentPane/markdownBlockMap.test.ts` — one test's name and assertion updated for
  breaks-off (§2.2), with the reason inline (it now also asserts the absence of `<br>`, which the
  old test only named in its title).
* `components/chat/MarkdownBlock.test.ts` — seven `styles as { cssText }` casts routed through one
  flatten helper, since `styles` is an array now. No assertion changed.
* `THIRD_PARTY_NOTICES` — the `highlight.js 11.12.0 — BSD-3-Clause` row, in the position and format
  `gen-notices.mjs` projects (the generator needs a Gradle license report this worktree has not
  produced, so the row was placed by hand to match its output exactly; CI's `check-notices-regen`
  is the check that this is right).

**Verification.** `npm run typecheck` clean; `npm run test:unit:run` 424 files / 5259 tests passed.
The whole `ui-web-gates` recipe was run: every gate passes except three that are RED on `main` in
files this branch does not touch (`check-theme-token-closure` and `strip-token-fallbacks` on
`RecentsMenu.ts`, `check-accent-as-text` and `strip-token-fallbacks` on `ActionLedgerView.ts`,
`check-controls-a11y` on `UnifiedChatView.ts:2143`); the first two are recorded as expected-state on
`main`. `MarkdownBlock.geometry.test.ts` passes **unmodified**, which is the §2.3 faithfulness
evidence.

**Not done, deliberately:** no live browser/`ui-shot` verification of the new rendering (this was a
worktree-only, backend-free charter and the dev stack is shared); the ramp's arrival in the pane and
the code theme's colours across the four palettes are the natural subject of a measured UX audit by
someone other than the implementer, per the presentation-authority closure discipline.

Constraints honoured: `decorateCitations` / `normalizeLiteralCitationTokens` / `makeMarker` /
`applyCitationHighlight` and every `.cite-*` rule are untouched (839's successor charter re-designs
anchoring on top of them); `mendMarkdown` and the streaming path are untouched; no backend file is
in the diff.
