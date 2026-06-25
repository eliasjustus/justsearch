/**
 * @vitest-environment happy-dom
 *
 * Slice 491 §9.D Phase E (C2) — NavigationReceipt tests.
 */

import { describe, expect, it } from 'vitest';
import './NavigationReceipt.js';

async function settle(el: Element): Promise<void> {
  await (el as unknown as { updateComplete: Promise<unknown> }).updateComplete;
}

describe('NavigationReceipt', () => {
  it('renders extracted state with → glyph', async () => {
    const el = document.createElement('jf-navigation-receipt');
    el.setAttribute('outcome', 'extracted');
    el.setAttribute('target', 'core.library-surface');
    document.body.appendChild(el);
    await settle(el);
    const text = el.shadowRoot?.textContent ?? '';
    expect(text).toContain('→');
    expect(text).toContain('URL extracted');
    expect(text).toContain('core.library-surface');
    el.remove();
  });

  it('renders forwarded state with ✓ + "Navigated to"', async () => {
    const el = document.createElement('jf-navigation-receipt');
    el.setAttribute('outcome', 'forwarded');
    el.setAttribute('target', 'core.library-surface');
    document.body.appendChild(el);
    await settle(el);
    const text = el.shadowRoot?.textContent ?? '';
    expect(text).toContain('✓');
    expect(text).toContain('Navigated to');
    el.remove();
  });

  it('renders rejected state with ✗ + reasonCode + message', async () => {
    const el = document.createElement('jf-navigation-receipt');
    el.setAttribute('outcome', 'rejected');
    el.setAttribute('target', 'core.bulk-reindex');
    el.setAttribute('address-kind', 'invoke');
    el.setAttribute('reason-code', 'confirmation-required');
    el.setAttribute('message', 'gate denied');
    document.body.appendChild(el);
    await settle(el);
    const text = el.shadowRoot?.textContent ?? '';
    expect(text).toContain('✗');
    expect(text).toContain('Invocation rejected');
    expect(text).toContain('core.bulk-reindex');
    expect(text).toContain('confirmation-required');
    expect(text).toContain('gate denied');
    el.remove();
  });

  it('renders nothing when both target and message are empty', async () => {
    const el = document.createElement('jf-navigation-receipt');
    el.setAttribute('outcome', 'extracted');
    document.body.appendChild(el);
    await settle(el);
    const text = (el.shadowRoot?.textContent ?? '').trim();
    expect(text).toBe('');
    el.remove();
  });
});
