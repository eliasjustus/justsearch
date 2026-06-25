/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.api.registry;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.util.List;
import java.util.Objects;

/**
 * Per-input rendering hint for the FE generic-renderer dispatcher.
 *
 * <p>Per tempdoc 429 §C.E + 421-extensibility.md §"Three layers, designed-in from day one"
 * Layer 1: hints describe the input's nature; the FE renderer maps to widgets via its
 * own theme conventions. Optional per field; the renderer falls back to JSON-Schema-driven
 * widget choice when absent.
 *
 * <p>Sealed type permits a closed-set V1 vocabulary; new variants are additive in V1.5+.
 * Jackson discriminator: {@code "type"} field per §A.3 + §E.10 verified pattern.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = UIHint.MultiSelect.class, name = "multi-select"),
    @JsonSubTypes.Type(value = UIHint.Autocomplete.class, name = "autocomplete"),
    @JsonSubTypes.Type(value = UIHint.FilePicker.class, name = "file-picker"),
    @JsonSubTypes.Type(value = UIHint.Slider.class, name = "slider"),
    @JsonSubTypes.Type(value = UIHint.CodeEditor.class, name = "code-editor")
})
public sealed interface UIHint
    permits UIHint.MultiSelect,
        UIHint.Autocomplete,
        UIHint.FilePicker,
        UIHint.Slider,
        UIHint.CodeEditor {

  /** Render as a multi-select list (typically string[] inputs with bounded choices). */
  record MultiSelect(List<String> choices) implements UIHint {
    public MultiSelect {
      Objects.requireNonNull(choices, "choices");
      choices = List.copyOf(choices);
    }
  }

  /**
   * Render as a typeahead/autocomplete input. The FE may call a configured suggestion
   * source via {@code suggestionEndpoint} (template URL with {@code {query}} placeholder)
   * or surface static hints via {@code staticChoices}.
   */
  record Autocomplete(String suggestionEndpoint, List<String> staticChoices) implements UIHint {
    public Autocomplete {
      staticChoices = staticChoices == null ? List.of() : List.copyOf(staticChoices);
    }
  }

  /** Render as a file-picker dialog (single or multi-file). */
  record FilePicker(boolean multiple, List<String> acceptedExtensions) implements UIHint {
    public FilePicker {
      acceptedExtensions = acceptedExtensions == null ? List.of() : List.copyOf(acceptedExtensions);
    }
  }

  /** Render as a slider (numeric inputs with a bounded range). */
  record Slider(double min, double max, double step) implements UIHint {}

  /** Render as a code editor with optional language-server hooks. */
  record CodeEditor(String language) implements UIHint {
    public CodeEditor {
      Objects.requireNonNull(language, "language");
    }
  }
}
