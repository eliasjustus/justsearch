package io.justsearch.aibackend.backend.providers;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.aibackend.backend.BackendDescriptor;
import io.justsearch.aibackend.backend.SecurityTier;
import io.justsearch.aibackend.backend.AiBackend;
import io.justsearch.aibackend.backend.BackendException;
import io.justsearch.aibackend.backend.DeterministicBackend;
import io.justsearch.aibackend.local.LocalIntentTranslatorConfig;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

final class DeterministicBackendProviderTest {

  private DeterministicBackendProvider provider;
  private LocalIntentTranslatorConfig config;

  @BeforeEach
  void setUp() {
    provider = new DeterministicBackendProvider();
    config = LocalIntentTranslatorConfig.newBuilder().build();
  }

  @Test
  void providerId_returnsStub() {
    assertEquals("stub", provider.providerId());
  }

  @Test
  void describe_returnsCorrectSupportedTasks() {
    BackendDescriptor descriptor = provider.describe(config);
    assertEquals(
        List.of("intent_v1", "summary_v1", "embed_v1", "classify_v1"),
        descriptor.supportedTasks());
  }

  @Test
  void describe_hasLocalOnlySecurityTier() {
    BackendDescriptor descriptor = provider.describe(config);
    assertEquals(SecurityTier.LOCAL_ONLY, descriptor.profile().securityTier());
  }

  @Test
  void describe_usesConfigContextLength() {
    // Default builder uses contextLength=4096
    BackendDescriptor descriptor = provider.describe(config);
    assertEquals(4096, descriptor.profile().contextLength());
  }

  @Test
  void describe_hasFixedThroughput() {
    BackendDescriptor descriptor = provider.describe(config);
    assertEquals(1.0, descriptor.profile().maxThroughput());
  }

  @Test
  void describe_hasDeterministicCapability() {
    BackendDescriptor descriptor = provider.describe(config);
    assertEquals(List.of("deterministic"), descriptor.profile().capabilities());
  }

  @Test
  void create_returnsDeterministicBackend() throws BackendException {
    AiBackend backend = provider.create(config);
    assertNotNull(backend);
    assertInstanceOf(DeterministicBackend.class, backend);
  }
}
