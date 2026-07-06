// SPDX-License-Identifier: Apache-2.0
import { describe, it, expect } from 'vitest';
import { inferRoute, describeRoute, type TurnRoute } from './routeHeuristic.js';

describe('inferRoute (Search Thread tempdoc D3)', () => {
  const cases: Array<[string, TurnRoute, string]> = [
    // empty / whitespace → search
    ['', 'search', 'empty string'],
    ['   ', 'search', 'whitespace only'],
    ['  \n  ', 'search', 'whitespace including a newline'],
    // plain short queries → search
    ['invoice march', 'search', 'plain two-word query'],
    ['invoice march   ', 'search', 'trailing spaces do not change the verdict'],
    ['quarterly report from last year budget draft', 'search', '7 words, no keyword/? → search'],
    // multiline → ask
    ['multi\nline text', 'ask', 'contains a newline'],
    // '?' anywhere → ask
    ['invoice march?', 'ask', 'trailing question mark'],
    ['how to configure ocr?', 'ask', 'starter word AND question mark'],
    // starter word, case-insensitive, word-boundary
    ['HOW does this work', 'ask', 'starter word is case-insensitive'],
    ['who sent this email', 'ask', 'starts with who'],
    ['what is happening', 'ask', 'starts with what'],
    ['when did this ship', 'ask', 'starts with when'],
    ['where is the report', 'ask', 'starts with where'],
    ['why is this slow', 'ask', 'starts with why'],
    ['which file is newest', 'ask', 'starts with which'],
    ['can you find the report', 'ask', 'starts with can'],
    ['could this be right', 'ask', 'starts with could'],
    ['should I file this', 'ask', 'starts with should'],
    ['would this work', 'ask', 'starts with would'],
    ['will this run', 'ask', 'starts with will'],
    ['is this correct', 'ask', 'starts with is'],
    ['are these related', 'ask', 'starts with are'],
    ['does this match', 'ask', 'starts with does'],
    ['do these overlap', 'ask', 'starts with do'],
    ['did this happen', 'ask', 'starts with did'],
    ['explain this result', 'ask', 'starts with explain'],
    ['summarize the report', 'ask', 'starts with summarize'],
    ['summarise the report', 'ask', 'starts with summarise (BrEng)'],
    ['compare these two files', 'ask', 'starts with compare'],
    ['list the files', 'ask', 'starts with list'],
    ['tell me more', 'ask', 'starts with tell'],
    ['describe the process', 'ask', 'starts with describe'],
    ['write a summary', 'ask', 'starts with write'],
    ['find out what happened', 'ask', 'multiword starter "find out"'],
    // word-boundary regressions — a starter word as a PREFIX of a longer word must not match
    ['whatsapp export', 'search', 'whatsapp is not "what" (word boundary)'],
    ['listing all files today', 'search', 'listing is not "list" (word boundary)'],
    ['canary release notes', 'search', 'canary is not "can" (word boundary)'],
    // verbose length fallback (≥ 8 words, no keyword/? present)
    [
      'quarterly report from last year budget summary draft',
      'ask',
      '8 words, no keyword/? → ask (verbose fallback)',
    ],
    ['   how are you   ', 'ask', 'leading/trailing whitespace does not hide the starter word'],
  ];

  it.each(cases)('inferRoute(%j) → %s (%s)', (text, expected) => {
    expect(inferRoute(text)).toBe(expected);
  });
});

describe('describeRoute', () => {
  it('maps search → Search', () => {
    expect(describeRoute('search')).toBe('Search');
  });
  it('maps ask → Ask', () => {
    expect(describeRoute('ask')).toBe('Ask');
  });
});
