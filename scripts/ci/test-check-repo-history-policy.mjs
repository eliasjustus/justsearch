#!/usr/bin/env node

import assert from 'node:assert/strict';

import { renderMarkdown, validateRepoHistoryPolicy } from './check-repo-history-policy.mjs';

const policy = {
  repoSettings: {
    allow_squash_merge: true,
    allow_merge_commit: false,
    allow_rebase_merge: false,
    delete_branch_on_merge: true,
    squash_merge_commit_title: 'PR_TITLE',
    squash_merge_commit_message: 'PR_BODY',
  },
  branchProtection: {
    branch: 'main',
    requirePullRequestBeforeMerging: true,
    requiredApprovingReviewCount: 0,
    enforceAdmins: true,
  },
};

function repo(overrides = {}) {
  return {
    allow_squash_merge: true,
    allow_merge_commit: false,
    allow_rebase_merge: false,
    delete_branch_on_merge: true,
    squash_merge_commit_title: 'PR_TITLE',
    squash_merge_commit_message: 'PR_BODY',
    ...overrides,
  };
}

function protection({ requiredApprovingReviewCount = 0, enforceAdmins = true, pullRequestReviews = true } = {}) {
  return {
    required_pull_request_reviews: pullRequestReviews
      ? {
          required_approving_review_count: requiredApprovingReviewCount,
        }
      : null,
    enforce_admins: {
      enabled: enforceAdmins,
    },
  };
}

{
  const report = validateRepoHistoryPolicy({
    policy,
    repo: repo(),
    protection: protection(),
    branch: 'main',
  });
  assert.equal(report.ok, true);
  assert.deepEqual(report.errors, []);
}

{
  const report = validateRepoHistoryPolicy({
    policy,
    repo: repo({ allow_merge_commit: true }),
    protection: protection(),
    branch: 'main',
  });
  assert.equal(report.ok, false);
  assert.match(report.errors.join('\n'), /allow_merge_commit/);
}

{
  const report = validateRepoHistoryPolicy({
    policy,
    repo: repo({ squash_merge_commit_message: 'COMMIT_MESSAGES' }),
    protection: protection(),
    branch: 'main',
  });
  assert.equal(report.ok, false);
  assert.match(report.errors.join('\n'), /squash_merge_commit_message/);
}

{
  const report = validateRepoHistoryPolicy({
    policy,
    repo: repo(),
    protection: protection({ pullRequestReviews: false }),
    branch: 'main',
  });
  assert.equal(report.ok, false);
  assert.match(report.errors.join('\n'), /requirePullRequestBeforeMerging/);
  assert.match(report.errors.join('\n'), /requiredApprovingReviewCount/);
}

{
  const report = validateRepoHistoryPolicy({
    policy,
    repo: repo(),
    protection: protection({ requiredApprovingReviewCount: 1 }),
    branch: 'main',
  });
  assert.equal(report.ok, false);
  assert.match(report.errors.join('\n'), /requiredApprovingReviewCount/);
}

{
  const report = validateRepoHistoryPolicy({
    policy,
    repo: repo(),
    protection: protection({ enforceAdmins: false }),
    branch: 'main',
  });
  assert.equal(report.ok, false);
  assert.match(report.errors.join('\n'), /enforceAdmins/);
}

{
  const report = validateRepoHistoryPolicy({
    policy,
    repo: repo(),
    protection: protection(),
    branch: 'develop',
  });
  assert.equal(report.ok, false);
  assert.match(report.errors.join('\n'), /checked branch is develop/);
}

{
  const report = validateRepoHistoryPolicy({
    policy,
    repo: repo(),
    protection: protection(),
    branch: 'main',
  });
  assert.match(renderMarkdown(report), /Repo History Policy/);
}

console.log('test-check-repo-history-policy: PASS');
