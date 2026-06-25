/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.api.conversation;

import java.util.Objects;

/**
 * A priority-ordered contribution to the assembled system prompt.
 *
 * <p>Per tempdoc 491 §5.1: {@link PromptContributor}s return zero or more fragments per
 * invocation. The engine collects all fragments from all active contributors, sorts by
 * priority, and joins them deterministically into the assembled system prompt.
 *
 * <p>{@code priority} is interpreted ascending: lower priority values appear earlier in
 * the assembled prompt. Convention: identity/style preambles use priority 0–9; catalog
 * descriptors (CatalogProjection blocks) use 50–69; dynamic context use 80–99. The exact
 * ranges are conventional, not enforced — the assembler uses a stable sort, so equal
 * priorities preserve declaration order.
 *
 * @param text the fragment text (non-null, non-empty)
 * @param priority ordering key; lower appears earlier in the assembled prompt
 */
public record PromptFragment(String text, int priority) {

  public PromptFragment {
    Objects.requireNonNull(text, "text");
    if (text.isEmpty()) {
      throw new IllegalArgumentException("PromptFragment text must not be empty");
    }
  }
}
