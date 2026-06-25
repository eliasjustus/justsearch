/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.api.registry;

import java.util.Set;

/**
 * Tempdoc 577 §2.14 Root III (#18) — the TEXT-PROVENANCE of a tool's output: where the bytes a tool
 * returns came from. Distinct from {@link OperationLineage} (which is resource-causal: what an
 * operation affects/supersedes); this is about whether a tool's returned text is the user's own
 * documents quoted back, a runtime/computed value, or the agent's own words.
 *
 * <p>This is the display half of prompt-injection safety (the answer-frame's mirror for tool spans):
 * a corpus-quoted excerpt must be framed as quoted — so citation- or instruction-shaped text inside
 * it ("ignore previous instructions", "[1]") cannot render as the agent's own credible claim. The
 * backend stamps the authoritative lineage at execution time; the FE frames accordingly and never
 * re-derives it (the single-authority discipline).
 *
 * <p>One classifier ({@link #forOperationId}) keyed on the corpus-reading operation set — a new
 * corpus reader is a one-line addition here, not a guess scattered across renderers. Tool output is
 * never {@code AGENT_AUTHORED} (that is the answer, not a tool result); the value exists for the
 * shared vocabulary with the answer-frame.
 */
public enum OutputLineage {
  /** Quoted from the user's own indexed documents (search hits, browse listings, file reads). */
  CORPUS_QUOTED("corpus-quoted"),
  /** A runtime/computed/system value the tool produced (not the user's content). */
  RUNTIME("runtime"),
  /** The agent's own words (the answer; never a tool output — present for vocabulary parity). */
  AGENT_AUTHORED("agent-authored");

  private final String wireToken;

  OutputLineage(String wireToken) {
    this.wireToken = wireToken;
  }

  /** The lowercase-hyphen token carried on the wire (in {@code structuredData.lineage}). */
  public String wireToken() {
    return wireToken;
  }

  // The operations whose OUTPUT is the user's corpus quoted back. A new corpus reader adds its id
  // here (the one declaration site) — everything else is RUNTIME by classification.
  private static final Set<String> CORPUS_READERS =
      Set.of("core.search-index", "core.browse-folders");

  /**
   * Classify a tool's output lineage from its operation id. Corpus readers return their documents'
   * content; everything else is a runtime value. The authoritative stamp, applied once at dispatch.
   */
  public static OutputLineage forOperationId(String operationId) {
    return operationId != null && CORPUS_READERS.contains(operationId)
        ? CORPUS_QUOTED
        : RUNTIME;
  }
}
