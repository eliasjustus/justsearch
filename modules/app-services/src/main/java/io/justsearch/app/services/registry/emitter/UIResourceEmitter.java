/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.registry.emitter;

import io.justsearch.agent.api.registry.ConsumerView;
import io.justsearch.agent.api.registry.Resource;
import io.justsearch.agent.api.registry.UIResourceView;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.ObjectMapper;

/**
 * Projects a {@link Resource} onto the typed {@link UIResourceView} wire record served at {@code
 * /api/registry/resources}.
 *
 * <p>Tempdoc 560 §4c: the Resource wire now has ONE typed authority ({@code UIResourceView}) whose
 * record→JSON-Schema→{TS,Zod} projection is faithful AND precise. The projection is a
 * component-for-component copy of the {@link Resource} record (same value types, so {@code
 * convertValue} serialization is identical by construction) with the single intended divergence that
 * {@code consumers} are flattened to the discriminator-free {@link ConsumerView}. {@code
 * RegistryController} overwrites {@code consumers} with the Surface-merged set (also projected to
 * {@code ConsumerView}); the declared-only projection here is what {@code
 * UIResourceViewConformanceTest} pins against the historical raw-record wire.
 */
public final class UIResourceEmitter {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private UIResourceEmitter() {}

  /** Project a {@link Resource} onto its typed wire view (declared consumers → {@link ConsumerView}). */
  public static UIResourceView toView(Resource r) {
    return new UIResourceView(
        r.id(),
        "resource",
        r.presentation(),
        r.schema(),
        r.category(),
        r.subscriptionMode(),
        r.endpoint(),
        r.kind(),
        r.history(),
        r.recovery(),
        r.provenance(),
        r.privacy(),
        r.itemOperations(),
        r.collectionOperations(),
        r.primaryKey(),
        r.audience(),
        r.consumers().stream().map(ConsumerView::from).toList(),
        r.emissionPolicy(),
        r.role());
  }

  /** Serialize the typed view to the wire {@code Map} (declaration-ordered; the envelope re-keys). */
  public static Map<String, Object> toEntry(Resource r) {
    try {
      @SuppressWarnings("unchecked")
      Map<String, Object> result = MAPPER.convertValue(toView(r), Map.class);
      return new LinkedHashMap<>(result);
    } catch (Exception e) {
      throw new IllegalStateException("Failed to serialize Resource " + r.id() + " to UI format", e);
    }
  }

  /** Project a catalog's worth of resources, declared consumers only (pre Surface-merge). */
  public static List<Map<String, Object>> emit(List<Resource> resources) {
    return resources.stream().map(UIResourceEmitter::toEntry).toList();
  }
}
