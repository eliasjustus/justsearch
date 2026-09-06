// @vitest-environment happy-dom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { __seedForTest, __resetForTest } from '../../i18n/errorCatalog.js';
import { getSearchState, resetSearchState, setQuery, submitSearch } from './searchState.js';

describe('search failure consumer (906)', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    resetSearchState();
    __seedForTest({ 'errors.INDEX_UNAVAILABLE': 'The search index is not available.' });
  });
  afterEach(() => {
    resetSearchState();
    __resetForTest();
    vi.unstubAllGlobals();
    vi.useRealTimers();
  });

  async function search(): Promise<void> {
    setQuery('report');
    submitSearch();
    await vi.runAllTimersAsync();
  }

  it('reads a typed 502, localizes it, and preserves server facts without retrying', async () => {
    const json = vi.fn().mockResolvedValue({ error: 'wire fallback', errorCode: 'INDEX_UNAVAILABLE',
      errorClass: 'TRANSIENT', i18nKey: 'errors.INDEX_UNAVAILABLE', retryable: true, requestId: 'r1' });
    const fetchMock = vi.fn().mockResolvedValue({ ok: false, status: 502, json });
    vi.stubGlobal('fetch', fetchMock);
    await search();
    expect(json).toHaveBeenCalledOnce();
    expect(fetchMock).toHaveBeenCalledOnce();
    expect(getSearchState().error).toContain('The search index is not available. Try searching again.');
    expect(getSearchState().errorInfo).toMatchObject({ code: 'INDEX_UNAVAILABLE', status: 502,
      errorClass: 'TRANSIENT', retryable: true, requestId: 'r1' });
    expect(getSearchState().isSearching).toBe(false);
  });

  it('preserves nonretryability and uses sanitized wire prose when the catalog misses', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status: 400,
      json: async () => ({ errorCode: 'FUTURE_INPUT', error: 'This query is not supported.',
        retryable: false, errorClass: 'PERMANENT' }) }));
    await search();
    expect(getSearchState().error).toContain('This query is not supported. Review the query');
    expect(getSearchState().error).not.toContain('Try searching again.');
    expect(getSearchState().errorInfo?.retryable).toBe(false);
  });

  it.each([null, [], 'proxy html', { error: '<html>proxy exception</html>' }, { i18nKey: 'toString' },
    { errorCode: 7, i18nKey: [], retryable: 'true', errorClass: {} }])(
    'uses a safe fallback for malformed envelopes: %j', async (body) => {
      vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status: 502, json: async () => body }));
      await search();
      expect(getSearchState().error).toContain('Search could not be completed. Open Health');
      expect(getSearchState().errorInfo?.retryable).toBeUndefined();
    });

  it.each(['non-json', 'network', 'null rejection'])('handles %s without displaying exception text', async (kind) => {
    const fetchMock = vi.fn();
    if (kind === 'non-json') fetchMock.mockResolvedValue({ ok: false, status: 502,
      json: async () => { throw new SyntaxError('private proxy text'); } });
    else fetchMock.mockRejectedValue(kind === 'network' ? new Error('private transport text') : null);
    vi.stubGlobal('fetch', fetchMock);
    await search();
    expect(getSearchState().error).toContain('Search could not be completed. Open Health');
    expect(getSearchState().error).not.toContain('private');
    expect(fetchMock).toHaveBeenCalledOnce();
  });

  it('drops a failure body that finishes after a newer search succeeds', async () => {
    let finish!: (value: unknown) => void;
    const json = vi.fn(() => new Promise((resolve) => { finish = resolve; }));
    const fetchMock = vi.fn().mockResolvedValueOnce({ ok: false, status: 502, json })
      .mockResolvedValue({ ok: true, json: async () => ({ results: [{ id: 'new', fields: { path: '/new' } }] }) });
    vi.stubGlobal('fetch', fetchMock);
    setQuery('old'); submitSearch();
    await vi.advanceTimersByTimeAsync(0);
    expect(json).toHaveBeenCalledOnce();
    setQuery('new'); submitSearch();
    await vi.advanceTimersByTimeAsync(0);
    finish({ errorCode: 'INDEX_UNAVAILABLE', retryable: true });
    await vi.runAllTimersAsync();
    expect(getSearchState().results[0]?.id).toBe('new');
    expect(getSearchState().error).toBeNull();
    expect(getSearchState().errorInfo).toBeNull();
  });

  it('keeps cancellation silent', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new DOMException('cancelled', 'AbortError')));
    setQuery('old'); submitSearch(); setQuery('');
    await vi.runAllTimersAsync();
    expect(getSearchState().error).toBeNull();
    expect(getSearchState().isSearching).toBe(false);
  });
});
