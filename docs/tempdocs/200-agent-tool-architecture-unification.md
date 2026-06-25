---
title: "Agent Tool Architecture — Research & Cross-System Reference"
status: done
created: 2026-02-12
updated: 2026-02-13
origin: conversation between 186 agent and 187 agent, prompted by dual-maintenance concern
verdict: keep dual system (provisional — key assumptions untested)
---

> NOTE: Noncanonical doc (research). May drift. Verify against docs/explanation + docs/reference + code.

# 200: Agent Tool Architecture — Research & Cross-System Reference

This document is the single reference for AI tool ecosystem research, cross-system comparison, and validation experiments. Implementation details live in their respective tempdocs:
- **Tempdoc 186** — Built-in agent implementation (Java, in-JVM)
- **Tempdoc 187** — Production MCP server implementation (TypeScript, stdio)

---

## Agent Handoff (2026-02-12)

### What was done

1. **Research complete**: Evaluated 4 unification options (A-D) across 27 research questions. All rejected. Provisional verdict: keep dual system. See §Option Verdicts and §Confidence Assessment below.

2. **Agent SSE bug fixed** (committed `ada82076`): `AgentController.handleRunStream()` was wrapping `runAgent()` in `CompletableFuture.runAsync()`, causing Javalin to close the response before SSE events could be written. Now runs synchronously on the handler thread.

3. **BrowseTool root sentinels** (committed `22add3ff`): Qwen3-4B sends `/`, `.`, `root`, etc. when it means "top-level." Added `ROOT_SENTINELS` set to map these to null. Also improved description with cross-tool reference.

4. **Experiment 1 deployed** (committed `61c68be3`): `SearchTool.description()` replaced with a richer version adapted from the MCP server. Original preserved in a comment for rollback. See the comment in `SearchTool.java:69-76`.

5. **Tempdoc 200 written** (committed `8f811269`): This document — full research findings, verdicts, confidence assessment, validation experiments.

6. **Cross-references** (committed `75caf26b`): Updated tempdocs 186 (SSE bug + "Next" section), 187 (synergy section), 198 (cross-refs).

### What's blocked

**Experiment 1 cannot be evaluated** because the Agent UI doesn't show tool call arguments. When the user tested "configuration and the SSOT," the agent returned 4 relevant results — but we can't see whether the LLM chose `mode: "hybrid"` or `path_prefix` because those details aren't surfaced. See §Prerequisite: Agent Observability Gap below.

### Recommended next steps (in priority order)

1. **Unblock observability** — Pick one of three options documented in §Prerequisite below:
   - *Cheapest*: Add `LOG.info("Tool call: {} args={}", name(), argumentsJson)` to each tool's `execute()` method, then check backend logs during testing
   - *Best long-term*: Extend the SSE stream in `AgentController` to include tool call arguments (the `AgentEvent` already carries tool results; adding the input JSON is small)
   - *Workaround*: Design prompts where success proves parameter usage (e.g., "search only in /docs folder" → results from /docs means `path_prefix` was used)

2. **Run Experiment 1** — With observability in place, test 5-10 prompts with the agent. Key prompts to try:
   - "show me what's indexed" (tests tool selection: should pick browse_folders)
   - "find markdown files about configuration" (tests path_prefix and mode usage)
   - "list the top-level folders" (tests BrowseTool root sentinel fix)
   - "search for documents about X in the Y folder" (tests path_prefix)
   - Compare tool call arguments against what the rich description suggests

3. **Run Experiment 2** (if Experiment 1 is conclusive) — Implement minimal `SuggestTool` and `StatusTool` (< 100 lines each), test whether the agent uses them. See §Experiment 2.

4. **Finalize verdict** — Update this tempdoc's status from `provisional` to `resolved` based on experiment outcomes. If rich descriptions help, revisit the dual-system verdict.

### Key files

| File | State | Notes |
|------|-------|-------|
| `modules/app-agent/src/main/java/io/justsearch/agent/tools/SearchTool.java` | Modified (experiment active) | Rich description deployed. Original in comment at line 69-76. Rollback = restore those 3 lines. |
| `modules/app-agent/src/main/java/io/justsearch/agent/tools/BrowseTool.java` | Modified (fix shipped) | ROOT_SENTINELS added. No rollback needed. |
| `modules/ui/src/main/java/io/justsearch/ui/api/AgentController.java` | Modified (fix shipped) | SSE synchronous fix. No rollback needed. |
| `docs/tempdocs/186-llm-agentic-file-operations.md` | Updated | Implementation record for built-in agent. |
| `docs/tempdocs/187-production-mcp-server.md` | Updated | Implementation record for MCP server. |

---

## Foundational Research

### Local Model Tool Calling

> Originally 186 §1. Moved here 2026-02-13.

#### Key question

Can llama-server (JustSearch's inference engine) support function calling, and which local models do it reliably?

#### llama-server native tool calling

**Yes — llama-server fully supports OpenAI-compatible tool calling.** The `/v1/chat/completions` endpoint accepts `tools` and `tool_choice` parameters. Internally, it uses **grammar-based constraints with lazy grammars activated by trigger words**: output remains unconstrained until model-specific trigger patterns are detected, then a GBNF grammar constrains token generation to match the function's JSON schema. This ensures syntactically valid tool calls without post-processing.

Implementation merged January 2025 ([PR #9639](https://github.com/ggml-org/llama.cpp/pull/9639)). Streaming tool calls supported as of May 2025.

**Requirements**: Must start llama-server with `--jinja` flag for tool template support. Can verify model support via `http://localhost:<port>/props` (check for `chat_template_tool_use`).

#### Request format

The `tools` parameter follows the OpenAI function-calling schema. Multiple tools can be provided — the model decides which to call (or none).

```json
{
  "model": "qwen2.5-14b",
  "tools": [
    {
      "type": "function",
      "function": {
        "name": "search_index",
        "description": "Search the knowledge index for documents matching a query",
        "parameters": {
          "type": "object",
          "properties": {
            "query": { "type": "string", "description": "Search query" },
            "limit": { "type": "integer", "description": "Max results" }
          },
          "required": ["query"]
        }
      }
    }
  ],
  "messages": [
    { "role": "user", "content": "Move all PDFs from Downloads to Archive/2024" }
  ]
}
```

Response uses `finish_reason: "tool_calls"` with a `tool_calls` array containing the function name and JSON arguments. The model may call one tool, multiple tools, or respond with text if no tool is needed.

#### Models with native tool-call templates

| Model | Sizes | Tool-Use Quality | Notes |
|-------|-------|-----------------|-------|
| **Qwen 2.5** | 7B, 14B, 72B | Good–Excellent | Dedicated `chat_template_tool_use`. JustSearch already ships Qwen models. |
| **Llama 3.1/3.2/3.3** | 8B, 70B | Good–Excellent | Includes built-in tools (wolfram_alpha, brave_search, code_interpreter). |
| **Mistral Nemo** | 12B | Good | Uses `[TOOL_CALLS]` trigger. |
| **Functionary v3.1/v3.2** | 7B, 70B | Excellent | Purpose-built for function calling. |
| **Hermes 2/3** | Various | Good | Uses `<tool_call>` trigger. Qwen-based variants available. |
| **Command R7B** | 7B | Good | — |
| **DeepSeek R1** | Various | WIP | Marked "reluctant to call tools" in llama.cpp docs. |

Models without native templates fall back to a generic JSON-schema-based handler (works but consumes more tokens and is less efficient).

#### Grammar-constrained JSON vs native tool-use

JustSearch already has `JsonGrammarGuard` (`modules/ai-bridge/.../JsonGrammarGuard.java`) for structured output via regex-based token filtering. llama-server's grammar-based function calling is a superset of this — it constrains output to match the function parameter JSON schema at the token level. Native tool-use is strictly better: it produces valid JSON guaranteed to match the schema, uses model-specific templates for efficiency, and requires no custom prompt engineering.

#### Reliability by model size

| Tier | Example Models | Single Tool Call | Multi-Step Plans | Parallel Calls |
|------|---------------|-----------------|-----------------|----------------|
| **7B** | Qwen 2.5-7B | Reliable | Fragile | Unreliable |
| **12–14B** | Mistral Nemo, Qwen 2.5-14B | Reliable | Good (2–3 steps) | Works with validation |
| **70B+** | Llama 3.3-70B | Excellent | Excellent | Reliable |

#### KV quantization caveat

**Critical for JustSearch**: Extreme KV cache quantization (`-ctk q4_0`) substantially degrades tool calling performance per llama.cpp documentation. K cache is much more sensitive to quantization than V cache. JustSearch uses `-ctk q4_0 -ctv q4_0` for the 8GB VRAM tier (`VramFlagsUtil`), meaning tool-use quality on 8GB GPUs will be noticeably worse than on 12GB+.

#### Recommendation

| Rank | Approach | Fit |
|------|----------|-----|
| **1** | **llama-server `tools` param with Qwen 2.5-7B/14B** | Best. Zero new dependencies. Extend existing `OnlineModeOps.chatCompletion()` to include `tools` array. Add `--jinja` to server args. |
| 2 | Grammar-constrained JSON via extended `JsonGrammarGuard` | Fallback. Works with any model. More manual prompt engineering. |
| 3 | Functionary-v3.2 as dedicated tool-call model | Good quality but requires shipping a separate model. |

**Integration point**: `OnlineModeOps` at `modules/app-inference/.../OnlineModeOps.java` — the HTTP client that constructs `/v1/chat/completions` requests. Adding a `tools` array is a small change. Parsing `tool_calls` from the streaming response requires extending the SSE delta handler.

**References**: [llama.cpp function-calling.md](https://github.com/ggml-org/llama.cpp/blob/master/docs/function-calling.md), [PR #9639](https://github.com/ggml-org/llama.cpp/pull/9639)

---

### MCP Protocol State of the Art (Feb 2026)

> Originally 187 §2. Moved here 2026-02-13.

#### Spec Version

Latest: **2025-11-25** (one-year anniversary release). Governed by the Agentic AI Foundation (Linux Foundation), co-founded by Anthropic, Block, and OpenAI. Next version expected June 2026.

#### Core Primitives

| Primitive | Control Model | Our Use |
|-----------|--------------|---------|
| **Tools** | Model-controlled (AI decides when to invoke) | Primary: search, suggest, ingest, status |
| **Resources** | Application-controlled (client/user decides) | Secondary: expose indexed root folders, doc metadata |
| **Prompts** | User-controlled (templates) | Deferred: search prompt templates |

#### Transports

| Transport | Topology | Security | Best For |
|-----------|----------|----------|----------|
| **stdio** | 1:1, client manages server lifecycle | Process boundary isolation | Local tools, IDE integrations |
| **Streamable HTTP** | Many:1, server is independent | Requires Origin validation, auth | Remote/multi-tenant servers |

**For JustSearch**: stdio is the right default. The server is local-only, single-user, and clients (Claude Code, Cursor) already expect stdio MCP servers. Streamable HTTP is a future option for multi-agent scenarios.

#### Key Protocol Features (2025-11-25)

- **Structured output**: Tools can declare `outputSchema` and return typed `structuredContent` alongside unstructured `content`
- **Tasks**: Experimental async primitive ("call-now, fetch-later") — relevant for long-running ingest operations
- **Capability negotiation**: Server declares what it supports during `initialize`
- **Tool annotations**: Behavioral metadata (`readOnlyHint`, `destructiveHint`, `idempotentHint`, `openWorldHint`)

#### Client Ecosystem

| Client | MCP Support | Notes |
|--------|------------|-------|
| Claude Code | Native | stdio, `claude mcp add` |
| Claude Desktop | Native | stdio, config JSON |
| Cursor | Full | `~/.cursor/mcp.json` |
| Windsurf | Full | Similar config model |
| VS Code (Copilot) | Extensions | MCP extension settings |
| JetBrains IDEs | Built-in (2025.2+) | IDE settings |
| ChatGPT (OpenAI) | Adopted March 2025 | Platform integration |
| Gemini (Google) | Adopted April 2025 | Platform integration |

All major clients support stdio transport. This confirms stdio as the right initial choice.

---

### Agentic Frameworks & Patterns

> Originally 186 §2. Moved here 2026-02-13.

#### Key question

Should JustSearch use an external agentic framework or build a lightweight agent loop? Which pattern (ReAct, Plan-then-Execute) fits best?

#### Pattern comparison

**ReAct (Reason + Act)**: The agent interleaves reasoning ("I should find all PDFs in Downloads") with actions (call search tool, then call move tool) in a loop: Thought → Action → Observation → repeat. Well-suited for dynamic tasks where the path to solution isn't obvious upfront. Requires the model to maintain coherent multi-turn reasoning. Reference: [ReAct paper](https://arxiv.org/abs/2210.03629), [Simon Willison's implementation](https://til.simonwillison.net/llms/python-react-pattern).

**Plan-then-Execute (P-t-E)**: The model generates a complete plan (list of all operations) in one pass, then the system executes after human approval. Separates strategic planning from tactical execution. The planner can be a large model while the executor is deterministic code — no LLM needed during execution.

| Aspect | ReAct | Plan-then-Execute |
|--------|-------|-------------------|
| Model calls | Many (every step) | One (planning only) |
| User visibility | Sees each step in real-time | Sees complete plan before execution |
| Adaptability | High — adjusts per step | Lower — follows predetermined plan |
| Model size needed | 14B+ for reliable multi-step | 7B+ for single plan generation |
| Safety | Each step needs approval or trust | One approval for entire plan |
| Best for | Discovery-heavy, multi-tool tasks | Single-tool calls with clear scope |

#### How other local tools handle agent loops

**Open Interpreter**: Converts natural language to executable code (Python, JS, shell). Shows code before execution, asks for explicit approval each step. No built-in undo mechanism — relies on the user understanding the generated code. Experimental Docker sandbox for isolation.

**Aider**: Uses **git as the undo mechanism**. Automatically commits every change with descriptive messages. Users can `git diff`, `git reset`, `git revert` to undo. Elegantly simple — leverages existing tooling. Reference: [Aider docs](https://aider.chat/docs/usage.html).

**Claude Code**: Plan Mode generates a read-only plan; user approves; execution proceeds with per-tool confirmations. Actions shown before execution. The "proposed actions" pattern with human-in-the-loop is exactly what we need.

**Goose (Block)**: Open-source dev agent with Interface → Agent → Extensions architecture. Runs commands and manages files locally. Uses MCP protocol for tool access.

**Key insight (2026 trend)**: Vercel found that reducing their agent's tools by 80% and providing filesystem access (`cat`, `ls`, `grep`) improved performance. Simple tools often beat complex abstractions. Reference: [LlamaIndex blog](https://www.llamaindex.ai/blog/did-filesystem-tools-kill-vector-search).

#### External frameworks evaluated

| Framework | Fit | Reason |
|-----------|-----|--------|
| **LangChain/LangGraph** | Poor | Python-only, cloud-centric. JustSearch is Java/TypeScript. Wrong ecosystem. |
| **Qwen-Agent** | Poor | Python, tightly coupled to Qwen models. |
| **llama-cpp-agent** | Low | Python library wrapping llama.cpp. Adds a Python dependency. |
| **Build custom** | Best | The core agent loop is ~20 lines. Complexity is in prompt engineering and error handling, not framework code. |

#### Recommendation

| Rank | Pattern | Fit |
|------|---------|-----|
| **1** | **Plan-then-Execute (single-shot plan generation)** | Best for v1. User sees complete plan before execution. Works with 7B+ models. One LLM call, then deterministic execution. |
| 2 | ReAct loop | v2 upgrade for discovery-heavy operations ("organize by topic" requiring index queries). Needs 14B+ models. |
| 3 | Hybrid (P-t-E strategic + ReAct tactical) | v3 for complex multi-phase operations. |
| 4 | External framework | Not recommended. Wrong language, unnecessary dependency. |

#### Proposed flow (general, tool-agnostic)

1. User instruction → Head builds prompt with available `tools` definitions
2. Head sends to llama-server via `POST /v1/chat/completions` with `tools` array
3. Model returns `tool_calls` (one or more function invocations with JSON arguments)
4. Head validates tool calls (schema check, permission check, safety check per tool)
5. UI presents proposed actions for approval (tool-specific rendering)
6. On approval, Head dispatches to the appropriate **tool handler** for execution
7. Tool handler returns results; if the agent loop continues (ReAct), results are fed back as observations
8. Side effects (e.g., index updates after file moves) are handled by each tool handler

---

### Model Requirements & VRAM Tier Gating

> Originally 186 §7. Moved here 2026-02-13.

#### Key question

What is the minimum model capability for reliable tool-use, and how should this interact with VRAM tiers?

#### VRAM tier mapping

| VRAM Tier | Model Size | Tool-Use Quality | Feature Status |
|-----------|-----------|-----------------|----------------|
| **12GB+** | 14B Q4_K_M | Good — reliable single-tool plans, handles 2–3 step reasoning | **Fully supported** |
| **8GB** | 7B Q4_K_M | Degraded — KV quantization (`-ctk q4_0`) hurts tool-use | **Supported with warning** ("Tool-use quality may be reduced") |
| **4GB** | Small models only | Poor — insufficient for reliable structured output | **Disabled** |
| **CPU-only** | Any (slow) | Interactive latency too high for agentic flows | **Disabled** |

**Implementation status**: VRAM gating is **deferred** — the agent is currently always available when `OnlineAiService` is up, regardless of VRAM tier.

#### Gating logic

Check VRAM tier from `VramFlagsUtil` (`modules/ai-bridge/.../VramFlagsUtil.java`). Gate the feature:
- 12GB+: enabled, full quality
- 8GB: enabled with UI warning about reduced quality
- 4GB / CPU: feature hidden or disabled with explanation

#### Model recommendation

For tool-use specifically, **Qwen 2.5-14B-Instruct** (Q4_K_M, ~8.5GB VRAM) is the sweet spot: native tool-call template, good structured output quality, fits in 12GB cards. For 8GB cards, **Qwen 2.5-7B-Instruct** (Q4_K_M, ~4.5GB VRAM) with the caveat that KV quantization degrades quality.

---

## Problem Statement

JustSearch has two independent systems that expose LLM-callable tools:

| System | Language | Transport | Tools | Consumer |
|--------|----------|-----------|-------|----------|
| **Built-in agent** (tempdoc 186) | Java (`ToolDefinition` SPI) | In-JVM callbacks | 4: search, browse, ingest, file_ops | Local Qwen3-4B via llama-server |
| **Production MCP server** (tempdoc 187) | TypeScript (Zod schemas) | stdio JSON-RPC → HTTP | 5: search, suggest, preview, ingest, status | External agents (Claude Code, Cursor, etc.) |

Both call the same backend services. Adding a new tool means implementing it twice. This research evaluated whether unification is feasible and worth it.

## Verdict: Keep Dual System

The two systems serve fundamentally different consumers. The actual duplication is small (~150 lines of schema overlap) and the maintenance cost is low (~190 lines per new tool across both, ~1-2 tools/quarter). Unification would add infrastructure complexity that doesn't pay for itself. See §Evaluation for the full rationale.

---

## Architecture (Unchanged)

```
                    ┌─────────────────────────────────────────┐
                    │         JustSearch JVM (Head)            │
                    │                                         │
  Agent UI ──SSE──▶ │  AgentController                        │
                    │    └─▶ AgentLoopService                 │
                    │          ├─▶ llama-server (Qwen3-4B)    │
                    │          └─▶ ToolRegistry                │
                    │               ├─ SearchTool ──callback──▶│──gRPC──▶ Worker
                    │               ├─ BrowseTool ──callback──▶│──gRPC──▶ Worker
                    │               ├─ IngestTool ──callback──▶│──gRPC──▶ Worker
                    │               └─ FileOpsTool ──callback──▶│──filesystem
                    │                                         │
  MCP client ─stdio─┼───────────────────────────────────────┐ │
    (Claude Code,   │                                       │ │
     Cursor, etc.)  │                                       ▼ │
                    │  ┌─────────────────────────────┐        │
                    │  │ Node.js MCP Server (stdio)  │        │
                    │  │  ├─ search ──HTTP POST──────▶│ REST API
                    │  │  ├─ suggest ─HTTP GET───────▶│ REST API
                    │  │  ├─ preview ─HTTP GET───────▶│ REST API
                    │  │  ├─ ingest ──HTTP POST──────▶│ REST API
                    │  │  └─ status ──HTTP GET───────▶│ REST API
                    │  └─────────────────────────────┘        │
                    └─────────────────────────────────────────┘
```

---

## Unification Research Findings

### R1–R5: Java MCP SDK (Option A viability)

| Question | Finding |
|----------|---------|
| R1. SDK maturity | v0.17.2 is production-ready. 3.2K GitHub stars, 59+ contributors, maintained by Anthropic/MCP org. |
| R2. JVM startup cost | ~250-300ms cold start per stdio session. Acceptable but worse than Node.js ~100ms. |
| R3. Connect to warm JVM | Possible — adapter can connect to running JustSearch via loopback HTTP, avoiding cold start. |
| R4. Structured output | Supported. `outputSchema` + `structuredContent` in `CallToolResult`. |
| R5. Tool annotations | Supported. `readOnlyHint`, `destructiveHint`, `idempotentHint`, `openWorldHint`. |
| R21. Transports | Stdio, SSE, Streamable-HTTP in core module. Spring WebFlux/WebMVC optional. |
| R22. In-process | Supported. `McpServer` can run in same JVM. Dual-serving (in-process + stdio) is architecturally possible. |
| R23. Dependency footprint | Lightweight. Core module needs only `jackson-annotations` for stdio. No Netty/Reactor. |
| R24. Production users | Growing adoption. Spring AI integration, LangChain4j, enterprise MCP servers. |

**Summary**: The Java SDK is technically capable. Option A is feasible from a tooling perspective.

### R6–R10: Node.js Dependency & Latency (Option B viability)

| Question | Finding |
|----------|---------|
| R6. Round-trip latency | Estimated ~5-10ms (Java → stdio → Node.js → HTTP loopback → Java). Negligible vs 2-10s LLM inference. |
| R7. Connection keep-alive | MCP stdio connections are persistent per-session. One connection, many tool calls. |
| R8. Node.js bundling | Not bundled by JustSearch today. Node.js SEA (Single Executable Apps) can compile to standalone exe. |
| R9. Process lifecycle | Standard child process management. Spawn on agent session start, kill on end. |
| R10. Preview capability | Going through HTTP limits to 4KB REST. gRPC path (200K chars) only available in-JVM. |
| R18. Tauri Node.js dependency | **None.** Tauri shell is pure Rust. Node.js used only at build time for frontend. |
| R20. Standalone compilation | **Node.js SEA recommended.** ~80-100MB binary, zero code changes, stable in Node 22. Bun experimental; pkg dead; Deno requires SDK port. |

**Summary**: Latency is fine. But adding Node.js dependency to a Tauri app for in-process tool calls is architecturally backwards.

### R11–R17: Schema Sharing (Options C/D viability)

| Question | Finding |
|----------|---------|
| R11. Zod from JSON Schema | **Not supported.** Zod v4 exports to JSON Schema but cannot import from it. No viable bridge library. |
| R12. Java ToolSchema format | Already standard JSON Schema (stored as string in `ToolSchema` record). Trivial to export. |
| R13. Schema % of duplication | ~30%. Java: 227 lines schema / 858 total. Execution logic is the other 70%. |
| R14. Shared descriptions | **Would not work.** Qwen3-4B needs simple 1-2 sentence descriptions. Claude/GPT benefits from multi-sentence descriptions with mode guidance, cross-tool references, and parameter examples. |
| R15. Export parameterSchema | Feasible — ToolSchema.jsonSchema() already returns a JSON string. Export to file is trivial. |
| R16. REST tool execution | Would bypass safety gates. The approval flow is in AgentLoopService, not exposed via HTTP. Reimplementing it adds ~150-200 lines with no net savings. |
| R17. Net maintenance reduction | **None.** Moves duplication from "two schema files" to "schema generation + HTTP adapter." |

**Summary**: Schema sharing eliminates only 30% of duplication, and descriptions must differ anyway.

### R25–R27: Cross-Cutting

| Question | Finding |
|----------|---------|
| R25. Same descriptions for both? | No. MCP server descriptions are 10-17x longer with implementation details (BM25, vector embedding, mode guidance). Small models need concise descriptions. |
| R26. Invalidates single source? | Yes. If descriptions must differ, the "define once" argument breaks for the most user-visible part of tool definitions. |
| R27. New preview REST endpoint | Worth considering independently. A `GET /api/documents/{id}/content?maxChars=N&offset=N` endpoint would benefit the MCP server (currently limited to 4KB) and any future HTTP consumers. Not related to unification. |

---

## Duplication Quantified

### Per-Tool Cost (both systems)

| Component | Java | TypeScript | Total |
|-----------|------|-----------|-------|
| Schema definition | ~25 lines | ~15 lines | ~40 lines |
| Tool description | 2-3 lines | 3-5 lines | ~7 lines |
| Parameter extraction | ~15-25 lines | ~5 lines | ~25 lines |
| Execution logic | ~30-60 lines | ~20-30 lines | ~70 lines |
| Result formatting | ~25-40 lines | ~5-10 lines | ~40 lines |
| Error handling | ~5-10 lines | ~3 lines | ~10 lines |
| **Total per new tool** | **~100-160 lines** | **~50-65 lines** | **~190 lines** |

**Frequency**: ~1-2 tools per quarter. **Annual cost**: ~380-760 lines across both systems.

### Divergence Points (by design)

| Aspect | Java | TypeScript | Why different |
|--------|------|-----------|---------------|
| Parameter naming | `snake_case` | `camelCase`/nested | Ecosystem convention |
| Validation | Permissive | Strict (Zod) | Small LLM sends messy JSON |
| Result format | Plain text | Structured JSON | Text is better for small LLMs |
| Descriptions | 1-2 sentences | Multi-sentence with cross-refs | Model capability gap |
| Max search results | 20 | 50 | Context window difference |

---

## Option Verdicts

### Option A: Java Tools Serve Both — REJECT

The SDK is capable, but the TypeScript MCP server isn't just schemas — it's 243 lines of discovery logic, token management, and Node.js-idiomatic HTTP client code. Replacing it with Java means rewriting ~1050 lines for negligible savings. Descriptions and result formats must differ anyway, so "define once" doesn't hold. The TypeScript MCP server tracks the MCP ecosystem (SDK updates, new transports) faster than a Java implementation would.

### Option B: Agent Becomes MCP Client — REJECT

Tauri has zero Node.js dependency today. Adding one (or an 80-100MB SEA binary) for in-process tool calls that currently take <1ms is architecturally backwards. The gRPC preview path (200K chars) would regress to 4KB REST. The `file_operations` tool with its undo log, path sandboxing, and Lucene index callbacks is deeply JVM-integrated — exposing it through MCP would require new safety-gate-aware REST endpoints.

### Option C: Shared Schema — REJECT

Zod cannot import JSON Schema. Schema is only 30% of duplication. Descriptions must differ. Shared schemas would force one naming convention on both ecosystems. Net effect: added build complexity for minimal savings.

### Option D: Hybrid Thin Wrapper — REJECT

REST tool execution endpoints bypass safety gates. The approval flow lives in `AgentLoopService`, not HTTP. Reimplementing session-less gating adds ~150-200 lines while creating a second safety code path. Moves duplication rather than eliminating it.

---

## Evaluation Against Criteria

| Criterion | Weight | Current Dual System | Best Unification Option |
|-----------|--------|--------------------|-----------------------|
| **Maintenance burden** | High | ~190 lines/tool, 1-2/quarter | ~100 lines/tool but +500 lines infrastructure |
| **Latency** | Low | <1ms (Java), ~10ms (MCP) | ~10ms everywhere (Option B) |
| **Runtime deps** | Medium | Node.js for MCP only | Node.js for everything (Option B) or none (Option A) |
| **Capability parity** | Medium | Full (gRPC 200K, file_ops undo) | Reduced (Option B loses gRPC) |
| **Migration effort** | Medium | Zero (status quo) | Medium-High (any option) |
| **Ecosystem alignment** | Medium | TypeScript tracks MCP ecosystem | Java lags TypeScript SDK evolution |
| **Complexity** | High | Two simple systems | One complex system |

**Winner: status quo (dual system).**

---

## Why Dual System Is Correct (Provisional)

1. **Different consumers**: Qwen3-4B needs simple schemas; Claude/GPT benefits from rich descriptions with cross-tool references
2. **Different transports**: In-JVM callback (<1ms) vs stdio JSON-RPC → HTTP (~10ms) — execution paths share only the backend API calls
3. **Different safety models**: Built-in has user-approval gates in `AgentLoopService`; MCP uses session token auth
4. **Different evolution timelines**: MCP server tracks SDK/ecosystem updates; built-in agent tracks local model capability improvements
5. **Low duplication cost**: ~190 lines per tool, ~1-2 tools/quarter — unification infrastructure would cost more than it saves

---

## Confidence Assessment

### Untested Assumptions (Unification Research)

This verdict rests on several assumptions that were reasoned about but never empirically validated:

| Assumption | Confidence | Risk if Wrong |
|------------|------------|---------------|
| Qwen3-4B needs simpler descriptions than Claude/GPT | **LOW** — never tested. Rich descriptions *might* help small models make better tool choices. This is the core argument for divergent descriptions. | High — if same descriptions work for both, the strongest argument for dual system collapses |
| ~1-2 tools per quarter is the right frequency estimate | **LOW** — fabricated. Tool system is brand new with no historical data. Tempdoc 197 suggests faster growth. | Medium — if tool count grows faster, dual maintenance scales linearly and coordination overhead compounds |
| Tool inventory gaps are intentional | **MEDIUM** — some gaps were rationalized after the fact (e.g., "small LLM doesn't need autocomplete"). Suggest/status might actually improve Qwen3-4B's effectiveness. | Medium — agent might underperform because we're withholding useful tools based on untested assumptions |
| Option B latency (~5-10ms) is negligible | **MEDIUM** — estimated, never measured on Windows with actual MCP server | Low — even if 2-3x higher, still negligible vs LLM inference time |
| Option D is infeasible due to safety gates | **MEDIUM** — safety gates *could* be exposed over HTTP with work. Dismissed as "too hard" without estimating effort. | Medium — if gated REST is ~100 lines, Option D becomes more viable |
| Zod cannot import JSON Schema | **LOW** — reported by research agent from web search, not verified. Zod v4 ecosystem may have evolved. | Low — even if possible, schemas are only 30% of duplication |

**Net assessment**: The "keep dual system" recommendation is the lowest-risk path because it's the status quo. But the confidence that it's the *right* long-term architecture is lower than the option verdicts suggest. The recommendation should be treated as provisional until the validation experiments below are completed.

### MCP Server Confidence (from 187)

> Originally 187 §9. Moved here 2026-02-13.

#### High Confidence (mechanical, proven patterns)

| Area | Why |
|------|-----|
| Tool registration & stdio transport | Dev server has 14 tools using `mcpServer.registerTool()`. Production server has 5. Copy-and-adapt. |
| HTTP client helpers | `httpGetTextLimited` / `httpPostJsonLimited` are self-contained, loopback-enforced. Direct reuse with header injection. |
| Search request/response contract | Fully investigated — manual JSON map parsing, `query`/`limit`/`mode`/`querySyntax`/`filters` input, `totalHits`/`tookMs`/`results` output. |
| Zod schemas | Dev server uses `zod/v4` with `.strict()` inputs, `.passthrough()` outputs. Same SDK (v1.26.0) from root `node_modules`. |
| Response size management | Dev server's `slimSearchResult()` truncates to 200-char previews. 10 results = ~5-7 KB. Proven token-efficient. |

#### Medium Confidence (workable, needs care)

| Area | Concern | Mitigation |
|------|---------|------------|
| Token endpoint (backend prerequisite) | Touches security-sensitive code. Must expose session token without undermining the auth model. | **Resolved** — implemented, tested, critical analysis verified. |
| Data directory resolution in TypeScript | Must replicate `PlatformPaths.resolveDataDir()`: check `JUSTSEARCH_DATA_DIR` → `%LOCALAPPDATA%\JustSearch`. Second implementation of same logic — could drift. | **Resolved** — implemented in `discovery.mjs`, coupling documented. |
| Stale `api-port.txt` after crash | If JustSearch crashes, the port file contains a dead port. Could connect to nothing or (unlikely) a different service. | **Resolved** — always validates with `GET /api/status`. Tested with stale port files. |

#### Low Confidence (inherently uncertain, requires prototyping)

| Area | Concern | Approach |
|------|---------|----------|
| Tool descriptions | Whether LLMs invoke tools correctly depends on model interpretation. | **Resolved** — Phase 2d improved descriptions with mode guidance, fileKind enumeration, cross-tool references. Tested via stdio; descriptions render correctly in tools/list. |
| Cross-client compatibility | Different MCP client implementations may have quirks. | **Partially resolved** — tested via Claude Code stdio JSON-RPC protocol (equivalent to any stdio MCP client). Cursor-specific UI testing still deferred. |

---

## Cross-System Synergy

> Originally 187 §12 "Synergy with Built-in Agent". Moved here 2026-02-13.

The built-in agent (tempdoc 186) has tools the MCP server lacks, and vice versa. The two systems converge on the same backend capabilities but diverge on schema complexity and transport — this is intentional and healthy.

**Built-in agent is ahead on**:
- `file_operations` — MOVE/RENAME/MKDIR/COPY with undo, transaction log, conflict resolution. The MCP server could adopt this as `justsearch_file_ops` with `destructiveHint: true`, `openWorldHint: true` annotations. Requires the session token for POST calls.
- `browse_folders` — Already implemented. Maps to the planned `justsearch_folders` / `justsearch_roots` tools above.
- `ingest_files` — Already implemented with directory expansion. Maps to the existing `justsearch_ingest` (already shipped).

**MCP server is ahead on**:
- `justsearch_preview` — Document content reading (paginated). The built-in agent plans to add this with a gRPC-backed callback (`FetchDocumentSlice`, up to 200K chars), which would be strictly better than the MCP server's 4KB REST pagination.
- `justsearch_suggest` — Autocomplete for content discovery. Simple to port.
- `justsearch_status` — Index health check. Simple to port.
- Tool descriptions — Phase 2d optimized for LLM usability (mode guidance, fileKind enumeration, cross-tool references). The built-in agent should adapt these for its smaller models.
- Cursor pagination on search — Already proven; built-in agent should add `cursor` parameter.

**No shared schema required** — the MCP server serves large external models (Claude, GPT) while the built-in agent serves Qwen3-4B. Both project the same backend API contract but tuned for their respective consumers.

---

## Validation Experiments

These are cheap, concrete tests that would resolve the key uncertainties. Run them before treating the verdict as final.

### Prerequisite: Agent Observability Gap

**Status**: done

The Agent UI currently displays only the final text output from tool calls (the result the agent shows the user). It does **not** expose:

- **Tool call arguments**: The raw JSON the LLM sends when invoking a tool (e.g., `{"query": "...", "mode": "hybrid", "path_prefix": "/docs"}`)
- **Tool selection sequence**: Which tools the LLM considered and in what order
- **Parameter choices influenced by descriptions**: Whether the LLM used `mode`, `path_prefix`, or other optional parameters that the rich description mentions

Without this data, we cannot determine whether Experiment 1's rich descriptions actually changed the LLM's behavior. A search that returns good results tells us the backend works — it doesn't tell us whether the LLM chose `hybrid` mode because the description mentioned it, or defaulted to `text` as before.

**Resolution options** (pick one):

1. **Server-side logging** (cheapest): Add `LOG.info("Tool call: {} args={}", name(), argumentsJson)` to each tool's `execute()` method. Check backend logs during experiments. Downside: requires log access, not visible in UI.
2. **Agent UI tool call display** (best long-term): Extend the SSE stream to include tool call arguments alongside results. The `AgentController` already streams tool results; adding the input JSON is a small change. This also benefits users who want to understand what their agent is doing.
3. **Experiment methodology change** (workaround): Design prompts that can only succeed if the LLM uses specific parameters. E.g., "search for X only in the /docs folder" — if results come from /docs, path_prefix was used. Indirect but avoids code changes.

**Resolved**: Option 2 implemented. The `tool_exec_started` SSE handler in `useAgentStore.ts` now creates `tool-call-group` conversation entries for auto-approved READ_ONLY tools. The `ToolCallCard` component (which already had argument display code) now renders for all tool calls, showing pretty-printed JSON arguments alongside results.

### Experiment 1: Description Complexity (resolves core assumption)

**Status**: CODE COMMITTED (`61c68be3`), EVALUATION PENDING

**Question**: Does Qwen3-4B perform better or worse with MCP-style rich descriptions?

**Method**:
1. Take the current `SearchTool` with its simple description
2. Replace the description with the MCP server's rich version (adapted for Java tool names)
3. Run 5-10 agent conversations with common tasks ("find documents about X", "what files are in Y folder")
4. Compare: tool selection accuracy, parameter correctness, overall task completion
5. Repeat with `BrowseTool`

**Cost**: ~1 hour with running dev stack + llama-server.

**Outcome**: If rich descriptions help → descriptions don't need to differ → strongest argument for dual system weakens significantly. If they hurt or make no difference → assumption confirmed.

**Progress**:
- Rich description deployed to `SearchTool.java` (see comment in code referencing this experiment)
- Original description preserved in comment for rollback
- First test: user asked "configuration and the SSOT" → agent returned 4 relevant results in 1-2 seconds
- **Blocked**: Cannot evaluate whether the LLM used `mode`, `path_prefix`, or other parameters mentioned in the rich description. The Agent UI only shows the final search results, not the tool call arguments. See §Prerequisite above.

**If rich descriptions help**:
- Port MCP server descriptions (adapted for Java tool names)
- Add cross-tool references
- Revisit tempdoc 200 verdict — description divergence was the core argument for dual system

**If they hurt or no difference**:
- Keep descriptions concise (1-2 sentences)
- Add minimal mode selection guidance only
- Tempdoc 200 verdict confirmed for descriptions

### Experiment 2: Suggest/Status Usefulness (resolves gap rationalization)

**Question**: Would adding `suggest` and `status` tools improve Qwen3-4B's task completion?

**Method**:
1. Implement minimal SuggestTool and StatusTool in Java (< 100 lines each)
2. Run the same task set from Experiment 1
3. Observe: Does the agent use these tools? Does it make better decisions with them?

**Cost**: ~2-3 hours implementation + testing.

**Outcome**: If useful → the "small LLM doesn't need these" rationalization was wrong → tool gaps are not intentional, they're oversights.

**If suggest/status are useful**:
- Implement SuggestTool (wired to `/api/knowledge/suggest`)
- Implement StatusTool (wired to `/api/knowledge/status`)
- Implement PreviewTool via gRPC (200K chars)

**If unused**:
- Defer — not needed for small LLM
- User monitors status via UI
- Defer PreviewTool until search→read pattern is needed

### Experiment 3: Option B Latency (resolves measurement gap)

**Question**: What is the actual round-trip latency of the MCP stdio path on Windows?

**Method**:
1. Write a minimal Java MCP client that sends 100 tool calls to the TypeScript MCP server via stdio
2. Measure p50, p95, p99 latency per call
3. Compare with direct Java callback latency

**Cost**: ~2 hours.

**Outcome**: Confirms or refutes the ~5-10ms estimate. If measured at <5ms, Option B's latency argument weakens further.

### Completed Improvements

**BrowseTool Root Sentinels** [DONE]
- Implemented in `BrowseTool.java` with `ROOT_SENTINELS = Set.of("/", ".", "..", "root", "roots", "top", "*")`
- **Commit**: `22add3ff`

---

## Tool Inventory (Living Table)

This table tracks which tools exist in each system and why gaps are intentional.

| Capability | Java Built-in | TypeScript MCP | Gap Rationale | Confidence |
|------------|--------------|----------------|---------------|------------|
| Search | `search_index` | `justsearch_search` | MCP has richer filters; Java simplified for small LLM | Medium — description experiment may change this |
| Browse folders | `browse_folders` | not exposed | MCP uses `pathPrefix` search filter instead; planned for MCP v2 | High — verified |
| Ingest | `ingest_files` | `justsearch_ingest` | Roughly equivalent | High — verified |
| File operations | `file_operations` | not exposed | Requires in-JVM safety gates, undo log, path sandboxing, Lucene index callbacks | High — structural constraint |
| Autocomplete | not exposed | `justsearch_suggest` | Assumed small LLM doesn't need it | **Low — untested, run Experiment 2** |
| Preview content | not exposed | `justsearch_preview` | Built-in could use gRPC (200K chars), superior to REST (4KB); add when needed | Medium — gap is real but gRPC advantage is verified |
| Index status | not exposed | `justsearch_status` | Assumed small LLM doesn't need it | **Low — untested, run Experiment 2** |

**When adding a tool**: Check this table. If the tool should exist in both systems, add it to both with system-appropriate schemas/descriptions. If the gap is intentional, document why.

---

## Governance: Tool Parity Checklist

When adding a new tool to either system:

1. Does the other system need this tool? (Not all tools need to exist in both — see inventory table.)
2. If yes, are the parameter names consistent with that system's conventions (Java: `snake_case`, MCP: `camelCase`)?
3. Are descriptions tuned for the target LLM? (Simple for built-in/Qwen, rich for MCP/Claude.)
4. Update the tool inventory table above.

---

## Deferred Actions

| Action | Trigger | Effort |
|--------|---------|--------|
| Compile MCP server to standalone exe (Node.js SEA) | When distributing JustSearch to users without Node.js | ~1-2 days |
| Add Java MCP stdio adapter | If significant MCP clients can't run Node.js | Medium (SDK is ready) |
| Enrich Java SearchTool (filters, pagination) | When local models improve beyond Qwen3-4B (7B+) | Small |
| Add `browse_folders` to MCP server | When external agents need folder structure browsing | Small |
| Add `preview_document` to built-in agent | When agent workflows need to read document content | Small (gRPC callback) |
| New REST endpoint for full document content | Independent of unification; benefits MCP server | Small backend change |
| VRAM tier gating for agent features | When tool quality is validated on different tiers | Small |

---

## References

- Built-in agent tools: `modules/app-agent/src/main/java/io/justsearch/agent/tools/`
- Tool SPI: `modules/app-agent-api/src/main/java/io/justsearch/agent/api/ToolDefinition.java`
- Tool registration: `modules/app-services/src/main/java/io/justsearch/app/services/AppFacadeBootstrap.java`
- Production MCP server: `scripts/prod/justsearch-mcp/server.mjs`
- MCP schemas: `scripts/prod/justsearch-mcp/schemas.mjs`
- Java MCP SDK: `io.modelcontextprotocol.sdk:mcp` v0.17.2
- Tempdoc 186: `docs/tempdocs/186-llm-agentic-file-operations.md` — built-in agent implementation
- Tempdoc 187: `docs/tempdocs/187-production-mcp-server.md` — MCP server implementation
- Tempdoc 197: `docs/tempdocs/197-agent-system-expansion.md` — broader agent platform gaps
- llama.cpp function-calling: [docs](https://github.com/ggml-org/llama.cpp/blob/master/docs/function-calling.md), [PR #9639](https://github.com/ggml-org/llama.cpp/pull/9639)
- ReAct paper: [arXiv 2210.03629](https://arxiv.org/abs/2210.03629)
- MCP Specification 2025-11-25: https://modelcontextprotocol.io/specification/2025-11-25
- TypeScript SDK: `@modelcontextprotocol/sdk` v1.26.0

---

## Staleness review (2026-05-18)

Investigation-shape tempdoc (research / audit / findings) — terminal by nature per the README's "investigation log that produced a decision" definition. Body content preserved as design history. Classification: INVESTIGATION. Stale for 94 days at audit time.

