package io.justsearch.agent.api.registry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ShellAddress (slice 489 §4 sealed address taxonomy)")
final class ShellAddressTest {

  @Test
  @DisplayName("Navigation carries SurfaceRef + StateSnapshot")
  void navigationCarriesTargetAndState() {
    SurfaceRef target = new SurfaceRef("core.library-surface");
    StateSnapshot state = StateSnapshot.empty();
    ShellAddress.Navigation nav = new ShellAddress.Navigation(target, state);
    assertEquals("core.library-surface", nav.target().value());
    assertTrue(nav.state().values().isEmpty());
  }

  @Test
  @DisplayName("Invocation carries OperationRef + argsJson + optional confirmation token")
  void invocationCarriesTargetArgsAndToken() {
    OperationRef target = new OperationRef("core.search-index");
    ShellAddress.Invocation inv =
        new ShellAddress.Invocation(target, "{\"query\":\"rust\"}", Optional.of("confirm-token-1"));
    assertEquals("core.search-index", inv.target().value());
    assertEquals("{\"query\":\"rust\"}", inv.argsJson());
    assertTrue(inv.confirmationToken().isPresent());
    assertEquals("confirm-token-1", inv.confirmationToken().get());
  }

  @Test
  @DisplayName("Invocation.of(...) produces no-confirmation-token invocation")
  void invocationOfConvenience() {
    ShellAddress.Invocation inv =
        ShellAddress.Invocation.of(new OperationRef("core.bulk-reindex"), "{}");
    assertFalse(inv.confirmationToken().isPresent());
  }

  @Test
  @DisplayName("Navigation rejects nulls")
  void navigationRejectsNulls() {
    assertThrows(
        NullPointerException.class,
        () -> new ShellAddress.Navigation(null, StateSnapshot.empty()));
    assertThrows(
        NullPointerException.class,
        () -> new ShellAddress.Navigation(new SurfaceRef("core.library-surface"), null));
  }

  @Test
  @DisplayName("Invocation rejects nulls")
  void invocationRejectsNulls() {
    OperationRef ref = new OperationRef("core.search-index");
    assertThrows(
        NullPointerException.class,
        () -> new ShellAddress.Invocation(null, "{}", Optional.empty()));
    assertThrows(
        NullPointerException.class,
        () -> new ShellAddress.Invocation(ref, null, Optional.empty()));
    assertThrows(
        NullPointerException.class, () -> new ShellAddress.Invocation(ref, "{}", null));
  }

  @Test
  @DisplayName("Query carries free query text + StateSnapshot")
  void queryCarriesTextAndState() {
    ShellAddress.Query q =
        new ShellAddress.Query("rust ownership", new StateSnapshot(java.util.Map.of("lang", "en")));
    assertEquals("rust ownership", q.query());
    assertEquals("en", q.state().values().get("lang"));
    assertEquals("query", q.kind());
  }

  @Test
  @DisplayName("Query(text) convenience produces an empty-state query")
  void queryConvenience() {
    ShellAddress.Query q = new ShellAddress.Query("rust");
    assertEquals("rust", q.query());
    assertTrue(q.state().values().isEmpty());
  }

  @Test
  @DisplayName("Query rejects nulls")
  void queryRejectsNulls() {
    assertThrows(NullPointerException.class, () -> new ShellAddress.Query(null, StateSnapshot.empty()));
    assertThrows(NullPointerException.class, () -> new ShellAddress.Query("rust", null));
  }

  @Test
  @DisplayName("Answer carries prompt + shape + StateSnapshot")
  void answerCarriesPromptShapeAndState() {
    ShellAddress.Answer a =
        new ShellAddress.Answer("what is rust", "core.summarize", StateSnapshot.empty());
    assertEquals("what is rust", a.prompt());
    assertEquals("core.summarize", a.shape());
    assertEquals("answer", a.kind());
  }

  @Test
  @DisplayName("Answer(prompt) convenience uses the default shape")
  void answerConvenience() {
    ShellAddress.Answer a = new ShellAddress.Answer("hello");
    assertEquals("core.rag-ask", a.shape());
    assertTrue(a.state().values().isEmpty());
  }

  @Test
  @DisplayName("Answer rejects nulls")
  void answerRejectsNulls() {
    assertThrows(NullPointerException.class, () -> new ShellAddress.Answer(null, "s", StateSnapshot.empty()));
    assertThrows(NullPointerException.class, () -> new ShellAddress.Answer("p", null, StateSnapshot.empty()));
    assertThrows(NullPointerException.class, () -> new ShellAddress.Answer("p", "s", null));
  }

  @Test
  @DisplayName("ShellAddress is sealed to Navigation + Invocation + Query + Answer")
  void sealedTaxonomy() {
    // Compile-time guarantee — verified by the type system. Runtime check:
    // pattern matching exhausts the type with four arms.
    ShellAddress addr =
        new ShellAddress.Navigation(new SurfaceRef("core.library-surface"), StateSnapshot.empty());
    String result =
        switch (addr) {
          case ShellAddress.Navigation n -> "nav-" + n.target().value();
          case ShellAddress.Invocation i -> "inv-" + i.target().value();
          case ShellAddress.Query q -> "query-" + q.query();
          case ShellAddress.Answer a -> "answer-" + a.prompt();
        };
    assertEquals("nav-core.library-surface", result);
  }
}
