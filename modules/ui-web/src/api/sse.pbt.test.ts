/**
 * SSE framing-invariance — a property test in TypeScript (tempdoc 554; fast-check).
 *
 * Law class: framing (metamorphic). Oracle class: free — the whole-stream parse IS the spec, so no
 * re-implementation is needed. Property: splitting a well-formed SSE stream at ARBITRARY chunk
 * boundaries and feeding it incrementally (carrying the remainder) recovers exactly the same events
 * as parsing it whole. This is the case the ~7 hand-written `sse.test.ts` splits cannot exhaust.
 */
import { describe, it, expect } from 'vitest';
import fc from 'fast-check';
import { parseSseBuffer, type SseEvent } from './sse';

function parseWhole(s: string): SseEvent[] {
  const out: SseEvent[] = [];
  parseSseBuffer(s, (e) => out.push(e));
  return out;
}

function parseChunked(s: string, rawSplits: number[]): SseEvent[] {
  const pts = [...new Set(rawSplits.map((p) => p % (s.length + 1)))]
    .filter((p) => p > 0 && p < s.length)
    .sort((a, b) => a - b);
  const chunks: string[] = [];
  let prev = 0;
  for (const p of pts) {
    chunks.push(s.slice(prev, p));
    prev = p;
  }
  chunks.push(s.slice(prev));
  const out: SseEvent[] = [];
  let rem = '';
  for (const c of chunks) {
    rem += c;
    rem = parseSseBuffer(rem, (e) => out.push(e));
  }
  return out;
}

const token = fc
  .array(fc.constantFrom(...'abcdefghijklmnopqrstuvwxyz0123456789-_'.split('')), {
    minLength: 1,
    maxLength: 8,
  })
  .map((a) => a.join(''));

describe('SSE framing-invariance (property test; fast-check)', () => {
  it('arbitrary chunk-boundary splits recover the same events as a whole parse', () => {
    const evt = fc.record({ event: token, data: token });
    fc.assert(
      fc.property(
        fc.array(evt, { minLength: 1, maxLength: 6 }),
        fc.array(fc.nat(), { maxLength: 6 }),
        (events, rawSplits) => {
          const stream = events.map((e) => `event: ${e.event}\ndata: ${e.data}\n\n`).join('');
          expect(parseChunked(stream, rawSplits)).toEqual(parseWhole(stream));
        },
      ),
      { numRuns: 300 },
    );
  });
});
