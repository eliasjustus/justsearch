/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.selection;

import io.soabase.recordbuilder.core.RecordBuilder;
import java.util.Objects;

/**
 * Typed citation locator used by {@link SelectionPayload.Citation} for G21 kind-flip
 * promotion of a previously-emitted citation back into an active selection.
 *
 * <p>Tempdoc 526 §16 retraction: this type started life as a sealed sum with five variants
 * (CharLocation, PageLocation, ContentBlockLocation, SearchResultLocation, UnknownLocation).
 * Only {@code CharLocation} ever had a producer; the other variants were forward-compat
 * decoration. Per {@code substrate-without-consumer-flavors}, the sum collapses to this flat
 * record. New locator variants land as a sealed sum widening when their producer ships.
 */
@RecordBuilder
public record SourceCitation(String parentDocId, int startChar, int endChar, String excerpt) {
  public SourceCitation {
    Objects.requireNonNull(parentDocId, "parentDocId");
    if (startChar < 0 || endChar < startChar) {
      throw new IllegalArgumentException(
          "invalid char range: startChar=" + startChar + " endChar=" + endChar);
    }
    excerpt = excerpt == null ? "" : excerpt;
  }
}
