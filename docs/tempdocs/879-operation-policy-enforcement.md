---
status: IMPLEMENTING
created: 2026-08-26
updated: 2026-08-26
follows: 868, 550 (trust lattice), 560 §28, 577
owner-session: f6617483
---

# 879 — Declared-but-inert operation policy: wire it or delete it

`OperationPolicy` declares seven axes. Two of them decide anything on the agent path. This
tempdoc closes the gap in both available directions — a declaration either changes behaviour
and has a test proving it, or it stops existing.

---

## Findings handed over

*(independent review 2026-08-26; every line reference re-verified in §A before it is relied on)*

`OperationPolicy` (app-agent-api) declares seven axes: `risk`, `confirm`, `audit`, `retry`, `rateLimit`, `capabilityFamily`, `undoSupported/inverse`. Only `risk` and `capabilityFamily` are load-bearing on the agent path. Five of seven being fiction trains authors to distrust the two that matter — including `risk`.

1. **`retry()` has zero production readers.** The retry that exists is `AgentToolDispatcher.java:~92-113`: `AgentRetryPolicy.forCode(TOOL_TRANSIENT_READ_ONLY)` with `canRetry = risk == LOW`. So `core.read-document`, declaring `noRetry()` because a paged read must not repeat, IS retried (it is LOW); `core.search-index` declaring `autoRetry(2)` gets one attempt. The catalog's own comment (`AgentToolsOperationCatalog.java:~180-184`) admits no reader exists.
2. **`ConfirmStrategy` gates nothing on the agent path.** `AgentToolDispatcher.handleSafetyGate` (`~217-290`) reads `risk`, `undoSupported`, `inverseOperationRef` and the autonomy dial; `confirm` is not consulted or transmitted (`~281-283`); it surfaces at `OperationExecutorImpl.java:~642` only as exception payload after a risk-derived decision.
3. **`AuditPolicy` is inert.** Only consumer is `RiskAuditValidator` (build-time lint: HIGH + NONE). `emitHistory` fires unconditionally with redaction hardwired `Optional.empty()` (`OperationExecutorImpl:~388-390`); `AgentRunStore:~183` persists full messages incl. raw tool arguments; `toolCompletedPayload` persists full output + structuredData for `AuditPolicy.NONE` ops into `events.ndjson` (30-day retention). The enum reads as a privacy control and functions as a lint.
4. **`rateLimit`** is read only by a UI emitter and a WARN validator; **`OperationLineage`** is decorative (all seven agent ops declare `empty()`).
5. **Store registers disagree at the encryption boundary.** `StoreCatalog` (app-agent-api; what `StoreCipher` seals: six stores) vs `governance/store-recoverability.v1.json` (registers more): `file-operation-journal` (`:~355`), `durable-grants` (`:~76`), `action-ledger-audit-journal` (`:~44`) are registered but never sealed; `DurableGrantStore`'s default path disagrees with its own `ownedPaths` entry. Reconcile: one authority, a test that the two agree, and a decision per store (seal or declare why not).
6. **Journal-by-reference (owner decision, 868 §A.7/§C.5):** content-bearing tool outputs (read pages, search excerpts) are persisted verbatim twice (events + checkpoint). Do NOT decide this unilaterally — design the option (journal `docId#range` references + a re-fetch path for the transcript view) with costs, and put it in the tempdoc as `OWNER-DECISION`; implement only if it falls out trivially from the AuditPolicy work (e.g. `AuditPolicy.METADATA_ONLY` actually redacting).
7. Coordinate: workstream 875 owns the family-grant × HIGH gate and durable grants semantics; workstream 876 owns availability. You own the retry/confirm/audit/rateLimit/lineage axes and the store registers. If wiring `confirm` overlaps 875's gate change, merge origin/main first and build on it.

Design rule handed down with the findings: for each axis choose **wire** (a real consumer with a
test proving the declaration changes behaviour) or **delete** (remove the field, every declaration,
validators, docs — retire-with-a-sweep). No third option ("keep for later").

---

## §A. Verification pass on the handed-over lines

Line references drift; each claim was re-checked against this worktree before any of it was
built on. Verdicts:

| # | Claim | Verdict |
|---|---|---|
| 1 | `retry()` has zero production readers | **Confirmed.** The only non-test `.retry()` occurrence in the tree is the catalog comment that says so (`AgentToolsOperationCatalog.java:182`). The retry loop is `AgentToolDispatcher.java:96-116`; the gate is `op.policy().risk() == RiskTier.LOW && attempt < decision.maxRetries()` (`:107-108`), with `decision` fixed at `AgentRetryPolicy.forCode(TOOL_TRANSIENT_READ_ONLY)` → `maxRetries = 1` (`AgentRetryPolicy.java:52-53`). `core.read-document` declares `RetryPolicy.noRetry()` (`AgentToolsOperationCatalog.java:212`) and is `RiskTier.LOW` (`:209`) — so it retries. `core.search-index` declares `autoRetry(2, …)` (`:153`) and gets one attempt. |
| 2 | `confirm` is not consulted on the agent path | **Confirmed.** `handleSafetyGate` (`AgentToolDispatcher.java:216-290`) reads `risk` (`:222`), `undoSupported`/`inverseOperationRef` (`:234`), and the autonomy dial (`:238`). `confirm` appears nowhere in the file. The `PendingApproval`/`ToolCallPendingApproval` events carry risk + gateBehavior only (`:270-284`). `OperationExecutorImpl.java:642` passes it into `ConfirmationRequiredException` *after* the risk-derived decision. |
| 2b | …but `confirm` is inert **only on the agent path** | **Correction to the framing.** `confirm` has four live non-agent readers: `UIOperationEmitter.java:98` (wire projection), `URLEmissionGrammar.java:248`, `ConfirmValidator.java:41,52`, `I18nKeyValidator.java:61`. Deleting the axis is not on the table; see §B species 3. |
| 3 | `AuditPolicy`'s only reader is a lint | **Confirmed** for the runtime: `RiskAuditValidator.java:39` is the sole reader of `op.policy().audit()` outside `UIOperationEmitter.java:108` (which only copies it onto the wire). `emitHistory` passes `Optional.empty()` for `argumentsSummary` unconditionally (`OperationExecutorImpl.java:388-390`), and the record's own javadoc has carried the "until audit-policy plumbing lands" note since slice 444b (`OperationHistoryEntry.java:18-25`). Distribution across the two catalogs: 31 × `METADATA_ONLY`, 6 × `NONE`, 0 × `FULL_PAYLOAD`. |
| 4a | `rateLimit` is read by a UI emitter and a WARN validator | **Confirmed, and stronger than reported.** `UIOperationEmitter.java:110` projects it; `RetryRateLimitValidator.java:40` warns on sub-second values. But **no operation anywhere in the tree declares a non-empty `rateLimit`** — every one of the ~37 production declaration sites passes `Optional.empty()`. The validator's only rule therefore cannot fire, and its documented retry rule is a no-op by its own admission ("handled at type-construction time … this validator only verifies the runtime entries are well-formed" — it verifies nothing). The wire field `rateLimitMs` is `NON_NULL`-included (`UIOperationView.java:65`) and so is never emitted. |
| 4b | `OperationLineage` is decorative | **Refuted as stated; true only of the agent ops.** Tree-wide, `lineage` has live consumers: `UIOperationEmitter.java:125-126` projects it and the FE renders it in two places (`operationButton.ts:123-127` tooltip, `operationHoverPreview.ts:24-28`). `CoreOperationCatalog.java:432` declares a non-empty `affects`; `:855` declares a non-empty `supersedes`, asserted by `CoreOperationCatalogTest.java:289`. What is true is narrower: **all seven agent-tool operations declare `OperationLineage.empty()`**, including the ones that mutate. |
| 5 | Store registers disagree | **Confirmed in substance**, with a nuance: the register's own `note` already distinguishes the two arrays (`stores[]` mirrors `StoreCatalog` and is "the cipher-selection authority for its six stores"; `durableStores[]` is "the broader in-place-upgrade compatibility register"). So the disagreement is not two copies of one fact — it is that the broader register names AUTHORED user data the cipher never touches, and **no gate compares a `durableStores` row's `ownedPaths` to the code that writes it**. Proof: `DurableGrantStore.java:297` resolves `base.resolve("ui").resolve("durable-grants.json")` while the register row declares `intent/durable-grants.json`, and the build is green. |

Net effect of the verification pass: findings 1, 2, 3, 5 stand. Finding 4 splits — `rateLimit`
is *more* inert than reported, `lineage` is *not* inert. The corrected picture is what §B is built
on.

### A.2 Two things the verification pass found that the handover did not

**(i) An accepted ADR asserts the enforcement that is missing.**
[`ADR-0030`](../decisions/0030-policy-on-operations-vs-mcp-hints.md) is the decision record that
justifies JustSearch diverging from MCP's tool-annotation discipline. MCP's spec says clients
"should never make tool use decisions based on ToolAnnotations received from untrusted servers";
ADR-0030 argues JustSearch may treat the equivalent metadata as *enforced policy* rather than
untrusted hints, because the backend and the shell ship from one distribution. Its Context section
says the executor "routes high-risk operations to confirmation modals, logs audit-required
operations, **throttles rate-limited operations**, gates capability-required operations", and its
Consequences section states:

> **Operation policy is load-bearing**, not documentation. The shell enforces declared risk,
> confirm, audit, and rate-limit on invocation; tests verify the enforcement.

Of the four axes that sentence names, one (`risk`) is enforced. `confirm` is projected but not
enforced; `audit` is linted, not enforced; `rate-limit` is neither declared nor enforced anywhere,
and no test verifies any of it.

This is a sharper statement of the problem than the handover's. The divergence from MCP is
justified *by* the enforcement. Without it the position reduces to "we trust the hints because we
wrote them" — which is the exact reasoning MCP's discipline exists to refuse. The ADR is not
merely out of date; it is load-bearing for a security posture, and it is describing a system that
does not exist. (Its Java snippet is also stale in shape — it shows a separate
`Optional<String> confirmTextKey` field that has since been folded into `ConfirmStrategy.Typed`.)

Whatever this tempdoc decides per axis, ADR-0030 has to end up describing what is true.

**(ii) `confirm`'s separation from the gate is documented and deliberate.**
`GateBehavior`'s own javadoc says the two are distinct on purpose:

> Distinct from `ConfirmStrategy` (the operation's declared confirmation *mechanism* — None /
> Inline / Typed). The lattice is the policy that decides *which* mechanism (if any) runs for a
> given source × operation combination; the `ConfirmStrategy` is the rendering hint when one runs.

So the architecture's own account is: the lattice decides **whether** to confirm; `ConfirmStrategy`
decides **how the confirmation looks**. That materially changes what "wire `confirm` on the agent
path" should mean — see §B.6, which is written against this rather than against the handover's
framing.

---

## §B. Theorization

### B.1 The problem is one bug class at two altitudes

The handed-over findings look like two unrelated halves — five policy axes, and a pair of store
registers. They are the same defect:

> **A declaration that no mechanism can contradict is not a constraint. It is a comment with a
> compile-time cost, and it accrues authority it never earned.**

Axis half: `core.read-document` declares `noRetry()` and is retried anyway. Register half: the
`durable-grants` row declares `ownedPaths: ["intent/durable-grants.json"]` and the code writes
`ui/durable-grants.json`. In both cases a reader who trusts the declaration is misled, and in
both cases nothing in the build noticed. This is the same shape 742 named for retired-feature
residue and 553 named for representation forks — here it appears as *forward* residue: substrate
declared ahead of its consumer, which then never arrived.

Naming this once is worth more than either fix. The repo already has the *inverse* gate shape —
`execution-surfaces` / `operation-surfaces` / `logic-seams` ask "is every referencer registered?".
What is missing is "**does every declared axis have something that can contradict it?**".

### B.2 "Inert" is consumer-relative — three species, not one

The wire-or-delete rule is right, but applying it needs the axes separated by *why* they are inert.
Collapsing them is how a correct rule produces a wrong sweep.

**Species 1 — phantom axis.** No non-empty declaration anywhere, no enforcer anywhere. Only
`rateLimit`. Nothing is lost by deleting it, because nothing was ever expressed through it. Pure
residue; the wire even carries an always-absent field for it.

**Species 2 — declared-but-unenforced.** Real, *varied* declarations that no runtime consults.
`retry` (seven agent ops declare meaningfully different values), `audit` (31 / 6 split). This is
the dangerous species, and the one where deletion would be the wrong call: the declarations carry
correct author intent that the runtime actively contradicts. `read-document`'s `noRetry()` is not
decoration — it is the right answer to "may a paged read be transparently repeated?", and the
runtime answers "yes". Deleting the axis would discard a correct intent and leave the wrong
behaviour hard-coded and unnameable.

**Species 3 — consumer-partial.** Load-bearing for one consumer, absent for another. `confirm`
(Operations/UI: yes; agent: no) and `lineage` (operations surface: yes; agent-op declarations: all
empty). Here neither "wire globally" nor "delete" is meaningful, because the axis is already wired
— just not everywhere it is read as applying.

Species 3 is where the "no third option" rule needs a precise reading. The forbidden third option
is *"keep for later"* — deferral dressed as a decision. But **"consumer X deliberately does not
consult this axis, here is the test that pins that boundary"** is not a deferral; it is a wire,
with the enforcement pointed at the exclusion instead of the inclusion. The rule should be read as
banning *unstated* exclusions, not stated ones. That said, for both species-3 axes here the
exclusion turns out not to be defensible on inspection (§B.4, §B.6), so the distinction does not
buy an escape in this tempdoc — it matters for the general rule.

### B.3 A fourth disposition worth naming: **redefine**

`AuditPolicy` claims, in its own javadoc, to "control how invocations are recorded for
retention/replay". It does not. There are two ways to end a lie: change the behaviour, or change
the claim.

That second move is not "keep for later" — it is making the declaration honest by fixing the
declaration. It is legitimate exactly when the runtime behaviour is *right* and only the name and
doc oversold it. It is illegitimate when it is used to bless an absent mechanism ("`NONE` means
'we don't promise anything'" would be that).

Applied here: `AuditPolicy.NONE` says "No audit record." Today every operation gets a history row
regardless. Either NONE genuinely suppresses the row, or the enum's vocabulary is wrong and should
say what the three values actually distinguish. This matters because suppressing rows has a real
downstream cost (§B.5) that redefinition avoids — so the choice is a design call, not a formality.

### B.4 `retry` — the axis declares *permission*, not *policy*

A tempting objection to wiring `retry` into `AgentToolDispatcher`: `OperationExecutorImpl` — the
path every non-agent dispatch takes — has no retry loop at all. Wiring one caller leaves the axis
half-live, changing behaviour for agent-tier callers and not UI-tier ones. Is that honest?

It is, under the right reading. **Whether to retry is a property of the caller's loop. Whether a
retry is *safe* is a property of the operation.** The `RetryPolicy` invariant already says this
out loud: `allowAutoRetry` without an `idempotencyKey` throws at construction. So the axis is a
permission the operation grants, and the invariant is per-caller and testable:

> No caller may transparently re-issue an operation that declares `noRetry()`.

One retrying caller satisfies that today; a second retrying caller would inherit the same
obligation. This framing also explains why the axis belongs on the operation rather than on the
agent loop, which is the alternative someone will propose.

The consequence to face honestly: wiring it is a **live behaviour change**. Today every LOW agent
tool retries once on exception. Afterwards only `core.search-index` does. Some of those tools
arguably *should* retry — a transient FS or index hiccup on `core.browse-folders` is precisely
what auto-retry is for. So the work is not only "wire the axis" but "**re-author the declarations
honestly**", which is the part that would have been skipped if the axis had merely been deleted.
Note that `autoRetry` forces an `idempotencyKey`, which is a useful forcing function: you cannot
grant retry permission without naming why replay is safe.

### B.5 `audit` — where are the stakes actually?

Two candidate wire targets, with very different value:

*(a) The `OperationHistoryEntry` path.* Make NONE suppress the row and FULL_PAYLOAD populate
`argumentsSummary`. Clean, local, entirely within `OperationExecutorImpl`. Risk: the history entries
feed the action ledger and its journal; suppressing rows for six operations blinds a surface that
today sees everything. And zero operations declare `FULL_PAYLOAD`, so half the wiring would have no
declaration exercising it — building a mechanism whose only user is its own test is the
`substrate-without-consumer` shape.

*(b) The agent-run journal.* This is where the actual privacy exposure lives: raw tool arguments
and full tool outputs, persisted verbatim into `agent-runs/*/events.ndjson` with a retention
window. If the operation is in scope at that write site, wiring `audit` there makes the enum mean
what its javadoc says. If it is not in scope, that is itself the finding — the privacy control and
the privacy-relevant write are in different rooms.

(b) is worth more if it is reachable. The open question is purely mechanical — does the journal
writer have the `Operation`? — and it is being checked. The answer decides the design, and it also
decides whether finding 6 (journal-by-reference) "falls out trivially", since a real
`METADATA_ONLY` redaction on that path is most of what journal-by-reference needs.

A third framing worth keeping in view: the honest unit for this axis may not be the *operation* at
all but the *field* — "this argument is a path, that one is a query string". Per-operation audit
policy is a coarse instrument for a per-argument problem. Not a proposal for this tempdoc; a note
for whoever finds per-operation granularity insufficient.

### B.6 `confirm` on the agent path — is the exclusion defensible?

The species-3 test from §B.2: is "the agent gate deliberately does not consult `confirm`"
defensible? Consider what the axis means. `ConfirmStrategy.Typed` says *"this is destructive enough
that the user must type a phrase before it happens."* The agent gate derives everything from
`risk` × autonomy × reversibility. If that derivation ever returns AUTO for an operation declaring
TYPED, the agent has auto-approved something the operation itself said required a typed phrase.

Whether that combination exists *today* is beside the point — the gate's inputs make it
representable, and a future MEDIUM-risk operation with a TYPED confirm would fall straight through.
So the exclusion is not defensible, and the wire is small: `confirm` becomes an input to the one
gate authority, with `Typed` forcing a non-AUTO verdict. Additively — one more input, one more
narrowing rule — so it composes with rather than collides against 875's family-grant change on
the same authority. Merging `origin/main` before touching it is the sequencing this implies.

There is a second, cheaper half: the pending-approval event carries `risk` and `gateBehavior` but
not the confirm shape, so the frontend cannot render a typed-phrase prompt even when the backend
wants one. That is a wire-shape question, not a gating one, and it may belong to whoever owns the
approval UI.

### B.7 `lineage` — the declarations are wrong, not the axis

Since `lineage` has two live frontend consumers, the disposition is not about the axis. It is that
seven operations declare "I affect nothing" and at least three of them mutate durable state
(`core.remember` writes memory; `core.ingest-files` and `core.file-operations` mutate). The
tooltip that says nothing is affected is *wrong*, not merely empty.

The caution: filling `affects` requires a `ResourceRef` for each affected resource. If one does
not already exist, minting a resource purely to populate a tooltip field is exactly the
`substrate-without-consumer` antipattern this tempdoc exists to fight — the fix would reproduce the
disease. So this reduces to: declare `affects` where a resource already exists; where none does,
say so explicitly rather than leaving `empty()` to mean two different things ("nothing is
affected" vs "the affected thing has no ref"). Those two meanings sharing one encoding is the
smaller version of the same bug class.

### B.8 The store registers — the missing gate is the finding

The `durableStores` register declares, per row, `ownedPaths`, `writeMode`, `atomicity`,
`corruptionPolicy`, `reconciliation`, `versionAuthority`, `tests`. Seven axes × ~37 rows. The
`durable-grants` path mismatch proves that at least `ownedPaths` is unchecked against the code —
which makes the register itself a 37-row instance of species 2, one altitude above
`OperationPolicy`. That is the unifying observation from §B.1 arriving as evidence rather than
as rhetoric.

Two distinct obligations fall out, and they should not be conflated:

1. **Agreement.** A test or gate that a row's `ownedPaths` correspond to what the named
   `implementationSources` actually write. Fully mechanical for rows whose writer resolves a
   literal path; harder where paths are composed at runtime. Even a partial check that catches the
   literal-path majority converts this class from silent to loud.
2. **A decision per store at the encryption boundary.** Several rows are AUTHORED user data whose
   bytes never pass through `StoreCipher`. Each needs an explicit, recorded answer: *sealed*, or
   *deliberately not sealed, because …*. Today the absence of an answer reads as an oversight and
   might be one — but might equally be a considered call nobody wrote down. Making the register
   carry the answer is what turns it from a description into an authority. The natural encoding is
   a required per-row field with a closed vocabulary, so "unanswered" becomes unrepresentable
   rather than merely undesirable.

On the path mismatch specifically: the code is the authority for where bytes actually are, because
changing the code strands existing user data at the old path. Barring evidence that the register's
path was the intended destination of a migration, the register is the side to correct.

### B.9 The generalization — a dead-declaration gate

If §B.1 is right, the durable fix is neither of these two sweeps but the gate that makes the class
loud: **for each axis of a registered policy record, a non-test, non-validator production reader
must exist.** No annotation escape hatch — an escape hatch for "declared ahead of its consumer" is
precisely the deferral the wire-or-delete rule bans, and would be reached for immediately.

Two data points already sit inside this one record: `OperationHistoryEntry.affectedResources` was
removed in slice 447 as a phantom field for exactly this reason, and `argumentsSummary` has carried
a "until audit-policy plumbing lands" note since slice 444b. Under `structural-defects-no-repeat`
two documented instances in one record is past the bar for the class being real.

The counter-argument deserves recording: a build-time validator *is* a consumer — it changes
whether the build passes, and `RiskAuditValidator` genuinely prevents a HIGH operation from
declaring `NONE`. So "lint-only" is not automatically inert. The line to draw:

> An axis may be lint-only when a structural invariant is its whole purpose. It may not be
> lint-only when its name and documentation promise a *runtime* effect.

`AuditPolicy` fails that test — "controls how invocations are recorded" is a runtime promise — so
it must be wired or its promise rewritten (§B.3). A hypothetical axis whose entire job was "HIGH
implies not-NONE" would pass.

Whether the gate belongs in this tempdoc or a follow-up is a scope question for design. The
argument for here: without it, this tempdoc fixes five instances and leaves the generator running.
The argument for later: a gate over *every* policy record is a much wider blast radius than the
five axes, and lands better once these five are settled and it has something green to ratchet from.

### B.10 Risks and things that could make this go wrong

- **Behaviour change under a green build.** Wiring `retry` changes what happens on tool failure;
  wiring `confirm` changes what happens at a gate. Both are exactly the point, and both are the
  kind of change a unit suite can pass while the live path regresses. Every wired axis needs a test
  that *flips the declaration and observes the behaviour change* — the acceptance criterion handed
  down, and the only one that distinguishes "wired" from "wired to a constant".
- **Sweep width vs. neighbouring worktrees.** Removing a record component touches ~37 production
  and ~25 test declaration sites. Two sibling workstreams are editing adjacent code. Mechanical
  breadth is cheap to produce and expensive to merge; keeping the diff scoped and merging
  `origin/main` before the gate-touching change is the mitigation.
- **The overload ladder.** `OperationPolicy` carries four backwards-compat constructor overloads
  for a record nothing outside this repo constructs. They are residue of the same species, and a
  `rateLimit` removal touches every declaration site anyway, so collapsing them is nearly free in
  marginal cost — but it doubles the conflict surface against the sibling worktrees. Tempting; a
  design call, not an obvious yes.
- **Fixing the disease with the disease.** Populating `lineage.affects` by minting resources that
  exist only to be pointed at, or wiring `FULL_PAYLOAD` when no operation declares it, would each
  add substrate whose only consumer is its own test. The rule that keeps this honest: wire toward
  a declaration that already exists, and if none does, that is evidence for delete, not for
  inventing one.
- **The motivating psychology is a hypothesis.** "Five fictions train authors to distrust `risk`"
  is plausible and unmeasured. The tempdoc should not rest on it. It does not need to: `read-document`
  declares `noRetry()` and is retried is a correctness bug on its own, and the register path
  mismatch is a factual disagreement on its own. The trust argument is why the class matters;
  the two bugs are why this tempdoc exists.

---

## §C. Investigation results (2026-08-26)

Three read-only investigations answered §C-old's open questions. Everything below carries
`file:line` evidence and was spot-checked against source before being built on.

### C.1 The `AuditPolicy` picture

- **Six operations declare `NONE`**: `core.search-index` (`AgentToolsOperationCatalog.java:153`),
  `core.read-document` (`:210`), `core.remember` (`:244`), `core.browse-folders` (`:273`),
  `core.navigate-to-surface` (`:367`), `core.ping-backend` (`CoreOperationCatalog.java:459`).
  Thirty-one declare `METADATA_ONLY`. **Zero declare `FULL_PAYLOAD`.**
- **What today's behaviour actually is**: every dispatch emits a metadata-only row with
  `argumentsSummary` empty. That is *exactly* `METADATA_ONLY`, applied uniformly. So the 31
  `METADATA_ONLY` declarations are already honoured by accident; the 6 `NONE` declarations are
  contradicted; and `FULL_PAYLOAD` describes a capability that does not exist and that nothing asks
  for.
- **A wrong-gate hazard, caught before it was written.** The advisory-emission block lives *inside*
  `emitHistory` (`OperationExecutorImpl.java:404-448`), after the history block (`:376-402`). And
  `core.ping-backend` is simultaneously the only operation declaring an `advisoryClass`
  (`CoreOperationCatalog.java:468`) and one of the six declaring `NONE`. An early return at the top
  of `emitHistory` for `NONE` would silently kill the entire advisory pipeline. The suppression must
  wrap the `historyEmitter.accept(...)` block only.
- **`argumentsSummary` is inert end-to-end**: declared in the record
  (`OperationHistoryEntry.java:58`), in the proto (`contracts/wire/operation_history.proto:32`,
  comment "absent until audit-policy plumbing lands"), in the SSOT schema as
  `["string","null"]`, and read by **nothing** — not by `ActionLedgerProjection` (which does not
  carry it), not by any frontend code, not by any test assertion.
- **Where `argumentsJson` is in scope**: at four of six `emitHistory` call sites (`:320`, `:337`,
  `:356`, `:364`); not at the two `undo()` sites (`:531`, `:536`), whose signature has no arguments.

### C.2 The agent-run journal

- The `Operation` **is** reachable at the event-mint site: `AgentStepRunner.java:822` resolves it
  and still holds it at `:957-958` where `ToolExecutionCompleted` is minted. So `op.policy().audit()`
  is consultable there without plumbing. (Recorded because it decides §D.3 — it turns out not to be
  the right place, but the option was real and had to be priced.)
- Double persistence, precisely: raw tool **arguments** are stored verbatim twice
  (`events.ndjson` via `AgentEventPayloads.java:74`/`:91`, and the assistant `tool_calls` message in
  the checkpoint `meta.json` via `AgentRunStore.java:229` ← `AgentLlmCaller.java:497-512`), neither
  truncated. Tool **outputs** are verbatim once (`RunEventStore.java:104-129`) plus a
  4000-char head in the checkpoint (`AgentStepRunner.java:961-965` truncates via
  `AgentContextCompressor`). `structuredData` is not duplicated. Retention is 30 days as an
  inline literal, not a named constant (`AgentRunStore.java:74-75`).

### C.3 Resources available for `lineage.affects`

Existing `ResourceRef`s include `core.indexing-jobs`, `core.indexed-roots`,
`core.failed-indexing-jobs`, `core.action-ledger`, `core.operation-history`, `core.health-events`.
There is **no** Resource for agent memory, and none for the file system. `core.rebuild-index` already
declares the indexing triple (`CoreOperationCatalog.java:432-436`), so there is a precedent to
follow for the ingest path and nothing to invent for the others.

### C.4 The store registers — worse, and wider, than reported

- **The gate is red on `main`, and nothing runs it.** `check-store-recoverability.mjs` appears in no
  workflow and no `governance/registry.v1.json` gate id; its only wiring is the manual pre-merge row
  in `CLAUDE.md`. Run read-only on this worktree's base it exits 1: an unclassified persistence write
  site, `modules/app-services/src/main/java/io/justsearch/app/services/ai/install/AcquisitionStage.java`,
  which arrived in PR #483 while the register's last touch was PR #381. A gate nobody runs is the
  same species as an axis nobody reads — the tempdoc's thesis, arriving unprompted as evidence.
- **`ownedPaths` is unvalidated free text.** The gate checks that the array is non-empty and that
  `implementationSources` files *exist*; it never relates a declared path to what the named code
  writes. Consequently the drift is not one row but **four**:

  | row | register declares | code actually writes |
  |---|---|---|
  | `durable-grants` | `intent/durable-grants.json` | `ui/durable-grants.json` — `DurableGrantStore.java:296` |
  | `ui-settings` | `settings/ui-settings.json` | `ui/settings.json` — `UiSettingsStore.java:122` |
  | `plugin-allowlist` | `plugins/allowlist.json` | `ui/plugin-allowlist.json` — `LocalApiServer.java:189` |
  | `watched-roots` | `watched-roots.json` | `watched_roots.json` (underscore) — `RemoteKnowledgeClient.java:250` |

- **Which side is right: the code.** Real user data sits at `ui/durable-grants.json` and
  `ui/settings.json` in the live profile directory; no `intent/` directory exists or ever did. The
  store is deliberately a sibling of the settings file (`DurableGrantStore.java:280` — "resolved like
  `UiSettingsStore`"). Moving the code would need a migration for no benefit. The register is the
  side to correct.
- **`ownedPaths` does have one live consumer**, which makes the drift more than cosmetic:
  `scripts/dev/dev-runner.cjs:299-318` derives the soft-clean keep-set from the first path segment
  of every AUTHORED row. It currently protects a directory named `intent` that does not exist, while
  the real `ui/` survives only because it is *separately* hand-listed on the floor (`:326`, `:337`).
  The derived half of that keep-set is inert — a data-loss guard resting on a hand-maintained
  duplicate rather than on the register it claims to read.
- **The sealing boundary.** `StoreCipher` is reachable only via
  `HeadAssembly.storeCipher(recoverability)` (`HeadAssembly.java:1049-1054`) and is applied at
  exactly five stores: conversations, memories, agent-runs (which covers `run-events`, since
  `RunEventStore` receives the AGENT_RUNS cipher at `AgentRunStore.java:72`), and feedback. Six
  AUTHORED files carry real user data in **plaintext**: `action-ledger-audit-journal`
  (contains the user's indexed directory paths — `ActionLedgerProjection.java:243-244` writes
  `scan.root()` unhashed, while the *ephemeral* ring hashes the equivalent field at
  `ActionEvent.java:158`), `file-operation-journal` (absolute source/destination paths plus the
  natural-language explanation — `FileOperationLog.java:230`), `durable-grants` (the standing-consent
  list — unsealed and unauthenticated, so the exposure is tamper rather than privacy),
  `ui-settings`, `plugin-allowlist`, and `watched-roots`. The remaining unsealed AUTHORED rows are
  unsealed *by design* (the keystore cannot be sealed by the key it holds; EXTERNAL and Rust-owned
  stores have no JVM cipher). **That distinction — genuinely-unsealed versus deliberately-unsealed —
  exists nowhere in the register today**, which is precisely why the six read as an oversight.
- **`StoreCatalog.isAuthored()` has zero production callers** (`StoreCatalog.java:63`; only
  `StoreCatalogTest` references it). The backup/export enumerates a separate hand-maintained
  registration list (`HeadAssembly.registerAuthoredStore` / `authoredStores()`, four call sites).
  A predicate on the authority that the thing it authorises does not consult is the same defect
  again, at method scope.
- **Out of scope but recorded**: the encrypted backup therefore contains conversations, memories,
  agent runs and feedback and nothing else — a restore silently loses the indexed-folder list, UI
  settings, plugin trust decisions, standing grants and the audit trail. Logged to the observations
  shard; it is a product decision, not an axis-enforcement one.

---

## §C-old. Open questions carried into design

1. Does the agent-run journal writer have the `Operation` in scope? Decides §B.5's (a)-vs-(b) and
   whether finding 6 is reachable.
2. What breaks if `AuditPolicy.NONE` suppresses the history row — which ledger/journal/FE surfaces
   lose data, and is redefinition (§B.3) the better answer?
3. Does a `ResourceRef` already exist for agent-mutated state (memory, watched roots, ingest)?
   Decides whether §B.7 is a declaration fix or a no-op-with-a-comment.
4. Is `ui/durable-grants.json` or `intent/durable-grants.json` the path real data occupies?
5. Does the dead-declaration gate (§B.9) belong here or in a follow-up?

---

## §D. Design

### D.0 Dispositions

| axis | disposition | what makes it true |
|---|---|---|
| `risk` | untouched | already the agent gate's primary input |
| `capabilityFamily` | untouched | live in `OperationExecutorImpl:616`; workstream 875 owns its semantics |
| `undoSupported` / `inverseOperationRef` | untouched | live in the reversibility signal + the executor's undo path |
| `advisoryClass` | untouched | live in `OperationExecutorImpl:411-448` |
| **`retry`** | **WIRE** — the agent dispatcher consults it; declarations re-authored | D.1 |
| **`confirm`** | **WIRE** — a floor on the agent gate; the agent-`Typed` exclusion pinned by a test | D.2 |
| **`audit`** | **WIRE** `NONE`; **DELETE** `FULL_PAYLOAD` and `argumentsSummary` | D.3 |
| **`rateLimit`** | **DELETE** — field, wire, schema, FE type, and its validator | D.4 |
| `lineage` | not inert — under-declared; one declaration corrected, the rest stated | D.5 |

Plus D.6 (ADR-0030), D.7 (a gate against recurrence), D.8 (the store registers).

### D.1 `retry` — wire it, then re-author the declarations

`AgentToolDispatcher.executeOperationWithPolicy` stops deriving retry from `risk == LOW` and reads
`op.policy().retry()`: retry iff `allowAutoRetry`, bounded by the declared `maxRetries`. The
back-off table (`AgentRetryPolicy.forCode(TOOL_TRANSIENT_READ_ONLY)`) stays — it is the *timing*
policy, which is the caller's business; the declaration supplies *permission and count*.

The framing that makes one wired caller sufficient (§B.4): **the axis declares permission, not
policy.** `RetryPolicy`'s own constructor already says so by refusing `allowAutoRetry` without an
`idempotencyKey`. The invariant is per-caller and testable: *no caller transparently re-issues an
operation that declares `noRetry()`*. Today there is one retrying caller; a second would inherit
the same obligation.

Declarations, each argued on the merits rather than on preserving today's behaviour:

**No declaration was re-authored.** The design originally called for changing `core.browse-folders`
from `noRetry()` to `autoRetry(1)` on the grounds that a directory listing is idempotent and the
`noRetry()` was boilerplate. That premise was **false** — the operation already declared
`autoRetry(2, "core.browse-folders")` and has since the initial public-release squash. The
implementer verified the pre-edit source and `git log -S` and declined the change, correctly: with
the premise gone, downgrading it to `autoRetry(1)` would have been an unrequested behaviour
reduction. Recorded rather than quietly corrected, because "the declarations were already right and
only the runtime was wrong" is a stronger statement of the defect than the one the design assumed.

Every agent-tool declaration is therefore unchanged, and the axis alone accounts for the behaviour
change:

| operation | declares | retries before | retries after |
|---|---|---|---|
| `core.search-index` | `autoRetry(2)` | 1 | **2** |
| `core.browse-folders` | `autoRetry(2)` | 1 | **2** |
| `core.read-document` | `noRetry()` | 1 | **0** |
| `core.remember` | `noRetry()` | 1 | **0** |
| `core.navigate-to-surface` | `noRetry()` | 1 | **0** |
| `core.ingest-files` (MEDIUM) | `noRetry()` | 0 | 0 |
| `core.file-operations` (HIGH) | `noRetry()` | 0 | 0 |

**The residual, stated rather than left to be found.** `core.ping-backend` declares `autoRetry(2)`
but is a UI-tier operation that never reaches `AgentToolDispatcher`, and the UI executor has no
retry loop at all — so that particular declaration still changes nothing. Under the
permission-not-policy reading (§B.4) this is coherent: it is a grant no caller currently exercises,
which is a different thing from a constraint nobody enforces. It would stop being coherent the day
a second caller starts retrying without consulting the axis, which is what the invariant in §B.4 is
for.

**Test**: parameterised over the catalog — an operation declaring `noRetry()` is dispatched exactly
once on a throwing handler; one declaring `autoRetry(n)` is dispatched `n+1` times. Flipping either
declaration flips the count.

### D.2 `confirm` — a floor on the agent gate, and a pinned exclusion

`GateBehavior`'s javadoc (§A.2 ii) is right that the lattice decides *whether* to confirm and
`ConfirmStrategy` describes *how*. But that separation is currently total: the declaration can
neither tighten the verdict nor reach the renderer. Two moves, chosen so that neither builds
substrate without a consumer.

**(a) The declaration becomes a floor.** `IntentGateEvaluator.agentGate` takes the operation's
`ConfirmStrategy` and never returns a verdict weaker than it: `None` imposes no floor, `Inline`
floors at `INLINE_CONFIRM`, `Typed` floors at `TYPED_CONFIRM`. The dial may still *tighten* — WATCH
raising a read to `INLINE_CONFIRM` is unchanged — it simply may no longer loosen below what the
operation declared.

This closes a representable hole: `ConfirmValidator` explicitly permits any strategy at MEDIUM, so
a *reversible* MEDIUM operation declaring `Inline` auto-fires under the `AUTO` dial today —
auto-approving something whose own declaration asks for a confirmation. No operation is in that
state right now, which is why the change is a safety floor and not a fix; the existing HIGH floor
(`:127-129`) is the precedent and is tested the same way.

The change is one added input and one narrowing rule on the single gate authority, so it composes
with rather than collides against 875's family-grant work on the same method.

**(b) The exclusion is pinned, not built around.** The agent authorization ceremony
(`AuthorizationHost.ts:340-372`) hardcodes the typed phrase to the operation id; `ActionButton.ts`
(the operations-palette path) already supports a backend-supplied phrase via `requireConfirmText`.
Threading `Typed(confirmTextKey)` onto the agent wire is therefore *possible* — and deliberately not
done, because **no agent-executable operation declares `Typed`**: both `typedForId` declarations
(`core.restart-worker`, `core.apply-excludes`) are `ExecutorTag.UI` only. Building the renderer for
zero declarations is the `substrate-without-consumer` shape this tempdoc exists to fight.

What replaces it is enforcement of the boundary: a registry test asserting **no `ExecutorTag.AGENT`
operation declares `ConfirmStrategy.Typed`**, whose message names the reason (the agent ceremony
cannot render a declared phrase). The day someone needs it, the build says so and names the three
files to change, instead of the phrase being silently replaced by an operation id at runtime. This
is the stated-exclusion form from §B.2 — a wire pointed at the exclusion, not a deferral.

### D.3 `audit` — enforce `NONE`, delete `FULL_PAYLOAD`

Today's uniform behaviour *is* `METADATA_ONLY`. So 31 declarations are honoured by accident, 6 are
contradicted, and one enum value describes a capability nothing asks for.

**Wire `NONE`.** `emitHistory` skips the `historyEmitter.accept(...)` block when
`op.policy().audit() == AuditPolicy.NONE`. Scoped to that block only — the advisory emission below
it must still fire, because `core.ping-backend` is simultaneously the only `advisoryClass` declarer
and one of the six `NONE` operations (§C.1). This is the wrong-gate hazard of the whole tempdoc,
found before the code was written; the test asserts *both* halves for that one operation.

Consequence, and it is an improvement rather than a loss: the 200-entry operation-history ring stops
being filled by health pings, navigations, and the agent's read/search traffic. Agent-loop dispatches
are already excluded from the unified action ledger (`OperationSubstrateInit.java:231-236` gates on
`transport != AGENT_LOOP`; their ledger rows come from `AgentRunLedgerProjector`), so the agent's
audit trail is unaffected — only the ring changes.

**Delete `FULL_PAYLOAD`.** Zero declarations, and the machinery it would need does not exist: there
is no PII flag on `Interface` inputs (the enum javadoc's "PII-flagged inputs … surface a WARN" is
itself unimplemented) and no reusable summariser in the tree. Building a redaction pipeline for zero
declared consumers is the antipattern; the one operation with a plausible claim to full-payload
audit, `core.file-operations`, already has a purpose-built journal recording exactly that
(`FileOperationLog`). `AuditPolicy` becomes two-valued, and **both values change behaviour**.

**Sweep** (retire-with-a-sweep): the enum value, the wire enum in `operation-wire.v1.json` (both
copies) and its generated FE zod/TS union, and then the field that existed only to carry it —
`OperationHistoryEntry.argumentsSummary` (record component, `operation_history.proto` field →
`reserved`, `operation-history-entry.v1.json` both copies, generated FE types, and the two doc
comments that promise "absent until audit-policy plumbing lands"). Nothing reads the field: not
`ActionLedgerProjection`, not any frontend code, not any test assertion.

**What this settles about finding 6.** The handover said journal-by-reference should be implemented
only if it "falls out trivially from the AuditPolicy work (e.g. `AuditPolicy.METADATA_ONLY` actually
redacting)". It does not: once `FULL_PAYLOAD` is gone there is nothing for `METADATA_ONLY` to redact
— the history row never carried a payload. And the double-persistence finding lives on a different
path entirely (the agent-run journal, §C.2), which is a *replay* substrate rather than an audit
record: gating its contents on `AuditPolicy` would silently break transcript replay for exactly the
content-bearing tools the finding is about. So journal-by-reference stays an OWNER-DECISION, written
up below with its costs, and is not implemented here.

### D.4 `rateLimit` — delete

No operation declares one; nothing throttles; the wire field is `NON_NULL` and therefore never
emitted; the validator's only rule is unreachable. Wiring a throttle would serve zero declarations.

Sweep: the record component and the four constructor overloads' parameter lists; `UIOperationView`'s
`rateLimitMs` and its `UIOperationEmitter` projection; `operation-wire.v1.json` (both copies) and the
generated FE types; `operationButton.ts`'s field list comment; and `RetryRateLimitValidator` in
whole — with `rateLimit` gone its remaining "rule" is a comment admitting it verifies nothing —
together with its registration and its test.

The four backwards-compat `OperationPolicy` constructor overloads are left in place. They are
residue of the same species and collapsing them is nearly free in marginal cost once every
declaration site is being touched anyway — but it doubles the conflict surface against two sibling
worktrees editing adjacent catalogs, and it is not this tempdoc's axis. Recorded as a known
follow-up rather than silently skipped.

### D.5 `lineage` — a declaration fix, not an axis fix

The axis has live consumers (`operationButton.ts:123-127`, `operationHoverPreview.ts:24-28`) and a
non-empty precedent (`core.rebuild-index` declares the indexing triple). What is wrong is that seven
agent operations declare "I affect nothing" and some of them mutate.

`core.ingest-files` gains `affects: {core.indexing-jobs}` — it queues indexing work, and
`core.rebuild-index` is the pattern to follow. The others keep `empty()` **with the reason stated in
the catalog**: no `ResourceRef` exists for agent memory or for the file system, and minting a
Resource so a tooltip has something to point at would reproduce this tempdoc's disease. `empty()`
currently encodes two different facts — "nothing is affected" and "the affected thing has no ref" —
and the comment is what separates them until a Resource exists.

### D.6 ADR-0030 — make the record true

ADR-0030 justifies diverging from MCP's "annotations are hints, never trust them" discipline on the
grounds that JustSearch *enforces* what it declares. It names four enforced axes; one is enforced.
The ADR is amended in this PR to state what is actually enforced after this work — and its stale
Java snippet (a separate `confirmTextKey` field that has since been folded into
`ConfirmStrategy.Typed`) corrected. The divergence argument survives, because after this work the
axes it names *are* enforced; what does not survive is the sentence claiming they already were.

### D.7 A gate against recurrence

`scripts/ci/check-policy-axis-liveness.mjs`: for every record component of `OperationPolicy`, assert
a production reader exists outside `modules/**/src/test` and outside the registry validators. Fails
the build otherwise. No annotation escape hatch — an opt-out for "declared ahead of its consumer" is
exactly the deferral the wire-or-delete rule bans, and would be reached for immediately.

Bounded deliberately to this one record. The general version (every policy record in the registry)
is a much wider blast radius and lands better once it has something green to ratchet from.
Registered in `CLAUDE.md`'s pre-merge table (which requires `check-premerge-table` to pass).

The lint-vs-runtime line the gate encodes: an axis may be validator-only when a structural invariant
is its whole purpose; it may not be validator-only when its name and documentation promise a runtime
effect. `AuditPolicy` failed that test, which is why D.3 is a wire and not a shrug.

### D.8 Store registers — the same defect one altitude up

Four obligations, in order of how load-bearing they are:

1. **Make the gate run and pass.** `check-store-recoverability.mjs` is red on `main` and wired into
   no workflow. `AcquisitionStage.java` writes managed model assets under AI_HOME `models/**`, so it
   is classified into `managed-ai-assets`' `implementationSources`. A gate nobody runs is this
   tempdoc's own thesis; it also has to be green before any new check it carries means anything.
2. **Correct the four drifted `ownedPaths` rows** (`durable-grants`, `ui-settings`,
   `plugin-allowlist`, `watched-roots`) to what the code writes. The code is the authority — shipped
   user data already occupies those paths, so moving the code would need a migration for no benefit.
3. **Make the drift impossible to repeat.** The gate gains a path-agreement check: for each
   `ownedPaths` entry, its literal directory segments and basename must appear as string literals in
   one of the row's `implementationSources`. Rows whose paths are composed at runtime and cannot be
   checked this way declare `pathVerification: "COMPOSED"` with a note — a *stated* exclusion the
   gate requires, so "unanswered" is unrepresentable rather than merely undesirable. This is the
   register's version of D.7.
4. **A decision per store at the encryption boundary.** Every AUTHORED row gains a required
   `encryption` field with a closed vocabulary: sealed by `StoreCipher`, or not sealed for a named
   structural reason (the keystore cannot be sealed by the key it holds; EXTERNAL and Rust-owned
   stores have no JVM cipher reachable). The gate cross-checks it — a row covered by a `StoreCatalog`
   directory must declare sealed. Six AUTHORED files today carry real user data in plaintext
   (§C.4); after this each one either changes or says why it does not, and the answer is in the
   register instead of in nobody's head.

   Sealing any of the six is a behaviour change with an on-disk migration and is **not** done here —
   what is done is making the absence of an answer impossible. Which of the six should be sealed is
   an owner call recorded below.

5. **`StoreCatalog.isAuthored()` gets a consumer.** It has none today
   (`StoreCatalog.java:63`) while a hand-maintained registration list decides what the encrypted
   backup contains. `HeadAssembly.registerAuthoredStore` asserts the predicate on registration — a
   two-line wire that makes the authority authoritative for the thing it is named after.

### D.9 What this design orphans

- `RetryRateLimitValidator` (whole file, registration, test) — nothing left to validate.
- `AuditPolicy.FULL_PAYLOAD` and every declaration surface that enumerates it.
- `OperationHistoryEntry.argumentsSummary` and its proto field, schema property, and FE type.
- `OperationPolicy.rateLimit`, `UIOperationView.rateLimitMs`, and the wire property.
- The doc comments promising audit-policy plumbing "when it lands" — it landed or it was deleted.

---

## §E. Plan

Sequenced so that the wave touching every `new OperationPolicy(...)` site lands before anything
else edits those files.

**Wave 1 (parallel).**

- **E1 — `rateLimit` delete + `retry` wire.** `OperationPolicy` record component and the four
  overloads; ~37 production + ~25 test declaration sites; `UIOperationView.rateLimitMs` +
  `UIOperationEmitter` projection; `SSOT/schemas/operation-wire.v1.json` and its
  `modules/ui/src/main/resources/` copy; regenerate `modules/ui-web/src/api/generated/schema-types/`;
  delete `RetryRateLimitValidator` + registration + test; `AgentToolDispatcher` reads
  `op.policy().retry()`; re-author `core.browse-folders` per D.1. Tests: retry count parameterised
  over the catalog (declaration flip flips the count).
- **E2 — store registers (D.8).** Classify `AcquisitionStage.java`; correct the four `ownedPaths`;
  add `pathVerification` + `encryption` fields with a closed vocabulary; extend
  `check-store-recoverability.mjs` with the path-agreement and encryption-disposition checks;
  `HeadAssembly.registerAuthoredStore` asserts `isAuthored()`. Gate must exit 0.

**Wave 2 (parallel, after E1).**

- **E3 — `audit` (D.3).** `NONE` suppresses the `historyEmitter.accept` block *only*; delete
  `AuditPolicy.FULL_PAYLOAD` and `OperationHistoryEntry.argumentsSummary` with the full sweep
  (proto `reserved`, both schema copies, generated FE types, doc comments). Tests: `NONE` emits no
  history row but still fires the advisory for `core.ping-backend`; `METADATA_ONLY` emits one.
- **E4 — `confirm` floor (D.2), `lineage` (D.5), ADR-0030 (D.6), liveness gate (D.7), docs.**

**Wave 3 — verification.** `spotlessApply` → `build -x test` → full `test` → ui-web typecheck +
unit tests → the pre-merge checks for every subject edited (`--gate wire`,
`check-store-recoverability`, `check-premerge-table`, `check-wire-schema-types-regen`, the ui-web
gate recipe, `check-policy-axis-liveness` itself) → critical-analysis pass → independent
refute-first reviewer on the diff.

**Teardown carried in the same PR** (D.9): `RetryRateLimitValidator`, `AuditPolicy.FULL_PAYLOAD`,
`OperationHistoryEntry.argumentsSummary`, `OperationPolicy.rateLimit`, `UIOperationView.rateLimitMs`,
and every doc comment promising audit-policy plumbing "when it lands".

**Explicitly not done, and why** — collapsing the four `OperationPolicy` backwards-compat
constructor overloads (D.4: same species, but not this tempdoc's axis and it doubles the conflict
surface against two sibling worktrees); sealing any of the six plaintext AUTHORED stores (O-2:
needs an on-disk migration and an owner call); journal-by-reference (O-1: does not fall out of the
audit work, and would make replay lossy).

---

## §F. Implementation log

### F.1 The liveness gate, and the fact that it fails today

`scripts/ci/check-policy-axis-liveness.mjs` (D.7) went in first, deliberately — a gate written
after the fix it enforces is a gate nobody has seen fail. Run against the tree before the `retry`
wire landed it reports:

```
policy-axis liveness: FAIL
  - axis 'retry' has no production reader outside the registry validators (no reader at all)
```

Two implementation notes worth keeping, because each was a way the gate could have passed
vacuously:

- **Comments had to be stripped before matching.** The catalog's own javadoc contains the literal
  `OperationPolicy.retry()` *while asserting that the axis has no reader*. Without comment
  stripping, prose describing the absence counts as the presence — the gate's first run passed for
  exactly that reason.
- **Catalogs are excluded from counting as readers.** A catalog is a declaration site. Letting one
  count would allow an axis to satisfy the gate by being declared, which is the precise state the
  gate exists to reject.

The second rule (an `Optional` axis needs a declaration, not just a reader) is the one `rateLimit`
failed while still having a wire projection as a "reader". Since `rateLimit` is being deleted, that
rule was verified against a synthetic catalog in which every `Optional` axis is `Optional.empty()`;
it correctly reports `advisoryClass`, `inverseOperationRef` and `capabilityFamily` as undeclared
there while passing them against the real catalogs.

The gate then went **green on the commit that wired `retry`**, having been red on the commit before
it. Red-then-green across exactly the change it was written for is the only evidence that separates
a gate from a decoration — and it is the same standard this tempdoc holds the axes to.

**Delivery.** The gate is registered in `governance/consult-register.v1.json`'s existing
`workflow-agent-tool` region (extended with `OperationPolicy.java` and an ADR-0030 doc pointer)
rather than as a new `CLAUDE.md` pre-merge row. Two reasons: the always-loaded budget ratchet
rejected the row (`CLAUDE.md` would have gone 55 B over its ceiling, and that ceiling never
ratchets up), and the consult hook pushes the recipe at the moment of the edit, which is stronger
delivery than a table someone has to remember to read. This is the `before-appending-to-rules`
routing rule applied to itself.

### F.2 `rateLimit` deleted, `retry` wired

68 call sites stripped across `CoreOperationCatalog` (30), `AgentToolsOperationCatalog` (7), two
projections and 15 test files; `UIOperationView.rateLimitMs` and its emitter projection; both copies
of `operation-wire.v1.json` **and** of `operation.v1.json` (the dual-copy pairs re-verified as
byte-identical by `SubstrateSchemaGenTest`); the regenerated FE types; and
`RetryRateLimitValidator` deleted outright — it had no production registration at all, only a test
one, and it was already sitting in `gates/dead-code-jvm/baseline.txt`. Its baseline line was removed
rather than kept, so the ratchet tightened rather than absorbing the change.

`AgentToolDispatcher` now gates on `retryPolicy.allowAutoRetry() && attempt < retryPolicy.maxRetries()`,
with `AgentRetryPolicy.forCode(...)` retained purely as the back-off table (the local was renamed
`decision` → `backoff` to stop it reading as the decision it no longer makes). The
`recordRetryExhausted` telemetry call moved to the same condition — a stale `risk == LOW` there
would have been a textbook wrong-gate leftover, correct-looking and silently wrong.

Tests: `AgentToolDispatcherRetryTest` (3 dispatch-counter cases, including `autoRetry(2)` at
`RiskTier.MEDIUM` retrying — the assertion that distinguishes "the declaration decides" from "it
still passes because everything is LOW"), plus `retryDeclarationsArePinned` over all seven agent
operations with a size assertion, so adding a tool forces a deliberate declaration.

### F.3 What the full-suite run says

The suite was run once under three concurrent sibling worktrees. Failures: `indexer-worker` (3,
Tika/VDU extraction), `worker-services` (6, Tika/policy extractor), `worker-core` (2, ONNX embedding
span) — the corpus/model-fixture family, in three modules that appear nowhere in this diff.
`app-services`, `app-agent`, `app-agent-api`, `ui` and `app-inference` all passed. The frontend
suite showed one timeout in `PluginLoader.test.ts` that passes in isolation, and an earlier run
failed two *different* files that also passed in isolation — load flakes from parallel builds, not
a signal. The orchestrator re-runs the full suite once at the end, unloaded, rather than trusting
this.

### F.4 What the store-register work found, and the CI wiring it forced

`check-store-recoverability.mjs` was **red on `main`** and invoked by no workflow and no gate id —
its only wiring was a manual row in `CLAUDE.md`'s pre-merge table. An unclassified persistence write
site (`AcquisitionStage.java`, writing managed model assets under AI_HOME) had been sitting there
since PR #483 while the register's last touch was PR #381. Nobody ran it, so nobody saw it.

That is this tempdoc's thesis one more altitude up, and it forced a scope decision. Shipping
`check-policy-axis-liveness.mjs` as another manual pre-merge row would have reproduced the exact
defect the gate exists to catch — an enforcement mechanism nothing runs cannot contradict anything.
So both gates (plus the store gate's own unit tests, which also ran nowhere) are wired into
`ci.yml` in this PR rather than left as a follow-up.

Beyond the red: `ownedPaths` turned out to be unvalidated free text, and **four** rows disagreed
with the code that writes them, not the one the handover named — `durable-grants`
(`intent/` vs the real `ui/`), `ui-settings`, `plugin-allowlist`, and `watched-roots` (hyphen vs
the real underscore). The drift was not cosmetic: `scripts/dev/dev-runner.cjs` derives its
soft-clean keep-set from the first path segment of every AUTHORED row, so a data-loss guard was
protecting a directory (`intent/`) that has never existed, while the real one survived only because
it was *separately* hand-listed. The register is corrected to match the code (shipped user data
already occupies those paths), and the gate now checks each declared path's literal segments
against string literals in the row's own `implementationSources`, with `COMPOSED` as a *stated*
exclusion a row must justify rather than an unanswered question.

Every row now carries a required `encryption` disposition. The final split: 5
`SEALED_BY_STORE_CIPHER`, 18 `NOT_APPLICABLE`, 6 unsealed for a *named structural* reason (1 key
root, 3 external authorities, 2 Rust-owned with no JVM cipher reachable) — and **7**
`UNSEALED_GAP`, one more than the review found: `entity-clusters` also holds authored user data
(the `entity_overrides` merge/split decisions) in plaintext SQLite. Each gap row carries a note
saying what the plaintext contains. `pathVerification` came out 31 `LITERAL` / 5 `COMPOSED`, so the
stated-exclusion escape was used sparingly rather than to silence failures. No bytes changed;
sealing is O-2 below.

### F.5 Interruption and recovery

This session was terminated mid-flight by an account session limit, taking three sub-workers with
it. The worktree survived intact and the tree compiled, so the recovery was inventory → verify each
worker's half → commit → merge `origin/main`. Recorded because the recovery shape is the useful
part: `git status` plus a compile told the whole story in two commands, and every worker's change
was independently checkable against its own acceptance test rather than against a report that no
longer existed.

The merge brought tempdoc 872, which retired the observations inbox. The six notes this
workstream's shard had accumulated were routed per the new rule (§G) and the shard deleted;
`check-no-observations-shards` is green.

---

## §G. Findings routed out of this workstream

Three findings surfaced during the work that are **not** this tempdoc's subject. Recorded here with
their evidence so they are routed rather than piled, per `rule:log-pre-existing-issues`.

1. **`WireContractVersion.CURRENT` has silently drifted from its declared source.**
   `modules/app-api/src/main/java/io/justsearch/app/api/stream/WireContractVersion.java:53` hard-codes
   `"0.2.0"` while its own javadoc says "Sourced from `contracts/wire/VERSION`". `VERSION` read
   `1.0.3` before this PR and `2.0.0` after (removing a proto field is a major bump under the file's
   own stated rule). So the constant `/infra/capabilities` advertises has been wrong across two
   major bumps. **Deliberately not fixed here**: correcting the constant changes what the API
   advertises, which is a behaviour change with consumers this workstream has not surveyed, and
   "declared source that isn't the source" is a wire-contract subject rather than an operation-policy
   one. It is the same bug class as this tempdoc, at a third site.

2. **The `wire` gate may report `pass` when it cannot run.** A worker observed that with
   `scripts/wire-contract/node_modules` absent, `scripts/governance/gates/wire/protobuf-buf-breaking.mjs`
   emits an `error`-level `contract-governance/buf-cli-missing` finding and the gate still reports
   `wire: pass`. **Stated as an unreproduced worker observation, not a verified finding** — I did not
   reproduce it (the dependencies were installed by the time I looked, and I will not report a
   gate-integrity defect on hearsay). The reproduction for whoever picks it up: remove
   `scripts/wire-contract/node_modules`, run `node scripts/governance/run.mjs --gate wire --mode gate`,
   and check whether the verdict is `pass`. If it is, a gate that cannot run is reporting success —
   which would make every `wire: pass` in the repo's history conditional on a directory nobody checks.
   *This PR's own `wire: pass` was re-run with the dependencies present and returned 0 findings.*

3. **`NdjsonInferenceTransitionLogTest` "retention prunes entries older than the cutoff" failed once
   under load** (`modules/app-inference/src/test/java/io/justsearch/inference/NdjsonInferenceTransitionLogTest.java:95`)
   and passed on immediate re-run, during a full suite executed with three concurrent Gradle builds.
   **Deliberately not pinned** in `expected-state.v1.json`: one failure under abnormal load does not
   establish a steady state, and that file's own contract warns that a pin whose red is gone is a lie.
   Reported so a second sighting has something to join.

---

## OWNER-DECISION — items this workstream deliberately does not decide

### O-1. Journal-by-reference for content-bearing tool outputs *(finding 6; from 868 §A.7 / §C.5)*

**What is true today (§C.2).** Raw tool *arguments* are persisted verbatim twice — in
`agent-runs/*/events.ndjson` (`tool_call_proposed`, `tool_call_pending`) and in the checkpoint
`meta.json`'s assistant `tool_calls` message — neither truncated. Tool *outputs* are verbatim once
in `events.ndjson` plus a 4000-char head in the checkpoint. `structuredData` is not duplicated.
Retention is 30 days, as an inline literal rather than a named constant
(`AgentRunStore.java:74-75`).

**The option.** Journal a reference (`docId` + character range) instead of the content for
outputs the index can re-serve — read pages and search excerpts — and have the transcript view
re-fetch on demand.

**Costs, so the decision is priced rather than assumed:**
- *Replay becomes lossy under change.* A re-fetch answers from the index as it is *now*. If the
  document changed or was removed, the transcript shows something the run did not see, or nothing.
  Today's journal is a faithful record of what the model was actually given; a reference is not.
- *Replay gains a dependency.* Reading an old transcript would require the Worker to be up and the
  document still indexed. Today it is a file read.
- *It does not fix the arguments.* The un-truncated duplicate of raw tool arguments — which is where
  file paths and queries live — is a separate problem a content-reference scheme does not touch.
- *What it buys.* Bounded run-directory growth, and a shorter plaintext exposure window for content
  in a store that is sealed (agent-runs is one of the five `StoreCipher` stores), so the privacy gain
  is smaller than it first appears.

**Recommendation if a decision is wanted:** the sharper, cheaper win is bounding what
`events.ndjson` stores for *arguments* and giving the 30-day retention a named constant — neither of
which needs the reference scheme. Not implemented here either way.

### O-3. The encrypted backup contains four stores and silently omits the rest

Surfaced by the store-register work (`ConversationBackupController.java:62`). The backup enumerates
a hand-maintained registration list, not `StoreCatalog`, and covers conversations, memories,
agent-runs and feedback. A restore therefore silently loses the indexed-folder list, UI settings,
plugin trust decisions, standing grants, and the audit trail — with no warning that the bundle was
partial. `StoreCatalog.isAuthored()` now has a consumer (registration asserts it), so the two lists
can no longer disagree about *class*; they can still disagree about *membership*. Whether a backup
should carry the other AUTHORED stores is a product decision, not an axis-enforcement one.

### O-2. Which of the seven plaintext AUTHORED stores should be sealed

D.8 makes every row declare an answer; it does not change any bytes. The seven now labelled
`UNSEALED_GAP` are `action-ledger-audit-journal` and `file-operation-journal` (absolute user paths),
`watched-roots` (the indexed-folder list), `ui-settings` (including the configured local model
path), `plugin-allowlist`, `entity-clusters` (the user's own entity merge/split decisions), and
`durable-grants` — where the exposure is tamper rather than privacy: an unauthenticated file decides
what the agent may run without a gate. Each would need an on-disk migration. Recorded, not decided.
