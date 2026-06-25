/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api.dto;

/**
 * LLM/inference settings in the v2 canonical contract.
 *
 * <p>Matches the frontend's {@code LLMSettings} interface.
 */
public record LlmSettingsV2(
    String serverExecutable,
    Integer contextWindow,
    Integer maxTokens,
    Integer gpuLayers,
    String modelPath,
    String llamaLibPath
) {
  public static LlmSettingsV2 defaults() {
    return new LlmSettingsV2(null, 4096, 1024, 0, null, null);
  }
}
