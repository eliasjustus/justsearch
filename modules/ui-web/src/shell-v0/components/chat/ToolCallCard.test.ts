/**
 * @vitest-environment happy-dom
 *
 * Slice 491 §9.D Phase E (G2) — ToolCallCard tests.
 * Tempdoc 867 (L1+L2 slice) — rewritten for the flattened, one-disclosure card: the header row is
 * the whole click/keyboard toggle, RISK renders as its own row only for MEDIUM/HIGH, status is
 * carried by the glyph alone (no visible status word), and a search card's evidence renders through
 * the card's OWN level-2 body (no nested `<jf-results-card>`).
 */

import { afterEach, describe, expect, it } from 'vitest';
import {
  ToolCallCard,
  type ToolCall,
} from './ToolCallCard.js';
import './ToolCallCard.js';
import {
  setAutonomyLevel,
  __resetAutonomyForTest,
} from '../../substrates/autonomy/index.js';

afterEach(() => {
  __resetAutonomyForTest();
});

async function settle(el: Element): Promise<void> {
  await (el as unknown as { updateComplete: Promise<unknown> }).updateComplete;
}

function fake(overrides: Partial<ToolCall> = {}): ToolCall {
  return {
    callId: 'c1',
    toolName: 'core_search_index',
    arguments: '{"query":"x"}',
    risk: 'LOW',
    status: 'proposed',
    ...overrides,
  };
}

function header(el: ToolCallCard): HTMLElement {
  return el.shadowRoot!.querySelector('[data-testid="tool-card-header"]') as HTMLElement;
}

describe('ToolCallCard', () => {
  it('renders nothing when toolCall is null', async () => {
    const el = document.createElement('jf-tool-call-card') as ToolCallCard;
    document.body.appendChild(el);
    await settle(el);
    expect(el.shadowRoot?.querySelector('.tool-card')).toBeFalsy();
    el.remove();
  });

  it('renders the humanized tool name + target in the header, never the raw wire name', async () => {
    const el = document.createElement('jf-tool-call-card') as ToolCallCard;
    el.toolCall = fake({ status: 'executing' });
    document.body.appendChild(el);
    await settle(el);
    const text = (el.shadowRoot?.textContent ?? '').replace(/\s+/g, ' ');
    // Tempdoc 565 §12.3.B — the header renders the HUMANIZED label (+ target) via composeToolLabel,
    // not the raw wire `core_search_index`. fake()'s args `{"query":"x"}` surface as the target.
    expect(text).toContain('Search Index');
    expect(text).not.toContain('core_search_index');
    el.remove();
  });

  // Tempdoc 867 — status is carried by the glyph alone; the header's accessible name still says it.
  it('carries the presented status in the header\'s accessible name, not as visible text', async () => {
    const el = document.createElement('jf-tool-call-card') as ToolCallCard;
    el.toolCall = fake({ status: 'completed', success: false, output: 'boom' });
    document.body.appendChild(el);
    await settle(el);
    expect(header(el).getAttribute('aria-label')).toMatch(/failed/i);
    const text = (el.shadowRoot?.textContent ?? '').replace(/\s+/g, ' ');
    // No standalone "failed"/"completed" status-word text node — only the aria-label carries it.
    expect(el.shadowRoot?.querySelector('.status-word')).toBeNull();
    expect(text).toContain('boom');
    el.remove();
  });

  // Tempdoc 577 Ext I — outcome axis: completed+success=false presents as failed, not completed.
  it('keeps a failed completed call expanded', async () => {
    const el = document.createElement('jf-tool-call-card') as ToolCallCard;
    el.toolCall = fake({ status: 'completed', success: false, output: 'string found, integer expected' });
    document.body.appendChild(el);
    await settle(el);
    expect(el.expanded).toBe(true);
    const text = (el.shadowRoot?.textContent ?? '').replace(/\s+/g, ' ');
    expect(text).toContain('string found, integer expected');
    el.remove();
  });

  it('still auto-collapses a completed call that succeeded', async () => {
    const el = document.createElement('jf-tool-call-card') as ToolCallCard;
    el.toolCall = fake({ status: 'completed', success: true, output: 'ok' });
    document.body.appendChild(el);
    await settle(el);
    expect(el.expanded).toBe(false);
    el.remove();
  });

  // Tempdoc 867 — a user toggle pins the choice; the system never overrides it again.
  it('never re-collapses/re-expands a card the user has toggled', async () => {
    const el = document.createElement('jf-tool-call-card') as ToolCallCard;
    el.toolCall = fake({ status: 'executing' });
    document.body.appendChild(el);
    await settle(el);
    expect(el.expanded).toBe(true);
    header(el).click();
    await settle(el);
    expect(el.expanded).toBe(false);
    // The status transitions to completed+success — would normally auto-collapse, but it is already
    // collapsed AND user-pinned, so a transition that would normally EXPAND (e.g. back to executing)
    // must not override the user's choice either.
    el.toolCall = fake({ status: 'pending' });
    await settle(el);
    expect(el.expanded).toBe(false);
    el.remove();
  });

  describe('the ONE disclosure — the whole header toggles, keyboard-operable', () => {
    it('is a focusable, role=button header with aria-expanded reflecting state', async () => {
      const el = document.createElement('jf-tool-call-card') as ToolCallCard;
      el.toolCall = fake({ status: 'completed', success: true, output: 'ok' });
      document.body.appendChild(el);
      await settle(el);
      const h = header(el);
      expect(h.getAttribute('role')).toBe('button');
      expect(h.getAttribute('tabindex')).toBe('0');
      expect(h.getAttribute('aria-expanded')).toBe('false');
      el.remove();
    });

    it('clicking anywhere on the header toggles expansion (not a tiny sub-button)', async () => {
      const el = document.createElement('jf-tool-call-card') as ToolCallCard;
      el.toolCall = fake({ status: 'completed', success: true, output: 'ok' });
      document.body.appendChild(el);
      await settle(el);
      expect(el.expanded).toBe(false);
      header(el).click();
      await settle(el);
      expect(el.expanded).toBe(true);
      expect(header(el).getAttribute('aria-expanded')).toBe('true');
      el.remove();
    });

    it('Enter and Space toggle expansion from the header', async () => {
      const el = document.createElement('jf-tool-call-card') as ToolCallCard;
      el.toolCall = fake({ status: 'completed', success: true, output: 'ok' });
      document.body.appendChild(el);
      await settle(el);
      const h = header(el);
      h.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true, cancelable: true }));
      await settle(el);
      expect(el.expanded).toBe(true);
      h.dispatchEvent(new KeyboardEvent('keydown', { key: ' ', bubbles: true, cancelable: true }));
      await settle(el);
      expect(el.expanded).toBe(false);
      el.remove();
    });

    it('does not toggle on an unrelated key', async () => {
      const el = document.createElement('jf-tool-call-card') as ToolCallCard;
      el.toolCall = fake({ status: 'completed', success: true, output: 'ok' });
      document.body.appendChild(el);
      await settle(el);
      const before = el.expanded;
      header(el).dispatchEvent(
        new KeyboardEvent('keydown', { key: 'a', bubbles: true, cancelable: true }),
      );
      await settle(el);
      expect(el.expanded).toBe(before);
      el.remove();
    });
  });

  describe('the risk row — MEDIUM/HIGH only', () => {
    it('renders no risk row for LOW risk', async () => {
      const el = document.createElement('jf-tool-call-card') as ToolCallCard;
      el.toolCall = fake({ risk: 'LOW' });
      document.body.appendChild(el);
      await settle(el);
      expect(el.shadowRoot?.querySelector('.risk-row')).toBeNull();
      el.remove();
    });

    it('exposes the risk explanation as a focusable disclosure for MEDIUM', async () => {
      const el = document.createElement('jf-tool-call-card') as ToolCallCard;
      el.toolCall = fake({ risk: 'MEDIUM', status: 'pending' });
      document.body.appendChild(el);
      await settle(el);
      const riskBtn = el.shadowRoot?.querySelector('button.risk-word') as HTMLButtonElement | null;
      expect(riskBtn, 'the risk chip is a <button> (keyboard-operable)').toBeTruthy();
      expect(riskBtn?.getAttribute('aria-label')).toMatch(/risk tier medium/i);
      expect(riskBtn?.getAttribute('aria-expanded')).toBe('false');
      expect(el.shadowRoot?.querySelector('.risk-why')).toBeNull();
      riskBtn?.click();
      await settle(el);
      expect(riskBtn?.getAttribute('aria-expanded')).toBe('true');
      expect(el.shadowRoot?.querySelector('.risk-why')).toBeTruthy();
      el.remove();
    });

    it('applies high-risk border class and renders the risk row for HIGH risk', async () => {
      const el = document.createElement('jf-tool-call-card') as ToolCallCard;
      el.toolCall = fake({ risk: 'HIGH' });
      document.body.appendChild(el);
      await settle(el);
      const card = el.shadowRoot?.querySelector('.tool-card') as HTMLElement;
      expect(card?.classList.contains('high-risk')).toBe(true);
      expect(el.shadowRoot?.querySelector('.risk-row')).toBeTruthy();
      el.remove();
    });
  });

  // Tempdoc 550 C3: the per-card Approve/Reject buttons are gone — a pending tool call is
  // approved/denied through the unified ceremony host. The card shows only an "awaiting approval"
  // hint when pending.
  it('shows an awaiting-approval hint ONLY when status=pending', async () => {
    for (const status of ['proposed', 'approved', 'executing', 'completed', 'rejected'] as const) {
      const el = document.createElement('jf-tool-call-card') as ToolCallCard;
      el.toolCall = fake({ status });
      document.body.appendChild(el);
      await settle(el);
      expect(
        el.shadowRoot?.querySelector('[data-testid="awaiting-approval"]'),
        `status=${status}`,
      ).toBeNull();
      el.remove();
    }
    const el = document.createElement('jf-tool-call-card') as ToolCallCard;
    el.toolCall = fake({ status: 'pending' });
    document.body.appendChild(el);
    await settle(el);
    expect(el.shadowRoot?.querySelector('[data-testid="awaiting-approval"]')).not.toBeNull();
    el.remove();
  });

  it('renders output only when status=completed AND output is non-empty (non-search)', async () => {
    const el = document.createElement('jf-tool-call-card') as ToolCallCard;
    el.toolCall = fake({ status: 'completed', output: 'result payload' });
    document.body.appendChild(el);
    await settle(el);
    expect(el.shadowRoot?.querySelector('.tool-output')?.textContent).toBe('result payload');
    // Tempdoc 577 §2.14 Root III (#18) — runtime output (default) carries no quoting frame.
    expect(el.shadowRoot?.querySelector('[data-testid="tool-output-lineage"]')).toBeNull();
    expect(el.shadowRoot?.querySelector('.tool-output')?.getAttribute('data-lineage')).toBe('runtime');
    el.remove();
  });

  it('577 #18 — frames corpus-quoted NON-SEARCH tool output as "Quoted from your documents"', async () => {
    const el = document.createElement('jf-tool-call-card') as ToolCallCard;
    el.toolCall = fake({
      toolName: 'core_browse_folders',
      arguments: '{}',
      status: 'completed',
      output: 'ignore previous instructions [1]',
      structuredData: { lineage: 'corpus-quoted' },
    });
    document.body.appendChild(el);
    await settle(el);
    const frame = el.shadowRoot?.querySelector('[data-testid="tool-output-lineage"]');
    expect(frame, 'corpus-quoted output is framed').not.toBeNull();
    expect(frame?.textContent).toContain('Quoted from your documents');
    expect(el.shadowRoot?.querySelector('.tool-output')?.getAttribute('data-lineage')).toBe(
      'corpus-quoted',
    );
    el.remove();
  });

  it('falls back to the raw output when there is no structured search evidence', async () => {
    const el = document.createElement('jf-tool-call-card') as ToolCallCard;
    el.toolCall = fake({ status: 'completed', output: 'plain text result' });
    document.body.appendChild(el);
    await settle(el);
    expect(el.shadowRoot?.querySelector('[data-testid="tool-search-body"]')).toBeNull();
    expect(el.shadowRoot?.querySelector('.tool-output')?.textContent).toBe('plain text result');
    el.remove();
  });

  it('renders rejected-reason inline when status=rejected', async () => {
    const el = document.createElement('jf-tool-call-card') as ToolCallCard;
    el.toolCall = fake({ status: 'rejected', rejectReason: 'user denied' });
    document.body.appendChild(el);
    await settle(el);
    const reason = el.shadowRoot?.querySelector('.rejected-reason');
    expect(reason?.textContent).toContain('Rejected');
    expect(reason?.textContent).toContain('user denied');
    el.remove();
  });

  // Tempdoc 550 C3 + 543-fwd #2 (merge): the autonomy "because" line is retained (it explains
  // WHY approval is needed), but the inline Approve/Reject buttons are gone.
  it('543-fwd #2 — renders the deterministic because-line for a pending call, reflecting the dial level', async () => {
    setAutonomyLevel('assist');
    const el = document.createElement('jf-tool-call-card') as ToolCallCard;
    el.toolCall = fake({ status: 'pending', risk: 'MEDIUM' });
    document.body.appendChild(el);
    await settle(el);
    const because = el.shadowRoot?.querySelector('[data-testid="tool-call-because"]');
    expect(because?.textContent?.trim()).toBe(
      'Assist mode — write (MEDIUM) actions need your confirmation.',
    );
    el.remove();
  });

  it('543-fwd #2 — HIGH-risk because-line is the always-confirm sentence', async () => {
    const el = document.createElement('jf-tool-call-card') as ToolCallCard;
    el.toolCall = fake({ status: 'pending', risk: 'HIGH' });
    document.body.appendChild(el);
    await settle(el);
    const because = el.shadowRoot?.querySelector('[data-testid="tool-call-because"]');
    expect(because?.textContent?.trim()).toBe(
      'HIGH-risk action — always needs your confirmation.',
    );
    el.remove();
  });

  it('543-fwd #2 — no because-line for non-pending calls', async () => {
    const el = document.createElement('jf-tool-call-card') as ToolCallCard;
    el.toolCall = fake({ status: 'completed' });
    document.body.appendChild(el);
    await settle(el);
    expect(el.shadowRoot?.querySelector('[data-testid="tool-call-because"]')).toBeNull();
    el.remove();
  });

  describe('the search level-2 body (867)', () => {
    function searchCall(overrides: Partial<ToolCall> = {}): ToolCall {
      return fake({
        status: 'completed',
        arguments: '{}',
        structuredData: {
          query: 'taxes',
          resultCount: 2,
          searchResults: [
            { title: 'Taxes 2025', path: 'C:/docs/taxes.md', excerpt: 'WARN deductible limits', line: 42 },
            { title: '', path: '/home/u/notes/budget.txt', excerpt: 'monthly budget', line: 0 },
          ],
        },
        ...overrides,
      });
    }

    it('suppresses the raw args/output dump and the old nested results card entirely', async () => {
      const el = document.createElement('jf-tool-call-card') as ToolCallCard;
      el.expanded = true;
      el.toolCall = searchCall();
      document.body.appendChild(el);
      await settle(el);
      expect(el.shadowRoot?.querySelector('.tool-args')).toBeNull();
      expect(el.shadowRoot?.querySelector('.tool-output')).toBeNull();
      expect(el.shadowRoot?.querySelector('jf-results-card')).toBeNull();
      expect(el.shadowRoot?.querySelector('[data-testid="evidence-lineage"]')).toBeNull();
      el.remove();
    });

    it('the accessory reads "N results" alone when evidencePaths was never wired (unavailable, not zero)', async () => {
      const el = document.createElement('jf-tool-call-card') as ToolCallCard;
      el.toolCall = searchCall();
      document.body.appendChild(el);
      await settle(el);
      const accessory = el.shadowRoot?.querySelector('[data-testid="tool-card-accessory"]');
      expect(accessory?.textContent?.trim()).toBe('2 results');
      el.remove();
    });

    it('the accessory reads "N results · M in evidence" once evidencePaths is wired', async () => {
      const el = document.createElement('jf-tool-call-card') as ToolCallCard;
      el.toolCall = searchCall();
      el.evidencePaths = new Set(['C:/docs/taxes.md']);
      document.body.appendChild(el);
      await settle(el);
      const accessory = el.shadowRoot?.querySelector('[data-testid="tool-card-accessory"]');
      expect(accessory?.textContent?.trim()).toBe('2 results · 1 in evidence');
      el.remove();
    });

    it('an empty (but WIRED) evidence set reads "0 in evidence", not the unavailable case', async () => {
      const el = document.createElement('jf-tool-call-card') as ToolCallCard;
      el.toolCall = searchCall();
      el.evidencePaths = new Set();
      document.body.appendChild(el);
      await settle(el);
      const accessory = el.shadowRoot?.querySelector('[data-testid="tool-card-accessory"]');
      expect(accessory?.textContent?.trim()).toBe('2 results · 0 in evidence');
      el.remove();
    });

    it('renders ONLY the in-evidence hits as rows; the rest are counted, not shown', async () => {
      const el = document.createElement('jf-tool-call-card') as ToolCallCard;
      el.expanded = true;
      el.toolCall = searchCall();
      el.evidencePaths = new Set(['C:/docs/taxes.md']);
      document.body.appendChild(el);
      await settle(el);
      const rows = el.shadowRoot?.querySelectorAll('[data-testid="tool-search-row"]');
      expect(rows).toHaveLength(1);
      const rowText = (rows?.[0]?.textContent ?? '').replace(/\s+/g, ' ');
      expect(rowText).toContain('Taxes 2025');
      expect(rowText).toContain('C:/docs/taxes.md');
      expect(rowText).toContain('Line 42');
      // The retrieved-but-not-in-evidence hit is NOT rendered as a row...
      expect(el.shadowRoot?.textContent).not.toContain('budget.txt');
      // ...but IS counted in the footer (right-reason vs wrong-reason: the count must come from the
      // hits the card actually suppressed, not from a stale total).
      const more = el.shadowRoot?.querySelector('[data-testid="tool-search-more"]');
      expect(more?.textContent).toContain('1 more retrieved, not in evidence');
      el.remove();
    });

    it('omits the footer when every hit is in evidence', async () => {
      const el = document.createElement('jf-tool-call-card') as ToolCallCard;
      el.expanded = true;
      el.toolCall = searchCall();
      el.evidencePaths = new Set(['C:/docs/taxes.md', '/home/u/notes/budget.txt']);
      document.body.appendChild(el);
      await settle(el);
      expect(el.shadowRoot?.querySelector('[data-testid="tool-search-more"]')).toBeNull();
      expect(el.shadowRoot?.querySelectorAll('[data-testid="tool-search-row"]')).toHaveLength(2);
      el.remove();
    });

    it('renders the muted scope line, including the path_prefix scope when the call carried one', async () => {
      const el = document.createElement('jf-tool-call-card') as ToolCallCard;
      el.expanded = true;
      el.toolCall = searchCall({ arguments: '{"path_prefix":"C:/docs"}' });
      document.body.appendChild(el);
      await settle(el);
      const scope = el.shadowRoot?.querySelector('[data-testid="tool-search-scope"]');
      expect(scope?.textContent).toContain('taxes');
      expect(scope?.textContent).toContain('C:/docs');
      el.remove();
    });

    // Tempdoc 867 §2a — "roots · pipeline preset actually used": the resolved mode the backend
    // stamped (SearchTool.resolveEffectiveSearchMode), read into the scope line.
    it('includes the RESOLVED pipeline preset in the scope line when the record carries one', async () => {
      const el = document.createElement('jf-tool-call-card') as ToolCallCard;
      el.expanded = true;
      el.toolCall = searchCall({
        structuredData: {
          query: 'taxes',
          resultCount: 2,
          searchMode: 'hybrid',
          searchResults: [
            { title: 'Taxes 2025', path: 'C:/docs/taxes.md', excerpt: 'WARN deductible limits', line: 42 },
            { title: '', path: '/home/u/notes/budget.txt', excerpt: 'monthly budget', line: 0 },
          ],
        },
      });
      document.body.appendChild(el);
      await settle(el);
      expect(
        el.shadowRoot?.querySelector('[data-testid="tool-search-scope"]')?.textContent,
      ).toContain('hybrid');
      el.remove();
    });

    // The named gap (867 §2a): a record persisted before the `searchMode` stamp renders no preset
    // segment at all — never a guessed one.
    it('omits the preset segment for a record persisted before the searchMode stamp', async () => {
      const el = document.createElement('jf-tool-call-card') as ToolCallCard;
      el.expanded = true;
      el.toolCall = searchCall();
      document.body.appendChild(el);
      await settle(el);
      const scope = el.shadowRoot?.querySelector('[data-testid="tool-search-scope"]')?.textContent ?? '';
      expect(scope).not.toContain('hybrid');
      expect(scope).not.toContain('custom');
      el.remove();
    });

    it('clicking an evidence row fires the same card-open contract the old excerpt rows fired', async () => {
      const el = document.createElement('jf-tool-call-card') as ToolCallCard;
      el.expanded = true;
      el.toolCall = searchCall();
      el.evidencePaths = new Set(['C:/docs/taxes.md']);
      document.body.appendChild(el);
      await settle(el);
      const opened: string[] = [];
      el.addEventListener('card-open', (e) => opened.push((e as CustomEvent<{ id: string }>).detail.id));
      (el.shadowRoot?.querySelector('[data-testid="tool-search-row"]') as HTMLButtonElement).click();
      expect(opened).toEqual(['C:/docs/taxes.md']);
      el.remove();
    });

    it('the "Open in Search" pill dispatches tool-card-open-search with {query, scope}', async () => {
      const el = document.createElement('jf-tool-call-card') as ToolCallCard;
      el.expanded = true;
      el.toolCall = searchCall({ arguments: '{"path_prefix":"C:/docs"}' });
      document.body.appendChild(el);
      await settle(el);
      const events: Array<{ query: string; scope: string }> = [];
      el.addEventListener('tool-card-open-search', (e) =>
        events.push((e as CustomEvent<{ query: string; scope: string }>).detail),
      );
      (
        el.shadowRoot?.querySelector('[data-testid="tool-search-open-in-search"]') as HTMLButtonElement
      ).click();
      expect(events).toEqual([{ query: 'taxes', scope: 'C:/docs' }]);
      el.remove();
    });

    // Honesty edge case: when NEITHER the new structuredData keys NOR the tool's own arguments carry
    // a query, the body is omitted entirely (never a fabricated/empty query) — the raw output shows.
    it('no derivable query at all falls back to the raw output, never fabricates', async () => {
      const el = document.createElement('jf-tool-call-card') as ToolCallCard;
      el.expanded = true;
      el.toolCall = fake({
        status: 'completed',
        arguments: '{}',
        output: 'plain output text',
        structuredData: { searchResults: [{ title: 'Doc A', path: '/a.md', excerpt: 'a', line: 0 }] },
      });
      document.body.appendChild(el);
      await settle(el);
      expect(el.shadowRoot?.querySelector('[data-testid="tool-search-body"]')).toBeNull();
      expect(el.shadowRoot?.querySelector('.tool-output')?.textContent).toBe('plain output text');
      el.remove();
    });
  });
});
