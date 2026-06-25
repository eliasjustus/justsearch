package io.justsearch.app.config;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ConfigManagerBootstrapTest {

  @Test
  void registerListenerFiresImmediately() {
    ConfigManagerBootstrap bootstrap = new ConfigManagerBootstrap();
    ConfigSnapshot current = bootstrap.currentSnapshot();
    assertNotNull(current);

    AtomicReference<ConfigSnapshot> listenerSnapshot = new AtomicReference<>();
    bootstrap.registerListener(listenerSnapshot::set, true);

    assertSame(current, listenerSnapshot.get());
  }

  @Test
  void refreshNotifiesListenersAndSwallowsErrors() {
    ConfigManagerBootstrap bootstrap = new ConfigManagerBootstrap();
    ConfigSnapshot initial = bootstrap.currentSnapshot();

    AtomicReference<ConfigSnapshot> updated = new AtomicReference<>();
    bootstrap.registerListener(snapshot -> { throw new IllegalStateException("boom"); }, false);
    bootstrap.registerListener(updated::set, false);

    bootstrap.refresh();

    assertNotNull(updated.get());
    assertNotSame(initial, updated.get(), "refresh() should create a new snapshot");
  }
}
