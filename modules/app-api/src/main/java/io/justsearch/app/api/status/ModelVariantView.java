/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.status;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Per-model variant status within the model distribution view.
 *
 * @param filename model file name (e.g., "model_fp16.onnx")
 * @param precision precision: FP32, FP16, INT8, GGUF
 * @param executionProvider execution provider: CPU, CUDA, LLAMA_SERVER
 * @param degraded true if running a suboptimal variant for the hardware
 * @param degradationReason human-readable degradation reason (null if not degraded)
 * @param skipped true if this model was skipped (insufficient hardware)
 * @param skipReason reason the model was skipped (null if not skipped)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ModelVariantView(
    String filename,
    String precision,
    String executionProvider,
    boolean degraded,
    String degradationReason,
    boolean skipped,
    String skipReason) {}
