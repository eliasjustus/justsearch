/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.reranker;

/**
 * Model-intrinsic facts about a cross-encoder (reranker or citation scorer) session. Tempdoc
 * 397 §14.24 FD: lets {@link CrossEncoderReranker} and {@link CitationScorer} receive
 * input-schema metadata via typed record instead of loading it from the session at construction
 * time. The composition root reads input names once from
 * {@link io.justsearch.ort.SessionHandle#inputNames()} and caches them here; the encoder does
 * no I/O in its constructor.
 *
 * <p>Shared across both reranker and citation because the ONNX graph shape is identical
 * (cross-encoder; {@code token_type_ids} is model-intrinsic).
 *
 * @param maxSequenceLength maximum tokens per inference call (typically 512)
 * @param needsTokenTypeIds true if the ONNX graph declares {@code token_type_ids} among its
 *     input names — determines whether the encoder builds a {@code token_type_ids} tensor per
 *     call
 */
public record RerankerShape(int maxSequenceLength, boolean needsTokenTypeIds) {}
