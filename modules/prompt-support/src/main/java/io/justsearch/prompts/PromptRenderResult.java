/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.prompts;

import java.util.Map;

/** Immutable rendering result containing the prompt text and block usage metadata. */
public record PromptRenderResult(
    String templateId,
    String blockName,
    String locale,
    String text,
    Map<String, Boolean> blockUsage,
    Map<String, Object> attributes) {

  public PromptRenderResult {
    templateId = templateId == null ? "" : templateId;
    blockName = blockName == null ? "default" : blockName;
    locale = locale == null || locale.isBlank() ? "und" : locale;
    text = text == null ? "" : text;
    blockUsage = blockUsage == null ? Map.of() : Map.copyOf(blockUsage);
    attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
  }
}
