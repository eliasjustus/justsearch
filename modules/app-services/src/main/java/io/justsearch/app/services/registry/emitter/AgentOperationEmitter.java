/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.registry.emitter;

import io.justsearch.agent.api.registry.AgentToolEmitter;
import io.justsearch.agent.api.registry.Audience;
import io.justsearch.agent.api.registry.ExecutorTag;
import io.justsearch.agent.api.registry.Operation;
import io.justsearch.agent.api.registry.OperationCatalog;
import io.justsearch.app.services.registry.preview.AvailabilityEvaluator;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Projects an {@link OperationCatalog} into the OpenAI function-calling tools format.
 *
 * <p>Per tempdoc 429 §A.2 + §C.G: replaces the deleted
 * {@code ToolRegistry.toOpenAiToolsArray()}. Output is semantically equivalent to the
 * legacy emitter (deep-equal after {@code JsonMapper} normalization per §C.G — see
 * {@code AgentOperationEmitterRegressionTest} in Phase 11 for the byte-level regression).
 *
 * <p>Tool ordering is preserved from catalog insertion order ({@code List<Operation>}
 * iteration is stable; emitter wraps in {@code LinkedHashMap}). LLMs may weight earlier
 * tools differently, so this stability is load-bearing.
 *
 * <p>Per tempdoc 429 §F.21 C1: emitter output uses {@link
 * OperationCatalog#toWireName(io.justsearch.agent.api.registry.OperationRef)} as the
 * OpenAI function name — a deterministic transliteration of {@link
 * io.justsearch.agent.api.registry.OperationRef#value()} that replaces {@code .} and
 * {@code -} with {@code _} (e.g., {@code core.search-index} → {@code core_search_index}).
 * The OperationRef is the single identity for any invocable surface; the wire form is
 * pure projection.
 *
 * <p>Description prose is resolved at emit-time through the supplied
 * {@code messageResolver} so the LLM sees the i18n-resolved description (e.g.,
 * {@code "Search the knowledge index..."}) rather than the raw i18n key. Production
 * wires a resolver backed by {@code registry-operation.en.properties}; tests typically
 * pass an identity resolver.
 */
public final class AgentOperationEmitter implements OperationEmitter, AgentToolEmitter {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /**
   * Tempdoc 508 §11.5 / §13.5 — sidecar virtual-operation store.
   * Optional: when present, the FE-projected virtual tools are
   * appended to the emitter's output so the agent sees one unified
   * tool vocabulary. Set via {@link #withVirtualOperationStore}; the
   * default constructor leaves it null (legacy behavior).
   */
  private final VirtualOperationStore virtualStore;
  // Tempdoc 550 Preview face: the "is this condition firing?" predicate the
  // AvailabilityEvaluator needs. Null in legacy/test wiring → no availability filtering (the
  // full tool list is emitted, as before). When present, an Operation whose declared
  // availability evaluates FALSE in the current state is omitted from the LLM tool list — the
  // emitter consults the same Preview evaluation as the preview endpoint, so the model is not
  // offered a tool it cannot run right now.
  private final java.util.function.Predicate<String> conditionFiring;

  /**
   * Per slice 484 §3.1 closure (C1, 2026-05-08): the LLM's tool list is restricted to
   * Operations whose {@code audience} is USER or AGENT. OPERATOR (and DEVELOPER) audiences
   * are admin / debug surfaces the agent must not invoke without explicit user delegation.
   *
   * <p>Verified hazard motivating this filter: {@code core.bulk-reindex} ships
   * {@code executors = {UI, AGENT}} + {@code audience = OPERATOR}; without this filter the
   * LLM can invoke admin operations purely on the strength of {@code ExecutorTag.AGENT}
   * membership. Allow-list (rather than deny-list) is intentional: future audience values
   * are denied by default, which is the safer side.
   *
   * <p>Per tempdoc 491 §5.5 (2026-05-12) audience-filter hoist: this set is now surfaced
   * via {@link OperationEmitter#allowedAudiences()} so the {@link OperationEmitter#filterForTarget}
   * default applies the filter uniformly. The behavior is unchanged; the override site
   * moved from {@code filterForTarget} to {@code allowedAudiences}.
   */
  private static final Set<Audience> AGENT_INVOCABLE_AUDIENCES =
      EnumSet.of(Audience.USER, Audience.AGENT);

  private final Function<String, String> messageResolver;

  /** Constructs an emitter with an identity message resolver — for tests + admin operations. */
  public AgentOperationEmitter() {
    this(key -> key);
  }

  /**
   * Constructs an emitter that resolves description i18n keys through the supplied
   * function (e.g., a {@code Properties}-backed lookup). Missing keys should pass through
   * unchanged so unknown descriptions still produce valid (if unhelpful) JSON.
   */
  public AgentOperationEmitter(Function<String, String> messageResolver) {
    this(messageResolver, null);
  }

  /**
   * Tempdoc 508 §11.5 / §13.5 — constructs an emitter that also
   * merges FE-published virtual operations into its tool-list
   * output. The virtual store is read on every {@link #emit} call
   * so updates propagate to the next tool fetch without restart.
   */
  public AgentOperationEmitter(
      Function<String, String> messageResolver, VirtualOperationStore virtualStore) {
    this(messageResolver, virtualStore, null);
  }

  /**
   * Tempdoc 550 Preview face: full constructor adding the availability firing predicate.
   * When non-null, {@link #emit} filters out operations whose declared availability evaluates
   * false in the current condition state.
   */
  public AgentOperationEmitter(
      Function<String, String> messageResolver,
      VirtualOperationStore virtualStore,
      java.util.function.Predicate<String> conditionFiring) {
    this.messageResolver = Objects.requireNonNull(messageResolver, "messageResolver");
    this.virtualStore = virtualStore;
    this.conditionFiring = conditionFiring;
  }

  /** Builder helper: copy this emitter with a virtual store attached. */
  public AgentOperationEmitter withVirtualOperationStore(VirtualOperationStore store) {
    return new AgentOperationEmitter(this.messageResolver, store, this.conditionFiring);
  }

  /**
   * Tempdoc 550 Preview face: copy this emitter with an availability firing predicate. The
   * emit path then omits operations that declare an availability expression which evaluates
   * false in the current state — the same Preview evaluation the preview endpoint uses.
   */
  public AgentOperationEmitter withAvailabilityProbe(
      java.util.function.Predicate<String> conditionFiring) {
    return new AgentOperationEmitter(this.messageResolver, this.virtualStore, conditionFiring);
  }

  @Override
  public ExecutorTag targetExecutor() {
    return ExecutorTag.AGENT;
  }

  /**
   * Audience allow-list per slice 484 §3.1 C1: LLM tool list is restricted to USER /
   * AGENT operations. Per tempdoc 491 §5.5 audience-filter hoist (2026-05-12), this
   * field is now the parametric extension point — the default
   * {@link OperationEmitter#filterForTarget} applies it uniformly. Behavior preserved
   * byte-for-byte from the previous {@code filterForTarget} override.
   */
  @Override
  public Set<Audience> allowedAudiences() {
    return AGENT_INVOCABLE_AUDIENCES;
  }

  @Override
  public List<Map<String, Object>> emit(
      OperationCatalog catalog, Collection<String> selectedNames) {
    List<Map<String, Object>> core = filterForTarget(catalog).stream()
        .filter(op -> matchesSelection(op, selectedNames))
        .filter(this::isAvailableNow)
        .map(this::toOpenAiTool)
        .toList();
    if (virtualStore == null) {
      return core;
    }
    // §11.5 / §13.5 — append FE-published virtual operations.
    // Conflicts (same wire-name) resolve in favor of core (no
    // shadowing — virtual entries with a colliding name are
    // dropped silently).
    List<Map<String, Object>> virtual = virtualStore.snapshot().stream()
        .filter(tool -> matchesVirtualSelection(tool, selectedNames))
        .filter(tool -> !collidesWithCore(tool, core))
        .toList();
    if (virtual.isEmpty()) {
      return core;
    }
    List<Map<String, Object>> merged = new java.util.ArrayList<>(core.size() + virtual.size());
    merged.addAll(core);
    merged.addAll(virtual);
    return List.copyOf(merged);
  }

  private static boolean matchesVirtualSelection(
      Map<String, Object> tool, Collection<String> selectedNames) {
    if (selectedNames == null || selectedNames.isEmpty()) return true;
    Object fn = tool.get("function");
    if (!(fn instanceof Map<?, ?> fnMap)) return false;
    Object name = fnMap.get("name");
    return name != null && selectedNames.contains(name.toString());
  }

  private static boolean collidesWithCore(
      Map<String, Object> virtualTool, List<Map<String, Object>> core) {
    Object fn = virtualTool.get("function");
    if (!(fn instanceof Map<?, ?> fnMap)) return true;
    Object name = fnMap.get("name");
    if (name == null) return true;
    String wireName = name.toString();
    for (Map<String, Object> coreTool : core) {
      Object coreFn = coreTool.get("function");
      if (coreFn instanceof Map<?, ?> coreFnMap) {
        Object coreName = coreFnMap.get("name");
        if (wireName.equals(coreName)) return true;
      }
    }
    return false;
  }

  private static boolean matchesSelection(Operation op, Collection<String> selectedNames) {
    if (selectedNames == null || selectedNames.isEmpty()) {
      return true;
    }
    String wire = OperationCatalog.toWireName(op.id());
    return selectedNames.contains(wire) || selectedNames.contains(op.id().value());
  }

  /**
   * Tempdoc 550 Preview face: is this operation available in the current condition state? When
   * no availability probe is wired ({@code conditionFiring == null}, legacy/test path) every
   * operation is available — full tool list, behavior preserved. When a probe is present, an
   * operation that declares an availability expression is included only when that expression
   * evaluates true under {@link AvailabilityEvaluator}; operations with no expression are
   * always available. This is the same Preview evaluation the preview endpoint exposes — the
   * model is not offered a tool it provably cannot run right now.
   */
  private boolean isAvailableNow(Operation op) {
    if (conditionFiring == null) {
      return true;
    }
    return op.availability()
        .expression()
        .map(expr -> AvailabilityEvaluator.evaluate(expr, conditionFiring))
        .orElse(true);
  }

  private Map<String, Object> toOpenAiTool(Operation op) {
    try {
      ObjectNode function = MAPPER.createObjectNode();
      // Per §F.21 C1: deterministic transliteration of OperationRef is the LLM-facing
      // function name. The substrate's identity (OperationRef) is the single source of
      // truth; the wire form is pure projection.
      function.put("name", OperationCatalog.toWireName(op.id()));
      // Resolve the i18n description key to actual prose so the LLM sees a meaningful
      // description rather than the raw key. Identity resolver passes the key through
      // unchanged (used for tests + the captured baseline regression).
      function.put(
          "description", messageResolver.apply(op.presentation().descriptionKey().value()));
      function.set("parameters", MAPPER.readTree(op.intf().inputs()));

      ObjectNode toolObj = MAPPER.createObjectNode();
      toolObj.put("type", "function");
      toolObj.set("function", function);

      @SuppressWarnings("unchecked")
      Map<String, Object> result = MAPPER.convertValue(toolObj, Map.class);
      return new LinkedHashMap<>(result);
    } catch (Exception e) {
      throw new IllegalStateException(
          "Failed to serialize Operation " + op.id() + " to OpenAI format", e);
    }
  }

  /**
   * Convenience overload: convert any List of Operations (e.g., test catalogs) to OpenAI shape.
   *
   * <p>Uses an identity message resolver — i18n keys pass through unchanged. For
   * production-shape output use the instance-level {@link #emit(OperationCatalog, Collection)}.
   */
  public static List<Map<String, Object>> emitOperations(
      List<Operation> ops, Collection<String> selectedNames) {
    AgentOperationEmitter emitter = new AgentOperationEmitter();
    return ops.stream()
        .filter(op -> matchesSelection(op, selectedNames))
        .map(emitter::toOpenAiTool)
        .toList();
  }

}
