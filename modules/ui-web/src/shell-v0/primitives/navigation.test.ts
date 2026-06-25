// @vitest-environment happy-dom
import { describe, it, expect } from 'vitest';
import { deriveFocus, NavigationController, MIN_VISIBLE, type Landmark } from './navigation.js';
import type { ReactiveControllerHost } from 'lit';

// A three-item run: a short user turn, a tool step, and a tall answer, laid out top→bottom over the
// scrollable content. The FOCUS facet (deriveFocus) maps a reading WINDOW to the topmost item that is
// meaningfully on screen — the §21 replacement for the IntersectionObserver scroll-spy.
const RUN: Landmark[] = [
  { id: 'u1', extent: { topFrac: 0.0, botFrac: 0.1 } },
  { id: 't1', extent: { topFrac: 0.1, botFrac: 0.3 } },
  { id: 'a1', extent: { topFrac: 0.3, botFrac: 1.0 } },
];

describe('navigation — §21 FOCUS (deriveFocus)', () => {
  it('picks the topmost landmark with ≥ MIN_VISIBLE of itself in the window', () => {
    // Window over the lower half of the tool step + the top of the answer: half of t1 shows and most of
    // the window is the answer — both clear MIN_VISIBLE, so the TOPMOST (tool step) wins.
    expect(deriveFocus(RUN, { topFrac: 0.2, botFrac: 0.6 })).toBe('t1');
    // Window fully inside the answer body → the answer is the focus (a long-answer stretch stays bound).
    expect(deriveFocus(RUN, { topFrac: 0.5, botFrac: 0.8 })).toBe('a1');
    // Window at the very top → the user turn.
    expect(deriveFocus(RUN, { topFrac: 0.0, botFrac: 0.12 })).toBe('u1');
  });

  it('ignores a landmark only barely peeking (the Spike B 1px-peek defect any-overlap would mis-pick)', () => {
    // The window starts 1% into the tool step (t1 spans 0.1..0.3, so only 0.01/0.20 = 5% of t1 shows —
    // below MIN_VISIBLE 0.1). Naive any-overlap would pick t1; the threshold predicate picks the answer.
    expect(MIN_VISIBLE).toBe(0.1);
    expect(deriveFocus(RUN, { topFrac: 0.29, botFrac: 0.7 })).toBe('a1');
  });

  it('returns the topmost item when the column is not scrollable (window null = all in view)', () => {
    expect(deriveFocus(RUN, null)).toBe('u1');
  });

  it('returns null for an empty run', () => {
    expect(deriveFocus([], { topFrac: 0, botFrac: 1 })).toBeNull();
  });

  it('honors a custom minVisibleFrac threshold', () => {
    // 5% of t1 visible: rejected at the default 0.1, accepted at 0.04.
    expect(deriveFocus(RUN, { topFrac: 0.29, botFrac: 0.7 }, 0.04)).toBe('t1');
  });
});

// A minimal ReactiveControllerHost stand-in: the controller only calls addController + requestUpdate.
function fakeHost(): ReactiveControllerHost & HTMLElement {
  let updates = 0;
  const host = {
    addController() {},
    removeController() {},
    requestUpdate() {
      updates++;
    },
    get updateComplete() {
      return Promise.resolve(true);
    },
    get __updates() {
      return updates;
    },
  };
  return host as unknown as ReactiveControllerHost & HTMLElement;
}

describe('navigation — §21 CONTROL (the live/pinned intent)', () => {
  it('is live by default → FOCUS is the derived item; pinned has no hold', () => {
    const nav = new NavigationController(fakeHost(), {
      scrollEl: () => null,
      spineEl: () => null,
      active: () => true,
    });
    nav.landmarks = RUN;
    nav.viewport = { topFrac: 0.5, botFrac: 0.8 };
    expect(nav.pinned).toBeNull();
    expect(nav.activeId).toBe('a1'); // derived
  });

  it('a jump-pin owns the FOCUS even when the derived focus differs; a user scroll releases it to live', () => {
    // A scroll container with the two jump targets so jumpTo finds a real element to pin to.
    const conv = document.createElement('div');
    for (const id of ['t1', 'a1']) {
      const el = document.createElement('div');
      el.setAttribute('data-item-id', id);
      conv.appendChild(el);
    }
    const nav = new NavigationController(fakeHost(), {
      scrollEl: () => conv,
      spineEl: () => null,
      active: () => true,
    });
    nav.landmarks = RUN;
    nav.viewport = { topFrac: 0.5, botFrac: 0.8 }; // derived focus would be a1
    nav.jumpTo('t1');
    expect(nav.pinned).toBe('t1');
    expect(nav.activeId).toBe('t1'); // pinned overrides the derived a1
    nav.onUserScroll();
    expect(nav.pinned).toBeNull();
    expect(nav.activeId).toBe('a1'); // back to derived
  });
});

describe('navigation — §21 AFFORDANCE (the minimap-as-scrollbar)', () => {
  it('dragTo maps Δy to scrollTop as the exact inverse of the viewport window (Spike A)', () => {
    const conv = { scrollTop: 0, clientHeight: 200, scrollHeight: 1000 } as unknown as HTMLElement;
    const nav = new NavigationController(fakeHost(), {
      scrollEl: () => conv,
      spineEl: () => null,
      active: () => true,
    });
    nav.trackPx = 400; // measured spine-track height
    nav.beginDrag(100); // grab at y=100 with scrollTop 0
    nav.dragTo(180); // pointer moved +80px down the 400px track
    // Δscroll = (Δy / trackPx) · scrollHeight = (80 / 400) · 1000 = 200 — the exact inverse mapping.
    expect(conv.scrollTop).toBe(200);
  });

  it('a thumb drag releases any active pin (grabbing the scrollbar is free navigation)', () => {
    const conv = {
      scrollTop: 0,
      clientHeight: 200,
      scrollHeight: 1000,
      querySelectorAll: () => [],
    } as unknown as HTMLElement;
    const nav = new NavigationController(fakeHost(), {
      scrollEl: () => conv,
      spineEl: () => null,
      active: () => true,
    });
    // Force a pinned intent, then begin a drag → the pin must release.
    (nav as unknown as { intent: { mode: string; pinnedId: string | null } }).intent = {
      mode: 'pinned',
      pinnedId: 'a1',
    };
    expect(nav.pinned).toBe('a1');
    nav.beginDrag(50);
    expect(nav.pinned).toBeNull();
  });

  it('nudge scrolls by line / page and jumps to the ends', () => {
    const conv = { scrollTop: 500, clientHeight: 200, scrollHeight: 1000 } as unknown as HTMLElement;
    const nav = new NavigationController(fakeHost(), {
      scrollEl: () => conv,
      spineEl: () => null,
      active: () => true,
    });
    nav.nudge('page-down'); // + clientHeight (200)
    expect(conv.scrollTop).toBe(700);
    nav.nudge('line-up'); // − max(40, 10% of 200 = 20) = 40
    expect(conv.scrollTop).toBe(660);
    nav.nudge('home');
    expect(conv.scrollTop).toBe(0);
    nav.nudge('end');
    expect(conv.scrollTop).toBe(1000); // = scrollHeight (the browser clamps to the max in practice)
  });
});
