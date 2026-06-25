/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.api.registry;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Argument-bearing reference to an Operation invocation.
 *
 * <p>Per slice 447 §X.5 + 447-impl-B: where a recovery slot or other cross-link needs to
 * carry default arguments alongside the Operation reference, this record bundles them. The
 * {@link #target} is the Operation id (per-primitive typed id; see slice 447-impl-A); the
 * {@link #defaultArgsJson} is the JSON object source text encoding the default arguments.
 *
 * <p><strong>Architectural boundary:</strong> {@code app-agent-api} has no Jackson databind
 * dependency (annotations only, per {@code Interface.java:16-19} §E.5). The
 * {@code defaultArgsJson} field carries JSON as String source text rather than a parsed
 * {@code JsonNode} so the boundary is preserved.
 *
 * <p><strong>Validation tier (447-followup/3.1)</strong>: the compact constructor enforces
 * a minimal structural check — the value must be a non-blank string matching
 * {@link #JSON_OBJECT_SHAPE}, i.e., {@code ^\s*\{[\s\S]*\}\s*$}. This catches obviously
 * broken inputs (non-object text, truncated braces) without parsing JSON. Full JSON
 * well-formedness validation (key/value structure, escape correctness, balanced braces
 * inside string literals) is the <strong>consumer's responsibility</strong> — typically
 * {@code OperationExecutorImpl} when applying defaults before invocation, where Jackson
 * databind is already available.
 *
 * <p>The two-tier validation matches the §E.5 boundary: app-agent-api gives a fast-fail
 * shape check at construction; consumers do the full parse where they're already coupled
 * to a JSON library.
 *
 * <p>Convention: pass {@code "{}"} for the no-args case via {@link #of(OperationRef)}; pass
 * a JSON object literal source string (e.g., {@code "{\"force\":true}"}) for the
 * args-bearing case via the canonical constructor.
 */
public record OperationInvocation(OperationRef target, String defaultArgsJson) {

  /**
   * Minimum-viable shape regex: leading whitespace, opening brace, any content (including
   * nested braces / nested objects / strings with escaped quotes), closing brace, trailing
   * whitespace. Does NOT validate full JSON well-formedness — the consumer's parser does
   * that. Catches the most common malformed inputs (non-object literals, truncated input).
   */
  private static final Pattern JSON_OBJECT_SHAPE = Pattern.compile("^\\s*\\{[\\s\\S]*\\}\\s*$");

  public OperationInvocation {
    Objects.requireNonNull(target, "target");
    Objects.requireNonNull(defaultArgsJson, "defaultArgsJson");
    if (defaultArgsJson.isBlank()) {
      throw new IllegalArgumentException(
          "defaultArgsJson must be non-blank (use \"{}\" for the no-args case)");
    }
    if (!JSON_OBJECT_SHAPE.matcher(defaultArgsJson).matches()) {
      throw new IllegalArgumentException(
          "defaultArgsJson must be a JSON object literal (\"{...}\"); got: " + defaultArgsJson);
    }
  }

  /** Convenience: invocation with empty default arguments. */
  public static OperationInvocation of(OperationRef target) {
    return new OperationInvocation(target, "{}");
  }
}
