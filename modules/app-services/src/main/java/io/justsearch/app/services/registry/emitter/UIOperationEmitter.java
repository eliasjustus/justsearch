/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.registry.emitter;

import io.justsearch.agent.api.registry.ConfirmStrategy;
import io.justsearch.agent.api.registry.ConsumerView;
import io.justsearch.agent.api.registry.ExecutorTag;
import io.justsearch.agent.api.registry.Operation;
import io.justsearch.agent.api.registry.OperationCatalog;
import io.justsearch.agent.api.registry.OperationRef;
import io.justsearch.agent.api.registry.ResourceRef;
import io.justsearch.agent.api.registry.UIHint;
import io.justsearch.agent.api.registry.UIOperationView;
import java.time.Duration;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.ObjectMapper;

/**
 * Projects an {@link OperationCatalog} into the FE shell's JSON shape.
 *
 * <p>Per tempdoc 429 §6 + §E.8.a: emits the entries the {@code RegistryController}
 * serves at {@code /api/registry/operations}. Each entry carries the i18n keys (resolved
 * by the FE via the per-primitive message catalog at
 * {@code /api/messages/registry-operation/{locale}}), the policy axes (risk, confirm,
 * audit, retry), the executor membership, and the {@code uiHints} field (Layer-1 escape
 * hatch per §C.E).
 *
 * <p>Tempdoc 560 §4c Phase B: the per-entry shape is now the typed {@link UIOperationView} record —
 * the emitter builds it and serializes it, so the Operation wire has ONE authority and its FE
 * projection (record→JSON-Schema→{TS,Zod}) is faithful. The hand-built {@code ObjectNode} tree was
 * replaced 1:1 (byte-identical, pinned by {@code UIOperationViewConformanceTest}).
 */
public final class UIOperationEmitter implements OperationEmitter {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Override
  public ExecutorTag targetExecutor() {
    return ExecutorTag.UI;
  }

  @Override
  public List<Map<String, Object>> emit(
      OperationCatalog catalog, Collection<String> selectedNames) {
    return filterForTarget(catalog).stream()
        .filter(
            op ->
                selectedNames == null
                    || selectedNames.isEmpty()
                    || selectedNames.contains(op.id().value()))
        .map(UIOperationEmitter::toUIEntry)
        .toList();
  }

  private static Map<String, Object> toUIEntry(Operation op) {
    try {
      @SuppressWarnings("unchecked")
      Map<String, Object> result = MAPPER.convertValue(toView(op), Map.class);
      return new LinkedHashMap<>(result);
    } catch (Exception e) {
      throw new IllegalStateException(
          "Failed to serialize Operation " + op.id() + " to UI format", e);
    }
  }

  /**
   * Projects an {@link Operation} onto the typed {@link UIOperationView} wire record. The interface
   * inputs/result and the availability expression/argumentDefaultsJson are arbitrary JSON (a
   * JSON-Schema source / an availability AST), parsed to {@link JsonNode}; every other field maps
   * 1:1 onto the view's typed components (enums serialize as their uppercase {@code name()}).
   */
  private static UIOperationView toView(Operation op) {
    UIOperationView.PresentationView presentation =
        new UIOperationView.PresentationView(
            op.presentation().labelKey().value(),
            op.presentation().descriptionKey().value(),
            op.presentation().iconHint().orElse(null),
            op.presentation().category().orElse(null));

    LinkedHashMap<String, Object> uiHints = new LinkedHashMap<>();
    for (Map.Entry<String, UIHint> hint : op.intf().uiHints().entrySet()) {
      uiHints.put(hint.getKey(), MAPPER.valueToTree(hint.getValue()));
    }
    UIOperationView.InterfaceView intf =
        new UIOperationView.InterfaceView(
            MAPPER.readTree(op.intf().inputs()),
            MAPPER.readTree(op.intf().result()),
            op.intf().errors(),
            uiHints);

    // Canonical uppercase enum names + the FE `kind` discriminator on the confirm sub-record
    // (tempdoc 511-followup Track E): aligning the wire to the FE's canonical shape deleted the
    // catalog-client normalizer and a class of silent type-lie defects.
    UIOperationView.ConfirmView confirm =
        switch (op.policy().confirm()) {
          case ConfirmStrategy.None ignored -> new UIOperationView.ConfirmView("NONE", null);
          case ConfirmStrategy.Inline ignored -> new UIOperationView.ConfirmView("INLINE", null);
          case ConfirmStrategy.Typed typed ->
              new UIOperationView.ConfirmView("TYPED", typed.confirmTextKey().value());
        };
    UIOperationView.PolicyView policy =
        new UIOperationView.PolicyView(
            op.policy().risk(),
            confirm,
            op.policy().audit(),
            op.policy().undoSupported(),
            op.policy().rateLimit().map(Duration::toMillis).orElse(null),
            // Tempdoc 560 WS3: the backend-declared inverse op as a bare OperationRef string, always
            // present (null when none) so the wire field matches the FE `inverseOperationId?`.
            op.policy().inverseOperationRef().map(OperationRef::value).orElse(null));

    Object expression = op.availability().expression().map(MAPPER::valueToTree).orElse(null);
    Object argumentDefaults = null;
    if (op.availability().argumentDefaultsJson().isPresent()) {
      argumentDefaults = MAPPER.readTree(op.availability().argumentDefaultsJson().get());
    }
    UIOperationView.AvailabilityView availability =
        new UIOperationView.AvailabilityView(expression, argumentDefaults);

    UIOperationView.LineageView lineage =
        new UIOperationView.LineageView(
            op.lineage().affects().stream().map(ResourceRef::value).sorted().toList(),
            op.lineage().supersedes().stream().map(OperationRef::value).sorted().toList());

    UIOperationView.ProvenanceView provenance =
        new UIOperationView.ProvenanceView(
            op.provenance().tier(), op.provenance().contributorId(), op.provenance().version());

    return new UIOperationView(
        op.id().value(),
        "operation",
        presentation,
        intf,
        policy,
        availability,
        lineage,
        provenance,
        // Sorted for a deterministic wire (op.executors() is an unordered Set; the prior hand-built
        // emitter left the order Set-iteration-dependent — a latent run-to-run wire flake). Matches
        // the lineage affects/supersedes sort convention. Order is FE-irrelevant (membership only).
        op.executors().stream().sorted(Comparator.comparing(ExecutorTag::name)).toList(),
        op.audience(),
        op.consumers().stream().map(ConsumerView::from).toList());
  }
}
