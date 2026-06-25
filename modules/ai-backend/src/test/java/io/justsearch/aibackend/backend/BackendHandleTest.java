package io.justsearch.aibackend.backend;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.aibackend.backend.AiBackend;
import io.justsearch.aibackend.backend.BackendException;
import io.justsearch.aibackend.backend.BackendRequest;
import io.justsearch.aibackend.backend.BackendResponse;
import io.justsearch.aibackend.local.LocalIntentTranslatorConfig;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

final class BackendHandleTest {

  private static BackendDescriptor testDescriptor() {
    CapabilityProfile profile =
        new CapabilityProfile(4096, 1.0, SecurityTier.LOCAL_ONLY, List.of());
    return new BackendDescriptor("test", List.of("task1"), profile);
  }

  /** Stub provider for testing without Mockito. */
  private static class StubProvider implements BackendProvider {
    private final AiBackend backendToReturn;
    private final BackendException exceptionToThrow;
    final AtomicBoolean createCalled = new AtomicBoolean(false);

    StubProvider(AiBackend backendToReturn) {
      this.backendToReturn = backendToReturn;
      this.exceptionToThrow = null;
    }

    StubProvider(BackendException exceptionToThrow) {
      this.backendToReturn = null;
      this.exceptionToThrow = exceptionToThrow;
    }

    @Override
    public String providerId() {
      return "stub-test";
    }

    @Override
    public BackendDescriptor describe(LocalIntentTranslatorConfig config) {
      return testDescriptor();
    }

    @Override
    public AiBackend create(LocalIntentTranslatorConfig config) throws BackendException {
      createCalled.set(true);
      if (exceptionToThrow != null) {
        throw exceptionToThrow;
      }
      return backendToReturn;
    }
  }

  /** Minimal AiBackend stub for testing. */
  private static class StubBackend implements AiBackend {
    @Override
    public BackendResponse translate(BackendRequest request) {
      return new BackendResponse("{}");
    }

    @Override
    public Session createSession() {
      return null;
    }

    @Override
    public io.justsearch.aibackend.local.LocalIntentTranslatorV2.Provenance provenance() {
      return new io.justsearch.aibackend.local.LocalIntentTranslatorV2.Provenance(
          "test", "stub", 0);
    }

    @Override
    public void close() {}
  }

  @Test
  void nullProvider_throws() {
    assertThrows(
        IllegalArgumentException.class, () -> new BackendHandle(null, testDescriptor()));
  }

  @Test
  void nullDescriptor_throws() {
    StubProvider provider = new StubProvider(new StubBackend());
    assertThrows(IllegalArgumentException.class, () -> new BackendHandle(provider, null));
  }

  @Test
  void create_delegatesToProvider() throws BackendException {
    StubBackend backend = new StubBackend();
    StubProvider provider = new StubProvider(backend);
    LocalIntentTranslatorConfig config = LocalIntentTranslatorConfig.newBuilder().build();

    BackendHandle handle = new BackendHandle(provider, testDescriptor());
    AiBackend result = handle.create(config);

    assertSame(backend, result);
    assertTrue(provider.createCalled.get());
  }

  @Test
  void create_propagatesBackendException() {
    StubProvider provider = new StubProvider(new BackendException("test_error"));
    LocalIntentTranslatorConfig config = LocalIntentTranslatorConfig.newBuilder().build();

    BackendHandle handle = new BackendHandle(provider, testDescriptor());

    BackendException thrown =
        assertThrows(BackendException.class, () -> handle.create(config));
    assertEquals("test_error", thrown.getMessage());
  }

  @Test
  void validHandle_preservesValues() {
    StubProvider provider = new StubProvider(new StubBackend());
    BackendDescriptor descriptor = testDescriptor();

    BackendHandle handle = new BackendHandle(provider, descriptor);

    assertSame(provider, handle.provider());
    assertSame(descriptor, handle.descriptor());
  }

  @Test
  void create_returnsNullWhenProviderReturnsNull() throws BackendException {
    StubProvider provider = new StubProvider((AiBackend) null);
    LocalIntentTranslatorConfig config = LocalIntentTranslatorConfig.newBuilder().build();

    BackendHandle handle = new BackendHandle(provider, testDescriptor());
    AiBackend result = handle.create(config);

    assertNull(result);
    assertTrue(provider.createCalled.get());
  }
}
