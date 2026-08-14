---
title: "830 — Search v3 degradation form (E1 readiness banner, E2 code-dedup, E3 mode-gated disclosure)"
type: tempdocs
status: "IMPLEMENTED (2026-08-14) — inventory rows E1/E2/E3 landed on branch sv3-degradation-form; unit suite + ui-web gate set green; live-verified backendless."
created: 2026-08-14
updated: 2026-08-14
author: agent session bccfc163-7b8f-4b1a-b9e4-0c011632d8a1
category: frontend / search-v3 window (tempdoc 822 inventory)
related:
  - 822 (the Search v3 window — the surface this extends)
  - 738 (Simple/Detailed disclosure in the shipped window — the pattern E3 follows)
  - 804 / 805 (the readiness authority's cause scoping + consequence classification)
  - modules/ui-web/src/shell-v0/state/readinessNotice.ts (the ONE authority consumed here)
---

# 830 — The Search v3 window's degradation form

## 1. What was missing

The Search v3 window (`modules/ui-web/src/shell-v0/views/search-v3/`, dev-gated
DEVELOPER/DEEPLINK) had **no degradation surface at all**. A reader whose semantic leg had
fallen back, or whose local model was not running, saw a composer that quietly refused a
send and — at best — a one-line availability reason with no fix attached. The window read
`reasonFor` for single control-scoped reasons but never consumed the system-scoped
`readinessNotice` projection that the shipped window's banner is built on.

The obvious repair — port the shipped banner — is the wrong repair. The shipped banner is a
block (headline, body, bulleted cause list, remedy) permanently in flow, measured at ~100px
of a 790px window. Chrome volume is the founding complaint this window exists to answer.

## 2. Design decisions

### 2.1 The banner is summary height; the detail is disclosed

`Sv3Composer` already owns the window's ONE banner slot (the `.notice` box above the
composer glass). The degradation banner joins that slot in the same box idiom — same
radius, same fill, same type size — and adds a **row**: severity mark, headline, remedy,
disclosure chevron. Measured live: **36px resting** (18px line) in a 722px window, versus
the shipped banner's ~100px/790px. Disclosed it grows to 76px, and only while open.

What rests is the honesty fact (*capability is reduced, and here is the fix*). What
discloses is the elaboration (the consequence sentence and the worded causes) — the same
L14 boundary the answer frame already draws in this window. Nothing is behind hover: the
disclosure is a real `<button>` with a real `aria-expanded`.

**Deliberate departure from the shipped window:** an `error`-severity verdict does *not*
force expansion here. The shipped window opens a severe notice outright; this window keeps
one resting line because the headline for a severe verdict ("Backend disconnected.") is
itself the complete honesty fact, and forcing a block would reintroduce the volume the
window is built against. The remedy is on the resting line either way.

### 2.2 One slot, and no fact in it twice

`sv3ComposerReason(degradation, unavailableReason)` decides what the slot holds:

- The affordance-scoped availability reason **yields** when the banner already words its
  code. Both strings come from `reasonFor`, so "same code" and "same sentence" coincide by
  construction — this is identity on the one vocabulary's output, not a string heuristic.
- A reason the banner does **not** word (no indexed documents; indexing in flight; the
  model-load estimate suffix) keeps its own line. The failure this window can afford is
  more text, never a refusal with no reason on screen.

The send's `aria-describedby` follows: it names the notice when the notice renders, the
banner's headline when the notice yielded, and nothing when the composer is not refusing.
A dangling reference reads as silence, which the reachable-reason contract rules out.

### 2.3 The remedy is a navigation, not a fired operation

`sv3RemedyReference` reuses the convention `Control.dispatchRemedy` and
`CapabilityMap.dispatchRemedy` already share: a `navigate` remedy passes through; an
`operation` remedy points at Health, where the operation's label, risk and consent ceremony
live. The Health reference is **derived from the authority's own `OPEN_HEALTH`**, not typed
out, so no surface id or label is hard-coded in this window. The click leaves through the
window's single remedy exit (`SV3_REMEDY` → `requestSurfaceNavigation`), the same one the
corpus remedy uses.

### 2.4 Causes carry their codes (E2)

`Sv3DegradationCause` is `{ code, wording }`. This is the structural half of "dedup by
code, not by wording": a wording-keyed implementation could not produce the type at all, so
the tests cannot pass for the wrong reason.

Two mechanics, both code-keyed:

- **Dedup.** A verdict's `reasons` are the concatenation of the `retrieval` and
  `aiFeatures` composites' `reasonCodes` (`aiStateStore.ts:771-774`), so one condition
  reported on both axes arrives twice. One code, one entry.
- **Scoping, consumed not re-derived.** The authority already decided which of the
  verdict's codes its headline speaks for (a rebuild headline lists only the causes a
  rebuild clears). A code survives only if the authority worded it into `notice.causes`.
- **The rebuild restatement.** A lone cause that only restates the "Reindex required."
  headline is dropped — ported from the shipped window's round-2 ruling and keyed the same
  way, on `isReindexCause`. Two rebuild causes are kept: then the list is informative.

`isReindexCause` is **exported** from `readinessNotice.ts`, so it is reused rather than
re-implemented. `dedupDegradationCauses` is private to `UnifiedChatView`; per the brief,
that shipped surface was not refactored to export it (no named defect), so this window
carries its own small code-keyed projection instead.

### 2.5 Whether a banner is warranted at all

`warrantsSearchDegradationBanner` is consumed rather than re-derived. An `info`-severity
verdict still *has* a notice (Health renders it calmly), so a local severity test could not
distinguish "nothing to say" from "this does not warrant warning-tier chrome".

### 2.6 Simple / Detailed (E3)

`SearchV3View` subscribes to `subscribeUiMode` and hands `?detailed` to both regions; every
render site reads `isAdvancedMode()` live, so nothing is copied into element state.

- **Banner.** Simple: resting line + remedy + disclosure. Detailed: causes open outright
  and the disclosure is *absent* — a toggle whose only state is "already open" is a dead
  control.
- **Frame line.** `sv3TailModelLabel` gained a required `detailed` parameter: a model id is
  a technical fact, so Simple never names one. The shipped window reached the same
  conclusion (`UnifiedChatView.ts:5040`). The **duration** and the **grounding verdict** are
  not gated — "how long it took" is plain, and the grounding verdict is an honesty fact.
  The parameter has no default: a disclosure decision made by omission is the one that
  silently stops being made.

## 3. Files

| File | What |
|---|---|
| `sv3-degradation.ts` (new) | The projection: `projectSv3Degradation`, `sv3DegradationCauses`, `sv3RemedyReference`, `sv3ComposerReason`. Pure data → data. |
| `Sv3Composer.ts` | The banner in the one slot: `degradationBanner`, `degradationDisclosure`, `degradationDetail`, `takeDegradationRemedy`, `refusalDescribedBy` + styles. |
| `Sv3Main.ts` | `detailed` property; threads it into the frame projection. |
| `sv3-honesty.ts` | `sv3TailModelLabel` / `projectSv3AnswerFrame` gained the required `detailed` parameter. |
| `SearchV3View.ts` | `subscribeUiMode`; the `degradation` getter; both props handed down. |
| `fixtures.ts` | The disclosure's two labels + the banner's ids/glyph size. No state wording — that is all the authority's. |
| `SearchV3View.degradation.test.ts` (new) | 29 cases. |

## 4. Verification

**Unit suite.** `npm run typecheck` clean; `npm run test:unit:run` — **5072 tests / 420
files, all passing**, of which **29 are new** (`SearchV3View.degradation.test.ts`). The
search-v3 directory alone: 519 passing across 21 files.

Mutation-probed pairs (each fails if the condition inverts):

- degraded verdict → banner projected; healthy verdict → `null`
  (`SearchV3View.degradation.test.ts` "projects a banner for a degraded verdict and NONE
  for a healthy one").
- In the DOM: one `sv3-degradation` node for a degraded state, zero for a healthy one.
- Through the real store, in the mounted window: a `DEGRADED` `retrieval` composite raises
  the banner and a `READY` one removes it, with `aiSnapshot.verdict.kind` asserted in both
  directions.
- Dedup by code: `['inference.model_not_found', 'inference.model_not_found']` → one entry,
  and `causes[0].code` is asserted (unsatisfiable by a wording-keyed implementation).
- E3 both directions, both surfaces: banner causes closed in Simple / open in Detailed;
  frame line without the model in Simple / with it in Detailed; and the app-wide mode
  followed without a re-mount.

**Wording is compared against the authority's own output**, never a literal — a wording
change in `readinessNotice.ts` moves the test and the code together.

**Gates.** The `ui-web-gates` recipe from `governance/consult-register.v1.json`, all run:

- Green: `check-presentation-purity`, `check-observed-state-collapse`, `check-color-tokens`,
  `check-a11y-closure`, `check-adaptive-closure`, `check-layout-purity`,
  `check-surface-composition`, `check-message-single-model`, `check-run-renderers`,
  `check-inflight-liveness`, `check-composition-surfaces`, `check-declared-surfaces`,
  `check-live-channels`, `check-contrast-matrix`, `check-offline-single-sense`,
  `gen-token-names --check`, `gen-component-vocabulary --check`,
  `check-steering-arbitration`, `check-search-issuance`, `check-verdict-derivation`,
  `check-ai-verdict-derivation`, `check-message-classes`, `check-capability-availability`,
  `check-realized-capability`, `check-consequence-classification`,
  `check-folder-status-derivation`, `check-surface-task-state-retention`,
  `check-thread-event-kinds`, `check-readiness-reason-codes`,
  `check-search-degradation-reason-codes`, `check-ui-step-coverage`,
  `check-intent-tier-coverage`; kernel gates `ambient-purity`, `style-literal-ratchet`,
  `atom-fork-ratchet`, `modality-contract`, `transient-arbitration`, `modal-arbitration`.
- Pre-existing red, untouched by this branch (all cite files this branch does not modify):
  `check-theme-token-closure` (RecentsMenu.ts — in `expected-state.v1.json`),
  `check-accent-as-text` (ActionLedgerView.ts — in `expected-state.v1.json`),
  `strip-token-fallbacks --check` (ActionLedgerView.ts, RecentsMenu.ts),
  `check-controls-a11y` (UnifiedChatView.ts:2096 title-on-disabled).

`check-offline-single-sense` matters twice here: the window's own suite additionally
forbids the token anywhere in the search-v3 directory, so the new module and its tests use
reason codes that do not carry it.

**Live (backendless vite, port 5175, Chrome 1280×790).** Route
`#justsearch://surface/core.search-v3-surface`, window found by shadow-piercing walk:

| State | Banners | Resting height | Headline | Remedy |
|---|---|---|---|---|
| Resting (`verdict: connecting`) | 0 | — | — | — |
| Degraded (`worker.health.embedding_not_ready`) | 1 | 36px (line 18px) of 722px | "Semantic search degraded." | "Open Health" |
| Disclosed | 1 | 76px | same | same |

Zero `pageerror`s. The 53 console errors are all backendless fetch failures
(`ERR_CONNECTION_REFUSED` / a 502 from the dev proxy) plus one unhandled rejection from the
same cause — none originate in this branch's code. The resting screenshot is unchanged from
before the change: only the pre-existing availability line ("The local AI model is still
starting"). Severity mark computes to `oklch(0.769 0.188 70.08)` (`--warning`).

## 5. Honest limits

- The live degraded state was injected by writing the window's `aiSnapshot` field, not by
  driving the store: SES lockdown in the shell rejects a dynamic `import()` inside an
  evaluated page script, so `__feedForTest` is out of reach in the browser. The
  store → verdict → banner chain *is* covered end to end in the unit suite (the mounted-window
  case feeds a real `readiness.composites` snapshot). What the live run proves is the render
  and the geometry.
- No measured accessibility audit (axe / contrast oracle) was run against the new banner.
  The a11y-shaped gates in the recipe (`check-a11y-closure`, `check-controls-a11y`) pass,
  and the disclosure/remedy carry names and `aria-expanded`/`aria-controls`, but the
  `ux-audit-closure` discipline (independent, measured, auditor ≠ committer) is **not**
  satisfied by this branch.
- `degradationOpen` is not reset when the verdict changes, so a reader who opened banner A
  sees banner B open. This matches the shipped window's `degradationBannerExpanded`
  behaviour; it is a deliberate consistency choice, not an oversight.
- The single-slot rule is enforced by construction and by test, but only for the two
  producers that exist today (the degradation banner and the documents-affordance
  availability reason). A third future producer would need to join `sv3ComposerReason`'s
  decision rather than render beside it.
