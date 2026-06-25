/**
 * Tests for the generative (LLM) authoring origin (569 Move 7). The model call is injected;
 * the live model run is the final batch. Here we verify the grammar embeds the closed
 * vocabularies and the certify glue is correct.
 */
import { describe, expect, it } from 'vitest';
import {
  authorPresentationFromPrompt,
  type ConstrainedCompletion,
} from './authorPresentation.js';
import { describeConformanceError } from './conformanceGate.js';
import { presentationDeclarationJsonSchema } from './presentationSchema.js';

describe('Move 7 — constrained authoring', () => {
  it('the schema (grammar) embeds the closed token + authorable-component vocabularies', () => {
    const tokenEnum = presentationDeclarationJsonSchema.properties.theme.properties.tokens
      .propertyNames.enum as readonly string[];
    expect(tokenEnum).toContain('accent-tint');
    const compEnum = presentationDeclarationJsonSchema.properties.layout.properties.regions
      .items.properties.component.enum as readonly string[];
    expect(compEnum).toContain('jf-declared-surface');
    // reserved/chrome components are NOT authorable — the grammar can't emit them.
    expect(compEnum).not.toContain('jf-authorization-host');
  });

  it('certifies a valid model-authored declaration (the gate is the grammar)', async () => {
    const complete: ConstrainedCompletion = async () =>
      JSON.stringify({
        schemaVersion: 1,
        id: 'user.warm',
        displayName: 'Warm Terminal',
        theme: { tokens: { 'accent-tint': 'oklch(70% 0.12 40)' } },
      });
    const r = await authorPresentationFromPrompt('warm terminal vibe', complete);
    expect(r.verdict.ok).toBe(true);
    expect(r.declaration?.id).toBe('user.warm');
  });

  it('rejects non-JSON model output gracefully', async () => {
    const complete: ConstrainedCompletion = async () => 'sorry, here is some prose';
    const r = await authorPresentationFromPrompt('x', complete);
    expect(r.verdict.ok).toBe(false);
    expect(r.verdict.errors.map(describeConformanceError).join(' ')).toMatch(/valid JSON/);
  });

  it('passes the schema as response_format and the user prompt to the completion', async () => {
    const calls: { prompt: string; responseFormat: object }[] = [];
    const complete: ConstrainedCompletion = async (req) => {
      calls.push(req);
      return JSON.stringify({ schemaVersion: 1, id: 'user.x', displayName: 'X' });
    };
    await authorPresentationFromPrompt('cozy dark vibe', complete);
    expect(calls).toHaveLength(1);
    expect(calls[0]?.responseFormat).toBe(presentationDeclarationJsonSchema);
    expect(calls[0]?.prompt).toContain('cozy dark vibe');
  });

  it('self-repairs: feeds the structured verdict back and the second attempt certifies', async () => {
    const prompts: string[] = [];
    let attempt = 0;
    const complete: ConstrainedCompletion = async (req) => {
      prompts.push(req.prompt);
      attempt += 1;
      return attempt === 1
        ? JSON.stringify({
            schemaVersion: 1,
            id: 'user.r',
            displayName: 'R',
            theme: { tokens: { 'not-a-token': '#fff' } }, // unknown token → fails certify
          })
        : JSON.stringify({
            schemaVersion: 1,
            id: 'user.r',
            displayName: 'R',
            theme: { tokens: { 'accent-tint': 'oklch(65% 0.12 200)' } }, // valid
          });
    };
    const r = await authorPresentationFromPrompt('repair me', complete);
    expect(r.verdict.ok).toBe(true);
    expect(attempt).toBe(2); // one bounded repair
    expect(prompts[1]).toContain('REJECTED'); // the repair prompt carries the prior output
    expect(prompts[1]).toMatch(/known token/); // …and the structured reason
  });

  it('gives up after maxRepairs and returns the last failing verdict', async () => {
    let attempt = 0;
    const complete: ConstrainedCompletion = async () => {
      attempt += 1;
      return JSON.stringify({
        schemaVersion: 1,
        id: 'user.bad',
        displayName: 'Bad',
        theme: { tokens: { 'not-a-token': '#fff' } },
      });
    };
    const r = await authorPresentationFromPrompt('always bad', complete, { maxRepairs: 1 });
    expect(r.verdict.ok).toBe(false);
    expect(attempt).toBe(2); // initial + 1 repair, then give up
  });
});
