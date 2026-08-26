---
status: IMPLEMENTING
created: 2026-08-26
updated: 2026-08-26
owner_session: f6617483
follows:
  - 868-agent-tool-capabilities.md §C.4b, §D.2
  - 865-agent-tool-read-grounding.md §7.5
  - 859-sv3-live-findings.md §D
  - 836 §1.4
---

# 878 — Agent run honesty: terminals, compaction, paging

**Thesis (working):** when an agent run ends, compacts, or pages, the user and the
model are told something that is *not what happened*. A step-limit terminal returns
an empty answer instead of a partial one; a layer-3 context strip deletes evidence
without leaving a mark, so the model re-reads it forever; a compaction drops sources
from the prompt without recording them in the inclusion ledger, so the receipt says
they were present; the wire shows a tool output larger than the one the model saw.
Each is a distinct honesty defect in the same run lifecycle.

## Findings handed over

*(Independent review + live runs 2026-08-26. Line references are from the review's
tree and MUST be re-verified before being relied on — see §A.)*

1. **HIGH — `MAX_ITERATIONS` never attempts synthesis.** `AgentLoopService.java:~594-600` emits `AgentDone.ofDisposition("", …)` with NO LLM call; the budget-edge path (`AgentStepRunner.java:~520-544`) calls `llmCaller.attemptBudgetEdgeFinalize` (a real no-tools synthesis, `AgentLlmCaller.java:~78-115`). This explains 8/8 empty answers on MAX_ITERATIONS in the recorded runs. Remedy: run the same finalize (gated like the budget path on `hasSuccessfulToolResult`), keep the MAX_ITERATIONS disposition so truncation is disclosed. FE: the "Cut short at the step limit" copy should then show the partial answer.
2. **HIGH — Layer-3 strip deletes rather than substitutes.** `ToolResultCarrier.STRIPPABLE_LINE` includes `Read:`; `AgentContextCompressor.stripSearchExcerpts` (`~200-210`) deletes the line; a read result then becomes a bare `[read] … More: offset_chars=N` header (below `minChars`, so no `[compressed-tool-output` marker), which advertises a follow-up call — observed live as the re-read loop. Layer 2 leaves a marker; Layer 3 leaves none. Remedy: substitute a distinct label (e.g. `    Elided: …`) that deliberately does NOT match `CARRIER_LINE`, so `carriesText()` and the receipt are unchanged; add the fourth label to `ToolResultCarrier`.
3. **HIGH — `compactOlderTurns` bypasses the inclusion ledger.** `AgentSession.compactOlderTurns` (`~897-919`) clears messages without adding their `tool_call_id`s to `carriersWithTextRemoved`; `receiptFor` classifies only present messages → dropped sources read `ABSENT` and render as ordinary evidence. The javadoc contract at `~562-565` ("or has left the prompt entirely") is false in code. Fix inside the synchronized method; test.
4. **MEDIUM-HIGH — the wire shows more than the model saw.** `AgentEventPayloads.toolCompletedPayload:~288-298` emits the full untruncated output; the truncate at `AgentStepRunner:~963` happens after. Add `outputCharsToModel`/`truncatedForModel` to the payload; FE tool cards should say so. Also `AgentRunStore` checkpoints post-truncation messages while `events.ndjson` is pre-truncation and neither labels which — record what "what the model saw" should mean and make at least one store answer it.
5. **MEDIUM — virtual-tool seam skips `withLineage`** (`AgentStepRunner:~1174-1192`) though `OperationResult:~83-86` calls the stamp "single authoritative"; FE default is fail-open (`toolOutputLineage.ts:~20-22` → no frame). Stamp it; extend `AgentGroundingSeamAuditTest` with an existence rule for lineage (it has uniqueness+existence for grounding). `RunChannelPolicy.java:~91-96` discriminates evidence on non-empty structuredData, which `withLineage` now makes always-true — discriminate on the bulk keys.
6. **MEDIUM — budget projection is blind to tool schemas.** `TokenEndpointOps.applyTemplate:~121-124` builds from `messages` only; the real call passes `tools` (`AgentLlmCaller:~207-209`). The documented ~40% undercount (`AgentStepRunner:~284-289`) has this cause. Thread `tools` into `applyTemplate`; keep the `max(tokens, lastReportedPromptTokens)` mitigation.
7. **MEDIUM — paging economics** (868 §C.4b): with n_ctx 4096, the FE's fixed `DEFAULT_MAX_ITERATIONS=10` (`AgentSessionController.ts`), and a 4B model, "read three documents" pages to exhaustion. Levers to design and (where product-safe) implement: `total_chars` in the read header so the model can budget; effort-scaled iteration cap (the effort rung already exists — `AgentBudgetPolicy`); a summary-mode default of one page per document; per-document progress vocabulary for the budget gate (859 §2.3 "1 of 3 files"). `ReadDocumentTool.intArg` requires `isNumber()` so `offset_chars:"3000"` silently restarts at page 0 — coerce/reject loudly (workstream 877 is building a shared `ToolArgs` helper; if it lands first, adopt it, else fix locally with the same contract). `ReadDocumentTool` ignores `DocumentSlice.error` → Worker failures reported as "not found" (877 also touches this via the error helper — coordinate by merging origin/main before editing `ReadDocumentTool`).
8. **LOW — `acquisition` has no enum authority** (`AgentEvent.java:~201-220`): its siblings `contextInclusion`/`citationScorer`/`disposition` each project a named enum across the `app-agent-api` boundary as a String; give `acquisition` one and make the record constants a projection. Also stale: `AgentSession:~435-437` claims `ContextCitation` clamps `-1` to `0` — `DocumentService.CHUNK_INDEX_ABSENT` now preserves it; fix the comment (the blank-excerpt guard stays, with the true reason).
9. **LOW — `OutputLineage.CORPUS_READERS`** is a fourth private copy of op ids (fail-open to RUNTIME); derive from a declaration category or add a test binding it to the catalog's corpus-reading ops.

**Live verification the owning worker cannot run:** the rank-1 prompt through the FE
at the default cap should now complete or at least return a partial answer on
MAX_ITERATIONS; the orchestrator will run it.

**Acceptance:** regression tests for 1–6 that fail on main; ui-web tests for any FE
copy; full suite green.

---

## §A. Re-verification of the handed-over line references (2026-08-26, this tree)

Base: `666422b6`, which contains `origin/main`'s tip `bd36421c`. Every claim below was read at
its source before any design was written.

| # | Claim | Verdict | Where it actually is, in this tree |
|---|---|---|---|
| 1 | ceiling emits `ofDisposition("")` with no LLM call | **confirmed** | `AgentLoopService.java:585-603`; the budget-edge sibling at `AgentStepRunner.java:520-544`, the finalize itself at `AgentLlmCaller.java:94-115` |
| 2 | Layer-3 deletes the `Read:` line | **confirmed** | `AgentContextCompressor.stripSearchExcerpts:200-210` (`replaceAll("")`); label set in `ToolResultCarrier` (`STRIPPABLE_LINE` includes `Read`) |
| 3 | `compactOlderTurns` bypasses the ledger | **confirmed** | `AgentSession.java:897-919` clears `messages.subList(...)` and touches neither `carriersWithTextRemoved` (declared `:126`) nor `compressionObserved`; the false contract is the parenthetical at `:562-565` |
| 4 | wire carries the untruncated output | **confirmed** | `AgentEventPayloads.toolCompletedPayload:288-297` puts `e.result().message()`; the Layer-2 cut happens after, at `AgentStepRunner.java:963` |
| 5 | virtual seam skips `withLineage` | **confirmed** | `AgentStepRunner.handleVirtualToolCall:1147-1203` mints + stamps grounding but never lineage; main dispatch stamps at `:933-940`. `RunChannelPolicy.java:88-99` keys evidence on `containsKey("structuredData")` |
| 6 | projection is schema-blind | **confirmed** | `TokenEndpointOps.applyTemplate:109-124` posts `messages` + `add_generation_prompt` only; the real call passes `tools` (`AgentLlmCaller:207-209`). Note the per-iteration `tools` list is already built at `AgentStepRunner:195-209`, *before* the projection at `:244` — so threading it needs no re-ordering |
| 7 | `intArg` requires `isNumber()`; `DocumentSlice.error` ignored | **confirmed** | `ReadDocumentTool.java:300-304` and `:162-164` (`!slice.found()` → `notFound`, discarding `error`). `DocumentSlice` carries `error` (`DocumentService.java:725-740`) but **no total length** — the Worker computes `totalLen` at `GrpcSearchService.java:685` and does not return it, so `total_chars` needs one new proto field |
| 8 | `acquisition` has no enum; `ContextCitation` comment stale | **confirmed** | `AgentEvent.java:181-220` (String + two constants, blank ⇒ `retrieved`); the stale comment is at `AgentSession.java:434-437`, falsified by `ContextCitation`'s `Math.max(CHUNK_INDEX_ABSENT, chunkIndex)` at `DocumentService.java:283` |
| 9 | `CORPUS_READERS` is an unbound private copy | **confirmed** | `OutputLineage.java:44-55` |

Two corrections to the handed-over text, both in the same direction (the defect is slightly
different from the description, and the difference changes the fix):

- **(4)** the truncation is at `AgentStepRunner:963`, but there are **three** `ToolExecutionCompleted`
  emit sites, not one — main dispatch (`:957`), handoff confirmation (`:775`) and the virtual
  seam (`:1192`) — and all three truncate on append. A stamp added at one site alone would leave
  the FE unable to distinguish "not truncated" from "not measured".
- **(5)** `structuredData` is *already* non-empty for every successful main-dispatch tool result,
  because `withLineage` has stamped it since 577. So `RunChannelPolicy`'s discriminator is
  already degraded on `main`; extending the stamp to the virtual seam widens an existing hole
  rather than opening a new one. The keying is per-`callId`, so nothing collapses — the cost is
  retention, not correctness.

---

## §T. Theorization (2026-08-26)

### §T.1 The nine findings are one class, and the repo already has the vocabulary for it

Read separately, findings 1–5 look like five unrelated bugs in five files. Read together they
are one shape:

> **A layer of the run destroys information, and something downstream keeps speaking as if it
> had not.**

Four destroyers, in the order a run meets them:

| # | Destroyer | What it removes | Who is misled |
|---|---|---|---|
| 4 | Layer-2 `truncate` | the tail of a tool output | the READER (the wire shows the untruncated output) |
| 2 | Layer-3 strip | a read page's text | the MODEL (a bare `More:` header invites another call) |
| 3 | `compactOlderTurns` | whole messages | the READER (the receipt reports the sources as `ABSENT`) |
| 1 | the iteration ceiling | the synthesis itself | BOTH (empty answer, no partial findings) |

And the remedy is not new here — this codebase **already** practises it in four places:

- `AgentContextCompressor.truncate` appends `[... truncated, N chars omitted]`.
- `compressToolOutput` stamps `[compressed-tool-output originalChars=… keptChars=…]`.
- `AgentSession.inclusionFor` reports `ContextInclusion.dropped()` on the wire.
- `TerminalDisposition` says *why* a run stopped, independently of the model's text.

So this workstream is not inventing a discipline; it is **finishing one**. That reframing matters
for scope: each fix should look like the mark that already exists one layer over, not like a new
mechanism.

**Candidate principle — "elision leaves a scar, addressed to a named consumer."** Every layer
that removes information from a run leaves a mark on the artifact it removed from, and the mark
is legible to the consumer that would otherwise misread the remainder. The corollary is the part
this repo keeps rediscovering: **there are TWO consumers, the model and the reader**, they read
different artifacts (the prompt vs the wire), and a mark aimed at one is not a mark for the
other. Finding 2 is a missing model-facing scar; finding 3 a missing reader-facing one;
finding 4 is a reader-facing statement *about* a model-facing elision. Naming both consumers
explicitly is what stops the next fix from being made in the wrong artifact.

### §T.2 Finding 1 reframed: a ceiling is a deadline, not a wall

The budget wall already honours a rule the iteration ceiling does not: *before you stop the run,
give the model one no-tools call to write down what it has.* `AgentStepRunner` does this
(`attemptBudgetEdgeFinalize`); `AgentLoopService`'s ceiling emits `AgentDone.ofDisposition("")`
and returns.

The generalization worth stating: **an involuntary terminal owes the run one synthesis attempt;
a voluntary one does not.** Cancel is the deliberate exception — the owner decision of
2026-08-26 (neutral, answerless cancel) is a *choice* that a run the reader stopped should not
produce an answer, and it must not be swept up by this principle. Involuntary terminals are the
ones the run walked into: the budget wall (done), the iteration ceiling (finding 1), and any
future exhaustion terminal.

Three ways to implement it, and the difference is really *what the cap means*:

- **(a) Post-wall grace call.** Reach the ceiling, then run the same no-tools finalize, then emit
  `MAX_ITERATIONS` regardless of whether it produced text. Smallest blast radius; reuses the
  instruction, the telemetry counter, and the disposition machinery verbatim; symmetric with the
  budget path, which is itself an argument — two terminals with one mechanism cannot drift.
- **(b) Reserved final step.** At iteration `max-1`, force `tools=[]` so the last step *is* the
  answer. The cap then means "N steps, the last of which is the answer". Cleaner semantics, but
  it silently costs a step of real work, and it changes what every existing `maxIterations`
  number means — a behaviour change to every caller, for a semantic gain.
- **(c) Leave it; make the FE render the emptiness honestly.** Rejected: the run has the
  material. Refusing to spend one more call throws away everything the run already paid for.

(a) is the direction. (b) is worth recording as the more ambitious follow-up, not as a rejected
idea: if the cap ever becomes effort-scaled (§T.6), reserving its last step becomes cheap.

**What the fix must NOT claim.** 859 §7 watched a compact model answer a finalize instruction
with a confidently formatted, content-free non-answer. So the honest claim is "the ceiling now
*attempts* synthesis and discloses the truncation either way", never "the ceiling no longer
returns empty answers". The fail-closed guarantee stays the disposition on the wire, which is
written independently of the model's text — exactly as 859 §D §2.6 built it.

**Fail-open, deliberately:** if the finalize call throws, or the run is also out of budget, the
path must land on exactly today's behaviour (empty answer + `MAX_ITERATIONS`). A new terminal
must not be able to fail worse than the terminal it replaces.

### §T.3 Finding 2: the scar has to change behaviour, not just record history

A substituted `Elided:` line is a scar for the reader-of-the-prompt — the model. But a scar the
model ignores is decoration. So the wording is load-bearing in a way the other marks' wording is
not: it has to tell the model *that re-reading will not bring the text back*.

There is a causal question here I cannot settle from code, and it decides how much weight this
fix carries. The observed symptom was a read loop. Two hypotheses:

- **H1 — the model re-reads to recover lost text.** The strip removes the page; the header stays;
  the model calls `core_read_document` again. Note the loop guard (`wouldExceedLoopThreshold`,
  3 identical calls) *would* catch a literal identical re-read, so H1 requires the model to vary
  its arguments.
- **H2 — the model pages forward forever.** The `More: … offset_chars=N` header is an explicit
  instruction to continue, the document is ~27 KB against a 3000-char page, and nothing tells the
  model how much is left. Each call has different arguments, so the loop guard never fires.

H2 fits the loop guard's silence better, and it points at §T.6's `total_chars` and progress
vocabulary as the *primary* remedy, with the scar as the secondary. Both fixes are worth making
regardless — H1 and H2 are not exclusive, and each remedy is honest on its own terms — but the
tempdoc must not claim the scar "fixes the read loop" without a live run distinguishing them.
(`interrogate-results`: the fix that matches the story is the one most likely to be adopted for
the wrong reason.)

Design constraint that is easy to get wrong: the scar must match **neither** `CARRIER_LINE` (or
the receipt would report stripped text as intact) **nor** `STRIPPABLE_LINE` (or a second pass
would strip the scar and the elision would go silent again after all). Both are properties of
`ToolResultCarrier`'s patterns, which is precisely why the label belongs in `ToolResultCarrier`
and not in the compressor: that class exists to hold exactly this kind of coupling in one file.

### §T.4 Finding 3: prose that ran ahead of code

`inclusionFor`'s javadoc already states the contract — "every tool message that carried this
source has lost its `Excerpt:` lines *(or has left the prompt entirely)*". The parenthetical is
true of the design and false of the code. This is worth naming as its own small pattern, because
it is the *hardest* kind of drift to notice: a reviewer reading the file finds the invariant
documented at the reading site and has no reason to check the writing site.

Semantically, folding compaction into `carriersWithTextRemoved` is exact rather than convenient:
"the message left the prompt" is a *stronger* removal than "its carrier line was stripped", and
`DROPPED` is defined as "its passage text is not in the prompt". The monotonicity argument the
set rests on also survives — a compacted message never comes back.

One judgement call to settle in design: `compressionObserved`. Compaction *is* an observation
about the prompt, so a compaction that drops carriers should arm the producer even if no
compression pass ever ran. (In practice one always has, but the claim should be true because of
what the code does, not because of a scheduling accident.)

### §T.5 Findings 4 and 5: the wire is a record for a different reader

**Finding 4** is not "the wire is wrong" — it is "the wire is unlabelled". Two honest answers
exist (*what the tool returned* and *what the model saw*) and `output` silently means the first.
The right move keeps the human's view complete and adds the model's view beside it, rather than
shrinking `output` to the model's view: the reader is not context-bound, and a tool card that
showed less than the tool returned would be a *new* dishonesty. The persisted stores then split
cleanly and can be documented as such: `events.ndjson` is the tool's record, the checkpoint's
`messages` is the model's.

A trap worth flagging early (§A correction): there are **three** emit sites and all three
truncate on append. A stamp added at one of them creates a tri-state where the FE cannot tell
"not truncated" from "not measured". One helper, all three sites.

**Finding 5** is more interesting than it looks, because it is an honesty fix that *touches* a
regression somewhere else. `withLineage` puts a key into `structuredData` for every successful
result, and `RunChannelPolicy.evidenceKey` discriminates evidence from narrative by asking
whether `structuredData` is present at all.

The obvious fix (list the bulk keys) rots the first time a producer adds one. The better rule is
its inverse: **a stamp is not bulk.** Evidence iff `structuredData` carries at least one key that
is not a stamp. That stays correct when a new producer key appears and fails in the safe
direction when a new *stamp* appears (a new stamp would be misread as bulk — visible, not
silent). Module boundaries need checking: `app-observability` should not have to depend on
`app-agent-api` to know the stamp names, so either a shared constant or a literal pinned by a
test — and per §T.7, a copy with a binding test is not drift.

Also worth recording honestly rather than papering over: a `vop_*` tool's output is classified
`RUNTIME` because it is not in `CORPUS_READERS`, and a plugin that returns document text would
therefore be framed as a runtime value. That is a real limit of an op-id classifier applied to
FE-supplied tools. Naming it beats a guess.

### §T.6 Findings 6 and 7: the two "economics" findings share a root

**Finding 6 is a category error, not an inaccuracy.** `countPromptTokens(messages)` is not "40%
low"; it measures a *different prompt* than the one the run will send, because the real call
passes `tools` and the projection does not. Calling it an undercount invites tuning a fudge
factor; calling it the wrong prompt points at the actual fix. The existing
`max(tokens, lastReportedPromptTokens)` mitigation stays either way — it covers the
one-step-stale direction, which threading `tools` does not.

Two risks to name: the assumption that `/apply-template` honours a `tools` field cannot be
verified without a live server (it fails SAFE — an ignored field yields today's number, never a
worse one); and an honest projection makes the budget and context gates fire *earlier*, which is
what they are for but is still a live behaviour change against multipliers that were fitted while
the projection was blind.

**Finding 7 is where the honesty framing pays off most.** At n_ctx 4096, a 3000-char page and a
fixed cap of 10, "read three 27 KB documents" is not slow — it is *impossible by construction*,
and every layer currently hides that from both the model and the reader. The levers, ordered by
how much design they need:

1. **`total_chars` in the read header.** The model cannot budget what it cannot see. The Worker
   already computes the document's full length to build the slice (`GrpcSearchService:685`); it
   simply does not return it. Pure disclosure, no policy, and it is what makes "sample rather
   than page" a decision the model is equipped to take.
2. **Per-document progress vocabulary** (859 §2.3's "1 of 3 files"). The reader-facing half of
   the same fact: a ceiling-terminated run that opened one of three named documents should say
   so, instead of "used all its steps".
3. **Argument hygiene.** `offset_chars:"3000"` currently restarts at page 0 — a *duplicate page
   that looks like progress*, which is the worst possible failure shape here, and one a model
   emitting stringified numbers will hit constantly. And `DocumentSlice.error` is discarded, so a
   Worker failure is reported to the model as "not found in the index", sending it to look for a
   path that exists. Both are silent-wrong-answer defects, not ergonomics.
4. **Effort-scaled iteration cap.** The effort rung already exists and already scales the budget
   (`AgentBudgetPolicy`); the cap is a fixed FE constant. But — **this is the ordering insight** —
   raising the cap at n_ctx 4096 *without* §T.3's scar and lever 1 makes things strictly worse:
   more iterations means the context gate compacts sooner, which means the model loses what it
   read, which is the very loop the extra iterations were bought to escape. A cap raise is safe
   only behind the disclosure fixes.
5. **A summarize-as-you-go fold** — after each document, ask for a short note and drop the pages
   (map-reduce over documents). This is the *actual* answer to multi-document reading at small
   n_ctx, and it is a new mechanism with its own gates, prompts and failure modes: a follow-up
   tempdoc, not a rider on this one. (Deliberately not numbered here — several workstreams are
   claiming tempdoc numbers concurrently; pick the number at pickup time.)
6. A Worker-side extractive `summarize:true` read mode — same conclusion as 5, and strictly more
   speculative.

Levers 1–3 are disclosure and correctness and belong here. Lever 4 belongs here only if 1 and
§T.3 land with it. Levers 5–6 are follow-up.

### §T.7 Findings 8 and 9: when is a private copy acceptable?

Both are the same question in different clothes. `acquisition` is a String with two hand-written
constants where its three siblings on the same record project named enums; `CORPUS_READERS` is a
fourth private copy of a set of operation ids that fails open to `RUNTIME`.

The rule this repo actually operates by — visible in the gates it built rather than in prose — is:
**a copy bound to its authority by a test or a gate is not drift; an unbound copy is.** That
makes finding 9's cheapest honest fix a binding test rather than a refactor, and it makes
finding 8 about placement: the enum has to live where the record can see it, so the authority is
minted in `app-agent-api` and the record's constants become its projection, rather than the enum
being imported from the module that happens to mint sources.

The stale `ContextCitation` comment in the same finding is §T.4's pattern again — prose that was
true when written and that a later, deliberate fix (`CHUNK_INDEX_ABSENT` preserving `-1`) made
false without anyone re-reading the site that cited it. The guard it justifies is still correct;
only the reason is wrong, which is the more dangerous state of the two.

### §T.8 What this workstream should be careful not to become

- **Not a rewrite of compression.** Every fix here is a mark, a label or one extra call. Where a
  fix wants a new mechanism (levers 5–6), that is the signal it belongs elsewhere.
- **Not a claim about outcomes it cannot measure.** "Attempts synthesis and discloses either way"
  is provable here; "no longer returns empty answers" needs a live model and a rank-1 prompt.
  The same applies to the read loop (§T.3) and to the gate-timing change (§T.6).
- **Not a cap raise sold on its own.** See §T.6's ordering.

---

## §D. Design (2026-08-26)

The design is one sentence applied nine times: **wherever a run destroys information, the
artifact keeps a mark, and the mark names its consumer.** Nothing below invents a mechanism; each
piece is the mark that the layer one over already has.

### §D.1 The iteration ceiling becomes a deadline (finding 1)

**Shape.** The ceiling terminal moves from `AgentLoopService` into `AgentStepRunner` and reuses
the budget wall's machinery verbatim: gate on `hasSuccessfulToolResult`, narrate `finalizing`,
call `attemptBudgetEdgeFinalize`, emit through `groundedDone` with
`TerminalDisposition.MAX_ITERATIONS` — whether or not the finalize produced text.

**Why the placement is forced rather than chosen.** `AgentGroundingSeamAuditTest` permits a
grounding-carrying `AgentDone` to be constructed in exactly two places, one of which is
`AgentStepRunner.groundedDone`. A ceiling terminal that carries evidence therefore *has* to be
emitted from the step runner. `AgentLoopService` keeps ownership of the state transition
(`markTerminated` + checkpoint) and calls the runner for the event — the same division the loop
already uses for every other terminal.

**What this orphans, and this tempdoc deletes:**

- `AgentEvent.AgentDone.ofDisposition` — the ungrounded factory, whose sole production caller is
  the ceiling. 865 §7.1 kept it because draining the accumulator into it would trip the seam
  audit's `java.util.List`-signature discriminator; routing through `groundedDone` removes that
  reason entirely. The factory goes, and with it the "exempt rather than fixed" exemption in
  `AgentGroundingSeamAuditTest`'s and `AgentLoopServiceTest`'s javadocs.
- The 859 §D §2.6 comment at the ceiling asserting the terminal "produces no answer text at all,
  so the model cannot disclose the truncation even in principle". That sentence stops being true;
  leaving it would be exactly the false authority `retire-with-a-sweep` is about.
- The existing MAX_ITERATIONS test's central assertion (`done.sources().isEmpty()` — "that is its
  contract, not a regression"). It is replaced by the *stronger* property it was standing in for:
  terminal equivalence now covers three dispositions instead of two.

**Fail-open by construction.** `attemptBudgetEdgeFinalize` already returns `null` on any failure
and the disposition is written independently of the text, so the worst case is byte-for-byte
today's behaviour: an empty answer stamped `MAX_ITERATIONS`.

**FE.** `sv3CutShortNotice` gains a second input — whether the turn has answer text — because the
one string it returns today can no longer be true in both cases. Two arms for `MAX_ITERATIONS`:
the run reached the ceiling *with* a partial answer, or *without* one. This is 859 D5's own rule
("the two limits have different remedies, so they get different sentences") applied one level
down, to the two outcomes of one limit.

### §D.2 Layer-3 substitutes a scar instead of deleting (finding 2)

**Shape.** `stripSearchExcerpts` stops being a pure deletion. It counts what it removes and
leaves **one** summary line in place of the removed lines — not one per line, which would cost
more tokens on a ten-hit search result than the strip saves.

**Where the label lives, and why it matters.** In `ToolResultCarrier`, beside the three carrier
labels — because the scar's defining property is a *relationship to the two patterns in that
file*: it must match neither `CARRIER_LINE` (or the receipt would report stripped text as still
in front of the model) nor `STRIPPABLE_LINE` (or the next pass would strip the scar and the
elision would go silent after all, which is the same bug one iteration later). That coupling is
precisely what `ToolResultCarrier` was created to hold in one place, and a test pins both
non-matches.

**What the scar says.** It is addressed to the model, so it is written to change a decision: how
much was removed, that this text was already placed in front of the model once, and that asking
again will not bring it back. Compare the two marks that already exist — `[... truncated, N chars
omitted]` and `[compressed-tool-output originalChars=… keptChars=…]` — both state the quantity;
this one additionally states the consequence, because the observed failure was a *behaviour*, not
a misreading.

**Honesty limit recorded rather than papered over:** per §T.3 this may be the secondary remedy.
The scar is correct on its own terms (an elision that leaves no mark is a silent one) and is
justified by that alone; the claim that it ends the read loop is not made here.

### §D.3 Compaction reports what it dropped (finding 3)

**Shape.** `compactOlderTurns` — already `synchronized`, already the one place that deletes
messages — records the `tool_call_id` of every `role:"tool"` message it drops into
`carriersWithTextRemoved`, and arms `compressionObserved` when it dropped any.

**Why this is exact, not convenient.** `ContextInclusion.dropped()` is defined as "this source's
passage text is not in the prompt". A message that left the prompt satisfies that more strongly
than one whose carrier line was stripped. The set's monotonicity argument — content only ever
shrinks, so a removed carrier never returns — holds for deletion trivially.

**No new type, no new call site.** The alternative (teaching `receiptFor` about a "known
universe" of ids so absence could be classified) would put the fact in the reader instead of at
the writer, which is how the divergence happened in the first place.

This closes a *prose-ahead-of-code* defect: `inclusionFor`'s javadoc already promised "or has left
the prompt entirely". The design does not change the contract — it makes the code keep it.

### §D.4 The wire says how much the model saw (finding 4)

**Shape.** `AgentEvent.ToolExecutionCompleted` gains ONE component, not two: the number of
characters of this result that were placed in the prompt, with a sentinel for *not measured*.
"Truncated for the model" is then derived (measured, and fewer than the tool returned) and cannot
contradict the count — a two-field version can, which is the failure mode worth designing out.

**One helper, three sites.** Main dispatch, handoff confirmation and the virtual seam all emit
this event and all truncate on append. The Layer-2 cut moves *before* the emit at all three, the
truncated string is what gets appended, and its length is what the event carries. A stamp at one
site only would leave the FE unable to distinguish "not truncated" from "not measured" — the
tri-state that §A flagged.

**`output` stays whole.** The reader is not context-bound; a tool card that showed less than the
tool returned would be a new dishonesty, not a fix for an old one. The wire carries the tool's
answer *and* the model's view of it, labelled.

**The two stores, finally distinguishable.** `events.ndjson` is the record of what each TOOL
returned; the checkpoint's `messages` is the record of what the MODEL was given. Neither says so
today. This design does not add a store — it names them, in their own javadoc, and gives the wire
the field that makes the difference computable rather than inferable.

**FE.** `ToolCall` carries the count; the output panel says, in one short line, how much of the
output the model actually received when it received less than the whole.

### §D.5 Lineage at every dispatch seam — and the evidence discriminator (finding 5)

**Shape, two halves.**

1. The virtual seam stamps `withLineage` on a successful result, exactly as main dispatch does.
   `AgentGroundingSeamAuditTest` gains a lineage existence rule mirroring the grounding one it
   already has, iterating the same `DISPATCH_SEAM_METHODS` list — so the rule bites per seam
   rather than once per class, which is the shape that caught this omission for grounding.
2. `RunChannelPolicy.evidenceKey` stops asking "is `structuredData` present" and asks "does
   `structuredData` carry anything that is not a **classification stamp**".

**Why the inverse rule.** Listing the bulk keys (`searchResults`, `readResults`, `grounding`, …)
rots the first time a producer adds one, and the failure is silent. Excluding the stamps rots only
when a *new stamp* is added, and that failure is visible (a narrative frame classified as
evidence, which is today's state — so the design cannot make it worse). `grounding` is content,
not classification: it carries the excerpts. `lineage` is the only classification stamp today.

**Single authority for the key names.** `OperationResult` owns the two key strings as named
constants, used by its own stamp methods and read by `RunChannelPolicy` (which already depends on
`app-agent-api`). Two literals become one declaration; nothing new crosses a module boundary.

**Recorded limit, not hidden:** a `vop_*` tool's output classifies `RUNTIME` because it is not in
`CORPUS_READERS`, so a plugin that returns document text would be framed as a runtime value. An
op-id classifier cannot see inside an FE-supplied tool. Naming that beats guessing at it.

### §D.6 The budget projection measures the prompt that will be sent (finding 6)

**Shape.** `applyTemplate` and `countPromptTokens` gain a `tools` parameter, threaded from the
step runner's per-iteration tool list — which is already built *before* the projection runs, so no
reordering is needed. The existing single-argument forms stay as overloads delegating with an
empty list, so implementors that have no tools (and test fakes) are untouched.

**Framing that decides the fix.** This is not a 40% undercount to be corrected with a factor; it
is a projection of a *different prompt* than the one the run sends. Naming it that way is what
makes threading `tools` the fix instead of a calibration constant.

**What stays.** `max(tokens, lastReportedPromptTokens)` is orthogonal — it covers the
one-tool-result-stale direction, which no amount of schema awareness addresses. The comment
asserting the projection is "schema-blind and measured ~40% low" is superseded and rewritten in
this tempdoc; the one-step-stale rationale for the `max` survives it.

**Two risks stated, not assumed away.** Whether `/apply-template` honours a `tools` field cannot
be settled here; it fails SAFE (an ignored field yields today's number). And an honest projection
makes both gates fire earlier — intended, but a live behaviour change against multipliers fitted
while the projection was blind.

### §D.7 Paging: give the model the denominator, and stop the silent wrong answers (finding 7)

Four levers land; one is designed and deliberately does not ship (§D.8).

**(a) `total_chars` — the disclosure that makes budgeting possible.** The Worker already computes
the document's full length to cut the slice and discards it. The response carries it, the slice
record carries it, and the read header states the span *out of the whole*. This is the single
highest-value item in the finding: a model that cannot see the denominator cannot choose between
paging and sampling, and every downstream lever (one-page defaults, progress vocabulary, an
effort-scaled cap) is guesswork without it. Unknown (`0`) stays unknown — an older Worker's
header says what it says today rather than inventing a total.

**(b) Argument hygiene — two silent wrong answers.**
`offset_chars:"3000"` today restarts at page 0: a duplicate page that *looks like progress*, which
is the worst available failure shape, and one a model emitting stringified numbers meets
constantly. The contract: a JSON number is used; a string that parses as an integer is coerced;
anything else is refused **loudly**, naming the field and the value. Silent fallback to the
default is what this replaces.
And a `DocumentSlice` that carries an error reason is currently reported to the model as "not
found in the index", which sends it to look for a path that exists. The fix names the reason when
the slice gives one and keeps the browse/search guidance as the follow-on sentence — no string
matching against the Worker's prose, which would be a second authority for "what counts as
absence".

**(c) One page per document, by default.** A *declaration* change, not a mechanism: the
operation's model-facing description and its `offset_chars` argument description say that one page
is the normal read and that paging further is for when the first page does not answer the
question. This is the cheapest lever and the only one that acts before the first wasted call.

**(d) Per-document progress — corrected to what the run can honestly know.** 859 §2.3's "1 of 3
files" phrasing assumes a denominator the run does not have: the number of documents the *user*
meant is not recoverable from anything the loop holds. What the run does know exactly is which
documents it opened. So the vocabulary is a factual inventory — the documents opened — and it is
delivered where it changes an outcome: appended to the finalize instruction, which already asks
the model to "name what you had gathered and what you had not gotten to yet" and currently gives
it nothing to name. The reader-facing half is the ceiling's `finalizing` narration.
**This is a correction to the handed-over finding, not an omission from it:** a fabricated
denominator on a truncation notice would be a new false claim on the exact surface this tempdoc
exists to make honest.

### §D.8 The effort-scaled iteration cap: designed, and deliberately not shipped

`AgentBudgetPolicy`'s `THOROUGH_MULTIPLIER` is justified by a stated structural bound:

```
spend(run) <= maxIterations * (n_ctx + maxTokens)
```

15x clears that bound *for `maxIterations` = 10*. Raising Thorough's cap to 20 puts the bound at
~25x and silently voids the derivation that the Thorough rung's entire meaning rests on
("tokens can never be what stops this run — the iteration cap is"). So the cap and the multiplier
are one decision, not two, and re-deriving the multiplier is a spend decision that belongs to the
owner.

What ships instead is the part that is unambiguously this tempdoc's business: **the coupling
becomes enforced rather than asserted.** A test derives the bound from the constants and fails if
a future cap raise voids it. Prose that says "15x clears the bound" is ~70% adherence; a test that
computes it is the guarantee — and it converts the next cap change from a one-line edit into a
visible decision, which is exactly what §T.6's ordering argument wants.

The owner call to be made, stated plainly: *at n_ctx 4096, should Thorough be funded for more
than 10 steps, and at what multiplier?* Everything needed to answer it — the bound, the measured
per-iteration burn, and now the disclosure levers that make extra steps productive rather than
self-defeating — is in place after this tempdoc.

### §D.9 Vocabulary authority (findings 8, 9)

`acquisition` gets a named enum in `app-agent-api` — the module the record lives in, so the
constants on `AgentSource` become a projection of it rather than free-standing strings, matching
what `contextInclusion`, `citationScorer` and `disposition` already do. The wire tokens are
unchanged by construction, which is the test.

`CORPUS_READERS` keeps its private copy and gains the thing that makes a copy legitimate here: a
test binding it to the catalog's corpus-reading operations, so a new corpus reader that forgets
this file fails the build instead of silently framing quoted text as a runtime value. This follows
the rule the repo already operates by (§T.7) rather than inventing a derivation mechanism for a
three-element set.

The stale `ContextCitation`-clamps-to-`0` comment is corrected in place, keeping the guard and
replacing its reason with the true one.

### §D.10 Sweep list (this tempdoc's work, not a follow-up)

| Orphan / falsehood | Disposition |
|---|---|
| `AgentEvent.AgentDone.ofDisposition` | deleted — sole caller replaced |
| The `ofDisposition`-exemption javadocs in `AgentGroundingSeamAuditTest` and `AgentLoopServiceTest` | rewritten to the new three-terminal equivalence |
| The ceiling's "produces no answer text at all … cannot disclose even in principle" comment | rewritten |
| `MAX_ITERATIONS` FE notice "used all its steps before reaching an answer" (+ its exported constant) | replaced by the two-arm notice |
| `AgentContextCompressor.stripSearchExcerpts`' "exactly as before" comment and `ToolResultCarrier`'s strip narrative | rewritten to include the scar |
| `AgentStepRunner`'s "schema-blind and measured ~40% low" comment | rewritten; the one-step-stale rationale kept |
| `RunChannelPolicy`'s "evidence only when it carries structuredData — the bulk case" comment | rewritten to the stamp-exclusion rule |
| `"lineage"` / `"grounding"` string literals in `OperationResult` | replaced by named constants, read by `RunChannelPolicy` |
| `AgentSession`'s stale `ContextCitation` clamp comment | corrected |

Nothing here is deferred to a cleanup PR. Every rewritten comment is a claim this design makes
false, and a false claim left in place is the residue `retire-with-a-sweep` exists to prevent.

---

## §R. The design's reach

### §R.1 The principle this is an instance of

**Elision leaves a scar, addressed to a named consumer.**

*Statement.* Any layer that removes information from a run leaves a mark on the artifact it
removed it from, and the mark is written for the consumer that would otherwise misread the
remainder. A run has two consumers reading two different artifacts — the MODEL reads the prompt,
the READER reads the wire and the record — and a mark aimed at one is not a mark for the other.

*This is not a new invariant.* It is already implemented four times in this codebase
(`[... truncated, N chars omitted]`, `[compressed-tool-output …]`, `ContextInclusion.dropped`,
`TerminalDisposition`). What this tempdoc found is that the practice was applied wherever someone
happened to think of it, and skipped at four layers where nobody did. Naming it converts "four
bugs" into "one discipline with four gaps".

*Where else it applies, and where the code still violates it:*

- **Handoff pruning** (`AgentHandoff.pruneHandoffMessages`) removes messages from the prompt. It
  is the same shape as §D.3 and should be checked against the same ledger question.
- **The system-prompt swap** on handoff replaces the run's instructions; the model is not told
  what changed.
- **`RAGContext`'s assembly cut** already marks (`contextInclusion` + `contextIncludedChars`) —
  a positive instance worth citing as the reference implementation for the reader-facing half.
- **`SearchTool.formatResults`' per-result budget** (Layer 1) clips a later hit's carrier line
  while still minting it as a source. `AgentSession.inclusionFor` names this as the reason it
  refuses to ever say `included` — an honest refusal, but the *elision itself* is still unmarked
  in the prompt. This is the clearest remaining violation and the natural next instance.

*Evidence it would be earning its keep:* new elisions ship with their mark in the same change,
and reviews start asking "which consumer does this mark address?" rather than "is anything
logged?". Concretely — the next compaction/trim/cap added to the loop arrives with both halves,
without a live incident teaching it.

*Retirement condition:* retire it if the two-consumer distinction stops paying — i.e. if marks
start being written for both consumers identically with no loss, which would mean the prompt and
the wire have converged and the principle is just "log what you drop". Also retire it if it
begins generating marks nobody reads: a scar the model provably ignores and no reader surface
renders is apparatus, not honesty, and should be deleted rather than defended.

### §R.2 A second, narrower shape worth naming

**A projection must name the prompt it projects.** `countPromptTokens` measures a prompt the run
never sends; `output` on the wire means one of two things without saying which; the checkpoint
and `events.ndjson` answer different questions under the same name. The general form: when two
honest answers exist for one name, the name must be qualified at the producer, not disambiguated
at each consumer.

Where else this likely applies: anywhere a token count, a character count or a "the content" field
is produced on one side of a truncation boundary. `AgentSession.lastReportedPromptTokens` versus
the projection is the same distinction already made *correctly* — two names, two facts — and is
the model for the fix.

*Retirement condition:* this one is cheap enough to be permanent, but it should be retired as a
*named* principle the moment it is enforced by something (a naming convention gate, or a type). A
principle that a type could carry does not need prose.

### §R.3 What this design does NOT generalize

The temptation is to build a "run elision registry" — one place every layer reports removals to,
with a uniform mark. That is premature: the four layers remove different things, for different
consumers, at different times, and their existing marks are already legible where they sit. The
principle is worth naming; the apparatus is not worth building until a fifth and sixth instance
show the marks actually needing to be correlated. Recording the principle without building the
structure is the deliberate half of this judgment.

---

## Plan

Written into the tempdoc rather than plan mode (subagent). Ordering is driven by file conflict,
not by finding number: `AgentStepRunner`, `AgentSession` and `AgentEvent` are each touched by
several workstreams, so those are sequenced in one lane while the disjoint modules run in
parallel.

### Lane A — parallel, disjoint modules (delegated, pinned opus)

**W4 — schema-aware budget projection (finding 6).** `app-inference` + `app-api`.
`TokenEndpointOps.applyTemplate` / `countPromptTokens` gain a `tools` parameter (single-arg
overloads retained, delegating with an empty list); `InferenceLifecycleManager` and
`OnlineAiServiceImpl` thread it; `OnlineAiService` gains a defaulted overload so existing
implementors and test fakes compile unchanged. Test: the request body carries `tools` when
supplied and is byte-identical to today when not.

**W5 — the read tool's three silent wrong answers (finding 7a/7b).** `ipc-common` proto +
`worker-services` + `app-services` + `app-api` + `ReadDocumentTool`.
`FetchDocumentSliceResponse` gains `total_chars`; `GrpcSearchService` sets it from the length it
already computes; `DocumentSlice` gains a `totalChars` component (the default `fetchSlice` in
`DocumentService` sets it too); the read header states the span out of the whole when known and
is unchanged when not. `intArg` coerces integer-valued strings and refuses anything else loudly.
A slice that carries an error reason is reported with that reason instead of "not found".
Tests: header with/without a known total; `offset_chars:"3000"` reaches the Worker as 3000;
`offset_chars:"abc"` fails naming the field; an errored slice names the error.

### Lane B — sequenced, app-agent core (this session)

Order is forced: B1 changes `AgentEvent` + `AgentStepRunner` + `AgentLoopService`; B2 changes
`AgentSession` + the compressor; B3 changes `AgentEvent` again and `AgentStepRunner` again.

**B1 — the ceiling becomes a deadline (finding 1) + sweep.**
`AgentStepRunner` gains the ceiling terminal (gate → narrate → finalize → `groundedDone`);
`AgentLoopService` calls it and keeps `markTerminated`/checkpoint; `AgentDone.ofDisposition` is
deleted with its two exemption javadocs. Tests: a run that exhausts its iterations *with*
successful tool results makes one extra no-tools LLM call and its answer text reaches the
terminal; the terminal's sources equal the concatenated deltas (equivalence extended to three
dispositions); a finalize that fails lands on the empty-answer + `MAX_ITERATIONS` behaviour; the
disposition is `MAX_ITERATIONS` in every case.

**B2 — the Layer-3 scar (finding 2) + the compaction ledger (finding 3).**
`ToolResultCarrier` gains the scar label and the two non-match properties as tests;
`stripSearchExcerpts` substitutes one summary line for the lines it removes;
`compactOlderTurns` folds dropped tool-message ids into `carriersWithTextRemoved` and arms the
observation flag. Tests: a stripped read result no longer presents a bare `More:` header; the
scar survives a second strip pass unchanged (idempotence); the receipt still classifies the
message `textRemoved`; a source whose only carrier was compacted away reports `dropped` at the
terminal (RED on `main`).

**B3 — model visibility on the wire + lineage at every seam (findings 4, 5).**
`ToolExecutionCompleted` gains the measured-chars component with a not-measured sentinel; the
Layer-2 cut moves before the emit at all three sites through one helper; the payload writes the
two derived keys only when measured; the virtual seam stamps lineage; the seam audit gains the
lineage existence rule; `OperationResult` owns the two key-name constants and `RunChannelPolicy`
discriminates on "not a classification stamp"; the two stores get their javadoc split. Tests:
each of the three emit sites reports a measured count; the count equals the appended message's
length; a virtual tool result carries `lineage`; a lineage-only result is NOT evidence and a
result with a bulk key IS; the seam-audit rule fails when a seam drops the stamp.

**B4 — paging declarations + the finalize inventory (finding 7c/7d) + the bound guard (§D.8).**
The operation declaration's model-facing text states the one-page default; the finalize
instruction is appended with the run's factual inventory of opened documents (no invented
denominator); the ceiling narration carries the same count; `AgentBudgetPolicy` gains the test
that derives the structural bound from the constants and fails a cap raise that voids it.

**B5 — vocabulary authority (findings 8, 9).** The `acquisition` enum in `app-agent-api` with the
record's constants as its projection (wire tokens unchanged — that is the test); the
`CORPUS_READERS` binding test against the catalog's corpus-reading operations; the stale
`ContextCitation` comment corrected.

**B6 — frontend.** The two-arm `MAX_ITERATIONS` cut-short notice (`sv3-honesty` + its call site
and exported constant); `ToolCall` carries the measured count and the output panel states it when
the model received less than the whole. `npm run typecheck` + `npm run test:unit:run`, plus the
ui-web gate recipe from the consult register.

### Validation

Per the common brief, in order, all Gradle through `gradle-locked.sh`:
`spotlessApply` → `build -x test` → full `test` (`VduEligibilityPdfFixturesTest` is a known local
red) → ui-web typecheck + unit tests → the pre-merge checks for the subjects edited (the ui-web
gate recipe; `--gate wire` if the proto turns out to be under its subject; `check-live-witness`
and the surface gates if a registry surface moves). Then the critical-analysis pass and one
independent refute-first opus reviewer on the diff.

**Explicitly out of reach here (orchestrator's phase):** every claim that needs a live model —
the rank-1 prompt completing or returning a partial answer at the default cap; whether the read
loop was H1 or H2 (§T.3); whether `/apply-template` honours `tools`; whether the earlier-firing
gates change run outcomes. These are listed in the final report, not asserted in this tempdoc.

**Deliberately not implemented:** the effort-scaled cap *raise* (§D.8 — coupled to
`THOROUGH_MULTIPLIER`, an owner spend decision; the enforcing test ships instead), and the
summarize-as-you-go fold and Worker-side summarize mode (§T.6 levers 5–6 — new mechanisms, their
own tempdoc).

---

## §I. Implementation record (2026-08-26)

### §I.1 What the design did not anticipate

Five things the plan got wrong or under-specified, found while building. Each is recorded because
the *reason* generalizes, not because the fix was hard.

1. **The finalize INSTRUCTION named the wrong limit.** The design said "run the same finalize";
   the same finalize opens "This run has reached its token budget". A step-ceiling run routinely
   stops with most of its budget unspent, so reusing the sentence would have committed 859 D5's
   defect — naming the wrong limit — in the text handed to the *model*, one layer below the FE
   notice D5 fixed. The split is a shared obligation block plus a per-terminal limit sentence, and
   a test pins that everything after the limit sentence is byte-identical, so a future edit to the
   four disclosure rules cannot land on one terminal and miss the other.
2. **The ceiling's telemetry has no home.** `agent.budget_edge_finalize.total` is named for the
   budget wall. Folding a second terminal into it would silently change what a recorded number
   means — this tempdoc's own defect class, committed while closing it. So the counter stays
   budget-only, the ceiling records nothing, and the gap is an open item in §O rather than papered
   over with a metric-schema change nobody asked for.
3. **`turn.answer` is not an agent turn's answer.** The FE's two-arm notice needs "did this turn
   end up with an answer", and `turn.answer` is populated only on the RECORD path — an agent turn's
   prose is the run feed's terminal text item. Reading the field alone printed "before reaching an
   answer" above the answer. The helper asks the same two places the answer renders from.
4. **The wire field needed four more sites than the design named.** The payload key had to be
   declared on `AgentRunShape` (an undeclared key fails `AgentEventPayloadConformanceTest`'s
   subset rule), recaptured into `shapes.fixture.json`, regenerated into the FE's shape handlers,
   AND carried by `AgentInteractionMapper` — whose `tool_exec_completed` case whitelists keys, so
   without it a reloaded run would have said less than the live one. That last is the live-vs-record
   divergence 867 built one renderer to prevent, and it was invisible from the design.
5. **An existing test's fixture was wrong for its own name.** `twoBulkyToolResultsKeepDistinctKeys`
   passed an EMPTY `structuredData` — it exercised two results that carried nothing, and only
   passed because the old discriminator asked whether the key was present. Tightening the rule
   exposed it. The fixture was corrected to match the test's stated intent; the assertions are
   unchanged.

### §I.2 What shipped

| Finding | Shipped | Regression test that fails on `main` |
|---|---|---|
| 1 | `AgentStepRunner.finalizeAtIterationCeiling` — gate on a successful tool result, narrate, one no-tools finalize, `groundedDone(MAX_ITERATIONS)` either way. `AgentLlmCaller` split into a shared obligation block + per-terminal limit sentence. `AgentDone.ofDisposition` deleted. FE: two-arm cut-short notice. | `maxIterationsTerminal_attemptsFinalizeAndCarriesItsAnswer` (three LLM calls, no tools on the third, step-limit instruction, answer on the terminal); `maxIterationsTerminal_carriesTheEvidenceItEstablished` (terminal equivalence, third disposition); `theStepCeilingInstructionSharesTheObligationAndNamesItsOwnLimit` |
| 2 | `ToolResultCarrier.elidedLine` + `stripSearchExcerpts` substitutes one scar per message instead of deleting | `theScarMatchesNeitherPattern`, `strippingAReadPageLeavesAScarBesideTheMoreHeader`, `theStripIsIdempotent`, `theScarDoesNotDisturbTheReceipt` |
| 3 | `compactOlderTurns` folds dropped tool-call ids into `carriersWithTextRemoved` and arms `compressionObserved` | `compactedAwayCarrier_yieldsDroppedSource` |
| 4 | `ToolExecutionCompleted.outputCharsToModel` (+ derived `truncatedForModel`), the Layer-2 cut moved before the emit at all three seams, payload + `AgentRunShape` + fixture + generated FE types + `AgentInteractionMapper` + `sv3-record` + the tool card's note; `AgentRunStore.updateCheckpoint` javadoc names which store answers which question | `toolCompletedReportsWhatReachedTheModel`; ui-web `878 §D.4 — says when the MODEL received less…` and its silent-in-two-ways sibling |
| 5 | `withLineage` at the virtual seam; `everyDispatchSeamStillStampsTheLineage`; `OperationResult.LINEAGE_KEY`; `RunChannelPolicy.carriesBulk` (a classification stamp is not bulk) | `everyDispatchSeamStillStampsTheLineage`, `aClassificationStampIsNotBulk`, and the lineage assertions added to `virtualToolRun_keepsTerminalEquivalence` |
| 6 | `tools` threaded through `applyTemplate` / `countPromptTokens` / `OnlineAiService` (defaulted overload) and passed at both `AgentStepRunner` projection sites | `applyTemplate_sendsToolsWhenListNonEmpty`, `applyTemplate_emptyToolsProducesBodyIdenticalToNoToolsForm`, `countPromptTokens_interfaceDefault_delegatesToSingleArgumentForm` |
| 7 | `total_chars` end to end (proto → Worker → `DocumentSlice` → read header); `intArg` coerces integer strings and refuses anything else loudly; an errored slice reports its reason instead of "not found"; one-page-per-document declared in the operation description; the finalize instruction carries the run's opened-document inventory | `stringifiedOffsetIsCoercedNotDropped` (asserted 0 on `main`, asserts 3000 now — the sharpest one), `unusableOffsetIsRefusedLoudly`, `unusableSliceSurfacesItsReason`, `headerNamesTheTotalWhenTheSliceReportsOne` |
| 8 | `SourceAcquisition` enum in `app-agent-api`, `AgentSource`'s constants projected from it and its compact constructor normalising through `fromWireToken`; the stale `ContextCitation`-clamps-to-0 comment corrected | wire tokens unchanged is the test (existing `AgentSessionGroundingTest` assertions) |
| 9 | `everyAgentOperationIsClassifiedByOutputLineage` — every AGENT-tagged operation must be a declared corpus reader or a declared runtime one, and every `CORPUS_READERS` id must still name a declared operation | that test |

**One addition is deliberately NOT a regression test, and saying so matters.**
`AgentBudgetPolicyTest.thoroughMultiplierStillClearsItsStructuralBound` is GREEN on `main` — 15x
already clears the 12.5x bound at today's cap of 10. It is a RATCHET, not a red-before-green: it
computes the bound from the constants so the next cap raise fails the build instead of silently
voiding the derivation the Thorough rung's meaning rests on (§D.8). Listing it beside the genuine
regressions would be the same category error this tempdoc is about.

Canonical doc: `docs/explanation/22-agent-system-architecture.md` gains a **Run-honesty invariants**
section — the involuntary-terminal rule and the elision-mark table — plus the read tool's row
updated for the total and the one-page default.

### §I.3 Independent review — findings and dispositions

An independent refute-first opus reviewer (read-only, ≠ implementer) audited the committed diff. It
found **two BLOCKERs, both real and both mine.** Every finding is recorded, including the ones not
taken and why.

**B1 — the model-visibility count measured the envelope, not the output. FIXED.**
`AgentContextCompressor.truncate` does not return a prefix; it returns the prefix PLUS a
`[... truncated, N chars omitted]` marker, ~35 characters of framing. The producer measured the
returned string, so for an output just over the cap it reported MORE characters than the tool
produced — which inverted the derived `truncatedForModel` into a measured *"the model got all of
it"* on an output it demonstrably did not get all of, and put a number on the tool card larger than
the output it was a fraction of. This is the exact mixed signal §D.4 chose a single derived
component to make impossible, reintroduced one layer down, in the field whose entire purpose is to
stop guessing. `charsToModel` now measures the ORIGINAL, capped: the marker is in the prompt but it
is not the tool's answer, and this field counts the answer. Pinned by
`anOverCapToolResultReportsWhatThePromptActuallyGot` (a real 9000-char result through the real loop
— the two unit tests hand-passed a count and so could not see it) and by
`theModelCountIsAFractionOfTheOutput`, which states the invariant rather than a magic number.

**B2 — the ceiling finalize could be sent grammar-constrained to emit a tool call. FIXED.**
`attemptFinalize` used the 3-argument `callLlmWithTools`, which resolves sampling from the SESSION.
In the E0a forced-tool state that returns `tool_choice=required` **plus** `TOOL_CALL_GRAMMAR` — and
the server applies a grammar exactly when the tools list is empty, which it always is for a
finalize. The model would be CONSTRAINED to emit `<tool_call>{…}</tool_call>` with no tools to call,
and `recoverInlineToolCalls` cannot strip it (its name set is empty when tools is empty), so a raw
JSON blob would stream out AS THE ANSWER of a truncated run. Reachable, not exotic: `recordHandoff`
zeroes `agentIterationsSinceHandoff` and the counter only increments after the LLM call, so a
handoff on the last allowed iteration leaves the session forced when the loop falls through.

The budget wall never met this because its outer gate excludes forced-tool turns for an unrelated
reason (E0a must not be stranded). §D.1's claim that the machinery was reused "verbatim" was
therefore wrong in the one way that mattered — it reused the finalize and not the guard around it.
The fix is at the finalize, not at the call site: a no-tools call now pins `SamplingParams.AGENT`
explicitly, so the invariant is structural rather than something every future caller must remember.
The ceiling deliberately does NOT grow the budget wall's outer gate — at a hard ceiling the run is
over either way and a synthesis attempt is still right. Pinned by
`theFinalizeNeverInheritsATellingToolForcingProfile`, which asserts the premise (session-resolved
sampling really does force a tool call here) before asserting the fix.

**S1–S6, all FIXED.** The sweep §D.10 committed to was incomplete, which is the residue-outliving-
its-reason failure the rule exists to prevent, committed inside the tempdoc that cites it:
the stale `ContextCitation`-clamps-to-`0` claim survived in three more places (`ReadDocumentTool`,
`AgentSessionGroundingTest`, `ReadDocumentToolTest`); `ofDisposition` and "MAX_ITERATIONS claims no
grounding" survived in `governance/execution-surfaces.v1.json` (a governance register naming a
deleted symbol) and as an open item in `docs/observations.md` (now marked resolved);
`iterationCeilingDeclaresItsDisposition`'s comment and assertion reason had become false, and its
fixture is now labelled as deliberately exercising the fail-open arm; "schema-blind and measured
~40% low" survived in `budgetProjection.ts`, where it is the STATED REASON the headroom uses the
reported prompt — the one place a reader would act on it (the choice stands, on its remaining
ground: a margin should be sized from a measurement, not a forecast). `turnHasAnswerText` asked
`.some(kind === 'text')` while its javadoc said "terminal text item", so a run with interim prose and
no answer would have claimed one; it now goes through `terminalTextItemId`, the same authority both
render iterators use. And `everyAgentOperationIsClassifiedByOutputLineage`'s second direction was a
third hand-written copy of the reader ids rather than a binding — `OutputLineage.corpusReaderIds()`
now exposes the authority and the test iterates it.

**NITs taken:** the scar's char count was short by one newline per removed line; `badIntArg` echoed
an unbounded `JsonNode` (an error is not a channel for returning content); the handoff confirmation
— the third `ToolExecutionCompleted` producer — stamped no lineage; and `intArg` accepted a
non-integral JSON number while refusing its stringified twin, which made its own "must be a whole
number" message false in one of the two cases.

**NITs not taken, with reasons:** the ceiling can now block for the LLM timeout where it previously
returned instantly — that is the budget wall's existing behaviour and the cost of attempting
synthesis at all, not a defect this tempdoc introduces. `AgentBudgetPolicyTest`'s "rounded UP" prose
was corrected rather than the arithmetic, which was already right.

### §I.4 Critical-analysis pass on the merge seam

`origin/main` moved twice mid-flight (871/873/874/869, then 872's retirement of the observations
store). Nine files were changed by BOTH sides since the branch point and auto-merged with no
conflict marker — the shape the `merge-full-suite-hint` warns about, where each side is correct
alone and the merge reopens a hole. Checked one by one:

- `sv3-honesty.ts` — main ADDED `sv3AnswerFrame`; it does not touch `SV3_TRUNCATION_NOTICES` or
  `sv3CutShortNotice`. Disjoint. The two-arm notice has exactly ONE production caller in `Sv3Main`
  and it passes both arguments explicitly, so nothing silently takes the `hasAnswer = true` default;
  `sv3WasCutShort` reads the same function on that default but is arm-independent (both arms are
  non-null).
- `AgentToolsOperationCatalog.java` — main edited `browse-folders`' comment, ours `read-document`'s
  `offset_chars`. Disjoint.
- `execution-surfaces.v1.json` — our note survived; the one surviving `ofDisposition` mention is our
  own past-tense tombstone, and a repo-wide grep confirms all five remaining mentions are historical
  context, with no live reference to the deleted symbol.
- The FE suite is green (460/462, the two failures both pinned pre-existing flakes) — but the suite
  is NOT what earned this conclusion, and the finding below is why: it passed while the
  model-visibility note was unreachable on a whole card type. The overlap files were re-read
  three-way (main's side, ours, merged) by hand.

**One finding, fixed (`85df979e`).** The model-visibility note rode on
`renderLineageFramedOutput`, and the search branch does not call it — a search card renders
`renderSearchBody` instead (867). So the BULKIEST tool output, the one most likely to be Layer-2
cut, was the only card that could be truncated silently. This was ours, not the merge's: the note
was attached to the wrong thing from the start, and 871's arrival on the same file is what made it
visible. The note is a fact about the tool RESULT rather than about the raw-output panel, so it now
sits outside both body branches, with a search-card fixture pinning it.

### §I.5 Second independent review (merge seam) — findings and dispositions

A second independent refute-first reviewer (read-only, ≠ implementer) audited the merge seam and the
post-merge deltas. **No blockers.** It independently reached the search-card finding §I.4 records,
and confirmed the fix passes for the right reason. Four SHOULD-FIX, all taken:

1. **The integrationTest pin fired on its own confirm command.** `(?!.*--tests)` sat AFTER the
   `.*`, so backtracking let `integrationTest` match from inside a `--tests` filter VALUE
   (`*KnowledgeServerIntegrationTest*`), leaving no later `--tests` for the lookahead to reject —
   and the hook compiles case-insensitively. The claim ends "re-run them with `--tests` to confirm",
   so the pin was suppressing the very step it prescribed. Lookahead hoisted ahead of the `.*`;
   pinned by a ten-case matcher check (both confirm forms no-match, `build` and bare
   `integrationTest` still match, `build -x test` and `test` still don't).
2. **That pin's `exitProbe` was guaranteed to fire.** It was verbatim the isolation run the claim
   states PASSES, and the contract reads exit 0 as "the pinned red is GONE" — so `--slow --gate`
   would have demanded deletion of a live pin. The inverse of a probe that never fires, and equally
   a lie. The exit is now the condition under which the flake occurs (`slow: ./gradlew.bat build`).
3. **The store-recoverability probe ran in CI.** It was the file's first non-`slow:` `exitProbe`,
   which contradicted the register's own contract note ("CI runs the --gate form WITHOUT --slow …
   the CI check is shape + reviewBy"). Prefixed `slow:`.
4. **The cut-short notice read the PHASE-FILTERED run.** `Sv3Main` nulls `run` once
   `live.phase === 'ended'` — correct for the feed, which is attention — but the notice needs the
   answer text, which that same feed still holds. Between the terminal and the record catching up
   (permanently, if the record fetch never lands) the reader would get "the run used all its steps
   before reaching an answer" over an answer they had just watched stream. A separate
   `liveForTurn`, matched by id WITHOUT the phase filter, now feeds the notice; `runBody`'s filter
   is untouched. This is the tempdoc's own thesis turned on itself: the notice is a claim about the
   RUN, not about which half of the window is currently drawing it.

NITs taken: the tempdoc named `sv3IsTruncated` for `sv3WasCutShort`; §I.4 claimed the suite was
"the real check" two paragraphs before recording a finding the suite missed (corrected — the
overlap files were re-read three-way by hand, and that is what earned the conclusion);
`hasTerminalText` round-tripped through `terminalTextItemId` instead of returning the item; and the
search-card fixture now asserts the search body actually rendered, so it cannot pass via the
raw-output path it was written to exclude.

NIT not taken, with the reason: `check-no-observations-shards.mjs` failing on an EMPTY
`docs/observations.d/` is not a gate bug — the gate's own remedy line says `git rm -r
docs/observations.d`, so removing the directory is what it asks for. The leftover empty directory
was this worktree's, and removing it turns the gate green.

Routed out (§O.8): `scripts/docs/docs-validate.mjs` exits 1 repo-wide with 6751 `heading-case`
findings. Pinned rather than fixed — it is not wired into CI and is not a pre-merge gate for any
subject, and the advisories on the canonical doc this branch edits (`tags`, `aliases`, H1-vs-title)
are demonstrably untouched by the diff.

### §I.6 Third merge (post-872) and what the CI lane caught

`origin/main` moved a third time (#575 residue sweep, #577 871 follow-up). One conflict —
`CLAUDE.md`, where main's #575 had made the SAME ride-along fix to the retired-inbox pointer that
this branch made independently; main's wording landed first and won. The nine-file overlap set was
re-checked as in §I.4: main's `AgentInteractionMapper` change (`withReasoning` made public) and
`ToolCallCard`/`Sv3Main` reworks are additive to this branch's carriage, and both `outputCharsToModel`
keys plus the model-visibility note survived intact in the merged tree.

**Two findings, both this branch's own, both from running the thing rather than reading it:**

1. **The lineage rule made the seam audit time out.** `productionAgentClasses()` ran a full
   `ClassFileImporter` scan of `io.justsearch.agent` on EVERY call, and a fourth rule built its own
   importer inline — four whole-package bytecode scans per test class. That fit inside the 30-second
   per-test budget only while the machine was idle, and §D.5's new rule was the fifth caller. The
   symptom was a `TimeoutException` naming the new test, which says nothing about the property it
   asserts. Fixed at the root: imported once into a lazy holder (`JavaClasses` is immutable and the
   rules only read it), so the class is now faster than before this tempdoc touched it rather than
   paying for the new rule with a raised threshold.

2. **A third ui-web suite pin broke the register's pairwise exclusion.** CI's Public-claims lane
   runs `known-state-hint.test.mjs`, which asserts every pin's `exitProbe` resolves to EXACTLY its
   own entry. 872's two ui-web pins each negatively-lookahead the OTHER's probe filter — exhaustive
   while they were a pair, stale the moment a third arrived. Each now excludes both siblings (not
   itself, which it still has to match), written out per entry so the next addition sees what it
   must update. The new pin also DROPPED its `exitProbe` and stands on `reviewBy`: the isolation run
   passes, so exit 0 would read as "the red is gone" and demand deletion of a live pin, while the
   bare full-suite run carries no filter token and cannot be attributed to one entry. For a flake
   whose trigger is machine load, `reviewBy` is the only honest exit — and the contract takes either.

The second is the same defect class as the first review's finding 1 and this tempdoc's whole
subject: a convention that was true for the case it was written against, silently false for the
next one, with nothing at the writing site saying so.

### §I.7 Delegation

Two bounded chunks ran as pinned-opus subagents in this worktree on disjoint modules — the
schema-aware token projection (§D.6, `app-inference` + `app-api`) and the read tool's three silent
wrong answers (§D.7a/b, proto → Worker → `app-services` → `ReadDocumentTool`). Everything touching
`AgentStepRunner`, `AgentSession`, `AgentEvent` or the frontend was sequenced in one lane, because
those files are each on the path of several workstreams and parallel edits would have collided.

---

## §O. Open items

Findings this workstream turned up but deliberately did not fix. Routed here per
`rule:log-pre-existing-issues` (tempdoc 872 retired the observations store) rather than logged to
an inbox nobody reads — each names the file and what the fix would be, so it can be picked up in
one step.

### §O.1 Owned by 878 (this tempdoc's own debt)

1. **The iteration-ceiling finalize has no telemetry counter.**
   `agent.budget_edge_finalize.total` is named for the budget wall, and §D.1 deliberately did not
   reuse it — folding a second terminal into a counter named for the first would silently change
   what a recorded number means, which is this tempdoc's own defect class. So ceiling-finalize
   success/failure is currently unmeasured. The fix is a `limit` dimension (`budget` | `steps`) on
   that counter, or a sibling counter: a metric-schema change across `AgentMetricCatalog`,
   `AgentTags` and the wire-format regression test.
   `modules/app-agent/src/main/java/io/justsearch/agent/AgentLlmCaller.java` →
   `attemptStepCeilingFinalize`.

2. **The effort-scaled iteration cap is designed and not shipped (§D.8).** An owner call on spend:
   raising the cap requires re-deriving `AgentBudgetPolicy.THOROUGH_MULTIPLIER`, because 859 derives
   15x *from* a cap of 10. The guard that makes the coupling fail the build shipped;
   the number did not. `modules/ui-web/src/shell-v0/controllers/AgentSessionController.ts` →
   `DEFAULT_MAX_ITERATIONS`.

3. **A `vop_*` tool's output classifies `RUNTIME` by construction (§D.5).** An operation-id
   classifier cannot see inside an FE-supplied tool, so a plugin returning document text would be
   framed as a runtime value rather than quoted. Named rather than guessed at.
   `modules/app-agent-api/src/main/java/io/justsearch/agent/api/registry/OutputLineage.java`.

### §O.2 Pre-existing, routed out

4. **`check-store-recoverability` is RED on `main`** — "persistence write site is unclassified:
   `AcquisitionStage.java`". Pinned with a dated exit as
   `store-recoverability-acquisitionstage-unclassified` in
   `scripts/agent-analytics/expected-state.v1.json`. The tracked fix: the only write the scanner
   sees is `Files.createDirectories(targetFile.getParent())`
   (`modules/app-services/src/main/java/io/justsearch/app/services/ai/install/AcquisitionStage.java:113`),
   preparing the model-download directory — directory preparation for re-acquirable model blobs, not
   a durable store. `nonDurableWriteSites` in `governance/store-recoverability.v1.json` is the likely
   bucket, beside `TempFileManager`, but the call belongs to PR #483's authors: a gate silenced by
   the wrong classification is worse than a pinned red. Introduced by `3af0e859`.

5. **Three load-sensitive test timeouts**, all seen only while several agent worktrees built
   concurrently, all `TimeoutException` rather than assertion failures — so none of them evaluated
   the property it exists to check. Each wants a budget sized to the assertion rather than to the
   machine:
   - `NdjsonInferenceTransitionLogTest.retentionPrunes` uses a **100 ms** retention window and then
     asserts the just-written record survived; when the write itself exceeds the window, the
     "current" record is pruned too and `lines.size() == 0`.
     `modules/app-inference/src/test/java/io/justsearch/app/inference/NdjsonInferenceTransitionLogTest.java:95`.
   - `OnnxEmbeddingEncoderLongDocForensicTest.longDocEmbedWithSpansMatchesBaseEmbed` and
     `OnnxEmbeddingEncoderBoundedTokenizeTest.groupBoundariesPreserveResults` — 30 s / 10 s budgets
     around two real 8192-token ONNX encoders.
     `modules/worker-core/src/test/java/io/justsearch/indexerworker/embed/onnx/`.
   - `resourceRegistry.test.ts` "produces the four expected registrations" times out at 5000 ms
     under the full ui-web suite (`vi.resetModules()` + dynamic re-import) and passes in isolation;
     its import graph contains none of 878's files. **Pinned** as
     `ui-web-resourceregistry-defaults-timeout` (same shape as the already-pinned
     `ui-web-pluginloader-module-mode-timeout`); the fix is a per-test timeout or a shape that does
     not re-transform the module graph inside one test's budget.
     `modules/ui-web/src/shell-v0/renderers/resourceRegistry.test.ts:243`.

6. **Two `app-services` integrationTest cases are wall-clock assertions** that flake when the
   machine is busy, and `./gradlew build` runs integrationTest: `LambdaMartBenchmarkTest`'s 5 ms
   p50 threshold (seen at 5.41 ms) and `KnowledgeServerIntegrationTest.fullE2E_...`'s 30 s
   worker-spawn timeout. Both passed immediately on re-run in isolation. **Pinned** as
   `app-services-integrationtest-wallclock-flaky`; the fix is a threshold sized to the property
   rather than to an idle machine, or moving the benchmark off the `build` path.

7. **`scripts/docs/docs-validate.mjs` exits 1 repo-wide** — 6751 `heading-case` findings across
   `docs/`, plus per-file `tags`/`aliases`/H1-vs-frontmatter-title advisories. Not wired into CI and
   not a pre-merge gate for any subject, so a canonical-doc edit does not inherit its red.
   **Pinned** as `docs-validate-heading-case-repo-wide`; the fix is a decision about whether
   `heading-case` is a rule this repo actually wants, before 6751 findings are mechanically applied.

8. **The shared Gradle single-lane wrapper leaks its lock.** It writes its `owner` file *inside* the
   lock directory, so the release `rmdir "$LOCK"` always fails with "Directory not empty" and the
   lock is never released by its holder; a patient poller can starve for hours (both of this
   workstream's sub-workers did, ~90 minutes each). The fix is an `"$LOCK.owner"` sibling file, or
   `rm -rf`. Separately, some agents run `tmp/gradle-lane*.sh`, which do not touch that lock at all,
   so single-lane Gradle is not actually enforced across worktrees.
   `tmp/agent-orchestration/gradle-locked.sh` (orchestration harness, untracked).
