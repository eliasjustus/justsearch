# JustSearch MCPB bundle

An [MCPB (MCP Bundle)](https://github.com/anthropics/mcpb) that gives Claude Desktop
users a one-click install of JustSearch's MCP connection, and satisfies the
[Official MCP Registry](https://registry.modelcontextprotocol.io)'s packaging
requirement (an MCPB attached to a GitHub release).

## What it is (and is not)

JustSearch's real MCP server runs **in-process** inside the desktop app, as
Streamable HTTP on the loopback API port (`POST /mcp` — see
[`docs/reference/mcp-production-server.md`](../../docs/reference/mcp-production-server.md)).
MCPB hosts launch bundles over **stdio**, so this bundle is only a **thin
stdio→Streamable-HTTP bridge** (`server/index.js`):

1. On start it discovers the live API port: `JUSTSEARCH_API_PORT` env var →
   `%APPDATA%\io.justsearch.shell\runtime\api-port.txt` (the app writes its
   actual port there) → default `8080`; a candidate counts only if
   `GET /api/health` answers.
2. If no running JustSearch is found, it exits with an actionable message
   (install + launch the desktop app).
3. It also fetches the desktop session token (`GET /api/mcp/token`) and sends
   it as `X-JustSearch-Session` on every POST, so the bridge keeps working if
   the packaged app enforces the token on non-GET requests
   (`ApiSecurityFilters.setupSessionTokenEnforcement`).
4. Otherwise it forwards newline-delimited JSON-RPC from stdin to
   `http://127.0.0.1:<port>/mcp` (JSON and SSE response bodies both handled)
   and relays server messages back on stdout. If the app restarts on a
   different port mid-session, the bridge re-reads the port file,
   re-initializes the session with the cached `initialize` params, and replays
   the failed call once before giving up.

The bridge is **dependency-free** (Node builtins only) and never touches the
network beyond `127.0.0.1` — no npx fetches, no downloads, ever. That is
deliberate: JustSearch's identity is "fully offline after install", and the
bundle must not betray it.

The bundle does **not** contain JustSearch itself. The desktop app
(Windows-only) must be installed and running:
<https://github.com/eliasjustus/justsearch/releases>.

## Files

| File | Purpose |
|---|---|
| `manifest.json` | MCPB manifest (schema v0.4) — metadata, the 6 tools, `win32`-only compatibility |
| `server/index.js` | The stdio→HTTP bridge (zero dependencies) |
| `server.json` | Official MCP Registry metadata (registry schema `2025-12-11`) — **not yet published** |
| `test/smoke.mjs` | Scripted stdio MCP client: success path (initialize → tools/list → `justsearch_status`) and failure path (`--expect-unreachable`) |
| `.mcpbignore` | Keeps the packed bundle to `manifest.json` + `server/` |
| `dist/` | Build output (gitignored) |

## Build

```powershell
# from the repo root; requires Node >= 18
npx -y @anthropic-ai/mcpb pack packaging/mcpb packaging/mcpb/dist/justsearch-mcp.mcpb
Get-FileHash packaging/mcpb/dist/justsearch-mcp.mcpb -Algorithm SHA256
```

`npx` downloads the *packing tool* at build time; the produced bundle itself
has no dependencies. Record the SHA-256 — it goes into `server.json`
(`fileSha256`) and the release's `SHA256SUMS`.

Signing: `mcpb sign` exists (code-signing certificate / self-signed). We do
not sign yet — same posture as the unsigned installer; revisit together with
installer code signing.

## Test

```powershell
# Failure path (JustSearch NOT running) — expects actionable error + exit 1:
node packaging/mcpb/test/smoke.mjs --expect-unreachable

# Success path (JustSearch running):
node packaging/mcpb/test/smoke.mjs
```

Local one-click check: drag `dist/justsearch-mcp.mcpb` onto
Claude Desktop → Settings → Extensions.

## Release flow (operator)

1. Build the bundle (above) from the tagged commit.
2. Attach `justsearch-mcp.mcpb` to the GitHub release **whose installer
   actually ships the `/mcp` endpoint** (the v0.1.0 app does **not** — its
   backend predates the MCP handler; the bundle is useful only from the next
   release onward).
3. Add the bundle's SHA-256 to the release's `SHA256SUMS`.
4. Update `server.json`: `version`, the release-asset URL in
   `packages[0].identifier`, and `packages[0].fileSha256` (hash of the exact
   uploaded asset). The asset URL must contain the string `mcp` (registry
   rule) — the `justsearch-mcp.mcpb` filename satisfies it.

## Registry publish (operator — do not run without approval)

Prereqs: the release asset above is live; the repo README contains the
ownership marker `<!-- mcp-name: io.github.eliasjustus/justsearch -->`
(added on this branch); you can complete a GitHub device login as
`eliasjustus` (GitHub auth is what authorizes the `io.github.eliasjustus/*`
namespace).

```powershell
# 1. Install the publisher CLI (see modelcontextprotocol.io/registry/quickstart)
# 2. Authenticate:
mcp-publisher login github
# 3. From packaging/mcpb/ (where server.json lives):
mcp-publisher publish
# 4. Verify:
curl "https://registry.modelcontextprotocol.io/v0.1/servers?search=io.github.eliasjustus/justsearch"
```

Note the registry is in preview (breaking changes / data resets possible).
MCP clients validate `fileSha256` before installing — a wrong hash breaks
installs even though the registry itself does not check it.
