---
title: "801 — Round 8 findings and the path from qualifiable to released"
type: tempdocs
status: "DESIGNED 2026-07-31, no implementation licensed. Opened because tempdoc 798 closed on round 8's QUALIFIABLE verdict. Design settled in §D against traced mechanisms (four read-only investigations, file:line evidence). Two conclusions reverse the sketch's own starting assumptions: F2/F9 is a backend defect (the pre-start byte authority cannot see partial downloads) and F4 must NOT be fixed by widening the measurement gate. Supersedes tempdoc 798 §R1b."
created: 2026-07-31
updated: 2026-07-31 (design pass)
category: release / presentation-truthfulness / validation-harness
related: [798, 734, 750, 772, 799, 742]
---

# 801 — Round 8 findings and the path from qualifiable to released

Sandbox round 8 validated a candidate cut from merged `main` and returned **QUALIFIABLE — no
blocking defect**, closing tempdoc 798. It also produced eight findings, a ninth was found
host-side reviewing its evidence, and its retrospective produced nine harness items. None of that
has a home, and 798 is closed.

This document is a **sketch**, not a design. It records what exists and what the open questions
are. The theorization it grows from is tempdoc 798 §T; the round's full record is tempdoc 734.

## Scope

Three strands, deliberately kept separate because they have different owners, different urgency,
and different evidence standards.

### Strand A — the nine product findings

All non-blocking. Full detail in `docs/tempdocs/734-0.2.0-sandbox-convergence.md`; severity and
proposed regression homes are recorded there per finding.

| # | Sev | Summary |
|---|---|---|
| F1 | MED | Disk-encryption card reads "Unknown — needs admin" where nothing is encryptable, in a session that already held admin |
| F2 | MED | Install-AI consent dialog states the **full** total on resume; the progress screen seconds later states the correct remaining total |
| F9 | MED | The paused-install surface reads "Not Installed" with ~1.2 GB retained on disk, contradicting the confirm dialog's explicit promise that the bytes are kept |
| F3 | MED | Tasks panel "QUEUED" and status bar "queue" show different numbers in the same frame; both are true of their own field |
| F4 | MED | Toasts occlude the header control row on surfaces the shipped assertion does not capture, and do not auto-dismiss |
| F5 | MED | Clearing results with the document preview open clips the composer below the viewport; recovers only by navigating away |
| F6 | MED | Extraction rung with a JSON schema attached returns Document Q&A prose under a "DOCUMENT Q&A" header |
| F7 | MED | Skin gallery swatches all render the *active* skin's accent — **reproduces a round-6 finding** |
| F8 | LOW | Simple Brain panel exposes span/trace-ID affordances; proved not tracing-gated |

**F2 and F9 are one defect** — the pre-download surfaces do not read the resume state the backend
already holds — and should be fixed as one change, not two.

**F4's fix already shipped and did not hold.** This is the T1 case: the assertion
(`.toast` `mustNotOverlapSelector: ".header"`) is registered against a single ui-shot step while
the overlay it guards is docked globally. Re-fixing at the same anchor would reproduce the same
silence on the next unexercised surface. Open question: whether the check should be re-anchored to
the overlay host's own contract, evaluated wherever it mounts.

**F7 is the second consecutive round to report it**, so it is not a triage question any more.

Two findings are open at the diagnosis stage, not the fix stage. **F6's root cause was not
resolvable in-sandbox** — whether `/api/chat/dispatch` received the wrong `shapeId` or the renderer
reused the Q&A block cannot be told apart from trace spans, which record only method, target and
status. **F3** needs a decision about which quantity the panel should show before it needs a fix.

### Strand B — the qualifying set is incomplete

Round 8 answers *"is this build sound on a clean machine"*. It does not answer *"is the qualifying
set complete"*, and it is not. Detail and reasoning in 798 §T6; the items are:

1. **No `upgrade-from-release` round has run for 0.2.0.** The harness's own round-mode policy
   requires at least one in a release's qualifying set, on the recorded grounds that its strongest
   defect reproduction ever came from a non-fresh arrival state. `v0.1.0` exists and is
   installable, so the round is possible. The "no current users" fact does not dispose of this —
   the round finds state-migration defects, which do not require a user to exist.
2. **The signed build is a different artifact.** The certificate is in identity validation and the
   validated candidate is unsigned by design. Signing changes the binary and the Windows trust
   path; equivalence should be demonstrated, not assumed.
3. **There is no auto-updater.** `tauri.conf.json` declares no updater configuration. Open
   decision, and one that is cheapest before a first real release: an un-updatable build in the
   field must be reached by some other channel forever. This should be decided explicitly rather
   than by omission.

### Strand C — harness and process items

Nine items in round 8's retrospective (734 §B8). Three are structural rather than housekeeping:

- **B8.1 — the convergence tempdoc went stale and lost a whole round.** Proposed guard: refuse to
  stage a convergence tempdoc whose latest recorded round is older than the charter's round
  number. See 798 §T5 for why the instruction-to-maintain form cannot work here.
- **The charter's livelock watch-string would have manufactured a false HIGH** (798 §T4). It
  encoded a symptom signature where it needed a discriminator. Candidate remedy: every charter
  watch item carries a stated *"what does this look like if the build is healthy"* answer.
- **B8.9 — the non-elevated requirement is unsatisfiable by the party it addresses.** Windows
  Sandbox's account is an administrator; no in-sandbox action can comply. Either address it to the
  host launcher, or state plainly that it is unachievable and prescribe what to record instead.
  Honest scoping: the "no admin needed" *claim* is already asserted host-side against the built
  installer's manifest, and the product's data paths are per-user, so the masked-defect surface is
  narrower than the instruction implies — but it is not empty, and it is currently unmeasured.

The remaining six (staging-gaps generation, absolute-coordinate clicks for the native folder
picker, coordinate drift as text is typed, evidence-review token cost, the install-state warning
firing on the round's own install, and shape-id span attribution) are recorded in 734 §B8 with
proposed fixes; they are friction, not risk.

---

# D — Design

## D0. What the nine findings actually share

They are not nine bugs. Eight of the nine are one shape, and the shape is sharper than
"presentation lies" — tempdoc 798 §R1b called it *prose authored next to the data that would
have made it true*, and round 8 shows that framing is too weak, because **most of these surfaces
do not author. They derive — privately.**

The install-resume case is the crispest instance. The progress screen renders "9.08 GB remaining"
and "Resumed from your earlier download" **correctly**. It derives. But it derives *locally*, so
its correctness is invisible to the two surfaces the user passes through first: the idle Brain
card still says "Not Installed" with 1.2 GB on disk, and the consent dialog still quotes the full
10.14 GB. Three surfaces, one truth, computed once — in the wrong place, at the wrong time, by the
one component that needed it least.

Once every mechanism was traced, the eight collapse further than expected. Stated as sharply as the
evidence supports:

> **A claim must be computed from the thing it claims about.**

Every one of these surfaces computes. None of them hardcodes, except in the degenerate case. What
each does is compute from *something adjacent to* its subject — ambient global state, a stale
mirror, a sibling call with a different argument, a guess at another component's size:

| Finding | The claim | What it actually read instead of its subject |
|---|---|---|
| F1 | "elevation would resolve this" | nothing — `qualityKnown` is hardcoded `false` and **no code anywhere checks elevation** |
| F2/F9 | "this downloads 10.14 GB" | a planner that tests for a *final* file at exact size, so a 1.2 GB `.partial` reads as absent |
| F3 | "4308 queued" | a two-hop in-memory mirror of the job table, repaired only when a frame arrives |
| F6 | mode chip "Extraction" | `resolveShape(…, 'none', affordance)` — the same resolver the send path calls with the **live** selection kind |
| F7 | this card's skin preview | the document-scoped `--accent-primary`, i.e. whichever skin is currently applied |
| F8 | "hidden when tracing is off" | a doc comment; the server's `tracesAvailable` is a **file-exists** check on `traces.ndjson` |
| F4 | "the overlay clears the header" | a constant `3rem`, guessing a height that varies 16/12/20px by density |

This supersedes tempdoc 798 §R1b's "derive, don't author", which was aimed one step short of the
target. Authoring is merely the degenerate case of not reading your subject. It also **withdraws
798 §T2's second clause**: F3 is not a label collision. Both figures describe the same `jobs`
table — the status bar via a live `COUNT(*)` over `PENDING`+`PROCESSING`, the panel via a per-row
SSE projection through a head-side cache — so the words do not disagree; the two derivations do.
The enrichment-backlog hypothesis in the round's own write-up is refuted: nothing in the tasks path
reads `status.embedding`, and `embed:` is a separate token on the same status-bar item.

The corrective follows directly and is uniform: **for each claim, identify its subject and read
it.** Not "add a projection", not "add a test" — those follow, but the defect is the missing edge
between a claim and the thing it describes.

## D1. Why the last round of fixes did not hold — and what that changes

Two fixes from tempdoc 798 shipped and the defect reproduced anyway (F1 disk-encryption wording,
F4 toast occlusion). Both are scope errors, but of two different kinds, and the difference is the
whole design input.

**A correction this document owes, because it was written here first and was wrong.** An earlier
draft of this section asserted both fixes "shipped and did not hold". For F1 that framing is not
what the evidence supports, and getting it wrong is itself an instance of 798 §T4 — an expectation
stated confidently enough to be read as a finding. What is actually established:

- The PKEY-4 fix **is** on `main` and **was** in the round-8 candidate. `06c90349` (PR #343)
  merged 2026-07-31 01:35:49 +0200 and is an ancestor of `9c112600`, the head of the build that
  produced the validated installer 25 minutes later. The distinct `NOT_APPLICABLE` state exists
  (`AtRestProtection.java:36`, `DiskEncryptionProbe.java:128`) and the frontend honours it
  (`atRestCard.ts:98`). Nothing downstream remaps it.
- **It was not the applicable branch.** The rendered `Source shell-property · LOW` is producible
  only by the switch's `default` arm (`DiskEncryptionProbe.java:130`) — every short-circuit path
  returns source `none` with confidence `UNKNOWN`, and the frontend hides the Source row entirely
  when source is `none` (`atRestCard.ts:138`). So the probe ran, exited 0, and returned a parseable
  integer outside `{1,2,4,5,6}`. On a machine with no BitLocker feature, `ExtendedProperty` returns
  `$null` and PowerShell's `[int]$null` is `0` — which parses cleanly and falls to `default`.
- **The half of the diagnosis that produces the string was never implemented.**
  `StatusLifecycleHandler.java:731` still hardcodes `qualityKnown=false` unconditionally, and
  **no code anywhere in the repo checks whether the process holds admin** — no `isElevated`, no
  token check, only prose in comments. So `atRestCard.ts:99-103` emits "Unknown — needs admin" to
  an already-elevated user in every `UNKNOWN` case, by construction. That alone reproduces the
  finding, with or without the PKEY work.

So F1 is not a fix that failed. It is **a fix scoped to one cause of a multi-cause symptom**, where
the cause that was fixed is the one the environment does not take. And the test suite cannot
notice: every test that reaches `NOT_APPLICABLE` hands the literal `4` to the code under test
(`DiskEncryptionProbeTest.java:65,91`), while `undocumentedPkeyValuesStayUnknown:98` asserts
`0 → UNKNOWN` as *correct behaviour*. A fully green suite is compatible with the reproduction, and
the seam the tempdoc named (`readMechanism`) sits **above** `readWindowsShellProperty`, replacing
wholesale the exact code whose behaviour is in question. This is `unreachable-seed-green` in its
purest form: the test supplies the input production does not produce.

F4 is the other kind — the fix was right, and **the assertion was anchored to the observation
rather than to the mechanism** (798 §T1). The toast fix adjusted the dock to clear the chat
surface's header and registered
`.toast` `mustNotOverlapSelector: ".header"` against the `chat-occlusion` ui-shot step. The check
is real, correct, and passing. It simply evaluates one surface out of many, while the component it
guards is docked globally and floats above all of them. Round 8 reproduced the defect on Library
and after an MCP approval — surfaces that step does not capture.

This conforms to an existing frame rather than introducing a new one. Tempdoc 799 §G.1 states *a
control that is not reachable is a claim, not a control*, with the corollary that *a reachable
check that exits 0 on violations is still a claim*. Round 8 supplies a third member of the same
family:

> A green control means what a reader takes it to mean only if it is **reachable**, **biting**,
> and **scoped to the mechanism it claims to guard**. Failing the third looks exactly like
> passing: every existing test is green, and the gap is precisely the set of instances nobody
> captured.

Of the three, the scope failure is the hardest to notice, because the other two can be detected by
asking whether the check runs and whether it can fail. Scope can only be detected by comparing the
check's domain against the mechanism's domain — a question nobody asks of a passing gate.

**F1 shows the same error one layer earlier, in the fix rather than the check.** A two-part
diagnosis was implemented in one part, the implemented part addressed a branch the target
environment does not take, and the tests seeded that branch by hand. Assertion scope and fix scope
are the same mistake at different altitudes: *the thing you built covers the instance you looked
at, not the mechanism that produced it.* Stating them as one class rather than two is what makes
the corrective uniform — for every one of these findings, ask what the mechanism is and whether the
fix and its check both reach all of it.

**A harness gap this exposed, cheap to close.** Settling whether the fix was even in the candidate
required matching a CI run's head SHA against a merge commit, because round 8 recorded no commit
hash — its own note says no PR/branch/CI-run/artifact URL was reachable from inside the sandbox.
The build that produces a candidate knows its commit; staging should write it into the share so a
round's evidence can always answer "what was in this build" without host-side archaeology.

**Concrete consequence for F4's routing.** Round 8 proposed a `sandbox-must-watch` entry plus a
ui-shot assertion. The must-watch half should be rejected. That register is explicitly for defects
CI *cannot* see — Windows trust prompts, clean-environment timing — and its existing five entries
all carry `"origin": "…not CI-gateable"`. Toast geometry is eminently gateable; it was gated, at
the wrong scope. Filing it as must-watch would convert a scoping bug into a permanent standing
excuse, and would be the predictable evasion here: it looks like added rigour while removing the
obligation to fix the anchor.

## D2. F2 + F9 — the pre-start byte authority is blind, and that is a backend defect

Investigation settles the question 801 opened with ("is this a frontend fix or a backend one"):
**it is a backend fix, and the frontend half alone cannot work, because the number does not exist
to read.**

The pre-start byte authority is `InstallPlanner.plan(...)`. It decides what still needs
downloading by testing `Files.isRegularFile(finalTarget)` plus an exact size match
(`InstallPlanner.java:242-254`). A 1.2 GB `<target>.partial` fails that test, so the planner adds
the variant's **full** size. The token `.partial` does not appear in `InstallPlanner.java`,
`AiPreflightService.java`, or any non-test Java outside the install-download package. The only
code that reads a partial's size is `DownloadResume.partialSize` (`DownloadResume.java:105-111`),
called from inside the fetch loop (`ResumableFetch.java:105-108`) — after the run has started, per
file, for the loop's own purposes.

This is D0's thesis in its clearest form. The system computes the right answer exactly once, in
the component that needs it least, and no shared projection carries it anywhere.

Three distinct defects sit behind the one symptom, and they need different fixes:

1. **The planner is partial-blind.** No endpoint, DTO field, or service method surfaces
   `.partial` bytes pre-start. This is the real gap and the one that makes the consent dialog's
   "10.14 GB" unfixable from the frontend.
2. **The completed-file portion IS already visible, and the frontend does not look.** The planner
   correctly excludes fully-downloaded files, so a *fresh* `plan-preview` after a cancel would
   already return the reduced total. But `planPreview` is fetched only in `refreshAll()`, called
   from `connectedCallback`, the manual refresh button, and one op-success handler
   (`BrainSurface.ts:682,1157,1743`); `startInstall()` merely opens the dialog and never re-fetches.
   The dialog shows a mount-time number the backend has already superseded.
3. **The paused state is emitted and dropped.** The backend writes `state: "cancelled"`
   (`AiInstallService.java:1178`) and **no frontend code consumes it** — the only literals the FE
   tests are `'running'` and `'failed'`. Everything else falls through `computeAiEngineVerdict` to
   `not_installed` with `stability: settled` (`aiVerdict.ts:195-212`). That is the worst available
   rendering: not merely "we don't know", but a *confident* assertion that nothing is there, over
   1.2 GB that is.

**Where the authority belongs.** `AiInstallStatus` is explicitly session-ephemeral
(`AiInstallStatus.java:12-14`), so `state: "cancelled"` does not survive a restart — after one, a
user sees plain `idle`. The durable truth is on disk: the `.partial` plus its resume record, which
`DownloadResume` already reads well enough that a shipped test asserts resume works *from disk
state alone after a process restart*. So the paused state must be **derived from disk by the
planner**, not carried by the ephemeral status object. Making the planner partial-aware fixes all
three surfaces by derivation and survives restart; patching the status object would fix the
session and lie after the next launch.

`AiEngineKind` (`aiVerdict.ts:52-64`) has no member that can hold this, used or unused, so the
frontend needs a real state — not a relabelling of `not_installed`.

## D3. Two tests that pin the defect as intended behaviour

This is not an aside; it is the reason both fix rounds could ship green and still be wrong, and it
generalises beyond these two findings.

- `DiskEncryptionProbeTest.undocumentedPkeyValuesStayUnknown:98` asserts `0 → UNKNOWN` as correct.
  If Windows Sandbox emits `0` — the most likely value, since `[int]$null` is `0` — this test pins
  the reproduction as intended.
- `BrainSurface.installConsent.test.ts:70-73` asserts `'10.14 GB'` from a fixture, with the comment
  *"the SAME number the progress screen shows"*. Round 8 observed the progress screen showing
  9.08 GB for that same state. The test's premise is false and the test is green.

Both were written alongside the fixes they cover, and both share one habit: **the oracle is the
implementation's own assumption, restated as a fixture.** A test built that way cannot discover
that the assumption is wrong about the world — it can only confirm the code matches what its
author already believed. That is a strictly weaker check than it appears, and it appears strong,
because the assertion is specific and the suite is green.

The corrective is not "more tests". It is that a test covering a truthfulness claim needs an
oracle anchored **outside** the component: the progress screen's actual denominator for the consent
total, the real environment's probe value for the encryption state. Where an external oracle is
genuinely unavailable, the honest move is to say so rather than to seed the input and assert the
output. This is the measurement-layer sibling of `unreachable-seed-green`, and 799 §G.2 already
names the general form — *an instrument needs stronger verification than the system it measures* —
so this conforms to an existing principle rather than adding one.

## D4. F4 — do not extend the measurement gate; fix the constant it fails to measure

801 opened asking whether the toast assertion should be re-anchored to "wherever the component
mounts". Investigation says **no**, and supplies a better answer that is also cheaper.

**Re-anchoring is expensive and fights a deliberate design choice.** A cross-step constraint is not
expressible today: the register is keyed on `uiShotStep`, the enforcer loops steps and evaluates
each constraint against that step's capture alone, and the schema is closed
(`additionalProperties: false`, three constraint kinds). Worse, a missing selector is a hard
ERROR by design — an anti-false-pass choice — and `.toast` is *absent* from every ordinary step,
because `AdvisoryToastHost.render()` returns `nothing` with no visible toasts. So asserting it
everywhere would require a present-or-skip semantics that contradicts the gate's central safeguard,
plus forcing a toast burst into 59 steps, 16 of which mount no shell and can never host one.

**The real defect is one line, and it is D0's thesis in geometric form.** The dock is a constant
(`OverlayHost.ts:44-63`):

```
.top-right { top: calc(2.5rem + 3rem + 0.5rem); … }
```

40px topbar + **48px assumed header** + 8px gap. There is no JS in the file; nothing reads any
header's geometry. That `3rem` is a *guess at another component's height* — and the height it
guesses is not fixed: `.header` is the shared SurfaceLayout region contract rendered by ~20
components, and its padding is `--density-header-pad-y`, which is **16px / 12px / 20px** across the
comfort, compact and rich density tokens. The reserved band does not track the thing it reserves
against, on any surface, at any density other than the one it was measured at.

So the overlay **authors** a number describing the header instead of **deriving** it. The per-step
assertion did not fail to generalise because assertions are per-step; it failed because the value
under it is a hardcoded guess that happens to be right on the one surface and density it was
measured at.

**Design: fix the derivation, keep the single assertion.** Express the dock in terms of the same
tokens that determine the header's height, so the offset tracks density and surface by
construction. Once there is one derived value rather than a guess, a single measured assertion on
one step is sufficient — not because one surface is representative, but because there is only one
code path left to regress. That converts a coverage problem into a correctness problem, which is
the cheaper of the two by a wide margin.

One asymmetry makes this tractable without measuring anything at runtime: **over-reserving is
harmless — it shows as a gap — while under-reserving is the defect.** The header's height is
`2 × --density-header-pad-y` plus its content, and content varies because some headers carry a
subtitle line and some do not (`surfaceLayout.ts:62-76`). A band declared by the component that
owns the header, sized for the taller case, is derived rather than guessed and needs no observer,
no JS, and no per-surface knowledge. The design direction is that the header's owner **publishes**
its reserved band and the overlay consumes it; exactly how that token is expressed is an
implementation choice, not a design one.

**This extends existing structure rather than adding any.** The repo already asserts this class at
the choke point: `check-layout-purity` plus `governance/overlay-positioning-classes.v1.json` walks
all of `modules/ui-web/src` and fails any `position: fixed` outside `OverlayHost.ts` or a
classified exemption, making *"the OverlayHost owns placement"* true by construction. The gap is
precise and worth stating exactly: **that gate guarantees the overlay owns placement, and says
nothing about whether the value it places with is derived or guessed.** Closing it is a
strengthening of a control that already exists, not a new mechanism.

Two facts to carry into implementation rather than discover later. The proportion gate is
**local-only by declared intent** (ADR-0026, "a runnable gate, not CI-wired") — legitimate under
799 §G.1's "declared tiers rather than accidents", but it means nothing runs it unless an agent
does, so its green is an agent's green, not CI's. And the honest residual the baseline already
records stays true: three components share the `.top-right` slot, each unaware of the others'
height, so bounding one component bounds its own growth and not the slot's.

## D5. Why F7 keeps coming back — the coverage gate cannot see its surface

F7 (skin gallery swatches) is the second consecutive round to report it. The reason is structural,
not triage.

`check-ui-step-coverage` derives its required instance set by scraping `CorePlugin.ts` for surfaces
declaring `placement: 'RAIL'`, and requires each to be covered-or-exempt. **Eight shipped surfaces
are DEEPLINK and therefore outside its scope by construction**, among them
`core.presentation-gallery-surface` and `core.presentation-editor-surface` — precisely where F7
lives — plus memory, browse, logs, activity, governance and api-explorer. None has a ui-shot step.

So the surface carrying a twice-reported defect is invisible to the only control that could have
demanded a witness for it. This is the same scope error as D1, one level up again: the *coverage
register* is scoped to the placement that motivated it rather than to the mechanism (a shipped,
user-reachable surface). RAIL versus DEEPLINK describes how a user navigates to a surface, not
whether its truthfulness matters — and round 8 reached all eight through the command palette.

The design question is whether coverage should key on **reachability** rather than placement.
Widening it enumerates eight new surfaces needing steps, which is real work and should be sized
before it is committed to; the alternative is to declare the eight `exempt` with honest reasons,
which at least converts an invisible gap into a visible one. What should *not* happen is fixing
F7's swatch and leaving its surface unwitnessed, because that is the configuration that already
produced two rounds of the same finding.

Related drift worth recording, found while establishing the above and not investigated further:
`check-ui-step-coverage`'s own header comment claims it is wired as a `ci.yml` step, and it is not
present in any workflow. Same for `check-layout-purity`. That is 799 §C.1's class exactly — an
assertion channel whose documentation asserts an evaluator it does not have.

## D6. F3, F6, F7, F8 — mechanisms, and what each subject actually is

Three of these are the same one-line-scope defect at the read site; the fourth is different in kind
and worth separating.

**F3 — Tasks panel vs status bar.** Both describe the worker's `jobs` table. The status bar reads
a live `COUNT(*) WHERE state IN ('PENDING','PROCESSING')` via REST poll
(`SqliteJobQueue.java:1063-1071` → `WorkerStatusMapper.java:75` → `StatusDeck.ts:437-443`). The
panel counts rows in a per-row SSE projection fed through a head-resident in-memory mirror
(`RemoteIndexingJobsBridge.cached`), which is repaired only by a fresh SNAPSHOT frame and otherwise
maintained by applying deltas. Two transports of one table, with reconciliation on only one of
them. The code already names this defect class in a comment describing a *previously fixed*
instance of it (`RemoteIndexingJobsBridge.java:214-222` — "phantom PENDING rows the live
`queueDepth()` reported as 0 — the count-vs-list drift"), which makes this a recurrence, not a
discovery. **The subject is the job table; the fix direction is that a projection of a counted
quantity must be reconcilable against the count**, not merely repaired when a frame happens to
arrive.

Which mirror held the phantom 4308 — the head cache or the FE substrate — is not settleable from
source, and the discriminating observation is cheap: at one instant, compare the SSE snapshot's
first-frame `items` against `/api/status` `worker.core.pendingJobs`.

**F6 — Extraction dispatches as RAG-ask.** The backend is not at fault: `core.extract` is
registered, and given `shapeId=core.extract`, `/api/chat/dispatch` and `/api/chat/extract` are the
same code path. The frontend never sends it. `resolveShape` consults the affordance **only** when
the selection kind is `'none'` (`compose.ts:139-155`); every other kind returns `core.rag-ask`,
discarding both the extract mode and the schema — and `buildRequestBody`'s `rag-ask` branch omits
the schema entirely. Meanwhile the mode chip calls the *same resolver* with `'none'` hardcoded
(`UnifiedChatView.ts:2089`), so it reports what the user would have got with no selection. The
"DOCUMENT Q&A" header is therefore honest — the message is stamped with the shape actually
dispatched — and is positive evidence that `core.rag-ask` was sent, not a renderer reusing a block.

This is the sharpest instance of the whole class: **one function, two call sites, different
arguments, and the label is the one that lies.** The subject of the chip's claim is *the request
that will be sent*, and it reads a hypothetical instead. Note the underlying product question is
separate and needs an owner call: whether a selection *should* override an explicitly chosen
Extraction mode. The truthfulness defect exists either way — if selection wins, the chip must say
so before the user sends.

**F7 — skin swatches.** The swatch is a bare `<div class="swatch">` painted by a static rule
`background: var(--accent-primary)` (`PresentationGallerySurface.ts:158-162,225`), and
`--accent-primary` aliases the document-scoped `--accent-tint` that applying a skin overwrites
globally. The per-card value is already in render scope: `decl.theme.tokens['accent-tint']`, with
literal values per built-in. One caveat for implementation: `theme` is optional and three of the
nine built-ins declare none, so the fix needs a defined fallback for "the default accent" rather
than rendering nothing.

**F8 — is a different class and the more interesting one.** The trace explorer and transition
timeline are rendered unconditionally inside `renderSimplePanel()` and gated only on their data
being non-empty. The doc comment claims they are "hidden when tracing is off
(HEAD_TRACING_LEVEL=none, the default — endpoint reports tracesAvailable=false)". The endpoint
implements no such thing: `tracesAvailable` is `false` only when `traces.ndjson` is not a regular
file (`DiagnosticsController.java:108-153`). Once that file exists from any prior run, the panel
renders forever regardless of the current tracing level — exactly what round 8 measured.

So the "gate" here exists **only in a comment**. That is 799 §C.1's class in miniature: a claim
with no mechanism behind it, and one that actively misleads the next reader into believing the
behaviour is already controlled. Two consequences worth carrying: the comment must go or become
true, and — separately — the `surface-altitude` governance cannot see any of this, because
`core.brain-surface` declares empty `resources` and `diagnosticChannels` (⇒ altitude `PRODUCT`)
while the span data is fetched out-of-band by raw `fetch` to `/api/diagnostics/traces`. The gate
derives altitude from *declared consumes*, so a surface that reaches around its own declaration is
invisible to it. That is R1's scope error again, in the governance plane.

## D8. F6's "product decision" was already made, and the resolver contradicts it

§D6 called the selection-vs-mode question an open product call needing an owner. On inspection it
is not open, and recording why is more useful than recording the answer.

**The project decided this and wrote the decision down.** `UnifiedChatView.ts:806-809`, carrying
decision B14 verbatim:

> the selection carries into sends as docIds; it no longer auto-flips the standing tier out from
> under the user.

That *is* the answer: a selection contributes **scope**, not **operation**. But `resolveShape`
(`compose.ts:139-155`) does precisely what B14 forbids — every selection kind other than `'none'`
returns `core.rag-ask`, flipping the tier out from under the user. Its own comment explains why it
looks the way it does: the affordance hint was designed to disambiguate *"the ask-without-selection
case"* (tempdoc 526 §14.5 T5), written when selection genuinely did determine the shape. B14 (a
later tempdoc) changed the policy; this function was never revisited. Two decisions, one
unrevisited resolver.

**The two inputs are orthogonal, so the framing "which wins" was itself the error.** Selection
answers *over what*; affordance answers *do what*. They only compete because `resolveShape`
resolves both from one switch into one shape id. And the cost of the current collapse is
one-directional: `buildRequestBody`'s `core.rag-ask` branch has no schema field at all
(`unifiedChatRequest.ts:78-85`), so a user's attached JSON schema is silently discarded. There is
no reading under which *"user attached a 2-property schema"* expresses a preference for prose.

**One part of the current behaviour is right and must survive the fix.** When the affordance is
`'none'` — nothing explicitly chosen — a selection upgrading free-chat to `core.rag-ask` is good
behaviour, not a bug. So the corrective is *not* "affordance always wins". It is:

> An **explicitly chosen** operation is never overridden by ambient selection state; an
> **unspecified** operation may be resolved by it.

That is one structural change — hoist the explicit-affordance check above the selection switch —
and it preserves every useful row in the existing table.

**The genuinely non-obvious part, and the reason this cannot be a one-line change.**
`ExtractShape.definition()` declares its injectors as
`List.of(ExternalContextInjector.ID, "core.user-prompt")` — **`SelectionContextInjector` is not
among them.** The frontend forwards `body.selection` for every shape
(`unifiedChatRequest.ts:97-103`), but extraction does not read it; it sources documents through
`ExternalContextInjector`. So simply letting extraction win could trade a wrong-mode failure for a
no-document failure — which is what the round's own direct API call produced when it invoked
`/api/chat/extract` with a schema and no document, and got back schema-conforming JSON reading
`{"error": "No document provided"}` (note: model output, not a server error — the string appears
nowhere in the Java sources).

This also supplies the most charitable reading of the current code, which is worth stating because
it is coherent: *if extraction cannot consume a selection anyway, routing a selection-bearing send
to RAG-ask at least uses the selection.* That rationale is defensible and still leads to the same
conclusion — the fix is to let extraction read the selection, not to reroute away from it, because
rerouting discards the schema and makes the chip lie about what was sent.

**So the sequence is forced:** give extraction a document channel first (declare
`SelectionContextInjector`, or establish that `ExternalContextInjector` already covers the attached
-document case), then hoist the explicit affordance above the selection switch. Doing the second
without the first converts a visible wrong-answer into a visible no-answer.

## D7. Strand B — the qualifying-set gap needs a round, not a design

Verified while designing, so this is settled rather than proposed:

- `scripts/sandbox/sandbox-launch.py` already implements `--upgrade-from`, including validation
  that the two installers are genuinely distinct binaries, staging with a recorded SHA-256, and a
  rule that an upgrade round always exercises the real download path rather than the pre-staged
  shortcut (`--upgrade-from` and `--models-dir` are mutually exclusive).
- `v0.1.0` publishes `JustSearch_0.1.0_x64-setup.exe` (852,874,628 bytes) and is downloadable.

So the missing `upgrade-from-release` round — required by the harness's own round-mode policy and
never run for 0.2.0 — **costs zero development**. It is a staging command and a round. That makes
it the highest-value use of the period while the code-signing certificate is in identity
validation, because it is the one round whose information is not already implied by round 8, and
it does not depend on the certificate.

The design question it does raise, and which this document does not settle: an upgrade round
exercises **state migration over a pre-existing index, config and data directory**, and nothing in
the current coverage register enumerates what must survive an upgrade. Round 8's warm-reinstall
must-watch entry covers uninstall→reinstall over surviving user data, which is adjacent but not
the same thing — it does not cross a version boundary. Whether upgrade-survival needs its own
enumerated must-touch set, or whether the existing warm-reinstall entry generalises, should be
decided when the round is chartered rather than assumed now.

---

# R — Reach

## R1. The principle, stated so it can be checked

Round 8 produced three independent instances of one error, at three different altitudes:

- **F1** — a fix scoped to one cause of a multi-cause symptom, verified by tests that hand the
  code the input production does not produce.
- **F4** — an assertion scoped to one rendering step of a component that mounts globally, over a
  value that is a hardcoded guess at another component's height.
- **F7** — a coverage register scoped to `placement: 'RAIL'`, so eight shipped, user-reachable
  surfaces cannot be required to have a witness at all, including the one carrying a
  twice-reported finding.
- **F8** — a governance gate scoped to *declared* consumes, so a surface that fetches diagnostic
  data out-of-band (raw `fetch` to `/api/diagnostics/traces`, no `DiagnosticChannelRef`) is
  classified `PRODUCT` altitude and its developer-tier content is invisible to the control designed
  to catch exactly that.

The common form:

> **A control's scope must match the mechanism it guards, not the instance that motivated it.**

This is not a new principle so much as the third member of one 799 §G.1 already names. That
section says a control which is not reachable is a claim, with the corollary that a reachable
control which exits 0 on violations is still a claim. Scope is the third way a green control means
less than a reader takes it to mean, and it is the hardest of the three to notice: reachability and
bite can each be checked by interrogating the control alone, while scope can only be seen by
comparing the control's domain against the domain of the thing it guards — a question nobody thinks
to ask of a passing gate.

## R2. The discriminator, which is what makes this useful rather than merely true

The repo contains both honourers and violators, and what separates them is sharp enough to apply
without judgment calls:

**A control whose instance set is *derived from the system* inherits only its derivation's stated
limit. A control whose instance set is *enumerated by hand* inherits the enumerator's blind spots
— silently, because the enumeration looks complete from the inside.**

Already honouring it:

- `execution-surface` / `operation-surface` scan production sources for every referencer of a
  canonical type and fail on any unregistered one. They also **state their own limit honestly** —
  a fork that re-models the concept from scratch is invisible to a reference scan. That
  combination, derived set plus declared blind spot, is the strongest shape in the repo.
- `check-layout-purity` walks the whole frontend tree, so a new file is scanned automatically, with
  a closed classified exemption list rather than an opt-in list.

Already violating it:

- `check-ui-step-coverage` derives its set from the product catalog but then **filters by
  `placement`**, a property that describes how a user navigates to a surface rather than whether
  its truthfulness matters. A derived-then-filtered set is only as good as the filter's
  relationship to the mechanism, and here there is none. (D5.)
- `ui-proportion-baseline.v1.json` enumerates steps by hand, which is correct for per-surface
  proportion constraints and wrong for a globally-docked component. (D4.)
- `DiskEncryptionProbeTest` and `BrainSurface.installConsent.test.ts` enumerate their inputs as
  fixtures encoding the implementation's own assumption. (D3.)

So the candidate scope for the principle is: **every control that has an instance set** — gates,
lints, coverage registers, and the fixture sets of tests that make truthfulness claims. That is
broad, which is exactly why the next section matters.

## R2b. The two principles this document names are siblings, and that is worth noticing once

§D0 says *a claim must be computed from the thing it claims about*. §R1 says *a control's scope
must match the mechanism it guards*. They arrived from different evidence — one from tracing eight
product defects, one from tracing why four controls stayed green — and they are the same idea on
two planes: **the artifact must be connected to its subject, not to a proxy that resembles it.**
A swatch reading global accent state, and a coverage register keyed on navigation placement, are
both substituting an available neighbour for the thing they are about.

This is recorded as an observation, not folded into one rule. The planes have different
consequences — a wrong claim misleads a user now, a wrong control scope hides defects
indefinitely — and different correctives, so collapsing them would lose the part that tells you
what to do. If a third plane shows the same shape, that is when it is worth naming once and
generally.

## R3. What is NOT being built, deliberately

No scope register, no meta-gate over controls' domains, no campaign to widen every control. The
present problem needs three concrete things — fix the overlay's derivation, decide whether ui-step
coverage keys on reachability or declares eight honest exemptions, and give the install planner
sight of partial downloads. None of those requires the generalisation, and building the
generalisation first would be the premature-abstraction trade this repo has learned to avoid.

The principle is recorded so that the *next* control someone writes gets one question asked of it
— "what is this control's instance set, and is it derived or enumerated?" — not so that a
framework gets built to ask it automatically.

## R4. Earning its keep, and when to retire it

**Evidence it is working:** across the next two to three validation rounds, findings that
reproduce a *previously fixed* defect should approach zero. That is the specific failure this
principle predicts and the current state supplies three examples of (F1, F4, F7). A round that
re-reports something already fixed is direct evidence the principle was not applied; a round whose
findings are all genuinely new is evidence it was.

**Retire it if** widening a control's scope produces only exemption rows and no fixes. If the eight
DEEPLINK surfaces get steps and none of them surfaces a defect, then placement *was* an adequate
proxy for where truthfulness matters, the principle over-fitted to F7, and it should be reduced to
the one concrete lesson (globally-mounted components need a non-per-step check) rather than kept as
a general rule.

**A second retirement trigger, aimed at this principle's own predictable failure mode:** if it
starts being cited to justify widening scope *before* a defect has been observed in the widened
region, it has become apparatus. The principle earns its keep by explaining reproductions that
already happened, not by licensing pre-emptive expansion. Its honest form is diagnostic, and a
diagnostic principle that starts generating work of its own accord has stopped being one.

## Questions the design pass closed

Recorded because two of them closed **against** this document's own opening assumptions, which is
the part worth keeping:

1. ~~Should F4's fix be re-anchored to the component?~~ **No** (§D4). Cross-step constraints are
   not expressible, the schema is closed, and a missing selector is a deliberate hard ERROR that a
   present-or-skip mode would undermine. The defect is a hardcoded guess at another component's
   height; fix the derivation and one assertion suffices.
2. ~~Is F2/F9 a frontend fix?~~ **No** (§D2). The pre-start byte authority cannot see `.partial`
   files at all, so the number the consent dialog should show does not exist to be read.
3. ~~Does the label-collision class deserve structure?~~ **The class does not exist** (§D0). F3 is
   two derivations of one quantity, not two names for two quantities.
4. ~~Is F6 a dispatch bug or a renderer bug?~~ **Neither** (§D6). The frontend never sends
   `core.extract`; the "DOCUMENT Q&A" header is honest about what was sent.

## Questions still open

1. **Should ui-step coverage key on reachability rather than placement** (§D5)? Widening it
   enumerates eight surfaces needing steps — real work that should be sized before it is committed
   to. The alternative is declaring them `exempt` with honest reasons, which at minimum converts an
   invisible gap into a visible one.
2. ~~Should a selection override an explicitly chosen Extraction mode?~~ **Largely settled — see
   §D8.** The project already decided it (decision B14) and `resolveShape` contradicts the
   decision. What remains is not a product call but a mechanical precondition: extraction must
   actually receive a document before it can be allowed to win.
3. **Is a sequence-reading pass over evidence worth adding** (798 §T3)? Three of nine findings came
   from re-reading existing captures, and F9 was invisible in any single frame. Cheap instrument;
   the review is already the round's dominant token cost, which argues for sharding lenses across
   readers rather than stacking them on one.
4. **What is the right next round?** A second fresh-install round on the same build has low
   marginal value. The two with real information value are `upgrade-from-release` — available today
   at zero development cost (§D7) — and a post-signing round.

## What this design deliberately does not do

It does not sequence the fixes or propose a PR breakdown. It also does not build the generalisation
its own §R names: no scope register, no meta-gate over controls' domains, no campaign to widen every
control (§R3).

One diagnostic is deliberately left to a cheap observation rather than resolved by reasoning:
which mirror held F3's phantom 4308 — the head-side cache or the frontend substrate — is settled by
comparing one SSE snapshot frame against one `/api/status` field at the same instant (§D6). Guessing
between them and fixing the guess is precisely the shape of error this document exists to describe.
