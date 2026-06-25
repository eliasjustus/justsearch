// @vitest-environment happy-dom

/**
 * Tempdoc 508 §11.7 / §13.7 — TemplateCatalog tests.
 */

import { describe, it, expect, beforeEach, vi } from 'vitest';
import {
  parseTemplate,
  registerTemplate,
  unregisterTemplate,
  listTemplates,
  expandTemplate,
  TemplateValidationError,
  TEMPLATE_SLOT_HARD_CAP,
  TEMPLATE_SLOT_SOFT_CAP,
  __resetTemplateCatalogForTest,
} from './TemplateCatalog.js';
import {
  setSingleSelection,
  __resetSelectionForTest,
} from '../state/selectionState.js';
import { CORE_PROVENANCE, makePluginProvenance } from '../primitives/provenance.js';
const PLUGIN_PROVENANCE = makePluginProvenance('test-plugin', '1.0.0', 'TRUSTED_PLUGIN');

beforeEach(() => {
  __resetTemplateCatalogForTest();
  __resetSelectionForTest();
});

describe('parseTemplate — slot grammar', () => {
  it('parses a literal-only template (no slots)', () => {
    const p = parseTemplate('plain text');
    expect(p.slots).toEqual([]);
    expect(p.segments).toEqual([{ kind: 'text', value: 'plain text' }]);
  });

  it('parses a single named argument', () => {
    const p = parseTemplate('search for {argument name="topic"}');
    expect(p.slots).toHaveLength(1);
    expect(p.slots[0]).toEqual({ kind: 'argument', name: 'topic' });
  });

  it('parses argument with default', () => {
    const p = parseTemplate('{argument name="topic" default="recent"}');
    expect(p.slots[0]).toEqual({ kind: 'argument', name: 'topic', default: 'recent' });
  });

  it('parses ambient bindings', () => {
    const p = parseTemplate('{clipboard} → {date} via {currentSurface}');
    expect(p.slots).toEqual([
      { kind: 'ambient', binding: 'clipboard' },
      { kind: 'ambient', binding: 'date' },
      { kind: 'ambient', binding: 'currentSurface' },
    ]);
  });

  it('escapes literal { and } with {{ and }}', () => {
    const p = parseTemplate('object: {{ key: {argument name="v"} }}');
    expect(p.slots).toHaveLength(1);
    // Segments interleave text + slot.
    const stitched = p.segments.map((s) => (s.kind === 'text' ? s.value : '<SLOT>')).join('');
    expect(stitched).toBe('object: { key: <SLOT> }');
  });

  it('rejects unknown ambient binding', () => {
    expect(() => parseTemplate('{unknown}')).toThrow(TemplateValidationError);
  });

  it('rejects argument without name', () => {
    expect(() => parseTemplate('{argument default="x"}')).toThrow(TemplateValidationError);
  });

  it('rejects unterminated slot', () => {
    expect(() => parseTemplate('hello {argument name="x"')).toThrow(TemplateValidationError);
  });

  it('rejects above-hard-cap slot count', () => {
    const big = Array.from({ length: TEMPLATE_SLOT_HARD_CAP + 1 }, (_, i) => `{argument name="a${i}"}`).join(' ');
    expect(() => parseTemplate(big)).toThrow(/hard cap/);
  });
});

describe('registerTemplate — projection + trust attenuation', () => {
  it('appears as an Action after register (§28.W10 — templates project into Action substrate)', async () => {
    const { getAction } = await import('../substrates/actions/index.js');
    registerTemplate({
      id: 'search-recent',
      label: 'Search Recent',
      source: 'core',
      provenance: CORE_PROVENANCE,
      template: 'recent {argument name="topic"}',
      onInvoke: vi.fn(),
    });
    expect(getAction('core.action.template.search-recent')).toBeDefined();
    expect(listTemplates()).toHaveLength(1);
  });

  it('disappears after unregister', async () => {
    const { getAction } = await import('../substrates/actions/index.js');
    registerTemplate({
      id: 'one',
      label: 'One',
      source: 'core',
      provenance: CORE_PROVENANCE,
      template: 'plain',
      onInvoke: vi.fn(),
    });
    unregisterTemplate('one');
    expect(listTemplates()).toHaveLength(0);
    expect(getAction('core.action.template.one')).toBeUndefined();
  });

  it('UNTRUSTED templates cannot bind {selection}', () => {
    expect(() =>
      registerTemplate({
        id: 'naughty',
        label: 'Steal selection',
        source: 'plugin',
        provenance: PLUGIN_PROVENANCE,
        trustTier: 'UNTRUSTED_PLUGIN',
        template: 'sneaky {selection}',
        onInvoke: vi.fn(),
      }),
    ).toThrow(/UNTRUSTED.*selection/);
  });

  it('UNTRUSTED templates cannot bind {clipboard}', () => {
    expect(() =>
      registerTemplate({
        id: 'naughty2',
        label: 'X',
        source: 'plugin',
        provenance: PLUGIN_PROVENANCE,
        trustTier: 'UNTRUSTED_PLUGIN',
        template: 'send {clipboard}',
        onInvoke: vi.fn(),
      }),
    ).toThrow(/UNTRUSTED.*clipboard/);
  });

  it('TRUSTED_PLUGIN templates can bind {selection}', () => {
    expect(() =>
      registerTemplate({
        id: 'ok-trusted',
        label: 'OK',
        source: 'plugin',
        provenance: PLUGIN_PROVENANCE,
        trustTier: 'TRUSTED_PLUGIN',
        template: 'about {selection}',
        onInvoke: vi.fn(),
      }),
    ).not.toThrow();
  });

  it('warns above soft cap but registers', () => {
    const spy = vi.spyOn(console, 'warn').mockImplementation(() => {});
    try {
      const big = Array.from({ length: TEMPLATE_SLOT_SOFT_CAP + 1 }, (_, i) => `{argument name="a${i}"}`).join(' ');
      registerTemplate({
        id: 'big',
        label: 'Big',
        source: 'core',
        provenance: CORE_PROVENANCE,
        template: big,
        onInvoke: vi.fn(),
      });
      expect(spy).toHaveBeenCalledWith(expect.stringContaining('soft cap'));
    } finally {
      spy.mockRestore();
    }
  });
});

describe('expandTemplate — slot resolution', () => {
  it('expands argument slots via promptFn', async () => {
    const p = parseTemplate('q={argument name="topic"} mode={argument name="mode"}');
    const out = await expandTemplate(p, 'test', async (slot) => {
      if (slot.name === 'topic') return 'cats';
      if (slot.name === 'mode') return 'recent';
      return '';
    });
    expect(out).toBe('q=cats mode=recent');
  });

  it('returns null if any prompt is cancelled', async () => {
    const p = parseTemplate('{argument name="a"} and {argument name="b"}');
    const out = await expandTemplate(p, 'test', async (slot) => {
      if (slot.name === 'a') return 'X';
      return null;  // cancel
    });
    expect(out).toBeNull();
  });

  it('expands {selection} from the live selection state', async () => {
    setSingleSelection({
      kind: 'search-hit',
      hitId: 'h1',
      title: 'My Doc',
      path: '/x',
      capabilities: new Set(),
    }, null);
    const p = parseTemplate('Discuss {selection}');
    const out = await expandTemplate(p, 'test');
    expect(out).toBe('Discuss My Doc');
  });

  it('expands {primarySelection} for multi-item selection', async () => {
    setSingleSelection({
      kind: 'search-hit',
      hitId: 'h1',
      title: 'Primary',
      path: '/x',
      capabilities: new Set(),
    }, null);
    const p = parseTemplate('Focus: {primarySelection}');
    const out = await expandTemplate(p, 'test');
    expect(out).toBe('Focus: Primary');
  });

  it('expands {date} as YYYY-MM-DD', async () => {
    const p = parseTemplate('today is {date}');
    const out = await expandTemplate(p, 'test');
    expect(out).toMatch(/today is \d{4}-\d{2}-\d{2}/);
  });
});

describe('setSlotPromptProvider (tempdoc 508-followup §δ1)', () => {
  it('expandTemplate routes through the registered provider', async () => {
    const provider = vi.fn(async (slot) => `from-provider:${slot.name}`);
    const p = parseTemplate('q={argument name="topic"}');
    const out = await expandTemplate(p, 'tpl', provider);
    expect(out).toBe('q=from-provider:topic');
    expect(provider).toHaveBeenCalled();
  });

  it('cancellation from provider returns null', async () => {
    const provider = vi.fn(async () => null);
    const p = parseTemplate('q={argument name="topic"}');
    const out = await expandTemplate(p, 'tpl', provider);
    expect(out).toBeNull();
  });

  it('multiple slots prompt in declaration order', async () => {
    const calls: string[] = [];
    const provider = vi.fn(async (slot) => {
      calls.push(slot.name);
      return slot.name + '-val';
    });
    const p = parseTemplate('{argument name="a"} and {argument name="b"}');
    const out = await expandTemplate(p, 'tpl', provider);
    expect(calls).toEqual(['a', 'b']);
    expect(out).toBe('a-val and b-val');
  });
});

describe('compute slot — Raycast inter-slot reference form (tempdoc 521 §16.2 deeper)', () => {
  it('parses {compute:<expr>} into a ComputeSlot', () => {
    const p = parseTemplate('= {compute:2 + 3}');
    expect(p.slots).toHaveLength(1);
    expect(p.slots[0]).toEqual({ kind: 'compute', expression: '2 + 3' });
  });

  it('parses compute with embedded {name} references (depth-aware end)', () => {
    const p = parseTemplate('last {argument name="n"} days = {compute:{n} * 7}');
    expect(p.slots).toHaveLength(2);
    expect(p.slots[1]).toEqual({ kind: 'compute', expression: '{n} * 7' });
  });

  it('expands compute by substituting argument values then evaluating', async () => {
    const p = parseTemplate(
      '{argument name="n"} days = last {compute:{n} * 7} days',
    );
    const provider = vi.fn(async () => '3');
    const out = await expandTemplate(p, 'tpl', provider);
    expect(out).toBe('3 days = last 21 days');
  });

  it('rejects compute referencing a missing argument name at register', () => {
    expect(() => {
      registerTemplate({
        id: 'bad-ref',
        label: 'bad',
        source: 'core',
        provenance: CORE_PROVENANCE,
        template: 'x = {compute:{missing} * 2}',
        onInvoke: () => {},
      });
    }).toThrow(/no argument or ambient slot with that name exists/i);
  });

  it('rejects multiple compute slots in one template (V1 cap = 1)', () => {
    expect(() => {
      registerTemplate({
        id: 'two-computes',
        label: 'two',
        source: 'core',
        provenance: CORE_PROVENANCE,
        template: '{argument name="a"} {compute:{a} + 1} {compute:{a} * 2}',
        onInvoke: () => {},
      });
    }).toThrow(/V1 cap is 1/);
  });

  it('rejects empty compute expression', () => {
    expect(() => parseTemplate('{compute:}')).toThrow(/non-empty/);
  });

  it('UNTRUSTED templates cannot register a compute slot', () => {
    expect(() => {
      registerTemplate({
        id: 'untrusted-compute',
        label: 'x',
        source: 'plugin',
        provenance: PLUGIN_PROVENANCE,
        trustTier: 'UNTRUSTED_PLUGIN',
        template: '{argument name="n"} → {compute:{n} + 1}',
        onInvoke: () => {},
      });
    }).toThrow(/UNTRUSTED.*compute/);
  });

  it('non-numeric argument input yields empty compute value (graceful)', async () => {
    const p = parseTemplate(
      '{argument name="n"} result: {compute:{n} + 1}',
    );
    const provider = vi.fn(async () => 'not-a-number');
    const out = await expandTemplate(p, 'tpl', provider);
    // Substituted "not-a-number + 1" fails to tokenize → empty string per
    // the same fallback used by the simple-form calculator binding.
    expect(out).toBe('not-a-number result: ');
  });

  it('compute slot with no references just evaluates a literal expression', async () => {
    const p = parseTemplate('= {compute:(2 + 3) * 4}');
    const out = await expandTemplate(p, 'tpl', () => Promise.resolve(''));
    expect(out).toBe('= 20');
  });

  // Tempdoc 521 §22 Phase B — compute slots can reference ambient
  // bindings declared in the same template, not just argument slots.
  it('accepts compute referencing an ambient binding name', () => {
    expect(() =>
      registerTemplate({
        id: 'compute-ambient-ref',
        label: 'demo',
        source: 'core',
        provenance: CORE_PROVENANCE,
        // {datetime} resolves to an ISO string; the compute substitution
        // produces a non-numeric expression at expand time, which fails
        // to tokenize gracefully (test below). Registration succeeds
        // because `datetime` is a resolvable name in this template.
        template: '{datetime} → {compute:{datetime}}',
        onInvoke: () => {},
      }),
    ).not.toThrow();
  });

  it('rejects compute referencing an ambient binding NOT in this template', () => {
    expect(() =>
      registerTemplate({
        id: 'compute-undeclared-ambient',
        label: 'bad',
        source: 'core',
        provenance: CORE_PROVENANCE,
        template: '{compute:{clipboard} * 2}',
        onInvoke: () => {},
      }),
    ).toThrow(/no argument or ambient slot/i);
  });

  it('non-numeric ambient substitution yields empty compute (graceful)', async () => {
    const p = parseTemplate('{datetime} → {compute:{datetime}}');
    const out = await expandTemplate(p, 'tpl', () => Promise.resolve(''));
    // {datetime} substitutes into compute as an ISO string; safeMath
    // can't tokenize it; compute resolves to empty per the same fallback
    // simple-form calculator uses.
    expect(out).toMatch(/ → $/);
  });
});
