#!/usr/bin/env node

/**
 * PostToolUse hook for Edit on build.gradle.kts files.
 *
 * When an agent edits a build.gradle.kts file, this hook reminds the agent
 * to regenerate lockfiles if dependencies changed.
 *
 * - Synchronous (blocks until return, <50ms)
 * - No external process spawning — just a path check
 * - Outputs hookSpecificOutput.additionalContext when matches found
 */

function normalize(p) {
  return p.replace(/\\/g, '/');
}

function isBuildGradle(filePath) {
  return normalize(filePath).endsWith('build.gradle.kts');
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

    if (toolName !== 'Edit') return;

    const filePath = toolInput?.file_path;
    if (!filePath || !isBuildGradle(filePath)) return;

    const hint = [
      `build.gradle.kts edited — if you changed dependencies, regenerate lockfiles:`,
      `  ./gradlew.bat --no-configuration-cache resolveAndLockAll --write-locks`,
      `Tempdoc 637 #4 — a neighbour's resolveAndLockAll can SILENTLY revert your added`,
      `dependency (a clobber that surfaces one layer up as a baffling build error). There`,
      `is no cheap per-build check (transitive closure is opaque to a build-file diff), so`,
      `run the sound full-resolve LOCALLY before merge and assert no drift:`,
      `  ./gradlew.bat --no-configuration-cache resolveAndLockAll --write-locks && git diff --exit-code -- "**/gradle.lockfile" settings-gradle.lockfile`,
      `(The pre-merge CI lockfile gate is the backstop.) Load /lockfile for full workflow.`,
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
