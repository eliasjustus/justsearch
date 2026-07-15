/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ort;

import io.justsearch.configuration.model.ModelPrecision;
import java.util.List;
import java.util.Map;

/**
 * Everything the runtime needs to know about a model directory's intrinsic behaviour, resolved
 * ONCE per model directory at composition time (tempdoc 710 Wave 2 Move 1).
 *
 * <p>Replaces the pre-Wave-2 pattern of each encoder independently hand-parsing sidecar JSON at
 * construction time ({@code OnnxEmbeddingEncoder.detectPoolingStrategy}, {@code
 * EmbeddingService.loadPrefixes}, {@code BertNerInference.loadLabelMapping}'s silent fallback) —
 * dominant defect class named in tempdoc 710 S-B3 (12 undeclared-model-capability instances).
 * {@link ModelCapabilityResolver#resolve} is the single production entry point; consumers never
 * re-derive these facts from the filesystem.
 *
 * <p>Every field uses an explicit "undeclared" sentinel ({@code UNKNOWN} enum constant, {@code 0}
 * for ints, {@code null} for precision/prefix strings, an empty map for label mappings) rather
 * than silently defaulting to a guess — per the TEI fail-closed precedent (S-C.R), an undeclared
 * fact is surfaced via {@link #warnings()} at resolve time (WARN today; a startup failure for
 * that encoder lane under {@code justsearch.models.capability_contract_strict}, tempdoc 710 S-C.D).
 *
 * @param poolingMode pooling strategy for extracting a single vector from token-level hidden
 *     states; {@link PoolingMode#UNKNOWN} if no source declared it
 * @param trainedContextLength the model's trained context window in tokens; {@code 0} if unknown.
 *     Informational/validation-only — runtime {@code maxSeqLen} stays operator-configurable
 *     (late-chunking deliberately runs below or above this figure)
 * @param embeddingDimension declared output vector width; {@code 0} if unknown (the reactive
 *     first-inference detection in {@code OnnxEmbeddingEncoder} remains the fallback, cross-
 *     validated against this field when both are known)
 * @param cpuPrecision numeric precision of the manifest's {@code cpu} file variant; {@code null}
 *     if undeclared and unresolvable
 * @param gpuPrecision numeric precision of the manifest's {@code gpu} file variant; {@code null}
 *     if undeclared and unresolvable
 * @param documentPrefix task-instruction prefix for document-side embedding; {@code null} means
 *     "no source declared a value" — distinct from {@code ""} which is a declared empty prefix
 *     (S-C.R: absence of {@code config_sentence_transformers.json prompts} is never "no prefix")
 * @param queryPrefix task-instruction prefix for query-side embedding; same null-vs-empty rule as
 *     {@link #documentPrefix}
 * @param labelMapping raw {@code id -> label} mapping read from the manifest-declared label
 *     config file's {@code id2label} object; empty if absent. Domain-specific projection (e.g.
 *     {@code BioTagDecoder.LabelMapping}) stays in the consuming module — {@code ort-common} has
 *     no NER-specific types.
 * @param warnings human-readable degradation reasons accumulated during resolution — every
 *     manifest-field-or-ecosystem-file gap that fell through to a fallback. Logged as WARN by the
 *     resolver; non-empty triggers a hard failure under strict mode.
 */
public record ModelCapabilities(
    PoolingMode poolingMode,
    int trainedContextLength,
    int embeddingDimension,
    ModelPrecision cpuPrecision,
    ModelPrecision gpuPrecision,
    String documentPrefix,
    String queryPrefix,
    Map<String, String> labelMapping,
    List<String> warnings) {

  /** Pooling strategy for extracting a single vector from token-level hidden states. */
  public enum PoolingMode {
    /** First token (CLS) embedding. */
    CLS,
    /** Attention-mask-weighted mean of all token embeddings. */
    MEAN,
    /** No source (manifest, ST ecosystem files, legacy sidecar) declared a pooling mode. */
    UNKNOWN
  }
}
