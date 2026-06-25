/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.api.registry;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Wire interface contract for an Operation: input schema, result schema, error codes,
 * and per-input UI rendering hints.
 *
 * <p>Per tempdoc 429 §C.E: {@code uiHints} is the Layer-1 escape hatch from
 * 421-extensibility.md §"Three layers, designed-in from day one" — optional per-field
 * widget hints that the FE generic renderer consumes when JSON-Schema-driven widget
 * choice is ambiguous (e.g., string-array → multi-select vs autocomplete).
 *
 * <p>{@code inputs} and {@code result} carry JSON Schema source text (not parsed
 * {@code JsonNode}) because {@code app-agent-api} has no Jackson databind dependency
 * (only annotations per §E.5). Emitters in {@code app-services} parse the schema
 * source via their own ObjectMapper before composing the wire JSON for OpenAI / FE
 * consumers.
 *
 * <p>{@code errors} enumerates the error codes this operation may surface. Validators
 * verify each code resolves to an entry in the active error catalog (per slice 1.1.d /
 * tempdoc 431).
 */
public record Interface(
    String inputs,
    String result,
    List<String> errors,
    Map<String, UIHint> uiHints) {

  public Interface {
    Objects.requireNonNull(inputs, "inputs");
    Objects.requireNonNull(result, "result");
    errors = errors == null ? List.of() : List.copyOf(errors);
    uiHints = uiHints == null ? Map.of() : Map.copyOf(uiHints);
  }

  /** Convenience: build an Interface with no uiHints or errors declared. */
  public static Interface of(String inputs, String result) {
    return new Interface(inputs, result, List.of(), Map.of());
  }

  /** Convenience for operations with no result (NOT_IMPLEMENTED stubs). */
  public static Interface inputsOnly(String inputs) {
    return new Interface(inputs, "{}", List.of(), Map.of());
  }
}
