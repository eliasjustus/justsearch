---
title: "875 — Agent-tool consent boundary: grants, containment, undo"
status: IMPLEMENTING
created: 2026-08-26
follows: [868, "560 §28", "811 C-2a", "577 §2.14"]
owner: session f6617483
---

# 875 — Agent-tool consent boundary: grants, containment, undo

One of several parallel workstreams following tempdoc 868's investigation of the delegate
agent's tool surface. This one owns the **consent boundary**: what a user's approval actually
authorizes, how far the authorized action can reach on disk, and whether undoing it is safe.

## Findings handed over

*(independent review, 2026-08-26; re-verified line by line before design — see §B)*

1. **CRITICAL — one "allow always" on ingest unlocks unsandboxed ingest AND HIGH-risk file
   mutation, durably.** `AgentToolsOperationCatalog.java:~307,~334` put `core.ingest-files`
   (MEDIUM) and `core.file-operations` (HIGH) in capability family `"file-operations"`
   (deliberate: tempdoc 560 §28 4d). `OperationExecutorImpl.java:~610-620` satisfies INLINE and
   TYPED confirm identically from a family grant; `DurableGrantStore.isAllowed` has no risk-tier
   discrimination; grants persist in `durable-grants.json`. Meanwhile `IngestTool.resolvePath`
   (`IngestTool.java:~215-253`) has NO root containment (811 C-2a made out-of-root a *label*,
   `mcp-ingest`, not a refusal); only `Files.exists/isReadable` gate it. Net: after one routine
   ingest approval, `core_ingest_files {"paths":["C:\\Users\\x\\.ssh"]}` runs silently, indexes
   the content, and search quotes it into the prompt. Decide: HIGH must never be satisfiable by a
   family grant (gate the durable short-circuit on risk, or split the family); ingest gets root
   validation with an explicit, separately-consented out-of-root path (a confirm naming the path,
   not a family grant). Read 560 §28 and 811 before deciding — the family was a product decision;
   your design must say what it preserves.
2. **HIGH — undo of a copied directory deletes recursively, unvalidated.**
   `FileOperationsTool.undo` `:~288-314` executes COPY reversals via `executor.deleteDirectory`
   directly with no `isWithinRoots` re-validation (the MOVE/RENAME arm at `:~277` does validate),
   and the "edited since" guard `modifiedSince` (`:~256-260, ~355-364`) reads the directory's
   mtime, which does not change when a file inside is edited. Zero tests cover directory undo
   (`grep -c deleteDirectory` over tool tests = 0). Fix + tests.
3. **HIGH — offering is not authorization.** `AgentStepRunner.java:~822` resolves tool calls via
   `OperationCatalog.resolveByWireName` (`OperationCatalog.java:~75-83`) over the raw catalog — no
   audience/availability/selection check. The emitter's audience allow-list
   (`AgentOperationEmitter.java:~70-76`, documented as "without this filter the LLM can invoke
   admin operations") only hides; the owner partition (`OperationCatalogComposition.java:~112`) is
   the real boundary. Eleven `CoreOperationCatalog` ops carry `ExecutorTag.AGENT` and are one
   partition change from dispatchable (`bulk-reindex:~402`, `reindex:~630`, `add-watched-root:~705`,
   `switch-inference-mode:~830`, …). Design: resolve against the emitted/offered set (or apply the
   same filter chain at resolution) so a hidden tool is a denied tool; add a test that a model
   naming an un-offered tool gets a typed rejection.
4. **MEDIUM — browse degrades open.** `BrowseTool.validateParentPath` (`:~352-367`) returns null
   (=allow) when the roots supplier throws or is empty; browse is a directory-disclosure primitive.
   Search/read degrade-open is defensible (Worker index membership bounds them) — browse is not.
   Also `AgentToolPaths.validateAgainstRoots` uses normalize+startsWith without `toRealPath`
   (symlink-escapable) while `FileOperationExecutor.isWithinRoots` does it right; and
   `resolveRelativePath` is first-match on duplicate root names. Decide per-tool fail-closed vs
   fail-open explicitly and test the adverse precondition (`green-masked-destructive`).
5. Coordinate-with-C1 note: workstream 877 (centralisation) will introduce a single
   `AgentToolPaths.RootsView`; do NOT refactor path helpers structurally here — fix semantics
   (fail-closed, realpath) in place with minimal diff so the merge is clean. Ingest containment
   (finding 1) is yours.

---

## §A. Theorization (2026-08-26)

### A.1 The shape underneath all four findings

Read separately these are four bugs. Read together they are one shape: **every one of them is a
place where a narrow authorization silently widens.**

| Finding | The narrow thing the user did | What it actually authorized |
|---|---|---|
| 1 | approved *this* ingest, or granted the `file-operations` family | unsandboxed ingest of any readable path, forever, plus destructive file mutation |
| 2 | approved a COPY into an indexed folder | a recursive delete of whatever now sits at that destination, including outside the roots |
| 3 | the product offered six tools | every AGENT-tagged operation the catalog partition happens to contain |
| 4 | configured indexed roots | any directory on the machine, whenever the roots lookup is unavailable |

So the frame is not "fix four bugs". It is: **consent has a scope, and no mechanism downstream of
the consent gesture may widen it.** The widening happens through four distinct mechanisms —
durability (a grant outliving the situation it was granted in), reach (a path argument leaving the
sandbox), offering (a list that informs but does not bind), and reversal (an undo that is itself an
unbounded operation). Naming them separately matters, because each needs its own enforcement point;
a single "be careful" rule at one chokepoint cannot cover all four.

### A.2 Framings considered

**Framing 1 — "tighten the four sites."** Minimal diff, closes the reported holes. Risk: leaves the
class open. Finding 3 in particular is not a bug at a site, it is a missing *relationship* between
two lists (offered vs resolvable) that will drift again the next time an operation is added.

**Framing 2 — "sandbox the agent process."** Real containment (a jailed subprocess, an
object-capability handle per tool). Correct in the limit and wrong for this tempdoc: the Head is one
JVM, ADR-0035 already ruled untrusted backend code out (560 §9.2), and 560's 4a facet was explicitly
skipped as a security regression rather than an improvement. Recorded as the direction the ceiling
eventually points at, not as this work.

**Framing 3 — "consent is a scoped grant; every mechanism downstream must narrow, never widen."**
This is the one the codebase already half-believes: `ConsentCapsuleService` binds a capsule to
`(operation, argsJson)`, single-use; `Grant.CapabilityFamily.authorizes()` is fail-closed;
`TrustLattice` denies unenumerated combinations by default; `IntentGateEvaluator.agentGate` carries
an explicit "HIGH/destructive never auto-fires" floor. The findings are precisely the places where
that belief is not enforced. Adopted.

### A.3 Hidden assumptions worth surfacing

- **"A durable grant is args-independent"** (`DurableGrantStore` javadoc) is stated as a property of
  the model, but it is really a *simplification* — and it is exactly the simplification finding 1
  exploits. Args-independence is defensible for an operation whose reach is fixed; it is not
  defensible for one that takes a filesystem path as an argument.
- **"The emitter's audience filter is a security control."** Its own javadoc says so ("without this
  filter the LLM can invoke admin operations"). It is not — it is a *presentation* control. What
  actually keeps `core.bulk-reindex` away from the agent today is the owner partition in
  `OperationCatalogComposition`, an unrelated mechanism that nobody wrote down as a security
  boundary. Two mechanisms, one of them load-bearing by accident.
- **"Degrading open is graceful."** `BrowseTool.validateParentPath`'s comment literally says
  "Degrade gracefully". For a read that the index already bounds, that is defensible. For a
  directory-listing primitive whose whole purpose is disclosure, the graceful degradation *is* the
  vulnerability. The word "gracefully" did the arguing.
- **"Undo is safe because it reverses."** Undo of a COPY is a *delete*. Reversal is not a lesser
  operation than the thing reversed; for COPY it is strictly more dangerous, because the forward op
  created something and the reverse op destroys whatever is there now.

### A.4 Tradeoffs / risks to weigh in design

- Tightening the durable grant re-introduces prompts that 560 §28 deliberately removed. Any design
  must state exactly which prompts come back and why the user is better off.
- 811 C-2a explicitly *rejected* "containment-check and reject out-of-root paths" because "agents
  ingesting arbitrary paths is the point" for the MCP surface. A containment design that simply
  refuses would re-litigate a settled product decision. The design must preserve the capability and
  move only the *consent* requirement.
- Making the offered set binding could turn today's steering restrictions (the DECIDING handoff-only
  list, the E0a first-turn list) into hard denials and strand runs. Steering and authority must be
  separated, not conflated.
- Fail-closed path validation risks breaking tests that use fictional paths, and — worse — risks
  breaking real users whose roots lookup is briefly unavailable during boot. The adverse precondition
  has to be tested (`green-masked-destructive`), not assumed.

### A.5 Does this point at a broader principle?

Provisionally yes, and it is worth stating even before design: **a list that informs a caller is not
the same as a list that binds it, and any system with both will drift unless one is derived from the
other.** Finding 3 is the agent-loop instance. The same shape exists wherever the codebase computes
"what to show" and "what to accept" separately — the FE's operation buttons vs the executor, the
MCP tools/list vs the MCP dispatch. Not built here beyond the agent loop; recorded in §C.5.

---

## §B. Source re-verification (2026-08-26)

Every line reference in the handed-over findings was re-checked against this worktree
(`worktree-agent-ad001053110fe44c5`, base `666422b6`). Result: **all four findings confirmed, with
two mechanism corrections that change the design.**

| # | Claim | Verdict | Evidence |
|---|---|---|---|
| 1a | ingest MEDIUM + file-operations HIGH share family `"file-operations"` | **CONFIRMED** | `AgentToolsOperationCatalog.java:297-307` (`RiskTier.MEDIUM` … `.withCapabilityFamily("file-operations")`), `:323-334` (`RiskTier.HIGH` … same family) |
| 1b | INLINE and TYPED confirm satisfied identically from a durable grant, no risk discrimination | **CONFIRMED** | `OperationExecutorImpl.java:610-620` — one `case INLINE_CONFIRM, TYPED_CONFIRM ->` arm; the durable check at `:614-620` reads only `op.id()`, `op.policy().capabilityFamily()`, `sourceTier` |
| 1c | `DurableGrantStore.isAllowed` has no risk-tier discrimination | **CONFIRMED** | `DurableGrantStore.java:158-166` |
| 1d | grants persist across restart | **CONFIRMED** | `DurableGrantStore.java:268-279`, `:282-298` (`ui/durable-grants.json`) |
| 1e | `IngestTool` has no root containment | **CONFIRMED** | `IngestTool.java:215-253` — absolute input returns `p.normalize()` unconditionally at `:218-220`; the only gates are `Files.exists` `:147` and `isRegularFile/isReadable` `:159` |
| **1f** | *"after one routine ingest approval"* the family grant is in force | **CORRECTED** | The FE approve gesture mints a **per-operation** grant, not a family grant: `AuthorizationController.java:169` calls `grantAllowAlways(pending.operationId(), …)`, and the checkbox reads "Always allow this action (don't ask again)" (`AuthorizationHost.ts:374-381`). A **family** grant is mintable only through `POST /api/authorizations/grants {kind:"family"}` (`AuthorizationController.java:330-336`) and the Durable-grants settings panel. So the escalation from ingest to file-mutation needs a *family* grant, not an ingest approval. |
| **1g** | the per-op path is therefore harmless | **REFUTED** | It is not. `UNTRUSTED × MEDIUM` and `UNTRUSTED × HIGH` are **both** `TYPED_CONFIRM` (`CoreTrustEvaluator.java:86-94`), and the durable short-circuit runs *before* the capsule check for both. So "Always allow this action" on `core.file-operations` durably reduces the strongest ceremony the system has to nothing — and "Always allow this action" on `core.ingest-files` durably authorizes ingest of *any readable path on the machine*, which is the whole of finding 1's payload minus the family hop. |
| 2a | COPY undo deletes without `isWithinRoots` re-validation | **CONFIRMED** | `FileOperationsTool.java:298-309` — `executor.deleteDirectory(action.path)` / `Files.delete(action.path)` with no validation; contrast the MOVE/RENAME arm `:276-285` which calls `executor.validate(...)` first. The file arm is unvalidated too, not only the directory arm. |
| 2b | `modifiedSince` reads the directory's own mtime | **CONFIRMED** | `FileOperationsTool.java:355-364` — `Files.getLastModifiedTime(target)`; a directory's mtime tracks entry add/remove, not edits to files inside it |
| 2c | zero tests cover directory undo | **CONFIRMED** | no `deleteDirectory` reference anywhere under `modules/app-agent/src/test` |
| 3a | resolution runs over the raw catalog with no audience/availability/selection check | **CONFIRMED** | `AgentStepRunner.java:822` → `OperationCatalog.resolveByWireName` `:90-104` → `findByWireName` `:75-83`, straight over `definitions()` |
| 3b | the emitter's audience allow-list only hides | **CONFIRMED** | `AgentOperationEmitter.java:83-84` + `OperationEmitter.java:60-66`; applied only on the `emit` path |
| **3c** | the 11 AGENT-tagged `CoreOperationCatalog` ops are dispatchable today | **CORRECTED — they are not, but the boundary is accidental** | `OperationCatalogComposition.java:104-121` partitions CORE-owner ops into `operationCatalog` and everything else into `agentToolsCatalog`; the agent loop reads the latter. So `core.bulk-reindex` is out of the agent's catalog *by owner*, not by audience. The live gap is different and real: **MCP-host contributions and workflow ops partition INTO the agent catalog** (`:112` else-branch, `installWorkflowOps :87-95`) — an MCP tool declaring an OPERATOR audience is hidden by the emitter and dispatchable by the runner. So is any operation the emitter withheld for **availability** (`core.search-index` / `core.read-document` while the index is down, `AgentToolsOperationCatalog.java:165-169`, `:215-219`) or for **selection** (`request.selectedToolNames()`). |
| 4a | browse returns null (=allow) when the roots supplier throws, and when roots are empty | **CONFIRMED** | `BrowseTool.java:353-361` — `catch (Exception e) { … return null; // Degrade gracefully }` and `if (roots == null \|\| roots.isEmpty()) return null; // No roots configured — allow any path` |
| 4b | `validateAgainstRoots` is normalize+startsWith, no `toRealPath` | **CONFIRMED** | `AgentToolPaths.java:64-70`; contrast `FileOperationExecutor.isWithinRoots` `:111-127` + `resolveClosestExistingAncestor` `:129-150` |
| 4c | `resolveRelativePath` is first-match on duplicate root names | **CONFIRMED** | `AgentToolPaths.java:22-30` — returns on the first case-insensitive name match |

**Two consequences of the corrections.** (i) Splitting the `file-operations` family would *not* fix
finding 1, because the per-operation grant carries the same payload — so the fix must be a property
of the grant model, not of the family membership; 560 §28's family stays intact. (ii) Finding 3's
fix must cover audience, availability *and* selection, because all three are emitter-side filters
that resolution ignores; fixing only audience would be a `wrong-gate` mistake.

---

## §C. Design (2026-08-26)

### C.1 The principle

> **A durable consent may only cover invocations within the scope the user could foresee when
> granting it.** Two enforcement rules follow, both at the one place that knows a confirmation was
> skipped — the durable-grant short-circuit in `OperationExecutorImpl.enforceTrustLattice`:
>
> - **Risk ceiling.** A durable grant never satisfies a gate on a HIGH-risk operation. Destructive
>   work always costs a fresh, args-bound gesture.
> - **Argument scope.** A durable grant never covers an invocation whose arguments reach outside the
>   containment the grant was granted against. For the filesystem-reaching operations that means the
>   indexed roots.

This is not a new belief. `IntentGateEvaluator.agentGate` already carries the identical risk floor
on the **issuance** side ("HIGH/destructive never auto-fires", `IntentGateEvaluator.java:126-128`).
The defect is that **enforcement** has no matching floor, so issuance and enforcement disagree — the
exact drift class 550 thesis III exists to make impossible. C.2 makes them agree.

Why the enforcement point must be the gate and not the tool: only the gate knows whether a
confirmation happened. `IngestTool` cannot distinguish "the user just approved these exact paths in
a dialog that named them" from "a grant made months ago skipped the dialog" — and the first case
must stay allowed (that is 811 C-2a's MCP capability). The placement is forced by the problem, not
chosen for convenience.

### C.2 Move 1 — the risk ceiling on durable grants

`DurableGrantStore.isAllowed` takes the operation's `RiskTier` and refuses to answer true for HIGH,
whatever grant exists. The rule lives in the store rather than only at the executor call-site so a
future second consumer cannot re-open it, and so the store's own tests pin it.

**What this preserves.** 560 §28's `file-operations` family axis is untouched: a family grant still
auto-approves `core.ingest-files` (MEDIUM). What changes is that it no longer auto-approves
`core.file-operations` (HIGH) — the member 560 §28 added "so the axis is real", not because the
product wanted blanket destructive approval.

**What comes back as a prompt.** Exactly one thing: a durable grant (per-op or family) covering
`core.file-operations` no longer suppresses its typed confirm. Every other grant behaves as today.

**What it orphans.** The `AuthorizationHost` "Always allow this action (don't ask again)" checkbox
becomes a promise the backend will not keep for a HIGH-risk operation. Rather than leave a lying
control, the checkbox is hidden for a HIGH-risk prompt, and the catalog javadoc that advertises
"auto-approves both the ingest and the file-mutation tools at once"
(`AgentToolsOperationCatalog.java:305-307, 333-334`) is corrected in this same change. Teardown
rides along; it is not a follow-up.

### C.3 Move 2 — argument scope for filesystem-reaching operations

A small collaborator, `DurableGrantScope`, answers one question: *do these arguments fall inside the
containment this grant was granted against?* It is supplied together with the store —
`setDurableGrantStore(store, scope)` — so durable grants cannot be wired without a scope, and the
production scope governs `core.ingest-files`: every `paths` entry must canonicalize inside an
indexed root. Any other operation is in scope by definition (containment is not a defined concept
for it).

Out of scope ⇒ the grant does not apply ⇒ the gate falls through to the capsule path ⇒ the user gets
the confirm dialog, which already renders the arguments (`AuthorizationHost.ts` `authorization-args`).
So an out-of-root ingest is still possible — it just costs an approval that *names the path*. That
is exactly what 811 C-2a asked for: the capability is preserved ("agents ingesting arbitrary paths
is the point"), only the blanket-consent shortcut is removed.

**Fail-closed by construction.** Unknown roots (supplier unwired, throwing, or empty) means "cannot
prove containment" means the grant does not apply means a confirm. The failure mode of this
component is a prompt, never a silent ingest.

### C.4 Move 3 — the offered set is the resolvable set

`AgentToolEmitter` gains a default `offeredWireNames(catalog, selection)` **derived from `emit`** —
a projection of the one authority, not a second list that can drift (the `execution-surface`
projection-vs-fork discipline, applied to the tool surface). `AgentStepRunner` consults it at the
resolution site: a tool call naming an operation outside the offered set is refused with a typed
`ToolCallRejected` and a tool-role message, before `handleSafetyGate` and before any dispatch.

The set consulted is the **run-level authority** set (audience ∧ availability ∧
`request.selectedToolNames()`), deliberately *not* the per-iteration steering list. DECIDING's
handoff-only restriction and E0a's first-turn narrowing are prompt engineering; turning them into
hard denials would strand runs on a model's ordinary mis-step. Authority binds; steering nudges.
Recomputed at resolution rather than reusing the run-start snapshot, so a tool that became
unavailable mid-run is denied rather than dispatched.

**What it orphans.** `AgentOperationEmitter`'s javadoc claim that the audience allow-list is what
stops the LLM invoking admin operations (`:70-76`) — after this change the claim is true, and the
comment is rewritten to say where the enforcement actually is.

### C.5 Move 4 — per-tool fail-closed, decided explicitly

| Tool | Roots unavailable / empty | Rationale |
|---|---|---|
| `core.browse-folders` | **fail closed** | a directory-listing primitive is pure disclosure; nothing else bounds it |
| `core.search-index` (`path_prefix`) | fail open (unchanged) | results are bounded by Lucene index membership, which the roots already determined |
| `core.read-document` (`path`) | fail open (unchanged) | same bound — the Worker serves only indexed documents (868 §B.2) |

Plus two semantic repairs kept deliberately in place (no structural refactor — 877 owns
centralisation, finding 5): `validateAgainstRoots` canonicalizes both sides through the closest
existing ancestor's real path before comparing, so a symlink or junction cannot straddle a root
boundary; and `resolveRelativePath` refuses an ambiguous first component instead of silently taking
the first of two identically-named roots.

### C.6 Move 5 — undo is an operation, not an exemption

COPY-undo re-validates its target against the indexed roots before deleting anything (the same
check the MOVE/RENAME arm already makes), and the modified-since conflict check recurses into a
directory so an edit to a file *inside* a copied tree is a conflict, not a silent recursive delete.
Both arms of COPY-undo (file and directory) are validated; the finding named the directory arm, but
the file arm is unvalidated for the same reason.

### C.7 Reach of this design

**It is an instance of a principle that already exists here.** "One verdict, computed once, at the
chokepoint, read by everyone" is 550 thesis III, realized as `IntentGateEvaluator`. C.2 does not
invent anything — it restores that property where enforcement had silently diverged from issuance.
Conforming rather than paralleling.

**It reveals one principle worth naming.** *A list that informs a caller and a list that binds it
must be one list, or one derived from the other.* The agent loop's offered-vs-resolvable split
(finding 3) is the instance fixed here. Candidate scope elsewhere: the FE's rendered operation
buttons vs what the executor accepts; the MCP `tools/list` payload vs MCP dispatch; any future
plugin tool surface. **Existing violations:** at least the MCP surface, unexamined here.
**Not generalized now** — one derived accessor on `AgentToolEmitter` is what this problem requires.

- *Evidence it earns its keep:* a subsequent change adds an operation to the agent partition (or an
  MCP server contributes one) with a restrictive audience/availability, and the model cannot invoke
  it without anyone having to remember a second filter.
- *Retirement condition:* if the offered and resolvable sets are ever unified into a single
  structure (so the derivation is a no-op), delete the principle rather than keep restating it.

**Second principle, weaker, recorded not built.** *The reversal of an operation is an operation and
inherits its risk class.* Undo bypassed validation in exactly the arm where the reversal is more
destructive than the forward op. Candidate scope: any future inverse-operation mechanism
(`inverseOperationRef` is already declared in the policy). Retirement condition: if inverses are
ever dispatched through the same executor path as forward operations, the property is structural and
the principle is redundant.

### C.8 Explicitly not done here

- **Process/object-capability sandboxing of the agent** (§A.2 framing 2) — ADR-0035 / 560 §9.2.
- **The second ingest surface.** `KnowledgeSearchController.handleIngest` is an independent ingest
  path (811's own correction note). Not this tempdoc's subject; its consent posture is unexamined.
- **Structural centralisation of the path helpers** — 877's, by agreement (finding 5).

---

## §D. Plan

Ordered; each step lists its own verification. Items 1–5 are the moves; 6–8 are closure.

1. **Risk ceiling (Move 1).**
   - `DurableGrantStore.isAllowed(operationId, capabilityFamily, risk, sourceTier)` returns false
     for `RiskTier.HIGH` before consulting either grant set; the 2-arg convenience overload takes
     the risk too. No back-compat overload retained.
   - `OperationExecutorImpl.enforceTrustLattice` passes `op.policy().risk()`.
   - Correct the two `AgentToolsOperationCatalog` javadocs that advertise the old semantics.
   - Hide the "Always allow this action" checkbox in `AuthorizationHost` when the prompt's risk is
     HIGH (teardown of the now-false promise).
   - Tests: `DurableGrantStoreTest` — a family grant and a per-op grant both fail to authorize a
     HIGH operation and still authorize a MEDIUM one; `OperationExecutorImplTest` — dispatching a
     HIGH op with a matching durable grant and no capsule throws `ConfirmationRequiredException`,
     while the MEDIUM sibling proceeds. `AuthorizationHost.test.ts` — checkbox absent for a HIGH
     prompt, present for MEDIUM.
2. **Argument scope (Move 2).**
   - New `DurableGrantScope` (functional interface) + `IndexedRootGrantScope` in
     `io.justsearch.app.services.intent`; `setDurableGrantStore(store, scope)` requires both.
   - Wire in `OperationSubstrateInit`; expose the scope on its `Output` so `HeadAssembly` binds the
     live roots supplier at the agent-tools registration memo (fail-closed until bound).
   - Tests: out-of-root `paths` ⇒ grant does not apply ⇒ `ConfirmationRequiredException`; in-root
     ⇒ proceeds; **adverse precondition** — roots supplier unwired / throwing / empty ⇒ confirm, not
     silent proceed (`green-masked-destructive`).
3. **Offered = resolvable (Move 3).**
   - `AgentToolEmitter.offeredWireNames(catalog, selection)` default, derived from `emit`.
   - `AgentStepRunner` refuses an un-offered tool call with `ToolCallRejected` + a tool-role message
     before the safety gate.
   - Rewrite the `AgentOperationEmitter` javadoc that mis-describes the filter as the enforcement.
   - Tests: a dispatcher/step-runner test where the model names an operation present in the catalog
     but withheld by audience, one withheld by availability, and one withheld by selection — all
     three rejected, none dispatched; and a control where an offered tool still dispatches.
4. **Path containment (Move 4).**
   - `BrowseTool.validateParentPath` fails closed on throw and on empty roots.
   - `AgentToolPaths.validateAgainstRoots` canonicalizes via closest-existing-ancestor real path.
   - `AgentToolPaths.resolveRelativePath` refuses an ambiguous root name.
   - Tests: `BrowseToolTest` adverse preconditions (throwing supplier, empty roots) reject with a
     message naming the reason; a junction/symlink escape is rejected where the platform supports
     creating one, else the canonicalization is asserted directly; duplicate-root-name resolution
     returns null. Existing browse fixtures get real roots rather than relaxed assertions.
5. **Undo containment (Move 5).**
   - `FileOperationExecutor` exposes a package-private containment check; `FileOperationsTool.undo`
     validates every COPY-undo target (file and directory) before deleting.
   - `modifiedSince` recurses for a directory target, short-circuiting on the first newer entry.
   - Tests: `FileOperationsToolTest` — undo of a copied directory outside the roots is skipped and
     reported, not deleted; undo of a copied directory whose *nested* file was edited is reported as
     changed-since and not deleted; undo of an untouched in-root copied directory succeeds.
6. **Verification.** `gradle-locked.sh spotlessApply` → `build -x test` → full `test`; ui-web
   `typecheck` + `test:unit:run` (Move 1 touches `AuthorizationHost.ts`) + the `ui-web-gates` recipe;
   `--gate operation-surface` if any operation surface changed.
7. **Critical-analysis pass**, then one independent refute-first opus reviewer on the diff.
8. **PR** per the common brief; report.

### D.1 Regression-test ↔ finding map (acceptance)

| Finding | Test that fails on `main`, passes here |
|---|---|
| 1 (risk) | `DurableGrantStoreTest` HIGH-refusal + `OperationExecutorImplTest` HIGH-with-grant ⇒ `ConfirmationRequiredException` |
| 1 (containment) | `OperationExecutorImplTest` out-of-root ingest args with a durable grant ⇒ `ConfirmationRequiredException` |
| 2 | `FileOperationsToolTest` directory-undo out-of-roots + nested-edit cases |
| 3 | step-runner/dispatcher test: audience-, availability- and selection-withheld tools all rejected |
| 4 | `BrowseToolTest` throwing-supplier + empty-roots rejection; `AgentToolPathsTest` realpath + ambiguous-root |
