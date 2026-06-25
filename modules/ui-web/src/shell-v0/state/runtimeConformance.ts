// SPDX-License-Identifier: Apache-2.0
/**
 * 569 Move 6 — apply-time RUNTIME conformance over the rendered DOM.
 *
 * The static gate ({@link certifyPresentation}) certifies what is checkable on the declaration
 * (closed vocab, literal token contrast). This adds the checks that need the RENDERED element:
 * the contrast oracle over the COMPUTED colours of the live DOM — catching a white-on-bright pair
 * produced by a `var()`/`oklch()`/`color-mix()` chain that a literal check cannot resolve (the
 * same canvas-resolver technique the 558 role co-projection uses). On a violation the region is
 * quarantined to the built-in render ({@link quarantineActiveSurface}) — degrade-never-fail.
 *
 * The FULL axe a11y sweep over the applied DOM is Move 6's "final live batch": it runs in the
 * e2e / ui-shot verification tier (the existing `@axe-core/playwright` path), not bundled into the
 * runtime — so the heavy audit lives in the verification tier, the cheap one at apply-time.
 *
 * The contrast sampler is injectable so the quarantine logic is unit-testable without a layout
 * engine; the default samples real computed colours and is exercised in the live batch.
 */
import { parseColor, contrastRatio, WCAG_AA } from '../themes/contrast.js';
import { quarantineActiveSurface } from './presentationRuntime.js';

export interface RuntimeAuditResult {
  readonly ok: boolean;
  readonly violations: readonly string[];
}

/** Samples a rendered node and returns human-readable contrast violations (empty = clean). */
export type ContrastSampler = (node: Element) => readonly string[];

/** Resolve any computed CSS colour (rgb/oklch/color-mix/…) to an `rgb()` string via a 1×1 canvas. */
function resolveComputed(value: string, doc: Document): string | null {
  if (!value) return null;
  const canvas = doc.createElement('canvas');
  canvas.width = 1;
  canvas.height = 1;
  const ctx = canvas.getContext('2d');
  if (!ctx) return value;
  ctx.fillStyle = '#000';
  ctx.fillStyle = value; // invalid value leaves the #000 reset
  ctx.fillRect(0, 0, 1, 1);
  const [r, g, b, a] = ctx.getImageData(0, 0, 1, 1).data;
  if (a === 0) return null; // fully transparent — not a foreground/background pair
  return `rgb(${r}, ${g}, ${b})`;
}

/**
 * Walk every element in `node`'s subtree, CROSSING shadow boundaries (the rendered controls live
 * in nested `jf-*` shadow roots — a plain `querySelectorAll('*')` would see none of them).
 */
function* deepElements(node: Element): Generator<Element> {
  yield node;
  const children = node.shadowRoot
    ? [...Array.from(node.shadowRoot.children), ...Array.from(node.children)]
    : Array.from(node.children);
  for (const child of children) yield* deepElements(child);
}

/** Does this element directly render text (a child text node), vs only aggregating descendants'? */
function hasDirectText(el: Element): boolean {
  for (const n of Array.from(el.childNodes)) {
    if (n.nodeType === 3 /* TEXT_NODE */ && (n.textContent ?? '').trim().length > 0) return true;
  }
  return false;
}

/** Resolve the nearest non-transparent background, ascending ACROSS shadow hosts. */
function resolveBackground(el: Element, win: Window, doc: Document): string | null {
  let cur: Element | null = el;
  while (cur) {
    const resolved = resolveComputed(win.getComputedStyle(cur).backgroundColor, doc);
    if (resolved) return resolved;
    if (cur.parentElement) {
      cur = cur.parentElement;
    } else {
      const root = cur.getRootNode();
      cur = root instanceof ShadowRoot ? (root.host as Element) : null;
    }
  }
  return null;
}

/**
 * Default sampler: for each element that DIRECTLY renders text (crossing shadow boundaries),
 * resolve its computed colour against the nearest non-transparent background and flag any pair
 * below the WCAG AA floor. Returns [] in a non-layout environment.
 */
export const defaultContrastSampler: ContrastSampler = (node) => {
  const doc = node.ownerDocument;
  const win = doc.defaultView;
  if (!win) return [];
  const violations: string[] = [];
  for (const el of deepElements(node)) {
    if (!hasDirectText(el)) continue;
    const fg = resolveComputed(win.getComputedStyle(el).color, doc);
    const bg = resolveBackground(el, win, doc);
    if (!fg || !bg) continue;
    const fgc = parseColor(fg);
    const bgc = parseColor(bg);
    if (!fgc || !bgc) continue;
    const ratio = contrastRatio(fgc, bgc);
    if (ratio < WCAG_AA) {
      violations.push(`computed contrast ${ratio.toFixed(2)}:1 on <${el.tagName.toLowerCase()}>`);
    }
  }
  return violations;
};

/** Audit a rendered surface node's computed contrast. */
export function auditRenderedSurface(
  node: Element,
  sample: ContrastSampler = defaultContrastSampler,
): RuntimeAuditResult {
  const violations = sample(node);
  return { ok: violations.length === 0, violations };
}

/** An a11y auditor over a rendered node — returns violation ids (default: a lazy axe-core pass). */
export type A11yAuditor = (node: Element) => Promise<readonly string[]>;

/**
 * Default a11y auditor: lazy-import axe-core and run the WCAG 2.0 A/AA rules over the rendered node
 * (the Move-6 "full a11y sweep over the applied DOM"). Lazy so axe is code-split out of the hot
 * bundle; tolerant (returns [] if axe can't load — e.g. a non-browser env), since the static gate +
 * the contrast sampler are the always-on floor.
 */
export const defaultA11yAuditor: A11yAuditor = async (node) => {
  try {
    const mod = (await import('axe-core')) as unknown as {
      default?: { run: (n: unknown, o: unknown) => Promise<{ violations: { id: string; nodes: unknown[] }[] }> };
      run?: (n: unknown, o: unknown) => Promise<{ violations: { id: string; nodes: unknown[] }[] }>;
    };
    const axe = mod.default ?? mod;
    if (!axe.run) return [];
    const res = await axe.run(node, { runOnly: { type: 'tag', values: ['wcag2a', 'wcag2aa'] } });
    return res.violations.map((v) => `a11y: ${v.id} (${v.nodes.length} node(s))`);
  } catch {
    return [];
  }
};

/** Audit a rendered surface's a11y (the apply-time axe sweep). Async; the auditor is injectable. */
export async function auditRenderedA11y(
  node: Element,
  audit: A11yAuditor = defaultA11yAuditor,
): Promise<RuntimeAuditResult> {
  const violations = await audit(node);
  return { ok: violations.length === 0, violations };
}

/**
 * The apply-time runtime loop: audit a rendered region and, on failure, quarantine it to the
 * built-in render. Returns the audit result. The region keeps rendering through the engine when
 * the audit is clean.
 */
export function auditAndQuarantine(
  regionId: string,
  node: Element,
  sample: ContrastSampler = defaultContrastSampler,
): RuntimeAuditResult {
  const result = auditRenderedSurface(node, sample);
  if (!result.ok) quarantineActiveSurface(regionId);
  return result;
}
