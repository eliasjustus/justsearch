/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.registry.emitter;

import io.justsearch.agent.api.registry.Audience;
import io.justsearch.agent.api.registry.ExecutorTag;
import io.justsearch.agent.api.registry.Operation;
import io.justsearch.agent.api.registry.OperationCatalog;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Projects an {@link OperationCatalog} into a consumer-specific wire format.
 *
 * <p>Per tempdoc 429 §6 + §E.8.b: one Operation declaration drives multiple emitters
 * (UI JSON for the FE shell, OpenAI function-calling JSON for the agent loop, future
 * MCP tools/list payload for external clients, planned {@code URLOperationEmitter} for
 * the LLM URL-emission shape per slice 487). Each emitter filters by
 * {@link ExecutorTag} membership and an {@link Audience} allow-list, then projects
 * matching Operations into the wire shape the consumer expects.
 *
 * <p>Per tempdoc 491 §5.5 (verified 2026-05-12) audience-filter hoist: the audience
 * allow-list was previously inlined inside {@link AgentOperationEmitter#filterForTarget};
 * it is now a parametric axis on the interface so a future {@code URLOperationEmitter}
 * sibling can declare its own audience set without duplicating filter logic. The default
 * is the empty set, interpreted as "all audiences allowed" — preserving the prior behavior
 * for emitters (like {@link UIOperationEmitter}) that never had an audience filter.
 */
public interface OperationEmitter {

  /** The {@link ExecutorTag} this emitter projects for. */
  ExecutorTag targetExecutor();

  /**
   * The {@link Audience} allow-list this emitter applies on top of the executor filter.
   * Default empty — interpreted as "no audience restriction; all audiences allowed."
   * Implementations that need to gate by audience (e.g., {@link AgentOperationEmitter}
   * disallowing OPERATOR-class admin operations per slice 484 §3.1 C1) override this
   * to return a non-empty allow-list. Future siblings (e.g., {@code URLOperationEmitter}
   * for slice 487's URL-emission shape) declare their own allow-list here without
   * duplicating filter machinery.
   */
  default Set<Audience> allowedAudiences() {
    return EnumSet.noneOf(Audience.class);
  }

  /**
   * Project the catalog into the wire format. The default implementation filters by
   * {@code executors.contains(targetExecutor())} AND, when {@link #allowedAudiences} is
   * non-empty, by {@code allowedAudiences().contains(op.audience())}. Returns entries
   * in catalog order (insertion order preserved for stable LLM tool ordering per §E.10
   * implication).
   *
   * <p>Per tempdoc 491 §5.5: implementations should NOT override this method unless they
   * need filter logic beyond executor + audience. The hoist's purpose is exactly that
   * implementations *don't* override this in order to layer in audience gating — they
   * just declare their {@link #allowedAudiences}.
   */
  default List<Operation> filterForTarget(OperationCatalog catalog) {
    Set<Audience> audiences = allowedAudiences();
    return catalog.definitions().stream()
        .filter(op -> op.executors().contains(targetExecutor()))
        .filter(op -> audiences.isEmpty() || audiences.contains(op.audience()))
        .toList();
  }

  /**
   * Emit the projected catalog as a list of consumer-specific records. The return type
   * is {@code List<?>} because each emitter's record shape differs (OpenAI function-call
   * spec, UI JSON envelope, etc.); callers know which emitter they invoked and cast
   * accordingly.
   */
  List<?> emit(OperationCatalog catalog, Collection<String> selectedNames);
}
