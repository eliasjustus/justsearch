#!/usr/bin/env node

import assert from 'node:assert/strict';

import {
  protectedStatusChecksFromProtection,
  requiredStatusChecksFromPolicy,
  validateBranchProtection,
} from './check-branch-protection.mjs';

const policy = {
  workflows: [
    {
      name: 'CI',
      requiredStatusChecks: ['Public claims', 'Build (no model blobs)', 'DCO'],
    },
    {
      name: 'CLA Assistant',
      requiredStatusChecks: ['cla-assistant'],
    },
  ],
};

function protection({ strict = true, contexts = [], checks = [] } = {}) {
  return {
    required_status_checks: {
      strict,
      contexts,
      checks,
    },
  };
}

{
  assert.deepEqual(requiredStatusChecksFromPolicy(policy).checks, [
    'Public claims',
    'Build (no model blobs)',
    'DCO',
    'cla-assistant',
  ]);
}

{
  const actual = protectedStatusChecksFromProtection(
    protection({
      contexts: ['Public claims'],
      checks: [{ context: 'Build (no model blobs)' }, { context: 'DCO' }, { context: 'cla-assistant' }],
    })
  );
  assert.equal(actual.strict, true);
  assert.deepEqual(actual.contexts, ['Build (no model blobs)', 'cla-assistant', 'DCO', 'Public claims']);
}

{
  const report = validateBranchProtection({
    policy,
    protection: protection({
      contexts: ['Public claims', 'Build (no model blobs)', 'DCO', 'cla-assistant'],
    }),
  });
  assert.equal(report.ok, true);
}

{
  const report = validateBranchProtection({
    policy,
    protection: protection({
      strict: false,
      contexts: ['Public claims', 'Build (no model blobs)', 'DCO', 'cla-assistant'],
    }),
  });
  assert.equal(report.ok, false);
  assert.match(report.errors.join('\n'), /not strict/);
}

{
  const report = validateBranchProtection({
    policy,
    protection: protection({
      contexts: ['Public claims', 'Build (no model blobs)', 'cla-assistant'],
    }),
  });
  assert.equal(report.ok, false);
  assert.match(report.errors.join('\n'), /missing required status check: DCO/);
}

{
  const report = validateBranchProtection({
    policy,
    protection: protection({
      contexts: ['Public claims', 'Build (no model blobs)', 'DCO', 'cla-assistant', 'Old omnibus build'],
    }),
  });
  assert.equal(report.ok, false);
  assert.match(report.errors.join('\n'), /undeclared status check: Old omnibus build/);
}

{
  const report = validateBranchProtection({
    policy: { workflows: [{ name: 'CI', requiredStatusChecks: ['DCO', 'DCO'] }] },
    protection: protection({ contexts: ['DCO'] }),
  });
  assert.equal(report.ok, false);
  assert.match(report.errors.join('\n'), /duplicate required status check/);
}

console.log('test-check-branch-protection: PASS');
