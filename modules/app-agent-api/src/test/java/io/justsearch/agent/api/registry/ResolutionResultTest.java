package io.justsearch.agent.api.registry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ResolutionResult (tempdoc 499 §4.1)")
final class ResolutionResultTest {

  @Test
  @DisplayName("Resolved carries entry")
  void resolved() {
    var result = new ResolutionResult.Resolved<>("entry");
    assertInstanceOf(ResolutionResult.Resolved.class, result);
    assertEquals("entry", result.entry());
  }

  @Test
  @DisplayName("Redirected carries entry, originalId, and reason")
  void redirected() {
    var result = new ResolutionResult.Redirected<>(
        "entry", "core.old-id", ResolutionResult.RedirectReason.RENAMED);
    assertEquals("entry", result.entry());
    assertEquals("core.old-id", result.originalId());
    assertEquals(ResolutionResult.RedirectReason.RENAMED, result.reason());
  }

  @Test
  @DisplayName("Unresolved carries attemptedId, diagnosis, and alternatives")
  void unresolved() {
    var diag = new ResolutionResult.UnresolvedDiagnosis(
        ResolutionResult.FailureMode.TYPO, "No entry 'core.libary'");
    var suggestion = new ResolutionResult.Suggestion<>(
        "core.library-surface", "core.library-surface", 0.91, "edit-distance=1");
    var result = new ResolutionResult.Unresolved<>(
        "core.libary", diag, List.of(suggestion));
    assertEquals("core.libary", result.attemptedId());
    assertEquals(ResolutionResult.FailureMode.TYPO, result.diagnosis().mode());
    assertEquals(1, result.alternatives().size());
  }

  @Test
  @DisplayName("Unresolved with null alternatives defaults to empty list")
  void unresolvedNullAlternatives() {
    var diag = new ResolutionResult.UnresolvedDiagnosis(
        ResolutionResult.FailureMode.UNKNOWN, "unknown");
    var result = new ResolutionResult.Unresolved<>("id", diag, null);
    assertTrue(result.alternatives().isEmpty());
  }

  @Test
  @DisplayName("Resolved rejects null entry")
  void resolvedRejectsNull() {
    assertThrows(NullPointerException.class, () -> new ResolutionResult.Resolved<>(null));
  }

  @Test
  @DisplayName("Suggestion carries all fields")
  void suggestion() {
    var s = new ResolutionResult.Suggestion<>("entry", "core.x", 0.85, "edit-distance=2");
    assertEquals("entry", s.entry());
    assertEquals("core.x", s.refId());
    assertEquals(0.85, s.confidence(), 0.001);
    assertEquals("edit-distance=2", s.rationale());
  }
}
