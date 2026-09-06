---
title: "Failure UX as one coherent surface: one presentation authority for failure wording, remedy and severity, projected over the six per-question reason vocabularies that already exist — never merged into a super-enum"
type: tempdocs
status: "VERIFIED — accepted §U scope and §W fix complete; publication in progress (2026-09-06)"
created: 2026-09-02
updated: 2026-09-06
lite-class: false
lane: 887 L18 coverage note (failure-UX pass)
model: fable (design) → opus (implementation)
parent: 887-improvement-landscape-register
related:
  - 837-reason-code-completeness-design      # the readiness vocabulary's completeness sweep; the shape this lane generalises
  - 600-degradation-cause-not-observable     # readiness reason codes + the first forward/backward gate
  - 602-residual-walkthrough-findings-fe-reliability-and-consistency  # R6: the search-degradation sibling gate
  - 596-unavailable-affordance-reason-authority  # reason + remedy at AFFORDANCE scope, already reading CAUSE_ROWS
  - 613-notification-model-unification       # the message-class register: class is closed, wording is not
  - 559-presentation-adjacent-authorities    # Authority III (one messaging model)
  - 902-unified-abstention-authority         # the sibling lane; its "one verdict per question" is the principle this generalises
  - 553-canonical-search-execution-record    # projection vs fork
  - 889-filesystem-reality                   # incoming ingestion reason families (soft block on chunk I-5)
  - 896-background-citizenship               # incoming disk families (soft block on chunk I-5)
  - 419-unused-user-agent-capability-discovery  # ledger backend shipped; the FE consumer never was
---

> Design tempdoc. §B briefing, §C corrects the premise, §F is the inventory (evidence),
> §G the incoherences, §D decisions, §A the design, §P reach, §O orphans, §I opus chunks,
> §K owner confirmations, §Z routed one-liners.

> **2026-09-05 takeover:** the original charter below is dated history, not an approved
> implementation plan. §T corrects its evidence and records the current verdict. No
> implementation or new design was undertaken in this takeover.

# 906 — Failure UX as one coherent surface

## §B. Briefing for the agent picking this up

Fresh start. Read this file, then `docs/explanation/27-frontend-presentation-kernel.md` (the
prevention ladder at `:21-39` and the authorities table at `:64-77` — every rule there binds this
lane), then the two shipped exemplars: `modules/ui-web/src/shell-v0/state/readinessNotice.ts` and
`scripts/ci/check-search-degradation-reason-codes.mjs`. Work in a worktree. This lane authors **no
new reason code and no new verdict**: it is a presentation-authority lane. If you find yourself
adding a member to a Java enum, you are in 889-filesystem-reality's or 896-background-citizenship's
lane, not this one. The register row this charter answers is the "Coverage note" at the end of
887-improvement-landscape-register §L row L18.

## §C. Correcting the premise

The coverage note asked: "are `readinessNotice`, degradation codes, ingestion ledger reasons, and
API error classes **one vocabulary** to the user?" The evidence says the question has a sharper
answer than either yes or no.

1. **They are not one vocabulary and must not become one.** They answer different questions, and
   the repo has already ruled on this twice in writing:
   `governance/search-degradation-reason-codes.v1.json:3` — "the search-side sibling of
   readiness-reason-codes.v1.json (**NOT merged** with it — separate vocabularies, per tempdoc 600
   PART IX)"; and `docs/explanation/27-frontend-presentation-kernel.md:76` records the same ruling
   for the AI-engine verdict ("deliberately NOT merged into the 595 `SystemHealthVerdict`").
   Merging them is the fork 902-unified-abstention-authority §C rejects.
2. **What is missing is one tier down: the PRESENTATION contract.** Exactly one family
   (`readinessNotice.ts`) has the full `code → wording → remedy → severity` shape. Every other
   family has some strict subset, and three user-facing failure channels have no vocabulary at all.
   So the honest finding is not "four vocabularies that should be one" but "**six vocabularies with
   four different presentation contracts, plus three unclassified prose channels, plus three
   incompatible severity scales**".
3. **Two of the incoming codes 889 names as new already exist.**
   `IngestionReasonCodes.UNREADABLE` is declared (`IngestionReasonCodes.java:52`) and already
   referenced (`WorkerIngestionAuthority.java:43`); `SKIPPED_POLICY` exists as an
   `IngestionOutcomeClass` member (`IngestionOutcomeClass.java:11`). What 889 adds is emission
   *sites* and the `REPARSE_POINT` sub-reason, not the constants. `ROOT_UNREADABLE` and
   `DATA_DIR_CONTENTION` genuinely do not exist (zero hits repo-wide). `INDEX_DISK_FULL` exists
   (`ApiErrorCode.java:28`, `PERMANENT`); 896's change is its **class**, not its existence.

## §F. Inventory — every user-facing failure/reason vocabulary on this branch

Verified by reading each file on `worktree-887-publish`.

| # | vocabulary | Java authority | wire shape | FE consumer + wording site | fwd/bwd gate | remedy |
|---|---|---|---|---|---|---|
| V1 | **readiness reason codes** | `LifecycleReasonCode.java:17-172` (58 members, each `NAME("dotted.code")`) | `readiness.*.reasonCode` / `reasonCodes[]` on `/api/status` (`status-response.ts:207,215`) | `readinessNotice.ts:66-427` `CAUSE_ROWS`; second consumer `state/availability.ts` via `reasonFor` (`:820-823`) | **yes** — `check-readiness-reason-codes.mjs`, forward + backward + a third PRODUCER direction (`readiness-reason-codes.v1.json:3-4`) | **yes** — typed `NoticeRemedy` (`readinessNotice.ts:20-25`), operation id or navigation, `OPEN_HEALTH` fallback |
| V2 | **query degradation** | `SearchReasonCode` (worker) | `SearchTrace.Degradation` (`SearchTrace.java:61-66`): three `*Reason` strings | `searchTraceExplain.ts:136-170` `DEGRADATION_REASON_WORDING` | **yes** — `check-search-degradation-reason-codes.mjs` | **no** |
| V3 | **cross-encoder skip** | `CrossEncoderSkipReason.java` | `cross-encoder` trace stage reason | `searchTraceExplain.ts:172-181` `CROSS_ENCODER_SKIP_WORDING` | **yes** — same gate, second `vocabularies[]` entry | **no** (the wording embeds the consequence, not the fix) |
| V4 | **API error codes** | `ApiErrorCode.java:23-469` (~140 members) + `ErrorClass.java:11-24` (TRANSIENT/PERMANENT/POLICY/VALIDATION) | `{error, errorCode, errorClass, retryable, i18nKey}` (`ApiErrorHandler.java:444-445`) | `errors.en.properties` → `SSOT/messages/errors.en.json` → `GET /api/messages/errors/en` → `i18n/errorCatalog.ts:213-250` | **yes**, Java-side — `ErrorMessagePropertiesContractTest.java:43,59,75` (forward for both enums, backward no-orphans) | **no structured remedy** — some messages embed one in prose (`errors.en.properties:36` vs `errors.INDEX_DISK_FULL` "Free up space and try again") |
| V5 | **agent error codes** | `AgentErrorCode.java:5-19` (14) + `AgentErrorClass.java:11-18` (6, a *different* class set) | agent SSE error payload | same catalog, same flat `errors.<NAME>` keyspace | **yes**, same test | **no** |
| V6 | **ingestion ledger reasons** | `IngestionReasonCodes.java:6-66` (26 string constants) + `IngestionOutcomeClass.java` | `ingestion_ledger` rows via `GET /api/diagnostics/ingestion/{recent,summary}` (`03-knowledge-server.md:157`) | **none** — zero hits for `CLOUD_PLACEHOLDER`/`PARSER_TIMEOUT`/`EXTRACTION_DROPOUT*`/`REASON_CODE_LABELS` in `modules/ui-web/src` | **no gate** | **no** — a proposed table exists only in `docs/how-to/library-indexing-activity-panel.md:219` (frontmatter `status: planning`, `:5`) |
| V7 | **agent completion** | `TerminalDisposition.java:13-29` (5) | `/api/agent/sessions` `disposition` string (`agent.ts:53`) | `healthEventActivityRow.ts:92-94` renders `` `disposition: ${disposition}` `` — the **raw code** | **no gate** | **no** |
| V8 | **VDU extraction trust** | `VduAbstentionGate.java` `Band` PASS/AMBIGUOUS/REJECT (`:27-33`), calibrated floors `:94,:104,:113,:124` | not on any user wire | **none** — zero `AMBIGUOUS`/`abstain` hits in `modules/ui-web/src` | n/a | n/a |
| V9 | **search-result outcome** | none (FE-derived) | n/a | `sv3-results.ts:26` `Sv3ResultsStatus = idle\|loading\|ready\|empty\|unreachable`; copy in `fixtures.ts:62-76`; standalone renderer `SearchResultsRenderer.ts:72` "No results." | **no gate** | no |
| V10 | **local transient messages** | n/a | n/a | `ephemeralToast.ts:28-34`: closed `classId` (`LOCAL_MESSAGE_CLASSES`, `messageClasses.ts:50`), **free-form `message: string`** — "Content is pre-humanized by the caller" (`:31`) | class only (`check-message-classes.mjs`, `message-classes.v1.json:2`); **wording ungated** | n/a |
| V11 | **MCP tool failures** | none — `errorContent(String)` (`McpToolSurface.java:1861-1863`), text built by `toolFailureMessage` (`:1885-1894`) | `{content:[text], isError:true}` | the calling agent | **no gate** | prose only |

## §G. The incoherences (each with `file:line`)

- **G1 — Same condition, two wordings, on the same screen.** A failed `/api/knowledge/search`
  reaches the user as the literal `HTTP 502`: `searchState.ts:645-647` stores
  `` error: `HTTP ${res.status}` `` and `:719` stores `err.message`; `sv3-results.ts:47,82-84`
  carries it as `failure`; `Sv3Main.ts:1817,3123` renders it. The response body carrying
  `errorCode` + `i18nKey` + `retryable` (`ApiErrorHandler.java:444-445`) is never read, so the FE's
  own catalog (`errorCatalog.ts:213`) and V4's whole gate chain are bypassed on the single most-used
  failure path in the product.
- **G2 — Two conditions, one wording, by construction.** `errors.en.properties:18` states it
  outright: "INTERNAL_ERROR exists in both enums; one entry serves both." `ApiErrorCode.INTERNAL_ERROR`
  (`ApiErrorCode.java:57`) and `AgentErrorCode.INTERNAL_ERROR` (`AgentErrorCode.java:10`) share
  `errors.INTERNAL_ERROR` (`:36`). The keyspace is flat, so the next collision (e.g. an agent-side
  `TIMEOUT`) silently inherits the other family's sentence and no gate notices — the orphan check
  (`ErrorMessagePropertiesContractTest.java:75`) unions both enums and cannot see it.
- **G3 — A retryability claim asserted without consulting the classification that exists.**
  `toolFailureMessage` (`McpToolSurface.java:1885-1894`) appends "This may be transient" to **every**
  MCP tool failure, including ones whose `ApiErrorCode` is `PERMANENT`. `ErrorClass` and
  `isRetryable()` (`ApiErrorCode.java:483-485`) are right there and unused on this path.
- **G4 — A vocabulary with 26 members and no user surface at all.** V6: the ledger is written,
  exported, privacy-hardened (`03-knowledge-server.md:157-168`) and never worded. Its wording table
  has been drafted for months in a `status: planning` how-to
  (`library-indexing-activity-panel.md:5,219`). Meanwhile `03-knowledge-server.md:283` still says
  three extraction gaps "remain deliberately deprioritized without an active tracked item" — one of
  which is literally "no user-visible signal".
- **G5 — Raw codes reaching users, in the exact shape the V2 gate exists to prevent.**
  `healthEventActivityRow.ts:94` prints `disposition: MAX_ITERATIONS`. `searchTraceExplain.ts:186`
  keeps a `(${reason})` fallback (correct as a backstop, since the gate makes it unreachable for V2/V3
  — but nothing makes it unreachable for V7).
- **G6 — Three severity scales.** `readinessNotice.ts:61` `Severity = ok|info|busy|warn|error`;
  `ephemeralToast.ts:28` `MessageSeverity = info|success|warning|error`; `substrates/effect.ts:29`
  `'info'|'warning'|'error'|'success'`. `warn` and `warning` are the same tier under two names, and
  `success` has no counterpart in the readiness scale. Any surface bridging a reason code to a toast
  must hand-translate.
- **G7 — Raw exception text in a toast.** `Shell.ts:1175` and `:1199` emit
  `` `invoke-operation '${id}' failed: ${err.message}` `` / `` `undo '${id}' failed: …` `` into the
  one toast channel. Sibling ad-hoc literals: `UnifiedChatView.ts:1494,1647,1658,5794`
  ("Failed to create branch" / "Failed to reset context" / "Failed to restore context") and
  `:6147-6150` (`'An error occurred'` as the SSE `onError` fallback);
  `EffectAuditLog.ts:305`; `StatusDeck.ts:380`; `pendingAuthorizationBridge.ts:218`;
  `ApiExplorerView.ts:75,84,90`; `GovernanceView.ts:101,116,126`.
- **G8 — Remedy exists in exactly one family.** V1 has a typed `NoticeRemedy`
  (`readinessNotice.ts:20-25`) that the presentation kernel already routes to affordance scope
  (`27-frontend-presentation-kernel.md:74`). V4 buries remedies in prose; V2/V3/V6/V7 have none.
  A user told "The search index is locked by another process" (`errors.en.properties`, `INDEX_LOCKED`,
  `TRANSIENT`) gets no button, while a user told "The semantic embedding index is not ready" gets one.
- **G9 — One word, three concepts.** "disposition" is `TerminalDisposition` (V7),
  `ToolDisposition` (`authoritySpace.ts:46-57`, a trust/authority concept), and the untyped
  attribute string at `healthEventActivityRow.ts:92`. The presentation kernel's Display authority
  (`display/present.ts`) governs names but no register notices this collision.
- **G10 — Two error CLASS vocabularies over one catalog.** `ErrorClass` (4 members) and
  `AgentErrorClass` (6) both feed `errors.en.properties`; no mapping between them exists, and
  `AgentErrorCode` carries no class annotation at all (`AgentErrorCode.java:5-19` is a bare enum),
  so the agent path cannot answer "is this retryable" the way `ApiErrorCode.isRetryable()` can.

## §D. Decisions

| # | decision | grounds |
|---|---|---|
| D1 | **One presentation authority, `failurePresentation.ts`, that is a PROJECTION over the existing vocabularies. No merged super-enum, no renumbered codes, no member moved between families.** | The two registers already ruled "NOT merged" (`search-degradation-reason-codes.v1.json:3`); a super-enum is the fork 902-unified-abstention-authority §C rejects and 603-rag-trust-calibration §13 killed. Each family keeps its own Java authority and its own question. |
| D2 | **One shared row shape: `{ code, family, label, remedy?, severity, surfaces[] }`** — `readinessNotice.ts`'s `CAUSE_ROWS` shape (`:66-75`) promoted to a shared type, with `family` added so the flat keyspace stops being the disambiguator. Each vocabulary declares its own table of that shape; the authority module owns the type, the lookup, and the unknown-code fallback. | The shape is shipped, gated, and already has two consumers at two scopes (banner + affordance, `27-frontend-presentation-kernel.md:74`). Generalising a proven shape beats inventing one. |
| D3 | **`family` becomes part of the catalog key: `errors.<family>.<CODE>`,** with the current flat keys retained as aliases for one release. | G2. A documented collision that no gate can see is a defect, not a convention. |
| D4 | **One severity scale.** `readinessNotice.ts:61` `Severity` is the survivor; `MessageSeverity` and the inline union in `effect.ts:29` become aliases of it (`warning` → `warn`; `success` stays a toast-only member declared in the shared type). | G6. Three scales for one axis is the 559-presentation-adjacent-authorities Authority-III class, one tier down. |
| D5 | **One coherence gate across all families**, `check-failure-vocabularies.mjs`, register-driven from `governance/failure-vocabularies.v1.json`, reusing the exported pure `checkCorrespondence` from `check-search-degradation-reason-codes.mjs:85-118` verbatim. Producer extractors: bare enum (`:47`), enum-with-code-string (the V1 shape), and **string-constant class** (new — V6's `public static final String X = "X"` shape). Consumer extractors: TS table (`:64`) and Java properties (V4/V5). | The pattern is the repo's shipped ladder rung for this class; the only genuinely new code is one producer extractor. |
| D6 | **The two existing gates are not replaced; they are re-pointed at the shared library.** `check-readiness-reason-codes` keeps its third PRODUCER direction (`readiness-reason-codes.v1.json:4`), which the others do not need. | `retire-with-a-sweep` cuts both ways: do not delete a gate that enforces more than its replacement. |
| D7 | **Every user-facing failure string is either a projection of a registered row or an explicitly declared free-text channel.** Free text stays legal only for: the diagnostic `failure` detail line (`sv3-results.ts:47`, which is documented as detail-not-prose) and dev-only surfaces (`ApiExplorerView`, `GovernanceView`, `EffectAuditLog`) — declared in the register's `freeTextExempt` with a rationale each. | G7. The evasion this pre-empts is "it's just one string in a catch block"; the register makes each one a reviewed decision. |
| D8 | **The search path reads the error envelope.** `searchState.ts` stores `{code, i18nKey, message, retryable}` instead of `` `HTTP ${status}` ``; `Sv3ResultsStatus` is unchanged (V9 stays a transport/cardinality axis, per 902's D5 boundary) but `failure` becomes the projected label. | G1 is the highest-traffic instance and needs no new vocabulary — only for one consumer to read what the producer already sends. |
| D9 | **The ingestion ledger gets its FE surface as part of this lane's V6 migration**, using the labels already drafted at `library-indexing-activity-panel.md:219` verbatim (`catalog-verbatim`), and that doc is promoted out of `status: planning`. | G4. 419-unused-user-agent-capability-discovery shipped the backend; the wording exists; only the projection is missing. Overlaps 889-filesystem-reality item 4 — coordinated in §I, not duplicated. |
| D10 | **MCP failures carry `code` + `errorClass` in `structuredContent`** and drop the unconditional "may be transient" sentence in favour of the classification. `errorContent` gains a code parameter. | G3. The 725 W3 descriptive-grammar rule (`McpToolSurface.java:1866-1868`) is preserved: stating a class is descriptive, promising a retry is not. |
| D11 | **What stays separate, explicitly:** the five verdict axes of 902-unified-abstention-authority §C (faithfulness, capability, completion, extraction trust, retrieval adequacy) keep their own authorities; V8 (VDU bands) stays off the user wire; `ToolDisposition` stays a trust concept and is renamed only if 878-agent-run-honesty-and-paging's lane touches it. This lane changes *how a reason is worded and remedied*, never *what a reason means*. | The whole point of D1. Naming the non-goal here is what stops the next agent from "finishing the job" by merging. |

## §A. Design

1. **Register** `governance/failure-vocabularies.v1.json`: one `vocabularies[]` array, each entry
   `{id, question, producer{file,symbol,style}, consumer{file,table|properties}, noWordingExempt[],
   feDerived[], hasRemedy, severityDefault}` — plus `freeTextExempt[]` (D7). Entries: `readiness`,
   `query-degradation`, `cross-encoder-skip`, `api-error`, `agent-error`, `ingestion`,
   `agent-completion`. The two existing registers stay as the authority for their own extra
   directions and are referenced, not copied (`553-canonical-search-execution-record`: projection,
   not fork).
2. **FE authority** `modules/ui-web/src/shell-v0/state/failurePresentation.ts`: exports the shared
   `FailureRow` / `Severity` / `NoticeRemedy` types, `failureRow(family, code)`, `failureLabel`,
   `failureRemedy`, and the unknown-code fallback (generic wording + `OPEN_HEALTH`, mirroring
   `readinessNotice.ts:795-803` — never silence). `readinessNotice.ts` imports the types and keeps
   `CAUSE_ROWS` as the `readiness` family's table; `searchTraceExplain.ts`'s two tables gain
   `severity` and become `FailureRow` tables; a new `ingestionReasons.ts` and
   `agentCompletion.ts` carry V6 and V7. Registered in `governance/run-renderers.v1.json` as a new
   `failurePresentation` authority with its `consumerSites` allowlist, in the `groundingSemantics`
   shape (`:58-73`).
3. **Wire**: additive only. `family` is derived FE-side from which endpoint answered (no new field);
   `errors.<family>.<CODE>` keys are added to `errors.en.properties` with the flat keys kept as
   aliases, so `ErrorCatalogJsonArtifactTest` regenerates `SSOT/messages/errors.en.json` in the
   normal `-PupdateSchemas` flow. MCP gains `code`/`errorClass` in `structuredContent` only.
4. **Gate** `scripts/ci/check-failure-vocabularies.mjs`: imports `checkCorrespondence` from the
   degradation gate (already exported and pure, `:85`), adds the string-constant producer extractor
   and the properties consumer extractor, and runs forward + backward per family. A CLAUDE.md
   pre-merge-table row is added for it (`check-premerge-table` must pass).
5. **Consumers migrated** (the per-family chunks): `searchState.ts` (D8), `Sv3Main.ts`'s failure
   detail, `SearchResultsRenderer.ts:72`, `healthEventActivityRow.ts:92-94`, the ingestion surface
   (D9), `Shell.ts:1175,1199`, the five `UnifiedChatView` literals, `StatusDeck.ts:380`,
   `pendingAuthorizationBridge.ts:218`, `McpToolSurface` (D10).
6. **Docs**: `27-frontend-presentation-kernel.md` authorities table gains a failure-presentation row;
   `03-knowledge-server.md:283` is rewritten (the "no user-visible signal" gap is closed by D9, the
   other two route to 889-filesystem-reality); `library-indexing-activity-panel.md` leaves
   `status: planning`.

## §P. Reach

**Principle — one presentation contract for every failure a user can meet: a code is worded,
severity-tiered and (where a fix exists) remedied by exactly one registered table, and no surface
authors a second phrasing of a condition another surface already words.**

This is 902-unified-abstention-authority's "one verdict per question, each a projection of the
canonical record" applied one tier down: that lane governs *which verdict answers which question*;
this one governs *how any verdict's answer is spoken*. The two compose — 902's `retrieval-adequacy`
vocabulary, when it lands, registers as an eighth family and inherits the gate for free.

**Already instantiated by:** V1 (fully), V2/V3 (wording + gate, no remedy), V4/V5 (wording + gate,
no remedy, colliding keyspace).

**Where else it applies:** 901-sensitive-content-policy-and-injection-adversary's policy-refusal
wording; 891's per-file forget failures; any future plugin-emitted error class (the flat keyspace
makes this actively unsafe today, G2).

**Existing violations:** the ten in §G.

**Evidence it earns its keep:** (1) the inventory-as-gate chunk (I-1) fails on `main` today and
lists every unworded code — the count going to zero is the measurement; (2) a grep for raw
`err.message`/`HTTP ${` in a user-facing assignment under `modules/ui-web/src/shell-v0` returns only
the declared `freeTextExempt` sites; (3) the V4/V5 collision is unrepresentable after D3 (add a
colliding pair in a test fixture and the gate reds).

**Retirement condition:** if after two releases the register's only content is the four families
that already had gates, and no new family was added by 889/896/902, this lane's register is
over-machinery — collapse it back into the two existing gates and keep only the shared severity type
(D4) and the shared row shape (D2).

## §O. Orphaned by this design

- `searchState.ts:646` `` `HTTP ${res.status}` `` and `:719` `err.message` as stored user-facing text.
- `Shell.ts:1175,1199` interpolated exception text in the toast channel.
- `UnifiedChatView.ts:1494,1647,1658,5794,6150` five ad-hoc literals.
- `SearchResultsRenderer.ts:72` "No results." (a V9 projection replaces it; note
  902-unified-abstention-authority §O claims the same line for the adequacy NONE projection — whichever
  lane lands first owns it, the other reads the shared row).
- `healthEventActivityRow.ts:94` `` `disposition: ${code}` ``.
- `McpToolSurface.java:1892-1893` the unconditional "This may be transient" sentence.
- `EffectAuditLog.ts:305`, `StatusDeck.ts:380`, `pendingAuthorizationBridge.ts:218` literals.
- **Not touched:** any Java enum member, any wire code string, `VduAbstentionGate`,
  `TerminalDisposition`'s meaning, `evidenceProjection.ts`, `Sv3ResultsStatus`'s five states.

## §I. Implementation chunks (opus takeover)

| chunk | scope | acceptance |
|---|---|---|
| **I-1 Inventory as a gate** | `governance/failure-vocabularies.v1.json` with all seven families + `freeTextExempt`; `scripts/ci/check-failure-vocabularies.mjs` reusing `checkCorrespondence`; the two new extractors; the CLAUDE.md pre-merge-table row | `node scripts/ci/check-failure-vocabularies.mjs` runs and reports the §G unworded set (RED is the expected first state — record the counts in §Status); `node scripts/ci/check-premerge-table.mjs` green; `node scripts/ci/check-readiness-reason-codes.mjs` and `node scripts/ci/check-search-degradation-reason-codes.mjs` still green |
| **I-2 The authority module + severity collapse** | `failurePresentation.ts` (types, lookup, unknown fallback); `readinessNotice.ts` re-exports its `Severity` from it; `MessageSeverity` and `effect.ts:29`'s union become aliases (D4); `run-renderers.v1.json` `failurePresentation` entry | `node scripts/ci/run-ui-web-gates.mjs`; `cd modules/ui-web && npm run typecheck && npm run test:unit:run`; a unit test asserting `warn`/`warning` cannot both be constructed |
| **I-3 API + agent families (V4/V5)** | `errors.<family>.<CODE>` keys with flat aliases; `AgentErrorCode` gains an `AgentErrorClass`; `searchState.ts` reads the envelope (D8); `Sv3Main` failure detail projects; the seven §G7 FE literals | `./gradlew.bat :modules:app-api:test :modules:ui:test`; `ErrorMessagePropertiesContractTest` + `ErrorCatalogJsonArtifactTest` green; ui-web gates + typecheck + vitest; a vitest asserting a 502 search renders the catalog message, not `HTTP 502` |
| **I-4 Ingestion + completion families (V6/V7) + MCP** | `ingestionReasons.ts` from `library-indexing-activity-panel.md:219` verbatim; the indexing-status FE surface (D9); `agentCompletion.ts` replacing `healthEventActivityRow.ts:94`; `McpToolSurface` code + class (D10); `03-knowledge-server.md:283` rewrite; kernel doc row | `check-failure-vocabularies` green for both families; `./gradlew.bat :modules:worker-core:test :modules:ui:test`; `node scripts/ci/check-dev-mcp-doc-sync.mjs`; ui-web gates; `ui-shot` steps for the ingestion reason panel; measured axe audit (see Constraints) |
| **I-5 Fold in the 889 + 896 families** — **BLOCKED-SOFT** | register rows + wording + remedy for `REPARSE_POINT`, `ROOT_UNREADABLE` (889 items 1-2), `DATA_DIR_CONTENTION` (889 item 5b), `DISK_CRITICAL` and the `INDEX_DISK_FULL` reclassification (896 low-disk) | unblocks when 889-filesystem-reality and 896-background-citizenship land their codes; `check-failure-vocabularies` + `check-readiness-reason-codes` green with the new members; `./gradlew.bat build -x test` |

Order: I-1 → I-2 → I-3 → I-4 → I-5. I-1 through I-4 have **no** dependency on 889 or 896; only I-5 does.

**Constraints.** No ranking, fusion, threshold or eval-baseline change (founder lane E owns those).
No pacing, extraction-pool, sandbox or job-object edit (885 / 896). No merging of verdict
authorities (D11). No `CLAUDE.md` edit except the one pre-merge-table row I-1 requires. Every
presentation-kernel rule binds (`27-frontend-presentation-kernel.md`): new wording renders through
existing atoms, no raw colour, no second severity→tone map. Closure requires an independent,
*measured* (axe / contrast oracle, not eyeballed) whole-screen UX audit by an auditor who is not the
committer (`slice-execution.md` `ux-audit-closure`). Java edits: `./gradlew.bat spotlessApply` then
`./gradlew.bat build -x test`.

## §K. Owner confirmations — PENDING

- **K1 (D3)** Namespace the catalog keys as `errors.<family>.<CODE>` with one release of flat
  aliases, or keep the flat keyspace and add a collision *detector* to the contract test instead?
  *Recommendation: namespace.* A detector converts a silent wrong-wording bug into a build break but
  still forces the two families to fight over one name; the alias window makes the migration free.
- **K2 (D7)** How strict is the free-text ban? Options: (a) register-declared exemptions only, gated;
  (b) exemptions declared but ungated (discipline); (c) FE-only ban, MCP prose unrestricted.
  *Recommendation: (a).* The prose-tier version of this rule is what produced the ten §G findings.
- **K3 (D9)** Does the ingestion FE surface belong to this lane or to 889-filesystem-reality item 4?
  *Recommendation: this lane builds the wording table and the projection; 889 adds its new reason
  rows to it.* Building the surface twice is the risk; building the table here and the rows there is
  the natural seam. Needs a note in 889 §Scope item 4 if accepted.
- **K4 (D10)** Should MCP failures carry a machine-readable `code`, given that an external agent could
  branch on it? *Recommendation: yes.* An agent that cannot distinguish PERMANENT from TRANSIENT
  retries a permanent failure, which is the observed cost of G3.
- **K5 (D4)** `success` as a member of the unified `Severity`: keep it (toast-only, never produced by
  a reason code), or keep the toast scale separate on the grounds that "success" is not a failure?
  *Recommendation: keep it, documented as toast-only.* One scale with a declared toast-only member
  beats two scales that must be hand-translated at every bridge.

## §Status

Original status, superseded by §T: CHARTERED (2026-09-02) — design settled; not started; final chunk (I-5) BLOCKED-SOFT on
889-filesystem-reality + 896-background-citizenship. Inventory in §F verified by reading each cited
file on `worktree-887-publish`. Owner confirmations K1-K5 pending. I-1's first run is expected RED;
its counts belong here when it exists.

## §Z. Routed one-liners

Out of scope, verified, not fixed here.

| # | file | drift | fix |
|---|---|---|---|
| Z-1 | `ApiErrorCode.java:15-16` | Javadoc instructs "Add a corresponding entry in `errorMessages.ts` (frontend catalog)" and names `ApiErrorCodeContractTest` as the enforcer. `errorMessages.ts` was deleted (`errorCatalog.ts:5`) and that test was renamed/split (`ApiErrorCodeInvariantTest.java:16-24`) | point the Javadoc at `messages/errors.en.properties` + `ErrorMessagePropertiesContractTest` |
| Z-2 | `docs/how-to/library-indexing-activity-panel.md:5` | `status: planning` since 419 shipped its backend; the doc is the de-facto authority for wording that nothing consumes | promote when D9 lands (this lane, I-4) — or set `status: proposed` now |
| Z-3 | `docs/explanation/03-knowledge-server.md:283` | "remain deliberately deprioritized without an active tracked item" — two of the three now have one (889-filesystem-reality), the third is D9 | rewrite; owned by 889 item 2 and this lane's I-4, whichever lands first |
| Z-4 | `AgentErrorCode.java:5-19` | bare enum with no `AgentErrorClass` annotation, while its sibling `ApiErrorCode` carries one per member — so agent errors have no `isRetryable()` | I-3 of this lane |
| Z-5 | `healthEventActivityRow.ts:92-94` | renders a raw `TerminalDisposition` code to the user | I-4 of this lane |
| Z-6 | `authoritySpace.ts:46` vs `TerminalDisposition.java:13` | two unrelated concepts both called "disposition" in user-adjacent code (G9) | naming decision for 878-agent-run-honesty-and-paging; not a rename this lane should make unilaterally |

## §T. Takeover investigation — 2026-09-05

### Verdict and scope of evidence

**GO now for the demonstrated failure-presentation defects. NO-GO for implementing
I-1 through I-5 unchanged.** The user-visible need is established without building a
new register first. The original charter overstates several premises and mixes
consumer fixes with retry-policy changes, a catalog migration, an ingestion feature,
and a public MCP contract change. Those are not all justified by the same evidence.

**LITE-CLASS: no**

Investigation baseline: local main commit
`8da7a24d94421656653e6fade63c79c9b3b47823`, isolated in
the dedicated `906-takeover` worktree, branch `codex/906-takeover`. The worktree was
created after running `node scripts/agent-analytics/world-state.mjs`; its directory,
branch, clean state and tempdoc-bearing base were checked before editing. The local
checkout and `origin/main` diverged, so this is a verdict about that explicit local
baseline, not a claim that every finding was reproduced on the current public release.
No other session's uncommitted changes were imported.

The complete charter, presentation-kernel authority map, ingestion documentation,
agent error architecture, runtime-contract reference, owning implementations and
neighboring consumers were inspected. Only this tempdoc was changed. No new design,
gate, product code, dependency installation, frontend rendering, model experiment,
Gradle build, PR or publication was performed. The shared stack was already held by
another session; none of this investigation required taking it over.

### Confirmed need, with current evidence

| Finding | Evidence at the investigation baseline | Implication |
|---|---|---|
| Search discards the typed failure envelope | `modules/ui-web/src/shell-v0/state/searchState.ts:645` returns before reading the non-OK response body; `:719` stores exception text. `modules/ui/src/main/java/io/justsearch/ui/api/ApiErrorHandler.java:439` emits code, class, retryability and i18n key. Probe T-E1 below executed the actual search module with a mocked 502 transport: one request, zero body reads, stored error `HTTP 502`. | The cheapest decisive evidence already exists. Fixing this consumer does not depend on a seven-family register or catalog namespace migration. The charter's "most-used" traffic claim was not measured. |
| Operation errors are rendered as implementation text | `modules/ui-web/src/shell-v0/chrome/Shell.ts:1175` and `:1199` interpolate operation IDs and exception messages into toasts. `modules/ui-web/src/shell-v0/operations/OperationClient.ts:65` already describes and carries typed `OperationError` information. | There is a concrete consumer gap, with an existing representation to inspect before adding another. |
| MCP generic failures all suggest transience | `modules/ui/src/main/java/io/justsearch/ui/api/mcp/McpToolSurface.java:2045` appends the same sentence irrespective of exception; `:2021` returns text plus `isError` only. Existing exception resolution and sanitization are in `ApiErrorHandler.java:222` and `:344`. | The misleading suggestion is established by source. The charter does not supply measured evidence that an external model retried a permanent failure; do not repeat that as an observed cost. Other MCP error paths also call `errorContent` without exceptions, so a classifier cannot cover the surface merely by changing the generic catch formatter. |
| Agent completion codes reach the Health row | `modules/app-services/src/main/java/io/justsearch/app/services/observability/health/HeadHealthEventsEmitter.java:140` supplies the disposition attribute without a message; `modules/ui-web/src/shell-v0/aggregate-substrate/strategies/healthEventActivityRow.ts:92` falls back to `disposition: <code>`. | This is a producer-to-consumer source trace, not just an unused fallback. The event also has an i18n-keyed title; the defect is the raw detail, not the absence of any readable title. No live screenshot was taken. |
| The ingestion ledger still lacks a hand-authored FE consumer | `docs/explanation/03-knowledge-server.md:157` describes shipped diagnostic endpoints; source searches for `diagnostics/ingestion`, `CLOUD_PLACEHOLDER`, `PARSER_TIMEOUT`, `EXTRACTION_DROPOUT` and `REASON_CODE_LABELS` found generated route/API inventory entries but no hand-authored consumer under `modules/ui-web/src`. | Generated endpoint discoverability is not a user-facing panel. The missing consumer remains real; ownership overlaps 889 item 4. |

### Corrections that prevent an unsafe or oversized takeover

1. **G10's "agent path cannot answer retryability" is false.** The bare enum is
   not the whole representation. `modules/app-agent-api/src/main/java/io/justsearch/agent/api/AgentEventPayloads.java:141`
   emits `errorClass`, `retryAction`, `retryAttempt` and `i18nKey` from the event;
   `modules/app-agent/src/main/java/io/justsearch/agent/AgentLoopService.java:1040`
   accepts code, class and retry action together. Canonical
   `docs/explanation/22-agent-system-architecture.md:241` explicitly describes their
   use in retry policy. Adding a fixed class to every enum member is therefore a
   policy change requiring investigation of existing pairs, not a prerequisite for
   readable failure copy. `OperationError.errorClass` is yet another distinct axis
   (dispatch-layer classes such as `HANDLER_FAILURE`), explicitly distinguished from
   the handler error code at `OperationClient.ts:69`. Do not conflate these axes.

2. **G2 proves deliberate sharing, not an existing wrong-message collision.**
   T-E2 found 130 API codes and 14 agent codes, with exactly one shared name:
   `INTERNAL_ERROR`. `ErrorMessagePropertiesContractTest.java:25` and `:75` explicitly
   govern that shared key. The message is generic enough for both; no contrary
   example was found. A future unintentional collision remains a plausible risk,
   but does not by itself establish that migrating every catalog key now is worth
   its compatibility cost. D3's alias window needs a real consumer/removal account:
   `errorCatalog.ts:226` prefers the wire-emitted key, `ApiErrorHandler.java:445`
   emits flat keys, and `AgentEventPayloads.java:154` notes those keys also enter
   persisted events. Merely adding namespaced properties does not migrate those
   producers or prove that old persisted keys disappear after one release.

3. **G6 overlooks the existing tone authority and distinct semantics.**
   `modules/ui-web/src/shell-v0/utils/statusTone.ts:29` already maps both `warn` and
   `warning` to warning tone, and `ok`/`success` to success tone. The readiness
   `Severity` includes operational/transition states (`readinessNotice.ts:56`),
   while toast severity is explicitly a tone axis
   (`components/advisory/ephemeralToast.ts:12`). No inconsistent rendering was
   reproduced. D4 would widen a failure type with success and potentially widen
   toast types with busy/ok; a spelling difference alone does not justify it.
   The duplicated toast union in `substrates/effect.ts:29` is narrower evidence
   than the charter's global-severity claim. Preserve the kernel's existing
   status-to-tone authority while evaluating any future shared type.

4. **The ingestion draft is not a complete catalog to copy verbatim.**
   T-E2 found 24 current `IngestionReasonCodes` constants, nine labels in the
   planning document, and 15 unlabeled current constants. The example ends with
   `// ... full set in IngestionReasonCodes.java`
   (`docs/how-to/library-indexing-activity-panel.md:256`). The draft also spans
   scan progress and scoped filename resolution; building a small reason-count
   panel would not justify declaring that entire how-to implemented. Success,
   unchanged files and policy skips are not necessarily failures and must not
   automatically receive warning severity or a remediation button.

5. **The proposed gate cannot be reused verbatim as claimed.** T-E3 exercised
   its exported `extractEnumConstants` on the actual proposed families: API = 0,
   agent = 13 of 14, completion = 4 of 5. The extractor at
   `scripts/ci/check-search-degradation-reason-codes.mjs:48` supports bare constants
   ending in comma/semicolon, not API constructor arguments, and misses a final
   semicolon-free member. `checkCorrespondence` itself accepts two empty sets;
   its existing CLI's non-empty check at `:145` is essential. Its diagnostic text
   at `:103` is also search-specific. These are reuse constraints, not evidence
   that the existing search gate fails its own supported inputs. A correspondence
   gate can prove code/label membership; it cannot prove a label is rendered, a
   remedy works, or free text is absent. The original I-1 acceptance overclaims
   coverage of §G. A `CLAUDE.md` table entry alone does not wire a new check into
   CI; `check-premerge-table.mjs:10` checks reference validity, not execution.

6. **Some evidence and dependencies are stale.** The readiness gate now reports
   56 emittable codes and 50 worded rows. The original V6 count of 26 is now 24.
   `docs/explanation/03-knowledge-server.md:283` already names 889's remaining
   junction-point gap and says extraction failures are typed outcomes; Z-3's
   quoted "deliberately deprioritized" text is gone. The incoming
   `REPARSE_POINT`, `ROOT_UNREADABLE`, `DATA_DIR_CONTENTION` and `DISK_CRITICAL`
   codes were not found in the inspected producer trees; 889 and 896 remain
   chartered on this baseline. Existing-consumer fixes do not need those codes.

### External research and its limits

Consulted on 2026-09-05, using primary sources:

- [MCP tools, protocol 2025-11-25](https://modelcontextprotocol.io/specification/2025-11-25/server/tools).
  This is the repository's pinned protocol
  (`modules/app-api/src/main/java/io/justsearch/app/api/mcp/McpContractVersions.java:30`).
  It permits structured results, uses `isError` for tool execution failures, and
  recommends serialized structured output in text for older consumers. D10's
  machine-readable direction is feasible, but "structuredContent only" needs
  text-client verification. Current tool definitions do not declare output schemas
  (`McpToolSurface.java:1860`); if a later change adds one, structured results must
  conform. MCP is a public runtime constituent, per
  `docs/reference/runtime-contract.md:25`, so this is more than an internal FE edit.
- [W3C error suggestion](https://www.w3.org/WAI/WCAG22/Understanding/error-suggestion.html)
  supports specific corrective advice where an input error's correction is known.
  It does not require a button for every runtime condition or one universal enum.
- [W3C status messages](https://www.w3.org/WAI/WCAG22/Understanding/status-messages.html)
  supports making dynamic success, waiting and failure messages available to
  assistive technology without unnecessary focus movement. It supports the
  charter's accessibility validation requirement, not a particular severity type.

These sources support understandable, actionable presentation. They do not
establish the need for the proposed global register, catalog migration or free-text
ban. No user study, actual model retry-cost measurement, or live accessibility
audit was performed in this takeover.

### Experiments and verification record

The dated experiment details are preserved in
[the takeover verification record](906-evidence/takeover-verification.md).

### What this displaces, what remains, and handoff

The evidenced work would displace the search store's raw transport-string path,
the operation-toast exception formatting, the generic MCP transience sentence,
and the completion-row raw-code fallback. It should consume the existing error
catalog, operation-error fields, event retry metadata and status-tone mapping
where applicable. It must not create another verdict or retry authority.

The ingestion consumer duplicates **889 §Scope item 4** unless one lane owns it;
906 has not silently taken ownership or edited 889. The `SearchResultsRenderer`
empty-result line is already jointly claimed with 902 and is not a failure merely
because it is empty. Neither overlap blocks the demonstrated search/operation
consumer need. No new tempdoc is needed for these findings.

The cheapest evidence invalidating the need for the **whole proposed package**
would be showing that existing authorities can deliver the concrete consumer
fixes without the new global mechanisms. The existing catalog, tone mapper,
classified event wire and T-E1 already make that a credible, lower-cost direction;
its implementation has not been attempted here. Conversely, namespacing needs a
real conflicting meaning or a justified compatibility requirement, severity
collapse needs an observed semantic/rendering inconsistency, and the all-family
gate needs evidence that its coverage and maintenance cost outperform extending
the existing authorities. A zero missing-label count alone proves none of those.

**Takeover is complete; no user input is needed to finish this investigation.**
K1-K5 remain historical, unapproved choices for a later scope/design conversation,
not five blocking questions posed by this takeover. A subsequent design/derisk
phase should resolve the corrected premises before treating §I as executable.
The existing independent measured UX review remains a future implementation
closure requirement, not a claim made here. This local tempdoc-only commit is to
remain unpushed until publication is requested; batch or ride it along under the
repository's tempdoc publication rules.

Closeout checks: `git diff --check` passed. The repository helper
`node scripts/dev/agent-spawn-sweep.cjs --occasion session-closeout --own-session-only`
reported no matching entries, zero deletions and two retained registry records.
No helper process was created by this takeover and none was killed. This was an
own-session check, not a claim that other sessions have no live helpers.
The closeout world-state command could not load `gray-matter` in the unprepared
worktree; rerunning the same repository command from main's installed environment
succeeded and reported this worktree clean (`DIRTY=0`), unpushed and `ACTIVE`.
No dependency preparation is needed to continue reading this investigation.

## §U. Accepted rescope and implementation contract — 2026-09-06

The owner accepted the plain-language rescope and explicitly requested delegated
implementation. This section supersedes original decisions/chunks where they
conflict; §T's findings remain the evidence. No catalog namespace migration,
universal severity union, fixed agent-code classification, merged reason enum,
or new all-family governance gate is authorized by this implementation scope.

### Design and ownership

Use typed information at its existing producer/consumer boundary. Preserve backend
classification and authorization rather than deriving retry policy in the UI.
Human wording comes from the existing error catalog where a catalog code exists;
local transport failures get bounded, understandable fallbacks. Remediation must
be actionable and must never silently replay a mutating operation. Preserve the
existing tone and readiness authorities.

906 owns the minimal ingestion-summary FE consumer previously duplicated by
889 item 4. 889 retains its filesystem admission behavior/new reason producers;
896 retains disk policy. Route that ownership explicitly in 889. The panel words
the current ledger reasons, distinguishes success/skips/failures/deferred work,
handles loading/empty/error states honestly, and uses existing diagnostic summary
transport. It does not resolve hashed paths, add automatic file retries, or claim
the older how-to's scan-progress and filename-resolution plans are all delivered.

MCP failures retain `content` and `isError`; typed information is additive and
describes the existing classification. Text-only and structured clients must get
consistent facts. Inspect all error call sites and existing protocol/public
contract tests before changing signatures. Unknown classification must remain
unknown, never an invented transient promise.

Completion wording belongs at the Health event projection, preserving the
separate terminal-disposition semantics and the event's severity/title.

### Bounded implementation and acceptance

- [x] U1 — Search and operation consumers: parse typed search failure envelopes,
  preserve supersession/abort behavior, localize supported codes, provide safe
  malformed/non-JSON/network fallbacks, and render useful recovery guidance.
  Replace Shell operation/undo exception prose using existing OperationError
  information. Regression tests must exercise actual consumers, including a 502
  typed body and a permanent/policy operation failure; no automatic mutation retry.
- [x] U2 — MCP failure facts: remove unconditional transience claims, reuse
  existing exception classification/sanitization, preserve text-client legibility,
  add structured code/class/retryability only when justified by source information,
  and test permanent/transient/validation/unknown paths and serialization.
- [x] U3 — Library ingestion summary: use the current 26-code vocabulary with
  complete readable labels, show bounded summary counts by outcome/reason, expose
  refresh/recovery on fetch failure, and prove lifecycle cleanup/stale-response
  behavior and success/skip/failure/unknown handling with regression tests.
- [x] U4 — Health completion details: replace raw terminal-disposition details
  with readable explanations for all five known outcomes; preserve useful unknown
  fallback and existing event title/severity. Add behavioral regression tests.
- [x] U5 — Integration/teardown/docs: remove superseded raw-string paths in the
  touched consumers; update canonical behavior docs and 889 ownership, without
  claiming deferred features shipped. Regenerate/check affected documentation.
- [x] U6 — Verification and independent review: frontend typecheck, full unit
  suite, relevant UI gates; affected Java tests and required build/multi-module
  tests with serialized Gradle use. Validate real Lit UI through browser/harness
  with measured accessibility evidence, exercise MCP live and a real model query
  for its AI-facing change. Independent refute-first review of code and evidence;
  remedy substantive findings and record any external blockers honestly.

U1, U2 and U3 are delegated in separate worktrees from the same accepted baseline.
The parent owns U4, tempdoc/canonical-doc integration, shared-stack/Gradle access,
final validation and review coordination. Delegates must not start a shared
stack, run Gradle without coordination, publish, or edit another lane's files.

The principle is to preserve known failure facts until presentation; it already
applies to OperationError and agent events. Evidence of value is the previously
discarded 502 envelope and regression checks that those facts reach rendered copy.
Retire any new adapter that gains no real consumer or duplicates an existing
usable adapter. Future filesystem/disk codes and empty-search adequacy remain
with their existing owners; they do not block U1–U6.

## §V. Implementation and verification — 2026-09-06

The three delegated lanes are integrated in `codex/906-takeover`; the parent
implemented Health wording and integrated the documentation and verification.
The work remains local and unpushed. Original §I mechanisms not retained by §U
are superseded, not outstanding implementation requirements.

### Delivered behavior and primary evidence

- Search preserves typed HTTP failure facts through `searchState.ts`, including
  non-2xx bodies, and uses the existing error catalog for readable explanations.
  Shell invoke/undo failures retain existing OperationError metadata. Regression
  authorities: `searchState.failure.test.ts`, `Shell.test.ts`, and
  `SearchV3View.search.test.ts`. The cancellation tests prove transport/body-read
  AbortErrors with an unchanged generation, separately from query supersession.
- MCP error text and `structuredContent` share the same known facts. Exceptions
  reuse `ApiErrorHandler`; plain errors remain unclassified. Tests cover
  permanent, transient, validation, unknown, operation-result and confirmation
  paths in `McpToolSurfaceTest` and `McpProtocolHandlerTest`. Tool surface version
  is 0.7.0; MCP protocol and runtime umbrella versions remain unchanged.
- Library Folders mounts `jf-ingestion-summary`, with all 26 current reason
  labels, outcome grouping, bounded visible groups, retained-event counts,
  loading/empty/failure/refresh states and stale-request/lifecycle protection.
  It consumes the existing authorized summary endpoint and existing status tone.
  `IngestionSummary.test.ts` and `LibrarySurface.ingestion.test.ts` prove these
  behaviors. A review-found live-region issue was fixed: unchanged background
  refreshes cause zero status-node mutations; manual refresh, changed totals and
  fetch failures still announce. No file is automatically retried.
- Health explains all five terminal dispositions at the existing event-row
  projection. `healthEventActivityRow.test.ts` verifies rendered copy, unknown
  values, explicit-message precedence, title/severity preservation and a source
  census against the Java enum. No global severity/type migration was introduced.

Canonical MCP/runtime, Library and Health behavior documentation now describes
this scope. The older Library how-to clearly separates the implemented summary
from planned progress, filenames and path resolution. 889 item 4 routes the
consumer to 906. The generated component vocabulary includes the new component.

### Verification record

Local evidence paths below are relative to the implementation worktree. The
runnable regression tests and harness steps are committed; scratch run outputs
are local evidence, not required runtime assets.

- Full `./gradlew.bat test` passed (5m15s), including affected UI Java tests:
  `tmp/906-test.log`. The delegated affected-module run counted 973 UI tests,
  zero failures/errors and one existing real-corpus skip; the MCP subset was
  203 tests. Gradle invocations were serialized across agents.
- Final integrated `./gradlew.bat build -x test` passed after the palette fix
  and vocabulary regeneration (54s), `tmp/906-build-final.log`.
- `npm run typecheck` and full `npm run test:unit:run` passed after the final
  palette fix: 471 files, 6,320 tests, `tmp/906-ui-unit-final.log`. Forty UI gates
  passed via `node scripts/ci/run-ui-web-gates.mjs`,
  `tmp/906-ui-gates-final.log`. The first gate
  run correctly caught the new component's stale generated vocabulary; it was
  regenerated with the existing generator and the full gate set passed.
- Full build plus Head/Worker installDist passed at product commit
  `c6324725c7ae6a600cd4e38299ea1ae91c5598ca`, `tmp/906-runtime-build.log`.
  Runtime verification uses the same committed product code in the dedicated
  `906-runtime-verification` worktree under `.claude/worktrees`, because dev MCP
  rejects external worktree roots. No ownership/path guard was bypassed.
- Owned live run `da91e414-dde9-4e97-92e7-b3022a5c5fc9`: production `POST /mcp`
  initialize reports 0.7.0; invalid argument and three malformed Lucene queries
  report VALIDATION/nonretryable with text/structured parity; valid search
  succeeds. `tmp/906-mcp-live.py` is the reproducible read-only probe and
  `tmp/906-mcp-live.json` records results. These live cases did not produce a
  permanent error; permanent/transient behavior is covered by Java tests.
- Real local model smoke through `jseval tier2-eval`, one query containing the
  exact live validation-error text: Qwen3.5-4B-Q4_K_M.gguf answered "No" to
  whether the unchanged request should automatically retry. Zero errors,
  `tmp/906-model-smoke/tier2-eval.json`. This is one interpretation smoke, not
  retrieval quality evidence or an autonomous-agent retry benchmark. An initial
  run used the harness's default llama port and failed; the successful run used
  the owned process's verified port 8082.
- New instrumented `health-completion` and `library-ingestion` fixture steps
  assert actual transport-to-Lit rendering. Their captures in `tmp/906-ui/`
  show the changed content in frame, no document overflow and zero axe
  violations in each captured viewport. A live Library capture is also saved
  in `tmp/906-ui-live/`. Fixture counts are not production ingestion evidence.
- Independent refute-first review inspected code and measurements and reran
  30 cancellation/announcement tests. Its cancellation false-pass concern,
  background announcement issue and obsolete Health docs were remedied.
- Native browser verification found an existing palette pointer bug on the
  search entry path: a nonfocusable option's mousedown blurred the combobox,
  causing host cleanup to dismiss the palette before click. The bounded fix
  preserves combobox focus during primary mousedown, retaining existing keyboard
  and external-focus behavior. `Sv3Palette.test.ts` models that focus sequence;
  `search-failure` now asserts native pointer selection, one 502 response and the
  rendered alert. Independent Chromium evidence in
  `tmp/906-search-pointer-check.json` proves exactly one command/request and no
  page errors. `tmp/906-ui/search-failure.measure.json` reports no axe violations
  or document overflow; the full harness still records its existing two console
  errors. These are separate instruments, not contradictory clean-console claims.
- Final independent Sol review found no substantive issue in the pointer fix,
  verified keyboard/external-focus preservation and ran the 28 palette tests.
  The native pointer observation is accompanied by its runnable scratch script
  `tmp/906-search-pointer-check.py`; the committed `search-failure` harness is
  the maintained regression path.
- The broader `jseval ui-a11y-gate --output-dir tmp/906-ui-a11y` passed with
  no new violations against the baseline (exit 0), `tmp/906-ui-a11y.log`.
  Documentation index/skill regeneration checks, canonical link verification,
  UI step coverage, Python harness syntax and `git diff --check` passed.

### Limits and follow-up boundaries

The Health baseline capture has an existing contrast failure on an unrelated
unknown-presence badge; scrolling to completion rows does not fix it. Existing
transition-timeout console errors and fixture localization-key titles likewise
prevent a claim that the entire screen is accessibility/console clean. The new
completion detail text and Library panel were measured in their actual viewport.

The ledger's existing SQL-error fallback returns an empty summary
(`SqliteJobQueue.java:1492`, `ingestionOutcomeSummary`); no database failure was reproduced
in this task. The new consumer detects transport/schema failures, but cannot
distinguish an empty ledger from that server fallback. This remains a backend
diagnostic limitation, not a claim of end-to-end database fault detection.

889/896 retain future filesystem/disk reason producers, and 902 retains empty
search adequacy. The original universal gate, catalog namespace migration and
severity collapse are explicitly outside the accepted scope. Publication and
merging have not been requested.

### Closeout

U1–U6 are complete. No implementation or verification step in the accepted
scope is awaiting the owner. The work remains on `codex/906-takeover`, unpushed;
publication requires a separate request. Delegated worktrees and the verification
worktree are clean and retained for traceability. The main checkout was not
edited by this implementation.

The owned dev run was stopped through MCP with `portsClosed: true`. Repository
own-session helper sweeps confirmed cleanup of the two screenshot Vite helpers
and the final accessibility-sweep helper; unrelated records were retained.
Closeout world state is recorded in `tmp/906-world-closeout.log`.

## §W. Requested review-changes pass — 2026-09-06

This review supersedes §V's completion verdict: **one P2 remains open**. No
product code was changed during this review. An independent Sol reviewer and
the parent independently checked the failure path; no other substantive finding
survived. Fresh targeted Vitest verification passed 94 tests in six files
(`tmp/906-review-tests.log`). Earlier build, full-suite and live evidence was
re-read rather than described as newly rerun.

### W1 — Async MCP answer failures lose the cause's classification

`McpToolSurface.java:597` waits on `retrieveContext(...).toCompletableFuture().get`.
An exceptional completion arrives as `ExecutionException`; the catch at line 619
passes it to `toolFailureContent`, which classifies that wrapper directly at
line 2075. `ApiErrorHandler.resolve` recognizes the underlying gRPC exception,
but not these async wrappers, so its fallback is INTERNAL_ERROR/PERMANENT.

Runnable reproduction: `tmp/Review906AsyncFailure.java`, executed against the
built UI install classpath; output `tmp/906-review-async-failure.log`. A real
`CompletableFuture.failedFuture(Status.UNAVAILABLE.asRuntimeException()).get()`
fed into the actual MCP helper produced:

```text
cause=SERVICE_UNAVAILABLE
wrapper=INTERNAL_ERROR
structuredContent: errorCode=INTERNAL_ERROR, errorClass=PERMANENT, retryable=false
```

This is a reproduced failed-completion interface path, not a reproduced live
outage: the current RemoteDocumentService commonly converts gRPC failures to
fallback results. `McpErrorLegibilityTest.java:127` throws synchronously, which
does not exercise the wrapper and cannot catch this classification loss.

Fix plan: unwrap completion/execution wrappers at the async error boundary,
preserve the existing classifier as the policy authority, and use the same
resolved cause for message and structured facts. Add a regression that returns
an exceptionally completed future through the real `justsearch_answer` consumer,
covering transient, validation and permanent causes plus an unknown fallback.
Run affected Java tests and the required build, then independently review the
fix and update U2/U6. Publication remains unrequested.

### W1 resolution and publication preparation

The owner subsequently said to proceed. The MCP helper now removes only
ExecutionException/CompletionException wrappers before handing the underlying
exception to the existing API classifier and sanitizer. Message and structured
facts use that same cause. An identity-based guard bounds cyclic wrapper chains;
absent or non-Exception causes retain the existing unknown fallback.

`classifiedAnswerFutureFailure` exercises the real `justsearch_answer` consumer
with exceptionally completed futures for unsupported, invalid-argument,
unavailable, timeout and unknown causes, both directly and through nested
wrappers. All five cases failed before the fix
(`tmp/906-async-regression-before.log`); the full affected UI Java module passed
after it (`tmp/906-async-module-tests.log`, 2m21s), including all 23
McpErrorLegibilityTest cases with zero failures/errors. This closes the review
finding rather than weakening its regression. Independent Sol review found no
substantive issue in the fix or regression. Canonical MCP wording was updated
and documentation regeneration/link checks passed.

U6 remains pending final verification on the candidate caught up with origin/main.
The publication branch must contain only this tempdoc's changes; the original
implementation branch's unrelated local ancestry is not a publication candidate.

## §X. Publication candidate verification — 2026-09-06

The owner authorized publication. `codex/906-publish` starts at origin/main
`b96cd9998` and carries only the 13 commits implementing this tempdoc. Independent
Sol range-diff review found no lost change; current-main Detailed terminology and
uiModeState registrations are preserved. Product code was verified at `16bdb8254`.
The remaining changes only move historical evidence and record this verification.

- Build, including integration checks, passed; full Java unit tests and license
  validation passed. All 23 MCP failure-classification tests pass.
- Frontend typecheck, all 6,333 tests in 472 files, and all 27 current UI gates pass.
- Python suite: 3,094 passed, 12 skipped; all eight generated-file sets match.
- Three maintained browser scenarios pass with no captured axe violations or
  document overflow; the full accessibility sweep reports no new violations.
- Documentation, tempdoc numbering/size, script lint, runtime/configuration and
  architecture checks pass. The branch secret scan reports no leaks.

Reproducible commands, log names and limitations are in
[the publication verification record](906-evidence/publication-verification.md).
U1–U6 and W1 are complete. Remaining publication work is the PR checks, merge
queue and confirmation of public CI on main. Existing §V limits still apply.
