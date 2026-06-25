/**
 * Tempdoc 565 §17 — the run-step presentation projection: ONE StepPresentation per step (glyph + tone
 * siblings of the §3.B status authority), with the per-kind status inference owned here rather than
 * hand-rolled in renderRunSpine (§17.G).
 */
import { describe, it, expect } from 'vitest';
import { statusGlyph } from '../utils/statusTone.js';
import { stepPresentation } from './runStepPresentation.js';
import {
  prominenceFor,
  type UnifiedTurnItem,
  type UnifiedTurnKind,
} from './unifiedThreadProjection.js';

function item(
  kind: UnifiedTurnKind,
  attributes: Record<string, unknown> = {},
  content = '',
): UnifiedTurnItem {
  return {
    id: 'i',
    ts: 0,
    kind,
    prominence: prominenceFor(kind),
    originator: kind === 'user' ? 'user' : 'agent',
    content,
    attributes,
  };
}

describe('statusGlyph — the §17 glyph sibling of statusToTone', () => {
  it('maps decisive states to distinct glyphs, routine/info to none (sparse by intent)', () => {
    expect(statusGlyph('completed')).toBe('done');
    expect(statusGlyph('approved')).toBe('done');
    expect(statusGlyph('rejected')).toBe('denied'); // a denied tool ⊘
    expect(statusGlyph('error')).toBe('error'); // a run failure ✕ — distinct from denied
    expect(statusGlyph('failed')).toBe('error');
    expect(statusGlyph('warn')).toBe('warn');
    expect(statusGlyph('executing')).toBe('running');
    expect(statusGlyph('running')).toBe('running');
    expect(statusGlyph('pending')).toBe('pending');
    expect(statusGlyph('proposed')).toBe('pending');
    expect(statusGlyph('info')).toBe('none'); // routine INFO = no glyph (backend 561 #5)
    expect(statusGlyph('')).toBe('none');
    expect(statusGlyph(undefined)).toBe('none');
  });
});

describe('stepPresentation — the §17 run-step presentation projection', () => {
  it('projects a tool step: composeToolLabel label+target, status→tone+glyph, prominence, not-live', () => {
    const p = stepPresentation(
      item('tool-activity', {
        status: 'completed',
        toolName: 'core_search_index',
        arguments: '{"query":"discipline-gate"}',
      }),
    );
    expect(p.tone).toBe('success');
    expect(p.glyph).toBe('done');
    expect(p.label.toLowerCase()).toContain('search');
    expect(p.label).toContain('discipline-gate');
    expect(p.prominence).toBe('secondary');
    expect(p.live).toBe(false);
  });

  it('marks an executing tool as live with a running glyph', () => {
    const p = stepPresentation(item('tool-activity', { status: 'executing', toolName: 'core_ingest_files' }));
    expect(p.glyph).toBe('running');
    expect(p.live).toBe(true);
    expect(p.tone).toBe('info');
  });

  it('distinguishes a denied tool (⊘) from a run error (✕) — the §17.G inference correction', () => {
    expect(stepPresentation(item('tool-activity', { status: 'rejected' })).glyph).toBe('denied');
    const err = stepPresentation(item('error', {}, 'Could not reach the model'));
    expect(err.glyph).toBe('error');
    expect(err.tone).toBe('error');
    expect(err.label).toBe('Could not reach the model');
  });

  it('infers the assistant answer as completed (done, success, primary)', () => {
    const p = stepPresentation(item('assistant', {}, 'the answer'));
    expect(p.glyph).toBe('done');
    expect(p.tone).toBe('success');
    expect(p.prominence).toBe('primary');
    expect(p.label).toBe('Answer');
  });

  it('renders a routine progress step from its human backend message, no glyph (INFO)', () => {
    const p = stepPresentation(item('progress', {}, 'Calling LLM'));
    expect(p.label).toBe('Calling LLM'); // backend content — NOT uppercased, NOT a raw event name
    expect(p.glyph).toBe('none'); // routine = no glyph (backend 561 #5)
    expect(p.tone).toBe('neutral');
    expect(p.live).toBe(false);
  });

  // Tempdoc 577 §2.12 Move 4 — the loop's DECISIONS narrate as acts, projected from the typed phase
  // token (not the backend prose), so the thread reads the retry/finalize/budget-gate story.
  it('projects the Move-4 decision phases from the typed token, not the prose message', () => {
    expect(stepPresentation(item('progress', { phase: 'retry_after_tool_failure' }, 'x')).label).toBe(
      'Retrying with corrected input',
    );
    expect(stepPresentation(item('progress', { phase: 'finalizing' }, 'x')).label).toBe('Wrapping up');
    expect(stepPresentation(item('progress', { phase: 'budget_gate_held' }, 'x')).label).toBe(
      'Waiting on budget',
    );
    // An unknown phase still degrades to the backend prose (forward-compat).
    expect(stepPresentation(item('progress', { phase: 'some_future_phase' }, 'Backend prose')).label).toBe(
      'Backend prose',
    );
  });

  it('decorates a warning progress step (severity → warn glyph + tone)', () => {
    const p = stepPresentation(item('progress', { severity: 'warn' }, 'Retrying'));
    expect(p.glyph).toBe('warn');
    expect(p.tone).toBe('warning');
  });

  // Tempdoc 577 Ext I — outcome is a facet distinct from lifecycle: completed+success=false reads failed.
  it('presents a completed tool whose result failed as failed (✕ error), not done (✓)', () => {
    const p = stepPresentation(
      item('tool-activity', {
        status: 'completed',
        success: false,
        toolName: 'core_search_index',
        arguments: '{"query":"x","limit":"10"}',
      }),
    );
    expect(p.glyph).toBe('error');
    expect(p.tone).toBe('error');
    expect(p.live).toBe(false);
  });

  it('keeps a completed tool with success=true (or no outcome on old records) as done', () => {
    expect(
      stepPresentation(item('tool-activity', { status: 'completed', success: true })).glyph,
    ).toBe('done');
    expect(stepPresentation(item('tool-activity', { status: 'completed' })).glyph).toBe('done');
  });
});
