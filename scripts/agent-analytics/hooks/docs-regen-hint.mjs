#!/usr/bin/env node

/**
 * PostToolUse hook for Edit/Write on canonical doc files.
 *
 * When an agent edits a file in docs/explanation/, docs/reference/,
 * docs/how-to/, or docs/decisions/, this hook reminds the agent to
 * run the post-edit regeneration sequence.
 *
 * - Synchronous (blocks until return, <50ms)
 * - No external process spawning — just a path check
 * - Outputs hookSpecificOutput.additionalContext when matches found
 */

function normalize(p) {
  return p.replace(/\\/g, '/');
}

const CANONICAL_PREFIXES = [
  'docs/explanation/',
  'docs/reference/',
  'docs/how-to/',
  'docs/decisions/',
];

function isCanonicalDoc(filePath) {
  const norm = normalize(filePath);
  return CANONICAL_PREFIXES.some(prefix => norm.includes(prefix)) &&
    norm.endsWith('.md');
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
    if (!filePath || !isCanonicalDoc(filePath)) return;

    const shortPath = normalize(filePath).split('docs/')[1] || filePath;

    const hint = [
      `Canonical doc edited: docs/${shortPath}`,
      `Run regeneration before committing:`,
      `  node scripts/docs/llmstxt-generate.mjs`,
      `  node scripts/docs/skills-sync.mjs`,
      `Load /docs-maintenance for full checklist.`,
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
