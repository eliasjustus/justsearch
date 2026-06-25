package io.justsearch.contracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import org.junit.jupiter.api.Test;

/** Smoke test that the tempdoc 400 LR6-a annotations are well-formed + instantiable. */
class ContractAnnotationsTest {

  @BuildContract(description = "smoke", tempdoc = "400 LR6-a", enforcer = "ContractAnnotationsTest")
  private static final class BuildSample {}

  @AdvisoryContract(description = "smoke", tempdoc = "400 LR6-a", signal = "test.smoke")
  private static final class AdvisorySample {}

  @Test
  void buildContractAttrsRoundTripViaReflection() {
    BuildContract ann = BuildSample.class.getAnnotation(BuildContract.class);
    assertNotNull(ann);
    assertEquals("smoke", ann.description());
    assertEquals("400 LR6-a", ann.tempdoc());
    assertEquals("ContractAnnotationsTest", ann.enforcer());
  }

  @Test
  void advisoryContractAttrsRoundTripViaReflection() {
    AdvisoryContract ann = AdvisorySample.class.getAnnotation(AdvisoryContract.class);
    assertNotNull(ann);
    assertEquals("smoke", ann.description());
    assertEquals("400 LR6-a", ann.tempdoc());
    assertEquals("test.smoke", ann.signal());
  }

  @Test
  void bothAnnotationsRetainedAtRuntime() {
    assertNotNull(BuildContract.class.getAnnotation(Retention.class));
    assertNotNull(AdvisoryContract.class.getAnnotation(Retention.class));
    // Target presence: the annotations are class / method / constructor scoped.
    assertNotNull(BuildContract.class.getAnnotation(Target.class));
    assertNotNull(AdvisoryContract.class.getAnnotation(Target.class));
  }
}
