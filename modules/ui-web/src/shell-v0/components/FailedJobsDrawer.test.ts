// @vitest-environment happy-dom
//
// Tempdoc 599 §16/B1 — the per-folder "failed files" drill-down drawer. A real FAILED indexing job
// could not be manufactured live (read-deny / zero-byte files still extracted cleanly), so this drives
// the drawer's load → render → retry path deterministically: the chip→drawer UI is covered here even
// though the live chip could not be reproduced. Mirrors RetrospectivePanel.test.ts (same right-drawer
// TransientController pattern).

import { afterEach, describe, expect, it } from 'vitest';
import './FailedJobsDrawer.js';
import type { FailedJobsDrawer } from './FailedJobsDrawer.js';
import {
  openFailedJobs,
  closeFailedJobs,
  isFailedJobsOpen,
  failedJobsFolderPathHash,
  __resetFailedJobsDrawer,
} from '../state/failedJobsDrawer.js';

afterEach(() => {
  __resetFailedJobsDrawer();
});

async function settle(el: Element): Promise<void> {
  await (el as unknown as { updateComplete: Promise<unknown> }).updateComplete;
}

/** Drive the element's open + load path, pumping microtasks so the async refresh() settles. */
async function pump(el: Element): Promise<void> {
  for (let i = 0; i < 30; i += 1) {
    await Promise.resolve();
    await settle(el);
  }
}

describe('failedJobsDrawer store', () => {
  it('opens with a folder pathHash and closes', () => {
    expect(isFailedJobsOpen()).toBe(false);
    expect(failedJobsFolderPathHash()).toBeNull();
    openFailedJobs('abc123');
    expect(isFailedJobsOpen()).toBe(true);
    expect(failedJobsFolderPathHash()).toBe('abc123');
    closeFailedJobs();
    expect(isFailedJobsOpen()).toBe(false);
  });
});

describe('FailedJobsDrawer', () => {
  it('loads the folder-scoped failed jobs and renders one row per file with its error + Retry', async () => {
    const origFetch = globalThis.fetch;
    globalThis.fetch = (async (url: unknown) => {
      const u = String(url);
      if (u.includes('/api/indexing-jobs/failed/by-prefix')) {
        return new Response(
          JSON.stringify({
            jobs: [
              { pathHash: 'h-one', errorMessage: 'parse error: unexpected EOF' },
              { pathHash: 'h-two', errorMessage: 'extraction timed out' },
            ],
          }),
          { status: 200, headers: { 'Content-Type': 'application/json' } },
        );
      }
      return new Response('{}', { status: 200, headers: { 'Content-Type': 'application/json' } });
    }) as typeof fetch;
    try {
      const el = document.createElement('jf-failed-jobs-drawer') as FailedJobsDrawer;
      el.apiBase = 'http://x';
      document.body.appendChild(el);
      await settle(el);
      // Drive the REAL open path (subscribe → refresh fetch), not a direct `el.open = true`.
      openFailedJobs('folder-hash');
      await pump(el);

      const rows = el.shadowRoot?.querySelectorAll('.row') ?? [];
      expect(rows.length).toBe(2);
      const text = (el.shadowRoot?.textContent ?? '').replace(/\s+/g, ' ');
      expect(text).toContain('parse error: unexpected EOF');
      expect(text).toContain('extraction timed out');
      expect(text).toContain('Retry all'); // the header batch affordance appears when rows>0
      // Tempdoc 599 §16.1 Move 1 / §17.2 — per-row actions REUSE the shared <jf-row-actions> over the
      // failed-jobs Resource (not a hand-rolled button), keyed by the row's pathHash.
      const rowActions = el.shadowRoot?.querySelectorAll('jf-row-actions') ?? [];
      expect(rowActions.length).toBe(2);
      expect(rowActions[0]?.getAttribute('resource-id')).toBe('core.failed-indexing-jobs');
      expect(rowActions[0]?.getAttribute('row-key')).toBe('h-one');
      el.remove();
    } finally {
      globalThis.fetch = origFetch;
    }
  });

  it('shows the empty state when the folder has no failed files', async () => {
    const origFetch = globalThis.fetch;
    globalThis.fetch = (async (url: unknown) => {
      if (String(url).includes('/api/indexing-jobs/failed/by-prefix')) {
        return new Response(JSON.stringify({ jobs: [] }), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        });
      }
      return new Response('{}', { status: 200, headers: { 'Content-Type': 'application/json' } });
    }) as typeof fetch;
    try {
      const el = document.createElement('jf-failed-jobs-drawer') as FailedJobsDrawer;
      el.apiBase = 'http://x';
      document.body.appendChild(el);
      await settle(el);
      openFailedJobs('empty-folder');
      await pump(el);

      const text = (el.shadowRoot?.textContent ?? '').replace(/\s+/g, ' ');
      expect(text).toContain('No failed files in this folder.');
      expect(text).not.toContain('Retry all');
      el.remove();
    } finally {
      globalThis.fetch = origFetch;
    }
  });

  it('a per-row action success (from <jf-row-actions>) drops that row from the list', async () => {
    const origFetch = globalThis.fetch;
    globalThis.fetch = (async (url: unknown) => {
      if (String(url).includes('/api/indexing-jobs/failed/by-prefix')) {
        return new Response(
          JSON.stringify({
            jobs: [
              { pathHash: 'h-one', errorMessage: 'boom' },
              { pathHash: 'h-two', errorMessage: 'bang' },
            ],
          }),
          { status: 200, headers: { 'Content-Type': 'application/json' } },
        );
      }
      return new Response('{}', { status: 200, headers: { 'Content-Type': 'application/json' } });
    }) as typeof fetch;
    try {
      const el = document.createElement('jf-failed-jobs-drawer') as FailedJobsDrawer;
      el.apiBase = 'http://x';
      document.body.appendChild(el);
      await settle(el);
      openFailedJobs('folder-hash');
      await pump(el);
      expect(el.shadowRoot?.querySelectorAll('.row').length).toBe(2);

      // <jf-row-actions> emits a composed `row-action-success` with the rowKey after a successful
      // Operation; the drawer drops that row. Emit it for h-one from the mounted row-actions element.
      const ra = el.shadowRoot?.querySelector('jf-row-actions[row-key="h-one"]');
      ra?.dispatchEvent(
        new CustomEvent('row-action-success', {
          detail: { operationId: 'core.retry-indexing-job', rowKey: 'h-one' },
          bubbles: true,
          composed: true,
        }),
      );
      await pump(el);

      const remaining = el.shadowRoot?.querySelectorAll('jf-row-actions') ?? [];
      expect(remaining.length).toBe(1);
      expect(remaining[0]?.getAttribute('row-key')).toBe('h-two');
      el.remove();
    } finally {
      globalThis.fetch = origFetch;
    }
  });

  it('"Retry all" invokes core.retry-indexing-job for every listed file and clears them', async () => {
    const retried: string[] = [];
    const origFetch = globalThis.fetch;
    globalThis.fetch = (async (url: unknown, init?: RequestInit) => {
      const u = String(url);
      if (u.includes('/api/indexing-jobs/failed/by-prefix')) {
        return new Response(
          JSON.stringify({
            jobs: [
              { pathHash: 'h-one', errorMessage: 'boom' },
              { pathHash: 'h-two', errorMessage: 'bang' },
            ],
          }),
          { status: 200, headers: { 'Content-Type': 'application/json' } },
        );
      }
      if (u.includes('/api/operations/') && u.includes('/invoke') && init?.method === 'POST') {
        retried.push(typeof init?.body === 'string' ? init.body : '');
        return new Response(JSON.stringify({ success: true }), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        });
      }
      return new Response('{}', { status: 200, headers: { 'Content-Type': 'application/json' } });
    }) as typeof fetch;
    try {
      const el = document.createElement('jf-failed-jobs-drawer') as FailedJobsDrawer;
      el.apiBase = 'http://x';
      document.body.appendChild(el);
      await settle(el);
      openFailedJobs('folder-hash');
      await pump(el);
      expect(el.shadowRoot?.querySelectorAll('.row').length).toBe(2);

      // Activate the header "Retry all" (jf-button exposes onActivate).
      const buttons = Array.from(el.shadowRoot?.querySelectorAll('jf-button') ?? []);
      const retryAll = buttons.find((b) => (b.getAttribute('label') ?? '').includes('Retry all')) as
        | (Element & { onActivate?: () => void })
        | undefined;
      retryAll?.onActivate?.();
      await pump(el);

      // The retry Operation was invoked for both files (POST /api/operations/.../invoke), and the
      // list cleared as each succeeded.
      expect(retried.length).toBe(2);
      expect(el.shadowRoot?.querySelectorAll('.row').length).toBe(0);
      el.remove();
    } finally {
      globalThis.fetch = origFetch;
    }
  });
});
