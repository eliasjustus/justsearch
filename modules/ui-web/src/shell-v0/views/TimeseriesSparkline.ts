// SPDX-License-Identifier: Apache-2.0
/**
 * TimeseriesSparkline — TIMESERIES Resource Category default renderer.
 *
 * Slice 3a.1.4 Phase 4 §B.7 (renderer split: primitive + Resource view).
 * Layer 4b: consumes a `TimeseriesSnapshot` wire payload and delegates
 * the actual SVG drawing to `<jf-timeseries-polyline>` (Layer 4a).
 *
 * Why split: alternative TIMESERIES renderers (gauges, histograms,
 * heatmap rows) compose over the same polyline primitive but apply
 * different normalization / aria semantics / layout. Splitting keeps
 * the primitive reusable.
 *
 * Wire-payload contract: imports `TimeseriesSnapshot` from the
 * `api/generated/index.ts` barrel (slice 3a.1.3 ESLint guard forces
 * barrel-only access). Per slice 3a.1.3 §B.A: every field is optional
 * (the generator faithfully reflects the wire's lack of required-field
 * encoding); this component narrows at the consumption boundary.
 *
 * Accessibility: the inner polyline has `aria-hidden="true"` (it's a
 * decorative shape); this element synthesizes a kernel-aware aria-label
 * from the snapshot — unit, sample count, latest value, peak. The host
 * element gets `role="img"` so screen readers announce the synthesized
 * label as an image description (per modern SVG accessibility best
 * practice — `role="img"` on the wrapping element with a stable
 * accessible name).
 *
 * Density / hint awareness (V1): hint defaults to SPARK at the
 * Resource-view registry dispatch level; this component implements the
 * SPARK shape only. GAUGE / HISTOGRAM are out of scope per the
 * tempdoc's §"Out of scope". Density is consumed via consumer-side
 * dispatch (the Resource-view registry's `dispatchResourceRenderer`
 * picks an appropriate tag); this element doesn't read density.
 */

import {
  css,
  html,
  nothing,
  type PropertyValues,
  type TemplateResult,
} from 'lit';
import { JfElement } from '../primitives/JfElement.js';
import type { TimeseriesSnapshot } from '../../api/generated/index.js';
import '../components/TimeseriesPolyline.js';

export class TimeseriesSparkline extends JfElement {
  static override properties = {
    snapshot: { attribute: false },
    label: { type: String },
  } as const;

  /**
   * Wire payload from a TIMESERIES Resource. Optional + all-fields-
   * optional inner shape per slice 3a.1.3's typescript-generator output;
   * this component narrows on access.
   */
  declare snapshot: TimeseriesSnapshot | undefined;

  /**
   * Optional label to incorporate into the synthesized aria-label.
   * Falls back to a unit-derived label when absent. Consumers
   * typically resolve this from `MetricRef.label` (i18n key) before
   * passing it in — i18n resolution is a consumer concern, not the
   * renderer's.
   */
  declare label: string;

  constructor() {
    super();
    this.snapshot = undefined;
    this.label = '';
  }

  static override styles = css`
    :host {
      display: inline-flex;
      align-items: center;
      gap: 0.25rem;
      flex-shrink: 0;
      /* Default sparkline color follows the host current color so
       * consumers can theme via CSS color on the parent. The component
       * does not introduce a new component token (per drift-guard I4
       * exception list: currentColor is allowed). */
      color: currentColor;
    }
  `;

  override connectedCallback(): void {
    super.connectedCallback();
    // role="img" on host for screen readers (per modern SVG a11y
    // best practice: wrapping element carries the accessible name).
    if (!this.hasAttribute('role')) {
      this.setAttribute('role', 'img');
    }
  }

  override updated(changed: PropertyValues): void {
    if (changed.has('snapshot') || changed.has('label')) {
      // Sync aria-label whenever the snapshot or label changes — the
      // synthesized label depends on both.
      this.setAttribute('aria-label', this.synthesizeAriaLabel());
    }
  }

  override render(): TemplateResult {
    const s = this.snapshot;
    const hasData = !!s && !!s.values && s.values.length >= 2;
    return html`${hasData
      ? html`<jf-timeseries-polyline
          .values=${s!.values!}
        ></jf-timeseries-polyline>`
      : nothing}`;
  }

  /**
   * Build a stable accessible name. Pattern:
   *   "<label> trend: <count> samples [in <unit>], current <latest>, peak <max>"
   * Falls back gracefully when fields are absent (the wire shape is all-
   * fields-optional). Skips peak when min == max (flat line).
   */
  private synthesizeAriaLabel(): string {
    const s = this.snapshot;
    if (!s || !s.values || s.values.length === 0) {
      return this.label || 'Empty trend';
    }
    const v = s.values;
    const n = v.length;
    const latest = v[n - 1]!;
    let max = v[0]!;
    let min = v[0]!;
    for (let i = 1; i < n; i++) {
      if (v[i]! > max) max = v[i]!;
      if (v[i]! < min) min = v[i]!;
    }
    const unitSuffix = s.unit ? ` in ${s.unit}` : '';
    const labelPrefix = this.label ? `${this.label} ` : '';
    const peakClause = max === min ? '' : `, peak ${formatNum(max)}`;
    return `${labelPrefix}trend: ${n} samples${unitSuffix}, current ${formatNum(
      latest,
    )}${peakClause}`;
  }
}

function formatNum(n: number): string {
  // Locale-independent compact formatting: integer if whole, else 2 decimals.
  // FE callers requiring locale-aware formatting can override the label
  // explicitly via the `label` property (which the synthesized output
  // composes around).
  if (Number.isInteger(n)) return String(n);
  return n.toFixed(2);
}

customElements.define('jf-timeseries-sparkline', TimeseriesSparkline);
