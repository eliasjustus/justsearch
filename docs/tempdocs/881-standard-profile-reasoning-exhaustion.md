---
status: THEORIZING
created: 2026-09-01
updated: 2026-09-01
owner_session: f6617483
follows:
  - 868-agent-tool-capabilities.md §C.4b, §D.3
  - 878-agent-run-honesty-and-paging.md
  - 859-sv3-live-findings.md §7
  - 842 (chat profiles)
---

# 881 — Standard-profile delegate: reasoning-token exhaustion

**Thesis (working):** the user-facing chat profile (standard, Qwen3.5-9B) cannot run a
delegate task at defaults. The turn after `core_browse_folders` returns an error the
product itself is guessing at — *"Model failed to generate a response (possible reasoning
token exhaustion)"* — and the loop that emits that guess is holding, unread, the one fact
that would settle it: llama-server's `finish_reason`.

## Findings handed over

> Verbatim from `tmp/agent-orchestration/BRIEF-881-F.md` (orchestrator session f6617483).

### Workstream F — tempdoc 881 "Standard-profile delegate: reasoning-token exhaustion"
(Launch under the orchestrator's dev-stack lease; the agent MAY drive the stack the orchestrator started, must NOT start/stop/take it over.)

Follows 868 §C.4b/§D.3, 859 §7 (CoT leakage note), 842 (chat profiles), 878 (paging levers — must be on main first).

#### Finding handed over (live, 2026-08-26)
On the standard profile (Qwen3.5-9B) at n_ctx 4096, a delegate run of "Read three of the indexed help documents and summarize each one in two sentences." fails 2/2 on the LLM turn immediately AFTER `core_browse_folders` with the FE error *"Model failed to generate a response (possible reasoning token exhaustion). Try simplifying the query or increasing the token budget."* — before any read tool call. The compact 4B profile does not hit it. `AgentLlmCaller.java:~50` sets `maxCompletionTokens` 1024 (floor 256); `AgentStepRunner.java:~302` handles the context-size rejection; the 9B thinks 14–21 s per turn.

#### Investigate (with the live stack, compact→standard via `ai_activate {chatProfile:"standard"}`)
1. Reproduce via API (`POST /api/chat/agent`, capture SSE to a file) and via the FE. Capture the raw llama-server response for the failing turn (probe `/v1/chat/completions` directly with the run's exact messages+tools from the run journal `meta.json`, `stream:true`, `max_tokens:1024`, `chat_template_kwargs.enable_thinking` as the loop sends it): is `finish_reason` `length` with all tokens spent in `reasoning_content`? Is the tools schema (9 tools incl. two workflow tools) consuming the prompt budget (878's `applyTemplate` fix measures this)?
2. Options to design and measure: (a) `reasoning_budget`/thinking cap via `chat_template_kwargs` or llama-server's reasoning controls for the 9B on tool turns; (b) raise `maxCompletionTokens` on tool-planning turns only; (c) suppress thinking on tool-decision turns (Direction D already does this for E0a) — measure completion rate and latency for each on the rank-1 prompt, 3 runs each, compact AND standard; (d) the n_ctx default itself (`InferenceConfig.java:~130` = 4096) — quantify what 8192 buys on the 9B for this prompt and the VRAM cost (12 GB card).
3. Land the fix that makes the standard profile complete the rank-1 prompt at defaults, with the measurements in the tempdoc (numbers must be reproducible: record commands, model files, n_ctx).

#### Rules
Dev stack: use `quick_health` first; the orchestrator owns the lease — do not call `start`/`stop`/takeover; `ai_activate` profile switches are allowed. Everything else per COMMON-BRIEF.md.

## §A. Measurement (live, 2026-09-01)

### A.0 The rig — everything below is reproducible from these facts

| Fact | Value | Evidence |
|---|---|---|
| Dev stack | run `d22ff0d5`, API `http://127.0.0.1:63712`, repoRoot `F:/justsearch-public`, gitHead `f3b29de5` | `quick_health` |
| llama-server | `http://127.0.0.1:8082`, 4 slots, `n_ctx = 4096` | `GET /props`; `modules/ui-web/.dev-data/logs/llama-server.log` |
| standard model | `models/Qwen_Qwen3.5-9B-Q4_K_M.gguf` (8.95 B params, Q4_K_M) | `/props`, llama-server log |
| compact model | `models/compact/Qwen3.5-4B-Q4_K_M.gguf` (4.21 B) | same |
| `justsearch.context.size` | **4096**, from `jvm_arg` (dev-runner) and `settings.json` — not the 8192 code default (`ResolvedConfigBuilder.java:1048`) | `GET /api/debug/effective-config` |
| server reasoning cap | `--reasoning-format deepseek --reasoning-budget 512` | `LlamaServerOps.java:252-262`; log line `reasoning-budget: activated, budget=512 tokens` |
| per-call completion cap | `max_tokens = 1024` (`AgentLlmCaller.DEFAULT_MAX_TOKENS`, `justsearch.agent.max_completion_tokens` default 1024) | `AgentLlmCaller.java:49-50`, `ResolvedConfigBuilder.java:1326` |
| corpus | 679 indexed documents, index IDLE | `GET /api/status` |
| index | rank-1 prompt: *"Read three of the indexed help documents and summarize each one in two sentences."* | 868 §C.4 |

Harnesses (in-tree, gitignored under `tmp/881/`, kept out of the PR):
`run.mjs` (drives `POST /api/chat/agent`, summarises the SSE stream),
`probe.mjs` / `matrix.mjs` (replay a journal's message prefix straight at
`/v1/chat/completions`, rebuilding the tool payload exactly as
`AgentOperationEmitter.toOpenAiTool` does — `{type,function:{name,description,parameters}}`
with the i18n description key resolved from
`modules/app-api/src/main/resources/messages/registry-operation.en.properties`).
Fidelity check: the replayed prompt tokenises to **2174** prompt tokens against the
loop's own **2190** for the same turn (llama-server `task.n_tokens`) — a 16-token
delta from the loop's trailing generation prompt, i.e. the same prompt.

### A.1 End-to-end baseline, 3 runs per profile, defaults, `maxIterations: 10`, `effort: standard`

| Profile | Run | Disposition | Iterations | Tool calls | Tokens | Journal |
|---|---|---|---|---|---|---|
| standard 9B | 1 | **ERROR** `EMPTY_RESPONSE` at iter 2 | 2 | 1 | 6 698 | `ff298669` |
| standard 9B | 2 | **ERROR** `EMPTY_RESPONSE` at iter 3 | 3 | 2 | 14 036 | `4c0687db` |
| standard 9B | 3 | **ERROR** `EMPTY_RESPONSE` at iter 2 | 2 | 1 | 6 715 | `b4fe030a` |
| compact 4B | 1 | DONE (step-ceiling synthesis) | 10 | 10 | 32 282 | `e99b1ee1` |
| compact 4B | 2 | DONE (step-ceiling synthesis) | 10 | 10 | 34 188 | `1687ca12` |
| compact 4B | 3 | DONE (budget-edge finalize) | 10 | 9 | 32 286 | `4f1c8e06` |

**standard 0/3 answered, compact 3/3 answered.** 868 §C.4b's tally reproduces exactly.

### A.2 What the model actually returns on the failing turn — 868's diagnosis is refuted

Replaying `ff298669`'s failing turn (system + user + assistant`core_browse_folders{"list_files":true}` +
the tool's refusal *"list_files requires a parent_path. Omit list_files to see top-level roots."*),
3 runs, `max_tokens 1024`, `chat_template_kwargs` omitted exactly as the loop sends it on a
PRIMARY turn:

```
run 1: finish_reason=stop  content_chars=0 reasoning_chars=226 tool_call_deltas=0 usage=2174p/52c
run 2: finish_reason=stop  content_chars=0 reasoning_chars=148 tool_call_deltas=0 usage=2174p/35c
run 3: finish_reason=stop  content_chars=0 reasoning_chars=248 tool_call_deltas=0 usage=2174p/55c
```

and the reasoning channel contains, verbatim:

```
I need to first see what's at the top level of the indexed folders before I can list files.
Let me call core_browse_folders without the list_files parameter.

<tool_call>
<function=core_browse_folders>
</function>
</tool_call>
```

So: **`finish_reason` is `stop`, not `length`. The model spent 35–55 of its 1024 completion
tokens. Nothing was exhausted.** The model produced a well-formed tool call and put it
*inside the thinking block*, in the XML/"pythonic" grammar rather than the native one, and
never closed the block — so llama-server's `--reasoning-format deepseek` routed the whole
thing to `reasoning_content`, its native tool-call parser saw nothing, and the loop threw the
call away.

Falsification of each of the brief's own candidate causes, same turn, same rig:

| Candidate (BRIEF-881-F §2) | Test | Result |
|---|---|---|
| (b) raise `maxCompletionTokens` | `max_tokens: 4096`, 3 runs | **no change** — 3/3 `finish_reason=stop`, 34/36/43 completion tokens, same leak |
| (d) raise `n_ctx` 4096 → 8192 | prompt occupancy at the failing turn | **not the cause** — 2174 / 4096 tokens (53 %); the run errors with 47 % of the window free and never reaches a context gate |
| (a) per-request `reasoning_budget` | `reasoning_budget: 0` and `128` in the request body, 10 runs each | **the field is ignored by this build** — `reasoning_budget: 0` still produced 265–487 reasoning chars, so "no effect" here is "not wired", not "tried and ineffective". The only cap the codebase sets is the server flag (`--reasoning-budget 512`), which never fires: the model stops at ~40–130 tokens |
| (c) suppress thinking on tool turns | `chat_template_kwargs.enable_thinking:false`, 3 runs | **3/3 `finish_reason=tool_calls`**, 17 completion tokens, 0.3 s |

### A.3 Leak rate per turn — and the compact profile is *not* immune

Replaying every assistant-turn boundary of a real completed run (`c9c55d00`, four turns:
open, post-read, post-read, post-read), 5 runs per turn per arm. "EMPTY(recoverable)" =
the turn returned no structured call and no text, but the reasoning channel contained a
`<tool_call>…</tool_call>` block naming an available tool.

| Model | Arm | tool_calls | EMPTY, recoverable | EMPTY, unrecoverable |
|---|---|---|---|---|
| 9B standard | thinking as-shipped | 12 / 20 | **8 / 20 (40 %)** | 0 |
| 9B standard | `enable_thinking:false` | **20 / 20** | 0 | 0 |
| 4B compact | thinking as-shipped | 19 / 20 | **1 / 20 (5 %)** | 0 |
| 4B compact | `enable_thinking:false` | **20 / 20** | 0 | 0 |

Extending the 9B's as-shipped arm on the two mid-run turn shapes (post-read, post-read) by
another 10 runs each: **12/20 empty, 12/20 recoverable, 0 unrecoverable** — so on the turn
shapes that actually occur mid-run the leak rate is 20/30 (67 %), and the 40 % above is the
average once the opening turn (which never leaked, 0/5) is included.

**Cumulative across every completion sampled for this tempdoc — 129 calls against the two
models — 42 turns came back empty, and all 42 carried a recoverable `<tool_call>` block. Not
one empty turn was a genuinely content-free response.** That is the finding the fix rests on:
there was never a turn where the model had nothing to say.

The second thing that follows: 868 §C.4b's "the compact 4B profile does not hit it" is *not* a
difference in kind. The 4B leaks at 5 % per turn, which a 10-step run survives ~60 % of the
time, while the 9B's 40 % kills a 10-step run ~99.4 % of the time. Same defect, different rate
— so the fix must be profile-blind, and a per-profile knob would have been the wrong shape.

**Does the measurement describe the shipped code?** The classifier in `tmp/881/matrix.mjs`
(`recoverCommitted`) implements the same rule as the Java `recoverCommittedToolCalls`: the
`<tool_call>…</tool_call>` wrapper, then the XML `<function=NAME>` head, then a JSON body, with
the name checked against the offered tool list. One difference exists — the JS accepts a JSON
body of `{"name":X}` with no arguments while `parseToolCallObject` requires arguments or
`type:"function"` — and it cannot affect the counts, because **all 42 recovered samples took the
XML branch**, which the two implement identically. So "42/42 recoverable" is a claim about the
rule that shipped, not about a more permissive measuring stick.

The leaked grammar is stable across models and turns (8 + 1 samples,
`tmp/881/matrix-*.json`) — arguments included:

```
<tool_call>
<function=core_read_document>
<parameter=path>
f:\justsearch-public\docs\tempdocs\611-chat-composer-add-action-entrypoint.md
</parameter>
<parameter=offset_chars>
3118
</parameter>
</function>
</tool_call>
```

Values arrive untyped (`True`, `3118`, a Windows path), so the conversion to JSON arguments
has to consult the tool's own declared parameter schema.

### A.4 The completion table, and what part of it is measured

The orchestrator asked for compact-vs-standard completion on the rank-1 prompt, before and
after. The *before* half is measured end-to-end. The *after* half **cannot be measured by this
agent** and is not presented as if it were: the shared dev stack runs the main checkout's build
(§F), so the fixed loop is not on the machine that has the model. Marked accordingly.

| Profile | Arm | Rank-1 prompt, 10 steps | Basis |
|---|---|---|---|
| standard 9B | shipped | **0 / 3 answered** (ERROR `EMPTY_RESPONSE` at iter 2, 3, 2) | measured end-to-end, §A.1 |
| compact 4B | shipped | **3 / 3 answered** | measured end-to-end, §A.1 |
| standard 9B | + §C.1 recovery | every empty turn yields the call the model committed to: **42/42 recoverable, 0 unrecoverable** across 129 sampled completions | measured per-turn, §A.3 — *end-to-end run pending* |
| standard 9B | + §C.2 retry | **20/20** structured tool calls under `enable_thinking:false` | measured per-turn, §A.3 — *end-to-end run pending* |
| compact 4B | + §C.1/§C.2 | **20/20** structured, 1/20 recovered leak; no arm regressed | measured per-turn, §A.3 — *end-to-end run pending* |

The arithmetic those per-turn rates imply, stated as a projection and not as a result: at a
40 % per-turn leak the shipped loop clears ten consecutive turns with probability 0.6¹⁰ ≈
**0.6 %** on the 9B (0.95¹⁰ ≈ 60 % on the 4B), which is what §A.1's 0/3 and 3/3 look like. With
every empty turn recovered, the leak stops being a run-ending event at all. The number that
would confirm it is one live 3-run pass on a build of this branch — §F names the command.

## §B. Theorization — what is actually broken

The failing turn has **three** defects stacked, and only the third is the one the product
has been chasing.

**B.1 The loop discards a channel it already receives.** `AgentLlmCaller.callLlmWithTools`
accumulates `reasoningBuilder` and then uses it for exactly one `LOG.debug` line
(`AgentLlmCaller.java:378-383`) before dropping it. The model's committed tool call was in
there. This is the `wire-emitter-elision` shape: the information reached the process and the
process elided it.

`recoverInlineToolCalls` (`AgentLlmCaller.java:450`) exists *precisely* for this class — its
own javadoc says "Local models leak this with two grammars … and the JSON rendered as the
'answer'". It just happens to look in the wrong channel (text only) and to know the wrong two
grammars (JSON only). The defect is not that recovery is missing; it is that recovery was
scoped to the leak that had been *observed at the time* rather than to the leak *class*.

**B.2 The retry is a re-issue, not a retry.** `AgentRetryPolicy` grants `EMPTY_RESPONSE`
one retry with a 250 ms delay (`AgentRetryPolicy.java:53`), and `callLlmWithRetries` re-issues
a byte-identical request — same messages, same tools, same sampling
(`AgentLlmCaller.java:220`). Against a failure that is a property of the prompt shape rather
than of transient server state, that is not a second chance; it is a 250 ms pause before the
same answer. The measurement says so: 3/3 identical failures on replay, 2/2 in 868, 3/3
end-to-end here. A retry that changes nothing should not be called a retry.

**B.3 The terminal guesses, and the guess is wrong.** `AgentStepRunner.java:656` tells the
user *"possible reasoning token exhaustion … Try simplifying the query or increasing the
token budget"* — while `AgentLlmCaller.java:355` (`fr -> latch.countDown()`) is throwing away
`finish_reason`, the one field that distinguishes `length` (a real budget wall) from `stop`
(what actually happened). The cost of that guess is documented: it is the entire premise of
868 §D.3 and of this tempdoc's own brief, which sent a measurement campaign at
`maxCompletionTokens`, `reasoning_budget` and `n_ctx` — three levers that A.2 shows have
nothing to do with it. A diagnostic that invents a cause is worse than one that says "I don't
know", because it is *actionable* in the wrong direction.

## §C. Design

Three changes, one per defect. Each is independently justified by A; none of them is a
knob the owner has to tune.

### C.1 Read the channel the model wrote in (fixes B.1)

Extend the existing recovery layer, in place — no new component:

1. **New grammar.** Teach the scanner the `<tool_call> <function=NAME> <parameter=K>…</parameter> </function> </tool_call>`
   form alongside the two JSON forms it already knows. Arguments are typed against the tool's
   own declared `parameters` JSON schema (`boolean` ← `true/True/1/yes`, `integer`/`number` ←
   parsed, everything else ← the raw string, trimmed). An undeclared key or a failed
   coercion keeps the raw string rather than dropping the argument — the tool's own
   validation is the authority on whether that is acceptable, not the recovery layer.
2. **New channel, conservatively gated.** Scan the reasoning channel *only when the turn
   would otherwise be a dead end* — no structured tool calls **and** no text content — and
   *only* for spans inside an explicit `<tool_call>…</tool_call>` wrapper naming an available
   tool. Both restrictions matter: thinking routinely *discusses* calls it decides against
   ("I could search first, but…"), and acting on a hypothetical would be worse than the bug.
   The wrapper is the model's own commit marker, and the empty-turn gate means the only
   alternative on the table is discarding the turn entirely.

The asymmetry is deliberate and is the design's load-bearing judgement: the **text** channel
keeps its existing permissive rule (any recognised grammar, always scanned, span stripped so
it cannot render as the answer), while the **reasoning** channel gets the strict rule
(wrapper-delimited only, empty-turn only, nothing stripped — it has already streamed to the
FE and rewriting it would make the transcript lie).

### C.2 Make the retry change something (fixes B.2)

When a call comes back empty **and** recovery found nothing, the second attempt goes out with
`enableThinking = false` instead of being a byte-identical re-issue. Measured: 40/40 turns
across both models returned a structured tool call under that arm, at lower latency. This is
not a new policy knob — `SamplingParams.withEnableThinking(false)` is already the codebase's
established remedy for exactly this shape (three sites: E0a, `DECIDING`, handoff escalation —
`AgentLlmCaller.java:270`, `AgentStepRunner.java:596`, `:639`); it is applied here at the one
moment where the alternative is a known-deterministic repeat failure.

Explicitly **not** done: suppressing thinking on all PRIMARY tool-planning turns. That would
also fix the symptom (20/20, and ~0.7 s faster per turn), and it is the cheaper patch — but it
pays for a 40 %-of-turns bug by removing the reasoning from *100 %* of turns on the profile
whose reason to exist is that it reasons better. C.1 keeps the thinking and recovers the call;
C.2 gives up the thinking only on a turn that has already failed once. Recorded so the owner
can overrule it: the blanket-suppression numbers are in A.3, and it is one line.

### C.3 Say what happened (fixes B.3)

`LlmCallResult` carries `finishReason`, captured from the stream's terminal callback that
already receives it. The empty-response terminal then states facts instead of a hypothesis —
`length` says the answer was cut off and names the token cap; `stop` with reasoning-only says
the model produced only reasoning and did not answer; anything else says what it was. The
WARN log gains the tail of the reasoning channel, so the next agent to look at this sees the
leaked call rather than having to run a campaign to find it.

### What this design orphans

- **868 §D.3's diagnosis** ("Qwen3.5-9B fails … *reasoning token exhaustion*") and the
  identically-worded line in 868's routed-open-items list. Both are refuted by A.2 and are
  annotated in place in the same PR — a wrong diagnosis left standing in the highest-numbered
  neighbouring tempdoc is exactly the false authority `retire-with-a-sweep` is about.
- **The string "possible reasoning token exhaustion"** — deleted, not reworded; there is no
  second copy (`grep`: one hit, `AgentStepRunner.java:656`).
- Nothing else: no config key, gate, baseline, or FE branch depends on the old text
  (`errors.EMPTY_RESPONSE` has no i18n entry — see §F).

## §D. Plan

| # | Change | File | Test |
|---|---|---|---|
| D1 | `LlmCallResult` gains `finishReason` | `LlmCallResult.java` | compile + D5 |
| D2 | Capture `finish_reason` from the terminal callback instead of discarding it | `AgentLlmCaller.java` (`fr -> latch.countDown()`) | D5 |
| D3 | XML `<tool_call>`/`<function=…>`/`<parameter=…>` grammar + schema-typed arguments, recovered from the text channel alongside the JSON grammars | `AgentLlmCaller.java` (`scanInlineToolCallJson` → a merged scan) | unit, verbatim captured strings |
| D4 | Reasoning-channel recovery, gated on (no structured calls ∧ no text) and on the `<tool_call>` wrapper | `AgentLlmCaller.callLlmWithTools` | unit: recovers when empty; **does not** recover a hypothetical when text is present; does not recover an unwrapped JSON blob |
| D5 | `EMPTY_RESPONSE` retry re-issues with `enableThinking=false` | `AgentLlmCaller.callLlmWithRetries` | unit: attempt 2's `SamplingParams.enableThinking() == false` |
| D6 | Honest empty-response terminal + reasoning tail in the WARN | `AgentStepRunner.java:649-667` | unit: `length` vs `stop` produce different, factual text |
| D7 | Annotate 868 §D.3 + its routed open item as refuted, with a pointer here | `868-agent-tool-capabilities.md` | n/a |

Verification list: `spotlessApply` → `build -x test` → full `test` → the agent-module suites
named in §E. Live end-to-end confirmation of the *fixed* loop is explicitly **not** in this
agent's reach (see §F).

## §E. What shipped, and what verified it

Shipped (commit `fix(881): recover the tool call the model leaks into its reasoning channel`):

| Plan item | Landed as |
|---|---|
| D1/D2 | `LlmCallResult.finishReason`; the stream's terminal callback stores it instead of discarding it (`AgentLlmCaller.java`) |
| D3 | `ToolSchemas` (name + declared parameter types, projected from the same tool list the loop sends) and the XML grammar in `scanToolCallSpans`; the wrapper span wins over an inner JSON object so no `<tool_call>` husk survives a strip |
| D4 | `recoverCommittedToolCalls` on the reasoning channel, gated on wrapper + otherwise-empty turn |
| D5 | `callLlmWithRetries` retries with `enableThinking(false)` |
| D6 | `AgentStepRunner.emptyResponseMessage(finishReason)`; the "reasoning token exhaustion" string is deleted |
| D7 | 868 §D.3 and its routed open item annotated as refuted |

**Two things the ArchUnit dead-code gate caught, and what they were worth.** `app-launcher`'s
`UnreferencedCodeTest` failed with *"Method callLlmWithTools in AgentLlmCaller is never
referenced"* and *"Method ofNames in ToolSchemas is never referenced"*. Neither was
gate-noise:

- The sampling-resolving 3-argument `callLlmWithTools` overload really had become dead once D5
  made `callLlmWithRetries` resolve sampling itself. Deleting it is not tidying — it makes 878
  review B2's invariant structural: there is now exactly one tool-bearing entry point and it
  cannot be called without stating its sampling. The retry *needs* different sampling on its
  second attempt, which the overload could not express.
- `ToolSchemas.ofNames` was a names-only convenience that existed **only so the pre-existing
  tests would not have to change** — production-code scaffolding for a test, which is the thing
  the gate is for. Deleted; those tests now build a real tool payload and go through the same
  `ToolSchemas.of` projection production uses, which is a better test than it was before.

Verification run:

| Command | Result |
|---|---|
| `gradle-locked.sh spotlessApply` | BUILD SUCCESSFUL |
| `gradle-locked.sh build -x test` (compile + Spotless + PMD) | BUILD SUCCESSFUL in 34s |
| `gradle-locked.sh :modules:app-agent:test` | BUILD SUCCESSFUL — `AgentLlmCallerTest` 19/0/0/0, `AgentLoopServiceTest` 125/0/0/0 |
| `gradle-locked.sh :modules:app-launcher:test` | BUILD SUCCESSFUL (the two ArchUnit violations above, fixed) |
| `gradle-locked.sh test` (full suite) | BUILD FAILED in 10m 27s on **two** classes, both environmental — see below |
| `node scripts/governance/run.mjs --gate register-guard-resolution --mode gate` | pass |
| `node scripts/governance/run.mjs --gate hook-integrity --mode gate` | pass |

The two full-suite failures, interrogated rather than waved through:

- `worker-core` `OnnxEmbeddingEncoderLongDocForensicTest.longDocEmbedWithSpansMatchesBaseEmbed`
  — `TimeoutException` after 30 s. This is **already a dated pin**
  (`expected-state.v1.json` → `worker-core-onnx-longdoc-forensic-timeout`, added 2026-08-31):
  load-dependent, and this run had llama-server holding the GPU plus a second worktree holding
  the Gradle lane, which is exactly the pinned condition. `modules/worker-core` has no
  dependency on `modules/app-agent` and zero files in this diff.
- `app-services` `WatchedRootScanCollectionTest$ProductionWireForwarding` — failed at **close**,
  not at assert (`IOException: Failed to delete temp directory …junit-…`), i.e. every assertion
  passed and Windows still held the handle. Green on an immediate targeted re-run (3/0/0/0 +
  1/0/0/0) on the same tree. New dated pin added:
  `expected-state.v1.json` → `app-services-junit-tempdir-delete-race`.

No pre-merge check in CLAUDE.md's table has this diff's subjects (`modules/app-agent/**`,
one javadoc line in `modules/app-inference`) — no contracts, SSOT, ui-web, workflow, catalog,
seam-registered, or surface-registry file is touched; `governance/logic-seams.v1.json` names
neither changed class.

## §F. What this slice does NOT do, and what still needs a live pass

**The after-fix end-to-end run is not in this agent's reach, and is not claimed.** The shared
dev stack runs the **main checkout's** build (`quick_health` → `provenance.repoRoot:
F:/justsearch-public`, `gitHead: f3b29de5`); this work lives in a worktree, and the common brief
forbids starting a stack or writing to main. So §A's *before* numbers are real end-to-end runs
and the *after* evidence is turn-level: 40/40 sampled turns produce a recoverable or structured
call under the arms this slice ships, plus unit tests over the verbatim captured strings. The
one measurement left is the orchestrator's: with this branch built, run the rank-1 prompt 3× on
`chatProfile: "standard"` and confirm a non-ERROR disposition, then 3× on `compact` to confirm
no regression. `static-green ≠ live-working` applies and is why this paragraph exists.

Deliberately not done, each with its reason:

- **Blanket thinking suppression on PRIMARY turns.** Measured and rejected in §C.2 — it trades
  100 % of the reasoning for a 40 %-of-turns bug. The numbers are in §A.3 if the owner wants it.
- **Raising `justsearch.agent.max_completion_tokens` or `justsearch.context.size`.** Both were
  measured (§A.2) and neither is implicated. Changing them here would have "fixed" nothing while
  spending VRAM, and would have left the real defect in place under a green run.
- **A per-chat-profile knob.** `ChatModelProfile` carries only `id`/`modelFile`/`mmprojFile`
  (`ChatModelProfile.java:35-38`) and nothing profile-specific was needed: §A.3 shows the defect
  is a rate difference, not a kind difference, so the fix is profile-blind by design.

Open items this slice found and did not take (routed here, per `log-pre-existing-issues`):

- [ ] The `<tool_call>` XML the model leaks into its thinking has **already streamed to the FE**
  as `ReasoningChunk` events by the time recovery runs, so a reader now sees the raw markup in
  the thinking panel *and* a correct tool card. Honest but ugly. Fixing it means filtering the
  reasoning stream, which is a presentation-authority change (859 §7's CoT-leakage question),
  not a loop change — `AgentLlmCaller.java` reasoning callback / `UnifiedChatView` thinking panel.
- [ ] `AgentEventPayloads` puts `i18nKey: "errors.EMPTY_RESPONSE"` on the wire and **nothing in
  `modules/ui-web` consumes it** (no such key exists); `UnifiedChatView.ts:5448-5457` renders the
  raw backend string. So the backend prose IS the user-visible text — which is why §C.3 treats it
  as user-facing copy — but the i18nKey is inert and either wants an entry or wants deleting.
- [ ] `InferenceConfig.java:96` javadoc says `JUSTSEARCH_CONTEXT_SIZE … (default: 4096)`; the
  resolved default is **8192** (`ResolvedConfigBuilder.java:1048`). Fixed in place as a
  ride-along (verified one-line doc fix).

## Status

Implementing.
