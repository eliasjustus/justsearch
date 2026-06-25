// @vitest-environment happy-dom

/**
 * Tempdoc 596 — projectAvailability tests. Pins the capability→availability projection:
 * one reason per gap, the loading window is transient, and a reranker-only "degraded"
 * (chat still up) never marks an affordance unavailable.
 */
import { describe, expect, it } from 'vitest';
import { projectAvailability, unavailableBecause, type Availability } from './availability.js';
import type { AiState } from './aiStateStore.js';
import { known, UNKNOWN } from './known.js';

/** Minimal AiState carrying only the fields projectAvailability reads. */
function aiState(opts: {
  phase?: AiState['phase'];
  chat?: boolean;
  docs?: number | 'unknown';
  mode?: AiState['runtime']['mode'];
  pendingJobs?: number;
  /** When set, builds a degraded verdict with this severity (the ONE authority projectAvailability reads). */
  degradedSeverity?: 'info' | 'warn';
  /** Tempdoc 601 — the last successful startup duration the model-load estimate reads (-1 ⇒ unknown). */
  lastStartupMs?: number;
}): AiState {
  const docs = opts.docs ?? 'unknown';
  const verdict =
    opts.degradedSeverity === undefined
      ? { kind: 'operational', severity: 'ok', reasons: [] }
      : { kind: 'degraded', severity: opts.degradedSeverity, reasons: [] };
  return {
    phase: opts.phase ?? 'connected',
    capabilities: { chat: opts.chat ?? true, rag: false, extract: false, embedding: false },
    runtime: { mode: opts.mode ?? 'online' },
    verdict,
    index: {
      documentCount: docs === 'unknown' ? UNKNOWN : known(docs),
      pendingJobs: opts.pendingJobs === undefined ? UNKNOWN : known(opts.pendingJobs),
    },
    inference: opts.lastStartupMs === undefined ? null : { lastStartupDurationMs: opts.lastStartupMs },
  } as unknown as AiState;
}

const isUnavailable = (a: Availability): a is Extract<Availability, { kind: 'unavailable' }> =>
  a.kind === 'unavailable';

describe('projectAvailability (tempdoc 596)', () => {
  it('null store → transient loading reason (the obs #420 loading window)', () => {
    const a = projectAvailability('agent', null);
    expect(a.kind).toBe('unavailable');
    expect(isUnavailable(a) && a.transient).toBe(true);
    // §17: wording comes from the shared reason vocabulary (`inference.starting`).
    expect(isUnavailable(a) && /start|load/i.test(a.reason)).toBe(true);
  });

  it("phase==='connecting' → transient (still loading, not offline)", () => {
    const a = projectAvailability('documents', aiState({ phase: 'connecting' }));
    expect(isUnavailable(a) && a.transient).toBe(true);
  });

  // Tempdoc 601 — model-load time-estimate on the actively-loading (runtime.mode==='starting') gap.
  it("runtime.mode==='starting' with a prior duration → transient 'still starting' + '~Ns' estimate", () => {
    const a = projectAvailability('documents', aiState({ chat: false, mode: 'starting', lastStartupMs: 11000 }));
    expect(a.kind).toBe('unavailable');
    expect(isUnavailable(a) && a.transient).toBe(true);
    expect(isUnavailable(a) && /still starting/i.test(a.reason)).toBe(true);
    // The estimate arm: the suffix carries the rounded seconds, never a countdown.
    expect(isUnavailable(a) && a.reason).toContain('usually ready in ~11s');
  });

  it("runtime.mode==='starting' without a prior duration (-1) → 'still starting' with NO number (unknown arm)", () => {
    const a = projectAvailability('documents', aiState({ chat: false, mode: 'starting', lastStartupMs: -1 }));
    expect(a.kind).toBe('unavailable');
    expect(isUnavailable(a) && a.transient).toBe(true);
    expect(isUnavailable(a) && /still starting/i.test(a.reason)).toBe(true);
    // No fabricated number on the unknown arm.
    expect(isUnavailable(a) && /~|usually ready/i.test(a.reason)).toBe(false);
  });

  it("runtime.mode==='starting' takes precedence over the settled offline reason (keyed on load state)", () => {
    // chat:false would otherwise yield the settled 'offline'; the starting branch must win.
    const a = projectAvailability('agent', aiState({ chat: false, mode: 'starting', lastStartupMs: 8000 }));
    expect(isUnavailable(a) && a.transient).toBe(true);
    expect(isUnavailable(a) && /offline/i.test(a.reason)).toBe(false);
  });

  it('chat offline → settled offline reason + the shared-vocabulary remedy (§17)', () => {
    const a = projectAvailability('agent', aiState({ chat: false }));
    expect(a.kind).toBe('unavailable');
    expect(isUnavailable(a) && a.transient).toBeFalsy();
    expect(isUnavailable(a) && /offline/i.test(a.reason)).toBe(true);
    // §17 Move A — the affordance now carries the SAME remedy the banner does (no fork).
    expect(isUnavailable(a) && a.remedy).toEqual({ kind: 'operation', operationId: 'core.reload-inference' });
  });

  it('documents with zero indexed docs (idle) → settled "No documents indexed" + onboarding remedy', () => {
    const a = projectAvailability('documents', aiState({ chat: true, docs: 0 }));
    expect(a.kind).toBe('unavailable');
    expect(isUnavailable(a) && a.transient).toBeFalsy();
    expect(isUnavailable(a) && /document/i.test(a.reason)).toBe(true);
    // §16.5 remedy-driven onboarding: the dead-end carries the "Add documents" → Library navigate remedy.
    expect(isUnavailable(a) && a.remedy).toEqual({
      kind: 'navigate',
      target: 'core.library-surface',
      label: 'Add documents',
    });
  });

  it('zero docs WHILE indexing (runtime.mode) → transient forward-looking reason (§16.4 availableWhen)', () => {
    const a = projectAvailability('documents', aiState({ chat: true, docs: 0, mode: 'indexing' }));
    expect(a.kind).toBe('unavailable');
    expect(isUnavailable(a) && a.transient).toBe(true);
    expect(isUnavailable(a) && /indexing|once/i.test(a.reason)).toBe(true);
  });

  it('zero docs WHILE jobs pending → transient forward-looking reason (§16.4 availableWhen)', () => {
    const a = projectAvailability('documents', aiState({ chat: true, docs: 0, pendingJobs: 3 }));
    expect(isUnavailable(a) && a.transient).toBe(true);
    expect(isUnavailable(a) && /indexing|once/i.test(a.reason)).toBe(true);
  });

  it('documents with docs>0 and chat up → available', () => {
    expect(projectAvailability('documents', aiState({ chat: true, docs: 5 })).kind).toBe('available');
  });

  it('documents usable but verdict degraded (cosmetic/info) → degraded caveat, still operable', () => {
    // chat up, docs present, the ONE verdict is degraded at info severity (optional re-ranker off).
    const a = projectAvailability(
      'documents',
      aiState({ chat: true, docs: 5, degradedSeverity: 'info' }),
    );
    expect(a.kind).toBe('degraded');
    expect(a.kind === 'degraded' && /optional|ranking|simpler/i.test(a.caveat)).toBe(true);
  });

  it('documents with verdict degraded at warn → caveat words the keyword fallback', () => {
    const a = projectAvailability(
      'documents',
      aiState({ chat: true, docs: 5, degradedSeverity: 'warn' }),
    );
    expect(a.kind).toBe('degraded');
    expect(a.kind === 'degraded' && /keyword|degraded/i.test(a.caveat)).toBe(true);
  });

  it('documents with an operational verdict → available (no caveat)', () => {
    expect(projectAvailability('documents', aiState({ chat: true, docs: 5 })).kind).toBe('available');
  });

  it('extract/agent only need chat (zero docs is irrelevant to them)', () => {
    expect(projectAvailability('extract', aiState({ chat: true, docs: 0 })).kind).toBe('available');
    expect(projectAvailability('agent', aiState({ chat: true, docs: 0 })).kind).toBe('available');
  });

  it('docs unknown (not yet reported) does not fabricate a zero-docs block', () => {
    // chat up, docs unknown → available (we do not assert "no documents" without data).
    expect(projectAvailability('documents', aiState({ chat: true, docs: 'unknown' })).kind).toBe(
      'available',
    );
  });
});

describe('unavailableBecause — literal local-gap reason (tempdoc 596 §16.2 a11y-debt close)', () => {
  it('builds a settled unavailable carrying the verbatim reason, no remedy', () => {
    const a = unavailableBecause('No unread advisories');
    expect(a.kind).toBe('unavailable');
    expect(isUnavailable(a) && a.reason).toBe('No unread advisories');
    expect(isUnavailable(a) && a.transient).toBeFalsy();
    expect(isUnavailable(a) && a.remedy).toBeUndefined();
  });

  it('transient=true marks a self-clearing gap (an in-flight refresh)', () => {
    const a = unavailableBecause('Refreshing…', true);
    expect(isUnavailable(a) && a.transient).toBe(true);
  });
});
