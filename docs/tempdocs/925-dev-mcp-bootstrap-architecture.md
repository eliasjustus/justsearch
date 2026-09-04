---
title: "Hermetic bootstrap and truthful failure architecture for the required justsearch-dev MCP"
type: tempdocs
status: "IMPLEMENTED — verification complete 2026-09-04; ready for review"
created: 2026-09-04
updated: 2026-09-04
author: Codex session 01a06b3b
category: agent-process / dev-tooling / mcp / worktrees
related:
  - 670-worktree-local-config-bootstrap-gap
  - 844-dev-mcp-surface-audit-and-prune
  - 899-project-operations-onboarding
  - 920-codex-cli-dual-harness-migration
---

# 925 — Hermetic bootstrap and truthful failure architecture for the required justsearch-dev MCP

## 1. Trigger and scope

Codex failed to create a task with:

```text
Error starting chat
error creating thread: Fatal error: Failed to initialize session: required MCP
servers failed to initialize: justsearch-dev: handshaking with MCP server failed:
connection closed: initialize response
```

This failure class has occurred before. The immediate incident was repaired by restoring the
root npm installation, but a fresh Codex worktree reproduced the same startup failure on
2026-09-04 without any repository mutation. This document therefore treats the recurrence as an
architecture problem rather than an isolated dependency-installation mishap.

The subject is the **development** MCP server registered as `justsearch-dev`, not the product MCP
endpoint exposed by the running JustSearch application. The scope includes:

- project configuration and harness startup semantics;
- dependency/package placement across clones and worktrees;
- the pre-protocol bootstrap and diagnostic boundary;
- initialization tests and deployment-topology fidelity;
- truthful state/error reporting inside the MCP control plane;
- the boundary between the MCP facade and `dev-runner.cjs`.

The scope does not include changing the twelve-tool product of tempdoc 844, weakening shared-stack
ownership, or making the server optional merely to suppress startup failures.

## 2. Acceptance contract for this decision pass

- [x] Reproduce the failure from a clean worktree using the exact committed Codex launcher.
- [x] Theorize multiple solution directions without prematurely selecting one.
- [x] Research relevant protocol, packaging, Node, Codex, and repository precedents from primary
      sources; distinguish facts from inferences.
- [x] Design one coherent end state, including superseded paths and verification.
- [x] Derisk the design with targeted experiments and a confidence/model recommendation.
- [x] Obtain owner approval before implementation (`proceed autonomously`, 2026-09-04).
- [x] Implement the approved design with runnable regression tests and real-client acceptance.

## 3. Confirmed facts before theorization

### 3.1 Deployment chain

The current chain is:

```text
Codex task creation
  -> .codex/config.toml (`required = true`)
  -> inline Node launcher resolves the active Git root
  -> scripts/dev/justsearch-dev-mcp.mjs
  -> static import of scripts/dev/justsearch-dev-mcp/server.mjs
  -> imports @modelcontextprotocol/sdk and zod from root node_modules
  -> McpServer + StdioServerTransport
  -> twelve tool handlers
  -> direct read/probe operations or child invocation of dev-runner.cjs
  -> Head / Worker / inference lifecycle
```

Primary code anchors:

- `.codex/config.toml:4-10` makes the server mandatory and executes source from the active Git root.
- `package.json:33,42` declares the MCP SDK and Zod as root development dependencies.
- `scripts/dev/justsearch-dev-mcp.mjs:13-19` statically imports the server before the `main()`
  rejection handler.
- `scripts/dev/justsearch-dev-mcp/server.mjs:76-77` imports the MCP SDK.
- `scripts/dev/justsearch-dev-mcp/schemas.mjs:1` imports Zod.

### 3.2 Reproduction on the dedicated worktree

Worktree and branch:

```text
worktree: 925-dev-mcp-bootstrap-architecture
codex/925-dev-mcp-bootstrap-architecture
base: the then-current local main; publication was later replayed onto current origin/main
```

Precondition and exact-launcher result:

```text
rootNodeModules=False
sdk=False
Error [ERR_MODULE_NOT_FOUND]: Cannot find package '@modelcontextprotocol/sdk'
imported from ...\scripts\dev\justsearch-dev-mcp\server.mjs
exit code: 1
```

No stack was started and no dependency was installed. This is not a hypothetical missing-package
case: it is the default state of the newly created worktree.

### 3.3 Existing guardrails do not represent that topology

- `scripts/dev/test-dev-mcp-projection-live.mjs:47-49` starts the entry point directly from the
  repository root that is running the test. It succeeds when that checkout already has root
  dependencies.
- `scripts/ci/check-codex-agent-parity.mjs:47-57` validates the config with regular expressions;
  all nine parity checks passed while the exact launcher failed in the new worktree.
- `scripts/dev/prepare-worktree.cjs:96-97` runs `npm ci` only in `modules/ui-web`, despite the MCP
  importing dependencies declared by the root package.
- `docs/how-to/use-codex-for-development.md` describes launching Codex from a checkout or worktree
  but does not establish the root MCP dependency precondition.

The cause of the earlier root checkout's partial/missing npm installation is not established.
That uncertainty does not weaken the reproduced worktree failure.

## 4. Theorization — candidate frames, not decisions

### 4.1 Frame A: dependency bootstrap gap

The narrowest interpretation is that worktree preparation omitted a root `npm ci`. Under this
frame, adding that command to `prepare-worktree.cjs` and onboarding docs appears sufficient.

Attractive properties:

- small change;
- uses the existing lockfile and npm workflow;
- preserves normal Node module resolution and debugging.

Hidden costs and assumptions:

- Codex must initialize the required MCP **before** the agent can run the preparer;
- not every worktree creation path invokes the preparer;
- installation is network/cache/toolchain dependent and mutates hundreds of files;
- `npm ci` deliberately replaces `node_modules`, creating an availability window if the server is
  starting concurrently or the install is interrupted;
- every disposable worktree pays disk/time costs for a tiny control-plane dependency set;
- it converts a deterministic architecture flaw into an operator-discipline requirement.

This direction may be useful as an onboarding improvement, but it cannot by itself establish the
invariant that a required server is present whenever the harness needs it.

### 4.2 Frame B: hermetic per-revision MCP artifact

Build the server and its runtime dependencies into one versioned artifact whose execution does not
depend on worktree-local `node_modules`. Candidate forms include a bundled ESM file, a small
generated directory with vendored dependencies, or a packaged executable.

Potential advantages:

- fresh worktrees can initialize offline;
- source and runtime dependency identity can be tied to the checked-out revision;
- the exact artifact can be tested in CI;
- no session-start package installation or shared mutable dependency directory;
- `required = true` remains an honest safety assertion.

Questions and risks to investigate:

- whether the MCP SDK and Zod bundle cleanly without dynamic or native dependencies;
- generated-artifact size, license/notice obligations, source-map/debug experience, and review
  noise;
- where generation lives and how CI proves the artifact is current;
- whether Node itself is a sufficiently stable prerequisite on all supported Codex hosts;
- whether a bundle can preserve ESM semantics and Windows stdio behavior.

### 4.3 Frame C: one stable machine-level MCP installation

Run a single MCP implementation from a stable location outside disposable worktrees, passing the
caller's repository root as context.

Potential advantages:

- only one installed dependency set;
- lower worktree disk cost;
- one place to patch diagnostics.

Architectural tensions:

- the server version may drift from the worktree's config/tool contract;
- machine-local installation becomes an undocumented second deployment authority;
- callers from different revisions may require incompatible schemas or lifecycle behavior;
- resolving the caller root securely over stdio/config is more subtle than inheriting CWD;
- sharing one process among harness sessions risks coupling process lifetime to shared-stack
  ownership and makes upgrades/restarts harder to reason about.

A stable launcher that executes a **revision-local hermetic artifact** may be viable. A stable,
independently-versioned server appears much harder to reconcile with repository-local truth.

### 4.4 Frame D: tiny dependency-free bootstrap with deferred capabilities

Implement the MCP handshake and a minimal diagnostic surface using only Node built-ins, then load
the full tool server after initialization or expose repair guidance when it is unavailable.

Potential advantages:

- the client receives a protocol-shaped explanation instead of EOF;
- bootstrap diagnostics can identify Node version, repository root, artifact state, and remedies;
- task creation can remain available in a constrained mode.

Risks:

- hand-implementing MCP framing/protocol behavior duplicates the SDK and may drift;
- an initialized but partially capable `required` server can make the requirement semantically
  ambiguous;
- tool-list changes after initialization may not be supported by every client;
- a repair tool that runs `npm ci` would introduce network, concurrency, and supply-chain mutation
  into the most privileged startup boundary.

A built-ins-only **diagnostic launcher** is attractive even if it exits. Reimplementing a durable
mini-MCP protocol is not attractive unless primary-source protocol constraints make it necessary.

### 4.5 Frame E: make the MCP optional

Set `required = false` so Codex tasks start when the server fails.

This optimizes task availability but weakens the reason this project made the MCP required: agents
could proceed without the shared-stack ownership, truthful health, and sanctioned lifecycle tools.
The failure would become easy to ignore and raw-process workarounds would reappear. Optionality may
be appropriate only if an independent mandatory safety substrate enforces the same constraints.
It is not, by itself, a root-cause fix.

### 4.6 Frame F: separate safety admission from convenience tooling

The current twelve-tool server combines mandatory shared-stack arbitration with convenience reads,
API calls, logging, and hot reload. One theoretical direction is a tiny, highly available mandatory
admission service plus an optional richer development tool server.

Potential advantages:

- the mandatory trusted computing base becomes smaller;
- convenience-tool dependency failures do not block task creation;
- ownership logic receives an explicit architectural boundary.

Potential costs:

- two servers, two schemas, and cross-server lifecycle/state coordination;
- a new representation of ownership unless both consume the same authority;
- agents may use convenience mutations without passing admission unless enforcement sits below
  both surfaces;
- the split could be ceremony without availability benefit if both still depend on the same npm
  installation.

This idea should survive only if research shows a real isolation or availability boundary. It must
not fork the ownership authority established by tempdocs 271/542/844.

## 5. Broader principles surfaced by theorization

1. **Required extensions must be bootable from the minimum supported checkout state.** A required
   pre-agent service cannot depend on an agent-run post-checkout preparation step.
2. **Revision affinity and installation stability are separate axes.** The server should match the
   checked-out contract without depending on a mutable per-worktree package tree.
3. **The diagnostic boundary must begin before the first fallible import.** A catch after a static
   import is not a bootstrap error boundary.
4. **Healthy, absent, and unknown are distinct states.** Broad catches must not translate
   unreadable/corrupt state into `OK`.
5. **Tests must reproduce deployment topology, not merely call the same source file.** The exact
   committed launcher in a dependency-free worktree is part of the product contract.
6. **Availability must not be bought by silently removing safety.** Optionality, fallback, and
   degraded modes need an explicit account of which invariants remain enforced.

## 6. Questions handed to research

- What exactly does Codex guarantee about MCP working directory, environment, startup retries,
  required-server failure, and stderr presentation?
- Does MCP permit a useful initialized degraded mode, late tool-list changes, or structured
  initialization diagnostics that Codex surfaces?
- Which bundling form works with the current MCP SDK/Zod dependency graph and supported Node
  versions, with acceptable license and debugging properties?
- Can a committed bundle be deterministic and guarded against source/lockfile drift?
- How do other repository-owned required tools handle worktree-independent bootstrap?
- Are dependency junctions, shared npm stores, `NODE_PATH`, or a main-checkout package installation
  reliable enough on Windows and across Codex CLI/IDE/desktop to be architectural dependencies?
- Which broad catches in `preflight` and `quick_health` are safe negative probes versus false-health
  conversions, and is there already a shared typed state reader to reuse?
- Where should the authority boundary lie between the MCP facade and `dev-runner.cjs`?

## 7. Theorize-phase conclusion

The strongest hypothesis is that the required MCP should remain revision-affine but cease depending
on worktree-local installation state: a checked, hermetic per-revision runtime plus a built-ins-only
diagnostic launcher. That is deliberately not yet the design. Research must test its packaging,
protocol, licensing, and harness assumptions and compare it honestly against the simpler root-install
and split-server alternatives.

## 8. Research pass — primary sources and repository precedents

Research was warranted because Codex MCP startup semantics, MCP lifecycle behavior, and Node
packaging are external and actively evolving. This pass used vendor/specification documentation and
the installed package metadata; community posts were not used as decision evidence.

### 8.1 Codex makes availability an explicit architectural choice

OpenAI's current MCP configuration reference says:

- a stdio server has `command`, optional `args`, `env`, forwarded variables, and optional `cwd`;
- `startup_timeout_sec` defaults to 10 seconds;
- `required = true` makes Codex startup fail when the enabled server cannot initialize;
- required servers use their full startup timeout, while the separate optional-server grace applies
  only to optional servers.

Source: [OpenAI — Model Context Protocol configuration](https://learn.chatgpt.com/docs/extend/mcp?surface=cli#configure-with-configtoml).

The observed fatal task-creation error is therefore intended Codex behavior, not an accidental UI
interpretation. Raising the timeout cannot repair a process that exits immediately on import.
Removing `required` would deliberately change the project's availability/safety policy.

The same documentation permits an explicit `cwd`, but a fixed main-checkout `cwd` would violate
revision affinity and the existing worktree contract. The committed launcher correctly finds the
caller's Git root; the defect is that the runtime dependencies are not present there.

### 8.2 MCP cannot guarantee that bootstrap stderr reaches the user

For stdio transport, the MCP specification requires newline-delimited JSON-RPC on stdout and permits
arbitrary UTF-8 logs on stderr. The client may capture, forward, **or ignore** stderr. Source:
[MCP 2025-11-25 — Transports](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports#stdio).

The lifecycle requires initialization to be the first interaction and requires the server to answer
the client's `initialize` request before normal operation. Initialization errors can be returned as
JSON-RPC only if enough server runtime exists to parse and answer the request. Source:
[MCP 2025-11-25 — Lifecycle](https://modelcontextprotocol.io/specification/2025-11-25/basic/lifecycle#initialization).

Consequences:

- moving the catch before the first import improves stderr and exit diagnostics, but stderr alone is
  not a portable user-facing contract;
- a missing generated artifact should still exit promptly because there is no safe full server;
- an optional durable diagnostic file under a known repository-local `tmp/` path can supplement
  stderr without contaminating stdout;
- implementing a parallel dependency-free JSON-RPC server solely for diagnostics would enlarge the
  protocol authority and should be avoided.

The specification supports tool-list change notifications. However, OpenAI's documentation only
promises building the initial tool catalog during startup; it does not promise that every Codex
surface refreshes a model-visible tool catalog after a late server repair. A "boot empty, install,
then announce twelve tools" design would therefore rely on client behavior not in the Codex
contract. MCP source:
[MCP 2025-11-25 — Tools/list changed](https://modelcontextprotocol.io/specification/2025-11-25/server/tools#list-changed-notification).

The 2026-07-28 MCP release candidate further changes transport/lifecycle shape. That ongoing
evolution is additional evidence against maintaining a hand-written protocol subset when the
official SDK already owns compatibility.

### 8.3 Startup installation is intrinsically non-atomic

The npm CLI reference specifies that `npm ci` removes an existing `node_modules` before installing
the locked tree. It installs the entire project and never adds only one dependency. Source:
[npm CLI — npm ci](https://docs.npmjs.com/cli/v11/commands/npm-ci/#description).

The root package contains many unrelated development dependencies. Using root `npm ci` as MCP
startup or repair therefore:

- creates an explicit no-dependencies window;
- couples control-plane availability to the whole developer tool graph;
- cannot be an atomic minimal repair;
- requires cache/network/tool availability before the agent session exists;
- can interfere with another process importing from the same checkout.

Adding root installation to an explicit developer bootstrap can still improve general checkout
readiness. It cannot satisfy the required-MCP boot invariant.

### 8.4 Shared module resolution is not a sound ESM escape hatch

Node's ESM documentation states that `NODE_PATH` is not used to resolve `import` specifiers. Source:
[Node.js — ECMAScript modules, no NODE_PATH](https://nodejs.org/api/esm.html#no-node_path).

Symlinking or junctioning every worktree to the main checkout's `node_modules` would technically
make resolution possible, but would create a cross-revision mutable dependency authority. A main
checkout `npm ci` would remove the target beneath every running worktree server. It also inherits
the Windows junction teardown hazards the repository already treats specially. This option fails
both isolation and availability even before portability is considered.

### 8.5 A JavaScript dependency bundle fits the actual import boundary

The dev MCP's source graph has only two third-party runtime imports:

- `@modelcontextprotocol/sdk/server/mcp.js` and `/server/stdio.js` in `server.mjs`;
- `zod/v4` in `schemas.mjs`.

Everything else imported by the MCP source is a Node built-in or repository-local file. The installed
SDK is 1.29.0, declares Node `>=18`, is MIT-licensed, and has Zod as a required peer dependency. Zod
4.4.3 is also MIT-licensed. The SDK's own installation documentation names exactly these two
packages. Sources:

- installed `node_modules/@modelcontextprotocol/sdk/package.json` and `LICENSE`;
- installed `node_modules/zod/package.json` and `LICENSE`;
- [official MCP TypeScript SDK v1.x](https://github.com/modelcontextprotocol/typescript-sdk/tree/v1.x#installation).

This permits a narrower artifact than bundling the 2,885-line application server: generate one
third-party runtime module that exports `McpServer`, `StdioServerTransport`, and the Zod v4 API.
Repository-local application modules remain ordinary readable source and retain their current
edit/restart behavior.

esbuild's primary documentation establishes that bundling recursively inlines analyzable imports;
`platform: "node"` preserves Node built-ins; `packages: "bundle"` explicitly opts package imports
into the bundle; and legal comments can be emitted to a linked legal file. Sources:

- [esbuild — Bundling](https://esbuild.github.io/api/#bundle)
- [esbuild — Platform](https://esbuild.github.io/api/#platform)
- [esbuild — Packages](https://esbuild.github.io/api/#packages)
- [esbuild — Legal comments](https://esbuild.github.io/api/#legal-comments)

The imports in the proposed dependency entry module would all be string literals, avoiding
esbuild's documented non-analyzable-import limitation. Actual SDK bundlability remains an experiment
for the derisk phase, not a research claim.

### 8.6 A native single executable is disproportionate

Node single-executable applications can embed a bundled script, but the facility remains Stability
1.1 (active development). Building is tied to the Node binary version; code cache/snapshot output is
platform-specific; and signing/distribution become platform concerns. Source:
[Node.js — Single executable applications](https://nodejs.org/api/single-executable-applications.html).

JustSearch already requires `node` to launch this MCP and all current application code can stay in
plain repository source. An executable would add multi-platform artifacts and supply-chain/signing
work without removing a current prerequisite. It is rejected for this lane unless the JavaScript
bundle experiment fails for a reason a binary actually solves.

### 8.7 Repository precedents favor deterministic generated artifacts

The repository already has two useful precedents:

1. `packaging/mcpb/server/index.js` is a zero-dependency, Node-built-ins-only stdio bridge. It proves
   the repository accepts dependency-free MCP launchers, but it forwards protocol to the running
   product server; it is not a precedent for duplicating this dev server's protocol implementation.
2. `scripts/ci/pack-mcpb.mjs` and `check-mcpb-consistency.mjs` deterministically rebuild, hash, and
   fail on drift. Numerous source-to-generated `--check` scripts use the same projection pattern.

The appropriate adaptation is deterministic generation plus byte-for-byte freshness verification,
not copying the product bridge or hand-maintaining generated JavaScript.

License handling is part of the artifact contract. Bundling MIT dependencies into an Apache-2.0
repository is viable only with their notices/license text preserved and a gate proving the notice
projection covers every bundled package input. esbuild's legal-comment output alone may not include
license files whose source lacks `@license` comments, so the generator should derive notices from
its metafile/package metadata rather than assume comments are sufficient.

### 8.8 Internal state-truth and authority findings

Research of the current server confirms two separate concerns that should not be disguised as the
same fix:

- **Bootstrap availability:** external imports happen before `main()` and before the intended fatal
  handler. The current source-freshness stamp explicitly excludes `node_modules`, so dependency
  identity is invisible after boot.
- **Operational truthfulness:** `readJsonFileNoSymlinks` correctly throws distinct `ENOENT`, file
  shape, symlink, size, read, and JSON parse failures. Several callers erase those distinctions with
  broad catches. `safeReadRunJson` already demonstrates the better local pattern by mapping ENOENT
  separately from `RUN_READ_ERROR`.

The most consequential false-health paths are:

- `preflight`: every `active.json` error becomes `OK (no active.json)`
  (`server.mjs:2010-2035`);
- `preflight`: every inference probe error becomes `OK` (`server.mjs:2047-2059`);
- `preflight`: every llama-resolution error becomes `OK (check skipped)`
  (`server.mjs:2064-2086`);
- `quick_health`: every active/run record failure becomes "No active run"
  (`server.mjs:2119-2148`).

These do not all have equal safety impact: authoritative start admission is rechecked by
`dev-runner.cjs`, while preflight and quick-health are advisory projections. They nevertheless
violate the server's tempdoc-844 invariant that it must not report state it did not verify.

The server's `cli.mjs` boundary is sound: it spawns the repository-local `dev-runner.cjs`, bounds
stderr, enforces timeouts, and requires exactly one JSON line. Lifecycle admission should remain in
the runner. Refactoring should remove duplicate interpretation from the MCP facade, not create a
second runner.

## 9. Research verdict on the theorized options

| Direction | Verdict after research | Reason |
|---|---|---|
| Root `npm ci` in preparation/startup | Onboarding-only; not the invariant | Occurs too late for task creation and removes the old tree before install. |
| Shared main `node_modules`, `NODE_PATH`, junction | Reject | ESM ignores `NODE_PATH`; shared mutable target breaks revision isolation and availability. |
| Machine-level independently-versioned server | Reject | Loses repository revision affinity and creates a second deployment authority. |
| Native Node single executable | Reject for this lane | Active-development, platform/version-coupled, and Node is already required. |
| Dependency-free hand-written MCP subset | Reject | Duplicates an evolving protocol and weakens SDK-owned conformance. |
| Split mandatory arbitration server | Defer | Adds a server/authority boundary without solving dependencies by itself. |
| Full application bundle | Viable but unnecessarily broad | Couples every source edit to regeneration and degrades debuggability. |
| Generated third-party runtime bundle + source server | **Advance to design** | Removes the only external runtime imports while preserving revision-local readable source. |

## 10. Research-phase conclusion

The evidence changes the leading hypothesis from "bundle the MCP" to the narrower "bundle the MCP's
third-party runtime boundary." Keep the application server and `dev-runner.cjs` revision-local and
readable; commit a deterministic generated ESM module containing the SDK/Zod closure; import it from
the source server; and prove freshness/licensing with a generator-backed gate. Pair that with a
built-ins-only dynamic-import launcher and typed state-read outcomes. The design phase must now name
the exact authorities, files, teardown, and acceptance tests.

## 11. Design decision

### 11.1 End-state invariants

1. `justsearch-dev` remains `required = true` for Codex and preserves the existing shared-stack
   safety posture.
2. A tracked checkout at a supported revision can initialize the server with `node` and Git but no
   root `node_modules`, package-manager invocation, network access, main-checkout dependency link, or
   prior preparation step.
3. The running server uses application source and lifecycle code from the caller's checkout; it
   never silently substitutes another worktree's implementation.
4. Third-party runtime code is a deterministic generated projection of the root lockfile and one
   small dependency entry module. CI fails when source, locked dependencies, generated bytes, or
   notices diverge.
5. The first fallible application import is inside a bootstrap error boundary that writes only MCP
   messages to stdout and stable diagnostics to stderr/a best-effort local record.
6. Only proven absence is reported as absence. Unreadable, invalid, and indeterminate observations
   cannot make preflight ready or make quick-health claim the shared stack is free.
7. `dev-runner.cjs` remains the lifecycle/admission authority. The MCP is a typed transport and
   observation facade, not a second orchestrator.

### 11.2 Runtime projection, not an application bundle

Add one hand-authored dependency entry module beside the current MCP source. It imports and exports
only:

- `McpServer`;
- `StdioServerTransport`;
- the Zod v4 namespace used by `schemas.mjs`.

A generator builds that entry with an exactly pinned root `esbuild` development dependency using
Node platform, ESM output, package bundling explicitly enabled, no minification, and deterministic
line endings/order. Its committed outputs are:

- `runtime.generated.mjs` — the SDK/Zod code closure;
- `runtime.generated.LEGAL.txt` — sorted package/version/license texts for every third-party package
  whose source appears in esbuild's metafile.

The generator supports normal write mode and `--check`. Check mode builds into a temporary directory,
requires that every external import is a Node built-in, verifies that every bundled package has an
allowed license and notice text, and byte-compares both outputs. The generated bundle carries a
header naming its generator and the locked SDK/Zod versions; it is never edited manually.

`server.mjs` and `schemas.mjs` import from this generated local module. All other MCP files,
`scripts/dev/lib/*.cjs`, and `dev-runner.cjs` remain ordinary repository source. This keeps source
edits immediate and debuggable; only dependency-entry or lockfile changes require regeneration.

The current self-freshness source set includes top-level `*.mjs`, so placing the generated runtime
beside the source automatically makes its bytes part of the boot stamp. The comment that says
`node_modules` is excluded should be amended to state that third-party runtime identity is represented
by `runtime.generated.mjs`. The legal file need not affect runtime freshness because it cannot alter
behavior; its separate byte-comparison gate owns notice freshness.

### 11.3 Bootstrap and fatal-process boundary

Keep the existing `.codex/config.toml` Git-root resolution because it preserves caller revision and
module-subdirectory launches. Keep `.mcp.json.example`'s revision-local entry for Claude.

Change `justsearch-dev-mcp.mjs` into a Node-built-ins-only bootstrap:

1. establish stable fatal handlers before importing application code;
2. dynamically import `server.mjs` and await `main()`;
3. on failure, classify at least missing generated runtime, unsupported Node, import/syntax failure,
   and server-main rejection under stable `DEV_MCP_BOOT_*` codes;
4. write a concise single-line stderr summary and best-effort JSON diagnostic at
   `tmp/justsearch-dev-mcp/bootstrap-failure.json` in the caller checkout;
5. include code, timestamp, Node/platform versions, caller root, failing module where known, and a
   bounded/sanitized message; never record environment values or credentials;
6. exit non-zero without emitting any stdout.

The diagnostic record is a supplement, not proof that Codex displays stderr. A successful launch
removes an old bootstrap-failure record best-effort so stale evidence is not mistaken for the current
session.

True uncaught exceptions and unhandled rejections after initialization log once and terminate the
server. The current handlers that log and continue are removed. A required control plane restarts
from a known state; it does not survive in an unknown one.

Do not add package installation or a reduced hand-written MCP server to the bootstrap. If the
committed runtime artifact is absent/corrupt, failing loudly is preferable to silently initializing
without the twelve safety/tool contracts.

### 11.4 Truthful observation layer

Add a small repository-local observation module, used by `preflight` and `quick_health`, with two
typed primitives:

- optional-file observation: `PRESENT(value)`, `ABSENT`, `INVALID(reason)`, or
  `UNREADABLE(reason)`;
- loopback probe observation: `REACHABLE(status)`, `REFUSED`, `TIMED_OUT`, or `ERROR(reason)`.

Only `ENOENT` maps to file absence. Symlinks, wrong file type, excessive size, malformed JSON,
permission errors, and unexpected I/O remain distinct. Only a proven connection refusal maps to an
absent local listener; timeout and unexpected socket errors are unknown.

Project these observations as follows:

- `preflight` retains its top-level `ready` convenience value, but adds typed per-check state
  `PASS | FAIL | UNKNOWN | SKIPPED`. Every gating check must be `PASS` for `ready: true`; an error can
  never become `OK`. The optional llama check may be explicitly non-gating but still reports
  `UNKNOWN`, not `OK (check skipped)`, when observation failed.
- `quick_health` represents whether a run exists as `ACTIVE | ABSENT | UNKNOWN`. Its compatibility
  `running` value becomes nullable: `true`, `false`, or `null` when the register cannot be trusted.
  `UNKNOWN` must not produce a free ownership recommendation.
- existing detailed error conventions from `safeReadRunJson` are reused rather than creating a
  parallel error grammar.

The MCP's optimistic start precheck may continue to delegate to `dev-runner.cjs`; the runner's
admission result remains authoritative. The facade must not attach a claim that unreadable state was
absent. No ownership computation moves out of the existing shared CJS authority.

This design requires extracting the shared observations, not splitting every tool handler. The
2,885-line `server.mjs` remains a maintainability concern, but a full tool-module rewrite does not
reduce this incident's risk and is deliberately outside this tempdoc.

### 11.5 Verification architecture

Four proof layers are required:

1. **Generator unit/fixture tests**
   - two generations from identical inputs are byte-identical;
   - `--check` passes on synchronized outputs and fails after source, bundle, or legal-file drift;
   - an external non-built-in import fails generation;
   - an input package without an allowed license/notice fails generation.
2. **Dependency-free deployment-topology test**
   - build a temporary Git checkout outside every ancestor containing `node_modules`;
   - copy the committed config/entry/MCP source, required `scripts/dev/lib` files, and runner marker;
   - assert root `node_modules` and the SDK package are absent;
   - read the actual command/args from `.codex/config.toml` and start from both repository root and a
     module subdirectory;
   - perform `initialize`, `notifications/initialized`, and `tools/list`; assert the twelve expected
     tools and clean exit.
3. **Negative bootstrap tests in disposable fixtures**
   - absent bundle, corrupt bundle, and unsupported runtime produce stable codes, no stdout, nonzero
     exit, bounded stderr, and a sanitized diagnostic record;
   - a later successful launch removes the stale record.
4. **Observation truth tests**
   - missing, malformed, symlink, oversized, denied/unreadable, and valid active/run records map to
     the designed states;
   - refused, timeout, reachable-non-200, and unexpected probe failures remain distinguishable;
   - preflight cannot be ready and quick-health cannot report free on `UNKNOWN`.

Existing `check-dev-mcp-doc-sync` and projection/surface-honesty tests remain. Their healthy-root
coverage is useful; the new deployment-topology test closes the missing dimension instead of
pretending those tests were worthless. `check-codex-agent-parity` should continue checking config
shape, while CI runs the new bundle-freshness and dependency-free handshake tests in the existing
Dev-MCP block after root `npm ci`.

A one-time manual acceptance run starts `codex exec --ephemeral` from a newly created worktree with
no root dependencies and confirms task creation plus `quick_health`. This is acceptance evidence,
not a default CI test because it requires an authenticated model session.

### 11.6 Canonical documentation and governance

Update in the same implementation change:

- `docs/reference/contributing/mcp-dev-tools.md`: bootstrap architecture, generated-runtime
  authority, failure codes/diagnostic path, and supported recovery;
- `docs/how-to/use-codex-for-development.md`: state that the required dev MCP works before root npm
  preparation and distinguish that from dependencies needed for other developer tasks;
- `governance/consult-register.v1.json` and `CLAUDE.md` pre-merge row: name the runtime generator,
  `--check`, dependency-free handshake, and observation-truth suites;
- `.github/workflows/ci.yml`: run those checks in the existing Dev-MCP surface step;
- generated documentation/skill projections only if their canonical inputs change.

Do not add a broad repository-wide artifact framework. Reuse the existing generator `--check`,
deterministic-output, and fail-closed license patterns locally.

### 11.7 Supersession and teardown sweep

The implementation supersedes these exact assumptions and paths:

- direct runtime imports from root `@modelcontextprotocol/sdk` and `zod/v4` in MCP application
  source — replace with the local generated runtime import;
- the static entry import that sits outside the fatal boundary — replace, do not retain a second
  entry path;
- the source-freshness statement that dependency code is unrepresented — update it for the bundled
  projection;
- `OK (no active.json)`, inference-probe `OK`, and `OK (check skipped)` error fallbacks — delete,
  not deprecate;
- uncaught handlers that log and continue — delete in favor of fail-fast handlers;
- tests or docs that imply direct healthy-root startup proves clean-worktree bootstrap — narrow the
  claim while preserving their actual coverage.

Root SDK and Zod development dependencies remain: they are generation inputs and support direct
source tooling/tests. `prepare-worktree.cjs` may independently gain a root-install step under
tempdoc 899 for general development, but tempdoc 925 must neither depend on it nor claim ownership of
that onboarding work.

### 11.8 Explicit non-goals

- changing the twelve-tool inventory or tool descriptions except where typed health outputs require
  documentation;
- implementing the 2026-07-28 MCP release candidate or upgrading the SDK;
- packaging Node itself;
- making the server optional;
- splitting arbitration into another service;
- broad decomposition of `server.mjs` or rewrite of `dev-runner.cjs`;
- solving the unknown historical event that removed the main checkout's dependencies—the design
  removes that event from the MCP availability path regardless of its cause.

## 12. Design reach

### 12.1 Principle: pre-agent hermeticity

**A repository-required extension must run from the minimum supported tracked checkout state.** It
may depend on prerequisites the harness can establish before loading the project (here: Node and Git),
but not on preparation that requires the agent session whose creation it gates.

Candidate scope beyond this tempdoc:

- required MCP servers;
- project hooks that execute before the agent can repair the checkout;
- plugin launchers or policy adapters made mandatory in the future.

Current evidence shows one concrete violation: `justsearch-dev`. Codex hook adapters inspected in
this pass use Node built-ins/repository-local modules and do not yet justify a generalized framework.
The Claude `.mcp.json.example` path benefits from the same fix because it launches this server, but
it is not a second violation.

Evidence that the principle earns its keep:

- the dependency-free checkout test fails if any new bare package import leaks into a required
  startup graph;
- new required pre-agent surfaces can name an equivalent clean-checkout proof instead of inventing
  an installation exception;
- task-creation incidents caused by mutable worktree package state cease after the design ships.

Retirement condition: if Codex/Claude project extensions move to one harness-managed, immutable,
revision-addressed packaging substrate that itself proves clean-checkout startup, fold this local
principle and test into that authority. Do not preserve a parallel `justsearch-dev` bundle/gate once
the broader substrate proves the same invariant.

### 12.2 Principle: negative evidence is not positive health

**Failure to observe unhealthy state is not evidence of health.** This is the same truthfulness seam
tempdoc 844 established, now applied to the filesystem/socket observation boundary.

Likely reach: other `scripts/dev` health/doctor tools and product readiness projections that catch
I/O errors and emit `OK`. This tempdoc will not build a repository-wide observation library; it will
repair the two MCP consumers for which the violation is proven.

Evidence that the principle earns its keep is a negative matrix where permission, corruption, and
timeout cases remain `UNKNOWN` and cannot satisfy readiness. Retire the local MCP observation module
if a canonical cross-tool observation type later replaces it and all tempdoc-925 tests are migrated
to that authority.

## 13. Design-phase conclusion

### 13.1 Actual design

Keep the required, revision-local MCP and its readable application source. Replace its two external
runtime imports with one deterministic, licensed SDK/Zod bundle; move the first application import
inside a built-ins-only fatal boundary; make fatal process errors terminate; and repair preflight/
quick-health through a small typed observation layer. Prove the exact contract from a dependency-free
temporary checkout, not only from an installed repository root.

### 13.2 Reach judgment

The broader insight is "pre-agent hermeticity" for anything that gates task creation, paired with the
existing "do not report unverified state" rule. Both have plausible reach, but only `justsearch-dev`
has a proven bootstrap violation and only its two health paths have proven false-positive projections.
The implementation should remain local and test the principles; general infrastructure is deferred
until a second consumer demonstrates it is needed.

## 14. Derisk pass

### 14.1 Bounded derisk plan

The derisk pass tested the decisions most likely to invalidate the design without implementing the
repository change:

1. bundle exactly the proposed SDK/Zod dependency boundary with a pinned esbuild;
2. transplant that experimental runtime into an otherwise dependency-free copy of the current MCP
   source and perform real MCP initialization through the exact configured launcher;
3. compare static- and dynamic-import bootstrap failures at the process boundary;
4. exercise current record/probe error paths to determine whether the proposed typed observation
   layer repairs a real ambiguity;
5. identify what still requires implementation-time or authenticated-client verification.

All experiment artifacts lived under ignored `tmp/925-derisk` and `tmp/925-clean-fixture`. They are
not proposed source files and are excluded from the implementation diff.

### 14.2 Runtime-bundle feasibility — proven

An isolated npm prefix installed exactly `esbuild@0.28.2`, `@modelcontextprotocol/sdk@1.29.0`, and
`zod@4.4.3`. The experimental entry exported `McpServer`, `StdioServerTransport`, and the Zod v4
namespace, matching design section 11.2. esbuild produced an ESM Node bundle with these measured
properties:

| Property | Result |
|---|---|
| Generated bytes | 1,150,404 |
| esbuild source inputs | 225 |
| Runtime exports | `McpServer`, `StdioServerTransport`, `z` |
| External imports | `node:process` only |
| Two-generation SHA-256 | `CABE9427BB2C53CB3BB465DD80C8F9DA57BE978529D68EAD349CC5830C3B8C58` both times |
| Byte comparison | identical |

The eight package identities actually represented in the metafile were:

| Package | Version | License |
|---|---:|---|
| `@modelcontextprotocol/sdk` | 1.29.0 | MIT |
| `ajv` | 8.20.0 | MIT |
| `ajv-formats` | 3.0.1 | MIT |
| `fast-deep-equal` | 3.1.3 | MIT |
| `fast-uri` | 3.1.7 | BSD-3-Clause |
| `json-schema-traverse` | 1.0.0 | MIT |
| `zod` | 4.4.3 | MIT |
| `zod-to-json-schema` | 3.25.2 | ISC |

This resolves the largest technical uncertainty: the current SDK/Zod closure is pure JavaScript at
this entry boundary, bundles successfully, and leaves no third-party runtime import.

One design detail was also confirmed rather than assumed: `--legal-comments=external` emitted no
legal file for this closure. The implementation **must** derive `runtime.generated.LEGAL.txt` from
the metafile's package identities and their package license files, as section 11.2 requires; it
must not treat esbuild's comment extraction as notice coverage.

### 14.3 Dependency-free deployment topology — proven at protocol level

A minimal Git fixture was placed outside every ancestor containing `node_modules`. It contained the
committed `.codex/config.toml`, current MCP application source, the runner marker and three required
shared CJS modules, plus only the experimental generated runtime. The two bare package imports in a
copy of `server.mjs`/`schemas.mjs` were redirected to that runtime; no other application code was
changed.

From both the fixture root and `scripts/dev/justsearch-dev-mcp/`, the exact inline launcher committed
in `.codex/config.toml`:

- resolved the fixture with `git rev-parse --show-toplevel`;
- started with no resolvable ancestor `node_modules`;
- answered `initialize` and accepted `notifications/initialized`;
- answered `tools/list` with exactly the twelve canonical tool names.

The unmodified clean worktree remains the negative control: both the exact launcher and
`node scripts/ci/check-dev-mcp-doc-sync.mjs` exit before initialization with
`ERR_MODULE_NOT_FOUND` for `@modelcontextprotocol/sdk`. This proves that the proposed artifact, not
an unnoticed machine-global dependency, caused the successful handshakes. It also proves that the
existing doc-sync test is useful surface coverage but not a clean-deployment test.

### 14.4 Bootstrap failure boundary — proven

An import of a deliberately absent server module produced the expected distinction:

| Entry form | Exit | stdout | stderr/diagnostic behavior |
|---|---:|---:|---|
| current-style static import before `main().catch` | 1 | 0 bytes | raw Node stack; the intended catch never ran |
| built-ins-only dynamic import inside `try/catch` | 1 | 0 bytes | stable `DEV_MCP_BOOT_MODULE_NOT_FOUND` plus a JSON diagnostic record |

The experiment validates the proposed boundary without requiring a reduced MCP implementation.
The production implementation still needs bounded message sanitization, atomic/best-effort record
writes, and stale-record removal tests.

### 14.5 State-truth defects — independently reproduced

The observation-layer work is not speculative cleanup. In the dependency-free fixture, an existing
but malformed `tmp/dev-runner/active.json` and a genuinely absent file produced the same public
answers:

- `preflight.checks.noStaleRun: true` with `details.noStaleRun: "OK (no active.json)"`;
- `quick_health.running: false`, `runId: null`, and no record-error state.

The malformed file was therefore reported as proven absence. Separately, the current shared
`httpGetStatusCode` was called against a locally refused port and a listener that accepted a socket
but never answered. Both returned `null`; the caller cannot distinguish `ECONNREFUSED` from timeout.
`preflight` presently turns that undifferentiated result into `noInferenceOrphan: true` / `"OK"`.

These experiments validate the proposed state vocabulary and its projection rules:

- `INVALID`/`UNREADABLE` must not collapse into `ABSENT`;
- `TIMED_OUT`/unexpected socket errors must not collapse into `REFUSED`;
- an unknown gating observation must keep `preflight.ready` false;
- an unknown run-register observation must make `quick_health.running` nullable and suppress any
  free-stack recommendation.

### 14.6 Design refinements from the experiments

The design in section 11 stands with four implementation constraints made explicit:

1. pin root esbuild exactly (the successful probe used `0.28.2`) and set its working directory/
   entry names deterministically so checkout-specific absolute paths never enter generated bytes;
2. derive the legal projection from metafile package inputs plus package license files; esbuild
   legal-comment output is insufficient;
3. treat every external metafile import as an error except an explicit Node-built-in allowlist—the
   measured healthy baseline is only `node:process`;
4. make filesystem and socket operations injectable in unit tests so Windows ACL and timeout cases
   are deterministic rather than dependent on host permissions or network routing.

No experiment supports weakening `required = true`, installing packages during startup, sharing the
main checkout's dependency tree, or adding a hand-written degraded MCP protocol.

### 14.7 Residual risks and implementation gates

| Residual risk | Required implementation evidence |
|---|---|
| Notice projection omits a license file or new transitive package | metafile-to-package coverage assertion, allowed-license test fixtures, byte-checked legal output |
| Generated output depends on checkout path or host line endings | two isolated checkout paths in the generator test, normalized output, byte comparison |
| Nullable `quick_health.running` breaks a consumer that assumed boolean | repository consumer sweep, schema/doc updates, surface-honesty and projection-live tests |
| Failure record leaks a path/message secret or becomes stale | sanitization/bounds unit tests and successful-start removal test |
| Fail-fast handlers terminate on a recoverable handler exception | SDK request-error tests plus a deliberate post-initialization fatal-process test |
| Host probe classifications vary by platform | injected error-code matrix plus Windows CI execution |
| Codex does not surface stderr or refresh as expected | manual authenticated `codex exec --ephemeral` acceptance from a fresh worktree |
| Host Node differs from the repository CI pin | run the complete suite on CI's Node 24.14.0; this probe used Node 24.12.0 |

### 14.8 Confidence and execution recommendation

**Implementation confidence: 8.5/10.** The core packaging choice and the exact clean-checkout MCP
handshake are now proven, and two distinct truthfulness defects were reproduced rather than inferred.
The remaining uncertainty is concentrated in generator/legal hardening, compatibility projection,
and real Codex presentation—not in the architecture's feasibility.

Recommended implementation model/effort: **`gpt-5.6-sol`, high reasoning**. The work is bounded but
crosses generated artifacts, process-failure semantics, schemas, Windows fixtures, CI/governance,
and canonical documentation. Increase to `xhigh` only if the consumer sweep finds more callers of
the boolean `running` contract or license generation requires a repository-wide authority change.

### 14.9 Derisk verdict

**GO for implementation after explicit owner approval.** Implement the section-11 design as one
coherent change with its teardown and proof matrix. Do not split out an availability-only patch
that leaves false-health projections behind: the repeated task-creation failure and the reproduced
state lies are separate defects in the same required control-plane reliability boundary.

## 15. Implementation and verification record

### 15.1 Shipped architecture

The approved design is implemented on `codex/925-dev-mcp-bootstrap-architecture`:

- `scripts/dev/justsearch-dev-mcp.mjs` is now a Node-built-ins-only launcher. It checks Node 24,
  installs fatal handlers before the first application import, dynamically imports `server.mjs`,
  exits promptly after import/main failure even if partial initialization left handles behind, and
  emits one bounded/sanitized stderr line plus the best-effort
  `tmp/justsearch-dev-mcp/bootstrap-failure.json` diagnostic. Successful initialization removes a
  stale diagnostic.
- `runtime-entry.mjs`, `runtime.generated.mjs`, and `runtime.generated.LEGAL.txt` establish the
  revision-local SDK/Zod boundary. `generate-dev-mcp-runtime.mjs` uses exact esbuild `0.28.2`,
  permits only explicit `node:` externals, derives the package/legal closure from the esbuild
  metafile, rejects unapproved or missing license evidence, writes deterministic outputs, and has a
  byte-checking `--check` mode.
- `server.mjs` and `schemas.mjs` import the tracked generated runtime instead of resolving root npm
  packages at startup. Root SDK/Zod/esbuild dependencies remain the generation authority, not a
  task-creation prerequisite.
- `observations.mjs` separates file `PRESENT`/`ABSENT`/`INVALID`/`UNREADABLE` and socket
  `REACHABLE`/`REFUSED`/`TIMED_OUT`/`ERROR`. `preflight.checkStates` is authoritative and every
  gating check must be `PASS`; its legacy booleans map all non-pass states to `false`.
  `quick_health.runState` is `ACTIVE`/`ABSENT`/`UNKNOWN`, and compatibility `running` is nullable.
  A reachable inference listener is called an orphan only when the absence of an owner is proven;
  corrupt ownership state or a reachable-but-unhealthy API yields `null`.
- The existing twelve-tool inventory, shared-stack ownership authority, and runner admission path
  are unchanged. The harness was updated to distinguish `running: null` from `false`.

The committed generated runtime is 1,077,341 bytes with SHA-256
`3f57ea2ae8874ac5492ae11d9e4dd0497b8c6441fc327b114efa2442c48fc16b`. Its exact eight-package
closure is `@modelcontextprotocol/sdk@1.29.0`, `ajv@8.20.0`, `ajv-formats@3.0.1`,
`fast-deep-equal@3.1.3`, `fast-uri@3.1.0`, `json-schema-traverse@1.0.0`, `zod@4.4.3`, and
`zod-to-json-schema@3.25.1`; the only external module is `node:process`.

### 15.2 Guardrails and documentation

The existing CI Dev-MCP block now checks generated freshness/licensing, the exact dependency-free
bootstrap topology, typed observations, canonical tool inventory, handler projection, and ownership
verdicts. The path-triggered `dev-mcp-surface` consult entry names the same recipes.
`docs/reference/contributing/mcp-dev-tools.md` is the canonical shipped-behavior update, while
`docs/how-to/use-codex-for-development.md` documents the fresh-worktree guarantee and recovery
diagnostic.

The design proposed expanding the compact `CLAUDE.md` pre-merge row as well. That wording was
tested and reverted because it exceeded the always-loaded prompt budget; no `CLAUDE.md` content
changed. The exact, path-triggered procedure belongs in the consult register and the executable CI
block, so retaining the compact row avoids creating a second detailed authority.

### 15.3 Evidence

All new and directly affected suites pass after a clean `npm ci`:

- generated runtime: 11/11 from `node scripts/dev/generate-dev-mcp-runtime.test.mjs`, plus
  `node scripts/dev/generate-dev-mcp-runtime.mjs --check`;
- dependency-free bootstrap: 12/12 from `node scripts/dev/test-dev-mcp-bootstrap.mjs`, including
  exact `.codex/config.toml`, root and nested CWD,
  malformed/absent run state, missing/corrupt runtime, unsupported Node, partial-handle
  termination, both post-main fatal paths, stdout cleanliness, bounded diagnostics, and credential
  redaction;
- typed observations: 21/21 from `node scripts/dev/test-dev-mcp-observations.mjs` across JSON/file
  failures, reachable/refused/timeout/error probes, default HTTP port behavior, legacy-adapter
  truth, and inference ownership attribution;
- `node scripts/dev/test-dev-mcp-surface-honesty.mjs`: 78/78;
  `node scripts/dev/test-dev-mcp-projection-live.mjs`: 16 assertions;
  `node scripts/dev/test-ownership-verdict.mjs`: 40/40;
- canonical inventory sync and repository governance pass via
  `node scripts/ci/check-dev-mcp-doc-sync.mjs`,
  `node scripts/docs/llmstxt-generate.mjs --check`,
  `node scripts/docs/skills-sync.mjs --check`,
  `node scripts/docs/verify-canonical-doc-links.mjs`,
  `node scripts/architecture/module-deps.mjs --check-canonical`,
  `node scripts/docs/verify-runtime-config-matrix.mjs`,
  `node scripts/docs/prompt-surface-inventory.mjs`,
  `node scripts/ci/check-premerge-table.mjs`,
  `node scripts/ci/check-workflow-triggers.mjs`,
  `node scripts/ci/check-tempdoc-numbers.mjs`, and
  `node scripts/governance/run.mjs --gate hook-integrity --mode gate`.

The deployment-topology test creates its Git fixture in the OS temporary directory outside every
`node_modules` ancestor and parses the real required launcher rather than duplicating it. The final
authenticated acceptance additionally moved this worktree's root `node_modules` aside, ran
`codex exec --ephemeral` from the worktree, and observed a real `justsearch-dev` `quick_health`
tool call returning `runState: ABSENT` and `running: false`; the Codex process exited 0 with final
sentinel `DEV_MCP_ACCEPTANCE_OK ABSENT false`. The package tree was restored in a `finally` block
and its hold path is absent.

One unrelated repository-wide check remains red on the branch base:
`check-always-loaded-budget.mjs` reports `CLAUDE.md` at 22,626 bytes against a 22,589-byte ceiling
(+37 bytes). This implementation has a zero-byte `CLAUDE.md` diff and did not change the baseline
or weaken the ratchet. It is recorded here so the verification claim does not turn a pre-existing
failure into a false green.

### 15.4 Remaining work and unverified assumptions

No implementation work remains in this tempdoc. Remote CI on the workflow's pinned Node runtime is
not yet evidence because this branch has not been published; pushing, review, and merge require a
separate owner-authorized publication pass. Bootstrap stderr display remains client-dependent under
the MCP stdio contract, which is why the implementation also writes the best-effort local
diagnostic. No claim depends on stderr being presented in the Codex UI.
