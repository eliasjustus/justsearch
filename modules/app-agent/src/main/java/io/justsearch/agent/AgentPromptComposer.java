/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent;

import java.util.List;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tempdoc 584 §B.4 — the agent's system-prompt assembly, extracted from {@code AgentLoopService}
 * (the prompt cluster, ~9% of the pre-584 file). Owns the default prompt text plus the two
 * compositional concerns that accreted onto it over time: the indexed-root-folders preamble
 * (built from the {@code rootPathsSupplier}) and the slice-447 condition-recovery context
 * (appended from the {@code conditionContextSupplier}).
 *
 * <p>The cut is on the *breadth* axis (§B.2): prompt assembly is a cohesive, independently
 * testable concern with no agent-loop state, so new prompt-shaping features attach here instead
 * of growing the orchestrator.
 *
 * <p>Note on scope: {@code TOOL_CALL_GRAMMAR} and {@code TRACER_SCOPE} stay on
 * {@code AgentLoopService} — they are shared cross-collaborator infra constants
 * ({@code AgentLlmCaller} / {@code AgentStepRunner} reference them), not prompt-composition state.
 * {@code swapSystemPrompt} stays on the loop class too (it mutates the live session's message list
 * and is a step-runner callback); it delegates here for the base text via {@link #buildSystemPrompt}.
 */
final class AgentPromptComposer {
  private static final Logger LOG = LoggerFactory.getLogger(AgentPromptComposer.class);

  // Per tempdoc 429 §F.21 C1: tool names in this prompt are deterministic
  // transliterations of OperationRef (`core.search-index` → `core_search_index`). The
  // OperationRef is the single identity; the LLM sees the projected wire form.
  static final String DEFAULT_SYSTEM_PROMPT =
      "You are a helpful assistant with access to tools for searching and managing"
          + " documents in a local knowledge base. When the user asks a question:"
          + " (1) Think about which tools can help answer it."
          + " (2) Call the appropriate tool(s) to gather information."
          + " (3) Use the tool results to formulate your response."
          + " (4) If the results are insufficient, try a different approach or tool."
          + " Provide clear, concise answers based on the information you find."
          + " Important: All file paths in this system are absolute paths."
          + " Use core_browse_folders when you need to discover folder structure or absolute paths."
          + " For search queries, call core_search_index directly without browsing first."
          + " When using path_prefix in core_search_index or paths in core_file_operations, get absolute"
          + " paths from core_browse_folders results."
          + " If a tool call fails, do not retry with the same arguments — try different"
          + " parameters or a different tool."
          + " IMPORTANT: Never answer factual questions about the knowledge base without"
          + " searching first. If the user asks about file contents, topics, or documents,"
          + " always call core_search_index before responding."
          + " When you reference a source, cite it inline with a bracketed number like [1], [2]"
          + " at the end of the sentence it supports. Do NOT append a separate"
          + " \"Citations:\", \"Sources:\", or \"References:\" list at the end of your answer —"
          + " the interface displays the full source list for you."
          + " When you learn a durable fact or preference about the user or their work that"
          + " is worth recalling in future conversations (e.g. a stated preference, their name,"
          + " a long-running goal), call core_remember with one concise sentence. Do not"
          + " remember transient or trivial details.";

  private final Supplier<List<String>> rootPathsSupplier; // nullable
  /**
   * Slice 447 §X.11.5 Phase 5: agent retrospection consumer. When set and non-empty, the
   * supplier's String is appended to {@link #buildSystemPrompt} as a "Currently asserted conditions
   * and recommended recoveries" section. Wired in {@code HeadAssembly} (via {@code AgentLoopService})
   * to render the {@code core.condition-recovery-index} snapshot as natural-language text the agent
   * can read at prompt-construction time. Null in test paths that don't exercise it.
   */
  private volatile Supplier<String> conditionContextSupplier; // nullable

  AgentPromptComposer(Supplier<List<String>> rootPathsSupplier) {
    this.rootPathsSupplier = rootPathsSupplier;
  }

  /**
   * Builds the agent's system prompt: the default text, optionally extended with the indexed-root
   * preamble, then the condition-recovery context.
   */
  String buildSystemPrompt() {
    String basePrompt = DEFAULT_SYSTEM_PROMPT;
    if (rootPathsSupplier != null) {
      List<String> roots;
      try {
        roots = rootPathsSupplier.get();
      } catch (Exception e) {
        LOG.warn("Failed to get root paths for system prompt", e);
        roots = null;
      }
      if (roots != null && !roots.isEmpty()) {
        var sb = new StringBuilder(DEFAULT_SYSTEM_PROMPT);
        sb.append(" The indexed root folders are:");
        for (String root : roots) {
          sb.append(" \"").append(root).append("\",");
        }
        sb.setLength(sb.length() - 1);
        sb.append(
            ". Always use these absolute paths (or subpaths of them)"
                + " as path_prefix in core_search_index and as parent_path in core_browse_folders.");
        basePrompt = sb.toString();
      }
    }
    return appendConditionContext(basePrompt);
  }

  /**
   * Slice 447 §X.11.5 Phase 5: append the current condition-recovery context if the supplier is
   * wired and returns non-empty content. Failure-tolerant: any throw from the supplier is logged and
   * swallowed; the agent loop never breaks because the retrospection feed degraded.
   */
  private String appendConditionContext(String basePrompt) {
    Supplier<String> supplier = this.conditionContextSupplier;
    if (supplier == null) return basePrompt;
    String context;
    try {
      context = supplier.get();
    } catch (Exception e) {
      LOG.warn("Condition context supplier threw; agent retrospection unavailable", e);
      return basePrompt;
    }
    if (context == null || context.isBlank()) return basePrompt;
    return basePrompt + "\n\n" + context;
  }

  /**
   * Slice 447 §X.11.5 Phase 5: wires the agent retrospection consumer. The supplier is called at
   * prompt-construction time; its returned String is appended to {@link #buildSystemPrompt}. Pass
   * {@code null} to disable.
   */
  void setConditionContextSupplier(Supplier<String> supplier) {
    this.conditionContextSupplier = supplier;
  }
}
