// @vitest-environment happy-dom
import { describe, it, expect } from 'vitest';
import { css } from 'lit';
import { JfElement } from './JfElement.js';

/** Serialize the CSSResult[] finalizeStyles returns (test env yields CSSResults with cssText). */
function text(styles: ReadonlyArray<unknown>): string {
  return styles.map((s) => (typeof (s as { cssText?: string }).cssText === 'string' ? (s as { cssText: string }).cssText : '')).join('\n');
}

describe('JfElement — ambient delivery substrate (574 Move 1)', () => {
  const finalize = (own?: unknown) =>
    text(
      (JfElement as unknown as { finalizeStyles(s?: unknown): ReadonlyArray<unknown> }).finalizeStyles(own),
    );

  it('prepends the ambient sheet to a subclass’s own styles', () => {
    const all = finalize(css`:host { color: rgb(1, 2, 3); }`);
    // Class-B ambient facets delivered into every component's shadow root:
    expect(all).toContain('::selection');
    expect(all).toContain('focus-visible');
    expect(all).toContain('visually-hidden');
    expect(all).toContain('@keyframes spin');
    // the subclass’s own styles are kept:
    expect(all).toContain('rgb(1, 2, 3)');
  });

  it('orders ambient BEFORE the subclass styles (ambient is lowest precedence)', () => {
    const all = finalize(css`:host { color: rgb(1, 2, 3); }`);
    expect(all.indexOf('::selection')).toBeLessThan(all.indexOf('rgb(1, 2, 3)'));
  });

  it('delivers ambient even when the subclass declares no styles', () => {
    const all = finalize(undefined);
    expect(all).toContain('::selection');
  });
});

/**
 * Tempdoc 609 (instance-retention) — the `settleTransients` seam is auto-invoked on disconnect. Under
 * instance-retention the Stage retains surfaces across navigation, so this hook is the one place a
 * surface resets transient state on hide. Lock the contract: a subclass's `settleTransients()` runs
 * exactly when the element disconnects; the base default is a harmless no-op.
 */
class SettleProbeElement extends JfElement {
  settleCount = 0;
  revealCount = 0;
  protected override settleTransients(): void {
    this.settleCount++;
  }
  protected override onReveal(): void {
    this.revealCount++;
  }
}
if (!customElements.get('jf-settle-probe')) {
  customElements.define('jf-settle-probe', SettleProbeElement);
}

describe('JfElement — settle-transients seam (tempdoc 609)', () => {
  it('invokes settleTransients() on every disconnect of a retained-then-hidden instance', () => {
    const el = document.createElement('jf-settle-probe') as SettleProbeElement;
    document.body.appendChild(el);
    expect(el.settleCount).toBe(0); // not on connect

    el.remove();
    expect(el.settleCount).toBe(1); // fired on disconnect

    // Reconnect the SAME instance (the retention path) then hide again — settles each hide.
    document.body.appendChild(el);
    el.remove();
    expect(el.settleCount).toBe(2);
  });

  it('default settleTransients is a harmless no-op (base JfElement disconnects cleanly)', () => {
    if (!customElements.get('jf-plain-jfelement')) {
      customElements.define('jf-plain-jfelement', class extends JfElement {});
    }
    const el = document.createElement('jf-plain-jfelement');
    document.body.appendChild(el);
    expect(() => el.remove()).not.toThrow();
  });
});

/**
 * Tempdoc 609 §R (P3) — the `onReveal()` seam fires only when a retained instance is RE-connected (shown
 * again), never on first mount. This is the refresh-on-return half, symmetric to settleTransients.
 */
describe('JfElement — onReveal seam (tempdoc 609 §R)', () => {
  it('fires onReveal on reconnect but NOT on first mount', () => {
    const el = document.createElement('jf-settle-probe') as SettleProbeElement;
    document.body.appendChild(el);
    expect(el.revealCount).toBe(0); // first mount → not a reveal

    el.remove(); // hide (settles)
    expect(el.revealCount).toBe(0);

    document.body.appendChild(el); // reconnect the SAME instance → reveal
    expect(el.revealCount).toBe(1);

    el.remove();
    document.body.appendChild(el); // second reveal
    expect(el.revealCount).toBe(2);
  });
});
