import { describe, it, expect, vi } from 'vitest';
import { transportChrome } from './TransportChrome.js';

describe('TransportChrome', () => {
  it('returns correct chrome for each known transport', () => {
    const known = [
      'URL_BAR', 'URL_DEEPLINK', 'LLM_EMISSION', 'PALETTE', 'BUTTON',
      'RAIL', 'AGENT_LOOP', 'MCP', 'PLUGIN_EMITTED', 'SYSTEM_INTERNAL',
      'SCHEDULED', 'RULE_ENGINE',
    ];
    for (const tag of known) {
      const chrome = transportChrome(tag);
      expect(chrome.icon).toBeTruthy();
      expect(chrome.label).toBeTruthy();
      expect(chrome.cssClass).toMatch(/^transport-/);
      expect(chrome.label).not.toBe('Unknown');
    }
  });

  it('BUTTON has mouse icon', () => {
    expect(transportChrome('BUTTON').icon).toBe('🖱');
    expect(transportChrome('BUTTON').label).toBe('Button');
  });

  it('AGENT_LOOP has robot icon', () => {
    expect(transportChrome('AGENT_LOOP').icon).toBe('🤖');
    expect(transportChrome('AGENT_LOOP').label).toBe('Agent');
  });

  it('unknown transport returns default chrome with humanized label', () => {
    const spy = vi.spyOn(console, 'warn').mockImplementation(() => {});
    const chrome = transportChrome('NEVER_SEEN_BEFORE');
    expect(chrome.icon).toBe('❓');
    expect(chrome.label).toBe('never seen before');
    expect(chrome.cssClass).toBe('transport-unknown');
    spy.mockRestore();
  });

  it('logs unknown transport only once', () => {
    const spy = vi.spyOn(console, 'warn').mockImplementation(() => {});
    transportChrome('ONCE_ONLY_TEST');
    transportChrome('ONCE_ONLY_TEST');
    const calls = spy.mock.calls.filter((c) =>
      String(c[0]).includes('ONCE_ONLY_TEST'),
    );
    expect(calls.length).toBe(1);
    spy.mockRestore();
  });
});
