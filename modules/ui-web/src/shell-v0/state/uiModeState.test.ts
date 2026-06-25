// @vitest-environment happy-dom
/** uiModeState — the app-wide Simple/Advanced authority (tempdoc 557 Q8). */
import { describe, it, expect, beforeEach } from 'vitest';
import {
  getUiMode,
  isAdvancedMode,
  setUiMode,
  subscribeUiMode,
  __resetUiModeForTest,
} from './uiModeState.js';

describe('uiModeState', () => {
  beforeEach(() => __resetUiModeForTest());

  it('defaults to simple (hide advanced affordances until known)', () => {
    expect(getUiMode()).toBe('simple');
    expect(isAdvancedMode()).toBe(false);
  });

  it('resolves only "advanced" to advanced; everything else is simple', () => {
    setUiMode('advanced');
    expect(isAdvancedMode()).toBe(true);
    setUiMode('simple');
    expect(isAdvancedMode()).toBe(false);
    setUiMode(undefined);
    expect(getUiMode()).toBe('simple');
    setUiMode('garbage');
    expect(getUiMode()).toBe('simple');
  });

  it('notifies subscribers on change (and once synchronously on subscribe)', () => {
    const seen: string[] = [];
    const unsub = subscribeUiMode((m) => seen.push(m));
    expect(seen).toEqual(['simple']); // sync on subscribe
    setUiMode('advanced');
    expect(seen).toEqual(['simple', 'advanced']);
    setUiMode('advanced'); // no-op (unchanged) → no extra notify
    expect(seen).toEqual(['simple', 'advanced']);
    unsub();
    setUiMode('simple');
    expect(seen).toEqual(['simple', 'advanced']); // unsubscribed
  });
});
