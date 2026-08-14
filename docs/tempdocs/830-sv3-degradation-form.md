---
title: "830 — Search v3 degradation form (E1 readiness banner, E2 code-dedup, E3 mode-gated disclosure)"
type: tempdocs
status: "IMPLEMENTED + AUDITED (2026-08-14) — inventory rows E1/E2/E3 landed on branch sv3-degradation-form (PR #450). Independent measured a11y audit ran and found two defects (D1 live-region misuse, D2 false truncation claim); both fixed and pinned by mutation-probed tests. Unit suite + ui-web gate set green; live-verified backendless."
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
| `SearchV3View.degradation.test.ts` (new) | 32 cases (29 + 3 from the audit fixes). |

## 4. Verification

**Unit suite.** `npm run typecheck` clean, `eslint` clean; `npm run test:unit:run` — **5075
tests / 420 files, all passing**, of which **32 are new** (`SearchV3View.degradation.test.ts`;
29 for E1/E2/E3 plus 3 pinning the audit fixes).

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
- **D1 and D2 were mutation-probed by hand, not merely asserted.** Re-adding `role="status"`
  to the row fails the D1 pin ("wraps its CONTROLS in no live region, in either disclosure
  state", which also re-checks after the toggle re-renders both buttons); removing the
  headline's `title` fails the D2 pin. Both mutations were applied, observed failing, and
  reverted.

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
`#justsearch://surface/core.search-v3-surface`, window found by a shadow-piercing walk. The
degraded state is fed through the **real store** (`__feedForTest` with a genuine
`readiness.composites` payload, reached by a static `<script type="module">` import — see the
methodology note in §6), so the verdict on screen is the production derivation's:

| State | Verdict | Banners | Height | Headline | Remedy |
|---|---|---|---|---|---|
| Resting | `connecting` | 0 | — | — | — |
| Degraded (`worker.health.embedding_not_ready`) | `degraded` | 1 | 36px (line 18px) of 722px | "Semantic search degraded." | "Open Health" |
| Disclosed | `degraded` | 1 | 76px | same | same, cause row `data-code="worker.health.embedding_not_ready"` |

Zero `pageerror`s. The 53 console errors are all backendless fetch failures
(`ERR_CONNECTION_REFUSED` / a 502 from the dev proxy) plus one unhandled rejection from the
same cause — none originate in this branch's code. The resting screenshot is unchanged from
before the change: only the pre-existing availability line ("The local AI model is still
starting").

The three audit fixes were re-measured live in the same run, in both disclosure states:

| Probe | Measured |
|---|---|
| D1 — live regions inside the banner | `[]` (none), `buttonsInsideLiveRegion: 0`, banner `role: null` |
| D2 — headline recovery route | `title === "Semantic search degraded."` |
| A6 — severity glyph | `aria-hidden="true"`, tone still `oklch(0.769 0.188 70.08)` (`--warning`) |

## 5. Independent measured a11y audit (2026-08-14)

An independent auditor (≠ committer) ran a measured whole-surface audit — axe, a contrast
oracle, keyboard traversal, and independent geometry reproduction — satisfying the
`ux-audit-closure` discipline for this work.

**Validated:** axe 0 violations / 0 incomplete in every state; every contrast ratio passes
(headline 16.95:1, severity marks 8.66:1 and 5.24:1, focus ring 9.05:1); keyboard traversal
clean; geometry independently reproduced at 36px collapsed / 76px disclosed; E2 dedup
confirmed live.

**Two defects found and fixed on this branch:**

### D1 — `role="status"` was inert for announcing and active for noise

The row carried `role="status"`. Measured, that got announcement backwards in both
directions at once:

- The live region is created *together with* its content — a single `childList` mutation
  adds the whole subtree on healthy → degraded — so the banner's **appearance** does not
  reliably announce.
- Meanwhile the region really is `{live: polite, atomic: true}` and **both buttons sit
  inside it**, so toggling the disclosure fires six mutations within it (`aria-expanded`,
  `aria-controls`, `aria-label`, `title`, chevron path) and re-announces the concatenated
  line *"Semantic search degraded. Open Health Hide what is reduced"* on **every** open and
  close.

The state change is already announced correctly by the shell's always-mounted verdict
announcer ("All systems operational" → "Service degraded", measured). **Fix:** the live
region is gone; this banner renders the fact, it does not announce it
(`Sv3Composer.ts:1295`).

### D2 — the ellipsis justification comment was factually false

The comment claimed the full sentence "stays reachable as the accessible name of the region
it heads and in the disclosed detail". Measured: the banner container has `role=null` and
`aria-label=null` (no region, no name), and the disclosed detail carries body + causes only,
never the headline. Truncation is real — at ≤520px the headline box is 57px and all seven
authority headlines truncate; the longest truncates already at 640px — and a sighted reader
had no recovery route.

**Fix:** the claim was made *true* rather than deleted. The headline carries
`title=${degradation.headline}` (`Sv3Composer.ts:1313`) — the same idiom, and the same
explicitly accepted residual, as the answer frame's `title` in `Sv3Main.tailFacts`. The
corrected comment (`Sv3Composer.ts:283-289`) now states what is actually true: CSS ellipsis
clips pixels rather than text, so the accessible name was never truncated and AT was never
affected; the gap was sighted-only, and the `title` is its route. **Residual, stated
plainly:** a sighted keyboard-only reader still sees the clipped line.

### A6 (advisory, fixed)

The severity `<svg>` had neither `role` nor `aria-hidden`. It is decorative — the headline's
words and the tone carry the fact — so it is now wrapped in an `aria-hidden="true"` span
that also holds the tone colour, letting the glyph inherit through `currentColor` without
moving the tone off the element the severity rules select (`Sv3Composer.ts:1298`).

### Recorded for the owner — design calls, not defects

Left unactioned by direction; they are questions about the whole screen or about a
deliberate idiom, not things this branch should decide:

| # | Finding |
|---|---|
| A1 | The 13×13 disclosure target conforms only via the spacing exception. |
| A2 | Screen-wide the degradation fact appears **4×** — this banner, the verdict announcer, the status pill, the status badge. A whole-screen budget question, not a banner question. |
| A3 | Body text contrast is 4.74:1 — passing, but only 0.24 of margin. Note the token coupling: `--secondary-label` == `--icon-muted`, so moving one moves the other. |
| A4 | The banner box is nearly invisible against its surround (1.07:1) — the intentional quiet idiom versus the floating-text critique. |
| A5 | Defensible as-is (auditor's own verdict); no change. |
| A7 | Forced-colors mode keeps the amber mark rather than adopting the system palette. |
| A8 | ~482px gap between the headline and the remedy at wide widths, from the headline's `flex-grow`. |

## 6. Honest limits

- **Methodology correction (adopted, and the limit is now gone).** An earlier version of this
  note claimed SES lockdown makes the store unreachable in a live browser. That is **wrong**
  and must not propagate to future agents: SES rejects a *dynamic* `import()` inside an
  evaluated page script, but a `<script type="module">` **static** import is not blocked. The
  auditor drove the real `__feedForTest` / `setUiMode` that way; this branch's live harness has
  since been switched to the same technique (`page.addScriptTag({type: 'module', …})`), so the
  live run no longer hand-writes a verdict onto the view — it feeds a real
  `readiness.composites` payload and the production derivation computes the verdict. **The
  store → verdict → banner chain is therefore verified live as well as in the unit suite.**
- `degradationOpen` is not reset when the verdict changes, so a reader who opened banner A
  sees banner B open. This matches the shipped window's `degradationBannerExpanded`
  behaviour; it is a deliberate consistency choice, not an oversight.
- The single-slot rule is enforced by construction and by test, but only for the two
  producers that exist today (the degradation banner and the documents-affordance
  availability reason). A third future producer would need to join `sv3ComposerReason`'s
  decision rather than render beside it.

## 7. Log

- **2026-08-14** — E1/E2/E3 implemented; PR #450 opened as draft.
- **2026-08-14** — Independent measured a11y audit ran: axe 0/0 in every state, all contrast
  ratios pass, keyboard clean, geometry independently reproduced. D1 and D2 found; both
  fixed on the same branch and pinned by tests that were mutation-probed (re-adding
  `role="status"` fails the D1 pin; removing the `title` fails the D2 pin). Six further
  findings recorded above as owner design calls.
