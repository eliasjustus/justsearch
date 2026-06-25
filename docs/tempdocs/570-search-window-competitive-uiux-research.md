---
title: "570 — The Search Window: from competitive research to the correct design — the search surface as a projection of the canonical authorities (the last hand-authored fork of the spine)"
type: tempdocs
status: open
created: 2026-06-09
category: frontend / ux / search / competitive-research
scope: THE SEARCH WINDOW ONLY. The query input, the results surface, the answer/RAG surface, the filter/facet model, result-item anatomy, keyboard model, zero/loading/empty states, and provenance display. Out of scope: settings, onboarding, billing, account, chrome unrelated to search.
related:
  - tempdoc 569 (the user-authored-frontend projection spine — the engine any future search-window redesign would project through)
  - tempdoc 561 (one-interaction-surface IA; modes-as-axis-points; "re-mount, never re-model" — directly relevant to the single-input-that-adapts pattern all three share)
  - tempdoc 565 (agent-window presentation + evidence rendering — the citation/provenance findings here are the search-side analogue)
  - tempdoc 553 / 549 / 551 (the canonical SearchTrace record + the `execution-surface` register/gate — the data the results/answer surface projects, and the prevention template)
  - tempdoc 559 (presentation projection kernel — SurfaceFactory / createRegistry / "re-mount never re-model" / the Lit-template ceiling — the engine the search body must be projected through)
  - tempdoc 558 (accessible colour-pair — the a11y floor any result/answer surface must co-project)
  - tempdoc 543 (the closed Effect union / Action / operationRef / Effect Journal / PendingEffect — the verb-space a search result must carry) ; tempdoc 550 (operation lifecycle / evaluateIntent→IntentVerdict / attenuable Grant — the trust seam result-actions pass through)
method:
  - Web research: ~10 targeted searches + canonical-page fetches (LangChain Perplexity case study, Raycast developer docs, Glean user-guide + product page, command-palette pattern references, empty-state UX literature). Sources in Appendix A.
  - Visual inspection: Claude-in-Chrome. Perplexity = live, logged-out, real query run (the answer view, the Links/sources view, follow-ups, the persistent composer). Glean = product-page hero (the real product UI, rendered as a marketing still) + the official user-guide operator model (live app is gated behind a corporate tenant). Raycast = official site product imagery (List+Detail+Metadata, Grid, AI-answer mode, the footer action bar) + the developer API docs (native macOS/Windows app, not browsable). Fidelity per product in Appendix B.
  - Live JustSearch baseline (added 2026-06-09): read-only Claude-in-Chrome inspection of JustSearch's *own* `core.search-surface` against a running dev stack (UI :5173 / API :57871) with a real 5-doc markdown index — zero state, results state, per-result explainability, the detail pane, and the visual theme. No ingest, no stack takeover (the stack was owned by another worktree; the answer/RAG tabs were confirmed present but not triggered, to avoid loading the shared inference server). Recorded in §8.
---

# 570 — The Search Window: from competitive research to the correct design

> **Document shape.** **Part I (§1–§10 + Appendices)** — the empirical grounding: competitive UI/UX research on the three best search front-ends + a live, measured baseline of JustSearch's own search window. **Part II (§11–§17)** — the centre of gravity: the correct long-term *structure* for the search window (design-theory genre; feasibility disregarded), which supersedes Part I's §9 implications. Read Part I for the evidence; read Part II for the design.

# Part I — Competitive research & live baseline (findings)

## §1 Purpose & scope

Most current frontend work (557→569) is building a single **projection spine**: one authority → a typed declaration → a governed projection. 569 generalizes that spine to the *whole* user-authored frontend. None of those docs ask the narrower, product-facing question this one does:

> **What does a best-in-class *search window* actually look and behave like, and what should JustSearch's search window learn from the field?**

JustSearch's search window is the product's centre of gravity: a desktop surface where a user types a query and gets **hybrid retrieval** (BM25 + SPLADE + dense vectors, fused) over their **own local files**, with an optional **LLM RAG/answer** layer on top. That description is a near-exact intersection of three mature product categories — and each category has a clear, widely-acknowledged leader. This doc inspects all three at the level of the *search window specifically* (the query box, the results surface, the answer surface, filters, result-item anatomy, keyboard model, and the zero/loading/empty states) and synthesizes the transferable patterns.

**Part I is a findings doc.** It commits no design. §8 measures JustSearch's *own* search window live against this field; §9 lists implications as *options grounded in evidence*. **Part II (§11–§17) supersedes §9** with the correct long-term *structure* — the centre of gravity of this document — derived after a codebase + adjacent-tempdoc investigation (§13).

## §2 Why these three — the archetype mapping

The web research (Appendix A) converges on three products that are each the consensus leader of a distinct archetype, and those three archetypes are exactly the three faces of JustSearch's search window:

| Archetype | Leader (per research) | JustSearch face it maps to |
|---|---|---|
| **AI answer engine** — query → synthesized, cited answer | **Perplexity** — "the canonical AI answer engine," repeatedly named best AI search 2025/26 | The RAG / chat-over-results layer |
| **Keyboard-first local launcher** — instant, list+detail, keyboard-driven | **Raycast** — the consensus best macOS launcher UX; the reference cmdk surface | The local-desktop, instant, keyboard-first window |
| **Neural search over *your own* documents** — federated, personalized, permission-trimmed | **Glean** — repeatedly named the top enterprise AI search | The closest functional twin: hybrid search over personal files + AI answer + connectors |

No fourth was needed: Algolia (instant-search-as-you-type) is subsumed by Raycast's keyboard-first instant model for a *desktop window*; Linear/cmdk is the command-palette pattern Raycast exemplifies; Google/Amazon are web/e-commerce, a different surface. The trio is both "the best" and maximally representative of JustSearch's actual shape.

---

## §3 Perplexity — the AI answer engine

*Inspection fidelity: LIVE (logged-out, real query: "What is hybrid search… BM25 + vector embeddings?"). All states below were observed directly.*

### §3.1 Anatomy observed

**Zero state (home).** A near-empty canvas: centered wordmark, one large **"Ask anything…"** input, and a thin **mode toolbar inside the input's footer** — `+` (attach), a **`Search ▾`** mode pill, a **`Computer`** mode pill, a **`Model ▾`** selector, mic, and a voice/"audio" affordance. Above: a slim category nav (Discover, Finance, Health, Academic, Patents). The whole zero state says *one thing*: type here. Two large suggestion cards sit below the input ("Search anything", "Get work done with Computer").

**Answer view (the core).** On submit the window transforms — not navigates — into an answer surface:
- A **result-type tab bar** at the very top: **`Answer` | `Links` | `Images`** (+ `Share`). This is the single most important structural decision: *the synthesized answer and the raw retrieval list are two tabs of one result*, with the answer as the default.
- The **query echoed** as a header chip.
- The **answer body**: well-structured prose — short lead paragraph, then hierarchical bullets, sub-bullets, sub-headings ("How it works", "Why use hybrid search", "Key design choices"), an "Illustrative example", and a closing offer of follow-on help.
- An **answer action row** at the end: share, export/download, copy, rewrite, and a **"N sources" pill** ("10 sources"), plus thumbs-up/down and an overflow `…`.
- A **"Follow-ups" block**: 2–3 suggested next questions, each prefixed with a ↪ glyph, one-click to continue.
- A **persistent "Ask a follow-up" composer pinned at the bottom**, carrying the same mode pills (`Search ▾`, `Model ▾`, mic, send). The conversation thread is the spine; the search box never leaves.

**Links view (the retrieval surface).** Clicking `Links` reveals what is essentially a **classic web-results list**, headed "Search results for: *hybrid search information retrieval*" — note the query was **auto-reformulated** from the natural-language question into a retrieval query. Each result row: **favicon + domain**, the **full URL**, a **title (link)**, a **snippet**, and an **optional thumbnail** on the right. This is the raw evidence behind the synthesized answer, one tab away.

### §3.2 The transferable UX ideas

1. **Answer-first, evidence-one-tab-away.** The default surface is the synthesized answer; the ranked source list is not hidden but is *subordinate* (a peer tab + an inline "N sources" pill). Hierarchy = Answer > Sources > Follow-ups (confirmed by UX teardowns in Appendix A).
2. **Provenance is structural, not decorative.** Citations are designed for *accountability*: a sources pill, hoverable citation snippets, and a full source list. "Citation fidelity is embedded into every interaction" (LangChain case study).
3. **Latency is hidden by progressive disclosure.** Perplexity's own finding: users tolerate waiting *if shown intermediate progress*. Their answer to this was a step-by-step plan UI with expandable steps — "you don't want to overload the user… until they are curious; then you feed their curiosity."
4. **The query box is a conversation, not a one-shot.** Follow-ups + a persistent composer turn a single search into an iterative session without ever leaving the surface.
5. **Mode is an in-input pill, not a separate screen** (`Search`/`Computer`/focus modes, `Model ▾`). This is 561's "modes-as-axis-points" arriving from the field.

---

## §4 Raycast — the keyboard-first local launcher

*Inspection fidelity: official-site product imagery (real UI, marketing stills) + developer API docs. Native app, not browsable. The List/Detail/Metadata/Grid/AI-answer/footer-bar states below were observed in the site's product animation; the IA terms are from developers.raycast.com.*

### §4.1 Anatomy observed

Raycast is a **floating, centered, single-window** surface invoked by a global hotkey (⌘-Space class). It is the purest expression of the **command-palette pattern**: one input, a live-filtering list, keyboard-first throughout.

- **Search bar (top).** Full-width "Type to filter entries…". Items filter by fuzzy match on title + keywords as you type. A **search-bar accessory dropdown** sits at the right edge (observed: "All Types" → "Images Only") for scoping without leaving the keyboard (⌘P).
- **Root Search.** With no command open, the input filters the **home list**: apps, commands, recent items, fallback commands. This is the launcher's entry point to "everything."
- **List item anatomy** (from docs + observed): **icon (left) · title · subtitle · accessories (right)**. Accessories are a right-aligned array of small text/date/tag(colored)/icon chips — e.g. status, recency, counts. Items group into **Sections** with titles ("Today").
- **Detail pane (right, optional).** When enabled, a right pane shows a **markdown preview** of the selected item plus a **Metadata block** at the bottom: `Label` (icon+text rows), `TagList` (colored tags in a row), `Link`, and `Separator`. Observed: a clipboard-history entry showing a color swatch / image preview + an "Information / Application: VS Code" metadata table. *Docs guidance: when the detail pane is on, push detail into metadata and drop list accessories — avoid duplicating.*
- **Grid layout.** For visual content (emoji, icons, images), the list becomes a **Grid** with sections ("Pinned", "Smileys & People").
- **Footer action bar (bottom).** A persistent bar: command icon + name on the left; on the right the **primary action with its ↵ hint** ("Copy to Clipboard ↵") and **"Actions ⌘K"** — the trigger for the **Action Panel**.
- **Action Panel (⌘K).** A bottom-right popover listing *every action available for the selected item*, each with an optional keyboard shortcut. This is how Raycast keeps the main surface clean while exposing deep per-item operations — discoverable (⌘K) yet keyboard-fast (direct shortcuts).
- **AI answer mode.** The same single input flips into an **ask-AI** surface: the question echoes and an answer renders inline in the body ("Be Curious. Why google it when you can just ask AI without leaving your keyboard?"). One surface, two behaviors.

### §4.2 The transferable UX ideas

1. **One input that *adapts*, never a new screen.** Filter → command → AI answer → grid, all in the same window. (561's thesis, shipped.)
2. **List + Detail + Metadata is a complete, reusable result grammar.** icon·title·subtitle·accessories on the left; markdown + structured metadata on the right. Highly relevant to a *file* result (path, type, modified date, size, snippet, owner).
3. **The Action Panel solves the "many actions per result" problem.** Primary action on ↵; everything else behind ⌘K with discoverable shortcuts. A file result has many actions (open, reveal, copy path, open-with, summarize, ask-about) — this is the canonical pattern for that.
4. **Keyboard-first is non-negotiable and fully specified.** Open via hotkey, type immediately, arrow to navigate, ↵ to act, Esc to close, ⌘K for actions, per-action shortcuts. The command-palette literature (Appendix A) stresses the hard part is the *non-happy paths* (empty/loading/error, focus management, narrow widths) — Raycast nails all of them.
5. **Always-visible shortcut hints.** The footer continuously teaches the keyboard model (↵, ⌘K) — discoverability without clutter.

---

## §5 Glean — neural search over *your own* documents

*Inspection fidelity: product-page hero (the real product UI as a marketing still, zoomed and read in detail) + the official user-guide search-operator model. Live app gated behind a corporate tenant.*

### §5.1 Anatomy observed

Glean is the closest functional twin to JustSearch: **personalized, permission-trimmed neural search over a heterogeneous personal/organizational corpus**, with an AI answer on top. Its results window (observed query: "company vision"):

- **Query box (top)** with a clear (×) affordance, and immediately below it a **row of filter chips/dropdowns**: **`Anytime ▾`** (date), **`Who from ▾`** (person), **`What type ▾`** (document type), **`My history`** (toggle). These are the headline facets, surfaced *before* you touch the results.
- **Results column (left): source-native, heterogeneous cards.** A single ranked list mixing document types from many sources, each rendered close to its native shape:
  - **Source icon + title (link)**, then a **metadata line**: author **avatar + name** · **"Updated 2hrs ago"** (recency) · **folder/space** ("Company").
  - A **snippet with the query terms bolded**.
  - **"Similar results 2 >"** — near-duplicate collapsing so one logical doc doesn't flood the list.
  - **Heterogeneous item types in one list**: a Google Doc, a **Slack message** ("Tony · #company-plans · Sent 20min ago · 3 reactions"), etc. — each result *looks like what it is*.
  - **Inline entity modules**: a **"People who write about this topic"** card row (person cards: avatar, name, title, location) woven into the document results — the **people/entity result type** as a first-class peer of documents.
- **Right rail: the answer entry + the source facets.**
  - A **"New answer"** button — the explicit entry point to the **AI-generated answer** (Glean keeps search and the synthesized answer as *adjacent, user-invoked* surfaces, where Perplexity makes the answer the default).
  - **"Found 24k results"** + a filter funnel.
  - A **datasource facet list with live counts**: `All 24k`, `Confluence 323`, `Slack 53`, `Dropbox 9`, `Github 51`, `Notion 235`, `Stack Overflow 900` — each with its app icon. This is **vertical/source navigation as a faceted sidebar**.

### §5.2 The operator model (from the user guide)

Glean exposes a **dual filtering model** — *type operators inline* **or** *click facets after results*, and the two are equivalent:
- `updated:yesterday` (recency), `from:me` / `from:<person>`, `type:<doctype>`, `my:history`, `app:<app>`.
- **Quotes** force exact terms/phrases; adjacent quoted words require order + adjacency.
- **Dynamically suggested filters within an app**: once narrowed to one source, the facets *repopulate from the actual results* (e.g. Salesforce custom fields like `status:"closed won" vertical:"xyz"`). Facets are **projected from the result set**, not a static list.
- **Plain-language fallback** ("product folder", trailing "pptx"/"pdf") for users who don't know operators.
- **Personalization + permission-trimming**: results are ranked per the user's role/history, and a shared query URL re-runs *personalized and access-checked* for the recipient — "they won't see content they don't have access to, even if you do."

### §5.3 The transferable UX ideas

1. **Federated results are heterogeneous and source-native.** A file, a chat message, and a person are *different result shapes in one ranked list*. JustSearch indexes many file types (PDF, Word, images, …) — the lesson is to render each result close to its type, not as identical rows.
2. **Faceting is dual and projected.** Type operators inline **and** clickable facets, equivalent and interchangeable; facet *values* (and counts) are computed from the current result set. The datasource rail with counts is an at-a-glance map of "where the matches live."
3. **Recency + authorship + location are part of result identity**, not afterthoughts (avatar, "updated 2h ago", folder). For local files this is modified-time, path, and type.
4. **Near-duplicate collapsing** ("Similar results") keeps a noisy corpus legible.
5. **The AI answer is an explicit, adjacent surface** ("New answer") rather than the default — a deliberately *different* choice from Perplexity, and arguably the right one when the user often wants the *document*, not a paraphrase.
6. **Entity results (people/topics) as first-class modules** inside the document list.

---

## §6 Synthesis — the convergent patterns

Across three products built by different teams for different users, the search window converges on a small set of structural decisions. These are the durable, transferable findings:

1. **One adaptive surface, not a flotilla of screens.** All three keep a single window/thread and *transform* it (zero → results → answer → detail → grid). The query box is persistent and never navigates away. (≙ tempdoc 561.)
2. **Answer and evidence are two views of one result.** Perplexity (`Answer`|`Links` tabs), Glean ("New answer" beside the result list). The synthesized answer and the ranked retrieval list coexist; the only difference is *which is default*. JustSearch, with both hybrid retrieval and RAG, lands squarely in this design space.
3. **Provenance is first-class and structural.** Sources pill, hoverable citation snippets, full source list, per-result source/recency/author. The answer is never a free-floating claim — it is anchored to inspectable evidence. (Search-side analogue of 565's evidence rendering.)
4. **Result-item anatomy is rich and typed.** icon · title · snippet(with **bolded query terms**) · typed metadata (source, recency, author, type) · per-item actions. Raycast's List+Detail+Metadata and Glean's source-native cards are two expressions of the same grammar.
5. **Filtering is dual: inline operators ⟷ clickable facets, projected from results.** Power users type (`type:`, `from:`, `updated:`); everyone else clicks chips/facets; the two are equivalent; facet values are computed from the live result set.
6. **Keyboard-first is the substrate, not a feature.** Open-on-hotkey, type-immediately, arrow/↵/Esc, ⌘K action panel, always-visible shortcut hints. The hard part (per the literature) is the *non-happy paths* — focus management, empty/loading/error, narrow widths.
7. **Latency is hidden by progressive disclosure**, not spinners — show intermediate progress / stream, reveal detail on curiosity, skeletonize.
8. **Zero/empty states do work.** A zero state that says "type here" + suggestions/recents; an empty-results state that *never dead-ends* — it offers reformulations, related scopes, or query tips. (67% of users abandon on a bare zero-results screen — Appendix A.)
9. **Sub-100ms perceived response** for as-you-type filtering is the bar for "instant."

## §7 Where they deliberately diverge (and the trade-off)

- **Answer-default (Perplexity) vs result-default with answer-on-demand (Glean).** Perplexity optimizes "give me the synthesized answer"; Glean optimizes "find me *the document*," treating the AI answer as an adjacent, explicit step. The right default depends on whether the user mostly wants *to know* or *to retrieve*. JustSearch serves both — which argues for a *toggle/peer-tab*, not a hardcoded default.
- **Conversational thread (Perplexity) vs stateless ranked list (Glean/Raycast).** Follow-ups + persistent composer (Perplexity) vs each query standing alone (Raycast/Glean). A file-search window leans more stateless; a RAG/chat surface leans conversational. JustSearch already has both a search surface and a unified-chat surface — the divergence maps onto that existing split.
- **Web-shaped (Perplexity) vs app/launcher-shaped (Raycast) vs enterprise-results-page (Glean).** JustSearch is a *desktop window over local files* — closest to **Raycast's launcher shape** for the window chrome and keyboard model, **Glean's results grammar** for the federated/heterogeneous file results, and **Perplexity's answer surface** for RAG.

## §8 Current JustSearch search window — measured live baseline (2026-06-09)

A live, **read-only** inspection of JustSearch's real search surface (`#justsearch://surface/core.search-surface`) against a running dev stack (UI :5173 / API :57871) with a **real index** — 5 markdown docs (JustSearch's own help corpus), worker IDLE/healthy, hybrid BM25+ANN enabled, LLM online (Meta-Llama-3.1-8B-Instruct). Inspected read-only: no ingest, no stack takeover (the stack was owned by the `565-grounding-hardening` worktree). Fidelity: **LIVE** for everything below except the answer/RAG *generation* (the `Answer`/`Ask` tabs were confirmed present but not triggered, to avoid loading the shared inference server). **Theme note:** the live skin was the **Sepia** built-in palette, so the §8.3 visual findings are theme-specific, not the product's fixed identity.

### §8.1 Functional anatomy — what already exists

- **Zero state** — one `Search files… (Esc to clear)` input; a `Modified: After / Before` date-range filter row; centered "Type to search across all indexed files"; a status bar (`● CONN · 5 docs · 61.3 KB · Online — Meta-Llama-3.1-8B-Instruct`); a left icon rail (search active, library/add, settings, chat, tree, docs, history, …).
- **Results state** — query reflected to the URL (`?query=…`); a `N results · Tms` counter — observed **"5 results · 1ms"**, well inside the sub-100ms "instant" bar. Each result row = **title (filename) · full file path (monospace) · snippet with query terms highlighted**. A `MD · JSON` toggle sits on the results header.
- **Per-result explainability** — a `▶ Why this result?` disclosure expands to the **hybrid fusion signals**: observed `sparse-retrieval #3 · 0.18` and `cross-encoder · 0.39`, plus an **`Explain in words`** button (LLM-generated rationale).
- **Detail pane** — clicking a result opens a right pane (file content) with header tabs **`Preview · Context · Answer · Ask`**: the RAG/answer surface is integrated *per-document* into the search window — the answer-beside-results model is already built.
- **Graceful degradation** — a live banner: "⚠ Semantic search degraded. Showing keyword results; relevance ranking may be reduced." The semantic/ANN tier had degraded and the surface fell back to BM25 *while explaining the state* — a textbook never-dead-end signal.

### §8.2 Scorecard against the field — present / gap / AHEAD

The §6 convergent patterns mapped onto the live surface:

| Convergent pattern (§6) | JustSearch today | Verdict |
|---|---|---|
| One adaptive surface | search ↔ detail-pane tabs in one window | ✅ present |
| Answer + evidence as two views of one result | `Preview/Context/Answer/Ask` per document | ✅ present (structurally) |
| Sub-100ms instant feedback | "5 results · 1ms" | ✅ present |
| Query-term highlighting | highlighted in snippets | ✅ present |
| Empty/error state does work | degraded-semantic banner; "type to search" | ◧ partial (no zero-state recents/suggestions) |
| **Per-result ranking rationale** | `Why this result?` + fusion scores + `Explain in words` | 🏆 **AHEAD — none of the three competitors expose this** |
| Rich, typed result-item metadata | title + path + snippet only (no type/source icon, recency, size, author) | ⚠️ gap |
| Dual filtering (operators ⟷ projected facets + counts) | only a `Modified` date range | ⚠️ gap (the Glean axis) |
| Provenance pill / source list on the answer | `Context` tab exists; no "N sources" pill / citation surface confirmed | ◧ partial |
| Per-result Action Panel (⌘K) | not observed | ⚠️ gap |
| Near-duplicate collapsing | not observed | ⚠️ gap |
| Latency hidden by streaming / progressive disclosure | not measured (answer not triggered) | ❔ unverified |

**The headline finding.** JustSearch's search window is **structurally further along than a greenfield reading of this doc would assume.** It already implements the answer-beside-results model *and it leads the entire competitive set on per-result explainability*: the `Why this result?` fusion-signal display is precisely a projection of the canonical **SearchTrace** (553/549/551). The leaders *synthesize and cite*; **none of them open the black box of *why this rank*.** That is a genuine, defensible differentiator and it is JustSearch's to own — the inversion of the usual "we're behind, catch up" framing.

### §8.3 Visual analysis (theme = Sepia; theme-specific, not fixed identity)

The live skin is a **warm, near-monochrome "parchment / reading-room"** aesthetic:

- **Palette** — cream/parchment background, dark-brown body text (not pure black); the only accent is a muted amber (degraded-banner border, dev pills). Effectively single-hue, accent-light.
- **Typography** — a **serif** body (titles, snippets, labels) with **monospace** for file paths and the preview pane. A deliberate document/library character, distinct from the genre norm.
- **Density** — comfortable, web-results-like (not Raycast's dense launcher rows).
- **Affordances — flat (the main visual weakness).** Result titles are **bold text, not styled as links** (no link colour/underline — they don't read as clickable); the **query-term highlight is very low-contrast** (a faint wash barely separating from the background).
- **Polish gap** — the `Preview` pane renders **raw, unrendered markdown** in monospace, not formatted content.

Against the three leaders' visual languages:

| Dimension | Perplexity | Raycast | Glean | JustSearch (Sepia) |
|---|---|---|---|---|
| Base | near-black, minimal | dark, translucent, floating | light/white, enterprise | warm cream/parchment |
| Contrast | very high | high | moderate-high | **moderate → low** |
| Accent | teal | coral/red | blue | **amber, barely used** |
| Type | clean sans | crisp native sans | neutral SaaS sans | **serif + monospace** |
| Title/link affordance | clear | clear (selection + ↵) | blue titles, obvious | **bold only — not obviously clickable** |
| Content render | rendered | rendered preview + metadata | source-native cards | **raw markdown** |
| Overall feel | focused answer engine | premium native tool | professional enterprise | calm reading-room — distinctive but **less crisp / less branded** |

**The key visual insight.** The affordance/contrast weaknesses are **not a search-surface defect — they are a theme / role-authority matter (557/558/567).** "Titles must read as links" and "matched terms must clear a contrast floor against their background" are exactly the *semantic-role + contrast-floor* problems 558 solved for colour pairs. The fix is for the search surface to **consume contrast-floored, accent-bearing semantic roles** (a link-role, a highlight-role, a selected-role) so it is legible and affordance-clear in **every** theme — sepia included — rather than to hardcode colours on the surface. This is the search-side instance of the 558/567 producer→consumer contract: the surface declares *roles*; the theme authority derives the *legible colours*.

---

## §9 Implications for JustSearch, reframed against the live baseline (options, not commitments)

The pre-inspection draft of this section read greenfield. The live baseline (§8) re-sorts the implications into four honest buckets. None are committed here; **all are expressible as *projections* under the 569 spine, not bespoke UI** (and the baseline proves the spine is already partly realized here — the explainability display *is* a SearchTrace projection).

**A. Protect & extend the one lead — don't lose it.**
- The `Why this result?` fusion-signal display is a real differentiator and a direct SearchTrace projection. Extend it: surface the **dense/vector** signal alongside sparse + cross-encoder; make `Explain in words` the Perplexity-grade natural-language provenance; keep it a *projection* of 553/549/551, never a second copy of the ranking logic. This is the asset to build the search window's identity around.

**B. Close the result-grammar + faceting gap (the Glean axis — the biggest delta).**
- **Enrich the result item** beyond title+path+snippet: type/source icon · recency · size · (where known) author. Raycast's List+Detail+Metadata is the template; project it through 559's `SurfaceFactory`.
- Add **dual filtering**: inline operators (`type:pdf`, `modified:<7d`, `path:`) ⟷ clickable facets with counts, **projected from the SearchTrace** — and the projection source already exists (the live `debug_state` exposes `search.facets: { enabled:true, fields:[mime, language] }`). Facets are a projection of the trace, not a second authority.
- A per-result **Action Panel (⌘K)**: open / reveal-in-folder / copy-path / open-with / "ask about this".

**C. Finish the answer / provenance surface (structurally present — polish it).**
- The `Answer`/`Context` tabs already exist; what's missing is the **citation surface** — a "sources" affordance + inline citations that open the **local** file at the matched offset (the search-side analogue of 565, and a real differentiator: *local* citations the user can actually open). Stream the answer and skeletonize the list (latency hiding) — unverified in §8, worth confirming and, if absent, adding.

**D. Fix the visual affordance/contrast via the role authority, NOT the surface (557/558/567).**
- Make titles consume a **link-role**, the highlight a **contrast-floored highlight-role**, selection a **selected-role** — so legibility and "this is clickable" hold in *every* theme, sepia included. Render markdown in `Preview` instead of raw source. Seed the zero state with **recents/suggestions**; ensure the empty-results state offers **query relaxation** (drop a filter, phrase→terms, exact→semantic) — and note that JustSearch *being hybrid* makes "no keyword matches — showing semantic matches" a uniquely natural recovery affordance (the degraded banner is the seed of exactly this pattern).

**Crucially (per the 569 spine):** unchanged from the original conclusion — every item above is a declaration the 559 engine renders and the SearchTrace feeds. This doc says *what* the search window should express; 569 says *how*, without forking the projection authority. What the baseline adds is the correction that **this is mostly *finishing and sharpening* a surface that already has the bones (and one lead), not building a new one.**

## §10 What to validate next (open questions)

*Competitive / design questions:*
- **Answer-default vs result-default for JustSearch's actual users** — Perplexity and Glean chose oppositely. Needs a real opinion/usability call, not a guess. (Note: the live baseline already chose *result-default with answer-in-a-tab*, the Glean lane — is that the intended default?)
- **How much of Raycast's Action Panel model fits a file result** — which actions are primary (open?) vs ⌘K-deep.
- **Facets-from-SearchTrace feasibility** — the live `debug_state` exposes `facets.fields:[mime, language]`; does the trace carry enough to project type/source/recency facet *counts* cheaply, and surface them as a rail? (Ties to 553/549/551.)
- **Local-citation rendering** — the search-side evidence surface (cf. 565) for "this answer came from *these* files at *these* offsets." Does the `Context` tab already do part of this?

*Questions raised by the live baseline (§8):*
- **Was the "Semantic search degraded" banner firing for a real reason?** The live `debug_state` reported `embedding_ready:true` / `ai_ready:true`, yet the surface showed keyword-only fallback — likely the ANN-cache readiness threshold (`ann_cache_ready_percent:75`) rather than embeddings. Worth confirming the banner reflects true degradation and isn't a stale/false signal. *(Logged to observations.md as an out-of-scope finding, not investigated here.)*
- **Is the `Preview` pane's raw-markdown rendering intentional or a polish gap?**
- **Are result titles meant to be links?** They don't render as clickable — confirm whether this is a missing affordance (role) or a deliberate "click-row-not-title" interaction.
- **Live-inspect Glean and Raycast at higher fidelity** if a tenant / the native app becomes available (this study used the gated-product proxies noted in Appendix B).
- **Re-baseline against the default theme**, not just Sepia — §8.3's visual findings are theme-specific; confirm contrast/affordance in the default + high-contrast variants (the 558 cross-product question).

---

## Appendix A — Sources

**General search/answer UX**
- DesignRush, *6 Essential Search UX Best Practices* — visibility, autocomplete, sub-100ms response.
- LogRocket, *Designing better advanced search UIs*.
- UXPin, *Advanced Search UX (2026)*.

**Perplexity**
- LangChain, *AI Answer Engine Case Study: Perplexity Pro Search* — progress-disclosure, hoverable citations, "feed their curiosity," accountability-by-design. https://www.langchain.com/breakoutagents/perplexity
- Unusual, *Perplexity Platform Guide: Design for Citation-Forward Answers* — Answer > Sources > Follow-ups hierarchy; numbered footnote citations.
- NextLeap UX teardowns of Perplexity citations & follow-ups.

**Raycast**
- Raycast Developer API — *User Interface*, *List*, *Detail*, *Action Panel*, *Grid*, *Terminology* (Root Search, Command, Action Panel ⌘K, accessories, metadata). https://developers.raycast.com/api-reference/user-interface
- Raycast Manual — *Search Bar*, *Action Panel*.
- Medium / TechLila / Startupik launcher comparisons (Raycast vs Alfred vs Spotlight, 2025).

**Glean**
- Glean Docs, *Search in Glean* (operators: `updated`, `from`, `type`, `my:history`, `app:`, quotes, dynamic per-app filters, plain-language fallback, personalization + permission-trimming). https://docs.glean.com/user-guide/search/how-to-search-in-glean
- Glean Docs, *Advanced search filters in Glean*; Glean Developer, *Filtering Results* / *Datasource Filters* / *Sidebar Search*.
- Glean product page, *AI-powered workplace search* (the inspected results-UI hero). https://www.glean.com/product/workplace-search-ai
- Glean vs Perplexity comparisons (Tanka, ClickUp) — purpose split: explore-the-web vs retrieve-internal.

**Command palette / keyboard model**
- UX Patterns for Developers, *Command Palette Pattern* (anatomy, keyboard, ARIA, the non-happy-path warning). https://uxpatterns.dev/patterns/advanced/command-palette
- cmdk (Paco Coursey) — powers Linear, Raycast, Vercel.

**Empty / zero states**
- NN/g, *Designing Empty States in Complex Applications*.
- Pencil & Paper / Eleken / Toptal — empty-state categories (informational / action-oriented / celebratory); "67% abandon on bare zero-results"; never dead-end a search.

## Appendix B — Inspection fidelity (honest labelling)

| Product | Window inspected | Fidelity | Caveat |
|---|---|---|---|
| **Perplexity** | zero state, answer view, sources/Links view, follow-ups, persistent composer | **LIVE** — real query run logged-out, all states observed directly | Logged-out tier may suppress some focus-mode UI; otherwise direct. |
| **Glean** | results page (query box, filter chips, source-native cards, people module, datasource facet rail, "New answer") | **PRODUCT-PAGE HERO** (real UI as a marketing still, zoomed & read) **+ official user-guide operator model** | Live app gated behind a corporate tenant; the still is a curated example, not an arbitrary live query. Operator model is from current docs (updated 2026-06-01). |
| **Raycast** | Root Search concept, List+Detail+Metadata, Grid, AI-answer mode, footer action bar (⌘K) | **OFFICIAL SITE PRODUCT IMAGERY + developer API docs** | Native macOS/Windows app, not browsable in Chrome; states observed via the site's product animation + authoritative dev docs. |
| **JustSearch** (§8) | zero state, results state, per-result `Why this result?`, detail pane (`Preview/Context/Answer/Ask`), the Sepia theme | **LIVE** — running dev stack + real 5-doc index, real query, all states observed directly; **except** the `Answer`/`Ask` RAG *generation* (confirmed present, not triggered) | Read-only; stack owned by another worktree (no ingest/takeover). Tiny 5-doc corpus. Theme = Sepia (visual findings theme-specific). |

The findings in §6 (the convergent patterns) hold across all three competitors regardless of fidelity, because each pattern was corroborated by at least one *live* observation (Perplexity) and the authoritative docs of the other two. §3/§4/§5 specifics are reliable to the fidelity noted above. §8 (the JustSearch baseline) is **direct live observation** except where explicitly marked unverified (RAG generation, streaming, action panel).

---

# Part II — The correct design (theory)

> **Bridge from Part I.** Part I is the empirical grounding: what the best search windows do (§3–§7), what JustSearch's does today (§8), and a first, *greenfield* reading of the implications (§9). **Part II supersedes §9.** It is not a list of options; it is the correct long-term *structure*, in the 557/559/567/569 design-theory genre — feasibility, phasing, and implementation deliberately disregarded, major rewrites in scope. The single claim, earned by the §13 investigation: the search window's deficits are not missing features but **missing projections** — the window is the last hand-authored fork of a projection spine the codebase already runs everywhere else. The correct design makes it a projection, and makes the fork unrepresentable.

## §11 The defect class — the search window is a *second authority*, not an *incomplete feature*

Part I's §8.2 scorecard reads as a feature checklist (thin metadata, no facets, raw-markdown preview, flat affordances). That framing is wrong at the root. Re-examined structurally, **every deficit is the same defect**: the search window is a **second presentation authority** for concepts that *already have one canonical authority elsewhere in the codebase*, and it either re-models them or under-projects them.

| The search window today… | …re-models / under-projects this canonical authority |
|---|---|
| hand-authors its body in `SearchSurface.ts` (Lit `render()`) | the projection engine `SurfaceFactory` + the per-facet projectors (559) |
| renders a homogeneous title+path+snippet row | the `EvidenceItem` evidence projection (565) |
| hard-codes a 2-item right-click menu, bypassing `listContextActions()` | the operation/`Effect`/`ContextAction` substrate + `evaluateIntent` seam (543/550) |
| shows the raw query string | the `SearchTrace` query-understanding / correction / `effectiveMode` facets (553) |
| offers a date-only filter, no facets | the `facets` + `entityFacetVariants` already on `KnowledgeSearchResponse` (553) |
| (Answer tab) — no confirmed citation surface | the one grounded-answer authority + local-passage deep-link (565 §3.A/§4) |
| hardcodes appearance into a low-contrast, flat skin | semantic colour roles + the contrast-floor (557/558/567) |

This is exactly the class 553 named for execution, 559 for the rendered element, 561 for the interaction surface, and 565 for the agent's content: **a hand-authored fork that flattens or ignores the single authority.** 565 said it precisely about the agent path — *"the one window still renders divergent content authorities… the agent path is an impoverished fork of the RAG path."* **The search window is the same fork, at the retrieval altitude, and it is the last one.**

**The nine theoretical deficits (the prior analysis) reduce to two roots, and each root is a missing projection:**

- **Root 1 — the result is modelled as a *noun to read*, not a *verb to act on* or a *set to explore*.** (Subsumes: result-as-noun, type-blind rendering, opaque result set, unused personal signal, no action panel.) The result row is a passive display string; the leaders model a result as an object carrying its own action space (Raycast) inside a self-describing set (Glean). *Missing projections:* the evidence projection (the result's identity), the operation substrate (the result's verbs), the facet payload (the set's self-description).
- **Root 2 — the query/answer is modelled as a *literal string*, not an *intent* or an *accountable answer*.** (Subsumes: literal query, no reformulation/mode-legibility, single-shot not session, mis-allocated transparency, unverified latency model.) *Missing projections:* the SearchTrace query-understanding facet (the intent), the one interaction surface's mode model (the session), the grounded-answer authority (the accountable answer with local citations).

The deepest reading: **JustSearch did not build the wrong engine — it put a *retrieval* window on an engine that already models intent, evidence, and action, and exposed only the retrieval slice.** The fix is not to add features to the window; it is to delete the window's status as an authority and make it a projection.

## §12 Thesis — the search window as a projection of the canonical authorities (the spine at the search altitude)

569 states the master pattern the codebase runs at every altitude:

> **one authority → a typed declaration → a governed projection → the breaking/subordinate form made *unrepresentable* → a register/gate whose coverage itself projects from the authority's catalog.**

The correct design is that spine **at the search altitude**, stated in one move and one deeper move:

- **The move:** the search window stops being a hand-authored surface and becomes a **projection of the canonical records** — `SearchTrace` (execution), `Hit`/`ExcerptRegion`/`ContextCitation` (evidence), the operation catalog (verbs), the facet payload (the set), semantic roles (appearance). Every region (results, result item, query, facets, answer, the explain panel) is a *projected facet of one engine over those records*, never bespoke Lit. This is 559's diagnosis (*"the rendered element body is still hand-authored Lit"*) applied to `SearchSurface.ts`.
- **The deeper move:** **search is a *mode* of the one interaction surface (561), not a separate surface.** 561 already made "one window" true by construction with the `interaction-surface` gate, with **modes as axis-points** in `autonomy × grounding × persistence`. Retrieval, RAG-ask, and the agent loop are *the same continuum*: "ask" is search with the grounding axis at *documents* and the autonomy axis turned up. Keeping `core.search-surface` a separate RAIL authority is the **surface-tier fork** 561 names — search is its missing mode. Unifying it dissolves Root 2's "single-shot, siloed" deficit *structurally*, not by adding a follow-up box.

Stated as the spine:

> **The search window is a projection of one typed *search-surface declaration* — a mode of the one interaction surface — whose results, result-items, query-understanding, facets, actions, and grounded answer are governed projections of the canonical execution/evidence/operation/theme authorities. The breaking forms (a second search-result authority, a hand-authored result row, a hard-coded action set, a raw-string query UI, a free `(fg,bg)` result colour, a forked search surface) are *unrepresentable* by construction, and a `search-surface`/`evidence-surface` register+gate — coverage projecting from the catalog — keeps them so.**

## §13 What already exists — the extend-don't-invent ledger

The investigation (read-only, against the live tree) found the substrate is **overwhelmingly already built**; the search window is the consumer that doesn't subscribe. (This is why the correct design is "project, don't build.")

**Exists and is correct — the search window must project through it:**
- **Execution authority (553):** `SearchTrace` carries `effectiveMode` (hybrid/sparse/dense/splade), `Degradation` (+reasons), `Qpp`, and 12 closed `TraceStage`s — including **`query-understanding`, `expansion`, `correction`** (with `ms`, status, and `detail` = the corrected query). Per-`Hit` `HitStage` carries the rich score `detail`. Guarded by the shipped **`execution-surface` register+gate**.
- **The result-set self-description is already on the wire (553):** `KnowledgeSearchResponse` carries **`facets` (field→value→count)**, **`entityFacetVariants`** (NER), `queryUnderstanding`, `filterNormalization`, and per-`Hit` `matchSpans`/`excerptRegions`. The FE renders **none** of it (facets "deferred Phase 3").
- **Evidence authority (565):** `evidenceProjection.ts` (`EvidenceItem`/`EvidenceScore`/`evidenceTier`/`filenameOf`) + the **local-passage deep-link** (`citation-select → highlightCitation/openExternal` opens the exact local file at the exact line). A search hit *is* an `EvidenceItem`.
- **Verb-space substrate (543/550):** the closed `Effect` union (incl. `invoke-operation`), the Effect Journal (inverse/undo/persist), PendingEffect, the Autonomy Dial, `evaluateIntent→IntentVerdict` + the `(SourceTier × RiskTier)` lattice, 40+ operations (incl. file ops), `/api/registry/operations` + `OperationClient.invoke`. **And the search-result hook already exists**: the `'search-result'` addressable + `searchResultProjector` + `ContextActionRegistry` + `listContextActions()` — used elsewhere, **bypassed** by `SearchSurface`'s hard-coded menu.
- **Projection engine (559):** `SurfaceFactory` (mints a surface element from its declaration), `createRegistry<T>()`, the authorities-as-facets model, the frame/contents cut.
- **One interaction surface (561):** the surface declaration + **modes-as-axis-points** + the `interaction-surface` gate. The mode catalog is the slot search plugs into.
- **Appearance authority (557/558/567):** semantic colour roles + the contrast-floor `deriveForeground`; `present()` Display labels; the Observed-state tri-state (so "degraded → keyword-only" is honest by construction); `statusTone` (565). Zero-state recents already exist (`pinnedSearchState`, `recordSearchRun`).

**The genuine gaps (small, and named):**
1. **Chunk-identity for answer-grounding** — `Hit` lacks `parentDocId`/`chunkIndex`, so the RAG citation matcher isn't directly reusable on agent/search hits (565 §10). A *named decision* (thread chunk-identity through the search path vs lexical-only vs LLM-emitted markers), not a free projection.
2. **Type-aware result minting** — the projection engine must mint result items *per type* (the type data exists on `Hit`; the renderer ignores it).
3. **A facet-projection region** — project the already-emitted `facets`/`entityFacetVariants` into dual filtering (none exists in the FE).
4. **The search-as-mode move** — promoting search into the 561 mode catalog and retiring `core.search-surface` as a standalone authority.

**Net:** four targeted structural pieces over a fully-built substrate. The correct design is *finishing the projection spine on the one surface where it is still hand-authored*, not a greenfield build.

## §14 The correct structure — the moves

Each move generalizes an authority that already exists to the search altitude. Together they make the search window a projection.

> **Build-status note (see §18 for the full de-risk audit).** A 2026-06-09 de-risk pass verified each move against the live tree + experiments. In short: **F** is a pure FE-render extension of a merged authority (HIGH); **A/D/G/H** extend real built primitives (MEDIUM-HIGH); **C** is sound but must be *two-layer* (platform-capability vs operation seam); **B** is **greenfield** (559's element engine is unbuilt — use the §18 D3 interim); **E** is a **backend gap** (facets are not emitted on the wire today). Read §18 before implementation planning.

### Move A — Search is a *mode* of the one interaction surface, not a separate surface
*(extend 561 modes-as-axis-points + the `interaction-surface` gate.)*
Promote search into the declared mode catalog as point(s) in `autonomy × grounding × persistence`: keyword / semantic / hybrid retrieval at low autonomy + document grounding; "ask" and the agent loop as the *same continuum* with autonomy turned up. Retire `core.search-surface` as a standalone RAIL authority; it becomes a mode/panel re-mount of the one surface (`consumesProjection`). **Result:** retrieval and dialogue are one surface; "refine in place / now only PDFs / ask about these" is the same surface changing mode, not a second window. Root-2's single-shot/siloed deficit is dissolved structurally.

### Move B — The result is a projected `EvidenceItem`, rendered source-native
*(extend 565 `evidenceProjection` + 559 `SurfaceFactory`.)*
A search hit *is* an `EvidenceItem`; the result list is a projection of the evidence authority, and the result *item* is **minted per type** by the engine (a PDF row, a code row with line context, an image row with a thumbnail, a message row) — co-projecting type, recency, location, and the score facet from the canonical records. **The homogeneous hand-authored row becomes unrepresentable** (there is no `render()` to hand-write; the engine mints from the declaration). Dissolves type-blindness and folds recency/location into result identity.

### Move C — The result carries its verb-space through the existing action seam
*(extend 543 `Effect`/`operationRef` + 550 `evaluateIntent` + the already-wired `ContextActionRegistry`; the 569 Move-8 form.)*
A result's actions (open, reveal-in-folder, copy-path, open-with, send-to-chat, summarize, ask-about) are **operation references** resolved through the catalog and gated by the one `evaluateIntent → IntentVerdict` seam — surfaced as a per-result Action Panel (the keyboard-first ⌘K affordance) with operability co-projected (557/559). `SearchSurface` calls `listContextActions(addressable)` instead of hard-coding two actions; **the hard-coded menu becomes unrepresentable** (actions are projected from the operation catalog, not authored on the surface). Dissolves result-as-noun and the missing action panel — and because actions are gated refs, a result action *cannot escalate privilege or fork truth* (569 Move 5/8).

### Move D — The query is a projection of the `SearchTrace` understanding facets
*(extend 553.)*
The query region projects the trace's `query-understanding` / `correction` / `expansion` / `effectiveMode` facets: a "did you mean / searched for *X*" correction line (the data is on the `correction` stage today, read only by the agent tool), mode legibility ("hybrid" vs "keyword-only" shown *always*, not only on failure), and expansion transparency. **The raw-literal-string UI becomes a projected-intent UI.** Dissolves Root-2's literal-query deficit and turns the neural value visible instead of invisible-until-it-breaks.

### Move E — The result set is self-describing: dual filtering as a projection of the emitted facets
*(extend 553 `facets`/`entityFacetVariants` + 557 Observed-state.)*
Project the already-emitted facet payload into **dual filtering**: inline operators (`type:pdf`, `modified:<7d`, `path:`) ⟷ clickable facets with counts, equivalent and interchangeable (Glean's model), with facet *values* projected from the live result set. The date-only filter becomes the *floor* of a facet projection, not the model. **A static, hand-authored filter set becomes unrepresentable** (facets are a projection of the response). Dissolves the opaque-result-set deficit.

### Move F — The answer is a projection of the one grounded-answer authority, with local-passage deep-link
*(extend 565 §3.A/§4.)*
The Answer/Ask mode projects through the **one grounded-answer authority**: the answer carries its provenance set, the evidence renders through `evidenceProjection`, and every citation opens the **exact local file at the exact line** via the existing deep-link. This **re-allocates transparency to where trust is at stake** (Root 2 / §8.2's mis-allocation): per-result "why this *rank*" (the existing lead, an execution projection) is *kept*, and "where did this *claim* come from" (an evidence projection) is *added*. **A grounded answer rendered without its evidence projection becomes unrepresentable** (the 565 evidence-surface invariant). Names the §13 chunk-identity decision as the one substrate piece, not a free projection.

### Move G — The invariant facets are co-projected, not authored (affordance, contrast, liveness, latency, cold-start)
*(extend 557 Display/Observed-state + 558/567 roles + 565 statusTone + the 559 engine.)*
The legibility/affordance facts are *engine outputs*, not surface choices: result titles consume a **link-role** (so "this is clickable" holds in every theme), the query highlight a **contrast-floored highlight-role** (so matched terms clear AA against any background — the sepia low-contrast finding), status a **statusTone**. Liveness ("retrieval degraded → keyword-only") is projected from Observed-state *by construction*. Latency is hidden by projecting the **`SearchTrace` stages as progressive-disclosure steps** (the 12 stages with `ms`/status are *already* a Perplexity-style plan display, unprojected), and the zero state projects **recents/pins** (already stored). **"White-on-bright result text," "keyboard-dead result," "blank spinner," and "inert zero state" become unrepresentable** — the author has no field for the foreground colour, the keyboard wiring, or the loading chrome; the engine derives them. Dissolves the visual + cold-start deficits.

### Move H — The prevention: a `search-surface` / `evidence-surface` register + gate, coverage projecting from the catalog
*(extend the 553 `execution-surface` / 550 `operation-surface` / 561 `interaction-surface` / 565 evidence-surface gate family.)*
A register + discipline-gate sibling makes the fork unrepresentable at build time: a *second* search-result authority, a result rendered without its evidence+verb projection, a hand-authored homogeneous result row, a raw-`(fg,bg)` result colour, or a *forked* search surface (a second `audience:USER` retrieval surface) **fails the build** — and the gate's coverage **projects from the search-surface/mode catalog**, so a *future* search mode or result type is covered automatically ("the registry enumerates from the authority," 557 §5.2). This is the long-term issue-prevention the whole exercise is for: not "remember to project," but "the unprojected form cannot be written."

## §15 The prevention ladder — why the defect class cannot recur

The 557/569 four-rung ladder (1 Collapse · 2 Unrepresentable-by-type · 3 Generate · 4 Gate) applied per breaking form. The point: almost every row lands on **rung 2** — the gate (Move H) is the floor, not the mechanism.

| Breaking form | Made impossible by | Strongest rung |
|---|---|---|
| A second search-result presentation authority / hand-authored Lit body | Move A+B — search is a projected mode; the result list/item are minted by the engine; no `render()` to fork | **2 — unrepresentable** |
| Homogeneous, type-blind result row | Move B — the item is minted per type from `Hit` + the evidence projection | **2/3 — unrepresentable / generated** |
| Hard-coded result action set | Move C — actions are operation refs projected from the catalog, gated by `evaluateIntent` | **2 — unrepresentable** |
| Privilege-escalating result action | Move C — every action is a gated ref through the trust seam (550) | **2 — unrepresentable** |
| Raw-literal query UI (no intent) | Move D — the query region projects the SearchTrace understanding facets | **2 — unrepresentable** |
| Static, hand-authored filter set | Move E — facets are a projection of the emitted `facets`/`entityFacetVariants` | **2 — unrepresentable** |
| Grounded answer with no verifiable citations | Move F — the answer projects the one grounded-answer authority + deep-link | **2 — unrepresentable** |
| White-on-bright result text / keyboard-dead result / inert zero state | Move G — affordance/contrast/operability/liveness/cold-start are co-projected facets | **2 — unrepresentable** |
| A *forked* second retrieval surface | Move A+H — search is a mode of the one surface; the gate forbids a second | **2/4 — unrepresentable / gate** |
| A schema-valid but perf-pathological / aesthetically-broken projection | Move H — the register/gate + measured UX-audit discipline | **4 — gate (the floor)** |

**Why the class cannot recur (the shape, from 553/565):** the search window is one projection of canonical records, in one place (a mode of the one surface), with every invariant facet co-projected and the breaking forms outside the authorable grammar — exactly the *single-authority + unrepresentable subordinate form* the codebase has proven at every other altitude, now applied to retrieval.

## §16 What must NOT change, and the honest limits

**Must not change (reuse, never fork):** the canonical records (`SearchTrace`, `Hit`/`ExcerptRegion`/`ContextCitation`, the facet payload) are *projected, not re-modelled*; the operation/Effect substrate, the evidence projection, the one interaction surface, and the colour-role authority are *extended, never paralleled*. The existing **lead** — per-result rank explainability (`Why this result?` + the fusion-signal `HitStage` projection) — is *kept and extended* (Move F surfaces the dense signal too), never dropped.

**Honest limits (per 553 §5 / 565 §6/§10):**
- **Gates guarantee the *declared* class only.** Move H makes the *declared* breaking forms unrepresentable (~100% in scope); **import-invisible re-models stay discipline-tier** (a clever surface can still re-model around the gate — the 553/565 caveat). Prevention is asymptotic, not total.
- **Presentation tuning stays judgment.** Density, prominence, the answer-default-vs-result-default choice (§7), and "which actions are primary" are *not* gate-decidable; they remain under the measured, honor-system UX-audit discipline (the `ux-audit-closure` gate was retired in 563).
- **The chunk-identity gap (Move F) is a named substrate decision, not a free projection** (565 §10): without `parentDocId`/`chunkIndex` on `Hit`, the authoritative citation matcher can't run on search hits — pick the identity-threading vs lexical-only vs LLM-marker path explicitly; the silent-failure mode (empty ids → zero matches → green tests → dead feature) must be guarded.
- **Personalization/ranking (prior Thesis 7) is partly outside the projection frame.** *Presenting* recency/access as result identity is a projection (Move B); *ranking by* personal frecency is a backend retrieval-quality concern that belongs to the search-quality register, not this presentation theory — named here so it isn't silently assumed solved.
- **Streaming/latency for the answer path is unverified** (§8 did not trigger generation); Move G assumes the SearchTrace-stages-as-progress projection is added if absent.

## §17 The differentiator, restated as two projections

The correct design yields a transparency pairing **no competitor has**, and both halves are *projections of canonical records*, not bespoke features:
- **"Why this *rank*"** — per-result fusion-signal explainability (the existing lead, a projection of `SearchTrace`/`HitStage`). Perplexity/Glean/Raycast never open this box.
- **"Where did this *claim* come from"** — local-passage citations that open the exact file at the exact line (a projection of the 565 grounded-answer authority + deep-link). NotebookLM/Perplexity can only deep-link a URL; **we own the corpus**.

Together: a search window that is transparent about *both* how it ranked and why its answer is trustworthy — achieved not by building two features but by projecting two records the system already produces. That is the structural payoff of treating the search window as the last fork of the spine, and closing it.

---

## §18 De-risk pass (2026-06-09) — confidence audit before any implementation

A read-only de-risk pass (in the 561 §8 / 565 §10 mould) run *before* committing to implementation, to separate **built primitives** from **unbuilt integration structures**, observe **unobserved data**, and surface **design-fit** calls. Method: targeted code probes (against the live tree) + live experiments on a **throwaway dev stack** (`tmp/devdata-570-derisk`, separate data dir, taken over with approval, since cleaned — the `565-grounding-hardening` worktree's on-disk index was never touched). The headline correction: the Part II framing *"the substrate is overwhelmingly already built; just project"* was **partly an overstatement** — it conflated built *primitives* with unbuilt *integration structures*, and assumed data flows that don't. The *direction* survives; several moves' *scope* is larger than stated.

### §18.1 Per-move build-status + confidence

| Move | Verdict (evidence) | Confidence |
|---|---|---|
| **A — search as a mode of the one surface** | The `interaction-surface` gate + register + one-window body (`UnifiedChatView`, `CONVERSATION_ZONES`) are **BUILT**; the mode catalog (`CORE_USER_INTERACTION_SHAPES`) is **real but flat** (no axes, no retrieval shape). Folding search in is **gate-compatible only if `core.search-surface` is retired** (else the "multiple interaction surfaces" check fails). A results-list is structurally hostable as a new zone. | **MEDIUM** — bounded scope (add a retrieval shape + retire the surface) + a design-fit call (D1). |
| **B — result as a projected EvidenceItem** | Type data is **real** (live: `file_kind` distinguishes `markdown` vs `code`). BUT 559's **element-body projection engine does not exist** (`SurfaceFactory` mints whole surfaces only; all bodies are hand-authored Lit). **Move B as written is GREENFIELD.** | **LOW as-written → MEDIUM via D3** (lighter interim: a typed result-view-model + one type-switching renderer, not the full 559 engine). |
| **C — result carries its verb-space** | Tauri `open_file`/`reveal_in_explorer` **exist + are FE-wired** (`platform.ts`); the `ContextActionRegistry`/`searchResultProjector` **exist but are bypassed** (SearchSurface hard-codes 2 actions). No *operation* bridges open/reveal. | **MEDIUM-HIGH with the two-layer fix (D2)** — simple actions via platform capability; only destructive/agent actions via the operation/verdict seam. Confirms the over-engineering risk. |
| **D — query as a projection of intent** | The `searchTrace` **is on the wire** with `effectiveMode`, `decisionKind`, `qpp`, 12 stages w/ status, **per-stage `reason`** (live: `dense-retrieval` skipped `"sparse-shortcut"`; `expansion` skipped `"AI_UNAVAILABLE"`), and a **`query-understanding` stage with intent** (live: `"INFORMATIONAL"`). BUT the top-level `queryUnderstanding` field is **empty/omitted**, and **`correction` was *skipped*** on a real typo. | **MEDIUM-HIGH** — project the **trace STAGE** (intent, mode, reason), *not* the empty `queryUnderstanding` field; "did you mean" is rarer than hoped. |
| **E — self-describing facets** | **Facets are NOT on the wire — even when explicitly requested** (live: `hasFacets:false` on a 9-hit query with a facet spec in the body; the `sparse_shortcut` path doesn't attach them, and the HTTP API doesn't surface the Java record's facet payload). | **LOW → MEDIUM** — **this is a BACKEND gap, not "just project"**: the search response must first *emit* facets before the FE can render them. |
| **F — answer as grounded-answer projection** | The grounded-answer authority is **BUILT-AND-MERGED** (evidenceProjection, the local-passage deep-link, `AgentDone.sources/citations`, the grounding gate). **P3 proved the search Answer tab's gap is PURELY FE-RENDER**: `/api/chat/agent`'s `done` event already carries `sources`+`citations` (`AgentController.toSsePayload`), and `InspectorPane.sendQuestion` reads only `finalResponse` and discards them. | **HIGH (raised)** — a genuine FE-wiring extension. (Live E3 not run — the throwaway stack had no chat-model pack; settled by code.) |
| **G — co-projected invariant facets** | `statusTone` + colour-role + contrast-floor machinery exist; recents (`pinnedSearchState`) exist; the trace stages are real progress data. BUT a **`link`/anchor role and a match-`highlight` role do NOT exist** in `ROLE_CATALOG`; the current snippet highlight is a **hardcoded** `--accent-tint-30` token. | **MEDIUM-HIGH** — two new roles must be **added** via the existing (well-trodden 558) role-catalog mechanism; everything else extends. |
| **H — the register/gate** | The discipline-gate kernel + the sibling `interaction-surface` enforcer are the **proven template** (read in full). | **HIGH.** |

### §18.2 Live experiment results (what the data actually carries)

- **`searchTrace` is the richest projectable record** and is reliably populated: `effectiveMode` (observed `TEXT`), `decisionKind` (`sparse_shortcut`), `qpp` (maxIdf/avgIctf/queryScope), `degradation` (vectorBlocked/hybridFallback/spladeExecuted — all false for a shortcut, i.e. it distinguishes a *decision* from a *degradation*), and 12 stages each with `status` + `reason` + (for query-understanding) an intent `detail`.
- **Semantic did not engage** on the throwaway stack: every query ran keyword-only (`effectiveMode:TEXT`, dense/splade skipped `"sparse-shortcut"`, expansion skipped `"AI_UNAVAILABLE"` because no chat-model pack). The trace *names why* per stage — exactly the legibility Move D/G want, and a reminder that "keyword-only" is the common live state to surface honestly.
- **Per-hit `HitStage.detail` (the rich numeric score breakdown) is empty even with `includeDetail:true`** → the "show the dense/vector signal" extension (§17/Move F) needs the detail tier actually emitted, not just rendered.
- **`facets`/`entityFacetVariants`/`queryUnderstanding` envelope fields are absent/empty** in real responses (confirmed via same-origin `fetch`; `api_call` does not allowlist `/api/knowledge/search`, and the MCP `search_query` strips the envelope).

### §18.3 Scope corrections (the surprises this pass caught)
1. **Move B is greenfield, not an extension** — 559's element-body projection engine is unbuilt. Reframe to the lighter interim (D3) or sequence behind 559.
2. **Move C is two-layer** — open/reveal/copy-path ride the existing **platform capability** (not an operation); only destructive/agent result-actions traverse the operation/verdict/Journal seam. Routing a trivial "open" through the full pipeline would be over-engineering (AHA).
3. **Move E is a backend gap** — facets must be *emitted* on the HTTP search response (and on the `sparse_shortcut` path) before any FE projection; today they are absent even when requested.
4. **Move D reads the trace STAGE, not the envelope field** — intent/mode/reason live on `searchTrace.stages`, while the top-level `queryUnderstanding` is empty; and "did you mean" correction is rarer than assumed (skipped on a real typo).

### §18.4 Named decisions (resolve before implementing)
- **D1 (Move A):** is a spatial results-list a legitimate *mode/panel of the conversation window*, or does search stay a distinct surface the gate explicitly allows? (Folding in *requires* retiring `core.search-surface`.)
- **D2 (Move C):** confirm the two-layer cut (platform-capability simple actions vs operation/verdict destructive/agent actions).
- **D3 (Move B):** build the 559 element-projection engine first, vs ship a lighter typed-result-view-model + one type-switching renderer now.
- **Chunk-identity (Move F, 565 §10):** scoped — the *agent answer* already carries `sources` with `parentDocId`+`chunkIndex` (so wiring the search Answer tab is FE-only); the gap is only for matching *raw result hits* to citations, if that is ever wanted.

### §18.5 Not verified here (residual)
- **E3 live answer path** — not run: the throwaway data dir had no chat-model pack and `ai_activate` failed (`"No chat model configured"`). Move F rests on **code evidence (P3)**, not a live click-through; a live verify needs a model-pack-equipped stack.
- **Whether semantic/hybrid engages for longer queries on a model-equipped stack** — every probe here ran keyword-only (no pack). The `sparse_shortcut` vs genuine-degradation distinction should be re-checked with AI active.

**Net confidence after the pass:** the *thesis* is **strengthened** (Move F is verifiably a pure FE-render fork of a merged authority; the trace is a rich, real projection source). The *scope* is **larger and better-bounded** than the body implied: **Move E needs backend work**, **Move B is greenfield (use the D3 interim)**, **Move C must stay two-layer**, and **Move D/G read the trace stages + need two new roles**. None of these are blockers; all are now explicit, which is the point of the pass. Implementation planning (separate, on request) should start from this §18, not the §14 body alone.
