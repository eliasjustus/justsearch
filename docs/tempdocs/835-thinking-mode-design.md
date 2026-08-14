# 835 — Thinking mode: probe protocol and outcome-conditional design

```
status: PROBES COMPLETE — design settled, implementation handoff ready (§9)
decision: finite reasoning budget ON by default (512); branch 2a-finite
created: 2026-08-14
updated: 2026-08-14
related: 833 §W5 + finding 3 (the workstream this designs), 822 (the Search v3
  window and its no-dead-controls law), 227 §E3 (the prior negative result on
  budget −1), 236 (llama-server upgrade notes on enable_thinking), 208 (the
  shared max_tokens exhaustion bug), 682 (the build-pin marker this extends)
```

Every `file:line` below was re-verified against the working tree while writing
this document. Where a claim is *inherited* from an older tempdoc rather than
re-verified in code, it is labelled as such — those are hypotheses the probes
must retest, not facts.

---

## 0. What this document decides, and what it defers

The reasoning chain — model → SSE `reasoning_chunk` → shape event schema → the
window's reasoning block — is built at every hop and inert in the product. This
document does three things:

1. Turns the two open questions into an **executable probe protocol** another
   person can run under a dev-stack lease without re-deriving any of this.
2. Pre-commits a **decision tree**, so implementation begins the hour the probes
   land instead of restarting the design.
3. Designs the parts that are **outcome-independent** — the build-compatibility
   floor, the streaming think-tag defence, the token-budget measurement — because
   those are needed on every branch.

It deliberately does not design the frontend consequences; §6 names those seams
and stops.

---

## 1. Verified state of the chain

| Hop | Where | Verified state |
|---|---|---|
| Launch flag | `modules/app-inference/src/main/java/io/justsearch/app/inference/LlamaServerOps.java:242-254` | When `useThinking` is on, the command gets `--reasoning-format deepseek` **and** `--reasoning-budget <n>`, where `n` is read from `ConfigStore.global().get().ai().reasoningBudget()` at process-launch time. |
| Config default | `modules/configuration/src/main/java/io/justsearch/configuration/resolved/ResolvedConfigBuilder.java:1022` | `resolveInt("justsearch.llm.reasoning_budget", 0)` — **0, i.e. reasoning disabled**, unless a source overrides it. `justsearch.llm.use_thinking` defaults to `true` (`:1020`), so `--reasoning-format deepseek` is always present. |
| Per-request lever | `modules/app-inference/src/main/java/io/justsearch/app/inference/OnlineModeOps.java:610-615` (streaming/tools) and `:842-846` (non-streaming) | When `SamplingParams.enableThinking()` is non-null, the request body carries `chat_template_kwargs: {"enable_thinking": <bool>}`. Null omits the field entirely. |
| Request parsing | `modules/app-services/src/main/java/io/justsearch/app/services/conversation/ConversationEngine.java:780-786` | `parseSamplingParams` returns sampling params **only** when the body has a boolean `enableThinking`, and the object it builds pins `temperature 0.8 / top_p 0.95` alongside it. |
| Reasoning parse | `OnlineModeOps.java:686-692` | `delta.reasoning_content` from each SSE frame is forwarded to `onReasoningChunk`. |
| Event emit | `ConversationEngine.java:494` | `reasoning -> sink.accept(new SseEvent("reasoning_chunk", Map.of("text", reasoning)))`. |
| Shape schema | `modules/app-services/src/main/java/io/justsearch/app/services/conversation/shapes/RAGAskShape.java:46-52` | `reasoning_chunk` is a declared event of `core.rag-ask`. |
| Wire format | `modules/ui/src/main/java/io/justsearch/ui/api/SseWriter.java:86-94` | `event: reasoning_chunk\ndata: {"text":"…"}\n\n`. |
| Window render | `modules/ui-web/src/shell-v0/views/search-v3/Sv3Main.ts:1141-1155` | Live controller while streaming, recorded blocks once settled; component `jf-reasoning-block`. |
| Effort rungs | `modules/ui-web/src/shell-v0/views/search-v3/sv3-ask.ts:139-148` | `quick → {enableThinking:false, maxTokens:512}`, `standard → {}`, `thorough → {enableThinking:true, maxTokens:3072, topK:12}`. |

**The one gap**: the launch flag is process-lifetime and server-wide, and it is
`0`. Whether the per-request kwarg can lift a request out of that is unverified —
that is Probe A.

**Two facts that sharpen the question**, both re-verified:

- The **per-request *disable*** direction is already in production use and
  therefore known to do something: `AgentStepRunner.java:458` and `:498` call
  `SamplingParams.AGENT.withEnableThinking(false)`, as does
  `AgentLlmCaller.resolveAgentSampling` (`AgentLlmCaller.java:147-159`). Nothing
  in the repository uses the per-request ***enable*** direction. The one lever the
  window's Thorough rung pulls is the one nobody has exercised.
- The bundled build already emits stray think tags at budget 0. The agent path
  carries a strip whose comment states the cause verbatim: *"Also strip lone
  opening/closing think tags (model outputs `</think>` with `--reasoning-budget
  0`)"* — `AgentLlmCaller.java:305-307`. The **ask path has no equivalent**: the
  only think-tag strip in the inference layer is on the non-streaming request
  (`OnlineModeOps.java:57`, `:885-893`), and the streaming path forwards
  `delta.content` to the sink unmodified (`:679-683`).

---

## 2. The probes, as an executable protocol

### 2.1 How the reasoning budget is actually settable — traced, not assumed

`justsearch.llm.reasoning_budget` is an `EnvRegistry` entry
(`modules/configuration/src/main/java/io/justsearch/configuration/EnvRegistry.java:116-117`),
which means `contributeEnvRegistry` (`ResolvedConfigBuilder.java:221-231`)
registers exactly three sources for it:

| Ordinal | Source | Concretely |
|---|---|---|
| 500 | JVM system property | `-Djustsearch.llm.reasoning_budget=-1` |
| 400 | Environment variable | `JUSTSEARCH_REASONING_BUDGET=-1` |
| 100 | Built-in default | `0` |

Ordinals are declared at `ResolvedConfigBuilder.java:55-76`; the first non-blank
source in descending ordinal order wins (`resolve`, `:750-779`).

Three negative findings that make the protocol concrete rather than hopeful:

- **There is no settings.json path.** `ConfigStoreRebuilder.contributeUiSettings`
  (`modules/app-services/src/main/java/io/justsearch/app/services/config/ConfigStoreRebuilder.java:64-97`)
  enumerates every key the UI settings store contributes at ordinal 300; the
  reasoning budget is not among them. So today the budget cannot be changed
  without restarting the Head.
- **There is no YAML path.** The only occurrence of the key in
  `ResolvedConfigBuilder` is the `resolveInt` read at `:1022`; nothing `put`s it
  from YAML.
- **The Gradle `runHeadless` path will not forward it.** `HEADLESS_AI_ENV_VARS`
  (`modules/ui/build.gradle.kts:2006-2044`) is the explicit forward list and does
  not include `JUSTSEARCH_REASONING_BUDGET`; `runHeadlessEval`'s contract
  whitelist-filters the environment. Do not run these probes through the Gradle
  run tasks.

**The dev-runner path does work.** The Head is spawned with `...process.env`
spread into its environment (`scripts/dev/dev-runner.cjs:1336-1382`), so any
`JUSTSEARCH_*` variable present when the dev-runner is invoked reaches the Head.
`JAVA_OPTS` is also honoured — `buildHeadJavaOpts` prepends the caller's existing
value (`scripts/dev/dev-runner.cjs:703-716`) — so the system-property form works
too, and is the form this project prefers on Windows.

**The MCP `start` tool cannot inject either.** Its input schema
(`scripts/dev/justsearch-dev-mcp/schemas.mjs:89-117`) has no env or JVM-opts
field. So the probe run must start the stack by invoking the dev-runner directly
with the variable set. That still registers the shared lease correctly — the
dev-runner is the lease authority, and it resolves the owning session from
`JUSTSEARCH_AGENT_SESSION_ID` or, failing that, from
`tmp/agent-telemetry/current-session-id` (`scripts/dev/dev-runner.cjs:529-539`).

**Fallback lever, if the environment route is blocked for any reason:** start
`llama-server.exe` by hand on the configured port with the desired flags and let
the Head adopt it. Adoption is a supported path —
`LlamaServerOps.adoptExistingServerIfPresent` (`:440-476`) probes `/health`, then
validates `/props` before adopting. Treat this as a cross-check, not the primary
route: an adopted server has no process handle (`:478-484`), and adoption skips
the very launch-argument path the design is about.

### 2.2 Preconditions common to both probes

1. Hold the shared dev-stack lease for the whole run; declare a lease duration
   long enough to cover two full stack restarts plus queries.
2. Start the stack from the dev-runner with the intended budget in the
   environment, e.g. (Git Bash):
   ```bash
   JUSTSEARCH_REASONING_BUDGET=0 JUSTSEARCH_SERVER_PORT=<free port> \
     node scripts/dev/dev-runner.cjs start --clean none --json
   ```
   The explicit `JUSTSEARCH_SERVER_PORT` is not optional — see §2.2a.
   (`--json` prints exactly one JSON object; the process is a foreground
   supervisor, so run it detached and tear it down explicitly.)
3. Resolve the API base from the manifest, never from a remembered port:
   `<dataDir>/runtime/manifest.json` → `.head.apiBaseUrl`.
4. Bring inference online (the ask path needs a loaded model) and confirm
   `.ai.phase == "READY"` in the same manifest.
5. **Pre-check the resolved value before sending anything** — this is what
   converts "the probe measured the default again" from a silent failure into an
   immediate one:
   ```bash
   curl -s "$BASE/api/debug/effective-config" \
     | jq '.resolvedConfig[] | select(.key=="justsearch.llm.reasoning_budget")'
   ```
   The response carries the winning value, the winning source name and ordinal,
   and every candidate considered
   (`modules/ui/src/main/java/io/justsearch/ui/api/EffectiveConfigController.java:169-183`).
   For a budget of −1, `sourceName` must read `env_var` (or `jvm_arg`), not
   `default`.
6. **Confirm the flag reached the process.** The launch command is logged in
   full at DEBUG (`LlamaServerOps.java:324`, `"Starting server with command: {}"`)
   and the dev-runner sets the Head's log level to DEBUG by default
   (`scripts/dev/dev-runner.cjs:1355`). Grep the run's backend stdout log for
   `Starting server with command:` and read the `--reasoning-budget` argument off
   it. A pre-check that stops at step 5 proves what the Head *resolved*, not what
   llama-server was *launched with*; both are needed.

### 2.2a Hazard the pre-check does NOT cover: silent server adoption

Found while attempting the campaign, and it invalidates a probe run if ignored.

**The llama-server port is not per-instance.** When `justsearch.server.port` is
unset — the default, `0` (`ResolvedConfigBuilder.java:947-948`) — the port is
derived as a **constant 8081** (`LocalApiServer.resolveLlamaServerPort`,
`modules/ui/src/main/java/io/justsearch/ui/api/LocalApiServer.java:705-713`; it
falls to 8082 only when the API port is itself 8081). Two Head processes on one
machine therefore target the *same* llama-server port, regardless of their API
ports being distinct.

Combined with adoption (`LlamaServerOps.adoptExistingServerIfPresent`, `:440-476`
— probes `/health`, validates `/props`, adopts), this produces a failure the
two-stage pre-check in §2.2 **cannot detect**:

- If another llama-server is already healthy on 8081 when this stack goes online,
  this Head adopts it and **launches no process of its own**. `/api/debug/effective-config`
  still reports the intended budget (it reports what the Head *resolved*), and the
  backend log contains **no** `Starting server with command:` line to contradict
  it — because no command was run. The probe would then measure a foreign server's
  flags while every check says the configuration took.
- The reverse is equally damaging to others: a probe server started with
  `--reasoning-budget -1` will be silently adopted by any other Head that comes
  online afterwards, applying an experimental flag to somebody else's run.

**Mandatory third check, before every probe arm.** Confirm this stack *owns* its
llama-server rather than having adopted one:

1. Before starting, assert nothing is listening on 8081/8082 and no
   `llama-server.exe` exists.
2. After activation, assert the backend log **does** contain
   `Starting server with command:` with the expected `--reasoning-budget` value —
   its *absence* now means "adopted", not "not logged".
3. Adoption also logs a distinctive warning (`:487-491`, *"llama-server already
   responding on port … Using existing instance (no process handle)"*). Treat any
   occurrence as a failed probe run, not a warning.

Isolating the port explicitly (`JUSTSEARCH_SERVER_PORT=<free port>` in the
dev-runner environment, alongside the budget) removes the hazard at the source and
should be part of every probe invocation.

### 2.3 The request

The window dispatches to `/api/chat/dispatch` with the shape id in the body
(`sv3-ask.ts:369`, `ChatController.dynamicHandler` at
`modules/ui/src/main/java/io/justsearch/ui/api/ChatController.java:103-113`);
the body shape comes from `buildRequestBody`
(`modules/ui-web/src/shell-v0/views/unifiedChatRequest.ts:68-107`) plus the
effort parameters merged in at `sv3-ask.ts:278-284`. The exact equivalent:

```bash
curl -N -s -X POST "$BASE/api/chat/dispatch" \
  -H 'Content-Type: application/json' \
  -d '{"shapeId":"core.rag-ask",
       "question":"<a question the corpus can actually answer>",
       "docIds":[],
       "conversationId":"probe-thinking-1",
       "enableThinking":true,
       "maxTokens":3072}'
```

Notes that matter for reading the result:

- `docIds: []` is the documented open-retrieval fallback, not an empty scope.
- Omitting `enableThinking` entirely is a *third* case, not a synonym for
  `false`: `parseSamplingParams` then returns `null` and the request carries no
  `chat_template_kwargs` **and** no temperature/top-p pin
  (`ConversationEngine.java:780-786`). Every probe must run all three arms —
  `true`, `false`, absent — or it will attribute a sampling-pin effect to the
  thinking switch.
- An HTTP 423 means the conversation store is locked
  (`ChatController.java:152-160`); unlock before probing.

### 2.4 The observables

Read the raw SSE stream, not a rendered UI. Decisive signals, in order:

| Signal | Meaning |
|---|---|
| One or more `event: reasoning_chunk` frames | Reasoning happened and was routed to its own channel. **This is the pass condition.** |
| Zero `reasoning_chunk`, and no think markup in `event: chunk` text | Thinking was suppressed cleanly. |
| Zero `reasoning_chunk`, but `<think>` / `</think>` appears inside `event: chunk` payloads | **The leak case** — the template rendered thinking, the deepseek parser did not claim it, and nothing on the ask path strips it. This outcome is a live product defect independent of the design. |
| `event: done` with an empty or truncated answer | Reasoning consumed the shared token budget. Cross-reference the completion-token count. |

A minimal counter over the stream (frames, not bytes) is enough:

```bash
… | tee /tmp/probe.sse | grep -c '^event: reasoning_chunk'
grep -c '<think>' /tmp/probe.sse
```

### 2.5 Probe A — does the per-request kwarg lift a request out of a zero budget?

Stack started with the budget left at its default (`0`); confirm via §2.2 step 5
that `sourceName` is `default` and the value is `0`.

| Arm | Body | Question answered |
|---|---|---|
| A1 | `enableThinking: true` | Can a request turn thinking on against a server default of off? |
| A2 | `enableThinking: false` | Baseline: no reasoning, clean content. |
| A3 | field absent | Baseline: what the Standard rung actually gets today. |

Run each arm at least three times with the same question — thinking is
probabilistic per turn, and a single quiet turn is not evidence of suppression.

**Outcomes.** `A1` yields `reasoning_chunk` frames → *per-request enable works*.
`A1` yields none and no think markup → *the server flag wins* (the expected
result, inherited from the upstream expression quoted in tempdoc 236 §4.1,
`enable_thinking = use_jinja && reasoning_budget != 0 && …` — an AND, which a
request-level kwarg cannot satisfy on the middle term). `A1` yields no
`reasoning_chunk` but think markup inside `chunk` → *leak*, and the Thorough rung
is not merely inert but actively degrading answers today.

Record `A3` carefully: it is the current production behaviour of the default
rung, and every later comparison is against it.

### 2.6 Probe A0 — does this build accept a *finite* budget?

Cheap, runs before A, and opens or closes an entire design branch. The prior
investigation (tempdoc 227 §E3, on build b8185) found that `--reasoning-budget`
accepted only `-1` or `0` and rejected arbitrary values with `error while
handling argument "--reasoning-budget": invalid value`. The bundled build is now
**b8571** (`modules/ui/build.gradle.kts:367`; the running stack's manifest
reports `ai.serverBuildActual: "b8571"`), so that finding is stale by hundreds of
builds and must be retested.

Protocol: start the stack with `JUSTSEARCH_REASONING_BUDGET=512` and check
whether llama-server starts. The rejection string is already known and is already
pattern-matched elsewhere in the repository
(`scripts/ci/build-agent-e3-decision.mjs:260-261`) — reuse that exact string.

If a bounded budget is accepted, "thinking with a predictable token cost" becomes
available and most of §4's risk evaporates. That is why this probe is ranked
first in §7 despite being an afterthought to the original two.

### 2.7 Probe B — does the whole chain light up at budget −1, and can a request opt out?

Stack restarted with `JUSTSEARCH_REASONING_BUDGET=-1`; confirm via §2.2 steps 5
and 6.

| Arm | Body | Question answered |
|---|---|---|
| B1 | `enableThinking: true`, `maxTokens: 3072` | Does `reasoning_chunk` arrive at all? |
| B2 | `enableThinking: false`, `maxTokens: 512` | Does the per-request *disable* suppress it? (Expected yes.) |
| B3 | field absent, `maxTokens` absent (engine default 1024) | **The regression retest.** |
| B4 | B1, then reload the window and re-read the same conversation | Does the reasoning survive the record? (§6 predicts: no.) |

**B3 is not optional.** It reproduces exactly the configuration that previously
failed: unlimited reasoning against the engine's shared 1024-token ceiling
(`ConversationEngine.java:65`, `DEFAULT_MAX_TOKENS = 1024`), which produced empty
answers on the agent battery. If B3 shows empty or truncated answers, then
"flip the default to −1" is disqualified as a standalone change — see §3.

**End-to-end leg.** B1 must also be confirmed through the window itself, not just
on the wire: send the same question from the Search v3 composer on the Thorough
rung and confirm a reasoning block renders. The wire proves plumbing; only the
rendered block proves the feature.

### 2.8 Teardown

Stop the stack through the dev-runner (`stop`/`cleanup`), and confirm the next
start returns to a `default`-sourced budget of `0` — a stale exported variable in
a shell that later starts the shared stack would silently apply this experiment
to somebody else's measurements.

---

## 3. Decision tree

### Branch 1 — Probe A1 produces reasoning (per-request enable works)

The best case, and it needs the least change.

- **Server default stays 0.** Quick asks pay nothing; nothing regresses; the
  catastrophic-budget risk never materialises because unlimited reasoning is only
  ever requested alongside an explicit token budget.
- **Thorough becomes a real control** exactly as written; its promise ("Thinks
  first") becomes true and no copy changes.
- **Standard stays parameterless** — it sends nothing and inherits a no-thinking
  server, which matches its stated meaning ("leaves every setting to the model").
- Remaining work is small: the build-compatibility floor (§5), the streaming
  defence (§5), the token measurement to justify Thorough's 3072 (§4), and the FE
  seams (§6).
- **No settings surface.** A user-facing thinking toggle would be a second
  authority over a fact the effort rung already owns.

### Branch 2 — Probe A1 produces nothing (server flag wins)

The expected case. `enableThinking: true` is inert for its stated purpose, and
the honest framing is precise: the *rung* is not dead — `maxTokens: 3072` and
`topK: 12` are both live parameters — but the **first clause of its description
is a false promise**, and under the window's no-dead-controls law a false promise
is the same defect as a dead control.

Three sub-options, in preference order:

**2a — Invert the default, and make the per-request lever the *off* switch.**
Set the budget to −1 (or, if Probe A0 passed, to a finite value) and have the
rungs *suppress* rather than enable. This aligns the product with the direction
llama.cpp demonstrably supports and that this codebase already relies on
(`AgentStepRunner.java:458`). It requires one mandatory companion change:
**Standard must stop being parameterless.** Standard currently sends `{}`
(`sv3-ask.ts:146-147`), so under a −1 default it would inherit unlimited
reasoning against a 1024-token ceiling — precisely the B3 configuration. Under 2a,
Standard sends `enableThinking:false` explicitly, or the engine default rises
(§4). Note the honest cost, already documented at `sv3-ask.ts:83-89`: sending any
`enableThinking` value also pins temperature 0.8 / top-p 0.95, so Standard would
stop being the rung that leaves sampling entirely to the backend. That is a real
product change, not a mechanical one, and it is the strongest argument for 2b.

**2b — Make the budget runtime-changeable, and treat thinking as a mode.**
`LlamaServerOps` re-reads the budget from `ConfigStore` at every launch
(`:243`), and `/api/inference/reload` already restarts llama-server when online
(`InferenceHandlers.java:510-541`, `RestartPolicy.RESTART_IF_ONLINE`). So adding
the key to the settings.json contribution list
(`ConfigStoreRebuilder.java:64-97`) would make the budget changeable at runtime
with a few-second inference restart, instead of an application restart. This
turns thinking into an opt-in mode with its own token floor, and leaves Quick /
Standard / Thorough as pure per-request effort. Cost: a mode and a rung both
touching the same user-visible behaviour is a two-authority smell, and the
restart is visible. Worth designing only if 2a's Standard change is judged too
expensive.

**2c — Retire the promise.** Drop `enableThinking: true` from Thorough and reword
its description to the two things it really does (longer answer, more passages).
This is the *minimum* honest change and should be shipped **regardless of which
option is chosen**, if the chosen option cannot land in the same change — a false
promise should not outlive the sitting that discovered it.

### Branch 3 — Probe A shows the leak (think markup inside `chunk`)

Branch 2's decision still applies, and the streaming defence in §5 is promoted
from prudent to required and blocking: the Thorough rung is currently shipping
raw markup into answer text. In this branch, 2c ships immediately as a stop-gap
while the real option lands.

### Cross-branch: what happens to Quick / Standard / Thorough

| Rung | Today | Branch 1 | Branch 2a |
|---|---|---|---|
| Quick | `enableThinking:false, maxTokens:512` | unchanged (suppression already effective) | unchanged — and now load-bearing |
| Standard | `{}` | unchanged | **must change** — explicit suppression, or an engine default that can absorb reasoning |
| Thorough | `enableThinking:true, maxTokens:3072, topK:12` | unchanged; promise becomes true | unchanged; promise becomes true |

---

## 4. Token and latency budget

**Reasoning tokens and answer tokens share one ceiling.** The engine parses
`maxTokens` from the body with a default of 1024
(`ConversationEngine.java:65`, `:771-778`) and passes it as the request's
`max_tokens`; llama-server spends it on reasoning first and answer second. That
is the documented mechanism behind the earlier empty-answer failures, and it is
unchanged in the current code.

**The number needed to size this is captured and then dropped.** `AiUsage`
carries `completionTokens` (`modules/app-api/src/main/java/io/justsearch/app/api/OnlineAiService.java:34`),
but the engine forwards only `promptTokens` and `totalTokens` onto the `done`
payload (`ConversationEngine.java:389-395`). **Design item: put
`completionTokens` on the done payload.** It is a one-line projection of a value
already in hand, it is the denominator for every budget decision below, and
without it "how much of the budget did thinking eat?" can only be estimated from
accumulated reasoning text length.

**What decides Thorough's budget.** Not a guess — a measurement, run once the
probes have settled which branch applies:

- A fixed question set (~20 questions spanning short-factual to
  multi-source-synthesis) against a stable corpus.
- Per question, per arm (thinking on/off), record: `completionTokens`,
  accumulated `reasoning_chunk` character count, answer character count,
  wall-clock from dispatch to `done`, and a binary empty-or-truncated flag.
- Set Thorough's `maxTokens` to roughly p95(reasoning tokens) + the answer
  headroom measured with thinking off. Publish both numbers next to the constant
  so the next person can see what it was derived from.
- The acceptance bar is a **zero** empty-answer rate at the chosen budget, not a
  low one. An empty answer is a total failure of the turn, and the prior
  investigation saw it on roughly half of a battery.

**Latency is a product decision, not only a number.** Unlimited reasoning on a
local model adds seconds before the first answer token. The window already shows
a live reasoning block while thinking runs, so the time is *visible* rather than
dead — but the first-token delay on the Thorough rung should be measured and
stated in the rung's description if it is large. Quick and Standard must not
regress at all; that is the floor.

---

## 5. Build-compatibility floor, and the streaming defence

### 5.1 What exists

`LlamaServerBuildCheck` (`modules/app-inference/src/main/java/io/justsearch/app/inference/LlamaServerBuildCheck.java:24-48`)
compares an expected build tag from a `runtime-version.txt` marker against the
`build_info` field of `/props`, exact-match and unknown-tolerant. Its only
consumer warns on drift (`ServerPropsOps.applyBuildInsightsFromProps`,
`:80-104`). There is **no capability assertion of any kind** — a build that
cannot honour `--reasoning-budget`, or cannot emit `reasoning_content`, is
indistinguishable from one that can.

The nearest thing to a capability check today is a model-identity heuristic:
`ServerPropsOps.warnIfThinkingMismatch` (`:137-150`) warns when thinking is
enabled and the loaded model's id does not contain "thinking". A warning, on a
substring, about the model — not the server, not the flag, and not fail-closed.

### 5.2 Designed floor

**Three independent signals, combined into one verdict.** None alone is
sufficient, and the combination should be computed once and stored, not
recomputed per request.

1. **Argument acceptance (authoritative, launch-time).** A build that rejects the
   flag says so on startup with `error while handling argument
   "--reasoning-budget": invalid value`. That exact string is already recognised
   in a CI decision script (`scripts/ci/build-agent-e3-decision.mjs:260-261`).
   Detecting a runtime capability only in a CI script is a second authority over
   a runtime fact: **lift the detection into the launch path** and let the CI
   script consume the runtime verdict, rather than pattern-matching logs
   separately.
2. **Props-reported capability (preferred when present).** Newer llama-server
   builds report chat-template capabilities on `/props`. Nothing in
   `ServerPropsOps` reads them today. **Probe C (§7): capture a full `/props`
   from the bundled b8571 and record which capability fields exist**, then read
   the specific field rather than inferring from a version number.
3. **Build-tag floor (fallback).** A minimum `bNNNN` below which reasoning
   support is assumed absent, used only when 1 and 2 are both silent. This is the
   weakest signal and must be labelled as such — the existing comparison is
   deliberately unknown-tolerant, and the floor must not convert an unknown build
   into a hard failure for a user running an adopted or externally-staged server.

**Fail closed, with a reason, and only on the requesting path.** When thinking is
requested and the verdict is "unsupported", the request must be answered
*without* thinking and the turn must carry a typed reason — never silently
degrade, and never leak markup. Two consequences worth stating plainly:

- The refusal must be **reportable to the surface**, so the Thorough rung can
  disable itself with an explanation instead of promising something the running
  build cannot do. A control that is greyed out with a reason satisfies
  no-dead-controls; a control that silently does nothing does not.
- The verdict belongs next to the build facts already published on the runtime
  manifest — `AiInfo` already carries `serverBuildExpected` / `serverBuildActual`
  — rather than in a new parallel surface. The manifest is where "what can this
  installation do" is already read from.

**Unsupported must not mean unstartable.** Refusing to launch llama-server
because it cannot think would take the whole product down over an optional
feature. Fail closed on *thinking*, not on *inference*.

### 5.3 Should the streaming path strip think tags anyway?

**Yes — and it is the one change worth making before the probes land**, because
its justification does not depend on any probe outcome: the agent path already
strips lone `</think>` tags with a comment attributing them to the *current
default* configuration (`AgentLlmCaller.java:305-307`), and the ask path has no
such protection.

Three design constraints:

- **Not a per-chunk regex.** Tags straddle SSE frame boundaries; a per-chunk
  `replaceAll` will pass through `<thi` + `nk>`. It needs a small stateful filter
  that buffers a partial tag across frames.
- **Reroute, do not delete.** When a build emits inline thinking instead of
  `reasoning_content`, the right behaviour is to feed that text to the *reasoning*
  channel, so the product behaves identically on both build families. Deleting it
  would make the same model look like it did no thinking.
- **One authority.** Place it in `OnlineModeOps`' streaming parse, next to the
  existing non-streaming strip (`:885-893`), so every shape benefits from one
  implementation. If it lands there, `AgentLlmCaller`'s accumulator-level strip
  becomes redundant and should be removed in the same change — leaving two
  strippers is exactly the drift this repository's containment rules exist to
  prevent.

---

## 6. Frontend seams (named, not designed)

1. **Visibility default.** `ReasoningBlock` starts collapsed
   (`modules/ui-web/src/shell-v0/components/chat/ReasoningBlock.ts:20`) and
   re-collapses itself when streaming ends (`:52-58`). If thinking becomes routine
   on Thorough, decide whether live thinking auto-expands (it is the only thing
   happening for several seconds) and whether it re-collapses on completion.
2. **Reasoning is not persisted — anywhere.** Verified: the engine's persisted
   assistant message copies `citations`, `calibration` and `claimMatches` from the
   done payload and nothing else (`ConversationEngine.persistedAssistant`,
   `:741-760`); reasoning text is only ever handed to the SSE sink (`:494`) and is
   never accumulated server-side. The window keeps it in the turn record it holds
   in memory (`SearchV3View.ts:1337-1341`, `sv3-sessions.ts:132-136`), so it
   survives session switches within the tab and dies with the tab. Consequence
   worth deciding rather than inheriting: a reloaded conversation shows the same
   turn *without* its reasoning, which is a silent asymmetry between the live
   render and the record — the same class of divergence that per-claim grounding
   was made durable to avoid. Either add `reasoning` to the persisted payload and
   the history projection, or state explicitly that reasoning is ephemeral by
   design and make the window say so.
3. **Simple / Detailed.** The app-wide disclosure authority is `uiModeState`
   (`modules/ui-web/src/shell-v0/state/uiModeState.ts:15-26`, default `simple`),
   surfaced as a topbar toggle (`chrome/Shell.ts:1820-1840`). The Search v3 window
   does not consult it at all today — verified by search. Open question: is a
   reasoning trace advanced-only detail (like the retrieval-trace diagnostics that
   are already gated this way), or is it primary content that a Simple-mode reader
   should still see because it is the only thing on screen while the model thinks?
   The effort control itself raises the same question.
4. **Effort-rung availability.** If §5's floor can report "this build cannot
   think", the Thorough rung needs a disabled-with-reason presentation. That is a
   new state for a control that currently has none.

---

## 7. Probes before commitment, ranked by how much the answer moves the design

1. **Probe A0 — does b8571 accept a finite `--reasoning-budget`?** Cheapest of
   all, and the only one that can open a branch nobody has costed: bounded
   thinking with a predictable token price would remove most of §4's risk and make
   Branch 2a safe without touching Standard. The prior "only −1 or 0" finding is
   hundreds of builds stale.
2. **Probe A — does the per-request kwarg lift a request out of a zero budget?**
   Selects Branch 1 (small, no product change) versus Branch 2 (a real product
   decision about what Standard means). Also the probe that can reveal the leak.
3. **Probe B3 — the regression retest at the engine's default 1024 ceiling.**
   Decides whether flipping the server default is even permissible. A green B1
   with a red B3 would be the trap: the feature demonstrably works, and shipping
   it as a default breaks the common case.
4. **Probe B1/B2 — end-to-end arrival and per-request suppression.** Expected to
   pass; needed as the evidence that the chain is whole, and B4 confirms the
   persistence gap §6 predicts.
5. **Probe C — what capability fields does the bundled build's `/props`
   actually report?** Decides whether §5's floor rests on a real capability signal
   or falls back to a build-tag comparison. Cheap: one HTTP GET against a running
   stack; worth capturing verbatim into this document when it is run.
6. **The token measurement (§4).** Not a probe but a measurement campaign; it
   sizes Thorough's budget. It cannot start until the branch is known, and it
   should not be skipped on the grounds that 3072 "looks fine".

---

## 7a. Probe campaign log

**Attempt 1 — 2026-08-14 — NOT RUN, machine not free.** No probe executed; no
stack started; no result below should be read as data.

Observed at attempt time:

- The dev-runner lease was free (`running: false`, no active run) — but the
  dev-runner only knows runs it started, so a free lease is **not** a free machine.
- A full Head + Worker from a *different* worktree was live outside the
  dev-runner, launched via `:modules:ui:runHeadlessEval`: Head on API port 33221
  reporting `head: READY`, `worker: READY`,
  `inference: DEGRADED / inference.offline`; the Worker process was resident on the
  GPU with encoder acceleration enabled (`justsearch.gpu.layers=99`,
  `justsearch.embed.gpu.enabled=true`).
- GPU: RTX 4070, 12282 MiB total, **2930 MiB already in use** by that run.
- No `llama-server.exe` was running; ports 8081/8082 were free — meaning the
  foreign run had not yet activated its LLM, but would take 8081 when it did.

Why the campaign was stopped rather than run:

1. **Port collision on 8081 is certain, not hypothetical** (§2.2a): both Heads
   derive the same constant llama-server port. Whichever activates second adopts
   the other's server. That either contaminates the neighbouring eval with an
   experimental `--reasoning-budget`, or yields probe data measured against a
   foreign server while every pre-check reports success.
2. **VRAM.** ~9.3 GB free on a 12 GB card with a neighbour mid-run; a 9B model at
   full offload plus a second LLM if their eval activates one does not fit. The
   single-tenant-GPU assumption is a stated premise of this document.
3. The contention rule for the shared stack is stop-and-report, never take over.

**Preconditions for attempt 2**: the foreign run finished (no Head/Worker from
another worktree, GPU back to idle), plus `JUSTSEARCH_SERVER_PORT` pinned to a free
port per §2.2a so this hazard cannot recur even if a neighbour appears mid-campaign.

### Attempt 2 — 2026-08-14 — COMPLETE. All probes answered.

Machine verified free first (no foreign Head/Worker, GPU 625 MiB idle, 8081/8082
free). Stack driven via the dev-runner directly with `JUSTSEARCH_SERVER_PORT=18081`
pinned and `--skip-build` (a neighbouring session held a test JVM; skipping Gradle
honours the one-build-at-a-time convention). Three full backend restarts, one per
budget. Torn down clean after each.

**Ownership verified per configuration** (§2.2a). The live process argv turned out
to be a *better* instrument than the launch log line — this run wrote nothing to
`backend.stdout.log`, so the documented log check would have been inconclusive,
while `Get-CimInstance Win32_Process` showed the flags directly. Recommend
amending §2.2a to prefer argv inspection. Observed argv, verbatim:

```
llama-server.exe -m ...\Qwen_Qwen3.5-9B-Q4_K_M.gguf --jinja --reasoning-format deepseek
  --reasoning-budget <N> --host 127.0.0.1 --metrics --port 18081 -c 4096 -ngl 99 -fa on
```

with `<N>` = 0, −1, 512 across the three runs, always a fresh PID owning 18081.
No adoption warning in any run. `/api/debug/effective-config` reported
`justsearch.llm.reasoning_budget` = the intended value from `source: env_var`,
`ordinal: 400`, `detail: JUSTSEARCH_REASONING_BUDGET` every time — never `default`.

**Instrument deviation, and why it is sound.** `core.rag-ask` returned
`event: error / {"errorCode":"NO_CONTENT"}` — the Worker failed to spawn on the
first run (`PID validation timeout after 5000ms`) and the dev index held no matching
documents, so retrieval errored *before* the LLM was reached. Probes A/B ask a
question about the llama-server boundary, not about retrieval, so the campaign ran
on `core.free-chat`, which drives the identical
`ConversationEngine.streamLlm` → `OnlineModeOps` path and the identical
`reasoning_chunk` emission (`ConversationEngine.java:494` is shape-independent;
`core-free-chat.ts` declares the same event). This removes retrieval as a confound
rather than introducing one. **Still open**: an end-to-end confirmation through
`core.rag-ask` and the rendered `jf-reasoning-block`, which needs an indexed corpus.

#### A0 — finite budget: **ACCEPTED** (supersedes the b8185 finding)

Build confirmed `version: 8571 (e397d3885)`, `build_info: b8571-e397d3885`.

| Invocation | Result |
|---|---|
| `--reasoning-budget 512 --version` | accepted, exit 0 |
| `--reasoning-budget abc --version` (control) | `error while handling argument "--reasoning-budget": invalid stoi argument`, exit 1 |
| `--reasoning-budget 512` (full server start) | **starts and serves** |

The control proves the parser validates in this context, so the acceptance is real.
**b8571 accepts arbitrary finite budgets** — tempdoc 227 §E3's "only −1 or 0"
applied to b8185 and no longer holds.

**Defect found in the existing detector.** `scripts/ci/build-agent-e3-decision.mjs:260`
matches the literal `error while handling argument "--reasoning-budget": invalid value`.
b8571 emits `invalid stoi argument`. The detector would **not** fire on this build.
Any lift of that detection into the launch path (§5.2 signal 1) must match the
prefix `error while handling argument "--reasoning-budget"`, not the suffix.

#### A — per-request enable against budget 0: **SERVER FLAG WINS** (Branch 2)

Identical prompt across all arms (a deliberation-inviting riddle), reused verbatim
at budget −1 so a negative here is falsifiable.

| Arm | reps | `reasoning_chunk` | think markup | answer | promptTokens |
|---|---|---|---|---|---|
| A1 `enableThinking:true`, max 3072 | 3 | **0** | 0 | 581 / 738 / 859 chars, all correct | 48 |
| A2 `enableThinking:false`, max 3072 | 2 | 0 | 0 | 1371 / 1402 chars | **50** |
| A3 field absent, max 3072 | 2 | 0 | 0 | 439 / 894 chars | 48 |

**The kwarg is not ignored — it is out-voted.** `promptTokens` is 50 with
`enable_thinking:false` versus 48 with `true` and 48 with the field absent, in both
budget configurations. So the kwarg reaches the chat template and changes prompt
rendering; what it cannot do is authorise reasoning *generation* while the server
budget is 0. That is the upstream AND-expression
(`enable_thinking = use_jinja && reasoning_budget != 0 && …`) confirmed empirically,
and it is a more precise statement than "the control is dead".

#### B — budget −1: chain live, per-request disable works

| Arm | reps | `reasoning_chunk` | reasoning chars | answer | totalTokens |
|---|---|---|---|---|---|
| B1 `enableThinking:true`, max 3072 | 3 | **906 / 1197 / 877** | 2597 / 3463 / 2402 | 580 / 888 / 690 chars | 1161 / 1619 / 1205 |
| B2 `enableThinking:false`, max 512 | 2 | **0** | 0 | 1349 / 1168 chars | 562 / 505 |

The A-versus-B1 comparison on the identical prompt is the controlled result:
**0 reasoning frames at budget 0, 877–1197 at budget −1.** A's negative was the
server flag, not a quiet model. Per-request *disable* (B2) suppresses completely,
confirming the direction the codebase already relies on.

Wire format exactly as designed: `event: reasoning_chunk` / `data: {"text":"…"}`,
one frame per token, separate from `event: chunk`.

**Measurement instrument found**: `reasoning_chunk` frame count equals the reasoning
token count exactly (B3 below: 1024 frames, `totalTokens` 1072 = 48 prompt + 1024
completion). §4 wanted `completionTokens` surfaced; until it is, the frame count is
an exact substitute rather than an estimate.

#### B3 — the regression retest: **REPRODUCES, 4/4, and silently**

Config: no `enableThinking`, no `maxTokens` (engine default 1024), budget −1 — i.e.
exactly what the Standard rung would send if the default were flipped.

| rep | `reasoning_chunk` | `chunk` | answerChars | totalTokens | error event |
|---|---|---|---|---|---|
| 1 | 1024 | **0** | **0** | 1072 | none |
| 2 | 1024 | **0** | **0** | 1072 | none |
| 3 | 1024 | **0** | **0** | 1072 | none |
| 4 | 1024 | **0** | **0** | 1072 | none |

Reasoning consumed the entire completion budget; **not one answer token was
produced**. The stream terminates with a normal `event: done` and **no error** — the
turn renders as an empty assistant message with no explanation. Deterministic, not
a low rate.

#### C — bounded budget 512: **rescues B3 completely** (the branch A0 opened)

Same B3 configuration, only the server budget changed:

| Arm | reps | `reasoning_chunk` | answerChars | totalTokens |
|---|---|---|---|---|
| C1 no params (engine default 1024) | 3 | **512** exactly | 946 / 807 / 652 | 896 / 865 / 849 |
| C2 `enableThinking:true`, max 3072 | 2 | **512** exactly | 1044 / 698 | 970 / 858 |

The budget binds at precisely 512 reasoning tokens and leaves ample room for a
complete answer. The configuration that failed 4/4 at −1 succeeds 3/3 at 512.
No think markup in any run.

#### B4 — persistence: reasoning is **not** recorded (as §6 predicted)

A `core.free-chat` turn streamed 445 `reasoning_chunk` frames; the persisted record
(`GET /api/chat/conversations/{id}/history`) returned the assistant message with
keys `[role, content, id, hash, ts]` — no reasoning field, confirming
`persistedAssistant` (`ConversationEngine.java:741-760`) drops it.

#### Q4 — `/props` capability fields on b8571

`chat_template_caps` **exists** and reads, verbatim:

```json
{"supports_object_arguments":true,"supports_parallel_tool_calls":true,
 "supports_preserve_reasoning":true,"supports_string_content":true,
 "supports_system_role":true,"supports_tool_calls":true,
 "supports_tools":true,"supports_typed_content":false}
```

Also `build_info: "b8571-e397d3885"` (the `bNNNN-<commit>` shape
`LlamaServerBuildCheck.actualFromProps` parses) and `modalities: {vision:false, audio:false}`.

**Consequence for §5.2 signal 2**: there is `supports_preserve_reasoning` but
**no `supports_enable_thinking`** field. The floor can rest on the presence of
`chat_template_caps` plus `supports_preserve_reasoning` as a reasoning-aware-build
signal, but per-request thinking support is *not* advertised and cannot be read
directly — so signal 1 (argument acceptance at launch) remains the authoritative one.

**Incidental**: no `runtime-version.txt` marker exists beside the shared cuda12
binary, so `LlamaServerBuildCheck.readExpectedNextTo` yields expected=UNKNOWN and the
drift comparison is inert for dev stacks — the unknown-tolerant path, working as
designed, but worth knowing before relying on drift detection.

#### What the results select

- **Branch 2 is confirmed** (server flag wins); Branch 1 is eliminated.
- **Branch 2a is now safe in a form the original design could not offer**: a finite
  default budget, not −1. C removes 2a's blocking prerequisite — with budget 512 the
  Standard rung can keep sending `{}` and still get both reasoning and a complete
  answer, so the temperature/top-p pin problem never arises.
- **A default of −1 is disqualified**, with 4/4 evidence.
- **The think-tag leak did not appear**: zero `<think>`/`</think>` occurrences in
  answer text across all 21 dispatches in every configuration (0, −1, 512). The
  streaming strip (§5.3) stays justified as defence-in-depth — the agent path's
  comment records the leak under some conditions, and this campaign exercised one
  model on one build — but it is **not** an active defect on today's default.
- **Thorough's promise is fixable rather than retirable**: under a finite default
  budget the rung's "Thinks first" becomes true, and `enableThinking:false` on Quick
  remains an effective suppressor (B2).

## 8. Risks and non-goals

**Risks**

- **Empty answers at an unbounded budget.** The single largest risk, with prior
  evidence behind it rather than speculation. Mitigated only by measurement
  (§4) and by refusing to flip a default without B3 green.
- **Model dependence.** The whole chain assumes a model whose template emits
  reasoning in the deepseek-format channel. A user who configures a different
  model gets a different answer to every question in this document. The
  model-identity warning that exists today (`ServerPropsOps.java:137-150`) is a
  substring heuristic on the model id and is not a capability check; §5's floor
  addresses the *server*, not the model, and the model gap remains open.
- **Latency and VRAM at an unbounded budget.** Reasoning is generation: it costs
  wall-clock and holds the single-tenant GPU longer per turn. Any concurrent
  workload sharing that GPU pays for a thinking ask.
- **Server-wide flags versus per-request UI.** The launch flag is
  process-lifetime by construction. Any product story that implies per-request
  freedom is bounded by what the completions API can override — which is exactly
  what Probe A measures, and why no UI should be designed before it lands.
- **Experiment leakage.** An exported `JUSTSEARCH_REASONING_BUDGET` in a shell
  that later starts the shared stack silently applies this experiment to
  unrelated measurements. §2.8 exists for this reason.

**Non-goals**

- No user-facing settings surface for thinking on Branch 1, and none on any
  branch without an explicit product decision — the effort rung already owns this
  fact for the ask path.
- No change to how the agent path handles thinking. Its per-request suppression
  is deliberate and working (`AgentStepRunner.java:458`, `:498`); the only
  agent-adjacent change contemplated here is removing a strip that becomes
  redundant if §5.3 lands upstream of it.
- No change to summarize, extract, navigate or free-chat behaviour. They declare
  the same `reasoning_chunk` event and would inherit any server-default change,
  which is a consequence to *verify* on Branch 2, not a surface to redesign here.
- No multi-model or per-model budget policy. One local model, one budget.
- No work on the other open backend workstreams. This document is scoped to
  thinking mode and stops at its edges.

---

## 9. Implementation handoff (2026-08-14)

**Owner decision: finite reasoning budget, ON by default; the v3 window's
reasoning handling mirrors the shipped window's.** Branch 2a-finite, the variant
the C probe opened. This section is the whole brief — a fresh implementer should
not need to re-derive anything above it.

### 9a. Branch and worktree

Build in a **new worktree off `origin/main`** (at or after `5da33b22`). Do **not**
implement in `.claude/worktrees/822-t3code-window`: that branch is ~162 commits
ahead / 14 behind `origin/main` and carries a live session's uncommitted work.

Citation reliability, verified by diffing this worktree against `origin/main`:

| files | state |
|---|---|
| `ResolvedConfigBuilder.java`, all of `app-inference/`, `AgentLlmCaller.java`, `ConversationEngine.java`, `OnlineAiService.java`, `build-agent-e3-decision.mjs` | **byte-identical** — every line number in §1–§8 holds verbatim |
| `LocalApiServer.java` | differs (8 lines); `resolveLlamaServerPort` is **713-721** on `origin/main` (705-713 here) |
| `ReasoningBlock.ts`, `ReasoningController.ts` | **byte-identical** |

### 9b. The default value: **512**, and why

`justsearch.llm.reasoning_budget` default changes `0` → `512` at
`ResolvedConfigBuilder.java:1022`.

| candidate | verdict | evidence |
|---|---|---|
| `-1` (unlimited) | **disqualified** | B3: 4/4 empty answers, silently, at the engine's default `maxTokens` |
| `1024` | **disqualified by construction** | equals `DEFAULT_MAX_TOKENS` (`ConversationEngine.java:65`) — reasoning would consume the entire budget for the parameterless rung; that *is* the B3 configuration |
| **`512`** | **chosen** | C probe: binds at exactly 512 reasoning tokens, 5/5 non-empty answers, `totalTokens` 849-970 against a 1024+48 ceiling — the tightest rung (Standard, parameterless) has ~100-220 tokens of headroom |
| `<512` | untested, and cuts thinking further below its natural length | — |

**The honest caveat, and the one thing the measurement must settle.** At 512 the
model consumed the full budget on *every* run (5/5 exactly 512 frames), while its
natural length on the same prompt at `-1` was 877-1197 tokens. So 512 truncates
thinking to roughly half. Answers stayed correct and coherent — llama.cpp closes
the reasoning block and proceeds cleanly — but "does truncated thinking degrade
answer quality versus unbounded?" is unanswered on a single prompt. The §4
measurement (now a merge gate, §9g) settles it. **If truncation-driven quality loss
appears, the remedy is raising `DEFAULT_MAX_TOKENS`, not lowering the budget** —
lowering degrades thinking further to protect a ceiling that is itself the
constraint.

### 9c. Backend changes, in dependency order

**1. The default (the whole feature, one line).**
`ResolvedConfigBuilder.java:1022` → `resolveInt("justsearch.llm.reasoning_budget", 512)`.
Update the `EnvRegistry` doc comment at `EnvRegistry.java:116` — it currently reads
*"Default 0 (disabled). -1 = unlimited"*, which becomes false. Nothing else in the
launch path changes: `LlamaServerOps.java:242-254` already reads the resolved value
and passes `--reasoning-budget`, and b8571 accepts finite values (A0, plus a live
server started with `512`).

**2. The build-compatibility floor (§5).** `/props` on b8571 carries
`chat_template_caps` **without** any `supports_enable_thinking` field (Q4), so the
authoritative signal is **launch-argument acceptance**, not capability discovery:

- Detect the rejection at the launch path in `LlamaServerOps`, matching the
  **prefix** `error while handling argument "--reasoning-budget"` — **not** the
  suffix. b8571 emits `invalid stoi argument`; the existing CI matcher looks for
  `invalid value` and would not fire (§9e).
- On rejection: **relaunch without the flag and mark thinking unsupported.** Fail
  closed on *thinking*, never on *inference* — a build that cannot think must still
  serve answers.
- Secondary signal, recorded not gating: `chat_template_caps.supports_preserve_reasoning`
  plus the presence of the caps object at all. Read it in
  `ServerPropsOps.applyBuildInsightsFromProps` (`:80-104`), beside the existing
  build-drift comparison.
- Publish the verdict on the runtime manifest's `AiInfo`, next to
  `serverBuildExpected` / `serverBuildActual` — that record is already where "what
  can this installation do" is read from. This is what lets the Thorough rung
  disable itself with a reason instead of promising something the build cannot do.
- Note for the implementer: no `runtime-version.txt` marker exists beside the
  shared cuda12 binary, so `LlamaServerBuildCheck.readExpectedNextTo` yields
  expected=UNKNOWN in dev. That is the designed unknown-tolerant path; do not make
  the new floor depend on the marker.

**3. Streaming think-tag strip, defence-in-depth.** The campaign found **no leak**
(0 occurrences across 21 dispatches at budgets 0, −1 and 512), so this is not
urgent — but `AgentLlmCaller.java:305-307` records the leak under conditions this
campaign did not reproduce, and the ask path has no protection at all. Build it as
designed in §5.3:

- **Stateful and straddle-safe** — tags split across SSE frames; a per-chunk
  `replaceAll` is wrong by construction.
- **Reroute, don't delete** — feed captured text to the reasoning channel so a
  non-deepseek build produces identical product behaviour.
- **One authority** — place it in `OnlineModeOps`' streaming parse beside the
  existing non-streaming strip (`:57`, `:885-893`). This makes
  `AgentLlmCaller.java:305-307` redundant; **delete it in the same slice**
  (retire-with-a-sweep — two strippers is two authorities).

**4. `completionTokens` on the done payload — ride-along, include it.** Three
lines at `ConversationEngine.java:389-395`, which already puts `promptTokens` and
`totalTokens` and drops `completionTokens` though `AiUsage` carries it
(`OnlineAiService.java:34`). It is the denominator for every budget decision and
for the §9g measurement. Cheap enough that deferring costs more than doing it.

### 9d. Frontend: the shipped window's pattern, and why v3 needs **no change**

Inventoried on `origin/main`. The reasoning idiom is **uniform across every
surface** — `UnifiedChatView` (`:5593-5601`), `NavigateView` (`:132-140`),
`SummarizeView` (`:224-232`) and v3 (`Sv3Main.ts:1141-1160`) all use the same two
shared authorities in the same two-mode shape:

```
isThinking            → <jf-reasoning-block .controller=${reasoning}>   // live
blocks.length && !isThinking → blocks.map(<jf-reasoning-block .text .durationMs>) // settled
```

Interaction behaviour lives entirely in the shared component
(`ReasoningBlock.ts`, byte-identical across branches): **collapsed by default**
(`:20`), its own disclosure toggle, and an auto **re-collapse when streaming ends**
(`:52-58`). Stream wiring is the shared `ReasoningController`
(`handleReasoningChunk` / `endThinking` / `finalize`).

**Verdict: the v3 window already mirrors the shipped window, and slightly exceeds
it.** No FE change is required to satisfy the owner's "mirror the original" —
turning the budget on simply lights up rendering that is already correct and
already tested. Two differences, both deliberate and both in v3's favour:

- The shipped window renders reasoning **only inside `renderStreamingBlock`**
  (`UnifiedChatView.ts:5594`, `:5598-5600`); once the turn commits and the next
  dispatch calls `reasoning.reset()` (`:5820`), the trace is gone from screen.
- v3 pins finalized blocks **onto the turn** (`SearchV3View.ts:1337-1341`,
  `sv3-sessions.ts:132-136`), so earlier turns keep their traces for the life of
  the session. Keep this.

**No thinking control exists anywhere in the FE** except v3's effort rungs —
`enableThinking` appears in exactly two `origin/main` FE files (`sv3-ask.ts` and
its test). The shipped window is display-only. So v3's effort rung is the *only*
thinking control in the product, and it stays as designed:

| rung | body | status after this change |
|---|---|---|
| Quick | `{enableThinking:false, maxTokens:512}` | unchanged — **now load-bearing**: it is the only way to opt out, and B2 proves per-request disable works |
| Standard | `{}` | unchanged — inherits the 512 budget; C proves it yields reasoning *and* a complete answer at the 1024 default |
| Thorough | `{enableThinking:true, maxTokens:3072, topK:12}` | unchanged, and **its label finally becomes true** |

Thorough's `enableThinking:true` is now redundant-but-harmless (thinking is on by
default; A showed the kwarg still reaches the template and shifts `promptTokens`
48↔50). Keep it: it makes the rung's intent explicit and survives a future default
change. The **"Thinks first" copy stays** — it is now accurate.

### 9e. Recorded decisions

- **Reasoning is NOT persisted to the conversation record — deliberate.** B4
  evidence: a turn streamed 445 `reasoning_chunk` frames; the persisted assistant
  message came back with keys `[role, content, id, hash, ts]` and no reasoning
  field, because `persistedAssistant` (`ConversationEngine.java:741-760`) copies
  only `citations` / `calibration` / `claimMatches`. This **matches the shipped
  window**, which does not even keep reasoning on screen past the turn. The
  live/record asymmetry (a reloaded conversation shows the turn without its trace)
  is accepted as the product's behaviour, not a defect to fix in this slice.
  Revisit only if reasoning becomes something users cite or search.
- **CI detector string fix — ride-along, trivial.**
  `scripts/ci/build-agent-e3-decision.mjs:260` matches
  `error while handling argument "--reasoning-budget": invalid value`; b8571 emits
  `invalid stoi argument`, so the detector cannot fire on the bundled build. Change
  it to match the prefix only. Same fix, same string, as the launch-path detector
  in §9c.2 — implement the predicate **once** and have both call it, or the two
  will drift the way this one already did.

### 9f. The B3 guard: make the empty-answer configuration unrepresentable

B3 is a *silent* failure — `done` with no error and no answer — so it must be
blocked structurally, not documented. Two layers:

1. **Config assertion at resolution.** After resolving the budget, reject the
   catastrophic shapes rather than passing them to the launch path. The invariant
   is not "budget ≠ −1" alone but the relationship that actually causes the
   failure: **unbounded, or large enough to crowd out the answer, against the
   default completion ceiling.** Concretely: a resolved budget of `-1`, or
   `budget >= DEFAULT_MAX_TOKENS`, must be refused with a loud WARN and clamped to
   the safe default — an operator who deliberately sets `-1` gets a log line saying
   why it was overridden and what it would have caused. (An escape hatch may be
   granted later behind a differently-named explicit key; do **not** add one in
   this slice — the whole point is that the value is unreachable by accident.)
2. **Regression test that reproduces the failure mode.** In
   `ResolvedConfigBuilderTest` (`modules/configuration/src/test/.../resolved/`,
   which today has **no** reasoning coverage): assert the default is 512, and that
   `-1` and `1024` from env/sysprop are clamped-with-warning rather than passed
   through. Name it for the failure it prevents, not the value it checks.

The pairing matters: the assertion makes it impossible, the test makes it stay
impossible.

### 9g. Verification plan

**Tier 1 — unit / compile (implementer, self-verifying).**
- `./gradlew.bat build -x test`, then `:modules:configuration:test`,
  `:modules:app-inference:test`, `:modules:app-services:test`.
- New: the §9f clamp tests; a `LlamaServerOps` test for the rejection-prefix
  detector (feed both `invalid value` and `invalid stoi argument` — the second is
  the one that regressed); an `OnlineModeOps` streaming-strip test whose input
  **splits `<think>` across two frames**, or it does not test the hard part.
- Frontend: `cd modules/ui-web && npm run typecheck && npm run test:unit:run`.
  No FE source change is expected (§9d) — if the suite goes red, something was
  changed that should not have been.

**Tier 2 — the measurement that gates merge (§4).** ~20 questions spanning
short-factual to multi-source-synthesis, run at budget 512 against budgets 0 and
−1 as references. Per question record: `completionTokens` (now available),
`reasoning_chunk` frame count (**exactly equals reasoning tokens** — verified: B3's
1024 frames = `totalTokens` 1072 − 48 prompt), answer chars, wall-clock, and an
empty-or-truncated flag. **Merge bar: zero empty answers and zero truncated
answers at the Standard configuration.** Also compare answer quality at 512 versus
−1 to settle §9b's caveat.

**Tier 3 — the live round, still open (needs a lease + an indexed corpus).** The
campaign ran on `core.free-chat` because retrieval was unavailable; the
rag-ask-through-rendered-block leg was never exercised. Script for one supervised
window:

1. `quick_health`; if free, `justsearch_dev_start` with a `leaseDurationSec`
   covering the round (900 is ample). **Pin `JUSTSEARCH_SERVER_PORT`** per §2.2a.
2. Confirm ownership, not just config: `Get-CimInstance Win32_Process` on
   `llama-server.exe` shows `--reasoning-budget 512` and a fresh PID on the pinned
   port; `/api/debug/effective-config` shows the value from `source: default`
   (this is now the shipped default — `default`, not `env_var`, is the pass here).
3. `ai_activate`, then **ingest a small corpus** so retrieval returns documents —
   without this, rag-ask answers `NO_CONTENT` and the leg proves nothing.
4. v3 window, **Standard** rung: ask a question the corpus answers. Confirm on the
   wire that `reasoning_chunk` frames arrive, and on screen that
   `jf-reasoning-block` renders **collapsed**, expands on click, and **re-collapses
   when the stream ends**.
5. **Thorough**: same question; confirm reasoning again and a complete,
   non-truncated answer.
6. **Quick**: same question; confirm **zero** `reasoning_chunk` frames and no
   reasoning block — the opt-out still works end to end.
7. Ask a second question in the same session; confirm the **first** turn keeps its
   trace (v3's per-turn pinning, §9d) while the new turn streams its own.
8. Reload the window; confirm reasoning is **absent** from the reloaded turns —
   the accepted asymmetry of §9e, verified rather than assumed.
9. Stop the stack; verify no `llama-server.exe`, ports closed, GPU at baseline.

### 9h. Open items

1. **Tier-3 live round** — the only unexercised leg (rag-ask + rendered block).
   Needs a corpus; everything else about the chain is measured.
2. **Answer quality at a truncated budget** (§9b) — the one substantive unknown
   behind the chosen default; Tier 2 settles it.
3. **Per-model variance** — every result here is one model (`Qwen3.5-9B-Q4_K_M`)
   on one build (b8571). `ServerPropsOps.warnIfThinkingMismatch` (`:137-150`) is a
   substring heuristic on the model id, not a capability check; the §9c.2 floor
   addresses the *server*, and the model gap stays open.
4. **Worker spawn flake** seen once during the campaign
   (`PID validation timeout after 5000ms`) — logged to the inbox, unrelated to this
   slice, but it will bite the Tier-3 round if it recurs.

---

## 10. Implementation log (2026-08-14, branch `thinking-mode-default`)

Implemented from §9 against `origin/main` at `5da33b22`. Every §1–§8 backend citation
held verbatim on that base; the one documented exception (§9a) was not touched.
No frontend source changed — §9d's inventory is confirmed by the untouched
`modules/ui-web` suite (typecheck + unit tests green, no diff under `src/`).

### 10a. What landed, by handoff item

| §9 item | Where it landed |
|---|---|
| 1. The default `0` → `512` | `ResolvedConfigBuilder.resolveReasoningBudget()`; `DEFAULT_REASONING_BUDGET = 512`. `EnvRegistry.java` doc comment rewritten (it claimed "Default 0 (disabled)"). |
| 2. Build-compatibility floor | `LlamaServerArgRejection` (the detector), `ThinkingSupport` (the verdict), `LlamaServerOps.relaunchWithoutReasoningBudget` (fail closed on thinking, relaunch without the flag), `ServerPropsOps.applyReasoningCapabilityFromProps` (secondary `/props` signal, recorded not gating), `RuntimeManifest.AiInfo.thinkingSupport` (published beside the build pin). |
| 3. Streaming think-tag strip | `ThinkTagStreamFilter`, wired into **both** streaming parses in `OnlineModeOps`. `AgentLlmCaller`'s accumulator strip deleted in the same change (its `THINK_TAGS` pattern and now-unused `Pattern` import with it). |
| 4. `completionTokens` ride-along | `ConversationEngine`, beside `promptTokens` / `totalTokens` on the done payload. |
| §9e CI detector fix | `governance/llama-server-arg-rejection.v1.json` is now the one declared marker; `scripts/ci/lib/llama-server-arg-rejection.mjs` reads it at runtime (no JS copy), the Java constant is pinned to it by a contract test, and `build-agent-e3-decision.mjs` prefers the runtime verdict from `<dataDir>/runtime/manifest.json` (`ai.thinkingSupport`), falling back to the shared predicate for runs that predate it. |
| §9f B3 guard | Clamp in `resolveReasoningBudget()` + `ResolvedConfigBuilderTest`'s reasoning-budget block, named for the failure (`unboundedBudgetCannotProduceTheEmptyAnswerConfiguration`). |

### 10b. Decisions taken while implementing (none of them redesigns)

- **The clamp's invariant is `budget == 0 || 0 < budget < ENGINE_DEFAULT_MAX_TOKENS`.**
  `0` stays representable — it is the explicit "no reasoning" setting, not a
  catastrophic one. `-1` and anything `>= 1024` are refused with a WARN naming what
  they would have caused, and clamped to 512. No escape hatch was added (§9f).
- **The engine ceiling is mirrored, not imported.** `modules/configuration` cannot
  depend on `app-services`, so `ResolvedConfigBuilder.ENGINE_DEFAULT_MAX_TOKENS`
  mirrors `ConversationEngine.DEFAULT_MAX_TOKENS` (widened `private` → package-private)
  and `ConversationEngineTokenCeilingTest` fails the build if the two drift — otherwise
  the clamp would silently start guarding the wrong number.
- **The rejection is read from THIS launch's output.** The llama-server log is
  append-only across restarts, so a previous run's rejection line in the tail would be
  a false positive. `LlamaServerOps` records the log offset at launch and scans only
  what the current process wrote.
- **Adopted servers stay `UNKNOWN`.** Adoption launches nothing, so launch-argument
  acceptance says nothing about that server; the verdict is not guessed from a build tag.
- **The relaunch is attempted once, and only for `PROCESS_EXITED`.** If the relaunch
  itself fails, the original startup failure propagates unchanged — a build-floor
  mechanism must never turn one failure into a worse one.
- **The filter runs on both streaming parses**, not only the tools one: `streamChat`
  (summarize / Q&A / map-reduce) renders user-facing text too. It gets a null reasoning
  sink, which matches what that path already does with `reasoning_content`.
- **`AgentLoopServiceTest.thinkTags_strippedFromFinalResponse` was re-pointed, not
  deleted.** Its scripted AI service bypasses `OnlineModeOps`, so with the agent-level
  strip gone it can witness the new authority boundary: the test now asserts the agent
  does *not* double-strip, and names where the guarantee moved. The guarantee itself is
  covered by `ThinkTagStreamFilterTest` plus an `OnlineModeOpsTest` case that splits
  `<think>` across SSE frames.

### 10c. The caveat this slice carries forward (verbatim from §9b)

> At 512 the model consumed the full budget on *every* run (5/5 exactly 512 frames),
> while its natural length on the same prompt at `-1` was 877-1197 tokens. So 512
> truncates thinking to roughly half. Answers stayed correct and coherent — llama.cpp
> closes the reasoning block and proceeds cleanly — but "does truncated thinking degrade
> answer quality versus unbounded?" is unanswered on a single prompt.

**If truncation-driven quality loss appears, the remedy is raising `DEFAULT_MAX_TOKENS`,
never lowering the budget** — lowering degrades thinking further to protect a ceiling
that is itself the constraint.

### 10d. Verification

Tier 1 (this slice): `spotlessApply`, `build -x test -PskipWebBuild=true` green;
`:modules:app-inference:test`, `:modules:configuration:test`, `:modules:app-services:test`,
`:modules:app-agent:test`, `:modules:app-api:test`, `:modules:ui:test` green;
`node scripts/ci/lib/llama-server-arg-rejection.test.mjs` green (wired into CI beside the
other `scripts/ci/*.test.mjs` steps, so it is not a layer nothing invokes);
`modules/ui-web` typecheck + unit suite green and untouched.

New tests: the reasoning-budget clamp block (`ResolvedConfigBuilderTest`), the
register↔constant pin plus both build wordings (`LlamaServerArgRejectionContractTest`),
the frame-straddling filter (`ThinkTagStreamFilterTest` — every two-way split point of the
same input, per-character frames, lone `</think>`, unclosed block, byte-identical
passthrough), the wired straddle case (`OnlineModeOpsTest`), the b8571 `/props` caps
verbatim (`ServerPropsOpsTest`), the manifest field's additive contract
(`RuntimeManifestSchemaCompatibilityTest`), and the ceiling pin
(`ConversationEngineTokenCeilingTest`).

**Tier 2 (§4 measurement) and Tier 3 (§9g live round) are PENDING** — both need a
dev-stack lease and an indexed corpus, which this slice deliberately did not take. Tier 2
remains the stated merge gate: zero empty and zero truncated answers at the Standard
configuration, plus the 512-versus-`-1` quality comparison that settles §10c.
