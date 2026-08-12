# 822 — Search window rebuilt on the T3 Code donor system

```
status: ACTIVE
created: 2026-08-12
owner-decisions (owner conversation, 2026-08-12, this session):
  - T3 Code (pingdotgg/t3code) is the SOLE design donor. T3 Chat is retired as a donor
    ("focus strictly on T3 code and forget T3 chat") — its capture artifacts remain in the
    session scratchpad as dated reference only; nothing in this arc consumes them.
  - New window FROM SCRATCH: zero presentation code reused from UnifiedChatView or
    search-v2. Shared authorities (search API clients, stores) will be reused when real
    data is wired — per the 818 guardrail "from-scratch components, shared authorities".
  - NOT on main, NOT in any installer: this branch stays local. No push, no PR, no
    publish, no merge without a new explicit owner instruction.
  - Autonomous proceed granted ("proceed autonomously from here on") — slices execute
    without blocking on checkpoints; screenshots surfaced at every slice boundary so the
    owner can redirect at any time.
related: 818 (Search v2 — strangler/route-registration recipe reused; its window is NOT
  touched), 820 (feel-budget predicates, other worktree — complementary, chat-surface
  latency; its W2 T3 Code interaction-pattern notes feed a LATER slice, not this one)
```

## 0. Problem

Agent-generated UI design failed across multiple rounds (evidence: this session's
frontend-history audit — every attempt was palette-scoped, no brief ever contained a
positive visual reference, honesty-law culture defended chrome accretion). Owner decision:
copy the design of an admired product instead of originating one. Research basis (session
2026-08-12): donor CODE is the measured-best reference input (beats screenshots; DesignBench
edit 8.4-8.6/10 code-only vs 7.4-7.7 image-only); region-by-region beats whole-screen
(up to +48% at high complexity); critic loops plateau at ~3 cycles; agent eyes cannot
self-judge geometry (BlindTest ~58.6%) — verification must be measurement, not vibes.

## 1. Donor authority

- **Spec:** `docs/tempdocs/822-donor/t3code-system.md` (mined 2026-08-12; every claim
  cites file:line into the clone).
- **Source:** github.com/pingdotgg/t3code @ `b73232bdd31e83914a8a943960c7dc4b6390b39b`
  (2026-08-12). Session-local clone in scratchpad; re-clone at that commit if absent.
- **License:** MIT (T3 Tools Inc.). Derived CSS shipped in a build must carry a
  THIRD-PARTY-NOTICES entry. Added in slice 1 so it can never be forgotten at cutover.
- Build briefs consult ONLY this spec + clone. No other products, no invented flourishes,
  no T3 Chat vocabulary.

## 2. Adopted donor laws (all cited in 822-donor/t3code-system.md)

1. **Token graph flows primitive → semantic → component; dark mode = semantic-token
   redefinition, never component re-authoring.** JustSearch twist: tokens live on the
   window HOST element (not `:root`) so the donor palette cannot leak into the shipped app;
   custom properties inherit down through every nested shadow root (donor §8.1 verifies
   the mechanism in production).
2. **Geometry tokens are semantic and test-enforced** ("cannot quietly drift apart") —
   every component's use of `--control-radius` / inset tokens is asserted by unit test.
3. **Two-knob radius:** derived additive ladder off `--radius` for surfaces; independent
   `--control-radius` for controls.
4. **Surface encodes interaction; content encodes status.** One surface model for rows;
   states express in content. (Donor tried elevation-per-state and recorded the failure.)
5. **3-color status budget:** color only for act-now / in-motion / broken; resting state
   unlabeled.
6. **Dark is an elevation inversion:** light casts shadows down; dark catches light
   (low-alpha white fills + 1px inset top highlight, drop shadows removed). Dark gets more
   blur, less saturation.
7. **Duty-cycled motion:** looping indicators hold ~40% at extremes, step in coarse
   increments, animate transform/opacity only. No continuous animation at rest.
8. **The app never scrolls; only inner regions do.**
9. **View-transition choreography:** only named elements move; opacity holds at the ends,
   crossing only mid-transition; sub-elements exit faster than containers settle.
10. **Type: system font stack, four effective sizes, weights 400/500/600 (no bold),
    desktop ramp one step down from touch.** No shipped fonts.
11. **Improve-don't-copy (donor §8.4):** explicit z-scale tokens; explicit 4px spacing
    ladder; tokenized 1px-border padding compensation.
12. **Never copy (donor §9):** T3 branding/wordmarks/stage-art/ultrathink/brand hue;
    Electron WCO env() values (keep the token indirection, feed from Tauri later);
    code-agent domain furniture.

## 3. Scope & mechanics

- New directory: `modules/ui-web/src/shell-v0/views/search-v3/`. Elements `jf-sv3-*`
  (prefix convention per `customElementPrefix.test.ts`).
- Mounted DEEPLINK/DEVELOPER only, hidden route `core.search-v3-surface`, **no rail
  entry** — 818 slice-1 registration recipe (lazySurfaceRegistry + CorePlugin +
  registry-surface label catalog + component-vocabulary regen).
- Fixture-first: no backend consumption until the shell clears the owner's bar.
- Zero new npm dependencies. No edits to UnifiedChatView, search-v2, global tokens.css,
  or any shipped surface.
- Implementation workers: ONE at a time (shared worktree), opus, Edit/Write tools only
  (UTF-8 rule). Orchestrator writes briefs + decomposition; worker never invents layout.
- Verification per slice: typecheck + unit tests + ui-web gate recipe; /ui-check ui-shot
  with measured assertions vs donor numbers; refute-first critic ≤3 cycles; orchestrator
  critical-analysis pass. Agent eyes judge nothing — numbers and gates do.

## 4. Slice plan

1. **Token sheet + shell skeleton** (fixture data): host-scoped donor tokens (semantic
   light+dark, geometry, radius ladder, z-scale, spacing ladder), shared adopted
   stylesheet (keyframes + scrollbar mixin), window grid (topbar 52px / sidebar 256px
   shell / main surface / composer band shell), THIRD-PARTY-NOTICES entry. This slice is
   the calibration experiment for the whole copy pipeline (Lit target is unbenchmarked).
2. **Sidebar rows** — donor session-row spec (two-level inset 8/10px, size ladder,
   hover/active at 6%/9% foreground-alpha, group labels) as the future result-row base.
3. **Composer + input morph** — view-transition choreography (§5.5/5.9), glass shell
   (simplified — no welded-tray clip-path), focus states.
4. **Command palette + empty states** — 420×576 popup, 8/16px inset pair, selection
   precedence; donor empty-state pattern.
5. **Adaptation seams — STOP for owner conversation** before designing any
   JustSearch-specific surface (result rows with real densities, document pane,
   citations). Donor principles constrain; owner decides.
6. Later (separate): real search wiring (shared authorities), 820-predicate alignment,
   promotion/sunset decision — none of it in this tempdoc's scope.

## 5. Log

- 2026-08-12 — Charter authored. Worktree `822-t3code-window` off local main @ 0063a8f4;
  FE deps prepared (`prepare-worktree.cjs --no-dist`). Donor pinned, spec imported.
  T3 Chat capture retired (kept in scratchpad: decision-sheet.md, tokens.json, 22 shots —
  dated reference only). Slice 1 delegated.
- 2026-08-12 — **Slice 1 implemented + verified** (opus worker + orchestrator review +
  live measurement). `views/search-v3/`: host-scoped token sheet (T0/T1 dark-default +
  light set behind `:host([theme="light"])`, T2 geometry, radius ladder, z-scale,
  4px spacing ladder, `--control-pad-3`; teal `--primary` replacing donor brand hue),
  shared adopted sheet (4 duty-cycled keyframes, scrollbar mixin via inherited
  `scrollbar-color` — `ambient-purity` bans `::-webkit-scrollbar` outside primitives),
  five shell components (`jf-sv3-window/topbar/sidebar/main/composer`), fixtures,
  33 tests incl. token-enforcement + box-math mechanism pins. Registration mirrors 818
  (CorePlugin DEVELOPER/DEEPLINK, lazySurfaceRegistry, label catalog,
  vocabulary regen, sandbox-coverage exempt row). MIT notice colocated in `search-v3/`
  (root THIRD_PARTY_NOTICES is a machine projection with no derived-source input —
  promotion-time TODO recorded in the notice). Verified: typecheck clean; 4480 unit
  tests green; 29/31 ui-web gates OK (2 reds pre-existing, untouched files);
  vite build green. **Live-measured** (Playwright, worktree serve, 1568×900):
  mount at hidden deeplink, five regions render, zero page errors; one remediation
  round — missing `box-sizing: border-box` made geometry tokens mean "at least"
  (sidebar 273→**256**, topbar 53→**52** after fix; donor numbers land exactly).
  Render is window-in-stage: the shipped shell's rail/topbar/status-bar frame the
  surface — the donor look is judged on the window interior; whole-shell chrome is a
  cutover-scope question, not this arc's. Observation filed: deeplink hash not honored
  on backend-less boot (URLSource boot read loses the default-surface race; popstate
  re-dispatch works — likely affects search-v2 too).
- 2026-08-12 — **Slice 2 implemented + verified** (fresh opus worker + live measurement,
  zero remediation rounds). `jf-sv3-session-row` per donor §6.1/§6.2: 32px rows,
  8+10 two-level inset (fill starts at 8, measured), --control-radius, glyph ladder
  (rest .4 grayscale → full on hover/active), fill precedence active>selected>hover with
  :not() guards (mutation-probed tests), 3-color status budget (act-now --success /
  in-motion --warning+duty-cycled ping / broken --destructive; resting = timestamp, no
  dot — donor's null-gray dot dropped per charter law 5), content ladder (unread w500
  full fg / broken 95% / normal 90% / receded 75%+w400), single-line ellipsis,
  content-visibility virtualization, aria-current on the one active row, 3×32px group
  labels, static order. 4494 tests green; kernel gates pass; live-measured all spec
  values exact (10 rows, 1 ping, 1 aria-current, hover fill appears). Worker corrected
  the charter's slice-2 shorthand: 6%/9% fills are the command palette's pair (§3.3),
  not the sidebar's — sidebar uses --sidebar-row-* tokens (§6.1). OWNER CALL PENDING:
  donor dark mode makes hover/active/selected fills identical (all white@4%),
  distinguishing states by content only — copied verbatim; diverging is one line.
  Known cosmetic: fixture glyphs render as empty ghost squares (no real icons in
  fixtures); resolves when real data lands.
- 2026-08-12 — **Slice 3 implemented + verified** (opus worker + 1 remediation round,
  both defects live-caught by orchestrator probes, fixes independently re-probed).
  Composer anatomy per donor (768px glass box, --radius-3xl 22px, dark glass
  mix@80%+blur16/sat1.08 with inset top highlight + no drop shadow — elevation
  inversion; light set carries the donor's single composer shadow; @supports no-blur
  fallback), field (min 70 / max 200, field-sizing: content, overlaid placeholder —
  ::placeholder is ambient-gated), round 32px send (disabled-on-empty, hover
  scale 1.05), two ghost scope-control chips (--control-pad-3), focus/invalid via
  :has() + --ring. Hero↔docked morph on the donor §5.5 choreography (group 180ms,
  sub-exit 130ms, holds 0-35/65-100), document-level VT sheet ref-counted onto
  adoptedStyleSheets with lifecycle containment, doubly-disabled reduced motion.
  REMEDIATION 1: morph flag leaked — rAF await inside the VT update callback
  deadlocks (Chromium suspends rendering; callback hit the ~4s timeout, so the morph
  also never animated); fixed with updateComplete awaits + unconditional finally +
  sync-throw fallback (a second hole the new test caught). REMEDIATION 2: glass
  reported backdrop:none — donor's node split (radius on one node, material on a
  sibling pseudo) served only the excluded attachment tray; collapsed to ONE .glass
  node, structural test forbids re-split. 4520 tests green; 10/10 mutation probes
  caught; kernel gates pass; live: flag null at +3s, blur(16px) saturate(1.08) on the
  22px node, zero page errors. Worker's clone-verified brief corrections: hero = same
  box centered + headline (not wider); send is bespoke rounded-full (not §6.3
  primary); label collapse instant by donor construction (VT crossfade covers it).
- 2026-08-12 — **Slice 4 implemented + verified** (opus worker, self-live-measured;
  orchestrator screenshot review; 1 defect the worker live-caught itself: ambient
  :focus-visible sheet boxing the palette field — fixed with the donor's ring-0
  equivalent). `jf-sv3-palette`: 576×420 donor spec exact (8/16 inset pair, item
  fills 0.06/0.09 single-fill precedence, mask-based scroll fade, kbd footer,
  corner-follows-footer), window-scoped overlay (--z-overlay, backdrop dims ONLY the
  window region; deliberately no showModal — top layer would cover shipped chrome;
  background not inert, recorded), Ctrl+K via host capture-phase listener (no global
  listener, spied); `jf-sv3-empty` (tile-fan anatomy) for sidebar-empty +
  zero-results. 4555 tests; 16/16 mutation probes; kernel gates pass.
  Accepted deviations: tooltip §6.5 skipped (aria-labels instead); 10cqh replaces
  10vh (window-scoped container); pointer moves highlight (donor hover would be a
  third fill); item 33px vs donor 32 (no line-height token tier — introduce with a
  type slice if wanted); donor --font-heading undefined in clone, not ported.
  OPEN (cutover-scope, observation filed): Ctrl+K inside the window opens BOTH
  palettes — the shipped mod+k dispatcher is a window-capture listener from Shell
  boot (KeybindingRegistry.ts:178) and cannot be pre-empted from a surface; remedy
  is a when-clause on the shipped binding or a surface-scoped binding tier, both
  outside this arc's file scope.
- 2026-08-12 — **Polish pass** (orchestrator five-state visual tour findings, donor-
  answerable subset): docked composer now COMPACTS per the donor's compact form
  (band 142→75px, field floor 70px→1lh with growth + 200px ceiling preserved,
  insets tightened to the donor's px-3 py-2 split; the donor's "single truncating
  line" honestly ported as a one-line floor since our field stays typable); scope
  chips got real 16px glyphs via the shared icon() helper (database/clock — the same
  Lucide set the donor imports), names move to aria-label ONLY when compacted.
  4560 tests; 9/9 new mutation probes. Open items recorded for the adaptation phase:
  (a) chip box is slice-3's 24px vs the donor ComposerControl's h-7 28px — two donor
  referents exist, settle when chips become real scope chips; (b) Sv3Empty tile glyph
  is a string prop rendering a hollow square — convert to icon() with a template-typed
  prop when empty states get final content.
- 2026-08-12 — **SHELL COMPLETE (slices 1-4). Phase stop per §4.5**: owner inspection
  + verdict on the donor copy, then the adaptation-seams conversation (result rows
  with real densities, document pane, citations, honesty facts) BEFORE any
  JustSearch-specific surface is designed. Serve: modules/ui-web `npx vite --port
  5175`; deeplink #justsearch://surface/core.search-v3-surface (backendless boot
  ~30s; hash needs a popstate re-dispatch after boot — see the deeplink observation).
