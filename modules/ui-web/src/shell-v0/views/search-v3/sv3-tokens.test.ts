// @vitest-environment happy-dom

/**
 * Token enforcement (tempdoc 822 slice 1, donor law 2).
 *
 * The donor's geometry tokens exist "so sidebar, palette, tooltip and toolbar controls cannot
 * quietly drift apart" — and the donor makes that real with unit tests asserting each component's
 * USE of the token, not just the token's existence. These are those tests: a component that
 * re-hardcodes 52px, 8px or a radius fails here even though it still renders correctly.
 */
import { describe, it, expect } from 'vitest';
import { sv3Tokens } from './sv3-tokens.css.js';
import { sv3Shared } from './sv3-shared-styles.js';
import { SearchV3View } from './SearchV3View.js';
import { Sv3Topbar } from './Sv3Topbar.js';
import { Sv3Sidebar } from './Sv3Sidebar.js';
import { Sv3SessionRow } from './Sv3SessionRow.js';
import { Sv3Main } from './Sv3Main.js';
import { Sv3Composer } from './Sv3Composer.js';

const tokens = sv3Tokens.cssText;
const shared = sv3Shared.cssText;
/**
 * A component's OWN stylesheet — the last entry in its `static styles`, after the shared sheets it
 * adopts. Asserting against the whole array would let a token declared in `sv3Tokens` satisfy a
 * "this region reads the token" claim.
 */
const styleTextOf = (ctor: { styles?: unknown }): string => {
  const sheets = ctor.styles as ReadonlyArray<{ cssText: string }>;
  return sheets[sheets.length - 1]?.cssText ?? '';
};

describe('the token sheet is host-scoped, never global', () => {
  it('declares the palette on the window host and nowhere else', () => {
    expect(tokens).toContain(':host {');
    expect(tokens).not.toContain(':root');
    expect(tokens).not.toContain('html');
  });

  it('carries both color sets, dark as the default and light behind the theme attribute', () => {
    expect(tokens).toContain('color-scheme: dark');
    expect(tokens).toContain(":host([theme='light'])");
    expect(tokens).toContain('color-scheme: light');
    // The dark base is the near-black the surfaces lift FROM.
    expect(tokens).toContain('--background: var(--color-neutral-950)');
  });

  it('keeps the donor brand hue out and JustSearch accent in, with the structure intact', () => {
    expect(tokens).toContain('--primary: var(--color-teal-accent)');
    expect(tokens).toContain('--color-teal-accent: oklch(75% 0.15 180)');
    expect(tokens).toContain('--ring: var(--primary)');
    expect(tokens).toContain('--update: var(--primary)');
    // The donor's brand blue, in either mode.
    expect(tokens).not.toContain('0.217 264');
    expect(tokens).not.toContain('0.21 264');
  });
});

describe('the geometry tokens the window is built from', () => {
  it('declares the donor geometry set verbatim', () => {
    for (const decl of [
      '--control-radius: 0.5rem',
      '--sidebar-content-inset: 0.5rem',
      '--sidebar-control-gap: 0.5rem',
      '--sidebar-row-content-inset: 0.625rem',
      '--command-shell-inset: 0.5rem',
      '--command-content-inset: 1rem',
      '--floating-content-inset: 0.75rem',
      '--workspace-topbar-height: 52px',
      '--sidebar-width: 16rem',
      '--glass-blur: 16px',
      '--glass-opacity: 80%',
      '--glass-saturation: 1.08',
      '--app-scrollbar-width: 6px',
    ]) {
      expect(tokens).toContain(decl);
    }
  });

  it('derives the radius ladder additively off one knob, with controls on a second', () => {
    expect(tokens).toContain('--radius: 0.625rem');
    expect(tokens).toContain('--radius-sm: calc(var(--radius) - 4px)');
    expect(tokens).toContain('--radius-md: calc(var(--radius) - 2px)');
    expect(tokens).toContain('--radius-lg: var(--radius)');
    expect(tokens).toContain('--radius-xl: calc(var(--radius) + 4px)');
    expect(tokens).toContain('--radius-2xl: calc(var(--radius) + 8px)');
    expect(tokens).toContain('--radius-3xl: calc(var(--radius) + 12px)');
    expect(tokens).toContain('--radius-4xl: calc(var(--radius) + 16px)');
    // The second knob is independent of the ladder — a control is not a surface.
    expect(tokens).not.toContain('--control-radius: var(--radius');
  });

  it('declares the three donor improvements: z-scale, spacing ladder, pad compensation', () => {
    for (const name of [
      '--z-content',
      '--z-sticky',
      '--z-overlay',
      '--z-dialog',
      '--z-tooltip',
      '--z-toast',
    ]) {
      expect(tokens).toContain(`${name}:`);
    }
    // Tooltips sit above dialogs.
    expect(tokens).toContain('--z-dialog: 50');
    expect(tokens).toContain('--z-tooltip: 70');

    for (let step = 1; step <= 12; step += 1) {
      expect(tokens).toContain(`--space-${step}: ${step * 4}px`);
    }

    expect(tokens).toContain('--control-pad-3: calc(0.75rem - 1px)');
  });

  it('keeps the platform type stack and no shipped face', () => {
    expect(tokens).toContain(
      "--font-sans: -apple-system, BlinkMacSystemFont, 'Segoe UI', system-ui, sans-serif",
    );
    expect(tokens).toContain('--font-mono: ui-monospace');
    expect(tokens).not.toContain('@font-face');
  });

  it('leaves the Electron titlebar env() values behind, keeping only the indirection', () => {
    expect(tokens).toContain('--workspace-controls-left: 0.75rem');
    expect(tokens).toContain('--workspace-controls-right: 0.75rem');
    expect(tokens).not.toContain('titlebar-area');
  });
});

describe('every region reads the tokens rather than re-hardcoding them', () => {
  it('the topbar takes its height from the workspace token, both floor and ceiling', () => {
    const styles = styleTextOf(Sv3Topbar);
    expect(styles).toContain('height: var(--workspace-topbar-height)');
    expect(styles).toContain('min-height: var(--workspace-topbar-height)');
    expect(styles).not.toContain('52px');
    // The icon controls read the control knob, not the surface ladder.
    expect(styles).toContain('border-radius: var(--control-radius)');
    expect(styles).toContain('background: var(--toolbar-control-hover)');
  });

  it('the sidebar row reads the control radius and the second-level inset', () => {
    const styles = styleTextOf(Sv3SessionRow);
    expect(styles).toContain('border-radius: var(--control-radius)');
    expect(styles).toContain('padding-inline: var(--sidebar-row-content-inset)');
    expect(styles).toContain('background: var(--sidebar-row-hover)');
    expect(styles).toContain('gap: var(--sidebar-control-gap)');
    // ...and the panel's own inset is the first level, so the fill reads as a pill.
    expect(styleTextOf(Sv3Sidebar)).toContain('padding: var(--sidebar-content-inset)');
  });

  it('the group label row is the donor ladder read through tokens', () => {
    const styles = styleTextOf(Sv3Sidebar);
    const rule = styles.slice(styles.indexOf('.group-label {'), styles.indexOf('.groups {'));
    expect(rule).toContain('height: var(--space-8)');
    expect(rule).toContain('padding-inline: var(--space-2)');
    expect(rule).toContain('font-size: var(--font-size-sv3-xs)');
    expect(rule).toContain('font-weight: 500');
    expect(rule).toContain('color: var(--sidebar-muted-foreground)');
  });

  it('the window sizes the sidebar from the token and does not let it flex', () => {
    const styles = styleTextOf(SearchV3View);
    expect(styles).toContain('flex: 0 0 var(--sidebar-width)');
    expect(styles).toContain('width: var(--sidebar-width)');
    expect(styles).not.toContain('16rem');
  });

  it('the composer reads the floating inset, the surface ladder and the pad compensation', () => {
    const styles = styleTextOf(Sv3Composer);
    expect(styles).toContain('padding: var(--floating-content-inset)');
    expect(styles).toContain('border-radius: var(--radius-xl)');
    expect(styles).toContain('padding: var(--control-pad-3)');
  });

  it('the main surface reads the semantic colors, not literals', () => {
    const styles = styleTextOf(Sv3Main);
    expect(styles).toContain('background: var(--background)');
    expect(styles).toContain('color: var(--foreground)');
  });
});

/**
 * A geometry token names a TOTAL, not a floor. Live measurement caught the sidebar at 273px
 * (256 + the 8px inset on both sides + a 1px border, all added outside the token) and the topbar at
 * 53px (52 + its rule) — the default content-box quietly turning both tokens into "at least".
 *
 * happy-dom runs no layout engine, so these pin the box MATH that produces the rendered total: the
 * border-box rule that makes padding and border count inward, plus the absence of anything (margin,
 * outer padding, gap) that would add width outside the sized box. The rendered pixels themselves
 * are measured live.
 */
describe('the sized regions render at exactly their token, not the token plus trim', () => {
  const hostRuleOf = (styles: string): string =>
    styles.slice(styles.indexOf(':host {'), styles.indexOf('}', styles.indexOf(':host {')));

  it('the shared sheet makes padding and border count inward, for hosts and content alike', () => {
    const rule = shared.slice(shared.indexOf(':host,'), shared.indexOf('@keyframes'));
    expect(rule).toContain('*');
    expect(rule).toContain('box-sizing: border-box');
  });

  it('the sidebar keeps its inset and its border inside the 256px region', () => {
    const host = hostRuleOf(styleTextOf(Sv3Sidebar));
    expect(host).toContain('padding: var(--sidebar-content-inset)');
    expect(host).toContain('border-right: 1px solid var(--sidebar-border)');
    // Anything outside the box would widen the region past the token.
    expect(host).not.toContain('margin');
    expect(host).not.toContain('min-width');
  });

  it('the window sizes the sidebar by the token alone and adds no gap beside it', () => {
    const styles = styleTextOf(SearchV3View);
    expect(styles).toContain('flex: 0 0 var(--sidebar-width)');
    expect(styles).not.toContain('gap:');
    const host = hostRuleOf(styles);
    expect(host).toContain('display: flex');
    expect(host).not.toContain('padding');
  });

  it('the session row is 32px total, with both insets counting inward', () => {
    const styles = styleTextOf(Sv3SessionRow);
    const start = styles.indexOf('button.row {');
    const rule = styles.slice(start, styles.indexOf('}', start));
    expect(rule).toContain('height: var(--space-8)');
    expect(rule).toContain('padding-inline: var(--sidebar-row-content-inset)');
    // Anything outside the box would push the row past the 32px the ladder claims.
    expect(rule).not.toContain('margin');
    expect(rule).not.toContain('min-height');
    expect(rule).not.toContain('border-width');
    expect(rule).toContain('border: 0');
    // The intrinsic size a skipped row reports must equal the size it actually renders at.
    expect(rule).toContain('contain-intrinsic-size: auto var(--space-8)');
    // The row host adds nothing around the button.
    const host = hostRuleOf(styles);
    expect(host).toContain('display: block');
    expect(host).not.toContain('padding');
    expect(host).not.toContain('margin');
  });

  it('the topbar keeps its rule inside the 52px band', () => {
    const host = hostRuleOf(styleTextOf(Sv3Topbar));
    expect(host).toContain('height: var(--workspace-topbar-height)');
    expect(host).toContain('border-bottom: 1px solid var(--toolbar-border)');
    expect(host).not.toContain('margin');
    // A max-height below the declared height would shrink the band instead.
    expect(host).not.toContain('max-height');
  });
});

/**
 * Donor §6.1/§6.2, as mechanism rather than appearance. happy-dom runs no cascade over adopted
 * sheets, so these pin the SELECTORS that decide the outcome: a fill that wins because it was
 * declared later is one edit away from stacking two fills, while a fill guarded by `:not()` cannot.
 */
describe('the session row spends one fill and three colours, by construction', () => {
  const styles = styleTextOf(Sv3SessionRow);
  /** The declaration block of the first rule whose selector starts with `sel`. */
  const ruleFor = (sel: string): string => {
    const at = styles.indexOf(sel);
    expect(at, `no rule for ${sel}`).toBeGreaterThan(-1);
    return styles.slice(at, styles.indexOf('}', at));
  };

  it('ranks active over selected over hover, each guarded out of the one above it', () => {
    expect(ruleFor(':host([active]) button.row')).toContain(
      'background: var(--sidebar-row-active)',
    );
    // Selected only applies where active does not — precedence lives in the selector.
    expect(ruleFor(':host([selected]:not([active])) button.row')).toContain(
      'background: var(--sidebar-row-selected)',
    );
    // ...and hover only where NEITHER claimed the row, so hover+selected cannot show two fills.
    expect(ruleFor(':host(:not([active]):not([selected])) button.row:hover')).toContain(
      'background: var(--sidebar-row-hover)',
    );

    // No hover rule anywhere in the row is UNguarded: an unguarded one would paint over the
    // active fill the moment the pointer crossed the claimed row.
    const hoverSelectors = [...styles.matchAll(/[^\n]*button\.row:hover[^\n]*/g)].map((m) => m[0]);
    expect(hoverSelectors.length).toBeGreaterThanOrEqual(2);
    for (const selector of hoverSelectors) {
      expect(selector, `unguarded hover rule: ${selector.trim()}`).toContain(':not(');
    }
  });

  it('keeps the in-flight dim orthogonal to the fill ladder and off the claimed row', () => {
    expect(ruleFor(':host([inflight]:not([active]):not([selected])) button.row {')).toContain(
      'opacity: 0.7',
    );
    expect(ruleFor(':host([inflight]:not([active]):not([selected])) button.row:hover')).toContain(
      'opacity: 1',
    );
  });

  it('spends colour on exactly the three status states, through semantic tokens', () => {
    expect(ruleFor(":host([status='act-now']) .dot")).toContain('background: var(--success)');
    expect(ruleFor(":host([status='in-motion']) .dot")).toContain('background: var(--warning)');
    expect(ruleFor(":host([status='broken']) .dot")).toContain('background: var(--destructive)');
    // A fourth status colour, or a literal instead of a token, is what this forbids.
    expect(styles).not.toContain("[status='resting']");
    expect(styles).not.toMatch(/#[0-9a-fA-F]{3}/);
    for (const literal of ['rgb(', 'hsl(', 'oklch(']) {
      expect(styles).not.toContain(literal);
    }
  });

  it('carries emphasis as foreground alpha, never as a second hue', () => {
    expect(ruleFor('.row-label {')).toContain(
      'color: color-mix(in srgb, var(--foreground) 90%, transparent)',
    );
    expect(ruleFor(':host([unread]) .row-label')).toContain('color: var(--foreground)');
    expect(ruleFor(":host([status='broken']) .row-label")).toContain(
      'color: color-mix(in srgb, var(--foreground) 95%, transparent)',
    );
    expect(ruleFor(':host([receded]:not([active]):not([selected])) .row-label')).toContain(
      'color: color-mix(in srgb, var(--sidebar-muted-foreground) 75%, transparent)',
    );
    // Truncation is the default for a row title (density §7).
    expect(ruleFor('.row-label {')).toContain('text-overflow: ellipsis');
    expect(ruleFor('.row-label {')).toContain('white-space: nowrap');
  });

  it('sizes the status dot and its ping off the spacing ladder', () => {
    expect(ruleFor('.dot-box {')).toContain('inline-size: var(--space-3)');
    expect(ruleFor('.dot {')).toContain('inline-size: var(--space-2)');
    expect(ruleFor(":host([status='in-motion']) .ping")).toContain(
      'background: color-mix(in srgb, var(--warning) 60%, transparent)',
    );
    // The slot reserves a floor so a row does not jitter when its contents change width.
    expect(ruleFor('.status-slot {')).toContain('min-inline-size: var(--space-8)');
  });
});

/**
 * Keyframes resolve per shadow root: an `animation` naming a keyframe that is not in THIS
 * component's own adopted sheets fails silently — the element simply never moves. So every
 * animation the row can run is checked against the sheets the row actually adopts.
 */
describe('every animation the row can run resolves inside its own adopted sheets', () => {
  const adopted = (Sv3SessionRow.styles as ReadonlyArray<{ cssText: string }>)
    .map((s) => s.cssText)
    .join('\n');

  it('names only keyframes declared in the sheets the component adopts', () => {
    const classes = [...adopted.matchAll(/sv3-anim-([a-z-]+)/g)].map((m) => m[1]);
    // The row runs the in-motion ping; if it ever runs more, each one is checked here too.
    expect(classes).toContain('status-ping');
    const shorthand = [...adopted.matchAll(/animation:\s*([a-z-]+)\s/g)].map((m) => m[1]);
    expect(shorthand.length).toBeGreaterThan(0);
    for (const name of shorthand) {
      if (name === 'none') continue;
      expect(adopted).toContain(`@keyframes ${name}`);
    }
  });
});

describe('the shared sheet carries what tokens cannot', () => {
  it('declares the four looping keyframes, because keyframes do not inherit into shadow roots', () => {
    for (const name of ['skeleton', 'ghost-pulse', 'status-pulse', 'status-ping']) {
      expect(shared).toContain(`@keyframes ${name}`);
    }
  });

  it('duty-cycles them: stepped ramps and a hold at each extreme', () => {
    expect(shared).toContain('steps(4)');
    expect(shared).toContain('steps(6)');
    expect(shared).toContain('steps(8)');
    // The holds — an extreme is carried across a span, never a single stop.
    expect(shared).toContain('40% {');
    expect(shared).toContain('42% {');
  });

  it('reads the scrollbar tokens through the inherited standard property', () => {
    expect(shared).toContain('scrollbar-color: var(--app-scrollbar-thumb) transparent');
  });

  it('drops the looping animations under reduced motion', () => {
    const guard = shared.slice(shared.indexOf('@media (prefers-reduced-motion: reduce)'));
    expect(guard).toContain('animation: none');
    for (const name of ['skeleton', 'ghost-pulse', 'status-pulse', 'status-ping']) {
      expect(guard).toContain(`.sv3-anim-${name}`);
    }
  });
});
