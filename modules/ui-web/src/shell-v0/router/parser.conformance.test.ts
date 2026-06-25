/**
 * Slice 487 §5 — cross-language URL-grammar conformance test (TS side).
 *
 * Reads the shared fixture corpus at
 * `scripts/ci/url-grammar-fixtures/v1.json` and asserts that
 * {@link parseUrl} + {@link extractUrls} from `parser.ts` produce the
 * expected output for every fixture.
 *
 * The Java side of the conformance check ships alongside
 * `MarkdownTextExtractor` in Phase 2.2; both ports test against the same
 * corpus. Drift between the two implementations fails one or both tests.
 */

import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import { extractUrls, parseUrl } from './parser.js';

const __dirname = dirname(fileURLToPath(import.meta.url));
const CORPUS_PATH = resolve(
  __dirname,
  '../../../../../scripts/ci/url-grammar-fixtures/v1.json',
);

interface ParseFixture {
  readonly id: string;
  readonly input: string;
  readonly expected: unknown;
  readonly _excluded?: boolean;
}

interface ExtractFixture {
  readonly id: string;
  readonly input: string;
  readonly extract: ReadonlyArray<{ readonly url: string }>;
  readonly _excluded?: boolean;
}

type Fixture = ParseFixture | ExtractFixture;

function isExtractFixture(f: Fixture): f is ExtractFixture {
  return 'extract' in f;
}

function loadCorpus(): ReadonlyArray<Fixture> {
  const raw = readFileSync(CORPUS_PATH, 'utf8');
  const parsed = JSON.parse(raw) as { fixtures: ReadonlyArray<Fixture> };
  return parsed.fixtures;
}

describe('URL grammar conformance (slice 487 §5 corpus)', () => {
  const fixtures = loadCorpus();

  for (const fixture of fixtures) {
    // Slice 487 §5: skip fixtures explicitly excluded from cross-port conformance.
    if (fixture._excluded === true) continue;
    if (isExtractFixture(fixture)) {
      it(`extract: ${fixture.id}`, () => {
        const result = extractUrls(fixture.input);
        expect(result.map((r) => ({ url: r.url }))).toEqual([...fixture.extract]);
      });
    } else {
      it(`parse: ${fixture.id}`, () => {
        const actual = parseUrl(fixture.input);
        if (fixture.expected === null) {
          expect(actual).toBeNull();
        } else {
          expect(actual).toEqual(fixture.expected);
        }
      });
    }
  }
});
