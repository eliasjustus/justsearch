/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.identity;

import java.util.Collection;
import java.util.Optional;

/**
 * Durable, path-free document identity authority.
 *
 * <p>The store maps the SHA-256 hash of a normalized absolute path to one content-independent
 * document uid. The mapping deliberately outlives path-resolution retention, deletion, and index
 * generation rebuilds.
 */
public interface DocumentIdentityStore {

  /**
   * Fail-closed default for deferred compositions and tests that do not wire the durable store.
   * Production indexing must never mint identity from a second authority.
   */
  DocumentIdentityStore UNAVAILABLE = new DocumentIdentityStore() {
    private IllegalStateException unavailable() {
      return new IllegalStateException("Document identity store is unavailable");
    }

    @Override
    public Identity resolve(String pathHash, long nowMs) {
      throw unavailable();
    }

    @Override
    public Identity importExisting(String pathHash, String docUid, long nowMs) {
      throw unavailable();
    }

    @Override
    public void importExisting(Collection<ImportedIdentity> identities, long nowMs) {
      throw unavailable();
    }

    @Override
    public RekeyResult rekey(String oldPathHash, String newPathHash, long nowMs) {
      throw unavailable();
    }

    @Override
    public Optional<Identity> lookup(String pathHash) {
      throw unavailable();
    }
  };

  /** Returns the existing identity for {@code pathHash}, or atomically mints and persists one. */
  Identity resolve(String pathHash, long nowMs);

  /**
   * Imports a uid already stored in the active index when neither its path hash nor uid is known.
   * Any existing store mapping remains authoritative during normal or mid-migration restarts.
   */
  Identity importExisting(String pathHash, String docUid, long nowMs);

  /** Imports one active-index snapshot atomically. */
  void importExisting(Collection<ImportedIdentity> identities, long nowMs);

  /**
   * Moves an existing identity to a renamed path hash without changing its uid or first-seen time.
   *
   * @return whether the row moved, was already converged at the destination, or was not found
   */
  RekeyResult rekey(String oldPathHash, String newPathHash, long nowMs);

  /** Looks up an identity by path hash. */
  Optional<Identity> lookup(String pathHash);

  /** SHA-256 hex over the already-normalized absolute path. */
  static String pathHash(String normalizedPath) {
    return PathHash.sha256(normalizedPath);
  }

  /** Persisted identity row. */
  record Identity(String pathHash, String docUid, long firstSeenAtMs, long lastSeenAtMs) {}

  /** Path-free row supplied by the serving-index bootstrap scan. */
  record ImportedIdentity(String pathHash, String docUid) {}

  /** Durable outcome of an idempotent path rekey. */
  enum RekeyResult {
    MOVED,
    ALREADY_AT_DESTINATION,
    NOT_FOUND
  }
}
