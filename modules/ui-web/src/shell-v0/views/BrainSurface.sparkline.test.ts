// @vitest-environment happy-dom
/**
 * Tempdoc 518 Wave A-E defect Fix-10 — pin the generation sparkline rendering contract.
 *
 * Exercises `renderGenerationSparkline` via the full Lit lifecycle (mount element, set
 * state, await updateComplete, inspect shadowRoot.innerHTML). Direct `String(TemplateResult)`
 * doesn't serialize Lit templates — must render through the element.
 */

import { describe, expect, it } from 'vitest';
import './BrainSurface';

interface SparklineHost extends HTMLElement {
  inference: { generation?: number; mode?: string; activeModelId?: string | null } | null;
  transitions: Array<{
    timestampMs: number;
    fromMode: string;
    toMode: string;
    reason: string;
    success: boolean;
    durationMs: number;
    wireCode?: string;
  }>;
  updateComplete: Promise<boolean>;
}

async function mountAndRender(state: {
  generation: number;
  transitions: SparklineHost['transitions'];
}): Promise<string> {
  const el = document.createElement('jf-brain-surface') as SparklineHost;
  el.inference = { generation: state.generation, mode: 'offline', activeModelId: null };
  el.transitions = state.transitions;
  document.body.appendChild(el);
  await el.updateComplete;
  const html = el.shadowRoot?.innerHTML ?? '';
  document.body.removeChild(el);
  return html;
}

describe('BrainSurface.renderGenerationSparkline', () => {
  it('renders no sparkline when generation is 0', async () => {
    const html = await mountAndRender({
      generation: 0,
      transitions: [
        { timestampMs: 1, fromMode: 'OFFLINE', toMode: 'OFFLINE', reason: 'X', success: false, durationMs: 1 },
      ],
    });
    expect(html).not.toContain('brain-generation-sparkline');
  });

  it('renders no sparkline when no transitions are present', async () => {
    const html = await mountAndRender({ generation: 3, transitions: [] });
    expect(html).not.toContain('brain-generation-sparkline');
  });

  it('renders polyline + per-row dots when generation > 0', async () => {
    const html = await mountAndRender({
      generation: 3,
      transitions: [
        { timestampMs: 100, fromMode: 'OFFLINE', toMode: 'ONLINE', reason: 'USER_SWITCH', success: true, durationMs: 5 },
        { timestampMs: 200, fromMode: 'ONLINE', toMode: 'INDEXING', reason: 'USER_SWITCH', success: true, durationMs: 5 },
        { timestampMs: 300, fromMode: 'INDEXING', toMode: 'ONLINE', reason: 'USER_SWITCH', success: true, durationMs: 5 },
      ],
    });
    expect(html).toContain('brain-generation-sparkline');
    expect(html).toContain('brain-generation-sparkline-line');
    expect(html).toContain('<polyline');
    const circleCount = (html.match(/<circle/g) ?? []).length;
    expect(circleCount).toBe(3);
  });

  it('failures contribute dots; failure dot uses the red fill', async () => {
    const html = await mountAndRender({
      generation: 1,
      transitions: [
        { timestampMs: 100, fromMode: 'OFFLINE', toMode: 'ONLINE', reason: 'USER_SWITCH', success: true, durationMs: 5 },
        { timestampMs: 200, fromMode: 'ONLINE', toMode: 'OFFLINE', reason: 'USER_SWITCH', success: false, durationMs: 5, wireCode: 'process_died' },
      ],
    });
    // §2.C / P1c: the failure dot uses the unified danger token, not a raw red hex.
    expect(html).toContain('var(--accent-danger)');
    expect(html).not.toContain('#f87171');
    const circleCount = (html.match(/<circle/g) ?? []).length;
    expect(circleCount).toBe(2);
  });
});
