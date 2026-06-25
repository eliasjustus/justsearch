// @vitest-environment happy-dom

/**
 * §32 R-E5 (previewSequence / previewMacro) + U4 (parameterized macros).
 */

import { afterEach, beforeEach, describe, it, expect } from 'vitest';
import type { JsonSchema } from '@jsonforms/core';
import {
  defineMacro,
  defineMacroFromEffects,
  extractMacroConstants,
  tokenizeMacroEffects,
  runMacro,
  previewMacro,
  getMacro,
  __resetMacrosForTest,
} from './index.js';
import {
  previewSequence,
  listJournal,
  __resetJournalForTest,
} from '../effects/index.js';
import {
  resolveElicit,
  cancelElicit,
  listPendingElicits,
  __resetElicitForTest,
} from '../elicit/index.js';
import { __resetActionsForTest } from '../actions/index.js';
import { CORE_PROVENANCE } from '../../primitives/provenance.js';

const SCHEMA: JsonSchema = {
  type: 'object',
  properties: { name: { type: 'string' } },
};

beforeEach(() => {
  __resetMacrosForTest();
  __resetJournalForTest();
  __resetElicitForTest();
  __resetActionsForTest();
  globalThis.localStorage?.clear();
});

afterEach(() => {
  __resetElicitForTest();
});

describe('§32 R-E5 — previewSequence', () => {
  it('returns one preview per effect and dispatches nothing', () => {
    const preview = previewSequence(
      [
        { kind: 'navigate', to: '#a' },
        { kind: 'toast', message: 'x' },
      ],
      CORE_PROVENANCE,
    );
    expect(preview).toHaveLength(2);
    expect(preview[0]!.effect.kind).toBe('navigate');
    expect(listJournal()).toHaveLength(0); // no append, no dispatch
  });
});

describe('§32 U4 — parameterized macros', () => {
  it('prompts via elicit, substitutes {{key}}, then dispatches', async () => {
    const toasts: string[] = [];
    document.addEventListener('jf-advisory-ephemeral', (e: Event) => {
      toasts.push((e as CustomEvent).detail.message as string);
    });
    defineMacro({
      id: 'greet',
      label: 'Greet',
      params: { title: 'Who?', schema: SCHEMA },
      effects: [{ kind: 'toast', message: 'Hello {{name}}' }],
    });
    const p = runMacro('greet'); // awaits elicit
    await Promise.resolve();
    const reqs = listPendingElicits();
    expect(reqs).toHaveLength(1);
    resolveElicit(reqs[0]!.id, { name: 'World' });
    const count = await p;
    expect(count).toBe(1);
    expect(toasts).toEqual(['Hello World']);
  });

  it('a cancelled prompt dispatches nothing', async () => {
    defineMacro({
      id: 'g2',
      label: 'G2',
      params: { title: '?', schema: SCHEMA },
      effects: [{ kind: 'toast', message: 'x' }],
    });
    const p = runMacro('g2');
    await Promise.resolve();
    cancelElicit(listPendingElicits()[0]!.id);
    expect(await p).toBe(0);
  });

  it('previewMacro substitutes vars and dispatches nothing', () => {
    defineMacro({
      id: 'g3',
      label: 'G3',
      params: { title: '?', schema: SCHEMA },
      effects: [{ kind: 'toast', message: 'Hi {{name}}' }],
    });
    const preview = previewMacro('g3', { name: 'Z' });
    expect(preview).toHaveLength(1);
    const eff = preview[0]!.effect;
    expect(eff.kind).toBe('toast');
    if (eff.kind === 'toast') expect(eff.message).toBe('Hi Z');
    expect(listJournal()).toHaveLength(0);
  });
});

describe('543-fwd #9 — defineMacroFromEffects (interactive author)', () => {
  it('prompts for a label then defines a macro with the given effects', async () => {
    const p = defineMacroFromEffects([
      { kind: 'invoke-operation', operationId: 'core_file_operations' },
      { kind: 'navigate', to: '#a' },
    ]);
    await Promise.resolve();
    const reqs = listPendingElicits();
    expect(reqs).toHaveLength(1);
    resolveElicit(reqs[0]!.id, { label: 'AI workflow' });
    const macro = await p;
    expect(macro).not.toBeNull();
    expect(macro!.label).toBe('AI workflow');
    expect(macro!.effects.map((e) => e.kind)).toEqual(['invoke-operation', 'navigate']);
    expect(getMacro(macro!.id)).toBe(macro); // registered
  });

  it('returns null on empty effects (no prompt)', async () => {
    expect(await defineMacroFromEffects([])).toBeNull();
    expect(listPendingElicits()).toHaveLength(0);
  });

  it('returns null when the prompt is cancelled', async () => {
    const p = defineMacroFromEffects([{ kind: 'noop' }]);
    await Promise.resolve();
    cancelElicit(listPendingElicits()[0]!.id);
    expect(await p).toBeNull();
  });

  it('returns null when the label is blank (whitespace)', async () => {
    const p = defineMacroFromEffects([{ kind: 'noop' }]);
    await Promise.resolve();
    resolveElicit(listPendingElicits()[0]!.id, { label: '   ' });
    expect(await p).toBeNull();
  });
});

describe('543-fwd #10 — parameterize-on-save', () => {
  it('extractMacroConstants pulls query/path/message values, skips structural ids', () => {
    const consts = extractMacroConstants([
      { kind: 'invoke-operation', operationId: 'core_search_index', args: { query: 'budget 2025' } },
      { kind: 'navigate', to: '#/search' },
      { kind: 'toast', message: 'done' },
      { kind: 'open-pane', paneId: 'inspector' }, // structural id — skipped
    ]);
    expect(consts).toEqual(['budget 2025', '#/search', 'done']);
    expect(consts).not.toContain('core_search_index');
    expect(consts).not.toContain('inspector');
  });

  it('tokenizeMacroEffects replaces whole-value matches with {{token}}', () => {
    const out = tokenizeMacroEffects(
      [{ kind: 'invoke-operation', operationId: 'core_search_index', args: { query: 'budget 2025' } }],
      [{ value: 'budget 2025', paramName: 'q' }],
    );
    const eff = out[0]!;
    expect(eff.kind).toBe('invoke-operation');
    if (eff.kind === 'invoke-operation') expect(eff.args).toEqual({ query: '{{q}}' });
  });

  it('a non-blank parameter name tokenizes the macro + attaches a params schema; runMacro prompts', async () => {
    const p = defineMacroFromEffects([
      { kind: 'invoke-operation', operationId: 'core_search_index', args: { query: 'budget 2025' } },
    ]);
    await Promise.resolve();
    const reqs = listPendingElicits();
    expect(reqs).toHaveLength(1);
    // candidate constant 'budget 2025' → param_0; name it "q".
    resolveElicit(reqs[0]!.id, { label: 'Search anything', param_0: 'q' });
    const macro = await p;
    expect(macro).not.toBeNull();
    expect(macro!.params).toBeDefined();
    const eff = macro!.effects[0]!;
    if (eff.kind === 'invoke-operation') expect(eff.args).toEqual({ query: '{{q}}' });
    // Running it now prompts for q and substitutes.
    const invokes: Array<Record<string, unknown>> = [];
    document.addEventListener('jf-invoke-operation', (e) => invokes.push((e as CustomEvent).detail));
    const rp = runMacro(macro!.id, { allowBackendReplay: true });
    await Promise.resolve();
    resolveElicit(listPendingElicits()[0]!.id, { q: 'taxes 2026' });
    await rp;
    expect(invokes[0]!.args).toEqual({ query: 'taxes 2026' });
  });

  it('blank parameter names keep the macro literal (no params)', async () => {
    const p = defineMacroFromEffects([
      { kind: 'invoke-operation', operationId: 'core_search_index', args: { query: 'budget 2025' } },
    ]);
    await Promise.resolve();
    resolveElicit(listPendingElicits()[0]!.id, { label: 'Literal search', param_0: '' });
    const macro = await p;
    expect(macro!.params).toBeUndefined();
    const eff = macro!.effects[0]!;
    if (eff.kind === 'invoke-operation') expect(eff.args).toEqual({ query: 'budget 2025' });
  });
});
