import { describe, it, expect } from 'vitest';
import { toolOutputLineage, lineageFrameLabel } from './toolOutputLineage.js';

describe('toolOutputLineage (tempdoc 577 §2.14 Root III #18)', () => {
  it('reads the backend-stamped lineage off structuredData', () => {
    expect(toolOutputLineage({ lineage: 'corpus-quoted' })).toBe('corpus-quoted');
    expect(toolOutputLineage({ lineage: 'agent-authored' })).toBe('agent-authored');
    expect(toolOutputLineage({ lineage: 'runtime' })).toBe('runtime');
  });

  it('defaults absent / unknown / no-structuredData to runtime (every output is framed)', () => {
    expect(toolOutputLineage(undefined)).toBe('runtime');
    expect(toolOutputLineage({})).toBe('runtime');
    expect(toolOutputLineage({ lineage: 'totally-made-up' })).toBe('runtime');
    expect(toolOutputLineage({ lineage: 42 as unknown as string })).toBe('runtime');
  });

  it('frames corpus-quoted as quoted, leaves runtime/agent-authored unframed', () => {
    expect(lineageFrameLabel('corpus-quoted')).toBe('Quoted from your documents');
    expect(lineageFrameLabel('runtime')).toBeNull();
    expect(lineageFrameLabel('agent-authored')).toBeNull();
  });
});
