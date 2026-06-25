---
title: "543 — Agent-Substrate Prior Art Survey (2024–2026)"
---

# 543 — Agent-Substrate Prior Art Survey (2024–2026)

**Question.** Does the eight-substrate kernel (Provenance, Scope, Action, HoverPreview, EvaluationContext, Effect Journal, Contribution Manifest, Workspace Profile) make "AI-as-third-party" slot in for free? An LLM agent would be just another contributor with a Manifest; its tool calls would be Actions producing Effects logged in the Journal.

**Method.** Read the canonical specs and post-2024 syntheses for MCP, App Intents, Claude Code, Cursor checkpoints, Goose, LangGraph/Temporal, Operator / Computer Use, agentic browsers, and the reversibility-research literature. Compare primitive shapes against the eight substrates. Output: alignment, gaps, design moves.

---

## 1. Model Context Protocol (MCP) — the canonical reference

MCP's three server-offered features map almost one-to-one to three of my substrates:

| MCP primitive | My substrate | Notes |
|---|---|---|
| **Tools** (functions for the model to execute) | **Action** | Both keyed by `name`, both carry JSON-Schema `inputSchema`. MCP added `outputSchema` for structured results — my Action's Effect union plays this role but with a closed enumeration. |
| **Resources** (URI-addressable context/data) | **EvaluationContext** + **Aggregate** | MCP Resources are an addressable, subscribe-able data plane; my EvaluationContext is a projected view of similar data per-AddressableKind. |
| **Prompts** (templated workflows) | **Action**+**Manifest** (no direct analog) | I have no parameterised user-facing workflow template separate from Action; MCP keeps these distinct. |

Client-offered features in MCP (`sampling`, `roots`, `elicitation`) are the server-to-host direction. **Elicitation** ([released 2025-06-18](https://modelcontextprotocol.io/specification/2025-06-18)) is structurally a server-initiated request: "host, render a form built from this JSON schema and return the user's response." This is the inverse of my Action and has no parallel in the eight substrates — it's the kernel acting as a *form host on behalf of the agent*. The Nov 2025 spec adds sampling-with-tools, OIDC discovery, icons metadata, and incremental scope consent ([2025-11-25 release notes](https://blog.modelcontextprotocol.io/posts/2025-11-25-first-mcp-anniversary/)).

**Tool annotations** (the closest MCP construct to my Provenance) are: `title`, `readOnlyHint`, `destructiveHint`, `idempotentHint`, `openWorldHint` ([spec §Tools](https://modelcontextprotocol.io/specification/2025-06-18/server/tools)). The spec warns these are *untrusted unless the server itself is trusted* — i.e., MCP has no signed-provenance plane at the protocol level. Cryptographic provenance is being added as a *separate* MCP server pattern (see the Agent Identity Protocol — [faalantir/mcp-agent-identity](https://github.com/faalantir/mcp-agent-identity) — which gives each agent a local RSA-2048 wallet and signs each tool call), and the W3C-PROV-over-MCP research line (PROV-AGENT, [arxiv:2508.02866](https://arxiv.org/pdf/2508.02866)) treats provenance as out-of-band metadata layered above MCP.

**Closed Effect union vs MCP freeform content.** MCP tool results are content blocks (`text`, `image`, `audio`, `resource_link`, `resource`) plus optional `structuredContent`. There is no closed taxonomy of *what the call did to the world* — that's left to prose in the response. My Effect union (noop / navigate / open-pane / close-pane / toast / invoke-operation) is strictly stronger for **kernel rendering and undo**, but strictly weaker as a *transport* — MCP can ship arbitrary bytes back to the model; my Effects can't. This isn't a flaw in either design; it reflects that MCP is a wire protocol and my substrate is a UI kernel.

**Compatibility verdict.** MCP `Tool` slots cleanly into a contribution Manifest as a kind. MCP `Resource` slots into EvaluationContext as a projector. MCP `Prompt` has no direct slot — likely needs a new Manifest kind. MCP `Elicitation` has no slot — it requires a kernel-rendered form host that the substrate doesn't currently expose.

## 2. Apple App Intents

App Intents is the clearest prior art for "contribution-manifest meets system-mediated invocation." An app declares types conforming to `AppIntent`, with parameters as `@Parameter`-wrapped properties, entity types via `AppEntity`, and predefined value sets via `AppEnum` ([Apple docs](https://developer.apple.com/documentation/appintents), [WWDC25 — Get to know App Intents](https://developer.apple.com/videos/play/wwdc2025/244/)). `AppShortcutsProvider` registers shortcuts that are discoverable system-wide even before first launch ([Superwall field guide](https://superwall.com/blog/an-app-intents-field-guide-for-ios-developers/)).

**Mapping.** App Intent ≅ my Action; `AppEntity` ≅ my Addressable; `AppEnum` ≅ a Scope-key value-set; `AppShortcutsProvider` ≅ Contribution Manifest. The 1000-phrases-per-app cap is App Intents' analog to a Manifest dependency budget.

**Provenance.** App Intents has *no multi-axis provenance*. Trust is granted at install time (the app is code-signed by a known developer; that's it). There's no per-intent tier/version/review axis the system exposes to users at invocation time. The mediation surface (Siri/Shortcuts/Spotlight) is the kernel-side, and it shows the app's name and icon — that's the whole trust display.

**Parameter declaration.** App Intents parameters are typed Swift properties with `IntentParameter` metadata, including resolution strategies (`needsValueDialog`, disambiguation prompts). This is parallel to my JSON-Schema `parameters` with `x-ui-renderer` hints, but App Intents has stronger native-typed binding because Swift types are first-class.

**Verdict.** App Intents validates the *declarative-action + system-mediated-invocation* shape. It does NOT validate multi-axis provenance — Apple punts that to the App Store signature. My Provenance is richer.

## 3. Claude Code's own architecture

Per [code.claude.com](https://code.claude.com/docs/en/overview), Claude Code composes:

- **CLAUDE.md** — persistent instructions, read each session. Pattern: contributor-owned config in the project tree.
- **Skills** — packaged repeatable workflows invoked by name (e.g. `/review-pr`, `/deploy-staging`). Mapping: ≅ a Manifest kind that bundles prompt+tools.
- **Subagents** — spawnable sub-sessions, optionally isolated to a worktree. Mapping: ≅ a Workspace Profile that scopes a delegated session.
- **Hooks** — shell commands fired before/after tool invocations (PreToolUse, PostToolUse, SessionStart, etc.). Mapping: ≅ Manifest lifecycle hooks (`onInstall`, `onActivate`) plus a *per-Action interceptor*, which I do not currently have.
- **Tools** (Read/Edit/Bash/Grep/etc.) and **MCP servers** — function-call surface. Mapping: ≅ Action handlers contributed by Manifests.

The interesting convergence: Anthropic's "skill + subagent + hook + MCP + memory" decomposition is structurally my "Manifest + Profile + lifecycle + Action + EvaluationContext." They settled on the same five buckets independently. The notable Claude-Code primitive I lack is **Hook-as-interceptor** — a contribution kind whose job is to *observe and gate* other Actions, not to ship its own. This is more powerful than `onInstall` lifecycle hooks because it inserts into the per-call path.

## 4. Cursor / Aider / Continue.dev / Copilot Workspace

**Cursor checkpoints.** Cursor's Agent mode "creates a checkpoint before every code edit" — a zip of pre-change file state stored in a hidden local directory, separate from git history ([Cursor checkpoints docs](https://cursor.com/changelog), [Steve Kinney's writeup](https://stevekinney.com/courses/ai-development/cursor-checkpoints)). Restoring a checkpoint resets all files to that conversation point; it's coarse-grained (whole-workspace) and conversation-anchored, not Action-level. Known 2.0 regression: checkpoints leaked across concurrent agents ([forum bug](https://forum.cursor.com/t/in-2-0-undo-checkpoint-is-not-agent-independent/139630)).

**Critical contrast.** Cursor's checkpoint is a *coarse snapshot*. My Effect Journal is a *fine-grained log of declared inverses*. The Cursor approach is robust (it captures all side-effects, including ones the agent didn't declare) but expensive (zip a workspace per turn) and untyped (you can't ask "show me the navigate effects from this session"). My approach is cheap and queryable but only correct if every Effect actually has a sound inverse. Both designs exist in the wild; mine is closer to the [Eunomia checkpoint-systems survey](https://eunomia.dev/blog/2025/05/11/checkpointrestore-systems-evolution-techniques-and-applications-in-ai-agents/)'s "logical-log" branch.

**Preview-before-execute.** Cursor, Continue, and Copilot Workspace converged on the *diff preview as the universal confirmation surface* — agent proposes changes, user sees a unified diff, accept/reject. My HoverPreview substrate is for hovering over Addressables, not for previewing an Effect before it's applied. **This is a real gap** (see §11).

## 5. LangGraph / Temporal — durable workflow primitives

LangGraph 1.0 ([October 2025](https://medium.com/@romerorico.hugo/langgraph-1-0-released-no-breaking-changes-all-the-hard-won-lessons-8939d500ca7c)) added checkpointing at every node execution; pair it with Temporal and you get [saga-style compensation](https://devopsvibe.io/en/blog/temporal-langgraph-reliable-agents) where each step has an explicit compensating action — "cancel a flight booking if the subsequent hotel booking fails" ([Kinde primer](https://www.kinde.com/learn/ai-for-software-engineering/ai-devops/orchestrating-multi-step-agents-temporal-dagster-langgraph-patterns-for-long-running-work/)).

The Temporal/saga "compensating activity per step" pattern is the direct analog of my Effect-with-derivable-inverse. Difference: Temporal compensations are *imperative code written per step*, mine are *derivable from the Effect kind*. Mine is more constrained — only works because the Effect union is closed. Temporal is more flexible — works for arbitrary side-effects but requires the developer to write the inverse by hand.

There is no "Effect Journal" abstraction in either — they have *event histories* (Temporal) and *checkpoint snapshots* (LangGraph). Neither expresses "undo to before this Effect" as a first-class user operation; both express "resume from this point if the worker crashes." My Journal is closer to the "Reversible AI Systems" enterprise vision ([Raktim Singh, 2025](https://www.raktimsingh.com/reversible-ai-systems-enterprise-ai-undo-button/)) than to the durable-workflow lineage.

## 6. Block Goose

Goose is an open-source MCP-native agent framework in Rust ([Block's Goose architecture](https://block.github.io/goose/docs/goose-architecture/extensions-design/)). Its **Extension** primitive is the closest external thing to my Manifest: "any component that can be operated by an AI agent; extensions expose their capabilities through Tools and maintain their own state." Tools are `async fn(Value) -> AgentResult<Value>`. Goose ships extensions for 70+ MCP servers ([Decision Crafters writeup](https://www.decisioncrafters.com/goose-open-source-ai-agent/)).

**Mapping.** Goose Extension ≅ my Manifest, narrower (Tools only, no UI Action kinds, no Scope contributions, no HoverPreview). Goose treats the agent's tool list as flat — there's no Workspace-Profile-like grouping. **Goose validates the Manifest-as-contribution-unit shape but stops short of multi-kind contributions.**

## 7. Agentic browsers — Arc / Dia / Comet / Atlas

The [agentic-browser-showdown 2025](https://www.kiadev.net/news/2025-11-16-agentic-browser-showdown-2025-atlas-copilot-dia-comet) and [Roger Wong's analysis](https://rogerwong.me/2025/11/the-ai-browser-wars-what-comet-atlas-and-dia-reveal-about-designing-for-ai-first-experiences) show the converged UX pattern:

- **Side-panel agent presence** — the chrome dedicates a column to "agent state" (current intent, pending preview).
- **Two-track autonomy** — chat-with-the-page (read-only, no preview needed) vs. agent-acts-on-the-page (preview + confirm).
- **Confirmation gating sensitive steps** — "FillApp and Claude explicitly confirm before critical steps; ChatGPT Agent asks permission for anything high-impact" ([FillApp survey](https://fillapp.ai/blog/the-state-of-ai-browser-agents-2025)).
- **Dia's conservative stance** — deliberately *omits* open-ended DOM automation; agents can run "Skills" but not arbitrary clicks ([Atlas/Neon/Comet/Dia comparison](https://o-mega.ai/articles/agentic-browsers-in-2025-atlas-neon-comet-dia-full-comparison)).

The "I'm about to do X" signal in all four browsers is **a rendered preview of the next Effect** (filled form, draft email, planned navigation) with explicit Accept/Modify/Cancel. My HoverPreview is about *hovering over data*, not *previewing a planned action*. These are different surfaces. The browsers also surface an *agent activity log* — a chronological list of completed steps with rollback affordances — which is closer to my Effect Journal but typically not user-undoable per-step.

## 8. Permission / capability models

OpenAI Operator / ChatGPT Agent: "Operator should ask for approval before finalizing any significant action, such as submitting an order or sending an email … pauses and hands control back to the user for logins and CAPTCHA" ([OpenAI launches Operator, MIT Tech Review](https://www.technologyreview.com/2025/01/23/1110484/openai-launches-operator-an-agent-that-can-use-a-computer-for-you/)). Anthropic Computer Use is similar but more permissive. Neither exposes a *typed capability grant* — confirmation is per-step, not per-capability-bundle.

VS Code's Language Model Tools API ([docs](https://code.visualstudio.com/api/extension-guides/ai/tools)) is contribution-manifest-shaped: extensions declare tools in `contributes.languageModelTools` in `package.json`. This is the same shape as my Manifest, scoped to LM tools. The Nov 2025 Chat Provider API extends it ([VS Code v1.104 blog](https://code.visualstudio.com/blogs/2025/10/22/bring-your-own-key)).

MCP's Nov 2025 spec adds **incremental scope consent** ([anniversary post](https://blog.modelcontextprotocol.io/posts/2025-11-25-first-mcp-anniversary/)) — capabilities grown over the lifetime of a session, not granted all-at-once. My Workspace Profile is install-time-fixed; this is a gap (see §11).

## 9. Multimodal action systems

Operator / Computer Use / Adept's earlier ACT-1 abstract *at the pixel/DOM level*. The agent's "action" is `click(x,y)`, `type(text)`, `scroll(amount)`. There is no `(Addressable, Scope, Action)` triple — there is only `(screen-coords, key-event)`. This is the strongest argument that my closed Effect union is **too high-level for general-purpose agent integration**. A computer-use agent can't express its work as my six Effects; it needs an `invoke-operation` Effect with arbitrary payloads, which collapses the union back to "anything goes."

But for my domain (a local search app, not a web browser), the (Addressable, Scope, Action) triple is the *correct* abstraction because the surface is bounded. The agent can `navigate(documentId)`, `search(query, corpusScope)`, `open-pane(filterId)` and that covers ~90% of useful agent actions. Computer-use-style pixel control would be off-domain.

## 10. Reversibility research

Two 2025 papers move past the saga pattern. *Learning to Undo: Rollback-Augmented RL with Reversibility Signals* ([arxiv:2510.14503](https://arxiv.org/pdf/2510.14503)) trains a measure of how likely a state is to be reversible within a horizon and uses it for selective rollback. *Do-Undo: Generating and Reversing Physical Actions in VLMs* ([arxiv:2512.13609](https://arxiv.org/pdf/2512.13609)) treats reversibility as a learned property of action–world pairs.

The enterprise-architecture position ([Reversible AI Systems](https://www.raktimsingh.com/reversible-ai-systems-enterprise-ai-undo-button/), 2025) is the clearest match to my Effect Journal: *"logs, permissions, state tracking, compensating actions, approvals, versioning, audit trails, rollback plans, escalation paths, human review"* as the architectural property bundle. Note "compensating actions" is plural and explicit, mirroring my derivable-inverse-per-Effect.

**Bottom line for reversibility.** No production agent system implements a *user-visible Effect Journal with declared inverses* as a kernel primitive. Cursor uses snapshots. Temporal uses imperative compensations. LangGraph uses checkpoints. My substrate is closer to the academic / enterprise-architecture vision than to shipped agent products. This is a positive signal — the substrate is a real *design move past the field's current state*.

---

## Where my substrate set is already aligned

- **Action ↔ MCP Tool / App Intent / VS Code LM Tool / Goose Tool / Claude Code Tool.** JSON-Schema parameters with typed metadata is the universal converged shape. My `x-ui-renderer` extension is consistent with MCP's `_meta` and VS Code's tool metadata.
- **Manifest ↔ Goose Extension / App Intents `AppShortcutsProvider` / Claude Code Skill / VS Code `package.json` contributions.** Declarative bundle of contributions is the universal converged shape.
- **EvaluationContext ↔ MCP Resources.** Both addressable, both projected, both subscribe-capable in principle.
- **Workspace Profile ↔ Claude Code subagent isolation + CLAUDE.md scoping.** Set-of-manifests-plus-config-snapshot is independently invented.
- **Effect Journal philosophy ↔ Saga compensation / enterprise reversibility architecture.** The *idea* is well-established; the *kernel-owned, cross-session-persistent, per-Effect-typed* shape is rarer than I expected.
- **Provenance multi-axis ↔ MCP tool annotations + AIP cryptographic provenance + PROV-AGENT.** The trend is unmistakably toward multi-axis trust signals; my Provenance anticipates it.

## Where my substrate set has a gap

- **No `originator` field on Effects/Actions.** All the surveyed agent UX patterns (Operator, agentic browsers, Cursor) distinguish *user-initiated* vs *agent-initiated* in the activity log and the confirmation flow. My Effects carry no such tag. The Journal can't currently answer "show me what the agent did this session" vs "what I did." Concrete fix: add `originator: USER | AGENT | SYSTEM` plus optional `agentId` to Effect.
- **No preview-of-pending-Effect surface.** HoverPreview is for hovering over Addressables, not previewing a planned Effect. The agentic-browser convention is a kernel-rendered "I'm about to do X — Accept / Modify / Cancel" card. Concrete fix: a `PendingEffect` substrate that pairs an Effect with a renderable preview and an explicit confirmation gate. Needed for `destructive` and `openWorld` Effects.
- **No elicitation / form-host substrate.** MCP's Elicitation (server asks the user a JSON-Schema-formed question mid-session) has no analog. Without it, agents that need clarification must round-trip through chat. Concrete fix: an `Elicitation` Action kind whose handler renders a kernel-owned form and returns the structured response.
- **Workspace Profile is install-time-fixed.** MCP's Nov-2025 incremental scope consent is the right direction. My Profiles can't grow capabilities mid-session without the user editing the profile. Concrete fix: per-Manifest *capability grants* layered on Profiles, with grant/revoke as Effects in the Journal.
- **No Hook-as-interceptor contribution kind.** Claude Code's PreToolUse/PostToolUse hooks intercept per Action, not just at lifecycle boundaries. My Manifest lifecycle hooks (`onInstall`, etc.) are coarser. Concrete fix: an `Interceptor` contribution kind that gates / wraps Actions.
- **No `Prompt` (template) Manifest kind.** MCP keeps Prompts separate from Tools; I don't. For agent-as-contributor, the agent's persona prompts, system messages, and reusable templates need a home.
- **Effects can't transport arbitrary content.** MCP tool results carry text/image/audio/resource blobs back to the model. My `invoke-operation` Effect is opaque from the kernel's view. For agent results that need to render in the chrome (e.g., a search result preview), there's no typed Effect for "show this structured content."

## Where the field has moved past my design

- **Cryptographic signed provenance per Action invocation** ([AIP](https://github.com/faalantir/mcp-agent-installer), [PROV-AGENT](https://arxiv.org/pdf/2508.02866)). My Provenance is install-time metadata. The field is moving toward per-invocation signatures forming a tamper-evident chain. **Design move:** Provenance should be extensible to carry a per-Effect-instance signature, not just a per-contributor cert.
- **Incremental / scoped capability grants.** MCP, Operator, and VS Code agent mode have all converged on growing capability over session lifetime. **Design move:** Workspace Profile gains a runtime *capability ledger* — grants are Effects, revocations are Effects, the journal becomes auditable consent history.
- **The diff-preview as universal Effect gate.** Cursor/Continue/Copilot Workspace have made the unified-diff preview the standard "agent confirms before applying" surface. My HoverPreview is the wrong substrate for this. **Design move:** introduce `PendingEffect` (queued, not yet applied, with kernel-rendered preview) as a first-class state, and require it for any Effect whose Provenance is `originator=AGENT` and Annotation is `destructive` or non-`readOnly`.
- **Subagent isolation as a Profile primitive.** Claude Code's worktree-isolated subagents demonstrate that "Profile" needs a *spawn-child-Profile* operation. My Profiles are flat. **Design move:** Profile gains parent/child with inherited Manifests and child-overridable Scope — formalising what Claude Code does ad-hoc.
- **Closed Effect union may be too narrow for one role.** For UI Effects (navigate, open-pane, toast) it is right. For *data-returning* agent operations (a search call that returns 20 hits the chrome should render) `invoke-operation` is a black hole. **Design move:** split Effect into `UIEffect` (closed union, kernel-rendered, undoable) and `DataEffect` (typed result that flows into EvaluationContext, not directly user-undoable but journaled).
- **Elicitation as a first-class protocol move.** MCP's Elicitation primitive is the strongest indicator that "agent asks the user a structured question" deserves its own slot. None of my substrates host this cleanly.

---

## Closing assessment of the original claim

The claim "AI-as-third-party slots in for free as another contributor with a Manifest" is **directionally correct but materially incomplete**. The Manifest/Action/EvaluationContext/Profile/Journal axis is the right four-out-of-five. The missing pieces are: an `originator` axis on Effects, a `PendingEffect`/preview-gate state, an Elicitation slot, runtime capability grants, and a split between UI Effects (rendered+undoable) and data Effects (typed results into context). With those five additions the substrate set genuinely subsumes MCP, App Intents, VS Code LM Tools, and Goose Extensions as instances — without them, agent integration would require ad-hoc bypasses around the Effect union and Manifest immutability.

The substrate set's strongest position is **reversibility** — no shipped agent system has a typed, declared-inverse, cross-session Effect Journal. Cursor has snapshots, Temporal has imperative compensations, LangGraph has node checkpoints; none has my shape. The 2025 academic and enterprise-architecture literature is pointing where my substrate already is.

## Sources

- [Model Context Protocol — Tools spec (2025-06-18)](https://modelcontextprotocol.io/specification/2025-06-18/server/tools)
- [Model Context Protocol — Spec overview (2025-11-25)](https://modelcontextprotocol.io/specification/2025-11-25)
- [MCP 1st anniversary post — Nov 2025 release](https://blog.modelcontextprotocol.io/posts/2025-11-25-first-mcp-anniversary/)
- [Apple — App Intents documentation](https://developer.apple.com/documentation/appintents)
- [WWDC25 — Get to know App Intents](https://developer.apple.com/videos/play/wwdc2025/244/)
- [Superwall — App Intents field guide for iOS developers](https://superwall.com/blog/an-app-intents-field-guide-for-ios-developers/)
- [Claude Code — overview docs](https://code.claude.com/docs/en/overview)
- [Cursor — changelog](https://cursor.com/changelog) and [Steve Kinney — Cursor checkpoints](https://stevekinney.com/courses/ai-development/cursor-checkpoints)
- [Eunomia — Checkpoint/Restore Systems survey (May 2025)](https://eunomia.dev/blog/2025/05/11/checkpointrestore-systems-evolution-techniques-and-applications-in-ai-agents/)
- [DevOpsVibe — Reliable AI Agents with Temporal and LangGraph](https://devopsvibe.io/en/blog/temporal-langgraph-reliable-agents)
- [LangGraph 1.0 release writeup (Oct 2025)](https://medium.com/@romerorico.hugo/langgraph-1-0-released-no-breaking-changes-all-the-hard-won-lessons-8939d500ca7c)
- [Kinde — orchestrating multi-step agents (Temporal/Dagster/LangGraph)](https://www.kinde.com/learn/ai-for-software-engineering/ai-devops/orchestrating-multi-step-agents-temporal-dagster-langgraph-patterns-for-long-running-work/)
- [Block — Goose Extensions Design](https://block.github.io/goose/docs/goose-architecture/extensions-design/)
- [Decision Crafters — Goose overview](https://www.decisioncrafters.com/goose-open-source-ai-agent/)
- [Agentic-browser showdown 2025](https://www.kiadev.net/news/2025-11-16-agentic-browser-showdown-2025-atlas-copilot-dia-comet)
- [Roger Wong — AI Browser Wars (Nov 2025)](https://rogerwong.me/2025/11/the-ai-browser-wars-what-comet-atlas-and-dia-reveal-about-designing-for-ai-first-experiences)
- [O-Mega — Atlas vs Neon vs Comet vs Dia comparison](https://o-mega.ai/articles/agentic-browsers-in-2025-atlas-neon-comet-dia-full-comparison)
- [FillApp — State of AI Browser Agents 2025](https://fillapp.ai/blog/the-state-of-ai-browser-agents-2025)
- [OpenAI — Introducing Operator](https://openai.com/index/introducing-operator/) and [MIT Technology Review coverage](https://www.technologyreview.com/2025/01/23/1110484/openai-launches-operator-an-agent-that-can-use-a-computer-for-you/)
- [VS Code — Language Model Tool API](https://code.visualstudio.com/api/extension-guides/ai/tools)
- [VS Code — Chat Participant API](https://code.visualstudio.com/api/extension-guides/ai/chat)
- [VS Code v1.104 — Bring Your Own Key (Oct 2025)](https://code.visualstudio.com/blogs/2025/10/22/bring-your-own-key)
- [faalantir/mcp-agent-identity — Agent Identity Protocol](https://github.com/faalantir/mcp-agent-identity)
- [PROV-AGENT — Unified Provenance over MCP (arxiv:2508.02866)](https://arxiv.org/pdf/2508.02866)
- [Securing MCP — Risks, Controls, Governance (arxiv:2511.20920)](https://arxiv.org/pdf/2511.20920)
- [Raktim Singh — Reversible AI Systems (2025)](https://www.raktimsingh.com/reversible-ai-systems-enterprise-ai-undo-button/)
- [Learning to Undo — Rollback-Augmented RL (arxiv:2510.14503)](https://arxiv.org/pdf/2510.14503)
- [Do-Undo — Reversing Physical Actions in VLMs (arxiv:2512.13609)](https://arxiv.org/pdf/2512.13609)
- [Partnership on AI — Real-Time Failure Detection in AI Agents (Sep 2025)](https://partnershiponai.org/wp-content/uploads/2025/09/agents-real-time-failure-detection.pdf)
