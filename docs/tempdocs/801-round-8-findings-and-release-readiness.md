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
3. **There is no auto-updater** — `tauri.conf.json` declares none, and `updater` appears nowhere in
   the repo. Researched 2026-07-31; **§D9 reframes this from one binary choice into two separable
   decisions**, only one of which is irreversible.

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
subtitle line and some do not (`surfaceLayout.ts:62-76`).

### D4.a Correction — the first form of this design was architecturally impossible

An earlier draft of this section said *"the header's owner **publishes** its reserved band and the
overlay consumes it"* and called the expression an implementation detail. **That is wrong, and the
distinction is not a detail — it decides whether the mechanism works at all.** A research pass
(2026-07-31) established the topology:

```
&lt;jf-shell&gt; #shadow-root
  ├─ &lt;div class="stage"&gt; → &lt;jf-stage&gt; #shadow-root → &lt;jf-*-surface&gt; #shadow-root → .header
  └─ &lt;jf-overlay-host&gt;   #shadow-root → .slot.top-right
```

The header and the overlay slot are in **sibling shadow trees two levels apart**
(`Shell.ts:2272,2292`; `JfElement.ts:20-25` gives every `jf-*` a real shadow root, with no
`createRenderRoot` override; Stage renders surfaces into its own root rather than slotting them,
`Shell.ts:2814-2853`). Custom properties inherit strictly parent→child. **A value set inside the
surface's shadow root is invisible to the sibling overlay host, in every density — not just in
unanticipated ones.** "The header's owner publishes it" cannot be built.

The workable form is the other reading of the same sentence: **a token declared at `:root` in the
global token layer, derived by `calc()` from the same `--density-header-pad-y` that the header
itself consumes**, with `surfaceLayout.ts` and `OverlayHost.ts` both reading it. `:root` custom
properties do inherit into every shadow root, and `tokens.css` already depends on exactly that
(`tokens.css:306-310`). This removes the density axis as a source of error — the 16/12/20 split
stops being something an author has to remember — and it needs no observer and no JS.

**The honest residual:** it fixes the *density* half and not the *content* half. A header whose
intrinsic content exceeds the assumed line count — a wrapped title, an added control row, a variant
with a subtitle where the constant assumed none — reopens the bug identically, and the token cannot
detect that. The measured `mustNotOverlapSelector` assertion stays the backstop for that axis, which
is what it already caught the round-7 instance with.

### D4.b Two mechanisms considered and rejected, with reasons

**CSS Anchor Positioning: ruled out, and not on version grounds.** Support is adequate — Edge and
therefore WebView2 have shipped `anchor-name`/`position-anchor`/`anchor()` since Chromium 125
(~May 2024), `position-area` since 129, and this app ships Evergreen WebView2 with no pinned
version (`tauri.conf.json` declares no `webviewInstallMode`, so Tauri's `downloadBootstrapper`
default applies, and no `minimumWebview2Version` exists anywhere in the repo). The blocker is
structural: **anchor names are tree-scoped**, and the spec permits referencing a name only from a
*higher* tree, never a sibling one. Chromium is additionally stricter than the spec here by Chrome's
own documentation ("Chromium doesn't allow inheriting `anchor-name` rules in a shadow root"), so a
newer runtime does not obviously unblock it. The `::part()` escape hatch (CSSWG #10525) would need
two-level `exportparts` forwarding that no surface declares, landing on the precise spec corner
Chrome documents as inconsistently implemented. The repo's own spike works only because its anchor
and target share one shadow root (`spike/NativePopoverSpike.ts:53,68-70`).

Worth noting what anchor positioning *would* have contributed: `anchor(…, <fallback>)` degrades to
a literal when no anchor is mounted. Any measurement-based alternative must handle unmount
explicitly — and unmount is a live path here, since Stage's instance-retention cache
(`Shell.ts:2722`) detaches and reattaches surfaces on navigation. The `:root` token sidesteps this
entirely, because the token always exists.

**`ResizeObserver` → custom property: rejected for a concrete reason, not a stylistic one.** It is
the only option that tracks the actual rendered height across every density *and* content variation,
and JS crosses shadow boundaries freely, so the topology problem disappears. But it would add a
**second writer to `:root`**, and `JfElement.ts:8-10` documents `applyAppearance` as the single
writer of that surface. It also carries a first-frame default before the first callback and a
stale-value path on unmount, which — per the cache above — is routine rather than hypothetical.
If the content axis ever produces a real defect the token cannot cover, this is the option to
revisit, and the single-writer invariant is the thing that would have to be reconciled first.

**Ecosystem check: there is no established pattern being missed here.** Sonner uses a constant
offset, Radix defines no placement variable at all, React Spectrum offers corner placement only,
and Fluent UI has "scope Toaster to a particular region of the screen" as an **open** issue
(microsoft/fluentui#28449). CSS's obvious right primitive — an author-declared environment inset
alongside `safe-area-inset-*` — does not exist; it is an open issue in the Environment Variables
spec itself (csswg-drafts#2820). The modest token approach is not naive; this is a genuine gap in
the platform and in every major design system.

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

## D9. The updater decision separates into two, and only one of them is irreversible

Researched 2026-07-31 against Tauri's own documentation and plugin sources. The repo is Tauri
**2.11.5** with no updater plugin, no `plugins.updater` section, and no
`bundle.createUpdaterArtifacts`. The framing in Strand B — "ship an updater or accept an
un-updatable cohort" — turns out to be a false binary.

### What the research settles

**The irreversibility claim holds, and is narrower than stated.** The updater is code compiled into
the binary. A shipped 0.2.0 with no updater configuration has no polling agent, no endpoint and no
public key, so nothing a later release does can reach it. But what is permanent is *the 0.2.0
cohort specifically*, not the option: 0.3.0 can add an updater freely. The debt is
(0.2.0 install count) × (how long those users sit on it) — which for a first release with no
current users is close to zero, and grows the longer 0.2.0 is the public version.

**Authenticode and the updater's signature are fully independent**, which removes a coupling this
tempdoc had implicitly assumed. The updater uses **Ed25519 via minisign**, its public half compiled
into every binary, its private half held as a CI secret; the code-signing certificate plays no part
in it, and an update artifact without a valid minisign signature is rejected unconditionally
(`pubkey` is a required `String`, not an `Option`). So **waiting on certificate validation is not a
reason to defer this decision**, and holding the certificate does not partially cover it. The
converse also holds: the minisign signature does nothing for SmartScreen, so an OV certificate's
reputation-building period applies to updates exactly as it does to first installs.

**Shipping the updater creates a permanent custody obligation from day one.** Tauri's documentation
is blunt that losing the private key means you cannot publish updates to anyone already installed —
i.e. key loss is *exactly equivalent* to never having shipped an updater, for that cohort. Rotation
is only possible through an update signed with the old key.

**Three costs that are easy to under-count.** Every update is a **full ~260 MB installer download**
— no deltas, no in-place patching. The app is **force-exited** on Windows during install (a
documented platform limitation), so `downloadAndInstall()` never returns there. And the release
workflow does **not** use `tauri-action`, so `latest.json` generation is net-new work in a pipeline
that already does substantial custom staging.

**One thing our configuration already gets right.** `installMode: currentUser` is the mode that
works: the NSIS template emits `RequestExecutionLevel user`, and the updater launches the installer
via a non-elevating `ShellExecuteW`. The `perMachine`/`both` modes fail with `Os error 740`
(elevation required), the app quits, and no installer appears. This is inference from the two
mechanisms rather than an explicit maintainer statement, but the mechanism is unambiguous.

### The reframing

The thing that is irreversible is **reachability** — whether a shipped build can ever learn that a
newer one exists. It is not *auto-installation*. Those separate cleanly:

| | Closes reachability | Permanent obligation | Reversible later |
|---|---|---|---|
| **Notify-only**: startup version check against a small JSON or the GitHub releases API, in-app notice linking to the download | Yes | None — no keypair, no signing, no NSIS interaction | Yes; the endpoint can be repointed at anything, including a later real updater |
| **Full updater plugin** | Yes | Minisign key custody, forever | Only forward |
| **Nothing** | **No** | None | The cohort is stranded |

A notify-only mechanism closes the one gap that cannot be closed later, at no permanent cost, and
does not foreclose the full updater. It also degrades honestly: a user who is told a version exists
and chooses to install it manually gets a correct in-place upgrade, because the NSIS installer
already reads `DisplayVersion` from the uninstall registry key, runs a semver comparison, and
invokes the stored `UninstallString` — with `allowDowngrades: false` respected.

### What is not established, and why one of them matters

- **Whether the updater-downloaded `setup.exe` triggers SmartScreen mid-update.** No authoritative
  source found either way. This is the sharpest open risk in the full-updater option: a SmartScreen
  prompt appearing in the middle of an ostensibly automatic update is a materially worse experience
  than no updater at all, and it would apply for the whole period an OV certificate is building
  reputation — which is precisely the period after 0.2.0.
- **Whether `nsis/installer-hooks.nsh` behaves correctly under `/P` + `/UPDATE`.** That path has
  never executed. Only a real update run settles it.
- **Expected install count for 0.2.0**, which is what actually prices the irreversibility.

Taken together these argue for deciding reachability now and auto-installation later, on evidence,
rather than treating them as one call that has to be made before the certificate lands. The owner
decision this tempdoc needs is therefore narrower than it first appeared.

## D10. The parity instrument commits this document's own defect

A stress-test pass (2026-07-31, briefed to refute rather than confirm) was run against the
conclusion this session had already written into tempdocs 798 and 734: *"same weights, different
numbers ⇒ the embedding inference path differs between dev and the Sandbox."* **It does not hold,
and the reason it does not hold is §D0's invariant, committed by the measuring instrument.**

### Why the conclusion fails

**The sign structure is decisive and was never examined.** Recomputed from the raw captures: 75
shared (query, doc) pairs across 10 queries, of which **65 shift in the same direction**, mean
**+2.2e-2** in cosine terms. (The captured score is not cosine — it is Lucene's euclidean
`1/(3−2·cos)`; converting *increases* the anomaly by about 15%.) Floating-point divergence from
execution providers, TF32, precision, drivers or kernel selection is **zero-mean and symmetric**.
A consistent one-directional shift is not a rounding signature — it means a different quantity is
being computed, not the same quantity computed differently.

**The magnitude was also wrong by orders of magnitude.** Documented CPU-EP↔CUDA-EP divergence sits
at ~1e-5–1e-4. Even ORT's own acceptance bound for a full FP32→FP16 conversion is `rtol=0.01` —
below the observed mean. Attributing 2e-2 to execution-path noise required the noise to be 100–1000×
its documented size.

### The instrument's defect, stated in this document's own terms

`EmbeddingFingerprint` is the field the parity checker uses to certify that both environments ran
the same model. It hashes **one file, chosen by a `Files.isRegularFile` check that prefers the FP16
model whenever it is present on disk** — and never asks which file the session actually loaded.
There are three states it cannot distinguish: FP16-on-CUDA, FP32-on-CUDA (a documented fallback
when FP16 session creation fails), and FP32-on-CPU. The sidecar JSONs that set truncation length,
pooling strategy and query/passage prefixes are not hashed at all, and round 8 logged all three as
degraded or undeclared.

So the fingerprint's **claim** is *"these are the weights that ran"*. What it **reads** is *"a file
that exists on disk."* That is precisely the defect in §D0's table — a claim computed from
something adjacent to its subject — and it appears here in the instrument the project uses to
decide whether search quality regressed. §R1's sibling framing is exactly on point: the artifact is
connected to a proxy that resembles its subject rather than to the subject.

It is worth being blunt about the consequence: **the parity check has been certifying "same
weights" across three rounds on evidence that cannot support it**, and the earlier refutation of
the FP16-vs-FP32 hypothesis — which rested entirely on this fingerprint — is withdrawn with it.
Finding 5 is un-attributed again, and has been un-attributable all along.

### What is now known, and what the corrective is

Established and worth keeping: the divergence is dense-leg-only; pooling and L2 normalisation
happen in Java after the ONNX graph, with identical bytecode both sides, so they are excluded; and
the ORT batch size is a fixed constant rather than VRAM-derived, which eliminates the
"paravirtualised GPU picks a different batch size" hypothesis. A second uncontrolled variable was
also surfaced: the index is int8 scalar-quantized with per-segment, corpus-dependent quantiles, and
round 8's live index held 5194 documents against the baseline's 5189 — a second-order effect, but
not a zero-mean one, and not currently controlled for.

The corrective is not a better guess at the cause. It is that **neither environment records what
ran**, so the question is currently untestable rather than merely unanswered:

1. **Record the actual model path, execution provider and precision** at session selection, and
   surface them next to the fingerprint. Near-zero cost, and nothing further can be concluded
   before it exists.
2. **Extend the fingerprint from a file hash to a directory manifest hash**, covering the sidecars
   that set truncation, pooling and prefixes; and record EP + precision in the baseline itself.
   This is the structural fix — without it the checker keeps certifying a property it cannot see.
3. **Embed one fixed string on both machines and compare the raw vectors.** This removes the index,
   quantization, HNSW and corpus from the experiment entirely. Per-element deltas around 1e-4 would
   exonerate the encoder and move the investigation index-side; deltas around 1e-2 with a
   consistent sign would confirm it.

Only after those does anything about TF32 or provider tuning become worth testing.

### The methodological point, which is the durable part

This conclusion was reached in one pass, written into two tempdocs, and reported as settled —
and it was wrong in both halves. What caught it was not more evidence but a **brief that required
refutation as the default stance**. The sign test that killed it takes one line of arithmetic over
data that was already on disk when the original conclusion was written.

That is the same lesson as §D3's two tests: the failure mode is not insufficient data, it is an
oracle that agrees with the hypothesis by construction. Here the hypothesis was *"the environments
differ"* and the fingerprint was read as evidence *against* the alternative — while being
structurally incapable of distinguishing them.

## D11. Derisking pass (2026-07-31) — measured, and it corrects §D4 again

A derisking pass ran before implementation. Four of its results change the design; they are recorded
here because the design was wrong without them.

### D11.a §D4's token is insufficient — the reserved band is structure-variable, not just density-variable

Measured live on the Library surface (the surface where F4 actually reproduced):

| Surface | `.header` y | `.header` h | header band ends |
|---|---|---|---|
| unified chat | 56 | 33 | **89** |
| library | 89 | 54 | **143** |

The shipped dock reserves `calc(2.5rem + 3rem + 0.5rem)` = **96px**. On Library the header band ends
at **143px**, so the post-fix dock still lands *inside* it. That is why F4 reproduced on Library
after the fix shipped.

The cause is not the header's own height. Chat's header starts at y=56; Library's starts at
**y=89** because Library stacks an extra chrome row (the Folders/Browse tab strip, visible in round
8's `28-library-surface.png`) between the topbar and its header. **So a token derived from
`--density-header-pad-y` — §D4's design — would still be wrong for Library**, because the quantity
that must be reserved is everything stacked above the surface's content, which no static
header-padding token can know.

Also measured: `--density-header-pad-y` is `16px` and chat's header is `33px` (2×16 + 1px border),
i.e. the header's *own* content contributes nothing to its height on that surface. The 48px the
dock assumes over-reserves on chat and under-reserves on Library — one constant cannot be right for
both.

**Consequence for the design:** §D4's "publish a derived token" is still the right *shape* (derive,
don't guess) but the wrong *quantity*. What must be published is the bottom of the surface's chrome
stack, not the header's padding. Whether that is expressible statically is now an open question,
and it moves `ResizeObserver` — rejected in §D4.b on the single-writer objection — back onto the
table as possibly the only correct mechanism. This must be settled before implementation, not
during.

### D11.b The measurement nearly repeated the stale-ref error, and the guard is worth recording

The first browser reading showed the overlay slot docked at **48px**, contradicting the source. The
dev stack had been launched from the **main checkout**, which was 10 commits behind `origin/main`
and still carried the pre-fix `calc(2.5rem + 0.5rem)`. My worktree carries the shipped
`calc(2.5rem + 3rem + 0.5rem)`. Had that reading been taken at face value it would have "proved"
the fix never shipped.

This is the same failure as the collision check earlier the same day (comparing against a stale
local `main`) and the same shape as §D10's fingerprint: **a measurement read from a proxy for the
subject.** For live UI measurement the guard is explicit — launch the stack with `distFrom` set to
the worktree under test, or verify a known-changed line in the served source before trusting any
geometry.

The Library header geometry above is retained as valid because it is a property of that surface's
own chrome stack, which neither the dock change nor the conversation-track change touches — and it
is corroborated by round 8's own screenshot, taken against a candidate that *did* contain the fix.

### D11.c F3 — the head-side cache does not drift; suspicion moves to the frontend substrate

Reproduced on a live stack: 807 documents ingested, then the SSE snapshot and `/api/status` captured
at the same instant, before and after drain.

| moment | `worker.core.pendingJobs` | SSE snapshot rows | non-DONE rows |
|---|---|---|---|
| mid-ingest | 807 | 812 (807 PENDING + 5 DONE) | 807 |
| after drain | 0 | 812 (all DONE) | **0** |

The head-side mirror (`RemoteIndexingJobsBridge.cached`) tracked a full ingest-to-drain cycle with
no drift, and agreed with the count at both instants. Since the frontend substrate maps `DONE →
null`, a correct consumer of that snapshot would show zero queued.

**Honest limit: this does not reproduce round 8's drift, it narrows it.** A healthy run cannot
distinguish "the head cache never drifts" from "it drifts only under a stalled feed" — and a
stalled feed is exactly the condition not reproduced. What it does establish is that the head cache
handles the ordinary path correctly, which moves the frontend substrate (the feed-stall path that
tempdoc 798 B4's reconnect actuator addresses, documented by
`indexingJobsBridge.feedstall.test.ts`) to the front of the queue as the suspect.

### D11.d F1 — the honest fix needs no backend change, and the test does not need touching

Costing both branches settled two things that were open in §D0's table.

**The wire already carries the distinction the card needs.** `source === 'shell-property'` with
`confidence === 'LOW'` uniquely means *the probe ran, exited 0, and returned a value the OS calls
indeterminate*; `source === 'none'` means *the probe produced no answer at all*. Both are already in
`StatusSnapshot` and already in scope in `renderAtRestCard`. So stopping the false "needs admin"
claim is **frontend-only** — no proto, no schema regeneration, no `--gate wire`.

**Detecting elevation is cheap but does not close the finding.** An FFM advapi32 probe is precedented
in-repo (`WindowsPowerStatus.java:91-95` calls kernel32 through `Linker` with no external
dependency, and `--enable-native-access` is already configured), and would be structurally simpler
than the existing `WindowsJobObject`. But `qualityKnown` means *"we read the TPM-vs-PIN
distinction"*, not *"we are elevated"* — so setting it from an elevation check would render
"Configuration: Known" while knowing nothing. Elevation would need its own wire field, and the card
would *still* need the honest wording. Branch 1 without Branch 2 leaves the card mute in the exact
reported scenario.

**The `0 → UNKNOWN` test does not need to change, and changing it at the mapper would be the
weakening move.** The conflation is a *producer* bug: the PowerShell one-liner casts
unconditionally, and `[int]$null` is `0`, so a missing property and a genuine `0` are
indistinguishable by the time `mapPkeyValue` sees them — the information is destroyed at the script,
not at the switch. Remapping `0` at the mapper would encode a guess at the layer that can no longer
know. Making absence distinguishable in the script is additive, keeps the existing assertion true,
and adds a new test for the absence case. This resolves the concern §D3 raised without touching a
green assertion.

### D11.e F6 is worse than §D8 assumed — the routing change alone would produce confabulation

§D8 said letting extraction win could trade a wrong-mode failure for a no-document failure. The
real exposure is worse. Today's extract request body is `{shapeId, prompt, schema, conversationId,
context?}` — `unifiedChatRequest.ts:86-89` drops `docIds` for extract, and `body.selection` comes
from a one-shot compose register that the reported flow never writes. `ExtractShape` declares
`[ExternalContextInjector, core.user-prompt]`, and `ExternalContextInjector` reads only
`body.context` — **prior chat turns**, never a document.

Meanwhile the schema *is* honoured: the engine promotes it to a `response_format` sampling
constraint. So routing to extract today would return **schema-conforming JSON confabulated from the
prompt and chat history, with no document behind it** — a quiet wrong answer replacing a visible
wrong mode. That is strictly worse for a product whose claim is grounded retrieval.

The fix needs both sides of the wire (declare a document-bearing injector *and* populate a document
field), plus two ride-alongs that must land in the same change: `SelectionContextInjector`'s
`text-range` arm hardcodes a *"Summarize the following selection"* prefix, and its `rag.citations`
emissions fall outside `ExtractShape`'s declared `EVENT_SCHEMA`. Also corrected: the
`selectionPolicy=OPTIONAL` comment at `unifiedChatRequest.ts:100` is **stale** — no such concept
exists in Java; opting in is simply listing the injector id.

### D11.f Confirmed available at zero cost

The `upgrade-from-release` round stages end-to-end: previous-release installer verified and staged
with its SHA-256, `validation-mode.md` set to `upgrade-from-release`, coverage brief generated (22
cohorts, 16 surfaces, 5 shapes), 26 must-touch items, `.wsb` written. It also confirms §D7's open
question — the generated must-touch set is the *same* as a fresh round's, so nothing enumerates what
must survive a version upgrade.

Also green: the branch is current with `origin/main` and the full suite passes genuinely — 190 of
190 tasks executed with the build cache disabled, 33 test tasks, `BUILD SUCCESSFUL in 4m 35s`. (A
first attempt with `cleanTest test` reported success in **8 seconds** with 78 tasks from cache; that
is not a test run, and it is the third instance of that false green in this work.)

## D12. F4 resolved — and it needed no reserved band at all

**This supersedes §D4 and §D11.a.** Both designed a reserved band; both are dead, and the third
investigation found the answer was never in the geometry.

### Why every band design fails

- A band derived from `--density-header-pad-y` (§D4) cannot work: surfaces stack a
  `<jf-surface-tabs>` strip above their header, so chat's header band ends at y=89 and Library's at
  y=143 (§D11.a). The strip is a **declared pattern with five hosts**, not a Library quirk, and its
  height is content-derived from a catalog-supplied member count — no token expresses it.
- A *measured* band cannot work either: Library's "Add Folder" row lives **inside**
  `.folders-scroll` (`LibrarySurface.ts:154-159`, `overflow-y: auto`), so its position is a function
  of scroll offset. **No reserved band can clear a control that scrolls.**
- Relocating the slot fails too: `bottom-right` is already occupied (`Shell.ts:2310`), and at 1040px
  a right-docked column overlaps the centre-docked composer.

There is also no design-system answer being missed — Fluent UI has "scope Toaster to a region" as an
open issue, and CSS's would-be primitive (an author-declared inset beside `safe-area-inset-*`) is an
open issue in the Environment Variables spec.

### What the defect actually is

The lethal combination is **persistence + overlay**, not either alone. `AdvisoryToastHost.ts:332-345`
already auto-dismisses every toast **except** `REQUIRES_ACK`, which persists deliberately — the
comment at `:47-48` is explicit that this is "NOT a timeout".

But `REQUIRES_ACK` records **already have a durable home**: they appear in `AdvisoryInboxDrawer`
(`:349`, mounted at `Shell.ts:2351`) and `AdvisoryRailBadge` already carries an unread indicator. So
the persistent overlay is redundant with a channel that already exists, and contributes nothing
except the occlusion. Round 8 watched the same two advisories cover the Library header for six
minutes across several navigations — that is the redundancy, observed in the wild.

**Fix: time-bound the `REQUIRES_ACK` toast; let the inbox and the rail badge carry persistence.**
The "don't let the user miss it" requirement is preserved by the badge, which is where it belonged.

### Why this is the right shape and not merely the cheap one

It removes the defect class rather than relocating it. A bounded toast cannot occlude anything
indefinitely regardless of surface, density, scroll offset, tab strip, or viewport — none of which
the previous designs could accommodate. It adds no structure, introduces no measurement, and needs
no new communication channel between sibling shadow trees.

It also fits §D0's invariant, one level up: the toast was **asserting** an unmet obligation
("act on this") by occupying screen space, when the component whose job is exactly that assertion
(the rail badge) already existed. The claim was being made by the wrong artifact.

### Honest residual, recorded rather than smoothed

The `.top-right` slot still hosts `jf-provenance-badge` and `jf-plugin-error-overlay`, and its
`calc(2.5rem + 3rem + 0.5rem)` dock remains calibrated against the chat surface's header — so it
still does **not** clear a surface that stacks a tab strip. That exposure is much smaller (both are
small and one is rare) but it is not zero, and the register's `occlusionNote` is updated to say so
rather than implying the class is closed.

## D13. F1's producer conflation — inferred from source, then measured

§D11.d argued from source that the disk-encryption probe manufactured a `0` from an absent property,
because PowerShell's `[int]$null` is `0`. That was inference. It has now been **executed**, and the
mechanism is confirmed exactly:

```
new script, C:\         -> [2]                 (property present, real value)
new script, C:\Windows  -> [PROPERTY_ABSENT]   (absence detected)
OLD unguarded cast, same absent case -> [0]
```

So the pre-fix one-liner did turn *absence* into a value indistinguishable from a genuine `0`, and
the new null-guard separates them. This matters for two reasons beyond the fix itself.

**It confirms the test was right to leave alone.** `DiskEncryptionProbeTest`'s
`undocumentedPkeyValuesStayUnknown` asserts `0 → UNKNOWN`, and that assertion is *true* — it was
merely being reached by a path that should never have produced `0`. Remapping `0` at the mapper
would have encoded a guess at the layer that could no longer know why `0` arrived. Fixing the
producer keeps the assertion honest and adds a new one for absence. This is the concrete instance of
§D3's point about oracles, resolved the additive way.

**It also closes a gap in the new test.** The shipped test asserts the script *text* contains a
null-check — which is the same "the oracle is the implementation's own assumption" shape §D3 names.
Executing the script is the external oracle that text-matching cannot be; both are now in evidence.

**Residual, stated plainly.** Which value Windows Sandbox actually returns is still unknown, and it
decides whether round 8's specific instance is fixed. If the property is *absent* there, the new
sentinel routes it to `source: none` and the card reads "Not applicable" — fixed. If Sandbox instead
returns a genuine PKEY `3` (the OS's own "indeterminate"), the card still reads "needs admin", which
is defensible for that state but would mean the reported instance persists. **The next round settles
it**, and if the card still says "needs admin" on the Security surface, the value is `3` and needs
its own treatment rather than a re-run of this fix.

## I — Implementation record, wave 1 (2026-07-31)

Three fixes implemented in parallel by pinned workers, each required to produce a **bite proof** per
assertion (break the fix, watch the test fail, restore). Every proof below is an observed failure
message, not a claim.

### F4 — advisory persistence (§D12)

`ACK_TOAST_DURATION_MS = TOAST_DURATION_MS * 3` (15 s); the duration now dispatches on
`sourceRenderHint`, and `timeoutId` stays null **only** for the sticky local-ERROR case, whose record
lives nowhere but the toast. Three pre-existing tests that pinned `timeoutId === null` for
`REQUIRES_ACK` were inverted — they pinned the old deliberate behaviour, which this change reverses
on purpose.

Independently verified by the orchestrator rather than taken on report: `dismiss()` calls
`dropEphemeral` only when `origin === 'local'`, so a stream advisory's record survives the toast
hiding. The one combination that would have broken this — a *local* `REQUIRES_ACK` — is **not
producible**: local records are constructed with `sourceRenderHint: 'EPHEMERAL'` hardcoded
(`AdvisoryStore.ts:436`).

Teardown landed: the `OverlayHost` comment no longer claims to address a toast defect it no longer
addresses, and the register's `occlusionNote` states the residual (reservation calibrated on the chat
header band; a tab-strip surface is not cleared).

### F7 / F8

F7 reads `decl.theme?.tokens['accent-tint']` through the existing `isSafeTokenValue` authority, with a
real fallback for the three theme-less built-ins — deliberately **not** `var(--accent-tint)`, which
would be the same defect wearing fallback clothing. F8 moved both telemetry rows to the Advanced
branch and corrected the false `tracesAvailable` doc claim, which the worker found duplicated at a
second site and swept in both places.

### F1 — see §D13

The frontend rule changed from an *exclusion* (anything not explicitly excluded gets the elevation
offer) to an *allowlist*. The behavioural consequence worth recording: an unrecognised future state
now reads "Not applicable" instead of defaulting to a claim about elevation it cannot support.

### F6 — extraction (§D8, and it grew)

All four steps landed, plus both ride-alongs. Two decisions worth recording because they went beyond
the brief:

- **`rag.citations` was declared, not suppressed.** The injector genuinely emits it and it names the
  document the extraction came from; suppressing would have shipped structured output with no visible
  provenance. This required regenerating `scripts/codegen/shapes.fixture.json` and the generated
  handler — verified independently by the orchestrator: `check-shape-handler-regen` reports
  "output matches committed files", and the diff is exactly the one new event.
- **A shape signal had to be introduced.** Making the injector's prompt prefix shape-aware (so an
  extraction is not told to "Summarize the following selection") required
  `ConversationContext.shapeId()`, fed from `shape.id().value()`. Reading `body.shapeId` would not
  work — only `/api/chat/dispatch` carries it; `/api/chat/extract` and `/api/chat/summarize` are
  static routes.

**Open, and correctly deferred to the orchestrator:** the chain is verified up to but not through a
live model. What remains is to confirm the assembled prompt actually contains the document text —
`ai-offline-isnt-a-wall` applies, so this is a live-stack check, not an unavailable tier.

**New fork logged, not fixed:** `UnifiedChatView.currentShapeId():4448` is a *fourth* hand-written
affordance-to-shape map running parallel to the resolver — the same drift shape §D0 describes, found
while fixing three of its siblings.

### Wave 2 — F5 and F2/F9

**F5's horizontal half had a root cause neither §D11.a nor the plan predicted.** The multi-column
decision was a **viewport media query** (`@media (min-width: 64rem)`), not a container query — so the
layout committed to multi-column at a 1040px window while the surface only had 832px
(`viewport − rail − host padding`). The 888px-of-minimums figure was never the defect; it was the
symptom of a decision made against the wrong width. Fixed by moving to `@container chat-surface` on
`.answer-plane`, with the responsive authority repurposed from "viewport ≥ 64rem" to "reported
surface content width ≥ 64rem". No container queries existed anywhere in `shell-v0` before this.

A side-effect caught during implementation and worth recording: `container-type` implies layout
containment, which makes the element a containing block for `position: fixed` descendants — it would
have re-anchored the citation hover card, which positions from viewport coordinates. Scoping the
container to `.answer-plane` rather than `:host` avoids it, and a test pins both the placement and
the wrapping.

The same measurement error was found **in our own harness**: the `chat-occlusion` ui-shot step ran at
1050×800 with a comment making the identical viewport-vs-container mistake. After the fix, 1050px
would starve the surface and the document pane would drop out of the capture — which the proportion
gate treats as a hard ERROR. The step moved to 1250×800.

F5's vertical half suppresses the landing collapse while a reading pane is mounted, on the reasoning
that a preview is opened by a deliberate act and carries its own close control, while clearing a
search box is query-scoped and should not destroy a reading surface.

**F2/F9 kept two quantities apart that it would have been easy to conflate.** `totalBytes` remains
the progress denominator (`downloadedBytes` starts at the resumed offset, so subtracting there would
overshoot 100%), while a new `remainingBytes() = totalBytes − resumableBytes` feeds the consent
total. Verified independently by the orchestrator at both call sites. The consequence is that consent
and progress now state *different numbers by design* — network transfer versus file completion — so
the dialog names the gap on its own line rather than leaving the reader to reconcile them.

### Orchestration note worth carrying forward

Three workers shared one worktree. Their file sets were disjoint and nothing collided, but **two of
the three reported the others' in-progress edits as "pre-existing foreign work"**, and one attributed
a typecheck error to it. Both correctly declined to "fix" what they had not written. The lesson is
that no single worker's green is trustworthy under concurrency — the authoritative run is the
orchestrator's, once on the settled tree.

## V — Verification ledger (2026-07-31)

Recorded per tier so a later reader can see what is actually established and what is still a claim.
A green suite is not the same as a live-verified fix, and this table keeps the difference visible.

| Fix | Unit/bite-proven | Measured (geometry) | Live (browser) | Live (through a model) |
|---|---|---|---|---|
| F4 advisory persistence | yes, incl. inverted pre-existing tests | proportion gate clean | not exercised (needs a `REQUIRES_ACK` advisory) | n/a |
| F5 layout | declarations + arithmetic only (happy-dom does no layout) | proportion gate clean at the new 1250×800 | **yes** — container query active, single column below threshold, no overflow | n/a |
| F6 extraction | yes, both wire sides | n/a | not exercised | **NO — see below** |
| F1 disk-encryption | yes; producer conflation additionally **executed** (§D13) | n/a | **yes** — card coherent, no false claim, but on PKEY 2 (an *unchanged* branch) | n/a |
| F2/F9 install resume | yes | n/a | not exercised (needs a real partial download) | n/a |
| F7 skin swatches | yes | n/a | **yes** — 9 swatches, 7 distinct colours | n/a |
| F8 Brain telemetry | yes | n/a | not exercised (needs `traces.ndjson` present) | n/a |

Suite: `test --rerun-tasks --no-build-cache` → **BUILD SUCCESSFUL, 190 of 190 tasks executed**, cache
disabled, real exit 0. Note a first attempt with `cleanTest test` reported success in **8 seconds**
with 78 tasks from cache — the third false green of this shape in this work, and the reason the
forced form is now written into the plan.

**The one genuine gap: F6 through a live model.** The chain is proven link by link — the frontend
sends `body.selection`, the shape declares a document-bearing injector, the injector resolves the
reference to real `DocumentService` content. What is *not* proven is that the assembled prompt the
model receives contains the document text. Per `ai-offline-isnt-a-wall` this was pursued rather than
declared unavailable: the blocker is that the **cuda12 llama-server runtime has never been staged on
this machine** (`:modules:ui:stageLlamaCudaVariant` downloads it), not that the tier does not exist.
Honest weighting: the *new* links are all tested, and the untested link is long-shipped engine
behaviour that `SummarizeShape` already depends on — so the residual risk is lower than the empty
cell suggests, but it is not zero and it is not closed.

**Measurement traps hit while verifying, all the same shape.** Three times a *proxy* was read instead
of the subject: a stale local `main` ref made an unrelated worktree look like a collision; browser
HMR staleness made this worktree's code look like the main checkout's (the served module was correct
all along — a hard reload settled it); and `window.innerWidth` reported 1493 while the window was
actually 720px wide, which only the element rects revealed. The durable rule for live UI work:
**hard-reload before measuring, and trust `getBoundingClientRect` over `window.innerWidth`.**

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
