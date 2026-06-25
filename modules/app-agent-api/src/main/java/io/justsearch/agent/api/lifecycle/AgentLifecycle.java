/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.api.lifecycle;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Tempdoc 561 P-A / P-A2 — the agent loop modeled as a first-class, typed object: the {@code Session
 * ⊃ Turn ⊃ Iteration} hierarchy the thesis names, carrying its {@link LifecycleState} and {@link
 * Budget}. Previously the loop was a scatter of untyped {@code Map} meta + an {@code events.ndjson}
 * stream; this is the value model every loop projection (thread, History, Timeline, budget) reads.
 *
 * <p>A {@code Session} is one agent run. A {@code Turn} is one user request within it (today a run
 * services one request, so {@code turns.size() == 1}; the structure is explicit so a future
 * multi-turn-within-a-run conversation extends it without a reshape). An {@code Iteration} is one
 * LLM call within a turn.
 *
 * @param sessionId the run id
 * @param conversationId the parent chat conversation (P-A1 join key); null if standalone
 * @param state the loop's lifecycle phase
 * @param actor the acting agent identity (originator/agent — the P-E actor-cardinality seed)
 * @param startedAt run start
 * @param updatedAt last checkpoint
 * @param budget the loop's token budget
 * @param turns the user-request units, each with its iterations
 * @param toolCallsExecuted the run's executed tool-call count — the loop record's authoritative tool
 *     total. Every loop projection (the thread's TOOL_ACTIVITY events, the Activity rail, History,
 *     Timeline) reads it from this ONE record, so they cannot disagree on "how many tools the run
 *     ran" (561 P-A; the consistency invariant
 *     {@code AgentLifecycleConsistencyTest} locks {@code toolCallsExecuted} == the count of completed
 *     thread tool events).
 */
public record AgentLifecycle(
    String sessionId,
    String conversationId,
    LifecycleState state,
    String actor,
    Instant startedAt,
    Instant updatedAt,
    Budget budget,
    List<Turn> turns,
    int toolCallsExecuted,
    List<String> actors) {

  public AgentLifecycle {
    Objects.requireNonNull(sessionId, "sessionId");
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(budget, "budget");
    turns = turns == null ? List.of() : List.copyOf(turns);
    actors = actors == null || actors.isEmpty() ? List.of(actor == null ? "primary" : actor)
        : List.copyOf(actors);
  }

  /**
   * Tempdoc 561 P-E — actor cardinality. The DISTINCT actor identities that participated in this run:
   * {@code [actor]} for a single-agent run, the full set ({@code manager}, {@code researcher}, …) for
   * a multi-agent / debate-critique run. Multiple originators on the ONE lifecycle record, under the
   * same provenance/projection discipline — not a separate framework (§P-E). {@code actorCount() > 1}
   * is the multi-agent signal a consumer renders.
   */
  public int actorCount() {
    return actors.size();
  }

  /** Total iterations across all turns. */
  public int iterationCount() {
    return turns.stream().mapToInt(t -> t.iterations().size()).sum();
  }

  /**
   * The loop's token budget, modeled (P-A): {@code consumed + remaining == initial} is invariant even
   * once an over-budget completion drives {@code remaining} negative (561 §9; the §2.2 honest
   * over-budget phase). {@code remaining} is the RAW value — consumers clamp for display.
   */
  public record Budget(int initial, int consumed, int remaining) {
    public boolean overBudget() {
      return remaining < 0;
    }
  }

  /** One user-request unit within a session. */
  public record Turn(int index, String userPrompt, Instant startedAt, List<Iteration> iterations) {
    public Turn {
      iterations = iterations == null ? List.of() : List.copyOf(iterations);
    }
  }

  /** One LLM call within a turn. */
  public record Iteration(int index, Instant startedAt) {}
}
