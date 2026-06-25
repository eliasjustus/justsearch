// SPDX-License-Identifier: Apache-2.0
/**
 * adaptiveDensity — tempdoc 565 §19 / tempdoc 559 Part II Authority VI (Adaptivity): the FILL half.
 *
 * The SIBLING of `adaptiveBar.ts`. 559 declares Adaptivity as "overflow + **fill** under constrained
 * space". `adaptiveBar.ts` built the OVERFLOW half — given a row of items and a width, which leading
 * items FIT (the rest go behind "…"). This builds the **FILL / DENSITY** half — given ONE element and
 * its measured box, which REPRESENTATION fits *legibly*. It is the same mechanism (a `ResizeObserver`
 * that measures + a pure decision that maps a size to an answer), generalised from "which items" to
 * "which representation of one item".
 *
 * The defect it cures (565 §19.1): the run-step node (`RunNode`) rendered ONE fixed representation —
 * its glyph char `✓/⊘/✕` — at every scale, so at the spine-gutter scale (0.3–0.8rem boxes ≈ 5–13px) the
 * char collapsed to illegible mush. §17 unified the run-step's SEMANTICS (the `StepPresentation`
 * descriptor) but not the SCALE of its rendering. This makes the representation a PROJECTION of the
 * measured size: a glyph too small to read is never drawn — below the legible threshold the element
 * falls to a dot/shape. "An illegible-at-scale glyph" becomes unrepresentable by construction.
 *
 * Two pieces (mirroring adaptiveBar.ts):
 *   - {@link representationFor} — the pure decision (testable without layout; degrades to the declared
 *     density when unmeasured, e.g. jsdom/SSR/first-paint, so nothing ever renders blank).
 *   - {@link DensityController} — a Lit ReactiveController that owns the `ResizeObserver`, measures the
 *     host box (rAF-deferred, never during update), and recomputes `density` on resize.
 */
import type { ReactiveController, ReactiveControllerHost } from 'lit';

/**
 * The representation ladder by density (565 §19.2). `minimal` — a dot/shape only (no glyph char);
 * `compact` — the glyph char; `full` — the glyph char + the surface's detail (the node renders the
 * same glyph as `compact`; `full` is meaningful at the SURFACE, which adds the label/detail). Ordered
 * minimal < compact < full.
 */
export type RunNodeDensity = 'minimal' | 'compact' | 'full';

const RANK: Readonly<Record<RunNodeDensity, number>> = { minimal: 0, compact: 1, full: 2 };
const BY_RANK: readonly RunNodeDensity[] = ['minimal', 'compact', 'full'];

/**
 * The legibility thresholds (px), the SINGLE source the decision and the primitive's CSS both read.
 * Calibrated to the run-step surfaces by a LIVE measured audit (565 §19.H): the rendered run-node box
 * is its disc wrapper minus the 1px border — the in-body trace node (0.72rem wrapper) measures ~9.5px
 * and is the `compact` tier that must SHOW its glyph; the spine-gutter nodes measure ~2.8–7.9px (ambient
 * dots through the primary turn) and must NOT (a 0.72em char there is 2–6px of mush). So `9` cleanly
 * separates them: a glyph char needs a box ≥ {@link GLYPH_LEGIBLE_PX} to read, below it the element
 * falls to a dot. (The gutter declares `minimal` regardless, so this bar chiefly governs the
 * trace/card `compact`/`full` surfaces + the illegibility backstop.) {@link DETAIL_PX} is the bar above
 * which a surface may afford `full` detail.
 */
export const GLYPH_LEGIBLE_PX = 9;
export const DETAIL_PX = 22;

/** The largest density a measured box can render LEGIBLY — or `null` when the box is unmeasured. */
function afford(measuredPx: number): RunNodeDensity | null {
  if (!(measuredPx > 0)) return null; // unmeasured (jsdom / SSR / before first layout)
  if (measuredPx < GLYPH_LEGIBLE_PX) return 'minimal';
  if (measuredPx < DETAIL_PX) return 'compact';
  return 'full';
}

/**
 * The smaller (more minimal) of two densities — `min` over the `minimal < compact < full` order. Public
 * so a render can cap its EFFECTIVE density by the declared ceiling synchronously (565 §19.I-R1): the
 * `DensityController` only knows the measured density after its first rAF, so a node renders
 * `minDensity(declaredCeiling, controller.density)` to hold the ceiling from frame 0 (no first-paint
 * flash of a char that will collapse) while still honouring the measured cap once it lands.
 */
export const minDensity = (a: RunNodeDensity, b: RunNodeDensity): RunNodeDensity =>
  RANK[a] <= RANK[b] ? a : b;

/**
 * The pure density decision (565 §19.2). The effective representation is the surface's `declared`
 * intent CAPPED by what the measured box can legibly afford — `min(declared, afford(px))`. Legibility
 * always wins: a surface that declares `full` on a 6px box still renders `minimal` (the backstop that
 * makes an illegible glyph unrepresentable). When the box is UNMEASURED (`px <= 0`), the declared
 * density is honoured verbatim, so SSR/jsdom/first-paint render the intended representation rather than
 * collapsing to a dot.
 *
 * `declared` is the surface's ceiling/intent: the spine gutter declares `minimal` (always a dot —
 * §19.2/§19.4), the in-body trace `compact`, the tool-card `full`. Default `full` = "show as much as
 * the box legibly affords" (the node is free to shrink to `minimal` when small).
 */
export function representationFor(
  measuredPx: number,
  declared: RunNodeDensity = 'full',
): RunNodeDensity {
  const afforded = afford(measuredPx);
  return afforded === null ? declared : minDensity(declared, afforded);
}

interface DensityOptions {
  /** The element whose measured box bounds the representation (defaults to the host). */
  readonly box?: () => HTMLElement | null;
  /** The surface's declared density ceiling/intent (see {@link representationFor}). */
  readonly declared?: () => RunNodeDensity;
}

/**
 * A Lit ReactiveController that projects the legible {@link RunNodeDensity} for the host's measured box
 * — the FILL-half analogue of {@link import('./adaptiveBar.js').OverflowController}. Owns a
 * `ResizeObserver` on the box, measures its limiting dimension (the smaller of width/height — a glyph
 * needs both), recomputes `density` rAF-deferred (never during the host update cycle, where box metrics
 * are unreliable), and `host.requestUpdate()`s only on a change. Degrades to the declared density where
 * `ResizeObserver` is absent (jsdom).
 */
export class DensityController implements ReactiveController {
  /** The current legible density for the measured box. Starts at the declared intent. */
  density: RunNodeDensity = 'full';

  private readonly host: ReactiveControllerHost & HTMLElement;
  private readonly opts: DensityOptions;
  private ro: ResizeObserver | null = null;
  private rafPending = false;

  constructor(host: ReactiveControllerHost & HTMLElement, opts: DensityOptions = {}) {
    this.host = host;
    this.opts = opts;
    this.density = this.declared();
    host.addController(this);
  }

  private declared(): RunNodeDensity {
    return this.opts.declared?.() ?? 'full';
  }

  private boxEl(): HTMLElement | null {
    return this.opts.box ? this.opts.box() : this.host;
  }

  hostConnected(): void {
    if (typeof ResizeObserver === 'undefined') return;
    this.ro = new ResizeObserver(() => this.schedule());
    queueMicrotask(() => {
      const el = this.boxEl();
      if (el && this.ro) this.ro.observe(el);
      this.schedule();
    });
  }

  hostDisconnected(): void {
    this.ro?.disconnect();
    this.ro = null;
  }

  hostUpdated(): void {
    // A declared-density change (surface re-declares) re-projects on the next frame.
    this.schedule();
  }

  /** Defer all measurement/recompute to after layout (rAF), never during update. */
  private schedule(): void {
    if (this.rafPending) return;
    this.rafPending = true;
    const raf =
      typeof requestAnimationFrame !== 'undefined'
        ? requestAnimationFrame
        : (cb: FrameRequestCallback) => queueMicrotask(() => cb(0));
    raf(() => {
      this.rafPending = false;
      this.recompute();
    });
  }

  private recompute(): void {
    const el = this.boxEl();
    const rect = el ? el.getBoundingClientRect() : null;
    const px = rect ? Math.min(rect.width, rect.height) : 0;
    const next = representationFor(px, this.declared());
    if (next !== this.density) {
      this.density = next;
      this.host.requestUpdate();
    }
  }
}

/** Tempdoc 565 §19.5 — the ordered ladder, for tests / introspection. */
export const DENSITY_LADDER = BY_RANK;
