package io.justsearch.app.services.observability.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import io.justsearch.app.observability.runtime.RuntimeContext;
import io.justsearch.app.observability.runtime.RuntimeContextChangeRegistry;
import io.justsearch.app.observability.runtime.RuntimeContextHolder;
import io.justsearch.app.observability.runtime.SystemMode;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.TestResolvedConfigHelper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link RuntimeContextConfigBridge} per slice 440 §B.B (post-impl correction).
 *
 * <p>The bridge wires {@link ConfigStore} change events into
 * {@link RuntimeContextChangeRegistry} broadcasts so {@code automationEnabled} mutates
 * live (was static-at-boot before this fix).
 */
@DisplayName("RuntimeContextConfigBridge")
final class RuntimeContextConfigBridgeTest {

  private RuntimeContextConfigBridge bridge;

  @AfterEach
  void tearDown() {
    if (bridge != null) {
      bridge.stop();
    }
  }

  @Test
  @DisplayName("automation toggle fires a RuntimeContext replace broadcast")
  void automationToggleBroadcasts() {
    ConfigStore store =
        new ConfigStore(
            TestResolvedConfigHelper.fromEntries(
                Map.of("justsearch.ui.automation.enabled", "false")));
    RuntimeContextHolder holder =
        new RuntimeContextHolder(new RuntimeContext(SystemMode.PRODUCTION, false));
    RuntimeContextChangeRegistry registry = new RuntimeContextChangeRegistry();
    List<RuntimeContext> seen = new ArrayList<>();
    registry.subscribe(env -> seen.add((RuntimeContext) env.payload()));

    bridge = new RuntimeContextConfigBridge(holder, registry, store);

    store.update(
        TestResolvedConfigHelper.fromEntries(
            Map.of("justsearch.ui.automation.enabled", "true")));

    assertEquals(1, seen.size(), "exactly one broadcast on automation flip");
    RuntimeContext broadcast = seen.get(0);
    assertEquals(true, broadcast.automationEnabled());
    assertSame(SystemMode.PRODUCTION, broadcast.systemMode(), "systemMode preserved");
    assertEquals(broadcast, holder.current(), "holder is updated");
    assertEquals(1, registry.currentSeq(), "registry version bumped exactly once");
  }

  @Test
  @DisplayName("config change with no automation diff does NOT broadcast (dedup)")
  void noChangeDoesNotBroadcast() {
    ConfigStore store =
        new ConfigStore(
            TestResolvedConfigHelper.fromEntries(
                Map.of("justsearch.ui.automation.enabled", "false")));
    RuntimeContextHolder holder =
        new RuntimeContextHolder(new RuntimeContext(SystemMode.PRODUCTION, false));
    RuntimeContextChangeRegistry registry = new RuntimeContextChangeRegistry();
    List<RuntimeContext> seen = new ArrayList<>();
    registry.subscribe(env -> seen.add((RuntimeContext) env.payload()));

    bridge = new RuntimeContextConfigBridge(holder, registry, store);

    // Mutate a different (non-automation) config key — automation stays false.
    store.update(
        TestResolvedConfigHelper.fromEntries(
            Map.of(
                "justsearch.ui.automation.enabled", "false",
                "justsearch.data.dir", "/tmp/somewhere-else")));

    assertEquals(0, seen.size(), "no broadcast when automation is unchanged");
    assertEquals(0, registry.currentSeq(), "registry version unchanged");
  }

  @Test
  @DisplayName("multiple automation flips each fire a broadcast")
  void multipleFlipsBroadcastSeparately() {
    ConfigStore store =
        new ConfigStore(
            TestResolvedConfigHelper.fromEntries(
                Map.of("justsearch.ui.automation.enabled", "false")));
    RuntimeContextHolder holder =
        new RuntimeContextHolder(new RuntimeContext(SystemMode.PRODUCTION, false));
    RuntimeContextChangeRegistry registry = new RuntimeContextChangeRegistry();
    List<RuntimeContext> seen = new ArrayList<>();
    registry.subscribe(env -> seen.add((RuntimeContext) env.payload()));

    bridge = new RuntimeContextConfigBridge(holder, registry, store);

    store.update(
        TestResolvedConfigHelper.fromEntries(
            Map.of("justsearch.ui.automation.enabled", "true")));
    store.update(
        TestResolvedConfigHelper.fromEntries(
            Map.of("justsearch.ui.automation.enabled", "false")));
    store.update(
        TestResolvedConfigHelper.fromEntries(
            Map.of("justsearch.ui.automation.enabled", "true")));

    assertEquals(3, seen.size(), "each genuine flip broadcasts once");
    assertEquals(true, seen.get(0).automationEnabled());
    assertEquals(false, seen.get(1).automationEnabled());
    assertEquals(true, seen.get(2).automationEnabled());
  }

  @Test
  @DisplayName("stop() unregisters the listener; subsequent updates do not broadcast")
  void stopUnregisters() {
    ConfigStore store =
        new ConfigStore(
            TestResolvedConfigHelper.fromEntries(
                Map.of("justsearch.ui.automation.enabled", "false")));
    RuntimeContextHolder holder =
        new RuntimeContextHolder(new RuntimeContext(SystemMode.PRODUCTION, false));
    RuntimeContextChangeRegistry registry = new RuntimeContextChangeRegistry();
    List<RuntimeContext> seen = new ArrayList<>();
    registry.subscribe(env -> seen.add((RuntimeContext) env.payload()));

    bridge = new RuntimeContextConfigBridge(holder, registry, store);
    bridge.stop();
    bridge = null; // prevent double-stop in @AfterEach

    store.update(
        TestResolvedConfigHelper.fromEntries(
            Map.of("justsearch.ui.automation.enabled", "true")));

    assertEquals(0, seen.size(), "no broadcast after stop()");
  }
}
