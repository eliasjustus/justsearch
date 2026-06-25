/**
 * stateValidator tests — slice 489 §7 stage 2.
 *
 * Validates that the lightweight type-validator correctly:
 *  - accepts well-formed values per the declared schema type
 *  - rejects non-parseable values for typed fields
 *  - drops keys not declared in the schema
 *  - errors on required-but-absent keys
 *  - handles array values element-wise
 *  - returns state as-is when the schema is missing or malformed
 */

import { describe, expect, it } from 'vitest';
import { coerceAndValidate } from './stateValidator.js';

const SAMPLE_SCHEMA = JSON.stringify({
  type: 'object',
  properties: {
    query: { type: 'string' },
    modifiedFromMs: { type: 'integer' },
    modifiedToMs: { type: 'integer' },
    page: { type: 'number' },
    enabled: { type: 'boolean' },
  },
});

describe('coerceAndValidate — accepting valid values', () => {
  it('accepts well-formed string', () => {
    const r = coerceAndValidate({ query: 'rust' }, SAMPLE_SCHEMA);
    expect(r).toEqual({ ok: true, value: { query: 'rust' } });
  });

  it('accepts integer-parseable string', () => {
    const r = coerceAndValidate({ modifiedFromMs: '12345' }, SAMPLE_SCHEMA);
    expect(r).toEqual({ ok: true, value: { modifiedFromMs: '12345' } });
  });

  it('accepts number-parseable string with decimal for number type', () => {
    const r = coerceAndValidate({ page: '2.5' }, SAMPLE_SCHEMA);
    expect(r.ok).toBe(true);
  });

  it('accepts boolean-parseable strings (case-insensitive)', () => {
    for (const v of ['true', 'false', 'True', 'YES', '0', '1', 'no']) {
      const r = coerceAndValidate({ enabled: v }, SAMPLE_SCHEMA);
      expect(r.ok, `value ${v} should be accepted`).toBe(true);
    }
  });
});

describe('coerceAndValidate — rejecting bad values', () => {
  it('rejects non-numeric string for integer field', () => {
    const r = coerceAndValidate({ modifiedFromMs: 'banana' }, SAMPLE_SCHEMA);
    expect(r.ok).toBe(false);
    if (!r.ok) {
      expect(r.errors[0]).toContain('modifiedFromMs');
      expect(r.errors[0]).toContain('integer');
    }
  });

  it('rejects non-integer string for integer field (decimal)', () => {
    const r = coerceAndValidate({ modifiedFromMs: '2.5' }, SAMPLE_SCHEMA);
    expect(r.ok).toBe(false);
    if (!r.ok) {
      expect(r.errors[0]).toContain('non-integer');
    }
  });

  it('rejects non-boolean-shaped string for boolean field', () => {
    const r = coerceAndValidate({ enabled: 'maybe' }, SAMPLE_SCHEMA);
    expect(r.ok).toBe(false);
    if (!r.ok) {
      expect(r.errors[0]).toContain('enabled');
      expect(r.errors[0]).toContain('boolean');
    }
  });

  it('reports multiple errors from a single state', () => {
    const r = coerceAndValidate(
      { modifiedFromMs: 'banana', enabled: 'maybe' },
      SAMPLE_SCHEMA,
    );
    expect(r.ok).toBe(false);
    if (!r.ok) {
      expect(r.errors).toHaveLength(2);
    }
  });
});

describe('coerceAndValidate — schema-driven filtering', () => {
  it('drops keys not in schema.properties', () => {
    const r = coerceAndValidate(
      { query: 'rust', unknown_key: 'leaked' },
      SAMPLE_SCHEMA,
    );
    expect(r).toEqual({ ok: true, value: { query: 'rust' } });
  });

  it('absent non-required keys do not appear in result', () => {
    const r = coerceAndValidate({ query: 'rust' }, SAMPLE_SCHEMA);
    expect(r.ok).toBe(true);
    if (r.ok) {
      expect(Object.keys(r.value)).toEqual(['query']);
    }
  });

  it('errors on required-but-absent key', () => {
    const schemaWithRequired = JSON.stringify({
      type: 'object',
      properties: {
        query: { type: 'string' },
        topK: { type: 'integer' },
      },
      required: ['query'],
    });
    const r = coerceAndValidate({ topK: '10' }, schemaWithRequired);
    expect(r.ok).toBe(false);
    if (!r.ok) {
      expect(r.errors[0]).toContain('required key "query"');
    }
  });
});

describe('coerceAndValidate — array values (URL repeated-key form)', () => {
  it('accepts arrays whose elements all parse as declared type', () => {
    const schema = JSON.stringify({
      type: 'object',
      properties: { ids: { type: 'integer' } },
    });
    const r = coerceAndValidate({ ids: ['1', '2', '3'] }, schema);
    expect(r.ok).toBe(true);
    if (r.ok) {
      expect(r.value.ids).toEqual(['1', '2', '3']);
    }
  });

  it('rejects array when any element fails type check', () => {
    const schema = JSON.stringify({
      type: 'object',
      properties: { ids: { type: 'integer' } },
    });
    const r = coerceAndValidate({ ids: ['1', 'banana', '3'] }, schema);
    expect(r.ok).toBe(false);
  });
});

describe('coerceAndValidate — degraded inputs', () => {
  it('returns state as-is for malformed schema source', () => {
    const r = coerceAndValidate({ foo: 'bar' }, '{not json');
    expect(r).toEqual({ ok: true, value: { foo: 'bar' } });
  });

  it('returns state as-is when schema has no properties', () => {
    const r = coerceAndValidate({ foo: 'bar' }, JSON.stringify({ type: 'object' }));
    expect(r).toEqual({ ok: true, value: { foo: 'bar' } });
  });

  it('empty state is valid against any schema', () => {
    const r = coerceAndValidate({}, SAMPLE_SCHEMA);
    expect(r).toEqual({ ok: true, value: {} });
  });
});
