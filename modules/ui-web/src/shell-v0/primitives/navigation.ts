// SPDX-License-Identifier: Apache-2.0
/**
 * navigation — Tempdoc 565 §21: the chat-first Navigation authority (spatial reading-position).
 *
 * The agent run's "where am I / how do I move" was FOUR hand-wired representations inside UnifiedChatView
 * (native scrollbar · viewport box · spine dots · active ring), bound by no authority. This controller is
 * §21's realization: ONE reading-position model the spine projects from — each item's scroll POSITION
 * (`fractions`, midpoints) and EXTENT (`landmarks`, top/bottom span), the reading WINDOW (`viewport`, via
 * {@link viewportWindow}), the FOCUS item (`activeId`, DERIVED from window×extents — no IntersectionObserver),
 * and the navigation CONTROL (`jumpTo` + a single live/pinned `intent`). It mirrors the Adaptivity
 * controller pattern ({@link OverflowController}/{@link DensityController}): a Lit `ReactiveController` that
 * owns its observers + rAF + state, exposes getters the host render reads, and self-manages its lifecycle.
 *
 * §21 FOCUS is derived ({@link deriveFocus}), not observed: the topmost landmark with ≥`MIN_VISIBLE` of
 * itself in the window — matching the retired observer's `threshold: 0.1`. The live↔pinned `intent` is the
 * ONE control state; the prior `activeSpineItemId`+`spinePinnedId`+`adopt`/`release` apparatus (and the
 * highlight-steal bug it papered over) is structurally unrepresentable now.
 */
import type { ReactiveController, ReactiveControllerHost } from 'lit';
import { viewportWindow, type ViewportWindow } from './scrollViewport.js';

/** §21 — a measured reading landmark: a timeline item and the 0..1 scroll-extent its box occupies. */
export interface Landmark {
  readonly id: string;
  readonly extent: { readonly topFrac: number; readonly botFrac: number };
}

/** Default min-visible fraction for FOCUS — matches the retired IntersectionObserver's `threshold: 0.1`. */
export const MIN_VISIBLE = 0.1;

/**
 * §21 FOCUS — derive the reading-focus item from the reading WINDOW × the landmark EXTENTS: the TOPMOST
 * landmark with at least `minVisibleFrac` of ITSELF inside the window. This matches the retired
 * IntersectionObserver (`threshold: 0.1` = intersectionRatio ≥ 0.1 of the element), NOT naive any-overlap
 * — Spike B (§21.8) showed any-overlap picks a 1px-peeking step where the observer picked the real focus.
 * Returns null when nothing qualifies. When the column is not scrollable (`window === null`) the whole run
 * is in view → the topmost landmark is the focus.
 */
export function deriveFocus(
  landmarks: readonly Landmark[],
  window: ViewportWindow | null,
  minVisibleFrac = MIN_VISIBLE,
): string | null {
  if (landmarks.length === 0) return null;
  if (window === null) {
    // Not scrollable — everything is visible; the focus is the topmost item by content position.
    let top: Landmark | null = null;
    for (const l of landmarks) if (top === null || l.extent.topFrac < top.extent.topFrac) top = l;
    return top?.id ?? null;
  }
  let best: Landmark | null = null;
  for (const l of landmarks) {
    const height = Math.max(l.extent.botFrac - l.extent.topFrac, 1e-6);
    const overlap = Math.max(
      0,
      Math.min(l.extent.botFrac, window.botFrac) - Math.max(l.extent.topFrac, window.topFrac),
    );
    if (overlap / height < minVisibleFrac) continue;
    if (best === null || l.extent.topFrac < best.extent.topFrac) best = l;
  }
  return best?.id ?? null;
}

export interface NavigationOptions {
  /** The scroll container (the reading column, `.conversation`). */
  readonly scrollEl: () => HTMLElement | null;
  /** The spine track element (`.run-spine`), whose height the dot placement scales to. */
  readonly spineEl: () => HTMLElement | null;
  /** True while the spine/minimap is mounted (agent affordance + wide breakpoint). */
  readonly active: () => boolean;
}

const raf =
  typeof requestAnimationFrame !== 'undefined'
    ? requestAnimationFrame
    : (cb: FrameRequestCallback): number => {
        queueMicrotask(() => cb(0));
        return 0;
      };

export class NavigationController implements ReactiveController {
  /** §21 POSITION — each conversation-anchored item's 0..1 MIDPOINT scroll fraction (the dot placement). */
  fractions = new Map<string, number>();
  /** §21 — each item's 0..1 scroll EXTENT (top/bottom span); FOCUS is derived from these × the window. */
  landmarks: Landmark[] = [];
  /** §19.4 — the measured spine-track height (px); 0 until measured → the render uses %-placement. */
  trackPx = 0;
  /** §21 WINDOW — the slice of the conversation on screen; null when it all fits (not scrollable). */
  viewport: ViewportWindow | null = null;

  private readonly host: ReactiveControllerHost & HTMLElement;
  private readonly opts: NavigationOptions;
  // §21 CONTROL — the ONE navigation intent. `live`: FOCUS tracks the reading position (derived). `pinned`:
  // a click-jump owns the FOCUS until a genuine user scroll flips back to `live`. This single state machine
  // replaces the old activeSpineItemId + spinePinnedId + adopt/release apparatus.
  private intent: { mode: 'live' | 'pinned'; pinnedId: string | null } = { mode: 'live', pinnedId: null };
  private resizeObserver: ResizeObserver | null = null;
  private scrollEl: HTMLElement | null = null;
  private scrollRaf = false;

  constructor(host: ReactiveControllerHost & HTMLElement, opts: NavigationOptions) {
    this.host = host;
    this.opts = opts;
    host.addController(this);
  }

  hostUpdated(): void {
    // (re)wire the resize-measure + re-measure after each render so newly-rendered items are measured;
    // tear everything down when the spine is not shown (non-agent / narrow). Mirrors the old updated().
    if (this.opts.active()) {
      this.setupResize();
      if (this.measure()) this.host.requestUpdate();
    } else {
      this.teardown();
    }
  }

  hostDisconnected(): void {
    this.teardown();
  }

  /** §21 FOCUS — the item the reader is on (the spine's `.active` ring): the pinned target, else derived. */
  get activeId(): string {
    if (this.intent.mode === 'pinned' && this.intent.pinnedId) return this.intent.pinnedId;
    return deriveFocus(this.landmarks, this.viewport) ?? '';
  }

  /** Whether a click-jump currently pins the focus (exposed for the affordance + tests). */
  get pinned(): string | null {
    return this.intent.mode === 'pinned' ? this.intent.pinnedId : null;
  }

  /** §13/§21 CONTROL — click-jump: scroll the reading column to the timeline item + pin the focus to it. */
  jumpTo(id: string): void {
    const conv = this.opts.scrollEl();
    const target = conv
      ? [...conv.querySelectorAll('[data-item-id]')].find((el) => el.getAttribute('data-item-id') === id)
      : null;
    if (!target) return;
    const el = target as HTMLElement;
    // Intra-turn steps render inside a collapsed `<details class="run-trace">`; `scrollIntoView` is a
    // no-op inside a closed `<details>`, so open every ancestor first so the jump always lands.
    for (let p = el.parentElement; p && p !== conv; p = p.parentElement) {
      if (p instanceof HTMLDetailsElement) p.open = true;
    }
    // a11y — honor reduced-motion AND move focus to the jumped-to turn so keyboard/SR users land on the
    // content, not stranded on the spine node.
    const reduce =
      typeof window.matchMedia === 'function' &&
      window.matchMedia('(prefers-reduced-motion: reduce)').matches;
    el.scrollIntoView({ block: 'center', behavior: reduce ? 'auto' : 'smooth' });
    el.setAttribute('tabindex', '-1');
    el.focus({ preventScroll: true });
    // §21 CONTROL — pin the focus to the clicked item (an explicit "show me this"); the centring scroll
    // fires only 'scroll' (never wheel/touch/keydown), so the jump never self-releases its own pin.
    this.intent = { mode: 'pinned', pinnedId: id };
    this.host.requestUpdate();
  }

  /** A genuine user scroll gesture (wheel / touch / keyboard) flips the intent back to `live`. */
  readonly onUserScroll = (): void => {
    if (this.intent.mode === 'live') return;
    this.intent = { mode: 'live', pinnedId: null };
    this.host.requestUpdate();
  };

  // §21 AFFORDANCE — drag state for the minimap-as-scrollbar (the thumb maps Δy → scrollTop, Spike A's
  // exact inverse of viewportWindow).
  private dragStartY = 0;
  private dragStartScroll = 0;

  /**
   * §21 AFFORDANCE — begin a thumb drag: record the grab origin + the current scroll, and release any pin
   * (grabbing the scrollbar is free navigation → FOCUS should track the reading position again).
   */
  beginDrag(clientY: number): void {
    const conv = this.opts.scrollEl();
    if (!conv) return;
    this.dragStartY = clientY;
    this.dragStartScroll = conv.scrollTop;
    this.onUserScroll();
  }

  /**
   * §21 AFFORDANCE — map the drag delta to a scroll offset: `Δscroll = (Δy / trackPx) · scrollHeight` —
   * the EXACT inverse of {@link viewportWindow} (Spike A, §21.8). The scroll fires `scroll` → measure →
   * re-render, which redraws the thumb at the new window, so the thumb follows the pointer 1:1.
   */
  dragTo(clientY: number): void {
    const conv = this.opts.scrollEl();
    if (!conv) return;
    const track = this.trackPx || conv.clientHeight || 1;
    const dy = clientY - this.dragStartY;
    conv.scrollTop = this.dragStartScroll + (dy / track) * conv.scrollHeight;
  }

  /** §21 AFFORDANCE — keyboard scroll from the thumb: arrows = a line, Page = a page, Home/End = the ends. */
  nudge(kind: 'line-up' | 'line-down' | 'page-up' | 'page-down' | 'home' | 'end'): void {
    const conv = this.opts.scrollEl();
    if (!conv) return;
    const page = conv.clientHeight || 0;
    const line = Math.max(40, page * 0.1);
    switch (kind) {
      case 'line-up':
        conv.scrollTop -= line;
        break;
      case 'line-down':
        conv.scrollTop += line;
        break;
      case 'page-up':
        conv.scrollTop -= page;
        break;
      case 'page-down':
        conv.scrollTop += page;
        break;
      case 'home':
        conv.scrollTop = 0;
        break;
      case 'end':
        conv.scrollTop = conv.scrollHeight;
        break;
    }
    this.onUserScroll();
  }

  private readonly onScroll = (): void => {
    if (this.scrollRaf) return;
    this.scrollRaf = true;
    raf(() => {
      this.scrollRaf = false;
      // A scroll only moves the reading WINDOW (item extents are scroll-invariant); the re-render then
      // re-derives FOCUS from the new window via the `activeId` getter — no IntersectionObserver needed.
      if (this.measure()) this.host.requestUpdate();
    });
  };

  private setupResize(): void {
    if (typeof ResizeObserver === 'undefined') return; // happy-dom / SSR guard
    // `.conversation` is a stable DOM node across renders → observe it ONCE (guarded), not recreate.
    if (this.resizeObserver) return;
    const conv = this.opts.scrollEl();
    if (!conv) return;
    this.resizeObserver = new ResizeObserver(() => {
      if (this.measure()) this.host.requestUpdate();
    });
    this.resizeObserver.observe(conv);
    conv.addEventListener('scroll', this.onScroll, { passive: true });
    conv.addEventListener('wheel', this.onUserScroll, { passive: true });
    conv.addEventListener('touchstart', this.onUserScroll, { passive: true });
    conv.addEventListener('keydown', this.onUserScroll, { passive: true });
    this.scrollEl = conv;
  }

  /** Whether the reading-window has moved enough to redraw the viewport box / re-derive focus. */
  private viewportChanged(vp: ViewportWindow | null): boolean {
    const cur = this.viewport;
    if (vp === null || cur === null) return vp !== cur;
    return Math.abs(vp.topFrac - cur.topFrac) > 0.002 || Math.abs(vp.botFrac - cur.botFrac) > 0.002;
  }

  /**
   * §19.4/§21 — measure each conversation-anchored item's 0..1 scroll EXTENT (top/bottom over the content
   * height) + MIDPOINT fraction, the spine-track height, and the reading window. Returns true if anything
   * changed beyond a small tolerance (so the host only re-renders on a real change, never looping).
   */
  private measure(): boolean {
    const conv = this.opts.scrollEl();
    if (!conv) return false;
    const spineEl = this.opts.spineEl();
    const trackPx = spineEl ? spineEl.clientHeight : 0;
    const vp = viewportWindow(conv.scrollTop, conv.clientHeight, conv.scrollHeight);
    const convTop = conv.getBoundingClientRect().top;
    const scrollH = conv.scrollHeight || 1;
    const clamp = (f: number): number => Math.min(1, Math.max(0, f));
    const nextFractions = new Map<string, number>();
    const nextLandmarks: Landmark[] = [];
    conv.querySelectorAll('[data-item-id]').forEach((el) => {
      const id = el.getAttribute('data-item-id');
      if (!id) return;
      const rect = (el as HTMLElement).getBoundingClientRect();
      // Skip elements with no laid-out box (a collapsed-trace step inside a closed <details> is
      // display:none) — leave it unanchored so the dot interpolates between turn anchors.
      if (rect.height === 0) return;
      const top = rect.top - convTop + conv.scrollTop;
      const topFrac = clamp(top / scrollH);
      const botFrac = clamp((top + rect.height) / scrollH);
      nextLandmarks.push({ id, extent: { topFrac, botFrac } });
      // Anchor each dot at its MIDPOINT (a marker represents the whole item's location), not its top.
      nextFractions.set(id, clamp((topFrac + botFrac) / 2));
    });
    let changed =
      nextFractions.size !== this.fractions.size ||
      Math.abs(trackPx - this.trackPx) > 1 ||
      this.viewportChanged(vp);
    if (!changed) {
      for (const [id, f] of nextFractions) {
        const prev = this.fractions.get(id);
        if (prev === undefined || Math.abs(prev - f) > 0.003) {
          changed = true;
          break;
        }
      }
    }
    if (changed) {
      this.fractions = nextFractions;
      this.landmarks = nextLandmarks;
      this.trackPx = trackPx;
      this.viewport = vp;
    }
    return changed;
  }

  private teardown(): void {
    this.resizeObserver?.disconnect();
    this.resizeObserver = null;
    this.scrollEl?.removeEventListener('scroll', this.onScroll);
    this.scrollEl?.removeEventListener('wheel', this.onUserScroll);
    this.scrollEl?.removeEventListener('touchstart', this.onUserScroll);
    this.scrollEl?.removeEventListener('keydown', this.onUserScroll);
    this.scrollEl = null;
    this.intent = { mode: 'live', pinnedId: null };
    this.viewport = null;
  }
}
