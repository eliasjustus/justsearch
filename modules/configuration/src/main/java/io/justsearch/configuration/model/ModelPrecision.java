/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.configuration.model;

/**
 * Numeric precision of an ONNX model file.
 *
 * <p>Determines which execution provider the model is optimized for and what graph optimization
 * behavior to expect:
 *
 * <ul>
 *   <li>{@code FP32} — Full precision. Native to CPU EP. No Cast node overhead.
 *   <li>{@code FP16} — Half precision. Native to CUDA EP. Catastrophic on CPU EP (ORT inserts
 *       Cast FP16→FP32 nodes; EXTENDED_OPT takes 30+ min).
 *   <li>{@code INT8} — 8-bit quantized. Efficient on CPU EP. Smallest footprint.
 * </ul>
 */
public enum ModelPrecision {
  FP32,
  FP16,
  INT8,

  /** GGUF quantized format (e.g., Q4_K_M). Runs through llama-server, not ORT. */
  GGUF
}
