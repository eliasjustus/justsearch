/**
 * Tester tests for the slice 3a.0 control renderers.
 *
 * Per the existing modules/ui-web vitest config (node environment),
 * these tests cover only the pure-function testers — the rank-
 * decision logic that maps `(uischema, schema)` to a renderer.
 *
 * Render tests (DOM mounting + reactive-property updates) defer
 * until a later phase adds happy-dom / jsdom to the test
 * environment. The testers ARE the registry's contract: when a
 * tester wins, the host instantiates the matching custom element.
 * Tester correctness is the primary defect surface.
 */

import { describe, expect, it } from 'vitest';
import type { JsonSchema, UISchemaElement } from '@jsonforms/core';
import {
  RANK_BASIC_CONTROL,
  RANK_SPECIALIZED_CONTROL,
  RANK_STRUCTURAL_CONTROL,
  dispatchRenderer,
} from '../registry.js';
import { textControlTester } from './TextControl.js';
import { numberControlTester } from './NumberControl.js';
import { booleanControlTester } from './BooleanControl.js';
import { enumControlTester } from './EnumControl.js';
import { dateControlTester } from './DateControl.js';
import { timeControlTester } from './TimeControl.js';
import { objectControlTester } from './ObjectControl.js';
import { arrayControlTester } from './ArrayControl.js';

const CONTROL_UISCHEMA: UISchemaElement = {
  type: 'Control',
  scope: '#/properties/x',
} as UISchemaElement;

const NON_CONTROL_UISCHEMA: UISchemaElement = {
  type: 'VerticalLayout',
} as UISchemaElement;

describe('textControlTester', () => {
  it('matches type:string with no format', () => {
    const schema: JsonSchema = { type: 'string' };
    expect(textControlTester(CONTROL_UISCHEMA, schema)).toBe(RANK_BASIC_CONTROL);
  });

  it('does NOT match type:string with format (defers to specialized)', () => {
    const schema: JsonSchema = { type: 'string', format: 'date' } as JsonSchema;
    expect(textControlTester(CONTROL_UISCHEMA, schema)).toBe(-1);
  });

  it('does NOT match non-string types', () => {
    expect(textControlTester(CONTROL_UISCHEMA, { type: 'number' })).toBe(-1);
    expect(textControlTester(CONTROL_UISCHEMA, { type: 'boolean' })).toBe(-1);
  });

  it('does NOT match non-Control uischema', () => {
    expect(textControlTester(NON_CONTROL_UISCHEMA, { type: 'string' })).toBe(-1);
  });
});

describe('numberControlTester', () => {
  it('matches type:number', () => {
    expect(numberControlTester(CONTROL_UISCHEMA, { type: 'number' })).toBe(
      RANK_BASIC_CONTROL,
    );
  });

  it('matches type:integer', () => {
    expect(numberControlTester(CONTROL_UISCHEMA, { type: 'integer' })).toBe(
      RANK_BASIC_CONTROL,
    );
  });

  it('does NOT match non-numeric types', () => {
    expect(numberControlTester(CONTROL_UISCHEMA, { type: 'string' })).toBe(-1);
    expect(numberControlTester(CONTROL_UISCHEMA, { type: 'boolean' })).toBe(-1);
  });

  it('does NOT match non-Control uischema', () => {
    expect(numberControlTester(NON_CONTROL_UISCHEMA, { type: 'number' })).toBe(-1);
  });
});

describe('booleanControlTester', () => {
  it('matches type:boolean', () => {
    expect(booleanControlTester(CONTROL_UISCHEMA, { type: 'boolean' })).toBe(
      RANK_BASIC_CONTROL,
    );
  });

  it('does NOT match non-boolean types', () => {
    expect(booleanControlTester(CONTROL_UISCHEMA, { type: 'string' })).toBe(-1);
    expect(booleanControlTester(CONTROL_UISCHEMA, { type: 'number' })).toBe(-1);
  });

  it('does NOT match non-Control uischema', () => {
    expect(booleanControlTester(NON_CONTROL_UISCHEMA, { type: 'boolean' })).toBe(-1);
  });
});

describe('enumControlTester', () => {
  it('matches schemas with non-empty enum array (any underlying type)', () => {
    const stringEnum: JsonSchema = { type: 'string', enum: ['a', 'b', 'c'] };
    expect(enumControlTester(CONTROL_UISCHEMA, stringEnum)).toBe(
      RANK_SPECIALIZED_CONTROL,
    );

    const numericEnum: JsonSchema = { type: 'number', enum: [1, 2, 3] };
    expect(enumControlTester(CONTROL_UISCHEMA, numericEnum)).toBe(
      RANK_SPECIALIZED_CONTROL,
    );
  });

  it('does NOT match schemas without enum', () => {
    expect(enumControlTester(CONTROL_UISCHEMA, { type: 'string' })).toBe(-1);
  });

  it('does NOT match schemas with empty enum array', () => {
    expect(enumControlTester(CONTROL_UISCHEMA, { type: 'string', enum: [] })).toBe(
      -1,
    );
  });

  it('outranks the basic string tester (specialized > basic)', () => {
    const enumSchema: JsonSchema = { type: 'string', enum: ['a', 'b'] };
    const enumRank = enumControlTester(CONTROL_UISCHEMA, enumSchema);
    const textRank = textControlTester(CONTROL_UISCHEMA, enumSchema);
    expect(enumRank).toBeGreaterThan(textRank);
    // Concretely: 2 > 1.
    expect(enumRank).toBe(RANK_SPECIALIZED_CONTROL);
    expect(textRank).toBe(RANK_BASIC_CONTROL);
  });

  it('does NOT match non-Control uischema', () => {
    expect(enumControlTester(NON_CONTROL_UISCHEMA, { enum: ['a'] })).toBe(-1);
  });
});

describe('dateControlTester', () => {
  it('matches type:string with format:date', () => {
    const schema: JsonSchema = { type: 'string', format: 'date' } as JsonSchema;
    expect(dateControlTester(CONTROL_UISCHEMA, schema)).toBe(
      RANK_SPECIALIZED_CONTROL,
    );
  });

  it('does NOT match type:string without format:date', () => {
    expect(dateControlTester(CONTROL_UISCHEMA, { type: 'string' })).toBe(-1);
    expect(
      dateControlTester(CONTROL_UISCHEMA, {
        type: 'string',
        format: 'time',
      } as JsonSchema),
    ).toBe(-1);
  });

  it('outranks the basic text tester for date schemas', () => {
    const schema: JsonSchema = { type: 'string', format: 'date' } as JsonSchema;
    expect(dateControlTester(CONTROL_UISCHEMA, schema)).toBeGreaterThan(
      textControlTester(CONTROL_UISCHEMA, schema),
    );
  });

  it('does NOT match non-Control uischema', () => {
    expect(
      dateControlTester(NON_CONTROL_UISCHEMA, {
        type: 'string',
        format: 'date',
      } as JsonSchema),
    ).toBe(-1);
  });
});

describe('timeControlTester', () => {
  it('matches type:string with format:time', () => {
    const schema: JsonSchema = { type: 'string', format: 'time' } as JsonSchema;
    expect(timeControlTester(CONTROL_UISCHEMA, schema)).toBe(
      RANK_SPECIALIZED_CONTROL,
    );
  });

  it('does NOT match type:string with format:date', () => {
    expect(
      timeControlTester(CONTROL_UISCHEMA, {
        type: 'string',
        format: 'date',
      } as JsonSchema),
    ).toBe(-1);
  });

  it('does NOT match non-Control uischema', () => {
    expect(
      timeControlTester(NON_CONTROL_UISCHEMA, {
        type: 'string',
        format: 'time',
      } as JsonSchema),
    ).toBe(-1);
  });
});

describe('objectControlTester', () => {
  it('matches type:object', () => {
    expect(
      objectControlTester(CONTROL_UISCHEMA, {
        type: 'object',
        properties: {},
      }),
    ).toBe(RANK_STRUCTURAL_CONTROL);
  });

  it('does NOT match non-object types', () => {
    expect(objectControlTester(CONTROL_UISCHEMA, { type: 'string' })).toBe(-1);
    expect(objectControlTester(CONTROL_UISCHEMA, { type: 'array' })).toBe(-1);
  });

  it('does NOT match non-Control uischema', () => {
    expect(objectControlTester(NON_CONTROL_UISCHEMA, { type: 'object' })).toBe(
      -1,
    );
  });
});

describe('arrayControlTester', () => {
  it('matches type:array', () => {
    expect(
      arrayControlTester(CONTROL_UISCHEMA, {
        type: 'array',
        items: { type: 'string' },
      } as JsonSchema),
    ).toBe(RANK_STRUCTURAL_CONTROL);
  });

  it('does NOT match non-array types', () => {
    expect(arrayControlTester(CONTROL_UISCHEMA, { type: 'object' })).toBe(-1);
    expect(arrayControlTester(CONTROL_UISCHEMA, { type: 'string' })).toBe(-1);
  });

  it('does NOT match non-Control uischema', () => {
    expect(arrayControlTester(NON_CONTROL_UISCHEMA, { type: 'array' })).toBe(-1);
  });
});

describe('dispatchRenderer', () => {
  it('selects highest-ranked renderer for string with enum', () => {
    const schema: JsonSchema = { type: 'string', enum: ['a', 'b', 'c'] };
    expect(dispatchRenderer(CONTROL_UISCHEMA, schema)).toBe('jf-enum-control');
  });

  it('selects basic text for plain string schema', () => {
    expect(dispatchRenderer(CONTROL_UISCHEMA, { type: 'string' })).toBe(
      'jf-text-control',
    );
  });

  it('selects date control for type:string format:date', () => {
    expect(
      dispatchRenderer(CONTROL_UISCHEMA, {
        type: 'string',
        format: 'date',
      } as JsonSchema),
    ).toBe('jf-date-control');
  });

  it('selects object control for nested objects', () => {
    expect(
      dispatchRenderer(CONTROL_UISCHEMA, {
        type: 'object',
        properties: { x: { type: 'string' } },
      }),
    ).toBe('jf-object-control');
  });

  it('selects array control for arrays', () => {
    expect(
      dispatchRenderer(CONTROL_UISCHEMA, {
        type: 'array',
        items: { type: 'string' },
      } as JsonSchema),
    ).toBe('jf-array-control');
  });

  it('returns null for non-matching uischema', () => {
    // No renderer registered for "UnknownLayout"; dispatch returns null.
    expect(
      dispatchRenderer({ type: 'UnknownLayout' } as UISchemaElement, {
        type: 'string',
      }),
    ).toBeNull();
  });
});

describe('rendererRegistry exports (control side)', () => {
  it('contains the 8 Phase-2 + Phase-3 control renderers', async () => {
    const { rendererRegistry } = await import('../registry.js');
    const tags = rendererRegistry.map((e) => e.tag);
    expect(tags).toContain('jf-text-control');
    expect(tags).toContain('jf-number-control');
    expect(tags).toContain('jf-boolean-control');
    expect(tags).toContain('jf-enum-control');
    expect(tags).toContain('jf-date-control');
    expect(tags).toContain('jf-time-control');
    expect(tags).toContain('jf-object-control');
    expect(tags).toContain('jf-array-control');
  });
});
