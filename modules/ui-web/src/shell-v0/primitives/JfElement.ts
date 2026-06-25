// SPDX-License-Identifier: Apache-2.0
/**
 * JfElement — tempdoc 574 Move 1 (the delivery substrate; the enabling root of Part II).
 *
 * The one base class every shell-v0 Lit component extends. It adopts {@link ambientStyles} (the
 * Class-B tree-wide presentation facets) into EVERY component's shadow root, so "a component that
 * does not receive the projected ambient presentation" is unrepresentable — you cannot define a
 * `jf-*` element outside this base without the `jfelement-base` gate (Phase 5) failing. This is the
 * shadow-DOM analogue of `applyAppearance` being the single writer of `:root` (567): one host-owned
 * channel for ambient presentation, which components compose but cannot write to (569 §2 / Move 4).
 *
 * Mechanism: we override Lit's static `finalizeStyles` to PREPEND the ambient sheet to whatever the
 * subclass declares in `static styles`. Ambient comes first (lowest precedence) so a component's own
 * rules always win. Crucially, this means subclasses need **no change to their `static styles`** —
 * migrating a component is the single-token edit `extends LitElement` → `extends JfElement`.
 */
import { LitElement, type CSSResultGroup, type CSSResultOrNative } from 'lit';
import { ambientStyles } from './ambientStyles.js';

export class JfElement extends LitElement {
  static override finalizeStyles(styles?: CSSResultGroup): CSSResultOrNative[] {
    const ambient = super.finalizeStyles(ambientStyles);
    const own = super.finalizeStyles(styles);
    return [...ambient, ...own];
  }

  /**
   * Tempdoc 609 (instance-retention) — the ONE "settle on hide" seam, symmetric to the Stage's
   * instance RETAIN (chrome/Shell.ts `_surfaceElCache`). Because the Stage now retains each surface's
   * element instance across navigation, ALL of its component `@state` survives a tab switch — correct
   * for recoverable task state (drafts, selection, thread, expanded trees, scroll), but WRONG for
   * TRANSIENT state the working rule says must reset on navigation (in-flight/loading flags, stale
   * errors, partial stream buffers, transient confirmations/editors). This hook is auto-invoked on
   * every disconnect, so a surface can't forget to *wire* it — only to populate it. Default no-op:
   * harmless for the many non-surface (and non-retained) jf-* components. A retained surface that holds
   * transient `@state` OVERRIDES this to reset ONLY those fields, leaving recoverable state intact.
   * Render-safe: Lit does not render a disconnected element, so resetting `@state` here is silent.
   *
   * Tempdoc 609 §R (S1) — the DEFAULT implementation applies a declarative {@link transientState} map (the
   * common case), so a surface whose transients reset to simple values needs no method override at all —
   * just `static transientState = { busy: false, error: null }`. A surface with complex resets (object/array
   * fields, controller `.reset()` calls, derived clears) still OVERRIDES this; it may call
   * `super.settleTransients()` first to apply its declared simple fields, then add the custom logic.
   */
  protected settleTransients(): void {
    const decl = (this.constructor as typeof JfElement).transientState;
    if (!decl) return;
    const self = this as unknown as Record<string, unknown>;
    for (const key of Object.keys(decl)) self[key] = decl[key];
  }

  /**
   * Tempdoc 609 §R (S1) — declarative transient-`@state` reset map: field name → its cleared value. The
   * default {@link settleTransients} assigns each on hide. Values are PRIMITIVES on purpose (boolean /
   * string / number / null) — the unambiguous, safe majority; object/array clears (e.g. `busy = {}`) keep
   * an explicit `settleTransients` override (the honest limit). The settle-coverage gate accepts a field
   * listed here as "settled", so declaring it satisfies the transient-reset obligation.
   */
  static transientState?: Readonly<Record<string, boolean | string | number | null>>;

  /**
   * Tempdoc 609 §R (P3) — the symmetric "on reveal" seam, the reciprocal of {@link settleTransients}.
   * Fires when a RETAINED element is RE-connected (shown again after a tab switch), NOT on first mount —
   * so a surface backed by mutable server state can refresh-on-return (the Vue `activated` / React Activity
   * "re-run effects on show" half) without re-fetching on every initial render. Default no-op. Distinct
   * from `connectedCallback` (which fires on both first mount and reconnect): `onReveal` fires only on the
   * reconnect, the moment unique to instance-retention.
   */
  protected onReveal(): void {}

  private _hasConnectedBefore = false;

  override connectedCallback(): void {
    super.connectedCallback();
    if (this._hasConnectedBefore) {
      this.onReveal();
    }
    this._hasConnectedBefore = true;
  }

  override disconnectedCallback(): void {
    super.disconnectedCallback();
    this.settleTransients();
  }
}
