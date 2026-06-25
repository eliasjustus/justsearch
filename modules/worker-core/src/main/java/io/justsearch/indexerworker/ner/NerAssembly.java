/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.ner;

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import io.justsearch.ort.SessionHandle;

/**
 * Complete pre-built inputs for {@link NerService}. Produced by
 * {@code InferenceCompositionRoot.composeNerAssembly(...)} (tempdoc 397 §14.24 FD-NER); replaces the
 * previous pattern where NerService loaded the tokenizer and label mapping lazily from disk
 * inside {@code ensureInitialized()}.
 *
 * <p>Centralising construction in the composition root means every consumer (production path,
 * dev-mode fallback, integration tests) produces the same shape — §7.5's "encoders as pure
 * inference transformers" landed for NER.
 *
 * @param sessions ORT session handle (owns GPU/CPU session lifecycle)
 * @param shape model-intrinsic facts (input-name detection + max sequence length)
 * @param tokenizer loaded DJL HuggingFace tokenizer
 * @param labelMapping BIO→entity-type mapping loaded from the model's label-config JSON
 */
public record NerAssembly(
    SessionHandle sessions,
    NerShape shape,
    HuggingFaceTokenizer tokenizer,
    BioTagDecoder.LabelMapping labelMapping) {}
