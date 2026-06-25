// @vitest-environment happy-dom

/**
 * Tempdoc 521 §16.4 deeper — onSettingChanged channel tests.
 *
 * The walkthrough card binds `onSettingChanged:<key>` step completion
 * to this subscription. The vocabulary is V1-fixed: activeThemeId,
 * activeProfileId, activeLayoutId, viewerAudience. Unknown keys are
 * silently no-op.
 */

import { describe, it, expect, beforeEach, vi } from 'vitest';
import {
  __resetUserStateForTest,
  onSettingChanged,
  mutateDocument,
} from './UserStateDocument.js';

function setActiveTheme(id: string | null): void {
  mutateDocument((doc) => ({ ...doc, activeThemeId: id }));
}

beforeEach(() => {
  __resetUserStateForTest();
});

describe('onSettingChanged', () => {
  it('does NOT fire on subscribe (initial value suppressed)', () => {
    const handler = vi.fn();
    onSettingChanged('activeThemeId', handler);
    expect(handler).not.toHaveBeenCalled();
  });

  it('fires when the named setting transitions', () => {
    const handler = vi.fn();
    onSettingChanged('activeThemeId', handler);
    setActiveTheme('dark-themed');
    expect(handler).toHaveBeenCalledTimes(1);
  });

  it('does not fire on no-op writes (same value)', () => {
    setActiveTheme('a');
    const handler = vi.fn();
    onSettingChanged('activeThemeId', handler);
    setActiveTheme('a');
    expect(handler).not.toHaveBeenCalled();
  });

  it('unknown keys are no-op subscriptions', () => {
    const handler = vi.fn();
    const off = onSettingChanged('not.a.real.key', handler);
    setActiveTheme('whatever');
    expect(handler).not.toHaveBeenCalled();
    off();
  });

  // Tempdoc 521 §22 Phase C — dotted-path resolver enables plugins to
  // gate walkthrough completion on their own settings slice without
  // touching the host. Walks `pluginSettings.<id>.<key>` symmetrically
  // with the four V1 keys.
  it('resolves dotted paths into pluginSettings', () => {
    const handler = vi.fn();
    onSettingChanged('pluginSettings.acme.configured', handler);
    mutateDocument((doc) => ({
      ...doc,
      pluginSettings: { ...(doc.pluginSettings ?? {}), acme: { configured: true } },
    }));
    expect(handler).toHaveBeenCalledTimes(1);
  });

  it('resolves nested dotted paths into userConfig', () => {
    const handler = vi.fn();
    onSettingChanged('userConfig.activeLayoutId', handler);
    mutateDocument((doc) => ({
      ...doc,
      userConfig: { ...doc.userConfig, activeLayoutId: 'core.split' },
    }));
    expect(handler).toHaveBeenCalledTimes(1);
  });

  it('unsubscribe stops firing', () => {
    const handler = vi.fn();
    const off = onSettingChanged('activeThemeId', handler);
    setActiveTheme('one');
    off();
    setActiveTheme('two');
    expect(handler).toHaveBeenCalledTimes(1);
  });
});
