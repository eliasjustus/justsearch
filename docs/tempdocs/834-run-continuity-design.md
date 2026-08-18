# 834 — Run continuity: one observation substrate, two execution semantics

```
status: DESIGN — implementation-ready
created: 2026-08-14
updated: 2026-08-14
revision: 3 — rev 1 was returned NEEDS REDESIGN (§1 placement, §7-S1 foundation
  slice); rev 2 redid both and folded in 19 corrections; rev 3 answers probe P1
  (POST-managed SSE is available), reverses the endpoint split on auth grounds,
  scopes the atomic-subscribe fix honestly, and adds the implementation handoff
  (§11). Deltas in §10.
related: 833 (theorization — W1/W2/W3 are the subject of this doc), 436 (the
  universal SSE envelope substrate this design projects from), 577 (§2.14 Root I
  — the agent run as an observed entity), 585 (§D — cursor reconnect +
  StateSnapshot primer), 604 (heartbeat + FE watchdog), 564 (record → JSON
  Schema → TS/Zod wire projection), 561 (agent lifecycle projection)
```

Design for tempdoc 833's W1 (generalize run observation to all conversational shapes),
W2 (live-run enumeration + startup reconciliation) and W3 (StateSnapshot enrichment).

Every `file:line` was verified against the tree this doc was written from.

---

## 0. The thesis

**One shared OBSERVATION substrate; two EXECUTION semantics.**

Shared, for every run of every shape: monotonic sequence, bounded replay, listener
fan-out, observer count, heartbeat, cursor reattach, disconnect detection.

Different, per shape family:

| | agent / workflow (stepped loop) | ask / summarize / dispatch (one-shot pipeline) |
|---|---|---|
| control points | approval / budget / context gates, steer, autonomy | none |
| zero observers | may **PARK** (posture-graded, `AgentSession.java:695-701`) | **keep going and persist** — never park |
| durable ledger | `AgentRunStore` meta + `RunEventStore` events.ndjson | `ConversationStore` messages |
| recovery after restart | resume from checkpoint | re-ask |

The failure to guard against is a unification that flattens column two into column one.
Rev 1 guarded this with a javadoc ("empty by construction") on a uniform `setPark`; that is
the flattening failure wearing a comment. §3.4 makes it structural.

---

## 1. Placement

Rev 1 specified a new hub, registry, sequence authority, ring buffer, subscription handle
and heartbeat in `app-agent-api`. **All six already exist**, two packages over,
production-proven. This section is the inventory rev 1 should have opened with, and the
projection-vs-fork decision that follows.

### 1.1 The substrate that already exists

`modules/app-observability/.../stream/` — slice 436's universal SSE substrate:

| capability rev 1 proposed to build | already exists |
|---|---|
| hub-assigned monotonic seq | `SseStreamChannel.nextEnvelope:100-106` (via `StreamSequenceTracker`) |
| bounded ring, oldest-eviction | `FrameHistoryRingBuffer`, `DEFAULT_CAPACITY = 9000` (`:35`) |
| cursor replay | `SseStreamChannel.framesSince:112-114` |
| fallen-off-cursor detection | `oldestRetainedSeq:120-122` + `SseEnvelopeWriter.attemptResume:131-155` (three miss cases) with a **reset + snapshot** answer |
| listener fan-out, evict-on-throw | `SseStreamChannel.publish:83-91` (`listeners.removeIf`) |
| `Subscription` handle | `SseStreamChannel.Subscription:131-134` |
| resume token | `ResumeTokenCodec` (+ property test) |

`modules/ui/.../SseEnvelopeWriter.java` — the per-connection orchestrator over Javalin's
**managed `SseClient`**: `connected` → `?since=` resume-attempt → snapshot → subscribe →
heartbeat scheduling (`:208-210`) → **`client.onClose` unsubscribe + heartbeat cancel**
(`:212-217`) → `keepAlive()`. Plus `forceSseHeaders:281-287`.

Topology maturity: **18 production `app.sse` routes** — `ResourceApiModule.java:401,404,407,416,420,424,466,476,479,482,485,493,497,502,506,512`, `routes/InfraRoutes.java:43`,
`routes/RuntimeApiRoutes.java:72`. Javalin 6.7.0 (`gradle/libs.versions.toml:34`).

### 1.2 The three genuine mismatches

1. **Subscribe is not atomic.** `SseStreamChannel.subscribe:125-129` only registers; replay
   is a *separate* caller-side `framesSince` call. `SseEnvelopeWriter` documents the
   consequence as a known limitation (`:49-53`). `RunEventHub` has the property and states
   it (`:25-29`): an event reaches a new subscriber *"either via the replay or via the live
   fan-out, never both, never neither."* Load-bearing for runs, not for catalogs: a catalog
   self-corrects at the next snapshot; a run stream that drops a `chunk` yields a
   permanently corrupted answer. Scope of the fix: §1.3.1.
2. **`StreamId` has no run kind.** `StreamId.java:24` pins
   `^(registry|surface|system):[a-z][a-z0-9-]*$`. Needs a `run` alternative; run ids must be
   letter-initial.
3. **Lifetime and cardinality.** The 18 channels are process-lifetime singletons, one per
   catalog. A run channel is per-instance, N-at-a-time, and must be created, bounded and
   destroyed. No `close()`, no registry. New work either way.

### 1.3 Decision: PROJECTION, not fork

**Run observation is built on `SseStreamChannel` + managed `SseClient`. `RunEventHub` is
deleted, not generalized.**

1. **Atomic subscribe — fixed, and honestly scoped.** `SseEnvelopeWriter.attach:181-220` has
   **two** subscribe paths, and only one is fixable without lock inversion:
   - the **resume path** (`?since=` non-blank → `attemptResume` → `framesSince` → `subscribe`),
     which is pure channel state, and
   - the **no-cursor path** (`:198-204`: `sendReset` then
     `sendSnapshot(snapshotExtras.get())` then subscribe), taken by 17 of the 18 catalog
     routes on every fresh connect. Closing *that* race means invoking a **caller-supplied
     supplier under a channel monitor** — lock inversion across 18 controllers, each of which
     may take its own locks inside `snapshotExtras.get()`.

   So the new `subscribeAndReplay(listener, sinceSeq)` closes the race for the **resume path
   only**. The 17 no-cursor catalog connects keep today's documented behaviour, and this doc
   does not claim a fleet-wide fix. Run streams get the property unconditionally because
   §1.6 makes "absent cursor ⇒ replay from 0" a protocol requirement — a run stream never
   takes the no-cursor path.

   **Lock cost, named.** The channel is lock-free today: listeners are
   `ConcurrentHashMap.newKeySet()` (`SseStreamChannel.java:44`) and only
   `FrameHistoryRingBuffer` synchronizes, per method (`:56,61,78,96`). Some streams run at
   ~30 fps. So `subscribeAndReplay` uses a per-channel `ReentrantReadWriteLock`: `publish`
   takes the **read** lock (uncontended read-lock acquisition, once per frame, on top of the
   ring's existing monitor), `subscribeAndReplay` takes the **write** lock once per
   connection and holds it only while snapshotting the frames to replay — never across
   socket writes (§2, two-phase handoff). If P8 shows the read-lock acquire is measurable at
   30 fps, the fallback is a publish-generation counter with a retry loop, which needs no
   lock on the publish path.
2. **Typed `E`.** The journal carries wire-projected frames — the `(name, payload)` pair from
   the single authority `AgentEventPayloads`. Publishing raw `AgentEvent` into a
   Jackson-serialized envelope would bypass it and re-create the three-way drift that class
   exists to prevent (`AgentEventPayloads.java:11-17`). AG-UI therefore gains a map-input
   overload — see §6.5, which does not pretend this is free.
3. **Per-run vs per-catalog.** The only structural difference is lifetime and cardinality
   (§1.2.3), a *registry* concern. A registry around the existing channel is strictly less
   code than one around a clone.

**Consequence: `RunEventHub` is deleted.** `AgentSession` loses `eventHub` (`:74`) and
`eventHub()` (`:671-673`); it keeps `observerCount()` (`:676-678`) re-pointed at an injected
`IntSupplier` and gains an injected `Consumer<AgentEvent>` publish sink. Both JDK types, so
**`app-agent` gains no new module dependency** and stops owning journaling. Full sweep list
in §7-S3b.

### 1.4 Where it lives

**`modules/app-observability`, package `io.justsearch.app.observability.stream.run`.**

Beside `SseStreamChannel`. Dependencies check out: `app-observability` depends on
`app-agent-api` (`:16`) and `app-api` (`:21`) but **not** `app-agent`; both `app-services`
(`:22`) and `ui` (`:70`) already depend on it. Acyclic, and **no new edge at all** because
§1.3 removes app-agent from the journaling path.

`app-agent-api` is rejected: its build file declares it a *"pure annotation jar. No databind,
no core"* (`modules/app-agent-api/build.gradle.kts:9-12`). A thread-owning, clock-holding
registry there is a module-posture change rev 1 costed at zero.

### 1.5 The interface

```java
package io.justsearch.app.observability.stream.run;

public record RunId(String value) { public StreamId streamId(); }   // run:<value>, letter-initial
public record RunDescriptor(String shapeId, String conversationId, long startedAtEpochMs) {}
public record RunChannelPolicy(int maxFrames, long maxBytes, boolean parkable) {}

public final class RunChannelRegistry {
  public RunChannel open(RunId id, RunDescriptor d, RunChannelPolicy policy);
  public Optional<RunChannel> find(RunId id);
  public List<RunChannel> live();
  /** Terminal: refuse publishes, keep the ring readable for `linger`, then drop. */
  public void retire(RunId id, Duration linger);
}

public sealed interface RunChannel permits SteppedRunChannel, OneShotRunChannel {
  RunId id(); RunDescriptor descriptor(); SseStreamChannel channel();
  int observerCount();
  Optional<ParkState> park();                 // OneShotRunChannel: empty, structurally
  Optional<RunStateSnapshot> snapshot();
  SseStreamChannel.Subscription observe(Consumer<SseEnvelope> listener, long sinceSeq);
}
/** Only a stepped run can park. There is no setPark on the one-shot type. */
public non-sealed interface SteppedRunChannel extends RunChannel {
  void setPark(ParkState park);
  void setSnapshotSupplier(Supplier<RunStateSnapshot> supplier);
}
public non-sealed interface OneShotRunChannel extends RunChannel {}

public record ParkState(Kind kind, long sinceEpochMs, String detail) {
  public enum Kind { APPROVAL, BUDGET, CONTEXT, UNOBSERVED }
}
```

### 1.6 Endpoint topology — reversed on auth grounds

**Probe P1 is answered, and it reverses rev 2's decision.**

`io.javalin.http.sse.SseHandler` has a **public** constructor
`SseHandler(Consumer<SseClient>)` and `implements io.javalin.http.Handler` (javap against
the pinned `javalin-6.7.0.jar`). So `app.post(path, new SseHandler(consumer))` yields a fully
managed `SseClient` — with `onClose` — **on a POST route carrying a body**. Rev 2 asserted
managed SSE was GET-only, inferred from all 18 call sites using `app.sse(...)`, and built a
start/observe endpoint split on that inference. The split was therefore a **choice presented
as a constraint**. Re-decided on merits:

**Auth settles it.** `ApiSecurityFilters` enforces the session token only for
`TOKEN_REQUIRED_METHODS = Set.of("POST", "PUT", "DELETE")` (`:44`) and returns early for
`OPTIONS` and `GET` (`:396-402`, comment: *"Always allow OPTIONS (CORS preflight) and GET
(read-only)"*). A GET run-stream family would therefore ship **unauthenticated**: the journal
carries prompts, answers, retrieved passage text and tool arguments, and a GET
`/api/chat/runs/live` would dispense the very runIds needed to fetch them. Loopback-only
(Hard Invariant #2) is not a trust boundary here — the session token exists precisely because
other local processes are not trusted.

Rev 2's three merits for the split, re-weighed:

| merit | survives under POST? |
|---|---|
| uniform attach path | **yes** — creating stream and reattach are both POST managed SSE through one writer |
| runId known before/independent of the stream | **mostly** — becomes an out-of-band `run_started` **lifecycle** frame (sequenced, not retained, no sealed permit — §3.2), not a JSON body |
| `EventSource`-compatible GET | **no — and it was never reachable.** `EventSource` cannot set headers, so a GET stream is either unauthenticated or unusable by `EventSource`. The merit was illusory once auth is designed in. |

The split also *created* a problem the single-route shape does not have: a window in which a
run exists with no observer and no record (rev 2's own blocking item — a reattach to a
retired conversational runId would find nothing, because §4 keeps the journal ephemeral).

**Decision: no split. All streaming is POST managed SSE.**

```
POST /api/chat/runs                       → managed SSE; creates the run AND observes it
POST /api/chat/runs/{runId}/observe       → managed SSE; additional / reattaching observer
GET  /api/chat/runs/live                  → enumeration (§5) — REQUIRES a filter change
```

Two protocol requirements, not passing notes:

- **Absent cursor means replay from 0, never snapshot-only.** `SseEnvelopeWriter.attach:196-204`
  calls `attemptResume` only when the token is non-blank and otherwise sends a snapshot.
  For run streams that would silently discard everything published before the observer
  connected — the exact loss the journal exists to prevent. `RunStreamWriter` always replays
  from `sinceSeq` (default 0). This is also what keeps run streams on the atomic path (§1.3.1).
- **`POST /observe` on an unknown or retired runId** answers **404 with a typed body**
  `{runId, reason: "unknown" | "retired", recordHint}`, not a 200 with an empty stream.
  `recordHint` names where the record lives: for agent runs `"/api/chat/sessions/{id}"`
  (backed by `events.ndjson`), for conversational runs `"/api/chat/conversations/{id}"` (the
  persisted transcript — the journal is ephemeral by §4, so there is no event record and the
  client must be told that rather than shown an empty stream). A 200 + `replay_truncated` is
  rejected: it says "you missed part" when the truth is "this run is over, read the record."

**`GET /api/chat/runs/live` needs an authentication change** and does not ship without it:
add a path-scoped token requirement for `/api/chat/runs/**` to `ApiSecurityFilters`'
`app.before` (`:395-410`), before the method check. The FE can comply — `consumeShapeStream`
already sends `SESSION_TOKEN_HEADER` on a `fetch` (`streams.ts:557-560`) and the enumeration
is a `fetch`, not an `EventSource`. **The filter change is a required S4 item, listed as
such.** The alternative — making the enumeration a POST — is rejected: a read should be a GET,
and the filter needs a path-scoped mechanism eventually regardless.

The wire *vocabulary* is unchanged: `SseEnvelopeWriter` emits envelope frames (constant event
name `"frame"`); run streams keep today's bare `event:`/`data:` frames. A sibling
`RunStreamWriter` reuses the same channel, managed client, heartbeat and `onClose` pattern in
run vocabulary — the relationship `MultiplexedSseWriter` already has to the shared channel.
Migrating runs onto the envelope format is a real option (one SSE family; the FE
re-establishment law at `EnvelopeStream.ts:15-21` for free) deliberately not bundled.

**Cursor grammar — one door, not two.** Envelope streams take `?since=<ResumeTokenCodec token>`
(`SseEnvelopeWriter:194`, `attemptResume:131-140` decodes and validates the stream id). A raw
integer under the same parameter name would be a silent grammar fork. Run streams use
**`?sinceSeq=<long>`** — a different parameter name for a different grammar. And run streams
**do not emit `id:`**, so `Last-Event-ID` cannot become a second, unvalidated resume channel
alongside `?sinceSeq=`. One resume input, one grammar. (This retires rev 1/rev 2's
`Last-Event-ID` story entirely; P7 confirms nothing produces it today.)

Existing POST routes (`/api/chat/ask`, `/api/chat/dispatch`, `/api/chat/agent`,
`/api/chat/agent/{id}/attach`) remain during migration — they are the old journal, which is
why S3 can split (§7).

### 1.7 Execution stays on the initiating thread

With the creating call *being* the first stream (§1.6), `engine.run` stays synchronous on the
Jetty handler thread, exactly as today (`AgentController:182-192`, `ChatController:165`). A
run survives its client disconnecting because the handler thread keeps running and write
failures are absorbed.

This is a direct saving from reversing the split. Rev 2 required an executor move (and
mis-attributed it to S4 when its own staging put the substrate in S3), which dragged in pool
sizing, error propagation to a not-yet-attached client, and re-ordering the 423 locked-store
gate (`ChatController:152-161`). **All of that is now out of scope.** The cost that remains is
one Jetty thread per live run, including while parked — pre-existing and unchanged.

An executor becomes *required* only if "start a run without observing it" (a background ask)
is ever wanted. Named as the trigger, not built.

---

## 2. Memory: bounds that cohere

Rev 1's numbers did not: at an honest ~200 B retained per small frame, `maxFrames = 4000` is
unreachable under 1 MiB (the frame axis becomes decoration), and one `rag.citations` frame
carrying passage text can evict most of a narrative ring, making truncation the norm for RAG.

**Two-tier retention.**

- **Narrative ring** — `chunk` / `reasoning_chunk` / progress. `maxFrames = 4000`,
  `maxBytes = 2 MiB` (≈800 KB at 200 B/frame, so both axes are reachable and bytes bind only
  on unusual narrative).
- **Evidence slot** — large replace-only frames (`rag.meta`, `rag.citations`,
  `rag.citation_matches`, tool results with `structuredData`) in a small **keyed latest-wins
  map**, own 4 MiB budget. These are state, not narrative: a reattacher needs the *latest*
  citations, never their history. Segregation stops one 500 KB frame evicting 2500 narrative
  frames.
- Replay order: evidence slot first in original seq order, then the narrative ring. Cursor
  stays coherent.
- **Agent runs**: `maxFrames = 1000` (today's value, `AgentSession.java:74`),
  `maxBytes = 4 MiB`, same segregation for tool results.

`FrameHistoryRingBuffer` already takes a capacity (`:45`); the byte bound and evidence slot
are new, beside it. The sizer measures *retained* bytes (fixed per-frame overhead + payload
string content), not wire size; P2 supplies the constant.

**Truncation stays honest.** When `sinceSeq < oldestRetainedSeq()`, `RunStreamWriter` emits
`replay_truncated {sinceSeq, oldestRetainedSeq}` then the snapshot then the partial window —
the run-vocabulary form of what `attemptResume:144-150` + reset already do for envelopes.

**Replay backpressure.** `RunEventHub.subscribe:73-82` replays *inside* the monitor while
calling the observer, whose terminal action is a blocking socket write; a slow-but-alive
reattacher replaying 4000 frames stalls the generating thread. Designed out via **two-phase
handoff**: under the write lock, snapshot the frames to replay and register the listener in a
*buffering* state; outside the lock, drain the snapshot to the socket, then drain frames
buffered during the drain, then flip to pass-through (flip under the lock, buffer empty).
Preserves never-both-never-neither, which the per-observer-queue alternative only preserves
with extra care.

**Cardinality.** `AgentSessionRegistry.java:48-51` records the assumption in code: *"For a
desktop deployment with 0–1 concurrent sessions this is irrelevant."* Cap total channels at
**32**; drop retired-and-lingering oldest-first; **never drop a live run** — the 33rd
concurrent live run is refused with a typed error. P6 re-derives whether 32 is generous by
two orders of magnitude.

**Terminal linger is two sites, not "one line each."** `AgentLoopService.java:575` removes
from the registry and `:579` immediately closes the hub, while `attachToRun:195-197` refuses
removed sessions. A 60 s linger moves both and must distinguish *retired-but-readable* from
*gone* (§1.6's 404 reasons). `RunChannelRegistry.retire(id, linger)` owns the whole
transition, which is why it is a registry method.

---

## 3. Identity, vocabulary, and the ask-survival guard

### 3.1 Today

| id | minted by | scope | authority |
|---|---|---|---|
| agent `sessionId` | backend at run start | one agent run | `AgentSessionRegistry.java:31`, run directory, `/api/chat/agent/*` |
| conversation `sessionId` | request body, PERSISTENT shapes | a whole conversation | `ConversationEngine.java:273-282` |
| `conversationId` | **frontend** | a whole conversation | thread write key, `ConversationEngine.java:290,412-415`; `sv3-ask.ts:280` |

"sessionId" means *run* on one side and *conversation* on the other; a conversation holds many
runs. Keying runs by `conversationId` breaks on the second question.

### 3.2 Decision

**One namespace, `RunId`, backend-minted.** Agent runs alias it to the existing `sessionId`
(no mapping table — a table is a second authority that drifts; aliasing preserves every URL
and the run directory name). Conversational runs mint `run-<uuid>`; the prefix also satisfies
`StreamId`'s letter-initial slug rule.

**`runId` is delivered as a `run_started` lifecycle frame, first on every run stream.**
Lifecycle frames are sequenced-but-not-retained — exactly the distinction the substrate
already draws (`SseStreamChannel.publish` appends to the ring only for `UPDATE`, `:80-82`;
`nextEnvelope:100-106` consumes a seq without retaining; `SseEnvelopeWriter` sends every
lifecycle frame through it, `:296-298`). **It is not an `AgentEvent`, so it adds no sealed
permit** and avoids the 7+ site cascade rev 1's version would have caused
(`AgentEventPayloads.name()`/`.base()`, `AgentEventTracing:83-84,139`, `AgUiEventTranslator`,
the two conformance `ALL_VARIANTS` lists and their permit-parity assertions,
`AgentRunShape.eventSchema`, regenerated FE handler types).

### 3.3 `session_started` cannot be retired

It is emitted by the workflow shape too (`WorkflowShapeRunner.java:163`, declared in
`WorkflowRunShape.java:49`, special-cased in its swallow list at `:285`), and it is **pinned
in the durable ledger** — asserted in `AgentRunStoreTest.java:52,383`, read by the history
indexer, and present in every existing `events.ndjson` forever. A persisted vocabulary is not
retirable by a wire change.

**Re-scoped:** the FE stops *depending* on it for run identity (it reads `runId` from
`run_started`). The event keeps being emitted and keeps being read from persisted ledgers —
**dual-read, wire-deprecated, never deleted** — documented as such rather than left as an
unexplained survivor.

### 3.4 The ask-survival guard is structural

`RunChannel` is sealed; `setPark` exists only on `SteppedRunChannel` (§1.5). A one-shot run's
handle has no method to park it — the mistake is a compile error, not a review catch.
`RunChannelPolicy.parkable` selects the subtype at `open`.

Plus a regression test stating the law: **an ask run whose observer count reaches zero
mid-generation still reaches `done` and still persists its assistant message.**

### 3.5 N concurrent runs per conversation

Nothing serializes two dispatches on one `conversationId`. `GET /api/chat/runs/live?conversationId=X`
may legitimately return N > 1. **Contract:** a list, ordered by `startedAtEpochMs` descending,
never collapsed. The backend does not serialize runs — turn-taking is a product decision that
belongs in the FE composer where the user's intent lives, not silently in the run substrate.
The FE seam brief must state that "is this conversation answering?" is `runs.length > 0`, and
that presenting N as one is a rendering choice it owns.

---

## 4. Persistence interplay

**Decision: the journal is ephemeral; persistence is unchanged.**

### 4.1 For ONE_SHOT shapes the durability gap is already closed

For `core.rag-ask` (`ONE_SHOT` × `EPHEMERAL`, `RAGAskShape.java:63-65`), `ConversationEngine`
persists the clean user turn *before* the LLM call (`:329-334`, after injectors at `:318`,
before the loop at `:356`), runs to completion regardless of observers, and persists the
complete assistant message with evidence after `onDone` (`:409-415`). A dropped ask loses
nothing; the gap is observability.

### 4.2 Why journaling to disk would be wrong

A durable shape-agnostic ledger already exists — `RunEventStore` (`:38`), used by the agent
path (`AgentRunStore.java:511-518`) and the workflow shape; durable conversational events
belong *there*, and conflating the two forks the ledger. Cost: ~600 appends per answer, each
through the `StoreCipher` seal when encryption is on (`RunEventStore.java:59-63`), for content
already persisted once, complete. And restoring a half-generated answer presents an
interrupted generation as a complete one.

### 4.3 Iterating shapes get a different answer

The "crash leaves an honest gap" property is **ONE_SHOT-only**. The persistence at
`ConversationEngine.java:409-415` sits *inside* the iteration loop (`:356-438`), so a
`WITHIN_TURN_ITERATION` shape — `ExtractShape.java:63`, `HierarchicalSummarizeShape.java:50` —
persists an assistant turn **per iteration**. A crash at iteration 3 of 8 leaves three
persisted turns reading as a finished answer. The same loop runs
`MemoryExtractionConsumer.onDone` (`:90-91`) every iteration, committing memory writes a crash
cannot roll back.

**Turn-open marker**, one field on the conversation record, set before iteration 0. **Cleared
in a `finally`, not at selected exits.** `dispatchSubstrateDriven` has **eight** exits and rev
2 cleared on two — which would have rendered every cleanly-errored run "interrupted", the
inverse dishonesty:

| exit | line |
|---|---|
| injector terminated | `:319-321` |
| AI unavailable | `:347-350` |
| `LlmStreamException` | `:363-366` |
| consumer `onDone` threw | `:376-380` |
| controller `next` threw | `:421-425` |
| `STOP_SUCCESS` | `:428-431` |
| `STOP_ERROR` | `:432-435` |
| iteration hard cap | `:440-442` |

A `finally` around the dispatch body clears it on all eight; only a process death leaves it
set, which is exactly the condition it encodes. (Per-iteration clearing is the alternative and
is weaker — it would mark a run complete after iteration 1 of 8.)

Not in scope: rolling back mid-run memory writes. Recorded as a known limitation with its
`file:line`, because the honest statement is "memory extraction is not transactional across a
crash", not silence.

---

## 5. W2 — enumeration + startup reconciliation

### 5.1 The endpoint

`GET /api/chat/runs/live`, optional `conversationId` / `shapeId` filters. **Ships only with
the `ApiSecurityFilters` path-scoped token change (§1.6).**

```java
// modules/app-api/src/main/java/io/justsearch/app/api/run/LiveRunsResponse.java
public record LiveRunsResponse(List<LiveRunSummary> runs) {}
public record LiveRunSummary(
    String runId, String shapeId, String conversationId,
    String state, ParkSummary park,
    long startedAtEpochMs, long updatedAtEpochMs,
    int observerCount, RunStateSnapshotView snapshot) {}
public record ParkSummary(String kind, long sinceEpochMs, String detail) {}
public record RunStateSnapshotView(
    Integer iteration, Integer budgetRemaining, Integer toolCallsExecuted,
    Integer messageCount, String activeAgentId,
    List<PendingApprovalView> pendingApprovals, String autonomyLevel, ParkSummary park) {}
public record PendingApprovalView(
    String callId, String toolName, String argumentsJson, String risk, String gateBehavior) {}
```

Typed, not `Map<String,Object>` — the 564 chain retired that fail-open hole
(`WireRecordSchemaGenTest.java:107-109`: *"record-backed so the FE retires its fail-open
`.loose()` hand-Zod"*). `arguments` rides as a JSON string for the same reason.

`app-api` cannot depend back on `app-agent-api` (`AgentSessionController.java:90-92`), so the
records are plain and the controller projects with `MAPPER.convertValue`, as
`handleListSessions` already does (`:93-97`). Wire chain: record →
`WireRecordSchemaGenTest.captureOrVerify` (agent precedent `:110-114`) →
`SSOT/schemas/live-runs-response.v1.json` → `gen-wire-schema-types.mjs` → TS + Zod.

No `interrupted` field: an interrupted run is a *persisted* run and never appears in a live
enumeration. Interruption surfaces on the persisted-sessions record (§5.3).

### 5.2 Startup reconciliation

**The problem.** `AgentRunStore.startRun` writes `resumable: true` (`:155`) and `checkpoint`
recomputes it from state (`:204`, via `isResumableState:516-520`). Nothing runs at Head start
to notice the owning process is gone. `LifecycleState`'s javadoc (`:10-11`) claims *"no
orphan-RUNNING / worker-drain hazard"* — true within a process, false across a restart.

**The rule.** For every persisted run whose state is non-terminal
(`!LifecycleState.parse(state).isTerminal()`) and absent from `RunChannelRegistry`, stamp
`interruptedAt`. Idempotent — skip runs already carrying it.

**No `INTERRUPTED` state; do not clear `resumable`.** `state` is the *resume seed*
(`handleResumeSessionStream` replays from the checkpoint it names); overwriting it destroys
what makes the run resumable in order to record that it is not running — two facts, two
fields. `resumable` is genuinely true for these runs. And a new enum constant is a downgrade
hazard: `LifecycleState.parse` maps unknown values to `READY_FOR_LLM` (`:44-52`).

**Four presentation cases.** `WAITING_BUDGET` and `WAITING_CONTEXT` (`LifecycleState.java:23,30`)
are non-terminal but **outside** `isResumableState` (`:516-520`):

| state at crash | `resumable` | honest presentation |
|---|---|---|
| terminal | false | finished; no marker |
| `READY_FOR_LLM` / `AFTER_TOOL_RESULT` | true | "Interrupted when the app closed. Resume." |
| `WAITING_APPROVAL` | true | "Interrupted while waiting for your approval. Resume." |
| `WAITING_BUDGET` / `WAITING_CONTEXT` | **false** | "Interrupted while waiting for your decision about tokens/context. **Cannot be resumed — start a new run from this transcript**" (the fork endpoint, `AgentRoutes.java:77`) |

The fourth row is a real product gap the reconciliation surfaces: those gates are in-memory
futures and no checkpoint records them. Extending `isResumableState` to cover them needs a
checkpoint that can re-park — a separate, larger change, named as the follow-on.

**Where it runs, and the unlock seam that must be built.** Boot: `HeadAssembly` right after
`AgentRunStore` construction (`:424-429`). But with encryption on and the store locked, reads
return empty and writes refuse (`RunEventStore.java:59-63`), so a boot-only pass is a silent
no-op on exactly the encrypted installs. Rev 1 asserted an unlock-listener seam; it does not
exist and must be built on
`DataKeyManager.addListener(BiConsumer<State,State>)` (`modules/app-services/.../encryption/DataKeyManager.java:91-93`).
Two constraints from source:

- `fire(before, state())` runs inside `synchronized unlock()` (`:126-130`; same for
  `setup:109-121`, `recover:134-138`), so **listeners execute under the key monitor**. A
  directory scan there blocks the key lifecycle.
- Therefore the listener only hands off to a single-thread executor; the scan runs
  off-monitor. It must not throw — `fire` swallows (`:100-105`), so a throw would be invisible
  rather than loud.
- Idempotency (above) makes boot + unlock + re-unlock all safe.

### 5.3 Presenting interruption

`GET /api/chat/sessions` → `AgentSessionSummary` gains `interruptedAt` additively, alongside
the `resumable` the panel already reads (`RetrospectivePanel.ts:187-193,629-632`). The four
rows are derivable from `(state, resumable, interruptedAt)` with no inference.

---

## 6. W3 — StateSnapshot enrichment

### 6.1 Why snapshot-not-ring is the recovery law

The ring evicts oldest. A run parked at an approval gate emits nothing while parked — the loop
blocks in `gate.get(APPROVAL_TIMEOUT_SECONDS, ...)` (`AgentToolDispatcher.java:265-271`) — so a
long run reaches that gate having emitted thousands of frames and the `tool_call_pending`
frame carrying the `callId` can be evicted. A reattacher then sees a stopped run with no gate
to answer.

The snapshot is pushed *before* replay (`AgentSessionRegistry.java:199-209`;
`SseEnvelopeWriter.attach:202-206` for envelopes), so it is the one frame guaranteed to arrive.

**The law: every fact required to *act* on a run lives in the snapshot; the ring carries
narrative only.** Falsifiable — any affordance that disappears after the ring wraps violates it.

Corollary: the snapshot is emitted at *every* subscribe, including the creating observer's.

### 6.2 The record

```java
record StateSnapshot(
    int iteration, int budgetRemaining, int toolCallsExecuted, int messageCount,
    String activeAgentId,
    List<PendingApproval> pendingApprovals,  // NEW — empty list, never null
    String autonomyLevel,                    // NEW — AutonomyLevel.name(), never null
    ParkSnapshot park,                       // NEW — nullable: absent = not parked
    TraceContext trace) implements AgentEvent { ... }

record PendingApproval(String callId, String toolName, Map<String,Object> arguments,
                       String risk, String gateBehavior) {}
record ParkSnapshot(String kind, long sinceEpochMs, String detail) {}
```

Sources: `autonomyLevel` is a session field (`AgentSession.java:127-131`); park derives from
`hasBudgetGate():374`, `hasContextGate():426`, non-empty `approvalGates:35`,
`zeroObserverPolicy():695-701`. `pendingApprovals` needs a change: `approvalGates` is
`Map<String, CompletableFuture<Boolean>>` (`:35`) with no tool detail, but the detail exists
one statement earlier — `AgentToolDispatcher.java:261-263` emits
`ToolCallPendingApproval(call.id(), call.toolName(), call.arguments(), risk, gateBehavior)`
immediately before `createApprovalGate(call.id())` at `:265`. So `createApprovalGate` takes the
detail and the map becomes `Map<String, PendingGate>`; `approve`/`reject` (`:315-331`) and the
two bulk `complete(false)` sweeps (`:182`, `:631-632`) follow mechanically.

### 6.3 Compat — it is not Jackson

Both wire and persistence go through hand-written switches: `AgentEventPayloads.name():33-58`
and `.base():66-...` (StateSnapshot arm `:204-211`), with `AgentRunStore` delegating (`:511-518`).

1. **Adding components does not change the wire until `base()` is edited**, and a missing
   *field* is silent (unlike a missing *variant*). Hence a record-component coverage test —
   with three constraints the naive version gets wrong:
   - **exclude the `trace` component** — `base()` deliberately omits it, `withTrace` appends
     it (`:62-64,222-230`); a blanket assertion fails on every variant;
   - **per-variant, not generic** — `ToolCallProposed` expands `call` into three keys, so
     component-name-to-key is not one-to-one across the sealed set. Scope to flat-mapping
     variants, `StateSnapshot` first;
   - key it off a variant list whose permit-parity is already asserted (§6.5).
2. **`Map.of` rejects nulls and caps at 10 pairs.** The arm already null-guards
   `activeAgentId` (`:210`). Rewrite as a `LinkedHashMap` so `park` can be *absent*.
3. **Old `events.ndjson` lacks the new keys.** Absent `pendingApprovals` = **unknown**, never
   **none**; conflating them renders "no approvals pending" for a run that had one.
4. **Arity-change sites** (positional constructor): `AgentEventTracing.java:83-84`,
   `AgentSessionRegistry.java:203-209`, `AgUiEventTranslator.java:58-64` (whose
   `STATE_SNAPSHOT` map also wants the new fields), `AgUiEventTranslatorConformanceTest.java:61`,
   `AgentEventSchemaConformanceTest.java:56`, `AgentRunStoreTest.java:356`. The convenience
   overload (`AgentEvent.java:443-450`) keeps old-arity callers compiling.
5. Naming: `io.justsearch.agent.api.registry.StateSnapshot` (`StateSnapshot.java:29`) is
   unrelated; the new types nest under `AgentEvent`.

### 6.4 Conversational snapshots

`OneShotRunChannel.snapshot()` is empty. A one-shot pipeline has no fact a user can act on. A
phase label is presentation, needs engine hooks, and would be substrate without a consumer.

### 6.5 The AG-UI equivalence gate — and its honest cost

The map-input `AgUiEventTranslator` overload (§1.3.2) is **a second hand-written switch**, not
a mechanical projection: the typed switch renames fields on the way out (e.g.
`ToolExecutionCompleted.result().message()` → `"content"`, `AgUiEventTranslator.java:54-57`),
so the map-input version re-derives each mapping from the payload keys. That is a real drift
surface, which is exactly what makes the equivalence gate load-bearing rather than ceremonial.

**Gate:** for every variant, `translateFromMap(name(e), base(e))` equals `translate(e)`.

Anchor it on **`AgUiEventTranslatorConformanceTest.ALL_VARIANTS:37-61`** with its
`coversEveryPermit:64-71` assertion — **not** `AgentEventSchemaConformanceTest.ALL_VARIANTS:45-56`,
which rev 2 cited in error: that list is built from null/zero-field variants
(`new AgentEvent.AgentProgress(null, null, 0, 0)`, `new AgentEvent.SessionStarted((String) null)`),
so an equivalence test over it would NPE on `ToolCallProposed`/`ToolExecutionCompleted` or pass
vacuously. The AG-UI list carries real payloads (`new ToolCallRequest("id","tool","args")`,
`OperationResult.success("ok")`).

**Add one traced instance.** Every entry in that list uses the convenience constructor, so all
carry `TraceContext.none()` — the trace/runId half of the projection would be untested. Add at
least one variant constructed with a populated `TraceContext`.

---

## 7. Staging

**S1** snapshot enrichment → **S2** reconciliation + unlock seam → **S3a** substrate hardening
→ **S3b** run substrate + endpoints + hub deletion → **S4** enumeration → **S5** FE sweep.

### S1 — StateSnapshot enrichment (W3) · independent

Three components on `AgentEvent.StateSnapshot`; detail-bearing `approvalGates`; `base()`'s arm
becomes a `LinkedHashMap`; the six arity sites (§6.3.4). Additive on the wire, no substrate
dependency.

*Verification:* unit — per-variant coverage test with the trace exclusion; absent-key
tri-state; existing permit-parity assertions. Live — attach to a run parked at an approval
gate; the snapshot alone must carry enough to render and answer the gate.

### S2 — Reconciliation + the unlock seam (W2, persistence half)

`interruptedAt` on `AgentRunStore` meta and `AgentSessionSummary`; the boot pass; the
`DataKeyManager` listener with off-monitor hand-off; the four presentation cases as data; the
iterating-shape turn-open marker with its `finally` (§4.3).

*Verification:* unit — idempotency; **the adverse precondition** (store locked ⇒ boot pass is a
no-op *and* the unlock listener completes it); the listener must not block the key monitor and
must not throw; the marker is cleared on **all eight** exits (a parameterised test over the
eight, not a spot check). Live — start a run, kill the Head, restart, read the sessions list;
repeat with encryption enabled and locked at boot.

### S3a — Substrate hardening · zero run code, ships under the 18 routes

`subscribeAndReplay(listener, sinceSeq)` with the read/write lock and two-phase handoff
(§1.3.1, §2), scoped to the resume path; the `run` kind on `StreamId`; the byte bound and
evidence slot on the retention layer. No run code, no new endpoints, independently revertable.

Rev 2 refused to split S3, arguing that splitting leaves two journals coexisting. **That
objection does not survive rev 2's own §1.6**, which keeps the existing POST routes during
migration — those *are* the old journal. Two journals coexist during the migration either way;
splitting merely makes the substrate change revertable on its own. The reviewer's position is
adopted.

*Verification:* the existing `SseEnvelopeContractTest`, `FrameHistoryRingBufferTest`,
`ResumeTokenCodecPropertyTest` stay green; new concurrency tests for
never-both-never-neither and for publisher non-stall under a slow subscriber; a benchmark on
the ~30 fps read-lock cost (P8).

### S3b — Run substrate + endpoints + hub deletion

`RunChannelRegistry`, sealed `RunChannel`, `RunChannelPolicy`, `ParkState`; `RunStreamWriter`
over managed `SseClient` via `new SseHandler(...)`; `POST /api/chat/runs` +
`POST /api/chat/runs/{id}/observe` with the §1.6 protocol requirements; `run_started` lifecycle
frame; the map-input `AgUiEventTranslator` overload + equivalence gate (§6.5);
**`ChatController` error paths routed through the sink** — today `sseError:123-130` and the
three catch arms `:170-179` write to `ctx` directly, so under a journal a failing run would
terminate invisibly for every non-creating observer.

**`RunEventHub` deletion sweep** — every site, so it is not discovered mid-implementation:

| site | what changes |
|---|---|
| `RunEventHub.java` | deleted |
| `AgentSession.java:74,671-673,676-678` | field + accessor removed; `observerCount()` → injected `IntSupplier`; publish via injected `Consumer<AgentEvent>` |
| `AgentSessionRegistry.java:185-232` | `attachToRun` (both arities) reimplemented on the channel |
| `AgentLoopService.java:639-645` | the two `attachToRun` overrides |
| `AgentLoopService.java:575,579` | registry remove + hub close → `registry.retire(id, linger)` |
| `AgentService.java:121-131` (app-agent-api) | the two default `attachToRun` methods — **public interface**, so this is a contract change |
| `AgentController.java:478,513` | native + AG-UI attach call sites |
| `AgentSseWriter.java:94-149` | the `writeOrEvict` / `evictIfGone` / `SseObserverGoneException` eviction seam — obsolete once `onClose` owns disconnect |
| `SseWriter.SseWriteOutcome.CLIENT_GONE` | its only eviction consumer disappears; keep the enum (`SseWriterTest:18` pins the serialization-vs-disconnect distinction) but re-document |
| `RunEventHubTest.java` | whole file |
| `AgentControllerSseEvictionTest.java` | whole file (`:25,32`) |
| `AgentLoopServiceTest.java:634-709,760` | attach + eviction cases retargeted at the channel |

*Verification:* unit — the §3.4 ask-survival test; the AG-UI equivalence gate; 404 contracts
for unknown vs retired runIds; absent-cursor ⇒ replay-from-0. Live — two concurrent observers
on one ask; reload mid-answer and rejoin; `onClose` fires on tab close; a run retired past its
linger answers 404 with the right `recordHint`.

### S4 — Enumeration (W2, read half)

`GET /api/chat/runs/live`; the typed records; SSOT schema and generated TS/Zod; **the
`ApiSecurityFilters` path-scoped token change (§1.6) — a required item of this slice, not a
follow-up.**

*Verification:* unit — projection fidelity; N>1 runs on one conversation; **a request without
the token header is rejected** (the adverse precondition for the auth change). Live — an ask
and an agent run both enumerate with correct `observerCount`; closing a tab drops the count.

### S5 — FE sweep

Retire `activeRunPointer.ts` as discovery authority; run identity from `run_started`;
`session_started` dual-read and wire-deprecated, **not deleted** (§3.3); label every remaining
reference.

### Verification topology

With managed `SseClient.onClose`, disconnect detection no longer depends on the run producing
output, so a dev-proxy run is not structurally blind. `docs/observations.md:1777` remains true
about the raw writer and about proxy-delayed close notification, so: **before S3b, direct
topology only; after S3b, tests asserting disconnect-detection *latency* still run direct,
tests asserting that detection *happens* may run through the proxy** — subject to P4.

---

## 8. Non-goals, seams, risks

**Non-goals.** Multi-device sync · durable chunk-level journaling · resuming a conversational
run's generation after a crash · transactional rollback of mid-run memory writes (§4.3, named
limitation) · migrating run streams to the envelope wire format (§1.6, a real option,
unbundled) · moving execution off the handler thread (§1.7, trigger named) · closing the
no-cursor subscribe race for the 17 catalog routes (§1.3.1, scoped out with reasons) · park
semantics for one-shot pipelines · frontend design.

**Frontend seams** (named, not designed).

| authority | change | slice |
|---|---|---|
| `shell-v0/controllers/activeRunPointer.ts` | retired as discovery authority | S5 |
| `shell-v0/controllers/AgentSessionController.ts` (`attachToRun:1714`, `onStateSnapshot:1122`) | enriched snapshot; `runId` from `run_started` | S1/S3b |
| `views/search-v3/SearchV3View.ts` (`reattachLiveRun:791-797`) | reattach an *ask* run from the enumeration | S4/S5 |
| `api/streams.ts` (`consumeShapeStream:545`) | `?sinceSeq=`; `replay_truncated`; 404 `recordHint` handling | S3b |
| `api/generated/schema-types/live-runs-response.ts` | new generated type + Zod | S4 |
| `components/RetrospectivePanel.ts:187-193,629-632` | the four interruption rows | S2 |

**Risks.**

- **R1 — S3a lands under 18 production routes.** Mitigated by splitting it out, by the
  resume-only scope, and by the existing contract tests.
- **R2 — the read-lock on `publish`** adds a per-frame acquire to a lock-free path at ~30 fps
  (P8). Fallback: publish-generation counter with retry, no publish-path lock.
- **R3 — the map-input AG-UI translator is a second hand-written switch** (§6.5). The
  equivalence gate is the whole mitigation; if it is weakened, the drift returns.
- **R4 — a coverage gap, not a broken park.** Rev 1 claimed the zero-observer park was
  substantially unreachable. **Wrong for the dominant case:** an actively-streaming run's
  disconnect evicts within milliseconds (chunk publish → `writeOrEvict` →
  `SseObserverGoneException` → `RunEventHub.deliver:99-106` → park at `AgentStepRunner.java:217`),
  and `AgentLoopServiceTest.java:713-780` tests exactly that — its dead socket throws on the
  first delivery, so **the test passes for precisely the reason it claims. No existing park
  test is unsound; none should be rewritten.** The real gap is narrower: a run blocked
  *silent* — approval gate `get(300s)`, a long tool call, first-token wait — publishes nothing
  and cannot notice a disconnect for the whole window. That is a **missing test**, not a wrong
  one. S3b's `onClose` closes the gap; the test guards it.
- **R5 — reconciliation under encryption.** Getting the off-monitor hand-off wrong is
  invisible precisely on encrypted installs. Test the adverse precondition.
- **R6 — the auth change is load-bearing.** If S4 ships without the `ApiSecurityFilters`
  change, the enumeration leaks runIds unauthenticated. Its adverse-precondition test is what
  keeps this from being a comment.
- **R7 — `LifecycleState.parse` maps unknown to `READY_FOR_LLM`** (`:44-52`). Not triggered
  here; it is why the enum route stays rejected.

**On the heartbeat.** Rev 1 proposed switching `AgentController.withHeartbeat` to
`writeOrEvict`. That is broken three ways: the heartbeat task is **not a hub observer** (it
writes from a scheduler thread, outside `RunEventHub.deliver:96-107`, so its throw never
reaches the code that removes a subscriber and `observerCount()` would not drop);
`scheduleAtFixedRate` **suppresses all subsequent executions** once the task throws, silently
killing the heartbeat; and it contradicts `AgentController.java:126-128`, which deliberately
keeps heartbeats out of the replay buffer. Resolved by §3.2's rule — heartbeats are
sequenced-but-not-retained lifecycle frames, so they never occupy a ring slot even at 15 s
cadence on a parked run — and by making disconnect detection `client.onClose`, not a write
result. There is no heartbeat slice.

---

## 9. Probes

**P1 — is managed SSE POST-capable? ANSWERED: yes.** `SseHandler(Consumer<SseClient>)` is
public and `SseHandler implements Handler` (javap, pinned `javalin-6.7.0.jar`), so
`app.post(path, new SseHandler(c))` is managed with `onClose`. Residual to confirm at
implementation: that `SseHandler.handle` gates only on `Accept: text/event-stream` and applies
no method check.

**P2 — retained bytes and frame counts per answer** (sizes §2). Per effort tier: frame counts,
the retained-heap per-frame constant, the largest single frame (expected `rag.citations`).

**P3 — the silent-run disconnect window** (guards R4). With a run blocked at an approval gate,
time-to-detection under the raw writer (expected: never, until the gate times out) versus
managed `onClose` (expected: container-prompt). The delta is S3b's justification in one number.

**P4 — does `onClose` fire through the Vite dev proxy, and with what delay?** Sizes the §7
topology relaxation; if it never fires, the direct-only rule survives S3b.

**P5 — envelope format for run streams.** Deferred by §1.6, not rejected. `EnvelopeStream`
wraps `EventSource` (`:106,112,138`) — GET-only, and §1.6 shows GET is unauthenticated, so
this is now blocked on the auth design too, not just framing. What this design *does* adopt
from that family is its re-establishment law (`:15-21`).

**P6 — observed concurrent-run maximum.** If it is 2, the 32-channel cap and the byte-budget
machinery are theatre and §2 collapses to a frame count.

**P7 — `Last-Event-ID` producers.** Static answer: none — `consumeShapeStream` sets only
content-type and the session token (`streams.ts:557-560`); `attachToRun` passes no headers
(`AgentSessionController.ts:1727-1731`); `AgentController.parseLastEventId:472` always sees
null. §1.6 closes the door by not emitting `id:` at all. Confirm no non-browser client (the
AG-UI route, `AgentRoutes.java:57`) round-trips it.

**P8 — read-lock cost on `publish` at 30 fps** (R2). Benchmark before/after on the busiest
existing channel; if measurable, take the generation-counter fallback.

---

## 10. Delta

### Rev 2 → rev 3

| # | rev 2 | rev 3 |
|---|---|---|
| 1 | Start/observe split, justified as forced by GET-only managed SSE | **P1 answered** — `SseHandler` is POST-capable. Split re-decided on merits and **reversed**: auth is decisive (GET is token-exempt, `ApiSecurityFilters:44,396-402`), and the `EventSource` merit was unreachable once auth is designed in. All streaming is POST managed SSE |
| 2 | Executor move required; §1.7 mis-attributed it to S4 | **Dropped** — the creating call is the first stream, so execution stays on the handler thread. R2 (error propagation) and the 423 reorder go with it |
| 3 | GET endpoints unauthenticated by omission | **Auth designed in**: streams are POST (covered today); `GET /runs/live` requires a path-scoped `ApiSecurityFilters` change, a required S4 item with an adverse-precondition test |
| 4 | "Atomic subscribe fixes all 18 routes" | **Scoped honestly** to the resume path; the no-cursor path would need caller suppliers under a channel monitor (lock inversion across 18 controllers). Lock cost named (channel is lock-free today: `:44`, ring-only `synchronized` at `:56,61,78,96`) with a no-lock fallback |
| 5 | Retired/unknown runId unspecified; absent-cursor a passing note | **Protocol requirements**: 404 + typed `{reason, recordHint}`; absent cursor ⇒ replay from 0 (which also keeps runs on the atomic path) |
| 6 | Turn-open marker cleared on 2 of 8 exits | **`finally`** — all eight enumerated with line numbers; the 2-of-8 version would have marked every cleanly-errored run interrupted |
| 7 | `?since=` reused for a raw int | **`?sinceSeq=`**, and no `id:` emitted — one resume grammar, one door |
| 8 | Equivalence gate on `AgentEventSchemaConformanceTest` | **`AgUiEventTranslatorConformanceTest:37-61,64-71`** — the cited list has null/zero variants and would NPE or pass vacuously. Plus one traced instance (all entries carry `TraceContext.none()`) |
| 9 | Map-input overload described as free | Named as **a second hand-written switch** (`message()` → `"output"` rename), which is what makes the gate load-bearing |
| 10 | S3 monolithic ("splitting leaves two journals") | **S3a / S3b** — the objection does not survive rev 2's own "existing POST routes remain during migration"; those are the old journal |
| 11 | Hub deletion sweep unlisted | **12-row sweep table** in S3b, incl. the `AgentService:121-131` public-interface contract change |
| 12 | — | §11 implementation handoff for S1 and S2 |

### Rev 1 → rev 2 (retained for the record)

Projection onto `SseStreamChannel` instead of a new hub in `app-agent-api`; managed SSE
adopted; the heartbeat slice shown mechanically broken; R4 corrected from "park is broken" to
"a missing silent-run test"; `run_started` demoted out of the sealed permit set;
`session_started` shown unretirable; `ChatController` error paths routed through the sink;
replay backpressure designed out; §4.3 scoped to ONE_SHOT; memory rebuilt as ring + evidence
slot; coverage test constrained (trace excluded, per-variant, arity sites); unlock seam built
on `DataKeyManager:91-93`; the fourth interruption case; linger ownership; `snapshot` typed;
enumeration reordered after the substrate; sealed `RunChannel`; N-runs-per-conversation.

---

## 11. Implementation handoff — S1 and S2

Line map for a fresh implementer. Verify each anchor before editing; line numbers drift.

### S1 — StateSnapshot enrichment

**Edit, in order:**

1. `modules/app-agent-api/.../AgentEvent.java:435-451` — add `pendingApprovals`,
   `autonomyLevel`, `park` before `trace`; add nested `PendingApproval` and `ParkSnapshot`
   records; keep the 5-arg convenience overload (`:443-450`) delegating with
   `List.of()`, `AutonomyLevel.DEFAULT.name()`, `null`.
2. `modules/app-agent-api/.../AgentEventPayloads.java:204-211` — rewrite the arm as a
   `LinkedHashMap`; omit `park` when null; `pendingApprovals` as a list of maps.
3. `modules/app-agent/.../AgentSession.java:35` — `approvalGates` becomes
   `Map<String, PendingGate>`; add a nested `PendingGate(PendingApproval detail,
   CompletableFuture<Boolean> future)`. Follow through `:182`, `:307-312` (signature takes the
   detail), `:315-331`, `:631-632`. Add `pendingApprovals()` returning the details.
4. `modules/app-agent/.../AgentToolDispatcher.java:261-265` — pass the same five values already
   being emitted at `:262-263` into `createApprovalGate`.
5. Park derivation — a private helper on `AgentSession` over `hasBudgetGate():374`,
   `hasContextGate():426`, non-empty `approvalGates`, `zeroObserverPolicy():695-701`.
6. Arity sites: `AgentEventTracing.java:83-84`, `AgentSessionRegistry.java:203-209`
   (populate from the session), `AgUiEventTranslator.java:58-64` (extend the `STATE_SNAPSHOT`
   map), `AgUiEventTranslatorConformanceTest.java:61`,
   `AgentEventSchemaConformanceTest.java:56`, `AgentRunStoreTest.java:356`.

**New test** — `AgentEventPayloadsCoverageTest`: for `StateSnapshot` (and other flat-mapping
variants), reflect `RecordComponent[]`, **skip `trace`**, assert every remaining component name
appears as a key in `base(instance)`.

**Done when:** `./gradlew.bat :modules:app-agent:test :modules:app-agent-api:test
:modules:app-services:test :modules:ui:test` is green and a live attach to a gate-parked run
shows the three new fields.

### S2 — Reconciliation + unlock seam

**Edit, in order:**

1. `modules/app-agent/.../AgentRunStore.java` — `markInterrupted(sessionId)` writing
   `interruptedAt` via the existing `readMeta`/`writeMeta` pair (pattern at `:195-210`); add
   `interruptedAt` to `toSessionSummary:466-478`.
2. `modules/app-api/.../agent/AgentSessionSummary.java` — add the field; re-run
   `WireRecordSchemaGenTest` to regenerate `SSOT/schemas/agent-sessions-response.v1.json`;
   then `node scripts/codegen/gen-wire-schema-types.mjs`.
3. New `AgentRunReconciler` (app-agent) — over `listSessions`, select
   `!LifecycleState.parse(state).isTerminal() && interruptedAt == null`, call
   `markInterrupted`. Pure and idempotent; no registry dependency at this slice (nothing is
   live at boot, and S3b can add the liveness check later).
4. `modules/app-services/.../HeadAssembly.java:424-429` — run it once after `AgentRunStore`
   construction; register a `DataKeyManager.addListener` (`:91-93`) whose body **only**
   submits to a single-thread executor and **cannot throw** (`fire` swallows, `:100-105`).
5. Turn-open marker — `ConversationEngine.dispatchSubstrateDriven`: set before the loop
   (`:356`), clear in a `finally` around the body; the eight exits are tabulated in §4.3.

**New tests:** idempotency (run twice, one stamp); **locked-store adverse precondition** (boot
pass is a no-op, unlock listener completes it); the listener does not block the key monitor
and does not throw; a parameterised test driving **all eight** engine exits and asserting the
marker is clear in every one.

**Done when:** the above are green, and a live kill-restart cycle shows `interruptedAt` on the
sessions list — repeated with encryption enabled and the store locked at boot.

---

## 12. Implementation log — S1 and S2

Landed 2026-08-14 on branch `run-continuity-s1-s2`, based on `origin/main` at `781b1a53`. Scope was
§11's S1 and S2 only; **nothing from S3a/S3b/S4/S5 is in this branch** — no `RunChannelRegistry`, no
`RunStreamWriter`, no endpoint changes, no `RunEventHub` deletion, no `ApiSecurityFilters` change.

### 12.1 S1 — as built

| §11 item | landed | note |
|---|---|---|
| 1. `AgentEvent.StateSnapshot` + nested records | `AgentEvent.java:435-506` | 9 components; `PendingApproval` / `ParkSnapshot` nested but **not** implementing `AgentEvent`, so `getPermittedSubclasses().length` stays 22 and `AgentEventSealedTest:134` needed no bump |
| 2. `base()` arm as `LinkedHashMap` | `AgentEventPayloads.java:204-222` | plus `approvalMap` / `parkMap` helpers at `:226-247` |
| 3. `approvalGates` → `Map<String, PendingGate>` | `AgentSession.java:36-46,192,318-390` | `pendingApprovals()` at `:352`; the two bulk sweeps at `:192` and `:700` follow |
| 4. dispatcher passes the detail | `AgentToolDispatcher.java:265-276` | the same five values the `ToolCallPendingApproval` at `:261-263` already carries |
| 5. park derivation | `AgentSession.parkSnapshot():363` | budget → context → approval → zero-observer, in §11's order |
| 6. arity sites | see 12.2 | |
| new coverage test | `AgentEventPayloadsCoverageTest.java` | 4 tests |

### 12.2 Anchors that had drifted, and one design/handoff conflict

Per §11's own instruction to verify each anchor:

- **`hasBudgetGate()` / `hasContextGate()` do not exist.** The real methods are
  `AgentSession.budgetGateHeld():432` and `contextGateHeld():484`. Used those.
- **`AgentEventSchemaConformanceTest.java:53`** (§11 said `:56`) needed **no edit**: §11 item 1 also
  mandates keeping the 5-arg convenience overload, which is what that list uses, so it compiles
  unchanged. Same for `AgentRunStoreTest.java:356`, which switches on type only. Two of the six
  named arity sites were therefore no-ops — recorded rather than silently skipped.
- **A 6-arg trace-carrying overload was also required.** `AgentEventTracing` and the payload
  conformance test both construct `StateSnapshot(…, TraceContext)`; §11 named only the 5-arg one.
- **`PendingApproval.arguments` is `String`, not `Map<String,Object>`.** §6.2's sketch says
  `Map<String,Object>`, but it is contradicted by the two places that pin the actual value: §6.2's
  own source citation (`call.arguments()`, a `String`) and §11 item 4 ("the same five values already
  being emitted"), plus §5.1's `PendingApprovalView.argumentsJson`. Resolved in favour of the
  source-verified `String`; a `Map` would have required parsing JSON the emitter never parses.
- **`sinceEpochMs` needed a source.** Nothing recorded when a park began, so `PendingGate` carries a
  creation stamp and the budget/context gates each got a `…SinceEpochMs` field. The zero-observer
  park is derived from an observer *count*, not a transition, so it honestly reports `0` ("start
  unknown") rather than inventing `now()`.

### 12.3 Two chains §11 did not mention but the build enforces

Both are real gates, discovered by running them, not by reading:

1. **`AgentRunShape`'s `state_snapshot` `EventDescriptor`** (`AgentRunShape.java:168-186`).
   `AgentEventPayloadConformanceTest.everyEmittedFieldIsDeclared` fails on any emitted-but-undeclared
   key. The three new fields are declared `.asOptional()` — `pendingApprovals` and `autonomyLevel`
   **not** because the producer elides them (it always writes them) but because a legacy
   `events.ndjson` record predates them, which is precisely what makes §6.3.3's absent-means-unknown
   representable on the generated FE type (`pendingApprovals?: PendingApproval[]`).
   Chain run: `-Dupdate.shapes.fixture=true` → `scripts/codegen/shapes.fixture.json` →
   `node scripts/codegen/gen-shape-handlers.mjs` → `core-agent-run.ts`, plus the two hand-written
   leaf interfaces in `shape-handlers/shared.ts`. `check-shape-handler-regen` passes.
2. **`AgentSessionSummary` → JSON Schema → TS/Zod** for S2's `interruptedAt`. The generated Zod is a
   `z.strictObject`, so shipping the field on the wire *without* the regen would have made the FE
   reject every sessions response. Chain run: `:modules:app-api:updateSchemas` →
   `SSOT/schemas/agent-sessions-response.v1.json` → `node scripts/codegen/gen-wire-schema-types.mjs`.

### 12.4 S2 — as built

| §11 item | landed |
|---|---|
| 1. `markInterrupted` + summary field | `AgentRunStore.java:387-421`, `toSessionSummary:512-514` |
| 2. `AgentSessionSummary.interruptedAt` + regen | `AgentSessionSummary.java:24-29`; schema + TS/Zod regenerated |
| 3. `AgentRunReconciler` | new file, `modules/app-agent/.../AgentRunReconciler.java` |
| 4. boot pass + unlock listener | `HeadAssembly.java:498-508`, via `UnlockDeferredScan` |
| 5. turn-open marker with its `finally` | `ConversationEngine.java:256-280` (wrapper + finally), `:377-389` (open), `ConversationStore.setTurnOpen/isTurnOpen`, `FileConversationStore.java:435-462` |
| four presentation cases as data | new `InterruptedRunPresentation` (app-api) |

Two deliberate shapes:

- **`UnlockDeferredScan`** (`app-services/.../encryption/`) exists as a named class rather than an
  inline lambda in `HeadAssembly` because its two properties are only testable if there is something
  to hold. `UnlockDeferredScanTest` asserts that `unlock()` **returns while the scan is still
  running** (a latch proves the key monitor was released — an inline scan would have deadlocked the
  test) and that a **throwing scan** breaks neither `unlock()` nor the *next* scan. Both matter
  because `DataKeyManager.fire` runs listeners inside `synchronized unlock()` and swallows their
  throws, so getting this wrong is invisible exactly where it hurts.
- **The turn-open marker is scoped to iterating shapes.** §4.1 says a ONE_SHOT ask's crash gap is
  already closed (it persists once, complete), and §4.3 scopes the marker to
  `WITHIN_TURN_ITERATION`. `onlyIteratingShapesOpenTheMarker` pins both halves.

One test was rewritten mid-implementation after it failed for a reason that invalidated it: a
"process death" falsifier that threw an `Error` inside the dispatch body. A `finally` runs on a
throw too, so that scenario proved nothing about a killed process. Replaced with
`theMarkerIsSetDuringGeneration`, which observes the marker from inside `StreamConsumer.onDone` — the
moment of maximum exposure — and is the honest falsifier for the eight-exit test's "always cleared".

### 12.5 Verification

- `./gradlew.bat spotlessApply` then `build -x test -PskipWebBuild=true` — **BUILD SUCCESSFUL**.
- `:modules:{app-agent-api,app-agent,app-api,app-services,ui}:test` — **all green**, including the
  four named conformance tests (`AgentEventSealedTest`, `AgentEventPayloadConformanceTest`,
  `AgentEventSchemaConformanceTest`, `AgUiEventTranslatorConformanceTest`) and
  `AgentWireProjectionTest`.
- FE untouched by hand, but proven: `npm run typecheck` clean; `npm run test:unit:run` —
  **421 files / 5140 tests passed**.
- `node scripts/ci/check-shape-handler-regen.mjs` — passes.

**Mutation probe on the new coverage test.** Deleted `snapshot.put("autonomyLevel", …)` from
`AgentEventPayloads.base()`. `AgentEventPayloadsCoverageTest` went **2 failed / 4** —
`everyComponentIsCarried` (the reflective assertion, `:89`) and `unknownIsNotNone` (`:149`). It fails
on the reflective assertion, i.e. for the right reason: a component with no payload key. Restored;
green again. Note that `AgentEventSchemaConformanceTest` and `AgentEventPayloadConformanceTest`
**stayed green** under the mutation — they pin names and declared-superset, so a *dropped* field is
invisible to them. That is the gap §6.3.1 named and this test closes.

### 12.6 Live legs — VERIFIED 2026-08-14

Both of §11's "done when" clauses were run against a real stack (supervised window, owner-cleared).
Stack: this worktree's dist (`distFrom`, provenance `gitHead 8e16febf`), API port 7710, cuda12 GPU
runtime, `Qwen_Qwen3.5-9B-Q4_K_M.gguf`. Every run below used `autonomyLevel: "watch"`, under which
even a LOW-risk read is gated (`inline_confirm`) — the deterministic way to park a run at an
approval gate.

#### S1 live leg — the gate-parked reattach · **VERIFIED**

Run `9b6cdded` parked at a `core_search_index` gate; the observing SSE client was killed; a fresh
`POST /api/chat/agent/{id}/attach` returned this as its **first frame, ahead of the replay**:

```
event: state_snapshot
data: {"iteration":1,"budgetRemaining":6519,"toolCallsExecuted":0,"messageCount":3,
       "activeAgentId":"primary",
       "pendingApprovals":[{"callId":"8bZbOLrIJJTjNvxI4aXJeW9dBjqjRknm",
                            "toolName":"core_search_index",
                            "arguments":"{\"query\":\"invoice\"}",
                            "risk":"low","gateBehavior":"inline_confirm"}],
       "autonomyLevel":"WATCH",
       "park":{"kind":"approval","sinceEpochMs":1786706180851,
               "detail":"8bZbOLrIJJTjNvxI4aXJeW9dBjqjRknm"}}
```

Then `id: 1 session_started`, `id: 2 progress`, … — i.e. the primer precedes the ring replay
entirely, which is the §6.1 ring-independence claim observed rather than argued. All three 834
fields are present and populated; `park.kind` is `approval` with the gate's real start time; the
snapshot carries no `trace` key (correct — the primer is untraced).

**"Render AND answer" (§11's actual wording) was tested, not assumed.** Taking the `callId` from
the snapshot *alone* and POSTing it to `/api/chat/approve` returned `{"status":"approved"}` / HTTP
200, and the run proceeded to `DONE`. So the affordance is genuinely actionable from the snapshot,
not merely displayed.

#### S2 live leg — kill/restart, plain · **VERIFIED**

Two runs existed at kill time, which gave a control pair rather than a single positive:

| run | state at kill | after restart |
|---|---|---|
| `9321a87b` | `LLM_STREAMING` (parked at a gate), `resumable=false` | `interruptedAt=2026-08-14T11:20:54.247Z` — **stamped** |
| `9b6cdded` | `DONE` (terminal) | `interruptedAt=null` — **correctly not stamped** |

The Head was hard-killed (`Stop-Process -Force`) with the run parked; on-disk `meta.json` confirmed
no `interruptedAt` key before the restart. After restart, `state` and `resumable` are **byte-identical
to their pre-kill values** on both rows — the additive property (§5.2) holds in the field, not just in
`doesNotTouchTheResumeSeed`.

**Idempotency across boots:** a second stop/start left the timestamp at `11:20:54.247Z`, unmoved.

#### S2 live leg — encrypted, locked at boot · **VERIFIED (the trap this exists to close)**

On the same (throwaway) dev-data: `POST /api/conversations/encryption/setup`, then a new run
`2da969cd` parked at a gate. Its `meta.json` on disk begins `JSEv1:0ZR6UIx/T3N5F99…` — genuinely
sealed, not a plaintext file in an encrypted store. Head hard-killed while parked.

1. **Restart, locked at boot** (lock-on-launch). `GET /api/chat/sessions` → `{"sessions":[]}`; the
   sealed `meta.json` mtime stayed `11:21:50` (its pre-kill write). So the boot pass **read nothing
   and wrote nothing** — exactly the silent no-op a boot-only design would have shipped as its whole
   answer on encrypted installs.
2. **`POST /api/conversations/encryption/unlock`** → the run was stamped
   `interruptedAt=2026-08-14T11:22:32.333Z`, observed **~36 ms after unlock returned**. That is
   `UnlockDeferredScan` completing the work the boot pass could not — and the 36 ms is also evidence
   the hand-off really is off-monitor and prompt, not a blocked key lifecycle.
3. **Idempotency across the encryption boundary:** the unlock pass left `9321a87b`'s earlier
   `11:20:54.247Z` untouched, and `9b6cdded` (`DONE`) is still `null` after **three boot passes and
   one unlock pass**.

#### Honest limits of the live legs

- **Only two of the four `InterruptedRunPresentation` rows were exercised live**: `FINISHED` (the
  `DONE` run) and `FORK_ONLY`. Every interrupted run above classifies `FORK_ONLY` because the
  checkpoint at gate-park time persists `state="LLM_STREAMING"`, which is outside
  `isResumableState`. `RESUMABLE` / `RESUMABLE_AT_APPROVAL` remain **unit-tier only**
  (`InterruptedRunPresentationTest`); no live run reached them.
- **`LLM_STREAMING` is a persisted state with no `LifecycleState` constant**
  (`AgentStepRunner.java:449`), so `LifecycleState.parse` maps it to `READY_FOR_LLM`. It is
  non-terminal either way, so reconciliation is correct here — but this means design **R7's downgrade
  hazard already exists in the persisted vocabulary**, independently of this slice. Logged to the
  observations inbox; out of scope for S1/S2.
- The GPU runtime had to be provisioned into this worktree
  (`modules/ui/native-bin/llama-server/variants/cuda12`, copied from the main checkout's staged copy
  after `stageLlamaCudaVariant`'s download failed on an SSL handshake). `RuntimeActivationService`
  resolves `variantsRoot` **once at construction**, so the copy only takes effect after a restart.

**Teardown** (verified, not assumed): `quick_health running:false`; no `llama-server.exe`; no
JustSearch java; no dev-runner; 0 listeners on 7710/5173/8080. One `llama-server` was orphaned by the
hard-kill of its parent Head and was reaped explicitly.

Still untouched by design (S5's job): no FE consumer reads the three new snapshot fields or
`interruptedAt` yet — `RetrospectivePanel`'s four interruption rows are §8's S2 row but sit on the FE
seam, and the generated types are the substrate they will bind to.

### 12.7 Critical-analysis pass — two findings, both fixed

Run after the two commits, walking the diff with "what would catch what the tests missed?":

1. **Asymmetric guard on the turn-open marker.** The clearing side (in the `finally`) caught
   `RuntimeException`; the opening side did not. `FileConversationStore.writeMetaAtomic` throws
   `UncheckedIOException`, and sealing throws `KeyLockedException` on a locked store — so a store
   fault would have killed the *run* over a diagnostic marker. Guarded symmetrically
   (`ConversationEngine.java:383-395`), and `openTurnKey` is armed only after the write succeeds, so
   the `finally` never chases a mark that was never made (meta.json is written atomically, so there
   is no partial state to clean up).
2. **`UnlockDeferredScan.awaitQuiescence` was dead code in main.** `UnreferencedCodeTest` (app-launcher
   ArchUnit) failed: the method was referenced only from its test. Fixed at the root rather than
   suppressed — `close()` now drains through it before `shutdownNow()`, so a scan already writing
   finishes instead of being interrupted. Better behaviour AND a real main-code reference.

Also re-checked, no change needed: `AgentRunReconciler` catches `RuntimeException` around
`listSessions`, which covers `CorruptDurableStoreException` (it extends `IllegalStateException`) and
the upcaster's `UnsupportedOperationException` — so one corrupt run directory degrades the pass to a
logged no-op instead of breaking Head boot. `createApprovalGate` has exactly one call site
(`AgentToolDispatcher:268`) and every `approvalGates` access is inside `AgentSession`, so the
`Map<String, PendingGate>` change has no missed consumer.

Full unit suite (`./gradlew.bat test -PskipWebBuild=true`): **BUILD SUCCESSFUL**.

---

## 13. Implementation log — S3a (substrate hardening)

Landed 2026-08-18 on `run-continuity-s3a`. Scope held to §7-S3a's list exactly: zero run
code, no new endpoints, no `RunEventHub` touch, no `ChatController` / `AgentSession` change.
Independently revertable — nothing outside the substrate depends on it.

### 13.1 As built

| §7-S3a item | as built |
|---|---|
| `subscribeAndReplay(listener, sinceSeq)`, resume-path only | `SseStreamChannel.java:220-247` — returns `Optional<Subscription>`; empty ⇒ window miss, no listener registered, nothing replayed |
| the channel-level lock, as costed | `SseStreamChannel.java:82` `ReentrantReadWriteLock`; `publish` takes the READ lock across append + fan-out (`:118-137`); `subscribeAndReplay` takes the write lock (`:225-235`) |
| two-phase replay handoff | `SseStreamChannel.HandoffListener:249-295` — buffer while draining, drain outside every lock, flip to pass-through under the write lock with the buffer empty |
| `run` kind on `StreamId` | `StreamId.java:31` regex + `StreamId.run(String)` (`:61-63`); wire mirror `contracts/wire/stream.proto:25` |
| byte bound + evidence slot | `FrameRetentionPolicy.java` (new), `FrameRetentionSizer.java` (new), `FrameHistoryRingBuffer.java:41-215` |
| the 18 routes' resume path rewired | `SseEnvelopeWriter.attemptResumeAndSubscribe:164-170`; `attach:226-236`; `attachEventOnly:279-289` |

**The window check moved into the lock.** §1.3.1 names replay + subscribe as the atomic
pair; validating the cursor *outside* it would leave a real check-then-act hole, since a
concurrent publish can evict the frames the check just approved. So
`SseStreamChannel.isWithinResumeWindow` (`:177-184`) is the single window authority — the
three slice-436 Fix-B cases, lifted verbatim out of `SseEnvelopeWriter.attemptResume` — and
`subscribeAndReplay` calls it *under the write lock*. `attemptResume` now delegates to it,
so the atomic and non-atomic forms cannot drift on the window rule.

**Retention defaults are inert.** `FrameRetentionPolicy.DEFAULT` is 9000 frames, no byte
bound, no evidence slot — and `tracksBytes()` is false for it, so no catalog frame is ever
sized. The sizer is only reachable from a policy that asks for it, which is what makes the
byte machinery shippable under 18 live routes today with the run policies still unwritten.
`FrameRetentionSizer.FRAME_OVERHEAD_BYTES = 200` is the doc's own "honest ~200 B" figure and
is **provisional pending P2**, which was not run.

### 13.2 Deliberate residuals, named rather than left to be discovered

1. **`MultiplexedSseWriter` keeps the non-atomic resume.** §1.3.1's subject is
   `SseEnvelopeWriter.attach`; the fan-in writer (tempdoc 662) attaches N channels to one
   connection and reuses `attemptResume` per channel (`MultiplexedSseWriter.java:99`). Its
   resume path therefore still has the race. Left in scope discipline, not oversight — it is
   the same bounded, self-correcting exposure the 17 no-cursor connects have.
2. **The no-cursor path is untouched**, per §1.3.1's own scoping, and the class javadoc +
   `05-streaming-envelope.md` now say so in those terms instead of claiming a fleet-wide fix.
3. **P8 was not run**, so the doc's primary mechanism (read lock on publish) is what shipped,
   not the generation-counter fallback. R2 stays open; the fallback is recorded at
   `SseStreamChannel.java:59-68` so a P8 result has somewhere to land.
   **RESOLVED (§14/D2): P8 ran and the read-lock primary stands.** R2 is closed. The fallback
   note at `SseStreamChannel.java:59-68` is now dead speculation and is deleted in S3b stage 0
   (§15.1) — this residual closes with it.
4. **Pre-existing, untouched:** `publish` assigns the seq *before* taking the lock, so two
   concurrent publishers can append to the ring out of seq order. Older than this slice and
   not in its scope; logged to the observations inbox.
5. **The wire gate cannot version-track this proto change.** Relaxing a `buf.validate`
   pattern is invisible to `buf breaking`, so a matching VERSION bump is rejected as
   `contract-governance/phantom-version`. Verified both ways; the changeset records it and
   `VERSION` stays at 1.0.3.

### 13.3 Verification

- `./gradlew.bat build -x test -PskipWebBuild=true` — BUILD SUCCESSFUL.
- `./gradlew.bat test -PskipWebBuild=true` — BUILD SUCCESSFUL; **1338 suites / 7753 tests /
  51 skipped / 0 failures**.
- **The existing contract tests are unedited and green**: `SseEnvelopeContractTest` (10),
  `FrameHistoryRingBufferTest` (8), `ResumeTokenCodecPropertyTest`, `SseEnvelopeWriterTest`
  (15), `MultiplexedSseWriterTest` (10). The only pre-existing test file touched is
  `StreamIdTest`, which gained two run-kind cases; no existing case was changed.
- New: `SseStreamChannelAtomicSubscribeTest` (25, incl. `@RepeatedTest(20)`),
  `FrameRetentionPolicyTest` (11), `SseEnvelopeWriterAtomicResumeTest` (5).
- FE untouched, proven: `npm run typecheck` clean, `npm run test:unit:run` — 421 files /
  5157 tests passed.
- Governance kernel: 35 gates, 6 fail / 70 findings **with and without** the change —
  byte-identical to the `origin/main` baseline (`npm-audit`, `ts-any`, `module-deps`,
  `dead-code`, `contract-projection`, `config-surface` are all pre-existing). `wire`: pass.

**Mutation probe** (the atomicity test, tested against a reintroduced race). Replacing
`subscribeAndReplay`'s body with the pre-834 order — window check, `framesSince`, replay,
*then* `listeners.add` — turned 26 green into **12 failures**:

- `never both, never neither` failed **10 of 20 repetitions** (`expected: <[1, 2, 3, …]>` vs
  a list with holes) — the intermittency is exactly why it is a `@RepeatedTest`, and why a
  single-shot test would have been a false green;
- `the channel lock excludes publish while the replay window is being taken` failed
  (`subscribe blocks until the in-flight publish completes ==> expected: <true> but was:
  <false>`);
- `replay handoff does not stall publishers behind a slow consumer's socket` failed.

Restored and re-verified byte-identical to the pre-probe file (`diff` clean), suite green.

### 13.4 Live legs — PENDING, not claimed

No dev stack was taken for this slice. Unverified live: that a real `EventSource` reconnect
with `?since=` through the 18 routes still renders identically (unit-covered at the frame
level via mocked `SseClient`, not at the browser level), and P8's read-lock cost on the
busiest channel at ~30 fps. Both are S3a-scoped legs a later session should run before the
substrate carries run traffic in S3b.

---

## 14. S3b pre-implementation probes — D1 (POST-managed-SSE) and D2 (P8 lock cost)

Run 2026-08-18 as a **derisk spike**: throwaway code on a scratch branch, never merged, never
pushed. One dev-stack session per leg, launched from this worktree's dist (`distFrom`), lease
declared, stopped and teardown-verified afterwards (`quick_health running:false`, ports 63120 /
63562 / 50744 / 5173 all closed, no `HeadlessApp` / `IndexerWorker` / vite / dev-runner / llama
survivors). No model was loaded — the probes are transport-level.

**The probe.** One route added to `DebugRoutes` —
`app.post("/api/debug/sse-probe", new SseHandler(consumer))` — whose consumer reads the request
body from `client.ctx()`, emits a `started` event echoing the body length, ticks once a second,
and registers `client.onClose(...)`. Open/close observations were recorded server-side and read
back through a companion GET route rather than scraped from logs (the dev-runner's captured
`backend.stdout.log` / `backend.stderr.log` hold only the port line and JVM warnings — see the
residuals below). `SseHandler`'s shape was re-confirmed by `javap` against the resolved
`javalin-6.7.0.jar`: `public SseHandler(java.util.function.Consumer<SseClient>)`,
`implements io.javalin.http.Handler`.

### 14.1 D1 — the hinge: **CONFIRMED**

**(a) Direct POST to the API port — passes on every axis.** `curl -N` with a JSON body,
`Accept: text/event-stream` and `X-JustSearch-Session` (`LocalApiServer.java:68`), replicating
`consumeShapeStream`'s header (`streams.ts:441`):

```
HTTP/1.1 200 OK
Content-Type: text/event-stream;charset=utf-8
Connection: close
Cache-Control: no-cache
X-Accel-Buffering: no

event: started
data: {"conn":1,"bodyProbe":"ok","bodyLength":84,"method":"POST","tsMs":1787051828420}
event: tick   data: {"conn":1,"n":1,...}   … n=2,3,4 at 1 s intervals
```

- **The body reaches the handler.** `client.ctx().body()` from inside the `SseHandler` consumer
  returned the full payload — echoed `bodyLength` matched the client's byte count exactly on
  every connection tried (84/84, 85/85, 83/83, 23/23). No exception, no truncation, no
  interaction with Javalin's async start.
- **Events stream incrementally.** Client-side byte counts, polled at 100 ms:
  `13:17:08.618 → 103 B`, `:09.601 → 160`, `:10.616 → 217`, `:11.491 → 274`, `:12.539 → 331`.
  One frame per second, delivered as produced. No buffering.
- **The token filter passes POST-SSE** — see (d); the header is inert in dev but honoured in
  prod mode.

**(b) onClose fires — but it is write-driven, not socket-driven.** Three disconnects
(`--max-time`; note that `kill -INT` cannot be delivered to a native Windows `curl` from this
shell, so the disconnect is a clean process exit / FIN rather than a signal):

| conn | client gone at | `onClose` at | latency | landed on |
|---|---|---|---|---|
| 1 | open + 5155 ms | open + 6005 ms | **850 ms** | the next tick |
| 2 | open + 5033 ms | open + 6002 ms | **969 ms** | the next tick |
| 3 | open + 4887 ms | open + 6001 ms | **1114 ms** | the tick *after* next |

Every close landed exactly on a tick boundary, never in between — and conn 3 shows the first
post-disconnect write can still succeed silently, with the failure surfacing on the one after.
**The design consequence is concrete: `onClose` latency is bounded by the stream's own write
cadence, not by TCP.** A run stream that goes quiet does not learn its observer left until it
next writes. The existing heartbeat is therefore load-bearing for §3's park/unobserved
detection, not merely a proxy keep-alive — a run whose only liveness signal is `onClose` and
whose heartbeat is disabled would never observe the disconnect at all.

**(c) Through the Vite dev proxy — the stream works, `onClose` NEVER fires. This is P4, and it
is a real hazard.** Same POST via `http://localhost:5173`:

```
HTTP/1.1 200 OK
content-type: text/event-stream;charset=utf-8
Connection: keep-alive
Keep-Alive: timeout=5
Transfer-Encoding: chunked
```

Streaming is byte-for-byte equivalent (`103 → 160 → 217 → 274 → 331` at 1 s), the body arrives
intact, `X-Accel-Buffering: no` survives. Then the client died at epoch 1787051922141 — and the
server-side record still read `closed:false` with ticks still succeeding at 1787052393711:
**471 seconds later, and still counting when the leg ended.** Not late — never.

The mechanism is in the dev proxy, not in Javalin. `modules/ui-web/vite.config.js:141-165`
hand-rolls the forward: `req.pipe(proxyReq)` (`:165`) and `proxyRes.pipe(res)` (`:155`), with
an error handler on `proxyReq` (`:158`) but **no `res.on('close', …)` teardown**. Node's `pipe`
does not destroy the source when the destination closes, so when the browser goes away the
proxy→backend socket stays open and the backend sees a permanently healthy subscriber.

Topology consequence for S3b, which is what P4 was asked to decide:

- **Any dev verification of run-stream lifecycle — park/unobserved detection, observer-count,
  `onClose`-driven journal retirement — must go direct to the API port.** Through the proxy the
  disconnect signal does not exist, so a green result there is meaningless (`static-green ≠
  live-working`).
- The proxy is fine for *content* verification (frames render, replay ordering, cursor grammar).
- The one-line proxy fix (`res.on('close', () => proxyReq.destroy())`) is worth doing so browser
  verification becomes trustworthy, but it is dev-tooling work, not S3b substrate work, and it
  does not gate S3b. Logged to the inbox.

**(d) Negative control — the auth posture §1.6 chose is real.** Token enforcement is a **no-op
under the dev-runner by default**: dev-runner-launched backends never set `JUSTSEARCH_PROD` /
`-Djustsearch.prod`, so `prodMode` resolves false and
`ApiSecurityFilters.setupSessionTokenEnforcement` returns early (`ApiSecurityFilters.java:382-390`;
the dev-runner documents this at `scripts/dev/dev-runner.cjs:842-846`). The control was therefore
run on a stack forced into prod mode by injecting `-Djustsearch.prod=true` into the dist start
script (a build artifact; reverted afterwards), which made the Head mint a 43-char session token
into the runtime manifest:

| request | result |
|---|---|
| POST, **no** token | `401` `{"errorCode":"UI_TOKEN_REQUIRED"}`, `content-type: application/json`, **3.1 ms**, no stream |
| POST, **wrong** token | `401`, identical body, 2.2 ms, no stream |
| POST, **correct** token | `200 text/event-stream`, body reached handler (`bodyLength:23`), ticks at 1 s |
| GET `/api/health`, no token | `200` — **unauthenticated**, exactly as §1.6 argues |

The `app.before` filter runs and halts *before* `SseHandler` is reached — no partial hijack, no
half-open SSE response on a rejected request. §1.6's core argument is confirmed end-to-end: POST
managed SSE is authenticated by the existing filter with no filter change, and a GET run-stream
family really would have shipped open.

**Verdict: HINGE CONFIRMED.** All four sub-measurements pass. `app.post(path, new SseHandler(…))`
yields a fully managed `SseClient` with a readable request body, incremental streaming, a working
`onClose`, and existing token enforcement — live, in this server. §1.6's endpoint topology stands
as designed, with two qualifications that belong in S3b's spec rather than in its risk register:
`onClose` is write-cadence-bound (b), and dev lifecycle verification must bypass the proxy (c).

### 14.2 D2 — P8 lock cost: **KEEP PRIMARY** (do not promote the fallback)

**Instrument.** A `System.nanoTime()` pair around `subscribeLock.readLock().lock()` in
`SseStreamChannel.publish` (`:121`), samples kept per `streamId` in a fixed overwrite ring and
read back through a debug route. It measures **acquisition only** — the fan-out inside the read
lock is unchanged by 834 and is not what P8 asks about.

The busiest live channel was `surface:indexing-jobs`, driven well past the ~30 fps target by
ingesting the repo's docs and script trees with 21 SSE subscribers attached and concurrent API
load.

| window | publishes | rate | mean | p50 | p90 | p99 | p99.9 | max | >10 µs | >1 ms |
|---|---|---|---|---|---|---|---|---|---|---|
| 1 — no writers | 1 331 | 127.9/s | 1 853 ns | 400 | 1 500 | 6 400 | 62 100 | 1 385 200 | 8 | 1 |
| 2 — **writer contention** | 7 185 | **152.9/s** | **296 ns** | **200** | **400** | **1 100** | 13 300 | 216 300 | 9 | **0** |

Window 2 is the one that decides it. It added a **resume-path reconnect storm — 228 connects
(4 workers × 57) in 60 s**, each one taking the **write** lock via `subscribeAndReplay`, and each
replaying a verified **8 537 frames / 4.27 MB** (window hit: no `resume-window-miss`, no
snapshot). So the primary mechanism was measured with real writers holding the exclusive lock
while snapshotting thousands of frames, concurrent with 153 publishes/sec:

- **p99 read-lock acquire = 1.1 µs. Zero acquisitions over 1 ms out of 7 185.** Nine (0.13 %)
  exceeded 10 µs.
- At 30 fps the total publish-side lock cost is `30 × ~300 ns ≈ 9 µs per second` — roughly
  0.001 % of one core.

**Interrogating the two surprises**, because the numbers do not read the way a naive reading
would predict:

1. *Window 2 is ~6× cheaper than window 1 despite far more contention.* Cause is JIT warmup,
   not a lower true cost: window 1 has 1 331 samples against window 2's 7 185, and window 1
   includes the first publishes after a reset. The **more** contended window being **faster**
   rules out contention as the driver of window 1's mean.
2. *Window 1's 1.39 ms outlier.* It cannot be lock contention: every subscriber in window 1
   connected without a cursor, so it took `subscribe()` (`SseStreamChannel.java:187-191`), which
   never touches the write lock — there was no writer in that window for a reader to wait on. It
   is JVM/OS noise (GC, descheduling under the concurrent curl load). Consistent with this,
   window 2 — which *did* have 228 writers — has **no** >1 ms sample at all.

Honest floor: `System.nanoTime()` granularity on this machine is ~100 ns, so the p50 of 200 ns is
one-to-two clock ticks. The true acquire cost is **at or below the measurement floor**, which
strengthens rather than qualifies the verdict.

**Verdict: KEEP PRIMARY.** The read lock on `publish` is not measurable at 30 fps, and is not
measurable at 5× that rate with a heavy concurrent writer either. §13.2 residual 3 and the
fallback note at `SseStreamChannel.java:59-68` can be closed: the publish-generation counter is
**not** promoted. The two-phase handoff is doing its job — 8 537-frame replays under the write
lock did not produce a single millisecond-scale publisher stall.

### 14.3 Spike residuals

1. **`kill -INT` cannot be delivered to native Windows `curl`** from Git Bash, so (b)'s
   disconnects are clean process exits (FIN), not signals. An abrupt `taskkill /F` was also
   observed to disconnect, but its `onClose` latency was not recorded. The write-driven
   conclusion rests on the three FIN cases, which agree.
2. **The dev-runner captures almost no Head log output** — `backend.stdout.log` held one line
   (`JUSTSEARCH_API_PORT=…`) and `backend.stderr.log` only JVM warnings; there is no Head log
   file under `<dataDir>/logs/` (only `worker.log`). The probe was rebuilt to self-report through
   an endpoint instead. Logged to the inbox; it makes `tail_log`-based diagnosis of Head-side
   behaviour unreliable.
3. **The proxy `onClose` leak (c) is unfixed** — reported here and to the inbox, deliberately not
   fixed in a throwaway spike.
4. **Not measured:** browser-`EventSource` behaviour (§13.4's other pending leg — untouched,
   and note `EventSource` cannot issue a POST at all, so run streams need `fetch`-based SSE
   parsing on the FE, which `consumeShapeStream` already does), and `MultiplexedSseWriter`'s
   non-atomic resume path (§13.2 residual 1).

---

## 15. Implementation handoff — S3b, S4, S5

Line map for a fresh implementer, in the shape of §11. **Every anchor below was re-verified
against `main` at the time of writing**; S1, S2 and S3a have all landed since §7 was authored,
so several §7 anchors had drifted (§15.0). Re-verify before editing anyway — lines drift.

### 15.0 What binds this handoff

**Cherry-pick §14 first.** The probe record for D1/D2 lives on branch `spike-post-sse` as
commit `3d5f15b8` (shared object store, so `git cherry-pick 3d5f15b8` works from any
worktree). Do this as the first commit of the S3b branch so §14 rides the PR and the spec
qualifications below have their evidence attached.

**D1 — the §1.6 hinge is CONFIRMED live.** `app.post(path, new SseHandler(consumer))` streams
incrementally, the request body is readable from `client.ctx()` inside the consumer, `onClose`
fires, and the existing `ApiSecurityFilters` token filter rejects an untokened POST with 401
*before* the handler runs. §1.6 stands as designed. **Two qualifications bind the spec:**

1. **`onClose` is write-cadence-bound.** Measured 850–1114 ms, always on a tick boundary,
   never on socket close. A stream that writes nothing **never learns** its client left.
   Therefore **the heartbeat is load-bearing, not cosmetic**: it is the only write a parked
   run makes, so it is the sole mechanism by which `onClose` can fire for a parked run, and
   therefore the sole mechanism by which §3's zero-observer park and `observerCount` become
   true. `RunStreamWriter` MUST schedule it (§15.1.3). Derived bound, and it is comfortable:
   heartbeat cadence is 15 s (`StreamLivenessWindows.STREAM_HEARTBEAT_INTERVAL_SECONDS`)
   against a 120 s park window (`AgentStepRunner.java:953`,
   `justsearch.agent.zeroObserverParkTimeoutSec` default) — an 8× margin, so detection cannot
   lose a race with the park timeout. **State the residual honestly:** zero-observer detection
   is eventually-consistent with a bound of one heartbeat interval, so a WATCH run may execute
   at most one further iteration after its watcher leaves. That is a real weakening versus the
   instantaneous-eviction story, and it is still strictly better than the pre-S3b silent case,
   which never detects at all (R4).
2. **P4 came back negative and reverses §7's topology relaxation.** Through the Vite dev proxy
   `onClose` **never** fires — `modules/ui-web/vite.config.js` pipes the upstream response
   (`proxyRes.pipe(res)`, ~`:150-156`) with no teardown on client `res` close, so the upstream
   socket stays open forever. §7's "Verification topology" paragraph says that after S3b,
   tests asserting detection *happens* may run through the proxy "subject to P4". **P4 has now
   answered: they may not.** Every S3b verification item touching stream lifecycle —
   `onClose`, `observerCount`, eviction, park, retire/linger, 404-on-retired — is
   **DIRECT-TOPOLOGY-ONLY**. The proxy remains fine for content checks (frames arrive, text
   renders, citations attach). Treat §7's paragraph as superseded by this one.

**D2 — P8 ran; keep the primary.** The read-lock-on-`publish` mechanism stands; the
generation-counter fallback is not needed. So §13.2 residual 3 and the fallback note it points
at are resolved, and closing them is S3b stage 0 (§15.1.0).

**Orthogonal in-flight work.** The selection-stall was root-caused to a producer-side wedge in
`OnlineModeOps`/`ConversationEngine`, unrelated to S3b, and is being fixed as its own slice
that may land first. S3b needs no special case for it, **but the fix may touch
`ConversationEngine`'s latch region (`:520-570`, `CountDownLatch latch` at `:531`,
`latch.await()` at `:564`)** — the same region S3b's sink rerouting sits next to. Expect a
catch-up merge there and re-read the region before editing rather than trusting this map.

### 15.1 S3b — run substrate, endpoints, hub deletion

Dependency-ordered. Stages 0–2 are additive and independently compilable; stage 3 flips run
traffic onto the new path; stage 4 is the deletion sweep and must come last because everything
before it still compiles against the hub.

#### Stage 0 — close the S3a residual (no run code)

- `modules/app-observability/.../stream/SseStreamChannel.java:59-68` — delete the
  "named fallback is a publish-generation counter … P8 has NOT been run" paragraph; replace
  with one line recording that P8 ran and the read-lock primary stands (§14/D2). Update
  §13.2 residual 3 in this doc in the same commit.
- **Done when:** `./gradlew.bat :modules:app-observability:test` green; no behavioural diff.

#### Stage 1 — the run substrate types (new package, no callers yet)

New package `io.justsearch.app.observability.stream.run` in `modules/app-observability`
(§1.4 — no new module edge; `app-observability` already api-depends on `app-agent-api` and
`app-api`, and nothing there depends on `app-agent`).

- `RunId` — wraps a letter-initial slug; `streamId()` delegates to the S3a-landed
  `StreamId.run(String)` (`StreamId.java:61-63`; the regex already admits `run:`, `:31`).
- `RunDescriptor`, `RunChannelPolicy` (§1.5). Build the two policies on the S3a retention
  layer rather than re-deriving bounds: `FrameRetentionPolicy` (`:39-95`, with
  `ofFrames`, `tracksBytes()`, `evidenceSlotEnabled()`, and the `EvidenceClassifier` SPI at
  `:93`). Narrative 4000 frames / 2 MiB; agent 1000 / 4 MiB; evidence classifier keyed on the
  event names in §2. Note `FrameRetentionSizer.FRAME_OVERHEAD_BYTES = 200` is **provisional
  pending P2**, which still has not been run — if P2 is run first, re-derive both budgets.
- Sealed `RunChannel` with `SteppedRunChannel` / `OneShotRunChannel` (§3.4). **The one-shot
  type must have no `setPark`** — that is the structural guard, not a javadoc.
- `RunChannelRegistry` — `open` / `find` / `live` / `retire(id, linger)`. `retire` owns the
  whole terminal transition (§2): refuse publishes, keep the ring readable for `linger`, then
  drop; cap 32 channels, drop retired-and-lingering oldest-first, never drop a live run.

**New tests:** `RunChannelRegistryTest` (open/find/live/retire; the 32-cap refusing a 33rd
live run with a typed error, not evicting); `RunChannelPolicyTest` (evidence classification;
the one-shot type does not expose `setPark` — a compile-level assertion is enough, e.g. a
test that only compiles against `RunChannel`).

**Done when:** `:modules:app-observability:test` green; zero references from any other module.

#### Stage 2 — `RunStreamWriter` (new writer, no routes yet)

`modules/ui/.../RunStreamWriter.java`, sibling to `SseEnvelopeWriter` — same managed-client
orchestration, run vocabulary instead of envelope vocabulary. Mirror the structure at
`SseEnvelopeWriter.attach:212-262`: resume-and-subscribe (`:228`), heartbeat schedule
(`:240`), `client.onClose` unsubscribe + heartbeat cancel (`:243`), `keepAlive()`.

Five protocol requirements from §1.6, each of which is a test:

1. **Absent cursor ⇒ replay from 0**, never snapshot-only. `SseEnvelopeWriter.attach` only
   resumes when the token is non-blank; a run stream must always replay. Convenient property
   of the S3a API: `isWithinResumeWindow` rejects only `sinceSeq > current` or
   `sinceSeq > 0 && (oldest == 0 || sinceSeq < oldest)`, so **`subscribeAndReplay(listener, 0)`
   always succeeds** — it is the guaranteed fallback path.
2. **`subscribeAndReplay` returns `Optional.empty()` on a window miss and registers nothing**
   (`SseStreamChannel.java:220-247`). So the writer must handle empty explicitly: emit
   `replay_truncated {sinceSeq, oldestRetainedSeq}`, emit the snapshot, then call
   `subscribeAndReplay(listener, 0)` and assert it succeeded. Silently returning an empty
   stream is the failure mode to avoid.
3. **`?sinceSeq=<long>`**, not `?since=` — the envelope family's `?since=` carries a
   `ResumeTokenCodec` token and reusing the name would fork the grammar (§1.6).
4. **No `id:` line** — closes `Last-Event-ID` as a second, unvalidated resume channel.
5. **Heartbeat is mandatory** (§15.0 D1.1), at `StreamLivenessWindows.STREAM_HEARTBEAT_INTERVAL_SECONDS`,
   as a sequenced-but-not-retained lifecycle frame (§3.2) — route it through the channel's
   `nextEnvelope` analogue, never `publish`, so it never occupies a ring slot.

**New tests:** `RunStreamWriterTest` over a mocked `SseClient` — absent cursor replays from 0;
window miss emits `replay_truncated` + snapshot then still subscribes; no `id:` line is ever
written; the heartbeat fires on cadence and is cancelled by `onClose`; `?sinceSeq=` parses and
a `?since=` token is rejected rather than silently treated as 0.

**Done when:** `:modules:ui:test` green; still no route registered.

#### Stage 3 — endpoints + engine wiring (run traffic flips here)

- `AgentRoutes` (or a new `RunRoutes`) — `app.post("/api/chat/runs", new SseHandler(...))` and
  `app.post("/api/chat/runs/{runId}/observe", new SseHandler(...))`. Read the request body from
  `client.ctx()` inside the consumer (D1-confirmed). Auth needs no change: POST is already
  covered by `ApiSecurityFilters`' token filter (`:395-402`, `TOKEN_REQUIRED_METHODS` at `:44`).
- `run_started` **lifecycle** frame, first on every run stream, carrying `{runId, shapeId,
  conversationId}` (§3.2). Not an `AgentEvent` — adding a sealed permit would cascade through
  7+ sites.
- **`ChatController` error paths through the sink.** Verified current anchors: the sink is
  `:169` (`sseWriter.writeEvent(ctx, sseEvent.name(), sseEvent.payload())`), `sseError` is
  `:123-130`, and the three catch arms are `:170-179` (`AudienceDeniedException` `:170`,
  `ShapeNotFoundException` `:173`, `Exception` `:176`). Also `:146-149` (malformed body) and
  `:108` (missing `shapeId`) write via `sseError` — the first is pre-run so it may stay on
  `ctx`, but `:170-179` are mid-run and must go through the sink or a failing run terminates
  invisibly for every non-creating observer.
- **404 contract** for unknown/retired runIds: typed `{runId, reason: "unknown"|"retired",
  recordHint}` (§1.6), not a 200 with an empty stream.

**New tests:** ask-survival (§3.4 — observer count reaches 0 mid-generation, run still reaches
`done` and still persists); 404 shape for unknown vs retired; `run_started` is first and is not
retained in the ring; a mid-run engine exception reaches a second observer.

**Done when:** `./gradlew.bat build -x test -PskipWebBuild=true` and the full unit suite are
green, and the **direct-topology** live legs below pass.

#### Stage 4 — hub deletion sweep (last)

**Sweep table, re-verified against `main`.** One row had drifted materially since §7 —
`AgentSession.java`, because S1 grew the file:

| site | §7 said | **verified now** | what changes |
|---|---|---|---|
| `RunEventHub.java` | deleted | 108 lines, untouched | deleted |
| `AgentSession.java` | `:74,671-673,676-678` | **`:85` field, `:735-736` `eventHub()`, `:740-741` `observerCount()`, `:760` policy read** | field + accessor removed; `observerCount()` → injected `IntSupplier`; publish via injected `Consumer<AgentEvent>` |
| `AgentSessionRegistry.java` | `:185-232` | `:185`, `:194`, `:207`, `:228` — holds | `attachToRun` (both arities) reimplemented on the channel |
| `AgentLoopService.java` | `:639-645` | `:639-640`, `:644-645` — holds | the two `attachToRun` overrides |
| `AgentLoopService.java` | `:575,579` | `:575` remove, `:579` `eventHub().close()` — holds | → `registry.retire(id, linger)` |
| `AgentService.java` (app-agent-api) | `:121-131` | `:121-122`, `:131` — holds | the two default `attachToRun` methods — **public interface, a contract change** |
| `AgentController.java` | `:478,513` | `:478` native attach, `:513` AG-UI attach — holds | call sites move to the channel |
| `AgentSseWriter.java` | `:94-149` | `:80`, `:94`, `:99`, `:134-136`, `:145-146` — holds | `writeOrEvict`/`evictIfGone`/`SseObserverGoneException` obsolete once `onClose` owns disconnect |
| `SseWriter.SseWriteOutcome.CLIENT_GONE` | keep, re-document | consumer is `AgentSseWriter:99,134` | keep the enum (`SseWriterTest:18` pins serialization-vs-disconnect) but re-document |
| `RunEventHubTest.java` | whole file | 168 lines | deleted |
| `AgentControllerSseEvictionTest.java` | whole file | 50 lines | deleted |
| `AgentLoopServiceTest.java` | `:634-709,760` | `:634`, `:666`, `:697`, `:705-709`, `:713-780` (the park test), `:760` — holds | attach + eviction cases retargeted at the channel |

Two things the sweep must not break:

- **`AgentSessionRegistry:207` is now an 8-arg `StateSnapshot`** (S1 landed `pendingApprovals`,
  `autonomyLevel()`, `parkSnapshot()`). Carry all eight when the primer moves to the channel;
  dropping the S1 fields here would silently undo S1's whole point (§6.1).
- **`AgentLoopServiceTest:713-780` is sound and must not be rewritten** (R4). Its dead socket
  throws on first delivery, so it tests eviction-on-publish, exactly as it claims. Retarget it
  at the channel; do not weaken it. **Add** the missing silent-run case (§15.3, open question 1).

**Also add**: the map-input `AgUiEventTranslator` overload + the equivalence gate (§6.5),
anchored on `AgUiEventTranslatorConformanceTest.ALL_VARIANTS:37-61` and `coversEveryPermit:64-71`
— **not** `AgentEventSchemaConformanceTest`, whose variants are null/zero-field and would NPE
or pass vacuously — plus at least one variant constructed with a populated `TraceContext`,
since every current entry carries `TraceContext.none()`.

**Live legs — DIRECT TOPOLOGY ONLY (P4):** two concurrent observers on one ask; reload
mid-answer and rejoin; `onClose` fires on tab close (expect ≤1 heartbeat interval on a parked
run, sub-second on a streaming one); a run retired past its linger answers 404 with the right
`recordHint`. Run these against the backend port, not through `npm run dev`.

### 15.2 S4 — enumeration

1. `modules/app-api/.../run/LiveRunsResponse.java` + `LiveRunSummary` + `ParkSummary` +
   `RunStateSnapshotView` + `PendingApprovalView` — **typed, never `Map<String,Object>`**
   (`WireRecordSchemaGenTest.java:107-109` records why). `arguments` rides as a JSON string.
2. `WireRecordSchemaGenTest` — add a `captureOrVerify(LiveRunsResponse.class,
   "live-runs-response.v1.json")` case beside the agent precedent at `:110-114`; run it to
   generate `SSOT/schemas/live-runs-response.v1.json`; then
   `node scripts/codegen/gen-wire-schema-types.mjs`. S2 already walked this exact path for
   `AgentSessionSummary.interruptedAt` (`:30`), so follow that commit.
3. Handler on `AgentSessionController` (the read-axis controller, `AgentRoutes.java:68-70`),
   projecting `RunChannelRegistry.live()` with `MAPPER.convertValue` — the pattern
   `handleListSessions:93-97` already uses, since `app-api` cannot depend back on
   `app-agent-api` (`AgentSessionController.java:90-92`).
4. **The auth change — a required item of this slice, not a follow-up.**
   `ApiSecurityFilters.java:395-402` is the token filter; `:399` returns early for GET and
   OPTIONS and `:44` lists `POST/PUT/DELETE`. Add a **path-scoped** requirement for
   `/api/chat/runs/**` *before* the method check, so `GET /api/chat/runs/live` demands the
   token. Do not confuse this filter with the operation-admission filter at `:114-119`, which
   has its own GET/OPTIONS early return for an unrelated reason. The FE can comply —
   `consumeShapeStream` already sends `SESSION_TOKEN_HEADER` on a `fetch` (`streams.ts:444`
   region) and the enumeration is a `fetch`, not an `EventSource`.

**New tests:** projection fidelity; N > 1 runs on one `conversationId` returned as a list,
never collapsed (§3.5); **the adverse precondition — a request without the token header is
rejected** (without this, R6 is a comment rather than a guarantee).

**Done when:** suite green, the generated TS/Zod is committed, and — direct topology — an ask
and an agent run both enumerate with correct `observerCount`, and closing a tab drops the count
within one heartbeat interval.

### 15.3 S5 — FE sweep

- `modules/ui-web/src/shell-v0/controllers/activeRunPointer.ts` (`setActiveRun:31`,
  `clearActiveRun:44`, `readActiveRun:53`) — retired as the *discovery* authority; discovery
  becomes `GET /api/chat/runs/live`. Callers: `SearchV3View.reattachLiveRun` and
  `AgentSessionController`.
- Run identity moves to the `run_started` frame; `consumeShapeStream` (`streams.ts:544-553`
  region) learns `?sinceSeq=`, `replay_truncated`, and the 404 `recordHint` shape.
- **`session_started` is dual-read and wire-deprecated, NOT deleted** (§3.3) — it is emitted by
  `WorkflowShapeRunner.java:163` and pinned in the durable ledger (`AgentRunStoreTest:52,383`).
  Deleting it breaks replay of every existing `events.ndjson`.
- `RetrospectivePanel.ts:187-193,629-632` — the four interruption rows (§5.2), reading
  `interruptedAt` which S2 already ships on `AgentSessionSummary:30`.

**Done when:** `npm run typecheck` and `npm run test:unit:run` green, and a browser reload
mid-answer rejoins the live ask — with the rejoin *content* check permitted through the proxy
but any `observerCount`/lifecycle assertion run direct (P4).

### 15.4 Open questions for the implementer

1. **The silent-run disconnect test (R4) needs a shape.** §8 calls it "a missing test". With
   `onClose` write-cadence-bound, the honest assertion is *"a run parked at an approval gate
   with a dead client loses its observer within one heartbeat interval"* — not "immediately".
   Decide whether to assert the bound (flaky-prone, needs a real socket) or to unit-test the
   two halves separately (heartbeat fires on cadence; `onClose` removes the observer) and
   cover the composition in the direct-topology live leg only. **Recommendation: the latter**,
   with the live leg recorded as evidence rather than as a CI test.
2. **P2 was never run**, so `FRAME_OVERHEAD_BYTES = 200` and both run budgets (§2) are
   provisional. Stage 1 can ship on the provisional numbers, but if the evidence slot's 4 MiB
   turns out to be wrong by an order of magnitude the retention story is theatre. Run P2
   before or during stage 1.
3. **The eventual-consistency residual (§15.0 D1.1)** — a WATCH run may execute one further
   iteration after its watcher leaves. This is a behavioural change to the park's promise and
   should be stated in whatever user-facing copy describes "Paused — no one is watching",
   rather than left as an implementation detail.
4. **`MultiplexedSseWriter` still has the non-atomic resume** (§13.2 residual 1). It is not in
   S3b's scope, but if any run stream is ever fanned in through it, the race returns for run
   traffic — where it is not self-correcting. Add a note at
   `MultiplexedSseWriter.java:99` if S3b does not close it.
