/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.api.registry;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Stable, namespaced identifier for an {@link IntentSource} catalog entry.
 *
 * <p>Per tempdoc 487 §4.1: {@code IntentSource} is the fourth Manifest tier
 * alongside Plugin, Surface, and ConversationShape. This Ref participates in
 * the {@link RegistryRef} sealed permit list so cross-reference shape
 * factoring works (a {@code RegistryRef<IntentSource>} reads unambiguously as
 * "reference to an IntentSource").
 *
 * <p>Format mirrors {@link OperationRef} / {@link SurfaceRef} /
 * {@link ConversationShapeRef}: {@code ^(core|vendor\.\w+)\.[a-z][a-z0-9-]*$}.
 * Per the platform's flat-namespace convention, multi-word source ids use
 * hyphens within the id segment rather than additional dots. Examples:
 * {@code core.ui-rail}, {@code core.ui-palette}, {@code core.url-bar},
 * {@code core.url-deeplink}, {@code core.llm-chat-emission},
 * {@code core.llm-agent-tool-call}, {@code vendor.acme.scheduled-trigger}.
 */
public record IntentSourceRef(@JsonValue String value) implements RegistryRef<IntentSource> {

  @JsonCreator
  public IntentSourceRef {
    value = NamespacedId.validate(value, "IntentSourceRef");
  }

  @Override
  public String toString() {
    return value;
  }
}
