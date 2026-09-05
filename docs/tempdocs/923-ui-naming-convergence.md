---
title: "UI naming convergence: Detailed mode and canonical Ask"
type: tempdocs
status: active
created: 2026-09-03
updated: 2026-09-05
parent: 893-hygiene-registers
related:
  - 504-systematic-ux-audit
  - 509-operation-label-coherence
---

# 923 — UI naming convergence

## Goal

Close the two product-language decisions that tempdoc 893 deliberately left open:

1. **F-25:** use **Detailed** as the user-facing name for the app-wide disclosure mode everywhere.
2. **F-22:** decide how the current Ask entry points relate, then implement the smallest coherent
   vocabulary and routing change.

The stored and wire-compatible mode value remains `advanced`. That identifier is an implementation
detail; renaming persisted values would add migration risk without improving the interface.

## Design

### Detailed is one app-wide disclosure level

The top bar and Settings already render `Simple | Detailed`, while the AI Brain surface and canonical
reference still say `Advanced`. All three controls write the same `ui.mode` setting, so the old claim
that Brain and Settings own different modes is no longer true. The correct repair is convergence on one
authority and one user-facing label, not another local mode.

- Render **Detailed** anywhere the `advanced` value is shown to a user.
- Keep `UiMode = 'simple' | 'advanced'`, `isAdvancedMode()`, JSON values, and URL/test identifiers stable.
- Make the Brain control publish and subscribe to `uiModeState`, like the top bar and Settings, so the
  three projections cannot disagree within one running page.
- Rename and rewrite the stale canonical Simple/Advanced reference for the active Lit implementation.
- Update user-facing help/remedy copy and focused tests. Historical tempdocs remain dated history.

This supersedes the user-facing term **Advanced** for the app-wide UI mode. It does not prohibit
“advanced” as ordinary prose or as an internal compatibility identifier.

### Ask

The independent F-22 investigation found that the interaction model is already consolidated in one
rail surface: `core.unified-chat-surface`, presented as **Search**, hosts retrieve, grounded Ask,
free chat, structured extraction, Delegate, and workflow shapes. The remaining problem is presentation
leakage, not architecture.

The product vocabulary is therefore:

- **Search** names the single top-level place and palette destination.
- **Ask** names a grounded, cited answer.
- **Delegate** names multi-step agent work; it remains distinct because it implies greater agency.
- **Structured** names field extraction.
- **Chat** is subordinate free conversation or a compatibility address, never another destination.

The implementation removes the duplicate **Go to Chat** palette action and excludes legacy Ask,
free-chat, and extract DEEPLINK IDs from split-pane discovery. Those IDs remain routable so existing
links can still select a mode. A persisted legacy secondary choice normalizes to Search, or to the
curated Library pairing when Search is already primary.

## Reach

The governing principle is **one user-facing name per shared state authority**. It earns its keep when
every control projecting `ui.mode` renders the same labels and changes propagate immediately between
surfaces. Retire this principle if the product later introduces genuinely independent modes with
separate persisted authorities; those modes should then receive distinct names.

No generic naming registry is introduced. The present work has two concrete concepts and existing
authorities; a new abstraction would exceed the problem.

## Plan and acceptance

- [x] Inventory current F-25 labels, mode projections, docs, and tests.
- [x] Converge Brain, Settings, top-bar copy, help text, and canonical docs on **Detailed**.
- [x] Prove Brain/top-bar/Settings mode projections share live state without changing the persisted wire value.
- [x] Record the F-22 current-state map and product recommendation from the independent investigation.
- [x] Implement the approved-by-autonomy F-22 vocabulary/routing boundary with focused tests.
- [x] Run frontend typecheck and unit tests, docs regeneration/verification, UI step coverage, and
  instrumented UI evidence for affected surfaces.
- [x] Critically review the final diff against F-22/F-25 and record remaining work honestly.

## Initial evidence

- `Shell.ts` and `SettingsSurface.ts` already render **Detailed** for the `advanced` value.
- `BrainSurface.ts` still renders **Advanced** and writes `ui.mode` directly without updating the shared
  `uiModeState` projection.
- `docs/reference/ui/simple-vs-advanced-mode.md` describes the retired React hook/store implementation
  rather than the active Lit `uiModeState` authority.
- Tempdoc 504 identified three historical Ask entry points; tempdoc 509 explicitly left their product
  relationship out of its operation-label work.

## Implementation and verification

- Brain now subscribes to `uiModeState`, renders **Detailed**, serializes rapid changes, and does not
  reseed the shared authority from a slower local refresh. Its POST body still carries
  `{"ui":{"mode":"advanced"}}`.
- Search is the sole interaction destination in the command palette, saved navigation labels use the
  presentation authority, and backend-unavailable fallback presentation still says **Search**.
- Legacy Ask, free-chat, and extract surface IDs remain routable, but split-pane discovery excludes
  them and canonicalized identity prevents Search from pairing beside a legacy interaction primary.
- Focused frontend verification passed after review, including shared ordering, timeout, reload,
  Settings, Brain, Shell, Stage, and router-resolution regressions. The final
  `npm run test:unit:run` run passed 470 files and 6,287 tests.
- TypeScript typecheck passed. Surface composition, interaction-surface, surface-altitude,
  intent-tier, and UI-step-coverage gates passed.
- The full `:modules:ui:test` task and `:modules:ui:spotlessJavaCheck` passed. The settings contract
  tests prove whole-document transaction serialization and stale-mode suppression without dropping
  unrelated fields.
- Documentation index/skill regeneration completed; llms, skills, canonical-link, module-dependency,
  and runtime-config-matrix checks passed. The optional repository-wide Markdown lint remains red on
  its recorded heading-case backlog; it identified no newly edited heading or front-matter defect.
- Instrumented captures `tmp/ui-shot/home.measure.json` and
  `tmp/ui-shot/ai-brain-advanced.measure.json` show the **Search** heading and accessible
  **Simple / Detailed** controls with no overflow or axe violations. The full accessibility sweep
  and UI proportion gate passed.
- The corrected `search-advanced-mode` harness setup now reaches the Detailed control. That older
  step subsequently stops at its already-documented retired `search-input` selector; repairing the
  cross-cutting search-step helper is existing harness debt and is not required to establish this
  naming change.

## Critical review

The refute-first review found four material issues. All in-scope objections were fixed:

1. Brain's local settings response could overwrite a newer global choice, and rapid clicks could be
   mistaken for a busy-operation failure. Brain no longer bootstraps the shared value and mode saves
   are serialized with latest-intent rollback/error ownership; deferred-refresh and rapid-toggle
   regressions cover both cases.
2. Raw-ID comparison could pair canonical Search beside a legacy Ask/Chat/Extract primary. Split
   eligibility now compares canonical identities, with all three legacy primaries covered.
3. The generic UI harness still looked for the visible label **Advanced**. Its stable wire-to-visible
   map now selects **Detailed**.
4. Canonical docs overstated uniform save-failure behavior and described retired Brain navigation
   links. They now document each existing persistence lifecycle and the actual header control.

No F-22 or F-25 implementation work remains. Publishing is deliberately separate authorization.

## Fresh review follow-up plan

The 2026-09-04 refute-first review upheld the naming and Search-routing results but found three
release objections in the shared disclosure-mode lifecycle:

- [x] Move mode-write sequencing from Brain into the shared `uiModeState` authority and route Brain,
  the top bar, and Settings through it, so successful cross-control writes persist in intent order.
- [x] Prevent Settings' delayed local load from overwriting a newer shared choice; merge the current
  authority value back into Settings' local snapshot.
- [x] Expose Brain's selected mode through `aria-pressed`, not only its visual `active` class.
- [x] Add regression coverage for shared write ordering, delayed Settings load, and Brain's accessible
  selected state; rerun focused and full frontend verification plus the UI/documentation gates.
- [x] Serialize the backend's whole-document `/api/settings/v2` transaction so a concurrent non-mode
  patch cannot restore an older `ui.mode` value (or lose the unrelated patch).
- [x] Bound every queued mode write with an aborting 10-second timeout, pass its signal through all
  three fetch adapters, and prove a timed-out request cannot wedge later intent or Brain's busy state.
- [x] Keep intent ordering valid across reloads and concurrent shell windows by persisting one origin
  client ID and atomically allocating its sequence with Web Locks; cover the reload boundary.

The review found no security or privacy issue. The compatibility routing, split-pane filtering,
Search presentation fallback, and user-facing Simple/Detailed vocabulary were independently upheld.

The final independent challenge found and closed two further ordering edges: page-local client IDs
did not survive reload, and a suspended Web Lock holder could initially bypass the timeout. The final
authority persists one origin client ID and sequence, allocates with Web Locks, and covers both lock
allocation and the network request with the same aborting timeout. Reload and deliberately held-lock
regressions passed. The reviewer then reported no remaining release objection and judged the reviewed
scope release-ready.

## Capability-realization closure plan

The 2026-09-05 realization pass found no new product-design decision, but it did find four places
where the implemented capability was not yet fully exercised or described through its intended
development paths. This follow-up closes those gaps without changing the compatible `advanced` wire
value or publishing the branch.

- [x] Catch the worktree up to `origin/main` and run the mandatory post-merge compile gate.
- [x] Add a production-stack HTTP contract proving the mode-intent header survives the real CORS,
  security-filter, route, controller, whole-document store, stale-intent, and restart path while an
  unrelated setting is preserved.
- [x] Register the shared `uiModeState` authority in the UI affected-step index so changes to the
  authority select every user-visible projection that depends on it; prove the affected-step query
  is non-empty and run the selected captures with measurement evidence.
- [x] Reconcile canonical and developer-facing prose: all three mode controls are top bar, Settings,
  and Brain; **Detailed** is the product label while `advanced` remains a compatibility identifier.
- [ ] Run targeted backend and frontend tests, frontend typecheck/unit tests, documentation
  regeneration and drift checks, UI coverage and affected captures, then retry the post-merge suite
  once concurrent Gradle pressure subsides. Record any unrelated baseline failure rather than
  weakening its test.
- [ ] Perform a fresh refute-first review of the final diff, fix every in-scope finding, and commit a
  self-contained closeout whose tempdoc evidence is sufficient for another agent.

Publishing, opening a pull request, enqueueing, and merging are explicitly excluded from this pass.

### Closure evidence in progress

- `UiModeIntentHttpContractTest` passed through a real loopback `LocalApiServer`. It verifies the
  CORS preflight admits `X-JustSearch-UI-Mode-Intent`, sends `simple:1`, `advanced:2`, then a stale
  `simple:1` request carrying `theme: dark`, and confirms both the immediate GET and a fresh-server
  GET retain `mode: advanced` plus the unrelated theme patch.
- The `uiModeState.ts` affected-step row is the exact deduplicated union of the seven direct
  consumer rows: 51 expected and 51 registered, with no missing or extra step. The UI-step coverage,
  baseline-schema, and jseval index tests passed.
- Targeted captures for `home`, `settings`, `ai-brain`, `ai-brain-advanced`, `chat-bands`, and
  `chat-bands-detailed` produced PNG and measurement companions under
  `tmp/ui-shot/923-closure/`. All six had zero axe violations and no overflow. The Brain fixtures
  still emit two real console diagnostics (an unnamed `jf-control` self-check and a timed-out DOM
  transition); those are retained as capture evidence and are not presented as clean console runs.
- The full accessibility gate passed all 20 registered surfaces with no new violation, and the full
  proportion gate reported every geometric constraint held.
- Frontend typecheck passed. The unit suite passed 6,285 tests and hit only the two recorded cold-load
  timeouts in `PluginLoader.test.ts` and `resourceRegistry.test.ts`; their combined isolated rerun
  passed all 35 tests. The complete `:modules:ui:test` task, including the new HTTP contract, passed.
- Documentation regeneration and all five canonical drift checks passed. The wording sweep now
  reserves **Detailed** for the product disclosure mode while retaining `advanced` only where it is
  a wire value, internal identifier, or exact historical label.
- Standards challenge: the WHATWG Fetch Standard confirms that a non-safelisted request header is
  preflight-relevant and checked against `Access-Control-Allow-Headers`; the W3C Web Locks draft
  confirms exclusive same-name locks prevent another context from acquiring that lock concurrently.
  Those are the two platform properties the HTTP preflight and cross-window sequence design rely on.
