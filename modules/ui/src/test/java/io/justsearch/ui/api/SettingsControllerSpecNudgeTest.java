package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.javalin.http.Context;
import io.justsearch.app.services.settings.UiSettingsStore;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tempdoc 737 Phase 1: a persisted settings write that CHANGES {@code chatEnabled} must fire the
 * spec-write nudge (the runtime reconciler's {@code specChanged()} hook) exactly once; writes that
 * do not change it must not fire. Guards the "spec writes converge now, not at next boot" seam —
 * the nudge had zero production callers when first implemented (wrong-gate class).
 */
@DisplayName("SettingsController — chatEnabled spec-write nudge")
final class SettingsControllerSpecNudgeTest {

  @TempDir Path tmp;

  private UiSettingsStore store;
  private AtomicInteger nudges;
  private SettingsController controller;

  @BeforeEach
  void setUp() {
    store = new UiSettingsStore(UiSettingsStore.PersistenceMode.READ_WRITE, tmp.resolve("settings.json"));
    nudges = new AtomicInteger();
    controller = new SettingsController(store, tmp, null, null, nudges::incrementAndGet);
  }

  private Context contextWithBody(String body) {
    Context ctx = mock(Context.class);
    when(ctx.body()).thenReturn(body);
    when(ctx.json(any())).thenReturn(ctx);
    when(ctx.status(org.mockito.ArgumentMatchers.anyInt())).thenReturn(ctx);
    return ctx;
  }

  @Test
  @DisplayName("write that sets chatEnabled true (from unset) fires the nudge once")
  void changedChatEnabledFires() {
    controller.handleUpdateSettingsV2(contextWithBody("{\"ui\":{\"chatEnabled\":true}}"));
    assertEquals(1, nudges.get());
    assertEquals(Boolean.TRUE, store.load().getChatEnabled());
  }

  @Test
  @DisplayName("write that does not touch chatEnabled does not fire")
  void unrelatedWriteDoesNotFire() {
    controller.handleUpdateSettingsV2(contextWithBody("{\"ui\":{\"theme\":\"dark\"}}"));
    assertEquals(0, nudges.get());
  }

  @Test
  @DisplayName("write repeating the current chatEnabled value does not fire")
  void unchangedValueDoesNotFire() {
    controller.handleUpdateSettingsV2(contextWithBody("{\"ui\":{\"chatEnabled\":true}}"));
    controller.handleUpdateSettingsV2(contextWithBody("{\"ui\":{\"chatEnabled\":true}}"));
    assertEquals(1, nudges.get());
  }

  @Test
  @DisplayName("toggling back fires again — one nudge per actual change")
  void toggleFiresPerChange() {
    controller.handleUpdateSettingsV2(contextWithBody("{\"ui\":{\"chatEnabled\":true}}"));
    controller.handleUpdateSettingsV2(contextWithBody("{\"ui\":{\"chatEnabled\":false}}"));
    assertEquals(2, nudges.get());
  }
}
