// SPDX-License-Identifier: Apache-2.0
/**
 * TimeseriesPolyline — pure SVG line-chart primitive.
 *
 * Slice 3a.1.4 Phase 4 §B.7 (renderer split: primitive + Resource view).
 * Layer 4a: visualization primitive with no domain knowledge.
 * Composable into other TIMESERIES renderers (gauges, histograms,
 * heatmap rows, etc.) — they all draw a polyline somewhere.
 *
 * Defaults derived verbatim from the React reference component
 * (`modules/ui-web/src/components/ui/Sparkline.tsx`) per the §B.8
 * anchor block: width=80, height=18, strokeWidth=1.25, stroke=
 * currentColor, returns nothing for `values.length < 2`, all-equal →
 * flat midline. The React reference is retired in slice 3a.1.4
 * Phase 7 once HealthLitView consumes the Lit Resource-view
 * dispatcher.
 *
 * Wire-format note: this primitive receives raw `number[]`, NOT a
 * `TimeseriesSnapshot`. The Resource-view wrapper
 * (`<jf-timeseries-sparkline>`) consumes the snapshot, extracts
 * `values`, and passes them here. Splitting the layers keeps this
 * element reusable for other TIMESERIES renderers AND for non-
 * TIMESERIES use cases (e.g., a one-off chart that doesn't surface
 * via the Resource-view registry).
 *
 * Layout note: Lit shadow DOM doesn't inherit Tailwind classes from
 * the React app. The React reference used `className="shrink-0"`;
 * the Lit equivalent is `:host { display: inline-block;
 * flex-shrink: 0; vertical-align: middle; }` set in static styles
 * so the element embeds correctly in flex rows like its React
 * counterpart did.
 */

import { css, html, nothing, svg, type TemplateResult } from 'lit';
import { JfElement } from '../primitives/JfElement.js';

export class TimeseriesPolyline extends JfElement {
  static override properties = {
    values: { attribute: false },
    width: { type: Number },
    height: { type: Number },
    strokeWidth: { type: Number, attribute: 'stroke-width' },
  } as const;

  /** Raw samples ordered oldest-first. Mutating the array post-set won't trigger re-render (Lit uses !== equality). */
  declare values: readonly number[] | undefined;

  /** Pixel width of the SVG. Default 80 per §B.8 anchor. */
  declare width: number;

  /** Pixel height of the SVG. Default 18 per §B.8 anchor. */
  declare height: number;

  /** Stroke width. Default 1.25 per §B.8 anchor. */
  declare strokeWidth: number;

  constructor() {
    super();
    this.values = undefined;
    this.width = 80;
    this.height = 18;
    this.strokeWidth = 1.25;
  }

  static override styles = css`
    :host {
      display: inline-block;
      flex-shrink: 0;
      vertical-align: middle;
      /* Default stroke uses CSS color (via currentColor); consumers
       * override by setting color on the host element. Per drift-guard
       * I4 exception list: currentColor is allowed as a literal at the
       * component layer without a token reference. */
      color: currentColor;
    }
    svg {
      display: block;
    }
  `;

  override render(): TemplateResult {
    const v = this.values;
    if (!v || v.length < 2) {
      // Per §B.8 anchor: return nothing when < 2 values. Lit's `nothing`
      // sentinel renders no DOM (matches React reference's `null` return).
      return html`${nothing}`;
    }
    const w = this.width;
    const h = this.height;
    let min = v[0]!;
    let max = v[0]!;
    for (let i = 1; i < v.length; i++) {
      const x = v[i]!;
      if (x < min) min = x;
      if (x > max) max = x;
    }
    const range = max - min;
    const stepX = w / (v.length - 1);
    let points = '';
    for (let i = 0; i < v.length; i++) {
      const x = i * stepX;
      const y = range === 0 ? h / 2 : h - ((v[i]! - min) / range) * h;
      points += `${x.toFixed(1)},${y.toFixed(1)}`;
      if (i < v.length - 1) points += ' ';
    }
    // Per Lit best practice (https://lit.dev/docs/api/templates/): use
    // `html` for the outer <svg> and `svg` for SVG fragments inside.
    return html`
      <svg
        width=${w}
        height=${h}
        viewBox="0 0 ${w} ${h}"
        aria-hidden="true"
      >
        ${svg`<polyline
          points=${points}
          fill="none"
          stroke="currentColor"
          stroke-width=${this.strokeWidth}
          stroke-linecap="round"
          stroke-linejoin="round"
        ></polyline>`}
      </svg>
    `;
  }
}

customElements.define('jf-timeseries-polyline', TimeseriesPolyline);
