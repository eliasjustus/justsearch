---
title: "513 — Conversation Branching as ConversationStore Extension"
---

# 513 — Conversation Branching as ConversationStore Extension

**Status**: done
**Scope**: Add branching as a localized feature on the existing
`ConversationStore` substrate. No Resource dependency, no Operation
dispatch substrate change, no llama-server config change.
**Prerequisite**: Tempdoc 510 §G confidence report green on U6 (CAS-ready
storage) and U9 (AgentRunStore stays separate).

**Live verification (2026-05-18, dev stack with --llm):**
- Sent 3 messages in `sess-513-root`; backend writes carry `id` (UUID) + `hash` (sha-256) per message.
- POST `/api/chat/conversations/sess-513-root/branch?fromMsgId=<id>` returned `{sessionId: uc-..., parentSessionId: sess-513-root, branchPointMessageId: <id>}`.
- GET history on the branch returned 2 messages (the prefix up to and including the branch point), inherited from the parent via lazy resolution.
- GET history on the parent still returned 6 messages — unchanged by the branch operation.
- Sending a divergent message in the branch appended to the branch only; parent stays at 6.
- listSessions exposes `parentSessionId` + `branchPointMessageId` on the branched row, null on roots.
- FE UI (parent loaded): 6 messages, 0 inherited, 3 branch buttons (one per assistant), no banner.
- FE UI (branch loaded): 2 messages, both flagged `inheritedFromParent` with ↪ marker, banner visible, 0 branch buttons.
- Click "Branch here" on assistant msg 2: new session `uc-c903e63a-…` created, view navigates in, 4 messages inherited (first 2 turns), banner shown.
- History dropdown: branched rows show teal ↪ prefix marker.

**Unit tests:** 7/7 FileConversationStoreTest pass (append id/hash, legacy backfill, branchFrom prefix walk, listSessions exposure, unknown branch-point fallback, parent-cycle truncation).

## Thesis

A linear chat conflates three distinct user intents (course correction,
alternative exploration, rollback) into one gesture. Branching makes the
intent explicit: pick a point in the conversation, fork from there. The
old conversation is preserved; the new one inherits the prefix and
diverges from the fork point.

The implementation lives inside the existing conversation substrate
(`ConversationStore` + `ConversationEngine` + `ChatController` +
`ConversationListStore` + `UnifiedChatView` + `ConversationHistory`).
**No new substrate primitives.** This is the right scope per §G's
go/no-go: branching at the Resource layer would have required new
Category + governance ceremony; branching as a Store extension is
single-tempdoc work.

## Design decisions (flagged for review)

### A.1 — Each message gains `id` + `hash` fields

**Alternative considered**: `id` only.

**Chosen**: both `id` (UUID v4 hex) and `hash` (sha-256 of `role + "\x00" + content`).

**Reasoning**: `id` is the branch-from primary key. `hash` is a
**per-message content fingerprint** — sha-256 over the message's own
`(role, content)` only, NOT a Merkle chain over the preceding messages.
Cost is one sha-256 per append. The field is useful as a tamper-detection
fingerprint for a single message but does NOT verify parent-prefix
integrity across a thread (that would require chaining `prevHash` into the
digest). Slice 515 §FIX-6 corrected an over-claim in this section's
earlier wording (which suggested forward-compat for content-addressed
storage and chain integrity); those uses would require Merkle-style
chaining, which has no consumer today. The field as-shipped is correctly
scoped to single-message fingerprinting.

### A.2 — Parent pointers live in meta.json, not in messages.jsonl

**Alternative considered**: encode parent + branch-point as a synthetic
first line in `messages.jsonl` (`{role: "system", "$kind": "branch-from",
parent: ..., from_msg_id: ...}`).

**Chosen**: store `parentSessionId` + `branchPointMessageId` as
optional top-level fields in `meta.json`.

**Reasoning**: meta.json is already mutated atomically per append; adding
two optional string fields fits its role. messages.jsonl stays a pure
linear thread (no metadata-vs-content discrimination needed in
`loadHistory`). Symmetry: `listSessions` already reads meta.json — it
gets the parent pointers for free without parsing each session's jsonl.

### A.3 — `loadHistory` walks the parent chain transparently

**Alternative considered**: copy parent messages into the branch's
`messages.jsonl` at branch time (eager copy).

**Chosen**: store no messages at the branch; resolve the prefix on each
`loadHistory` call by walking `parentSessionId`.

**Reasoning**: lazy resolution preserves the "branch is just a pointer"
property — editing or deleting the parent affects branches (which the
user might want; if they don't, we can add a `copyOnBranch` flag later).
Disk usage stays O(N total messages across all branches), not O(N ×
depth). KV cache prefix sharing (when enabled) keys on the prompt prefix
identity; eager copy would defeat that.

### A.4 — Backward compatibility via read-time field synthesis

**Alternative considered**: rewrite existing JSONL files on startup to
backfill `id`+`hash`.

**Chosen**: leave old data alone. On `loadHistory`, if a message lacks
`id`/`hash`, compute deterministically: `id = "idx-" + index`,
`hash = sha256(role + "\x00" + content)`.

**Reasoning**: no migration step; old sessions remain branchable (their
synthetic ids are deterministic so the FE can pass them back to the
branch endpoint); new sessions write real UUIDs. No risk of data loss
from a migration script.

### A.5 — Agent-run branching is explicitly out of scope

Per §G U9, `AgentRunStore` is deliberately separate from
`ConversationStore` (richer state model: tool calls, traces, handoff
state, budget tracking). Branching an agent run is a different feature
with different semantics (do you replay tool calls? re-establish
budgets?) and lives outside 513.

## Closure criteria

1. **Storage layer**: `FileConversationStore.appendMessage` writes `id`
   + `hash` on every new message (synthesizing if absent from the
   incoming Map). `loadHistory` returns messages with both fields
   populated (synthesizing for legacy data).
2. **Interface**: `ConversationStore` gains `branchFrom(parentSessionId,
   branchPointMessageId, newSessionId)`. `SessionSummary` gains
   `parentSessionId` + `branchPointMessageId` optional fields.
3. **Engine**: no engine changes needed — it appends maps; the store
   adds the metadata. (Verify by tracing.)
4. **REST**: `POST /api/chat/conversations/{sessionId}/branch?fromMsgId=...`
   returns `{ sessionId, parentSessionId, branchPointMessageId }`. The
   handler generates a new UUID for the branch session.
5. **FE store**: `ConversationListStore.branchConversation(parentId,
   msgId): Promise<string>` calls the endpoint, returns new sessionId,
   `recordRecentSession`'s it.
6. **FE UI — Branch here button**: each assistant message in
   `UnifiedChatView` shows a "Branch here" affordance on hover. Click
   → `branchConversation` → navigate to chat surface with new session
   (resume prompt resolves it).
7. **FE UI — branch indicator**: a row in the `UnifiedChatView` thread
   above the inherited prefix that says "↪ Branched from earlier
   conversation." History dropdown shows "↪ from \"<root preview>\""
   beneath each branched conversation.
8. **Unit tests**: storage roundtrip (write+read preserves id/hash),
   loadHistory prefix walk, branchFrom creates meta with parent
   pointers, listSessions exposes the new fields.
9. **Live verification on the dev stack with `--llm`**:
   - Send 3 messages in a fresh conversation A. Open History →
     verify A appears.
   - Branch from message 2 of A. Verify the new conversation B opens
     in the chat surface with messages 1–2 visible, plus the "↪
     Branched from earlier conversation" indicator above them.
   - Send a message in B. Verify the response references the prefix
     context.
   - Reload, open History → verify both A and B are listed; B shows
     the "↪ from..." indicator.
   - Open A → verify it still has its original 3 messages, no
     contamination from B.

## Known constraints (carried from §G)

- **KV cache prefix-sharing is OFF** in our llama-server config (§G U4).
  Branching saves disk via lazy prefix resolution, but inference re-
  prefills the prompt on every branch invocation. Follow-up: a separate
  tempdoc to add the `--cache-reuse` flag to llama-server's argv.
- **No agent-run branching** per §A.5. The agent-inbox follow-up belongs
  in a different tempdoc projecting from `AgentRunStore` via
  `core.operation-history` Resource.

## Critical files

**Backend:**
- `modules/app-agent-api/.../conversation/ConversationStore.java`
- `modules/app-agent-api/.../conversation/NoOpConversationStore.java`
- `modules/app-services/.../conversation/FileConversationStore.java`
- `modules/ui/.../api/ChatController.java`
- `modules/ui/.../api/routes/AiRoutes.java`

**Frontend:**
- `modules/ui-web/src/shell-v0/state/conversationListStore.ts`
- `modules/ui-web/src/shell-v0/views/UnifiedChatView.ts`
- `modules/ui-web/src/shell-v0/components/chat/ConversationHistory.ts`

## Verification commands

- `./gradlew.bat :modules:app-agent-api:test :modules:app-services:test :modules:ui:test`
- `cd modules/ui-web && npm run typecheck && npm run test:unit:run`
- Live: `justsearch_dev_start { hotReload: false }` → browser flow per
  closure criterion §9.

---

# Consolidated satellites (folded 2026-06-09, post-400 hygiene pass)

> The branching correctness-fixes (515) and followups (522/523) folded in — they were sub-efforts of this feature, not standalone designs.

## Branching correctness fixes (was 515)

*(folded from `515-branching-correctness-fixes.md`)*

### 515 — Branching + askAi correctness fixes

**Status**: done

**Live verification results (2026-05-18, dev stack with --llm):**
- FIX-1: dispatch body intercept on `/api/chat/dispatch` confirmed
  `docIds: ["/test/path.txt"]` reached the body (previously hardcoded
  to `[]`). RAG scoping now works.
- FIX-2: POST `/branch?fromMsgId=BOGUS-DOES-NOT-EXIST` returned HTTP 400
  with `{"error":"Branch point message id not found in parent: ...",
  "errorCode":"INVALID_REQUEST"}`.
- FIX-3: DELETE on `sess-513-root` (has 3 child branches) returned HTTP
  409 with `childSessionIds: [<3 ids>]`.
- FIX-5: askAi dispatch state contains only `[query, affordance, docIds]`
  — no `kind` field. URL projector reflects this (no `?kind=...`).
- FIX-7: `/api/chat/conversations/{id}/history` for a branched session
  returns `parentSessionId` + `branchPointMessageId` +
  `parentFirstUserMessage="Reply: T1"` via the new `getSessionMeta`
  lookup (one O(1) read instead of the O(N) listSessions scan).
- FIX-8: branched session banner reads `↪ Branched from "Reply: T1"` —
  parent preview surfaces correctly.

**Unit tests:** 17/17 FileConversationStoreTest pass (10 from 513 + 7
new). 9/9 askAi.test.ts pass (post-FIX-5). 10/10
conversationListStore.test.ts pass (new file). 1331/1331 total FE unit
tests pass (was 1305 — +26 from FIX-9 coverage).
**Scope**: 11 fixes to defects identified in critical analysis of
tempdocs 513 (conversation branching) and 514 (typed askAi intents).
Includes one pre-existing silent bug (docIds dropped on send), one C-018
substrate-without-consumer violation introduced by 514, and several
correctness + UX gaps in 513.
**Prerequisite**: Tempdocs 513 + 514 shipped.

## Thesis

Tempdocs 513 + 514 shipped working features with three categories of
latent defects:

1. **A silent pre-existing bug** — `UnifiedChatView.send()` hardcoded
   `body.docIds = []`, so every `askAi(...docIds...)` call from
   BrowseSurface/SearchSurface silently degraded to open-retrieval. The
   bug predates 514; 514's typed-intent investigation surfaced it.
2. **A substrate-without-consumer violation** — 514's `kind` discriminant
   carried through `unifiedChatState` + URL projector with intent to drive
   future ConversationShape routing. No consumer materialised; the three
   current kinds don't structurally differentiate on shape. Per CLAUDE.md
   C-018, this is "ship a slot for nobody."
3. **Correctness + UX gaps in 513** — branchFrom silently fell back to
   full-parent prefix on bad branch-point ids; deleteSession orphaned
   branches; syncMessageIds raced; branch UI banners were generic.

Slice 515 lands the corrections.

## Fixes shipped

### FIX-1: wire docIds through `UnifiedChatView.send`

`modules/ui-web/src/shell-v0/views/UnifiedChatView.ts` — added a
`pinnedDocIds` reactive field, captured from `unifiedChatState.docIds`
before `resetUnifiedChatState` clears the store, and passed to
`buildRequestBody`. The hardcoded `body.docIds = []` is now
`body.docIds = docIds`. `newConversation()` clears pinnedDocIds so a
fresh thread doesn't inherit prior docIds.

**Effect:** `askAi({kind: 'summarize-doc', docId: file.path, ...})` from
BrowseSurface now actually scopes RAG retrieval to the referenced doc.
The bug had been silent because `RAGContext.java` falls back to
open-retrieval when docIds is empty.

### FIX-2: branchFrom validates branch-point existence

`FileConversationStore.branchFrom` now scans the parent's resolved
history for the `branchPointMessageId`. If not found, throws
`IllegalArgumentException`. `ChatController.handleBranchConversation`
catches and returns HTTP 400 with structured error. Replaces the silent
"fall back to full-parent prefix" behavior. Same path also rejects
unknown parent sessionId (previously logged + silently no-op).

### FIX-3: parent-deletion safety

`FileConversationStore.deleteSession` now scans for child branches
before deletion. If any exist, throws `BranchesPreventDeletionException`
(extends `IllegalStateException`) carrying the child session ids.
`ChatController.handleDeleteConversation` returns HTTP 409 Conflict with
`childSessionIds[]` in the body. The throwaway-title sessions (prefix
`_title_`) bypass the check since they're internal cleanup. Future
cascade-delete UX can live on top of this 409.

### FIX-4: syncMessageIds race + perf

`UnifiedChatView` adds a `private syncToken = 0` counter; each
`syncMessageIds` invocation bumps the token; the response is discarded
if a newer invocation has run in the meantime. Also early-exits the
splice loop once all unmatched ids are matched. No more last-write-wins
clobbering under rapid sends.

### FIX-5: remove `kind` carry-through (C-018 dissolution)

`unifiedChatState` no longer carries a `kind` field through serialize +
restore. `askAi.ts`'s `renderIntent` no longer emits `kind` into the
navigation-state payload. The `AskAiIntent` discriminated union remains
in `askAi.ts` as a call-site type contract — the discriminant is
consumed at dispatch time (`renderIntent` picks template + affordance per
kind) but is not propagated downstream.

Honest framing: 514's typed-intent shape is a call-site type contract,
not substrate. Per-kind ConversationShape routing was deferred to "V2"
in 514 — V2 never materialised, and investigation showed it wouldn't
have differentiated anyway. The slot is removed; if a future kind needs
different routing (e.g., `extract-from-doc` going to `core.extract`), the
discriminant can be re-introduced with a named consumer.

### FIX-6: hash semantics — drop the chain claim

`docs/tempdocs/513-conversation-branching.md` §A.1 rewritten to scope the
`hash` field to "per-message content fingerprint" — sha-256 of the
message's own `(role, content)` only, NOT a Merkle chain. The original
claim of forward-compat for content-addressed storage + parent-prefix
integrity was an over-claim; those would require chaining `prevHash`.
The field as-shipped is correctly scoped to single-message fingerprinting;
implementing Merkle is deferred until a consumer exists (would itself be
C-018 to add speculatively).

### FIX-7: ConversationStore.getSessionMeta

New interface method `Optional<SessionSummary> getSessionMeta(String)`
on `ConversationStore`, implemented in `FileConversationStore` (delegates
to the private `readMeta` helper) and `NoOpConversationStore`.
`ChatController.handleLoadHistory` now uses it instead of the previous
`listSessions(null, 1000).stream().filter(...)` O(N) scan.

### FIX-8: branch UI carries parent context

`ChatController.handleLoadHistory` response gains
`parentFirstUserMessage` when the session is a branch (looked up from
the parent's meta via FIX-7's `getSessionMeta`). `UnifiedChatView` reads
it into a new `parentFirstMessagePreview` reactive field; the branch
indicator banner renders `↪ Branched from "<preview>"` when present,
falling back to the generic banner otherwise. `ConversationHistory`
dropdown's ↪ marker carries a hover-title naming the parent (looked up
from the loaded conversations array; generic fallback if parent isn't in
the loaded list).

### FIX-9: test coverage gaps

Backend (`FileConversationStoreTest`) — 7 new tests:
- branchFrom rejects unknown branch-point (FIX-2)
- branchFrom rejects unknown parent (FIX-2)
- branchFrom works with synthesized `idx-N` id (legacy data path)
- branch prefix is bounded by branch-point even when parent grows
- deleteSession blocks when child branches exist (FIX-3)
- deleteSession of a leaf session succeeds (FIX-3)
- getSessionMeta for known + unknown session (FIX-7)

Frontend (`conversationListStore.test.ts`) — new test file, 10 tests:
- branchConversation roundtrip + URL encoding + error paths
- fetchMessageIds happy path + non-2xx + network error
- resumeConversation surfaces parent pointers when present

### FIX-10: tempdoc 514 framing honesty

`docs/tempdocs/514-typed-askai-intents.md` rewritten title + intro to
match shipped scope: "Discriminated-union argument shape for askAi."
Drops the "Intent as universal verb consolidation" framing. Cross-links
the FIX-5 `kind` removal.

### FIX-11: canonical doc updates

`docs/reference/api-contract-map.md` — added the four conversation-
management endpoints (list, history, delete, branch) with their slice
references and HTTP status semantics (409 on parent-with-branches, 400
on bad branch-point).

`docs/llms.txt` — regenerated.

## Closure criteria

1. All 11 fixes implemented as above.
2. Backend builds clean.
3. Frontend typecheck clean.
4. Unit tests:
   - 17/17 FileConversationStoreTest (10 from 513 + 7 new from FIX-9)
   - 9/9 askAi.test.ts (post-FIX-5 update)
   - 10/10 conversationListStore.test.ts (new from FIX-9)
   - 1315+/1315+ total FE unit tests pass.
5. Live verification on dev stack:
   - BrowseSurface "Summarize \<file\>" produces a body.docIds containing
     the file path (FIX-1)
   - POST /branch with bogus fromMsgId returns 400 (FIX-2)
   - DELETE on a session-with-branches returns 409 with childSessionIds
     (FIX-3)
   - URL after askAi dispatch has no `kind` query param (FIX-5)
   - Branched session banner shows parent preview (FIX-8)
   - History dropdown ↪ shows hover-title naming the parent (FIX-8)

## Out of scope

- Cascade-delete UX (can layer on top of FIX-3's 409 response)
- Merkle hash chaining (deferred — no consumer)
- Eager-copy parent prefix on deletion (deferred — block-with-409
  preferred as default)
- Per-kind ConversationShape routing (deferred — no kind genuinely needs
  a different shape today)
- Voice / ACP IntentSource (separate tempdocs)
- Conversation-as-Resource refactor (still no-go per tempdoc 510 §G)

## Branching followup (was 522)

*(folded from `522-branching-followup.md`)*

### 522 — Branching followup (race, locality, coupling)

*Renumbered from 516 on 2026-05-18 (per `docs/tempdocs/README.md`
§"Renumbering procedure") to free 516 for `indexingloop-size` which
is the more-cited 516. Prose citations using the slug
`branching-followup` continue to resolve.*

**Follow-up:** Slice 517 FIX-U2 closes the soft spot in this slice's T5
("test verifies the abort mechanism, not the bug absence") by mocking
`consumeShapeStream` with a never-resolving Promise and asserting
`view.thread === []` after a mid-stream conversation switch. The race
is now bug-absence-verified, not just mechanism-verified.


**Status**: done

**Live verification (2026-05-18, dev stack with --llm):**
- FIX-T1: spied on `AbortController.abort()`, set the spy as
  `view.abortController`, fired the conversation-select event the
  dropdown emits, observed `abort` called exactly once before
  `loadConversation` mutated `sessionId` / `thread`. New session loaded
  cleanly with the expected 6 messages from the resumed conversation;
  no contamination from the stale stream.
**Scope**: 5 fixes (T1–T5) to defects identified in critical-analysis of
tempdoc 515. One real race (cross-session SSE-stream contamination), one
defense-in-depth (syncMessageIds session capture), and three quality
cleanups (exception locality, throwaway-prefix constant, coverage gaps).
**Prerequisite**: Tempdocs 513 + 514 + 515 shipped.

## Thesis

515 fixed 11 defects in branching/typed-intents but introduced one new
issue and missed one inherited one:

1. **Cross-session SSE-stream contamination** — `UnifiedChatView.send()`
   sets `this.abortController` but `loadConversation`, `newConversation`,
   and `branchHere` never abort it. An in-flight stream's `onDone` writes
   its assistant message into the NEW conversation's thread. The 515
   `syncToken` fix narrowed `syncMessageIds` but didn't address the
   deeper onDone-writes-to-wrong-thread bug.
2. **`BranchesPreventDeletionException` leaked across modules** — defined
   in `FileConversationStore` (app-services) but caught in
   `ChatController` (ui module). Implementation leak across the
   `ConversationStore` interface abstraction.
3. **`_title_` prefix duplicated literal across Java + TS** — fragile
   cross-language coupling; renaming on one side silently breaks the
   bypass on the other.

Slice 516 lands the corrections + coverage.

## Fixes shipped

### FIX-T1: abort in-flight stream on conversation switch

`UnifiedChatView.ts` — `loadConversation` and `newConversation` now call
`this.abortController?.abort()` before mutating `sessionId` / `thread`.
`branchHere` inherits via `loadConversation`. Per Explore-agent
investigation, AbortError is caught by `consumeShapeStream` in
`api/streams.ts` (lines 1234–1236); neither `onDone` nor `onError`
fires; the try/finally cleanly resets `isStreaming = false`. No new
thread mutation, no `syncMessageIds` invocation, no contamination.

### FIX-T2: syncMessageIds captures sessionId

`UnifiedChatView.syncMessageIds` — captures `mySession = this.sessionId`
alongside `myToken`; on response, discards splice if either changed.
Defense in depth — even with T1 in place, any future code path that
switches conversations without aborting would still be guarded.

### FIX-T3: BranchesPreventDeletionException moved to api module

New file:
`modules/app-agent-api/src/main/java/io/justsearch/agent/api/conversation/BranchesPreventDeletionException.java`.
Public final, extends `IllegalStateException`, carries
`List<String> childSessionIds`. Matches `ConfirmationRequiredException.java`
+ `TrustGateDeniedException.java` packaging style in the adjacent
registry package.

`FileConversationStore.java` — drops the nested class; imports the new
file. `ChatController.java` — imports via
`io.justsearch.agent.api.conversation.BranchesPreventDeletionException`
instead of the impl class. `FileConversationStoreTest.java` — import +
class reference updated. Behavior unchanged.

### FIX-T4: THROWAWAY_SESSION_PREFIX constant

`ConversationStore.java` (interface) — new constant
`String THROWAWAY_SESSION_PREFIX = "_title_"` with a JavaDoc note that
the TS producer side
(`ConversationListStore.generateConversationTitle`) MUST use this exact
spelling. `FileConversationStore.deleteSession` references the constant
instead of the literal `"_title_"`. TS side has a comment block
pointing at the Java spec as the single source of truth.

Cross-language constants don't share a literal at compile time — the
comment is the discipline. A future codegen pipeline could pull the
constant from the Java SSOT if a second cross-language constant ever
exists; YAGNI until then.

### FIX-T5: coverage gaps

Backend (`FileConversationStoreTest`) — 1 new test:
- `_title_`-prefixed session deletes successfully even when meta points
  at a parent (defense against the bypass regressing).

Frontend (`conversationListStore.test.ts`) — 3 new tests:
- `resumeConversation` surfaces `parentFirstUserMessage` from the
  response (covers FIX-8 from 515).
- `branchConversation` records the new session as recent when a
  `firstMessagePreview` is supplied.
- `branchConversation` does NOT record when no preview is supplied.

Frontend (`UnifiedChatView.test.ts`) — new file, 4 tests:
- `loadConversation` calls `abortController.abort()` (FIX-T1).
- `newConversation` calls `abortController.abort()` (FIX-T1).
- Abort is a no-op when no stream is in flight (null safety).
- View mounts with a fresh `sessionId` (sanity).

## Verification commands

- `./gradlew.bat :modules:app-agent-api:compileJava :modules:app-services:test :modules:ui:compileJava`
- `cd modules/ui-web && npm run typecheck && npm run test:unit:run`
- Live verify on dev stack:
  1. Start a long-running send on conversation A → switch to B in the
     History dropdown mid-stream → assert B's thread doesn't carry a
     ghost assistant message from A's stream. Confirms FIX-T1
     end-to-end.

## Closure criteria

1. All 5 fixes implemented.
2. Backend builds clean; FileConversationStoreTest passes (18 total =
   10 from 513 + 7 from 515 + 1 from 516).
3. FE typecheck clean; 1339+ total FE unit tests pass (was 1331 — +8
   from FIX-T5).
4. Live verification of FIX-T1 on the dev stack (single user-driven
   test).

## Out of scope

- Indexing parent→children for O(1) findChildBranches (deferred)
- TOCTOU race between findChildBranches and concurrent branchFrom
  (deferred; single-user UI; recoverable)
- Codegen for cross-language constants (no second one yet)
- pinnedDocIds clearing on affordance toggle (deferred UX nit)
- Cascade-delete UX on top of the 409 (separate UX feature)

## Reversibility

All 5 fixes are reversible single-commit changes. T1 is the only one
that changes behavior; T2 is a guard; T3/T4 are pure refactors; T5 adds
tests only.

## Cascade-delete followup (was 523)

*(folded from `523-cascade-delete-followup.md`)*

### 523 — Cascade-delete UX + true SSE-race test

*Renumbered from 517 on 2026-05-18 (per `docs/tempdocs/README.md`
§"Renumbering procedure") to free 517 for `search-execution-design`.
Prose citations using the slug `cascade-delete-followup` continue to
resolve.*

**Status**: done

**Live verification (2026-05-18, dev stack):**
- Without callback: `deleteConversationWithCascade('sess-513-root')`
  returned `{ok: false, childIds: [3 ids]}`. Parent stays intact; 409
  body's childSessionIds propagated correctly.
- Declining callback: returned `{ok: false, childIds: [3 ids]}`;
  callback was invoked once with the 3 child ids; no deletions occurred.
- Accepting callback: returned `{ok: true}`; backend listSessions
  confirmed parent (`sess-513-root`) and all 3 children
  (`uc-890e1c65...`, `uc-96a66c67...`, `uc-c903e63a...`) removed. The
  3 unrelated sessions (`s514-probe`, two pre-existing uc-…) remained
  intact — cascade scope correctly bounded to declared children.
**Scope**: 2 fixes (U1, U2) addressing deferred items from tempdoc 516.
A planned third item (pinnedDocIds clearing on affordance toggle) was
explicitly retracted after investigation — see Out of scope.
**Prerequisite**: Tempdocs 513 + 515 + 516 shipped.

## Thesis

516 left two confirmed-ready follow-ups:
- a cascade-delete UX hole (515's 409 response carried `childSessionIds`
  but `deleteConversation` silently swallowed them, so the user couldn't
  delete a parent without manually deleting each branch first);
- a soft test (the 516 race test verified the abort mechanism, not the
  bug absence).

Confidence-building probes (3 Explore agents) confirmed both are doable
without backend changes or substrate refactors. The probes also produced
one **material reversal**: the proposed "clear pinnedDocIds on affordance
toggle" item didn't survive scrutiny — affordance is per-turn shape
choice, docIds are session-scoped context, and toggling Schema-then-back-
to-Documents should preserve the pin. Current 516 behavior is correct;
the proposed item is retracted.

## Fixes shipped

### FIX-U1: cascade-delete UX

**Store-level addition:** new `deleteConversationWithCascade(sessionId,
onChildsFound?)` in `conversationListStore.ts`. Behavior:
- Try DELETE.
- On 2xx: update local state, return `{ok: true}`.
- On 409 with `BRANCHES_PREVENT_DELETION`: parse `childSessionIds` from
  the response body; if no callback, return `{ok: false, childIds}`;
  with a callback, invoke `onChildsFound(childIds): Promise<boolean>`.
- If user confirms (callback returns true): delete each child
  sequentially; if any fails, abort the cascade and leave the parent
  intact. If all succeed, retry the parent DELETE.

Sequential, not Promise.all — simpler error semantics, cascade depth is
typically 1, cost is bounded by N branches.

The original `deleteConversation` signature is preserved for backward
compat; the dropdown switches to the cascade variant.

**UI wiring:** `ConversationHistory.onDelete` calls
`deleteConversationWithCascade` with a callback that:
- looks up each child's title from `this.conversations` (no extra
  roundtrip; child rows are already in the loaded list),
- shows a second `confirmAsync` listing the child titles ("Delete the
  conversation and N branches together?"),
- returns the user's confirm result.

If the user cancels at the cascade-confirm, **nothing is deleted** — not
even the parent. This is the safe default; aggressive "delete parent
anyway" would orphan branches via a different code path.

### FIX-U2: true SSE-race simulation test

**Goal:** lock the 516 FIX-T1 fix at the bug-absence level, not just the
mechanism (abort-called) level.

**Approach:** extended `UnifiedChatView.test.ts` with a `vi.mock` of
`consumeShapeStream` that returns a never-resolving Promise. The test:
1. Mounts the view + types a message + sets affordance to documents.
2. Fires `send()` — the stream starts and stays in flight forever.
3. Asserts `isStreaming === true` (proves the stream is mid-flight).
4. Calls `loadConversation('uc-other')` mid-stream.
5. Asserts:
   - `view.thread === []` (no ghost user-or-assistant message from the
     aborted stream contaminated the new conversation's thread)
   - `view.sessionId === 'uc-other'` (new session loaded)
   - `view.abortController?.signal.aborted === true` (the abort
     mechanism fired)

This proves the bug is GONE, not just that the abort method was called.

## Closure criteria

1. Both fixes implemented.
2. Typecheck clean.
3. Unit tests:
   - 5/5 UnifiedChatView.test.ts (4 from 516 + 1 new race test = U2)
   - 18/18 conversationListStore.test.ts (13 from 515+516 + 5 new
     cascade tests = U1)
   - 1345 total FE unit tests (was 1339).
4. Live verify: delete a branched parent → first confirm "Delete X?" →
   on confirm, second confirm "+ N branches" listing child names → on
   confirm, all gone. Cancel-at-cascade-confirm leaves the parent
   intact.

## Out of scope (explicit)

- **pinnedDocIds clearing on affordance toggle.** Investigation showed
  the current behavior is correct: affordance is per-turn shape choice;
  docIds are session-scoped context. Toggling Schema-then-back should
  preserve the pin. Explicitly retracted.
- **Parent→children index for O(1) findChildBranches** — still deferred
  (current O(N) fine for N ≤ 1000).
- **TOCTOU race in findChildBranches** — still deferred (single-user UI,
  recoverable).
- **Per-kind ConversationShape routing, voice IntentSource, ACP
  IntentSource, Conversation-as-Resource refactor** — all deferred per
  their original tempdocs.

## Reversibility

U1 adds `deleteConversationWithCascade` as a new export; the existing
`deleteConversation` signature is preserved. U2 is test-only. Both are
reversible single-commit changes.
