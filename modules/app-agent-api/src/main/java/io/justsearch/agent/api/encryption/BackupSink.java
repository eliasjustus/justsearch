/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.api.encryption;

import java.util.List;
import java.util.Map;

/**
 * Tempdoc 629 (#E) — a store's write-side for encrypted-backup IMPORT: restores a list of JSON-able
 * entries (decrypted from the backup container) into the store via its existing write APIs, which re-seal
 * under the LOCAL data key. Wired as a lambda at the store's construction site and held by its {@link
 * StoreDescriptor}, so import iterates the descriptor list with no per-store knowledge — the read-side
 * twin of {@link BackupSource}.
 *
 * <p>Collision policy is <b>skip-existing</b> (an entry whose id/session already exists is left
 * untouched). Returns the number of entries actually written.
 */
@FunctionalInterface
public interface BackupSink {
  int restore(List<Map<String, Object>> entries);
}
