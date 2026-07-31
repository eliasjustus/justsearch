// @vitest-environment happy-dom

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const { invoke, isTauriRuntime } = vi.hoisted(() => ({
  invoke: vi.fn(),
  isTauriRuntime: vi.fn(() => true),
}));
vi.mock('@tauri-apps/api/core', () => ({ invoke }));
vi.mock('../../utils/tauriRuntime.js', () => ({ isTauriRuntime }));

import {
  __resetForTest,
  checkForAppUpdate,
  getAppUpdateStatus,
  installAppUpdate,
  startAppUpdateMonitor,
  subscribeAppUpdate,
} from './appUpdateState.js';

describe('appUpdateState', () => {
  beforeEach(() => {
    __resetForTest();
    invoke.mockReset();
    isTauriRuntime.mockReturnValue(true);
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('publishes trusted host status to all chrome consumers', async () => {
    invoke.mockResolvedValueOnce({
      state: 'available',
      currentVersion: '1.0.0',
      availableVersion: '1.1.0',
      releaseSequence: 8,
    });
    const states: Array<string | undefined> = [];
    const stop = subscribeAppUpdate((status) => states.push(status?.state));

    await checkForAppUpdate();

    expect(states).toEqual([undefined, 'checking', 'available']);
    expect(getAppUpdateStatus()?.releaseSequence).toBe(8);
    stop();
  });

  it('requires an explicit install call before invoking the install command', async () => {
    invoke
      .mockResolvedValueOnce({
        state: 'available',
        currentVersion: '1.0.0',
        availableVersion: '1.1.0',
      })
      .mockResolvedValueOnce(undefined)
      .mockResolvedValueOnce({
        state: 'install_launched',
        currentVersion: '1.0.0',
        availableVersion: '1.1.0',
      });
    await checkForAppUpdate();
    expect(invoke).not.toHaveBeenCalledWith('install_app_update');

    await installAppUpdate();
    expect(invoke).toHaveBeenCalledWith('install_app_update');
  });

  it('defers the passive check until after startup', async () => {
    vi.useFakeTimers();
    invoke.mockResolvedValue({
      state: 'up_to_date',
      currentVersion: '1.0.0',
    });

    const stop = startAppUpdateMonitor(1_000);
    await vi.advanceTimersByTimeAsync(0);
    expect(invoke).toHaveBeenCalledWith('app_update_status');
    expect(invoke).not.toHaveBeenCalledWith('check_for_app_update');

    await vi.advanceTimersByTimeAsync(1_000);
    expect(invoke).toHaveBeenCalledWith('check_for_app_update');
    stop();
  });
});
