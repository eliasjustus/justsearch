---
number: 863
title: The delegate turn is missing from the answer plane — `recordsToThread` as a declared capability
status: IMPLEMENTED — slice A (PR #550) and slice B (#542) both built; awaiting merge. Charter premise
  CORRECTED by source + live evidence (§1); the layer decision is SERVER-SIDE (§4.A) as the fix, with
  the FE merge rule repaired as a distinct correctness defect (§4.B) — both, doing two different jobs.
  Decisions taken during implementation are recorded in §9, including three defects the design did not
  anticipate and one gap it leaves open (§8.4). The live leg (§7 A.8) is NOT done.
created: 2026-08-25
updated: 2026-08-25
scheduling: 863 implements BEFORE 865, serial — both edit `AgentInteractionMapper`'s `done` case and
  the evidence seam that hangs off it.
related: 859 (sv3 live findings — §5 C-persistence is the open half this closes), 561 P-A/P-B (the
  two-plane thread; `recordsToThread`), 847 (record reconciliation + `reconcileEvidence`'s "ask the
  field's own question" rule), 848 (reasoning on the record), 852 (the window cutover), 528/530/533
  (the merged 859 PRs this builds on), 629 (the conversation-store lock), 610 (context floor /
  effective context), 865 (serialised behind this one)
---

## 0. One-paragraph summary

A delegate (agent-tier) turn is durable, but it is durable **only in the action plane**
(`AgentRunStore`). The conversation record — the answer plane every store-backed consumer reads —
holds nothing for it. `GET /api/thread/{id}` papers over this by synthesising a `USER_MESSAGE` from
the run's meta at read time, which is why Search v3 shows the reader's prompt correctly and why the
charter's "the question disappears" symptom does **not** reproduce live. What does reproduce, measured
live, is the half the synthesis cannot reach: `/api/chat/conversations/{id}/history` returns
`{"messages":[]}`, so the legacy window renders an empty transcript for a delegate conversation, and
rename is withheld (`storeBacked: false`). The fix is at the shape-capability layer the registry
already names: `ConversationShape.recordsToThread()` is *derived* from
`executionMode == SUBSTRATE_DRIVEN`, and its own javadoc says to promote it to a declared component
when a shape needs to override it. `core.agent-run` is that shape. Separately and independently,
`applySv3Record`'s `reconcile` takes `question` from a record turn that reports it was **not** opened
by a user item — an unsound merge rule six FE consumers sit downstream of. Both are in scope; neither
substitutes for the other.

## 1. The charter's premise, corrected at source

The charter stated: *a delegate dispatch persists no user message, so the record's thread for that turn
opens with `question: ''`, and on the terminal refresh `reconcile` blanks the reader's own prompt.*

Half of that is exactly right, and the other half is the part that matters.

**True.** `ConversationEngine.dispatchShapeDriven`
(`modules/app-services/src/main/java/io/justsearch/app/services/conversation/ConversationEngine.java:248-262`)
calls the runner and nothing else. The `ShapeRunner` for `core.agent-run` is `ToolIteratingShapeRunner`,
which references no `ConversationStore` and appends no message. The substrate-driven path does
(`ConversationEngine.java:378-387` user turn, `:494-504` assistant turn). So a delegate run writes
**zero** rows to the conversation store.

**False as stated.** The *record* is not missing the question. `AgentLoopService.java:520` persists the
run's messages into the run meta (`AgentRunStore.startRun` → `meta.messages`, `AgentRunStore.java:176`),
and `AgentRunQueryService.threadEvents`
(`modules/app-agent/src/main/java/io/justsearch/agent/AgentRunQueryService.java:377-388`) synthesises,
at read time, a `USER_MESSAGE` event with id `<runId>:user` carrying the full prompt. The FE sends the
prompt in exactly the shape that survives this
(`modules/ui-web/src/shell-v0/controllers/AgentSessionController.ts:1739` —
`messages: [{ role: 'user', content: message }]`, conversation id at `:1751`). So
`projectSv3RecordTurns` opens the turn on a real user item, with the real question.

This is why the live re-check found the sv3 symptom **not reproducing**: after a record refresh and a
full reload, the delegate turn kept the reader's full prompt.

> **On provenance (F-0).** The charter attributed this hypothesis to "859 §9(c)". Tempdoc 859 has no
> §9 — it runs §0-§8 — and shipped code cites the same phantom section
> (`sv3-record.evidenceProjection.test.ts:196`, `SearchV3View.delegateEvidence.test.ts`). Rather than
> supersede a section that cannot be quoted, **this tempdoc rests its correction on the source reading
> above plus the live re-check, and on nothing else.** The supersession holds on that evidence alone.
> The phantom citations are a separate stale-reference issue, logged, not fixed here.

**What is measured, live, and open:** `GET /api/chat/conversations/{id}/history` returns
`{"messages": []}` for a delegate conversation — deliberately so, per `ChatController.java:401-410`,
which states "a run-backed conversation therefore answers here with an empty message list, which is the
true thing: it has no store messages." The legacy window's transcript *is* that array —
`UnifiedChatView.loadConversation` sets `this.thread = resumed.messages.map(...)`
(`modules/ui-web/src/shell-v0/views/UnifiedChatView.ts:2062-2068`) — and the live DOM had zero
transcript messages. That is 859 §5's **C-persistence**, still open, and it is a defect of the answer
plane, not of the record projection.

## 2. What is actually broken

Two defects, of different kinds, which the charter treated as one.

### P1 — the delegate turn is absent from the answer plane (live-measured)

| Consumer | Reads | Today, for a delegate conversation |
|---|---|---|
| Legacy window transcript | `/history` via `resumeConversation` (`UnifiedChatView.ts:2062`) | empty transcript; prompt absent from the DOM (measured) |
| `#533`'s lifted reasoning read | the same store messages | nothing to read |
| **Rename** (the only `storeBacked` gate in the FE) | a `ConversationStore` session | withheld — `Sv3SessionRow.ts:575` (start rename), `:590` (F2), `:739` (the affordance's render) |
| Sidebar row + label | `AgentRunStore.listConversations` → `derivePreview` | **works**, via a row #528 had to synthesise because the store index has no entry |
| Multi-turn context seeding | `loadEffectiveContext`, reached only when `sessionIdFor(shape, body) != null` (`ConversationEngine.java:318-327`) | not seeded — a *delta* only for PERSISTENT shapes carrying a `sessionId`; the EPHEMERAL ask path seeds nothing either way. Not a motivation for this design; recorded so nobody promotes it into one. |

**Correction carried from review (A-6).** `ChatController.java:280-283` says every per-row action
except discard — "rename, branch, context-floor, compact, exclude" — writes to a store session. That
is backend prose about *why* the flag exists. In `modules/ui-web` the flag gates **rename only** (the
three sites above); branch, context-floor, compact and exclude are gated elsewhere, on message ids.
So A's user-visible list-side gain is **rename**, plus whatever the id-gated actions gain from the
turn's ids becoming store ids (§4.A.4). Claiming five actions would overstate the payoff.

#528 is the honest workaround for the index half and stays correct for the runs that will always be
run-plane-only (standalone runs, background runs). It is not a fix for the record half.

### P2 — `reconcile` takes a fact from a side that says it does not have it (latent)

`applySv3Record`'s `reconcile`
(`modules/ui-web/src/shell-v0/views/search-v3/sv3-sessions.ts:929-955`) spreads the recorded turn first
and then overrides, field by field, everything the record cannot know: `evidence` (via
`reconcileEvidence`), `status`/`detail` for a halt, `standaloneQuestion`, `reasoning`, `durationMs`,
`modelLabel`, `disposition`, and the turn's own `id`. `question` is not in that list, so a record turn
carrying no question overwrites the reader's prompt with `''`.

The record turn already reports whether a user item opened it — `Sv3Turn.recordOpenedByUser`, set at
`sv3-record.ts:268` (true) / `:260` (false), carried at `:346`, **readable today with zero plumbing**.
The rule is unsound because it consults neither that flag nor anything else.

P2 does not manifest today because P1's read-time synthesis supplies the user item. It still bites for
every record turn no user item opens, a class the projector itself names (`sv3-record.ts:236-238`): a
run whose meta carries no `role:"user"` message (a workflow or background run joined to the
conversation, a search-only run), or one whose checkpointed array no longer opens with one. Six FE
consumers sit downstream:

- `projectSv3RecordTurns` → the rendered question bubble (`sv3-record.ts:348`).
- Retry: `SearchV3View.ts:1473-1474` refuses when `question === ''` — a **silent no-op**, no error.
- `branchInto`'s opening-question preview (`SearchV3View.ts:1508`) falls back to `''`.
- Markdown export writes an **empty `### user` block** above the real answer (`SearchV3View.ts:2368-2372`).
- The context-floor inspector **drops the turn's user entry** (`sv3-context.ts:290-293`).
- (Unaffected, recorded for honesty: `ConversationHistory` and the sidebar label read the backend's
  `firstUserMessage`, not this field.)

Per *structural defects don't need repeat incidents*: "it does not currently manifest" is not a reason
to defer a rule that is wrong in kind. It is a reason not to call it the headline.

## 3. The seam this belongs to (and the note the repo left for it)

`ConversationShape.recordsToThread()`
(`modules/app-agent-api/src/main/java/io/justsearch/agent/api/registry/ConversationShape.java:119-135`)
answers "are this shape's answer-plane turns on the canonical conversation record?". It is *derived*:

```java
return audience == Audience.USER && executionMode == ExecutionMode.SUBSTRATE_DRIVEN;
```

with a javadoc stating the current position ("agent / shape-driven shapes record via `AgentRunStore`
instead") and its own exit condition:

> This is intentionally a derivation rather than a stored slot — no shape needs to override it today
> (YAGNI / C-018); promote it to an explicit component if and when one does.

`core.agent-run` is that shape. It is `Audience.USER`, `PersistenceMode.PERSISTENT`, its turns are the
reader's conversation — and it is `SHAPE_DRIVEN`, which is a statement about *who drives the loop*, not
about where the turn belongs.

**Corroboration (A-5).** The class javadoc at `ConversationShape.java:55-65` already describes the
promoted world as if it existed: *"the 13-arg constructor lets a shape override it explicitly."* There
is no 13-arg constructor — the record has **12** components (`id, presentation, audience, provenance,
executionMode, iterationMode, persistenceMode, promptContributorIds, contextInjectorIds,
streamConsumerIds, iterationControllerId, eventSchema`). The prose describes the override this tempdoc
adds. That is not a licence, but it is evidence the derivation was always understood as provisional.

## 4. The design

**Decision: server-side is the fix; the FE merge rule is repaired as its own correctness defect.**
Both, with a clear division of labour — not "fix plus belt".

### A. Server: promote `recordsToThread`, and make the shape-driven dispatch honour it

#### A.1 The capability

Promote `recordsToThread` from a derived accessor to a **declared 13th record component**, defaulting
every existing shape to exactly what the derivation returns today, and set it **true** on
`core.agent-run`. No other shape's behaviour changes. The javadoc's retirement note and its phantom
"13-arg constructor" sentence go with it (§5).

**Wire probe, not reasoning (Part 4).** `GET /api/registry/shapes`
(`modules/ui/src/main/java/io/justsearch/ui/api/RegistryController.java:354-360`) serialises
`ConversationShape` objects straight into a live Jackson envelope (`writeEnvelope(ctx, "registry-shape",
"ConversationShape", all)`). A record *component* is serialised where a bare no-arg accessor named
`recordsToThread()` (no `get`/`is` prefix) plausibly is not — so the promotion may **add a key to a
live wire envelope**. Do not settle this by reasoning about Jackson: capture the endpoint's response
before and after and diff the keys. If a key appears, it is a wire addition and goes through the wire
gate (`--gate wire`) and whatever consumer contract covers `registry-shape`; if not, record the probe
result so the next reader does not re-litigate it.

#### A.2 The write (both dispatch modes)

The write key is already resolved by one helper pair the engine trusts enough to gate its locked-store
refusal on (`persistenceKey` / `sessionIdFor` / `threadRecordId`, `ConversationEngine.java:191-198`,
`:905-933`). For a `recordsToThread` shape-driven shape the engine appends the **clean user turn**
before `runner.run`, and the **assistant turn** at the terminal `done` observed on the sink the engine
itself owns — so there is one store-write site, not two, and `ToolIteratingShapeRunner` stays
untouched.

- `threadUserMessage` currently reads `question|prompt|message|text` (`ConversationEngine.java:940-948`);
  the agent body carries `messages`. Extend the helper to fall back to the last `role:"user"` entry of
  `body.messages` rather than changing the wire contract.
- `assistantMessage(text)` (`ConversationEngine.java:890-895`) needs the agent's `finalResponse` from
  the `done` payload as its text — the shape-driven path accumulates no `finalText` of its own.

#### A.3 The stamp, and how it reaches the two suppression sites

Suppression is required, not optional: `InteractionThreadController.handleGet` merges both planes with
**no dedup** (`InteractionThreadController.java:64-77`), and in `projectSv3RecordTurns` *every* user
item opens a turn (`sv3-record.ts:267-270`) — so without it, one delegate turn becomes two, one holding
the question and one holding the activity.

**A-1 — the route to `startRun`.** `ShapeRunner.run(Map body, Audience audience, Consumer<SseEvent> sink)`
(`ShapeRunner.java:42`, implemented at `ToolIteratingShapeRunner.java:95`) receives **no**
`ConversationShape`, so the runner cannot ask `recordsToThread()`. `AgentRunStore.startRun` does receive
the `AgentRequest` (`AgentLoopService.java:520`). **Decision: carry the stamp as a new `AgentRequest`
component**, set by the engine when it dispatches a `recordsToThread` shape-driven shape, and persisted
by `startRun` beside `conversationId`. Rationale: `AgentRequest` is already the dispatch-time input
record that carries exactly this kind of fact (`conversationId`, `autonomyLevel`, `effort` are all
engine/FE-supplied policy inputs the run must persist), and `startRun` already reads it. The cost is
real and must be paid in-slice, not deferred: `AgentRequest` is a 10-component record with **six**
back-compat constructors, and adding an 11th component means every one of them delegates with the new
component defaulted `false`. Rejected alternative: changing `ShapeRunner.run`'s signature to pass the
shape — it touches every runner and every test double to serve one shape, and puts a store-policy
decision inside runners that have no business making it.

**A-2 — the route to the mapper.** `AgentInteractionMapper.fromRunEvents(records, conversationId)` is a
**2-arg static** (`AgentInteractionMapper.java:294`) with ~17 call sites, and the meta holding the stamp
is read one frame up at `AgentRunQueryService.java:364` and never passed down. **Decision: filter at the
`threadEvents` call site** (`AgentRunQueryService.java:377-393`), which already holds both the meta and
the mapper's output — drop the terminal `ASSISTANT_MESSAGE` there for a stamped run, exactly where the
`<runId>:user` synthesis is already skipped. Rejected alternative: threading the flag through
`fromRunEvents` — it changes a 2-arg static used in ~17 places to serve one caller that already has the
information. The filter must key on the event's identity (the terminal assistant message the `done`
case mints), not on "the last assistant event", so a multi-node workflow run is untouched.

**Forward-only, and no backfill.** A run written before the stamp carries no flag, keeps both
synthesised events, and renders exactly as it does today. Minting store rows for messages that were
never store messages would invent addressable fork points that never existed. Standalone runs (no
`conversationId`) and background runs (`/api/presence/run`) never record to the answer plane, so the
synthesis stays theirs unchanged.

#### A.4 The evidence-parity problem the suppression creates (A-3 — the sharpest amendment)

Suppressing the run-plane `done` **drops three attributes the store plane does not currently carry**.
Measured against source:

| Attribute | Run plane (`AgentInteractionMapper.java:65-94`) | Store plane (`persistedAssistant` `:955-984` → `chatTurn` `:264-286`) |
|---|---|---|
| `sources` | carried | **absent** |
| `citations` | carried | carried |
| `citationScorer` | carried | **absent** |
| `disposition` | carried | **absent** |
| `calibration` | not produced by agent `done` | carried (RAG payloads only) |
| `claimMatches` | not produced by agent `done` | carried (RAG payloads only) |

So "the evidence projects identically from either plane" had **no mechanism behind it** as originally
written — the parity test would have failed, or worse, passed against a fixture. The design therefore
specifies:

- Extend `persistedAssistant` to carry `sources`, `citationScorer` and `disposition` from the done
  entries when present, on the same "present ⇒ carried, absent ⇒ no key" rule the existing three
  follow (absence stays load-bearing — 859 §4's pre-stamp allowance depends on it).
- Extend `chatTurn` (`InteractionThreadController.java:264-286`) to surface those three onto the wire
  event, so `recordEvidenceOf` sees them from the store plane exactly as it does from the run plane.
- State honestly that `calibration` and `claimMatches` stay **absent for agent payloads** — the agent
  `done` does not produce them. Absent is the true answer; a zero would not be.

This is what makes the parity test in §7 a real acceptance criterion rather than a wish.

#### A.5 The sweep (same slice — a retiree's fingerprints, not a follow-up)

- **A-4 — supersede the shipped rejection of this design.** `ChatController.java:260-262` records:
  *"Rejected alternative: having agent runs mint a `ConversationStore` row. The decisive argument is the
  DOUBLE-RENDER — `InteractionThreadController` already merges both planes, so agent-authored store
  messages would render every delegate turn twice."* That argument is **correct and unanswered until
  A.3 lands**; A.3's stamp is the answer to it. The comment must be rewritten in this PR to say so —
  left standing it is false authority pointing at a design that shipped.
- **A-5 — two more stale sites.** `ChatController.java:401-410` (the `handleLoadHistory` javadoc:
  "a run-backed conversation therefore answers here with an empty message list, which is the true
  thing") is falsified for stamped conversations and must be narrowed to pre-stamp/run-only ones.
  `ConversationShape.java:55-65` (the phantom "13-arg constructor") becomes true and must be corrected
  to describe the component that now exists.
- **A-6 — the list join.** A stamped conversation is store-backed, so `hasStoreSession` dedups it and
  `runBackedRows` stops synthesising a row; `storeBacked` goes true and **rename** becomes available.
  Pre-stamp conversations keep the synthesised row. Both paths covered, not just the new one.
- **A-7 — discard is already correct; demote to a regression test.** `handleDeleteConversation`
  (`ChatController.java:751-757`) deletes the store session and *then* the conversation's agent runs,
  **unconditionally** — it does not branch on `storeBacked`. So A does not create a delete gap. A test
  pins that a stamped conversation still loses both planes; nothing needs changing.
- **A-8 — the affordance flip has three parts, not one.**
  1. **Agent turn, Edit/Retry: stays hidden, for a new reason.** Retry re-sends through the *ask* tier;
     on an agent turn that is a silent tier conversion. The refusal survives but is re-gated on **the
     turn's kind** instead of on the accident that its ids are not store ids.
  2. **The ordinary ask turn *after* a delegate turn: legitimately becomes VISIBLE.** Today
     `SearchV3View.branch.test.ts:899-902` withholds Edit there because the message it would fork
     *before* is a run-plane id. After A that predecessor is a store message, the fork point is real,
     and the affordance should appear. This is an **improvement**, and the test's second assertion
     flips to expect it — a conscious flip, like T11.
  3. **Branch-here on an agent turn: decide, don't inherit.** `sv3-branch.ts:71-73` gives
     Branch-to-new-thread *this turn's own answer* as `fromMsgId`. After A the delegate turn's assistant
     record id is a store id, so Branch appears on agent turns — and branching re-dispatches through the
     ask tier, the **same silent tier conversion** part 1 keeps Retry refused for. **Decision: withhold
     Branch on an agent turn too, on the same kind-based gate**, so the two acts answer the same
     question the same way. Re-dispatching an agent turn *to the agent tier* is the better long-term
     answer and is deliberately §8's open question, not a silent inclusion here.
- **A-9 — the locked store: this closes a pre-existing silent drop.** Today a delegate dispatch against
  a locked conversation store is **accepted-and-dropped on the run plane too**: `startRun`'s
  `writeMeta` throws (the key is locked) and the failure is swallowed by the bare
  `catch (Exception e) { LOG.warn(...) }` at `AgentRunStore.java:192-194`, so the run executes with no
  durable record and the reader is told nothing. After A the delegate dispatch has a conversation-store
  write key, so `wouldDiscardWhileLocked` answers true and the dispatch controller refuses with **423**
  — the reader learns the store is locked instead of losing the run. Stated as a **behaviour change
  that fixes a silent drop**, tested explicitly. (The pre-existing drop is logged to the inbox as its
  own finding.)
- **A-10 — two store-write consequences of making the agent path a writer.**
  1. **`shapeId` relabelling.** `FileConversationStore.updateMeta:752` writes
     `meta.put("shapeId", shapeId)` **unconditionally on every append**. After A, one delegate turn in a
     mixed conversation relabels that conversation `core.free-chat` → `core.agent-run`, which changes
     what `UnifiedChatView` re-tags the whole transcript as (it maps every message to one resolved
     shape, `UnifiedChatView.ts:2043-2049,2062-2068`) and flips which `?shapeId=` filter the row answers
     to. **Decision to make in-slice** (not to discover): first-wins (only write when absent), a
     conditional write, or accept-and-document. Note the same relabel is already reachable today for a
     mixed conversation whose last append came from a different substrate shape — so this is a
     pre-existing sharp edge that A makes routine, and the decision should say which of those it is
     fixing.
  2. **Ask-tier token cost, stated as a cost.** After A, `loadEffectiveContext` will feed the delegate
     turn — prompt plus the full `finalResponse`, which for an agent run can be long — into the prompts
     of **subsequent turns of shapes that seed from history**. That is the correct behaviour (the turn
     really is part of the conversation), but the direction is *more* prompt tokens per subsequent turn,
     not a free win. The context floor and compaction (610) already exist as the reader's controls; the
     design does not add new ones.

**Rejected alternatives, on evidence.**

- *Append only the user message.* The transcript would show a question with no answer, and any
  store-seeded context would replay the question without its answer. The pair moves together.
- *Teach `/history` (and the other store consumers) to fold the run plane at read time.* Every
  store-plane **write** addresses a message by id (`?fromMsgId=` forks, floor, exclude); synthesised
  run-plane ids are rejected by all of them (`sv3-branch.ts:16`). This re-creates the "affordance that
  404s" problem #528 solved by withholding, and gives each consumer a second read path. It is also what
  `ChatController.java:401-410` already refused, for the same reason.
- *Leave the answer plane empty and fix only the FE.* Does not touch the live-measured defect.

### B. Frontend: `reconcile` must not take a fact the record says it does not hold

`reconcile` gates `question` on **`recordOpenedByUser`**, not on emptiness. The distinction is the whole
point and is the lesson `reconcileEvidence` already recorded (847 F-12): an empty-string test is a
*proxy* for "the record was never told", and proxies are what that function exists to stop using. A
record turn opened by a user item is authoritative for the question even when the two strings differ
(an edited or re-asked prompt); a record turn opened by anything else knows nothing about it.

**T11 flips, and must pass for the right reason.** `sv3-record.evidenceProjection.test.ts:191-230`
today asserts `merged.question` is `''` and says so explicitly as an observed limit. It becomes an
assertion that the reader's prompt survives, plus a **companion case**: a record turn that *is* opened
by a user item with different content must still take the record's question. Without the companion, the
flipped test passes for a wrong reason.

**A-11 — fix T11's stale framing in slice B, so slice A touches the file zero times.** The case is
currently named and premised on the delegate tier (`describe('T11 — an agent conversation with NO user
message …')`, and the body comment "a delegate run persists NO user message" ⇒ the record opens empty).
Slice A falsifies that premise for every stamped run. **Rename the case to the class that survives A** —
a record turn opened by something other than a prompt (a workflow/background run joined to the
conversation, a search-only run, a pre-stamp run) — and rewrite its comment to that class. The phantom
"§9" citation in the same comment goes at the same time.

### Ordering

**B first**, independently landable (small, FE-only, no backend dependency), then **A** as one slice —
its sweep items are consequences of its own change, and splitting them out is the
follow-up-that-never-comes pattern. Program-level: **863 before 865, serial** (both edit the mapper's
`done` case and the evidence seam).

## 5. What this orphans

- The **derivation body** of `ConversationShape.recordsToThread()`, its "promote it to an explicit
  component if and when one does" note, and the class javadoc's phantom "13-arg constructor" sentence
  (`ConversationShape.java:55-65`).
- **`ChatController.java:260-262`** — the shipped rejection of this exact design. Superseded by the
  stamp, and rewritten to say so (A-4).
- **`ChatController.java:401-410`** — the `handleLoadHistory` "empty is the true thing" javadoc, narrowed
  to pre-stamp / run-only conversations (A-5).
- **`ChatController.java:253-258`** and **`AgentRunStore.java:378-383`** — prose asserting "a delegate
  run appends no message" as current truth.
- The `<runId>:user` synthesis and the mapper's terminal `ASSISTANT_MESSAGE` are **narrowed, not
  deleted** — they remain the only record for standalone, background and pre-stamp runs. Stated as a
  narrowing so a surviving call site is not later read as proof the change was not made.
- T11's `OBSERVED LIMIT, pre-existing` comment, its delegate-tier framing, and its phantom §9 citation.
- **Not orphaned:** `sv3-record.ts:236-238`'s comment about a run that recorded no prompt. That class
  survives A and is precisely what B protects.

## 6. Reach

**The principle: a capability must be declared, not derived from an adjacent mechanism it merely
correlates with.**

Third instance of one shape in this subsystem, and the first two are quoted in the code being changed:

- 847 §2.4.4 — `panelSpeaks` gated on a classification that correlated with having evidence.
- 859 §A §3.5 — the `agent` turn kind derived as "not text", which flipped every thinking ask turn; the
  fix states it as the allow-list it always meant. The comment there (`sv3-record.ts:325-331`) already
  writes the rule down: *a fact must be gated on itself, not on a classification that merely correlates.*
- This tempdoc — `recordsToThread` derived from `executionMode`, and `question` taken on an empty-string
  proxy instead of on `recordOpenedByUser`. Both halves of §4 are the same error, once in the registry
  and once in the merge.

That the same error appears in a registry derivation and in a view-layer merge rule, in one change, is
the evidence the principle is not a view-layer idiom.

**Candidate scope, not built now:** other derived predicates on `ConversationShape` (anything computed
from `audience` or `executionMode` rather than declared), and the remaining field rules in `reconcile`
— which are already correct, and are the model to conform to rather than a debt.

**What would show it earning its keep:** the next derived-capability override is caught when the
capability is read, at declaration-review time, rather than as a consumer-visible defect. **Retirement
condition:** if, after `recordsToThread` is promoted, no further derived capability ever needs an
override, the principle was over-generalised from a small sample and should be demoted to a note on
that one field. Deliberately *not* done here: building a general "declared capability" mechanism. One
field is promoted, because one field needs it.

## 7. Plan

### Slice B — the merge rule (frontend only; lands first)

1. `sv3-sessions.ts` `reconcile`: add `question` to the override list, gated on
   `recorded.recordOpenedByUser` — record wins when a user item opened the turn, prior otherwise.
   Comment states the rule by reference to `reconcileEvidence`'s (proxy vs the field's own question).
2. Flip **T11** (`sv3-record.evidenceProjection.test.ts:191-230`) to assert the reader's prompt
   survives; add the **companion case** (record turn opened by a user item with different content still
   wins); **rename the case and rewrite its premise** to the surviving class, and drop the phantom §9
   citation (A-11) — so slice A touches this file zero times.
3. Re-read the five downstream consumers (§2 P2) and confirm none depended on the blank: retry
   (`SearchV3View.ts:1473`), branch preview (`:1508`), export (`:2368`), context-floor list
   (`sv3-context.ts:290`).
4. Verify: `cd modules/ui-web && npm run typecheck && npm run test:unit:run`; the ui-web gate set pushed
   by the consult hook.

**Acceptance:** flipped T11 **and** its companion green; no other sv3 test changed.

### Slice A — the answer plane (server, with the sweep)

1. **Registry.** Promote `recordsToThread` to a declared 13th component; every existing shape declares
   what the derivation returned; `core.agent-run` declares true. **Run the wire probe** on
   `GET /api/registry/shapes` before/after and diff the envelope keys (§4.A.1) — record the result
   either way; if a key appears, take it through `--gate wire`.
2. **Engine.** Resolve the write key for shape-driven dispatch through the existing `persistenceKey`
   helpers; append the clean user turn before `runner.run`; append the assistant turn on the terminal
   `done` observed on the engine-owned sink, via `persistedAssistant`. Extend `threadUserMessage` to
   read `body.messages`' last `role:"user"` entry; feed `assistantMessage` the done payload's
   `finalResponse`.
3. **Evidence parity (A-3).** Extend `persistedAssistant` to carry `sources`, `citationScorer`,
   `disposition` (present ⇒ carried, absent ⇒ no key) and `chatTurn` to surface them on the wire event.
   Document `calibration` / `claimMatches` as honestly absent for agent payloads.
4. **Stamp.** Add the component to `AgentRequest` (updating all six back-compat constructors to default
   it false), set it engine-side at dispatch, persist it in `startRun`; skip the `<runId>:user`
   synthesis **and** the terminal `ASSISTANT_MESSAGE` **at the `threadEvents` call site** for stamped
   runs (A-1, A-2).
5. **Sweep (§4.A.5).** Rewrite `ChatController.java:260-262` (superseded rejection), narrow `:401-410`
   and `:253-258`, narrow `AgentRunStore.java:378-383`, correct `ConversationShape.java:55-65`; re-gate
   Edit/Retry **and Branch-here** on turn kind; flip `SearchV3View.branch.test.ts:899-902`'s second
   assertion to expect a visible Edit; decide and implement the `shapeId`-relabel policy
   (`FileConversationStore:752`).
6. **Tests** — each able to fail for one reason:
   - `/api/thread` for a stamped delegate run contains **exactly one** user item and **exactly one**
     assistant item (the double-render regression A.3 exists to prevent — and the direct answer to the
     superseded `ChatController:260-262` argument).
   - A pre-stamp run's thread is byte-identical to today's (forward-only, no backfill).
   - `/history` for a stamped delegate conversation returns the user turn **and** the assistant turn.
   - **Evidence parity:** a delegate answer's `sources`, `citations`, `citationScorer` and `disposition`
     project identically through `recordEvidenceOf` from the store plane and from the run plane;
     `calibration`/`claimMatches` absent from both.
   - List join: a stamped conversation appears once, store-backed, with rename available; a pre-stamp
     one appears once, run-backed.
   - **Regression (A-7):** discard removes both the store session and the run directories for a stamped
     conversation.
   - Locked store: a delegate dispatch is refused **423** rather than executing with a swallowed
     `writeMeta` failure (A-9).
   - Affordances: agent turn offers neither Edit/Retry nor Branch (kind-gated); the ask turn following a
     delegate turn offers Edit.
   - `shapeId` relabel: whichever policy was chosen, asserted directly on a mixed conversation.
7. **Verify:** `./gradlew.bat spotlessApply`, then `./gradlew.bat build -x test`, affected module tests,
   then the full unit suite (multi-module change). Gates: `--gate wire` if the probe shows a wire
   addition or any `contracts/**` file is touched; `check-intent-tier-coverage` if
   `CoreConversationShapeCatalog.java` or `UnifiedChatView.ts` is touched.
8. **Live leg** (dev stack, when free — an acceptance criterion, not an extra; `ai-offline-isnt-a-wall`):
   dispatch a delegate run from Search v3, then confirm (a) the **legacy window** renders that
   conversation's transcript with prompt and answer both in the DOM; (b) Search v3 renders **exactly
   one** turn for it, with its evidence, before and after a full reload; (c) the sidebar lists it once,
   with rename available; (d) a pre-stamp delegate conversation still renders as it did.

**Acceptance for A:** every test above green, the live leg's four checks confirmed, and no surviving
prose in the repo asserting that a delegate run records nothing — including the superseded rejection.

## 8. Open questions

1. **Re-dispatching an agent turn to the agent tier.** §4.A.5 A-8 withholds Edit, Retry *and* Branch on
   an agent turn because all three re-dispatch through the ask tier. The better answer is a re-dispatch
   that preserves the tier — its own charter, not a silent inclusion here.
2. **Pre-stamp conversations stay legacy-invisible.** No backfill, by design (§4.A.3). If that residue
   matters, the honest remedy is a one-time, clearly-labelled import — a decision, not a cleanup.
3. ~~**The `shapeId`-relabel policy**~~ — **decided in-slice: first-wins.** See §9.2.
4. **The store-plane turn does not get the run plane's reasoning fold.** New, opened by the
   implementation (§9.4). Suppressing the run-plane terminal answer moves its trailing thinking; the
   blocks are re-homed onto the run's last surviving event, and a delegate run that called no tool has
   no surviving event to receive them, so its final block does not reach the thread on reload. The
   `reasoning_chunk` records are still on disk — a projection gap, not data loss. Closing it means
   giving the store-plane turn the run plane's fold output, which is a cross-plane merge; the shape of
   that merge (and whether the block should render above or below its re-homed carrier) is the
   question, and it is the same question §4.A.5 F4's known inversion points at.

## 9. Implementation record (slice A, PR #550)

Written at implementation time, so §7's plan is not read as the account of what shipped.

### 9.1 The wire probe (§4.A.1) — measured, and it IS a wire addition

`GET /api/registry/shapes` gained a `recordsToThread` key: a record *component* serialises where the
bare `recordsToThread()` accessor did not. The probe is
`RegistryControllerTest.shapesEnvelopeKeySetAfterRecordsToThreadPromotion`, which asserts the
envelope's per-entry key set against the twelve pre-863 component names written out verbatim, so it
fails if the answer ever changes in either direction. No `contracts/**` file is involved — that tree is
the gRPC Head↔Worker contract — so `--gate wire` had nothing to say; the envelope's only consumer
contract is the FE mirror `modules/ui-web/src/api/types/conversation-shape.ts`, updated in the same PR.

### 9.2 The `shapeId` relabel (A-10.1) — FIRST WINS, and it fixes the pre-existing edge

`FileConversationStore.updateMeta` overwrote `shapeId` on **every** append, so the stored fact was
really "the shape of the most recent turn". `listSessions(shapeId, …)` filters rows on it and
`UnifiedChatView` re-tags a whole transcript with the one shape it resolves, so a single turn in
another mode silently relabelled everything before it. A conversation's shape is the shape that
**opened** it: stable, legible, and the same answer on every later append. This is the *fix* option,
not the *stop-making-it-routine* option — the edge already existed for a mixed conversation whose last
append came from a different substrate shape, and 863 only made it common.

Blank counts as undeclared (F5): `branchFrom` seeds a child with
`parentMeta.getOrDefault("shapeId", "")`, so an absent-only guard would freeze a branch of a pre-field
session at `""` forever.

### 9.3 The per-turn shape declaration (F1) — a defect the design did not see

§4.A.5 A-8 re-gates Edit/Retry/Branch on the turn's **kind**, and the FE derived kind from
`activity.some(tool|note)` (`sv3-record.ts`). That correlates with the tier until it does not: a
delegate run that calls **no tool** records no activity, and after A-2's suppression contributes no
run-plane events either — so it projected as an ordinary ask turn, and the three affordances appeared
on it pointing at real store ids. Exactly the silent tier conversion A-8 exists to refuse, reached
through the one shape of delegate run the inference cannot see.

Fixed at the root rather than at the gate: the record now declares the shape that dispatched each turn
(`FileConversationStore` persists the caller's `shapeId` per message, `chatTurn` surfaces it,
`projectSv3RecordTurns` reads it). The session's own `shapeId` cannot answer this — it is first-wins
for the whole conversation (§9.2) — so the fact belongs on the turn. The activity inference survives
only as the fallback for records that genuinely cannot say: pre-863 rows and run-plane-only turns.
Same principle as `recordsToThread` itself and as 847's `panelSpeaks` (§6): gate a fact on itself.

### 9.4 Suppression is keyed on the duplicate EXISTING (F2), not on the stamp alone

The stamp is written at `startRun`; the answer is appended at the terminal `done`. Keyed on the stamp
alone, a store that becomes locked or unwritable **mid-run** left a stamped run whose answer reached
neither plane — the run's own answer erased by a deduplication against a duplicate that was never
written, with a `WARN` as the only trace (`green-masked-destructive`). The engine therefore stamps the
`runId` onto each answer it records, `InteractionThreadController` — the one place both planes are
visible — collects the run ids the answer plane actually holds, and `AgentRunQueryService` suppresses
only for those. This also self-heals every run that already failed that way, with no backfill. The
USER synthesis stays keyed on the stamp alone, correctly: its append happens *before* the run starts.

A second instance of the same class turned up while testing it: the engine forwarded the terminal
`done` to the sink **before** recording it, and `AgentSseWriter.writeOrEvict` throws by design to drop
a disconnected observer — so closing the tab during a long run cost the record its answer. The durable
write now happens first and does not depend on an audience.

### 9.5 The locked-store refusal is per-route (F3)

A-9's "the dispatch is refused 423" holds for `POST /api/chat/dispatch` and `POST /api/chat/runs`,
which ask `wouldDiscardWhileLocked` before committing an SSE status. `POST /api/chat/agent` — still the
FE's fallback path — committed SSE headers *before* parsing the body and never asked, so post-863 its
unguarded user-turn append would have thrown into an already-committed stream and reported a generic
`BAD_REQUEST`. It now asks the same question at the one point a status is still settable.
