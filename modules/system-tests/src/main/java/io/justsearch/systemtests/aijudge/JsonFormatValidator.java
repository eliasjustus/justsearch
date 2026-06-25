/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.systemtests.aijudge;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * AI Judge component that validates JSON format of AI-generated intent output.
 *
 * <p>The intent translation pipeline outputs structured JSON. This validator ensures:
 * <ul>
 *   <li>Output is valid JSON</li>
 *   <li>Required fields are present</li>
 *   <li>Field types are correct</li>
 *   <li>Values are within expected ranges</li>
 * </ul>
 *
 * <p><b>Expected Intent JSON Schema:</b>
 * <pre>{@code
 * {
 *   "limit": 10,              // integer, > 0
 *   "highlight": true,        // boolean
 *   "clauses": [              // array, non-empty
 *     {
 *       "type": "text",       // "text" | "filter" | "date_range"
 *       "value": "query"      // string
 *     }
 *   ],
 *   "filters": {              // optional object
 *     "language": "en"
 *   }
 * }
 * }</pre>
 */
public final class JsonFormatValidator {
  private static final ObjectMapper MAPPER = new ObjectMapper();

  // Required top-level fields
  private static final Set<String> REQUIRED_FIELDS = Set.of("limit", "clauses");

  // Valid clause types
  private static final Set<String> VALID_CLAUSE_TYPES = Set.of(
      "text", "filter", "date_range", "path", "extension", "tag"
  );

  /**
   * Validates AI-generated intent JSON.
   *
   * @param json The JSON string to validate
   * @return Validation result
   */
  public ValidationResult validateIntent(String json) {
    List<String> errors = new ArrayList<>();
    List<String> warnings = new ArrayList<>();

    if (json == null || json.isBlank()) {
      return new ValidationResult(false, null, List.of("empty or null JSON"), List.of());
    }

    // Strip markdown code blocks if present (small models often wrap JSON in ```json ... ```)
    String cleanedJson = stripMarkdownCodeBlock(json);

    // 1. Parse JSON
    JsonNode root;
    try {
      root = MAPPER.readTree(cleanedJson);
    } catch (JacksonException e) {
      return new ValidationResult(false, null,
          List.of("invalid JSON: " + e.getMessage()), List.of());
    }

    // 2. Check it's an object
    if (!root.isObject()) {
      errors.add("root must be an object, got: " + root.getNodeType());
      return new ValidationResult(false, root, errors, warnings);
    }

    // 3. Check required fields
    for (String field : REQUIRED_FIELDS) {
      if (!root.has(field) || root.get(field).isNull()) {
        errors.add("missing required field: " + field);
      }
    }

    // 4. Validate limit
    if (root.has("limit")) {
      JsonNode limit = root.get("limit");
      if (!limit.isInt()) {
        errors.add("'limit' must be an integer, got: " + limit.getNodeType());
      } else if (limit.asInt() <= 0) {
        errors.add("'limit' must be > 0, got: " + limit.asInt());
      } else if (limit.asInt() > 1000) {
        warnings.add("'limit' is unusually high: " + limit.asInt());
      }
    }

    // 5. Validate highlight (optional)
    if (root.has("highlight") && !root.get("highlight").isBoolean()) {
      errors.add("'highlight' must be a boolean");
    }

    // 6. Validate clauses
    if (root.has("clauses")) {
      JsonNode clauses = root.get("clauses");
      if (!clauses.isArray()) {
        errors.add("'clauses' must be an array");
      } else if (clauses.isEmpty()) {
        warnings.add("'clauses' array is empty");
      } else {
        for (int i = 0; i < clauses.size(); i++) {
          validateClause(clauses.get(i), i, errors, warnings);
        }
      }
    }

    // 7. Validate filters (optional)
    if (root.has("filters") && !root.get("filters").isNull()) {
      JsonNode filters = root.get("filters");
      if (!filters.isObject()) {
        errors.add("'filters' must be an object");
      }
    }

    boolean valid = errors.isEmpty();
    return new ValidationResult(valid, root, errors, warnings);
  }

  /**
   * Validates that JSON is parseable (minimal check).
   *
   * @param json The JSON string
   * @return true if valid JSON
   */
  public boolean isValidJson(String json) {
    if (json == null || json.isBlank()) {
      return false;
    }
    try {
      // Strip markdown code blocks if present
      MAPPER.readTree(stripMarkdownCodeBlock(json));
      return true;
    } catch (JacksonException e) {
      return false;
    }
  }

  /**
   * Strips markdown code block wrappers from LLM output.
   *
   * <p>Small models often output JSON wrapped in markdown:
   * <pre>{@code
   * ```json
   * {"key": "value"}
   * ```
   * }</pre>
   *
   * <p>This method extracts just the JSON content.
   *
   * @param text The potentially markdown-wrapped text
   * @return Cleaned text with code blocks removed
   */
  public static String stripMarkdownCodeBlock(String text) {
    if (text == null) {
      return null;
    }

    String trimmed = text.strip();

    // Pattern 1: ```json ... ``` or ```JSON ... ``` (multiline)
    if (trimmed.startsWith("```")) {
      int firstNewline = trimmed.indexOf('\n');
      if (firstNewline > 0) {
        // Skip the opening ``` line
        String afterOpening = trimmed.substring(firstNewline + 1);
        // Find the closing ```
        int closingIndex = afterOpening.lastIndexOf("```");
        if (closingIndex > 0) {
          return afterOpening.substring(0, closingIndex).strip();
        }
        // No closing found, just strip the opening
        return afterOpening.strip();
      } else {
        // Opening ``` with no newline - strip it
        String withoutOpening = trimmed.substring(3).strip();
        // Check if there's a language tag
        if (withoutOpening.toLowerCase(Locale.ROOT).startsWith("json")) {
          withoutOpening = withoutOpening.substring(4).strip();
        }
        // Check for closing ```
        if (withoutOpening.endsWith("```")) {
          return withoutOpening.substring(0, withoutOpening.length() - 3).strip();
        }
        return withoutOpening;
      }
    }

    // Pattern 2: Inline backticks `{...}`
    if (trimmed.startsWith("`") && trimmed.endsWith("`") && trimmed.length() > 2) {
      return trimmed.substring(1, trimmed.length() - 1).strip();
    }

    // Pattern 3: Just starts with backtick (malformed)
    if (trimmed.startsWith("`")) {
      // Strip all leading/trailing backticks
      String cleaned = trimmed;
      while (cleaned.startsWith("`")) {
        cleaned = cleaned.substring(1);
      }
      while (cleaned.endsWith("`")) {
        cleaned = cleaned.substring(0, cleaned.length() - 1);
      }
      // Check for json language tag
      if (cleaned.toLowerCase(Locale.ROOT).startsWith("json")) {
        cleaned = cleaned.substring(4);
      }
      return cleaned.strip();
    }

    return trimmed;
  }

  /**
   * Extracts a field value from JSON.
   *
   * @param json The JSON string
   * @param fieldPath Path to field (e.g., "filters.language")
   * @return Field value as string, or null if not found
   */
  public String extractField(String json, String fieldPath) {
    try {
      JsonNode root = MAPPER.readTree(json);
      String[] parts = fieldPath.split("\\.");
      JsonNode current = root;

      for (String part : parts) {
        if (current == null || !current.has(part)) {
          return null;
        }
        current = current.get(part);
      }

      return current.isTextual() ? current.asText() : current.toString();
    } catch (JacksonException e) {
      return null;
    }
  }

  /**
   * Parses JSON and returns the root node.
   *
   * @param json The JSON string
   * @return JsonNode or null if invalid
   */
  public JsonNode parse(String json) {
    try {
      return MAPPER.readTree(json);
    } catch (JacksonException e) {
      return null;
    }
  }

  private void validateClause(JsonNode clause, int index, List<String> errors, List<String> warnings) {
    String prefix = "clauses[" + index + "]";

    if (!clause.isObject()) {
      errors.add(prefix + " must be an object");
      return;
    }

    // Check type field
    if (!clause.has("type")) {
      errors.add(prefix + " missing 'type' field");
    } else {
      String type = clause.get("type").asText();
      if (!VALID_CLAUSE_TYPES.contains(type)) {
        warnings.add(prefix + " has unknown type: " + type);
      }
    }

    // Check value field
    if (!clause.has("value")) {
      errors.add(prefix + " missing 'value' field");
    } else if (!clause.get("value").isTextual() && !clause.get("value").isArray()) {
      errors.add(prefix + ".value must be string or array");
    }
  }

  // === Result types ===

  /**
   * Result of JSON validation.
   */
  public record ValidationResult(
      boolean valid,
      JsonNode parsedJson,
      List<String> errors,
      List<String> warnings
  ) {
    /**
     * Returns true if valid with no warnings.
     */
    public boolean isClean() {
      return valid && warnings.isEmpty();
    }

    /**
     * Returns all issues (errors + warnings).
     */
    public List<String> allIssues() {
      List<String> all = new ArrayList<>(errors);
      all.addAll(warnings);
      return all;
    }

    /**
     * Returns formatted error message.
     */
    public String errorMessage() {
      if (valid) {
        return warnings.isEmpty() ? "valid" : "valid with warnings: " + warnings;
      }
      return "invalid: " + errors;
    }
  }
}
