/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.api.encryption;

import java.util.List;
import java.util.Map;

/**
 * Tempdoc 629 (#1) — a store's read-side for the encrypted backup: returns its content as a list of
 * JSON-able entries (decrypted, read while the data key is unlocked). Wired as a lambda over the store's
 * existing read APIs at the store's construction site and held by its {@link StoreDescriptor}, so the
 * backup export iterates the descriptor list with no per-store knowledge.
 */
@FunctionalInterface
public interface BackupSource {
  List<Map<String, Object>> read();
}
