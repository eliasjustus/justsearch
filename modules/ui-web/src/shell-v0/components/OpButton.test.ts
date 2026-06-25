/**
 * @vitest-environment happy-dom
 *
 * Tempdoc 509 Phase 2 — OpButton tests.
 */

import { describe, expect, it, vi, beforeEach } from 'vitest';

// Mock the OperationCatalogClient before importing OpButton
vi.mock('../../api/registry/OperationCatalogClient.js', () => {
  const ops = new Map<string, unknown>();
  ops.set('core.reindex', {
    id: 'core.reindex',
    presentation: { labelKey: 'ops.reindex.label', descriptionKey: 'ops.reindex.description' },
    policy: { risk: 'LOW', confirm: { kind: 'NONE' } },
  });
  ops.set('core.rebuild-index', {
    id: 'core.rebuild-index',
    presentation: { labelKey: 'ops.rebuild-index.label', descriptionKey: 'ops.rebuild-index.description' },
    policy: { risk: 'HIGH', confirm: { kind: 'TYPED', confirmTextKey: 'ops.rebuild-index.confirm' } },
  });
  ops.set('core.low-risk-typed', {
    id: 'core.low-risk-typed',
    presentation: { labelKey: 'ops.low-risk-typed.label', descriptionKey: '' },
    policy: { risk: 'LOW', confirm: { kind: 'TYPED', confirmTextKey: 'k' } },
  });
  return {
    getOperation: (id: string) => ops.get(id),
    onCatalogChange: () => () => {},
  };
});

// Mock the i18n catalog
vi.mock('../../i18n/resourceCatalog.js', () => ({
  localizeResourceKey: (key: string) => {
    const map: Record<string, string> = {
      'ops.reindex.label': 'Reindex',
      'ops.rebuild-index.label': 'Force Rebuild',
    };
    return map[key] ?? key;
  },
}));

// Mock OperationClient
vi.mock('../operations/OperationClient.js', () => {
  class MockOperationClient {
    async invoke() {
      return {
        success: true,
        message: 'ok',
        executionId: 'exec-1',
        structuredData: { path: '/tmp/diag.zip' },
      };
    }
    // Tempdoc 550 C3: OpButton dispatches via invokeWithConsent. For the LOW-risk path
    // (consented:false, no 428) it delegates to a plain invoke.
    async invokeWithConsent() {
      return this.invoke();
    }
  }
  class MockOperationError extends Error {
    errorClass = 'HANDLER_FAILURE';
  }
  return {
    OperationClient: MockOperationClient,
    OperationError: MockOperationError,
    // 574 F2 — OpButton now resolves its client via the shared getOperationClient accessor;
    // mock it to the same stub so the invoke path is exercised, not the real network.
    getOperationClient: () => new MockOperationClient(),
  };
});

import './OpButton.js';
import type { OpButton, OpSuccessEventDetail } from './OpButton.js';

async function settle(el: Element): Promise<void> {
  await (el as unknown as { updateComplete: Promise<unknown> }).updateComplete;
}

describe('OpButton', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('resolves label from catalog', async () => {
    const el = document.createElement('jf-op-button') as OpButton;
    el.operationId = 'core.reindex';
    document.body.appendChild(el);
    await settle(el);
    const btn = el.shadowRoot?.querySelector('jf-action-button');
    expect(btn?.getAttribute('label')).toBe('Reindex');
    el.remove();
  });

  it('resolves risk from catalog', async () => {
    const el = document.createElement('jf-op-button') as OpButton;
    el.operationId = 'core.rebuild-index';
    document.body.appendChild(el);
    await settle(el);
    const btn = el.shadowRoot?.querySelector('jf-action-button');
    expect(btn?.getAttribute('risk')).toBe('HIGH');
    expect(btn?.getAttribute('label')).toBe('Force Rebuild');
    el.remove();
  });

  it('falls back to humanized ID when catalog has no entry', async () => {
    const el = document.createElement('jf-op-button') as OpButton;
    el.operationId = 'core.unknown-action';
    document.body.appendChild(el);
    await settle(el);
    const btn = el.shadowRoot?.querySelector('jf-action-button');
    expect(btn?.getAttribute('label')).toBe('Unknown Action');
    el.remove();
  });

  it('forwards wire confirm.kind to ActionButton (Track B)', async () => {
    // Wire shape: core.rebuild-index has policy.confirm.kind = TYPED.
    // OpButton must forward to ActionButton so wire-driven ceremony
    // wins over the legacy risk-as-proxy.
    const el = document.createElement('jf-op-button') as OpButton;
    el.operationId = 'core.rebuild-index';
    document.body.appendChild(el);
    await settle(el);
    const btn = el.shadowRoot?.querySelector('jf-action-button');
    expect(btn?.getAttribute('confirm-kind')).toBe('TYPED');
    el.remove();
  });

  it('honors explicit confirm-kind override from the strategy', async () => {
    // core.low-risk-typed has risk=LOW + confirm.kind=TYPED. Without
    // the override, ActionButton's risk-derived ceremony would fire
    // immediately (LOW). With the override, the TYPED ceremony wins.
    const el = document.createElement('jf-op-button') as OpButton;
    el.operationId = 'core.low-risk-typed';
    document.body.appendChild(el);
    await settle(el);
    const btn = el.shadowRoot?.querySelector('jf-action-button');
    expect(btn?.getAttribute('risk')).toBe('LOW');
    expect(btn?.getAttribute('confirm-kind')).toBe('TYPED');
    el.remove();
  });

  it('explicit confirm-kind attribute beats catalog value', async () => {
    // Strategy can pass `confirm-kind="NONE"` to suppress the wire's
    // TYPED ceremony (e.g., a debug surface that wants fire-and-forget).
    const el = document.createElement('jf-op-button') as OpButton;
    el.operationId = 'core.rebuild-index';
    el.setAttribute('confirm-kind', 'NONE');
    document.body.appendChild(el);
    await settle(el);
    const btn = el.shadowRoot?.querySelector('jf-action-button');
    expect(btn?.getAttribute('confirm-kind')).toBe('NONE');
    el.remove();
  });

  it('fires op-success on successful invoke with structuredData', async () => {
    const el = document.createElement('jf-op-button') as OpButton;
    el.operationId = 'core.reindex';
    document.body.appendChild(el);
    await settle(el);
    let successDetail: OpSuccessEventDetail | null = null;
    el.addEventListener('op-success', ((e: CustomEvent<OpSuccessEventDetail>) => {
      successDetail = e.detail;
    }) as EventListener);
    const btn = el.shadowRoot?.querySelector('jf-action-button');
    btn?.dispatchEvent(
      new CustomEvent('action-invoke', {
        detail: { operationId: 'core.reindex', risk: 'LOW' },
        bubbles: true,
        composed: true,
      }),
    );
    await new Promise((r) => setTimeout(r, 50));
    expect(successDetail).toBeTruthy();
    // Tempdoc 511-followup-D — surfaces need the operation's structured
    // output forwarded through the typed event detail, not just the
    // human-readable message.
    const detail = successDetail as unknown as OpSuccessEventDetail;
    expect(detail.structuredData).toEqual({ path: '/tmp/diag.zip' });
    expect(detail.executionId).toBe('exec-1');
    el.remove();
  });
});
