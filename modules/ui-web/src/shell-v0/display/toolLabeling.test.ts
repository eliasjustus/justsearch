import { describe, expect, it } from 'vitest';
import { composeToolLabel } from './toolLabeling.js';

describe('composeToolLabel (565 §12.3.B)', () => {
  it('extracts a search query as the target', () => {
    const { label, target } = composeToolLabel('core_search', '{"query":"discipline-gate kernel"}');
    expect(label).toBeTruthy();
    expect(target).toBe('discipline-gate kernel');
  });

  it('extracts a path target as its basename', () => {
    const { target } = composeToolLabel(
      'core_ingest_files',
      '{"path":"f:/justsearch/docs/discipline-gate-kernel.md"}',
    );
    expect(target).toBe('discipline-gate-kernel.md');
  });

  it('summarises an array target with a +N suffix', () => {
    const { target } = composeToolLabel('core_ingest_files', '{"paths":["a/one.md","b/two.md"]}');
    expect(target).toBe('one.md +1');
  });

  it('derives a readable label from the tool name (via the Display authority)', () => {
    const { label } = composeToolLabel('core_search_index', '{}');
    expect(label).toMatch(/search/i);
  });

  it('degrades to an empty target on malformed args', () => {
    expect(composeToolLabel('core_search', 'not json').target).toBe('');
    expect(composeToolLabel('core_search', undefined).target).toBe('');
    expect(composeToolLabel('core_search', null).target).toBe('');
  });

  it('falls back to a non-empty label for an empty tool name', () => {
    expect(composeToolLabel('', '{}').label).toBe('Tool');
  });
});

// Tempdoc 871 §3b (owner, 2026-08-26) — the verb form, so a tool row reads as the MODEL'S act.
describe('composeToolLabel().verbLabel (871 §3b)', () => {
  it('maps the four owner-settled verbs off a segment of the wire name', () => {
    expect(composeToolLabel('core_search_index', '{}').verbLabel).toBe('Searched');
    expect(composeToolLabel('core_read_document', '{}').verbLabel).toBe('Read');
    expect(composeToolLabel('core_browse_folders', '{}').verbLabel).toBe('Listed');
    expect(composeToolLabel('core_file_write', '{}').verbLabel).toBe('Write');
  });

  it('reads a segment out of a camelCase or dotted MCP tool name too', () => {
    expect(composeToolLabel('notes.search', '{}').verbLabel).toBe('Searched');
    expect(composeToolLabel('readDocument', '{}').verbLabel).toBe('Read');
  });

  // A WRITE renders while it is still awaiting approval, so the neutral form is the honest one
  // there; the read-only verbs only ever render as the record of something that already ran.
  it('keeps WRITE in the neutral form (it renders BEFORE it happened), the rest in past tense', () => {
    expect(composeToolLabel('core_file_write', '{}').verbLabel).not.toBe('Wrote');
  });

  it('falls back to the humanized label — never a guessed verb — for an unmapped tool', () => {
    const { label, verbLabel } = composeToolLabel('core_ingest_files', '{}');
    expect(verbLabel).toBe(label);
    expect(verbLabel).toBe('Ingest Files');
    expect(composeToolLabel('core_remember', '{}').verbLabel).toBe('Remember');
  });

  it('is never empty, even for an empty tool name', () => {
    expect(composeToolLabel('', '{}').verbLabel).toBe('Tool');
  });
});
