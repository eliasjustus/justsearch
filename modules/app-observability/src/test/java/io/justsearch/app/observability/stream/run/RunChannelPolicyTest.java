/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.observability.stream.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.observability.stream.FrameRetentionPolicy;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 834 §2 (two-tier retention) and §3.4 (the ask-survival guard is STRUCTURAL, not a
 * javadoc).
 */
@DisplayName("RunChannelPolicy")
final class RunChannelPolicyTest {

  // ── the §2 bounds ────────────────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("the two run policies carry §2's bounds and build the S3a retention layer")
  void policiesCarryTheDesignedBounds() {
    RunChannelPolicy ask = RunChannelPolicy.conversational();
    RunChannelPolicy agent = RunChannelPolicy.agent();

    assertEquals(4000, ask.maxFrames());
    assertEquals(2L * 1024 * 1024, ask.maxBytes());
    assertFalse(ask.parkable(), "a one-shot pipeline has no control point to park at");
    assertEquals(1000, agent.maxFrames(), "today's AgentSession value, carried over rather than re-guessed");
    assertEquals(4L * 1024 * 1024, agent.maxBytes());
    assertTrue(agent.parkable());

    FrameRetentionPolicy retention = ask.frameRetention();
    assertEquals(4000, retention.maxFrames());
    assertEquals(2L * 1024 * 1024, retention.maxBytes());
    assertEquals(RunChannelPolicy.EVIDENCE_BYTES, retention.maxEvidenceBytes());
    assertTrue(retention.tracksBytes(), "a run policy MUST size its frames — that is the point of §2");
    assertTrue(retention.evidenceSlotEnabled());
  }

  @Test
  @DisplayName("degenerate bounds are refused at construction")
  void degenerateBoundsAreRefused() {
    assertThrows(IllegalArgumentException.class, () -> new RunChannelPolicy(0, 1024, false));
    assertThrows(IllegalArgumentException.class, () -> new RunChannelPolicy(10, 0, false));
  }

  // ── evidence classification ──────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("the §2 evidence events are keyed latest-wins; narrative frames are not")
  void evidenceEventsAreClassified() {
    for (String event : Arrays.asList("rag.meta", "rag.citations", "rag.citation_matches")) {
      assertEquals(
          Optional.of(event),
          RunChannelPolicy.evidenceKey(new RunFrame(event, Map.of("k", "v")).asPayload()),
          event + " is replace-only STATE: a reattacher needs the latest, never the history");
    }
    assertEquals(
        Optional.empty(),
        RunChannelPolicy.evidenceKey(new RunFrame("chunk", Map.of("text", "hi")).asPayload()),
        "narrative must stay in the ring — routing it latest-wins would collapse the answer");
    assertEquals(
        Optional.empty(),
        RunChannelPolicy.evidenceKey("not a run frame at all"));
  }

  @Test
  @DisplayName("a tool result is evidence only when it carries BULK, and is keyed per call")
  void toolResultsAreEvidenceOnlyWhenBulky() {
    Map<String, Object> bulky = new LinkedHashMap<>();
    bulky.put("callId", "call-7");
    bulky.put("output", "ok");
    bulky.put("structuredData", Map.of("rows", 5000));

    assertEquals(
        Optional.of("tool_exec_completed:call-7"),
        RunChannelPolicy.evidenceKey(new RunFrame("tool_exec_completed", bulky).asPayload()));

    Map<String, Object> plain = new LinkedHashMap<>();
    plain.put("callId", "call-8");
    plain.put("output", "ok");
    assertEquals(
        Optional.empty(),
        RunChannelPolicy.evidenceKey(new RunFrame("tool_exec_completed", plain).asPayload()),
        "keying every tool result latest-wins would COLLAPSE several tool calls into the last one");
  }

  /**
   * Tempdoc 878 §D.5 — "carries structuredData at all" stopped separating bulk from narrative.
   *
   * <p>Since 577, {@code OperationResult.withLineage} stamps a text-provenance classification onto
   * EVERY successful tool result, so a bare "ok" arrives carrying a key. The discriminator kept
   * saying yes, and narrative frames began drawing on the evidence slot's separate byte budget —
   * the budget that exists so one passage-bearing frame cannot evict a whole narrative ring.
   *
   * <p>The rule is the inverse of listing the bulk keys, which rots silently the first time a
   * producer adds one: a CLASSIFICATION stamp is not bulk, and anything else is. That fails in the
   * visible direction if a new stamp is ever added (it reads as bulk — today's behaviour, so no
   * regression is possible), rather than in the silent one.
   */
  @Test
  @DisplayName("878 §D.5: a lineage stamp alone is narrative; a grounding delta is evidence")
  void aClassificationStampIsNotBulk() {
    Map<String, Object> stampedOnly = new LinkedHashMap<>();
    stampedOnly.put("callId", "call-9");
    stampedOnly.put("output", "ok");
    stampedOnly.put("structuredData", Map.of("lineage", "runtime"));

    assertEquals(
        Optional.empty(),
        RunChannelPolicy.evidenceKey(new RunFrame("tool_exec_completed", stampedOnly).asPayload()),
        "a plain success message does not become evidence by being classified");

    Map<String, Object> grounded = new LinkedHashMap<>();
    grounded.put("callId", "call-10");
    grounded.put("structuredData", Map.of("lineage", "corpus-quoted", "grounding", java.util.List.of(Map.of("path", "/a.md"))));
    assertEquals(
        Optional.of("tool_exec_completed:call-10"),
        RunChannelPolicy.evidenceKey(new RunFrame("tool_exec_completed", grounded).asPayload()),
        "a grounding delta carries the excerpts it established — content, not classification");

    assertFalse(RunChannelPolicy.carriesBulk(Map.of()), "an empty payload carries nothing");
    assertFalse(RunChannelPolicy.carriesBulk(null), "and neither does an absent one");
    assertTrue(
        RunChannelPolicy.carriesBulk(Map.of("searchResults", java.util.List.of())),
        "an unrecognised key is BULK by default — a new producer key must not silently become"
            + " narrative, which is what listing the bulk keys instead would have caused");
  }

  @Test
  @DisplayName("evidence keys are per-call, so two bulky tool results do not evict each other")
  void twoBulkyToolResultsKeepDistinctKeys() {
    // Genuinely bulky fixtures (878 §D.5). These used to carry an EMPTY structuredData and passed
    // only because the discriminator asked whether the key was present — so the test named "two
    // bulky tool results" was exercising two results that carried nothing.
    Map<String, Object> first =
        new LinkedHashMap<>(Map.of("callId", "a", "structuredData", Map.of("rows", 1)));
    Map<String, Object> second =
        new LinkedHashMap<>(Map.of("callId", "b", "structuredData", Map.of("rows", 2)));

    assertEquals(
        Optional.of("tool_exec_completed:a"),
        RunChannelPolicy.evidenceKey(new RunFrame("tool_exec_completed", first).asPayload()));
    assertEquals(
        Optional.of("tool_exec_completed:b"),
        RunChannelPolicy.evidenceKey(new RunFrame("tool_exec_completed", second).asPayload()));
  }

  // ── §3.4: the guard is structural ────────────────────────────────────────────────────────────

  @Test
  @DisplayName("§3.4 — a one-shot run handle has NO setPark: the mistake cannot compile")
  void aOneShotRunHandleCannotBeParked() {
    // The compile-level half of the assertion: this method body only compiles because nothing here
    // calls setPark on a one-shot handle. Uncommenting `oneShot.setPark(...)` below would not
    // compile — which is the guarantee. Rev 1 wrote that guarantee as a javadoc on a UNIFORM
    // setPark ("empty by construction"); that is the flattening failure wearing a comment.
    RunChannelRegistry registry = new RunChannelRegistry();
    RunChannel oneShot =
        registry.open(
            new RunId("run-oneshot"),
            new RunDescriptor("core.rag-ask", "conv", 1L),
            RunChannelPolicy.conversational());

    assertFalse(oneShot instanceof SteppedRunChannel);
    assertEquals(Optional.empty(), oneShot.park(), "and the park is empty, with no field to set");
    assertEquals(Optional.empty(), oneShot.snapshot(), "§6.4 — no fact a user can act on");

    // The runnable half: no accessible setPark exists anywhere on the one-shot type, so the
    // guarantee cannot be reached reflectively either.
    assertTrue(
        Arrays.stream(OneShotRunChannel.class.getMethods()).noneMatch(m -> m.getName().equals("setPark")),
        "OneShotRunChannel must expose no setPark");
    assertTrue(
        Arrays.stream(oneShot.getClass().getMethods()).noneMatch(m -> m.getName().equals("setPark")),
        "and neither may its implementation");
  }

  @Test
  @DisplayName("a stepped run carries the park and the current-state primer")
  void aSteppedRunCarriesParkAndSnapshot() {
    RunChannelRegistry registry = new RunChannelRegistry();
    RunChannel channel =
        registry.open(
            new RunId("agent-9"),
            new RunDescriptor("core.agent-run", "conv", 1L),
            RunChannelPolicy.agent());

    SteppedRunChannel stepped = (SteppedRunChannel) channel;
    stepped.setPark(new ParkState(ParkState.Kind.APPROVAL, 1_700_000_000_000L, "call-1"));
    stepped.setSnapshotSupplier(() -> new RunStateSnapshot(Map.of("iteration", 3)));

    assertEquals(ParkState.Kind.APPROVAL, channel.park().orElseThrow().kind());
    assertEquals("approval", channel.park().orElseThrow().kind().wire());
    assertEquals("call-1", channel.park().orElseThrow().detail());
    assertEquals(3, channel.snapshot().orElseThrow().fields().get("iteration"));

    stepped.setPark(null);
    assertEquals(Optional.empty(), channel.park(), "clearing the park is how a released gate reads");
  }

  @Test
  @DisplayName("a snapshot supplier that throws degrades to no primer, never to a failed attach")
  void aThrowingSnapshotSupplierDoesNotBreakTheAttach() {
    RunChannelRegistry registry = new RunChannelRegistry();
    SteppedRunChannel stepped =
        (SteppedRunChannel)
            registry.open(
                new RunId("agent-throwy"),
                new RunDescriptor("core.agent-run", "conv", 1L),
                RunChannelPolicy.agent());
    stepped.setSnapshotSupplier(
        () -> {
          throw new IllegalStateException("session evicted mid-attach");
        });

    assertEquals(
        Optional.empty(),
        stepped.snapshot(),
        "the attach is the path a user takes to RECOVER a run they cannot see — it must not throw");
  }
}
