import { execFileSync } from 'node:child_process';
import fs from 'node:fs';

import { resolveGhBin } from '../../dev/run-gh.mjs';

const SNAPSHOT_QUERY = `
query PublicationSnapshot($owner: String!, $name: String!, $number: Int!) {
  repository(owner: $owner, name: $name) {
    pullRequest(number: $number) {
      number title body url headRefName headRefOid updatedAt baseRefName
      isDraft state mergeStateStatus isInMergeQueue
      mergeQueueEntry { position }
      autoMergeRequest { enabledAt }
      author { login }
      viewerMergeHeadlineText(mergeType: SQUASH)
    }
  }
}`;

export function splitRepoSlug(slug) {
  const match = /^([^/]+)\/([^/]+)$/.exec(String(slug ?? ''));
  if (!match) throw new Error(`Invalid repository slug: ${JSON.stringify(slug)}.`);
  return { owner: match[1], name: match[2] };
}

export function loadPublicationSnapshot({ repo, pr, snapshotJson = null }) {
  if (snapshotJson) return JSON.parse(fs.readFileSync(snapshotJson, 'utf8'));
  const { owner, name } = splitRepoSlug(repo);
  const output = execFileSync(resolveGhBin(), [
    'api', 'graphql', '-f', `query=${SNAPSHOT_QUERY}`,
    '-F', `owner=${owner}`, '-F', `name=${name}`, '-F', `number=${pr}`,
  ], { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'], windowsHide: true });
  const result = JSON.parse(output);
  const pullRequest = result?.data?.repository?.pullRequest;
  if (!pullRequest) throw new Error(`PR #${pr} was not returned by GitHub.`);
  return pullRequest;
}
