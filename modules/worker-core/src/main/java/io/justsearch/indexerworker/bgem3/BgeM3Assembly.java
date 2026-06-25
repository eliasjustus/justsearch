/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.bgem3;

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import io.justsearch.ort.SessionHandle;

/**
 * Complete pre-built inputs for {@link BgeM3Encoder}. Tempdoc 397 §14.24 FD-BgeM3 — produced
 * by {@code InferenceCompositionRoot.composeBgeM3Assembly(...)} or by
 * {@link BgeM3Encoder#buildAssembly}. Centralises every BGE-M3 metadata I/O (tokenizer,
 * vocabulary parse) at boot time.
 *
 * @param sessions ORT session handle
 * @param shape model-intrinsic facts (max sequence length + vocabulary)
 * @param tokenizer pre-loaded DJL HuggingFace tokenizer
 */
public record BgeM3Assembly(
    SessionHandle sessions, BgeM3Shape shape, HuggingFaceTokenizer tokenizer) {}
