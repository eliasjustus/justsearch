/**
 * Tempdoc 564 Phase 2 — FE-side faithfulness gate for the /api/status surface.
 *
 * StatusResponse is the second hard case (@JsonUnwrapped flattening, nullable-ref defs,
 * an anyOf nullable enum). The generated `statusResponseSchema` (record → JSON Schema → Zod)
 * must validate the real captured status wire fixture — proving the generality of the
 * pipeline beyond search.
 */
import { describe, it, expect } from 'vitest';

import { statusResponseSchema } from './status-response';
import statusFixture from '../../__fixtures__/status-response-live.json';

describe('generated statusResponseSchema (564 faithfulness)', () => {
  it('validates the real captured status wire fixture with no contract drift', () => {
    const result = statusResponseSchema.safeParse(statusFixture);
    if (!result.success) {
      throw new Error(
        'generated status schema rejected the real wire fixture: ' +
          JSON.stringify(result.error.issues, null, 2)
      );
    }
    expect(result.success).toBe(true);
  });
});
