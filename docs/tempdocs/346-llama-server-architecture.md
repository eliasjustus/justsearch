---
title: "346: llama-server Architecture — Binary, Model & Vision Lifecycle"
type: tempdoc
status: done
created: 2026-03-23
---

> NOTE: Noncanonical doc (strategy). May drift.

# 346: llama-server Architecture — Binary, Model & Vision Lifecycle

## Purpose

Fix the accumulated technical debt in llama-server binary discovery,
model configuration, and vision mode management. The current
architecture was designed for "one model, one binary, text-only" and
has broken under: hybrid architectures (qwen35 DeltaNet), vision
multimodality (mmproj), model-specific runtime quirks, and binary
variant selection.

**Trigger:** Tempdoc 252 Phase 6 (VDU via chat LLM) discovered
multiple issues during experimentation that block production
integration.

---

## Problems

### P1: Binary discovery is broken in dev mode

`InferenceConfig.findServerExecutable()` searches:
1. `JUSTSEARCH_SERVER_EXE` env var (explicit override)
2. `{baseDir}/native-bin/llama-server/llama-server.exe` (canonical)
3. Subdirectories of `native-bin/llama-server/` — **but skips
   `variants/`**

The only working binary (build 8185, Qwen3.5-compatible) is at
`modules/ui/native-bin/llama-server/variants/cuda12/llama-server.exe`.
This path is never found because `variants/` is explicitly excluded
(line 253 of `InferenceConfig.java`).

The canonical path (`native-bin/llama-server/llama-server.exe`) does
not exist in dev mode. The Tauri build copies the binary from
`modules/shell/src-tauri/resources/headless/` at package time, but
that binary is build 7502 which **cannot load the Qwen3.5 model**
(`unknown model architecture: 'qwen35'`).

**Result:** In dev mode, the system either requires
`JUSTSEARCH_SERVER_EXE` override or fails to find the binary.

### P2: No model↔binary compatibility check

The code launches llama-server and passes the model path. If the
binary doesn't support the model architecture, it fails at runtime
with an opaque error. No pre-flight check, no graceful degradation,
no user-facing message.

**Discovered during 252:** Build 7502 failed with `error loading
model architecture: unknown model architecture: 'qwen35'`. The
system just reported "failed to load model" with no guidance.

### P3: Vision mode is not codified

Vision requires multiple coordinated settings that were discovered
experimentally during tempdoc 252:

| Setting | Required for | What happens without it |
|---------|-------------|----------------------|
| `--mmproj <path>` | Vision capability | Text-only mode (correct fallback) |
| `-np 1` (single slot) | Vision reliability | Alternating HTTP 500 "failed to process image" on multi-slot |
| `--cache-ram 0` | Vision stability | Server crashes silently after ~7 pages (prompt cache corruption) |
| `chat_template_kwargs: {"enable_thinking": false}` | Correct output | VLM output goes to `reasoning_content` field (lost) instead of `content` |
| Temperature 0 | Deterministic OCR | Minor — but 0.1 produces preamble more often |

None of these are enforced by the code. `LlamaServerOps` launches
with generic flags. If mmproj is present, vision is "enabled" but
the server isn't configured for it.

### P4: Hardcoded model filenames

`InferenceConfig` defaults:
```java
String vlmModel = nonBlankOr(rc.ai().vlmModel(), "Qwen_Qwen3.5-9B-Q4_K_M.gguf");
String mmprojModel = nonBlankOr(rc.ai().mmprojModel(), "mmproj-F16.gguf");
```

These were `Qwen3VL-8B-Thinking-Q4_K_M.gguf` until tempdoc 252
changed them. When the model changes again, these defaults break
until someone updates Java code. Should be discoverable from disk.

### P5: No hardware-aware binary variant selection

`modules/ui/native-bin/llama-server/variants/` has `cuda12/`. There's
no logic to select the right variant based on detected GPU. The
`variants/` directory is in fact *skipped* by `findServerExecutable`.

### P6: Stale Tauri-bundled binary

`modules/shell/src-tauri/resources/headless/native-bin/llama-server/`
contains build 7502 which cannot load the current model. The desktop
app would fail to start the LLM.

---

## Relevant Files

| File | Role |
|------|------|
| `modules/app-inference/.../InferenceConfig.java` | Binary + model path resolution, config record |
| `modules/app-inference/.../LlamaServerOps.java` | Process launch, health probing, crash recovery |
| `modules/app-inference/.../OnlineModeOps.java` | HTTP calls to llama-server (`/v1/chat/completions`, vision) |
| `modules/app-inference/.../ServerPropsOps.java` | Parses `/props` for `modalities.vision` |
| `modules/app-inference/.../InferenceLifecycleManager.java` | Mode state machine (ONLINE/INDEXING/OFFLINE) |
| `modules/app-services/.../vdu/VduProcessor.java` | VDU prompt, two-pass pipeline |
| `modules/shell/src-tauri/resources/headless/native-bin/` | Tauri-bundled binary (build 7502) |
| `modules/ui/native-bin/llama-server/variants/cuda12/` | Working binary (build 8185) |
| `config/application.yaml` | Runtime config (model_path was hardcoded, now commented out) |

---

## Design Decisions (revised after critical review)

### D1: Vision flags are mode-scoped, not global

The original plan applied `-np 1` and `--cache-ram 0` globally whenever
mmproj is present. This permanently degrades chat performance (no
concurrent slots, no prompt caching) even when VDU isn't active.

**Decision:** VDU is a batch operation (process N pages), not an
interactive feature. Vision-safe flags are applied only when VDU
processing is requested, via server restart. The lifecycle manager
already handles ONLINE↔INDEXING transitions with VRAM flush delays —
VDU follows the same pattern. Chat mode retains multi-slot and prompt
caching.

Implementation: `InferenceConfig` gains a `vduMode` flag. When true,
`LlamaServerOps.startLlamaServer()` adds `-np 1`, `--cache-ram 0`,
`--mmproj`. When false (default), those flags are omitted. The
`VduProcessor` orchestrates: request VDU mode → process pages →
release VDU mode.

### D2: Crash diagnostics over GGUF pre-flight

The original plan proposed reading GGUF `general.architecture` before
launch. This requires a Java GGUF parser (doesn't exist) or a Python
dependency in the startup path.

**Decision:** Parse llama-server crash output instead. The server
already redirects stderr to `logs/llama-server.log`.
`waitForServerHealth()` already detects DLL-not-found exit codes.
Extend it to parse `unknown model architecture` and surface a
user-facing message. This catches *any* startup error, not just
architecture mismatches, with zero maintenance burden.

### D3: sendChatRequest must honor all SamplingParams fields

The streaming path (`streamChatWithTools`) handles `enableThinking`,
`grammar`, and `toolChoice` from `SamplingParams`. The non-streaming
`sendChatRequest` only handles `temperature` and `topP`. This is a
correctness gap — item 3e (vision thinking suppression) requires the
non-streaming path to emit `chat_template_kwargs`.

**Decision:** Fix `sendChatRequest` to apply all `SamplingParams`
fields. This is a prerequisite for 3e, not a VDU-specific fix.

### D4: DPI change and MIME type fix are independent

The original 3d conflated "change DPI to 100" with "output JPEG".
The pipeline is already PDF → PNG (lossless intermediate) → JPEG
(wire format via ImagePreparer). The intermediate format is fine as
PNG. The real bug is `sendVisionRequest` line 641: `data:image/png`
MIME type but the bytes are JPEG from ImagePreparer.

**Decision:** Split into two: DPI change in PdfImageRenderer, MIME
type bugfix in sendVisionRequest. Don't change intermediate format.

---

## Work Items

### Binary discovery & variant selection

- [x] **1a.** Fix `findExistingServerExecutable` to search inside
  `variants/` instead of skipping it. When CUDA is available
  (`VramDetector.isCudaAvailable()`), prefer `variants/cuda12/`.
  Fall back to the baseline (canonical path or Tauri bundle).
  *Done: uses `gpuLayers > 0` as signal (avoids VramDetector in
  static path). New `findCudaVariant()` helper. Step 4 fallback
  finds variant even when CPU mode (it still works).*
- [x] **1b.** Add hardware-aware variant selection: detect CUDA via
  existing `VramDetector`, pick `cuda12/` when GPU present, fall
  back to baseline. Wire through `InferenceConfig.fromEnvironment`.
  *Done: merged into 1a — `preferCudaVariant` boolean threaded
  from `fromEnvironment` through `findServerExecutable`.*
- [x] **1c.** Verify Tauri-bundled binary version. `runtime-version.txt`
  says `b8157` but tempdoc claims build 7502 — verify the actual exe
  capabilities before deciding if an update is needed. **Note:** this
  is a binary asset update, not a code change. Defer if it requires
  release engineering.
  *Done: Tauri `resources/headless/` is gitignored (local build
  artifact). Copied b8185 binary + DLLs from dev `variants/cuda12/`
  to both Tauri root and Tauri `variants/cuda12/`. Updated
  `runtime-version.txt` to `b8185`.*

### Model compatibility

- [x] **2a.** Improve crash diagnostics in `waitForServerHealth()`:
  parse llama-server log output for known failure patterns (`unknown
  model architecture`, `failed to load model`, CUDA errors). Surface
  as a structured user-facing message instead of generic "health
  check timed out". ~~GGUF pre-flight removed — see D2.~~
  *Done: `diagnoseServerFailure()` in LlamaServerOps reads last 100
  lines of log, matches 4 known patterns.*
- [x] **2b.** Replace hardcoded model filename defaults with disk
  discovery. Heuristic: scan models dir for `.gguf` files, pick
  largest non-mmproj file as main model, pick file matching
  `mmproj*` as projector. Fallback to current hardcoded names if
  scan finds nothing.
  *Done: `discoverGgufModels()` in InferenceConfig.*

### Vision mode codification

- [x] **3a.** Add VDU server mode to lifecycle manager. When VDU
  processing is requested: transition to VDU mode (restart server
  with `-np 1`, `--cache-ram 0`, `--mmproj`). After batch completes:
  transition back to ONLINE (restart without vision-safe flags).
  See D1 for rationale.
  *Done: `InferenceConfig.vduMode` + `ILM.enterVduMode()/exitVduMode()`
  + `VduProcessor` wraps with enter/exit in finally block.*
- [x] **3b.** Update `VduProcessor` extraction prompt from the current
  markdown-style prompt to the validated plain-text prompt:
  `"Return the plain text representation of this document as if you
  were reading it naturally. Do not add any commentary."`
- [x] **3c.** Set VDU sampling params: temperature=0 (was 0.2),
  max_tokens=4096 (was 2048). Update `SamplingParams.VDU` preset.
- [x] **3d-i.** Fix MIME type bug: `sendVisionRequest` declares
  `data:image/png` but bytes are JPEG from `ImagePreparer`. Change
  to `data:image/jpeg`.
- [x] **3d-ii.** Reduce `PdfImageRenderer.DEFAULT_DPI` from 150 to
  100 (validated: sufficient quality, ~50% faster rendering).
- [x] **3e.** Fix `sendChatRequest` to honor all `SamplingParams`
  fields (enableThinking, grammar, toolChoice) — parity with the
  streaming path. Then set `SamplingParams.VDU` to include
  `enableThinking=false`. See D3.

### Cleanup

- [x] **4a.** Remove the stale `model_path` line from
  `config/application.yaml`. *Was an active YAML key pointing to a
  nonexistent file, not just a comment. Replaced with a comment.*

---

## Context from Tempdoc 252

### Validated VDU configuration (Run 1, deterministic)

| Setting | Value | Why |
|---------|-------|-----|
| Prompt | "Return the plain text representation of this document as if you were reading it naturally. Do not add any commentary." | Eliminates preamble, plain text > markdown for BM25 search |
| DPI | 100 | Sufficient quality, ~50% faster than 150 DPI |
| Temperature | 0 | Deterministic output, fewer hallucinations |
| Image format | JPEG | Smaller payload than PNG |
| max_tokens | 4096 | Full-page text can exceed 2048 tokens |
| Thinking mode | Disabled (`enable_thinking: false`) | Output goes to `content` not `reasoning_content` |
| Slots | 1 (`-np 1`) | Multi-slot causes vision 500 errors |
| Prompt cache | Disabled (`--cache-ram 0`) | Prevents silent crash after ~7 pages |

### Quality results (50-page stratified OHR-Bench sample)

| Variant | Hard (15) | Medium (15) | Easy (20) | Total (50) |
|---------|-----------|-------------|-----------|------------|
| Docling (GPU) | 23.1% | 73.1% | 93.1% | 66.1% |
| **VLM Run 1** | **54.6%** | **74.2%** | **93.5%** | **76.0%** |

VLM beats Docling by 10% overall with zero new dependencies. Hard
bracket (Tika-failed pages) is 2.3x better than Docling.

---

## Dependencies

- Tempdoc 252 items 6j–6l (VDU production integration) are blocked
  on items 3a–3e in this tempdoc.
- `VramDetector` in `modules/ai-bridge` provides GPU detection for
  item 1b.
- Model manifest work may overlap with tempdoc 340.

---

## Verification Results

**Merged to `main`** on 2026-03-23 (merge commit `64b5614b9`).

### Live dev stack verification (2026-03-23)

- Disk discovery found `Qwen_Qwen3.5-9B-Q4_K_M.gguf` automatically
  (no `JUSTSEARCH_SERVER_EXE` or hardcoded model path needed)
- Inference went ONLINE: llama-server started, health check passed,
  vision hparams loaded (mmproj active)
- Binary discovery found the CUDA variant under `variants/cuda12/`

### V1: VDU restart cycle — deferred to tempdoc 252 Phase 6

The VDU mode enter/exit API exists and compiles, but VDU is triggered
by the OfflineCoordinator as a background batch process on "pending
VDU files." The VDU trigger path is tempdoc 252 items 6j–6l (VDU
production integration), which depends on the infrastructure built
here. V1 can only be tested end-to-end once 252 Phase 6 wires the
trigger path.

### V2: Crash diagnostics — not tested live

`diagnoseServerFailure()` was not tested against a real crash. Low
priority — the code is straightforward string matching on log output.
The health check still waits up to 30s before surfacing the
diagnostic (early process exit detection could short-circuit this
in a future improvement).

### V3: Tauri binary freshness — local fix only

b8185 copied to the gitignored `resources/headless/` directory on
this machine. Will go stale on other machines or after clean checkout.
Consider a build-time version check in the future.
