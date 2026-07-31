/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Late-bound bridge: the API starts before Worker connection, while the ordered shutdown owner can
 * only be assembled after all resources exist.
 */
public final class UpgradeShutdownBridge implements UpgradeShutdownAction {
  private final AtomicReference<UpgradeShutdownAction> delegate = new AtomicReference<>();

  public void install(UpgradeShutdownAction action) {
    if (!delegate.compareAndSet(null, Objects.requireNonNull(action))) {
      throw new IllegalStateException("upgrade shutdown action already installed");
    }
  }

  @Override
  public void shutdown(String preparationId, String shutdownNonce) {
    UpgradeShutdownAction action = delegate.get();
    if (action == null) {
      throw new IllegalStateException("upgrade shutdown action is not ready");
    }
    action.shutdown(preparationId, shutdownNonce);
  }
}
