---
title: "Project operations: cross-platform contributor onramp, contract lifecycle signals, succession, and diagnostic handoff"
type: tempdocs
status: "TAKEOVER + RESEARCH + DESIGN + DERISK COMPLETE (2026-09-03) — implementation not started; SDK deferred on typed-contract coverage"
created: 2026-09-02
updated: 2026-09-03
lane: 887 L17
model: opus (takeover)
parent: 887-improvement-landscape-register
related:
  - 656-five-minute-agent-runtime-onramp   # doctor, tiered onramp, CONTRIBUTING sections
  - 631-go-public-publish-machinery / 633-go-public-launch-content / 634-cutover
  - 654-local-runtime-contract-and-product-center / 655-mcp-conformance-and-capability-policy
  - 660-plugin-sdk-community-onramp        # open since 2026-06-28, unstarted
  - 802-release-artifact-provenance        # SPDX/step-3 owner decision pattern
---

# 899 — Project operations

## Briefing for the agent picking this up

Fresh start. Read this file and 887 Appendix A10 (§10.1, 10.2, 10.5, 10.6) + A3 §3.6. Work in a
worktree. Root community files (`CONTRIBUTING.md`, `MAINTAINING.md`, `SUPPORT.md`,
`.github/`) are public-facing: `check-root-readme` and the docs-lint checks apply. Anything
outward-facing that is not a file in this repo (creating GitHub labels/issues, npm publish) is
**drafted here for the founder**, not executed. Five small PRs.

## Thesis

Contributor onboarding is real but Windows-only and agent-shaped (656): no devcontainer, a
PowerShell-only bootstrap, and a live `good first issue` label with no curated issue list. Bus factor is
one name in CODEOWNERS with no succession document. The runtime contract has a 90-day
deprecation policy but no on-wire signal — a client learns of a removal by reading a doc. There
is no runtime-contract client SDK in any language, and 660 has sat open since June.

## Settled scope (supersedes the initial charter assumptions recorded in §Status)

- **Devcontainer:** `.devcontainer/` on Ubuntu 24.04 with JDK 25 (Temurin), Node 24, Python 3.13,
  and Rust stable; `postCreate` runs the cross-platform bootstrap and doctor. Tier-0 keyword search
  must work inside it. This is a contributor environment, not Linux product-support expansion; no
  GPU expectation and no claim that existing Ubuntu CI proves the child-process smoke.
- **Bootstrap:** add a Node `scripts/setup/bootstrap.mjs` as the post-Node coordinator, reusing the
  JDK and platform-command mechanics already in `scripts/dev/lib/resolve-jdk.cjs` and
  `scripts/dev/prepare-worktree.cjs`. Keep the `.ps1` as the Windows pre-Node implementation.
- **On-wire deprecation:** HTTP — RFC-correct `Deprecation`, optional `Sunset`, and documentation
  `Link` headers projected from `RouteLifecyclePolicy`, plus manifest/OpenAPI projections and CORS
  exposure. MCP — namespaced Tool `_meta`, description fallback, and a versioned experimental
  capability projected from `McpToolSurface`; standard annotations remain untouched. Focused
  closed-world contract/snapshot tests replace the unrelated protobuf `wire` gate.
- **SDK:** decision — **TypeScript first and generated**, but blocked beyond 893's structural
  snapshot until the explicitly enumerated SDK HTTP surface has complete operation ids, request,
  success/error, and trust-boundary shapes plus a source/snapshot coherence gate. Do not hand-write
  a client or treat `/mcp` as a generic OpenAPI JSON-RPC binding.
- **Crash reporting:** no submit path (NON-GOALS). Instead: a "Copy diagnostic summary" action
  that puts an allowlisted, size-bounded Head-local summary on the clipboard for pasting into a
  GitHub issue — user-initiated, no network, no log/ZIP reuse, and no payload-bearing effect journal.

## Scope

1. Devcontainer + bootstrap + CONTRIBUTING update; verify the Tier-0 onramp
   (`scripts/dev/test-onramp-first-success.mjs`) inside the container (Codespaces or local).
2. Five curated starter issues drafted in §Status with file pointers and acceptance criteria; the
   founder opens them using the existing `good first issue` label. No inert label-sync file.
3. On-wire deprecation (HTTP headers/OpenAPI/CORS + MCP extension + docs + tests).
4. `MAINTAINING.md` §Succession skeleton: what a second maintainer needs (signing credential
   mode, release workflow secrets list by *name* only, npm/GitHub org roles, model-registry
   upstream accounts, the private cutover package's existence). Placeholders for values only the
   founder knows.
5. "Copy diagnostic summary" action (typed Head-local allowlist + non-journaling clipboard path) +
   the bug-report template field.

## Acceptance criteria

- Item 1: container builds; onramp test green inside it; link the run.
- Item 3: focused lifecycle, manifest/OpenAPI, CORS, MCP, and doc-sync checks green; orphaned and
  duplicate lifecycle rows fail; a deprecated test route emits the correct applicable headers.
- Item 5: ui-web gates + typecheck/tests; hostile values cannot escape the allowlist or enter the
  journal/persistence/export surfaces, clipboard failure is visible, and 297 redaction coverage is
  extended independently.
- `node scripts/ci/check-root-readme.mjs` and docs-lint green.

## Constraints

- No outward actions (labels, issues, npm publish, secrets) — draft only.
- Non-goals: KPI/telemetry (NON-GOALS), SDK implementation before typed public-contract coverage
  and source/snapshot coherence exist, Linux product support, per-client MCP identity.

## Status

### Takeover investigation and verdict (2026-09-03)

The charter identifies real project-operability gaps, but four premises changed or were too loose:

1. The live GitHub repository already has the standard `good first issue` label (and no open
   issues). A `.github/labels.yml` file would have no consumer: GitHub does not apply that file by
   itself, and this charter does not add a label-sync action. Do not add dead configuration. Draft
   five issues here and use the existing label when the founder opens them.
2. MCP protocol `2025-11-25`, which this runtime pins, does **not** define `deprecated` or `sunset`
   in `ToolAnnotations`. Its closed standard fields are `title`, `readOnlyHint`, `destructiveHint`,
   `idempotentHint`, and `openWorldHint`. Putting lifecycle fields there would make JustSearch look
   standard while actually relying on an extension.
3. The future 893 OpenAPI snapshot is necessary but insufficient for a generated SDK. The current
   route snapshot has 201 routes, only 7 with a response schema (3.5%), and no request-body
   descriptions. Generating now would produce a large, weakly typed client and falsely imply a
   supported HTTP surface much broader than the deliberately small runtime contract.
4. There is no "658 bundle index" to reuse. The diagnostics ZIP is built directly by
   `DiagnosticsServiceImpl`; it includes free-form logs and crash messages/stacks. The existing
   path scrubber has no direct regression test and is not a safe source for clipboard text. The
   clipboard summary must be constructed from an explicit field allowlist.

**LITE-CLASS: no.** This work introduces new cross-platform, HTTP, MCP, service, and frontend
behaviour; it is not pure teardown/rename/config deletion.

**Verdict: GO now, with the corrected scope below.** Implement the devcontainer/onramp, issue
drafts, succession skeleton, lifecycle authorities, and allowlisted diagnostic summary. Do not
implement the SDK yet. The SDK trigger is not merely "893 item 2 landed"; it is "the committed
OpenAPI document describes the request and response shapes of the enumerated public-contract HTTP
operations well enough for a compile-tested generated client." Keep the wider plugin-authoring
onramp in 660 distinct from this future runtime client.

The cheapest decisive evidence is already partly present:

- **Need:** no `.devcontainer/`, no cross-platform setup coordinator, no succession section, no
  lifecycle metadata in HTTP/MCP, and no clipboard diagnostic summary exist on this base.
- **HTTP/MCP shape:** one fake deprecated route and one fake deprecated tool can prove positive
  projections without a live stack; deliberate duplicate, orphan, and misspelling fixtures prove
  the catalogs fail closed.
- **SDK no-go:** the 3.5% response-schema coverage and absent request bodies already invalidate a
  useful generated client; no speculative generation experiment is required.
- **Container:** one `devcontainer build` followed by the Tier-0 onramp is still required. This host
  has neither Docker nor the `devcontainer` CLI, so that proof does not exist yet.
- **Diagnostic privacy:** a hostile fixture containing absolute paths, exception messages, tokens,
  and oversized values should produce only allowlisted, bounded output. This evidence does not
  exist yet and is the highest-value pre-merge negative test.

What this displaces or extends:

- `bootstrap.mjs` becomes the cross-platform **post-Node coordinator**; it does not delete or hide
  `bootstrap-node-win.ps1`, which remains the Windows pre-Node entry point.
- `RouteLifecyclePolicy` follows the existing `RouteCapabilityPolicy` seam. The live route manifest
  and OpenAPI are projections, not new authorities.
- MCP lifecycle metadata extends `McpToolSurface`'s catalog and names itself as a JustSearch
  extension; it does not misuse standard `annotations`.
- The diagnostic action extends `DiagnosticsService`, `CoreOperationCatalog`, the existing Help
  surface operation mount, and the existing clipboard utility, but it must not put the summary in
  the payload-bearing persistent Effect journal. It adds one awaitable, non-payload-journaling copy
  action rather than a parallel raw fetch/button path.
- `.github/labels.yml` is deleted from the design because it would duplicate live GitHub state with
  no synchronizer.

### Research pass (2026-09-03)

Internet research was warranted because the HTTP deprecation RFC is recent, MCP changed protocol
eras in 2026, and devcontainer feature availability is live infrastructure. Only primary standards
and upstream project sources were used; no external code or text is copied into the repository.

| Subject | Finding | Design consequence |
|---|---|---|
| HTTP deprecation | [RFC 9745](https://www.rfc-editor.org/rfc/rfc9745.html) defines `Deprecation` as an RFC 9651 Structured Field Date, e.g. `@<epoch-seconds>`; `Deprecation: true` is not the final RFC syntax. | Correct `docs/reference/contracts/api-evolution.md`, which currently says `Deprecation: true`; store an instant, not a boolean-only marker. |
| HTTP sunset | [RFC 8594](https://www.rfc-editor.org/rfc/rfc8594.html) says `Sunset` is the HTTP date at which a URI is expected to become unresponsive. Deprecation does not automatically imply a known sunset. | `sunsetAt` is optional. Emit `Sunset` only when a removal date is committed; validate it is after `deprecatedSince` and respects the promised window for public-contract routes. |
| Browser visibility | The [Fetch Standard](https://fetch.spec.whatwg.org/#cors-safelisted-response-header-name) does not CORS-safelist `Deprecation`, `Sunset`, or `Link`. | Add those names to `Access-Control-Expose-Headers` for already-allowed origins, with positive and negative security-filter tests. Do not alter Host or Origin admission. |
| OpenAPI | [OpenAPI 3.1](https://spec.openapis.org/oas/v3.1.0) has standard `deprecated: true` and `externalDocs` on an Operation Object and permits `x-*` specification extensions. | Project the standard fields plus `x-deprecated-since`, optional `x-sunset`, and `x-justsearch-replacement` from the route lifecycle authority. |
| MCP 2025-11-25 | The official [schema reference](https://modelcontextprotocol.io/specification/2025-11-25/schema) has no lifecycle fields in `ToolAnnotations`; Tool `_meta` is open, and server capabilities include a namespaced experimental extension area. | Put structured lifecycle data in namespaced Tool `_meta`; advertise the extension in `capabilities.experimental`; add a short description prefix as the human/model fallback. |
| MCP currency | MCP `2026-07-28` removed the initialize handshake in its new stateless era ([upstream changelog](https://github.com/modelcontextprotocol/modelcontextprotocol/blob/main/docs/specification/2026-07-28/changelog.mdx)). | Do not upgrade protocol versions in 899. Design against the repository's pinned `2025-11-25`; a future protocol migration owns how the extension is rediscovered without initialize. |
| Devcontainers | Official Features support Java, Node, Python, and Rust; the Java feature accepts a distribution selector. Repository CI currently pins Python 3.13 and Node 24. | Use Ubuntu 24.04 plus official versioned Features: Temurin 25, Node 24, Python 3.13, Rust stable. Pin Feature major versions and let 893's platform-EOL register own currency. |
| GitHub intake | Read-only `gh label list` on 2026-09-03 found `good first issue` (`7057ff`, "Good for newcomers"); `gh issue list --state open` returned none. | Reuse the live label; draft issues only. Re-check immediately before the founder opens them. |

### Settled design

#### D1. Contributor onramp: a reproducible container plus an honest bootstrap boundary

Add `.devcontainer/devcontainer.json` on Ubuntu 24.04 with official Features for Temurin JDK 25,
Node 24, Python 3.13 (matching CI), and stable Rust. This is an all-repository, CPU-only contributor
environment, not a newly supported Linux product/runtime platform: no GPU feature, model mount,
model download, packaged-app promise, or Tier-1/2 promise. Preserve the canonical Windows-only
product scope unless a separate decision deliberately changes it. `postCreateCommand` runs the
cross-platform bootstrap coordinator and then `node scripts/dev/doctor.mjs`; the terminal message
points to the Tier-0 proof.

`scripts/setup/bootstrap.mjs` is intentionally a **post-Node** coordinator. A Node program cannot
install Node on a machine that has no Node runtime. It composes or extracts the portable mechanics
already owned by `scripts/dev/lib/resolve-jdk.cjs` and `scripts/dev/prepare-worktree.cjs`; it must not
duplicate their JDK discovery or platform-correct command selection, and must not invoke the whole
maintainer-oriented worktree preparer because that also seeds maintainer configuration. It:

- validates the supported Node/JDK/Python core prerequisites and prints one actionable failure at a
  time; Rust is present in the all-repository container but is not a core-bootstrap failure for
  contributors who are not working on the Rust/Tauri shell;
- installs repository-local JavaScript dependencies with the lockfile-preserving command already
  used by CI, and verifies the Gradle wrapper is executable on Unix;
- selects platform-correct wrapper spellings (`gradlew.bat` on Windows, `./gradlew` elsewhere);
- supports a non-mutating `--check` mode for tests and diagnosis;
- never chooses a host package manager or edits a user's shell profile.

Keep `bootstrap-node-win.ps1` as the Windows **pre-Node** entry point and fix its version parsing/
failure reporting while it is touched. Native Linux/macOS users either bring a supported Node
runtime themselves or use the devcontainer; do not pretend a Node script solved the pre-Node
bootstrap problem.
`CONTRIBUTING.md` presents Windows and devcontainer paths side by side and uses platform-neutral
Gradle command notation where possible. The container pins Node 24 and JDK 25 for reproducibility,
while native bootstrap validation preserves the currently documented Node 20+ and JDK 24+ floors;
raising those minimums requires a deliberate contributor-contract update rather than an incidental
container choice.

Verification: build the container, assert all four tool versions, run `bootstrap.mjs --check`, run
the doctor, and execute `scripts/dev/test-onramp-first-success.mjs` inside the container. Record the
Codespaces or local-container run link/output in this section. The smoke must report Tier 0 and a
real keyword result without models or GPU. This is a blocking acceptance gate: current Ubuntu CI
proves assembly/unit coverage, not Linux child-process integration, and the existing onramp smoke is
Windows-only.

#### D2. Community intake: use live labels; publish five bounded issue drafts

Do not create `.github/labels.yml`. The founder opens the following only after a last-minute
ownership/staleness check and applies the existing `good first issue` label. Each issue must state
that architectural invariants and generated files are out of scope.

| Draft title | Starting pointers | Acceptance boundary |
|---|---|---|
| Add `--help` and unknown-option handling to the onramp doctor | `scripts/dev/doctor.mjs` | Establish a side-effect-free main guard/CLI test seam; `--help` exits 0 without probing hardware/files; an unknown option exits nonzero with usage; focused Node tests cover both. |
| Unit-test the onramp tier decision matrix (after the doctor CLI seam lands) | `scripts/dev/doctor.mjs` (`deriveTier`) | Reuse the first issue's main guard/export seam. Cover no artifacts, embedding only, full artifacts with and without verified GPU, and contradictory inputs; production output stays unchanged. Extract only the smallest pure helper needed for testing. |
| Expand URL-grammar fixtures for scalar, array, and enum arguments | `scripts/ci/url-grammar-fixtures/generate.mjs:137`, adjacent README/fixture | Add the missing small deterministic sampler; do not claim an existing schema walker. Cover the named shapes for both operation `inputSchema` and surface `stateSchema`, then pass existing parser/fixture checks. No URL grammar redesign. |
| Add one fabricated non-Markdown document to the Tier-0 onramp corpus | `examples/onramp-corpus/`, `scripts/dev/test-onramp-first-success.mjs` | Add a license-clean plain-text or HTML fixture with a unique token and prove it is indexed/searchable in the existing smoke without models. Do not broaden the extraction matrix. |
| Convert the feature-request template to a validated GitHub issue form | `.github/ISSUE_TEMPLATE/feature_request.md`, `CONTRIBUTING.md`, `NON-GOALS.md`, `SUPPORT.md` | Required problem/alternative fields, scope reminder, no private data prompt, valid YAML, old Markdown template removed, and both direct template links updated without changing security-report routing. |

These drafts are intentionally documentation/tooling/test tasks with tight seams. The tier-matrix
issue is sequenced after the CLI seam to prevent two contributors creating competing main guards.
If any becomes owned by another lane before opening, replace it rather than creating duplicate work.

#### D3. One lifecycle authority, projected to HTTP, OpenAPI, and MCP

**HTTP.** Add `RouteLifecyclePolicy`, parallel to `RouteCapabilityPolicy`, keyed by method + route
pattern. A lifecycle row contains a machine-readable stability class, `deprecatedSince`, optional
`sunsetAt`, optional replacement route, and a documentation URI. A pre-1.0 exception is an explicit
row field containing its rationale and decision-document URI, never an implicit bypass. Construction
validates chronology, requires the exception record when a public-contract sunset is inside the
90-day floor, and rejects exception records on other rows. This lifecycle registry is the initial
machine-readable classification authority for deprecated routes; canonical prose remains the
authority for routes with no lifecycle row until a broader classification projection is justified.
The route manifest adds these nullable fields and bumps its schema version. OpenAPI projects
standard `deprecated: true`, standard `externalDocs`, `x-deprecated-since`, optional `x-sunset`, and
an explicitly named `x-justsearch-replacement` extension.

A single Javalin matched-route filter reads that policy and stamps responses. `Deprecation` uses the
RFC 9745 `@epochSeconds` syntax. `Sunset` uses an IMF-fixdate and is absent when no shutdown date is
committed. Add a `Link: <...>; rel="deprecation"` when documentation is present. Tests use a fake
deprecated route; do not deprecate a production route merely to exercise the mechanism. Expose
`Deprecation`, `Sunset`, and `Link` through CORS for already-allowed origins, with tests proving the
existing Origin and Host rejection paths remain unchanged.

**MCP.** Add a small lifecycle catalog beside the six curated `McpToolSurface` declarations, keyed
by tool name and carrying the same conceptual fields. `tools/list` projects lifecycle data under
top-level namespaced `_meta` keys (`io.justsearch/deprecated`, `io.justsearch/deprecatedSince`,
`io.justsearch/sunsetAt`, `io.justsearch/replacement`) and prefixes the description with a concise
deprecation sentence so clients that discard `_meta` still expose the warning to the model/user.
The initialize result advertises a versioned `io.justsearch/tool-lifecycle` experimental capability
obtained from the surface, keeping protocol transport ignorant of tool details. Standard
`annotations` remain unchanged.

Do not extend the protobuf `wire` gate for this. It governs `contracts/wire` protobuf evolution.
Instead, add focused route-lifecycle and MCP lifecycle contract tests and extend the relevant
snapshot/regen checks. When 893's OpenAPI snapshot lands, its diff gate becomes the HTTP lifecycle
drift guard. An actual deprecated tool requires a tool-surface version change; adding the empty
capability mechanism alone does not. Both lifecycle catalogs are closed-world: construction or a
contract test must reject duplicates and orphaned/misspelled keys, require each entry to resolve to
exactly one live route/tool, and prove each deprecated entry appears in every required projection.

Update `docs/reference/runtime-contract.md`, `docs/reference/contracts/api-evolution.md`, and
`docs/reference/mcp-production-server.md`. The docs must distinguish standard HTTP fields, standard
OpenAPI metadata, and the namespaced JustSearch MCP extension.

#### D4. Succession is a public recovery map, never a secret dump

Add `MAINTAINING.md` §Succession and emergency handover with a table for responsibility, service,
minimum successor role, credential mode, repository secret **name**, recovery/rotation procedure,
and owner placeholder. Link to `docs/how-to/cut-a-release.md` as the single durable release
procedure rather than duplicating its steps. Include:

- GitHub organization ownership, repository administration, rulesets/merge queue, release access,
  and the separate `justsearch-releases` asset repository;
- future/unprovisioned npm organization/package ownership for `@justsearch/plugin-api`
  (publication remains separately gated by 660);
- code-signing mode/provider and the names already consumed by workflows:
  `JUSTSEARCH_CODESIGN_MODE`, `JUSTSEARCH_CODESIGN_PFX_B64`,
  `JUSTSEARCH_CODESIGN_PFX_PASSWORD`, `JUSTSEARCH_CODESIGN_THUMBPRINT`,
  `JUSTSEARCH_CODESIGN_TIMESTAMP_URL`, and `JUSTSEARCH_CODESIGN_COMMAND`;
- release/update keys by name only:
  `JUSTSEARCH_RELEASE_METADATA_PRIVATE_KEY_PEM`, `JUSTSEARCH_TAURI_UPDATER_PRIVATE_KEY`, and
  `JUSTSEARCH_TAURI_UPDATER_PRIVATE_KEY_PASSWORD`;
- release/update repository variables by name only:
  `JUSTSEARCH_UPDATE_ARTIFACT_PUBLIC_KEY`, `JUSTSEARCH_UPDATE_ARTIFACT_KEY_ID`,
  `JUSTSEARCH_RELEASE_METADATA_PUBLIC_KEY_PEM`, `JUSTSEARCH_RELEASE_METADATA_ROOT_PUBLIC_KEY`,
  `JUSTSEARCH_RELEASE_METADATA_ROOT_KEY_ID`, and `JUSTSEARCH_RELEASE_DESCRIPTOR_URL`;
- CLA administration (`PERSONAL_ACCESS_TOKEN` as an optional compatibility path, not a current
  requirement), upstream model accounts/terms sources, and the
  existence/location class of the private cutover package without copying its contents.

Every unknown value is a plainly marked founder placeholder. Add a twice-yearly dry-run checklist:
a second maintainer can identify each account, rotate one non-production credential, run the
existing non-tag branch candidate dispatch, and locate the private recovery package. No credential
values, recovery codes, personal emails, account IDs, or private URLs enter Git.

#### D5. Copy diagnostic summary: allowlist construction plus a non-journaling clipboard action

Extend `DiagnosticsService` with a bounded text-summary method implemented in app-services. The
summary is built from typed/structured sources, not from the ZIP, logs, stack traces, exception
messages, environment dumps, or generic regex redaction. Allow only:

- JustSearch build version and runtime-contract constituent versions;
- OS family/version/architecture and JVM version;
- lifecycle state + stable reason codes for Head/Worker/Inference;
- GPU vendor/model and capability tier, with no serial/UUID/device path;
- latest crash timestamp/process/exception **type** only, if a parseable crash report exists;
- a fixed note saying the summary was generated locally and copied only by the user's action.

Use deterministic field order, replace control characters, cap each value and the whole UTF-8
payload (8 KiB target), and omit unknown fields rather than copying arbitrary fallback text. The
service returns the text through a new low-risk, user-audience, **Head-local** core operation that
requires neither Worker nor Inference availability. Test it with both offline and with absent or
malformed optional snapshots. The Help surface mounts that operation next to Export diagnostics;
on success it invokes the existing clipboard utility through a new awaitable action that never
places the text in the persistent Effect journal. Journal only an opaque event (operation id,
outcome, timestamp) if auditability is required, and render success only after clipboard writing
returns true. No network request leaves loopback and no automatic issue opening is added.

Update the bug-report template with a fenced "Diagnostic summary (optional)" field and an explicit
instruction to review before posting. Tests must inject hostile absolute paths, messages, secret-like
strings, control characters, and oversized values and prove none escape. Frontend tests must also
prove the summary never enters journal memory, local storage, effect archives, logs, or receipts,
and that a failed clipboard write cannot produce a success receipt. Separately extend 297's ZIP
redaction regression coverage while this privacy seam is open: the current implementation has no
direct test, and its Windows regex is not sufficient evidence for paths containing spaces or other
edge cases.

#### D6. SDK decision: TypeScript first remains correct; implementation is deferred further

The first runtime client remains TypeScript and must be generated, not hand-written. Package it
separately from `@justsearch/plugin-api`: one is a client for the public local runtime contract; the
other is an authoring SDK for in-process extensions.

Do not start generation when 893 merely commits the current structural OpenAPI. Start only when all
public-contract HTTP operations intended for the SDK have stable `operationId`s, request shapes,
success/error response shapes, and authentication/session requirements in the snapshot. Add an
explicit, reviewed SDK operation allowlist and require 100% coverage of it (not all ~201 internal
routes and not `/mcp` as a generic OpenAPI JSON-RPC binding). Before settling that allowlist, resolve
whether `/api/mcp/token` must be promoted into the public-contract classification because external
MCP clients depend on it. Require a source/live-route-to-snapshot coherence gate as well as the
existing snapshot-to-generated-code gate. The first generated package then needs a compile smoke
and a live compatibility smoke against the advertised runtime-contract version.
LangChain/LlamaIndex adapters remain 660 follow-ons after one real generated client has users.

### Design reach

**Immediate design.** Five implementation PRs remain the right publication shape: (1) container +
bootstrap + contributor docs, (2) issue drafts/intake docs, (3) HTTP/MCP lifecycle authority + docs,
(4) succession skeleton, and (5) diagnostic summary + issue-template field. SDK implementation is
not one of those PRs.

**Broader reach.** This work reveals two reusable principles but does not justify new generalized
frameworks:

1. **Lifecycle metadata is authored once per governed surface and projected into every transport's
   native extension point.** It applies to HTTP routes, MCP tools, and later generated clients.
   Evidence it earns its keep: one forced deprecation change updates headers, route manifest,
   OpenAPI, tool list, and docs from one catalog row, and a missing projection fails a test.
   Retire the local MCP extension if a future pinned MCP specification standardizes equivalent tool
   lifecycle fields that supported clients actually consume.
2. **Shareable diagnostics are allowlisted products, not redacted internal telemetry.** It also
   applies to future issue attachments, support bundles, and clipboard exports. Evidence it earns
   its keep: hostile fixtures cannot surface undeclared fields and adding a field requires an
   explicit code/test change. Retire the separate text builder only if the diagnostics bundle gains
   a typed, privacy-reviewed manifest with the same strict field/size contract; do not retire it in
   favor of free-form log scrubbing.

Do not build a universal lifecycle registry across HTTP, MCP, operations, and configuration now.
Their identifiers, version rules, and consumers differ. Reconsider only after a third surface needs
the same chronology/replacement model and two concrete implementations have shown a stable shared
shape.

### Pre-implementation derisk pass (2026-09-03)

#### Confidence-building plan executed

1. Verify the live GitHub label/issue state and the 893 dependency.
2. Inspect the actual route manifest/OpenAPI coverage, HTTP `wire` gate, MCP schema/protocol handler,
   diagnostics service, operation catalog, Help surface, and clipboard authority.
3. Check devcontainer/tool availability on this host and avoid violating the one-Gradle-build/one-
   stack rule.
4. Convert every corrected assumption into a negative or projection test required before feature
   implementation.

#### Results and residual risks

- **Closed:** the label exists; no sync file is needed. There are currently no open issues to
  collide with, but active worktree churn still requires a re-check before opening the five drafts.
- **Closed:** HTTP syntax and MCP extension placement are settled by primary specifications. The
  repository's current `api-evolution.md` syntax is stale and must be fixed in the lifecycle PR.
- **Closed:** the SDK dependency is measurable. Current response-schema coverage is 7/201 (3.5%) and
  request bodies are absent, so deferral is evidence-based.
- **Closed:** Help already mounts the diagnostics operation and the clipboard utility is reusable,
  but the generic clipboard Effect is not: it journals, persists, and exports the payload. The
  dedicated awaitable action must keep summary text out of every journal/persistence surface and
  propagate clipboard failure before showing success.
- **Closed:** the diagnostic summary must be an allowlist. Crash reports contain free-form message
  and stack trace; those fields are explicitly excluded.
- **Residual:** this host matches the proposed Node 24, JDK 25, and Python 3.13 versions but has no
  Docker/devcontainer CLI; Rust has rustup with no default toolchain. The exact container build and
  Tier-0 proof remain implementation gates, not assumed facts.
- **Residual:** several other sessions were actively running Gradle (including a lane-E headless
  evaluation and separate builds). Per the repository's single-build rule, no Gradle tests or live
  stack probes were started in this pass. Existing focused test seams were identified instead.
- **Residual:** Javalin matched-route behavior and header persistence across normal/error responses
  should be pinned with one minimal test before broad route integration. Use the matched route
  pattern, not the concrete request URI, as the policy key. Browser visibility additionally requires
  explicit CORS exposure without weakening Host/Origin admission.
- **Residual:** generic MCP clients may ignore custom `_meta`; the description prefix is the required
  legibility fallback. This is an interoperability ceiling, not a reason to forge standard fields.
- **Residual:** succession values known only to the founder remain explicit placeholders. Their
  absence does not block the skeleton or secret-name inventory.

#### Bounded subagent review

Two read-only subagents challenged disjoint parts of the design; neither edited files, ran builds,
touched the shared stack, or mutated GitHub:

- A refute-first contract/privacy reviewer found one design blocker: the generic clipboard Effect
  persists and exports its text payload. It also found missing CORS exposure, missing lifecycle
  classification/exception and closed-world validation, ambiguous OpenAPI documentation projection,
  and an offline-availability requirement for diagnostics. D3 and D5 now incorporate all six
  corrections, so the blocker is closed in design but remains an implementation acceptance gate.
- A contributor-operations explorer confirmed SDK generation is a no-go, then tightened container
  platform wording/version alignment, reuse of existing setup seams, issue sequencing, SDK surface
  enumeration/coherence, and succession variables. D1, D2, D4, and D6 now incorporate those
  corrections.

Subagents are useful during implementation only where write ownership stays disjoint: container +
bootstrap, intake/succession prose, lifecycle, and diagnostics can each be separate worktree/PR
chunks. Keep one owner for HTTP+MCP lifecycle because they share projection rules, one owner for the
backend+frontend diagnostic path because privacy is end-to-end, and never assign the two doctor
issues concurrently. Shared-stack operation, outward GitHub actions, publication, and founder-only
succession values remain with the coordinating agent/founder.

#### Confidence and implementation recommendation

| Chunk | Confidence | Difficulty / recommended effort |
|---|---:|---|
| Container + bootstrap | 6/10 | Medium; strongest residual is the unavailable container proof. |
| Issue drafts + succession | 9/10 | Low; writing judgment and founder placeholders only. |
| HTTP/MCP lifecycle | 7/10 | Medium-high; cross-transport projection and Javalin middleware tests. |
| Diagnostic summary | 7/10 | Medium-high; typed composition, an awaitable non-journaling copy path, and adversarial privacy/persistence tests. |
| Generated SDK | 2/10 now | Do not implement; dependency condition is unmet. |

**Overall confidence for the remaining authorized implementation: 7/10.** Use an Opus-class /
high-reasoning implementation pass (for Codex: `gpt-5.6-sol` at high or xhigh) for the lifecycle
and diagnostic PRs; the docs/intake PRs fit a Sonnet-class / medium-effort pass. Keep the five PRs
separate so the container proof, contract projection, founder-input skeleton, and privacy surface can
each be reviewed and reverted independently.

#### Closeout evidence

- `git diff --check`: passed before commit.
- `node scripts/ci/check-tempdoc-numbers.mjs`: passed across 624 distinct tempdoc numbers and 21
  worktrees.
- `node scripts/ci/check-tempdoc-status-staleness.mjs`: report-mode exit 0; it reported unrelated
  stale statuses elsewhere and no 899-specific defect.
- `node scripts/agent-analytics/world-state.mjs`: completed at takeover entry before worktree
  selection; the closeout rerun was unavailable because `gray-matter` was no longer installed in
  either checkout. No dependency install was performed merely to satisfy closeout.
- `node scripts/dev/agent-spawn-sweep.cjs --occasion session-closeout --session-id
  01a06822-4e63-73c2-be47-5f74ef402868`: retained and reported the expected ownerless `otlp-sink`;
  nothing was reaped or refused.
- No Gradle, frontend, container, or live-stack claim is made. Other sessions owned active Gradle
  work, this host lacked Docker/devcontainer tooling, and this takeover changed only the tempdoc.

**BLOCKED ON YOU**

- Nothing for research/design/derisk. During implementation, the founder supplies only the unknown
  succession placeholders and performs the outward GitHub issue creation.

**PROCEEDING / DONE**

- Takeover investigation, external research, corrected design, design-reach judgment, and derisk
  pass are complete. No feature implementation, GitHub mutation, SDK generation, or dev-stack
  takeover occurred.
