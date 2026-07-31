---
title: "801 — Round 8 findings and the path from qualifiable to released"
type: tempdocs
status: "SKETCH (2026-07-31) — no design settled, no implementation licensed. Opened because tempdoc 798 closed on round 8's QUALIFIABLE verdict, and the work that verdict surfaced needs a home that is not a closed document."
created: 2026-07-31
updated: 2026-07-31
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

## Open questions this sketch does not answer

1. **Should F4's fix be re-anchored, and if so to what?** Asserting a global overlay's geometry
   "wherever it mounts" has no precedent in the current ui-shot model, which is step-based.
   Whether that is a small extension or a different instrument is unknown.
2. **Does the label-collision class (F3) deserve structure?** Two instances is not enough to
   justify a vocabulary register, and the existing registers govern declared concepts rather than
   display strings. Recording and accumulating is the honest move.
3. **Is a sequence-reading pass over evidence worth adding** (798 §T3)? Three of nine findings came
   from re-reading existing captures, and F9 was invisible in any single frame. The instrument is
   cheap; the token cost of the review is already the round's dominant cost, which argues for
   sharding lenses across readers rather than stacking them.
4. **What is the right next round?** A second fresh-install round on the same build has low
   marginal value — round 8 found no blocker and its remaining findings are all fix-then-verify.
   The two rounds with real information value are `upgrade-from-release` and a post-signing round.

## What this sketch deliberately does not do

It does not sequence the fixes, propose a PR breakdown, or pick regression homes beyond what 734
already records per finding. Round 8's evidence is one round old and two of its findings (F3, F6)
are not yet diagnosed; sequencing before diagnosis is how the F4 anchor mistake happened the first
time.
