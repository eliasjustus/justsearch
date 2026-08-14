# 838 — Conversation title authority: design (833 W7)

```
status: IMPLEMENTED — unit + gate tiers green; the §8d live round is PENDING a
  dev-stack lease (see §10)
created: 2026-08-14
updated: 2026-08-14
related: 833 (§W7, the theorization this designs), 822 (the Search v3 window —
  the only rename producer), 610 (the meta.json field family this extends),
  629 (at-rest encryption / the content-vs-structural split), 562 (the
  localStorage-plaintext leak this must not re-create), 516 (the `_title_`
  throwaway-session contract)
```

## The question, and the one-line answer

A conversation's *name* is the only fact about a conversation that the browser
owns. Everything else the sidebar renders — existence, timestamps, message
count, first message, branch pointers, context floor — comes from the Head's
encrypted conversation store. The name lives in
`localStorage['jf-conversation-titles']`
(`modules/ui-web/src/shell-v0/state/conversationListStore.ts:39,91-103`), which
means it does not survive a profile change, a cleared site-data, or a second
client, and it is *plaintext at rest outside the encryption boundary* while the
conversation it names is sealed.

**The design: `title` becomes a sealed field of the session's `meta.json`, a
`SessionSummary` component, and one `POST /api/chat/conversations/{sessionId}/title`
(plus `DELETE` to clear). The frontend writes through to it and stops writing
localStorage; the legacy map becomes a read-fallback that erases itself as it is
adopted.** Pins / shelves / unread stay window-local and are put to the owner in
§5 as a product question, not decided here.

## 1. The title seam

### 1a. Storage — one more sealed meta field

`meta.json` already carries five optional additive fields written through one
atomic pattern:

| field | added by | sealed? |
|---|---|---|
| `firstUserMessage` | list preview | yes (`FileConversationStore.java:557`) |
| `parentSessionId` / `branchPointMessageId` | slice 513 branching | no (structural) |
| `contextFloor` | 610 Phase C (`FileConversationStore.java:362-386`) | no (an id) |
| `contextFloorSummary` | 610 Phase D | yes (`:557`) |
| `excludedMessageIds` / `excludedSourceIds` | 610 §E.3/§J.3 (`:436-460`) | no (ids) |

`title` joins `META_CONTENT_FIELDS`
(`modules/app-services/src/main/java/io/justsearch/app/services/conversation/FileConversationStore.java:557`).
**It is content, not structure.** An auto-title is a model's summary of the
messages, and a reader's title is a sentence about them; either one leaks the
subject of a sealed conversation. Sealing it makes the list behave exactly as
it already does for `firstUserMessage`: while the store is locked, `listSessions`
catches `KeyLockedException` per session and renders the row without content
(`:262-273`) rather than dropping it or failing the list.

Write path — reuse, do not invent. `setContextFloor` (`:362-386`) is the closest
analogue: a *scalar* optional meta field, read-modify-write through
`writeMetaAtomic` (`:475-483`), which stamps `schemaVersion`
(`versionedMeta`, `:617-621`) and seals content fields (`withSealedMeta`,
`:560-569`) on the way out. A new private `setTitle(sessionId, title)` on
`FileConversationStore` mirrors it verbatim; the interface gains
`void setTitle(String sessionId, String title)` on `ConversationStore`
(`modules/app-agent-api/src/main/java/io/justsearch/agent/api/conversation/ConversationStore.java`),
with a no-op default so `NoOpConversationStore` and any test double keep
compiling — the shape `excludeSource` already uses (`ConversationStore.java:165`).

Two properties worth stating because they are not obvious:

- **`updateMeta` (the per-message append path, `:665-692`) is safe by
  construction.** It reads via `readRawMeta` (`:670`) — sealed values stay
  sealed strings — and writes via `withSealedMeta`, which skips anything already
  sealed (`:564`). So an appended message cannot decrypt-and-re-encrypt, or worse
  strip, a stored title, and appending while locked cannot destroy it.
- **A rename while locked answers 423 for free.** `setTitle` goes through
  `readMeta` → `openMetaContent` (`:571-577`), which raises `KeyLockedException`,
  and `LocalApiServer.java:463-474` already maps that to
  `423 {"locked":true,"errorCode":"STORE_LOCKED"}`. No new controller arm.

### 1b. Schema version: no bump

`CURRENT_SCHEMA_VERSION` stays 1 (`:47`). `title` is additive and optional; a v1
meta written before this change simply lacks the key, and `requireReadable`
(`modules/configuration/src/main/java/io/justsearch/configuration/persistence/StoreFormatVersions.java:15-31`)
only compares the version integer. A bump would be actively wrong: it would make
today's Head refuse metas written by tomorrow's, for a field whose absence means
exactly "no title".

### 1c. Projection — `SessionSummary` + the list endpoint

`SessionSummary` (`ConversationStore.java:177-191`) gains a trailing
`String title` component. Its three back-compat constructors (`:193-232`) get a
fourth in the same style, so the existing 6/8/9-arg call sites are untouched;
`FileConversationStore.listSessions` (`:274-284`) and `getSessionMeta`
(`:349-359`) pass the new value. `ChatController.handleListSessions`
(`modules/ui/src/main/java/io/justsearch/ui/api/ChatController.java:192-206`)
emits it conditionally, exactly like the parent pointers:
`if (s.title() != null && !s.title().isEmpty()) m.put("title", s.title());`.

One decision to record: `listSessions` decrypts content fields inline and
substitutes a blank on lock (`FileConversationStore.java:267-273`); `title` joins
that `try` block. `getSessionMeta` goes through `readMeta`, which *propagates*
`KeyLockedException` (`:584-586`) — that asymmetry is pre-existing and correct
(the history endpoint must 423, the list must not), and `title` inherits it
unchanged.

### 1d. The route — POST, not PATCH

The brief proposed `PATCH`. **There is no `PATCH` route in the Head**: a grep of
`modules/ui/src/main/java/io/justsearch/ui/api/routes/*.java` for `app.patch`
returns zero hits. The conversation family's house style
(`AiRoutes.java:74-99`) is *POST to set, DELETE to clear*, established by
context-floor (`:80-86`) and used by compact / summary / exclude (`:87-100`).

```
POST   /api/chat/conversations/{sessionId}/title   body {"title": "..."}  → {"ok":true,"title":"..."}
DELETE /api/chat/conversations/{sessionId}/title                          → {"ok":true}
```

Handlers mirror `handleSetContextFloor` / `handleClearContextFloor`
(`ChatController.java:268-298`), including the body-parse `try` and the
`ApiErrorCode.INVALID_REQUEST` 400 shape.

**Validation.**

- Trim; reject blank with 400 `INVALID_REQUEST`. Clearing is `DELETE`, never
  `POST ""` — one verb per meaning, and it keeps the FE's existing rule
  (`sv3-sessions.ts:702-712`: an empty rename is a *revert*, not a clear) from
  needing a second interpretation server-side.
- Length cap **200 characters**, matching the `firstUserMessage` preview cap
  (`FileConversationStore.java:684-685`). Over-length is truncated, not
  rejected — the FE's own auto-title guard already rejects ≥80
  (`conversationListStore.ts:251`), so a 200-char server cap is a backstop
  against a pathological client, not a UX rule.
- **404 `NOT_FOUND` when the session has no `meta.json`.** This is a deliberate
  divergence from `setContextFloor`, which *materialises* a meta for an unknown
  session (`:373-385`). A materialised title would mint a zero-message
  conversation that `listSessions` then lists — a row that names nothing. The
  race it exposes (rename between conversation-open and the first
  `appendMessage`) is sub-second and handled on the FE side in §2.

### 1e. Governance: does `check-store-recoverability` fire?

**No, and here is why, because "the gate probably doesn't care" is not evidence.**
The gate (`scripts/ci/check-store-recoverability.mjs`) asserts three things:
`StoreCatalog` ↔ register parity *by store directory name* (`:46-70`), per-row
field validity for `governance/store-recoverability.v1.json` (`:94-176`), and
that every discovered persistence write site is a registered
`implementationSource` (`:190-196`, sites found by
`scripts/governance/lib/persistence-write-scan.mjs`). Adding a field to
`meta.json` adds no store directory, no write site, and no new file:
`FileConversationStore.java` is already the `conversations` row's declared
`implementationSource` and `versionAuthority`
(`governance/store-recoverability.v1.json:274-292`). The row's prose `format`
("schema-v1 encrypted meta.json plus legacy-compatible append-only
messages.jsonl") stays accurate at v1.

Still **run it in the slice** (`node scripts/ci/check-store-recoverability.mjs`)
— the subject file is the row's version authority, and a green run is cheaper
than the argument above.

The generated route snapshot
(`modules/ui-web/src/api/generated/route-manifest.snapshot.json`) needs no update:
it is a live capture that already lags — it lists only four conversation routes
and knows nothing of the shipped 610-era context-floor / compact / exclude
routes — and the FE calls this family by template string
(`conversationListStore.ts:371-401`), not by `API_ROUTES` key.

## 2. Frontend write-through

### 2a. `setConversationTitle` becomes optimistic-then-authoritative

Today it is synchronous: localStorage + in-memory list + emit
(`conversationListStore.ts:184-193`). It becomes:

```ts
export function setConversationTitle(id: string, title: string): Promise<boolean>
```

1. **Optimistic in-memory update + emit, synchronously** (the current body, minus
   `saveTitle`). The sidebar renames instantly; a rename that flickered would be
   worse than one that can fail.
2. **`POST .../title`** through `authorizedFetch`, in the house
   `try { … return res.ok } catch { return false }` shape used by every other
   conversation mutation (`:371-401`, `:434-451`, `:457-…`).
3. **On failure, revert the row to its previous title and emit**, then return
   `false`. Callers already have the before-value in hand
   (`SearchV3View.ts:1823`), and the view surfaces the failure with the existing
   vocabulary: a `423` is *locked* (the wording precedent is
   `views/MemorySurface.ts:111-120` and `state/readinessNotice.ts:261,280`), any
   other failure is a plain "couldn't save that name".
4. **On `404`, queue the title in an in-memory pending map and retry once from
   the next successful `loadConversations`.** This is the §1d race, and it is
   eight lines: the conversation exists server-side the moment its first user
   message is appended, and `loadConversations` already runs at every ask's
   `onDone` (`SearchV3View.ts:1391`).

Call sites: `SearchV3View.ts:1444` and `:1830` become `void setConversationTitle(...)`
(or `await`, in the async `maybeGenerateTitle`); `conversationListStore.ts:252`
awaits it so `generateConversationTitle` can report an honest `null` when the
title could not be stored.

### 2b. No write-behind cache. The localStorage mirror is deleted.

The tempting fallback — "keep writing localStorage so a rename works offline" —
is **rejected**, on two independent grounds:

- **It re-creates the 562 leak.** Tempdoc 562 removed a cached plaintext
  `firstMessage` from `localStorage` precisely because it rendered conversation
  content while the store was encrypted and locked
  (`conversationListStore.ts:49-53`, with a purge-on-read that is still in the
  code). Once `title` is a *sealed* meta field, a plaintext localStorage mirror
  of it is the same defect with a different key: the row would show the
  conversation's subject while the store refuses to show anything else.
- **There is no offline case to serve.** Every row in the list came from
  `GET /api/chat/conversations`. If the Head is unreachable there is nothing to
  rename. "Backend unreachable" here is not degraded operation; it is an empty
  window.

So: the backend is the only writer of record, the FE keeps an optimistic
in-memory copy for the duration of the request, and a failed write is *shown*,
not swallowed. This is the window's own honesty law applied to persistence.

### 2c. Read path

`loadConversations` (`:121-152`) stops merging `loadTitles()` at `:128,132` and
takes `title` from the response row (`s.title as string | null`), which is
already the `Conversation.title: string | null` contract at `:13`. The
downstream consumers need **no change**, because they all read
`Conversation.title` with a fallback chain that already tolerates null:

| consumer | site |
|---|---|
| shipped-window drawer | `components/chat/ConversationHistory.ts:202,218,236,244` |
| Search v3 sidebar | `views/search-v3/sv3-sessions.ts:603-611` (`titleFor`) |
| Search v2 view | `views/search-v2/SearchV2View.ts:122,411` (list only) |
| Unified chat view | `views/UnifiedChatView.ts:270,931,2003,2048` (list only) |

Stale localStorage entries are handled by migration (§3) and then gone.

## 3. Migration: adopt on list, once, and only on a confirmed write

**Recommendation: fallback-read + one-shot adopt inside `loadConversations`.**

For each listed row where the server reports no title and the legacy map has one:
render the legacy title immediately (no regression in what the reader sees), fire
`POST .../title` once, and **delete that entry from the local map only on a 2xx**;
when the map empties, remove `jf-conversation-titles` entirely.

Why this and not the two alternatives:

- **vs. bulk adopt at boot** — identical work, worse shape: it would need its own
  pass over ids the list has not returned (the list is `limit=20`,
  `conversationListStore.ts:125`), i.e. a second enumeration authority for
  conversations. Adopting the rows actually listed converges to the same result
  with no new traversal.
- **vs. let them decay (read-fallback forever)** — cheapest to write, but it
  keeps plaintext titles in `localStorage` indefinitely (the 562 objection in
  §2b) and never makes an existing name durable, which is the entire point of
  the workstream. It also leaves a permanent second read authority in the store.

The locked case needs no detection: while the key is locked the adopt POST
answers 423, the entry is *not* deleted, and the next unlocked list adopts it.
Suppress further adopt attempts for the rest of the session after the first 423
so a locked profile does not re-POST up to 20 times per list refresh.

Migration is complete when the key is absent; the reader is never asked
anything.

## 4. Auto-titling: keep the FE flow, add provenance

**Recommendation: minimal change — the `_title_*` throwaway flow stays exactly as
it is** (`conversationListStore.ts:206-258`, prefix contract
`ConversationStore.java:37`, cleanup bypass `FileConversationStore.java:633`),
and its final step (`:252`) writes through the new endpoint instead of
localStorage. Nothing else about it changes.

Backend-side auto-titling was considered and is **not** recommended now: it would
put an LLM round-trip inside the persistence path, need its own
"already-titled" flag and its own trigger policy, and duplicate a decision the
window already makes well (`sv3ShouldGenerateTitle`,
`sv3-sessions.ts:752-756`: never over a rename, only after a *completed* ask).
The only real argument for it — that a non-browser client (MCP, a future agent
surface) would get titles too — is speculative today. Revisit if a second
producer appears.

**But the seam must carry provenance, because the FE's precedence rule is
currently broken across a reload.** Verified defect chain:

1. `maybeGenerateTitle` runs at every ask's `onDone`
   (`SearchV3View.ts:1393`), guarded by an in-process `titledSessionIds` set
   (`:563,1435`) and by `session.renamed` (`sv3-sessions.ts:753`).
2. `renamed` is set only by a live rename (`renameSession`, `:734`) and is
   **not** on the wire, so a conversation merged back from the store after a
   reload starts `renamed: false` — the code says so explicitly
   (`sv3-sessions.ts:582-585`: "whether the store's title came from a rename or
   from auto-titling is not on the wire").
3. A reloaded conversation's turns project to `status: 'complete'`
   (`sv3-record.ts:157`), so `sv3ShouldGenerateTitle` passes.
4. The next ask in that conversation therefore auto-titles it, and
   `generateConversationTitle` writes the model's name over the reader's
   (`conversationListStore.ts:252`); the `after.renamed` guard at
   `SearchV3View.ts:1444` cannot restore it, because `renamed` is false.

The fix rides this slice for one field's cost: persist
`titleSource: "user" | "auto"` alongside `title` (structural, **not** sealed — it
is a provenance bit, not content), set it from the endpoint body
(`{"title": "...", "source": "user"|"auto"}`, defaulting to `"user"`), project
it on the list row, and have `mergeStoreConversations` seed
`renamed: row.titleSource === 'user'` instead of the hard-coded `false` at
`sv3-sessions.ts:585`. This is the honest version of the comment already in the
code, and it is what makes "a reader's name is never overwritten" true rather
than true-until-reload.

## 5. FOR THE OWNER: pins, shelves, unread — a product decision

*Not decided here. This section states the cost and the loss, and recommends;
the call is the owner's.*

**What they are.** `pinned` (a boolean per row) and the unread bit
(`completedAt` / `lastVisitedAt`, a pair of timestamps) are reader preferences
about a row *in the Search v3 window*; the shelf grouping is a pure projection of
those two plus the live run, not a third fact
(`views/search-v3/sv3-sessions.ts:23-28`). They are deliberately window-local
today.

**What backend persistence would cost.**

- *Schema*: three structural fields on `meta.json` (`pinned`,
  `lastVisitedAtMs`, `completedAtMs`). Cheaper than `title` in one respect —
  they are not content, so no sealing, and they would survive a locked store and
  render while locked.
- *Endpoints*: two more in the same family (`POST .../pin`, `POST .../visited`),
  or one generic `POST .../preferences` taking a small object. Plus projection
  onto `SessionSummary` and the list row.
- *Conceptual*: it puts a **per-window presentation preference into the
  conversation record**, which every other surface then inherits. The shipped
  drawer has no pin affordance; giving it a `pinned` field it cannot set is a
  field with one writer and two readers. The alternative home is a
  per-conversation UI-preferences blob (the existing HEAD-owned
  `settings/ui-settings.json` store,
  `governance/store-recoverability.v1.json:19-37`) — conceptually correct, but it
  has no REST surface today, so it is *more* work, not less, and it is the same
  work the sidebar's width/collapsed pair will eventually need
  (`sv3-sessions.ts:26-27`).

**What browser loss actually means today.** Local-first, single-user: the only
loss modes are clearing site data, switching browser profile, or moving to
another machine. In all three the reader also loses the sidebar width, the
collapsed state, the active-run pointer, and every other per-tab preference —
the pins would be a small part of a larger reset, and no *content* is lost. A
title is different in kind: it is the reader's own words about a conversation
they will look for by name.

**Recommendation (owner to accept or reject):** ship the title seam alone; leave
pins/unread window-local until either (a) a second surface grows a pin
affordance, or (b) the sidebar-preferences store of `sv3-sessions.ts:26-27`
gets built — at which point pins go *there*, not into `meta.json`. Rationale:
title is a content fact with an authority gap; pins are a presentation
preference whose natural home is a preference store that does not exist yet, and
putting them in the conversation record now would be the cheap-but-wrong
placement that the 822 containment law exists to prevent.

## 6. Shipping shape — one slice

Backend and FE ship together: a `title` field no client writes is dead schema,
and an FE write-through with no endpoint is a 404 loop.

**Backend** — `ConversationStore` (`setTitle` default no-op + `SessionSummary.title`
+ a fourth back-compat constructor), `FileConversationStore` (`setTitle`,
`META_CONTENT_FIELDS`, both projections), `ChatController`
(`handleSetTitle`/`handleClearTitle` + list projection), `AiRoutes` (two routes).

**Frontend** — `conversationListStore` (async `setConversationTitle`, read from
the row, migration adopt, delete `TITLES_KEY`/`loadTitles`/`saveTitle`),
`sv3-sessions.ts` (`Sv3StoreConversation.titleSource`, `mergeStoreConversations`
seeding `renamed`), `SearchV3View` (failure wording on the two write sites).

**Verification tiers.**

1. *Unit, Java* — `FileConversationStoreTest`: title round-trips; title survives
   an `appendMessage` (the `updateMeta` re-seal path, §1a); a v1 meta with no
   `title` reads as null; a sealed title is absent from the list while locked and
   present after unlock; `setTitle` on an unknown session does not create a
   directory.
2. *Unit, Java* — a `ChatController`-level test for 400-on-blank, 404-on-unknown,
   and the list row carrying `title` + `titleSource`.
3. *Unit, TS* — `conversationListStore.test.ts`: POST body shape, optimistic
   update, revert-on-failure, adopt-and-erase migration (including *no* erase on
   423). `SearchV3View.honesty.test.ts:988-994` currently asserts the localStorage
   map — it becomes an assertion on the rename POST, which is a strictly better
   test of the same law. A new test must cover the §4 defect: a merged store row
   with `titleSource: "user"` is never auto-titled.
4. *Live round (required, one)* — rename a conversation in the v3 window, restart
   the Head, confirm the sidebar and the shipped drawer both show the new name and
   that `localStorage['jf-conversation-titles']` is gone. Then lock the store and
   confirm a rename answers 423 and the row reverts with the locked wording.
   Load the model (`ai_activate`) for the auto-title leg — the `_title_*`
   throwaway dispatch is an LLM round-trip and `AI_OFFLINE` does not exercise it
   (`use-every-verification-tier`).
5. *Gates* — `node scripts/ci/check-store-recoverability.mjs`, the ui-web gate set
   (consult-register `ui-web-gates`), `./gradlew.bat build -x test` plus
   `:modules:app-services:test` and `:modules:ui:test`.

## 7. Risks and non-goals

- **Cross-window consistency.** Every title consumer is enumerated in §2c; all
  four read `Conversation.title` from the one store, so making the store's value
  authoritative fixes them all at once. The one asymmetry to keep in mind: only
  the v3 window can *write* a title (a grep for `rename` in
  `components/chat/ConversationHistory.ts` and `views/UnifiedChatView.ts` finds
  no rename affordance — the shipped window only auto-titles,
  `UnifiedChatView.ts:2063`). So the shipped drawer becomes a pure reader of a
  name set elsewhere, which is fine, and its "Untitled" fallback
  (`ConversationHistory.ts:236`) stays the right answer.
- **Old meta.json without the field.** Absence means "no title"; no upcaster, no
  version bump (§1b). The v0-meta path (`readableLegacyVersions: [0]`) is
  unaffected — it reads the same map with one fewer key.
- **Sealed-title lock behaviour is a visible change.** Today a locked store still
  shows titles (they are in localStorage); after this change a locked store shows
  `firstUserMessage`-less, title-less rows, i.e. `titleFor`'s
  `SV3_UNTITLED_CONVERSATION` placeholder (`sv3-sessions.ts:603-611`). That is
  the *correct* behaviour (it is what 629 decided for every other content field)
  but it will read as a regression to anyone who has not been told, so it belongs
  in the slice's own notes.
- **Not in scope**: multi-device sync, title search, per-title history/undo,
  backend auto-titling, and the pins/unread question of §5.

## Appendix B.1 — corrections to the 833 §W7 brief, verified verbatim

| brief claim | verdict |
|---|---|
| "one `PATCH /api/chat/conversations/{id}`" | **corrected** — the Head has no `PATCH` route at all; house style is POST-to-set / DELETE-to-clear (`AiRoutes.java:74-99`). |
| "`FileConversationStore.java:435` already has an atomic meta field-patch pattern" | **confirmed** (`toggleStringInMeta`, `:436-460`) — but `setContextFloor` (`:362-386`) is the closer analogue for a scalar field, and is what `setTitle` should mirror. |
| "`SessionSummary` (~:177-191) has no title field" | **confirmed** exactly (`ConversationStore.java:177-191`), plus three back-compat constructors at `:193-232` a fourth must join. |
| "list endpoint projects that (`ChatController.java:192-206`)" | **confirmed** verbatim. |
| "no rename endpoint" | **confirmed** against `AiRoutes.java:74-99` (the generated `apiRoutes.ts` is a stale capture and is not evidence either way — it also lacks the shipped 610 routes). |
| "titles are browser-local (`:39,97-101`, `setConversationTitle:184`, `_title_*` at `:221`)" | **confirmed** verbatim. |
| "v3 renames via `sv3-sessions.ts` `renameSession:726-737`" | **confirmed**; the write-through is `SearchV3View.ts:1830`. |
| "pins/shelves/unread deliberately window-local (`sv3-sessions.ts:23-28`)" | **confirmed** verbatim, including the pointer to a future preference store at `:26-27`. |
| *(not in the brief)* | **new**: a reader's title is silently overwritten by auto-titling after a reload — §4, four verified steps. |
| *(not in the brief)* | **new**: `title` must be sealed, which makes the localStorage mirror a 562-class leak and settles the fallback question — §2b. |
| *(not in the brief)* | **new**: `KeyLockedException → 423` is already global (`LocalApiServer.java:463-474`); locked-rename needs no new code. |

## 8. Dispositions and implementation prep (2026-08-14)

The design is **approved**. Coordinator dispositions on the ranked questions:

| # | question | disposition |
|---|---|---|
| 1 | pins / shelves / unread | **owner question, not implemented** — §5 ships as written |
| 2 | `titleSource` | **IN-SLICE**, with a regression test reproducing the clobber (reload → the reader's title survives the next ask) |
| 3 | 404 on unknown session | **stands** — no ghost rows |
| 4 | `locked` flag on the list endpoint | **out of slice** (already logged to the inbox) |
| 5 | 200-char server cap | **stands** |

### 8a. Branch and worktree, already prepared

A clean worktree is checked out at `.claude/worktrees/conversation-title-authority`
on branch `conversation-title-authority`, based on `origin/main` at `2eb05825`
(`git worktree list` confirms; the tree is clean). The implementer works there —
**not** in `.claude/worktrees/822-t3code-window`, which is on a branch 162 commits
ahead / 14 behind `origin/main` and carries a live session's uncommitted work.

### 8b. `origin/main` line map for the frontend seam

The four FE files are **not** identical between `origin/main` and the 822
worktree — `origin/main` is the newer side (a later squash landed ~139 more lines
across three of them). All backend files are byte-identical. Cite these, not the
§1-§7 numbers, when implementing:

| file (`modules/ui-web/src/shell-v0/…`) | seam | `origin/main` line |
|---|---|---|
| `state/conversationListStore.ts` | *identical to the design's citations* | — |
| `views/search-v3/sv3-sessions.ts` | `Sv3StoreConversation` / `title` | 535 / 537 |
| | `mergeStoreConversations` | 561 |
| | merged row `renamed: false` (the fix site) | 585 |
| | `titleFor` | 603 |
| | `renameSession` | 781 |
| | `sv3ShouldGenerateTitle` | 807 |
| `views/search-v3/SearchV3View.ts` | `titledSessionIds` | 570 |
| | `onDone` → `loadConversations` / `maybeGenerateTitle` | 1414 / 1416 |
| | `maybeGenerateTitle` (write site) | 1457-1467 |
| | `onSessionRename` (write site) | 1837-1853 |
| `views/search-v3/SearchV3View.honesty.test.ts` | the localStorage assertion to convert | 994 |

### 8c. Two implementation decisions taken during prep

- **No fourth `SessionSummary` convenience constructor.** §1c proposed one for
  back-compat, but `new SessionSummary(` has exactly **two** call sites in the
  repository, both inside `FileConversationStore` (`:274`, `:349`), and both are
  updated by this slice. A fourth constructor would be dead on arrival; instead
  the canonical record gains `title` + `titleSource` and the three existing
  delegating constructors (`ConversationStore.java:193-232`) pass `null, null`.
- **`titleSource` is a `String` with boundary validation**, not an enum:
  `SessionSummary` is all-`String`, the value crosses the wire as a string, and
  the store persists strings. The two legal values are pinned as
  `ConversationStore.TITLE_SOURCE_USER` / `TITLE_SOURCE_AUTO` (the same
  constant-on-the-interface shape as `THROWAWAY_SESSION_PREFIX`,
  `ConversationStore.java:37`), and the controller rejects anything else with
  400 `INVALID_REQUEST`.

### 8d. Live round — the exact script (pending an orchestrator lease)

Not run: the dev stack is the orchestrator's to lease, and this work was
explicitly told not to start it. Run these seven steps in one supervised window.

1. `quick_health`; if free, `justsearch_dev_start` with `hotReload: true` and a
   `leaseDurationSec` covering the whole round (600 is ample).
2. `ai_activate` — the auto-title leg is a real LLM round-trip through the
   `_title_*` throwaway dispatch; `AI_OFFLINE` does not exercise it.
3. In the v3 window: ask one question, let it settle, confirm the sidebar row
   auto-titles. Then rename the row to `Renewal postmortem`.
4. Confirm the write landed server-side, not just on screen:
   `GET /api/chat/conversations?limit=5` → the row carries
   `"title":"Renewal postmortem","titleSource":"user"`.
5. **Restart the Head** (`justsearch_dev_stop` then `start`), reload the window:
   the row still reads `Renewal postmortem`, the shipped drawer shows the same
   name, and `localStorage['jf-conversation-titles']` is **absent**
   (`javascript_tool`: `localStorage.getItem('jf-conversation-titles')` → null).
6. **The clobber regression, live**: ask a *second* question in that same
   renamed conversation after the reload. The title must not change — this is
   the §4 defect, and step 5's reload is what used to reset `renamed`.
7. **Locked leg**: lock the store, attempt a rename → the request answers `423`
   with `errorCode: STORE_LOCKED`, the row reverts to its previous name, and the
   window shows the locked wording (not a generic failure). Unlock, confirm the
   row's title returns.

Stop the stack when done.

## 9. Why implementation did not proceed in this session

The implementing agent is **pinned by the harness to
`.claude/worktrees/822-t3code-window`**: every `Edit`/`Write` outside that path,
every `Bash` command whose working directory resolves outside it, and every git
invocation redirected out of it (`cd … && git`, `git -C …`) is refused by the
session-isolation guard. `EnterWorktree` reports a switch but does not move the
pin — verified by all three tool families still refusing after the switch.

That leaves no safe way to produce the requested `origin/main`-based branch from
this session:

- Checking out `conversation-title-authority` **inside** the pinned worktree
  would switch the branch under a live session that has 14 uncommitted files
  there, and would abort anyway on the files that differ between the branches.
- Editing the pinned worktree's copies and committing them onto `origin/main`
  via plumbing would **revert** the newer `origin/main` versions of
  `SearchV3View.ts`, `sv3-sessions.ts` and `SearchV3View.honesty.test.ts`
  (§8b) — a silent regression of already-merged work.

The unblock is one line of setup, not a redesign: launch the implementer with
its working directory pinned to
`.claude/worktrees/conversation-title-authority` (already created, clean, on the
right base), or spawn it with `isolation: "worktree"` from a checkout on `main`.
Everything else in this document is implementation-ready.

## 10. Implementation log (2026-08-14)

The slice shipped on branch `conversation-title-authority`, based on `origin/main`
at `2b4b3692`. §9's worktree pin was resolved by running the implementer in its
own worktree, exactly as that section proposed; nothing in the design was
re-opened.

### 10a. Where each design point landed

| design point | file:line |
|---|---|
| `TITLE_SOURCE_USER` / `TITLE_SOURCE_AUTO` on the interface (§8c) | `modules/app-agent-api/src/main/java/io/justsearch/agent/api/conversation/ConversationStore.java:62`, `:65` |
| `setTitle(sessionId, title, titleSource)`, no-op default | `ConversationStore.java:207` |
| `SessionSummary.title` + `.titleSource`; the three delegating ctors pass `null, null` (§8c: no fourth ctor) | `ConversationStore.java:228`, `:232`; `:235`, `:247`, `:261` |
| `title` joins `META_CONTENT_FIELDS`; `titleSource` deliberately does not | `modules/app-services/src/main/java/io/justsearch/app/services/conversation/FileConversationStore.java:599` |
| store `setTitle` — read-modify-write, 200-char cap, never materialises | `FileConversationStore.java:404`, cap at `:604` |
| `listSessions` decrypts the title inline, blanks it on lock | `FileConversationStore.java:247` |
| `getSessionMeta` projection (propagates `KeyLockedException`, unchanged) | `FileConversationStore.java:349` |
| `handleSetTitle` (400 blank / 400 bad source / 404 unknown / 423 by the global mapping) | `modules/ui/src/main/java/io/justsearch/ui/api/ChatController.java:319` |
| `handleClearTitle` | `ChatController.java:370` |
| list-row projection of `title` + `titleSource` | `ChatController.java:208` |
| the two routes | `modules/ui/src/main/java/io/justsearch/ui/api/routes/AiRoutes.java:102-103` |
| `Conversation.titleSource` on the wire type | `modules/ui-web/src/shell-v0/state/conversationListStore.ts:12`, `:23` |
| legacy map: read-only + self-erasing; `saveTitle` deleted | `conversationListStore.ts:108`, `:121` |
| `pendingTitles` (the §1d 404 race) and `adoptBlockedIds` (the 423 suppression) | `conversationListStore.ts:140`, `:146` |
| `loadConversations` reads the row's title, falls back to legacy, fires both write-throughs | `conversationListStore.ts:164`; wire narrowing at `:209` |
| `flushPendingTitles` / `adoptLegacyTitles` (§3) | `conversationListStore.ts:220`, `:240` |
| `setConversationTitle` — optimistic, then authoritative | `conversationListStore.ts:357`; `applyTitle` `:293`, `postTitle` `:317`, outcome type `:311` |
| auto-title write-through, awaited, `source: 'auto'` | `conversationListStore.ts:437` |
| `Sv3StoreConversation.titleSource` | `modules/ui-web/src/shell-v0/views/search-v3/sv3-sessions.ts:543` |
| **the clobber fix** — `renamed: c.titleSource === 'user'` | `sv3-sessions.ts:592` |
| `restoreSessionTitle` (the revert a failed write needs) | `sv3-sessions.ts:811` |
| `writeTitleThrough` — revert + worded failure | `modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.ts:1871` |
| `onSessionRename` write site | `SearchV3View.ts:1842` |
| `maybeGenerateTitle` write-back, as `user` | `SearchV3View.ts:1460` |
| the generic-failure wording | `modules/ui-web/src/shell-v0/views/search-v3/fixtures.ts:110` |

### 10b. Three decisions taken during implementation

1. **`setConversationTitle` returns `{ok, status}`, not a bare boolean.** §2a
   sketched `Promise<boolean>`, but §2a step 3 also requires the view to word a
   `423` differently from any other failure, and a boolean cannot carry that.
   Re-deriving the status with a second request would be a second authority, so
   the outcome carries it (`ConversationTitleWrite`).
2. **A refused rename is reverted through a new pure `restoreSessionTitle`, not
   through `renameSession` with the old value.** `renameSession` is the READER's
   decision point: it rejects an empty title by design (so it cannot restore a
   conversation that never had a name) and it raises `renamed`, which would then
   outrank auto-titling on the strength of a write that never landed. The revert
   restores the flag it found.
3. **The failure is surfaced as an ephemeral toast** (`emitEphemeralToast`, the
   ONE client-originated message channel — 559 Authority III), because the v3
   window's only other notice channel (`recordNotice`) is a transcript-scoped
   boolean with fixed copy. The `423` wording comes from `reasonFor(
   'conversations.locked')`, so this surface cannot drift from how the rest of
   the product names a locked store.

`title`/`titleSource` were additionally documented on the list row and as two new
routes in `docs/reference/api-contract-map.md:406`, `:410-411`; `docs/llms.txt` and the
skills index were regenerated.

### 10c. Verification actually run

- `./gradlew.bat spotlessApply` then `./gradlew.bat build -x test -PskipWebBuild=true` — **BUILD SUCCESSFUL**.
- `./gradlew.bat :modules:ui:test :modules:app-services:test :modules:app-agent-api:test` — **BUILD SUCCESSFUL** (the new `ChatControllerTitleTest` reports 9 tests, 0 failures).
- `cd modules/ui-web && npm run typecheck && npm run test:unit:run` — clean typecheck; **5125 tests, 421 files, all passing** (5109 before this slice).
- Gates: the full `ui-web-gates` recipe (21 `scripts/ci` gates + `gen-token-names --check`, `gen-component-vocabulary --check`, `strip-token-fallbacks --check`) and the six kernel gates, plus `execution-surface`, `operation-surface`, `wire`, `check-tempdoc-numbers`, and **`check-store-recoverability` — green**, as §1e predicted.
- Four gates are RED on this branch and on `origin/main` alike, in files this
  branch does not touch: `check-theme-token-closure` + `strip-token-fallbacks`
  (`RecentsMenu.ts`), `check-accent-as-text` + `strip-token-fallbacks`
  (`ActionLedgerView.ts`) — the first three are declared in
  `governance/expected-state.v1.json` — and `check-controls-a11y`
  (`UnifiedChatView.ts:2096`), which is NOT declared there and has been logged to
  the observations inbox.

### 10d. Test precision — both new regressions were made to fail first

Neither new test is a green that would pass anyway:

- Reverting the merge seed to `renamed: false` makes the view-level clobber case
  fail at the provenance assertion; with that assertion removed as well, it fails
  at `expect(titleCalls()).toHaveLength(0)` with `1` — i.e. the model really is
  asked to re-name the conversation without the fix. Both were run and observed.
- The two suite assertions that used to read `localStorage['jf-conversation-titles']`
  were converted rather than deleted: the honesty case now asserts the last
  *title POST* for that conversation is the reader's, plus that the legacy key is
  absent; the record case's fake backend now STORES the posted title and serves it
  back on the next list, so the reload assertion tests durability instead of a
  browser cache.

New coverage: 7 Java store cases (`FileConversationStoreTest`), 9 controller
cases (`ChatControllerTitleTest`), 9 store-level TS cases
(`conversationListStore.test.ts`), 5 pure-module cases (`sv3-sessions.test.ts`),
2 view-level cases (`SearchV3View.honesty.test.ts`, `SearchV3View.record.test.ts`).

### 10e. What is NOT done

- **The §8d live round is PENDING.** It was not run: the dev stack is the
  orchestrator's to lease and this work was told not to start one. The seven-step
  script in §8d stands unmodified and is the remaining acceptance gate — in
  particular step 6 (the clobber regression against a real restart) and step 7
  (the locked leg), which unit tiers can only approximate.
- **§5 (pins / shelves / unread) is untouched**, as dispositioned: it remains an
  owner question with zero implementation.

## Open questions, ranked (all dispositioned — see §8; kept for the record)

1. **§5 pins/unread** — owner call. Blocks nothing; the title slice ships either
   way.
2. **`titleSource` in this slice or the next?** Recommended in-slice (§4): it is
   one unsealed field and it is what makes the auto-title precedence rule true
   across a reload. If the owner wants the seam minimal, the defect must be
   filed rather than shipped-with.
3. **404 vs. materialise on rename of an unknown session** (§1d). Recommended
   404 + FE retry; the alternative matches `setContextFloor`'s existing
   behaviour, at the cost of ghost zero-message rows in the list.
4. **Should the list endpoint report `locked`** the way `GET /api/memory` does?
   Not needed by this design (§3's adopt is self-limiting), but it would let the
   sidebar say *why* a row is nameless instead of showing a placeholder. Separate
   change, separate justification.
5. **200-char server cap vs. the FE's 80-char auto-title guard** — deliberate
   asymmetry (backstop vs. UX rule) or should they be one number?
