// @vitest-environment happy-dom

/**
 * §32 U5 — <jf-agent-activity-panel> render tests. Drives the real component
 * through the real substrates (pending / tasks / journal).
 */

import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { AgentActivityPanel } from './AgentActivityPanel.js';
import {
  proposeEffect,
  listPending,
  __resetPendingForTest,
} from '../substrates/pending-effects/index.js';
import { startTask, __resetTasksForTest } from '../substrates/tasks/index.js';
import {
  recordEffect,
  __resetJournalForTest,
} from '../substrates/effects/index.js';
import { CORE_PROVENANCE } from '../primitives/provenance.js';
import {
  __seedForTest as __seedOpsForTest,
  __resetForTest as __resetOpsForTest,
} from '../../api/registry/OperationCatalogClient.js';
import type { Operation, OperationCatalog } from '../../api/types/registry.js';

void AgentActivityPanel;

// §32 #2B — seed an operation's risk so the accept gate can read it.
function seedOp(id: string, risk: 'LOW' | 'MEDIUM' | 'HIGH'): void {
  const entry: Operation = {
    id,
    presentation: { labelKey: id, descriptionKey: id, iconHint: null, category: null },
    intf: { errors: [], inputs: {}, result: {}, uiHints: {} },
    policy: { risk, confirm: { kind: 'NONE' }, audit: 'METADATA_ONLY', undoSupported: false },
    availability: {},
    lineage: { affects: [], supersedes: [] },
    provenance: { tier: 'CORE', contributorId: 'core', version: '1.0' },
    executors: ['UI'],
    audience: 'USER',
    consumers: [],
  };
  const catalog: OperationCatalog = {
    schemaVersion: '1.0',
    catalogVersion: 1,
    namespace: 'core',
    primitive: 'Operation',
    entries: [entry],
  };
  __seedOpsForTest(catalog);
}

let host: HTMLElement & { open: boolean };

beforeEach(() => {
  __resetPendingForTest();
  __resetTasksForTest();
  __resetJournalForTest();
  __resetOpsForTest();
  host = document.createElement('jf-agent-activity-panel') as HTMLElement & {
    open: boolean;
  };
  document.body.appendChild(host);
});

afterEach(() => {
  host.remove();
  __resetOpsForTest();
});

async function flush(): Promise<void> {
  await Promise.resolve();
  await Promise.resolve();
}

/** 574 B — accept/reject/close are <jf-button>; activate the native button in the composed control. */
async function activateJfButton(el: Element | null | undefined): Promise<void> {
  if (!el) throw new Error('activateJfButton: element not found');
  await (el as unknown as { updateComplete: Promise<unknown> }).updateComplete;
  const control = el.shadowRoot!.querySelector('jf-control')!;
  await (control as unknown as { updateComplete: Promise<unknown> }).updateComplete;
  (control.shadowRoot!.querySelector('button') as HTMLButtonElement).click();
}

describe('<jf-agent-activity-panel> (§32 U5)', () => {
  it('renders nothing when closed', async () => {
    await flush();
    expect(host.shadowRoot?.querySelector('.panel')).toBeNull();
  });

  it('open: aggregates pending + tasks + recent agent journal with counts', async () => {
    proposeEffect({ kind: 'toast', message: 'x' }, CORE_PROVENANCE, 'agent', {
      rationale: 'why',
    });
    startTask({ label: 'Reindex' });
    recordEffect({ kind: 'navigate', to: '#a' }, CORE_PROVENANCE, {
      originator: 'agent',
    });
    host.open = true;
    await flush();
    const sr = host.shadowRoot!;
    expect(sr.querySelector('[data-testid="agent-activity-panel"]')).not.toBeNull();
    expect(
      sr.querySelector('[data-testid="aap-pending-section"]')?.textContent,
    ).toContain('Pending (1)');
    expect(
      sr.querySelector('[data-testid="aap-tasks-section"]')?.textContent,
    ).toContain('Running tasks (1)');
    expect(
      sr.querySelector('[data-testid="aap-recent-section"]')?.textContent,
    ).toContain('Recent agent actions (1)');
  });

  it('the accept button on a pending consumes it', async () => {
    const pid = proposeEffect(
      { kind: 'toast', message: 'y' },
      CORE_PROVENANCE,
      'agent',
    );
    host.open = true;
    await flush();
    const accept = host.shadowRoot?.querySelector(
      `[data-testid="aap-pending-${pid}"] jf-button.accept`,
    );
    expect(accept).not.toBeNull();
    await activateJfButton(accept);
    await flush();
    expect(listPending()).toHaveLength(0);
  });

  it('the close button sets open=false and collapses the panel', async () => {
    host.open = true;
    await flush();
    await activateJfButton(host.shadowRoot?.querySelector('[data-testid="agent-activity-close"]'));
    await flush();
    expect(host.open).toBe(false);
    expect(host.shadowRoot?.querySelector('.panel')).toBeNull();
  });

  // §32 #2B + tempdoc 550 C3 — accepting a HIGH-risk proposed op requires typing the op
  // id, then dispatches with the effect marked `consented` (no client-side mint). The
  // Shell recovers the backend 428 via approve-by-pendingId.
  it('HIGH op accept requires typed-confirm, then dispatches consented (no client-side mint)', async () => {
    seedOp('core.file-operations', 'HIGH');
    const events: Array<Record<string, unknown>> = [];
    const lis = (e: Event) =>
      events.push((e as CustomEvent).detail as Record<string, unknown>);
    document.addEventListener('jf-invoke-operation', lis);
    try {
      const pid = proposeEffect(
        { kind: 'invoke-operation', operationId: 'core.file-operations', args: {} },
        CORE_PROVENANCE,
        'agent',
      );
      host.open = true;
      await flush();
      await activateJfButton(
        host.shadowRoot!.querySelector(`[data-testid="aap-pending-${pid}"] jf-button.accept`),
      );
      await flush();
      const row = host.shadowRoot!.querySelector(`[data-testid="aap-confirm-${pid}"]`);
      expect(row).not.toBeNull();
      expect(events).toHaveLength(0);
      const input = row!.querySelector('input') as HTMLInputElement;
      input.value = 'core.file-operations';
      input.dispatchEvent(new Event('input'));
      await flush();
      await activateJfButton(
        host.shadowRoot!.querySelector(`[data-testid="aap-confirm-${pid}"] jf-button.accept`),
      );
      await flush();
      expect(listPending()).toHaveLength(0);
      expect(events).toHaveLength(1);
      expect(events[0]!.operationId).toBe('core.file-operations');
      expect(events[0]!.consented).toBe(true);
      expect(events[0]!.confirmationToken).toBeUndefined();
    } finally {
      document.removeEventListener('jf-invoke-operation', lis);
    }
  });
});
