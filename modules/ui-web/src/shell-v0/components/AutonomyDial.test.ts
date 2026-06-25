// @vitest-environment happy-dom

/**
 * §32 U1 — <jf-autonomy-dial> render tests.
 */

import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { AutonomyDial } from './AutonomyDial.js';
import {
  getAutonomyLevel,
  __resetAutonomyForTest,
} from '../substrates/autonomy/index.js';

void AutonomyDial;

let host: HTMLElement;

beforeEach(() => {
  globalThis.localStorage?.clear();
  __resetAutonomyForTest();
  host = document.createElement('jf-autonomy-dial');
  document.body.appendChild(host);
});

afterEach(() => {
  host.remove();
});

async function flush(): Promise<void> {
  await Promise.resolve();
  await Promise.resolve();
}

describe('<jf-autonomy-dial> (§32 U1)', () => {
  it('renders three segments with assist active by default', async () => {
    await flush();
    expect(host.shadowRoot?.querySelectorAll('.seg').length).toBe(3);
    expect(
      host.shadowRoot
        ?.querySelector('[data-testid="autonomy-assist"]')
        ?.hasAttribute('data-active'),
    ).toBe(true);
  });

  it('clicking a segment sets the level and moves the active state', async () => {
    await flush();
    (
      host.shadowRoot?.querySelector(
        '[data-testid="autonomy-watch"]',
      ) as HTMLButtonElement
    ).click();
    await flush();
    expect(getAutonomyLevel()).toBe('watch');
    expect(
      host.shadowRoot
        ?.querySelector('[data-testid="autonomy-watch"]')
        ?.hasAttribute('data-active'),
    ).toBe(true);
    expect(
      host.shadowRoot
        ?.querySelector('[data-testid="autonomy-assist"]')
        ?.hasAttribute('data-active'),
    ).toBe(false);
  });

  // §32 unify — compact variant: segments only, no title/hint block.
  it('compact variant renders the segments but omits the title + hint', async () => {
    const el = document.createElement('jf-autonomy-dial') as HTMLElement & {
      compact?: boolean;
    };
    el.compact = true;
    document.body.appendChild(el);
    await flush();
    expect(el.shadowRoot?.querySelectorAll('.seg').length).toBe(3);
    expect(el.shadowRoot?.querySelector('.title')).toBeNull();
    expect(el.shadowRoot?.querySelector('[data-testid="autonomy-hint"]')).toBeNull();
    // per-segment hint is still available via the title tooltip
    expect(
      el.shadowRoot
        ?.querySelector('[data-testid="autonomy-assist"]')
        ?.getAttribute('title'),
    ).toBeTruthy();
    el.remove();
  });
});
