/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.api.encryption;

import java.nio.file.Path;

/**
 * Tempdoc 629 (#1) — a runtime entry in the authoritative store list: a {@link StoreCatalog} member, the
 * resolved on-disk path, and a {@link BackupSource} read-side (a lambda over the constructed store's
 * existing APIs). Aggregated in {@code HeadAssembly} as each AUTHORED store is built, and iterated by the
 * encrypted-backup export — so "which stores are AUTHORED" lives in ONE list, not duplicated per obligation.
 *
 * <p>The {@link BackupSink} is the import write-side (629 Phase E); it may be a no-op for stores not
 * yet restorable.
 */
public record StoreDescriptor(
    StoreCatalog store, Path dataPath, BackupSource source, BackupSink sink) {}
