// @vitest-environment happy-dom

/**
 * <jf-macro-dry-run> tests — 543-fwd idea #12 (macro dry-run diff).
 *
 * Drives the real component through the real macros substrate (single module
 * instance in the test env).
 */

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { MacroDryRun } from './MacroDryRun.js';
import './MacroDryRun.js';
import { defineMacro, __resetMacrosForTest } from '../substrates/macros/index.js';
import { __resetActionsForTest } from '../substrates/actions/index.js';

void MacroDryRun;

let host: MacroDryRun;

beforeEach(() => {
  __resetMacrosForTest();
  __resetActionsForTest();
  globalThis.localStorage?.clear();
  host = document.createElement('jf-macro-dry-run') as MacroDryRun;
  document.body.appendChild(host);
});

afterEach(() => {
  host.remove();
});

async function settle(): Promise<void> {
  await (host as unknown as { updateComplete: Promise<unknown> }).updateComplete;
}

/**
 * 574 B (remediation) — cancel/run are <jf-button>; the action fires from the native <button>
 * inside the composed <jf-control>, not a host click. Awaits both render passes, then clicks.
 */
async function activateJfButton(el: Element | null | undefined): Promise<void> {
  if (!el) throw new Error('activateJfButton: element not found');
  await (el as unknown as { updateComplete: Promise<unknown> }).updateComplete;
  const control = el.shadowRoot!.querySelector('jf-control')!;
  await (control as unknown as { updateComplete: Promise<unknown> }).updateComplete;
  (control.shadowRoot!.querySelector('button') as HTMLButtonElement).click();
}

describe('<jf-macro-dry-run> (543-fwd #12)', () => {
  it('opens via jf-open-macro-dry-run and lists each effect labelled', async () => {
    defineMacro({
      id: 'fe-only',
      label: 'FE only',
      effects: [
        { kind: 'navigate', to: '#a' },
        { kind: 'toast', message: 'hi' },
      ],
    });
    document.dispatchEvent(
      new CustomEvent('jf-open-macro-dry-run', { detail: { macroId: 'fe-only' } }),
    );
    await settle();
    expect(host.open).toBe(true);
    const rows = host.shadowRoot?.querySelectorAll('[data-testid="macro-dry-run-row"]') ?? [];
    // Each row labels the effect via the shared <jf-effect-line> (UPDATE 10 P1).
    expect(
      [...rows].map((r) =>
        r
          .querySelector('jf-effect-line')
          ?.shadowRoot?.querySelector('[data-testid="effect-label"]')
          ?.textContent?.trim(),
      ),
    ).toEqual(['Navigate to #a', 'Show message: hi']);
    // 543-fwd #4 — navigate/toast have no prior state, so the before→after diff line
    // (which would merely restate the label) is suppressed; only the label shows.
    expect([...rows].every((r) => r.querySelector('.step-diff') === null)).toBe(true);
    // No backend warning for a pure-FE macro.
    expect(host.shadowRoot?.querySelector('[data-testid="macro-dry-run-warning"]')).toBeNull();
  });

  it('543-fwd #12 — symmetric effects render a before→after diff', async () => {
    defineMacro({
      id: 'panes',
      label: 'Panes',
      effects: [{ kind: 'open-pane', paneId: 'inspector' }],
    });
    host.show('panes');
    await settle();
    const row = host.shadowRoot?.querySelector('[data-testid="macro-dry-run-row"]');
    expect(row?.querySelector('.diff-before')?.textContent?.trim()).toBe('closed');
    expect(row?.querySelector('.diff-after')?.textContent?.trim()).toBe('pane "inspector" open');
  });

  it('warns naming the backend operations a replay would re-run', async () => {
    defineMacro({
      id: 'backend',
      label: 'Backend',
      effects: [
        { kind: 'toast', message: 'ok' },
        { kind: 'invoke-operation', operationId: 'core.file-operations' },
      ],
    });
    host.show('backend');
    await settle();
    const warning = host.shadowRoot?.querySelector('[data-testid="macro-dry-run-warning"]');
    const text = (warning?.textContent ?? '').replace(/\s+/g, ' ').trim();
    expect(text).toContain('core.file-operations');
    expect(text).toContain('1 backend operation');
  });

  it('Run dispatches the macro WITH backend replay allowed, then closes', async () => {
    const invokes: string[] = [];
    document.addEventListener('jf-invoke-operation', (e) =>
      invokes.push((e as CustomEvent).detail.operationId),
    );
    defineMacro({
      id: 'run-it',
      label: 'Run it',
      effects: [{ kind: 'invoke-operation', operationId: 'core.search-index' }],
    });
    host.show('run-it');
    await settle();
    await activateJfButton(host.shadowRoot?.querySelector('[data-testid="macro-dry-run-run"]'));
    await settle();
    await Promise.resolve();
    expect(invokes).toEqual(['core.search-index']);
    expect(host.open).toBe(false);
  });

  it('543-fwd residue #5 — restores focus to the pre-open element on close', async () => {
    // Assert the wiring (pre-open element's focus() called on close) rather than
    // reading the happy-dom-flaky document.activeElement.
    const sentinel = document.createElement('button');
    document.body.appendChild(sentinel);
    sentinel.focus(); // active when the dialog opens → captured
    const focusSpy = vi.spyOn(sentinel, 'focus');
    defineMacro({ id: 'focus-it', label: 'Focus it', effects: [{ kind: 'toast', message: 'x' }] });
    host.show('focus-it');
    await settle();
    expect(focusSpy).not.toHaveBeenCalled(); // not restored while open
    await activateJfButton(host.shadowRoot?.querySelector('[data-testid="macro-dry-run-cancel"]'));
    await settle();
    expect(host.open).toBe(false);
    expect(focusSpy).toHaveBeenCalled(); // restored on close
    sentinel.remove();
  });

  it('Cancel closes without dispatching anything', async () => {
    const invokes = vi.fn();
    document.addEventListener('jf-invoke-operation', invokes as EventListener);
    defineMacro({
      id: 'cancel-it',
      label: 'Cancel it',
      effects: [{ kind: 'invoke-operation', operationId: 'core.file-operations' }],
    });
    host.show('cancel-it');
    await settle();
    await activateJfButton(host.shadowRoot?.querySelector('[data-testid="macro-dry-run-cancel"]'));
    await settle();
    expect(host.open).toBe(false);
    expect(invokes).not.toHaveBeenCalled();
    document.removeEventListener('jf-invoke-operation', invokes as EventListener);
  });
});
