---
status: CONTRACT — ready for takeover (not started)
created: 2026-09-01
updated: 2026-09-01
owner_session: unassigned (wave-1 orchestrator; branch after 882 merges)
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
