// @vitest-environment happy-dom

/**
 * §32 U1 — <jf-autonomy-dial> render tests.
 */

import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { AutonomyDial } from './AutonomyDial.js';
import {
  getAutonomyLevel,
  hasAutoConsent,
  setAutoConsent,
  __resetAutonomyForTest,
} from '../substrates/autonomy/index.js';
import './ConfirmDialog.js';

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

  // Search Thread S7 (tempdoc decision 6) — the FIRST switch to Auto opens a consent modal;
  // every later switch is silent, and canceling never flips the dial.
  describe('Auto-mode consent (S7 decision 6)', () => {
    afterEach(() => {
      document.body.querySelectorAll('jf-confirm-dialog').forEach((el) => el.remove());
    });

    it('selecting Auto the FIRST time opens a consent modal and does not yet change the level', async () => {
      await flush();
      (
        host.shadowRoot?.querySelector('[data-testid="autonomy-auto"]') as HTMLButtonElement
      ).click();
      await flush();
      expect(getAutonomyLevel()).toBe('assist');
      const dialog = document.body.querySelector('jf-confirm-dialog') as
        | (HTMLElement & { open: boolean; message: string })
        | null;
      expect(dialog, 'the shared confirm-dialog modal mounts').not.toBeNull();
      expect(dialog?.open).toBe(true);
      expect(dialog?.message).toContain('without asking');
      expect(dialog?.message).toContain('Irreversible writes still confirm');
    });

    it('confirming the modal persists the consent flag and applies the level', async () => {
      await flush();
      (
        host.shadowRoot?.querySelector('[data-testid="autonomy-auto"]') as HTMLButtonElement
      ).click();
      await flush();
      const dialog = document.body.querySelector('jf-confirm-dialog')!;
      dialog.dispatchEvent(new CustomEvent('confirm'));
      await flush();
      await flush();
      expect(getAutonomyLevel()).toBe('auto');
      expect(hasAutoConsent()).toBe(true);
    });

    it('canceling the modal leaves the dial on the previous level (never optimistically flipped)', async () => {
      await flush();
      (
        host.shadowRoot?.querySelector('[data-testid="autonomy-auto"]') as HTMLButtonElement
      ).click();
      await flush();
      const dialog = document.body.querySelector('jf-confirm-dialog')!;
      dialog.dispatchEvent(new CustomEvent('cancel'));
      await flush();
      await flush();
      expect(getAutonomyLevel()).toBe('assist');
      expect(hasAutoConsent()).toBe(false);
      expect(
        host.shadowRoot
          ?.querySelector('[data-testid="autonomy-assist"]')
          ?.hasAttribute('data-active'),
      ).toBe(true);
    });

    it('a SECOND switch to Auto (consent already recorded) applies silently — no modal', async () => {
      setAutoConsent();
      await flush();
      (
        host.shadowRoot?.querySelector('[data-testid="autonomy-auto"]') as HTMLButtonElement
      ).click();
      await flush();
      expect(getAutonomyLevel()).toBe('auto');
      expect(document.body.querySelector('jf-confirm-dialog')).toBeNull();
    });
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
