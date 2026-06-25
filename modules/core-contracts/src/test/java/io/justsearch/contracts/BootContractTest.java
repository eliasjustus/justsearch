package io.justsearch.contracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Tempdoc 402 §3.2: @BootContract + registry + runner + audit behavior. */
class BootContractTest {

  @BootContract(description = "smoke", tempdoc = "402 P2", validator = "dummy.Validator")
  private static final class BootSample {}

  @BootContract(
      description = "audit-positive",
      tempdoc = "402 P2",
      validator = "io.justsearch.contracts.BootContractTest$ServiceLoaderProbeValidator")
  private static final class AuditPositiveSample {}

  @BeforeEach
  void resetFixture() {
    BootContractRegistry.reset();
    PassingValidator.invocations.set(0);
    FailingValidator.invocations.set(0);
    ThrowingValidator.invocations.set(0);
    ServiceLoaderProbeValidator.invocations.set(0);
  }

  @AfterEach
  void tearDown() {
    BootContractRegistry.reset();
  }

  @Test
  void bootContractAttrsRoundTripViaReflection() {
    BootContract ann = BootSample.class.getAnnotation(BootContract.class);
    assertNotNull(ann);
    assertEquals("smoke", ann.description());
    assertEquals("402 P2", ann.tempdoc());
    assertEquals("dummy.Validator", ann.validator());
  }

  @Test
  void bootContractRetainedAtRuntimeWithCorrectTargets() {
    Retention retention = BootContract.class.getAnnotation(Retention.class);
    assertNotNull(retention);
    assertEquals(RetentionPolicy.RUNTIME, retention.value());

    Target target = BootContract.class.getAnnotation(Target.class);
    assertNotNull(target);
    ElementType[] elements = target.value();
    assertTrue(containsElement(elements, ElementType.TYPE));
    assertTrue(containsElement(elements, ElementType.METHOD));
    assertTrue(containsElement(elements, ElementType.CONSTRUCTOR));
  }

  @Test
  void registryDiscoversServiceLoaderEntries() {
    List<BootContractValidator> found = BootContractRegistry.validators();
    assertTrue(
        found.stream().anyMatch(v -> v instanceof ServiceLoaderProbeValidator),
        "ServiceLoader discovery did not find ServiceLoaderProbeValidator on the classpath");
  }

  @Test
  void registryResetCausesReDiscoveryOnNextValidatorsCall() {
    // Cleanup fix for tempdoc 402 P2-P6 critical-analysis Issue 1: pin the
    // reset-then-reload behavior. ServiceLoader.load creates fresh instances on
    // every invocation, so a reset() followed by validators() must yield a NEW
    // ServiceLoaderProbeValidator instance (not the cached one from before
    // reset). A future refactor that accidentally retains the cached list across
    // reset would silently break test isolation — every @BeforeEach would
    // observe stale validator instances. This test fails fast if that happens.
    BootContractValidator probeBeforeReset =
        BootContractRegistry.validators().stream()
            .filter(v -> v instanceof ServiceLoaderProbeValidator)
            .findFirst()
            .orElseThrow();
    BootContractRegistry.reset();
    BootContractValidator probeAfterReset =
        BootContractRegistry.validators().stream()
            .filter(v -> v instanceof ServiceLoaderProbeValidator)
            .findFirst()
            .orElseThrow();
    assertTrue(
        probeBeforeReset != probeAfterReset,
        "reset() must null the cache so next validators() call creates fresh"
            + " ServiceLoader instances; got the same probe reference both times, which means"
            + " reset silently no-op'd");
  }

  @Test
  void findViolationsReturnsEmptyWhenAllValidatorsPass() {
    List<ContractViolation> violations =
        BootContractRunner.findViolations(List.of(new PassingValidator(), new PassingValidator()));
    assertTrue(violations.isEmpty(), "no violations expected when every validator passes");
    assertEquals(2, PassingValidator.invocations.get());
  }

  @Test
  void findViolationsCapturesContractViolation() {
    List<ContractViolation> violations =
        BootContractRunner.findViolations(List.of(new FailingValidator()));
    assertEquals(1, violations.size());
    assertEquals("failing-validator", violations.get(0).getMessage());
    assertEquals(1, FailingValidator.invocations.get());
  }

  @Test
  void findViolationsWrapsUncheckedExceptionAsContractViolation() {
    List<ContractViolation> violations =
        BootContractRunner.findViolations(List.of(new ThrowingValidator()));
    assertEquals(1, violations.size());

    ContractViolation wrapped = violations.get(0);
    assertTrue(
        wrapped.getMessage().contains("unexpected failure"),
        "wrapped message should name the failure mode; got: " + wrapped.getMessage());
    assertTrue(
        wrapped.getMessage().contains(ThrowingValidator.class.getName()),
        "wrapped message should name the failing validator; got: " + wrapped.getMessage());
    assertInstanceOf(RuntimeException.class, wrapped.getCause());
    assertEquals("throwing-validator unchecked", wrapped.getCause().getMessage());
  }

  @Test
  void findViolationsCollectsAllViolationsNotJustFirst() {
    // Departure from a literal first-then-stop reading of tempdoc 402 §3.2:
    // collect-all is preserved-system-level-fail-fast (validateAll still
    // System.exits) but gives the operator every problem at once rather than
    // one per boot-retry cycle. Validators are SPI-contracted idempotent.
    List<BootContractValidator> all = new ArrayList<>();
    all.add(new FailingValidator());
    all.add(new ThrowingValidator());
    all.add(new FailingValidator());

    List<ContractViolation> violations = BootContractRunner.findViolations(all);
    assertEquals(3, violations.size(), "all three failing validators must be reported");
    assertEquals(2, FailingValidator.invocations.get());
    assertEquals(1, ThrowingValidator.invocations.get());
  }

  @Test
  void productionFindViolationsUsesRegistryAndInvokesProbe() {
    List<ContractViolation> violations = BootContractRunner.findViolations();
    assertTrue(
        violations.isEmpty(),
        "probe validator passes; no violations expected. Got: " + violations);
    assertTrue(
        ServiceLoaderProbeValidator.invocations.get() > 0,
        "probe validator must have been invoked through the production findViolations() path");
  }

  @Test
  void validatorTempdocDefaultsToEmpty() {
    assertEquals("", new PassingValidator().tempdoc());
  }

  @Test
  void validatorTempdocIsOverridable() {
    BootContractValidator v =
        new BootContractValidator() {
          @Override
          public void validate() {
            // passing
          }

          @Override
          public String tempdoc() {
            return "402 P2 review";
          }
        };
    assertEquals("402 P2 review", v.tempdoc());
  }

  @Test
  void auditAcceptsClassWithRegisteredValidator() {
    // AuditPositiveSample names ServiceLoaderProbeValidator which IS SPI-registered.
    BootContractAudit.auditAnnotatedClass(AuditPositiveSample.class);
  }

  @Test
  void auditRejectsUnknownValidatorFqn() {
    // BootSample names "dummy.Validator" which does not resolve.
    IllegalStateException ex =
        assertThrows(
            IllegalStateException.class,
            () -> BootContractAudit.auditAnnotatedClass(BootSample.class));
    assertTrue(
        ex.getMessage().contains("dummy.Validator"),
        "audit failure should name the bad FQN; got: " + ex.getMessage());
    assertTrue(
        ex.getMessage().contains("silent no-op at boot"),
        "audit failure should explain the silent-failure consequence; got: " + ex.getMessage());
  }

  @Test
  void auditRejectsUnannotatedClass() {
    IllegalStateException ex =
        assertThrows(
            IllegalStateException.class,
            () -> BootContractAudit.auditAnnotatedClass(String.class));
    assertTrue(
        ex.getMessage().contains("not @BootContract-annotated"),
        "audit should reject non-annotated classes; got: " + ex.getMessage());
  }

  @Test
  void auditRejectsValidatorThatIsNotOnSpiList() {
    // Unregistered validator — compiles and loads, but not in META-INF/services.
    IllegalStateException ex =
        assertThrows(
            IllegalStateException.class,
            () -> BootContractAudit.auditAnnotatedClass(UnregisteredAuditSample.class));
    assertTrue(
        ex.getMessage().contains("not on the ServiceLoader list"),
        "audit should name the registration gap; got: " + ex.getMessage());
  }

  private static boolean containsElement(ElementType[] types, ElementType target) {
    for (ElementType t : types) {
      if (t == target) {
        return true;
      }
    }
    return false;
  }

  // ---- Fixtures -------------------------------------------------------------

  @BootContract(
      description = "unregistered-validator",
      tempdoc = "402 P2 audit",
      validator = "io.justsearch.contracts.BootContractTest$UnregisteredValidator")
  private static final class UnregisteredAuditSample {}

  /**
   * Deliberately NOT registered in META-INF/services — exercises the audit's
   * "names a validator that isn't SPI-registered" branch.
   */
  public static final class UnregisteredValidator implements BootContractValidator {
    @Override
    public void validate() {
      // never invoked — audit rejects before any discovery
    }
  }

  public static final class PassingValidator implements BootContractValidator {
    static final AtomicInteger invocations = new AtomicInteger(0);

    @Override
    public void validate() {
      invocations.incrementAndGet();
    }
  }

  public static final class FailingValidator implements BootContractValidator {
    static final AtomicInteger invocations = new AtomicInteger(0);

    @Override
    public void validate() throws ContractViolation {
      invocations.incrementAndGet();
      throw new ContractViolation("failing-validator");
    }
  }

  public static final class ThrowingValidator implements BootContractValidator {
    static final AtomicInteger invocations = new AtomicInteger(0);

    @Override
    public void validate() {
      invocations.incrementAndGet();
      throw new RuntimeException("throwing-validator unchecked");
    }
  }

  /** Registered via META-INF/services — proves SPI discovery works. */
  public static final class ServiceLoaderProbeValidator implements BootContractValidator {
    static final AtomicInteger invocations = new AtomicInteger(0);

    @Override
    public void validate() {
      invocations.incrementAndGet();
    }

    @Override
    public String tempdoc() {
      return "402 P2 probe";
    }
  }
}
