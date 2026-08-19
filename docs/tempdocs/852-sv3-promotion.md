---
number: 852
title: The window cutover — promoting Search v3 to the one interaction surface
status: IN PROGRESS — S0 implemented (merged, #493); S1 implemented (merged, #495); S2 implemented
  (this PR); S3+ pending
created: 2026-08-19
scope-of-this-file: S0, S1 and S2. The full program charter (target end state, the parity ledger,
  the slice DAG S1-S11, the open questions) lives with the orchestrator and lands here as the
  program's later slices land. This file exists so each slice's code has its design of record
  in-repo rather than only in a PR body.
forcing-function: `check-window-cutover` (shipped with 851) WARNs until 2026-09-30 and FAILS
  after, keyed on (a) `core.search-v3-surface` audience USER in CorePlugin.ts and (b) the
  `governance/window-cutover.done` marker the program's final slice writes.
related: 847 (citation correctness — S1-S3 shipped in #488; 852-S1 is sequenced strictly behind
  847-S3, which owns `Sv3Turn.recordId` and the identity-keyed merge), 848 (reasoning parity),
  846 (markdown substrate), 849 (evidence reader), 822 (the Search v3 window itself: Phases
  F1-F10), 610 (the context set), 513 (stable message ids + branch), 629/734 (the
  conversation-store lock)
---

## Status

| Slice | What | State |
|---|---|---|
| **S0** | FE↔Java surface-parity leg on `check-surface-composition` | **implemented (merged, #493)** |
| **S1** | Turn identity + the `/history` companion load. No UI. | **implemented (merged, #495)** |
| **S2** | The tempdoc-610 context set (floor / clear / compact / summary-edit / message-exclude) | **implemented (this PR)** |
| S3 | Branch + version pager + edit/retry/resend + cascade-aware delete | pending |
| S4-S7 | Composer tier control, retrieve tier, `jf-control` adoption, flip prerequisites | pending |
| S8-S11 | The flip, the sweep, the marker, the renames | pending |

S0's second half — recording that record-attribute hydration lives in
`modules/ui-web/src/shell-v0/components/chat/` (adopting 847-S1's location) — is a decision
addressed to the 847 and 848 implementers and carries no code. It is noted here and not
duplicated as an entry above.

## S0 — the FE↔Java surface-parity leg

### Why this exists

Three facts, each fine alone, compose into one specific hole:

1. The `interaction-surface` gate parses `new Surface(...)` declarations out of
   `modules/app-observability/src/main/java/io/justsearch/app/observability/surface/CoreSurfaceCatalog.java`
   **only** (`governance/interaction-surfaces.v1.json` `scan`). Its FE leg scans for a second
   `registerViewFactory` mount, and its `feMirror` is `coreInteractionShapes.ts` — a *shape*
   mirror, not a surface/audience mirror.
2. `check-window-cutover.mjs` keys "the promotion is complete" on
   `registeredAudience(CorePlugin.ts)` plus the marker file (`:79-88`, `:129-130`) — i.e. it reads
   **only** the TypeScript side.
3. `check-surface-composition.mjs` already read *both* files, but only to resolve dangling
   member refs against the merged id set. Its own comment recorded the limit: the RAIL set was
   "Java-only (the only placement this static gate authoritatively knows)".

Composed: **an implementer who flips `audience` in `CorePlugin.ts` alone ships two USER/RAIL
interaction windows, passes every gate, and satisfies the 2026-09-30 forcing function.** The gate
meant to prevent the outcome cannot see the FE registration; the gate enforcing the deadline reads
nothing else. Neither is wrong on its own.

`core.search-v3-surface` has **no entry in `CoreSurfaceCatalog.java` at all** today, so the
`interaction-surface` gate is currently blind to search-v3 on every leg — it is not "allowing" a
second window, it cannot see one. The safety S8 (the flip) depends on is created *by* S8 unless
something arms it earlier. That is what S0 is.

### What shipped

A second leg on `scripts/ci/check-surface-composition.mjs` — a leg on plumbing the gate already
had, not a new gate:

> Any surface id declared in **both** `CorePlugin.ts` and `CoreSurfaceCatalog.java` must agree on
> `audience` and `placement`. A disagreement fails the build.

- **Comment-stripped first**, both sources, using the same technique and for the same reason as
  `check-window-cutover.mjs` `stripComments` — a commented-out or merely discussed registration is
  not a declaration. (This also hardened leg 1's Java parse, which previously matched
  `Placement.*` inside comments; leg 1's result on `main` is byte-identical either way —
  `4 host(s), 7 member(s)` before and after.)
- **One-sided surfaces are out of scope.** FE-only (`core.search-v3-surface`,
  `core.memory-surface`, `core.command-palette`) and Java-only (`core.ask-surface`,
  `core.system-surface`, `core.free-chat-surface`, `core.extract-surface`) ids are not compared:
  there is no second declaration to disagree with. 14 of the 21 ids are compared today.
- **The failure message names both files, both values, and the rationale**, because a gate whose
  message does not explain the hazard gets "fixed" by editing whichever side is nearer.
- The script was refactored to export pure functions (`parseJavaSurfaces`,
  `parseCorePluginSurfaces`, `checkComposition`, `checkParity`, `run`) so both legs are testable
  without a repo on disk. Leg 1's rules and messages are unchanged.

### The pre-existing drift this leg found

Arming the leg immediately surfaced two live disagreements that predate it:

| Surface | `CorePlugin.ts` | `CoreSurfaceCatalog.java` |
|---|---|---|
| `core.health-surface` | `audience: 'OPERATOR'` | `Audience.USER` |
| `core.activity-surface` | `audience: 'OPERATOR'` | `Audience.USER` |

The FE re-declaration wins in the shell (`CorePlugin.ts:45-53` states this for `core.help-surface`
in as many words: "this FE re-declaration would otherwise override the wire's placement"), so the
wire catalog is currently wrong about who can see Health and Activity. `core.logs-surface`, the
third System-hub member, agrees at OPERATOR on both sides — which is what makes these two look
like drift rather than a rule.

**Which side is right is a product decision about who sees Health and Activity, not a gate fix**,
and it is outside S0's scope. So they are recorded rather than silently tolerated, in the script's
`KNOWN_PARITY_DRIFT` ledger, with three properties that keep it from becoming an escape hatch:

1. Each entry **pins the exact pair of values**. The pinned pair warns.
2. **Any change to that pair fails** — settled (delete the stale entry) or drifted further (that is
   new drift, and the ledger does not exempt it).
3. **An entry naming a pair no longer declared in both files fails** as residue.

`core.search-v3-surface` and `core.unified-chat-surface` are deliberately not in the ledger —
those are the ids S0 exists to protect. Logged to the observations inbox as a pre-existing issue.

### Mutation probe — evidence the leg bites

A gate armed slices before the thing it protects is the shape that ships inert, so the bite was
verified against the real tree, not only against fixtures. `core.search-v3-surface` cannot serve as
the probe subject (it has no Java entry), so `core.unified-chat-surface` — the shipped USER/RAIL
window, and the closest analogue of the flip — was mutated on the FE side only:

| # | Mutation (`CorePlugin.ts` only) | Result |
|---|---|---|
| 0 | none | **PASS** (exit 0) — `4 host(s), 7 member(s); 14 surface(s) … checked, 2 recorded pre-existing disagreement(s)` |
| 1 | `audience: 'USER'` → `'DEVELOPER'` | **FAIL** (exit 1) — `core.unified-chat-surface declares audience 'DEVELOPER' in …/CorePlugin.ts but 'USER' in …/CoreSurfaceCatalog.java. … a ONE-SIDED flip ships a broken or duplicated USER window while every other gate stays green …` |
| 2 | `placement: 'RAIL'` → `'DEEPLINK'` | **FAIL** (exit 1) — same message shape, on `placement` |
| 3 | reverted | **PASS** (exit 0), output identical to #0; `git diff` shows no change to `CorePlugin.ts` |

A second probe checked **test precision** rather than gate bite: replacing `stripComments` with the
identity function turned exactly the four comment-handling assertions red
(`FAIL (4 of 28)`) and nothing else — so those tests pass because stripping works, not because the
fixture happened to parse. Reverted.

### Tests

`scripts/ci/check-surface-composition.test.mjs` (new, 28 assertions, the bare-node style of
`check-window-cutover.test.mjs`): parser behaviour; agreement passes; audience mismatch fails;
placement mismatch fails; both fields disagreeing yields one failure per field; a one-sided
search-v3 promotion fails on both fields; FE-only and Java-only surfaces are not compared; four
comment cases (line-commented FE field, block-commented FE field, a commented-out whole FE
registration placed *after* the live one so it cannot pass by overwrite order, commented-out Java
field); the ledger's warn / drifted-further / settled / residue paths; and leg 1's four composition
rules plus the 578-Option-A merged-id-set resolution, so the refactor cannot silently regress them.

### Routing, and one limitation left open

The leg adds a second **subject** to this gate: `CoreSurfaceCatalog.java`. The gate's existing
wiring — the `ui-web-gates` recipe in `governance/consult-register.v1.json`, pushed by the consult
hook on any `modules/ui-web/src/**` edit — only covers the FE side, so a Java-only edit would not
have reached it.

The first attempt added `check-surface-composition` to the CLAUDE.md pre-merge row for
`CoreSurfaceCatalog.java`. **`check-always-loaded-budget` rejected it** — 31 B over CLAUDE.md's
ratchet ceiling — and its failure message names the correct remedy: migrate the addition out to
the consult-register. So the routing lives in a new `surface-catalog-parity` region
(`pathIncludes: ["CoreSurfaceCatalog.java"]`) whose recipe names both this gate and
`--gate surface-altitude`; `regionFor()` was probed against the catalog's real path and resolves
it. `governance/surface-composition.v1.json`'s description was updated to describe both legs
rather than only the first. `check-premerge-table` validates the register's script refs
(50 refs, all resolving) and `check-always-loaded-budget` is green with CLAUDE.md unchanged.

**Open:** neither this gate nor its test is wired into `.github/workflows/ci.yml` — that is the
pre-existing arrangement for this gate and is unchanged here, so the leg is enforced by the
pre-merge/consult path, not by hosted CI. Recorded rather than fixed: adding a gate to CI is a
workflow change with its own review surface, and S8 lists `check-surface-composition` explicitly
in its required-gate set regardless.

## S1 — turn identity and the two-record load

### The corrected premise

The first draft claimed `Sv3Turn` had no backend message identity. That was **refuted** during
review: a record-projected `Sv3Turn.id` already *is* the `ConversationStore` id of the turn's user
message (`FileConversationStore.enrichMessage:213-219` mints it, `loadHistory:159-165` back-fills
`idx-N` for legacy rows, `InteractionThreadController.chatTurn:260-262` uses it verbatim as the
thread-event id, `unifiedThreadProjection.ts` preserves it, `sv3-record.ts` opens each turn on it),
and after 847-S3 `Sv3Turn.recordId` carries it too. So S1 must **not** mint a second, differently
named id field — that would fork the id space in the one place this codebase has kept it single.

Three real gaps remained, and those are what shipped:

### (a) The assistant message id was discarded

`projectSv3RecordTurns` emitted `activity: agent ? turn.activity : []` — for an **ask** turn the
activity list, and with it every assistant item id, was thrown away. The rendering rule behind that
drop is sound (a one-item activity list would be a second way to draw the same paragraph), so the
fix preserves the **id**, not the list: `Building.assistantId` tracks the turn's last assistant
item and is emitted as `Sv3Turn.assistantRecordId`. Last-wins, the same rule `evidence` follows and
for the same reason — the terminal assistant message is the answer.

### (b) One accessor for a turn's two message ids

`sv3TurnMessageIds(turn) → { userMsgId, assistantMsgId }`, reusing 847-S3's `recordId` for the user
half and (a) for the assistant half. No new identity field.

**What it guarantees, and why the rule is provenance rather than a list of ids to reject.**
`GET /api/thread/{id}` interleaves two planes (`InteractionThreadController.java:66-73`): the chat
turns, which ARE conversation-store rows, and every agent run's events, projected read-time from
`AgentRunStore` and written as messages nowhere. The run plane mints its own user messages
(`${runId}:user`, `AgentRunQueryService.java:346-350`), assistant messages
(`${conversationId}:assistant:${stamp}`, `AgentInteractionMapper.java:69`), workflow node outputs
and search events — and `chatTurn` has a fallback of its own for a row with no usable id
(`${conversationId}:chat:${msg.hashCode()}`, `:260-262`). The first draft of this slice filtered
only the last of those, which is a list one entry behind the next event kind anybody adds.

So the accessor reports an id only when **both** hold:

1. **Provenance** — the record opened the turn on a `user` item (`Sv3Turn.recordOpenedByUser`), for
   the user half. The projection opens a turn on whatever arrives before any user item, and a sealed
   first line becomes a `role:"locked"` placeholder that `chatTurn` drops, so a thread can legitimately
   open on a *stored assistant* row — whose id is a perfectly good store UUID and is still not the
   user message a branch point means. Shape alone cannot see that.
2. **The store's own mints** — a UUID from `FileConversationStore.enrichMessage` (`:213-219`) or the
   `idx-N` back-fill `loadHistory` applies on read (`:159-165`). An allowlist rather than a denylist,
   so a new run-plane id family fails CLOSED (an affordance unavailable) instead of mis-targeting.

The same store test is applied while projecting, so a turn's `assistantRecordId` holds the last
**stored** assistant message rather than the last assistant item — on a turn that has both, a plain
last-wins would let the run's projected message displace the addressable one.

A **live** turn is null on both halves by construction: it carries the positional
`${sessionId}#t${n}` handle and `recordId: null` until the record speaks.

`sv3TurnByMessageId(turns, messageId)` is the companion lookup, because every `/history` field names
a MESSAGE while the window renders TURNS.

### (c) The `/history` companion load

The shipped window reads both records at adjacent lines (`UnifiedChatView.ts:2048-2049`); search-v3
read only `GET /api/thread/{id}`. `SearchV3View.refreshHistory` now calls the shared
`resumeConversation` on **session open** (the per-tab restore and a sidebar claim — not per turn:
these are properties of a conversation, and re-asking at every terminal would spend a round trip per
answer), and `applySv3History` parks the result on `Sv3Session.history` as `Sv3SessionHistory`:
`parentSessionId` · `branchPointMessageId` · `parentFirstUserMessage` · `contextFloor` ·
`contextFloorSummary` · `excludedMessageIds` · `excludedSourceIds` · `locked`.

`messages` is deliberately **not** carried: `/history` has a transcript too, and taking it would
give this window a second answer to "what happened in this conversation". The canonical record is
the one authority for that.

**The read does not claim.** `resumeConversation` claimed the app-wide active conversation as a side
effect of any successful read — right for the shipped window's open path, its only caller until now,
and wrong for a companion load: a slow read landing after the reader moved on (to another v3
conversation, to New session, or into the *other* window, which claims the same shared pointer) would
re-point the product at the conversation they walked away from. It now takes `{ claim }`, defaulting
to today's behaviour, and search-v3 passes `claim: false` — it already claims at open. Removing the
side effect beats compensating for it afterwards: a restore-the-pointer heuristic cannot tell a
cross-window claim from its own. A superseded load is still discarded.

**Nothing renders any of it yet.** S2/S3 are its consumers and are the reason it is loaded now
rather than later; S1 stays a substrate slice with no UI to audit.

**Obligation this places on S2/S3:** every one of these fields is mutable by an affordance those
slices ship — setting or clearing a floor, compacting, excluding a message or a source. Each write
must be followed by a re-load, or the window renders a floor the backend no longer holds. The
`history: null` tri-state supports that directly: a lazy re-load is the same call.

### Verification

The design's live probe was re-aimed into unit-level regression tests (`sv3-sessions.test.ts`,
`SearchV3View.record.test.ts`), because static reading settles the id space and does not settle
these two:

1. **Id-vs-index pairing across `role:"locked"` placeholders.** `/history` returns every row it read
   including the placeholder a sealed line becomes (`FileConversationStore.java:149-157`), while
   `chatTurn` returns `null` for every role that is not user/assistant (`:247-259`) — so the two
   arrays differ in length and position, and turns group messages besides. The test constructs the
   skew and asserts the id answer, naming the index answer that would look entirely plausible (the
   first turn's assistant message sits at history position 1). A merge leg asserts the record's
   evidence and message ids land on the turn bearing the matching `recordId` when the record's
   order disagrees with the local one.
2. **The `idx-N` backfill, the run plane, and the `hashCode()` fallback.** `idx-N` is a real store id
   and is reported; a run turn's `${runId}:user` + `${conversationId}:assistant:${stamp}` pair, a
   turn opened on a search event, a turn opened on a stored *assistant* row, and the `:chat:`
   fallback are each constructed and asserted `null`. **The fallback assertion's premise, recorded
   because it is what makes that half a guard rather than a live expectation:** a store-backed
   conversation cannot produce it — `enrichMessage` mints a UUID before every write, `loadHistory`
   back-fills `idx-N` on read, and a sealed line becomes `idx-N-locked`.

Every new case was confirmed to bite by mutation: reverting (a) fails 5; dropping the store-mint
allowlist fails 3; dropping the `recordOpenedByUser` condition fails the stored-assistant-row case
(the one shape alone cannot decide); letting the projection take the last assistant item regardless
of provenance fails the mixed-turn case; disabling the `refreshHistory` calls fails the arrival case;
and restoring the claiming read fails both pointer cases.

Green: `npm run typecheck` clean; `npm run test:unit:run` 419 files / 5227 tests passed. ui-web gate
set + the six kernel gates + `execution-surface` + `register-guard-resolution` pass, except reds
already red on `main` in files this PR does not touch (`check-theme-token-closure`,
`check-accent-as-text`, `strip-token-fallbacks --check`, and `check-controls-a11y`'s
`UnifiedChatView.ts` title-on-disabled finding). One unrelated flake was seen once in a parallel
suite run (`EnvelopeStream.test.ts`'s 70 ms watchdog, green in isolation and on the final run) and
is logged to the observations inbox.

### Deviation from the design's letter

The design put the `resumeConversation` call inside `refreshRecord()`. `refreshRecord` also runs at
every turn terminal (`onDone`, `concludeRun`), which would have made the companion load per-turn;
it is wired to the two session-open sites instead, which is what "lazy, on session open" asks for.

## S2 — the tempdoc-610 context set in Search v3

### What the port is, and what it is not

Tempdoc 610 gave the shipped window five acts over what the NEXT prompt contains: set the
effective-context floor, clear it, compact everything above it into a summary, edit that summary,
and hide a single message from the prompt while it stays in the transcript. All five are live
backend endpoints (`ChatController.java:287-531`) behind five shared store functions
(`state/conversationListStore.ts:575-681`), and search-v3 imported none of them.

So this slice adds **no backend, no request and no second store**: it is the window's affordances
over authorities that already exist, plus the two readouts 610 pairs with them (the context meter
and the shared context inspector). What is genuinely new is the derivation between them —
`views/search-v3/sv3-context.ts`, the pure half — because A's unit is a MESSAGE and this window's
unit is a TURN.

### The unit change, and the two rules it inherits from S1

- **The floor names the turn's QUESTION** (`floorMessageId = messageIds[0]`), so "reset context to
  here" keeps the turn the reader pointed at *in* context. A turn the record opened on a stored
  assistant row falls back to that row, which is still a store message the endpoint accepts.
- **An exclusion hides BOTH of a turn's messages.** Leaving the question in the prompt while
  dropping the answer would send the model a question it never answered. A turn counts as hidden
  when *either* is excluded — a half-excluded turn is one the prompt is missing part of, and
  reporting it as fully present would be the more comfortable lie.

Both run on S1's accessor and on nothing else:

1. **Ids come from `sv3TurnMessageIds` only.** A turn reports an id only when the conversation store
   minted it, so an affordance can never be pointed at an agent-run-plane id (`${runId}:user`,
   `${conversationId}:assistant:${stamp}`) that `{floorMessageId}` and
   `POST …/messages/{id}/exclude` would reject. **A turn that names no store message renders no ⋯
   trigger at all** — the honest null, not a control that fails when pressed. That is agent turns
   and live turns, and it is asserted for both.
2. **Message → turn resolution is by id** (`sv3TurnByMessageId`), never by position: `/history`
   counts rows `/api/thread` never emits, so a floor resolved by index would attach to a
   neighbouring turn and look entirely plausible on screen.

### The F4 obligation, discharged

S1 recorded it: *"every one of these fields is mutable by an affordance S2/S3 ship — each write must
be followed by a re-load, or the window renders a floor the backend no longer holds."*

Every act routes through one tail (`settleContextWrite`): **re-load `/history`, then report a
refusal**. The reload is UNCONDITIONAL — a partially-applied bulk exclusion is exactly when the
window's own idea of the ledger is least trustworthy — and nothing is patched locally, so the
backend stays the single authority for the floor, the summary and the ledger. Seven of the fifteen
integration cases fail when that one `await` is removed (mutation-probed).

### Where the affordances live

| Surface | What it carries |
|---|---|
| The turn's tail, in the transcript | A ⋯ `jf-control` opening the product's ONE context-menu primitive, with the four per-turn acts. It **rests** rather than hiding until hover: L14 allows exactly one thing in the row to yield (the copy action, 818 §6b), and hiding otherwise-unreachable capabilities behind a pointer would make them discoverable by accident only. |
| The floor divider, above the floor turn | The two forms of the boundary (rewind vs compacted), the summary disclosure, its editor, and Restore. |
| `jf-sv3-context-bar`, between the transcript and the composer | The occupancy meter (the shared `projectContextHorizon`, so the product's two context meters cannot disagree about what 80% means) and the hidden-turn aggregate with its bulk undo. It renders NOTHING when the conversation reported no occupancy and hides no turn. |
| The shell's context inspector | Opened from the meter; the projection drops out-of-context and hidden turns, so what it lists and what the transcript dims are one derivation. |

Every control this slice adds is born on **`jf-control`** (parity-ledger row 11) rather than adding
more hand-rolled buttons for S6 to convert.

### One defect the critical-analysis pass caught

The summary editor first closed on the SAVE PRESS. That discards the reader's correction exactly
when it is hardest to reproduce — the write was refused. It now answers to the store: the editor
closes when the saved text comes back from `/history`, clears when the summary changes underneath it
(another conversation, a re-compaction, a restore), and otherwise stays open with the correction in
it while the toast names the act that failed. Asserted, and mutation-probed.

### Deviations from the design's letter

1. **Source-exclusion is not here.** The parity ledger puts the source-exclusion ledger +
   `SourcesPane` in **row 8, slice S6**; S2's row is row 7 — the five message-side functions.
   `excludedSourceIds` is loaded (S1) and rendered by S6.
2. **The meter is stored per CONVERSATION** (`Sv3Session.contextUsage`), not per window as the
   shipped window keeps it. A window-level reading would follow the reader into a conversation whose
   prompt it never measured. It is fed by the `done` terminal, which this window was already
   receiving and discarding (`sv3-ask.ts` registered no `onDone` handler at all).

### Verification

- `npm run typecheck` clean; `npm run test:unit:run` **427 files / 5378 tests** pass.
- The ui-web gate set + the six kernel gates + `execution-surface` + `register-guard-resolution`
  pass, except reds already red on `main` in files this PR does not touch
  (`check-theme-token-closure` + `strip-token-fallbacks --check` on `RecentsMenu.ts` /
  `ActionLedgerView.ts`, `check-accent-as-text` on `ActionLedgerView.ts` — the first two named in
  `expected-state.v1.json` — and `check-controls-a11y`'s `UnifiedChatView.ts:2137` finding).
  `gen-component-vocabulary` was regenerated for the new `jf-sv3-context-bar` tag.
- **Mutation probes** (each reverted): dropping the post-write `/history` reload fails 7 cases;
  removing the store-id gate on the ⋯ trigger fails the agent-turn case; excluding only one of a
  turn's two messages fails 2; flooring on the answer instead of the question fails 3; letting the
  inspector ignore the floor fails 2; dropping the `done`-payload occupancy read fails 2; closing
  the summary editor on the press fails the refused-edit case.
- New tests: `sv3-context.test.ts` (13 cases, the pure derivation) and
  `SearchV3View.context.test.ts` (15 cases, the five acts round-tripping against a stateful fake
  backend holding the 610 endpoints). Every case fails before this slice — the window imported none
  of the five store functions and rendered no context affordance.
- **Not verified live.** This is FE-only work against a fake backend that mirrors the five
  endpoints; a dev-stack pass over a real conversation (set a floor, compact, exclude, reload,
  confirm `GET …/history` agrees) is the design's S2 gate row and is left for the live leg.
