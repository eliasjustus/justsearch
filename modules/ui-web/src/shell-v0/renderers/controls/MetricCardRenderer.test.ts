// @vitest-environment happy-dom

/**
 * 569 §15 — the Health metric-card renderer projects stat cards through the engine; the tone dot is a
 * SEMANTIC token (engine-derived), never an author colour.
 */
import { describe, it, expect, beforeEach } from 'vitest';
import './MetricCardRenderer.js';
import type { MetricCardRenderer } from './MetricCardRenderer.js';
import { getXUiRendererTag } from './XUiRendererControl.js';

beforeEach(() => {
  document.body.innerHTML = '';
});

describe('<jf-metric-card>', () => {
  it('registers the hint and renders one card per metric (value + tone dot)', async () => {
    expect(getXUiRendererTag('metric-card')).toBe('jf-metric-card');
    const el = document.createElement('jf-metric-card') as MetricCardRenderer & {
      data: unknown;
      visible: boolean;
      enabled: boolean;
      uischema: unknown;
      onChange: () => void;
    };
    document.body.appendChild(el);
    el.data = [
      { label: 'Files', value: '1,234', icon: 'file-text', tone: 'success' },
      { label: 'Queue', value: '0', icon: 'zap', tone: 'neutral', sub: 'Idle' },
    ];
    el.visible = true;
    el.enabled = true;
    el.uischema = { type: 'Control' };
    el.onChange = () => {};
    await el.updateComplete;
    const cards = el.shadowRoot?.querySelectorAll('.card');
    expect(cards?.length).toBe(2);
    expect(el.shadowRoot?.querySelector('.card-value')?.textContent).toBe('1,234');
    expect(el.shadowRoot?.querySelector('.dot')?.getAttribute('data-tone')).toBe('success');
    el.remove();
  });
});
