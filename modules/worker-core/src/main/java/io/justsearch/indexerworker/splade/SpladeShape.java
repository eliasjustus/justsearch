/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.splade;

/**
 * Model-intrinsic facts about a SPLADE session. Tempdoc 397 §14.24 FD-SPLADE: lets
 * {@link SpladeEncoder} receive input-schema + output-format metadata via typed record instead
 * of probing the session at construction time.
 *
 * @param maxSequenceLength maximum tokens per inference call (typically 512)
 * @param needsTokenTypeIds true if the ONNX graph declares {@code token_type_ids} among its
 *     input names
 * @param outputFormat inferred from the ONNX output-name set — {@link SpladeEncoder.OutputFormat#PRESPARSE}
 *     when both {@code output_idx} and {@code output_weights} are present, else
 *     {@link SpladeEncoder.OutputFormat#MLM_LOGITS}
 * @param outputName primary output name for the MLM_LOGITS path ({@code "logits"} when
 *     present, else the first listed output); null when {@code outputFormat == PRESPARSE}
 */
public record SpladeShape(
    int maxSequenceLength,
    boolean needsTokenTypeIds,
    SpladeEncoder.OutputFormat outputFormat,
    String outputName) {}
