#!/usr/bin/env node
/**
 * Best-effort helper: enforce local evidence retention (keep last N bundles under tmp/agent-evidence/**).
 *
 * Contract:
 * - docs/tempdocs/13/00-evidencebundle-v1-implementation.md
 * - docs/tempdocs/13/06-locked-decisions-and-next-steps.md
 *
 * Safety:
 * - Deletion logic is implemented in scripts/dev/justsearch-dev-mcp/files.mjs and is repo-confined + symlink-safe.
 */

import path from 'node:path';
import process from 'node:process';
import { fileURLToPath } from 'node:url';

import { pruneAgentEvidence } from '../dev/justsearch-dev-mcp/files.mjs';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

async function main() {
  const repoRoot = path.resolve(__dirname, '..', '..');
  const keepLastN = 20;

  const result = await pruneAgentEvidence({ repoRoot, keepLastN });
  // eslint-disable-next-line no-console
  console.log(JSON.stringify({ ok: true, policy: { keep_last_n: keepLastN, root: 'tmp/agent-evidence' }, result }));
}

main().catch((err) => {
  // eslint-disable-next-line no-console
  console.error(JSON.stringify({ ok: false, error: String(err?.message || err) }));
  process.exit(1);
});


