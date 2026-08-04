---
title: Round-12 fix campaign — locked-state truthfulness, and the unreadable-is-not-empty invariant
status: "theorized + designed 2026-08-04 (Parts A/B); derisk Part C; plan pending. Scope: R12-F3 (HIGH, blocks 0.2.0 qualification) + the round-12 minor findings + the ranked sandbox-harness backlog from the round-12 session retro."
created: 2026-08-04
updated: 2026-08-04
---

# Round-12 fix campaign — locked-state truthfulness

Round 12 (tempdoc 734) confirmed the whole 805 campaign fixed in context and found **one new
HIGH** that blocks 0.2.0: the Memory trust surface misreports itself when chat encryption is
locked. This document theorizes the problem space, settles the design, and records the derisk
pass. Adjacent history: 805 (the round-11 campaign, same convergence loop), 629 (the at-rest
encryption LAYER that introduced the lock states), 734 (the round record).

---

## Part A — Theorization

### A.1 The round's diagnosis was directionally right and mechanically wrong

Round 12 reported "the UI renders the fact as successfully added" and inferred an optimistic
render. Source reading says otherwise, and the difference matters for the fix:

1. `FileMemoryStore.remember()` (`modules/app-agent/.../FileMemoryStore.java:69-75`) does
   `byId.put(record)` **then** `persist()`. When the key is locked, `persist()` throws
   `KeyLockedException` (`:148`) — and **nothing rolls back the `put`**. The RAM cache now holds
   a record that is not on disk and never will be.
2. `MemoryController.handleRemember` (`modules/ui/.../MemoryController.java:77-80`) catches
   bare `Exception` → `500 {"error":"Failed to record memory"}`. The locked case is
   indistinguishable from a disk failure at the wire.
3. The frontend's `remember()` (`MemorySurface.ts:82-99`) **never inspects the response status**
   — it awaits the fetch, clears the draft, and re-lists.
4. The re-list (`GET /api/memory` → `whatItKnows()` → reads `byId`) returns **the phantom**,
   HTTP 200.

So the UI is not optimistically rendering a fact it hopes was stored: **the backend is serving a
record it failed to persist.** The surface is faithfully rendering a lying API. Any fix aimed
only at the frontend would leave the phantom readable by every other client, including `/mcp`.

### A.2 The symmetric defect the round did not reach — and it is the worse one

The same put-then-persist shape (no rollback) exists in `forget()` (`:86-90`) and `clear()`
(`:93-98`), where it inverts into a **privacy** failure rather than a durability one:

- User locks (or simply restarts — every restart locks the key), opens Memory, clicks **Forget**
  on a fact they want gone.
- `byId.remove()` succeeds in RAM; `persist()` throws; the controller 500s; the FE's `forget()`
  (`:101-108`) ignores the status exactly like `remember()` does; the re-list reads the RAM cache
  and the fact is **gone from the screen**.
- On unlock, `onKeyUnlocked()` (`:58-61`) clears the cache and re-reads the file — **the
  "forgotten" fact comes back.**

A user exercised a privacy control, watched it appear to work, and it silently did not. This is
strictly worse than F3a (a lost write) because the user's belief is inverted about data they
explicitly asked to delete. It is unfound by round 12 and unproven live; it is a source-level
inference to be confirmed in the derisk pass, not asserted.

### A.3 Framing the problem three ways

**(a) As a cache-coherence bug.** The store treats its RAM cache as the authority while the disk
is the real one; a failed persist leaves them diverged, and every read in that window is a lie in
one direction or the other. Fix = make a failed persist unobservable (roll back, or write-through
so the cache is only updated after the write lands).

**(b) As a missing state in the type system.** `whatItKnows()` returns `List<MemoryRecord>`. That
type cannot express "I cannot read right now." Empty-because-nothing and
empty-because-locked collapse into the same value, and the surface then has no way to render
differently even if it wanted to. Fix = make the read tri-state at the store boundary and carry
that state to the wire.

**(c) As a conformance gap.** The sibling subsystem already solved this. Conversations —
encrypted with the *same* key — have `conversations.locked` as a first-class reason code
(`readinessNotice.ts:260`), a `409` + typed message on the locked path
(`ConversationBackupController.java:57,85`), and a history endpoint that answers
`{"locked":true}` so the shell can render "Your chat history is encrypted and locked" (observed
working in rounds 11 and 12). **Memory is the only encrypted store that never got the treatment.**
Fix = conform, don't invent.

(c) is the right primary frame — it makes this a small, precedented change rather than new
architecture — with (a) and (b) as the two mechanical corrections it must carry.

### A.4 Solution directions considered

**D1 — Typed error + tri-state read + locked render (conform to conversations).** Store gains a
readable-state signal and rolls back failed persists; controller maps `KeyLockedException` to a
typed `409 KEY_LOCKED` (matching the backup controller); `GET /api/memory` answers
`{"memories":[], "locked":true}`; the surface renders a locked state with an unlock affordance,
never the empty state; the FE stops ignoring mutation status. *Tradeoff:* touches four layers,
but each touch is one or two lines and every one of them is a place that is currently wrong.

**D2 — Frontend-only (check status, render error).** Cheapest, and wrong: the phantom stays
readable to every non-shell client, `forget()` still silently fails, and the empty-vs-locked
collapse persists at the API. Rejected — it fixes the symptom on one consumer.

**D3 — Keep the cache warm while locked (don't clear on lock).** Would make reads truthful and
writes durable-on-unlock, but it defeats the entire point of `onKeyLocked()`: dropping plaintext
from RAM is the privacy property 629 built. Rejected on principle, and worth stating explicitly
so a future reader does not "simplify" toward it.

**D4 — Queue writes until unlock.** Superficially attractive (the user's intent is preserved) but
it means holding plaintext the user asked to have locked, in RAM or worse on disk, for an
unbounded period. Same rejection as D3, plus new failure modes. Rejected.

**D5 — Refuse the interaction up-front (disable the input while locked).** A good *addition* to
D1, not a substitute: it prevents the failure rather than reporting it. But it cannot be the only
defense — an agent-originated `remember` (the store's other producer) does not go through the
surface's disabled input at all.

Direction: **D1 + D5's affordance-level guard**, with D3/D4 recorded as explicitly rejected.

### A.5 The reach — a third instance of one shape

This campaign has now hit the same shape three times in two rounds, in three unrelated
subsystems:

| Where | Unknown/unreadable state | Rendered as |
|---|---|---|
| Frozen search card (805, R11-F1) | trace absent at commit | `'TEXT'` → "Keyword" |
| Degradation banner (805, R11-F1) | unclassified reason code | keyword-fallback claim |
| Memory surface (806, R12-F3) | store locked | "No learned memory yet." |

Each time, a state meaning *"I do not know"* was rendered as a **positive factual claim**. 805
named the fix pattern for the first two (unknown stays conservative; unknown renders nothing);
this is the same rule crossing from a projection layer into a **persistence** layer.

The sharpest way to say it: the sandbox harness teaches every validating agent that *absence of
signal is not evidence of absence* — and the product violates that rule at its own API boundary.
The rule was written for the observer; it belongs to the observed.

### A.6 Open questions worth holding

- **Which other stores clear on lock?** `check-store-recoverability` and `StoreCatalog` already
  register store construction sites. If other encrypted stores share the clear-on-lock +
  list-returns-empty shape, this is a register-level fix, not a one-surface fix. Derisk must
  enumerate rather than assume.
- **Is `KeyLockedException` reachable on paths that currently 500 elsewhere?** The bare-`Exception`
  catch is a house style in these controllers; the question is whether locked-state 500s exist
  beyond memory.
- **Should the empty-vs-locked distinction be a wire *convention*** (every encrypted-store read
  carries `locked`) rather than a per-endpoint field? Attractive, but only two stores exist today
  — recording the option, not building it (AHA).

---

## Part B — Design

### B.1 W1 — Memory locked-state truthfulness (the blocker)

**Store (`FileMemoryStore`).** Two corrections, both small:
1. **No observable write without a durable write.** `remember`/`forget`/`clear` must not leave
   the cache mutated when `persist()` throws — snapshot-and-restore around the persist, or
   persist-then-mutate. A failed mutation leaves the store exactly as it was.
2. **Reads say when they cannot read.** The store exposes its readable state (an explicit
   `locked` signal alongside `whatItKnows()`), so "empty" and "unreadable" stop being the same
   answer. The existing `byId.clear()` on lock is KEPT — dropping plaintext is the point.

**API (`MemoryController`).** `KeyLockedException` maps to a typed, client-actionable response —
**`409` with an errorCode** — matching `ConversationBackupController`'s existing locked-path
convention rather than inventing a code. `GET /api/memory` carries the `locked` flag so a client
can distinguish the two empties. The bare-`Exception` → 500 stays as the genuine-failure arm.

**Shell (`MemorySurface`).** Three fixes: mutations check status and surface failures instead of
discarding them; the locked read renders a **locked state** ("Unlock to see what the AI has
learned", routing to Security) and never the empty state; the remember input is disabled with a
reason while locked (D5), which also removes the most common way to hit the error at all.

**Reason-code parity.** `conversations.locked` exists; memory's locked condition should be
expressible in the same vocabulary so the health/consequence surfaces can consume it. Whether it
warrants its own code or reuses the conversations one is a derisk question — the two lock
together (one key), so a single code may be honest.

**Regression homes (the round's proposal, adopted and extended):** store-level tests that a
failed persist leaves the cache unchanged (all three mutators, including the **forget**
round-trip through lock/unlock that proves the fact does not return); a controller test that the
locked path is 409-typed, not 500; a ui-web test that a failed mutation does not render as
success and that locked renders the locked state, not the empty state; and a
`sandbox-must-watch` for the cross-boundary sequence, because only a round that locks and
unlocks in sequence can observe it.

### B.2 W2 — Round-12 minor findings

Grouped by what they actually are, not by where they appeared:

- **Observed-EP coverage gap** (`onnxFeatures` carries only reranker + citation-scorer): extend
  the same `EncoderRuntimeExplainer` projection 805 built to embed and SPLADE, so the
  `onnx-ep-fallback-vs-status` must-watch can convert to an API assertion for *all* encoders
  rather than two. Direct continuation of 805 §G.3, not new design.
- **Remedy disagreement** (Brain Simple says "use Repair in Advanced"; Advanced offers Install as
  primary for the same condition): one condition, one named remedy. Small copy/affordance fix in
  `BrainSurface`.
- **Document Q&A UI timeout while the API answered in 9.5 s**: the UI-side budget is tighter than
  the backend's real p95 on a cold reranker. Raise/align the budget, and — more important — the
  timeout copy should not read as a product failure when the backend is still working.
- **Settings render instability + the Vim toggle showing ON while `ui.vimMode` stayed false**: a
  render/state-binding defect (the write path is proven fine by the High-contrast control). Needs
  a reproduction before a fix is designed — carried as investigate-then-fix, not blind-fix.
- **Persistent assistant toast (~10 min, never auto-dismisses, occludes the header)**: give the
  advisory toast a bounded lifetime; round 7's B6 #8 already asked for this and it regressed or
  was never completed — check before implementing.
- **Health "Online" with a red dot** (now reproduced with no skin, so round 11's tinting caveat
  is resolved): a status-dot binding reading a different field than the label beside it — the
  intent-vs-observation shape again, small.
- **Installer names no version on any page**: NSIS page text should carry the version; trivial,
  and it is the third round it has been reported.

### B.3 W3 — Sandbox harness batch (from the round-12 session retro)

The retro's ranked list, adopted with the host-side corrections already recorded in 734. Highest
leverage first, because two of these prevent *false verdicts*, not merely friction:

1. **Bulk-frame exclusion** — a round's capture driver can produce ~950 credit-eligible images
   and make the mandatory reader pass impossible by construction. Either exclude a designated
   bulk directory from the credit-eligible set or make drivers write only on visible change.
2. **`traces.ndjson` parse guidance + a staged `analyze-traces.ps1`** — the field container is
   `attrs` (not `attributes`) and spans embed CRLFs, so naive line-JSON parsing returns a
   **false clean pass on the token-health discriminator**. Round 12 caught it by re-deriving;
   the next round should not have to.
3. **`redact.ps1` absolute-path corruption + success-shaped failure** — the H1 class inside the
   one tool whose job is protecting a secret. Fail loud; add a regression test.
4. **Review-shard exemption** — a no-subagent session rule silently disables the sharding
   procedure the sandbox docs mandate above ~90 images.
5. **`KICKOFF.md` generated by the launcher** (and joined to the documented-asset diff) — round
   12 was told to read a file that was never staged; that was an orchestrator error the harness
   should make impossible.
6. Then: ALT-nudge focus retry folded into `Connect-App`; detached-installer launch documented;
   `gui/README.md` parameter-signature table; MCP history moved out of the generated brief;
   `core.help-surface` validateHow reworded to something performable; build-metadata file
   carrying the candidate commit beside the installer artifact.

### B.4 What this design orphans

The bare `catch (Exception)` → 500 arm in `MemoryController`'s mutating handlers stops being the
locked path's home (it stays for genuine failures). `MemorySurface`'s status-discarding
`try/catch` blocks are deleted, not amended. If a `memory.locked` reason code is added, the
`conversations.locked` row's scope note is updated in the same change so the two do not drift.

### B.5 Reach judgment

**Conformance:** the locked-state treatment follows `ConversationBackupController`'s 409 + typed
message and the `conversations.locked` reason-code precedent; the observed-EP extension follows
805's `EncoderRuntimeExplainer` authority. Nothing new is invented where a pattern exists.

**The principle, named:** *an unreadable state must not be answerable as an empty one, and an
unpersisted write must not be observable.* Candidate scope: every encrypted-at-rest store
(memory today, conversations already compliant, any future one), and by extension every cache
that fronts a fallible writer. Evidence it earns its keep: no further "the surface said nothing
was there" or "it looked saved" finding in later rounds. Retirement condition: once the store
register (`StoreCatalog` / `check-store-recoverability`) can express readable-state and a check
enforces it, the prose retires into the gate.

---

## Part C — Derisk (2026-08-04)

Seven claims probed at source before planning. Two changed the design's scope; none invalidated
it.

**C1 [was the biggest inference] — CONFIRMED and strengthened.** `persist()` throws at
`FileMemoryStore.java:145-149` **before** touching the file, so a locked mutation leaves the disk
untouched while the RAM cache is already mutated. Part A.2's forget-returns defect is therefore
established at source, not merely inferred: `forget()` removes from `byId`, `persist()` throws,
the file still holds the record, and `onKeyUnlocked()` re-reads it. **A privacy control that
appears to work and silently does not.** It still deserves a live reproduction during
implementation (it is the test that must exist), but it is no longer a hypothesis.

**C2 — CONFIRMED.** `MemorySurface.remember()` and `.forget()` both `await authorizedFetch(...)`
with no `r.ok` check (`:87-91`, `:103`), while `loadMemories()` does check (`:74`). The asymmetry
is exactly where the lie enters.

**C3 — CONFIRMED.** The phantom-read chain holds: a locked `remember` leaves the record in
`byId`, and `GET /api/memory` (which reads `byId` via `whatItKnows()`) returns it with HTTP 200.
The surface renders a truthful view of an untruthful API — so a frontend-only fix (direction D2)
is definitively rejected on evidence, not taste.

**C4 [new] — the invariant was named 18 months of tempdocs ago and half-built.**
`FileMemoryStoreTest` carries the comment *"the §L4 'locked must not look deleted' fix for the
eager cache"* — tempdoc 629 identified this exact class and fixed it **only** for the store's own
reload path (unlock restores the cache). It never propagated to the API or to any surface. This
is the `structural-defects-no-repeat` case in its textbook form: the class was known, the fix was
scoped to one layer, and the remaining layers produced a HIGH finding three rounds later.

**C5 [new, changes scope] — the sibling store has the identical collapse, self-documented.**
`RunEventStore.readEvents()` returns `List.of()` when sealed+locked (`:177-179`) with the inline
comment *"empty until unlock (documented agent-run ledger limitation)"*. So the agent-run/Activity
ledger answers "no events" when the truth is "cannot read" — the same lie as memory's, already
known and written down as a limitation rather than surfaced to the user. **Consequence for the
design:** the readable-state invariant belongs to the encrypted-store family (`StoreCatalog` /
`check-store-recoverability` is its natural register), not to `FileMemoryStore` alone. The
campaign fixes memory (the blocker) and *names* the ledger instance with an explicit decision
rather than leaving it buried in a comment; whether the Activity surface currently renders that
empty as "nothing yet" is a one-screen check during implementation, and if it does, the same
locked render rides along (it is a read-only change).

**C6 — CONFIRMED, and the design is additive.** The existing tests pin
`whatItKnows().isEmpty()` for both locked-at-launch and lock-after-unlock. Because the design
adds a **separate** readable-state signal rather than changing `whatItKnows()`'s return type,
those pins stay valid and no deliberate re-pin is required — a good sign that the design runs
with the grain of 629 rather than against it.

**C7 — CONFIRMED.** The conformance target is real: `ConversationBackupController` answers the
locked path with `409` + a typed message (`:57`, `:85`), and `conversations.locked` is an
established reason code. Memory's `409` mapping copies a live pattern.

**Residual risks accepted into the plan:** the Settings render instability (B.2) has no
established mechanism and must be reproduced before it is fixed — it is carried as
investigate-then-fix and may end up out of scope; and the toast-lifetime item may already have a
partial implementation from round 7's B6 #8, so implementation checks before building.

**Confidence: 8.5/10** for the blocker (mechanism fully established at source, conformance target
live, tests additive), 6/10 for the Settings item (mechanism unknown by design), 9/10 for the
harness batch (mechanical).
