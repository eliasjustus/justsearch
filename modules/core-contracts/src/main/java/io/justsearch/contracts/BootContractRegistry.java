/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.contracts;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.ServiceLoader;

/**
 * Holds the list of {@link BootContractValidator} instances discovered at
 * startup (tempdoc 402 §3.2). Populated lazily via
 * {@link java.util.ServiceLoader} from every
 * {@code META-INF/services/io.justsearch.contracts.BootContractValidator}
 * file on the runtime classpath.
 */
public final class BootContractRegistry {

  private static final Object LOCK = new Object();
  private static List<BootContractValidator> validators;

  private BootContractRegistry() {}

  /**
   * Populate the registry by loading every
   * {@link BootContractValidator} reachable through {@code ServiceLoader}.
   * Idempotent — repeat calls are cheap no-ops.
   */
  public static void registerAll() {
    synchronized (LOCK) {
      if (validators != null) {
        return;
      }
      List<BootContractValidator> loaded = new ArrayList<>();
      for (BootContractValidator v : ServiceLoader.load(BootContractValidator.class)) {
        loaded.add(v);
      }
      validators = Collections.unmodifiableList(loaded);
    }
  }

  /**
   * Return the registered validators. Triggers lazy {@link #registerAll()}
   * on first call. The returned list is unmodifiable.
   */
  public static List<BootContractValidator> validators() {
    synchronized (LOCK) {
      if (validators == null) {
        registerAll();
      }
      return validators;
    }
  }

  /**
   * Test-only hook: clear the cached validator list so the next
   * {@link #validators()} call re-runs {@code ServiceLoader}. Package-private
   * — production code must not call this.
   */
  static void reset() {
    synchronized (LOCK) {
      validators = null;
    }
  }
}
