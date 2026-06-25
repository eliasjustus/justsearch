import { describe, it, expect } from 'vitest';
import { known, UNKNOWN, renderObserved } from './known.js';

describe('renderObserved (595 §4.3) — total fold over (value, stability)', () => {
  const r = (m: ReturnType<typeof known<number>> | typeof UNKNOWN, provisional: boolean) =>
    renderObserved(
      m,
      provisional,
      (v) => `settled:${v}`,
      () => 'provisional',
      () => 'unknown',
    );

  it('Unknown ⇒ onUnknown (regardless of provisional)', () => {
    expect(r(UNKNOWN, false)).toBe('unknown');
    expect(r(UNKNOWN, true)).toBe('unknown');
  });

  it('Known + settled ⇒ onSettled(value)', () => {
    expect(r(known(42), false)).toBe('settled:42');
  });

  it('Known + provisional ⇒ onProvisional — the value is NOT shown as settled', () => {
    // The §1.2 crux: a worker fallback reports known(0); whenKnown would render a
    // settled "0", but renderObserved diverts it to the provisional branch.
    expect(r(known(0), true)).toBe('provisional');
  });
});
