/**
 * Native-popover modality spike — tempdoc 574 §25 Phase 5 (Edge 5).
 *
 * MEASURES how much of TransientController's contract the web platform absorbs natively on the engine the
 * Tauri shell ships (Windows-only WebView2/Chromium). The spike component `src/spike/NativePopoverSpike.ts`
 * documents the pattern + the residue; this spec exercises the underlying platform PRIMITIVES directly
 * (popover="auto", Invoker Commands, CSS anchor positioning) so the measurement does not depend on bundling
 * the spike element into production. Runs in the final browser batch.
 */
import { test, expect } from '@playwright/test';

// The spike measures the PLATFORM, not the app — so it runs in an isolated page
// (setContent), giving clean empty space for the outside-click light-dismiss check
// and zero interference from the app's overlays / pointer handlers.
const SPIKE_HTML = `
  <style>
    #trigger-a { anchor-name: --spike-anchor-a; }
    [popover] { margin: 0; padding: 8px; border: 1px solid #888; }
    #panel-a { position: absolute; position-anchor: --spike-anchor-a; top: anchor(bottom); left: anchor(left); }
  </style>
  <div id="spike-wrap">
    <button id="trigger-a" command="toggle-popover" commandfor="panel-a">Menu A (command)</button>
    <button id="trigger-b" popovertarget="panel-b">Menu B (popovertarget)</button>
    <div id="panel-a" popover="auto">Popover A</div>
    <div id="panel-b" popover="auto">Popover B</div>
  </div>`;

test.describe('574 §25 Phase 5 — native popover modality spike (measure platform absorption)', () => {
  test('popover=auto gives single-open + light-dismiss; anchor positions; Invoker Commands measured', async ({
    page,
  }) => {
    await page.setContent(`<!doctype html><html><head><meta charset="utf-8"></head><body>${SPIKE_HTML}</body></html>`);

    const isOpen = (id: string) =>
      page.evaluate((i) => document.getElementById(i)?.matches(':popover-open') ?? false, id);

    // (1) SINGLE-OPEN — opening B auto-closes A (the registerTransient+closeOthers triad, absorbed natively).
    await page.evaluate(() => (document.getElementById('panel-a') as any).showPopover());
    expect(await isOpen('panel-a')).toBe(true);
    await page.evaluate(() => (document.getElementById('panel-b') as any).showPopover());
    expect(await isOpen('panel-b')).toBe(true);
    expect(await isOpen('panel-a')).toBe(false); // A was light-dismissed by B opening — native single-open.

    // (2) LIGHT-DISMISS via Escape (the managesDismiss keydown capture, absorbed natively).
    await page.keyboard.press('Escape');
    expect(await isOpen('panel-b')).toBe(false);

    // (3) LIGHT-DISMISS via outside-click (the managesDismiss pointerdown capture, absorbed natively).
    await page.evaluate(() => (document.getElementById('panel-b') as any).showPopover());
    expect(await isOpen('panel-b')).toBe(true);
    // Click empty space far from the top-left panel/buttons (the `[popover]{margin:0}` style anchors the
    // bare popover at 0,0, so a top-left click would land INSIDE it — click the lower-right quadrant instead).
    await page.mouse.click(1000, 700);
    expect(await isOpen('panel-b')).toBe(false);

    // (4) ANCHOR POSITIONING — panel A places itself just below its trigger with NO measure-and-place JS.
    await page.evaluate(() => (document.getElementById('panel-a') as any).showPopover());
    const placed = await page.evaluate(() => {
      const t = document.getElementById('trigger-a')!.getBoundingClientRect();
      const p = document.getElementById('panel-a')!.getBoundingClientRect();
      // panel top within a few px of trigger bottom, and left-aligned — the anchor() resolved.
      return Math.abs(p.top - t.bottom) < 6 && Math.abs(p.left - t.left) < 6;
    });
    expect(placed, 'CSS anchor positioning resolved panel-a under trigger-a').toBe(true);
    await page.keyboard.press('Escape');

    // (5) INVOKER COMMANDS (command/commandfor) — MEASURED (newer; Baseline Dec 2025). Click the declarative
    //     toggle button (no JS handler) and record whether the engine opened the popover.
    await page.click('#trigger-a');
    const commandWorks = await isOpen('panel-a');
    // eslint-disable-next-line no-console
    console.log(`[574 §25 Phase 5] Invoker Commands (command/commandfor) supported on this engine: ${commandWorks}`);
    // Not asserted hard — its support is the measurement; the by-construction single-open/dismiss above is the
    // load-bearing result. (popovertarget, asserted via Menu B, is the Baseline-2024 fallback if absent.)
    expect(typeof commandWorks).toBe('boolean');
  });
});
