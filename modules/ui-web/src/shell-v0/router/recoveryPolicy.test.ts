import { describe, expect, it } from 'vitest';
import { interactivePolicy, strictPolicy } from './recoveryPolicy.js';
import type { ResolutionResult, Suggestion } from './resolution.js';

const mkSuggestion = (id: string, confidence: number): Suggestion => ({
  id, label: id, confidence, rationale: 'test',
});

describe('strictPolicy', () => {
  it('resolved → proceed', () => {
    const r: ResolutionResult = { status: 'resolved', id: 'core.x' };
    expect(strictPolicy(r).kind).toBe('proceed');
  });
  it('redirected → abort', () => {
    const r: ResolutionResult = { status: 'redirected', id: 'core.x', originalId: 'core.old', reason: 'alias' };
    expect(strictPolicy(r).kind).toBe('abort');
  });
  it('unresolved → abort', () => {
    const r: ResolutionResult = { status: 'unresolved', attemptedId: 'core.x', diagnosis: { mode: 'unknown', detail: '' }, alternatives: [] };
    expect(strictPolicy(r).kind).toBe('abort');
  });
});

describe('interactivePolicy', () => {
  it('resolved → proceed', () => {
    const r: ResolutionResult = { status: 'resolved', id: 'core.x' };
    expect(interactivePolicy(r).kind).toBe('proceed');
  });

  it('redirected → auto-correct', () => {
    const r: ResolutionResult = { status: 'redirected', id: 'core.x', originalId: 'core.old', reason: 'alias' };
    const action = interactivePolicy(r);
    expect(action.kind).toBe('auto-correct');
  });

  it('unresolved with 1 high-confidence alternative (DL=1) → auto-correct', () => {
    const r: ResolutionResult = {
      status: 'unresolved',
      attemptedId: 'core.libary-surface',
      diagnosis: { mode: 'typo', detail: '' },
      alternatives: [mkSuggestion('core.library-surface', 0.705)],
    };
    const action = interactivePolicy(r);
    expect(action.kind).toBe('auto-correct');
  });

  it('unresolved with multiple alternatives → suggest', () => {
    const r: ResolutionResult = {
      status: 'unresolved',
      attemptedId: 'core.surface',
      diagnosis: { mode: 'typo', detail: '' },
      alternatives: [
        mkSuggestion('core.library-surface', 0.5),
        mkSuggestion('core.search-surface', 0.45),
      ],
    };
    const action = interactivePolicy(r);
    expect(action.kind).toBe('suggest');
  });

  it('unresolved with no alternatives → abort', () => {
    const r: ResolutionResult = {
      status: 'unresolved',
      attemptedId: 'core.ghost',
      diagnosis: { mode: 'unknown', detail: 'not found' },
      alternatives: [],
    };
    expect(interactivePolicy(r).kind).toBe('abort');
  });

  it('unresolved with 1 low-confidence alternative → suggest (not auto-correct)', () => {
    const r: ResolutionResult = {
      status: 'unresolved',
      attemptedId: 'core.completely-different',
      diagnosis: { mode: 'typo', detail: '' },
      alternatives: [mkSuggestion('core.library-surface', 0.30)],
    };
    const action = interactivePolicy(r);
    expect(action.kind).toBe('suggest');
  });

  it('unresolved with small gap between top two → suggest (not auto-correct)', () => {
    const r: ResolutionResult = {
      status: 'unresolved',
      attemptedId: 'core.libary-surface',
      diagnosis: { mode: 'typo', detail: '' },
      alternatives: [
        mkSuggestion('core.library-surface', 0.705),
        mkSuggestion('core.logs-surface', 0.582),
        mkSuggestion('core.search-surface', 0.571),
      ],
    };
    const action = interactivePolicy(r);
    expect(action.kind).toBe('suggest');
  });

  it('unresolved with large gap to second → auto-correct', () => {
    const r: ResolutionResult = {
      status: 'unresolved',
      attemptedId: 'core.search-surfce',
      diagnosis: { mode: 'typo', detail: '' },
      alternatives: [
        mkSuggestion('core.search-surface', 0.792),
        mkSuggestion('core.health-surface', 0.507),
      ],
    };
    const action = interactivePolicy(r);
    expect(action.kind).toBe('auto-correct');
    if (action.kind === 'auto-correct') {
      expect(action.id).toBe('core.search-surface');
    }
  });
});
