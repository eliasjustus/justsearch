// @vitest-environment happy-dom

/**
 * Tempdoc 521 §16.4 deeper — PluginRegistry.onInstalled subscription.
 * Walkthrough steps with `completionEvent: extensionInstalled:<pluginId>`
 * rely on this channel.
 */

import { describe, it, expect, vi } from 'vitest';
import { PluginRegistry } from './PluginRegistry.js';
import {
  PLUGIN_CONTRACT_VERSION,
  type PluginManifest,
  type PluginContribution,
} from './plugin-types.js';

const noopManifest = (id: string): PluginManifest => ({
  id,
  version: '1.0.0',
  displayName: id,
  contractVersion: PLUGIN_CONTRACT_VERSION,
  tagNamespace: id,
  capabilities: {},
  register: (): PluginContribution => ({}),
  unregister: () => {},
});

describe('PluginRegistry.onInstalled', () => {
  it('fires once per successful install with the plugin id', () => {
    const reg = new PluginRegistry();
    const handler = vi.fn();
    reg.onInstalled(handler);
    reg.install(noopManifest('vendor.acme'));
    expect(handler).toHaveBeenCalledTimes(1);
    expect(handler).toHaveBeenCalledWith('vendor.acme');
  });

  it('does not fire when register() throws', () => {
    const reg = new PluginRegistry();
    const handler = vi.fn();
    reg.onInstalled(handler);
    reg.install({
      ...noopManifest('vendor.broken'),
      register: () => { throw new Error('boom'); },
    });
    expect(handler).not.toHaveBeenCalled();
  });

  it('fires per install — three plugins, three fires', () => {
    const reg = new PluginRegistry();
    const handler = vi.fn();
    reg.onInstalled(handler);
    reg.install(noopManifest('a'));
    reg.install(noopManifest('b'));
    reg.install(noopManifest('c'));
    expect(handler).toHaveBeenCalledTimes(3);
    expect(handler.mock.calls.map((c) => c[0])).toEqual(['a', 'b', 'c']);
  });

  it('unsubscribe stops firing', () => {
    const reg = new PluginRegistry();
    const handler = vi.fn();
    const off = reg.onInstalled(handler);
    reg.install(noopManifest('one'));
    off();
    reg.install(noopManifest('two'));
    expect(handler).toHaveBeenCalledTimes(1);
  });
});
