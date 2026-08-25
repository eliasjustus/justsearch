// @vitest-environment happy-dom
import { describe, it, expect, vi } from 'vitest';
import {
  anchorFractions,
  deriveFocus,
  NavigationController,
  MIN_VISIBLE,
  type Landmark,
} from './navigation.js';
import { FRAME_LATCH_FALLBACK_MS } from './frameLatch.js';
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

// Tempdoc 814 §D4 — POSITION anchoring: a gutter index must point at where a block STARTS, so the
// marker fraction is the landmark's TOP edge. (565 anchored the midpoint; 809 finding 15 measured that
// as "alignment too loose for navigation" and 814 §B.5 supersedes it knowingly.)
describe('navigation — §21 POSITION (top-edge anchoring, 814 §D4)', () => {
  it('anchors each marker at its landmark TOP edge, not its midpoint', () => {
    const f = anchorFractions(RUN);
    expect(f.get('u1')).toBe(0.0); // midpoint would be 0.05
    expect(f.get('t1')).toBe(0.1); // midpoint would be 0.2
    expect(f.get('a1')).toBe(0.3); // midpoint would be 0.65 — a tall answer's worst case
  });

  it('a tall block anchors at its first line, so the marker never drifts down with block height', () => {
    const short: Landmark[] = [{ id: 'x', extent: { topFrac: 0.4, botFrac: 0.45 } }];
    const tall: Landmark[] = [{ id: 'x', extent: { topFrac: 0.4, botFrac: 1.0 } }];
    expect(anchorFractions(short).get('x')).toBe(anchorFractions(tall).get('x'));
  });

  it('is empty for no landmarks and covers every landmark exactly once', () => {
    expect(anchorFractions([]).size).toBe(0);
    expect(anchorFractions(RUN).size).toBe(RUN.length);
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

  /**
   * Tempdoc 859 live-leg — the pin must survive the very key that set it.
   *
   * `jumpTo` moves DOM focus INTO the scroller, so from the second J/K press onward the keydown
   * ORIGINATES on a landmark inside the scroll container and bubbles through this controller's own
   * `keydown` listener on its way to the host's window-level handler. While that listener was
   * `onUserScroll` itself, every press released the pin the previous press had set — before the host
   * read `activeId` — so the walk re-derived its position from the scroll instead of advancing.
   * A scroll key still releases it: that IS the gesture the listener exists for.
   */
  it('859 live-leg: a NON-scroll key over the scroller keeps the pin; a scroll key releases it', () => {
    const conv = document.createElement('div');
    for (const id of ['t1', 'a1']) {
      const el = document.createElement('div');
      el.setAttribute('data-item-id', id);
      conv.appendChild(el);
    }
    document.body.appendChild(conv);
    const nav = new NavigationController(fakeHost(), {
      scrollEl: () => conv,
      spineEl: () => null,
      active: () => true,
    });
    nav.hostUpdated(); // binds the scroller's listeners
    nav.landmarks = RUN;
    nav.viewport = { topFrac: 0.5, botFrac: 0.8 }; // derived focus would be a1
    nav.jumpTo('t1');
    expect(nav.pinned).toBe('t1');

    // The press comes from the element `jumpTo` just focused, exactly as it does in a browser.
    const focused = conv.querySelector('[data-item-id="t1"]') as HTMLElement;
    focused.dispatchEvent(new KeyboardEvent('keydown', { key: 'j', bubbles: true }));
    expect(nav.pinned, 'a J press is navigation, not a scroll gesture').toBe('t1');
    expect(nav.activeId).toBe('t1');

    focused.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowDown', bubbles: true }));
    expect(nav.pinned, 'an arrow key genuinely scrolls the column').toBeNull();
    expect(nav.activeId).toBe('a1');
    conv.remove();
  });
});

describe('navigation — §21 AFFORDANCE (the minimap-as-scrollbar)', () => {
  // The title used to say "the exact inverse of the viewport window". Tempdoc 859 §B made that
  // false — `measure()` feeds `viewportWindow` the VISIBLE height now, so under an overlay the two
  // are off by the occluded band. The MAPPING is unchanged and still what this pins.
  it('dragTo maps Δy to scrollTop over the content height (Spike A)', () => {
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

describe('navigation — the jump moves real DOM focus (tempdoc 857 PR-A)', () => {
  it('focuses the landmark it scrolls to, so a keyboard reader lands on the content', () => {
    // `el.focus({preventScroll:true})` (`navigation.ts:206`) is this feature's ENTIRE accessibility
    // payload — the difference between "the column scrolled" and "the reader is now on the step" for
    // someone using a keyboard or a screen reader — and until this case nothing pinned it.
    const conv = document.createElement('div');
    document.body.appendChild(conv);
    const step = document.createElement('div');
    step.setAttribute('data-item-id', 't1');
    conv.appendChild(step);

    const nav = new NavigationController(fakeHost(), {
      scrollEl: () => conv,
      spineEl: () => null,
      active: () => true,
    });
    nav.jumpTo('t1');

    expect(document.activeElement).toBe(step);
    // A landmark is not natively focusable, so the jump makes it programmatically focusable first.
    expect(step.getAttribute('tabindex')).toBe('-1');
    conv.remove();
  });
});

describe('navigation — the scroll container is observed per NODE, not once per controller (857 A2)', () => {
  it('rebinds its listeners when scrollEl() returns a different element', () => {
    // The authority used to early-return forever once bound, on the premise that the scroll column
    // is "a stable DOM node across renders". That holds for the first adopter and NOT for the
    // second: `Sv3Main` emits `.scroller` from four render arms, each a different node. A controller
    // that kept its first binding would leave its observer and all four listeners on a detached
    // element while the reader looked at a live one.
    const first = document.createElement('div');
    const second = document.createElement('div');
    for (const conv of [first, second]) {
      const step = document.createElement('div');
      step.setAttribute('data-item-id', 'x');
      conv.appendChild(step);
      document.body.appendChild(conv);
    }
    let current = first;
    const nav = new NavigationController(fakeHost(), {
      scrollEl: () => current,
      spineEl: () => null,
      active: () => true,
    });
    nav.hostUpdated(); // binds to `first`

    current = second;
    nav.hostUpdated(); // must REBIND to `second`

    // Behavioural probe rather than a field read: a `wheel` gesture releases a pin, and only the
    // node the controller is listening to can deliver it.
    const pin = (): void => {
      (nav as unknown as { intent: { mode: string; pinnedId: string | null } }).intent = {
        mode: 'pinned',
        pinnedId: 'x',
      };
    };
    pin();
    first.dispatchEvent(new Event('wheel'));
    expect(nav.pinned).toBe('x'); // the OLD node no longer reaches the controller

    second.dispatchEvent(new Event('wheel'));
    expect(nav.pinned).toBeNull(); // the NEW node does

    first.remove();
    second.remove();
  });

  it('does NOT rebind while the node is unchanged (the first adopter’s behaviour, preserved)', () => {
    const conv = document.createElement('div');
    document.body.appendChild(conv);
    const added: string[] = [];
    const realAdd = conv.addEventListener.bind(conv);
    conv.addEventListener = ((type: string, ...rest: unknown[]) => {
      added.push(type);
      return (realAdd as unknown as (...a: unknown[]) => void)(type, ...rest);
    }) as typeof conv.addEventListener;

    const nav = new NavigationController(fakeHost(), {
      scrollEl: () => conv,
      spineEl: () => null,
      active: () => true,
    });
    nav.hostUpdated();
    const afterFirst = added.length;
    nav.hostUpdated();
    nav.hostUpdated();
    // Identity-equal → exactly the old early return: no second observer, no duplicated listeners.
    expect(added.length).toBe(afterFirst);
    expect(afterFirst).toBeGreaterThan(0);
    conv.remove();
  });
});

/**
 * Tempdoc 859 §B — the occluded band.
 *
 * Once an element FLOATS over a scroller, the scroller's client box stops being its visible region,
 * and every consumer that assumed those were the same thing is wrong. These are the cheapest,
 * highest-value assertions in the slice: the arithmetic is pure, and the failure mode it prevents
 * (D10) is a silent one.
 */
describe('navigation — the visible region under a floating overlay (859 §B)', () => {
  /** A measurable scroll column: real element (the controller wires listeners to it), stubbed box. */
  function column(clientHeight: number, scrollHeight: number, scrollTop: number): HTMLElement {
    const conv = document.createElement('div');
    document.body.appendChild(conv);
    Object.defineProperty(conv, 'clientHeight', { configurable: true, value: clientHeight });
    Object.defineProperty(conv, 'scrollHeight', { configurable: true, value: scrollHeight });
    Object.defineProperty(conv, 'scrollTop', { configurable: true, writable: true, value: scrollTop });
    return conv;
  }

  it('shrinks the reading WINDOW from the bottom by exactly the band', () => {
    // 600px client, 120px of it behind the composer ⇒ 480px actually visible.
    const conv = column(600, 2000, 200);
    const nav = new NavigationController(fakeHost(), {
      scrollEl: () => conv,
      spineEl: () => null,
      active: () => true,
      occludedEndPx: () => 120,
    });
    nav.hostUpdated();
    expect(nav.viewport).toEqual({ topFrac: 0.1, botFrac: (200 + 480) / 2000 });
    conv.remove();
  });

  it('stops the window claiming content the reader cannot see', () => {
    // The same scroll position, read two ways. An item lying entirely inside the occluded strip is
    // claimed as the reading focus by the CLIENT box and is correctly not claimed by the VISIBLE
    // region — which is the minimap thumb and the active-item ring both telling the truth.
    const clientWindow = { topFrac: 0.7, botFrac: 1.0 };
    const visibleWindow = { topFrac: 0.7, botFrac: 0.94 };
    const behindGlass: Landmark[] = [{ id: 'last', extent: { topFrac: 0.95, botFrac: 0.99 } }];
    expect(deriveFocus(behindGlass, clientWindow)).toBe('last');
    expect(deriveFocus(behindGlass, visibleWindow)).toBeNull();
  });

  it('D10 — a band TALLER than the client box must not jump FOCUS to the top of the transcript', () => {
    // A long draft plus a degradation banner in a short window. Without the Math.max(1, …) clamp
    // the subtraction goes negative, `viewportWindow`'s `!(clientHeight > 0)` guard returns null,
    // and `deriveFocus`'s null branch sends FOCUS to the TOPMOST item — the reader's ring jumping
    // to the top of the transcript because they typed a long question.
    const conv = column(300, 2000, 1500);
    const items: Array<[string, number]> = [
      ['u1', 0],
      ['a1', 1600],
    ];
    for (const [id, y] of items) {
      const el = document.createElement('div');
      el.setAttribute('data-item-id', id);
      el.getBoundingClientRect = () =>
        ({ top: y, left: 0, right: 800, bottom: y + 200, width: 800, height: 200, x: 0, y, toJSON: () => ({}) }) as DOMRect;
      conv.appendChild(el);
    }
    const nav = new NavigationController(fakeHost(), {
      scrollEl: () => conv,
      spineEl: () => null,
      active: () => true,
      occludedEndPx: () => 500,
    });
    nav.hostUpdated();
    expect(nav.viewport).not.toBeNull();
    expect(nav.activeId).not.toBe('u1');
    conv.remove();
  });

  it('D7 — a page is the VISIBLE height, and `end` still reaches the true end', () => {
    // `scroll-padding` governs BROWSER-driven scrolling and says nothing about a raw scrollTop
    // assignment, so page-down would otherwise overshoot by the band. `end` needs no subtraction of
    // its own: the scroller's bottom padding already carries the band, so scrollHeight includes it.
    const conv = { scrollTop: 500, clientHeight: 200, scrollHeight: 1000 } as unknown as HTMLElement;
    const nav = new NavigationController(fakeHost(), {
      scrollEl: () => conv,
      spineEl: () => null,
      active: () => true,
      occludedEndPx: () => 60,
    });
    nav.nudge('page-down'); // + (200 − 60) = 140, not 200
    expect(conv.scrollTop).toBe(640);
    nav.nudge('line-up'); // − max(40, 10% of 140 = 14) = 40
    expect(conv.scrollTop).toBe(600);
    nav.nudge('end');
    expect(conv.scrollTop).toBe(1000);
  });

  it('is bit-identical for a consumer that passes no band (the other adopter)', () => {
    // `views/UnifiedChatView.ts` passes exactly three options; its composer is a grid track, not an
    // overlay. The `0` default is what keeps this slice off it entirely.
    const withOut = { scrollTop: 500, clientHeight: 200, scrollHeight: 1000 } as unknown as HTMLElement;
    const nav = new NavigationController(fakeHost(), {
      scrollEl: () => withOut,
      spineEl: () => null,
      active: () => true,
    });
    nav.nudge('page-down');
    expect(withOut.scrollTop).toBe(700); // the full client height, exactly as before

    const conv = column(600, 2000, 200);
    const nav2 = new NavigationController(fakeHost(), {
      scrollEl: () => conv,
      spineEl: () => null,
      active: () => true,
    });
    nav2.hostUpdated();
    expect(nav2.viewport).toEqual({ topFrac: 0.1, botFrac: 0.4 });
    conv.remove();
  });

  it('D5 — remeasure() refreshes the window without a render, and stays inert when inactive', async () => {
    // The ResizeObserver watches the SCROLLER, and under the overlay the scroller's box no longer
    // changes when the composer grows. remeasure() is the replacement signal the band's publisher
    // calls; it is deliberately NOT a requestUpdate.
    const conv = column(600, 2000, 200);
    let band = 0;
    const nav = new NavigationController(fakeHost(), {
      scrollEl: () => conv,
      spineEl: () => null,
      active: () => true,
      occludedEndPx: () => band,
    });
    nav.hostUpdated();
    expect(nav.viewport).toEqual({ topFrac: 0.1, botFrac: 0.4 });
    band = 120; // the composer grew; nothing resized the scroller
    nav.remeasure();
    // 857 A9's coalescing owns WHEN, not WHETHER: this frame already measured (hostUpdated above),
    // so the remeasure collapses into the trailing pass rather than measuring twice in one frame —
    // which is the right economics for a band that changes on every draft keystroke.
    for (let i = 0; i < 4 && nav.viewport?.botFrac === 0.4; i++) {
      await new Promise((r) => setTimeout(r, 0));
    }
    expect(nav.viewport).toEqual({ topFrac: 0.1, botFrac: (200 + 480) / 2000 });

    const inert = new NavigationController(fakeHost(), {
      scrollEl: () => conv,
      spineEl: () => null,
      active: () => false,
      occludedEndPx: () => 120,
    });
    inert.remeasure();
    expect(inert.viewport).toBeNull();
    conv.remove();
  });
});

/**
 * Tempdoc 859 follow-up — the landmark list going STALE with no signal that says so.
 *
 * Measured live post-#534: the record's plain HTML lays out synchronously, so the render-time measure
 * committed two landmarks while the `jf-*` rows had not upgraded, measured zero height and were
 * skipped; nothing re-ran the measurement afterwards, because their growth moves the scroller's
 * `scrollHeight` and NOT the scroller's own box (the ResizeObserver's blind spot, same class as 859
 * §B/D5's floating composer) and no render followed the finished load. The content height is the
 * signal the staleness has of its own, which is why the fix is a recorded height rather than a timer.
 */
describe('navigation — a measurement is stale once the content height moves (859 follow-up)', () => {
  /** A column whose content height can be moved afterwards, counting the measures it is subjected to. */
  function growable(clientHeight: number, contentHeight: number) {
    const conv = document.createElement('div');
    document.body.appendChild(conv);
    let content = contentHeight;
    let measures = 0;
    Object.defineProperty(conv, 'clientHeight', { configurable: true, value: clientHeight });
    Object.defineProperty(conv, 'scrollTop', { configurable: true, writable: true, value: 0 });
    Object.defineProperty(conv, 'scrollHeight', { configurable: true, get: () => content });
    // `measure()` reads the column's own box exactly once per pass, so this counts passes.
    conv.getBoundingClientRect = () => {
      measures++;
      return { top: 0, left: 0, right: 800, bottom: clientHeight, width: 800, height: clientHeight, x: 0, y: 0, toJSON: () => ({}) } as DOMRect;
    };
    return { conv, grow: (h: number) => { content = h; }, measures: () => measures };
  }

  /** A stamped row whose laid-out box can change after it has already been measured once. */
  function row(conv: HTMLElement, id: string, box: () => { top: number; height: number }): void {
    const el = document.createElement('div');
    el.setAttribute('data-item-id', id);
    el.getBoundingClientRect = () => {
      const b = box();
      return { top: b.top, left: 0, right: 800, bottom: b.top + b.height, width: 800, height: b.height, x: 0, y: b.top, toJSON: () => ({}) } as DOMRect;
    };
    conv.appendChild(el);
  }

  it('re-measures on a read when the content grew, and records the height so the read settles', () => {
    // Browser-faithful geometry: the column OVERFLOWS in both states (clientHeight below both content
    // heights), which is the only regime in which `scrollHeight` can move at all — a scroller whose
    // content fits reports `scrollHeight === clientHeight` by spec and this net is structurally blind
    // there. That regime is the content-wrapper observer's, covered by its own case below.
    const { conv, grow, measures } = growable(200, 300);
    let rowHeight = 100;
    row(conv, 'a', () => ({ top: 0, height: rowHeight }));
    row(conv, 'late', () => ({ top: 100, height: rowHeight === 100 ? 0 : 200 }));
    const nav = new NavigationController(fakeHost(), {
      scrollEl: () => conv,
      spineEl: () => null,
      active: () => true,
    });
    nav.hostUpdated();
    expect(nav.landmarks.map((l) => l.id), 'the late row measured zero and was skipped').toEqual(['a']);
    const afterFirst = measures();

    grow(600);
    rowHeight = 200;
    expect(nav.landmarks.map((l) => l.id), 'the read re-measures the grown column').toEqual(['a', 'late']);
    expect(measures()).toBe(afterFirst + 1);

    void nav.landmarks;
    void nav.activeId;
    expect(measures(), 'and the recorded height settles it — no re-measure per read').toBe(afterFirst + 1);
    conv.remove();
  });

  it('records the height even when the measurement changed nothing (SYNTHETIC isolation)', () => {
    // SYNTHETIC — this geometry is not one a browser produces. The column is non-overflowing in both
    // states while `scrollHeight` still moves, which no real scroller does (it would pin scrollHeight
    // to clientHeight), and the row grows in exact proportion so extents, count, track and reading
    // window are all identical. The point is to isolate ONE claim that the browser-faithful case
    // above cannot separate out: `measuredScrollHeight` is recorded at every LOOK, not only at every
    // commit. Move the assignment inside measure()'s `if (changed)` block and this goes red, because
    // the baseline stays behind and every subsequent read re-measures forever instead of settling.
    const { conv, grow, measures } = growable(800, 300);
    let rowHeight = 150;
    row(conv, 'a', () => ({ top: 0, height: rowHeight }));
    const nav = new NavigationController(fakeHost(), {
      scrollEl: () => conv,
      spineEl: () => null,
      active: () => true,
    });
    nav.hostUpdated();
    expect(nav.landmarks.map((l) => l.extent)).toEqual([{ topFrac: 0, botFrac: 0.5 }]);
    const afterFirst = measures();

    grow(600);
    rowHeight = 300;
    void nav.landmarks;
    expect(measures()).toBe(afterFirst + 1);
    void nav.landmarks;
    void nav.activeId;
    expect(measures(), 'nothing committed, but the look was still recorded').toBe(afterFirst + 1);
    conv.remove();
  });

  it('leaves a genuinely collapsed row unanchored across the re-measure', () => {
    // The fix must not become "stop skipping zero-height elements". A step inside a CLOSED <details>
    // is display:none and has no reading position at all, so measure() deliberately leaves it out for
    // the dot to interpolate past. A re-measure triggered by grown content reaches the same verdict.
    const { conv, grow } = growable(200, 1000);
    row(conv, 'open', () => ({ top: 0, height: 100 }));
    row(conv, 'collapsed', () => ({ top: 100, height: 0 }));
    const nav = new NavigationController(fakeHost(), {
      scrollEl: () => conv,
      spineEl: () => null,
      active: () => true,
    });
    nav.hostUpdated();
    expect(nav.landmarks.map((l) => l.id)).toEqual(['open']);

    grow(1400); // a sibling laid out and the column grew — the read re-measures
    expect(nav.landmarks.map((l) => l.id)).toEqual(['open']);
    conv.remove();
  });

  /**
   * Swap in a ResizeObserver that records what it observes and can be fired on demand. happy-dom
   * DEFINES the class but never fires it (`views/search-v3/sv3-occlusion.test.ts:329`), so the
   * observation path is otherwise untestable — and it is the ONLY net that covers the regime the
   * defect was actually measured in.
   */
  function captureResizeObserver(): {
    observed: () => Element[];
    fireFor: (el: Element) => void;
    restore: () => void;
  } {
    const saved = (globalThis as Record<string, unknown>).ResizeObserver;
    const instances: Array<{ cb: () => void; els: Set<Element> }> = [];
    class Fake {
      private readonly rec: { cb: () => void; els: Set<Element> };
      constructor(cb: () => void) {
        this.rec = { cb, els: new Set() };
        instances.push(this.rec);
      }
      observe(el: Element): void {
        this.rec.els.add(el);
      }
      disconnect(): void {
        this.rec.els.clear();
      }
      unobserve(el: Element): void {
        this.rec.els.delete(el);
      }
    }
    (globalThis as Record<string, unknown>).ResizeObserver = Fake;
    return {
      observed: () => instances.flatMap((i) => [...i.els]),
      // Faithful to the real thing: an element that is not observed delivers nothing. That is what
      // makes a case built on this discriminate rather than merely poke the callback.
      fireFor: (el) => {
        for (const i of instances) if (i.els.has(el)) i.cb();
      },
      restore: () => {
        (globalThis as Record<string, unknown>).ResizeObserver = saved;
      },
    };
  }

  it('THE DEFECT REGIME — content fits the viewport, so only the wrapper’s box can report the growth', async () => {
    // The regime the live probe measured: `scrollHeight === clientHeight === 825`. A non-overflowing
    // scroller pins scrollHeight to clientHeight, so when the rows upgrade from height 0 the content
    // height does not move by a single pixel — the freshen-on-read net is structurally blind here,
    // and so is an observer watching only the scroller (a flex track whose box is fixed). What moves
    // is the CONTENT WRAPPER's box, which is why it is observed.
    const ro = captureResizeObserver();
    try {
      const { conv } = growable(825, 825); // fits exactly — and stays that way throughout
      const content = document.createElement('div');
      conv.appendChild(content);
      let laidOut = false;
      row(content, 'early', () => ({ top: 0, height: 100 }));
      row(content, 'late-a', () => ({ top: 100, height: laidOut ? 300 : 0 }));
      row(content, 'late-b', () => ({ top: 400, height: laidOut ? 300 : 0 }));
      const nav = new NavigationController(fakeHost(), {
        scrollEl: () => conv,
        contentEl: () => content,
        spineEl: () => null,
        active: () => true,
      });
      nav.hostUpdated();
      expect(ro.observed(), 'the wrapper is observed alongside the scroller').toEqual([conv, content]);
      expect(nav.landmarks.map((l) => l.id)).toEqual(['early']);
      await new Promise((r) => setTimeout(r, 0)); // close the coalescer's frame

      // The rows lay out. Nothing renders, nothing scrolls, and the content height is unchanged.
      laidOut = true;
      expect(conv.scrollHeight, 'the premise: the content height did NOT move').toBe(825);
      expect(nav.landmarks.map((l) => l.id), 'no other signal can see this').toEqual(['early']);

      // Asserted with no await, so the delivery is the only thing that can have refreshed the list.
      ro.fireFor(content);
      expect(nav.landmarks.map((l) => l.id)).toEqual(['early', 'late-a', 'late-b']);
      conv.remove();
    } finally {
      ro.restore();
    }
  });

  it('rebinds when the content wrapper swaps, so it is never left observed on a detached node', () => {
    // The wrapper is emitted by the same swapping render arms as the scroller (857 A2), so its
    // identity has to join the rebind guard — an unchanged pair takes the old early return.
    const ro = captureResizeObserver();
    try {
      const { conv } = growable(825, 825);
      const first = document.createElement('div');
      const second = document.createElement('div');
      conv.appendChild(first);
      let current = first;
      const nav = new NavigationController(fakeHost(), {
        scrollEl: () => conv,
        contentEl: () => current,
        spineEl: () => null,
        active: () => true,
      });
      nav.hostUpdated();
      expect(ro.observed()).toEqual([conv, first]);

      nav.hostUpdated();
      expect(ro.observed(), 'an unchanged pair does not re-observe').toEqual([conv, first]);

      conv.replaceChild(second, first);
      current = second;
      nav.hostUpdated();
      expect(ro.observed(), 'a swapped wrapper rebinds').toEqual([conv, second]);
      conv.remove();
    } finally {
      ro.restore();
    }
  });

  it('observes only the scroller for a host that has no content wrapper (the other adopter)', () => {
    const ro = captureResizeObserver();
    try {
      const { conv } = growable(200, 1000);
      const nav = new NavigationController(fakeHost(), {
        scrollEl: () => conv,
        spineEl: () => null,
        active: () => true,
      });
      nav.hostUpdated();
      expect(ro.observed()).toEqual([conv]);
      conv.remove();
    } finally {
      ro.restore();
    }
  });

  it('does not force a measurement before the render path has taken one', () => {
    // A read on a controller that has never measured is NOT staleness — there is no baseline to be
    // stale against, and forcing a layout for it would put a getBoundingClientRect on every consumer
    // that touches the list of a transcript that has not rendered yet.
    const { conv, measures } = growable(200, 1000);
    row(conv, 'a', () => ({ top: 0, height: 100 }));
    const nav = new NavigationController(fakeHost(), {
      scrollEl: () => conv,
      spineEl: () => null,
      active: () => true,
    });
    expect(nav.landmarks).toEqual([]);
    expect(nav.activeId).toBe('');
    expect(measures()).toBe(0);
    conv.remove();
  });
});

describe('navigation — the coalescer drains a page that stops delivering frames (860 §6.4 / P3)', () => {
  /** A column that counts the measure passes it is subjected to (`measure()` reads its box once). */
  function counted(): { conv: HTMLElement; measures: () => number } {
    const conv = document.createElement('div');
    document.body.appendChild(conv);
    let measures = 0;
    Object.defineProperty(conv, 'clientHeight', { configurable: true, value: 200 });
    Object.defineProperty(conv, 'scrollTop', { configurable: true, writable: true, value: 0 });
    Object.defineProperty(conv, 'scrollHeight', { configurable: true, get: () => 600 });
    conv.getBoundingClientRect = () => {
      measures++;
      return { top: 0, left: 0, right: 800, bottom: 200, width: 800, height: 200, x: 0, y: 0, toJSON: () => ({}) } as DOMRect;
    };
    const item = document.createElement('div');
    item.setAttribute('data-item-id', 'a');
    item.getBoundingClientRect = () =>
      ({ top: 0, left: 0, right: 800, bottom: 100, width: 800, height: 100, x: 0, y: 0, toJSON: () => ({}) }) as DOMRect;
    conv.appendChild(item);
    return { conv, measures: () => measures };
  }

  it('runs the trailing pass on the fallback when no frame ever arrives', () => {
    // The incident's own site, in the incident's own conditions. happy-dom's rAF ALWAYS fires, so
    // the wedge only exists once frames are withheld — which is why this stubs rather than trusting
    // the environment. Before D3 the trailing pass was scheduled on a frame that never came, and
    // the latch stayed set until the page was foregrounded again.
    vi.useFakeTimers();
    const realRaf = globalThis.requestAnimationFrame;
    globalThis.requestAnimationFrame = ((): number => 1) as typeof requestAnimationFrame;
    try {
      const { conv, measures } = counted();
      const nav = new NavigationController(fakeHost(), {
        scrollEl: () => conv,
        spineEl: () => null,
        active: () => true,
      });
      nav.hostUpdated();
      expect(measures(), 'the leading measure is synchronous').toBe(1);

      nav.remeasure();
      nav.remeasure();
      expect(measures(), 'both coalesce into one trailing pass').toBe(1);

      vi.advanceTimersByTime(FRAME_LATCH_FALLBACK_MS);
      expect(measures(), 'which the fallback delivers — once, not once per coalesced caller').toBe(2);

      // And the latch is genuinely open afterwards: a later signal measures again.
      nav.remeasure();
      vi.advanceTimersByTime(FRAME_LATCH_FALLBACK_MS);
      expect(measures()).toBe(3);
      conv.remove();
    } finally {
      globalThis.requestAnimationFrame = realRaf;
      vi.useRealTimers();
    }
  });

  it('teardown withdraws the fallback, so a detached column is never measured a second later', () => {
    vi.useFakeTimers();
    const realRaf = globalThis.requestAnimationFrame;
    globalThis.requestAnimationFrame = ((): number => 1) as typeof requestAnimationFrame;
    try {
      const { conv, measures } = counted();
      let active = true;
      const nav = new NavigationController(fakeHost(), {
        scrollEl: () => conv,
        spineEl: () => null,
        active: () => active,
      });
      nav.hostUpdated();
      nav.remeasure();
      expect(measures()).toBe(1);

      active = false;
      nav.hostUpdated(); // → teardown
      vi.advanceTimersByTime(FRAME_LATCH_FALLBACK_MS * 3);
      expect(measures(), 'nothing measures the arm that was torn down').toBe(1);
      conv.remove();
    } finally {
      globalThis.requestAnimationFrame = realRaf;
      vi.useRealTimers();
    }
  });
});

