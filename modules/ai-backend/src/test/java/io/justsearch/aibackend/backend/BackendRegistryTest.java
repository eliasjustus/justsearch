package io.justsearch.aibackend.backend;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.aibackend.backend.AiBackend;
import io.justsearch.aibackend.backend.BackendException;
import io.justsearch.aibackend.local.LocalIntentTranslatorConfig;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

final class BackendRegistryTest {

  private BackendRegistry registry;
  private LocalIntentTranslatorConfig config;

  @BeforeEach
  void setUp() {
    registry = new BackendRegistry();
    config = LocalIntentTranslatorConfig.newBuilder().build();
  }

  /** Test provider with REMOTE_TRUSTED security tier for testing blocking logic. */
  private static class TestRemoteProvider implements BackendProvider {
    @Override
    public String providerId() {
      return "test-remote";
    }

    @Override
    public BackendDescriptor describe(LocalIntentTranslatorConfig config) {
      return new BackendDescriptor(
          "test-remote",
          List.of(),
          new CapabilityProfile(1000, 1.0, SecurityTier.REMOTE_TRUSTED, List.of()));
    }

    @Override
    public AiBackend create(LocalIntentTranslatorConfig config) {
      return null;
    }
  }

  /** Test provider with REMOTE_UNTRUSTED security tier. */
  private static class TestUntrustedProvider implements BackendProvider {
    @Override
    public String providerId() {
      return "test-untrusted";
    }

    @Override
    public BackendDescriptor describe(LocalIntentTranslatorConfig config) {
      return new BackendDescriptor(
          "test-untrusted",
          List.of(),
          new CapabilityProfile(1000, 1.0, SecurityTier.REMOTE_UNTRUSTED, List.of()));
    }

    @Override
    public AiBackend create(LocalIntentTranslatorConfig config) {
      return null;
    }
  }

  @Test
  void constructor_loadsProvidersViaServiceLoader() {
    // ServiceLoader should discover registered providers (llama provider removed)
    Collection<BackendDescriptor> descriptors = registry.listDescriptors(config);
    Set<String> expectedProviders = Set.of("stub");
    Set<String> actualProviders =
        descriptors.stream().map(BackendDescriptor::providerId).collect(Collectors.toSet());
    assertEquals(
        expectedProviders,
        actualProviders,
        "Provider set mismatch. If providers changed, update expectedProviders.");
  }

  @Nested
  class Resolve {

    @Test
    void findsProviderById_stub() throws BackendException {
      Optional<BackendHandle> handle = registry.resolve("stub", config);
      assertTrue(handle.isPresent());
      assertEquals("stub", handle.get().descriptor().providerId());
    }

    @Test
    void llama_returnsEmpty_afterRemoval() throws BackendException {
      Optional<BackendHandle> handle = registry.resolve("llama", config);
      assertFalse(handle.isPresent(), "llama provider was removed");
    }

    @Test
    void llamaAliases_returnEmpty() throws BackendException {
      assertFalse(registry.resolve("ggml", config).isPresent());
      assertFalse(registry.resolve("llama-cpp", config).isPresent());
      assertFalse(registry.resolve("llama_cpp", config).isPresent());
    }

    @Test
    void unknownProvider_returnsEmpty() throws BackendException {
      Optional<BackendHandle> handle = registry.resolve("nonexistent", config);
      assertFalse(handle.isPresent());
    }

    @Test
    void allowsLocalOnlyWhenRemoteDisabled() throws BackendException {
      // Default builder has allowRemoteExecution=false
      // Both stub and llama are LOCAL_ONLY, so they should be allowed
      Optional<BackendHandle> stub = registry.resolve("stub", config);
      assertTrue(stub.isPresent());
      assertEquals(SecurityTier.LOCAL_ONLY, stub.get().descriptor().profile().securityTier());
    }

    @Test
    void blocksRemoteWhenNotAllowed() {
      BackendRegistry testRegistry =
          new BackendRegistry(Map.of("test-remote", new TestRemoteProvider()));
      LocalIntentTranslatorConfig restrictedConfig =
          LocalIntentTranslatorConfig.newBuilder().allowRemoteExecution(false).build();

      BackendException ex =
          assertThrows(
              BackendException.class, () -> testRegistry.resolve("test-remote", restrictedConfig));
      assertTrue(ex.getMessage().contains("blocked"));
      assertTrue(ex.getMessage().contains("REMOTE_TRUSTED"));
    }

    @Test
    void blocksUntrustedRemoteWhenNotAllowed() {
      BackendRegistry testRegistry =
          new BackendRegistry(Map.of("test-untrusted", new TestUntrustedProvider()));
      LocalIntentTranslatorConfig restrictedConfig =
          LocalIntentTranslatorConfig.newBuilder().allowRemoteExecution(false).build();

      BackendException ex =
          assertThrows(
              BackendException.class,
              () -> testRegistry.resolve("test-untrusted", restrictedConfig));
      assertTrue(ex.getMessage().contains("blocked"));
      assertTrue(ex.getMessage().contains("REMOTE_UNTRUSTED"));
    }

    @Test
    void allowsUntrustedRemoteWhenEnabled() throws BackendException {
      BackendRegistry testRegistry =
          new BackendRegistry(Map.of("test-untrusted", new TestUntrustedProvider()));
      LocalIntentTranslatorConfig permissiveConfig =
          LocalIntentTranslatorConfig.newBuilder().allowRemoteExecution(true).build();

      Optional<BackendHandle> handle = testRegistry.resolve("test-untrusted", permissiveConfig);
      assertTrue(handle.isPresent());
      assertEquals(
          SecurityTier.REMOTE_UNTRUSTED, handle.get().descriptor().profile().securityTier());
    }

    @Test
    void allowsRemoteWhenEnabled() throws BackendException {
      BackendRegistry testRegistry =
          new BackendRegistry(Map.of("test-remote", new TestRemoteProvider()));
      LocalIntentTranslatorConfig permissiveConfig =
          LocalIntentTranslatorConfig.newBuilder().allowRemoteExecution(true).build();

      Optional<BackendHandle> handle = testRegistry.resolve("test-remote", permissiveConfig);
      assertTrue(handle.isPresent());
      assertEquals(SecurityTier.REMOTE_TRUSTED, handle.get().descriptor().profile().securityTier());
    }
  }

  @Nested
  class ListDescriptors {

    @Test
    void returnsAllProviders() {
      Collection<BackendDescriptor> descriptors = registry.listDescriptors(config);
      Set<String> expectedProviders = Set.of("stub");
      Set<String> actualProviders =
          descriptors.stream().map(BackendDescriptor::providerId).collect(Collectors.toSet());
      assertEquals(
          expectedProviders,
          actualProviders,
          "Provider set mismatch. If providers changed, update expectedProviders.");
    }

    @Test
    void collectionIsUnmodifiable() {
      Collection<BackendDescriptor> descriptors = registry.listDescriptors(config);
      assertThrows(
          UnsupportedOperationException.class,
          () -> descriptors.add(
              new BackendDescriptor(
                  "fake",
                  java.util.List.of(),
                  new CapabilityProfile(1000, 1.0, SecurityTier.LOCAL_ONLY, java.util.List.of()))));
    }
  }
}
