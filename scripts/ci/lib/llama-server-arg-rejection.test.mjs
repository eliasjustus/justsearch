#!/usr/bin/env node
/**
 * Unit tests for scripts/ci/lib/llama-server-arg-rejection.mjs (tempdoc 835).
 *
 * The regression these lock down is real and shipped: the CI detector matched b8185's full string
 * ("… invalid value") while the bundled b8571 emits "… invalid stoi argument", so it silently
 * stopped firing. The marker now lives in governance/llama-server-arg-rejection.v1.json and both
 * the runtime (Java) and this reader are pinned to it.
 *
 * Run with: `node scripts/ci/lib/llama-server-arg-rejection.test.mjs`
 */

import assert from 'node:assert/strict';
import fs from 'node:fs';
import test from 'node:test';

import {
  isReasoningBudgetRejection,
  reasoningBudgetRejectionMarker,
  REASONING_BUDGET_REGISTER_PATH,
} from './llama-server-arg-rejection.mjs';

test('the marker comes from the register, not a local copy', () => {
  const doc = JSON.parse(fs.readFileSync(REASONING_BUDGET_REGISTER_PATH, 'utf8'));
  assert.equal(reasoningBudgetRejectionMarker(), doc.reasoningBudget.rejectionMarker);
});

test('every build wording the register records is detected', () => {
  const doc = JSON.parse(fs.readFileSync(REASONING_BUDGET_REGISTER_PATH, 'utf8'));
  assert.ok(doc.reasoningBudget.observedSuffixes.length >= 2);
  for (const { build, suffix } of doc.reasoningBudget.observedSuffixes) {
    assert.ok(
      isReasoningBudgetRejection(`${reasoningBudgetRejectionMarker()}${suffix}`),
      `not detected for ${build}`,
    );
  }
});

test('b8571 stoi wording is detected — the case the old suffix matcher missed', () => {
  assert.ok(
    isReasoningBudgetRejection(
      'error while handling argument "--reasoning-budget": invalid stoi argument',
    ),
  );
});

test('unrelated output is not a reasoning-budget rejection', () => {
  assert.equal(
    isReasoningBudgetRejection('error while handling argument "--reasoning-format": invalid value'),
    false,
  );
  assert.equal(isReasoningBudgetRejection('srv    load_model: loading model'), false);
  assert.equal(isReasoningBudgetRejection(''), false);
  assert.equal(isReasoningBudgetRejection(null), false);
});
