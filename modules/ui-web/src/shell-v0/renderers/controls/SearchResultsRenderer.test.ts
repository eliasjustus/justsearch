// @vitest-environment happy-dom

/**
 * 569 Fix A — the §9 results-list + agent-source renderers render their content kinds at bespoke
 * quality through the engine.
 */
import { describe, it, expect, beforeEach } from 'vitest';
import './SearchResultsRenderer.js';
import './SourceChipsRenderer.js';
import type { SearchResultsRenderer } from './SearchResultsRenderer.js';
import type { SourceChipsRenderer } from './SourceChipsRenderer.js';
import { getXUiRendererTag } from './XUiRendererControl.js';

beforeEach(() => {
  document.body.innerHTML = '';
});

describe('<jf-search-results>', () => {
  it('registers the search-results hint and renders one row per hit', async () => {
    expect(getXUiRendererTag('search-results')).toBe('jf-search-results');
    const el = document.createElement('jf-search-results') as SearchResultsRenderer;
    document.body.appendChild(el);
    el.data = [
      { title: 'Design doc', path: '/docs/569.md', snippet: 'the inversion' },
      { title: 'Spec', path: '/docs/spec.md' },
    ];
    el.visible = true;
    el.enabled = true;
    el.uischema = { type: 'Control' };
    el.onChange = () => {};
    await el.updateComplete;
    const rows = el.shadowRoot?.querySelectorAll('.row');
    expect(rows?.length).toBe(2);
    expect(el.shadowRoot?.querySelector('.title')?.textContent).toBe('Design doc');
    expect(el.shadowRoot?.querySelector('.path')?.textContent).toContain('/docs/569.md');
  });

  it('shows an empty state for no hits', async () => {
    const el = document.createElement('jf-search-results') as SearchResultsRenderer;
    document.body.appendChild(el);
    el.data = [];
    el.visible = true;
    el.uischema = { type: 'Control' };
    el.onChange = () => {};
    await el.updateComplete;
    expect(el.shadowRoot?.querySelector('.empty')).not.toBeNull();
  });
});

describe('<jf-source-chips>', () => {
  it('registers the source-chips hint and renders numbered chips', async () => {
    expect(getXUiRendererTag('source-chips')).toBe('jf-source-chips');
    const el = document.createElement('jf-source-chips') as SourceChipsRenderer;
    document.body.appendChild(el);
    el.data = [{ title: 'tempdoc 569', url: '/docs/569' }];
    el.visible = true;
    el.uischema = { type: 'Control' };
    el.onChange = () => {};
    await el.updateComplete;
    const chips = el.shadowRoot?.querySelectorAll('.chip');
    expect(chips?.length).toBe(1);
    expect(chips?.[0]?.textContent).toContain('tempdoc 569');
  });
});
