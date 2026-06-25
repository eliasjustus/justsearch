package io.justsearch.contracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.Test;

/** Tempdoc 402 §3.3: @SampleContract attribute round-trip + retention. */
class SampleContractTest {

  @SampleContract(
      description = "smoke",
      tempdoc = "402 P5",
      every = 2500,
      validator = "dummy.Validator")
  private static void annotatedSite() {}

  @SampleContract(description = "defaults", tempdoc = "402 P5")
  private static void defaultedSite() {}

  @Test
  void sampleContractAttrsRoundTripViaReflection() throws Exception {
    SampleContract ann =
        SampleContractTest.class
            .getDeclaredMethod("annotatedSite")
            .getAnnotation(SampleContract.class);
    assertNotNull(ann);
    assertEquals("smoke", ann.description());
    assertEquals("402 P5", ann.tempdoc());
    assertEquals(2500, ann.every());
    assertEquals("dummy.Validator", ann.validator());
  }

  @Test
  void sampleContractDefaultsAreEveryOneThousandAndEmptyValidator() throws Exception {
    SampleContract ann =
        SampleContractTest.class
            .getDeclaredMethod("defaultedSite")
            .getAnnotation(SampleContract.class);
    assertNotNull(ann);
    assertEquals(1000, ann.every(), "tempdoc 402 §3.3 locks the default sample rate at 1000");
    assertEquals("", ann.validator());
  }

  @Test
  void sampleContractRetainedAtRuntimeWithCorrectTargets() {
    Retention retention = SampleContract.class.getAnnotation(Retention.class);
    assertNotNull(retention);
    assertEquals(RetentionPolicy.RUNTIME, retention.value());

    Target target = SampleContract.class.getAnnotation(Target.class);
    assertNotNull(target);
    ElementType[] elements = target.value();
    assertTrue(containsElement(elements, ElementType.METHOD));
    assertTrue(containsElement(elements, ElementType.CONSTRUCTOR));
  }

  private static boolean containsElement(ElementType[] types, ElementType target) {
    for (ElementType t : types) {
      if (t == target) {
        return true;
      }
    }
    return false;
  }
}
