/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.prompts;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Represents a compiled prompt template with one or more named blocks. */
public final class PromptTemplate {
  private final PromptTemplateMetadata metadata;
  private final Map<String, PromptTemplateBlock> blocks;

  PromptTemplate(PromptTemplateMetadata metadata, Map<String, PromptTemplateBlock> blocks) {
    this.metadata = Objects.requireNonNull(metadata, "metadata");
    this.blocks = Collections.unmodifiableMap(blocks);
  }

  public PromptTemplateMetadata metadata() {
    return metadata;
  }

  public PromptTemplateBlock block(String name) throws PromptTemplateException {
    String normalized = name == null || name.isBlank() ? "default" : name;
    PromptTemplateBlock block = blocks.get(normalized);
    if (block == null) {
      throw new PromptTemplateException(
          "Template "
              + metadata.templateId()
              + " does not define block '"
              + normalized
              + "'");
    }
    return block;
  }

  public Map<String, PromptTemplateBlock> blocks() {
    return blocks;
  }

  public Optional<PromptTemplateBlock> maybeBlock(String name) {
    return Optional.ofNullable(blocks.get(name == null || name.isBlank() ? "default" : name));
  }
}
