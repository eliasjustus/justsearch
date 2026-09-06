---
title: "Runtime-client publication readiness and capability-realization closeout"
type: tempdocs
status: "REPOSITORY PUBLICATION AUTHORIZED; NPM RELEASE DEFERRED (2026-09-05)"
created: 2026-09-05
updated: 2026-09-05
parent: 899-project-operations-onboarding
related:
  - 654-local-runtime-contract-and-product-center
  - 660-plugin-sdk-community-onramp
  - 899-project-operations-onboarding
---

# 934 — Runtime-client publication readiness

## Purpose

Tempdoc 899 shipped and published the repository-side project-operations work. A subsequent
capability-realization review found that the contributor onramp, lifecycle projections, and
diagnostic-summary path reach their intended consumers. Three broader outcomes remain partial:
founder-only succession values are not filled, the five starter issues are not open, and
`@justsearch/runtime-client` is not published to npm.

The first two require people or outward GitHub actions and cannot be completed by repository code.
The SDK also has one repository-owned readiness defect: a fresh checkout intentionally has no
`packages/runtime-client/dist/`, but the package has no `prepublishOnly` lifecycle. Tempdoc 899's
design explicitly called for reusing the existing plugin package's publication shape after fixing
its module assumptions. Without that hook, a direct `npm publish` does not itself prove or create
the required distributable files.

## Settled design

Extend the existing package-publication seam rather than adding a new release framework:

1. Give `packages/runtime-client/package.json` the same npm discovery metadata shape already used by
   `packages/plugin-api-ts`: repository subdirectory, canonical homepage, issue URL, useful keywords,
   and public-scope `publishConfig`.
2. Add a `prepublishOnly` lifecycle that checks generated-source coherence, builds and tests the
   Node-20-compatible package, and runs packed-content verification. Keep `check:pack` non-recursive:
   it uses `npm pack --dry-run`, which must not invoke `prepublishOnly`.
3. Make `check:pack` validate the load-bearing publication metadata and lifecycle so a future edit
   cannot silently remove the safeguard while leaving ordinary build tests green.
4. Make the packed README honest and self-sufficient: distinguish repository validation from npm
   installation, tell readers to use installation only when the registry lists a release, explain
   that callers obtain `baseUrl` from the runtime manifest, and link to the canonical
   runtime-manifest and Runtime Contract documents. Do not embed a point-in-time "not published"
   statement in the immutable tarball because publication would make that statement false. Do not
   add a hand-written filesystem discovery API: tempdoc 899 deliberately made `baseUrl` an input to
   the small generated client, and the cross-language data-directory contract already owns
   discovery.
5. Update the canonical Runtime Contract reference with the automatic publication preflight and
   regenerate/check the documentation index.

No existing implementation is superseded. The package's current manual command sequence remains
valid; the lifecycle hook composes it. No generated client, OpenAPI projection, endpoint, runtime
contract, or application behavior changes.

## External-action boundary

- Repository publication was not authorized during implementation. The later explicit `$publish`
  request on 2026-09-05 supersedes that part of the implementation boundary and authorizes the
  ordinary branch-push, pull-request, and merge-queue workflow. It does not authorize publishing the
  package to npm, which remains a separate founder release action.
- Do not open the five starter issues. The existing `good first issue` label and current issue list
  must be rechecked immediately before that separate outward action.
- Do not invent succession roles, custodians, provider modes, or recovery-package locations. A
  founder must fill those values and appoint a second maintainer before operational succession is
  real.

## Design reach

The narrow reusable principle is: **a publishable package must make its own required artifact and
fail closed at the package-manager publication boundary**. Documentation that tells a human to run
several commands is useful verification guidance but is not a publication guard. This principle
already applies to `packages/plugin-api-ts`; 934 brings the runtime client to the same seam without
building generalized release tooling.

Evidence that the principle earns its keep is a dry-run publication from a fresh package checkout
where `dist/` starts absent, the lifecycle creates it, tests run, and the packed manifest contains
the expected exports and metadata. Retire the package-local lifecycle only if a future single
repository package-release orchestrator invokes equivalent checks unconditionally and proves that
individual package publication cannot bypass it.

## Derisk results

The focused confidence-building pass stayed read-only apart from this tempdoc:

- A fresh worktree at public `origin/main` had no `packages/runtime-client/dist/`; the directory is
  intentionally ignored and no built files are tracked.
- `npm pack --dry-run --json --ignore-scripts` exited 0 and reported a four-file tarball containing
  only `LICENSE`, `NOTICE`, `README.md`, and `package.json`. It omitted every path named by the
  package's `exports`, proving that npm's default packaging path does not fail closed on the current
  checkout.
- `packages/plugin-api-ts/package.json` already supplies the repository/homepage/bugs/keywords,
  public-scope publication, and `prepublishOnly` precedent. Extending that shape is lower risk than
  adding a repository-wide publisher.
- The runtime client's existing `check:regen`, `test`, and `check:pack` commands already cover the
  required generation, build/runtime behavior, legal files, and tarball contents. The missing work
  is orchestration and discovery, not a new build system.
- Runtime discovery already belongs to `docs/explanation/23-runtime-manifest.md` and
  `contracts/platform-paths/spec.v1.json`. The README should link that authority and name
  `head.apiBaseUrl`, not copy a platform-specific data-directory path that could drift.

Residual risks and controls:

1. `check:regen` executes the pinned generator and therefore needs Node 22.18 or newer, while the
   generated package supports Node 20 at runtime. The README and publication preflight must state
   that distinction clearly; publication should fail rather than skip coherence on an old Node.
2. `prepublishOnly` must not recurse through `check:pack`. Verify this with a real
   `npm publish --dry-run` from an initially build-artifact-free package after dependencies are
   installed.
3. Package metadata and README promises can drift independently. Extend `check:pack` with exact
   assertions for the publication lifecycle, repository directory, homepage/bugs links,
   `publishConfig.access`, and the README's canonical discovery link.
4. npm publication, public issue creation, and succession appointments remain outside this branch.
   Tests and prose must not imply that those outward capabilities are complete.

**Implementation confidence: 9/10.** The failure is reproduced, the reusable commands and package
precedent already exist, and the change is isolated to package metadata, its packed README/check,
and canonical documentation. Use a balanced-capability Codex model at high reasoning
(`gpt-5.6-terra` where available); stronger capability is unnecessary for this bounded packaging
change.

## Implementation plan

- [x] **Close the package-manager boundary.** Add repository, homepage, bugs, keywords, public
  `publishConfig`, and a cross-platform `prepublishOnly` composition of `check:regen`, `test`, and
  `check:pack`. Preserve ESM-only exports, Node 20 runtime support, and Node 22.18+ generation.
- [x] **Make publication readiness executable.** Extend `check-pack.mjs` to reject missing or stale
  publication metadata/lifecycle and a packed README that does not link the canonical runtime
  discovery documentation. Keep the existing legal-file, forbidden-input, and required-output
  assertions.
- [x] **Repair the packed first-run path.** Add a durable registry-availability section and
  distinguish source validation from npm installation. Explain that a native consumer reads the
  filesystem runtime manifest, takes `head.apiBaseUrl`, and passes it to `createRuntimeClient`; link
  canonical discovery, Runtime Contract, and issue-reporting pages with absolute public URLs that
  work on npm.
- [x] **Update canonical truth.** Amend `docs/reference/runtime-contract.md` to describe the
  fail-closed `prepublishOnly` boundary and package README, then regenerate `docs/llms.txt`. No
  runtime, OpenAPI, route, UI, security, or architectural document changes are needed.
- [x] **Verify from the real boundary.** Install the package's locked development dependencies,
  confirm the worktree starts without `dist/`, and run `npm publish --dry-run` so npm itself invokes
  the lifecycle and produces a complete tarball. Also run focused `check:regen`, package tests,
  `check:pack`, docs index/link/skill checks, and `git diff --check`.
- [x] **Review and close out.** Re-read this tempdoc for design fit, perform a refute-first change
  review with evidence pointers, rerun the no-edit capability-realization audit, record all evidence
  and remaining external actions here, commit explicit paths, and run session closeout. Do not push,
  open a PR, merge, publish npm, or open GitHub issues.

No teardown is required: the manual verification commands remain supported and no generated source,
package export, or prior documentation authority is replaced.

## Implementation evidence

- The initial fresh-worktree `npm pack --dry-run --json --ignore-scripts` reproduced the defect: it
  exited 0 with only four files and no `dist/` exports.
- After implementation, `npm publish --dry-run --json` began with `dist/` absent, invoked the exact
  `prepublishOnly` lifecycle, passed deterministic regeneration, built the client, passed all 7 Node
  tests, and verified a 20-file tarball containing every declared JavaScript and type export. npm
  reported public access and explicitly remained in dry-run mode; no package was published.
- A separate `npm run check:pack` passed with 20 files after the metadata guard was tightened to
  compare repository fields semantically and require the discovery, contract, and issue links.
- `node scripts/docs/llmstxt-generate.mjs --check`, `node scripts/docs/skills-sync.mjs --check`, and
  `node scripts/docs/verify-canonical-doc-links.mjs` passed (`115` indexed docs, `5` generated skills
  from `9` sources, and `155` canonical files after the final rebase).
- `node scripts/architecture/module-deps.mjs --check-canonical` and
  `node scripts/docs/verify-runtime-config-matrix.mjs` passed (`111` YAML files, `251` pairs, `307`
  rows). No module or configuration output changed.
- `npx markdownlint-cli2` reported zero issues across the package README, canonical Runtime Contract,
  and this tempdoc. `git diff --check` passed.

## Review results

The conceptual tempdoc-fit review found no scope mismatch: the implementation closes the
repository-owned publication and discovery defects without changing the generated API or claiming
that founder-owned outward work is complete.

The independent refute-first pass was performed directly because this session exposes no bounded
subagent interface. It challenged the package lifecycle, tarball, documentation lifetime, and
external-state claims from a skeptical stance. It found one substantive documentation defect: the
first README revision embedded a point-in-time statement that version 0.1.0 was unpublished, which
would become false inside the immutable artifact at the instant of publication. The README now uses
a durable rule—install only when npm lists a release, otherwise use the source verification path—
and `check:pack` requires the npm availability link.

After that fix, the final `npm publish --dry-run --json` again invoked `prepublishOnly`, passed
`check:regen`, passed all 7 package tests, and verified all 20 packed files with public access. The
final `check:pack` therefore exercises the tightened repository-field, keyword, lifecycle, and four
README-link assertions. No security- or privacy-sensitive finding was identified.

## Capability-realization result and remaining work

The no-edit capability recheck found the repository-side 934 capability realized. A normal npm
publication now invokes a fail-closed lifecycle that creates and verifies the declared exports; the
packed package points a fresh consumer to npm availability, repository ownership, issue reporting,
the Runtime Contract, and runtime-manifest discovery; and repository contributors have an explicit
verification path. Confidence is 9.5/10, grounded in the real npm dry-run rather than configuration
or compilation alone.

No publication occurred. Three external outcomes remain deliberately outside this branch:

1. A founder with npm scope ownership and 2FA publishes `@justsearch/runtime-client` only after the
   normal release decision and a final registry/version check.
2. A founder rechecks ownership and collisions, then opens the five starter issues recorded in
   tempdoc 899 with the existing `good first issue` label.
3. A founder fills the non-secret custody placeholders in `MAINTAINING.md`, appoints a second
   maintainer, and completes the documented succession dry run.

No additional repository implementation is known to be required for those actions. At implementation
closeout this branch had not been pushed, no pull request had been opened, no issue had been created,
and no npm package had been published.

## Repository publication record

[PR #676](https://github.com/justsearch-app/justsearch/pull/676) is the authorized repository
publication path for this tempdoc. The candidate was rebased onto `origin/main` at `8a919ad1` before
push. The current full local preflight passed public claims (including lockfile completeness),
license and notice consistency, the no-model build, all three JVM unit-test shards, the 2,808-test
jseval suite (95 skipped), the exact repository secret scan, and the npm publication dry run. The
dry run again rebuilt the package, passed all 7 package tests, and verified the same 20-file tarball.

Required hosted checks, the rich review record, merge-queue result, and post-merge `main` CI remain
GitHub-owned publication evidence and are intentionally not copied here as mutable status snapshots.
Landing this tempdoc through PR #676 completes repository publication only. It does not publish
`@justsearch/runtime-client` to npm, create the starter issues, or fill the human custody and
succession assignments listed above.

## Session closeout

The closeout world-state pass discovered that another worktree had claimed tempdoc 933 after this
worktree was created. This tempdoc was therefore renumbered to 934 before handoff; the repository-wide
tempdoc-number check then passed across all active worktrees and `origin/main`.

The session-owned-helper sweep completed successfully and removed nothing. It retained the
ownerless `otlp-sink` by contract and refused to act on a separately registered `ui-shot` process
because its process-table timestamp could not safely establish identity. This session did not start
either helper, and no direct process termination was attempted.
