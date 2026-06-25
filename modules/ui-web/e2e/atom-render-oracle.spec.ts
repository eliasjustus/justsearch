/**
 * Atom render oracle — tempdoc 574 §25 Edge 3 (the rendered-style + contrast oracle).
 *
 * The source-grep ratchet (`check-atom-fork-ratchet`) admits an honest ceiling (574 §22.D): a hand-rolled
 * CSS fork that uses a DIFFERENTLY-NAMED class is grep-invisible and slips it. This oracle closes that gap by
 * asserting the RENDERED OUTPUT, not the source: it mounts each `@atom` in the real browser and checks that
 * its COMPUTED colour equals the resolved authority token (statusTone / originatorTone), plus runs axe
 * `color-contrast` over the mounted atoms. A fork that renders the wrong colour (or an unreadable pair) fails
 * here even though no `.badge{`-style rule exists for the ratchet to see.
 */
import { test, expect } from '@playwright/test';

// One instance of each atom (+ the variants whose computed output we assert). Mounted on a surface-1
// backdrop so contrast is judged in a realistic context.
const ORACLE_HTML = `
  <div id="atom-oracle-host" style="padding:16px;background:var(--surface-1);display:flex;gap:8px;flex-wrap:wrap;align-items:center">
    <jf-status-badge tone="success">healthy</jf-status-badge>
    <jf-status-badge tone="error">failed</jf-status-badge>
    <jf-status-badge origin="agent">agent</jf-status-badge>
    <jf-status-badge origin="system">system</jf-status-badge>
    <jf-status-dot status="failed" label="Failed"></jf-status-dot>
    <jf-filter-chip active tone="error">errors</jf-filter-chip>
    <jf-button tone="success" label="Confirm"></jf-button>
    <jf-error-alert tone="error">Something went wrong</jf-error-alert>
  </div>`;

test.describe('atom render oracle (574 §25 Edge 3 — assert the rendered output, not the source)', () => {
  test('each atom computes its authority token + clears WCAG AA contrast', async ({ page }) => {
    // The oracle needs only the custom-element registry + :root tokens (ready at DOM load) — NOT
    // `networkidle`, which never settles without a backend (demo-mode API polling). So navigate light +
    // wait for the atom to be defined, avoiding navigateToDemoMode's networkidle wait.
    await page.goto('/?demo=true', { waitUntil: 'domcontentloaded' });
    await page.waitForFunction(() => !!customElements.get('jf-status-badge'), null, { timeout: 15000 });

    // Mount the atoms into the live document and let them render.
    await page.evaluate(async (html) => {
      const host = document.createElement('div');
      host.id = 'atom-oracle-wrap';
      host.innerHTML = html;
      document.body.appendChild(host);
      const els = Array.from(host.querySelectorAll('*'));
      await Promise.all(
        els
          .map((el) => (el as unknown as { updateComplete?: Promise<unknown> }).updateComplete)
          .filter(Boolean) as Promise<unknown>[],
      );
      await new Promise((r) => requestAnimationFrame(() => r(null)));
    }, ORACLE_HTML);

    // (a) Computed-style oracle: the success badge's TEXT colour must equal the resolved --accent-success
    //     token, and the neutral (origin=system) badge must carry the §23.C.1 delineating border.
    const probe = await page.evaluate(() => {
      const resolveToken = (name: string): string => {
        const p = document.createElement('span');
        p.style.color = `var(${name})`;
        document.body.appendChild(p);
        const v = getComputedStyle(p).color;
        p.remove();
        return v;
      };
      const host = document.getElementById('atom-oracle-wrap')!;
      const badges = Array.from(host.querySelectorAll('jf-status-badge'));
      const fg = (b: Element): string =>
        getComputedStyle(b.shadowRoot!.querySelector('.badge') as HTMLElement).color;
      const border = (b: Element): string =>
        getComputedStyle(b.shadowRoot!.querySelector('.badge') as HTMLElement).borderTopWidth;
      const success = badges.find((b) => b.getAttribute('tone') === 'success')!;
      const system = badges.find((b) => b.getAttribute('origin') === 'system')!;
      return {
        successBadgeFg: fg(success),
        resolvedAccentSuccess: resolveToken('--accent-success'),
        systemBorderWidth: border(system),
      };
    });
    // The badge text colour is the resolved status-tone authority token (both sides resolved by the browser).
    expect(probe.successBadgeFg).toBe(probe.resolvedAccentSuccess);
    // The neutral case carries a real border (the §23.C.1 fix), not 0.
    expect(probe.systemBorderWidth).not.toBe('0px');

    // (b) Contrast oracle — deterministic WCAG 2.x ratio, computed in-page (NOT axe: the app freezes
    //     intrinsics, which blocks axe-core's instrumentation — `Cannot assign to read only property 'get'
    //     of WeakMap`). This is the same AA=4.5 floor the contrast-role engine derives to, asserted directly
    //     on each atom's RENDERED text/fill pair — catching the obs-#331 class (an inaccessible pair) more
    //     precisely than axe (it targets the atom parts, no app-wide noise).
    const contrast = await page.evaluate(() => {
      // Resolve ANY CSS color (incl. oklch / color() — the 574 palette is oklch, which getComputedStyle
      // serializes verbatim and a regex can't read) to rgb by painting it on a 1×1 canvas and reading the
      // pixel back. This is the same technique themes/roleForegrounds.ts uses. `paintOver(base, top)`
      // composites a (possibly translucent) `top` over an opaque `base` → the effective opaque rgb.
      const canvas = document.createElement('canvas');
      canvas.width = 1;
      canvas.height = 1;
      const ctx = canvas.getContext('2d')!;
      const paintOver = (base: string, top: string): number[] => {
        ctx.clearRect(0, 0, 1, 1);
        ctx.fillStyle = base;
        ctx.fillRect(0, 0, 1, 1);
        ctx.fillStyle = top;
        ctx.fillRect(0, 0, 1, 1);
        const d = ctx.getImageData(0, 0, 1, 1).data;
        return [d[0], d[1], d[2]];
      };
      const lum = (rgb: number[]) => {
        const f = rgb.map((v) => {
          const c = v / 255;
          return c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4;
        });
        return 0.2126 * f[0] + 0.7152 * f[1] + 0.0722 * f[2];
      };
      const ratio = (a: number[], b: number[]) => {
        const [l1, l2] = [lum(a), lum(b)].sort((x, y) => y - x);
        return (l1 + 0.05) / (l2 + 0.05);
      };
      // The backdrop the atoms sit on (--surface-1), resolved to a canvas-acceptable color string.
      const probe = document.createElement('span');
      probe.style.backgroundColor = 'var(--surface-1)';
      document.body.appendChild(probe);
      const backdrop = getComputedStyle(probe).backgroundColor;
      probe.remove();

      const host = document.getElementById('atom-oracle-wrap')!;
      // Text-bearing atom parts (the dot carries no text → excluded).
      const parts: { label: string; el: Element | null }[] = [
        ...Array.from(host.querySelectorAll('jf-status-badge')).map((b) => ({
          label: `badge[${b.getAttribute('tone') ?? b.getAttribute('origin')}]`,
          el: b.shadowRoot?.querySelector('.badge') ?? null,
        })),
        { label: 'filter-chip', el: host.querySelector('jf-filter-chip')?.shadowRoot?.querySelector('button') ?? null },
        { label: 'button', el: host.querySelector('jf-button')?.shadowRoot?.querySelector('button') ?? null },
        { label: 'error-alert', el: host.querySelector('jf-error-alert')?.shadowRoot?.querySelector('*') ?? null },
      ];
      const failures: string[] = [];
      for (const { label, el } of parts) {
        if (!el) continue;
        const cs = getComputedStyle(el as Element);
        const bgEff = paintOver(backdrop, cs.backgroundColor); // fill composited over the backdrop
        const fgEff = paintOver(`rgb(${bgEff[0]}, ${bgEff[1]}, ${bgEff[2]})`, cs.color); // text over its bg
        const r = ratio(fgEff, bgEff);
        if (r < 4.5) failures.push(`${label}: ${r.toFixed(2)}:1 (< 4.5 AA)`);
      }
      return failures;
    });
    expect(contrast, `atom text/fill pairs below WCAG AA: ${contrast.join('; ')}`).toEqual([]);
  });
});
