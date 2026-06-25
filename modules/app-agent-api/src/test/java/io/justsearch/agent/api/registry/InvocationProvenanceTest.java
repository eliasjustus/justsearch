package io.justsearch.agent.api.registry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class InvocationProvenanceTest {

  @Test
  void requiresAllFieldsNonNull() {
    Instant t = Instant.parse("2026-05-12T00:00:00Z");
    assertThrows(
        NullPointerException.class,
        () -> new InvocationProvenance(null, ExecutorTag.UI, Optional.empty(), t));
    assertThrows(
        NullPointerException.class,
        () -> new InvocationProvenance(TransportTag.BUTTON, null, Optional.empty(), t));
    assertThrows(
        NullPointerException.class,
        () -> new InvocationProvenance(TransportTag.BUTTON, ExecutorTag.UI, null, t));
    assertThrows(
        NullPointerException.class,
        () ->
            new InvocationProvenance(
                TransportTag.BUTTON, ExecutorTag.UI, Optional.empty(), null));
  }

  @Test
  void initiatorMustBeNonBlankWhenPresent() {
    Instant t = Instant.parse("2026-05-12T00:00:00Z");
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new InvocationProvenance(
                TransportTag.BUTTON, ExecutorTag.UI, Optional.of("   "), t));
    assertThrows(
        IllegalArgumentException.class,
        () -> new InvocationProvenance(TransportTag.BUTTON, ExecutorTag.UI, Optional.of(""), t));
  }

  @Test
  void canConstructWithPresentInitiator() {
    Instant t = Instant.parse("2026-05-12T00:00:00Z");
    InvocationProvenance p =
        new InvocationProvenance(
            TransportTag.LLM_EMISSION, ExecutorTag.AGENT, Optional.of("agent:advisor"), t);
    assertEquals(TransportTag.LLM_EMISSION, p.transport());
    assertEquals(ExecutorTag.AGENT, p.executor());
    assertEquals(Optional.of("agent:advisor"), p.initiator());
    assertEquals(t, p.occurredAt());
  }

  @Test
  void systemInternalFactoryFillsTransportAndEmptyInitiator() {
    Instant t = Instant.parse("2026-05-12T00:00:00Z");
    InvocationProvenance p = InvocationProvenance.systemInternal(t);
    assertEquals(TransportTag.SYSTEM_INTERNAL, p.transport());
    assertEquals(ExecutorTag.UI, p.executor());
    assertTrue(p.initiator().isEmpty());
    assertEquals(t, p.occurredAt());
  }

  @Test
  void uiButtonFactoryUsesButtonTransport() {
    Instant t = Instant.parse("2026-05-12T00:00:00Z");
    InvocationProvenance p = InvocationProvenance.uiButton(t);
    assertEquals(TransportTag.BUTTON, p.transport());
    assertEquals(ExecutorTag.UI, p.executor());
  }

  @Test
  void agentLoopFactoryUsesAgentExecutorAndAgentLoopTransport() {
    Instant t = Instant.parse("2026-05-12T00:00:00Z");
    InvocationProvenance p = InvocationProvenance.agentLoop(t);
    assertEquals(TransportTag.AGENT_LOOP, p.transport());
    assertEquals(ExecutorTag.AGENT, p.executor());
  }

  // ----- Tempdoc 561 P-A1 — correlationId (the cross-domain join key) -----

  @Test
  void agentLoopWithCorrelationIdStampsTheSessionId() {
    Instant t = Instant.parse("2026-05-12T00:00:00Z");
    InvocationProvenance p =
        InvocationProvenance.agentLoop(t, Optional.of("session-abc-123"));
    assertEquals(TransportTag.AGENT_LOOP, p.transport());
    assertEquals(ExecutorTag.AGENT, p.executor());
    assertEquals(Optional.of("session-abc-123"), p.correlationId());
  }

  @Test
  void correlationIdDefaultsAbsentOnLegacyConstructorsAndFactories() {
    Instant t = Instant.parse("2026-05-12T00:00:00Z");
    // 4-arg legacy
    assertTrue(
        new InvocationProvenance(TransportTag.BUTTON, ExecutorTag.UI, Optional.empty(), t)
            .correlationId()
            .isEmpty());
    // 5-arg token-carrying legacy
    assertTrue(
        new InvocationProvenance(
                TransportTag.BUTTON, ExecutorTag.UI, Optional.empty(), t, Optional.of("tok"))
            .correlationId()
            .isEmpty());
    // named factories (no correlation)
    assertTrue(InvocationProvenance.uiButton(t).correlationId().isEmpty());
    assertTrue(InvocationProvenance.agentLoop(t).correlationId().isEmpty());
  }

  @Test
  void correlationIdMustBeNonBlankWhenPresent() {
    Instant t = Instant.parse("2026-05-12T00:00:00Z");
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new InvocationProvenance(
                TransportTag.AGENT_LOOP,
                ExecutorTag.AGENT,
                Optional.empty(),
                t,
                Optional.empty(),
                Optional.of("  ")));
  }

  // ----- Slice 489 §17.5 — FE→backend transport stamping factories -----

  @Test
  void urlBarFactoryStampsUrlBar() {
    Instant t = Instant.parse("2026-05-12T00:00:00Z");
    InvocationProvenance p = InvocationProvenance.urlBar(t);
    assertEquals(TransportTag.URL_BAR, p.transport());
    assertEquals(ExecutorTag.UI, p.executor());
    assertTrue(p.initiator().isEmpty());
  }

  @Test
  void urlDeeplinkFactoryStampsDeeplink() {
    Instant t = Instant.parse("2026-05-12T00:00:00Z");
    InvocationProvenance p = InvocationProvenance.urlDeeplink(t);
    assertEquals(TransportTag.URL_DEEPLINK, p.transport());
    assertEquals(ExecutorTag.UI, p.executor());
  }

  @Test
  void paletteFactoryStampsPalette() {
    Instant t = Instant.parse("2026-05-12T00:00:00Z");
    InvocationProvenance p = InvocationProvenance.palette(t);
    assertEquals(TransportTag.PALETTE, p.transport());
    assertEquals(ExecutorTag.UI, p.executor());
  }

  @Test
  void railFactoryStampsRail() {
    Instant t = Instant.parse("2026-05-12T00:00:00Z");
    InvocationProvenance p = InvocationProvenance.rail(t);
    assertEquals(TransportTag.RAIL, p.transport());
    assertEquals(ExecutorTag.UI, p.executor());
  }

  @Test
  void mcpFactoryStampsMcpWithInitiator() {
    Instant t = Instant.parse("2026-05-12T00:00:00Z");
    InvocationProvenance p = InvocationProvenance.mcp(t, Optional.of("mcp:claude"));
    assertEquals(TransportTag.MCP, p.transport());
    assertEquals(ExecutorTag.UI, p.executor());
    assertEquals(Optional.of("mcp:claude"), p.initiator());
  }

  @Test
  void fromTransportFactoryMapsAnyTransport() {
    Instant t = Instant.parse("2026-05-12T00:00:00Z");
    InvocationProvenance p =
        InvocationProvenance.fromTransport(TransportTag.URL_DEEPLINK, Optional.empty(), t);
    assertEquals(TransportTag.URL_DEEPLINK, p.transport());
    assertEquals(ExecutorTag.UI, p.executor());
  }

  @Test
  void fromTransportFactoryRejectsNullTransport() {
    Instant t = Instant.parse("2026-05-12T00:00:00Z");
    assertThrows(
        NullPointerException.class,
        () -> InvocationProvenance.fromTransport(null, Optional.empty(), t));
  }

  @Test
  void transportTagCoversFourConsumerClusters() {
    // Slice 490 §4.B: TransportTag values cover the union of needs across slices 487
    // (LLM emitter), 489 (URL substrate), 490 (proactive emission), 491 (chat).
    // Smoke test: each named consumer has at least one TransportTag value.
    // 487: LLM_EMISSION
    assertEquals(TransportTag.LLM_EMISSION, TransportTag.valueOf("LLM_EMISSION"));
    // 489: URL_BAR, URL_DEEPLINK, BUTTON, RAIL, PALETTE
    assertEquals(TransportTag.URL_BAR, TransportTag.valueOf("URL_BAR"));
    assertEquals(TransportTag.URL_DEEPLINK, TransportTag.valueOf("URL_DEEPLINK"));
    // 490: SCHEDULED, RULE_ENGINE, SYSTEM_INTERNAL
    assertEquals(TransportTag.SCHEDULED, TransportTag.valueOf("SCHEDULED"));
    assertEquals(TransportTag.RULE_ENGINE, TransportTag.valueOf("RULE_ENGINE"));
    // 491: AGENT_LOOP, MCP
    assertEquals(TransportTag.AGENT_LOOP, TransportTag.valueOf("AGENT_LOOP"));
    assertEquals(TransportTag.MCP, TransportTag.valueOf("MCP"));
  }
}
