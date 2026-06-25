/**
 * Tempdoc 564 Phase 5: the failed-jobs surface is validated at the FE parse boundary against the
 * GENERATED record→JSON-Schema→Zod projection (the single authority), not an unchecked raw cast.
 * This covers that the generated schema accepts the real wire shape produced by
 * IndexingController.handleListFailedJobs (built from IndexingService.FailedJobInfo).
 */
import { describe, it, expect } from 'vitest';
import { failedJobsResponseSchema } from '../generated/schema-types/failed-jobs-response.js';

describe('failedJobsResponseSchema (564 Phase 5, generated)', () => {
  it('accepts the failed-jobs wire envelope', () => {
    const result = failedJobsResponseSchema.safeParse({
      jobs: [
        {
          path: 'C:/docs/report.pdf',
          errorMessage: 'Tika extraction failed',
          attempts: 3,
          lastUpdatedMs: 1_714_300_000_000,
          collection: 'default',
        },
      ],
      count: 1,
    });
    expect(result.success).toBe(true);
    expect(result.data?.jobs?.[0]?.attempts).toBe(3);
  });

  it('accepts an empty failed-jobs list', () => {
    const result = failedJobsResponseSchema.safeParse({ jobs: [], count: 0 });
    expect(result.success).toBe(true);
  });
});
