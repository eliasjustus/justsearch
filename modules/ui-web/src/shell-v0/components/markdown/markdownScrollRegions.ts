// SPDX-License-Identifier: Apache-2.0
/**
 * Tempdoc 853 (F-05) — keyboard reachability for the markdown ramp's own scroll containers.
 *
 * `markdownStyles.ts` makes two rendered elements horizontally scrollable: `pre { overflow-x: auto }`
 * on the default path, and `:host([prose]) table { display: block; overflow-x: auto }`. Neither is
 * focusable, so a keyboard-only user cannot reach the clipped half of a wide code fence or a wide
 * table — measured as axe `scrollable-region-focusable` (serious, WCAG 2.1.1) on the `DocumentPane`
 * reading pane in all four palettes, 2026-08-19 UX audit.
 *
 * Why a post-render DOM pass, not markup: the renderer emits bare `<pre>` / `<table>` through
 * `unsafeHTML`, and the sheet's own comment rules out synthesising a wrapper (every re-render
 * rebuilds the subtree, so a wrapper would have to be re-applied per frame anyway). The pass is the
 * shape this module directory already uses for exactly that reason — `markdownHighlight.ts` walks
 * the same freshly-rendered root — so this is a sibling of that pass, called next to it, and
 * re-applied on every settled render because Lit's rebuild takes the attributes with it.
 *
 * Unconditional, not measured: a declared scroll container is one at *some* viewport width, and a
 * render-time `scrollWidth > clientWidth` probe would leave the region unreachable exactly when the
 * window is narrow enough to clip it. The same reasoning is why `DocumentPane`'s own `.scroll-region`
 * and `UnifiedChatView`'s transcript carry a static `tabindex="0"`.
 */

/** `<pre>` maps to `generic`, where `aria-label` is prohibited — `group` is the smallest role that
 *  takes a name without minting a landmark for every code fence on screen. A `<table>` keeps its own
 *  role (overriding it would cost the reader the table semantics) and takes the name directly. */
const REGIONS: ReadonlyArray<{ selector: string; role: string | null; label: string }> = [
  { selector: 'pre', role: 'group', label: 'Code block' },
  { selector: 'table', role: null, label: 'Table' },
];

/**
 * Make every markdown-rendered scroll container under `root` focusable and named.
 *
 * Safe on every render: setting the same attributes again is a no-op, and an element that already
 * carries an author-supplied `tabindex` is left alone.
 */
export function markScrollableRegions(root: ParentNode | null | undefined): void {
  if (!root) return;
  for (const { selector, role, label } of REGIONS) {
    for (const el of root.querySelectorAll(selector)) {
      if (el.hasAttribute('tabindex')) continue;
      el.setAttribute('tabindex', '0');
      if (role && !el.hasAttribute('role')) el.setAttribute('role', role);
      if (!el.hasAttribute('aria-label')) el.setAttribute('aria-label', label);
    }
  }
}
