// @vitest-environment happy-dom
import { describe, it, expect, vi } from 'vitest';
import './RouteChip.js';
import type { RouteChip } from './RouteChip.js';
import type { Availability } from '../state/availability.js';

function control(el: RouteChip): HTMLElement & { availability?: Availability } {
  return el.shadowRoot!.querySelector('jf-control') as HTMLElement & {
    availability?: Availability;
  };
}

async function mount(setup: (el: RouteChip) => void): Promise<RouteChip> {
  const el = document.createElement('jf-route-chip') as RouteChip;
  setup(el);
  document.body.appendChild(el);
  await el.updateComplete;
  const ctrl = control(el) as unknown as { updateComplete?: Promise<void> } | null;
  await ctrl?.updateComplete;
  return el;
}

function innerButton(el: RouteChip): HTMLButtonElement {
  return control(el).shadowRoot!.querySelector('button') as HTMLButtonElement;
}

describe('jf-route-chip (Search Thread tempdoc D3)', () => {
  it('renders "↵ Search" and the search aria-label for route=search', async () => {
    const el = await mount((c) => {
      c.route = 'search';
    });
    expect(el.shadowRoot!.textContent).toContain('Search');
    const btn = innerButton(el);
    expect(btn.getAttribute('aria-label')).toBe('Enter will search your files');
  });

  it('renders "↵ Ask" and the ask aria-label for route=ask', async () => {
    const el = await mount((c) => {
      c.route = 'ask';
    });
    expect(el.shadowRoot!.textContent).toContain('Ask');
    const btn = innerButton(el);
    expect(btn.getAttribute('aria-label')).toBe('Enter will ask the AI');
  });

  it('clicking the chip (not pinned) dispatches a bubbling, composed route-toggle event', async () => {
    const el = await mount((c) => {
      c.route = 'search';
      c.pinned = false;
    });
    const spy = vi.fn();
    document.addEventListener('route-toggle', spy);
    innerButton(el).click();
    expect(spy).toHaveBeenCalledTimes(1);
    const evt = spy.mock.calls[0]![0] as CustomEvent;
    expect(evt.bubbles).toBe(true);
    expect(evt.composed).toBe(true);
    document.removeEventListener('route-toggle', spy);
  });

  it('carries the "other way" hint via title when not pinned', async () => {
    const el = await mount((c) => {
      c.route = 'search';
      c.pinned = false;
    });
    expect(control(el).getAttribute('title')).toBe('Ctrl+Enter sends the other way');
  });

  it('pinned mode forces the displayed route to search regardless of `route`', async () => {
    const el = await mount((c) => {
      c.route = 'ask';
      c.pinned = true;
      c.askAvailability = { kind: 'unavailable', reason: 'AI is offline' };
    });
    expect(el.shadowRoot!.textContent).toContain('Search');
    const btn = innerButton(el);
    expect(btn.getAttribute('aria-label')).toBe('Enter will search your files');
  });

  it('pinned mode forwards askAvailability to the composed jf-control', async () => {
    const availability: Availability = { kind: 'unavailable', reason: 'AI is offline' };
    const el = await mount((c) => {
      c.pinned = true;
      c.askAvailability = availability;
    });
    expect(control(el).availability).toEqual(availability);
  });

  it('pinned mode does NOT emit route-toggle when the unavailable side is activated', async () => {
    const el = await mount((c) => {
      c.pinned = true;
      c.askAvailability = { kind: 'unavailable', reason: 'AI is offline' };
    });
    const spy = vi.fn();
    document.addEventListener('route-toggle', spy);
    innerButton(el).click();
    expect(spy).not.toHaveBeenCalled();
    document.removeEventListener('route-toggle', spy);
  });

  it('pinned mode falls back to a reason when askAvailability is null', async () => {
    const el = await mount((c) => {
      c.pinned = true;
      c.askAvailability = null;
    });
    expect(control(el).availability?.kind).toBe('unavailable');
  });
});
