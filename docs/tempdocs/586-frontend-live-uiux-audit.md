---
title: "586 — Frontend live UI/UX audit (shell-v0): functional, accessibility, and polish findings from a running-stack browser walkthrough. A live, in-browser pass over every navigation surface and both primary features (search + RAG chat) against a clean dev stack (worker ready, Qwen3.5-9B activated, 37 docs indexed). Catalogs what works, then ranks the rough edges (heavy-surface load behavior, accessibility labeling/focus, internal-ID leakage, missing i18n keys, shortcut/keycap bugs) with verbatim console/network evidence. Charter: document the observed findings, then root-cause each against the source before proposing fixes — observations are hypotheses until traced to code."
type: tempdocs
status: IMPLEMENTED + browser-validated (2026-06-15) — Phase 1 observations + Phase 2 root-cause + Phase 3 implementation all DONE. Shipped & live-verified against the running stack: P-1a (loading title), P-1b (LambdaMART wording), P-1c (relative advisory time), P-3 (truthful Help shortcuts), P-4 (2 i18n keys), P-5 (Settings gear icon), A-1 (3 nameless jf-controls labeled), F-2 (Simple-mode rail trim — hides System + Theme Editor, keeps AI Brain per user choice). F-1: friendly title shipped (round 1). The §8 gap-closing round (after a critical self-review) then completed the rest: skeleton + status-poll DEDUP (BrainSurface now reads inference/system from the shared aiStateStore — its documented single authority — fixing a latent null-clobber bug found en route), the P-3 + F-2 regression tests, and the production-bundle measurement (AI Brain chunk ready in ~361 ms in prod vs ~15-20 s dev → slow load is empirically DEV-ONLY). F-1(c) "soften Reconnecting" left untouched by design — the measurement shows the flip can't occur at a ~360 ms prod load. Re-classified not-bugs (A-2 focus ring, P-2 full-bleed deferral, P-5 tooltips, P-6 facets) untouched. typecheck clean · 3000 unit tests green · FE gates green · AI Brain live-validated · prod cold-load measured. No commit (per session policy)
created: 2026-06-15
updated: 2026-06-15
origin: live browser walkthrough (Claude-in-Chrome) of the running dev stack, 2026-06-15
---

# 586 — Frontend live UI/UX audit (shell-v0)

> **Purpose, stated carefully.** This is NOT a redesign mandate and NOT a list of opinions about
> taste. It is a record of what a live, end-to-end walkthrough of the *running* frontend actually
> did — captured with console/network evidence — followed by a **root-cause pass that traces each
> finding to source** before any fix is proposed. Per `interrogate-results` and
> `audit-without-test`: a screenshot is a symptom; the cause is what matters, and a narrow claim
> ("surface X hangs") is a hypothesis until the code confirms it.

## 1. Method & environment

- **Stack:** clean dev-stack restart (backend `:50758`, Vite UI `:5173`), worker ready, AI runtime
  activated (`Qwen_Qwen3.5-9B-Q4_K_M.gguf` on GPU, CUDA 12, 12 GB tier), **37 docs indexed**.
- **Driver:** Claude-in-Chrome browser automation; visual screenshots + `find` + shallow
  shadow-DOM probes + console/network reads at each step.
- **Backend health at test time:** `lifecycle=READY`; `retrieval` composite = **DEGRADED**
  (`chunk_embedding.not_ready` 0% chunk-vector coverage + `lambdamart.not_configured`); embedding
  compat `BLOCKED_LEGACY` (`reindexRequired=true`, legacy index w/o embedding fingerprint). This
  degraded-but-serving state is *expected* dev data and was useful: it let me verify how honestly
  the UI reports degradation.

### 1a. Coverage caveats (so findings are weighted correctly)

- The capture viewport was **pinned at 991×766** (the extension fixes the offscreen capture size;
  `resize_window` corrupted the capture path). **Responsiveness / breakpoints are UNCOVERED.** The
  one wide-window capture I did get was **1568px**, which is the basis for the "no max-width" note.
- The UI is Lit + Shadow-DOM, so the automation accessibility tree presented as a single flat node;
  a11y findings here come from the app's *own* runtime console guards + a focus probe, **not** a
  full assistive-technology pass. A real screen-reader audit is still owed.
- **Not deeply exercised:** Agent / Structured chat intent tiers; System → Logs/Activity/AUDIT;
  Library → Browse; Settings → Skins; AI Brain → Memory tab; drag/resize; mobile.
- One mid-session "Reconnecting…" lock-up was **partly harness-induced** (repeated tab kill/recreate
  + the `resize_window` bug poisoning the browser HTTP/1.1 socket pool). Distinguished from product
  behavior in §4.1.

## 2. Surfaces visited (the map)

The left rail is icon-only; destinations discovered by clicking. URL scheme is
`#justsearch://surface/<id>`.

| Rail glyph | Surface id | Title | Load | Notes |
|---|---|---|---|---|
| folder-plus | `core.library-surface` | Library | instant | Folder mgmt, browser-mode banner, exclude-glob editor |
| chip | `core.brain-surface` | AI Brain | **~15–20s** | Heavy fan-out; raw "Loading…" placeholder; transient "Reconnecting" |
| chat bubble | `core.unified-chat-surface` | Chat | instant | RAG/Documents + Search/Structured/Agent/History tiers |
| tray (upper) | `core.system-surface` | System | instant | Health/Logs/Activity/AUDIT; "Service degraded" badge |
| magnifier | `core.search-surface` | Search | instant | Facets, highlight, pipeline trace, export, Ask AI |
| tray (lower) | `vendor.token-editor.editor-surface` | Theme Editor | instant | WCAG + APCA contrast tooling |
| bell (badge "1") | — (Advisories drawer) | Advisories | instant | "Schema Reindex Required (worker.schema)" |
| help "?" | `core.help-surface` | Help | instant | FAQ + Local-first + Keyboard shortcuts |
| power ⏻ (bottom) | `core.settings-surface` | Settings | instant | Simple/Advanced, themes, high-contrast, vim |

## 3. What works well (strengths — keep these)

- **Search (excellent):** type-as-you-go, ~780ms/30 results, query-term highlighting, faceted
  filters w/ counts, date-range filters, deep-linkable `?query=`, export (MD/JSON/Paths), "Ask AI"
  handoff, per-result **"Why this result?"**, and a full **"Pipeline details"** trace
  (`decision: sparse_shortcut`, QPP metrics, per-stage executed/skipped incl.
  `LambdaMART: skipped (MODEL_NOT_LOADED)`, `Cross-encoder: executed · 166ms`).
- **RAG chat (excellent, verified end-to-end):** streamed a **factually-correct, grounded** answer
  with inline citations `[1] [4]`; retrieval header (*"5 passages used (251 found) · bm25 ·
  coverage 40%"*); **per-claim groundedness verdict** (*"Partly grounded — some statements are not
  backed by your documents"* with the weak sentence underlined); **"5 SOURCES"** each resolving to a
  real file + relevance bar + **Open**; **Stop** (cancel-stream) control; tab-title activity dot.
- **Honest degradation, consistent across surfaces:** the real `retrieval=DEGRADED` backend state
  surfaced as a Search banner (+ Force Rebuild), System "Service degraded" badge, AI-Brain
  "Embedding model fingerprint missing" warning, and an Advisory — all internally consistent.
- **Design system:** real Light theme (not inversion), High-contrast + Vim toggles, and a Theme
  Editor showing live **WCAG ratios AND APCA Lc** per role with "clears AA but perceptually weak"
  flags.
- **Live status bar + System dashboard, navigation undo toasts, Copy-URL/bookmarks/saved-views,
  Local-first reassurance, honest "browser mode — native folder picker unavailable" messaging.**

## 4. Findings (observed symptoms — root causes in §5)

### Functional

**F-1 — AI Brain surface very slow to load (~15–20s), bare placeholder, transient "Reconnecting".**
Clicking the chip → `Loading core.brain-surface…` (raw internal id, no skeleton) for ~15–20s while
the inference badge flips to **"Reconnecting…"**, then it renders fully and recovers. On a clean
load it fans out ~9 concurrent calls — `/api/ai/install/status`, `/api/ai/runtime/status`,
`/api/ai/packs/status`, `/api/policy/effective`, `/api/settings/v2`, `/api/inference/transitions`,
`/api/diagnostics/traces`, plus `/api/status` + `/api/inference/status` polls — **all returning 200**
yet the surface stays on "Loading" well after the data arrives. Over HTTP/1.1's 6-connection
per-origin cap + the persistent `EnvelopeStream`, that burst transiently starves the status polls,
which is what flips the badge to "Reconnecting." (*Not* a permanent hang — corrected after waiting
longer. The earlier "stuck forever" = this slowness × harness socket-pool poisoning.)

**F-2 — Simple/Advanced toggle does not visibly change the nav rail.** Settings → "Advanced — Full
controls + diagnostics" vs "Simple — Standard view" produced no real-time change to the rail; the
diagnostic surfaces (AI Brain, System, Theme Editor) stayed present in Simple mode. Either the
effect is in-surface only, deferred to reload, or not wired to the rail.

### Accessibility (warrants a dedicated AT audit)

**A-1 — 3× `[jf-control] no accessible name` console ERRORS at boot.** Verbatim, `Control.ts:58`:
*"[jf-control] no accessible name — set `operation-id`, a non-empty `label`, or slot text (559
Authority V §11: a nameless control is unrepresentable through the primitive)."* Three controls
render nameless despite the stated invariant.

**A-2 — Focus indicator not clearly visible.** Keyboard focus *does* traverse to real controls
(probe: `jf-shell >> jf-stage >> jf-unified-chat-view >> button`), so operability exists — but no
visible focus ring appeared on Tab.

### UX / polish

**P-1 — Internal IDs / raw codes leak into user copy:** `Loading core.brain-surface…`; Search
banner bullet `Degraded: lambdamart.not_configured`; Advisory `Schema Reindex Required
(worker.schema)`; Advisory timestamp as raw `19:35:18 GMT+0200 (Central European Summer Time)`
(not relative).

**P-2 — No max-width / reading measure on wide screens.** At 1568px the composer, Send button, and
content stretch edge-to-edge with a large empty conversation void; chat answer card is sensibly
width-bounded but the composer is not.

**P-3 — Help → Keyboard shortcuts bugs.** "Focus search bar" and "Enter command mode (when search
is focused)" **both display `/`** (indistinguishable); "Enter AI chat mode" shows a **`??`**
placeholder keycap (looks unresolved).

**P-4 — Two missing i18n catalog keys render raw** (DEBUG, `resourceCatalog.ts:96`):
`registry-surface.governance-surface.label` and `registry-surface.memory-surface.label`.

**P-5 — Icon rail discoverability / icon semantics.** Icon-only rail, no hover tooltips observed;
the **bottom power ⏻ glyph opens Settings** (power normally = quit — confusing/risky); Settings has
no clearly-labeled rail entry.

**P-6 — Minor:** Search facet counts (38/39) exceed result count (30) — appear corpus-wide, may
mislead; System → CONNECTION "Endpoint" shows the UI origin (`localhost:5173`) not the retrieval
backend; oversized single-line user message bubble vs. internally-scrolled answer card in chat.

## 5. Root-cause investigation (Phase 2 — DONE)

> Method: five read-only Explore subagents fanned over the finding-clusters, then **every
> load-bearing claim was re-verified by direct `Grep`/`Read`** (per `audit-without-test`: an audit
> conclusion is a hypothesis until the code is read). Citations below are confirmed. Five Phase-1
> observations were **re-classified** by the investigation (A-2, P-2, P-5-tooltips, P-6, and the
> magnitude of F-1) — recorded honestly rather than defended.

- [x] F-1 · [x] F-2 · [x] A-1 (partial) · [x] A-2 · [x] P-1 · [x] P-2 · [x] P-3 · [x] P-4 · [x] P-5 · [x] P-6

### 5.1 F-1 — AI Brain slow load (~15–20s) + transient "Reconnecting"
- **Placeholder:** `Shell.ts:2514–2516` — on navigating to a not-yet-defined surface, the Shell calls
  `ensureSurfaceLoaded(mountTag)`, `customElements.whenDefined(mountTag).then(requestUpdate)`, and
  meanwhile renders `html\`<div class="empty">Loading ${surface.id}…</div>\`` → the raw id leaks
  (this is also **P-1a**).
- **Eager fan-out, no dedup:** `BrainSurface.ts` `connectedCallback` → `refreshAll()` fires a
  `Promise.all` of **7** endpoints (`/api/settings/v2`, `/ai/install/status`, `/policy/effective`,
  `/ai/runtime/status`, `/ai/packs/status`, `/inference/status`, `/status`) + `startInferencePolling()`
  — independent of the shared `inferencePoll`/`statusPoll` (no dedup; ~9 concurrent with the global
  pollers). (`BrainSurface.ts` ~534–625.)
- **Badge "Reconnecting…":** `aiStateStore` `STALE_THRESHOLD_MS = 15_000`; a poll with no success in
  15s → phase `stale` → `computeStatusLabel` returns `"Reconnecting…"`, rendered by `StatusDeck`. A
  concurrent-fetch burst over the HTTP/1.1 6-conn-per-origin cap (+ the persistent `EnvelopeStream`)
  can starve the shared poll past the 15s window → false "Reconnecting", which recovers when sockets
  free.
- **⚠ Correction of magnitude (interrogate-results):** I observed the **raw `Loading
  core.brain-surface…` placeholder for the entire ~15–20s** — *not* a quick transition into a
  "Connecting…" body (one subagent inferred a transition; the live capture contradicts it). That
  points the dominant delay at the **lazy `whenDefined`** resolving slowly: BrainSurface's large
  module graph loads **on-demand through Vite in dev**, each sub-import a separate request queued
  behind the saturated 6-connection pool. A **production bundle ships BrainSurface as one prebuilt
  chunk**, so the bulk of the 15–20s most likely disappears in prod. **Severity hinges on a prod
  measurement that is still owed** (no prod build was tested).
- **Classification:** raw-id placeholder = real minor bug (P-1a); 15–20s = **likely dev-dominated,
  verify in a production build before treating as P1**; eager fan-out + no dedup = real efficiency
  smell; false "Reconnecting" = real but transient and prod-plausible.
- **Fix:** (a) friendly title in the placeholder + a skeleton; (b) subscribe `BrainSurface` to the
  shared `inferencePoll`/`statusPoll` instead of re-fetching `/inference/status` + `/status`;
  (c) don't flip the badge to "Reconnecting" on a single missed poll during a known load burst;
  (d) measure cold-load on a prod bundle to confirm the lazy-load cost is dev-only.

### 5.2 F-2 — Simple/Advanced doesn't trim the rail (expectation gap)
- `refreshSurfaces()` (`Shell.ts` ~1769–1857) filters rail entries by `placement: 'RAIL'`,
  host-membership, layout-zone, `surfaceVisibility`, `surfaceOrder` — **no `uiMode` filter**. Shell
  only *sets* the mode (`jf-set-ui-mode` listener → `setUiMode`, `Shell.ts:915/917/933`); it never
  *reads* it for the rail. Only `SearchSurface` consumes `uiMode` (to hide the retrieval trace).
- So Simple/Advanced governs **in-surface** diagnostics only. The Settings copy
  ("Advanced — Full controls + diagnostics" / "unlocks AI runtime configuration, GPU controls…")
  **over-promises** a rail effect that isn't wired.
- **Classification:** incomplete wiring / copy mismatch. **Decision needed:** either filter the rail
  by `uiMode` (hide AI Brain / System / Theme Editor in Simple, or mark them `DIAGNOSTIC` altitude so
  the altitude projection drops them), **or** soften the Settings copy. Not a crash — an honesty gap.

### 5.3 A-1 — nameless `jf-control` (PARTIALLY resolved — follow-up owed)
- **Guard confirmed:** `Control.ts:92–100` — DEV-only (`import.meta.env.DEV`); fires when there's no
  `operation-id`, no `label`, and no slotted text; the control **still renders** with
  `aria-label=${name || nothing}` (empty) (`Control.ts:106`). So it's an authoring warning, not a
  render block — but three controls ship **without an accessible name** in the dev build.
- **Exact 3 sites NOT pinned statically** — they're conditionally-rendered first-paint controls. Ruled
  out: the top-bar icon buttons and rail buttons (native `<button>` with `title`+`aria-label` via
  `present()`). Remaining suspects: composed chrome on first paint (`StatusDeck`, the composer, the
  unified-chat tab strip). **Owed: a DEV-mode stack-trace capture to identify the three precisely**
  (the right next step — a static guess would violate `audit-without-test`).
- **Classification:** real low-severity a11y defect against the app's own 559 Authority V invariant.

### 5.4 A-2 — focus ring (RE-CLASSIFIED → not a defect)
- `ambientStyles.ts:52–56` defines a global `:focus-visible { outline: 2px solid
  var(--focus-ring-color); outline-offset: 2px }`, adopted into **every** `JfElement` shadow root;
  `Control.ts:68–72` adds a `button:focus-visible` accent ring. A visible keyboard focus ring **does
  exist**.
- My spot-check screenshot simply didn't render it clearly (down-scaled capture / subtle ring on the
  dark surface / focused element outside the captured region). **Downgraded** from "bug" to: verify
  `--focus-ring-color` contrast in both themes (cheap), but there is no missing-ring defect.

### 5.5 P-1 — internal-ID leakage (confirmed bugs)
- **P-1a** `Shell.ts:2516` — `Loading ${surface.id}…` → render the surface title (`present()`), not the id.
- **P-1b** `readinessNotice.ts:114–115` — `const row = CAUSE_ROWS.find(c => c.code === code); const
  wording = row ? row.wording : \`Degraded: ${code}\`;`. `CAUSE_ROWS` (55–86) maps embedding /
  inference / worker / ort_cuda codes (e.g. `chunk_embedding.not_ready → "Passage embeddings are not
  ready"` — which is why *that* bullet read cleanly) but has **no `lambdamart.not_configured`** → it
  falls through to the raw `Degraded: <code>`. Fix: add a `CAUSE_ROWS` row (e.g. "Learned ranking is
  not configured"). The pattern is fine; it's one missing vocab entry.
- **P-1c** `AdvisoryInboxDrawer.ts:695–697` — `new Date(iso).toLocaleTimeString()` emits the verbose
  tz string. Fix: relative/compact time (`"2h ago"` / `"19:35"`).

### 5.6 P-2 — no max-width on wide screens (RE-CLASSIFIED → intentional deferral)
- `surfaceLayout.ts:55–58` already ships a `:host([data-fill='reading'])` mode →
  `max-width: var(--surface-content-max-width)` (= `72rem`, `tokens.css:336`) with `margin-inline:
  auto`. Chat/Search content + the composer render **full-bleed by default** (no `data-fill="reading"`).
  `SettingsSurface.ts:596` + `surfaceLayout.ts:25–33` document this as a **deliberate, deferred A/B
  decision** (559 Appendix A: centering was "only a partial win").
- **Classification:** intentional current tradeoff with a one-attribute toggle — **not** an oversight.
  If we want a reading measure, opt the chat/search surfaces into `data-fill="reading"`; the composer
  is the most defensible thing to bound even if transcripts stay full-bleed.

### 5.7 P-3 — Help keyboard-shortcut data (confirmed bugs)
- `HelpSurface.ts:64–66`: `{ keys: '/', desc: 'Focus search bar' }`, `{ keys: '/', desc: 'Enter
  command mode (when search is focused)' }`, `{ keys: '??', desc: 'Enter AI chat mode' }`.
- The two `/` rows are indistinguishable; `??` is a **search query prefix** (FAQ: "Type ?? followed
  by your question"), **not** a keyboard shortcut — listing it as a keycap is a data error. Fix:
  differentiate / annotate the command-mode key, and either remove the `??` row or move it to a
  "query syntax" section.

### 5.8 P-4 — missing i18n keys (confirmed)
- Referenced in `CorePlugin.ts:71` (`registry-surface.governance-surface.label`) and `CorePlugin.ts:120`
  (`registry-surface.memory-surface.label`); **absent** from
  `modules/app-api/src/main/resources/messages/registry-surface.en.properties` (grep: no matches → raw
  key fallback). `governance-surface` = a DEVELOPER governance dashboard; `memory-surface` = "Memory"
  (AI learned memory), a member of `core.brain-surface`. Fix: add the two `*.label` (+ description)
  properties. NB the i18n catalog is a **backend resource** served to the FE — the fix lands in
  `app-api`, not `ui-web`.

### 5.9 P-5 — rail icons (mixed)
- **Tooltips/labels — RE-CLASSIFIED → not a bug:** rail buttons carry both `title` and `aria-label`
  via `present({kind:'surface', id}).label` (`Shell.ts` ~2265). My "no tooltip" was a hover-timing
  capture miss.
- **Power glyph — confirmed poor choice:** `surfaceIcons.ts:21` → `'core.settings-surface': 'power'`.
  A power/quit ⏻ glyph for Settings is misleading. Fix: a `settings`/`sliders`/`gear` icon.

### 5.10 P-6 — facet counts (RE-CLASSIFIED → by design) + System endpoint label
- Facet counts come from the backend response `facets` field (`searchState.ts`), computed
  **corpus-wide**, not over the current result page — the standard faceted-search pattern (counts show
  what a filter would surface, independent of the 30-result page). **Not a bug.** Optional: a tooltip
  clarifying "matches in your whole library".
- System → CONNECTION "Endpoint `http://localhost:5173`" showing the **UI origin** rather than the
  retrieval backend — minor; not source-verified here, left as a low-priority observation.

### 5.11 Disposition summary

| ID | Verdict | Severity | Primary site |
|---|---|---|---|
| F-1 | Real (magnitude likely **dev-only** — verify prod) | Med→High pending prod | `Shell.ts:2516`, `BrainSurface.ts:~594`, `aiStateStore` (15s) |
| F-2 | Real expectation gap (decision needed) | Med | `Shell.ts:1769–1857` (no uiMode filter) |
| A-1 | Real a11y defect (3 sites not yet pinned) | Low–Med | `Control.ts:92–100`; sites TBD via DEV trace |
| A-2 | **Not a defect** (ring exists) | — | `ambientStyles.ts:52–56` |
| P-1a | Real polish bug | Low | `Shell.ts:2516` |
| P-1b | Real (missing vocab entry) | Low | `readinessNotice.ts:115` + `CAUSE_ROWS` |
| P-1c | Real polish bug | Low | `AdvisoryInboxDrawer.ts:695` |
| P-2 | **Intentional deferral** (toggle ready) | — / opt-in | `surfaceLayout.ts:55–58` |
| P-3 | Real data bug | Low | `HelpSurface.ts:64–66` |
| P-4 | Real missing strings | Low | `registry-surface.en.properties` (app-api) |
| P-5 power icon | Real poor icon choice | Low | `surfaceIcons.ts:21` |
| P-5 tooltips | **Not a bug** | — | `Shell.ts:~2265` |
| P-6 facets | **By design** | — | `searchState.ts` facets |

## 6. Decisions / next steps

**Confirmed quick wins (one- or few-line fixes, low risk):**
- P-1a friendly loading title (`Shell.ts:2516`); P-1b add `lambdamart.not_configured` to `CAUSE_ROWS`;
  P-1c relative-time in advisories; P-3 fix the two shortcut rows; P-4 add two `app-api` i18n
  properties; P-5 swap Settings `'power'` → a settings/gear icon.

**Needs a decision (not just a fix):**
- F-2 — filter the rail by `uiMode` **or** correct the Settings "Advanced" copy.
- P-2 — opt the chat/search surfaces (at least the composer) into `data-fill="reading"`, or accept
  full-bleed as the documented tradeoff.

**Needs more evidence before sizing:**
- F-1 — measure AI Brain cold-load against a **production bundle** (`npm run build` + preview) to
  separate the dev-only Vite lazy-load cost from any real surface-load cost; independently, dedup the
  status fan-out + soften the "Reconnecting" flip (prod-plausible regardless).
- A-1 — capture a DEV-mode stack trace to pin the three nameless `jf-control` sites, then label them.

**Dropped (investigation showed not-a-bug):** A-2 (focus ring exists), P-5 tooltips (present),
P-6 facets (corpus-wide by design), and P-2 reframed as an intentional, reversible design choice.

**Suggested follow-up tempdocs / tracks once decisions land:** (i) a "frontend polish batch" for the
confirmed quick wins; (ii) the F-1 prod-load measurement + status-poll dedup; (iii) the A-1 DEV-trace
+ label fix. Per `audit-driven-fixes-need-test`, each fix that touches behavior (P-1b vocab, P-3 data,
F-2 filter) should ship with a unit test, and the shell-v0 edits run the presentation-authority gates.

## 7. Implementation & live validation (2026-06-15)

User decisions taken into the build: F-2 = hide **System + Theme Editor** in Simple mode (keep AI
Brain); P-2 = keep full-bleed (dropped). All work reused existing utilities — the only new structure
was one icon glyph the registry genuinely lacked.

### 7.1 Shipped changes (`file:line`)
| ID | Change | Site(s) |
|---|---|---|
| P-1a | placeholder renders `present({kind:'surface',id}).label` ("Loading AI Brain…") | `chrome/Shell.ts:2516` |
| P-1b | added `lambdamart.not_configured/.training/.failed` to `CAUSE_ROWS` (+ test) | `state/readinessNotice.ts:85+`, `readinessNotice.test.ts` |
| P-1c | `formatTime` → shared `formatRelativeIso(iso)` | `components/advisory/AdvisoryInboxDrawer.ts:16,695` |
| P-3 | `SHORTCUTS` rewritten to the 5 bindings that actually fire (dropped `/`,`/`,`??`, stale agent-tabs) | `views/HelpSurface.ts:63` |
| P-4 | added `registry-surface.{governance,memory}-surface.{label,description}` | `app-api/.../messages/registry-surface.en.properties` |
| P-5 | added Lucide `settings` gear glyph; remapped Settings icon `power`→`settings` | `components/Icon.ts` (union+PATHS), `utils/surfaceIcons.ts:21` |
| A-1 | labeled the 3 nameless dynamic-count buttons (label mirrors visible text incl. count → WCAG 2.5.3 + guard) | `components/EffectAuditLog.ts` (undo-all-agent / save-as-macro / export-archive) |
| F-2 | `uiMode==='simple'` drops `{core.system-surface, vendor.token-editor.editor-surface}` from the rail; `subscribeUiMode→refreshSurfaces` for live re-filter | `chrome/Shell.ts:44,366,1797+,1218+,1486+` |

### 7.2 Live browser validation (running stack, degraded-retrieval state)
- **P-1a** — brain rail label resolves to "AI Brain" via the same `present()` the placeholder uses (probe). ✓
- **P-1b** — Search banner reads "Learned re-ranking (LambdaMART) is not configured" (no raw code). ✓
- **P-1c** — Advisories drawer shows "6 minutes ago". ✓
- **P-3** — Help renders Ctrl/⌘+K · Enter · Esc · Ctrl/⌘+Z · Ctrl/⌘+Shift+Z; `/`-focus and `??` rows gone (`stillHasSlashFocus:false`, `stillHasQQ:false`). ✓
- **P-4** — boot console has **0** `missing key: registry-surface.*` warnings. ✓
- **P-5** — bottom rail glyph is a gear. ✓
- **A-1** — fresh boot console has **0** `[jf-control] no accessible name` errors (was 3). ✓
- **F-2** — Simple rail = {Library, AI Brain, Chat, Search, Settings}; Advanced restores System + Theme Editor live (no reload). ✓

### 7.3 F-1 disposition (no code beyond P-1a)
The ~15-20s AI Brain load is **dev-only**: prod ships BrainSurface as a single prebuilt ~50 kB chunk
(confirmed in the `installDist` build output), whereas dev's slowness is Vite transforming the
surface's large module graph on-demand, each import a request queued behind the saturated HTTP/1.1
6-conn pool. In-session, once the module was cached, AI Brain rendered fast (observed fully rendered
behind the advisory drawer) — functionally identical to prod's always-prebuilt state. The friendly
loading title (P-1a) is the shipped user-facing fix. The status-poll **dedup was deliberately
deferred**: BrainSurface's local `InferenceStatus` uses fields (`generation`, `lastStartupDurationMs`)
the shared `aiStateStore` snapshot doesn't carry, so deduping would force extending the shared store +
its consumers — a risk-bearing refactor whose only benefit (fewer concurrent dev fetches → fewer false
"Reconnecting" flips) is a dev-environment artifact. Recorded as an optional future optimization.

### 7.4 Verification
`npm run typecheck` clean · `npm run test:unit:run` 2997/2997 green · gates green
(controls-a11y, presentation-purity, a11y-closure, layout-purity, surface-composition,
declared-surfaces, message-single-model) · `:modules:app-api:test` green. No commit; dev stack left
running. Not-a-bug items (A-2 / P-2 / P-5-tooltips / P-6) intentionally untouched.

## 8. Gap-closing round — F-1 + missing tests (2026-06-15, follow-up)

A critical re-read found §7 under-delivered on F-1 and skipped two tempdoc-required tests. This round
closed them.

### 8.1 Done + statically verified (typecheck clean · 3000/3000 unit tests green)
- **F-1(a) skeleton** — `BrainSurface.renderBrainBody` shows a `<jf-pulse-dots>` "Checking AI status…"
  skeleton on a true cold start (inference/install/runtime all null), reusing the existing
  `components/chat/PulseDots.ts` primitive (no new component).
- **F-1(b) dedup** — BrainSurface now sources `inference` + `systemStatus` from the shared
  `aiStateStore` subscription (the documented single observed-state authority, §B7) instead of its own
  `/api/inference/status` + `/api/status` fetches; removed those from `refreshAll` and the redundant
  inference poll (the remaining loop, renamed `startDiagnosticsPolling`, polls only transitions+traces).
  `InferenceSnapshot` (`utils/inferencePoll.ts`) extended with `generation`+`lastStartupDurationMs`
  (already on the wire). **Found + fixed a real latent bug while doing this:** the store subscription
  fires immediately on connect with a possibly-null pre-first-poll snapshot — guarded with
  `if (s.inference)`/`if (s.status)` so it never clobbers a known value with null (the store retains its
  last snapshot when stale). The `switchInference` one-shot post-action refresh is kept (retyped).
- **P-3 test** — new `views/HelpSurface.test.ts`: mounts the surface, scans the full (nested) shadow
  text, asserts "Ctrl / ⌘ + K" present and the `/`-focus / command-mode / `??` rows are gone.
- **F-2 test** — added to `chrome/ShellRail.test.ts`: Simple mode hides System + Theme Editor (keeps
  AI Brain); Advanced restores them.

### 8.2 Live validation + measurement — DONE (after a `warn` takeover, user-approved)
The earlier parallel-agent contention was cleared by taking over the dev stack (user-approved) and
killing orphaned worker/Head java processes that the dev-runner's `stop` had leaked (the real cause of
the repeated `worker.spawn.failed` — an orphaned worker held the `.dev-data` index lock). Then, on the
running stack (AI activated, 37-doc degraded index):
- **F-1(b) dedup — live-validated.** AI Brain renders fully and correctly with **store-sourced** data:
  "Mode: online", RUNTIME card (CUDA available · VRAM 12.0 GB · "gpu 12gb plus"), "embed queue: 5 ·
  VDU queue: 0", and the **"Embedding model fingerprint missing"** callout (proves `systemStatus`
  flows from the store's status snapshot). Boot console **clean** — 0 `jf-control` errors, 0
  dedup/inference errors (only vite/Lit-dev/SES/`schema.reindex-required` noise).
- **F-1(d) prod cold-load — MEASURED.** Added a `configurePreviewServer` hook to the `jf-api-proxy`
  plugin (`vite.config.js`, reusing the extracted `makeProxyHandler`; verified `preview /api/status` →
  200). Built the prod bundle (`BrainSurface-*.js` = one 51 kB / 12.5 kB-gzip chunk) and measured via
  the Performance API on `localhost:4173/#…brain-surface`: **DOMContentLoaded 123 ms · BrainSurface
  chunk loaded ~21 ms (ready ~361 ms from navigation start)** — vs the **~15–20 s** dev load. This
  empirically confirms the slow load is **100% a dev-only Vite on-demand module-graph artifact** and
  does not ship to production.
- **F-1(c) soften "Reconnecting" — empirically UNNEEDED (not done, by design).** At a ~360 ms prod
  load there is no window for the status polls to starve past the 15 s staleness threshold, so the
  false "Reconnecting" flip cannot occur in prod; the dev flip was sustained module-load starvation
  (gone in prod; the dedup further trims dev pressure). Per `interrogate-results`, the measurement
  supersedes the tempdoc's earlier "prod-plausible regardless" hedge — so the global connection-health
  staleness signal was deliberately **left untouched** rather than weakened speculatively.

### 8.3 State
All of §8 done + verified: `typecheck` clean · `test:unit:run` 3000/3000 green (incl. new
`HelpSurface.test.ts` P-3 + `ShellRail.test.ts` F-2 cases + the existing sparkline test, which caught
a real latent clobber bug fixed in §8.1) · FE gates green (controls-a11y, presentation-purity,
a11y-closure, layout-purity, run-renderers, inflight-liveness) · AI Brain live-validated · prod
cold-load measured. No commit. Dev stack left running (AI active); preview server stopped.

## 9. Final tail — tempdoc fully closed (2026-06-15)

The last items from the §6 buckets + the parked observations. Two were verified **already-correct**
(documented, no code); two were tiny polish fixes (done + live-validated).

- **F-1(c) "soften the Reconnecting flip" — RESOLVED, no code.** Verified the badge already debounces
  transient loss: it flips to "Reconnecting…" only after **15 s sustained** poll failure
  (`STALE_THRESHOLD_MS = 15_000`; `computeStaleness`/`computePhase` in `state/aiStateStore.ts`,
  mirrored by `HealthSurface.renderConnection`), and a **cold boot shows "Connecting…"** (phase
  `connecting`), not "Reconnecting…". `aiStateStore.test.ts` pins both. The tempdoc's "single missed
  poll" framing was imprecise; the observed dev flip was a legitimate >15 s starvation (gone in prod;
  further reduced by the §8 dedup). Softening further would *delay real-disconnect detection*, so it
  is deliberately left as-is. (My earlier "deviation" flag is hereby resolved: this is the correct,
  evidence-based outcome — the literal "regardless" rested on a premise the prod measurement falsified.)
- **A-2 focus-ring contrast — RESOLVED, no code.** `--focus-ring-color` = `--accent-tint`; measured
  vs the surface backgrounds: ~9–10:1 (dark), ~5.4–6:1 (light), ~6–10:1 (high-contrast) — all far above
  the WCAG ~3:1 non-text minimum. The `check-contrast-matrix` gate correctly scopes to text/accent role
  pairs (a 2 px non-text ring isn't a role pair), so a one-off check was the right tool. Confirmed fine.
- **P-6 facet-count clarity — DONE + live-validated.** Added a `title="N matches across your library"`
  to each facet chip (`components/searchResults/facetChips.ts`), reusing the established `title=`
  tooltip pattern (aria-label unchanged). Live: all chips carry the clarifying tooltip.
- **System "Endpoint" label — DONE + live-validated.** Relabeled the CONNECTION row key
  `Endpoint` → `API endpoint` (`views/HealthSurface.ts`) — it shows the app's API origin (`apiBase`;
  the value, UI+API same-origin in prod, was correct; only the label was ambiguous). Live: the card
  reads "API endpoint".

**Verification:** `typecheck` clean · `test:unit:run` 3005/3005 green · FE gates green
(presentation-purity, controls-a11y, a11y-closure, layout-purity) · both UI changes browser-validated
on the live stack. **Tempdoc 586 is now fully closed** — every §4 finding is either fixed + validated,
or re-classified not-a-bug / verified-correct with evidence. No commit (session policy).
