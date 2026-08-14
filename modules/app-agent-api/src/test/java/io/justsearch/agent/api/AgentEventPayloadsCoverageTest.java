/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.RecordComponent;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 834 §6.3.1 — the RECORD-COMPONENT coverage gate for {@link AgentEventPayloads#base}.
 *
 * <p>The gap this closes: adding a component to an {@link AgentEvent} record does NOT change the
 * wire until {@code base()} is hand-edited, and unlike a missing VARIANT (which the exhaustive
 * switch rejects at compile time) a missing FIELD is silent — the run persists and streams an event
 * that is quietly missing state. 834 added three components to {@link AgentEvent.StateSnapshot}
 * exactly this way; this test is what makes the next such addition loud.
 *
 * <p>Three constraints the naive version gets wrong, all of them deliberate here:
 *
 * <ul>
 *   <li><b>{@code trace} is excluded.</b> {@code base()} deliberately omits it — {@code withTrace}
 *       appends it afterwards — so a blanket every-component assertion would fail on EVERY variant.
 *   <li><b>Per-variant, not generic over the sealed set.</b> Component-name-to-payload-key is not
 *       one-to-one across all permits: {@code ToolCallProposed} expands its single {@code call}
 *       component into three keys. So this test is scoped to the FLAT-MAPPING variants listed
 *       below, {@code StateSnapshot} first.
 *   <li><b>Keyed off a list whose permit-parity is already asserted elsewhere</b> — the sealed set
 *       is covered by {@code AgUiEventTranslatorConformanceTest.coversEveryPermit} and
 *       {@code AgentEventSchemaConformanceTest}, so this file does not fork a second permit census;
 *       it asserts instead that every variant it DOES cover is genuinely flat-mapping.
 * </ul>
 */
final class AgentEventPayloadsCoverageTest {

  /** The component {@code base()} deliberately omits (see {@code AgentEventPayloads#withTrace}). */
  private static final String TRACE_COMPONENT = "trace";

  /**
   * Variants whose record components map ONE-TO-ONE onto {@code base()} payload keys. Deliberately
   * not the whole sealed set — see the class javadoc's second constraint.
   */
  private static final List<AgentEvent> FLAT_MAPPING_VARIANTS =
      List.of(
          new AgentEvent.StateSnapshot(
              2,
              1500,
              3,
              7,
              "primary",
              List.of(new AgentEvent.PendingApproval("c1", "core_write", "{}", "high", "confirm")),
              "WATCH",
              new AgentEvent.ParkSnapshot("approval", 1_700_000_000_000L, "c1"),
              TraceContext.none()),
          new AgentEvent.TextChunk("hi"),
          new AgentEvent.ReasoningChunk("thinking"),
          new AgentEvent.ToolCallApproved("c1"),
          new AgentEvent.ToolCallRejected("c1", "no"),
          new AgentEvent.ToolExecutionStarted("c1", "tool"),
          new AgentEvent.ToolCallVirtual("c1", "wire", "args"),
          new AgentEvent.DirectiveAcknowledged("focus"),
          new AgentEvent.ContextCompacted(4),
          new AgentEvent.SessionStarted("sid"),
          new AgentEvent.HandoffExecuted("a", "b"),
          new AgentEvent.HandoffProposed("a", "b", "why"),
          new AgentEvent.BudgetGatePending(1, 2, 3),
          new AgentEvent.ContextGatePending(7800, 8192),
          new AgentEvent.AgentProgress("phase", "msg", 1, 8),
          new AgentEvent.AgentBudgetUpdate("phase", 1, 2, 3, 4, 5));

  @Test
  @DisplayName("every record component (except trace) appears as a base() payload key")
  void everyComponentIsCarried() {
    for (AgentEvent event : FLAT_MAPPING_VARIANTS) {
      Set<String> components = componentsOf(event);
      Set<String> keys = new TreeSet<>(AgentEventPayloads.base(event).keySet());
      Set<String> missing =
          components.stream()
              .filter(c -> !keys.contains(c))
              .collect(Collectors.toCollection(TreeSet::new));
      assertTrue(
          missing.isEmpty(),
          () ->
              event.getClass().getSimpleName()
                  + " declares component(s) "
                  + missing
                  + " that base() does not emit — adding a component does NOT change the wire until"
                  + " AgentEventPayloads.base() is edited, and a missing FIELD is silent (834"
                  + " §6.3.1). Emit them, or move this variant off FLAT_MAPPING_VARIANTS with a"
                  + " reason.");
    }
  }

  @Test
  @DisplayName("FLAT_MAPPING_VARIANTS really are flat — no variant emits an undeclared key")
  void flatMeansFlat() {
    // The converse direction: if base() emitted a key with no matching component, the variant is
    // NOT flat-mapping (that is the ToolCallProposed shape) and does not belong in this list — the
    // forward assertion above would then be checking a weaker property than it claims to.
    for (AgentEvent event : FLAT_MAPPING_VARIANTS) {
      Set<String> components = componentsOf(event);
      Set<String> extra =
          AgentEventPayloads.base(event).keySet().stream()
              .filter(k -> !components.contains(k))
              .collect(Collectors.toCollection(TreeSet::new));
      assertTrue(
          extra.isEmpty(),
          () ->
              event.getClass().getSimpleName()
                  + " emits key(s) "
                  + extra
                  + " with no matching record component — it is not flat-mapping, so remove it from"
                  + " FLAT_MAPPING_VARIANTS.");
    }
  }

  @Test
  @DisplayName("base() omits trace on every variant (the exclusion is real, not vacuous)")
  void traceIsExcludedNotForgotten() {
    // Guards the exclusion itself: if base() ever started emitting `trace`, skipping it above would
    // silently stop testing anything about it, and withTrace would double-write.
    for (AgentEvent event : FLAT_MAPPING_VARIANTS) {
      assertFalse(
          AgentEventPayloads.base(event).containsKey(TRACE_COMPONENT),
          () -> event.getClass().getSimpleName() + ": base() must not emit the trace envelope");
    }
  }

  @Test
  @DisplayName("StateSnapshot: pendingApprovals is emitted-empty for none, park is ABSENT")
  void unknownIsNotNone() {
    // 834 §6.3.3 — the tri-state. A running, unparked snapshot emits `pendingApprovals: []`
    // ("none") and OMITS `park`. A legacy events.ndjson record simply lacks the key, and a consumer
    // must read that absence as UNKNOWN — which is only representable because the producer always
    // writes the key when it knows the answer.
    Map<String, Object> running =
        AgentEventPayloads.base(new AgentEvent.StateSnapshot(1, 10, 0, 2, "primary"));
    assertTrue(running.containsKey("pendingApprovals"), "the key is emitted even when empty");
    assertEquals(List.of(), running.get("pendingApprovals"), "empty list means NONE pending");
    assertFalse(running.containsKey("park"), "an unparked run omits park entirely");
    assertEquals("ASSIST", running.get("autonomyLevel"), "the dial is never null");

    Map<String, Object> parked =
        AgentEventPayloads.base(
            new AgentEvent.StateSnapshot(
                1,
                10,
                0,
                2,
                "primary",
                List.of(new AgentEvent.PendingApproval("c1", "core_write", "{}", "high", null)),
                "WATCH",
                new AgentEvent.ParkSnapshot("approval", 42L, "c1"),
                TraceContext.none()));
    List<?> approvals = assertInstanceOf(List.class, parked.get("pendingApprovals"));
    assertEquals(1, approvals.size());
    Map<?, ?> approval = assertInstanceOf(Map.class, approvals.get(0));
    assertEquals("c1", approval.get("callId"));
    assertEquals("high", approval.get("risk"));
    assertFalse(
        approval.containsKey("gateBehavior"), "a null gate verdict is ABSENT, not an empty string");
    Map<?, ?> park = assertInstanceOf(Map.class, parked.get("park"));
    assertEquals("approval", park.get("kind"));
    assertEquals(42L, park.get("sinceEpochMs"));
  }

  private static Set<String> componentsOf(AgentEvent event) {
    RecordComponent[] components = event.getClass().getRecordComponents();
    return java.util.Arrays.stream(components)
        .map(RecordComponent::getName)
        .filter(name -> !TRACE_COMPONENT.equals(name))
        .collect(Collectors.toCollection(TreeSet::new));
  }
}
