package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 583 §D.4 — the "thin composer" architectural fitness function for {@link LocalApiServer}.
 *
 * <p>583 decomposed LocalApiServer from a 2596-LOC god-class into per-domain collaborators and §B.11
 * collapsed the spread-field re-bloat. That durability rested on a *pattern* (single-{@code Result}
 * field) plus the generic class-size LOC gate. This test adds the structural guarantee the literature
 * calls a fitness function (Ford/Parsons/Kua, <i>Building Evolutionary Architectures</i>): a check in
 * the same pipeline as the rest of the tests that fails the build when LocalApiServer regrows — so the
 * composition root cannot quietly accrete its way back into a god-class.
 *
 * <p>The metric is instance-field count: a thin composer holds a small, fixed set of collaborators
 * (the assembly {@code Result} records, the route modules, the few shared singletons) and delegates;
 * a regrowing god-class accretes a field per feature. Mirrors the reflection-based cardinality
 * ceilings in {@code CompositionRootGuardrailsTest} (the bootstrap composition root's analogue).
 *
 * <p>Field-count was chosen over the behavioural "no class both constructs controllers AND registers
 * routes" rule because the latter has a legitimate false-positive: {@code lateBindKnowledgeServer}
 * re-creates {@code KnowledgeSearchController} on Worker reconnect (583 §D.4).
 *
 * <p><b>To raise the ceiling:</b> adding a field is a deliberate act — confirm the new state truly
 * belongs on the composition root (not inside a collaborator / assembly {@code Result}), then bump
 * {@link #MAX_INSTANCE_FIELDS} in the same change with a one-line rationale.
 */
class LocalApiServerThinComposerTest {

  /**
   * Pinned at the post-583/§B.11 count. No buffer: a field addition must be a conscious bump, which
   * is the whole point — it forces the "should this live on the root?" question.
   *
   * <p>30 (tempdoc 629): +2 for the at-rest conversation-protection layer —
   * {@code conversationEncryptionController} + {@code conversationBackupController}. These genuinely
   * belong on the composition root (the API surface wires both into request handlers). Follow-up
   * logged to fold them into a {@code ConversationSecurityAssembly.Result} per 583 §D.4.
   */
  static final int MAX_INSTANCE_FIELDS = 30;

  @Test
  @DisplayName("LocalApiServer stays a thin composer (instance-field-count ceiling)")
  void localApiServerInstanceFieldCountStaysUnderCeiling() {
    Field[] declared = LocalApiServer.class.getDeclaredFields();
    var instanceFields =
        Arrays.stream(declared)
            .filter(f -> !Modifier.isStatic(f.getModifiers()))
            .filter(f -> !f.isSynthetic()) // exclude e.g. coverage-tool-injected fields
            .map(Field::getName)
            .sorted()
            .collect(Collectors.toList());

    assertTrue(
        instanceFields.size() <= MAX_INSTANCE_FIELDS,
        () ->
            "LocalApiServer instance-field count "
                + instanceFields.size()
                + " > MAX_INSTANCE_FIELDS="
                + MAX_INSTANCE_FIELDS
                + " — the composition root is regrowing (tempdoc 583 §D.4 thin-composer rule). "
                + "Move new state into a collaborator / assembly Result, OR (if it genuinely belongs "
                + "on the root) bump MAX_INSTANCE_FIELDS with a rationale. Fields: "
                + instanceFields);
  }
}
