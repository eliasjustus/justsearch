package io.justsearch.app.services.intent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.agent.api.registry.IntentSource;
import io.justsearch.agent.api.registry.IntentSourceCatalog;
import io.justsearch.agent.api.registry.SourceTier;
import io.justsearch.agent.api.registry.TransportTag;
import java.util.EnumSet;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Per tempdoc 487 §4.1 + Phase 1.6 gate: catalog completeness, drift-proof.
 *
 * <p>Pre-Commit-4 this test hand-maintained a {@code CORE_REGISTERED} set of
 * transports that were known to need a source. A new {@link TransportTag} value
 * landing without registration AND without being in the set would silently pass.
 *
 * <p>Post-Commit-4 (post-impl fix A4): the test is driven by the {@link TransportTag}
 * enum directly. For every value, the rule is: either the catalog finds a source via
 * {@code findByTransport}, OR the transport is in the explicit
 * {@link #EXEMPT_FROM_CORE_REGISTRATION} set (plugin-contributed, scheduled, etc.).
 * Any new enum value that's neither registered nor exempt fails the test.
 *
 * <p>Also closes the post-impl fix A3 gap (the pre-Commit-4 hard-coded switch in
 * {@code IntentSourceCatalog.matchesTransport}) by exercising the new data-driven
 * {@code findByTransport} path — sources expose their transport via the new
 * {@code IntentSource.transport()} field; plugin catalogs supply their own without
 * inheriting a CORE-specific mapping.
 */
final class CoreIntentSourceCatalogTest {

  private final IntentSourceCatalog catalog = CoreIntentSourceCatalog.catalog();

  /**
   * Transports that are NOT registered in the CORE catalog by design.
   *
   * <ul>
   *   <li>{@link TransportTag#PLUGIN_EMITTED} — plugin-contributed sources register at
   *       plugin-load time, not in CORE.
   *   <li>{@link TransportTag#SCHEDULED}, {@link TransportTag#RULE_ENGINE} —
   *       reserved for future backend-emitted intent sources (scheduled-trigger
   *       catalog, rule-engine catalog) that ship in their own slices.
   * </ul>
   *
   * <p>Adding a new {@link TransportTag} value lands without registration here only
   * if it's added to this exempt set explicitly. A truly-new transport without
   * either a registered source or an explicit exemption fails the test.
   */
  private static final Set<TransportTag> EXEMPT_FROM_CORE_REGISTRATION =
      EnumSet.of(
          TransportTag.PLUGIN_EMITTED,
          TransportTag.SCHEDULED,
          TransportTag.RULE_ENGINE);

  @Test
  void everyTransportEitherHasACoreSourceOrIsExplicitlyExempt() {
    for (TransportTag transport : TransportTag.values()) {
      boolean exempt = EXEMPT_FROM_CORE_REGISTRATION.contains(transport);
      boolean registered = catalog.findByTransport(transport).isPresent();
      if (exempt) {
        // Sanity: exempt transports MUST NOT also be registered (else the exemption
        // is stale).
        assertTrue(
            !registered,
            "TransportTag "
                + transport
                + " is marked exempt from CORE registration but a CORE source"
                + " resolves for it. Drop it from EXEMPT_FROM_CORE_REGISTRATION or"
                + " remove the source.");
      } else {
        assertTrue(
            registered,
            "TransportTag "
                + transport
                + " has no registered IntentSource in CORE catalog AND is not in"
                + " EXEMPT_FROM_CORE_REGISTRATION. Either register a source for it"
                + " in CoreIntentSourceCatalog, OR add the value to the exempt set"
                + " with rationale.");
      }
    }
  }

  @Test
  void uiSourcesAreTrusted() {
    assertEquals(
        SourceTier.TRUSTED,
        catalog.findByTransport(TransportTag.RAIL).orElseThrow().sourceTier());
    assertEquals(
        SourceTier.TRUSTED,
        catalog.findByTransport(TransportTag.PALETTE).orElseThrow().sourceTier());
    assertEquals(
        SourceTier.TRUSTED,
        catalog.findByTransport(TransportTag.BUTTON).orElseThrow().sourceTier());
  }

  @Test
  void urlSourcesAreMedium() {
    assertEquals(
        SourceTier.MEDIUM,
        catalog.findByTransport(TransportTag.URL_BAR).orElseThrow().sourceTier());
    assertEquals(
        SourceTier.MEDIUM,
        catalog.findByTransport(TransportTag.URL_DEEPLINK).orElseThrow().sourceTier());
  }

  @Test
  void llmAndExternalSourcesAreUntrusted() {
    assertEquals(
        SourceTier.UNTRUSTED,
        catalog.findByTransport(TransportTag.LLM_EMISSION).orElseThrow().sourceTier());
    assertEquals(
        SourceTier.UNTRUSTED,
        catalog.findByTransport(TransportTag.AGENT_LOOP).orElseThrow().sourceTier());
    assertEquals(
        SourceTier.UNTRUSTED,
        catalog.findByTransport(TransportTag.MCP).orElseThrow().sourceTier());
  }

  /**
   * Per C-020 cross-check follow-up (option b, forward-compat-doc-honesty):
   * the {@code extractorId} field is forward-compat metadata in V1 — no
   * production code resolves it at runtime. The honest invariant is that
   * each id is well-formed against the codebase's single-segment
   * {@code core.<thing>} convention (matches every other {@code *Ref}
   * id in the codebase: {@code core.search-surface},
   * {@code core.ui-rail}, etc.). Sub-namespaces like
   * {@code core.<category>.<thing>} are rejected so future drift back
   * into the prior catalog's deviating pattern fails fast.
   */
  @Test
  void everyRegisteredSourceHasAWellFormedExtractorId() {
    Pattern wellFormed = Pattern.compile("^core\\.[a-z][a-z0-9-]*$");
    for (IntentSource source : catalog.definitions()) {
      String id = source.extractorId();
      assertTrue(
          id != null && wellFormed.matcher(id).matches(),
          "Source "
              + source.id().value()
              + " has non-conformant extractorId '"
              + id
              + "' — expected single-segment core.<thing>");
    }
  }

  /**
   * Per C-020 cross-check follow-up: the slice's one shipped extractor
   * implementation must be referenced by at least one catalog entry. This
   * catches drift where the extractor's {@code ID} is renamed without the
   * catalog being updated (or vice versa). When additional extractors
   * ship, add them here.
   */
  @Test
  void registeredExtractorsResolveWhenIdMatchesAKnownImplementation() {
    Set<String> knownExtractorIds = Set.of(MarkdownUrlExtractor.ID);
    Set<String> declaredExtractorIds =
        catalog.definitions().stream()
            .map(IntentSource::extractorId)
            .collect(Collectors.toSet());
    for (String knownId : knownExtractorIds) {
      assertTrue(
          declaredExtractorIds.contains(knownId),
          "Shipped extractor id '"
              + knownId
              + "' is not referenced by any catalog entry — likely "
              + "drift between MarkdownUrlExtractor.ID and the catalog's "
              + "LLM_CHAT_EMISSION extractorId. Rename one or the other "
              + "to match.");
    }
  }
}
