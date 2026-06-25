---
title: Threat Model
type: reference
status: stable
description: "STRIDE threat model for the local-first, loopback-only architecture; the provable-privacy story and its mechanical anchors."
---

# Threat Model

This doc answers: *"What is JustSearch's attack surface, what is the privacy guarantee, and what
mechanically enforces it?"* It is a STRIDE-style threat model over the local-first architecture and the
basis for the README's **"nothing leaves your machine"** claim. It is an NLnet-M2 deliverable.

> **Scope.** JustSearch runs entirely on the user's machine: a desktop shell (Tauri webview), a loopback
> HTTP API (Head), a Lucene-owning Worker, and a local inference server (`llama-server`). There is no
> server-side component, no account, and no cloud processing of user documents. The guarantee here is
> **privacy** (your files and queries stay local), **not** infallibility of AI answers — always check a
> citation. See the project's `NON-GOALS` (at the repository root in the public release).

## Assets

1. **The user's documents** (indexed content, snippets, embeddings) — the primary asset; never leaves the device.
2. **Search queries and AI conversations** — reveal user intent; local-only.
3. **The local API** — the control surface for retrieval, ingest, and RAG (`modules/ui`).
4. **The model files** — integrity matters (a tampered model could degrade or mislead); see [first-run](#first-run-and-supply-chain).

## Trust boundaries

| Boundary | Description |
|---|---|
| **Webview ↔ Head** | The Tauri webview talks to the loopback HTTP API. Confined by CSP + Host/Origin/token checks. |
| **Other local processes ↔ Head** | Any process (or a malicious web page the user visits) can attempt to reach `127.0.0.1:<port>`. The key inbound boundary. |
| **Head ↔ Worker / Inference** | In-process/loopback IPC (gRPC + MMF, `llama-server` on loopback). Not network-exposed. |
| **Device ↔ Internet** | The only intended egress is the one-time model download. Everything else stays local. |

## The privacy guarantee and its mechanical anchors

The "nothing leaves your machine" claim rests on three enforced properties, each with a source-of-truth
anchor (so the claim is *checkable*, not promised):

1. **The webview cannot egress to the public internet.** The Content-Security-Policy pins
   `connect-src` to loopback only — `connect-src 'self' ipc: http://ipc.localhost http://127.0.0.1:*`,
   with `script-src 'self'` and no external `img-src`/`script-src` origins
   (`modules/shell/src-tauri/tauri.conf.json`, the `csp` field). A compromised or buggy frontend cannot
   `fetch`/`XHR`/`WebSocket` to an external host. *(Drift guard: `scripts/docs/check-privacy-claims.mjs`
   fails the build if the CSP stops pinning loopback.)*
2. **No product telemetry exporter exists.** The shipped code contains no analytics/telemetry SDK
   (no PostHog/Segment/Sentry/Mixpanel/Amplitude). The only "telemetry" in the codebase is **internal**
   Lucene/runtime metrics that are never exported off-device. **Do not confuse** this with the
   *development-time* agent-analytics OTLP sink (`scripts/agent-analytics/otlp-sink.py`) — that is a
   dev-only tool, binds `127.0.0.1:4318`, writes under `tmp/`, and is **not part of the shipped app**.
3. **The only intended egress is the one-time model download** (from GitHub Releases + Hugging Face).
   After first run, the app operates fully offline; a user can confirm with a network monitor. See
   [First-run and supply chain](#first-run-and-supply-chain).

## STRIDE analysis

### Spoofing / Tampering / Information disclosure — the inbound loopback surface ★

The most important threat is **not** outbound egress (the CSP confines that) but **inbound** access to
the loopback API by software other than the legitimate webview. "Loopback-only" means *private from the
internet*; it does **not** by itself mean *isolated from other local software*.

- **DNS rebinding.** A malicious web page the user visits can, after a short DNS TTL, re-resolve its own
  domain to `127.0.0.1` and issue requests to the local API from the user's browser. This is the dominant
  2025–2026 attack class against local services (multiple MCP-server CVEs — e.g. CVE-2026-11624,
  CVE-2026-42559; the Python SDK fix in 1.23.0; the TypeScript SDK shipping protection off by default),
  with the **Ollama DNS-rebinding CVE-2024-28224** as the exact precedent: a rebound page read and
  exfiltrated arbitrary files a local LLM server could reach.
- **Cross-process access.** Any local process can attempt to call `127.0.0.1:<port>`.

**Mitigations (defense in depth), all in `modules/ui/src/main/java/io/justsearch/ui/api/ApiSecurityFilters.java`:**

| Control | What it stops | Note |
|---|---|---|
| **Host-header allowlist** (`isAllowedHost` / `setupHostValidation`) | DNS rebinding. After rebinding the request is *same-origin* with the attacker domain, so CORS no longer applies — but the server still sees the attacker's domain in the `Host` header and returns **403**. Only loopback hosts (`127.0.0.1`/`localhost`/`::1`) are accepted. | The canonical DNS-rebinding defense; applies to **all** methods, incl. token-exempt GET reads. |
| **CORS Origin allowlist** (`resolveAllowedOrigin`) | A normal cross-origin page from reading API responses. In prod, only the desktop origins (`tauri://localhost`, `http(s)://tauri.localhost`) are allowed. | Protects response-reading; insufficient alone vs. rebinding (hence the Host check). |
| **Session token on mutations** (`setupSessionTokenEnforcement`) | A foreign caller from performing `POST`/`PUT`/`DELETE` in prod. The token is generated at startup and delivered to the UI via the Tauri bridge — a web origin cannot obtain it. | Covers `POST`/`DELETE /mcp` tool calls (`LocalApiServer.java:561-562`) and `justsearch_ingest`. |
| **Loopback bind** (Hard Invariant #2) | Remote network access entirely — the API binds `127.0.0.1`, never `0.0.0.0`. | Necessary baseline; not sufficient alone. |

Together: mutations and the MCP retrieval backend (`POST /mcp`) are token-protected; token-exempt GET
reads are protected by the Host-allowlist; remote access is barred by the loopback bind.

### Repudiation
Single-user local app; no multi-tenant identity. Out of scope — there is no shared server to repudiate to.

### Denial of service
A local process could spam the loopback API; impact is confined to the user's own machine (no remote
amplification, no shared service). Deny logging is rate-limited (`maybeRecordHostDeny`/`maybeRecordTokenDeny`)
to avoid log floods during a probe.

### Elevation of privilege
The API delegates index IO to the Worker over IPC (Hard Invariant #1 — Head never touches Lucene), so a
compromised Head cannot directly corrupt the index. The MCP tool surface follows least-privilege in
spirit: read tools (`justsearch_search`/`answer`/`browse`/`status`) vs. the privileged write tool
(`justsearch_ingest`), with mutations gated by the session token.

## First-run and supply chain

First run downloads models once. Integrity + availability considerations:

- **Integrity.** Each model package pins a `sha256` in `model-registry.v2.json`; a tampered download
  fails verification.
- **Availability / single points of failure.** Project-controlled artifacts resolve from the project's
  own releases repo. The third-party chat-model GGUF (`huggingface.co/bartowski/...`) and the
  `ggml-org/llama.cpp` binaries are external; the project mirrors the chat-model GGUF to remove the
  single point of failure (see tempdoc 633 #6). *(Drift guard: `ModelRegistryLoaderTest` asserts every
  `downloadUrl` resolves from an allowlisted public host.)*

## What this model deliberately does not claim

- **Not** that the app makes zero network connections — it downloads models on first run, and runs a
  local `llama-server`. The claim is that **your documents and queries never leave the device**.
- **Not** infallibility — AI answers can be wrong; the guarantee is privacy, not correctness.
- **Not** protection against a fully compromised host OS or a malicious local user with disk access.

## See also
- [`mcp-production-server.md`](../mcp-production-server.md) — the MCP endpoint surface.
- [`api-contract-map.md`](../api-contract-map.md) — the HTTP/gRPC contract sources.
- `ApiSecurityFilters.java` — the request-filter security plumbing (CORS / Host / token / capability gates).
