// @vitest-environment happy-dom

/**
 * Render tests for ActionButton. Covers:
 *  - LOW risk: click immediately fires `action-invoke`.
 *  - MEDIUM risk: click → confirm prompt → "Yes" fires; "Cancel" doesn't.
 *  - HIGH risk: click → typed-confirm prompt → typing the operation
 *    id enables the confirm button; mismatched typing keeps it disabled.
 *  - disabled / pending suppress invoke.
 */

import { describe, expect, it } from 'vitest';
import './ActionButton.js';
import type { ActionButton, ActionInvokeEventDetail } from './ActionButton.js';

async function mountBtn(
  props: Partial<ActionButton>,
): Promise<ActionButton> {
  const el = document.createElement('jf-action-button') as ActionButton;
  for (const [k, v] of Object.entries(props)) {
    (el as unknown as Record<string, unknown>)[k] = v;
  }
  document.body.appendChild(el);
  await el.updateComplete;
  return el;
}

function captureInvoke(el: ActionButton): ActionInvokeEventDetail[] {
  const events: ActionInvokeEventDetail[] = [];
  el.addEventListener('action-invoke', ((e: CustomEvent) => {
    events.push(e.detail as ActionInvokeEventDetail);
  }) as EventListener);
  return events;
}

describe('ActionButton — LOW risk', () => {
  it('fires action-invoke immediately on click', async () => {
    const el = await mountBtn({
      operationId: 'cache.refresh',
      label: 'Refresh',
      risk: 'LOW',
    });
    const events = captureInvoke(el);
    const btn = el.shadowRoot?.querySelector('button.invoke') as HTMLButtonElement;
    btn.click();
    expect(events).toEqual([{ operationId: 'cache.refresh', risk: 'LOW' }]);
    el.remove();
  });
});

describe('ActionButton — MEDIUM risk', () => {
  it('shows a confirm prompt and only fires on Yes', async () => {
    const el = await mountBtn({
      operationId: 'index.flush',
      label: 'Flush',
      risk: 'MEDIUM',
    });
    const events = captureInvoke(el);
    const btn = el.shadowRoot?.querySelector('button.invoke') as HTMLButtonElement;
    btn.click();
    await el.updateComplete;
    expect(events).toEqual([]);
    expect(el.shadowRoot?.textContent).toContain('Are you sure?');

    const buttons = el.shadowRoot?.querySelectorAll(
      'button.invoke',
    ) as NodeListOf<HTMLButtonElement>;
    // [original-button (still rendered for visual continuity), Yes, Cancel]
    const yes = Array.from(buttons).find(
      (b) => b.textContent?.trim() === 'Yes',
    );
    yes?.click();
    expect(events).toEqual([{ operationId: 'index.flush', risk: 'MEDIUM' }]);
    el.remove();
  });

  it('Cancel returns to idle without firing', async () => {
    const el = await mountBtn({
      operationId: 'index.flush',
      label: 'Flush',
      risk: 'MEDIUM',
    });
    const events = captureInvoke(el);
    const btn = el.shadowRoot?.querySelector('button.invoke') as HTMLButtonElement;
    btn.click();
    await el.updateComplete;
    const cancel = Array.from(
      el.shadowRoot?.querySelectorAll('button.invoke') ?? [],
    ).find((b) => b.textContent?.trim() === 'Cancel');
    (cancel as HTMLButtonElement | undefined)?.click();
    await el.updateComplete;
    expect(events).toEqual([]);
    expect(el.shadowRoot?.textContent).not.toContain('Are you sure?');
    el.remove();
  });
});

describe('ActionButton — HIGH risk', () => {
  it('requires typing the operationId to enable confirm', async () => {
    const el = await mountBtn({
      operationId: 'index.reset',
      label: 'Reset',
      risk: 'HIGH',
    });
    const events = captureInvoke(el);
    const btn = el.shadowRoot?.querySelector('button.invoke') as HTMLButtonElement;
    btn.click();
    await el.updateComplete;

    const typeInput = el.shadowRoot?.querySelector(
      'input.typed-confirm',
    ) as HTMLInputElement;
    expect(typeInput).not.toBeNull();

    // Confirm button is the one with text "Confirm"
    const findConfirm = (): HTMLButtonElement | undefined =>
      Array.from(
        el.shadowRoot?.querySelectorAll('button.invoke') ?? [],
      ).find((b) => b.textContent?.trim() === 'Confirm') as
        | HTMLButtonElement
        | undefined;

    expect(findConfirm()?.disabled).toBe(true);

    // Type the wrong text
    typeInput.value = 'wrong';
    typeInput.dispatchEvent(new Event('input'));
    await el.updateComplete;
    expect(findConfirm()?.disabled).toBe(true);

    // Type the right text
    typeInput.value = 'index.reset';
    typeInput.dispatchEvent(new Event('input'));
    await el.updateComplete;
    expect(findConfirm()?.disabled).toBe(false);

    findConfirm()?.click();
    expect(events).toEqual([{ operationId: 'index.reset', risk: 'HIGH' }]);
    el.remove();
  });

  it('respects requireConfirmText override over operationId', async () => {
    const el = await mountBtn({
      operationId: 'op.x',
      label: 'X',
      risk: 'HIGH',
      requireConfirmText: 'I CONFIRM',
    });
    const events = captureInvoke(el);
    (
      el.shadowRoot?.querySelector('button.invoke') as HTMLButtonElement
    ).click();
    await el.updateComplete;
    const typeInput = el.shadowRoot?.querySelector(
      'input.typed-confirm',
    ) as HTMLInputElement;
    typeInput.value = 'I CONFIRM';
    typeInput.dispatchEvent(new Event('input'));
    await el.updateComplete;
    const confirm = Array.from(
      el.shadowRoot?.querySelectorAll('button.invoke') ?? [],
    ).find((b) => b.textContent?.trim() === 'Confirm') as
      | HTMLButtonElement
      | undefined;
    confirm?.click();
    expect(events).toHaveLength(1);
    el.remove();
  });
});

describe('ActionButton — disabled / pending', () => {
  it('does not fire when disabled', async () => {
    const el = await mountBtn({
      operationId: 'op.x',
      label: 'X',
      risk: 'LOW',
      disabled: true,
    });
    const events = captureInvoke(el);
    const btn = el.shadowRoot?.querySelector('button.invoke') as HTMLButtonElement;
    btn.click();
    expect(events).toEqual([]);
    el.remove();
  });

  it('does not fire when pending', async () => {
    const el = await mountBtn({
      operationId: 'op.x',
      label: 'X',
      risk: 'LOW',
      pending: true,
    });
    const events = captureInvoke(el);
    const btn = el.shadowRoot?.querySelector('button.invoke') as HTMLButtonElement;
    btn.click();
    expect(events).toEqual([]);
    el.remove();
  });
});
