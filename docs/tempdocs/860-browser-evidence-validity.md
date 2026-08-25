# 860 — Browser-automation evidence validity (the rAF / hidden-tab artifact class)

```
status:  PLANNED — rev 2 (2026-08-25) — dispatch update: P3 shipped via #544, P6 has
         run, P1/P2/P4/P5 remain externally gated (PR #404 and the skill-registry-clean
         worktree, per §7.9).
         PLANNED — rev 2 (2026-08-25) — NOTHING IMPLEMENTED. Chartered through plan
         stage only; §7 is the implementation path and awaits a go-ahead.
         REV 2 after adversarial review (APPROVE-WITH-AMENDMENTS, A1-A11, each
         re-verified at source before adoption). The load-bearing correction is
         A1: `capture_measure` is Playwright-only, so D2's refusal covers the
         SOUND leg only — the incident's own channel (`javascript_tool` reads)
         emits no artifact and nothing can refuse it. Three of rev 1's factual
         claims were wrong and are corrected: 5 capture call sites (not 6),
         6 production readers (not 5), 3 `raf` definitions (not 4). rev 1's
         "happy-dom lacks rAF" premise is WITHDRAWN — happy-dom provides it
         (`BrowserWindow.d.ts:1327`), which removes the only worked case for
         §6.9's second principle. Design is §6: a rendering-liveness
         witness stamped into `ui-measure.v1` (D1), refusal at consumption through
         one shared reader that replaces five ad-hoc parsers (D2 — the load-bearing
         part), a time-based latch release adopted at all six rAF-latch sites (D3),
         a fire-time hint on the MCP browser leg (D4), an in-place amendment to the
         existing `harness-for-assertions` carve-out (D5, which does NOT overturn
         owner decision 844 D4), and a bounded re-check of the 859 program's
         browser-sourced claims (D6).
         §0's mechanism is
         CONFIRMED by primary sources (§4): occlusion-driven renderer
         backgrounding stops rendering, and rAF / ResizeObserver /
         IntersectionObserver are one suspended spec step, not three. Two
         theorization findings survive — the `ui-shot` headless leg is not
         exposed (but only because Playwright passes flags this repo does not
         assert), and `visibilityState` is not a usable oracle. One is withdrawn:
         §5.0's off-screen probe measured Playwright's default flags, not the
         platform (§4.3).
created: 2026-08-25
updated: 2026-08-25
owner:   session bccfc163-7b8f-4b1a-b9e4-0c011632d8a1
```

## 0. The incident (proven, not hypothesised)

The MCP browser tab sits **off-screen** (`screenX 1920` on a 1920-wide display). Chrome suspends
`requestAnimationFrame` in hidden / off-screen tabs. `NavigationController.measureCoalesced`
(`modules/ui-web/src/shell-v0/primitives/navigation.ts:206`) sets `measureFrame = true` and clears it
**only from a rAF callback**. In that tab the flag latched permanently, every deferred measure was
swallowed into `measurePending`, and `nav.landmarks` froze at 2 while the DOM held 7 rows.

Consequence chain:

1. A **measured** UX audit reported a J/K traversal stall.
2. An **independent live re-probe reproduced it** — in the same hidden tab. Independence did not
   help, because the invalidating variable was shared by both probes.
3. Two PRs (#534, #535) were driven partly by that manufactured evidence.
4. A visible-window A/B (documented in #535's body) overturned the premise: the walk is correct on
   **both** builds when the window is visible.

| | hidden / off-screen tab | visible window |
|---|---|---|
| `requestAnimationFrame` fires | **no** | yes |
| `measureFrame` (coalescer latch) | **stuck `true`** | `false` |
| `measurePending` | **`true`, never drained** | `false` |
| DOM rows, all non-zero height | 7 | 7 |
| `nav.landmarks.length` | **2** | **7** |
| same, on **pre-fix `HEAD`** | **2** | **7** |

## 1. The defect class

Automation evidence is systematically invalid for anything **rAF-gated** — animations, coalesced
measurement, `IntersectionObserver`, `ResizeObserver` callback timing, focus/scroll settling —
whenever the tab is hidden. The class is three-sided, and only the first side is intuitive:

- it can **fabricate defects** (what happened here);
- it can **fabricate greens** (a measure that never re-ran reports the last good state);
- it can get **real defects dismissed as artifacts** (the inverse error, now newly available as an
  excuse — this is the predictable evasion once this tempdoc lands).

Current mitigation is **prose**: an observations-shard note. The repo's own doctrine
(`CLAUDE.md` → *Before Appending to CLAUDE.md*) says a load-bearing must/never belongs in a hook or
gate (~100% adherence), not more prose (~70%).

## 2. Evidence sources

- PR #534 body — the two "defects" as originally reported.
- PR #535 body — the visible-window A/B table above and the CORRECTION.
- `docs/tempdocs/859-sv3-live-findings.md` — the CORRECTION paragraph in the status block.
- `docs/observations.d/bccfc163-*.md` lines 52 / 54 — the two shard notes (the mitigation this
  tempdoc is meant to replace with enforcement).
- `modules/ui-web/src/shell-v0/primitives/navigation.ts:186-246` — `measureCoalesced` and its
  documented F1 exception; `freshenIfStale`.
- `scripts/jseval/jseval/ui_measure.py:501-620` — `capture_measure`, the `ui-measure.v1` schema and
  its `flags` list (where a validity verdict would be stamped).
- `scripts/jseval/jseval/ui_shot.py:529` — `chromium.launch(headless=True)`: the jseval leg is a
  *different* browser context from the MCP leg, and its exposure must be established, not assumed.

## 3. Design space as chartered (open questions — **resolved in §6**)

- **(a) Evidence-validity preflight** in the ui-shot / browser-leg harness: assert
  `document.visibilityState === 'visible'` **and** rAF-fires-within-N-ms before any capture, and
  **stamp the verdict into `measure.json`** so invalid evidence is refused at collection rather than
  caught in review.
- **(b) Product-side latch**: `measureCoalesced`'s flag should not wedge forever in a hidden tab
  (`visibilitychange` re-arm, or a timeout fallback). Benign for users — it self-heals on foreground
  — but a real fragility, and #535's review already measured the coalescer invariant carrying one
  documented exception.
- **(c) Rule residence**: where does the visible-window rule live so future agents inherit it at
  ~100%, not ~70%? `/ui-check` skill, a `consult-register` recipe, a hook, or the harness itself.

## 4. Research leg — DONE (2026-08-25), verdict GO, primary sources only

> **Reading order.** §5 (theorization) was written *before* this section and is preserved as written,
> including one inference §4.3 withdraws. Read §5 first for how the problem was framed, or §4 first
> for what is actually true; §6 depends only on §4.

Research was warranted on the standard test: the behaviour is defined outside this repo, cannot be
derived from the codebase, and a locally-measured result (§5.0) had already contradicted the
incident's recorded mechanism. Sources are Chromium's own documentation, the Chrome for Developers
blog, the WHATWG/W3C/CSSWG specs, and Playwright's source — no blogs relied on for any load-bearing
claim.

### 4.1 The mechanism, established

| # | Finding | Source |
|---|---|---|
| R1 | "Chrome does not call `requestAnimationFrame()` when a page is in the background. This behavior has been in place since 2011." | [Chrome for Developers — Background tabs in Chrome 57](https://developer.chrome.com/blog/background_tabs) |
| R2 | "If a window is occluded, Chromium treats foreground tabs as if they were background tabs; **rendering stops, and js is throttled**." | [Chromium — Windows Native Window Occlusion Tracking](https://chromium.googlesource.com/chromium/src/+/main/docs/windows_native_window_occlusion_tracking.md) |
| R3 | On Windows, occlusion is computed natively: minimized windows are marked hidden, windows on another virtual desktop occluded; a `WindowOcclusionCalculator` enumerates HWNDs in z-order and subtracts each window's rectangle from the unoccluded desktop region. **The Chromium doc does not discuss windows positioned off the visible desktop at all** (see §4.3). | ibid. |
| R3b | `--disable-features=CalculateNativeWinOcclusion`: "Calculate window occlusion on Windows will be used in the future to throttle and potentially unload foreground tabs in occluded windows." — **rev 2 attribution fix**: this flag is documented in the chrome-launcher doc, *not* in the Chromium occlusion doc R3 cites. | [chrome-launcher — Chrome flags for tools](https://github.com/GoogleChrome/chrome-launcher/blob/main/docs/chrome-flags-for-tools.md) |
| R4 | `--disable-backgrounding-occluded-windows`: "Normally, Chrome will treat a 'foreground' tab instead as *backgrounded* if the surrounding window is occluded (aka visually covered) by another window. This flag disables that." | [chrome-launcher — Chrome flags for tools](https://github.com/GoogleChrome/chrome-launcher/blob/main/docs/chrome-flags-for-tools.md) |
| R5 | `--disable-renderer-backgrounding`: "This disables non-foreground tabs from getting a lower process priority. This doesn't (on its own) affect timers or painting behavior." — i.e. **it is not the rAF flag**, a common misreading. | ibid. |
| R6 | Playwright passes `--disable-backgrounding-occluded-windows`, `--disable-renderer-backgrounding` **and** `--disable-background-timer-throttling` among its default Chromium switches. | [playwright `chromiumSwitches.ts`](https://github.com/microsoft/playwright/blob/main/packages/playwright-core/src/server/chromium/chromiumSwitches.ts) |
| R7 | ResizeObserver delivery is *inside* update-the-rendering: "the following algorithms are invoked as a part of **update the rendering** in the html spec: gather active resize observations at depth … broadcast active resize observations". | [CSSWG — Resize Observer](https://drafts.csswg.org/resize-observer/) |
| R8 | IntersectionObserver likewise: "An Intersection Observer processing step exists as a substep within the '**Update the rendering**' step, in the HTML Event Loops Processing Model." | [W3C — Intersection Observer](https://www.w3.org/TR/intersection-observer/) |
| R9 | Timers degrade rather than stop: ≥1 s per timer in background since Chrome 11, plus Chrome 57's budget throttling after 10 s in background (budget regenerates at 0.01 s/s). Exempt: audible audio, WebSocket/WebRTC pages (the 1 s rule still applies). | [Chrome for Developers](https://developer.chrome.com/blog/background_tabs) |

### 4.2 What this settles

- **The suspension unit is "update the rendering", not "rAF".** R1+R2 with R7+R8 mean rAF callbacks,
  `ResizeObserver` delivery, `IntersectionObserver` notification, the scroll steps and media-query
  evaluation are the *same* suspended step — they do not fail independently and cannot be worked
  around by swapping one for another. §1's list of affected observations is not a guess; it is the
  contents of one spec algorithm. The right name for the class is a **non-rendering page**, and the
  tempdoc should use that rather than "hidden tab".
- **Timers are the one channel that survives** (R9), degraded to ≥1 s and then budget-limited. That
  is exactly why a `setTimeout`-based latch release (§5.3 option 3) *would* eventually fire while a
  rAF-based one never does — and equally why a time-based release (option 1) works: it needs no
  callback at all, only a clock read on the next entry.
- **There is no flag that keeps rAF running in a genuinely backgrounded page.** R5 disposes of the
  usual candidate. `--disable-backgrounding-occluded-windows` (R4) and
  `--disable-features=CalculateNativeWinOcclusion` (R3) prevent a *foreground* tab from being
  *mistaken* for background; neither un-backgrounds a real background tab. So "configure the browser"
  is a partial remedy at best, and only for the occlusion trigger.

### 4.3 Correction — §5.0's probe did not falsify the incident

This is the most important research outcome, and it corrects my own theorization rather than the
incident record.

§5.0 observed a headed Chromium window at `--window-position=3000,0` still running rAF, and inferred
that off-screen position is not the suspension trigger. **R6 invalidates that inference.** Playwright
launches Chromium with `--disable-backgrounding-occluded-windows` and `--disable-renderer-backgrounding`
by default — the probe was run on a browser *immunized against the exact mechanism it was testing for*.
It measured the flags, not the platform.

Re-derived with the sources aligned, the incident's chain is coherent and no longer needs correcting:

> MCP browser = an ordinary Chrome, launched without automation flags → its window sits off the
> visible desktop → **[INFERENCE, not sourced]** Windows native occlusion tracking computes an empty
> unoccluded region for it → the window is treated as occluded → "rendering stops, and js is
> throttled" (R2) → rAF is not called (R1) → `measureCoalesced`'s latch, whose only clear-site is a
> rAF callback, never clears → every subsequent measure is swallowed into `measurePending` →
> `nav.landmarks` freezes at 2.

**rev 2 — the inference is labelled deliberately.** R3's source enumerates minimized windows, other
virtual desktops and a locked screen; it says nothing about a window positioned off the visible
desktop. That an off-desktop window yields an empty unoccluded region is a reasonable reading of the
z-order subtraction algorithm, and it is *not* something the source states. In a section whose entire
subject is an unlabelled inference presented as fact, leaving a second one unlabelled would be
indefensible. What remains *proven* is the downstream half — the #535 A/B measured the latched flag
and the frozen landmark count directly, and R1/R2 explain any non-rendering page. The trigger's exact
identity (off-desktop vs. background tab vs. covered window) is still unestablished, and P6 is the
place to settle it, by probing the real MCP tab.

So §0 stands, §5.0 finding 3 is **withdrawn**, and the two shard notes were right. The residual
refinement is only that the mechanism's name is occlusion-driven backgrounding (of which an
off-desktop window is one cause, a covered window and a non-foreground tab being others) rather than
off-screen-ness as such — which matters for remedy design, because a remedy keyed to *position*
covers one member of that set.

The methodological point is worth more than the correction. The probe produced a clean,
confidently-shaped, wrong answer, and it was wrong in the direction I already found interesting —
"the incident record is under-verified" is a more engaging finding than "the incident record is
right". Nothing about the number 11 was false; the inference drawn from it was, because a variable
nobody had enumerated (the launcher's default flags) differed between the probe and the thing it
stood for. That is precisely this tempdoc's own thesis — *a measurement is only as good as the
preconditions recorded with it* — reproduced, inside the tempdoc, by the agent writing it, two hours
after describing the failure mode. It is retained above rather than quietly edited out for that
reason.

### 4.4 Consequences carried into design

1. **The verdict must be a positive liveness measurement of a rendering update**, not a flag read.
   `visibilityState` measured `visible` in every context §5.0 sampled, and R2's occlusion path is
   about renderer backgrounding, whose web-exposed visibility mapping is platform- and
   version-dependent. Count delivered frames; do not ask.
2. **One probe covers the whole class.** Because rAF, RO and IO share the update-the-rendering step
   (R7/R8), a single frame-liveness probe is a sound proxy for all of them. This is a real
   simplification: the preflight does not need per-API checks.
3. **A graded verdict is justified but the middle band is now explainable.** Context D's ~22 fps was
   an artifact of the probe browser, not evidence of a throttled-rendering regime, so the design need
   only distinguish *rendering* from *not rendering*; a rate floor remains useful as a smoke signal
   for an under-powered or contended machine, not as a separate correctness state.
4. **`ui-shot`'s soundness is inherited, not intrinsic** — it holds because Playwright passes R6's
   flags, a third-party default this repo does not control and does not currently assert. That is a
   materially better argument for a preflight on the harness leg than "insurance": it converts a
   silent dependency on someone else's launcher defaults into a checked one.
5. **`--disable-features=CalculateNativeWinOcclusion` / `--disable-backgrounding-occluded-windows`
   are available levers for a Chrome we launch ourselves**, and unavailable for a Chrome the user
   already has open. This is the fork the design leg must decide on.

## 5. Theorization

### 5.0 A measurement taken while theorizing — and the correction it forces

Before theorizing about remedies it was worth establishing which of this repo's two browser legs is
actually exposed. A standalone Playwright probe (a `data:` page running a self-incrementing rAF loop,
sampled over 500 ms; no backend, no dev stack) was run against four contexts, including
`ui_shot.py:529`'s exact launch arguments:

| context | frames in 500 ms | `document.visibilityState` |
|---|---|---|
| A — headless, single page (**the `ui-shot` leg**) | 29 | `visible` |
| B — headless, page 1 with a second page open | 31 | `visible` |
| C — headless, page 2 | 31 | `visible` |
| D — **headed, window positioned off-screen** (`--window-position=3000,0`) | 11 | `visible` |

Three consequences, and the third is uncomfortable:

1. **The `ui-shot` harness leg is sound.** Headless Chromium runs rAF at full rate, and it does *not*
   throttle a page merely because a second page exists in the context. Whatever we build for it is
   insurance against a future regression, not a fix for what happened. That in turn means the
   incident's remedy cannot be "add a preflight to `ui-shot`" — **the harness that can be gated is not
   the harness that produced the bad evidence.** This is the central tension of the whole tempdoc.
2. **`visibilityState` is a weak oracle.** It read `visible` in *every* context sampled, including the
   throttled one. A preflight that asserts `visibilityState === 'visible'` and stops there would have
   passed context D. Any validity check must be a **positive liveness measurement** — count frames,
   don't ask a flag — and the answer is plausibly **graded** (LIVE / THROTTLED / SUSPENDED), not
   binary: D ran at roughly a third rate, which is fast enough to drain a latch but slow enough to
   change settling-time evidence.
3. **The incident's stated trigger is not established.** — **WITHDRAWN by §4.3.** The probe browser
   was Playwright-launched and therefore carried `--disable-backgrounding-occluded-windows`, so
   context D measured the flag, not the platform. §0's mechanism stands. The paragraph is kept below
   as written because the way it failed is itself evidence for this tempdoc's thesis. "The tab sits off-screen at `screenX 1920`,
   therefore rAF is suspended" is the mechanism recorded in the shard notes and in §0 — and context D
   shows an off-screen window still animating. Off-screen position is therefore *correlation*, not the
   demonstrated cause. The real suspension trigger is more likely "not the foreground tab of its
   window", full occlusion, or minimisation. **The rAF suspension itself is proven** (the #535 A/B
   measured the latched flag directly); only the attribution to off-screen-ness is not. This matters
   for design, because a remedy aimed at window *position* would not cover a backgrounded tab in an
   on-screen window — the more common case. Establishing the true trigger is the first job of the
   research leg, and it should be settled by probing the real MCP tab, not by reasoning.

There is a mild irony worth stating plainly, because it is the tempdoc's own thesis applied to
itself: the correction record in 859 replaced one under-verified causal claim with another
under-verified causal claim. Reproducing a symptom twice in the same invalid context proved the
symptom; naming its cause needed a *third* variable held down, and it did not get one.

### 5.1 Three ways to frame the problem — they imply different work

- **Framing A — "a browser-harness bug."** The automation context is misconfigured; make it visible
  (or make the capture refuse to run when it is not). Cheapest, and if the trigger turns out to be a
  window-manager property, possibly a *configuration* fix with no code at all. Weakness: it treats one
  substrate's quirk as the whole problem, and leaves the product latch and the agent-facing rule
  untouched.
- **Framing B — "a product fragility."** Frontend code latches on a callback the environment is free
  never to deliver. Fixing that removes this instance permanently and is user-facing-benign but
  robustness-positive. Weakness: it does not make *future* rAF-adjacent evidence valid — a fixed
  coalescer still cannot make an animation, an `IntersectionObserver`, or a scroll-settling
  measurement meaningful in a suspended context. It closes one door in a corridor.
- **Framing C — "an evidence-provenance defect."** A measurement artifact was consumed without any
  record of the preconditions under which it is meaningful, so an invalid measurement was
  indistinguishable from a valid one — and two people re-derived the same wrong answer because
  independence of *observer* is not independence of *substrate*. This is the framing that generalises,
  and it is the one that explains why "two independent probes agreed" gave false confidence.

These are not exclusive, and the honest reading is that C is the diagnosis while A and B are two of
its instances. But the framing chosen decides the scope, so it should be chosen deliberately rather
than by accretion.

### 5.2 The rule already existed — and its carve-out swallowed the case

The most useful finding for the residence question (§3c) is that `/ui-check` **already carries** the
governing rule, at `.claude/skills/ui-check/SKILL.md:76` (`rule:harness-for-assertions`):

> Use the instrumented harness for anything you will assert on. Use the browser for things you are
> only looking at.

Followed, it would have prevented the incident outright: the J/K claim was an assertion, and it came
from the browser. So the corrective is **not "write the rule"** — the rule is written, in the right
place, with a trigger that fires at the moment of the choice. Two things went wrong instead, and each
suggests different work:

- **The carve-out absorbed the case.** The rule's own exception list includes *"reading
  console/network output during a live debug"*. The J/K probe presented as exactly that — a live
  debug, driven by `javascript_tool`, reading state out of a running page — not as "asserting from a
  screenshot", which is the shape the prose warns about. The exception was written against a *cost*
  argument (the rule's stated rationale is `claude-in-chrome`'s ~98% share of MCP result bytes,
  tempdoc 844 §6.4), and a cost-shaped exception does not carry a **validity** qualifier. Candidate
  move: the exception needs "…and nothing you read that way becomes an assertion" made explicit, plus
  a validity clause for the debug case itself.
- **~70% is ~70%.** Even a perfectly-worded rule in a loaded skill is prose-tier. The repo's own
  doctrine says the load-bearing half belongs below the prose. The question this tempdoc must answer
  is *what, concretely, can be below it for a tool the repo does not own* — and the honest answer may
  be "less than we want", which is itself worth writing down rather than papering over.

An adjacent observation: the rule is in a skill that must be *loaded* to bind. An agent doing a UX
audit may reasonably not think of it as "editing shell-v0", which is the skill's stated trigger. If
the rule's reach is the problem, moving the trigger (audit/verification work, not just editing) may
be worth more than sharpening the words.

### 5.3 The product latch is a repeated pattern, not one site

`measureCoalesced` is not alone. The same "set a flag, clear it only from a rAF callback" shape
appears at least six times in `modules/ui-web/src`:

| site | flag |
|---|---|
| `shell-v0/primitives/navigation.ts:206` | `measureFrame` (the incident) |
| `shell-v0/primitives/navigation.ts:477` | `scrollRaf` |
| `shell-v0/primitives/adaptiveBar.ts:132` | `rafPending` |
| `shell-v0/primitives/adaptiveDensity.ts:152` | `rafPending` |
| `shell-v0/components/chat/MarkdownBlock.ts:474` | `rafId` (streaming coalescer) |
| `shell-v0/views/search-v3/SearchV3View.ts:1818` | `frame` |

Every one of them wedges in a suspended context; every one self-heals on foreground; none is covered
by a test, because all of them define a local `raf` fallback that degrades to `queueMicrotask` when
`requestAnimationFrame` is undefined — which is precisely the happy-dom path the unit suites take.
**The test environment cannot express the failure**, so no amount of test discipline was going to
catch this. That is a more interesting finding than the wedge itself.

> **rev 2 — the reasoning above is WRONG, the conclusion right.** happy-dom *provides* rAF
> (`BrowserWindow.d.ts:1327`), so the fallbacks never execute and blind nothing — they are dead code.
> The suite is blind because happy-dom's rAF **always fires**. See §6.4; the correction changes how
> P3's test must be written (stub the scheduler), which is why it is not a footnote. Also: three such
> definitions exist, not the six implied here.

Three of those sites also carry a byte-identical copy of the `raf`-with-microtask-fallback definition
and an identical `schedule()` body. Per AHA, the question is whether they share a *reason to change* —
and this tempdoc is evidence that they do: the fix for the wedge is the same fix in all of them, which
is the definition of one reason. So a shared `coalesceFrame` primitive is a live candidate. The
counter-argument deserves equal weight: `navigation`'s coalescer has a documented exception (#535's
F1: `freshenIfStale` bypasses it deliberately) and a trailing-pass re-entry that the others do not
have, so a primitive general enough to hold all six may end up with more options than the duplication
costs. Unifying the *fallback definition* is clearly right; unifying the *scheduling policy* is a
judgement call the design leg should make explicitly rather than by default.

Candidate mechanisms for the wedge itself, in rough order of how little they add:

1. **Time-based latch release.** Record when the latch was set; treat it as clear if more than a frame
   budget of wall-clock has passed. Self-healing by construction, no listener, no timer, no
   subscription to tear down — the invariant becomes "at most one measure per frame *of elapsed
   time*", which is what the coalescer actually means. Testable in happy-dom with a clock.
2. **`visibilitychange` drain.** Explicit and readable, but adds a listener with a lifecycle to every
   adopting site, and only covers the visibility trigger — not, say, a browser that throttles without
   changing visibility (context D).
3. **`setTimeout` racing the rAF.** Works, but two schedulers for one latch invites double-measures
   and is the least honest about intent.

Option 1 looks strongest on present evidence, and it has the property that the *unit* suites can test
it — which none of the others fully have.

### 5.4 On stamping a verdict into `measure.json`

`ui-measure.v1` (`scripts/jseval/jseval/ui_measure.py:561`) is the natural home: it already carries
`viewport`, `theme`, `console_errors` and a `flags` list that downstream gates read. Adding a
`validity` block (frames observed, rate, verdict) is a small, well-precedented change, and the
schema-version question (`v1` → `v2`, or additive) is the design leg's to settle.

Two cautions worth carrying into design:

- **A validity probe that always says VALID is worse than none** — it launders evidence. This is the
  `unreachable-seed-green` shape. Whatever is built needs a seed case that proves the probe
  *discriminates*: a deliberately-suspended context that the probe reports as invalid. Without that
  the stamp is decoration.
- **The stamp's value is mostly on greens.** The red in this incident was eventually caught, expensively,
  by an A/B someone thought to run. A fabricated *green* — a measure that never re-ran and therefore
  reports the last good state — is caught by nobody, because nobody re-probes a pass. If the work is
  justified on "it would have saved #534/#535", that undersells it.

### 5.5 What "enforcement" can actually mean for a tool we don't own

Worth enumerating honestly, since §1's doctrine argument ("gate, not prose") will otherwise be assumed
to have an obvious implementation:

- **Refuse at collection (jseval leg).** Fully available: the harness is ours, the probe is ours, the
  exit code is ours. But per §5.0 this leg was never the problem.
- **Refuse at collection (MCP leg).** Not directly available — the tool is external and a PreToolUse
  hook cannot inspect the tab it is about to drive. What a hook *can* do: fire on the first
  `mcp__claude-in-chrome__*` call of a session and inject the validity procedure at the moment of use.
  That is delivery-tier, not gate-tier: better than a skill line (it fires unloaded), weaker than a
  block. The repo has precedent for exactly this trade (the `pipe-mask-hint` conversion, 618).
- **Refuse at consumption.** Genuinely available and possibly the strongest lever: make the *artifact*
  the gated object rather than the collection. If a claim in a PR body or a baseline row is sourced
  from a measurement, require it to cite a stamped-valid artifact. This is the same shape as the
  existing register gates, and it binds regardless of which browser produced the number.
- **Remove the failure mode from the environment.** If the trigger turns out to be configuration (tab
  foregrounding, window placement), a one-time environment fix beats all of the above for this class —
  and covers the parts of the class no gate can see (animations, IO, scroll settling). It also silently
  rots if the environment is ever rebuilt, which argues for pairing it with a probe that would notice.

The most likely correct answer is a *combination* whose parts sit at different tiers, and the design
leg should say which part is doing the load-bearing work rather than presenting a bundle.

### 5.6 The inverse error, and why it must be pre-empted in the same PR

Once "that was a hidden-tab artifact" is an established move in this repo, it becomes an available
*dismissal* for a real defect an agent does not want to chase. This is the predictable evasion for
whatever lands here, and it is worse than the original failure because it is unfalsifiable in
conversation.

The structural answer is that the verdict must be **stamped, not argued**: if the artifact records
`validity: LIVE`, "artifact" is not available as an excuse, and if it records nothing, the correct
move is to re-probe rather than to adjudicate. Any prose that lands should name this evasion inline,
per the always-loaded-budget rule's own guidance that pre-empting a specific excuse raises adherence
more than restating the rule.

### 5.7 The broader shape this points at

Stated as a candidate invariant, not a conclusion:

> **A measurement artifact must carry the preconditions under which it is valid, and a consumer must
> refuse it when they are unmet.** Independence of *observer* is not independence of *substrate*; two
> probes sharing an invalid substrate agree, and their agreement is the strongest false signal
> available.

This is not browser-specific. The same shape covers benchmark numbers taken with a warm cache, live
evidence taken while the dev-stack lease had lapsed, and quality numbers taken against the compact
chat profile. The repo already carries close relatives as postmortem handles — `static-green ≠
live-working` (the environment did not exercise the thing), `ai-offline-isnt-a-wall` (a tier was
declared unavailable without checking), `unreachable-seed-green` (a check that cannot fail) — and this
incident is the case where the *substrate itself* silently invalidated a measurement that everything
downstream treated as sound. A handle along the lines of `substrate-invalidated-evidence` looks
warranted, though the design leg should judge whether it is genuinely distinct from `static-green ≠
live-working` or a sharper statement of it. If it is distinct, the second sentence — *agreement between
probes on a shared substrate is not corroboration* — is the part worth carrying, because that is what
actually failed here.

A second, smaller generalisation worth recording: **a fallback written to make a test environment work
can hide the very bug the test exists to catch.** Every one of §5.3's six sites degrades rAF to
`queueMicrotask` under happy-dom for a good reason, and the collective effect is that the suite is
structurally blind to rAF-suspension bugs. That is a testing-substrate observation with reach beyond
this tempdoc, and it belongs to §5.7's family rather than to the fix.

### 5.8 Hidden assumptions to check before design settles

- That the MCP tab's suspension trigger is off-screen position (§5.0 #3 — probably false).
- That `visibilityState` is a usable oracle (§5.0 #2 — demonstrably weak).
- That the throttled-but-not-suspended regime (context D) is safe. It drains latches but distorts
  timing; whether the verdict needs a third state is open.
- That the `ui-shot` leg stays sound. It is sound *today*, under today's launch flags and Chromium
  build; nothing currently notices if that changes, which is the actual argument for a preflight there.
- That the incident's blast radius stops at `nav.landmarks`. Six latch sites and every
  animation/`IntersectionObserver`/settling-time observation in a suspended context are in scope; how
  much *prior* browser-sourced evidence in the 859 program shares the defect is unexamined, and a
  bounded re-check of the greens is arguably the highest-value single item here.
- That two PRs were the whole cost. #534's J/K change and its baseline recalibrations were reasoned
  about under the artifact too; whether any of them are wrong (as opposed to merely
  differently-motivated) has not been asked.

### 5.9 Deliberately out of scope

No follow-up tempdoc number is claimed. The two candidates that could have taken one — the shared
`coalesceFrame` primitive (§5.3) and the re-check of prior browser-sourced greens (§5.8) — both belong
to this tempdoc's own thesis and would fragment it. Both were kept in scope by §6 (as D3 and D6).

**rev 2 — the number is stale.** "#861 is next free" was true when written and is not now: 861-866
all exist (`861-agent-process-registry`, `862-shard-multi-writer`, `863-delegate-question-blanking`,
`864-composer-focus-steal`, `865-agent-tool-read-grounding`, `866-agent-file-read-capability`). Any
follow-up must re-check `world-state.mjs` at pick time rather than trusting this line — which is
`tempdocs-are-dated-history` demonstrating itself inside a five-hour-old document.

## 6. Design

### 6.1 Thesis

The defect is not that a browser window was in the wrong place. It is that **a measurement crossed
from collection to conclusion carrying no record of whether the page was rendering**, so an invalid
capture and a valid one were indistinguishable to every downstream consumer — a PR body, a baseline
row, a reviewer, and a second agent re-probing. Independence of observer did not help, because the
invalidating variable was in the substrate both observers shared.

So the design puts the fact on the artifact and the refusal on the consumer, and separately removes
the one product latch that converts a suspended renderer into a *plausible-looking wrong number*
rather than an obvious absence of data. Four parts, at three different tiers, and it is worth saying
up front **which one is load-bearing: D2.** D1 is the substrate D2 needs, D3 removes one instance of
the class, D4 is delivery. A design that presented these as an undifferentiated bundle would be
hiding that only one of them is enforcement.

### 6.2 D1 — a rendering-liveness witness in `ui-measure.v1`

`capture_measure` (`scripts/jseval/jseval/ui_measure.py:501`) already emits the schema every
downstream verdict is derived from, carrying `viewport`, `theme`, `geometry`, `axe`, `statusFacts`,
`console_errors` and a `flags` list. It gains a `validity` block, **additively** — same
`ui-measure.v1` name, one new optional key, because every consumer reads by key and none validates
exhaustively.

The witness is a **positive measurement of a delivered rendering update**, not a flag read: count
frames delivered over a short window and record the count, the window, and the derived verdict. Three
properties matter and each is forced by §4:

- **One probe covers the class.** rAF callbacks, `ResizeObserver` delivery and `IntersectionObserver`
  notification are the *same* spec step (R7/R8), so frame liveness is a sound proxy for all of them.
  Per-API probes would be three ways of measuring one thing.
- **`visibilityState` is recorded but never decides.** It read `visible` in every context sampled in
  §5.0, and the occlusion path (R2) is renderer backgrounding, whose web-exposed visibility mapping is
  platform- and version-dependent. It goes in the artifact as context for a human, not as the oracle.
- **The verdict is four-state, not tri-state (rev 2).** `capture_measure` is deliberately best-effort
  and documented never to raise — correct for `axe` or `statusFacts`, and *wrong* for this one field,
  because a probe that fails open launders exactly the evidence it exists to qualify. But rev 1's
  single `unknown` bucket merged two situations that must not share a fate.

#### 6.2.1 The four states (rev 2)

| State | Meaning | Consumer behaviour |
|---|---|---|
| `RENDERING` | probe ran, frame threshold met | accept |
| `NOT_RENDERING` | probe ran, threshold not met | **refuse** — this is the defect |
| `PROBE_ERRORED` | probe raised or timed out; page state unknown | **refuse**, with an explicit named escape hatch |
| *(key absent)* | capture predates this field | **accept, with a flag** |

Two hazards forced the split, and both were live in rev 1:

- **Availability.** `capture_measure`'s bare `try/except` means a probe that times out on a contended
  3-4-agent Windows box would have produced `unknown` → refused → **every gate on that machine fails
  with no way forward**. That is an availability hazard dressed as discipline, and it is the shape of
  failure that gets a guard disabled wholesale within a week. `PROBE_ERRORED` must be
  distinguishable from `NOT_RENDERING` in the stamp and must carry a documented escape hatch, so a
  machine problem is legible as a machine problem.
- **Legacy captures.** Absent-validity **must** be accept-with-flag, not refuse. This is not a free
  choice: the existing Python fixtures write minimal measure docs with no `validity` key —
  `test_ui_a11y_gate.py:28-33` (`{"axe": {...}}`), `test_ui_proportion_gate.py` at five separate
  write sites (16-20, 137-140, 260, 315, 426), plus `test_ui_critic.py` and `test_ui_trace.py`.
  Refusing on absence turns all of them red for a reason unrelated to what they test. Rev 1's
  "explicit argued decision" framing was illusory; there is one viable answer.

#### 6.2.2 Probe parameters (rev 2)

The probe must be specified, not left to implementation. Required decisions, each to be fixed with a
number in the implementing PR and justified there:

- **N frames over M ms** — the sampling window. It is paid on every capture, so it trades directly
  against ui-shot wall-clock across a multi-step run.
- **The rendering threshold** — how many frames in the window count as rendering. The signal is
  bimodal (≈60 vs 0 per second; §5.0's middle band was a probe artifact, §4.4), so the threshold has
  wide margin on both sides and should be set well clear of zero rather than near full rate.
- **A false-invalid budget** — the explicit tolerance for a contended machine under a long task or GC
  pause. A threshold chosen without this number is a threshold chosen by accident, and the false
  positives land on whoever is running four agents at once.

The tri-state rule this refines is already written into `slice-execution.md`'s post-implementation
checklist: do not conflate unknown with healthy. Rev 2's addition is that you must also not conflate
*two different unknowns* with each other.

A `flags` entry marks an invalid capture so it is legible in the one-line console summary an agent
actually reads, alongside the existing `console-real:` / `axe-NEW:` entries.

### 6.3 D2 — refuse at consumption, through one shared reader

This is the enforcement, and it is available at gate tier because the artifact and its consumers are
all ours.

Five things read `ui-measure.v1` today and **each parses it its own way**: `ui_proportion_gate`
(`_measure_doc`, line 267), `ui_a11y_gate` (line 35), `ui_diff`, `ui_critic`, and `ui_check`'s
pre/post comparison. That duplication is the reason a validity rule cannot simply be written once
today — and it is also the opportunity. The design introduces **one shared loader** in `ui_measure.py`
that all five adopt, which refuses a capture whose verdict is not `rendering`. Consumers stop parsing
JSON and start asking for a *validated* measure document.

Two consequences worth stating plainly:

- Refusal belongs here, not at capture time. A capture that failed its liveness probe is still worth
  writing to disk — it is the evidence that the probe worked. What must not happen is that it
  silently becomes an exit code, a recalibrated baseline row, or a number in a PR body.

#### 6.3.1 The leg asymmetry — rev 2, correcting rev 1's central overclaim

Rev 1 said refusing at the gate "binds regardless of which browser produced the capture." **That is
false, and it was the design's worst sentence.** `capture_measure` is Playwright-only and is the sole
writer of `ui-measure.v1`. The MCP browser leg — `javascript_tool` reads, which is the channel the
incident actually came through — produces **no artifact at all**. There is nothing for a consumer to
refuse, and no amount of gate work creates one.

Stated plainly, because the design must agree with what §5.0 finding 1 already established:

| | jseval / Playwright leg | MCP browser leg |
|---|---|---|
| Produces a `ui-measure.v1` artifact | yes | **no** |
| Exposed to the incident's failure | **no** (inherits Playwright's flags, R6) | **yes** — this is where it happened |
| Enforcement available | gate tier (D1+D2) | **delivery tier only** (D4) |

So the design's two halves do not meet. Enforcement lands on the leg that was already sound;
the leg that failed gets a hint. That is uncomfortable and it is the truth, and rev 1 papered over it
with one sentence.

D1/D2 are nonetheless kept, on their own merits rather than as the incident's fix:

- they close the **fabricated-green** channel for harness evidence — the failure mode nobody catches,
  because nobody re-probes a pass (§5.4);
- they convert Playwright's flag guarantee from an *inherited third-party default this repo never
  asserts* (R6) into a checked one. That is the honest headline for D1/D2: not "this stops the
  incident recurring", but "this stops the sound leg going quietly unsound".

The predictable evasion here is to let the gate work's solidity stand in for coverage of the actual
incident. It does not. Anyone reading this tempdoc as "the hidden-tab problem is now gated" has
misread it.
- The refusal must be a hard, named failure, not a warning. Per `fix-root-causes-not-symptoms`, the
  failure mode to design against is an agent under time pressure reading a warning as noise.

**A refusal path that cannot fire is decoration** (`unreachable-seed-green`). The design therefore
requires a seed case that drives a deliberately non-rendering context through the probe and asserts
the verdict flips — the discriminating half, without which the whole of D1/D2 is a stamp that always
says VALID. The repo already institutionalises exactly this idea for hooks (`agent-hooks.v1.json`'s
`bite` fixtures, enforced by the `hook-integrity` gate); this is the same requirement in the harness.

### 6.4 D3 — release the latch on elapsed time, at the pattern rather than the site

Six sites in `modules/ui-web/src` share the shape "set a flag; clear it only from a rAF callback"
(§5.3). All six wedge in a non-rendering page.

**rev 2 — why the suite misses it, corrected.** Rev 1 claimed the suite is blind because the six
sites degrade `raf` to `queueMicrotask` under happy-dom. That premise is **false**: happy-dom
*provides* `requestAnimationFrame` (`BrowserWindow.d.ts:1327`, `setImmediate`-backed), so the
`typeof requestAnimationFrame !== 'undefined'` guards never take their fallback branch in the unit
suite — they are dead code, and they blind nothing. The suite is blind for the opposite reason: **in
happy-dom, rAF always fires**, so the latch always clears and the wedge cannot occur. Same blind
spot, entirely different cause — and the correct cause changes the test design, which is why it
matters rather than being a footnote. A test that merely runs under happy-dom proves nothing; the
test must **stub `globalThis.requestAnimationFrame`** (or the primitive must take an injectable
scheduler) to construct a page that does not render. Only three such definitions exist, not rev 1's
four: `adaptiveBar.ts:137` and `adaptiveDensity.ts:156` (byte-identical one-liners) and
`navigation.ts:137-142` (a multi-line module-level form).

The mechanism chosen is **rAF primary with a time-based fallback release** — not, as rev 1 wrote,
time-based release as the mechanism. The distinction is load-bearing and rev 1 got it wrong:
"released once more than a frame budget of wall-clock has elapsed" permits a *second synchronous
`measure()` inside a single rAF interval* whenever a long task stretches past the budget, which is
exactly the write→read→write forced layout `navigation.ts:212-220` documents and the coalescer exists
to prevent. Rev 1's mechanism would have re-introduced the cost the code it edits is designed around.

The corrected shape: rAF stays the primary release, and elapsed time is a **fallback at a
deliberately long horizon (≥1 s)** — far beyond any real frame, so it never fires in a rendering
page and cannot cause a double-measure, while still guaranteeing that a non-rendering page eventually
drains instead of wedging forever. It needs no listener with a lifecycle and no second scheduler
racing the first at frame cadence.

**Blast radius differs three ways across the six sites** — they are not equivalent, and the sweep
should not present them as such:

| Sites | Wedge duration in a non-rendering page |
|---|---|
| `navigation.ts` ×2, `adaptiveBar`, `adaptiveDensity` | **permanent** (until foreground) |
| `MarkdownBlock` | until disconnect |
| `SearchV3View` | until pointerup |

Only the first group carries the incident's failure mode in full. A further consequence to weigh
before adopting uniformly: `MarkdownBlock` and `SearchV3View` have **no** `typeof`-guard fallback
today, so adopting a fallback-bearing primitive at those two sites *changes their behaviour in the
test environment*. That is a real change, not a refactor, and it needs its own justification rather
than riding in on the sweep.

This lands as **one shared coalescing primitive** in `shell-v0/primitives/`, adopted by the six
sites. Per AHA the question is whether they share a reason to change, and this tempdoc is the
evidence that they do: one mechanism wedges all six, and one fix repairs all six. The honest
qualifier is that they do not share a *policy* — `navigation`'s coalescer has a trailing re-entry pass
and a deliberate documented bypass (`freshenIfStale`, #535's F1) that the other five lack. The
primitive should therefore own the **latch**, which is the part that is genuinely identical, and
leave scheduling policy to the caller. A primitive that also absorbed the trailing pass would need
options for a difference that only one caller has.

**What this orphans** — deletion belongs to this tempdoc, not a later sweep:

- **three** `raf`-with-`queueMicrotask`-fallback definitions (rev 2 count, not four): the
  byte-identical pair at `adaptiveBar.ts:137` / `adaptiveDensity.ts:156` and the module-level form at
  `navigation.ts:137-142`. They are dead code in the unit suite (happy-dom provides rAF) and
  unreachable in a browser, so they are pure residue;
- the two byte-identical `schedule()` bodies in `adaptiveBar.ts` / `adaptiveDensity.ts`;
- the ad-hoc `ui-measure.v1` readers in `ui_proportion_gate.py` and `ui_a11y_gate.py` (§6.3), which
  become the shared loader rather than surviving beside it;
- the two observation-shard lines that are the *current* mitigation (§2). Prose standing in for a
  mechanism is exactly the residue `retire-with-a-sweep` is about: once the mechanism exists, the
  notes are closed by this lane, not left to accrete false authority.

To be explicit about the user-facing stake, since it should not be oversold: the latch self-heals on
foreground, so no user has been harmed by it. The justification is robustness plus the fact that it
manufactured a false defect and two PRs' worth of work — not a shipped bug.

### 6.5 D4 — deliver the rule at the browser leg, at the tier that is actually available

For the MCP browser there is no gate: the tool is external, and a `PreToolUse` hook cannot inspect
the tab it is about to drive. What *is* available — and has direct precedent in this repo — is
fire-time delivery: `agent-hooks.v1.json` already binds `PreToolUse` on
`matcher: "mcp__justsearch-dev__.*"` for `mcp-session-inject`, so MCP-matched hooks work here and are
registered.

The design adds a hint hook on `mcp__claude-in-chrome__.*` that fires **once per session, on any
call** — non-blocking. It carries the harness-first rule and the liveness probe to run before
trusting anything read from the page.

**rev 2 — the loopback predicate is withdrawn as unimplementable.** Rev 1 scoped the hook to loopback
targets, which sounded precise and cannot be built: `javascript_tool` takes `{action, tabId, text}`
and `read_page` takes `{tabId, …}` — **neither carries a URL**. Only `navigate` does. Since
`javascript_tool` reads are the incident's actual call shape, a URL-scoped predicate would have
missed the very case the hook exists for, while appearing well-targeted. A hook cannot see which
origin a tab is on.

So the honest form is a session-scoped tax: **one hint, once, on the first `claude-in-chrome` call of
a session, whatever it is.** It occasionally fires on genuinely external work, which is the price of a
predicate that can actually be evaluated, and one message per session is small enough to pay.
Rev 1's version was better-targeted only in prose.

**rev 2 — the `bite` claim is deleted.** Rev 1 said the register's `bite` requirement would supply the
hook's discriminating fixture. It would not: `hook-integrity`'s enforcer skips non-blocking hooks
outright (`enforcer.mjs:216`, `if (entry.role !== 'blocking') continue;`), and the closest precedent,
`mcp-session-inject`, is `role: "advisory"` with no bite at all. An advisory hook gets **no** automated
proof that it fires. The design therefore requires a **direct unit test** for the hook — fires once,
stays silent on the second call, emits the intended text — because otherwise D4 ships with no evidence
it works, which is the `unreachable-seed-green` shape applied to the one part of the design that
covers the failing leg.

**rev 2 — wiring.** Hook bindings live in a gitignored local settings file, so registering in
`agent-hooks.v1.json` alone binds the hook on nobody's machine. The change must also run
`node scripts/codegen/gen-agent-hooks-wiring.mjs --emit-local-example` and commit the regenerated
`.example`, or D4 is inert everywhere except the authoring worktree.

Two options are named and **rejected**:

- *Block the first loopback browser call outright.* Gate-tier, and wrong: it would tax the legitimate
  exploratory uses the rule explicitly protects, to catch a mistake that D2 already prevents from
  becoming a verdict.
- *Have the hook rewrite `javascript_tool`'s code via `updatedInput` to self-stamp every read.* The
  mechanism exists (`mcp-session-inject` does exactly this kind of rewrite) and it is too clever: it
  silently changes the semantics of code the agent wrote, alters return shapes, and creates a second,
  invisible authority for what a probe means. Rejected on legibility, not feasibility.

### 6.6 D5 — the rule's residence: amend, do not relocate

`harness-for-assertions` already exists, in the right place, with the right words
(`.claude/skills/ui-check/SKILL.md:76`). Its placement was an **owner decision** — tempdoc 844 D4,
option (b), decided 2026-08-19 on the ground that `CLAUDE.md`'s always-loaded budget had two bytes of
headroom and the skill's trigger fires at the moment of the choice. Nothing here overturns that, and
this tempdoc should not be read as re-litigating it.

What is new is an axis D4 did not weigh. D4's rationale was **cost** (`claude-in-chrome`'s share of
MCP result bytes, 844 §6.4); the failure of 2026-08-25 is **validity**. And the case escaped through
the rule's own carve-out: the exception list permits *"reading console/network output during a live
debug"*, which is exactly what the J/K probe presented as. A cost-shaped exception carries no validity
qualifier, so the exception swallowed the case. The amendment is small and in place, per one-canonical-
file: the carve-out gains the clause that nothing read that way becomes an assertion without a
liveness check.

One structural finding belongs here rather than in the fix. The tier register
(`docs/reference/contributing/tier-register.md`) exists to record which tier enforces each load-bearing
prose rule — and `harness-for-assertions` **has no row in it**, because the register's scope is
`CLAUDE.md` plus `.claude/rules/*.md`. A load-bearing rule that was deliberately placed in a skill to
respect the always-loaded budget is therefore invisible to the register whose entire job is this
accounting, and the budget pressure that put it there guarantees more rules will follow it out. The
register's scope should follow load-bearing-ness rather than file location. Whether to widen it here
or charter it separately is a sequencing call for §7 — but it should not be left unnamed, since it is
the reason nobody noticed this rule had no enforcement tier.

### 6.7 D6 — a bounded re-check of what the artifact already touched

The incident's blast radius was never scoped. Browser-sourced evidence in the 859 program includes
greens as well as the one red that was eventually caught, and a fabricated green is caught by nobody
because nobody re-probes a pass (§5.4). #534's baseline recalibrations were reasoned about in the same
window. This is a bounded, finite re-check of browser-sourced claims from that program, and it is in
scope for the same reason the sweep in §6.4 is: this tempdoc chartered a defect *class*, and the
already-committed consequences of the class are part of it. Bounded means bounded — the 859 program,
not an open-ended audit of every past measurement.

### 6.8 Explicitly out of scope

Launching our own Chrome for the MCP leg, or applying `--disable-backgrounding-occluded-windows` /
`--disable-features=CalculateNativeWinOcclusion` to it: those flags are only available to a browser we
launch, and they would not un-background a genuinely backgrounded tab in any case (R5). Per-API RO/IO
probes: one spec step, one probe. A `ui-measure.v2` schema fork: additive works, and forking a schema
to add one optional key would be the representation-drift class this repo has a register to prevent.

### 6.9 Reach — the principle this is an instance of

> **A measurement artifact must carry the preconditions under which it is valid, and its consumer
> must refuse it when they are unmet. Agreement between two probes sharing a substrate is not
> corroboration.**

The second sentence is the part that actually failed here, and it is the part with no existing home in
the repo's rules. It is also what makes this distinct from its nearest relatives rather than a restatement
of them: `static-green ≠ live-working` says the environment did not exercise the thing;
`ai-offline-isnt-a-wall` says a tier was declared unavailable without checking;
`unreachable-seed-green` says a check could not fail. None of them covers *the substrate silently
invalidated a measurement that everything downstream, including a second independent observer, treated
as sound*. A handle along the lines of `substrate-invalidated-evidence` is warranted, with the
postmortem entry carrying this incident as its worked case.

**Where else it applies, today, in this repo** — named, not built:

- benchmark and pipeline-profiling numbers taken against a warm cache or a contended machine
  (`interrogate-results` states the discipline; no artifact records the precondition);
- live evidence gathered while a dev-stack lease had lapsed or a takeover intervened;
- quality-sensitive evidence taken under the compact chat profile — `CLAUDE.md` already draws this
  distinction in prose, and nothing stamps which profile produced a given result;
- `ui-proportion-baseline` rows recalibrated from a capture whose validity was unrecorded, which is
  literally what #534 did.

That last one is an **existing violation of the principle by the very mechanism this design extends**,
which is the strongest available argument that the principle is real rather than tidy. It is also
deliberately not being generalised now: D1/D2 build the evidence-validity seam for one artifact type,
and the other three are recorded as candidate scope, not as work. Recognising a principle and building
general structure for it are separated on purpose.

**What would show it earning its keep:** a capture stamped invalid and *refused* by a gate at least
once outside its own seed test; and a defect report that cites a stamped-valid artifact instead of
arguing about whether the evidence was real.

**Retirement condition:** if over a sustained period no capture is ever stamped invalid and no consumer
ever refuses one — outside the seed fixture — then the probe is guarding a condition that no longer
occurs, and it should be deleted rather than kept as apparatus. The same applies if the substrate
changes such that a non-rendering page cannot arise. A principle whose only evidence is its own
fixture has become self-justifying.

**A second, smaller principle — WITHDRAWN in rev 2.** Rev 1 proposed *"a fallback written to make a
test environment work can blind the suite to the bug it exists to catch"*, resting entirely on the
claim that the six sites' `queueMicrotask` fallbacks blind the suite. They do not: happy-dom provides
rAF (`BrowserWindow.d.ts:1327`), the fallbacks never execute, and the suite's blindness comes from
happy-dom's rAF *always firing* (§6.4). The principle had exactly one worked case and the case
evaporated on inspection, so it is withdrawn rather than re-aimed at a different example — inventing a
new case to keep a principle alive is how self-justifying apparatus gets built, which §6.9 itself warns
against.

What survives is narrower and belongs to P3 rather than to the principles section: **a test
environment that always satisfies a precondition cannot test the code path where it fails.** That is
a fact about happy-dom's rAF, not a general law, and it is already actionable in the one place it
applies (stub the scheduler).

The *first* principle is untouched by this — its worked case is the incident itself, which §4
established from primary sources.

## 7. Plan

Nothing below is implemented. P1 must land before P2 (the gate needs a field to read). **Rev 2 drops
the P2→P6 dependency**: P6 needs a rendering browser window, not the refusal machinery, so it can run
first — and it is the item that covers the leg the incident actually came through.

**Rev 2 — cross-worktree ordering constraints.** Parallel worktrees make this a scheduling problem,
not just a dependency one:

| Wave | Items | Constraint |
|---|---|---|
| now, in parallel | **P6**, **P3** | No collisions; #534/#535 are merged. P3 is frontend-only, P6 touches no source. |
| after | **P1+P2** | `ui_check.py` is touched by 4 live worktrees. Start after PR #404 lands, **or** start now with the `_write_trace` reader migration deferred to a follow-up commit in the same PR. |
| last | **P4+P5** | Both collide with the skill-registry-clean worktree, which touches `agent-hooks.v1.json` **and** `.claude/skills/ui-check/SKILL.md` — exactly P4's and P5's two files. Wait for it to merge. |

### 7.0 What already exists — investigated, so the plan extends rather than invents

| Need | Existing infrastructure | Verdict |
|---|---|---|
| One place to stamp the witness | `ui_measure.capture_measure` — the single producer of `ui-measure.v1`, funnelling **5** call sites (rev 2 count: `ui_check.py:250/2060/2140`, `ui_fuzz.py:51`, plus `experiments/route_mock_home.py:97`, which is an experiment, not a product path) | Extend. One function, one edit, every capture covered. |
| A place to put the verdict | the `flags` list already read by consumers and printed in `format_console_shot` | Extend additively, no new channel. |
| A refusal point | **6** production readers (rev 2 count): `ui_proportion_gate`, `ui_a11y_gate`, `ui_diff`, `ui_critic`, `ui_check._write_trace`, and `ui_fuzz.cell_anomalies:58-64` — each parsing the artifact separately | **Consolidate**, don't add a seventh reader. |
| Python test home | `scripts/jseval/tests/test_ui_measure.py`, `test_ui_a11y_gate.py`, `test_ui_diff.py`, `test_ui_critic.py`, plus `tests/fixtures/` | Reuse. |
| Hook on an MCP tool | `agent-hooks.v1.json` binds `PreToolUse` / `mcp__justsearch-dev__.*` → `mcp-session-inject` | Direct precedent. |
| Proof a guard discriminates | the register's `bite` fixtures, enforced by the `hook-integrity` gate | Reuse the idea for the harness probe too. |
| Shared hook plumbing | `scripts/agent-analytics/lib/hook-base.mjs` | Reuse; do not hand-roll stdin/atomic-write. |

No new gate directory is warranted: this is a refusal *inside* existing gates, not a new discipline
rule with its own kernel entry.

### 7.1 P1 — the rendering-liveness witness (D1)

Add the probe and the `validity` block to `capture_measure`. Tri-state verdict; a probe that could
not run yields `unknown`, never `rendering`. Record the frame count, the window, and
`visibilityState` as context. Add the `flags` entry and surface it in the console summary.

*Validation.* Unit-level in `test_ui_measure.py`: the block is present and well-formed; a synthetic
capture with zero frames yields the invalid verdict; a probe error yields `unknown`, **not** valid —
that last case is the one that matters, and it is the exact shape of the fail-open bug the design
warns about. Plus a live capture against a real page showing a valid verdict, so the happy path is
not only synthetic.

### 7.2 P2 — one shared reader that refuses (D2) — *the load-bearing item*

Introduce the validated loader in `ui_measure.py`; migrate all five consumers onto it; **delete**
`ui_proportion_gate._measure_doc` and `ui_a11y_gate`'s equivalent in the same change (teardown rides
along — §6.4's orphan list is not a follow-up). Refusal is a hard, named failure with a message that
says what to do, not a warning.

**rev 2 — refusal is not uniform across the six readers, and must be decided per reader.** Only two
are gates today; the other four (`ui_diff`, `ui_critic`, `ui_fuzz.cell_anomalies`,
`ui_check._write_trace`) have **no refusal path at all** and would *newly acquire* one. Handing them
a hard failure is a behaviour change smuggled in as consolidation. In particular `ui_diff` is an
iteration signal — an agent comparing two captures while working on a surface — so a flag is right
there and a refusal is not; a diff that refuses to render is strictly worse than one that renders and
says the inputs were suspect. The per-reader decision (refuse vs. flag) is part of P2's work and each
choice gets a one-line justification in the PR.

*Validation.* Red-before/green-after per consumer: a stamped-invalid fixture must make each **gate**
fail and each flagging reader mark. **The seed case is the acceptance criterion, not a nice-to-have**
— a deliberately non-rendering capture must flip the verdict, or P1+P2 are a stamp that always says
VALID (`unreachable-seed-green`). Absent-validity ⇒ accept-with-flag and `PROBE_ERRORED` ⇒ refuse-with-
escape-hatch are fixed by §6.2.1, not open questions; both get a pinning test, and the existing
fixture files (`test_ui_a11y_gate.py`, `test_ui_proportion_gate.py` ×5 sites, `test_ui_critic.py`,
`test_ui_trace.py`) must stay green **without being edited** — if they need editing, the absent-key
rule was implemented wrong.

### 7.3 P3 — the shared frame-coalescing primitive (D3)

New primitive in `modules/ui-web/src/shell-v0/primitives/` owning the **latch only**, with time-based
release. Adopt at all six sites (`navigation.ts` ×2, `adaptiveBar.ts`, `adaptiveDensity.ts`,
`MarkdownBlock.ts`, `SearchV3View.ts`), leaving `navigation`'s trailing-pass and `freshenIfStale`
bypass as caller policy. Delete the four duplicated `raf` fallback definitions and the two duplicated
`schedule()` bodies in the same change.

*Validation.* A test that **stubs `globalThis.requestAnimationFrame`** (or injects a scheduler) so no
frame is ever delivered, then asserts the fallback releases the latch and the next measure runs.
Stubbing is mandatory, not stylistic: happy-dom's rAF always fires (§6.4), so a test that merely runs
under happy-dom passes without exercising anything. Mutation-check it: revert the release and the
test must go red. A second assertion must pin the ≥1 s horizon — that a *rendering* page never takes
the fallback path, so the fix cannot re-introduce the double-measure §6.4 describes. Then
`npm run typecheck` and `npm run test:unit:run`, and `./gradlew.bat build -x test`.

`MarkdownBlock` and `SearchV3View` are streaming-path coalescers; their adoption needs a live look at
streaming rendering, which is model-dependent. If the AI runtime is unavailable, adopt them and say so
explicitly rather than silently narrowing the sweep to four sites — `tempdoc-is-your-contract`.

### 7.4 P4 — the browser-leg hint hook (D4)

New hint hook, `PreToolUse` on `mcp__claude-in-chrome__.*`, fires **once per session on any call**
(rev 2: no loopback predicate — `javascript_tool`/`read_page` carry no URL, §6.5). Register it in
`agent-hooks.v1.json` as `role: "advisory"`, built on `hook-base.mjs`, and run
`node scripts/codegen/gen-agent-hooks-wiring.mjs --emit-local-example` committing the regenerated
`.example` — without that the hook binds on one machine only.

*Validation.* A **direct unit test** — rev 2: `hook-integrity` will *not* prove this hook fires, since
its bite check skips non-blocking hooks (`enforcer.mjs:216`). The test asserts it emits on the first
`claude-in-chrome` call, stays silent on the second, and carries the intended text. `hook-integrity`
still runs, for wiring and load.

### 7.5 P5 — the rule amendment and the register gap (D5)

Amend the `harness-for-assertions` carve-out **in place** in `.claude/skills/ui-check/SKILL.md`
(one canonical file, no forked variant), adding the validity qualifier to the live-debug exception
and naming the inverse-error evasion inline (§5.6). Add the new handle
(`substrate-invalidated-evidence`) to `docs/reference/contributing/agent-postmortems.md` with this
incident as its worked case, and its handle-only entry to `.claude/rules/agent-lessons.md`'s list —
the postmortem file is the authority, the rules file carries only the handle, so the two cannot drift.

The tier-register scope gap (§6.6) is **a decision, not a task**: whether the register should cover
load-bearing rules resident in skills. Recommendation is yes — the budget pressure that pushed this
rule into a skill will push others — but it changes a governed artifact's contract and a gate's
scope, so it is put to the owner rather than decided here. It does not block P1-P4.

### 7.6 P6 — bounded re-check of the 859 program's browser-sourced claims (D6)

Enumerate the claims in the 859 program sourced from the **browser leg** rather than the harness —
greens included, since a fabricated green is caught by nobody — and re-establish each in a rendering
context.

**rev 2 — right-sized by substrate, not by calendar window.** Rev 1 pulled in #534's baseline
recalibrations because they happened in the same window. That is the wrong criterion, and it
contradicts §6.3.1: those numbers came from a `jseval … --fixtures` capture (stated in #534's own PR
body, which also records `ui-proportion-gate` → exit 0 against them) — the Playwright leg, which §4
established was never exposed. They are **dropped from P6**. Scope is defined by *which leg produced
the claim*, and only browser-leg claims qualify.

**rev 2 — P6 also settles the open trigger question.** §4.3's off-desktop→occlusion step is labelled
inference; probing the real MCP tab for its actual visibility/liveness state closes it, and P6 is
already there with a rendering window open.

**rev 2 — notify downstream if anything is withdrawn.** Tempdocs 863, 864 and 865 exist and descend
in part from 859 §7 claims. If P6 withdraws a claim any of them rests on, that tempdoc must be
notified in the same pass — a withdrawn premise silently left standing under a live tempdoc is how
#534/#535 happened in the first place.

Bounded means the 859 program's browser-leg claims, not an open-ended audit. Anything found outside
it goes to the observations inbox, not into this lane.

### 7.7 Verification gate for the whole tempdoc

- `./gradlew.bat build -x test`; `cd modules/ui-web && npm run typecheck && npm run test:unit:run`;
  the jseval python suite for the touched modules; the full kernel + ui-web gate set — **the full
  suite, not a hand-picked subset** (`subset-isnt-the-suite`).
- A live `jseval ui-shot` run producing a stamped-valid capture end to end.
- The two discriminating seed cases (P2, P3) green *and* mutation-checked — each must go red when the
  thing it guards is reverted.
- The orphan list in §6.4 is empty: grep the retiree's names and confirm no residue
  (`retire-with-a-sweep`).
- Independent review by an agent that did not implement (`independent-review-required`). Given this
  tempdoc's own subject, the reviewer's specific charge is to check that the validity probe can
  actually fail and that no verification tier was declared unavailable without being tried.

### 7.8 Orchestration

P1+P2 are one coherent bundle (schema producer + its consumers) and should go to a single worker —
splitting them would leave a stamped field nothing reads. P3 is a self-contained frontend refactor
with a crisp acceptance test, well-suited to a separate worker in parallel. P4+P5 are small and
governance-shaped; they can share a worker. P6 is investigation, not implementation, and should not
be bundled with code. Every subagent gets an explicit `model` and a self-contained brief demanding
primary-source `file:line` evidence; the orchestrator judges returned evidence and keeps merge and
publication decisions in the main loop.

Per the wave table in §7, **P3 and P6 dispatch first and in parallel**. P6's worker needs a visible
browser window and must be told so explicitly in its brief — an agent re-probing the hidden-tab
artifact from a hidden tab is the failure this whole tempdoc documents, and it is exactly the mistake
a brief that omits the precondition invites.

### 7.9 State

**Dispatch state (2026-08-25, set by the orchestrator after rev 2 was accepted).** The charter agent's
work ends here; dispatch is the orchestrator's.

| Item | State |
|---|---|
| **P3** | dispatched |
| **P6** | queued for the next dev-stack window — re-establishing claims needs a rendering context against live records. The visible-window precondition (§7.8) is in its brief. |
| **P1+P2** | waiting on PR #404, per the wave table |
| **P4+P5** | waiting on the skill-registry-clean worktree to merge |

- **BLOCKED ON OWNER:** the §7.5 tier-register scope decision — whether the register should cover
  load-bearing rules resident in skills. A governance contract change, not an implementation choice;
  it blocks none of P1-P6.
- **PROCEEDING:** P3. Everything else is scheduled, not stalled.
