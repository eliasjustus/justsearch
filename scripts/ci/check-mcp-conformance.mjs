#!/usr/bin/env node

/**
 * Tempdoc 655 — MCP protocol-conformance regression check.
 *
 * Runs the upstream `@modelcontextprotocol/conformance` suite against a LIVE `POST /mcp`
 * endpoint (the dev stack — this script does NOT manage its lifecycle) and asserts the subset
 * of scenarios that apply to a real, curated production server.
 *
 * Why only a subset: this suite is designed to validate SDK/framework transport correctness by
 * pairing with a companion reference server that implements specific fixture tools/prompts/
 * resources (e.g. `test_image_content`, `test_simple_prompt`, `test://static-text`) purely for
 * the suite's own use. A real product server has no reason to expose those — confirmed live
 * (2026-07-02): running the full 30-scenario suite against JustSearch's actual `/mcp` endpoint
 * produced 11 passes (protocol/transport-level scenarios, listed in PASSING_SCENARIOS below) and
 * 19 failures, every one of which was the suite expecting a fixture tool/prompt/resource that
 * doesn't exist and shouldn't. Building a parallel fixture-tool surface just to satisfy the
 * suite's full run is out of scope here — see tempdoc 655's "Phase 3" for the reasoning. This
 * check locks in the 11 that measure real behavior, so a future protocol-handling regression on
 * one of THOSE scenarios (session lifecycle, resource subscribe/unsubscribe, DNS-rebinding
 * protection, etc.) is caught.
 *
 * Honest limit, confirmed by reading all 30 scenario names (both PASSING_SCENARIOS and
 * EXCLUDED_FIXTURE_SCENARIOS below): none of them exercise `notifications/*_list_changed`
 * behavior. The over-declared `tools.listChanged`/`resources.listChanged` capability tempdoc 655
 * investigated (fixed separately, in `McpProtocolHandler#handleInitialize`) was NOT — and would
 * not have been — caught by this gate; an earlier version of this comment claimed otherwise. This
 * gate's coverage is real but scoped to what its 11 locked-in scenarios actually assert on.
 *
 * Prerequisites:
 *   - JustSearch dev stack running (the runner does NOT manage lifecycle) — start it first
 *     (jseval / the justsearch-dev MCP tools / `node scripts/dev/dev-runner.cjs start`).
 *   - Network access to fetch `@modelcontextprotocol/conformance` via `npx` (not vendored;
 *     dual Apache-2.0/MIT licensed, safe to depend on — no vendoring, no attribution needed for
 *     simply running it).
 *
 * Usage:
 *   node scripts/ci/check-mcp-conformance.mjs --url http://127.0.0.1:<port>/mcp
 *
 * Manual/dev-stack-driven — NOT wired into public CI (ADR-0044's fact-lane split: this needs a
 * live backend, same category as the other live-stack checks in this repo).
 */

import { spawn } from 'node:child_process';
import { parseArgs } from 'node:util';

/**
 * The 11 scenarios confirmed (2026-07-02, live run against JustSearch's real `/mcp`) to exercise
 * genuine protocol/transport behavior rather than requiring a suite-only fixture tool/prompt/
 * resource. If a future suite version renames/adds scenarios, this list intentionally does NOT
 * auto-expand — a newly-passing scenario should be added here deliberately, not silently.
 */
export const PASSING_SCENARIOS = [
  'server-initialize',
  'ping',
  'tools-list',
  'tools-call-simple-text',
  'tools-call-error',
  'resources-list',
  'resources-subscribe',
  'resources-unsubscribe',
  'prompts-list',
  'server-sse-multiple-streams',
  'dns-rebinding-protection',
];

/**
 * The 19 scenarios confirmed to require suite-only fixture tools/prompts/resources JustSearch's
 * real, curated tool surface has no reason to implement. Recorded here (not silently omitted) so
 * a future reader can see what was excluded and why, per the "no silent caps" discipline.
 */
export const EXCLUDED_FIXTURE_SCENARIOS = [
  'logging-set-level',
  'completion-complete',
  'tools-call-image',
  'tools-call-audio',
  'tools-call-embedded-resource',
  'tools-call-mixed-content',
  'tools-call-with-logging',
  'tools-call-with-progress',
  'tools-call-sampling',
  'tools-call-elicitation',
  'elicitation-sep1034-defaults',
  'elicitation-sep1330-enums',
  'resources-read-text',
  'resources-read-binary',
  'resources-templates-read',
  'prompts-get-simple',
  'prompts-get-with-args',
  'prompts-get-embedded-resource',
  'prompts-get-with-image',
];

function runConformanceSuite(url) {
  // Tempdoc 655 fix pass: verified live (2026-07-02) that `--scenario` only keeps the LAST value
  // passed — repeating the flag to select a subset silently runs just one scenario, not the
  // intersection. There is no documented multi-scenario select; the reliable approach is running
  // the full active suite (no `--scenario` filter) and filtering the parsed results ourselves to
  // just PASSING_SCENARIOS, ignoring the rest (they're expected to fail — see this file's header).
  return new Promise((resolve, reject) => {
    const args = ['--yes', '@modelcontextprotocol/conformance', 'server', '--url', url];
    const child = spawn('npx', args, { shell: true });
    let stdout = '';
    let stderr = '';
    child.stdout.on('data', (d) => {
      stdout += d.toString();
    });
    child.stderr.on('data', (d) => {
      stderr += d.toString();
    });
    child.on('error', reject);
    child.on('close', (code) => resolve({ code, stdout, stderr }));
  });
}

function parseSummary(stdout) {
  // Matches lines like: "✓ server-initialize: 1 passed, 0 failed" / "✗ prompts-get-simple: 0 passed, 1 failed"
  const lineRe = /^[✓✗]\s+([\w-]+):\s+(\d+)\s+passed,\s+(\d+)\s+failed/gm;
  const results = new Map();
  let m;
  while ((m = lineRe.exec(stdout)) !== null) {
    const [, name, passed, failed] = m;
    results.set(name, { passed: Number(passed), failed: Number(failed) });
  }
  return results;
}

async function main() {
  const { values } = parseArgs({
    options: {
      url: { type: 'string' },
    },
  });
  if (!values.url) {
    console.error('Usage: node scripts/ci/check-mcp-conformance.mjs --url http://127.0.0.1:<port>/mcp');
    console.error('(the JustSearch dev stack must already be running — this script does not start it)');
    process.exit(2);
  }
  // The url is spawned via `shell: true` (needed for npx.cmd resolution on Windows) — a strict
  // allowlist pattern here rules out shell metacharacters reaching that shell, independent of
  // Node's own DEP0190 warning about the general risk of unescaped args with shell:true.
  if (!/^https?:\/\/[a-zA-Z0-9.-]+:\d+\/[\w/-]*$/.test(values.url)) {
    console.error(`Refusing to run: --url does not look like a plain http(s) loopback URL: ${values.url}`);
    process.exit(2);
  }

  console.log(`Running the full active conformance suite against ${values.url}`);
  console.log(
    `(only the ${PASSING_SCENARIOS.length} protocol-level scenarios below are asserted on; ` +
      `${EXCLUDED_FIXTURE_SCENARIOS.length} others are expected to fail — they require ` +
      `suite-only fixture tools/prompts/resources; see this script's header)`,
  );

  // Verified live (2026-07-02): the tool's own process exit code is 1 whenever ANY scenario in
  // the run fails — including the 19 expected fixture-requiring ones — so it cannot be used as
  // the pass/fail signal here. Only the parsed per-scenario results, filtered to
  // PASSING_SCENARIOS, decide this script's outcome.
  const { stdout, stderr } = await runConformanceSuite(values.url);
  const results = parseSummary(stdout);

  const regressions = [];
  for (const scenario of PASSING_SCENARIOS) {
    const r = results.get(scenario);
    if (!r || r.failed > 0 || r.passed === 0) {
      regressions.push(scenario);
    }
  }

  if (regressions.length > 0 || results.size === 0) {
    console.error('\nMCP conformance regression(s) detected:');
    for (const s of regressions) {
      console.error(`  - ${s}: ${results.has(s) ? JSON.stringify(results.get(s)) : 'no result parsed'}`);
    }
    if (results.size === 0) {
      console.error('\n(No scenario results were parsed at all — the suite may have failed to run.)');
      console.error('--- stdout ---\n' + stdout);
      console.error('--- stderr ---\n' + stderr);
    }
    process.exit(1);
  }

  console.log(`\nAll ${PASSING_SCENARIOS.length} protocol-conformance scenarios pass.`);
}

// Robust CLI-vs-import guard (Windows-safe — avoids brittle file:// URL string comparison):
// run main() only when this file is the actual entry point Node was invoked with.
const isMainModule =
  process.argv[1] && new URL(import.meta.url).pathname.endsWith(process.argv[1].replace(/\\/g, '/').split('/').pop());
if (isMainModule) {
  main().catch((err) => {
    console.error('check-mcp-conformance failed to run:', err);
    process.exit(1);
  });
}
