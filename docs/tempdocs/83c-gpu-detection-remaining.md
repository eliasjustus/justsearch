---
title: "GPU Detection: Remaining Improvements"
status: done
created: 2026-02-02
completed: 2026-02-02
origin: tempdoc 83 (section 4)
---

# 83c — GPU Detection: Remaining Improvements

GPU detection improvements after the infrastructure work in commit `5785b6aa`.

---

## Implemented

| # | Improvement | Formal Issue | Status | Notes |
|---|-------------|-------------|--------|-------|
| 1 | Clear "NVIDIA required" messaging | GPU-009 | **DONE** | ModeIndicator: "require an NVIDIA GPU". useAiCapabilities: "Requires NVIDIA GPU". |
| 2 | Actionable NVML error messages | GPU-010 | **DONE** | `friendlyNvmlError()` in BrainView maps 5 NvmlService error prefixes to user-friendly messages with remediation steps. Raw fallback for unknown patterns. |
| 4 | Configurable VRAM thresholds | GPU-007 | **DONE** | `JUSTSEARCH_VRAM_THRESHOLD_{12GB,8GB,4GB}` env vars / system properties. Read directly in VramFlagsUtil (no EnvRegistry dependency needed in ai-bridge). |

## Deferred

| # | Improvement | Formal Issue | Status | Notes |
|---|-------------|-------------|--------|-------|
| 5 | AMD/Intel GPU support | GPU-001 | Deferred | Requires ROCm/HIP or oneAPI bindings + hardware |

## Deferred to canonical issues

Tracked only in `docs/reference/issues/gpu-detection.md`:

- **GPU-004** (Multi-GPU enumeration)
- **GPU-002** (Linux NVML support)
- **GPU-008** (Temperature/throttling monitoring)
- **GPU-006** (Self-test VRAM measurement timing)
