---
title: "Local runtime contract and product center: JustSearch's public center is the local agent runtime (desktop = reference client); v1 runtime-contract design — a manifest-native, under-promised stable core (health/status subset + MCP endpoint/tools + runtime manifest) with a coarse contract version, a Docker-style compatibility matrix, and a SemVer-scoped stability policy, projected over already-versioned surfaces rather than a new governed substrate"
type: tempdocs
status: "IMPLEMENTED 2026-07-02 (§Implementation) — v1 Runtime Contract shipped: a manifest `runtimeContract` descriptor projecting single-sourced constituent versions, `serverInfo.version` made the real SemVer tool-surface version, `/mcp` advertised in reachability, canonical docs (explanation 28 + reference), and tests. Product-center RESOLVED 2026-07-01; design §D0–D11; de-risking §U1–U6. Conformance depth → 655, onramp → 656, install modes → 657, trust evidence → 659."
created: 2026-06-28
updated: 2026-07-02
category: product-architecture / runtime-contract / public-positioning
related:
  - 650-go-public-capability-descriptor-truthfulness
  - 501-runtime-manifest-design
  - 500-mcp-protocol-surface
  - docs/explanation/23-runtime-manifest.md
  - docs/reference/api-contract-map.md
---

> NOTE: Noncanonical working tempdoc. Verify against canonical docs and code before
> treating any claim as current truth.

# 654 - Local runtime contract and product center

## Purpose

JustSearch's public story currently has three plausible centers: a Windows desktop search app, a
private MCP retrieval backend for agents, and a broader local knowledge runtime. The recent external
research reports converge on the same pressure: developer adoption becomes easier if external tools
can target one stable object, not a bundle of strong but scattered subsystems.

This tempdoc asks a next agent to design that product boundary. The likely direction is not to demote
the desktop app, but to make the desktop shell the reference client for a versioned local runtime
contract: health/status, runtime manifest, supported API subset, MCP endpoint, compatibility matrix,
and semver/change rules.

## Boundary

This is not an implementation ticket for new endpoints. It is a design pass over product identity and
contract ownership. It should decide what "JustSearch Runtime" means, which existing surfaces belong
to that contract, and which surfaces remain private implementation detail.

Do not duplicate `go-public-capability-descriptor-truthfulness`; that tempdoc owns public descriptor
truthfulness. This note owns the missing product-contract shape behind that descriptor.

## Prior owners to read first

- `go-public-capability-descriptor-truthfulness` for the public "what JustSearch is" drift class.
- `runtime-manifest-design` and `docs/explanation/23-runtime-manifest.md` for the existing runtime
  discovery surface.
- `docs/reference/api-contract-map.md` for current API ownership.
- `mcp-protocol-surface` for the external agent ingress shape.

## First questions

- What is the smallest stable local runtime contract an external tool can rely on?
- Does the runtime contract need its own version, changelog, and compatibility matrix?
- Which endpoints are public contract, which are reference-client-only, and which are internal?
- How should the README and canonical docs describe the desktop app relative to the runtime?
- What would make Headless or MCP Lite a product shape rather than a loose launch mode?


---

# Direction note (2026-07-01, founder-resolved)

The open "product center" question this tempdoc poses is resolved: **JustSearch's public center is
the local runtime for agents; the desktop app is the first-party reference client.**

The design pass this tempdoc requests is now commissioned, with the above as fixed input plus one
hard boundary: **under-promise.** The v1 contract should be the minimal surface we are confident
holds stable (health/status lifecycle subset, the MCP endpoint + tool surface, a runtime manifest).
Stability promises are cheap to add later and expensive to retract; a compatibility matrix and a
semver policy are in scope, but broad API-subset stability promises are not (v1).


---

# Design — JustSearch Runtime Contract v1 (2026-07-02)

> Design pass only. This section is general (product/architecture-level), not an implementation
> ticket. It authorizes a shape; exact field names, wiring, and gate code are the implementer's.
> Verify every code reference against `main` before building — this doc is dated history the moment
> it merges.

## D0. Thesis in one paragraph

The "product center = local runtime, desktop = reference client" decision needs a concrete, nameable
object external tools can target. **That object already exists in code — the runtime manifest** — and
the missing piece is not new machinery but a *declared, versioned boundary* drawn over a small set of
already-shipped, already-versioned surfaces. The v1 design is therefore deliberately thin: name a
**coarse "Runtime Contract" version** whose meaning is a **compatibility matrix** of independently
versioned constituents; advertise it **on the manifest** (conforming to the manifest closure rule);
classify every runtime surface into **public-contract / reference-client / internal**; and bind the
whole with a **SemVer-scoped, deprecation-clocked stability policy** that promises little and keeps its
promises. Everything the desktop uses beyond the stable core is *reference-client / internal* and is
explicitly **not** promised. No new governed substrate, no auth/remote, no certification claims (v1).

## D1. Why this is a positioning + versioning pass, not new endpoints

Most of the "contract" is shipped. The design's job is to *draw a boundary* and *name a version*, not
to build surfaces. Verified against `main`:

| Constituent | Where it lives | Already versioned? |
|---|---|---|
| Runtime manifest (discovery/liveness/lifecycle) | `RuntimeManifest` + `Reachability`; fs + `GET /api/runtime/manifest` + SSE + `/.well-known/justsearch/manifest.json` + MCP `justsearch_runtime_manifest` | ✅ `CURRENT_SCHEMA_VERSION = 1` |
| Health/status lifecycle subset | `GET /api/health` (gate) + `GET /api/status` minimum subset | ✅ `schema_version = 1`, guarded by `LifecycleContractTest` |
| MCP endpoint + curated tool surface | `POST /mcp`, `McpToolSurface` (5 tools), `McpProtocolHandler` | ⚠️ protocol `2025-11-25` is versioned; the **tool surface itself is not** (see D4) |
| Underlying wire shapes | `contracts/wire` (proto specs incl. `runtime.proto`/`status.proto`/`health.proto`) | ✅ `contracts/wire/VERSION = 0.2.0`, buf-breaking + governance-kernel enforced |

The manifest already carries an **audience axis** (`Reachability.Transport{kind,url,audience}` with
`audience ∈ {public, full}` and a `publicProjection()` that filters non-public transports, populated
from `RuntimeTransportRegistry` at route-registration time). That axis is the seam the contract
boundary refines — not a place to bolt on a parallel classifier.

## D2. The v1 stable core (what "the contract" promises)

The Runtime Contract v1 promises exactly three surfaces, at the versions pinned by the matrix (D3):

1. **Runtime manifest** — the discovery + liveness + lifecycle object (its `publicProjection()`, i.e.
   the HTTP/SSE/well-known/MCP body, never the filesystem-only `sessionToken`). This is also the
   **entry point**: an external tool reads the manifest first and learns everything else from it.
2. **Health/status lifecycle subset** — the `LifecycleContractTest` minimum fields (`schema_version`,
   `lifecycle.state`, `components.{head,worker,inference}.state`, allowlisted `reason_code`s) and the
   `GET /api/health` 200/503 gate semantics. The *extended* `/api/status` fields are **not** in the
   contract — they are reference-client/internal and may change.
3. **MCP endpoint + curated tool surface** — `POST /mcp` at protocol `2025-11-25`, and the enumerated
   tool set (`justsearch_answer`, `_search`, `_browse`, `_ingest`, `_status`) with their declared
   input schemas. Tool *descriptions/hints* and Resources/Prompts are **advisory**, not contract.

Promise semantics (v1): **additive-only within a contract major** (new optional manifest fields, new
tools, new optional response fields do not break); **removal or a required-shape change is a breaking
change** and requires a contract-major bump + the deprecation clock (D5). This mirrors what the
manifest already guarantees (`RuntimeManifestSchemaCompatibilityTest`: new optional fields don't
break) and what the lifecycle contract test already fixes.

## D3. Contract version + compatibility matrix (the "one object to target")

**A single coarse, slow-moving `runtimeContract.version`**, advertised as a new sub-record on the
manifest (following the "adding a new fact" recipe in `docs/explanation/23-runtime-manifest.md` §
"Adding a new fact" — a field on the one object, *not* a tenth mechanism). Its *definition* is the
matrix of constituent versions:

```
JustSearch Runtime Contract v1  ≙  {
    manifest.schemaVersion   : 1,           # app-api RuntimeManifest record
    lifecycle.schema_version : 1,           # app-api status views (LifecycleContractTest subset)
    mcp.protocolVersion      : "2025-11-25",
    mcp.toolSurfaceVersion   : <new; see D4>,
}
```

> Correction (verified — U2): the external surfaces serialize from **app-api record/view types**
> (`io.justsearch.app.api.*`), which are the external authority. `contracts/wire` (the Head↔Worker gRPC
> protos, `io.justsearch.contract.wire`, VERSION `0.2.0`) is the **internal** transport one layer below
> — structurally mirrored to some external shapes but **not** a constituent of the *external* contract,
> and deliberately kept out of this matrix. `/infra/capabilities` `protocolVersion 1.0` (internal
> FE↔Head handshake) stays out for the same reason.

Design rationale, grounded in prior art (see §Research grounding, external):

- **Coarse single version, bump-only-on-break.** Like Docker Engine's negotiated API version
  (single number, min/max table) and — pleasingly consistent — like MCP's own dated version rule
  (*"only bumped when backward-incompatible changes are made"*). Additive changes to any constituent
  do **not** bump the contract version. This gives external tools **one** thing to target while the
  matrix records what it means, without coupling every additive tweak to a version churn.
- **Communicated as a table + a one-line skew note**, not a per-release artifact. The table maps
  Runtime Contract version → constituent versions; the skew note states the tolerance (e.g. "a client
  built for Contract vN works against runtime advertising Contract vN; older clients degrade
  gracefully via manifest-declared reachability"). This is the Docker-history-table ⊕ K8s-skew-rule
  combination — the lowest-maintenance form that still answers "will my client work."
- **Deliberately modest starting version.** The internal `contracts/wire` is honestly `0.2.0`
  (pre-1.0 per SemVer clause 4). The external contract should start equally modest and only reach a
  "1.0 = we will not break this" declaration when we mean it (SemVer clause 5 scopes *what* 1.0
  covers — here, only the three D2 surfaces).

## D4. The MCP tool-surface version gap (delegated depth to 655, anchor set here)

MCP's protocol version says **nothing** about the stability of *your* tool set — as of the current
stable spec (`2025-11-25`) MCP has no *shipped* tool-surface versioning; tool changes are
runtime-discovered (`tools/list`) and signaled via the `listChanged` capability, not a protocol bump.
So the tool surface must be versioned by **us**. v1 decision: the tool surface is versioned **as a
constituent of the Runtime Contract** (the `toolSurfaceVersion` in D3), enumerated in the contract,
governed by the same additive-only rule. **Version home (verified — U4):** `McpProtocolHandler` already
returns `serverInfo.version` (today a dead literal `"1.0.0"`, *not* the app/build version) — the
MCP-native, currently-unused slot. Make *that* slot mean the SemVer tool-surface version and mirror it
in the manifest's `runtimeContract` record; no new MCP field, no collision with the app version.

**Standardization watch (2026-07-02) — pre-conform, don't invent a parallel scheme.** MCP is
*actively standardizing* exactly this, though nothing has landed yet (not in the `2026-07-28` release
candidate): SEP-986 / SEP-1575 point at **per-tool SemVer** (major = incompatible, minor = additive)
and issue #1915 is drafting recommended tool versioning/naming; SEP-1400 proposes SemVer for the spec
itself plus **per-capability versioning**. Implication for D4: shape `toolSurfaceVersion` as **SemVer
and align its naming to the SEP-986/1575 direction** so that if/when MCP ships tool versioning,
JustSearch conforms by *adoption*, not by a redesign or rename. This is the same "conform to the seam,
don't build a parallel version" discipline (D10) applied to the *external* standard. See §Research
deepening for detail + sources.

This tempdoc sets that **anchor**; the *conformance* depth — capability-negotiation tests, client
fixtures, schema/output guarantees, capability policy for state-mutating tools — is **655's** owned
territory and must not be duplicated here. Explicit language boundary (D8): 654 does **not** claim the
tool surface is "certified" or "conformant."

## D5. Stability policy (borrowed mechanisms, under-promised)

The policy is prose + the two existing contract tests; **no new governance kernel** (see D7). It states:

- **Scope** — only the D2 three surfaces are "the contract." Everything else is reference-client or
  internal (D6) and may change without notice. (SemVer clause 5: 1.0 defines the public API — ours is
  narrow by construction.)
- **Additive-only within a major** (D2 semantics); breaking change ⇒ contract-major bump.
- **Deprecation clock** for anything we *do* promise, before removal — a written window (candidate:
  align to MCP's own ≥90-day expedited / longer default, so a JustSearch consumer never faces a
  shorter fuse than MCP's). Values are a founder open decision (D9).
- **Under-promise mechanisms available** (menu, not all needed in v1): version-in-the-identifier
  (K8s `v1alpha1`/`beta`/GA, LM Studio `/api/v0`), an opt-in `experimental` marker for surfaces we
  want to expose-but-not-promise (Rust feature-gate analog), the coarse contract version (Docker),
  the deprecation clock (K8s/MCP). v1 uses the **contract version + the classification table + the
  clock**; the per-surface identifier tiers are held in reserve for when the boundary needs mechanical
  per-route enforcement (D7).

## D6. Classification: public-contract / reference-client / internal

The design's third deliverable. Precise mechanism (verified — U1): the contract's **discovery
transports** (manifest, probes, well-known, MCP tool, filesystem) are *already* a genuine projection —
each is declared with a `public`/`full` audience at route-registration time via
`RuntimeTransportRegistry`, the single source of truth the manifest reads. The **whole-API**
public/reference/internal split below, however, is a **v1 hand-authored doc table** — the audience axis
does not yet classify the ~40 general REST routes or per-field `/api/status` shape. The audience axis is
the *seam* a staged per-route gate would extend (D7); it is **not** claimed to already classify the
full surface. Buckets:

- **Public-contract** — the D2 three surfaces. Promised; versioned; deprecation-clocked.
- **Reference-client** — surfaces the desktop shell (and the manifest's `full` audience) depend on
  that we expose but do **not** promise to third parties: extended `/api/status`, `/api/knowledge/*`
  (search/suggest/ingest/status), boot phases, health-event streams, governance state, operation
  substrate, the `/v1/*` OpenAI-compat shim, etc. These *demonstrate* the runtime; they do not
  *define* the contract.
- **Internal** — `/api/debug/*`, gRPC Head↔Worker IPC, MMF signalling, filesystem-only manifest
  fields (`sessionToken`), the `contracts/wire` protos as such. Not for external callers at all.

**The leak-plug (load-bearing):** "reference client" means the desktop *demonstrates* the contract; it
does **not** mean everything the desktop touches is contract. Without this sentence stated plainly, the
under-promise boundary silently leaks the moment someone reasons "the desktop uses `/api/knowledge/search`,
so it must be stable." It is not. Only D2 is.

## D7. Scope judgment — what structure the problem requires, and what it does not

The present problem is: *draw a non-drifting external boundary over a 3-surface stable core, name its
version, and position it — under an explicit under-promise constraint.* Matching structure to that:

**Required (build when implementation is authorized):**
- A manifest-advertised `runtimeContract` sub-record (version + constituent matrix). One field on the
  existing object.
- A canonical reference doc — "JustSearch Runtime Contract" — stating D2/D3/D5/D6. Durable current
  truth (unlike this tempdoc).
- The compatibility matrix table + skew note (in that doc).
- Reuse of the two **existing** contract tests (`LifecycleContractTest`, `RuntimeManifestSchemaCompatibilityTest`)
  as the mechanical guardrails; a light MCP tool-surface enumeration test as the tool-surface anchor.

**Not required now (would be structure for a case the problem does not yet include):**
- A per-route stability-tier **field** on every one of ~40 routes, plus a CI drift-gate enforcing it.
  For a *three-surface* enumerated core this is over-structure. It becomes justified only if/when the
  boundary needs mechanical per-route enforcement (many promised surfaces, drift observed). Recognized,
  staged, **not built** — the same discipline tempdoc 664 applied to its projections and the
  tier-register applied to its own meta-loop gate (seeded first, made live later).
- Folding the runtime contract into the `contracts/wire` governed substrate (buf-breaking +
  structural-diff governance kernel). That substrate versions internal *message shapes*; the runtime
  contract is a *product boundary declaration*. Per AHA ("only unify what shares a reason to change"),
  these are different reasons to change — the contract *references* `contracts/wire`'s VERSION in its
  matrix, it does not become a wire category. Adopting the heavy machinery would also directly violate
  the founder's under-promise constraint.
- Any auth/OAuth/remote surface (violates the loopback-only invariant #2; tempdoc 500 "don't add
  OAuth"). The trust boundary is the local machine; "external contract" here means external *agents on
  the same host*, not remote access.
- Certification/conformance claims (D8).

## D8. Public-claims honesty (this tempdoc is public once merged)

Because the repo is public and the public-claims CI lane (`check-frontend-stack-claims.mjs` scope now
includes README + `docs/business`; `check-readme-benchmark-numbers.mjs`) guards over-claiming, any
README / `docs/business` / marketing language derived from this design must **not**:
- call the runtime contract or MCP surface "certified", "compliant", or "conformant". Verified
  2026-07-02: **there is no MCP server certification program.** MCP conformance is *spec/SDK-facing*
  and still hardening (a conformance suite now *gates* a Standards-Track SEP from reaching Final; an
  "SDK tier system" scores official SDKs) — it does **not** certify third-party servers. JustSearch
  has run no such suite. Conformance *evidence* is **655**'s deliverable and *verifiable trust
  evidence* is **659**'s; 654 only defines the contract's shape.
- conflate the **official MCP Registry** with certification. That registry (registry.modelcontextprotocol.io,
  in *preview*, API-frozen at v0.1) is a **discovery** index that does **namespace/provenance
  verification** (reverse-DNS server names tied to a GitHub account via OAuth or a domain via DNS TXT)
  — it verifies *who published the server*, **not** its quality, security, or conformance. So the
  honest, checkable status JustSearch can eventually claim is *"listed in the official MCP Registry,
  namespace-verified"* — never *"certified"* or *"verified server"* in a quality sense. (This is a
  concrete, honest trust lever for **659** to pick up.)
- imply remote/multi-tenant access, enterprise guarantees, or a stability promise broader than D2.
Honest framing to use: "a defined, versioned local runtime contract with a small stable core," "the
desktop app is the reference client," "under-promised by design." This tempdoc itself uses only that
register.

## D9. Answers to the tempdoc's five First Questions

1. *Smallest stable contract an external tool can rely on?* → The D2 three surfaces, discovered via the
   manifest, at the versions pinned by the D3 matrix. Nothing else.
2. *Own version / changelog / compatibility matrix?* → Yes to all three, but **coarse and cheap**: one
   slow-moving contract version (bump-only-on-break), a CHANGELOG entry per bump, and a matrix table.
   No governance kernel.
3. *Which endpoints are public / reference-client / internal?* → D6.
4. *How should README/canonical docs describe desktop vs runtime?* → Runtime-first: "JustSearch is a
   local knowledge runtime agents can target; the desktop app is its reference client." **Smaller than
   it looks (verified — U5): the README is already runtime-first** (leads with *"A private retrieval
   backend for your AI agents…"*, `README.md:3`). 654 does not flip the narrative — it *adds the
   contract vocabulary* ("versioned runtime contract", "reference client"). The one alignment edit is
   `docs/explanation/01-system-overview.md:12` (still says "…delivered resiliently on the desktop"),
   coordinated with **650** (narrative owner) — 654 owns the contract *shape*, 650 owns the *narrative*;
   do not duplicate. Contract vocabulary trips neither public-claims gate.
5. *What makes Headless / MCP Lite a product shape vs a launch mode?* → They become product shapes
   precisely by *serving the same Runtime Contract*: a mode is a "product shape" iff it advertises the
   same manifest + contract version + stable core. 654 **owns that naming** (they are official product
   shapes because the runtime is the center); **657** owns the packaging/model-pack decomposition and
   **656** owns the onramp that introduces the object. 654 hands 657 a definition: *a product shape is
   a supported way to run the Runtime Contract, distinguished by which reference-client/optional
   capabilities it bundles, not by a different contract.*

## D10. Reach — the seam this conforms to, and the principle it reveals

**This design is an instance of an existing seam; conform, do not parallelize.** The codebase already
has a named recurring shape — **canonical-record → governed projections → drift-gate** (the
representation-drift lineage: tempdoc 553's `SearchTrace`/execution-surfaces register; 623/640/625/646;
664's "canonical-record-plus-governed-projections"; 653's "public main as a *projected* history
surface"; 634's "publish-the-mechanism, canonical-source→projection→drift-gate"). The runtime manifest's
**audience axis** (`public`/`full` `publicProjection()`) is already the minimal instance of it for the
runtime object. The Runtime Contract boundary is the *same seam refined one notch* (audience → audience
+ stability tier), discovered through the *same one object* (the manifest, per its closure rule). The
design therefore **extends** the audience/reachability projection and the manifest closure rule rather
than inventing a parallel "contract classification" system — which is exactly what the closure rule
exists to prevent.

**The principle it reveals, named plainly:**

> **A product/contract boundary is a projection over per-surface declarations, discovered through one
> runtime object — never a hand-maintained prose enumeration that forks from the code.**

This is the external-product-boundary case of the codebase's canonical-record-plus-projections seam.

**Candidate scope beyond 654 (recorded, not built):**
- `docs/reference/api-contract-map.md` is itself a hand-maintained prose enumeration of surfaces
  (partially guarded by the `docsApiDriftCheck` gradle task). Under the principle, its public/reference/internal
  classification should ultimately *project* from per-surface declarations rather than be re-typed by
  hand. **Standing near-instance; do not refactor now.**
- Tempdoc 650's public capability descriptor is a *deliberately deferred* prose-tier projection
  (~70%, founder-chosen for a solo-dev launch). A known, intentional non-conformance — the principle
  says the descriptor should eventually project from ground truth; 650 already recorded why it doesn't
  yet. **Do not re-litigate.**
- `/infra/capabilities` (per-sub-API `contractVersions` from route registration) and the audience axis
  already **conform** — evidence the seam is real, not invented here.

**Why not build the generalized structure now:** the v1 problem is a *three-surface* enumerated core;
a generalized per-route tier-projection + drift-gate is structure for a case (many promised surfaces,
observed drift) the problem does not yet include. Recognizing the principle and staging the mechanism
is deliberate — it captures the insight without paying for premature abstraction, consistent with how
664 and the tier-register meta-loop were handled.

## D11. Open decisions for the founder

1. **Starting contract version label** — modest `0.x` (honest, matches `contracts/wire` 0.2.0) vs a
   confident `1` (targetable, but a stronger promise). Recommendation: `0.x` until we mean "won't
   break," then a scoped 1.0 (D3).
2. **Deprecation-clock length** for promised surfaces (D5) — align to MCP's window, or state our own.
3. **Where the canonical doc lives** — a new `docs/explanation/` page ("JustSearch Runtime Contract")
   vs a `docs/reference/` page. Recommendation: an explanation page for the *why/positioning* +
   a reference page for the *matrix/policy* (mirrors how the manifest is split across doc 23 +
   api-contract-map), coordinated with 650 for the narrative half.
4. **Sequence vs 655/656/657** — 654 is the keystone (660 waits on it). Recommendation: land the
   contract *doc + manifest version field* first (thin), then 655 certifies within it, 657 packages
   the shapes, 656 onboards to it.

## Research grounding (external, 2026-07-02)

The design's versioning/under-promise choices are grounded in peer prior art (web research, cited):

- **"Runtime + reference client" is a proven split, but stability is usually folklore.** Ollama
  (background server on `127.0.0.1:11434` + CLI/GUI/library clients), LM Studio, Jan, llamafile, and
  Khoj all separate runtime from client, but **none publishes a real API semver + compatibility
  matrix** — most outsource stability to OpenAI's schema. Ollama documents its HTTP API yet has *no*
  versioning/deprecation policy (stability is convention). So JustSearch's plan to write the boundary
  and policy down is *more* disciplined than the category leaders — Ollama is the positioning model, a
  cautionary one on stability. LM Studio's native REST is explicitly labeled `/api/v0` "subject to
  change" — the version-in-identifier under-promise, directly reusable.
- **MCP: protocol version ≠ tool-surface version.** MCP versions are dated (`YYYY-MM-DD`), *bumped
  only on backward-incompatible change*; `2025-11-25` is current (JustSearch is on it). Capability
  negotiation is separate from version negotiation. MCP has **no** mechanism to version a server's tool
  set (runtime-discovered via `tools/list` + `listChanged`), and a feature-lifecycle/deprecation
  window (≥12 mo default / ≥90 day expedited). ⇒ the tool surface must be versioned by us (D4). Official
  conformance tooling (`modelcontextprotocol/conformance`, MCP Inspector) exists but is **emerging** —
  hence D8's no-certification-claim rule.
- **Under-promising, concrete mechanisms.** SemVer clause 4 (0.x = "anything MAY change") / clause 5
  (1.0 defines *the* public API — scope it narrowly); Kubernetes per-object `v1alpha1`/`v1beta1`/`v1`
  tiers + deprecation clock (9 mo / 3 releases); Rust `#[unstable]` feature-gates (opt-in namespace,
  walled off across crate boundaries). Menu drawn on in D5.
- **Compatibility matrices — borrow from infra, not local-AI.** Docker Engine↔API (single dated API
  version negotiated to the highest common value at connect + a published min/max history table) is
  the closest analog to a local client↔runtime pair and motivates D3's coarse single version. K8s↔kubectl
  is a prose "±1 minor skew" rule — the low-maintenance alternative folded into D3's skew note. No
  local-AI peer publishes a first-party client↔runtime matrix, so this axis borrows cross-domain.

## Research deepening — MCP fast-moving surface (2026-07-02)

A targeted second pass on the *one* fast-moving external standard the design bets on: MCP's own
stance on tool/capability versioning and on server certification. (The mature borrowings — SemVer,
K8s, Docker, Rust gates — were not re-checked; they don't move.) Findings, with sources:

- **Current stable MCP version is `2025-11-25`** — JustSearch's declared version is on current stable.
  `2026-07-28` is a *release candidate*, not yet stable, and **stays date-based** (it did not adopt
  SemVer-for-spec). So D3's `mcp.protocolVersion: "2025-11-25"` pin is correct today. Treat the MCP
  version in the matrix as an **opaque pinned string** — its *scheme* may change (see SEP-1400) even if
  the current value is stable. — https://blog.modelcontextprotocol.io/posts/2026-07-28-release-candidate/
- **Tool-surface versioning is being standardized toward per-tool SemVer, but has NOT shipped.**
  SEP-986 / SEP-1575 point at per-tool SemVer (major = backwards-incompatible, minor = additive);
  issue #1915 drafts recommended tool versioning/naming patterns. None is in the `2026-07-28` RC. ⇒
  D4's premise ("we must version the tool surface ourselves *today*") holds, and the correct move is to
  **pre-shape it as SemVer aligned to that direction** so conformance is later adoption, not redesign.
  — https://github.com/modelcontextprotocol/modelcontextprotocol/issues/1400 ,
  https://github.com/modelcontextprotocol/modelcontextprotocol/issues/1915
- **SEP-1400 proposes SemVer for the spec + per-capability versioning** (additive atop the spec
  version). A watch item for D3: if MCP moves the *protocol* version from dated to SemVer, the matrix's
  MCP pin changes shape (another reason to treat it as opaque). — https://github.com/modelcontextprotocol/modelcontextprotocol/issues/1400
- **No MCP server certification program (D8 confirmed).** Conformance is spec/SDK-facing: a conformance
  suite now *gates* a Standards-Track SEP from reaching Final, and there's an "SDK tier system" scoring
  official SDKs — neither certifies third-party servers. — https://blog.modelcontextprotocol.io/posts/2026-07-28-release-candidate/
- **The official MCP Registry is discovery + namespace verification, not certification.**
  registry.modelcontextprotocol.io ("app store for MCP servers") is in *preview* (API frozen at v0.1
  since Oct 2025). It authenticates **namespaces** — reverse-DNS server names tied to a GitHub account
  (OAuth) or domain (DNS TXT) — verifying *who published*, not quality/security/conformance. Honest
  status JustSearch can target: *"listed, namespace-verified"*; never *"certified."* Feeds **659**. —
  https://modelcontextprotocol.io/registry/about , https://github.com/modelcontextprotocol/registry

**Net effect on the design:** no reversal — the thin, manifest-native, under-promised shape stands. Two
refinements: (1) D4's `toolSurfaceVersion` should be SemVer-shaped and pre-aligned to SEP-986/1575 so
JustSearch *conforms to the emerging external standard rather than forking from it* — the same
principle as D10, one layer out; (2) D8's honesty guardrail sharpens to the precise
*discovery/namespace-verification ≠ certification* distinction. No external code, text, schemas, or
assets were copied into the repo or docs during this pass — orientation only, so no license/attribution
obligation arises.

## Pre-implementation verification — U1–U6 (2026-07-02)

A read-only de-risking pass *before* any implementation: convert the design's six load-bearing
assumptions into verified facts with `file:line` citations. No feature code written. Result: **no
showstoppers; the thin manifest-native shape survives intact.** Two real corrections (D3, D6), one
concrete refinement (D4), one de-risk (U5), two confirmations (D2, U3). The corrected claims are edited
in-place above; the evidence and the changed conclusions are recorded here.

- **U6 — D2 stable subset: CONFIRMED exact.** `LifecycleContractTest` (`modules/ui/src/test/java/io/justsearch/ui/api/LifecycleContractTest.java:132-163`)
  asserts the `/api/status` v1 subset is **present, not exhaustive** — verbatim: *"check the required v1
  fields are present, not exact match."* Required subset = `schema_version`==1, `observed_at`,
  `lifecycle.state`, `components.*`; reason codes match `^(head|worker|ipc|inference)\.[a-z0-9_…]+$`
  (`:48-49`). `/api/health` gates 200 for DEGRADED / 503 for ERROR. D2 stands as written.
- **U3 — manifest field legality: CONFIRMED clean, no schema bump.** `RuntimeManifest`
  (`modules/app-api/src/main/java/io/justsearch/app/api/runtime/RuntimeManifest.java:33-72`) already
  carries producer-owned constants (`schemaVersion`, `pid`, `head.buildStamp`); its own doc: *"New
  fields are added with `@JsonInclude(NON_NULL)`; schema bumps require incrementing schemaVersion."*
  `RuntimeManifestSchemaCompatibilityTest` (`…/RuntimeManifestSchemaCompatibilityTest.java:80-107`)
  proves nullable/`NON_NULL` fields are additive; only a *required field without default* breaks. The
  closure check constrains sibling **files**/stdout/`dataDir` writes, not record fields. ⇒ a nullable
  `runtimeContract` sub-record is a clean additive producer-owned field.
- **U4 — MCP version home: RESOLVED, cleaner than assumed.** `McpProtocolHandler` returns
  `serverInfo.version = "1.0.0"` — a **hardcoded literal**, not the app/build version, currently inert
  as a signal (`modules/ui/src/main/java/io/justsearch/ui/api/mcp/McpProtocolHandler.java:36,119`);
  `McpToolSurface` carries **no** per-tool version/metadata. ⇒ the natural home for `toolSurfaceVersion`
  is to make that already-present, currently-meaningless `serverInfo.version` slot *mean* the SemVer
  tool-surface version, mirrored in the manifest's `runtimeContract` record. No collision with the app
  version. (Correction folded into D4 below.)
- **U1 — D6 "projection of the audience axis": CORRECTED (was overstated).** The audience axis is a
  **per-transport** tag declared at route registration (`RuntimeTransportRegistry.registerHttp/Sse/
  Probe/WellKnown/McpTool/Filesystem(path, audience)`, `modules/ui/src/main/java/io/justsearch/ui/
  runtime/RuntimeTransportRegistry.java:38-70`) — and only the *curated runtime-discovery transports*
  register (manifest, probes, well-known, MCP tool, filesystem). It does **not** classify the ~40
  general REST routes or individual `/api/status` fields, and has only `public`/`full`, no stability
  tier. ⇒ the contract's *discovery transports* are a genuine projection today; the whole-API
  public/reference/internal split is a **v1 hand-authored doc table**, with the audience axis as the
  seam a *staged* per-route gate would extend (D7). D6 corrected to say exactly this.
- **U2 — D3 `contracts/wire` in the external matrix: CORRECTED (category imprecision).** The external
  surfaces serialize from **app-api record/view types** (`io.justsearch.app.api.runtime.RuntimeManifest`,
  `io.justsearch.app.api.status.*View`), which are the external serialization authority.
  `contracts/wire` (`status.proto`/`health.proto`/`runtime.proto`, `option java_package =
  "io.justsearch.contract.wire"`) is the **internal Head↔Worker gRPC transport** — structurally
  *mirrored* to some external shapes (its `status.proto` header even says "— /api/status") but it is
  the layer *below* the external DTO, not the external promise. ⇒ the external compatibility matrix
  pins the **app-api-level** versions (manifest `schemaVersion`, lifecycle/status `schema_version`,
  MCP protocol + tool-surface); `contracts/wire` VERSION is noted as the *internal* IPC shape, **out of
  the external constituent list**. `/infra/capabilities` `protocolVersion 1.0` likewise stays out
  (internal FE↔Head handshake). D3 matrix corrected.
- **U5 — README/doc-01 reframe: DE-RISKED (smaller than assumed) + gate-safe.** The **README is already
  runtime-first** — it leads with *"A private retrieval backend for your AI agents…"* and an *"As a
  private MCP retrieval backend for agents (the fast path for developers)"* section (`README.md:3,12-33`),
  landed by the go-public cluster (650/634). So 654 does **not** need a narrative flip — it adds the
  *contract vocabulary* ("versioned runtime contract", "reference client") on top. `docs/explanation/
  01-system-overview.md:12` still frames delivery as *"…on the desktop"* and is the one alignment edit,
  coordinated with **650** (narrative owner). Gate scope: `check-frontend-stack-claims.mjs` forbids only
  present-tense React/`.tsx`/Zustand; `check-readme-benchmark-numbers.mjs` checks benchmark numbers — my
  contract vocabulary trips neither. (Refines D9 Q4 / D11.)

## Implementation (2026-07-02)

Shipped the thin design (D0–D11), matching scope to the three-surface core. Chosen version labels
(D11 defaults; founder may bump): Runtime Contract **`0.1.0`**, MCP tool surface **`0.1.0`**, deprecation
window **≥90 days**. Docs split as recommended (explanation + reference).

**Code (new):**
- `modules/app-api/.../mcp/McpContractVersions.java` — single source for MCP `PROTOCOL_VERSION`
  (`2025-11-25`) + `TOOL_SURFACE_VERSION` (`0.1.0`).
- `modules/app-api/.../runtime/RuntimeContract.java` — the descriptor (`version` + `constituents`);
  `current()` **projects** each constituent from its canonical constant (`RuntimeManifest.CURRENT_SCHEMA_VERSION`,
  `LifecycleSnapshotV1.SCHEMA_VERSION`, `McpContractVersions.*`) — no fork.

**Code (edited):**
- `RuntimeManifest.java` — nullable `runtimeContract` field (additive, no schema bump) + composed in
  `publicProjection()`.
- `RuntimeManifestPublisher.java` — populate `.runtimeContract(RuntimeContract.current())` at
  `publishHead` (carries forward via `builder(previous)`).
- `McpProtocolHandler.java` — `protocolVersion`/`serverInfo.version` now read the `McpContractVersions`
  constants (was a dead literal `"1.0.0"`), so the manifest and MCP handshake report identical versions
  by construction.
- `RuntimeApiRoutes.java` — advertise `POST /mcp` in the manifest reachability list.

**Docs:** new `docs/explanation/28-runtime-contract.md` (positioning + definition) and
`docs/reference/runtime-contract.md` (compatibility matrix + stability policy + **changelog** + the
public-contract / reference-client / internal classification — the changelog answers First-Question-2's
third item, seeded with the `0.1.0` entry, mirroring `contracts/wire/CHANGELOG.md`); cross-links from
`api-contract-map.md` + `23-runtime-manifest.md`;
one runtime-first alignment line in `01-system-overview.md` (coordinated with 650); `llms.txt` regenerated.

**Tests (all green):** `RuntimeContractTest` (projection reads single sources, not literals);
`RuntimeManifestSchemaCompatibilityTest` +case (round-trips with the field; `NON_NULL`-omitted when
absent); `McpProtocolHandlerTest` +assert (`serverInfo.version`/`protocolVersion` == constants);
`RuntimeManifestControllerRedactionTest` +case (**the exact serve transform**: `runtimeContract`
survives `publicProjection()` while the token is stripped). `LifecycleContractTest` unchanged + green.

**Verification.** `spotlessApply` + `build -x test` green; full `:modules:app-api:test` +
`:modules:ui:test` green; gates green (`check-runtime-manifest-closure` 0 violations,
`check-frontend-stack-claims` OK, `check-readme-benchmark-numbers` OK); docs regen clean. Full
`./gradlew.bat test` surfaced one **pre-existing, unrelated** failure — `VduEligibilityPdfFixturesTest`
(local Tesseract/Tika PDF-OCR env gap; `@DisabledIfEnvironmentVariable(CI=true)` so public CI is green;
documented in `observations.md` 6×; worker-services untouched by this branch).

**Disclosed verification limit (honest).** The serve path is validated deterministically at every tier
that does not need the multi-process stack: the projection transform (`publicProjection` preserves
`runtimeContract` + strips the token), the JSON round-trip (real Jackson), and the MCP `initialize`
response (real `handlePost`). A live-stack HTTP probe of the *running* backend was **not** run: the
coordinated dev-stack MCP tooling is not wired in this worktree session (`.mcp.json` is maintainer-local,
seeded at session start), and starting an uncoordinated raw backend risks colliding with a parallel
agent's shared stack (the lease model that guards this can't be consulted without the MCP tools). This is
not a user-visible/UI feature, so no browser tier applies. The residual unproven step is only that the
assembled backend *invokes* `publishHead` (a trivial builder call, exercised by the existing live-stack
harness referenced in `RuntimeManifestControllerRedactionTest`'s javadoc). A one-line live check when a
coordinated stack is available: `GET /api/runtime/manifest` shows `runtimeContract`, and MCP `initialize`
`serverInfo.version` matches `runtimeContract.constituents.mcpToolSurfaceVersion`.

