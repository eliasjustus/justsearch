# 864 — Search v3 composer focus steal / global-keybinding navigation hazard

```
status:  DIAGNOSED + DESIGNED + PLANNED (2026-08-25); PR A and PR C SHIPPED (2026-08-25),
         PR-0's live repro RAN and CONFIRMED the diagnosis (§4.1 results table).
         B / D / E remain.
         ROOT CAUSE: a focus-authority vacuum on the Search v3 surface — nothing
         ever focuses the composer, nothing signals that it is unfocused, both
         keyboard routes to it are dead residue from the retired window, and focus
         is parked instead on sidebar controls that swap the conversation. A
         printable `Space` typed at what looks like a focused composer activates
         that control; the swap is invisible to the URL because sv3's conversation
         identity is not router-projected, so it leaves no history entry and only a
         reload recovers. NOT a keybinding-guard defect — see §2.5 / §2.11.
         Established statically at source (§2.7a-b, §2.9). The specific INSTANCE
         (which control held focus in the owner's session) is inference until §4.1
         runs. (rev 2, post-adversarial-review: a catalog-eviction alternative was
         raised and WITHDRAWN — its mid-session trigger does not exist; it survives
         as an independent boot-time hazard, §2.7d.)
created: 2026-08-25
updated: 2026-08-25
owner:   session bccfc163-7b8f-4b1a-b9e4-0c011632d8a1
scope:   diagnose → design → plan. No implementation this round.
stack:   dev stack leased elsewhere at authoring time — that pass was STATIC by
         charter. §4.1's live repro RAN 2026-08-25; its results are recorded in
         §4.1 and are what satisfied PR C's gate.
```

## 0. The symptom (owner's live session, 2026-08-20)

Recorded verbatim in `docs/tempdocs/859-sv3-live-findings.md:216-219`, severity **SEVERE**:

> **Composer focus steal (severe):** typing into an unfocused composer fires global
> keybindings and can navigate the reader off the window entirely (hash unchanged, view
> swapped; Escape also exits). Recoverable only by reload. Likely interacts with the
> keybinding registry, not 857's guarded J/K.

Three distinguishable facts in one report:

- **F1 — false focus affordance.** The reader believed the composer had focus. It did not.
- **F2 — unguarded global consumption.** Printable keystrokes reached window-level bindings.
- **F3 — hash-free view swap.** The rendered view changed while `location.hash` did not, and
  Escape also left the surface. Only a reload recovered.

F3 is the discriminating fingerprint: whatever swapped the view did **not** go through the
hash-backed navigation path, which is why the router could not restore it.

## 1. Recorded facts carried into this tempdoc

(Each is a prior observation, cited to its source; re-verified in §2.)

- `KeybindingRegistry.ts:163-167` carries its **own** typing-target predicate — the codebase has a
  history of predicate forks (`docs/observations.d/…:17`: UnifiedChatView's j/k guard omitted
  `SELECT` while the registry's included it; closed by 857 PR-A's shared `isTypingTarget` union).
- `mod+k` (`shell.toggle-palette`) is registered **without a `when` clause on a window-CAPTURE
  listener at boot**, so no later-loaded surface can scope or pre-empt it —
  `docs/observations.md:69`, citing `chrome/Shell.ts:927` + `commands/KeybindingRegistry.ts:178`.
- `mod+l` is `shell.focus-composer` while the tooltip advertises "Copy URL" —
  `docs/observations.md:68`.
- `AdvisoryInboxDrawer.ts:379` bare j/k handler `preventDefault()`s **without**
  `stopPropagation()`, so its keys bubble to every window-level listener.
- `Control.ts:189` — `jf-control` has **no `delegatesFocus`**; programmatic focus must reach
  through the shadow root (852 S4 had to).
- `Sv3Main.ts:1322` — window-level J/K run-step nav has **no modal-owns-focus guard**; the recorded
  note explicitly warns the guard set (`defaultPrevented` + modifiers + typing + arm) must not be
  read as exhaustive. Parity with the retiree, not a regression.

**Parity bar.** UnifiedChatView had the same holes. Search v3 is its *successor* window and 851
retired v2 — parity with a retiree is not a defence here.

## 2. Static diagnosis

Read as a chain: **F1** (focus is nowhere) makes **F2** (globals eat the keys) possible, and F2
reaches **F3** (something swaps the view) only because no layer between them asks the one question
that matters — *"is the reader trying to type right now?"*

### 2.1 The listener stack that is live on the sv3 surface

The four global handlers that matter for this defect, in the order a keystroke meets them. (A first
pass counted "sixteen listeners reachable while sv3 is mounted"; that number mixed app-wide,
overlay-scoped and per-instance listeners and is not a meaningful total — dropped rather than
defended.)

| # | Handler | Target / phase | `file:line` | Keys |
|---|---|---|---|---|
| 1 | `KeybindingRegistry` dispatcher | `window`, **capture** | `commands/KeybindingRegistry.ts:150-181` | registered chords + `/` |
| 2 | `Shell.handleGlobalKey` | `document`, **capture** | `chrome/Shell.ts:1407` → `:1997-2022` | `Alt+←/→`, `Ctrl+D`, `Ctrl+Shift+A` |
| 3 | `SearchV3View.onHostKeydown` | host element, **capture** | `views/search-v3/SearchV3View.ts:929` → `:1993-2024` | `Escape`, `mod+k` |
| 4 | `Sv3Main.onWindowKeydown` | `window`, bubble | `views/search-v3/Sv3Main.ts:1460` → `:1501-1526` | `j` / `k` |

Both #1 and #2 are **capture-phase and registered at boot**, so they run *before* any element
handler in the document — including the composer's own. Nothing a surface mounts later can scope or
pre-empt them (already recorded for `mod+k` at `docs/observations.md:69`).

The rest are overlay- or instance-scoped and are not implicated, but three carry findings the design
must not step on:

- **`components/Control.ts:365`** attaches a `document`-capture Escape listener **per `jf-control`
  instance** — one listener per control on screen, not one per app.
- **`components/advisory/AdvisoryToastHost.ts`** — handler at `:245-257`, bound at `:259` — binds
  `Ctrl+Z`/`Cmd+Z` on `document`, unconditionally and app-wide, `preventDefault()` without
  `stopPropagation()`: a **second, independent** mechanism answering the same chord as the
  registry's `mod+z` → `shell.undo` (`Shell.ts:926-931`). Two authorities for one key.
- **`components/ConfirmDialog.ts:199-211`** — see §2.2, fork #4. Its typing guard is defeated by
  shadow retargeting, though a second gate contains the consequence. Owned by an in-flight PR.

Two properties hold across the **entire** census and both are design inputs:

- **No listener anywhere checks `event.repeat`.**
- **Exactly one checks `event.isComposing`** — `Sv3Composer.onKeydown:1036`, and only on its `Enter`
  branch. Every global is IME-blind.

### 2.2 The typing guard is FOUR forks, not one — and 857's unification missed the worst

857 PR-A unified the *predicate* into `utils/keyboardHandler.ts:64` (`isTypingTarget`) and the
*shadow descent* into `:46` (`deepActiveElement`). Three call sites adopted it. **`Shell.ts` did
not.** It still carries a private inline copy:

```ts
// chrome/Shell.ts:1985-1995 (isInputFocused)
el = el.shadowRoot.activeElement;      // descends — good
const tag = el.tagName?.toLowerCase();
return tag === 'input' || tag === 'textarea' || (el as HTMLElement).isContentEditable === true;
```

It omits `SELECT` — *precisely the omission 857 PR-A was written to close* on the other copy
(`docs/observations.d/…:17`) — and it is the guard on the app's most powerful shortcuts
(`Alt+←/→` history, `Ctrl+D`, `Ctrl+Shift+A` surface swap). The fork survived the sweep.

A **fourth** fork is worse than a coverage gap — it is structurally broken:

```ts
// components/ConfirmDialog.ts:206-207
const target = e.target as HTMLElement | null;
if (target?.tagName !== 'INPUT' && target?.tagName !== 'TEXTAREA') { … }
```

The listener is on `document` (`:164`), *outside* the dialog's own shadow root, so `e.target` is
**retargeted to the host** — `<jf-confirm-dialog>`, never `INPUT`. The guard's condition is
therefore satisfied while the reader is typing the confirm phrase. This is the shadow-retargeting
failure mode the other three forks avoid by using `composedPath()[0]` or `deepActiveElement()`, and
it covers neither `SELECT` nor `isContentEditable`.

**Severity downgraded on review — the guard is broken but a second gate contains it.** `Enter` is
additionally conditioned on the phrase already matching:

```ts
// components/ConfirmDialog.ts:202
if (e.key === 'Enter' && !this.confirmDisabled()) {
```

So this is **not** a mid-phrase misfire: it cannot confirm a destructive action the reader has not
already typed out in full. What it violates is intent — `Enter` inside a deliberately-frictional
typed-confirmation field should not submit at all. Real, worth fixing, **not** the severe bug the
first pass called it. A parallel PR already owns it, so it is struck from this tempdoc's §3.2(a) and
§4.3 to avoid two branches editing the same lines.

Four copies, four different coverage sets, one of them broken. This is direct evidence for the
design conclusion in §3: **per-listener guards drift; the guard must live at one authority.**

The forks also disagree on **subject**, deliberately:

| Call site | Subject | Question it answers |
|---|---|---|
| `KeybindingRegistry.ts:168-170` | `e.composedPath()[0]` | where did the event come **from**? |
| `Sv3Main.ts:1506`, `UnifiedChatView.ts:4878` | `deepActiveElement()` | where **is** focus? |
| `Shell.ts:1998` | own descent from `document.activeElement` | where **is** focus? |

`KeybindingRegistry.ts:164-167` states the divergence in a comment and calls it deliberate. It is
defensible in isolation — and irrelevant to this defect, because **when focus is on `<body>` all
three subjects agree: not editable.** Every guard passes, and every global fires. The guard set is
not weak; it is asking a question that cannot detect this failure mode.

### 2.3 The guard set is missing more than the typing check

`KeybindingRegistry.ts:171-179` applies exactly two tests — modifier-less-while-editable, and
`when`. It has **no** `e.isComposing` (IME) check, **no** `e.repeat` check, and **no**
modal-owns-focus check. `Sv3Main.ts:1502-1515` is the strongest of the four (defaultPrevented +
modifiers + typing + arm + non-empty landmarks) and *still* has no modal-owns-focus check — recorded
at the time, deliberately, so the set would not be read as exhaustive
(`docs/observations.d/…:23`, citing `Sv3Main.ts:1322`).

### 2.4 The `when`-clause escape hatch exists and is INERT — dead substrate

`ShellContext` already declares exactly the two fields this defect needs:

```ts
// state/shellContextState.ts:51
readonly focusKind: FocusKind;          // 'input' | 'result' | 'tab' | 'palette' | 'none'
// state/shellContextState.ts:73-74
/** Palette open state (for keybindings that should only fire when palette is closed). */
readonly paletteOpen: boolean;
```

The comment on `paletteOpen` names this tempdoc's use case verbatim. **Neither field has a single
production writer.** The only `updateShellContext` call sites are:

- `chrome/Shell.ts:1465` — `{ activeSurface }`
- `state/selectionState.ts:227,247` — selection fields
- `substrates/scope/index.ts:171` — profile/scope restore

So `focusKind` is permanently `'none'` and `paletteOpen` permanently `false` for the app's whole
life. Both are nonetheless carried through `updateShellContext`'s equality check
(`shellContextState.ts:156,166`), which makes them *look* maintained.

Consequence, and it is the sharpest finding here: **a predicate over an unwritten slot is a
compile-time constant whose polarity depends on how it is phrased.**

Note the mechanism precisely, because the obvious statement of it is wrong. `whenExpression`'s
`truthy()` returns false **only** for `undefined`/`null`:

```ts
// commands/whenExpression.ts:303-304
function truthy(v: unknown): boolean {
  if (v === undefined || v === null) return false;
```

`focusKind` does not default to absent — it defaults to the **non-empty string `'none'`**
(`shellContextState.ts:102`), which is truthy. So the 543 §3.B "absent slots evaluate false
silently" posture (`shellContextState.ts:80-81`) does **not** apply to it. Instead:

- `when: "focusKind != input"` → `'none' != 'input'` → **constantly TRUE** — the guard never blocks
  anything, and reads as if it does.
- `when: "focusKind == input"` → **constantly FALSE** — the binding never fires at all.

Either way the clause is dead code that looks live, and which failure you get depends on the
phrasing rather than on anything the reader chose. `paletteOpen` (a `false` boolean) is the same
shape. Any design that reaches for `when` must populate the slot **in the same change**. Handle:
`substrate-without-consumer-flavors`.

### 2.5 The single-letter hotkey surface is smaller than the symptom implies

The complete `default`-source registration set is five entries, all in
`chrome/Shell.ts:926-961`: `mod+z`, `mod+shift+z`, `mod+k`, `mod+l`, and **`/`**. No in-repo
manifest contributes `keybindings` (the installer exists at
`substrates/manifest/index.ts:482-495` but has no CORE declarant), so the only modifier-less
printable global is `/`.

`matchesEvent` is AltGr-safe: `KeybindingRegistry.ts:77` requires `parsed.alt === e.altKey`, so
AltGr (which reports `ctrlKey && altKey`) cannot match a `mod+…` chord. **Shell.ts's raw handler is
not** — `Shell.ts:1999` tests `e.altKey && e.key === 'ArrowLeft'` with no `!e.ctrlKey`, so
`AltGr+←` triggers history-back. Relevant on a QWERTZ layout where AltGr is a routine typing
modifier.

**This is the finding that reframes the problem.** A single dead `/` cannot produce "typing
navigated me off the window", and no other bare printable is registered. So the view swap did
**not** come from the keybinding registry at all — which means the phrase *"fires global
keybindings"* in the original report is a reasonable but incorrect inference by the reporter.
§2.7a-b establish what actually fired. Chasing the registry would have been the wrong repair.

### 2.6 `/` is retiree residue: it focuses a composer that no longer exists

`/` → `shell.focus-composer` → `core.action.shell.focus-composer`
(`substrates/actions/index.ts:861-868`) → `deps.focusComposer()` → `Shell.ts:813`:

```ts
focusComposer: () => window.dispatchEvent(new CustomEvent('jf-focus-composer')),
```

The **only** production listener for `jf-focus-composer` is the retired
`views/UnifiedChatView.ts:958` (removed at `:1219`). Search v3 registers none. So on the successor
window, `/` is captured, `preventDefault()`ed at `KeybindingRegistry.ts:176`, dispatched to a
command whose effect nobody hears — a keystroke that is swallowed and does nothing.

That is a `retire-with-a-sweep` residue, and it compounds F1: the app's advertised
"focus the search bar from anywhere" affordance is dead on the one window that has a search bar.
(`views/HelpSurface.ts:67` is separately stale about the binding set — logged to the inbox.)

### 2.7 What is RULED OUT for F3 (hash-free view swap)

**`Ctrl+Shift+A` → the retiree is the obvious suspect, and it is REFUTED.**
`handleAskAiShortcut()` (`Shell.ts:2024-2034`) does swap the reader onto the retired chat window —
`this.activateSurface('core.unified-chat-surface', {}, 'BUTTON')` — and its guard is the broken
fourth typing-predicate fork (§2.2), so it *is* live whenever focus is not a real editable. It is a
tempting fit. But it moves the hash, so it cannot be F3.

`Shell.activateSurface` (`:1813-1822`) is the single canonical activation entry; it dispatches a
`navigate` intent, and `NavigationHandler` resolves the push question with a **default-on** flag:

```ts
// router/navigationHandler.ts:190-197
const push = options?.push !== false;
if (push) { pushAddress({ kind: 'navigate', target: addr.target, state: addr.state }); }
```

`activateSurface` passes **no** `options`, so `push` is `true` and `pushAddress` reaches
`window.history.pushState(…)` (`router/URLProjector.ts:154`). (An earlier reading of this pass
guessed the flag was absent-and-therefore-off; the line says the opposite, and the `!== false`
default is the whole point of the gate — it exists so only *popstate-driven* activations opt out.)

The same disposes of `Alt+←/→` → `navigateBack`/`navigateForward` (`Shell.ts:1999-2008`): those
*are* history operations, so the URL moves by definition. And `goBack()` (`:1872-1876`) and the
folder-drop redirect (`:2060`) both route through `activateSurface`.

**Every surface-level swap moves the hash. None of them is F3.** `Sv3Main.onWindowKeydown` is ruled
out separately: `j`/`k` only, and it scrolls rather than navigates.

**A leftover retiree listener is also ruled out.** The obvious worry after 851/852 is that
UnifiedChatView still has global handlers bound while sv3 is mounted. It does not: its window
listeners are attached in `connectedCallback` (`UnifiedChatView.ts:865,956-958`) and removed in
`disconnectedCallback` (`:1167,1219`) — instance-scoped, not module-load-time. The retiree's residue
in this defect is the **unimplemented command** of §2.6, not a live listener.

The swap therefore did not come from a *surface* change at all. §2.7a names what it did come from.

### 2.7a F3 — the hash-free swap is an IN-WINDOW conversation change (ESTABLISHED)

> **Dated diagnosis — SUPERSEDED for the conversation by PR C (2026-08-25).** Everything below was
> true of `main` when written and was confirmed live (§4.1 L6/L8). The conversation identity is now
> URL-projected (§4.6), so a swap moves the hash and Back undoes it. The rest of sv3's internal
> state — the transcript arm, the composer's hero/docked state — is still unprojected, and the
> reading of the ORIGINAL incident stands unchanged.

**On the Search v3 surface the hash is frozen by construction.** The app *is* hash-routed —
`router/sources/URLSource.ts:88` parses a `justsearch://…` URL out of `window.location.hash`, and a
surface change `pushState`s (`router/URLProjector.ts:154`). But the URL projector refuses this
surface outright:

```ts
// router/URLProjector.ts:71-76
const schema = getSurfaceStateSchema(surfaceId);
if (!schema) {
  // Surface has no declared stateSchema — not URL-addressable; nothing to project.
  return;
}
```

Schemas are registered by the hydrator from the **wire** (`router/surfaceSchemas.ts:41` — *"called
by the hydrator after fetching /api/registry/surfaces"*), and `core.search-v3-surface` is
**frontend-only**: it is declared at `plugin-api/CorePlugin.ts:99-106` and
`grep -rn "search-v3" --include=*.java modules/` returns **zero hits**. No wire entry, therefore no
stateSchema, therefore `activateProjection` returns early and **no sv3-internal state can move the
hash at all** — not the conversation, not the transcript arm, not the composer's hero/docked state.

The conversation identity itself lives in `state/conversationListStore.ts:281`
(`setActiveConversation`) plus a `localStorage` pointer, `controllers/lastViewedConversation.ts:21`
(`'justsearch.lastViewedConversation.v1'`, tempdoc 609 Phase 3) — outside the router entirely.

So **every** sv3 conversation change — switch, new session, hero flip — swaps the entire visible
transcript **with zero hash movement, by construction**, and leaves **no history entry**. That is
F3's fingerprint exactly, and it is also why Back does nothing and only a reload recovers.

**Corollary that matters for reading the original report:** "hash unchanged" is *not* evidence about
*which* sv3-internal thing swapped — it is guaranteed for all of them. It is only discriminating
against a **shell-level surface** change, which is what §2.7 ruled out. The observation narrows the
suspect list to "something inside the sv3 window" and no further.

Three in-window swaps are reachable, each from a *focused control activated by a printable key*:

| Trigger | Control | `file:line` | Effect |
|---|---|---|---|
| select a conversation | `button.row` inside the row's shadow root | `Sv3SessionRow.ts:706-715` → host `@click` at `Sv3Sidebar.ts:446` → `select()` `:264-272` → `SearchV3View.ts:1123,2843` | transcript replaced by a different conversation |
| new session | sidebar `+` button | `Sv3Sidebar.ts:260-262,394` → `SearchV3View.ts:3029-3049` | transcript → blank hero, **and `clearLastViewedConversation()`** |
| hero flip | composer `Escape` | `Sv3Composer.ts:1031-1035` → `SearchV3View.ts:3048` | docked → hero |

### 2.7b The complete chain, and the key that fires it: **Space**

`Sv3SessionRow.focusRow()` puts real focus on the row's **claim (select) control**:

```ts
// views/search-v3/Sv3SessionRow.ts:649-651
focusRow(): void {
  this.shadowRoot?.querySelector<HTMLButtonElement>('button.row')?.focus();
}
```

It is called on two ordinary paths: when a rename is left by key (`Sv3SessionRow.ts:653-661`) and
when a row is discarded and the survivor takes focus (`Sv3Sidebar.ts:364`, inside
`placeFocusAfterDiscard`). The file states the hazard it was fixing outright at `:603-605` —
*"the input is removed when the edit closes, and focus falls to `<body>` — a pointerless reader ends
up at the top of the document having lost the row they were naming."* The remedy parks focus on a
**button that swaps the whole transcript when activated**.

**And when a discard leaves no survivor, focus is parked on the new-session button instead:**

```ts
// views/search-v3/Sv3Sidebar.ts:352-356 (placeFocusAfterDiscard)
if (survivorId === null) {
  this.shadowRoot?.querySelector<HTMLButtonElement>('[data-testid="sv3-sidebar-new"]')?.focus();
  return;
}
```

That is the **more destructive** of the two parking spots — it is the `onSessionNew` trigger, the
one that also calls `clearLastViewedConversation()`. Discarding your last conversation therefore
leaves the keyboard armed on the control that wipes the rehydration pointer.

Put together:

1. The reader renames a conversation, or a row is discarded → focus lands on `button.row`.
   (Or the reader simply never focused anything — §2.9(a): nothing on this surface ever autofocuses
   the composer, so `<body>` is the default.)
2. The reader looks at the composer — a large, centred, glass-filled box with placeholder text and
   no "not focused" signal (§2.9) — and starts typing. Perhaps first pressing `Ctrl+L` or `/`, the
   app-wide "focus the search bar" shortcut, which is **dead on this window** (§2.6) and reports
   nothing.
3. The letters are harmless: every guard correctly reports "not a typing target", so they reach the
   globals, where `j`/`k` scroll the transcript and `/` is swallowed.
4. **The first word break presses `Space`.** A focused `<button>` activates on `Space` *and*
   `Enter` — platform behaviour, no app code involved. Note the shadow crossing that makes this
   reach: `button.row` lives in the row's shadow root (`Sv3SessionRow.ts:706-715`) and carries no
   `@click` of its own, but the activation click is `composed`, so it crosses the boundary to the
   host's `@click=${() => this.select(row.id)}` (`Sv3Sidebar.ts:446`) → `SV3_SESSION_SELECT`
   (`:264-272`) → the transcript is replaced by a different conversation, **hash unchanged, no
   history entry, reload the only way back.**

If focus was on the sidebar's **new-session** button instead (`Sv3Sidebar.ts:394`), the same `Space`
runs `onSessionNew()` (`SearchV3View.ts:3029-3049`): the run is detached, the pane closed, the draft
cleared, and `clearLastViewedConversation()` wipes the rehydration pointer — so even a reload lands
on a blank hero.

**"Escape also exits" is consistent** and is a *different* handler:
`SearchV3View.onHostKeydown` (`:1993-2024`) is host-scoped and capture-phase, and the sidebar is
inside the host, so an Escape pressed with a row button focused runs the Escape ladder and hits
`if (event.key === 'Escape' && this.paneDocPath !== null) { … this.closePane(); }` (`:2011-2016`) —
closing the evidence document the reader had open. From the reader's seat that is the same class of
event: a key they meant as text tore down what they were reading.

### 2.7c Why this is a design defect, not a keybinding bug

`Space`-activates-a-focused-button is correct platform behaviour and must not be "fixed". The defect
is that **the sidebar's focus-parking choice and the composer's focus-affordance failure compose
into a destructive action**, and nothing in between notices that the reader is mid-sentence. No
guard in §2.1 can catch this: the registry never sees the event as a binding, `Sv3Main`'s guard
correctly declines, and the button handler is the browser's.

That is the finding that shapes §3: this cannot be fixed by tightening key guards alone.

### 2.7d A standing hazard, NOT a competing hypothesis: catalog eviction

A parallel audit lane proposed this as an alternative F3 mechanism, and the first draft of this
tempdoc ranked it second. **Review established the trigger it depended on does not exist**, so the
ranking is withdrawn — it cannot explain this incident. The underlying hazard is real, sharper than
first described, and worth its own fix; that is why the section stays.

**What verifies.** `core.search-v3-surface` is frontend-only (above) and reaches the live catalog
only via `mergePluginSurfaceContributions`, which copies the existing map
(`api/registry/SurfaceCatalogClient.ts:462` — `const next = new Map(entriesById);`). But any wire
fetch **replaces** it wholesale:

```ts
// api/registry/SurfaceCatalogClient.ts:248-256
function rebuildIndex(catalog: SurfaceCatalog): void {
  const next = new Map<string, Surface>();
  for (const entry of catalog.entries) { next.set(entry.id, stampFactory(entry)); }
  entriesById = next;
```

The merge site's own comment (`:470-472`) states the ordering it depends on — *"`entriesById` was
populated by bootSurfaceRegistry from /api/registry/surfaces BEFORE this merge runs"* — and nothing
enforces it. If a `rebuildIndex` runs *after* the merge, sv3 is silently evicted: the stage resolves
`undefined` (`Shell.ts:2278-2280`) and renders `No surface selected.` (`:2998-3000`) with `activeId`
and the hash untouched; re-navigating dies at `isKnownSurface`
(`router/navigationHandler.ts:129-132`, `Shell.ts:1448-1449`); and since sv3 is
`placement: 'DEEPLINK'` (`CorePlugin.ts:105`) there is no rail button either.

**What is FALSE — the mid-session trigger.** The first draft claimed the backend's mid-session death
(859 §7's heap climb) could re-enter `rebuildIndex`. It cannot. All three production callers
(`:291`, `:363`, plus a test seed at `:566`) sit inside `bootSurfaceRegistry`, which early-returns
once boot has completed:

```ts
// api/registry/SurfaceCatalogClient.ts:284-287
if (bootAttempted && entriesById.size > 0) { … return; }
bootAttempted = true;
```

There is **no mid-session catalog refresh path at all**. Only a reload re-enters it — visibly. So
this cannot produce a swap during a live typing session, which is why it is no longer ranked as an
explanation for the incident.

**What review found instead, and it is sharper: a real boot-time race.** `bootSurfaceRegistry` has
**two** call sites — `main.jsx:134` (awaited) and `i18n.ts:56` (inside a `Promise.all`). The guard
above is not re-entrancy-safe across them: if i18n's call sets `bootAttempted = true` (`:287`) while
its fetch is still in flight, `entriesById` is still empty, so main.jsx's call does **not**
early-return; it fetches, `rebuildIndex` populates, and `mergePluginSurfaceContributions` adds sv3.
Then i18n's late fetch resolves, calls `rebuildIndex` at `:363`, and **replaces the map — evicting
every plugin contribution, sv3 included.** The window would be missing from boot, not lost
mid-session.

**Worth fixing on its own terms.** Every FE-only surface is exposed, and 852 promoted sv3 to primary
status while leaving it out of the Java `CoreSurfaceCatalog`. Options: add it to the wire catalog,
make `rebuildIndex` re-apply plugin contributions, or make the boot guard re-entrancy-safe. Logged
to the inbox with the race cited; **not** in this tempdoc's PR sequence — different subsystem,
different blast radius, and no longer a candidate explanation for this defect.

### 2.7e A lane disagreement, resolved at source

The same lane claimed the `Escape` → hero morph "destroys the focused `<textarea>`", which would
have made the morph an F1 contributor. **That is wrong**, and §2.9's ruling-out stands: only
`.landing()` is conditional on `state === 'hero'` (`Sv3Composer.ts:1122`); the
`.glass`/`.field`/`.editor`/`<textarea>` subtree is unconditional in the same template
(`:1129-1148`), so Lit preserves element identity across the transition, and a View Transition
crossfades a screenshot rather than recreating nodes. L11 in §4.1 is the falsifier that settles it
empirically rather than by argument.

### 2.8 Honest limits of this static pass

Established from source: **F1** (§2.9), **F3's mechanism class** (§2.7a) and **the chain that joins
them** (§2.7b). What a static pass genuinely cannot settle:

- **Which control actually held focus in the owner's session, and which key fired.** §2.7b names
  `Space`-on-a-focused-`button.row` as the best-fitting single explanation, and it fits every
  reported detail — but the session recorded no `document.activeElement`. The *class* is proven;
  this *instance* is inference. §4's repro leg is what converts it.
- **Whether a user keybinding override was in play.** `KeybindingRegistry.ts:198-222` loads
  overrides from `UserStateDocument`; a plain-letter override would add hotkeys the static table
  cannot see. Only the live profile settles it. (Not needed for the fix — the design in §3 does not
  depend on which key it was.)
- ~~Whether §2.7d (catalog eviction) was what the owner actually hit.~~ **Closed by review**: its
  trigger is boot-scoped and there is no mid-session refresh path (§2.7d), so it is not a candidate
  explanation. It remains a standing hazard, watched passively by L13/L14.
- **The exact pixel boundary of the composer's click-to-focus dead zone** (§2.9(b)) — the shape is
  proven from CSS; the measurement is not.
- Live measurement is deferred by charter — the dev stack is leased elsewhere. §4 specifies the
  repro leg, which is a **falsification** exercise, not a confirmation one: the `interrogate-results`
  trap here is that the predicted repro is *exactly* the kind of expected result that feels like
  proof without being it.

### 2.9 F1 — why the composer looked focused (ESTABLISHED)

The plainest possible answer, and it holds up: **nothing ever focuses the sv3 composer except a
click landing precisely on the `<textarea>` rectangle.** Not a load, not a record open, not a
morph, not a shortcut. `<body>` keeps focus by default, and the surface never says so.

**(a) There is no autofocus anywhere.** Grepping `\.focus(` / `autofocus` under `views/search-v3/`
finds the **new-session** button (`Sv3Sidebar.ts:355` — not a rename button, as a first pass
mislabelled it; see §2.7b), the session row's claim control and rename input
(`Sv3SessionRow.ts:650,667`), a decision-gate button (`SearchV3View.ts:2767`), the palette
(`Sv3Palette.ts:390,399,493`) and the composer's own effort/tier menu triggers
(`Sv3Composer.ts:1393,1420,1446`). **Every one of them is a control; none targets the composer
textarea.**
`Sv3Composer.ts` has no lifecycle `.focus()` at all, and every `SearchV3View` reference to
`this.composer` is `.draft` / `.clearDraft()` (`:873,2196,2436,2481,3044`) — never focus.

**(b) The click target is much smaller than the thing that looks clickable.** The only handlers in
`Sv3Composer.render()` (`:1107-1159`) are `@input`/`@keydown` on the textarea itself
(`:1132-1139`). There is no `@click` or `pointerdown` on `.band` / `.glass` / `.field` / `.editor`,
no wrapping `<label>`, and the `.placeholder` span is `pointer-events: none` (`:536`). Dead zones
that read as part of the same field:

- `.field` padding — `space-4 space-4 space-2` hero / `space-2 space-3 space-1` docked (`:495-502`);
- the whole footer row (mode, effort, model-label, send/stop) (`:1150-1155`);
- the `.glass::after` 1px outline + highlight (`:470-479`) — the rounded-rect ring that frames the
  entire box as "the input", most of whose interior is not click-to-focus.

**(c) The keyboard route to focus is dead on this window** — §2.6. `Ctrl+L` and `/` both
`preventDefault()` and dispatch into silence. A reader with app-wide muscle memory presses the
shortcut, sees nothing happen, and types.

**(d) `delegatesFocus` is used nowhere in the frontend.** `grep delegatesFocus modules/ui-web/src`
returns **zero matches**. `Control.ts`, `Sv3Composer.ts:172` and `Sv3Main.ts:258` all extend
`JfElement` with no `createRenderRoot` / `shadowRootOptions` override. Note the contrast: the
*retiree's* composer rendered into **light DOM** (`components/Composer.ts:147 override
createRenderRoot(): HTMLElement { … return this; }`), so it never had a shadow boundary for focus to
cross. Search v3 did not inherit that escape hatch, and did not replace it. This does not cause the
current defect (nothing calls `.focus()` on the host today) but it is a **loaded gun aimed at the
fix**: any remedy that calls `.focus()` on `<jf-sv3-composer>` or `<jf-control>` will silently
no-op. The app's `deepActiveElement()` (`utils/keyboardHandler.ts:46-50`) is a manual workaround for
this same gap on the read side.

**Two plausible-sounding mechanisms are RULED OUT by source**, and saying so matters as much as the
positives:

- **The focus ring is honest.** `Sv3Composer.ts:483-486` keys it off the real pseudo-class —
  `:host(:has(textarea:focus-visible)) .glass::after { border-color: var(--ring); … }` — not off a
  `hero`/`docked`/`morphing` state flag. The composer *cannot* paint a focused ring while nothing is
  focused. The illusion is not a false ring; it is the **absence of any "not focused" signal** on a
  large, centred, glass-filled box with placeholder text that looks identical either way.
- **The morph does not drop focus.** `sv3-composer-morph.ts:177-211` runs hero↔docked through the
  View Transitions API plus a reflected `state` attribute (`Sv3Composer.ts:876`). Only `.landing()`
  is conditional on `state === 'hero'` (`:1122`); the `.glass`/`.field`/`.editor`/`<textarea>`
  subtree is unconditional — **same element identity across both states**. View Transitions crossfade
  a screenshot; they do not recreate the transitioning node.

**(e) The modal-owns-focus gap is real, but the gesture that reaches it is `Tab`, not the arrows.**
The first pass said arrow keys move focus onto a palette row; they do not. `moveHighlight`
(`Sv3Palette.ts:414-418`) only advances an integer — `this.highlight = (this.highlight + delta +
count) % count` — and the rows are `role="option"` in a `role="listbox"` (`:502`, `:567`), so **real
focus stays in the palette's `<input>`**, where `isTypingTarget` correctly guards it. Arrows are
safe.

The leak needs focus on a *non-editable* control inside the popup, which `Tab` provides:
`trapTab` (`:480-486`) enumerates `'.popup input, .popup button, .popup [tabindex]:not([tabindex="-1"])'`
and cycles focus among them. Once focus is on a popup **button**, a bare `j`/`k` is unhandled by
`Sv3Palette.onKeydown` (`:437-473`), falls through **without `preventDefault()`**, reaches
`Sv3Main.onWindowKeydown`, passes every guard (a button is not a typing target), and
`nav.jumpTo(...)` pulls real focus into the transcript **while the palette is still visibly open**.
The palette's own close paths (`hide()`/`dismiss()`, `:393-412`) are well-behaved; the gap is the
window listener's missing "a modal owns focus" guard — as recorded at `docs/observations.d/…:23`.

### 2.10 Ranked F1 mechanisms

1. **Dead `Ctrl+L` / `/` focus-composer shortcut on sv3** (§2.6) — the reader's deliberate,
   app-advertised attempt to focus the composer is captured, `preventDefault`ed, and dropped, with
   no failure signal.
2. **No autofocus on any entry path** — fresh hero, record load, session open all leave `<body>`
   focused under a composer that looks primed.
3. **Click-to-focus dead zones inside the visually unified glass box** — padding, footer, ring.
4. **No `delegatesFocus` anywhere** — a standing hazard that will silently defeat a host-level
   `.focus()` fix.
5. **Modal-owns-focus gap** — the inverse illusion; same root (no focus-ownership authority).

## 2.11 What this is really about

Three framings were available. Naming which one is right is most of the design.

1. *"A keybinding fired when it shouldn't."* — the original report's framing, and **wrong**
   (§2.5). There is one bare printable global on this surface and it is inert.
2. *"The guards are too weak."* — tempting, given four forks and a broken one (§2.2), and it
   produces real hygiene work. But **no guard could have caught this**: the fatal key was `Space`
   on a focused `<button>`, which is the platform's job, not the app's (§2.7c). A tempdoc that
   stopped at guard-tightening would ship a green PR and leave the defect live.
3. *"Nothing on this surface owns focus."* — the one that explains all three facts.

**The root cause is a focus-authority vacuum.** Search v3 has a primary text input; it never focuses
it, never signals that it isn't focused, provides no working keyboard route to it, and parks focus
instead on controls that swap the view irreversibly. Every individual component is defensible. The
composition is destructive.

### The parity note is worse than parity — this is a REGRESSION

The charter's framing was that UnifiedChatView had the same holes, so the successor's bar is higher.
The evidence says something sharper: **on focus, sv3 is materially worse than the window it
replaced.**

| | UnifiedChatView (retired) | Search v3 |
|---|---|---|
| composer DOM | **light DOM** — `components/Composer.ts:147 override createRenderRoot(): HTMLElement { … return this; }` — no shadow boundary for focus to cross | shadow DOM, no `delegatesFocus` |
| `Ctrl+L` / `/` | **works** — `UnifiedChatView.ts:958` listens, `:3136-3139` reaches the textarea | **dead** — no listener (§2.6) |
| conversation in URL | also not projected — it imports the same `lastViewedConversation` pointer (`UnifiedChatView.ts:310-312`) | not projected (§2.7a) |

The third row is **genuine parity, not a regression** — both windows keep conversation identity out
of the router. It is listed so the table is not read as making a claim it cannot support: Layer 3(a)
is a fix for a long-standing shape, not for something 852 broke. The first two rows are the
regression.

851 retired v2 and 852 promoted sv3, but `shell.focus-composer`'s only consumer stayed on the
retiree. That is `retire-with-a-sweep` read from the other end: the *replacement* inherited the
command's advertisement without its implementation.

### Broader shape, for the register rather than for this fix

The `focusKind` / `paletteOpen` finding (§2.4) is an instance of a shape worth naming:
**a declared-but-unwritten state slot is worse than an absent one**, because it makes the guard that
reads it look present. `whenExpression`'s documented "absent slots evaluate false silently"
(`shellContextState.ts:80-81`) is a reasonable posture for *aspirational* slots (543 §3.B named five
deliberately) and a trap for slots something already reads. The distinction the codebase lacks is
between "declared and inert by design" and "declared and inert by accident". Not this tempdoc's job
to build that, but it is the generalisable lesson, and it constrains §3: **no design here may rest
on a `when` clause without populating the slot in the same change.**

## 3. Design

Four layers. They are ordered by how much of the defect each removes, **not** by effort — and the
ordering matters because layer 2 is the one that feels like the fix and isn't.

### 3.1 Layer 1 — the composer owns focus (this is the actual fix)

Removes F1, and with it the precondition for the whole chain.

**(a) Focus the field on entry.** `Sv3Composer` gains a public `focusField()` that reaches its own
textarea (the retiree's `onFocusComposer` at `UnifiedChatView.ts:3136-3139` is the shape to copy —
`shadowRoot.querySelector`, because there is no `delegatesFocus`). Call it on first mount and on
every entry that leaves the reader at a ready composer: fresh hero, session select, new session,
record load. Do **not** call it while a transient owns focus, and do not steal focus from a reader
who has already put it somewhere.

**(b) Click anywhere on the band focuses the field.** A `@pointerdown` on `.glass` (or `.band`) that
calls `focusField()` when the event's `composedPath()[0]` is not itself interactive — so the footer
controls, the notice's links, and the menus keep their own clicks. This kills the dead zones in
§2.9(b) without restyling anything.

> **Considered and not chosen: `delegatesFocus: true` on the composer's shadow root.** It would give
> both halves at once — host `.focus()` reaches the field, *and* the platform focuses the first
> shadow-tree focusable when a non-focusable part of the host is clicked. It is the structurally
> right answer and it is why `Control.ts:189` was flagged in 852 S4. It is not chosen *here* because
> the repo uses it in **zero** places (§2.9(d)), it makes the host itself focusable (a second
> `:focus` target beside the `:has(textarea:focus-visible)` ring at `Sv3Composer.ts:483-486`), and
> "first focusable in the shadow tree" is order-dependent — the degradation banner and notice render
> *before* the field. Adopting it is a defensible separate change with its own verification; folding
> it into a severe-defect fix is how a fix acquires a second defect.

**(c) Make `/` and `Ctrl+L` work on this surface — sweep the residue.** `shell.focus-composer`
currently dispatches `jf-focus-composer` into silence (§2.6). Two options, and the choice is a real
one:

- **(c1) sv3 listens for `jf-focus-composer`** — smallest diff, restores parity with the retiree,
  keeps the window-event indirection. Its cost is that the indirection is exactly what let the
  command go silently unimplemented for a whole window.
- **(c2) Give the command a real target** — resolve the active surface's composer rather than
  broadcasting and hoping. Larger, and it makes "no surface implements this" a legible failure
  instead of a silent one.

**Recommendation: (c2)**, because the failure mode this tempdoc is fixing *is* a silently
unimplemented command. Taking (c1) accepts a known trap for a smaller diff — reasonable only if
scope pressure demands it, and it should be recorded as a deliberate debt if so.

**(d) Say when the composer is NOT focused.** §2.9 established that the ring is honest and the
placeholder is not: a large glass box with placeholder text looks identical focused and unfocused.
With (a) in place the unfocused state becomes rare, so this is the smallest of the four — but it is
the one that protects readers on the paths (a) misses. A resting-state treatment on the field (not
a new ring — a de-emphasis that lifts on focus) is enough.

### 3.2 Layer 2 — one guard authority (hygiene; would NOT have prevented this)

State that plainly at the top of the PR, so the layer is not mistaken for the fix.

**(a) Collapse the typing-target forks** (§2.2). Point `Shell.isInputFocused`
(`Shell.ts:1987-1995`) at `isTypingTarget(deepActiveElement())` — it is a verbatim re-implementation
of the descent that already omits `SELECT`, and it guards the app's most powerful chords. **This is
the whole of (a).** `ConfirmDialog` is owned by an in-flight PR (§2.2) and is deliberately excluded
so two branches do not edit the same lines.

> **DONE — amendment A6 (2026-08-25, verified from source at PR D time, not from a changelog).** Both
> halves of (a) have landed and neither is in PR D's scope:
>
> - `ConfirmDialog`'s retargeted `e.target` guard — **#541**. `components/ConfirmDialog.ts` no longer
>   carries the fourth fork.
> - `Shell.isInputFocused`'s `SELECT` omission — **#546**. `Shell.handleGlobalKey` now opens with
>   `if (isTypingTarget(deepActiveElement())) return;` and the private descent is gone
>   (`chrome/Shell.ts`, comment "864 Layer 2(a) — THE shared typing guard"); the regression case is
>   `chrome/Shell.globalKeys.test.ts`. §4.3 tests 6 and the `SELECT` case are therefore closed, and
>   PR D **struck them from its own scope** rather than re-doing them.

The *subject* asymmetry stays: `KeybindingRegistry` asking about the event's origin and `Sv3Main`
asking where focus is are genuinely different questions, and `KeybindingRegistry.ts:164-167` argues
it correctly. **Unify the predicate; leave the subject to each caller.**

**(b) Add `event.isComposing` and `event.repeat` — at all three sites, in one PR.** The first
draft said "add them to the registry dispatcher", which **would have created a fifth fork**: only
`/` and the four `mod+…` chords go through the registry. `Shell.handleGlobalKey` is a raw
`document`-capture listener (`Shell.ts:1407`) and `Sv3Main.onWindowKeydown` a raw `window` listener
(`Sv3Main.ts:1460`) — neither is a registered binding, so neither would have inherited the fix. That
is the exact defect this layer exists to end.

Two ways to satisfy it; **choose one and apply it to all three**:

- **(b1) One dispatcher** — route Shell's four raw chords through `registerKeybindingEntry` so the
  registry is genuinely the only global-key authority. Structurally the right answer; the larger
  change, and it must preserve `Alt+←/→`'s history semantics.
- **(b2) One shared helper** — a `shouldIgnoreKeyEvent(e)` beside `isTypingTarget` in
  `utils/keyboardHandler.ts` covering `isComposing` + `repeat`, adopted at all three sites in the
  same PR.

**Recommendation: (b2)** for this tempdoc — it achieves the guarantee in one reviewable diff and
does not entangle the fix with `Alt+←/→`'s history behaviour. (b1) is the better end state and
should be its own charter. Taking (b2) is a deliberate deferral, recorded as such.

Related and in the same family, small and real: `Sv3Composer.onKeydown` (`:1031-1036`) tests
`Escape` **before** it tests `isComposing` — so an `Escape` dismissing an IME candidate window also
flips the composer to hero. Fix in the same PR.

> **DONE — amendment A7 (PR D).** (b2) is implemented: `shouldIgnoreKeyEvent(e)` lives beside
> `isTypingTarget` in `utils/keyboardHandler.ts` and covers `isComposing` + `repeat`. Adopted at all
> three named sites in the one PR — `KeybindingRegistry`'s dispatcher, `Shell.handleGlobalKey`,
> `Sv3Main.onWindowKeydown` — **and at a fourth global site the design's census missed**:
> `UnifiedChatView.onConversationKeydown` is a live `window` `j`/`k` listener with the identical
> shape (`views/UnifiedChatView.ts`, registered at `:956`), so it takes the same helper rather than
> becoming the one handler that still leaks. Four ADOPTERS of one helper, not a fourth fork —
> `grep -c` for the two old inline shapes returns zero. One case per site pins it (§4.3 test 7).
>
> **(b1) — one dispatcher — remains the better end state and is NOT done.** It is deferred, with its
> reason unchanged (it entangles this fix with `Alt+←/→`'s history semantics) and its second
> motivation now explicit: (b1) is also what would make Layer 4's policy fully gate-enforceable
> (§3.4). It needs its own charter.
>
> `Sv3Composer.onKeydown`'s `Escape`-before-`isComposing` ordering is **fixed in PR D**, as this
> section asked: the handler now tests `event.isComposing` first, so an `Escape` dismissing an IME
> candidate window no longer flips the window to hero mid-draft. It takes the raw property rather
> than the shared helper deliberately — `shouldIgnoreKeyEvent` also swallows `repeat`, and a held
> `Enter`/`Escape` in a text field is a legitimate composer gesture. The shared helper is for GLOBAL
> handlers; this is the field's own.

**(c) Populate `focusKind` and `paletteOpen`, or delete them.** Per §2.11, a `when`-based guard over
an unwritten slot passes silently forever. If the design uses either field, the same change must add
its writer; if not, they should go, so the next author does not reach for them. Either resolution is
acceptable — **carrying them forward unwritten is not.**

**(d) Modal-owns-focus guard** on `Sv3Main.onWindowKeydown` (`docs/observations.d/…:23`,
`Sv3Main.ts:1322`). Note this is only meaningful once (c) gives it something true to read.

> **DONE — amendment A8-adjacent, PR D. (c) resolved by DELETION; (d) does not depend on it.**
>
> **(c): both fields are gone** from `state/shellContextState.ts` — the type `FocusKind`, the two
> declarations, the two defaults, and the two lines in the equality check — plus the four residue
> mentions in `substrates/scope/index.ts` and the two test files. A comment in their place names the
> rule and its evasion ("do not re-declare a slot here without its writer"). Deletion rather than
> back-fill, because the alternative was a SECOND authority for a fact the app already tracks.
>
> **(d): the guard's shape is `modalOwnsFocus()`** — a predicate exported from the app's existing
> modality authority, `primitives/modality.ts`, reading the same reference count `ModalityController`
> already keeps for the background scroll-lock (one counter, two consumers). Adopted by
> `Sv3Main.onWindowKeydown` (the L10 fix), `UnifiedChatView.onConversationKeydown`,
> `Shell.handleGlobalKey`, and — for modifier-less bindings ONLY — the registry dispatcher. Chorded
> bindings are deliberately still dispatched under a modal: `mod+k` is how the palette is toggled,
> and blocking it would trap the reader inside what they opened.
>
> **The design's "(d) is only meaningful once (c) lands" is superseded, and the correction matters.**
> It assumed the guard would read a `when`-clause slot. It cannot: a `when` clause only binds
> registered bindings, and every listener the L10 defect runs through is a raw `window`/`document`
> listener (§3.4's own finding, one paragraph later). So the guard reads the modality authority
> instead, which needed no new writer — and (d) is therefore **independent of (c)**, not gated on it.
>
> `Sv3Palette` had to join that authority to be seen: it is a modal (`role="dialog"`,
> `aria-modal="true"`, a click-swallowing backdrop) that the platform does not know about, because it
> is window-scoped rather than a native `<dialog>` (the header's deliberate choice). It now composes
> `ModalityController` — entering on `show()`, exiting on both `hide()` and `dismiss()` — which also
> gives it the background scroll-lock it never had. Focus-restore stays the component's own
> (`skipFocusRestore` on both exits): `hide()` returns focus to the invoker and `dismiss()`
> deliberately does not, a distinction the controller's saved-focus cannot make.
>
> **Independent review round (#557, APPROVE-WITH-FIXES) — applied in the same PR.** Reviewer ≠
> committer, per `independent-reviewer-required`. What it caught, and what changed:
>
> - **F1 — the leaked-count guard had no test.** `ModalityController.hostDisconnected` is the third
>   exit, and the only one with no caller to remember it (a palette torn down while open runs neither
>   `hide()` nor `dismiss()`). Post-864 a leak is not a stuck scroll-lock — it is **every global key
>   dead for the rest of the session**, silently. Now pinned: `Sv3Palette.test.ts` "864 F1: a palette
>   torn down while OPEN releases modality"; verified red against a disabled `hostDisconnected`.
> - **F2 — `IndexingOverlay` was a modal that had not joined the authority.** `role="dialog"
>   aria-modal="true"` over a `pointer-events: auto` full-screen backdrop, exempt from
>   `check-modality-contract` as a "presentational center-slot backdrop" — an exemption written
>   before that count became the KEYBOARD authority. It joins now (enters on `connectedCallback`; its
>   host renders the element only while the overlay is up, so mounted === modal and one teardown
>   covers every withdrawal path). The gate's exemption note is corrected in place; `DragOverlay`
>   stays out deliberately (a pointer-drag affordance, not a keyboard-owning layer). Both edges
>   pinned, plus the consumer half in `Sv3Main.navigation.test.ts`.
> - **F3 — the gate and the runtime disagreed about Shift.** The gate treated `shift+…` as exempting;
>   the dispatcher's test is `!mod && !ctrl && !meta && !alt`, so it does not. The runtime's answer
>   wins (a `shift+?` binding is still a printable a reader types), the gate's modifier set drops
>   `shift`, and both sides now carry the same documented constant plus a runtime case.
> - **F4 — a comment claimed more than was true.** Corrected at the registration site: `/` works on
>   `core.unified-chat-surface` and is still swallowed on sv3, unchanged from `main`; PR B (Layer
>   1(c2)) is what makes it reach sv3's composer.
> - **F5/F6 — gate hygiene.** `when`-detection runs through the shared `stripComments` (the 698
>   precedent), so a commented-out `when:` cannot satisfy the policy; the bite is now a committed
>   self-test (`check-printable-keybinding-policy.test.mjs`, 16 assertions) rather than something a
>   reviewer reproduces by hand.
> - **F7 — logged, not fixed:** `SearchV3View`'s `palette?.open` is a narrower second answer to the
>   question `modalOwnsFocus()` now owns, and the module-state modality depth is a test-isolation
>   hazard (a suite that leaves a modal open makes later global-key assertions pass for the wrong
>   reason). Both are in the inbox.
>
> The review also confirmed the recipe defect this PR logged is worse than reported: the comma form
> exits 2 and repeated `--gate` flags are silently last-wins, so **the verbatim recipe runs zero
> kernel gates**.
>
> **`trapTab` was fixed in the same PR** (§2.9(e)'s second half). It used to hand `Tab` back to the
> platform whenever focus was not already on an edge stop — and the rows are `role="option"`, driven
> by `aria-activedescendant` and deliberately not tab stops, so focus resting on one let the next
> `Tab` walk out to `<body>` (the live session's second symptom). It now consumes every `Tab` reaching
> an open palette and lands focus on a stop by construction, wrapping in the asked-for direction.

### 3.3 Layer 3 — make the swap survivable (the safety net)

Layers 1-2 reduce the probability. This is the layer that bounds the *damage* when it happens
anyway, and it is what turns "recoverable only by reload" into a non-event.

**(a) Project sv3's conversation identity to the URL.** Today it lives in
`conversationListStore` + `localStorage` (§2.7a) and is invisible to the router, so a swap leaves no
history entry and Back does nothing. Registering the conversation as a URL-projected store makes the
swap undoable by the browser's own Back, and — not incidentally — would have made the owner's
"hash unchanged" observation *diagnostic* instead of merely true. This is the single highest-value
item in the tempdoc after Layer 1.

**(b) `onSessionNew`'s `clearLastViewedConversation()`** (`SearchV3View.ts:3047`) is the one step
that defeats even a reload. It is correct for a *deliberate* new session and catastrophic for an
accidental one. With (a) in place it is survivable; without (a) it deserves re-examination.

**(c) Do not park focus on an unmarked view-swapping control.** `Sv3SessionRow.focusRow()`
(`:649-651`) parks focus on `button.row` — the control that swaps the conversation — after a rename
or a discard. The a11y intent behind it (tempdoc 831: don't drop a pointerless reader at
`<body>`, stated at `:603-605`) is **right and must be preserved**; the problem is that the parked
control is an armed trigger with a focus ring the reader was not looking at. Options, in order of
preference: (i) keep row focus but make its focus indicator unmissable; (ii) return focus to the
composer, which matches what the reader almost always does next but abandons "keep my place";
(iii) leave as-is and rely on (a). **(i) plus (a)** is the recommendation — it keeps 831's guarantee
and removes the irreversibility rather than the focus.

### 3.4 Layer 4 — the printable-key policy

The charter asks for a decision. **Proposed policy:**

> A modifier-less **printable** character may be a global keybinding only if it carries a `when`
> clause scoping it to named surfaces, and it is inert by default on any surface that owns a primary
> text input.

This is implementable **today** without new state: `activeSurface` is the one `ShellContext` field
with a live writer (`Shell.ts:1465`), and it is exactly what the clause needs — a point in its
favour over anything keyed on `focusKind` (§2.4).

**The policy's reach is narrower than it looks, and the first draft obscured that.** A `when` clause
only binds things that go through `registerKeybindingEntry` — which on this surface is `/` and the
four `mod+…` chords, and nothing else. It cannot reach `j`/`k` (a raw `window` listener,
`Sv3Main.ts:1460`) or Shell's `Alt+←/→` / `Ctrl+D` / `Ctrl+Shift+A` (a raw `document` listener,
`Shell.ts:1407`). The draft's reassurance that "the policy scopes bindings, it does not ban letters"
was therefore misleading — it implied `j`/`k` were governed when they are not. **Struck.**

So the policy's *subject* must be stated as the behaviour, not the mechanism:

> …applies to **any modifier-less printable character handled at `window` or `document` scope**,
> however it is wired.

Enforcement is then split, and that split must be named rather than glossed:

- **Registered bindings** — gate-enforceable, since the registration sites are enumerable.
- **Raw `window`/`document` listeners** — **review-tier only** (~70%). No gate can see them today.
  Layer 2(b1) is what would close this: routing every global chord through one dispatcher makes the
  whole policy gate-enforceable. That is the second argument for (b1) as the eventual end state.

Consequences to accept honestly:

- `/` (`Shell.ts:956-961`) gains a `when`. Under Layer 1(c2) it resolves a real target, so scoping
  it costs nothing.
- sv3's `j`/`k` stay, governed by **review** rather than by the clause. They are safe only because
  Layer 1 makes the composer focused by default — that dependency is why **Layer 4 must not ship
  without Layer 1**.
- Non-printable keys (`Escape`, `Enter`, arrows, `F2`) are out of scope. They have their own
  ordering problem — `SearchV3View`'s Escape ladder (`:1993-2024`) is careful and good, and nothing
  here should disturb it.

> **DONE (the enforceable half) — amendment A8, shipped in PR D rather than PR B.** The §4.2 table
> assigned Layer 4 to PR B; the policy's *gate* half moved to PR D because it is a keyboard-guard
> change and belongs with the rest of them. PR B keeps Layer 1(c2) — giving `shell.focus-composer` a
> real target — which is the behavioural half and is untouched here.
>
> **The gate**: `scripts/ci/check-printable-keybinding-policy.mjs`, wired into the `ui-web-gates`
> recipe in `governance/consult-register.v1.json` (the authority CLAUDE.md's pre-merge row points at;
> `check-premerge-table` validates the reference). It brace-matches every
> `registerKeybinding(Entry)?({…})` literal under `modules/ui-web/src`, classifies the `key`, and
> fails the build on a modifier-less printable with no `when`. Verified to BITE on a fixture (a bare
> `'j'` registration fails; `'mod+j'` and a `when`-scoped `'?'` pass), not merely to be green.
>
> **`/` gained its `when`** as this policy's own consequence, and the scope is the honest one:
> `activeSurface == 'core.unified-chat-surface' || activeSurface == 'core.search-v3-surface'`. Note a
> correction to §2.6 while scoping it — `/` is **not** globally inert. `UnifiedChatView` still listens
> for `jf-focus-composer` (`views/UnifiedChatView.ts:956`, a live surface reachable by `Ctrl+Shift+A`),
> so `/` works there today and only sv3 is deaf. Scoping it to both surfaces preserves the working
> case and makes the dead one legible instead of silent.
>
> **The exemptions are stated in the gate's own header**, where an author meets them:
> - **raw `window`/`document` listeners — review tier (~70%)**, exactly as this section says. sv3's
>   and `UnifiedChatView`'s `j`/`k` are not registered bindings, so no `when` reaches them and no gate
>   sees them. What governs them is review plus Layer 2's runtime guards (typing target, IME/repeat,
>   modal ownership). (b1) is what closes this exemption.
> - **dynamically-keyed registrations** — `Shell.ts`'s plugin bridge passes a runtime `key`
>   (`chrome/Shell.ts:1317`), which no source scan can classify. Same review tier. This one the
>   design had not named; it is named now rather than left as a silent hole in the gate's coverage.

## 4. Plan

**Sequencing (rev 2 — supersedes the first draft's "everything waits for PR-0").** The original rule
blocked all work behind the live repro. Review corrected it, and the correction is right: **F1 is
established statically and is hypothesis-independent.** Nothing about "the composer is never focused,
has no working keyboard route, and has click-dead zones" depends on which control the owner's
keystroke hit. Blocking that fix on a repro would be caution theatre, not rigour.

- **Proceeds NOW, ungated:** PR A (Layer 1(a)(b)(d)) and Layer 2(a) (`Shell.isInputFocused` →
  `isTypingTarget(deepActiveElement())`).
- **Genuinely gated on PR-0/L13:** PR C (URL projection). It is the one item whose justification
  rests on the F3 reading of the incident.
- **PR-0 runs at the next stack window** with the corrected legs, in parallel with PR A rather than
  ahead of it.

The `audit-without-test` discipline is satisfied by test 2, not by the repro: PR A ships with an
assertion that fails on `main` today.

### 4.1 PR-0 — the deferred live-repro leg (RAN 2026-08-25; gated PR C)

> **RESULTS — run 2026-08-25 against a live stack with PR A merged.** Verdict: the primary
> diagnosis (§2.7a-b) is **CONFIRMED**, all three falsifiers held, and hypothesis 2 (§2.7d) is
> **out** for this incident. PR C's gate is **SATISFIED**; the URL projection shipped against these
> results.
>
> | # | Leg | Verdict | What was observed |
> |---|---|---|---|
> | L1 | Fresh sv3 load; probe | **PASS** | With PR A live the composer `<textarea>` is focused on entry — the predicted `BODY` was the pre-PR-A state, and its absence is PR A working |
> | L2 | Open an existing conversation from the sidebar | **PASS** | Focus lands in the composer, not on a row button (PR A's entry-path focus) |
> | L4 | Composer padding → footer → textarea | **PASS** | The glass-box `@pointerdown` focuses the field from the padding; footer controls keep their own clicks |
> | L5 | `F2` rename, commit with `Enter` | **PASS** | `focusRow()` parks focus on `BUTTON.row` exactly as §2.7b reads it — the a11y placement 831 asked for, on an armed trigger |
> | L6 | **From L5's state, press `Space`** | **CONFIRMED — the decisive leg** | The conversation swapped and `location.hash` was recorded **VERBATIM UNCHANGED**. F3's fingerprint, reproduced on demand |
> | L6-falsifier | Same, with focus deliberately in the textarea | **HELD** | `Space` typed a space. Nothing swapped — so button activation, not a stray global binding, is what fires the chain (§2.7b stands; §2.5 confirmed) |
> | L7 | Discard the last remaining conversation, then `Space` | **NOT RUN** | Destructive (it wipes the rehydration pointer) and not needed once L6 confirmed; the worst variant is an amplification of the same mechanism, not a separate one |
> | L8 | From L6's post-state, press **Back** | **CONFIRMED** | Nothing happened. No history entry existed to return to — §2.7a's "by construction", observed |
> | L9 | Escape with the evidence pane open and a row focused | **REFUTED** | The pane did **not** close on that path; the original report's "Escape also exits" is not this ladder |
> | L10 | Palette open, `Tab` to a popup button, press `j` | **DEFECT REPRODUCED** | The transcript scrolled under an open palette — §2.9(e)'s modal-owns-focus gap, live. Routed to **PR D** |
> | L11 | Hero→docked morph with the field focused | **HELD (falsifier)** | Focus retained across the morph — §2.9/§2.7e's ruling-out stands. **Caveat:** observed docked-not-hero, so the morph exercised was the arm this window could reach, not a cold hero flip |
> | L13 | Stage content at every swap | **CLEAN** | `<jf-sv3-window>` stayed mounted throughout; no `No surface selected.` |
> | L14 | Console across the whole session | **CLEAN** | Zero `[NavigationHandler] BUG: received unresolved surfaceId` strings |
>
> Read L13/L14 as §4.1 asks them to be read: a passive watch that never fired, **not** a refutation
> of hypothesis 2 (a boot-time race is not falsifiable on demand). It remains the standing hazard
> §2.7d describes.

The original plan text follows, unchanged, as the spec the run was executed against.

Not run this round: the dev stack is leased elsewhere and the charter fixes this pass as static.

**Preconditions.** Own the stack lease (`quick_health` → `start`, declare `leaseDurationSec` for the
window). **`AI_OFFLINE` is fine** — no leg needs a model; they need a persisted conversation with a
transcript, which the sessions list already provides. Run in a **visible, foregrounded** window, not
the off-screen MCP tab — tempdoc 860 and `docs/observations.md` (rAF latch) make background-tab
evidence inadmissible for anything measured, and focus/activation is exactly that class.

**Instrument.** One probe, used at every step — the app's own descent, so the probe and the product
agree:

```js
(() => { let a = document.activeElement;
         while (a?.shadowRoot?.activeElement) a = a.shadowRoot.activeElement;
         return { tag: a?.tagName, cls: a?.className, hash: location.hash }; })()
```

**Legs.** Each records the probe *before* and *after*, plus whether the visible view changed.

| # | Leg | Confirms | Predicted |
|---|---|---|---|
| L1 | Fresh sv3 load; probe | §2.9(a) — nothing autofocuses | `BODY` |
| L2 | Open an existing conversation from the sidebar; probe | entry paths leave focus unset | `BODY` or a row button |
| L3 | Press `Ctrl+L`, then `/`; probe both | §2.6 — both are dead here | unchanged; nothing focused |
| L4 | Click composer **padding**, then the **footer** row, then the **textarea**; probe each | §2.9(b) dead-zone map | dead, dead, `TEXTAREA` |
| L5 | Rename a session via `F2`, commit with `Enter`; probe | §2.7b step 1 — `focusRow()` parks on the swap trigger | `BUTTON.row` |
| L6 | **From L5's state, press `Space`.** Record view + hash | **§2.7b — THE decisive leg** | conversation swaps; `location.hash` **unchanged** |
| L7 | Discard the **last remaining** conversation (leaves no survivor), then press `Space` | §2.7b's worst variant — `Sv3Sidebar.ts:352-356` parks focus on the new-session button | hero; `lastViewedConversation` **cleared**, so even reload does not recover |
| L8 | From L6's post-state, press browser **Back** | §2.7a — no history entry | nothing happens |
| L9 | With the evidence pane open and a row button focused, press `Escape` | "Escape also exits" (§2.7b) | pane closes |
| L10 | Open the palette, press **`Tab`** until focus is on a popup **button** (arrows will NOT work — §2.9(e)), then press `j` | §2.9(e) modal-owns-focus | transcript scrolls under an open palette |
| L11 | Hero→docked morph with the field focused; probe after | §2.9/§2.7e rule the morph out — **falsifier** | focus **retained** |
| L13 | Read the stage's rendered content at any swap | §2.7d — **the workable discriminator** | `<jf-sv3-window>` still mounted, **not** `No surface selected.` |
| L14 | Watch the console across the whole session | §2.7d | **absence** of `[NavigationHandler] BUG: received unresolved surfaceId` (`navigationHandler.ts:119`) |

**Falsification discipline** (`interrogate-results`). L6 producing the predicted swap is the
*expected* result, which is the dangerous kind. It is not sufficient on its own. Additionally
require: (i) L11 shows focus retained — if the morph *does* drop focus, §2.9's ruling-out is wrong
and the diagnosis needs revision; (ii) L6 with focus deliberately placed **in the textarea** must
**not** swap — if it swaps anyway, something other than button activation is responsible and §2.7b
is refuted; (iii) record `location.hash` verbatim at L6, because the whole F3 argument rests on it.

**Also capture while the stack is up** (cheap, and each settles an open variable):

- `location.hash` verbatim at every step — the whole F3 argument rests on it.
- Full console for the session, retained. `[NavigationHandler] BUG: received unresolved surfaceId`
  (`navigationHandler.ts:119`) is §2.7d's signature.

> **A discriminator the first draft proposed and that does not work.** It called for evaluating
> `listSurfaces().some(s => s.id === 'core.search-v3-surface')` at the moment of failure. That is
> **not evaluable** — `listSurfaces` has no global exposure (the only `window.*` surface in the app
> is `__justsearchDevFixtures`, dev-only, `plugin-api/dev-fixtures.ts:116`), and it would **not
> discriminate** anyway: at a *deliberate* swap sv3 is trivially still in the catalog, and §2.7d's
> race cannot be provoked on demand. L13/L14 replace it — they read what actually rendered and what
> the router actually complained about, both observable without new instrumentation.

**Exit condition — stated honestly.** L6 plus the three falsifiers confirm or refute the **primary**
diagnosis; that is what gates PR C. Hypothesis 2 (§2.7d) is a boot-time race and is **not falsifiable
on demand** — it can only be caught on live recurrence, which is why L13/L14 are passive watches
across the whole session rather than legs with expected values. Do not treat "L13 looked fine" as
having ruled it out; treat a `No surface selected.` stage or that console string, if either ever
appears, as the signal that re-points the work.

Note `listKeybindings()` (`KeybindingRegistry.ts:133`) is likewise module-scoped, so §2.8's
user-override question needs a devtools source-mapped breakpoint or a temporary dev-fixture export
rather than a console one-liner. Low value — the design does not depend on the answer.

### 4.2 PR sequencing

Each PR is independently mergeable and independently verifiable. **Layer 1 ships first**: it is the
fix, and every later layer's safety argument depends on it.

Explicit constraint for PR A's implementer: `focusField()` **must** reach the textarea via
`this.shadowRoot?.querySelector('textarea')`. A host-level `.focus()` on `<jf-sv3-composer>` or a
`jf-control` will **silently no-op** — `delegatesFocus` is used in **zero** places app-wide
(§2.9(d)), and adopting it is explicitly out of scope for this PR (§3.1's non-choice).

| PR | Content | Gates / checks |
|---|---|---|
| **A** | Layer 1(a)(b) + Layer 2(a) — `focusField()`, entry-path focus, glass-box `@pointerdown`, `Shell.isInputFocused` unification. **Ungated; proceeds now.** | ui-web gate set; tests 2-3, 6 below |
| **B** | Layer 1(c2) — real target for `shell.focus-composer`. ~~Layer 4~~ moved to D (A8), and `/` already carries its `when` | ui-web gates; `check-premerge-table` n/a |
| **C** | Layer 3(a) — project sv3 conversation identity to the URL. **SHIPPED 2026-08-25** (gate satisfied by §4.1's L6/L8) — see §4.6 | router tests; back/forward live leg |
| **D** | Layer 2 — ~~predicate unification~~ (#546), `isComposing`/`repeat`, `focusKind`/`paletteOpen` resolution, modal guard, `trapTab` containment, **+ Layer 4's gate half** | ui-web gates |
| **E** | Layer 1(d) + Layer 3(c)(i) — resting-state affordance, row focus indicator, + the split-view residual below. **SHIPPED-PENDING-AUDIT 2026-08-25 (#561)** — see §4.7 | ui-web gate set (green); **§4.4's measured UX audit still owed** — procedure in #561's body |

> **Amended when PR A shipped (2026-08-25).** Row A originally also listed Layer 1(d)'s
> resting-state affordance, which row E owns — it is a visible-affordance change and therefore
> carries §4.4's measured UX audit, which is E's gate and not A's. A shipped Layer 1(a)(b) with no
> resting-state treatment is the correct intermediate state: (a) makes the unfocused composer rare,
> which is exactly the condition under which (d) is the smallest of the four.
>
> **Residual for PR E, found in PR A's review and deliberately not fixed there.** The split-view
> toggle re-templates the stage, which re-runs `connectedCallback` and therefore re-fires
> `focusComposer()` — a **non-entry** path taking the caret to the composer on a layout change the
> reader made for some other reason. Narrow (it needs focus to be outside a typing target at that
> moment) and benign compared with the vacuum it comes from, but it is a focus move nobody asked for
> and belongs with E's other focus-presentation work. **Fixed in PR E** — §4.7 (c).

`ConfirmDialog.ts:206-207` (§3.2(a)) is **not** in this sequence — it is a live destructive-action
bug on an unrelated component. Ship it on its own, ahead of everything. **It did: #541 (A6).**

Per the ui-web gate row in `CLAUDE.md`, every PR touching `modules/ui-web/src/**` runs the
`ui-web-gates` recipe from `governance/consult-register.v1.json` — that is the authority, not a
hand-copied list.

### 4.3 Regression tests (the fix is not done until these are green)

`audit-without-test` applies with full force here: this whole tempdoc is a static audit, and every
claim it makes is a hypothesis until a test exercises it.

1. ~~**The chain test**: focus `button.row`, dispatch `Space`, assert the conversation is
   unchanged.~~ **Withdrawn — this test can never go green, and proposing it contradicted §2.7c and
   the design's own chosen remedy.** §2.7c says `Space`-activates-a-focused-button is correct
   platform behaviour that must not be "fixed", and §3.3(c) chooses option (i) — *keep* row focus
   and make the indicator unmissable. After the entire four-PR sequence ships, `Space` on a focused
   `button.row` **still swaps the conversation, by design**. A test asserting otherwise would only
   pass if someone broke the platform contract to satisfy it, which is precisely the
   `fix-root-causes-not-symptoms` inversion. The chain is broken at **F1** (focus never sits there
   while the reader believes they are typing) and at **F3** (the swap becomes undoable) — never at
   the keypress. Replacement below.
2. **Entry-path focus** (PR A) — **this is PR A's real regression test.** After fresh mount /
   session select / new session / record load, `deepActiveElement()` is the composer `<textarea>`.
   This is the assertion that breaks the chain at F1, and it fails on `main` today.

   **The incident's own regression test is test 9** (PR C: Back undoes a conversation swap) — that
   is the one that converts "recoverable only by reload" into a caught condition.
3. **Dead-zone focus** (PR A) — a `pointerdown` on `.field` padding and on `.glass` focuses the
   textarea; a `pointerdown` on a footer control does **not**.
4. **`/` and `Ctrl+L` reach sv3's composer** (PR B) — and a surface with no composer does not throw.
5. **Policy test** (~~PR B~~ → **PR D, done as a gate** — see 8c) — a modifier-less printable binding
   registered without a `when` clause fails a check. Prefer a gate over a unit test if the
   registration site is enumerable. It was, so it is a gate.
6. ~~**Predicate unification** (**PR A**, with Layer 2(a)) — a focused `<select>` must not trigger
   `Shell.handleGlobalKey`'s chords (the `SELECT` omission).~~ **GREEN — #546**
   (`chrome/Shell.globalKeys.test.ts`, "leaves a chord alone while a `<select>` has focus"). The
   `ConfirmDialog` exclusion is moot: #541 shipped it with its own coverage.
7. **Guard-helper adoption** (PR D) — `isComposing` and `repeat` are honoured at **all three**
   global sites, not just the registry (§3.2(b)); one case per site, or the fifth fork is reborn.
   **GREEN, at four sites:** `KeybindingRegistry.editableGuard.test.ts` ("864: an IME-composing press
   and an auto-repeat press dispatch nothing"), `Shell.globalKeys.test.ts` ("864: ignores an
   IME-composing chord and an auto-repeat chord"), `Sv3Main.navigation.test.ts` ("864: ignores an
   IME-composing press and an auto-repeat press"), `UnifiedChatView.test.ts` ("864: stands down for
   IME composition, auto-repeat, and an open modal"). The helper itself is pinned in
   `utils/keyboardHandler.test.ts`. Each case asserts a press that STILL fires beside the ignored
   ones, so a guard that swallowed everything would fail.
8. ~~**Slot writers** (PR D) — if `focusKind`/`paletteOpen` are kept, assert they actually change.~~
   **Moot by resolution (PR D): both slots were deleted**, so there is nothing to assert a movement
   of. What replaces it is the modal guard's own coverage — `modality.test.ts`
   ("modalOwnsFocus (864 Layer 2(d))", including the stacked-modal case where an inner modal closing
   must not report the keyboard free) plus the L10 case below.
8a. **The L10 modal guard** (PR D) — **RED before, GREEN after**, against the REAL palette: with
   `jf-sv3-palette` open and focus parked on a `role="option"` row, a window `j` must not call
   `nav.jumpTo` and must not be `preventDefault`ed; the same press after `hide()` must navigate.
   `Sv3Main.navigation.test.ts` "864 L10: stands down while the command palette is open, wherever
   focus sits". Verified red on the pre-fix behaviour (`jumpTo` called once with `g1:q`).
8b. **`trapTab` containment** (PR D) — a `Tab` pressed with focus on a palette ROW is consumed and
   lands focus back on a stop (`Sv3Palette.test.ts` "864: contains Tab even when focus is on a row
   rather than on a stop"); also verified red against the old early-return.
8c. **Layer 4 policy** (PR D, was test 5 for PR B) — the gate, not a unit test, per §4.3's own
   preference. `check-printable-keybinding-policy` passes on the tree and was verified to fail on a
   fixture carrying an unscoped bare `'j'`.
9. **Back undoes a conversation swap** (PR C) — **the incident's regression test.** **GREEN
   2026-08-25**: `router/sv3ConversationUrl.test.ts` (`it('Back undoes a conversation swap')`), with
   the window half in `views/search-v3/SearchV3View.urlConversation.test.ts`. Both verified red
   without the fix's mechanism — see §4.6.

Test-infra caveat, already recorded: happy-dom's `ShadowRoot.activeElement` throws inside
`deepActiveElement` for shadow-host landmarks (`docs/observations.d/…:44`), which is why the
existing `Sv3Main` navigation cases fall back to a mocked `jumpTo`. Tests 2-3 are focus tests and
will meet this — budget for it rather than discovering it mid-PR.

### 4.4 Closure requirements

- **Independent review** (`independent-reviewer-required`) — implementer ≠ reviewer.
- **Measured UX audit** for PR E and for any of A/B that changes visible affordance
  (`ux-audit-closure`): axe + a contrast oracle, live, auditor ≠ committer. Eyeballing does not
  discharge it. Both are honor-system since tempdoc 563 retired the gates — they are still expected.
- **Live re-verification after the fix**, re-running §4.1's L1-L11 against the fixed build. A static
  green plus unit tests is `static-green ≠ live-working`; this defect was found live and must be
  closed live.
- **Registers**: `/search-quality` and `/inference-runtime` are not implicated (no retrieval or
  runtime surface changes) — recorded here so the closure check is answered rather than skipped.

### 4.5 Out of scope, logged not fixed

`HelpSurface.ts:67`'s stale keybinding list, the duplicate `Ctrl+Z` authority
(`AdvisoryToastHost.ts:245`), and `AdvisoryInboxDrawer.ts:379`'s missing `stopPropagation` are all
in the inbox. The drawer one is deliberate: `Sv3Main.ts:1483-1494` documents the downstream
`defaultPrevented` guard it forced, so changing it now would edit a load-bearing comment's premise
for no gain in this tempdoc. **Re-checked at PR D and left logged**, on the same reasoning: the
`defaultPrevented` guard is still the first thing `onWindowKeydown` reads, and PR D adds guards
after it rather than replacing it.

Two findings from the F3 hunt are logged and deliberately **not** in the PR sequence — both are real,
both are bigger than this tempdoc, and folding either in would blur what PR-0 is testing:

- **FE-only surfaces are evictable by a boot-time race** (§2.7d) — a registry-lifecycle fix in
  `src/api/registry/SurfaceCatalogClient.ts:284`, different subsystem and blast radius. Not a
  candidate explanation for this incident (its trigger is boot-scoped), but a real hazard for every
  FE-only surface including sv3 itself.
- **The `navigate` Effect corrupts the hash** — `substrates/actions/index.ts:459-462` writes a bare
  surface id (`#core.unified-chat-surface`) that `URLSource.parseHash` rejects
  (`sources/URLSource.ts:100-101`), *after* the router already wrote the correct canonical URL. It
  is a live defect on the palette/action navigation path and worth its own PR.

## 4.6 PR C as implemented (2026-08-25) — mechanism, format, and where it exceeded the design

The design named the route and not the mechanism: *"registering the conversation as a URL-projected
store"*, with §2.11 identifying the blocker (an FE-only surface has no wire `stateSchema`, so
`URLProjector.activateProjection` returns early for all of it) and the fix route as "an FE-registered
state schema for the surface, or the projector accepting FE-declared schemas". **The first was taken.**

**The hash format.** One argument, and the format is the contract:

```
#justsearch://surface/core.search-v3-surface?conversationId=<id>
#justsearch://surface/core.search-v3-surface            ← the hero: the argument is ABSENT
```

Absent rather than empty or a sentinel, because `URLProjector.collectState` drops null/undefined and
because an empty value would restore a conversation named `""`. Asserted literally in
`router/sv3ConversationUrl.test.ts` so a change to it is a test change.

**The five moves.**

1. `router/bootstrap.ts` — `registerFrontendSurfaceSchemas()` registers sv3's schema locally, before
   the wire fetch (so a future backend entry still wins). This is §2.11's blocker, removed.
2. `router/bootstrap.ts` — a `sv3.conversation` `StoreAdapter` over `conversationListStore.activeId`.
   It dedups on `activeId`, because the store emits on every list refresh and a completed ask
   re-lists.
3. `router/URLProjector.ts` — `projectAsNavigation(emit)`. Slice 489 split history writes as
   "surface change pushes, in-surface edit replaces", and **that split is the reason registering a
   schema alone would not have fixed the incident**: the projector would have written the swap with
   `replaceState` and Back would still have done nothing. A surface can replace everything the reader
   is looking at without changing surface; the store that knows says so per-emission. Nothing that
   does not call it changes behaviour.
4. `state/conversationListStore.ts` — `claim` / `restore` / `list` change reasons, and
   `restoreActiveConversation`. A claim is a navigation; a restore is the URL, a popstate or the
   reload pointer replaying a position the reader already has.
5. `views/search-v3/SearchV3View.ts` — `followStoreConversation`, and `restoreLastViewed` now yields
   to a non-null store claim (the address outranks this tab's pointer).

**Where the implementation went beyond the design, and why.**

- **`StoreAdapter.clearsOnTraversal`** (`router/storeRegistry.ts`, honoured in
  `navigationHandler.applyState`). Absent-valued bindings are skipped for every adapter — right for
  a refinement (a search address without `?query=` must not empty the box), wrong for a store whose
  whole content is the address. Without it, going **forward** onto a hero entry left the window
  showing the conversation the reader had left, with a URL saying otherwise. Gated on traversal so a
  programmatic `activateSurface(id, {})` is never read as "close the open conversation".
- **The view follows `restore`, not every store change.** The first cut had it follow `activeId`
  outright, which broke `SearchV3View.record.test.ts`'s companion-load case — that test asserts the
  app-wide pointer being claimed by ANOTHER window must not move sv3, and it is right. Following
  only rehydrations keeps that line exactly where it was. HONEST LIMIT, recorded at the adapter: the
  projection still mirrors whoever writes `activeId` while sv3 is the active surface. Only the
  surface on screen can claim in practice; a second simultaneous claimer would move the URL without
  moving the window, and the fix then is a window-scoped slice, not a special case.

**Verification.** `typecheck` clean; the whole ui-web unit suite green; the full `ui-web-gates` set +
the six kernel gates green. Twenty-two cases across `router/sv3ConversationUrl.test.ts` (15) and
`views/search-v3/SearchV3View.urlConversation.test.ts` (7), covering §4.3 test 9 (Back undoes a
conversation swap), forward/back across several switches, the deep link, the reload, the literal hash
format, the review's F1-F4 (see below), and a regression guard that other surfaces still project with
`replaceState` only.

**Red on main, verified rather than asserted** (`interrogate-results`): with
`registerFrontendSurfaceSchemas()` removed — main's behaviour — **9 of the 11** router cases fail,
including `Back undoes a conversation swap`. The two that survive are the "re-claiming adds no
entry" case (vacuously true when nothing projects) and the other-surfaces regression guard, which is
supposed to be insensitive to this change. With `followStoreConversation` unwired, **4 of the 5**
window cases fail. Neither test passes for a reason other than the fix.

### Two user-visible consequences, accepted and stated

Putting the conversation in the address makes Back reach places it could not reach before. Both of
these are consequences of the fix working, not defects in it, and both are accepted:

- **Back during a streaming answer, landing on the hero, cancels the stream.** The hero teardown
  aborts the in-flight ask (it is the same teardown New session runs). The CONVERSATION survives —
  it is server-side, and Forward returns to it — but the partial answer on screen does not, and the
  turn resumes from the record rather than from where the stream was. Back to a *different*
  conversation does not abort: that matches what a row click has always done, so it is not a new
  behaviour.
- **Back onto a deleted conversation's entry shows a phantom row and a record notice.** The address
  still names it, so the window merges a placeholder row for it and then fails the record fetch —
  the reader gets the honest 404 notice rather than a silent empty transcript. Deleting the OPEN
  conversation does not create such an entry (the drop is projected as a correction, not a
  navigation — §"the `list` reason" in `conversationListStore`), so this is only reachable by
  deleting a conversation whose entry is already on the stack.

### Independent review (PR #556) — five findings, all applied

Reviewer verdict was APPROVE-WITH-FIXES. F1 was a real defect the suite did not catch:

- **F1 (MEDIUM) — the hero branch claimed during popstate handling.** `followStoreConversation`'s
  null branch called `startFreshSession`, which writes the store; the projector cannot tell that
  write from a fresh claim, so it pushed the bare address onto a stack the browser had just walked
  back through — **truncating the forward tail**. Reproduced by the reviewer by reordering the
  store's listener Set. Fixed by splitting `startFreshSession` into `resetToHero()` (state only) +
  the two pointer writes; the follow path takes the claim-free half. Two pinning cases run in the
  ADVERSE listener order (the window mounts before the projector's adapter subscribes): no push, and
  no `claim` emitted at all — the second is order-independent, which is what actually closes the
  class. Verified red on the pre-fix body: exactly one spurious `pushState`.
- **F2 (LOW) — `isCurrentUrl` had zero coverage.** Both suites mock `history`, so `location.hash`
  never moved and the guard was constant-false; deleting it left everything green. Added two cases
  against a REAL `window.location.hash` — push when the browser is elsewhere, replace when it is
  already showing that address. Verified red with the guard removed.
- **F3 (LOW) — the `list` reason's doc was untrue.** Both delete paths null `activeId` under it. The
  BEHAVIOUR is right (a deleted conversation is not somewhere Back can lead, so the URL is corrected
  in place), so the doc was widened to say so rather than the reason changed — plus a case pinning
  it, since a doc-only fix cannot fail.
- **F4 (LOW) — a claim inside the 75ms debounce was dropped by a surface change.** Post-PR that is a
  missing history entry, not a stale URL. **The reviewer's suggested site does not work**:
  `handle()` calls `pushAddress` BEFORE `activateProjection` (the slice 489 T1/N2 ordering
  invariant), so flushing inside `deactivateProjection` would write the OUTGOING surface's URL on
  top of the entry the incoming navigation had just pushed. Flushed at the top of `handle()` instead
  — where the browser is still on the outgoing address — via `flushPendingNavigationWrite()`, which
  is a no-op unless a navigational write is pending and is skipped on a traversal (a push there
  would truncate the forward tail, F1's hazard again).
- **F5** — the two consequences above.

**Still open from this tempdoc:** PR B (Layer 1(c2) + Layer 4), PR D (Layer 2 + §4.1's L10 defect),
PR E (Layer 1(d) + Layer 3(c)(i), with §4.4's measured UX audit). §4.4's live re-verification of
L1-L11 against the fixed build is a stack-window item and is **not** discharged by this PR's green
suite (`static-green ≠ live-working`).

> **Superseded for E (2026-08-25):** PR E is implemented and green — §4.7. What is still open for it
> is §4.4's measured UX audit alone, which is pooled and owed by an auditor ≠ committer.

## 4.7 PR E as implemented (#561, 2026-08-25) — the resting affordance, the row ring, the residual

Three items, each with its mechanism named, because "de-emphasise the composer" and "make the ring
louder" are the kind of instructions that get satisfied by something that looks right and is
unreachable — which is exactly what this PR found already shipped (item (d) below).

**(a) Layer 1(d) — the resting-state affordance** (`Sv3Composer.ts`). ONE knob,
`--composer-rest`, declared `1` on `.glass` and spent to `0` by the single rule
`.glass:has(textarea:focus)`. Two derived declarations, both `color-mix` in the 859 §B idiom:

- the **material** — `--composer-rest-surface` mixes `--composer-glass-surface` toward
  `--background` by `calc(100% - 65% * var(--composer-rest))`, i.e. the resting box spends the 4%
  lift that is its whole "raised surface" signal and sits back into the page; the shipped fill
  recipe then reads the resting surface through the same one blur multiplier, unchanged, and the
  `@supports` no-blur companion reads it too (or the composer would re-emphasise itself wherever
  `backdrop-filter` is missing);
- the **frame** — `.glass::after`'s `border-color` fades to 45% of `--composer-outline` at rest,
  the element §2.9(b) named as the thing that "frames the entire box as the input".

**It deliberately spends no text contrast.** No rule that reads the knob sets `color` or `opacity`,
and there is a test that says so. That was a choice, not an omission: `--placeholder` is
`--muted-foreground` (zinc-500 on the light page), already near the AA floor, so a resting treatment
that dimmed the placeholder would have bought the affordance with the one thing the audit measures.
Motion: both transitions are `--duration-sv3-micro`, and the existing
`prefers-reduced-motion: reduce` block stills them — the STATE survives a reduced-motion reader, its
animation does not.

**(b) Layer 3(c)(i) — the row focus indicator** (`Sv3SessionRow.ts`). The ring keeps the repo's
idiom (`outline: 2px solid var(--ring)`, inset because the row clips its own overflow) and gains a
second mark in the composer's halo idiom: `box-shadow: inset 0 0 0 var(--space-2)
color-mix(in srgb, var(--ring) 22%, transparent)`. One hue, two marks, no literal. The 8px band sits
above and below the label's line box, so it costs no label contrast either.

**(c) The split-view residual** — fixed, `SearchV3View.ts`. `connectedCallback` is the entry signal
and three things route through it: a first mount, a re-entry to the retained instance, and a
**re-parent**. The discriminator is the task, not a timer: Lit removes and re-inserts the node inside
one synchronous update, so a re-parent's connect runs before a microtask queued by the disconnect
that preceded it, while a real re-entry needs a user event and is a later task by construction. Only
the focus move is gated — every subscription and observer is still re-taken on every connect.
Verified red both ways: without the guard the composer steals focus from the split toggle; with the
flag never cleared, the genuine re-entry stops landing in the composer.

**(d) A defect this PR found and fixed on the way in: the composer's focus ring was unreachable.**
The three rules that draw it were `:host(:has(textarea:focus-visible)) .glass::after` and siblings —
the shape 822 F3 measured as a Chrome syntax error that invalidates its whole selector list, and the
shape whose argument `:host()` matches against the host in the OUTER tree, where a shadow
`<textarea>` does not live. Either reading makes the ring dead in Chrome. WPT agrees: Chrome fails
the `:host(:has())` tests. §2.9's "the focus ring is honest" was therefore true about the *keying*
and wrong about the *painting* — the composer had no focus mark at all, which sharpens rather than
softens this tempdoc's diagnosis. All three are re-keyed onto the wrapper
(`.glass:has(textarea:focus-visible)::after`), same pseudo-classes, same order, same declarations,
and the banned shape is now pinned out of the sheet.

**Tests** — `sv3-focus.test.ts` (the 864 home), plus the two moved pins in `sv3-tokens.test.ts`.
Style-text, because happy-dom runs no cascade and resolves no `color-mix`; each was verified red
against a plausible wrong implementation rather than against a deletion:

| Pin | Verified red by |
|---|---|
| the knob is keyed on the field's focus, not a state flag | re-keying the lift to `:host([state='hero']) .glass` — 2 red |
| the row's ring carries the halo | dropping the `box-shadow` line — 1 red |
| `all:` cannot eat the row ring (#539) | adding `all: unset` to `button.row` — 1 red (and it caught a hole in the first regex: `(^|;)` never matched a first declaration, so the assertion was passing vacuously) |
| the resting transition is motion-safe | removing `.glass::after` from the reduce block — 1 red |
| a re-parent is not an entry | removing the guard — focus lands in the composer instead of on the toggle |
| ...and the entry it must NOT catch still fires | leaving the flag set — the genuine re-entry stops focusing the field |

Full ui-web gate set green (23 scripts + 6 kernel gates); full FE suite 6032/6032.

**Owed:** §4.4's measured UX audit (axe + a contrast oracle, live, auditor ≠ committer) — the
procedure is written into #561's body for the pooled window, with the verdict to be recorded here. Nothing in this PR discharges it: every
assertion above is style TEXT, which is exactly the tier the audit exists to outrank.

Two asks the first draft of that procedure missed, added after #561's independent review:

- **Confirm the re-keyed ring PAINTS.** In Chrome, with the caret in the field, verify
  `.glass:has(textarea:focus-visible)::after` draws a visible ring. Item (d) replaced rules that were
  silently dead for this surface's whole life, and the replacement is pinned only at the tier that
  failed to notice the original — a style-text pin cannot tell a painted rule from a parsed one.
- **Point the contrast oracle at the resting composer's BOUNDARY, not only its text.** WCAG 1.4.11
  asks 3:1 of a component's boundary against its surround. The measured **pre-existing** baseline on
  `main` is already below that (dark, composer edge over the page: **1.21:1**), and the resting state
  aggravates it theme-asymmetrically — the reviewer measured **1.06:1** in dark, where the fill and
  the frame lose their separation together. Record a NUMBER for the resting boundary in dark over an
  empty region, and judge it as a **delta against the 1.21:1 baseline**, not as a floor this PR
  introduced. Logged as a standing pre-existing condition in the observations inbox.
