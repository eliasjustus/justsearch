/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

/** Ordered Head shutdown requested after an upgrade preparation has been committed. */
@FunctionalInterface
public interface UpgradeShutdownAction {
  void shutdown(String preparationId, String shutdownNonce);
}
