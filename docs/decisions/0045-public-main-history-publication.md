---
title: "Public main history publication"
type: decision
status: stable
description: "Public main receives curated squash commits from pull requests while branch commits remain workspace history."
date: 2026-06-28
---

# ADR-0045: Public main history publication

## Status

Accepted.

## Context

The public repository is now the canonical JustSearch development repository.
That changes the audience for `main`: it is no longer just a private working
log for one maintainer and their agents. It is a public project history read by
contributors, users, reviewers, and future agents.

Agent branches and maintainer worktrees often contain checkpoint commits,
investigation commits, retry commits, and temporary documentation edits. Those
commits are useful while work is active, but preserving every intermediate step
on public `main` turns the default branch into an execution transcript instead
of a durable project record.

The repository already treats some public facts as projected surfaces. Public
CI exposes stable fact-lane names rather than workflow internals, and branch
protection verifies those names through a small maintainer-run script. Public
Git history needs the same shape: working evidence can stay detailed, but the
published history should be intentional.

## Decision

Public `main` is a projected history surface. Ordinary changes land through a
pull request as one curated squash commit. The squash commit title and body come
from the PR title and PR body, not from the raw branch commit list.

Branch commits remain workspace history. Agents and maintainers may checkpoint,
experiment, and recover freely on branches. Review detail, test evidence,
discussion, and implementation notes live in the PR, checks, artifacts, and
working docs. The public commit on `main` records the durable project change.

The repository settings for the public repo should enforce this default:

- allow squash merging;
- disable routine merge commits;
- disable routine rebase merging;
- delete source branches after merge;
- use the PR title as the squash commit title;
- use the PR body as the squash commit body;
- require PR publication for `main` with zero required approving reviews;
- enforce branch protection for administrators.

Rare exceptions require an explicit maintainer action and reason. A
topology-preserving merge or curated multi-commit publication is possible only
when the intermediate commits are themselves durable public review units or the
branch topology has independent public meaning.

A small maintainer-run verifier records the expected settings in
`scripts/ci/repo-history-policy.v1.json` and checks them with
`scripts/ci/check-repo-history-policy.mjs`. The verifier is not a CI gate in
this slice because normal pull-request tokens cannot reliably read all
repository and branch-protection settings, and the native GitHub settings are
the enforcement mechanism.

## Consequences

Positive:

- Public `main` stays readable as project history rather than agent session
  history.
- Contributors do not need to curate local commit history before opening a PR.
- Dependency-update PRs, agent PRs, and normal maintainer PRs all have a simple
  default publication path.
- Branch and PR evidence is preserved without making every checkpoint a public
  mainline commit.

Negative:

- Maintainers must keep PR titles and bodies suitable for the resulting squash
  commit.
- A deliberately curated multi-commit change needs an explicit exception
  instead of using rebase merge casually.
- Repository settings become another maintainer-owned public fact to verify
  when GitHub configuration changes.

Neutral:

- Existing public history is not rewritten.
- This decision does not change CI fact lanes or required status-check names.
- The verifier is maintainer-run, similar to branch-protection verification,
  and can be promoted later only if drift proves that necessary.

## Alternatives Considered

### Keep all merge methods enabled and document a preference

Rejected. A soft preference would still allow the next routine agent branch to
land as a merge commit or rebased checkpoint transcript. The problem is a
publication boundary, so native repository settings should carry the default.

### Require linear history only

Rejected. Linear history prevents merge commits, but it still allows rebase
merges. Rebase merge preserves checkpoint commits on `main`, which is the
primary history-shape problem.

### Allow rebase merge for curated series by default

Rejected for the first public-history policy. A curated multi-commit series is
a valid exception, but the current repository does not have a recurring need
for a standing multi-commit lane. Keeping rebase enabled would make the
exception too easy to use as the default.

### Add a CI gate for repository settings

Rejected for this slice. The relevant GitHub settings are native repository and
branch-protection configuration. A maintainer-run verifier records the policy
without adding a CI job that may lack permissions on pull-request tokens.
