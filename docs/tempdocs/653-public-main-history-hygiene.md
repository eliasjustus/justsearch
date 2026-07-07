---
title: "Public main as projected history surface and merge policy"
type: tempdoc
status: "axis-1 implemented (squash policy + preview); axis-2 (PR/commit granularity) reopened for design"
created: 2026-06-28
updated: 2026-06-30
related:
  - 634-go-public-cutover-transition
  - 651-public-ci-feedback-loop-efficiency
  - 652-public-ci-unit-test-latency
---

> NOTE: Noncanonical working note. Verify against current GitHub repository
> settings, branch protection, and `git log` before treating any detail as
> current truth.

# 653 - Public main as projected history surface and merge policy

## Idea

The public repository is now the canonical development repo, so `main` needs a
different history standard than the old private working repository. Agent
branches can remain iterative and messy; public `main` should read as a curated,
reviewable project history.

This tempdoc is a handoff for another agent to design and, if appropriate,
implement a lightweight public-history policy. The goal is not to shame high
development velocity or ban small commits. The goal is to prevent the old
private repo's agent work-log pattern from becoming the public repository's
permanent `main` history.

## Why this was opened

After the public cutover stabilization work landed, the public repo was in a
good canonical state: PR #9 was merged, `main` was green, and branch protection
required the public fact lanes plus CLA.

The next concern came from looking back at the previous private checkout
`F:\JustSearch`, which is now archive/history only. That checkout showed the
history shape this public repo should avoid repeating:

- local `main` had 6,563 commits;
- local `main` was 6,563 commits ahead of private `origin/main` and 3 behind;
- 464 commits were merge commits;
- commit volume reached hundreds of commits on single days;
- `docs/tempdocs` was touched by 3,331 commits, and `docs` overall by 3,912;
- many commit subjects were checkpoint, record, WIP, follow-up, green-main, or
  drift-management style messages rather than public narrative units.

The current public repo is still small and healthy. At the time this note was
opened, public `main` had only 26 commits after the cutover merge. The warning
sign is structural, not urgent damage: PR #9 preserved a 20-commit stabilization
branch through a normal merge commit, and GitHub settings allowed merge commits,
squash merges, and rebase merges. If that remains the default development norm,
public `main` can drift toward the old private-history shape.

## Principle candidate

Branches are workspaces. `main` is the public narrative.

Agent sessions should be free to use branch commits as scratchpad, checkpoint,
and recovery history. The merge into `main` should usually collapse that work
into one coherent reviewable change, unless the intermediate commits are
themselves meaningful public review units.

## Boundary

Do not rewrite the current public `main`. It is still small enough, and the
cutover stabilization branch contains useful evidence for the transition.

Do not import, reconcile, or rewrite the old private history. `F:\JustSearch`
is archive/history only.

Do not make tempdocs the problem. Tempdocs are intentionally development
archaeology. The problem is preserving every intermediate tempdoc edit,
investigation checkpoint, CI retry, and drift-fix as a separate public `main`
commit.

Do not solve this with a heavy custom governance framework unless investigation
proves that repository settings and contributor guidance are insufficient.

Do not prevent agents from committing freely on branches. The policy should
shape how work enters public `main`, not make branch work brittle.

## Initial direction for the follow-on agent

Investigate the current GitHub merge settings for `eliasjustus/justsearch` and
decide the smallest policy that keeps `main` readable.

Likely directions to consider:

- make squash merge the default for ordinary PRs;
- consider disabling normal merge commits for routine PRs;
- decide whether rebase merge should remain available for intentionally
  curated multi-commit PRs;
- enable branch deletion on merge;
- document that agent branch commits may be noisy, but public `main` should be
  curated;
- update contributor and agent guidance so future agents know when to squash,
  when to keep multiple commits, and what a good squash message should say;
- keep rare merge commits available only if there is a deliberate integration
  reason, such as preserving a large branch's topology.

The follow-on agent should also consider adjacent areas if they serve the same
purpose:

- whether branch protection or repository rulesets should encode merge-method
  expectations;
- whether PR templates or maintainer checklists should include a history-shape
  reminder;
- whether cutover, tempdoc, or agent docs still teach a private-repo habit that
  is wrong for public `main`;
- whether Dependabot and release PRs need a separate merge convention;
- whether post-merge branch cleanup should be automatic;
- whether a small settings verifier is useful later, or whether that would be
  premature.

## Questions to answer before implementation

- Should the repository enforce squash-only, or merely make squash the default?
- Should admins keep merge-commit capability for exceptional integration work?
- Should rebase merges be allowed, and if so what counts as a curated
  multi-commit PR?
- What should the default squash commit title/message look like?
- Should tempdoc-heavy PRs squash to one `docs:` change, or should they keep a
  more specific `docs(tempdocs):` / `chore:` convention?
- Should the policy distinguish human-authored branches, agent branches,
  Dependabot branches, and release branches?
- Is a settings verifier worth adding now, or should the first slice be only
  repo settings plus documentation?

## Done shape

A good first result would leave the current public history intact and make the
future default safer:

- GitHub merge settings reflect the chosen public-history policy;
- branch deletion on merge is enabled unless there is a concrete reason not to;
- contributor and agent docs explain that `main` is curated while branches can
  be iterative;
- the policy gives maintainers an escape hatch for rare non-squash merges;
- no custom CI or governance gate is added unless it clearly prevents a real
  drift risk;
- future public PRs naturally land as coherent project-history commits rather
  than agent session logs.

## Takeover investigation - 2026-06-28

This pass is investigation only. No implementation or policy design has started.

### Live repository settings

Checked the live public repository with `gh` against `eliasjustus/justsearch`.
Current settings match the warning in this note:

- repository is public, default branch is `main`;
- merge commits are allowed;
- squash merges are allowed;
- rebase merges are allowed;
- automatic branch deletion on merge is disabled;
- auto-merge is disabled;
- default squash title/message behavior is currently
  `COMMIT_OR_PR_TITLE` / `COMMIT_MESSAGES`;
- repository rulesets are empty;
- branch protection on `main` requires strict status checks and one approving
  review, but does not require linear history;
- branch protection does not enforce admins;
- `node scripts/ci/check-branch-protection.mjs --repo eliasjustus/justsearch
  --branch main` passes for the current required check set.

The current branch-protection verifier is a useful precedent, but it only checks
required status-check names and strictness. It does not model merge methods,
linear history, rulesets, branch deletion, or squash-message settings.

### Current history evidence

Local `main` matches `origin/main` at `afde0dc3069ea6e55da20545165e732388f014ca`.
The public history is still small:

- 26 commits total;
- 1 merge commit;
- 15 commits touched `docs/`;
- 15 commits touched `docs/tempdocs/`;
- the busiest public day so far is 2026-06-28 with 16 commits.

PR #9 (`ci: stabilize public cutover lane`) merged on 2026-06-28 through merge
commit `afde0dc3069ea6e55da20545165e732388f014ca` and preserved 19 branch commits
on `main`. Those commits are not bad commits individually, but they demonstrate
the exact mechanism this tempdoc is worried about: an agent stabilization branch
became permanent public mainline history as a branch transcript instead of one
or a few curated project-history units.

Because branch deletion on merge is off, `origin/codex/public-cutover-stabilization`
still exists after merge. That is harmless now, but it is another sign that the
public repo has not yet been given a post-merge cleanup posture.

For comparison only, the old private checkout `F:\JustSearch` still verifies the
motivation numbers recorded above:

- local `main` is 6,563 commits ahead of private `origin/main` and 3 behind;
- 6,563 commits total;
- 464 merge commits;
- 3,331 commits touched `docs/tempdocs`;
- 3,912 commits touched `docs`;
- busiest observed days include 205 commits on 2026-05-18 and 200 commits on
  2026-06-11.

That checkout is archive/history only, not a source of current truth, but it is a
good empirical example of the history shape the public repo should avoid.

### Existing guidance surface

`CONTRIBUTING.md` explains how to open a PR and run verification, but says
nothing about how PR history should land. `.github/PULL_REQUEST_TEMPLATE.md`
asks for summary, related issues, changes, testing, and a generic checklist,
but has no history-shape reminder. `MAINTAINING.md` explains the public
agent-driven development posture, but also does not explain merge policy.

Canonical CI docs do contain a related pattern: ADR-0044 and
`docs/reference/contributing/agent-guide.md` say branch protection requires
stable public fact-lane check names, and `scripts/ci/workflow-signal-policy.v1.json`
is the local declaration that `check-branch-protection.mjs` verifies. That is
useful precedent for a small repo-settings verifier if enforcement becomes
worth it. It is not itself a merge-history policy.

Dependabot is already active and grouped across npm, Gradle, Cargo, and GitHub
Actions. Several Dependabot PRs are open. Any future policy should say whether
these grouped dependency PRs normally squash to one dependency-history commit,
because they will otherwise be the first recurring non-agent case.

### Upstream GitHub constraints

GitHub supports three PR merge methods: merge commit, squash merge, and rebase
merge. GitHub's own docs explicitly frame squash merging as a way to consolidate
work-in-progress branch commits into a clearer default-branch history:
https://docs.github.com/en/pull-requests/collaborating-with-pull-requests/incorporating-changes-from-a-pull-request/about-pull-request-merges

GitHub repository settings can allow or disable squash merging, and the squash
commit message format is configurable. This matters here because the current
repo setting `squash_merge_commit_message=COMMIT_MESSAGES` would still carry
all intermediate branch commit messages into the squash commit body for a
multi-commit PR. Squash merge alone does not fully solve "agent transcript in
main" unless the squash title/message convention is also chosen deliberately:
https://docs.github.com/en/repositories/configuring-branches-and-merges-in-your-repository/configuring-pull-request-merges/configuring-commit-squashing-for-pull-requests

GitHub branch rulesets can require linear history and can require a merge type.
Linear history prevents merge commits, but still allows either squash or rebase
when those methods are enabled. A merge-type rule can be stricter than linear
history if the desired policy is "squash only" for `main`:
https://docs.github.com/en/repositories/configuring-branches-and-merges-in-your-repository/managing-rulesets/available-rules-for-rulesets

GitHub also has a native repository setting to automatically delete head branches
after PRs are merged. Branch protection and repository rules can prevent deletion
in some cases, but the basic setting exists and fits the cleanup concern:
https://docs.github.com/en/repositories/configuring-branches-and-merges-in-your-repository/configuring-pull-request-merges/managing-the-automatic-deletion-of-branches

### Critical reading

The core diagnosis is sound. Public `main` is no longer a private working log,
and the PR #9 merge shows that the default settings already allow a noisy branch
shape to become permanent history. This is early enough to fix without rewriting
anything.

The strongest challenge to the initial direction is that "make squash default"
may be too weak and "squash-only" may be too strong. GitHub does not expose a
simple durable "default merge method" policy in the local repo; if multiple merge
methods remain enabled, maintainers can still choose the wrong one. But if normal
merge commits are disabled globally, rare topology-preserving integration merges
become harder unless there is a clear admin/ruleset bypass story.

Rebase merge is a real middle option, not just noise. It keeps a linear public
history while preserving multiple curated commits. That is useful only if a PR's
commits are intentionally authored as public review units. It is a poor fit for
ordinary agent checkpoint branches, because it preserves exactly the intermediate
commit subjects this note wants to keep off `main`.

The squash-message setting is a hidden but important detail. With the current
`COMMIT_MESSAGES` behavior, even a future squash merge may preserve a long list
of checkpoint subjects in the commit body. If the policy wants public `main` to
read like a curated narrative, the squash message should probably be based on
the PR title/body, not the branch's raw commit list. That is a policy question,
not an implementation decision yet.

Branch deletion on merge looks low-risk and aligned with the tempdoc. It does
not rewrite history, does not affect fork branches, and would have cleaned up the
now-merged `codex/public-cutover-stabilization` remote branch if it had been on.

I would not add a custom CI/governance gate first. The repo already has native
GitHub settings for most of this. The existing `check-branch-protection.mjs`
pattern could be extended later if settings drift becomes real, but the first
question is policy strictness: convention, repository settings, branch
protection linear-history, or ruleset merge-type enforcement.

### Open questions sharpened by the investigation

- Is the intended baseline "squash by default" or "main only accepts squash
  unless explicitly bypassed"?
- If rebase remains available, what concrete evidence proves a PR has curated
  public commits rather than agent checkpoints?
- Should merge commits be disabled repository-wide, or kept available only
  through a ruleset/admin escape hatch?
- Should the squash commit body use the PR body instead of the raw commit list?
- Should Dependabot grouped PRs always squash, with dependency details living in
  the PR body and generated changelog rather than several mainline commits?
- Should an eventual settings verifier extend `check-branch-protection.mjs`, or
  should merge-policy verification remain a maintainer checklist until drift is
  observed?

## Theorization - 2026-06-28

This section deliberately broadens the idea space before design. It is not a
chosen policy.

### Different ways to frame the problem

One framing is **history hygiene**: keep `main` readable by avoiding noisy
checkpoint commits. That is the surface-level problem, but by itself it can
collapse into a blunt rule like "always squash" without asking what information
should survive.

Another framing is **audience separation**. Branch history is for the people and
agents doing the work: recovery, bisecting within a session, recording trials,
and checkpointing before risky edits. `main` history is for future readers:
contributors, maintainers, users evaluating project maturity, and future agents
trying to understand why the project changed. The policy should preserve both
audiences by moving information to the right surface instead of pretending one
history can serve all readers equally.

A third framing is **publication boundary**. The merge button is the moment a
private working surface becomes part of the public project record. The current
repo already has similar boundaries: tempdocs are allowed to be candid and
dated, canonical docs must be current truth, PR checks are named facts, and
branch protection turns check names into public contracts. Main-history policy
is another instance of the same shape: working evidence can be messy, but the
published projection should be intentional.

A fourth framing is **agent-output governance**. Agent branches naturally produce
logs: "record validation", "follow-up", "stabilize", "green", "drift fix". That
is useful during work, but if it lands unchanged it makes the repo look like the
agent's scratchpad rather than the product's story. The policy is less about Git
aesthetics and more about preventing agent execution traces from becoming the
canonical artifact.

A fifth framing is **review unit integrity**. A public commit should be a unit a
reviewer can reason about later: what changed, why, and what evidence supported
it. Too many commits are bad when they are smaller than the reasoning unit.
Too few commits are bad when they join unrelated reasoning units. The best
policy may not be "one PR equals one commit"; it may be "one public commit
equals one durable reason to change."

### Option space, not yet a recommendation

**Soft convention only.** Keep all merge methods enabled, document that ordinary
PRs should squash, and rely on the maintainer to pick the right button and edit
the squash message. This has the least friction and keeps every escape hatch. It
also has the weakest drift resistance: the next tired merge can repeat PR #9.

**Squash-preferred repository posture.** Disable normal merge commits, keep
squash and rebase enabled, enable branch deletion, and document when rebase is
acceptable. This prevents merge-bubble transcripts while preserving curated
multi-commit history. The risk is that "rebase is allowed" becomes a loophole
for agent checkpoints unless the rebase criterion is crisp.

**Squash-only for `main`.** Use settings or a ruleset so ordinary PRs must land
as one squash commit. This maximizes mainline readability and keeps the policy
easy for agents to follow. The cost is loss of intentionally curated
multi-commit branches unless admins have a bypass path or temporarily relax the
rule.

**Linear-history-only.** Require linear history but allow both squash and rebase.
This bans merge commits but treats squash and rebase as equally acceptable. It
solves topology clutter, not agent-transcript clutter. It is likely too weak if
the central concern is checkpoint subjects in `main`.

**Two-lane merge policy.** Ordinary agent/human/dependency PRs squash; explicitly
curated series PRs rebase; merge commits are reserved for rare integration
topology. This is probably the most expressive idea, but it needs a clear review
check so it does not become "anything goes with nicer words."

**PR-as-record, commit-as-summary.** Treat the PR discussion, description,
checks, artifacts, and tempdoc as the detailed evidence record, while the squash
commit is the summary pointer. This makes squash less scary because detail is
not destroyed; it is moved to a surface better suited for it. The hidden
dependency is that PR bodies must be good enough to carry the detail.

**Local branch transcript preserved outside `main`.** Keep noisy branch commits
available in remote branches for a short retention period, or rely on closed PR
refs, but keep them out of `main`. This is a middle ground for auditability, but
it conflicts with automatic branch deletion unless GitHub's PR refs are treated
as sufficient archival evidence.

### Message-shape possibilities

The merge method is only half the issue. The public commit message is the
surface future readers actually see.

One direction is a **PR-title-as-commit-title** convention:
`ci: stabilize public fact lanes`, `docs: define public main history policy`,
`deps: update Gradle dependency group`. This keeps the log scannable.

Another direction is **squash body from PR body**, not branch commits. The body
could preserve the durable summary, tests, and relevant tempdoc link without
listing every checkpoint commit. This is especially important for agent branches,
where the branch commit list is often an execution trace rather than a design
summary.

A third direction is **type-specific commit bodies**:

- code PRs summarize behavior change plus verification;
- docs/tempdoc PRs summarize the decision or investigation boundary;
- CI PRs summarize fact-lane or policy change plus remote validation;
- dependency PRs summarize ecosystem, risk, and automation source.

This may be overkill if made formal, but the idea is useful: the right public
message depends on what kind of evidence the change carries.

### Hidden assumptions to test before design

The tempdoc assumes public `main` should optimize for readability. That is
probably right, but there is a competing value: forensic detail. A future bug
investigation sometimes benefits from seeing the exact intermediate sequence.
The design should be honest about what detail moves to PRs/tempdocs/artifacts
when commits are squashed.

The tempdoc assumes noisy branches are acceptable. That is probably necessary
for agent work, but there is a second-order risk: if branch commits are always
messy, then squashing becomes mandatory cleanup instead of a final publication
step. A healthier long-term practice might still encourage agents to write
better branch commits when practical, while not requiring branch perfection.

The tempdoc assumes GitHub settings are enough unless proven otherwise. That is
pragmatic, but repo settings are invisible in the worktree and easy to drift.
The existing branch-protection verifier shows that this repo already values
local declarations of remote settings. The design question is not "verifier or
no verifier" but "which remote setting becomes stable enough to deserve a
declared source of truth?"

The tempdoc assumes the exceptional merge-commit case matters. It may not. For a
single-maintainer public repo with agent branches, it may be better to start
strict and relax only when a real topology-preserving case appears. The opposite
risk is overfitting to today's small repo and blocking a future release or large
vendor import workflow.

The tempdoc assumes contributors and agents should see the same guidance. Maybe
not. External contributors need a lightweight instruction: "open a PR; the
maintainer may squash it." Maintainer/agent guidance can be stricter:
"branches are scratch; the merge commit message is the public artifact."

### Risks and failure modes

**Policy theater.** Documentation says "curated main", but all merge buttons
remain enabled and the default squash body still includes branch commits. The
repo gets the language of hygiene without changing the actual outcome.

**Over-enforcement too early.** A squash-only ruleset lands before the team has
decided how to preserve intentional multi-commit series. A future legitimate
series then fights the policy or encourages temporary setting changes.

**Rebase loophole.** Rebase remains available for "curated commits", but no one
defines curated. Agent checkpoint branches start landing via rebase because they
look linear and avoid merge commits.

**Loss of evidence.** Squash commits remove intermediate context while PR bodies
stay thin. Future readers see clean commits but cannot recover the reasoning,
test sequence, or rejected alternatives.

**Commit-message dumping.** Squash merge is adopted, but GitHub includes all
branch commit messages in the squash body. The visible `git log --format=fuller`
still reads like the agent transcript.

**Contributor friction.** External contributors are told to prepare perfect
history before opening a PR. That would contradict the current contributor
front door, which intentionally keeps contribution lightweight.

**Automation mismatch.** Dependabot and release PRs follow the same policy as
agent work even though their evidence shape differs. Grouped dependency updates
often want one public commit, while release PRs may need a generated changelog
or tag relationship rather than a normal feature squash.

### Broader principle candidate

A broader invariant may be:

> Public surfaces should be projections of working evidence, not raw dumps of
> the work process.

That same shape already appears elsewhere in the repo:

- canonical docs project current truth from tempdoc history;
- ADR-0044 names CI jobs by the fact they prove rather than the script bucket
  they run;
- branch protection requires stable fact names, not arbitrary workflow shape;
- tempdocs preserve dated reasoning, while canonical docs avoid drifting with
  every work note.

Under that invariant, `main` history is the public projection of branch work.
The branch can hold attempts, retries, and local evidence. The PR can hold
review context and verification. The squash/rebase result on `main` should hold
the durable project change. The hard part is deciding which projection rules are
worth encoding now and which should remain human judgment.

### Ideas that may be useful later

- Treat "public commit = durable reason to change" as the review criterion.
- Add a PR-template checkbox or maintainer-only checklist item asking whether
  the PR should squash, rebase, or preserve topology.
- Prefer PR body as the source for squash commit bodies when branch commits are
  execution traces.
- Define "curated multi-commit PR" by positive evidence: each commit builds or
  tests independently, has a stable subject, and maps to a separable review
  reason.
- Define "ordinary agent PR" by default: squash unless the PR explicitly argues
  for multiple public commits.
- Treat Dependabot grouped PRs as ordinary squash candidates unless a generated
  release/update note needs different handling.
- Enable automatic branch deletion as cleanup independent of the final merge
  method policy.
- Consider a future `check-repo-settings` only after the chosen remote settings
  become part of canonical policy; do not start with a gate.
- If a ruleset is used, document the escape hatch before enabling it so the
  first exceptional case does not create emergency policy edits.

## Design settlement - 2026-06-28

This is the long-term design direction for the current tempdoc's problem. It is
still general: no repository setting, ruleset, script, or documentation edit is
implemented here.

### Adjacent-tempdoc synthesis

The closest adjacent work is not a generic Git-history tempdoc. It is the
go-public fact-surface cluster:

- `go-public-cutover-transition` created the fresh public-history line and made
  `F:\justsearch-public` the canonical development repo. Its "fresh history"
  choice is the upstream premise: public history is a publication surface, not a
  dump of the old private repo.
- `public-ci-feedback-loop-efficiency` and `public-ci-unit-test-latency` settled
  the public CI shape around named facts, stable branch-protection check names,
  and small verifier scripts where remote GitHub configuration becomes a public
  contract.
- `go-public-capability-descriptor-truthfulness` is the closest principle
  sibling: it distinguishes working prose from public projected truth, and it
  extends an existing guard only where a real public drift class is proven.

Those docs point away from a new heavyweight history-governance subsystem. They
point toward the same recurring shape already used for public CI and public
claims: declare the public fact, use the smallest native mechanism that enforces
it, and add a local verifier only when the remote setting becomes important
enough to drift.

### Existing design to extend

The codebase already has usable public-repo policy machinery:

- `scripts/ci/workflow-signal-policy.v1.json` declares public workflow facts,
  owners, trigger expectations, blocking/advisory status, and required check
  names.
- `scripts/ci/check-workflow-triggers.mjs` verifies that workflow files match
  that declaration.
- `scripts/ci/check-branch-protection.mjs` verifies that GitHub branch
  protection requires exactly the declared public check names with strict
  up-to-date branches.
- `scripts/ci/test-evidence-policy.v1.json` and
  `verify-test-evidence-policy.mjs` show the same pattern one layer inward:
  when a test is outside normal hosted evidence, name its tier, owner,
  replacement signal, and cadence.

There is no existing merge-history policy, and no current repo-settings
manifest for merge methods, branch deletion, squash-message format, or rulesets.
That absence matters, but it does not by itself justify a new framework. The
present problem needs one public-history policy and possibly one small
repo-settings verifier later, not a general remote-settings substrate.

### Correct long-term design

The design should treat public `main` as the **projected history surface**.
Branches remain workspaces. Pull requests are the review and evidence record.
Tempdocs remain dated working archaeology. `main` should receive only the
durable project-history unit that a future reader needs.

The default publication rule should be:

> Ordinary PRs land as one curated squash commit whose title/body come from the
> PR-level summary, not from the raw branch commit transcript.

This directly matches the observed problem. PR #9 did not fail because merge
commits are aesthetically bad; it failed the future-shape test because a
routine agent stabilization branch landed as a 19-commit execution log. The
right durable unit was the public CI stabilization outcome, with detailed
evidence preserved in the PR, tempdocs, checks, and artifacts.

Normal merge commits should not be part of the routine public `main` path. They
preserve topology, but this repo has no current recurring topology-preservation
need. Designing a standing merge-commit lane now would add structure for a case
the present problem does not include. If a genuine case appears later, such as a
large imported branch whose topology has independent public meaning, it should
be handled as a maintainer exception with an explicit reason, not as a default
option.

Rebase merge should also not be a routine option for agent branches. It solves
merge bubbles but not transcript leakage: a rebased checkpoint branch is still a
checkpoint branch on `main`. A curated multi-commit PR is a real concept, but
the current repo does not need a standing multi-commit track yet. The policy can
recognize it as an exception: every public commit in such a series must be a
durable review unit, not an agent recovery point.

Automatic branch deletion on merge belongs in the design. It is not a history
rewrite and does not weaken review evidence; it simply prevents the remote from
accumulating stale agent workspaces after the public projection has landed.

The squash message format is part of the design, not a cosmetic detail. If
GitHub keeps using branch commit messages as the squash body, the branch
transcript still leaks into public history even though the topology is clean.
The durable public commit should be derived from the PR title/body or an
edited maintainer summary.

### Scope of the first real design slice

The first slice should be repo posture plus documentation, not custom CI:

- native GitHub settings/rules should make squash the normal path for `main`;
- automatic branch deletion should be enabled;
- the squash commit title/body convention should be documented;
- `CONTRIBUTING.md` should stay contributor-light: external contributors open
  normal PRs, and maintainers may squash before merge;
- `MAINTAINING.md`, the PR template, or agent guidance should carry the stricter
  maintainer/agent rule: branch commits may be iterative, but the merge result
  is the public artifact;
- any exception path for curated multi-commit or topology-preserving history
  should require an explicit PR statement.

The design should not start with a broad `check-repo-settings` gate. The repo
already has native GitHub controls for merge method, branch deletion, and rules.
A verifier becomes warranted only after the chosen settings are policy, in the
same way `check-branch-protection.mjs` became useful once stable public check
names were branch-protection facts. If built later, it should extend or sit
beside the existing branch-protection verifier; it should not invent a parallel
governance world.

### What not to design yet

Do not create a general Git-history taxonomy for every future branch type.
Current evidence supports ordinary PRs, Dependabot PRs, release/tag PRs, and
rare maintainer exceptions. That is enough.

Do not require contributors to curate perfect local history before opening a
PR. That would violate the public contributor front door and confuse a
maintainer publication rule with a contributor burden.

Do not make tempdocs the target. Tempdocs are allowed to be messy and dated.
The public-history policy only decides how the edits land on `main`.

Do not rewrite current `main`. The current public history is small, and PR #9 is
useful transition evidence. This design is forward-only.

## Reach judgment - 2026-06-28

The design is an instance of a broader principle already visible in the
go-public work:

> Public surfaces should be projected from working evidence; they should not be
> raw dumps of the work process.

For this tempdoc, the public surface is `main` history. The working evidence is
branch commits, PR discussion, tempdocs, CI runs, artifacts, and review notes.
The projection is the final public commit message and diff.

The same principle already applies elsewhere:

- public CI check names project named facts from workflow internals;
- branch protection requires stable fact names rather than arbitrary job
  topology;
- test-evidence policy gives hosted-skipped tests an owner, tier, replacement
  signal, and cadence instead of leaving raw `CI=true` skips unexplained;
- capability and claim truthfulness work projects public prose from canonical
  sources rather than hand-authored drift;
- tempdocs preserve working history, while canonical docs carry current truth.

Candidate future scope:

- PR templates and maintainer checklists: ask what public fact/history unit the
  PR should publish.
- Repo-settings verification: if merge method, branch deletion, and squash
  message settings become policy, declare and verify them like branch
  protection.
- Dependabot/release conventions: generated update detail can live in the PR or
  changelog while `main` gets the curated update unit.
- Agent workflow guidance: make clear that branch commits are recovery/workspace
  tools, not necessarily public-history units.

Known current violations or gaps:

- live repository settings still allow merge commits, squash, and rebase, with
  branch deletion disabled;
- the squash body currently uses branch commit messages, which can preserve the
  transcript even under squash;
- `CONTRIBUTING.md`, `MAINTAINING.md`, and the PR template do not yet explain
  the history publication boundary;
- no local verifier models merge-method or branch-deletion settings if they
  drift later.

Do not build a generalized projection framework for this now. The present
problem only requires applying the principle to public Git history with native
GitHub controls and small, scoped documentation. The broader principle is worth
naming because it connects this tempdoc to the repo's existing public-fact
shape, but the general structure is not yet required.

## Internet research pass - 2026-06-28

This pass was warranted because the design depends on active GitHub product
surfaces: merge methods, squash-message defaults, repository rulesets, branch
protection, branch deletion, merge queues, and auto-merge. Those settings can
change independently of this repository, so relying only on local code would be
too stale-prone.

Research scope was deliberately narrow:

- official GitHub docs for pull-request merge methods;
- official GitHub docs for squash-message configuration;
- official GitHub docs and changelog for repository rulesets and merge-method
  rules;
- official GitHub docs for automatic branch deletion, protected branches, merge
  queues, and auto-merge.

Findings:

- GitHub still models PR merging as three methods: merge commit, squash merge,
  and rebase merge. Its docs explicitly frame squash merge as a way to turn
  work-in-progress branch commits into a clearer default-branch history:
  https://docs.github.com/articles/about-pull-request-merges
- Squash-message configuration is load-bearing for this tempdoc. GitHub's
  default for multi-commit PRs can use the PR title plus the list of branch
  commits, but repository settings can instead present the PR title and
  description. That confirms the design point that "squash" alone is not enough
  if the squash body still dumps checkpoint subjects:
  https://docs.github.com/articles/configuring-commit-squashing-for-pull-requests
- GitHub repository rulesets can now require a merge type of merge, squash, or
  rebase. The merge-method rule was announced in public preview in 2024 and
  later marked generally available in 2025. This sharpens the design: if the
  intended policy is ordinary PRs squash to `main`, native rulesets can enforce
  that directly rather than relying only on a maintainer checklist:
  https://github.blog/changelog/2025-03-24-enterprise-custom-properties-enterprise-rulesets-and-pull-request-merge-method-rule-are-all-now-generally-available/
  and
  https://docs.github.com/en/repositories/configuring-branches-and-merges-in-your-repository/managing-rulesets/available-rules-for-rulesets
- Requiring linear history is weaker than the design needs. GitHub documents it
  as preventing merge commits while still allowing squash or rebase. That
  supports the earlier conclusion: linear history solves topology clutter but
  not rebased agent-transcript clutter.
- Automatic branch deletion is a native repository setting under pull-request
  settings. This remains a low-structure part of the design:
  https://docs.github.com/en/repositories/configuring-branches-and-merges-in-your-repository/configuring-pull-request-merges/managing-the-automatic-deletion-of-branches
- Merge queue has its own merge method setting. It is relevant later if the repo
  adopts merge queue, but it is not part of the present problem: the current
  repo is small, and the design does not require queued merges yet:
  https://docs.github.com/en/repositories/configuring-branches-and-merges-in-your-repository/configuring-pull-request-merges/managing-a-merge-queue
- Auto-merge can be enabled and can choose a merge method, but it only merges
  after requirements pass. It is a throughput convenience, not a history-shape
  policy. Do not use auto-merge as the core design mechanism:
  https://docs.github.com/en/repositories/configuring-branches-and-merges-in-your-repository/configuring-pull-request-merges/managing-auto-merge-for-pull-requests-in-your-repository

Design impact:

- Keep the design: public `main` is the projected history surface; ordinary PRs
  should squash with PR-authored summary text.
- Strengthen the enforcement option: a ruleset merge-method rule is now a real
  native mechanism, not speculative future structure.
- Keep linear-history-only out of the main design. It is useful only if rebase
  is considered acceptable routine publication, which this tempdoc rejects for
  agent checkpoint branches.
- Keep merge queue and auto-merge out of scope. They may matter if merge volume
  grows, but they solve queueing/throughput, not the present public-history
  transcript problem.
- If a later verifier is built, it should check the chosen native settings:
  allowed merge methods or merge-method ruleset, squash-message format,
  automatic branch deletion, and any required exception/bypass posture. It
  should remain a small sibling of `check-branch-protection.mjs`, not a broad
  governance framework.

## Confidence-building pass - 2026-06-28

Purpose: reduce implementation surprises before any actual feature work. This
pass intentionally did not change repository policy, GitHub settings, canonical
docs, CI wiring, or application code.

Live repository settings were checked through read-only `gh api` calls. The
current public repository is not already in the intended state:

- squash, merge commits, and rebase merges are all enabled.
- automatic branch deletion after merge is disabled.
- squash commit body defaults to commit messages rather than the PR body.
- no repository rulesets are currently configured.
- `main` branch protection is strict and its required status checks match the
  existing workflow-signal policy, but linear history is not required.

That makes the remaining work real rather than documentary cleanup. The policy
would need a deliberate GitHub settings change, and a future verifier would have
useful failing cases immediately.

Existing repo machinery supports a small implementation shape:

- `scripts/ci/check-branch-protection.mjs` already proves the pattern of a
  narrow GitHub-policy verifier that compares live repository state to a
  checked-in policy and reports JSON/Markdown.
- `scripts/ci/workflow-signal-policy.v1.json` is intentionally about workflow
  signal and should not be expanded casually into all repository policy.
- `scripts/ci/test-evidence-policy.v1.json` and its verifier show the sibling
  pattern for a second narrow policy file when a separate class of governance
  becomes important.
- The existing fixture tests are enough precedent for testing a future
  merge-history verifier without needing network access.

A read-only prototype probe confirmed that the core desired settings are
mechanically observable from the GitHub repository API. It would currently fail
on merge commits enabled, rebase merges enabled, branch deletion disabled, and
the squash message source. This lowers the implementation risk: the main
uncertainty is not whether the state can be observed, but the exact policy
surface to record and enforce.

Documentation surfaces have different audiences:

- `CONTRIBUTING.md` is intentionally lightweight for outside contributors. It
  should not grow agent-oriented history hygiene rules beyond perhaps noting
  that maintainers may squash PRs before merge.
- `MAINTAINING.md` is the better maintainer-facing place for the rule that
  public `main` should contain curated public history units.
- `.github/PULL_REQUEST_TEMPLATE.md` already has summary/testing structure and
  could later carry a small merge-intent prompt if the PR body becomes the
  squash commit source.
- `docs/reference/contributing/agent-guide.md` is the likely home for agent
  expectations if CI or verifier behavior is added.

Dependency PRs are an immediate practical case. The repository's Dependabot
configuration creates grouped dependency PRs across npm, Gradle, Cargo, and
GitHub Actions. Those should usually become one curated public dependency
update unit, not a preserved sequence of bot/checkpoint commits.

Remaining uncertainties:

- The exact mutation path was not tested because changing GitHub settings would
  be implementation work. The likely future path is still either repository
  merge settings, a ruleset that restricts allowed merge methods, or both.
- GitHub ruleset details are not observable in this repository yet because no
  rulesets exist. A verifier can still start with repository merge settings and
  add ruleset checks only if enforcement actually uses rulesets.
- Squash message defaults are documented and observable, but the exact desired
  value should be validated during implementation before changing settings.
- PR-body quality remains a human/process dependency. A verifier can check
  settings; it cannot guarantee that every PR summary is a good durable history
  note.
- Rare exceptions still need to stay explicit. The design should not pretend
  merge commits or rebases are impossible; it should make them non-routine and
  justified.

Confidence rating for the remaining work: **8/10**. The design is now well
grounded in live repo state and existing verifier patterns. It is not 9 or 10
because the actual settings mutation and any ruleset behavior still need to be
validated during implementation or in a sandbox before relying on them.

## Implementation record - 2026-06-28

Implemented the first real slice of the design as a forward-only public-main
publication policy. Existing public history was not rewritten.

Local repository changes:

- added ADR-0045, "Public main history publication";
- added `scripts/ci/repo-history-policy.v1.json`;
- added `scripts/ci/check-repo-history-policy.mjs`;
- added `scripts/ci/test-check-repo-history-policy.mjs`;
- updated maintainer, contributor, PR-template, and agent/worktree guidance;
- regenerated `docs/llms.txt` and synced generated skills after canonical doc
  edits.

Live GitHub settings changed for `eliasjustus/justsearch`:

- squash merge remains enabled;
- routine merge commits are disabled;
- routine rebase merges are disabled;
- automatic source branch deletion after merge is enabled;
- squash commit title source is `PR_TITLE`;
- squash commit body source is `PR_BODY`;
- `main` requires PR publication with zero required approving reviews;
- branch-protection admin enforcement is enabled;
- existing strict required status checks were preserved.

`node scripts/ci/check-repo-history-policy.mjs --repo eliasjustus/justsearch
--branch main --json` passes after the live settings change. Existing
`check-branch-protection` still passes, confirming that the required public CI
fact lanes were not lost while updating branch protection.

Remote branch cleanup:

- `origin/codex/public-cutover-stabilization` was checked with
  `git merge-base --is-ancestor` against `origin/main`;
- after verifying it was merged, the remote branch was deleted.

Validation notes:

- the new repo-history verifier's offline fixture tests pass;
- canonical docs link/index/skill-sync checks pass after regeneration;
- `check-always-loaded-budget` still fails on pre-existing over-budget
  untouched files `.claude/rules/hooks-reference.md` and
  `.claude/rules/tier-register.md`; this branch trimmed its touched
  always-loaded files back under their ceilings;
- `verify-runtime-config-matrix` still crashes because the verifier expects
  `buildMatrixModel()` fields (`yamlKeys`, `envSyspropPairs`) that the current
  library no longer returns; that failure is unrelated to this history-policy
  slice and was not fixed here.

## Follow-up implementation record - 2026-06-28

Critical review found two guidance issues after the first implementation:

- the PR template still contained visible instructional placeholder text that
  could leak into public squash commit bodies now that GitHub uses `PR_BODY`;
- `.claude/rules/branch-safety.md` still contained local merge/fast-forward
  wording from the old merge-commit workflow.

Follow-up changes:

- reduced `.github/PULL_REQUEST_TEMPLATE.md` to commit-body-safe headings only;
- rewrote the branch-safety merge workflow around worktree verification before
  PR readiness, PR squash publication, local `main` update after merge, and
  scoped cleanup;
- kept `.claude/rules/branch-safety.md` under its always-loaded byte ceiling.

## Post-implementation research and possibility map - 2026-06-28

This pass is intentionally research-only. It asks what the implemented policy
could enable later: polish, simplification, extension, maintainer UX, or broader
system ideas. It does not propose another immediate implementation slice.

### Research approach

The right research order is:

1. Re-read this tempdoc, ADR-0045, maintainer guidance, branch-safety guidance,
   the new verifier, the policy JSON, and the PR template.
2. Re-check live repository settings and branch protection read-only, because
   this design depends on mutable GitHub configuration.
3. Read adjacent public-repo tempdocs `651` and `652`, because they established
   the nearby pattern: public facts are named, declared, verified, and kept
   small.
4. Do an internet pass only over active GitHub surfaces: repository merge
   settings, pull-request templates, rulesets, branch protection, merge queue,
   and PR metadata APIs.
5. Separate "useful later" from "should build now." This tempdoc's current
   design deliberately chose native GitHub settings plus a small verifier, so
   any future idea must earn its extra structure.

### Current state after implementation

Read-only live checks still match the implementation:

- `allow_squash_merge=true`;
- `allow_merge_commit=false`;
- `allow_rebase_merge=false`;
- `delete_branch_on_merge=true`;
- `squash_merge_commit_title=PR_TITLE`;
- `squash_merge_commit_message=PR_BODY`;
- `main` requires pull-request publication with zero required approving
  reviews;
- admin enforcement is enabled;
- required status checks remain the public CI fact lanes;
- repository rulesets are still empty;
- `node scripts/ci/check-repo-history-policy.mjs --repo eliasjustus/justsearch
  --branch main --json` passes.

Current `main` is still small: 26 commits and one merge commit, the original
public cutover stabilization merge that motivated this policy. The policy is
therefore still forward-only, not cleanup of accumulated public damage.

### Internet findings that matter

GitHub's repository REST API exposes the settings this verifier checks,
including merge-method booleans, branch deletion, and squash title/body
configuration: [GitHub REST repository endpoints](https://docs.github.com/en/rest/repos/repos).

GitHub's pull-request template docs confirm that the repository template is the
entry surface for PR bodies. Since this repo now uses `PR_BODY` for squash
commit bodies, the template is part of public commit-message UX:
[Creating a pull request template](https://docs.github.com/en/communities/using-templates-to-encourage-useful-issues-and-pull-requests/creating-a-pull-request-template-for-your-repository).

GitHub rulesets can express pull-request rules such as allowed merge methods.
That keeps a future ruleset path available if repository-wide merge-method
settings become too blunt: [Available rules for rulesets](https://docs.github.com/en/repositories/configuring-branches-and-merges-in-your-repository/managing-rulesets/available-rules-for-rulesets).

GitHub also exposes PR metadata through the REST API. That makes a future
maintainer tool mechanically possible: fetch a PR title/body, render the exact
default squash commit message, and lint obvious template leftovers before
publishing: [GitHub REST pull request endpoints](https://docs.github.com/en/rest/pulls/pulls).

Merge queue and auto-merge remain adjacent but not central. They help queueing
and throughput after checks pass; they do not by themselves decide whether the
public history unit is well written.

### Codebase findings

The implemented verifier is intentionally generic over repo settings: any
boolean or string key in `repo-history-policy.v1.json` is compared against the
repository API response. That makes small future extensions cheap, such as
checking `allow_auto_merge` if the maintainer later decides auto-merge should
stay disabled or become allowed.

The branch-protection part is narrow and mirrors `check-branch-protection.mjs`:
it checks only the publication boundary facts this policy needs. It should not
grow into a general branch-protection replacement unless future drift proves
that useful.

The PR template is now the smallest safe template: headings only, no checklist
or placeholder text. That is correct for `PR_BODY`, but it makes PR quality a
human habit rather than an automated outcome. A future helper could support the
maintainer without changing the contributor-facing template.

Dependabot is grouped across npm, Gradle, Cargo, and GitHub Actions. Grouped
Dependabot PRs fit the current rule: they should normally squash into one
dependency-update history unit. The only nuance is PR-body length and generated
detail. A maintainer may need to edit those bodies before squash if they are too
noisy for public commit history.

### Possibility map

**Polish: squash-message preview.** A small maintainer-run command could take a
PR number, fetch `title` and `body`, and print the exact squash commit title and
body that GitHub will use under the current settings. It could warn on empty
sections, visible checklist syntax, template headings with no content, or known
"do not publish" markers. This is the most useful future UX idea because it
supports the human publication step without pretending prose quality can be
fully linted.

**Polish: policy report bundle.** The repo already has
`check-branch-protection --md` and `check-repo-history-policy --md`. A future
maintainer runbook could combine both into a short "public repo policy snapshot"
before major public milestones. This should probably stay documentation or a
manual command, not a CI job.

**Simplification: do not merge policy manifests yet.** It is tempting to fold
repo-history policy into workflow-signal policy, branch-protection policy, or a
general remote-settings manifest. That would reduce file count but blur
ownership. The current sibling manifest is simpler conceptually: workflow facts,
branch-protection check facts, test-evidence facts, and history-publication
facts each have their own reason to change.

**Extension: optional auto-merge posture.** `allow_auto_merge` is currently
false. If PR volume grows and required checks are reliable, auto-merge could be
allowed for low-risk PRs. That would not replace the history policy; it would
make PR title/body quality even more important because the final publication
could happen without a last manual glance.

**Extension: ruleset-based enforcement.** The current repository-wide merge
settings are the simplest enforcement. A ruleset may become worthwhile if the
repo needs a standing exception model: for example, keep merge/rebase methods
available at the repository level for admin-only rare cases while a `main`
ruleset requires squash for normal PRs. Do not add this just because rulesets
exist; add it only if real exception pressure appears.

**Extension: public history health report.** After several months, a small
read-only history report could count merge commits, rebased multi-commit
series, squash-merge subjects, PR-number references, and likely template
leftovers in commit bodies since ADR-0045. This would measure whether the
policy is working in practice. It should start as an audit, not a gate, because
detecting "good public history" from Git alone is fuzzy.

**Extension: Dependabot publication convention.** Grouped dependency PRs are
recurring and may produce verbose generated bodies. If they become noisy on
`main`, document a compact body convention: one summary, ecosystems changed,
notable major/security changes, and testing. Do not add that burden before the
first real dependency PR shows whether it is needed.

**UX feature: PR readiness checklist outside the PR body.** Because the PR body
is now commit-body source, checklists are poor visible content. If maintainers
miss the checklist, the better UX is an external command or GitHub saved reply,
not checklist boilerplate inside the body.

**UX feature: release-note seed.** The curated squash body might later become a
useful seed for release notes. That is a downstream benefit of the policy:
public commits now summarize durable changes. Do not build release-note
automation until release cadence exists.

### Risks and hidden assumptions

The largest remaining assumption is that maintainers will edit PR titles and
bodies before squash. The implementation moved the publication burden to the
right place, but it did not eliminate the need for judgment.

The second assumption is that one squash commit per ordinary PR remains the
right granularity. If future PRs become too broad, the fix is smaller PRs or
explicit curated series, not weakening the public-history policy.

The third assumption is that GitHub settings remain stable and readable through
maintainer credentials. The verifier catches current drift, but it does not
protect against future API shape changes except through tests and maintainer
use.

The fourth assumption is that closed PRs are enough detailed evidence once
source branches are deleted. This is probably fine for normal work, but if a
future audit or regulatory story needs branch transcripts retained, branch
deletion policy would need a conscious revisit.

### Broad principle

The broader shape is **publication preview before publication**.

The repo already has projection checks for public facts: generated docs,
workflow fact lanes, branch-protection settings, and now repo-history settings.
The next level is not "more enforcement everywhere." It is giving maintainers a
preview of the artifact that is about to become public: the commit message,
the branch-protection fact set, the CI fact set, or the release note.

That principle could apply later to:

- squash commit messages before merge;
- release notes before tag publication;
- root README public claims before announcement;
- installer/update metadata before release;
- GitHub repository settings before a launch or governance milestone.

Recognizing the principle does not mean building a general publication-preview
framework now. For this tempdoc, the only strong candidate is a future
PR/squash-message preview helper, because `PR_BODY` is now a direct public
commit-message source.

### Judgment

The implemented design is in the right place: small, native, forward-only, and
verifiable. The best future improvement is not stricter enforcement; it is a
better maintainer UX around the final publication moment.

If this tempdoc gets another implementation slice later, the highest-value
candidate is a read-only `preview squash commit message for PR N` helper plus
very light lint for obvious template leftovers. The next-best candidate is a
periodic history-health report after enough squash-merged PRs exist to measure.
Rulesets, auto-merge, release-note automation, and generalized publication
preview should wait for real pressure.

## Current-state reflection before nearby-work audit - 2026-06-28

The implemented slice is stable in its own terms. The live
`check-repo-history-policy` verifier still passes for `eliasjustus/justsearch`
on `main`, which means the repository settings and branch-protection
publication boundary still match ADR-0045:

- ordinary publication is squash-only;
- squash title/body come from the PR title/body;
- source branches are deleted after merge;
- `main` requires PR publication with zero required approving reviews;
- admin enforcement is enabled.

The local worktree still contains the implementation as uncommitted work. That
is expected for this session, but it matters for coordination: adjacent agents
will not see the ADR, verifier, PR-template cleanup, or branch-safety rewrite
until this branch is published through the new PR squash path.

Conceptually, the remaining work is optional polish rather than policy
completion. The core outcome is already satisfied: future `main` history is
shaped by native GitHub settings plus a small maintainer verifier, without
rewriting existing `main`. The strongest possible follow-up remains a
read-only squash-message preview helper, because `PR_BODY` now becomes public
commit body text. That is maintainer UX around the publication moment, not
another enforcement layer.

The main coordination risk is not that another nearby tempdoc disagrees with
this policy. It is that other public-repo work may change branch protection,
workflow check names, PR templates, maintainer guidance, or GitHub repository
settings while this branch is still unmerged. Those are the surfaces to inspect
in the nearby-tempdoc and active-worktree audit below.

## Nearby tempdoc and worktree interference audit - 2026-06-28

This tempdoc's filename number is `653`, so the requested numeric window is
`633` through `673`.

Recency scan caveat: the active worktree was created recently, so many clean
tracked tempdocs in that worktree have fresh filesystem `LastWriteTime` values
even though their contents are unchanged. I treated those as checkout timestamp
artifacts, not substantive recent tempdoc modifications. The primary checkout's
Git status and diffs show the actual nearby active tempdoc edits:

- `651-public-ci-feedback-loop-efficiency.md`;
- `652-public-ci-unit-test-latency.md`;
- `653-public-main-history-hygiene.md`.

`634-go-public-cutover-transition.md` also has a recent filesystem timestamp in
the primary checkout, but no Git content change. I still inspected it because it
is the upstream public-cutover context for this tempdoc.

Active worktrees in this repo:

- primary checkout `F:\justsearch-public` on `main`;
- `.claude/worktrees/public-main-history-policy` on
  `codex/public-main-history-policy`.

The only active worktree named for a tempdoc-relevant topic in the `633..673`
range is `public-main-history-policy`, associated with this tempdoc.

### Nearby tempdocs

`634-go-public-cutover-transition` is historical upstream context. It describes
the public cutover, branch-protection setup, fresh public history, and old
snapshot/cutover risks. It should not own current merge-policy decisions now
that ADR-0045 exists. Its long-term interference risk is stale authority: if a
future agent reads only 634, it may treat cutover-era repo-settings notes as
current. The mitigation is already in this tempdoc and ADR-0045: verify live
GitHub settings and use the repo-history verifier for current truth.

`651-public-ci-feedback-loop-efficiency` is now titled "Public CI protected
evidence map." Its recent additions settle a broader CI design: public CI is an
evidence map, with future work around CI digest, trend reporting, build-lane
attribution, and contributor-facing explanation. It explicitly says public
history settings verification belongs to this tempdoc's lane. The overlap risk
is low but real: a future "public repo policy snapshot" or "CI evidence digest"
could be tempted to absorb repo-history policy. The correct boundary is that
651 may consume `check-repo-history-policy` output as one input, but 653 owns
merge/squash/rebase policy, PR-body-as-commit-body risk, and repository history
settings.

`652-public-ci-unit-test-latency` owns the three unit-test shards,
`test-evidence-policy.v1.json`, unit attribution artifacts, parser/PDF/OCR
evidence tiering, and future unit-test budgets or platform experiments. It has
no direct interference with 653. The only shared surface is branch protection:
652 can change required unit-test check names, while 653's verifier preserves
branch-protection publication facts and must not accidentally drop those status
checks when settings are updated. The implementation already preserved the
required status checks while enabling PR publication/admin enforcement. Future
history-policy changes should continue to patch branch protection without
rewriting the required-check set.

`653-public-main-history-hygiene` exists as untracked tempdoc copies in both
the primary checkout and this worktree. The worktree copy is the active,
complete implementation record. The primary checkout copy is older and lacks
the implementation, follow-up, research, and audit sections. The coordination
risk is a file-add collision when this branch is published if the primary
checkout copy is independently edited. The mitigation is to treat the worktree
copy as this branch's source of truth and compare before staging/PR if the
primary checkout changes again.

### Other in-range timestamp artifacts

Clean tracked tempdocs `633` through `650` in this worktree show recent
filesystem timestamps because of worktree materialization. Their titles cover
go-public launch content, evaluation corpus work, retrieval/search quality,
engine performance, connection truthfulness, and capability descriptor
truthfulness. I did not find active worktrees for them and did not find content
diffs in this branch. They do not currently interfere with the remaining 653
work.

The one conceptual neighbor worth remembering is
`650-go-public-capability-descriptor-truthfulness`: it shares the same broad
principle of public projection from working evidence. It does not own Git
history policy, but it reinforces the design's direction: public surfaces should
be true, stable projections, not raw internal process traces.

### Interference judgment

No current nearby tempdoc or active worktree blocks this tempdoc's remaining
work.

The long-term coordination risks are:

- **Branch-protection coupling:** 651/652 may change required CI check names;
  future 653 changes must preserve those names when patching branch protection.
- **Policy snapshot boundary:** 651 may later build a CI/public-policy digest;
  it should call this verifier rather than redefine history-publication policy.
- **PR-template ownership:** 653 must remain the owner of PR-body-as-squash-body
  concerns. Do not reintroduce checklist boilerplate into the PR template from a
  generic contributor UX pass.
- **Untracked 653 collision:** the primary checkout has an older untracked 653
  file. Before staging this work, compare the two copies and keep the worktree
  implementation record unless the primary checkout has newer user-authored
  additions.

The practical next step for 653 remains unchanged: if more work is requested,
the best candidate is a read-only squash-message preview helper. It should stay
scoped to public-history publication UX and should not become a general CI,
branch-protection, or repository-governance framework.

## Long-term design settlement after implementation - 2026-06-28

This section settles the long-term design for this tempdoc's remaining ideas
after re-reading adjacent tempdocs and inspecting the codebase. It is general
design, not an implementation plan.

### Current truth to design from

Live verification still matches ADR-0045:

- `check-repo-history-policy` passes for `eliasjustus/justsearch` on `main`;
- `check-branch-protection` passes and still sees the protected public CI fact
  lanes;
- repository settings are squash-only for normal PR publication;
- squash title/body come from `PR_TITLE` / `PR_BODY`;
- automatic branch deletion is enabled;
- no repository rulesets exist.

That means the core policy is not the remaining design problem. The remaining
problem is the human publication moment: because `PR_BODY` becomes the public
squash commit body, maintainers need a way to see and sanity-check the exact
public artifact before pressing merge.

### Adjacent-tempdoc synthesis

`public-ci-feedback-loop-efficiency` now owns the public CI evidence map. Its
design is fact declaration, evidence production, and evidence presentation. It
may later consume this tempdoc's verifier as one input to a broader public
policy or CI digest, but it should not redefine merge-history policy.

`public-ci-unit-test-latency` owns unit-test evidence and shows the same
discipline one layer inward: facts should be owned, named, and attributed before
being optimized. That argues against adding history "quality gates" before the
actual published artifact can be previewed and understood.

`go-public-capability-descriptor-truthfulness` is the closest principle sibling.
It fixed a public-surface truth problem by extending an existing guard/scope
where possible and avoiding a parallel mechanism. This tempdoc should do the
same: extend the existing repo-history verifier/report style, not create a new
publication-governance subsystem.

`event-sourced-tempdoc-current-state` names the projection-vs-fork shape in a
document-process context. This tempdoc is a Git-history instance of the same
shape: branch commits and PR discussion are the append-only working evidence;
`main` receives a projected public history unit. The remaining preview work
should show the projection before it is published.

`go-public-cutover-transition` remains upstream history. It contains older
repo-settings and cutover context, but ADR-0045 plus live verification now own
the current public-main policy.

### Existing design to extend

The codebase already has the right local pattern:

- `scripts/ci/check-repo-history-policy.mjs` declares and verifies remote
  publication settings from `repo-history-policy.v1.json`;
- `scripts/ci/check-branch-protection.mjs` verifies the separate protected
  check-name fact from `workflow-signal-policy.v1.json`;
- `scripts/ci/workflow-signal-health.mjs` shows the report-style pattern for
  reading GitHub state and rendering Markdown/JSON without becoming a gate;
- `scripts/ci/report-unit-test-attribution.mjs` shows that evidence reports can
  be useful as summaries and artifacts while leaving pass/fail owned elsewhere;
- the PR template is intentionally commit-body-safe headings only;
- `MAINTAINING.md` and `.claude/rules/branch-safety.md` already place the
  publication responsibility on PR title/body curation.

There is no existing squash-message preview tool. But the existing small-script
style is usable: parse arguments, fetch GitHub state with `gh`, expose testable
pure functions, support JSON/Markdown where useful, and avoid CI wiring when
maintainer credentials or human judgment are required.

### Correct long-term design

The long-term shape for this tempdoc should be **public-main publication with a
previewable artifact**:

1. **Policy authority.** ADR-0045 and `repo-history-policy.v1.json` remain the
   authority for repository publication settings. The verifier checks that the
   remote settings still match.
2. **Publication source.** The PR title/body are the source of the public squash
   commit title/body. Branch commits are workspace evidence, not public-history
   input.
3. **Publication preview.** Before merge, a maintainer should be able to render
   the exact commit-message artifact implied by the PR title/body and the live
   repo settings. The preview may warn about obvious problems such as empty
   sections, visible checklist syntax, template-only headings, raw "WIP"/retry
   phrasing, or "do not publish" markers, but it should not pretend to judge
   prose quality.
4. **Publication audit.** After enough squash-merged PRs exist, a read-only
   history-health report can sample whether `main` is staying clean: merge
   commits, unexpected multi-commit publications, suspicious template leftovers,
   and PR-number traceability. This should start as audit, not a gate.

This design keeps the scope matched to the actual problem. The repo does not
need another enforcement layer while GitHub settings already enforce the merge
method. It does need a better maintainer UX at the only remaining judgment
point: the public commit message.

If implemented later, the preview should be a sibling of the current verifier,
not a replacement. `check-repo-history-policy` answers "are the remote settings
still correct?" A future preview helper would answer "given those settings, what
public commit message will this PR publish?" Those are different facts with
different failure modes.

### What not to design

Do not add a required CI gate for PR-body prose. Pull-request tokens and fork
permissions are the wrong surface for maintainer-owned publication judgment,
and prose quality cannot be proved mechanically.

Do not restore checklist boilerplate inside `.github/PULL_REQUEST_TEMPLATE.md`.
Because the PR body is now the squash body source, visible process checklists
pollute public commit history. Any checklist belongs in an external command,
saved reply, or maintainer workflow note.

Do not create a general repo-settings framework. The existing sibling policies
are clearer because each has a distinct reason to change: workflow facts,
branch-protection checks, test evidence, and history publication.

Do not enable auto-merge or rulesets as part of this design unless a later
problem requires them. Auto-merge would increase the importance of preview, but
it does not solve preview. Rulesets may become useful for exception handling,
but current repository-wide squash settings are sufficient.

Do not design release-note automation yet. Curated squash bodies may later seed
release notes, but release cadence and release-note ownership are separate
problems.

### Design reach

The broader principle is **publication artifact preview**:

> Before a working surface becomes a durable public artifact, show the exact
> projected artifact and verify its source/shape without turning the whole
> publication process into a new framework.

This principle already exists elsewhere in the system:

- `check-repo-history-policy` previews/verifies remote publication settings;
- `check-branch-protection` previews/verifies protected CI fact names;
- public CI fact lanes project raw workflow internals into stable public checks;
- unit-test attribution projects raw JUnit XML into readable evidence;
- capability truthfulness projects public prose from verified product facts;
- cutover preflight projected many one-shot flip risks into a single go/no-go
  surface.

Candidate future scope:

- squash commit messages before PR merge;
- CI evidence digest before treating a run as understood;
- release notes before tag publication;
- root README claims before launch/announcement;
- installer/update metadata before public release;
- repository settings snapshots before governance or launch milestones.

Known current gaps:

- There is no preview of the exact squash commit body before merge.
- The PR template is safe, but it does not help maintainers fill the body well.
- The future "history health" question has no report yet because too few
  post-policy squash commits exist.
- The primary checkout still has an older untracked copy of this tempdoc, so
  publication of this branch should compare tempdoc copies before staging.

Do not build the generalized structure now. The immediate future need, if any,
is only the PR/squash-message preview helper. The broader principle is worth
recording because it aligns 653 with the existing public-evidence and
projection seams, but the general framework is not required by this tempdoc's
remaining scope.

## Squash-message preview implementation - 2026-06-28

Implemented the remaining v1 maintainer UX as a read-only helper:
`scripts/ci/preview-squash-message.mjs`.

The helper previews the PR title/body that GitHub will use as the default
squash commit title/body under the current `PR_TITLE` / `PR_BODY` repository
settings. It reports the proposed public commit title, body size, a short body
preview, live settings context, and advisory warnings for obvious publication
problems:

- non-`PR_TITLE` / non-`PR_BODY` squash settings;
- empty title/body;
- very long PR bodies;
- generated HTML details blocks or HTML comments;
- visible checklist syntax;
- empty PR-template headings;
- missing testing or verification signal;
- WIP, draft, or do-not-publish markers in the title/opening body.

The helper is intentionally not a CI gate. Warnings are review prompts and the
command exits nonzero only for usage, fetch, or parse errors. This keeps
publication judgment with the maintainer while making the public commit-message
artifact visible before merge.

Added fixture coverage in `scripts/ci/test-preview-squash-message.mjs` for clean
human PR bodies, empty templates, generated Dependabot-style bodies, non-policy
repo settings, missing bodies, and the scoped WIP heuristic that avoids matching
incidental release-note text like "draft a new release."

Maintainer guidance now points noisy or dependency PRs at the preview helper
before squash merge, and `CLAUDE.md` lists the helper as the relevant
maintainer-run check for PR title/body publication shape.

## Squash-message preview edge-case fixes - 2026-06-29

Tightened two v1 preview edge cases found during critical review.

First, `missing-testing-signal` now looks for first-party testing evidence only:
a non-empty `## Testing` section or a top-level `Testing:` / `Tests:` /
`Verification:` label. It no longer treats generic words such as "tested" or
"verified" anywhere in the body as sufficient, because generated dependency
release notes can contain those words without saying anything about this repo's
verification.

Second, Markdown output now chooses an outer body-preview fence longer than any
backtick run in the previewed PR body. This keeps `--md` readable even when the
PR body preview contains its own fenced code block.

## Axis-2 internet research: PR/commit granularity - 2026-06-30

This pass reopens 653 against a problem the implemented slice does **not**
cover. The shipped policy (ADR-0045 + repo settings + verifier + squash preview)
solves what can be called **axis 1**: collapsing a noisy branch transcript into
one curated `main` commit at merge. It is silent on **axis 2**: whether a
trivial or docs-only change should become its own standalone PR / public `main`
commit *at all*. Everything above assumes "one PR = one durable change" and even
worries about PRs being *too broad* (see "Risks and hidden assumptions", second
assumption); nothing here addresses the opposite — a PR that is *too small to be
a public-history unit*.

### Triggering instance

The most recent `origin/main` commit at the time of this pass is
`7d020cd docs(644): catalogue post-implementation future directions (#16)`:
a single-file, docs-only, +240-line append to a tempdoc, self-described in its
own PR body as "all options, nothing prioritised, no code changed". It merged
cleanly as one squash commit — axis 1 worked perfectly — yet it is exactly the
shape the *Boundary* section of this tempdoc warned against: "preserving every
intermediate tempdoc edit … as a **separate public `main` commit**." Squash
tidied *how* it landed; it did nothing about *whether it should have been its
own public unit*. That gap is what this pass researches.

### Research scope and method

Web research over established commit/PR-granularity guidance, docs-vs-code PR
convention, working-notes/RFC placement, and the 2025-2026 AI-agent commit-noise
literature. The question: has the industry already designed a solution for
"trivial / docs-only change becomes a standalone mainline commit", or is 653's
axis-2 problem novel? Findings below; each external claim is cited.

### Findings

**There is no turnkey, named solution — there is a convergent convention
cluster.** Every mature project answers axis 2 with conventions, not a tool. The
cluster:

1. **The PR (not the commit) is the public-history unit, and a PR should have
   exactly one reason to change.** Microsoft's engineering playbook states it
   directly: "the PR should have only one reason to change the project." This is
   ADR-0045's own framing, independently standard — so axis 1 is consensus, and
   axis 2 is simply the same principle applied to *under*-sized changes.
   ([Microsoft code-with-engineering-playbook — Pull Requests](https://microsoft.github.io/code-with-engineering-playbook/code-reviews/pull-requests/))

2. **Working history ≠ published history is an explicit, named practice.**
   Fixup/checkpoint commits during review; rewrite/squash before merge. This is
   653's "branches are workspaces, `main` is the narrative" projection, arrived
   at independently elsewhere. ([epage — Curating Commit History](https://epage.github.io/dev/commits/);
   [Mainmatter — keeping a clean git history](https://mainmatter.com/blog/2021/05/26/keeping-a-clean-git-history/))

3. **Docs ride along with the code they document — don't open a separate
   docs-only PR.** The mainstream answer to "should this doc change be its own
   PR/commit" is *no — fold it into the code PR*. Microsoft: "update the
   documentation to match the changes as part of [creating the PR]"; monorepo
   guidance favors one atomic PR carrying related code + docs together. This is
   the **ride-along** rule, and it is the established convention rather than a
   653 invention.
   ([Microsoft playbook](https://microsoft.github.io/code-with-engineering-playbook/code-reviews/pull-requests/);
   [monorepo.tools](https://monorepo.tools/))

4. **Batch trivial changes; a PR-per-one-liner is recognized waste.**
   Kubernetes' contributor guide and broader consensus: separate PRs for tiny
   fixes are "significant overhead … avoidable"; "batch your changes together."
   By this standard `7d020cd` (a standalone PR for a docs-only tempdoc append) is
   precisely the overhead these conventions exist to remove.
   ([Kubernetes — Pull Request Process](https://www.kubernetes.dev/docs/guide/pull-requests/);
   [HN discussion](https://news.ycombinator.com/item?id=11407485))

5. **Working-design notes frequently do not live on the code mainline at all.**
   The direct analog to tempdocs. The industry split: **ADRs / decision records**
   (current truth) live in-repo; **RFCs / design docs / working notes** are often
   kept in a *separate* knowledge base (Confluence, Notion, Google Docs, separate
   repo) *specifically so design churn never pollutes code history*. Uber and
   others are cited doing exactly this. Mapped onto JustSearch this is the
   canonical-docs (in-repo, durable) vs. tempdocs (archaeology) boundary — and it
   raises a genuine road-not-taken: tempdoc churn arguably should not be mainline
   *code-history* commits in the first place.
   ([Pragmatic Engineer — RFCs and Design Docs](https://blog.pragmaticengineer.com/rfcs-and-design-docs/);
   [Scheufler — Documenting design decisions with RFCs and ADRs](https://brunoscheufler.com/blog/2020-07-04-documenting-design-decisions-using-rfcs-and-adrs))

6. **Agent-era guidance (2025-2026) confirms the problem and the fix shape: build
   constraints into the workflow, do not expect agent self-regulation.** "git
   hygiene around AI-generated code is a disaster — agents don't think about
   branch strategy, commit granularity, or review ergonomics unless you build
   those constraints into the workflow"; remedies are per-agent feature branches,
   never committing to a shared branch, and squash on merge. DORA's 2025 data
   quantifies the pressure: a 90% rise in AI adoption correlated with ~+154% PR
   size and ~+91% review time. The agent variant does not change the answer; it
   raises the stakes for *having* the convention.
   ([buildmvpfast — Git workflow for AI-assisted development 2026](https://www.buildmvpfast.com/blog/git-workflow-ai-assisted-development-agent-commits-2026);
   [Developers Digest — what HN gets right about AI coding agents 2026](https://www.developersdigest.tech/blog/what-hacker-news-gets-right-about-ai-coding-agents-2026))

### What this means for 653

- **Nothing needs inventing.** Axis-2 mitigations are conventions the industry
  already settled; 653 can cite Microsoft/Kubernetes rather than argue from first
  principles. This matches the doc's standing posture — smallest native
  mechanism, no bespoke framework.
- **The gap is the convention half, not a missing tool.** The shipped settings
  enforce *how* a PR lands; they cannot judge *whether a change deserves a public
  unit*. That judgment is what conventions 3 + 4 supply.
- **Distinguish the two doc classes precisely**, or the convention over-blocks:
  canonical docs (`docs/{explanation,reference,how-to,decisions}`) are durable
  current truth and a standalone update **is** a legitimate public unit; tempdocs
  + observations (`docs/tempdocs/**`, `docs/observations*`) are dated archaeology
  and should **ride-along or batch**, not stand alone on `main`.
- **No CI gate.** Consistent with the existing "what not to design" guidance and
  every external source: granularity is judgment; a gate cannot tell "worth a
  public commit" from "not", and would false-positive on real ADR/decision
  commits. Salience-at-commit-time (a non-blocking hook-hint) is the right tier.
- **Road-not-taken, recorded not recommended (finding 5):** mature orgs keep
  working-design notes off the code mainline entirely. That conflicts with 653's
  explicit *Boundary* ("do not make tempdocs the problem") and the existing
  in-repo tempdoc tooling (tempdoc-numbers gate, `tempdoc-age-hint`), so full
  separation is out of scope here. The 653-compatible reduction of the same idea
  is just: tempdoc edits ride-along/batch rather than each becoming a standalone
  public commit.

### Proposed axis-2 slice (design only)

The smallest forward-only addition, mirroring axis 1's "settings + small native
mechanism + docs" shape — but here the enforceable surface is a hook-hint, not a
GitHub setting:

1. **Convention text** (maintainer/agent-facing): in `MAINTAINING.md` and the
   `branch-safety.md` merge workflow — *docs-only/tempdoc edits ride along with
   the code PR they document, or batch into one periodic `docs(tempdocs): …` PR;
   do not open a standalone PR for a single tempdoc append. Canonical-doc updates
   may stand alone.* Keep `CONTRIBUTING.md` contributor-light (it must not grow
   an agent-history burden).
2. **Salience** (the enforcement tier): a non-blocking commit/PR-time hook-hint
   that fires when a change's diff is *only* under `docs/tempdocs/**` /
   `docs/observations*` (excluding canonical-doc dirs), pointing at the
   ride-along/batch convention. ~85% delivery, never blocks, cannot misjudge
   intent.
3. **Governance bookkeeping:** if the convention is written as an anchored
   `must`/`never` rule, it needs a `tier-register.md` row (tier `hook-hint`,
   resolving to the new hook) plus a `new-rule-registered` changeset citing 653.
   The lighter alternative — rationale in MAINTAINING/agent-guide with the
   hook-hint as the registered enforcement — avoids minting a new always-loaded
   must-rule and is preferred unless a hard rule is wanted.

This pass is research + design only: no convention text, hook, or settings
change is implemented here.
