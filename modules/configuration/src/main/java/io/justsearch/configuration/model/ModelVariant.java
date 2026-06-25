/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.configuration.model;

/**
 * A specific variant of an ONNX model file — one precision/EP combination.
 *
 * <p>A model (e.g., "embedding") has multiple variants: FP32 for CPU, FP16 for CUDA. Each variant
 * describes one downloadable file with its metadata.
 *
 * @param filename the ONNX model filename (e.g., "model.onnx", "model_fp16.onnx")
 * @param precision numeric precision of the model weights
 * @param targetEP the execution provider this variant is optimized for
 * @param sha256 uppercase hex SHA-256 hash of the file
 * @param sizeBytes file size in bytes
 * @param downloadUrl HTTPS URL to download the file
 */
public record ModelVariant(
    String filename,
    ModelPrecision precision,
    ExecutionProvider targetEP,
    String sha256,
    long sizeBytes,
    String downloadUrl) {

  /** Returns true if this variant requires CUDA (i.e., targets CUDA EP). */
  public boolean requiresCuda() {
    return targetEP == ExecutionProvider.CUDA;
  }
}
