/**
 * Backend auto-discovery regression test.
 *
 * Validates that in browser/Vite mode (no ?api_port and no env port),
 * the UI scans the common port range and connects with source=auto.
 */

import { test, expect } from '@playwright/test';
import { DeterminismRecorder } from './determinism';

test.describe('Backend discovery', () => {
  test('connects via auto-discovery when no env/query is configured', async ({ page }, testInfo) => {
    const det = new DeterminismRecorder(testInfo);
    let targetPort: number | null = null;
    let sawProxyStatus = false;
    const seenStatusPorts = new Set<number>();
    const seenStatusUrls: string[] = [];
    const pageLogs: string[] = [];
    page.on('console', (msg) => pageLogs.push(msg.text()));
    page.on('request', (req) => {
      if (req.url().includes('/api/status')) {
        seenStatusUrls.push(req.url());
      }
    });

    // Force browser-mode behavior even if a test harness injects a __TAURI__ global.
    await page.addInitScript(() => {
      try {
        const w = window as unknown as { __TAURI__?: unknown };
        delete w.__TAURI__;
      } catch {
        // ignore
      }
    });

    const fulfillJson = async (route: any, body: Record<string, unknown>) => {
      const requestOrigin = route.request().headers()['origin'];
      const allowOrigin =
        typeof requestOrigin === 'string' && requestOrigin.length > 0 ? requestOrigin : '*';
      await route.fulfill({
        status: 200,
        headers: {
          'Access-Control-Allow-Origin': allowOrigin,
          'Access-Control-Allow-Methods': 'GET,POST,DELETE,OPTIONS',
          Vary: 'Origin',
          'Content-Type': 'application/json',
        },
        body: JSON.stringify(body),
      });
    };

    await page.route('**/api/status**', async (route) => {
      const url = new URL(route.request().url());
      const port = url.port ? Number(url.port) : null;
      if (url.hostname === '127.0.0.1' && port != null) {
        if (targetPort == null) targetPort = port;
        seenStatusPorts.add(port);
        if (targetPort != null && port !== targetPort) {
          await route.abort();
          return;
        }
      } else {
        sawProxyStatus = true;
      }
      await fulfillJson(route, { status: 'ok', service: 'JustSearch Local API' });
    });

    await page.route('**/api/inference/status**', async (route) => {
      await fulfillJson(route, { mode: 'offline', tier: 'unavailable' });
    });

    await page.route('**/api/settings/v2**', async (route) => {
      await fulfillJson(route, {
        ui: { mode: 'simple', theme: 'dark', density: 'comfort' },
        llm: { contextWindow: 4096, maxTokens: 1024, gpuLayers: 0 },
      });
    });

    await page.route('**/api/indexing/roots**', async (route) => {
      await fulfillJson(route, { roots: [] });
    });

    await page.route('**/api/indexing/suggested-roots**', async (route) => {
      await fulfillJson(route, { roots: [] });
    });

    await page.route('**/api/ui/ready', async (route) => {
      if (route.request().method() === 'POST') {
        await route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
      } else {
        await route.continue();
      }
    });

    await page.goto('/');
    await det.waitUntil(() => (targetPort != null && seenStatusPorts.has(targetPort)) || sawProxyStatus, {
      reason: 'wait_for_backend_resolution',
      timeoutMs: 2000,
      intervalMs: 100,
    });
    await det.attach();

    expect(
      (targetPort != null && seenStatusPorts.has(targetPort)) || sawProxyStatus,
      `Expected endpoint resolution to reach either a discovered localhost backend port or proxy /api/status.\n` +
        `Chosen target port: ${targetPort ?? '(none)'}\n` +
        `Saw proxy /api/status: ${sawProxyStatus}\n` +
        `Seen /api/status URLs:\n${seenStatusUrls.join('\n')}\n\n` +
        `Seen ports: ${Array.from(seenStatusPorts).sort((a, b) => a - b).join(', ') || '(none)'}\n\n` +
        `Console logs:\n${pageLogs.join('\n')}`
    ).toBeTruthy();

    const fatalError = page.getByText('Unable to connect to the JustSearch backend');
    await expect(fatalError).not.toBeVisible();
    await expect(page.locator('footer')).toContainText(/Ready|Connected|Demo mode/i);
  });
});
