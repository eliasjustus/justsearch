#!/usr/bin/env node

/**
 * PostToolUse hook for Edit on SSOT catalog files.
 *
 * When an agent edits any file under SSOT/catalogs/ or the classpath copy
 * under adapters-lucene/src/main/resources/SSOT/catalogs/, this hook warns
 * about the dual-copy sync requirement.
 *
 * - Synchronous (blocks until return, <50ms)
 * - No external process spawning — just a path check
 * - Outputs hookSpecificOutput.additionalContext when matches found
 */

function normalize(p) {
  return p.replace(/\\/g, '/');
}

const ROOT_SSOT = 'SSOT/catalogs/';
const CLASSPATH_SSOT = 'adapters-lucene/src/main/resources/SSOT/catalogs/';

function isSsotCatalog(filePath) {
  const norm = normalize(filePath);
  return norm.includes(ROOT_SSOT) || norm.includes(CLASSPATH_SSOT);
}

function isRootCopy(filePath) {
  const norm = normalize(filePath);
  return norm.includes(ROOT_SSOT) && !norm.includes(CLASSPATH_SSOT);
}

function isClasspathCopy(filePath) {
  return normalize(filePath).includes(CLASSPATH_SSOT);
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
    if (!filePath || !isSsotCatalog(filePath)) return;

    let hint;
    if (isRootCopy(filePath)) {
      hint = [
        `SSOT catalog edited (root copy).`,
        `CRITICAL: Also update the classpath copy at:`,
        `  modules/adapters-lucene/src/main/resources/SSOT/catalogs/`,
        `Without this, the field is silently dropped in packaged deployments.`,
        `Load /ssot-catalog for full checklist.`,
      ].join('\n');
    } else if (isClasspathCopy(filePath)) {
      hint = [
        `SSOT catalog edited (classpath copy).`,
        `Verify the root copy is also up to date at:`,
        `  SSOT/catalogs/`,
        `Load /ssot-catalog for full checklist.`,
      ].join('\n');
    }

    if (hint) {
      process.stdout.write(JSON.stringify({
        hookSpecificOutput: {
          hookEventName: 'PostToolUse',
          additionalContext: hint,
        },
      }));
    }
  } catch {
    // Parse failure — no output, don't block
  }
}

main().catch(() => process.exit(0));
