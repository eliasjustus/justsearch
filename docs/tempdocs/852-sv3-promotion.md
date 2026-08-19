# 852 — Promoting Search v3 to the product's chat window

```
status:  PARTIALLY IMPLEMENTED — rev 2 design (adversarially reviewed, APPROVE-WITH-AMENDMENTS).
         S1 (turn identity + the /history companion load) is IMPLEMENTED by this PR.
         S0 and S2-S11 remain PENDING.
created: 2026-08-19
related: 847 (citation correctness — S1-S3 shipped in #488; 852-S1 is sequenced strictly behind
              847-S3, which owns `Sv3Turn.recordId` and the identity-keyed merge),
         848 (reasoning parity), 846 (markdown substrate), 849 (evidence reader),
         822 (the Search v3 window itself: Phases F1-F10), 610 (the context set),
         513 (stable message ids + branch), 629/734 (the conversation-store lock)
```

The program's shape (slices, parity ledger, the flip and its sweep) lives in the rev-2 design
document. This tempdoc records what each slice actually shipped, and is the durable half.

| Slice | What | Status |
|---|---|---|
| S0 | FE↔Java surface-parity leg on `check-surface-composition`; record-attribute hydration lives in `components/chat/` | PENDING |
| **S1** | **Turn identity + the `/history` companion load. No UI.** | **IMPLEMENTED (this PR)** |
| S2 | The tempdoc-610 context set (floor / clear / compact / summary-edit / message-exclude) | PENDING |
| S3 | Branch + version pager + edit/retry/resend + cascade-aware delete | PENDING |
| S4-S7 | Composer tier control, retrieve tier, `jf-control` adoption, flip prerequisites | PENDING |
| S8-S11 | The flip, the sweep, the marker, the renames | PENDING |

---

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
half and (a) for the assistant half. No new identity field. Both halves are honestly `null` when
there is no id an endpoint would accept:

- a **live** turn still carries the positional `${sessionId}#t${n}` handle and has `recordId: null`,
  so an affordance needing a backend id is unavailable until the record arrives, and says so;
- a thread event carrying the **synthesised** `${conversationId}:chat:${msg.hashCode()}` id
  (`InteractionThreadController.java:260-262`) names a message that exists in no store — reporting
  it would hand an affordance a key the backend is guaranteed to reject.

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

A superseded load is discarded **and** the shared active-conversation pointer is restored —
`resumeConversation` claims that pointer as a side effect of a successful read
(`conversationListStore.ts:529`), so a slow load for a conversation the reader has already left
would otherwise re-point the product at the one they walked away from.

**Nothing renders any of it yet.** S2/S3 are its consumers and are the reason it is loaded now
rather than later; S1 stays a substrate slice with no UI to audit.

### Verification

The design's live probe was re-aimed into unit-level regression tests (`sv3-sessions.test.ts`,
`SearchV3View.record.test.ts`), because static reading settles the id space and does not settle
these two:

1. **Id-vs-index pairing across `role:"locked"` placeholders.** `/history` returns every row it read
   including the placeholder a sealed line becomes (`FileConversationStore.java:149-157`), while
   `chatTurn` returns `null` for every role that is not user/assistant (`:247-259`) — so the two
   arrays differ in length and position, and turns group messages besides. The test constructs the
   skew and asserts the id answer, naming the index answer that would look entirely plausible
   (`m1` sits at history position 1 and belongs to the FIRST turn). A merge leg asserts the
   record's evidence and message ids land on the turn bearing the matching `recordId` when the
   record's order disagrees with the local one.
2. **The `idx-N` backfill and the `hashCode()` fallback shapes.** `idx-N` is a real store id and is
   reported; the synthesised shape is not. **The assertion's premise, recorded because it is what
   makes the second half a guard rather than a live expectation:** a store-backed conversation
   cannot produce the fallback — `enrichMessage` mints a UUID before every write, `loadHistory`
   back-fills `idx-N` on read, and a sealed line becomes `idx-N-locked`. It is reachable only for a
   thread event that did not come from the conversation store.

Both were confirmed by mutation: reverting (a) fails 5 of the new cases, dropping the synthesised-id
filter fails the guard, and disabling the `refreshHistory` calls fails the view-level case.

Green: `npm run typecheck` clean; `npm run test:unit:run` 5221 passed (one unrelated pre-existing
flake in `EnvelopeStream.test.ts`'s 70 ms watchdog, green in isolation). ui-web gate set + the six
kernel gates + `execution-surface` + `register-guard-resolution` pass, except three reds already
red on `main` and in files this PR does not touch (`check-theme-token-closure`,
`check-accent-as-text`, `strip-token-fallbacks --check`, plus `check-controls-a11y`'s
`UnifiedChatView.ts:2137` finding).

### Deviation from the design's letter

The design put the `resumeConversation` call inside `refreshRecord()`. `refreshRecord` also runs at
every turn terminal (`onDone`, `concludeRun`), which would have made the companion load per-turn;
it is wired to the two session-open sites instead, which is what "lazy, on session open" asks for.
