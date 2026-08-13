/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Late-bound bridge for the normal-quit shutdown (tempdoc 805 G.1), mirroring {@link
 * UpgradeShutdownBridge}: the API starts before Worker connection, while the ordered shutdown owner
 * can only be assembled after all resources exist.
 */
public final class LifecycleShutdownBridge implements Runnable {
  private final AtomicReference<Runnable> delegate = new AtomicReference<>();

  public void install(Runnable action) {
    if (!delegate.compareAndSet(null, Objects.requireNonNull(action))) {
      throw new IllegalStateException("lifecycle shutdown action already installed");
    }
  }

  @Override
  public void run() {
    Runnable action = delegate.get();
    if (action == null) {
      throw new IllegalStateException("lifecycle shutdown action is not ready");
    }
    action.run();
  }
}
