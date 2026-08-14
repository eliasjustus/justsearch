// @vitest-environment happy-dom

/**
 * The session row's ACTION SET (tempdoc 831) — the affordance half of the status→action swap, whose
 * CSS mechanism is pinned in `sv3-tokens.test.ts` and whose store semantics are decided without a
 * DOM in `sv3-sessions.test.ts`.
 *
 * The row is driven directly here: what is under test is the row's own contract with the panel
 * above it — which controls exist in which state, what each of them raises, and what none of them
 * is allowed to do (claim the row it is sitting on). happy-dom runs no cascade, so nothing here
 * claims anything about VISIBILITY; the hover and focus reveal is measured in the browser, because
 * that is the one place the F3 defect (a CSS-text-green, live-dead selector) could be caught.
 */
import { describe, it, expect, afterEach } from 'vitest';
import {
  Sv3SessionRow,
  SV3_SESSION_PIN_TOGGLE,
  SV3_SESSION_REMOVE_REQUEST,
  SV3_SESSION_RENAME_START,
} from './Sv3SessionRow.js';
import type { Sv3RowStatus } from './fixtures.js';

interface RowProps {
  readonly label?: string;
  readonly status?: Sv3RowStatus;
  readonly live?: boolean;
  readonly pinned?: boolean;
}

async function mountRow(props: RowProps = {}): Promise<Sv3SessionRow> {
  const row = document.createElement('jf-sv3-session-row') as Sv3SessionRow;
  row.label = props.label ?? 'northfield lease';
  row.status = props.status ?? 'resting';
  row.live = props.live ?? false;
  row.pinned = props.pinned ?? false;
  document.body.appendChild(row);
  await row.updateComplete;
  return row;
}

const action = (row: Sv3SessionRow, name: string): HTMLButtonElement | null =>
  row.shadowRoot?.querySelector<HTMLButtonElement>(`[data-testid="sv3-session-row-${name}"]`) ??
  null;

const must = (row: Sv3SessionRow, name: string): HTMLButtonElement => {
  const found = action(row, name);
  if (found === null) throw new Error(`no ${name} action in the row`);
  return found;
};

const actionTestIds = (row: Sv3SessionRow): string[] =>
  [...(row.shadowRoot?.querySelectorAll('.actions button') ?? [])].map(
    (button) => button.getAttribute('data-testid') ?? '',
  );

afterEach(() => {
  for (const child of [...document.body.children]) child.remove();
});

describe('the row offers exactly the actions its session really supports', () => {
  it('carries rename, pin and discard, in that order, on a resting conversation', async () => {
    // Every one of them is backed by an operation the session list has (`renameSession`,
    // `toggleSessionPin`, `removeSession`) — the set is not allowed to grow a control that asks the
    // window for something it cannot do. Discard sits LAST, furthest from where the pointer enters.
    const row = await mountRow();
    expect(actionTestIds(row)).toEqual([
      'sv3-session-row-rename',
      'sv3-session-row-pin',
      'sv3-session-row-remove',
    ]);
  });

  it('WITHHOLDS the discard while work is in flight, rather than showing an inert one', async () => {
    const row = await mountRow({ live: true, status: 'in-motion' });
    expect(action(row, 'remove')).toBeNull();
    // ...and the same lookup finds a control that IS there, so the null above is an absence rather
    // than a mistyped selector.
    expect(action(row, 'rename')).not.toBeNull();
    // The other two stay: a running conversation can still be renamed and parked.
    expect(actionTestIds(row)).toEqual(['sv3-session-row-rename', 'sv3-session-row-pin']);
    // Withheld, not disabled: a control that is present but refuses asks the reader to find out by
    // pressing it, and a disabled button is also unreachable by keyboard.
    expect(row.shadowRoot?.querySelector('[disabled]')).toBeNull();
    expect(row.shadowRoot?.querySelector('[aria-disabled]')).toBeNull();
  });

  it('brings the discard back the moment the work ends', async () => {
    const row = await mountRow({ live: true, status: 'in-motion' });
    row.live = false;
    row.status = 'resting';
    await row.updateComplete;
    expect(action(row, 'remove')).not.toBeNull();
  });

  it('offers its full set on the two statuses that never yield', async () => {
    // The never-yields exception is about where the actions sit, not about whether they exist: a
    // broken conversation is precisely one a reader may want to discard.
    const broken = await mountRow({ status: 'broken' });
    expect(actionTestIds(broken)).toHaveLength(3);
    // ...and a parked run is live, so it keeps its two.
    const parked = await mountRow({ status: 'act-now', live: true });
    expect(actionTestIds(parked)).toHaveLength(2);
  });
});

describe('every action raises its own intent, and none of them claims the row', () => {
  const raised = async (row: Sv3SessionRow, name: string, event: string): Promise<number> => {
    let count = 0;
    row.addEventListener(event, () => (count += 1));
    must(row, name).click();
    await row.updateComplete;
    return count;
  };

  it('rename raises the same START that F2 does — one intent, two affordances', async () => {
    const row = await mountRow();
    expect(await raised(row, 'rename', SV3_SESSION_RENAME_START)).toBe(1);
  });

  it('pin raises the toggle', async () => {
    const row = await mountRow();
    expect(await raised(row, 'pin', SV3_SESSION_PIN_TOGGLE)).toBe(1);
  });

  it('discard raises the removal request', async () => {
    const row = await mountRow();
    expect(await raised(row, 'remove', SV3_SESSION_REMOVE_REQUEST)).toBe(1);
  });

  it('stops the click before it reaches the row, so acting on a row never opens it', async () => {
    // The row is one big claim target and the actions sit ON it. A click that also bubbled would
    // navigate the reader into the conversation they were only renaming, pinning or discarding.
    const row = await mountRow();
    let claims = 0;
    row.addEventListener('click', () => (claims += 1));
    for (const name of ['rename', 'pin', 'remove']) must(row, name).click();
    await row.updateComplete;
    expect(claims).toBe(0);
    // ...while the row's own button still claims, which is what proves the listener works at all.
    row.shadowRoot?.querySelector<HTMLButtonElement>('button.row')?.click();
    expect(claims).toBe(1);
  });

  it('does not start a rename when a double-click lands on an action', async () => {
    const row = await mountRow();
    let starts = 0;
    row.addEventListener(SV3_SESSION_RENAME_START, () => (starts += 1));
    must(row, 'remove').dispatchEvent(new MouseEvent('dblclick', { bubbles: true, composed: true }));
    await row.updateComplete;
    expect(starts).toBe(0);
    // The same gesture on the row itself IS the rename trigger.
    row.shadowRoot
      ?.querySelector<HTMLButtonElement>('button.row')
      ?.dispatchEvent(new MouseEvent('dblclick', { bubbles: true, cancelable: true }));
    await row.updateComplete;
    expect(starts).toBe(1);
  });
});

describe('the action set is reachable and legible without a pointer', () => {
  it('keeps every action in the tab order and focusable', async () => {
    const row = await mountRow();
    for (const name of ['rename', 'pin', 'remove']) {
      const button = must(row, name);
      // A negative tabindex, or a `hidden` attribute, would make the set pointer-only — which is
      // the whole failure the swap's focus half exists to avoid.
      expect(button.getAttribute('tabindex')).toBeNull();
      expect(button.hasAttribute('hidden')).toBe(false);
      button.focus();
      expect(row.shadowRoot?.activeElement).toBe(button);
    }
  });

  it('names the conversation in each accessible name, and keeps the tooltip a bare verb', async () => {
    // Three rows in a sidebar otherwise announce three identical "Delete" buttons.
    const row = await mountRow({ label: 'northfield lease' });
    expect(must(row, 'rename').getAttribute('aria-label')).toBe('Rename northfield lease');
    expect(must(row, 'pin').getAttribute('aria-label')).toBe('Pin northfield lease');
    expect(must(row, 'remove').getAttribute('aria-label')).toBe('Delete northfield lease');
    expect(must(row, 'remove').getAttribute('title')).toBe('Delete');
  });

  it('still names an unnamed conversation', async () => {
    const row = await mountRow({ label: '' });
    expect(must(row, 'rename').getAttribute('aria-label')).toBe('Rename conversation');
    expect(must(row, 'remove').getAttribute('aria-label')).toBe('Delete conversation');
  });

  it('announces the pressed state of the pin, which the other two do not have', async () => {
    const row = await mountRow({ pinned: true });
    expect(must(row, 'pin').getAttribute('aria-pressed')).toBe('true');
    // Rename and discard are not toggles; claiming a pressed state for them would be a lie.
    expect(must(row, 'rename').hasAttribute('aria-pressed')).toBe(false);
    expect(must(row, 'remove').hasAttribute('aria-pressed')).toBe(false);
  });
});
