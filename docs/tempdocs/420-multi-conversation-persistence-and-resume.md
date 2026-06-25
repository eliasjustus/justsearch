---
title: Multi-Conversation Persistence and Session Resume
type: tempdocs
status: open
---

# 420 — Multi-Conversation Persistence and Session Resume

## Status

**OPEN — design stub.** Created 2026-04-26 after tempdoc 415 (AgentSession
Observability) shipped. 415 was the metric-substrate prerequisite; this
tempdoc layers the user-visible feature: persisting conversations across
restarts and resuming them on demand. The pattern doc
`docs/future-features/service-identity-lifecycle-pattern.md:100` lists
this as the driver feature for `AgentSession`'s eventual structural
shape.

This tempdoc owns the agent-continuity cluster identified in tempdoc
419's discovery pass: **C20** (Agent Session Resume and Replay), **C28**
(Notification-to-Session Continuity), **C33** (Agent Session Transcript
Export), and **C43** (Workspace Operation Timeline) — see
`docs/tempdocs/419-unused-user-agent-capability-discovery.md` ownership
annotations. **C44** (Agent History Contract Alignment) is a hygiene
prerequisite: it must ship as a 415 follow-up before 420's history
endpoints can claim audit-grade trust per 419's own P0 gate.

This document captures the design sketch from the 415 work; full
implementation requires user direction on scope and priority.

## Problem

`AgentRunStore` already persists per-run snapshots (`meta.json`,
`events.ndjson`) and `AgentLoopService.resumeLastSession` partially
implements resume (`AgentLoopService.java:1166`). But:

1. **Resume only works for the most recent session**, not arbitrary past
   conversations. `readLastSnapshot()` reads `last-session.txt`; there is
   no enumerate-all-conversations API.
2. **Session ID is conflated with run ID.** Each `runAgent` invocation
   generates a fresh UUID. A user who "resumes" a paused session today
   gets a NEW sessionId — metrics and event logs treat it as a different
   session. The user-visible concept "this is the same conversation"
   has no first-class representation.
3. **Resume is only safe in three states** (`WAITING_APPROVAL`,
   `READY_FOR_LLM`, `AFTER_TOOL_RESULT`) per `AgentRunStore.isResumableState`.
   Other terminal states reject resume with `UNSUPPORTED_RESUME_STATE`.
   Operationally fine; conceptually limiting (a user can't say "pick up
   where we left off" on a session that errored out).
4. **No UI affordance.** The MCP `lastSessionSnapshot` tool exists for
   debugging, but there's no `/api/agent/conversations` REST surface and
   no UI to browse / select / resume past conversations.

## Proposed primitive split (from tempdoc 415's structural theorization)

```java
public record ConversationId(String id) {}     // long-lived, persisted, user-visible
public record RunId(String id) {}              // one per runAgent invocation
public record SessionId(ConversationId conv, RunId run) {}  // composite
```

- **Conversation**: a logical user dialogue. May span multiple runs
  (resume = new run within same conversation). The user "sees"
  conversations.
- **Run**: one synchronous `runAgent` invocation, terminating in a
  disposition. Always one runStore meta.json per run.
- **Session**: the runtime entity (today: `AgentSession`); composite of
  `(ConversationId, RunId)`. Loop-state lives here.

Tempdoc 415's `agent.session.*` metrics treat each Run as a session.
This is correct for "how is the loop performing?" semantics. When 420
ships, **add `agent.conversation.*` as a separate metric family**:
- `agent.conversation.start_total` — fires when a new conversation
  begins (vs. resuming an existing one).
- `agent.conversation.duration_ms` — wall-clock time across all runs in
  a conversation.
- `agent.conversation.runs_total` — counter, tag = `disposition`.

No backward-incompatible rename of the 415 surface.

## API shape (sketch)

```
POST /api/agent/run/stream         (existing — augment with optional conversationId)
  body.conversationId?: string     // resume an existing conversation; omit for new
GET  /api/agent/conversations       (new)
  → list of {conversationId, lastUpdatedAt, lastDisposition, summary}
GET  /api/agent/conversations/{id}  (new)
  → full conversation: {id, runs: [{runId, startedAt, endedAt, disposition, summary}], messages: [...]}
POST /api/agent/conversations/{id}/resume  (new — convenience over runAgent + conversationId)
DELETE /api/agent/conversations/{id}       (new)
```

The existing `runStore.readEvents` and `readLastSnapshot` get
companion `enumerateConversations` and `readConversation(convId)`
methods. The on-disk layout becomes:

```
data/agent-runs/
  conversations/
    <conversationId>/
      meta.json                  (conversation-level metadata)
      runs/
        <runId>/
          meta.json              (existing per-run schema, gains conversationId field)
          events.ndjson
```

## Schema migration

Bump `AgentRunStore.CURRENT_SCHEMA_VERSION` to 4. v3→v4 upcaster:

- Per-run `meta.json` gains a `conversationId` field. Default:
  `conversationId = sessionId` (each pre-420 run becomes its own
  single-run conversation — preserves history, no data loss).
- Top-level layout migration: existing `data/agent-runs/<sessionId>/`
  directories move to `conversations/<sessionId>/runs/<sessionId>/`.
  This is a one-time directory rename on first read of legacy state.
  The `last-session.txt` pointer is preserved and now points at
  `conversations/<id>/runs/<id>/`.

## State machine implications

The pattern doc lists `AgentSession` as Form B (single-class
single-shot). With multi-run conversations, this still holds at the
*Run* level: each run gets a fresh `AgentSession` value. The
*Conversation* layer is a separate concept owned by `AgentRunStore`,
not by `AgentSession`. No structural refactor of `AgentSession`
needed for the basic resume feature.

Refinement possible if needed: introduce a sealed `RunOutcome`
(`Completed | Resumable(state) | Errored`) returned from
`runAgent`. This is the structural improvement sketched in the
earlier theorization. Out of scope for V1 of this tempdoc; revisit
if event-driven UI updates need typed dispatch.

## Out of scope

- **Cross-conversation search.** Searching message content from past
  conversations is a separate feature; depends on per-message
  redaction policy first (`SensitiveQuery` work, not metrics).
- **Conversation summarization.** Auto-generating a 1-line summary of
  each conversation for the list view. LLM-driven; depends on
  conversation persistence existing first.
- **Cross-device sync.** Local-first product; no cloud sync.
- **Concurrent runs within one conversation.** A conversation has one
  active run at a time. If a user opens the app and an old conversation
  is "running" (lease detection from the dev-runner pattern), the user
  gets an error or a takeover prompt — not parallel execution.

## Critical files (sketch)

**New:**
- `modules/app-agent-api/src/main/java/io/justsearch/agent/api/ConversationId.java`
- `modules/app-agent-api/src/main/java/io/justsearch/agent/api/RunId.java`
- `modules/app-agent/src/main/java/io/justsearch/agent/ConversationStore.java` — wraps `AgentRunStore` with conversation-level operations
- `modules/ui/src/main/java/io/justsearch/ui/api/AgentConversationsController.java` — REST surface for the new endpoints
- New `agent.conversation.*` metrics in `AgentMetricCatalog`
- Frontend UI: conversation list view, resume button, etc. (separate frontend tempdoc)

**Modified:**
- `AgentRunStore.java` — schema v4 upcaster + directory layout migration
- `AgentLoopService.runAgent` — accepts optional `ConversationId`; if present, persists run under that conversation; otherwise creates a new conversation
- `AgentService` interface — `enumerateConversations()`, `readConversation(id)`, `resumeConversation(id, eventConsumer)` methods
- `AgentSession` — gains `conversationId` field passed through from request

## Sequencing (rough)

Estimate: ~5–7 days, in slices.

1. Identity primitives + interface design (~½ day)
2. `AgentRunStore` schema v4 upcaster + directory layout migration + tests (~1 day)
3. `ConversationStore` wrapping AgentRunStore (~½ day)
4. `runAgent(conversationId)` plumbing through `AgentLoopService` (~1 day)
5. REST endpoints + Javalin routes + tests (~1 day)
6. `agent.conversation.*` metric family in `AgentMetricCatalog` (~½ day)
7. Frontend conversation-list UI (separate tempdoc)
8. End-to-end smoke + observability doc update (~½ day)

## Validation gate (sketch)

- Start a conversation, send a message, get a response, terminate.
- Send a follow-up message via `POST /api/agent/run/stream` with the
  prior `conversationId`. Verify the new run's `meta.json` carries that
  conversationId.
- `GET /api/agent/conversations` returns both runs grouped under one
  conversation entry.
- `agent.conversation.start_total` increments by 1 (one conversation
  started); `agent.session.start_total` increments by 2 (two runs).
- `meta.json` schema v3 fixtures from tempdoc 415 must upcast cleanly to
  v4 with `conversationId = sessionId` and the directory layout
  migration completing without data loss.

## Long-term considerations

- **The `AgentSession` god-object becomes more visible.** Splitting
  identity (Conversation) from runtime state (Session, in 415's sense)
  is forced by 420; the deeper structural refactor (sealed phase types)
  remains optional. If 420's design ships cleanly, the pattern doc's
  Form B verdict on `AgentSession` is reaffirmed; if it surfaces real
  pain, Form A becomes a follow-up.
- **The `mode` tag question revisited.** Tempdoc 415 dropped `mode` due
  to lack of source. With Conversation as a first-class concept, a real
  `mode` candidate emerges: `conversation_kind` (chat / rag / agentic),
  derived from the request shape. Not in 420's scope but worth noting.
- **Cross-run cancellation semantics.** Today, `cancelSession(sessionId)`
  cancels a run. With conversations, `cancelConversation(conversationId)`
  is a coarser-grained op. Resolve by API design when the feature ships.

## Dependencies

- **Blocked by**: tempdoc 415 (shipped 2026-04-26 — provides the per-run
  metric foundation that conversation-level metrics layer onto).
- **Blocks**: nothing currently. The hot-reload tempdoc (416), the
  structural refactor of AgentSession, and any conversation-content
  features (summarization, redaction-aware search) are independent
  follow-ons.

## Notes

This tempdoc is a **design stub**. It captures the design from tempdoc
415's "Long-term considerations" + the structural-refactor theorization
that informed the 415 implementation. Full implementation requires
user direction on scope, priority, and UX (conversation-list view
design — that's a frontend tempdoc, not this one).
