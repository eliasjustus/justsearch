#!/usr/bin/env node

import assert from 'node:assert/strict';

import {
  protectedStatusChecksFromProtection,
  requiredStatusChecksFromPolicy,
  requiredStatusChecksStrictFromPolicy,
  validateBranchProtection,
} from './check-branch-protection.mjs';

const policy = {
  branchProtection: {
    requireBranchesUpToDateBeforeMerging: false,
  },
  workflows: [
    {
      name: 'CI',
      requiredStatusChecks: ['Public claims', 'Build (no model blobs)', 'Secret scan'],
    },
    {
      name: 'CLA Assistant',
      requiredStatusChecks: ['cla-assistant'],
    },
  ],
};

function protection({ strict = false, contexts = [], checks = [] } = {}) {
  return {
    required_status_checks: {
      strict,
      contexts,
      checks,
    },
  };
}

{
  assert.deepEqual(requiredStatusChecksStrictFromPolicy(policy), { strict: false, errors: [] });
}

{
  assert.deepEqual(requiredStatusChecksFromPolicy(policy).checks, [
    'Public claims',
    'Build (no model blobs)',
    'Secret scan',
    'cla-assistant',
  ]);
}

{
  const actual = protectedStatusChecksFromProtection(
    protection({
      contexts: ['Public claims'],
      checks: [{ context: 'Build (no model blobs)' }, { context: 'Secret scan' }, { context: 'cla-assistant' }],
    })
  );
  assert.equal(actual.strict, false);
  assert.deepEqual(actual.contexts, ['Build (no model blobs)', 'cla-assistant', 'Public claims', 'Secret scan']);
}

{
  const report = validateBranchProtection({
    policy,
    protection: protection({
      contexts: ['Public claims', 'Build (no model blobs)', 'Secret scan', 'cla-assistant'],
    }),
  });
  assert.equal(report.ok, true);
}

{
  const report = validateBranchProtection({
    policy,
    protection: protection({
      strict: false,
      contexts: ['Public claims', 'Build (no model blobs)', 'Secret scan', 'cla-assistant'],
    }),
  });
  assert.equal(report.ok, true);
  assert.equal(report.strict, false);
  assert.equal(report.expectedStrict, false);
  assert.equal(report.actualStrict, false);
}

{
  const report = validateBranchProtection({
    policy,
    protection: protection({
      strict: true,
      contexts: ['Public claims', 'Build (no model blobs)', 'Secret scan', 'cla-assistant'],
    }),
  });
  assert.equal(report.ok, false);
  assert.match(report.errors.join('\n'), /strict setting is true, but policy requires false/);
}

{
  const report = validateBranchProtection({
    policy,
    protection: protection({
      contexts: ['Public claims', 'Build (no model blobs)', 'cla-assistant'],
    }),
  });
  assert.equal(report.ok, false);
  assert.match(report.errors.join('\n'), /missing required status check: Secret scan/);
}

{
  const report = validateBranchProtection({
    policy,
    protection: protection({
      contexts: ['Public claims', 'Build (no model blobs)', 'Secret scan', 'cla-assistant', 'Old omnibus build'],
    }),
  });
  assert.equal(report.ok, false);
  assert.match(report.errors.join('\n'), /undeclared status check: Old omnibus build/);
}

{
  const report = validateBranchProtection({
    policy: {
      branchProtection: { requireBranchesUpToDateBeforeMerging: false },
      workflows: [{ name: 'CI', requiredStatusChecks: ['Secret scan', 'Secret scan'] }],
    },
    protection: protection({ contexts: ['Secret scan'] }),
  });
  assert.equal(report.ok, false);
  assert.match(report.errors.join('\n'), /duplicate required status check/);
}

{
  const report = validateBranchProtection({
    policy: { workflows: [{ name: 'CI', requiredStatusChecks: ['Secret scan'] }] },
    protection: protection({ contexts: ['Secret scan'] }),
  });
  assert.equal(report.ok, false);
  assert.match(report.errors.join('\n'), /must declare branchProtection\.requireBranchesUpToDateBeforeMerging/);
}

console.log('test-check-branch-protection: PASS');
