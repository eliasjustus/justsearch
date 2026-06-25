/**
 * Tests for the install-time conformance gate (569 Move 6) + anti-spoof reserved-component
 * rejection (Move 4/6) + quarantine-to-default (degrade-never-fail).
 */
import { describe, expect, it } from 'vitest';
import {
  certifyPresentation,
  quarantineSurfaces,
  quarantineLayout,
  describeConformanceError,
} from './conformanceGate.js';
import type { PresentationDeclaration } from './presentationDeclaration.js';
import { REQUIRED_REGION_IDS } from './requiredRegions.js';

describe('conformance gate', () => {
  it('passes a declaration with no literal contrast violations', () => {
    const r = certifyPresentation({
      schemaVersion: 1,
      id: 'user.ok',
      displayName: 'OK',
      theme: { tokens: { 'accent-tint': 'oklch(70% 0.1 200)' } },
    });
    expect(r.verdict.ok).toBe(true);
    expect(r.declaration).not.toBeNull();
  });

  it('fails a theme with a failing literal contrast pair (readability floor)', () => {
    const r = certifyPresentation({
      schemaVersion: 1,
      id: 'user.bad',
      displayName: 'Bad',
      theme: { tokens: { 'text-primary': '#888888', 'surface-1': '#777777' } },
    });
    expect(r.verdict.ok).toBe(false);
    expect(r.verdict.errors.map(describeConformanceError).join(' ')).toMatch(/contrast/);
  });

  it('rejects a layout mounting a reserved trusted component — anti-spoof (Move 4/6)', () => {
    const r = certifyPresentation({
      schemaVersion: 1,
      id: 'user.spoof',
      displayName: 'Spoof',
      layout: { regions: [{ id: 'x', component: 'jf-authorization-host' }] },
    });
    expect(r.declaration).toBeNull();
    expect(r.verdict.errors.map(describeConformanceError).join(' ')).toMatch(/AUTHORABLE|reserved/i);
  });

  it('quarantines a failed surface body to the default (degrade-never-fail)', () => {
    const decl: PresentationDeclaration = {
      schemaVersion: 1,
      id: 'user.q',
      displayName: 'Q',
      body: {
        'core.a': { schema: {}, uischema: { type: 'VerticalLayout', elements: [] } },
        'core.b': { schema: {}, uischema: { type: 'VerticalLayout', elements: [] } },
      },
    };
    const q = quarantineSurfaces(decl, ['core.b']);
    expect(Object.keys(q.body ?? {})).toEqual(['core.a']);
  });

  it('required-presence: a layout omitting a required region is quarantined to default (Move 4/6)', () => {
    const r = certifyPresentation({
      schemaVersion: 1,
      id: 'user.orphan',
      displayName: 'Orphan',
      layout: { regions: [{ id: 'just-my-own-region' }] },
    });
    expect(r.verdict.quarantinedLayout).toBe(true);
    expect(r.verdict.errors.map(describeConformanceError).join(' ')).toMatch(/required/);
    // quarantineLayout strips the layout so the apply path uses the default (complete) layout.
    const safe = quarantineLayout(r.declaration!);
    expect(safe.layout).toBeUndefined();
  });

  it('perf budget: a pathological body is quarantined to the default (Move 6, Fix C)', () => {
    const r = certifyPresentation({
      schemaVersion: 1,
      id: 'user.huge',
      displayName: 'Huge',
      body: {
        'core.x': {
          schema: {},
          uischema: {
            type: 'VerticalLayout',
            elements: Array.from({ length: 700 }, () => ({ type: 'Control', scope: '#/x' })),
          },
        },
      },
    });
    expect(r.verdict.quarantinedSurfaces).toContain('core.x');
    expect(r.verdict.errors.map(describeConformanceError).join(' ')).toMatch(/perf/);
  });

  it('required-presence: a layout that includes every required region passes', () => {
    const r = certifyPresentation({
      schemaVersion: 1,
      id: 'user.complete',
      displayName: 'Complete',
      layout: { regions: [...REQUIRED_REGION_IDS.map((id) => ({ id })), { id: 'extra' }] },
    });
    expect(r.verdict.quarantinedLayout).toBe(false);
    expect(r.verdict.ok).toBe(true);
  });

  it('569 §14 — present-but-hidden: a required region carrying visibleWhen is quarantined (Move 4/6)', () => {
    const r = certifyPresentation({
      schemaVersion: 1,
      id: 'user.hidden',
      displayName: 'Hidden',
      // Every required region is PRESENT (presence check passes) but `stage` is gated by a binding
      // — the loophole presence-only would miss. The visibility check catches it.
      layout: {
        regions: REQUIRED_REGION_IDS.map((id) => ({ id, visibleWhen: 'data.never == true' })),
      },
    });
    expect(r.verdict.quarantinedLayout).toBe(true);
    expect(r.verdict.errors.map(describeConformanceError).join(' ')).toMatch(/unconditionally present/);
    const safe = quarantineLayout(r.declaration!);
    expect(safe.layout).toBeUndefined();
  });
});
