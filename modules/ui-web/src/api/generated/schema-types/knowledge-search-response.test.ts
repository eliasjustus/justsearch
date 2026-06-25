/**
 * Tempdoc 564 Phase 1 — FE-side record↔schema↔wire faithfulness gate.
 *
 * The generated `knowledgeSearchResponseSchema` (record → JSON Schema → Zod) must validate
 * the real captured wire fixture. This is the FE mirror of the Java
 * `KnowledgeSearchResponseSchemaTest.liveFixtureConforms` — together they prove the one
 * schema faithfully describes the JSON both sides exchange.
 */
import { describe, it, expect } from 'vitest';

import { knowledgeSearchResponseSchema } from './knowledge-search-response';
import searchFixture from '../../__fixtures__/search-response-live.json';

describe('generated knowledgeSearchResponseSchema (564 faithfulness)', () => {
  it('validates the real captured search wire fixture with no contract drift', () => {
    const result = knowledgeSearchResponseSchema.safeParse(searchFixture);
    if (!result.success) {
      // Surface the precise mismatch path(s) on failure.
      throw new Error(
        'generated schema rejected the real wire fixture: ' +
          JSON.stringify(result.error.issues, null, 2)
      );
    }
    expect(result.success).toBe(true);
  });

  it('types the facets map-of-map faithfully (the proto3-impossible shape)', () => {
    const parsed = knowledgeSearchResponseSchema.parse(searchFixture);
    // facets is Record<string, Record<string, number>> — a bare nested map, no wrapper.
    if (parsed.facets) {
      for (const byValue of Object.values(parsed.facets)) {
        for (const count of Object.values(byValue)) {
          expect(typeof count).toBe('number');
        }
      }
    }
  });
});
