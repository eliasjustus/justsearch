/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.api;

import java.util.Locale;

/**
 * Tempdoc 865 §7.6 / 868 §B.3, given an authority by 878 §D.9 — HOW a grounding source came to be in
 * front of the model.
 *
 * <p>{@link AgentEvent.AgentSource} carries three other cross-boundary vocabularies as Strings, and
 * each of them PROJECTS a named enum: {@code contextInclusion} projects {@code
 * DocumentService.ContextInclusion.State}, {@code citationScorer} projects {@code
 * DocumentService.ScorerKind}, {@code disposition} projects {@code TerminalDisposition}. The values
 * are Strings on the record because {@code app-agent-api} is annotation-light and does not depend on
 * the modules those enums live in — a projection, not a parallel vocabulary free to drift.
 *
 * <p>{@code acquisition} was the exception: two hand-written String constants with no type behind
 * them, so "is this a legal acquisition value" had no answer a compiler could give and adding a
 * third would have been an edit to a comment. This enum is the authority the other three already
 * had; the record's constants become its wire projection.
 *
 * <p><b>The invariant is DIRECTIONAL and must not be read the other way.</b> An opened-by-name
 * document has LESS relevance evidence than a retrieved one, never more — nothing ranked it, the
 * agent simply asked for it. It is also an IDENTITY component, fixed at the mint: how a source was
 * acquired is knowable exactly when it is established and never changes afterwards.
 */
public enum SourceAcquisition {
  /** A search matched it: something ranked this passage against the query. */
  RETRIEVED("retrieved"),
  /** The agent named it and read it: nothing ranked it (868 §B.3, {@code core.read-document}). */
  OPENED("opened");

  private final String wireToken;

  SourceAcquisition(String wireToken) {
    this.wireToken = wireToken;
  }

  /** The lowercase token carried on the wire and stored on {@link AgentEvent.AgentSource}. */
  public String wireToken() {
    return wireToken;
  }

  /**
   * The acquisition a blank or unrecognised token resolves to.
   *
   * <p>{@link #RETRIEVED} rather than a third "unknown" value, deliberately: every producer that
   * predates the axis was a search, so defaulting to retrieved describes them correctly rather than
   * making a historical record say something it cannot support. It is also the SAFE direction for
   * the invariant above — treating an unknown source as retrieved never claims more relevance
   * evidence than it has, because retrieved is the arm that HAS the evidence and the reader that
   * acts on this axis acts on {@code opened}.
   */
  public static SourceAcquisition fromWireToken(String token) {
    if (token != null) {
      String normalized = token.trim().toLowerCase(Locale.ROOT);
      for (SourceAcquisition value : values()) {
        if (value.wireToken.equals(normalized)) {
          return value;
        }
      }
    }
    return RETRIEVED;
  }
}
