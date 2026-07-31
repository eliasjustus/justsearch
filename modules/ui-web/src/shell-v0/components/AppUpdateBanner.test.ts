// @vitest-environment happy-dom

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const invoke = vi.fn();
vi.mock('@tauri-apps/api/core', () => ({ invoke }));

import './AppUpdateBanner.js';
import { __resetForTest, checkForAppUpdate } from '../state/appUpdateState.js';
import { NAVIGATE_TO_SURFACE_EVENT } from '../controllers/navigateRequest.js';

describe('AppUpdateBanner', () => {
  beforeEach(() => {
    __resetForTest();
    invoke.mockReset();
  });

  afterEach(() => {
    document.body.innerHTML = '';
  });

  it('reveals an available release and routes review through Settings', async () => {
    invoke.mockResolvedValue({
      state: 'available',
      currentVersion: '1.0.0',
      availableVersion: '1.1.0',
    });
    const element = document.createElement('jf-app-update-banner') as HTMLElement & {
      updateComplete: Promise<unknown>;
    };
    document.body.appendChild(element);
    await checkForAppUpdate();
    await element.updateComplete;

    const navigate = vi.fn();
    document.addEventListener(NAVIGATE_TO_SURFACE_EVENT, navigate, { once: true });
    const action = element.shadowRoot?.querySelector('jf-button') as
      | (HTMLElement & { onActivate: () => void })
      | null;
    action?.onActivate();

    expect(element.hasAttribute('hidden')).toBe(false);
    expect(element.shadowRoot?.textContent).toContain('JustSearch 1.1.0 is available.');
    expect(navigate).toHaveBeenCalledOnce();
    expect((navigate.mock.calls[0]?.[0] as CustomEvent).detail).toEqual({
      surfaceId: 'core.settings-surface',
    });
  });

  it('stays absent for non-actionable status', async () => {
    invoke.mockResolvedValue({ state: 'up_to_date', currentVersion: '1.0.0' });
    const element = document.createElement('jf-app-update-banner') as HTMLElement & {
      updateComplete: Promise<unknown>;
    };
    document.body.appendChild(element);
    await checkForAppUpdate();
    await element.updateComplete;

    expect(element.hasAttribute('hidden')).toBe(true);
    expect(element.shadowRoot?.querySelector('[data-testid="app-update-banner"]')).toBeNull();
  });
});

