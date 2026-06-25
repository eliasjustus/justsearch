/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.selection;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.soabase.recordbuilder.core.RecordBuilder;
import java.util.Objects;

/**
 * Universal dispatch envelope for "do an operation, with a selection, from a source" per
 * tempdoc 526 §4.5. The FE collapses today's {@code askAi} typed intent helpers to
 * {@code compose()} calls that build one of these.
 *
 * <p>v1 is a minimal flat envelope. The {@code (operation, selection.kind) → ShapeId}
 * resolution table is hand-rolled in the FE for current shapes (4 rows: summarize+item,
 * summarize+text-range, ask+item, ask+text-range). The full {@code SelectionActions} registry
 * that would make this data-driven lands with F9 per substrate-without-consumer-flavors.
 *
 * <p>v1 does NOT add a new {@code TransportTag.COMPOSE} value: the migrating call sites
 * (BrowseSurface context menu, SearchSurface ask) keep their existing transports (BUTTON).
 * {@code compose()} is an FE dispatcher helper, not a new provenance.
 *
 * <p>{@code selection} and {@code userPrompt} are nullable — present-or-absent semantics use
 * Java {@code null} (matching the app-api {@code KnowledgeSearchResponse} precedent) rather
 * than {@code Optional<T>}, which avoids a new Jackson Jdk8Module dependency.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@RecordBuilder
public record ComposeIntent(
    SelectionPayload selection, String operation, String userPrompt, String source) {
  public ComposeIntent {
    Objects.requireNonNull(operation, "operation");
    Objects.requireNonNull(source, "source");
  }
}
