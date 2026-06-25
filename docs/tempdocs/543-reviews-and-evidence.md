---
title: "543 — Reviews & evidence (consolidated independent-review fragments)"
type: tempdocs
status: done
consolidated: 2026-06-09
---

# 543 — Reviews & evidence (consolidated independent-review fragments)

> Consolidated 2026-06-09 from 26 small independent-review fragments of the 543 contribution-substrate effort (orig. tempdoc 526). The substantive 543 docs (543-three-axes parent, prior-art, fwd-live-evidence, fwd-future-directions) stay standalone; these per-slice/followup verdicts are folded here. Originals in git.

---

## slice-1-review

*(consolidated from `543-slice-1-review.md`)*

### 547 — Independent reviewer verdict (Provenance substrate port)

**Reviewer**: independent-reviewer subagent dispatched from `worktree-507-kernel-boundary`.
**Date**: 2026-05-24
**Scope**: Verify the port of the Provenance substrate from `worktree-507-kernel-boundary`'s `@kernel/*` reference impl to main's `shell-v0/*` paths, as enumerated in `docs/tempdocs/547-provenance-substrate-port.md`.
**Discipline**: `verdict-is-gate` — implementing agent ≠ reviewer; this verdict is the merge gate for this slice.

## Verdict: APPROVE

The port preserves the reference impl's API surface and semantics, makes the wire-boundary risk negligible by keeping the extension fields TS-only, sweeps all nine declared registries uniformly, wires a real production consumer in `Shell.ts`-mounted `<jf-status-deck>` chrome, and arrives with strictly *more* test coverage than the reference (port adds an end-to-end `resolveProvenance` describe block the reference impl lacked).

## Critical findings (block merge)

None.

## Material follow-ups (should fix soon, do not block merge)

1. **CORE-side wrapping asymmetry on StatusDeck left side** (`StatusDeck.ts:302-308`).
   Plugin items get wrapped in `<span class="group plugin-item" data-plugin-item=${id}>…<jf-provenance-chip>…</span>`; core items go through `renderCoreItem(item.id)` and skip both the wrapper and the chip. Today this is correct (chip would render `nothing` for CORE anyway because `isNonCore(CORE_PROVENANCE) === false`), so the production output is identical. But if a future change pushes a CORE registration through with an explicit non-CORE `provenance` (e.g., a built-in surface that is plugin-attributed for some reason), the asymmetry will silently lose attribution on the left side. Two clean options for the next slice that touches StatusDeck:
   - Always render through one branch and let `<jf-provenance-chip>` self-suppress for CORE (matches the design intent that "kernel renders, plugins request"), or
   - Keep the split but assert (in a test) that `item.source === 'core'` ↔ `resolveProvenance(item).tier === 'CORE'` at registration time.

2. **Tooltip / accessible-name on CORE items.** Once the chip becomes the universal attribution surface, the absence of a chip for CORE items removes the only place where contribution attribution surfaces in the UI. If a future slice retires the `source` field, consider whether the StatusBar exposes contribution attribution via title/aria for first-party items too. Out of scope for 547.

3. **Sweep of existing CORE registrations to stamp `provenance: CORE_PROVENANCE`** (already called out in tempdoc §4). Once the legacy `source` field retires, the resolver becomes a no-op and can be deleted. Until then the duplication is benign.

## Polish nits

1. **JSDoc drift between port and reference.** The reference impl's `provenance.ts` carries `// review: undefined — implicit at every commit, no explicit display` and `// capability: undefined — kernel-wide` inline comments at the `CORE_PROVENANCE` literal (lines 48-49 of `507-kernel-boundary/.../provenance.ts`); the port elides them. Not a defect — the extension fields are optional and default to undefined regardless — but they were useful hints for future readers. Restore at next touch.

2. **Reference impl's chip docstring mentions "settings panels, inspector tabs, status-bar items, command-palette entries"; port narrows to "status-bar items, inspector tabs, command-palette entries, context-menu items".** Both lists are accurate; the port aligned with the actual nine-registry sweep. No action.

3. **`provenance.test.ts` adds 10 tests for `resolveProvenance` beyond the reference's 13 tests.** This is a strict improvement and should be back-ported to `worktree-507-kernel-boundary` if that branch outlives this slice (it likely doesn't, per the 543 §17 architectural divergence verdict).

## Notes

### Faithfulness of port

API surface diff between `507-kernel-boundary/.../kernel/primitives/provenance.ts` and `543-impl/.../shell-v0/primitives/provenance.ts`:

| Export | Reference | Port | Drift |
|--------|-----------|------|-------|
| `Provenance` (re-export) | yes | yes | none |
| `ProvenanceTier` (re-export) | yes | yes | none |
| `CORE_PROVENANCE` | tier=CORE, contributorId=core, version=0, identity.verified=true, frozen | identical | none |
| `makeCoreProvenance()` | returns CORE_PROVENANCE | identical | none |
| `makePluginProvenance(id, version, tier='TRUSTED_PLUGIN', extras?)` | populates `installedAt`, freezes | identical | none |
| `displayTier(p)` | CORE → CORE, UNTRUSTED_PLUGIN → UNTRUSTED, identity.verified → VERIFIED, else TRUSTED | identical | none |
| `isNonCore(p)` | `p.tier !== 'CORE'` | identical | none |
| `resolveProvenance({provenance?, source?, pluginId?, owner?})` | explicit > source mapping > CORE fallback | identical | none |

The two files are byte-equivalent in API behavior. The chip components (`507-kernel-boundary/.../ProvenanceChip.ts` vs `543-impl/.../shell-v0/components/ProvenanceChip.ts`) are also functionally identical — same CSS, same render branches, same custom-element registration (`jf-provenance-chip`). The port's only meaningful divergences are header-comment phrasing and import paths (`../primitives/provenance.js` vs `./provenance.js`), both of which reflect the path differences between the two trees.

### Wire-shape boundary (item §2 from review brief)

The `Provenance` interface in `modules/ui-web/src/api/types/registry.ts` mirrors `Provenance.java`'s 3-field shape (`tier` / `contributorId` / `version`). The slice adds four optional readonly TS-only fields: `identity` / `review` / `capability` / `installedAt`. The interface's `JSDoc` (lines 130-140) explicitly documents that the extension fields do NOT cross the wire.

Verified by grep across `modules/ui-web/src`:
- No `JSON.stringify(...provenance...)` round-trip on the registry-contribution `Provenance` (the only `JSON.stringify` paths I found involve unrelated payloads).
- The only schema-level `provenance` mention in `api/schemas.ts` is `HitProvenanceSchema` for search-hit attribution — a different concept; unrelated to registry `Provenance`.
- The `InvocationProvenance` mentions in `shell-v0/router/{transports.ts, invocationHandler.ts}` are a separate type (operation invocation source), not the contribution `Provenance`.
- There is no FE→BE POST path that ships a `Provenance` value back to the server.

Conclusion: extension fields are local-only by construction. Wire contract preserved.

### Uniform sweep correctness (item §3)

All nine declared registries have `import type { Provenance }` from the same path (`../primitives/provenance.js`) and add `readonly provenance?: Provenance` to the relevant interface. Verified by grep:

- `StatusBarRegistry.ts:15, 24`
- `InspectorTabRegistry.ts:11, 20`
- `ContextActionRegistry.ts:12, 23`
- `EmptyStateRegistry.ts:19, 38`
- `KeybindingRegistry.ts:13, 30`
- `TemplateCatalog.ts:35, 109`
- `WalkthroughRegistry.ts:26, 63`
- `SelectionActionRegistry.ts:31, 75`
- `CommandRegistry.ts:14, 24`

All `readonly`, all optional, all typed via the same primitive re-export. No drift.

### Consumer is real (C-018) (item §4)

`<jf-status-deck>` is mounted in production chrome at `modules/ui-web/src/shell-v0/chrome/Shell.ts:1423` (`html\`<jf-status-deck api-base=${this.apiBase}></jf-status-deck>\``), guarded by `isStatusDeckVisible()`. The element is also styled at `Shell.ts:337`. This is the same Shell that hosts the entire UI; the consumer is not a demo or test path.

`StatusDeck.render()` (lines 295-315) renders `<jf-provenance-chip .provenance=${resolveProvenance(item)}></jf-provenance-chip>` next to every plugin-contributed item on both left and right sides. `resolveProvenance` with `source: 'core'` returns `CORE_PROVENANCE`, and `isNonCore(CORE_PROVENANCE)` returns `false`, so the chip's `render()` returns `nothing` — no visual noise on CORE. For `source: 'plugin'` (or explicit non-CORE `provenance`) the chip renders. The branching is internally consistent and matches the C-018 substrate-without-consumer requirement.

(See follow-up §1 for the latent asymmetry concern on the left-side CORE-vs-plugin branching: today it's correct, but it would silently fail if a CORE item were ever to carry an explicit non-CORE provenance — which the type system permits.)

### Name-collision risk (item §5)

`ProvenanceBadge` registers `customElements.define('jf-provenance-badge', …)` (line 212); `ProvenanceChip` registers `customElements.define('jf-provenance-chip', …)` (line 114). Distinct custom-element tag names, distinct class names, distinct files, distinct purposes (one renders user-config overrides as a popover; the other renders per-contribution attribution). No collision. The 543 §12.2 confidence-pass naming concern is resolved by the chip's distinct identity.

### Tests (item §6)

`provenance.test.ts` (23 tests, port vs reference's 13): all critical branches covered.
- `identity.verified → VERIFIED` — asserted at line 93-98.
- `UNTRUSTED_PLUGIN → UNTRUSTED` — asserted at line 83-86.
- `TRUSTED_PLUGIN without identity → TRUSTED` — asserted at line 89-91.
- `CORE → CORE` — asserted at line 79-81.
- `source: 'user' → CORE` (the §M1 review correction from 543) — asserted at line 120-122.
- `source: 'plugin'` fallback with/without `pluginId`/`owner` — asserted at lines 124-139.
- `frozen` invariants on `CORE_PROVENANCE` and `makePluginProvenance` result — asserted.

`ProvenanceChip.test.ts` (7 tests):
- Renders nothing for undefined provenance — line 30-34.
- Renders nothing for CORE provenance — line 36-41 (this is the C-018-defining branch: "chip silent for CORE").
- Renders chip for TRUSTED_PLUGIN — line 43-52 (includes label/contributorId/version assertions).
- `untrusted` CSS class for UNTRUSTED_PLUGIN — line 54-62.
- `verified` CSS class + `✓` mark for identity.verified — line 64-74.
- Omits version span for `version: '0'` sentinel — line 76-86.
- Tooltip includes review timestamp and capability count — line 88-99.

Tests are precise: each assertion tests one branch, and each branch's pass condition distinguishes the correct outcome from incidental side-effects. The `chip.classList.contains('verified')` + `chip.getAttribute('data-display-tier')` + `chip.querySelector('.chip-verify-mark')` triple-check on the VERIFIED case is exactly the kind of right-reason-vs-wrong-reason discrimination the substrate-discipline test-precision rule asks for.

### Verification budget

Per brief: I read the files, cross-referenced with the reference impl, did not run the build/tests (already declared green by implementer), and did not spawn further subagents.

---

## slice-2-review

*(consolidated from `543-slice-2-review.md`)*

### 548 — Independent reviewer verdict (Scope substrate port)

**Reviewer**: independent (not the implementer of 548)
**Date**: 2026-05-24
**Scope**: tempdoc 548 — port of the Scope substrate from
`worktree-507-kernel-boundary` reference implementation to main's
`shell-v0/substrates/scope/`, plus the six-slot extension of
`ShellContext` and the `Shell.ts` production consumer.

## Verdict: APPROVE-WITH-FOLLOWUPS

The slice is faithful to the §3.B contract, the production wiring is
correct end-to-end (boot + profile-switch project, teardown nulls the
handle), the deferred-slot defaults align with `whenExpression.ts`'s
nullish semantics, and the test suite covers the replace-vs-patch
contract precisely. Two follow-ups (snapshot-shape divergence from
reference, missing assertion) are noted below; neither is a correctness
regression and neither blocks merge.

## Critical findings (block merge)

None.

## Material follow-ups (should fix soon, do not block merge)

**F1. ScopeSnapshot field types diverge from the reference impl.**
Reference (`worktree-507-kernel-boundary` `kernel/substrates/scope/index.ts`):
all snapshot ID fields are `readonly activeCorpusId?: string;` (i.e.
`string | undefined` only).
Main port (`shell-v0/substrates/scope/index.ts:62-68`): the same fields
are `readonly activeCorpusId?: string | null;` (admitting an explicit
`null` value in the snapshot).

Functionally this works — `replace` mode coalesces `null ?? null` →
`null`, and `serializeScope` already filters with `!== null` so it
never emits a literal `null` itself. But a caller round-tripping
through JSON or constructing a snapshot by hand can now produce a
shape the reference type-system would have rejected (`{ activeCorpusId:
null }`). The reference's tighter `string | undefined` was a
deliberate "absent = omit the key" invariant; the port relaxes it.

Resolution options: (a) narrow the port back to `string` only (and let
`serializeScope` omit absent keys) for parity with the reference, or
(b) document explicitly in §3 that the persisted JSON shape allows
`null` as an alternative absence marker. Either is fine — pick one and
state it. Slice 10 (Workspace Profiles) is the consumer that will care.

**F2. The "replace clears absent fields" test only verifies two of the
six cleared slots.** `scope.test.ts:73-79` exercises `activeCorpusId`
(null) and `audience` (`''`), but does not assert clearing for
`activeLibraryId`, `preferredModelId`, `activeAgentRole`, or
`enabledPluginIds`. Today all six pass because the implementation is
uniform, but a future refactor that drops one of the four unasserted
clears from the `replace` branch would not be caught by this test.

Resolution: extend the existing `it('clears fields absent from
snapshot ...')` to set + clear all six slots, not two. One assert per
slot.

## Polish nits

**P1. Replace-mode docstring vs activeProfileId.** The docstring at
lines 109-117 says `restoreScope` defaults to replace semantics; the
test `it('preserves activeProfile when snapshot omits activeProfileId')`
at line 81 verifies the documented activeProfileId exception. That's
correct. But the docstring buries the exception in the second
paragraph; a quick reader skimming the first paragraph might think
"replace clears everything." Consider promoting the activeProfileId
exception to the first sentence or a `**Exception**:` bullet.

**P2. `serializeScope` mutability cast.** Lines 89-97 cast the
`snapshot` constant through an inline `Mutable` shape to set fields
conditionally. This works but reads as two indirections (declare
const, cast to mutable alias, write through alias). A single
`const snapshot: Mutable = {}` followed by a `return snapshot as
ScopeSnapshot` at the end would be one fewer cast and equivalent
type-safety. Not load-bearing.

## Notes

**N1. Faithfulness to reference API.** ✓
Public symbols exported by main's `scope/index.ts` exactly match the
reference: `ScopeSnapshot`, `serializeScope()`, `restoreScope(snap,
mode)`, `getScope()`. The four-symbol surface is preserved.
Replace-vs-patch semantics match: replace clears absent fields (with
the documented `activeProfileId` exception), patch preserves them.

**N2. ShellContext extension correctness.** ✓
- `ShellContext` (`shellContextState.ts:84-95`) adds all six slots with
  the documented types: 4× `string | null` (IDs) + 2× `string`
  (`enabledPluginIds`, `audience`).
- `DEFAULT_CONTEXT` (`shellContextState.ts:114-119`) initializes all
  six: `null` for IDs, `''` for the two string slots.
- `updateShellContext`'s structural-equality early-return
  (`shellContextState.ts:169-174`) covers all six. I verified by line
  count: 6 new conjunct lines for 6 new fields. Missing any would
  silently swallow updates — none is missing.
- The pre-existing `shellContextState.test.ts` still passes; the
  defaults test (`shellContextState.test.ts:20-29`) only asserts a
  subset of fields, so adding new defaults is non-breaking.

**N3. Default-rule contract alignment (§12.3 #4).** ✓
Re-read `whenExpression.ts:302-308` (`truthy`) and `:317-364`
(`evaluateNode`):
- A bare key on a `null` value → `truthy(null)` → false. ✓
- `activeCorpusId == 'corpus-a'` against `null` → `String(null) === 'corpus-a'`
  → `'null' === 'corpus-a'` → false. ✓ (note: not equal to `'null'`
  string either, which is the right behavior — predicates can't
  accidentally match a literal `'null'`).
- `audience == 'DEVELOPER'` against `''` → `'' === 'DEVELOPER'` →
  false. ✓
- Membership `pluginX in enabledPluginIds` against `''` →
  `hay.split(',').filter(Boolean)` → `[]` → not present → false. ✓

All four default-slot patterns evaluate false silently — contract
satisfied. The reference impl claims the same against
`@kernel/predicates/when.ts`; main's `whenExpression.ts` has the same
nullish handling, so the port preserves the §12.3 #4 invariant.

**N4. Production consumer (C-018).** ✓
- `Shell.ts:261` declares `scopeProfileSwitchUnsub: (() => void) | null
  = null` (initialized to null in the field declaration, so a remount
  with no boot path still starts cleanly).
- `Shell.ts:549-552` (connectedCallback): calls
  `applyActiveProfileToScope()` then assigns the subscription handle.
- `Shell.ts:949-950` (disconnectedCallback): calls
  `this.scopeProfileSwitchUnsub?.()` then `= null`. Both calls and
  nulling are present; no leak on remount.
- `Shell.ts:283-291` (`applyActiveProfileToScope`): uses `'patch'`
  mode — correct per the slice tempdoc rationale (deferred slots have
  no per-profile producer today, so replace would stomp them to
  null/'').
- `getProfile(getActiveProfileId())?.viewerAudience ?? ''` —
  `viewerAudience` is declared `Audience | undefined` (Audience is a
  string union per UserStateDocument.ts), so `?? ''` is type-safe and
  yields a string-typed slot value. ✓

**N5. Pre-existing-on-main wiring claim.** ✓
The slice tempdoc claims `ShellContext.activeProfile` was stuck at
`'default'` because nothing on main wrote it. I verified by grepping
`updateShellContext\([^)]*activeProfile\b` across `modules/ui-web/src`:
**zero matches outside the new test file `scope.test.ts`**. Before
this slice, no production callsite wrote `activeProfile` directly.
The slice's `applyActiveProfileToScope` → `restoreScope` is now the
sole producer. Claim is accurate.

**N6. Test precision spot-check.** ✓ with one gap noted in F2.
- "replace clears absent fields" (`scope.test.ts:73-79`): asserts
  `activeCorpusId` is `null` and `audience` is `''` — precise on the
  cleared *values*, not just "the slot was processed." ✓ (F2: only
  2/6 slots covered).
- "preserves activeProfile when snapshot omits activeProfileId"
  (`:81-85`): sets `activeProfile: 'research'`, calls `restoreScope({
  activeCorpusId: 'c' })` (no activeProfileId), asserts `'research'`
  is preserved. Covers the documented exception precisely. ✓
- "updates activeProfile when snapshot carries activeProfileId"
  (`:87-90`): the symmetric positive case. ✓
- "round-trip" (`:107-127`): resets context between serialize and
  restore, so the restore is not a no-op against existing state. ✓
- "omits ephemeral fields" (`:48-62`): casts to `Record<string,
  unknown>` to assert the snapshot keys don't exist — appropriate
  shape-assertion for a typed interface that wouldn't expose them
  otherwise. ✓

**N7. Verification budget.** Honored: read-only review. Did not run
build or tests (already green per slice tempdoc §5). Did not dispatch
subagents.

---

**Recommendation for the implementing agent:** F1 + F2 are
should-soon-not-now. Slice 10 (Workspace Profiles) will be the
consumer that exercises ScopeSnapshot's persisted shape; resolving F1
before that slice keeps the persistence boundary clean. F2 is a
one-line test extension and can land as part of slice 10's prep.

---

## slice-3-review

*(consolidated from `543-slice-3-review.md`)*

### 549 — Independent reviewer verdict (EvaluationContext substrate port)

**Reviewer**: independent subagent (not the implementing agent)
**Date**: 2026-05-24
**Subject worktree**: `F:\JustSearch\.claude\worktrees\543-impl`
**Reference**: `F:\JustSearch\.claude\worktrees\507-kernel-boundary\modules\ui-web\src\kernel\predicates\evaluationContext.ts`
**Slice tempdoc**: `docs/tempdocs/549-evaluation-context-substrate-port.md`

## Verdict: APPROVE

## Critical findings (block merge)

None.

## Material follow-ups (should fix soon, do not block merge)

None.

## Polish nits

1. **Composer doctring under-specifies fact > environment ordering.**
   `buildEvaluationContext` JSDoc (substrates/evaluationContext/index.ts §"Build the layered EvaluationContext…", lines 208-219) says "TargetFacts and EnvironmentSignals OVERRIDE Scope keys on conflict" but does not state that TargetFacts ALSO override EnvironmentSignals. The implementation spread `{...scope, ...environment, ...facts}` does enforce facts > env > scope, and the test at lines 87-97 verifies it ("projector's `now` overrides env's `now`"), so behavior is correct; only the prose elides the env vs facts precedence. Inherited from the 507 reference verbatim — port did not introduce it. Worth a one-line addendum if touching this block again.

## Notes

### Faithfulness — reference vs port

Diff of `kernel/predicates/evaluationContext.ts` (reference) vs `shell-v0/substrates/evaluationContext/index.ts` (port) shows only:

- Docstring re-anchoring: 507's KP/KS/FM/PS layering replaced with §19 KCS-bridge framing (correct — main has no 507 layering).
- Boot-wiring docstring: `boot/compose.ts` → `Shell.ts` (correct — port consumer is Shell.ts).
- Import path: `Addressable` / `AddressableKind` from the new shared `../addressable.js` instead of inline in `substrates/actions/index.js`.
- Trivial line-wrap differences.

Public API is verbatim-identical:
- `Projector` type, `registerProjector`, `unregisterProjector`, `getProjector`, `listProjectors`, `subscribeProjectors`, `__resetProjectorRegistryForTest`
- `bumpScopeVersion`, `getScopeVersion`
- `BuildEvaluationContextOptions`, `buildEvaluationContext`
- Memo key shape (`${addressable.kind ?? 'null'}\x00${addressable.id}`) — identical
- Lazy eviction via version check at read time — identical
- Composition order `{...scope, ...environment, ...facts}` — identical
- `register`/`unregister` triggers `bumpScopeVersion()` — identical
- "Fast path: no addressable → return scope unmodified when env empty" — identical
- "No projector for kind → scope (or scope+env if env non-empty)" — identical

### Addressable extraction (focus #2)

Reference inline definition at `kernel/substrates/actions/index.ts` lines 69-88:

```ts
export type AddressableKind =
  | 'search-result' | 'citation' | 'document-passage'
  | 'agent-tool-call' | 'inspector-row' | 'corpus-item' | 'plugin' | null;

export interface Addressable {
  readonly kind: AddressableKind;
  readonly id: string;
  readonly payload: unknown;
}
```

Port at `shell-v0/substrates/addressable.ts` lines 22-42: union members and order identical; interface fields, modifiers, and types identical. Module-augmentation contract preserved in the docstring. Slice 7 (Action substrate) can re-import the same shared module.

### Memoization correctness (focus #3)

All three required properties are verified by precise test assertions:

1. **Same `(scopeVersion, kind, id)` → cache hit.** Test "repeated calls within same scope-version hit memo" (lines 109-118): registers projector (one `bumpScopeVersion` happens), then 3× `buildEvaluationContext` against the same Addressable. Asserts `expect(projector).toHaveBeenCalledTimes(1)` — i.e., exactly one projection across 3 evaluations. Distinguishes 1× from 3×. ✓
2. **Different `id` → distinct cache entries.** Test "different addressables of same kind cache separately" (lines 131-141): 2× builds with ids `'a'` and `'b'`; asserts `toHaveBeenCalledTimes(2)`. ✓
3. **`bumpScopeVersion()` invalidates.** Test "bumpScopeVersion invalidates memo" (lines 120-129): build → assert 1 call; bump; build → assert 2 calls. Lazy-version-check eviction path covered. ✓

Memo key encoding uses NUL (`\x00`) as a separator so kind/id with embedded delimiters cannot collide — verbatim from reference.

### Override semantics (focus #4)

- Composition: `{...scope, ...environment, ...facts}` ⇒ facts > environment > scope.
- Test "environment facts override Scope but not target-facts" (lines 87-97) registers a projector emitting `{ now: 'projector-value' }`, passes `environment: { now: 'env-value', audience: 'env-audience' }`, with `audience: 'USER'` in scope. Asserts BOTH branches: `audience === 'env-audience'` (env > scope) AND `now === 'projector-value'` (facts > env). ✓

### Consumer #1 — ContextActionRegistry (focus #5)

`listContextActions(context?, payload?, addressable?)` at `commands/ContextActionRegistry.ts` lines 75-92:

- **Back-compat.** `ContextMenu.openContextMenu` (`components/ContextMenu.ts:311`) calls `listContextActions(opts.context)` with one arg. The new third param is `addressable?: Addressable | null` (optional). Existing 2-arg callers in `whenExpressionIntegration.test.ts` (lines 114, 116, 130, 131, 145, 160, 161, 163) work unchanged. The function body's `addressable ?? null` fallthrough produces Scope-only evaluation context — identical to pre-substrate (`getShellContext()` projected directly).
- **Substrate on hot path even with null addressable.** Line 80-83: `buildEvaluationContext({ scope: getShellContext()…, addressable: addressable ?? null })`. The substrate is unconditionally invoked; the fast-path branch in `buildEvaluationContext` returns the scope unmodified when no addressable + no environment, so back-compat behavior is byte-for-byte identical to the prior `evaluateWhen(a.when, getShellContext())` shape.

### Consumer #2 — Shell.ts boot wiring (focus #6)

- **Handle declared.** `private evalContextScopeBumpUnsub: (() => void) | null = null;` at line 269.
- **Initialized in connectedCallback.** Lines 562-569: comment block then `this.evalContextScopeBumpUnsub = subscribeShellContext(() => { bumpScopeVersion(); });`.
- **Torn down + nulled in disconnectedCallback.** Lines 968-969: `this.evalContextScopeBumpUnsub?.(); this.evalContextScopeBumpUnsub = null;`.
- **Immediate-fire acknowledgment.** Comment at lines 564-566: "subscribeShellContext fires the listener once immediately with the current state; that initial fire is fine (just primes version 1)." Confirmed against `shellContextState.ts:132` (`listener(current)` on subscribe). Behavior aligns with docstring.

### C-018 substantiveness (focus #7)

Both consumers are real production paths, not scaffolding:

- **ContextMenu / openContextMenu** is the right-click chrome surface. `BrowseSurface.ts:455` calls `openContextMenu({...})` in production search-result row handlers. Not a demo panel.
- **Shell.ts** is the root chrome host (custom-element extending HTMLElement). `connectedCallback`/`disconnectedCallback` are the lifecycle entrypoints — not test paths.

### Tests (focus #8)

13 tests across three describe blocks: projector registry (4), composer (5), memoization (4). Spot-checked test precision in focus #3; no over-permissive matchers (`toHaveBeenCalled` would be insufficient; `toHaveBeenCalledTimes(N)` with exact N is used).

### Verification budget honored

No build/test runs performed; slice claim of 13/13 targeted green + 1745/1745 full + typecheck-clean is accepted on its assertion (re-verifying would burn the no-build budget). No subagents spawned.

---

**Verdict rationale:** Port is a faithful translation with appropriate path/import adjustments and idiomatic docstring updates. Addressable extraction is verbatim. Memoization invariants are precisely tested. Override semantics are correct and exercised. Both production consumers are real chrome paths, not scaffolding. No critical findings, no material follow-ups. Single polish nit (composer JSDoc env-vs-facts ordering) is inherited from the 507 reference and does not block merge.

---

## slice-4-review

*(consolidated from `543-slice-4-review.md`)*

### 550 — Independent reviewer verdict (Effect Journal substrate port)

## Verdict: APPROVE-WITH-FOLLOWUPS

Faithful port of the reference implementation at
`worktree-507-kernel-boundary` `modules/ui-web/src/kernel/substrates/effects/index.ts`.
Persistence is safe (probe + try/catch in all four paths: probe, write, parse, version-check).
Per-Provenance read isolation is correct. Undo cursor walks past null-inverse entries as specified.
Both production consumers (NavigationJournal, Shell boot) are real wires in mounted chrome.

The two material follow-ups below are behavioral inconsistencies in the
NavigationJournal consumer, not in the substrate itself, and do not
constitute a wire-contract break — but they should be resolved (one way
or the other) before Slice 7 lands `applyEffect` and the Effect Journal
becomes the canonical undo source-of-truth.

---

## Critical findings (block merge)

None.

---

## Material follow-ups

### F1. NavigationJournal de-dup branch skips `recordEffect`

`modules/ui-web/src/shell-v0/state/NavigationJournal.ts:146-151`

When the new navigation lands on the same surface as the cursor entry
(typical case: a query refinement on `/search`, a filter toggle on the
same surface), the de-dup branch mutates the entry's `url`/`label`/
`transport`/`timestamp` in place, persists + fires listeners, then
**returns before the `recordEffect` call at line 183**. Effect Journal
sees only first-time surface transitions, not subsequent in-surface
URL changes.

Consequence for downstream consumers:

- **Undo**: A user types "alpha", types "beta", types "gamma" on the
  same search surface — only the first transition is in the Effect
  Journal. Ctrl-Z (once Slice 7 wires it) cannot walk back through
  the query refinements; it jumps to the previous surface.
- **Audit**: Same problem — the audit trail loses every in-surface
  URL mutation.
- **Macro**: Same problem — recorded macros lose the query edits.

The brief's intent ("every navigation in main now lands in the Effect
Journal as a typed entry" — tempdoc §1.4) is not satisfied for the
de-dup case. Two viable resolutions:

1. Move `recordEffect({kind: 'navigate', to: url}, CORE_PROVENANCE)`
   above the de-dup early-return, so it fires for every URL change
   regardless of branch.
2. Document explicitly in NavigationJournal that the de-dup branch
   is opaque to the Effect Journal by design (consequence: Undo of a
   query refinement is unsupported until per-surface state-versioning
   ships).

Option 1 is mechanically smaller and matches the reviewer-brief's
stated invariant. The de-dup branch in NavigationJournal exists for
its own ring-buffer accounting (one entry per surface), not for the
Effect Journal — they should not be coupled.

Recommend Option 1.

### F2. Persisted Provenance loses object identity after restore

`modules/ui-web/src/shell-v0/substrates/effects/index.ts:124`

The persisted `entries` are round-tripped through `JSON.stringify` /
`JSON.parse`. After restore, each entry's `invokedBy` is a plain
object, not `CORE_PROVENANCE` (which is `Object.freeze`d at module
load). `invokedBy === CORE_PROVENANCE` returns `false` for restored
entries.

Today this is benign — `listJournalFor(contributorId)` compares
`.contributorId` (a string), and no other code in the substrate
relies on Provenance identity. Logging it for the record because:

- A future consumer that compares Provenance by reference (e.g., for
  fast `is-core` checks before the structural `displayTier()` call)
  would silently misclassify restored entries.
- If `Provenance.identity` ever grows a non-serializable field (e.g.,
  a `crypto.subtle` key reference), restore would silently lose it.

Mitigation options if/when needed: re-normalize through `resolveProvenance`
on restore, or store `contributorId` only and rehydrate Provenance
from a per-contributor registry. Not a Slice 4 blocker.

---

## Polish nits

### P1. `_resetInMemoryOnly` test helper relies on save/restore around the public reset

`effects.test.ts:180-188`

The helper saves `localStorage`, calls `__resetJournalForTest()` (which
clears storage), then writes the saved value back. Works, but the
intent ("reset in-memory store only") would be clearer as a dedicated
test-only export like `__resetJournalInMemoryOnlyForTest()` next to the
existing `__resetUndoCursorForTest`. The current shape is fragile if
`__resetJournalForTest` ever grows additional side effects.

### P2. `__resetUndoCursorForTest` is exported but unused by the test file

`index.ts:281-283`

The reference also exports this. Keeping it for parity is fine; flag for
later cleanup if it doesn't pick up a consumer.

### P3. Effect union `severity` widens reference

`shell-v0/substrates/effect.ts:28`

Port adds `'success'` to `toast.severity`; reference at
`worktree-507-kernel-boundary` `kernel/substrates/actions/index.ts:109`
omits it. The reviewer brief specifies the wider union as the target,
so this is intentional. Worth noting in the tempdoc as a deliberate
divergence from the 507 reference (the port is the new source of
truth for the union shape going forward).

### P4. Two tests share the "successive undos" + "navigate captures previous" coverage

`effects.test.ts:96-108` and `effects.test.ts:130-139`

Both exercise the previous-target capture for navigate. No problem — the
first asserts inverse-shape, the second asserts cursor-walk. Just an
observation, no change required.

---

## Notes

### N1. Effect union extraction (faithfulness check)

Port at `effect.ts` extracts the 6-kind union from the reference's
inline definition at `kernel/substrates/actions/index.ts:104-110`.
Payload shapes are byte-identical except for the deliberate
`severity: 'success'` widening (P3). `EffectKind` is re-exported. The
extraction is the right shape for Slice 7 (Action) to consume alongside
the journal — neither substrate becomes the union's source of truth.

### N2. Inverse derivation table (faithfulness check)

All six cases match the reference verbatim:

| Effect kind        | Inverse                                             |
|--------------------|-----------------------------------------------------|
| noop               | null                                                |
| navigate           | `{kind: 'navigate', to: _previousNavTarget ?? hash}` |
| open-pane          | `{kind: 'close-pane', paneId}`                       |
| close-pane         | `{kind: 'open-pane', paneId}`                        |
| toast              | null                                                |
| invoke-operation   | toast acknowledging pending wire extension          |

The navigate before/after capture (`_previousNavTarget` mutation
*after* reading `before`) matches the reference at lines 200-211.

### N3. Persistence safety (faithfulness check)

`safeLocalStorage()` probe + `writePersisted()` try/catch + LRU cap
to `MAX_PERSISTED_ENTRIES = 500` + version-tagged + `Array.isArray`
guard on restore + `try/catch` around `JSON.parse` — all four failure
modes (no storage, quota exceeded, version mismatch, corrupt JSON)
return cleanly without throwing. Confirmed.

### N4. Per-Provenance read isolation test (faithfulness check)

`effects.test.ts:143-152` records 3 entries (2 CORE, 1 plugin),
asserts `listJournalFor('core')` returns 2, `'acme'` returns 1,
`'nobody'` returns 0. Exact counts, satisfies §13.5 rule 2 contract.

### N5. Undo cursor walking test (faithfulness check)

`effects.test.ts:112-124` records navigate→navigate→toast then undoes;
asserts the inverse is `{kind: 'navigate', to: '#a'}` (toast skipped,
walked back two entries). Covers the "irreversible in middle" scenario
specified by the brief.

### N6. Shell.ts boot wiring (production consumer #2)

`chrome/Shell.ts:87` imports `restoreJournalFromStorage`; line 576
invokes it in `connectedCallback`. Idempotency is enforced inside
`restoreJournalFromStorage` via the `_restored` flag, so re-mount
(connected → disconnected → connected) is safe. Confirmed real
production path.

### N7. NavigationJournal wire (production consumer #1)

`state/NavigationJournal.ts:24` imports `recordEffect`; line 183 invokes
it AFTER the FIFO eviction at lines 170-172, so the `url` passed is the
final entry's url, not pre-eviction. Correct placement for the non-de-dup
branch. The `isNavigatingHistory` early return at line 140 correctly
skips `recordEffect` (back/forward must not pollute the journal).
The de-dup branch issue is F1.

### N8. C-018 substantiveness

Shell subscribes IntentRouter → recordNavigation (per NavigationJournal's
own docstring §14-16). Any real navigation through the IntentRouter
produces a NavigationJournal entry and (in the non-de-dup branch) an
Effect Journal entry. This is mounted chrome, not test scaffolding.
Substantive consumer present.

### N9. Test count

15 `it(...)` blocks confirmed at `effects.test.ts` lines 26, 36, 43, 50,
59, 64, 77, 85, 96, 112, 126, 130, 143, 155, 168. Matches tempdoc §5.

---

**Verdict path**: `F:\JustSearch\.claude\worktrees\543-impl\docs\tempdocs\550-review.md`

---

## slice-5-review

*(consolidated from `543-slice-5-review.md`)*

### 551 — Independent reviewer verdict (Form substrate port)

## Verdict: APPROVE

The Slice 5 Form substrate port is faithful to the
`worktree-507-kernel-boundary` reference, the registry wiring is
correct (xUiRendererTester is the first entry, CorpusPickerRenderer is
side-effect imported, no circular-dep risk), and the production-consumer
path (C-018) is real (`dispatchRenderer` is called by `ObjectControl`,
`ArrayControl`, and `layoutDispatch`). The "Phase 4 update" test bump
12 → 13 is the right fix — the new entry IS the dispatcher, not a
duplicate. All four tester branches are covered (non-Control, no hint,
with-hint, empty-hint), and the registry round-trip / replace / sort /
subscribe behaviors are exercised.

## Critical findings

None.

## Material follow-ups

None.

## Polish nits

1. **Comment drift between port and reference (cosmetic).**
   - `XUiRendererControl.ts` header §13.3.1 paragraph: reference uses
     "KP+KS surface … FM/PS code"; port uses "substrate surface …
     feature-module / plugin code". Both convey the same intent; the
     port wording is more readable and matches main's vocabulary. Keep
     the port's wording.
   - `XUiRendererControl.ts` line 24 (port) adds "KCS bridge per §19:
     future `useForm()` capability module." — forward-looking comment,
     accurate, non-load-bearing. Fine to keep or strip.

2. **`registry.ts` import-block placement (cosmetic).** The port
   places the `xUiRendererTester` import after the layouts imports
   (lines 83-87), with a Tempdoc 543 §13.3.1 comment + the
   side-effect import for `CorpusPickerRenderer.js`. The reference
   places `xUiRendererTester` at the top of the controls imports
   (line 68). Functionally equivalent — the port's placement keeps the
   §13.3.1 grouping intact, which is arguably clearer. No change
   needed.

3. **Side-effect import comment for `CorpusPickerRenderer`** (lines
   84-87 of port `registry.ts`): explicitly documents that
   `<jf-corpus-picker>` is mounted whenever an Action/form declares
   `'x-ui-renderer': 'corpus-picker'`. Good addition over the reference,
   which only has the side-effect imports for testers.

## Notes

### Faithfulness check (Focus item 1)

- **XUiRendererControl.ts**: structurally identical to the reference.
  - Registry API: `registerXUiRenderer`, `unregisterXUiRenderer`,
    `getXUiRendererTag`, `listXUiRenderers`, `subscribeXUiRenderers`,
    `__resetXUiRendererRegistryForTest` — all present, same signatures,
    same `_hintRegistry` Map + `_listeners` Set + `notify()` helper +
    swallow-throw semantics.
  - `RANK_X_UI_RENDERER = 100` — matches reference.
  - Dispatcher Lit class `XUiRendererControl extends
    JsonFormsRendererBase` — same `render()` shape: visibility short-
    circuit → no-hint diagnostic → unregistered-hint diagnostic
    (`data-testid="x-ui-renderer-missing"`) → registered-tag mount
    wrapped in `<div data-x-ui-renderer-hint=${hint}>` with
    `createElementWithProps`.
  - Prop forwarding: `schema, uischema, path, data, errors, enabled,
    visible, onChange, userConfig` — identical 9-prop set on both.
  - `createElementWithProps(tag, props)` direct `document.createElement`
    + property assignment (no `unsafeStatic`) — same reasoning, same
    code.
  - Custom element registration `customElements.define(
    'jf-x-ui-renderer-control', …)` guarded by `customElements`
    availability + `customElements.get(...)` — identical.
  - `xUiRendererTester`: returns `-1` for non-Control (`uischema.type
    !== 'Control'`), `-1` for missing/empty hint, `100` for
    Control + non-empty hint — identical.

- **CorpusPickerRenderer.ts**: byte-identical except for two minor
  comment-line wording tweaks (lines 16-18 wording: "FM (feature-
  module)" → "feature-module code", §13.2.3 → §9). Custom-element
  define + `registerXUiRenderer('corpus-picker', 'jf-corpus-picker')`
  side-effect at module bottom — identical.

### Registry insertion ordering (Focus item 2)

`registry.ts` line 109: `{ tester: xUiRendererTester, tag:
'jf-x-ui-renderer-control' }` is the FIRST entry in
`_builtRegistry`. The `rendererRegistry` Proxy view at line 135
forwards array reads to `getRendererRegistry()`, so back-compat
iteration sees the dispatcher first too. Rank 100 > all
specialized controls (rank 2) > basic (rank 1), so dispatch wins
unambiguously on rank — first-position is only a tiebreaker, but
the architecture matches the reference.

### CorpusPickerRenderer boot exercise (Focus item 3)

`registry.ts` line 87: `import './controls/CorpusPickerRenderer.js';`
is a bare side-effect import. ESM evaluates the module on first
import, which runs `customElements.define('jf-corpus-picker', …)`
and `registerXUiRenderer('corpus-picker', 'jf-corpus-picker')`
at module load. Since `registry.ts` is itself imported eagerly by
`shell-v0/index.ts` (lines 162-170), CorpusPicker registration
happens at app boot.

### Production consumer / C-018 (Focus item 4)

`dispatchRenderer` (registry.ts lines 172-191) iterates
`getRendererRegistry()` and returns the highest-rank tag.
Production callsites:
- `controls/ObjectControl.ts:94` — `dispatchRenderer(childUischema,
  propSchema)` for each object property's child renderer.
- `controls/ArrayControl.ts` — analogous for array items.
- `layouts/layoutDispatch.ts:65, 101` — root-level dispatch + child
  dispatch from layout containers.

Every form mounted through the JsonForms-shaped pipeline (every
Object/Array recursion, every layout-element dispatch) flows through
`getRendererRegistry()`. Inserting `xUiRendererTester` at the top of
that registry IS the production wire. The tempdoc's §3 caveat —
"Slice 7 Action substrate will declare schemas that exercise the
path; until then no production form uses the hint, but the wire is
live" — is accurate. The dispatch infrastructure is real; no
no-consumer / pure-substrate concern.

### Side-effect ordering / circular-dep risk (Focus item 5)

Trace:
1. `shell-v0/index.ts` imports `./renderers/registry.js`.
2. `registry.ts` evaluation begins. At line 68-79 it imports the
   eight control testers + four layout testers (these all run
   `customElements.define(...)` for their own tags).
3. Line 83: `import { xUiRendererTester } from
   './controls/XUiRendererControl.js'` — triggers
   `XUiRendererControl.ts` module evaluation. Defines
   `jf-x-ui-renderer-control` custom element, exports the registry
   API + tester. No back-import of `registry.ts` (only the type
   `RendererTester`, which is type-only and elided at runtime).
4. Line 87: `import './controls/CorpusPickerRenderer.js'` —
   triggers `CorpusPickerRenderer.ts` module evaluation. Imports
   `registerXUiRenderer` from `./XUiRendererControl.js` (already
   loaded in step 3, no re-evaluation). Defines `jf-corpus-picker`,
   calls `registerXUiRenderer('corpus-picker',
   'jf-corpus-picker')`.
5. `registry.ts` finishes its top-level code (definitions of
   `getRendererRegistry`, `dispatchRenderer`, `lookupRendererOverride`).
6. Lazy `_builtRegistry` construction is deferred until first
   `dispatchRenderer` call (or first `rendererRegistry` Proxy read),
   by which time everything is loaded.

No circular import. The XUiRendererControl module's only
`registry.js` reference is the `RendererTester` type, which doesn't
create a runtime edge. CorpusPickerRenderer's `registerXUiRenderer`
import resolves to the already-evaluated XUiRendererControl module.

### Test precision (Focus item 6)

`XUiRendererControl.test.ts` has 9 tests across 2 describes:
- Registry (5 tests): register/get/unregister round-trip; re-register
  replaces (HMR idempotency); list sorted; subscribe fires on
  register + unregister; case-sensitive lookup.
- Tester (4 tests): non-Control → -1; Control without hint → -1;
  Control with hint → 100; empty-string hint → -1 (defensive).

The four tester branches are all the code-path branches that
matter: the only escape path from the tester is `return -1` or
`return RANK_X_UI_RENDERER`, gated on `(uischema.type === 'Control')
&& (typeof hint === 'string') && (hint.length > 0)`. All three gates
have a passing-truth test (rank 100) and at least one falsifying
test. Empty-string is correctly its own test (not collapsed into
"no hint"), which exercises the `hint.length === 0` branch
specifically. Test boundary is precise.

Layouts test count bump 12 → 13 (Focus item 7): the assertion at
`layouts.test.ts:185` reads `expect(rendererRegistry).toHaveLength(13)`
with the breakdown comment "1 x-ui-renderer + 8 controls + 4
layouts". I verified the registry entries: xUiRendererTester (1)
+ text/number/boolean/enum/date/time/object/array (8) +
vertical/horizontal/group/categorization (4) = 13. The new entry's
tag is `'jf-x-ui-renderer-control'` (line 188 `expect(tags).toContain
('jf-x-ui-renderer-control')`). Not a duplicate of any other tag.
The Phase 4 update is the right fix.

### `static-green ≠ live-working`

This slice is pure-frontend substrate; the "live" tier here is the
browser-rendered form dispatch. The tempdoc's verification §5 lists
unit tests + typecheck, which are appropriate for substrate without
a domain consumer. The next slice that adds a hint-consumer in a
real form (Slice 7 Action) should `jseval ui-shot` the rendered
output to close the loop. Not a blocker for this slice.

### Verdict rationale

Faithfulness: identical. Registry wiring: correct and at the top.
Production consumer: real (`dispatchRenderer` in Object/Array/layout
dispatch). Side-effect ordering: sound, no cycles. Test count bump:
correct fix, not papering over a regression. Tester branches:
exhaustive. Tests already green (9/9 XUi, 17/17 layouts, 1769/1769
full, typecheck clean) per slice §5.

No correctness regression, no wire-contract break, no faithfulness
defect. APPROVE.

---

## slice-6-review

*(consolidated from `543-slice-6-review.md`)*

### 552 — Independent reviewer verdict (Multi-Provider Dispatch port)

**Reviewer:** independent agent (not the implementer)
**Date:** 2026-05-24
**Verification budget used:** Read only (no build / no subagents).
**Files reviewed:**
- `modules/ui-web/src/shell-v0/aggregate-substrate/aggregateRegistry.ts` (full file)
- `modules/ui-web/src/shell-v0/aggregate-substrate/index.ts`
- `modules/ui-web/src/shell-v0/aggregate-substrate/multiProviderDispatch.test.ts`
- Reference: `worktree-507-kernel-boundary` `modules/ui-web/src/kernel/registries/aggregate-strategies.ts` lines 175–285
- Diff: `git diff HEAD~1 -- modules/ui-web/src/shell-v0/aggregate-substrate/aggregateRegistry.ts`

## Verdict: APPROVE

The Multi-Provider Dispatch substrate port is a faithful, well-tested
addition to the existing `aggregate-substrate/aggregateRegistry.ts`. The
diff is strictly additive plus a single one-line change to
`__clearAggregateRegistry` (which is necessary for test isolation). All
existing single-winner consumers (JfOperation / JfResource /
JfHealthEvent / JfSearchIntrospection) continue to operate against an
unmodified `dispatchAggregateStrategy` / `renderAggregate` API.

## Critical findings

None.

## Material follow-ups

None. (See "Notes" below for the C-018 stance — it does not rise to a
material follow-up under the criteria in
`.claude/rules/agent-lessons.md`.)

## Polish nits

1. **Return-type widening vs. reference.** Reference uses
   `Array<Exclude<StrategyResult, null | typeof litNothing>>` for
   `renderAggregateMulti`. The port simplifies to `Array<TemplateResult>`.
   These are equivalent (`StrategyResult` is
   `TemplateResult | typeof litNothing | null`, exclusion yields
   `TemplateResult`), but the reference's `Exclude<...>` form documents
   the relationship to `StrategyResult` more explicitly. Pure style
   preference; not actionable.

2. **`removePluginAggregateStrategies` already present.** Lines 314–321
   of the file are a pre-existing helper that the reference's slice does
   not have. Confirmed pre-existing via `git diff HEAD~1`. No action.

3. **Test file inline-formatting.** A few of the
   `registerAggregateStrategy({...})` blocks in the test put multiple
   fields on one line (e.g., `aggregate: 'Operation', context: 'button',
   rank: 1,`). Prettier may auto-rewrap on next format pass; harmless
   either way.

## Notes (including stance on the C-018 question)

### Faithfulness (focus item 1)

Verified line-by-line vs. reference lines 175–285:

| Element                          | Port lines       | Faithfulness                          |
| -------------------------------- | ---------------- | ------------------------------------- |
| `DispatchPolicy` union           | 218              | identical                             |
| `_contextPolicies` Map           | 220              | identical                             |
| `setDispatchPolicy`              | 223–228          | identical body                        |
| `getDispatchPolicy`              | 231–235          | identical (`?? 'winner'` default)     |
| `__clearDispatchPolicies`        | 238–240          | identical                             |
| `dispatchAggregateStrategies`    | 252–267          | identical: rank-desc, ties → `_entries.indexOf(b) - indexOf(a)` |
| `renderAggregateMulti`           | 284–307          | semantically identical (winner/rank-first-non-empty break after first non-empty; merge collects all); return-type simplification noted in polish nits |

The `typeof r === 'symbol'` runtime check correctly catches Lit's
`nothing` sentinel — same as the reference's comment-and-check pattern.

### Back-compat (focus item 2)

Confirmed via `git diff HEAD~1`: lines 1–188 of the file are byte-identical
to the prior commit. The only edit in the existing region is line 192
(`_contextPolicies.clear();` appended inside `__clearAggregateRegistry`).
`dispatchAggregateStrategy` / `renderAggregate` / `registerAggregateStrategy`
/ `removePluginAggregateStrategies` / `getRegisteredCells` /
`sameSlot` — all untouched. JfOperation / JfResource / JfHealthEvent /
JfSearchIntrospection continue to call the original API.

### `__clearAggregateRegistry` policy clear (focus item 3)

Verified at line 192. Without this, the `beforeEach(__clearAggregateRegistry)`
calls scattered through other aggregateRegistry tests would leave stale
policy state across files. The new test file additionally calls
`__clearDispatchPolicies()` explicitly, which is belt-and-suspenders;
also fine.

### Index re-exports (focus item 4)

All six new symbols re-exported at `index.ts` lines 35–40:

- `setDispatchPolicy` ✓
- `getDispatchPolicy` ✓
- `__clearDispatchPolicies` ✓
- `dispatchAggregateStrategies` ✓
- `renderAggregateMulti` ✓
- `type DispatchPolicy` ✓

Comment marker (`// Tempdoc 543 §13.3.2 — Multi-Provider Dispatch.`)
properly distinguishes the new block from the original 511 exports.

### C-018 substantiveness (focus item 5)

**Stance: not a blocker, not even a material follow-up under main's discipline.**

The substrate-without-consumer-flavors principle (named in
`.claude/rules/agent-lessons.md`) targets the case where new substrate
ships with no named, sequenced reader. Here:

- The new API is wired into the *production* `_entries` store — the same
  array every JsonAggregate render path reads. The store is shared with
  live consumers; the new functions are not on an orphan registry.
- The consumer is **named** (HoverPreview), **sequenced** (Slice 8, next
  in the /goal sequence), and **immediate** (the slice tempdoc commits
  to it landing after this one).
- The default `'winner'` policy means production behavior is unchanged —
  no risk of an unread substrate slot accumulating divergent
  expectations the way C-018 was originally written to prevent.

The slice tempdoc's §3 framing — "substrate is on the production
`_entries` store; Slice 8 is the named next-slice consumer" — is
adequate evidence under the discipline. The "wait for more evidence"
trigger is explicitly rejected in CLAUDE.md *Structural Defects Don't
Need Repeat Incidents* and *Tempdoc Is Your Contract*: a slice the user
has scoped should be implemented in full, and the consumer is sequenced.

If Slice 8 were *not* the literal next slice — for example, gated behind
"will reconsider after Slice 9 lands" — I would flag it as a material
follow-up. It is sequenced as the literal next slice, so I do not.

### Tests precision (focus item 6)

All 11 assertions distinguish correct policy semantics from incorrect
behavior:

| Test                                                | Distinguishing assertion                                                                                   |
| --------------------------------------------------- | ---------------------------------------------------------------------------------------------------------- |
| default policy is winner                            | direct equality on `winner` for two unrelated contexts                                                     |
| setDispatchPolicy overrides                         | one context flipped, sibling context unchanged — distinguishes per-context isolation                       |
| __clearDispatchPolicies resets                      | direct equality post-clear                                                                                 |
| returns all in rank-desc order                      | `out[0]` rank 10, `out[1]` rank 5, `out[2]` rank 1 — distinguishes from any other ordering                 |
| returns empty for unregistered cell                 | `.toEqual([])`                                                                                             |
| filters by both aggregate AND context               | two registrations on different contexts, dispatch returns exactly the matching one                         |
| **winner stops walking after first non-null**       | **line 126: `expect(s1).not.toHaveBeenCalled();` — explicitly proves early termination, not just result-shape** |
| merge returns all in rank order                     | `.toEqual(['r2', 'r1'])` — distinguishes from `['r1', 'r2']` or single                                     |
| rank-first-non-empty walks past null/nothing        | asserts all 3 stubs called AND result `['real']` — proves walk + first-non-empty stop both                |
| empty registry → empty array                        | `.toEqual([])`                                                                                             |
| back-compat single-winner unchanged                 | direct identity check on highest-ranked stub                                                               |

The "winner stops walking" test is the load-bearing assertion for early
termination — the implementing agent correctly added the
`expect(s1).not.toHaveBeenCalled()` line, which distinguishes
"breaks after first non-null" from "calls everything and returns first."
This is the wrong-gate / test-precision lesson done right.

### Summary

APPROVE. The port is faithful, additive, fully back-compatible, and
adequately tested. C-018 is satisfied by the named next-slice consumer
plus the production-store wiring; no blocker.

---

## slice-7-review

*(consolidated from `543-slice-7-review.md`)*

### 553 — Independent reviewer verdict (Action substrate port)

## Verdict

**APPROVE.**

The Action substrate at `modules/ui-web/src/shell-v0/substrates/actions/index.ts` is a faithful port of the reference `kernel/substrates/actions/index.ts`. Imports are re-pointed correctly to main's non-`@kernel` layout (Slice 3's extracted `Addressable`, Slice 4's extracted `Effect`, the relocated `commands/whenExpression.js`, `primitives/provenance.js`, and `substrates/effects/index.js`). The `applyEffect` → `recordEffect` ordering is preserved (write before dispatch). The canonical `core.action.cite-selection` Action matches the reference's appliesTo / handler / provenance shape. Shell.ts side-effect import is in the right block and unconditional. 19/19 targeted tests are green, full suite 1799/1799 green, typecheck clean.

C-018 substantiveness is satisfied: every `applyEffect` lands in the production Effect Journal (already exercised in production via `NavigationJournal.recordNavigation` per Slice 4). The chain "Shell.ts side-effect import → canonical Action registers → invokeAndApply → applyEffect → recordEffect → journal" is real production code, not test-only. Slice 8 (HoverPreview) is the named next-slice surface consumer of `listActions`.

No critical findings. No material follow-ups required for this slice.

## Critical findings

None.

## Material follow-ups

None blocking. Two minor items deferred to follow-up slices, already disclosed in the tempdoc's §4:

1. Per-registry facade migration (Command / ContextAction / SelectionAction / VirtualOperation) — explicitly out-of-scope per §4.
2. Parameter-schema runtime validation — deferred to a later wave; the substrate ships ParameterSchema as a documentation contract today.

## Polish nits

1. **`when` field declared as `string` literal in Action interface (line 86) rather than imported `WhenExpression`.**
   - Reference impl: `readonly when?: WhenExpression;` with a typed import of `WhenExpression` from the predicates module.
   - Port: `readonly when?: string;` — semantically identical because `WhenExpression = string`, but the port loses the named-type self-documentation. Trivial; only worth a one-line touch-up if you're already in the file. Not a correctness defect; not blocking.

2. **`Effect.severity` widened on main.** The shared `substrates/effect.ts` admits `'success'` as an additional severity option (reference impl: `'info' | 'warning' | 'error'`). The port forwards `effect.severity` verbatim via `?? 'info'`, so this is just a pre-existing main-side widening relative to the reference. Already correct.

3. **Test comment at line 33-34 is slightly misleading.** The comment says "Re-register the canonical Action that module-load installed. The reset clears the registry for the rest of this test file." — but no re-registration actually happens in `beforeEach`. The intent reads as "we acknowledge the reset clears it and we don't re-register on purpose," which is fine — the comment just dangles. Optional rewrite: "The reset clears the registry — tests that need the canonical Action would re-register it." Not blocking.

## Notes

**Confirmed import-graph correctness (no `@kernel/*` aliasing):**
- `Addressable, AddressableKind` ← `../addressable.js` (Slice 3 shared shape)
- `Effect` ← `../effect.js` (Slice 4 shared closed union)
- `Provenance, CORE_PROVENANCE` ← `../../primitives/provenance.js`
- `getShellContext` ← `../../state/shellContextState.js`
- `evaluateWhen` ← `../../commands/whenExpression.js` (Slice 2's commands location)
- `recordEffect` ← `../effects/index.js` (Slice 4 journal substrate)

All resolve to files that exist on main. None of them route through `@kernel/*` — the port respects main's path discipline.

**Confirmed Effect Journal ordering invariant.** `applyEffect` (line 278 of the port) calls `recordEffect(effect, invokedBy)` BEFORE the switch — failed dispatches still produce an audit entry. Matches reference impl line 385.

**Confirmed exhaustiveness check.** The `default` branch assigns `effect` to `never` (line 341) — adding a new Effect kind without a handler fails TS compilation. Matches reference impl.

**Confirmed defensive DOM guards.** All `document.dispatchEvent` calls (toast, open-pane, close-pane, invoke-operation) are wrapped in `typeof document !== 'undefined'` guards; `navigate` is guarded by `typeof window !== 'undefined' && window.location`. The reference impl lacked guards on the `document.dispatchEvent` calls — the port is **stricter** than the reference here, which is correct for SSR / Node-without-DOM contexts. Improvement, not regression.

**Confirmed throwing-enabled test is precise.** `actions.test.ts:154` asserts `listActions({}).map((a) => a.id)).toEqual([])` — exact empty array, not merely "does not throw." Defensive catch is testable proof.

**Confirmed invokeAndApply provenance threading.** The Action's own `provenance` is passed to `applyEffect`, which forwards to `recordEffect` — journal entries attribute to the registering contributor (CORE for the canonical Action; plugin provenance for plugin-registered Actions). The `acme.demo` test at line 232 verifies exact identity (`toBe(provenance)`), not just shape.

**Boot wire placement.** Shell.ts `import '../substrates/actions/index.js';` at line 92 sits in the substrate import block alongside scope (80), evaluationContext (84), and effects (87). Unconditional. Side-effect-only import, no named bindings used — appropriate.

**Faithfulness diff scan:** none of the structural changes alter wire contract or behavior:
- Reference exports `AddressableKind`, `Addressable`, `Effect`, `EffectKind` from this file; port imports them from extracted shared modules. Slice 3/4 boundary, not a regression.
- Reference uses module-private `actions`/`listeners`; port uses `_actions`/`_listeners` (underscore prefix). Cosmetic.
- Reference renderer comment mentions unknown-kind fallback to info-toast; port relies purely on TS exhaustiveness check (cleaner, matches Slice 4's closed-union discipline).

---

## slice-8-review

*(consolidated from `543-slice-8-review.md`)*

### 554 — Independent reviewer verdict (HoverPreview substrate port)

**Date**: 2026-05-24
**Reviewer**: independent (not implementer)
**Scope**: Slice 8 of `/goal 543-substrate-migration` — port HoverPreview kernel host + `'hover-preview'` SurfaceContextKind from `worktree-507-kernel-boundary` to main, with the reviewer follow-up B1 (double-registration guard) folded in.

## Verdict

**APPROVE** for merge. No correctness regressions, no wire-contract breaks, no faithfulness defects. The port adapts the kernel-aliased reference (`@kernel/registries/aggregate-strategies`) to main's shell-v0 relative imports without semantic drift, deliberately omits the audience gate (acknowledged in tempdoc §4), and substantively satisfies the C-018 surfaceContextKinds.ts header rule (new context + production surface mount + first non-default policy flip in one slice).

## Critical findings

None.

## Material follow-ups

None blocking. Two items already captured in tempdoc §4 (per-surface `data-hover-aggregate-*` annotation on OpButton; optional audience gate one-liner) are correctly deferred — without trigger annotations the host listens but does not fire, so the host's lifecycle is exercised only once an adopter opts in. That is the intended substrate shape; the slice is not the place to annotate consumers.

## Polish nits

1. **Provenance omission is faithful, but worth a one-line tempdoc note.** Worktree-507 reference calls `registerAggregateStrategy({…, provenance: makeCoreProvenance()})`; the port omits `provenance` because main's `Entry<K,C>` interface (`aggregateRegistry.ts:60-66`) has no `provenance` field — the kernel-boundary provenance axis hasn't been ported to main yet. Omission is correct, not a regression. Future slice that ports the kernel provenance axis to main will need to revisit all strategy registrations together; not a §554 concern. (No action required this slice.)

2. **Disconnect-then-reconnect now works.** The port nulls listener fields in `disconnectedCallback` (lines 105/113/117) — the reference impl did not. Combined with the B1 guard (`if (this.mouseEnterListener) return;` at line 88), this means rapid disconnect→reconnect re-registers listeners correctly. The reference, by contrast, would silently fail to re-register if it had had the guard. This is an improvement over the reference, not a defect. (No action required.)

3. **Strategy test imports `html` only for "future expansion."** `operationHoverPreview.test.ts:6` imports `html` and uses it as `void html\`\`` in the final test to silence the unused-import lint. Cleaner would be removing the import entirely until a test actually renders. Cosmetic. (No action required this slice.)

## Notes

Verification matrix walked:

| Item | Status | Evidence |
|---|---|---|
| 1. HoverPreviewHost faithfulness | PASS | HOVER_DELAY_MS=350, HIDE_DELAY_MS=150, mouseenter/mouseleave/scroll listeners with `capture=true`, activate via `getOperation`, render uses `renderAggregateMulti('Operation','hover-preview',…)`, popover at `rect.bottom+6` with `left = max(8, min(window.innerWidth-392, rect.left))`. All match reference. |
| 2. Defensive B1 guard | PASS | `HoverPreviewHost.ts:88` `if (this.mouseEnterListener) return;` — present and correctly placed after `super.connectedCallback()`. |
| 3. operationHoverPreview strategy | PASS | rank 0, source 'core', renders `op.id` + `Confirm: ${op.policy.confirm.kind}` + conditional lineage. Audience gate deliberately OMITTED; tempdoc §4 acknowledges with rationale ("preview is read-only; gating would surface fewer preview bodies, not hide destructive ops"). |
| 4. SurfaceContextKind addition | PASS | `surfaceContextKinds.ts:50` adds `'hover-preview': { triggerEl?: HTMLElement }` to map; line 63 adds `'hover-preview'` to `SURFACE_CONTEXT_KINDS` readonly array. Both updates land. |
| 5. Bootstrap wiring | PASS | `bootstrap.ts:18` imports `registerOperationHoverPreviewStrategy`; line 21 imports `setDispatchPolicy`; both calls (lines 38, 40) are inside the function body AFTER `if (bootstrapped) return; bootstrapped = true;` (lines 32-33). Idempotent. |
| 6. Shell.ts integration | PASS | Side-effect import `'../hover/HoverPreviewHost.js'` at Shell.ts:98; `bootstrapAggregateSubstrate` import at line 99; call at line 593 (inside `connectedCallback`); `<jf-hover-preview-host></jf-hover-preview-host>` mounted at line 1525 immediately after `<jf-simple-toast>` (line 1523). |
| 7. C-018 substantiveness | PASS | Multi-Provider Dispatch's first non-default policy flip lands here (`setDispatchPolicy('hover-preview','merge')` in bootstrap.ts:40), and the new SurfaceContextKind ships with a production surface (`<jf-hover-preview-host>` in Shell). Both halves of the surfaceContextKinds.ts header rule satisfied in one slice. |
| 8. Test minimality | PASS for now | Four strategy unit tests + indirect coverage of the policy flip via existing `multiProviderDispatch.test.ts`. No direct lifecycle test for the Lit host. Acceptable per main's discipline — Lit lifecycle is properly verified by integration/live-stack rather than JSDOM, and the slice ships no JSDOM lifecycle harness. Live-stack smoke (mouse over an annotated trigger; observe popover) is the natural next verification tier once the first adopter annotates a chrome button. **Not a blocker.** |

Trade-offs accepted by the port (not faithfulness gaps):

- **Import-path rewrite**: `@kernel/registries/aggregate-strategies` → `../aggregate-substrate/aggregateRegistry.js`. Required because main does not have the `@kernel/*` path alias. Symbol surface is equivalent.
- **`StrategyHost` shape difference**: worktree-507's host carries `apiBase` + `viewerAudience`; main's carries only `apiBase` (aggregateRegistry.ts:56-58). The port passes `{ apiBase: '' }`, matching main's surface. Once main grows `viewerAudience` on `StrategyHost`, the audience gate can be added back.
- **`override` keywords on lifecycle methods**: port adds `override connectedCallback` / `override disconnectedCallback` / `override render`; reference omits them. Strictness difference, not faithfulness.

Verification budget honored: read-only review against files; no build, no subagent dispatch.

— independent reviewer

---

## slice-9-review

*(consolidated from `543-slice-9-review.md`)*

### 555 — Independent reviewer verdict (Contribution Manifest substrate port)

## Verdict

**APPROVE with one material follow-up.** No correctness regressions, no
wire-contract breaks, no rollback/lifecycle/activate-throw faithfulness
defects. The substrate ports faithfully; the canonical demo manifest
installs cleanly at boot; tests are precise enough to catch the
defect-classes the brief specified.

The one finding worth recording is a `source` semantics drift versus the
reference (port uses `source: 'plugin'` uniformly for the seven
contribution kinds that carry a `source` field; reference used `'core'`
for most). It's latent today — the canonical core manifest only installs
an Action (no source field) — but will misclassify entries when first-
party manifests add a status-bar item / inspector tab / template. Not
blocking for Slice 9; record as follow-up for Slice 10 or the first
core-manifest migration that adds a sourced entry.

## Critical findings

None. Specifically verified:

- **Atomic rollback iterates all 10 contribution kinds** (port `index.ts`
  L345-L416): actions, projectors, renderers, statusBarItems,
  inspectorTabs, contextActions, emptyStates, keybindings, templates,
  walkthroughs. Tracked-id arrays match the 10 declared kinds. No leak
  on partial failure.
- **Lifecycle ordering** (L325-L342): register-each → catch → rollback →
  set `_installed` → call activate → on activate-throw `_installed.delete`
  + rollback. Matches reference. `notify()` fires only after activate
  succeeds — confirmed at L341 vs L337's delete-on-failure path.
- **Activate-throw does not leak the manifest** (test L174-L197 verifies
  both `getInstalledManifest` is undefined AND the action's registration
  was rolled back).
- **Rollback test precision** (test L114-L153) verifies (a) the first
  action was unregistered (`getAction('rollback.first') === undefined`),
  (b) the pre-existing collider remains (`getAction('collide.id')`
  defined), and (c) the manifest itself was not installed
  (`getInstalledManifest('test.plugin') === undefined`). All three
  assertions present — distinguishes "passes for the right reason".
- **Skipped kinds claim is accurate.** `SettingsPanelRegistry` and
  `LayoutRegistry` appear in the codebase only as doc-comment references
  inside the manifest substrate itself; no concrete registry exists to
  route into. Decision to omit `settingsPanels` / `layouts` is sound.
- **Boot integration correct.** `installCoreDemoManifest()` is imported
  at `Shell.ts:104` and invoked at `Shell.ts:605` inside
  `connectedCallback` (after `super.connectedCallback()` at L520,
  before the URL-source teardown at L925) — not in the constructor.
  Fire-and-forget with `console.warn` on failure is appropriate for a
  non-critical boot side-effect.
- **Canonical demo's Action is retrievable.** Manifest installs
  `core.action.demo-echo` via `registerAction({ ...action, provenance:
  manifest.provenance })`; `getAction('core.action.demo-echo')` will
  return the registered Action with provenance stamped from
  `makeCoreProvenance()`. C-018 satisfied.
- **Per-entry registration paths match registry signatures.** Verified
  against `registerAction` (Action with `provenance`, no `source`),
  `registerProjector(kind, project)` (positional, no provenance),
  `registerXUiRenderer(hint, mountTag)` (no provenance —
  documented limitation, not a defect), and the seven sourced kinds
  each with `source: 'plugin'` + `provenance: manifest.provenance`.

## Material follow-ups

- **`source` semantics drift.** Reference (`kernel/substrates/manifest`)
  passed `source: 'core'` for settingsPanels, statusBarItems,
  inspectorTabs, contextActions, emptyStates, templates (and `'plugin'`
  for keybindings only). Port uses `source: 'plugin'` uniformly for all
  seven sourced kinds. Latent consequences when a CORE-provenance
  manifest installs an entry of these kinds:
  - `StatusDeck.ts:300-303` filters `source !== 'core'` to render core
    vs non-core status bar items differently — manifest-installed core
    items will render as plugin items.
  - `InspectorPane.ts:298` only renders tabs with `source === 'plugin'`
    — actually this means manifest-installed core inspector tabs WILL
    render (currently the reference would have skipped them). The
    behavior is acceptable but inverts the reference's intent.
  - `TemplateCatalog.ts:366` projects `source === 'core' ? 'shell' :
    'plugin'` — manifest-installed core templates will surface as
    plugin-sourced in the command projection.
  No production impact today because the canonical demo manifest only
  installs an Action (which has no `source` field). Surface a follow-up
  to either: (a) pass `manifest.provenance.contributorId === 'core' ?
  'core' : 'plugin'` (the natural classifier), or (b) thread the
  intended `source` through the manifest (an installer option). Pick
  before the first core-manifest migration adds a sourced entry.

## Polish nits

- `canonicalManifest.ts` uses a module-scoped `installed` boolean for
  idempotency in addition to the registry's own `_installed.has` check.
  Redundant but harmless — the registry check at `installContributionManifest`
  L219 would throw on second call, and the boolean prevents the throw
  from surfacing. Could simplify by relying on the registry alone
  (catching the duplicate-install error in `installCoreDemoManifest`)
  but the current shape is fine.
- The brief flagged walkthroughs as appearing only in the port (not
  reference). The walkthroughs entry IS in the port at `index.ts:140-143`
  with a matching `WalkthroughRegistry` (verified). This is a port-only
  addition that extends, not deviates from, the reference — acceptable.

## Notes

- Verified files: `modules/ui-web/src/shell-v0/substrates/manifest/{index,canonicalManifest,manifest.test}.ts`,
  `modules/ui-web/src/shell-v0/chrome/Shell.ts` (boot integration),
  `modules/ui-web/src/shell-v0/substrates/actions/index.ts` (registerAction
  signature + `__resetActionsForTest`), `modules/ui-web/src/shell-v0/commands/*Registry.ts`
  (source/provenance field surface for all seven sourced kinds).
- Reference compared: `worktree-507-kernel-boundary/modules/ui-web/src/kernel/substrates/manifest/index.ts`.
- Tests not re-run per brief (12/12 targeted, 1815/1815 full, typecheck
  clean per tempdoc §5).
- Reviewer is independent of implementing agent. Verdict-is-gate applies.

---

## slice-10-review

*(consolidated from `543-slice-10-review.md`)*

### 556 — Independent reviewer verdict (Workspace Profiles substrate port — final slice)

## Verdict

**APPROVE.**

Faithful port of `worktree-507-kernel-boundary`'s `@kernel/substrates/profiles/index.ts`
to `modules/ui-web/src/shell-v0/substrates/profiles/index.ts`. All eight focus areas
in the brief check out: WorkspaceProfile shape, CRUD + subscribe, parent-first
cycle-safe resolveEffectiveProfile with child-wins Scope merge, activation that
uninstalls profile-scoped misfits and reinstalls via factory before applying
restoreScope, persistence with version-tagged JSON and idempotent restoration, and
the alias-based Shell.ts boot integration that avoids the UserStateDocument
name collision. Tests are precise (the child-scope-wins assertion correctly
checks BOTH child override AND parent preservation). Cross-substrate composition
with Slice 2 (Scope) and Slice 9 (Manifest) is structurally correct.

No correctness regression, no wire-contract break, no faithfulness defect.

## Critical findings

None.

## Material follow-ups

None.

The "what is NOT in this slice" carve-outs in §4 of the tempdoc (profile-mgmt
UX, UserStateDocument per-profile state merge, plugin-suggested profiles,
PluginRegistry profile-scoped activation) are explicit deferrals, not gaps in
the substrate itself. The substrate's V1 surface — set arithmetic + factory
re-install + Scope snapshot + localStorage — is internally complete.

## Polish nits

1. **Reference's dead ternary is dropped — good.** The reference
   (`worktree-507-kernel-boundary` lines 291) ended `resolveEffectiveProfile`'s
   returned object with `...(root === tip ? {} : { /* root tag for diagnostics */ })`
   — a spread of an always-empty literal regardless of branch, with no observable
   effect. The port (line 277 of the ported file) correctly elides this. Not a
   regression; an improvement.

2. **Activation iteration order on `targetSet`.** `for (const mid of targetSet)`
   walks insertion order into the `Set`, which is `effectiveIds` insertion order —
   parent-first then child. This matches the reference and is fine for V1 where
   manifests have no declared cross-install ordering, but if Slice 9's
   `installContributionManifest` ever grows order-sensitive dependency wiring
   beyond the existing `dependsOn` check, profile activation may need to topo-sort.
   Out of scope for this slice; flag for future awareness.

3. **`writeProfiles()` swallows storage errors silently.** Reference does too.
   For V1 this is acceptable (boot must not block on storage quota or private-mode
   restrictions), but a future telemetry hook on the catch would help diagnose
   silent-loss scenarios. Out of scope for this slice.

4. **`_resetInMemoryOnly()` test helper calls `void __resetProfilesForTest()`
   without awaiting.** The helper is async (it awaits `uninstallContributionManifest`),
   so the void-discarded promise *could* race with the subsequent
   `localStorage.setItem` re-seed. In practice the test passes because the
   uninstall loop is empty in that test (no manifests installed), so the async
   work completes synchronously. Not a current defect, but a latent fragility if
   the test grows to install a manifest before the round-trip check. Consider
   making `_resetInMemoryOnly` async + `await`-ing it in the round-trip test.

5. **`createProfileFromCurrent` does not validate that `inheritsFrom` references
   an existing profile.** A user could call it with a typo parent id, producing
   a profile whose `resolveEffectiveProfile` returns null. Matches the reference
   and is consistent with the "registry is dumb storage" model — validation belongs
   to the future profile-mgmt UX layer. Noting for completeness.

## Notes

### Sequence verdict (10-slice arc)

This is the final slice of the /goal 543-substrate-migration sequence. The
ten slices land as a coherent substrate set on `worktree-543-impl`:

1. **Provenance** (547) — uniform attribution field.
2. **Scope** (548) — deferred slots + audience + serialize/restore. *This slice's
   `restoreScope` is the load-bearing consumer for activation.*
3. **EvaluationContext** (549) — Scope + TargetFacts composer.
4. **Effect Journal** (550) — typed Effect + inverse + persistence.
5. **Form** (551) — JSON Schema renderer.
6. **Multi-Provider Dispatch** (552) — policy on aggregateRegistry.
7. **Action** (553) — unified substrate.
8. **HoverPreview** (554) — kernel host + SurfaceContextKind.
9. **Contribution Manifest** (555) — unifying first-party + plugin paths. *This
   slice's `profileBinding` discriminator + install/uninstall verbs are the
   load-bearing consumers for activation's set-arithmetic.*
10. **Workspace Profiles** (556) — manifest set + Scope snapshot. *This slice.*

The dependency arrows from Slice 10 back to Slices 2 and 9 close the
substrate graph: profiles are the user-facing composition layer that
binds Scope (state) and Manifest (contribution sets) into named, persistent,
inheritance-aware bundles. Set-arithmetic inheritance (union + child-wins)
is the architectural payoff Tempdoc 543 §13.6 promised — and it works as
designed (4 inheritance tests green).

The sequence has been disciplined throughout: each slice ported faithfully
from `worktree-507-kernel-boundary`'s reference, each got a production
consumer wired in real chrome (boot path or feature module), each got
targeted unit tests + full-suite green, and no slice introduced wire-contract
regressions. The independent-reviewer cadence held — 10 separate verdicts,
no BLOCKs. APPROVE the substrate set as a whole; the 543-impl worktree is
ready for merge consideration into main.

### Tests + typecheck

Per the brief: 14/14 targeted tests, 1829/1829 full suite, typecheck clean.
I did not re-run; the tempdoc's §5 verification record is consistent with
the code I read.

---

## fwd-slice-1-review

*(consolidated from `543-fwd-slice-1-review.md`)*

### Independent Review — Commit 1f8af9682 (S1 foundation polish)

Reviewer: independent adversarial review (not the implementer).
Scope: R-P4 / R-P2 / R-S2 / R-P5 as described in the commit message.

## Verdict

APPROVE-WITH-FOLLOWUPS

The four claims are all substantively correct and behavior-preserving where
claimed. One stale doc comment was introduced (the `deriveInverse` JSDoc still
describes the old toast behavior). It is documentation-only and non-blocking,
hence a follow-up rather than a block.

## Findings

1. **`deriveInverse` JSDoc is now stale** — `modules/ui-web/src/shell-v0/substrates/effects/index.ts:183-186`
   — severity: LOW (doc-only). The function-level comment still reads:
   "Hybrid model per §13.7 q.9: 'invoke-operation' returns a toast inverse
   acknowledging the undo intent today; once `policy.inverse?: Effect` lands…".
   This directly contradicts the new behavior (R-P2 made the no-declared-inverse
   path return `null`). The inline comment at lines 215-222 is correct and
   up to date, but the JSDoc above the function was not updated alongside the
   code. Recommend updating the JSDoc in this slice or as an S3 follow-up.
   (No functional impact; TS/tests unaffected.)

2. **README signature/count update is accurate but a sibling comment block
   elsewhere may drift** — `modules/ui-web/src/shell-v0/substrates/README.md:24-27`
   — severity: INFO. The 14→16 change and the `applyEffect(effect, invokedBy,
   originator?)` signature both match source. No action required for this slice;
   noted only for completeness.

No HIGH/MEDIUM findings. No weakened assertions, no deleted tests, no
behavioral change unreflected in tests.

## Claims verified

- **R-P4 (dispatchDomEvent helper, behavior-preserving)** — ✓
  - Helper added with identical semantics: `bubbles: true`, `detail` payload,
    and the `typeof document !== 'undefined'` SSR guard moved inside the helper.
  - Every migrated case preserves the SAME event name and SAME detail keys:
    `toast`(jf-show-toast: message+severity), `open-pane`/`close-pane`(paneId),
    `invoke-operation`(operationId+args ?? {}), `set-selection`(surfaceId+ids),
    `clear-selection`(surfaceId), `open-modal`(modalId+payload ?? {}),
    `close-modal`(modalId), `set-form-value`(formId+path+value),
    `data-result`(operationId+resultKey+result), `data-error`(operationId+
    resultKey+error). All verbatim-equivalent to the pre-commit blocks.
  - `copy-to-clipboard`: the prior combined guard
    `effect.successMessage && typeof document !== 'undefined'` is now
    `if (effect.successMessage) dispatchDomEvent(...)`, with the `document`
    guard inside the helper. Short-circuit semantics are identical.
  - NOT migrated (correctly): `navigate` (writes `window.location.hash`),
    `focus-element`/`scroll-to` (querySelector + DOM method calls, not event
    dispatch). Their `typeof document` guards remain intact. `data-result`'s
    `setLatestDataResult` side-write is preserved after the dispatch.
  - "~12" in the message is loose (10 dispatch-only cases migrated); not a
    correctness issue.

- **R-P2 (invoke-operation inverse → null, declared-inverse path unchanged)** — ✓
  - `lookupEffectInverseForOperation(effect.operationId)` + `if (declared)
    return declared;` is byte-for-byte unchanged; only the fall-through changed
    from a toast to `return null`.
  - Test STRENGTHENED, not weakened: assertion changed from
    `expect(e.inverse?.kind).toBe('toast')` to `expect(e.inverse).toBeNull()`
    and the test title was updated. Verified the test exercises the genuine
    null path: the test imports no manifest and registers no `effectInverses`
    for `core.reindex`, so `_effectInverses` is empty and the lookup returns
    `undefined` → the new `null` branch. Passes for the RIGHT reason.
  - No other code/test relied on the toast inverse (grep: only stale doc
    comments + the corrected test reference "Undo:"/invoke-operation+toast).
  - Targeted suite green: `vitest run effects.test.ts` → 34/34 pass.

- **R-S2 (recordEffect → options-only)** — ✓
  - Signature changed from `optsOrCausation?: number | RecordEffectOptions`
    to `opts: RecordEffectOptions = {}`; the numeric-branch normalization
    removed.
  - Grep of all `recordEffect(` callers across `modules/ui-web/src`: every
    call passes either 2 args or a `{ ... }` options object as the third
    arg. No numeric third argument anywhere (production or test). Verified.

- **R-P5 (README dispatch-table count 14 → 16)** — ✓
  - `effect.ts` Effect union has exactly 16 `kind:` members: noop, navigate,
    open-pane, close-pane, toast, invoke-operation (6) + set-selection,
    clear-selection, focus-element, scroll-to, open-modal, close-modal,
    copy-to-clipboard, set-form-value (8 §21.D) + data-result, data-error
    (2 §25.β5) = 16. README breakdown "6 + 8 + 2" matches.

## Honesty / wrong-reason check

No substrate-scaffolding-counted-as-completion pattern here; this is a
genuine refactor + one behavioral correction. The behavioral change (R-P2)
is reflected in a strengthened test that passes for the verified-correct
reason. No assertion weakened, no test disabled/deleted, no catch broadened.
The only defect is the un-updated JSDoc (Finding 1).

---

## fwd-slice-2-review

*(consolidated from `543-fwd-slice-2-review.md`)*

### 543-fwd S2 — Independent Review (commit 83099112f)

Reviewer: independent adversarial reviewer (not the implementer).
Scope: "S2 — agent trust bridge (originator→transport) [FE]".

## Verdict

APPROVE-WITH-FOLLOWUPS

The change is behaviorally correct, the backend lattice it relies on is
verified verbatim against source (not the evidence file), and the live
428/200 differential proves the claim for the right reason. The single
gap is a test-coverage one: the load-bearing FE mapping (the Shell
listener ternary) has **no** automated regression test — only the
upstream half is tested, and the mapping is proven only by a manual live
probe. That is the `audit-without-test` pattern. It does not block merge
(the live proof is genuine and the path is statically closed) but should
be closed with a follow-up unit test before S2's DoD is called complete.

## Claims verified

- ✓ **`applyEffect` threads `originator` into the `invoke-operation`
  detail.** `actions/index.ts:362` — `originator: EffectOriginator = 'user'`
  is the third param; `actions/index.ts:394-398` dispatches
  `jf-invoke-operation` with `originator` included. In scope and correct.
- ✓ **`originator` threads from `invokeAndApply`.** `actions/index.ts:512`
  param → `:526` `applyEffect(effect, action.provenance, originator)`.
- ✓ **A real agent path supplies `'agent'` (not dead code).**
  `VirtualToolDispatcher.ts:61` → `invokeCommandWithResult(commandId,
  { originator: 'agent' })` → `CommandRegistry.ts:148,158`
  `invokeAndApply(actionId, {}, null, undefined, originator)`. Full chain:
  agent harness → dispatcher → command registry → invokeAndApply →
  applyEffect → `jf-invoke-operation{originator:'agent'}` → Shell listener.
- ✓ **Shell listener maps originator→transport and REPLACED `'EFFECT'`.**
  `Shell.ts:815-820` ternary `agent→'LLM_EMISSION' / system→'SYSTEM_INTERNAL'
  / else→'BUTTON'`; `:824` `client.invoke(operationId, { args…, transport })`.
  The prior `transport: 'EFFECT'` literal is gone (confirmed in diff).
- ✓ **No regression for user/system paths.** `'user'→'BUTTON'` (TRUSTED)
  preserves the prior `'EFFECT'→degrade-to-BUTTON` (TRUSTED) behavior;
  `'system'→'SYSTEM_INTERNAL'` is also TRUSTED. Only the agent path
  changes tier (TRUSTED→UNTRUSTED). Verified against lattice (below).
- ✓ **All three transport strings are real `TransportTag` constants** —
  `TransportTag.java`: `LLM_EMISSION` (41), `BUTTON` (45),
  `SYSTEM_INTERNAL` (72). None fall through `valueOf` to the BUTTON catch.
- ✓ **Backend: `LLM_EMISSION → SourceTier.UNTRUSTED`.**
  `CoreIntentSourceCatalog.java:126-131` (`LLM_CHAT_EMISSION` source,
  `SourceTier.UNTRUSTED`, `TransportTag.LLM_EMISSION`). `BUTTON→TRUSTED`
  (`:89-95`), `SYSTEM_INTERNAL→TRUSTED` (`:148-154`).
- ✓ **Backend lattice: `UNTRUSTED × MEDIUM → TYPED_CONFIRM`,
  `TRUSTED × MEDIUM → AUTO`.** `CoreTrustEvaluator.java:73` (TRUSTED
  LOW,MEDIUM→AUTO) and `:81-83` (UNTRUSTED LOW→AUTO; MEDIUM,HIGH→
  TYPED_CONFIRM). HIGH is TYPED_CONFIRM for both tiers (`:74`, `:83`), so
  the behavioral delta is precisely MEDIUM-risk write ops.
- ✓ **`'EFFECT'` was genuinely broken.** `OperationsController.java:185-192`
  — `TransportTag.valueOf(...)` throws `IllegalArgumentException` for the
  unknown string `EFFECT`, caught → `InvocationProvenance.uiButton(now)`
  = BUTTON = TRUSTED. This is exactly the silent-degrade the commit
  describes.
- ✓ **New test is a real assertion, not weakened.** `actions.test.ts:241-251`
  `applyEffect({…}, CORE_PROVENANCE, 'agent')`; asserts
  `toHaveBeenCalledTimes(1)` and `ev.detail` `toMatchObject({operationId:
  'core.bulk-reindex', originator:'agent'})`. Positive, specific.
- ✓ **Type safety.** `OperationClient` request field is `transport?: string`
  (`OperationClient.ts:30`); the listener's literal-union const is
  assignable. Detail's inline type adds `originator?: 'user'|'agent'|'system'`,
  matching the canonical `EffectOriginator` (`effects/index.ts:58`).

## Findings

1. **(MEDIUM) The behavioral core has no automated test.** The new test in
   `actions.test.ts` only locks the *upstream* contract (detail carries
   `originator`). The actual S2 behavior — the Shell listener ternary
   `agent→LLM_EMISSION / system→SYSTEM_INTERNAL / user→BUTTON` and the
   resulting `X-JustSearch-Transport` header — is untested. `Shell.test.ts`
   does not exercise `invokeOperationListener` at all (no `invoke-operation`
   / `originator` / `transport` reference). The mapping is verified only by
   the manual live probe. Per the project's `audit-without-test` discipline,
   a narrow lifecycle claim needs a green regression test, not just a
   passing live run. **Follow-up:** add a Shell-listener unit test that
   dispatches `jf-invoke-operation` with each `originator` value and asserts
   the `transport` passed to a mocked `OperationClient.invoke` (`'agent'`→
   `'LLM_EMISSION'`, `'system'`→`'SYSTEM_INTERNAL'`, `'user'`/absent→
   `'BUTTON'`).

2. **(LOW) No `system`-originator test, and `system` has no production
   producer in this slice.** The `'system'→'SYSTEM_INTERNAL'` branch is
   asserted by neither unit test nor live evidence (live proof covered only
   agent vs user). `SYSTEM_INTERNAL` is TRUSTED so the trust outcome equals
   the `BUTTON` default — low risk — but the branch is unverified. Cover it
   in the same follow-up test.

3. **(LOW / semantic) Agent tool-calls are stamped `LLM_EMISSION`, but the
   catalog reserves `AGENT_LOOP` (`LLM_AGENT_TOOL_CALL`) for the agent
   tool-call ingress.** `CoreIntentSourceCatalog.java:132-138` maps
   `TransportTag.AGENT_LOOP → UNTRUSTED` for `core.agent-tool-call`, whereas
   `LLM_EMISSION` is the chat-emission ingress (`core.markdown-url`). Both
   are UNTRUSTED, so the *trust outcome is identical* (TYPED_CONFIRM) — no
   behavioral defect. But the audit attribution / source-id will read as a
   chat emission rather than an agent tool-call, which is semantically
   slightly off for the `VirtualToolDispatcher` path. Worth a one-line
   decision: is `LLM_EMISSION` intentional for all `originator==='agent'`
   effects, or should agent-loop invocations carry `AGENT_LOOP`? Not a
   blocker.

4. **(INFO) No collateral breakage.** `applyEffect` (index.ts:394) is the
   *only* dispatcher of `jf-invoke-operation`; Shell is the only listener.
   Any future dispatch lacking `originator` falls to the `else→'BUTTON'`
   branch = prior behavior. Safe.

## Live-evidence assessment

The evidence (`543-fwd-live-evidence.md`) genuinely proves the claim and
does **not** pass for a wrong reason:

- **Part A is the load-bearing proof.** An explicit-header probe to
  `POST /api/operations/core.index-gc/invoke`: `LLM_EMISSION → 428
  CONFIRMATION_REQUIRED` with the backend message explicitly naming
  `gate=TYPED_CONFIRM, sourceTier=UNTRUSTED`, vs `BUTTON → 200 success
  (AUTO)`. The `sourceTier=UNTRUSTED` string in the response is direct
  evidence the lattice resolved via the UNTRUSTED row — not an incidental
  428 from some unrelated validation. `core.index-gc` is correctly a
  MEDIUM-risk op (the only risk tier where the agent/user outcome differs).

- **Part B closes the FE half** by driving the *real* Shell listener via a
  genuine `jf-invoke-operation` dispatch (not a hand-built fetch) and
  observing `agent → 428` vs `user → 200`.

- **Identified gap (matches the prompt's concern):** Part B captures the
  *HTTP status code* from the network monitor, not the literal
  `X-JustSearch-Transport` request header. So Part B in isolation proves
  "agent → some-UNTRUSTED transport", not specifically `LLM_EMISSION`. The
  claim is nonetheless closed because: (a) Part A proves `LLM_EMISSION` →
  UNTRUSTED → 428 by explicit header; (b) static review confirms the
  listener emits the literal `'LLM_EMISSION'` for `agent` and
  `OperationClient` forwards `transport` verbatim into
  `X-JustSearch-Transport`. The three legs (explicit-header probe + verbatim
  source path + live agent-dispatch differential) together pin the claim.
  The gold-standard artifact still missing is a *captured request header*
  showing `X-JustSearch-Transport: LLM_EMISSION` on the agent path — cheap
  to add and would make Part B self-sufficient.

Backend is unchanged (confirmed: diff touches only three `ui-web` files),
consistent with "LLM_EMISSION→UNTRUSTED already registered."

---

## fwd-slice-3-review

*(consolidated from `543-fwd-slice-3-review.md`)*

### S3 Independent Review — causation graph (R-E3 / R-P1) + confidence (R-E2)

Commit: `0f21a45a9` — "S3 — causation graph (R-E3/R-P1) + confidence (R-E2)"
Reviewer: independent (did not author S3). Verified against worktree source at review time.

## Verdict

APPROVE-WITH-FOLLOWUPS

The substrate logic is correct and well-tested; the consumers are correct by
inspection and screenshot-proven. The one substantive gap is that an existing
component-render harness (`PendingEffectQueue.test.ts`) — which already mounts
the real component and drives it through the real substrate — was left
unextended, so the new confidence chip + most-uncertain-first sort have NO
automated render assertion, and `EffectAuditLog` has no render test at all. The
live-evidence note itself admits the screenshots could not exercise the
integrated substrate→component path (Vite dual-module-instance quirk), and that
is precisely the path the existing harness *can* cover. Not a blocker — nothing
is wrong — but the cheapest available automated coverage was skipped.

## Claims verified

- ✓ `PendingEffect` + `ProposeEffectOptions` gain optional `confidence`, stored
  with spread guard (omitted when undefined) — `pending-effects/index.ts:50`,
  `:73`, `:97`. Optional, not stored when absent.
- ✓ `acceptPending` accepts optional `causation`, threaded into
  `recordEffect(... { ...causation })` on the **'accepted'-outcome** entry —
  `pending-effects/index.ts:116`, `:134`. `recordEffect` stores it via spread
  guard — `effects/index.ts:325`.
- ✓ Two journal entries per accept confirmed: `applyFn` → `applyEffect` calls
  `recordEffect(effect, invokedBy, { originator })` with **no** `pendingOutcome`
  (`actions/index.ts:369`), then `acceptPending` records the
  `pendingOutcome:'accepted'` entry. The applyFn entry is correctly excluded by
  the test's `pendingOutcome === 'accepted'` filter.
- ✓ `acceptSequence` threads `prevEntryId` from `acceptPending`'s RETURN (the
  accepted-outcome entry id), not the dispatch entry —
  `pending-effects/index.ts:247-253`. First entry's `prevEntryId` is
  `undefined` → first accepted entry has no `causation`. Each subsequent
  accepted entry's `causation` points to the PREVIOUS accepted entry's id.
  Traced manually: acc = [idA(causation=∅), idB(causation=idA),
  idC(causation=idB)] — matches the claim and the test.
- ✓ `getCausationChain(id)` walks `causation` pointers, cycle-guarded by a
  `seen` set keyed on the `causation` target, returns ancestors oldest-first
  (`.reverse()`), empty when no causation / unknown id —
  `effects/index.ts:405-414`. The `seen.has(cur.causation)` guard before
  following + `if (!parent) break` are both sound; append-only journal can't
  cycle but a corrupt restore is defended.
- ✓ R-P1 discharged: before `0f21a45a9~1`, `causation` existed in the
  interface and `recordEffect` could store it, but **no** production caller
  ever passed `opts.causation` (verified via `git grep causation 0f21a45a9~1`
  — only interface/comment/spread-guard sites, zero feeders). S3 is the first
  feeder. Genuinely a dead field before.
- ✓ PendingEffectQueue confidence chip: `?data-low=${p.confidence < 0.5}`,
  `${Math.round(p.confidence * 100)}%`, rendered only when
  `p.confidence !== undefined` — `PendingEffectQueue.ts:191-196`. Styles for
  `.confidence` + `.confidence[data-low]` added (`:108-119`).
- ✓ PendingEffectQueue sort `(a.confidence ?? 1) - (b.confidence ?? 1)` on a
  copied array (`[...this.pending]`, non-mutating) puts lowest-confidence first,
  unscored treated as 1 (last) — `PendingEffectQueue.ts:237-239`.
- ✓ EffectAuditLog causation chip renders for `e.causation !== undefined`,
  mirrors the existing `pendingOutcome` chip pattern, reuses the `chip` class —
  `EffectAuditLog.ts:419-426`.
- ✓ Test `causation.test.ts` (4 tests) asserts real properties:
  `acc[1].causation === acc[0].id`, `acc[2].causation === acc[1].id`,
  `acc[0].causation` undefined, `getCausationChain` returns
  `[acc[0].id, acc[1].id]`, empty case for single-element sequence, confidence
  stored/absent. Filters `pendingOutcome === 'accepted'` correctly. Not
  trivially-true assertions.
- ✓ Tests green: `causation.test.ts` 4/4; pending-effects + effects suites
  55/55. `npm run typecheck` clean.

## Findings

1. **[Medium] No automated component-render coverage for the new chip/sort,
   despite an existing harness that could provide it.**
   `PendingEffectQueue.test.ts` already mounts `<jf-pending-effect-queue>`,
   drives it through the real `proposeEffect` substrate, and asserts on
   shadow-DOM (`.card`, `.kind`, `.rationale`). Adding a `confidence` value to a
   `proposeEffect` call and asserting the `.confidence` chip text, the `data-low`
   attribute, and card ordering would be a few lines in that same harness — and
   would cover exactly the integrated substrate→component path the live-evidence
   note admits the screenshots could NOT reach (the Vite dual-module-instance
   quirk). The chip/sort are correct by inspection, but they rest on
   screenshots + hand-fed state, not regression-proof unit assertions. This is
   the `audit-without-test`-adjacent pattern: render correctness proven by
   eyeball, not by a runnable test, when a runnable test was cheap.

2. **[Low] `EffectAuditLog` has no render test at all** (confirmed: no
   `EffectAuditLog.test.ts` exists). The causation chip is correct by
   inspection and mirrors the proven `pendingOutcome` pattern, but there is no
   automated guard that `e.causation !== undefined → ↳ #N` renders and that the
   root entry shows none. Same remediation class as #1.

3. **[Info / not a defect] confidence === 0 edge case is handled correctly.**
   `0 ?? 1` → `0` (nullish-coalescing does not fire on `0`), so a
   zero-confidence proposal sorts first — correct. `0 < 0.5` → `data-low` true —
   correct. The chip renders (`0 !== undefined`) showing "0%". No bug.

4. **[Info / not a defect] causation === 0 renders `↳ #0`.** The
   `!== undefined` guard (not truthiness) correctly renders a chip for a
   causation pointer of id 0. In practice the two-entry-per-accept pattern means
   the first accepted entry is id ≥ 1, so a real causation value of 0 is
   unlikely, but the guard is correct regardless.

5. **[Info] `acceptPending(id, fn, causation)` single-call path is exercised
   only indirectly** (through `acceptSequence`). The direct three-arg call with
   an explicit `causation` is not unit-tested in isolation, but `acceptSequence`
   covers the only production caller and the chaining assertions transitively
   prove the parameter threads through `recordEffect`. Acceptable.

## Live-evidence assessment

The S3 live-evidence section is **honest and appropriately scoped**, which is
to its credit — it explicitly discloses that the Vite dev server serves the app
and the console `import()` as separate module instances, so substrate state set
via console did not reach the mounted components, and that component RENDERING
was therefore proven by feeding the real mounted components hand-built state
rather than by an end-to-end substrate→component flow. That disclosure is the
right call and avoids the "passed for the wrong reason" trap.

However, the proof it delivers is split: the substrate LOGIC is genuinely
unit-tested (strong), and the component RENDER branches are genuinely
screenshot-proven given well-formed input (adequate for "does the chip draw").
What is NOT proven by any tier is the integrated path — real substrate output
flowing into the real mounted component — because the screenshots used injected
state and no unit test exercises the component. That integrated path is exactly
what the pre-existing `PendingEffectQueue.test.ts` harness is built to cover
(it mounts the component and drives it through `proposeEffect`). So the
live-evidence is legitimate as far as it goes, but it leans on a manual,
non-regressable proof where a cheap automated one was available. The substrate
is adequately covered; the consumers are under-covered relative to the tooling
already in the repo.

Net: nothing is incorrect. The follow-up is to extend the existing
PendingEffectQueue render harness with confidence chip + sort assertions and add
a minimal EffectAuditLog render test for the causation chip — closing the one
tier (integrated render) that currently rests on screenshots alone.

---

## fwd-slice-4-review

*(consolidated from `543-fwd-slice-4-review.md`)*

### S4 Review — "What the AI did" digest (U3) — commit e27645339

Independent adversarial review. Reviewer did not write this code.

## Verdict

APPROVE-WITH-FOLLOWUPS

## Claims verified

- ✓ `summarizeAgentActivity(sinceId)` counts `originator==='agent'` entries, exclusive of `id <= sinceId`, excluding `pendingOutcome==='rejected'`, grouped by `effect.kind`, returning `{total, byKind, latestId}` — `modules/ui-web/src/shell-v0/substrates/effects/index.ts:374-389`.
- ✓ Counting rule does NOT double-count accepted proposals. Verified against the substrate: `acceptPending` calls `applyFn(effect, invokedBy)` (2 args ⇒ `applyEffect` records under default `originator='user'`, `actions/index.ts:362`) and then records a SECOND entry tagged `originator: pending.originator` + `pendingOutcome:'accepted'` (`pending-effects/index.ts:130-135`). So exactly one agent row per accepted action. Tally-of-agent-rows = one-per-action holds — `pending-effects/index.ts:113-138`, `actions/index.ts:359-369`.
- ✓ Rejected entries excluded from counts; `latestId` still advances past them. The loop bumps `latestId` BEFORE the `rejected` `continue` (`effects/index.ts:382-385`), so "Mark as seen" dismisses rejected proposals too. This is consistent with intent (a vetoed proposal the user has already acted on should not keep re-surfacing as "new"). Acceptable.
- ✓ `sinceId` exclusive; `latestId` starts at `sinceId`, only grows (`effects/index.ts:381,383`). Unit-asserted precisely (`agentDigest.test.ts:46-55`, total=1 after cursor).
- ✓ undo-all calls `undoAllByOriginator('agent', (e) => { applyEffect(e); })` — correct signature; `applyEffect(effect, invokedBy=CORE_PROVENANCE, originator='user')` so 1-arg call is valid and the dispatched inverse records as a user action (correct — human pressed undo) — `AiActivityDigest.ts:151-156`, `actions/index.ts:359-362`. Button only renders when `digest.total > 0` (`AiActivityDigest.ts:189`). Honest-button caveat acknowledged: many agent effects have null inverse (toast/invoke-operation per R-P2 at `effects/index.ts:209-226`); `undoAllByOriginator` skips `inverse===null` (`effects/index.ts:523`) — it reverses what it can.
- ✓ mark-seen reads/writes cursor via `safeLocalStorage` (`storage.ts:15-24`, `AiActivityDigest.ts:27-37,158-162`); refresh recomputes (`AiActivityDigest.ts:71-74`); collapse logic `total===0 && pendingAgent===0` → `data-empty` in `updated()` (`AiActivityDigest.ts:163-167`).
- ✓ Subscribes to journal + pending on connect, unsubscribes on disconnect — no leak across a connect/disconnect cycle (`AiActivityDigest.ts:60-69`).
- ✓ Constructor reads journal/pending before connect; no SSR/no-window hazard — `safeLocalStorage` guards storage; `summarizeAgentActivity`/`listPending` are pure array reads (`AiActivityDigest.ts:53-58`).
- ✓ Mounted in Shell render tree as a production consumer, beside `<jf-pending-effect-queue>` — `chrome/Shell.ts:1812` (inside `render()` at 1679), import at `Shell.ts:47`.
- ✓ Tests present and green: `agentDigest.test.ts` (3) + `AiActivityDigest.test.ts` (3). Ran `vitest run` on both files: 6/6 passed. `tsc --noEmit` clean for S4 files.

## Findings

1. **[Medium] Pending-badge path is unit-untested.** The commit claims an "agent-pending count badge" as a feature. `countAgentPending()` (`AiActivityDigest.ts:65-67`), the `pendingAgent` state, the `ai-digest-pending` badge (`AiActivityDigest.ts:173-177`), and the pending-only non-empty case (`data-empty` stays off when `total===0 && pendingAgent>0`) are exercised by NO unit test — no test proposes an agent pending effect and asserts the badge renders or that the digest stays visible on pending alone. The path is proven only by the live screenshot ("1 PENDING"). Follow-up: add a component test that proposes an agent pending effect and asserts the badge + non-collapse.

2. **[Medium] Subscription-driven refresh is unproven.** All three component tests call `recordEffect(...)` BEFORE `appendChild`, so the constructor/`connectedCallback` initial read covers them. The `subscribeJournal(() => this.refresh())` / `subscribePending(...)` wiring (`AiActivityDigest.ts:62-63`) — i.e. an agent effect landing AFTER the component is connected, triggering a live re-render — is exercised by neither tier: unit tests record-before-connect, and the live screenshot fed mounted state directly (Vite dual-instance, documented). The integrated substrate→subscription→digest-refresh flow is structurally present but unverified. Follow-up: add a test that connects the component first, then `recordEffect({originator:'agent'})`, then asserts the summary updated.

3. **[Low] undo-all does not assert reversal in tests.** `AiActivityDigest.test.ts:42-67` asserts the undo-all button exists but never clicks it nor asserts an inverse was dispatched. Button-present ≠ button-works.

4. **[Low / UX, not a correctness bug] Digest count does not drop after "Undo all AI actions".** The journal is append-only; undo dispatches inverses recorded under `originator='user'` (correctly excluded from the agent count), so the original agent entries remain and `summaryText()` still shows e.g. "2 navigates" after undo-all. A user may expect the count to clear. Consider advancing the seen-cursor on undo-all, or document the intent.

5. **[Low / out-of-scope, logged to observations.md] `undoAllByOriginator` does not skip `pendingOutcome:'rejected'` entries.** A rejected (vetoed, never-dispatched) agent effect that has a derivable inverse (e.g. `open-pane`→`close-pane`) would be "undone" by the button, dispatching a `close-pane` for a pane that was never opened. This is pre-existing §28.W4 behavior, not introduced by S4 (S4 only excludes rejected from *counts*), but the new button is the first prominent consumer. `effects/index.ts:494,514`.

6. Pluralization / empty-byKind edge cases: clean. `summaryText` (`AiActivityDigest.ts:181-186`) pluralizes per-kind; `byKind` empty ⟺ `total===0` (both mutate in the same loop iteration, `effects/index.ts:386-387`), so "byKind empty but total>0" is structurally impossible. The `'no completed actions'` fallback only renders in the pending-only visible state — reasonable. No double-subscribe across a normal Lit connect/disconnect cycle.

## Live-evidence assessment

The live screenshot `ss_2608s9heq` proves the component RENDERS the full surface in the real mounted Shell — summary text, the "1 PENDING" badge, "Undo all AI actions", "Mark as seen". The live-evidence doc honestly discloses the Vite dual-instance limitation: substrate state set via console import does not reach mounted components, so rendering was proven by feeding the real mounted component realistic state, and substrate LOGIC was proven by unit tests through the real module.

The combination is adequate for the *static* feature surface but leaves one genuine integration gap unproven by ANY tier: the **subscription-driven live refresh** — a real agent Effect dispatched after the component is connected, propagating through `subscribeJournal`/`subscribePending` into a re-render. Unit tests record-before-connect; the live screenshot injected state directly. This is the §28 "passes for the right reason" risk: every individual piece is verified, but the wire that makes the digest *update in response to live agent activity* — the whole point of "Since you last looked" — is the one path no test or screenshot exercises. It is low-risk (the subscribe→refresh code is trivial and the substrates' notify is unit-tested elsewhere), hence APPROVE-WITH-FOLLOWUPS rather than BLOCK, but Findings #1 and #2 should be closed before the slice is called fully done.

---

## fwd-slice-5-review

*(consolidated from `543-fwd-slice-5-review.md`)*

### S5 — Undo-the-AI (U2) — Independent Review

Reviewer: independent adversarial pass (did not write the code).
Commit: `9a2f619bf` — "feat(543-fwd): S5 — Undo-the-AI (U2)".
Date: 2026-05-25.

## Verdict

APPROVE-WITH-FOLLOWUPS

## Claims verified

| Claim | Status | Evidence |
|---|---|---|
| `undo-operation` Effect kind in the closed union | ✓ | `effect.ts:57` — `{ kind: 'undo-operation'; operationId: string; executionId: string }` added to the `Effect` union. |
| `applyEffect` handles it; `never` exhaustiveness still satisfied | ✓ | `actions/index.ts:404-411` case before the `never` default at `:504-510`. `npm run typecheck` clean — proves the union is closed (an unhandled arm would fail the `never` assignment). |
| `deriveInverse('undo-operation')` returns null | ✓ | `effects/index.ts:280-281` — `case 'undo-operation': return null;`. |
| Side-map keyed by journal entry id; reset clears it | ✓ | `effects/index.ts:475` `_undoable = new Map<number, UndoableOperation>()`; cleared in `__resetJournalForTest` at `:598` (`_undoable.clear()`). |
| `markUndoableOperation` re-fires journal listeners | ✓ | `effects/index.ts:483-484` — `notifyAllWith(_listeners, entry)` after `_undoable.set`. No re-entrancy/infinite-loop (see Finding 1). |
| `applyEffect`: `journalEntry` is the entry whose id is threaded | ✓ | `actions/index.ts:369` `const journalEntry = recordEffect(...)`; invoke-operation detail carries `journalEntryId: journalEntry.id` at `:401`. `recordEffect` returns the just-pushed `JournalEntry` with its `id` (`effects/index.ts:321-335`). |
| Shell jf-invoke listener: `markUndoableOperation` only when `result.executionId && detail.journalEntryId !== undefined` | ✓ | `Shell.ts:816-825`. Guard is exactly that. |
| `OperationClient.invoke` returns `executionId` on success | ✓ | `OperationClient.ts:190-195` returns `executionId: parsed.executionId`; `WireResponseShape.executionId?` at `:105`. |
| `.catch` preserved on invoke | ✓ | `Shell.ts` — `.then(...)` inserted *between* `invoke()` and the existing `.catch((err) => …toast…)`; error path intact. |
| Shell jf-undo listener: reads + guards both ids, calls `OperationClient.undo` | ✓ | `Shell.ts:856-877` — `if (!operationId \|\| !executionId) return;` then `client.undo(...)` with `.catch` toast. |
| Listener field declared + removed in `disconnectedCallback` (no leak) | ✓ | declared `Shell.ts:351`; `addEventListener` at `:878`; removed `:1319-1325` with null-out. Symmetric. |
| `EffectAuditLog.renderUndoOp` returns button only when `getUndoableOperation(e.id)` exists; click → `applyEffect({kind:'undo-operation',…})` | ✓ | `EffectAuditLog.ts:443-456` (`if (!u) return nothing`); `:458-460` `handleUndoOp` calls `applyEffect`. Invoked per-entry at `:429`. |
| `OperationClient.undo` URL is `/api/undo/{id}` | ✓ | `OperationClient.ts:204` `POST ${apiBase}/api/undo/${encode(operationId)}`, body `{executionId}`. Matches backend route `LocalApiServer.java:1660` `app.post("/api/undo/{id}", …handleUndo)`. NOT `/api/operations/{id}/undo`. Correct. |
| Tests assert the right things | ✓ | See Findings 1 (test gap, non-blocking). EffectAuditLog test drives the REAL component through the REAL single-instance substrate (`recordEffect`+`markUndoableOperation` imported from the same `effects/index.js` the component reads), `open=true`, queries `[data-testid="undo-op-${id}"]`, clicks, asserts `jf-undo-operation` detail `{operationId, executionId}`. |
| All S5 tests green | ✓ | 29/29 pass (`undoable.test.ts` 2, `actions.test.ts` 25, `EffectAuditLog.test.ts` 2). typecheck clean. eslint: 0 errors. |

## Findings

1. **[LOW — test precision] The re-fire→re-render path is not exercised by a test.**
   `markUndoableOperation` re-fires journal listeners (`effects/index.ts:483-484`) specifically so the audit log re-renders to show "Undo" for an entry whose executionId arrived *after* the entry was already on screen. The EffectAuditLog test calls `markUndoableOperation` BEFORE `open=true`/`flush()`, so the button renders from first paint — it never proves the re-fire actually triggers a re-render of an already-open log. The code is correct (the journal listener is the component's `subscribeJournal` re-render hook), but the test passes for a weaker reason than the feature claims. Recommend a follow-up test: open log → record invoke-operation entry → assert no button → `markUndoableOperation` → flush → assert button appears.

2. **[INFO — re-entrancy, no defect] No infinite loop / re-entrancy in `markUndoableOperation`.**
   The re-fired `_listeners` are journal subscribers (audit-log re-render). A re-render only *reads* `getUndoableOperation`; it never calls `markUndoableOperation`. `notifyAllWith` is a single synchronous swallow-loop (`primitives/notify.ts`). No cycle. Two invoke-operations cannot share a `journalEntryId` (`_nextId++` is monotonic). Confirmed safe.

3. **[INFO — no defect] `journalEntry` const now allocated for every `applyEffect` call but read only by `invoke-operation`.**
   `recordEffect` was always called for its side effect; the only change is capturing its return. No extra allocation, no unused-var lint (read in the invoke-operation arm). eslint clean.

4. **[INFO — capture-half not live-proven] The executionId-CAPTURE half rests on code-reading + unit tests only.**
   See Live-evidence assessment. The live proof covered only the undo-DISPATCH half. The capture half (real backend invoke → `result.executionId` → `markUndoableOperation`) is verified by code-reading (`Shell.ts:816-825`) and the `actions.test.ts` `journalEntryId` threading test + `undoable.test.ts` side-map test — but no end-to-end run drove a real undoSupported op through invoke and observed a real executionId landing in the side-map. Acceptable for an FE-wire slice, but should be named, not implied, as a coverage boundary.

5. **[INFO — pre-existing, out of scope] 3 stale `eslint-disable(no-console)` warnings** at `Shell.ts:409,753,1253` predate this commit (introduced `96627c817`); not in the S5 diff. Logged to `docs/observations.md` inbox.

## Live-evidence assessment

The live proof (`543-fwd-live-evidence.md` S5 section) dispatched
`jf-undo-operation {operationId:'core.index-gc', executionId:'fake-exec-123'}`
→ `OperationClient.undo` → **POST /api/undo/core.index-gc → 200**.

**What it genuinely proves:** the FE→backend undo *transport wire* fires
end-to-end — the Shell listener is wired, `OperationClient.undo` builds the
correct URL (`/api/undo/{id}`) and body (`{executionId}`), the backend route
exists (`LocalApiServer.java:1660`) and reaches `handleUndo`.

**What the synthetic id / 200 does NOT prove (and why it doesn't hide a
defect):** the 200 does NOT mean a real reversal happened. Trace:
`handleUndo` (`OperationsController.java:198-242`) resolves the op, reads the
body executionId, calls `dispatcher.undo(op, "fake-exec-123")`, and writes
`writeResponse(ctx, 200, …fromResult(result))` for *any* non-throwing
`OperationResult` — including `OperationResult.failure(...)`. So a `success:false`
result still returns HTTP 200 with a failure body. Separately,
`OperationExecutorImpl.undo` (`:377-412`) does NOT validate `executionId`
against any record of a prior execution; it only checks `op.policy().undoSupported()`,
then delegates to `handler.undo(executionId)`. `IndexGcHandler` does not
override `undo`, so the default (`OperationHandler.java:55-58`) throws
`UnsupportedOperationException` → 500 HANDLER_ERROR — UNLESS `core.index-gc`
is declared `undoSupported=false`, in which case the executor returns
`OperationResult.failure(...)` and the controller writes **200 with success:false**.
Either way, the synthetic id reaching 200 reflects the transport + framing
layer, not a verified reversal. The live-evidence note states exactly this
("executionId synthetic — proves the wire, not a specific reversal") — an
honest framing, not an overclaim.

**Capture half unproven live (Finding 4):** the live test exercised only the
dispatch half. The invoke→`result.executionId`→`markUndoableOperation` half
is code-read + unit-tested but never run against a real backend that returns
a real executionId. For a FE-wire slice this is an acceptable boundary, but it
should be recorded as such rather than presented as fully live-verified.

**Net:** the slice's S5-scoped claims (new Effect kind, side-map, threading,
two listeners, audit-log button, FE→backend undo wire) are all true and
verified by the right mechanisms. The two follow-ups (test the re-render-on-
late-mark path; live-prove the capture half against a real undoSupported op)
are coverage gaps, not correctness defects — hence APPROVE-WITH-FOLLOWUPS.

---

## fwd-slice-6-review

*(consolidated from `543-fwd-slice-6-review.md`)*

### S6 — Autonomy Dial (U1) — Independent Review

Commit: b210095446cbc369064aceb62a37eef3db7be1bd
Reviewer: independent (did not author S6). Lens: "passes for the wrong reason."

## Verdict

APPROVE-WITH-FOLLOWUPS

## Claims verified

- **Safety: dial leaves destructive-op gating to the backend.** ✓
  The only behavioral change in `invokeAndApply` is a propose branch gated on
  `originator === 'agent' && agentInvocationDisposition() === 'propose'`
  (`actions/index.ts:540-545`). `agentInvocationDisposition` returns `propose`
  ONLY for `watch` (`autonomy/index.ts:73-77`). For assist/auto the code falls
  through to the unchanged `applyEffect(effect, action.provenance, originator)`
  (`actions/index.ts:550`). The `originator` value is **not mutated anywhere in
  this diff** — auto does NOT send a higher-trust transport. The
  `invoke-operation` effect dispatch still threads the same `originator` to the
  Shell listener (`actions/index.ts:399-404`), which stamps the AGENT_LOOP
  TransportTag → backend (SourceTier × RiskTier) lattice. No bypass. **This is
  the load-bearing claim and it holds.**

- **Gate fires only for agent + watch; user/system unaffected.** ✓
  Condition is `originator === 'agent' && ... === 'propose'`
  (`actions/index.ts:540`). A `user` invocation in watch dispatches normally —
  proven by `autonomy-gate.test.ts:51-57` (watch+user → journaled, not queued).
  `proposeEffect(effect, action.provenance, 'agent', { rationale })` uses the
  action's provenance + literal `'agent'` originator (`actions/index.ts:541-543`),
  matching `proposeEffect`'s signature (`pending-effects/index.ts:83-88`).

- **No regression to existing invokeAndApply.** ✓
  assist/auto path is identical to pre-S6 (same `applyEffect` call, same
  args). Default `originator='user'` (`actions/index.ts:526`) unchanged.

- **Substrate: default assist, set/get, persistence, invalid ignored,
  subscribe-on-change-only, disposition mapping.** ✓
  Default `assist` (`autonomy/index.ts:35`, test :29). set/get + persist to
  `justsearch.autonomy.level.v1` (:50-55, test :35-43). Invalid + no-op set
  short-circuits before notify (`setAutonomyLevel` :50: `if (!LEVELS.includes(level) || level === _level) return;`) — invalid-level
  is ignored and a no-op does not notify (test :47-55 asserts 2 notifications
  for watch/watch/auto). disposition mapping tested :32-36.

- **autonomy-gate.test.ts uses REAL substrates in one process.** ✓
  Imports real `invokeAndApply`, real `proposeEffect`/`listPending`, real
  `listJournal`, real autonomy. No mocks/spies. Resets all four substrates in
  `beforeEach`. This is a genuine integrated flow test.

- **AutonomyDial.test.ts asserts segments + active + click→setAutonomyLevel.** ✓
  3 segments (`.seg`), assist `data-active` by default (:39-46); click watch
  → `getAutonomyLevel()==='watch'` + active moves watch-on/assist-off (:48-64).

- **localStorage SSR/Private-mode guards.** ✓
  Substrate uses `safeLocalStorage()?.…` everywhere (`autonomy/index.ts:27,52,80`);
  `safeLocalStorage` returns null on `undefined` (SSR) or throw (Safari Private)
  (`storage.ts:15-24`). Tests guard with `globalThis.localStorage?.clear()`.

- **__resetAutonomyForTest fully resets module-level `_level`.** ✓
  `_level = readPersisted()` at import (`autonomy/index.ts:42`) is overwritten by
  `__resetAutonomyForTest()` which sets `_level = DEFAULT_LEVEL`, clears
  listeners, removes the key (:78-82). Tests call `localStorage.clear()` THEN
  `__resetAutonomyForTest()` in beforeEach — module-import read cannot leak.

- **Dial subscribe/unsubscribe (no leak).** ✓
  `connectedCallback` subscribes, stores unsub; `disconnectedCallback` calls
  `this.unsub?.()` and nulls it (`AutonomyDial.ts:36-49`). `subscribeAutonomy`
  returns a real `delete` closure (`autonomy/index.ts:64-69`).

- **icon 'layers' exists.** ✓ `Icon.ts:40` (type) + `:100` (svg path).

- **Verification claims (tsc clean, vitest green).** ✓ Reproduced:
  `npx tsc --noEmit` → exit 0. The three S6 test files → 10/10 passed.

## Findings

1. **(LOW — UX honesty, not safety)** assist and auto map to the SAME
   disposition (`dispatch`); `agentInvocationDisposition` only branches on
   `watch` (`autonomy/index.ts:73-77`). But the assist hint reads "Agent acts;
   risky (write/destructive) actions ask first" (`AutonomyDial.ts:35`),
   implying an FE-side gate that does not exist — the "ask first" is entirely
   the backend lattice (TYPED_CONFIRM), which fires identically in auto. The
   copy is defensible (the gating IS real, just backend-side) but could lead a
   user to believe assist provides FE oversight that auto removes; in this diff
   the two levels are FE-behaviorally identical. Recommend either tightening the
   assist/auto copy or documenting that the assist/auto distinction is
   backend-lattice-only. Does NOT affect the safety guarantee.

2. **(LOW — test gap)** No `auto + agent` case in autonomy-gate.test.ts (only
   watch+agent, assist+agent, watch+user). Because auto==assist by construction
   the missing case is currently redundant, but a future regression that made
   `auto → propose` (or `auto` elevate trust) would be uncaught at the gate
   level. Cheap to add an `auto + agent → dispatched` assertion as a guard
   against the disposition table drifting. Follow-up, not a blocker.

3. **(INFORMATIONAL)** The gate test exercises a `toast` effect, not an
   `invoke-operation` (destructive) effect. This is appropriate for a FE-routing
   test — the destructive gate is backend and out of FE-unit scope — but it
   means the test proves "watch queues / assist dispatches," NOT "a destructive
   op is gated." The latter is the S2 backend lattice's responsibility, verified
   in S2's evidence, not here. No action; the claim split is honest.

## Live-evidence assessment

Adequate. The split is correctly drawn: the **dial UI** (render, default-active,
click-to-switch, hint, backend-enforced note) is the part that benefits from a
screenshot and was live-proven (`ss_7553kwbdc`, default assist → click Watch).
The **gate flow** (watch→propose vs dispatch) is pure substrate routing with no
visual surface, and is covered by the real-substrate integrated test
(`autonomy-gate.test.ts`), which is a higher-fidelity check than a screenshot
for that logic. The stated reason for not live-driving the gate (Vite
dual-instance + needing a real agent turn) is legitimate.

One residual "right-reason" caveat: the live screenshot proves the **dial reads
the substrate**, and the unit test proves **invokeAndApply reads the same
substrate's disposition** — but no single artifact proves the *production agent
loop* actually calls `invokeAndApply` with `originator='agent'` end-to-end in
watch mode and lands in the pending queue. That end-to-end wiring
(originator='agent' from the real AI emitter) was the subject of §28.W13 and
S2; this slice reasonably relies on those rather than re-proving them. Given
the disposition logic is trivially correct and tested with real substrates, the
combination is sufficient for APPROVE-WITH-FOLLOWUPS. The two follow-ups
(finding 1 copy, finding 2 auto-case test) are non-blocking.

---

## fwd-slice-7-review

*(consolidated from `543-fwd-slice-7-review.md`)*

### S7 — Async / long-running Task substrate (R-E1) — Independent Review

Reviewer: independent (did not author S7). Commit: `b9b6910f5`.
Worktree: `F:\JustSearch\.claude\worktrees\543-forward` (branch `worktree-543-forward`).
Method: read full diff; ran `tasks.test.ts` + `TaskList.test.ts` (7/7 pass);
`tsc --noEmit` clean; traced the Shell consumer + emitter path; read the
shared `notifyAll` primitive.

## Verdict

**APPROVE-WITH-FOLLOWUPS**

The substrate, component, and Shell wrap do what S7 claims. Lifecycle semantics
are correct, the production consumer is honest (agent-only tracking,
non-cancellable agent-op tasks → no misleading cancel affordance), and the tests
drive the real substrate/component rather than mocks. The findings below are all
low-severity / latent (no current producer triggers them) — none blocks merge.

## Claims verified

| Claim | Result | Evidence |
|---|---|---|
| Standalone substrate, NOT in closed Effect union | ✓ | `substrates/tasks/index.ts:1-142` — its own module; no `Effect`-union import. |
| `startTask` → status `running` | ✓ | `index.ts:50-62` sets `status: 'running'`. Test `tasks.test.ts:25`. |
| `cancellable` derived from presence of `cancel` fn | ✓ | `index.ts:57` `cancellable: typeof opts.cancel === 'function'`. Tests `tasks.test.ts:26,46`. |
| `completeTask('succeeded')` sets status + `progress=1` | ✓ | `index.ts:71-80` spreads `progress:1` only when `succeeded`. Test `tasks.test.ts:29-30`. |
| `completeTask` failed/cancelled leaves progress as-is | ✓ | `index.ts:78` conditional spread; failed/cancelled omit progress. |
| `updateTaskProgress` only while `running` | ✓ | `index.ts:65-69` guards `t.status !== 'running'`. Test `tasks.test.ts:36-41`. |
| `cancelTask` invokes fn + marks `cancelled`, only while running | ✓ | `index.ts:83-96` guards `running`, calls fn, sets `cancelled`. Test `tasks.test.ts:43-50`. |
| Cancel fn errors swallowed | ✓ | `index.ts:88-92` try/catch best-effort. |
| `clearFinishedTasks` prunes non-running only | ✓ | `index.ts:111-116`. Test `tasks.test.ts:52-59`. |
| `getTask` / `listTasks` / `listRunningTasks` | ✓ | `index.ts:98-109`. |
| `subscribeTasks` returns symmetric unsubscribe; no leak | ✓ | `index.ts:118-123` add + return delete. |
| `<jf-task-list>` collapses (`data-empty`) when empty | ✓ | `TaskList.ts:152-155` `updated()`; CSS `:host([data-empty]){display:none}` `TaskList.ts:48-50`. Test `TaskList.test.ts:39-42`. |
| Status chip rendered | ✓ | `TaskList.ts:160-162`. Test `TaskList.test.ts:47-52`. |
| Progress bar only when `running` + progress defined | ✓ | `TaskList.ts:176-180`. |
| Cancel button only when `running` + `cancellable` | ✓ | `TaskList.ts:163-170`. Test `TaskList.test.ts:53-55`. |
| Clear-finished only when finished tasks exist | ✓ | `TaskList.ts:185,189-196` `hasFinished` gate. |
| subscribe/unsubscribe symmetric in component | ✓ | `TaskList.ts:33-44` connected subscribes, disconnected unsubs + nulls. |
| Shell: only agent ops become tasks | ✓ | `Shell.ts:829-832` `detail?.originator === 'agent' ? startTask(...) : null`. |
| Shell: succeeded on `.then`, failed on `.catch`, null-guarded | ✓ | `Shell.ts:836,849` `if (taskId) completeTask(...)`. |
| No regression to executionId-capture / error-toast | ✓ | `Shell.ts:837-846` markUndoable untouched; `:850-858` toast untouched. New lines are additive guards above existing logic. |
| Agent-op tasks non-cancellable (no cancel fn) | ✓ | `Shell.ts:831` no `cancel` field → `cancellable:false` → cancel button never renders. Honesty maintained. |
| Tests drive the real substrate/component | ✓ | Both import `./index.js` / `./TaskList.js` directly; ran 7/7 green; tsc clean. |

## Findings

1. **[LOW] `completeTask` has no terminal-state guard — re-completion can flip
   a cancelled/finished task.** `index.ts:71-80` mutates regardless of current
   status. If a producer wires BOTH a `cancel` fn and a `.then`/`.catch`
   completer (cancellable task whose promise settles after the user cancels),
   `completeTask` would overwrite `cancelled` → `succeeded`/`failed`. **No
   current producer hits this** — the only production consumer (Shell agent-op
   wrap) creates non-cancellable tasks, so the sole completer is the promise
   settle (exactly once). Latent foot-gun for the planned indexing-jobs feed
   (which WILL be cancellable). Suggest: guard `completeTask` with
   `if (t.status !== 'running') return;` symmetric to `cancelTask`. Follow-up,
   not a blocker.

2. **[LOW] No auto-prune of finished tasks — unbounded accumulation across a
   long session.** `clearFinishedTasks` is manual (button-driven). A session
   firing many agent ops accumulates succeeded/failed entries until the user
   clicks "Clear finished". Bounded by user action and visually bounded
   (`max-width:24rem`), but the in-memory `Map` grows unboundedly. Not a leak
   in the listener sense (subscribe/unsubscribe are symmetric); it is a
   data-accumulation concern only. Acceptable for S7's scope; consider a
   cap or TTL when the indexing-jobs feed (higher volume) lands.

3. **[INFO, not S7-scope] `notifyAll` iterates the live `Set` (no snapshot).**
   `primitives/notify.ts:16-23`. If a listener subscribed/unsubscribed *during*
   a notify pass, JS `Set` iteration semantics apply (deletes safe; adds may be
   visited same-pass). TaskList subscribes/unsubs in connected/disconnected
   callbacks, never inside a notify callback, so S7 does not trigger this.
   Shared primitive across 19+ substrates — out of scope, flagged for awareness
   only.

4. **[INFO] Indeterminate progress for agent-op tasks is acceptable.**
   `Shell.ts:831` passes no `progress`; the bar is hidden when progress is
   `undefined` (`TaskList.ts:176`). Correct — no fake progress.

## Live-evidence assessment

The integrated proof (agent `core.index-gc` → task → `failed`) is a **legitimate
end-to-end proof of the agent-op→task wrap**, not a defect mask. It exercises
two of the three wrap branches: (a) task *creation* gated on
`originator === 'agent'`, and (b) the `.catch` → `completeTask('failed')`
completer. The `'failed'` outcome came from the op hitting the S2 trust gate
(428) at the HTTP boundary — i.e., the task system worked correctly and faithfully
reported the backend's rejection. There is a real emitter path
(`VirtualToolDispatcher.ts`, `AgentEmitterDemo.ts` dispatch `jf-invoke-operation`
with `originator:'agent'`), so this is not a hand-synthesized event.

**Gap (minor):** the live integrated proof did NOT exercise the
`.then` → `'succeeded'` branch against a *real backend resolve* — the SUCCEEDED
state in the screenshot (`ss_2974dd6jy`) was produced by direct component-state
injection (the Vite dual-instance pattern noted in the prompt). That is adequate
for *visual* verification of the chip/progress/cancel rendering, and the
`'succeeded'` substrate transition is independently covered by `tasks.test.ts`,
with the Shell `.then` line being the trivially-symmetric counterpart to the
proven `.catch` line. So no claim is unverified, but the strongest possible
proof (a real backend op that *resolves* → task `succeeded`) was not captured.
Recommend capturing one real-resolve agent op in a future live pass when an
allow-listed agent op is available — tracked as a follow-up, not a blocker.

The cancel-button honesty claim holds: agent-op tasks are non-cancellable
(`Shell.ts:831` injects no cancel fn), so `TaskList.ts:163` never renders a
Cancel affordance on an uncancellable backend op. No misleading control.

---

## fwd-slice-8-review

*(consolidated from `543-fwd-slice-8-review.md`)*

### S8 Independent Review — Sequence dry-run (R-E5) + parameterized macros (U4)

Reviewer: independent adversarial pass (did not write the code).
Commit: `5b75980e4` — "S8 — sequence dry-run (R-E5) + parameterized macros (U4)".
Branch/worktree: `worktree-543-forward` @ `F:\JustSearch\.claude\worktrees\543-forward`.

## Verdict

**APPROVE-WITH-FOLLOWUPS**

The slice does what it claims. Both R-E5 (preview, no dispatch) and U4
(parameterized macros via the real elicit substrate) are correctly
implemented, the discriminated-union substitution is sound, persistence
round-trips, the async handler is properly awaited by the Action substrate,
and the tests exercise the real substrates (not mocks). I reproduced
`tsc` clean and `vitest` 12/12 for the macros directory. The follow-ups are
low-severity observations, none blocking.

## Claims verified

| Claim | Status | Evidence |
|---|---|---|
| `previewSequence(effects[])` maps `previewEffect`; no dispatch, no journal append | ✓ | `effects/index.ts:429-435` maps `previewEffect`; `previewEffect` (`:408-421`) reads `_nextId` but does NOT advance it and does NOT push to `_entries`. Test `parameterized.test.ts:34-45` asserts `listJournal()` length 0. |
| `previewMacro(id, vars)` substitutes vars then calls `previewSequence` | ✓ | `macros/index.ts:222-227` → `previewSequence(resolveEffects(macro, vars), CORE_PROVENANCE)`. Test `parameterized.test.ts:90-104` asserts `"Hi Z"` substitution + 0 journal entries. |
| Static effects pass through unchanged for non-parameterized macros | ✓ | `resolveEffects` (`:171-177`) returns `macro.effects` by reference when `!macro.params` — no substitution. |
| `defineMacro` accepts optional `params` (JSON-serializable) | ✓ | `macros/index.ts:34-40` `MacroParams = {title, description?, schema: JsonSchema}` — all JSON; `:111-115` + `:124` conditionally include `params`. |
| `runMacro` is async; parameterized → elicit → substitute → dispatch | ✓ | `macros/index.ts:189-211`. Test `parameterized.test.ts:65-81` drives REAL elicit (`resolveElicit({name:'World'})`) → toast `"Hello World"`. |
| Cancelled prompt (or no chrome) → 0, no dispatch | ✓ | `:204` `if (elicited === null) return 0`. `elicit` returns null on cancel AND headless (`elicit/index.ts:93-98`). Test `parameterized.test.ts:83-89` (`cancelElicit` → 0). |
| Macro Action handler awaits `runMacro` | ✓ | `macros/index.ts:241-245` `handler: async () => { await runMacro(...); return {kind:'noop'}}`. |
| Action substrate handles async handlers | ✓ | `actions/index.ts:535` `const effect = await action.handler(...)`. `macros.test.ts:110-123` invokes via `invokeAndApply` and observes the toast. |
| `substitute` recursive, pure, leaves unknown tokens intact | ✓ | `macros/index.ts:155-169`: string→`replace`, array→`map`, object→new record; primitives returned as-is; unknown key returns `m` (the literal). Builds new containers at every level — does not mutate stored macro. |
| `kind` discriminant not corrupted; `as Effect` cast sound | ✓ | All `kind` values are string literals with no `{{...}}` token (`effect.ts:23-80`), so `substitute` returns them unchanged. Cast is sound for templated string fields. |
| Persistence round-trips with `params` | ✓ | `writePersisted` (`:71-83`) `JSON.stringify` over full Macro (incl. `params`); `restoreMacrosFromStorage` (`:86-104`) `JSON.parse` + re-register. No non-serializable field on `Macro` (no build-fn). |
| `macros.test.ts` runMacro tests made async + awaited, not weakened | ✓ | Diff: assertions unchanged (`count===3`, ordered events, unknown→0); only `async`/`await` added. |
| `parameterized.test.ts` uses REAL elicit substrate | ✓ | Imports `resolveElicit`/`cancelElicit`/`listPendingElicits` from `../elicit/index.js`; no mock. |
| tsc clean; vitest pass | ✓ (reproduced) | `npm run typecheck` clean. `vitest run src/shell-v0/substrates/macros/` → 12/12 (4 parameterized + 8 macros). |

## Findings

1. **[LOW] `operationId` and other non-templated string fields are substituted indiscriminately.**
   `substitute` walks ALL string fields, including `operationId`,
   `resultKey`, `surfaceId`, `selector`, `modalId`, `formId`, `path`. A
   `{{token}}` placed in any of these would be substituted. This is
   arguably a feature (parameterize which op to call) and unknown tokens
   are left intact, so the realistic blast radius is small. But there is
   no allow-list restricting templating to "value" fields. Acceptable for
   this slice; consider documenting which fields are templatable if macros
   become user-authored. No correctness bug today.

2. **[LOW] String-literal-union fields can be corrupted by an author-supplied token.**
   If a macro author puts `{{sev}}` in `severity` (`'info'|'warning'|...`),
   `behavior`, or `block`, substitution can yield an arbitrary string that
   violates the union — and neither `previewSequence` nor `applyEffect`
   re-validates it (`as Effect` is an unchecked cast). This is author
   error, not a defect introduced by S8, and a literal value like `'info'`
   (no token) is never touched. Severity is low because (a) it requires the
   author to template a union field and (b) the runtime effect of a bad
   severity is a cosmetic toast variant, not a crash. Worth a note, not a block.

3. **[INFO] `\{\{(\w+)\}\}` matches word-chars only — no dotted/nested keys.**
   `{{user.name}}` would not be substituted (the `.` breaks the match) and
   would be left intact. Acceptable: the elicited values are a flat
   `Record<string, unknown>` keyed by schema property names, so flat
   `\w+` keys are exactly the right scope. No change needed.

4. **[INFO] All previewed entries share the same `id` (current `_nextId`).**
   `previewSequence` maps `previewEffect`, which returns `_nextId` without
   advancing it — so every entry in a multi-effect preview carries the same
   informational id. The docstring already states the id is "informational
   only", and a consumer showing "this plan would do…" doesn't depend on
   unique ids. Not a defect; noting for any future consumer that keys on id.

5. **[INFO] No regression in `runMacro` callers from the sync→async change.**
   Grep confirms the only non-test caller is the macro Action handler,
   which was updated to `await`. No orphaned fire-and-forget sync caller
   exists.

## Live-evidence assessment

The "no new chrome" claim is **sound**. A parameterized macro's prompt is
rendered by the pre-existing `ElicitHost.ts` (listens for
`jf-elicit-request`, renders `request.schema` via the shared JsonForms
renderer set). S8 adds no macro-specific rendering branch — the macro's
schema flows through the identical elicit path already proven for other
elicit callers. A boot-regression check (shell + `<jf-elicit-host>` present,
no errors) plus an in-process integration test through the real elicit
substrate is adequate for a slice that introduces no new visible surface.

**One genuine test-vs-browser gap (low severity):** the integration test
resolves the prompt by calling `resolveElicit(...)` directly, bypassing the
ElicitHost's JsonForms rendering and form-collection step. So if a macro
author supplied a schema the renderer set doesn't support, the in-process
test would not catch the rendering failure. However: (a) this is a
pre-existing property of *every* elicit caller, not something S8 introduces;
(b) the test's `SCHEMA` (`{type:'object', properties:{name:{type:'string'}}}`)
is a renderer-supported shape; and (c) the macro substrate is not where
schema-rendering correctness belongs. The elicit host's own rendering is
covered by `elicit/index.ts`-adjacent tests. No additional live evidence is
required for S8 closure.

## Why this is not a "passes for the wrong reason" approval

The earlier 97%→65% cut was about confirmation without causation. I checked
the failure modes that would make the tests pass for the wrong reason:
- The no-dispatch assertions key on `listJournal().length === 0`, which is
  the real journal, and `previewEffect` provably neither advances `_nextId`
  nor pushes to `_entries` (read the source, not just the test).
- The substitution test asserts the *substituted output* (`"Hello World"`,
  `"Hi Z"`), not merely that a toast fired — distinguishing real `{{name}}`
  replacement from a pass-through.
- The cancelled-path test uses the real `cancelElicit`, so the `=== null`
  branch is genuinely exercised, not stubbed.
- The async-handler integration goes through `invokeAndApply`, whose
  `await action.handler(...)` I confirmed at the source — proving the
  Promise is awaited before `applyEffect`, not dropped.

---

## fwd-slice-9-review

*(consolidated from `543-fwd-slice-9-review.md`)*

### S9 Independent Review — Unified Agent Activity Panel (U5)

Reviewer: independent adversarial pass (did not write the code).
Commit: 8730e48fa — "feat(543-fwd): S9 — unified agent activity panel (U5)".
Verification run in-worktree: `npx vitest run AgentActivityPanel.test.ts` → 4/4;
`npx tsc --noEmit` → exit 0.

## Verdict

**APPROVE**

The panel is a genuine aggregating consumer of the three existing substrates
(no parallel store), subscribes to and fully unsubscribes from all three, the
toggle Action is a verbatim mirror of the audit-log/agent-emitter pattern and
is registered idempotently against a registry that genuinely throws on
duplicate, and the import-elision fix is correct and applied consistently with
every other casted custom element in Shell.ts. Tests drive the real substrates
(substrate-read path proven), tsc is clean, and the targeted vitest run is
green. No defects that block merge; findings are all LOW / cosmetic.

## Claims verified

- ✓ **Aggregates, does not duplicate state.** `refresh()` reads
  `listPending()`, `listTasks().filter(running)`, `listJournalByOriginator('agent')`
  — no local store. `AgentActivityPanel.ts:88-91` (refresh), reads at
  `:104` (`listPending`), `:105` (`listTasks` filter), `:106` (`listJournalByOriginator`).
- ✓ **Subscribes to all three, unsubscribes all on disconnect (no leak).**
  `connectedCallback` builds `this.unsubs = [subscribePending, subscribeTasks, subscribeJournal]`
  (`AgentActivityPanel.ts:69-78`); `disconnectedCallback` runs
  `this.unsubs.forEach(u => u()); this.unsubs = []` (`:81-85`). Each `subscribe*`
  returns a real `_listeners.delete` closure (`pending-effects/index.ts:170-177`,
  `tasks/index.ts:145-150`, `effects/index.ts:596-603`). Array is fully cleared.
- ✓ **`render()` returns `nothing` when `!open`; `:host(:not([open])){display:none}`.**
  `AgentActivityPanel.ts:206` (`if (!this.open) return nothing;`) and CSS
  `:115-117`. Closed → no `.panel` (test asserts `.panel` is null when closed
  and after close). Open → three `<section>` blocks (`:218-243`).
- ✓ **Accept → `acceptPending(id, applyEffect)`; reject → `rejectPending`.**
  `handleAccept` calls `acceptPending(id, (effect, invokedBy) => applyEffect(effect, invokedBy))`
  (`AgentActivityPanel.ts:166-168`); signature matches
  `acceptPending(id, applyFn: (Effect, Provenance) => void)`
  (`pending-effects/index.ts:113-117`). Reject button →
  `rejectPending(p.id)` (`:181`). Close → `this.open = false` (`:217`).
- ✓ **Toggle Action mirrors show-audit-log, registered idempotently.**
  `core.action.shell.show-agent-activity` handler queries
  `this.shadowRoot.querySelector('jf-agent-activity-panel')` and flips
  `.open` (`Shell.ts:698-715`) — byte-for-byte the same shape as
  `show-audit-log` (`:661-679`) and `show-agent-emitter` (`:680-697`),
  same `try/catch` idempotency. `registerAction` THROWS on a duplicate id
  (`actions/index.ts:217-223`), so the catch is real re-mount protection,
  not an error-swallow.
- ✓ **Import fix is sound.** Shell.ts now has BOTH `import '../components/AgentActivityPanel.js';`
  (side-effect → runs the bottom-of-file `customElements.define`,
  `AgentActivityPanel.ts:246-248`) AND `import type { AgentActivityPanel }`
  (`Shell.ts:49-50`). The type-only import is erased by Vite; the side-effect
  import survives and registers the element. tsc clean confirms the cast still
  type-checks.
- ✓ **No other latent elision among casted elements in Shell.ts.** I enumerated
  all 8 `as <X> | null` casts (Shell.ts:429, 654, 672, 690, 708, 1013, 1490,
  1965 → InspectorPane, CommandPalette, EffectAuditLog, AgentEmitterDemo,
  AgentActivityPanel, BookmarksPopover, AdvisoryInboxDrawer). Every one has a
  matching side-effect import next to its `import type`: InspectorPane (73+102),
  CommandPalette (198+199), EffectAuditLog (59+60), AgentEmitterDemo (70+71),
  BookmarksPopover (81+82), AdvisoryInboxDrawer (87+89), plus the new panel
  (49+50). No latent elision bug present.
- ✓ **Tests use real component + real substrates.** `AgentActivityPanel.test.ts`
  resets the real substrates (`__resetPendingForTest/__resetTasksForTest/__resetJournalForTest`),
  drives them via `proposeEffect / startTask / recordEffect`, then asserts the
  rendered counts. `createElement('jf-agent-activity-panel')` relies on the real
  registration; `void AgentActivityPanel` (`:22`) keeps the side-effect import
  alive. Counts are precise (proposeEffect adds to pending but does NOT journal,
  so `Recent agent actions (1)` reflects exactly the one `recordEffect`).
  Closed→null `.panel`, open→three counts, accept→`listPending()` length 0,
  close→`open===false` + null `.panel`. Real assertions. Ran green 4/4.
- ✓ **`slice(-8).reverse()` correctness.** `_entries.push(entry)` appends newest
  last (`effects/index.ts:331`); `listJournalByOriginator` preserves order
  (`filter`, `:357-361`). `.slice(-RECENT_LIMIT)` = last 8 = most recent;
  `.reverse()` = newest-first. Correct for a "recent" view.

## Findings

1. **LOW / explore-before-implementing nit.** The panel reinlines
   `listTasks().filter((t) => t.status === 'running')` (`AgentActivityPanel.ts:105`)
   while the tasks substrate already exports `listRunningTasks()` doing exactly
   that (`tasks/index.ts:134-136`). Behaviorally identical; using the helper
   would be marginally cleaner. Not a defect.

2. **LOW / cosmetic — z-index overlap with audit log.** The panel is
   `position: fixed; right: 0; z-index: 1001` (`AgentActivityPanel.ts:118-124`).
   The Effect Audit Log is a separate fixed panel toggled by an adjacent Action.
   If both are opened at once they will overlap on the right edge. Each toggle
   is independent (no mutual-exclusion), so a user could open both. Purely
   cosmetic; both are Diagnostics-category developer surfaces. Worth a
   follow-up only if these become user-facing.

3. **LOW / coverage gap (non-blocking).** No unit assertion exercises the
   empty-state strings ("No pending proposals." / "No running tasks." /
   "Nothing yet.") nor the reject button path nor the `slice(-8)` truncation
   (>8 entries). The logic is straightforward and tsc-clean, but a test that
   opens with empty substrates and one that records 9 agent entries (asserting
   only 8 render, newest-first) would harden the most-likely-to-regress paths.
   Recommend as an S11-gate follow-up, not a blocker.

## Live-evidence assessment

**Adequate — the two tiers are complementary and together cover both paths.**

- **Substrate-READ path** is proven by the unit test, not the live screenshot.
  The test drives the real `pending-effects`, `tasks`, and `effects` substrates
  and asserts the panel's rendered counts derive from them. This is the load-
  bearing proof that the panel is an aggregating consumer (the S9 headline
  claim) and it does NOT rely on direct state injection.

- **RENDER path** is what the live screenshot (`ss_0780u2vv3`) proves: the real
  mounted custom element lays out the three sections with rows/buttons in the
  real shell chrome. Per the §28 note, the live check set `panel.pending/tasks/
  recent` directly (the Vite dual-instance hazard means the live browser's
  substrate singletons are a different module instance than a test harness would
  reach), so the live tier proves "the element renders aggregated data
  correctly" but NOT "it reads the production substrate singletons."

- The split is honest and correctly self-described in the commit and live-
  evidence file. The one path neither tier proves end-to-end is *production
  substrate singleton → this exact mounted element instance* (i.e., that the
  panel mounted in the real Shell subscribes to the same module-singleton the
  rest of the app writes to). That is the same Vite dual-instance caveat that
  applies to every substrate-consuming chrome surface in this codebase and is
  not specific to S9; the import-elision fix (independently verified above) is
  what actually gated whether the element registers at all. No additional live
  proof is required to APPROVE this slice.

- The §28 "elided import" lesson is correctly applied: the bug was real
  (type-only named import → Vite drops the `customElements.define` side effect),
  the fix is the canonical side-effect + type-only pair, and it matches every
  other casted element in Shell.ts.

---

## fwd-slice-10-review

*(consolidated from `543-fwd-slice-10-review.md`)*

### 543-fwd Slice 10 (S10) — Independent Adversarial Review

Reviewer: independent (not the S10 implementer). Scope: the two S10
decisions — R-S1 (decline unifying pending/elicit/consent) and R-P3
(treat the acceptPending double-entry as intentional + load-bearing).

Method: read all three substrates verbatim, plus `effects/index.ts`
(`summarizeAgentActivity`, `recordEffect`), `actions/index.ts`
(`applyEffect`, `invokeAndApply`), and both production `acceptPending`
call sites. Verified each claim against source rather than against the
tempdoc prose.

---

## Verdict summary

| Decision | Verdict |
|---|---|
| R-S1 — decline unification | **APPROVE** (skip is justified) |
| R-P3 — double-entry intentional/load-bearing | **APPROVE-WITH-FOLLOWUP** (claim is true, but guarded only by a comment, not a test) |
| **Overall** | **APPROVE-WITH-FOLLOWUPS** |

---

## R-S1 — decline unification: SOUND

I tried to break each of the three pillars by sketching the unification
the agent declined. All three hold.

### Pillar (a) — "consent is dominated by a persistent synchronous lookup, not a request" — HOLDS

Verified against `consent/index.ts`. The substrate's primary API surface
is a **synchronous keyed store**, not a request/await flow:

- `checkCapability` (`consent/index.ts:141`) and `isAllowed`
  (`consent/index.ts:160`) are synchronous reads against the in-memory
  `_consents` map. No prompt, no Promise, no event dispatch.
- `requestCapability` (`consent/index.ts:193`) — the only request-shaped
  entry point — is explicitly documented as the *undecided-only* path:
  the gate caller invokes it "When the answer is 'undecided'"
  (`consent/index.ts:14-16`).
- `recordConsent` → `writePersisted` → localStorage
  (`consent/index.ts:121-135`, `:80-94`) means the dominant lifecycle is a
  **persistent grant re-read indefinitely**, not a one-shot decision.

An "Interaction = request → decide → resolve" abstraction does not model
the consent substrate's actual job (a policy store queried synchronously
on the hot path). Folding consent in would force a persistent grant store
+ a synchronous allow/deny lookup onto pending and elicit, neither of
which has any such concept. That is the definition of a leaky abstraction.
Pillar holds.

### Pillar (b) — "PendingEffect is event-driven + journal-integrated; elicit/consent are Promise-return + journal-free" — HOLDS

Verified:

- **PendingEffect** is event-driven: `proposeEffect` returns an id and
  fires `notify({kind:'proposed'})` (`pending-effects/index.ts:100`);
  resolution is out-of-band via `acceptPending`/`rejectPending` driven by
  chrome (`PendingEffectQueue.ts:224`, `AgentActivityPanel.ts:165`). It is
  journal-integrated: `acceptPending` calls `recordEffect`
  (`pending-effects/index.ts:140`) and `rejectPending` likewise
  (`:159`). Multiple proposals coexist (the `_pending` Map,
  `pending-effects/index.ts:56`) and the review-queue UI renders all of
  them at once.
- **elicit** is Promise-return, journal-free: `elicit` returns
  `new Promise` awaited mid-handler (`elicit/index.ts:72-100`); no
  `recordEffect` import anywhere in the file.
- **consent** is Promise-return + synchronous lookup, journal-free:
  `requestCapability` returns a Promise (`consent/index.ts:207`); no
  journal import.

A single unified abstraction must pick **one** resolution model. Promise
return breaks the queue's "N coexisting out-of-band proposals reviewed
together" model (you cannot `await` N proposals a user will resolve in
arbitrary order and quantity). Event return breaks elicit/consent's
ergonomic `const v = await ctx.elicit(...)` mid-handler usage
(`actions/index.ts:63`, the KernelCtx contract). The two models are
genuinely incompatible at the call site, not just stylistically
different. Pillar holds.

### Pillar (c) — "decision types are binary / arbitrary / 3-way-enum → unified resolve typed unknown" — HOLDS

Verified the three decision types are structurally unrelated:

- PendingEffect: binary accept/reject, payload is a pre-determined
  `Effect` (`PendingEvent` union, `pending-effects/index.ts:60-67`).
- elicit: arbitrary structured form value, `Promise<unknown | null>`
  (`elicit/index.ts:72`) — the form value is already `unknown` by nature.
- consent: a closed 3-way enum `ConsentDecision`
  (`consent/index.ts:31`).

A unified `resolve(decision)` would have to type `decision` as the union
of `Effect | unknown | ConsentDecision`, which collapses to `unknown` and
erases the per-consumer compile-time safety each substrate currently
enjoys. Pillar holds.

### Could a *partial* unification still be worthwhile? — No.

The only genuinely shared seam is "dispatch a request to a chrome host
via a bubbling `document` CustomEvent, hold a pending map keyed by id,
resolve when chrome calls back." That seam is real (elicit `_pending` +
`jf-elicit-request`; consent `_pending` + `jf-consent-request`). But:

1. PendingEffect does **not** share even this seam — it is event/listener,
   not request/Promise, and its resolution writes the journal. So a
   "unify all three" is off the table; at most elicit+consent share a
   request/Promise shell.
2. The elicit/consent shared shell is ~15 lines each of boilerplate
   (`_nextId`, a `_pending` map, a dispatch-or-resolve-null guard). The
   §32 `dispatchDomEvent` helper (`actions/index.ts:352`) already factors
   the *dispatch* half. Extracting the remaining `_pending`-map shell into
   a generic `createRequestHost<TReq,TRes>()` would save a few lines but
   would have to be parameterized over: the default headless resolution
   value (elicit → `null`, consent → `'deny'`), the side-effect on resolve
   (consent calls `recordConsent`, elicit does not), and the request/
   response types. The abstraction's surface is nearly as large as the two
   concrete implementations. This is a YAGNI-negative refactor: it adds an
   indirection layer that future readers must learn, to deduplicate ~25
   lines that read clearly today.

The agent's decision is consistent with the §31.2 / W11 registry
over-consolidation refusal it cites. **R-S1 skip is justified — APPROVE.**
This is not a lazy rationalization; the agent read the source and the
divergences it names are real and load-bearing.

---

## R-P3 — double-entry is intentional + load-bearing: CLAIM TRUE, GUARD INSUFFICIENT

### (i) Does `acceptPending` write exactly two entries with the claimed attribution? — CONFIRMED

Traced both production call sites:

- `PendingEffectQueue.handleAccept` (`PendingEffectQueue.ts:224-227`):
  `acceptPending(id, (effect, invokedBy) => { applyEffect(effect, invokedBy); })`
- `AgentActivityPanel.handleAccept` (`AgentActivityPanel.ts:165-167`):
  `acceptPending(id, (effect, invokedBy) => applyEffect(effect, invokedBy))`

**Both omit the third `originator` argument to `applyEffect`.**
`applyEffect`'s signature defaults `originator = 'user'`
(`actions/index.ts:361-365`), and that default flows into the
`recordEffect(effect, invokedBy, { originator })` call at
`actions/index.ts:371`. So:

- **Entry A** (the dispatch entry, written inside `applyEffect`):
  `originator = 'user'`, no `pendingOutcome`.
- **Entry B** (the marker, written by `acceptPending` itself at
  `pending-effects/index.ts:140-144`): `originator = pending.originator`
  (which is `'agent'` for agent proposals — default at
  `pending-effects/index.ts:86`, confirmed by the test at
  `pending-effects.test.ts:126-129`), with `pendingOutcome: 'accepted'`.

Two distinct entries, attributed exactly as the agent claims. Confirmed.

### (ii) Would re-attributing the dispatch entry to 'agent' double-count? — CONFIRMED LOAD-BEARING

`summarizeAgentActivity` (`effects/index.ts:383-396`) counts an entry iff
`e.originator === 'agent'` and `pendingOutcome !== 'rejected'`. Today, for
an accepted agent pending, only Entry B matches (`'agent'` + `'accepted'`)
→ **counted once**. Entry A is `'user'` → skipped.

If a future "dedup" re-attributed Entry A to the pending's originator
(`'agent'`), then **both** A and B would match the `'agent'` filter →
the action would be **counted twice** in the "What the AI did" digest. The
claim is mechanically correct: the split attribution is load-bearing for
digest correctness.

### The gap: guarded by a comment, not a test

This is where the slice falls short of clean. The load-bearing invariant
is documented in a regression comment (`pending-effects/index.ts:131-138`)
— good — but **no test exercises the actual double-entry path**:

- `pending-effects.test.ts:50-68` ("acceptPending dispatches AND records
  a pendingOutcome=accepted entry") passes a bare `vi.fn()` as `applyFn`,
  so `applyEffect` never runs and **only one** journal entry is written
  (`expect(listJournal()).toHaveLength(1)`, `:67`). This test does not see
  the double entry at all.
- `agentDigest.test.ts:19-63` exercises `summarizeAgentActivity` using raw
  `recordEffect` calls with hand-set originators — it never goes through
  `acceptPending` + the production `applyEffect` applyFn. So it asserts the
  *counting rule* but not the *integration* that the counting rule depends
  on.

The exact scenario the R-P3 comment protects — "accept an **agent**
pending through the **real** `applyEffect` applyFn, then assert
`summarizeAgentActivity().total === 1` (not 2)" — is **not asserted
anywhere**. A future agent could re-attribute the dispatch entry to
`'agent'` (the comment's forbidden "fix") and the entire suite would stay
green, because:

- `pending-effects.test.ts:60` asserts only Entry B's originator
  (`entry!.originator === 'agent'`), not Entry A's, and uses a mock applyFn
  so Entry A never exists in that test.
- No test threads the real `applyEffect` into `acceptPending` and counts
  agent entries.

Per this repo's `audit-without-test` discipline (CLAUDE.md: "Audit-driven
fixes need a runnable test, not just a passing audit"), a comment is a
hypothesis; the test is truth. The R-P3 *analysis* is correct, but the
*guard* is prose-tier, which is ~70% adherence — exactly the kind of
silent-regression surface the comment itself warns about.

### Verdict on R-P3: APPROVE-WITH-FOLLOWUP

The claim is true and the double-entry is genuinely load-bearing — the
agent is **not** dodging a real bug; the "no behavioral change" call is
correct. But the slice should not close on a comment alone for a
load-bearing invariant.

**Required follow-up (small, ~10 lines):** add a regression test in
`pending-effects.test.ts` (or `agentDigest.test.ts`) that:

1. `proposeEffect({kind:'toast',...}, CORE_PROVENANCE, 'agent')`
2. `acceptPending(id, (e, p) => applyEffect(e, p))` — the **real**
   production applyFn, not a mock
3. asserts `listJournal()` has length 2 with originators `['user','agent']`
4. asserts `summarizeAgentActivity(0).total === 1`

That test fails the moment someone re-attributes the dispatch entry —
converting the comment's intent into an enforced gate.

---

## Final verdict: APPROVE-WITH-FOLLOWUPS

- **R-S1 (decline unification): APPROVE.** Verified all three pillars
  against source; each holds. The substrates diverge on resolution model,
  journal integration, decision type, and persistence. A unification — full
  or partial — is a net-negative leaky abstraction. The skip is a sound
  architectural judgment, not a lazy out.
- **R-P3 (double-entry intentional): APPROVE-WITH-FOLLOWUP.** The double
  entry is real, the attribution is exactly as claimed
  (`applyEffect` default `'user'` vs. marker `'agent'`), and it is
  load-bearing for `summarizeAgentActivity`. No behavioral change is
  correct. **But** the invariant is guarded only by a comment; the existing
  tests use a mock applyFn and never exercise the two-entry integration.
  Add the regression test above before treating R-P3 as closed.

No BLOCK: neither decision is wrong on the merits. The single actionable
defect is a missing test, not a bad call.

---

## fwd-followup-1-review

*(consolidated from `543-fwd-followup-1-review.md`)*

### 543 §32 #1 — indexing-jobs → Task bridge: independent review

**Reviewer role:** independent (not the implementer).
**Scope:** single-module FE review of the new `indexingJobsBridge` projecting the
backend `core.indexing-jobs` TABULAR Resource SSE stream into the Task substrate.
**Date:** 2026-05-25.

## Verdict: APPROVE

The bridge is correct, the read-only invariant holds, lifecycle/resource handling
is sound, the reducer reuse matches the real wire shape, and the tests exercise the
full lifecycle (not just happy path). All 7 unit tests green. No blocking findings.
One optional, non-blocking observation recorded below.

---

## 1. Projection correctness — VERIFIED

`projectJobsToTasks` (`indexingJobsBridge.ts:61-86`) is called on **every** stream
snapshot with the reducer's full current `items` map (`indexingJobsBridge.ts:114`).
This "reconcile the full set each frame" design is what makes the lifecycle correct:

- **present, non-FAILED → running task.** `getTask(id) === undefined` gate
  (`:69`) means `startTask` fires once per id; subsequent frames for the same
  still-running job no-op. Mapping to `running` is implicit (startTask sets
  `status:'running'`, `tasks/index.ts:73`). ✔
- **present, FAILED → failed.** `completeTask(id,'failed')` (`:73-75`). ✔
- **job leaves the live set → succeeded.** The `tracked`-vs-`present` diff
  (`:80-85`) completes any tracked id no longer in the map. ✔ This works
  regardless of *how* the job left: Delete delta removes it from the reducer map;
  a later snapshot / SnapshotReplaced that omits it also drops it from the map.
  Either way the bridge sees the full map without it and completes it.
- **terminal-guard reliance is correct.** A cleared FAILED job: `completeTask(id,
  'succeeded')` is called at `:82`, but `tasks/index.ts:99` early-returns because
  the task is no longer `running` (`t.status !== 'running'`). The task stays
  `failed`. ✔ This is the load-bearing interaction and it is exercised by a test
  (see §5).

**No double-task bug.** The `getTask(id) === undefined` guard (`:69`) prevents a
second `startTask` across repeated snapshots; verified by the "running→FAILED, no
duplicate" test (`:71-83`, asserts `toHaveLength(1)`).

**No leaked-tracking bug.** `tracked.delete(id)` (`:83`) fires in the same branch
that completes a departed job, so the set doesn't grow unboundedly across
snapshots. A job that fails (and is still present) stays in `tracked` until it
leaves the set — correct, because while present it must still be reconciled.
Separately, the Task substrate caps retained tasks at 50 and prunes finished ones
(`tasks/index.ts:46-54`), so even a never-departing failed task can't leak memory
on the substrate side.

## 2. Read-only invariant — VERIFIED

`startTask({ id, label })` is called with **no `cancel` field**
(`indexingJobsBridge.ts:70`). `startTask` sets `cancellable: typeof opts.cancel
=== 'function'` (`tasks/index.ts:76`), so job-tasks land with `cancellable:false`
and register no cancel fn in `_cancelFns`. `cancelTask` early-returns for a
non-running task and, even while running, there's no registered fn to invoke. The
tray genuinely cannot duplicate the Resource view's CANCEL_OP/RETRY_OP control.
Explicitly asserted by the test at `:36` (`expect(t?.cancellable).toBe(false)`). ✔

## 3. Resource / lifecycle — VERIFIED

- **stop() closes the EventSource.** The returned stop fn (`:117-120`) calls
  `unsub()` then `stream.stop()`; `EnvelopeStream.stop()` removes listeners and
  calls `es.close()` (`EnvelopeStream.ts:110-124`). The fake-EventSource test
  asserts `es.closed === true` after `stop()` (`indexingJobsBridge.test.ts:146`). ✔
- **Shell boot/teardown symmetry.** Started in `connectedCallback` and the handle
  stored (`Shell.ts:639`); torn down in `disconnectedCallback` with
  `this.indexingJobsBridgeStop?.(); this.indexingJobsBridgeStop = null;`
  (`Shell.ts:1342-1343`). The null-out after stop means a remount (connected →
  disconnected → connected) starts a fresh bridge and the old one is closed first
  — no EventSource leak across remounts. ✔
- **SSR/headless guard is correct.** `if (!opts.eventSourceFactory && typeof
  EventSource === 'undefined') return () => {}` (`:100-102`). The guard is checked
  *before* any `new EnvelopeStream`/`EventSource` construction, so no crash when
  the global is absent; the returned no-op stop fn is safe to call. Asserted by
  the headless test (`:149-160`). ✔ The guard correctly still allows an injected
  factory in a headless environment (test path), which is the right precedence.

## 4. Reducer reuse — VERIFIED against the real wire shape

`tabularStrategy('pathHash')` is reused (`indexingJobsBridge.ts:103`). Cross-checked
against the three authorities:

- **Snapshot.** Backend delivers the initial snapshot as a LIFECYCLE frame with
  `{kind:'snapshot', items:[...], snapshotSeq}` — confirmed in
  `IndexingJobsStreamController.java:72-73` (`extras.put("items", ...)`,
  `extras.put("snapshotSeq", ...)`) and the integration test
  (`IndexingJobsSubstrateIntegrationTest.java:164` asserts `"kind":"snapshot"`
  carrying `items`). The reducer's LIFECYCLE branch reads exactly
  `p.kind === 'snapshot' && Array.isArray(p.items)` (`subscriptionStrategy.ts:243-251`). ✔
- **Deltas.** `IndexingJobsChangeRegistry.DeltaEnvelope`
  (`IndexingJobsChangeRegistry.java:158-198`) emits
  `{kind:'insert'|'update', row}`, `{kind:'delete', primaryKeyValue}`,
  `{kind:'snapshot-replaced', items}` inside UPDATE frames. The reducer's UPDATE
  branch dispatches on exactly these `kind` values (`subscriptionStrategy.ts:255-296`),
  including the generic `primaryKeyValue` for delete. ✔
- **Primary key.** `pathHash` matches `IndexingJobView`'s first field and the
  Resource's primaryKey; the reducer keys the map by it. ✔

The bridge's `IndexingJobRow` interface (`pathHash`, `state`, `collection`) is a
strict subset of `IndexingJobView`'s fields — it reads only what it projects and
correctly does NOT depend on a `progress` field (none exists on the wire;
job-tasks are indeterminate-progress, consistent with `Task.progress` being
optional). ✔

## 5. Tests — VERIFIED (lifecycle, not just happy path)

The projection-level suite covers all five lifecycle transitions named in the
review brief:
- present non-FAILED → running + read-only (`:28-38`)
- FAILED → failed (`:40-47`)
- leaves set → succeeded + `tracked` cleared (`:49-59`)
- **FAILED-then-cleared stays failed (terminal guard)** (`:61-69`) — the
  load-bearing case
- **running→FAILED → failed, no duplicate task** (`:71-83`)

The stream-level suite (`:107-160`) exercises the real
SSE-frame → `tabularStrategy` reducer → projection chain via a fake EventSource:
a LIFECYCLE snapshot frame (mixed PENDING + FAILED) then an UPDATE delete frame,
asserting the running task flips to succeeded — i.e. it verifies the reducer wiring
and URL construction (`http://127.0.0.1:55393/api/indexing-jobs/stream`, `:116`),
not just the pure projection fn. Plus the headless no-op path (`:149-160`). All 7
pass.

**Coverage is sufficient.** No missing case among the brief's enumerated risks.

## Non-blocking observations

1. **`completeTask(id,'succeeded')` for a job that departs while still PENDING.**
   If the backend drops a job from the live set without it having finished (e.g. a
   reconnect SnapshotReplaced that omits a job that was genuinely still pending, or
   an operator clearing the queue), the bridge marks the task `succeeded` rather
   than, say, `cancelled`. Given the read-only/observational scope and that the
   tray is a glanceable indicator (the authoritative state lives in the Resource
   view), "succeeded" is a defensible default and matches the documented mapping
   (`indexingJobsBridge.ts:16-19`). Not a defect; noting it so the semantic is a
   conscious choice on record. No action required.

2. The stream-level test uses `g7h8i9j0`-style "hex" that includes non-hex letters;
   harmless (the bridge only `.slice(0,6)`s it for the label and never validates
   hex). Cosmetic, test-only. No action.

## Closure

`independent-reviewer-required` satisfied: this review was performed by an agent
other than the implementer. Verdict is **APPROVE** with no follow-up slices
required.

---

## fwd-followup-2-review

*(consolidated from `543-fwd-followup-2-review.md`)*

### 543-fwd Follow-up #2 — Independent Review (commit `0d646121d`)

**Reviewer:** independent (not the implementer). **Date:** 2026-05-25.
**Scope:** Autonomy Dial watch/assist/auto made distinct via effect-kind-keyed
disposition. Safety claim under audit: *"Lowering oversight via this dial NEVER
lets a destructive agent op auto-fire."*

## Verdict: APPROVE

The load-bearing safety claim is **TRUE**, verified end-to-end against source
with a regression guard on every link of the chain.

## The BUTTON×HIGH safety question — YES, it is still gated

**An accepted destructive (HIGH) agent proposal does NOT silently complete.**
It re-dispatches as `originator='user'` → `BUTTON` → `SourceTier.TRUSTED`, and
`TRUSTED × HIGH = TYPED_CONFIRM`, which throws `ConfirmationRequiredException`
when no confirmation token is present (the accept path carries none). LOW/MEDIUM
accepted ops auto-complete (TRUSTED×LOW=AUTO, TRUSTED×MEDIUM=AUTO) — the user's
click is the confirmation, exactly as the commit claims. If BUTTON×HIGH had been
AUTO the claim would be false; it is not.

## Verification chain (file:line)

### FE: accept → re-dispatch as 'user'/BUTTON
1. `applyEffect(effect, invokedBy, originator='user')` — default originator is
   `'user'`. `modules/ui-web/src/shell-v0/substrates/actions/index.ts:361-365`.
   The `invoke-operation` case stamps `originator` into the `jf-invoke-operation`
   detail. `actions/index.ts:392-405`.
2. `acceptPending`'s `applyFn` signature is `(effect, invokedBy) => void` — **no
   originator parameter** — so any production wiring necessarily uses
   applyEffect's `'user'` default. The split-entry comment (lines 131-138)
   documents this as load-bearing and warns against "dedup."
   `modules/ui-web/src/shell-v0/substrates/pending-effects/index.ts:113-146`.
3. Both production accept callsites call `applyEffect(effect, invokedBy)` (two
   args → `'user'`): `components/PendingEffectQueue.ts:225-227` and
   `components/AgentActivityPanel.ts:166`.
4. Shell listener maps `detail.originator` → transport via `originatorToTransport`
   and sends it as `X-JustSearch-Transport`. `chrome/Shell.ts:844-854`;
   `operations/OperationClient.ts:148-153`.
5. `originatorToTransport`: `'user'`/absent → `BUTTON`; `'agent'` → `AGENT_LOOP`;
   `'system'` → `SYSTEM_INTERNAL`. `operations/originatorTransport.ts:20-29`.

### Backend: BUTTON → TRUSTED → TYPED_CONFIRM
6. Header → provenance; malformed/absent → BUTTON (cannot escalate privileges,
   per the method's own contract). `OperationsController.java:179-192`.
7. `BUTTON` → `SourceTier.TRUSTED` in the intent-source catalog.
   `CoreIntentSourceCatalog.java:89-95`. (`AGENT_LOOP` → `UNTRUSTED`, lines 132-138.)
8. Lattice `evaluate`: **TRUSTED×LOW=AUTO, TRUSTED×MEDIUM=AUTO, TRUSTED×HIGH=
   TYPED_CONFIRM**. `CoreTrustEvaluator.java:71-86` (matrix Javadoc lines 17-23).
9. Enforcement: `enforceTrustLattice` calls `evaluate(deriveSourceTier(prov),
   op.policy().risk())`; on INLINE/TYPED_CONFIRM without a token → throws
   `ConfirmationRequiredException`. `OperationExecutorImpl.java:465-484`.
   `deriveSourceTier` defaults unregistered transports to **UNTRUSTED** (strictest)
   — fail-safe. Lines 486-494.
10. Risk is the operation's own declaration (`op.policy().risk()`), never the FE.
    `RiskTier`: READ_ONLY→LOW, WRITE→MEDIUM, **DESTRUCTIVE→HIGH**.
    `RiskTier.java:6-16`. Real destructive ops declare HIGH (e.g.
    `ApplyExcludesHandler`, `CoreOperationCatalog`) — the claim is not vacuous.

### Disposition logic + gate wiring
- `agentInvocationDisposition(effectKind, level)`: watch→propose all; auto→
  dispatch all; assist→propose iff `effectKind==='invoke-operation'` else
  dispatch. `substrates/autonomy/index.ts:90-101` (post-commit).
- `invokeAndApply` gates only `originator==='agent'` and proposes when
  disposition returns `'propose'`; otherwise dispatches with the agent
  originator. `substrates/actions/index.ts:543-557`. User-originated invocations
  skip the gate entirely (line 544) — the dial only gates agents.

### Tests assert the right things
- Disposition table (effect-kind × level), incl. assist+invoke-operation→propose
  and auto+invoke-operation→dispatch: `autonomy.test.ts:35-52`.
- Real-substrate integration: assist+backend-op→proposed (`autonomy-gate.test.ts:63-70`),
  auto+backend-op→dispatched (72-77), assist+toast→dispatched (56-61),
  watch+everything→proposed, watch+user→dispatched (86-91).
- Backend regression guards: BUTTON→TRUSTED (`CoreIntentSourceCatalogTest.java:96-98`);
  TRUSTED×HIGH=TYPED_CONFIRM (`CoreTrustEvaluatorTest.java:36`).
- Re-ran the three FE suites locally: **16/16 green**.

## Follow-ups (non-blocking)

- **Minor test-coverage gap (not a defect):** No single FE test exercises the
  full *accept → re-dispatch → originator='user' → BUTTON* chain in one flow.
  Each segment is independently covered (originatorTransport user→BUTTON; the
  applyEffect default is structural; the two-arg accept callsites are
  source-verified), and the backend chain is guarded by the two Java tests
  above. An optional integration test that proposes an agent invoke-operation,
  calls `acceptPending(id, (e,p)=>applyEffect(e,p))`, and asserts the
  `jf-invoke-operation` detail carries `originator:'user'` would close this to a
  single regression guard. Recommend logging, not blocking.

## Why APPROVE and not APPROVE-WITH-FOLLOWUPS

The only finding is an additive test that would consolidate already-covered
segments into one guard. No correctness, safety, or wiring defect was found.
The implementation, the dial copy (`AutonomyDial.ts` HINTS), and the module-header
safety invariant all match the verified behavior.

---

## fwd-followup-3-review

*(consolidated from `543-fwd-followup-3-review.md`)*

### 543-fwd Follow-up 3 — Independent Review (Fix 1 + Fix 2B)

Reviewer: independent (not the implementer). Reviewed the uncommitted working
tree against source. Verification tiers run: `npm run typecheck` (clean),
`vitest run` on the three affected suites (24 tests pass), plus source-verbatim
checks of the FE→backend safety chain.

## Verdict: APPROVE-WITH-FOLLOWUPS

Both headline properties hold against source:
- **(a) Fix 1's reset/remount logic holds.** The stateless reconcile is correct.
- **(b) Fix 2B's token-only-on-user-confirm safety property is real.** Verified
  end-to-end through the live `jf-invoke-operation` event detail and the backend
  gate.

The follow-ups are a stale docstring (documentation defect, not logic) and one
defensive-depth note. Neither blocks merge.

---

## FIX 1 — indexing-jobs → Task bridge (stateless reconcile + `removeTask`)

`projectJobsToTasks` (`indexingJobsBridge.ts:74-100`) derives the existing
job-task set from `listTasks()` filtered by the `idxjob:` prefix each call — no
closure Set. Verified each sub-claim:

1. **Reset bug fixed.** An empty-items frame removes every job-task via the
   departure loop (`indexingJobsBridge.ts:95-99` → `removeTask`). A subsequent
   frame re-adds a still-running job through the `getTask(id) === undefined`
   branch (`:88-90`) as `'running'`. No terminal-state lock can strand it,
   because `removeTask` (`tasks/index.ts:134-139`) deletes outright rather than
   transitioning to a guarded terminal status. Covered by test 1A
   (`indexingJobsBridge.test.ts:69-75`).

2. **Remount fixed.** Truth is read from the substrate, so a fresh bridge
   instance reconciles against pre-existing `_tasks`. Test 1B
   (`:80-88`) starts an `idxjob:h7` task directly (simulating a prior instance)
   and a fresh projection with empty items still removes it — proving no
   reliance on per-instance closure state.

3. **`idxjob:` prefix scoping correct.** The departure loop guards on
   `t.id.startsWith(TASK_ID_PREFIX)` (`:96`). Agent-op tasks use `task-N`
   (`tasks/index.ts:70`, auto-id) or arbitrary labels and are never removed.
   Covered by `:90-96` (a `task-agent-1` task survives an empty frame).

4. **`removeTask` correct.** Deletes from `_tasks` and `_cancelFns`, notifies,
   and no-ops on an absent id (`tasks/index.ts:134-139`). Unlike `completeTask`
   it is status-agnostic — exactly what a "vanish on departure" mirror needs.

5. **FAILED handling correct + idempotent.** A present FAILED job creates a
   single `'failed'` task; the `getTask(id)?.status !== 'failed'` guard
   (`:84`) makes repeat frames no-ops (test `:50-57`). A running→FAILED
   transition produces no duplicate (test `:59-64`: `startTask` overwrites to
   running, `completeTask` transitions to failed — single task id). Departure
   removes it (`:55-56`).

### Fix 1 follow-ups (non-blocking)

- **F1.1 — stale module docstring.** `indexingJobsBridge.ts:14-19` still
  documents the OLD behavior ("job removed from the live set → the Task
  completes as 'succeeded' ... a previously-FAILED task stays failed"). The
  actual code removes (vanishes) on departure, and the larger docstring at
  `:56-100` describes the new behavior correctly. The `:14-19` block directly
  contradicts the shipped logic and should be updated to avoid misleading a
  future reader. Documentation defect, not a logic bug.

- **F1.2 — defensive-depth note (edge case the tests don't cover).** If a
  FAILED job-task is present and the SAME `pathHash` reappears in a later frame
  as a non-FAILED state *without an intervening departure frame* (i.e. the live
  set keeps the row but flips state FAILED→PENDING in place), `projectJobsToTasks`
  takes neither branch: the FAILED branch's `getTask(id)?.status !== 'failed'`
  is false, and the `else if (getTask(id) === undefined)` is also false (the
  failed task still exists), so the task stays `'failed'`. In practice the
  indexing-jobs stream models a retry as delete+insert (the test at
  `startIndexingJobsBridge:152-172` exercises delete→re-add, which works), so
  this in-place FAILED→running flip is unlikely. Worth a one-line test or an
  explicit comment if the wire contract ever permits in-place state revival.
  Out-of-scope to fix now.

---

## FIX 2B — typed-confirm in queue accept for HIGH-risk ops

Traced the full chain; the safety model is sound.

1. **SAFETY: token added ONLY on the user typed-confirm path.** Confirmed.
   - The token is injected exclusively in `acceptPendingWithToken`
     (`acceptPendingEffect.ts:40-48`), and only when a `token` arg is supplied
     AND `effect.kind === 'invoke-operation'`.
   - `handleAccept` (`PendingEffectQueue.ts:302-313`,
     `AgentActivityPanel.ts:200-210`) calls `acceptPendingWithToken(id)` with
     **no** token for LOW/MEDIUM/non-op effects. A token is only passed by
     `confirmAccept(id, opId)` (`PendingEffectQueue.ts:315-319`,
     `AgentActivityPanel.ts:212-216`), reachable solely via the Confirm button.
   - An agent's own dispatch never goes through this helper: `invokeAndApply`
     proposes the bare effect (`actions/index.ts:553`, no token) on the
     `originator==='agent'` propose path, and direct agent dispatch calls
     `applyEffect(effect, …)` whose `invoke-operation` arm only forwards a token
     when `effect.confirmationToken !== undefined` (`actions/index.ts:407-409`).
   - **Backstop verified at the backend:** `OperationExecutorImpl:473-480` —
     for `INLINE_CONFIRM`/`TYPED_CONFIRM`, an empty/blank token throws
     `ConfirmationRequiredException` (the 428). A HIGH agent op
     (transport `AGENT_LOOP` → `UNTRUSTED` → `TYPED_CONFIRM`) with no token
     still 428s. The FE token is purely additive on the user-approved path.

2. **Typed-confirm genuinely required for HIGH (not bypassable).** The Confirm
   button is `?disabled=${!matches}` where `matches = this.typedConfirm === opId`
   (`PendingEffectQueue.ts:273,292`; `AgentActivityPanel.ts:227,243`). The first
   Accept click only enters the confirm step (`handleAccept` returns early,
   `:307-311`/`:204-208`) — nothing dispatches. Test
   `PendingEffectQueue.test.ts:200-242` asserts: first click dispatches nothing
   (`events` length 0, Confirm disabled), and only after typing the exact op id
   does Confirm enable and dispatch the event carrying
   `confirmationToken === 'core.file-operations'`. This asserts the real
   `jf-invoke-operation` detail, exercising the whole applyEffect chain, not
   just the helper.

3. **`needsTypedConfirm` keys correctly.** Returns
   `operationRisk(effect) === 'HIGH'` (`acceptPendingEffect.ts:31-33`);
   `operationRisk` returns `getOperation(id)?.policy?.risk` for
   `invoke-operation` effects, else `undefined` (`:24-28`). Non-operation
   effects and unknown ops → `undefined` → `needsTypedConfirm` false → accept
   directly. No regression for LOW/MEDIUM/navigate/etc. Test
   `:174-196` confirms MEDIUM accepts in one click with `confirmationToken`
   undefined.

4. **`getOperation` is a safe synchronous lookup.** `Map.get`
   (`OperationCatalogClient.ts:141-143`), returns `undefined` for unknown ids.
   No async/null hazard at accept time; the optional-chaining in `operationRisk`
   covers a missing entry.

5. **Shared helper avoids drift and the placement rationale is correct.**
   Both components import `needsTypedConfirm` + `acceptPendingWithToken` and
   differ only in rendering — the accept logic is single-sourced. The helper
   lives in `components/` (not `pending-effects/`) because it imports both
   `acceptPending` (pending-effects) and `applyEffect` (actions), and
   `actions` already imports `proposeEffect` from `pending-effects`; placing
   the helper in `pending-effects/` would create
   `pending-effects → actions → pending-effects` cycle. The docstring at
   `acceptPendingEffect.ts:13-16` states this; verified the import direction is
   real (`actions/index.ts:42-46` imports from `../pending-effects/index.js`).

### Fix 2B threading verified
- `effect.ts:37` adds optional `confirmationToken`.
- `actions/index.ts:407-409` forwards it into the `jf-invoke-operation` detail
  only when present.
- `Shell.ts:847,867-869` forwards it into `OperationClient.invoke`.
- `OperationClient.ts:144` serializes it into the request body.
- User-confirm path resolves to `originator='user'` (applyEffect default,
  `actions/index.ts:364`) → `originatorToTransport('user') === 'BUTTON'`
  (`originatorTransport.ts:26-27`) + token forwarded.

### Fix 2B follow-ups
None required. (Optional defense-in-depth: `confirmAccept` passes the token
unconditionally and relies on the disabled-button guard to prevent a
mismatched confirm; an internal re-check of `token === opId` inside
`confirmAccept` would be belt-and-suspenders, but the button gate is the
established `ActionButton` pattern and is adequately tested.)

---

## Tier results

- `npm run typecheck`: clean.
- `vitest run` (indexingJobsBridge, PendingEffectQueue, AgentActivityPanel):
  3 files / 24 tests pass.
- Backend gate + transport mapping read at source (not assumed).

The one actionable item before/with merge is **F1.1** (stale docstring at
`indexingJobsBridge.ts:14-19`). It is documentation-only; the logic and tests
are correct.

---

## fwd-followup-4-review

*(consolidated from `543-fwd-followup-4-review.md`)*

### 543-fwd Followup-4 — Independent Review: Autonomy dial drives agent auto-approval

**Reviewer role:** independent (not the implementer).
**Scope:** §32 autonomy dial now drives the agent surface's real per-tool
auto-approval flow, replacing the old binary "Auto-approve low-risk" checkbox.

## Verdict: APPROVE-WITH-FOLLOWUPS

The change is correct, the safety floor holds, and all checks are green. One
non-blocking documentation gap (default-behavior shift) should be recorded.

## Safety floor (the headline question): YES — HIGH is NEVER auto-approved

`agentToolAutoApprove` checks `risk === 'HIGH'` and returns `false` **before any
level check** (`autonomy/index.ts:141`). No level — including `auto` — can reach
a `true` for HIGH. Confirmed three ways:

1. Source: the HIGH guard is the first statement; `watch`/`auto`/`assist`
   branches are all downstream of it (`index.ts:141-144`).
2. Unit test `autonomy.test.ts`: `agentToolAutoApprove('HIGH', 'auto') === false`
   (plus watch/assist HIGH = false).
3. Controller test `AgentSessionController.test.ts`: `auto` + HIGH leaves the
   fetch-call count unchanged (HIGH stays manual) while MEDIUM is approved.

The controller's auto-approve branch (`AgentSessionController.ts:235`) is the
ONLY behavioral change in `onToolCallPending`. Manual Approve/Reject is
untouched: `tool-call-approve`/`tool-call-reject` listeners →
`approveCall`/`rejectCall` (`AgentSessionController.ts:630,643`,
event wiring `:491,:494`) are unchanged, so everything not auto-approved still
flows through manual approval.

## Item-by-item

1. **Safety floor intact** — YES (above). `agentToolAutoApprove` returns false
   for HIGH before any level check; controller auto-approve is the sole change.

2. **Policy correctness** — Mapping matches watch=none / assist=LOW /
   auto=LOW+MEDIUM. No off-by-one / fall-through:
   - `HIGH` → false (floor)
   - `watch` → false
   - `auto` → true (LOW+MEDIUM; HIGH already returned)
   - else (`assist`) → `risk === 'LOW'`
   The order is correct: the HIGH floor precedes the `auto → true` branch, so
   `auto`+HIGH cannot leak. All 9 (level × risk) combos asserted in
   `autonomy.test.ts`.

3. **Live-level read** — YES, not stale. `agentToolAutoApprove(risk)` omits the
   level arg; the default param `level: AutonomyLevel = _level`
   (`index.ts:139`) is re-evaluated on EVERY call, reading the current
   module-global `_level`. `setAutonomyLevel` mutates `_level`
   (`index.ts:80`), so a dial change between tool-calls is observed at the next
   `onToolCallPending`. No closure capture of a stale value. The controller test
   proves this: it flips the level via `setAutonomyLevel` and the controller's
   later `agentToolAutoApprove(...)` call reflects it.

4. **No stale refs** — `autoApproveLowRisk` field removed from the controller;
   `jf-agent-auto-approve` localStorage seed removed from `AgentView`. Repo-wide
   grep finds only a single explanatory COMMENT in
   `AgentSessionController.ts:22` — no dangling reader, no live code path. Clean.

5. **Compact dial** — Correct. `compact` only swaps the title block
   (`AutonomyDial.ts:125-127`) and hint block (`:143-147`) for `nothing`; the
   3-segment control and `connectedCallback` substrate subscription
   (`:59-65`) are unconditional, so the compact dial stays in sync with the
   Settings dial. Per-segment hint is preserved via the unconditional
   `title=${HINTS[l]}` tooltip (`:136`). The agent header mounts
   `<jf-autonomy-dial compact>` (`AgentView.ts:579`); Settings mounts
   `<jf-autonomy-dial>` with no attribute (`SettingsSurface.ts:1300`) →
   `compact` defaults to `false` (`:56`), so the Settings dial renders fully.
   Side-effect import in `AgentView.ts` registers the element (avoids the §28
   type-only-elision pitfall).

6. **Tests** — Assert the right behavior. `autonomy.test.ts` covers the full
   policy table incl. the HIGH floor at every level. `AgentSessionController.test.ts`
   covers assist+LOW (immediate + queued-then-flushed),
   assist+MED/HIGH→manual, auto+MED→approve, auto+HIGH→manual (floor),
   watch+LOW→manual. `AutonomyDial.test.ts` covers the compact variant (3 segs,
   no title, no hint, tooltip present). Determinism: the module-global level is
   reset via `__resetAutonomyForTest()` in BOTH `beforeEach` and `afterEach`
   (`AgentSessionController.test.ts:62,73`); the autonomy test suite already
   resets. Tests share `_level` with production code via the same module import,
   so `setAutonomyLevel` in a test correctly drives the controller's read.

7. **Default-behavior shift** — CONFIRMED and is the one follow-up. The old
   checkbox defaulted OFF (seeded from `localStorage 'jf-agent-auto-approve'`,
   absent ⇒ false ⇒ auto-approve NOTHING). The new dial defaults to `assist`
   (`autonomy/index.ts:62`), which auto-approves LOW. So a fresh user shifts
   from "auto-approve nothing" to "auto-approve LOW-risk read-only tools."
   This is defensible (LOW = read-only search/browse, and the backend trust
   lattice remains the universal write/destructive gate per the module header),
   but it is NOT currently documented in a §32-followup tempdoc. Only one live
   note exists (`543-fwd-live-evidence.md:130`: "assist active by default").

## Verification performed

- `npm run typecheck` — clean (no output / no errors).
- `npx vitest run` on the 3 affected files — **58 passed (3 files)**. The
  trailing happy-dom `AbortError` is a window-teardown artifact (in-flight fetch
  aborted on teardown), not a test failure; every file reports passed.

## Follow-ups (non-blocking)

1. **Document the default-behavior shift** (off→assist/LOW) in a §32 tempdoc or
   the followup ledger, so the change from "auto-approve nothing" to
   "auto-approve LOW by default" is an explicit, recorded decision rather than
   an implicit consequence of reusing the dial's `assist` default. Reference the
   safety argument: LOW = read-only; backend lattice still gates writes.
2. *(Optional)* The old per-view localStorage key `jf-agent-auto-approve` is now
   orphaned in any existing user's storage. Harmless (never read), but a one-line
   cleanup or a note that it is intentionally abandoned would close the loop.

---

## substrate-review-post-impl

*(consolidated from `543-substrate-review-post-impl.md`)*

### 543 — Post-implementation substrate review (independent pass)

Reviewed at git rev `8f832e5e8` by independent reviewer (filename
retained as `543-substrate-review-post-impl.md` per the worktree
hand-off note; the worktree's set was renumbered 526→543 in commit
`d649f373c`, so all in-source citations reference §13.* of
`543-three-axes-of-contribution.md`).

Commit range: `5fae3bd7b..HEAD` (12 commits — 7 feature, 5 docs).

## Verdict

**APPROVE-WITH-FOLLOWUPS.**

Material from the first review (`543-substrate-review.md`) is largely
addressed: Effect union is closed (M4), `source: 'user'` → CORE (M1),
restoreScope replaces by default (M2), audience added to Scope (M3),
canonical Action's Effect no longer dead-ends at a phantom op (M5),
HoverPreviewHost now mounts as a real chrome host and consumes
`renderAggregateMulti` under the 'merge' policy (M6), status-bar
docstring fixed (M7).

The remaining concerns are SCOPE/CONSUMER gaps, not correctness
regressions. The substrate primitives ship internally consistent and
compile cleanly; what's missing is broader production read-through.
Concretely:

1. **EvaluationContext has no production consumer yet** — the
   existing `evaluateWhen` callers (`actions/index.ts:308`,
   keybindings, settings-panels) still pass a flat ShellContext
   projection, not `buildEvaluationContext(...)`. Only the demo
   panel and tests call the new composer. (§13.2.1 substrate-
   without-consumer-flavors.)
2. **Multi-axis Provenance is producer-starved.** The TS-side
   `identity` / `review` / `capability` fields are read by
   `displayTier` + `ProvenanceChip`, but no production plugin-
   install path populates them. `makePluginProvenance` is called
   only in tests. Result: chip's VERIFIED branch is unreachable in
   prod outside CORE (which is filtered out by `isNonCore`).
3. **Effect kinds `open-pane` / `close-pane` / `invoke-operation`
   dispatch to listeners that don't exist** — no production code
   registers handlers for the `jf-open-pane` / `jf-close-pane`
   custom events. The Effect renderer is honest about this ("best-
   effort dispatch"), but it's substrate-without-consumer at the
   dispatch layer.
4. **`Action.audience` / `Action.shortcut` are dead fields.**
   Declared in the type, never read by `listActions` /
   `invokeAction` / any other production code.
5. **Canonical Action test is still self-fulfilling** (prior
   review nit #1 was not actioned). The test re-registers if reset
   cleared the module-load side effect.

None block merge. Each has a candidate slice id in §Material below.

## Critical findings (block merge)

None.

## Material follow-ups (should fix soon, do not block merge)

1. **EvaluationContext is not consumed by `listActions` or any
   `evaluateWhen` callsite.** `modules/ui-web/src/kernel/substrates/actions/index.ts:293`
   passes `getShellContext() as unknown as Record<string, unknown>`;
   no `buildEvaluationContext(...)` route. The Action substrate
   ships the canonical `core.action.cite-selection` Action that
   *applies to* `'search-result'` / `'citation'` / `'document-passage'`
   addressables, but its `when` (none) and the surrounding listActions
   path don't carry per-Addressable facts. The only callers of
   `buildEvaluationContext` are
   `modules/ui-web/src/features/settings-substrate-demo/SubstrateDemoPanel.ts:260-261`
   and the unit test. Wire `listActions` (or a new Addressable-aware
   filter pathway) to build the layered context when an addressable
   is provided. Candidate slice: **544-A** (EvaluationContext
   consumer wiring).

2. **Multi-axis Provenance has no production producer.** The TS-
   side optional fields `identity` / `review` / `capability` /
   `installedAt` (`modules/ui-web/src/api/types/registry.ts:152-176`)
   are only populated by `makePluginProvenance` helper
   (`modules/ui-web/src/kernel/primitives/provenance.ts:78-91`),
   which is called nowhere in production — only in
   `manifest.test.ts:43`, `effects.test.ts:54-68,304`, and
   `ProvenanceChip.test.ts:38,50`. The actual plugin-install path
   (`PluginRegistry`) constructs Provenance independently and
   never sets these fields. Net effect: `displayTier` always
   resolves to `TRUSTED` for plugins (never `VERIFIED`), and the
   ✓ mark in `ProvenanceChip.ts:101` is dead code. This tracks the
   tempdoc's own α7 follow-up (`stamp installedAt in
   installContributionManifest`, line 1628). Candidate slice:
   **544-B** (plug PluginRegistry trust-handshake + manifest
   installer into the multi-axis fields).

3. **Effect kinds `open-pane` / `close-pane` dispatch into a
   void.** `modules/ui-web/src/kernel/substrates/actions/index.ts:407-423`
   emits `jf-open-pane` / `jf-close-pane` CustomEvents on `document`,
   but no listener anywhere in `modules/ui-web/src/` consumes them
   (verified by grep — only test file adds a listener, and only for
   `jf-open-pane`). The Effect renderer docstring at line 365-367
   acknowledges this as "best-effort dispatch — full wiring depends
   on which surface is mounted." But after the close-pane gap-fill,
   the symmetric pair is shipped as primitives without ANY production
   consumer. Either: (a) wire one pane host (Inspector pane is the
   obvious candidate — its open/close is already in `inspectorOpen`
   ShellContext state) to consume these events, or (b) explicitly
   defer these Effect kinds to a slice that ships the consumer.
   Candidate slice: **544-C** (pane host event consumer).

4. **`Action.audience` and `Action.shortcut` are dead fields.**
   Declared at `actions/index.ts:180,192`; never read in production.
   `listActions` (line 292-330) gates on `appliesTo` + `when` +
   `enabled` only — `audience` is ignored. `shortcut` is documented
   as "Bound by the keybinding registry when the Action is
   registered" but no code actually binds it. Either implement
   (audience gating in listActions; keybinding binding at
   registerAction) or remove the fields with a deprecation note.
   Candidate slice: **544-D** (Action field cleanup or fulfillment).

5. **Canonical Action test is self-fulfilling.** Prior-review nit
   #1 was not addressed: `actions.test.ts:268-284` explicitly re-
   registers `core.action.cite-selection` if the prior `__reset...`
   cleared the side effect. The assertion would pass even if the
   module-load eager registration at `actions/index.ts:494-516`
   were deleted. Use `vi.resetModules()` + dynamic `import` (the
   pattern works around the vitest module cache) or extract the
   eager block into a named helper that the test calls directly.
   Without this, the §3.C "substrate end-to-end" claim has no
   regression-test backing. Candidate slice: **544-E** (test
   precision fix).

6. **`searchResultFacts` projector is only loaded via the demo-
   panel import.** `modules/ui-web/src/features/search/projectors/searchResultFacts.ts:72`
   self-registers at module load, but the module is imported only
   from `SubstrateDemoPanel.ts:29`. If the demo panel is not
   mounted (e.g., the audience-gated developer settings panel is
   hidden in a production deployment), the projector never
   registers — even though it's a first-party feature module per
   §13.2.1 layer placement (FM). Either move the import to
   `composeBuiltinSurfaces` directly, or ship a feature-module
   side-effect import in the search feature's own boot path.
   Candidate slice: **544-A** (paired with consumer wiring above).

7. **`Effect Journal cross-session persistence test does not
   actually round-trip a record→reload→restore cycle.**
   `effects.test.ts:184-232` writes localStorage MANUALLY after
   `__resetJournalForTest()` clears it, then calls
   `restoreJournalFromStorage`. The test passes if restore parses
   a hand-crafted JSON blob — but it does NOT verify that
   `recordEffect` actually writes through to localStorage (the test
   never inspects `localStorage.getItem` between record and reset).
   The actual `writePersisted` code at `effects/index.ts:112-129`
   is uncovered. Add a test that calls `recordEffect`, reads
   `localStorage.getItem(PERSIST_KEY)` directly, and asserts the
   stored payload contains the entry. Candidate slice: **544-E**
   (test precision fix; pair with #5).

## Nits (could fix sometime)

- `manifest/index.ts:325-339` — `lifecycle.activate` failure
  permanently uninstalls the manifest with no retry semantics. The
  tempdoc itself flags this on the X1/α7 follow-up list; the test
  at `manifest.test.ts:179-204` documents the rollback as expected.
  For first-party `version: '0'` manifests this is fine, but for
  plugins on a slow backend a transient `activate` throw burns the
  install. Worth a comment-level note distinguishing "structural
  failure (correct to rollback)" from "transient failure (caller
  may want to retry)."

- `manifest/index.ts:331-340` — `activate` is `await`ed inside the
  installer but `installContributionManifest` is fire-and-forget
  in all 10 settings-panel migrations
  (`settings-appearance/index.ts:28` etc.: `void install(...).catch(...)`).
  Boot-order assumptions in `composeBuiltinSurfaces` (the
  sequential `await import(...)`) might race against the unawaited
  install microtask in pathological scheduling. In practice the
  microtask queue drains before the next `await`, but this is
  worth a comment at the migration sites.

- `Effect` open-question 9 fallback at `effects/index.ts:238-243`
  emits a toast inverse for `invoke-operation` saying "handler-
  computed inverse pending Operation wire extension." This means
  `undoLastEffect` will produce a toast announcing undo-intent
  WITHOUT actually undoing — chrome that wires undo to a Ctrl+Z
  shortcut will surface a misleading "Undo: ..." toast every time
  the user undoes an operation. Either skip such entries in
  `undoLastEffect` (treat as null inverse for now), or document the
  toast-as-stand-in semantic in the UI consumer.

- `aggregate-strategies.ts:242-244` — equal-rank tie-break is
  `_entries.indexOf(b) - _entries.indexOf(a)` (later-wins). The
  existing single-winner `dispatchAggregateStrategy` (lines 137-148)
  uses `entry.rank >= best.rank` with later-wins through the loop —
  same intent, but the algorithms diverge in shape. A comment
  noting both implement "later registration wins among equal rank"
  would prevent future drift.

- `HoverPreviewHost.ts:181-184` — `apiBase: ''` is passed to the
  strategy host. If any future hover-preview strategy needs the
  API base (e.g., to fetch enrichment), this is a silent footgun.
  Either wire a real apiBase from boot context or document that
  hover-preview is intentionally URL-free.

## Per-lens findings

### Lens 1 — substrate-without-consumer-flavors

| Substrate | Production consumer | Verdict |
|---|---|---|
| Form (§13.3.1) | `<jf-form>` + JSON-Forms registry tester at `registry.ts:103`; canonical `<jf-corpus-picker>` renders for `x-ui-renderer: 'corpus-picker'`. Demo panel mounts a live form. | OK (one canonical renderer; the consumer surface — `<jf-form>` — exists in production at the demo panel level). |
| Multi-Provider Dispatch (§13.3.2) | `HoverPreviewHost.ts:175-184` calls `renderAggregateMulti` under 'merge' policy; mounted at `Shell.ts:1423`. Two strategies (`operationHoverPreview` + `operationHoverPreviewExtras`) stack. | OK (real production host consumes the multi-strategy API end-to-end). |
| EvaluationContext (§13.2.1) | **Only demo panel + tests.** `actions/index.ts:293`, `keybindings.ts`, `settings-panels.ts`, etc. still pass flat ShellContext. The single FM projector (`searchResultFacts.ts`) is loaded only when the demo panel mounts. | **CONCERN.** Material #1 + #6. |
| Effect Journal (§13.2.2) | `applyEffect` writes to the journal on every dispatch (production callers: `invokeAndApply`). Demo panel's "Undo last effect" surfaces the cursor walk. | OK at write side; read side (Undo UI, Macro panel, AI preview) is FM-pending per the design. |
| ContributionManifest (§13.2.3) | 10 first-party settings panels + substrate-demo migrated. Manifest read at install time, entries register via the per-registry register functions. | OK (real first-party consumers). |
| Workspace Profiles (§13.6) | `boot/compose.ts:50-73` restores active profile on boot; demo panel UX exposes create/activate/duplicate/delete. `activateProfile` calls `restoreScope` which re-renders ShellContext consumers. | OK (one canonical UX consumer; the lifecycle is real). |
| Multi-axis Provenance (§13.2.3.1) | `ProvenanceChip.ts:82-104` reads `displayTier(p)` + `identity.verified` + `review.lastReviewedAt` + `capability.length`. | **CONCERN.** Read side present; producer side absent in production. Material #2. |
| Journal persistence (§13.7 q.3) | `restoreJournalFromStorage` at `boot/compose.ts:38-49`. `writePersisted` on every `recordEffect`. | OK at the data path; test precision concern at Material #7. |

### Lens 2 — independent-review-required

This pass discharges the gate. The prior reviewer is not the
implementer.

### Lens 3 — static-green ≠ live-working

Commit `d649f373c` reports "1764/1764 unit tests pass; typecheck
clean." Live-stack evidence in commit messages: the gap-fill
commit `5e7d51fa4` and the manifest migration `a245eb24e` do not
cite a live-stack verification artifact (no `jseval ui-shot`, no
captured-evidence id). The substrate-demo panel is the design's
live-validation surface, but no commit attaches an evidence
artifact. **Unverified live-stack** for: the 10 settings-panel
migrations actually rendering through the manifest pipe, the
HoverPreviewHost mounting on a real Operation row, the Workspace
Profile activation switching Scope live. The substrates compile
green; whether each of the 10 migrated panels actually loads
correctly in a running stack is not evidenced.

### Lens 4 — wrong-gate

Checked:

- `setDispatchPolicy('hover-preview', 'merge')` at
  `bootstrap.ts:38` — `bootstrapAggregateSubstrate` is called from
  `shell-v0/index.ts` boot path. Gate fires.
- `bumpScopeVersion()` wired into `subscribeShellContext` at
  `compose.ts:107-112`. Memo invalidation fires on every
  ShellContext broadcast.
- `Profile activation → restoreScope → updateShellContext →
  subscribeShellContext listeners → bumpScopeVersion` chain
  verified at `scope/index.ts:145` (calls `updateShellContext`).
  **§14.1 concern is closed**: profile activation does invalidate
  the EvaluationContext memo.
- The `enabled?` gate on Actions (`actions/index.ts:311-318`) wraps
  in try/catch — defensive against throwing plugin code; gate-
  intent matches the docstring.

No wrong-gate cases identified in the new code.

### Lens 5 — wire-emitter-elision

Provenance multi-axis fields (`identity` / `review` / `capability`
/ `installedAt`) are declared at `api/types/registry.ts:152-176`
as **TS-side optional** with explicit comment: "do NOT cross the
wire — the Java side serializes only the 3-field shape." This is
defensible: the fields live on the TS type but aren't wire-bound.
The Java emitter elides them by virtue of never seeing them; the
TS-side producer (`makePluginProvenance`) is the source.

Risk: if a future Java change accidentally serializes one of these
names, Zod's `.loose()` schemas would silently accept the field
into the wire shape — but since the TS type already has the slot,
no parse error. No current bug, but worth a comment at the wire-
emission test (`WireTypesTsGenerationTest`) that these four field
names are deliberately TS-only.

`close-pane` Effect kind: closed-union member, no wire emission
(Effect is TS-only per `actions/index.ts:104-110`).
`applyEffect` dispatches `jf-close-pane` CustomEvent. No listener
in production. **Substrate without dispatch-side consumer** —
Material #3.

### Lens 6 — audit-without-test

Commit message claims requiring test backing:

- `5e7d51fa4` "close-pane Effect + multi-axis Provenance + Manifest
  expansion" — close-pane added with `effects.test.ts:22-` test
  cases (verified by grep). Multi-axis fields tested at
  `provenance.test.ts:55-98`. Manifest 11 kinds: only 3
  contribution kinds (actions / factsProjectors / renderers) are
  exercised in `manifest.test.ts`; the other 8 (settingsPanels,
  statusBarItems, inspectorTabs, contextActions, emptyStates,
  keybindings, layouts, templates) are routed by the installer but
  no test verifies they register/uninstall correctly. **Audit-
  without-test concern**: the manifest's 11-kind claim has 3-of-11
  test coverage. Candidate slice: **544-F** (full manifest-kind
  coverage tests).
- `a245eb24e` "migrate 10 first-party settings panels" — no test
  per panel; the migration is mechanical. Tolerable but the
  end-to-end "settings panel renders via manifest path" is
  unverified live.

- `2a4fe2f63` "Effect Journal cross-session persistence" — test
  exists but is mis-shaped (Material #7).

### Lens 7 — standalone-capability-stays-stuck

No new lazy-Capability code in the substrates. Profile activation
re-applies state synchronously via `updateShellContext`. No late-
bind sites identified.

### Lens 8 — cross-substrate composition gaps (§14.1)

Per the prompt's named items:

1. **EvaluationContext memo invalidation on Profile activation.**
   §14.1 claim: covered by `bumpScopeVersion` chain via
   `subscribeShellContext`. **VERIFIED.** `restoreScope` calls
   `updateShellContext` (`scope/index.ts:145`) which broadcasts to
   `subscribeShellContext` listeners, including the one wired at
   `compose.ts:110-112` that calls `bumpScopeVersion`.

2. **Manifest activate-hook async failure permanently uninstalls.**
   **CONFIRMED PRESENT.** `manifest/index.ts:331-340` rolls back and
   throws — no retry path, no transient-vs-structural distinction.
   Test at `manifest.test.ts:179-204` documents this as expected.
   The tempdoc's X1/α7 row acknowledges. Acceptable for V1; track
   on follow-up.

3. **HoverPreviewHost double-mount listener leak.** **NOT REAL in
   practice.** `HoverPreviewHost.ts:101-110` removes its listeners
   in `disconnectedCallback` using captured references. Shell
   mounts the host once at `Shell.ts:1423`; a second mount under
   HMR triggers proper disconnect-then-connect via LitElement
   lifecycle. The risk is only material if an external caller
   manually creates+detaches the element without `disconnectedCallback`
   firing — not the current shape.

4. **Dead fields `Action.audience` / `Action.shortcut`.**
   **CONFIRMED DEAD.** See Material #4.

### Lens 9 — test precision

- **Canonical Action test (`actions.test.ts:254-292`)**: still
  self-fulfilling per prior nit #1. Re-registers on its own. Does
  not actually test that the module-load side effect at
  `actions/index.ts:494` fires correctly. Material #5.
- **Manifest install/uninstall round-trip
  (`manifest.test.ts:39-96`)**: tests 3 of 11 contribution kinds
  (`actions`, `factsProjectors`, `renderers`). 8 untested. The
  install pathway is uniform, so the failure mode would be a typo
  in the per-kind register loop — possible. Material §Lens 6.
- **Journal cross-session persistence (`effects.test.ts:184-232`)**:
  hand-seeds localStorage; does not exercise the
  `recordEffect → writePersisted → reset → restore` integration
  path. Material #7.
- **`scope.test.ts` profile-switch field-clear**: replace
  semantics tested at `scope.test.ts` (line numbers from prior
  review's Q3 update — `serializeScope` + `restoreScope` round-
  trip with `mode: 'replace'`).
- **EvaluationContext memo invalidation
  (`evaluationContext.test.ts:144-159`)**: bump scope version,
  re-project, observe cache miss. Good shape; tests for the
  right reason.

### Lens 10 — documentation drift

- §13.7 q.9 hybrid declarative+handler claim: shipped at
  `effects/index.ts:221-243`. The `invoke-operation` branch
  returns a toast inverse pending the Java `policy.inverse?`
  field. The §14.2 G5 finding (UIEffect vs DataEffect split)
  identifies a related-but-different gap (data-returning effects
  vs UI effects); G5 is positioned as a candidate β follow-up,
  not a supersession of q.9. **No drift on q.9.**
- §13.8 table at lines 1456-1469 claims all 8 primitives "shipped"
  with file:line citations. Verified each citation exists. The
  table is honest about cross-module follow-ups (Operation Java
  wire, manifest factory catalog, plugin trust handshake).
- The renumber 526→543 is complete in canonical paths; commit
  history retains old numbers per the chore commit's note.

## Per-substrate confidence

| Substrate | Consumer named | Test coverage | Live-verify | Verdict |
|---|---|---|---|---|
| Form (§13.3.1) | yes (XUiRendererControl + CorpusPicker + demo `<jf-form>`) | yes (XUiRendererControl.test.ts) | unverified | OK |
| Multi-Provider Dispatch (§13.3.2) | yes (HoverPreviewHost.ts:175) | yes (aggregate-strategies.test.ts; 205 lines, all three policies) | unverified | OK |
| EvaluationContext (§13.2.1) | demo + tests only | yes (evaluationContext.test.ts; 219 lines) | unverified | **CONSUMER GAP** (Mat #1, #6) |
| Effect Journal (§13.2.2) | yes (applyEffect production path) | yes (effects.test.ts; 318 lines incl. persistence) | unverified | OK, with test precision concern (Mat #7) |
| ContributionManifest (§13.2.3) | yes (10 settings panels + demo) | partial (3-of-11 kinds) | unverified | OK, with test coverage gap (Lens 6) |
| Workspace Profiles (§13.6) | yes (demo UX + boot restore) | yes (profiles.test.ts; 200 lines) | unverified | OK |
| Multi-axis Provenance (§13.2.3.1) | read side (chip); producer side absent | yes (provenance.test.ts; chip.test.ts) | unverified | **PRODUCER GAP** (Mat #2) |
| Journal persistence (§13.7 q.3) | yes (compose.ts boot) | partial (does not round-trip the write path) | unverified | OK, with test precision concern (Mat #7) |

## Summary

Eight kernel primitives shipped. The substrate scaffolding is
internally consistent and compiles clean. The first-pass review's
critical material is addressed. The remaining gaps are about
production-consumer breadth (EvaluationContext, multi-axis
Provenance, pane-event listeners), Action dead fields, and a
small set of test-precision issues — all candidate follow-up
slices (544-A through 544-F), none merge-blocking.

The merge plan in §15 of the tempdoc remains sound; this review
contributes the missing "post-Wave-E independent review" gate
named in §15.4.

---

## substrate-review

*(consolidated from `543-substrate-review.md`)*

### Tempdoc 543 substrate review (independent pass)

Reviewed at git rev 44a51fcc5 by independent reviewer.

## Verdict

APPROVE-WITH-FOLLOWUPS

The five waves compile cleanly, carry per-wave unit tests, and the
substrate scaffolding is internally consistent. However: four of the
five substrates ship with at most one production consumer
(SettingsShellSurface for the Provenance chip; substrate-demo panel
for Action + Scope + HoverPreview), the Action substrate's only
registered handler emits an Effect pointing at a `core.search.cite-selection`
operation that does not exist in the codebase, `restoreScope` has
asymmetric serialize/restore semantics that will leak state across
profile switches, the legacy-source fallback in `resolveProvenance`
misclassifies user-customized keybindings as `TRUSTED_PLUGIN`, and the
HoverPreview SurfaceContext has no chrome host invoking
`renderAggregate('Operation', 'hover-preview', …)` anywhere in
production code. These are substrate-without-consumer-flavors and
correctness gaps that should land before the next slice consumes any
of these substrates as load-bearing infrastructure, but none of them
break existing functionality.

## Critical findings (block merge)

None. The substrate work is additive and existing call sites are
unaffected.

## Material follow-ups (should fix soon)

1. **`resolveProvenance` misclassifies `source: 'user'` as
   TRUSTED_PLUGIN.** User-customized keybindings persisted in
   `UserStateDocument` carry `source: 'user'` (verified at
   `modules/ui-web/src/kernel/registries/keybindings.ts:185` and
   `modules/ui-web/src/shell-v0/state/UserStateDocument.ts:163,194,248,533`).
   The fallback at `modules/ui-web/src/kernel/primitives/provenance.ts:101`
   collapses `'user'` and `'plugin'` into a single
   `TRUSTED_PLUGIN` branch, so a future consumer that mounts the chip
   on a user-keybinding entry would label the user's own remap as a
   plugin contribution. Recommend a separate `tier: 'CORE'` (or a new
   `tier: 'USER'`) for user-sourced contributions, or — minimally —
   special-case `'user'` to return `CORE_PROVENANCE`.

2. **`restoreScope` round-trip is lossy on field-clear.**
   `serializeScope` (`scope/index.ts:81-94`) conditionally OMITS fields
   that are `undefined` to keep snapshots compact. `restoreScope`
   (`scope/index.ts:107-116`) only patches fields PRESENT in the
   snapshot. Consequence: a profile saved with `activeCorpusId='X'`,
   then user clears corpus, then saves Profile-B (with no corpus) →
   restoring Profile-B leaves `activeCorpusId='X'` in ShellContext.
   This contradicts the Workspace Profiles "switch identity" intent
   ("activate this profile" should mean "be exactly this profile, not
   union of last and this"). The scope.test.ts case at lines 87-101
   actively documents the wrong semantic as the expected one.
   Recommend either: (a) restoreScope CLEARS unknown-slot fields by
   default, or (b) snapshot retains explicit `null` sentinels for
   "intentionally cleared" so `restoreScope` can distinguish "missing
   key" from "cleared". Decision belongs to the Workspace Profiles
   design owner.

3. **`audience` Scope slot is documented as persistent but not
   serialized.** `shellContextState.ts:81-88` declares
   `readonly audience?: string` and the comment names it as a Scope
   slot. `scope/index.ts` neither captures nor restores it. The
   field is wired by `composeShellContextProjections` in
   `boot/compose.ts:56-74` from `viewerAudienceState` — so it has a
   source of truth elsewhere — but a Workspace Profile cannot
   capture/restore audience preference through Scope. If audience is
   intended to be in Scope per §3.B, add the field. If it's intended
   to stay in `UserStateDocument.viewerAudience`, remove the §3.B
   framing from the comment.

4. **`Effect` discriminated union has an escape-hatch branch that
   defeats narrowing.** `actions/index.ts:103-109` defines
   `Effect = … | { readonly kind: string; readonly [k:string]: unknown }`.
   The trailing branch makes `Effect.kind` widen to `string`, so a
   `switch (eff.kind) { case 'navigate': … }` no longer narrows
   `eff.to: string` — TypeScript sees `kind: string` as a supertype
   of `'navigate'`. Recommend the closed-union pattern (drop the
   open branch; if future extensibility is required, declare new
   kinds in this file). No production consumer switches on
   `Effect.kind` today, so the impact is forward-looking.

5. **`core.action.cite-selection` handler emits an Effect with a
   phantom `operationId`.** `actions/index.ts:386-390` returns
   `{ kind: 'invoke-operation', operationId: 'core.search.cite-selection', … }`.
   No operation with that id is registered anywhere in
   `modules/ui-web/src/` (verified by `git grep`). The Effect chain
   dead-ends — only the demo panel renders the returned object
   (without dispatching it). Either register the operation
   (in VirtualOperationCatalog) or rename the action so its emitted
   Effect points at a real handler. `audit-without-test`: there is
   no integration test that walks an Action through invoke and
   verifies the Effect is dispatchable.

6. **HoverPreview substrate has no chrome host.** The (Operation,
   hover-preview) strategy is registered (`bootstrap.ts:31`) and
   unit-tested in isolation, but no production code calls
   `renderAggregate('Operation', 'hover-preview', op, ctx)`. The
   existing hover infrastructure (`Peek.ts`, Shell §δ2,
   CommandPalette `jf-peek-request`) is surface-based, not
   aggregate-strategy-based. The substrate-policy of "declare it
   only when at least one production surface mounts it" (per the
   surfaceContextKinds.ts header — "511-followup Track D: trimmed
   the other 8 forward-declared kinds with no consumer surface") is
   violated by this addition. Recommend either: (a) wire a
   hover-preview host that consumes the strategy (could be a
   wrapper inside Peek that prefers strategy output for known
   aggregate kinds), or (b) defer the kind to a slice that ships
   the consumer alongside.

7. **status-bar.ts docstring references a non-existent module path.**
   `status-bar.ts:11` says `resolveProvenance() from
   @kernel/primitives/provenance-resolver`; the actual module is
   `@kernel/primitives/provenance` (verified by `git grep` — no
   `provenance-resolver` path exists). Fix the docstring.

## Nits (could fix sometime)

- `actions.test.ts:165-183` — the test for the canonical
  `core.action.cite-selection` registration is self-fulfilling: it
  re-registers the Action if the module-load registration was
  cleared by `__resetActionsForTest()`. The assertion would pass
  even if the module-load side effect were deleted. Recommend a
  fresh module import via `vi.resetModules()` + dynamic `import` to
  force the side effect, or extract the eager registration into a
  named helper that the test calls directly.

- `provenance.ts:39-43` — `CORE_PROVENANCE` is `Object.freeze`-d at
  the top level, but `makeCoreProvenance()` returns the SAME frozen
  object — the comment claims it's so future "per-feature
  versioning…can replace the sentinel without affecting call sites,"
  but since both return the same reference, replacing the sentinel
  in one branch wouldn't be detectable from callers. Either
  document that the abstraction is forward-compat-only, or make
  `makeCoreProvenance` return a fresh object.

- `actions/index.ts:289-291` — `listActions` defaults `scope` to
  `getShellContext() as unknown as Record<string, unknown>`. This is
  the right coordinate (matches WhenExpression evaluator input;
  flat-key shape per `shellContextState.ts:14`), but the
  double-cast through `unknown` is a code smell. Consider declaring
  `ShellContext` as `extends Record<string, unknown>` or adding a
  typed projection helper.

- `surfaceContextKinds.ts:55-60` — `SURFACE_CONTEXT_KINDS` is
  declared `readonly SurfaceContextKind[]` but immediately uses
  `as const` and then a runtime `includes` check via
  `(… as readonly string[])`. Tolerable, but the array could just
  be a `const arr = ['…'] as const` plus a derived type.

## Per-question answers

### Q1. Wave A chip rendering trigger

Production: only `SettingsShellSurface.renderRegisteredPanels`
(`modules/ui-web/src/features/settings-shell/SettingsShellSurface.ts:1244-1246`)
mounts `<jf-provenance-chip>`. The demo panel adds two more sites
in its own template (`SubstrateDemoPanel.ts:88-90`), but those are
explicit chips on hardcoded provenance values, not registry-driven.

Other contribution-rendering surfaces that DO NOT mount the chip:

- `shell-v0/components/StatusDeck.ts` (consumes `listStatusBarItems`)
- `kernel/inspector/InspectorPane.ts` (consumes `listInspectorTabs`)
- `shell-v0/components/ContextMenu.ts` (consumes `listContextActions`)
- The command palette (consumes `listCommands` via
  `CommandPalette.ts`)
- The keybinding renderer in the keyboard settings panel
- The aggregate-strategy renderers (`<jf-operation>`,
  `<jf-resource>`, `<jf-health-event>`) — strategies carry a
  `source` field but the components don't surface attribution UI

So Wave A is materially substrate-without-consumer-flavors for 11 of
the 12 touched registries. Acceptable as an "additive migration
window" intermediate state (the field's optional and consumers can
opt in), but the contract isn't observable through 11 of the 12
chrome surfaces yet.

### Q2. resolveProvenance derivation

`provenance.ts:89-112`. Verified `source` set-sites in
`modules/ui-web/src/`:

- `'core'` → CORE_PROVENANCE — correct
- `'shell'` → CORE — correct (`commands.ts` scope)
- `'operation'` → CORE — correct (virtual-operations.ts scope)
- `'surface'` → CORE — correct (commands.ts:scoped-by-surface)
- `'default'` → CORE — correct
- `'plugin'` → TRUSTED_PLUGIN with id — correct
- `'user'` → TRUSTED_PLUGIN with id — **INCORRECT** (see Material #1)
- `'demo'`, `'hello'` → CORE (unknown-source fallback) — acceptable
  (test/demo fixtures only)

`'user'` is the load-bearing miss: `UserStateDocument` keybinding
overrides carry `source: 'user'` and would render as plugin
attribution.

### Q3. restoreScope additive vs clearing

Material #2 above. Additive is the WRONG default for profile-switch
semantics: switching profiles should yield "be this profile," not
"union with whatever was active." The test at `scope.test.ts:87-101`
documents the additive behavior as expected, but the design intent
(per scope/index.ts:20-21 — "Workspace Profiles serialization. The
persistent subset of Scope…snapshots into a profile; restoring
applies the subset") doesn't justify the additive semantic — it just
doesn't address the clear-a-field case.

### Q4. Action substrate import path in production

The module IS imported in production via the substrate-demo panel:
`compose.ts:176` imports `@features/settings-substrate-demo/index.js`
during `composeBuiltinSurfaces`, which imports
`@kernel/substrates/actions/index.js` at line 32. So the
module-load registration of `core.action.cite-selection` DOES fire
at boot.

However: the Action's Effect points at `core.search.cite-selection`,
which is not a registered operation anywhere (see Material #5). So
the registration fires but the Action is non-functional outside of
the demo panel (which only renders the Effect object, doesn't
dispatch it).

### Q5. listActions scope vs when coordinate

Same coordinate. `listActions` defaults `scope` to
`getShellContext() as unknown as Record<string, unknown>`
(`actions/index.ts:290`), and `evaluateWhen` consumes the flat-key
ShellContext projection per `shellContextState.ts:14` (header
comment) and `predicates/when.ts`. Verified by `actions.test.ts:88-94`
where `when: 'activeCorpusId == "X"'` toggles correctly when
`updateShellContext({ activeCorpusId: 'X' })` is called.

### Q6. Effect union escape hatch

Yes, the open-string branch (`actions/index.ts:109`) widens
`Effect.kind` to `string` for the entire union. Any consumer doing
`switch (effect.kind) { case 'navigate': … }` would lose narrowing
on `effect.to`. The four canonical branches' literal types are
shadowed by `kind: string` because TypeScript unifies discriminants
across the union. No consumer hits this today (Effect is returned
from `invokeAction` and rendered as a generic object by the demo
panel), but the next consumer that depends on exhaustive narrowing
will need this fixed. See Material #4.

### Q7. SURFACE_CONTEXT_KINDS iteration

No production code iterates `SURFACE_CONTEXT_KINDS` to render UI
affordances. Only references:

- `PluginRegistry.ts:928` — uses `isSurfaceContextKind` to validate
  plugin strategy registrations (no UI render impact)
- `aggregate-substrate/index.ts:18,22` — re-export
- `SubstrateDemoPanel.ts:116` — render-only label

So the new `'hover-preview'` kind doesn't unexpectedly mis-render
anywhere. Plugin-registered strategies for `'hover-preview'` would
now pass validation that previously rejected them — that's the
intended change.

### Q8. HoverPreview without a host

Material #6 above. The strategy is registered, but no production
host invokes `renderAggregate('Operation', 'hover-preview', op,
ctx)`. The existing hover infrastructure (`Peek.ts`, Shell §δ2) is
surface-based and unaware of the aggregate-strategy registry. This
violates the surfaceContextKinds.ts header's own substrate
discipline: "Adding a new context is a substrate change: declare it
here, add its prop shape to `SurfaceContextOfMap`, register at
least one canonical strategy, AND ensure at least one production
surface mounts an aggregate in the context." The fourth condition
is not met.

### Q9. 12 registry edits — any missed?

The 12 touched:

1. aggregate-strategies.ts
2. commands.ts
3. context-actions.ts
4. empty-states.ts
5. inspector-tabs.ts
6. keybindings.ts
7. layouts.ts
8. recovery-overlays.ts
9. settings-panels.ts
10. status-bar.ts
11. templates.ts
12. virtual-operations.ts

Not touched (intentional or not):

- `builtin-surfaces.ts` — wraps wire-shaped `SurfaceContribution`
  which already carries `Provenance` via the wire type (verified at
  `api/registry/SurfaceCatalogClient.ts`); this is the right
  scope — Provenance is already uniform here.
- `virtual-tool-dispatcher.ts` — not a contribution registry; it
  dispatches virtual tool calls.
- The wire-shaped catalog clients (`SurfaceCatalogClient`,
  `OperationCatalogClient`, `ResourceCatalogClient`,
  `ConversationShapeCatalogClient`, `DiagnosticChannelCatalogClient`):
  these already carry `Provenance` on the wire shape from the
  backend Java types, so adding a TS-side field would be
  duplicative.

The 12 are the contribution-registry interfaces in
`@kernel/registries/`. Coverage looks correct.

### Q10. when-expressions referencing new Scope slots

Verified — no production `when` expression references any of
`activeCorpusId`, `activeLibraryId`, `preferredModelId`,
`activeAgentRole`, `enabledPluginIds`. Only references are in
substrate-demo panel + scope.test.ts + actions.test.ts (all
test/demo). No silent-flip risk today.

## Substrate-discipline lens checks

- **audit-without-test:** Concerns. The Action substrate has unit
  tests but no integration test that walks `core.action.cite-selection`
  end-to-end (invoke → Effect → operation dispatch); the test
  case at `actions.test.ts:152-189` is self-fulfilling (it
  re-registers if the side effect was cleared). The Scope substrate
  has unit tests but no test for the profile-switch field-clear
  case (Material #2). The HoverPreview strategy is unit-tested in
  isolation but no test verifies a host actually consumes it.

- **wrong-gate:** Pass for the changes shipped. `evaluateWhen`'s
  silent-false-on-missing-key behavior is verified at
  `kernel/predicates/when.ts:17-21` per scope/index.ts:13's claim,
  and the actions.test.ts:88-94 case confirms the `when` gate fires
  on the right coordinate.

- **substrate-without-consumer-flavors:** Concerns. Material #6
  (HoverPreview has no host) is the clearest case. The Provenance
  field on 11-of-12 registries (all except SettingsPanel) is
  arguably substrate-without-consumer-flavors too, but the
  additive-migration framing in the docstrings (e.g.,
  `status-bar.ts:8-12`) explicitly admits this. The Action
  substrate registers a canonical Action whose Effect points at a
  nonexistent operation (Material #5) — that's substrate without a
  working consumer.

- **wire-emitter-elision:** Pass. `Provenance` is the existing
  wire-typed re-export from `api/types/registry.ts` (per
  `provenance.ts:28`); no new wire shape is shipped.
  `ScopeSnapshot` is purely client-side state; not a wire shape.
  `Effect`, `Action`, `Addressable` are all client-side.

- **static-green ≠ live-working:** Concerns. Unit tests pass, but
  no live-stack verification was performed for: (a) the demo panel
  actually rendering against a DEVELOPER audience and exercising
  the substrate buttons; (b) the chip rendering on a plugin-
  contributed settings panel (no such plugin exists in-repo);
  (c) the HoverPreview strategy receiving a real host invocation
  (no host exists). The substrate-demo panel is the live-validation
  surface per its own docstring (`features/settings-substrate-demo/index.ts:2-7`)
  but the prompt does not mention live-stack evidence having been
  collected. Recommend running `jseval ui-shot` against the demo
  panel before declaring closure.

- **standalone-capability-stays-stuck:** Pass. Nothing in the five
  waves uses lazy/late-binding Capability creation. The new
  substrates use module-state + listener fan-out, mirroring the
  existing state-store idiom (matches the pattern at
  `shellContextState.ts:97-113`).
