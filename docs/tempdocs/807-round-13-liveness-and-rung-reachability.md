---
title: Round-13 fix campaign — snapshot liveness, and the escalation rungs' landing-only gate
status: "IMPLEMENTED 2026-08-05 (Part D) — four bundles landed and accepted. R13-F2 fixed and PROVEN LIVE (every stale present-tense claim now reads as last-observed; red CONN dot; controls disabled) after the designed ~40s contact window; the headline sentence is proven at the rendered-DOM test tier because this dev data dir has no chat model. The rung-reachability class is closed at its cause (the escalation strip no longer vanishes post-search), which also fixes round 13 shape:core.extract coverage gap properly. R13-F1 fixed with a clean non-fork NSIS lever after a makensis probe DISPROVED tempdoc 806 recorded blocker. Plus: model-degraded warnings half-fixed (the rest is a release-asset gap, logged), expiresAt now on the wire (the TTL existed all along), and fail-closed approvals now tell the user. Forced suite 186/186 with one non-causal flake proven by isolation; ui-web 4005 green. NEXT: PR, then ONE fresh-install confirmation round for a clean 0.2.0 qualification"
created: 2026-08-05
updated: 2026-08-05
---

# Round-13 fix campaign — liveness and rung reachability

Round 13 (tempdoc 734) was the qualifying round. It found **no blocking product defect** —
golden parity passed its blocking assertion for the first time, the fresh-install AI journey,
locked-state truthfulness, uninstall and warm reinstall are all clean. Two things stop it being
a clean qualification, and both are fifth instances of classes this project already named.

---

## Part A — R13-F2: a snapshot rendered present-tense

### A.1 What was observed, and what it actually is

The round found the Brain card reporting "Online / Chat and summaries ready" with **zero** Head
or Worker processes alive. The orchestrator reproduced it on the dev stack and found it wider:
with both java processes killed and `/api/health` unreachable, the surface simultaneously
rendered an **animating** "Building semantic search 2.0% · 5,084 pending" progress card,
"Search Quality Features 4/4 active", a populated Runtime card (CUDA available, VRAM 12 GB,
embed queue 4,789), and a **green CONN dot** in the status bar.

So this is not one stale card. Every surface that renders from the *status snapshot* keeps
asserting live facts about a process that no longer exists.

### A.2 The mechanism — and why the existing authority did not catch it

The verdict authority already models this: `SystemHealthVerdict` has an **`unreachable`** kind,
`readinessNotice` words it ("Backend disconnected."), and `verdictOwnsStatus()`
(`aiStateStore.ts:462`) is the shared predicate the status label and tone both consume since
806 W2.

The gap is one layer down. `verdictOwnsStatus` governs the **status line's own wording and
tone**. It does not govern the many surfaces that read *fields off the last successful
snapshot* — `BrainSurface`'s progress card reads `emb?.coveragePercent` and a queue count
directly (`:1868-1890`); the capability count, the runtime card, and the connection dot do the
same. Each renders a **last-known observation in the present tense**, and nothing asks whether
the observation is still live.

That is the campaign's signature class for the fifth time, now in its purest form: **the value
is not wrong — its tense is.** "4/4 active" was true when it was measured. Rendering it while
the backend is gone converts a past measurement into a present claim.

### A.3 Design — one liveness question, asked once

The store already knows everything needed: it has the verdict (including `unreachable`) and it
knows when the snapshot was last refreshed. Add **one derived signal — is the snapshot live? —
projected from the same verdict authority**, and have snapshot-rendering surfaces consume it
rather than each inventing a staleness heuristic.

When the snapshot is not live, surfaces must degrade rather than assert: progress/queue/coverage
figures stop animating and read as last-known (or disappear), capability counts stop claiming
"active", the connection dot reflects reachability rather than the last good poll, and any
action whose precondition is a live backend is unavailable-with-a-reason rather than clickable.
The existing "Backend disconnected." banner stays the loud, correct signal — this change stops
the surfaces *around* it contradicting it.

**Explicitly rejected:** per-surface `if (unreachable)` patches. That is how the status label
and dot drifted apart in the first place (806 W2's Health finding — the label lacked the arm its
own doc comment claimed). One predicate, consumed; the gate that enforces the classifier's
single authority is the model.

**Regression home:** a ui-web test that with an `unreachable` verdict no snapshot-derived
surface renders a present-tense capability/progress claim; plus the standing
`ui-api-truthfulness-under-load` must-watch, which is what caught it.

---

## Part B — The rung-reachability class, root-caused

### B.1 One line explains five findings

`UnifiedChatView.ts:2507` renders the escalation strip as
`${this.isLanding() ? html`<div class="escalation-strip">…` — **the strip exists only in the
empty landing state.** Once a search has run, every rung control is gone from the DOM, and the
only remaining way into a non-default affordance is the "+ Schema" attachment path (which
derives `extract`) or a fresh session.

That single gate explains the whole class:

| Round | Finding |
|---|---|
| 5 | `agent-run` / `free-chat` "not found" — entry points existed, hidden behind composer state |
| 7 (B2a/B2b) | `workflow-run` unreachable by any user; `free-chat` deep-link only |
| 11 | Delegate rung reachable *only from the empty landing state* — ~8 min lost, recovered only after a cold restart returned the app to landing |
| 13 | No route back to the Structured rung after a search — **cost the qualification round its coverage gate** |

Tempdoc 805 F.5c deferred this class on the explicit grounds that it was "four findings without
a blocker." The fifth finding blocked a gate; the deferral's own trigger condition is met.

### B.2 Design — minimum viable, not the full campaign

805 F.5c's full proposal (declare each rung's entry conditions as data, render from the
declaration, make reachability unit-testable) remains the right long-term shape and stays
deferred. What this campaign does is the **smallest honest slice**: the escalation rungs remain
reachable after a search has run — the strip (or an equivalent control carrying the same rungs
with the same availability semantics) does not vanish when `isLanding()` goes false.

Constraints that make this small rather than a redesign: the rung controls, their labels, their
availability-with-reason behaviour and their `data-testid`s already exist and are correct — only
their *render condition* is wrong. Keep the landing presentation as-is; add the post-search
route. Do not redesign the composer.

**Regression home:** a ui-web test asserting each rung is reachable in the post-search state,
not only on landing — the assertion that would have failed for rounds 11 and 13 alike. This
also closes round 13's `shape:core.extract` coverage gap at its cause rather than by renaming a
screenshot.

---

## Part C — R13-F1 and derisk notes

**R13-F1 (LOW).** Interior wizard pages and the uninstaller carry
"JustSearch 0.2.0 - Copyright (c) JustSearch"; the two MUI full pages (Welcome, Finish) render
an empty strip. 806's `bundle.copyright` → `BrandingText` lever works where a strip exists; the
MUI pages do not draw one. Either give those pages a version-bearing element or accept the
partial and correct the claim — the honest options, in that order of preference. The host-side
regression home is a source-side assertion extended to those templates.

**Derisk, recorded before implementation:**
- **C1 — the `unreachable` verdict is already produced** (`aiStateStore.ts:465,578`), so W1
  consumes an existing signal rather than inventing detection. Confirmed at source.
- **C2 — the strip's controls are already correct** in label, availability and test ids
  (`:2508-2545`); only `isLanding()` gates them. Confirmed at source, which is what makes B.2 a
  render-condition change rather than a feature.
- **C3 — open risk on W1's blast radius:** "surfaces that render from the snapshot" is not an
  enumerated set. Implementation must enumerate the consumers it changes and say which it left
  alone, rather than claiming a class-wide fix it did not make. A partial fix honestly scoped is
  acceptable; an overclaimed one is not.
- **C4 — the CONN dot may be a distinct authority** from the AI-state store (the status bar has
  its own connection tracking, `HealthLitView.ts:144` carries a disconnect debounce). If so, the
  fix must reconcile the two rather than adding a third; report which authority won.

---

## Part E — W3 outcomes (R13-F1 and the three round-13 smaller findings)

### E.1 R13-F1 — FIXED, and 806's "impossible" was wrong at source

**A clean non-fork lever exists.** MUI takes both full pages' text through `MUI_DEFAULT` — an
`!ifndef` (`Contrib/Modern UI 2/Pages/Welcome.nsh:72`, `Pages/Finish.nsh:156`) — so a `!define` in
`nsis/installer-hooks.nsh`, the one installer file this repo owns, wins over MUI's default. No
template fork, no `customLanguageFiles`.

806's blocking note — *"`installer_hooks` is included before `!define VERSION`, so hooks cannot
reference the version"* — is **false for this use**, and that was verified rather than argued.
`!include "{{installer_hooks}}"` is indeed line 35 and `!define VERSION`/`COPYRIGHT` lines 42/55
(tauri-bundler `installer.nsi`, dev @ 2.11.x), but NSIS expands a nested `${...}` inside a define
when the define is **used**, not when it is written. A `makensis` probe settled it:

```
!define HOOKTITLE "JustSearch ${VERSION}"    ; VERSION not yet defined
!define VERSION "0.2.0"
!warning "PROBE_RESULT=[${HOOKTITLE}]"       ->  PROBE_RESULT=[JustSearch 0.2.0]
```

A second probe compiled the REAL hook file in the template's exact ordering with
`MUI_PAGE_WELCOME`/`MUI_PAGE_FINISH` (exit 0) and found the branding line baked into the built
installer twice in its appended form — once per page. The 806 constraint holds only for
*immediate* uses at include time.

Shipped: `MUI_WELCOMEPAGE_TEXT` / `MUI_FINISHPAGE_TEXT` appended with `${COPYRIGHT}` (localized MUI
text preserved, so non-English installers keep their wording), plus `MUI_FINISHPAGE_TEXT_LARGE` —
not cosmetic: the finish page carries both checkboxes, which caps its text box at 40u and would
clip the appended line. Regression home: `scripts/ci/check-installer-branding.mjs` (+ test, wired
into CI) pins both halves — `bundle.copyright` must name the bundle version, and both MUI pages
must declare `${COPYRIGHT}`-bearing text. The false claim in `sync-version.ps1` ("the strip at the
bottom of EVERY wizard page", "hooks cannot name the version at all") is corrected in place.

### E.2 "Shipped models reported degraded" — half fixed here, half needs a release asset

Six warnings, and they split cleanly. **Four are precision, and they were this repo's defect.**
`Fact.PRECISION` sat in the `EMBEDDING` and `NER` role presets justified as *"informational —
surfaced by `DevModeVariantProbe`"*. That premise is false at source: `DevModeVariantProbe:59,75-83`
loads its own manifest and runs its own filename guess, never consulting the resolver, and nothing
in production reads `ModelCapabilities.cpuPrecision()`/`gpuPrecision()` at all — the runtime's
precision authority is `VariantSelection.precision()` off the install contract. So the resolver
guessed an unread fact from a filename and logged the guess as "model capability degraded",
including **FP32 for the NER CPU variant the registry declares INT8**, about a file a CUDA install
never downloads. Precision is now in no role preset (still reachable via `ALL` for diagnostics);
`ModelCapabilityResolverShippedPackTest` pins the shipped-pack layouts.

**Two are real and CANNOT be fixed here.** The released `embed-model_manifest.json` asset (135
bytes) predates the tempdoc-710 capability authoring pass: no `capabilities` block, and `cpu`
pointing at `model_fp16.onnx` where the repo's own manifest says `model.onnx`. The `ner`,
`reranker`, `citation-scorer` and `splade` packs ship **no manifest at all**. So a clean install
genuinely cannot declare the embedding pack's context length or prefixes, and those two warnings
are correct — they are the release assets telling the truth about themselves. Closing them means
uploading regenerated manifests to `justsearch-releases@models-v1` and adding the matching
`supportingFiles` sha256/URL entries to `model-registry.v2.json`. Deliberately left undone and
logged; the test above asserts those two warnings STAY, so silencing them later is a visible change.

### E.3 F3's premise was wrong: pendings do expire — nothing said when

Round 13 called the `expired-pending-approval-ceremony` unperformable "because no TTL exists".
The TTL exists: `PendingAuthorizationStore.DEFAULT_TTL` is 5 minutes, `PendingAuthorization` has
carried `expiresAt` since it was written, and `PendingAuthorizationEvent` carries it too. It was
simply never put on any consumer-facing surface. Added additively (ISO-8601 UTC) to the peek
endpoint's payload and the advisory stream's `classExtras`; the raw `system:pending-authorizations`
payload already carried it (whole-record serialization). The charter item is now performable, and a
client can tell a user how long an approval request is valid.

### E.4 The silent failed approval was a fail-closed deny wearing a refusal's clothes

Every approve path already reported network/HTTP failures. The one branch with no user-visible
outcome was `!decision.approved` — which conflates an explicit human deny with a **fail-closed**
outcome: no `<jf-authorization-host>` mounted (`authorizationBroker:124`) or a mounted host torn
down mid-ceremony (`AuthorizationHost.failClosed`). Both resolved the identical
`{approved: false, allowAlways: false}`, so the bridge returned in silence — exactly round 13's
shape: the modal dismissed, nothing dispatched, nothing said. Fixed by marking those two sites
`failedClosed: true` (additive, mirroring `superseded`) and reporting them through
`reportExecutionFailure` → the existing sticky error toast. An explicit deny stays silent; a
decision is not a failure.

---

## Part D — Implementation record

**W2 (rung reachability) — ACCEPTED.** Route (a) extended: the escalation strip now renders in
both states from ONE rung definition (`renderEscalationRungs()`), landing markup byte-for-byte
unchanged, both branches at the same position inside the same `.composer` container so 687 R5a's
stable-slot invariant holds (`jf-composer` is never moved, re-parented or re-keyed). Route (b)
was rejected with evidence: the post-search route chip expresses the per-turn route
(search↔ask), a different axis from the standing tier, and its whole row renders only while
`affordance === 'retrieve'` — i.e. it is invisible in exactly the pinned-tier states rounds 11
and 13 got stuck in, so it cannot be the carrier.

Two docked-only rungs beyond the brief's three, each forced by the fix and each argued:
**Structured** goes through the one derivation authority (`explicitAffordance = null;
schemaAttached = true` — `deriveAffordance` prefers explicit over attachment) rather than setting
`affordance = 'extract'` directly, which would have made "Detach schema" a silent no-op
(bite-proven); and **Search (back to the floor)** exists because adding a post-search entry into
`agent`/`documents` WITHOUT an exit would have re-created round 11's trap in reverse, escapable
only via "New chat". Five bite proofs, the first of which re-broke the exact pre-fix condition
(`isLanding() || true`) and failed the four post-search tests while the landing test stayed green
— proving the bite touched only the new route.

**Round 13's uncovered `shape:core.extract` is closed at its cause:** one test drives the real
DOM (`jf-control` → shadow `<button>` → click) from a rendered-results state and reaches
Delegate → `agent` (round 11's finding), Structured → `extract` / `core.extract` (round 13's),
Ask → `documents`, and Search → `retrieve`. Offline, all three AI rungs report
`kind: 'unavailable'` with the 804 §B9 sibling wording rather than dead-clicking.

Two refinements the worker established at source, worth keeping: post-search **in the retrieve
tier** the "+ Schema" route row was still present — the Structured dead-end begins the moment
the tier is pinned away from retrieve (`escalateAsk` pins `documents`), which is exactly round
13's path; and the Structured rung is deliberately left unconditional even where that duplicates
"+ Schema", because hiding it when another route happens to be showing would re-create the
"hidden behind composer state" class this campaign exists to kill.

**Cross-bundle finding (recorded because the discipline is the point):** W2 measured 24 failures
across three test files, traced them to W1's in-flight `snapshotLive` arm in
`projectAvailability`, and PROVED causation by adding the field to one fixture (157/157 green)
before reverting it per "report, don't fix" — leaving the fix to the owning bundle in one
coherent pass. Residual for W1's C3 enumeration: the rungs gate on `aiState.capabilities.chat`
directly rather than `projectAvailability`, so with a dead backend they stay clickable —
pre-existing, not a regression, and the same class as A.3.

**W1 (snapshot liveness) — ACCEPTED, and it corrected this document's design twice.**

*Correction 1, material:* Part A.2's premise was wrong. `computeVerdict` mints `unreachable`
ONLY when `phase === 'disconnected'`, which requires that **no poll ever succeeded**. Killing a
backend that was working drives `phase` to `'stale'` → `computeStability` returns
`channel-stale` → the verdict is **`transitioning` / warn / `['channel-stale']`**, label
"Reconnecting…". A predicate keyed on `unreachable` alone — which is what A.3 described — would
have left every photographed surface exactly as it was. The shipped `isSnapshotLive(verdict)`
covers both kinds (precisely the two `computeVerdict` mints when `reachableViaContact` is false),
so it is a faithful re-reading of the one verdict authority rather than a second signal.

*Correction 2:* A.3's claim that the "Backend disconnected." banner is the loud correct signal
the surfaces were contradicting is also wrong — that banner is `unreachable`-only too, so it
never appeared in the reproduced state either (the loud signal there is the pill's
"Reconnecting…"). W1 authored no new disconnection phrase; every string it added imports
`reasonFor('binding.unreachable').wording`, the same row the verdict words itself from, so the
surfaces cannot drift from the status line.

*C4 answer (a real find):* `HealthLitView`'s `DISCONNECT_DEBOUNCE_MS` is NOT a rival authority —
it debounces the SSE channel only, and that view already consumes
`getAiState().connection.reachable`. The actual second authority was
**`StatusDeck.connDotClass()`**, re-deriving connection health from raw
`status.components.head/worker.state` off the retained snapshot — which reads `READY/READY`
forever after both processes die. That is why the dot stayed green. The store's authority won;
the lifecycle fields now answer only "what were the components doing when we last heard".

*C3 enumeration (honest, with named non-fixes):* five consumers changed (embedding-progress card,
Search-Quality count + per-feature dots, Models count, Runtime card readout + 5 controls, plus
`projectAvailability` and the indexing overlay). Deliberately left: Health (the diagnosis surface
— degrading it blanks the screen a user opens to find out what is wrong), `projectFact` (answers
"what is the value", not "is it live"; and the 595 §15.3 last-known/dimmed treatment already
covers it via `stability`), and the already-verdict-projecting readouts. Threshold: **no new
number** — age is already carried by `originContact`'s generated `STREAM_WATCHDOG_STALE_MS` (40 s,
>2× the 15 s heartbeat), so a second age check would have been the second authority A.3 rejects.
7 bite proofs; fixture debt settled in one pass across the three files the sibling had isolated.

**W3 (installer branding + small findings) — ACCEPTED; it disproved a claim this project had
recorded as fact.** Tempdoc 806 recorded that `installer-hooks.nsh` "cannot reference the version
at all" because the template includes hooks before `!define VERSION`. **False for defines**, and
W3 proved it empirically with `makensis` rather than by argument: NSIS expands a nested `${...}`
inside a define when the define is USED, not when it is written — probe output
`PROBE_RESULT=[JustSearch 0.2.0]` — and a second probe compiled the real hooks file in the
template's exact ordering with the MUI Welcome/Finish pages and found the branding baked into the
built `.exe` twice. So **R13-F1 is FIXED with a clean non-fork lever** (`MUI_WELCOMEPAGE_TEXT` /
`MUI_FINISHPAGE_TEXT` appending `${COPYRIGHT}` to the LOCALIZED MUI text, plus
`MUI_FINISHPAGE_TEXT_LARGE` — not cosmetic: the checkboxes cap that box at 40u and would clip the
line), pinned by a new `check-installer-branding` CI check, with 806's two false claims corrected
in place. Pixel verification on a CI build is still owed.

*Item 2 (the app calling its own shipped models degraded) — half fixed, half honestly blocked.*
Four of the six warnings were this repo's defect: `Fact.PRECISION` sat in the EMBEDDING/NER
presets justified as "informational — surfaced by `DevModeVariantProbe`", which is false at
source (that probe runs its own filename guess and never consults the resolver; **zero**
production readers of `cpuPrecision()`/`gpuPrecision()` — the runtime authority is
`VariantSelection.precision()` off the install contract). So the resolver guessed an unread fact
from a filename and logged the guess as a degraded capability — and guessed *wrong* (FP32 for a
variant the registry declares INT8, about a file a CUDA install never downloads). Removed from
the presets. The remaining two are a **release-asset** gap, not a code gap: the published
`embed-model_manifest.json` is 135 bytes with no `capabilities` block and overwrites the repo's
fully-authored copy on download; four other models ship no manifest at all. Logged, not forced —
and the new test asserts those two warnings STAY, so a future silencing is a visible change.

*Item 3 — premise corrected, then fixed.* Round 13's F3 said no TTL exists. It does:
`PendingAuthorizationStore.DEFAULT_TTL` = 5 min, and `expiresAt` has been on the domain records
since they were written — it was simply never put on a consumer surface. Now additive on the peek
payload and the advisory stream's `classExtras`, so the `expired-pending-approval-ceremony`
must-watch becomes performable.

*Item 4 — the real cause, and the user-visible half landed.* Every approve path already reported
network/HTTP failures; the one silent branch was `!decision.approved`, which **conflates a human
deny with a fail-closed outcome** (no host mounted, or a host torn down mid-ceremony) — both
returning an identical `{approved:false, allowAlways:false}`. That is exactly round 13's shape.
Now marked `failedClosed` and routed through the existing sticky error toast; the test pins the
toast (severity + text), not just the flag, and the bite proof turned that assertion red. An
explicit human deny stays silent by design.

**W4 (the headline tense error) — ACCEPTED, and it refuted both the brief's preferred shape and
one of its explicit instructions.**

*It refuted option (a) with a consumer table, not an opinion.* The brief argued for keeping
`kind: 'online'` and marking `stability: provisional`, on the premise that "consumers already
branch on `stability`". True of the SYSTEM `Stability`; **false of `AiStability`** — W4
enumerated every consumer (`aiEngineHeadline`, `aiEngineTone`, `aiEngineBody`, BrainSurface's
`statusConfig.online`, the store's status label/tone) and all of them key on `kind` ALONE; the
single reader of `aiEngine.stability` (`isGpuReadingProvisional`) explicitly excludes
`stale-poll`. So option (a) would have changed **zero pixels** and left the photographed sentence
intact. It chose (b) — but not a new kind: the gated arms return the **existing**
`{ kind: 'connecting', stability: { kind: 'provisional', cause: 'stale-poll' } }`, the same pair
this function already mints when it cannot see the engine, whose doc already reads "an
already-installed engine's runtime state can no longer be confirmed". Every exhaustive switch
already handles it. Renders amber "Connecting… / Checking AI status…" with a **blocked**
action instead of a green dot and a dead "Shut Down AI" click. `offline` was rejected for a
reason worth keeping: "AI Offline" is itself an unconfirmable present-tense claim, and its
primary action is a "Start AI" that cannot succeed.

*It declined a literal instruction, correctly.* The brief said the soft-off `background` arm must
still win over the new branch. Taken literally that leaves "AI engine is finishing document
understanding — chat is off." rendered against a dead backend — **the identical tense defect one
arm over**. W4 preserved what tempdoc 737 §15 decision 1 actually protects (soft-off beats
`online` whenever the observation is live, asserted) and gated `background` too, flagging the
divergence rather than making it silently. That is the correct reading of the rule over its
letter.

*Scope discipline in both directions:* all seven engine-axis arms gated uniformly — "cherry-picking
arms is the judgement that let the defect exist" — while the four install-history arms
(`installing`/`install_failed`/`paused`/`not_installed`) are deliberately left alone with a
boundary test, because disk and install history do not change when a process dies and blanking
them would hide the install CTA that is the only actionable thing in that state. Liveness is
threaded (a new required `snapshotLive` input fed from W1's predicate at the single call site),
never re-derived; making it *required* immediately caught a second construction site.

*The sentence is provably gone at three tiers:* the derivation (`kind` is never `online`), the one
presentation projection (`body !== 'Chat and summaries ready.'`, `tone !== 'success'`), and the
**rendered Brain card** — `renderSimplePanel()` driven through the real `computeAiEngineVerdict`
asserts the DOM contains neither "Online" nor "Chat and summaries ready.", the status dot is not
`success`, and the action is neither "Shut Down AI" nor available. Bite proof 1 (restoring the
exact pre-fix condition) fires all four of those assertions plus 9 more; bite proof 2 (forcing
the unconfirmed path always) fires all three anti-regression halves, proving the fix does not
blank a healthy UI vacuously; bite proof 3 isolates the store wiring from the pure function.
`check-ai-verdict-derivation` green — the fix stayed in the single authority and no consumer
re-derives. Suite: **4005 passed / 384 files**.

### Cross-cutting verification (2026-08-05)

**Forced full suite** `test --rerun-tasks --no-build-cache`, 186/186 executed: **one failure,
established as NOT causal.** `RuntimeReconcilerTest.specWriteDuringProcedure_deferredUntilEnd`
threw `AccessDeniedException` at `:256` under full-suite parallelism, then passed cleanly when
re-run alone (BUILD SUCCESSFUL, 9 s). The campaign diff contains **zero** `modules/app-services`
changes, so the test is outside the blast radius — a Windows temp-file lock under concurrent
execution. Logged as a flake with its evidence rather than dismissed or re-run until green.
ui-web: **4005 tests / 384 files** green (W4's final run); `check-ai-verdict-derivation` green.

**Live before/after proof of R13-F2 — the decisive check.** Dev stack served from this worktree,
Brain surface open, both Head and Worker killed, `/api/health` unreachable. This reproduces the
scenario the orchestrator photographed at the start of the day, and the two screenshots are a
direct comparison.

*Immediately after the kill* the surface still showed the stale claims — and that is the fix's
**designed detection window, not a failure**: liveness derives from `originContact`'s generated
`STREAM_WATCHDOG_STALE_MS` (40 s, >2× the 15 s SSE heartbeat), so for up to ~40 s the last
observation is still legitimately treated as live. Distinguishing "fix did not work" from "fix
has not triggered yet" required waiting past that window — the interrogate-results discipline
applied to an expected-looking result.

*After the window elapsed*, every single claim flipped to honest past tense:

| Before (this morning, and pre-fix) | After (post-fix, same scenario) |
|---|---|
| "**Building** semantic search" + animating amber bar | "Semantic search build — **last known**", bar greyed and static |
| (no caveat) | "The connection to the search backend was lost — these figures are the last observed values, **not live progress**." |
| "4,189 pending" | "4,189 pending **when last observed**" |
| "Search Quality Features **4/4 active**" | "Search Quality Features **4/4 when last observed**" |
| Runtime card asserting CUDA/VRAM/Tier | same values, **dimmed**, under "the values below are the last observed readings, **not live**" |
| Online / Indexing / Reload **clickable** | all three **disabled** |
| "embed queue: 4,089 · VDU queue: 0" | "… **(last observed)**" |
| **GREEN** CONN dot | **RED** CONN dot + "Reconnecting…" |

**Tier honesty (green-masked-destructive):** the one thing NOT proven live is the literal
sentence "Online / Chat and summaries ready." — reaching `engineState: Healthy` needs an
installed chat model, and this dev data dir has none (`ai_activate` → "Variant not installed:
cuda12"). That sentence is proven at W4's three test tiers instead, including a rendered-DOM
assertion driven through the real `computeAiEngineVerdict`, with a bite proof showing all four
assertions fire on the un-fixed code. Stated as a tier rather than claimed as a live result.

**Recorded for the next round (so it is not re-filed):** there is a bounded ~40 s window after a
backend dies during which the UI still presents the last observation as current. That is the
heartbeat-derived contact window, deliberate and defensible — but a round that photographs
within it will see the pre-fix appearance. The must-watch and any future finding should measure
**after** the contact window, and the window's existence is the honest cost of not adding a
second, faster staleness authority.

---

## Part E — Review pass and its fixes (2026-08-05)

A `/review-changes` pass over `92aeed25` produced a claims list with evidence pointers, and an
**independent refute-first subagent** was given the default stance that every claim was wrong
until its evidence held. It earned its place twice: it found the campaign's central fix
bypassable, and it refuted a finding the orchestrator had raised.

### E.1 The fix was silently disabled in SIX states (found by review, now fixed)

`isSnapshotLive` derived from the verdict **kind**. But `computeStability`
(`verdict.ts:100-126`) returns from six higher-precedence branches *before* it can reach
`channel-stale`, and every one reads a **retained snapshot** field: `indexState ===
'UNAVAILABLE'` (`:103`), `migrationState SWITCHING` (`:107`) / `MIGRATING` (`:108`), `building ≠
active` (`:110`), `servingSearch ≠ servingIngest` (`:115`), `catchingUp` (`:120`). Because
`statusSig` is retained on a failed poll, any of them wins **forever** once the backend dies —
`snapshotLive` stayed true and every claim in Part D's before/after table reverted to the
"Before" column. The ordinary trigger is not exotic: **the Worker dies first** (the backend
writes `UNAVAILABLE`), then the Head; a laptop resume (`catchingUp`) or a mid-upgrade reindex
does the same.

What makes this the review's most valuable find is what was defending it: **a green 4005-test
suite, a doc comment asserting the two non-live kinds were "exactly" what the verdict mints when
contact is lost, and a test blessing three of the six states as live** ("work in flight, backend
answering"). The suite passed because the tests asked the wrong question — `audit-without-test`
inverted: a test that passes for a wrong reason is worse than no test, because it also defends
the defect.

**Fix:** liveness is now a **contact fact**, not a verdict-kind classification —
`isSnapshotLive(connection)` returns `connection.reachable`, from `computeReachability()`, which
already owns both edges (the never-contacted boot grace and the 40 s `isOriginReachable` aging)
and is the same `reachableViaContact` the verdict itself consumes. No new number, no second
authority — and *more* faithful to A.3's one-authority rule than what shipped, because a verdict
kind is a classification six unrelated branches can pre-empt. Both defending artefacts were
corrected in the same change, and the re-pinned test now asserts those states are live **iff
contact is fresh**, which is what its own justification always meant.

**Live proof of the specific case** (dev stack, this worktree): Worker killed first so
`indexState: UNAVAILABLE` is the retained value, then the Head, then waited past the contact
window. Result: "Search Quality Features **4/4 when last observed**", Runtime "the values below
are the **last observed readings, not live**" with values dimmed, Online/Indexing/Reload
**disabled**, "embed queue: 0 · VDU queue: 0 **(last observed)**", "Models **4 when last
observed**", **red CONN dot**. Against the pre-fix derivation this state rendered fully live,
indefinitely.

### E.2 Two regressions the campaign introduced, neither covered by any claim

**Queue-and-replay.** Converting the Runtime controls from `?disabled` to
`.availability=unavailableBecause(reason, transient=true)` changed them from inert to **queued**:
`Control.activate` holds a transient-unavailable intent and `resolveQueued` fires it the moment
the control becomes operable. Clicking Online / Indexing / Reload during an outage would have
fired a burst of conflicting runtime mutations on reconnect — the opposite of the "disabled" this
document claimed. Fixed by making the liveness-derived unavailability **non-transient** in both
`BrainSurface`'s gate and `availability.ts`'s arm, on the reasoning that `transient` means a
*bounded* wait (boot, model load) while not-live is by construction ≥40 s with no contact on any
channel. The boot window is untouched: `projectAvailability` answers `phase === 'connecting'` →
`inference.starting` (transient) *before* it consults liveness, so the "queued, runs when ready"
promise still covers the case it was designed for.

**Visual-only state.** The docked rungs' active tier was CSS-only (`?data-pressed`), while
`jf-control` has no `aria-pressed` passthrough — and this project documents its own workaround
200 lines away (`renderPinToggle`: the accessible LABEL carries the state). Now followed via
`rungLabel(base, active)`; the old test asserted the attribute and so passed for the wrong reason.

### E.3 A finding the orchestrator raised and the refuter REFUTED

The orchestrator claimed Part D overstated W3's precision work ("zero production readers of
`cpuPrecision()`/`gpuPrecision()`" when four readers exist). **That correction was wrong and was
not applied.** It is a method-name collision across two types:
`ModelCapabilities.cpuPrecision()` returns `ModelPrecision`;
`ModelManifest.Capabilities.cpuPrecision()` returns `String`. All four readers are of the
*manifest* accessor. Zero production readers of the `ModelCapabilities` accessor — exactly as
`CapabilityRequirements.java:32-33` states. Applying the "correction" would have inserted a false
claim into an accurate record. Recorded here because the near-miss is the lesson: the reviewer's
own finding needed refuting as much as the implementer's.

### E.4 Known residual, evidenced and deliberately not fixed

The **verdict itself** is still pinned by retained fields, so the status *line* can project a
retained cause after contact is lost (probed: retained `UNAVAILABLE` + 41 s aged contact ⇒
`{"kind":"transitioning","reasons":["worker-restart"]}`, `statusLabel: "Restarting…"`, while
`snapshotLive: false` and the dot is red; a later orchestrator run of the same shape showed
"Reconnecting…", so the wording varies with what the final snapshot held). Everything Part D
claimed — last-observed wording, blocked controls, red dot, AI-engine gating — is now honest;
this is the status wording alone. The fix is a precedence change inside `computeStability` (lost
contact should dominate anything derived from a retained snapshot), which moves the status bar,
the announcer, the Health surface and the N1 completion-toast edge — a change to the one verdict
authority that should not be made unasked immediately before a qualification round. **Recorded so
that if round 14 files it, the decision is on the record rather than rediscovered.**

Also recorded from the review, not fixed: `expiresAt` now ships on the wire with **no ui-web
consumer** (the expired-pending must-watch is API-performable only — a round should not expect a
countdown in the UI); the floor rung is clickable mid-stream and swaps away a streaming answer
(recoverable, pre-existing in kind); and no ui-shot covers the docked composer at a small
viewport, which is where round 8's F5 layout defect lived.

### E.5 Verification

`isSnapshotLive` bite proof restored the old derivation and failed **7 tests** — all six
precedence states plus the never-contacted case. Each regression fix carries its own bite proof
(the queue test observes the "queued" toast reappear; the a11y test loses "(current mode)").
Suite **4015 tests / 384 files** green (was 4005); `check-ai-verdict-derivation`, the ui-web,
shell-v0, views and kernel gate sets all green, with the two known-red-on-main entries unchanged
and confined to their own files. Two corrections to the review's own brief, from the fixer: there
are **six** precedence branches, not five (`catchingUp` was missed), and the pre-existing test
`a never-contacted origin past the grace window is not live` **asserted the opposite of its own
name** — now matching it. One further production bug surfaced by the contact rule and fixed:
`StatusDeck.connDot()` painted a red "disconnected" dot when there was no snapshot at all
(pre-first-poll), now answering "no snapshot" before it consults liveness.
