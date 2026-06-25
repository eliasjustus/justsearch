// @vitest-environment happy-dom

import { describe, expect, it, beforeEach } from 'vitest';
import './Composer.js';
import { JfComposer } from './Composer.js';

function make(props: Partial<JfComposer> = {}): JfComposer {
  const el = document.createElement('jf-composer') as JfComposer;
  Object.assign(el, props);
  document.body.appendChild(el);
  return el;
}

function ta(el: JfComposer): HTMLTextAreaElement {
  // Light DOM: composer renders textarea directly into itself.
  return el.querySelector('textarea') as HTMLTextAreaElement;
}

function btn(el: JfComposer): HTMLButtonElement {
  return el.querySelector('button') as HTMLButtonElement;
}

describe('JfComposer (tempdoc 528)', () => {
  beforeEach(() => {
    document.body.innerHTML = '';
  });

  it('renders into light DOM (no shadow root needed for queries)', async () => {
    const el = make();
    await el.updateComplete;
    expect(el.shadowRoot).toBeNull();
    expect(ta(el)).toBeTruthy();
    expect(btn(el)).toBeTruthy();
  });

  it('default submit button renders submitLabel; emits composer-submit on click', async () => {
    const el = make({ submitLabel: 'Send', value: 'hello' });
    await el.updateComplete;
    expect(btn(el).textContent?.trim()).toBe('Send');
    let fired = 0;
    el.addEventListener('composer-submit', () => fired++);
    btn(el).click();
    expect(fired).toBe(1);
  });

  it('streaming swaps button label to streamingLabel and disables submit', async () => {
    const el = make({ submitLabel: 'Send', streamingLabel: 'Sending…' });
    el.streaming = true;
    await el.updateComplete;
    expect(btn(el).textContent?.trim()).toBe('Sending…');
    expect(btn(el).disabled).toBe(true);
  });

  it('submit-disabled blocks both click and Enter from firing composer-submit', async () => {
    const el = make({ value: '' });
    el.submitDisabled = true;
    await el.updateComplete;
    let fired = 0;
    el.addEventListener('composer-submit', () => fired++);
    // Click is gated by disabled attribute (no event from a disabled button).
    btn(el).click();
    // Enter goes through fireSubmit which early-returns.
    ta(el).dispatchEvent(
      new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }),
    );
    expect(fired).toBe(0);
  });

  it('submit-mode=enter submits on Enter (not Shift+Enter)', async () => {
    const el = make({ value: 'x', submitMode: 'enter' });
    await el.updateComplete;
    let fired = 0;
    el.addEventListener('composer-submit', () => fired++);
    ta(el).dispatchEvent(
      new KeyboardEvent('keydown', { key: 'Enter', shiftKey: true, bubbles: true, cancelable: true }),
    );
    expect(fired).toBe(0);
    ta(el).dispatchEvent(
      new KeyboardEvent('keydown', { key: 'Enter', bubbles: true, cancelable: true }),
    );
    expect(fired).toBe(1);
  });

  it('submit-mode=ctrl-enter submits only on Ctrl+Enter', async () => {
    const el = make({ value: 'x', submitMode: 'ctrl-enter' });
    await el.updateComplete;
    let fired = 0;
    el.addEventListener('composer-submit', () => fired++);
    ta(el).dispatchEvent(
      new KeyboardEvent('keydown', { key: 'Enter', bubbles: true, cancelable: true }),
    );
    expect(fired).toBe(0);
    ta(el).dispatchEvent(
      new KeyboardEvent('keydown', { key: 'Enter', ctrlKey: true, bubbles: true, cancelable: true }),
    );
    expect(fired).toBe(1);
  });

  it('composer-input fires with current textarea value', async () => {
    const el = make();
    await el.updateComplete;
    let last = '';
    el.addEventListener('composer-input', (e) => {
      last = (e as CustomEvent<{ value: string }>).detail.value;
    });
    const t = ta(el);
    t.value = 'typed';
    t.dispatchEvent(new Event('input', { bubbles: true }));
    expect(last).toBe('typed');
    expect(el.value).toBe('typed');
  });

  it('?cancellable + ?streaming renders red cancel button that fires composer-cancel', async () => {
    const el = make({ submitLabel: 'Send' });
    el.cancellable = true;
    el.streaming = true;
    el.cancelLabel = 'Stop';
    await el.updateComplete;
    const b = btn(el);
    expect(b.textContent?.trim()).toBe('Stop');
    expect(b.classList.contains('cancel')).toBe(true);
    expect(b.disabled).toBe(false);
    let fired = 0;
    el.addEventListener('composer-cancel', () => fired++);
    b.click();
    expect(fired).toBe(1);
  });

  it('cancellable + streaming: textarea is disabled and cancel is button-only', async () => {
    // Real browsers don't deliver keyboard events to disabled inputs, so the
    // composer's keydown handler can't drive cancel in production. The
    // button-click path is the only cancel route. (happy-dom would let us
    // dispatch a synthetic keydown to a disabled element — that test would
    // pass while production behavior would differ. See tempdoc 528 §A.D1.)
    const el = make({ value: 'whatever' });
    el.cancellable = true;
    el.streaming = true;
    el.cancelLabel = 'Stop';
    await el.updateComplete;
    expect(ta(el).disabled).toBe(true);
    let cancels = 0;
    el.addEventListener('composer-cancel', () => cancels++);
    btn(el).click();
    expect(cancels).toBe(1);
  });

  it('non-cancellable streaming does NOT show cancel button (label-swap only)', async () => {
    const el = make({ submitLabel: 'Send', streamingLabel: 'Sending…' });
    el.cancellable = false;
    el.streaming = true;
    await el.updateComplete;
    expect(btn(el).classList.contains('cancel')).toBe(false);
    expect(btn(el).textContent?.trim()).toBe('Sending…');
  });

  it('mono prop applies the .mono class to the textarea', async () => {
    const el = make();
    el.mono = true;
    await el.updateComplete;
    expect(ta(el).classList.contains('mono')).toBe(true);
  });

  it('rows prop sets the textarea rows attribute', async () => {
    const el = make();
    el.rows = 3;
    await el.updateComplete;
    expect(ta(el).getAttribute('rows')).toBe('3');
  });

  it('submit-title omits title attr when empty; sets it when non-empty', async () => {
    const el = make();
    await el.updateComplete;
    expect(btn(el).hasAttribute('title')).toBe(false);
    el.submitTitle = 'AI offline';
    await el.updateComplete;
    expect(btn(el).getAttribute('title')).toBe('AI offline');
  });

  it('cancel-title omits title attr when empty; sets it on the cancel button', async () => {
    const el = make();
    el.cancellable = true;
    el.streaming = true;
    await el.updateComplete;
    expect(btn(el).hasAttribute('title')).toBe(false);
    el.cancelTitle = 'Click to abort the streaming response';
    await el.updateComplete;
    expect(btn(el).getAttribute('title')).toBe('Click to abort the streaming response');
  });
});
