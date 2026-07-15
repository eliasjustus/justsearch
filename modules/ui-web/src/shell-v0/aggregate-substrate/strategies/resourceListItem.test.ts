// @vitest-environment happy-dom

/**
 * Tempdoc 511-followup Track C — Behavioral Pass-8 mirror for
 * (Resource, list-item).
 */

import { describe, expect, it } from 'vitest';
import { render } from 'lit';
import {
  RESOURCE_LIST_ITEM_ROLES,
  resourceListItemStrategy,
} from './resourceListItem';
import type { Resource } from '../../../api/types/registry';
import { classifiedKeys } from '../assertExhaustive';
import { assertBehavioralPass8 } from '../behavioralPass8';

const REFERENCE_RESOURCE: Resource = {
  id: 'core.test-resource',
  presentation: {
    labelKey: 'registry-resource.test.label',
    descriptionKey: 'registry-resource.test.description',
    iconHint: null,
    category: null,
  },
  schema: 'https://ssot.justsearch/v1/schemas/test.v1.json',
  category: 'TABULAR',
  subscriptionMode: 'SSE_STREAM',
  endpoint: '/api/test/stream',
  kind: 'test-table',
  history: null,
  recovery: null,
  provenance: { tier: 'CORE', contributorId: 'core', version: '1.0' },
  privacy: { pathPolicy: 'NO_PATHS', loopbackOnly: true, resolver: null },
  itemOperations: [],
  collectionOperations: [],
  primaryKey: 'id',
  audience: 'USER',
  consumers: [],
  emissionPolicy: null,
  role: 'PRODUCT',
};

describe('(Resource, list-item) canonical strategy — Pass-8 mirror', () => {
  it('roles record covers every Resource wire key', () => {
    const wireKeys = Object.keys(REFERENCE_RESOURCE).sort();
    const declared = classifiedKeys(RESOURCE_LIST_ITEM_ROLES).slice().sort();
    expect(declared).toEqual(wireKeys);
  });

  it('behavioral Pass-8 — roles agree with observed render diffs', () => {
    assertBehavioralPass8({
      reference: REFERENCE_RESOURCE,
      roles: RESOURCE_LIST_ITEM_ROLES,
      strategy: resourceListItemStrategy,
      ctx: {},
      host: { apiBase: 'http://localhost', viewerAudience: 'DEVELOPER' },
      mutations: {
        // visual — resource-id attribute on the inner jf-resource-view
        id: (res) => ({ ...res, id: 'core.mutated' }),
        // gate — flipping audience MAY hide the strategy's output
        audience: (res) => ({ ...res, audience: 'AGENT' }),
        // routing — all fields below are passed downstream via id;
        // jf-resource-view fetches them from the catalog. The strategy
        // does not read them directly, so mutation MUST NOT diff.
        presentation: (res) => ({
          ...res,
          presentation: { ...res.presentation, labelKey: 'mutated.label' },
        }),
        schema: (res) => ({ ...res, schema: 'https://example.com/mutated.json' }),
        category: (res) => ({ ...res, category: 'STATE' }),
        subscriptionMode: (res) => ({ ...res, subscriptionMode: 'ONE_SHOT' }),
        endpoint: (res) => ({ ...res, endpoint: '/api/mutated/stream' }),
        kind: (res) => ({ ...res, kind: 'mutated-kind' }),
        history: (res) => ({
          ...res,
          history: {
            mode: 'RING_BUFFER',
            capacity: 50,
            retention: null,
            onOverflow: 'EVICT_OLDEST',
            resumeWindow: 'PT5M',
          },
        }),
        recovery: (res) => ({ ...res, recovery: 'core.recover-op' }),
        provenance: (res) => ({
          ...res,
          provenance: { ...res.provenance, tier: 'TRUSTED_PLUGIN' },
        }),
        privacy: (res) => ({
          ...res,
          privacy: { ...res.privacy, pathPolicy: 'RAW' },
        }),
        itemOperations: (res) => ({
          ...res,
          itemOperations: ['core.mutated-op'],
        }),
        collectionOperations: (res) => ({
          ...res,
          collectionOperations: ['core.coll-op'],
        }),
        primaryKey: (res) => ({ ...res, primaryKey: 'mutatedKey' }),
        consumers: (res) => ({
          ...res,
          consumers: [{ consumerId: 'c.test', audience: 'USER' }],
        }),
        emissionPolicy: (res) => ({
          ...res,
          emissionPolicy: { renderHint: 'PERSISTED', dedupeWindow: null },
        }),
        // Tempdoc 571 §4c: role is a routing field (a derivation input read backend-side), so mutating
        // it must not change the list-item strategy output.
        role: (res) => ({ ...res, role: 'DIAGNOSTIC' }),
      },
    });
  });

  it('audience gate denies USER viewers from OPERATOR resources', () => {
    const operatorOnly: Resource = {
      ...REFERENCE_RESOURCE,
      audience: 'OPERATOR',
    };
    const host = { apiBase: '', viewerAudience: 'USER' as const };
    const result = resourceListItemStrategy(operatorOnly, {}, host);
    expect(typeof result).toBe('symbol');
  });

  // 727 F-4 — pre-fix, the strategy rendered `<jf-resource-view resource-id=...>` with no
  // `api-base` attribute at all, regardless of `host.apiBase`. `<jf-resource-view>` uses its
  // own `apiBase` property (default `''`) to build its SSE-stream / REST-snapshot URLs, so a
  // dropped api-base makes every Resource consumed through `<jf-resource>` (the one sanctioned
  // consumption point; `core.operation-history` on ActivitySurface is its only V1 caller)
  // resolve those URLs against the wrong origin whenever the FE and backend are served from
  // different origins — the stream never opens, with no error, and the connection badge is
  // stuck at "connecting" forever (727 F-4's exact symptom). This test fails on pre-fix code
  // because the rendered HTML has no `api-base` attribute — `toContain` finds neither string.
  it('forwards host.apiBase to the inner jf-resource-view (727 F-4)', () => {
    const host = { apiBase: 'http://127.0.0.1:33221', viewerAudience: 'USER' as const };
    const result = resourceListItemStrategy(REFERENCE_RESOURCE, {}, host);
    const container = document.createElement('div');
    render(result, container);
    expect(container.innerHTML).toContain('api-base="http://127.0.0.1:33221"');
  });

  it('forwards an empty host.apiBase explicitly (no attribute silently dropped)', () => {
    const host = { apiBase: '', viewerAudience: 'USER' as const };
    const result = resourceListItemStrategy(REFERENCE_RESOURCE, {}, host);
    const container = document.createElement('div');
    render(result, container);
    expect(container.querySelector('jf-resource-view')?.getAttribute('api-base')).toBe('');
  });
});
