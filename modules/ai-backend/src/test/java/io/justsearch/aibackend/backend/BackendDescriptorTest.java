package io.justsearch.aibackend.backend;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

final class BackendDescriptorTest {

  private static CapabilityProfile testProfile() {
    return new CapabilityProfile(4096, 1.0, SecurityTier.LOCAL_ONLY, List.of());
  }

  @Test
  void nullProviderId_throws() {
    assertThrows(
        NullPointerException.class,
        () -> new BackendDescriptor(null, List.of("task1"), testProfile()));
  }

  @Test
  void nullSupportedTasks_defaultsToEmptyList() {
    BackendDescriptor descriptor = new BackendDescriptor("test", null, testProfile());
    assertNotNull(descriptor.supportedTasks());
    assertTrue(descriptor.supportedTasks().isEmpty());
  }

  @Test
  void nullProfile_throws() {
    assertThrows(
        NullPointerException.class,
        () -> new BackendDescriptor("test", List.of("task1"), null));
  }

  @Test
  void supportedTasks_isDefensivelyCopied() {
    List<String> mutable = new ArrayList<>();
    mutable.add("task1");
    BackendDescriptor descriptor = new BackendDescriptor("test", mutable, testProfile());

    // Modify the original list
    mutable.add("task2");

    // Descriptor should still only have the original task
    assertEquals(1, descriptor.supportedTasks().size());
    assertEquals("task1", descriptor.supportedTasks().get(0));

    // Returned list should be immutable
    assertThrows(
        UnsupportedOperationException.class, () -> descriptor.supportedTasks().add("task3"));
  }

  @Test
  void validDescriptor_preservesValues() {
    CapabilityProfile profile = testProfile();
    BackendDescriptor descriptor =
        new BackendDescriptor("my-provider", List.of("task1", "task2"), profile);
    assertEquals("my-provider", descriptor.providerId());
    assertEquals(List.of("task1", "task2"), descriptor.supportedTasks());
    assertSame(profile, descriptor.profile());
  }
}
