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
