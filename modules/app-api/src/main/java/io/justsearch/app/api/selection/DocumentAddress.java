/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.selection;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import io.soabase.recordbuilder.core.RecordBuilder;
import java.util.Objects;

/**
 * Typed address into a document, naming the coordinate system the offsets refer to.
 *
 * <p>Sealed type per tempdoc 526 §4.2 — names the "which content do these offsets address?"
 * question at the type-system level so that selection requests can no longer silently send
 * preview-window offsets that the backend resolves against canonical content.
 *
 * <p>Jackson discriminator: {@code "coords"} field with values {@code "canonical"},
 * {@code "display"} — matching the FE shape declared in
 * {@code modules/ui-web/src/api/types/selection.ts} per the {@code ConfirmStrategy} precedent
 * (lowercase wire names mirror the FE discriminator).
 *
 * <p>Variants: {@link Canonical} (canonical-char offsets), {@link Display} (offsets in a
 * view-formatted projection), {@link Lines} (line-based ranges — preserved for citation
 * rendering), and {@link Opaque} (forward-compat catch-all for AST paths, PDF object refs,
 * etc.).
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "coords")
@JsonSubTypes({
  @JsonSubTypes.Type(value = DocumentAddress.Canonical.class, name = "canonical"),
  @JsonSubTypes.Type(value = DocumentAddress.Display.class, name = "display"),
  @JsonSubTypes.Type(value = DocumentAddress.Lines.class, name = "lines")
})
public sealed interface DocumentAddress
    permits DocumentAddress.Canonical, DocumentAddress.Display, DocumentAddress.Lines {

  /** Document id this address belongs to. */
  String docId();

  /** Canonical-content coordinates. The backend substrings the canonical document directly. */
  @RecordBuilder
  record Canonical(String docId, int startChar, int endChar) implements DocumentAddress {
    public Canonical {
      Objects.requireNonNull(docId, "docId");
      if (startChar < 0 || endChar < startChar) {
        throw new IllegalArgumentException(
            "invalid canonical range: startChar=" + startChar + " endChar=" + endChar);
      }
    }
  }

  /**
   * Display-coordinate address — offsets are within a view-formatted projection of the document
   * (e.g., the {@code preview-5k} window InspectorPane renders today). The backend must resolve
   * display→canonical before substringing.
   *
   * <p>v1 supports {@code viewId == "preview-5k"} with an identity-map resolution: offsets in
   * {@code [0, 5000)} of the display map directly to the same canonical offsets, verified
   * empirically against {@code /api/preview} byte-by-byte (tempdoc 526 §12.9 E1). Other view
   * formats (markdown-rendered, paginated, syntax-highlighted) will land with the first
   * consumer that needs them.
   *
   * <p>{@code canonicalHint} carries a FE-computed estimate of canonical coordinates when known
   * (for view formats that are identity-mappable). Backend treats it as advisory and may
   * recompute.
   */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  @RecordBuilder
  record Display(
      String docId, String viewId, int displayStart, int displayEnd, CanonicalHint canonicalHint)
      implements DocumentAddress {
    public Display {
      Objects.requireNonNull(docId, "docId");
      Objects.requireNonNull(viewId, "viewId");
      if (displayStart < 0 || displayEnd < displayStart) {
        throw new IllegalArgumentException(
            "invalid display range: displayStart=" + displayStart + " displayEnd=" + displayEnd);
      }
    }
  }

  /** Optional FE-supplied hint that the display offsets map to these canonical offsets. */
  record CanonicalHint(int startChar, int endChar) {
    public CanonicalHint {
      if (startChar < 0 || endChar < startChar) {
        throw new IllegalArgumentException(
            "invalid canonical hint: startChar=" + startChar + " endChar=" + endChar);
      }
    }
  }

  /**
   * Line-based address — preserved per tempdoc 526 §4.2 for citation rendering (the
   * {@code highlightCitation(startLine, endLine)} consumer). Indices are 0-based inclusive,
   * matching {@code ContextCitation}'s convention.
   */
  @RecordBuilder
  record Lines(String docId, int startLine, int endLine) implements DocumentAddress {
    public Lines {
      Objects.requireNonNull(docId, "docId");
      if (startLine < 0 || endLine < startLine) {
        throw new IllegalArgumentException(
            "invalid line range: startLine=" + startLine + " endLine=" + endLine);
      }
    }
  }

}
