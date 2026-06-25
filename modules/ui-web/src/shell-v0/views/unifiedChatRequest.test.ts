import { describe, it, expect } from 'vitest';
import { buildRequestBody, SHAPE_LABELS } from './unifiedChatRequest.js';
import type { SelectionPayload } from '../../api/types/selection.js';

describe('buildRequestBody (tempdoc 621 Phase 2 — extracted request shaping)', () => {
  it('rag-ask carries `question` + `docIds` (scoped retrieval), not `prompt`', () => {
    const body = buildRequestBody('core.rag-ask', 'what is X', 's1', '', ['d1', 'd2']);
    expect(body).toMatchObject({ shapeId: 'core.rag-ask', question: 'what is X', docIds: ['d1', 'd2'] });
    expect(body.prompt).toBeUndefined();
    expect(body.sessionId).toBeUndefined();
  });

  it('extract carries `prompt` + `schema`', () => {
    const body = buildRequestBody('core.extract', 'pull fields', 's1', '{"a":1}', []);
    expect(body).toMatchObject({ shapeId: 'core.extract', prompt: 'pull fields', schema: '{"a":1}' });
  });

  it('free-chat / agent-run carry `prompt` + `sessionId`', () => {
    for (const shape of ['core.free-chat', 'core.agent-run'] as const) {
      const body = buildRequestBody(shape, 'hi', 'sess-9', '', []);
      expect(body).toMatchObject({ shapeId: shape, prompt: 'hi', sessionId: 'sess-9' });
      expect(body.question).toBeUndefined();
    }
  });

  it('forwards a typed selection verbatim when present, omits it otherwise', () => {
    const sel = { kind: 'text-range', text: 'hello' } as unknown as SelectionPayload;
    expect(buildRequestBody('core.free-chat', 't', 's', '', [], sel).selection).toBe(sel);
    expect(buildRequestBody('core.free-chat', 't', 's', '', [], null).selection).toBeUndefined();
    expect('selection' in buildRequestBody('core.free-chat', 't', 's', '', [])).toBe(false);
  });

  it('SHAPE_LABELS covers every interaction shape', () => {
    expect(Object.keys(SHAPE_LABELS).sort()).toEqual(
      ['core.agent-run', 'core.extract', 'core.free-chat', 'core.rag-ask', 'core.workflow-run'].sort(),
    );
  });
});
