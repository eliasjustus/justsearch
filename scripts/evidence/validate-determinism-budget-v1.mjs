#!/usr/bin/env node
/**
 * Determinism Budget v1 validator (policy enforcement for run-metadata.json.determinism_budget).
 *
 * Contract:
 * - docs/tempdocs/13/01-determinism-budget-run-metadata.md
 *
 * Usage:
 *   node scripts/evidence/validate-determinism-budget-v1.mjs <bundleDir>
 *
 * Options:
 *   --strict-reasons           Default: true. Enforce allowlisted reason strings for backoff sleeps.
 *   --allow-reason=<reason>    Repeatable. Adds an allowed reason (escape hatch for new reasons).
 */

import fs from 'node:fs';
import fsp from 'node:fs/promises';
import path from 'node:path';
import process from 'node:process';

function printUsageAndExit(code = 1) {
  // eslint-disable-next-line no-console
  console.error(
    [
      'Usage: node scripts/evidence/validate-determinism-budget-v1.mjs <bundleDir> [options]',
      '',
      'Options:',
      '  --strict-reasons           Default: true. Enforce allowlisted reason strings for backoff sleeps.',
      '  --allow-reason=<reason>    Repeatable. Adds an allowed reason (escape hatch for new reasons).',
      '  -h, --help',
    ].join('\n'),
  );
  process.exit(code);
}

function fail(errors) {
  // eslint-disable-next-line no-console
  console.error(['Determinism Budget validation FAILED:', ...errors.map((e) => `- ${e}`)].join('\n'));
  process.exit(1);
}

function asNumberOrZero(v) {
  const n = Number(v);
  if (!Number.isFinite(n)) return 0;
  return n;
}

function get(obj, dottedPath) {
  const parts = dottedPath.split('.');
  let cur = obj;
  for (const p of parts) {
    if (cur == null || typeof cur !== 'object') return undefined;
    cur = cur[p];
  }
  return cur;
}

function normalizeStrictReasonsFlag(v) {
  // Default strict unless explicitly set to false.
  if (v == null) return true;
  const s = String(v).trim().toLowerCase();
  if (s === '0' || s === 'false' || s === 'no' || s === 'off') return false;
  if (s === '1' || s === 'true' || s === 'yes' || s === 'on' || s === '') return true;
  // Unknown -> strict to avoid surprise passes.
  return true;
}

function parseArgs(argv) {
  const out = {
    bundleDir: null,
    strictReasons: true,
    allowReasons: new Set(),
  };

  const args = [...argv];
  for (let i = 0; i < args.length; i += 1) {
    const token = args[i];
    if (token === '-h' || token === '--help') printUsageAndExit(0);
    if (!token.startsWith('--')) {
      if (!out.bundleDir) out.bundleDir = token;
      else throw new Error(`Unexpected extra arg: ${token}`);
      continue;
    }

    if (token === '--strict-reasons') {
      out.strictReasons = true;
      continue;
    }
    if (token.startsWith('--strict-reasons=')) {
      out.strictReasons = normalizeStrictReasonsFlag(token.split('=', 2)[1]);
      continue;
    }
    if (token.startsWith('--allow-reason=')) {
      const reason = token.split('=', 2)[1];
      if (reason) out.allowReasons.add(reason);
      continue;
    }

    throw new Error(`Unknown option: ${token}`);
  }

  return out;
}

function assertObject(v, context, errors) {
  if (v == null || typeof v !== 'object' || Array.isArray(v)) {
    errors.push(`Expected object at ${context}`);
    return false;
  }
  return true;
}

function assertArray(v, context, errors) {
  if (!Array.isArray(v)) {
    errors.push(`Expected array at ${context}`);
    return false;
  }
  return true;
}

async function main() {
  const opts = parseArgs(process.argv.slice(2));
  if (!opts.bundleDir) printUsageAndExit(2);

  const bundleDir = path.resolve(process.cwd(), opts.bundleDir);
  const runMetaPath = path.join(bundleDir, 'run-metadata.json');
  const errors = [];

  if (!fs.existsSync(bundleDir) || !fs.statSync(bundleDir).isDirectory()) {
    fail([`Bundle dir does not exist or is not a directory: ${bundleDir}`]);
  }
  if (!fs.existsSync(runMetaPath) || !fs.statSync(runMetaPath).isFile()) {
    fail([`Missing required file: run-metadata.json`]);
  }

  let meta = null;
  try {
    meta = JSON.parse(await fsp.readFile(runMetaPath, 'utf8'));
  } catch (err) {
    fail([`Invalid JSON (run-metadata.json): ${err?.message || String(err)}`]);
  }

  const det = meta?.determinism_budget;
  if (!assertObject(det, 'run-metadata.json.determinism_budget', errors)) fail(errors);

  const budget = det.budget;
  const usage = det.usage;
  const violations = det.violations;

  if (!assertObject(budget, 'determinism_budget.budget', errors)) fail(errors);
  if (!assertObject(usage, 'determinism_budget.usage', errors)) fail(errors);
  if (!assertArray(violations, 'determinism_budget.violations', errors)) fail(errors);

  // Required enforced budget keys (v1).
  const requiredBudgetKeys = [
    'sleep.fixed.count',
    'log.scrape_unstructured.count',
    'assert.screenshot_only.count',
    'wait.unbounded.count',
  ];
  for (const k of requiredBudgetKeys) {
    if (!(k in budget)) errors.push(`Missing determinism_budget.budget['${k}']`);
  }

  // Reasons allowlist (v1 starter set).
  const reasonAllowlist = new Set([
    'wait_for_port_sentinel',
    'wait_for_backend_ready',
    'wait_for_ui_ready',
    'wait_for_transition',
    'wait_for_searchable',
    'ui_screenshot_cooldown',
    'ui_screenshot_retry_backoff',
    'viewport_resize_cooldown',
    'viewport_restore_cooldown',
  ]);
  for (const r of opts.allowReasons) reasonAllowlist.add(r);

  if (opts.strictReasons) {
    const reasons = get(usage, 'sleep.backoff.reasons');
    if (reasons != null) {
      if (assertArray(reasons, 'determinism_budget.usage.sleep.backoff.reasons', errors)) {
        for (const entry of reasons) {
          const reason = entry?.reason;
          if (typeof reason !== 'string' || !reason.trim()) {
            errors.push('determinism_budget.usage.sleep.backoff.reasons[] entry missing reason');
            continue;
          }
          if (!reasonAllowlist.has(reason)) {
            errors.push(
              `Unknown determinism backoff reason '${reason}'. Add to docs or pass --allow-reason=${reason}`,
            );
          }
        }
      }
    }
  }

  // Budget compliance checks (enforced counters).
  const checks = [
    {
      counter: 'sleep.fixed.count',
      allowed: asNumberOrZero(budget['sleep.fixed.count']),
      observed: asNumberOrZero(get(usage, 'sleep.fixed.count')),
    },
    {
      counter: 'log.scrape_unstructured.count',
      allowed: asNumberOrZero(budget['log.scrape_unstructured.count']),
      observed: asNumberOrZero(get(usage, 'log.scrape_unstructured.count')),
    },
    {
      counter: 'assert.screenshot_only.count',
      allowed: asNumberOrZero(budget['assert.screenshot_only.count']),
      observed: asNumberOrZero(get(usage, 'assert.screenshot_only.count')),
    },
    {
      counter: 'wait.unbounded.count',
      allowed: asNumberOrZero(budget['wait.unbounded.count']),
      observed: asNumberOrZero(get(usage, 'wait.unbounded.count')),
    },
  ];

  for (const c of checks) {
    if (c.observed > c.allowed) {
      errors.push(`Over budget: ${c.counter}: observed=${c.observed} allowed=${c.allowed}`);
    }
  }

  // Log-scrape followups rule (v1): if used, must have at least one violation entry with followups[].
  const scrapeObserved = asNumberOrZero(get(usage, 'log.scrape_unstructured.count'));
  if (scrapeObserved > 0) {
    const hasViolationWithFollowups = violations.some((v) => {
      const counter = v?.counter;
      const followups = v?.followups;
      return counter === 'log.scrape_unstructured.count' && Array.isArray(followups) && followups.length > 0;
    });
    if (!hasViolationWithFollowups) {
      errors.push(
        'log.scrape_unstructured.count observed > 0 but no violations[] entry found with followups[] (required)',
      );
    }
  }

  // If any explicit violation is marked severity=error, fail.
  for (const v of violations) {
    if (String(v?.severity || '').toLowerCase() === 'error') {
      errors.push(`determinism_budget.violation severity=error: ${v?.counter || '(unknown counter)'}: ${v?.message || ''}`);
    }
  }

  if (errors.length > 0) fail(errors);

  // eslint-disable-next-line no-console
  console.log('Determinism Budget validation OK:', bundleDir);
}

main().catch((err) => {
  // eslint-disable-next-line no-console
  console.error('Determinism validator crashed:', err);
  process.exit(2);
});


