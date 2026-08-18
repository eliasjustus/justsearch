# 842 — Chat model profile: a compact dev default with the standard model opt-in

```
status: IMPLEMENTED 2026-08-18 on worktree-842-chat-model-profile — unit+gate
        green, live E2E verified (§8 implementation entry); pending merge.
        D1=4B / D2=auto-activate / D3=chat.profile applied as recommended;
        probes P1 (passed) and P2 (mechanism confirmed) recorded in §4.
        Rev 2 design below; rev 1 superseded in-place (§8).
created: 2026-08-18
updated: 2026-08-18
revision: 2 — rev 1 was written before the seam investigation; this revision
  conforms the design to the shipped 657 substrate (InstallIntent pattern,
  CapabilityTier), the real chat-model selection pipeline, and the dev-MCP /
  evidence / jseval surfaces as they exist on main. Naming changed
  ("tier" → "profile", §2.1), the honesty section became conform-to-805 rather
  than bespoke (§2.5), and three pre-existing defects are absorbed (§2.7).
related: 657 (install-intent axis + CapabilityTier substrate this conforms to;
         its open D7 "small dense rung" is the sibling shape on the retrieval
         side), 840 (component-level install — chat-compact is one more component
         with identity; its H2 chosen-vs-declared completeness must ignore
         dev-only packages), 805 (observed-vs-declared capability honesty — §2.5
         extends it to chat model identity and GPU placement), 804 (§B refuted
         packs-registry chat resolution; settings-persistence axis), 637
         (freshness in quick_health — realized model identity joins it), 606
         (one-authority verdict pattern)
external: F:\System\20-analysis\research-dev-model-stack-tiering.md — all probe
         measurements and model-landscape research (2026-08-18). Numbers quoted
         below are single-machine dev-loop probes recorded there; they are not
         product benchmarks and must not be cited in public-facing surfaces.
```

## 1. Problem and measured motivation

Owner proposal: dev stacks default to a smaller chat model; the standard
(user-facing) model becomes an explicit opt-in per agent/session.

Probe basis (2026-08-18, production llama-server binary and settings, idle GPU;
full tables external): the standard Qwen3.5-9B holds ~8.3 GB VRAM + ~5.5 GB RAM
resident and loads in ~7.5 s; on CPU it degrades to ~2 tok/s with pathologically
slow prompt ingestion, pinning the whole machine for minutes per interaction. A
compact same-family sibling (Qwen3.5-4B/2B class) measures in the ~2 GB VRAM /
~2 s load / >200 tok/s class. The suspected (unconfirmed) mechanism for the
lived "dev AI congests the machine" experience is llama-server's fit-to-free-VRAM
silently landing layers on CPU when the worker's ORT arenas have grown — see
probe P2 (§6). The compact default removes the contention *and* the latency in
one move.

Landscape (2026-08): the Qwen3.5 small series is natively multimodal at every
size; nothing newer ≤12 GB-class beats the shipped 9B, so the *user* model does
not change. Candidate compact model: **Qwen3.5-4B Q4_K_M + its own mmproj**
(unsloth GGUF ships the projector; bartowski's small repos do not). 2B is the
fallback if headroom outranks quality (owner decision D1).

Scope guard (unchanged from rev 1): chat LLM only. The embedder must stay
identical across profiles — vectors are index-persistent, and a different
embedder invalidates every dev index. The remaining ONNX suite is load-cheap and
stays as-is.

## 2. Design (rev 2 — conformed to existing seams)

### 2.1 The axis is a *chat model profile*, and "tier" is the wrong word

Investigation found "tier" already carries three meanings on main:
`CapabilityTier` {retrieval-core, retrieval-enrichment, llm, runtime} classifies
registry packages (657, shipped); jseval "tier-2" names an eval rung; the old
prose-rule register names enforcement tiers. A fourth meaning would be noise.

The concept this design adds is a **named, atomic chat-model bundle selection**:
`standard` (Qwen3.5-9B + mmproj, default) | `compact` (small sibling + its
mmproj). "Profile" is the in-repo word for exactly this shape:
`VlmExtractionProfile` is already an enum of atomic *(model, mmproj)* pairs
selected by one config key with warn-on-unknown fallback — the right
micro-pattern at the wrong altitude (package-private inside `InferenceConfig`,
invisible to health/manifest/evidence).

**Confidence-pass correction (2026-08-18): this is ONE axis, not two.** The
"chat model" and the "extraction VLM" are the same llama-server engine and the
same file — `InferenceConfig`'s default primary model *is* the
`VlmExtractionProfile`'s model; only the settings override obscures this. So
the design does not add a `ChatModelProfile` beside `VlmExtractionProfile`; it
**hoists and extends the existing profile axis** (a `compact` pair joins the
enum, the type moves to the shared model domain, the selection key gains the
altitude InstallIntent has). One engine, one pair, one profile.

The macro-pattern to conform to is **`InstallIntent`** (657): a top-level enum in
the configuration model domain, one `EnvRegistry` key
(`justsearch.chat.profile` / `JUSTSEARCH_CHAT_PROFILE`, default `standard`,
warn-fallback resolution), consumed by the runtime *and* republished on the
runtime manifest so the advertised value cannot drift from the effective one.
The three existing axes — install intent (product shape), hardware
variant/DownloadProfile (precision × EP), capability tier (package
classification) — are untouched; profile is a fourth, orthogonal axis.
`VariantSelector` explicitly short-circuits `LLAMA_SERVER`, confirming size
selection cannot ride the hardware-variant seam.

### 2.2 Registry: a `chat-compact` package, excluded from user install plans

The registry is the system's model-identity authority (sha256, size, license,
targetDir), and the model-freshness CI lint and NOTICES generation are projected
off it — a parallel "dev models manifest" would fork that authority. So the
compact model is a first-class package: id `chat-compact`, `tier: "llm"`, one
GGUF variant plus its own mmproj as a supporting file (mirroring `chat`).

New required substrate, kept minimal: an explicit package-level exclusion flag
("not part of any user install plan") honored by `InstallPlanner` — a one-rule
skip branch parallel to 657's intent-gated skip. Consequences named now, not
discovered later:

- `InstallCompleteness` / repair must ignore excluded packages — the same
  chosen-vs-declared reconciliation 840's H2 already requires; coordinate there.
- The model-freshness lint's "current model" projection must not start treating
  the compact model as a retired-or-current *user* model.
- The config-surface gate requires the new key to be live-consumed and
  baselined; the runtime-config ownership matrix gains a row.

Acquisition for dev is **not** routed through `AiInstallService` — 840 is
dismantling that class, and dev-side fetching needs none of its system-mutation
duties. Dev tooling (the doctor / prerequisites scripts already read the
registry) fetches the compact package's files sha-verified into the models dir.

### 2.3 Selection and the hot-swap seam that already exists

Runtime truth for the chat model is `UiSettings.llmModelPath` (ordinal 300) with
env/jvm-arg overrides above it; the registry is not consulted at llama-server
start. The working swap seam on main is: write the LLM settings, then
`POST /api/inference/reload` (or activation) — `applyRuntimeOverrides` restarts
the engine on the new file. Everything this design adds is *upstream* of that
seam: profile → resolve the package's *(model, mmproj)* pair (install
contract/registry-derived path, same shape as the existing
install-contract fallback used by runtime activation) → feed the pair through
the existing seam. The explicit `llm.model_path` override keeps winning over the
profile, exactly as it wins today.

**A defect this must fix, not inherit — now observed live, not theoretical:**
the existing resolution nulls the mmproj whenever the model-path override is
set without an explicit mmproj env (defensive against projector/model
mismatch), and since the settings override is set in every installed/dev data
dir, **the current dev stack's llama-server runs with no `--mmproj` at all**
(verified against the live process command line and its log, 2026-08-18;
mmproj presence is inconsistent across recovered session logs). Dev sessions
are silently text-only today. This design hoists the *(model, mmproj)* pair
into the shared model domain and carries it through resolution and reload, so
a profile switch swaps both files together — and vision comes back to dev.
See §5 for the orphan this creates.

**Precedence rule (closes the confidence-pass "landmine": every dev data dir
already stores a 9B `llmModelPath`, which as rev 2 stood would have made the
compact profile silently inert).** The runtime already distinguishes
*system-owned* stored values from *operator-set* ones via source markers
(`justsearch.llm.model_path.source` = ui-settings marker; the activation
service already treats `ui_settings` and `auto_selected_cuda12` as
system-owned rather than operator locks). The rule: **profile resolution
supersedes system-owned stored model paths (installer/activation-written —
they are re-derivable); a bare operator-set path (unmarked sysprop, env var,
hand-edited setting) stays sacred and wins.** Carriage stays conforming: the
activation path already writes a resolved model path *into settings* when
recovered from the install contract — profile-resolved paths follow that
exact pattern, with a source marker. Preferred shape: persist the *profile
id* and resolve the pair at `InferenceConfig` level (where the profile enum
already lives) rather than widening `applyRuntimeOverrides` with an mmproj
parameter; the signature chain (interface + admin default + impl + five call
sites) is bounded if widening is chosen instead.

### 2.4 Dev default and the agent surface

The dev runner already injects all backend configuration as spawn environment
(ambient operator env deliberately wins). Conforming additions:

1. `justsearch.dev.start` gains `chatProfile?: "compact" | "standard"`, default
   `compact`, delivered as `JUSTSEARCH_CHAT_PROFILE` in the spawn env through
   the three existing layers (MCP schema → CLI args → dev-runner spawn). A
   packaged/user launch has no dev runner and defaults to `standard` — the
   entire "build split" is a runtime decision by whoever starts the stack; no
   build flavor, no second artifact.
2. `justsearch.dev.ai_activate` gains `chatProfile?` beside its existing
   `variantId` — activation is when llama-server spawns, so it is the natural
   switch point. Measured switch cost is single-digit seconds either direction;
   per-verification switching is viable.
3. jseval keeps its `env_overrides` channel; a profile flag rides it.

The dev stack already boots AI-offline (autostart defaults false), so the
compact default costs nothing until an agent activates AI.

### 2.5 Honesty: conform to observed-vs-declared, don't invent a parallel guard

805 established the principle (for ONNX EPs) that *declared* configuration and
*observed* capability must both be reported, because they diverge silently. 657
shipped the projection surface (`ModeInfo{intent, realized}` on the runtime
manifest). This design extends the same principle to the chat engine instead of
adding a bespoke "tier stamp":

- **Runtime projection:** the realized chat identity — which model file actually
  loaded, and its observed GPU placement (offloaded-layers count) — joins the
  realized-capability projection surfaced by the manifest/inference status.
  Placement matters independently of this design: it makes the fit-to-free-VRAM
  fall-to-CPU failure (probe P2) permanently visible instead of requiring a
  log tail. This is the same defect class 805 fixed for ONNX (declared GPU,
  observed CPU, no field able to say so).
- **quick_health** projects that identity from the runtime (one-authority
  pattern, 606) so an agent inheriting a leased stack *sees* which profile it is
  talking to.
- **EvidenceBundle** records the realized chat identity in its environment
  block, which today carries only node version + platform. "Verified on
  compact" becomes a recorded, greppable fact.
- **jseval tier-2** records the served model identity in its result aggregate —
  the probe for this (`served_model_name` via `/v1/models`) already exists,
  used only by the judge-ceiling self-preference guardrail today — and
  hard-errors when the served model is a compact profile unless an explicit
  allow flag is passed. Quality numbers from a compact model must be impossible
  to produce by accident; today tier-2 records no model identity at all, so a
  poisoned baseline would be unattributable after the fact.

### 2.6 CLAUDE.md rule amendment

The `use-every-verification-tier` rule ("`ai-offline-isnt-a-wall`") gains one
sentence: the compact profile satisfies "load the model, send a real query" for
plumbing and feature-shape verification; quality-sensitive verification (RAG
answer quality, prompt-format changes, VLM extraction quality, anything
eval-adjacent) requires `standard`. The rule's spirit — a running model, a real
query, the full render — is preserved at every rung.

### 2.7 Pre-existing defects absorbed (found during this investigation)

These sit exactly on the surfaces this design touches; fixing them is this
work, not a cleanup sweep:

- `quick_health.aiActive` is hard-coded `null` — declared nullable-boolean,
  never populated. It becomes real (and gains model identity) in §2.5.
- The `freshness` block quick_health emits is absent from its declared MCP
  output schema (survives only via passthrough) — declare it while the schema
  is open for `chatProfile`.
- `agent_chat` does not mention or pre-check AI activation; the postmortem
  register records three rounds lost to `AI_OFFLINE` one call away from
  `ai_activate`. Owner decision D2 below decides between auto-activating the
  compact profile or a self-explaining error; either resolves the trap.

Logged but **not** absorbed (wrong owner): evidence capture silently drops the
`gpu`/`ui_ready` include values its schema accepts (no endpoint mapping) —
observations inbox; the `setSysPropIfBlank` first-writer-wins latch in the
install path — 840 B2 owns it.

### 2.8 Deliberately not built

- **No simultaneous dual chat stacks** — preserves the single-stack lease model
  (271/542/606) and the ONLINE/INDEXING VRAM-arbitration state machine.
- **No user-facing compact chat rung.** The same `chat-compact` package could
  one day serve sub-VRAM-floor users (today's `GPU_LITE` means "9B or no chat
  at all"), converting a hardware cliff into a quality step. That is a founder
  product decision adjacent to 657's open D7, not this work — see §7.
- **No packs-based delivery.** AI packs are a replace-not-add sideload channel
  requiring a bundled embedding asset; wrong seam.
- **No lite-mode extension.** `lite.mode` stays the boolean AI-off switch. (Its
  consumer bypasses `EnvRegistry` and reads env directly — an anti-pattern the
  new key must not copy; route through `EnvRegistry`/`ResolvedConfig`.)

## 3. Open decisions (owner)

| # | Decision | Recommendation |
|---|---|---|
| D1 | Compact model: Qwen3.5-4B vs 2B | 4B — confirmed mmproj (vision-dependent dev paths keep working), quality close enough to 9B that agent-chat/tool-call checks stay meaningful, still frees ~5 GB VRAM. Gate on probe P1 either way. |
| D2 | `agent_chat` with AI offline: auto-activate compact, or typed error naming `ai_activate`? | Auto-activate compact and return the activated profile in the result — the postmortem record shows the current silent trap costs real rounds; §2.5 stamping keeps the implicitness honest. |
| D3 | Axis naming | Settled by codebase constraint (§2.1): `chat.profile` = `standard`/`compact`; "tier" and "dev/full" rejected. Confirm or veto. |

## 4. Gating probes — RESULTS (2026-08-18 confidence pass)

- **P1 — PASSED (compatibility).** The shipped **b8571** binary loads the
  unsloth (b9222-era) Qwen3.5-4B Q4_K_M **and** its mmproj: healthy in 8.2 s
  (CPU, cold cache), log shows `loaded multimodal model`, no unknown-tensor or
  arch errors. Vision round-trip: a generated invoice image was read back with
  invoice number, total, and due date all correct (27.7 s at 4 CPU threads —
  compatibility check, not a perf figure). Native `tool_calls` via `--jinja`
  work identically to the 9B (the agent loop's actual mechanism). **No
  llama.cpp pack bump is needed.** GPU-side throughput/VRAM for the 4B were
  not measured (the GPU was held by a live dev session throughout the probe
  window); the expected class is bounded by the family's measured 1.7B/9B GPU
  numbers and should be captured during implementation verification.
- **P2 — mechanism confirmed, instance not reproduced.** The live dev
  llama-server's log shows `llama_params_fit` explicitly sizing offload to
  free VRAM at load time ("will leave 5013 >= 1024 MiB free, no changes
  needed" — i.e. it *would* reduce offload when free VRAM is low). This boot
  loaded first (11.1 GB free) and got 33/33 layers; the fall-to-CPU state
  therefore depends on load *order* (worker arenas grown before activation)
  and was not observed in any recovered log. The §2.5 placement projection
  remains the right permanent instrument; owner's CPU-only observation stays
  unattributed until one congested activation is captured.

### D1 evidence (quality screen, same-day; qualitative, non-benchmark)

Five paired prompts (native tool-call emission with the real `search` tool
schema, tool-result synthesis, RAG-with-citations, structured JSON, German
two-bullet constraint) against the live 9B and the 4B on the b8571 binary,
temperature 0: the 4B **passed all five**; its tool call was byte-identical to
the 9B's, citations and JSON were correct, and on tool-result synthesis the 4B
stayed *better grounded* than the 9B (which speculated with invented
arithmetic). Raw transcripts: local probe artifacts (see external doc). This
is strong evidence for D1 = 4B.

### Implementation-relevant facts confirmed in the same pass

- `ModelRegistryLoader` disables `FAIL_ON_UNKNOWN_PROPERTIES` — a new package
  field is back-compatible, but the loader must explicitly parse the exclusion
  flag or `InstallPlanner` will silently include `chat-compact` in user plans;
  pin with a registry test (the `everyPackageDeclaresATier` pattern).
- `AiPreflightService.computePreflight` already takes `InstallIntent` for its
  severity logic and has a deterministic unit seam — the exclusion flag rides
  the same severity path; a never-installed `chat-compact` must not surface as
  incomplete.
- Activation has **no VRAM hard-gate** (the 7.5 GB floor gates install
  planning only; activation merges recommended KV-cache flags) — compact
  activation on a busy GPU will not be wrongly refused. Self-test's 64 MiB
  VRAM-delta threshold is compatible with the 4B.
- `check-model-freshness.mjs` lints only *retired* names (registry-projected);
  adding `chat-compact` does not interfere.
- The skills tree is duplicated (`.claude/skills/` and `.agents/skills/`) —
  the CLAUDE.md/skill amendments in §2.6 must land in both copies.

## 5. Orphans (owned by this work, not a later sweep)

1. **`VlmExtractionProfile`'s private pair shape.** Hoisting the atomic
   *(model, mmproj)* pair into the shared configuration model domain (§2.3)
   supersedes the package-private enum's private encoding of the same
   invariant. The extraction-VLM *concept* and its config key survive
   unchanged; its two members migrate onto the hoisted type in this work, so
   the invariant lives in exactly one place.
2. **The mmproj-nulling swap behavior** (defensive projector-drop on model
   change) is superseded by pair-carrying swaps for profile-driven changes; the
   bare-path escape hatch keeps the defensive null (a bare path genuinely has
   no known projector).
3. **`quick_health.aiActive: null`** — replaced by a real value (§2.7).
4. **Rev 1 of this tempdoc** — its `justsearch.model.tier` key, `chat-dev`
   package name, and bespoke health/evidence stamping are superseded by §2;
   recorded here per tempdocs-are-dated-history rather than rewritten away.

## 6. Reach — principles this design instantiates (recorded, not built)

**P-A. Surfaces that certify work must report realized identity, not intended
identity.** Already established for ONNX EPs (805) and install intent (657's
`{intent, realized}`); this design extends it to chat model identity + GPU
placement. Known violations on main, all touched here: quick_health's null
`aiActive`, EvidenceBundle's model-blind environment block, tier-2's
unattributed results, llama-server's invisible offload count. *Earning its
keep:* a verification or eval gets caught mis-attributed by the stamp (served
model ≠ expected) within a few campaign cycles. *Retirement:* if the realized
fields are never consulted in any disagreement over that horizon, they are
dead weight — stop extending the projection and prune the unused fields.

**P-B. A model is selected as an atomic bundle (profile), on an axis orthogonal
to hardware variant and install intent.** The *(model, mmproj)* pair is one
instance; ONNX encoders' model+tokenizer+pooling-config is the same shape
(already guarded by the capability contract, a fact-completeness guard rather
than a selection axis). Candidate future instances: 657's open D7 (small dense
embedder rung) and the user-facing compact chat rung for sub-VRAM-floor
hardware (§2.8) — both would reuse `chat-compact`-style packages plus a
selector, none of which is built now. *Earning its keep:* a second
profile-selected package arrives (either instance above) and slots in without
registry rework. *Retirement:* if no second instance appears once the compact
profile has bedded in, `ChatModelProfile` stays a two-value switch and no
selection-group machinery should ever be built around it.

## 7. Explicitly out of scope

- User-model updates (none warranted 2026-08; watch for a Qwen3.8 small
  series — the prior generation went flagship→small in three weeks).
- Embedding/reranker upgrades (index-migration product decisions; route through
  the encoder bakeoff tooling).
- Eval grader refresh (2024-era graders; upgrading breaks campaign
  comparability — separate jseval decision).
- Blessing a user-facing compact chat rung (founder decision, §2.8/§6 P-B).

## 8. Log

- **2026-08-18 (rev 1)** — proposal + probe measurements recorded; design
  sketched as `justsearch.model.tier` with a `chat-dev` package.
- **2026-08-18 (rev 2)** — seam investigation (3 parallel code sweeps + adjacent
  tempdocs 657/840/804/805 read). Design conformed to existing substrate:
  "profile" axis per §2.1, registry package + planner exclusion per §2.2,
  existing hot-swap seam + pair-hoisting per §2.3, env-channel dev default per
  §2.4, 805-conformant honesty per §2.5. Orphans named (§5), principles
  recorded with retirement conditions (§6). D1–D3 pending owner; P1 gates.
- **2026-08-18 (implementation)** — §2 implemented on branch
  `worktree-842-chat-model-profile` with D1=4B (compact), D2=auto-activate,
  D3=`chat.profile`/`standard`/`compact` as recommended. Six slices
  (configuration+registry / inference resolution / activation+projection /
  dev-MCP / jseval / docs+evidence), all unit+gate green (config-surface
  passes; the new key is live-consumed). Orchestrator integration additions:
  `run.json` records the stack's spawn-time `chatProfile` and `agent_chat`
  auto-activation follows the STACK's profile rather than assuming compact;
  `JUSTSEARCH_CHAT_PROFILE` added to `HEADLESS_AI_ENV_VARS` (runHeadless env
  allowlist — found only by live E2E). **Live E2E verified end-to-end**: dist
  Head booted with `JUSTSEARCH_CHAT_PROFILE=compact` + autostart → realized
  status `{chatProfile: compact, mmprojActive: true}`, manifest `chat` block
  `{profileId: compact, modelFile: Qwen3.5-4B-Q4_K_M.gguf, mmprojActive:
  true}` (vision restored to dev), llama-server `/v1/models` serves the 4B,
  and the jseval compact guard fires against that live server. The §2.3
  precedence rule was exercised live: a stale `ui_settings`-marked model-path
  sysprop was correctly classified system-owned and superseded by the
  explicit profile. **Live discovery (new lead for the owner's CPU-only
  observation):** the shared `JUSTSEARCH_HOME` settings carry stale pins —
  `llmModelPath` → the old repo's Meta-Llama-3.1-8B and `serverExecutablePath`
  → a deleted `565-agent-window` worktree exe. A dead exe path degrades the
  engine to the data-dir default (CPU build); inspect and repair the real
  home's settings — this is a concrete rival to the P2 params_fit
  hypothesis. Deferred, known: deactivate does not clear the
  `justsearch.chat.profile` sysprop (divergence is visible via status, not
  silent); the EvidenceBundle spec doc cited by the validator header does not
  exist (decide whether to write it); pre-existing reds untouched
  (`JUSTSEARCH_APP_VERSION` matrix row, closure-check `api-port.txt`
  findings, Node teardown crash in the capture harness on live URLs).
- **2026-08-18 (confidence pass)** — pre-implementation de-risking executed
  (approved plan). P1 **passed** (b8571 loads the 4B + mmproj; vision and
  native tool-calls verified; no pack bump needed). P2 mechanism confirmed via
  live `llama_params_fit` log lines; fall-to-CPU instance still unobserved.
  §2.1 corrected: chat model and extraction VLM are one engine/one axis —
  extend `VlmExtractionProfile`, do not add a sibling enum. §2.3 gained the
  precedence rule (system-owned vs operator-owned source markers) closing the
  stored-settings landmine, plus the live finding that dev stacks currently
  run **text-only** (no `--mmproj` on the running server). D1 evidence added
  (4B passed all five paired quality checks). Implementation-relevant gate
  facts recorded in §4. Remaining before implementation: owner confirms
  D1–D3; capture 4B GPU numbers during implementation verification.
