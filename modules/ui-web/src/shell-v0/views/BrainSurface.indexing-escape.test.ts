// @vitest-environment happy-dom

/**
 * 0.2.0 round F-6 / F-6b — the AI Brain Simple panel's `indexing` state.
 *
 * F-6: there is no `offline` inference mode on the wire (`core.switch-inference-mode` accepts only
 * `online`/`indexing`), and `indexing` used to share the `online`/default branch, whose only button
 * ("Shut Down AI") re-POSTed `indexing` — the mode it was already in. A Simple-mode user who landed
 * in `indexing` (the idle auto-trigger gets them there without a click) had no way back to chat.
 *
 * F-6b: `statusConfig` kept its own copy of the online/indexing wording and called BOTH "AI Online",
 * so the panel showed a green "AI Online" headline while chat was down and the footer pill (which
 * reads the canonical `aiEngineHeadline`/`aiEngineTone`) said "Indexing".
 *
 * These pin the escape hatch and the projection onto the canonical authority — the label is asserted
 * against `aiEngineHeadline`, not a string literal, so the two cannot re-fork.
 */

import { describe, expect, it } from 'vitest';
import { render, type TemplateResult } from 'lit';
import './BrainSurface.js';
import { aiEngineHeadline, type AiEngineVerdict } from '../state/aiVerdict.js';
import { unavailableBecause } from '../state/availability.js';

interface PanelHarness {
  apiBase: string;
  policy: unknown;
  busy: Record<string, boolean>;
  installStatus: unknown;
  inference: unknown;
  _unifiedAiState: unknown;
  host_: unknown;
  fetchJson(path: string): Promise<unknown>;
  renderSimplePanel(): TemplateResult;
}

interface ActionButton extends Element {
  onActivate: () => unknown;
  availability: unknown;
}

const verdict = (kind: AiEngineVerdict['kind']): AiEngineVerdict =>
  ({ kind, stability: { kind: 'settled' }, installFailure: null }) as AiEngineVerdict;

/** Build a detached BrainSurface (no connectedCallback ⇒ no poll/subscribe), drive the private
 *  Simple-panel renderer, and return the rendered panel plus the recorded op invocations. */
function renderPanel(kind: AiEngineVerdict['kind'], opts: { onlineAiEnabled?: boolean } = {}) {
  const invocations: Array<{ operationId: string; args: Record<string, unknown> }> = [];
  const el = document.createElement('jf-brain-surface') as unknown as PanelHarness;
  el.apiBase = '';
  el.busy = {};
  el.policy = { downloadsEnabled: true, onlineAiEnabled: opts.onlineAiEnabled ?? true };
  el.installStatus = null;
  el.inference = null;
  el._unifiedAiState = { aiEngine: verdict(kind) };
  // The `host_` seam is `invokeOp`'s first branch — the op call lands here instead of on the wire.
  el.host_ = {
    data: {
      invokeOperation: (operationId: string, args: Record<string, unknown>) => {
        invocations.push({ operationId, args });
        return Promise.resolve({ structuredData: null });
      },
    },
  };
  // switchInference's post-action refresh; stubbed so the test stays hermetic.
  el.fetchJson = () => Promise.resolve(null);

  const container = document.createElement('div');
  render(el.renderSimplePanel(), container);
  const button = container.querySelector('[data-testid="brain-simple-action"]') as ActionButton;
  return { container, button, invocations };
}

describe('BrainSurface Simple panel — indexing is escapable (F-6)', () => {
  it('offers a way back online instead of re-POSTing the mode it is already in', async () => {
    const { button, invocations } = renderPanel('indexing');

    expect(button.getAttribute('label')).not.toBe('Shut Down AI');

    await button.onActivate();

    expect(invocations).toEqual([
      { operationId: 'core.switch-inference-mode', args: { mode: 'online' } },
    ]);
  });

  it('gates the escape hatch behind the online-AI policy, with a showable reason', () => {
    const { button } = renderPanel('indexing', { onlineAiEnabled: false });

    expect(button.availability).toEqual(
      unavailableBecause('Online AI is disabled by administrator policy.'),
    );
  });

  it('still shuts down from online (no regression)', async () => {
    const { button, invocations } = renderPanel('online');

    expect(button.getAttribute('label')).toBe('Shut Down AI');

    await button.onActivate();

    expect(invocations).toEqual([
      { operationId: 'core.switch-inference-mode', args: { mode: 'indexing' } },
    ]);
  });
});

describe('BrainSurface Simple panel — indexing is not "AI Online" (F-6b)', () => {
  it('headlines indexing with the canonical wording, not a green "AI Online"', () => {
    const { container } = renderPanel('indexing');

    expect(container.textContent).not.toContain('AI Online');
    expect(container.textContent).toContain(aiEngineHeadline(verdict('indexing')));
  });

  it('headlines online with the canonical wording too (one authority, both kinds)', () => {
    const { container } = renderPanel('online');

    // The forked literal is gone from BOTH kinds, not just the one that was wrong.
    expect(container.textContent).not.toContain('AI Online');
    expect(container.textContent).toContain(aiEngineHeadline(verdict('online')));
  });
});
