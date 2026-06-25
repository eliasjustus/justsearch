// @vitest-environment happy-dom

/**
 * Slice 486 G35 — clipboardCopy utility tests.
 *
 * Mocks `navigator.clipboard.writeText` to assert success / failure
 * paths return the expected booleans without throwing.
 */

import { describe, it, expect, vi, afterEach } from 'vitest';
import { copyToClipboard } from './clipboardCopy.js';

describe('clipboardCopy', () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('returns true on successful write', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined);
    Object.defineProperty(navigator, 'clipboard', {
      value: { writeText },
      configurable: true,
    });
    const ok = await copyToClipboard('hello');
    expect(ok).toBe(true);
    expect(writeText).toHaveBeenCalledWith('hello');
  });

  it('returns false when writeText rejects (permission denied)', async () => {
    const writeText = vi.fn().mockRejectedValue(
      new DOMException('NotAllowedError', 'NotAllowedError'),
    );
    Object.defineProperty(navigator, 'clipboard', {
      value: { writeText },
      configurable: true,
    });
    const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => {});
    const ok = await copyToClipboard('hello');
    expect(ok).toBe(false);
    expect(warnSpy).toHaveBeenCalled();
  });

  it('returns false when navigator.clipboard is missing', async () => {
    Object.defineProperty(navigator, 'clipboard', {
      value: undefined,
      configurable: true,
    });
    const ok = await copyToClipboard('hello');
    expect(ok).toBe(false);
  });

  it('returns false when writeText is not a function', async () => {
    Object.defineProperty(navigator, 'clipboard', {
      value: { writeText: 'not-a-function' },
      configurable: true,
    });
    const ok = await copyToClipboard('hello');
    expect(ok).toBe(false);
  });

  it('does not throw on any failure path', async () => {
    Object.defineProperty(navigator, 'clipboard', {
      value: {
        writeText: vi.fn().mockImplementation(() => {
          throw new Error('synthetic sync throw');
        }),
      },
      configurable: true,
    });
    vi.spyOn(console, 'warn').mockImplementation(() => {});
    // Sync-throw is caught by the async wrapper and returns false.
    await expect(copyToClipboard('hello')).resolves.toBe(false);
  });
});
