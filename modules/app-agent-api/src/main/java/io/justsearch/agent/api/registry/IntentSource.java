/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.api.registry;

import java.util.Objects;
import java.util.Optional;

/**
 * Manifest entry that registers a place where intents originate.
 *
 * <p>Per tempdoc 487 §4.1: {@code IntentSource} is the fourth Manifest tier
 * alongside Plugin, Surface, and ConversationShape (slice 491). Every place
 * an intent can originate in the platform is a registered {@code IntentSource}
 * in an {@link IntentSourceCatalog}; {@link #extractorId} declares a
 * reference to a registered {@link IntentExtractor} when one exists
 * (forward-compat metadata — see field doc).
 *
 * <p>The catalog makes the family enumerable, audit-able, and Pass-8 reviewable.
 * Existing ingresses (rail click, palette, URL bar, deep-link, agent loop, MCP)
 * re-register without behavior change; new sources (LLM emission, scheduled
 * triggers, plugin emissions) follow the same shape — a one-line registration
 * plus one {@code IntentExtractor} implementation.
 *
 * <h3>Manifest fields</h3>
 *
 * <ul>
 *   <li>{@link #id}: namespaced identifier (e.g., {@code core.ui-rail},
 *       {@code core.llm-chat-emission}).
 *   <li>{@link #presentation}: i18n key for the trust-aware gate UI copy
 *       (e.g., "from URL paste", "from LLM in chat").
 *   <li>{@link #declarationProvenance}: CORE / TRUSTED_PLUGIN /
 *       UNTRUSTED_PLUGIN. Governs whether this source can register at all
 *       (same enum used on {@link Operation} and {@link ConversationShape}).
 *   <li>{@link #sourceTier}: intrinsic trust of this source's emissions.
 *       Composed with {@link RiskTier} (operation policy) in the
 *       {@code (SourceTier × RiskTier) → GateBehavior} trust lattice.
 *   <li>{@link #extractorId}: stable id; references a registered
 *       {@link IntentExtractor} when one exists, or is reserved for
 *       forward-compat declaration. V1 sources may declare placeholder
 *       ids — producers (e.g., slice 487's URL-emission consumer) hold
 *       typed extractor references directly; runtime lookup-by-id is
 *       not implemented in V1 and will be added when a consumer
 *       (e.g., plugin-contributed extractors with dynamic dispatch)
 *       requires it. Naming convention: single-segment
 *       {@code core.<thing>} matching the rest of the codebase's
 *       Ref-style ids. Matches the slice 489 §6 forward-compat
 *       precedent for {@code URLSurfaceEmitter} /
 *       {@code URLOperationEmitter}.
 *   <li>{@link #signedIntentTokenSupported}: forward-compat reservation for
 *       the OWASP 2026 "signed intent" recommendation (Appendix B.10).
 *       Default {@code false}; the slice does not ship signing in V1.
 *   <li>{@link #transport}: the {@link TransportTag} this source corresponds to
 *       on the wire. {@code Optional.empty()} when no single transport
 *       captures the source. The {@code IntentSourceCatalog}'s transport-based
 *       lookup ({@code findByTransport}) uses this field to resolve from a
 *       {@link TransportTag} back to a source — replacing the hard-coded
 *       switch the pre-Commit-4 default method carried. Plugin catalogs
 *       supply their own transport association without inheriting a
 *       CORE-specific mapping (post-impl fix A3).
 * </ul>
 *
 * @see IntentSourceCatalog
 * @see IntentExtractor
 * @see SourceTier
 */
public record IntentSource(
    IntentSourceRef id,
    Presentation presentation,
    Provenance declarationProvenance,
    SourceTier sourceTier,
    String extractorId,
    boolean signedIntentTokenSupported,
    Optional<TransportTag> transport) implements Provenanced {

  /**
   * Tempdoc 560 §4.1 — conform to the shared {@link Provenanced} axis. IntentSource names its
   * component {@code declarationProvenance} historically; this exposes it under the unified
   * {@code provenance()} accessor every other declaration kind shares.
   */
  @Override
  public Provenance provenance() {
    return declarationProvenance;
  }

  public IntentSource {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(presentation, "presentation");
    Objects.requireNonNull(declarationProvenance, "declarationProvenance");
    Objects.requireNonNull(sourceTier, "sourceTier");
    Objects.requireNonNull(extractorId, "extractorId");
    Objects.requireNonNull(transport, "transport");
    if (extractorId.isBlank()) {
      throw new IllegalArgumentException("extractorId must be non-blank");
    }
  }

  /**
   * Pre-Commit-4 6-arg compatibility constructor — preserves the prior record
   * shape with no transport association. Sources constructed via this overload
   * get {@code Optional.empty()} for {@code transport} and are not discoverable
   * via {@link IntentSourceCatalog#findByTransport(TransportTag)}. New
   * registrations should use the canonical 7-arg constructor.
   */
  public IntentSource(
      IntentSourceRef id,
      Presentation presentation,
      Provenance declarationProvenance,
      SourceTier sourceTier,
      String extractorId,
      boolean signedIntentTokenSupported) {
    this(
        id,
        presentation,
        declarationProvenance,
        sourceTier,
        extractorId,
        signedIntentTokenSupported,
        Optional.empty());
  }
}
