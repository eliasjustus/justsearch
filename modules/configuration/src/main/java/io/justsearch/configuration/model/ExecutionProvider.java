/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.configuration.model;

/** ONNX Runtime execution provider that a model variant targets. */
public enum ExecutionProvider {
  /** CPU execution provider. Default, always available. */
  CPU,

  /** NVIDIA CUDA execution provider. Requires CUDA runtime DLLs. */
  CUDA,

  /** llama-server runtime for GGUF models. Requires GPU VRAM for acceptable performance. */
  LLAMA_SERVER
}
