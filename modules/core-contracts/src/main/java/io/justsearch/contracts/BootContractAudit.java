/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.contracts;

/**
 * Structural audit for {@link BootContract}-annotated sites (tempdoc 402 §3.2).
 *
 * <p>The annotation's {@code validator()} attribute is declarative metadata;
 * the runtime dispatches through {@link java.util.ServiceLoader}, not through
 * the string. That means a typo'd FQN, a missing {@code META-INF/services}
 * entry, or a removed validator class would leave the invariant silently
 * unchecked at boot — the exact silent-failure class tempdocs 402 / 404 /
 * observability-playbook exist to eliminate.
 *
 * <p>This audit closes that hole at test time. Every module that adds a
 * {@code @BootContract} annotation should include a one-line unit test:
 *
 * <pre>{@code
 * @Test
 * void myContractIsEnforceable() {
 *   BootContractAudit.auditAnnotatedClass(MyAnnotatedClass.class);
 * }
 * }</pre>
 *
 * <p>The audit verifies four conditions, each failing fast with an
 * {@link IllegalStateException} naming the offending site:
 *
 * <ol>
 *   <li>The class carries a {@code @BootContract} annotation.
 *   <li>The {@code validator()} FQN resolves to a loadable class.
 *   <li>That class implements {@link BootContractValidator}.
 *   <li>An instance of that class is discoverable via
 *       {@link BootContractRegistry} (proves the SPI registration exists).
 * </ol>
 */
public final class BootContractAudit {

  private BootContractAudit() {}

  /**
   * Verify that a {@code @BootContract}-annotated class's {@code validator()}
   * FQN resolves to a loadable {@link BootContractValidator} that is
   * registered via {@link java.util.ServiceLoader}.
   *
   * @throws IllegalStateException if any of the four audit conditions fail.
   */
  public static void auditAnnotatedClass(Class<?> annotated) {
    BootContract ann = annotated.getAnnotation(BootContract.class);
    if (ann == null) {
      throw new IllegalStateException(
          "audit target " + annotated.getName() + " is not @BootContract-annotated");
    }
    String validatorFqn = ann.validator();

    Class<?> validatorClass;
    try {
      validatorClass = Class.forName(validatorFqn);
    } catch (ClassNotFoundException e) {
      throw new IllegalStateException(
          "@BootContract on "
              + annotated.getName()
              + " references validator "
              + validatorFqn
              + " which does not resolve (silent no-op at boot)",
          e);
    }
    if (!BootContractValidator.class.isAssignableFrom(validatorClass)) {
      throw new IllegalStateException(
          "@BootContract on "
              + annotated.getName()
              + " names "
              + validatorFqn
              + " which does not implement BootContractValidator");
    }
    boolean registered =
        BootContractRegistry.validators().stream()
            .anyMatch(v -> v.getClass().getName().equals(validatorFqn));
    if (!registered) {
      throw new IllegalStateException(
          "@BootContract on "
              + annotated.getName()
              + " names validator "
              + validatorFqn
              + " but it is not on the ServiceLoader list"
              + " — add an entry to META-INF/services/io.justsearch.contracts.BootContractValidator,"
              + " or the invariant is a silent no-op at boot");
    }
  }
}
