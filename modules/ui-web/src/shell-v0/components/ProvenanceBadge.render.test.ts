// @vitest-environment happy-dom

/**
 * Tests for slice 471's ProvenanceBadge popover.
 *
 * Covers:
 *  - hidden when no override active
 *  - rendered when override active; shows summary
 *  - reactive update on subscribed userConfig change
 *  - click reverts all overrides
 */

import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import './ProvenanceBadge.js';
import {
  __resetUserConfigForTest,
  getUserConfig,
  setSurfaceOverride,
} from '../state/userConfigState.js';

async function nextFrame(): Promise<void> {
  await new Promise<void>((resolve) => requestAnimationFrame(() => resolve()));
}

describe('ProvenanceBadge (slice 471)', () => {
  let host: HTMLElement;

  beforeEach(() => {
    __resetUserConfigForTest();
    host = document.createElement('div');
    document.body.appendChild(host);
  });

  afterEach(() => {
    host.remove();
    __resetUserConfigForTest();
  });

  it('renders nothing when no override is active', async () => {
    const el = document.createElement('jf-provenance-badge');
    host.appendChild(el);
    await (el as HTMLElement & { updateComplete?: Promise<void> })
      .updateComplete;
    const badge = el.shadowRoot?.querySelector('jf-control');
    expect(badge).toBeNull();
  });

  it('renders the badge when a single surface override is active', async () => {
    setSurfaceOverride('core.library-surface', 'acme.alt-library-surface');
    const el = document.createElement('jf-provenance-badge');
    host.appendChild(el);
    await (el as HTMLElement & { updateComplete?: Promise<void> })
      .updateComplete;
    const badge = el.shadowRoot?.querySelector('jf-control');
    expect(badge).not.toBeNull();
    expect(badge?.textContent ?? '').toContain('core.library-surface');
    expect(badge?.textContent ?? '').toContain('acme.alt-library-surface');
  });

  it('summarizes when multiple overrides are active', async () => {
    setSurfaceOverride('core.library-surface', 'acme.alt-library-surface');
    setSurfaceOverride('core.brain-surface', 'acme.alt-brain-surface');
    const el = document.createElement('jf-provenance-badge');
    host.appendChild(el);
    await (el as HTMLElement & { updateComplete?: Promise<void> })
      .updateComplete;
    const badge = el.shadowRoot?.querySelector('jf-control');
    expect(badge?.textContent ?? '').toMatch(/2 surface overrides/);
  });

  it('updates reactively when userConfig mutates', async () => {
    const el = document.createElement('jf-provenance-badge');
    host.appendChild(el);
    const updateComplete = el as HTMLElement & {
      updateComplete?: Promise<void>;
    };
    await updateComplete.updateComplete;
    expect(el.shadowRoot?.querySelector('jf-control')).toBeNull();

    setSurfaceOverride('core.library-surface', 'acme.alt-library-surface');
    await updateComplete.updateComplete;
    await nextFrame();
    expect(el.shadowRoot?.querySelector('jf-control')).not.toBeNull();
  });

  it('click reverts all overrides', async () => {
    setSurfaceOverride('core.library-surface', 'acme.alt-library-surface');
    setSurfaceOverride('core.brain-surface', 'acme.alt-brain-surface');
    const el = document.createElement('jf-provenance-badge');
    host.appendChild(el);
    const updateComplete = el as HTMLElement & {
      updateComplete?: Promise<void>;
    };
    await updateComplete.updateComplete;
    const badge = el.shadowRoot?.querySelector('jf-control') as HTMLElement | null;
    expect(badge).not.toBeNull();
    // 559 Authority V — the revert is a real control; activate its inner button.
    await (badge as HTMLElement & { updateComplete?: Promise<void> }).updateComplete;
    const btn = badge!.shadowRoot?.querySelector('button') as HTMLButtonElement;
    btn.click();
    expect(getUserConfig().surfaceOverride).toBeUndefined();
  });
});
