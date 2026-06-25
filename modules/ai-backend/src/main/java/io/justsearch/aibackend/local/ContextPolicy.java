/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.aibackend.local;

/**
 * Context window management strategy for multi-slot inference.
 *
 * <p>When the KV cache fills up during generation, these policies determine
 * how to make room for new tokens.
 */
public enum ContextPolicy {
  /**
   * Removes the oldest tokens from the context and shifts remaining tokens.
   *
   * <p><b>Warning:</b> This may evict the system prompt in RAG/chat scenarios,
   * causing the model to "forget" its instructions. Use {@link #SHIFT_AFTER_PINNED}
   * for applications with important system prompts.
   */
  SHIFT_OLDEST,

  /**
   * Preserves a "pinned" region at the start of the context (typically the system prompt)
   * and only evicts tokens after the pinned region.
   *
   * <p>This is the recommended policy for chat/RAG applications where the system prompt
   * must be preserved to maintain consistent bot behavior.
   *
   * <p>Configure the pinned region size via {@code pinnedContextTokens} in config.
   */
  SHIFT_AFTER_PINNED,

  /**
   * Clears the entire KV cache and starts fresh.
   *
   * <p>Use for stateless operations where context history is not important.
   */
  TRUNCATE_ALL,

  /**
   * Fails immediately when context is exhausted.
   *
   * <p>Use when you want strict control and prefer errors over lossy behavior.
   */
  FAIL_FAST
}
