#!/usr/bin/env node
/**
 * Real MCP stdio server for JustSearch dev orchestration.
 *
 * Contract:
 * - docs/tempdocs/13/02-dev-runner-cli.md §5
 *
 * Notes:
 * - MCP protocol messages ONLY on stdout (stdio transport). All logs to stderr.
 * - Delegates lifecycle to scripts/dev/dev-runner.cjs.
 */

import { main } from './justsearch-dev-mcp/server.mjs';

main().catch((err) => {
  try {
    process.stderr.write(`[justsearch-dev-mcp] fatal: ${err?.stack || String(err)}\n`);
  } catch (_) {}
  process.exit(1);
});


