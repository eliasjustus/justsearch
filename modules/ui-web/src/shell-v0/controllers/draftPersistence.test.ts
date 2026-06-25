// @vitest-environment happy-dom
import { describe, it, expect, beforeEach } from 'vitest';
import { LitElement } from 'lit';
import {
  DraftPersistence,
  __draftStorageKey,
  __resetDraftProvidersForTest,
} from './draftPersistence.js';

const KEY = 'test.composer';

class DraftProbe extends LitElement {
  value = '';
  readonly persist = new DraftPersistence(
    this,
    KEY,
    () => this.value,
    (v) => {
      this.value = v;
    },
  );
  override createRenderRoot(): HTMLElement {
    return this;
  }
}
if (!customElements.get('jf-draft-probe')) customElements.define('jf-draft-probe', DraftProbe);

function mount(): DraftProbe {
  const el = document.createElement('jf-draft-probe') as DraftProbe;
  document.body.appendChild(el);
  return el;
}

describe('DraftPersistence (tempdoc 609 §R T2.1)', () => {
  beforeEach(() => {
    localStorage.removeItem(__draftStorageKey(KEY));
    __resetDraftProvidersForTest();
    document.body.innerHTML = '';
  });

  it('flushes a draft to localStorage on disconnect', () => {
    const el = mount();
    el.value = 'unsent message';
    el.remove(); // disconnect → flush
    expect(localStorage.getItem(__draftStorageKey(KEY))).toBe('unsent message');
  });

  it('rehydrates a fresh instance from storage on first connect (the reload path)', () => {
    localStorage.setItem(__draftStorageKey(KEY), 'restored draft');
    const el = mount(); // a brand-new instance = first connect → restore
    expect(el.value).toBe('restored draft');
  });

  it('does NOT clobber the live in-memory draft when a RETAINED instance reconnects', () => {
    localStorage.setItem(__draftStorageKey(KEY), 'stale storage');
    const el = mount(); // first connect → restores 'stale storage'
    expect(el.value).toBe('stale storage');
    el.value = 'newer in-memory edit';
    el.remove(); // flush 'newer in-memory edit'
    document.body.appendChild(el); // reconnect SAME instance (retention) → must keep in-memory, not re-read
    expect(el.value).toBe('newer in-memory edit');
  });

  it('removes the storage entry when the draft is emptied', () => {
    localStorage.setItem(__draftStorageKey(KEY), 'old');
    const el = mount();
    el.value = '';
    el.remove(); // flush empty → delete
    expect(localStorage.getItem(__draftStorageKey(KEY))).toBeNull();
  });
});
