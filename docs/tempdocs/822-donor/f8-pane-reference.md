# F8 prerequisites — citation-inspection pane (mined 2026-08-13, read-only pass)

Reference brief for the F8 implementation slice. Sources: repo at worktree HEAD; t3code
sparse clone @ b73232bd (apps/web/src — full clone hits Windows path-length errors in
vendored .repos/).

## 1. The shared document pane

- `jf-document-pane` / `DocumentPane`, `components/documentPane/DocumentPane.ts:112` —
  genuinely shared (outside all views/). Registered today by a side-effect import in
  SearchV2View.ts:96 — F8 must add its own side-effect import, not rely on v2's.
- API (`:112-168`): `.docPath`, `api-base`, `.highlightRange {startLine,endLine}`
  (0-based inclusive), `.chunkRange` (weak tint), `mode: rendered|source`. Events:
  `pane-close` (`:322`, bubbles+composed, no opinion on meaning), `pane-visible-range`
  (`:354`, 150ms debounce).
- Land-strong-then-settle is the PANE's own: `HIGHLIGHT_DECAY_MS 1500` (`:95,145,226-244`),
  re-render-safe via `armedHighlightKey`, skipped under prefers-reduced-motion
  (`:101-110`). Do not add a second emphasis.
- v2's wiring pattern (cite-only, no imports from v2): panel-element-local
  `@citation-select` → set docPath+range (`SearchV2View.ts:944-952, 2072-2089, 2221-2228`).

## 2. Double-open mechanics (the truth, superseding older notes)

- `components/InspectorPane.ts` NO LONGER EXISTS — retired (Shell.ts:1340-1342,
  2304-2305). Nothing to suppress there.
- The real racer: UnifiedChatView's own jf-document-pane mount driven by the shared
  `state/inspectorState.ts` store, written by Shell's UNGUARDED host-level listener
  (`Shell.ts:739` add; `:543-554` handler; `:533-542` "the ONE listener" comment).
- `citation-select` is bubbles+composed from every producer (SourcesPane.ts:144-148,
  CitationsPanel.ts:291-296, MarkdownBlock.ts:571-576, UnifiedChatView.ts:4674-4680) —
  it WILL reach Shell from inside sv3's shadow tree.
- Today's non-interference is INCIDENTAL (stage mounts one surface at a time), not a
  guard. **F8 requirement: sv3's own citation-select handler calls
  event.stopPropagation()** so Shell's `setSelected` write never fires for in-window
  clicks; test this (probe: without stopPropagation, inspectorState mutates).

## 3. Donor right-panel geometry (t3code @ b73232bd)

- Widths: default **540px**, min **360px**, max **floor(70vw)**
  (`PreviewPanelShell.tsx:12-19,42-49`). Persisted per-window,
  localStorage `t3code:preview-panel-width`, written ONLY on pointerup
  (`useResizableWidth.ts:29-36`); pointercancel reverts without persisting.
- Handle: `role="separator"`, `-left-1 w-2` hit (8px), 1px line, `col-resize`,
  edge:"left" (grows leftward, right-anchored), rAF-coalesced drag
  (`RightPanelResizeHandle.tsx`, `useResizableWidth.ts`).
- Topbar override 44px (`--spacing(11)`) only in NON-inline (sheet) modes
  (`RightPanelTabs.tsx:596`); inline keeps ambient 52.
- 980px switch (`rightPanelLayout.ts:1`): below → Sheet (`w-[min(42vw,28rem)] min-w-80
  max-w-[28rem]`, narrower ≤760px; 200ms opacity/translate, `sheet.tsx:25,80`);
  above → inline `border-l border-border` on `bg-background`, explicit width, no
  open/close animation (conditional render, ChatView.tsx:6520-6553).
- **No main-column clamp exists for the right panel in the donor** (unlike the sidebar's
  viewport−40rem rule) — searched, absent. F8 IMPROVEMENT (charter law 11, and the F5
  host-box precedent): clamp pane max so the main column keeps its 640px floor with BOTH
  sidebar and pane open, deriving from the window host box.

## 4. F8 requirements distilled

1. In-window `<aside>` right pane mounting the shared jf-document-pane; mounted only
   while a doc is open; `pane-close` + Escape (existing order) close it.
2. Panel-element-local citation-select handling + stopPropagation (see §2), probed.
3. Geometry: donor numbers (540/360) but max = min(70% of host box, host − sidebar −
   640); resize per F5's grip discipline (pointer + keyboard + double-click-forgets),
   persisted on pointerup under `justsearch.searchV3.pane.width.v1`.
4. Narrow behavior: below the window-container equivalent of 980px, overlay
   presentation (window-scoped, --z-overlay, per the palette's containment precedent) —
   NOT a document-level sheet.
5. Reading surface scope: cited documents only. Opening arbitrary search results stays
   deferred (standing directive); no search affordances in the pane chrome.
