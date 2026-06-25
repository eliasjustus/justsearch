/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.api.registry;

import io.justsearch.agent.api.conversation.ExecutionMode;
import io.justsearch.agent.api.conversation.IterationMode;
import io.justsearch.agent.api.conversation.PersistenceMode;
import java.util.List;
import java.util.Objects;

/**
 * Manifest entry that composes substrate SPIs (defined in
 * {@code io.justsearch.agent.api.conversation}) into a runnable LLM-output flow.
 *
 * <p>Per tempdoc 491 §5.3: {@code ConversationShape} is a Manifest (third Manifest tier
 * alongside Plugin and Surface) — it does NOT extend {@link RegistryEntry} (which is sealed
 * to the four primitives). Each named LLM-output use-case (RAG ask, summarize, agent run,
 * URL emission, plugin shapes) is a {@code ConversationShape} entry in a {@link
 * ConversationShapeCatalog} parallel to the per-primitive catalogs.
 *
 * <h3>Manifest fields</h3>
 *
 * <ul>
 *   <li>{@link #id}: namespaced identifier, mirrors {@link OperationRef} format.
 *   <li>{@link #presentation}: i18n-keyed labelKey + descriptionKey for FE display.
 *   <li>{@link #audience}: declared access-control audience (USER / AGENT / OPERATOR /
 *       DEVELOPER). The endpoint validates the request's invocation audience (from the
 *       {@code X-JustSearch-Audience} HTTP header per §5.4) against this field before
 *       invoking the engine.
 *   <li>{@link #provenance}: CORE / TRUSTED_PLUGIN / UNTRUSTED_PLUGIN. Plugin-contributed
 *       shapes register with {@code Provenance.tier=PLUGIN} and are gated by the same
 *       machinery as plugin operations.
 *   <li>{@link #executionMode}: {@link ExecutionMode#SUBSTRATE_DRIVEN} (engine controls the
 *       per-iteration loop and SPI invocation; default for fresh shapes) or
 *       {@link ExecutionMode#SHAPE_DRIVEN} (engine delegates to the shape's runner; used to
 *       encapsulate existing implementations whose iteration logic is correctness-critical,
 *       e.g., the agent loop).
 *   <li>{@link #iterationMode}: {@link IterationMode#ONE_SHOT} or
 *       {@link IterationMode#WITHIN_TURN_ITERATION}.
 *   <li>{@link #persistenceMode}: {@link PersistenceMode#EPHEMERAL} or
 *       {@link PersistenceMode#PERSISTENT}.
 *   <li>{@link #promptContributorIds}: ordered list of {@code PromptContributor} ids the
 *       shape composes. The engine collects each contributor's fragment and assembles the
 *       system prompt by priority (then declaration order on ties).
 *   <li>{@link #contextInjectorIds}: ordered list of {@code ContextInjector} ids. Injected
 *       messages compose in declaration order before the user message.
 *   <li>{@link #streamConsumerIds}: ordered list of {@code StreamConsumer} ids. Stream
 *       events are dispatched to each consumer in order; their message deltas append in
 *       order before the next iteration.
 *   <li>{@link #iterationControllerId}: the {@code IterationController} id for
 *       substrate-driven shapes. {@code null} for shape-driven shapes (the runner controls
 *       its own iteration).
 *   <li>{@link #eventSchema}: declared SSE event names this shape emits, both substrate
 *       events ({@code chunk}, {@code done}, etc.) and shape-specific namespaced events
 *       (per §5.4). The wire-format contract per shape.
 *   <li>{@link #recordsToThread}: tempdoc 561 P-A/P-B — whether this shape's answer-plane turns
 *       are recorded to the canonical conversation record (the unified thread) under the request's
 *       {@code conversationId}, INDEPENDENT of {@link #persistenceMode}. {@code persistenceMode}
 *       governs multi-turn context RELOAD (PERSISTENT shapes seed their next prompt from history);
 *       {@code recordsToThread} governs whether the turn (and its evidence) is on the durable record
 *       the thread/History/Timeline project. An EPHEMERAL shape (e.g. RAG ask — fresh LLM context
 *       every turn) can still be {@code recordsToThread=true} so its grounded answer + citations live
 *       on the record. Agent / shape-driven shapes record via {@code AgentRunStore} instead, so they
 *       default to {@code false}. The default derives from {@code audience==USER &&
 *       executionMode==SUBSTRATE_DRIVEN} (the answer-plane shapes); the 13-arg constructor lets a
 *       shape override it explicitly.
 * </ul>
 *
 * <p><strong>Discoverability:</strong> a USER-audience shape's user mount is established
 * via the reverse reference in {@link SurfaceConsumes#conversationShapes()} — a
 * {@link Surface} declares "I consume this shape" and the audit (Pass-9) enforces
 * coverage. The slice 491 C0 design originally also included a forward
 * {@code ShapeProjections} field on this record; the G4 Pass-8 review confirmed the
 * field had no production consumer (only the F4 test read it), so the field was
 * retracted as speculative substrate. The reverse-reference + audit + Q8 coverage
 * gate together provide load-bearing discoverability without the speculative slot.
 *
 * @see ConversationShapeCatalog
 */
public record ConversationShape(
    ConversationShapeRef id,
    Presentation presentation,
    Audience audience,
    Provenance provenance,
    ExecutionMode executionMode,
    IterationMode iterationMode,
    PersistenceMode persistenceMode,
    List<String> promptContributorIds,
    List<String> contextInjectorIds,
    List<String> streamConsumerIds,
    String iterationControllerId,
    List<EventDescriptor> eventSchema) implements Provenanced {

  public ConversationShape {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(presentation, "presentation");
    Objects.requireNonNull(audience, "audience");
    Objects.requireNonNull(provenance, "provenance");
    Objects.requireNonNull(executionMode, "executionMode");
    Objects.requireNonNull(iterationMode, "iterationMode");
    Objects.requireNonNull(persistenceMode, "persistenceMode");
    promptContributorIds =
        promptContributorIds == null ? List.of() : List.copyOf(promptContributorIds);
    contextInjectorIds =
        contextInjectorIds == null ? List.of() : List.copyOf(contextInjectorIds);
    streamConsumerIds = streamConsumerIds == null ? List.of() : List.copyOf(streamConsumerIds);
    // iterationControllerId may be null for shape-driven shapes
    eventSchema = eventSchema == null ? List.of() : List.copyOf(eventSchema);

    if (executionMode == ExecutionMode.SUBSTRATE_DRIVEN
        && iterationMode == IterationMode.WITHIN_TURN_ITERATION
        && iterationControllerId == null) {
      throw new IllegalArgumentException(
          "iterationControllerId is required for SUBSTRATE_DRIVEN + WITHIN_TURN_ITERATION shapes ("
              + id.value()
              + ")");
    }
  }

  /**
   * Tempdoc 561 P-A/P-B — whether this shape's answer-plane turns are recorded to the canonical
   * conversation record (the unified thread) under the request's {@code conversationId}, INDEPENDENT
   * of {@link #persistenceMode}. {@code persistenceMode} governs multi-turn context RELOAD (PERSISTENT
   * shapes seed their next prompt from history); this governs whether the turn (and its evidence) is
   * on the durable record the thread / History / Timeline project. An EPHEMERAL shape (e.g. RAG ask —
   * fresh LLM context every turn) is still {@code recordsToThread} so its grounded answer + citations
   * live on the record; agent / shape-driven shapes record via {@code AgentRunStore} instead.
   *
   * <p>Derived from the manifest: the answer-plane shapes are exactly the USER-audience
   * substrate-driven ones. This is intentionally a derivation rather than a stored slot — no shape
   * needs to override it today (YAGNI / C-018); promote it to an explicit component if and when one
   * does.
   */
  public boolean recordsToThread() {
    return audience == Audience.USER && executionMode == ExecutionMode.SUBSTRATE_DRIVEN;
  }
}
