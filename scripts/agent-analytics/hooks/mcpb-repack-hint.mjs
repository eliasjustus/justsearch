#!/usr/bin/env node

/**
 * PostToolUse hook for Edit/Write on the MCPB bundle source.
 *
 * The packed bundle (packaging/mcpb/dist/justsearch-mcp.mcpb) is a committed
 * artifact whose SHA-256 is a published contract (server.json.fileSha256 + the
 * release SHA256SUMS). `mcpb pack` is nondeterministic, so editing the source
 * without re-packing leaves a STALE committed bundle whose hash still matches
 * server.json — the consistency gate can't catch that, so this hint is the
 * moment-of-relevance backstop (tempdoc 726; mirrors lockfile-hint).
 *
 * - Synchronous, no external process spawning — just a path check.
 * - Advisory: outputs hookSpecificOutput.additionalContext, never blocks.
 */

function normalize(p) {
  return p.replace(/\\/g, '/');
}

function isMcpbSource(filePath) {
  const p = normalize(filePath);
  return p.includes('packaging/mcpb/server/') || p.endsWith('packaging/mcpb/manifest.json');
}

async function main() {
  const chunks = [];
  for await (const chunk of process.stdin) {
    chunks.push(chunk);
  }
  const raw = Buffer.concat(chunks).toString('utf8');

  try {
    const input = JSON.parse(raw);
    const toolName = input.tool_name;
    const toolInput = input.tool_input;

    if (toolName !== 'Edit' && toolName !== 'Write') return;

    const filePath = toolInput?.file_path;
    if (!filePath || !isMcpbSource(filePath)) return;

    const hint = [
      `MCPB source edited — the packed bundle is now stale. Re-pack and re-hash before commit:`,
      `  npx -y @anthropic-ai/mcpb pack packaging/mcpb packaging/mcpb/dist/justsearch-mcp.mcpb`,
      `Then update packaging/mcpb/server.json "fileSha256" to the new bundle hash and re-verify:`,
      `  node scripts/ci/check-mcpb-consistency.mjs`,
      `The bundle's hash is a published contract (server.json + release SHA256SUMS); a stale`,
      `bundle whose hash still matches an un-updated server.json passes the gate but ships old`,
      `bridge code. See packaging/mcpb/README.md.`,
    ].join('\n');

    process.stdout.write(JSON.stringify({
      hookSpecificOutput: {
        hookEventName: 'PostToolUse',
        additionalContext: hint,
      },
    }));
  } catch {
    // Parse failure — no output, don't block
  }
}

main().catch(() => process.exit(0));
