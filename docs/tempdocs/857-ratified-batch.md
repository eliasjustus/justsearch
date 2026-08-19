---
number: 857
title: The ratified batch — run-spine navigation ported, surface-audience honesty, pane closes on conversation switch
status: PR-A IMPLEMENTED (2026-08-19) AND PR-B IMPLEMENTED (2026-08-19). Design is rev 2,
  adversarially reviewed. PR-A (§10 "the run-spine navigation port", rows PR-A1-PR-A6) is implemented
  in full, with the rev 2 amendments A1-A10 and one recorded deviation (the `runPrompt` anchor carries
  a `:hold` suffix — see §13). Its live-verification rows (§10) are NOT yet run: they need the shared
  dev stack and are handed to the next stack session, A9's ungated `measure()` cost being the one
  genuinely open question. PR-A merged to `main` first (#516). PR-B (§10 "audience honesty + ledger
  retirement + pane close", rows B1-B5) is implemented in full and merged into this branch on top of
  PR-A (the two PRs touch disjoint files, confirmed by a conflict-free merge of `SearchV3View.ts`
  against PR-A's `Sv3Main.ts`/`navigation.ts`/`keyboardHandler.ts` changes): `CorePlugin.ts` audience
  flip (health/activity USER, logs untouched OPERATOR), `check-surface-composition.mjs`
  `KNOWN_PARITY_DRIFT` emptied (both adverse half-states captured in the PR body per review B1),
  `go-to-activity` action added beside `go-to-health` with `governance/sandbox-coverage.v1.json`'s two
  reach rows updated, `listSurfacesByAudience()` and its test deleted, and the switch-guarded
  `closePane()` added at `onSessionSelect` + `openBranch` (the `lastVisitedAt`-not-`lastActiveAt`
  correction confirmed against source). Both PRs' code-comment self-references were re-stamped from
  854 to 857 in their own diffs (PR-A's re-stamp landed with #516; PR-B's landed in this branch).
  RENUMBERED 854 → 857 (2026-08-19). This design was authored, reviewed and implemented as **854**;
  every commit message on the branch still carries that scope, because history is history. It was
  renumbered when `854-fusion-residue-lane.md` MERGED TO `main` first (PR #517, commit `b816b98e`) and
  took 854 in published history. 855 and 856 were already claimed by the settings-window and
  merge-attribution worktrees, so 857 is the next free number. The ported code comments were
  re-stamped to `857 PR-A` in the same change; `SearchV3View.ts:1854-1856` is a line number, not a
  reference, and is deliberately untouched.
  Worth recording for the next agent who hits this: `check-tempdoc-numbers` did NOT catch the
  collision. It compares claims ACROSS worktrees, so once the competing doc merged, this tree held
  both `854-fusion-residue-lane.md` and `854-ratified-batch.md` and the check still reported OK —
  nothing mechanical would have stopped two 854s reaching `main`.
  Rev 2 folded an adversarial review: three BLOCKING corrections to PR-A (A1 the `active()` deadlock,
  A2 the unstable `.scroller`, A3 the structurally blind test tier) and seven amendments (A4-A10).
  PR-B was unchanged by the review; D4's route enumeration and D3's reframing both survived
  independent re-derivation.
created: 2026-08-19
updated: 2026-08-19
owner-decisions: D1-D4 ratified by the owner 2026-08-19 (recorded verbatim in §1)
related: 852 (the window cutover — this batch's parity ledger lives there; S8-S11 sweep obligations
  discovered here are recorded in §8), 851 (search-v2 retirement + `check-window-cutover`),
  849 (evidence reader — the late-match upgrade path this design proves compatible),
  565 §13/§19/§21 (the run spine + the NAVIGATION authority whose *2nd adopter* this design is),
  814 §D4 (top-edge anchoring), 621 Phase 5 (the spine's pure-helper extraction),
  855 (settings window + rail slimming — the reason D3 must not add a rail entry)
scope-note: search work (query trail, pinned searches) is OUT — deferred to the owner's separate
  search deliberation. 852 slices S7-S10 and HC-palette work are OUT.
---

# 857 — The ratified batch

## 1. The owner's decisions (2026-08-19), verbatim in substance

- **D1 — parity drops ratified.** Autonomy dial, retrospective drawer, replay bar, resume prompt are
  consciously dropped: they die with `UnifiedChatView`'s retirement and are not ported. Query trail
  and pinned searches are **deferred, not dropped** — they belong to the owner's separate search
  deliberation and are recorded as *undecided*.
- **D2 — the run spine IS ported** to Search v3: "UnifiedChatView's keyboard-landmark navigation of a
  run's steps — the only keyboard nav for run steps in the product; kept for accessibility."
- **D3 — Health and Activity become USER-visible**: the FE registration flips `OPERATOR` → `USER` to
  match the Java catalog's existing `USER`; the two `KNOWN_PARITY_DRIFT` ledger entries are deleted in
  the same change.
- **D4 — the Search v3 evidence pane CLOSES on conversation switch**, on every route that changes the
  active conversation.

Everything below is the design for exactly these, verified against `main` @ `5d6d99b2` (post #509).

---

## 2. Research verdict (the `/research` question)

**No internet research pass is warranted.** Reasons, in order of weight:

1. Every load-bearing claim in this design is repo-internal and citable at `file:line`. Nothing here
   depends on an external API, a moving spec, or a third-party library decision.
2. The accessibility mechanism being ported is not a novel widget pattern. It is native `<button>` /
   real DOM focus movement (`el.setAttribute('tabindex','-1'); el.focus({preventScroll:true})`,
   `modules/ui-web/src/shell-v0/primitives/navigation.ts:170-171`) plus `scrollIntoView`. There is no
   roving-tabindex composite, no custom `role` widget, and therefore no WAI-ARIA APG adjudication to
   fetch. §5.7 keeps the one exception explicit.
3. The repo already runs a stronger oracle than external prose: measured axe-core +
   shadow-piercing contrast audits on a live stack (`docs/tempdocs/853-ux-audit-remediation.md:20-24`).
   A design claim that would be settled by reading a spec is better settled by running that audit.
4. No external code, text or asset is copied, so the license/attribution CI lane is not engaged.

**Conditional re-open:** if the owner elects Tier 2 (§5.7 — the visual minimap), the ported thumb is a
genuine `role="scrollbar"` ARIA widget with `aria-valuemin/max/now`
(`modules/ui-web/src/shell-v0/views/UnifiedChatView.ts:3346-3365`). *That* is a pattern worth checking
against the WAI-ARIA APG before re-authoring. Tier 1 is not.

---

## 3. D1 — the ratified dispositions (record only, no code)

No code lands for D1. It is a **record**, and its home is 852's parity ledger (which lives with the
orchestrator; `docs/tempdocs/852-sv3-promotion.md:9-12` says so explicitly). §8 specifies the exact
text to add there.

| Ledger item | Disposition | Why it is safe to leave uncoded |
|---|---|---|
| Autonomy dial | **DROPPED** | Dies with `UnifiedChatView` in 852 S8-S11. No successor consumer. |
| Retrospective drawer | **DROPPED** | Same. `components/RetrospectivePanel.ts` becomes an orphan of the sweep — recorded in §8 so the sweep slice deletes it rather than leaving residue (`retire-with-a-sweep`). |
| Replay bar | **DROPPED** | Same. |
| Resume prompt | **DROPPED** | Same. |
| Query trail | **DEFERRED — undecided** | Owner's separate search deliberation. Note the search-v2 implementation (`views/search-v2/queryTrail.ts`) was already deleted with the window (`docs/tempdocs/851-search-v2-retirement.md:27-31`), so "deferred" means *un-designed*, not *un-built-yet-specified*. |
| Pinned searches | **DEFERRED — undecided** | Same. |
| **Run spine** | **PORTED** — §5 | The only ratified port. |

The distinction matters for the sweep: a DROPPED item's fingerprints get deleted with the retiree; a
DEFERRED item's do not exist yet and must not be invented by an implementer reading the ledger.

---

## 4. What the "run spine" actually is (the research finding that shapes D2)

The name covers **two separable mechanisms**, and the design turns on separating them.

**(a) The minimap** — `<nav class="run-spine" aria-label="Run timeline — jump to a turn">` rendered by
`UnifiedChatView.renderRunSpine()`
(`modules/ui-web/src/shell-v0/views/UnifiedChatView.ts:3264-3443`). It contains: a
`role="scrollbar" tabindex="0"` drag thumb (`:3346-3365`), one `<button class="run-spine-node">` per
step wrapping `<jf-run-node density="minimal">` (`:3423-3440`), aggregated cluster buttons
(`:3384-3394`), ~170 lines of view-local CSS (`views/unifiedChatStyles.ts:234-405`), and its own grid
track generated from `CONVERSATION_ZONES` (`views/unifiedChatRequest.ts:151-179`, spine entry `:153`)
through `composeGridStyles`.

**(b) The J/K keyboard landmark navigation** —
`UnifiedChatView.onConversationKeydown()` (`views/UnifiedChatView.ts:4802-4830`), a window listener
added at `:955` and removed at `:1217`. Bare `j` (forward) / `k` (back), no modifiers, clamped at both
ends (no wrap). It descends nested shadow roots to the truly-focused element and bails on
`INPUT`/`TEXTAREA`/`isContentEditable` (`:4808-4817`; note it omits `SELECT` — §5.5 A5). It indexes **`this.nav.landmarks`** — every
measured `[data-item-id]` element in the reading column — and calls `nav.jumpTo(id)`.

**This is the mechanism D2's gloss names**, and it is the accessibility asset: `jumpTo`
(`primitives/navigation.ts:152-176`) opens every ancestor `<details>`, honours
`prefers-reduced-motion`, `scrollIntoView({block:'center'})`, then **moves real DOM focus to the
target** so a keyboard or screen-reader user lands on the content.

**The exclusivity claim is verified.** A full-tree sweep of `modules/ui-web/src` for bare `j`/`k`
handlers finds run-step navigation only at `views/UnifiedChatView.ts:4804`. The other hits navigate
different things: advisory inbox rows (`components/advisory/AdvisoryInboxDrawer.ts:382-386`), command
palettes (`commands/CommandPalette.ts:323`, `views/search-v3/Sv3Palette.ts:432`), and
popover/menu/tab lists. `primitives/navigation.ts:268` is a passive listener that only releases a pin.

**Two defects the retiree carries, which the port must not inherit:**

- It is a **raw window listener, not registered in `commands/KeybindingRegistry.ts`**, so it is
  invisible to the palette and absent from the Help shortcut table, which documents only Ctrl/⌘+K,
  Enter, Esc, Ctrl+Z, Ctrl+Shift+Z (`views/HelpSurface.ts:65-77`). The only keyboard nav for run steps
  in the product is also the least discoverable thing in it.
- It is gated on `affordance === 'agent' && wideZone` (`:4803`), so a narrow viewport has **no**
  run-step keyboard nav at all — the gate exists because the *minimap* needs a wide gutter, and the
  keyboard nav was gated along with it for no reason of its own.

---

## 5. D2 — the design: port the navigation, not the minimap

### 5.1 The scope call, stated plainly

**Port mechanism (b) in full. Do not port mechanism (a) in this batch.**

The reason is not effort. It is that (b) is *self-sufficient as an accessibility feature* and (a) is
*not portable without new layout work that collides with unsettled chrome direction*:

- (b) needs nothing from (a). `NavigationController`'s `spineEl` option is allowed to return `null` —
  `measure()` reads `const trackPx = spineEl ? spineEl.clientHeight : 0`
  (`primitives/navigation.ts:288-289`), and `trackPx` feeds only minimap placement. Landmark
  measurement, focus derivation and `jumpTo` are untouched by its absence.
- (b)'s visible feedback is the browser's own focus ring on the jumped-to element, which is *better*
  for a keyboard user than the minimap's `.active` ring (which a keyboard user cannot see the point of
  without also seeing the gutter).
- (a) requires a reserved gutter track. Search v3's transcript is a centred `max-inline-size: 48rem`
  block inside one scroller (`views/search-v3/Sv3Main.ts:296-301`, `:1344-1346`) with **no** grid-zone
  frame equivalent to `CONVERSATION_ZONES`. That is new layout work, not a style copy.
- (a) is **indivisible from scrollbar suppression**: `.conversation.spine-scrolled` +
  `jf-scrollbar-none` (`views/UnifiedChatView.ts:2642, 2668`; styles `:399-408`) exist so the window
  never shows both a native scrollbar and a minimap. Porting the minimap without the thumb leaves
  Sv3 with no scroll affordance; porting it without the suppression leaves two.
- (a)'s structure gate is **unbuildable in Sv3 today**: `spineItems()` shows the spine only when
  `≥2 user turns OR ≥2 distinct segment.nodeId` (`views/UnifiedChatView.ts:3248-3252`), and Search v3
  has no segment model at all — no `RunSegmentRef`, no `assignRunSegments` (`views/search-v3/sv3-run.ts:112-132`).
- 855 is actively redesigning main-window chrome and shrinking the rail
  (tempdoc 855 §5 — **caveat: that tempdoc is untracked in git as of this writing**, so it is cited as
  in-flight direction from another worktree, not as authority; the argument below does not rest on it);
  adding a new permanent gutter zone to the successor window ahead of that is the wrong order.

**This is flagged for the owner, not decided unilaterally.** D2 says "the run spine IS ported" and
then glosses it as the keyboard nav. Tier 1 delivers the gloss completely. If the owner also wants the
visual minimap, that is a separate, larger PR sequenced with 855 — §5.7 specifies it. **BLOCKED ON
OWNER:** confirm Tier 1 satisfies D2. **PROCEEDING meanwhile:** Tier 1 is unambiguously inside D2 and
PR-A can be written against it now.

### 5.2 The load-bearing precondition: landmark anchors

`NavigationController` measures and jumps by `[data-item-id]` (`primitives/navigation.ts:155, 295`).
**Search v3 emits none** — no `data-item-id`, no `<nav>`, no ARIA landmark/list role anywhere in its
run rendering. Without the anchors the ported handler measures zero landmarks and silently no-ops
(the source bails on an empty landmark list at `views/UnifiedChatView.ts:4819`). This is the first
work item, and it is small:

| Landmark | Stamp site | Id |
|---|---|---|
| The reader's question | `Sv3Main.question()` — the `.ask-bubble` div (`views/search-v3/Sv3Main.ts:1515`) | `${turn.id}:q` |
| A non-agent turn's answer | `Sv3Main.turn()` — the `.answer` div (`views/search-v3/Sv3Main.ts:1474`) | `${turn.id}:a` |
| Every run step (prose / tool card / note) | `Sv3Main.runItem()`, all three arms (`views/search-v3/Sv3Main.ts:2153-2172`) | `item.id` |
| **A held decision (approval / budget prompt)** | `Sv3Main.runPrompt()`, reached from `runBody` at `views/search-v3/Sv3Main.ts:2148` | `prompt.id` |

**The fourth site is not optional (rev 2, amendment A8).** `runBody` renders
`${run.prompts.map((prompt) => this.runPrompt(prompt))}` **outside** the `.run-feed` div
(`Sv3Main.ts:2148`), and its own doc-comment says why: *"a held decision must not be something the
reader can scroll past"* (`:2131-2133`). Under a three-site plan the one item the design most wants a
reader to reach — a run parked on an approval — would be the only run element J/K skips. Stamp the
`.run-prompt` element. If an implementer finds a reason not to, it must be **recorded as a deliberate
omission**, not left implicit.

Four facts make this clean, each verified:

1. **`runItem()` is the ONE renderer for both the live feed and the record.** `recordedActivity()`
   (`Sv3Main.ts:2034-2039`) and `runBody()` (`Sv3Main.ts:2138-2151`) both map through it, and the
   doc-comment says why ("Two sources, ONE renderer … so a run the reader watched and a run they came
   back to cannot be drawn differently", `:2026-2029`). One stamp covers live **and** reloaded
   conversations — which the retiree only achieves by merging two projections.
2. **They are mutually exclusive per turn**, so no id is stamped twice:
   `run === null ? this.recordedActivity(turn) : this.runBody(run)` (`Sv3Main.ts:1469-1471`).
3. **Do not stamp the `.turn` container** (`Sv3Main.ts:1455-1463`). Nested landmarks would give
   overlapping extents and make J/K step "into" a turn before stepping through it. The retiree stamps
   per timeline *item*, never per container; match that.
4. **`question()` has two early-return arms that emit no anchor** (rev 2, amendment A10): it returns
   `nothing` for an empty question (`Sv3Main.ts:1511`) and returns `questionEditor(turn)` while that
   turn is being edited (`:1512`). So "one anchor per turn" is false by construction, and the
   one-anchor-per-item test must be written against the **rendered** item set, not against
   `turns.length`. A test that assumes `2 × turns.length` anchors will fail the moment a turn is
   opened for edit — and it would be failing for the right reason, which is why the test must be
   stated correctly up front.

**Id-space risk (the one open item worth a check at implementation time).** Turn ids are minted
handles (`Sv3Turn.id`, `views/search-v3/sv3-sessions.ts:97-105`); run-feed ids come from the
`AgentSessionController`'s entry/call id space (`sv3-run.ts:180, 190, 203`). The `:q`/`:a` suffixes
keep the turn anchors out of the feed's namespace by construction. A collision between two feed items
is already prevented for tool calls (`seenCalls`, `sv3-run.ts:186-188`). **Pin it with a test** that
asserts no duplicate `data-item-id` in a rendered multi-turn transcript with activity — that is the
assertion, not a code reading.

### 5.3 Adopting `NavigationController` — Search v3 is §21's 2nd adopter

Tempdoc 565 §21 shipped the NAVIGATION authority "chat-first … general multi-surface authority
deferred to the **2nd adopter**". This is that adopter. Confirmed: no `NavigableRun` type and no
`navigation-surfaces` register exist (`governance/` has neither), and `NavigationController` /
`computeSpinePositions` / `runStepPresentation` / `jf-run-node` have **zero** matches in
`views/search-v3/`. So the authority was extracted as a *module*, never validated by a second consumer.

`Sv3Main` hosts it — it owns the scroller and the items, both in one shadow root. It already has the
accessor: `private get scroller()` (`Sv3Main.ts:1224-1225`). Use it; do not add a second query.

```
new NavigationController(this, {
  scrollEl: () => this.scroller,          // the existing accessor, Sv3Main.ts:1224-1225
  spineEl:  () => null,                   // legal — navigation.ts:288-289
  active:   () => this.transcriptArmRendered,   // see A1 below — HOST STATE, never nav.landmarks
})
```

#### A1 (rev 2) — `active()` must be a host-state predicate, or it deadlocks

Rev 1 wrote `active: () => <the transcript has landmarks>`. **That is a deadlock and the doc was
wrong.** `landmarks` is populated only inside `measure()`, and `hostUpdated()` calls `measure()` only
when `active()` is already true — otherwise it calls `teardown()`
(`primitives/navigation.ts:128-133`). An `active()` that reads `nav.landmarks.length` can never
become true: zero landmarks → inactive → never measured → zero landmarks.

`active()` must therefore be answerable **before** any measurement, from host state and rendered-arm
identity alone. The predicate already exists in the code — it is the transcript arm's own condition at
`Sv3Main.ts:1300`:

```
turns.length > 0 || this.recordNotice
```

Use exactly that (extracted to one private getter so the render arm and the controller cannot
disagree — two copies of this predicate is precisely the fork this design is elsewhere arguing
against). It is synchronous, depends on no layout, and is true exactly when there is something to
navigate.

Degenerate case, accepted: `recordNotice === true` with zero turns renders the transcript arm with no
items, so `active()` is true and `landmarks` is empty. J/K then no-ops harmlessly — the handler
already bails on an empty landmark list (`views/UnifiedChatView.ts:4818-4819`).

**`active()` must not be width-gated.** The retiree's `affordance === 'agent' && wideZone` gate exists
for the gutter (§4). Sv3 has no gutter, so the keyboard nav should work at every width — this closes
the narrow-viewport hole rather than porting it. State that in the PR body as a deliberate divergence
from the source, so a reviewer does not "restore parity" by re-adding the gate. See A9 (§10) for the
cost this converts from gated to ungated, and the live row that must confirm it.

#### A2 (rev 2) — `.scroller` is NOT a stable node, and the controller binds ONCE

Rev 1 treated the scroller as stable. **It is not.** `Sv3Main` emits `.scroller` from four distinct
render arms, each a different `html` template and therefore a different DOM node:

| Arm | `views/search-v3/Sv3Main.ts` |
|---|---|
| hero / undocked (empty scroller) | `:1304` |
| search rows | `:1321` |
| **transcript** (the only arm carrying `@scroll=${this.onScroll}`) | `:1345` |
| pending / skeleton | `:2312` |

Meanwhile `NavigationController.setupResize()` binds **once and early-returns forever after**:
`if (this.resizeObserver) return;` (`primitives/navigation.ts:257-258`), and it stores the node it
bound to in `this.scrollEl` (`:269`). So if the controller is active across a scroller swap, its
`ResizeObserver` and its four listeners stay attached to a **detached** node: `measure()` then reads
the new node's items but a stale `viewport`, and J/K indexes a landmark list that no longer matches
what the reader sees. The first-submit path (hero → transcript) is exactly this transition.

**Two mechanisms, both specified, because they answer different failure modes:**

1. **Primary — the `active()` coupling (correctness by construction).** With `active()` defined as
   "the transcript arm is rendered", the scroller node can only change when *leaving or entering* that
   arm, and leaving makes `active()` false, which drives `hostUpdated()` into `teardown()`
   (`navigation.ts:131-133`) — nulling the observer and removing the listeners, so the next entry
   re-binds to the new node. **This teardown coupling is load-bearing, not incidental.** Say so in a
   WHY-comment at the `active()` definition: a future `active()` that stays true across arms
   silently reintroduces the stale binding. Within the transcript arm Lit reuses the same node across
   re-renders (same template identity), including across conversation switches, so no swap occurs
   while active.
2. **Required hardening — an identity guard in `setupResize()`.** The controller's own doc-comment
   asserts the premise that just became false: *"`.conversation` is a stable DOM node across renders →
   observe it ONCE (guarded), not recreate"* (`navigation.ts:257`). That is true for the retiree and
   **false for the second adopter**, and leaving a false invariant in a shared authority's comment is
   the false-authority class this design elsewhere refuses to tolerate. Change the early-return to
   compare identity — rebind when the node changed:

   ```
   if (this.resizeObserver && this.scrollEl === conv) return;   // unchanged node: today's behaviour
   if (this.resizeObserver) this.teardown();                     // node swapped: rebind
   ```

   This is additive and behaviour-preserving for a stable node (identity equal → same early return),
   and it makes the shared authority correct for both adopters instead of correct-by-luck for one.
   Update the doc-comment in the same edit. **Needs its own unit test** (swap `scrollEl()`'s return
   value between two elements and assert the listeners follow) — otherwise it is an untested claim
   about the exact mechanism that was just found broken.

Defence 1 alone would work; defence 2 alone would work. Both are specified because 1 is a property of
*this* adopter's render structure (a future arm restructuring breaks it silently) and 2 is a property
of the *authority* (which now has two adopters with different node stability). Neither subsumes the
other.

`hostUpdated()` re-measures after every render (`primitives/navigation.ts:125-134`) and
`hostDisconnected()` cleans up (`:136-138`) — no lifecycle work to write beyond the above.

**Consciously unused facets** (do not delete them, do not wire them): `trackPx`, `viewport`,
`beginDrag`/`dragTo`, `nudge`, and the `.active`-ring `activeId` getter. They are the minimap's half of
the authority and stay live for UnifiedChatView until the sweep. §8 records them as sweep candidates.

### 5.4 The key handler

Port `views/UnifiedChatView.ts:4802-4830`'s body into `Sv3Main`: bare `j` forward, bare `k` back, all
other keys ignored, no modifiers, index into `nav.landmarks`, clamp at both ends (**no wrap**),
`nav.jumpTo(target.id)`.

Listener placement: a **window** listener added in `connectedCallback` and removed in
`disconnectedCallback`, matching the source. Rationale: J/K must work while focus sits in the sidebar
or elsewhere in the window, and `SearchV3View`'s existing host-capture listener (`SearchV3View.ts:846`)
lives on the wrong element to reach `Sv3Main`'s landmark index. The shell mounts one surface at a time
and only the *connected* element listens, so a cached-but-disconnected sibling window cannot
double-handle. **The removal-on-disconnect assertion is mandatory** — the retiree has exactly that test
(`views/UnifiedChatView.test.ts:2501`) and it is the leak this shape invites.

#### A4 (rev 2) — the "no collision" claim was WRONG at app scope

Rev 1 checked for collisions **within `views/search-v3/`** and declared it clean. That was the wrong
scope for a **window**-level listener. At app scope there is a real, confirmed collision:

`AdvisoryInboxDrawer.handleItemKeydown` binds bare `j` and bare `k`
(`components/advisory/AdvisoryInboxDrawer.ts:379-386`), bound per row at `:566`, and the drawer is
mounted **app-wide** in the Shell's right-drawer slot (`chrome/Shell.ts:2384`). It calls
`e.preventDefault()` but **not** `e.stopPropagation()`, so the event bubbles to `window`. Its `.item`
target is `tabindex="0" role="button"` (`:562-564`) — a *non-typing* element, so the editable guard
does not stop it. With the advisory drawer open and a row focused, one `j` press would move the
advisory selection **and** jump the Search v3 transcript.

**Specified fix — the Sv3 handler ignores an already-consumed event:**

```
if (event.defaultPrevented) return;
```

One line, general, and correct in kind: a window-level listener is the last handler in the chain and
has no business acting on an event an inner handler already claimed. Because the drawer's listener is
on the element and `window` is reached by bubbling, `defaultPrevented` is reliably true by the time
the Sv3 handler runs. It also pre-empts every future inner `j`/`k` handler, not just this one.

**Rejected alternative — `composedPath()` containment** (`event.composedPath().includes(this)`). It is
tempting and it is what Search v3 does elsewhere (`SearchV3View.ts:1854-1856` documents the window's
preference for `composedPath` over `activeElement`), but it silently kills J/K whenever nothing inside
`Sv3Main` is focused — e.g. right after the reader clicks page whitespace, where `composedPath` is
`[body, html, document, window]`. That trades a rare collision for a common dead key.

**Also rejected — patching the drawer.** Adding `stopPropagation` there fixes this one caller and
leaves the Sv3 handler still wrong for the next one. The drawer's missing `stopPropagation` is logged
as a separate observation; it is not this PR's to fix.

**Mandatory regression test:** dispatch a bubbling `j` `keydown` from an advisory `.item` with
`preventDefault()` already applied and assert `nav.jumpTo` was **not** called. Without it this is an
untested claim about the exact interaction that was just found broken.

Remaining collision check, now at the right scope and **clean**: Search v3 attaches no
window/document keydown in production code (`Sv3Palette.test.ts:157-167` asserts the palette
deliberately attaches none); `SearchV3View`'s host-capture handler takes Escape and the Ctrl/⌘+K chord
(`:329-330, 1827-1858`) — a *modified* chord, so bare `k` does not collide. Element-scoped
Arrow/Home/End/F2/Enter handlers (pane and sidebar grips `:1683, 1703`; composer `:971, 1330, 1342`;
session row `:565, 574`) are in other shadow roots and Tier 1 binds none of those keys.
`commands/CommandPalette.ts:323` is **`ArrowDown`, not `j`** — rev 1 mis-cited it as a `j`/`k` handler;
it is not one.

### 5.5 The editable guard — extract, but there are THREE copies, not two (rev 2)

The retiree's guard descends nested shadow roots to the truly-focused element
(`views/UnifiedChatView.ts:4808-4817`) and is the reason J/K does not eat characters in the composer.
Copying it into Sv3 would fork a security-of-input concern. Extract it — with three corrections to
rev 1.

**A5 — "extract once" was wrong: a second live copy already exists, and the two disagree.**
`commands/KeybindingRegistry.ts:163-167` performs the same check for modifier-less bindings and covers
**`INPUT || TEXTAREA || SELECT || isContentEditable`**. The retiree's copy omits **`SELECT`**. That
omission is a live bug, not a hypothetical: `UnifiedChatView.ts:3986` renders a
`<select class="workflow-picker">`, and with it focused the retiree's `j`/`k` handler steals the
`<select>`'s native type-ahead.

So the extracted helper is the **union** — `INPUT`, `TEXTAREA`, `SELECT`, `isContentEditable` — and
adopting it in `UnifiedChatView` **fixes that bug as a side effect of the port**. Say so in the PR
body; it is a behaviour change, small and strictly correct, and a reviewer must not read it as scope
creep.

`KeybindingRegistry` is named here as **the third copy, deliberately NOT re-pointed.** It resolves its
subject differently — `composedPath()[0]`, the event's *origin* — while the other two resolve
`document.activeElement` and descend. Those are different questions (where the event came from vs.
where focus currently is) and collapsing them would be a semantic change smuggled inside a
refactor. It may reuse the *predicate* later; that is a follow-up, recorded, not done here.

**A6 — wrong home.** Rev 1 put the helper in `primitives/navigation.ts`. That module is the
scroll-geometry authority and contains no keyboard code at all. The right home already exists:
`modules/ui-web/src/shell-v0/utils/keyboardHandler.ts`, created by exactly this rationale — *"Extracted
from the duplicated Enter/Shift+Enter pattern in AgentView, AskView, NavigateView, and SummarizeView"*
(`:1-7`) — already holding keyboard-event predicates (`handleSubmitKey`, `activateOnKey`) with five
consumers. Putting a keyboard predicate anywhere else while that file exists is the
`explore-before-implementing` failure this repo names as its most common mistake.

**A7 — the helper MUST duck-type.** The test this design mandates stay green
(`views/UnifiedChatView.test.ts:2482-2499`) monkeypatches `document.activeElement` with **plain object
literals** — `{ tagName: 'INPUT', isContentEditable: false, shadowRoot: null }` and
`{ shadowRoot: { activeElement: innerInput }, tagName: 'JF-UNIFIED-CHAT-VIEW' }` (`:2488-2489`). Any
narrowing to `instanceof Element`, `nodeType`, or `closest()` breaks it. Export two duck-typed units:

- `deepActiveElement(doc?: Document): Element | null` — the shadow-root descent, reading only
  `.shadowRoot?.activeElement`.
- `isTypingTarget(el: Element | null): boolean` — reading only `tagName` and `isContentEditable`.

Callers compose them: `if (isTypingTarget(deepActiveElement())) return;`. Splitting them is what lets
`KeybindingRegistry` reuse the predicate later without inheriting the descent it does not want.

**Why Sv3 gets an `activeElement`-based guard at all**, when `SearchV3View.ts:1854-1856` documents this
window's preference for `composedPath`: the two guards answer different questions and both are
right in their place. `composedPath` answers *"did this event originate inside X?"* — correct for
Escape precedence (`SearchV3View.ts:342-361`, which is orthogonal state this design does not touch or
supersede). The typing guard must answer *"is the reader typing right now?"*, and a key pressed while
a `<textarea>` in a sibling shadow root has focus can be dispatched with a path that does not include
that textarea. `activeElement` + descent is the correct instrument for that question. State this in a
WHY-comment so the next reader does not "unify" two guards that only look alike.

Unit-test both helpers directly — plain input, textarea, **select**, `contentEditable`, an element
inside two nested shadow roots, a non-editable focused element, and `null`. The retiree's cases
(`views/UnifiedChatView.test.ts:2470, 2482`) are the template, and its existing tests must pass
**unmodified** against the extracted version.

### 5.5b A3 (rev 2) — the existing test tier is structurally blind to this design's top risk

Rev 1 named the anchor wiring as the top risk ("a mis-stamped anchor gives zero landmarks and J/K
no-ops with every test green") and then specified tests that **cannot detect it**. That is the
`audit-without-test` shape, committed inside the very section warning about it.

Two facts make the existing tier blind:

1. **Every existing test hand-assigns the landmarks.** `primitives/navigation.test.ts:102, 121` set
   `nav.landmarks = RUN` directly, and the retiree's keyboard tests mount via a
   `mountWithLandmarks()` helper. `measure()` is never exercised, so the path from *stamped DOM* to
   *populated landmarks* has never been tested for either adopter.
2. **happy-dom lays nothing out**, so `getBoundingClientRect()` returns all-zero rects, and
   `measure()` explicitly **drops** every zero-height element:
   `if (rect.height === 0) return;` (`primitives/navigation.ts:301`). A correctly-stamped transcript
   therefore yields **zero** landmarks under the default harness. Every J/K test would still pass —
   they inject landmarks — while the real feature is inert.

**Mandated wiring test** (PR-A does not close without it): render a transcript with turns and run
activity, stub `getBoundingClientRect` on the stamped elements to return non-zero heights, drive a
render, and assert `nav.landmarks` **equals the stamped id set, in DOM order** — querying from the
exact element `scrollEl()` returns, not from an assumed selector. That last clause is what makes the
test also cover A2: if the controller is bound to a detached scroller, the assertion fails.

The idiom is already in this directory and its header comments say why:
`views/search-v3/SearchV3View.pane.test.ts:58` and `SearchV3View.sidebar.test.ts:43` stub
`getBoundingClientRect` per case, both prefaced with *"happy-dom lays nothing out, so
`getBoundingClientRect` is stubbed per case — which is what makes [the test] real"*
(`pane.test.ts:21`, `sidebar.test.ts:13`). Copy that idiom, not a new one.

### 5.6 The untested behaviour the port must close

Two gaps in the source, found by reading its tests. **Do not replicate them:**

- **No test asserts `jumpTo` actually moves focus.** `el.focus({preventScroll:true})`
  (`primitives/navigation.ts:171`) is the entire accessibility payload of this feature and nothing
  pins it. PR-A adds a direct assertion in `primitives/navigation.test.ts` (and an Sv3-level one).
- **`onSpineThumbKeyDown` is never exercised end-to-end** — only `nav.nudge` directly
  (`primitives/navigation.test.ts:169-184`). Tier 1 does not port the thumb, so this stays a Tier-2
  item; it is recorded here so Tier 2 does not inherit it silently.

### 5.7 Tier 2 — the minimap, named and deferred (not dropped)

If the owner wants it, the work is: an Sv3 gutter zone (either adopt `composeGridStyles` — making Sv3
the `composition-surfaces` register's replacement adopter, see §8 — or a bespoke frame),
`renderRunSpine` re-authored against Sv3 tokens, the `role="scrollbar"` thumb + drag +
`nudge` wiring, `.spine-scrolled` scrollbar suppression, an Sv3 structure gate (turn-count only — the
segment arm is unbuildable), and `<jf-run-node>` + `stepPresentation()` per step, which would also have
to confront the pre-existing hard-wired `.stepPresentation=${null}` at `Sv3Main.ts:2166`. That last
one puts Tier 2 squarely inside `governance/run-renderers.v1.json`'s scope; Tier 1 does not touch it.

**Record Tier 2 in 852's parity ledger as DEFERRED — undecided**, the same status as query trail. It is
not a ratified drop, and an implementer must not read Tier 1's landing as its retirement.

---

## 6. D3 — Health and Activity: what "user-visible" actually means

### 6.1 The finding that reframes the decision

**Surface `audience` gates nothing in the shell.** It has exactly one reader —
`listSurfacesByAudience()` (`modules/ui-web/src/api/registry/SurfaceCatalogClient.ts:390-393`) — and
that function has **zero production callers**; only its own test refers to it
(`SurfaceCatalogClient.test.ts:13, 281-289`). Its doc-comment claims it is "used by the chrome's
`visibleAudienceSet` filter". **No such filter exists.**

Everything that *does* gate reachability keys off something else:

| Mechanism | Actual filter | `file:line` |
|---|---|---|
| Rail membership | `placement === 'RAIL'` and not a member of a host; then layout zone, `userConfig.surfaceVisibility`, Simple-mode drop set, altitude-banded order | `chrome/Shell.ts:2000-2074` |
| Rail banding | `altitude` | `chrome/Shell.ts:2660-2702` |
| Command palette | no audience filter at all — the Action substrate **retired** its `audience` field for exactly this reason ("No consumer ever read it") | `substrates/actions/index.ts:164-169`; pool built at `commands/CommandPaletteProjection.ts:127-141` |
| Deep-link routing | bare id membership | `chrome/Shell.ts:1444-1445`, `router/navigationHandler.ts:99` |
| "View tier" toggle | gates **operations, resources and two Settings sections** — never surfaces | `state/viewerAudienceState.ts:63, 74, 95`; `views/SettingsSurface.ts:2485, 2577` |

**So the flip changes nothing observable at runtime. It is a declaration-honesty fix.** This
contradicts the ledger entry's own note, which says "the FE re-declaration wins in the shell, so Health
is OPERATOR-visible there" (`scripts/ci/check-surface-composition.mjs:57-65`). That note is wrong, and
deleting the entry (D3's second half) removes it — which is the right outcome, but the owner should
know the premise it was framed on was inaccurate.

### 6.2 Both surfaces are already reachable by a normal user

They are declared **members of the System hub**: `core.system-surface` is `Audience.USER`,
`Placement.RAIL`, `.withMembers(HEALTH, LOGS, ACTIVITY)`
(`modules/app-observability/src/main/java/io/justsearch/app/observability/surface/CoreSurfaceCatalog.java:643-657`),
rendered as tabs by `views/SystemSurface.ts` through the one `<jf-surface-tabs>` primitive, which has
no audience logic. A deep-link to a member redirects to the host with that member's tab preselected
(`router/catalogResolver.ts:49-57, 103-113` + `chrome/Shell.ts:1506-1513`). Health additionally has a
palette entry, `core.action.shell.go-to-health` → "Go to System Health"
(`substrates/actions/index.ts:796`).

The Java catalog also **already declares placement**, and it declares `DEEPLINK` for both
(`CoreSurfaceCatalog.java:666, 731`), with a comment saying why: *"Health is a MEMBER of the System hub
… so its home is its host: DEEPLINK (off the rail, URL-routable)."* So placement is already in parity;
only `audience` disagrees.

### 6.3 A rail entry is structurally forbidden — do not attempt it

`check-surface-composition.mjs:269-274` fails the build if **a member is also a RAIL surface** ("a
member's home is its host"). Making Health or Activity RAIL would first require removing them from
`.withMembers(...)`, i.e. dismantling the System hub. Independently, 855's stated end-state rail is
"Library · Chat · (advisory badge) · Help · Settings"
(tempdoc 855 §5 — **untracked in git as of this writing**, cited as in-flight direction, not authority).
The tracked, sufficient reason stands alone: the member-may-not-be-RAIL rule above. **Placement stays `DEEPLINK` on both sides.** This settles the owner's open question ("unless
research shows the Java catalog also declares rail placement") with a No, twice over.

### 6.4 The minimal change that makes them genuinely reachable

1. **The flip itself** (`audience: 'OPERATOR'` → `'USER'`) at `plugin-api/CorePlugin.ts:179` (health)
   and `:221` (activity). **Do not touch `:199`** — that is Logs, which is `OPERATOR` on *both* sides
   (`CoreSurfaceCatalog.java:702`) and is correctly in parity. This is the wrong-line trap in this PR.
2. **One palette entry for Activity.** Health has `go-to-health`; Activity has nothing. Add
   `navigateAction('core.action.shell.go-to-activity', 'Go to Activity', 'core.activity-surface')`
   beside it at `substrates/actions/index.ts:796`. This is the only actual reachability *delta* in D3,
   and it costs one line. The member-deeplink redirect (§6.2) lands it on the System hub's Activity tab.
3. **Delete the settled ledger entries** — §6.5, and it is not optional.

**One genuine hole, surfaced not closed. BLOCKED ON OWNER:** Simple mode drops
`core.system-surface` off the rail entirely (`chrome/Shell.ts:2034-2037`). A Simple-mode user reaches
Health only through the palette and Activity through nothing at all — even after this change. Whether
Simple mode should reach these surfaces is a product decision about what "Simple" means, adjacent to
855's rail work, and this design does not take it.

### 6.5 The ledger deletion is enforced, not conventional — verified

The owner's claim holds. `checkParity` at `scripts/ci/check-surface-composition.mjs:189-222`:
a pinned entry `continue`s to a warning only when **all four** pinned values still match; otherwise it
pushes a failure, and with `disagreements.length === 0` the message is *"The disagreement is settled —
DELETE the entry; an exemption outliving its reason is false authority."* `failures.length > 0` exits
1 (`:317-320`). A second path at `:232-238` fires if a ledger id stops being declared in both files.

**Consequence: the flip and the deletion are atomic — one commit.** Flip without delete → "stale
entry, settled". Delete without flip → the drift becomes an unpinned parity failure. Either half alone
is a red build.

The ledger entries are `KNOWN_PARITY_DRIFT` at `:56-74` — `:57-65` health, `:66-73` activity. The gate's
own test suite uses **synthetic** ledgers via the `ledger` parameter and contains no reference to
health/activity (`scripts/ci/check-surface-composition.test.mjs`, 28 assertions), so **no test change
is required** by the deletion.

**Warning the implementer must act on: this gate is NOT wired into hosted CI.** Grep of
`.github/workflows/` for `surface-composition` returns nothing, and 852 states the arrangement openly
(`docs/tempdocs/852-sv3-promotion.md:166-170`). It is enforced by the pre-merge / consult-register
path only. Run it locally; hosted CI will not catch a split.

### 6.6 The residue this settles (`retire-with-a-sweep`)

D3 establishes as fact that surface audience has no shell consumer. Two artefacts assert otherwise and
become false authority the moment this lands:

- `SurfaceCatalogClient.ts:390-393` — `listSurfacesByAudience()`, zero production callers, with a
  doc-comment naming a `visibleAudienceSet` chrome filter that does not exist. **Recommended: delete
  the function and its test** (`SurfaceCatalogClient.test.ts:13, 281-289`). Minimum acceptable
  fallback: correct the comment. Leaving both is the option this rule forbids.
- The two ledger notes themselves — removed by the deletion in §6.5. No extra work.

Checked and **not** residue: `governance/sandbox-coverage.v1.json`'s reach descriptions for both
surfaces describe the System-page tabs and the palette, and mention no audience — no edit needed.

---

## 7. D4 — the evidence pane closes on conversation switch

### 7.1 The complete route enumeration

The active conversation is `this.sessions.activeId`
(`views/search-v3/sv3-sessions.ts:314`); every switch is a `this.sessions = <helper>(…)` assignment,
and the helpers that write `activeId` are a closed set: `focusSession` (`sv3-sessions.ts:1194`),
`startNewSession` (`:1179-1182`), `submitInSession` (`:409`), `removeSession` (`:1081`), plus the
`SV3_SESSIONS_EMPTY` constructor (`:317`). Verified **non**-writers: `mergeStoreConversations`
(`:682-718`), `adoptRunSession` (`:605-640`), `renameSession`, `restoreSessionTitle`, `applySv3Record`,
`applySv3History`.

| # | Route | `file:line` in `views/search-v3/SearchV3View.ts` | Closes today? | Action |
|---|---|---|---|---|
| 1 | `onSessionSelect` — sidebar row click (bound `:2886`) | `:2547-2561`, `focusSession` `:2553` | **NO** | **add `closePane()`** |
| 2 | `openBranch` — the shared open path | `:1368-1381` | **NO** | **add `closePane()`** |
| 3 | `onVersionSelect` — the version pager (bound `:2920`, produced `Sv3Main.ts:1609`) | `:1388-1392` → delegates to #2 | **NO** | covered by #2 |
| 4 | `onBranchAction` → `runBranchAction` → `branchInto` → `openBranch` (branch / retry / edit) | `:1281-1285`, `:1301-1327`, `:1341-1356` (`:1354`) | **NO** | covered by #2 |
| 5 | `onSessionNew` (bound `:2889`) | `:2755-2775`, `closePane()` at `:2767` | **YES** | none |
| 6 | `deleteThroughStore` delete-fallback | `:2735-2739` → routes through #5 | **YES** (transitively) | none |
| 7 | `restoreLastViewed` — mount-time restore | `:955-967`, called from `connectedCallback:831` | **NO** | none needed — §7.4 |
| 8 | `runAsk` first submit from the hero | `:2023-2028` | **NO** | none needed — §7.4 |
| 9 | `delegate` first submit from the hero | `:2263-2270` | **NO** | none needed — §7.4 |

Ruled out by inspection: no URL/hash routing switches this window; the only store subscription merges
rows only (`:824-829`); `Sv3Palette.ts` has no conversation command; `sv3-session-select` has exactly
one producer (`Sv3Sidebar.ts:266`) and `sv3-version-select` one (`Sv3Main.ts:1609`).

**Two lines of code fix all four leaking routes** — one in `onSessionSelect`, one in `openBranch`
(which is the shared implementation under #3 and #4). That is the whole of D4's change surface.

**But the close must be guarded on an actual change of conversation, not merely on the handler
running.** This is the wrong-gate trap in this PR, and it is not hypothetical:

- `onSessionSelect` guards only against an *unknown* id — `if (this.sessions === before) return;`
  (`:2554`). Clicking the **already-active** row still runs the whole claim path, because
  `focusSession` returns a new object (it stamps `lastVisitedAt`, `sv3-sessions.ts:1190-1197`, and returns the list unchanged only for an *unknown* id). An unconditional `closePane()` there
  would close a pane whose content is still perfectly valid, on a gesture that changed nothing.
- `openBranch` is safe either way — its only two callers are `branchInto` (`:1354`, always a
  newly-created conversation) and `onVersionSelect` (`:1391`), which already guards
  `id === this.sessions.activeId` (`:1390`). Verified: those are the *only* two callers.

So write both sites identically, capturing the previous id before the claim and closing only on a real
switch:

```
const prevId = this.sessions.activeId;
…                                  // existing claim path
if (prevId !== id) this.closePane();
```

Identical form at both sites is the point: "switching conversations closes the pane" should read the
same way in both places, so a later edit to one is visibly a divergence from the other.

### 7.2 What `closePane()` covers, and what it deliberately doesn't

`closePane()` — `SearchV3View.ts:1820-1825` — nulls exactly the four identity fields:
`paneDocPath` (`:598`), `paneCitation` (`:603`), `paneSource` (`:610`), `paneCitationHeader` (`:617`).

Two fields it leaves, and **both should stay left** — record the decision so a reviewer does not
"complete" it:

- `paneWidthPx` (`:619`) — deliberately preserved and documented at `:1818` ("closing a document is
  not withdrawing a boundary preference"), pinned by `SearchV3View.pane.test.ts:360`.
- `paneOverlay` (`:621`) — stale after close but recomputed on every open (`:1757`) and by the
  `ResizeObserver` (`:902`); the only rule reading it needs a mounted pane (`:474`). Benign.

**No new cancel mechanism is needed.** The view performs no document fetch; the fetch lives in the
shared reader (`components/documentPane/DocumentPane.ts:499-522`, kicked from `willUpdate` `:369-370`).
It has no `AbortController` but uses a monotonic `loadToken` (`:290`, `:500`) with stale guards at
`:511` and `:522`. Nulling `paneDocPath` stops the view rendering `<jf-sv3-pane>` at all
(`SearchV3View.ts:2824-2825`), unmounting `<jf-document-pane>` with it; a late response writes to a
detached element and is discarded. And `paneDocPath` has exactly **one** writer, `onCitationOpen`
(`:1753-1764`), asserted structurally by a test (`pane.test.ts:929`) — so nothing left stale can
re-open the pane.

### 7.3 The 849-S2 late-match upgrade path — no conflict, twice over

`upgradeOpenPaneAnchor()` (`SearchV3View.ts:1791-1814`, driven from `willUpdate` on any `sessions`
change at `:917-919`) is the late-match upgrade specified in
`docs/tempdocs/849-evidence-reader.md:689-693`. An unconditional close is safe because:

1. **It cannot re-open the pane** — it writes only `paneCitation` and `paneCitationHeader`, never
   `paneDocPath`, which is the mount condition (`:2825`).
2. **It is gated on exactly what `closePane()` nulls** — its first statement is
   `const source = this.paneSource; if (source === null …) return;` (`:1792-1793`), and `closePane()`
   sets `paneSource = null` (`:1823`). After a close, every later `sessions` update — including the
   incoming conversation's `refreshRecord` merge — short-circuits.

**And this same path is where the reported defect lives.** `headerFor` (`:1772-1779`) resolves the turn
against `activeTurns(this.sessions)` — the *active* session's turns only. After a switch with the pane
still open, the old conversation's `turnId` is gone from `activeTurns`, so `sv3CitationHeader(null, …)`
(`sv3-citation-anchor.ts:88-109`) passes `citation: null, question: null` into `citationHeader`, which
returns `null` when every fact is null (`components/chat/evidenceProjection.ts:959`). That is exactly
"the header goes honestly blank while the document stays": the header is a projection of the *active*
conversation and the document is not. **There is no third state worth preserving** — closing is the
coherent fix, not a workaround.

The logged observation is `docs/observations.md:1828` (2026-08-19); its cited line `:1315` is already
stale against `main`, and its enumeration matches §7.1 exactly except that it misses route #4.

### 7.4 Why routes 7-9 need nothing (state it, don't just omit it)

- **#7 `restoreLastViewed`** is mount-time; a cold element has all four pane fields `null` by
  construction (`:759-764`). *Caveat worth recording:* `disconnectedCallback` (`:850-889`) resets no
  pane field and the shell caches/re-connects this element (`:907-910`), so a pane left open survives
  navigating away and back — but `restoreLastViewed` early-returns (`:957`) because the *same*
  conversation is still active. That is retention within one conversation, which is correct, not the
  defect D4 names.
- **#8 / #9** are hero submits (`activeId: null` → a new id). The hero state has no turn, and
  `paneDocPath`'s single writer requires a turn's citation, so the pane is already closed. Route #5
  (which does close) is what put the window in the hero state.

---

## 8. What this batch orphans, and the sweep obligations it discovers

Per the design skill: name what is displaced; per `retire-with-a-sweep`: name it where the sweep will
look. Nothing below is PR-A or PR-B work — **all of it belongs to 852's S8-S11 sweep**, and §9 puts it
in 852 so that slice does not have to rediscover it.

**Displaced by this batch (PR-A does the teardown itself):**

- `UnifiedChatView`'s inline editable guard (`views/UnifiedChatView.ts:4808-4817`) — replaced by the
  shared `deepActiveElement()` + `isTypingTarget()` import from `utils/keyboardHandler.ts` in the same
  PR (§5.5). This is the only in-batch orphan. Note PR-A also *amends* (does not orphan) the shared
  `NavigationController`: the identity guard and the corrected "stable DOM node" doc-comment
  (`primitives/navigation.ts:257-258`) are edits to a live authority with two consumers, so the
  retiree's existing navigation tests are the regression surface and must pass unmodified.

**Discovered sweep obligations for S8-S11** (each is a *hard* consequence, not a tidiness note):

1. **`governance/composition-surfaces.v1.json` has exactly ONE adopter** — `views/UnifiedChatView.ts`.
   Deleting the view makes the register vacuous and its gate assert nothing. The sweep must either
   retire the register or re-point it at a successor adopter (which Tier 2, §5.7, would supply).
2. **`scripts/jseval/jseval/ui_step_index.json` will FAIL the build at sweep time.**
   `check-ui-step-coverage.mjs` freshness check (a) fails when a mapped source path no longer resolves
   (`:37-50`), and the index maps `shell-v0/views/UnifiedChatView.ts` → 12 steps (`chat-mode`,
   `qa-response`, `chat-proportion`, `chat-bands`, `chat-bands-detailed`, `chat-composer-small`,
   `chat-spine-single`, `chat-spine-multi`, `chat-chip-yield`, `chat-evidence-rail`,
   `chat-activity-rail-open`, `chat-wide-docked`) plus `views/unifiedChatStyles.ts` → 6. Also the
   `core.unified-chat-surface` exempt row in `governance/ui-step-coverage.v1.json`.
3. **Spine modules become orphans** once Tier 2 is settled: `views/runSpinePresentation.ts`,
   `views/unifiedChatStyles.ts:234-405`, the spine zone in `views/unifiedChatRequest.ts:153`,
   `primitives/adaptiveSpacing.ts` (verify other consumers first), and `NavigationController`'s
   minimap-only facets (`trackPx`, `viewport`, `beginDrag`/`dragTo`/`nudge`, the `activeId` ring
   getter) — `dead-code` gate territory.
4. **`components/RetrospectivePanel.ts`** and the other D1-dropped surfaces' components.
5. **`governance/run-renderers.v1.json`** lists `views/UnifiedChatView.ts` as both a tool-renderer
   mount site and a run-projection render site — both rows go with the view.
6. **A stale doc-comment already contradicts the code**: `views/UnifiedChatView.ts:3261` still says the
   spine is "aria-hidden because … decorative", while `:3345` renders an operable `<nav>` with an
   `aria-label` and the test pins the operable form (`views/UnifiedChatView.test.ts:1333-1341`).
   Pre-existing drift; it dies with the file.

---

## 9. Tempdoc updates this design mandates

1. **`docs/tempdocs/857-ratified-batch.md`** — this document, committed with PR-A (number verified free:
   `node scripts/ci/check-tempdoc-numbers.mjs` → OK, 560 distinct numbers, no collisions).
2. **`docs/tempdocs/852-sv3-promotion.md`** — add a section *"Ratified parity-ledger dispositions
   (owner, 2026-08-19)"* carrying: the §3 disposition table; the note that the run spine's **navigation
   tier** ships in 857 PR-A while the **minimap tier** is DEFERRED-undecided (§5.7); and the §8 sweep
   obligations addressed to S8-S11. 852 explicitly says the ledger lives with the orchestrator and
   lands in-repo as slices land (`:9-12`), so this is that landing, not a fork.
3. **No change to 851.** `check-window-cutover` keys on `core.search-v3-surface`'s audience and the
   `governance/window-cutover.done` marker (`docs/tempdocs/851-search-v2-retirement.md:75-80`); neither
   is touched. The D3 flip is a *different* surface pair — confirm with the gate's bounded per-entry
   regex (`check-window-cutover.mjs:79-88`), which is designed so a neighbour's audience cannot be
   mistaken for the successor's.

Per `docs-ride-along`, both tempdoc edits ride along with their PRs; neither is a standalone PR.

---

## 10. The plan — two implementer-sized PRs

They touch disjoint files, so they do not serialize on code.

**They DO serialize on the dev stack (rev 2).** Both PRs have mandatory live-verification rows, only
one dev stack runs at a time, and it is leased. That — not the ledger rationale — is the real
constraint on ordering. Each PR's live pass must acquire the lease (`quick_health` first; on
`OWNER_CONFLICT` / `CONTENTION` ask the user before starting or taking over) and declare
`leaseDurationSec` at start so the hold survives minutes of busy-but-silent work: **~900s for PR-B**
(one pane-switch pass plus the palette check), **~1800s for PR-A** (five live rows including a
streaming-jank observation). Stop the stack at the end of each pass.

**Recommended order: PR-B first** — it is smaller, mechanical, gate-verified, fixes a live defect, and
retires the ledger entry, so it frees the shared lease sooner and leaves PR-A a clean `main`.

### PR-B — the small batch: audience honesty + ledger retirement + pane close

**Changes** (row ids are `PR-A#`; the `A#` labels in §5 and §11 are the *review amendments*, a
different numbering — the two are cross-referenced explicitly where they meet.)

| # | Change | Site |
|---|---|---|
| B1 | `audience: 'OPERATOR'` → `'USER'` | `plugin-api/CorePlugin.ts:179` (health), `:221` (activity). **NOT `:199`** (Logs — correctly OPERATOR on both sides) |
| B2 | Delete both `KNOWN_PARITY_DRIFT` entries | `scripts/ci/check-surface-composition.mjs:56-74` |
| B3 | Add `go-to-activity` navigate action | `substrates/actions/index.ts:796` (beside `go-to-health`) |
| B4 | Add a **switch-guarded** `this.closePane()` (§7.1 — `if (prevId !== id)`, identical form at both sites) | `views/search-v3/SearchV3View.ts` `onSessionSelect` (`:2547-2561`) and `openBranch` (`:1368-1381`) |
| B5 | Delete `listSurfacesByAudience()` + its test (fallback: correct the false doc-comment) | `api/registry/SurfaceCatalogClient.ts:390-393`; `SurfaceCatalogClient.test.ts:13, 281-289` |

**B1 + B2 must be one commit** (§6.5). B4's two insertions must precede the `refreshRecord` /
`refreshHistory` dispatches in each handler (they are `void`-dispatched, so ordering is natural, but
say it).

**Tests** — right-reason discipline is the point here, because the pane tests have an obvious
wrong-reason trap:

| Test | Home | The assertion, and why it is the *right* reason |
|---|---|---|
| Pane closes on a sidebar row-click switch | `SearchV3View.pane.test.ts`, beside `:363` | Assert the pane is **present immediately before** the switch and absent after. Without the "present before" half the test passes on a pane that never opened. Row-click idiom: `SearchV3View.sessions.test.ts:196-221` (`rowsOf`, `clickRow`). |
| Pane closes on `sv3-version-select` | same | Dispatch `sv3-version-select` with a `detail.sessionId ≠ activeId` from `jf-sv3-main`; `openBranch` merges the unknown id first (`:1370`) so `focusSession` claims it. Same present-before/absent-after shape. |
| Pane closes on branch/retry/edit | same | Exercises route #4 through `branchInto` → `openBranch`. |
| **Negative 1:** clicking the **already-active** row does NOT close the pane | same | The sharpest right-reason test in this PR. It fails against the obvious unguarded implementation (§7.1) and passes only against the `prevId !== id` guard. |
| **Negative 2:** a non-switching update does NOT close the pane | same | Drive a `mergeStoreConversations` refresh or a late-match upgrade and assert the pane **survives**. Distinguishes "closes on switch" from "closes on any re-render" — without it, a naive `closePane()` in `willUpdate` would pass every positive test. |
| Late-match upgrade still upgrades an open pane | extend `pane.test.ts:637-693` | Guards §7.3's claim from the opposite side. |
| `go-to-activity` navigates to `core.activity-surface` | mirror whatever covers `go-to-health` in the actions/palette suite | Assert the target id, not merely registration. |
| No test change for B2 | — | Verified: the gate's suite uses synthetic ledgers only. |

**Gates** — the `ui-web-gates` recipe in `governance/consult-register.v1.json:36-42` (the full
`scripts/ci/check-*` set + the kernel gates + `cd modules/ui-web && npm run typecheck &&
npm run test:unit:run`). Named specifically:

- `node scripts/ci/check-surface-composition.mjs` — **the acceptance gate for B1+B2.** Do a
  right-reason probe: apply B1 alone and confirm it fails with the *"settled — DELETE the entry"*
  branch, then apply B2 and confirm exit 0. Remember it is **not in hosted CI** (§6.5).
- `node scripts/ci/check-ui-step-coverage.mjs` — unaffected (RAIL-keyed; both stay DEEPLINK) but cheap
  insurance that B1 did not disturb the CorePlugin parse.
- `./gradlew.bat build -x test` — no Java changes, but the pre-merge rule requires it.

**Live verification** (required — `use-every-verification-tier`, and the plan skill's browser rule):
happy-dom lays nothing out, so the pane behaviour needs a real stack. The `sv3-citation-selected`
ui-shot step already exists (`scripts/jseval/jseval/ui_step_index.json`) and is the base: open a
citation pane in conversation A, switch to B via the sidebar, confirm the pane is gone. Then confirm
"Go to Activity" appears in the palette and lands on the System hub's Activity tab.

**Confidence: 8.5/10. Model: sonnet.** Every site is a precise `file:line`, the acceptance criterion is
a gate that fails loudly, and the one subtle part (the negative pane test) is specified above.

### PR-A — the run-spine navigation port

**Changes**

| # | Change | Site |
|---|---|---|
| PR-A1 | Stamp `data-item-id` on the **four** landmark kinds | `views/search-v3/Sv3Main.ts` — `question()` `.ask-bubble` (`:1515`), the non-agent `.answer` div (`:1474`), all three arms of `runItem()` (`:2153-2172`), and `runPrompt()` (`:2148`) |
| PR-A2 | Export `deepActiveElement()` + `isTypingTarget()` (union incl. **SELECT**), duck-typed; re-point the retiree at them | `utils/keyboardHandler.ts`; replaces the inline guard at `views/UnifiedChatView.ts:4808-4817` |
| PR-A3 | Construct `NavigationController` on `Sv3Main`: `scrollEl: () => this.scroller` (`:1224-1225`), `spineEl: () => null`, `active()` = the extracted transcript-arm predicate (`:1300`), **not** width-gated, **never** `nav.landmarks` | `views/search-v3/Sv3Main.ts` |
| PR-A4 | The J/K window listener (add in `connectedCallback`, remove in `disconnectedCallback`), with the `if (event.defaultPrevented) return;` guard | `views/search-v3/Sv3Main.ts` |
| PR-A5 | Add the J/K binding to the Help shortcut table, with a scope note | `views/HelpSurface.ts:71-77` (`SHORTCUTS`) |
| PR-A6 | **Identity guard in `setupResize()`** + correct its now-false "stable DOM node" doc-comment | `primitives/navigation.ts:257-258` |

**On A5:** a keyboard affordance no one can discover is not an accessibility feature. And the table's
own governing comment already makes this an obligation rather than a courtesy — tempdoc 586 P-3, at
`views/HelpSurface.ts:65-70`: *"only shortcuts that ACTUALLY fire are listed … Listing shortcuts that
don't work is worse than a short list, so the table now mirrors the real bindings."* The inverse holds
with equal force: a binding that fires and is not listed makes the table a partial mirror. Add the row
with an explicit scope note (J/K is window-scoped, unlike the five global bindings already listed) so
the table does not start over-claiming in the other direction.

Registering the chord in `commands/KeybindingRegistry.ts` (which would make it rebindable and
palette-visible) is the better form and is **out of scope** — record it as a follow-up, do not do it
here.

**Tests**

| Test | Home | The assertion |
|---|---|---|
| **WIRING (§5.5b) — stamped DOM actually becomes `nav.landmarks`** | `Sv3Main.*.test.ts` | **The one test that can detect this PR's top risk.** Stub `getBoundingClientRect` on the stamped elements (idiom: `SearchV3View.pane.test.ts:58`, `sidebar.test.ts:43`) so `measure()` does not drop them at `navigation.ts:301`; assert `nav.landmarks` equals the stamped id set **in DOM order**, queried from the element `scrollEl()` returns. Also covers A2 — a detached scroller fails it. |
| **Scroller swap (A2) — hero → first submit keeps the binding live** | same | Render the hero arm, submit, and assert after the transcript arm renders that `nav.landmarks` is populated (i.e. the controller rebound). Fails against rev 1's design. |
| Identity guard rebinds on a changed `scrollEl()` | `primitives/navigation.test.ts` | Swap `scrollEl()`'s return between two elements; assert the listeners/observer follow. Pins A6 — the mechanism just found broken. |
| No duplicate `data-item-id` in a multi-turn transcript with recorded activity | `Sv3Main.*.test.ts` | Collect all `[data-item-id]` and assert the id set size equals the element count. The §5.2 id-space check — pinned, not reasoned. |
| One anchor per **rendered** item, incl. a held prompt | same | Assert against the rendered item set, **not** `2 × turns.length` — `question()` emits no anchor for an empty question (`Sv3Main.ts:1511`) or a turn open for edit (`:1512`) (A10). Include a run parked on an approval so the `runPrompt` anchor (A8) is covered. |
| **Advisory-drawer collision (A4)** | same | Dispatch a bubbling `j` keydown from an advisory `.item` with `preventDefault()` applied; assert `nav.jumpTo` was NOT called. Pins the `defaultPrevented` guard against the confirmed app-wide collision. |
| A focused `<select>` blocks nav | same + `views/UnifiedChatView.test.ts` | The union-guard addition (A5). On the retiree this is a **bug fix** — the workflow picker (`UnifiedChatView.ts:3986`) currently loses type-ahead. |
| `j` moves forward, `k` back, other keys ignored | `Sv3Main` or a new `sv3-navigation.test.ts` | Template: `views/UnifiedChatView.test.ts:2415-2451`. |
| Clamps at both ends (no wrap) | same | First landmark + `k` stays; last + `j` stays. |
| A focused light-DOM `<input>` blocks nav | same | Template: `:2470`. |
| A focused editable inside **nested shadow roots** blocks nav | same | Template: `:2482`. This is the case a naive `document.activeElement` check fails. |
| Listener removed on disconnect | same | Template: `:2501`. The leak this shape invites. |
| **`jumpTo` moves DOM focus to the target** | `primitives/navigation.test.ts` **and** the Sv3 suite | Closes the §5.6 gap. This is the port's whole accessibility payload and is currently untested anywhere. |
| `deepActiveElement()` + `isTypingTarget()` unit cases | `utils/keyboardHandler.test.ts` | input / textarea / **select** / contentEditable / nested-shadow editable / non-editable / `null`. **Must pass with plain object literals** (A7) — no `instanceof`, `nodeType`, or `closest()`. |
| The Help row is actually rendered | `views/HelpSurface.test.ts` | The suite asserts rendered **substrings** (`expect(text).toContain('Ctrl / ⌘ + K')`, `:62`), so A5's row is otherwise unverified. Add a `toContain` for the new row. |
| Keyboard nav works at a narrow width | Sv3 suite | Pins the §5.3 deliberate divergence, so a reviewer cannot silently restore the `wideZone` gate. |
| `UnifiedChatView`'s J/K still works after A2 | existing `views/UnifiedChatView.test.ts:2415-2508` | Must stay green **unmodified** — that is the proof the extraction is behaviour-preserving. If a test needs changing, the extraction is wrong. |

**Gates** — the same `ui-web-gates` recipe. Named specifically:

- `check-controls-a11y` — Tier 1 adds no new activation handlers, so it should be a no-op; note 853
  records a **pre-existing** `controls-a11y` red (F-11), so read the diff of failures, not the pass/fail.
- `check-a11y-closure` — **will not see these files, and the root-cause fix is NOT free (rev 2).**
  Check (5) walks `VIEWS_DIR` (`scripts/ci/check-a11y-closure.mjs:35`) with a **non-recursive**
  `readdirSync` (`:134`) and loops those files only (`:140`), so `views/search-v3/**` and
  `views/security/**` are never scanned.

  Rev 1 compensated with "assert manually", which is symptom-level. Investigating the root-cause fix
  produced the reason it cannot ride PR-A: **making the walk recursive turns the build red today.**
  `views/search-v3/Sv3Composer.ts:1403` emits `<h1 class="headline">` for the hero — a second page
  `<h1>` beside the Shell topbar's sole one, which is exactly the violation rule (5) exists to
  prevent, in the window about to become *the* window. That is a real pre-existing a11y defect, not a
  gate artefact, and fixing it is a design question about the hero (demote to `<h2>`, or suppress the
  topbar heading in that state) that PR-A has no mandate to answer.

  **Specified split:** PR-A carries a *scoped* repo test — no `<h1>` and no `main` landmark in
  `Sv3Main.ts` — which is true, passes, and pins A1's markup. The gate fix (recursive walk) is
  specified as its own small follow-up that **must land together with the `Sv3Composer` decision**,
  and the finding is logged as an observation so it is not lost between the two.
- `check-run-renderers` — **IS engaged (rev 2 correction).** `Sv3Main.ts` is a registered referencer in
  `governance/run-renderers.v1.json` (a `toolRenderer.mountSites` entry; `sv3-record.ts` is likewise a
  `runProjection.renderSites` entry), so editing it puts the file in the gate's read set. Rev 1's
  "untouched" was wrong. The *requirement* is unchanged and Tier 1 satisfies it — no second tool
  renderer, no second run-assembly structure, no `<jf-run-node>`, no `stepPresentation` — but the gate
  must be **run**, not assumed inert. If a change would add a run-step render site, the scope has
  drifted into Tier 2.
- `style-literal-ratchet` / `atom-fork-ratchet` — Tier 1 should add ~no CSS. If a focus-ring rule is
  added, a declared changeset under `gates/<id>/.changesets/` may be required; load `/governance`
  before writing one.
- `./gradlew.bat build -x test` + `npm run typecheck` + `npm run test:unit:run`.

**Live verification** (required — stubbed rects prove the wiring, only a real layout proves the
feature). Load `/ui-check` and `/dev-stack` first. Rows:

1. **Navigation.** With an agent run in the transcript, press `j`/`k`: focus visibly moves step to
   step, the column scrolls the target to centre, and a held approval prompt is reachable (A8).
2. **Typing is unaffected.** Type `j` and `k` in the composer and in a `<select>`; the transcript must
   not move.
3. **The scroller swap (A2).** From the hero, submit a first query and confirm J/K works on the
   resulting transcript **without a reload** — this is the exact path rev 1's design broke.
4. **A9 — the ungated cost.** Dropping the width gate converts a gated cost into an ungated one:
   `measure()` runs a `querySelectorAll` plus a `getBoundingClientRect` per landmark after **every**
   render (`navigation.ts:285-306`, driven from `hostUpdated`, `:125-134`), and Sv3 re-renders on every
   streamed delta. Row: a long streaming answer with 20+ run steps, at a narrow width, watching for
   jank. If it janks, the fix is to throttle/coalesce `measure()` — **not** to restore the width gate,
   which would re-close the accessibility hole this port exists to open.
5. **Collision.** Open the advisory drawer, focus a row, press `j`: the advisory selection moves and
   the transcript does **not** (A4).

Consider adding an `sv3-run-spine` ui-shot step so the behaviour has a screenshot home (not
gate-required, but cheap parity with the retiree's `chat-spine-*` steps).

**Confidence: 6.5/10 (rev 2, down from 7). Model: opus.** The mechanism is well-understood and the substrate is already
extracted, but the work spans two large Lit components (`Sv3Main.ts`, `views/UnifiedChatView.ts`),
touches the retiring window (A2), threads a `ReactiveController` into a component that has never hosted
one, and has real happy-dom measurement subtleties. The failure mode is silent — a mis-stamped anchor
gives zero landmarks and J/K no-ops with every test green — which is exactly the
`audit-without-test` class, and why the wiring test (§5.5b) is mandatory rather than nice-to-have.

**Why rev 2 lowers rather than raises the number, despite resolving more:** the review found three
HIGH defects in a design that had already claimed high confidence — a deadlocked predicate, a stale
node binding, and a test tier blind to the design's own named top risk. Each was resolved, so the
*specification* is stronger; but the base rate that produced them has not changed, and two of the
three were errors of the form "asserted a property of code I had read". The residual risk now sits in
the live rows (A9's ungated `measure()` cost is genuinely unknown until measured) and in whatever
class of error the review's own scope did not cover. An implementer should treat §5.5b's wiring test
as the acceptance gate for the whole PR: if it cannot be made to pass, the design is wrong, not the
test.

---

## 11. Derisk register — every uncertainty, and how it was resolved

| # | Uncertainty | Verdict | Evidence |
|---|---|---|---|
| D-1 | Does the spine's markup port cleanly into Sv3Main's turn structure, or need a Sv3-native rebuild? | **Split.** The pure/controller layer ports verbatim; the render layer does not (no gutter track, no `affordance`/`wideZone`, no segment model, disjoint item type). Tier-1 scope makes the render layer moot. | `Sv3Main.ts:296-301, 1344-1346`; `sv3-run.ts:112-132`; `UnifiedChatView.ts:3248-3252` |
| D-2 | Does `NavigationController` work without a minimap? | **Yes.** `spineEl` may return `null`; `trackPx` degrades to 0 and feeds only minimap placement. | `primitives/navigation.ts:288-289` |
| D-3 | The `[data-item-id]` precondition and id-space collisions | **Named as work item 1; residual MEDIUM.** Absence makes J/K silently no-op. `:q`/`:a` suffixes separate the namespaces; tool ids are already de-duplicated. **Must be pinned by a test, not reasoned.** | `navigation.ts:155, 295`; `UnifiedChatView.ts:4819`; `sv3-run.ts:186-188`; `sv3-sessions.ts:97-105` |
| D-4 | Key collisions in Search v3 | **None.** No window/document keydown in sv3 production code; Ctrl/⌘+K is a modified chord; Arrow/Home/End handlers are element-scoped and Tier 1 binds none. | `Sv3Palette.test.ts:157-167`; `SearchV3View.ts:329-330, 846, 1827-1858` |
| D-5 | Does the audience flip trip any gate beyond parity — `interaction-surface`, sandbox-coverage rows, ui-step-coverage? | **No.** `interaction-surface` reads the Java side and requires RAIL/STAGE + ≥1 shape; `surface-altitude` does not read audience; `ui-step-coverage` and sandbox coverage are **placement**-keyed; `check-shape-view-coverage` is about ConversationShapes. | `gates/interaction-surface/enforcer.mjs:112-124`; `check-ui-step-coverage.mjs:62-79`; `scripts/sandbox/gen_coverage_brief.py:47-49`; `check-shape-view-coverage.mjs:69` |
| D-6 | Does a settled drift's stale ledger entry really fail the build? | **VERIFIED TRUE**, with the exact "settled — DELETE the entry" branch. Flip + deletion are therefore atomic. **Caveat: the gate is not in hosted CI.** | `check-surface-composition.mjs:189-222, 232-238, 317-320`; `852:166-170` |
| D-7 | Does pane-close conflict with 849-S2's late-match upgrade? | **No, twice over.** The upgrade never writes `paneDocPath` (the mount condition) and returns early on `paneSource === null`, which `closePane()` sets. | `SearchV3View.ts:1791-1793, 1823, 2825` |
| D-8 | Can an in-flight document load re-open a closed pane? | **No.** The view fetches nothing; `DocumentPane` unmounts and guards with a monotonic `loadToken`. No `AbortController` needed. | `DocumentPane.ts:290, 499-522`; `SearchV3View.ts:2824-2825` |
| D-9 | Does "user-visible" require a rail entry? | **No, and RAIL is structurally forbidden** — a hub member may not be RAIL. (855's rail-slimming points the same way but is untracked, so it is corroboration, not evidence.) | `check-surface-composition.mjs:269-274` |
| D-10 | What does the audience flip change for a user? | **Nothing at runtime.** No shell code path reads surface audience; the one reader has zero callers and a false doc-comment. The flip is declaration honesty; the reachability delta is the `go-to-activity` action. | `SurfaceCatalogClient.ts:390-393`; `Shell.ts:2000-2074`; `substrates/actions/index.ts:164-169` |
| D-11 | Simple mode hides the System hub | **Real hole, deliberately not closed. BLOCKED ON OWNER.** | `Shell.ts:2034-2037` |
| D-13 | Can `active()` be derived from the landmark list? | **NO — deadlock.** `landmarks` populate only in `measure()`, which runs only when `active()` is already true. Must be a host-state predicate; use the transcript arm's own condition. **(rev 2 BLOCKING, was wrong in rev 1)** | `primitives/navigation.ts:128-133`; `Sv3Main.ts:1300` |
| D-14 | Is `.scroller` a stable node the controller can bind once? | **NO.** Four render arms emit it; `setupResize` early-returns forever after the first bind, so a swap leaves listeners on a detached node. Resolved by the `active()` teardown coupling **plus** a required identity guard. **(rev 2 BLOCKING, was wrong in rev 1)** | `Sv3Main.ts:1304, 1321, 1345, 2312`; `navigation.ts:257-258, 269` |
| D-15 | Can the existing test tier detect a mis-stamped anchor? | **NO.** Every test hand-assigns `nav.landmarks`, and happy-dom's zero-height rects make `measure()` drop everything. A correctly-stamped transcript yields zero landmarks and every test still passes. Resolved by the mandated wiring test with stubbed rects. **(rev 2 BLOCKING)** | `navigation.test.ts:102, 121`; `navigation.ts:301`; idiom at `SearchV3View.pane.test.ts:58` |
| D-16 | Is the bare-`j`/`k` collision check clean? | **NO at app scope** — rev 1 checked only within `views/search-v3/`. `AdvisoryInboxDrawer` binds bare `j`/`k` with `preventDefault` and no `stopPropagation`, mounted app-wide, on a non-typing target. Resolved by a `defaultPrevented` guard + a regression test. **(rev 2, was wrong in rev 1)** | `AdvisoryInboxDrawer.ts:379-386, 566`; `Shell.ts:2384` |
| D-17 | Is the a11y-closure gate fix a free in-scope root-cause fix? | **NO.** Making the walk recursive fails the build today: `Sv3Composer.ts:1403` emits a second page `<h1>`. Split into a scoped repo test now + a gate fix bundled with the hero-heading decision. **(rev 2 — and a real uncovered a11y defect found)** | `check-a11y-closure.mjs:35, 134, 140`; `Sv3Composer.ts:1403` |
| D-12 | Anything needing a live probe? | **Yes** — PR-B's pane-close on a real switch, and PR-A's five rows (§10), of which **A9's ungated `measure()` cost is the only genuinely open question in this design**: everything else was resolved by reading code, but per-render `querySelectorAll` + per-landmark `getBoundingClientRect` under streaming is not answerable statically. Both PRs serialize on the single dev-stack lease. | §10 live-verification rows |

---

## 12. Reach — the principle this design instances, and its retirement condition

**The principle: a deferred authority is validated by its second adopter, not by its extraction.**

Tempdoc 565 §21 extracted the NAVIGATION authority and explicitly deferred "the general multi-surface
authority … to the 2nd adopter". The extraction shipped: `NavigationController`,
`runSpinePresentation.ts`, `adaptiveSpacing.ts`, `scrollViewport.ts` all exist as standalone, unit-tested
modules. But for two months the authority had **one** consumer, and a single-consumer authority is
indistinguishable from a well-organized view — its interface has never been tested against a second
shape. This design is the first time that is tested, and the test **passed**: the controller's option
surface (`scrollEl` / `spineEl` / `active`, `primitives/navigation.ts:82-89`) turned out to be exactly
the right seam — `spineEl: () => null` cleanly separates navigation from minimap without a single
change to the authority's *interface*. That is a real validation, and it is worth recording.

**Rev 2 qualifies this claim, because rev 2's review falsified half of it.** The interface held, but the
authority's stated **invariant** did not: its `setupResize` doc-comment asserts *"a stable DOM node
across renders → observe it ONCE"* (`primitives/navigation.ts:257`), which is true of the first adopter
and **false** of the second, where `.scroller` is emitted from four render arms. So the second adopter
did require an implementation change (§5.3 A2). The sharper form of the principle is therefore:

> A single-consumer authority's *interface* may be fine while its *unstated assumptions about its
> consumer* are silently load-bearing. The second adopter is what separates the two, and the
> assumptions — usually parked in a doc-comment rather than in a type — are what break.

That is a better claim than rev 1's, and it was only available because someone re-derived the design
against source instead of accepting it. Record it that way: the extraction was good, the invariant was
provincial.

**Where else this applies in this codebase, today:**

- `governance/composition-surfaces.v1.json` — **one** adopter (`views/UnifiedChatView.ts`), and that
  adopter is being deleted. Same shape, opposite outcome: the authority is about to lose its only
  consumer, which is the strongest possible signal that its generality was never earned. §8 item 1.
- `views/runStepPresentation.ts` / `<jf-run-node>` (565 §17's StepPresentation authority) — Search v3
  hard-wires `.stepPresentation=${null}` (`Sv3Main.ts:2166`), i.e. the second window declined to adopt
  it. That is a *failed* second-adopter test that nobody recorded as one.
- `api/registry/SurfaceCatalogClient.ts:390-393` — an authority-shaped API with **zero** adopters and a
  doc-comment inventing one. §6.6.

**The observable evidence it earns its keep:** a second adopter lands without modifying the authority's
interface (as here), or the attempt forces a change that improves both consumers (as the identity guard
does — it makes the shared controller correct for both instead of correct-by-luck for one). **The
retirement condition:** if a "deferred to the 2nd adopter" authority reaches its second adopter and the
adopter has to fork it or bypass it (as `Sv3Main.ts:2166` did to StepPresentation), the deferral was
wrong and the module should be collapsed back into its one consumer rather than kept as apparatus.

**Deliberately not built now:** no `navigation-surfaces` register, no `NavigableRun` type, no gate. §21
theorized all three and none exists. Two adopters do not justify a register; the seam is doing the work
that a register would only describe. Revisit at a third adopter, or when a fork appears.

**A smaller, sharper principle from D3, worth stating because it is cheap to check:**
*a declaration nobody reads is not a policy — it is a comment that lies with a straight face.* Surface
`audience` has been carried in two catalogs, mirrored in a generated enum, pinned by a drift ledger,
and argued about in a tempdoc note — and no code has ever read it for a surface. The falsifier is a
one-line grep for the field's consumers, and it should be run **before** the next register row is added
for any discriminator. Where else this may already hold: `Placement.MODAL` has zero producers and zero
consumers (`855 §9.2`), and the Action substrate already retired its own `audience` field for precisely
this reason, leaving the reasoning in place at `substrates/actions/index.ts:164-169` — the codebase has
learned this lesson once already and did not generalize it.

---

## 13. PR-A implementation record (2026-08-19)

Every §10 PR-A row landed. What follows is what an independent reviewer needs that the plan above
does not already say: the deviations, the things the plan got wrong about the tree, and the evidence
each test carries.

### 13.1 Rows, as landed

| Row | Landed as |
|---|---|
| PR-A1 — four stamp sites | `Sv3Main.question()` `.ask-bubble` → `${turn.id}:q`; the non-agent `.answer` → `${turn.id}:a`; all three `runItem()` arms → `item.id`; all three `runPrompt()` arms → `${prompt.id}:hold` (**deviation, §13.2**) |
| PR-A2 — shared typing guard | `utils/keyboardHandler.ts` exports `deepActiveElement()` + `isTypingTarget()`, duck-typed; `UnifiedChatView.onConversationKeydown` re-pointed at them, its inline guard deleted |
| PR-A3 — the controller on `Sv3Main` | `scrollEl: () => this.scroller`, `spineEl: () => null`, `active: () => this.transcriptArmRendered` — host state, never `nav.landmarks`, never width-gated |
| PR-A4 — the J/K window listener | added in `connectedCallback`, removed in `disconnectedCallback`, with `if (event.defaultPrevented) return;` first |
| PR-A5 — the Help row | `views/HelpSurface.ts` `SHORTCUTS` gains `J / K` with the window-scope note |
| PR-A6 — the authority's identity guard | `setupResize()` compares node identity and rebinds; the false "stable DOM node" doc-comment is corrected in the same edit |
| A9 — the ungated `measure()` cost | `measure()` is coalesced to one LEADING pass per animation frame (`measureCoalesced`). Throttled, *not* re-gated on width — re-gating would close the accessibility hole the port exists to open |

### 13.2 Deviations from the plan, and why

1. **The `runPrompt` anchor carries a `:hold` suffix; the plan said stamp `prompt.id`.** The plan's
   own D-3 flagged the id space as the residual risk to pin with a test rather than reason about, and
   the test found this: an APPROVAL prompt's id **is** the tool call's id. `projectSv3RunFeed` pushes
   `{kind:'tool', id: callId}` and `{kind:'approval', id: callId}` from the same call
   (`views/search-v3/sv3-run.ts:190, 194`), and `projectSv3RunPrompts` forwards
   `feed.pendingApprovals` unchanged (`:274`). A bare `prompt.id` would put the same `data-item-id`
   on the tool card and on the hold — a duplicate landmark, and an unreachable hold, since `jumpTo`
   resolves by first match (`primitives/navigation.ts:190`). The suffix is the design's own `:q`/`:a`
   idiom applied to the same problem it was introduced for. Pinned by the duplicate-id test and by
   probe P5 below.
2. **The J/K handler ignores modified chords**; the source does not. `Ctrl+J` and `⌘+K` belong to the
   browser and to the palette, and the plan's own wording is "bare `j` … no modifiers". Strictly
   narrowing, and it is what lets `Sv3Palette.test.ts` keep asserting the shipped chord is untouched.
3. **`Sv3Palette.test.ts`'s "registers NO global key listener" case was narrowed to its subject.**
   The plan cited that case (`:157-167`) as *evidence* that Search v3 attaches no global keydown, and
   it was — but PR-A deliberately adds one, so the case had to be re-derived rather than assumed
   compatible. Its declared contract is the CHORD ("the chord is scoped to the window host so the
   shipped shell's own Ctrl+K keeps working outside it", file header): the assertion is now that
   exactly one global keydown listener exists, that a `Ctrl+K` raised at `window` passes through it
   unconsumed and unprevented with the palette still closed, and that the listener is removed on
   teardown. That last clause is a leak assertion the old "zero removals" form could not make, so the
   case is stronger, not weaker.

### 13.3 Corrections to the plan's reading of the tree

- **§5.3's `active()` predicate is incomplete as written.** `turns.length > 0 || this.recordNotice`
  is the transcript arm's condition only *after* the lock arm has declined it — `render()` takes
  `locked()` first (`Sv3Main.ts:1294`), and that arm renders no `.scroller` at all. Left as written,
  `active()` would stay true across a transcript→locked transition, the controller would never tear
  down, and A2's primary defence would be inoperative in exactly the arm the plan did not enumerate.
  Landed as two extracted getters — `locksTranscript` and `transcriptArmRendered` — with `render()`
  reading both, so the arms and the controller cannot disagree. Probe P7 pins it.
- **§5.2's "one anchor per turn" is false in a third way**, beyond the two A10 names: an AGENT turn
  emits no `.answer` div at all (its answer *is* its activity), so it contributes `:q` plus its run
  steps and never a `:a`. The rendered-item test asserts the exact ordered id list rather than any
  arithmetic over `turns.length`.

### 13.4 Right-reason evidence (revert-probes)

Each probe mutates one thing and runs the suites that must catch it. All eight were caught; the tree
was restored after each.

| Probe | Mutation | Caught by |
|---|---|---|
| P1 | drop `data-item-id` from `runItem`'s tool arm (the named silent failure mode) | 2 wiring/duplicate cases |
| P2 | `active: () => this.nav.landmarks.length > 0` (the A1 deadlock) | 9 cases |
| P3 | remove `if (event.defaultPrevented) return;` (the A4 collision) | the advisory-drawer case |
| P4 | drop `SELECT` from the union (the A5 live bug) | 4 cases across three suites, incl. the retiree's |
| P5 | bare `prompt.id` anchor (§13.2) | 3 cases |
| P6 | restore `setupResize`'s unconditional early-return (the A2 stale binding) | the rebind case |
| P7 | `active()` ignoring the locked arm (§13.3) | the arm-coverage case |
| P8 | remove the Help row | the Help table case |

### 13.4b Independent-review fixes (coordinator, 2026-08-19)

The review approved with fixes; all four are applied, plus one logged.

- **F1 — the landmark list outlives its arm.** `teardown()` releases the observer, listeners, pin and
  viewport but deliberately keeps `landmarks`/`fractions`/`trackPx`
  (`primitives/navigation.ts:376-386`). So after transcript→locked the list is stale-but-non-empty,
  the handler's length guard passed, `preventDefault()` fired, and `jumpTo` then bailed on a null
  `scrollEl()` — a key swallowed to no effect, over a transcript the store is refusing to show. Fixed
  as the FIRST landmark-side guard, `if (!this.transcriptArmRendered) return;`, rather than by
  clearing the list in the shared authority: the first adopter's suite hand-assigns `nav.landmarks`,
  so clearing there risks collateral the port has no reason to take. Regression case asserts both
  `jumpTo` not called AND `defaultPrevented === false`.
- **F2 — two stale cites** corrected (`navigation.ts:171`→`:206` for `el.focus`; the workflow-picker
  `<select>` is `:3987`, `:3986` is its label).
- **F5 — the a11y pin was file-keyed**, so a SECOND `<h1>` inside `Sv3Composer.ts` would have passed
  while the comment claimed "exactly one". Now count-keyed (`{'Sv3Composer.ts': 1}`) and using the
  gate's own patterns (`/<\/h1>/`, `check-a11y-closure.mjs:142, 148`), so the stand-in and the gate
  can differ only in SCOPE, never in what counts as a violation.
- **F3** — see §13.5.
- **F4, logged not fixed:** there is no modal-owns-focus guard. With focus on a palette popup button
  or a drawer control that neither types nor calls `preventDefault`, `j`/`k` jumps the transcript and
  pulls focus out of the open modal. This is parity with the retiree, not a regression; recorded so
  the guard set is not read as exhaustive.

The wiring test is the acceptance gate the plan asked for: it stubs `getBoundingClientRect` per case
(this directory's own idiom, `SearchV3View.pane.test.ts:58` / `sidebar.test.ts:43`) so `measure()`
does not drop every element at `navigation.ts:345`, and asserts `nav.landmarks` equals the stamped id
set in DOM order **read back from the element `scrollEl()` returns** — which is why P1 and P2 both
fail it, and why a controller bound to a detached scroller would too.

### 13.5 What PR-A did NOT do (so nobody reads it as done)

- **The five live-verification rows (§10) are unrun.** They need the shared dev stack. Row 4 (A9) is
  the one that can still change the code: a long streaming answer with 20+ run steps at a narrow
  width, watching for jank. The coalescing above is the plan's specified remedy applied up front, so
  the row now *confirms* a fix rather than deciding whether one is needed — but it is still the only
  question in this design that reading code cannot answer.
- **The `check-a11y-closure` recursive-walk fix is not here** (D-17). PR-A carries the scoped
  stand-in instead: `views/search-v3/sv3-a11y.test.ts` asserts `Sv3Main.ts` declares no `<h1>` and no
  `main` landmark, that no file in the window claims a `main` landmark, and that the set of files
  carrying an `<h1>` is exactly `['Sv3Composer.ts']` — a pin, so the known hero heading stays visible
  as an open item and a *second* one fails immediately. The gate fix must land with the hero-heading
  decision.
- **`KeybindingRegistry` keeps its own SUBJECT but no longer its own PREDICATE** (A5, amended by
  review F3). A5 said not to re-point it, and the reason it gave — it resolves `composedPath()[0]`,
  the event's *origin*, which is a different question from where focus is — justifies not sharing
  `deepActiveElement()`. It does not justify keeping a third inline copy of *what counts as an
  editable*, which is the thing that had already drifted (this file had `SELECT`; the retiree did
  not). So the descent stays un-shared and `const inEditable = isTypingTarget(origin);` replaces the
  inline union — semantically identical, one definition instead of three.
- **No Tier 2** (the minimap), no `navigation-surfaces` register, no `NavigableRun` type — §5.7 and
  §12 stand as written.
- **Nothing from §8's sweep obligations**; they remain 852 S8-S11's.
