/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.prompts;

import tools.jackson.databind.JsonNode;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class PromptTemplateMetadata {
  private final String templateId;
  private final String schemaVersion;
  private final String defaultLocale;
  private final String taskId;
  private final Map<String, Object> attributes;
  private final Set<String> requiredParameters;

  PromptTemplateMetadata(
      String templateId,
      String schemaVersion,
      String defaultLocale,
      String taskId,
      Map<String, Object> attributes,
      Set<String> requiredParameters) {
    this.templateId = normalize(templateId, "template_id");
    this.schemaVersion = normalize(schemaVersion, "schema_ver");
    this.defaultLocale = normalize(defaultLocale, "default_locale");
    this.taskId = normalize(taskId, "task_id");
    this.attributes = Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
    this.requiredParameters = Collections.unmodifiableSet(new LinkedHashSet<>(requiredParameters));
  }

  public String templateId() {
    return templateId;
  }

  public String schemaVersion() {
    return schemaVersion;
  }

  public String defaultLocale() {
    return defaultLocale;
  }

  public String taskId() {
    return taskId;
  }

  public Map<String, Object> attributes() {
    return attributes;
  }

  public Set<String> requiredParameters() {
    return requiredParameters;
  }

  static PromptTemplateMetadata fromJson(JsonNode node) throws PromptTemplateException {
    if (node == null || !node.isObject()) {
      throw new PromptTemplateException("Template front matter must be a JSON object");
    }
    String templateId = text(node, "template_id");
    String schemaVersion = text(node, "schema_ver");
    String defaultLocale = text(node, "default_locale");
    String taskId = text(node, "task_id");
    Map<String, Object> attrs = new LinkedHashMap<>();
    for (var entry : node.properties()) {
      String key = entry.getKey();
      if ("template_id".equals(key)
          || "schema_ver".equals(key)
          || "default_locale".equals(key)
          || "task_id".equals(key)
          || "required_params".equals(key)) {
        continue;
      }
      attrs.put(key, extractValue(entry.getValue()));
    }
    Set<String> required =
        node.has("required_params")
            ? asStringSet(node.get("required_params"))
            : Set.of();
    return new PromptTemplateMetadata(templateId, schemaVersion, defaultLocale, taskId, attrs, required);
  }

  private static String text(JsonNode node, String field) throws PromptTemplateException {
    JsonNode value = node.get(field);
    if (value == null || value.asText().isBlank()) {
      throw new PromptTemplateException("Missing required front matter field '" + field + "'");
    }
    return value.asText().trim();
  }

  private static Object extractValue(JsonNode node) {
    if (node == null || node.isNull()) {
      return null;
    }
    if (node.isNumber()) {
      return node.numberValue();
    }
    if (node.isBoolean()) {
      return node.booleanValue();
    }
    if (node.isArray()) {
      java.util.List<Object> list = new java.util.ArrayList<>();
      for (JsonNode child : node) {
        list.add(extractValue(child));
      }
      return Collections.unmodifiableList(list);
    }
    if (node.isObject()) {
      Map<String, Object> nested = new LinkedHashMap<>();
      for (var entry : node.properties()) {
        nested.put(entry.getKey(), extractValue(entry.getValue()));
      }
      return Collections.unmodifiableMap(nested);
    }
    return node.asText();
  }

  private static Set<String> asStringSet(JsonNode node) {
    if (node == null || !node.isArray()) {
      return Set.of();
    }
    Set<String> values = new LinkedHashSet<>();
    for (JsonNode entry : node) {
      if (entry.isTextual() && !entry.asText().isBlank()) {
        values.add(entry.asText().trim().toLowerCase(Locale.ROOT));
      }
    }
    return values;
  }

  private static String normalize(String value, String field) {
    Objects.requireNonNull(value, field);
    String trimmed = value.trim();
    if (trimmed.isEmpty()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    return trimmed;
  }
}
