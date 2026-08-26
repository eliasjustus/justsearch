package io.justsearch.app.services.intent;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.agent.api.registry.AuditPolicy;
import io.justsearch.agent.api.registry.Binding;
import io.justsearch.agent.api.registry.ConfirmStrategy;
import io.justsearch.agent.api.registry.ExecutorTag;
import io.justsearch.agent.api.registry.I18nKey;
import io.justsearch.agent.api.registry.Interface;
import io.justsearch.agent.api.registry.Operation;
import io.justsearch.agent.api.registry.OperationAvailability;
import io.justsearch.agent.api.registry.OperationLineage;
import io.justsearch.agent.api.registry.OperationPolicy;
import io.justsearch.agent.api.registry.OperationRef;
import io.justsearch.agent.api.registry.Presentation;
import io.justsearch.agent.api.registry.Provenance;
import io.justsearch.agent.api.registry.RetryPolicy;
import io.justsearch.agent.api.registry.RiskTier;
import io.justsearch.agent.api.registry.TrustTier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tempdoc 875 C.3 — the argument scope. Every adverse precondition must produce "not covered" (which
 * costs a confirmation), never a silent "covered" (`green-masked-destructive`).
 */
@DisplayName("IndexedRootGrantScope")
class IndexedRootGrantScopeTest {

  private static final OperationRef GOVERNED = new OperationRef("core.ingest-files");
  private static final OperationRef UNGOVERNED = new OperationRef("core.search-index");

  private static IndexedRootGrantScope scopeBoundTo(Path... roots) {
    IndexedRootGrantScope scope = new IndexedRootGrantScope(Set.of(GOVERNED));
    scope.bindIndexedRoots(() -> List.of(roots));
    return scope;
  }

  private static String argsFor(Path... paths) {
    StringBuilder sb = new StringBuilder("{\"paths\":[");
    for (int i = 0; i < paths.length; i++) {
      if (i > 0) {
        sb.append(',');
      }
      sb.append('"').append(paths[i].toAbsolutePath().toString().replace("\\", "\\\\")).append('"');
    }
    return sb.append("]}").toString();
  }

  @Test
  @DisplayName("an operation outside the governed set is covered unconditionally, even unbound")
  void ungovernedOperationIsAlwaysCovered() {
    IndexedRootGrantScope scope = new IndexedRootGrantScope(Set.of(GOVERNED));
    assertTrue(
        scope.coversArguments(op(UNGOVERNED), "{\"query\":\"anything\"}"),
        "containment is not a defined concept for a non-filesystem operation");
  }

  @Test
  @DisplayName("every paths entry inside an indexed root ⇒ covered")
  void inRootPathsAreCovered(@TempDir Path root) throws Exception {
    Path a = Files.createFile(root.resolve("a.txt"));
    Path b = Files.createDirectory(root.resolve("sub"));
    IndexedRootGrantScope scope = scopeBoundTo(root);

    assertTrue(scope.coversArguments(op(GOVERNED), argsFor(a, b)));
  }

  @Test
  @DisplayName("one out-of-root entry poisons the whole invocation ⇒ not covered")
  void anyOutOfRootPathIsNotCovered(@TempDir Path base) throws Exception {
    Path root = Files.createDirectory(base.resolve("indexed"));
    Path outside = Files.createDirectory(base.resolve("elsewhere"));
    Path inside = Files.createFile(root.resolve("ok.txt"));
    Path escape = Files.createFile(outside.resolve("secret.txt"));
    IndexedRootGrantScope scope = scopeBoundTo(root);

    assertFalse(scope.coversArguments(op(GOVERNED), argsFor(escape)), "a wholly out-of-root ingest");
    assertFalse(
        scope.coversArguments(op(GOVERNED), argsFor(inside, escape)),
        "one out-of-root entry is enough — containment must hold for EVERY path");
  }

  // ── Adverse preconditions (green-masked-destructive): each must be a confirm, not a proceed ──────

  @Test
  @DisplayName("adverse: the roots supplier is never bound ⇒ not covered")
  void unboundSupplierIsNotCovered(@TempDir Path root) throws Exception {
    Path file = Files.createFile(root.resolve("a.txt"));
    IndexedRootGrantScope scope = new IndexedRootGrantScope(Set.of(GOVERNED));

    assertFalse(
        scope.coversArguments(op(GOVERNED), argsFor(file)),
        "unbound ⇒ containment unprovable ⇒ a wiring regression costs a prompt, not a silent grant");
  }

  @Test
  @DisplayName("adverse: the roots supplier throws ⇒ not covered")
  void throwingSupplierIsNotCovered(@TempDir Path root) throws Exception {
    Path file = Files.createFile(root.resolve("a.txt"));
    IndexedRootGrantScope scope = new IndexedRootGrantScope(Set.of(GOVERNED));
    scope.bindIndexedRoots(
        () -> {
          throw new IllegalStateException("Worker unavailable");
        });

    assertFalse(scope.coversArguments(op(GOVERNED), argsFor(file)));
  }

  @Test
  @DisplayName("adverse: the roots supplier returns empty (or null) ⇒ not covered")
  void emptyRootsAreNotCovered(@TempDir Path root) throws Exception {
    Path file = Files.createFile(root.resolve("a.txt"));
    IndexedRootGrantScope empty = new IndexedRootGrantScope(Set.of(GOVERNED));
    empty.bindIndexedRoots(List::of);
    IndexedRootGrantScope nullish = new IndexedRootGrantScope(Set.of(GOVERNED));
    nullish.bindIndexedRoots(() -> null);

    assertFalse(empty.coversArguments(op(GOVERNED), argsFor(file)), "no roots ⇒ nothing is contained");
    assertFalse(nullish.coversArguments(op(GOVERNED), argsFor(file)), "a null root list too");
  }

  @Test
  @DisplayName("adverse: unreadable / pathless arguments ⇒ not covered")
  void unusableArgumentsAreNotCovered(@TempDir Path root) {
    IndexedRootGrantScope scope = scopeBoundTo(root);
    Operation op = op(GOVERNED);

    assertFalse(scope.coversArguments(op, null), "null args");
    assertFalse(scope.coversArguments(op, "   "), "blank args");
    assertFalse(scope.coversArguments(op, "{not-json"), "unparseable args");
    assertFalse(scope.coversArguments(op, "[1,2,3]"), "args that are not an object");
    assertFalse(scope.coversArguments(op, "{\"collection\":\"x\"}"), "no paths key");
    assertFalse(scope.coversArguments(op, "{\"paths\":\"a\"}"), "paths is not an array");
    assertFalse(scope.coversArguments(op, "{\"paths\":[]}"), "an empty paths array proves nothing");
    assertFalse(scope.coversArguments(op, "{\"paths\":[123]}"), "a non-string entry");
    assertFalse(scope.coversArguments(op, "{\"paths\":[\"  \"]}"), "a blank entry");
  }

  @Test
  @DisplayName("containment is canonicalized, so a sibling with a shared name prefix is not inside")
  void siblingPrefixIsNotInside(@TempDir Path parent) throws Exception {
    Path root = Files.createDirectory(parent.resolve("docs"));
    Path sibling = Files.createDirectory(parent.resolve("docs-private"));
    Path secret = Files.createFile(sibling.resolve("s.txt"));
    IndexedRootGrantScope scope = scopeBoundTo(root);

    assertFalse(
        scope.coversArguments(op(GOVERNED), argsFor(secret)),
        "startsWith is path-element-wise after realpath, not a string prefix");
  }

  @Test
  @DisplayName("a not-yet-existing path is canonicalized via its closest existing ancestor")
  void nonExistentPathResolvesThroughClosestExistingAncestor(@TempDir Path base) throws Exception {
    Path root = Files.createDirectory(base.resolve("indexed"));
    Path outside = Files.createDirectory(base.resolve("elsewhere"));
    IndexedRootGrantScope scope = scopeBoundTo(root);

    assertTrue(
        scope.coversArguments(op(GOVERNED), argsFor(root.resolve("not").resolve("there.txt"))),
        "in-root but absent ⇒ still provably inside via the ancestor's real path");
    assertFalse(
        scope.coversArguments(op(GOVERNED), argsFor(outside.resolve("not").resolve("there.txt"))),
        "out-of-root and absent ⇒ still outside");
  }

  private static Operation op(OperationRef id) {
    return new Operation(
        id,
        Presentation.of(
            new I18nKey("test." + id.value()), new I18nKey("test." + id.value() + ".desc")),
        Interface.of("{\"type\":\"object\"}", "{\"type\":\"object\"}"),
        new OperationPolicy(
            RiskTier.MEDIUM,
            ConfirmStrategy.Inline.INSTANCE,
            AuditPolicy.NONE,
            RetryPolicy.noRetry(),
            Optional.empty(),
            Set.of(),
            false),
        OperationAvailability.empty(),
        OperationLineage.empty(),
        Binding.of(id),
        new Provenance(TrustTier.CORE, "test", "1.0"),
        Set.of(ExecutorTag.AGENT));
  }
}
