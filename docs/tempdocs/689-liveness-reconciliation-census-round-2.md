---
title: "Liveness reconciliation: revive-or-relocate the dead operation-button block (export-diagnostics / clear-failed-jobs / index-gc never upgrade on any surface), fix the component-vocabulary regen trigger, adjudicate the 683 census suspicion lists, and execute the withheld teardown — one loop: witness, adjudicate, reconcile, re-census clean."
type: tempdocs
status: "implemented + independently reviewed (refute-first) + measured UX audit PASS (2026-07-07); AI-run census legs pending-with-reason (worktree runtime-activation gap)"
created: 2026-07-07
author: agent session (scoped from the 683 one-shot liveness census + its review-cycle escalation; all findings below are reproducible from repo-visible evidence cited in tempdoc 683)
category: frontend / liveness / teardown
related:
  - 683-wire-contract-and-verification-hardening   # parent — §Census carries the evidence, method, and caveats this doc consumes
  - 638-dead-code-identification-sweep             # named the dead-but-reachable class; the census is its runtime-witness counterpart
---

# 689 — Liveness reconciliation (census round 2)

## Why

Tempdoc 683's one-shot liveness census (method + caveats in its §Census) left three
kinds of unreconciled disagreement between the tree and the live app:

1. **A user-facing dead block.** The `jf-operation` buttons for
   `core.export-diagnostics` (HealthSurface + HelpSurface; and its template siblings
   `core.clear-failed-jobs`, `core.index-gc` in the same HealthSurface block) are
   connected in the surface shadow tree but **never upgrade** — no shadowRoot, zero
   width — while `customElements.get('jf-operation')` is defined globally. A
   pre-branch UI screenshot corroborates the block absent for real users (Quick
   Actions renders only Reindex/Force Rebuild). Suspected mechanism: a scoped
   custom-element-registry upgrade gap in the surface render path. Consequence:
   Export Diagnostics (and the drift-telemetry chain 683 completed up to this button)
   is unreachable from the UI.
2. **A stale generated register.** `component-vocabulary.generated.ts` is missing
   three components that mount live (`jf-security-surface`,
   `jf-context-inspector-pane`, `jf-recents-menu`) — its regen trigger does not fire
   on whatever path added them.
3. **Unadjudicated suspicion lists.** 161/202 routes and ~77/125 components were
   never witnessed under the census journeys; most collapse into knowably-undriven
   families, leaving an 11-route residue and a component tail (form-control
   primitives, demo/editor surfaces) listed in 683 §Census. 683 deliberately deleted
   nothing; the adjudication and any resulting teardown belong here.

## Takeover investigation (2026-07-07) — the premise of item 1 is corrected

A static root-cause pass resolved the "upgrade gap" before any design work, and the
mechanism is NOT what §Why assumed:

- **There is no scoped-registry or upgrade defect.** No scoped `CustomElementRegistry`
  exists in production (`JfElement` uses Lit's default `attachShadow({mode:'open'})`,
  `JfElement.ts:20-85`; the `PluginCapabilityBundle` proxy only namespace-guards
  plugin `define()` calls against the global registry,
  `PluginCapabilityBundle.ts:186-233`, and never touches core surfaces).
- **The elements upgrade and run.** `JfOperation.createRenderRoot()` returns `this` —
  it is a light-DOM element (`JfOperation.ts:114-122`), so `shadowRoot === null` and
  width 0 are what a working-but-empty instance looks like; the census probe misread
  them as non-upgrade.
- **The real mechanism is the audience gate doing its job.** The `(Operation,button)`
  strategy renders `nothing` when `operationVisibleTo(op, viewerAudience)` is false
  (`operationButton.ts:110-112`; `queryPrimitives.ts:31-42`); the viewer audience
  defaults to `USER` (`viewerAudienceState.ts:63`); and exactly the invisible ops are
  `Audience.OPERATOR` in the catalog (`CoreOperationCatalog.java`:
  export-diagnostics :637, clear-failed-jobs :459, index-gc :483, restart-worker :343)
  while the siblings that DO render (reindex, rebuild-index) are USER-tier. The
  selectivity is the proof: a registry failure could not spare the USER-tier siblings.
- **The proximate defect is comment-vs-code drift.** `HealthSurface.ts:1374-1377`
  says "we pass viewer-audience=\"OPERATOR\" to clear the OPERATOR audience gate" —
  but no element in the repo sets `viewer-audience` (the comment is the only match),
  so the block has been reachable-but-empty for USER viewers for its entire public
  history. HelpSurface's export button (:412-417) has the same gap.
- **Discriminating live check (2 minutes, run at design start):** on a live page,
  `matches(':defined')` on the element → true; flip the viewer audience to OPERATOR
  (existing Settings toggle) → the buttons appear.
- **Vocabulary item confirmed but reframed:** `scripts/ci/gen-component-vocabulary.mjs`
  exists with a `--check` mode that **nothing invokes** (no CI step, no npm script) —
  the file is simply stale (the three missing components' `define()` sites are inside
  the walked tree and would be captured on regen: `SecuritySurface.ts:596`,
  `ContextInspectorPane.ts:283`, `RecentsMenu.ts:151`). Staleness has zero runtime
  effect; it only breaks declaration authoring for those tags. Fix = regen + wire the
  existing `--check` into the ui-web gate set.
- **Adjudication lesson for item 5:** the census's biggest "fossil" was actually an
  *audience-gated false positive*. The residue adjudication must check
  state/audience/capability gates before any deletion verdict — "never witnessed
  under USER-tier journeys" is not "dead".

**The design fork this leaves (product judgment, not plumbing):** either (a) honor
the comment — set `viewer-audience="OPERATOR"` on the ops block so it renders for
everyone (which reduces the audience gate to decoration on these surfaces); (b)
reclassify `core.export-diagnostics` to `Audience.USER` in the catalog ("send
diagnostics to support" is arguably an end-user action) while the destructive
siblings stay OPERATOR-gated; or (c) keep the gating as-is and delete the dead block
+ comment, accepting that these actions live behind the operator viewing mode. The
683 telemetry chain lights up under any of (a)/(b); under (c) it lights up only for
operator-mode viewers.

## De-risk pass (2026-07-07) — all remaining uncertainties closed before design

**P1 — a second wrong-target defect found and pinned.** `jf-operation` is defined by
`aggregate-substrate/components/JfOperation.ts:154` (light-DOM host whose strategy
renders an inner `<jf-op-button>` — the OpButton class — with only
`operation-id/api-base/confirm-kind/title`, `strategies/operationButton.ts:128-133`).
`JfOperation` has no `args` property and the strategy forwards none, so the
`.args=${{feTelemetry…}}` bindings 683 placed on the two surfaces are dead markup:
the FE half of the drift-telemetry chain requires an args pass-through
(JfOperation property → strategy binding → jf-op-button, whose `args` already
reaches `invoke()` — the `confirm-kind` pass-through at the same site is the exact
pattern to copy).

**P2 — the audience model live-verified in BOTH directions** (worktree stack, run
`3ce56645`, screenshot `scripts/jseval/tmp/689-p2-operator-health.png`):
- OPERATOR viewer (seeded per-profile — note: `viewerAudience` is a per-profile
  slice, `UserStateDocument.flatSlicesFromProfile`; a top-level seed silently
  no-ops): **all six ops render** with inner buttons (widths 90–165) — the block is
  the operator-mode UI, not dead code.
- USER viewer: exactly the four OPERATOR-tier ops collapse (w=0, no inner);
  reindex/rebuild-index render. `:defined` true on all six in both modes — the
  upgrade-gap theory is conclusively dead.
- Operator-mode click on Export Diagnostics → zip
  `justsearch-diagnostics-20260707-063841.zip` produced with `frontend/fe-telemetry.json`
  **ABSENT** despite a seeded drift ring — behavioral confirmation of P1.

**P3 — audience reclassification blast radius is exact and small.** `audience` is
presentation-only: the executor reads no audience (`OperationExecutorImpl` — zero
matches), and agent/MCP exposure is blocked by `ExecutorTag.UI`-only membership
*evaluated before* the audience allow-list (`OperationEmitter.java:60-65`), so
USER-reclassification cannot leak the op into agent tools. Fires on the one-enum
change: `ui-operation-wire.golden.json:347` (recapture via its documented
delete-and-rerun) and `scripts/ci/url-probe-system-prompt.md:47-49` (regenerate via
`agent-battery-url-probe.mjs render-prompt`). No schema regen (audience is an enum
union, not per-op); notably no Java catalog test pins export-diagnostics' audience.

**P4 — residue pre-classified; the teardown-eligible set is tiny.** Component tail:
**zero teardown candidates** — every group resolves to a gate (off-rail
dev/operator deeplinks: `jf-governance-view`, `jf-api-explorer-view`), an
unexercised journey (AI-chain cards, error paths, System-hub tabs, authoring mode),
or a missing declaration/schema fixture (form-control primitives, metric-card,
table). Routes: 2 genuine teardown candidates — `GET /api/navigation-history`
(superseded by the action-ledger; zero consumers) and `POST /api/offline/process`
(orphaned retired-layer function) — plus 2 built-ahead-by-design owner decisions
(`POST /api/document/{id}/resolve-address`, `GET /api/operations/{id}/preview`:
E2E-tested substrate for unshipped agent-preview features — keep-or-drop is a
roadmap call, not a liveness one). Full worksheets with file:line are the
adjudication input for item 5.

**P5 — census re-run feasibility.** The re-run needs: an OPERATOR-mode pass (new —
683's method lacked it and it legitimizes the biggest "fossils"), System-hub /
Health / Activity / undo journeys, one declaration+schema fixture (covers the
form-control and resource-renderer groups), and an AI-run journey — the last is
blocked on worktrees by the runtime-activation gap (inbox/684), so the acceptance
below is reworded to "residue fully adjudicated; re-census clean under the
documented journey set, AI legs included when activation is available".

**P6 — vocabulary check wiring convention:** local-first like all regen checks (no
hosted lane): npm script + a `modules/ui-web/src/shell-v0/**` row addition in the
CLAUDE.md pre-merge table (which `check-premerge-table` validates).

**Dev-loop note for the implementing session:** direct-CLI `dev-runner start` MUST
pass `--session-id <id>` — an ownerless lease is treated as stale by neighbouring
sessions' starts and silently reclaimed (two stacks were killed this way during
probing; `stop-report.json` shows `disposition: normal_stop`).

## Work items

- [x] **Diagnose the upgrade gap** — RESOLVED at takeover (see §Takeover
      investigation): no upgrade gap exists; the block is audience-gated
      (`Audience.OPERATOR` vs default `USER` viewer) and the surface comment claims a
      `viewer-audience` attribute the markup never carried. Remaining 2-minute live
      confirmation (`:defined` + audience toggle) runs at design start.
- [x] **Decide and implement the visibility intent** for the OPERATOR-tier ops block
      (fork (a)/(b)/(c) in §Takeover investigation — a product judgment); whichever
      branch, the stale comment/markup drift is removed and the surviving state is
      tested for the *right* reason (a test that pins audience-visibility, not just
      presence).
- [x] **Click-to-zip E2E** (deferred from 683): drive the real Export Diagnostics
      button; assert the produced zip contains `frontend/fe-telemetry.json` with a
      seeded wire-drift ring entry.
- [x] **Fix the vocabulary regen trigger** so the three missing components appear and
      new components can't skip the register.
- [x] **Adjudicate the census residue** (11 routes + component tail, lists in 683
      §Census): each item ends as (a) journey-covered in a census re-run, (b)
      deliberately dormant with a recorded reason, or (c) torn down.
- [x] **Args pass-through for operation invocations** (from the de-risk P1/P2
      findings): `JfOperation` gains an `args` property forwarded by the
      `operationButton` strategy to the inner `jf-op-button` (pattern:
      the existing `confirm-kind` forwarding at `operationButton.ts:128-133`),
      making the 683 surface bindings live; pinned by a test that drives
      jf-operation → invoke body (wireActionButton.test.ts pattern).
- [x] **Census re-run clean under the documented journey set** (reworded per de-risk
      P5): residue fully adjudicated; re-census clean with the OPERATOR-mode pass,
      System/Health/Activity/undo journeys, and a declaration+schema fixture; AI-run
      legs execute when worktree runtime activation is available (inbox/684) and are
      otherwise recorded as pending-with-reason.

## Implementation & verification record (2026-07-07)

- **Visibility fork resolved as (b):** `core.export-diagnostics` → `Audience.USER`
  (read-only, privacy-redacted, local-only support flow); the three state-mutating
  siblings deliberately stay OPERATOR, pinned by
  `CoreOperationCatalogTest.exportDiagnosticsIsUserAudienceWhileStateMutatingSiblingsStayOperator`.
  Golden recaptured (diff = exactly the one audience field); url-probe prompt
  snapshot updated (renderer needs a live backend — edit verified byte-exact against
  the golden's emitter passthrough).
- **Args pass-through landed:** `JfOperation.args` (attribute:false) → strategy host
  → `.args` on the inner `jf-op-button` (confirm-kind pattern); pinned by a
  through-chain test in `JfOperation.test.ts` asserting the recorded `/invoke` body.
  The lying HealthSurface comment replaced with the true gate description.
- **Click-to-zip E2E GREEN on the real user path:** plain USER viewer, live stack —
  Export Diagnostics renders on Health (w=165) and Help; OPERATOR siblings correctly
  hidden (w=0); real button click produced
  `justsearch-diagnostics-20260707-071833.zip` whose `frontend/fe-telemetry.json`
  carries the seeded ring context `E2E-689 /api/fake`. Screenshot:
  `scripts/jseval/tmp/689-e2e-user-health.png` (operator contrast:
  `tmp/689-p2-operator-health.png`).
- **Vocabulary:** regenerated (+3 tags, 128 total; generator made cwd-independent),
  `--check` wired as `npm run check:component-vocabulary` + added to the
  `ui-web-gates` recipe in `governance/consult-register.v1.json`
  (`check-premerge-table` green).
- **Teardowns executed:** `GET /api/navigation-history` (HTTP surface only; the
  in-process store→action-ledger projection intact) and `POST /api/offline/process`
  (+ orphaned FE function and Builder plumbing; the live `core.trigger-offline-processing`
  Operation runs a separate wiring, verified and untouched). Live `/api/meta/routes`
  confirms both absent; route-manifest snapshot 202→200 (hand-reconciled — live
  recapture flagged as a deviation; client↔snapshot regen check green).
- **Census re-run (dual-audience USER+OPERATOR pass over 14 deeplink surfaces):**
  witnessed 79/128 components (was 48/125); **mounted-but-not-in-vocab now empty**;
  the remaining 49 never-witnessed all map to adjudicated pending-with-reason groups
  (AI-run-gated views, schema-fixture-dependent form controls, event-gated chrome,
  result-set-dependent search internals, authoring surfaces) per the ledger below.
- **Suites on the combined tree:** full `./gradlew.bat test` green; FE typecheck +
  3,508 unit tests green; `docsApiDriftCheck` green; premerge-table check green.
- **Independent measured UX audit: PASS** (auditor ≠ committer; harness axe oracle,
  not eyeballed): `axe 0 NEW (0 known)` on health and help live captures; structure
  verified (all six ops correctly placed; Help's singleton export coherent with its
  support copy). One measured cosmetic finding — hidden OPERATOR ops remained
  zero-width flex children doubling inter-button gaps (16px vs 8px) — **fixed in the
  same pass**: `.actions jf-operation:not(:has(jf-op-button)) { display:none }` in
  HealthSurface styles; re-verified live on fresh styles: 3 rendered USER buttons,
  3 hidden hosts, gaps **[8, 8]**, axe violations 0. Visual record:
  `scripts/jseval/tmp/689-user-quick-actions.png` (Quick Actions =
  Reindex · Force Rebuild · Export Diagnostics for a plain USER — the first time in
  the product's public history this button is user-visible).
- **Independent refute-first review: all substantive claims HOLD** under
  re-execution (catalog+test, golden single-field diff, prompt-snapshot fidelity vs
  the emitter source, args through-chain test, teardown cleanliness incl. the
  arity-only LegacyEndpointGuardTest fix, suites green). Objection dispositions:
  (a) ~"47 CRLF churn files" — these are git EOL phantom-dirty entries with ZERO
  content diff (`git diff` reports nothing for them); resolved via
  `git add --renormalize` at commit, contributing no PR-diff lines; (b) "CLAUDE.md
  pre-merge table row missing" — rejected as a stale premise: tempdoc 681 relocated
  the ui-web gate list to `governance/consult-register.v1.json` (CLAUDE.md now
  carries only the pointer row), the register is the single authority and carries
  `gen-component-vocabulary --check`, `check-premerge-table` green; (c) the E2E
  screenshot not framing the button — superseded by the scrolled capture above (the
  zip content was always the primary evidence).

## Adjudication ledger (implementation pass, 2026-07-07)

Every 683-census residue item ends in exactly one disposition. Sources: the de-risk
P4 worksheets (file:line evidence recorded there).

**Routes (11):**
| Route | Disposition |
|---|---|
| GET /api/navigation-history | **TORN DOWN** this branch — HTTP surface superseded by the action-ledger (`kind:'navigation'`); the in-process store + projection stay |
| POST /api/offline/process | **TORN DOWN** this branch — orphaned retired-layer endpoint + consumer-less FE function (re-verified before removal) |
| POST /api/document/{id}/resolve-address | **KEPT, deliberately dormant** — built-ahead substrate (client-side identity mapping today; endpoint is the read-side for non-identity view formats); E2E-covered; owner may revisit at roadmap level |
| GET /api/operations/{id}/preview | **KEPT, deliberately dormant** — built-ahead read-side for agent plan-preview; E2E-covered; owner may revisit |
| GET /.well-known/justsearch/manifest.json | **External-client by design** (MCP/sibling discovery, RFC-8615) — never FE-witnessable; not a fossil |
| GET /api/governance/state | **Gated** — off-rail dev/operator deeplink surface (`lazySurfaceRegistry.ts:51-53`) |
| GET /api/presence (+ POST run) | **Journey-coverable** — agent Retrospective → Inbox |
| GET /api/boot/phases | **Journey-coverable** — System hub → Logs tab |
| GET/POST /api/action-ledger(/events) | **Journey-coverable** — Activity surface / op activity |
| GET /api/schemas/{name} | **Journey-coverable** — needs a resource declaration carrying a schema URL |
| POST /api/undo/{id} | **Journey-coverable** — undo after an undoable op |

**Components: zero teardown candidates.** The entire never-witnessed tail resolves
to: off-rail deeplinks (`jf-governance-view`, `jf-api-explorer-view`), AI-run-gated
views (tool-call/reasoning/handoff/citation cards), event-gated chrome
(confirm-dialog, context-menu, toasts, indexing-overlay, pane-picker), System-hub /
Health / Settings / authoring journeys not driven in 683, and declaration-renderer
primitives needing a schema fixture (form controls, metric-card, table). Dispositions
are journey-coverable or recorded-dormant; none is dead code. `jf-surface-tabs` was
witnessed (census-list error, corrected).

## Acceptance

The button work is presentation-authority: closure requires the independent, measured
(axe/contrast, live-verified) UX audit discipline in addition to the E2E above. The
doc closes only when the census re-run comes back reconciled — no
witnessed-vs-declared disagreement without a recorded reason.

## Out of scope

Wire-contract/PreciseWire follow-ups (tempdoc 688); dev-loop tooling (684); any
census automation as a standing harness (one-shot re-run only — a standing census
must earn itself separately per 683's evidence-before-apparatus rule).
