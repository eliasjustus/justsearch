import { describe, expect, it, vi } from 'vitest';

const resolveApiEndpoint = vi.fn();

vi.mock('../api/http', () => ({
  resolveApiEndpoint,
}));

describe('resolveBootApiBase', () => {
  it('uses the resolved backend base URL instead of the page origin', async () => {
    resolveApiEndpoint.mockResolvedValue({
      port: 8080,
      baseUrl: 'http://127.0.0.1:8080',
      source: 'tauri',
    });

    const { resolveBootApiBase } = await import('./apiBase');

    await expect(resolveBootApiBase()).resolves.toBe('http://127.0.0.1:8080');
  });

  it('preserves unresolved endpoint state for the boot error path', async () => {
    resolveApiEndpoint.mockResolvedValue({
      port: null,
      baseUrl: null,
      source: 'unresolved',
    });

    const { resolveBootApiBase } = await import('./apiBase');

    await expect(resolveBootApiBase()).resolves.toBeNull();
  });
});
