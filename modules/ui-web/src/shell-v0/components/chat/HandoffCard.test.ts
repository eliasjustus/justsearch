// @vitest-environment happy-dom
//
// Tempdoc 585 §D Phase 2 (D2) — the multi-agent handoff card composes the status-badge atom.

import { describe, expect, it } from 'vitest';
import './HandoffCard.js';
import type { HandoffCard } from './HandoffCard.js';

async function settle(el: Element): Promise<void> {
  await (el as unknown as { updateComplete: Promise<unknown> }).updateComplete;
}

describe('HandoffCard', () => {
  it('renders both agent roles as jf-status-badge atoms plus the reason', async () => {
    const el = document.createElement('jf-handoff-card') as HandoffCard;
    el.from = 'researcher';
    el.to = 'writer';
    el.reason = 'drafting the summary';
    document.body.appendChild(el);
    await settle(el);

    const badges = Array.from(el.shadowRoot?.querySelectorAll('jf-status-badge') ?? []);
    // Two role badges (from → to), each the ONE status-badge atom (no re-authored badge CSS).
    expect(badges.length).toBe(2);
    expect(badges.map((b) => b.getAttribute('label'))).toEqual(['researcher', 'writer']);
    expect(badges.every((b) => b.getAttribute('origin') === 'agent')).toBe(true);

    const text = (el.shadowRoot?.textContent ?? '').replace(/\s+/g, ' ');
    expect(text).toContain('Handoff');
    expect(text).toContain('drafting the summary');

    // The group carries an accessible name spanning the transition.
    const group = el.shadowRoot?.querySelector('[role="group"]');
    expect(group?.getAttribute('aria-label')).toBe('Handoff from researcher to writer');
    el.remove();
  });

  it('omits the reason node when no reason is provided', async () => {
    const el = document.createElement('jf-handoff-card') as HandoffCard;
    el.from = 'a';
    el.to = 'b';
    document.body.appendChild(el);
    await settle(el);
    expect(el.shadowRoot?.querySelector('.reason')).toBeNull();
    el.remove();
  });
});
