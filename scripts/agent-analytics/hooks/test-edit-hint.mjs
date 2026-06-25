#!/usr/bin/env node

/**
 * PostToolUse hook for Edit/Write on test files.
 *
 * When an agent edits a Java or TypeScript test file, surfaces the exact
 * `--tests` / `vitest` filter that re-runs ONLY that test class. The
 * recurring defect class this targets: an agent edits a test file
 * (changes an assertion, adds a case) and commits without re-running
 * the test, ship-testing only at the next full build. Caught the
 * `ConfigWiringTest` class-mismatch defect during the 2026-05-08
 * inbox-triage session — observations.md item #5.
 *
 * - Synchronous (blocks until return, <50ms)
 * - No external process spawning — just path parsing
 * - Outputs hookSpecificOutput.additionalContext when matches found
 */

function normalize(p) {
  return p.replace(/\\/g, '/');
}

function isJavaTest(filePath) {
  const n = normalize(filePath);
  if (!n.endsWith('.java')) return false;
  // Match files under any test sourceset (test, integrationTest, systemTest,
  // testFixtures) — these are the standard JUnit-discovered locations.
  if (
    !n.includes('/src/test/') &&
    !n.includes('/src/integrationTest/') &&
    !n.includes('/src/systemTest/') &&
    !n.includes('/src/testFixtures/')
  ) {
    return false;
  }
  // Exclude support classes (helpers, fakes, fixtures) — only suggest a
  // re-run for files whose basename ends with `Test`. JUnit's discovery
  // matches the same convention.
  const base = n.split('/').pop();
  return base.replace(/\.java$/, '').endsWith('Test');
}

function isTsTest(filePath) {
  const n = normalize(filePath);
  return n.endsWith('.test.ts') || n.endsWith('.test.tsx');
}

function javaSuggestion(filePath) {
  const n = normalize(filePath);
  // Path shape: modules/<module>/src/<sourceSet>/java/<package-path>/<ClassName>.java
  const m = n.match(/modules\/([^/]+)\/src\/(test|integrationTest|systemTest|testFixtures)\//);
  if (!m) return null;
  const module = m[1];
  const sourceSet = m[2];
  const className = n.split('/').pop().replace(/\.java$/, '');
  // testFixtures isn't a runnable JUnit set — skip the suggestion.
  if (sourceSet === 'testFixtures') return null;
  const taskName = sourceSet === 'integrationTest'
    ? 'integrationTest'
    : sourceSet === 'systemTest'
      ? 'systemTest'
      : 'test';
  return [
    `Java test edited — re-run before commit:`,
    `  ./gradlew.bat :modules:${module}:${taskName} --tests "*${className}*"`,
    `Pre-commit run catches the assertEquals-class-mismatch defect class`,
    `(see observations.md item #5 / ConfigWiringTest 2026-05-08).`,
  ].join('\n');
}

function tsSuggestion(filePath) {
  const n = normalize(filePath);
  // Vitest accepts file paths or filter substrings. The path filter is the
  // most precise — strip everything before `modules/ui-web/` so the
  // working-directory contract (cd modules/ui-web && npm run test:unit:run)
  // sees a relative path.
  const idx = n.indexOf('modules/ui-web/');
  const rel = idx >= 0 ? n.slice(idx + 'modules/ui-web/'.length) : n;
  return [
    `TS test edited — re-run before commit:`,
    `  cd modules/ui-web && npm run test:unit:run -- ${rel}`,
    `Pre-commit run catches the unrun-test defect class`,
    `(see observations.md item #5).`,
  ].join('\n');
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
    if (!filePath) return;

    let hint = null;
    if (isJavaTest(filePath)) {
      hint = javaSuggestion(filePath);
    } else if (isTsTest(filePath)) {
      hint = tsSuggestion(filePath);
    }
    if (!hint) return;

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
