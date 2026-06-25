/**
 * Unit tests for search domain mapper.
 */
import { describe, it, expect } from 'vitest';
import { mapKnowledgeSearchResponse, type KnowledgeSearchResponse } from './search';

describe('mapKnowledgeSearchResponse', () => {
  it('maps empty results to empty hits', () => {
    const raw: KnowledgeSearchResponse = {
      totalHits: 0,
      tookMs: 5,
      results: [],
    };

    const result = mapKnowledgeSearchResponse(raw);

    expect(result.hits).toEqual([]);
    expect(result.totalHits).toBe(0);
    expect(result.queryTimeMs).toBe(5);
    expect(result.nextCursor).toBeUndefined();
  });

  it('maps doc_id from fields if present, falls back to result.id', () => {
    const raw: KnowledgeSearchResponse = {
      totalHits: 2,
      tookMs: 10,
      results: [
        { id: 'id1', score: 1.0, fields: { doc_id: 'custom-doc-id' } },
        { id: 'id2', score: 0.9, fields: {} },
      ],
    };

    const result = mapKnowledgeSearchResponse(raw);

    expect(result.hits[0]?.docId).toBe('custom-doc-id');
    expect(result.hits[1]?.docId).toBe('id2');
  });

  it('derives title from filename if title is not present', () => {
    const raw: KnowledgeSearchResponse = {
      results: [
        { id: 'doc1', score: 1.0, fields: { path: '/home/user/docs/report.pdf' } },
      ],
    };

    const result = mapKnowledgeSearchResponse(raw);

    expect(result.hits[0]?.title).toBe('report.pdf');
    expect(result.hits[0]?.path).toBe('/home/user/docs/report.pdf');
  });

  it('uses explicit title if present', () => {
    const raw: KnowledgeSearchResponse = {
      results: [
        { id: 'doc1', score: 1.0, fields: { title: 'My Report', path: '/home/user/docs/report.pdf' } },
      ],
    };

    const result = mapKnowledgeSearchResponse(raw);

    expect(result.hits[0]?.title).toBe('My Report');
  });

  it('handles Windows-style paths for filename extraction', () => {
    const raw: KnowledgeSearchResponse = {
      results: [
        { id: 'doc1', score: 1.0, fields: { path: 'C:\\Users\\me\\docs\\notes.md' } },
      ],
    };

    const result = mapKnowledgeSearchResponse(raw);

    expect(result.hits[0]?.title).toBe('notes.md');
  });

  it('passes through mime_base correctly', () => {
    const raw: KnowledgeSearchResponse = {
      results: [
        { id: 'doc1', score: 1.0, fields: { mime: 'text/markdown', mime_base: 'text' } },
      ],
    };

    const result = mapKnowledgeSearchResponse(raw);

    expect(result.hits[0]?.mime).toBe('text/markdown');
    expect(result.hits[0]?.mimeBase).toBe('text');
  });

  it('formats meta from mime and language', () => {
    const raw: KnowledgeSearchResponse = {
      results: [
        { id: 'doc1', score: 1.0, fields: { mime: 'text/x-python', language: 'python' } },
        { id: 'doc2', score: 0.9, fields: { mime: 'application/pdf' } },
        { id: 'doc3', score: 0.8, fields: {} },
      ],
    };

    const result = mapKnowledgeSearchResponse(raw);

    expect(result.hits[0]?.meta).toBe('text/x-python . python');
    expect(result.hits[1]?.meta).toBe('application/pdf');
    expect(result.hits[2]?.meta).toBeUndefined();
  });

  it('handles facets passthrough', () => {
    const raw: KnowledgeSearchResponse = {
      results: [],
      facets: {
        file_kind: { pdf: 10, markdown: 5 },
        language: { python: 3, typescript: 2 },
      },
      facetsTruncated: true,
    };

    const result = mapKnowledgeSearchResponse(raw);

    expect(result.facets).toEqual({
      file_kind: { pdf: 10, markdown: 5 },
      language: { python: 3, typescript: 2 },
    });
    expect(result.facetsTruncated).toBe(true);
  });

  it('omits nextCursor if empty or whitespace', () => {
    const raw1: KnowledgeSearchResponse = { results: [], nextCursor: '' };
    const raw2: KnowledgeSearchResponse = { results: [], nextCursor: '   ' };
    const raw3: KnowledgeSearchResponse = { results: [], nextCursor: 'abc123' };

    expect(mapKnowledgeSearchResponse(raw1).nextCursor).toBeUndefined();
    expect(mapKnowledgeSearchResponse(raw2).nextCursor).toBeUndefined();
    expect(mapKnowledgeSearchResponse(raw3).nextCursor).toBe('abc123');
  });

  it('defaults totalHits to hits.length if not provided', () => {
    const raw: KnowledgeSearchResponse = {
      results: [
        { id: 'doc1', score: 1.0 },
        { id: 'doc2', score: 0.9 },
      ],
    };

    const result = mapKnowledgeSearchResponse(raw);

    expect(result.totalHits).toBe(2);
  });

  it('defaults queryTimeMs to 0 if not provided', () => {
    const raw: KnowledgeSearchResponse = { results: [] };

    const result = mapKnowledgeSearchResponse(raw);

    expect(result.queryTimeMs).toBe(0);
  });

  it('defaults score to 0 if not a number', () => {
    const raw: KnowledgeSearchResponse = {
      results: [
        // @ts-expect-error - testing invalid input
        { id: 'doc1', score: 'invalid' },
      ],
    };

    const result = mapKnowledgeSearchResponse(raw);

    expect(result.hits[0]?.score).toBe(0);
  });

  describe('excerpt regions mapping', () => {
    it('maps well-formed excerpt regions with match spans', () => {
      const raw: KnowledgeSearchResponse = {
        results: [{
          id: 'doc1', score: 1.0,
          excerptRegions: [{
            text: 'The needle appears in this sentence.',
            startChar: 5000,
            endChar: 5400,
            approxLine: 120,
            matchSpans: [
              { field: 'content', startChar: 4, endChar: 10, term: 'needle' },
            ],
          }],
        }],
      };

      const result = mapKnowledgeSearchResponse(raw);
      const regions = result.hits[0]?.excerptRegions;

      expect(regions).toBeDefined();
      expect(regions).toHaveLength(1);
      expect(regions![0]!.text).toBe('The needle appears in this sentence.');
      expect(regions![0]!.startChar).toBe(5000);
      expect(regions![0]!.endChar).toBe(5400);
      expect(regions![0]!.approxLine).toBe(120);
      expect(regions![0]!.matchSpans).toHaveLength(1);
      expect(regions![0]!.matchSpans![0]!.startChar).toBe(4);
      expect(regions![0]!.matchSpans![0]!.endChar).toBe(10);
    });

    it('returns undefined for empty excerpt regions', () => {
      const raw: KnowledgeSearchResponse = {
        results: [{ id: 'doc1', score: 1.0, excerptRegions: [] }],
      };

      const result = mapKnowledgeSearchResponse(raw);
      expect(result.hits[0]?.excerptRegions).toBeUndefined();
    });

    it('returns undefined when excerpt regions not present', () => {
      const raw: KnowledgeSearchResponse = {
        results: [{ id: 'doc1', score: 1.0 }],
      };

      const result = mapKnowledgeSearchResponse(raw);
      expect(result.hits[0]?.excerptRegions).toBeUndefined();
    });

    it('drops regions with empty text', () => {
      const raw: KnowledgeSearchResponse = {
        results: [{
          id: 'doc1', score: 1.0,
          excerptRegions: [
            { text: '', startChar: 0, endChar: 100, approxLine: 1, matchSpans: [] },
            { text: 'Valid region text.', startChar: 200, endChar: 400, approxLine: 5, matchSpans: [] },
          ],
        }],
      };

      const result = mapKnowledgeSearchResponse(raw);
      const regions = result.hits[0]?.excerptRegions;

      expect(regions).toHaveLength(1);
      expect(regions![0]!.text).toBe('Valid region text.');
    });

    it('drops match spans with endChar <= startChar', () => {
      const raw: KnowledgeSearchResponse = {
        results: [{
          id: 'doc1', score: 1.0,
          excerptRegions: [{
            text: 'Some text with matches.',
            startChar: 100, endChar: 500, approxLine: 3,
            matchSpans: [
              { field: 'content', startChar: 5, endChar: 5 },   // zero-width — dropped
              { field: 'content', startChar: 10, endChar: 8 },  // negative — dropped
              { field: 'content', startChar: 5, endChar: 9, term: 'text' }, // valid
            ],
          }],
        }],
      };

      const result = mapKnowledgeSearchResponse(raw);
      const spans = result.hits[0]?.excerptRegions?.[0]?.matchSpans;

      expect(spans).toHaveLength(1);
      expect(spans![0]!.startChar).toBe(5);
      expect(spans![0]!.endChar).toBe(9);
    });

    it('clamps negative startChar to 0 and approxLine to 1', () => {
      const raw: KnowledgeSearchResponse = {
        results: [{
          id: 'doc1', score: 1.0,
          excerptRegions: [{
            text: 'Region text.',
            startChar: -10,
            endChar: 200,
            approxLine: -5,
            matchSpans: [],
          }],
        }],
      };

      const result = mapKnowledgeSearchResponse(raw);
      const region = result.hits[0]?.excerptRegions?.[0];

      expect(region?.startChar).toBe(0);
      expect(region?.approxLine).toBe(1);
    });

    it('maps multiple regions preserving order', () => {
      const raw: KnowledgeSearchResponse = {
        results: [{
          id: 'doc1', score: 1.0,
          excerptRegions: [
            { text: 'First region.', startChar: 1000, endChar: 1400, approxLine: 25, matchSpans: [] },
            { text: 'Second region.', startChar: 8000, endChar: 8400, approxLine: 180, matchSpans: [] },
            { text: 'Third region.', startChar: 15000, endChar: 15400, approxLine: 340, matchSpans: [] },
          ],
        }],
      };

      const result = mapKnowledgeSearchResponse(raw);
      const regions = result.hits[0]?.excerptRegions;

      expect(regions).toHaveLength(3);
      expect(regions![0]!.approxLine).toBe(25);
      expect(regions![1]!.approxLine).toBe(180);
      expect(regions![2]!.approxLine).toBe(340);
    });
  });
});

