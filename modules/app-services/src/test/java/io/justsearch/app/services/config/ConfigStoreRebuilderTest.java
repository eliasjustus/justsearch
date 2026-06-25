package io.justsearch.app.services.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.configuration.resolved.ResolvedConfigBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link ConfigStoreRebuilder} — verifies that rebuild picks up runtime sysprop changes.
 */
@DisplayName("ConfigStoreRebuilder")
final class ConfigStoreRebuilderTest {

  private static final String TEST_SYSPROP = "justsearch.api.port";

  @AfterEach
  void cleanup() {
    System.clearProperty(TEST_SYSPROP);
  }

  @Test
  @DisplayName("rebuild picks up sysprop change written after initial build")
  void rebuildPicksUpSyspropChange() {
    // Build initial config with default port
    ResolvedConfigBuilder builder = new ResolvedConfigBuilder();
    ResolvedConfig initial = builder.build();
    ConfigStore store = new ConfigStore(initial);

    int originalPort = store.get().ports().apiPort();

    // Simulate runtime sysprop write (as RuntimeActivationService does)
    System.setProperty(TEST_SYSPROP, "7777");

    // Rebuild should pick up the new sysprop value
    ConfigStoreRebuilder.rebuild(store, null);

    assertEquals(7777, store.get().ports().apiPort(), "Rebuild should reflect new sysprop value");
  }

  @Test
  @DisplayName("rebuild with null store is a safe no-op")
  void rebuildWithNullStoreIsNoOp() {
    // Should not throw
    ConfigStoreRebuilder.rebuild(null, null);
  }

  @Test
  @DisplayName("rebuild preserves config completeness")
  void rebuildPreservesCompleteness() {
    ResolvedConfigBuilder builder = new ResolvedConfigBuilder();
    builder.contributeEnvRegistry();
    ResolvedConfig initial = builder.build();
    ConfigStore store = new ConfigStore(initial);

    ConfigStoreRebuilder.rebuild(store, null);

    ResolvedConfig rebuilt = store.get();
    assertNotNull(rebuilt.paths(), "paths sub-record must exist after rebuild");
    assertNotNull(rebuilt.ports(), "ports sub-record must exist after rebuild");
    assertNotNull(rebuilt.ai(), "ai sub-record must exist after rebuild");
    assertNotNull(rebuilt.index(), "index sub-record must exist after rebuild");
    assertNotNull(rebuilt.search(), "search sub-record must exist after rebuild");
  }
}
