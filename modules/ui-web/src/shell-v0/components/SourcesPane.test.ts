// @vitest-environment happy-dom
//
// Tempdoc 565 §3.A — the sources pane + its open-store: the answer's grounding sources render as
// clickable local-passage cards that dispatch citation-select (the deep-link).

import { afterEach, describe, expect, it } from 'vitest';
import './SourcesPane.js';
import type { SourcesPane } from './SourcesPane.js';
import {
  getAgentSessionController,
  __resetAgentSessionStore,
} from '../state/agentSessionStore.js';
import { isSourcesOpen, toggleSources, __resetSourcesDrawer } from '../state/sourcesDrawer.js';
import {
  getSelectedSource,
  setSelectedSource,
  sourceKey,
  __resetSelectedSource,
} from '../state/selectedSource.js';
import type { CitationSelectDetail } from './chat/citationTypes.js';
import {
  setExcludedSources,
  sourceExcludeKey,
  __resetExcludedSources,
} from '../state/excludedSources.js';

afterEach(() => {
  __resetAgentSessionStore();
  __resetSourcesDrawer();
  __resetSelectedSource();
  __resetExcludedSources();
});

function seedSource(): void {
  const ctrl = getAgentSessionController('http://x');
  (ctrl as unknown as { answerSources: unknown[] }).answerSources = [
    {
      parentDocId: 'C:/docs/taxes.md',
      chunkIndex: 2,
      path: 'C:/docs/taxes.md',
      title: 'taxes.md',
      excerpt: 'the reliability budget report',
      startLine: 42,
      endLine: 48,
      headingText: 'Budget',
    },
  ];
}

async function settle(el: Element): Promise<void> {
  await (el as unknown as { updateComplete: Promise<unknown> }).updateComplete;
}

describe('sourcesDrawer store', () => {
  it('toggles open state', () => {
    expect(isSourcesOpen()).toBe(false);
    toggleSources();
    expect(isSourcesOpen()).toBe(true);
    toggleSources();
    expect(isSourcesOpen()).toBe(false);
  });
});

describe('SourcesPane (565 §3.A)', () => {
  it('renders the answer sources and dispatches citation-select with the local-passage detail', async () => {
    const ctrl = getAgentSessionController('http://x');
    (ctrl as unknown as { answerSources: unknown[] }).answerSources = [
      {
        parentDocId: 'C:/docs/taxes.md',
        chunkIndex: 2,
        path: 'C:/docs/taxes.md',
        title: 'taxes.md',
        excerpt: 'the reliability budget report',
        startLine: 42,
        endLine: 48,
        headingText: 'Budget',
      },
    ];

    const el = document.createElement('jf-sources-pane') as SourcesPane;
    el.apiBase = 'http://x';
    el.open = true;
    document.body.appendChild(el);
    await settle(el);

    const text = (el.shadowRoot?.textContent ?? '').replace(/\s+/g, ' ');
    expect(text).toContain('taxes.md');
    expect(text).toContain('line 42');

    let detail: CitationSelectDetail | null = null;
    el.addEventListener('citation-select', (e) => {
      detail = (e as CustomEvent<CitationSelectDetail>).detail;
    });
    const card = el.shadowRoot?.querySelector('[role="button"]') as HTMLElement | null;
    expect(card).not.toBeNull();
    card?.click();

    expect(detail).not.toBeNull();
    expect(detail!.parentDocId).toBe('C:/docs/taxes.md');
    expect(detail!.startLine).toBe(42);
    el.remove();
  });

  it('610 §J.3: renders the hide/restore control on each source row + dims hidden sources', async () => {
    seedSource(); // parentDocId C:/docs/taxes.md, chunkIndex 2
    setExcludedSources([sourceExcludeKey('C:/docs/taxes.md', 2)]);
    const el = document.createElement('jf-sources-pane') as SourcesPane;
    el.apiBase = 'http://x';
    el.open = true;
    document.body.appendChild(el);
    await settle(el);

    const hideBtn = el.shadowRoot?.querySelector('.source-hide');
    expect(hideBtn).not.toBeNull();
    // Hidden state → restore glyph + the row dimmed (shared store, consistent with the inline chips).
    expect(hideBtn?.textContent?.trim()).toBe('↺');
    expect(el.shadowRoot?.querySelector('.source.hidden-source')).not.toBeNull();
    el.remove();
  });

  it('603 D-4: a DOCUMENT-LEVEL source (startLine === -1 sentinel) shows NO line locator and still deep-links to the file', async () => {
    const ctrl = getAgentSessionController('http://x');
    (ctrl as unknown as { answerSources: unknown[] }).answerSources = [
      {
        parentDocId: 'C:/docs/overview.md',
        chunkIndex: -1, // doc-level: no chunk identity
        path: 'C:/docs/overview.md',
        title: 'overview.md',
        excerpt: 'a whole-document provenance source',
        startLine: -1, // sentinel: no precise line
        endLine: -1,
        headingText: '',
      },
    ];

    const el = document.createElement('jf-sources-pane') as SourcesPane;
    el.apiBase = 'http://x';
    el.open = true;
    document.body.appendChild(el);
    await settle(el);

    const text = (el.shadowRoot?.textContent ?? '').replace(/\s+/g, ' ');
    expect(text).toContain('overview.md');
    // The provenance source must NOT render a fabricated "line -1"/"line 0" locator.
    expect(text).not.toContain('line -1');
    expect(text).not.toContain('line 0');
    expect(text).not.toMatch(/line\s/i);
    expect(el.shadowRoot?.querySelector('.source-loc')).toBeNull();

    // It still deep-links to the file (opened at top — startLine -1 means no precise highlight).
    let detail: CitationSelectDetail | null = null;
    el.addEventListener('citation-select', (e) => {
      detail = (e as CustomEvent<CitationSelectDetail>).detail;
    });
    (el.shadowRoot?.querySelector('[role="button"]') as HTMLElement | null)?.click();
    expect(detail).not.toBeNull();
    expect(detail!.parentDocId).toBe('C:/docs/overview.md');
    expect(detail!.startLine).toBe(-1);
    el.remove();
  });
});

describe('SourcesPane docked rail + cross-highlight (565 §12.3.E)', () => {
  it('docked renders without the open store (always-visible persistent rail) and no Close button', async () => {
    seedSource();
    const el = document.createElement('jf-sources-pane') as SourcesPane;
    el.apiBase = 'http://x';
    el.docked = true; // NOT open — docked is independent of the toggle drawer store.
    document.body.appendChild(el);
    await settle(el);

    expect(isSourcesOpen()).toBe(false);
    const text = (el.shadowRoot?.textContent ?? '').replace(/\s+/g, ' ');
    expect(text).toContain('taxes.md'); // rendered despite the drawer being closed
    expect(el.shadowRoot?.querySelector('.close')).toBeNull(); // no Close in the docked rail
    el.remove();
  });

  it('a card click sets the shared selection; the matching card highlights', async () => {
    seedSource();
    const el = document.createElement('jf-sources-pane') as SourcesPane;
    el.apiBase = 'http://x';
    el.docked = true;
    document.body.appendChild(el);
    await settle(el);

    const card = el.shadowRoot?.querySelector('.source') as HTMLElement | null;
    expect(card?.classList.contains('selected')).toBe(false);
    card?.click();
    // The click set the cross-surface selection to this source's local-passage key.
    expect(getSelectedSource()).toBe(sourceKey('C:/docs/taxes.md', 42));
    await settle(el);
    const after = el.shadowRoot?.querySelector('.source') as HTMLElement | null;
    expect(after?.classList.contains('selected')).toBe(true);
    expect(after?.getAttribute('aria-current')).toBe('true');
    el.remove();
  });

  it('reflects an EXTERNAL selection (e.g. an inline [n] mark) by highlighting the matching card', async () => {
    seedSource();
    const el = document.createElement('jf-sources-pane') as SourcesPane;
    el.apiBase = 'http://x';
    el.docked = true;
    document.body.appendChild(el);
    await settle(el);

    // Simulate the answer's inline mark focusing this source.
    setSelectedSource(sourceKey('C:/docs/taxes.md', 42));
    await settle(el);
    const card = el.shadowRoot?.querySelector('.source') as HTMLElement | null;
    expect(card?.classList.contains('selected')).toBe(true);
    el.remove();
  });
});
