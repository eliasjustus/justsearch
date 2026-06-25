// @vitest-environment happy-dom

/**
 * Tempdoc 559 notice-presentation — SystemNotice primitive tests.
 * Pins the two things it is the single authority for: tone (reflected attribute
 * that drives the left-accent token) + a11y live region (role/aria-live from `live`).
 */
import { beforeEach, describe, expect, it } from 'vitest';
import './SystemNotice.js';
import type { SystemNotice } from './SystemNotice.js';

async function mount(attrs: Record<string, string> = {}, content = 'hello'): Promise<SystemNotice> {
  const el = document.createElement('jf-system-notice') as SystemNotice;
  for (const [k, v] of Object.entries(attrs)) el.setAttribute(k, v);
  el.textContent = content;
  document.body.appendChild(el);
  await el.updateComplete;
  return el;
}

describe('SystemNotice', () => {
  beforeEach(() => {
    document.body.innerHTML = '';
  });

  it('defaults to neutral tone + a polite status live region', async () => {
    const el = await mount();
    expect(el.tone).toBe('neutral');
    expect(el.getAttribute('role')).toBe('status');
    expect(el.getAttribute('aria-live')).toBe('polite');
  });

  it('reflects tone as an attribute (drives the left-accent token via CSS)', async () => {
    const el = await mount({ tone: 'warning' });
    expect(el.getAttribute('tone')).toBe('warning');
  });

  it('live="alert" → role=alert + aria-live=assertive', async () => {
    const el = await mount({ live: 'alert' });
    expect(el.getAttribute('role')).toBe('alert');
    expect(el.getAttribute('aria-live')).toBe('assertive');
  });

  it('live="off" → no role + aria-live=off', async () => {
    const el = await mount({ live: 'off' });
    expect(el.hasAttribute('role')).toBe(false);
    expect(el.getAttribute('aria-live')).toBe('off');
  });

  it('updates the live region when `live` changes', async () => {
    const el = await mount({ live: 'status' });
    el.live = 'alert';
    await el.updateComplete;
    expect(el.getAttribute('role')).toBe('alert');
    expect(el.getAttribute('aria-live')).toBe('assertive');
  });

  it('renders slotted content', async () => {
    const el = await mount({}, 'Reindex required');
    expect(el.textContent).toContain('Reindex required');
    expect(el.shadowRoot?.querySelector('slot')).not.toBeNull();
  });
});
