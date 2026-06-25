// @vitest-environment happy-dom

/**
 * Tempdoc 511 — `<jf-operation>` aggregate-component smoke test.
 *
 * Covers the end-to-end render path: catalog seeded → element
 * mounted → strategy dispatched → ActionButton in light DOM. Does
 * not cover invocation semantics — those are tested at the
 * OperationClient layer.
 */

import { afterEach, beforeEach, describe, expect, it } from 'vitest';
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
});
