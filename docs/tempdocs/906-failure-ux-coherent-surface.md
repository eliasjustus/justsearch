---
title: "Failure UX as one coherent surface: one presentation authority for failure wording, remedy and severity, projected over the six per-question reason vocabularies that already exist — never merged into a super-enum"
type: tempdocs
status: "CHARTERED (2026-09-02) — design settled; not started; final chunk BLOCKED-SOFT on 889 + 896"
created: 2026-09-02
updated: 2026-09-02
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

CHARTERED (2026-09-02) — design settled; not started; final chunk (I-5) BLOCKED-SOFT on
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
