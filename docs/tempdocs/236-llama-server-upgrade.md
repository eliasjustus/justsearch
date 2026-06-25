---
title: "236: llama-server Binary Upgrade Investigation"
type: tempdoc
status: done
created: 2026-02-26
updated: 2026-02-26
---

# 236: llama-server Binary Upgrade Investigation

**Status:** active — investigation complete. Ready for upgrade execution (§9 checklist).
**Scope:** Investigate and execute an upgrade of the bundled `llama-server.exe` binary from the
currently pinned `b7502` to the latest upstream `ggml-org/llama.cpp` release. Assess risks,
document the upgrade procedure, and validate via battery.

---

## 1. Background

JustSearch bundles a pinned prebuilt `llama-server.exe` from the upstream `ggml-org/llama.cpp`
GitHub releases. The current pin is `b7502` (built ~late 2025). As of 2026-02-26, the latest
upstream release is `b8157` — 655 builds ahead.

The question of upgrading arose during the tempdoc 227 Phase 3 investigation into the
grammar+tools conflict (§7b). The conflict turned out to be **intentional architecture** (not a
bug), and a newer binary will not change it. However, 655 builds of upstream development include
significant improvements worth evaluating.

---

## 2. Current pinning mechanism

All version data lives in a single file: `modules/ui/build.gradle.kts`.

```kotlin
// line 277
val llamaPrebuiltVersion = "b7502"
val llamaPrebuiltAsset   = "llama-$llamaPrebuiltVersion-bin-win-cpu-x64.zip"
val llamaPrebuiltUrl     = "https://github.com/ggml-org/llama.cpp/releases/download/..."
val llamaPrebuiltSha256  = "71D0BBC9AE146F469AD1AC05EC35BE2722BFD42EAA4B93D2DC82CF46599BD9FD"

// CUDA variant (same version)
val llamaCudaAsset = "llama-$llamaPrebuiltVersion-bin-win-cuda-12.4-x64.zip"
```

The build downloads, verifies SHA-256, and extracts. The extracted binary is placed at:
`modules/shell/src-tauri/resources/headless/native-bin/llama-server/llama-server.exe`
and the CUDA variant at:
`modules/shell/src-tauri/resources/headless/native-bin/llama-server/variants/cuda12/llama-server.exe`

A human-readable version stamp is written to:
`modules/shell/src-tauri/resources/headless/native-bin/llama-server/runtime-version.txt`
(current contents: `llama.cpp b7502 prebuilt`)

There is also a local source-build path (`llamaRuntime=source` Gradle property) that compiles
from `third_party/llama.cpp/`. This path is for developer overrides and not used in production
builds.

---

## 3. Why upgrade

### 3.1 Confirmed improvements in b7503–b8157

> **Correction note:** Several entries in the draft version of this section were wrong.
> PR #16932 (Qwen3-Coder XML tool-call support), PR #12168 (`response_format` typo fix), and the
> fix for issue #13189 (`<think>` leakage — fixed by PR #13196) were all merged months before
> b7502. They are already present and are not reasons to upgrade.

| Area | PR / Build | Details |
|------|-----------|---------|
| **Grammar stack-overflow fix** ⚠️ | PR #18342, ~b7521 | `[\s\S]*` and `[\s\S]*?` patterns in grammar regex caused recursive backtracking stack overflows on inputs ≥50K chars. Also removed `[\s\S]*?` from Hermes 2 Pro parser in `chat.cpp`. Critical stability fix for long-context agentic sessions. |
| **New Jinja engine** ⚠️ | PR #18462, ~b7752 | minja (`vendor/minja/`) completely replaced by new in-tree Jinja engine under `common/jinja/`. Tested against 370 templates. Many follow-up bugfixes in b7752–b7900. Biggest regression risk (see §6). |
| **Jinja engine bugfixes** | many, b7752–b7900 | `join`/`map`/`sort` attribute support, `tojson` for bool, float literal lexing, object item order/`dictsort`, `none` filter handling, mixed-type keys, type coercion, empty `tools` field handling (PR #19176 — don't pass null `tools` to templates). |
| **Qwen3-Coder parser overhaul** | PR #19765, ~b8100 | GBNF grammar parser replaced with PEG-based Nemotron Nano 3 parser. Fixes: crashes after 50+ tool calls (`llama_grammar_accept_token`), invalid JSON (duplicate fields), 80B model crashes (issues #19382, #19430, #19304). Adds parallel tool calling. Fixes `json_schema` + tools for the Qwen3-Coder path. |
| **`reasoning_content` round-trip** | PR #18994, ~b7801 | Assistant messages now accept incoming `reasoning_content` for history replay. Also adds `chat_template_caps.supports_preserve_reasoning` to `/props` for capability discovery. |
| **`max_completion_tokens`** | PR #19831, ~b8144 | OpenAI-spec replacement for deprecated `max_tokens`; sets upper bound on reasoning + output tokens combined. `max_tokens` still works. |
| **Timing measurement fix** | PR #18713, ~b7690 | Fixed bug where `timings` in server responses was measured incorrectly for async backends. |

### 3.2 What does NOT change with an upgrade

- **Grammar+tools conflict** — still architectural. `chat.cpp` explicitly throws
  `"Cannot specify grammar with tools"` in all versions. The `TOOL_CALL_GRAMMAR` constant and
  `OnlineModeOps` guard remain correct regardless of version.
- **Hermes 2 Pro tool-call format** — the streaming format and tool-call structure are stable.
  However, the new Jinja engine (PR #18462) evaluates templates differently from minja in edge
  cases. The Hermes 2 Pro template must be validated after upgrade (battery §7 Step 7).
- **`enable_thinking` per-request override** — already in b7502 (PR #13196, June 2025). No change.
- **`reasoning_format` flag** — behavior unchanged across all versions in this range.
- **`--reasoning-budget 0`** — still disables thinking; behavior confirmed unchanged.

---

## 4. Local source analysis — what b7502 already supports

Direct inspection of `third_party/llama.cpp/tools/server/` and `common/chat.cpp` revealed
several API capabilities that are **already in b7502** but not yet used by JustSearch. These
represent zero-upgrade-cost improvements.

### 4.1 Per-request `enable_thinking` toggle ⚠️ unblocks Direction D

**Source:** `tools/server/utils.hpp` lines 717–722, `server.cpp` line 2593.

The `/v1/chat/completions` endpoint accepts a `chat_template_kwargs` field that can override
thinking mode per-request:

```json
{
  "chat_template_kwargs": { "enable_thinking": false }
}
```

The server-level default is computed at startup:
```cpp
enable_thinking = use_jinja && reasoning_budget != 0
                && common_chat_templates_support_enable_thinking(chat_templates.get())
```

With `REASONING_BUDGET = 0` (JustSearch default), `enable_thinking` starts as `false`. A
per-request `chat_template_kwargs: {"enable_thinking": true}` can turn it on for individual
calls; `false` can explicitly disable it even when the server default is true.

**Impact on tempdoc 227 Direction D / E3-unblock:** This is the per-request thinking control
that §8 of tempdoc 227 described as "blocked on upstream PR." It is already present in b7502.
The blocker was misidentified — the API exists; JustSearch simply hasn't wired it. Required
changes:

1. Add `chatTemplateKwargs` field to `SamplingParams.java` (or a separate `LlmCallOptions`
   container)
2. In `OnlineModeOps.java` `sendChatRequest()`, include `chat_template_kwargs` in the request
   body when present
3. In `AgentLoopService.java`, pass `{"enable_thinking": false}` on Organizer turns (where
   thinking wastes budget) and leave default on PRIMARY turns (where chain-of-thought helps
   multi-hop reasoning)

This unlocks selective thinking: PRIMARY benefits from reasoning on complex multi-hop tasks;
Organizer E0a gets a lighter, faster call with no reasoning overhead.

### 4.2 `thinking_forced_open` per-request parameter

**Source:** `tools/server/README.md` line 1226, `utils.hpp` line 777.

```json
{ "thinking_forced_open": true }
```

Forces a reasoning model to always emit its reasoning chain. Only works on certain models.
Potentially useful for debugging reasoning quality on complex h003/h004 scenarios without
changing `REASONING_BUDGET` server-wide.

### 4.3 `response_format` with `json_schema` (blocked alongside tools in b7502)

**Source:** `common/chat.cpp` line 1058.

In b7502, `json_schema` (including via `response_format`) throws an error when `tools` are
also present:
```cpp
if (are_tools_provided && (is_json_schema_provided || is_grammar_provided))
    throw "Tools call must not use json_schema or grammar"
```

> **Correction:** The earlier claim that PR #12168 fixed this conflict in b7650+ was wrong.
> PR #12168 (March 2025, before b7502) fixed a typo regression in `json_schema` parsing when
> tools are NOT present. The `json_schema` + tools conflict in b7502 remains unresolved for
> Hermes 2 Pro. It is partially resolved for the Qwen3-Coder path via PR #19765 (~b8100),
> which fixed `json_schema` + tools for the Nemotron PEG parser. For Hermes 2 Pro, the conflict
> remains architectural (same guard in `chat.cpp`). Not a blocker — we do not currently use
> `json_schema` alongside tools.

### 4.4 Qwen3 chat template formats already in b7502

**Source:** `common/chat.h` — `COMMON_CHAT_FORMAT_QWEN3_CODER_XML` (line 123).

Qwen3-Coder XML format is already natively handled in b7502. Our production model
(Qwen3VL-8B-Thinking Q4_K_M) uses the Hermes 2 Pro format (`COMMON_CHAT_FORMAT_HERMES_2_PRO`),
which is also present. No template upgrade needed for the current model.

### 4.5 `reasoning_format` is server-level only (not per-request)

**Source:** `utils.hpp` line 701 — `inputs.reasoning_format = opt.reasoning_format` (from
server startup options only; no per-request override path from the request body).

`--reasoning-format deepseek` cannot be toggled per-request. This is unchanged across all
known versions. The `reasoning_content` SSE field is emitted when `reasoning_format == deepseek`
and the model emits thinking tokens — which is suppressed by our `REASONING_BUDGET = 0` default.

---

## 6. Risks

| Risk | Severity | Mitigation |
|------|----------|-----------|
| **New Jinja engine behavior** | **High** | PR #18462 (~b7752) completely replaces minja with a new in-tree engine. Behavior differs from minja in edge cases; many bugfixes landed in b7752–b7900. Hermes 2 Pro template must be validated. Run full exploration battery (`exp-001`–`exp-016`). |
| Tool-call format regression | Medium | Battery run validates all h001–h006 handoff scenarios |
| Jinja template regressions (individual) | Medium | Many individual template fixes in b7752–b7900; should be stable by b8157. Verify with `exp-001`–`exp-016` exploration battery. |
| `--reasoning-format deepseek` regression | Low–Medium | `<think>` stripping is defense-in-depth; verify Thinking model still produces reasoning_content in separate field |
| `--reasoning-budget 0` behaviour change | Low | Verify thinking is still disabled when REASONING_BUDGET=0 |
| New DLL dependencies | **Low (confirmed)** | Windows artifact structure is identical: same zip names, same artifact set. Internal DLL contents may still differ — inspect new zip before updating installer allowlist. |
| SHA-256 mismatch | None (expected) | Must update `llamaPrebuiltSha256` after downloading |

---

## 7. Upgrade procedure

### Step 1 — Choose target version

Download the release index and select a build. Prefer the most recent build with a full set of
Windows CPU and CUDA artifacts. As of 2026-02-26, target: **b8157**.

Check release availability:
```
https://github.com/ggml-org/llama.cpp/releases/tag/b<N>
```
Required artifacts:
- `llama-b<N>-bin-win-cpu-x64.zip`
- `llama-b<N>-bin-win-cuda-12.4-x64.zip`
- `cudart-llama-bin-win-cuda-12.4-x64.zip` (CUDA runtime redistributables — may not change between versions)

### Step 2 — Compute new SHA-256

```powershell
# Download and hash
$cpu = Invoke-WebRequest "https://github.com/ggml-org/llama.cpp/releases/download/b<N>/llama-b<N>-bin-win-cpu-x64.zip" -OutFile "llama-cpu.zip"
(Get-FileHash "llama-cpu.zip" -Algorithm SHA256).Hash
```

### Step 3 — Update build.gradle.kts

Two changes in `modules/ui/build.gradle.kts`:
```kotlin
val llamaPrebuiltVersion = "b<N>"          // was "b7502"
val llamaPrebuiltSha256  = "<new-hash>"    // was "71D0BBC9..."
```
The CUDA zip URL derives from `llamaPrebuiltVersion` automatically — no separate change needed.

### Step 4 — Update runtime-version.txt

```
modules/shell/src-tauri/resources/headless/native-bin/llama-server/runtime-version.txt
```
Change `llama.cpp b7502 prebuilt` → `llama.cpp b<N> prebuilt`

### Step 5 — Update documentation references

`b7502` appears in 9 locations across docs and scripts. All must be updated atomically:

| File | Reference type |
|------|---------------|
| `docs/explanation/16-gpu-booster-pack.md` | ~8 references (version pin, zip names, DLL list) |
| `scripts/ci/verify-installer-nsis-win.ps1` | 1 comment (`pinned to ggml-org/llama.cpp (b7502)`) |
| `docs/tempdocs/186-llm-agentic-file-operations.md` | 2 references (version confirmation notes) |
| `docs/reference/contributing/agent-guide.md` | Check for references |

The DLL list in `docs/explanation/16-gpu-booster-pack.md` §2.1 may need updating if the new
release adds or removes bundled DLLs. Verify by inspecting the new zip contents.

### Step 6 — Rebuild and verify binary version

```bash
./gradlew.bat :modules:ui:downloadLlamaServer
# Extract and confirm version
./modules/shell/src-tauri/resources/headless/native-bin/llama-server/llama-server.exe --version
```
Expected output: `version: <N> (<commit>)`

### Step 7 — Validate with battery

Run the full handoff battery (h001–h006, N=2) and the exploration battery (exp-001–exp-016, N=1)
with the new binary. Pass criteria:
- Handoff battery: ≥ current best result (5/6) in at least one run
- Exploration battery: no new failures vs. the `8/16` pre-existing baseline
- No `BUDGET_EXHAUSTED` regressions on h002 beyond what was observed with b7502

If regressions appear, compare against known b7502 results before deciding whether to proceed.

---

## 8. third_party/llama.cpp relationship

The `third_party/llama.cpp/` directory contains the source tree that was used to build b7502
locally (the `build/` directory with MSVC project files is present). It is not a git submodule
and is not automatically updated. Two options:

- **Prebuilt path (recommended):** Update only `build.gradle.kts`. The source tree in
  `third_party/` is reference material for code inspection and the `llamaRuntime=source`
  developer path. It does not need to match the pinned version exactly for normal operation.
- **Source path (optional, higher effort):** Update `third_party/llama.cpp/` to match the new
  version by pulling from upstream. Required only if the `llamaRuntime=source` path needs to
  produce the same binary as the pinned prebuilt.

For this upgrade, **use the prebuilt path only**.

---

## 9. Implementation checklist

- [ ] **Select target version** — confirm CPU + CUDA artifacts exist for chosen build
- [ ] **Download CPU zip, compute SHA-256**
- [ ] **Update `build.gradle.kts`** — version string + SHA-256 hash
- [ ] **Update `runtime-version.txt`**
- [ ] **Update doc references** — 9 locations across 4 files
- [ ] **Verify DLL list** — inspect new zip for added/removed DLLs; update GPU booster pack doc §2.1 if needed
- [ ] **Run `./gradlew.bat :modules:ui:downloadLlamaServer`** and confirm binary version output
- [ ] **Run handoff battery** (h001–h006, N=2) — assert ≥5/6 in at least one run
- [ ] **Run exploration battery** (exp-001–exp-016, N=1) — assert no new failures
- [ ] **Commit** — single commit touching build.gradle.kts, runtime-version.txt, and all doc references

---

## 10. Open questions — ANSWERED by upstream research

1. **Does b8157 fix the `<think>` tag leakage bug #13189?**

   **Answer: Already fixed before b7502.** Issue #13189 was fixed by PR #13196 (merged June 2025,
   well before b7502). The fix added the `chat_template_kwargs: {enable_thinking: false}` API and
   ensured it properly suppresses `<think>` output. The two defense-in-depth THINK_TAGS stripping
   layers in `AgentLoopService` are therefore belt-and-suspenders against model-level leakage,
   not a workaround for a server bug. They should be kept unless proven unnecessary by battery
   evidence.

2. **Does the new CUDA 12.4 zip still bundle `cudart-llama-bin-win-cuda-12.4-x64.zip` separately?**

   **Answer: Yes — same structure.** The Windows artifact set is identical in b8157: the main
   `llama-bNNNN-bin-win-cuda-12.4-x64.zip` does NOT include the CUDA runtime redistributables.
   `cudart-llama-bin-win-cuda-12.4-x64.zip` is still a separate download. The GPU booster pack
   procedure in `docs/explanation/16-gpu-booster-pack.md` does not need updating on this point.

3. **Are there any breaking changes to the `/v1/chat/completions` SSE streaming protocol?**

   **Answer: No breaking changes.** The SSE chunk format (`data: {...}\n\n`), `choices[0].delta`
   structure, `finish_reason`, `[DONE]` terminator, and `reasoning_content` delta field are all
   unchanged. **Additive changes:** (a) PR #18994 (~b7801): `reasoning_content` is now accepted
   as input in assistant messages for history replay; (b) PR #19831 (~b8144): `max_completion_tokens`
   parameter added (OpenAI-spec alias for `max_tokens`); (c) `/props` endpoint now reports
   `chat_template_caps.supports_preserve_reasoning` for capability discovery. All additive —
   existing clients are unaffected.

---

## 11. Related

- tempdoc 227 §7b — grammar+tools investigation (resolved as non-actionable; links here)
- `modules/ui/build.gradle.kts` — single source of truth for pinned version + SHA-256
- `docs/explanation/16-gpu-booster-pack.md` — GPU variant documentation (most doc references)
- `docs/explanation/05-ai-architecture.md` — references `<think>` tag leakage issue #13189

---

## Staleness review (2026-05-18)

Investigation-shape tempdoc (research / audit / findings) — terminal by nature per the README's "investigation log that produced a decision" definition. Body content preserved as design history. Classification: INVESTIGATION. Stale for 68 days at audit time.

