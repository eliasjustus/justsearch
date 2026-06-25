---
title: "Transport Substrate (FE — single canonical apiClient)"
type: tempdocs
status: open
created: 2026-05-05
gated_on: "421 frontend framework kernel implementation completion"
---

# 426 — Transport Substrate (FE)

## Status

**PROPOSED — review after 421 ships.** Forward-looking design surfaced
by slice 3a.1.4b's live-validation pass. Captures the structural fix
that the finding's documentation (§B.I Finding 2: "pre-existing
dev-environment behavior") only named.

Sibling tempdocs from the same root-cause analysis:

- **425** — Bootstrap Substrate (declare-not-side-effect boot phase).
- **427** — Telemetry Substrate (declarative metric replication).

All three derive from the same anti-pattern: **declaration drift from
implementation**.

## Origin

Slice 3a.1.4b §B.I Finding 2:

> `resolveApiEndpoint()` returns `{ baseUrl: null }` in plain
> `npm run dev` mode without `?api_port=...` in the URL. The boot
> code's `if (endpoint.baseUrl)` short-circuits — neither catalog
> fetches. The page falls back to raw-key passthrough gracefully (per
> the documented contract), but visually the labels show raw keys.

The slice closed by treating this as "pre-existing dev-environment
behavior." That framing is technically accurate but operationally a
dodge: the FE has multiple URL conventions that disagree across
deployment modes, and the slice's own visual smoke required the
URL-override workaround. The defect class is real.

## Problem (today's shape)

The FE has at least three URL conventions for talking to the backend:

1. **Absolute via `resolveApiEndpoint`**: `${baseUrl}${path}` — used
   by `errorCatalog.ts`, my new `resourceCatalog.ts`. Depends on
   `resolveApiEndpoint()` returning a non-null `baseUrl`.
2. **Relative paths**: `/api/...` — used by other FE code. Works in
   dev via Vite proxy, in production via same-origin.
3. **The `request(baseUrl, path)` helper in `http.ts`**: takes
   `baseUrl` as a parameter, leaks it to call sites.

Each convention breaks in different deployment modes:

- Convention 1 breaks in plain `npm run dev` without `?api_port=...`
  (no Tauri runtime, no env var, `resolveApiEndpoint` returns null).
- Convention 2 works in dev via Vite proxy and in production via
  same-origin, but doesn't work in Tauri shell where the page is
  loaded from a Tauri-served origin and the backend is on a different
  port.
- Convention 3 leaks `baseUrl` to every call site and lets each site
  decide what to do with it — inviting drift.

This is **declaration drift from implementation**: the developer
declares "fetch this URL" by writing a `fetch(...)` call. The runtime
*implements* that intent only if the URL was constructed by the right
convention for the current deployment mode. The two diverge silently;
the page "looks fine" because the React app uses one pattern and a new
catalog uses another, and only the new catalog is broken.

## Correct design (theoretical)

There is exactly one **`ApiClient`** abstraction that all FE→backend
communication goes through:

```typescript
interface ApiClient {
  fetch(path: string, init?: RequestInit): Promise<Response>;
  sse(path: string, opts: SseOptions): SseStream;
  // ... possibly websocket, gRPC-web, etc. as the codebase grows
}
```

Critically:

- **`baseUrl` is a private implementation detail of the client.** No
  call site reads it. No call site constructs `${baseUrl}${path}`. No
  call site does `fetch(absoluteUrl)`.
- `path` is **always** path-only, starting with `/api/...`.
- Implementations cover deployment modes: `RelativePathApiClient`
  (browser, leverages Vite proxy in dev / same-origin in prod),
  `TauriApiClient` (Tauri shell, uses `invoke('api_port')` to find
  the backend), `MockApiClient` (tests).
- The boot kernel (tempdoc 425) constructs the right one once at app
  startup and injects it everywhere.

Layered above this: **typed domain clients** that wrap `ApiClient` with
endpoint-specific methods.

```typescript
class MessageCatalogClient {
  constructor(private api: ApiClient) {}
  async fetchCatalog(namespace: string, locale: string)
    : Promise<CatalogResponse> { ... }
}

class MetricsClient {
  constructor(private api: ApiClient) {}
  async snapshot(metricId: string): Promise<TimeseriesSnapshot> { ... }
  stream(metricId: string): SseStream { ... }
}
```

These are recipe-following exercises against the substrate — same
shape Resource catalogs follow today.

Compiler-enforced bans (lint rules):

- **Banned**: bare `fetch(...)` outside the kernel — replace with
  `apiClient.fetch(path)`.
- **Banned**: any string concatenation that could form a backend URL
  in non-kernel code.
- **Banned**: reading `import.meta.env.VITE_API_PORT` (or similar)
  outside the kernel.
- **Banned**: importing `resolveApiEndpoint` outside the kernel.

What this prevents:

- "Does this code work in dev mode?" becomes a property of the client
  (one place to verify), not per-call-site.
- The `?api_port=...` requirement disappears from the developer's
  mental model — the client knows.
- New code can't accidentally introduce a fourth URL convention.
- Switching deployment modes (e.g., adding a new shell host) becomes
  one new `ApiClient` implementation, not a per-call-site
  refactor.

Patterns in the wild: Angular's `HttpClient` (DI-injected everywhere),
Apollo Client / TanStack Query (one instance, all calls through it),
Tauri's `invoke` (single canonical RPC). Shape: one transport, every
call site uses it, transport handles environment differences.

## Concrete migration shape (theoretical)

1. Define `packages/api-client` with `ApiClient` interface and
   per-deployment implementations.
2. Migrate `errorCatalog.ts`, `resourceCatalog.ts`, and any other
   `${baseUrl}${path}` consumers to take an `ApiClient` instead of a
   `baseUrl` string.
3. Migrate the existing `request(baseUrl, path)` helper to be a method
   on `ApiClient`.
4. Migrate bare `fetch(...)` call sites incrementally.
5. Ship lint rules forbidding the alternatives once migration is
   substantially complete.

## Why this matters long-term

Each new piece of FE code making backend requests has to choose a
convention. Without one canonical choice, conventions multiply, each
breaking in a different deployment mode. The slice 3a.1.4b experience
shows this isn't abstract: my code copied an existing convention
(errorCatalog's `${baseUrl}${path}`) that happened to be incompatible
with the Vite-dev-mode visual-smoke flow. The point fix was a URL
parameter; the structural fix is making the alternatives unrepresentable.

## Out of scope (this tempdoc)

- Choice of DI mechanism (context provider / singleton / explicit
  parameter passing).
- Specific lint rule implementations.
- Implementation of the kernel itself (deferred until 421 ships).
- Migrating existing call sites (separate cohort follow-up slices).
- The Tauri-shell-specific port-discovery contract — should be one
  layer down inside `TauriApiClient`, not part of the public `ApiClient`.

## Gating

**Review after 421 frontend-framework-kernel implementation
completes.** Same rationale as tempdoc 425 — depends on the
kernel-substrate patterns 421 establishes.

## See also

- Sibling tempdocs **425** (bootstrap) and **427** (telemetry).
- Origin: slice 3a.1.4b `slices/3a-1-4b-timeseries-cohort-followup.md`
  §B.I Finding 2.
- The existing ad-hoc `http.ts` `resolveApiEndpoint` /
  `request(baseUrl, path)` is the obvious starting point for the
  refactor; the goal is to invert the API so call sites stop seeing
  `baseUrl`.
