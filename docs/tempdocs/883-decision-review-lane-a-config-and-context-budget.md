---
status: IN PROGRESS — chunk 1 (PR 1 #596: precedence slice 1 + derived context window) implemented, independent review folded (§D); live window pending
created: 2026-09-01
updated: 2026-09-02
owner_session: lane-A worker (branch worktree-lane-A, base 6c3ba431)
follows:
  - 882-decision-review-lane0-hygiene.md (lane 0; must be on main before this lane branches)
  - 845-rag-budget-and-prompt-scope.md (the live-window budget fix this lane generalizes)
  - 881-standard-profile-reasoning-exhaustion.md (n_ctx 4096 observed live, 2026-09-01)
  - 208-ai-model-tuning-optimization.md (the original 4096→8192 argument, 2026-02-16)
  - 347-gpu-env-var-propagation.md (config precedence diagnosis)
---

# 883 — Decision review, lane A: config precedence + the LLM context budget

**Thesis.** The LLM context window is treated as a user preference with two shipped defaults,
promoted to a system property so it outranks every other source, and then copied into a dozen
window-blind constants. The model is trained to 262k tokens; the app runs it at 4096 with four
llama-server slots it never asked for and no KV-cache quantization. This lane makes the window a
**derived resource** (from model + free memory + slot policy), makes every downstream budget a
**fraction of the live window** computed once per request, and finishes the configuration
precedence cleanup that the promotion hack was papering over.

This lane is one of three in wave 1 (A, B, C) of the 2026-09-01 decision review; the lane split,
dependency order and cross-lane rules are in `882-decision-review-lane0-hygiene.md`. Lane A is
**off the critical path** (0 → C → D → F) and may slip without blocking other lanes, but lane E's
RAG-assembly change (small-to-big passages) waits for this lane to merge because both edit the
same assembly code.

## Scope (contract)

Review item numbers are kept for cross-referencing.

| # | Item | This lane does | Not this lane |
|---|---|---|---|
| 22 | config precedence remainder | delete every settings→sysprop promotion; make `settings.json` contribute at its own ordinal (300); one repo-wide `System.getenv`/`getProperty` ArchUnit rule with a ratcheting allowlist replacing six per-module rules; extend the dead-config gate so every yaml key that is `put` has a reader | lane 0 already deleted dead yaml groups, `WorkerAi`, the pitfall row and the reversed javadoc |
| 8 | context window | window = f(model training ctx, free VRAM/RAM after weights, slot policy); explicit `-np`; `q8_0` KV cache; `UiSettings.contextLength` default becomes "auto" (0); single default for the quantity; `/props` readback stays the truth | model choice, chat profiles, GPU runtime variants |
| 9 | constants sized against the window | one `ContextBudget` value computed per request from live n_ctx + this request's completion reserve, passed down; every literal in the table below becomes a fraction of it; the two class-init-frozen config snapshots become per-request reads | prompt content, citation marks, agent tool semantics |

Out of scope even where it lives in the same file: the RAG passage shape itself (lane E, item 12),
the reranker/fusion parameters (lane E, item 20), anything in `modules/ui-web`.

## File ownership (no other wave-1 lane edits these)

`modules/configuration/**` (EnvRegistry, ResolvedConfig, ResolvedConfigBuilder, ConfigPrecedence),
`modules/ui/.../api/SettingsController.java`, the config phase of `modules/ui/.../HeadlessApp.java`
(`resolveConfig`, lines ~525-570), `modules/app-api/.../UiSettings.java`,
`modules/app-inference/**` (InferenceConfig, LlamaServerOps, ServerPropsOps),
`modules/app-services/.../conversation/spi/RAGContext.java`, `.../HierarchicalShapeRunner.java`,
`.../ConversationEngine.java` (max-tokens parsing only), `.../ExternalContextInjector.java`,
`.../SelectionContextInjector.java`, `modules/app-agent/.../AgentContextCompressor.java`,
`.../AgentLlmCaller.java`, `.../ReadDocumentTool.java`, `modules/core/.../TokenEstimation.java`,
`modules/ui/.../api/EffectiveConfigController.java`, `modules/app-services/.../config/ConfigStoreRebuilder.java`,
`config/application.yaml`, the ArchUnit guardrail tests named in §22, the new rule in
`modules/dead-code-audit`, `gates/config-surface/**`, `docs/reference/configuration/**`,
`docs/explanation/05-ai-architecture.md` §Token budgets, `06-configuration-ssot.md` §precedence,
and **ADR-0047 "Context window as a derived resource"** (number reserved; see cross-lane rules).

Cross-lane rules added by the review (apply to A, B, C):
- **ADR numbers are reserved now**: 0046 local trust model (B), 0047 context window (A),
  0048 extraction isolation + pacing (C). A and C may create only their own numbered file; B
  owns `docs/decisions/README.md`, the index, and every existing ADR.
- **`EnvRegistry` and `ResolvedConfigBuilder` append regions are shared**: any lane may add
  constants and `resolveX` lines for keys it introduces (C needs several on day one). Lane A owns
  the structure (ordinals, contributors, promotions), not the append region. Keep additions at
  the end of the enum / builder method to make merges trivial.

Lane C owns `StatusLifecycleHandler`/`CoreApiAssembly`; lane B owns existing `docs/decisions/**`.
If this lane needs an edit there, it sends a one-line request to the user.

## Evidence (verified 2026-09-01; `path:line` on `main` at 8e148b3b)

### Item 8 — the window

- Two defaults for one quantity: `modules/app-api/.../UiSettings.java:254` `contextLength = 4096`;
  `modules/configuration/.../ResolvedConfigBuilder.java:1048` `resolveInt("justsearch.context.size", 8192)`;
  `modules/app-inference/.../InferenceConfig.java:571` builder default 4096 and `:134` `if (ctxSize <= 0) ctxSize = 4096`.
- The promotion that makes 8192 unreachable: `SettingsController.java:304-325`
  `maybeApplyContextSizeSysProp` writes `justsearch.context.size` as a **system property**
  (ordinal 500, `ResolvedConfigBuilder.java:55,226-228`) on every settings PUT (`:130`) and reset
  (`:447`); startup path `HeadlessApp.java:561-566` does the same unless `JUSTSEARCH_CONTEXT_SIZE`
  is set. `settings.json` is serialized whole on every save (`UiSettingsStore.java:86-99`), and
  saves fire from AI install/activation (`AiInstallService.java:881,1807,1886,1948`,
  `RuntimeActivationService.java:876,956,981`), so every install has `contextLength: 4096` on disk.
- Reaches llama-server as `-c` at `LlamaServerOps.java:277-279`. `-np` is passed only in VDU mode
  (`:237-242`); otherwise llama-server auto-selects (observed `n_parallel = 4`, `kv_unified = true`).
  No `-ctk/-ctv` (KV cache type) flag anywhere in `modules/app-inference`.
- Live truth exists and is already consumed: `ServerPropsOps.java:188-198` reads `n_ctx` from
  `GET /props`; exposed as `lastKnownContextTokens()` (null until observed) and
  `configuredContextTokens()` (`InferenceLifecycleManager.java:1100-1108`). Consumers:
  `RAGContext.contextWindowTokens()` (`:251-266`, observed → configured → 4096) and the agent loop
  (`AgentLoopService.java:453-456`, `AgentStepRunner` 0.80/0.95 gates).
- Nothing derives VRAM/KV sizing from the window (greps over `modules/*/src/main` for
  `kvCache|estimateVram|contextSize \*` hit only comments). No catalog declares a model's native
  context; the dev llama-server log reports `n_ctx_train = 262144` against `n_ctx_slot = 4096`.
- History: 4096 first set 2025-11-13 (`9b2c2914e`, pre-cutover repo); raised to 8192 in code
  2026-02-16 (`39dba9c28`, argued by tempdoc 208) but never in `UiSettings`, so the raise never
  shipped. 881 (2026-09-01) confirmed live n_ctx 4096 and found raising to 8192 was *not* the cause
  of the failure it chased (881:117-118), i.e. the window is under observation but not re-decided.
  208's "linear scaling not viable, VRAM is finite" (208:502-512) was reasoned for a February model
  without KV quantization or a slot policy; it is not evidence about the current model.

### Item 9 — constants sized against the window

| Constant | Value | Site | Defect |
|---|---|---|---|
| `HIERARCHICAL_THRESHOLD_TOKENS` | 5000 | `HierarchicalShapeRunner.java:59`, branch `:133` | window never consulted (grep `contextTokens|ContextWindow` in file: none); a 4999-token doc goes single-pass into a 4096 window; set 2025-12-12, never argued |
| `SECTION_TARGET_TOKENS` / `SECTION_MAX_TOKENS` / `SYNTHESIS_MAX_TOKENS` | 1800 / 512 / 1024 | same file `:63,67,68` | same assumption |
| RAG default shape `top_k × max_chunks_per_article × chunk` | 5 × 2 × 500 | `RAGContext.java:81`, `ResolvedConfigBuilder.java:1592-1594`, `ChunkSplitter.java:92` | ≈5000 tokens requested against `computeSafeInputBudgetTokens(4096, 1024)` ≈ 2304 (`TokenEstimation.java:114-123`); 845's trimmer (`RAGContext.java:406-421,511-531`) then drops a suffix of sections on every ask and reports `context_truncated` |
| `ExternalContextInjector.MAX_CONTEXT_TOKENS` | 1000 | `:22,30` | documented as "~25% of a conservative 8K window"; drops history silently (no log, no SSE) |
| `ReadDocumentTool.DEFAULT_PAGE_CHARS` | 3000 | `:24,39` | sized "well inside n_ctx 4096" |
| `ServerPropsOps.SUMMARY_CONTROLLER_MAX_CONTEXT_TOKENS` | 3000 | `:26` | diagnostic-only, same assumption |
| `AgentLlmCaller.DEFAULT_MAX_TOKENS`, `AgentContextCompressor.MAX_TOOL_RESULT_CHARS` | 1024, 4000 | `:47-49`, `:74-75` | `static final` resolved at class-init from config; a window change at runtime never reaches them |
| `TokenEstimation` reserves | 256/256/512/256 | `:18-21` | absolute, fine at 16k+, dominant at 4k |
| `AgentBudgetPolicy` multiplier bound | — | `:28` | comment "= 12.5x @ n_ctx 4096" |

Correct today and to be kept: `RAGContext.DEFAULT_CONTEXT_WINDOW_TOKENS = 4096` (`:148`) as the
*last-resort* fallback when no server has been observed; `ConversationEngine.parseMaxTokens`
(`:1142-1149`) and the 845 reserve publication (`:447-455`).

### Item 22 — precedence remainder

- Ordinal chain (`ResolvedConfigBuilder.java:55-76`): jvm_arg 500 > worker_snapshot 450 > env 400 >
  ci_profile 350 > settings_json 300 > yaml 200 > auto_detect 150 > default 100. This is the right
  order for a desktop product (operator overrides beat the GUI, the GUI beats packaged defaults).
  The promotions exist because they predate the chain (promotion 2025-11/12; chain 2026-02-28,
  `312d57d61`) and are now a precedence lie: `/api/debug/effective-config` reports a GUI value as
  `jvm_arg`. Lane 0 fixed the reversed javadoc in `EnvRegistry.java:22-31`.
- 46 direct `System.getenv(` sites across 27 files and 121 `System.getProperty(` across 50 in
  `modules/*/src/main/java` (review-corrected counts; clusters:
  `app-services/bootstrap/phases/*`, `telemetry/*`, `worker-services/extract/TikaOcrRuntime.java` ×5,
  plus the sanctioned funnel in `configuration/*`). Six per-module ArchUnit rules each with named
  exemptions: `app-api/.../ArchitectureRulesTest.java:44-54`, `core/.../ArchUnitSanityTest.java:39-49`,
  `app-services/.../AppServicesWorkerGuardrailsTest.java:90-104`,
  `indexer-worker/.../IndexerWorkerGuardrailsTest.java:37-51`, `ui/.../UiApiGuardrailsTest.java:14-26`,
  `adapters-lucene/.../AdaptersLuceneGuardrailsTest.java:10-24`. `telemetry`, `app-inference`,
  `gpu-bridge`, `ai-backend` are uncovered.
- Dead-config gate: `gates/config-surface/dead-config-baseline.txt` exists; its scanner only sees
  keys that are `put` into the resolver. Lane 0 noted `search.pipeline.profile` /
  `index.pipeline.profile` yaml spellings are silently ignored because only the env/sysprop spelling
  is resolved (`EnvRegistry.java:257`). The gate extension ("every put key has a resolve, every yaml
  key has a put") is this lane's.

## Independent review fold (2026-09-01, session justsearch-public-9a; measured on the bundled llama-server b8571)

The reviewer's derisk probes changed the design below in six places; each is marked **[R#]**.
Measured facts, re-read and accepted by this contract's author:

- **[R1]** An explicit `-np` disables `kv_unified` on b8571 (`--help`: "enabled if number of
  slots is auto"). Measured `-c 32768 -np 2` → `kv_unified = false`, `n_ctx_seq = 16384` (two 16k
  slots) while `/props` still reports `n_ctx = 32768`. With `-kvu`: `n_ctx_seq = 32768`, same KV
  size. The live truth for "what window does a request get" is `n_ctx_seq` in the llama log, not
  `/props.n_ctx`.
- **[R2]** `q8_0` V-cache requires flash attention or the launch **aborts**; `-fa auto` resolved
  to on for CUDA and for `-ngl 0`, but pass `-fa on` explicitly. Only a cuda12 variant ships; "CPU"
  means `-ngl 0` on that binary. Measured KV at 32k q8_0: 544 MiB (both profiles).
- **[R3]** Qwen3.5 is a Gated-Delta-Net hybrid: 8 of 32 layers carry KV plus ~50 MiB/slot of
  recurrent state independent of n_ctx; measured 32 KiB/token f16, 17 KiB/token q8_0. Any
  dense-attention formula is ~4× wrong. No GGUF reader exists in the tree and `/props` on b8571 does
  not expose `n_ctx_train`, so a `min(tier, n_ctx_train)` clause cannot be computed.
- **[R4]** `--fit` (default on in b8571) already does memory fitting but **maximizes**: with `-c`
  omitted the 9B chose 242,944 tokens / 4 GB KV; with `-c 0` it dropped layers off the GPU instead
  of reducing context. An explicit `-c` that fits loads cleanly. NVML free VRAM is already in the
  argv path (`LlamaServerOps.java:304`), so it is available for the record without new plumbing.
- **[R5]** `UiSettings.setContextLength` clamps to `Math.max(512, …)` (`UiSettings.java:304-306`);
  Jackson deserializes through the setter, and `ConfigStoreRebuilder.java:85-88` forwards any
  `> 0` value at ordinal 300. A stored `0` therefore becomes a real 512 override today. The
  settings_json contributor **already exists** there and `SettingsController.java:317-321` already
  clears the sysprop on `<= 0`, so decision 4 is smaller than first briefed. `schemaVersion` exists
  (`UiSettings.java:16-19`, store `CURRENT_SCHEMA_VERSION = 1`) but there is no migration step and
  no way to tell a deliberate 4096 from the default.
- **[R6]** `EffectiveConfigController.java:503,523,538` renders the context row from the
  `justsearch.context.size.source` marker this lane deletes; the controller must be re-sourced
  (now lane-owned). Tempdoc 845's harness is an MCP-driven procedure (`845:217-241`) with no
  surviving artefacts; the arms are spelled out under acceptance instead.
- Counts corrected: `SettingsController` has **four** promotions (`server.exe :243`,
  `exclude_patterns :273`, `gpu.layers :300`, `context.size :323`); the six per-module ArchUnit
  rules also cover `System.getProperty/setProperty`; a coverage-preserving ratchet baseline is
  ~167 entries (getenv 46 sites/27 files, getProperty 121/50), not 49. Nothing in `scripts/`
  parses `application.yaml`, so "every yaml key has a reader" is net-new YAML parsing.
- `modules/ui/build.gradle.kts:2024` forwards `JUSTSEARCH_CONTEXT_SIZE` to headless eval; the env
  path must survive. `06-configuration-ssot.md:131` and `runtime-config-ownership-matrix.md:146`
  document the promotion as intended and must change with it. `modules/ui-web` has no
  contextLength control, so the "keep it visible?" question resolves to "it never was".

## Design decisions this lane must make (recommendation in bold)

1. **Window derivation [R3, R4].** **A ladder of explicit `-c` values, stepped down on launch
   failure**, no VRAM arithmetic, no GGUF reader: top rung by backend (32k with `-ngl > 0`, 8k at
   `-ngl 0` because CPU prefill at 32k is minutes per RAG ask), then 16k → 8k → 4k. A launch
   failure on b8571 is a fast hard abort, so stepping is cheap and detectable. Contribute the
   chosen rung at the existing `ORDINAL_AUTO_DETECT` (150, source `hardware_probe`,
   `ResolvedConfigBuilder.java:73,285`), after GPU detection, so `effective-config` explains it
   with no new mechanism; record rung, reason (fit / stepped-from / override) and NVML free VRAM in
   the activation record and the runtime manifest. A user `contextLength > 0` is an explicit
   override at ordinal 300; `0` means auto. Frame this as one instance of a **memory plan at
   activation** (window, `gpuLayers`, slots, KV type, reranker/VDU co-residency all compete for the
   same VRAM) so lane F does not add a second arbiter.
2. **Slots and KV [R1, R2].** **`-np 2 -kvu -ctk q8_0 -ctv q8_0 -fa on`**, keys
   `justsearch.llm.slots` (default 2) and `justsearch.llm.kv_type` (default `q8_0`). Two slots so
   a background delegate does not evict the foreground prompt-cache prefix
   (`841-agent-prompt-cache-efficiency.md`), not for memory. Measure the tok/s cost of q8_0 once
   and record it; if it exceeds 10% on the dev GPU, make f16 the default at the 16k rung and below.
3. **`ContextBudget` with ceilings.** One immutable record `{windowTokens, completionReserve,
   inputBudget, source}` built per request in the Head from `RAGContext.contextWindowTokens()` +
   the request's reserve, threaded into the hierarchical runner, injectors, agent tools and (as
   today) `RetrieveContextParams.maxContextTokens`. Every derived value is
   `min(fraction × inputBudget, absoluteCap)` with the cap's reason stated in code: hierarchical
   threshold = `inputBudget` (no cap: it is the budget); section target = `inputBudget / 2`, cap
   4k (map-step latency); external-context = `inputBudget / 4`, cap 2k (history is low-value per
   token); read-document page = `inputBudget / 2`, cap 4k tokens (agent-context hygiene: a 12k page
   at 32k defeats the compressor); tool-result compressor = `inputBudget / 4`, cap 2k. Every drop
   is surfaced (INFO log with before/after tokens; `rag.meta.context_truncated` for RAG).
4. **Precedence [R5].** Fix the setter clamp so `0` survives (`Math.max(0, …)`, with 512 as the
   minimum only for positive values); delete `maybeApplyContextSizeSysProp`, the
   `HeadlessApp:561-566` block and the `.source` marker; re-source `EffectiveConfigController`
   from the resolver's provenance. Then, as a second slice inside this lane, retire the other three
   promotions (`server.exe`, `exclude_patterns`, `gpu.layers`; the last feeds the GPU auto-detect
   mirror at `HeadlessApp.java:559-570`), each with its `effective-config` row re-sourced. If the
   second slice proves larger than one week, it stays a tracked item in this tempdoc with the
   reason, not a silent drop. Migration note: bumping the settings schema to 2 and resetting a
   stored 4096 to 0 discards a deliberate 4096; say so in the release note. Headless eval keeps
   working through the env path.
5. **getenv funnel.** **The rule lives in `modules/dead-code-audit`** next to
   `WholeProgramDeadCodeTest.java:103-105`, which already imports every `io.justsearch` module
   over an explicit list including the four uncovered ones; it bans `System.getenv/getProperty/setProperty`
   outside `io.justsearch.configuration..` with `gates/config-surface/sysaccess-allowlist.txt`
   (~167 entries) as a ratchet that may only shrink. The six per-module rules are then deleted.
   The yaml-reader gate is net-new YAML parsing in `scripts/ci`; size it as its own item (medium)
   and do it last.

## Acceptance criteria (all must be green before the lane closes)

- **Live, with a running model** (`ai_activate {chatProfile:"standard"}` on the dev GPU; the
  compact profile does not satisfy this lane): the llama-server log shows `n_ctx_seq` equal to
  the chosen rung **[R1]** (not only `/props.n_ctx`), `kv_unified = true`, `-fa on`, `q8_0` cache;
  `effective-config` shows `justsearch.context.size` sourced `auto_detected`/`hardware_probe` on a
  fresh data dir and `settings_json` when a user sets it, never `jvm_arg`; the ladder step-down is
  exercised once by forcing an unfittable top rung and observing the recorded reason.
- Live RAG arms (the 845 procedure, spelled out since no harness survives): ingest the 60-chunk
  corpus 845 used; ask the same question in quick (`maxTokens` 512) and standard (1024) modes via
  `/api/chat` with `rag.meta` captured from the SSE stream; assert `context_truncated = false`,
  `chunks used = chunks found` up to `top_k`, and prompt + completion ≤ the window in every arm.
  Record the table in this tempdoc.
- Unit: `HierarchicalShapeRunner` with a 4096 window never single-passes a document above the
  input budget; with a 32k window the threshold scales and every capped fraction hits its cap.
  `ExternalContextInjector` drop is logged. `ContextBudget` has a test that the fallback (no server
  observed) equals the configured window and never the old 8192. `UiSettings` round-trips `0`
  through Jackson unchanged **[R5]**.
- Greps, run as commands (line numbers move after #592):
  `grep -n "setSysProp" modules/ui/src/main/java/io/justsearch/ui/api/SettingsController.java`
  → no `CONTEXT_SIZE` site (slice 1) / none (slice 2);
  `grep -rn 'getProperty("justsearch.context.size' modules --include=*.java` → only under
  `modules/configuration`; `grep -rn "context.size.source" modules` → none.
- ArchUnit funnel rule green with the allowlist; the dead-config gate extension fails on a seeded
  unread key (prove it bites, then remove the seed).
- Docs regenerated: `environment-variables.md` states the default ("auto, derived; override with
  N"), `05-ai-architecture.md` §Token budgets describes `ContextBudget`, the ownership matrix row
  for `justsearch.context.size` says `settings_json` not `jvm_arg`. Run `/docs-maintenance`.
- `./gradlew.bat build -x test`, `:modules:app-services:test`, `:modules:app-inference:test`,
  `:modules:configuration:test`, `:modules:app-agent:test`; then the full `./gradlew.bat test`.
- Independent review by a session other than the implementer (`independent-review-required`).

## Verification tier and dev-stack rules

This lane needs the shared dev stack with a live LLM. Lanes A and C both need it in wave 1; lane B
does not. Lease windows explicitly (`leaseDurationSec`), never take over another lane's lease
without the user, and release when idle. Stack-driving may be delegated to a subagent only inside a
window this session holds and supervises.

## Takeover checklist

1. Branch only after `882-decision-review-lane0-hygiene` is on `main`
   (`git log --oneline origin/main | grep 882`); otherwise you inherit its conflicts.
2. Load `/inference-runtime` before touching `app-inference`; update its register before closing.
3. Re-read every `path:line` above on your base; line numbers move after 882.
4. Write the design decisions (§above) into this tempdoc as §B before coding (slice-execution
   pre-impl pass), then the post-impl critical-analysis pass.
5. Do not widen into lane E's passage shape or lane C's status path.

## Open questions for the owner

- Is a 32k top rung acceptable on the stated 16 GB / 8 GB-VRAM floor (measured KV at 32k q8_0 is
  544 MiB plus ~50 MiB per slot, so the fit is not in doubt), or should the top rung stay at 16k
  until the reranker/VDU co-residency is measured under load?
- Slice 2 of decision 4 (retiring the `server.exe`, `exclude_patterns`, `gpu.layers` promotions)
  is in scope but the largest blast radius here; confirm it stays in lane A rather than moving to
  its own tempdoc.
- (Resolved by review) `contextLength` was never a UI control; it stays a diagnostics override in
  `UiSettings` with `0` = auto.

---

## §B — Pre-implementation pass (chunk 1, PR 1; base `6c3ba431`, 2026-09-02)

Every `path:line` this chunk depends on, re-read on this base (lane 0 / #593 moved lines).
Corrections are marked **[moved]**.

### B.a — Item 8 / item 22 sites, verified

| Fold claim | On this base | Verdict |
|---|---|---|
| `UiSettings.java:254` `contextLength = 4096` | `modules/app-api/.../app/api/UiSettings.java:254` `private int contextLength = 4096;` | confirmed (package is `io.justsearch.app.api`) |
| `UiSettings.java:304-306` clamp `Math.max(512, ...)` **[R5]** | `UiSettings.java:304-306` `setContextLength` -> `this.contextLength = Math.max(512, contextLength);` | confirmed |
| `schemaVersion` exists, no migration | `UiSettings.java:19` `private int schemaVersion = 1;`; `UiSettingsStore.java:47` `CURRENT_SCHEMA_VERSION = 1` | confirmed |
| `ConfigStoreRebuilder.java:85-88` forwards `> 0` at ordinal 300 | `modules/app-services/.../config/ConfigStoreRebuilder.java:85-87` in `contributeUiSettings`; `putSettings` -> `ResolvedConfigBuilder.java:215-217` `put(key, ORDINAL_SETTINGS_JSON=300, "settings.json", key, value)` | confirmed |
| `SettingsController.java:304-325` `maybeApplyContextSizeSysProp`, `<= 0` clears | `SettingsController.java:304-325`; call sites `:130` (PUT) and `:447` (reset). Marker constants `:46-47` | confirmed |
| `SettingsController` has four promotions | `:222` serverExe, `:247` excludePatterns, `:281` gpuLayers, `:304` contextSize **[moved]** (the fold's 243/273/300/323 are the `setSysProp` lines inside each) | confirmed |
| `HeadlessApp.java:561-566` startup promotion, guarded by `JUSTSEARCH_CONTEXT_SIZE` | `modules/ui/.../HeadlessApp.java:561-565`; `System.getenv("JUSTSEARCH_CONTEXT_SIZE")` at `:561` | confirmed |
| `EffectiveConfigController` renders the row from the `.source` marker | `EffectiveConfigController.java:490` reads the marker, `:496-498` promotes `system_property`->`ui_settings`, `:508-512` publishes `uiOwnershipProp`, `:525` emits the row **[moved]** (fold said 503/523/538) | confirmed |
| `LlamaServerOps` argv builder + `gpuCapabilitiesService.snapshot()` | `LlamaServerOps.java:215-337` `startLlamaServer`; `-c` at `:277-279`; `-np 1` VDU-only at `:236-242`; `snapshot()` at `:308` **[moved]** (fold said `:304`) | confirmed |
| `ServerPropsOps` `/props` readback | `ServerPropsOps.java:188-198` `applyContextInsightsFromProps`; `warnIfConfiguredContextExceedsActual` at `:259-268` | confirmed |
| `InferenceConfig` `ctxSize <= 0 -> 4096` + builder default | `InferenceConfig.java:133-134` and `:571` `private int contextSize = 4096;` | confirmed |
| `ResolvedConfigBuilder.java:1048` `resolveInt("justsearch.context.size", 8192)` | `ResolvedConfigBuilder.java:1039` **[moved]** | confirmed |
| `modules/ui/build.gradle.kts:2024` forwards `JUSTSEARCH_CONTEXT_SIZE` | `modules/ui/build.gradle.kts:2020` **[moved]**; the env path must survive | confirmed |
| `06-configuration-ssot.md:131` documents the promotion | confirmed; `:130` also names `justsearch.context.size` in the sysprop-mirror list | confirmed |
| `runtime-config-ownership-matrix.md:146` | the row is at `:143` **[moved]**, reading `sysprop > env > default` | confirmed |

### B.b — Facts the fold did not state that change the design

1. **`ConfigStoreRebuilder` reads a primitive `int`, not `Integer`.** `UiSettings.getContextLength()`
   returns `int` (`UiSettings.java:300`); `SettingsController.java:306` boxes it into an `Integer`
   whose null check (`:317`) can therefore never fire. Deleting `maybeApplyContextSizeSysProp`
   removes that dead branch with it.
2. **`StoreFormatVersions.requireReadable(storeId, observed, current, absent, readableLegacy...)`**
   (`modules/configuration/.../persistence/StoreFormatVersions.java:15-31`) throws
   `UnsupportedStoreVersionException` for any version that is neither `current` nor in the varargs
   legacy list, and `UiSettingsStore.load()` (`:87-90`) catches only `CorruptDurableStoreException` --
   so an unlisted version is **fatal, not quarantined**. Today the call is
   `requireReadable("ui-settings", observed, 1, 0, 0)`. Bumping to 2 therefore **requires** adding
   `1` to the readable-legacy varargs, or every existing install fails to start. The migration then
   runs on the version `requireReadable` returns. (This answers the brief's "verify 882's quarantine
   path treats a schemaVersion 1 file as migratable, not corrupt": it does **not** today; adding the
   legacy-version listing is what makes it so.)
3. **A second literal default for the quantity exists** at `RuntimeActivationService.java:1203`
   (`startSelfTestServer`): `settings.getContextLength() > 0 ? ... : 4096`. See B.c for why it stays.
4. **`VramFlagsUtil.mergeRecommendedFlags` skips a flag already present in the command**
   (`VramFlagsUtil.java:57-65`) and always skips `-c`/`-ngl` (`:41-49`).
   `VramRequirements.recommendedLlamaServerFlags` (`:88-96`) returns only `{-c, -ngl, -fa on}`
   (+ `-ctk/-ctv q4_0` at the 8 GB tier). Once this chunk emits `-fa`, `-ctk` and `-ctv`
   unconditionally, that merge inside `LlamaServerOps` becomes a **provably inert call** -- swept in
   this PR (the `RuntimeActivationService` self-test caller keeps it).
5. **A step-down / relaunch precedent already exists**: `LlamaServerOps.relaunchWithoutReasoningBudget`
   (`:478-514`), invoked from `waitForServerHealth` (`:388-397`) on `Reason.PROCESS_EXITED`. The
   ladder hooks the same seam instead of inventing one.
6. **`InferenceConfig`'s compact constructor rejects `contextSize <= 0`** (`:59-61`). "Builder
   default 0" therefore cannot mean "store 0 in the record"; it means the builder's *field* default
   is 0 = auto and `build()` resolves it through the ladder policy. Same for `fromEnvironment`.
7. **`InferenceStatusResponse` is a schema-generated wire record** (`WireRecordSchemaGenTest:158-161`
   -> `SSOT/schemas/inference-status-response.v1.json` -> `scripts/codegen/gen-wire-schema-types.mjs`
   -> `modules/ui-web/src/api/generated/schema-types/inference-status-response.ts`). Publishing the
   context-window record there means regenerating both; `contracts/**` is protobuf only, so no
   `--gate wire` subject is touched.
8. **`RAGContext.java:143-148`'s javadoc** ("Matches the shipped default (`InferenceConfig` builder
   default, `UiSettings.getContextLength()`)") stops being true the moment both become auto. The
   constant stays (the fold is right that it is the correct last-resort fallback); the javadoc is
   corrected in place.

### B.c — Decisions taken inside this chunk

- **An explicit override does not step down.** `ContextWindowPolicy.plan` returns a one-rung ladder
  when an explicit `justsearch.context.size` is present, so an operator's value is honoured or fails
  loud. Silently serving a smaller window than an operator asked for would be this lane's own
  precedence lie in a new place. Recorded reason: `override`.
- **The resolver row carries the *planned* rung; `/props` carries the *observed* one.** After a
  step-down the ord-150 contribution is stale by construction (`ConfigStoreRebuilder.rebuild` does
  not re-run `contributeAutoDetected` -- which is exactly what the `HeadlessApp` Phase-E sysprop
  mirror exists to work around). `EffectiveConfigController.keyContextSize` already prefers the
  observed runtime value and reports `source: "runtime"` with the resolver value as a `conflicts[]`
  entry; that is the honest shape and it is kept.
- **Source names are reported verbatim from the resolver** (`jvm_arg`, `env_var`, `settings.json`,
  `auto_detected`, `yaml`, `default` -- `ResolvedConfigBuilder.java:202,217,241-242,285`). The
  acceptance text spells the settings source `settings_json`; the resolver spells it `settings.json`.
  Re-spelling it in the controller would be a second vocabulary (`catalog-verbatim`), so the
  controller emits the resolver's own string and the ownership-matrix doc is corrected to match.
- **`RuntimeActivationService.startSelfTestServer` keeps its `4096`.** It is a VRAM-delta *probe*
  launch, not the runtime window, and because it already reads `settings.getContextLength() > 0`
  first, its behaviour is *unchanged* by this PR (before: 4096 from the settings default; after:
  4096 from its own fallback). Raising it to the ladder top rung would change what the self-test
  measures and could fail it on small GPUs. A WHY comment now says so at the site.
- **A deliberate user `4096` is discarded** by the schema 1 -> 2 migration. There is no way to tell
  it from the shipped default (fold **[R5]**), `modules/ui-web` never had a control for it, and the
  cost of keeping it is that every existing install stays pinned to the smallest rung forever.
  Release-note line required.

## §C — Post-implementation critical analysis (chunk 1, PR 1; 2026-09-02)

### C.1 — Wrong-gate checks (each grepped at the set-site, not inferred)

| Claim the change depends on | How it was checked | Result |
|---|---|---|
| The step-down fires on the failure an unfittable `-c` actually produces | `grep -rn "Reason.PROCESS_EXITED" modules --include=*.java` -> the only throw site reachable from a launch is `LlamaServerOps.awaitServerHealth:515`, in the `!process.isAlive()` branch, i.e. exactly "the process we just launched died". `TransitionRunner:641,653` are code MAPPINGS, not throws into this path. | confirmed |
| Every path that waits for health has a plan to step down | `grep -rn "waitForServerHealth()"` -> 4 call sites in `InferenceLifecycleManager` (start, reload x2, activation) + `scheduleRecoveryTask` in `LlamaServerOps`. All are preceded by `startLlamaServer()`, which is where `contextPlan` is set. The recovery path calls `stopLlamaServer(); startLlamaServer(); waitForServerHealth()` — so a crash-recovery relaunch replans, it does not inherit a stale rung. | confirmed |
| A health-check TIMEOUT does not step the window down | `relaunchAtLowerContextRung` gates on `PROCESS_EXITED` only. A timeout means the model is loading slowly (the 120 s default exists because a 9B cold load legitimately exceeds 30 s); shrinking the window for that would be the wrong-gate mistake in its classic form. | deliberate |
| The two relaunch handlers cannot loop | `relaunchWithoutReasoningBudget` sets `ThinkingSupport.UNSUPPORTED` + `reasoningBudgetRequested=false`, both of which its own guard refuses on re-entry; `relaunchAtLowerContextRung` consumes a rung from a 4-element list. The `while(true)` in `waitForServerHealth` therefore terminates in at most 5 iterations. | confirmed |
| The derived rung is computed AFTER GPU detection | `augmentDerivedContextWindow` takes the map `augmentGpuAutoDetectionAndMirror` returned (Phase F is what writes `justsearch.gpu.layers`), and reads the user's sysprop/env first. Pinned by `HeadlessAppContextWindowAutoDetectTest.gpuLayersFromAutoDetectGiveGpuRung` + `explicitZeroLayersGivesCpuRung`. | confirmed |
| The rung the resolver reports and the rung the launch tries are the same number | `ContextWindowPolicyTest.autoTopRungByBackend` asserts `autoTopRung(b) == auto(b, null).topRung()` for both backends — the Head contributes the former, `LlamaServerOps` launches the latter. | confirmed |

### C.2 — Defect found by this pass and fixed in the same PR

**`ConfigStoreRebuilder.rebuild` silently drops ordinal 150.** `rebuild` re-derives the config from
`contributeBaseSources()` + `contributeUiSettings(...)` only. Ordinal-150 values come from a probe
that runs once, at startup, in the Head — so the first settings PUT, AI install or activation after
boot would have erased the derived window's provenance, leaving `/api/debug/effective-config` with
no source for `justsearch.context.size` at all.

This was invisible before 883 because the only ordinal-150 values were GPU flags, and they survive a
rebuild by *also* being written as system properties — the `HeadlessApp` "Phase E sysprop-mirror",
whose own javadoc names `ConfigStoreRebuilder.rebuild` as the reason it exists. That mirror is the
promotion pattern this lane deletes (it resolves at 500 and reports as `jvm_arg`), so the fix could
not be to mirror the window too.

Fix: `ConfigStoreRebuilder.rememberAutoDetected(map)`, called once by `HeadlessApp` with the same
map it contributes, re-contributed by `rebuild` at ordinal 150. Regression tests:
`rebuildPreservesTheDerivedWindow` and `rebuildKeepsTheOrdinalChainIntact` (the user override must
still win at 300 after a rebuild). Static process-wide state, for the same reason
`ConfigStore.setGlobal` is: one probe per process, and the four services that call `rebuild` have no
path to it.

### C.3 — Test precision (does each test pass for the right reason?)

- `LlamaServerLaunchFlagsTest` asserts the **exact ordered list**, not `contains`. The fold's [R1]
  failure is `-np` present and `-kvu` absent — a `contains("-np")` test passes on exactly the launch
  line that silently halves the window, so the ordering assertion is the whole point.
- `UiSettingsContextLengthTest.zeroRoundTrips` goes through Jackson rather than calling the setter
  and reading the getter back. The old clamp bug was only observable across serialization, because
  Jackson deserializes through the setter.
- `ConfigStoreRebuilderTest.autoContextLengthContributesNothing` asserts the *ordinal and source
  name*, not just the value. Asserting `32768` alone would also pass if `contributeUiSettings` had
  contributed `32768` at ordinal 300 — a green for the wrong reason.
- `EffectiveConfigContextSizeSourceTest.markerIsIgnored` sets the deleted marker sysprop and asserts
  the row is unaffected AND that `owner` / `uiOwnershipProp` are absent — a negative test that
  cannot pass by the marker simply not being set.
- `ServerPropsOps` mismatch: the predicate `isContextWindowMismatch` is asserted directly (the
  defect was the comparand, not the comparison), plus one readback test proving the launched-rung
  supplier is what `updateFromPropsBestEffort` consults, with an `InferenceConfig` whose
  `contextSize` deliberately disagrees.
- `UiSettingsStoreContextLengthMigrationTest.schemaOneIsReadable` asserts `lastRecovery().isEmpty()`
  — a migration must not be a quarantine. Without it, a bump that forgot the readable-legacy listing
  would still "pass" a migration test that only checked the resulting value after defaults loaded.

### C.4 — Known limitations and deviations, stated rather than hidden

1. **The acceptance grep `grep -rn "context.size.source" modules` is not literally empty.** Two hits
   remain by choice: the `EffectiveConfigController` javadoc that *labels* what was deleted and why
   (retire-with-a-sweep asks for "delete or label"), and `EffectiveConfigContextSizeSourceTest`,
   whose whole job is proving the marker is now inert. Deleting the regression test to satisfy a
   grep would be making the check pass rather than making the property true.
2. **`RuntimeActivationService.startSelfTestServer` still contains a literal 4096.** Reasoning and a
   WHY comment at the site: it is a VRAM-delta probe parameter, and its behaviour is unchanged by
   this PR. See §B.c.
3. **`VramFlagsUtil.mergeRecommendedFlags` was removed from `LlamaServerOps` only.** The
   `RuntimeActivationService` self-test still calls it, and `VramRequirements` still ships the
   8 GB-tier `q4_0` recommendation. That recommendation is now unreachable on the main launch path
   (this PR always sets `-ctk`/`-ctv`), which makes `justsearch.llm.kv_type` the single authority
   for the runtime cache type. Whether the self-test should follow is a lane-F memory-plan question,
   not this chunk's.
4. **Between health-OK and the first `/props` read, `configuredContextTokens()` reports the PLANNED
   rung, not the stepped-down one.** `/props` is read immediately after health in
   `logServerProperties()`, so the window is sub-second, and `RAGContext` prefers the observed value
   as soon as it exists. Recorded rather than fixed: closing it would mean the launch writing back
   into the resolver, which is the promotion shape in a new coat.
5. **`UiSettings.schemaVersion` (the field, `UiSettings.java:19`) is read by no Java code** — the
   envelope's `schemaVersion` is the authority `UiSettingsStore` reads. Pre-existing dead field,
   left alone; noted here rather than silently swept in a PR about something else.

### C.5 — Live-window acceptance items still OPEN (nothing below was measured in PR 1)

None of these can be checked without a running model; the orchestrator schedules the window.

1. `n_ctx_seq` in the llama-server log equals the chosen rung (**not** only `/props.n_ctx` — fold
   [R1] is precisely that these can disagree).
2. `kv_unified = true` in the llama-server log with `-np 2` present.
3. `-fa on` accepted and a `q8_0` K/V cache reported at load.
4. `effective-config` shows `justsearch.context.size` sourced `auto_detected` / `hardware_probe` on
   a **fresh data dir**, and `settings.json` when a user sets one — and never `jvm_arg`.
5. The ladder step-down exercised once by forcing an unfittable top rung, with
   `/api/inference/status.contextWindow.reason` observed as `stepped-from:<n>`.
6. q8_0 vs f16 generation tok/s on the dev GPU (design says: if q8_0 costs >10%, f16 becomes the
   default at the 16k rung and below). Register entry Q-002.
7. The 845 RAG arms: ingest the 60-chunk corpus, ask the same question in quick (`maxTokens` 512)
   and standard (1024) modes via `/api/chat`, assert `context_truncated = false`,
   `chunks used = chunks found` up to `top_k`, and prompt + completion <= the window in every arm.
   Table recorded in this tempdoc.
8. An upgraded install (existing `settings.json` at schema 1 with `contextLength: 4096`) starts,
   migrates to 0, and runs at the derived rung — the migration is unit-tested but the upgrade path
   is not.

### C.6 — Still open in this lane after PR 1

Decision 3 (`ContextBudget` and the item-9 constants), decision 5 (the `getenv` funnel + the yaml
reader gate), decision 4 slice 2 (the `server.exe` / `exclude_patterns` / `gpu.layers` promotions),
and **ADR-0047 "Context window as a derived resource"** (number reserved; written in a later chunk,
not this one).

## §D — Independent review fold (PR #596, 2026-09-02): NEEDS-FIXES applied

The reviewer returned one design-premise blocker and four should-fix items. All are applied on
`worktree-lane-A`; two were refuted and are recorded as accepted-as-is.

### D.1 — BLOCKER: the step-down premise was unmeasured. Fixed by `-fit off`.

The ladder steps down only on `PROCESS_EXITED` (`LlamaServerOps.relaunchAtLowerContextRung` ←
`awaitServerHealth`'s `!process.isAlive()` branch), which assumes an unfittable `-c` is a hard
abort. But b8571 defaults `--fit on`, and the fold's [R4] measured that it MAXIMIZES rather than
fits (242,944 tokens / 4 GB KV with `-c` omitted) and reallocates layers. The reviewer's read —
that `--fit` only touches UNSET arguments, so our explicit `-c` and `-ngl` are outside its remit —
is probably right but was inferred, not measured.

Verified against the bundled binary rather than reasoned about
(`modules/ui/native-bin/llama-server/variants/cuda12/llama-server.exe --help`, version
`8571 (e397d3885)`):

```
-fit,  --fit [on|off]                   whether to adjust unset arguments to fit in device memory ('on' or
                                        'off', default: 'on')
                                        (env: LLAMA_ARG_FIT)
```

So the flag exists, is spelled `-fit off`, and IS on by default. The fix is to stop depending on
the inference: the launch now passes `-fit off` unconditionally
(`LlamaServerOps.memoryPlanFlags`). The premise the ladder rests on — a rung that does not fit
produces a hard, detectable abort — is now a property of the launch line rather than of a
heuristic's documented scope. Argv docs, `environment-variables.md` and register D-010 updated.
The live window still measures an actual unfittable `-c` (exit code + log) before merge; this
change makes that measurement mean something.

### D.2 — The window is now on the runtime manifest, not only the status endpoint.

Decision 1 says "record rung, reason and NVML free VRAM in the activation record **and the runtime
manifest**"; PR 1 only did the former. `RuntimeManifest.AiInfo` gains a nullable additive
`contextWindow` carrying the same `OnlineAiRuntimeIntrospection.ContextWindow` record the status
endpoint publishes — a projection of one authority, not a second shape. Populated where
`thinkingSupport` already is (`HeadAssembly.launchedContextWindow` → `publishAi` → both listener
call sites), null when this process launched no server. No schema bump (nullable + `NON_NULL`),
matching the tempdoc-682 build-pin and tempdoc-835 thinking-verdict precedents.

Tests: `RuntimeManifestSchemaCompatibilityTest.aiContextWindowRoundTripsAndStaysOptional` (round
trip, absent-stays-absent) and `RuntimeManifestControllerRedactionTest` (the window survives the
public projection — it is a capability fact, and free VRAM is already public on
`/api/inference/status.gpu`).

`check-runtime-manifest-closure` still fails on `main` for two unrelated pre-existing
`sibling-file` violations (`packaging/mcpb/server/index.js:33`,
`scripts/sandbox/mcp-typed-confirm.mjs:109`, both reading `runtime/api-port.txt`, last touched in
#468). This PR adds no `<dataDir>/runtime/` file and no new sibling; it adds a field to the
existing manifest.

### D.3 — Adopted external servers are no longer judged by our own rung.

`ServerPropsOps` set `externalServerContextTooSmall` from `ctx < config.get().contextSize()`. Once
that configured value became a derived 32768 rung, every adopted BYO llama-server below 32k — an
entirely workable 8192, say — would have been flagged too small. The comparand is now
`ContextWindowPolicy.MIN_USABLE_ADOPTED_TOKENS` (4096, and defined AS the ladder's bottom rung so
the two cannot drift): the smallest window this app will run its own engine at is the honest floor
for one it adopts. Tests cover 8192 → false, 4096 → false, 2048 → true, null → false, and that the
constant is the ladder's last rung.

### D.4 — Ownership-matrix prose now says `settings.json`, matching the resolver.

§B.c decided to report the resolver's own spelling; the new prose said `settings_json` in three
places. Fixed at the source (`scripts/docs/runtime-config-matrix-lib.mjs`), not in the generated
file, and regenerated. `grep -c settings_json docs/reference/configuration/runtime-config-ownership-matrix.md` → 0.

### D.5 — Test precision.

(a) `LlamaServerOps.buildLaunchCommand(cfg, rc, gpuLayers, plan)` is now a pure, package-private
static that builds the FULL argv; `startLlamaServer` keeps only the side effects. The test asserts
the complete ordered list. **Mutation-verified rather than assumed**: deleting
`command.addAll(memoryPlanFlags(...))` fails 5 of the 11 tests in `LlamaServerLaunchFlagsTest`
(exact-argv, memory-plan-block, CPU argv, VDU argv, thinking-off), re-run and restored.

(b) `HeadlessAppContextWindowAutoDetectTest` gains two end-to-end cases over the real composition:
`augmentDerivedContextWindow(augmentGpuAutoDetectionAndMirror(probe, 12GB))` yields the GPU rung,
and the swapped order yields the CPU rung. Asserting the two orders DIFFER is what makes swapping
the calls in `resolveConfig` a test failure rather than a silent 4x context loss on every GPU box.

### D.6 — Nits.

(a) `planContextWindow` now takes BOTH the provenance and the value from the same
`ConfigResolution` (it previously read the ordinal from the resolution and the number from
`InferenceConfig`, two reads of one fact that can disagree), with an unparseable or non-positive
value falling back to the derived ladder rather than to a bad override. It no longer takes
`InferenceConfig` at all.
(b) An override that exhausts its one-rung ladder now logs at ERROR naming the override and the
remedy ("set it lower, or set it to 0"), instead of the ladder-exhausted WARN which described
something that had not happened.
(c) `applyContextInsightsFromProps` javadoc records what `/props.n_ctx` cannot prove: it reports
the TOTAL context even when `kv_unified` is off, so a matching `n_ctx` is not evidence a request
gets the full window. The guarantee for [R1] is the argv-order test plus the live `n_ctx_seq` log
check — with an explicit instruction not to add a check here that claims otherwise.
(d) **Ownership exceptions taken by this lane**, named per §C.4: `InferenceStatusResponse.java`,
`OnlineAiRuntimeIntrospection.java`, `RuntimeManifest.java` (app-api, additive fields);
`InferenceHandlers.java`, `RuntimeManifestPublisher.java`, `RuntimeManifestListenerWiring.java`
(modules/ui, wiring the additive fields); `HeadAssembly.java` (one delegating accessor);
`SSOT/schemas/inference-status-response.v1.json` + its `modules/ui/src/main/resources` dual copy
and `modules/ui-web/src/api/generated/schema-types/inference-status-response.ts` (all three
REGENERATED, not hand-edited); `scripts/docs/runtime-config-matrix-lib.mjs` (the generator for a
lane-owned doc). None is a lane-C or lane-B owned surface.

### D.7 — Refuted by the reviewer, accepted as-is (no change)

The `RuntimeActivationService` VRAM-delta probe literal (§B.c), override = one rung / fail loud
(§B.c), the migration ordering, and `-c` always being explicit.

### D.8 — Live-window items, updated

§C.5 stands, with one addition and one sharpening:

- **(new) `-fit off` is accepted by the running build** and appears in the launch line.
- **(sharpened) item 5**: force an unfittable top rung and record the llama-server **exit code and
  the log line**, not merely that the reason string changed — that is the measurement this PR's
  `-fit off` change exists to make meaningful.

## Live verification (2026-09-02, lane A PR 1)

Stack built from `2e7d6e99` (`distFrom lane-A`), runId `cb635ac5-d93c-40c5-8b46-97bed2f62afb`,
API `http://127.0.0.1:50556`, dataDir `modules/ui-web/.dev-data`. Orchestrator holds the lease; all
observations below are over HTTP, the bundled binary, and log files. llama-server
`version: 8571 (e397d3885)`, RTX 4070 12281 MiB.

**Status: HALTED after step A. The design premise did not survive contact.** Steps B-F were not
run; see the decision needed at the end.

### Item 11 precondition — no legacy settings file to migrate

`modules/ui-web/.dev-data` contains NO `settings.json` (`find .dev-data -name "settings.json*"` →
empty; there is no `ui/` subdirectory). The store had not saved yet, so this data dir carries no
schema-1 file. `GET /api/settings/v2` reports `llm.contextWindow: 0` — the new auto default,
reached as a fresh default rather than by migration. The upgrade-path arm (E) therefore needs a
synthetic schema-1 file, which was not reached before the halt.

`GET /api/runtime/manifest` at rest: `ai.phase = OFFLINE`, `thinkingSupport = UNKNOWN`, and **no**
`ai.contextWindow` key — correct, nothing had been launched.

### A.1 — `-fit off` exists and is on by default (confirmed)

```
-fit,  --fit [on|off]                   whether to adjust unset arguments to fit in device memory ('on' or
                                        'off', default: 'on')
                                        (env: LLAMA_ARG_FIT)
```

### A.2 — `-c 262144` at q8_0 LOADS on a 12 GB card. The ladder's top rung is wrong.

```
llama-server.exe -m Qwen_Qwen3.5-9B-Q4_K_M.gguf -c 262144 -ngl 99 -np 2 -kvu \
  -ctk q8_0 -ctv q8_0 -fa on -fit off --port 8099
```

`EXIT=124` — i.e. `timeout 120` had to KILL it, because it was serving. Log:

```
load_tensors: offloaded 33/33 layers to GPU
load_tensors:   CPU_Mapped model buffer size =   545.62 MiB
load_tensors:        CUDA0 model buffer size =  5060.88 MiB
llama_context: n_seq_max     = 2
llama_context: n_ctx         = 262144
llama_context: n_ctx_seq     = 262144
llama_context: flash_attn    = enabled
llama_context: kv_unified    = true
llama_kv_cache:      CUDA0 KV buffer size =  4352.00 MiB
llama_kv_cache: size = 4352.00 MiB (262144 cells,   8 layers,  2/1 seqs), K (q8_0): 2176.00 MiB, V (q8_0): 2176.00 MiB
llama_memory_recurrent:      CUDA0 RS buffer size =   100.50 MiB
sched_reserve:      CUDA0 compute buffer size =   808.02 MiB
slot   load_model: id  0 | task -1 | new slot, n_ctx = 262144
slot   load_model: id  1 | task -1 | new slot, n_ctx = 262144
```

VRAM total: 5060.88 (model) + 4352.00 (KV) + 100.50 (recurrent) + 808.02 (compute) = **10,321 MiB
of 12,281 MiB**, with the Worker's ONNX encoders also up. The model's ENTIRE 262,144-token training
context fits, with two slots, on the stated 12 GB dev card.

This falsifies decision 1's sizing, not its mechanism. The fold's [R3] said a dense-attention
formula would be ~4x wrong and no GGUF reader exists, so the design chose a conservative ladder
topping out at 32k. The measurement says the conservative rung gives away **8x** the window the
hardware supports — and llama-server says so itself at 32768:

```
llama_context: n_ctx_seq (32768) < n_ctx_train (262144) -- the full capacity of the model will not be utilized
```

### A.2b — an actually-unfittable `-c` IS a hard abort. The step-down gate is correct.

```
... -c 1000000 -ngl 99 -np 2 -kvu -ctk q8_0 -ctv q8_0 -fa on -fit off
```

`EXIT=127`, after:

```
llama_context: n_ctx_seq     = 1000192
llama_context: n_ctx_seq (1000192) > n_ctx_train (262144) -- possible training context overflow
llama_kv_cache:      CUDA0 KV buffer size = 16604.75 MiB
CUDA error: out of memory
D:\a\llama.cpp\llama.cpp\ggml\src\ggml-cuda\ggml-cuda.cu:98: CUDA error
```

A rung that does not fit produces a nonzero exit with the process dead — exactly the shape
`awaitServerHealth` turns into `Reason.PROCESS_EXITED` and `relaunchAtLowerContextRung` acts on.
**The step-down mechanism and its `PROCESS_EXITED` gate are verified.** `-fit off` did not mask it.

### A.3 — the 32768 rung loads, and reproduces [R2]'s KV measurement exactly

`EXIT=124` (killed at 100 s while serving):

```
load_tensors: offloaded 33/33 layers to GPU
llama_context: n_ctx         = 32768
llama_context: n_ctx_seq     = 32768
llama_context: flash_attn    = enabled
llama_context: kv_unified    = true
llama_kv_cache:      CUDA0 KV buffer size =   544.00 MiB
llama_kv_cache: size =  544.00 MiB ( 32768 cells,   8 layers,  2/1 seqs), K (q8_0):  272.00 MiB, V (q8_0):  272.00 MiB
llama_memory_recurrent:      CUDA0 RS buffer size =   100.50 MiB
sched_reserve:      CUDA0 compute buffer size =   501.00 MiB
```

544.00 MiB matches the review fold's [R2] figure to the MiB, and `-np 2 -kvu` gives
`n_ctx_seq == n_ctx == 32768` with `kv_unified = true` — [R1]'s halving does not occur with the
argv this PR ships. Total at 32k: 6,206 MiB of 12,281.

KV scales linearly and exactly: 544 MiB / 32768 = 4352 MiB / 262144 = **17.0 KiB/token** at q8_0,
confirming [R3]'s per-token figure and making the window/VRAM relationship predictable after all
(for THIS model — 8 of 32 layers carry KV, which is why it is so cheap).

### Decision needed before B-F can mean anything

Everything that runs after this point (the `fit` reason, the effective-config row, the
precedence arms, the RAG arms) reads out a 32768 that the hardware says should be much larger, and
the forced step-down in D would be measuring a ladder whose top rung is the wrong number. Options,
for the owner:

1. **Raise the GPU top rung** (e.g. to `n_ctx_train`, or to a 131072/262144 rung) — the ladder,
   the step-down and `-fit off` all keep working unchanged; only `GPU_TOP_RUNG` moves. The measured
   headroom at 262144 is ~1.9 GB, which is thin once the reranker and a VDU batch are co-resident
   (the owner's own open question in this tempdoc), so 131072 (KV ~2176 MiB, total ~8.1 GB) is the
   conservative version of the same correction.
2. **Keep 32768 and record why** — but the tempdoc must then say it is a co-residency budget
   decision, not a fit decision, because "it is what fits" is now measurably false.

Either way the ladder gains a rung above 32768 and the step-down is what protects smaller cards.
This is a one-constant change plus tests, not a redesign — but it is a design change, so it stopped
here rather than being made unilaterally.

Not run, and why: B, C, D, E, F all pend this decision. The `JUSTSEARCH_CONTEXT_SIZE` env arm was
out of scope for the window regardless (needs a restart the orchestrator owns).

Cleanup: all three standalone servers were bounded by `timeout` and are gone
(`tasklist | grep llama-server` → none; no listeners on 8098/8099). The dev stack was left running
and untouched; the AI runtime was never activated.

### Owner decision on step A (2026-09-02): keep 32768, as a BUDGET rung

Option 2. `GPU_TOP_RUNG` stays 32768 and the record is corrected: the top rung was never "what
fits" — it is a budget the owner set, for three reasons:

- **(a) Latency.** Prefill per RAG ask scales with the prompt, and PR 2's `ContextBudget` fractions
  will fill whatever window exists. The rung is what bounds worst-case ask latency.
- **(b) Co-residency.** KV is reserved up front for the whole `n_ctx` whether used or not, and the
  same card must also hold the embedding / SPLADE / NER encoders, the reranker and VDU batches
  (the co-residency open question at the top of this tempdoc). 544 MiB at 32k versus ~2.2 GB at
  128k is headroom this app chooses to keep.
- **(c) The ladder's job is stepping DOWN on smaller cards, not maximizing on big ones.**

Users who want more set `contextLength` / `JUSTSEARCH_CONTEXT_SIZE`; the override has no upper
clamp below `n_ctx_train`. llama-server's
`n_ctx_seq (32768) < n_ctx_train (262144) -- the full capacity of the model will not be utilized`
is therefore **expected output**, not a defect.

**Consequence: the recorded reason `fit` is renamed `top-rung`.** With the rung documented as a
budget, a wire value called `fit` would assert exactly what the measurement disproves — and would
read as the opposite of the `-fit off` the same launch passes. `top-rung` says what actually
happened (the ladder's first rung loaded; no step-down) and sits correctly beside `override` and
`stepped-from:<n>`. `ContextWindowPolicy.REASON_FIT` → `REASON_TOP_RUNG`, with the tests, the
status/manifest javadocs, `05-ai-architecture.md`, `environment-variables.md` and register D-010
updated. The measured evidence (three probes, 17.0 KiB/token) is recorded in D-010 as the design's
justification rather than left in this tempdoc alone.

## Live verification part 2 — B-F (2026-09-02, stack f3e569c7 built from f143a118)

API `http://127.0.0.1:53366`, dataDir `modules/ui-web/.dev-data`, standard profile, RTX 4070.
Binary identity confirmed by `contextWindow.reason == "top-rung"` (the value renamed in `f143a118`).

**Activation route note.** `POST /api/ai/runtime/activate {variantId:"cuda12"}` returns
`RUNTIME_VARIANT_NOT_INSTALLED` on this data dir (the install registry has no variant; the dev
stack stages the binary without registering it). All arms below therefore drive the engine through
the shipped desired-state path — `POST /api/settings/v2 {ui:{chatEnabled:...}}` → `RuntimeReconciler`
— which is the tempdoc-737 autostart path and exercises exactly the same launch code.

### Results

| # | Check | Result |
|---|---|---|
| B1 | llama-server log at activation | **PASS** `n_ctx_seq 32768`, `n_ctx 32768`, `kv_unified true`, `n_seq_max 2`, `flash_attn enabled`, `K (q8_0)`/`V (q8_0)`, `offloaded 33/33`, KV 544.00 MiB, both slots `n_ctx = 32768` |
| B2 | `/api/inference/status.contextWindow` | **PASS** `{rung:32768, reason:"top-rung", freeVramBytes:9468739584, slots:2, kvType:"q8_0"}`; `llmContextTokens` 32768 |
| B3 | `/api/debug/effective-config` context row | **PASS** `source auto_detected`, `sourceOrdinal 150`, `sourceDetail hardware_probe`, value 32768. The candidate list shows `jvm_arg`(500) and `env_var`(400) present **with no value** — the promotion is gone |
| B4 | runtime manifest `ai.contextWindow` | **PASS** present with the same shape; absent before activation |
| C1 | `contextLength 16384` → resolver | **PASS** `settings.json` @ ordinal 300 wins; `auto_detected` 32768 visible as a losing candidate; the row honestly reports `source:"runtime"` (server still at 32768) with `conflicts:[{16384, settings.json}]` |
| C2 | re-activate under the override | **PASS** `{rung:16384, reason:"override"}`, log `n_ctx_seq 16384`, KV **272.00 MiB** (exactly half of 544 — 17.0 KiB/token confirmed again), `llmContextTokens 16384` |
| C3 | `contextLength 0` → auto | **PASS** the settings.json candidate disappears; resolver returns `auto_detected`/150/32768; re-activation gives `{rung:32768, reason:"top-rung"}`, log `n_ctx_seq 32768` |
| C4 | `rememberAutoDetected` live | **PASS** after 5+ settings PUTs (each one a `ConfigStoreRebuilder.rebuild`), the ordinal-150 contribution is still present with its value. Without the fix in this PR it would have been dropped on the first PUT |
| C5 | `JUSTSEARCH_CONTEXT_SIZE` env arm | **NOT RUN** — needs a restart the orchestrator owns (as briefed) |
| D | forced step-down on the auto path | **PARTIAL — see below** |
| E | adopted external server | **PASS** adopted a standalone at `-c 8192` on port 8082: `usingExternalLlamaServer true`, `verified true`, `contextTokens 8192`, **`contextTooSmall false`** (the review-item-3 fix; pre-fix this compared 8192 against the derived 32768 and would have been `true`). **`contextWindow: null`** — the app does not claim a window it did not choose |
| 11 | schema-1 upgrade path | **PASS** — see below |
| F12 | q8_0 vs f16 tok/s | **PASS — q8_0 is free.** See the table below |
| F13 | 845 RAG arms | **NOT RUN** — out of the 90-minute box (needs a corpus ingest plus an enrichment wait) |

### D — the step-down, and what could not be forced

**VRAM pressure could not force a step-down on this machine, and that is itself a measurement.**
The rungs are only ~272 MiB apart in VRAM (17.0 KiB/token times 16384), while observed free VRAM
fluctuated by ~280 MiB between a reading taken seconds before activation and the value the launch
itself recorded — the encoders release and re-acquire. Four sized hogs (partial-offload
llama-server instances at `-ngl` 26 / 13 / 16 / 18, leaving 4067 / 5867 / 5511 / 5684 MiB free)
either starved the whole ladder or still let 32768 load. The band is narrower than the noise.

**The step-down code path was verified instead by the override arm, which is deterministic.**
Setting `contextLength = 1000000` and activating produced, in order:

```
Context window: rung=1000000 reason=override slots=2 kv=q8_0 ladder=[1000000] freeVramBytes=9987952640 (-fit off)
llama_context: n_ctx_seq     = 1000192
llama_kv_cache:      CUDA0 KV buffer size = 16604.75 MiB
CUDA error: out of memory
```

```
ERROR io.justsearch.app.inference.LlamaServerOps —
llama-server did not start at the explicitly configured context size 1000000
(justsearch.context.size). This is an operator override, so it is NOT reduced to a smaller window:
set it lower, or set it to 0 to let the window be derived from the backend.
```

```
WARN RuntimeReconciler: transition failed; will retry with backoff
io.justsearch.app.api.ModeTransitionException: [PROCESS_EXITED] llama-server process exited before
becoming healthy (exit code -1073740791).
```

This proves the load-bearing links: the failure arrives as **`PROCESS_EXITED`** (the gate
`relaunchAtLowerContextRung` reads — the wrong-gate check, confirmed on the real path rather than
by reading code); `relaunchAtLowerContextRung` **is invoked** and takes its override branch; an
override is **not** silently reduced; and the new ERROR message fires with the remedy. `rung` and
`reason` stayed `1000000` / `override` — no `stepped-from:` was written.

The auto-path log line confirms the ladder on every launch:
`ladder=[32768, 16384, 8192, 4096] ... (-fit off)`.

**Still unexercised live:** the successful rung-walk itself (`withFlagValue` rewriting `-c`, then a
lower rung loading). It is covered by unit tests, A.2b proves the abort that triggers it, and the
gate plus guard are now live-verified; only the relaunch line has no live witness. Forcing it needs
a card where the rung gap exceeds VRAM noise, or a test seam — not this machine.

### Item 11 — schema-1 upgrade path

The data dir had no `settings.json` at first (recorded in part 1); once the store saved, the live
file was `schemaVersion: 2, contextLength: 0`. A synthetic legacy file was then written
(`{"schemaVersion":1, "settings":{... "contextLength":4096 ...}}`) and read back through
`GET /api/settings/v2`, which calls `settingsStore.load()` on every request:

- `llm.contextWindow` came back **0** — migrated.
- **No `.corrupt-` sibling** was created: an older schema is a migration, not a quarantine.
- The log carried the migration line verbatim:
  `ui-settings schema 1 -> 2: contextLength 4096 (the pre-883 shipped default) migrated to 0 = auto;
  the context window is now derived at activation.`
- The next settings save rewrote the file at `schemaVersion 2, contextLength 0`.

### F12 — q8_0 versus f16, same rung, same prompt

Standalone, `-c 32768 -ngl 99 -np 2 -kvu -fa on -fit off`, 3 x 200 generated tokens,
`cache_prompt:false`:

| KV type | KV buffer | tok/s (3 runs) | median |
|---|---|---|---|
| `q8_0` | **544.00 MiB** | 69.71 / 69.66 / 69.33 | **69.66** |
| `f16` | 1024.00 MiB | 68.97 / 69.61 / 69.54 | **69.54** |

**q8_0 costs nothing — it is 0.2% FASTER than f16, inside run-to-run noise, while halving the KV
cache.** Design decision 2's revisit trigger ("if q8_0 exceeds 10% on the dev GPU, make f16 the
default at 16k and below") **does not fire**. q8_0 stays the default at every rung. Register Q-002
is answered for the tok/s half.

### Two reporting defects this window found

Both are in the read-out layer; every launch decision above was correct.

1. **`EffectiveConfigController.keyContextSize` reads the wrong "runtime" value.** It uses
   `runtimeInfo.contextSize()`, which is `InferenceConfig.contextSize()` — the value the inference
   layer was CONFIGURED with — not the observed `/props` window. Observed at C1: with the server
   running at 32768 and the setting changed to 16384, the row reported `runtime: 32768`; after
   re-activation at 16384 it still reported `runtime: 32768`, because `InferenceConfig` is rebuilt
   on its own schedule. The honest source is `OnlineAiService.llmContextTokens()`
   (`manager.lastKnownContextTokens()`), the `/props` readback, which was correct (16384)
   throughout. This matters most in exactly the case the row exists for: after a step-down it would
   report the PLANNED rung as the runtime value.
2. **`contextWindow` survives engine shutdown.** With the engine stopped
   (`mode=indexing, available=false`), `/api/inference/status.contextWindow` still returned the
   last launch's `{rung:32768, reason:"top-rung"}`. It is cleared at the start of the next
   `startLlamaServer` but not on stop, so it reads as current state for a server that is gone —
   the "intent presented as an outcome" shape register D-009 warns about.

## Live verification part 3 — fix re-verification + F13 (2026-09-02, stack 91fde467 from e302a96d)

API `http://127.0.0.1:59410`, same dataDir, standard profile.

### Fix 1 — the context row reads the OBSERVED window

Activated with `contextLength = 16384` (an override), then `GET /api/debug/effective-config`:

```json
{"key": "justsearch.context.size", "value": 16384, "source": "settings.json",
 "details": {"sysprop": "justsearch.context.size", "envVar": "JUSTSEARCH_CONTEXT_SIZE",
             "baseline": 16384, "sourceOrdinal": 300,
             "sourceDetail": "justsearch.context.size", "runtime": 16384}}
```

**PASS.** `runtime: 16384` is the `/props` readback. The corroborating call on the same engine makes
the fix unambiguous, because the OLD defect's wrong answer is still visible beside it:

```
llmContextTokens (/props) = 16384      <- what the row now reports as `runtime`
configuredContextTokens   = 32768      <- InferenceConfig, what the row USED to report
contextWindow             = {"rung": 16384, "reason": "override", "slots": 2, "kvType": "q8_0"}
```

Pre-fix this row said `runtime: 32768` while the server served 16384. Setting `contextLength` back
to 0 and re-activating returned it to `auto_detected` / ordinal 150 / 32768 with
`contextWindow.reason = "top-rung"`.

### Fix 2 — the launched-window record does not outlive its server

With the engine stopped (`mode=indexing`, `available=false`):

- `GET /api/inference/status` → `contextWindow: null` — **ABSENT**
- `GET /api/runtime/manifest` → `ai` keys are exactly
  `['pendingReason', 'phase', 'readyAt', 'required', 'serverBuildActual', 'thinkingSupport']` —
  **no `contextWindow` key at all** (omitted by `NON_NULL`, not emitted as null)

**PASS on both surfaces.** Before any launch at all, the field was likewise absent — so the record
appears exactly while a server this process launched is running, and at no other time.

### F13 — the 845 RAG arms

Corpus: `F:/justsearch-public/docs/explanation` added as a watched root via
`POST /api/indexing/roots {"path":"F:/justsearch-public/docs/explanation"}` (29 markdown files).
Enrichment reached `indexedDocuments 35`, `indexState IDLE`, `pendingJobs 0`,
`chunkDocCount 352` with `chunkEmbeddingCompletedCount 352` (100% chunk-vector coverage) before the
arms ran. Engine at the derived top rung: `{rung: 32768, reason: "top-rung", slots: 2, kv q8_0}`.

Both arms `POST /api/chat/ask` (SSE), same question, `topK: 5`:

> "What is the JustSearch hybrid inference architecture and how does the GPU mutual exclusion
> between the Main and Worker processes work?"

| Arm | Request body | HTTP | `context_truncated` | `chunks_used` / `chunks_found` | prompt + completion | window | error |
|---|---|---|---|---|---|---|---|
| Quick | `{"question":"...","topK":5,"maxTokens":512}` | 200 | **false** | **5** / 62 | 2597 + 512 = **3109** | 32768 | none |
| Standard | `{"question":"...","topK":5,"maxTokens":1024}` | 200 | **false** | **5** / 62 | 2597 + 1024 = **3621** | 32768 | none |

Both arms: `retrieval_mode CHUNK_HYBRID`, `retrieval_mode_reason HYBRID_AVAILABLE`,
`chunks_considered 15`. The standard arm streamed 510 `chunk` events.

**All three assertions hold in both arms:**

- `context_truncated == false` — the 845 trimmer never fired.
- `chunks_used == min(chunks_found, topK)` — 5 used of 62 found at `topK 5`, i.e. every chunk the
  budget asked for was delivered. (`chunks_found` counts the retrieval candidate pool, not a
  delivery shortfall.)
- prompt + completion is far inside the window: 3109 and 3621 against 32768, ~10% utilization.

For contrast, 845's original failure was "asked for **5990** input tokens out of a 4096-token
window" (845:326). The same class of ask now costs 2597 prompt tokens against a window 8x larger.
Note this is a *headroom* result, not a proof that the budget fractions are right — the constants
that would fill a larger window are PR 2's `ContextBudget` work (decision 3), which is not in this
PR. What it does show is that the derived window removes the pressure that made 845's trimmer fire.

Watched root removed afterwards (`DELETE /api/indexing/roots` → `{"status":"ok","deletedJobs":30}`;
`GET /api/indexing/roots` → `{"roots":[]}`).

### C5 — the env-var arm remains NOT RUN (an honest gap, not a pass)

`JUSTSEARCH_CONTEXT_SIZE` contributes at ordinal 400 (`env_var`). It is exercised at the unit level
by `ResolvedConfigBuilderTest` (the ordinal-chain and clamping cases put values at
`ORDINAL_ENV_VAR` explicitly and assert the resolution), and the effective-config row lists
`env_var` among its candidates live — but with no value, because nothing set it.

Running it live needs the variable present in the backend's process environment at start, which the
orchestrator's dev-runner does not expose as a knob; changing it means an orchestrator-owned
restart. It is therefore recorded as a **gap**, not as a pass: the env path's live behaviour on this
build is inferred from the ordinal chain being verified at 150 / 300 / 500 (the last via the
`-D`-sourced `jvm_arg` candidate), not observed at 400.

### Live acceptance status after part 3

Everything in the original acceptance list is now measured except C5 above, and the one step-down
detail recorded in part 2: the successful rung-walk (a lower rung actually loading after a failed
higher one) still has no live witness, because on this card the inter-rung VRAM gap (272 MiB) is
smaller than observed free-VRAM noise (~280 MiB). Its trigger (`PROCESS_EXITED` on an unfittable
`-c`), its guard, and its override branch are all live-verified; the relaunch line is covered by
unit tests only.

---

## §B — Pre-implementation pass (chunk 2, PR 2: decision 3 `ContextBudget`; base `5547f564` = PR 1)

Every `path:line` in the item-9 constants table, re-read on THIS base (PR 1 moved several).
Corrections are marked **[moved]**; facts the item-9 table did not state are in §B.b2.

### B.a2 — The item-9 constant sites, verified

| Item-9 row | On this base | Verdict |
|---|---|---|
| `HIERARCHICAL_THRESHOLD_TOKENS` 5000 at `:59`, branch `:133` | `HierarchicalShapeRunner.java:59` `private static final int HIERARCHICAL_THRESHOLD_TOKENS = 5000;`, branch at `:133` `if (totalTokens < HIERARCHICAL_THRESHOLD_TOKENS)` | confirmed |
| `SECTION_TARGET/SECTION_MAX/SYNTHESIS_MAX` 1800/512/1024 at `:63,67,68` | same lines; `SECTION_TARGET_TOKENS` used at `:140`, `SECTION_MAX_TOKENS` at `:169`, `SYNTHESIS_MAX_TOKENS` at `:245` | confirmed |
| RAG default shape `5 x 2 x 500` | `RAGContext.java:81` `DEFAULT_TOP_K = 5`; `ResolvedConfigBuilder.java:1643` `resolveInt("justsearch.rag.top_k", 5)` and `:1645` `rag.max_chunks_per_article` **[moved]** (fold said 1592-1594); `ChunkSplitter.java:92` `DEFAULT_CHUNK_TOKENS = 500` | confirmed |
| 845 trimmer + budget | `RAGContext.inputBudgetTokens :244`, `contextWindowTokens :254`, `completionReserveTokens :274`, cut at `:412-414`, `cutContext :514`, `SectionCut :482` **[moved]** (fold said 406-421,511-531) | confirmed |
| `DEFAULT_CONTEXT_WINDOW_TOKENS = 4096` | `RAGContext.java:151` **[moved]** (fold said `:148`; PR 1 rewrote its javadoc) | confirmed, and kept |
| `TokenEstimation` reserves 256/256/512/256 | `TokenEstimation.java:16-21` (`FIRST_PORTION 2000`, `LAST_PORTION 800`, `OVERHEAD 256`, `SAFETY 256`, `MIN_CONTEXT 512`, `MIN_BUDGET 256`); `computeSafeInputBudgetTokens :115-124` **[moved]** (fold said 114-123) | confirmed |
| `ExternalContextInjector.MAX_CONTEXT_TOKENS = 1000` | `:30`, javadoc claim "~25% of a conservative 8K context window" at `:22`, applied at `:71` | confirmed |
| `ReadDocumentTool.DEFAULT_PAGE_CHARS = 3000` | `:40` **[moved]** (fold said `:24,39`); `READ_PAGE_CHARS` at `:68-73`, a `static final` composed at class-init from `ToolResultCarrier.layerTwoCapChars()` | confirmed |
| `ServerPropsOps.SUMMARY_CONTROLLER_MAX_CONTEXT_TOKENS = 3000` | `:27` **[moved]** (fold said `:26`); sole reader `warnIfSummaryBudgetExceedsActual :331-340`, called from `:210` | confirmed - and see B.b2 (4) |
| `AgentLlmCaller.DEFAULT_MAX_TOKENS`, `AgentContextCompressor.MAX_TOOL_RESULT_CHARS` | `AgentLlmCaller.java:48-49` `Math.max(256, resolveInt(rc -> rc.agent().maxCompletionTokens(), 1024))`, used at `:317`; `AgentContextCompressor.java:74-75` `Math.max(100, resolveInt(rc -> rc.agent().maxToolResultChars(), 4000))`, used at `:95-99` | confirmed, both class-init `static final` |
| `AgentBudgetPolicy` "12.5x @ n_ctx 4096" | `:28` inside the bound derivation, restated at `:46` | confirmed |
| `ConversationEngine.parseMaxTokens` + reserve publication | `parseMaxTokens :1142-1149` **[moved]**; reserve published at `:447-455` | confirmed, unchanged by this PR |
| `RetrieveContextParams.maxContextTokens` on the wire | `RetrieveContextParams.java:21` component; `RAGContext.tryOpenRetrieval :724-727` sends `max(1, inputBudgetTokens(ctx))`; `tryRetrieveContext :698-699` sends `0` (scoped path, char-budget behaviour) | confirmed - **no proto change needed**, the field already exists and already carries the budget |

### B.b2 - Facts the contract did not state that change the design

1. **`modules/app-agent` has no `modules:core` edge.** Its deps are `app-agent-api`, `app-api`,
   `configuration`, `telemetry` (`modules/app-agent/build.gradle.kts:8-12`); `app-api` pulls
   `app-agent-api` + `configuration` + `api-contract-projection-java`, and `configuration` pulls only
   `core-contracts`. So `TokenEstimation` (`modules/core`) is NOT reachable from `app-agent` today.
   `modules/core` is a leaf (no project deps), so the edge cannot cycle. Adding
   `api(project(":modules:core"))` to `app-agent` is this PR's one new module edge; the canonical
   `docs/reference/architecture/module-deps.md` is regenerated with it.
2. **The tools cannot see the session.** `SearchTool`/`ReadDocumentTool` are `OperationHandler`s
   dispatched by id (`execute(String argumentsJson)`); nothing hands them an `AgentSession`. So a
   per-call budget reaches them as a `Supplier<ContextBudget>` injected at construction, in
   `AgentToolFactory.assemble` (`modules/app-services/.../bootstrap/phases/AgentToolFactory.java:97-107`),
   which already holds the `OnlineAiService`. This is the only seam that does not require changing
   the `OperationHandler` contract.
3. **`READ_PAGE_CHARS` must stay under the Layer-2 cap, and the decision-3 fractions invert that.**
   Decision 3 sizes the read page at `inputBudget/2` (cap 4k tokens) and the tool-result cap at
   `inputBudget/4` (cap 2k tokens) - the page is twice the cap that clips it, so a full page would
   arrive Layer-2-truncated, which is the exact failure `READ_PAGE_CHARS` exists to prevent
   (868 A.5). The existing `min(pageSize, layerTwoCap - PAGE_HEADROOM_CHARS)` shape is therefore
   KEPT, with the budget's page figure replacing the `3000` literal inside it. Both operands now
   scale with the window, so the page grows with the window and never exceeds the cut.
4. **`ServerPropsOps.SUMMARY_CONTROLLER_MAX_CONTEXT_TOKENS` names a class that does not exist.**
   `grep -rn "SummaryController" modules --include=*.java` -> the only hits are this constant's own
   name and its WARN string. The warning ("SummaryController MAX_CONTEXT_TOKENS may be too large for
   server context") is residue of a deleted controller, and with decision 3 the situation it warns
   about is unrepresentable: every consumer's budget is derived FROM the observed window, so no
   consumer constant can exceed it. Constant + `warnIfSummaryBudgetExceedsActual` + its call site are
   DELETED (`retire-with-a-sweep`), not re-derived. This is the PR's only `app-inference` change.
5. **`rag.max_chunks_per_article` is a worker-side per-parent diversity cap**, applied in
   `RagContextOps.java:632,643,672,1767-1781`; it never crosses to the Head and is not a bound on how
   many passages the Head asks for. The Head-side upper bound is `justsearch.rag.top_k` alone. Stated
   because the contract names both.
6. **An explicit body `topK` must keep winning.** `RAGContext.extractTopK :821-828` documents
   body -> configured -> `DEFAULT_TOP_K`, and its javadoc (`:171-178`) states that config must not
   override a caller that asked for a value. The budget-derived shape therefore replaces the
   DEFAULT (the configured/`DEFAULT_TOP_K` arm) only. Consequence for the live item: the 845 arms
   send `topK: 5` explicitly, so they are unaffected - see C.6b.
7. **The completion reserve must not be clamped where the caller already fixed it.** `RAGContext`
   budgets against the reserve `ConversationEngine` will actually send (`ATTR_COMPLETION_RESERVE_TOKENS`,
   `ConversationEngine.java:447-455`). Clamping that number inside `ContextBudget` would promise input
   room the real completion can still eat. So `ContextBudget` has TWO factories: one that takes the
   caller's fixed reserve verbatim, one (`withDerivedReserve`) for callers that let the budget CHOOSE
   the reserve - today only the agent loop, whose cap is a config knob, not a request field.
8. **`justsearch.agent.max_tool_result_chars` / `max_completion_tokens` defaults (4000 / 1024) block
   the derivation.** `ResolvedConfigBuilder.java:1384-1385`. If they stay positive defaults they
   always win the `min(...)` against a 32k-derived value and the window scaling is invisible. Both
   defaults become `0 = derive from the window`, with a positive value an explicit operator ceiling
   honoured verbatim - the same "0 means auto, an override is honoured or fails loud" shape PR 1 gave
   `contextLength` (B.c). `docs/reference/configuration/environment-variables.md:91` currently
   documents the tool-result default as `900`, which was already wrong on `main`; corrected here.
9. **`ExternalContextInjector` is a stateless `INSTANCE` singleton** (`:28`), wired at
   `modules/ui/.../ConversationApiAssembly.java:238` where `onlineAiSupplier` (`:131-136`) is already
   in scope - the same supplier `RAGContext` gets at `:234`. It becomes a constructed injector.
   `SelectionContextInjector` (`:239-243` wiring) takes the same supplier.

### B.c2 - Decisions taken inside this chunk

- **`ContextBudget` lives in `modules/core` (`io.justsearch.core.util`), next to `TokenEstimation`,
  whose `computeSafeInputBudgetTokens` is the derivation it wraps.** It is built from PLAIN INTS
  (`Integer observedWindow, Integer configuredWindow, int reserve`) so `core` stays a leaf and every
  caller - Head conversation SPI, agent loop, agent tools - resolves the same precedence in one
  place without `core` learning about `OnlineAiService`. The alternative (put it in `app-api`) would
  have forced either a `core` edge on `app-api` anyway or a second copy of the budget formula.
- **`RAGContext` delegates, it does not fork.** `contextWindowTokens()` / `inputBudgetTokens()` become
  `RAGContext.budgetFor(...)`, a pair of public statics that read the live `OnlineAiService` and the
  turn's reserve attribute and hand both to `ContextBudget`. `RAGContext` already owned both the
  window walk and `ATTR_COMPLETION_RESERVE_TOKENS`, so this is generalizing in place; the injectors
  in the same package call the same static rather than re-reading the window.
- **`SECTION_MAX_TOKENS` and `SYNTHESIS_MAX_TOKENS` are per-call OUTPUT limits, not window
  fractions**, and stay constants. They are the `max_tokens` handed to `streamChat`
  (`HierarchicalShapeRunner:169,245`) - a completion reservation, the same category as
  `ConversationEngine.DEFAULT_MAX_TOKENS`. What changes is that `SYNTHESIS_MAX_TOKENS` is now the
  reserve the runner's `ContextBudget` is BUILT from, so the threshold it derives accounts for the
  room the answer will take. `SECTION_TARGET_TOKENS` IS window-derived and becomes `sectionTarget()`.
- **The agent's completion reserve is `min(configured cap, window/4)`.** A reserve is not linear in
  the window (an answer does not get longer because the window did), but at a small window a flat
  1024 crowds out the input. `window/4` changes nothing at 4096 and above (min picks the 1024 cap)
  and shrinks the reserve below it, which is where the starvation is real. It also keeps
  `AgentBudgetPolicy`'s structural bound `spend <= maxIterations * (n_ctx + maxTokens)` valid, since
  `maxTokens` can now only go DOWN.
- **Tokens->chars conversion is the documented inverse of `TokenEstimation`'s default heuristic**
  (`charEstimate = ceil(len / 4)`), added as `TokenEstimation.charsForTokens`. It is an estimate, and
  is only used where the consumer's own budget is a CHAR budget (`READ_PAGE_CHARS`, the Layer-2 cut,
  the selection injector). Dense/CJK text estimates higher per char, so a char budget converted this
  way can be re-estimated above its token figure - which is why the read page keeps its second,
  char-vs-char bound against the Layer-2 cap (B.b2 (3)) instead of trusting the conversion alone.
- **The selection injector's caps are the full input budget, not a fraction.** A user's selection is
  the turn's PRIMARY material (the same role the RAG context plays), so it is budgeted at
  `inputBudget` converted to chars, and the result-set arm splits that budget across the docs it
  takes (`inputBudget / MAX_RESULT_SET_DOCS`). No new fraction is invented; the existing
  `MAX_RESULT_SET_DOCS` stays a doc COUNT, which is not a window quantity.
- **`execution-surface` is not implicated.** The register gates new production files that reference
  `SearchTrace` / `ContextCitation` / `EvidenceSpan` (`governance/execution-surfaces.v1.json`
  `scan.javaImportPatterns`). `ContextBudget` is a request-scoped budget record that references none
  of them, and this PR adds no new file that does. Gate re-run under C.

### B.d2 - The fraction/cap table as it will be implemented

| Accessor | Derivation | Cap reason (stated in javadoc at the site) |
|---|---|---|
| `inputBudget()` | `computeSafeInputBudgetTokens(window, reserve)` | unchanged (845) |
| `hierarchicalThreshold()` | `inputBudget` | no cap - it IS the budget |
| `sectionTarget()` | `min(inputBudget / 2, 4096)` | map-step latency: a section is one blocking LLM call |
| `externalContextCap()` | `min(inputBudget / 4, 2048)` | prior turns are low value per token next to this turn's material |
| `readDocumentPageTokens()` | `min(inputBudget / 2, 4096)` | agent-context hygiene: a 12k page at 32k defeats the compressor |
| `toolResultCap()` | `min(inputBudget / 4, 2048)` | one tool result must not own the prompt |
| agent completion reserve | `min(configured cap, window / 4)` | see B.c2 |

## §C — Post-implementation critical analysis (chunk 2, PR 2; 2026-09-02)

### C.1b — Wrong-gate checks (each grepped at the set-site, not inferred)

The acceptance greps, run as commands on the finished branch:

| Command | Result |
|---|---|
| `grep -rn "5000\|HIERARCHICAL_THRESHOLD_TOKENS" modules/app-services/.../HierarchicalShapeRunner.java` | one hit, in the WHY comment that names the retired literal (`:137`). The constant and its branch are gone. |
| `grep -rn "MAX_CONTEXT_TOKENS = 1000\|DEFAULT_PAGE_CHARS = 3000\|SUMMARY_CONTROLLER_MAX_CONTEXT_TOKENS = 3000\|MAX_TOOL_RESULT_CHARS = 4000" modules` | **no output** |
| `grep -rn "SECTION_TARGET_TOKENS\|1800" .../HierarchicalShapeRunner.java` | **no output** |
| `grep -rn "layerTwoCapChars()" modules --include=*.java` | no CALL sites; the two hits are prose naming the method, both updated to `layerTwoCapChars(budget)` |
| `grep -rn "MAX_TOOL_RESULT_CHARS\|READ_PAGE_CHARS\|DEFAULT_MAX_TOKENS\|DEFAULT_PAGE_CHARS\|SUMMARY_CONTROLLER" modules --include=*.java` | after the sweep, the only surviving hits are `ConversationEngine.DEFAULT_MAX_TOKENS` (a DIFFERENT, live symbol — the chat engine's default `max_tokens`, untouched by this PR, and its `ResolvedConfigBuilder.ENGINE_DEFAULT_MAX_TOKENS` mirror) and the `EnvRegistry` key spellings. Six stale prose references to the deleted symbols were swept in the same PR rather than left as false authority. |

Claims the change depends on, checked at the set-site:

| Claim | How it was checked | Result |
|---|---|---|
| The tools actually RECEIVE a live budget in production, not the null-fallback | `AgentToolFactory.assemble` builds `() -> AgentContextBudgets.forCall(onlineAiService)` once and passes it to BOTH `SearchTool` and `ReadDocumentTool`; `AgentToolFactory.build` and `AgentToolHandlers.registerLateBound` both route through `assemble` (tempdoc 832's single construction authority), so the eager and late-bound paths cannot diverge. | confirmed |
| The compressor's cap is not frozen | `AgentLoopService` passes a SUPPLIER, not a value (`AgentLoopService.java:336-338`); `AgentContextCompressorTest`-equivalent coverage is `AgentContextBudgetsTest.toolResultCapTracksTheWindowWithinOneJvm`, which flips the window inside one JVM and requires the number to move. A value-typed wiring would have compiled and passed every other test. | confirmed |
| The read page cannot be Layer-2 clipped at any rung | `AgentContextBudgetsTest.readPageGrowsWithTheWindowAndStaysUnderTheCut` asserts `page + PAGE_HEADROOM_CHARS <= layerTwoCap` at 2048/4096/8192/16384/32768. It binds with EQUALITY at every rung, which is the sign the second bound (not the page fraction) is what governs — exactly as §B.b2 (3) predicted. | confirmed |
| A history drop is reported on the path that actually drops | the INFO log is inside `ExternalContextInjector.inject`, after the keep-loop, gated on `kept.size() < parsed.size()` — the same list the loop built. `noDropNoLog` asserts the quiet path stays quiet, so the assertion is not satisfied by an unconditional log. | confirmed |
| The `execution-surface` register is not implicated | `node scripts/governance/run.mjs --gate execution-surface --mode gate` -> `1 gate evaluated, 0 fail, 0 findings`. No new file references `SearchTrace` / `ContextCitation` / `EvidenceSpan`. | pass |
| No contract changed | `RetrieveContextParams.maxContextTokens` already existed and already carried the budget; `contracts/**` is untouched, so `--gate wire` has no subject. `UnifiedChatView.ts` / `CoreConversationShapeCatalog.java` are untouched, so `check-intent-tier-coverage` has no subject. | confirmed |

### C.2b — Test precision: does each test pass for the RIGHT reason?

The three most important, and how each FAILS on the old code:

1. **`ContextBudgetConsumerTest.smallWindowForcesHierarchical`** — a 4999-token document at a
   4096-token window. On the old code `4999 < HIERARCHICAL_THRESHOLD_TOKENS (5000)` is true, so the
   runner emits `progress phase:"standard"` and single-passes 4999 tokens into a window whose honest
   input budget is 2304. The test asserts `"standard"` is NOT among the emitted phases, so the old
   code fails it on the first assertion. Its companion (`largeWindowRaisesTheThreshold`, 6000 tokens
   at 32768) fails the old code from the other side — the old literal split a document that fits
   whole. Straddling the retired constant in BOTH directions is what makes the pair discriminate a
   derived threshold from any other constant.
2. **`AgentContextBudgetsTest.toolResultCapTracksTheWindowWithinOneJvm`** — flips the window from
   4096 to 32768 inside one JVM and requires the cap to move (2304 -> 8192 chars). On the old code
   `MAX_TOOL_RESULT_CHARS` is a `static final` initialized once from config; no window value is an
   input to it at all, so the two reads are identical by construction and the `assertTrue(large >
   small)` fails. This is the shape the acceptance asked for precisely because a
   single-value assertion cannot tell "resolved correctly" from "frozen at whatever the first caller
   saw".
3. **`ContextBudgetConsumerTest.historyDropIsLogged`** — asserts an INFO record naming the dropped
   count, the before/after token totals and the cap. On the old code the keep-loop `break`s with no
   log statement anywhere in the class (the class had no `Logger` field), so the appender list is
   empty and the `orElseThrow` fires. The assertion also pins `cap 576`, i.e. the DERIVED cap, so a
   version that logged but kept the flat 1000 would fail too.

Also checked for the "passes for a wrong reason" shape:

- `ContextBudgetTest.windowPrecedence` asserts `assertNotEquals(8192, ...)` explicitly, because the
  845 defect was a hardcoded 8192 and a test that only asserted 4096 would pass on a fallback that
  happened to be right for a different reason.
- `sectionTargetScalesWithTheWindow` counts sections from the runner's OWN `sections` progress event
  (`totalStages`), not from a count of stubbed LLM calls. The first draft counted calls and read 0
  at both windows, because `blockingStreamChat` uses a 7-argument `streamChat` overload the stub did
  not implement — a green-for-the-wrong-reason that the explicit `throw new AssertionError("no
  sections event")` in the helper now makes impossible.
- `defaultTopKIsDerivedFromTheBudget` asserts BOTH ends (5 at 32768, 1 at 2048). Asserting only the
  narrow end would also pass on a version that always returned `min(top_k, 1)`.

### C.3b — Defects this pass found, and fixed in the same PR

1. **The history keep-loop was rewritten from `break` to `continue` in the first draft**, which would
   have let an older, SMALLER turn ride along after a larger one was skipped — a history with a hole
   in it, which reads as a different conversation than the one the user had. Reverted to `break`
   with the reason stated at the site, and the drop counted separately.
2. **The decision-3 fractions invert the read-page invariant.** The page fraction
   (`inputBudget/2`) is twice the tool-result fraction (`inputBudget/4`), so a full page would be
   Layer-2 clipped at every rung — the exact failure `READ_PAGE_CHARS` was introduced to prevent
   (868 §A.5). Caught in the §B pre-implementation pass, not by a test; the existing
   `min(page, layerTwoCap - headroom)` shape is kept and both operands now scale.
3. **Four pre-existing `RAGContextTest` top-K tests went red** on the derived default (they asserted
   the configured 17 / 5 reached retrieval at a 4096-token window, where the budget affords 4). The
   tests are RIGHT about their intent — config precedence, not budget arithmetic — so each was given
   a window wide enough to afford its number, with a comment saying why, and the budget bound itself
   is covered by the new `defaultTopKIsDerivedFromTheBudget`. No assertion was weakened.
4. **`ServerPropsOps.SUMMARY_CONTROLLER_MAX_CONTEXT_TOKENS` names a class that does not exist**
   (§B.b2 (4)). Deleted with its warning and its call site rather than re-derived.

### C.4b — Deviations from the contract, stated rather than hidden

1. **`SECTION_MAX_TOKENS` / `SYNTHESIS_MAX_TOKENS` are NOT window-derived.** The contract asked to
   add them "if they are window-derived, state if they are per-call output limits instead". They are
   per-call OUTPUT limits — the `max_tokens` handed to `streamChat` — so they stay constants, and
   `SYNTHESIS_MAX_TOKENS` is now the reserve the runner's budget is built FROM. Stated in §B.c2.
2. **The agent's completion cap is not a pure window fraction.** `min(configured cap, window/4)`,
   which changes nothing at 4096 and above. Raising a completion cap with the window would change
   agent run economics and invalidate `AgentBudgetPolicy`'s structural spend bound; that is a spend
   decision, not a budget-plumbing one. The acceptance's "the two former class-init constants change
   when the window changes at runtime" is still satisfied and tested — the reserve moves at 2048.
3. **`rag.max_chunks_per_article` is not used as a Head-side bound** — it is a worker-side per-parent
   diversity cap and never crosses to the Head (§B.b2 (5)). The Head-side bound is `rag.top_k`.
4. **Both agent config defaults changed from positive to `0 = derive`** (§B.b2 (8)). An operator who
   had set either key explicitly is unaffected; an operator relying on the shipped 4000/1024 now gets
   a window-derived number instead, which is the point. `environment-variables.md` says so.
5. **`DocAccess` / `BatchDocAccess` keep their own 200,000-char soft caps.** They are not in the
   item-9 table and they mirror the Worker's gRPC transport cap
   (`GrpcSearchService.MAX_CONTENT_CHARS = 200_000`), which is a different quantity from a prompt
   budget. Routed here rather than swept: `DocAccess.java:50-51,98-99` and
   `BatchDocAccess.java:48-49,100-101` inject document text into a prompt with no window-derived
   bound, so the same class of over-commit is still reachable through the doc-access injectors. It
   belongs to whoever takes the remaining item-9 residue.
6. **One new module edge**: `modules/app-agent` -> `modules/core` (`api`), regenerated into
   `docs/reference/architecture/module-deps.md`. `core` is a leaf, so no cycle is possible.

### C.5b — Live-window items for the orchestrator (nothing below was measured in PR 2)

1. **Re-run the 845 RAG arms at 32768 with the new shape.** Expected, and stated in advance so the
   result can falsify it: **the arms are UNCHANGED at `chunks_used 5 / chunks_found 62`**, because
   both arms send `topK: 5` explicitly (883 F13's request bodies) and an explicit `topK` still wins
   verbatim (§B.b2 (6)). The derived default would ask for `min(28108/500, 5) = 5` anyway at this
   window, so the shape is the same number by two routes. `context_truncated` must still be `false`
   and prompt + completion must still sit far inside 32768. **The arm that would actually show the
   change is a request with NO `topK`**: run one, and record what the wire `topK` was.
2. **A small-window arm.** The derived shape only bites below ~4096: at the CPU rung (8192, reserve
   1024) the input budget is 5860 and `min(5860/500, 5) = 5`, i.e. still unchanged. To witness the
   derivation live, force a small window (`-Djustsearch.context.size=2048`) and assert the wire
   `topK` is 1 and `context_truncated` stays `false` — the case 845's trimmer used to absorb.
3. **The hierarchical runner at a live window.** Summarize a ~5000-token document with a 32768-token
   server and confirm it goes SINGLE-PASS (`progress phase:"standard"`), then at a forced 4096 window
   confirm it splits. Unit-covered; not yet witnessed against a real model.
4. **An agent run at 32768.** Confirm a `core_read_document` page is materially larger than 3000
   chars (expected 7592) and that no tool result carries the `[... truncated,` marker at a size the
   old 4000-char cap would have cut.
5. **The history drop, live.** A chat turn with a long `context` array should emit the new INFO line
   in the backend log with before/after token counts.

### C.6b — Still open in this lane after PR 2

Decision 5 (the `getenv` funnel + the yaml-reader gate), decision 4 slice 2 (the `server.exe` /
`exclude_patterns` / `gpu.layers` promotions), ADR-0047 "Context window as a derived resource"
(number still reserved, still unwritten), and the item-9 residue named in §C.4b (5) — the
`DocAccess` / `BatchDocAccess` 200,000-char prompt injections.
