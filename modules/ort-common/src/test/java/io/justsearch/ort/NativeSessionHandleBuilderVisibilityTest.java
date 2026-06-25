package io.justsearch.ort;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Modifier;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 402 Phase P4 — enforcer for the {@code @BuildContract} on
 * {@link NativeSessionHandle.Builder}. Pins package-private visibility so a future widening
 * (e.g. {@code public Builder}) fails a test rather than slipping through code review.
 *
 * <p>Matches the {@code enforcer = "NativeSessionHandleBuilderVisibilityTest"} attribute on the
 * annotation at {@link NativeSessionHandle.Builder}. Named via the annotation's {@code enforcer}
 * string so a reader can navigate from the contract straight to this test.
 */
class NativeSessionHandleBuilderVisibilityTest {

  @Test
  void builderIsPackagePrivate() {
    Class<?> builder = findNestedBuilder();
    int mods = builder.getModifiers();
    assertFalse(
        Modifier.isPublic(mods),
        "NativeSessionHandle.Builder must NOT be public — §14.19 made it package-private to"
            + " enforce the single-construction-path invariant. If you widened it, update the"
            + " @BuildContract annotation + this test, or route new callers through"
            + " OrtSessionAssembler#buildManager.");
    assertFalse(
        Modifier.isProtected(mods),
        "NativeSessionHandle.Builder must NOT be protected — see javadoc.");
    assertTrue(Modifier.isStatic(mods), "Builder should be static");
    assertTrue(Modifier.isFinal(mods), "Builder should be final");
  }

  private static Class<?> findNestedBuilder() {
    for (Class<?> nested : NativeSessionHandle.class.getDeclaredClasses()) {
      if ("Builder".equals(nested.getSimpleName())) {
        return nested;
      }
    }
    throw new AssertionError("NativeSessionHandle.Builder not found — was it renamed or moved?");
  }
}
