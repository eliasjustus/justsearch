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
