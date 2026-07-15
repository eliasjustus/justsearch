#!/usr/bin/env node

/**
 * PostToolUse hook for Edit/Write on the MCPB bundle source.
 *
 * The bundle is built deterministically from source (pack-mcpb.mjs); its SHA-256 is
 * a published contract recorded in server.json.fileSha256 (+ the release SHA256SUMS).
 * Editing manifest.json / server/** changes that hash, so server.json must be re-synced.
 * The check-mcpb-consistency gate rebuilds from source and FAILS on an un-synced edit,
 * so this hint is a convenience (run --sync now) rather than the sole backstop
 * (tempdoc 726; mirrors lockfile-hint).
 *
 * - Synchronous, no external process spawning -- just a path check.
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
      `MCPB source edited -- server.json.fileSha256 is now stale. Re-sync it before commit:`,
      `  node scripts/ci/pack-mcpb.mjs --sync`,
      `That re-packs deterministically from source and writes the new hash into server.json.`,
      `The check-mcpb-consistency gate rebuilds from source, so an un-synced edit FAILS the`,
      `build (the hash is a published contract: server.json + release SHA256SUMS).`,
      `See packaging/mcpb/README.md.`,
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
