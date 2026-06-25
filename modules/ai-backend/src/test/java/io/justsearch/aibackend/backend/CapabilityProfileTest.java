package io.justsearch.aibackend.backend;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

final class CapabilityProfileTest {

  @Test
  void negativeContextLength_clampedToZero() {
    CapabilityProfile profile =
        new CapabilityProfile(-100, 1.0, SecurityTier.LOCAL_ONLY, List.of());
    assertEquals(0, profile.contextLength());
  }

  @Test
  void negativeThroughput_clampedToZero() {
    CapabilityProfile profile =
        new CapabilityProfile(1000, -5.0, SecurityTier.LOCAL_ONLY, List.of());
    assertEquals(0.0, profile.maxThroughput());
  }

  @Test
  void nullSecurityTier_throws() {
    assertThrows(
        NullPointerException.class,
        () -> new CapabilityProfile(1000, 1.0, null, List.of()));
  }

  @Test
  void nullCapabilities_handledGracefully() {
    CapabilityProfile profile =
        new CapabilityProfile(1000, 1.0, SecurityTier.LOCAL_ONLY, null);
    assertNotNull(profile.capabilities());
    assertTrue(profile.capabilities().isEmpty());
  }

  @Test
  void capabilitiesList_isDefensivelyCopied() {
    List<String> mutable = new ArrayList<>();
    mutable.add("cap1");
    CapabilityProfile profile =
        new CapabilityProfile(1000, 1.0, SecurityTier.LOCAL_ONLY, mutable);

    // Modify the original list
    mutable.add("cap2");

    // Profile should still only have the original capability
    assertEquals(1, profile.capabilities().size());
    assertEquals("cap1", profile.capabilities().get(0));

    // Returned list should be immutable
    assertThrows(
        UnsupportedOperationException.class, () -> profile.capabilities().add("cap3"));
  }

  @Test
  void validProfile_preservesValues() {
    CapabilityProfile profile =
        new CapabilityProfile(4096, 2.5, SecurityTier.REMOTE_TRUSTED, List.of("a", "b"));
    assertEquals(4096, profile.contextLength());
    assertEquals(2.5, profile.maxThroughput());
    assertEquals(SecurityTier.REMOTE_TRUSTED, profile.securityTier());
    assertEquals(List.of("a", "b"), profile.capabilities());
  }
}
