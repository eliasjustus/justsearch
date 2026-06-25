/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.prompts;

import com.github.jknack.handlebars.Context;
import com.github.jknack.handlebars.Handlebars;
import com.github.jknack.handlebars.Template;
import com.google.common.base.Splitter;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class PromptTemplateBlock {
  private static final Splitter SECTION_SPLITTER =
      Splitter.on('.').omitEmptyStrings().trimResults();
  private final String name;
  private final Template template;
  private final TemplateAnalysis analysis;
  private final PromptTemplateMetadata metadata;

  PromptTemplateBlock(
      String name, String source, Handlebars handlebars, PromptTemplateMetadata metadata)
      throws PromptTemplateException {
    this.name = name == null || name.isBlank() ? "default" : name;
    this.metadata = Objects.requireNonNull(metadata, "metadata");
    this.analysis = TemplateAnalysis.analyze(source);
    try {
      this.template = handlebars.compileInline(source);
    } catch (IOException e) {
      throw new PromptTemplateException(
          "Failed to compile block '" + this.name + "' for template " + metadata.templateId(), e);
    }
    validatePlaceholders();
  }

  public PromptRenderResult render(Map<String, Object> context, Locale locale)
      throws PromptTemplateException {
    Context handlebarsContext = Context.newBuilder(context == null ? Map.of() : context).build();
    try {
      String text = template.apply(handlebarsContext);
      Map<String, Boolean> blockUsage = blockUsage(context);
      Map<String, Object> attributes = new LinkedHashMap<>(metadata.attributes());
      attributes.put("used_sections", blockUsage);
      attributes.put("rendered_locale", locale == null ? metadata.defaultLocale() : locale.toLanguageTag());
      return new PromptRenderResult(
          metadata.templateId(), name, attributes.get("rendered_locale").toString(), text, blockUsage, attributes);
    } catch (IOException e) {
      throw new PromptTemplateException(
          "Failed to render template " + metadata.templateId() + "#" + name, e);
    }
  }

  public Set<String> partials() {
    return analysis.partials();
  }

  public PromptTemplateMetadata metadata() {
    return metadata;
  }

  public String name() {
    return name;
  }

  private void validatePlaceholders() throws PromptTemplateException {
    Set<String> variables = analysis.variables();
    if (variables.isEmpty()) {
      return;
    }
    Set<String> declared = metadata.requiredParameters();
    if (declared.isEmpty() && !variables.isEmpty()) {
      throw new PromptTemplateException(
          "Template " + metadata.templateId() + " does not declare required_params");
    }
    for (String variable : variables) {
      if (variable.isBlank()) {
        continue;
      }
      String root = variable;
      int dot = variable.indexOf('.');
      if (dot > 0) {
        root = variable.substring(0, dot);
      }
      if (!declared.contains(root) && !declared.contains(variable)) {
        throw new PromptTemplateException(
            "Template "
                + metadata.templateId()
                + " references undeclared placeholder '"
                + variable
                + "'");
      }
    }
  }

  private Map<String, Boolean> blockUsage(Map<String, Object> context) {
    Map<String, Boolean> usage = new LinkedHashMap<>();
    for (String section : analysis.sections()) {
      Object value = resolve(context, section);
      usage.put(section, isTruthy(value));
    }
    return usage;
  }

  @SuppressWarnings("unchecked")
  private static Object resolve(Map<String, Object> context, String section) {
    if (context == null || section == null || section.isBlank()) {
      return null;
    }
    List<String> parts = SECTION_SPLITTER.splitToList(section);
    Object current = context;
    for (String part : parts) {
      if (!(current instanceof Map<?, ?> currentMap)) {
        return null;
      }
      current = currentMap.get(part);
      if (current == null) {
        return null;
      }
    }
    return current;
  }

  private static boolean isTruthy(Object value) {
    if (value == null) {
      return false;
    }
    if (value instanceof Boolean bool) {
      return bool;
    }
    if (value instanceof Number number) {
      return number.doubleValue() != 0.0d;
    }
    if (value instanceof CharSequence cs) {
      return !cs.toString().isBlank();
    }
    if (value instanceof java.util.Collection<?> collection) {
      return !collection.isEmpty();
    }
    return true;
  }
}
