/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import io.javalin.http.Context;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.ResolvedConfigBuilder;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The {@code justsearch.context.size} row of {@code /api/debug/effective-config} reports the
 * RESOLVER's provenance (tempdoc 883 decision 4).
 *
 * <p>It used to report a {@code justsearch.context.size.source} marker sysprop, which existed only
 * to un-tell the lie told by promoting {@code settings.json} to a system property: a GUI value
 * resolved at ordinal 500 and the row said {@code jvm_arg}. Both are deleted, so the row must now
 * say {@code auto_detected} for a derived window and {@code settings.json} for a user override —
 * and must never say {@code jvm_arg} for either.
 */
@DisplayName("effective-config justsearch.context.size row")
final class EffectiveConfigContextSizeSourceTest {

  private static final String KEY = "justsearch.context.size";
  private static final String MARKER_PROP = "justsearch.context.size.source";

  @AfterEach
  void clearMarker() {
    System.clearProperty(MARKER_PROP);
    System.clearProperty(KEY);
  }

  @Test
  @DisplayName("a derived window reports auto_detected / hardware_probe at ordinal 150")
  void derivedWindowReportsAutoDetected() {
    ResolvedConfigBuilder builder = new ResolvedConfigBuilder();
    builder.contributeAutoDetected(Map.of(KEY, "32768"));

    Map<String, Object> row = rowFor(new ConfigStore(builder.build()));

    assertEquals(32768, row.get("value"));
    assertEquals("auto_detected", row.get("source"));
    Map<?, ?> details = (Map<?, ?>) row.get("details");
    assertEquals(ResolvedConfigBuilder.ORDINAL_AUTO_DETECT, details.get("sourceOrdinal"));
    assertEquals("hardware_probe", details.get("sourceDetail"));
  }

  @Test
  @DisplayName("a user override reports settings.json at ordinal 300, never jvm_arg")
  void userOverrideReportsSettingsJson() {
    ResolvedConfigBuilder builder = new ResolvedConfigBuilder();
    builder.contributeAutoDetected(Map.of(KEY, "32768"));
    builder.putSettings(KEY, "16384");

    Map<String, Object> row = rowFor(new ConfigStore(builder.build()));

    assertEquals(16384, row.get("value"));
    assertEquals("settings.json", row.get("source"));
    assertEquals(
        ResolvedConfigBuilder.ORDINAL_SETTINGS_JSON,
        ((Map<?, ?>) row.get("details")).get("sourceOrdinal"));
  }

  @Test
  @DisplayName("an operator -D reports jvm_arg — the chain, not a marker, decides")
  void operatorOverrideReportsJvmArg() {
    System.setProperty(KEY, "4096");
    ResolvedConfigBuilder builder = new ResolvedConfigBuilder();
    builder.contributeAutoDetected(Map.of(KEY, "32768"));
    builder.putSettings(KEY, "16384");
    builder.contributeEnvRegistry();

    Map<String, Object> row = rowFor(new ConfigStore(builder.build()));

    assertEquals(4096, row.get("value"));
    assertEquals("jvm_arg", row.get("source"));
  }

  @Test
  @DisplayName("a stale ui_settings marker cannot influence the row any more")
  void markerIsIgnored() {
    System.setProperty(MARKER_PROP, "ui_settings");
    ResolvedConfigBuilder builder = new ResolvedConfigBuilder();
    builder.contributeAutoDetected(Map.of(KEY, "32768"));

    Map<String, Object> row = rowFor(new ConfigStore(builder.build()));

    assertEquals("auto_detected", row.get("source"));
    Map<?, ?> details = (Map<?, ?>) row.get("details");
    assertFalse(
        details.containsKey("uiOwnershipProp"),
        "the marker vocabulary is deleted; reporting it would resurrect the second authority");
    assertFalse(details.containsKey("owner"));
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> rowFor(ConfigStore store) {
    EffectiveConfigController controller =
        new EffectiveConfigController(() -> 8081, null, null, null, Path.of("index"), store);

    Context ctx = mock(Context.class);
    AtomicReference<Object> captured = new AtomicReference<>();
    doAnswer(
            inv -> {
              captured.set(inv.getArgument(0));
              return ctx;
            })
        .when(ctx)
        .json(any(Object.class));

    controller.handleGetEffectiveConfig(ctx);

    Map<String, Object> root = (Map<String, Object>) captured.get();
    assertNotNull(root, "the controller must have produced a response");
    List<Map<String, Object>> keys = (List<Map<String, Object>>) root.get("keys");
    return keys.stream()
        .filter(k -> KEY.equals(k.get("key")))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no " + KEY + " row in the report"));
  }
}
