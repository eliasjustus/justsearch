/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.reranker;

import io.justsearch.ort.SessionHandle;

/**
 * Complete pre-built inputs for {@link CrossEncoderReranker} and {@link CitationScorer}.
 * Tempdoc 397 §14.24 FD — produced by {@code InferenceCompositionRoot.composeRerankAssembly}
 * / {@code composeCitationAssembly} or by the static {@code buildAssembly} helpers on each
 * encoder (dev-mode fallback).
 *
 * <p>Citation and reranker share the same assembly shape because they have identical
 * construction inputs (cross-encoder model, tokenizer, input-schema facts). The caller
 * determines which concrete encoder to build.
 *
 * @param sessions ORT session handle
 * @param shape model-intrinsic facts (input-name detection + max sequence length)
 * @param tokenizer pre-loaded reranker tokenizer
 */
public record RerankerAssembly(
    SessionHandle sessions, RerankerShape shape, RerankerTokenizer tokenizer) {}
