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
| `mcp-typed-confirm.mjs` | Thin Node driver: spawns `node index.js` as a child process, speaks newline-delimited JSON-RPC to its stdin/stdout, and drives `initialize` -> `notifications/initialized` -> `tools/call justsearch_ingest`. |

**Why Node, not PowerShell:** an earlier PowerShell driver hung waiting on
the `tools/call` response and has been removed. The sandbox already requires Node (the MCP Inspector CLI needs
`npx`), so a Node driver spawning the Node bridge is the natural, proven
shape — Node's async stream handling matches how the bridge itself streams
JSON-RPC frames, where PowerShell's synchronous `StandardOutput.ReadLine()` /
stderr handling did not.

## Usage

```powershell
node --version   # confirm Node is on PATH (same requirement as the existing
                  # MCP Inspector CLI check in collect-evidence.ps1)
node .\mcp-client\mcp-typed-confirm.mjs --target "C:\Users\WDAGUtilityAccount\Desktop\JustSearchTest\scifact"
```

Optional flags: `--port <n>` (sets `JUSTSEARCH_API_PORT` for the bridge; by
default the bridge discovers the port itself), `--bridge <path>` (default:
`index.js` next to this script), `--timeout <seconds>` (default 45),
`--out <file>` (write the raw JSON-RPC frame transcript for evidence).

Read the printed `tools/call justsearch_ingest` result and the trailing
PASS/FAIL line. Per the published TYPED_CONFIRM trust-gate claim
(`docs/reference/mcp-production-server.md` §Trust Model), the response must
indicate a **PENDING authorization** (`isError:true`, "requires your
approval (gate: TYPED_CONFIRM)"), not a silent/immediate ingest — the script
distinguishes these explicitly rather than treating any response as a pass,
since a silent ingest is the actual bug this check exists to catch. Resolve
the pending authorization:

```powershell
Invoke-RestMethod -Method Post -Uri "http://127.0.0.1:<port>/api/authorizations/approve" `
  -ContentType "application/json" -Body (@{ authorizationId = "<id from the PENDING response>" } | ConvertTo-Json)
```

Then re-check `/api/knowledge/status` (or run the driver again with the same
target) to confirm the ingest actually proceeded after approval.
`tools/list` reachability alone does not exercise this claim — this is a
separate, mandatory step.

## Status

**Live-verified 2026-07-15** against a running dev stack, in three
independently isolated layers:

1. **Server**: fixed and conformant — `notifications/initialized` now
   returns `202 Accepted` with an empty body (JSON-RPC 2.0 §4.1 forbids
   replying to a Notification); an unknown *request* still correctly
   returns `-32601`. Verified by curl.
2. **The shipped MCPB bridge** (`index.js`), driven over stdio from a Node
   driver using this script's exact message sequence: **works end-to-end**
   — `initialize` succeeds, then `tools/call justsearch_ingest` with a real
   array `paths` returns the TYPED_CONFIRM gate response
   (`isError:true`, "requires your approval (gate: TYPED_CONFIRM)"). It
   also handles the 202-no-body notification response correctly.
3. **The PowerShell predecessor hung** — it never received the `tools/call`
   response, both before and after the server fix. The server and bridge
   are exonerated; the PowerShell driver itself was the defect.

The server, the shipped bridge, and this driver's message sequence are
live-verified. The next Sandbox round must still execute
`mcp-typed-confirm.mjs` itself end-to-end (including the post-approval
re-check) and report the result.
