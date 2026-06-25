/**
 * 569 Move 7 — the local constrained-completion factory + the generative authoring path.
 */
import { describe, it, expect, vi } from 'vitest';
import type { PluginHostApi } from '../plugin-api/plugin-types.js';
import { createLocalConstrainedCompletion, EXTRACT_SHAPE_ID } from './localCompletion.js';
import { authorPresentationFromPrompt } from './authorPresentation.js';
import { describeConformanceError } from './conformanceGate.js';

function fakeHost(reply: string, spy = vi.fn()): PluginHostApi {
  return {
    ai: {
      invokeShape: async (shapeId: string, body: Record<string, unknown>) => {
        spy(shapeId, body);
        return { text: reply, events: [] };
      },
    },
  } as unknown as PluginHostApi;
}

describe('createLocalConstrainedCompletion', () => {
  it('routes the prompt + schema through host.ai.invokeShape(core.extract) and returns the text', async () => {
    const spy = vi.fn();
    const complete = createLocalConstrainedCompletion(fakeHost('{"ok":true}', spy));
    const text = await complete({ prompt: 'warm dark theme', responseFormat: { a: 1 } });
    expect(text).toBe('{"ok":true}');
    expect(spy).toHaveBeenCalledWith(EXTRACT_SHAPE_ID, {
      prompt: 'warm dark theme',
      schema: '{"a":1}',
    });
  });
});

describe('authorPresentationFromPrompt (generative origin, end-to-end over the seam)', () => {
  it('certifies a model-emitted declaration into an applicable artifact', async () => {
    const decl = JSON.stringify({
      schemaVersion: 1,
      id: 'llm.warm-dark',
      displayName: 'Warm Dark',
      theme: { tokens: { 'accent-tint': 'oklch(60% 0.12 40)' } },
    });
    const result = await authorPresentationFromPrompt(
      'warm dark theme',
      createLocalConstrainedCompletion(fakeHost(decl)),
    );
    expect(result.declaration).not.toBeNull();
    expect(result.declaration?.id).toBe('llm.warm-dark');
  });

  it('LIVE: the real on-device model output (Llama-3.1-8B, cuda12) certifies + applies', async () => {
    // Captured verbatim from a live dev-stack run: agent_chat → this JSON. Proves the generative
    // origin end-to-end with the real model — schema-shaped output → certify → an applicable artifact.
    const liveModelOutput =
      '{"schemaVersion":1,"id":"llm.warm-dark","displayName":"Warm Dark Theme",' +
      '"theme":{"tokens":{"text-primary":"#f7f0c8","surface-1":"#3b2f1a","surface-2":"#3a2c23","accent-tint":"#ff9900"}}}';
    const result = await authorPresentationFromPrompt(
      'warm dark theme',
      createLocalConstrainedCompletion(fakeHost(liveModelOutput)),
    );
    expect(result.declaration).not.toBeNull();
    expect(result.verdict.ok).toBe(true); // certified — every token is in the closed vocab, contrast OK
    expect(result.declaration?.theme?.tokens['accent-tint']).toBe('#ff9900');
  });

  it('rejects model output that escapes the closed vocabulary (the gate is the floor)', async () => {
    const bad = JSON.stringify({
      schemaVersion: 1,
      id: 'llm.evil',
      displayName: 'Evil',
      theme: { tokens: { 'not-a-token': '#000' } },
    });
    const result = await authorPresentationFromPrompt(
      'evil',
      createLocalConstrainedCompletion(fakeHost(bad)),
    );
    expect(result.declaration).toBeNull();
    expect(result.verdict.errors.map(describeConformanceError).join(' ')).toMatch(/not a known token/);
  });
});
