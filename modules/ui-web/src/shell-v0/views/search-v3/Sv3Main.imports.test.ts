// SPDX-License-Identifier: Apache-2.0

/**
 * The SHIPPED components rendered inside the Search v3 window, dressed in the window's tokens
 * (tempdoc 822 Phase F9 — the F-series fit audit's action 1).
 *
 * The window is dark by construction while the shipped app's `:root` is currently light, so every
 * custom property a nested shipped component reads and the window does NOT re-point is not a taste
 * difference but a polarity inversion. The audit measured two of them on screen: a tool-call card
 * painting `rgb(248,249,252)` under text at `oklch(0.97 0 0)` (white on white) and a reasoning
 * block painting `rgba(15,23,42,.58)` slate on `oklch(14.5% 0 0)`.
 *
 * This file is the standing guard, and it computes rather than looks:
 *
 *  1. **Closure** — every `var(--…)` the component's own source reads appears in its bridge block.
 *     A token the window forgets is exactly how the defect arrives, so the component's source is
 *     the checklist, not a hand-copied list.
 *  2. **Contrast** — every text/surface pair those components can paint resolves through the real
 *     token graph (oklch → sRGB, `color-mix` with alpha composited over the layers beneath it) and
 *     clears WCAG AA. The pairs are read from the components' own stylesheets, cited per case.
 *  3. **Polarity** — the surfaces are darker than the text they carry, which is the one thing the
 *     white-on-white card could not have claimed.
 */
import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { Sv3Main } from './Sv3Main.js';
import { sv3Tokens } from './sv3-tokens.css.js';
import { contrastRatio, relativeLuminance, WCAG_AA, type Rgb } from '../../themes/contrast.js';

/* ── The colour engine the browser would run, in the small part of CSS these tokens use ───────── */

interface Rgba {
  readonly rgb: Rgb;
  readonly a: number;
}

const clamp01 = (v: number): number => Math.min(1, Math.max(0, v));

/** oklch() → sRGB (Björn Ottosson's matrices), gamut-clipped the way a display clips it. */
function oklchToRgb(l: number, c: number, hDeg: number): Rgb {
  const h = (hDeg * Math.PI) / 180;
  const a = c * Math.cos(h);
  const b = c * Math.sin(h);
  const l_ = (l + 0.3963377774 * a + 0.2158037573 * b) ** 3;
  const m_ = (l - 0.1055613458 * a - 0.0638541728 * b) ** 3;
  const s_ = (l - 0.0894841775 * a - 1.291485548 * b) ** 3;
  const lin = [
    4.0767416621 * l_ - 3.3077115913 * m_ + 0.2309699292 * s_,
    -1.2684380046 * l_ + 2.6097574011 * m_ - 0.3413193965 * s_,
    -0.0041960863 * l_ - 0.7034186147 * m_ + 1.707614701 * s_,
  ];
  return lin.map((v) => {
    const u = clamp01(v);
    const encoded = u <= 0.0031308 ? 12.92 * u : 1.055 * u ** (1 / 2.4) - 0.055;
    return Math.round(encoded * 255);
  }) as unknown as Rgb;
}

/** Split a comma list at top level (commas inside nested parens belong to the nested function). */
function splitTop(input: string): string[] {
  const parts: string[] = [];
  let depth = 0;
  let start = 0;
  for (let i = 0; i < input.length; i += 1) {
    const ch = input[i];
    if (ch === '(') depth += 1;
    else if (ch === ')') depth -= 1;
    else if (ch === ',' && depth === 0) {
      parts.push(input.slice(start, i));
      start = i + 1;
    }
  }
  parts.push(input.slice(start));
  return parts.map((p) => p.trim());
}

/** Everything between `name(` and its matching `)`. */
function argsOf(value: string, name: string): string {
  const open = value.indexOf(`${name}(`) + name.length + 1;
  let depth = 1;
  for (let i = open; i < value.length; i += 1) {
    if (value[i] === '(') depth += 1;
    else if (value[i] === ')') {
      depth -= 1;
      if (depth === 0) return value.slice(open, i);
    }
  }
  throw new Error(`unbalanced ${name}() in ${value}`);
}

/** Premultiplied sRGB mix, which is what `color-mix(in srgb, …)` computes. */
function mix(one: Rgba, weight: number, two: Rgba): Rgba {
  const a = weight * one.a + (1 - weight) * two.a;
  if (a === 0) return { rgb: [0, 0, 0], a: 0 };
  const channel = (i: number): number =>
    (weight * one.a * (one.rgb[i] as number) + (1 - weight) * two.a * (two.rgb[i] as number)) / a;
  return { rgb: [channel(0), channel(1), channel(2)], a };
}

function composite(top: Rgba, under: Rgb): Rgb {
  return [0, 1, 2].map(
    (i) => top.a * (top.rgb[i] as number) + (1 - top.a) * (under[i] as number),
  ) as unknown as Rgb;
}

/* ── The token graph, read from the sheets that ship ──────────────────────────────────────────── */

/** The declarations of the FIRST `:host { … }` block — the window's dark set, its default. */
function hostTokens(cssText: string): Map<string, string> {
  const body = cssText.slice(cssText.indexOf(':host {') + 7, cssText.indexOf('\n  }'));
  return declarations(body);
}

/** `--name: value;` pairs, comments stripped. */
function declarations(body: string): Map<string, string> {
  const out = new Map<string, string>();
  for (const match of body.replace(/\/\*[\s\S]*?\*\//g, '').matchAll(/(--[\w-]+):([^;]+);/g)) {
    out.set((match[1] as string).trim(), (match[2] as string).trim().replace(/\s+/g, ' '));
  }
  return out;
}

/** A component's OWN stylesheet — the last entry in `static styles`. */
function ownStyles(ctor: { styles?: unknown }): string {
  const sheets = ctor.styles as ReadonlyArray<{ cssText: string }>;
  return sheets[sheets.length - 1]?.cssText ?? '';
}

/** The `<tag> { … }` bridge block inside a stylesheet. */
function bridgeFor(cssText: string, tag: string): Map<string, string> {
  const at = cssText.search(new RegExp(`(^|[\\s}])${tag}\\s*\\{`, 'm'));
  if (at < 0) throw new Error(`no bridge block for <${tag}>`);
  const open = cssText.indexOf('{', at);
  return declarations(cssText.slice(open + 1, cssText.indexOf('\n      }', open)));
}

const TOKENS = hostTokens(sv3Tokens.cssText);
const MAIN = ownStyles(Sv3Main);

const HERE = dirname(fileURLToPath(import.meta.url));
const componentSource = (file: string): string =>
  readFileSync(join(HERE, '..', '..', 'components', 'chat', file), 'utf8');

/** Resolve a CSS value to sRGB + alpha, following `var()` through the bridge and the token sheet. */
function color(expr: string, scope: Map<string, string>): Rgba {
  const value = expr.trim();
  if (value === 'transparent') return { rgb: [0, 0, 0], a: 0 };
  if (value.startsWith('var(')) {
    const name = splitTop(argsOf(value, 'var'))[0] as string;
    const next = scope.get(name) ?? TOKENS.get(name);
    if (next === undefined) throw new Error(`unresolved ${name}`);
    return color(next, scope);
  }
  if (value.startsWith('color-mix(')) {
    const [, first, second] = splitTop(argsOf(value, 'color-mix')) as [string, string, string];
    const percent = first.match(/\s(\d+(?:\.\d+)?)%$/);
    if (percent === null) throw new Error(`no weight in ${value}`);
    const weight = Number(percent[1]) / 100;
    return mix(
      color(first.slice(0, first.length - (percent[0] as string).length), scope),
      weight,
      color(second, scope),
    );
  }
  if (value.startsWith('oklch(')) {
    const [l, c, h] = argsOf(value, 'oklch').trim().split(/\s+/) as [string, string, string];
    return { rgb: oklchToRgb(Number.parseFloat(l) / 100, Number(c), Number(h)), a: 1 };
  }
  throw new Error(`unhandled colour value: ${value}`);
}

/** Paint a stack of (possibly translucent) surfaces from the window background up. */
function surface(layers: readonly string[], scope: Map<string, string>): Rgb {
  let under = composite(color('var(--background)', scope), [0, 0, 0]);
  for (const layer of layers) under = composite(color(layer, scope), under);
  return under;
}

interface Pair {
  readonly what: string;
  readonly text: string;
  readonly on: readonly string[];
  /** A FILLED control inverts on purpose (dark ink on the accent); only its ratio is checked. */
  readonly filled?: boolean;
}

function check(tag: string, pairs: readonly Pair[]): void {
  const scope = bridgeFor(MAIN, tag);
  for (const pair of pairs) {
    const bg = surface(pair.on, scope);
    const fg = composite(color(pair.text, scope), bg);
    const ratio = contrastRatio(fg, bg);
    expect(ratio, `${tag} — ${pair.what} (${ratio.toFixed(2)}:1)`).toBeGreaterThanOrEqual(WCAG_AA);
    if (pair.filled === true) continue;
    expect(
      relativeLuminance(bg),
      `${tag} — ${pair.what}: the surface is LIGHTER than its text (the light-theme fall-through)`,
    ).toBeLessThan(relativeLuminance(fg));
  }
}

/** Every custom property the component reads must be re-pointed by the window. */
function assertClosed(tag: string, source: string, alsoRead: readonly string[] = []): void {
  const scope = bridgeFor(MAIN, tag);
  const consumed = new Set<string>(alsoRead);
  for (const match of source.matchAll(/var\((--[\w-]+)/g)) consumed.add(match[1] as string);
  const missing = [...consumed].filter((name) => !scope.has(name)).sort();
  expect(missing, `<${tag}> reads tokens the window never re-points`).toEqual([]);
}

/* ── 1. Closure: nothing falls through to the shipped app's light `:root` ─────────────────────── */

describe('the three imported components read NO token the window leaves unbridged', () => {
  it('dresses <jf-tool-call-card>, including the tokens its inline status colour resolves', () => {
    // `ToolCallCard.ts:354` writes the status word's colour inline, but what `statusTone.ts:88-104`
    // returns is `var(--accent-<tone>)` — a custom property, so the host bridge reaches it after
    // all (the audit recorded it as unreachable; it is not).
    assertClosed('jf-tool-call-card', componentSource('ToolCallCard.ts'), [
      '--accent-success',
      '--accent-warning',
      '--accent-danger',
    ]);
  });

  it('dresses <jf-reasoning-block> and the markdown block nested inside it', () => {
    // The nested renderer is outside the `.sv3-markdown` class bridge, so its tokens arrive through
    // the reasoning block's host or not at all.
    assertClosed(
      'jf-reasoning-block',
      componentSource('ReasoningBlock.ts') + componentSource('MarkdownBlock.ts'),
      [],
    );
  });

  it('dresses <jf-citation-hover-card>', () => {
    assertClosed('jf-citation-hover-card', componentSource('CitationHoverCard.ts'));
  });
});

/* ── 2. Contrast, computed from the token values ──────────────────────────────────────────────── */

describe('every text/surface pair those components can paint clears WCAG AA on the dark window', () => {
  it('keeps the tool-call card legible — the white-on-white case the audit measured', () => {
    // Pairs read from `ToolCallCard.ts`: the card fill (:119), its wells (:189,200), the quoted
    // frame (:213), the subdued rungs (:142,148,172,219,275,284), the actions (:255-266), the
    // rejected reason (:269), the resource link (:245) and the inline status word (:354).
    check('jf-tool-call-card', [
      { what: 'tool name / body on the card', text: 'var(--foreground)', on: ['var(--surface-secondary)'] },
      { what: 'tool target, status, risk-why, because, expand', text: 'var(--text-secondary)', on: ['var(--surface-secondary)'] },
      { what: 'args / output text in a well', text: 'var(--foreground)', on: ['var(--surface-secondary)', 'var(--surface-tertiary)'] },
      { what: 'action button label', text: 'var(--text-primary)', on: ['var(--surface-secondary)', 'var(--surface-tertiary)'] },
      { what: 'primary action label on its fill', text: 'var(--accent-on-tint)', on: ['var(--surface-secondary)', 'var(--accent-tint)'], filled: true },
      { what: 'lineage frame label on the quoted frame', text: 'var(--text-secondary)', on: ['var(--surface-secondary)', 'var(--surface-2)'] },
      { what: 'rejected reason', text: 'var(--text-warning)', on: ['var(--surface-secondary)'] },
      { what: 'resource link', text: 'var(--accent)', on: ['var(--surface-secondary)'] },
      { what: 'status word — completed', text: 'var(--accent-success)', on: ['var(--surface-secondary)'] },
      { what: 'status word — waiting', text: 'var(--accent-warning)', on: ['var(--surface-secondary)'] },
      { what: 'status word — failed', text: 'var(--accent-danger)', on: ['var(--surface-secondary)'] },
      { what: 'status word — running', text: 'var(--accent-tint)', on: ['var(--surface-secondary)'] },
    ]);
  });

  it('keeps the reasoning block legible — the slate-on-black case the audit measured', () => {
    // Pairs read from `ReasoningBlock.ts`: the container (:65-71), the hover emphasis (:80,105),
    // and the nested markdown whose text it re-points to `--text-muted` (:120-123).
    check('jf-reasoning-block', [
      { what: 'thinking text on the container', text: 'var(--text-muted)', on: ['var(--surface-subtle)'] },
      { what: 'header under the pointer', text: 'var(--text-secondary)', on: ['var(--surface-subtle)'] },
      { what: 'copy button under the pointer', text: 'var(--text-secondary)', on: ['var(--surface-subtle)', 'var(--surface-subtle)'] },
      { what: 'nested markdown body', text: 'var(--text-muted)', on: ['var(--surface-subtle)'] },
      { what: 'nested markdown code', text: 'var(--text-muted)', on: ['var(--surface-subtle)', 'var(--surface-tertiary)'] },
      { what: 'nested markdown link', text: 'var(--text-tint)', on: ['var(--surface-subtle)'] },
    ]);
  });

  it('keeps the citation preview legible', () => {
    // Pairs read from `CitationHoverCard.ts`: the card (:57-64), the document name (:66-72), the
    // excerpt (:74) and the match score (:82-84).
    check('jf-citation-hover-card', [
      { what: 'card text', text: 'var(--text-primary)', on: ['var(--surface-2)'] },
      { what: 'document name', text: 'var(--text-tint)', on: ['var(--surface-2)'] },
      { what: 'excerpt', text: 'var(--text-secondary)', on: ['var(--surface-2)'] },
      { what: 'match score', text: 'var(--text-muted)', on: ['var(--surface-2)'] },
    ]);
  });
});

/* ── 3. The bridges carry the window's ramps, not the shipped app's ───────────────────────────── */

describe('the bridges also carry the window type and motion budget', () => {
  it('re-points the type ramp on all three, so an import cannot render at 13/11 px', () => {
    for (const tag of ['jf-tool-call-card', 'jf-reasoning-block', 'jf-citation-hover-card']) {
      const scope = bridgeFor(MAIN, tag);
      expect(scope.get('--font-size-sm'), tag).toBe('var(--font-size-sv3-sm)');
      expect(scope.get('--font-size-xs'), tag).toBe('var(--font-size-sv3-xs)');
    }
  });

  it('re-points every duration and easing a bridged component reads onto the window budget', () => {
    for (const tag of ['jf-tool-call-card', 'jf-reasoning-block', 'jf-citation-hover-card']) {
      const scope = bridgeFor(MAIN, tag);
      for (const [name, value] of scope) {
        if (name.startsWith('--duration')) expect(value, `${tag} ${name}`).toMatch(/--duration-sv3-/);
        if (name.startsWith('--ease')) expect(value, `${tag} ${name}`).toMatch(/--ease-sv3-/);
      }
    }
  });

  it('spends no fourth colour role: the risk edges are the window destructive/warning', () => {
    const scope = bridgeFor(MAIN, 'jf-tool-call-card');
    expect(scope.get('--accent-danger-45')).toContain('var(--destructive)');
    expect(scope.get('--accent-warning-45')).toContain('var(--warning)');
  });
});

/* ── 4. The name collision that made a link invisible (audit DEFECT-6) ────────────────────────── */

describe('the window does not re-use a shipped token NAME for a different meaning', () => {
  it('names its hover material outside the shipped vocabulary', () => {
    // The shipped `:root` defines `--accent` as a colour (`styles/tokens.css:124`) and
    // `ToolCallCard.ts:245` paints a link with it. While sv3 defined `--accent` as a 4 %-white
    // fill, that link rendered at 4 % opacity inside the window.
    expect(sv3Tokens.cssText).not.toMatch(/^\s*--accent:/m);
    expect(TOKENS.get('--accent-surface')).toBe('color-mix(in srgb, var(--color-white) 4%, transparent)');
    // …and where a shipped component genuinely wants a colour there, the bridge gives it one.
    expect(bridgeFor(MAIN, 'jf-tool-call-card').get('--accent')).toBe('var(--info-foreground)');
  });
});
