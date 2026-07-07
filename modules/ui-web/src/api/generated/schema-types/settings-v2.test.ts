/**
 * Tempdoc 683 (X3) — FE-side faithfulness gate for the /api/settings/v2 surface.
 *
 * The generated `settingsV2Schema` (record → JSON Schema → Zod) must validate the
 * real captured settings wire fixture. Strict (no `.loose()` fail-open): contract
 * drift fails here instead of passing silently.
 */
import { describe, it, expect } from 'vitest';

import { settingsV2Schema } from './settings-v2';
import settingsFixture from '../../__fixtures__/settings-v2-live.json';

describe('generated settingsV2Schema (683 faithfulness)', () => {
  it('validates the real captured settings wire fixture with no contract drift', () => {
    const result = settingsV2Schema.safeParse(settingsFixture);
    if (!result.success) {
      throw new Error(
        'generated settings-v2 schema rejected the real wire fixture: ' +
          JSON.stringify(result.error.issues, null, 2)
      );
    }
    expect(result.success).toBe(true);
  });
});
