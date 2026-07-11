/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ort;

import java.util.EnumSet;
import java.util.Set;

/**
 * The set of {@link ModelCapabilities} facts a consuming encoder role actually reads.
 *
 * <p>{@link ModelCapabilityResolver#resolve} only resolves (and, on a gap, WARNs about) facts in
 * this set — a fact outside it is never even attempted and never appears in {@code warnings()}.
 * This is what makes the WARN log a signal instead of noise: pre-tempdoc-710-Wave-2-Move-2, the
 * resolver unconditionally resolved every fact for every role, so NER — which never pools token
 * embeddings and never applies a task-instruction prefix — WARNed "pooling mode undeclared" and
 * "prefix(es) undeclared" on every healthy boot, and embedding — which has no label taxonomy —
 * WARNed "label config not found". Warnings that always fire on a healthy config train operators
 * to ignore warnings (the F-013 failure mode this contract exists to prevent).
 *
 * <p>Presets are derived from what each encoder actually consumes off {@link ModelCapabilities}
 * (verified against the call sites, not guessed): {@code OnnxEmbeddingEncoder.buildAssembly} reads
 * {@code poolingMode}/{@code embeddingDimension}, {@code KnowledgeServer} reads {@code
 * documentPrefix}/{@code queryPrefix} off the embedding assembly's capabilities, and both {@code
 * cpuPrecision}/{@code gpuPrecision} are resolved for every role (informational — surfaced by
 * {@code DevModeVariantProbe} today, a hard fact worth getting right regardless of role). {@code
 * BertNerInference.buildAssembly} reads only {@code labelMapping}; SPLADE/reranker/citation/BGE-M3
 * don't call the resolver yet, but their pending integration needs only context length + precision
 * (no pooling — those lanes consume raw token-level or single-score outputs, not a pooled sentence
 * vector; no prefixes; no label taxonomy).
 *
 * @param facts the facts this role resolves; every other fact stays at its "undeclared" sentinel
 *     ({@code UNKNOWN}/{@code 0}/{@code null}/empty map) with zero warnings, unconditionally
 */
public record CapabilityRequirements(Set<Fact> facts) {

  public CapabilityRequirements {
    facts = facts == null || facts.isEmpty() ? Set.of() : Set.copyOf(facts);
  }

  /** True if this role reads {@code fact} off the resolved {@link ModelCapabilities}. */
  public boolean requires(Fact fact) {
    return facts.contains(fact);
  }

  /** A resolvable {@link ModelCapabilities} fact. */
  public enum Fact {
    POOLING,
    CONTEXT_LENGTH,
    DIMENSION,
    PRECISION,
    PREFIXES,
    LABELS
  }

  /** {@code OnnxEmbeddingEncoder}: pooling, dimension, and prefixes; no label taxonomy. */
  public static final CapabilityRequirements EMBEDDING =
      new CapabilityRequirements(
          EnumSet.of(
              Fact.POOLING, Fact.CONTEXT_LENGTH, Fact.DIMENSION, Fact.PRECISION, Fact.PREFIXES));

  /** {@code BertNerInference}: label taxonomy only; NER never pools or prefixes. */
  public static final CapabilityRequirements NER =
      new CapabilityRequirements(EnumSet.of(Fact.CONTEXT_LENGTH, Fact.PRECISION, Fact.LABELS));

  /**
   * SPLADE/reranker/citation/BGE-M3: none of these lanes pool a sentence vector, apply a
   * task-instruction prefix, or consume a label taxonomy — only context length and precision are
   * ever read. Not yet wired to any {@code ModelCapabilityResolver.resolve} call site (those
   * encoders don't call the resolver yet); declared ahead of that integration.
   */
  public static final CapabilityRequirements SPLADE =
      new CapabilityRequirements(EnumSet.of(Fact.CONTEXT_LENGTH, Fact.PRECISION));

  public static final CapabilityRequirements RERANKER = SPLADE;
  public static final CapabilityRequirements CITATION = SPLADE;
  public static final CapabilityRequirements BGEM3 = SPLADE;

  /** Every fact — used where completeness, not role-scoping, is the point (e.g. broad tests). */
  public static final CapabilityRequirements ALL =
      new CapabilityRequirements(EnumSet.allOf(Fact.class));
}
