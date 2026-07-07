/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.configuration.model;

/**
 * Capability tier of a model package — the axis an {@link InstallIntent} selects over (tempdoc 657).
 *
 * <p>Orthogonal to {@link DownloadProfile} (the <em>hardware</em> axis, which picks the precision
 * variant within a wanted package). The tier answers "which capability does this package serve," so an
 * install intent can include or exclude whole capability groups independent of hardware.
 *
 * <ul>
 *   <li>{@link #RETRIEVAL_CORE} — the dense embedding model; required for any neural/hybrid search.
 *   <li>{@link #RETRIEVAL_ENRICHMENT} — SPLADE, reranker, NER, citation-scorer; retrieval-quality
 *       boosters that retrieval degrades gracefully without.
 *   <li>{@link #LLM} — the GGUF chat model (and its vision projector supporting file); powers
 *       RAG/chat/agent/summarize/extract.
 *   <li>{@link #RUNTIME} — hardware-support payloads (e.g. the CUDA runtime DLLs); not a capability
 *       itself, but required for the LLM tier on GPU.
 * </ul>
 */
public enum CapabilityTier {
  RETRIEVAL_CORE("retrieval-core", "Core retrieval"),
  RETRIEVAL_ENRICHMENT("retrieval-enrichment", "Retrieval enrichment"),
  LLM("llm", "Chat & AI answers"),
  RUNTIME("runtime", "GPU runtime");

  private final String id;
  private final String label;

  CapabilityTier(String id, String label) {
    this.id = id;
    this.label = label;
  }

  /** The kebab-case identifier used in the registry JSON ({@code "tier"} field). */
  public String id() {
    return id;
  }

  /** Human-readable label for UI grouping (e.g. an install-weight breakdown). */
  public String label() {
    return label;
  }

  /**
   * Resolves a tier from its registry JSON id (kebab-case). Returns {@code null} for a null/blank
   * input so a package with no declared tier stays untagged (back-compat with pre-tier registries).
   *
   * @throws IllegalArgumentException if the id is non-blank but unrecognized
   */
  public static CapabilityTier fromId(String id) {
    if (id == null || id.isBlank()) {
      return null;
    }
    for (CapabilityTier t : values()) {
      if (t.id.equals(id)) {
        return t;
      }
    }
    throw new IllegalArgumentException("Unknown capability tier id: " + id);
  }
}
