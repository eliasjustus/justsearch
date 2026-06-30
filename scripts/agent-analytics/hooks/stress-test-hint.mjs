#!/usr/bin/env node

/**
 * PostToolUse hook for Edit/Write on ORT stress-test subjects.
 *
 * When an agent edits NativeSessionHandle, SessionHandle, OrtSessionAssembler,
 * OnnxSessionCache, or any @Tag("stress") test file in ort-common, surfaces a
 * reminder to run the stress-tagged Gradle tests. Without this, stress-cadence
 * is discipline-dependent (observations.md L45).
 *
 * - Synchronous (blocks until return, <50ms)
 * - No external process spawning — just path parsing
 * - Outputs hookSpecificOutput.additionalContext when matches found
 */

function normalize(p) {
  return p.replace(/\\/g, '/');
}

const STRESS_SUBJECTS = [
  'NativeSessionHandle.java',
  'SessionHandle.java',
  'OrtSessionAssembler.java',
  'OnnxSessionCache.java',
  'OrtCudaHelper.java',
];

function isStressSubject(filePath) {
  const n = normalize(filePath);
  if (!n.includes('modules/ort-common/')) return false;
  const base = n.split('/').pop();
  return STRESS_SUBJECTS.includes(base);
}

function isStressTest(filePath) {
  const n = normalize(filePath);
  if (!n.includes('modules/ort-common/')) return false;
  const base = n.split('/').pop();
  return base.includes('Stress') && base.endsWith('Test.java');
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

    if (!isStressSubject(filePath) && !isStressTest(filePath)) return;

    const hint = [
      `ORT stress-test subject edited — run the stress-tagged Gradle tests:`,
      `  ./gradlew.bat test -PincludeStress=true --tests "*Stress*"`,
      `Stress tests are opt-in. Without an explicit local run, ORT concurrency`,
      `regressions can reach main undetected (observations.md L45).`,
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
