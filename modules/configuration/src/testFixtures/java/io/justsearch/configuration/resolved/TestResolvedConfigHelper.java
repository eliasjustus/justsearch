package io.justsearch.configuration.resolved;

import java.util.Map;

/**
 * Shared helper for constructing {@link ResolvedConfig} and {@link ConfigStore} in tests.
 *
 * <p>Lives in the {@code testFixtures} source set so any module depending on
 * {@code testFixtures(project(":modules:configuration"))} can use it without starting HeadlessApp.
 */
public final class TestResolvedConfigHelper {

  private TestResolvedConfigHelper() {}

  /** Builds a ResolvedConfig with all defaults (no env/sysprop/YAML contributions). */
  public static ResolvedConfig withDefaults() {
    return ResolvedConfig.builder().build();
  }

  /**
   * Builds a ResolvedConfig with programmatic defaults overridden by the given entries.
   *
   * @param entries key-value pairs contributed at ordinal 100 (default)
   */
  public static ResolvedConfig fromEntries(Map<String, String> entries) {
    ResolvedConfigBuilder b = ResolvedConfig.builder();
    entries.forEach(b::putDefault);
    return b.build();
  }

  /**
   * Creates a ConfigStore with all-default config and publishes it globally.
   *
   * <p>Callers should reset the global after their test (e.g., in {@code @AfterEach}).
   */
  public static ConfigStore storeWithDefaults() {
    ConfigStore store = new ConfigStore(withDefaults());
    ConfigStore.setGlobal(store);
    return store;
  }

  /**
   * Creates a ConfigStore that reads system properties and env vars (via EnvRegistry),
   * then publishes it globally. Use this in tests that set system properties before
   * building the config.
   */
  public static ConfigStore storeFromEnvironment() {
    ResolvedConfigBuilder b = ResolvedConfig.builder();
    b.contributeEnvRegistry();
    ConfigStore store = new ConfigStore(b.build());
    ConfigStore.setGlobal(store);
    return store;
  }

  /**
   * Restores the previous ConfigStore global state. If {@code previous} is null, clears the global
   * entirely (returning to the uninitialized state).
   *
   * <p>Use in {@code finally} blocks or {@code @AfterEach} to undo {@link #storeWithDefaults()} or
   * {@link #storeFromEnvironment()}.
   */
  public static void restoreGlobal(ConfigStore previous) {
    if (previous != null) {
      ConfigStore.setGlobal(previous);
    } else {
      ConfigStore.clearGlobal();
    }
  }
}
