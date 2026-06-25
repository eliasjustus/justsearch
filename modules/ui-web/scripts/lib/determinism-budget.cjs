/**
 * Determinism Budget recorder (CommonJS).
 *
 * Canonical semantics:
 * - docs/tempdocs/13/01-determinism-budget-run-metadata.md
 * - scripts/evidence/validate-determinism-budget-v1.mjs
 */

'use strict';

function createDeterminismBudget({ stdoutSentinel } = {}) {
  return {
    budget: {
      'sleep.fixed.count': 0,
      'log.scrape_unstructured.count': 1,
      'assert.screenshot_only.count': 0,
      'wait.unbounded.count': 0,
    },
    usage: {
      sleep: {
        backoff: { count: 0, total_ms: 0, reasons: [] },
        fixed: { count: 0, total_ms: 0 },
      },
      log: {
        parse_stdout_sentinel: { count: 0, sentinel: stdoutSentinel || 'JUSTSEARCH_API_PORT=...' },
        scrape_unstructured: { count: 0 },
      },
      assert: { screenshot_only: { count: 0 } },
      wait: { unbounded: { count: 0 } },
    },
    violations: [],
  };
}

function recordBackoffSleep(det, reason, ms) {
  if (!det || !det.usage) throw new Error('determinism recorder missing');
  if (!ms || ms <= 0) return;
  det.usage.sleep.backoff.count += 1;
  det.usage.sleep.backoff.total_ms += ms;

  const arr = det.usage.sleep.backoff.reasons;
  let found = null;
  for (const r of arr) {
    if (r && r.reason === reason) {
      found = r;
      break;
    }
  }
  if (!found) {
    found = { reason, count: 0, total_ms: 0 };
    arr.push(found);
  }
  found.count += 1;
  found.total_ms += ms;
}

function recordFixedSleep(det, ms) {
  if (!det || !det.usage) throw new Error('determinism recorder missing');
  if (!ms || ms <= 0) return;
  det.usage.sleep.fixed.count += 1;
  det.usage.sleep.fixed.total_ms += ms;
}

function recordStdoutSentinelParse(det, { sentinel } = {}) {
  if (!det || !det.usage) throw new Error('determinism recorder missing');
  det.usage.log.parse_stdout_sentinel.count += 1;
  if (sentinel) det.usage.log.parse_stdout_sentinel.sentinel = String(sentinel);
}

function recordUnstructuredLogScrape(det) {
  if (!det || !det.usage) throw new Error('determinism recorder missing');
  det.usage.log.scrape_unstructured.count += 1;
}

function addViolation(det, v) {
  if (!det || !Array.isArray(det.violations)) throw new Error('determinism recorder missing');
  det.violations.push(v);
}

function enforceDeterminismBudget(det) {
  const budget = det.budget || {};
  const usage = det.usage || {};
  const violations = [];
  let hasError = false;

  const observedFor = (counter) => {
    switch (counter) {
      case 'sleep.fixed.count':
        return Number(usage?.sleep?.fixed?.count ?? 0);
      case 'log.scrape_unstructured.count':
        return Number(usage?.log?.scrape_unstructured?.count ?? 0);
      case 'assert.screenshot_only.count':
        return Number(usage?.assert?.screenshot_only?.count ?? 0);
      case 'wait.unbounded.count':
        return Number(usage?.wait?.unbounded?.count ?? 0);
      default:
        return 0;
    }
  };

  const strictCounters = [
    {
      counter: 'sleep.fixed.count',
      message: 'Fixed sleeps are disallowed; replace with condition-based waits',
      severity: 'error',
    },
    {
      counter: 'assert.screenshot_only.count',
      message: 'Screenshot-only assertions are disallowed; add an API/state assertion',
      severity: 'error',
    },
    {
      counter: 'wait.unbounded.count',
      message: 'Unbounded waits are disallowed; use bounded backoff with a deadline',
      severity: 'error',
    },
  ];

  for (const c of strictCounters) {
    const allowed = Number(budget[c.counter] ?? 0);
    const observed = observedFor(c.counter);
    if (observed > allowed) {
      violations.push({ counter: c.counter, observed, allowed, severity: c.severity, message: c.message });
      hasError = true;
    }
  }

  // Log scrape: tolerated up to budget (warn), but must be visible debt.
  const logCounter = 'log.scrape_unstructured.count';
  const logAllowed = Number(budget[logCounter] ?? 0);
  const logObserved = observedFor(logCounter);
  if (logObserved > 0) {
    const severity = logObserved > logAllowed ? 'error' : 'warn';
    const followups = [
      {
        type: 'doc',
        href: 'docs/tempdocs/13/01-determinism-budget-run-metadata.md',
        title: 'Determinism Budget: replace log scraping with state/API signals',
      },
    ];
    violations.push({
      counter: logCounter,
      observed: logObserved,
      allowed: logAllowed,
      severity,
      message: 'Unstructured log scraping is temporary debt; replace with an API/state signal',
      followups,
    });
    if (severity === 'error') hasError = true;
  }

  // Replace violations array (stable, deterministic).
  det.violations = violations;
  return { hasError, violations };
}

function mergeDeterminismBudgets(into, other) {
  if (!into || !into.budget || !into.usage) throw new Error('merge target must be a determinism budget object');
  if (!other || !other.budget || !other.usage) throw new Error('merge source must be a determinism budget object');

  // Merge budgets: never raise allowances; choose strictest (min) per key.
  for (const [k, v] of Object.entries(other.budget || {})) {
    const n = Number(v);
    if (!Number.isFinite(n)) continue;
    const cur = Number(into.budget[k]);
    if (!Number.isFinite(cur)) into.budget[k] = n;
    else into.budget[k] = Math.min(cur, n);
  }

  const addNum = (a, b) => {
    const x = Number(a ?? 0);
    const y = Number(b ?? 0);
    const ax = Number.isFinite(x) ? x : 0;
    const by = Number.isFinite(y) ? y : 0;
    return ax + by;
  };

  // sleep.fixed
  into.usage.sleep = into.usage.sleep || { backoff: { count: 0, total_ms: 0, reasons: [] }, fixed: { count: 0, total_ms: 0 } };
  into.usage.sleep.fixed = into.usage.sleep.fixed || { count: 0, total_ms: 0 };
  into.usage.sleep.fixed.count = addNum(into.usage.sleep.fixed.count, other.usage?.sleep?.fixed?.count);
  into.usage.sleep.fixed.total_ms = addNum(into.usage.sleep.fixed.total_ms, other.usage?.sleep?.fixed?.total_ms);

  // sleep.backoff
  into.usage.sleep.backoff = into.usage.sleep.backoff || { count: 0, total_ms: 0, reasons: [] };
  into.usage.sleep.backoff.count = addNum(into.usage.sleep.backoff.count, other.usage?.sleep?.backoff?.count);
  into.usage.sleep.backoff.total_ms = addNum(into.usage.sleep.backoff.total_ms, other.usage?.sleep?.backoff?.total_ms);

  const intoReasons = Array.isArray(into.usage.sleep.backoff.reasons) ? into.usage.sleep.backoff.reasons : [];
  const otherReasons = Array.isArray(other.usage?.sleep?.backoff?.reasons) ? other.usage.sleep.backoff.reasons : [];
  const map = new Map();
  for (const r of intoReasons) {
    if (!r || typeof r.reason !== 'string') continue;
    map.set(r.reason, { reason: r.reason, count: addNum(0, r.count), total_ms: addNum(0, r.total_ms) });
  }
  for (const r of otherReasons) {
    if (!r || typeof r.reason !== 'string') continue;
    const existing = map.get(r.reason) || { reason: r.reason, count: 0, total_ms: 0 };
    existing.count = addNum(existing.count, r.count);
    existing.total_ms = addNum(existing.total_ms, r.total_ms);
    map.set(r.reason, existing);
  }
  into.usage.sleep.backoff.reasons = Array.from(map.values()).sort((a, b) => a.reason.localeCompare(b.reason));

  // log
  into.usage.log = into.usage.log || { parse_stdout_sentinel: { count: 0, sentinel: null }, scrape_unstructured: { count: 0 } };
  into.usage.log.parse_stdout_sentinel = into.usage.log.parse_stdout_sentinel || { count: 0, sentinel: null };
  into.usage.log.scrape_unstructured = into.usage.log.scrape_unstructured || { count: 0 };
  into.usage.log.parse_stdout_sentinel.count = addNum(
    into.usage.log.parse_stdout_sentinel.count,
    other.usage?.log?.parse_stdout_sentinel?.count,
  );
  if (!into.usage.log.parse_stdout_sentinel.sentinel && other.usage?.log?.parse_stdout_sentinel?.sentinel) {
    into.usage.log.parse_stdout_sentinel.sentinel = other.usage.log.parse_stdout_sentinel.sentinel;
  }
  into.usage.log.scrape_unstructured.count = addNum(into.usage.log.scrape_unstructured.count, other.usage?.log?.scrape_unstructured?.count);

  // assert / wait
  into.usage.assert = into.usage.assert || { screenshot_only: { count: 0 } };
  into.usage.wait = into.usage.wait || { unbounded: { count: 0 } };
  into.usage.assert.screenshot_only = into.usage.assert.screenshot_only || { count: 0 };
  into.usage.wait.unbounded = into.usage.wait.unbounded || { count: 0 };
  into.usage.assert.screenshot_only.count = addNum(into.usage.assert.screenshot_only.count, other.usage?.assert?.screenshot_only?.count);
  into.usage.wait.unbounded.count = addNum(into.usage.wait.unbounded.count, other.usage?.wait?.unbounded?.count);

  // violations: we intentionally do NOT merge arbitrary violation entries; callers should call enforceDeterminismBudget()
  // once after merging to regenerate canonical, deterministic violations.
  into.violations = [];
}

async function sleepBackoff(det, ms, reason) {
  recordBackoffSleep(det, reason, ms);
  await new Promise((r) => setTimeout(r, ms));
}

async function sleepFixed(det, ms) {
  recordFixedSleep(det, ms);
  await new Promise((r) => setTimeout(r, ms));
}

async function waitUntil(checkFn, { det, reason, deadlineMs, intervalMs }) {
  if (!Number.isFinite(deadlineMs) || deadlineMs <= 0) throw new Error(`Invalid deadlineMs: ${deadlineMs}`);
  if (!Number.isFinite(intervalMs) || intervalMs <= 0) throw new Error(`Invalid intervalMs: ${intervalMs}`);
  const deadline = Date.now() + deadlineMs;
  while (Date.now() < deadline) {
     
    const ok = await checkFn();
    if (ok) return true;
     
    await sleepBackoff(det, intervalMs, reason);
  }
  return false;
}

module.exports = {
  createDeterminismBudget,
  recordBackoffSleep,
  recordFixedSleep,
  recordStdoutSentinelParse,
  recordUnstructuredLogScrape,
  addViolation,
  mergeDeterminismBudgets,
  enforceDeterminismBudget,
  sleepBackoff,
  sleepFixed,
  waitUntil,
};


