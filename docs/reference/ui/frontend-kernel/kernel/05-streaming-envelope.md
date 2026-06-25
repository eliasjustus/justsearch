---
title: "Frontend kernel — streaming envelope"
type: reference
status: stable
description: "The universal multi-frame SSE envelope contract (initial snapshot + delta frames)."
date: 2026-06-09
---

# Frontend kernel — streaming envelope

> **Graduated to canonical docs on 2026-06-09** from the retired `421` frontend-rewrite kernel
> draft's `10-kernel/` set (authored ~2026-05; the rewrite shipped per tempdoc 563). References to
> the draft's removed planning material (`slices/`, `20-systems/`, `archive/`, …) are historical.
> ADR links point to `docs/decisions/`; sibling kernel docs are in this folder.


Every framework SSE endpoint adopts the universal envelope. New SSE
Resources adopt it from day one; the four shipped Resources
(`core.health-events`, `core.runtime-context`, `core.server-capabilities`,
`core.operation-history`) all use it.

This doc is the wire-format reference. The decision rationale lives in
`../../../../decisions/0037-universal-sse-envelope.md`. The implementation history
lives in `slices/436-streaming-envelope.md`.

## Wire format

Every frame is a single JSON object as the SSE `data:` payload. The
SSE event name is constant `"frame"`. Consumers route by
`envelope.frameKind` and (for lifecycle frames) the nested
`payload.kind`.

```json
{
  "streamId": "surface:health-events",
  "frameKind": "UPDATE",
  "seq": 42,
  "ts": "2026-05-05T12:34:56.789Z",
  "payload": { /* frame-specific shape */ },
  "resumeToken": "c3VyZmFjZTpoZWFsdGgtZXZlbnRzOjQy"
}
```

Field semantics:

- `streamId` — kind-prefixed slug identifying the stream. Stable
  across reconnects.
- `frameKind` — top-level discriminator (see below).
- `seq` — monotonic per-stream sequence number. Starts at 1 on the
  first frame after stream registration. Gaps may occur across
  server restarts (consumers detect via the `reset` lifecycle).
- `ts` — server-side wall-clock timestamp of frame emission, ISO-8601
  UTC.
- `payload` — frame-specific data. UPDATE frames carry the
  controller-defined wire shape. LIFECYCLE frames carry at minimum
  `{kind: <subkind>}` plus per-subkind extras.
- `resumeToken` — opaque server-encoded cursor. Consumers send it as
  `?since=<resumeToken>` on reconnect.

## Frame kinds

`frameKind` is the top-level discriminator:

- `UPDATE` — data frame. Broadcasts to all subscribed clients.
  Carries the controller-defined payload shape (e.g.,
  `HealthEventChangeRegistry.HealthDelta`). Retained in the per-
  stream ring buffer for resume.
- `LIFECYCLE` — connection-management frame. Per-connection (not
  shared); not retained in the ring buffer. The lifecycle subkind
  is encoded in `payload.kind`:
  - `connected` — emitted once per connection on subscribe.
  - `snapshot` — carries the controller-supplied initial-state
    payload via `{kind: "snapshot", ...extras}`. Emitted on subscribe
    when no resume token was supplied (or after a `reset`).
  - `heartbeat` — emitted on the heartbeat scheduler tick.
  - `reset` — signals the consumer should discard cached state.
    Emitted when a resume attempt falls outside the window. Always
    followed by a fresh `snapshot`.
  - `closing` — emitted once during graceful shutdown.

## StreamId

Stream identifiers are kind-prefixed slugs of the form
`<kind>:<id>` where:

- `<kind>` ∈ `{registry, surface, system}`:
  - `registry` — catalog-shaped streams (e.g.,
    `registry:capabilities`).
  - `surface` — UI-rendered surfaces (e.g., `surface:health-events`,
    `surface:operation-history`).
  - `system` — system-level streams (e.g., `system:runtime-context`).
- `<id>` matches `[a-z][a-z0-9-]*`.

Validation lives in `StreamId.PATTERN`
(`modules/app-api/.../stream/StreamId.java`).

## Resume semantics

A consumer may include `?since=<resumeToken>` on reconnect. The
controller decodes the token and either:

1. **Replays in-window UPDATE frames**: when the token's `streamId`
   matches and its `seq` lies within the channel's ring-buffer
   window (`oldest <= sinceSeq <= currentSeq`), the controller
   forwards each retained UPDATE frame with `seq > sinceSeq`. The
   consumer is brought up to date; no fresh snapshot is emitted.
2. **Emits `reset` + fresh `snapshot`**: when the token is malformed,
   from a different stream, from a future / different server
   lifetime (`sinceSeq > currentSeq`), or predates the ring buffer
   (`sinceSeq < oldest`, including the empty-buffer-with-positive-
   sinceSeq case).

Per slice 436 §B.B Fix B, the empty-buffer guard is essential: a
client whose token references seq from a previous server lifetime
must not receive a false-positive "you're up to date" response when
the new server's buffer is empty.

`resumeToken` is opaque on the wire (base64-URL-encoded
`(streamId, seq)` tuple internally; consumers MUST NOT parse it).
The opacity is contractual so the encoding can change without a
protocol break.

## Heartbeat policy

Default cadence is 30 seconds (per the original spec). The four
shipped controllers override to 15 seconds — tighter heartbeats are
fine; loosening would shrink the FE's connection-dead-detection
budget.

Heartbeat frames are LIFECYCLE-kind broadcasts originating from
each controller's per-connection scheduler. They consume seqs from
the channel's shared tracker but are NOT retained in the ring
buffer.

## Per-stream coordination

Each `StreamId` has exactly one `SseStreamChannel` instance per
process (one per change-registry). The channel owns:

- A `StreamSequenceTracker` (atomic monotonic counter, starts at 1).
- A `FrameHistoryRingBuffer` (default capacity 9000 frames).
- A listener set (`Set<Consumer<SseEnvelope>>`).

Frame discipline:

- `channel.publish(frameKind, payload)` — broadcasts to all
  subscribers. Assigns next seq, wraps in envelope, appends to ring
  if `frameKind == UPDATE`, fans out to listeners.
- `channel.nextEnvelope(frameKind, payload)` — per-client envelope
  construction. Consumes a seq but does NOT append to ring or
  broadcast. Used for lifecycle frames sent only to one connection.

Resume reads `framesSince(sinceSeq)` from the ring; `oldestRetainedSeq()`
reports the buffer's first frame (or 0 when empty).

## Per-connection writer

`SseEnvelopeWriter` is the canonical per-connection helper. The four
shipped controllers each delegate via the static `attach()`
orchestrator:

```java
public void handle(SseClient sseClient) {
  SseEnvelopeWriter.attach(
      sseClient,
      changes.channel(),
      () -> Map.of(/* controller-specific snapshot extras */),
      clock,
      heartbeatScheduler,
      HEARTBEAT_SECONDS);
}
```

The `attach` orchestrator sequences:

1. Emit `connected` lifecycle.
2. Read `?since=<token>` from `client.ctx()` (null-safe).
3. `attemptResume(token)`; on miss emit `reset`.
4. If not replayed, build snapshot via the supplied supplier and
   emit `snapshot` lifecycle.
5. Subscribe to channel for live UPDATE forwarding.
6. Schedule heartbeat at the supplied cadence.
7. Register `onClose` to unsubscribe + cancel heartbeat.
8. Call `client.keepAlive()`.

A controller's `handle()` method shrinks to ~5 lines of
delegation; the writer encapsulates the contract uniformly.

## Capability advertisement

The handshake at `/infra/capabilities` advertises:

```json
{
  "serverCapabilities": {
    "streamingEnvelope": { "version": 1 }
  }
}
```

Absence implies a backend that pre-dates the envelope (bespoke per-
endpoint shape). FE consumers feature-detect to fall back gracefully.
A version bump signals a wire-incompatible envelope change; field
additions within v1 are non-breaking per the LSP soft-fail discipline.

## Known limitation: snapshot-vs-subscribe race

Between snapshot generation and `channel.subscribe()`, broadcasts can
fire and be missed by the new subscriber. The window is small
(single-thread function call) but real. Closure requires a
subscribe-before-snapshot + queue-and-filter pattern — out of scope
for V1; tracked in slice 436 §B.B Fix F.

## Cross-references

- `../../../../decisions/0037-universal-sse-envelope.md` — decision rationale.
- `slices/436-streaming-envelope.md` — implementation spec + §B
  appendixes.
- `../../../../decisions/0036-fe-resource-category.md` — typed Resource Category
  substrate; SSE_STREAM Resources adopt the envelope by default.
- `30-agent-workflows/01b-add-event-stream-resource.md` and
  `01c` / `01e` — per-Category recipes; each has a "Wire Format"
  subsection cross-referencing this doc.
