import type { TestInfo, Page } from '@playwright/test';

type BackoffReasonEntry = { reason: string; count: number; total_ms: number };

export type DeterminismBudgetV1 = {
  budget: Record<string, number>;
  usage: {
    sleep: {
      backoff: { count: number; total_ms: number; reasons: BackoffReasonEntry[] };
      fixed: { count: number; total_ms: number };
    };
    log: {
      parse_stdout_sentinel: { count: number; sentinel: string | null };
      scrape_unstructured: { count: number };
    };
    assert: { screenshot_only: { count: number } };
    wait: { unbounded: { count: number } };
  };
  violations: Array<Record<string, unknown>>;
};

export class DeterminismRecorder {
  readonly det: DeterminismBudgetV1;
  private readonly testInfo?: TestInfo;

  constructor(testInfo?: TestInfo) {
    this.testInfo = testInfo;
    this.det = {
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
          parse_stdout_sentinel: { count: 0, sentinel: null },
          scrape_unstructured: { count: 0 },
        },
        assert: { screenshot_only: { count: 0 } },
        wait: { unbounded: { count: 0 } },
      },
      violations: [],
    };
  }

  recordBackoffSleep(reason: string, ms: number) {
    if (!ms || ms <= 0) return;
    this.det.usage.sleep.backoff.count += 1;
    this.det.usage.sleep.backoff.total_ms += ms;
    const existing = this.det.usage.sleep.backoff.reasons.find((r) => r.reason === reason);
    if (existing) {
      existing.count += 1;
      existing.total_ms += ms;
    } else {
      this.det.usage.sleep.backoff.reasons.push({ reason, count: 1, total_ms: ms });
    }
  }

  recordFixedSleep(ms: number) {
    if (!ms || ms <= 0) return;
    this.det.usage.sleep.fixed.count += 1;
    this.det.usage.sleep.fixed.total_ms += ms;
  }

  async sleepBackoff(page: Page, ms: number, reason: string) {
    this.recordBackoffSleep(reason, ms);
    await page.waitForTimeout(ms);
  }

  async sleepFixed(page: Page, ms: number, reason: string) {
    // Reason is required by convention, but v1 schema does not store fixed-sleep reasons.
    void reason;
    this.recordFixedSleep(ms);
    await page.waitForTimeout(ms);
  }

  async waitUntil(
    checkFn: () => boolean | Promise<boolean>,
    {
      reason,
      timeoutMs,
      intervalMs = 100,
    }: { reason: string; timeoutMs: number; intervalMs?: number },
  ) {
    const deadline = Date.now() + timeoutMs;
    while (Date.now() < deadline) {
       
      const ok = await checkFn();
      if (ok) return true;
       
      await new Promise((r) => setTimeout(r, intervalMs));
      this.recordBackoffSleep(reason, intervalMs);
    }
    return false;
  }

  async attach(name = 'determinism-budget.json') {
    if (!this.testInfo) return;
    const body = Buffer.from(JSON.stringify(this.det, null, 2) + '\n', 'utf8');
    await this.testInfo.attach(name, { body, contentType: 'application/json' });
  }
}


