---
title: "Policy on Operations vs MCP-style hints: JustSearch's deliberate divergence"
type: decision
status: accepted
description: "JustSearch's Operation registry primitive carries policy fields (risk, confirm, audit, retry, rate-limit, capabilities) on the entry, while MCP keeps equivalent metadata as untrusted hints clients should not trust. The divergence is justified by JustSearch's single-trust-domain threat model and is composed with MCP discipline at the plugin-tier boundary via a trust-tier-aware executor."
date: 2026-04-30
---

# ADR-0030: Policy on Operations vs MCP-style hints — JustSearch's deliberate divergence

## Status

Accepted (2026-04-30). Documents the deliberate divergence from MCP
2025-06-18's tool-annotation discipline made by tempdoc 429 (revision 2)
when designing JustSearch's `Operation` registry primitive. The
divergence affects how the FE shell and agent loop trust and enforce
operation metadata declared by the backend.

## Context

Tempdoc 429 unifies the previously-separate `AdminAction` (UI-invocable)
and `ToolDefinition` (LLM-invocable) concepts into a single `Operation`
primitive consumed by multiple executors (UI button, LLM tool call, CLI
affordance). The primitive's design borrows from the MCP three-primitive
split (Tools / Resources / Prompts) per the MCP 2025-06-18
specification.

MCP's `Tool` schema includes a `ToolAnnotations` object with
`readOnlyHint`, `destructiveHint`, `idempotentHint`, and `openWorldHint`
fields. The MCP spec's wording, verbatim
(`schema/2025-06-18/schema.ts:884` and surrounding):

> "All properties in ToolAnnotations are **hints**... Clients should
> never make tool use decisions based on ToolAnnotations received from
> untrusted servers."

The MCP design choice is principled: an MCP server is potentially
adversarial relative to the client (a remote, third-party-authored
server invoked from a user's editor). Trusting server-declared metadata
about destructiveness or read-only-ness would let a malicious server
mislead the client into auto-approving harmful operations.

JustSearch's `Operation` primitive carries similar metadata but treats
it as **policy** (load-bearing, enforced) rather than **hints**
(advisory, not-trusted):

```java
public record OperationPolicy(
    RiskTier risk,                          // LOW / MEDIUM / HIGH
    ConfirmStrategy confirm,                // NONE / INLINE / TYPED
    Optional<String> confirmTextKey,        // i18n key for typed-confirm literal
    AuditPolicy audit,                      // NONE / METADATA_ONLY / FULL_PAYLOAD
    RetryPolicy retry,
    Optional<Duration> rateLimit,
    Set<RequiredCapability> capabilities
) {}
```

The shell's `OperationExecutor` reads these fields and enforces them
(routes high-risk operations to confirmation modals, logs audit-required
operations, throttles rate-limited operations, gates capability-required
operations). This is exactly the trust pattern MCP rejects.

The decision below documents why this divergence is correct for
JustSearch's threat model and how it composes with MCP discipline at the
plugin-tier boundary.

## Decision

**JustSearch Operations carry enforceable policy fields, NOT
untrusted hints.** The `OperationPolicy` record's fields are read by
the shell's executor and used to make trust decisions (auto-approve,
prompt-for-confirmation, refuse-without-capability, etc.).

The divergence is justified by **threat model**, not preference:

1. **Single trust domain in V1.** JustSearch is a local-first desktop
   app. The backend (Head process) and FE (shell + bundled JS/CSS)
   ship as a single distribution; both come from the same source under
   the same publisher's signature (the `374` packaging chain).
   The FE has no reason to distrust backend-declared operation
   metadata because the backend is part of the same install.
2. **No remote MCP-server-style threat in V1.** JustSearch does not
   load operations from third-party network sources in V1. There is
   no untrusted operation-publisher analogous to an MCP server.
3. **Plugin-tier boundary handled separately.** Plugins (per
   `421-stack.md §Plugin trust model`) are the place where MCP-style
   trust concerns DO arise. The trust-tier-aware executor (per 429
   §A.5) composes with MCP discipline at that boundary — see
   "Composition with MCP discipline" below.

The divergence is **deliberate and bounded** — it applies to
core-tier and trusted-plugin-tier operations only. Untrusted plugin
operations follow MCP discipline.

## Composition with MCP discipline

The shell's `OperationExecutor` dispatches per the operation's
`Provenance.tier` (per 429 §A.5):

```java
public final class OperationExecutor {
  public OperationResult dispatch(Operation op, JsonNode args) {
    return switch (op.provenance().tier()) {
      case CORE -> dispatchCore(op, args);                     // policy enforced as declared
      case TRUSTED_PLUGIN -> dispatchTrustedPlugin(op, args);  // policy enforced; V1 == CORE
      case UNTRUSTED_PLUGIN -> dispatchUntrustedPlugin(op, args);
                                                                // policy treated as untrusted hints (MCP discipline)
    };
  }
}
```

- **CORE** (operations shipped with JustSearch itself): policy
  fields enforced verbatim. The user trusts the install; the install
  trusts its own declarations.
- **TRUSTED_PLUGIN** (V1 plugins per `421-stack.md` — "you wrote it,
  or you know who did"): policy fields enforced, equivalent to CORE
  in V1. V1.5+ may add a policy floor (e.g., risk minimum lifted to
  MEDIUM regardless of declaration) without changing the API.
- **UNTRUSTED_PLUGIN** (V1.5+ sandboxed marketplace plugins flagged
  `untrusted: true` per the plugin manifest): **policy fields treated
  as untrusted hints per MCP discipline**. The executor:
  - Forces `confirm: TYPED` regardless of declared confirm strategy.
  - Forces `audit: FULL_PAYLOAD` for forensic traceability.
  - Rejects the operation if any `RequiredCapability` is declared
    (untrusted plugins cannot self-grant capabilities).
  - Treats `rateLimit` as a maximum rather than a request.

The UNTRUSTED_PLUGIN branch is the MCP-aligned tier. JustSearch
adopts MCP discipline where MCP's threat model applies (untrusted
publisher) and diverges where it doesn't (trusted single distribution).

## Consequences

**Positive:**

- **Operation policy is load-bearing**, not documentation. The shell
  enforces declared risk, confirm, audit, and rate-limit on
  invocation; tests verify the enforcement.
- **Single declaration source for UI + agent invocation.** An
  Operation declared once is consumed by the UI button, the agent
  tool emitter (per 429's `AgentOperationEmitter`), and any future
  CLI affordance. The policy is enforced uniformly across executors.
- **MCP compatibility preserved at the plugin boundary.** Untrusted
  plugins follow MCP-style hint-not-policy discipline. Future MCP
  interop (e.g., JustSearch as an MCP server, or JustSearch consuming
  MCP servers) can apply UNTRUSTED_PLUGIN-tier handling at that
  boundary without redesign.
- **Threat model is explicit.** A future contributor reading the
  executor sees three branches with distinct trust treatments and
  can reason about which tier their new operation falls into.

**Negative (accepted):**

- **The divergence from MCP must be documented and re-justified
  whenever JustSearch's threat model evolves.** Specifically: if
  JustSearch ever loads operations from third-party network sources
  in a way analogous to MCP-server consumption, the CORE/TRUSTED tier
  handling needs revisiting. ADR amendment required at that point.
- **Plugin authors face two distinct enforcement models** depending
  on their tier. Documentation in `421-extensibility.md §Plugin
  contract specification` must clarify which fields are enforced in
  which tier.
- **MCP-fluent readers may expect hints-only behavior** and be
  surprised that policy fields enforce. Mitigated by this ADR being
  the canonical reference.

## Alternatives considered

### Adopt MCP hints-only discipline uniformly

Apply MCP's "policy is untrusted hints" rule across all tiers,
including CORE. The executor would never auto-approve based on
declared `risk: low`; every operation would route through the
confirmation flow.

Rejected because:

- **It would degrade core operations' UX without security benefit.**
  Auto-approving `core.ping-backend` (a read-only health check shipped
  with JustSearch) requires no trust assumption beyond "the user
  trusts their own install."
- **MCP's reasoning doesn't apply.** MCP servers are remote, third-
  party, anonymously-authored. JustSearch core is none of those.
  Importing MCP discipline where the threat doesn't exist is cargo-
  culting.
- **It would push enforcement into per-operation client code.** The
  shell would need bespoke approval logic for each core operation
  rather than a centralized executor reading declared policy.

### Adopt enforcement uniformly across tiers (no UNTRUSTED branch)

Apply policy enforcement to all operations regardless of tier; trust
plugin-declared policy.

Rejected because:

- **Untrusted plugins genuinely should not self-grant capabilities or
  bypass confirmation.** This is the same threat MCP defends against.
  V1.5+ marketplace plugins are precisely the tier where MCP-style
  discipline applies.
- **The trust-tier executor's three branches are cheap to implement
  and easy to reason about.** Collapsing them would lose the explicit
  threat-model differentiation.

### Defer the policy-vs-hints decision

Ship Operations with policy fields but leave enforcement TBD; let the
shell decide per-operation whether to enforce or hint.

Rejected because:

- **Implicit per-operation decisions are exactly the failure mode
  ADR-0023 (runtime context honesty) defends against.** The
  enforcement model must be explicit in the API contract, not inferred
  by the shell's runtime behavior.
- **Without an enforcement decision, plugin authors cannot design
  against the contract.** They need to know whether declaring
  `risk: low` is honored or merely advisory.

## References

- Tempdoc 429 (slice 1.2 admin-action registry, under the tempdoc 421
  frontend-destination-architecture series) §A.5 (Trust-tier executor)
  + §B.D (revision-2 reaffirmation that TRUSTED_PLUGIN is intentionally
  CORE-equivalent in V1, not dead code)
- Tempdoc 421 (frontend-destination-architecture stack)
  §Plugin trust model — V1/V1.5/V2 tiers
- MCP 2025-06-18: https://modelcontextprotocol.io/specification/2025-06-18/
  (Tool annotations as hints; Security Considerations)
- ADR-0023 — API responses declare runtime context (the honesty
  discipline this ADR composes with)
- ADR-0027 — MetricCatalog as the telemetry contract (the
  typed-catalog substrate Operation extends)
- Tempdoc 435 — Phase 0 cluster doc alignment (lists this ADR as a
  required adjacent update to the destination spec)
