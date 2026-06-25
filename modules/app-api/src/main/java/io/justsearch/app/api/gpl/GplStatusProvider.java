/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.gpl;

/**
 * Read-only status view of the GPL job coordinator. Implemented by {@code GplJobCoordinator} in
 * {@code app-services}; consumed by the UI layer without importing the concrete class.
 */
public interface GplStatusProvider {

  /** Returns a point-in-time consistent snapshot of the current GPL job state. */
  GplJobStatus getStatus();
}
