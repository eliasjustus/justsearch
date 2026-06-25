/**
 * Tempdoc 577 §2.12 Move 1 — the control seam's lifecycle predicates: a per-run affordance is a
 * projection of (capability × lifecycle). The predicate and the dispatch share ONE implementation,
 * so the raise-budget-on-DONE 404 / Resume-on-evicted 500 class is unrepresentable at this seam.
 */
import { describe, expect, it, vi } from 'vitest';
import {
  dispatchRunControl,
  directiveAvailable,
  type RunDirective,
} from './runControlIntent.js';
import type { AgentSessionController } from './AgentSessionController.js';

function fakeCtrl(runInFlight: boolean): AgentSessionController {
  return {
    runInFlight,
    send: vi.fn().mockResolvedValue('sent'),
    steer: vi.fn().mockResolvedValue(true),
    cancelSession: vi.fn().mockResolvedValue(undefined),
    raiseBudget: vi.fn().mockResolvedValue(true),
    resumeSession: vi.fn().mockResolvedValue(undefined),
  } as unknown as AgentSessionController;
}

describe('directiveAvailable — the per-directive lifecycle predicate', () => {
  it('initiate is always available', () => {
    expect(directiveAvailable(fakeCtrl(false), { kind: 'initiate', prompt: 'x' })).toBe(true);
    expect(directiveAvailable(fakeCtrl(true), { kind: 'initiate', prompt: 'x' })).toBe(true);
  });

  it('interject / halt / raise-budget require the run to be in flight', () => {
    const live = fakeCtrl(true);
    const dead = fakeCtrl(false);
    const directives: RunDirective[] = [
      { kind: 'interject', text: 'go left' },
      { kind: 'halt' },
      { kind: 'raise-budget', addTokens: 4096 },
    ];
    for (const d of directives) {
      expect(directiveAvailable(live, d), d.kind).toBe(true);
      expect(directiveAvailable(dead, d), d.kind).toBe(false);
    }
  });

  it('resume requires the session to be declared resumable AND no run in flight', () => {
    expect(
      directiveAvailable(fakeCtrl(false), { kind: 'resume', sessionId: 's1', resumable: true }),
    ).toBe(true);
    expect(
      directiveAvailable(fakeCtrl(false), { kind: 'resume', sessionId: 's1', resumable: false }),
    ).toBe(false);
    expect(
      directiveAvailable(fakeCtrl(true), { kind: 'resume', sessionId: 's1', resumable: true }),
    ).toBe(false);
  });
});

describe('dispatchRunControl — refusal shares the predicate with the affordance', () => {
  it('dispatches an available directive to the controller', async () => {
    const ctrl = fakeCtrl(true);
    await dispatchRunControl(ctrl, { kind: 'raise-budget', addTokens: 4096 });
    expect((ctrl as unknown as { raiseBudget: ReturnType<typeof vi.fn> }).raiseBudget)
      .toHaveBeenCalledWith(4096);
  });

  it('refuses (typed no-op) when the predicate fails — the controller is never touched', async () => {
    const ctrl = fakeCtrl(false);
    const result = await dispatchRunControl(ctrl, { kind: 'raise-budget', addTokens: 4096 });
    expect(result).toEqual({ refused: true, kind: 'raise-budget' });
    expect((ctrl as unknown as { raiseBudget: ReturnType<typeof vi.fn> }).raiseBudget)
      .not.toHaveBeenCalled();
  });

  it('refuses resume of a non-resumable session — the 500 class dies at the seam', async () => {
    const ctrl = fakeCtrl(false);
    const result = await dispatchRunControl(ctrl, {
      kind: 'resume',
      sessionId: 'finished',
      resumable: false,
    });
    expect(result).toEqual({ refused: true, kind: 'resume' });
    expect((ctrl as unknown as { resumeSession: ReturnType<typeof vi.fn> }).resumeSession)
      .not.toHaveBeenCalled();
  });

  it('dispatches resume of a resumable session through the seam', async () => {
    const ctrl = fakeCtrl(false);
    await dispatchRunControl(ctrl, { kind: 'resume', sessionId: 's-ok', resumable: true });
    expect((ctrl as unknown as { resumeSession: ReturnType<typeof vi.fn> }).resumeSession)
      .toHaveBeenCalledWith('s-ok');
  });
});

// Tempdoc 577 §2.14 Root II (#14) — the context-decision directive shares the seam.
describe('context-decision — the context-pressure gate directive', () => {
  function ctrlWithContextGate(parked: boolean): AgentSessionController {
    return {
      runInFlight: true,
      contextGate: parked ? { promptTokens: 7800, contextWindow: 8192 } : null,
      resolveContextGate: vi.fn().mockResolvedValue(true),
    } as unknown as AgentSessionController;
  }

  it('is available ONLY while the run is parked at a held context gate', () => {
    expect(
      directiveAvailable(ctrlWithContextGate(true), { kind: 'context-decision', decision: 'summarize' }),
    ).toBe(true);
    expect(
      directiveAvailable(ctrlWithContextGate(false), { kind: 'context-decision', decision: 'summarize' }),
    ).toBe(false);
  });

  it('dispatches the decision to resolveContextGate through the one seam', async () => {
    const ctrl = ctrlWithContextGate(true);
    const directive: RunDirective = { kind: 'context-decision', decision: 'summarize' };
    await dispatchRunControl(ctrl, directive);
    expect((ctrl as unknown as { resolveContextGate: ReturnType<typeof vi.fn> }).resolveContextGate)
      .toHaveBeenCalledWith('summarize');
  });

  it('refuses (typed no-op) when the run is not parked — the controller is never touched', async () => {
    const ctrl = ctrlWithContextGate(false);
    const result = await dispatchRunControl(ctrl, { kind: 'context-decision', decision: 'stop' });
    expect(result).toEqual({ refused: true, kind: 'context-decision' });
    expect((ctrl as unknown as { resolveContextGate: ReturnType<typeof vi.fn> }).resolveContextGate)
      .not.toHaveBeenCalled();
  });
});
