/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ort;

/**
 * Identity of an ORT-backed encoder within the inference surface.
 *
 * <p>Used as a key by {@link ModelSessionPolicyResolver} to look up per-encoder defaults in
 * {@link io.justsearch.configuration.resolved.ResolvedConfig.Ai} sub-records, and as a map key in
 * {@link PolicySnapshot} for the {@code /api/debug/session-policies} endpoint.
 *
 * <p>Corresponds one-to-one with the six encoder classes constructed today by
 * {@code KnowledgeServer.initDeferredModels()}.
 *
 * <p>{@link #packageId()} returns the install-contract / model-registry package ID. Defining it
 * on the enum (rather than a sibling {@code Map<EncoderRole, String>}) forces compile-time
 * exhaustiveness: adding a new enum constant without a {@code packageId} value is a compile
 * error.
 */
public enum EncoderRole {
  EMBEDDING("embedding", "embed"),
  /**
   * BGE-M3 is an alternative embedding model that shares the registry {@code "embedding"} package
   * with {@link #EMBEDDING} — they're mutually exclusive at runtime (see
   * {@code KnowledgeServer.java:729}). Both roles therefore map to the same install-contract
   * entry but produce different {@link ModelSessionPolicy} records because the resolver reads
   * their per-encoder config from different {@code ResolvedConfig.Ai} sub-records.
   */
  BGE_M3("embedding", "bgem3"),
  SPLADE("splade", "splade"),
  NER("ner", "ner"),
  RERANKER("reranker", "reranker"),
  CITATION("citation-scorer", "citation");

  private final String packageId;
  private final String consumerName;

  EncoderRole(String packageId, String consumerName) {
    this.packageId = packageId;
    this.consumerName = consumerName;
  }

  /** The install-contract / model-registry package ID for this role. */
  public String packageId() {
    return packageId;
  }

  /**
   * The short identifier used in log lines and as the {@code consumer} tag value on
   * {@code ort.session.*} metrics. Tempdoc 414 C1: single source of truth — replaces the
   * hardcoded duplicate lists in {@code KnowledgeServer} and {@code InferenceCompositionRoot}.
   */
  public String consumerName() {
    return consumerName;
  }

  /**
   * Tempdoc 374 alpha.18 Bug I + alpha.21 Bug R: roles that are CPU-only by design.
   * The runtime composition path (composeCitationRole) hardcodes {@code gpuEnabled=false}
   * for citation-scorer; the head's status display must use the same per-role policy
   * to avoid reporting citation as `degraded(CUDA)` when it actually runs on CPU.
   */
  public boolean isCpuOnly() {
    return this == CITATION;
  }

  /**
   * Tempdoc 374 alpha.21 Bug R: package-id lookup for callers that don't have an
   * EncoderRole in scope (e.g., {@code StatusLifecycleHandler.probeModelDistributionStatus}
   * which iterates the install contract by package ID). Returns true when the package
   * is CPU-only by design — the caller should pass {@code gpuAllowed=false} to
   * {@code VariantSelector.select} for that package.
   */
  public static boolean isPackageCpuOnly(String packageId) {
    if (packageId == null) return false;
    for (EncoderRole role : values()) {
      if (role.packageId.equals(packageId) && role.isCpuOnly()) {
        return true;
      }
    }
    return false;
  }
}
