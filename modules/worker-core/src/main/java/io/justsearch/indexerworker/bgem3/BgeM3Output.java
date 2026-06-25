/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.bgem3;

import java.util.Map;

/**
 * Combined dense + sparse output from BGE-M3 encoder.
 *
 * @param denseVector 1024-dimensional L2-normalized embedding (CLS pooling)
 * @param sparseWeights token string → weight (same format as SPLADE output)
 */
public record BgeM3Output(float[] denseVector, Map<String, Float> sparseWeights) {}
