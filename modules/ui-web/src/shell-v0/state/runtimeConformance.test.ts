// @vitest-environment happy-dom

/**
 * 569 Move 6 — apply-time runtime conformance + quarantine-to-default.
 */
import { describe, it, expect, beforeEach } from 'vitest';
import {
  auditRenderedSurface,
  auditAndQuarantine,
  auditRenderedA11y,
  defaultContrastSampler,
} from './runtimeConformance.js';
import {
  applyPresentationBodies,
  activeBodyFor,
  __resetPresentationForTest,
} from './presentationRuntime.js';
import {
  SETTINGS_DECLARED,
  SETTINGS_INTERFACE_REGION,
} from '../themes/builtinPresentations.js';

const NODE = {} as unknown as Element; // injected sampler ignores the node

beforeEach(() => {
  __resetPresentationForTest();
  document.body.innerHTML = '';
});

describe('defaultContrastSampler — REAL sampler across shadow boundaries (569 Fix 2)', () => {
  // Build host → shadow → child custom-element-like → shadow → text spans, mirroring how
  // jf-declared-surface renders controls in NESTED shadow roots (the case the old sampler MISSED).
  function buildHost(): { host: HTMLElement; bad: HTMLSpanElement; good: HTMLSpanElement } {
    const host = document.createElement('div');
    const outer = host.attachShadow({ mode: 'open' });
    const control = document.createElement('div'); // a "renderer" with its own shadow root
    const inner = control.attachShadow({ mode: 'open' });

    const bad = document.createElement('span');
    bad.textContent = 'low contrast';
    bad.style.color = 'rgb(255, 255, 255)';
    bad.style.backgroundColor = 'rgb(240, 240, 240)'; // white on near-white ≈ 1.07:1

    const good = document.createElement('span');
    good.textContent = 'readable';
    good.style.color = 'rgb(0, 0, 0)';
    good.style.backgroundColor = 'rgb(255, 255, 255)'; // black on white = 21:1

    inner.append(bad, good);
    outer.append(control);
    document.body.appendChild(host);
    return { host, bad, good };
  }

  it('SEES text rendered inside nested shadow roots and flags the low-contrast pair', () => {
    const { host } = buildHost();
    const violations = defaultContrastSampler(host);
    // The old querySelectorAll('*') sampler returned [] here (shadow-blind). The fixed one catches it.
    expect(violations.length).toBe(1);
    expect(violations[0]).toMatch(/contrast 1\.\d+:1/);
  });

  it('does not flag a readable pair', () => {
    const host = document.createElement('div');
    const root = host.attachShadow({ mode: 'open' });
    const span = document.createElement('span');
    span.textContent = 'readable';
    span.style.color = 'rgb(17, 17, 17)';
    span.style.backgroundColor = 'rgb(255, 255, 255)';
    root.appendChild(span);
    document.body.appendChild(host);
    expect(defaultContrastSampler(host)).toEqual([]);
  });
});

describe('auditRenderedA11y (apply-time axe sweep, 569 Fix C)', () => {
  it('fails when the auditor reports violations, passes when clean (injectable)', async () => {
    const bad = await auditRenderedA11y(NODE, async () => ['a11y: color-contrast (2)']);
    expect(bad.ok).toBe(false);
    expect(bad.violations).toHaveLength(1);
    const good = await auditRenderedA11y(NODE, async () => []);
    expect(good.ok).toBe(true);
  });
});

describe('auditRenderedSurface', () => {
  it('passes when the sampler reports no violations', () => {
    expect(auditRenderedSurface(NODE, () => []).ok).toBe(true);
  });

  it('fails and surfaces the violations when the sampler reports them', () => {
    const r = auditRenderedSurface(NODE, () => ['computed contrast 1.4:1 on <span>']);
    expect(r.ok).toBe(false);
    expect(r.violations).toHaveLength(1);
  });
});

describe('auditAndQuarantine (the apply-time runtime loop)', () => {
  it('quarantines a rendered region to the built-in when its computed contrast fails', () => {
    applyPresentationBodies(SETTINGS_DECLARED);
    expect(activeBodyFor(SETTINGS_INTERFACE_REGION)).toBeDefined();

    const r = auditAndQuarantine(SETTINGS_INTERFACE_REGION, NODE, () => ['fail 1.2:1']);
    expect(r.ok).toBe(false);
    // The failing region reverted to the built-in render (degrade-never-fail).
    expect(activeBodyFor(SETTINGS_INTERFACE_REGION)).toBeUndefined();
  });

  it('leaves a clean region rendering through the engine', () => {
    applyPresentationBodies(SETTINGS_DECLARED);
    const r = auditAndQuarantine(SETTINGS_INTERFACE_REGION, NODE, () => []);
    expect(r.ok).toBe(true);
    expect(activeBodyFor(SETTINGS_INTERFACE_REGION)).toBeDefined();
  });
});
