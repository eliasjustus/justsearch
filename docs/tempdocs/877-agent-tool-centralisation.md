# 877 — Agent-tool centralisation: one authority per fact

```
status:  IMPLEMENTING (2026-08-26) — §1 theorized, §2 designed against re-verified
         source, §3 planned. Was THEORIZING.
created: 2026-08-26
follows: 868 (§D — the agent tool surface investigation this remediation comes out of),
         865 §7.5 (ToolResultCarrier — the worked example of the pattern this tempdoc
         generalises), 832 (AgentToolFactory.assemble — one construction authority),
         742 (retire-with-a-sweep — a replaced fork's fingerprints go in the same PR)
owner:   session f6617483
scope:   the SMALL centralisations only. Re-homing the catalog/adapters and splitting
         `executeIteration` are workstream 880 and are deliberately NOT in this tempdoc.
```

## Findings handed over

Duplication census, 2026-08-26 (independent review; every line reference re-verified
against this worktree in §2 before use).

1. **Dead `PARAMETER_SCHEMA` × 4** (`SearchTool:~153-192`, `BrowseTool:~51-79`,
   `IngestTool:~36-59`, `FileOperationsTool:~51-93`; only test callers, 7 sites). They
   declare parameters the model cannot see: `conflict_strategy` (so always FAIL),
   `explanation` (always the literal "File operations", persisted to the undo journal and
   surfaced by `AgentRunQueryService:~568`), `max_folders/max_files`, and the file-op item
   shape (the catalog Interface declares `operations: array` UNTYPED at
   `AgentToolsOperationCatalog:~319`). Delete all four + `parameterSchema()` + the tests;
   promote the real file-operations item schema and `conflict_strategy` into the catalog
   Interface; decide `mode`/`pipeline` for search (declare with enum or delete the prose).
   Also `MAX_EXPANDED_FILES` in `IngestTool:~172-179` guards an unreachable path since 418
   Phase B (and `IngestToolTest:~219-226` asserts a false invariant) — delete.
2. **`agent.maxToolResultChars` has two readers**:
   `AgentContextCompressor.MAX_TOOL_RESULT_CHARS:~74` (floored 100, frozen at class-init) vs
   `SearchTool.resolveMaxToolResultChars:~54` (unfloored, per call). One accessor:
   `ToolResultCarrier.layerTwoCapChars()` exists — use it. Also `SearchTool.formatResults`
   per-result budget (`~447-448`) counts excerpt chars only, not headers/paths/framing, so
   `limit:20` reliably exceeds the Layer-2 cap and the tail dies — budget against emitted
   length.
3. **structuredData keys are bilateral string literals**: `searchResults` (SearchTool:~403 ↔
   AgentSession:~350, `toolSearchCard.ts:~63`), `readResults`, `feedbackFeatures` (↔
   `AgentDispositionWiring:~96`), `lineage` (`OperationResult.java:~88` writes a bare literal
   though `OutputLineage` owns the token). Only `grounding` has a constant
   (`OperationResult.GROUNDING_KEY`) + keyset test (`AgentSessionGroundingTest:~548-585`).
   Apply that pattern: constants beside `GROUNDING_KEY`, imported by every
   producer/consumer, one keyset conformance test per key; the TS mirror stays a literal but
   the test binds it (`shared.ts` is hand-written — say so in the test).
4. **Roots access: 13 call sites, 5 guarded copies with 4 degrade behaviours, 4 unguarded**
   (`BrowseTool:~171,~218`, `IngestTool:~222`, `AgentToolFactory:~120`); three near-verbatim
   `validateAgainstRoots` wrappers; `IngestTool.resolvePath:~215-251` is a second
   relative-resolution algorithm that falls through to the JVM cwd. Introduce
   `AgentToolPaths.RootsView` (one guarded accessor + `validate(path, paramName)` +
   `resolveRelative`) injected instead of the raw `Supplier<List<RootInfo>>`; delete the
   fork. NOTE: workstream 875 changes path *semantics* (fail-closed, realpath) — you change
   *structure*; merge origin/main before you touch these files and keep the semantic
   behaviour whatever main has.
5. **Arg parsing: 30 raw `JsonNode` sites, two private helpers** (`ReadDocumentTool.intArg`,
   `SearchTool.boolField`); `SearchTool` coerces `"5"` via `asInt`,
   `ReadDocumentTool.intArg` requires `isNumber()` so `offset_chars:"3000"` silently
   restarts at page 0 (workstream 878 owns the *read* semantics; you own the shared helper —
   coordinate by making the helper coerce and reject loudly on non-numeric, and let 878 adopt
   it). `limit`/`max_folders` unbounded below. One `ToolArgs` helper (int/bool/string with
   bounds), used by all five tools.
6. **Errors: 7 tool-level `catch(Exception) → failure(prefix + e.getMessage())` +
   `LOG.error` with stack traces on model-authored bad JSON; 9 handlers repeat
   `"Invalid args: "`; 19 of 39 handlers declare their own `ObjectMapper`.**
   `OperationResult` already carries `errorCode/errorDetails/retryable` (`~35-41`) and no
   agent tool uses them. One `AgentToolErrors` mapping (`JacksonException → BAD_REQUEST`,
   timeouts/gRPC → `WORKER_UNAVAILABLE` retryable, else INTERNAL), WARN without trace for
   input-shaped failures. `ReadDocumentTool` ignores `DocumentSlice.error` (reports Worker
   failures as "not found") — fix as part of the same helper adoption.
7. **Browse never returns absolute paths** (`BrowseTool.toRelativePath:~308-334`) while the
   prompt (`AgentPromptComposer:~41-44`), search's empty-result hint (`SearchTool:~296-299`)
   and read-document's schema say it does; `FileOperationsTool.parseOperations:~402` does
   bare `Path.of` and cannot re-resolve → `DEST_NOT_SANDBOXED`. Decide one convention (emit
   absolute, or give file-ops the same `resolveRelative` pre-pass) and make prose match.
8. **Timeouts: 9 Java constants in 3 units, zero config derivation**
   (`AgentCitationResolver:~35` 4000 ms, `AgentLlmCaller:~274` 5 min,
   `AgentSessionRegistry:~29` 30 min, `AgentStepRunner` 30 s virtual / 120 s gates,
   `AgentToolDispatcher:~48` 300 s, `ReadDocumentTool:~82` 15 s, `FileOperationsTool:~49`
   2 s); the virtual-tool comment claims alignment with the 300 s approval gate (10× stale).
   Only `ReadDocumentTool` has a fetch timeout — `SearchTool`/`BrowseTool`/`IngestTool.scanRoot`
   block the loop thread indefinitely. One `AgentTimeouts` policy record (config-derived
   where sensible), applied to all four tool fetches.
9. **MCP `justsearch_search` bypasses `SearchTool`** (`McpToolSurface.java:~814-853` →
   `KnowledgeHttpApiAdapter.search`), forcing a second ~390-LOC rendering stack
   (`McpSearchResultFormatter`, `McpEvidenceProjection`) beside `SearchTool.formatResults`'s
   ~70. The delivery governor/framing are legitimately different (tempdoc 200/770) — do not
   merge them. Close only the bypass: share `SearchTool.buildSearchEvidence` (the structured
   half) so evidence identity is one authority; leave MCP text rendering alone unless
   trivially shareable. If this proves >1 day, record the design and defer with a clear
   boundary.
10. **Stale comments/docs**: `AgentToolsOperationCatalog:~260-265` ("handler does not yet read
    list_files" — false); `~137-138` line refs; `AgentToolHandlers:~156` log lists tools by
    hand; `docs/explanation/22-agent-system-architecture.md:~91-95` lists 5 of 7 tools and
    claims file-ops can "delete"; `ServicePhase` "seventh tool". Sweep as you touch.

---

## 1. Theorization (phase 2)

### 1.1 What kind of problem this actually is

Ten findings, one shape: **a fact about the agent tool surface is written down in more
than one place, and the copies have drifted.** They differ only in how visible the drift
already is.

There is a useful three-way split, and it decides how much structure each item deserves:

- **Phantom authority** — a declaration nothing reads (findings 1, and the dead
  `MAX_EXPANDED_FILES` guard). The copies did not drift *from* each other; one copy stopped
  being consulted at all, and kept looking authoritative. This is the most dangerous class,
  because a reader (human or agent) treating `PARAMETER_SCHEMA` as the tool's contract will
  be wrong in a way no test can notice: the tests assert the schema *parses*, not that it is
  *used*. Fix: delete the phantom, and move whatever was genuinely true in it to the one
  place that IS read.
- **Split authority** — two live readers of the same underlying fact, each with its own
  derivation (findings 2, 4, 5, 6, 8). Both run; both are correct in isolation; the pair is
  incoherent. Fix: one accessor, N callers.
- **Bilateral literal** — a fact that crosses a seam (Java↔Java, Java↔TS) and is spelled
  independently on each side (findings 3, 7, 10). A constant closes the Java half; the wire
  half can only be closed by a test that asserts the two spellings are equal. 865's
  `GROUNDING_KEY` + `AgentSessionGroundingTest` is the shipped precedent and it works —
  copy it rather than invent a mechanism.

Findings 9 is different in kind and is treated separately in §2.9.

### 1.2 Framings considered and rejected

**"Build a tool-definition DSL."** Tempting: one declaration per tool from which the
catalog `Interface`, the arg parser, the bounds, the error mapping and the docs table are
all generated. That is the real end state of "one authority per fact" — but it is a large
speculative abstraction for five tools whose shapes differ a lot (file-ops has a nested
item schema and an undo journal; read-document has a config-derived page size; search has a
server-injected `docIds` key the model must never see). Per AHA, only unify what shares a
reason to change. The five tools' *parameter shapes* do not share a reason to change; their
*plumbing* (parse, bound, validate a root path, classify an error, time out a fetch) does.
So: centralise the plumbing, leave the shapes declared per tool — in the ONE place the
model actually reads them, the catalog `Interface`.

**"Ratchet it with a gate."** CLAUDE.md is explicit that a load-bearing must/never belongs
in a hook or gate, not prose. A `check-agent-tool-schema-coverage` gate could assert every
key a tool reads is declared in the catalog. But the repo already has a heavy gate kernel
and tempdoc 563 retired two discipline gates for being apparatus. The cheaper instrument
that catches the same class is an ordinary unit test that enumerates the keys each tool
reads and asserts the catalog declares them — it fails in the same build, needs no
registration, and is deletable when the tools are. Prefer the test; revisit the gate only
if the test proves insufficient (see the retirement condition in §2.11).

**"Do nothing about `mode`/`pipeline`; it's harmless."** It is not quite harmless — a
reader of `SearchTool` today cannot tell which of its accepted keys the model can send.
But the honest fix is not to declare them (868 §B.4 deliberately withheld them: they are
internal retrieval levers, not a capability the model should steer). The fix is to delete
the *prose* that claims otherwise, and to say plainly, once, in the tool, which keys are
model-visible and which are server-injected.

### 1.3 Hidden assumptions worth naming

- *"The catalog `Interface` is what the model sees."* Verified — `AgentOperationEmitter`
  reads `op.intf().inputs()`. Everything in this tempdoc rests on that; §2.1 re-verifies it
  against source before any deletion.
- *"A degrade-open roots check is deliberate."* It is — `SearchTool`, `ReadDocumentTool` and
  `BrowseTool` all document "no roots configured ⇒ do not reject" and the Worker's own
  index-membership check is the real boundary. Centralising must preserve degrade-open, not
  quietly convert it to fail-closed. That is workstream 875's call, not this one's.
- *"Tests that assert a constant's range are testing something."* `IngestToolTest`'s
  `MAX_EXPANDED_FILES >= 1000 && <= 100_000` is a tautology over a literal. Deleting a
  guard means deleting its tautology, not weakening a real assertion.

### 1.4 A principle this points at

**One authority per fact; a second speller must be a projection, not a peer.** The repo
already names this for search execution (`governance/execution-surfaces.v1.json`, the
projection-vs-fork discovery step) and for construction (832's `assemble`). This tempdoc is
the same rule applied to the *agent tool surface*, where there is no register — so the
enforcement is per-fact tests instead. Whether the agent tool surface eventually earns its
own register is a §2.11 question, not a §1 one.

---

## 2. Design (phase 3)

Every line reference below was re-verified in this worktree at
`666422b6` (`git merge origin/main` reported already-up-to-date before design).

### 2.0 Re-verification of the handed-over census

| # | Claim | Verified line(s) | Verdict |
|---|---|---|---|
| 1 | `PARAMETER_SCHEMA` ×4 | `SearchTool.java:153-193`, `BrowseTool.java:51-79`, `IngestTool.java:36-59`, `FileOperationsTool.java:51-93` | confirmed |
| 1 | only test callers | 7 test sites (`BrowseToolTest:30`, `FileOperationsToolTest:44,248`, `IngestToolTest:75,464`, `SearchToolTest:45,396`) | confirmed |
| 1 | catalog declares `operations: array` untyped | `AgentToolsOperationCatalog.java:320-321` | confirmed |
| 1 | `explanation` always the literal | `FileOperationsTool.java:136-137`; journalled and surfaced at `AgentRunQueryService.java:568` | confirmed |
| 1 | `MAX_EXPANDED_FILES` unreachable | `MAX_PATHS = 100` (`IngestTool.java:33`) caps the array; `singleFileCount ≤ 100 < 10_000` (`:172`) | confirmed |
| 2 | two readers of the cap | `AgentContextCompressor.java:74` (floor 100) vs `SearchTool.java:54-57` (no floor) | confirmed |
| 2 | budget ignores framing | `SearchTool.java:447-448` divides by `hits.size()` and counts only `excerpt.length()` (`:479`) | confirmed |
| 3 | bilateral literals | `SearchTool.java:401-404`, `ReadDocumentTool.java:278`, `AgentSession.java:350,353`, `AgentDispositionWiring.java:96`, `OperationResult.java:88`, `toolSearchCard.ts:63` | confirmed |
| 4 | roots forks | `BrowseTool.java:171,218` unguarded; `IngestTool.java:222` inside a broad catch; `AgentToolFactory.java:118-126` unguarded supplier body; wrappers at `SearchTool:547-563`, `BrowseTool:354-367`, `ReadDocumentTool:325-338` | confirmed |
| 5 | two private arg helpers | `ReadDocumentTool.java:300-304` (`isNumber()`), `SearchTool.java:126-128` | confirmed |
| 6 | typed error fields unused by tools | `OperationResult.java:32-39`; no agent tool calls the 4-arg `failure` | confirmed |
| 7 | browse emits root-relative | `BrowseTool.java:248,285` write `toRelativePath(...)`; prompt says absolute at `AgentPromptComposer.java:40-44` | confirmed |
| 8 | nine constants, three units | listed in the census; all re-read | confirmed |
| 8 | stale virtual-tool comment | `AgentStepRunner.java:1092-1098` says "aligns with INLINE_CONFIRM approval-gate timeout" (30 s) vs `AgentToolDispatcher.java:48` (300 s) | confirmed |
| 10 | catalog list_files comment false | `AgentToolsOperationCatalog.java:260-265` vs `BrowseTool.java:124-126` (reads it) | confirmed |
| 10 | `ServicePhase` "seventh tool" | `ServicePhase.java:126` already reads "seventh COMPONENT and fifth tool" | **already fixed** by 868 §C.5 — nothing to do |
| 10 | doc 22 lists 5 tools, claims delete | `docs/explanation/22-agent-system-architecture.md:88-96` | confirmed (`core_remember`, `core_navigate_to_surface` missing; `FileOperation.OpType` has no DELETE) |

One census item is retired as already-done (`ServicePhase`). Everything else stands.

### 2.1 Phantom schemas → the catalog is the only declaration (finding 1)

`AgentOperationEmitter` projects `op.intf().inputs()` to the model. Therefore the catalog
`Interface` is the tool's contract and `PARAMETER_SCHEMA` is a second, unread one.

**Delete** all four constants, all four `parameterSchema()` accessors, and the seven test
call sites. **Promote** into the catalog `Interface` exactly the keys the tools honour and
the model should be able to send:

- `core.file-operations`: the item shape (`op` enum MOVE/RENAME/MKDIR/COPY, `source`,
  `destination`) — today the model must *guess* it against an untyped `array`; plus
  `conflict_strategy` (enum FAIL/SKIP/AUTO_SUFFIX) and `explanation`. `explanation` is
  promoted rather than deleted because it is persisted to the undo journal and rendered in
  the undo history — a constant there makes every batch indistinguishable.
- `core.browse-folders`: `max_folders` and `max_files`. The tool's own truncation message
  instructs the model to "increase max_folders" (`BrowseTool.java:253`) — an instruction it
  cannot follow while the key is undeclared. Declaring it makes the hint actionable.
- `core.ingest-files`: nothing. `IngestTool` never reads `explanation`; the key dies with
  the schema.
- `core.search-index`: nothing. 868 §B.4 deliberately withholds `mode`/`pipeline`; deleting
  the prose that advertised them settles the census's "declare or delete the prose"
  question in favour of delete. `SearchTool` keeps honouring `mode` (it is the shape the
  `agent.searchDefaultMode` config default flows through) and keeps `pipeline` parsing,
  with a comment stating plainly that no production caller supplies `pipeline` today.

**Orphaned by this**: `PARAMETER_SCHEMA` ×4, `parameterSchema()` ×4, 7 test call sites,
`IngestTool.MAX_EXPANDED_FILES` + its guard + `IngestToolTest:219-226`. All deleted in this
PR (742).

**The test that goes red on drift**: `AgentToolCatalogContractTest` — for each of the five
tools, the set of argument keys the tool reads is enumerated and asserted to be a subset of
the keys the catalog `Interface` declares, with a documented exception list (`docIds`,
`mode`, `pipeline`, `path` as a `destination` alias) naming *why* each is server-injected or
deliberately undeclared. A tool that starts reading a new key without declaring it fails.

### 2.2 One cap accessor (finding 2)

`SearchTool.resolveMaxToolResultChars` is deleted; `ToolResultCarrier.layerTwoCapChars()`
is the only reader of `agent.maxToolResultChars` outside `AgentContextCompressor` (which
owns the constant it exposes).

The budget bug is a separate fact and gets a separate fix: `formatResults` must budget
against **emitted length**, not excerpt length. Concretely — reserve the trailing summary
line, then give each hit `remaining / hitsLeft` of the *emitted* budget and charge it the
full length of every line it writes (header, `Path:`, carrier line framing), so the final
string is ≤ the Layer-2 cap by construction rather than by luck.

**Test**: a 20-hit response with long excerpts formats to ≤ `layerTwoCapChars()`, and
`AgentContextCompressor.truncate` returns it unchanged. That is the assertion that would
have caught the tail-death.

### 2.3 structuredData key constants (finding 3)

Constants beside `GROUNDING_KEY` in `OperationResult`: `SEARCH_RESULTS_KEY`,
`READ_RESULTS_KEY`, `FEEDBACK_FEATURES_KEY`, `LINEAGE_KEY`. `withLineage` uses
`LINEAGE_KEY`; `SearchTool`, `ReadDocumentTool`, `AgentSession` and `AgentDispositionWiring`
import them.

The literals are NOT hidden behind the constants on the TS side — a wire key has to be a
literal somewhere. What closes the seam is the 865 pattern already in the tree
(`AgentSessionGroundingTest:562` asserts `assertEquals("grounding", GROUNDING_KEY, "the key
the FE reads")`): a per-key conformance test that pins the constant's *value* to the literal
its consumer holds, names that consumer, and asserts a producer emits it and a consumer
reads it.

**Deliberately not centralised**: `query`, `resultCount`, `searchMode`. They are read only
by `toolSearchCard.ts` and `AgentInteractionMapper`, have no second Java producer, and
adding four more constants for a one-producer key is apparatus. If a second producer
appears, they join the set.

### 2.4 `RootsView` (finding 4)

`AgentToolPaths.RootsView` — one small type owning three facts:

- `roots()`: never throws, never returns null (a throwing or null supplier yields an empty
  list, WARN-once). This is the single degrade behaviour replacing today's four.
- `validate(path, paramName)`: null when valid *or* when roots are unknown/empty
  (degrade-open, preserved verbatim from today's three wrappers); otherwise the existing
  message from `validateAgainstRoots`.
- `resolveRelative(path)`: the one relative→absolute algorithm
  (`AgentToolPaths.resolveRelativePath`), returning null when no root matches.

`AgentToolFactory.assemble` builds ONE `RootsView` and hands it to search, browse, ingest,
read-document and file-operations. Supplier-taking constructors remain as thin delegating
overloads so existing tests do not churn; internally every tool holds a `RootsView`.

**Orphaned by this**: `SearchTool.validatePathPrefix`, `BrowseTool.validateParentPath`,
`BrowseTool.resolveRelativeParent`, `ReadDocumentTool.roots()`, `ReadDocumentTool.validatePath`,
and `IngestTool.resolvePath`'s second resolution algorithm — including its fall-through to
`p.toAbsolutePath()` (the JVM cwd). All deleted here.

`IngestTool.resolvePath` becomes: absolute ⇒ normalize; else `resolveRelative` and, only if
that misses, the existing existence-probe over roots. The cwd fall-through is removed —
resolving a relative path against the *process working directory* is never what the model
meant, and it is the one behaviour in this cluster that can address a file outside every
indexed root.

**Boundary with 875**: 875 owns path *semantics* (fail-closed, realpath). This tempdoc
changes only *where the code lives* and preserves whatever semantics `main` has at merge
time. The one exception is the cwd fall-through, which is a deletion of a fork, not a
semantic tightening of a live rule — it is called out here so 875 can see it.

### 2.5 `ToolArgs` (finding 5)

One helper in `io.justsearch.agent.tools`, used by all five tools:

- `ToolArgs.parse(String)` — the single `ObjectMapper` for the tool package (five die).
- `intArg(node, name, fallback, min, max)` — accepts a JSON number *or* a numeric string
  (so `"5"` and `"3000"` both work), clamps to `[min, max]`, and throws a typed
  `ToolArgs.BadArgument` naming the field on a non-numeric value. Loud, not silent: today
  `offset_chars:"3000"` silently restarts a read at page 0.
- `boolArg`, `stringArg` with the same null/JSON-null discipline as
  `FileOperationsTool.textField` (which is deleted in favour of it).

**Coordination with 878**: 878 owns read-document *semantics*. This tempdoc supplies the
helper and adopts it in `ReadDocumentTool`, which incidentally fixes the string-offset
silent-restart. That fix is stated here so 878 can build on it rather than re-derive it.

**Orphaned**: `ReadDocumentTool.intArg`, `SearchTool.boolField`,
`FileOperationsTool.textField`, and the five per-tool `MAPPER` fields.

### 2.6 `AgentToolErrors` (finding 6)

One classifier turning a caught exception into an `OperationResult` that uses the typed
fields `OperationResult` has carried unused since slice 3a-2-c:

| cause | errorCode | retryable | log |
|---|---|---|---|
| `JacksonException` / `ToolArgs.BadArgument` | `BAD_REQUEST` | false | WARN, no stack trace |
| `TimeoutException`, gRPC `StatusRuntimeException`, `CompletionException` wrapping either | `WORKER_UNAVAILABLE` | true | WARN with cause class + message |
| anything else | `INTERNAL` | false | ERROR with stack trace |

The point of the split is the first row: a model that emits malformed JSON currently writes
a stack trace into the Head log on every attempt, which makes a *model* mistake look like a
*system* fault in the diagnostics export.

`ReadDocumentTool` additionally reads `DocumentSlice.error` and reports a Worker failure as
a Worker failure instead of "Document not found in the index" — the same class of lie, one
layer down.

**Handler-side sprawl (the 19 `ObjectMapper`s and 9 `"Invalid args: "` copies)**: those
handlers are *not* agent tools — they are UI/system operations (`AddWatchedRoot`,
`ImportAiPack`, `SwitchInferenceMode`, …) that happen to share the `OperationHandler`
contract. They still hold one fact in 19 places, so this PR gives them one holder in their
own package (`HandlerJson.MAPPER` + `HandlerJson.invalidArgs(e)`) and adopts it at all 19 +
9 sites. Zero behaviour change; it is a rename, and it is verifiable by grep.

### 2.7 One path convention: root-relative is accepted everywhere (finding 7)

Two candidate conventions:

- (a) Make the prose match the code: root-relative is the emitted form, every consumer
  resolves it, and the prompt says so.
- (b) Make the code match the prose: browse emits absolute paths.

**The first draft of this design chose (b) and was wrong.** `BrowseTool.toRelativePath` is
not an accident: tempdoc 227 §A.6 round 6 (2026-02-23) introduced it deliberately, together
with `resolveRelativeParent`, off a measured live battery — absolute paths leaking into a
handoff reason produced an Organizer non-compliance failure, and the same round removed the
prompt's "NOT browse_folders" prohibition *because* browse had become relative-safe.
Reversing a measured decision on the strength of a prose mismatch, with no measurement of
our own, is the mistake `interrogate-results` exists to prevent. Recorded here rather than
silently dropped, because "the design considered X and rejected it for reason Y" is the part
that stops the next agent re-deriving it.

**So: (a).** It is also the convention the system already has almost everywhere —
`SearchTool` (`path_prefix`), `ReadDocumentTool` (`path`) and `IngestTool` (`paths`) all
resolve relative→absolute against the indexed roots before validating. `FileOperationsTool`
is the ONE tool that does not: `parseOperations` does a bare `Path.of(destStr)`, so a
root-relative path echoed back from a browse result fails sandboxing with
`DEST_NOT_SANDBOXED`. That is the actual defect, and it is one tool wide, not a convention
question.

The work:

1. `FileOperationsTool` gains the same `RootsView.resolveRelative` pre-pass the other three
   tools have, applied to `source` and `destination` in `parseOperations`. A path that is
   already absolute is untouched; a root-relative one resolves; one that matches no root is
   passed through unchanged so `FileOperationExecutor`'s sandbox check still rejects it with
   its existing message (degrade-open on resolution, fail-closed on sandboxing — the split
   the other tools already use).
2. The prose is corrected to describe the real convention, in the three places that
   currently misdescribe it:
   - `AgentPromptComposer.DEFAULT_SYSTEM_PROMPT` — "All file paths in this system are
     absolute paths" becomes a statement of what is actually true: paths from
     `core_browse_folders` and search results can be passed straight back to any tool.
     Keep it SHORT; this string is in every agent prompt and 227 round 6 showed prompt
     wording here is load-bearing and measurable. The change must remove a falsehood
     without adding length.
   - `SearchTool`'s empty-result hint ("Use an absolute path from browse_folders…") — the
     hint fires when `path_prefix` matched no root, so it should name the root NAMES the
     model can use, exactly as `BrowseTool`'s own no-folders hint already does.
   - `core.read-document`'s catalog `Interface` description says "Absolute path … as
     returned by `core_browse_folders`" — drop "Absolute", since the tool resolves
     root-relative input and browse returns root-relative output.
3. `BrowseTool.toRelativePath` STAYS. It is renamed only if the rename is free.

**Test**: the round-trip nobody was asserting — take a path as `BrowseTool` emits it, feed
it to `SearchTool` as `path_prefix`, to `ReadDocumentTool` as `path`, and to
`FileOperationsTool` as `destination`, and assert all four agree it addresses the same file
inside the sandbox. That single test binds the convention across all four tools, which is
what makes it one authority rather than four agreeing by luck.

### 2.8 `AgentTimeouts` (finding 8)

One holder in `io.justsearch.agent` naming all nine durations in ONE unit (milliseconds)
with the existing system-property/config overrides preserved:

`llmCallMs`, `sessionAttachMs`, `approvalGateMs`, `virtualToolMs`, `contextGateMs`,
`budgetGateMs`, `citationMatchMs`, `toolFetchMs`, `fileOpConflictToleranceMs`.

Every current site reads from it; the stale "aligns with INLINE_CONFIRM approval-gate
timeout" comment is corrected to state the actual relationship (the virtual-tool wait is
deliberately shorter than the approval gate: an FE that never answers a virtual tool should
not hold the loop for five minutes).

`toolFetchMs` is then applied to the three unguarded fetches — `SearchTool.searchCallback`,
`BrowseTool.browseCallback`/`filesCallback`, `IngestTool.scanRootCallback` — via one
`AgentTimeouts.call(label, callable)` that runs the synchronous callback on a virtual
thread and abandons it on timeout, returning the `WORKER_UNAVAILABLE` retryable failure
from §2.6. Java 25 toolchain, so an abandoned virtual thread is cheap and self-cleaning.

This is the one finding that adds behaviour rather than removing duplication, and it is
justified by the same fact the census names: three of four tool fetches can block the agent
loop thread forever on an unresponsive Worker, and one cannot. Two behaviours, one
question — the definition of a split authority.

### 2.9 The MCP "bypass" is not a bypass (finding 9) — refuted, with the boundary recorded

The census asks to close `McpToolSurface.callSearch`'s bypass of `SearchTool` by sharing
`SearchTool.buildSearchEvidence`. Reading both, this is the wrong move and this tempdoc
declines it, with reasons rather than a deferral:

1. **There is no duplicated engine call to close.** Both paths call
   `KnowledgeHttpApiAdapter.search` — that IS the shared authority, and it is already
   single. What differs is what each *consumer* asks for: MCP sends facets, `query_syntax`,
   `detail`, a 50-cap limit and a delivery-governor budget; the delegate sends a 3-result
   default, an injected `docIds` scope and a 4000-char context cap. Routing MCP through
   `SearchTool` would impose the delegate's context economics on an external client — the
   exact coupling tempdoc 770 §4 declined when it withdrew `fetch` from the MCP surface.
2. **The two structured payloads share almost no fields.** `SearchTool.buildSearchEvidence`
   emits `title/path/excerpt/line/parentDocId/chunkIndex/startLine/endLine/headingText`
   plus a `feedbackFeatures` channel for the citation-disposition loop.
   `McpEvidenceProjection.searchEvidence` emits `id/path/title/score/matchedTerms/
   matchedFields/excerpts/trace/legScores/continuation/entityCarriage` plus response-level
   `searchTrace/degradation/hints/facets/coverage/truncated/facetsTruncated/appliedFilters`.
   A shared producer would be a union type neither consumer wants — creating a fork where
   today there are two projections of one canonical record.
3. **The register already governs this.** `McpEvidenceProjection` is registered as a
   `projection` surface in `governance/execution-surfaces.v1.json` over the canonical
   `SearchTrace`, with a reflective-totality guard (`McpEvidenceProjectionTest`). The
   projection-vs-fork question the census raises has already been answered for this file,
   in the register, in favour of projection.

**The boundary, recorded**: if a *third* consumer of search evidence appears, or if the two
projections start disagreeing about a field they both carry (`path`, `title`, `excerpt`
selection), that is the trigger to extract a shared hit-identity projection in `app-api`
next to `KnowledgeSearchResponse` — not before. Nothing in this PR touches
`McpToolSurface`, `McpSearchResultFormatter` or `McpEvidenceProjection`.

### 2.10 Stale prose sweep (finding 10)

`AgentToolsOperationCatalog:260-265` (the false "does not yet read list_files" note) and
`:137-138` (line refs that have drifted) are corrected. `AgentToolHandlers:348-349`'s
hand-written tool list is replaced by the registered refs it actually registered.
`docs/explanation/22-agent-system-architecture.md`'s tool table gains `core_remember` and
`core_navigate_to_surface` and loses the "delete" claim (`FileOperation.OpType` is
MOVE/RENAME/MKDIR/COPY). `ServicePhase` needs no change — 868 already fixed it.

### 2.11 Reach of this design

**The principle**: *one authority per fact; a second speller must be a projection, not a
peer.* This is not new — `governance/execution-surfaces.v1.json` states it for search
execution, 832 states it for construction, 865 §7.5 states it for the carrier line. What
877 adds is the observation that the **agent tool surface has no register**, so the same
rule has to be carried by per-fact tests instead.

**Where else it applies, unbuilt**: the MCP tool surface declares its own schemas
independently of the operation catalog (655 already caught one drift between them and fixed
it by hand). That is the same shape as finding 1 one layer out. It is NOT built here —
naming it is enough until a second drift appears.

**What would show it earning its keep**: a future change that adds a key to a tool and is
caught by `AgentToolCatalogContractTest` before review, or a key rename caught by a
structuredData conformance test. Concretely measurable as "a red test in a PR that would
otherwise have shipped a silent no-op key".

**Retirement condition**: if the per-fact tests never go red across, say, ten subsequent
agent-tool PRs while the same PRs keep introducing drift that review catches instead, the
tests are the wrong instrument and should be replaced by a register + gate (or deleted). A
principle that never fires is apparatus.

---

## 3. Plan (phase 4)

Sequenced so each step is independently green. `W` items are delegated to pinned workers in
bounded chunks; the orchestrator writes briefs, judges evidence, and owns the final
critical-analysis pass.

| # | Work | Files (approx) | Verification |
|---|---|---|---|
| W1 | §2.1 delete `PARAMETER_SCHEMA` ×4 + `parameterSchema()` ×4 + 7 test sites; delete `MAX_EXPANDED_FILES` + guard + `IngestToolTest:219-226`; promote file-op item schema/`conflict_strategy`/`explanation` and browse `max_folders`/`max_files` into the catalog `Interface`; add `AgentToolCatalogContractTest` | 4 tools, catalog, 4 tests, 1 new test | `:modules:app-agent:test`, `:modules:app-services:test` |
| W2 | §2.2 one cap accessor; emitted-length budget in `formatResults`; cap-fit test | `SearchTool`, `SearchToolTest` | `:modules:app-agent:test` |
| W3 | §2.3 four key constants + adoption + per-key conformance tests | `OperationResult`, `SearchTool`, `ReadDocumentTool`, `AgentSession`, `AgentDispositionWiring`, 1 new test | `:modules:app-agent:test`, `:modules:app-services:test` |
| W4 | §2.4 `RootsView` + adoption in 5 tools + factory; delete 6 forks incl. the cwd fall-through | `AgentToolPaths`, 5 tools, `AgentToolFactory`, tests | `:modules:app-agent:test`, `:modules:app-services:test` |
| W5 | §2.5 `ToolArgs` + adoption in 5 tools; delete 3 private helpers + 5 mappers | `ToolArgs` (new), 5 tools, tests | `:modules:app-agent:test` |
| W6 | §2.6 `AgentToolErrors` + adoption; `DocumentSlice.error` honoured; `HandlerJson` for the 19+9 handler sites | `AgentToolErrors` (new), 5 tools, `HandlerJson` (new) + 19 handlers | `:modules:app-agent:test`, `:modules:app-services:test` |
| W7 | §2.7 file-ops `resolveRelative` pre-pass; prose corrected in prompt / search hint / read-document schema; four-tool round-trip test | `FileOperationsTool`, `AgentPromptComposer`, `SearchTool`, catalog, tests | `:modules:app-agent:test` |
| W8 | §2.8 `AgentTimeouts` + adoption at 9 sites; `toolFetchMs` applied to 3 unguarded fetches; stale comment fixed | `AgentTimeouts` (new), 7 files | `:modules:app-agent:test` |
| W9 | §2.10 stale prose sweep incl. `docs/explanation/22` | catalog, `AgentToolHandlers`, 1 doc | docs regen check |

Then: full `spotlessApply` → `build -x test` → `test`, the pre-merge checks for the subjects
touched (`--gate operation-surface` for the catalog `Interface` changes;
`check-live-witness` is not implicated), a critical-analysis pass, and ONE independent
refute-first opus reviewer on the diff.

### 3.1 Findings discovered during implementation

Two facts turned up mid-implementation that the census did not have, and both changed a
decision. Recorded because a design that is only right by luck is not right.

**(a) The catalog `Interface` is ENFORCED, not merely advertised.**
`OperationExecutorImpl:327-339` validates every `argumentsJson` against
`op.intf().inputs()` with a full DRAFT-2020-12 validator
(`OperationInputSchemaValidator`) BEFORE the handler is invoked, returning a typed
`BAD_REQUEST` and never calling the tool. That makes §2.1's promotion double-duty: what the
model is shown is also what it is held to. Two consequences:

- Promoting a *constraint* keyword (`maximum`, `maxItems`) converts a tool's forgiving
  clamp into a hard rejection. `BrowseTool` clamps `max_folders` to 200; declaring
  `"maximum":200` would instead fail the whole call. `FileOperationsTool` answers an
  oversized batch with "Too many operations: N exceeds limit of 50. Split into smaller
  batches."; declaring `"maxItems":50` would replace that remedy with a generic validation
  string. So the promoted schemas declare `default`, `enum` and `description` — which shape
  what the model *generates* — but not the numeric bounds, which the tools already enforce
  more helpfully. `core.search-index`'s existing `limit` declaration was already this shape;
  the promotion follows it rather than inventing a second convention.
- Item-level `required` on file-operations is `["op"]` only. `parseOperations` accepts
  `path` as an alias for `destination` because small models emit it; requiring
  `destination` in the schema would reject those calls before the alias could resolve.
  The destination is still required — by `parseOperations`, which names the offending
  operation index.
- `docIds` survives because no schema sets `additionalProperties:false`. Worth stating,
  since setting it would silently break every scoped run.

**(b) The rule the promotions follow, derived from (a).** *Declare the shape, describe the
constraint, enforce in the tool.* A `type`/`required` keyword is shape and is safe to
declare. An `enum`/`maximum`/`maxItems` keyword is a value constraint, and declaring one on
this schema does two harmful things at once: it replaces the tool's specific, actionable
message with a generic validator string, and it can NARROW behaviour the tool deliberately
accepts. The worked case, caught during implementation and nearly shipped:

> `FileOperationsTool.parseOperations` upper-cases `op`, because small local models emit
> `"mkdir"` — `FileOperationsToolTest#executeCaseInsensitiveOpType` pins that as intended
> behaviour. Declaring `"enum":["MOVE","RENAME","MKDIR","COPY"]` would reject those calls at
> the dispatch boundary **while that unit test stayed green**, because every tool unit test
> calls `execute(json)` directly and never passes through the validator. A live regression
> with a green suite over it — `static-green ≠ live-working`, produced by the very change
> meant to remove a drift. The allowed values now ride the `description`, which is what the
> model reads anyway, and `AgentToolCatalogContractTest` asserts both that every
> `FileOperation.OpType` is named there AND that no `enum` keyword exists, so the
> "tightening" cannot be reapplied without reading why. Same for `conflict_strategy`.

**(c) Finding 5's headline bug is not reachable through the agent loop.** The census reports
`offset_chars:"3000"` silently restarting a read at page 0. `ReadDocumentTool.intArg`
genuinely does fall back to 0 on a string — but `core.read-document` already declared
`offset_chars` as `{"type":"integer"}`, so the pre-dispatch validator rejects the string
before the tool sees it. The observable defect is therefore narrower than reported: the two
helpers disagree, and the disagreement bites only off the Operation path (direct calls,
tests, and any future caller that does not go through `OperationExecutorImpl`). The
unification is still right — one answer to one question, and defence in depth behind a
validator that a non-Operation caller bypasses — but the tempdoc should not claim a live
paging bug it cannot demonstrate. Recorded because "the fix worked" and "the bug was real"
are separate claims, and only the first is established here.

**(d) `BrowseTool.toRelativePath` is a measured decision, not an accident** — see §2.7.
This is what turned finding 7 from "browse is wrong" into "the prose is wrong".

### 3.2 What shipped, per census finding

| # | Finding | Outcome |
|---|---|---|
| 1 | Dead `PARAMETER_SCHEMA` ×4 | **Done.** All four constants + accessors + the 7 test call sites deleted; the three SUBSTANTIVE assertions relocated to `AgentToolCatalogContractTest` (schema-parses, `maxItems`↔`MAX_BATCH_SIZE`, ingest advertises `collection`) rather than dropped. File-op item shape + `conflict_strategy` + `explanation` and browse `max_folders`/`max_files` promoted into the catalog `Interface`. `mode`/`pipeline` settled as delete-the-prose, keep-the-code, with the authorship of every key stated once in `SearchTool.execute`. `MAX_EXPANDED_FILES` + its unreachable guard + the tautological test deleted. |
| 2 | Two readers of `agent.maxToolResultChars` | See §3.3. |
| 3 | Bilateral `structuredData` literals | **Done.** Four constants beside `GROUNDING_KEY`; `AgentSession`, `AgentDispositionWiring` and `withLineage` adopt them; `AgentToolStructuredDataKeysTest` pins each key's value and checks the consumer that cannot import it. |
| 4 | Roots access forked 13 ways | See §3.3. |
| 5 | Two arg-parsing helpers | `ToolArgs` + tests shipped; adoption in §3.3. Note the correction in §3.1(c). |
| 6 | Error handling | `AgentToolErrors` + tests shipped (adoption in §3.3). The handler-side half is **done**: `HandlerJson` holds one mapper for 19 handlers and one `invalidArgs` for the 9 byte-identical sites, with a regression test that fails if a per-handler mapper reappears. `NavigateToSurfaceHandler` and four `"Invalid arguments JSON: "` handlers were deliberately left alone — different text, different shape, not the same fact. |
| 7 | Path convention | Redesigned (§2.7); see §3.3. |
| 8 | Nine timeout constants | **Done for the holder.** `AgentTimeouts` declares all nine in one unit; the five non-tool sites adopt it; `AgentLlmCaller`'s "5 minutes" message is derived from the constant it waits on; the 10×-stale "aligns with INLINE_CONFIRM" comment is replaced with the real relationship, and `AgentTimeoutsTest` asserts `virtualToolMs() < approvalGateMs()` — the assertion that would have caught it. Tool-side adoption in §3.3. |
| 9 | MCP "bypass" | **Refuted, not deferred** (§2.9). No MCP file touched. |
| 10 | Stale comments/docs | **Done.** The false "handler does not yet read list_files" note corrected; drifted line-number citations replaced with method names; `AgentToolHandlers`' hand-listed completion log derived from what it actually registered; `docs/explanation/22`'s tool table completed (7 rows) with the non-existent "delete" capability removed and the approval paragraph corrected. `ServicePhase` needed no change — 868 had already fixed it. |

### 3.3 Deliberately not done

**Not doing** (with reasons, per §2.9 and §2.3): the MCP evidence unification (refuted —
two governed projections of one canonical record, not a fork); `query`/`resultCount`/
`searchMode` constants (one producer each); a tool-definition DSL (§1.2); a governance gate
for tool-schema coverage (§1.2 — the unit test is the cheaper instrument, with a stated
retirement condition).
