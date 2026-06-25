// @vitest-environment happy-dom

/**
 * Tempdoc 622 §17/§18 — GovernanceView surfaces per-gate ACTIVATION efficacy (does each discipline
 * gate ever fire / find anything), joined onto the committed roster from the local-runtime
 * `efficacy` block of GET /api/governance/state. These tests pin: the Activation column renders the
 * right tone-labels per status (found-something / silent / never-local / orphaned), the
 * local-only disclaimer is present (honesty: "never (local)" ≠ dead), and the surface degrades to an
 * em-dash when no efficacy is served (clean checkout / CI).
 */

import { afterEach, describe, expect, it, vi } from 'vitest';
import './GovernanceView.js';

interface Efficacy {
  available: boolean;
  scope?: string;
  byGate: Array<{
    gate: string;
    totalRuns: number;
    runsWithFindings: number;
    error: number;
    warning: number;
    note: number;
    status: 'active' | 'never-fired' | 'orphaned';
  }>;
}

function stateBody(efficacy?: Efficacy): unknown {
  return {
    source: 'tmp/governance-report.sarif',
    registry: {
      schema: 'governance-state.v1',
      gateCount: 2,
      gates: [
        { id: 'class-size', title: 'Class-size ratchet', tier: 'ratchet', hasChangesets: true },
        { id: 'sleepy-gate', title: 'A gate that never ran locally', tier: 'ratchet', hasChangesets: false },
      ],
      exceptions: { maxExceptions: 56 },
      strengthFloors: [],
      classSizeDebt: { files: 0, totalDebt: 0, worst: [] },
    },
    ...(efficacy ? { efficacy } : {}),
    available: false,
    gates: [],
  };
}

async function mount(body: unknown): Promise<HTMLElement & { updateComplete: Promise<unknown> }> {
  global.fetch = vi.fn().mockResolvedValue({
    ok: true,
    status: 200,
    json: async () => body,
  }) as unknown as typeof fetch;
  const el = document.createElement('jf-governance-view') as HTMLElement & {
    updateComplete: Promise<unknown>;
  };
  document.body.appendChild(el);
  // Let the connectedCallback fetch chain settle, then the re-render.
  for (let i = 0; i < 4; i++) {
    await new Promise((r) => setTimeout(r, 0));
    await el.updateComplete;
  }
  return el;
}

function text(el: HTMLElement): string {
  return el.shadowRoot?.textContent ?? '';
}

afterEach(() => {
  document.body.innerHTML = '';
  vi.restoreAllMocks();
});

describe('GovernanceView — activation efficacy (tempdoc 622 §17/§18)', () => {
  it('renders an Activation column with per-gate tone-labels from the efficacy block', async () => {
    const el = await mount(
      stateBody({
        available: true,
        scope: 'local',
        byGate: [
          { gate: 'class-size', totalRuns: 258, runsWithFindings: 183, error: 139, warning: 0, note: 243, status: 'active' },
          { gate: 'sleepy-gate', totalRuns: 0, runsWithFindings: 0, error: 0, warning: 0, note: 0, status: 'never-fired' },
        ],
      }),
    );
    const t = text(el);
    expect(t).toContain('Activation'); // the new column header
    expect(t).toContain('258 runs · 139 err'); // a gate that earns its keep
    expect(t).toContain('never (local)'); // a roster gate unexercised locally
  });

  it('shows the local-only disclaimer (never-local ≠ dead)', async () => {
    const el = await mount(
      stateBody({ available: true, scope: 'local', byGate: [] }),
    );
    expect(text(el).toLowerCase()).toContain('local');
    expect(text(el)).toContain('not dead');
  });

  it('degrades to an em-dash when no efficacy is served (clean checkout / CI)', async () => {
    const el = await mount(stateBody(undefined));
    const t = text(el);
    expect(t).toContain('Activation');
    // no efficacy entries → both gate rows show the empty marker, no run-labels
    expect(t).not.toContain('runs ·');
    expect(t).toContain('—');
  });
});
