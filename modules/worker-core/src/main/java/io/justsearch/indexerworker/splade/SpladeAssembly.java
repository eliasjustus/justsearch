/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.splade;

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.djl.modality.nlp.Vocabulary;
import io.justsearch.ort.SessionHandle;
import java.nio.file.Path;

/**
 * Complete pre-built inputs for {@link SpladeEncoder}. Produced by
 * {@code InferenceCompositionRoot.composeSpladeAssembly(...)} (tempdoc 397 §14.24 FD-SPLADE) or
 * by {@link SpladeEncoder#buildAssembly} (dev-mode fallback). Centralises every SPLADE
 * metadata I/O (tokenizer, vocabulary, output-format detection, evidence-path resolution) at
 * boot time.
 *
 * @param sessions ORT session handle
 * @param shape model-intrinsic facts (input + output names + max sequence length)
 * @param tokenizer pre-loaded DJL HuggingFace tokenizer
 * @param vocabulary pre-loaded WordPiece vocabulary (from {@code vocab.txt})
 * @param truncationEvidencePath path for truncation-evidence sidecar writes; null = disabled
 */
public record SpladeAssembly(
    SessionHandle sessions,
    SpladeShape shape,
    HuggingFaceTokenizer tokenizer,
    Vocabulary vocabulary,
    Path truncationEvidencePath) {}
