---
title: "API Responses Declare Runtime Context"
type: decision
status: stable
description: "Any endpoint whose behavior varies by runtime mode must surface that mode in its response to prevent silent behavioral switches."
date: 2026-03-30
---

# ADR-0023: API Responses Declare Runtime Context

## Status
Accepted

## Context

JustSearch endpoints behave differently based on runtime mode (eval vs production, GPU vs CPU, settings persistence mode). When behavior varies silently, consumers (frontend, agents, tests) cannot distinguish "feature disabled" from "feature broken." This caused multiple bugs discovered during the frontend-backend alignment work (tempdoc 364/368):

- **Settings persistence**: `POST /api/settings/v2` returns a 200 with merged data, but in `IN_MEMORY` mode (eval), the save is a no-op. The response is indistinguishable from a successful persist. Developers spent significant time investigating a "settings persistence bug" that was actually correct eval-mode behavior.
- **ONNX feature status**: "Search reranking: Inactive" displayed in the UI when the cross-encoder was actually executing. The status was derived from file discovery (system 2), not from the actual ORT session state (system 5). The response said "not active" for a feature that was actively running.
- **Model installation**: All assets showed "not installed" despite working models, because the SHA-256 check against the registry (system 1) disagreed with the running ORT session (system 5).

The common pattern: the API reports on a concept (settings state, model state, feature status) but the answer depends on invisible runtime context that no consumer can query. Consumers must guess why things aren't working.

## Decision

Any endpoint whose behavior varies by runtime mode must surface that mode in its response. The principle is: **the persistence/runtime layer is the authority — its state includes values AND mode.**

Implemented for:

1. **Settings endpoint** (`GET /api/settings/v2`): Added `settingsMode: "read_write" | "in_memory"` field. The POST handler returns 409 with `SETTINGS_READ_ONLY` error code when `IN_MEMORY` mode would discard the save. Frontend shows "Read-only" pill in the settings header and skips network flushes when mode is `in_memory`.

2. **ONNX feature status**: Added `modelActive: boolean` field derived from actual ORT session state (not file discovery). The ORT session — the runtime layer that actually performs inference — is the authority on whether a model is active. File discovery is demoted to an installation indicator.

This establishes a general rule for future endpoints: if runtime mode affects behavior, declare it in the response.

## Consequences

**Positive:**
- Consumers can adapt UI and behavior based on declared mode. The frontend shows "Read-only" in eval mode, disables save buttons, and avoids pointless 409 retry loops.
- Eliminates "silent behavioral switch" bugs. A developer seeing `settingsMode: "in_memory"` immediately understands why saves aren't persisting, without debugging.
- ORT session state (`modelActive`) resolves the contradiction between "installed" and "active" — consumers get a single authoritative signal.

**Negative:**
- Every mode-varying endpoint needs explicit mode declaration — more fields on responses, more contract surface to maintain.
- The principle must be applied proactively when adding new endpoints. Without enforcement, new endpoints will repeat the silent-mode pattern.
- Adding mode fields changes the API contract, requiring contract test and frontend type updates.

## Alternatives Considered

### Document modes externally
Write documentation explaining which endpoints behave differently in eval mode. Rejected because consumers must know to read the docs, and runtime mode may change dynamically (e.g., switching from production to eval requires a config change and restart, but nothing in the API signals this happened). Documentation doesn't help programmatic consumers (agents, frontend code) that need to adapt behavior at runtime.

### Use HTTP headers for mode signaling
Return `X-Settings-Mode: in_memory` as a response header instead of a body field. Rejected because response headers are lost in most frontend fetch abstractions (the app uses `fetch` → JSON parsing pipelines that discard headers). Headers are also invisible in API debugging tools and harder to include in contract tests.

### Single global mode endpoint
Add a `GET /api/runtime-mode` endpoint that returns all active modes. Consumers check this before calling other endpoints. Rejected because it doesn't associate mode with specific behavior — consumers still can't tell which specific API is affected by which mode. A settings consumer would need to know to check the global endpoint, then cross-reference which modes affect settings. Co-locating the mode with the affected response eliminates this indirection.
