// @vitest-environment happy-dom
import { describe, it, expect, vi, afterEach } from 'vitest';
import { surfaceTransitionsEnabled, startSurfaceTransition } from './viewTransition.js';

type VTDoc = Document & { startViewTransition?: (cb: () => Promise<void> | void) => unknown };

afterEach(() => {
  delete (document as unknown as { startViewTransition?: unknown }).startViewTransition;
  vi.restoreAllMocks();
});

function stubReducedMotion(reduce: boolean): void {
  vi.spyOn(window, 'matchMedia').mockReturnValue({
    matches: reduce,
    media: '(prefers-reduced-motion: reduce)',
    addEventListener: () => {},
    removeEventListener: () => {},
  } as unknown as MediaQueryList);
}

describe('viewTransition (tempdoc 609 §R T1.1)', () => {
  it('surfaceTransitionsEnabled is false when the API is absent', () => {
    stubReducedMotion(false);
    expect(surfaceTransitionsEnabled()).toBe(false);
  });

  it('surfaceTransitionsEnabled is false when reduced-motion is preferred (even with the API)', () => {
    (document as VTDoc).startViewTransition = vi.fn();
    stubReducedMotion(true);
    expect(surfaceTransitionsEnabled()).toBe(false);
  });

  it('surfaceTransitionsEnabled is true when the API is present and motion is allowed', () => {
    (document as VTDoc).startViewTransition = vi.fn();
    stubReducedMotion(false);
    expect(surfaceTransitionsEnabled()).toBe(true);
  });

  it('startSurfaceTransition is a no-op (returns false, does not call the API) when disabled', () => {
    const spy = vi.fn();
    (document as VTDoc).startViewTransition = spy;
    stubReducedMotion(true); // disabled via reduced motion
    const host = { updateComplete: Promise.resolve(true) };
    expect(startSurfaceTransition(host)).toBe(false);
    expect(spy).not.toHaveBeenCalled();
  });

  it('startSurfaceTransition starts a transition and awaits the host update when enabled', async () => {
    let captured: (() => Promise<void> | void) | null = null;
    (document as unknown as { startViewTransition: unknown }).startViewTransition = vi.fn(
      (cb: () => Promise<void> | void) => {
        captured = cb;
        return {};
      },
    );
    stubReducedMotion(false);
    let resolved = false;
    const host = { updateComplete: Promise.resolve(true).then(() => void (resolved = true)) };

    expect(startSurfaceTransition(host)).toBe(true);
    expect(captured).not.toBeNull();
    // The callback awaits the host's updateComplete (the pending Lit flush) before resolving.
    await captured!();
    expect(resolved).toBe(true);
  });
});
