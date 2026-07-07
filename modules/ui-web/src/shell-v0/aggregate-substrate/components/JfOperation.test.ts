// @vitest-environment happy-dom

/**
 * Tempdoc 511 — `<jf-operation>` aggregate-component smoke test.
 *
 * Covers the end-to-end render path: catalog seeded → element
 * mounted → strategy dispatched → ActionButton in light DOM. Does
 * not cover invocation semantics — those are tested at the
 * OperationClient layer.
 */

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  __resetForTest,
  __seedForTest,
} from '../../../api/registry/OperationCatalogClient';
import type {
  Operation,
  OperationCatalog,
} from '../../../api/types/registry';
import { bootstrapAggregateSubstrate, __resetBootstrap } from '../bootstrap';
import { __clearAggregateRegistry } from '../aggregateRegistry';
import './JfOperation.js';

function op(id: string, overrides: Partial<Operation> = {}): Operation {
  return {
    id,
    presentation: {
      labelKey: `ops.${id}.label`,
      descriptionKey: `ops.${id}.description`,
      iconHint: null,
      category: null,
    },
    intf: { errors: [], inputs: {}, result: {}, uiHints: {} },
    policy: {
      risk: 'LOW',
      confirm: { kind: 'NONE' },
      audit: 'NONE',
      undoSupported: false,
    },
    availability: {},
    lineage: { affects: [], supersedes: [] },
    provenance: { tier: 'CORE', contributorId: 'core', version: '1.0' },
    executors: ['UI'],
    audience: 'USER',
    consumers: [],
    ...overrides,
  };
}

function catalogOf(...entries: Operation[]): OperationCatalog {
  return {
    schemaVersion: '1.0',
    catalogVersion: 1,
    namespace: 'core',
    primitive: 'Operation',
    entries,
  };
}

async function mountOperation(
  operationId: string,
  context = 'button',
): Promise<HTMLElement> {
  const el = document.createElement('jf-operation');
  el.setAttribute('operation-id', operationId);
  el.setAttribute('context', context);
  document.body.appendChild(el);
  await (el as unknown as { updateComplete: Promise<void> }).updateComplete;
  return el;
}

describe('<jf-operation>', () => {
  beforeEach(() => {
    __resetForTest();
    __clearAggregateRegistry();
    __resetBootstrap();
    bootstrapAggregateSubstrate();
  });
  afterEach(() => {
    document.body.innerHTML = '';
    __resetForTest();
    __clearAggregateRegistry();
    __resetBootstrap();
  });

  it('renders an op-button when the operation is in the catalog', async () => {
    __seedForTest(catalogOf(op('core.do-thing', { policy: {
      risk: 'HIGH',
      confirm: { kind: 'TYPED', confirmTextKey: 'ops.do-thing.confirm' },
      audit: 'METADATA_ONLY',
      undoSupported: false,
    } })));
    const el = await mountOperation('core.do-thing');
    const button = el.querySelector('jf-op-button');
    expect(button).not.toBeNull();
    expect(button?.getAttribute('operation-id')).toBe('core.do-thing');
    // Confirm-kind is forwarded as a first-class attribute (Track B);
    // OpButton consumes it and passes through to ActionButton.
    expect(button?.getAttribute('confirm-kind')).toBe('TYPED');
  });

  it('renders nothing when the operation is not in the catalog', async () => {
    const el = await mountOperation('core.unknown');
    expect(el.querySelector('jf-op-button')).toBeNull();
  });

  it('honors the audience gate', async () => {
    __seedForTest(catalogOf(op('core.dev-only', { audience: 'DEVELOPER' })));
    const el = await mountOperation('core.dev-only');
    expect(el.querySelector('jf-op-button')).toBeNull();
  });

  it('forwards .args through jf-op-button into the /invoke request body (tempdoc 689)', async () => {
    // Pins the through-chain that tempdoc 689 revived: <jf-operation .args=...>
    // was previously dead because neither JfOperation nor the (Operation,
    // button) strategy read or forwarded it. Drives jf-operation (the
    // aggregate host) — not jf-op-button directly — so the whole pass-through
    // is exercised, matching HealthSurface/HelpSurface's real usage.
    __seedForTest(catalogOf(op('core.export-diagnostics')));
    const fetchImpl = vi.fn<typeof fetch>().mockResolvedValue(
      new Response(JSON.stringify({ success: true, message: 'ok' }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      }),
    );
    const originalFetch = globalThis.fetch;
    globalThis.fetch = fetchImpl;
    try {
      const el = document.createElement('jf-operation') as HTMLElement & {
        args: Record<string, unknown>;
      };
      el.setAttribute('operation-id', 'core.export-diagnostics');
      el.setAttribute('context', 'button');
      el.setAttribute('api-base', 'http://localhost');
      el.args = { feTelemetry: { wireDrift: { total: 1 } } };
      document.body.appendChild(el);
      await (el as unknown as { updateComplete: Promise<void> }).updateComplete;

      const opButton = el.querySelector('jf-op-button');
      expect(opButton).not.toBeNull();
      await (opButton as unknown as { updateComplete: Promise<void> }).updateComplete;
      const actionButton = opButton!.shadowRoot?.querySelector('jf-action-button');
      expect(actionButton).toBeTruthy();

      actionButton!.dispatchEvent(
        new CustomEvent('action-invoke', {
          detail: { operationId: 'core.export-diagnostics', risk: 'LOW' },
          bubbles: true,
          composed: true,
        }),
      );
      await new Promise((resolve) => setTimeout(resolve, 0));

      const invokeCall = fetchImpl.mock.calls.find((c) => String(c[0]).includes('/invoke'));
      expect(invokeCall).toBeTruthy();
      const body = JSON.parse((invokeCall![1] as RequestInit).body as string) as {
        args: { feTelemetry: { wireDrift: { total: number } } };
      };
      expect(body.args.feTelemetry.wireDrift.total).toBe(1);
    } finally {
      globalThis.fetch = originalFetch;
    }
  });
});
