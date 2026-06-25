// SPDX-License-Identifier: Apache-2.0
/**
 * NativePopoverSpike — tempdoc 574 §25 Phase 5 (Edge 5), the modality-on-platform SPIKE.
 *
 * EXPLORATORY, NOT a migration. Measures how much of `TransientController`'s contract the web platform
 * absorbs NATIVELY on the only engine the Tauri shell ships (Windows-only WebView2/Chromium — no
 * WKWebView/Safari target, so Baseline-2024/2025 features are fully available):
 *
 *   - **Single-open** — `popover="auto"`: opening one auto-popover light-dismisses every OTHER open
 *     auto-popover automatically (the platform's own top-layer arbitration). This is exactly the
 *     `registerTransient` + `closeOthersInLayer` triad `TransientController` hand-rolls — absorbed for free
 *     WITHIN the auto-popover set.
 *   - **Light-dismiss** — `popover="auto"`: Escape + outside-click close the popover natively. This is the
 *     `managesDismiss` document `pointerdown`/`keydown` capture pair — absorbed for free.
 *   - **Invoker** — `command="toggle-popover" commandfor="<id>"` (Invoker Commands, Baseline Dec 2025): a
 *     declarative open/close button with NO JS handler. (Plus the older `popovertarget` form for comparison.)
 *   - **Positioning** — CSS anchor positioning (`anchor-name` / `position-anchor` / `anchor()`): the panel
 *     positions against its trigger with NO measure-and-place JS.
 *
 * RESIDUE the controller would still own after a platform-shrink (the spike's finding, documented in §25.E):
 *   - **Cross-LAYER coordination** — `popover=auto` arbitrates only the auto-popover set; the `transient`
 *     vs `right-drawer` layer distinction (a drawer must close an open menu but a tooltip must not) is NOT a
 *     native concept. `transientLayerArbiter` stays the cross-layer authority.
 *   - **`dismissExclude`** — suppressing dismiss for a click on an EXTERNAL opener (e.g. a rail badge) has no
 *     native equivalent; light-dismiss fires regardless.
 *   - **Drawer scroll-lock + the property-driven focus bridge** — orthogonal to popover.
 *
 * Flag-gated: the element is DEFINED (inert) but only auto-MOUNTED when `?spike=native-popover` is present,
 * so production never shows it. The e2e spec (`e2e/native-popover-spike.spec.ts`) instantiates it directly to
 * MEASURE the platform behaviors on WebView2/Chromium.
 */
import { html, css } from 'lit';
import { JfElement } from '../shell-v0/primitives/JfElement.js';

/** True only when the spike is explicitly requested via the dev URL — production never auto-mounts it. */
export const NATIVE_POPOVER_SPIKE_ENABLED =
  typeof location !== 'undefined' && /[?&]spike=native-popover\b/.test(location.search);

export class NativePopoverSpike extends JfElement {
  static styles = css`
    :host {
      display: block;
      padding: 1rem;
      font-size: var(--font-size-md);
      color: var(--text-primary);
    }
    .row {
      display: flex;
      gap: 0.5rem;
      align-items: center;
    }
    #trigger-a {
      anchor-name: --spike-anchor-a;
    }
    [popover] {
      margin: 0;
      border: 1px solid var(--border-subtle);
      border-radius: 0.5rem;
      background: var(--surface-secondary);
      color: var(--text-primary);
      padding: 0.5rem 0.75rem;
      font-size: var(--font-size-sm);
      z-index: var(--z-overlay-transient);
    }
    /* Anchor positioning — the panel places itself against #trigger-a with no JS. */
    #panel-a {
      position: absolute;
      position-anchor: --spike-anchor-a;
      top: anchor(bottom);
      left: anchor(left);
    }
  `;

  render() {
    return html`
      <div class="row">
        <!-- Invoker Commands (Baseline Dec 2025): declarative toggle, NO JS handler. -->
        <button id="trigger-a" command="toggle-popover" commandfor="panel-a">Menu A (command)</button>
        <!-- Popover API invoker (Baseline 2024) for comparison. -->
        <button id="trigger-b" popovertarget="panel-b">Menu B (popovertarget)</button>
      </div>

      <!-- popover="auto": opening either light-dismisses the other (native single-open) + Esc/outside-click. -->
      <div id="panel-a" popover="auto">
        <p>Popover A — anchor-positioned under its trigger.</p>
      </div>
      <div id="panel-b" popover="auto">
        <p>Popover B — opening this auto-closes A (native single-open).</p>
      </div>
    `;
  }
}

if (typeof customElements !== 'undefined' && !customElements.get('jf-native-popover-spike')) {
  customElements.define('jf-native-popover-spike', NativePopoverSpike);
}

/** Auto-mount the spike into `document.body` ONLY when the `?spike=native-popover` flag is set. */
export function maybeMountNativePopoverSpike(doc: Document = document): void {
  if (!NATIVE_POPOVER_SPIKE_ENABLED) return;
  if (doc.querySelector('jf-native-popover-spike')) return;
  doc.body.appendChild(doc.createElement('jf-native-popover-spike'));
}
