# Sandbox MCP TYPED_CONFIRM Client Harness

Staged into the sandbox at `<mapped folder>\mcp-client\` by `sandbox-launch.py`
alongside `collect-evidence.ps1` and `gui\`. Exists to make the `cohort:mcp`
mutating-tool procedure (`justsearch_ingest` via TYPED_CONFIRM) actually
followable — the MCP Inspector CLI's `--tool-arg` string-coerces every value
and cannot express `paths: string[]`, so `tools/call justsearch_ingest` cannot
be driven through it (verified against a real round).

## What's staged

| File | What it is |
|---|---|
| `index.js` | Verbatim copy of `packaging/mcpb/server/index.js` — the REAL shipped MCPB stdio bridge. Not a second bespoke client: this is the artifact JustSearch actually ships. |
| `mcp-typed-confirm.ps1` | Thin driver: spawns `node index.js` as a child process, speaks newline-delimited JSON-RPC to its stdin/stdout, and drives `initialize` -> `notifications/initialized` -> `tools/call justsearch_ingest`. |

## Usage

```powershell
node --version   # confirm Node is on PATH (same requirement as the existing
                  # MCP Inspector CLI check in collect-evidence.ps1)
.\mcp-client\mcp-typed-confirm.ps1 -TargetPath "C:\Users\WDAGUtilityAccount\Desktop\JustSearchTest\scifact"
```

Read the printed `tools/call justsearch_ingest` result. Per the published
TYPED_CONFIRM trust-gate claim (`docs/reference/mcp-production-server.md`
§Trust Model), the response must indicate a **PENDING authorization**, not a
silent/immediate ingest. Resolve it:

```powershell
Invoke-RestMethod -Method Post -Uri "http://127.0.0.1:<port>/api/authorizations/approve" `
  -ContentType "application/json" -Body (@{ authorizationId = "<id from the PENDING response>" } | ConvertTo-Json)
```

Then re-check `/api/knowledge/status` (or run the driver again with the same
target) to confirm the ingest actually proceeded after approval.
`tools/list` reachability alone does not exercise this claim — this is a
separate, mandatory step.

## Status

**Unverified against a live `/mcp` as of authoring.** `index.js` is a proven
shipped artifact and the message sequence mirrors a hand-rolled client that
DID work in a real round (`mcp-call.js`), but `mcp-typed-confirm.ps1` itself
has not yet been run end-to-end against a running JustSearch instance. The
next Sandbox round must verify it and report the result.
